package expo.modules.glasssdk.glass.vendors.cyan

import android.content.Context
import android.util.Log
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import expo.modules.glasssdk.glass.AudioConfigData
import expo.modules.glasssdk.glass.AudioResult
import expo.modules.glasssdk.glass.BaseGlassAdapter
import expo.modules.glasssdk.glass.GlassConnectionState
import expo.modules.glasssdk.glass.GlassEventConstants
import expo.modules.glasssdk.glass.GlassEventMsg
import expo.modules.glasssdk.glass.GlassStorageInfo
import expo.modules.glasssdk.glass.MediaConfigData
import expo.modules.glasssdk.glass.PhotoConfigData
import expo.modules.glasssdk.glass.PhotoResult
import expo.modules.glasssdk.glass.VideoConfigData
import expo.modules.glasssdk.glass.VideoResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.greenrobot.eventbus.EventBus
import java.util.concurrent.ConcurrentHashMap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build

class CyanAdapter(private val ctx: Context) : BaseGlassAdapter() {

    companion object {
        private const val TAG = "[Glass:CyanAdapter]"
        private const val NOTIFY_LISTENER_ID = 9001
        private const val CMD_TAKE_PHOTO: Byte = 0x01
        private const val CMD_START_VIDEO: Byte = 0x02
        private const val CMD_STOP_VIDEO: Byte = 0x03
        private const val CMD_START_AUDIO: Byte = 0x08
        private const val CMD_STOP_AUDIO: Byte = 0x0c
        private const val CMD_AI_MODE: Byte = 0x06
        private const val CMD_TRANSFER_MODE: Byte = 0x04
        private const val CMD_OTA_MODE: Byte = 0x05
        private const val OTA_AP_PASSWORD = "123456789"
        private const val OTA_AP_SSID_PREFIX = "CY01_"
        private const val CMD_EXIT_TRANSFER: Byte = 0x09
        private const val CMD_RESET_P2P: Byte = 0x0F
        private const val CMD_FACTORY_RESET: Byte = 0x0A
        private const val CMD_RESTART: Byte = 0x0E
        private const val NOTIFY_BATTERY = 0x05
        private const val NOTIFY_AI_RESULT = 0x02
        private const val NOTIFY_MIC = 0x03
        private const val NOTIFY_WIFI_IP = 0x08
        private const val NOTIFY_WIFI_ERROR = 0x09
        private const val NOTIFY_OTA = 0x04
        private const val NOTIFY_PAUSE = 0x0c
        private const val NOTIFY_UNBIND = 0x0d
        private const val NOTIFY_MEMORY_LOW = 0x0e
        private const val NOTIFY_TRANSLATION_PAUSE = 0x10
        private const val NOTIFY_VOLUME = 0x12
    }

    private val bleConnection = CyanBleConnection(ctx)
    private var fileSyncManager: CyanFileSyncManager? = null
    private var connected = false
    private var syncInProgress = false
    private var lastP2pResetAtMs = 0L
    private val discoveredDevices = mutableMapOf<String, String>() // deviceId -> deviceName

    private enum class CommandType { PHOTO, VIDEO_START, AUDIO_START }
    private val pendingRequests = ConcurrentHashMap<CommandType, CompletableDeferred<Any>>()
    private var pendingAiImagePath: CompletableDeferred<String>? = null
    
    private var pendingOtaFilePath: String? = null
    private var otaServerSocket: java.net.ServerSocket? = null

    // --- OTA P2P Flow State ---
    private var otaSystemSuccess = false
    private var otaBleCallbackSuccess = false
    private var otaGlassesIp: String? = null
    private var otaP2pConnecting = false
    private var otaFailRetryCount = 0
    private var otaP2pReceiver: android.content.BroadcastReceiver? = null
    private var otaP2pChannel: android.net.wifi.p2p.WifiP2pManager.Channel? = null
    private var otaP2pGoAddress: String? = null
    // --- OTA SoftAP (Wi-Fi station join) state ---
    private var otaWifiCallback: ConnectivityManager.NetworkCallback? = null
    private var otaBoundNetwork: Network? = null
    private var otaServerStarted = false
    @Volatile private var otaServerStopping = false
    private val otaP2pManager by lazy { ctx.getSystemService(Context.WIFI_P2P_SERVICE) as android.net.wifi.p2p.WifiP2pManager }
    private val otaTimeoutRunnable = java.lang.Runnable { handleOtaP2pTimeout() }

    /** Push an OTA diagnostic line to the JS/UI log (mirrors Log.i, which is logcat-only). */
    private fun otaPost(msg: String) {
        EventBus.getDefault().post(GlassEventMsg(GlassEventConstants.MSG_GLASS_ERROR, s1 = msg, n1 = 0))
    }

    /**
     * Diagnostics-only glassesControl: send an arbitrary payload and otaPost the fully-parsed
     * GlassModelControlResponse (dataType/work-type/error/counts/resolution+AOV lists). Used by the
     * Debug screen to probe capabilities, resolutions, and unknown work-types at runtime.
     */
    private fun sendProbe(cmd: ByteArray) {
        LargeDataHandler.getInstance().glassesControl(cmd) { _, baseResponse ->
            val r = baseResponse as? com.oudmon.ble.base.communication.bigData.resp.GlassModelControlResponse
            if (r == null) { otaPost("[PROBE] no GlassModelControlResponse"); return@glassesControl }
            val sb = StringBuilder("[PROBE] dataType=${r.dataType} workType=${r.glassWorkType} err=${r.errorCode} busy=${r.workTypeIng}")
            if (r.otaStatus != 0) sb.append(" ota=${r.otaStatus}")
            r.p2pIp?.let { sb.append(" ip=$it") }
            if (r.dataType == 4) sb.append(" photos=${r.imageCount} videos=${r.videoCount} audio=${r.recordCount}")
            if (r.dataType == 2) sb.append(" videoAngle=${r.videoAngle} videoDuration=${r.videoDuration}")
            if (r.dataType == 6) sb.append(" recAudioDur=${r.recordAudioDuration}")
            if (r.dataType == 8) sb.append(" (resolution/AOV payload — bundled aar can't decode it; needs SDK update)")
            Log.i(TAG, sb.toString())
            otaPost(sb.toString())
        }
    }

    private fun p2pReasonName(reason: Int): String = when (reason) {
        android.net.wifi.p2p.WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
        android.net.wifi.p2p.WifiP2pManager.ERROR -> "ERROR"
        android.net.wifi.p2p.WifiP2pManager.BUSY -> "BUSY"
        android.net.wifi.p2p.WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS"
        else -> "reason#$reason"
    }

    override val capabilities: Map<String, Boolean> = mapOf(
        "audio" to true,
        "sensors" to false,
        "display" to true,
        "wakeword" to false,
        "battery" to true,
        "camera" to true
    )

