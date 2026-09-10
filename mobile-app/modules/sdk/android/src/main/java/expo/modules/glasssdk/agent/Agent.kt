package expo.modules.glasssdk.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import androidx.core.content.ContextCompat
import expo.modules.glasssdk.tools.ToolManager
import expo.modules.glasssdk.glass.GlassManager
import expo.modules.glasssdk.network.ApiClient
import expo.modules.glasssdk.util.AppLog
import expo.modules.glasssdk.util.FileUtil
import io.livekit.android.AudioOptions
import io.livekit.android.AudioType
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import livekit.org.webrtc.audio.JavaAudioDeviceModule
import io.livekit.android.RoomOptions
import io.livekit.android.annotations.Beta
import io.livekit.android.audio.AudioProcessorOptions
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.datastream.StreamBytesOptions
import io.livekit.android.room.datastream.StreamTextOptions
import io.livekit.android.room.participant.AudioPresets
import io.livekit.android.room.participant.AudioTrackPublishDefaults
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.participant.isAgent
import io.livekit.android.room.track.DataPublishReliability
import expo.modules.glasssdk.stream.GlassVideoCapturer
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackPublication
import io.livekit.android.room.types.TranscriptionSegment
import io.livekit.audio.krisp.KrispAudioProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.greenrobot.eventbus.EventBus
import org.json.JSONObject
import java.io.File
import java.util.Collections
import kotlin.coroutines.resume
import io.sentry.Sentry;

class Agent(private val context: Context) {
    private companion object {
        const val AUDIO_TAG = "AgentAudio"

        // Flip to true to log audio device/mode/routing transitions (adb logcat | grep AgentAudio).
        // Off by default to keep production logs quiet. Non-const so guards don't trip
        // "unreachable code" warnings.
        val AUDIO_DEBUG = false
    }

    private var room: Room? = null
    private var localAudioTrack: LocalAudioTrack? = null
    private var localVideoTrack: LocalVideoTrack? = null
    private var glassVideoCapturer: GlassVideoCapturer? = null
    // If startGlassVideoStream is called before the room is connected,
    // store the params and publish as soon as we connect.
    private var pendingGlassStream: Pair<String, Int>? = null
    // Renderers registered before the track exists; attached when the track is created.
    private val pendingRenderers = mutableListOf<TextureViewRenderer>()
    // Remote video track from the WHIP ingress participant (identity ends with "-ingress")
    private var ingressVideoTrack: RemoteVideoTrack? = null
    // Token response cached from the initial start() call — reused for WHIP ingress credentials
    private var cachedTokenResponse: TokenResponse? = null
    private var toolManager: ToolManager? = null


    private var connectionState: ConnectionState = ConnectionState.DISCONNECTED
        set(value) {
            val oldState = field
            field = value
            AppLog.i("VoiceAgent state transition: $oldState -> $value")
        }

    private var isAudioEnabled: Boolean = true
    private var isMicEnabled: Boolean = true
    private var isSpeakerEnabled: Boolean = true
    private var isBtScoActive = false
    private var agentName: String? = null
    // Tracks whether we've told the glasses to enter AI-reply mode (deduped edge state).
    private var glassAiActive = false

    // Whether we currently hold the audio session (audio focus, plus MODE_IN_COMMUNICATION + SCO
    // routing on the BT path). We own this lifecycle ourselves (LiveKit runs with NoAudioHandler)
    // so the device leaves call mode when audio is disabled, even while the room stays connected.
    private var audioSessionActive = false
    private var audioFocusRequest: AudioFocusRequest? = null

    // Debug instrumentation — logs audio device add/remove, mode changes, and comm-device changes.
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var modeChangedListener: AudioManager.OnModeChangedListener? = null
    private var commDeviceChangedListener: AudioManager.OnCommunicationDeviceChangedListener? = null

    // Monotonic timestamp captured when start() is called — used to log connect-phase latency.
    private var startTimeMs: Long = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var eventCollectionJob: Job? = null
    private val registeredRpcMethods = Collections.synchronizedSet(mutableSetOf<String>())

    private val krisp = KrispAudioProcessor.getInstance(context.applicationContext)


