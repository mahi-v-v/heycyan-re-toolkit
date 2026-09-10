import Foundation
import LiveKitClient

/// Manages all LiveKit RPC tools as a singleton (mirrors ToolManager.kt)
class ToolManager {
    static let shared = ToolManager()
    private init() {}

    private var tools: [String: any BaseTool] = [:]
    private let defaultTimeoutNs: UInt64 = 10_000_000_000 // 10 seconds

    func initialize() {
        guard tools.isEmpty else { return }
        let toolList: [any BaseTool] = [
            LocationTool(),
            ContactsTool(),
            CameraTool(),
            S3UploadTool(),
            QrTool(),
            HealthTool()
        ]
        toolList.forEach { tools[$0.toolName] = $0 }
    }

    /// Register all tools as LiveKit RPC methods on the connected Room.
    func registerWithLiveKit(room: Room) {
        tools.forEach { (name, tool) in
            Task {
                do {
                    try await room.registerRpcMethod(name) { [weak self] (data: RpcInvocationData) in
                        guard let self else {
                            return #"{"success":false,"error":"ToolManager deallocated"}"#
                        }
                        let timeoutNs = UInt64(data.responseTimeout * 1_000_000_000)
                        return await self.handleToolCall(
                            tool: tool,
                            payload: data.payload,
                            timeoutNs: timeoutNs
                        )
                    }
                } catch {
                    print("[ToolManager] Failed to register RPC method '\(name)': \(error)")
                }
            }
        }
    }

    /// Direct tool call (for testing or non-LiveKit invocation)
    func callTool(toolName: String, params: [String: Any], timeoutNs: UInt64? = nil) async -> String {
        guard let tool = tools[toolName] else {
            return "{\"success\":false,\"error\":\"Tool not found: \(toolName)\"}"
        }
        return await handleToolCall(tool: tool, params: params, timeoutNs: timeoutNs ?? defaultTimeoutNs)
    }

    func requestHealthPermissions() async -> Bool {
        guard let health = tools["getHealthData"] as? HealthTool else { return false }
        return await health.requestAuthorization()
    }

    func hasHealthPermissions() -> Bool {
        return (tools["getHealthData"] as? HealthTool)?.isConnected() ?? false
    }

    // MARK: - Internal

    func handleToolCall(tool: any BaseTool, payload: String, timeoutNs: UInt64) async -> String {
        guard let data = payload.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return #"{"success":false,"error":"Invalid payload"}"#
        }
        let params = json["params"] as? [String: Any] ?? json
        return await handleToolCall(tool: tool, params: params, timeoutNs: timeoutNs)
    }

    private func handleToolCall(tool: any BaseTool, params: [String: Any], timeoutNs: UInt64) async -> String {
        guard tool.hasPermissions() else {
            return "{\"success\":false,\"error\":\"Permission denied: \(tool.requiredPermissions.joined(separator: ", "))\"}"
        }
        do {
            return try await withThrowingTaskGroupTimeout(nanoseconds: timeoutNs) {
                try await tool.execute(params: params)
            }
        } catch {
            return "{\"success\":false,\"error\":\"\(error.localizedDescription)\"}"
        }
    }

    func cleanup() {
        tools.removeAll()
    }
}