    override suspend fun init(context: Context) {
        Log.d(TAG, "Initializing Cyan adapter")

        bleConnection.init(
            object : CyanBleConnection.ConnectionCallback {
                override fun onConnectStateChange(isConnected: Boolean) {
                    if (!isConnected && connected) {
                        connected = false
                        scope.launch {
                            updateState(GlassConnectionState.IDLE, "Disconnected")
                            onDisconnected(reason = "BLE connection lost", wasExpected = false)
                        }
                    }
                }

                override fun onServiceDiscovered() {
                    Log.d(TAG, "Service discovered — SDK ready, marking connected")
                    connected = true
                    scope.launch {
                        updateState(GlassConnectionState.CONNECTED)
                        onConnected()
                        requestInitialData()
                    }
                }

                override fun onDeviceFound(deviceId: String, deviceName: String, rssi: Int) {
                    discoveredDevices[deviceId] = deviceName
                    scope.launch { this@CyanAdapter.onDeviceFound(deviceId, deviceName, rssi) }
                }

                override fun onDeviceIpReceived(ip: String) {
                    Log.i(TAG, "Device IP from BLE: $ip")
                    fileSyncManager?.setDeviceIp(ip)
                }
            }
        )

        LargeDataHandler.getInstance().addOutDeviceListener(NOTIFY_LISTENER_ID, notifyListener)
        LargeDataHandler.getInstance().addBatteryCallBack("cyan_main") { _, response ->
            if (response != null) {
                val level = response.battery
                val charging = response.isCharging
                updateSystemInfo(batteryLevel = level, isCharging = charging)
                EventBus.getDefault().post(
                    GlassEventMsg(
                        GlassEventConstants.MSG_GLASS_BATTERY,
                        n1 = level,
                        n2 = if (charging) 1 else 0
                    )
                )
            }
        }

        fileSyncManager = CyanFileSyncManager(
            ctx,
            onResetDeviceP2p = {
                Log.d(TAG, "Discovery timeout — resetting device P2P [0x02,0x01,0x0F]")
                sendGlassControl(byteArrayOf(0x02, 0x01, CMD_RESET_P2P))
            }
        ) { event ->
            when (event) {
                is CyanFileSyncManager.SyncEvent.Started -> onSyncStarted()
                is CyanFileSyncManager.SyncEvent.Progress -> onSyncProgress(event.current, event.total)
                is CyanFileSyncManager.SyncEvent.FileSynced -> onFileSynced(event.path, event.type)
                is CyanFileSyncManager.SyncEvent.Completed -> {
                    // Emit only the first completion per session. Late/duplicate events
                    // (e.g. a device WiFi error fired as a side effect of cancel teardown)
                    // must not overwrite the terminal state the UI already settled on.
                    if (!syncInProgress) {
                        Log.d(TAG, "Ignoring late/duplicate sync completed (error=${event.error})")
                    } else {
                        syncInProgress = false
                        Log.i(TAG, "Sync completed — sending exit transfer command")
                        sendGlassControl(byteArrayOf(0x02, 0x01, CMD_EXIT_TRANSFER))
                        onSyncCompleted(event.totalFiles, event.totalBytes, event.success, event.error)
                    }
                }
            }
        }
    }

    private fun requestInitialData() {
        LargeDataHandler.getInstance().syncBattery()
        LargeDataHandler.getInstance().syncDeviceInfo { _, response ->
            if (response != null) {
                updateSystemInfo(
                    firmwareVersion = response.firmwareVersion,
                    appVersion = response.wifiFirmwareVersion,
                    deviceModel = "Cyan Glass"
                )
            }
            onReady()
        }
        sendGlassControl(byteArrayOf(0x02, 0x04))
    }

    override suspend fun connect(deviceId: String) {
        Log.i(TAG, "Connecting to $deviceId")
        currentDeviceId = deviceId
        currentDeviceName = discoveredDevices[deviceId] ?: "Cyan Glass"
        updateState(GlassConnectionState.CONNECTING)
        bleConnection.connect(deviceId)
    }

    override suspend fun disconnect() {
        Log.i(TAG, "Disconnecting")
        bleConnection.disconnect()
        connected = false
        updateState(GlassConnectionState.IDLE, "User disconnected")
        onDisconnected(reason = "User disconnected", wasExpected = true)
    }

    override fun isConnected(): Boolean = connected

    private var hasScannedOnce = false

    override suspend fun startScan() {
        updateState(GlassConnectionState.SCANNING)
        if (!hasScannedOnce) {
            // BleOperateManager.init() is not guaranteed synchronous — give it a moment
            // to finish setting up the BLE stack before the first scan attempt.
            delay(300)
            hasScannedOnce = true
        }
        bleConnection.startScan()
    }

    override suspend fun stopScan() {
        bleConnection.stopScan()
        updateState(GlassConnectionState.IDLE)
    }

    override suspend fun takePhoto(id: Int?, filename: String?): PhotoResult {
        return withTimeout(30_000L) {
            val deferred = CompletableDeferred<Any>()
            pendingRequests[CommandType.PHOTO] = deferred
            sendGlassControl(byteArrayOf(0x02, 0x01, CMD_TAKE_PHOTO))
            try {
                deferred.await() as PhotoResult
            } catch (e: TimeoutCancellationException) {
                pendingRequests.remove(CommandType.PHOTO)
                PhotoResult(success = false, errorCode = -1)
            }
        }
    }

    override suspend fun takeAiImage(): String {
        return withTimeout(120_000L) {
            val deferred = CompletableDeferred<String>()
            pendingAiImagePath = deferred
            // Step 1: trigger AI capture (matches demo MainActivity:1513)
            sendGlassControl(byteArrayOf(0x02, 0x01, CMD_AI_MODE, 0x02, 0x02))
            delay(250)
            // Step 2: follow-up camera activation command (matches demo MainActivity:1517)
            sendGlassControl(byteArrayOf(0x02, 0x01, CMD_TAKE_PHOTO))
            try {
                deferred.await()
            } catch (e: TimeoutCancellationException) {
                pendingAiImagePath = null
                throw e
            }
        }
    }

    override suspend fun startVideo(id: Int?, filename: String?): VideoResult {
        return withTimeout(30_000L) {
            val deferred = CompletableDeferred<Any>()
            pendingRequests[CommandType.VIDEO_START] = deferred
            sendGlassControl(byteArrayOf(0x02, 0x01, CMD_START_VIDEO))
            try {
                deferred.await() as VideoResult
            } catch (e: TimeoutCancellationException) {
                pendingRequests.remove(CommandType.VIDEO_START)
                VideoResult(success = false, errorCode = -1)
            }
        }
    }

    override suspend fun stopVideo() {
        sendGlassControl(byteArrayOf(0x02, 0x01, CMD_STOP_VIDEO))
    }

    override suspend fun startAudio(filename: String?): AudioResult {
        return withTimeout(30_000L) {
            val deferred = CompletableDeferred<Any>()
            pendingRequests[CommandType.AUDIO_START] = deferred
            sendGlassControl(byteArrayOf(0x02, 0x01, CMD_START_AUDIO))
            try {
                deferred.await() as AudioResult
            } catch (e: TimeoutCancellationException) {
                pendingRequests.remove(CommandType.AUDIO_START)
                AudioResult(success = false, errorCode = -1)
            }
        }
    }

    override suspend fun stopAudio() {
        sendGlassControl(byteArrayOf(0x02, 0x01, CMD_STOP_AUDIO))
    }

    override suspend fun getMediaConfig(): MediaConfigData {
        return MediaConfigData(
            photo = PhotoConfigData(width = 0, height = 0),
            video = VideoConfigData(width = 0, height = 0, quality = 0, duration = 0),
            audio = AudioConfigData(duration = 0)
        )
    }

    override suspend fun setMediaConfig(config: Map<String, Any>): Boolean {
        Log.w(TAG, "setMediaConfig not supported on Cyan")
        return false
    }

    override suspend fun setDeviceName(name: String) {
        // Not supported by QCSDK — no-op
        Log.w(TAG, "setDeviceName not supported on Cyan")
    }

