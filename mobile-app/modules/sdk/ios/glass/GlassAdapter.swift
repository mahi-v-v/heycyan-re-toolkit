import Foundation

/// Protocol mirroring the GlassAdapter Kotlin interface
/// All async methods may throw errors
protocol GlassAdapter: AnyObject {
    var capabilities: [String: Bool] { get }

    func initAdapter() async throws
    func connect(deviceId: String) async throws
    func disconnect() async throws
    func isConnected() -> Bool
    func sendCommand(_ command: String, params: [String: Any]) async throws
    func getStorageInfo() async throws -> GlassStorageInfo
    func getSystemInfo() async throws -> GlassSystemInfo
    func setDeviceName(_ name: String) async throws
    func clearMedia() async throws
    func reboot() async throws
    func factoryReset() async throws
    func setAiStatus(_ status: String) async throws
    func startScan() async throws
    func stopScan() async throws
    func takePhoto(id: Int?, filename: String?) async throws -> PhotoResult
    func takeAiImage() async throws -> String
    func startVideo(id: Int?, filename: String?) async throws -> VideoResult
    func stopVideo() async throws
    func startAudio(filename: String?) async throws -> AudioResult
    func stopAudio() async throws
    func getMediaConfig() async throws -> MediaConfigData
    func setMediaConfig(_ config: [String: Any]) async throws -> Bool
    func syncFiles() async throws
    func cancelSync() async
    func getMediaDirectory() -> String
    func destroy()
}
