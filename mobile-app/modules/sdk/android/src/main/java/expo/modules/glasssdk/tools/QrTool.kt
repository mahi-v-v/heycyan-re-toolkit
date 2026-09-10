package expo.modules.glasssdk.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import expo.modules.glasssdk.glass.GlassEventConstants
import expo.modules.glasssdk.glass.GlassEventMsg
import expo.modules.glasssdk.glass.GlassManager
import expo.modules.glasssdk.util.AppLog
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * QR Scanner tool for scanning QR codes using glass device
 *
 * Request params: {} (no parameters required)
 *
 * Response (success):
 * {"success":true,"data":"QR code content"}
 *
 * Response (failure):
 * {"success":false,"error":"error message","errorCode":123}
 *
 * Note: This is a one-shot scanner with 30 second timeout
 */
class QrTool(context: Context) : BaseTool(context) {

    companion object {
        private var pendingContinuation: CancellableContinuation<String>? = null
        private const val SCAN_TIMEOUT_MS = 30000L // 30 seconds
    }

    override val toolName = "scanQR"

    override val requiredPermissions =
        arrayOf<String>() // No permissions needed - scanning on glass

    override suspend fun execute(params: JSONObject): String {
        return try {
            // Access GlassManager singleton directly
            val glassManager = GlassManager.getInstance(context)

            if (!glassManager.isConnected()) {
                return """{"success":false,"error":"Glass device not connected"}"""
            }

            withTimeout(SCAN_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    // Check if there's already a pending scan
                    if (pendingContinuation != null) {
                        continuation.resume("""{"success":false,"error":"QR scan already in progress"}""")
                        return@suspendCancellableCoroutine
                    }

                    // Register EventBus to receive scan results
                    if (!EventBus.getDefault().isRegistered(this@QrTool)) {
                        EventBus.getDefault().register(this@QrTool)
                    }

                    // Store continuation
                    pendingContinuation = continuation

                    // Send scan command to glass via GlassManager (suspend call in coroutine scope)
                    // Note: GlassManager uses sendCommand for QR scanning
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            glassManager.sendCommand("scanQr", emptyMap())
                            AppLog.i("QrTool: QR scan initiated via GlassManager")
                        } catch (e: Exception) {
                            continuation.resume("""{"success":false,"error":"Failed to initiate QR scan: ${e.message}"}""")
                            cleanup()
                        }
                    }

                    // Cleanup on cancellation
                    continuation.invokeOnCancellation {
                        cleanup()
                        AppLog.i("QrTool: QR scan cancelled")
                    }
                }
            }

        } catch (e: TimeoutCancellationException) {
            cleanup()
            AppLog.e("QrTool: QR scan timeout")
            """{"success":false,"error":"QR scan timed out after ${SCAN_TIMEOUT_MS}ms"}"""

        } catch (e: Exception) {
            cleanup()
            AppLog.e("QrTool: QR scan failed", e)
            """{"success":false,"error":"${e.message ?: "QR scan failed"}"}"""
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEventReceived(msg: GlassEventMsg) {
        if (msg.cmd == GlassEventConstants.MSG_QR_SCAN_RESULT) {
            val success = msg.n1 == 1
            val errorCode = msg.n2
            val qrData = msg.s1 ?: ""

            AppLog.i("QrTool: Received scan result - success=$success, data=$qrData, errorCode=$errorCode")

            // Handle UPI links - automatically open them
            if (success && qrData.startsWith("upi://", ignoreCase = true)) {
                AppLog.i("QrTool: UPI link detected, opening: $qrData")
                openUpiLink(qrData)
            }

            // Build response
            val response = if (success) {
                JSONObject().apply {
                    put("success", true)
                    put("data", qrData)
                    if (qrData.startsWith("upi://", ignoreCase = true)) {
                        put("type", "upi")
                        put("opened", true)
                    }
                }.toString()
            } else {
                JSONObject().apply {
                    put("success", false)
                    put("error", "Scan failed")
                    put("errorCode", errorCode)
                }.toString()
            }

            // Resume continuation
            pendingContinuation?.resume(response)

            // Cleanup
            cleanup()
        }
    }

    private fun openUpiLink(upiUrl: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(upiUrl)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else {
                AppLog.i("no upi app installed")
                // show message: No UPI app installed
            }

            AppLog.i("QrTool: UPI link opened successfully")
        } catch (e: Exception) {
            AppLog.e("QrTool: Failed to open UPI link", e)
        }
    }

    private fun cleanup() {
        pendingContinuation = null
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
    }
}