    override suspend fun reboot() {
        // 0x0E = restart device (fire-and-forget; the connection drops as it restarts)
        sendGlassControl(byteArrayOf(0x02, 0x01, CMD_RESTART))
    }

    override suspend fun factoryReset() {
        // 0x0A = factory reset (fire-and-forget; the device resets and may unpair)
        sendGlassControl(byteArrayOf(0x02, 0x01, CMD_FACTORY_RESET))
    }

    override suspend fun clearMedia() {
        // QCSDK delete-all is a separate command (not a device-mode control byte); the Android
        // BLE protocol doesn't expose it here.
        Log.w(TAG, "clearMedia not supported on Cyan (Android)")
    }

    override suspend fun setAiStatus(status: String) {
        // QCSDK setAISpeekModel is a separate command (not a device-mode control byte); not exposed
        // over the Android BLE control path.
        Log.w(TAG, "setAiStatus not supported on Cyan (Android): $status")
    }

    override suspend fun syncFiles() {
        val manager = fileSyncManager ?: run { Log.e(TAG, "FileSyncManager not initialized"); return }
        Log.i(TAG, "syncFiles: sending transfer mode command")
        syncInProgress = true
        sendGlassControl(byteArrayOf(0x02, 0x01, CMD_TRANSFER_MODE))
        delay(1500) // allow glasses to enter transfer mode and broadcast IP via BLE
        // The user may cancel during the startup delay above; cancelSync() clears
        // syncInProgress, so don't kick off the sync that was just aborted.
        if (!syncInProgress) {
            Log.i(TAG, "syncFiles: cancelled during startup — not starting sync")
            return
        }
        manager.startSync() // non-blocking — sync runs in its own coroutine
        // syncInProgress cleared in onSyncCompleted
    }

    override suspend fun cancelSync() {
        Log.i(TAG, "cancelSync: aborting Cyan sync")
        // cancelWithError cancels the download job, cleans up P2P/network, and emits
        // Completed(success=false, "Cancelled") which routes through onEvent →
        // clears syncInProgress, sends EXIT_TRANSFER, and calls onSyncCompleted.
        fileSyncManager?.cancelWithError("Cancelled")
    }

