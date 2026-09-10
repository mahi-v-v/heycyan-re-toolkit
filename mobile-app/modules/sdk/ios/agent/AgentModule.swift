import ExpoModulesCore

/// Expo module for voice agent control (mirrors AgentModule.kt)
/// Thin layer that forwards calls to AgentManager and emits events to JS.
public class AgentModule: Module {

    private var notificationObserver: NSObjectProtocol?
    private var glassNotificationObserver: NSObjectProtocol?
    private var wakeupInactivityTask: Task<Void, Never>?
    private var isWakeupSessionActive = false
    private let wakeupTimeoutSeconds: Double = 10

    public func definition() -> ModuleDefinition {
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
            SdkConfig.shared.initialize()
            ApiClient.shared.initialize(baseUrl: SdkConfig.shared.apiUrl)
            AgentManager.shared.initialize()
            ToolManager.shared.initialize()

            self.notificationObserver = NotificationCenter.default.addObserver(
                forName: .agentEvent,
                object: nil,
                queue: .main
            ) { [weak self] notification in
                guard let self, let msg = notification.userInfo?["msg"] as? AgentEventMsg else { return }
                // Inactivity auto-off handled by backend (exit_voice_mode) — app-side activity tracking disabled.
                // if self.isWakeupSessionActive {
                //     if msg.cmd == AgentEventConstants.msgActiveSpeakers,
                //        let s = msg.s1, !s.isEmpty, s != "[]" {
                //         self.resetWakeupTimer()
                //     } else if msg.cmd == AgentEventConstants.msgTextEvent {
                //         self.resetWakeupTimer()
                //     }
                // }
                self.emitAgentEvent(msg)
            }

            self.glassNotificationObserver = NotificationCenter.default.addObserver(
                forName: .glassEvent,
                object: nil,
                queue: .main
            ) { [weak self] notification in
                guard let msg = notification.userInfo?["msg"] as? GlassEventMsg else { return }
                switch msg.cmd {
                case GlassEventConstants.msgVoiceWakeup: self?.handleVoiceWakeup()
                case GlassEventConstants.msgVoiceStop:   self?.handleVoiceWakeupEnd()
                default: break
                }
            }
        }

        OnDestroy {
            if let observer = self.notificationObserver {
                NotificationCenter.default.removeObserver(observer)
                self.notificationObserver = nil
            }
            if let observer = self.glassNotificationObserver {
                NotificationCenter.default.removeObserver(observer)
                self.glassNotificationObserver = nil
            }
            self.wakeupInactivityTask?.cancel()
            self.isWakeupSessionActive = false
            AgentManager.shared.destroy()
        }

        // iOS has no OnActivityEntersForeground/Background.
        // The CameraTool accesses the root UIViewController directly via UIWindowScene.

        AsyncFunction("start") { (audio: Bool, promise: Promise) in
            Task {
                AgentManager.shared.start(audio: audio)
                promise.resolve(nil)
            }
        }

        AsyncFunction("stop") { (promise: Promise) in
            Task {
                AgentManager.shared.stop()
                promise.resolve(nil)
            }
        }

        AsyncFunction("sendText") { (text: String, promise: Promise) in
            Task {
                AgentManager.shared.sendText(text)
                promise.resolve(nil)
            }
        }

        AsyncFunction("sendFile") { (path: String, promise: Promise) in
            Task {
                do {
                    try await AgentManager.shared.sendFile(path)
                    promise.resolve(nil)
                } catch {
                    promise.reject(error)
                }
            }
        }

        AsyncFunction("sendData") { (data: Data, reliable: Bool, promise: Promise) in
            Task {
                AgentManager.shared.sendData(data, reliable: reliable)
                promise.resolve(nil)
            }
        }

        AsyncFunction("setMicEnabled") { (enabled: Bool, promise: Promise) in
            Task {
                AgentManager.shared.setMicEnabled(enabled)
                promise.resolve(nil)
            }
        }

        AsyncFunction("setSpeakerEnabled") { (enabled: Bool, promise: Promise) in
            Task {
                AgentManager.shared.setSpeakerEnabled(enabled)
                promise.resolve(nil)
            }
        }

        AsyncFunction("toggleAudio") { (promise: Promise) in
            Task {
                AgentManager.shared.toggleAudio()
                promise.resolve(nil)
            }
        }

