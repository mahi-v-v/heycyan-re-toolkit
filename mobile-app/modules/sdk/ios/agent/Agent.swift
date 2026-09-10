import Foundation
import AVFoundation
import LiveKitClient
import os.log

private let log = Logger(subsystem: Bundle.main.bundleIdentifier ?? "glass", category: "Agent")

/// Core LiveKit voice agent (mirrors Agent.kt)
/// Connects to a LiveKit room, manages audio session, handles RPC tools,
/// and posts all events to NotificationCenter (replacing EventBus).
class Agent: NSObject {

    // MARK: - State

    private(set) var connectionState: AgentConnectionState = .disconnected {
        didSet {
            if oldValue != connectionState {
                postEvent(AgentEventMsg(
                    cmd: AgentEventConstants.msgStateChanged,
                    s1: connectionState.rawValue
                ))
            }
        }
    }

    private(set) var isMicEnabled: Bool = true
    private(set) var isSpeakerEnabled: Bool = true
    private var isAudioEnabled: Bool = true
    private var agentName: String?

    private var room: Room?
    private var connectionTask: Task<Void, Never>?
    private var authCookie: String?
    private var cachedTokenResponse: TokenResponse?
    private var registeredRpcMethods: Set<String> = []
    var currentIngressTrack: RemoteVideoTrack?
    private var previewRenderers: [GlassStreamRendering] = []
    private let renderersLock = NSLock()

    // We own the AVAudioSession lifecycle (see enterAudioSession) instead of letting LiveKit churn it.
    private var audioSessionActive = false
    private var hasBtMic = false
    // Tracks whether we've told the glasses to enter AI-reply mode (deduped edge state).
    private var glassAiActive = false

    // MARK: - Start / Stop

    func start(audio: Bool = true, agentName: String? = nil) {
        log.info("▶️ start(audio: \(audio), agentName: \(agentName ?? "nil"))")
        self.isAudioEnabled = audio
        self.agentName = agentName

        // Reset session state to defaults
        self.isMicEnabled = audio
        self.isSpeakerEnabled = audio

        connectionTask?.cancel()
        connectionTask = Task {
            await startAsync(audio: audio)
        }
    }

    private func startAsync(audio: Bool) async {
        guard !Task.isCancelled else {
            log.warning("⚠️ startAsync cancelled before starting")
            return
        }

        if connectionState == .connected {
            log.info("🔄 Already connected — stopping before reconnect")
            stop()
            try? await Task.sleep(nanoseconds: 500_000_000)
        }

        connectionState = .connecting

        do {
            log.info("🔑 Fetching token from backend...")
            let tokenData = try await getTokenFromBackend()
            log.info("✅ Token received — url: \(tokenData.url)")
            guard !Task.isCancelled else {
                log.warning("⚠️ startAsync cancelled after token fetch")
                return
            }
            await startConnection(tokenData: tokenData, audio: audio)
        } catch {
            log.error("❌ START_FAILED: \(error.localizedDescription)")
            connectionState = .failed
            postErrorEvent(code: "START_FAILED", message: error.localizedDescription)
        }
    }

    func stop() {
        log.info("⏹️ stop()")
        connectionTask?.cancel()
        connectionTask = nil
        Task { await cleanup() }
    }

    // MARK: - Token fetch