    override suspend fun sendCommand(command: String, params: Map<String, Any>) {
        if (command == "startTransferMode") {
            Log.i(TAG, "sendCommand: entering transfer mode (Wi-Fi on)")
            sendGlassControl(byteArrayOf(0x02, 0x01, CMD_TRANSFER_MODE))
        } else if (command == "startOtaMode") {
            Log.i(TAG, "sendCommand: entering OTA mode (Wi-Fi on for update)")
            sendGlassControl(byteArrayOf(0x02, 0x01, CMD_OTA_MODE))
        } else if (command == "exitTransferMode") {
            Log.i(TAG, "sendCommand: exiting transfer mode (Wi-Fi off)")
            sendGlassControl(byteArrayOf(0x02, 0x01, CMD_EXIT_TRANSFER))
        } else if (command == "setVoiceWake") {
            // Enable/disable the on-device "Hey Cyan" wake word via the vendor SDK's
            // aiVoiceWake (BLE opcode 0x44). write=true, on=enabled -> payload {2, on?1:0}.
            // A device setting (same get/set shape as wearCheck), so it should persist.
            val enabled = (params["enabled"] as? Boolean) ?: false
            Log.i(TAG, "sendCommand: setVoiceWake enabled=$enabled (aiVoiceWake 0x44)")
            otaPost("[WAKE] Sending aiVoiceWake(set, on=$enabled) — BLE opcode 0x44...")
            LargeDataHandler.getInstance().aiVoiceWake(true, enabled) { code, rsp ->
                Log.i(TAG, "aiVoiceWake set ack: code=$code rsp=$rsp")
                otaPost("[WAKE] aiVoiceWake set ACK: code=$code rsp=$rsp")
            }
        } else if (command == "getVoiceWake") {
            // Query current wake-word state (aiVoiceWake get -> payload {1, 0}).
            Log.i(TAG, "sendCommand: getVoiceWake query (aiVoiceWake 0x44 get)")
            otaPost("[WAKE] Querying aiVoiceWake status — BLE opcode 0x44 (get)...")
            LargeDataHandler.getInstance().aiVoiceWake(false, false) { code, rsp ->
                Log.i(TAG, "aiVoiceWake status: code=$code rsp=$rsp")
                otaPost("[WAKE] aiVoiceWake status: code=$code rsp=$rsp")
            }
        } else if (command == "queryFeatureSupport") {
            // Ask the glasses what they support (GlassesTouchSupportRsp bitmask). This is the
            // definitive per-unit capability probe — resolves liveReview/aov/shortcut/1300W/etc.
            otaPost("[CAP] Querying device capability bitmask (wearFunctionSupport)...")
            LargeDataHandler.getInstance().wearFunctionSupport { code, rsp ->
                if (rsp == null) { otaPost("[CAP] no response (code=$code)"); return@wearFunctionSupport }
                // The BUNDLED cyan_sdk.aar's GlassesTouchSupportRsp only parses these 4 fields.
                // The newer capability bits (liveReview/aov/shortcut/1300W/resolution/rtChat/v881) are
                // present in the wire response but this aar does NOT decode them — reading those needs
                // an SDK/aar update (same gap as aiShortCut/gyroConfig).
                val s = "[CAP] model=${rsp.glassesModel}" +
                    " translationSupport=${rsp.isTranslationSupport}" +
                    " wearCheckSupport=${rsp.isWearCheckSupport}" +
                    " volumeControl=${rsp.isVolumeControl}" +
                    " | (bundled aar exposes only these 4 — liveReview/aov/shortcut need an aar update)"
                Log.i(TAG, s)
                otaPost(s)
            }
        } else if (command == "syncTime") {
            otaPost("[TIME] Syncing device RTC (syncTime)...")
            LargeDataHandler.getInstance().syncTime { code, rsp -> otaPost("[TIME] syncTime ack code=$code") }
        } else if (command == "getVolume") {
            otaPost("[VOL] Querying volume (getVolumeControl)...")
            LargeDataHandler.getInstance().getVolumeControl { code, rsp ->
                if (rsp == null) { otaPost("[VOL] no response (code=$code)"); return@getVolumeControl }
                otaPost("[VOL] music=${rsp.currVolumeMusic}/${rsp.maxVolumeMusic}" +
                    " call=${rsp.currVolumeCall}/${rsp.maxVolumeCall}" +
                    " system=${rsp.currVolumeSystem}/${rsp.maxVolumeSystem} type=${rsp.currVolumeType}")
            }
        } else if (command == "setVolume") {
            val value = (params["value"] as? Number)?.toInt() ?: 8
            otaPost("[VOL] setVolume=$value (music/call/system) — reading back...")
            LargeDataHandler.getInstance().setVolumeControl(0, 15, value, 0, 15, value, 0, 15, value, 0)
            delay(400)
            LargeDataHandler.getInstance().getVolumeControl { _, rsp ->
                if (rsp != null) otaPost("[VOL] readback music=${rsp.currVolumeMusic} call=${rsp.currVolumeCall} system=${rsp.currVolumeSystem}")
            }
        } else if (command == "setBeep") {
            val enabled = (params["enabled"] as? Boolean) ?: true
            otaPost("[BEEP] speakSoundSwitch(on=$enabled) — shutter/system sounds")
            LargeDataHandler.getInstance().speakSoundSwitch(enabled)
        } else if (command == "setWear") {
            val enabled = (params["enabled"] as? Boolean) ?: false
            otaPost("[WEAR] wearCheck(set, on=$enabled)")
            LargeDataHandler.getInstance().wearCheck(true, enabled) { code, rsp -> otaPost("[WEAR] set ack open=${rsp?.isOpen} (code=$code)") }
        } else if (command == "getWear") {
            otaPost("[WEAR] wearCheck(get)...")
            LargeDataHandler.getInstance().wearCheck(false, false) { code, rsp -> otaPost("[WEAR] status open=${rsp?.isOpen} (code=$code) — note: this unit reported wearCheckSupport=false") }
        } else if (command == "playGlassAudio") {
            val status = (params["status"] as? Number)?.toInt() ?: 1
            otaPost("[AUDIO] aiVoicePlay(status=$status) — play on glasses speaker")
            LargeDataHandler.getInstance().aiVoicePlay(status) { code, rsp -> otaPost("[AUDIO] aiVoicePlay ack status=${rsp?.status} (code=$code)") }
        } else if (command == "aiVoiceCapture") {
            val on = (params["enabled"] as? Boolean) ?: true
            if (on) { otaPost("[MIC] AI voice/speech capture start {2,1,11}"); sendProbe(byteArrayOf(0x02, 0x01, 0x0B)) }
            else { otaPost("[MIC] AI voice capture stop {2,4}"); sendProbe(byteArrayOf(0x02, 0x04)) }
        } else if (command == "queryResolutions") {
            otaPost("[RES] Querying resolutions / AOV config {2,1,8}...")
            sendProbe(byteArrayOf(0x02, 0x01, 0x08))
        } else if (command == "queryWorkType") {
            otaPost("[STATE] Querying current work-type {1,10}...")
            sendProbe(byteArrayOf(0x01, 0x0A))
        } else if (command == "rawControl") {
            // Diagnostics probe: send an arbitrary glassesControl payload and dump the response.
            val bytes = (params["bytes"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt()?.toByte() }?.toByteArray()
            if (bytes == null || bytes.isEmpty()) { otaPost("[PROBE] rawControl: no/invalid bytes") }
            else {
                otaPost("[PROBE] rawControl -> [${bytes.joinToString(" ") { "0x%02X".format(it) }}]")
                sendProbe(bytes)
            }
        } else {
            Log.w(TAG, "sendCommand not implemented: $command")
        }
    }

    override fun destroy() {
        super.destroy()
        bleConnection.destroy()
        fileSyncManager = null
    }

    private fun sendGlassControl(cmd: ByteArray) {
        val cmdHex = cmd.joinToString(" ") { "0x%02X".format(it) }
        Log.i(TAG, "sendGlassControl: [$cmdHex]")
        otaPost("[CMD] -> [$cmdHex]")
        LargeDataHandler.getInstance().glassesControl(cmd) { _, baseResponse ->
            val response = baseResponse as? com.oudmon.ble.base.communication.bigData.resp.GlassModelControlResponse
            Log.i(TAG, "glassesControl ack: dataType=${response?.dataType} errorCode=${response?.errorCode} workTypeIng=${response?.workTypeIng}")
            otaPost("[CMD] ack dataType=${response?.dataType} workType=${response?.glassWorkType} err=${response?.errorCode} busy=${response?.workTypeIng}")
            if (cmd.size < 2) return@glassesControl
            
            // Check for OTA Mode ACK
            if (response?.dataType == 1 && response.glassWorkType == 5) {
                Log.i(TAG, "OTA Mode ACK: errorCode=${response.errorCode} otaStatus=${response.otaStatus} workTypeIng=${response.workTypeIng}")
                if (response.otaStatus == 1 || response.errorCode == 1) {
                    // Match the official app (OTAActivity1.startSocOta): on OTA-mode ACK, start
                    // Wi-Fi Direct peer discovery — the phone auto-connect()s to the glasses' P2P
                    // group with no user interaction. This replaces the old startOtaApJoin() path,
                    // which joined the glasses' hidden SoftAP via WifiNetworkSpecifier and forced a
                    // system "join Wi-Fi network?" prompt the user had to approve (and which failed).
                    otaPost("[OTA] Glasses ACK: OTA mode active (otaStatus=${response.otaStatus}). Starting Wi-Fi Direct discovery...")
                    startOtaP2pDiscovery()
                } else {
                    otaPost("[OTA] Glasses did NOT accept OTA mode (otaStatus=${response.otaStatus}, errorCode=${response.errorCode}, busyWith=${response.workTypeIng}). Device may be busy/recording.")
                }
            }
            
            val success = response?.dataType == 1 && response.errorCode == -1
            if (cmd.size >= 3) {
                when (cmd[2]) {
                    CMD_TAKE_PHOTO -> {
                        Log.i(TAG, "takePhoto ack: success=$success errorCode=${response?.errorCode}")
                        pendingRequests.remove(CommandType.PHOTO)
                            ?.complete(PhotoResult(success = success, errorCode = response?.errorCode ?: -1))
                        sendGlassControl(byteArrayOf(0x02, 0x04))
                    }
                    CMD_START_VIDEO -> {
                        Log.i(TAG, "startVideo ack: success=$success errorCode=${response?.errorCode}")
                        pendingRequests.remove(CommandType.VIDEO_START)
                            ?.complete(VideoResult(success = success, errorCode = response?.errorCode ?: -1))
                        sendGlassControl(byteArrayOf(0x02, 0x04))
                    }
                    CMD_START_AUDIO -> {
                        Log.i(TAG, "startAudio ack: success=$success errorCode=${response?.errorCode}")
                        pendingRequests.remove(CommandType.AUDIO_START)
                            ?.complete(AudioResult(success = success, errorCode = response?.errorCode ?: -1))
                        sendGlassControl(byteArrayOf(0x02, 0x04))
                    }
                    CMD_STOP_VIDEO -> {
                        Log.i(TAG, "stopVideo ack: success=$success errorCode=${response?.errorCode}")
                        sendGlassControl(byteArrayOf(0x02, 0x04))
                    }
                    CMD_STOP_AUDIO -> {
                        Log.i(TAG, "stopAudio ack: success=$success errorCode=${response?.errorCode}")
                        sendGlassControl(byteArrayOf(0x02, 0x04))
                    }
                    CMD_AI_MODE -> {
                        Log.i(TAG, "aiMode ack: errorCode=${response?.errorCode} (waiting for NOTIFY_AI_RESULT)")
                    }
                }
            } else if (cmd[1] == 0x04.toByte()) {
                response?.let {
                    val info = GlassStorageInfo(
                        totalSize = 0L, freeSize = 0L, usedSize = 0L,
                        photoCount = it.imageCount,
                        videoCount = it.videoCount,
                        audioCount = it.recordCount
                    )
                    updateStorageInfo(info)
                    EventBus.getDefault().post(
                        GlassEventMsg(
                            GlassEventConstants.MSG_GLASS_STORAGE_INFO,
                            n1 = info.photoCount,
                            n2 = info.videoCount,
                            s1 = info.audioCount.toString()
                        )
                    )
                }
            }
        }
    }

    private val notifyListener = object : GlassesDeviceNotifyListener() {
        override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
            val data = response.loadData ?: return
            Log.i(TAG, "notify: cmdType=$cmdType type=0x${data.getOrNull(6)?.toInt()?.and(0xFF)?.toString(16)} data=${data.joinToString(",") { it.toInt().and(0xFF).toString() }}")
            // Surface EVERY inbound device event (incl. unknown sub-types: button/shortcut, charge, etc.)
            otaPost("[NOTIFY] type=0x${data.getOrNull(6)?.toInt()?.and(0xFF)?.toString(16)} data=[${data.joinToString(",") { (it.toInt() and 0xFF).toString() }}]")
            when (data.getOrNull(6)?.toInt()?.and(0xFF)) {
                NOTIFY_BATTERY -> {
                    val level = data.getOrNull(7)?.toInt()?.and(0xFF) ?: return
                    val charging = (data.getOrNull(8)?.toInt()?.and(0xFF) ?: 0) != 0
                    updateSystemInfo(batteryLevel = level, isCharging = charging)
                    EventBus.getDefault().post(
                        GlassEventMsg(
                            GlassEventConstants.MSG_GLASS_BATTERY,
                            n1 = level,
                            n2 = if (charging) 1 else 0
                        )
                    )
                }
                NOTIFY_AI_RESULT -> {
//                    if (data.size <= 9 || data[9].toInt() != 0x02) return
                    Log.i(TAG, "NOTIFY_AI_RESULT received — calling getPictureThumbnails")
                    val chunkBuffer = java.io.ByteArrayOutputStream()
                    LargeDataHandler.getInstance().getPictureThumbnails { _, isComplete, chunkData ->
                        if (chunkData != null && chunkData.isNotEmpty()) {
                            chunkBuffer.write(chunkData)
                            Log.i(TAG, "getPictureThumbnails chunk: ${chunkData.size} bytes (total so far: ${chunkBuffer.size()})")
                        }
                        if (isComplete) {
                            val imageData = chunkBuffer.toByteArray()
                            Log.i(TAG, "getPictureThumbnails complete: ${imageData.size} bytes total")
                            val deferred = pendingAiImagePath
                            pendingAiImagePath = null
                            val path = saveAiImageBytes(imageData)
                            Log.i(TAG, "AI image saved: $path")
                            deferred?.complete(path)
                        }
                    }
                }
                NOTIFY_WIFI_IP -> {
                    if (data.size >= 11) {
                        val ip = "${data[7].toInt().and(0xFF)}.${data[8].toInt().and(0xFF)}.${data[9].toInt().and(0xFF)}.${data[10].toInt().and(0xFF)}"
                        Log.i(TAG, "NOTIFY_WIFI_IP: glasses reported WiFi IP: $ip")
                        fileSyncManager?.setDeviceIp(ip)
                        EventBus.getDefault().post(GlassEventMsg(GlassEventConstants.MSG_GLASS_ERROR, s1 = "[OTA] Glasses broadcasted IP: $ip", n1 = 0))
                        
                        if (pendingOtaFilePath != null) {
                            otaGlassesIp = ip
                            otaBleCallbackSuccess = true
                            checkOtaGates()
                        }
                    } else {
                        Log.w(TAG, "NOTIFY_WIFI_IP: data too short (size=${data.size})")
                    }
                }
                NOTIFY_WIFI_ERROR -> {
                    val errorCode = data.getOrNull(7)?.toInt()?.and(0xFF) ?: -1
                    Log.e(TAG, "NOTIFY_WIFI_ERROR: WiFi/P2P error from device errorCode=$errorCode")
                    if (errorCode == 255) {
                        maybeResetP2pAfterError255()
                    }
                }
                NOTIFY_MIC -> {
                    if (response.loadData[7].toInt() == 1) {
                        onVoiceWakeup()
                    }
                }
                NOTIFY_OTA -> {
                    // WiFi/Linux OTA progress, reported by the glasses over BLE (action 0x73, subtype 0x04).
                    // The glasses push three stage bytes every ~500ms; the official app folds them into a
                    // single bar with fixed weights (OTAActivity1.combineProgress, weights 0/0.29/0.01/0.70):
                    //   b2 = phone->glasses download   (0..100)  -> occupies  0..29% of the bar
                    //   b3 = download->swupdate handoff (0..100)  -> occupies 29..30%
                    //   b4 = swupdate flash write       (0..100)  -> occupies 30..100%  (the real flashing)
                    val b2 = data.getOrNull(7)?.toInt()?.and(0xFF) ?: 0
                    val b3 = data.getOrNull(8)?.toInt()?.and(0xFF) ?: 0
                    val b4 = data.getOrNull(9)?.toInt()?.and(0xFF) ?: 0
                    val percent = Math.round(0.29 * b2 + 0.01 * b3 + 0.70 * b4).toInt()
                    Log.i(TAG, "NOTIFY_OTA: wifi upgrade b2=$b2 b3=$b3 b4=$b4 -> $percent%")
                    EventBus.getDefault().post(GlassEventMsg(GlassEventConstants.MSG_GLASS_OTA_PROGRESS, n1 = percent))
                }
                NOTIFY_PAUSE -> {
                    Log.i(TAG, "NOTIFY_PAUSE: device paused")
                    EventBus.getDefault().post(GlassEventMsg(GlassEventConstants.MSG_GLASS_BT_STATUS, n1 = 0x0c))
                }
                NOTIFY_UNBIND -> {
                    Log.i(TAG, "NOTIFY_UNBIND: device requested unbind")
                    EventBus.getDefault().post(GlassEventMsg(GlassEventConstants.MSG_GLASS_BT_STATUS, n1 = 0x0d))
                }
                NOTIFY_MEMORY_LOW -> {
                    Log.w(TAG, "NOTIFY_MEMORY_LOW: device memory low")
                    EventBus.getDefault().post(GlassEventMsg(GlassEventConstants.MSG_GLASS_BT_STATUS, n1 = 0x0e))
                }
                NOTIFY_TRANSLATION_PAUSE -> {
                    Log.i(TAG, "NOTIFY_TRANSLATION_PAUSE")
                    EventBus.getDefault().post(GlassEventMsg(GlassEventConstants.MSG_GLASS_BT_STATUS, n1 = 0x10))
                }
                NOTIFY_VOLUME -> {
                    val volume = data.getOrNull(7)?.toInt()?.and(0xFF) ?: 0
                    Log.i(TAG, "NOTIFY_VOLUME: volume=$volume")
                    EventBus.getDefault().post(GlassEventMsg(GlassEventConstants.MSG_GLASS_BT_STATUS, n1 = 0x12, n2 = volume))
                }
                0x07 -> {
                    val success = data.getOrNull(7)?.toInt()?.and(0xFF) == 1
                    Log.i(TAG, "NOTIFY_OTA_STATUS: success=$success")
                    EventBus.getDefault().post(GlassEventMsg(GlassEventConstants.MSG_GLASS_ERROR, s1 = "[OTA] Status received from glasses: success=$success", n1 = 0))
                    if (success) {
                        // Emit the dedicated Linux success event so the UI can prompt for chaining
                        EventBus.getDefault().post(GlassEventMsg(GlassEventConstants.MSG_GLASS_OTA_LINUX_COMPLETED))
                        cleanupOtaP2p()
                    }
                }
                else -> {
                    Log.w(TAG, "Unhandled large data command subtype: 0x${data[6].toUByte().toString(16)}")
                }
            }
        }
    }

