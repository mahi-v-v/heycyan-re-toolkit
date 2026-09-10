package expo.modules.glasssdk.network

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import expo.modules.glasssdk.util.AppLog
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiClient private constructor(
    private val baseUrl: String
) {
    @PublishedApi
    internal val client: OkHttpClient

    @PublishedApi
    internal val gson: Gson
    private val headers: MutableMap<String, String> = mutableMapOf()

    companion object {
        @Volatile
        private var instance: ApiClient? = null

        /**
         * Get singleton instance of ApiClient
         * @param baseUrl The base URL for API calls (e.g., "https://api.example.com")
         */
        fun getInstance(baseUrl: String): ApiClient {
            return instance ?: synchronized(this) {
                instance ?: ApiClient(baseUrl).also { instance = it }
            }
        }

        /**
         * Get existing instance or null if not initialized
         */
        fun getInstance(): ApiClient? = instance
    }
 
    init {
        // Build OkHttpClient with default configuration
        client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(LoggingInterceptor())
            .build()

        // Build Gson for JSON serialization
        gson = GsonBuilder()
            .setLenient()
            .create()

        AppLog.i("ApiClient initialized with base URL: $baseUrl")
    }

    /**
     * Set default headers for all requests
     * @param key Header key
     * @param value Header value
     */
    fun setHeader(key: String, value: String) {
        headers[key] = value
    }

    /**
     * Remove a default header
     * @param key Header key to remove
     */
    fun removeHeader(key: String) {
        headers.remove(key)
    }


    fun setAuthToken(token: String) {
        setHeader("Cookie", token)
    }


    fun clearAuthToken() {
        removeHeader("Cookie")
    }

    /**
     * Make a GET request
     * @param endpoint API endpoint (e.g., "/users")
     * @param callback Callback for handling response
     */
    inline fun <reified T> get(
        endpoint: String,
        callback: ApiCallback<T>
    ) {
        val url = buildUrl(endpoint)
        val request = buildRequest(url)

        executeRequest(request, callback)
    }

    /**
     * Make a POST request
     * @param endpoint API endpoint (e.g., "/users")
     * @param body Request body (will be serialized to JSON)
     * @param callback Callback for handling response
     */
    inline fun <reified T> post(
        endpoint: String,
        body: Any,
        callback: ApiCallback<T>
    ) {
        val url = buildUrl(endpoint)
        val jsonBody = gson.toJson(body)
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
        val request = buildRequest(url, "POST", requestBody)

        executeRequest(request, callback)
    }

    /**
     * Make a PUT request
     * @param endpoint API endpoint (e.g., "/users/1")
     * @param body Request body (will be serialized to JSON)
     * @param callback Callback for handling response
     */
    inline fun <reified T> put(
        endpoint: String,
        body: Any,
        callback: ApiCallback<T>
    ) {
        val url = buildUrl(endpoint)
        val jsonBody = gson.toJson(body)
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
        val request = buildRequest(url, "PUT", requestBody)

        executeRequest(request, callback)
    }

    /**
     * Make a PATCH request
     * @param endpoint API endpoint (e.g., "/users/1")
     * @param body Request body (will be serialized to JSON)
     * @param callback Callback for handling response
     */
    inline fun <reified T> patch(
        endpoint: String,
        body: Any,
        callback: ApiCallback<T>
    ) {
        val url = buildUrl(endpoint)
        val jsonBody = gson.toJson(body)
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
        val request = buildRequest(url, "PATCH", requestBody)

        executeRequest(request, callback)
    }

    /**
     * Make a DELETE request
     * @param endpoint API endpoint (e.g., "/users/1")
     * @param callback Callback for handling response
     */
    inline fun <reified T> delete(
        endpoint: String,
        callback: ApiCallback<T>
    ) {
        val url = buildUrl(endpoint)
        val request = buildRequest(url, "DELETE")

        executeRequest(request, callback)
    }

    /**
     * Build full URL from endpoint
     */
    @PublishedApi
    internal fun buildUrl(endpoint: String): String {
        val cleanedBase = baseUrl.trimEnd('/')
        val cleanedEndpoint = endpoint.trimStart('/')
        return "$cleanedBase/$cleanedEndpoint"
    }

    /**
     * Build HTTP request with headers
     */
    @PublishedApi
    internal fun buildRequest(
        url: String,
        method: String = "GET",
        body: RequestBody? = null
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .method(method, body)

        // Add default headers
        headers.forEach { (key, value) ->
            builder.addHeader(key, value)
        }

        return builder.build()
    }

    /**
     * Execute HTTP request and handle response
     */
    @PublishedApi
    internal inline fun <reified T> executeRequest(
        request: Request,
        callback: ApiCallback<T>
    ) {
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                AppLog.e("API request failed: ${request.url}", e)
                callback.onError(
                    ApiError(
                        code = -1,
                        message = e.message ?: "Network error",
                        exception = e
                    )
                )
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val responseBody = response.body?.string()

                    if (response.isSuccessful) {
                        // Parse response based on type
                        val data = when {
                            T::class == Unit::class -> Unit as T
                            T::class == String::class -> responseBody as T
                            responseBody.isNullOrEmpty() -> null as T
                            else -> gson.fromJson(responseBody, T::class.java)
                        }

                        AppLog.i("API request successful: ${request.url}")
                        callback.onSuccess(data)
                    } else {
                        AppLog.e("API request failed with code ${response.code}: ${request.url}")
                        callback.onError(
                            ApiError(
                                code = response.code,
                                message = responseBody ?: response.message,
                                response = response
                            )
                        )
                    }
                } catch (e: Exception) {
                    AppLog.e("Failed to parse response: ${request.url}", e)
                    callback.onError(
                        ApiError(
                            code = response.code,
                            message = "Failed to parse response: ${e.message}",
                            exception = e
                        )
                    )
                }
            }
        })
    }

    /**
     * Logging interceptor for debugging
     */
    private class LoggingInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()

            AppLog.d("API Request: ${request.method} ${request.url}")
            request.headers.forEach { (name, value) ->
                AppLog.d("  Header: $name: $value")
            }

            val response = chain.proceed(request)

            AppLog.d("API Response: ${response.code} ${request.url}")

            return response
        }
    }
}

/**
 * Callback interface for API responses
 */
interface ApiCallback<T> {
    fun onSuccess(data: T)
    fun onError(error: ApiError)
}

/**
 * API error data class
 */
data class ApiError(
    val code: Int,
    val message: String,
    val exception: Exception? = null,
    val response: Response? = null
)