    /**
     * Start the voice agent - fetches token from backend and connects to LiveKit
     */
    fun start(audio: Boolean = true, agentName: String? = null) {
        this.isAudioEnabled = audio
        this.agentName = agentName

        // Reset all mutable state to defaults before starting
        this.isMicEnabled = audio
        this.isSpeakerEnabled = audio
        this.isBtScoActive = false
        this.pendingGlassStream = null

        startTimeMs = SystemClock.elapsedRealtime()

        setupAudio()

        scope.launch {
            try {
                connectionState = ConnectionState.CONNECTING
                postEvent(VoiceAgentConnectionEvent(VoiceAgentConnectionState.CONNECTING))


                val tokenStart = SystemClock.elapsedRealtime()
                val tokenData = getTokenFromBackend()
                AppLog.i("[Latency] token fetch took ${SystemClock.elapsedRealtime() - tokenStart}ms (+${SystemClock.elapsedRealtime() - startTimeMs}ms since start)")
                cachedTokenResponse = tokenData

                if (tokenData == null) {
                    connectionState = ConnectionState.FAILED
                    postEvent(
                        VoiceAgentErrorEvent(
                            "TOKEN_FETCH_FAILED",
                            "Failed to fetch token from backend"
                        )
                    )
                    return@launch
                }

                // Continue with connection using fetched token
                startConnection(tokenData.url, tokenData.token)

            } catch (e: Exception) {
                AppLog.e("Failed to start VoiceAgent", e)
                connectionState = ConnectionState.FAILED
                postEvent(VoiceAgentErrorEvent("START_FAILED", e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * Get token from backend API using ApiClient (with callback converted to suspend)
     */
    private suspend fun getTokenFromBackend(): TokenResponse? {
        val endpoint = if (agentName != null)
            "/chat/agent/token?agentName=${android.net.Uri.encode(agentName)}"
        else
            "/chat/agent/token"
        return suspendCancellableCoroutine { continuation ->
            ApiClient.getInstance()?.get<TokenResponse>(
                endpoint,
                object : expo.modules.glasssdk.network.ApiCallback<TokenResponse> {
                    override fun onSuccess(data: TokenResponse) {
                        AppLog.i("Token received($agentName): ${data.token} ${data.streamKey}")
                        Sentry.logger().info("Token received($agentName): ${data.token} ${data.streamKey}");
                        continuation.resume(data)
                    }

                    override fun onError(error: expo.modules.glasssdk.network.ApiError) {
                        AppLog.e("Failed to get token: ${error.message}")
                        continuation.resume(null)
                    }
                })
        }
    }

    /** Returns WHIP ingress credentials from the token fetched at start() — same room. */
    suspend fun fetchStreamCredentials(): Pair<String, String> {
        val resp = cachedTokenResponse
            ?: throw Exception("Agent not started — no token cached")
        val ingressUrl = resp.ingressUrl
            ?: throw Exception("No ingressUrl in token response")
        val streamKey = resp.streamKey
            ?: throw Exception("No streamKey in token response")
        return Pair(ingressUrl, streamKey)
    }

    /**
     * Start connection with provided URL and token
     */
    private suspend fun startConnection(url: String, token: String) {
        if (connectionState != ConnectionState.DISCONNECTED && connectionState != ConnectionState.CONNECTING) {
            AppLog.w("VoiceAgent already started (state: $connectionState), stopping previous instance first")
            stop()
            delay(500)
        }

        if (!checkPermissions()) {
            connectionState = ConnectionState.FAILED
            return
        }

        try {
            // Clean up any existing room
            room?.let {
                try {
                    it.disconnect()
                    it.release()
                } catch (e: Exception) {
                    AppLog.e("Error cleaning up existing room", e)
                }
            }

            // Always use the MEDIA playout stream. CallAudioType bakes a voice-call playout track
            // into the room for its whole life, which keeps the device looking "in call" even after
            // we restore MODE_NORMAL. MediaAudioType avoids that. SCO mic routing for a BT headset
            // is handled at runtime in enterAudioSession via setCommunicationDevice — no need to
            // bake the call stream here.
            val outputType = AudioType.MediaAudioType()

            room = LiveKit.create(
                appContext = context.applicationContext,
                options = RoomOptions(
                    audioTrackPublishDefaults = AudioTrackPublishDefaults(
                        preconnect = true,
                        audioBitrate = AudioPresets.MUSIC_HIGH_QUALITY_STEREO.maxBitrate,
                    )
                ),
                overrides = LiveKitOverrides(
                    audioOptions = AudioOptions(
                        // We own the audio-session lifecycle (enter/exitAudioSession) so the
                        // device can leave MODE_IN_COMMUNICATION while idle-but-connected.
                        // NoAudioHandler stops LiveKit from forcing call mode/focus/routing, and
                        // disabling the workaround stops the silent voice-call track that pins it.
                        audioHandler = NoAudioHandler(),
                        disableCommunicationModeWorkaround = true,
                        audioOutputType = outputType,
                        audioProcessorOptions = AudioProcessorOptions(
                            capturePostProcessor = krisp,
                        ),
                        javaAudioDeviceModuleCustomizer = { builder ->
                            if (isBtScoActive) {
                                // Hardware AEC can't cancel BT SCO echo (mic and speaker are on
                                // different physical paths). Software AEC uses WebRTC's internal
                                // playout buffer as reference, which works regardless of routing.
                                builder.setUseHardwareAcousticEchoCanceler(false)
                                builder.setUseHardwareNoiseSuppressor(false)
                            }
                        }
                    )
                )
            )

            AppLog.i("LiveKit room created")

            setupEventListeners()

            ToolManager.getInstance()?.registerWithLiveKit(room!!)

            room?.registerRpcMethod("tool_executed") { data ->
                postEvent(VoiceAgentToolExecutedEvent(data.payload))
                "{}"
            }
            AppLog.i("Registered RPC method 'tool_executed'")

            // Native handler so the voice agent can stop voice mode even when JS is dead
            // (backgrounded/killed). Directly disables audio: releases mic, mutes speaker,
            // tears down the audio session, and exits glasses AI-reply mode.
            room?.registerRpcMethod("exit_voice") { _ ->
                AppLog.i("RPC 'exit_voice' → disabling audio")
                setAudioEnabled(false)
                "{}"
            }
            AppLog.i("Registered RPC method 'exit_voice'")

            registeredRpcMethods.forEach { method ->
                room?.registerRpcMethod(method) { data ->
                    postEvent(VoiceAgentRpcMethodCalledEvent(method, data.payload))
                    "{}"
                }
            }

            AppLog.i("Connecting to LiveKit room: $url")

            val connectStart = SystemClock.elapsedRealtime()
            room?.connect(url, token)
            AppLog.i("[Latency] room.connect() took ${SystemClock.elapsedRealtime() - connectStart}ms (+${SystemClock.elapsedRealtime() - startTimeMs}ms since start)")

            if (this.isAudioEnabled) {
                val micStart = SystemClock.elapsedRealtime()
                acquireMicTrack()
                AppLog.i("[Latency] mic publish took ${SystemClock.elapsedRealtime() - micStart}ms (+${SystemClock.elapsedRealtime() - startTimeMs}ms since start)")
            }


        } catch (e: Exception) {
            AppLog.e("Failed to start VoiceAgent", e)
            connectionState = ConnectionState.FAILED
            postEvent(VoiceAgentErrorEvent("CONNECTION_FAILED", e.message ?: "Unknown error"))
            cleanup()
        }
    }

    fun stop() {
        scope.launch {
            try {
                AppLog.i("Stopping VoiceAgent")

                // Unpublish local tracks (stop capturing before tearing down the audio session)
                localAudioTrack?.let { track ->
                    try {
                        room?.localParticipant?.unpublishTrack(track)
                        track.stop()
                        AppLog.i("Local audio track unpublished")
                    } catch (e: Exception) {
                        AppLog.e("Error unpublishing track", e)
                    }
                }
                localAudioTrack = null

                // Restore MODE_NORMAL / abandon focus / stop SCO — NoAudioHandler won't do it on disconnect.
                exitAudioSession()

                // Disconnect from room
                room?.disconnect()

                postEvent(VoiceAgentConnectionEvent(VoiceAgentConnectionState.DISCONNECTED))

            } catch (e: Exception) {
                AppLog.e("Error during stop", e)
            } finally {
                cleanup()
            }
        }
    }


    fun setMicEnabled(enabled: Boolean) {
        isMicEnabled = enabled

        scope.launch {
            room?.localParticipant?.setMicrophoneEnabled(enabled)

            postEvent(
                VoiceAgentAudioTrackEvent(
                    type = if (enabled) "unmuted" else "muted",
                    participantId = room?.localParticipant?.sid?.value ?: "",
                    trackId = "",
                    isLocal = true,
                    isMuted = !enabled
                )
            )
        }
    }


    fun setSpeakerEnabled(enabled: Boolean) {

        isSpeakerEnabled = enabled

        room?.setSpeakerMute(!enabled)

        postEvent(
            VoiceAgentAudioTrackEvent(
                type = if (enabled) "speaker_enabled" else "speaker_disabled",
                participantId = "",
                trackId = "",
                isLocal = false,
                isMuted = !enabled
            )
        )

        AppLog.i("Speaker ${if (enabled) "enabled" else "disabled"}")
    }


    fun getMicEnabled(): Boolean = isMicEnabled


    fun getSpeakerEnabled(): Boolean = isSpeakerEnabled


    fun getConnectionState(): ConnectionState = connectionState

    private fun getRemoteParticipant(): RemoteParticipant? {
        val size = room?.remoteParticipants?.size ?: 0

        if (size > 0) {
            return room!!.remoteParticipants.entries.first().value
        } else {
            return null
        }
    }

    fun sendText(text: String) {
        scope.launch {
            room?.localParticipant?.sendText(
                text, StreamTextOptions(
                    topic = "lk.chat"
                )
            )
        }
    }

    fun sendFile(path: String) {
        scope.launch {
            val file = File(path)
            val mimeType = FileUtil.detectContentType(file);

            room?.localParticipant?.sendFile(file, StreamBytesOptions(topic = "files",  name = file.name, mimeType = mimeType))
        }
    }

    /** Truly releases WebRTC's AudioRecord by unpublishing the mic track. */
    private suspend fun releaseMicTrack() {
        val lp = room?.localParticipant ?: return
        val pub = lp.getTrackPublication(Track.Source.MICROPHONE)
        val track = pub?.track as? LocalAudioTrack
        if (track != null) {
            try {
                // stopOnUnpublish defaults to true → track.stop() releases the AudioRecord,
                // freeing the mic for other apps while the agent stays connected.
                lp.unpublishTrack(track)
                AppLog.i("Mic track unpublished + stopped (AudioRecord released)")
            } catch (e: Exception) {
                AppLog.e("Error releasing mic track", e)
            }
        } else {
            // Nothing published; keep muted state consistent
            lp.setMicrophoneEnabled(false)
        }
        localAudioTrack = null
        // Drop call mode so the device leaves "in call" state and other apps can use the mic.
        exitAudioSession()
    }

    /** Re-publishes a fresh mic track (re-acquires the AudioRecord). */
    private suspend fun acquireMicTrack() {
        val lp = room?.localParticipant ?: return
        // Enter the audio session before capturing so routing/AEC are correct from the first frame.
        enterAudioSession()
        try {
            lp.setMicrophoneEnabled(true)
            localAudioTrack = lp.getTrackPublication(Track.Source.MICROPHONE)?.track as? LocalAudioTrack
            AppLog.i("Mic track acquired")
        } catch (e: Exception) {
            AppLog.e("Error acquiring mic track", e)
        }
    }

    // Acquire the audio session for active conversation. Idempotent.
    // We re-detect a BT-SCO mic HERE (devices can connect after start(), e.g. the glasses), so the
    // decision isn't stale. SCO mic present → MODE_IN_COMMUNICATION + route to the SCO device (its
    // mic + speaker, like a normal call → BT volume, not the earpiece call slider). No SCO → leave
    // MODE_NORMAL + phone mic; media output auto-routes to A2DP (glasses) on its own.
    private fun enterAudioSession() {
        if (audioSessionActive) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val scoDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            else null
            val hasLegacySco = Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
                am.getDevices(AudioManager.GET_DEVICES_INPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            isBtScoActive = scoDevice != null || hasLegacySco

            if (isBtScoActive) {
                // Capture the BT (glasses/headset) mic — needs communication mode + SCO routing.
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                requestAudioFocus(am, voiceComm = true)
                if (scoDevice != null) {
                    val ok = am.setCommunicationDevice(scoDevice)
                    AppLog.i(AUDIO_TAG, "setCommunicationDevice(${describe(scoDevice)}) -> $ok")
                } else {
                    @Suppress("DEPRECATION")
                    run { am.startBluetoothSco(); am.isBluetoothScoOn = true }
                }
            } else {
                // No BT mic: phone mic + MODE_NORMAL (no call UI); media output auto-routes to A2DP.
                requestAudioFocus(am, voiceComm = false)
            }
            // Only mark active after a successful entry, so a failed entry can be retried and a
            // later exitAudioSession() isn't fooled into tearing down state that was never set.
            audioSessionActive = true
            AppLog.i(AUDIO_TAG, "Entered audio session (btSco=$isBtScoActive)")
            dumpAudioState("enter")
        } catch (e: Exception) {
            AppLog.e(AUDIO_TAG, "enterAudioSession failed", e)
        }
    }

    // Release the audio session: stop SCO + restore MODE_NORMAL (BT path only), abandon focus.
    // Idempotent. Called when audio is disabled, and on stop()/cleanup() — NoAudioHandler won't
    // do it for us. Does NOT clear isBtScoActive (the session's chosen path, reset in cleanup()).
    private fun exitAudioSession() {
        if (!audioSessionActive) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            // Always leave communication mode when audio is disabled — the device must NOT stay
            // "in call" while idle, no matter how it got there (BT dropped mid-session, abrupt SCO
            // teardown, etc.). These calls are safe no-ops when nothing is routed.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                if (am.isBluetoothScoOn) {
                    am.isBluetoothScoOn = false
                    am.stopBluetoothSco()
                }
            }
            am.mode = AudioManager.MODE_NORMAL
            abandonAudioFocus(am)
            AppLog.i(AUDIO_TAG, "Released audio session (mode=NORMAL)")
            dumpAudioState("exit")
        } catch (e: Exception) {
            AppLog.e(AUDIO_TAG, "exitAudioSession failed", e)
        }
        audioSessionActive = false
    }

    private fun requestAudioFocus(am: AudioManager, voiceComm: Boolean) {
        val usage = if (voiceComm) AudioAttributes.USAGE_VOICE_COMMUNICATION
                    else AudioAttributes.USAGE_MEDIA
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { }
            .build()
        audioFocusRequest = req
        val result = am.requestAudioFocus(req)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            AppLog.w(AUDIO_TAG, "Audio focus not granted (result=$result)")
        }
    }

    private fun abandonAudioFocus(am: AudioManager) {
        audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }

    // ---- Audio debug instrumentation ----------------------------------------------------------
    // Logs the live audio device list, the current AudioManager mode/routing, and reacts to
    // device/mode/comm-device changes. Use `adb logcat | grep AgentAudio` to watch transitions.

    private fun audioModeName(mode: Int) = when (mode) {
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        AudioManager.MODE_CALL_SCREENING -> "CALL_SCREENING"
        else -> "MODE($mode)"
    }

    private fun deviceTypeName(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        else -> "TYPE($type)"
    }

    private fun describe(d: AudioDeviceInfo): String {
        val dir = (if (d.isSource) "in" else "") + (if (d.isSink) "out" else "")
        return "${deviceTypeName(d.type)}(${d.productName}/$dir)"
    }

    /** Dumps the current audio mode + routing + device lists. `label` marks where it was called. */
    private fun dumpAudioState(label: String) {
        if (!AUDIO_DEBUG) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val sb = StringBuilder()
            sb.append("mode=").append(audioModeName(am.mode))
            @Suppress("DEPRECATION")
            sb.append(" scoOn=").append(am.isBluetoothScoOn)
            @Suppress("DEPRECATION")
            sb.append(" speakerOn=").append(am.isSpeakerphoneOn)
            sb.append(" isBtScoActive=").append(isBtScoActive)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cd = am.communicationDevice
                sb.append(" commDevice=").append(cd?.let { describe(it) } ?: "null")
                sb.append(" availComm=[")
                    .append(am.availableCommunicationDevices.joinToString { deviceTypeName(it.type) })
                    .append("]")
            }
            sb.append(" outputs=[")
                .append(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString { deviceTypeName(it.type) })
                .append("]")
            sb.append(" inputs=[")
                .append(am.getDevices(AudioManager.GET_DEVICES_INPUTS).joinToString { deviceTypeName(it.type) })
                .append("]")
            AppLog.i(AUDIO_TAG, "[AudioState:$label] $sb")
        } catch (e: Exception) {
            AppLog.e(AUDIO_TAG, "dumpAudioState failed", e)
        }
    }

    private fun registerAudioDebug() {
        if (!AUDIO_DEBUG) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val cb = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                    AppLog.i(AUDIO_TAG, "[AudioDevices] ADDED: ${added.joinToString { describe(it) }}")
                    dumpAudioState("devicesAdded")
                }
                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                    AppLog.i(AUDIO_TAG, "[AudioDevices] REMOVED: ${removed.joinToString { describe(it) }}")
                    dumpAudioState("devicesRemoved")
                }
            }
            am.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
            audioDeviceCallback = cb

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val ml = AudioManager.OnModeChangedListener { mode ->
                    AppLog.i(AUDIO_TAG, "[AudioMode] -> ${audioModeName(mode)}")
                }
                am.addOnModeChangedListener(context.mainExecutor, ml)
                modeChangedListener = ml

                val cdl = AudioManager.OnCommunicationDeviceChangedListener { device ->
                    AppLog.i(AUDIO_TAG, "[CommDevice] -> ${device?.let { describe(it) } ?: "null"}")
                }
                am.addOnCommunicationDeviceChangedListener(context.mainExecutor, cdl)
                commDeviceChangedListener = cdl
            }
            dumpAudioState("registerDebug")
        } catch (e: Exception) {
            AppLog.e(AUDIO_TAG, "registerAudioDebug failed", e)
        }
    }

    private fun unregisterAudioDebug() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioDeviceCallback?.let { am.unregisterAudioDeviceCallback(it) }
            audioDeviceCallback = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                modeChangedListener?.let { am.removeOnModeChangedListener(it) }
                modeChangedListener = null
                commDeviceChangedListener?.let { am.removeOnCommunicationDeviceChangedListener(it) }
                commDeviceChangedListener = null
            }
        } catch (e: Exception) {
            AppLog.e(AUDIO_TAG, "unregisterAudioDebug failed", e)
        }
    }

    fun setAudioEnabled(enabled: Boolean) {
        isAudioEnabled = enabled
        isMicEnabled = enabled

        scope.launch {
            if (enabled) acquireMicTrack() else releaseMicTrack()

            postEvent(
                VoiceAgentAudioTrackEvent(
                    type = if (enabled) "unmuted" else "muted",
                    participantId = room?.localParticipant?.sid?.value ?: "",
                    trackId = "",
                    isLocal = true,
                    isMuted = !enabled
                )
            )

            setSpeakerEnabled(enabled)
            syncGlassAiStatus()   // tell the glasses to enter/exit AI-reply mode
        }
    }

    /**
     * Keep the glasses' on-device AI-reply mode in sync with whether the agent's voice is on.
     * Deduped (one edge state) and gated on the glasses being connected. Adds a setAiStatus call only
     * — does not touch the audio session/routing. Public so AgentModule can re-fire it on glasses
     * (re)connect (the SCO-induced BLE blip can otherwise drop the speaking_start).
     */
    fun syncGlassAiStatus() {
        val active = isAudioEnabled && GlassManager.getInstance(context).isConnected()
        if (active == glassAiActive) return
        glassAiActive = active
        val status = if (active) "speaking_start" else "speaking_stop"
        AppLog.i("glass AI mode → $active ($status)")
        scope.launch { runCatching { GlassManager.getInstance(context).setAiStatus(status) } }
    }

    fun toggleAudio() {
        setAudioEnabled(!isAudioEnabled)
    }

    fun getRoomId(): String? {
        return room?.name
    }

    fun reloadTools() {
        scope.launch {
            val participant = getRemoteParticipant()
            if (participant != null) {
                room?.localParticipant?.performRpc(
                    participant.identity!!,
                    "reload_tools",
                    "{}"
                )
            }
        }
    }

    fun startApp(appId: String) {
        scope.launch {
            val json = JSONObject().apply {
                put("appId", appId)
            }

            val participant = getRemoteParticipant();

            if (participant != null) {
                room?.localParticipant?.performRpc(
                    participant.identity!!,
                    "app.session.start",
                    json.toString()
                )
            };
        }
    }

    fun sendFiles(urls: List<String>, message: String) {
        scope.launch {
            val participant = getRemoteParticipant()
            if (participant != null) {
                val json = JSONObject().apply {
                    put("urls", org.json.JSONArray(urls))
                    put("message", message)
                }
                room?.localParticipant?.performRpc(
                    participant.identity!!,
                    "send_files",
                    json.toString()
                )
            }
        }
    }

    suspend fun performRpc(method: String, payload: String): String {
        val participant = getRemoteParticipant()
            ?: throw Exception("No agent participant")
        return room?.localParticipant?.performRpc(
            participant.identity!!,
            method,
            payload
        ) ?: "{}"
    }

    fun registerRpcMethod(methodName: String) {
        registeredRpcMethods.add(methodName)
        room?.registerRpcMethod(methodName) { data ->
            postEvent(VoiceAgentRpcMethodCalledEvent(methodName, data.payload))
            "{}"
        }
    }

    /**
     * Set authentication cookie
     * Stores cookie for use in API requests
     */
    private var authCookie: String? = null

    fun setCookie(cookie: String) {
        AppLog.i("Setting auth cookie ${ApiClient.getInstance()}")
        authCookie = cookie
        ApiClient.getInstance()?.setAuthToken(cookie)
    }

    fun sendData(data: ByteArray, reliable: Boolean = true) {
        scope.launch {
            try {
                val reliability =
                    if (reliable) DataPublishReliability.RELIABLE else DataPublishReliability.LOSSY
                val result = room?.localParticipant?.publishData(data, reliability)
                AppLog.i("Data sent: ${data.size} bytes: ${result.toString()}")
            } catch (e: Exception) {
                AppLog.e("Failed to send data", e)
                postEvent(
                    VoiceAgentErrorEvent(
                        "DATA_SEND_FAILED",
                        e.message ?: "Failed to send data"
                    )
                )
            }
        }
    }


    // Phone camera streaming
    private var phoneCameraTrack: LocalVideoTrack? = null
    // All registered renderers — both those waiting for a track and those already attached.
    // Keeping one list ensures stopPhoneStream removes every renderer, even those added
    // directly to the track (not via the pending path). Without this, setCameraEnabled(false)
    // merely mutes the track (same instance is reused on restart), so a released renderer
    // that was never removed would receive frames again → "not initialized" crash.
    private val phoneRenderers = mutableListOf<TextureViewRenderer>()
    private var phoneStreaming: Boolean = false

    suspend fun startPhoneStream(fps: Int, bitrate: Int) {
        AppLog.i("[PhoneStream] startPhoneStream fps=$fps bitrate=$bitrate")
        val r = room ?: throw Exception("Agent room not ready — start agent first")
        AppLog.i("[PhoneStream] room state=${r.state} localParticipant=${r.localParticipant?.identity}")

        val enableResult = r.localParticipant?.setCameraEnabled(true)
        AppLog.i("[PhoneStream] setCameraEnabled(true) result=$enableResult")

        val pub = r.localParticipant?.getTrackPublication(Track.Source.CAMERA)
        AppLog.i("[PhoneStream] getTrackPublication(CAMERA)=$pub track=${pub?.track} muted=${pub?.muted}")

        val track = pub?.track as? LocalVideoTrack
        AppLog.i("[PhoneStream] LocalVideoTrack=$track")

        phoneCameraTrack = track
        phoneStreaming = true

        if (track == null) {
            AppLog.e("[PhoneStream] track is NULL — camera publication not found after setCameraEnabled")
        } else {
            AppLog.i("[PhoneStream] attaching ${phoneRenderers.size} renderer(s)")
            phoneRenderers.forEach { renderer ->
                r.initVideoRenderer(renderer)
                track.addRenderer(renderer)
                AppLog.i("[PhoneStream] renderer attached: $renderer")
            }
        }
        AppLog.i("[PhoneStream] done — phoneStreaming=$phoneStreaming track=$phoneCameraTrack")
    }

    suspend fun stopPhoneStream() {
        AppLog.i("[PhoneStream] stopPhoneStream track=$phoneCameraTrack renderers=${phoneRenderers.size}")
        phoneCameraTrack?.let { track ->
            phoneRenderers.forEach { track.removeRenderer(it) }
        }
        room?.localParticipant?.setCameraEnabled(false)
        phoneCameraTrack = null
        phoneStreaming = false
        AppLog.i("[PhoneStream] stopPhoneStream done")
    }

    fun flipPhoneCamera() {
        phoneCameraTrack?.switchCamera()
    }

    fun addPhonePreviewRenderer(renderer: TextureViewRenderer) {
        AppLog.i("[PhoneStream] addPhonePreviewRenderer track=$phoneCameraTrack renderers=${phoneRenderers.size}")
        if (!phoneRenderers.contains(renderer)) phoneRenderers.add(renderer)
        phoneCameraTrack?.addRenderer(renderer)
    }

    fun removePhonePreviewRenderer(renderer: TextureViewRenderer) {
        AppLog.i("[PhoneStream] removePhonePreviewRenderer track=$phoneCameraTrack")
        phoneRenderers.remove(renderer)
        phoneCameraTrack?.removeRenderer(renderer)
    }

    fun isPhoneStreaming(): Boolean = phoneStreaming

    fun getRoom(): Room? = room

    /**
     * Publish the glass camera feed as a video track in the LiveKit room.
     * Called automatically by K900Adapter when the stream connects.
     * If the room isn't connected yet, the params are stored and the track
     * is published automatically once the room connects.
     */
    fun startGlassVideoStream(host: String, port: Int) {
        pendingGlassStream = Pair(host, port)
        scope.launch {
            val r = room
            if (r == null || connectionState != ConnectionState.CONNECTED) {
                AppLog.i("Room not ready — glass video will publish on connect (host=$host port=$port)")
                return@launch
            }
            publishGlassVideoTrack(r, host, port)
        }
    }

    private suspend fun publishGlassVideoTrack(room: Room, host: String, port: Int) {
        try {
            // Tear down any existing glass track first
            localVideoTrack?.let { room.localParticipant?.unpublishTrack(it); it.stop() }
            glassVideoCapturer?.dispose()

            val capturer = GlassVideoCapturer(host, port, context)
            val track = room.localParticipant?.createVideoTrack("glass_camera", capturer)
                ?: run { AppLog.w("localParticipant null — cannot create glass track"); return }
            // createVideoTrack only calls initialize(); we must start the capturer explicitly.
            track.start()
            track.startCapture()
            room.localParticipant?.publishVideoTrack(track)
            localVideoTrack = track
            glassVideoCapturer = capturer
            pendingGlassStream = null
            // Attach any renderers that registered before the track was created
            pendingRenderers.forEach { r -> room.initVideoRenderer(r); track.addRenderer(r) }
            pendingRenderers.clear()
            AppLog.i("Glass video track published (host=$host port=$port)")
        } catch (e: Exception) {
            AppLog.e("Failed to publish glass video track", e)
        }
    }

    /**
     * Unpublish and tear down the glass video track.
     * Called automatically by K900Adapter when the stream stops.
     */
    fun stopGlassVideoStream() {
        pendingGlassStream = null
        scope.launch {
            try {
                localVideoTrack?.let { track ->
                    pendingRenderers.forEach { track.removeRenderer(it) }
                    room?.localParticipant?.unpublishTrack(track)
                    track.stop()
                }
                pendingRenderers.clear()
                localVideoTrack = null
                glassVideoCapturer?.dispose()
                glassVideoCapturer = null
                AppLog.i("Glass video track stopped")
            } catch (e: Exception) {
                AppLog.e("Error stopping glass video track", e)
            }
        }
    }

    fun addGlassPreviewRenderer(renderer: TextureViewRenderer) {
        val r = room
        val localTrack = localVideoTrack
        val remoteTrack = ingressVideoTrack
        when {
            r != null && remoteTrack != null -> {
                r.initVideoRenderer(renderer)
                remoteTrack.addRenderer(renderer)
            }
            r != null && localTrack != null -> {
                r.initVideoRenderer(renderer)
                localTrack.addRenderer(renderer)
            }
            else -> {
                if (!pendingRenderers.contains(renderer)) pendingRenderers.add(renderer)
            }
        }
    }

    fun removeGlassPreviewRenderer(renderer: TextureViewRenderer) {
        pendingRenderers.remove(renderer)
        localVideoTrack?.removeRenderer(renderer)
        ingressVideoTrack?.removeRenderer(renderer)
    }

    fun isGlassVideoStreamingActive(): Boolean =
        localVideoTrack != null || pendingGlassStream != null || ingressVideoTrack != null

    fun destroy() {
        AppLog.i("Destroying VoiceAgent")
        scope.cancel()
        cleanup()
    }


    @OptIn(Beta::class)
    private fun setupEventListeners() {
        eventCollectionJob?.cancel()

        eventCollectionJob = scope.launch {
            room?.events?.collect { event ->
                when (event) {
                    is RoomEvent.Connected -> {
                        handleConnected()
                    }

                    is RoomEvent.Disconnected -> {
                        handleDisconnected(event.error, event.reason.toString())
                    }

                    is RoomEvent.Reconnecting -> {
                        connectionState = ConnectionState.RECONNECTING
                        postEvent(VoiceAgentConnectionEvent(VoiceAgentConnectionState.RECONNECTING))
                        AppLog.w("Reconnecting to room...")
                    }

                    is RoomEvent.Reconnected -> {
                        connectionState = ConnectionState.CONNECTED
                        postEvent(VoiceAgentConnectionEvent(VoiceAgentConnectionState.CONNECTED))
                        // Resume reconnect doesn't re-fire ParticipantConnected for existing
                        // participants — manually re-sync so JS state stays accurate
                        room?.remoteParticipants?.values?.forEach { handleParticipantConnected(it) }
                        AppLog.i("Reconnected to room")
                    }

                    is RoomEvent.FailedToConnect -> {
                        connectionState = ConnectionState.FAILED
                        postEvent(
                            VoiceAgentErrorEvent(
                                "FAILED_TO_CONNECT",
                                event.error.message ?: "Failed to connect"
                            )
                        )
                        AppLog.e("Failed to connect: ${event.error.message}")
                        cleanup()
                    }

                    is RoomEvent.ParticipantConnected -> {
                        handleParticipantConnected(event.participant)
                    }

                    is RoomEvent.ParticipantDisconnected -> {
                        handleParticipantDisconnected(event.participant)
                    }

                    is RoomEvent.TrackSubscribed -> {
                        handleTrackSubscribed(event.track, event.participant)
                    }

                    is RoomEvent.TrackUnsubscribed -> {
                        handleTrackUnsubscribed(event.track, event.publications, event.participant)
                    }

                    is RoomEvent.TrackMuted -> {
                        handleTrackMuted(event.publication, event.participant)
                    }

                    is RoomEvent.TrackUnmuted -> {
                        handleTrackUnmuted(event.publication, event.participant)
                    }

                    is RoomEvent.ActiveSpeakersChanged -> {
                        handleActiveSpeakers(event.speakers)
                    }

                    is RoomEvent.DataReceived -> {
                        handleDataReceived(event.data, event.participant)
                    }

                    is RoomEvent.TranscriptionReceived -> {
                        handleTranscriptionReceived(event.participant!!, event.transcriptionSegments)
                    }

                    else -> {
                        AppLog.i("Unknown event: $event")
                        // Ignore other events
                    }
                }
            }
        }

        AppLog.i("Event listeners setup complete")
    }

    private fun handleTranscriptionReceived(participant: Participant, transcriptionSegments: List<TranscriptionSegment>) {
        val isUser = participant.identity == room?.localParticipant?.identity

        transcriptionSegments.forEach { segment ->

            postEvent(
                VoiceAgentTextEvent(
                    id = segment.id,
                    text = segment.text,
                    isFinal = segment.final,
                    isUser = isUser,
                    isTranscription = true,
                    segmentId = segment.id
                )
            )
        }
    }


    private suspend fun handleConnected() {
        connectionState = ConnectionState.CONNECTED
        postEvent(VoiceAgentConnectionEvent(VoiceAgentConnectionState.CONNECTED))
        // Emit joined events for participants already present at connect time
        // (e.g. agent dispatched before client joined the room)
        room?.remoteParticipants?.values?.forEach { handleParticipantConnected(it) }
        AppLog.i("[Latency] room CONNECTED ${SystemClock.elapsedRealtime() - startTimeMs}ms after start")
        AppLog.i("Connected to LiveKit room: ${room?.sid.toString()} ${room?.name}")
        // Publish any glass video stream that connected before the room was ready
        val pending = pendingGlassStream
        if (pending != null) {
            AppLog.i("Room now connected — publishing pending glass video (host=${pending.first} port=${pending.second})")
            room?.let { publishGlassVideoTrack(it, pending.first, pending.second) }
        }
    }


    private fun handleDisconnected(error: Exception?, reason: String?) {
        AppLog.i("Disconnected from room. Reason: $reason, Error: ${error?.message}")
        connectionState = ConnectionState.DISCONNECTED
        postEvent(VoiceAgentConnectionEvent(VoiceAgentConnectionState.DISCONNECTED))
        cleanup()
    }

    private fun handleParticipantConnected(participant: RemoteParticipant) {
        val sid = participant.sid.value
        val identity = participant.identity?.value ?: ""

        if (participant.isAgent) {
            AppLog.i("[Latency] agent JOINED ${SystemClock.elapsedRealtime() - startTimeMs}ms after start")
        }

        if (!isAudioEnabled) {
            // Only release if a mic was actually published — when started with audio=false
            // nothing was ever published (see startConnection guard), so there's nothing to free.
            val hasMic = room?.localParticipant?.getTrackPublication(Track.Source.MICROPHONE) != null
            if (hasMic) setAudioEnabled(false)
        }

        postEvent(
            VoiceAgentParticipantEvent(
                type = "joined",
                participantId = sid,
                identity = identity,
                name = participant.name ?: "",
                isSpeaking = false,
                isAgent = participant.isAgent
            )
        )

        AppLog.i("Participant joined: $identity (sid: $sid)")
    }


    private fun handleParticipantDisconnected(participant: RemoteParticipant) {
        val sid = participant.sid.value
        val identity = participant.identity?.value ?: ""

        postEvent(
            VoiceAgentParticipantEvent(
                type = "left",
                participantId = sid,
                identity = identity,
                name = participant.name ?: "",
                isSpeaking = false
            )
        )

        AppLog.i("Participant left: $identity (sid: $sid)")
    }


    private fun handleTrackSubscribed(
        track: Track,
        participant: RemoteParticipant
    ) {
        val identity = participant.identity?.value ?: ""

        if (track is RemoteAudioTrack) {
            postEvent(
                VoiceAgentAudioTrackEvent(
                    type = "subscribed",
                    participantId = participant.sid.value,
                    trackId = track.sid.toString(),
                    isLocal = false,
                    isMuted = !track.enabled
                )
            )
            AppLog.i("Subscribed to remote audio track: ${track.sid} from $identity")
        } else if (track is RemoteVideoTrack && identity.endsWith("-ingress")) {
            ingressVideoTrack = track
            val r = room ?: return
            pendingRenderers.forEach { renderer ->
                r.initVideoRenderer(renderer)
                track.addRenderer(renderer)
            }
            pendingRenderers.clear()
            AppLog.i("Ingress video track subscribed from $identity")
        }
    }


    private fun handleTrackUnsubscribed(
        track: Track,
        publication: TrackPublication,
        participant: RemoteParticipant
    ) {
        if (track is RemoteAudioTrack) {
            postEvent(
                VoiceAgentAudioTrackEvent(
                    type = "unsubscribed",
                    participantId = participant.sid.toString(),
                    trackId = track.sid.toString(),
                    isLocal = false,
                    isMuted = true
                )
            )
            AppLog.i("Unsubscribed from remote audio track: ${track.sid.toString()}")
        } else if (track is RemoteVideoTrack && track == ingressVideoTrack) {
            AppLog.i("Ingress video track unsubscribed from ${participant.identity?.value}")
            ingressVideoTrack = null
        }
    }


    private fun handleTrackMuted(publication: TrackPublication, participant: Participant) {
        postEvent(
            VoiceAgentAudioTrackEvent(
                type = "muted",
                participantId = participant.sid.value,
                trackId = publication.sid,
                isLocal = participant == room?.localParticipant,
                isMuted = true
            )
        )

        AppLog.i("Track muted: ${publication.sid}")
    }


    private fun handleTrackUnmuted(publication: TrackPublication, participant: Participant) {
        postEvent(
            VoiceAgentAudioTrackEvent(
                type = "unmuted",
                participantId = participant.sid.value,
                trackId = publication.sid,
                isLocal = participant == room?.localParticipant,
                isMuted = false
            )
        )

        AppLog.i("Track unmuted: ${publication.sid}")
    }


    private fun handleActiveSpeakers(speakers: List<Participant>) {
        val jsonArray = org.json.JSONArray()
        speakers.forEach { participant ->
            val obj = org.json.JSONObject()
            obj.put("participantId", participant.sid.value)
            obj.put("identity", participant.identity?.value ?: "")
            obj.put("name", participant.name ?: "")
            obj.put("audioLevel", participant.audioLevel.toDouble())
            obj.put("isAgent", participant.isAgent)
            jsonArray.put(obj)
        }
        postEvent(VoiceAgentActiveSpeakersEvent(speakersJson = jsonArray.toString()))
    }


    private fun handleDataReceived(data: ByteArray, participant: RemoteParticipant?) {
        val base64Data = Base64.encodeToString(data, Base64.NO_WRAP)

        postEvent(
            VoiceAgentDataEvent(
                participantId = participant?.sid?.value ?: "",
                data = base64Data,
                topic = null
            )
        )

        AppLog.d("Data received from ${participant?.identity?.value}: ${data.size} bytes")
    }


    private fun setupAudio() {
        scope.launch(Dispatchers.IO) {
            krisp.init()
        }
        // Detection only — no AudioManager mutation here. We own the audio mode ourselves via
        // enter/exitAudioSession (LiveKit runs with NoAudioHandler), so the only system-audio
        // work at start is detecting whether a BT-SCO mic is present.
        detectBluetoothMic()
        registerAudioDebug()
    }

    /**
     * Detect whether a Bluetooth-SCO mic is currently connected, WITHOUT mutating system audio
     * state. Sets [isBtScoActive], which drives three things, all keyed on the same value:
     * the AEC customizer (disable hardware AEC for BT SCO), the audioOutputType at LiveKit.create(),
     * and the enter/exitAudioSession mode branch. Must run before LiveKit.create().
     */
    private fun detectBluetoothMic() {
        val tag = "BluetoothMicRoute"
        try {
            val audioManager =
                context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            if (!audioManager.isBluetoothScoAvailableOffCall) {
                AppLog.d(tag, "Bluetooth SCO not supported on this device")
                isBtScoActive = false
                return
            }

            // Check for an actually-connected SCO device (not just whether the device supports it),
            // so phone-mic sessions stay in normal/media mode and don't show call UI.
            isBtScoActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.availableCommunicationDevices
                    .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            } else {
                audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            }
        } catch (e: Exception) {
            AppLog.e(tag, "BT mic detect failed", e)
            isBtScoActive = false
        }
    }

    private fun cleanup() {
        cachedTokenResponse = null
        // Cancel event collection
        eventCollectionJob?.cancel()
        eventCollectionJob = null

        // Release phone camera track if active
        phoneCameraTrack?.let { track -> phoneRenderers.forEach { track.removeRenderer(it) } }
        phoneRenderers.clear()
        phoneCameraTrack = null
        phoneStreaming = false

        // Release glass video track if active
        localVideoTrack?.let { track -> pendingRenderers.forEach { track.removeRenderer(it) } }
        pendingRenderers.clear()
        pendingGlassStream = null
        localVideoTrack?.runCatching { stop() }
        localVideoTrack = null
        glassVideoCapturer?.runCatching { dispose() }
        glassVideoCapturer = null

        // Release resources
        localAudioTrack = null

        // Safety net: ensure the audio session is torn down (idempotent) — NoAudioHandler
        // won't restore MODE_NORMAL on disconnect.
        exitAudioSession()
        unregisterAudioDebug()

        room?.let {
            try {
                it.disconnect()
                it.release()
                AppLog.i("Room disconnected and released")
            } catch (e: Exception) {
                AppLog.e("Error releasing room", e)
            }
        }
        room = null

        // Cleanup tools
        toolManager?.cleanup()
        toolManager = null

        isBtScoActive = false
        startTimeMs = 0L
        connectionState = ConnectionState.DISCONNECTED

        AppLog.i("VoiceAgent cleanup complete")
    }


    private fun checkPermissions(): Boolean {
        val recordAudio = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!recordAudio) {
            postEvent(
                VoiceAgentErrorEvent(
                    "PERMISSION_REQUIRED",
                    "RECORD_AUDIO permission is required for voice agent"
                )
            )
            AppLog.e("RECORD_AUDIO permission not granted")
            return false
        }

        return true
    }


    private fun postEvent(event: VoiceAgentEvent) {
        try {
            val agentEvent = when (event) {
                is VoiceAgentConnectionEvent -> {
                    AgentEventMsg(
                        cmd = AgentEventConstants.MSG_AGENT_STATE_CHANGED,
                        s1 = event.state.name
                    )
                }

                is VoiceAgentParticipantEvent -> {
                    AgentEventMsg(
                        cmd = AgentEventConstants.MSG_AGENT_PARTICIPANT_EVENT,
                        s1 = event.type,
                        s2 = event.participantId,
                        s3 = event.identity,
                        s4 = event.name,
                        n1 = if (event.isSpeaking) 1 else 0,
                        n2 = if (event.isAgent) 1 else 0
                    )
                }

                is VoiceAgentAudioTrackEvent -> {
                    AgentEventMsg(
                        cmd = AgentEventConstants.MSG_AGENT_AUDIO_TRACK_EVENT,
                        s1 = event.type,
                        s2 = event.participantId,
                        s3 = event.trackId,
                        n1 = if (event.isLocal) 1 else 0,
                        n2 = if (event.isMuted) 1 else 0
                    )
                }

                is VoiceAgentTextEvent -> {
                    AgentEventMsg(
                        cmd = AgentEventConstants.MSG_AGENT_TEXT_EVENT,
                        s1 = event.id,
                        s2 = event.text,
                        s3 = event.segmentId,
                        n1 = if (event.isFinal) 1 else 0,
                        n2 = if (event.isUser) 1 else 0,
                        n3 = if (event.isTranscription) 1 else 0
                    )
                }

                is VoiceAgentDataEvent -> {
                    AgentEventMsg(
                        cmd = AgentEventConstants.MSG_AGENT_DATA_EVENT,
                        s1 = event.participantId,
                        s2 = event.data,
                        s3 = event.topic
                    )
                }

                is VoiceAgentErrorEvent -> {
                    AgentEventMsg(
                        cmd = AgentEventConstants.MSG_AGENT_ERROR,
                        s1 = event.code,
                        s2 = event.message
                    )
                }

                is VoiceAgentActiveSpeakersEvent -> {
                    AgentEventMsg(
                        cmd = AgentEventConstants.MSG_AGENT_ACTIVE_SPEAKERS,
                        s1 = event.speakersJson
                    )
                }

                is VoiceAgentToolExecutedEvent -> {
                    AgentEventMsg(
                        cmd = AgentEventConstants.MSG_AGENT_TOOL_EXECUTED,
                        s1 = event.data
                    )
                }

                is VoiceAgentRpcMethodCalledEvent -> {
                    AgentEventMsg(
                        cmd = AgentEventConstants.MSG_AGENT_RPC_METHOD_CALLED,
                        s1 = event.method,
                        s2 = event.payload
                    )
                }
            }

            EventBus.getDefault().post(agentEvent)
            AppLog.d("VoiceAgent event posted: ${event.javaClass.simpleName}")
        } catch (e: Exception) {
            AppLog.e("Failed to post event", e)
        }
    }
}