    private fun maybeResetP2pAfterError255() {
        // Only reset during an active sync session
        if (!syncInProgress) {
            Log.i(TAG, "Ignoring error=255 P2P reset — no sync session active")
            return
        }
        // Suppress reset if download is actively in progress (would drop the HTTP session)
        if (fileSyncManager?.isDownloading() == true) {
            Log.i(TAG, "Suppressing error=255 P2P reset — download in progress")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastP2pResetAtMs < 10_000L) {
            Log.i(TAG, "Suppressing error=255 P2P reset — debounced (last reset ${now - lastP2pResetAtMs}ms ago)")
            return
        }
        lastP2pResetAtMs = now
        Log.w(TAG, "error=255: sending exit transfer command to glasses [0x02,0x01,0x09]")
        sendGlassControl(byteArrayOf(0x02, 0x01, CMD_EXIT_TRANSFER))
        fileSyncManager?.cancelWithError("WiFi/P2P error from device (errorCode=255)")
    }

    private fun saveAiImageBytes(data: ByteArray): String {
        if (data.isEmpty()) throw Exception("Received empty image data from glass")
        if (data.size < 2 || data[0] != 0xFF.toByte() || data[1] != 0xD8.toByte()) {
            throw Exception("Invalid image data from glass (not a JPEG, size=${data.size}, header=${data.take(4).map { it.toUByte().toString(16) }})")
        }
        val file = java.io.File(ctx.cacheDir, "cyan_ai_${System.currentTimeMillis()}.jpg")
        java.io.FileOutputStream(file).use { it.write(data) }
        return file.absolutePath
    }

