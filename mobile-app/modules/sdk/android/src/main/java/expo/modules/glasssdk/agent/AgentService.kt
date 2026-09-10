package expo.modules.glasssdk.agent

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import expo.modules.glasssdk.ForegroundNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service for maintaining voice agent connection in background
 * Keeps LiveKit connection alive when app is backgrounded or screen is off
 * Critical for handling glass device wake periods
 */
class AgentService : Service() {

    companion object {
        private const val TAG = "[Agent:Service]"

        const val ACTION_START_FOREGROUND = "expo.modules.glasssdk.agent.ACTION_START_FOREGROUND"
        const val ACTION_STOP_FOREGROUND = "expo.modules.glasssdk.agent.ACTION_STOP_FOREGROUND"

        /**
         * Start the foreground service
         */
        fun start(context: Context) {
            val intent = Intent(context, AgentService::class.java).apply {
                action = ACTION_START_FOREGROUND
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
        fun stop(context: Context) {
            val intent = Intent(context, AgentService::class.java).apply {
                action = ACTION_STOP_FOREGROUND
            }
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var agentManager: AgentManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var isServiceStarted = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        agentManager = AgentManager.getInstance(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action} startId=$startId")

        if (intent?.action == ACTION_STOP_FOREGROUND) {
            stopForegroundService()
            isServiceStarted = false
            return START_NOT_STICKY
        }

        // Every other delivery (ACTION_START_FOREGROUND or a null-intent system restart) arrived via
        // startForegroundService(), so startForeground() MUST be called promptly and unconditionally —
        // even if already foreground — or Android throws ForegroundServiceDidNotStartInTimeException.
        // Building the notification (channel creation, PendingIntent, NotificationCompat.build) stays
        // inside the try so that ANY failure on the path to startForeground() — not just the Android 14+
        // microphone-FGS SecurityException (thrown if RECORD_AUDIO isn't held) — fails to a clean stop
        // rather than skipping startForeground() and tripping the foreground-start contract.
        try {
            val notification = ForegroundNotificationManager.updateAgentState(applicationContext, active = true)
            startForeground(ForegroundNotificationManager.getNotificationId(), notification)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed — stopping service", e)
            stopForegroundService()
            isServiceStarted = false
            return START_NOT_STICKY
        }

        if (intent == null) {
            // Process (and the Agent/LiveKit state) was recreated — nothing to keep alive.
            // Satisfy the foreground contract above, then stop. JS restarts the agent when needed.
            Log.i(TAG, "Null-intent restart — stopping; no agent to serve")
            stopForegroundService()
            isServiceStarted = false
            return START_NOT_STICKY
        }

        if (!isServiceStarted) {
            acquireWakeLock()
            isServiceStarted = true
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "App task removed (user closed app)")
        Log.d(TAG, "Service continues running after app closed")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        // Update unified notification manager
        ForegroundNotificationManager.updateAgentState(applicationContext, false)

        // Disconnect agent
        scope.launch {
            try {
                agentManager.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop agent on service destroy", e)
            }
        }

        // Release wake lock and cancel scope
        releaseWakeLock()
        scope.cancel()

        isServiceStarted = false
    }

    /**
     * Stop foreground service
     */
    private fun stopForegroundService() {
        Log.i(TAG, "Stopping foreground service")

        ForegroundNotificationManager.updateAgentState(applicationContext, false)

        if (!ForegroundNotificationManager.isAnyServiceActive()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH)
            }
        }

        stopSelf()
    }

    /**
     * Acquire partial wake lock to keep CPU running
     */
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AgentService:WakeLock"
            ).apply {
                acquire(10 * 60 * 1000L) // 10 minutes max
            }
            Log.d(TAG, "Wake lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }
    }

    /**
     * Release wake lock
     */
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "Wake lock released")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release wake lock", e)
        }
    }
}
