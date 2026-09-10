import Foundation

/// Uploads a file to S3 via presigned URL (mirrors S3UploadTool.kt)
class S3UploadTool: BaseTool {

    let toolName = "s3_upload"
    let requiredPermissions: [String] = []

    func hasPermissions() -> Bool { return true }

    func execute(params: [String: Any]) async throws -> String {
        guard let filePath = params["filePath"] as? String, !filePath.isEmpty else {
            return #"{"success":false,"error":"filePath is required"}"#
        }
        guard let presignedUrl = params["presignedUrl"] as? String, !presignedUrl.isEmpty else {
            return #"{"success":false,"error":"presignedUrl is required"}"#
        }
        let publicUrl = params["url"] as? String ?? ""
        let contentType = params["contentType"] as? String ?? FileUtil.detectContentType(path: filePath)

        guard FileManager.default.fileExists(atPath: filePath) else {
            let errObj: [String: Any] = ["success": false, "error": "File not found: \(filePath)"]
            let errData = (try? JSONSerialization.data(withJSONObject: errObj))
                .flatMap { String(data: $0, encoding: .utf8) }
            return errData ?? #"{"success":false,"error":"File not found"}"#
        }

        let fileUrl = URL(fileURLWithPath: filePath)
        let fileSize = (try? FileManager.default.attributesOfItem(atPath: filePath)[.size] as? Int64) ?? 0

        guard let url = URL(string: presignedUrl) else {
            return #"{"success":false,"error":"Invalid presigned URL"}"#
        }

        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")

        let (_, response) = try await URLSession.shared.upload(for: request, fromFile: fileUrl)
        guard let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode < 300 else {
            let code = (response as? HTTPURLResponse)?.statusCode ?? -1
            return #"{"success":false,"error":"Upload failed with HTTP \#(code)"}"#
        }

        let resultObj: [String: Any] = ["success": true, "url": publicUrl, "size": fileSize]
        let resultData = try JSONSerialization.data(withJSONObject: resultObj)
        return String(data: resultData, encoding: .utf8) ?? #"{"success":false,"error":"Encoding error"}"#
    }
}
