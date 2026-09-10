import Foundation

/// Protocol mirroring BaseTool.kt abstract class
protocol BaseTool: AnyObject {
    var toolName: String { get }
    var requiredPermissions: [String] { get }
    func execute(params: [String: Any]) async throws -> String
    func hasPermissions() -> Bool
}