    private func getTokenFromBackend() async throws -> TokenResponse {
        let endpoint: String
        if let name = agentName, !name.isEmpty,
           let encoded = name.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) {
            endpoint = "/chat/agent/token?agentName=\(encoded)"
        } else {
            endpoint = "/chat/agent/token"
        }
        return try await ApiClient.shared.get(endpoint: endpoint)
    }

    // MARK: - Connection

    private func startConnection(tokenData: TokenResponse, audio: Bool) async {
        log.info("🔌 startConnection — audio: \(audio)")
        do {
            await cleanup()
            cachedTokenResponse = tokenData

            let newRoom = Room()
            newRoom.add(delegate: self)
            self.room = newRoom

            ToolManager.shared.registerWithLiveKit(room: newRoom)

            Task {
                do {
                    try await newRoom.registerRpcMethod("tool_executed") { [weak self] (data: RpcInvocationData) in
                        self?.postEvent(AgentEventMsg(
                            cmd: AgentEventConstants.msgToolExecuted,
                            s1: data.payload
                        ))
                        return "{}"
                    }
                    log.info("✅ Registered RPC method 'tool_executed'")

                    // Native handler so the voice agent can stop voice mode even when JS is dead
                    // (backgrounded/killed). Directly disables audio: releases mic, mutes speaker,
                    // tears down the audio session, and exits glasses AI-reply mode.
                    try await newRoom.registerRpcMethod("exit_voice") { [weak self] (_: RpcInvocationData) in
                        log.info("📵 RPC 'exit_voice' → disabling audio")
                        self?.setAudioEnabled(false)
                        return "{}"
                    }
                    log.info("✅ Registered RPC method 'exit_voice'")

                    for method in self.registeredRpcMethods {
                        try await newRoom.registerRpcMethod(method) { [weak self] (data: RpcInvocationData) in
                            self?.postEvent(AgentEventMsg(
                                cmd: AgentEventConstants.msgRpcMethodCalled,
                                s1: method,
                                s2: data.payload
                            ))
                            return "{}"
                        }
                    }
                } catch {
                    log.error("❌ Failed to register RPC methods: \(error)")
                }
            }

            log.info("🌐 Connecting to LiveKit room...")
            try await newRoom.connect(
                url: tokenData.url,
                token: tokenData.token,
                connectOptions: ConnectOptions(autoSubscribe: true),
                roomOptions: RoomOptions(defaultAudioPublishOptions: AudioPublishOptions(

                ))
            )
            log.info("🟢 Room connected — name: \(newRoom.name ?? "unknown")")

            if audio {
                // Own the AVAudioSession ONLY when voice is on. Doing it here (not on every connect)
                // keeps a no-audio control connection from grabbing/holding a record session that
                // ducks other apps and pins routing in the background. Enter before capturing so
                // routing is correct from the first frame (mirrors Android's acquireMicTrack).
                // The repeated setConfiguration/setActive churn LiveKit would otherwise do is what
                // drops the glasses' BLE link ~4s in — owning the session avoids that.
                enterAudioSession()

                // Release the mic HAL when muted (idle) so other apps can record while we stay
                // connected. .restart restarts the engine without mic input on mute — but that engine
                // restart is exactly the kind of session churn that knocks the glasses' BLE off, so we
                // skip it on the BT path and keep LiveKit's default signal-mute behavior there.
                if !hasBtMic {
                    do { try AudioManager.shared.set(microphoneMuteMode: .restart) }
                    catch { log.error("⚠️ Failed to set mic mute mode: \(error)") }
                }

                log.info("🎙️ Enabling microphone")
                // Enqueue via the serial chain so the connect-time publish stays ordered ahead of any
                // immediate close/reopen toggle (no detached-Task race).
                setMicEnabled(true)
            }

        } catch {
            log.error("❌ CONNECTION_FAILED: \(error.localizedDescription)")
            connectionState = .failed
            postErrorEvent(code: "CONNECTION_FAILED", message: error.localizedDescription)
            await cleanup()
        }
    }

    // MARK: - Audio session ownership

    /// Take over the AVAudioSession so LiveKit doesn't reconfigure/churn it — churn drops the glasses'
    /// BLE link. Engage the BT headset (HFP mic + speaker) when present; otherwise phone mic + speaker.
    /// Mirrors Android's enterAudioSession (NoAudioHandler + manual routing).
    private func enterAudioSession() {
        guard !audioSessionActive else { return }
        // iOS analog of Android's NoAudioHandler: stop LiveKit's observer from driving the session.
        AudioManager.shared.audioSession.isAutomaticConfigurationEnabled = false

        let session = AVAudioSession.sharedInstance()
        do {
            // Configure a 2-way call session FIRST so the BT HFP input becomes enumerable, THEN look
            // for it. Querying availableInputs before .playAndRecord is active returns no HFP port —
            // which is why capture was falling back to the phone mic.
            try session.setCategory(.playAndRecord, mode: .voiceChat,
                                    options: [.allowBluetooth, .duckOthers])  // HFP only — force SCO, not A2DP
            try session.setActive(true)

            if let btMic = session.availableInputs?.first(where: { $0.portType == .bluetoothHFP }) {
                // Headset path: selecting the HFP input brings up SCO → glasses mic + glasses speaker
                // (mono, controlled by the call volume), like a normal call. No engine churn.
                try session.setPreferredInput(btMic)
                hasBtMic = true
                // Clean up the HFP voice path. The agent's voice arrives already studio-leveled, so
                // LiveKit's Auto Gain Control only pumps it (swelling/dipping) and advanced ducking
                // modulates it further — both read as "unclear" over the mono HFP link. Disable them
                // while KEEPING voice processing (AEC) on: we do NOT bypass it, because the glasses'
                // mic and speaker are inches apart and without AEC the agent would hear itself and
                // echo/feed back. Runtime-safe per LiveKit docs.
                AudioManager.shared.isVoiceProcessingAGCEnabled = false
                AudioManager.shared.isAdvancedDuckingEnabled = false
                log.info("🎧 enterAudioSession — BT headset (HFP) mic+speaker: \(btMic.portName)")
            } else {
                // No BT headset: phone mic + speaker/A2DP output.
                hasBtMic = false
                // Restore LiveKit defaults (a prior BT session may have turned these off): the phone
                // mic sits farther from the mouth, so AGC leveling is helpful here.
                AudioManager.shared.isVoiceProcessingAGCEnabled = true
                AudioManager.shared.isAdvancedDuckingEnabled = true
                try session.setCategory(.playAndRecord, mode: .videoChat,
                                        options: [.allowBluetoothA2DP, .defaultToSpeaker, .duckOthers])
                log.info("📱 enterAudioSession — phone mic (no BT headset)")
            }
            audioSessionActive = true
            startAudioRouteObserver()
            startGlassEventObserver()
        } catch {
            log.error("⚠️ enterAudioSession failed: \(error)")
        }
    }

    private func exitAudioSession() {
        guard audioSessionActive else { return }
        stopAudioRouteObserver()
        stopGlassEventObserver()
        let session = AVAudioSession.sharedInstance()
        try? session.setActive(false, options: .notifyOthersOnDeactivation)
        // Restore LiveKit's default behavior for any non-agent audio.
        AudioManager.shared.audioSession.isAutomaticConfigurationEnabled = true
        audioSessionActive = false
        hasBtMic = false
        log.info("🔇 exitAudioSession")
    }

    private func startAudioRouteObserver() {
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.routeChangeNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleAudioRouteChange),
            name: AVAudioSession.routeChangeNotification, object: nil)
    }

    private func stopAudioRouteObserver() {
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.routeChangeNotification, object: nil)
    }

    /// Glasses may (re)connect after the session is up — re-pin the mic to the HFP input so it stays
    /// on the glasses. Mirrors Android re-detecting the SCO device at conversation start.
    @objc private func handleAudioRouteChange(_ notification: Notification) {
        guard audioSessionActive else { return }
        let session = AVAudioSession.sharedInstance()
        guard let btMic = session.availableInputs?.first(where: { $0.portType == .bluetoothHFP }) else {
            hasBtMic = false
            return
        }
        if session.preferredInput?.uid != btMic.uid {
            do {
                try session.setPreferredInput(btMic)
                hasBtMic = true
                log.info("🎧 route change — re-pinned mic to BT headset: \(btMic.portName)")
            } catch {
                log.error("⚠️ Failed to re-pin BT mic on route change: \(error)")
            }
        }
    }

    // MARK: - Glasses AI-mode sync

    /// Keep the glasses' on-device AI-reply mode in sync with whether the agent's voice is on.
    /// Deduped (one edge state) and gated on the glasses being connected. Event-only — does not touch
    /// audio routing. Re-fires on glasses (re)connect so the SCO-induced BLE blip doesn't drop the
    /// `speaking_start`.
    private func syncGlassAiStatus() {
        let active = isAudioEnabled && GlassManager.shared.isConnected()
        guard active != glassAiActive else { return }
        glassAiActive = active
        let status = active ? "speaking_start" : "speaking_stop"
        log.info("🕶️ glass AI mode → \(active) (\(status))")
        Task { try? await GlassManager.shared.setAiStatus(status) }
    }

    private func startGlassEventObserver() {
        NotificationCenter.default.removeObserver(self, name: .glassEvent, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleGlassEvent),
            name: .glassEvent, object: nil)
    }

    private func stopGlassEventObserver() {
        NotificationCenter.default.removeObserver(self, name: .glassEvent, object: nil)
    }

    @objc private func handleGlassEvent(_ notification: Notification) {
        guard let msg = notification.userInfo?["msg"] as? GlassEventMsg else { return }
        if msg.cmd == GlassEventConstants.msgConnected || msg.cmd == GlassEventConstants.msgDisconnected {
            syncGlassAiStatus()
        }
    }

    // MARK: - Audio controls

    // Serializes mic/session ops so a fast disable→enable (close→reopen of voice mode) can't run out
    // of order. setMicrophone runs in a detached Task and the bridge resolves its JS promise before it
    // finishes, so without this an unpublish (from close) could land after a publish (from reopen) and
    // leave the mic disabled. Mirrors Android awaiting acquire/releaseMicTrack in sequence.
    private var audioOpTask: Task<Void, Never>?
    private func enqueueAudioOp(_ op: @escaping () async -> Void) {
        let previous = audioOpTask
        audioOpTask = Task { await previous?.value; await op() }
    }

    /// Diagnostic: dump the ACTUAL audio route + mic-track state, to tell apart "LiveKit published but
    /// the AVAudioSession input didn't land on the glasses" from a publish error.
    private func logAudioRoute(_ tag: String) {
        let s = AVAudioSession.sharedInstance()
        let ins = s.currentRoute.inputs.map { "\($0.portType.rawValue):\($0.portName)" }.joined(separator: ",")
        let outs = s.currentRoute.outputs.map { "\($0.portType.rawValue):\($0.portName)" }.joined(separator: ",")
        let micPub = room?.localParticipant.trackPublications.values.first { $0.kind == .audio }
        log.info("🔎 [\(tag)] cat=\(s.category.rawValue) mode=\(s.mode.rawValue) in=[\(ins)] out=[\(outs)] micPub=\(micPub != nil) micMuted=\(micPub?.isMuted ?? true)")
    }

    private func postMicEvent(enabled: Bool) {
        let sid = room?.localParticipant.sid?.stringValue ?? ""
        postEvent(AgentEventMsg(
            cmd: AgentEventConstants.msgAudioTrackEvent,
            n1: 1,
            n2: enabled ? 0 : 1,
            s1: enabled ? "unmuted" : "muted",
            s2: sid
        ))
    }

    private func micPublication() -> LocalTrackPublication? {
        room?.localParticipant.trackPublications.values.first { $0.source == .microphone } as? LocalTrackPublication
    }

    /// Mute/unmute the existing mic track (or create it on first enable). Lightweight — used by the
    /// mid-call mute button. Surfaces errors that the old `try?` call sites swallowed.
    private func applyMicrophone(_ enabled: Bool) async {
        guard let lp = room?.localParticipant else {
            log.error("🎙️ setMicrophone(\(enabled)) SKIPPED — no room/localParticipant (state: \(self.connectionState.rawValue))")
            return
        }
        do {
            try await lp.setMicrophone(enabled: enabled)
        } catch {
            log.error("❌ setMicrophone(\(enabled)) FAILED: \(error.localizedDescription)")
        }
    }

    /// Publish a FRESH mic track (unpublishing any stale one first). A plain unmute won't restart
    /// WebRTC capture after the AVAudioSession was deactivated on the previous voice-mode close — the
    /// track looked published+unmuted+routed but captured nothing. Republishing restarts the capture
    /// unit. Mirrors Android's acquireMicTrack.
    private func publishMicFresh() async {
        guard let lp = room?.localParticipant else {
            log.error("🎙️ publishMicFresh SKIPPED — no room/localParticipant (state: \(self.connectionState.rawValue))")
            return
        }
        if let stale = micPublication() {
            log.info("🎙️ unpublishing stale mic track before fresh publish")
            try? await lp.unpublish(publication: stale)
        }
        do {
            try await lp.setMicrophone(enabled: true)   // no existing pub → creates + publishes a fresh track
        } catch {
            log.error("❌ publishMicFresh FAILED: \(error.localizedDescription)")
        }
    }

    /// Fully unpublish the mic track (not just mute) so the next enable republishes and restarts
    /// capture. Mirrors Android's releaseMicTrack.
    private func unpublishMic() async {
        guard let lp = room?.localParticipant, let pub = micPublication() else { return }
        do {
            try await lp.unpublish(publication: pub)
        } catch {
            log.error("❌ unpublishMic FAILED: \(error.localizedDescription)")
        }
    }

    func setMicEnabled(_ enabled: Bool) {
        log.info("🎙️ setMicEnabled: \(enabled)")
        isMicEnabled = enabled
        enqueueAudioOp { [weak self] in
            await self?.applyMicrophone(enabled)
            self?.postMicEvent(enabled: enabled)
        }
    }

    func setSpeakerEnabled(_ enabled: Bool) {
        isSpeakerEnabled = enabled

        // Mirror Android's room.setSpeakerMute(): set volume on all remote audio tracks
        room?.remoteParticipants.values.forEach { participant in
            participant.trackPublications.values.forEach { pub in
                if pub.kind == .audio,
                   let remotePub = pub as? RemoteTrackPublication,
                   let audioTrack = remotePub.track as? RemoteAudioTrack {
                    audioTrack.volume = enabled ? 1.0 : 0.0
                }
            }
        }
        postEvent(AgentEventMsg(
            cmd: AgentEventConstants.msgAudioTrackEvent,
            n1: 0, n2: enabled ? 0 : 1,
            s1: enabled ? "speaker_enabled" : "speaker_disabled"
        ))
    }

    func setAudioEnabled(_ enabled: Bool) {
        isAudioEnabled = enabled
        isMicEnabled = enabled
        // Run as a single ordered op (enter→publish to enable, unpublish→exit to disable). enter/exit
        // live INSIDE the op so a fast disable→enable (close→reopen) can't reactivate the session
        // ahead of the queued unpublish. enter/exit are idempotent, so the no-audio connect path
        // (participantDidConnect → setAudioEnabled(false)) stays a safe no-op.
        enqueueAudioOp { [weak self] in
            guard let self else { return }
            // One concise "what's happening" line per op so we can trace the close→reopen sequence.
            log.info("🔊 setAudioEnabled(\(enabled)) op running — room: \(self.room != nil), sessionActive: \(self.audioSessionActive), hasBtMic: \(self.hasBtMic)")
            if enabled {
                self.enterAudioSession()
                await self.publishMicFresh()   // fresh publish → restarts WebRTC capture
                self.logAudioRoute("after-enable")
            } else {
                await self.unpublishMic()       // full release so the next enable republishes
                self.exitAudioSession()
            }
            self.postMicEvent(enabled: enabled)
            self.setSpeakerEnabled(enabled)   // synchronous remote-track volume; safe here
            self.syncGlassAiStatus()          // tell the glasses to enter/exit AI-reply mode
        }
    }

    func toggleAudio() {
        setAudioEnabled(!isAudioEnabled)
    }

    // MARK: - Messaging

    func sendText(_ text: String) {
        log.info("💬 sendText: \"\(text.prefix(60))\"")
        Task {
            try? await room?.localParticipant.sendText(
                text,
                options: StreamTextOptions(topic: "lk.chat")
            )
        }
    }

    func sendFile(_ path: String) async throws {
        log.info("📎 sendFile: \(path)")
        let fileURL = URL(fileURLWithPath: path)
        let mimeType = FileUtil.detectContentType(path: path)
        try await room?.localParticipant.sendFile(
            fileURL,
            options: StreamByteOptions(topic: "files", mimeType: mimeType)
        )
    }

    func sendData(_ data: Data, reliable: Bool = true) {
        Task {
            try? await room?.localParticipant.publish(
                data: data,
                options: DataPublishOptions(reliable: reliable)
            )
        }
    }

    // MARK: - Room info

    func getRoomId() -> String? {
        return room?.name
    }

    // MARK: - App session

    func reloadTools() {
        Task {
            guard let agentParticipant = room?.remoteParticipants.values
                .first(where: { $0.kind == .agent }),
                  let identity = agentParticipant.identity else { return }
            try? await room?.localParticipant.performRpc(
                destinationIdentity: identity,
                method: "reload_tools",
                payload: "{}"
            )
        }
    }

    func startApp(appId: String) {
        Task {
            guard let agentParticipant = room?.remoteParticipants.values
                .first(where: { $0.kind == .agent }),
                  let identity = agentParticipant.identity else { return }
            let payload = "{\"appId\":\"\(appId)\"}"
            try? await room?.localParticipant.performRpc(
                destinationIdentity: identity,
                method: "app.session.start",
                payload: payload
            )
        }
    }

    func sendFiles(_ urls: [String], message: String) async throws {
        guard let agentParticipant = room?.remoteParticipants.values
            .first(where: { $0.kind == .agent }),
              let identity = agentParticipant.identity else { return }
        let payload: [String: Any] = ["urls": urls, "message": message]
        let data = try JSONSerialization.data(withJSONObject: payload)
        let payloadStr = String(data: data, encoding: .utf8) ?? "{}"
        try await room?.localParticipant.performRpc(
            destinationIdentity: identity,
            method: "send_files",
            payload: payloadStr
        )
    }

    func seedRpcMethods(_ methods: Set<String>) {
        registeredRpcMethods.formUnion(methods)
    }

    func registerRpcMethod(_ methodName: String) async throws {
        registeredRpcMethods.insert(methodName)
        guard let room else { return }
        try await room.registerRpcMethod(methodName) { [weak self] (data: RpcInvocationData) in
            self?.postEvent(AgentEventMsg(
                cmd: AgentEventConstants.msgRpcMethodCalled,
                s1: methodName,
                s2: data.payload
            ))
            return "{}"
        }
    }

    func performRpc(method: String, payload: String) async throws -> String {
        guard let agentParticipant = room?.remoteParticipants.values
            .first(where: { $0.kind == .agent }),
              let identity = agentParticipant.identity else {
            throw NSError(domain: "Agent", code: -1,
                          userInfo: [NSLocalizedDescriptionKey: "No agent participant"])
        }
        let result = try await room?.localParticipant.performRpc(
            destinationIdentity: identity,
            method: method,
            payload: payload
        )
        return result ?? "{}"
    }

    // MARK: - Auth

    func setCookie(_ cookie: String) {
        log.info("🍪 setCookie (\(cookie.count) chars)")
        authCookie = cookie
        ApiClient.shared.setAuthToken(cookie)
    }

    // MARK: - Cleanup

    private func cleanup() async {
        log.info("🧹 cleanup()")
        stopPhoneStream()
        exitAudioSession()
        await room?.disconnect()
        room = nil
        cachedTokenResponse = nil
        currentIngressTrack = nil
        connectionState = .disconnected
    }

    // MARK: - Phone camera streaming

    private var phoneCameraTrack: LocalVideoTrack?
    private var phoneCameraPublication: LocalTrackPublication?
    private var phonePreviewRenderers: [VideoView] = []
    private(set) var phoneStreaming: Bool = false

    func startPhoneStream(fps: Int, bitrate: Int) async throws {
        let captureOptions = CameraCaptureOptions(
            position: .back,
            dimensions: .h720_169,
            fps: fps
        )
        let publishOptions = VideoPublishOptions(
            encoding: VideoEncoding(maxBitrate: bitrate * 1000, maxFps: fps)
        )
        let track = LocalVideoTrack.createCameraTrack(
            name: "phone-camera",
            options: captureOptions
        )
        guard let pub = try await room?.localParticipant.publish(
            videoTrack: track,
            options: publishOptions
        ) else {
            throw NSError(domain: "Agent", code: -1,
                          userInfo: [NSLocalizedDescriptionKey: "Room not connected"])
        }
        phoneCameraTrack = track
        phoneCameraPublication = pub
        phoneStreaming = true
        let renderers = phonePreviewRenderers
        DispatchQueue.main.async { renderers.forEach { $0.track = track } }
    }

    func stopPhoneStream() {
        let pub = phoneCameraPublication
        let track = phoneCameraTrack
        phoneCameraTrack = nil
        phoneCameraPublication = nil
        phoneStreaming = false
        let renderers = phonePreviewRenderers
        Task {
            if let p = pub { try? await room?.localParticipant.unpublish(publication: p) }
            try? await track?.stop()
            DispatchQueue.main.async { renderers.forEach { $0.track = nil } }
        }
    }

    func flipPhoneCamera() {
        guard let capturer = phoneCameraTrack?.capturer as? CameraCapturer else { return }
        Task { try? await capturer.switchCameraPosition() }
    }

    func addPhonePreviewRenderer(_ view: VideoView) {
        phonePreviewRenderers.append(view)
        if let track = phoneCameraTrack {
            DispatchQueue.main.async { view.track = track }
        }
    }

    func removePhonePreviewRenderer(_ view: VideoView) {
        phonePreviewRenderers.removeAll { $0 === view }
        DispatchQueue.main.async { view.track = nil }
    }

    func isPhoneStreaming() -> Bool { phoneStreaming }

    // MARK: - Glass streaming

    func fetchStreamCredentials() throws -> (ingressUrl: String, streamKey: String) {
        guard let resp = cachedTokenResponse,
              let url = resp.ingressUrl, let key = resp.streamKey
        else { throw NSError(domain: "Agent", code: -1,
               userInfo: [NSLocalizedDescriptionKey: "No stream credentials — start agent first"]) }
        return (url, key)
    }

    func addGlassPreviewRenderer(_ view: GlassStreamRendering) {
        renderersLock.withLock { previewRenderers.append(view) }
        if let track = currentIngressTrack {
            DispatchQueue.main.async { view.videoView.track = track }
        }
    }

    func removeGlassPreviewRenderer(_ view: GlassStreamRendering) {
        renderersLock.withLock { previewRenderers.removeAll { $0 === view } }
        DispatchQueue.main.async { view.videoView.track = nil }
    }

    // MARK: - Event helpers

    private func postErrorEvent(code: String, message: String) {
        postEvent(AgentEventMsg(cmd: AgentEventConstants.msgError, s1: code, s2: message))
    }

    func postEvent(_ msg: AgentEventMsg) {
        NotificationCenter.default.post(
            name: .agentEvent,
            object: nil,
            userInfo: ["msg": msg]
        )
    }
}

