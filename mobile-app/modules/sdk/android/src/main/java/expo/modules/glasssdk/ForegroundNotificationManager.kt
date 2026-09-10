package expo.modules.glasssdk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Unified notification manager for foreground services.
 * Consolidates GlassService and AgentService notifications into a single notification.
 *
 * Industry best practice: Multiple foreground services should share a single notification
 * to avoid notification clutter (per Google Navigation SDK guidelines).
 */
object ForegroundNotificationManager {
    private const val NOTIFICATION_ID = 1000
    private const val CHANNEL_ID = "glass_services_channel"
    private const val CHANNEL_NAME = "Glass Services"

    const val ACTION_STOP_SERVICES = "expo.modules.glasssdk.ACTION_STOP_SERVICES"

    // Thread-safe state tracking
    @Volatile
    private var glassConnected: Boolean = false

    @Volatile
    private var glassDeviceName: String? = null

    @Volatile
    private var agentActive: Boolean = false

    private val lock = Any()

    /**
     * Gets the shared notification ID used by both services.
     */
    fun getNotificationId(): Int = NOTIFICATION_ID

    /**
     * Updates the Glass service state and returns updated notification.
     *
     * @param context Android context
     * @param connected Whether glass is connected
     * @param deviceName Name of the connected device (null if disconnected)
     * @return Updated notification to use with startForeground()
     */
    @Synchronized
    fun updateGlassState(context: Context, connected: Boolean, deviceName: String?): Notification {
        synchronized(lock) {
            glassConnected = connected
            glassDeviceName = if (connected) deviceName else null
        }
        return buildNotification(context)
    }

    /**
     * Updates the Agent service state and returns updated notification.
     *
     * @param context Android context
     * @param active Whether voice agent is active
     * @return Updated notification to use with startForeground()
     */
    @Synchronized
    fun updateAgentState(context: Context, active: Boolean): Notification {
        synchronized(lock) {
            agentActive = active
        }
        return buildNotification(context)
    }

    /**
     * Checks if any service is currently active.
     * Used to determine if notification should be shown.
     */
    @Synchronized
    fun isAnyServiceActive(): Boolean {
        synchronized(lock) {
            return glassConnected || agentActive
        }
    }

    /**
     * Builds the unified notification with combined state.
     */
    private fun buildNotification(context: Context): Notification {
        createNotificationChannel(context)

        val notificationText = buildNotificationText()
        val stopIntent = createStopIntent(context)

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("the app")
            .setContentText(notificationText)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // TODO: Replace with app icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(
                android.R.drawable.ic_delete,
                "Stop",
                stopIntent
            )
            .build()
    }

    /**
     * Builds notification text based on current state.
     *
     * States:
     * - Both active: "Connected & Listening"
     * - Glass only: "Connected to [device name]"
     * - Agent only: "Listening"
     */
    private fun buildNotificationText(): String {
        synchronized(lock) {
            return when {
                glassConnected && agentActive -> "Connected & Listening"
                glassConnected -> {
                    val deviceName = glassDeviceName ?: "device"
                    "Connected to $deviceName"
                }
                agentActive -> "Listening"
                else -> "Active" // Fallback (shouldn't normally happen)
            }
        }
    }

    /**
     * Creates the notification channel (Android O+).
     */
    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintains connections to smart glasses and voice agent"
                setShowBadge(false)
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Creates PendingIntent for the Stop button.
     */
    private fun createStopIntent(context: Context): PendingIntent {
        val intent = Intent(ACTION_STOP_SERVICES).apply {
            setPackage(context.packageName)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    /**
     * Resets all state (used for cleanup).
     */
    @Synchronized
    fun reset() {
        synchronized(lock) {
            glassConnected = false
            glassDeviceName = null
            agentActive = false
        }
    }
}
