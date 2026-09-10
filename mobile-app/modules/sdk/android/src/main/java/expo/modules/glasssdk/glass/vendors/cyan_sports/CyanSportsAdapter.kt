package expo.modules.glasssdk.glass.vendors.cyan_sports

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.util.Log
import com.moyoung.glasses.CRPBleClient
import com.moyoung.glasses.conn.CRPBleConnection
import com.moyoung.glasses.conn.CRPBleDevice
import com.moyoung.glasses.conn.callback.CRPCommandCallback
import com.moyoung.glasses.conn.callback.CRPFileDownloadCallback
import com.moyoung.glasses.conn.listener.CRPAiDialogueListener
import com.moyoung.glasses.conn.listener.CRPBleConnectionStateListener
import com.moyoung.glasses.conn.listener.CRPWifiChangeListener
import com.moyoung.glasses.conn.protos.FlowStatus
import com.moyoung.glasses.conn.protos.TakePhoto
import com.moyoung.glasses.conn.protos.VersionInfo
import com.moyoung.glasses.conn.protos.VideoConfig
import com.moyoung.glasses.conn.protos.VideoRecord
import com.moyoung.glasses.conn.type.CRPWifiType
import com.moyoung.glasses.scan.bean.CRPScanDevice
import com.moyoung.glasses.scan.callback.CRPScanCallback
import com.xy.bt.api.BluetoothListener
import com.xy.bt.base.BluetoothA2dpController
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
import expo.modules.glasssdk.util.OpusOggWrapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import org.greenrobot.eventbus.EventBus
import java.io.File

/**
 * Adapter for the cyan_sports glasses, wrapping the Moyoung CRP SDK
 * (com.moyoung.glasses.CRPBleClient → CRPBleDevice → CRPBleConnection).
 *
 * Discovery is handled by the generic GlassManager scanner; JS classifies the advertisement
 * (manufacturer marker 0xF0EF) as "cyan_sports" and routes connect() here.
 *
 * Notes on SDK coverage:
 *  - The CRP SDK performs the Wi-Fi join + HTTP media download itself (enableWifi → connectWifi
 *    → downloadMediaFile), so syncFiles() needs no custom P2P/sync-manager (unlike Cyan/K900).
 *  - The SDK exposes no programmatic video-record start/stop (only video *config*), and no
 *    shutter-ack listener, delete-all, photo/audio resolution config, or device-name setter —
 *    those methods are clean no-ops/unsupported below.
 */
class CyanSportsAdapter(private val ctx: Context) : BaseGlassAdapter() {

    companion object {
        /** Grace period after disableWifi() for the glasses to leave file mode and free camera/mic. */
        private const val SYNC_EXIT_SETTLE_MS = 3000L
    }

    private var client: CRPBleClient? = null
    private var bleDevice: CRPBleDevice? = null
    private var connection: CRPBleConnection? = null

    /** Guards the Wi-Fi/download callbacks so stray events outside a sync are ignored. */
    private var syncInProgress = false
    private var syncFileCount = 0
    private var syncTotalBytes = 0L
    private var syncReportedCount = 0

    /// In-flight takeAiImage() capture; completed by the AI-dialogue image callback (binder thread).
    @Volatile
    private var pendingAiImage: CompletableDeferred<String>? = null

    /// Classic-BT bond state: receiver + bounded re-bond counter. The CRP SDK only does BLE/GATT;
    /// the glasses' A2DP speaker + SCO mic need a Classic-BT bond, which we drive via createBond().
    private var bondReceiver: BroadcastReceiver? = null
    private var bondRetries = 0
    private val maxBondRetries = 2

    /// A2DP profile controller (from the K900 SDK, reused here). Bonding only *pairs* the device;
    /// the A2DP audio profile must be explicitly connected for sound to route to the glasses.
    private var a2dpController: BluetoothA2dpController? = null
    private var a2dpRetries = 0
    private val maxA2dpRetries = 2

    /// Bounded BLE connect retry: Android's first GATT connect after idle drops instantly
    /// (status-133-style). The Moyoung SDK already refresh()+close()s the failed gatt on its
    /// disconnect, so we must NOT add our own disconnect — just back off (peripheral/stack needs
    /// a few seconds to free its slot) and recreate. Linear backoff stays within the JS 25s budget.
    private val maxBleConnectAttempts = 5
    private val bleConnectTimeoutMs = 5000L        // per-attempt safety net; real failures fire in ms
    private val bleConnectRetryBaseDelayMs = 1000L // linear backoff: 1s, 2s, 3s, 4s between attempts

    override val capabilities: Map<String, Boolean> = mapOf(
        "audio" to true,
        "sensors" to false,
        "display" to true,
        "wakeword" to true,
        "battery" to true,
        "camera" to true
    )

    override suspend fun init(context: Context) {
        Log.d(TAG, "Initializing CyanSports adapter")
        // CRPBleClient is a singleton; create() returns the shared instance.
        client = CRPBleClient.create(context.applicationContext)
    }

    // ── Connection ────────────────────────────────────────────────────────────