// MARK: - RoomDelegate

extension Agent: RoomDelegate {

    func roomDidConnect(_ room: Room) {
        log.info("🟢 roomDidConnect — room: \(room.name ?? "?")")
        connectionState = .connected
        // Emit joined for participants already in the room at connect time
        // (e.g. agent dispatched before client joined)
        room.remoteParticipants.values.forEach { self.room(room, participantDidConnect: $0) }
    }

    func roomIsReconnecting(_ room: Room) {
        log.warning("🔄 roomIsReconnecting")
        connectionState = .reconnecting
    }

    func roomDidReconnect(_ room: Room) {
        log.info("✅ roomDidReconnect — room: \(room.name ?? "?")")
        connectionState = .connected
        // Resume reconnect doesn't re-fire participantDidConnect for existing participants — sync manually
        room.remoteParticipants.values.forEach { self.room(room, participantDidConnect: $0) }
    }

    func room(_ room: Room, didFailToConnectWithError error: LiveKitError?) {
        log.error("❌ didFailToConnect: \(error?.localizedDescription ?? "unknown")")
        connectionState = .failed
        postErrorEvent(code: "FAILED_TO_CONNECT", message: error?.localizedDescription ?? "Failed to connect")
        Task { await cleanup() }
    }

    func room(_ room: Room, didDisconnectWithError error: LiveKitError?) {
        if let error {
            log.error("❌ didDisconnect with error: \(error.localizedDescription)")
        } else {
            log.info("🔴 didDisconnect (clean)")
        }
        connectionState = .disconnected
        Task { await cleanup() }
    }

