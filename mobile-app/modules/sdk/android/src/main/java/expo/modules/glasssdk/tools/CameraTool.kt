package expo.modules.glasssdk.tools

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import expo.modules.glasssdk.glass.GlassManager
import expo.modules.glasssdk.network.ApiCallback
import expo.modules.glasssdk.network.ApiClient
import expo.modules.glasssdk.network.ApiError
import expo.modules.glasssdk.util.AppLog
import expo.modules.glasssdk.util.FileUtil
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Camera tool for capturing photos using glass or phone camera
 *
 * Request params:
 * {
 *   "device": "glass" | "phone",           // Optional, default "glass"
 *   "camera": "front" | "back",            // Optional, default "back" (phone only)
 *   "quality": 85,                         // Optional, JPEG quality 0-100, default 85 (phone only)
 *   "returnFormat": "base64" | "file"      // Optional, default "file" (phone only)
 * }
 *
 * Response (glass):
 * {"success":true,"device":"glass","format":"file","path":"/path/to/photo.jpg","filename":"photo.jpg","size":245678}
 *
 * Response (phone file):
 * {"success":true,"device":"phone","format":"file","path":"/path/to/photo.jpg","width":1920,"height":1080,"size":245678}
 *
 * Response (phone base64):
 * {"success":true,"device":"phone","format":"base64","image":"iVBORw0KGgo...","width":1920,"height":1080,"size":245678}
 *
 * Note: Glass captures automatically sync the file to phone
 * Note: Phone captures require Activity context and will fail if called from background
 */
class CameraTool(context: Context) : BaseTool(context) {

    companion object {
        private var pendingContinuation: CancellableContinuation<Bitmap>? = null

        @JvmStatic
        fun onCaptureComplete(bitmap: Bitmap) {
            pendingContinuation?.resume(bitmap)
            pendingContinuation = null
        }

        @JvmStatic
        fun onCaptureFailed(error: String) {
            pendingContinuation?.resumeWithException(Exception(error))
            pendingContinuation = null
        }
    }

    override val toolName = "camera"