    override suspend fun connect(deviceId: String) {
        Log.i(TAG, "Connecting to $deviceId")
        val client = client ?: throw IllegalStateException("SDK not initialized")

        val device = client.getBleDevice(deviceId)
            ?: throw IllegalArgumentException("Invalid device address: $deviceId")
        bleDevice = device
        currentDeviceId = deviceId
        currentDeviceName = device.name ?: "Cyan Sports Glass"

        updateState(GlassConnectionState.CONNECTING)

        // Warm the controller cache / confirm the device is in range before burning connect
        // attempts. Early-exits the moment the device advertises.
        warmScanFor(deviceId, timeoutMs = 5000)

        // Android's first GATT connect after an idle period often drops instantly
        // (status-133-style: CONNECTING -> DISCONNECTED in ms) even when the device is in range;
        // an immediate retry succeeds. Retry a bounded number of times, distinguishing a
        // connect-phase disconnect (retry) from a post-connected disconnect (real link loss).
        var connected = false
        for (attempt in 1..maxBleConnectAttempts) {
            val gate = CompletableDeferred<Boolean>()
            // connect() returns the connection object but does NOT initiate the link; wire up
            // listeners first, then call connection.connect() to start the async connection.
            val conn = device.connect()
            connection = conn
            wireConnectionListeners(conn, gate)
            // The Moyoung SDK's connect() boolean is unreliable: it can return false ("failed to
            // initiate") while the link still comes up async and the state listener fires
            // STATE_CONNECTED. Don't treat false as fatal — let the gate/state listener drive.
            if (!conn.connect()) {
                Log.w(TAG, "conn.connect() returned false (attempt $attempt) — relying on state listener")
            }

            if (withTimeoutOrNull(bleConnectTimeoutMs) { gate.await() } == true) {
                connected = true
                break
            }

            Log.w(TAG, "BLE connect attempt $attempt/$maxBleConnectAttempts failed")
            connection = null  // neutralize late callbacks from this attempt
            // Do NOT call device.disconnect() — the SDK's gatt callback already ran
            // refresh()+close() on the failed gatt. An extra disconnect just adds churn that races
            // that cleanup. Back off (the peripheral/stack needs a few seconds to free its slot),
            // then recreate a fresh connectGatt on the next iteration.
            if (attempt < maxBleConnectAttempts) delay(bleConnectRetryBaseDelayMs * attempt)
        }

        if (!connected) {
            Log.w(TAG, "BLE connect failed after $maxBleConnectAttempts attempts")
            connection = null
            onDisconnected(reason = "BLE connection failed", wasExpected = false)
        }
    }

    /**
     * Wire all listeners for a connection attempt. The state listener completes [gate] for the
     * retry loop and uses a `conn !== connection` guard to ignore callbacks from a
     * superseded/abandoned attempt (we null `connection` between tries).
     */
    private fun wireConnectionListeners(conn: CRPBleConnection, gate: CompletableDeferred<Boolean>) {
        conn.setConnectionStateListener(CRPBleConnectionStateListener { state ->
            if (conn !== connection) return@CRPBleConnectionStateListener  // superseded attempt
            Log.d(TAG, "Connection state changed: $state")
            when (state) {
                CRPBleConnectionStateListener.STATE_CONNECTED -> {
                    if (gate.isActive) gate.complete(true)  // resume the retry loop
                    scope.launch {
                        onConnected()
                        requestInitialData(conn)
                        onReady()
                        // BLE control link is up; make sure the Classic-BT bond is in place so the
                        // glasses' A2DP speaker + SCO mic are routable (the CRP SDK does no Classic bonding).
                        ensureClassicBond()
                    }
                }
                CRPBleConnectionStateListener.STATE_DISCONNECTED -> {
                    if (gate.isActive) {
                        // Failure during the connect phase — let the retry loop decide; don't surface IDLE yet.
                        gate.complete(false)
                    } else {
                        // Disconnect after a successful connect → real link loss.
                        scope.launch {
                            pendingAiImage?.cancel(); pendingAiImage = null
                            onDisconnected(reason = "BLE connection lost", wasExpected = false)
                        }
                    }
                }
            }
        })

        conn.setBatteryListener { info ->
            val level = info.lvl
            val charging = info.charging
            Log.i(TAG, "Battery: $level% (charging=$charging, volt=${info.volt}mV)")
            updateSystemInfo(
                batteryLevel = level,
                isCharging = charging,
                batteryVoltage = info.volt.toFloat()
            )
            EventBus.getDefault().post(
                GlassEventMsg(
                    GlassEventConstants.MSG_GLASS_BATTERY,
                    n1 = level,
                    n2 = if (charging) 1 else 0
                )
            )
        }

        // Media file counts → storage info (CRP reports counts only, no byte sizes).
        conn.setMediaFileChangeListener { info ->
            Log.i(TAG, "Media counts: photo=${info.photoCount} video=${info.videoCount} audio=${info.audioCount}")
            val storage = GlassStorageInfo(
                totalSize = 0L, freeSize = 0L, usedSize = 0L,
                photoCount = info.photoCount,
                videoCount = info.videoCount,
                audioCount = info.audioCount
            )
            updateStorageInfo(storage)
            EventBus.getDefault().post(
                GlassEventMsg(
                    GlassEventConstants.MSG_GLASS_STORAGE_INFO,
                    n1 = storage.photoCount,
                    n2 = storage.videoCount,
                    s1 = storage.audioCount.toString()
                )
            )
        }

        // AI-dialogue listener: used here only to receive the AI-recognition image
        // (onDialogueImageChange) for takeAiImage(). The audio side (onDialogueAudioChange) is opus
        // and is a no-op — its decoder (com.jieli.jl_audio_decode.opus.OpusManager) is stubbed
        // since the vendor never shipped the Jieli lib for Android. Registering is safe now that
        // the stub exists; the image is delivered independently of audio.
        conn.setAiDialogueListener(object : CRPAiDialogueListener {
            override fun onDialogueStart() {}
            override fun onDialogueAudioChange(audio: ByteArray?) { /* opus audio — unused/stubbed */ }
            override fun onDialogueImageChange(file: java.io.File?) {
                val p = pendingAiImage ?: return
                if (file != null && file.exists() && file.length() > 0) {
                    Log.i(TAG, "AI image received: ${file.absolutePath} (${file.length()} bytes)")
                    p.complete(file.absolutePath)
                } else {
                    p.completeExceptionally(Exception("cyan_sports AI image missing/empty"))
                }
            }
            override fun onDialogueStop(interrupted: Boolean) {}
        })

        // Wi-Fi listener drives the media-download state machine (see syncFiles()).
        conn.setWifiListener(object : CRPWifiChangeListener {
            override fun onWifiStateChange(type: CRPWifiType?, state: Int) {
                if (!syncInProgress) return
                Log.i(TAG, "Wi-Fi state: type=$type state=$state")
                when (state) {
                    CRPWifiChangeListener.STATE_SUCCESS -> conn.connectWifi()
                    CRPWifiChangeListener.STATE_LOW_BATTERY -> finishSync(false, "Low battery")
                    CRPWifiChangeListener.STATE_TIMEOUT -> finishSync(false, "Wi-Fi timeout")
                    CRPWifiChangeListener.STATE_BUSY -> finishSync(false, "Device busy")
                }
            }
            override fun onWifiConnectionStateChanged(connected: Boolean) {
                if (!syncInProgress) return
                Log.i(TAG, "Wi-Fi connected=$connected")
                if (connected) conn.downloadMediaFile(downloadCallback) else finishSync(false, "Wi-Fi disconnected")
            }
            override fun onLiveUrlChanged(url: String?) { /* live streaming — unused */ }
        })
    }