    func room(_ room: Room, participantDidConnect participant: RemoteParticipant) {
        let isAgent = participant.kind == .agent
        log.info("👤 participantDidConnect — identity: \(participant.identity?.stringValue ?? "?") isAgent: \(isAgent)")
        if !isAudioEnabled { setAudioEnabled(false) }
        postEvent(AgentEventMsg(
            cmd: AgentEventConstants.msgParticipantEvent,
            n1: 0,
            n2: isAgent ? 1 : 0,
            s1: "joined",
            s2: participant.sid?.stringValue ?? "",
            s3: participant.identity?.stringValue ?? "",
            s4: participant.name ?? ""
        ))
    }

    func room(_ room: Room, participantDidDisconnect participant: RemoteParticipant) {
        let isAgent = participant.kind == .agent
        log.info("👤 participantDidDisconnect — identity: \(participant.identity?.stringValue ?? "?") isAgent: \(isAgent)")
        postEvent(AgentEventMsg(
            cmd: AgentEventConstants.msgParticipantEvent,
            n1: 0, n2: 0,
            s1: "left",
            s2: participant.sid?.stringValue ?? "",
            s3: participant.identity?.stringValue ?? "",
            s4: participant.name ?? ""
        ))
    }

    func room(_ room: Room, participant: RemoteParticipant, didSubscribeTrack publication: RemoteTrackPublication) {
        if publication.kind == .video,
           let videoTrack = publication.track as? RemoteVideoTrack {
            currentIngressTrack = videoTrack
            let renderers = renderersLock.withLock { previewRenderers }
            DispatchQueue.main.async {
                renderers.forEach { $0.videoView.track = videoTrack }
            }
            return
        }
        guard publication.kind == .audio else { return }
        if !isSpeakerEnabled, let audioTrack = publication.track as? RemoteAudioTrack {
            audioTrack.volume = 0.0
        }
        postEvent(AgentEventMsg(
            cmd: AgentEventConstants.msgAudioTrackEvent,
            n1: 0,
            n2: publication.isMuted ? 1 : 0,
            s1: "subscribed",
            s2: participant.sid?.stringValue ?? "",
            s3: publication.sid.stringValue
        ))
    }

