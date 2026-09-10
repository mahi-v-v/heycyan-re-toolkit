#if !targetEnvironment(simulator)
import Foundation
import CoreBluetooth
import QCSDK

/// Cyan (HeyCyan) glass adapter — wraps QCSDK (ObjC) in Swift async/await.
/// Mirrors CyanAdapter.kt exactly.
class CyanAdapter: BaseGlassAdapter {

    // MARK: - Capabilities

    override var capabilities: [String: Bool] {
        ["audio": true, "sensors": false, "display": true,
         "wakeword": true, "battery": true, "camera": true]
    }

    // MARK: - Internal state

    private let bleConnection = CyanBleConnection()

    /// Map of bare UUID → CBPeripheral found during the connect-time re-scan
    private var peripheralMap: [String: CBPeripheral] = [:]
    private var reportedDeviceIds: Set<String> = []

    /// Auto-connect state
    private let autoConnectLock = NSLock()
    private var autoConnectTargetId: String?
    private var autoConnectContinuation: CheckedContinuation<Void, Error>?
    private var autoConnectTimeoutTask: Task<Void, Never>?

    /// Pending continuation for takePhoto/startVideo/startAudio — keyed by media type
    private enum MediaCmd { case photo, video, audio }
    private var pendingMediaCmd: (MediaCmd, CheckedContinuation<[String: Any], Error>)?
    private let mediaLock = NSLock()

    /// Pending continuation for takeAiImage
    private var pendingAiImage: CheckedContinuation<String, Error>?
    private let aiLock = NSLock()

    /// File sync manager — created lazily per sync call
    private var syncManager: CyanFileSyncManager?

    // MARK: - Init / Destroy

    override func initAdapter() async throws {
        print("[CyanAdapter] initAdapter")
        QCSDKManager.shareInstance().delegate = self
        bleConnection.setup()

        bleConnection.onConnectStateChange = { [weak self] connected in
            guard let self else { return }
            print("[CyanAdapter] onConnectStateChange connected=\(connected) | currentState=\(self.connectionState)")
            if connected && self.connectionState != .connected {
                // Connection established — QCSDK may skip service re-discovery (iOS caches
                // services from prior sessions), so onServiceDiscovered may never fire.
                // Treat any state-change to connected as authoritative.
                print("[CyanAdapter] onConnectStateChange → onConnected() state was=\(self.connectionState)")
                self.bleConnection.stopScan()
                self.reportedDeviceIds.removeAll()
                self.onConnected()
                Task { self.requestInitialData() }
            } else if !connected && self.connectionState == .connected {
                print("[CyanAdapter] onConnectStateChange → onDisconnected (unexpected drop)")
                self.onDisconnected(reason: "BLE connection lost", wasExpected: false)
            }
        }

        bleConnection.onDeviceFound = { [weak self] deviceId, name, rssi in
            guard let self else { return }
            if let peripheral = self.bleConnection.discoveredPeripherals[deviceId] {
                self.peripheralMap[deviceId] = peripheral
            }
            guard !self.reportedDeviceIds.contains(deviceId) else { return }
            self.reportedDeviceIds.insert(deviceId)
            print("[CyanAdapter] initAdapter.onDeviceFound \(deviceId) name=\(name)")
            self.onDeviceFound(deviceId: deviceId, deviceName: name, rssi: rssi)
        }

        bleConnection.onDataUpdateReport = { [weak self] dict in
            guard let self else { return }
            let type = dict["type"] as? Int ?? -1
            print("[CyanAdapter] DataUpdateReport type=\(type)")
            if type == 3 { // 0x03 = voice wakeup (matches Android NOTIFY_MIC)
                self.onVoiceWakeup()
            }
        }
    }

