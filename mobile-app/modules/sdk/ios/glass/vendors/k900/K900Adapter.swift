#if !targetEnvironment(simulator)
import Foundation
import AVFoundation
import CoreBluetooth
import XingYiGlassesSDK

/// K900 smart glass adapter — wraps XingYiGlassesSDK (ObjC) in Swift async/await.
/// Mirrors K900Adapter.kt exactly.
class K900Adapter: BaseGlassAdapter {

    // MARK: - Capabilities

    override var capabilities: [String: Bool] {
        ["photo": true, "video": true, "audio": true, "aiImage": true, "sync": true, "qr": true]
    }

    // MARK: - Internal state

    /// Peripherals found during scanning, keyed by UUID string
    private var peripheralMap: [String: CBPeripheral] = [:]

    /// Device IDs already reported during this scan session — prevents duplicate DEVICE_FOUND events
    private var reportedDeviceIds: Set<String> = []

    /// Pending continuations for command responses, keyed by response cmd string
    private var pendingCommands: [String: CheckedContinuation<[AnyHashable: Any], Error>] = [:]
    private let commandLock = NSLock()

    /// Pending continuation for connect
    private var connectContinuation: CheckedContinuation<Void, Error>?

    /// Auto-connect state (used by connectWithAutoScan)
    private let autoConnectLock = NSLock()
    private var autoConnectTargetId: String?
    private var autoConnectContinuation: CheckedContinuation<Void, Error>?
    private var autoConnectTimeoutTask: Task<Void, Never>?

    /// File sync manager — created lazily per sync call
    private var syncManager: K900FileSyncManager?

    /// Heartbeat task (30-second interval)
    private var heartbeatTask: Task<Void, Never>?

    // MARK: - Init / Destroy

    override func initAdapter() async throws {
        setupCallbacks()
        XYBleManager.sharedInstance().xy_startLog(false)
    }

