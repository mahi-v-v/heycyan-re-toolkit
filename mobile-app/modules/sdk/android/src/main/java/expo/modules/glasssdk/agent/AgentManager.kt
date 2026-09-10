package expo.modules.glasssdk.agent

import android.app.Activity
import android.content.Context
import android.util.Log
import expo.modules.glasssdk.tools.ToolManager
import io.livekit.android.renderer.TextureViewRenderer
import org.greenrobot.eventbus.EventBus

/**
 * Central manager for voice agent
 * Handles agent lifecycle, state management, and API routing
 */
class AgentManager private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "[Agent:Manager]"

        @Volatile
        private var instance: AgentManager? = null

        /**
         * Get singleton instance
         */
        fun getInstance(context: Context): AgentManager {
            return instance ?: synchronized(this) {
                instance ?: AgentManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // Currently active agent instance
    private var agent: Agent? = null

    // Track if foreground service is running
    private var isForegroundServiceRunning = false

    // Agent name to dispatch for token requests (null = use default)
    private var agentName: String? = null

    // RPC method names registered from JS — persisted so they survive agent restarts
    private val pendingRpcMethods = mutableSetOf<String>()

    /**
     * Start the voice agent - fetches token from backend and connects to LiveKit
     */

   fun init() {
        if(agent == null) {
            agent = Agent(context)
            pendingRpcMethods.forEach { agent?.registerRpcMethod(it) }
        }
    }

    fun start(audio: Boolean = true) {
        Log.i(TAG, "Starting voice agent (audio=$audio)")

        if (agent?.getConnectionState() === ConnectionState.CONNECTED) {
            Log.w(TAG, "Agent already connected, stopping previous instance first")
            stop()
        }

        try {
            agent?.start(audio, agentName)

            // Start foreground service to keep connection alive
            AgentService.start(context)
            isForegroundServiceRunning = true

            Log.i(TAG, "Voice agent started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start voice agent", e)
            postErrorEvent("START_FAILED", e.message ?: "Failed to start agent")
            throw e
        }
    }

    /**
     * Stop the voice agent
     */
    fun stop() {
        Log.i(TAG, "Stopping voice agent")

        try {
            agent?.stop()
//            agent = null

            // Stop foreground service
            if (isForegroundServiceRunning) {
                AgentService.stop(context)
                isForegroundServiceRunning = false
            }

            Log.i(TAG, "Voice agent stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping voice agent", e)
            postErrorEvent("STOP_FAILED", e.message ?: "Failed to stop agent")
        }
    }

    /**
     * Send text message to agent
     */
    fun sendText(text: String) {
        agent?.sendText(text) ?: run {
            Log.w(TAG, "Cannot send text - agent not running")
            throw IllegalStateException("Agent not running")
        }
    }

    /**
     * Send file to agent
     */
    fun sendFile(path: String) {
        agent?.sendFile(path) ?: run {
            Log.w(TAG, "Cannot send file - agent not running")
            throw IllegalStateException("Agent not running")
        }
    }

    /**
     * Send binary data to agent
     */
    fun sendData(data: ByteArray, reliable: Boolean = true) {
        agent?.sendData(data, reliable) ?: run {
            Log.w(TAG, "Cannot send data - agent not running")
            throw IllegalStateException("Agent not running")
        }
    }

    /**
     * Set microphone enabled/disabled
     */
    fun setMicEnabled(enabled: Boolean) {
        agent?.setMicEnabled(enabled) ?: run {
            Log.w(TAG, "Cannot set mic enabled - agent not running")
            throw IllegalStateException("Agent not running")
        }
    }

    /**
     * Set speaker enabled/disabled
     */
    fun setSpeakerEnabled(enabled: Boolean) {
        agent?.setSpeakerEnabled(enabled) ?: run {
            Log.w(TAG, "Cannot set speaker enabled - agent not running")
            throw IllegalStateException("Agent not running")
        }
    }

    /**
     * Toggle audio (both mic and agent audio)
     */
    fun toggleAudio() {
        agent?.toggleAudio() ?: run {
            Log.w(TAG, "Cannot toggle audio - agent not running")
            throw IllegalStateException("Agent not running")
        }
    }

    /**
     * Set audio enabled/disabled
     */
    fun setAudioEnabled(enabled: Boolean) {
        agent?.setAudioEnabled(enabled) ?: run {
            Log.w(TAG, "Cannot set audio enabled - agent not running")
            throw IllegalStateException("Agent not running")
        }
    }

    /** Re-evaluate and push the glasses' AI-reply mode (e.g. after glasses (re)connect). */
    fun syncGlassAiStatus() {
        agent?.syncGlassAiStatus()
    }

    /**
     * Get current connection state
     */
    fun getConnectionState(): ConnectionState {
        return agent?.getConnectionState() ?: ConnectionState.DISCONNECTED
    }

    /**
     * Get microphone enabled state
     */
    fun getMicEnabled(): Boolean {
        return agent?.getMicEnabled() ?: false
    }

    /**
     * Get speaker enabled state
     */
    fun getSpeakerEnabled(): Boolean {
        return agent?.getSpeakerEnabled() ?: false
    }

    /**
     * Get room ID
     */
    fun getRoomId(): String? {
        return agent?.getRoomId()
    }

    fun reloadTools() {
        agent?.reloadTools() ?: Log.w(TAG, "Cannot reload tools - agent not running")
    }

    /**
     * Start an app session
     */
    fun startApp(appId: String) {
        agent?.startApp(appId) ?: run {
            Log.w(TAG, "Cannot start app - agent not running")
            throw IllegalStateException("Agent not running")
        }
    }

    fun sendFiles(urls: List<String>, message: String) {
        agent?.sendFiles(urls, message) ?: Log.w(TAG, "Cannot send files - agent not running")
    }

    suspend fun performRpc(method: String, payload: String): String {
        return agent?.performRpc(method, payload) ?: throw IllegalStateException("Agent not running")
    }

    fun registerRpcMethod(method: String) {
        pendingRpcMethods.add(method)
        agent?.registerRpcMethod(method)
    }

    /**
     * Set Activity context for tools that need UI
     */
    fun setActivity(activity: Activity) {
        ToolManager.getInstance()?.setActivity(activity)
        Log.d(TAG, "Activity context set for tools")
    }

    /**
     * Destroy the manager and clean up resources
     */
    fun destroy() {
        Log.i(TAG, "Destroying AgentManager")
        stop()
    }

    /**
     * Post error event to EventBus
     */
    private fun postErrorEvent(code: String, message: String) {
        try {
            EventBus.getDefault().post(
                AgentEventMsg(
                    cmd = AgentEventConstants.MSG_AGENT_ERROR,
                    s1 = code,
                    s2 = message
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post error event", e)
        }
    }

    /**
     * Set agent name for token dispatch (null = use default)
     */
    fun setAgentName(name: String?) {
        Log.i(TAG, "Setting agent name: $name")
        agentName = name
    }

    /**
     * Set authentication cookie
     * Simply stores it in the agent instance for use in API requests
     */
    fun setCookie(cookie: String) {
        Log.i(TAG, "Setting cookie ${agent}")
        agent?.setCookie(cookie)
    }

    // Phone camera streaming

    suspend fun startPhoneStream(fps: Int, bitrate: Int) {
        agent?.startPhoneStream(fps, bitrate) ?: throw IllegalStateException("Agent not initialized")
    }

    suspend fun stopPhoneStream() {
        agent?.stopPhoneStream()
    }

    fun flipPhoneCamera() {
        agent?.flipPhoneCamera()
    }

    fun getRoom(): io.livekit.android.room.Room? = agent?.getRoom()

    fun addPhonePreviewRenderer(renderer: TextureViewRenderer) {
        agent?.addPhonePreviewRenderer(renderer)
    }

    fun removePhonePreviewRenderer(renderer: TextureViewRenderer) {
        agent?.removePhonePreviewRenderer(renderer)
    }

    fun isPhoneStreaming(): Boolean = agent?.isPhoneStreaming() ?: false

    /**
     * Fetch WHIP credentials from backend and start glass stream over the provided WiFi.
     * Status is reported via GLASS_STREAM_STATUS BLE events.
     */
    private var glassStreaming = false

    suspend fun startGlassStream(ssid: String, pwd: String, fps: Int = 20, bitrate: Int = 800) {
        val ag = agent ?: throw IllegalStateException("Agent not initialized")
        val (ingressUrl, streamKey) = ag.fetchStreamCredentials()
        glassStreaming = true
        expo.modules.glasssdk.glass.GlassManager.getInstance(context)
            .startStream(ssid, pwd, ingressUrl, streamKey, fps, bitrate)
    }

    fun stopGlassStream() {
        glassStreaming = false
        expo.modules.glasssdk.glass.GlassManager.getInstance(context).stopStream()
    }

    fun isGlassStreaming(): Boolean = glassStreaming

    fun startGlassVideoStream(host: String, port: Int) {
        agent?.startGlassVideoStream(host, port)
    }

    fun stopGlassVideoStream() {
        agent?.stopGlassVideoStream()
    }

    fun addGlassPreviewRenderer(renderer: TextureViewRenderer) {
        agent?.addGlassPreviewRenderer(renderer)
    }

    fun removeGlassPreviewRenderer(renderer: TextureViewRenderer) {
        agent?.removeGlassPreviewRenderer(renderer)
    }

    fun isGlassVideoStreamingActive(): Boolean =
        agent?.isGlassVideoStreamingActive() ?: false
}

/**
 * Connection state for voice agent
 */
enum class ConnectionState {
    DISCONNECTED,   // Initial state or after cleanup
    CONNECTING,     // start() called, connecting to room
    CONNECTED,      // Successfully connected, audio flowing
    RECONNECTING,   // Lost connection, attempting to reconnect
    FAILED          // Connection failed, cleanup called
}