    override val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA
    )

    override fun hasPermissions(): Boolean {
        // Glass capture uses the glasses' own camera over BLE — no phone CAMERA permission needed.
        if (GlassManager.getInstance(context).isConnected()) return true
        return super.hasPermissions()
    }

    override suspend fun execute(params: JSONObject): String {
        return try {
            AppLog.d("CameraTool: execute raw params=$params (keys=${params.keys().asSequence().toList()})")
            // Parse device parameter
            var device = params.optString("device", "phone")
            val glassManager = GlassManager.getInstance(context)

            if (glassManager.isConnected()) {
                device = "glass"
            };

            // Video recording is glass-only; the phone path is a photo-only native camera.
            val mode = params.optString("mode", "photo")
            AppLog.d("CameraTool: resolved device=$device mode='$mode' (connected=${glassManager.isConnected()})")
            if ((mode == "start_video" || mode == "stop_video") && device != "glass") {
                return """{"success":false,"error":"Video recording requires connected glasses"}"""
            }

            val captured = when (device) {
                "glass" -> captureWithGlass(params)
                "phone" -> captureWithPhone(params)
                else -> """{"success":false,"error":"Invalid device: $device. Use 'glass' or 'phone'"}"""
            }
            addUploadedUrl(captured)

        } catch (e: SecurityException) {
            AppLog.e("CameraTool: Permission denied", e)
            """{"success":false,"error":"Camera permission denied"}"""

        } catch (e: Exception) {
            AppLog.e("CameraTool: Failed to capture photo", e)
            """{"success":false,"error":"${e.message ?: "Failed to capture photo"}"}"""
        }
    }

    /**
     * Capture photo using glass device via GlassManager
     */
    private suspend fun captureWithGlass(params: JSONObject): String {
        return try {
            // Access GlassManager singleton directly
            val glassManager = GlassManager.getInstance(context)

            if (!glassManager.isConnected()) {
                return """{"success":false,"error":"Glass device not connected"}"""
            }

            // Video recording (glass-only). Recording is saved on the glasses and synced over
            // Wi-Fi later, so these just return a recording-state ack (no inline file/upload).
            val mode = params.optString("mode", "photo")
            AppLog.d("CameraTool: captureWithGlass branch mode='$mode'")
            when (mode) {
                "start_video" -> {
                    AppLog.d("CameraTool: -> startVideo()")
                    val r = glassManager.startVideo()
                    return JSONObject().apply {
                        put("success", r.success)
                        put("device", "glass")
                        put("mode", "video")
                        put("recording", r.success)
                        if (!r.success) put("error", "Failed to start recording (code ${r.errorCode})")
                    }.toString()
                }
                "stop_video" -> {
                    AppLog.d("CameraTool: -> stopVideo()")
                    glassManager.stopVideo()
                    return JSONObject().apply {
                        put("success", true)
                        put("device", "glass")
                        put("mode", "video")
                        put("recording", false)
                    }.toString()
                }
            }

            AppLog.d("CameraTool: -> takeAiImage() (photo path, mode='$mode')")
            val filePath = glassManager.takeAiImage()

            val response = JSONObject().apply {
                put("success", true)
                put("device", "glass")
                put("format", "file")
                put("path", filePath)
            }
            response.toString()

        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            AppLog.e("CameraTool: Glass AI image capture timed out (90s)", e)
            """{"success":false,"error":"Glass camera timed out. Please try again or check if glass is responding."}"""
        } catch (e: IllegalStateException) {
            AppLog.e("CameraTool: Glass state error", e)
            """{"success":false,"error":"${e.message ?: "Glass not ready for capture"}"}"""
        } catch (e: Exception) {
            AppLog.e("CameraTool: Glass capture failed", e)
            """{"success":false,"error":"${e.message ?: "Failed to capture photo"}"}"""
        }
    }

    /**
     * Capture photo using phone's native camera
     */
    private suspend fun captureWithPhone(params: JSONObject): String {
        // Parse parameters
        val cameraType = params.optString("camera", "back")
        val quality = params.optInt("quality", 85).coerceIn(0, 100)
        val returnFormat = params.optString("returnFormat", "file")

        // Launch native camera app
        val bitmap = launchNativeCamera(cameraType)

        // Prepare response based on format
        val response = when (returnFormat) {
            "base64" -> {
                val base64 = bitmapToBase64(bitmap, quality)

                // Check payload size (15KiB limit)
                if (base64.length > 15 * 1024) {
                    AppLog.w("CameraTool: Base64 image exceeds 15KiB RPC limit, consider using 'file' format")
                }

                JSONObject().apply {
                    put("success", true)
                    put("device", "phone")
                    put("format", "base64")
                    put("image", base64)
                    put("width", bitmap.width)
                    put("height", bitmap.height)
                    put("size", base64.length)
                }
            }

            "file" -> {
                val file = saveBitmapToFile(bitmap, quality)
                JSONObject().apply {
                    put("success", true)
                    put("device", "phone")
                    put("format", "file")
                    put("path", file.absolutePath)
                    put("width", bitmap.width)
                    put("height", bitmap.height)
                    put("size", file.length())
                }
            }

            else -> {
                return """{"success":false,"error":"Invalid returnFormat: $returnFormat. Use 'base64' or 'file'"}"""
            }
        }

        return response.toString()
    }

    /**
     * Launch device's native camera app and wait for result
     */
    private suspend fun launchNativeCamera(cameraType: String): Bitmap {
        return suspendCancellableCoroutine { continuation ->
            // Check if Activity context available
            if (activity == null) {
                continuation.resumeWithException(
                    Exception("Native camera unavailable: No Activity context")
                )
                return@suspendCancellableCoroutine
            }

            // Store continuation for callback
            pendingContinuation = continuation

            // Launch native camera activity
            val intent = Intent(context, NativeCameraActivity::class.java).apply {
                putExtra("cameraType", cameraType)
            }
            activity!!.startActivity(intent)

            // Handle cancellation
            continuation.invokeOnCancellation {
                pendingContinuation = null
            }
        }
    }

    /**
     * Convert bitmap to Base64 string
     */
    private fun bitmapToBase64(bitmap: Bitmap, quality: Int): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * Save bitmap to file
     */
    private fun saveBitmapToFile(bitmap: Bitmap, quality: Int): File {
        val file = File(context.filesDir, "camera_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        }
        return file
    }

    data class PresignedResponse(val url: String, val publicUrl: String)

    private suspend fun addUploadedUrl(resultStr: String): String {
        val json = try {
            JSONObject(resultStr)
        } catch (e: Exception) {
            return resultStr
        }
        if (!json.optBoolean("success")) return resultStr
        val path = json.optString("path", "")
        if (path.isEmpty() || json.has("url")) return resultStr

        return try {
            val file = File(path)
            val (presignedUrl, publicUrl) = mintPresigned(file)
            val uploadResult = S3UploadTool(context).execute(JSONObject().apply {
                put("filePath", path)
                put("presignedUrl", presignedUrl)
                put("url", publicUrl)
            })
            if (JSONObject(uploadResult).optBoolean("success")) {
                json.put("url", publicUrl)
            }
            json.toString()
        } catch (e: Exception) {
            AppLog.e("CameraTool: upload failed", e)
            json.toString()
        }
    }

    private suspend fun mintPresigned(file: File): Pair<String, String> =
        suspendCancellableCoroutine { cont ->
            val api = ApiClient.getInstance()
            if (api == null) {
                cont.resumeWithException(IllegalStateException("ApiClient not initialized"))
                return@suspendCancellableCoroutine
            }
            val body = mapOf(
                "filename" to file.name,
                "contentType" to FileUtil.detectContentType(file)
            )
            api.post("/user/presigned-url", body, object : ApiCallback<PresignedResponse> {
                override fun onSuccess(data: PresignedResponse) {
                    cont.resume(data.url to data.publicUrl)
                }

                override fun onError(error: ApiError) {
                    cont.resumeWithException(Exception(error.message))
                }
            })
        }
}

