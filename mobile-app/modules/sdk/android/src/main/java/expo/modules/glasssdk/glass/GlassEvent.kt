package expo.modules.glasssdk.glass

/**
 * Connection state enum for glass devices
 */
enum class GlassConnectionState {
    IDLE,           // No connection, not scanning
    SCANNING,       // Actively scanning for devices
    CONNECTING,     // Connection attempt in progress
    CONNECTED,      // Active connection established
    DISCONNECTING,  // Graceful disconnect in progress
    ERROR           // Error state
}

/**
 * Event constants for Glass module (70000 range to avoid conflicts)
 */
object GlassEventConstants {
    const val MSG_GLASS_DEVICE_FOUND = 70001
    const val MSG_GLASS_STATE_CHANGED = 70002
    const val MSG_GLASS_CONNECTED = 70003
    const val MSG_GLASS_DISCONNECTED = 70004
    const val MSG_GLASS_BATTERY = 70005
    const val MSG_GLASS_ERROR = 70006
    const val MSG_GLASS_STORAGE_INFO = 70007
    const val MSG_GLASS_SYSTEM_INFO = 70008
    const val MSG_GLASS_READY = 70009
    const val MSG_GLASS_SYNC_STARTED = 70010
    const val MSG_GLASS_SYNC_PROGRESS = 70011
    const val MSG_GLASS_SYNC_COMPLETED = 70012
    const val MSG_GLASS_WEAR_STATUS = 70013
    const val MSG_GLASS_VOICE_WAKEUP = 70014
    const val MSG_GLASS_BT_STATUS = 70015
    const val MSG_GLASS_STREAM_STATUS = 70016
    const val MSG_GLASS_VOICE_STOP = 70017
    const val MSG_GLASS_SYNC_FILE = 70018  // a single file finished syncing (s1=path, s2=type)
    const val MSG_GLASS_OTA_PROGRESS = 70019
    const val MSG_GLASS_OTA_COMPLETED = 70020
    const val MSG_GLASS_OTA_ERROR = 70021
    const val MSG_GLASS_OTA_LINUX_COMPLETED = 70022

    // Legacy K900 SDK events (from old EventMsg)
    const val MSG_QR_SCAN_RESULT = 10020
}

/**
 * Simple event message class for Glass module
 * Compatible with EventBus posting
 */
data class GlassEventMsg(
    val cmd: Int,
    val n1: Int = 0,
    val n2: Int = 0,
    val s1: String? = null,
    val s2: String? = null
)

/**
 * Event types for glass device communication
 */
object GlassEventType {
    const val DEVICE_FOUND = "GLASS_DEVICE_FOUND"
    const val STATE_CHANGED = "GLASS_STATE_CHANGED"
    const val CONNECTED = "GLASS_CONNECTED"
    const val DISCONNECTED = "GLASS_DISCONNECTED"
    const val BATTERY = "GLASS_BATTERY"
    const val ERROR = "GLASS_ERROR"
    const val VOICE_WAKEUP = "GLASS_VOICE_WAKEUP"
}

/**
 * Base class for all glass events
 */
sealed class GlassEvent(val type: String) {
    /**
     * Convert event to Map for React Native bridge
     */
    abstract fun toMap(): Map<String, Any>

    /**
     * Device discovered during scan
     */
    data class DeviceFound(
        val deviceId: String,
        val deviceName: String,
        val rssi: Int? = null
    ) : GlassEvent(GlassEventType.DEVICE_FOUND) {
        override fun toMap(): Map<String, Any> = buildMap {
            put("deviceId", deviceId)
            put("deviceName", deviceName)
            rssi?.let { put("rssi", it) }
        }
    }

    /**
     * Connection state changed
     */
    data class StateChanged(
        val oldState: GlassConnectionState,
        val newState: GlassConnectionState,
        val reason: String? = null
    ) : GlassEvent(GlassEventType.STATE_CHANGED) {
        override fun toMap(): Map<String, Any> = buildMap {
            put("oldState", oldState.name)
            put("newState", newState.name)
            reason?.let { put("reason", it) }
        }
    }

    /**
     * Successfully connected to device
     */
    data class Connected(
        val deviceId: String,
        val deviceName: String
    ) : GlassEvent(GlassEventType.CONNECTED) {
        override fun toMap(): Map<String, Any> = mapOf(
            "deviceId" to deviceId,
            "deviceName" to deviceName
        )
    }

    /**
     * Disconnected from device
     */
    data class Disconnected(
        val deviceId: String,
        val reason: String,
        val wasExpected: Boolean
    ) : GlassEvent(GlassEventType.DISCONNECTED) {
        override fun toMap(): Map<String, Any> = mapOf(
            "deviceId" to deviceId,
            "reason" to reason,
            "wasExpected" to wasExpected
        )
    }

    /**
     * Battery status update
     */
    data class Battery(
        val level: Int,
        val isCharging: Boolean,
        val voltage: Float? = null
    ) : GlassEvent(GlassEventType.BATTERY) {
        override fun toMap(): Map<String, Any> = buildMap {
            put("level", level)
            put("isCharging", isCharging)
            voltage?.let { put("voltage", it) }
        }
    }

    /**
     * Error occurred
     */
    data class Error(
        val error: String,
        val code: String? = null,
        val isFatal: Boolean = false
    ) : GlassEvent(GlassEventType.ERROR) {
        override fun toMap(): Map<String, Any> = buildMap {
            put("error", error)
            code?.let { put("code", it) }
            put("isFatal", isFatal)
        }
    }
}

/**
 * Storage information for the glass device
 */
data class GlassStorageInfo(
    val totalSize: Long,      // Total storage in bytes
    val freeSize: Long,       // Free storage in bytes
    val usedSize: Long,       // Used storage in bytes
    val photoCount: Int,      // Number of photos
    val videoCount: Int,      // Number of videos
    val audioCount: Int       // Number of audio files
)

/**
 * System information including battery status and device readiness
 */
data class GlassSystemInfo(
    val firmwareVersion: String,
    val appVersion: String,
    val deviceModel: String,
    val batteryLevel: Int,         // 0-100
    val isCharging: Boolean,
    val batteryVoltage: Float,    // Voltage in V
    val isReady: Boolean           // Device fully initialized and ready for use
)
