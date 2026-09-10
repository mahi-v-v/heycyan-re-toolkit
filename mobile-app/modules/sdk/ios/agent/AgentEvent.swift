import Foundation

// MARK: - Agent connection state (mirrors ConnectionState Kotlin enum)
enum AgentConnectionState: String, CaseIterable {
    case disconnected  = "DISCONNECTED"
    case connecting    = "CONNECTING"
    case connected     = "CONNECTED"
    case reconnecting  = "RECONNECTING"
    case failed        = "FAILED"
}

// MARK: - Event constants (mirrors AgentEventConstants Kotlin object)
struct AgentEventConstants {
    static let msgStateChanged      = 1001
    static let msgParticipantEvent  = 1002
    static let msgAudioTrackEvent   = 1003
    static let msgTextEvent         = 1004
    static let msgDataEvent         = 1005
    static let msgError             = 1006
    static let msgToolCall          = 1007
    static let msgToolResult        = 1008
    static let msgActiveSpeakers    = 1009
    static let msgToolExecuted      = 1010
    static let msgRpcMethodCalled   = 1011
}

// MARK: - Internal notification name (replaces EventBus)
extension Notification.Name {
    static let agentEvent = Notification.Name("GlassSdkAgentEvent")
}

// MARK: - Event message (mirrors AgentEventMsg Kotlin data class)
struct AgentEventMsg {
    let cmd: Int
    var n1: Int = 0
    var n2: Int = 0
    var n3: Int = 0
    var s1: String? = nil
    var s2: String? = nil
    var s3: String? = nil
    var s4: String? = nil

    init(cmd: Int, n1: Int = 0, n2: Int = 0, n3: Int = 0,
         s1: String? = nil, s2: String? = nil, s3: String? = nil, s4: String? = nil) {
        self.cmd = cmd
        self.n1 = n1; self.n2 = n2; self.n3 = n3
        self.s1 = s1; self.s2 = s2; self.s3 = s3; self.s4 = s4
    }
}

// MARK: - Token response (mirrors TokenResponse Kotlin data class)
struct TokenResponse: Codable {
    let token: String
    let url: String
    let ingressUrl: String?
    let streamKey: String?
}
