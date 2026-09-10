import Foundation

/// Abstract base class for all glass adapters (mirrors BaseGlassAdapter.kt)
/// Provides shared state management, event posting, and reconnection logic.
open class BaseGlassAdapter: NSObject, GlassAdapter {

    // MARK: - GlassAdapter protocol defaults (subclasses must override abstract ones)

    open var capabilities: [String: Bool] { [:] }

    open func initAdapter() async throws {
        fatalError("Subclass must implement initAdapter()")
    }

    open func connect(deviceId: String) async throws {
        fatalError("Subclass must implement connect(deviceId:)")
    }

    open func disconnect() async throws {
        fatalError("Subclass must implement disconnect()")
    }

    open func isConnected() -> Bool {
        fatalError("Subclass must implement isConnected()")
    }

    open func sendCommand(_ command: String, params: [String: Any] = [:]) async throws {}

    open func takePhoto(id: Int? = nil, filename: String? = nil) async throws -> PhotoResult {
        fatalError("Subclass must implement takePhoto()")
    }

    open func takeAiImage() async throws -> String {
        fatalError("Subclass must implement takeAiImage()")
    }

    open func startVideo(id: Int? = nil, filename: String? = nil) async throws -> VideoResult {
        fatalError("Subclass must implement startVideo()")
    }

    open func stopVideo() async throws {}

    open func startAudio(filename: String? = nil) async throws -> AudioResult {
        fatalError("Subclass must implement startAudio()")
    }

    open func stopAudio() async throws {}

    open func getMediaConfig() async throws -> MediaConfigData {
        fatalError("Subclass must implement getMediaConfig()")
    }

    open func setMediaConfig(_ config: [String: Any]) async throws -> Bool { return false }

    open func syncFiles() async throws {}

    open func cancelSync() async {}

    open func startStream(ssid: String, pwd: String, url: String, streamKey: String?, fps: Int, bitrate: Int) throws {}
    open func stopStream() {}

    // MARK: - Default implementations

    open func getStorageInfo() async throws -> GlassStorageInfo { return storageInfo }
    open func getSystemInfo() async throws -> GlassSystemInfo { return systemInfo }

    open func setDeviceName(_ name: String) async throws {}
    open func clearMedia() async throws {}
    open func reboot() async throws {}
    open func factoryReset() async throws {}
    open func setAiStatus(_ status: String) async throws {}

    open func startScan() async throws {
        throw NSError(
            domain: "GlassAdapter", code: -1,
            userInfo: [NSLocalizedDescriptionKey: "Scanning not supported for this adapter"]
        )
    }

    open func stopScan() async throws {
        throw NSError(
            domain: "GlassAdapter", code: -1,
            userInfo: [NSLocalizedDescriptionKey: "Scanning not supported for this adapter"]
        )
    }