    /** Pull battery, firmware version and media counts once the link is up. */
    private fun requestInitialData(conn: CRPBleConnection) {
        conn.syncTime()
        conn.queryBattery()
        conn.queryNewMediaFile()
        conn.queryDeviceVersion(VersionInfo.VersionType.VerFirmware) { info ->
            Log.i(TAG, "Firmware version: ${info?.ver}")
            updateSystemInfo(firmwareVersion = info?.ver ?: "", deviceModel = "Cyan Sports Glass")
        }
        // Voice/glass wakeup: surface the device AI-dialogue rising edge as GlassVoiceWakeup via a
        // PASSIVE feature-state listener. Do NOT call sendVoiceWakeUpState()/queryFeatureActiveState()
        // here — the active wakeword-enable reconfigures the mic/AI subsystem and was dropping the
        // link on every (re)connect (looping). The button/wakeword trigger still pushes feature-state
        // updates to the listener. Defensive try/catch in case the listener path pulls the missing
        // Jieli audio lib.
        try {
            conn.setFeatureActiveStateListener { rs ->
                // Only act on aiDialogue == true (wakeup); ignore false and hold no dialogue state.
                // Repeated true frames are deduped downstream — handleVoiceWakeup ignores wakeups when
                // the mic is already on — and the agent drives the device flow status from its audio
                // state.
                if (rs.aiDialogue) {
                    Log.i(TAG, "AI dialogue true → voice wakeup")
                    scope.launch { onVoiceWakeup() }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "wakeup wiring failed (feature-state listener) — skipping", e)
        }
    }

    // ── Classic-BT bonding (A2DP speaker + SCO mic) ────────────────────────────────
    // The CRP SDK only establishes the BLE/GATT control link. The glasses' audio path uses Classic
    // Bluetooth (A2DP out, SCO/HFP mic), and Android only surfaces those endpoints for a Classic-bonded
    // device. Sometimes the device auto-bonds, sometimes not — when it doesn't, agent audio falls back
    // to the phone. So once BLE is up we explicitly ensure the bond via the native BluetoothDevice the
    // SDK exposes (CRPBleDevice.getBluetoothDevice()).

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT already granted for the BLE connect
    private fun ensureClassicBond() {
        val btDevice = bleDevice?.bluetoothDevice ?: run {
            Log.w(TAG, "ensureClassicBond: no underlying BluetoothDevice")
            return
        }
        Log.i(TAG, "Classic BT bond check for ${btDevice.address}: state=${btDevice.bondState}")
        when (btDevice.bondState) {
            BluetoothDevice.BOND_BONDED -> {
                Log.i(TAG, "Classic BT already bonded — connecting A2DP profile")
                connectA2dp(btDevice)
            }
            BluetoothDevice.BOND_BONDING -> registerBondReceiver()          // already pairing; just watch
            else -> {                                                       // BOND_NONE
                bondRetries = 0
                registerBondReceiver()
                Log.i(TAG, "Classic BT not bonded — initiating createBond()")
                if (!btDevice.createBond()) Log.w(TAG, "createBond() returned false")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerBondReceiver() {
        if (bondReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val dev: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                val target = bleDevice?.bluetoothDevice
                if (dev == null || target == null || dev.address != target.address) return
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                Log.i(TAG, "Bond state changed for ${dev.address}: $state")
                when (state) {
                    BluetoothDevice.BOND_BONDED -> {
                        Log.i(TAG, "Classic BT bonded — connecting A2DP profile")
                        connectA2dp(target)
                        unregisterBondReceiver()
                    }
                    BluetoothDevice.BOND_NONE -> {
                        if (bondRetries < maxBondRetries && bleDevice?.isConnected == true) {
                            bondRetries++
                            Log.w(TAG, "Bond failed — retrying createBond() ($bondRetries/$maxBondRetries)")
                            scope.launch {
                                delay(1500)
                                if (bleDevice?.isConnected == true) target.createBond()
                            }
                        } else {
                            Log.w(TAG, "Bond failed — giving up after $bondRetries retries")
                            unregisterBondReceiver()
                        }
                    }
                }
            }
        }
        try {
            ctx.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
            bondReceiver = receiver
        } catch (e: Exception) {
            Log.w(TAG, "registerBondReceiver failed", e)
        }
    }

    private fun unregisterBondReceiver() {
        bondReceiver?.let {
            try { ctx.unregisterReceiver(it) } catch (e: Exception) { /* not registered */ }
        }
        bondReceiver = null
        bondRetries = 0
    }

    /**
     * Connect the Classic A2DP audio profile once the device is bonded. Pairing alone does NOT
     * route audio — the profile must be connected (BluetoothDevice has no usable public connect()
     * for Classic; the real connect lives on the hidden A2DP proxy). We reuse the K900 SDK's
     * BluetoothA2dpController, which does the version-correct hidden-API connect and is proven on
     * these phones. SCO mic is started on demand by Agent.kt (same as K900) — A2DP only here.
     */
    /**
     * Warm the BLE controller cache for [deviceId] before a direct connect. On Android a GATT
     * connect to an address the controller hasn't seen advertise fails immediately
     * (CONNECTING -> DISCONNECTED in ms) — which is why scan-then-connect works but a cold
     * reconnect doesn't. Resolves as soon as the target MAC advertises; returns false on timeout
     * (caller still attempts the connect as a fallback — no worse than today).
     */
    @SuppressLint("MissingPermission")
    private suspend fun warmScanFor(deviceId: String, timeoutMs: Long): Boolean {
        val client = client ?: return false
        val seen = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Boolean> { cont ->
                cont.invokeOnCancellation { runCatching { client.cancelScan() } }
                val started = client.scanDevice(object : CRPScanCallback {
                    override fun onScanning(device: CRPScanDevice?) {
                        if (device?.device?.address.equals(deviceId, ignoreCase = true)) {
                            runCatching { client.cancelScan() }
                            if (cont.isActive) cont.resume(true)
                        }
                    }
                    override fun onScanComplete(results: MutableList<CRPScanDevice>?) {
                        if (cont.isActive) {
                            cont.resume(results?.any { it.device?.address.equals(deviceId, true) } == true)
                        }
                    }
                }, timeoutMs)
                if (!started && cont.isActive) cont.resume(false)  // scan failed to start
            }
        } ?: false
        runCatching { client.cancelScan() }
        Log.i(TAG, "Pre-connect warm scan for $deviceId: seen=$seen")
        return seen
    }

    /** Ground-truth check: is A2DP actually connected to [device] right now? */
    @SuppressLint("MissingPermission")
    private fun isA2dpConnectedTo(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        val controller = a2dpController ?: return false
        return controller.isA2dpConnected && controller.connectedDevices.any { it.address == device.address }
    }

    @SuppressLint("MissingPermission")
    private fun connectA2dp(device: BluetoothDevice) {
        a2dpRetries = 0
        try {
            val controller = a2dpController ?: BluetoothA2dpController.getInstance().build(ctx).also {
                it.setBluetoothListener(a2dpListener)
                a2dpController = it
            }
            if (isA2dpConnectedTo(device)) {
                Log.i(TAG, "A2DP already connected to ${device.address}")
                return
            }
            Log.i(TAG, "Connecting A2DP profile to ${device.address}")
            controller.connect(device)
        } catch (e: Throwable) {
            Log.w(TAG, "connectA2dp failed: ${e.message}", e)
        }
    }

    /** Listener for the A2DP controller. All methods are no-ops except the A2DP connect result. */
    private val a2dpListener = object : BluetoothListener {
        override fun onReadData(device: BluetoothDevice?, data: ByteArray?) {}
        override fun onActionStateChanged(prev: Int, state: Int) {}
        override fun onActionDiscoveryStateChanged(action: String?) {}
        override fun onActionScanModeChanged(prev: Int, mode: Int) {}
        override fun onBluetoothServiceStateChanged(state: Int) {}
        override fun onActionDeviceFound(device: BluetoothDevice?, rssi: Short, record: ByteArray?) {}
        override fun onBondStateChange(device: BluetoothDevice?, state: Int) {
            Log.i(TAG, "A2DP ctrl bond state ${device?.address}: $state")
        }
        @SuppressLint("MissingPermission")
        override fun onA2dpConnectResult(device: BluetoothDevice?, success: Boolean) {
            Log.i(TAG, "A2DP connect result for ${device?.address}: $success")
            // The controller flaps: every connect emits an interleaved success=true (connect
            // *accepted*) followed by a spurious failure, even when the profile is already
            // connected. Trusting `success` reset a2dpRetries to 0 each cycle (cap never held) and
            // the retry path reconnected an already-connected device — an infinite ~1 Hz loop.
            // Trust live state, not the callback flag.
            if (isA2dpConnectedTo(device)) {
                a2dpRetries = 0
                Log.i(TAG, "A2DP connected to ${device?.address} — stopping retries")
                return
            }
            if (success) return  // accepted but not yet connected; wait for a real result
            if (device != null && a2dpRetries < maxA2dpRetries && bleDevice?.isConnected == true) {
                a2dpRetries++
                Log.w(TAG, "A2DP connect failed — retrying ($a2dpRetries/$maxA2dpRetries)")
                scope.launch {
                    delay(1000)
                    // Re-check before reconnecting; it may have settled during the delay.
                    if (!isA2dpConnectedTo(device) && bleDevice?.isConnected == true) {
                        try { a2dpController?.connect(device) } catch (e: Throwable) { Log.w(TAG, "A2DP retry failed: ${e.message}") }
                    }
                }
            } else {
                Log.w(TAG, "A2DP connect failed — giving up after $a2dpRetries retries")
            }
        }
    }

    override suspend fun disconnect() {
        Log.i(TAG, "Disconnecting")
        unregisterBondReceiver()  // stop watching bond state; leave the bond itself in place
        bleDevice?.takeIf { it.isConnected }?.disconnect()
        connection = null
        updateState(GlassConnectionState.IDLE, "User disconnected")
        onDisconnected(reason = "User disconnected", wasExpected = true)
    }

    override fun isConnected(): Boolean = bleDevice?.isConnected == true

    // ── System / storage info ──────────────────────────────────────────────────

    override suspend fun getSystemInfo() = run {
        connection?.queryBattery()
        super.getSystemInfo()
    }

    override suspend fun getStorageInfo(): GlassStorageInfo {
        connection?.queryNewMediaFile()
        return super.getStorageInfo()
    }

    // ── Camera ──────────────────────────────────────────────────────────────────

    override suspend fun takePhoto(id: Int?, filename: String?): PhotoResult {
        val conn = connection ?: return PhotoResult(success = false, errorCode = -2)
        Log.i(TAG, "Taking photo (ModeNormal)")
        // The CRP SDK exposes no shutter-ack listener, so normal-photo is fire-and-forget: the
        // photo lands on device storage. Report success once sent; refresh counts afterward so
        // the new file surfaces via MSG_GLASS_STORAGE_INFO.
        conn.takePhoto(TakePhoto.PhotoMode.ModeNormal)
        conn.queryNewMediaFile()
        return PhotoResult(success = true)
    }

    override suspend fun takeAiImage(): String {
        val conn = connection ?: throw IllegalStateException("Not connected to cyan_sports device")
        if (pendingAiImage != null) throw IllegalStateException("AI image capture already in progress")
        Log.i(TAG, "takeAiImage (ModeAIRecognition)")
        val deferred = CompletableDeferred<String>()
        pendingAiImage = deferred
        return try {
            // Triggers an AI-recognition capture; the JPEG arrives via the AI-dialogue listener's
            // onDialogueImageChange callback registered in connect().
            conn.takePhoto(TakePhoto.PhotoMode.ModeAIRecognition)
            withTimeout(30_000L) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            throw Exception("cyan_sports AI image timed out (30s)")
        } finally {
            pendingAiImage = null
        }
    }

    // The CRP SDK's public CRPBleConnection exposes only video *config*, not a record trigger
    // (iOS has setVideoRecord; Android doesn't). The firmware protocol is identical, so we build
    // the same VideoRecord protobuf the iOS SDK sends and push it through the SDK's own internal
    // BLE transport via sendRawCommand(). VideoRecord is command (model=2, feature=1).
    override suspend fun startVideo(id: Int?, filename: String?): VideoResult {
        Log.i(TAG, "startVideo (VideoRecord.StartRecord)")
        val record = VideoRecord.newBuilder()
            .setAction(VideoRecord.VideoAction.StartRecord)
            .setConfig(VideoConfig.newBuilder().setFps(30).setMaxDuration(0).build())
            .build()
        val ok = sendRawCommand(2, 1, record.toByteArray())
        return VideoResult(success = ok, errorCode = if (ok) 0 else -3)
    }

    override suspend fun stopVideo() {
        Log.i(TAG, "stopVideo (VideoRecord.StopRecord)")
        val record = VideoRecord.newBuilder()
            .setAction(VideoRecord.VideoAction.StopRecord)
            .setConfig(VideoConfig.newBuilder().setFps(0).setMaxDuration(0).build())
            .build()
        sendRawCommand(2, 1, record.toByteArray())
    }

    /**
     * Sends a raw (model, feature) command through the CRP SDK's internal BLE transport — used for
     * commands the public CRPBleConnection doesn't expose (e.g. VideoRecord). Reuses the SDK's own
     * framer (`com.moyoung.d.j.a`, which produces `[0x4D 0x59][len_lo len_hi][model][feature][payload]`)
     * and its write pipeline (`com.moyoung.i.d.a(bytes, priority, callback)`), reaching the
     * transport instance via the connection impl's private field.
     *
     * All obfuscated-name access is reflective and guarded: a future SDK re-shade that renames
     * these symbols degrades to "unsupported" (returns false) rather than failing the build or
     * crashing. Awaits the device's command ack like the other suspend methods.
     */
    private suspend fun sendRawCommand(model: Int, feature: Int, payload: ByteArray): Boolean {
        val conn = connection ?: return false
        return try {
            val framer = Class.forName("com.moyoung.d.j")
                .getMethod("a", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, ByteArray::class.java)
            val frame = framer.invoke(null, model, feature, payload) as ByteArray

            val transport = conn.javaClass.getDeclaredField("b")
                .apply { isAccessible = true }
                .get(conn)
            val priorityClass = Class.forName("com.moyoung.j.a\$a")
            val priority = priorityClass.getField("a").get(null)
            val sendMethod = transport.javaClass.getMethod(
                "a", ByteArray::class.java, priorityClass, CRPCommandCallback::class.java
            )

            withTimeout(10_000L) {
                val deferred = CompletableDeferred<Boolean>()
                sendMethod.invoke(transport, frame, priority, object : CRPCommandCallback {
                    override fun onSuccess() { deferred.complete(true) }
                    override fun onFailure(code: Int) {
                        Log.w(TAG, "sendRawCommand($model,$feature) onFailure: $code")
                        deferred.complete(false)
                    }
                })
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "sendRawCommand($model,$feature) timed out")
            false
        } catch (t: Throwable) {
            Log.w(TAG, "sendRawCommand($model,$feature) failed: ${t.message}")
            false
        }
    }

    // ── Audio recording ──────────────────────────────────────────────────────────

    override suspend fun startAudio(filename: String?): AudioResult {
        val conn = connection ?: return AudioResult(success = false, errorCode = -2)
        return withTimeout(30_000L) {
            val deferred = CompletableDeferred<AudioResult>()
            // duration 0 = unlimited (stopped explicitly via stopAudio()).
            conn.startAudio(0, object : CRPCommandCallback {
                override fun onSuccess() { deferred.complete(AudioResult(success = true)) }
                override fun onFailure(code: Int) { deferred.complete(AudioResult(success = false, errorCode = code)) }
            })
            try {
                deferred.await()
            } catch (e: TimeoutCancellationException) {
                AudioResult(success = false, errorCode = -1)
            }
        }
    }

    override suspend fun stopAudio() {
        connection?.stopAudio()
    }

    // ── Media config (video only; photo/audio resolution not exposed by CRP) ───────

    override suspend fun getMediaConfig(): MediaConfigData {
        val conn = connection ?: return defaultMediaConfig()
        return try {
            withTimeout(10_000L) {
                val deferred = CompletableDeferred<MediaConfigData>()
                conn.queryVideoConfig { cfg ->
                    // CRP video config = fps + maxDuration. Map fps→quality, maxDuration→duration.
                    deferred.complete(
                        MediaConfigData(
                            photo = PhotoConfigData(width = 0, height = 0),
                            video = VideoConfigData(width = 0, height = 0, quality = cfg?.fps ?: 0, duration = cfg?.maxDuration ?: 0),
                            audio = AudioConfigData(duration = 0)
                        )
                    )
                }
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            defaultMediaConfig()
        }
    }

    override suspend fun setMediaConfig(config: Map<String, Any>): Boolean {
        val conn = connection ?: return false
        val video = config["video"] as? Map<*, *> ?: run {
            Log.w(TAG, "setMediaConfig: only the 'video' config (fps/duration) is supported on cyan_sports")
            return false
        }
        val fps = (video["quality"] as? Number)?.toInt() ?: (video["fps"] as? Number)?.toInt()
        val duration = (video["duration"] as? Number)?.toInt()
        val builder = VideoConfig.newBuilder()
        fps?.let { builder.setFps(it) }
        
        duration?.let { builder.setMaxDuration(it) }
        conn.sendVideoConfig(builder.build(), object : CRPCommandCallback {
            override fun onSuccess() { Log.i(TAG, "Video config updated") }
            override fun onFailure(code: Int) { Log.e(TAG, "Video config failed: $code") }
        })
        return true
    }

    private fun defaultMediaConfig() = MediaConfigData(
        photo = PhotoConfigData(width = 0, height = 0),
        video = VideoConfigData(width = 0, height = 0, quality = 0, duration = 0),
        audio = AudioConfigData(duration = 0)
    )

    // ── Media sync (Wi-Fi download, handled internally by the CRP SDK) ─────────────

    override suspend fun syncFiles() {
        val conn = connection ?: run { Log.e(TAG, "syncFiles: not connected"); return }
        if (syncInProgress) { Log.w(TAG, "syncFiles: already in progress"); return }
        Log.i(TAG, "syncFiles: enabling Wi-Fi (FILE)")
        syncInProgress = true
        syncFileCount = 0
        syncTotalBytes = 0L
        syncReportedCount = 0
        onSyncStarted()
        // enableWifi(FILE) → onWifiStateChange → connectWifi → onWifiConnectionStateChanged →
        // downloadMediaFile (all driven by the wifi listener + downloadCallback below).
        conn.enableWifi(CRPWifiType.FILE)
    }

    override suspend fun cancelSync() {
        Log.i(TAG, "cancelSync: aborting (syncInProgress=$syncInProgress)")
         sendExitFileMode()

        if (syncInProgress) {
            // Active sync: normal teardown emits SYNC_COMPLETED and runs the exit-frame recovery.
            finishSync(false, "Cancelled")
        } else {
            // No tracked sync, but the device may still be stuck in file mode (e.g. a prior sync
            // ended uncleanly). Force Wi-Fi teardown and always re-send the exit-file-mode frame.
            connection?.disableWifi()
        }
    }

    private val downloadCallback = object : CRPFileDownloadCallback {
        override fun onStart() { Log.i(TAG, "Media download started") }
        override fun onProgress(progress: Int) { /* percentage variant — use the count variant */ }
        override fun onProgress(total: Int, downloadedCount: Int) {
            // The device reports the running download count here; remember it for the completion
            // event since onDownloadFile (where files are saved/tallied) only fires after onSuccess.
            syncReportedCount = downloadedCount
            onSyncProgress(downloadedCount, total)
        }
        override fun onDownloadFile(dirPath: String?, filePaths: MutableList<String>?) {
            // The SDK downloads to its own location; move each file into the app media dir under
            // a category subdir (photo/video/audio/other), matching the other adapters so the
            // gallery finds them. Raw .opus audio is rewrapped as Ogg/Opus in place.
            val base = mediaDir() ?: return
            filePaths?.forEach { p ->
                try {
                    val src = File(p).takeIf { it.exists() } ?: File(dirPath ?: "", p)
                    if (!src.exists()) { Log.w(TAG, "Synced file missing: $p"); return@forEach }
                    val sub = subDirFor(src.name)
                    val outputDir = File(base, sub).also { if (!it.exists()) it.mkdirs() }
                    // Extension-less `video-…` files get a `.mp4` suffix so the gallery (which filters
                    // by extension) recognizes them. Renaming the local copy is safe — the SDK handles
                    // device-side deletion internally and doesn't depend on this name.
                    val destName = if (sub == "video" && !hasVideoExt(src.name)) "${src.name}.mp4" else src.name
                    val dest = File(outputDir, destName)
                    src.copyTo(dest, overwrite = true)
                    src.delete()
                    if (src.name.endsWith(".opus", true)) {
                        try {
                            val raw = dest.readBytes()
                            val wrapped = OpusOggWrapper.wrapIfNeeded(raw)
                            if (!wrapped.contentEquals(raw)) dest.writeBytes(wrapped)
                        } catch (e: Exception) {
                            Log.w(TAG, "Opus wrap failed for ${src.name}", e)
                        }
                    }
                    syncFileCount++
                    syncTotalBytes += dest.length()
                    Log.d(TAG, "Saved synced file: ${dest.absolutePath} (${dest.length()} bytes)")
                    // Notify JS per-file so the gallery can index + thumbnail it incrementally.
                    if (sub == "photo" || sub == "video" || sub == "audio") {
                        onFileSynced(dest.absolutePath, sub)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to move synced file $p", e)
                }
            }
        }
        override fun onSuccess() {
            Log.i(TAG, "Media download complete: $syncFileCount files")
            finishSync(true, null)
        }
        override fun onFail(code: Int) {
            Log.e(TAG, "Media download failed: code=$code")
            finishSync(false, "Download failed (code $code)")
        }
    }

    /** Tear down Wi-Fi (exit file-transfer mode) and emit the single terminal SYNC_COMPLETED event. */
    private fun finishSync(success: Boolean, error: String?) {
        if (!syncInProgress) return
        syncInProgress = false
        val conn = connection
        Log.i(TAG, "finishSync: success=$success files=$syncReportedCount error=$error")
        conn?.disableWifi()

        // Report the count the device acked via onProgress — onDownloadFile (which fills
        // syncFileCount/syncTotalBytes) fires only after onSuccess, so those are still 0 here.
        val files = if (syncFileCount > 0) syncFileCount else syncReportedCount
        onSyncCompleted(files, syncTotalBytes, success, error)
        // The SDK's disableWifi() builds the exit-file-mode frame (group 4 / id 2 = 0402) but
        // silently drops the BLE write while it's still tearing down Wi-Fi/file mode, so the
        // glasses never receive it and stay stuck (camera/mic frozen). After a settle, send the
        // 0402 exit frame ourselves through the SDK's write path, with retries (the official app
        // also re-sends 0402 repeatedly), then re-query media counts.
        sendExitFileMode()
    }

    /**
     * Re-send the exit-file-mode frame (0402) that the SDK's disableWifi() fails to deliver, with
     * retries after a settle, then re-query media counts. Recovers a device stuck in file-transfer
     * mode. Called from finishSync() and from cancelSync() (even when no sync is tracked).
     */
    private fun sendExitFileMode() {
        val conn = connection
        scope.launch {
            // delay(SYNC_EXIT_SETTLE_MS)
            Log.i(TAG, "sendExitFileMode: sending raw exit-file-mode frame (0402)")
            repeat(3) {
                sendRawFrame(group = 0x04, id = 0x02)
                delay(400)
            }
            conn?.queryNewMediaFile()
        }
    }

    /**
     * Send a raw MY-protocol frame [0x4D,0x59, lenLo,lenHi, group, id, ...payload] directly through
     * the SDK's BLE write path, bypassing the higher-level CRP methods. Used to issue the
     * exit-file-mode command (group 4 / id 2) that disableWifi() fails to deliver after a sync.
     *
     * Reflection note: the connection impl is com.moyoung.f.b with a private `void a(byte[])` that
     * forwards to the BLE writer. Names are obfuscated — if a future SDK build renames this, the
     * call is caught and logged (camera-after-sync would regress but nothing crashes).
     */
    private fun sendRawFrame(group: Int, id: Int, payload: ByteArray = ByteArray(0)) {
        val conn = connection ?: return
        val len = payload.size
        val frame = byteArrayOf(0x4D, 0x59, (len and 0xFF).toByte(), ((len shr 8) and 0xFF).toByte(), group.toByte(), id.toByte()) + payload
        try {
            val m = conn.javaClass.getDeclaredMethod("a", ByteArray::class.java).apply { isAccessible = true }
            m.invoke(conn, frame)
            Log.i(TAG, "sendRawFrame ok: ${frame.joinToString("") { "%02x".format(it) }}")
        } catch (e: Throwable) {
            Log.e(TAG, "sendRawFrame failed (group=$group id=$id) — SDK internal write method not found?", e)
        }
    }

    private fun mediaDir(): File? = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)

    /** Category subdirectory by extension — mirrors CyanFileSyncManager so the gallery layout matches. */
    private fun subDirFor(filename: String): String = when {
        filename.endsWith(".jpg", true) || filename.endsWith(".jpeg", true) || filename.endsWith(".png", true) -> "photo"
        hasVideoExt(filename) -> "video"
        filename.endsWith(".mp3", true) || filename.endsWith(".wav", true) || filename.endsWith(".aac", true) ||
            filename.endsWith(".m4a", true) || filename.endsWith(".opus", true) -> "audio"
        // The device names videos with a `video-` prefix and NO extension — classify those as video.
        filename.startsWith("video", true) -> "video"
        else -> "other"
    }

    private fun hasVideoExt(filename: String): Boolean =
        filename.endsWith(".mp4", true) || filename.endsWith(".mov", true) || filename.endsWith(".avi", true)

    // ── Device control ────────────────────────────────────────────────────────────

    override suspend fun reboot() {
        connection?.restart(loggingCallback("restart"))
    }

    override suspend fun factoryReset() {
        connection?.reset(loggingCallback("reset"))
    }

    override suspend fun setAiStatus(status: String) {
        // sendAIDialogueState runs through the CRP AI-dialogue subsystem (decodes via Jieli
        // jl_audio_decode); the OpusManager stub now keeps it from crashing, so we drive it.
        val conn = connection ?: return
        try {
            when (status) {
                "speaking_start", "thinking_start" ->
                    conn.sendAIDialogueState(FlowStatus.FlowStatusType.FlowStatusStart)
                "speaking_stop", "thinking_stop" ->
                    // Just exit the AI-reply flow (no FlowStatusComplete — mirrors iOS exitAIReply).
                    conn.exitAIDialogue()
                else -> Log.w(TAG, "setAiStatus unknown: $status")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "setAiStatus failed: $status", e)
        }
    }

    override suspend fun setDeviceName(name: String) {
        Log.w(TAG, "setDeviceName not supported on cyan_sports (no CRP API)")
    }

    override suspend fun clearMedia() {
        Log.w(TAG, "clearMedia not supported on cyan_sports (no CRP delete-all API)")
    }

    override suspend fun sendCommand(command: String, params: Map<String, Any>) {
        Log.w(TAG, "sendCommand not implemented: $command")
    }

    override fun destroy() {
        super.destroy()
        unregisterBondReceiver()
        try { a2dpController?.release() } catch (e: Throwable) { /* ignore */ }
        a2dpController = null
        a2dpRetries = 0
        bleDevice?.takeIf { it.isConnected }?.disconnect()
        connection = null
        bleDevice = null
    }

    private fun loggingCallback(op: String) = object : CRPCommandCallback {
        override fun onSuccess() { Log.i(TAG, "$op ok") }
        override fun onFailure(code: Int) { Log.e(TAG, "$op failed: $code") }
    }
}
