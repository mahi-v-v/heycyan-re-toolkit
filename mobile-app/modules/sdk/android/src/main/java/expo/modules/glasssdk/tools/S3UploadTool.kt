package expo.modules.glasssdk.tools

import android.content.Context
import expo.modules.glasssdk.util.AppLog
import expo.modules.glasssdk.util.FileUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException

class S3UploadTool(context: Context) : BaseTool(context) {

    override val toolName = "s3_upload"

    override val requiredPermissions = arrayOf<String>()

    private val client = OkHttpClient.Builder()
        .build()

    override suspend fun execute(params: JSONObject): String {
        AppLog.i("params ${params.toString()}")
        return try {
            val filePath = params.optString("filePath", "")
            val presignedUrl = params.optString("presignedUrl", "")
            val publicUrl = params.optString("url", "")
            val contentType = params.optString("contentType", "")


            if (filePath.isEmpty()) {
                return """{"success":false,"error":"filePath is required"}"""
            }

            if (presignedUrl.isEmpty()) {
                return """{"success":false,"error":"presignedUrl is required"}"""
            }


            val file = File(filePath)
            if (!file.exists()) {
                return """{"success":false,"error":"File not found: $filePath"}"""
            }
            if (!file.isFile) {
                return """{"success":false,"error":"Path is not a file: $filePath"}"""
            }


            val mimeType = if (contentType.isNotEmpty()) {
                contentType
            } else {
                FileUtil.detectContentType(file)
            }

            // Upload file
            uploadToS3(file, publicUrl, presignedUrl, mimeType);

            // Return response
            JSONObject().apply {
                put("success", true)
                put("url", publicUrl)
                put("size", file.length())
            }.toString()

        } catch (e: IOException) {
            AppLog.e("S3UploadTool: Upload failed", e)
            """{"success":false,"error":"Upload failed: ${e.message}"}"""

        } catch (e: Exception) {
            AppLog.e("S3UploadTool: Unexpected error", e)
            """{"success":false,"error":"${e.message ?: "Upload failed"}"}"""
        }
    }

    /**
     * Upload file to S3 using presigned URL
     */
    private suspend fun uploadToS3(file: File, publicUrl: String, presignedUrl: String, contentType: String): String {
        return withContext(Dispatchers.IO) {
            val mediaType = contentType.toMediaType()
            val requestBody = file.asRequestBody(mediaType)

            val request = Request.Builder()
                .url(presignedUrl)
                .put(requestBody)
                .header("Content-Type", contentType)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Upload failed with status ${response.code}: ${response.message}")
                }

                // Extract public URL from presigned URL (remove query parameters)

                AppLog.i("S3UploadTool: File uploaded successfully to $publicUrl")
                publicUrl
            }
        }
    }
}
