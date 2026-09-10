import Foundation
import UIKit

/// Triggers QR scan on glass device and awaits result (mirrors QrTool.kt)
class QrTool: BaseTool {

    let toolName = "scanQR"
    let requiredPermissions: [String] = []

    private static let scanTimeoutNs: UInt64 = 30_000_000_000 // 30 seconds

    func hasPermissions() -> Bool { return true }

    func execute(params: [String: Any]) async throws -> String {
        guard GlassManager.shared.isConnected() else {
            return #"{"success":false,"error":"Glass device not connected"}"#
        }

        return try await withThrowingTaskGroupTimeout(nanoseconds: QrTool.scanTimeoutNs) {
            try await withCheckedThrowingContinuation { continuation in
                var observer: NSObjectProtocol?
                observer = NotificationCenter.default.addObserver(
                    forName: .glassEvent,
                    object: nil,
                    queue: .main
                ) { notification in
                    guard let msg = notification.userInfo?["msg"] as? GlassEventMsg,
                          msg.cmd == GlassEventConstants.msgQrScanResult else { return }

                    if let obs = observer {
                        NotificationCenter.default.removeObserver(obs)
                        observer = nil
                    }

                    let success = msg.n1 == 1
                    let qrData = msg.s1 ?? ""

                    if success, qrData.lowercased().hasPrefix("upi://"),
                       let url = URL(string: qrData) {
                        DispatchQueue.main.async {
                            UIApplication.shared.open(url)
                        }
                    }

                    let responseObj: [String: Any]
                    if success {
                        responseObj = ["success": true, "data": qrData]
                    } else {
                        responseObj = ["success": false, "error": "Scan failed", "errorCode": msg.n2]
                    }
                    let responseData = (try? JSONSerialization.data(withJSONObject: responseObj))
                        .flatMap { String(data: $0, encoding: .utf8) }
                        ?? #"{"success":false,"error":"Encoding error"}"#
                    continuation.resume(returning: responseData)
                }

                Task {
                    try? await GlassManager.shared.sendCommand("scanQr", params: [:])
                }
            }
        }
    }
}

// MARK: - Timeout helper

func withThrowingTaskGroupTimeout<T>(
    nanoseconds timeout: UInt64,
    operation: @escaping () async throws -> T
) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask { try await operation() }
        group.addTask {
            try await Task.sleep(nanoseconds: timeout)
            throw NSError(
                domain: "Timeout", code: -1,
                userInfo: [NSLocalizedDescriptionKey: "Operation timed out"])
        }
        let result = try await group.next()!
        group.cancelAll()
        return result
    }
}