sealed class VoiceAgentEvent

data class VoiceAgentTextEvent(
    val id: String,
    val text: String,
    val isFinal: Boolean,
    val isUser: Boolean,
    val isTranscription: Boolean,
    val segmentId: String?
) : VoiceAgentEvent()

data class VoiceAgentConnectionEvent(
    val state: VoiceAgentConnectionState
) : VoiceAgentEvent()

enum class VoiceAgentConnectionState {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    RECONNECTING,
    FAILED
}

data class VoiceAgentParticipantEvent(
    val type: String,           // "joined", "left", "speaking", "stopped_speaking"
    val participantId: String,
    val identity: String,
    val name: String,
    val isSpeaking: Boolean = false,
    val isAgent: Boolean = false
) : VoiceAgentEvent()


data class VoiceAgentAudioTrackEvent(
    val type: String,           // "published", "subscribed", "unsubscribed", "muted", "unmuted", "speaker_enabled", "speaker_disabled"
    val participantId: String,
    val trackId: String,
    val isLocal: Boolean,
    val isMuted: Boolean
) : VoiceAgentEvent()


data class VoiceAgentDataEvent(
    val participantId: String,
    val data: String,           // Base64 encoded
    val topic: String?
) : VoiceAgentEvent()

data class VoiceAgentErrorEvent(
    val code: String,           // Error code (e.g., "CONNECTION_FAILED", "PERMISSION_DENIED")
    val message: String         // Error message
) : VoiceAgentEvent()

data class VoiceAgentActiveSpeakersEvent(
    val speakersJson: String    // JSON array of {participantId, identity, name, audioLevel, isAgent}, ordered by audioLevel desc
) : VoiceAgentEvent()

data class VoiceAgentToolExecutedEvent(
    val data: String            // raw JSON string from RPC payload
) : VoiceAgentEvent()

data class VoiceAgentRpcMethodCalledEvent(
    val method: String,
    val payload: String
) : VoiceAgentEvent()

/**
 * Token response from backend API
 */
data class TokenResponse(
    val token: String,
    val url: String,
    val ingressUrl: String? = null,
    val streamKey: String? = null
)