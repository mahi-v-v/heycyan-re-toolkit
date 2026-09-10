package expo.modules.glasssdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import expo.modules.glasssdk.agent.AgentService
import expo.modules.glasssdk.glass.GlassService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Broadcast receiver to handle Stop action from the unified notification.
 * Stops both GlassService and AgentService.
 */
class StopServicesReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "[StopServicesReceiver]"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ForegroundNotificationManager.ACTION_STOP_SERVICES) {
            Log.d(TAG, "Stop services requested from notification")

            scope.launch {
                try {
                    // Stop both services
                    Log.d(TAG, "Stopping GlassService")
                    GlassService.stopForegroundService(context)

                    Log.d(TAG, "Stopping AgentService")
                    AgentService.stop(context)

                    // Reset notification manager state
                    ForegroundNotificationManager.reset()

                    Log.i(TAG, "All services stopped successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping services", e)
                }
            }
        }
    }
}
