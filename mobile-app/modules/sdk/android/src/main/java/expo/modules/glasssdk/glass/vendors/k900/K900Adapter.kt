package expo.modules.glasssdk.glass.vendors.k900

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.util.Log
import com.xy.ksdk.api.cmd.CmdDealManager
import com.xy.ksdk.api.cmd.CmdList
import com.xy.ksdk.api.cmd.CmdSendManager
import com.xy.ksdk.api.cmd.CmdType
import com.xy.ksdk.cmd.base.Cmd
import expo.modules.glasssdk.glass.vendors.k900.cmds.S_StartStream
import expo.modules.glasssdk.glass.vendors.k900.cmds.S_StopStream
import expo.modules.glasssdk.glass.AudioConfigData
import expo.modules.glasssdk.glass.AudioResult
import expo.modules.glasssdk.glass.BaseGlassAdapter
import expo.modules.glasssdk.glass.GlassConnectionState
import expo.modules.glasssdk.glass.GlassEventConstants
import expo.modules.glasssdk.glass.GlassEventConstants.MSG_GLASS_BT_STATUS
import expo.modules.glasssdk.glass.GlassEventMsg
import expo.modules.glasssdk.glass.GlassStorageInfo
import expo.modules.glasssdk.glass.GlassSystemInfo
import expo.modules.glasssdk.glass.MediaConfigData
import expo.modules.glasssdk.glass.PhotoConfigData
import expo.modules.glasssdk.glass.PhotoResult
import expo.modules.glasssdk.glass.VideoConfigData
import expo.modules.glasssdk.glass.VideoResult
import org.greenrobot.eventbus.EventBus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * K900 glasses adapter - uses K900 SDK with BLE connection helper
 * Copies BLE logic from BleService.java while keeping vendor code isolated
 */
