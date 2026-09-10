package expo.modules.glasssdk.agent

import android.nfc.Tag
import android.os.Bundle
import android.util.Log
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.glasssdk.tools.ToolManager
import expo.modules.glasssdk.glass.GlassEventConstants
import expo.modules.glasssdk.glass.GlassEventMsg
import expo.modules.glasssdk.network.ApiClient
import expo.modules.glasssdk.SdkConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

/**
 * Expo module for voice agent control
 * Thin layer that forwards calls to AgentManager and emits events to JS
 */
class AgentModule : Module() {

    companion object {
        private const val TAG = "[Agent:Module]"
        // Duration of inactivity before voice wakeup audio session is auto-disabled
        private const val WAKEUP_AUDIO_TIMEOUT_MS = 10_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var agentManager: AgentManager
    private var wakeupInactivityJob: Job? = null
    private var isWakeupSessionActive: Boolean = false

    override fun definition() = ModuleDefinition {
        Name("AgentModule")

        Events(
            "AGENT_STATE_CHANGED",
            "AGENT_PARTICIPANT_EVENT",
            "AGENT_AUDIO_TRACK_EVENT",
            "AGENT_TEXT_EVENT",
            "AGENT_DATA_EVENT",
            "AGENT_ERROR",
            "AGENT_TOOL_CALL",
            "AGENT_TOOL_RESULT",
            "AGENT_ACTIVE_SPEAKERS",
            "AGENT_TOOL_EXECUTED",
            "AGENT_RPC_METHOD_CALLED"
        )

        OnCreate {
            // Initialize AgentManager
            val context = appContext.reactContext!!.applicationContext
            SdkConfig.init(context)
            ApiClient.getInstance(SdkConfig.apiUrl)
            agentManager = AgentManager.getInstance(context)
            agentManager.init()
            ToolManager.getInstance(appContext.reactContext!!);
            // Register for EventBus events
            EventBus.getDefault().register(this@AgentModule)
        }

        OnDestroy {
            // Unregister from EventBus
            EventBus.getDefault().unregister(this@AgentModule)
            wakeupInactivityJob?.cancel()
            wakeupInactivityJob = null
            isWakeupSessionActive = false
            agentManager.destroy()
        }

        OnActivityEntersForeground {
            ToolManager.getInstance()?.setActivity(appContext.currentActivity)
        }

        OnActivityEntersBackground {
            ToolManager.getInstance()?.setActivity(null)
        }

        /**
         * Start the voice agent - connects to LiveKit
         */
        AsyncFunction("start") { audio: Boolean, promise: Promise ->
            scope.launch {
                try {
                    Log.d(TAG, "Starting voice agent (audio=$audio)")
                    agentManager.start(audio)
                    promise.resolve(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start agent", e)
                    promise.reject("START_FAILED", e.message, e)
                }
            }
        }

        /**
         * Stop the voice agent
         */
        AsyncFunction("stop") { promise: Promise ->
            scope.launch {
                try {
                    Log.d(TAG, "Stopping voice agent")
                    agentManager.stop()
                    promise.resolve(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop agent", e)
                    promise.reject("STOP_FAILED", e.message, e)
                }
            }
        }

        /**
         * Send text message to agent
         */
        AsyncFunction("sendText") { text: String, promise: Promise ->
            scope.launch {
                try {
                    Log.d(TAG, "Sending text: $text")
                    agentManager.sendText(text)
                    promise.resolve(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send text", e)
                    promise.reject("SEND_TEXT_FAILED", e.message, e)
                }
            }
        }

        /**
         * Send file to agent
         */
        AsyncFunction("sendFile") { path: String, promise: Promise ->
            scope.launch {
                try {
                    Log.d(TAG, "Sending file: $path")
                    agentManager.sendFile(path)
                    promise.resolve(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send file", e)
                    promise.reject("SEND_FILE_FAILED", e.message, e)
                }
            }
        }

        /**
         * Send binary data to agent
         */
        AsyncFunction("sendData") { data: ByteArray, reliable: Boolean, promise: Promise ->
            scope.launch {
                try {
                    Log.d(TAG, "Sending data: ${data.size} bytes (reliable=$reliable)")
                    agentManager.sendData(data, reliable)
                    promise.resolve(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send data", e)
                    promise.reject("SEND_DATA_FAILED", e.message, e)
                }
            }
        }

        /**
         * Enable/disable microphone
         */
        AsyncFunction("setMicEnabled") { enabled: Boolean, promise: Promise ->
            scope.launch {
                try {
                    Log.d(TAG, "Setting mic enabled: $enabled")
                    agentManager.setMicEnabled(enabled)
                    promise.resolve(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set mic enabled", e)
                    promise.reject("SET_MIC_FAILED", e.message, e)
                }
            }
        }

        /**
         * Enable/disable speaker
         */
        AsyncFunction("setSpeakerEnabled") { enabled: Boolean, promise: Promise ->
            scope.launch {
                try {
                    Log.d(TAG, "Setting speaker enabled: $enabled")
                    agentManager.setSpeakerEnabled(enabled)
                    promise.resolve(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set speaker enabled", e)
                    promise.reject("SET_SPEAKER_FAILED", e.message, e)
                }
            }
        }

        /**
         * Toggle audio (both mic and agent audio)
         */
        AsyncFunction("toggleAudio") { promise: Promise ->
            scope.launch {
                try {
                    Log.d(TAG, "Toggling audio")
                    agentManager.toggleAudio()
                    promise.resolve(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to toggle audio", e)
                    promise.reject("TOGGLE_AUDIO_FAILED", e.message, e)
                }
            }
        }

        /**
         * Set audio enabled/disabled
         */
        AsyncFunction("setAudioEnabled") { enabled: Boolean, promise: Promise ->
            scope.launch {
                try {
                    Log.d(TAG, "Setting audio enabled: $enabled")
                    agentManager.setAudioEnabled(enabled)
                    promise.resolve(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set audio enabled", e)
                    promise.reject("SET_AUDIO_FAILED", e.message, e)
                }
            }
        }

        /**
         * Get current connection state
         */
        AsyncFunction("getConnectionState") { ->
            try {
                val state = agentManager.getConnectionState()
                return@AsyncFunction state.name
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get connection state", e)
                throw e
            }
        }

        /**
         * Get microphone enabled state
         */
        AsyncFunction("getMicEnabled") { ->
            try {
                val enabled = agentManager.getMicEnabled()
                return@AsyncFunction enabled
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get mic enabled", e)
                throw e
            }
        }

        /**
         * Get speaker enabled state
         */
        AsyncFunction("getSpeakerEnabled") { ->
            try {
                val enabled = agentManager.getSpeakerEnabled()
                return@AsyncFunction enabled
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get speaker enabled", e)
                throw e
            }
        }

        /**
         * Get room ID
         */
        AsyncFunction("getRoomId") { ->
            try {
                val roomId = agentManager.getRoomId()
                return@AsyncFunction roomId
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get room ID", e)
                throw e
            }
        }

        AsyncFunction("reloadTools") { promise: Promise ->
            scope.launch {
                try {
                    agentManager.reloadTools()
                    promise.resolve(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to reload tools", e)
                    promise.reject("RELOAD_TOOLS_FAILED", e.message, e)
                }
            }
        }

        AsyncFunction("sendFiles") { urls: List<String>, message: String, promise: Promise ->
            scope.launch {
                try {
                    agentManager.sendFiles(urls, message)
                    promise.resolve(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send files", e)
                    promise.reject("SEND_FILES_FAILED", e.message, e)
                }
            }
        }

        AsyncFunction("performRpc") { method: String, payload: String, promise: Promise ->
            scope.launch {
                try {
                    val result = agentManager.performRpc(method, payload)
                    promise.resolve(result)
                } catch (e: Exception) {
                    promise.reject("PERFORM_RPC_FAILED", e.message ?: "Unknown error", e)
                }
            }
        }

        AsyncFunction("registerRpcMethod") { method: String, promise: Promise ->
            scope.launch {
                try {
                    agentManager.registerRpcMethod(method)
                    promise.resolve(null)
                } catch (e: Exception) {
                    promise.reject("REGISTER_RPC_METHOD_FAILED", e.message ?: "Unknown error", e)
                }
            }
        }

        /**
         * Start an app session
         */
        AsyncFunction("startApp") { appId: String, promise: Promise ->
            scope.launch {
                try {
                    Log.d(TAG, "Starting app: $appId")
                    agentManager.startApp(appId)
                    promise.resolve(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start app", e)
                    promise.reject("START_APP_FAILED", e.message, e)
                }
            }
        }

        /**
         * Set authentication cookie for API requests
         */
        AsyncFunction("setCookie") { cookie: String, promise: Promise ->
            try {
                Log.d(TAG, "Setting cookie")
                agentManager.setCookie(cookie)
                promise.resolve(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set cookie", e)
                promise.reject("SET_COOKIE_FAILED", e.message, e)
            }
        }

        AsyncFunction("startPhoneStream") { fps: Int, bitrate: Int, promise: Promise ->
            scope.launch {
                try {
                    agentManager.startPhoneStream(fps, bitrate)
                    promise.resolve(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start phone stream", e)
                    promise.reject("START_PHONE_STREAM_FAILED", e.message, e)
                }
            }
        }

        AsyncFunction("stopPhoneStream") { promise: Promise ->
            scope.launch {
                try {
                    agentManager.stopPhoneStream()
                    promise.resolve(null)
                } catch (e: Exception) {
                    promise.reject("STOP_PHONE_STREAM_FAILED", e.message, e)
                }
            }
        }

        AsyncFunction("flipPhoneCamera") { promise: Promise ->
            try {
                agentManager.flipPhoneCamera()
                promise.resolve(null)
            } catch (e: Exception) {
                promise.reject("FLIP_PHONE_CAMERA_FAILED", e.message, e)
            }
        }

        AsyncFunction("isPhoneStreaming") { promise: Promise ->
            promise.resolve(agentManager.isPhoneStreaming())
        }

        /**
         * Set agent name for token dispatch (empty string = use default)
         */
        AsyncFunction("startGlassStream") { ssid: String, pwd: String, fps: Int, bitrate: Int, promise: Promise ->
            scope.launch {
                try {
                    agentManager.startGlassStream(ssid, pwd, fps, bitrate)
                    promise.resolve(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start glass stream", e)
                    promise.reject("START_GLASS_STREAM_FAILED", e.message, e)
                }
            }
        }

        AsyncFunction("stopGlassStream") { promise: Promise ->
            try {
                agentManager.stopGlassStream()
                promise.resolve(null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop glass stream", e)
                promise.reject("STOP_GLASS_STREAM_FAILED", e.message, e)
            }
        }

        AsyncFunction("isGlassStreaming") { promise: Promise ->
            promise.resolve(agentManager.isGlassStreaming())
        }

        AsyncFunction("setAgentName") { name: String, promise: Promise ->
            try {
                Log.d(TAG, "Setting agent name: $name")
                agentManager.setAgentName(if (name.isEmpty()) null else name)
                promise.resolve(null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set agent name", e)
                promise.reject("SET_AGENT_NAME_FAILED", e.message, e)
            }
        }
    }

    /**
     * Subscribe to Glass events from EventBus — handles voice wakeup to manage audio session
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onGlassEvent(event: GlassEventMsg) {
        when (event.cmd) {
            GlassEventConstants.MSG_GLASS_VOICE_WAKEUP -> handleVoiceWakeup()
            GlassEventConstants.MSG_GLASS_VOICE_STOP -> handleVoiceWakeupEnd()
            GlassEventConstants.MSG_GLASS_CONNECTED,
            GlassEventConstants.MSG_GLASS_DISCONNECTED -> agentManager.syncGlassAiStatus()
        }
    }

    /**
     * On voice wakeup: enable audio if agent is connected but audio is off,
     * then schedule auto-disable after WAKEUP_AUDIO_TIMEOUT_MS of inactivity.
     */
    private fun handleVoiceWakeup() {
        Log.d(TAG, "Voice wakeup received")
        val state = agentManager.getConnectionState()
        if (state != ConnectionState.CONNECTED) {
            Log.d(TAG, "Agent not connected — ignoring voice wakeup")
            return
        }

        // Echo guard: a phone-initiated voice session pushes speaking_start to the glasses, which can
        // make the device echo aiDialogue=true → a spurious wakeup. Ignore it when voice is already on
        // and we're not in a device-initiated wakeup session.
        // Idempotent guard: ignore the wakeup whenever voice is already on — covers repeated
        // aiDialogue=true frames and the speaking_start echo, so we never re-send "hey" / re-enable.
        if (agentManager.getMicEnabled()) {
            Log.d(TAG, "Voice already active — ignoring wakeup")
            return
        }

        // Send text first for lower latency
        scope.launch {
            try {
                agentManager.sendText("hey")
            } catch (e: Exception) {
                Log.w(TAG, "Could not send wakeup message", e)
            }
        }

        if (!agentManager.getMicEnabled()) {
            Log.i(TAG, "Voice wakeup: enabling audio")
            agentManager.setAudioEnabled(true)
        }

        isWakeupSessionActive = true
        // Inactivity auto-off handled by backend (exit_voice_mode) — app-side timer disabled.
        // resetWakeupTimer()
    }

    /**
     * Glasses' button turned voice mode OFF (device AI-dialogue falling edge) → turn off the
     * phone-side voice and cancel the wakeup inactivity timer.
     */
    private fun handleVoiceWakeupEnd() {
        Log.d(TAG, "Voice wakeup end received")
        wakeupInactivityJob?.cancel()
        wakeupInactivityJob = null
        isWakeupSessionActive = false
        if (agentManager.getMicEnabled()) {
            agentManager.setAudioEnabled(false)
        }
    }

    /**
     * Restart the inactivity countdown without re-enabling audio.
     * Called on initial wakeup and whenever conversation activity is detected.
     */
    private fun resetWakeupTimer() {
        wakeupInactivityJob?.cancel()
        wakeupInactivityJob = scope.launch {
            delay(WAKEUP_AUDIO_TIMEOUT_MS)
            Log.i(TAG, "Voice wakeup session timed out after inactivity — disabling audio")
            agentManager.setAudioEnabled(false)
            isWakeupSessionActive = false
            wakeupInactivityJob = null
        }
    }

    /**
     * Subscribe to Agent events from EventBus.
     * Also resets the wakeup inactivity timer when conversation activity is detected.
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onAgentEvent(event: AgentEventMsg) {
        Log.i(TAG, "onAgentEVentxx ${event}")

        // Inactivity auto-off handled by backend (exit_voice_mode) — app-side activity tracking disabled.
        // if (isWakeupSessionActive) {
        //     when (event.cmd) {
        //         AgentEventConstants.MSG_AGENT_ACTIVE_SPEAKERS -> {
        //             val speakers = event.s1
        //             if (!speakers.isNullOrEmpty() && speakers != "[]") {
        //                 Log.d(TAG, "Wakeup: activity detected (active speakers) — resetting timer")
        //                 resetWakeupTimer()
        //             }
        //         }
        //         AgentEventConstants.MSG_AGENT_TEXT_EVENT -> {
        //             Log.d(TAG, "Wakeup: activity detected (transcription) — resetting timer")
        //             resetWakeupTimer()
        //         }
        //     }
        // }

        emitEvent(event)
    }

    /**
     * Emit event to JavaScript
     */
    private fun emitEvent(event: AgentEventMsg) {
        Log.i(TAG, "eventxxxx ${event}")
        try {
            val eventData = Bundle()

            // Map based on event type
            when (event.cmd) {
                AgentEventConstants.MSG_AGENT_STATE_CHANGED -> {
                    eventData.putString("state", event.s1)
                    sendEvent("AGENT_STATE_CHANGED", eventData)
                }

                AgentEventConstants.MSG_AGENT_PARTICIPANT_EVENT -> {
                    eventData.putString("type", event.s1)
                    eventData.putString("participantId", event.s2)
                    event.s3?.let { eventData.putString("identity", it) }
                    event.s4?.let { eventData.putString("name", it) }
                    eventData.putBoolean("isSpeaking", event.n1 == 1)
                    eventData.putBoolean("isAgent", event.n2 == 1)
                    sendEvent("AGENT_PARTICIPANT_EVENT", eventData)
                }

                AgentEventConstants.MSG_AGENT_AUDIO_TRACK_EVENT -> {
                    eventData.putString("type", event.s1)
                    eventData.putString("participantId", event.s2)
                    eventData.putString("trackId", event.s3 ?: "")
                    eventData.putBoolean("isLocal", event.n1 == 1)
                    eventData.putBoolean("isMuted", event.n2 == 1)
                    sendEvent("AGENT_AUDIO_TRACK_EVENT", eventData)
                }

                AgentEventConstants.MSG_AGENT_TEXT_EVENT -> {
                    eventData.putString("id", event.s1)
                    eventData.putString("text", event.s2)
                    eventData.putBoolean("isFinal", event.n1 == 1)
                    eventData.putBoolean("isUser", event.n2 == 1)
                    eventData.putBoolean("isTranscription", event.n3 == 1)
                    event.s3?.let { eventData.putString("segmentId", it) }
                    sendEvent("AGENT_TEXT_EVENT", eventData)
                }

                AgentEventConstants.MSG_AGENT_DATA_EVENT -> {
                    eventData.putString("participantId", event.s1)
                    eventData.putString("data", event.s2) // Base64 encoded
                    event.s3?.let { eventData.putString("topic", it) }
                    sendEvent("AGENT_DATA_EVENT", eventData)
                }

                AgentEventConstants.MSG_AGENT_ERROR -> {
                    eventData.putString("code", event.s1)
                    eventData.putString("message", event.s2)
                    sendEvent("AGENT_ERROR", eventData)
                }

                AgentEventConstants.MSG_AGENT_TOOL_CALL -> {
                    eventData.putString("toolName", event.s1)
                    eventData.putString("params", event.s2)
                    sendEvent("AGENT_TOOL_CALL", eventData)
                }

                AgentEventConstants.MSG_AGENT_TOOL_RESULT -> {
                    eventData.putString("toolName", event.s1)
                    eventData.putBoolean("success", event.n1 == 1)
                    eventData.putString("result", event.s2)
                    sendEvent("AGENT_TOOL_RESULT", eventData)
                }

                AgentEventConstants.MSG_AGENT_ACTIVE_SPEAKERS -> {
                    eventData.putString("speakers", event.s1)
                    sendEvent("AGENT_ACTIVE_SPEAKERS", eventData)
                }

                AgentEventConstants.MSG_AGENT_TOOL_EXECUTED -> {
                    eventData.putString("data", event.s1)
                    sendEvent("AGENT_TOOL_EXECUTED", eventData)
                }

                AgentEventConstants.MSG_AGENT_RPC_METHOD_CALLED -> {
                    eventData.putString("method", event.s1)
                    eventData.putString("payload", event.s2)
                    sendEvent("AGENT_RPC_METHOD_CALLED", eventData)
                }
            }

            Log.d(TAG, "Emitted Agent event: ${event.cmd}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to emit Agent event: ${event.cmd}", e)
        }
    }
}