    override func destroy() {
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.routeChangeNotification, object: nil)
        heartbeatTask?.cancel()
        heartbeatTask = nil
        syncManager?.cancelSync()
        syncManager = nil
        XYBleManager.sharedInstance().xy_cancelAllConnect()
        peripheralMap.removeAll()
        commandLock.withLock {
            let err = NSError(domain: "K900Adapter", code: -99,
                userInfo: [NSLocalizedDescriptionKey: "Adapter destroyed"])
            pendingCommands.values.forEach { $0.resume(throwing: err) }
            pendingCommands.removeAll()
        }
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
            domain: "K900Adapter", code: -99,
            userInfo: [NSLocalizedDescriptionKey: "Adapter destroyed"]))
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
        updateState(.scanning)
        print("[K900Adapter] startScan — beginning BLE scan")
        setupDiscoverBlock()
        XYBleManager.sharedInstance().xy_startScanBleDevice()
    }

    /// Sets up XYBleManager.discoverDeviceBlock — shared by startScan() and connectWithAutoScan().
    private func setupDiscoverBlock() {
        XYBleManager.sharedInstance().discoverDeviceBlock = { [weak self] peripheral, _, rssi in
            guard let self else { return }
            let deviceId = peripheral.identifier.uuidString
            let deviceName = peripheral.name ?? "K900"
            self.peripheralMap[deviceId] = peripheral

            guard !self.reportedDeviceIds.contains(deviceId) else {
                print("[K900Adapter] discoverDeviceBlock — duplicate, skipping: \(deviceId)")
                // Still check auto-connect target even for duplicates (in case we missed it)
                self.checkAutoConnectTarget(deviceId: deviceId)
                return
            }
            self.reportedDeviceIds.insert(deviceId)
            print("[K900Adapter] discoverDeviceBlock — new device: \(deviceId) name=\(deviceName) rssi=\(rssi)")
            self.onDeviceFound(deviceId: deviceId, deviceName: deviceName, rssi: rssi.intValue)
            self.checkAutoConnectTarget(deviceId: deviceId)
        }
    }

    /// Called from discoverDeviceBlock — if this device matches the auto-connect target,
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
            XYBleManager.sharedInstance().xy_stopScanBleDevice()
            cont.resume(returning: ())
        }
    }

    /// Connects to a device by UUID, starting a background scan first if the peripheral
    /// is not already known from a prior scan in this session (used for auto-connect on
    /// app restart). Falls through to the normal connect() path once peripheral is found.
    func connectWithAutoScan(deviceId: String) async throws {
        // Fast path: peripheral already discovered this session
        if peripheralMap[deviceId] != nil {
            try await connect(deviceId: deviceId)
            return
        }

        print("[K900Adapter] connectWithAutoScan — starting background scan for \(deviceId)")
        updateState(.scanning)
        reportedDeviceIds.removeAll()
        setupDiscoverBlock()

        autoConnectLock.withLock { autoConnectTargetId = deviceId }
        XYBleManager.sharedInstance().xy_startScanBleDevice()

        // Wait for the target to appear (15s timeout)
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            autoConnectLock.withLock { autoConnectContinuation = cont }
            let timeoutTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: 15_000_000_000)
                guard !Task.isCancelled, let self else { return }
                var continuationToFail: CheckedContinuation<Void, Error>?
                autoConnectLock.withLock {
                    guard autoConnectTargetId != nil else { return } // already resolved
                    autoConnectTargetId = nil
                    continuationToFail = autoConnectContinuation
                    autoConnectContinuation = nil
                }
                if let c = continuationToFail {
                    XYBleManager.sharedInstance().xy_stopScanBleDevice()
                    c.resume(throwing: NSError(
                        domain: "K900Adapter", code: -3,
                        userInfo: [NSLocalizedDescriptionKey: "Auto-connect timeout: device not in range"]))
                }
            }
            autoConnectLock.withLock { autoConnectTimeoutTask = timeoutTask }
        }

        print("[K900Adapter] connectWithAutoScan — device found, connecting")
        try await connect(deviceId: deviceId)
    }

    override func stopScan() async throws {
        print("[K900Adapter] stopScan")
        XYBleManager.sharedInstance().xy_stopScanBleDevice()
        reportedDeviceIds.removeAll()
        if connectionState == .scanning { updateState(.idle) }
    }

    // MARK: - Connection

    override func connect(deviceId: String) async throws {
        guard let peripheral = peripheralMap[deviceId] else {
            throw NSError(domain: "K900Adapter", code: -1,
                userInfo: [NSLocalizedDescriptionKey: "Device not found — call startScan() first"])
        }

        updateState(.connecting)
        currentDeviceId = deviceId
        currentDeviceName = peripheral.name ?? "K900"

        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            connectContinuation = cont
            XYBleManager.sharedInstance().xy_connectBleDevice(peripheral)
        }

    }

    override func disconnect() async throws {
        updateState(.disconnecting)
        heartbeatTask?.cancel()
        heartbeatTask = nil
        updateSystemInfoFields(isReady: false) // reset so onReady() fires again on reconnect
        XYBleManager.sharedInstance().xy_cancelAllConnect()
        onDisconnected(reason: "Disconnected by user", wasExpected: true)
    }

    override func isConnected() -> Bool {
        return XYBleManager.sharedInstance().xy_isConnectBleDevice()
    }

    // MARK: - Photo

    override func takePhoto(id: Int? = nil, filename: String? = nil) async throws -> PhotoResult {
        let ts = Int(Date().timeIntervalSince1970)
        let name = filename ?? "IMG_\(ts).jpg"
        let data = try await sendCommandAwaitResponse(cmd: "sr_pho") {
            XYBleManager.sharedInstance().sendTakePhoto(name)
        }
        let state = intValue(data["state"])
        if state != 0 {
            throw NSError(domain: "K900Adapter", code: state,
                userInfo: [NSLocalizedDescriptionKey: photoStateMessage(state)])
        }
        let photoCount = intValue(data["phonum"])
        return PhotoResult(success: true, errorCode: 0, photoCount: photoCount, filename: name)
    }

    // MARK: - AI Image

    override func takeAiImage() async throws -> String {
        let data = try await sendCommandAwaitResponse(cmd: "sr_aiph") {
            XYBleManager.sharedInstance().sendAiph()
        }
        return data["path"] as? String ?? ""
    }

    // MARK: - Video

    override func startVideo(id: Int? = nil, filename: String? = nil) async throws -> VideoResult {
        let ts = Int(Date().timeIntervalSince1970)
        let name = filename ?? "VID_\(ts).mp4"
        let data = try await sendCommandAwaitResponse(cmd: "sr_vdo") {
            XYBleManager.sharedInstance().sendVideoRecord(name)
        }
        let state = intValue(data["state"])
        // state 5 = recording started (normal response from glass)
        if state != 0 && state != 5 {
            throw NSError(domain: "K900Adapter", code: state,
                userInfo: [NSLocalizedDescriptionKey: photoStateMessage(state)])
        }
        let videoCount = intValue(data["vdonum"])
        return VideoResult(success: true, errorCode: 0, videoCount: videoCount)
    }

    override func stopVideo() async throws {
        XYBleManager.sharedInstance().sendStopVideoRecord()
    }

    // MARK: - Audio

    override func startAudio(filename: String? = nil) async throws -> AudioResult {
        let ts = Int(Date().timeIntervalSince1970)
        let name = filename ?? "AUD_\(ts).m4a"
        let data = try await sendCommandAwaitResponse(cmd: "sr_ado") {
            XYBleManager.sharedInstance().sendAudioRecord(name)
        }
        let state = intValue(data["state"])
        if state != 0 {
            throw NSError(domain: "K900Adapter", code: state,
                userInfo: [NSLocalizedDescriptionKey: photoStateMessage(state)])
        }
        let audioCount = intValue(data["adonum"])
        return AudioResult(success: true, errorCode: 0, audioCount: audioCount)
    }

    override func stopAudio() async throws {
        XYBleManager.sharedInstance().sendStopAudioRecord()
    }

    // MARK: - Storage / System Info

    override func getStorageInfo() async throws -> GlassStorageInfo {
        let data = try await sendCommandAwaitResponse(cmd: "sr_gtst") {
            XYBleManager.sharedInstance().sendGetStorageInfo()
        }
        return parseStorageInfo(data)
    }

    override func setDeviceName(_ name: String) async throws {
        // K900 exposes both names separately — set both so the device is renamed
        // consistently over BT Classic and BLE.
        XYBleManager.sharedInstance().sendSetBTName(name)
        XYBleManager.sharedInstance().sendSetBleName(name)
    }

    override func getSystemInfo() async throws -> GlassSystemInfo {
        let data = try await sendCommandAwaitResponse(cmd: "sr_syvr") {
            XYBleManager.sharedInstance().sendGetSystemVersion()
        }
        return GlassSystemInfo(
            firmwareVersion: data["sys"] as? String ?? "",
            appVersion: data["app"] as? String ?? "",
            deviceModel: "K900",
            batteryLevel: systemInfo.batteryLevel,
            isCharging: false,
            batteryVoltage: systemInfo.batteryVoltage,
            isReady: systemInfo.isReady
        )
    }

    // MARK: - Media Config

    override func getMediaConfig() async throws -> MediaConfigData {
        let data = try await sendCommandAwaitResponse(cmd: "sr_gmst") {
            XYBleManager.sharedInstance().sendGetMediaSettings()
        }
        let pw  = intValue(data["pw"])  != 0 ? intValue(data["pw"])  : 1920
        let ph  = intValue(data["ph"])  != 0 ? intValue(data["ph"])  : 1080
        let vw  = intValue(data["vw"])  != 0 ? intValue(data["vw"])  : 1920
        let vh  = intValue(data["vh"])  != 0 ? intValue(data["vh"])  : 1080
        let vq  = intValue(data["vq"])
        let vs  = intValue(data["vs"])  != 0 ? intValue(data["vs"])  : 60
        let aud = intValue(data["as"])  != 0 ? intValue(data["as"])  : 60
        return MediaConfigData(
            photo: PhotoConfigData(width: pw, height: ph),
            video: VideoConfigData(width: vw, height: vh, quality: vq, duration: vs),
            audio: AudioConfigData(duration: aud)
        )
    }

    override func setMediaConfig(_ config: [String: Any]) async throws -> Bool {
        if let photo = config["photo"] as? [String: Any] {
            let w = photo["width"] as? Int ?? 1920
            let h = photo["height"] as? Int ?? 1080
            XYBleManager.sharedInstance().sendSetPhotoParam(photoArgType(width: w, height: h))
        }
        if let video = config["video"] as? [String: Any] {
            let w = video["width"]    as? Int ?? 1920
            let h = video["height"]   as? Int ?? 1080
            let q = video["quality"]  as? Int ?? 1
            let d = video["duration"] as? Int ?? 60
            XYBleManager.sharedInstance().sendSetVideoParam(
                videoSizeType(width: w, height: h),
                videoArgumentQualityType: videoQualityType(q),
                videoArgumentDurationType: videoDurationType(d)
            )
        }
        if let audio = config["audio"] as? [String: Any] {
            let d = audio["duration"] as? Int ?? 60
            XYBleManager.sharedInstance().sendSetAudioParam(audioDurationType(d))
        }
        return true
    }

    // MARK: - File Sync

    override func syncFiles() async throws {
        let manager = K900FileSyncManager(adapter: self)
        syncManager = manager
        defer { syncManager = nil }
        try await manager.sync()
    }

    override func cancelSync() async {
        // No active sync — nothing to cancel.
        guard let manager = syncManager else { return }
        // Tears down the hotspot + resumes the sync's continuations with an error.
        manager.cancelSync()
        // The K900 sync flow doesn't emit a terminal event on cancel, so emit it here
        // to guarantee the JS onSyncCompleted(success:false) fires.
        onSyncCompleted(totalFiles: 0, totalBytes: 0, success: false, error: "Cancelled")
    }

    // MARK: - Stream

    override func startStream(ssid: String, pwd: String, url: String, streamKey: String?, fps: Int, bitrate: Int) throws {
        var body: [String: Any] = ["ssid": ssid, "pwd": pwd, "url": url, "fps": fps, "bitrate": bitrate]
        if let key = streamKey { body["streamKey"] = key }
        guard let bodyData = try? JSONSerialization.data(withJSONObject: body),
              let bodyStr = String(data: bodyData, encoding: .utf8),
              let packetData = try? JSONSerialization.data(withJSONObject: ["C": "startStream", "V": 1, "B": bodyStr] as [String: Any]),
              let json = String(data: packetData, encoding: .utf8) else { return }
        sendXyCommand(json)
    }

    override func stopStream() {
        sendXyCommand("{\"C\":\"stopStream\",\"V\":1,\"B\":\"\"}")
    }

    private func sendXyCommand(_ json: String) {
        guard let jsonData = json.data(using: .utf8) else { return }
        let len = jsonData.count
        var frame = Data([0x23, 0x23, 0x30, UInt8((len >> 8) & 0xFF), UInt8(len & 0xFF)])
        frame.append(jsonData)
        frame.append(contentsOf: [0x24, 0x24])
        XYBleManager.sharedInstance().sendCustomHexCode(frame.map { String(format: "%02X", $0) }.joined())
    }

    // MARK: - Custom Command (e.g., QR scan)

    override func sendCommand(_ command: String, params: [String: Any] = [:]) async throws {
        switch command {
        case "scanQr":
            XYBleManager.sharedInstance().sendCustomHexCode("scanQr")
        default:
            break
        }
    }

    // MARK: - Callback setup

    private func setupCallbacks() {
        XYBleManager.sharedInstance().connectStatusBlock = { [weak self] isConnected in
            guard let self else { return }
            print("[K900Adapter] connectStatusBlock isConnected=\(isConnected)")
            if isConnected {
                self.onConnected()
                self.startHeartbeat()
                self.startBtObserver()
                self.connectContinuation?.resume(returning: ())
                self.connectContinuation = nil

                Task {
                    try? await Task.sleep(nanoseconds: 1_000_000_000) // 1s for BLE stack to settle
                    print("[K900Adapter] connectStatusBlock — sending post-connect commands")
                    XYBleManager.sharedInstance().openHeart()
                    XYBleManager.sharedInstance().sendGetBatteryVol()
                    XYBleManager.sharedInstance().sendGetSystemVersion()
                    XYBleManager.sharedInstance().sendGetStorageInfo()
                }
            } else {
                let wasAttempting = self.connectContinuation != nil
                let err = NSError(domain: "K900Adapter", code: -2,
                    userInfo: [NSLocalizedDescriptionKey: "BLE connection lost"])
                self.connectContinuation?.resume(throwing: err)
                self.connectContinuation = nil

                if wasAttempting {
                    // Connection attempt failed — reset state from .connecting back to .idle
                    print("[K900Adapter] Connection attempt failed — resetting to idle")
                    self.updateState(.idle)
                } else if self.connectionState == .connected {
                    // Unexpected drop from an established connection
                    print("[K900Adapter] Connection lost — triggering disconnect")
                    self.heartbeatTask?.cancel()
                    self.updateSystemInfoFields(isReady: false) // reset so onReady() fires on reconnect
                    self.onDisconnected(reason: "Connection lost", wasExpected: false)
                }
            }
        }

        XYBleManager.sharedInstance().receiveDataBlock = { [weak self] respondModel in
            guard let self else { return }
            let cmd = respondModel.cmd ?? ""
            let data = (respondModel.data as? [AnyHashable: Any]) ?? [:]

            print("[K900Adapter] ← RECV cmd=\(cmd) data=\(data)")
            
            self.handleResponse(cmd: cmd, data: data)
        }
    }

    private func handleResponse(cmd: String, data: [AnyHashable: Any]) {
        switch cmd {
        case "sr_hrt":
            handleHeartbeat(data)

        case "sr_gtst":
            completeCommand(cmd: cmd, data: data)

        case "sr_syvr":
            completeCommand(cmd: cmd, data: data)

        case "sr_batv":
            handleHeartbeat(data)

        case "sr_btst":
            postEvent(GlassEventMsg(cmd: GlassEventConstants.msgBtStatus, n1: intValue(data["on"]) == 1 ? 1 : 0))

        case "sr_pho", "sr_vdo", "sr_ado", "sr_aiph", "sr_gmst":
            completeCommand(cmd: cmd, data: data)

        case "sr_apsr":
            // Convert AnyHashable keys to String keys for FileSyncManager
            let strData = data.reduce(into: [String: Any]()) { result, pair in
                if let key = pair.key as? String { result[key] = pair.value }
            }
            syncManager?.handleHotspotResult(strData)

        case "ss_avpg":
            let current = Int(data["current"] as? String ?? "0") ?? 0
            let total   = Int(data["total"]   as? String ?? "0") ?? 0
            onSyncProgress(current: current, total: total)

        case "sr_asfl":
            syncManager?.handleSyncComplete()

        case "sr_wrst":
            let worn = intValue(data["on"]) == 1
            onWearStatus(worn: worn)

        case "sr_ivww":
            onVoiceWakeup()

        case "sc_stream":
            let status = data["status"] as? String ?? ""
            let msg    = data["msg"]    as? String
            postEvent(GlassEventMsg(cmd: GlassEventConstants.msgStreamStatus, s1: status, s2: msg))

        default:
            break
        }
    }

    // MARK: - Async command bridge

    private func sendCommandAwaitResponse(
        cmd expectedCmd: String,
        timeout: UInt64 = 15_000_000_000,
        send: @escaping () -> Void
    ) async throws -> [AnyHashable: Any] {
        print("[K900Adapter] → SEND cmd=\(expectedCmd)")
        return try await withThrowingTaskGroupTimeout(nanoseconds: timeout) {
            try await withCheckedThrowingContinuation { continuation in
                self.commandLock.withLock {
                    self.pendingCommands[expectedCmd] = continuation
                }
                send()
            }
        }
    }

    func completeCommand(cmd: String, data: [AnyHashable: Any]) {
        print("[K900Adapter] ✓ COMPLETE cmd=\(cmd) data=\(data)")
        let continuation = commandLock.withLock { pendingCommands.removeValue(forKey: cmd) }
        continuation?.resume(returning: data)
    }

    // MARK: - BT Observer

    private func startBtObserver() {
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.routeChangeNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleAudioRouteChange),
            name: AVAudioSession.routeChangeNotification, object: nil)
    }

    @objc private func handleAudioRouteChange(_ notification: Notification) {
        XYBleManager.sharedInstance().sendGetBTConnectState()
    }

    // MARK: - Heartbeat

    private func startHeartbeat() {
        heartbeatTask?.cancel()
        heartbeatTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 30_000_000_000)
                guard !Task.isCancelled else { break }
                XYBleManager.sharedInstance().openHeart()
            }
        }
    }

    private func handleHeartbeat(_ data: [AnyHashable: Any]) {

        let battery = intValue(data["pt"])
        let voltage = floatValue(data["vt"])
        let ready   = intValue(data["ready"]) == 1
        let wasReady = systemInfo.isReady
        print("[K900Adapter] ← HEARTBEAT battery=\(battery) voltage=\(voltage / 1000.0) ready=\(ready)")
        updateSystemInfoFields(
            batteryLevel: battery,
            batteryVoltage: voltage / 1000.0,
            isReady: ready
        )
        postEvent(GlassEventMsg(
            cmd: GlassEventConstants.msgBattery,
            n1: battery,
            s1: String(voltage / 1000.0)
        ))
        if ready && !wasReady {
            onReady()
            // Refresh system info now that device is ready (mirrors Android behavior)
            // Note: storage info is NOT fetched here — the JS screen calls getStorageInfo()
            // directly. Calling it here too would race on the single commandContinuation.
            Task { [weak self] in
                guard let self else { return }
                try? await self.getSystemInfo()
            }
        }
    }

    // MARK: - Helpers

    private func parseStorageInfo(_ data: [AnyHashable: Any]) -> GlassStorageInfo {
        print("[K900Adapter] parseStorageInfo raw=\(data)")
        let total  = int64Value(data["total_size"])
        let free   = int64Value(data["free_size"])
        let phoCnt = intValue(data["phonum"])
        let vdoCnt = intValue(data["vdonum"])
        let adoCnt = intValue(data["adonum"])
        let info = GlassStorageInfo(
            totalSize: total, freeSize: free, usedSize: total - free,
            photoCount: phoCnt, videoCount: vdoCnt, audioCount: adoCnt
        )
        updateStorageInfo(info)
        postEvent(GlassEventMsg(
            cmd: GlassEventConstants.msgStorageInfo,
            n1: phoCnt, n2: vdoCnt,
            s1: String(total), s2: String(free)
        ))
        return info
    }

    // MARK: - Type-safe value helpers (SDK may return NSNumber or NSString for numeric fields)

    private func intValue(_ val: Any?) -> Int {
        if let n = val as? NSNumber { return n.intValue }
        if let s = val as? String   { return Int(s) ?? 0 }
        return 0
    }

    private func int64Value(_ val: Any?) -> Int64 {
        if let n = val as? NSNumber { return n.int64Value }
        if let s = val as? String   { return Int64(s) ?? 0 }
        return 0
    }

    private func floatValue(_ val: Any?) -> Float {
        if let n = val as? NSNumber { return n.floatValue }
        if let s = val as? String   { return Float(s) ?? 0 }
        return 0
    }

    private func photoStateMessage(_ state: Int) -> String {
        switch state {
        case 1: return "Uploading"
        case 2: return "Transferring"
        case 3: return "Error during operation"
        case 4: return "Unsupported operation"
        case 5: return "Flash error"
        case 6: return "No storage space"
        case 7: return "Connection lost"
        case 8: return "Device error"
        default: return "Unknown error (\(state))"
        }
    }

    private func photoArgType(width: Int, height: Int) -> XYPhotoArgumentType {
        switch (width, height) {
        case (1280, 720):  return XYPhotoArgumentType1280_720
        case (2560, 1920): return XYPhotoArgumentType2560_1920
        case (3840, 2160): return XYPhotoArgumentType3840_2160
        case (4208, 3120): return XYPhotoArgumentType4208_3120
        default:           return XYPhotoArgumentType1920_1080
        }
    }

    private func videoSizeType(width: Int, height: Int) -> XYVideoArgumentSizeType {
        switch (width, height) {
        case (1280, 720): return XYVideoArgumentSizeType1280_720
        case (640, 480):  return XYVideoArgumentSizeType640_480
        default:          return XYVideoArgumentSizeType1920_1080
        }
    }

    private func videoQualityType(_ q: Int) -> XYVideoArgumentQualityType {
        switch q {
        case 0:  return XYVideoArgumentQualityTypeLow
        case 2:  return XYVideoArgumentQualityTypeHigh
        default: return XYVideoArgumentQualityTypeMiddle
        }
    }

    private func videoDurationType(_ seconds: Int) -> XYVideoArgumentDurationType {
        switch seconds {
        case 30:  return XYVideoArgumentDurationType30
        case 180: return XYVideoArgumentDurationType180
        default:  return XYVideoArgumentDurationType60
        }
    }

    private func audioDurationType(_ seconds: Int) -> XYAudioArgumentDurationType {
        switch seconds {
        case 180:  return XYAudioArgumentDurationType180
        case 300:  return XYAudioArgumentDurationType300
        case 600:  return XYAudioArgumentDurationType600
        case 1800: return XYAudioArgumentDurationType1800
        case 3600: return XYAudioArgumentDurationType3600
        case 7200: return XYAudioArgumentDurationType7200
        default:   return XYAudioArgumentDurationType60
        }
    }
}
#endif // !targetEnvironment(simulator)