class K900Adapter(private val ctx: Context) : BaseGlassAdapter(),
    CmdDealManager.BleCmdRecvListener {
    companion object {
        private const val TAG = "[Glass:K900Adapter]"
    }

    // BLE connection handler
    private val bleConnection = K900BleConnection(ctx)

    // Internal state - managed by adapter
    private var connected = false
    private var deviceReady = false
    private var battery = 0
    private var batteryVoltage = 0.0;
    private var firmware = ""
    private var app = ""
    private var storage = GlassStorageInfo(
        totalSize = 0L,
        freeSize = 0L,
        usedSize = 0L,
        photoCount = 0,
        videoCount = 0,
        audioCount = 0
    )

    // AI image request tracking
    private var pendingAiImagePath: CompletableDeferred<String>? = null

    // Store discovered devices: device.address -> (deviceName, btAddress)
    private data class DeviceInfo(val name: String, val btAddress: String)

    private val discoveredDevices =
        mutableMapOf<String, DeviceInfo>() // device.address -> DeviceInfo

    // Generic command request tracking
    private enum class CommandType {
        PHOTO,
        VIDEO_START,
        VIDEO_STOP,
        AUDIO_START,
        AUDIO_STOP,
        GET_MEDIA_CONFIG
    }

    // Track single pending request per command type (one at a time)
    private val pendingRequests = ConcurrentHashMap<CommandType, CompletableDeferred<Any>>()

    // Cache media config for quick access
    private var mediaConfig: MediaConfigData? = null

    // Stream state
    private var pendingStreamDeferred: CompletableDeferred<Map<String, Any>>? = null
    private var localHotspotManager: LocalHotspotManager? = null

    // File sync state
    private var isSyncing = false
    private var syncTotalFiles = 0
    private var syncCompletedFiles = 0
    private var syncTotalBytes = 0L
    private var currentSyncFile: String? = null
    private var fileSyncManager: K900FileSyncManager? = null

    // A2DP service state tracking
    private var a2dpServiceStarted = false

    override val capabilities: Map<String, Boolean> = mapOf(
        "audio" to true,
        "sensors" to false,
        "display" to true,
        "wakeword" to false,
        "battery" to true,
        "camera" to true
    )

    override suspend fun init(context: Context) {
        Log.d(TAG, "Initializing K900 adapter")


        CmdSendManager.getInstance().init(context)
        CmdDealManager.getInstance().init(context, this)

        bleConnection.init(object : K900BleConnection.ConnectionCallback {
            override fun onConnectStateChange(connected: Boolean) {
                val wasConnected = this@K900Adapter.connected
                this@K900Adapter.connected = connected
                Log.d(TAG, "BLE connection state changed: $wasConnected -> $connected")

                if (connected && !wasConnected) {
                    // Newly connected
                    CmdSendManager.getInstance().setBluetooth(bleConnection.getBleController())

                    scope.launch {
                        // Get device info from discovered devices
                        val deviceInfo = currentDeviceId?.let { discoveredDevices[it] }
                        if (deviceInfo != null) {
                            currentDeviceName = deviceInfo.name
                        } else {
                            currentDeviceName = "K900 Device"
                        }

                        updateState(GlassConnectionState.CONNECTED)
                        onConnected()

                        // Request initial data after BLE stack settles (mirrors iOS)
                        delay(1000)
                        if (this@K900Adapter.connected) {
                            CmdSendManager.getInstance().sendGetBatteryVol()
                            CmdSendManager.getInstance().sendGetSystemVersion()
                            CmdSendManager.getInstance().sendGetStorageInfo()
                        }
                    }
                } else if (!connected && wasConnected) {
                    // Newly disconnected (only if we were previously connected)
                    resetDeviceState()
                    scope.launch {
                        updateState(GlassConnectionState.IDLE, "Disconnected")
                        onDisconnected(wasExpected = false, reason = "BLE connection lost")
                    }
                }
            }

            override fun onBleRecvData(data: ByteArray) {
                // Pass to CmdDealManager (from BleService line 222)
                CmdDealManager.getInstance().deal(data)
            }

            @SuppressLint("MissingPermission")
            override fun onDeviceFound(btAddress: String, device: BluetoothDevice, rssi: Short) {
                Log.d(
                    TAG,
                    "Found device: ${device.name} (btAddress=$btAddress, deviceAddress=${device.address})"
                )

                // Store device info: device.address -> (name, btAddress)
                // This allows us to look up the btAddress when connecting via device.address
                val deviceName = device.name ?: "Unknown"
                discoveredDevices[device.address] = DeviceInfo(deviceName, btAddress)

                // Forward to base adapter to emit event to JS layer
                // Send device.address as deviceId so we can use it for connection
                scope.launch {
                    onDeviceFound(
                        deviceId = device.address,  // Use device.address for connection
                        deviceName = deviceName,
                        rssi = rssi.toInt()
                    )
                }
            }

            override fun onAiImageData(imageData: ByteArray) {
                Log.d(TAG, "Received AI image data from glass: ${imageData.size} bytes")
                scope.launch {
                    try {
                        // Check if we have a pending request
                        if (pendingAiImagePath == null) {
                            Log.w(TAG, "Received AI image data but no pending request - ignoring")
                            return@launch
                        }

                        // Save image bytes to file
                        Log.d(TAG, "Saving AI image to file...")
                        val filePath = saveAiImageFromBytes(imageData)
                        Log.i(TAG, "AI image saved successfully: $filePath")
                        pendingAiImagePath?.complete(filePath)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to process AI image data", e)
                        pendingAiImagePath?.completeExceptionally(e)
                    }
                }
            }
        })


        fileSyncManager = K900FileSyncManager(context, this.getMediaDirectory(context), scope)
        localHotspotManager = LocalHotspotManager(context)


        fileSyncManager?.init(object : K900FileSyncManager.FileSyncCallback {
            override fun onFileProgress(
                fileType: Int,
                filePath: String,
                fileSize: Int,
                percent: Int
            ) {
                Log.d(TAG, "File receiving: ${File(filePath).name} - $percent%")
            }

            override fun onFileFinish(fileType: Int, filePath: String, fileSize: Int) {
                scope.launch {
                    syncCompletedFiles++
                    syncTotalBytes += fileSize.toLong()

                    // Emit overall progress
                    onSyncProgress(syncCompletedFiles, syncTotalFiles)

                    // Notify per-file so the gallery indexes + thumbnails this file incrementally.
                    val category = when (fileType.toByte()) {
                        CmdType.CMD_TYPE_PHOTO -> "photo"
                        CmdType.CMD_TYPE_VIDEO -> "video"
                        CmdType.CMD_TYPE_AUDIO -> "audio"
                        else -> null
                    }
                    if (category != null) onFileSynced(filePath, category)

                    // Update storage info (decrement count)
                    val current = storageInfo
                    val updated = when (fileType.toByte()) {
                        CmdType.CMD_TYPE_PHOTO -> current.copy(
                            photoCount = (current.photoCount - 1).coerceAtLeast(
                                0
                            )
                        )

                        CmdType.CMD_TYPE_VIDEO -> current.copy(
                            videoCount = (current.videoCount - 1).coerceAtLeast(
                                0
                            )
                        )

                        CmdType.CMD_TYPE_AUDIO -> current.copy(
                            audioCount = (current.audioCount - 1).coerceAtLeast(
                                0
                            )
                        )

                        else -> current
                    }
                    updateStorageInfo(updated)

                    Log.d(
                        TAG,
                        "File sync completed: ${File(filePath).name} ($fileSize bytes) - $syncCompletedFiles/$syncTotalFiles"
                    )
                }
            }

            override fun onError(error: Exception) {
                Log.e(TAG, "File sync failed: ${error.message}", error)
                isSyncing = false
                onSyncCompleted(
                    totalFiles = syncCompletedFiles,
                    totalBytes = syncTotalBytes,
                    success = false,
                    error = error.message
                )
            }

            override fun onFilesRequested() {
                Log.d(TAG, "Files requested - waiting for transfer via socket")
            }

            override fun onWifiConnectedForStream(host: String, port: Int) {
                val d = pendingStreamDeferred
                pendingStreamDeferred = null
                d?.complete(mapOf("host" to host, "port" to port))
                // Automatically start glass video track in the agent (if connected)
                expo.modules.glasssdk.agent.AgentManager.getInstance(ctx)
                    .startGlassVideoStream(host, port)
            }

        })

        Log.d(TAG, "K900 adapter initialized")
    }

    override suspend fun onConnected() {
        super.onConnected()  // Call base implementation

        // Start A2DP audio service for audio streaming to glasses
        currentDeviceId?.let { deviceId ->
            val deviceInfo = discoveredDevices[deviceId]
            if (deviceInfo != null && deviceInfo.btAddress.isNotEmpty()) {
                Log.d(TAG, "Starting A2DP service for BT address: ${deviceInfo.btAddress}")
                startA2dpService(deviceInfo.btAddress)
            } else {
                Log.w(TAG, "No BT address found for device, cannot start A2DP")
            }
        }
    }

    /**
     * Start A2DP audio service for audio streaming to glasses
     */
    private fun startA2dpService(btAddress: String) {
        try {
            A2dpService.setBtStateCallback { connected ->
                EventBus.getDefault().post(GlassEventMsg(cmd = MSG_GLASS_BT_STATUS, n1 = if (connected) 1 else 0))
            }
            val intent = Intent(ctx, A2dpService::class.java).apply {
                putExtra(A2dpService.EXTRA_DEVICE_ADDRESS, btAddress)
                putExtra(A2dpService.EXTRA_COMMAND, A2dpService.CMD_START)
            }
            ctx.startService(intent)
            a2dpServiceStarted = true
            Log.d(TAG, "A2DP service started for address: $btAddress")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start A2DP service (non-fatal)", e)
            // Don't throw - A2DP failure shouldn't break glass connection
        }
    }

    /**
     * Stop A2DP audio service
     */
    private fun stopA2dpService() {
        A2dpService.setBtStateCallback(null)
        if (a2dpServiceStarted) {
            try {
                val intent = Intent(ctx, A2dpService::class.java).apply {
                    putExtra(A2dpService.EXTRA_COMMAND, A2dpService.CMD_STOP)
                }
                ctx.startService(intent)
                ctx.stopService(Intent(ctx, A2dpService::class.java))
                a2dpServiceStarted = false
                Log.d(TAG, "A2DP service stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop A2DP service", e)
                // Continue cleanup even if stop fails
            }
        }
    }

    override suspend fun connect(deviceId: String) {
        Log.d(TAG, "Connecting to K900: $deviceId")

        // Validate MAC address format
        if (!deviceId.matches(Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$", RegexOption.IGNORE_CASE))) {
            throw IllegalArgumentException("Invalid BLE address: $deviceId")
        }

        // Store device ID for connection callback
        currentDeviceId = deviceId

        updateState(GlassConnectionState.CONNECTING, "Connecting to K900")

        // Use K900BleConnection helper (from BleService.startBLE line 211)
        bleConnection.connect(deviceId)
        // Connection callback will handle state changes
    }

    override suspend fun disconnect() {
        Log.d(TAG, "Disconnecting from K900")

        if (!connected) {
            Log.w(TAG, "Already disconnected, ignoring")
            return
        }

        // Stop A2DP service before disconnecting BLE
        stopA2dpService()

        bleConnection.disconnect()
        resetDeviceState()

        updateState(GlassConnectionState.IDLE, "Disconnected")
        onDisconnected(wasExpected = true, reason = "User requested")
    }

    /**
     * Reset all device-specific state
     */
    private fun resetDeviceState() {
        connected = false
        deviceReady = false
        battery = 0
        batteryVoltage = 0.0
        firmware = ""
        app = ""
        currentDeviceId = null
        currentDeviceName = null

        // Reset A2DP state
        a2dpServiceStarted = false
    }

    override fun isConnected(): Boolean = connected

    override suspend fun sendCommand(command: String, params: Map<String, Any>) {
        Log.d(TAG, "Sending command: $command")

        if (!connected) {
            throw IllegalStateException("Not connected to K900 device")
        }

        val cmdMgr = CmdSendManager.getInstance();


//        when (command) {
//            "take_photo" -> cmdMgr.sendTakePhoto()
//            "start_video" -> cmdMgr.sendStartVideo()
//            "stop_video" -> cmdMgr.sendStopVideo()
//            "start_audio" -> cmdMgr.sendStartAudio()
//            "stop_audio" -> cmdMgr.sendStopAudio()
//            "set_brightness" -> {
//                val level = (params["level"] as? Number)?.toInt() ?: 50
//                cmdMgr.sendSetBrightness(level)
//            }
//            "set_volume" -> {
//                val level = (params["level"] as? Number)?.toInt() ?: 50
//                cmdMgr.sendSetVolume(level)
//            }
//            else -> throw IllegalArgumentException("Unsupported command: $command")
//        }
    }

    override suspend fun getStorageInfo(): GlassStorageInfo {
        CmdSendManager.getInstance().sendGetStorageInfo();

        return super.getStorageInfo();
    }

    override suspend fun getSystemInfo(): GlassSystemInfo {
        CmdSendManager.getInstance().sendGetSystemVersion()

        return super.getSystemInfo()
    }

    override suspend fun setDeviceName(name: String) {
        // K900 Android SDK only exposes the BT Classic name (no sendSetBleName in the AAR),
        // so we set just that. iOS additionally sets the BLE name.
        CmdSendManager.getInstance().sendSetBTName(name)
    }

    override suspend fun startScan() {
        Log.d(TAG, "Starting BLE scan for K900 devices")
        discoveredDevices.clear()
        updateState(GlassConnectionState.SCANNING, "Scanning for devices")
        bleConnection.startScan()
    }

    override suspend fun stopScan() {
        Log.d(TAG, "Stopping BLE scan")
        bleConnection.cancelScan()

        // Only update state to IDLE if we're currently scanning
        if (connectionState == GlassConnectionState.SCANNING) {
            updateState(GlassConnectionState.IDLE, "Scan stopped")
        }
    }

    /**
     * Execute a command and wait for response
     * Prevents concurrent requests of the same type
     */
    private suspend fun <T> executeCommand(
        commandType: CommandType,
        sendCommand: () -> Unit,
        timeoutMs: Long = 30000
    ): T {
        if (!connected) {
            throw IllegalStateException("Not connected to K900 device")
        }

        // Check if this command type already has a pending request
        if (pendingRequests.containsKey(commandType)) {
            throw IllegalStateException("${commandType.name} command already in progress")
        }

        val deferred = CompletableDeferred<T>()
        pendingRequests[commandType] = deferred as CompletableDeferred<Any>

        try {
            // Send command to device
            sendCommand()

            // Wait for response with timeout
            @Suppress("UNCHECKED_CAST")
            return withTimeout(timeoutMs) {
                deferred.await()
            } as T
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "${commandType.name} command timed out after ${timeoutMs}ms")
            throw e
        } finally {
            // Always clean up pending request
            pendingRequests.remove(commandType)
        }
    }

    /**
     * Complete a pending command request
     */
    private fun <T> completeCommand(commandType: CommandType, result: T) {
        val deferred = pendingRequests.remove(commandType)
        if (deferred != null) {
            @Suppress("UNCHECKED_CAST")
            (deferred as? CompletableDeferred<T>)?.complete(result)
        }
    }

    override suspend fun takePhoto(id: Int?, filename: String?): PhotoResult {
        Log.d(TAG, "Taking photo (id=$id, filename=$filename)")

        return try {
            executeCommand(
                commandType = CommandType.PHOTO,
                sendCommand = {
                    val photoType = id ?: 49
                    // Generate timestamp-based filename if not provided
                    val fileName = filename ?: "photo_${System.currentTimeMillis()}"
                    CmdSendManager.getInstance().sendTakePhoto(photoType, fileName)
                }
            )
        } catch (e: IllegalStateException) {
            PhotoResult(success = false, errorCode = -2) // -2 = already in progress
        } catch (e: TimeoutCancellationException) {
            PhotoResult(success = false, errorCode = -1) // -1 = timeout
        }
    }

    override suspend fun takeAiImage(): String {
        Log.d(TAG, "Taking AI image - sending command to glass")

        if (!connected) {
            throw IllegalStateException("Not connected to K900 device")
        }

        // Prevent concurrent AI image requests
        if (pendingAiImagePath != null) {
            Log.w(TAG, "AI image capture already in progress - rejecting new request")
            throw IllegalStateException("AI image capture already in progress")
        }

        val deferred = CompletableDeferred<String>()
        pendingAiImagePath = deferred

        try {
            // Send AI image command
            Log.d(TAG, "Sending AI image command to K900...")
            CmdSendManager.getInstance().sendAIImg()
            Log.d(TAG, "AI image command sent, waiting for glass response (30s timeout)...")

            // Wait for image data with 90-second timeout
            return withTimeout(120000) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "AI image capture timed out after 120 seconds - glass did not respond")
            throw Exception("Glass did not respond to AI image request (120s timeout). Glass may be busy or not functioning.")
        } catch (e: Exception) {
            Log.e(TAG, "AI image capture failed: ${e.message}", e)
            throw e
        } finally {
            Log.d(TAG, "Clearing pending AI image request")
            pendingAiImagePath = null
        }
    }

    override suspend fun startVideo(id: Int?, filename: String?): VideoResult {
        Log.w(TAG, "Starting video recording")
        return try {
            executeCommand<VideoResult>(
                commandType = CommandType.VIDEO_START,
                sendCommand = {
                    // Use id as photoType (defaults to 49 for standard photo)
                    val videoType = id ?: 49
                    // Generate timestamp-based filename if not provided
                    val fileName = filename ?: "video_${System.currentTimeMillis()}"
                    CmdSendManager.getInstance().sendVideoRecord(videoType, fileName)
                }
            )
        } catch (e: IllegalStateException) {

            VideoResult(success = false, errorCode = -2) // -2 = already in progress
        } catch (e: TimeoutCancellationException) {

            VideoResult(success = false, errorCode = -1) // -1 = timeout
        }
    }

    override suspend fun stopVideo() {
        Log.d(TAG, "Stopping video recording")
        if (!connected) {
            throw IllegalStateException("Not connected to K900 device")
        }
        CmdSendManager.getInstance().sendStopVideoRecord()
    }

    override suspend fun startAudio(filename: String?): AudioResult {
        CmdSendManager.getInstance().sendAIImg()
        Log.w(TAG, "Starting audio recording")
        return try {
            executeCommand<AudioResult>(
                commandType = CommandType.AUDIO_START,
                sendCommand = {
                    val fileName = filename ?: ""
                    CmdSendManager.getInstance().sendAudioRecord(fileName)
                }
            )
        } catch (e: IllegalStateException) {

            AudioResult(success = false, errorCode = -2) // -2 = already in progress
        } catch (e: TimeoutCancellationException) {

            AudioResult(success = false, errorCode = -1) // -1 = timeout
        }
    }

    override suspend fun stopAudio() {
        Log.d(TAG, "Stopping audio recording")
        if (!connected) {
            throw IllegalStateException("Not connected to K900 device")
        }
        CmdSendManager.getInstance().sendStopAudioRecord()
    }

    override suspend fun getMediaConfig(): MediaConfigData {
        Log.d(TAG, "Getting media configuration")

        return executeCommand(
            commandType = CommandType.GET_MEDIA_CONFIG,
            sendCommand = {
                CmdSendManager.getInstance().sendGetMediaSettings()
            },
            timeoutMs = 10000
        )
    }

    override suspend fun setMediaConfig(config: Map<String, Any>): Boolean {
        Log.d(TAG, "Setting media configuration: $config")

        if (!connected) {
            throw IllegalStateException("Not connected to K900 device")
        }

        try {
            // Set photo config if provided
            config["photo"]?.let { photoMap ->
                if (photoMap is Map<*, *>) {
                    val width = (photoMap["width"] as? Number)?.toInt() ?: return@let
                    val height = (photoMap["height"] as? Number)?.toInt() ?: return@let

                    Log.d(TAG, "Setting photo config: ${width}x${height}")
                    CmdSendManager.getInstance().sendSetPhotoParam(width, height)
                }
            }

            // Set video config if provided
            config["video"]?.let { videoMap ->
                if (videoMap is Map<*, *>) {
                    val width = (videoMap["width"] as? Number)?.toInt() ?: return@let
                    val height = (videoMap["height"] as? Number)?.toInt() ?: return@let
                    val quality = (videoMap["quality"] as? Number)?.toInt() ?: return@let
                    val duration = (videoMap["duration"] as? Number)?.toInt() ?: return@let

                    Log.d(
                        TAG,
                        "Setting video config: ${width}x${height}, quality=$quality, duration=$duration"
                    )
                    CmdSendManager.getInstance().sendSetVideoParam(width, height, quality, duration)
                }
            }

            // Set audio config if provided
            config["audio"]?.let { audioMap ->
                if (audioMap is Map<*, *>) {
                    val duration = (audioMap["duration"] as? Number)?.toInt() ?: return@let

                    Log.d(TAG, "Setting audio config: duration=$duration")
                    CmdSendManager.getInstance().sendSetAudioParam(duration)
                }
            }

            // Commands sent successfully (fire and forget)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set media config", e)
            throw e
        }
    }

    override suspend fun syncFiles() {
        Log.d(TAG, "Starting file sync")

        if (!connected) {
            throw IllegalStateException("Not connected to K900 device")
        }

        if (isSyncing) {
            throw IllegalStateException("File sync already in progress")
        }

        try {
            // Get total files from storage info
            val totalFiles =
                storageInfo.photoCount + storageInfo.videoCount + storageInfo.audioCount

            // Initialize sync state
            isSyncing = true
            syncTotalFiles = totalFiles
            syncCompletedFiles = 0
            syncTotalBytes = 0L
            currentSyncFile = null

            // Emit sync started event
            onSyncStarted()
            onSyncProgress(0, totalFiles)

            // Delegate to file sync manager (callbacks already wired in init)
            fileSyncManager?.startFileSync()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start file sync", e)
            isSyncing = false
            onSyncCompleted(
                totalFiles = syncCompletedFiles,
                totalBytes = syncTotalBytes,
                success = false,
                error = e.message
            )
            throw e
        }
    }

    override suspend fun cancelSync() {
        if (!isSyncing) return
        Log.i(TAG, "cancelSync: aborting K900 sync")
        isSyncing = false
        // Tear down WiFi + socket (keeps callback so re-sync works).
        fileSyncManager?.cancelSync()
        // K900 sync only emits completion via callback paths, so force the terminal
        // event here so the JS onSyncCompleted(success=false) always fires.
        onSyncCompleted(
            totalFiles = syncCompletedFiles,
            totalBytes = syncTotalBytes,
            success = false,
            error = "Cancelled"
        )
        syncTotalFiles = 0
        syncCompletedFiles = 0
        syncTotalBytes = 0L
    }

    /**
     * Save AI image bytes to file
     */
    private fun saveAiImageFromBytes(imageData: ByteArray): String {
        try {
            // Generate unique filename with timestamp
            val timestamp = System.currentTimeMillis()
            val filename = "ai_image_$timestamp.jpg"

            // Save to app's cache directory
            val cacheDir = ctx.cacheDir
            val imageFile = java.io.File(cacheDir, filename)
            imageFile.writeBytes(imageData)

            Log.d(TAG, "AI image saved to: ${imageFile.absolutePath} (${imageData.size} bytes)")
            return imageFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save AI image", e)
            throw Exception("Failed to save AI image: ${e.message}")
        }
    }

    /**
     * Start WHIP stream: glass connects to WiFi and streams camera directly to the WHIP server.
     * Status is reported asynchronously via GLASS_STREAM_STATUS events (sc_stream BLE replies).
     */
    fun startStream(
        ssid: String,
        pwd: String,
        url: String,
        streamKey: String?,
        fps: Int,
        bitrate: Int
    ) {
        if (!connected) throw IllegalStateException("Not connected to K900 device")
        val bt = bleConnection.getBleController() ?: throw IllegalStateException("BLE controller not available")
        Log.d(TAG, "startStream → ssid=$ssid url=$url fps=$fps bitrate=$bitrate")
        val cmd = S_StartStream()
        cmd.setSsid(ssid)
        cmd.setPwd(pwd)
        cmd.setUrl(url)
        if (!streamKey.isNullOrEmpty()) cmd.setStreamKey(streamKey)
        cmd.setFps(fps)
        cmd.setBitrate(bitrate)
        cmd.send(bt)
    }

    /** Stop the WHIP stream. Glass will reply with sc_stream {status:"stopped"} over BLE. */
    fun stopStream() {
        if (!connected) return
        val bt = bleConnection.getBleController() ?: return
        Log.d(TAG, "stopStream")
        S_StopStream().send(bt)
    }

    // ── Legacy stream methods kept for backward compat ────────────────────────

    suspend fun startLiveStream(): Map<String, Any> {
        if (!connected) throw IllegalStateException("Not connected to K900 device")
        if (pendingStreamDeferred != null) throw IllegalStateException("Stream already starting")

        val deferred = CompletableDeferred<Map<String, Any>>()
        pendingStreamDeferred = deferred

        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        localHotspotManager?.start(mainHandler, object : LocalHotspotManager.Callback {
            override fun onStarted(ssid: String, password: String) {
                Log.d(TAG, "Local hotspot ready: $ssid — sending to glass via BLE")
                CmdSendManager.getInstance().sendWifiSTA(ssid, password)
            }
            override fun onFailed(reason: String) {
                Log.w(TAG, "Local hotspot failed ($reason) — falling back to glass AP mode")
                fileSyncManager?.startStreamWifi()
            }
        })

        return withTimeout(30_000) { deferred.await() }
    }

    suspend fun startLiveStreamWithWifi(ssid: String, password: String): Map<String, Any> {
        if (!connected) throw IllegalStateException("Not connected to K900 device")
        if (pendingStreamDeferred != null) throw IllegalStateException("Stream already starting")

        val deferred = CompletableDeferred<Map<String, Any>>()
        pendingStreamDeferred = deferred

        Log.d(TAG, "Sending user WiFi credentials to glass: $ssid")
        CmdSendManager.getInstance().sendWifiSTA(ssid, password)

        return withTimeout(30_000) { deferred.await() }
    }

    fun stopLiveStream() {
        localHotspotManager?.stop()
        pendingStreamDeferred?.cancel()
        pendingStreamDeferred = null
        expo.modules.glasssdk.agent.AgentManager.getInstance(ctx).stopGlassVideoStream()
    }

    override fun destroy() {
        Log.d(TAG, "Destroying K900 adapter")

        // Stop A2DP service
        stopA2dpService()

        // Clean up file sync manager
        fileSyncManager?.cleanup()
        fileSyncManager = null

        bleConnection.destroy()
        CmdSendManager.getInstance().destroy()
        discoveredDevices.clear()
        pendingRequests.clear()

        super.destroy()
    }

    private fun handleHeartbeat(body: JSONObject) {
        battery = body.optInt("pt", 0)
        batteryVoltage = body.optInt("vt", 0).div(1000).toDouble()
        val ready = body.optInt("ready", 0)

        updateSystemInfo(
            batteryLevel = battery,
            batteryVoltage = batteryVoltage.toFloat()
        )

        EventBus.getDefault().post(
            GlassEventMsg(
                cmd = GlassEventConstants.MSG_GLASS_BATTERY,
                n1 = battery,
                n2 = 0,
                s1 = batteryVoltage.toString()
            )
        )

        if (ready == 1 && !deviceReady) {
            deviceReady = true
            updateSystemInfo(isReady = true)
            onReady()
            scope.launch {
                getSystemInfo()
                getStorageInfo()
            }
        }
    }

    /**
     * K900 SDK callback - receives command responses
     * This is where all K900 events come in
     */
    override fun onBleCmdRecv(cmd: Cmd, body: JSONObject?) {
        Log.d(TAG, "K900 response: ${cmd.cmd}")

        val safeBody = body ?: JSONObject()

        scope.launch {
            try {
                when (cmd.cmd) {
                    CmdList.SR_HEART, CmdList.SR_BATVOL -> {
                        handleHeartbeat(safeBody)
                    }

                    CmdList.SR_GET_STORAGEINFO -> {
                        // Storage info response
                        storage = GlassStorageInfo(
                            totalSize = safeBody.optLong("total_size", 0),
                            freeSize = safeBody.optLong("free_size", 0),
                            usedSize = safeBody.optLong("total_size", 0) - safeBody.optLong("free_size", 0),
                            photoCount = safeBody.optInt("phonum", 0),
                            videoCount = safeBody.optInt("vdonum", 0),
                            audioCount = safeBody.optInt("adonum", 0)
                        )
                        updateStorageInfo(storage)
                    }

                    CmdList.SR_SYSVERSION -> {
                        // System version response
                        firmware = safeBody.optString("sys", "")
                        app = safeBody.optString("app", "")

                        // Update firmware and app version
                        updateSystemInfo(
                            firmwareVersion = firmware.takeIf { it.isNotEmpty() },
                            appVersion = app.takeIf { it.isNotEmpty() }
                        )
                    }

                    CmdList.SR_PHOTO, CmdList.SR_CONTINUE_PHOTO -> {
                        // Photo capture response
                        val success = safeBody.optBoolean("succ", false)
                        val photoCount = safeBody.optInt("phonum", storage.photoCount)

                        if (success) {
                            Log.d(TAG, "Photo captured successfully")
                            storage = storage.copy(photoCount = photoCount)
                            updateStorageInfo(storage)
                        } else {
                            Log.e(TAG, "Photo capture failed")
                        }

                        // Complete pending photo request
                        val result = PhotoResult(
                            success = success,
                            errorCode = if (success) 0 else safeBody.optInt(
                                "error",
                                -3
                            ), // -3 = device error
                            photoCount = photoCount
                        )
                        completeCommand(CommandType.PHOTO, result)
                    }

                    CmdList.SR_VIDEO -> {
                        // Video response - update storage info but don't process commands yet
                        val success = safeBody.optBoolean("succ", false)
                        val videoCount = safeBody.optInt("vdonum", storage.videoCount)

                        if (success) {
                            Log.d(TAG, "Video command successful")
                            storage = storage.copy(videoCount = videoCount)
                            updateStorageInfo(storage)
                        }
                        // TODO: Complete pending video requests when video methods are implemented
                    }

                    CmdList.SR_AUDIO -> {
                        // Audio response - update storage info but don't process commands yet
                        val success = safeBody.optBoolean("succ", false)
                        val audioCount = safeBody.optInt("adonum", storage.audioCount)

                        if (success) {
                            Log.d(TAG, "Audio command successful")
                            storage = storage.copy(audioCount = audioCount)
                            updateStorageInfo(storage)
                        }
                        // TODO: Complete pending audio requests when audio methods are implemented
                    }

                    CmdList.SR_GET_MEDIASET -> {
                        // Parse media config response
                        val config = MediaConfigData(
                            photo = PhotoConfigData(
                                width = safeBody.optInt("pw", 1920),
                                height = safeBody.optInt("ph", 1080)
                            ),
                            video = VideoConfigData(
                                width = safeBody.optInt("vw", 1920),
                                height = safeBody.optInt("vh", 1080),
                                quality = safeBody.optInt("vq", 2),
                                duration = safeBody.optInt("vs", 30)
                            ),
                            audio = AudioConfigData(
                                duration = safeBody.optInt("as", 60)
                            )
                        )

                        Log.d(
                            TAG,
                            "Received media config: photo=${config.photo.width}x${config.photo.height}, video=${config.video.width}x${config.video.height}"
                        )

                        // Cache the config
                        mediaConfig = config

                        // Complete pending request
                        completeCommand(CommandType.GET_MEDIA_CONFIG, config)
                    }


                    CmdList.SR_APSERVER_RESULT -> {
                        val success = safeBody.optBoolean("succ")
                        val ssid = safeBody.optString("ssid")
                        val pwd = safeBody.optString("pwd")

                        if (success) {
                            Log.i(TAG, "ap server build success $ssid $pwd")
                            // Always route through fileSyncManager — streamMode flag decides behavior
                            fileSyncManager?.connectToGlassWifi(ssid, pwd)
                        }
                    }

                    CmdList.SR_WIFI_STA -> {
                        val ip = safeBody.optString("ip")
                        Log.i(TAG, "Glass WiFi STA connected, ip=$ip")
                        val d = pendingStreamDeferred
                        pendingStreamDeferred = null
                        if (ip.isNotEmpty()) {
                            d?.complete(mapOf("host" to ip, "port" to K900StreamManager.STREAM_PORT))
                            // Automatically start glass video track in the agent (if connected)
                            expo.modules.glasssdk.agent.AgentManager.getInstance(ctx)
                                .startGlassVideoStream(ip, K900StreamManager.STREAM_PORT)
                        } else {
                            localHotspotManager?.stop()
                            d?.completeExceptionally(Exception("Glass failed to connect to WiFi"))
                        }
                    }
                    // Note: Set config responses (SR_PHOTO_SET, SR_VIDEO_SET, SR_AUDIO_SET) are ignored
                    // as we don't wait for responses when setting config

                    CmdList.SR_ASK_FILE_FINISH -> {
                        // File sync completed - all files downloaded
                        Log.d(TAG, "File sync finished")
                        if (isSyncing) {
                            isSyncing = false
                            onSyncCompleted(
                                totalFiles = syncCompletedFiles,
                                totalBytes = syncTotalBytes,
                                success = true
                            )
                            // Reset counters
                            syncTotalFiles = 0
                            syncCompletedFiles = 0
                            syncTotalBytes = 0L
                            currentSyncFile = null
                        }
                    }

                    CmdList.SR_FILE_RECV -> {
                        // Individual file metadata received
                        val fileName = safeBody.optString("fname", "unknown")
                        val fileType = safeBody.optInt("ftype", 0)
                        Log.d(TAG, "File received: $fileName (type=$fileType)")
                        syncTotalFiles++
                    }

                    "sr_wrst" -> {
                        val worn = safeBody.optInt("on", 0) == 1
                        onWearStatus(worn)
                    }

                    "sr_ivww" -> {
                        Log.d(TAG, "Voice wakeup command received from glass")
                        onVoiceWakeup()
                    }

                    "sc_stream" -> {
                        val status = safeBody.optString("status", "")
                        val msg = safeBody.optString("msg", null)
                        Log.d(TAG, "sc_stream: status=$status msg=$msg")
                        EventBus.getDefault().post(
                            GlassEventMsg(
                                cmd = GlassEventConstants.MSG_GLASS_STREAM_STATUS,
                                s1 = status,
                                s2 = msg
                            )
                        )
                    }

                    else -> {
                        Log.d(TAG, "Unhandled K900 command: ${cmd.cmd}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling K900 response", e)
                onError("K900 response error: ${e.message}", e, isFatal = false)
            }
        }
    }
}
