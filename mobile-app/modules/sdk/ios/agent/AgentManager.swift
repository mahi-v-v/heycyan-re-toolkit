import Foundation
import LiveKitClient

/// Central manager for voice agent (mirrors AgentManager.kt)
/// Singleton — handles agent lifecycle, state management, and API routing.
class AgentManager {
    static let shared = AgentManager()
    private init() {}

    private let lock = NSLock()
    private var _agent: Agent?
    private var _agentName: String?
    private var _pendingRpcMethods: Set<String> = []

    // MARK: - Initialization

    func initialize() {
        lock.withLock {
            if _agent == nil {
                let agent = Agent()
                agent.seedRpcMethods(_pendingRpcMethods)
                _agent = agent
            }
        }
    }

    func registerRpcMethod(_ method: String) async throws {
        lock.withLock { _pendingRpcMethods.insert(method) }
        if let agent = lock.withLock({ _agent }) {
            try await agent.registerRpcMethod(method)
        }
    }

    // MARK: - Start / Stop

    func setAgentName(_ name: String?) {
        lock.withLock { _agentName = name }
    }

    func start(audio: Bool = true) {
        let agent = lock.withLock { _agent }
        let agentName = lock.withLock { _agentName }

        if agent?.connectionState == .connected {
            stop()
        }

        agent?.start(audio: audio, agentName: agentName)
    }

    func stop() {
        lock.withLock { _agent }?.stop()
    }

    // MARK: - Messaging

    func sendText(_ text: String) {
        lock.withLock { _agent }?.sendText(text)
    }

    func sendFile(_ path: String) async throws {
        try await lock.withLock { _agent }?.sendFile(path)
    }

    func sendData(_ data: Data, reliable: Bool = true) {
        lock.withLock { _agent }?.sendData(data, reliable: reliable)
    }

    // MARK: - Audio controls

    func setMicEnabled(_ enabled: Bool) {
        lock.withLock { _agent }?.setMicEnabled(enabled)
    }

    func setSpeakerEnabled(_ enabled: Bool) {
        lock.withLock { _agent }?.setSpeakerEnabled(enabled)
    }

    func toggleAudio() {
        lock.withLock { _agent }?.toggleAudio()
    }

    func setAudioEnabled(_ enabled: Bool) {
        lock.withLock { _agent }?.setAudioEnabled(enabled)
    }

    // MARK: - State getters

    func getConnectionState() -> AgentConnectionState {
        return lock.withLock { _agent }?.connectionState ?? .disconnected
    }

    func getMicEnabled() -> Bool {
        return lock.withLock { _agent }?.isMicEnabled ?? false
    }

    func getSpeakerEnabled() -> Bool {
        return lock.withLock { _agent }?.isSpeakerEnabled ?? false
    }

    func getRoomId() -> String? {
        return lock.withLock { _agent }?.getRoomId()
    }

    // MARK: - App session

    func reloadTools() {
        lock.withLock { _agent }?.reloadTools()
    }

    func startApp(appId: String) {
        lock.withLock { _agent }?.startApp(appId: appId)
    }

    func sendFiles(_ urls: [String], message: String) async throws {
        try await lock.withLock { _agent }?.sendFiles(urls, message: message)
    }

    func performRpc(method: String, payload: String) async throws -> String {
        guard let agent = lock.withLock({ _agent }) else {
            throw NSError(domain: "AgentManager", code: -1,
                userInfo: [NSLocalizedDescriptionKey: "Agent not initialized"])
        }
        return try await agent.performRpc(method: method, payload: payload)
    }

    // MARK: - Auth

    func setCookie(_ cookie: String) {
        lock.withLock { _agent }?.setCookie(cookie)
    }

    // MARK: - Phone camera streaming

    func startPhoneStream(fps: Int, bitrate: Int) async throws {
        guard let agent = lock.withLock({ _agent }) else {
            throw NSError(domain: "AgentManager", code: -1,
                userInfo: [NSLocalizedDescriptionKey: "Agent not initialized"])
        }
        try await agent.startPhoneStream(fps: fps, bitrate: bitrate)
    }

    func stopPhoneStream() {
        lock.withLock { _agent }?.stopPhoneStream()
    }

    func flipPhoneCamera() {
        lock.withLock { _agent }?.flipPhoneCamera()
    }

    func addPhonePreviewRenderer(_ view: VideoView) {
        lock.withLock { _agent }?.addPhonePreviewRenderer(view)
    }

    func removePhonePreviewRenderer(_ view: VideoView) {
        lock.withLock { _agent }?.removePhonePreviewRenderer(view)
    }

    func isPhoneStreaming() -> Bool {
        return lock.withLock { _agent }?.isPhoneStreaming() ?? false
    }

    // MARK: - Glass streaming

    private var glassStreaming = false

    func startGlassStream(ssid: String, pwd: String, fps: Int, bitrate: Int) async throws {
        guard let agent = lock.withLock({ _agent }) else {
            throw NSError(domain: "AgentManager", code: -1,
                userInfo: [NSLocalizedDescriptionKey: "Agent not initialized"])
        }
        let (ingressUrl, streamKey) = try agent.fetchStreamCredentials()
        glassStreaming = true
        try GlassManager.shared.startStream(ssid: ssid, pwd: pwd, url: ingressUrl,
                                             streamKey: streamKey, fps: fps, bitrate: bitrate)
    }

    func stopGlassStream() {
        glassStreaming = false
        GlassManager.shared.stopStream()
    }

    func isGlassStreaming() -> Bool { glassStreaming }

    func isGlassVideoStreamingActive() -> Bool {
        lock.withLock { _agent }?.currentIngressTrack != nil
    }

    func addGlassPreviewRenderer(_ view: GlassStreamRendering) {
        lock.withLock { _agent }?.addGlassPreviewRenderer(view)
    }

    func removeGlassPreviewRenderer(_ view: GlassStreamRendering) {
        lock.withLock { _agent }?.removeGlassPreviewRenderer(view)
    }

    // MARK: - Destroy

    func destroy() {
        stop()
    }
}