    override func destroy() {
        print("[CyanAdapter] destroy — id=\(ObjectIdentifier(self))")
        syncManager?.cancelSync()
        syncManager = nil
        // Clean up any pending auto-connect state
        var pendingAutoConnect: CheckedContinuation<Void, Error>?
        autoConnectLock.withLock {
            autoConnectTimeoutTask?.cancel()
            autoConnectTimeoutTask = nil
            autoConnectTargetId = nil
            pendingAutoConnect = autoConnectContinuation
            autoConnectContinuation = nil
        }
        pendingAutoConnect?.resume(throwing: NSError(
            domain: "CyanAdapter", code: -99,
            userInfo: [NSLocalizedDescriptionKey: "Adapter destroyed"]))
        bleConnection.disconnect()
        aiLock.withLock {
            let err = NSError(domain: "CyanAdapter", code: -99,
                userInfo: [NSLocalizedDescriptionKey: "Adapter destroyed"])
            pendingAiImage?.resume(throwing: err)
            pendingAiImage = nil
        }
        mediaLock.withLock {
            let err = NSError(domain: "CyanAdapter", code: -99,
                userInfo: [NSLocalizedDescriptionKey: "Adapter destroyed"])
            pendingMediaCmd?.1.resume(throwing: err)
            pendingMediaCmd = nil
        }
        QCSDKManager.shareInstance().delegate = nil
        super.destroy()
    }

    // MARK: - Scanning

    override func startScan() async throws {
        // Cancel any in-progress auto-connect scan so it doesn't interfere with the manual scan
        var contToCancel: CheckedContinuation<Void, Error>?
        autoConnectLock.withLock {
            guard autoConnectTargetId != nil else { return }
            autoConnectTargetId = nil
            autoConnectTimeoutTask?.cancel()
            autoConnectTimeoutTask = nil
            contToCancel = autoConnectContinuation
            autoConnectContinuation = nil
        }
        contToCancel?.resume(throwing: CancellationError())

        reportedDeviceIds.removeAll()
        peripheralMap.removeAll()
        bleConnection.clearDiscoveredPeripherals()
        updateState(.scanning)
        print("[CyanAdapter] startScan")

        bleConnection.onDeviceFound = { [weak self] deviceId, name, rssi in
            guard let self else { return }
            if let peripheral = self.bleConnection.discoveredPeripherals[deviceId] {
                self.peripheralMap[deviceId] = peripheral
            }
            self.checkAutoConnectTarget(deviceId: deviceId)
            guard !self.reportedDeviceIds.contains(deviceId) else { return }
            self.reportedDeviceIds.insert(deviceId)
            print("[CyanAdapter] startScan.onDeviceFound \(deviceId) name=\(name)")
            self.onDeviceFound(deviceId: deviceId, deviceName: name, rssi: rssi)
        }

        try await bleConnection.startScan()
    }

    // MARK: - Auto-connect

    /// Called from onDeviceFound — if this device matches the auto-connect target,
    /// stops the scan and resolves the waiting continuation.
    private func checkAutoConnectTarget(deviceId: String) {
        var continuationToResume: CheckedContinuation<Void, Error>?
        autoConnectLock.withLock {
            guard let targetId = autoConnectTargetId, deviceId == targetId else { return }
            autoConnectTargetId = nil
            autoConnectTimeoutTask?.cancel()
            autoConnectTimeoutTask = nil
            continuationToResume = autoConnectContinuation
            autoConnectContinuation = nil
        }
        if let cont = continuationToResume {
            bleConnection.stopScan()
            cont.resume(returning: ())
        }
    }