/**
 * Native Camera Activity - Launches device's default camera app
 */
class NativeCameraActivity : Activity() {

    companion object {
        private const val REQUEST_IMAGE_CAPTURE = 1
    }

    private var cameraType: String = "back"
    private var photoUri: android.net.Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get camera type from intent
        cameraType = intent.getStringExtra("cameraType") ?: "back"

        // Create temp file for photo
        val photoFile = File(cacheDir, "camera_native_${System.currentTimeMillis()}.jpg")
        photoUri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.fileprovider",
            photoFile
        )

        // Launch native camera
        val takePictureIntent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(android.provider.MediaStore.EXTRA_OUTPUT, photoUri)

            // Set camera facing (front/back) if supported
            if (cameraType == "front") {
                putExtra(
                    "android.intent.extras.CAMERA_FACING",
                    android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT
                )
                putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
            }
        }

        if (takePictureIntent.resolveActivity(packageManager) != null) {
            startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE)
        } else {
            finishWithError("No camera app found")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_IMAGE_CAPTURE) {
            if (resultCode == RESULT_OK && photoUri != null) {
                try {
                    // Read the captured image
                    val inputStream = contentResolver.openInputStream(photoUri!!)
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()

                    // Delete temp file
                    photoUri?.path?.let { File(it).delete() }

                    // Return bitmap to CameraTool
                    CameraTool.onCaptureComplete(bitmap)
                    finish()
                } catch (e: Exception) {
                    AppLog.e("NativeCameraActivity: Failed to process image", e)
                    finishWithError("Failed to process image: ${e.message}")
                }
            } else {
                finishWithError("Camera capture cancelled")
            }
        }
    }

    private fun finishWithError(error: String) {
        Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
        CameraTool.onCaptureFailed(error)
        finish()
    }
}
