package expo.modules.glasssdk.tools

import android.content.Context
import expo.modules.glasssdk.util.AppLog
import io.livekit.android.room.Room
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * Manages all LiveKit RPC tools as a singleton
 * Can be accessed from anywhere (LiveKit RPC or React Native)
 *
 * Tools access GlassManager directly via singleton pattern - no injection needed
 */
class ToolManager private constructor(
    private val context: Context,
) {
    private val tools = mutableMapOf<String, BaseTool>()
    private var currentActivity: android.app.Activity? = null

    companion object {
        @Volatile
        private var INSTANCE: ToolManager? = null

        private const val DEFAULT_TIMEOUT_MS = 10000L // 10 seconds

        fun getInstance(context: Context): ToolManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ToolManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }

        fun getInstance(): ToolManager? = INSTANCE
    }

    init {
        // Create tools once
        val toolList = listOf(
            LocationTool(context),
            ContactsTool(context),
            CameraTool(context),
            S3UploadTool(context),
            QrTool(context),
            HealthTool(context)
        )
        toolList.forEach { tools[it.toolName] = it }
        AppLog.i("ToolManager initialized with ${tools.size} tools")
    }

    /**
     * Set the current Activity context
     * Should be called when Activity enters foreground
     */
    fun setActivity(activity: android.app.Activity?) {
        currentActivity = activity
        AppLog.d("ToolManager: Activity context set")
    }

    /**
     * Register all tools with LiveKit RPC
     */
    fun registerWithLiveKit(room: Room) {
        tools.forEach { (name, tool) ->
            room.registerRpcMethod(name) { data ->
                handleToolCall(tool, data.payload, data.responseTimeout.inWholeMilliseconds)
            }
            AppLog.i("ToolManager: Registered RPC method '$name'")
        }
    }

    /**
     * Direct call from React Native or anywhere else
     * @param toolName Name of the tool to call
     * @param params Parameters as JSONObject
     * @return JSON string response
     */
    suspend fun callTool(toolName: String, params: JSONObject, timeout: Long): String {


        val tool = tools[toolName]
            ?: return """{"success":false,"error":"Tool not found: $toolName"}"""

        val payload = JSONObject().apply {
            put("params", params)
        }.toString()

        return handleToolCall(tool, payload, timeout)
    }

    suspend fun requestHealthPermissions(): Boolean {
        val tool = tools["getHealthData"] as? HealthTool ?: return false
        tool.activity = currentActivity
        return tool.requestAuthorization()
    }

    suspend fun hasHealthPermissions(): Boolean {
        val tool = tools["getHealthData"] as? HealthTool ?: return false
        return tool.hasAllGranted()
    }

    /**
     * Handle incoming tool call from LiveKit RPC or direct call
     */
    private suspend fun handleToolCall(
        tool: BaseTool,
        payload: String,
        timeout: Long = DEFAULT_TIMEOUT_MS
    ): String {
        AppLog.d("ToolManager: Executing tool '${tool.toolName}'")

        // Set activity context before execution
        tool.activity = currentActivity

        return try {
            // Check permissions
            if (!tool.hasPermissions()) {
                val missingPerms = tool.requiredPermissions.filter {
                    !tool.hasPermissions()
                }.joinToString(", ")
                AppLog.w("ToolManager: Permission denied for '${tool.toolName}'")
                return """{"success":false,"error":"Permission denied: ${
                    tool.requiredPermissions.joinToString(
                        ", "
                    )
                }"}"""
            }

            // Parse params
            val parsed = try {
                JSONObject(payload)
            } catch (e: Exception) {
                AppLog.e("ToolManager: Failed to parse payload for '${tool.toolName}'", e)
                return """{"success":false,"error":"Invalid payload: ${e.message}"}"""
            }

            // The relay (and callTool) wrap the tool arguments in a { "params": {...} } envelope;
            // unwrap it so tools read their arguments at the top level (e.g. camera's "mode").
            val params = parsed.optJSONObject("params") ?: parsed

            // Execute tool with timeout
            val result = withTimeout(timeout) {
                tool.execute(params)
            }

            AppLog.d("ToolManager: Tool '${tool.toolName}' executed successfully")
            result

        } catch (e: TimeoutCancellationException) {
            AppLog.e("ToolManager: Tool '${tool.toolName}' timed out")
            """{"success":false,"error":"Tool execution timed out after ${DEFAULT_TIMEOUT_MS}ms"}"""

        } catch (e: Exception) {
            AppLog.e("ToolManager: Tool '${tool.toolName}' failed", e)
            """{"success":false,"error":"${e.message ?: "Unknown error"}"}"""
        }
    }

    /**
     * Cleanup tools
     */
    fun cleanup() {
        tools.clear()
        INSTANCE = null
        AppLog.i("ToolManager: Cleaned up all tools")
    }
}
