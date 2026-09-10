package expo.modules.glasssdk.glass

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import expo.modules.glasssdk.ForegroundNotificationManager

/**
 * Foreground service for Glass device management
 * Keeps the BLE connection alive even when app is in background/closed
 *
 * This service maintains the BLE connection independently of the app lifecycle.
 * When the app is closed, the service continues running and keeps the connection alive.
 * When the app reopens, it reconnects to the existing GlassManager instance.
 */
class GlassService : Service() {

    companion object {
        private const val TAG = "[Glass:Service]"

        const val ACTION_START_FOREGROUND = "expo.modules.glasssdk.glass.ACTION_START_FOREGROUND"
        const val ACTION_STOP_FOREGROUND = "expo.modules.glasssdk.glass.ACTION_STOP_FOREGROUND"

        const val EXTRA_DEVICE_NAME = "device_name"

        /**
         * Start the foreground service. The device name (from JS) is used only for the
         * notification text — it is not persisted; JS owns device identity.
         */
        fun startForegroundService(context: Context, deviceName: String? = null) {
            val intent = Intent(context, GlassService::class.java).apply {
                action = ACTION_START_FOREGROUND
                deviceName?.let { putExtra(EXTRA_DEVICE_NAME, it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stop the foreground service
         */
        fun stopForegroundService(context: Context) {
            val intent = Intent(context, GlassService::class.java).apply {
                action = ACTION_STOP_FOREGROUND
            }
            context.startService(intent)
        }
    }

    private val binder = GlassServiceBinder()
    private var isServiceStarted = false
    private var glassManager: GlassManager? = null

    inner class GlassServiceBinder : Binder() {
        fun getService(): GlassService = this@GlassService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        // Initialize GlassManager
        glassManager = GlassManager.getInstance(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        if (intent?.action == ACTION_STOP_FOREGROUND) {
            stopForegroundService()
            isServiceStarted = false
            return START_NOT_STICKY
        }

        // Every other delivery (ACTION_START_FOREGROUND or a null-intent system restart) arrived via
        // startForegroundService(), so startForeground() MUST be called promptly and unconditionally —
        // even if already foreground — or Android throws ForegroundServiceDidNotStartInTimeException.
        val deviceName = intent?.getStringExtra(EXTRA_DEVICE_NAME)
        val notification = ForegroundNotificationManager.updateGlassState(
            applicationContext,
            connected = true,
            deviceName = deviceName
        )
        // Fail to a clean stop rather than crashing if startForeground() is rejected (e.g. an
        // Android 14+ connectedDevice-FGS permission issue).
        try {
            startForeground(ForegroundNotificationManager.getNotificationId(), notification)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed — stopping service", e)
            stopForegroundService()
            isServiceStarted = false
            return START_NOT_STICKY
        }

        if (intent == null) {
            // Process recreated; JS (GlassContext) auto-connects to the last device on launch,
            // so there's nothing to keep alive here. Satisfy the foreground contract, then stop.
            Log.i(TAG, "Null-intent restart — stopping; JS handles reconnect")
            stopForegroundService()
            isServiceStarted = false
            return START_NOT_STICKY
        }

        isServiceStarted = true
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "Service bound")
        return binder
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "App task removed (user closed app)")

        // App was closed by user, but service continues running
        // Notification will continue to show current state
        Log.d(TAG, "Service continues running after app closed")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        // Only clean up notification state — do NOT destroy GlassManager or adapters.
        // Adapter lifecycle is owned by GlassModule (OnCreate/OnDestroy), not this service.
        // Destroying adapters here nukes peripheralMap on every disconnect, breaking reconnect.
        ForegroundNotificationManager.updateGlassState(applicationContext, false, null)

        glassManager = null
        isServiceStarted = false
    }

    /**
     * Stop foreground service
     */
    private fun stopForegroundService() {
        Log.i(TAG, "Stopping foreground service")

        // Update unified notification manager
        ForegroundNotificationManager.updateGlassState(applicationContext, false, null)

        // Only remove notification if no other services are active
        if (!ForegroundNotificationManager.isAnyServiceActive()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } else {
            // Another service is still active, just update the notification
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH)
            }
        }

        stopSelf()
    }

    /**
     * Get GlassManager instance
     */
    fun getGlassManager(): GlassManager? = glassManager
}
