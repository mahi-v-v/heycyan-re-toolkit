package expo.modules.glasssdk.glass

import android.content.Context

/**
 * Interface that all glass vendor adapters must implement
 * Provides vendor-agnostic API for glass device control
 */
interface GlassAdapter {
    /**
     * Device capabilities
     * Adapters must declare which features the vendor SDK supports
     */
    val capabilities: Map<String, Boolean>

    /**
     * Initialize the adapter with application context
     * @param context Application context
     */
    suspend fun init(context: Context)

    /**
     * Connect to a glass device
     * @param deviceId Unique identifier for the device
     * @throws Exception if connection fails
     */
    suspend fun connect(deviceId: String)

    /**
     * Disconnect from the current device
     */
    suspend fun disconnect()

    /**
     * Check if currently connected to a device
     * @return true if connected, false otherwise
     */
    fun isConnected(): Boolean

    /**
     * Send a generic command to the device
     * Adapters translate commands to vendor-specific API calls
     *
     * Standard commands:
     * - "vibrate" (params: duration: Long)
     * - "set_brightness" (params: level: Int)
     * - "take_photo" (params: none)
     * - "start_video" (params: duration: Int)
     * - "stop_video" (params: none)
     *
     * @param command Command name
     * @param params Command parameters
     * @throws Exception if command fails
     */
    suspend fun sendCommand(command: String, params: Map<String, Any> = emptyMap())

    /**
     * Get storage information
     * @return Storage info (returns default values if not connected)
     */
    suspend fun getStorageInfo(): GlassStorageInfo

    /**
     * Get system information including battery status
     * @return System info (returns default values if not connected)
     */
    suspend fun getSystemInfo(): GlassSystemInfo

    /**
     * Set the glasses' device name. The adapter sets whatever name(s) the vendor
     * supports (e.g. Bluetooth Classic name on K900; no-op where unsupported).
     * @param name Name to set on the device
     */
    suspend fun setDeviceName(name: String)

    /** Delete all media (photos/videos/audio) from the glasses' storage. */
    suspend fun clearMedia()

    /** Reboot/restart the device. The connection will drop as it restarts. */
    suspend fun reboot()

    /** Factory-reset the device. The connection will drop and it may unpair. */
    suspend fun factoryReset()

    /**
     * Set the on-device AI status indicator. Receives the generic vendor-agnostic status
     * ("speaking_start" | "speaking_stop" | "thinking_start" | "thinking_stop"); the adapter
     * maps it to the device's native AI status (no-op where unsupported).
     */
    suspend fun setAiStatus(status: String)

    /**
     * Start scanning for nearby glass devices
     * Discovered devices will be emitted via DEVICE_FOUND events
     */
    suspend fun startScan()

    /**
     * Stop scanning for nearby glass devices
     */
    suspend fun stopScan()

    /**
     * Take a photo with optional parameters
     * @param id Optional photo ID
     * @param filename Optional filename for the photo
     * @return PhotoResult with success status and error code
     * @throws TimeoutException if device doesn't respond within 30 seconds
     * @throws IllegalStateException if not connected
     */
    suspend fun takePhoto(id: Int? = null, filename: String? = null): PhotoResult

    /**
     * Take an AI-enhanced image
     * @return File path to the captured AI image
     * @throws TimeoutException if device doesn't respond within 30 seconds
     * @throws IllegalStateException if not connected or already in progress
     */
    suspend fun takeAiImage(): String

    /**
     * Start video recording
     * @param id Optional photo ID
     * @param filename Optional filename for the photo
     * @return VideoResult with success status and error code
     * @throws TimeoutException if device doesn't respond within 30 seconds
     * @throws IllegalStateException if not connected
     */
    suspend fun startVideo(id: Int? = null, filename: String? = null): VideoResult

    /**
     * Stop video recording
     */
    suspend fun stopVideo()

    /**
     * Start audio recording
     * @return AudioResult with success status
     */
    suspend fun startAudio(filename: String?): AudioResult

    /**
     * Stop audio recording
     */
    suspend fun stopAudio()

    /**
     * Get current media configuration from device
     * @return MediaConfigData with all settings
     * @throws TimeoutException if device doesn't respond within 10 seconds
     * @throws IllegalStateException if not connected
     */
    suspend fun getMediaConfig(): MediaConfigData

    /**
     * Set media configuration on device (fire-and-forget, no response wait)
     * @param config Map with optional "photo", "video", "audio" keys
     * @return Boolean indicating if commands were sent successfully
     * @throws IllegalStateException if not connected
     */
    suspend fun setMediaConfig(config: Map<String, Any>): Boolean

    /**
     * Sync (download) all media files from device to phone
     * Uses WiFi Hotspot for file transfer
     * Progress tracked via SYNC_STARTED, SYNC_PROGRESS, SYNC_COMPLETED events
     * @throws IllegalStateException if not connected
     */
    suspend fun syncFiles()

    /**
     * Cancel an in-progress media sync. Tears down WiFi/P2P, returns the device
     * to capture mode, and emits SYNC_COMPLETED with success=false, error="Cancelled".
     * No-op if no sync is in progress.
     */
    suspend fun cancelSync()

    /**
     * Start an OTA firmware update
     * @param filePath Path to the firmware file
     */
    suspend fun startOtaUpdate(filePath: String)

    /**
     * Send a WiFi OTA URL to the glasses (Linux OS update)
     * @param url The URL of the .swu file
     */
    suspend fun startWifiOtaUpdate(url: String)

    /**
     * Clean up resources and destroy the adapter
     * Called when switching adapters or shutting down
     */
    fun destroy()
}

/**
 * Result of a photo capture operation
 */
data class PhotoResult(
    val success: Boolean,
    val errorCode: Int = 0,
    val photoCount: Int = 0,
    val filename: String? = null
)

/**
 * Result of a video operation
 */
data class VideoResult(
    val success: Boolean,
    val errorCode: Int = 0,
    val videoCount: Int = 0
)

/**
 * Result of an audio operation
 */
data class AudioResult(
    val success: Boolean,
    val errorCode: Int = 0,
    val audioCount: Int = 0
)

/**
 * Media configuration data classes
 */
data class PhotoConfigData(
    val width: Int,
    val height: Int
)

data class VideoConfigData(
    val width: Int,
    val height: Int,
    val quality: Int,
    val duration: Int
)

data class AudioConfigData(
    val duration: Int
)

data class MediaConfigData(
    val photo: PhotoConfigData,
    val video: VideoConfigData,
    val audio: AudioConfigData
)
