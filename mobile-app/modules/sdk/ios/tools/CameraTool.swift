import Foundation
import UIKit
import AVFoundation

/// Captures photos from phone camera or glass device (mirrors CameraTool.kt)
class CameraTool: NSObject, BaseTool, UIImagePickerControllerDelegate, UINavigationControllerDelegate {

    let toolName = "camera"
    let requiredPermissions = ["camera"]

    private var pickerContinuation: CheckedContinuation<UIImage, Error>?
    private let continuationLock = NSLock()

    func hasPermissions() -> Bool {
        // Glass capture uses the glasses' own camera over BLE — no phone camera permission needed.
        if GlassManager.shared.isConnected() { return true }
        return AVCaptureDevice.authorizationStatus(for: .video) == .authorized
    }

    func execute(params: [String: Any]) async throws -> String {
        let useGlass = GlassManager.shared.isConnected()
        let device = useGlass ? "glass" : (params["device"] as? String ?? "phone")

        // Video recording is glass-only; the phone path is a photo-only camera.
        let mode = params["mode"] as? String ?? "photo"
        if (mode == "start_video" || mode == "stop_video") && device != "glass" {
            return #"{"success":false,"error":"Video recording requires connected glasses"}"#
        }

        let captured: String
        switch device {
        case "glass": captured = try await captureWithGlass(params: params)
        case "phone": captured = try await captureWithPhone(params: params)
        default:      return #"{"success":false,"error":"Invalid device"}"#
        }
        return await addUploadedUrl(captured)
    }

    private struct PresignRequest: Encodable { let filename: String; let contentType: String }
    private struct PresignResponse: Codable { let url: String; let publicUrl: String }

    private func addUploadedUrl(_ jsonStr: String) async -> String {
        guard let data = jsonStr.data(using: .utf8),
              var obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              obj["success"] as? Bool == true,
              let path = obj["path"] as? String,
              obj["url"] == nil else {
            return jsonStr
        }
        do {
            let publicUrl = try await uploadCaptured(path: path)
            obj["url"] = publicUrl
            let out = try JSONSerialization.data(withJSONObject: obj)
            return String(data: out, encoding: .utf8) ?? jsonStr
        } catch {
            return jsonStr
        }
    }

    private func uploadCaptured(path: String) async throws -> String {
        let filename = (path as NSString).lastPathComponent
        let contentType = FileUtil.detectContentType(path: path)
        let presign: PresignResponse = try await ApiClient.shared.post(
            endpoint: "/user/presigned-url",
            body: PresignRequest(filename: filename, contentType: contentType)
        )
        let uploadParams: [String: Any] = [
            "filePath": path,
            "presignedUrl": presign.url,
            "url": presign.publicUrl,
            "contentType": contentType
        ]
        let uploadResult = try await S3UploadTool().execute(params: uploadParams)
        guard let ud = uploadResult.data(using: .utf8),
              let uo = try? JSONSerialization.jsonObject(with: ud) as? [String: Any],
              uo["success"] as? Bool == true else {
            throw NSError(domain: "CameraTool", code: -10,
                          userInfo: [NSLocalizedDescriptionKey: "Upload failed"])
        }
        return presign.publicUrl
    }

    private func captureWithGlass(params: [String: Any]) async throws -> String {
        // Video recording (glass-only). Recording is saved on the glasses and synced over Wi-Fi
        // later, so these just return a recording-state ack (no inline file/upload).
        switch params["mode"] as? String ?? "photo" {
        case "start_video":
            let r = try await GlassManager.shared.startVideo(id: nil, filename: nil)
            var obj: [String: Any] = ["success": r.success, "device": "glass", "mode": "video",
                                      "recording": r.success]
            if !r.success { obj["error"] = "Failed to start recording (code \(r.errorCode))" }
            let data = try JSONSerialization.data(withJSONObject: obj)
            return String(data: data, encoding: .utf8) ?? #"{"success":false,"error":"Encoding error"}"#
        case "stop_video":
            try await GlassManager.shared.stopVideo()
            let obj: [String: Any] = ["success": true, "device": "glass", "mode": "video",
                                      "recording": false]
            let data = try JSONSerialization.data(withJSONObject: obj)
            return String(data: data, encoding: .utf8) ?? #"{"success":false,"error":"Encoding error"}"#
        default:
            let filePath = try await GlassManager.shared.takeAiImage()
            let obj: [String: Any] = ["success": true, "device": "glass", "format": "file", "path": filePath]
            let data = try JSONSerialization.data(withJSONObject: obj)
            return String(data: data, encoding: .utf8) ?? #"{"success":false,"error":"Encoding error"}"#
        }
    }

    private func captureWithPhone(params: [String: Any]) async throws -> String {
        guard hasPermissions() else {
            return #"{"success":false,"error":"Camera permission not granted"}"#
        }

        let image = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<UIImage, Error>) in
            DispatchQueue.main.async {
                guard let rootVC = UIApplication.shared.connectedScenes
                    .compactMap({ $0 as? UIWindowScene })
                    .first?.windows.first?.rootViewController else {
                    continuation.resume(throwing: NSError(
                        domain: "CameraTool", code: -1,
                        userInfo: [NSLocalizedDescriptionKey: "No root view controller available"]))
                    return
                }
                self.continuationLock.withLock { self.pickerContinuation = continuation }
                let picker = UIImagePickerController()
                picker.sourceType = .camera
                picker.delegate = self
                rootVC.present(picker, animated: true)
            }
        }

        let quality = params["quality"] as? CGFloat ?? 0.85
        guard let data = image.jpegData(compressionQuality: quality) else {
            throw NSError(domain: "CameraTool", code: -2,
                          userInfo: [NSLocalizedDescriptionKey: "Failed to encode image"])
        }
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("camera_\(Int(Date().timeIntervalSince1970)).jpg")
        try data.write(to: url)
        let obj: [String: Any] = ["success": true, "device": "phone", "format": "file",
                                  "path": url.path, "size": data.count]
        let encoded = try JSONSerialization.data(withJSONObject: obj)
        return String(data: encoded, encoding: .utf8) ?? #"{"success":false,"error":"Encoding error"}"#
    }

    // MARK: - UIImagePickerControllerDelegate

    func imagePickerController(
        _ picker: UIImagePickerController,
        didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
    ) {
        picker.dismiss(animated: true)
        let cont = continuationLock.withLock { () -> CheckedContinuation<UIImage, Error>? in
            let c = pickerContinuation; pickerContinuation = nil; return c
        }
        if let image = info[.originalImage] as? UIImage {
            cont?.resume(returning: image)
        } else {
            cont?.resume(throwing: NSError(domain: "CameraTool", code: -3, userInfo: nil))
        }
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        picker.dismiss(animated: true)
        let cont = continuationLock.withLock { () -> CheckedContinuation<UIImage, Error>? in
            let c = pickerContinuation; pickerContinuation = nil; return c
        }
        cont?.resume(throwing: NSError(
            domain: "CameraTool", code: -4,
            userInfo: [NSLocalizedDescriptionKey: "Camera capture cancelled"]))
    }
}