    func room(_ room: Room, participant: RemoteParticipant, didUnsubscribeTrack publication: RemoteTrackPublication) {
        if publication.kind == .video {
            currentIngressTrack = nil
            let renderers = renderersLock.withLock { previewRenderers }
            DispatchQueue.main.async { renderers.forEach { $0.videoView.track = nil } }
            return
        }
        guard publication.kind == .audio else { return }
        postEvent(AgentEventMsg(
            cmd: AgentEventConstants.msgAudioTrackEvent,
            n1: 0, n2: 1,
            s1: "unsubscribed",
            s2: participant.sid?.stringValue ?? "",
            s3: publication.sid.stringValue
        ))
    }

    func room(_ room: Room, participant: Participant, trackPublication: TrackPublication, didUpdateIsMuted isMuted: Bool) {
        guard trackPublication.kind == .audio else { return }
        let isLocal = participant.identity?.stringValue == room.localParticipant.identity?.stringValue
        postEvent(AgentEventMsg(
            cmd: AgentEventConstants.msgAudioTrackEvent,
            n1: isLocal ? 1 : 0,
            n2: isMuted ? 1 : 0,
            s1: isMuted ? "muted" : "unmuted",
            s2: participant.sid?.stringValue ?? "",
            s3: trackPublication.sid.stringValue
        ))
    }