    /// Connects to a device by ID, starting a background BLE scan first if the peripheral
    /// is not already known from a prior scan in this session (used for auto-connect on
    /// app restart). Falls through to the normal connect() path once peripheral is found.
    func connectWithAutoScan(deviceId: String) async throws {
        // Fast path: peripheral already discovered this session
        if peripheralMap[deviceId] != nil {
            try await connect(deviceId: deviceId)
            return
        }

        print("[CyanAdapter] connectWithAutoScan — starting background scan for \(deviceId)")
        updateState(.scanning)
        reportedDeviceIds.removeAll()
        peripheralMap.removeAll()
        bleConnection.clearDiscoveredPeripherals()

        bleConnection.onDeviceFound = { [weak self] foundId, name, rssi in
            guard let self else { return }
            if let peripheral = self.bleConnection.discoveredPeripherals[foundId] {
                self.peripheralMap[foundId] = peripheral
            }
            self.checkAutoConnectTarget(deviceId: foundId)
            guard !self.reportedDeviceIds.contains(foundId) else { return }
            self.reportedDeviceIds.insert(foundId)
            self.onDeviceFound(deviceId: foundId, deviceName: name, rssi: rssi)
        }

        autoConnectLock.withLock { autoConnectTargetId = deviceId }

        // Set up continuation first (before starting scan) to avoid race where device
        // is discovered before the continuation is registered.
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            autoConnectLock.withLock { autoConnectContinuation = cont }
            // Start scan in a Task (bleConnection.startScan is async; fire-and-forget here)
            Task { [weak self] in
                try? await self?.bleConnection.startScan()
            }
            let timeoutTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: 15_000_000_000)
                guard !Task.isCancelled, let self else { return }
                var continuationToFail: CheckedContinuation<Void, Error>?
                self.autoConnectLock.withLock {
                    guard self.autoConnectTargetId != nil else { return } // already resolved
                    self.autoConnectTargetId = nil
                    continuationToFail = self.autoConnectContinuation
                    self.autoConnectContinuation = nil
                }
                if let c = continuationToFail {
                    self.bleConnection.stopScan()
                    c.resume(throwing: NSError(
                        domain: "CyanAdapter", code: -3,
                        userInfo: [NSLocalizedDescriptionKey: "Auto-connect timeout: device not in range"]))
                }
            }
            autoConnectLock.withLock { autoConnectTimeoutTask = timeoutTask }
        }

        print("[CyanAdapter] connectWithAutoScan — device found, connecting")
        try await connect(deviceId: deviceId)
    }

    override func stopScan() async throws {
        print("[CyanAdapter] stopScan")
        bleConnection.stopScan()
        reportedDeviceIds.removeAll()
        if connectionState == .scanning { updateState(.idle) }
    }

    // MARK: - Connection

    override func connect(deviceId: String) async throws {
        print("[CyanAdapter] connect \(deviceId) | adapterId=\(ObjectIdentifier(self))")
        bleConnection.stopScan()
        updateState(.connecting)
        currentDeviceId = deviceId
        currentDeviceName = "Cyan Glass"

        // Only now hook up the connected callback — not during scan-only init
        bleConnection.onServiceDiscovered = { [weak self] in
            guard let self, self.connectionState != .connected else {
                // onConnectStateChange already called onConnected() — skip double-fire.
                print("[CyanAdapter] onServiceDiscovered — already connected, skipping")
                return
            }
            print("[CyanAdapter] onServiceDiscovered fired — adapterId=\(ObjectIdentifier(self)) | calling onConnected()")
            self.bleConnection.stopScan()
            self.reportedDeviceIds.removeAll()
            self.onConnected()
            Task { self.requestInitialData() }
        }

        // deviceId is the bare CBPeripheral UUID (vendor prefix dropped — vendor is a
        // separate field now). Get the CBPeripheral from the re-scan cache or QCCentralManager.
        guard let uuid = UUID(uuidString: deviceId) else {
            throw NSError(domain: "CyanAdapter", code: -1,
                userInfo: [NSLocalizedDescriptionKey: "Invalid device ID format"])
        }

        // Ask QCCentralManager to retrieve already-known peripherals or find via scan
        let peripheral: CBPeripheral
        if let cached = peripheralMap[deviceId] {
            peripheral = cached
        } else if let connected = QCCentralManager.shared().connectedPeripheral as CBPeripheral?,
                  connected.identifier == uuid {
            peripheral = connected
        } else {
            throw NSError(domain: "CyanAdapter", code: -2,
                userInfo: [NSLocalizedDescriptionKey: "Device not found — call startScan() first"])
        }
        // Use the real peripheral name from the scan if available
        if let peripheralName = peripheral.name, !peripheralName.isEmpty {
            currentDeviceName = peripheralName
        }
        print("[CyanAdapter] connect — calling bleConnection.connect(peripheral: \(peripheral.identifier))")

        // QCCentralManager.didConnectPeripheral internally calls
        // QCSDKManager.removePeripheral + addPeripheral:finished: and sets
        // deviceState = .connected — no need to call addPeripheral again.
        try await bleConnection.connect(peripheral: peripheral)
    }

    override func disconnect() async throws {
        print("[CyanAdapter] disconnect — adapterId=\(ObjectIdentifier(self))")
        updateState(.disconnecting)
        QCSDKManager.shareInstance().removeAllPeripheral()
        bleConnection.disconnect()
        onDisconnected(reason: "Disconnected by user", wasExpected: true)
    }

    override func isConnected() -> Bool {
        return QCCentralManager.shared().deviceState == .connected
    }

    // MARK: - Photo

    override func takePhoto(id: Int? = nil, filename: String? = nil) async throws -> PhotoResult {
        print("[CyanAdapter] takePhoto — sending photo mode command")
        let data = try await withMediaCommand(.photo, timeout: 30_000_000_000) {
            QCSDKCmdCreator.setDeviceMode(QCOperatorDeviceMode(rawValue: 0x01)!) {
                print("[CyanAdapter] takePhoto — setDeviceMode ack (success)")
            } fail: { code in
                print("[CyanAdapter] takePhoto — setDeviceMode fail, code=\(code)")
            }
        }
        let photoCount = data["photoCount"] as? Int ?? 0
        print("[CyanAdapter] takePhoto — resolved, photoCount=\(photoCount)")
        return PhotoResult(success: true, errorCode: 0, photoCount: photoCount, filename: nil)
    }

    // MARK: - AI Image

    override func takeAiImage() async throws -> String {
        print("[CyanAdapter] takeAiImage — sending aiPhoto mode command")
        return try await withThrowingTaskGroupTimeout(nanoseconds: 120_000_000_000) {
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<String, Error>) in
                self.aiLock.withLock { self.pendingAiImage = cont }
                // Step 1: trigger capture (mirrors CyanAdapter.kt two-command sequence)
                QCSDKCmdCreator.setDeviceMode(QCOperatorDeviceMode(rawValue: 0x06)!) {
                    print("[CyanAdapter] aiPhoto mode ack — waiting for image data")
                } fail: { code in
                    print("[CyanAdapter] aiPhoto mode ack error: \(code) (device still processing)")
                }
            }
        }
    }

    // MARK: - Video

    override func startVideo(id: Int? = nil, filename: String? = nil) async throws -> VideoResult {
        let data = try await withMediaCommand(.video, timeout: 30_000_000_000) {
            QCSDKCmdCreator.setDeviceMode(QCOperatorDeviceMode(rawValue: 0x02)!) { } fail: { _ in }
        }
        let videoCount = data["videoCount"] as? Int ?? 0
        return VideoResult(success: true, errorCode: 0, videoCount: videoCount)
    }

    override func stopVideo() async throws {
        QCSDKCmdCreator.setDeviceMode(QCOperatorDeviceMode(rawValue: 0x03)!) { } fail: { _ in }
    }

    // MARK: - Audio

    override func startAudio(filename: String? = nil) async throws -> AudioResult {
        // Audio recording starts on the setDeviceMode(.audio) success ack — the SDK does NOT
        // emit a media/file event until the recording is stopped, so we must resolve on the
        // command ack (mirrors the official QCSDKDemo `recordAudio`), not on didUpdateMedia.
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            QCSDKCmdCreator.setDeviceMode(.audio) {
                print("[CyanAdapter] startAudio — setDeviceMode(.audio) ack (success)")
                cont.resume()
            } fail: { code in
                print("[CyanAdapter] startAudio — setDeviceMode(.audio) fail, code=\(code)")
                cont.resume(throwing: NSError(domain: "CyanAdapter", code: code,
                    userInfo: [NSLocalizedDescriptionKey: "startAudio failed, current device mode=\(code)"]))
            }
        }
        return AudioResult(success: true, errorCode: 0, audioCount: storageInfo.audioCount)
    }

    override func stopAudio() async throws {
        QCSDKCmdCreator.setDeviceMode(.audioStop) {
            print("[CyanAdapter] stopAudio — setDeviceMode(.audioStop) ack (success)")
        } fail: { code in
            print("[CyanAdapter] stopAudio — setDeviceMode(.audioStop) fail, code=\(code)")
        }
    }

    // MARK: - Media Config

    override func getMediaConfig() async throws -> MediaConfigData {
        return try await withCheckedThrowingContinuation { cont in
            var videoAngle = 0, videoDuration = 0, audioAngle = 0, audioDuration = 0
            let group = DispatchGroup()

            group.enter()
            QCSDKCmdCreator.getVideoInfoSuccess({ angle, duration in
                videoAngle = angle; videoDuration = duration
                group.leave()
            }, fail: { group.leave() })

            group.enter()
            QCSDKCmdCreator.getAudioInfoSuccess({ angle, duration in
                audioAngle = angle; audioDuration = duration
                group.leave()
            }, fail: { group.leave() })

            group.notify(queue: .global()) {
                let config = MediaConfigData(
                    photo: PhotoConfigData(width: 0, height: 0),
                    video: VideoConfigData(width: 0, height: 0, quality: videoAngle, duration: videoDuration),
                    audio: AudioConfigData(duration: audioDuration)
                )
                cont.resume(returning: config)
            }
        }
    }

    override func setMediaConfig(_ config: [String: Any]) async throws -> Bool {
        if let video = config["video"] as? [String: Any] {
            let angle    = video["quality"]  as? Int ?? 0
            let duration = video["duration"] as? Int ?? 60
            QCSDKCmdCreator.setVideoInfo(angle, duration: duration, success: { }, fail: { })
        }
        if let audio = config["audio"] as? [String: Any] {
            let duration = audio["duration"] as? Int ?? 60
            QCSDKCmdCreator.setAudioInfo(0, duration: duration, success: { }, fail: { })
        }
        return true
    }

    // MARK: - Storage / System Info

    override func getStorageInfo() async throws -> GlassStorageInfo {
        return try await withCheckedThrowingContinuation { cont in
            QCSDKCmdCreator.getDeviceMedia({ photo, video, audio, total in
                let info = GlassStorageInfo(
                    totalSize: Int64(total), freeSize: 0, usedSize: 0,
                    photoCount: Int(photo), videoCount: Int(video), audioCount: Int(audio)
                )
                cont.resume(returning: info)
            }, fail: {
                cont.resume(throwing: NSError(domain: "CyanAdapter", code: -4,
                    userInfo: [NSLocalizedDescriptionKey: "getDeviceMedia failed"]))
            })
        }
    }

    override func getSystemInfo() async throws -> GlassSystemInfo {
        return try await withCheckedThrowingContinuation { cont in
            QCSDKCmdCreator.getDeviceVersionInfoSuccess({ hw, fw, wifiHw, wifiFw in
                let info = GlassSystemInfo(
                    firmwareVersion: fw ?? "",
                    appVersion: wifiFw ?? "",
                    deviceModel: "Cyan Glass",
                    batteryLevel: self.systemInfo.batteryLevel,
                    isCharging: self.systemInfo.isCharging,
                    batteryVoltage: self.systemInfo.batteryVoltage,
                    isReady: self.systemInfo.isReady
                )
                cont.resume(returning: info)
            }, fail: {
                cont.resume(throwing: NSError(domain: "CyanAdapter", code: -5,
                    userInfo: [NSLocalizedDescriptionKey: "getDeviceVersionInfo failed"]))
            })
        }
    }

    override func setDeviceName(_ name: String) async throws {
        // Not directly supported by QCSDK — no-op
        print("[CyanAdapter] setDeviceName not supported on Cyan")
    }

    // MARK: - Device Management

    override func clearMedia() async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            QCSDKCmdCreator.deleleteAllMediasSuccess({
                cont.resume()
            }, fail: {
                cont.resume(throwing: NSError(domain: "CyanAdapter", code: -20,
                    userInfo: [NSLocalizedDescriptionKey: "clearMedia failed"]))
            })
        }
        // Reflect the cleared storage locally so the UI updates immediately.
        updateStorageInfo(storageInfo.copy(photoCount: 0, videoCount: 0, audioCount: 0))
    }

    override func reboot() async throws {
        // Fire-and-forget; the connection drops as the device restarts.
        QCSDKCmdCreator.setDeviceMode(.restart) {
            print("[CyanAdapter] reboot — ack")
        } fail: { code in
            print("[CyanAdapter] reboot — fail, code=\(code)")
        }
    }

    override func factoryReset() async throws {
        // Fire-and-forget; the device resets and may unpair.
        QCSDKCmdCreator.setDeviceMode(.factoryReset) {
            print("[CyanAdapter] factoryReset — ack")
        } fail: { code in
            print("[CyanAdapter] factoryReset — fail, code=\(code)")
        }
    }

    override func setAiStatus(_ status: String) async throws {
        // Map the generic transition → QGAISpeakMode.
        let mode: QGAISpeakMode
        switch status {
        case "speaking_start": mode = .start
        case "speaking_stop":  mode = .stop
        case "thinking_start": mode = .thinkingStart
        case "thinking_stop":  mode = .thinkingStop
        default:
            print("[CyanAdapter] setAiStatus — unknown status \(status)")
            return
        }
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            QCSDKCmdCreator.setAISpeekModel(mode) { ok, error in
                if ok {
                    cont.resume()
                } else {
                    cont.resume(throwing: error ?? NSError(domain: "CyanAdapter", code: -21,
                        userInfo: [NSLocalizedDescriptionKey: "setAiStatus failed"]))
                }
            }
        }
    }

    // MARK: - File Sync

    override func syncFiles() async throws {
        let manager = CyanFileSyncManager(adapter: self) { [weak self] event in
            guard let self else { return }
            switch event {
            case .started:
                self.onSyncStarted()
            case .progress(let current, let total):
                self.onSyncProgress(current: current, total: total)
            case .fileSynced(let path, let type):
                self.onFileSynced(path: path, type: type)
            case .completed(let totalFiles, let totalBytes, let success, let error):
                self.onSyncCompleted(totalFiles: totalFiles, totalBytes: totalBytes, success: success, error: error)
            }
        }
        syncManager = manager
        defer { syncManager = nil }
        await manager.sync()
    }

    override func cancelSync() async {
        // Flip the cancel flag; the running sync() flow tears down the hotspot,
        // returns the device to capture mode, and emits .completed(success:false,"Cancelled").
        syncManager?.cancelSync()
    }

    // MARK: - sendCommand

    override func sendCommand(_ command: String, params: [String: Any] = [:]) async throws {
        print("[CyanAdapter] sendCommand not implemented: \(command)")
    }

    // MARK: - QCSDKManagerDelegate

    @objc func didUpdateBatteryLevel(_ battery: Int, charging: Bool) {
        print("[CyanAdapter] battery=\(battery) charging=\(charging)")
        updateSystemInfoFields(batteryLevel: battery, isCharging: charging)
        postEvent(GlassEventMsg(cmd: GlassEventConstants.msgBattery,
                                n1: battery,
                                n2: charging ? 1 : 0))
    }

    @objc func didUpdateMedia(withPhotoCount photo: Int, videoCount video: Int,
                              audioCount audio: Int, type: Int) {
        print("[CyanAdapter] didUpdateMedia photo=\(photo) video=\(video) audio=\(audio) type=\(type)")

        // Complete pending media command
        let result: [String: Any] = ["photoCount": photo, "videoCount": video, "audioCount": audio, "type": type]
        let pending = mediaLock.withLock { () -> (MediaCmd, CheckedContinuation<[String: Any], Error>)? in
            let p = pendingMediaCmd; pendingMediaCmd = nil; return p
        }
        if let (cmd, cont) = pending {
            print("[CyanAdapter] didUpdateMedia — resuming pending cmd=\(cmd)")
            cont.resume(returning: result)
        } else {
            print("[CyanAdapter] didUpdateMedia — no pending command (spurious callback)")
        }

        // Update storage info
        let info = GlassStorageInfo(totalSize: 0, freeSize: 0, usedSize: 0,
                                    photoCount: photo, videoCount: video, audioCount: audio)
        updateStorageInfo(info)
        postEvent(GlassEventMsg(cmd: GlassEventConstants.msgStorageInfo,
                                n1: photo, n2: video))
    }

    @objc func didReceiveAIChatImageData(_ imageData: Data) {
        print("[CyanAdapter] didReceiveAIChatImageData: \(imageData.count) bytes")

        let cont = aiLock.withLock { () -> CheckedContinuation<String, Error>? in
            let c = pendingAiImage; pendingAiImage = nil; return c
        }
        guard let cont else {
            print("[CyanAdapter] AI image received but no pending request — ignoring")
            return
        }

        // Validate JPEG
        guard !imageData.isEmpty else {
            cont.resume(throwing: NSError(domain: "CyanAdapter", code: -10,
                userInfo: [NSLocalizedDescriptionKey: "Received empty AI image data"]))
            return
        }
        let bytes = [UInt8](imageData.prefix(2))
        guard bytes.count >= 2, bytes[0] == 0xFF, bytes[1] == 0xD8 else {
            cont.resume(throwing: NSError(domain: "CyanAdapter", code: -11,
                userInfo: [NSLocalizedDescriptionKey: "Invalid AI image (not JPEG), size=\(imageData.count), header=\(bytes.map { String($0, radix: 16) })"]))
            return
        }

        // Save to cache
        let filename = "cyan_ai_\(Int(Date().timeIntervalSince1970 * 1000)).jpg"
        let dest = FileManager.default.temporaryDirectory.appendingPathComponent(filename)
        do {
            try imageData.write(to: dest)
            print("[CyanAdapter] AI image saved: \(dest.path)")
            cont.resume(returning: dest.path)
        } catch {
            cont.resume(throwing: error)
        }
    }

    // MARK: - Helpers

    private func requestInitialData() {
        QCSDKCmdCreator.getDeviceBattery({ [weak self] battery, charging in
            self?.didUpdateBatteryLevel(Int(battery), charging: charging)
        }, fail: { })
        QCSDKCmdCreator.getDeviceVersionInfoSuccess({ [weak self] _, fw, _, wifiFw in
            guard let self else { return }
            self.updateSystemInfoFields(firmwareVersion: fw, appVersion: wifiFw)
            self.onReady()
        }, fail: { [weak self] in
            self?.onReady()
        })
    }

    private func withMediaCommand(
        _ cmd: MediaCmd,
        timeout: UInt64,
        send: @escaping () -> Void
    ) async throws -> [String: Any] {
        print("[CyanAdapter] withMediaCommand — waiting for cmd=\(cmd), timeout=\(timeout/1_000_000_000)s")
        do {
            let result = try await withThrowingTaskGroupTimeout(nanoseconds: timeout) {
                try await withCheckedThrowingContinuation { cont in
                    self.mediaLock.withLock { self.pendingMediaCmd = (cmd, cont) }
                    send()
                }
            }
            return result
        } catch {
            print("[CyanAdapter] withMediaCommand — TIMED OUT or failed for cmd=\(cmd): \(error)")
            throw error
        }
    }
}

// MARK: - QCSDKManagerDelegate conformance

extension CyanAdapter: QCSDKManagerDelegate {}

#endif // !targetEnvironment(simulator)
