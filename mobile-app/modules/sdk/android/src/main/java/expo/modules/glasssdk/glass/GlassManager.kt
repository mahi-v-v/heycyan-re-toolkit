package expo.modules.glasssdk.glass

import android.content.Context
import android.os.Environment
import android.util.Log
import expo.modules.glasssdk.glass.vendors.cyan.CyanAdapter
import expo.modules.glasssdk.glass.vendors.cyan_sports.CyanSportsAdapter
import expo.modules.glasssdk.glass.vendors.k900.K900Adapter
import expo.modules.glasssdk.glass.vendors.mock.MockAdapter
import org.greenrobot.eventbus.EventBus

/**
 * Central manager for glass devices.
 * Handles adapter lifecycle and API routing. Vendor detection lives in JS; native is a
 * pure router keyed by the vendor string ("k900"/"cyan"/"mock") passed into connect().
 *
 * Adapters are initialized ONCE at startup (in init()) and persist for the app lifetime.
 * They are never destroyed during normal runtime — this prevents stale-state bugs from
 * adapter teardown/recreation races (e.g. peripheralMap cleared after auto-connect timeout).
 */
class GlassManager private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "[Glass:Manager]"

        @Volatile
        private var instance: GlassManager? = null

        fun getInstance(context: Context): GlassManager {
            return instance ?: synchronized(this) {
                instance ?: GlassManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /// Persistent adapter pool — keyed by the JS vendor string, initialized once, never destroyed at runtime.
    private val adapters = mutableMapOf<String, BaseGlassAdapter>()

    /// Which vendor is currently connected (null when disconnected).
    private var activeVendor: String? = null

    /// One generic BLE scanner — surfaces ALL devices to JS, which classifies the vendor.
    private val scanner by lazy {
        GlassScanner(context).apply {
            onDeviceFound = { deviceId, _, rssi, advJson ->
                EventBus.getDefault().post(
                    GlassEventMsg(
                        GlassEventConstants.MSG_GLASS_DEVICE_FOUND,
                        n1 = rssi,
                        s1 = deviceId,
                        s2 = advJson,
                    ),
                )
            }
        }
    }

    private var isForegroundServiceRunning = false

    /**
     * Initialize all vendor adapters once. Called when the Expo module is created.
     */
    suspend fun init(): GlassManager {
        for (vendor in listOf("k900", "cyan", "cyan_sports")) {
            if (adapters.containsKey(vendor)) continue
            val adapter = createAdapter(vendor)
            try {
                adapter.init(context)
                adapters[vendor] = adapter
                Log.d(TAG, "init — adapter ready: $vendor")
            } catch (e: Exception) {
                Log.e(TAG, "init — adapter failed: $vendor", e)
                adapter.destroy()
            }
        }
        return this
    }

    /**
     * Create adapter instance for vendor (used during init() only)
     */
    private fun createAdapter(vendor: String): BaseGlassAdapter {
        Log.d(TAG, "Creating adapter for vendor: $vendor")
        return when (vendor) {
            "k900" -> K900Adapter(context)
            "cyan" -> CyanAdapter(context)
            "cyan_sports" -> CyanSportsAdapter(context)
            else -> MockAdapter() // "mock" / unknown
        }
    }

    /**
     * Connect to a glass device. Vendor is decided in JS and passed in — native routes
     * straight to the matching adapter (no native detection). On Android the device
     * connects directly by MAC, so this works for both fresh-scan connects and reconnects.
     * @param name Optional display name from JS, used for the foreground-service notification.
     */
    suspend fun connect(deviceId: String, vendor: String, name: String? = null) {
        Log.i(TAG, "Connecting to device: $deviceId vendor: $vendor")
        val adapter = adapters[vendor]
            ?: throw IllegalStateException("No adapter for vendor $vendor — not initialized")

        // Stop the generic scan before connecting.
        scanner.stop()

        // Set activeVendor BEFORE connect so getCurrentVendorString() is correct when
        // the GLASS_CONNECTED event fires inside onConnected() during the connect call.
        activeVendor = vendor
        try {
            adapter.connect(deviceId)
        } catch (e: Exception) {
            activeVendor = null
            throw e
        }

        if (!isForegroundServiceRunning) {
            val displayName = name ?: adapter.currentDeviceName ?: generateDeviceName(deviceId, vendor)
            Log.d(TAG, "Starting foreground service for device: $displayName")
            GlassService.startForegroundService(context, displayName)
            isForegroundServiceRunning = true
        }
    }

    /**
     * Generate a human-readable device name (notification fallback when JS sends none).
     */
    private fun generateDeviceName(deviceId: String, vendor: String): String {
        return when (vendor) {
            "k900" -> "K900 Glass (${deviceId.takeLast(8)})"
            "cyan" -> "Cyan Glass (${deviceId.removePrefix("cyan:").takeLast(8)})"
            "cyan_sports" -> "Cyan Sports Glass (${deviceId.takeLast(8)})"
            "mock" -> "Mock Device"
            else -> "Glass Device"
        }
    }

    /**
     * Disconnect from current device. Does NOT destroy the adapter.
     */
    suspend fun disconnect() {
        Log.i(TAG, "Disconnecting from device")
        adapters[activeVendor]?.disconnect()

        activeVendor = null

        if (isForegroundServiceRunning) {
            Log.d(TAG, "Stopping foreground service")
            GlassService.stopForegroundService(context)
            isForegroundServiceRunning = false
        }
    }

    fun isConnected(): Boolean {
        return activeVendor?.let { adapters[it] }?.isConnected() ?: false
    }

    fun getCurrentState(): GlassConnectionState {
        return activeVendor?.let { adapters[it] }?.let {
            if (it.isConnected()) GlassConnectionState.CONNECTED else GlassConnectionState.IDLE
        } ?: GlassConnectionState.IDLE
    }

    suspend fun sendCommand(command: String, params: Map<String, Any> = emptyMap()) {
        val adapter = activeVendor?.let { adapters[it] }
            ?: throw IllegalStateException("No adapter connected")
        adapter.sendCommand(command, params)
    }

    fun getCapabilities(): Map<String, Boolean> {
        return activeVendor?.let { adapters[it] }?.capabilities ?: emptyMap()
    }

    suspend fun getStorageInfo(): GlassStorageInfo {
        return activeVendor?.let { adapters[it] }?.getStorageInfo() ?: GlassStorageInfo(
            totalSize = 0L, freeSize = 0L, usedSize = 0L,
            photoCount = 0, videoCount = 0, audioCount = 0
        )
    }

    suspend fun getSystemInfo(): GlassSystemInfo {
        return activeVendor?.let { adapters[it] }?.getSystemInfo() ?: GlassSystemInfo(
            firmwareVersion = "", appVersion = "", deviceModel = "",
            batteryLevel = 0, isCharging = false, batteryVoltage = 0F, isReady = false
        )
    }

    suspend fun setDeviceName(name: String) {
        activeVendor?.let { adapters[it] }?.setDeviceName(name)
    }

    suspend fun clearMedia() {
        activeVendor?.let { adapters[it] }?.clearMedia()
    }

    suspend fun reboot() {
        activeVendor?.let { adapters[it] }?.reboot()
    }

    suspend fun factoryReset() {
        activeVendor?.let { adapters[it] }?.factoryReset()
    }

    suspend fun setAiStatus(status: String) {
        activeVendor?.let { adapters[it] }?.setAiStatus(status)
    }

    /**
     * Start scanning for nearby BLE devices via the single generic scanner.
     * JS classifies the vendor from the advertisement (OTA-updatable).
     */
    suspend fun startScan() {
        Log.i(TAG, "Starting generic BLE scan")
        scanner.start()
    }

    /**
     * Stop scanning. Adapters are kept alive (persistent lifecycle).
     */
    suspend fun stopScan() {
        Log.i(TAG, "Stopping generic BLE scan")
        scanner.stop()
    }

    suspend fun takePhoto(id: Int? = null, filename: String? = null): PhotoResult {
        val adapter = activeVendor?.let { adapters[it] }
            ?: throw IllegalStateException("No adapter connected")
        return adapter.takePhoto(id, filename)
    }

    suspend fun takeAiImage(): String {
        val adapter = activeVendor?.let { adapters[it] }
            ?: throw IllegalStateException("No adapter connected")
        return adapter.takeAiImage()
    }

    suspend fun startVideo(id: Int? = null, filename: String? = null): VideoResult {
        val adapter = activeVendor?.let { adapters[it] }
            ?: throw IllegalStateException("No adapter connected")
        return adapter.startVideo(id, filename)
    }

    suspend fun stopVideo() {
        val adapter = activeVendor?.let { adapters[it] }
            ?: throw IllegalStateException("No adapter connected")
        adapter.stopVideo()
    }

    suspend fun startAudio(filename: String?): AudioResult {
        val adapter = activeVendor?.let { adapters[it] }
            ?: throw IllegalStateException("No adapter connected")
        return adapter.startAudio(filename)
    }

    suspend fun stopAudio() {
        val adapter = activeVendor?.let { adapters[it] }
            ?: throw IllegalStateException("No adapter connected")
        adapter.stopAudio()
    }

    fun getMediaDirectory(): String {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        return base!!.absolutePath
    }

    suspend fun getMediaConfig(): MediaConfigData {
        val adapter = activeVendor?.let { adapters[it] }
            ?: throw IllegalStateException("No adapter connected")
        return adapter.getMediaConfig()
    }

    suspend fun setMediaConfig(config: Map<String, Any>): Boolean {
        val adapter = activeVendor?.let { adapters[it] }
            ?: throw IllegalStateException("No adapter connected")
        return adapter.setMediaConfig(config)
    }

    suspend fun syncFiles() {
        val adapter = activeVendor?.let { adapters[it] }
            ?: throw IllegalStateException("No adapter connected")
        adapter.syncFiles()
    }

    suspend fun cancelSync() {
        val adapter = activeVendor?.let { adapters[it] } ?: return
        adapter.cancelSync()
    }

    suspend fun startOtaUpdate(filePath: String) {
        val adapter = activeVendor?.let { adapters[it] }
            ?: throw IllegalStateException("No adapter connected")
        adapter.startOtaUpdate(filePath)
    }

    suspend fun startWifiOtaUpdate(url: String) {
        val adapter = activeVendor?.let { adapters[it] }
            ?: throw IllegalStateException("No adapter connected")
        adapter.startWifiOtaUpdate(url)
    }

    /**
     * Start WHIP stream — glass connects to WiFi and streams directly to the WHIP server.
     * Status is reported via GLASS_STREAM_STATUS events.
     */
    fun startStream(
        ssid: String,
        pwd: String,
        url: String,
        streamKey: String?,
        fps: Int,
        bitrate: Int
    ) {
        val adapter = activeVendor?.let { adapters[it] }
            ?: throw IllegalStateException("No adapter connected")
        if (adapter is K900Adapter) {
            adapter.startStream(ssid, pwd, url, streamKey, fps, bitrate)
        } else {
            throw UnsupportedOperationException("WHIP stream not supported for this device")
        }
    }

    fun stopStream() {
        val adapter = activeVendor?.let { adapters[it] }
        if (adapter is K900Adapter) adapter.stopStream()
    }

    // ── Legacy stream methods ─────────────────────────────────────────────────

    suspend fun startLiveStream(): Map<String, Any> {
        val adapter = activeVendor?.let { adapters[it] }
            ?: throw IllegalStateException("No adapter connected")
        if (adapter is K900Adapter) {
            return adapter.startLiveStream()
        }
        throw UnsupportedOperationException("Live stream not supported for this device")
    }

    suspend fun startLiveStreamWithWifi(ssid: String, password: String): Map<String, Any> {
        val adapter = activeVendor?.let { adapters[it] }
            ?: throw IllegalStateException("No adapter connected")
        if (adapter is K900Adapter) {
            return adapter.startLiveStreamWithWifi(ssid, password)
        }
        throw UnsupportedOperationException("Live stream not supported for this device")
    }

    fun stopLiveStream() {
        val adapter = activeVendor?.let { adapters[it] }
        if (adapter is K900Adapter) {
            adapter.stopLiveStream()
        }
    }

    /** The currently-connected vendor string (null when disconnected). */
    fun getCurrentVendorString(): String? = activeVendor

    /**
     * Clean up all resources on module destroy.
     */
    fun destroy() {
        Log.d(TAG, "Destroying manager")

        if (isForegroundServiceRunning) {
            Log.d(TAG, "Stopping foreground service on destroy")
            GlassService.stopForegroundService(context)
            isForegroundServiceRunning = false
        }

        adapters.values.forEach { it.destroy() }
        adapters.clear()
        activeVendor = null
    }
}
