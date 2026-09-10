package expo.modules.glasssdk.glass

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus

/**
 * Base implementation for all glass adapters
 * Provides shared functionality to eliminate code duplication
 */
abstract class BaseGlassAdapter : GlassAdapter {
    protected val TAG: String = "[Glass:${this::class.simpleName}]"

    // Coroutine scope for adapter operations
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Current connection state
    protected var connectionState: GlassConnectionState = GlassConnectionState.IDLE
        private set

    // Reconnection settings
    protected var reconnectAttempts = 0
    protected val maxReconnectAttempts = 5
    protected val reconnectDelays = listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L)
    protected var reconnectJob: Job? = null

    // Current device info
    protected var currentDeviceId: String? = null
    internal var currentDeviceName: String? = null

    // Storage and system info state - common to all adapters
    protected var storageInfo: GlassStorageInfo = GlassStorageInfo(
        totalSize = 0L,
        freeSize = 0L,
        usedSize = 0L,
        photoCount = 0,
        videoCount = 0,
        audioCount = 0
    )
        private set
    protected var systemInfo: GlassSystemInfo = GlassSystemInfo(
        firmwareVersion = "",
        appVersion = "",
        deviceModel = "",
        batteryLevel = 0,
        isCharging = false,
        batteryVoltage = 0F,
        isReady = false
    )
        private set

    /**
     * Update connection state and emit state change event
     */
    protected suspend fun updateState(newState: GlassConnectionState, reason: String? = null) {
        val oldState = connectionState
        if (oldState != newState) {
            connectionState = newState
            Log.d(TAG, "State changed: $oldState -> $newState${reason?.let { " ($it)" } ?: ""}")

            // Post to EventBus
            EventBus.getDefault().post(
                GlassEventMsg(
                    GlassEventConstants.MSG_GLASS_STATE_CHANGED,
                    n1 = oldState.ordinal,
                    n2 = newState.ordinal,
                    s1 = reason
                )
            )
        }
    }

    /**
     * Protected lifecycle hooks for subclasses
     * Subclasses call these hooks instead of emitting events directly
     */

    protected open suspend fun onDeviceFound(
        deviceId: String,
        deviceName: String,
        rssi: Int? = null
    ) {
        Log.d(TAG, "Device found: $deviceName ($deviceId)")
        EventBus.getDefault().post(
            GlassEventMsg(
                GlassEventConstants.MSG_GLASS_DEVICE_FOUND,
                n1 = rssi ?: 0,
                s1 = deviceId,
                s2 = deviceName
            )
        )
    }

    protected open suspend fun onConnected() {
        Log.i(TAG, "Connected to device: $currentDeviceName ($currentDeviceId)")
        updateState(GlassConnectionState.CONNECTED, "Connected successfully")

        EventBus.getDefault().post(
            GlassEventMsg(
                GlassEventConstants.MSG_GLASS_CONNECTED,
                s1 = currentDeviceId,
                s2 = currentDeviceName
            )
        )

        // Reset reconnect attempts on successful connection
        reconnectAttempts = 0
    }

    protected open suspend fun onDisconnected(reason: String, wasExpected: Boolean = false) {
        Log.w(TAG, "Disconnected: $reason (expected: $wasExpected)")
        updateState(GlassConnectionState.IDLE, reason)

        EventBus.getDefault().post(
            GlassEventMsg(
                GlassEventConstants.MSG_GLASS_DISCONNECTED,
                n1 = if (wasExpected) 1 else 0,
                s1 = currentDeviceId,
                s2 = reason
            )
        )

        // Auto-reconnect if disconnection was unexpected
        if (!wasExpected && reconnectAttempts < maxReconnectAttempts) {
//            startReconnection()
        }
    }

    protected open fun onReady() {
        Log.d(TAG, "Device is ready")

        EventBus.getDefault().post(
            GlassEventMsg(
                GlassEventConstants.MSG_GLASS_READY
            )
        )
    }

    protected open fun onWearStatus(worn: Boolean) {
        Log.d(TAG, "Wear status: worn=$worn")
        EventBus.getDefault().post(
            GlassEventMsg(
                GlassEventConstants.MSG_GLASS_WEAR_STATUS,
                n1 = if (worn) 1 else 0
            )
        )
    }

    protected open fun onVoiceWakeup() {
        Log.d(TAG, "Voice wakeup received")
        EventBus.getDefault().post(
            GlassEventMsg(
                GlassEventConstants.MSG_GLASS_VOICE_WAKEUP
            )
        )
    }

    /**
     * Falling edge of the device AI-dialogue flag (e.g. the glasses' button turned voice mode OFF).
     * Symmetric to onVoiceWakeup — lets the agent turn the phone-side voice mode off.
     */
    protected open fun onVoiceWakeupEnd() {
        Log.d(TAG, "Voice wakeup end received")
        EventBus.getDefault().post(
            GlassEventMsg(
                GlassEventConstants.MSG_GLASS_VOICE_STOP
            )
        )
    }

    /**
     * Emit sync started event
     */
    protected open fun onSyncStarted() {
        Log.d(TAG, "File sync started")
        EventBus.getDefault().post(
            GlassEventMsg(
                GlassEventConstants.MSG_GLASS_SYNC_STARTED
            )
        )
    }

    /**
     * Emit sync progress event (overall progress)
     * @param current Number of files completed so far
     * @param total Total number of files to sync
     */
    protected open fun onSyncProgress(current: Int, total: Int) {
        val percent = if (total > 0) (current * 100 / total) else 0
        Log.d(TAG, "Sync progress: $current/$total files ($percent%)")
        EventBus.getDefault().post(
            GlassEventMsg(
                GlassEventConstants.MSG_GLASS_SYNC_PROGRESS,
                n1 = current,
                n2 = total
            )
        )
    }

    /**
     * Emit a per-file synced event the moment a file is fully written to its final subdir.
     * Lets the JS layer index the file and generate its thumbnail incrementally, instead of
     * re-listing the whole media directory.
     * @param path Absolute path of the written file
     * @param type Media category: "photo" | "video" | "audio"
     */
    protected open fun onFileSynced(path: String, type: String) {
        Log.d(TAG, "File synced: $type $path")
        EventBus.getDefault().post(
            GlassEventMsg(
                GlassEventConstants.MSG_GLASS_SYNC_FILE,
                s1 = path,
                s2 = type
            )
        )
    }

    /**
     * Emit sync completed event
     * @param totalFiles Number of files synced
     * @param totalBytes Total bytes transferred
     * @param success Whether sync completed successfully
     * @param error Optional error message if failed
     */
    protected open fun onSyncCompleted(
        totalFiles: Int,
        totalBytes: Long,
        success: Boolean,
        error: String? = null
    ) {
        Log.i(TAG, "File sync completed: $totalFiles files, $totalBytes bytes, success=$success")
        EventBus.getDefault().post(
            GlassEventMsg(
                GlassEventConstants.MSG_GLASS_SYNC_COMPLETED,
                n1 = totalFiles,
                n2 = if (success) 1 else 0,
                s1 = totalBytes.toString(),
                s2 = error
            )
        )
    }


    fun getMediaDirectory(context: Context): String {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        return "${base!!.absolutePath}";
    }

    /**
     * Update storage information
     * Subclasses call this when they receive new storage data
     */
    protected fun updateStorageInfo(newStorageInfo: GlassStorageInfo) {
        storageInfo = newStorageInfo
        Log.d(TAG, "Storage info updated: $newStorageInfo")
    }

    /**
     * Update system information with optional fields
     * Only provided fields will be updated, others remain unchanged
     */
    protected fun updateSystemInfo(
        firmwareVersion: String? = null,
        appVersion: String? = null,
        deviceModel: String? = null,
        batteryLevel: Int? = null,
        isCharging: Boolean? = null,
        batteryVoltage: Float? = null,
        isReady: Boolean? = null
    ) {
        systemInfo = systemInfo.copy(
            firmwareVersion = firmwareVersion ?: systemInfo.firmwareVersion,
            appVersion = appVersion ?: systemInfo.appVersion,
            deviceModel = deviceModel ?: systemInfo.deviceModel,
            batteryLevel = batteryLevel ?: systemInfo.batteryLevel,
            isCharging = isCharging ?: systemInfo.isCharging,
            batteryVoltage = batteryVoltage ?: systemInfo.batteryVoltage,
            isReady = isReady ?: systemInfo.isReady
        )
        Log.d(TAG, "System info updated: $systemInfo")
    }

    protected open suspend fun onError(
        error: String,
        throwable: Throwable? = null,
        isFatal: Boolean = false
    ) {
        Log.e(TAG, "Error: $error (fatal: $isFatal)", throwable)

        EventBus.getDefault().post(
            GlassEventMsg(
                GlassEventConstants.MSG_GLASS_ERROR,
                n1 = if (isFatal) 1 else 0,
                s1 = error,
                s2 = throwable?.javaClass?.simpleName
            )
        )

        if (isFatal) {
            updateState(GlassConnectionState.ERROR, error)
        }
    }

    override suspend fun startOtaUpdate(filePath: String) {
        Log.i(TAG, "Mock OTA update started with file: $filePath")
        
        // Emit mock progress
        scope.launch {
            for (i in 1..10) {
                delay(200)
                EventBus.getDefault().post(
                    GlassEventMsg(
                        GlassEventConstants.MSG_GLASS_OTA_PROGRESS,
                        n1 = i * 10
                    )
                )
            }
            // Emit success
            EventBus.getDefault().post(
                GlassEventMsg(
                    GlassEventConstants.MSG_GLASS_OTA_COMPLETED,
                    n1 = 1 // 1 for success
                )
            )
        }
    }

    override suspend fun startWifiOtaUpdate(url: String) {
        throw UnsupportedOperationException("startWifiOtaUpdate is not supported by this adapter")
    }

    /**
     * Start automatic reconnection with exponential backoff
     */
    private fun startReconnection() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            while (reconnectAttempts < maxReconnectAttempts && isActive) {
                val delay = reconnectDelays.getOrElse(reconnectAttempts) { 30000L }
                Log.i(
                    TAG,
                    "Attempting reconnect ${reconnectAttempts + 1}/$maxReconnectAttempts in ${delay}ms"
                )

                delay(delay)

                try {
                    currentDeviceId?.let { deviceId ->
                        reconnectAttempts++
                        updateState(
                            GlassConnectionState.CONNECTING,
                            "Reconnecting (attempt $reconnectAttempts)"
                        )
                        connect(deviceId)
                        // If successful, onConnected will reset reconnectAttempts
                        break
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Reconnect attempt ${reconnectAttempts} failed", e)
                    if (reconnectAttempts >= maxReconnectAttempts) {
                        onError("Max reconnection attempts reached", e, isFatal = true)
                        break
                    }
                }
            }
        }
    }

    /**
     * Cancel any ongoing reconnection attempts
     */
    protected fun cancelReconnection() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
    }

    /**
     * Execute a block with timeout protection
     */
    protected suspend fun <T> withTimeout(
        timeoutMs: Long,
        block: suspend CoroutineScope.() -> T
    ): T = kotlinx.coroutines.withTimeout(timeoutMs, block)

    /**
     * Safe execution wrapper with error handling
     */
    protected suspend fun <T> safeExecute(
        operation: String,
        block: suspend () -> T
    ): T? {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e // Don't catch cancellation
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute $operation", e)
            onError("Operation failed: $operation", e)
            null
        }
    }

    /**
     * Default implementation for getting storage info
     * Returns the current cached storage info
     * Subclasses can override to provide real-time data
     */
    override suspend fun getStorageInfo(): GlassStorageInfo {
        return storageInfo
    }

    /**
     * Default implementation for getting system info
     * Returns the current cached system info
     * Subclasses can override to provide real-time data
     */
    override suspend fun getSystemInfo(): GlassSystemInfo {
        return systemInfo
    }

    override suspend fun setDeviceName(name: String) {}
    override suspend fun clearMedia() {}
    override suspend fun reboot() {}
    override suspend fun factoryReset() {}
    override suspend fun setAiStatus(status: String) {}

    /**
     * Default implementation for starting scan
     * Subclasses should override if they support scanning
     */
    override suspend fun startScan() {
        Log.w(TAG, "Scanning not supported for this adapter")
        throw UnsupportedOperationException("Scanning not supported for this adapter")
    }

    /**
     * Default implementation for stopping scan
     * Subclasses should override if they support scanning
     */
    override suspend fun stopScan() {
        Log.w(TAG, "Scanning not supported for this adapter")
        throw UnsupportedOperationException("Scanning not supported for this adapter")
    }

    /**
     * Default cancelSync implementation
     * Subclasses that support file sync should override to abort the transfer.
     */
    override suspend fun cancelSync() {
        Log.w(TAG, "cancelSync not supported for this adapter")
    }

    /**
     * Default destroy implementation
     * Cancels coroutine scope and cleans up state
     */
    override fun destroy() {
        Log.d(TAG, "Destroying adapter")
        cancelReconnection()
        scope.cancel()
        connectionState = GlassConnectionState.IDLE
        currentDeviceId = null
        currentDeviceName = null

        // Reset to default values
        storageInfo = GlassStorageInfo(
            totalSize = 0L,
            freeSize = 0L,
            usedSize = 0L,
            photoCount = 0,
            videoCount = 0,
            audioCount = 0
        )
        systemInfo = GlassSystemInfo(
            firmwareVersion = "",
            appVersion = "",
            deviceModel = "",
            batteryLevel = 0,
            isCharging = false,
            batteryVoltage = 0F,
            isReady = false
        )
    }
}