    override suspend fun startOtaUpdate(filePath: String) {
        val dfuHandle = com.oudmon.ble.base.communication.DfuHandle.getInstance()
        if (!dfuHandle.checkFile(filePath)) {
            Log.e(TAG, "OTA checkFile failed for $filePath")
            EventBus.getDefault().post(GlassEventMsg(GlassEventConstants.MSG_GLASS_OTA_ERROR, s1 = "File check failed"))
            return
        }

        dfuHandle.start(object : com.oudmon.ble.base.communication.DfuHandle.IOpResult {
            override fun onActionResult(action: Int, result: Int) {
                Log.d(TAG, "DfuHandle onActionResult: action=$action result=$result")
                if (result != 0) {
                    EventBus.getDefault().post(GlassEventMsg(GlassEventConstants.MSG_GLASS_OTA_ERROR, s1 = "Action $action failed with $result"))
                    return
                }
                when (action) {
                    1 -> dfuHandle.init()
                    2 -> dfuHandle.sendPacket()
                    3 -> dfuHandle.check()
                    4 -> {
                        dfuHandle.endAndRelease()
                        EventBus.getDefault().post(GlassEventMsg(GlassEventConstants.MSG_GLASS_OTA_COMPLETED))
                    }
                }
            }

            override fun onProgress(progress: Int) {
                EventBus.getDefault().post(GlassEventMsg(GlassEventConstants.MSG_GLASS_OTA_PROGRESS, n1 = progress))
            }
        })
    }

    override suspend fun startWifiOtaUpdate(url: String) {
        if (url.startsWith("file://") || url.startsWith("/")) {
            val path = url.removePrefix("file://")
            Log.i(TAG, "startWifiOtaUpdate: intercepting local file $path, initiating normal OTA flow")
            EventBus.getDefault().post(GlassEventMsg(GlassEventConstants.MSG_GLASS_ERROR, s1 = "[OTA] Intercepted local file, sending 0x02 0x01 0x05 to glasses", n1 = 0))
            pendingOtaFilePath = path
            
            // Initialize OTA flow state
            otaSystemSuccess = false
            otaBleCallbackSuccess = false
            otaFailRetryCount = 0
            otaGlassesIp = null
            otaP2pGoAddress = null
            otaP2pConnecting = false
            otaServerStarted = false
            otaServerStopping = false

            // Register receiver
            registerOtaP2pReceiver()
            
            sendGlassControl(byteArrayOf(0x02, 0x01, CMD_OTA_MODE))
            return
        }
        return kotlin.coroutines.suspendCoroutine { continuation ->
            LargeDataHandler.getInstance().writeIpToSoc(url) { code, response ->
                Log.d(TAG, "WiFi OTA writeIpToSoc callback: code=$code, response=$response")
                // Code 1 usually means success in this SDK, 0 or negative is error
                if (code == 1 || code == 0) { 
                    continuation.resume(Unit)
                } else {
                    continuation.resumeWithException(Exception("WiFi OTA (writeIpToSoc) failed with code $code"))
                }
            }
        }
    }

