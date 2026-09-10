package expo.modules.glasssdk.glass.vendors.cyan

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import expo.modules.glasssdk.util.OpusOggWrapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Handles WiFi P2P peer discovery → connection → HTTP download flow for Cyan glasses.
 *
 * Flow (mirrors the demo's startDataDownload()):
 * 1. registerReceiver() — listen for P2P state/peer changes
 * 2. discoverPeers() — find the glasses as a WiFi Direct peer
 * 3. onPeersChanged → connectToDevice(peer)
 * 4. onConnected(WifiP2pInfo) → get groupOwnerAddress → resolveGlassesIp → downloadFiles
 * 5. glassesControl(0x02,0x01,0x04) is sent in parallel to tell glasses to bring up WiFi
 *
 * The glasses' IP comes from:
 *   a) BLE notify type 0x08 (parsed by CyanAdapter, passed via setDeviceIp)
 *   b) WifiP2pInfo.groupOwnerAddress (if glasses are the GO)
 *   c) Subnet scan of the P2P network
 */
class CyanFileSyncManager(
    private val context: Context,
    private val onResetDeviceP2p: () -> Unit,
    private val onEvent: (SyncEvent) -> Unit
) {
    companion object {
        private const val TAG = "[Glass:CyanSync]"
        private const val MEDIA_CONFIG_PATH = "/files/media.config"
        private const val DISCOVERY_TIMEOUT_MS = 16_000L
        private const val CONNECT_TIMEOUT_MS = 5_000L
    }

    sealed class SyncEvent {
        object Started : SyncEvent()
        data class Progress(val current: Int, val total: Int) : SyncEvent()
        data class FileSynced(val path: String, val type: String) : SyncEvent()
        data class Completed(val totalFiles: Int, val totalBytes: Long, val success: Boolean, val error: String? = null) : SyncEvent()
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    // State
    private var bleIp: String? = null           // IP from BLE notify 0x08
    private var groupOwnerIp: String? = null    // IP from WifiP2pInfo after connection
    private var phoneIsGroupOwner = false
    private var p2pNetwork: Network? = null
    private var p2pConnected = false
    private var p2pConnecting = false
    private var connectRetryTarget: WifiP2pDevice? = null
    private var connectRetryCount = 0
    private var discoveryRetryCount = 0
    private var downloading = false
    private var downloadAttemptJob: Job? = null
    private var abortReason: String? = null

    // P2P
    private val p2pManager: WifiP2pManager =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var p2pReceiver: BroadcastReceiver? = null

    fun isDownloading(): Boolean = downloading

    fun cancelWithError(reason: String) {
        // First reason wins: once we're aborting, ignore later calls. This keeps a
        // user "Cancelled" from being overwritten by a device error (e.g. errorCode=255)
        // that the glasses emit as a side effect of our teardown.
        if (abortReason != null) {
            Log.d(TAG, "cancelWithError ignored — already aborting ($abortReason), ignoring: $reason")
            return
        }
        abortReason = reason
        if (downloadAttemptJob?.isActive == true) {
            downloadAttemptJob?.cancel()
            // coroutine exits via isActive check in resolveGlassesIp → fires SyncEvent.Completed via null-IP path
        } else {
            onEvent(SyncEvent.Completed(0, 0L, false, reason))
            cleanup()
        }
    }

    fun setDeviceIp(ip: String) {
        bleIp = ip
        Log.i(TAG, "BLE reported device IP: $ip")
        // If P2P is already connected, this arriving late can trigger the download
        if (p2pConnected && downloadAttemptJob?.isActive != true) {
            maybeStartDownload("BLE-late")
        }
    }

    var isOtaMode = false
    private var otaNetworkCallback: ((Network) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun startSync(otaMode: Boolean = false, onOtaNetworkReady: ((Network) -> Unit)? = null) {
        isOtaMode = otaMode
        otaNetworkCallback = onOtaNetworkReady
        if (!otaMode) onEvent(SyncEvent.Started)

        // Reset state
        bleIp = null
        groupOwnerIp = null
        phoneIsGroupOwner = false
        p2pNetwork = null
        p2pConnected = false
        p2pConnecting = false
        connectRetryTarget = null
        connectRetryCount = 0
        discoveryRetryCount = 0
        downloading = false
        abortReason = null
        downloadAttemptJob?.cancel()
        downloadAttemptJob = null
        handler.removeCallbacksAndMessages(null)

        initP2pChannel()
        registerP2pReceiver()

        Log.i(TAG, "Starting peer discovery")
        startDiscovery()
    }

    private fun initP2pChannel() {
        p2pChannel?.close()
        p2pChannel = p2pManager.initialize(context, Looper.getMainLooper(), object : WifiP2pManager.ChannelListener {
            override fun onChannelDisconnected() {
                Log.w(TAG, "P2P channel disconnected")
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        handler.postDelayed(discoveryTimeoutRunnable, DISCOVERY_TIMEOUT_MS)
        p2pManager.discoverPeers(p2pChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "discoverPeers started") }
            override fun onFailure(reason: Int) { Log.e(TAG, "discoverPeers failed: $reason") }
        })
    }

    @SuppressLint("MissingPermission")
    private fun stopAndRestartDiscovery() {
        p2pManager.stopPeerDiscovery(p2pChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "stopPeerDiscovery — restarting")
                startDiscovery()
            }
            override fun onFailure(reason: Int) {
                Log.d(TAG, "stopPeerDiscovery failed: $reason — restarting anyway")
                startDiscovery()
            }
        })
    }

    // Fires after 16s if no peer found; resets glasses P2P and retries discovery once
    private val discoveryTimeoutRunnable = Runnable {
        Log.d(TAG, "Discovery timeout (retry=$discoveryRetryCount)")
        if (discoveryRetryCount < 1) {
            discoveryRetryCount++
            Log.d(TAG, "Resetting device P2P and retrying discovery")
            onResetDeviceP2p()
            initP2pChannel()
            stopAndRestartDiscovery()
        } else {
            Log.e(TAG, "Discovery timeout — giving up after retry")
            onEvent(SyncEvent.Completed(0, 0L, false, "Discovery timeout — glasses not found"))
            cleanup()
        }
    }

    // Fires after 5s if connect doesn't complete; retries connect once
    private val connectTimeoutRunnable = Runnable {
        p2pConnecting = false
        val target = connectRetryTarget
        if (target != null && connectRetryCount < 1) {
            connectRetryCount++
            Log.d(TAG, "Connect timeout — retrying (attempt $connectRetryCount)")
            connectToDevice(target)
        } else {
            Log.e(TAG, "Connect timeout — giving up")
            onEvent(SyncEvent.Completed(0, 0L, false, "Connect timeout — could not connect to glasses"))
            cleanup()
        }
    }

    private fun registerP2pReceiver() {
        unregisterP2pReceiver()
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        p2pReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        Log.d(TAG, "P2P peers changed — requesting peer list")
                        p2pManager.requestPeers(p2pChannel) { peerList ->
                            onPeersChanged(peerList.deviceList)
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        Log.d(TAG, "P2P connection changed — requesting connection info")
                        p2pManager.requestConnectionInfo(p2pChannel) { info ->
                            if (info != null && info.groupFormed) {
                                onP2pConnected(info)
                            } else {
                                Log.d(TAG, "P2P group disbanded: $info")
                                p2pConnected = false
                                p2pConnecting = false
                            }
                        }
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            context, p2pReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun unregisterP2pReceiver() {
        try {
            p2pReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) {}
        p2pReceiver = null
    }

    private fun onPeersChanged(peers: Collection<WifiP2pDevice>) {
        Log.i(TAG, "Peers found: ${peers.size} — ${peers.map { "${it.deviceName}/${it.deviceAddress}" }}")
        if (peers.isEmpty()) return
        if (p2pConnected) {
            Log.d(TAG, "Already connected, skipping peer connect")
            return
        }
        val targetPeer = peers.firstOrNull { it.deviceName?.contains("CY01", ignoreCase = true) == true || it.deviceName?.startsWith("CY", ignoreCase = true) == true }
        if (targetPeer == null) {
            Log.d(TAG, "No HeyCyan glasses found in peer list.")
            return
        }
        
        // Cancel discovery timeout — peer found
        handler.removeCallbacks(discoveryTimeoutRunnable)
        connectToDevice(targetPeer)
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(target: WifiP2pDevice) {
        if (p2pConnecting) {
            Log.d(TAG, "Already connecting — skipping duplicate connect to ${target.deviceName}")
            return
        }
        p2pConnecting = true
        connectRetryTarget = target
        handler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS)

        Log.i(TAG, "Connecting to peer: ${target.deviceName} / ${target.deviceAddress}")
        val config = WifiP2pConfig().apply {
            deviceAddress = target.deviceAddress
            wps.setup = 0 // WPS PBC — matches vendor app
        }
        p2pManager.connect(p2pChannel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "Connect request sent to ${target.deviceName}") }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "Connect request failed: $reason")
                p2pConnecting = false
                handler.removeCallbacks(connectTimeoutRunnable)
            }
        })
    }

    private fun onP2pConnected(info: WifiP2pInfo) {
        handler.removeCallbacks(connectTimeoutRunnable)
        p2pConnecting = false
        phoneIsGroupOwner = info.isGroupOwner
        groupOwnerIp = info.groupOwnerAddress?.hostAddress
        Log.i(TAG, "P2P connected: groupFormed=${info.groupFormed} isGroupOwner=$phoneIsGroupOwner groupOwnerIp=$groupOwnerIp")
        p2pNetwork = findLikelyP2pNetwork()
        bindToNetwork(p2pNetwork)
        p2pConnected = true
        if (isOtaMode && p2pNetwork != null) {
            Log.i(TAG, "P2P connected for OTA. Triggering callback.")
            otaNetworkCallback?.invoke(p2pNetwork!!)
        }
        maybeStartDownload("P2P")
    }

    private fun findLikelyP2pNetwork(): Network? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val prefixHints = listOfNotNull(subnet(bleIp), subnet(groupOwnerIp)).distinct()
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val lp = cm.getLinkProperties(n)
                val ifName = lp?.interfaceName ?: ""
                val addrs = lp?.linkAddresses?.mapNotNull { it.address.hostAddress } ?: emptyList()
                val matchesHint = prefixHints.any { p -> addrs.any { it.startsWith(p) } }
                val looksLikeP2p = ifName.contains("p2p", ignoreCase = true) ||
                    ifName.contains("wfd", ignoreCase = true) ||
                    addrs.any { it.startsWith("192.168.49.") } ||
                    matchesHint
                if (looksLikeP2p) {
                    Log.i(TAG, "Found P2P network: if=$ifName addrs=$addrs")
                    return n
                }
            }
            Log.w(TAG, "Could not find P2P network")
            null
        } catch (e: Exception) {
            Log.w(TAG, "findLikelyP2pNetwork failed: ${e.message}")
            null
        }
    }

    private fun bindToNetwork(network: Network?) {
        if (network == null) return
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (cm.bindProcessToNetwork(network))
                Log.i(TAG, "Bound process to P2P network")
            else
                Log.w(TAG, "bindProcessToNetwork returned false")
        } catch (e: Exception) {
            Log.w(TAG, "bindProcessToNetwork failed: ${e.message}")
        }
    }

    private fun unbindNetwork() {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.bindProcessToNetwork(null)
            Log.i(TAG, "Unbound from P2P network")
        } catch (_: Exception) {}
    }

    private fun maybeStartDownload(source: String) {
        if (downloading || downloadAttemptJob?.isActive == true) {
            Log.d(TAG, "Download already running, ignoring trigger from $source")
            return
        }
        Log.i(TAG, "Starting HTTP download resolution (trigger=$source bleIp=$bleIp groupOwnerIp=$groupOwnerIp)")
        downloadAttemptJob = scope.launch {
            if (isOtaMode) {
                Log.i(TAG, "P2P connection established for OTA. Skipping media download and IP resolution.")
                return@launch
            }

            val ip = resolveGlassesIp()
            if (ip == null) {
                val reason = abortReason ?: "Could not resolve glasses IP after 90s"
                Log.e(TAG, "IP resolution failed: $reason")
                onEvent(SyncEvent.Completed(0, 0L, false, reason))
                cleanup()
                return@launch
            }
            Log.i(TAG, "Resolved glasses IP: $ip")
            Log.i(TAG, "starting download")
            downloading = true
            try {
                downloadFiles(ip)
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed", e)
                onEvent(SyncEvent.Completed(0, 0L, false, e.message))
            } finally {
                downloading = false
                cleanup()
            }
        }
    }

    private fun buildHttpClient(connectTimeoutSec: Long, readTimeoutSec: Long = 60): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
        p2pNetwork?.socketFactory?.let { builder.socketFactory(it) }
        return builder.build()
    }

    /**
     * Probes all candidate IPs in parallel every 500ms for up to 90s.
     * Returns first IP whose HTTP server responds with 200.
     */
    private suspend fun resolveGlassesIp(): String? {
        val probeClient = buildHttpClient(connectTimeoutSec = 2, readTimeoutSec = 2)
        val startMs = System.currentTimeMillis()
        while (currentCoroutineContext().isActive && System.currentTimeMillis() - startMs < 90_000L) {
            val candidates = buildCandidateIps().filter { it.isNotBlank() }
            Log.d(TAG, "resolveGlassesIp: bleIp=$bleIp groupOwnerIp=$groupOwnerIp candidates=$candidates")
            if (candidates.isNotEmpty()) {
                val found = coroutineScope {
                    val result = CompletableDeferred<String>()
                    val jobs = candidates.map { candidate ->
                        launch(Dispatchers.IO) {
                            try {
                                val req = Request.Builder().url("http://$candidate$MEDIA_CONFIG_PATH").build()
                                probeClient.newCall(req).execute().use { resp ->
                                    Log.d(TAG, "resolveGlassesIp: $candidate → ${resp.code}")
                                    if (resp.isSuccessful) result.complete(candidate)
                                }
                            } catch (e: Exception) {
                                Log.d(TAG, "resolveGlassesIp: $candidate → ${e.message}")
                            }
                        }
                    }
                    val ip = withTimeoutOrNull(3_000L) { result.await() }
                    jobs.forEach { it.cancel() }
                    ip
                }
                if (found != null) {
                    Log.i(TAG, "resolveGlassesIp: found at $found")
                    return found
                }
            }
            delay(500)
        }
        return null
    }

    private fun buildCandidateIps(): List<String> {
        val set = LinkedHashSet<String>()
        bleIp?.let { set.add(it) }
        // groupOwnerIp is the glasses' address when glasses are GO
        // If phone is GO, groupOwnerIp is 192.168.49.1 (our own address — skip it)
        groupOwnerIp?.takeIf { !(phoneIsGroupOwner && it == "192.168.49.1") }?.let { set.add(it) }
        // Subnet candidates derived from best available hint
        val hint = bleIp ?: groupOwnerIp
        subnet(hint)?.let { prefix ->
            listOf("${prefix}1", "${prefix}79", "${prefix}2", "${prefix}3").forEach { ip ->
                // Skip our own GO address
                if (!(phoneIsGroupOwner && ip == "192.168.49.1")) set.add(ip)
            }
        }
        return set.toList()
    }

    private fun subnet(ip: String?): String? {
        if (ip.isNullOrBlank()) return null
        val parts = ip.split(".")
        if (parts.size != 4) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}."
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        unbindNetwork()
        p2pNetwork = null
        p2pManager.removeGroup(p2pChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "P2P group removed after sync") }
            override fun onFailure(reason: Int) { Log.d(TAG, "removeGroup failed: $reason") }
        })
        unregisterP2pReceiver()
        p2pConnected = false
        p2pConnecting = false
        groupOwnerIp = null
    }

    private fun downloadFiles(ip: String) {
        val baseUrl = "http://$ip"
        val manifest = fetchMediaManifest(baseUrl) ?: run {
            onEvent(SyncEvent.Completed(0, 0L, false, "Failed to fetch media manifest"))
            return
        }

        val files = parseMediaManifest(manifest)
        if (files.isEmpty()) {
            Log.d(TAG, "No files to sync")
            onEvent(SyncEvent.Completed(0, 0L, true))
            return
        }

        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        var completedFiles = 0
        var totalBytes = 0L

        for ((index, filePath) in files.withIndex()) {
            try {
                val url = "$baseUrl$filePath"
                val filename = filePath.substringAfterLast('/')
                val subDir = when {
                    filename.endsWith(".jpg", true) || filename.endsWith(".jpeg", true) || filename.endsWith(".png", true) -> "photo"
                    filename.endsWith(".mp4", true) || filename.endsWith(".mov", true) || filename.endsWith(".avi", true) -> "video"
                    filename.endsWith(".mp3", true) || filename.endsWith(".wav", true) || filename.endsWith(".aac", true) || filename.endsWith(".m4a", true) || filename.endsWith(".opus", true) -> "audio"
                    else -> "other"
                }
                val outputDir = File(baseDir, subDir).also { if (!it.exists()) it.mkdirs() }
                val dest = File(outputDir, filename)
                val written = downloadFileTo(url, dest)
                if (written > 0) {
                    totalBytes += written
                    completedFiles++
                    Log.d(TAG, "Downloaded ($completedFiles/${files.size}): $filename ($written bytes)")
                    // Glasses store audio as a raw Opus packet stream (no container); rewrite it as a
                    // standard Ogg/Opus file in place so the media player can open it.
                    if (filename.endsWith(".opus", true)) {
                        try {
                            val raw = dest.readBytes()
                            val wrapped = OpusOggWrapper.wrapIfNeeded(raw)
                            if (!wrapped.contentEquals(raw)) dest.writeBytes(wrapped)
                        } catch (e: Exception) {
                            Log.w(TAG, "Opus wrap failed for $filename", e)
                        }
                    }
                    // Notify per-file so the gallery indexes + thumbnails this file incrementally.
                    if (subDir == "photo" || subDir == "video" || subDir == "audio") {
                        onEvent(SyncEvent.FileSynced(dest.absolutePath, subDir))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to download $filePath", e)
            }
            onEvent(SyncEvent.Progress(index + 1, files.size))
        }

        onEvent(SyncEvent.Completed(completedFiles, totalBytes, true))
    }

    private fun fetchMediaManifest(baseUrl: String): String? {
        return try {
            val request = Request.Builder().url("$baseUrl$MEDIA_CONFIG_PATH").build()
            buildHttpClient(10).newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch manifest", e)
            null
        }
    }

    private fun parseMediaManifest(manifest: String): List<String> {
        return try {
            val files = mutableListOf<String>()
            if (manifest.trimStart().startsWith("[")) {
                val arr = JSONArray(manifest)
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i)
                    val path = item?.optString("path") ?: item?.optString("url") ?: arr.optString(i)
                    if (path.isNotEmpty()) files.add(path)
                }
            } else if (manifest.trimStart().startsWith("{")) {
                val obj = JSONObject(manifest)
                val fileArr = obj.optJSONArray("files") ?: obj.optJSONArray("media")
                if (fileArr != null) {
                    for (i in 0 until fileArr.length()) {
                        val item = fileArr.optJSONObject(i)
                        val path = item?.optString("path") ?: item?.optString("url") ?: fileArr.optString(i)
                        if (path.isNotEmpty()) files.add(path)
                    }
                }
            } else {
                // Plain text: one filename per line (actual format used by glasses)
                manifest.lines().filter { it.isNotBlank() }.forEach { files.add("/files/${it.trim()}") }
            }
            Log.d(TAG, "Parsed ${files.size} files from manifest")
            files
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse manifest", e)
            emptyList()
        }
    }

    private fun downloadFileTo(url: String, dest: File): Long {
        return try {
            val request = Request.Builder().url(url).build()
            buildHttpClient(10).newCall(request).execute().use { response ->
                if (!response.isSuccessful) return 0L
                val body = response.body ?: return 0L
                FileOutputStream(dest).use { out -> body.byteStream().copyTo(out) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download $url", e)
            dest.delete()
            0L
        }
    }
}
