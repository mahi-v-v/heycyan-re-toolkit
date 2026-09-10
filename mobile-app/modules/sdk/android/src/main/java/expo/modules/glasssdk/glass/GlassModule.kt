package expo.modules.glasssdk.glass

import android.os.Bundle
import android.util.Log
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import expo.modules.glasssdk.agent.AgentManager
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

/**
 * Expo module for glass device control
 * Thin layer that forwards calls to GlassManager and emits events to JS
 */
class GlassModule : Module() {

    companion object {
        private const val TAG = "[Glass:Module]"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var glassManager: GlassManager

    override fun definition() = ModuleDefinition {
        Name("GlassModule")

        Events(
            "GLASS_DEVICE_FOUND",
            "GLASS_STATE_CHANGED",
            "GLASS_CONNECTED",
            "GLASS_DISCONNECTED",
            "GLASS_BATTERY",
            "GLASS_READY",
            "GLASS_ERROR",
            "GLASS_SYNC_STARTED",
            "GLASS_SYNC_PROGRESS",
            "GLASS_SYNC_COMPLETED",
            "GLASS_SYNC_FILE",
            "GLASS_WEAR_STATUS",
            "GLASS_VOICE_WAKEUP",
            "GLASS_BT_STATUS",
            "GLASS_STORAGE_INFO",
            "GLASS_STREAM_READY",
            "GLASS_STREAM_STOPPED",
            "GLASS_STREAM_STATUS",
            "GLASS_OTA_PROGRESS",
            "GLASS_OTA_COMPLETED",
            "GLASS_OTA_ERROR"
        )

        OnCreate {
            // Initialize GlassManager
            scope.launch {
                glassManager =
                    GlassManager.getInstance(appContext.reactContext!!.applicationContext).init()
            }

            
            // Register for EventBus events
            EventBus.getDefault().register(this@GlassModule)
        }

        OnDestroy {
            // Unregister from EventBus
            EventBus.getDefault().unregister(this@GlassModule)
            glassManager.destroy()
        }

        /**
         * Connect to a glass device
         */
        AsyncFunction("connect") { deviceId: String, vendor: String, name: String?, promise: Promise ->
            scope.launch {
                try {
                    Log.d(TAG, "Connecting to device: $deviceId vendor: $vendor")
                    glassManager.connect(deviceId, vendor, name)
                    promise.resolve(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to connect to device", e)
                    promise.reject("CONNECT_FAILED", e.message, e)
                }
            }
        }

        /**
         * Disconnect from current device
         */
        AsyncFunction("disconnect") { promise: Promise ->
            scope.launch {
                try {
                    Log.d(TAG, "Disconnecting from device")
                    glassManager.disconnect()
                    promise.resolve(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to disconnect", e)
                    promise.reject("DISCONNECT_FAILED", e.message, e)
                }
            }
        }

        /**
         * Start OTA firmware update
         */
        AsyncFunction("startOtaUpdate") { filePath: String, promise: Promise ->
            scope.launch {
                try {
                    Log.d(TAG, "Starting OTA update with file: $filePath")
                    glassManager.startOtaUpdate(filePath)
                    promise.resolve(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start OTA update", e)
                    promise.reject("OTA_FAILED", e.message, e)
                }
            }
        }

        /**
         * Start WiFi OTA firmware update (Linux OS)
         */
        AsyncFunction("startWifiOtaUpdate") { url: String, promise: Promise ->
            scope.launch {
                try {
                    Log.d(TAG, "Starting WiFi OTA update with URL: $url")
                    glassManager.startWifiOtaUpdate(url)
                    promise.resolve(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start WiFi OTA update", e)
                    promise.reject("WIFI_OTA_FAILED", e.message, e)
                }
            }
        }

        /**
         * Send command to device
         */
        AsyncFunction("sendCommand") { command: String, params: Map<String, Any>, promise: Promise ->
            scope.launch {
                try {
                    Log.d(TAG, "Sending command: $command")
                    glassManager.sendCommand(command, params)
                    promise.resolve(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send command: $command", e)
                    promise.reject("COMMAND_FAILED", e.message, e)
                }
            }
        }

        /**
         * Get device capabilities
         */
        AsyncFunction("getCapabilities") { ->
            try {
                val capabilities = glassManager.getCapabilities()
                return@AsyncFunction capabilities
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get capabilities", e)
                throw e
            }
        }

        /**
         * Get current connection state
         */
        AsyncFunction("getCurrentState") { ->
            try {
                val state = glassManager.getCurrentState()
                return@AsyncFunction state.name
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get current state", e)
                throw e
            }
        }

        /**
         * Check if connected to a device
         */
        AsyncFunction("isConnected") { ->
            try {
                val connected = glassManager.isConnected()
                return@AsyncFunction connected
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check connection status", e)
                throw e
            }
        }

        /**
         * Get storage information
         */
        AsyncFunction("getStorageInfo") { promise: Promise ->
            scope.launch {
                try {
                    val storageInfo = glassManager.getStorageInfo()
                    val storageMap = Bundle().apply {
                        putLong("totalSize", storageInfo.totalSize)
                        putLong("freeSize", storageInfo.freeSize)
                        putLong("usedSize", storageInfo.usedSize)
                        putInt("photoCount", storageInfo.photoCount)
                        putInt("videoCount", storageInfo.videoCount)
                        putInt("audioCount", storageInfo.audioCount)
                    }
                    promise.resolve(storageMap)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get storage info", e)
                    promise.reject("GET_STORAGE_INFO_FAILED", e.message, e)
                }
            }
        }

        /**
         * Get system information
         */
        AsyncFunction("getSystemInfo") { promise: Promise ->
            scope.launch {
                try {
                    val systemInfo = glassManager.getSystemInfo()
                    val systemMap = Bundle().apply {
                        putString("firmwareVersion", systemInfo.firmwareVersion)
                        putString("appVersion", systemInfo.appVersion)
                        putString("deviceModel", systemInfo.deviceModel)
                        putInt("batteryLevel", systemInfo.batteryLevel)
                        putBoolean("isCharging", systemInfo.isCharging)
                        putDouble("batteryVoltage", systemInfo.batteryVoltage.toDouble())
                        putBoolean("isReady", systemInfo.isReady)
                    }
                    promise.resolve(systemMap)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get system info", e)
                    promise.reject("GET_SYSTEM_INFO_FAILED", e.message, e)
                }
            }
        }

        AsyncFunction("setDeviceName") { name: String, promise: Promise ->
            scope.launch {
                try {
                    glassManager.setDeviceName(name)
                    promise.resolve(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set device name", e)
                    promise.reject("SET_DEVICE_NAME_FAILED", e.message, e)
                }
            }
        }

        AsyncFunction("clearMedia") { promise: Promise ->
            scope.launch {
                try {
                    glassManager.clearMedia()
                    promise.resolve(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to clear media", e)
                    promise.reject("CLEAR_MEDIA_FAILED", e.message, e)
                }
            }
        }

        AsyncFunction("reboot") { promise: Promise ->
            scope.launch {
                try {
                    glassManager.reboot()
                    promise.resolve(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to reboot", e)
                    promise.reject("REBOOT_FAILED", e.message, e)
                }
            }
        }

        AsyncFunction("factoryReset") { promise: Promise ->
            scope.launch {
                try {
                    glassManager.factoryReset()
                    promise.resolve(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to factory reset", e)
                    promise.reject("FACTORY_RESET_FAILED", e.message, e)
                }
            }
        }

        AsyncFunction("setAiStatus") { status: String, promise: Promise ->
            scope.launch {
                try {
                    glassManager.setAiStatus(status)
                    promise.resolve(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set AI status", e)
                    promise.reject("SET_AI_STATUS_FAILED", e.message, e)
                }
            }
        }

        /**
         * Start scanning for nearby glass devices
         */
        AsyncFunction("startScan") { promise: Promise ->
            scope.launch {
                try {
                    Log.d(TAG, "Starting device scan")
                    glassManager.startScan()
                    promise.resolve(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start scan", e)
                    promise.reject("START_SCAN_FAILED", e.message, e)
                }
            }
        }

        /**
         * Stop scanning for nearby glass devices
         */
        AsyncFunction("stopScan") { promise: Promise ->
            scope.launch {
                try {
                    Log.d(TAG, "Stopping device scan")
                    glassManager.stopScan()
                    promise.resolve(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop scan", e)
                    promise.reject("STOP_SCAN_FAILED", e.message, e)
                }
            }
        }

        AsyncFunction("isBluetoothEnabled") {
            val btManager = appContext.reactContext!!.applicationContext
                .getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            return@AsyncFunction btManager?.adapter?.isEnabled ?: false
        }

        /**
         * Take a photo
         */
        AsyncFunction("takePhoto") { id: Int?, filename: String?, promise: Promise ->
            scope.launch {
                try {
                    Log.d(TAG, "Taking photo (id=$id, filename=$filename)")
                    val result = glassManager.takePhoto(id, filename)
                    val resultMap = Bundle().apply {
                        putBoolean("success", result.success)
                        putInt("errorCode", result.errorCode)
                        putInt("photoCount", result.photoCount)
                        result.filename?.let { putString("filename", it) }
                    }
                    promise.resolve(resultMap)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to take photo", e)
                    promise.reject("TAKE_PHOTO_FAILED", e.message, e)
                }
            }
        }

        /**
         * Take an AI-enhanced image
         */
        AsyncFunction("takeAiImage") { promise: Promise ->
            scope.launch {
                try {
                    Log.d(TAG, "Taking AI image")
                    val filePath = glassManager.takeAiImage()
                    promise.resolve(filePath)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to take AI image", e)
                    promise.reject("TAKE_AI_IMAGE_FAILED", e.message, e)
                }
            }
        }

        /**
         * Start video recording
         */
        AsyncFunction("startVideo") { id: Int?, filename: String?, promise: Promise ->
            scope.launch {
                try {
                    Log.d(TAG, "Starting video recording")
                    val result = glassManager.startVideo(id, filename)
                    val resultMap = Bundle().apply {
                        putBoolean("success", result.success)
                        putInt("errorCode", result.errorCode)
                        putInt("videoCount", result.videoCount)
                    }
                    promise.resolve(resultMap)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start video", e)
                    promise.reject("START_VIDEO_FAILED", e.message, e)
                }
            }
        }

        /**
         * Stop video recording
         */
        AsyncFunction("stopVideo") { promise: Promise ->
            scope.launch {
                try {
                    Log.d(TAG, "Stopping video recording")
                    glassManager.stopVideo()
                    promise.resolve(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop video", e)
                    promise.reject("STOP_VIDEO_FAILED", e.message, e)
                }
            }
        }

        /**
         * Start audio recording
         */
        AsyncFunction("startAudio") { filename: String?, promise: Promise ->
            scope.launch {
                try {
                    Log.d(TAG, "Starting audio recording")
                    val result = glassManager.startAudio(filename)
                    val resultMap = Bundle().apply {
                        putBoolean("success", result.success)
                        putInt("errorCode", result.errorCode)
                        putInt("audioCount", result.audioCount)
                    }
                    promise.resolve(resultMap)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start audio", e)
                    promise.reject("START_AUDIO_FAILED", e.message, e)
                }
            }
        }

        /**
         * Stop audio recording
         */
        AsyncFunction("stopAudio") { promise: Promise ->
            scope.launch {
                try {
                    Log.d(TAG, "Stopping audio recording")
                    glassManager.stopAudio()
                    promise.resolve(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop audio", e)
                    promise.reject("STOP_AUDIO_FAILED", e.message, e)
                }
            }
        }

        AsyncFunction("getMediaDirectory") { promise: Promise ->
            scope.launch {
                try {
                    val mediaDirectory = glassManager.getMediaDirectory()
                    promise.resolve(mediaDirectory)
                } catch (e: Exception) {
                    promise.reject("FAILED", e.message, e)
                }
            }
        }
        /**
         * Get media configuration from device
         */
        AsyncFunction("getMediaConfig") { promise: Promise ->
            scope.launch {
                try {
                    Log.d(TAG, "Getting media configuration")
                    val config = glassManager.getMediaConfig()
                    val configMap = Bundle().apply {
                        // Photo config
                        val photoBundle = Bundle().apply {
                            putInt("width", config.photo.width)
                            putInt("height", config.photo.height)
                        }
                        putBundle("photo", photoBundle)

                        // Video config
                        val videoBundle = Bundle().apply {
                            putInt("width", config.video.width)
                            putInt("height", config.video.height)
                            putInt("quality", config.video.quality)
                            putInt("duration", config.video.duration)
                        }
                        putBundle("video", videoBundle)

                        // Audio config
                        val audioBundle = Bundle().apply {
                            putInt("duration", config.audio.duration)
                        }
                        putBundle("audio", audioBundle)
                    }
                    promise.resolve(configMap)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get media config", e)
                    promise.reject("GET_MEDIA_CONFIG_FAILED", e.message, e)
                }
            }
        }

        /**
         * Set media configuration on device
         */
        AsyncFunction("setMediaConfig") { config: Map<String, Any>, promise: Promise ->
            scope.launch {
                try {
                    Log.d(TAG, "Setting media configuration")
                    val success = glassManager.setMediaConfig(config)
                    promise.resolve(success)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set media config", e)
                    promise.reject("SET_MEDIA_CONFIG_FAILED", e.message, e)
                }
            }
        }

        /**
         * Start WHIP stream: glass connects to WiFi and streams camera directly to the WHIP URL.
         * Status is reported asynchronously via GLASS_STREAM_STATUS events.
         */
        AsyncFunction("startStream") { ssid: String, pwd: String, url: String,
                                       streamKey: String?, fps: Int, bitrate: Int,
                                       promise: Promise ->
            scope.launch {
                try {
                    glassManager.startStream(ssid, pwd, url, streamKey, fps, bitrate)
                    promise.resolve(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start stream", e)
                    promise.reject("START_STREAM_FAILED", e.message, e)
                }
            }
        }

        AsyncFunction("stopStream") { promise: Promise ->
            try {
                glassManager.stopStream()
                promise.resolve(null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop stream", e)
                promise.reject("STOP_STREAM_FAILED", e.message, e)
            }
        }

        /**
         * Start live stream from glass camera.
         * Connects phone to glass WiFi hotspot and returns { host, port } for GlassStreamView.
         */
        AsyncFunction("startLiveStream") { promise: Promise ->
            scope.launch {
                try {
                    val result = glassManager.startLiveStream()
                    val bundle = Bundle().apply {
                        putString("host", result["host"] as? String ?: "")
                        putInt("port", (result["port"] as? Int) ?: 8554)
                    }
                    sendEvent("GLASS_STREAM_READY", bundle)
                    promise.resolve(bundle)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start live stream", e)
                    promise.reject("START_STREAM_FAILED", e.message, e)
                }
            }
        }

        /**
         * Mode B: send user's WiFi credentials to glass, glass joins that network.
         */
        AsyncFunction("startLiveStreamWithWifi") { ssid: String, password: String, promise: Promise ->
            scope.launch {
                try {
                    val result = glassManager.startLiveStreamWithWifi(ssid, password)
                    val bundle = Bundle().apply {
                        putString("host", result["host"] as? String ?: "")
                        putInt("port", (result["port"] as? Int) ?: 8554)
                    }
                    sendEvent("GLASS_STREAM_READY", bundle)
                    promise.resolve(bundle)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start live stream with WiFi", e)
                    promise.reject("START_STREAM_FAILED", e.message, e)
                }
            }
        }

        /**
         * Returns true if a glass video track is currently active or pending.
         * Used by the stream screen to restore UI state on re-navigation.
         */
        AsyncFunction("isStreamingActive") { ->
            AgentManager.getInstance(appContext.reactContext!!.applicationContext)
                .isGlassVideoStreamingActive()
        }

        /**
         * Stop live stream and release glass WiFi connection.
         */
        AsyncFunction("stopLiveStream") { promise: Promise ->
            try {
                glassManager.stopLiveStream()
                sendEvent("GLASS_STREAM_STOPPED", Bundle())
                promise.resolve(null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop live stream", e)
                promise.reject("STOP_STREAM_FAILED", e.message, e)
            }
        }

        /**
         * Sync (download) all media files from device to phone
         * Progress tracked via events: SYNC_STARTED, SYNC_PROGRESS, SYNC_COMPLETED
         */
        AsyncFunction("syncFiles") { promise: Promise ->
            scope.launch {
                try {
                    Log.d(TAG, "Starting file sync")
                    glassManager.syncFiles()
                    // Note: No immediate result - use events for progress/completion
                    promise.resolve(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start file sync", e)
                    promise.reject("SYNC_FILES_FAILED", e.message, e)
                }
            }
        }

        AsyncFunction("cancelSync") { promise: Promise ->
            scope.launch {
                try {
                    Log.d(TAG, "Cancelling file sync")
                    glassManager.cancelSync()
                    promise.resolve(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to cancel file sync", e)
                    promise.reject("CANCEL_SYNC_FAILED", e.message, e)
                }
            }
        }
    }

    /**
     * Subscribe to Glass events from EventBus
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onGlassEvent(event: GlassEventMsg) {
        emitEvent(event)
    }

    /**
     * Emit event to JavaScript
     */
    private fun emitEvent(event: GlassEventMsg) {
        try {
            val eventData = Bundle()

            // Map based on event type
            when (event.cmd) {
                GlassEventConstants.MSG_GLASS_DEVICE_FOUND -> {
                    // s2 is the advertisement JSON (incl. name); extract name + forward
                    // the JSON for JS-side vendor classification.
                    val advJson = event.s2 ?: "{}"
                    val name = try {
                        org.json.JSONObject(advJson).optString("name", "")
                    } catch (_: Exception) { "" }
                    eventData.putString("deviceId", event.s1)
                    eventData.putString("deviceName", name)
                    eventData.putString("advertisement", advJson)
                    if (event.n1 != 0) eventData.putInt("rssi", event.n1)
                    sendEvent("GLASS_DEVICE_FOUND", eventData)
                }

                GlassEventConstants.MSG_GLASS_STATE_CHANGED -> {
                    eventData.putString("oldState", GlassConnectionState.values()[event.n1].name)
                    eventData.putString("newState", GlassConnectionState.values()[event.n2].name)
                    event.s1?.let { eventData.putString("reason", it) }
                    sendEvent("GLASS_STATE_CHANGED", eventData)
                }

                GlassEventConstants.MSG_GLASS_CONNECTED -> {
                    eventData.putString("deviceId", event.s1)
                    eventData.putString("deviceName", event.s2)
                    glassManager.getCurrentVendorString()?.let { eventData.putString("vendor", it) }
                    sendEvent("GLASS_CONNECTED", eventData)
                }

                GlassEventConstants.MSG_GLASS_DISCONNECTED -> {
                    eventData.putString("deviceId", event.s1)
                    eventData.putString("reason", event.s2)
                    eventData.putBoolean("wasExpected", event.n1 == 1)
                    sendEvent("GLASS_DISCONNECTED", eventData)
                    // Update the visible notification immediately on any disconnect
                    appContext.reactContext?.let { ctx ->
                        val notification = expo.modules.glasssdk.ForegroundNotificationManager.updateGlassState(ctx, false, null)
                        val nm = ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                        nm.notify(expo.modules.glasssdk.ForegroundNotificationManager.getNotificationId(), notification)
                    }
                }

                GlassEventConstants.MSG_GLASS_BATTERY -> {
                    eventData.putInt("level", event.n1)
                    eventData.putBoolean("isCharging", event.n2 == 1)
                    event.s1?.let { eventData.putString("voltage", it) }
                    sendEvent("GLASS_BATTERY", eventData)
                }

                GlassEventConstants.MSG_GLASS_STORAGE_INFO -> {
                    eventData.putInt("photoCount", event.n1)
                    eventData.putInt("videoCount", event.n2)
                    eventData.putInt("audioCount", event.s1?.toIntOrNull() ?: 0)
                    sendEvent("GLASS_STORAGE_INFO", eventData)
                }

                GlassEventConstants.MSG_GLASS_READY -> {
                    sendEvent("GLASS_READY", Bundle())
                }

                GlassEventConstants.MSG_GLASS_ERROR -> {
                    eventData.putString("error", event.s1)
                    event.s2?.let { eventData.putString("code", it) }
                    eventData.putBoolean("isFatal", event.n1 == 1)
                    sendEvent("GLASS_ERROR", eventData)
                }

                GlassEventConstants.MSG_GLASS_SYNC_STARTED -> {
                    // No additional data needed
                    sendEvent("GLASS_SYNC_STARTED", Bundle())
                }

                GlassEventConstants.MSG_GLASS_SYNC_PROGRESS -> {
                    eventData.putInt("current", event.n1)  // Files completed so far
                    eventData.putInt("total", event.n2)    // Total files to sync
                    eventData.putInt(
                        "percent",
                        if (event.n2 > 0) (event.n1 * 100 / event.n2) else 0
                    )
                    sendEvent("GLASS_SYNC_PROGRESS", eventData)
                }

                GlassEventConstants.MSG_GLASS_SYNC_COMPLETED -> {
                    eventData.putInt("totalFiles", event.n1)
                    eventData.putLong("totalBytes", event.s1?.toLongOrNull() ?: 0L)
                    eventData.putBoolean("success", event.n2 == 1)
                    event.s2?.let { eventData.putString("error", it) }
                    sendEvent("GLASS_SYNC_COMPLETED", eventData)
                }

                GlassEventConstants.MSG_GLASS_SYNC_FILE -> {
                    event.s1?.let { eventData.putString("path", it) }
                    event.s2?.let { eventData.putString("type", it) }
                    sendEvent("GLASS_SYNC_FILE", eventData)
                }

                GlassEventConstants.MSG_GLASS_WEAR_STATUS -> {
                    eventData.putBoolean("worn", event.n1 == 1)
                    sendEvent("GLASS_WEAR_STATUS", eventData)
                }

                GlassEventConstants.MSG_GLASS_BT_STATUS -> {
                    eventData.putBoolean("connected", event.n1 == 1)
                    sendEvent("GLASS_BT_STATUS", eventData)
                }

                GlassEventConstants.MSG_GLASS_VOICE_WAKEUP -> {
                    sendEvent("GLASS_VOICE_WAKEUP", Bundle())
                }

                GlassEventConstants.MSG_GLASS_STREAM_STATUS -> {
                    eventData.putString("status", event.s1)
                    event.s2?.let { eventData.putString("msg", it) }
                    sendEvent("GLASS_STREAM_STATUS", eventData)
                    if (event.s1 == "stopped" || event.s1 == "error") {
                        AgentManager.getInstance(appContext.reactContext!!.applicationContext)
                            .stopGlassStream()
                    }
                }

                GlassEventConstants.MSG_GLASS_OTA_PROGRESS -> {
                    eventData.putInt("percent", event.n1)
                    sendEvent("GLASS_OTA_PROGRESS", eventData)
                }

                GlassEventConstants.MSG_GLASS_OTA_COMPLETED -> {
                    sendEvent("GLASS_OTA_COMPLETED", Bundle())
                }

                GlassEventConstants.MSG_GLASS_OTA_LINUX_COMPLETED -> {
                    sendEvent("GLASS_OTA_LINUX_COMPLETED", Bundle())
                }

                GlassEventConstants.MSG_GLASS_OTA_ERROR -> {
                    eventData.putString("error", event.s1 ?: "Unknown OTA Error")
                    sendEvent("GLASS_OTA_ERROR", eventData)
                }

                else -> {
                    Log.w(TAG, "Unhandled event type: ${event.cmd}")
                }
            }

            Log.d(TAG, "Emitted Glass event: ${event.cmd}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to emit Glass event: ${event.cmd}", e)
        }
    }
}
