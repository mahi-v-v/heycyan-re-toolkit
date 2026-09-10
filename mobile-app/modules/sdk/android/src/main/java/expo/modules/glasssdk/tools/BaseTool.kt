package expo.modules.glasssdk.tools

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * Base class for all LiveKit RPC tools
 * Each tool must implement execute() to handle tool-specific logic and return JSON string
 *
 * Tools can access GlassManager directly via GlassManager.getInstance() for glass device interaction
 */
abstract class BaseTool(protected val context: Context) {

    /**
     * Tool identifier used for RPC method registration
     * e.g., "getLocation", "searchContacts", "capturePhoto"
     */
    abstract val toolName: String

    /**
     * Required Android permissions for this tool
     */
    abstract val requiredPermissions: Array<String>

    /**
     * Current Activity context (set by ToolManager before execution)
     * Used by tools that need to launch Activities (e.g., camera preview)
     */
    var activity: android.app.Activity? = null

    /**
     * Execute tool with given parameters
     * @param params JSON parameters from RPC caller
     * @return JSON string with result or error
     * Format: {"success":true,...} or {"success":false,"error":"..."}
     */
    abstract suspend fun execute(params: JSONObject): String

    /**
     * Check if tool has required permissions.
     * Open so tools with non-standard permission models (e.g. Health Connect) can override.
     */
    open fun hasPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
