package expo.modules.glasssdk.tools

import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

class ToolsModule : Module() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun definition() = ModuleDefinition {
        Name("ToolsModule")

        OnCreate {
            ToolManager.getInstance(appContext.reactContext!!.applicationContext)
        }

        AsyncFunction("executeDeviceTool") { name: String, payload: String, promise: Promise ->
            scope.launch {
                try {
                    val params = try { JSONObject(payload) } catch (e: Exception) { JSONObject() }
                    ToolManager.getInstance()?.setActivity(appContext.currentActivity)
                    val result = ToolManager.getInstance()?.callTool(name, params, 30_000L)
                        ?: """{"success":false,"error":"ToolManager unavailable"}"""
                    promise.resolve(result)
                } catch (e: Throwable) {
                    promise.reject("EXEC_TOOL_FAILED", e.message ?: "executeDeviceTool failed", e)
                }
            }
        }

        AsyncFunction("requestHealthPermissions") { promise: Promise ->
            scope.launch {
                try {
                    ToolManager.getInstance()?.setActivity(appContext.currentActivity)
                    val granted = ToolManager.getInstance()?.requestHealthPermissions() ?: false
                    promise.resolve(granted)
                } catch (e: Throwable) {
                    promise.reject("HEALTH_PERM_FAILED", e.message ?: "requestHealthPermissions failed", e)
                }
            }
        }

        AsyncFunction("hasHealthPermissions") { promise: Promise ->
            scope.launch {
                try {
                    val granted = ToolManager.getInstance()?.hasHealthPermissions() ?: false
                    promise.resolve(granted)
                } catch (e: Throwable) {
                    promise.resolve(false)
                }
            }
        }
    }
}