    open func getMediaDirectory() -> String {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)
        let base = docs.first ?? URL(fileURLWithPath: NSTemporaryDirectory())
        return base.appendingPathComponent("GlassMedia").path
    }

    // MARK: - Shared state (guarded by NSLock for thread safety)

    private let stateLock = NSLock()
    private var _connectionState: GlassConnectionState = .idle

    var connectionState: GlassConnectionState {
        stateLock.withLock { _connectionState }
    }

    var currentDeviceId: String?
    var currentDeviceName: String?

    // MARK: - Reconnection

    var reconnectAttempts = 0
    let maxReconnectAttempts = 5
    let reconnectDelaysMs: [UInt64] = [1_000, 2_000, 4_000, 8_000, 16_000, 30_000]
    private var reconnectTask: Task<Void, Never>?

    // MARK: - Storage and system info cache

    private(set) var storageInfo: GlassStorageInfo = .empty
    private(set) var systemInfo: GlassSystemInfo = .empty

    // MARK: - Protected helpers for subclasses

    func updateState(_ newState: GlassConnectionState, reason: String? = nil) {
        let oldState = stateLock.withLock { () -> GlassConnectionState in
            let old = _connectionState
            if old != newState { _connectionState = newState }
            return old
        }
        guard oldState != newState else { return }
        postEvent(GlassEventMsg(
            cmd: GlassEventConstants.msgStateChanged,
            n1: GlassConnectionState.allCases.firstIndex(of: oldState) ?? 0,
            n2: GlassConnectionState.allCases.firstIndex(of: newState) ?? 0,
            s1: reason
        ))
    }

    func onDeviceFound(deviceId: String, deviceName: String, rssi: Int? = nil) {
        postEvent(GlassEventMsg(
            cmd: GlassEventConstants.msgDeviceFound,
            n1: rssi ?? 0,
            s1: deviceId,
            s2: deviceName
        ))
    }

    func onConnected() {
        reconnectAttempts = 0
        updateState(.connected, reason: "Connected successfully")
        postEvent(GlassEventMsg(
            cmd: GlassEventConstants.msgConnected,
            s1: currentDeviceId,
            s2: currentDeviceName
        ))
    }

    func onDisconnected(reason: String, wasExpected: Bool = false) {
        updateState(.idle, reason: reason)
        postEvent(GlassEventMsg(
            cmd: GlassEventConstants.msgDisconnected,
            n1: wasExpected ? 1 : 0,
            s1: currentDeviceId,
            s2: reason
        ))
    }

    func onReady() {
        postEvent(GlassEventMsg(cmd: GlassEventConstants.msgReady))
    }

    func onWearStatus(worn: Bool) {
        postEvent(GlassEventMsg(cmd: GlassEventConstants.msgWearStatus, n1: worn ? 1 : 0))
    }

    func onVoiceWakeup() {
        postEvent(GlassEventMsg(cmd: GlassEventConstants.msgVoiceWakeup))
    }

    /// Falling edge of the device AI-dialogue flag (e.g. the glasses' button turned voice mode OFF).
    /// Symmetric to onVoiceWakeup — lets the agent turn the phone-side voice mode off.
    func onVoiceWakeupEnd() {
        postEvent(GlassEventMsg(cmd: GlassEventConstants.msgVoiceStop))
    }

    func onSyncStarted() {
        postEvent(GlassEventMsg(cmd: GlassEventConstants.msgSyncStarted))
    }

    func onSyncProgress(current: Int, total: Int) {
        postEvent(GlassEventMsg(
            cmd: GlassEventConstants.msgSyncProgress,
            n1: current,
            n2: total
        ))
    }

    /// Emit a per-file synced event the moment a file is fully written to its final subdir, so
    /// the JS layer can index + thumbnail it incrementally instead of re-listing the media dir.
    func onFileSynced(path: String, type: String) {
        postEvent(GlassEventMsg(
            cmd: GlassEventConstants.msgSyncFile,
            s1: path,
            s2: type
        ))
    }

    func onSyncCompleted(totalFiles: Int, totalBytes: Int64, success: Bool, error: String? = nil) {
        postEvent(GlassEventMsg(
            cmd: GlassEventConstants.msgSyncCompleted,
            n1: totalFiles,
            n2: success ? 1 : 0,
            s1: String(totalBytes),
            s2: error
        ))
    }

    func onError(_ error: String, isFatal: Bool = false) {
        postEvent(GlassEventMsg(
            cmd: GlassEventConstants.msgError,
            n1: isFatal ? 1 : 0,
            s1: error
        ))
        if isFatal { updateState(.error, reason: error) }
    }

    func updateStorageInfo(_ info: GlassStorageInfo) {
        storageInfo = info
    }

    func updateSystemInfo(_ info: GlassSystemInfo) {
        systemInfo = info
    }

    func updateSystemInfoFields(
        firmwareVersion: String? = nil,
        appVersion: String? = nil,
        deviceModel: String? = nil,
        batteryLevel: Int? = nil,
        isCharging: Bool? = nil,
        batteryVoltage: Float? = nil,
        isReady: Bool? = nil
    ) {
        systemInfo = systemInfo.copy(
            firmwareVersion: firmwareVersion,
            appVersion: appVersion,
            deviceModel: deviceModel,
            batteryLevel: batteryLevel,
            isCharging: isCharging,
            batteryVoltage: batteryVoltage,
            isReady: isReady
        )
    }

    func cancelReconnection() {
        reconnectTask?.cancel()
        reconnectTask = nil
        reconnectAttempts = 0
    }

    // MARK: - Destroy

    open func destroy() {
        cancelReconnection()
        updateState(.idle, reason: "Destroyed")
        currentDeviceId = nil
        currentDeviceName = nil
        storageInfo = .empty
        systemInfo = .empty
    }

    // MARK: - Internal: Post via NotificationCenter (replaces EventBus)

    func postEvent(_ msg: GlassEventMsg) {
        NotificationCenter.default.post(
            name: .glassEvent,
            object: nil,
            userInfo: ["msg": msg]
        )
    }
}