    func room(_ room: Room, didUpdateSpeakingParticipants participants: [Participant]) {
        let arr: [[String: Any]] = participants.map { p in [
            "participantId": p.sid?.stringValue ?? "",
            "identity": p.identity?.stringValue ?? "",
            "name": p.name ?? "",
            "audioLevel": p.audioLevel,
            "isAgent": p.kind == .agent
        ]}
        guard let data = try? JSONSerialization.data(withJSONObject: arr),
              let json = String(data: data, encoding: .utf8) else { return }
        postEvent(AgentEventMsg(cmd: AgentEventConstants.msgActiveSpeakers, s1: json))
    }

    func room(_ room: Room, participant: RemoteParticipant?, didReceiveData data: Data, forTopic topic: String, encryptionType: EncryptionType) {
        let base64 = data.base64EncodedString()
        postEvent(AgentEventMsg(
            cmd: AgentEventConstants.msgDataEvent,
            s1: participant?.sid?.stringValue ?? "",
            s2: base64,
            s3: topic
        ))
    }

    func room(_ room: Room, participant: Participant, trackPublication: TrackPublication, didReceiveTranscriptionSegments segments: [TranscriptionSegment]) {
        let isUser = participant.identity?.stringValue == room.localParticipant.identity?.stringValue
        for segment in segments {
            let t = ISO8601DateFormatter().string(from: Date()).suffix(12)
            log.info("📝 [\(t)] TEXT id=\(segment.id.suffix(6)) isUser=\(isUser) isFinal=\(segment.isFinal) text=\"\(segment.text.prefix(60))\"")
            postEvent(AgentEventMsg(
                cmd: AgentEventConstants.msgTextEvent,
                n1: segment.isFinal ? 1 : 0,
                n2: isUser ? 1 : 0,
                n3: 1,
                s1: segment.id,
                s2: segment.text,
                s3: segment.id
            ))
        }
    }
}