        AsyncFunction("setAudioEnabled") { (enabled: Bool, promise: Promise) in
            Task {
                AgentManager.shared.setAudioEnabled(enabled)
                promise.resolve(nil)
            }
        }

        AsyncFunction("getConnectionState") { () -> String in
            return AgentManager.shared.getConnectionState().rawValue
        }

        AsyncFunction("getMicEnabled") { () -> Bool in
            return AgentManager.shared.getMicEnabled()
        }

        AsyncFunction("getSpeakerEnabled") { () -> Bool in
            return AgentManager.shared.getSpeakerEnabled()
        }

        AsyncFunction("getRoomId") { () -> String? in
            return AgentManager.shared.getRoomId()
        }

        AsyncFunction("startApp") { (appId: String, promise: Promise) in
            Task {
                AgentManager.shared.startApp(appId: appId)
                promise.resolve(nil)
            }
        }

        AsyncFunction("reloadTools") { (promise: Promise) in
            Task {
                AgentManager.shared.reloadTools()
                promise.resolve(nil)
            }
        }

        AsyncFunction("sendFiles") { (urls: [String], message: String, promise: Promise) in
            Task {
                do {
                    try await AgentManager.shared.sendFiles(urls, message: message)
                    promise.resolve(nil)
                } catch {
                    promise.reject(error)
                }
            }
        }

        AsyncFunction("performRpc") { (method: String, payload: String, promise: Promise) in
            Task {
                do {
                    let result = try await AgentManager.shared.performRpc(method: method, payload: payload)
                    promise.resolve(result)
                } catch {
                    promise.reject(error)
                }
            }
        }

        AsyncFunction("registerRpcMethod") { (method: String, promise: Promise) in
            Task {
                do {
                    try await AgentManager.shared.registerRpcMethod(method)
                    promise.resolve(nil)
                } catch {
                    promise.reject(error)
                }
            }
        }

        AsyncFunction("setCookie") { (cookie: String, promise: Promise) in
            AgentManager.shared.setCookie(cookie)
            promise.resolve(true)
        }

        AsyncFunction("setAgentName") { (name: String, promise: Promise) in
            AgentManager.shared.setAgentName(name.isEmpty ? nil : name)
            promise.resolve(nil)
        }

        AsyncFunction("startPhoneStream") { (fps: Int, bitrate: Int, promise: Promise) in
            Task {
                do {
                    try await AgentManager.shared.startPhoneStream(fps: fps, bitrate: bitrate)
                    promise.resolve(nil)
                } catch {
                    promise.reject("START_PHONE_STREAM_FAILED", error.localizedDescription)
                }
            }
        }

        AsyncFunction("stopPhoneStream") { (promise: Promise) in
            AgentManager.shared.stopPhoneStream()
            promise.resolve(nil)
        }

        AsyncFunction("flipPhoneCamera") { (promise: Promise) in
            AgentManager.shared.flipPhoneCamera()
            promise.resolve(nil)
        }

        AsyncFunction("isPhoneStreaming") { () -> Bool in
            return AgentManager.shared.isPhoneStreaming()
        }

        AsyncFunction("startGlassStream") { (ssid: String, pwd: String, fps: Int, bitrate: Int, promise: Promise) in
            Task {
                do {
                    try await AgentManager.shared.startGlassStream(ssid: ssid, pwd: pwd, fps: fps, bitrate: bitrate)
                    promise.resolve(nil)
                } catch {
                    promise.reject("START_GLASS_STREAM_FAILED", error.localizedDescription)
                }
            }
        }

        AsyncFunction("stopGlassStream") { (promise: Promise) in
            AgentManager.shared.stopGlassStream()
            promise.resolve(nil)
        }