    private fun registerOtaP2pReceiver() {
        otaP2pReceiver?.let { ctx.unregisterReceiver(it) }
        otaP2pChannel = otaP2pManager.initialize(ctx, ctx.mainLooper, null)
        val filter = android.content.IntentFilter().apply {
            addAction(android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        
        otaP2pReceiver = object : android.content.BroadcastReceiver() {
            @android.annotation.SuppressLint("MissingPermission")
            override fun onReceive(context: Context, intent: android.content.Intent) {
                when (intent.action) {
                    android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        otaP2pManager.requestPeers(otaP2pChannel) { peers ->
                            val list = peers.deviceList.toList()
                            val summary = if (list.isEmpty()) "(none)" else list.joinToString(", ") {
                                "${it.deviceName ?: "?"}[${it.deviceAddress}] st=${it.status}"
                            }
                            Log.i(TAG, "[OTA] Peers changed: ${list.size} peer(s): $summary")
                            otaPost("[OTA] P2P peers (${list.size}): $summary")

                            val target = list.firstOrNull {
                                val n = it.deviceName ?: ""
                                n.startsWith("CY", ignoreCase = true) ||
                                    n.contains("cyan", ignoreCase = true) ||
                                    n.contains("glass", ignoreCase = true)
                            }
                            when {
                                target == null ->
                                    otaPost("[OTA] No matching glasses peer yet (want name CY*/cyan/glass). Still scanning...")
                                otaP2pConnecting || otaSystemSuccess ->
                                    Log.i(TAG, "[OTA] Target ${target.deviceName} present but already connecting/connected")
                                else -> {
                                    Log.i(TAG, "[OTA] Found glasses peer: ${target.deviceName} [${target.deviceAddress}], connecting...")
                                    otaPost("[OTA] Found glasses peer ${target.deviceName} [${target.deviceAddress}], connecting...")
                                    otaP2pConnecting = true
                                    val config = android.net.wifi.p2p.WifiP2pConfig().apply {
                                        deviceAddress = target.deviceAddress
                                        wps.setup = 0
                                    }
                                    otaP2pManager.connect(otaP2pChannel, config, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
                                        override fun onSuccess() {
                                            Log.i(TAG, "[OTA] P2P connect() accepted, negotiating group...")
                                            otaPost("[OTA] P2P connect() accepted, negotiating group...")
                                        }
                                        override fun onFailure(reason: Int) {
                                            otaP2pConnecting = false
                                            Log.e(TAG, "[OTA] P2P connect failed: $reason")
                                            otaPost("[OTA] P2P connect FAILED: ${p2pReasonName(reason)}")
                                        }
                                    })
                                }
                            }
                        }
                    }
                    android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(android.net.wifi.p2p.WifiP2pManager.EXTRA_NETWORK_INFO)
                        Log.i(TAG, "[OTA] P2P connection changed: connected=${networkInfo?.isConnected}")
                        if (networkInfo?.isConnected == true) {
                            otaP2pManager.requestConnectionInfo(otaP2pChannel) { info ->
                                if (info != null && info.groupFormed) {
                                    val go = info.groupOwnerAddress?.hostAddress
                                    otaP2pGoAddress = go
                                    Log.i(TAG, "[OTA] P2P Group formed. isGroupOwner=${info.isGroupOwner} groupOwner=$go")
                                    otaSystemSuccess = true
                                    otaP2pConnecting = false
                                    otaPost("[OTA] P2P group formed (isGO=${info.isGroupOwner}, groupOwner=$go). Waiting for BLE IP notify (0x08)...")
                                    checkOtaGates()
                                } else {
                                    Log.i(TAG, "[OTA] Connected but group not formed yet (info=$info)")
                                }
                            }
                        }
                    }
                }
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(ctx, otaP2pReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /**
     * The glasses expose a HIDDEN WPA2 SoftAP named "CY01_<MAC>" (MAC = connected BLE MAC, no
     * colons, uppercase) with passphrase "123456789". A hidden SSID cannot be scanned for, so we
     * derive the exact name from the connected device and join it explicitly.
     */
    private fun deriveOtaApSsid(): String? {
        val raw = currentDeviceId ?: return null
        val mac = raw.removePrefix(CyanBleConnection.DEVICE_ID_PREFIX).replace(":", "").uppercase()
        if (mac.length < 12) return null
        return "$OTA_AP_SSID_PREFIX$mac"
    }

    private fun phoneIpv4OnNetwork(cm: ConnectivityManager, network: Network): String? {
        val lp = cm.getLinkProperties(network) ?: return null
        return lp.linkAddresses
            .map { it.address }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull()
            ?.hostAddress
    }

    /**
     * Join the glasses' SoftAP as a Wi-Fi station and, once connected, bind this process to that
     * network and serve the firmware over HTTP. Replaces the Wi-Fi Direct (P2P) path, which the
     * glasses do not actually use for OTA — they run a plain hidden AP.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun startOtaApJoin() {
        val ssid = deriveOtaApSsid()
        if (ssid == null) {
            otaPost("[OTA] Cannot derive glasses AP SSID (no connected MAC). Aborting.")
            EventBus.getDefault().post(GlassEventMsg(GlassEventConstants.MSG_GLASS_OTA_ERROR, s1 = "No connected device MAC for AP join"))
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            otaPost("[OTA] AP join requires Android 10+. Aborting.")
            EventBus.getDefault().post(GlassEventMsg(GlassEventConstants.MSG_GLASS_OTA_ERROR, s1 = "AP join needs Android 10+"))
            return
        }

        otaPost("[OTA] Joining glasses AP \"$ssid\" (hidden, WPA2). Approve the system Wi-Fi prompt if it appears...")

        val specifierBuilder = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(OTA_AP_PASSWORD)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            specifierBuilder.setIsHiddenSsid(true)
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifierBuilder.build())
            .build()

        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "[OTA] AP network available: $network")
                otaBoundNetwork = network
                // Route this process's sockets through the glasses AP so the local HTTP server is
                // reachable by the glasses (and not leaked to the phone's other interfaces).
                cm.bindProcessToNetwork(network)
                otaSystemSuccess = true

                val phoneIp = phoneIpv4OnNetwork(cm, network)
                otaPost("[OTA] Joined AP \"$ssid\". Phone IP on AP = ${phoneIp ?: "?"}")
                if (phoneIp == null) {
                    EventBus.getDefault().post(GlassEventMsg(GlassEventConstants.MSG_GLASS_OTA_ERROR, s1 = "Joined AP but no IPv4 assigned yet"))
                    return
                }

                val path = pendingOtaFilePath
                if (path == null) {
                    otaPost("[OTA] AP joined but no pending firmware file. Aborting.")
                    return
                }
                pendingOtaFilePath = null
                Thread { startLocalOtaServerAndNotify(phoneIp, path) }.start()
            }

            override fun onUnavailable() {
                Log.e(TAG, "[OTA] AP join unavailable (denied / timeout / wrong SSID or password / AP down)")
                otaPost("[OTA] AP join FAILED for \"$ssid\": denied the Wi-Fi prompt, wrong SSID/password, or the glasses' AP was not up. Blue Wi-Fi light must be ON.")
                EventBus.getDefault().post(GlassEventMsg(GlassEventConstants.MSG_GLASS_OTA_ERROR, s1 = "AP join failed for $ssid"))
                cleanupOtaP2p()
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "[OTA] AP network lost: $network")
                otaPost("[OTA] AP network lost.")
            }
        }
        otaWifiCallback = cb
        try {
            // 60s timeout: onUnavailable() fires if the AP can't be joined in time.
            cm.requestNetwork(request, cb, 60_000)
        } catch (e: Exception) {
            Log.e(TAG, "[OTA] requestNetwork failed", e)
            otaPost("[OTA] requestNetwork threw: ${e.message}")
            EventBus.getDefault().post(GlassEventMsg(GlassEventConstants.MSG_GLASS_OTA_ERROR, s1 = "requestNetwork failed: ${e.message}"))
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startOtaP2pDiscovery() {
        Log.i(TAG, "[OTA] Starting P2P discovery...")
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.removeCallbacks(otaTimeoutRunnable)
        handler.postDelayed(otaTimeoutRunnable, 40_000L) // 40s timeout

        otaPost("[OTA] Starting Wi-Fi Direct peer discovery...")
        otaP2pManager.discoverPeers(otaP2pChannel, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "[OTA] discoverPeers started")
                otaPost("[OTA] discoverPeers started OK")
            }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "[OTA] discoverPeers failed: $reason")
                otaPost("[OTA] discoverPeers FAILED: ${p2pReasonName(reason)} — check NEARBY_WIFI_DEVICES/location permission and that Wi-Fi is ON")
            }
        })
    }

    private fun handleOtaP2pTimeout() {
        // Both gates already passed -> transfer is underway, nothing to do.
        if (otaSystemSuccess && otaBleCallbackSuccess) return

        Log.e(TAG, "[OTA] Handshake timeout. p2pGroup=$otaSystemSuccess bleIp=$otaBleCallbackSuccess retry=$otaFailRetryCount")
        otaPost("[OTA] Handshake timeout after 40s (p2pGroup=$otaSystemSuccess, bleIp=$otaBleCallbackSuccess). Retry ${otaFailRetryCount + 1}/3")

        if (otaFailRetryCount < 3) {
            otaFailRetryCount++
            if (!otaSystemSuccess) {
                // P2P group never formed: re-run discovery on the existing AP (do NOT re-send
                // OTA mode, which resets the glasses' Wi-Fi and restarts the whole handshake).
                startOtaP2pDiscovery()
            } else {
                // Group is up but the glasses never sent their IP (notify 0x08).
                // Keep waiting for the BLE notify; just re-arm the watchdog.
                otaPost("[OTA] P2P is up but no BLE IP (0x08) received. Still waiting...")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(otaTimeoutRunnable, 40_000L)
            }
        } else {
            EventBus.getDefault().post(GlassEventMsg(GlassEventConstants.MSG_GLASS_OTA_ERROR, s1 = "OTA handshake timeout after 3 retries (p2pGroup=$otaSystemSuccess, bleIp=$otaBleCallbackSuccess)"))
            cleanupOtaP2p()
        }
    }
    
    private fun cleanupOtaP2p() {
        try {
            otaP2pReceiver?.let { ctx.unregisterReceiver(it) }
        } catch (_: Exception) {}
        otaP2pReceiver = null

        // Tear down the SoftAP station join: unregister the request and restore normal networking.
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            otaWifiCallback?.let { cm.unregisterNetworkCallback(it) }
            cm.bindProcessToNetwork(null)
        } catch (_: Exception) {}
        otaWifiCallback = null
        otaBoundNetwork = null

        otaServerStopping = true
        otaServerSocket?.close()
        otaServerSocket = null
        otaServerStarted = false

        android.os.Handler(android.os.Looper.getMainLooper()).removeCallbacks(otaTimeoutRunnable)
        pendingOtaFilePath = null
    }

    private fun checkOtaGates() {
        if (!otaSystemSuccess || !otaBleCallbackSuccess || pendingOtaFilePath == null) {
            val msg = "[OTA] Waiting on gates: p2pGroup=$otaSystemSuccess bleIp=$otaBleCallbackSuccess file=${pendingOtaFilePath != null}"
            Log.i(TAG, msg)
            otaPost(msg)
            return
        }

        // Both gates open! Start server.
        android.os.Handler(android.os.Looper.getMainLooper()).removeCallbacks(otaTimeoutRunnable)
        Log.i(TAG, "[OTA] Both gates passed (P2P + BLE IP). Starting server.")
        otaPost("[OTA] Dual-gate passed! Resolving phone IP on glasses subnet...")

        val glassesIp = otaGlassesIp ?: return
        val path = pendingOtaFilePath ?: return
        pendingOtaFilePath = null // Prevent double execution

        Thread {
            fun subnetOf(ip: String) = ip.split(".").take(3).joinToString(".")

            // Enumerate every local IPv4 address (for diagnostics + selection).
            val candidates = mutableListOf<Pair<String, String>>() // iface name -> ip
            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val ni = interfaces.nextElement()
                    if (!ni.isUp || ni.isLoopback) continue
                    val addresses = ni.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (addr is java.net.Inet4Address) {
                            addr.hostAddress?.let { candidates.add(ni.name to it) }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error enumerating interfaces", e)
            }
            otaPost("[OTA] glassesIP=$glassesIp goAddr=${otaP2pGoAddress ?: "?"}; phone ifaces: " +
                (candidates.joinToString(", ") { "${it.first}=${it.second}" }.ifEmpty { "(none)" }))

            val glassesSubnet = subnetOf(glassesIp)
            val goSubnet = otaP2pGoAddress?.let { subnetOf(it) }

            // Prefer an address on the glasses' reported subnet; fall back to the P2P
            // group-owner subnet (covers the case where the BLE-reported IP and the actual
            // P2P interface disagree).
            var phoneIp = candidates.map { it.second }.firstOrNull { subnetOf(it) == glassesSubnet }
            if (phoneIp == null && goSubnet != null) {
                phoneIp = candidates.map { it.second }.firstOrNull { subnetOf(it) == goSubnet }
                if (phoneIp != null) {
                    otaPost("[OTA] No IP on glasses subnet $glassesSubnet; using P2P group-owner subnet $goSubnet -> $phoneIp")
                }
            }

            if (phoneIp != null) {
                startLocalOtaServerAndNotify(phoneIp, path)
            } else {
                otaPost("[OTA] FAILED to find phone IP on subnet $glassesSubnet (GO subnet ${goSubnet ?: "?"}). Ifaces: " +
                    candidates.joinToString(", ") { "${it.first}=${it.second}" })
                EventBus.getDefault().post(GlassEventMsg(GlassEventConstants.MSG_GLASS_OTA_ERROR, s1 = "Could not find phone IP on subnet $glassesSubnet"))
            }
        }.start()
    }

    @Synchronized
    private fun startLocalOtaServerAndNotify(phoneIp: String, filePath: String) {
        if (otaServerStarted) {
            Log.i(TAG, "OTA server already started — ignoring duplicate call")
            return
        }
        otaServerStarted = true
        try {

            Log.i(TAG, "Starting local OTA server on $phoneIp:8080 for file $filePath")
            EventBus.getDefault().post(GlassEventMsg(GlassEventConstants.MSG_GLASS_ERROR, s1 = "[OTA] Server starting on $phoneIp:8080", n1 = 0))
            
            // Start server
            otaServerSocket?.close()
            otaServerSocket = java.net.ServerSocket(8080, 50, java.net.InetAddress.getByName(phoneIp))
            
            // Notify glasses using a very short URL to prevent buffer overflow in the embedded daemon
            val url = "http://$phoneIp:8080/fw.swu"
            LargeDataHandler.getInstance().writeIpToSoc(url) { code, _ ->
                Log.i(TAG, "writeIpToSoc local result: $code")
                EventBus.getDefault().post(GlassEventMsg(GlassEventConstants.MSG_GLASS_ERROR, s1 = "[OTA] writeIpToSoc result: $code (URL=$url)", n1 = 0))
            }

            EventBus.getDefault().post(GlassEventMsg(GlassEventConstants.MSG_GLASS_ERROR, s1 = "[OTA] Waiting for glasses to connect to $phoneIp:8080...", n1 = 0))
            
            while (true) {
                val socket = otaServerSocket?.accept() ?: break
                Log.i(TAG, "OTA client connected: ${socket.inetAddress.hostAddress}")
                
                val inputStream = java.io.BufferedReader(java.io.InputStreamReader(socket.getInputStream()))
                val requestLine = inputStream.readLine() ?: ""
                Log.i(TAG, "OTA HTTP Request: $requestLine")
                
                val outputStream = socket.getOutputStream()
                val file = java.io.File(filePath)
                
                if (!file.exists()) {
                    val errorResponse = "HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n"
                    outputStream.write(errorResponse.toByteArray())
                    socket.close()
                    continue
                }
                
                val headers = "HTTP/1.1 200 OK\r\n" +
                              "Content-Type: application/octet-stream\r\n" +
                              "Content-Length: ${file.length()}\r\n" +
                              "Content-Disposition: attachment; filename=\"fw.swu\"\r\n" +
                              "Connection: close\r\n\r\n"
                outputStream.write(headers.toByteArray())
                
                if (requestLine.startsWith("GET")) {
                    EventBus.getDefault().post(GlassEventMsg(GlassEventConstants.MSG_GLASS_ERROR, s1 = "[OTA] Client GET request! Streaming file...", n1 = 0))
                    // We deliberately do NOT derive a progress % from bytes served here. The glasses
                    // report authoritative combined progress (download + flash) over BLE via the
                    // NOTIFY_OTA (0x04) frame — see notifyListener. A byte-served counter would only
                    // measure the phone->glasses transfer and would fight that BLE-reported bar.
                    val fileInputStream = java.io.FileInputStream(file)
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (fileInputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                    fileInputStream.close()
                    outputStream.flush()
                    Log.i(TAG, "OTA file served successfully")
                    EventBus.getDefault().post(GlassEventMsg(GlassEventConstants.MSG_GLASS_ERROR, s1 = "[OTA] File served successfully! Waiting for glasses to flash and reboot...", n1 = 0))
                }
                
                socket.close()
            }
            
        } catch (e: Exception) {
            if (otaServerStopping) {
                Log.i(TAG, "OTA server socket closed as part of normal shutdown")
            } else {
                Log.e(TAG, "Error in local OTA server", e)
                EventBus.getDefault().post(GlassEventMsg(GlassEventConstants.MSG_GLASS_OTA_ERROR, s1 = "Server error: ${e.message}"))
                otaServerSocket?.close()
                otaServerSocket = null
                // Restore normal networking if the transfer died mid-way.
                try {
                    (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).bindProcessToNetwork(null)
                } catch (_: Exception) {}
            }
        }
    }
}