        AsyncFunction("isGlassStreaming") { () -> Bool in
            return AgentManager.shared.isGlassStreaming()
        }
    }

    // MARK: - Voice wakeup (mirrors AgentModule.kt handleVoiceWakeup / resetWakeupTimer)

    private func handleVoiceWakeup() {
        guard AgentManager.shared.getConnectionState() == .connected else { return }

        // Idempotent guard: ignore the wakeup whenever voice is already on — covers repeated
        // aiDialogue=true frames and the speaking_start echo, so we never re-send "hey" / re-enable.
        if AgentManager.shared.getMicEnabled() {
            return
        }

        AgentManager.shared.sendText("hey")

        if !AgentManager.shared.getMicEnabled() {
            AgentManager.shared.setAudioEnabled(true)
        }

        isWakeupSessionActive = true
        // Inactivity auto-off handled by backend (exit_voice_mode) — app-side timer disabled.
        // resetWakeupTimer()
    }

    /// Glasses' button turned voice mode OFF (device AI-dialogue falling edge) → turn off the
    /// phone-side voice and cancel the wakeup inactivity timer.
    private func handleVoiceWakeupEnd() {
        wakeupInactivityTask?.cancel()
        wakeupInactivityTask = nil
        isWakeupSessionActive = false
        if AgentManager.shared.getMicEnabled() {
            AgentManager.shared.setAudioEnabled(false)
        }
    }

    private func resetWakeupTimer() {
        wakeupInactivityTask?.cancel()
        wakeupInactivityTask = Task { [weak self] in
            guard let self else { return }
            try? await Task.sleep(for: .seconds(self.wakeupTimeoutSeconds))
            guard !Task.isCancelled else { return }
            AgentManager.shared.setAudioEnabled(false)
            self.isWakeupSessionActive = false
            self.wakeupInactivityTask = nil
        }
    }

    // MARK: - Event emission (mirrors AgentModule.kt emitEvent)

    private func emitAgentEvent(_ event: AgentEventMsg) {
        switch event.cmd {
        case AgentEventConstants.msgStateChanged:
            sendEvent("AGENT_STATE_CHANGED", ["state": event.s1 ?? ""])

        case AgentEventConstants.msgParticipantEvent:
            var data: [String: Any] = [
                "type": event.s1 ?? "",
                "participantId": event.s2 ?? "",
                "isSpeaking": event.n1 == 1,
                "isAgent": event.n2 == 1
            ]
            if let identity = event.s3 { data["identity"] = identity }
            if let name = event.s4     { data["name"] = name }
            sendEvent("AGENT_PARTICIPANT_EVENT", data)

        case AgentEventConstants.msgAudioTrackEvent:
            sendEvent("AGENT_AUDIO_TRACK_EVENT", [
                "type": event.s1 ?? "",
                "participantId": event.s2 ?? "",
                "trackId": event.s3 ?? "",
                "isLocal": event.n1 == 1,
                "isMuted": event.n2 == 1
            ])

        case AgentEventConstants.msgTextEvent:
            var data: [String: Any] = [
                "id": event.s1 ?? "",
                "text": event.s2 ?? "",
                "isFinal": event.n1 == 1,
                "isUser": event.n2 == 1,
                "isTranscription": event.n3 == 1
            ]
            if let seg = event.s3 { data["segmentId"] = seg }
            sendEvent("AGENT_TEXT_EVENT", data)

        case AgentEventConstants.msgDataEvent:
            var data: [String: Any] = [
                "participantId": event.s1 ?? "",
                "data": event.s2 ?? ""
            ]
            if let t = event.s3 { data["topic"] = t }
            sendEvent("AGENT_DATA_EVENT", data)

        case AgentEventConstants.msgError:
            sendEvent("AGENT_ERROR", [
                "code": event.s1 ?? "",
                "message": event.s2 ?? ""
            ])

        case AgentEventConstants.msgToolCall:
            sendEvent("AGENT_TOOL_CALL", [
                "toolName": event.s1 ?? "",
                "params": event.s2 ?? ""
            ])

        case AgentEventConstants.msgToolResult:
            sendEvent("AGENT_TOOL_RESULT", [
                "toolName": event.s1 ?? "",
                "success": event.n1 == 1,
                "result": event.s2 ?? ""
            ])

        case AgentEventConstants.msgActiveSpeakers:
            sendEvent("AGENT_ACTIVE_SPEAKERS", ["speakers": event.s1 ?? ""])

        case AgentEventConstants.msgToolExecuted:
            sendEvent("AGENT_TOOL_EXECUTED", ["data": event.s1 ?? ""])

        case AgentEventConstants.msgRpcMethodCalled:
            sendEvent("AGENT_RPC_METHOD_CALLED", [
                "method": event.s1 ?? "",
                "payload": event.s2 ?? ""
            ])

        default:
            break
        }
    }
}

