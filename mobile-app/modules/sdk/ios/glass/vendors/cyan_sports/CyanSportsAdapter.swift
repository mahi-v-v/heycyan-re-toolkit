#if !targetEnvironment(simulator)
import Foundation
import CoreBluetooth
import NetworkExtension
import CRPSmartGlasses

/// One-shot guard for CRP SDK handlers that may fire more than once (they are NOT guaranteed
/// single-shot), so we resume an `async` continuation at most once and avoid
/// "continuation resumed more than once" crashes.
private final class ResumeOnce {
    private let lock = NSLock()
    private var done = false
    func claim() -> Bool { lock.lock(); defer { lock.unlock() }; if done { return false }; done = true; return true }
}

/// cyan_sports (Moyoung) glass adapter — wraps the CRPSmartGlasses SDK (singleton + global
/// CRPManagerDelegate) in Swift async/await. Mirrors CyanSportsAdapter.kt / CyanAdapter.swift.
///
/// The CRP SDK is dynamic-only and links SwiftProtobuf/AFNetworking dynamically; those + the JL
/// support frameworks are vendored as device-only xcframeworks (see GlassSdk.podspec). On the
/// simulator this whole file is excluded and GlassManager uses MockAdapter.
class CyanSportsAdapter: BaseGlassAdapter, CRPManagerDelegate {

    // MARK: - Capabilities

    override var capabilities: [String: Bool] {
        ["audio": true, "sensors": false, "display": true,
         "wakeword": true, "battery": true, "camera": true, "video": true]
    }

    // MARK: - State

    private var sdk: CRPSmartGlassesSDK { CRPSmartGlassesSDK.sharedInstance }
    private var connected = false

    /// The CRP SDK is touched lazily — only on a JS-initiated connect. We never access
    /// `CRPSmartGlassesSDK.sharedInstance` (which creates its CBCentralManager and would let
    /// the SDK autonomously reconnect to a bonded device) until JS calls connect.
    private var sdkInitialized = false

    /// Discoveries seen during scan, keyed by CBPeripheral UUID (matches the JS deviceId).
    private var discoveryMap: [String: CRPDiscovery] = [:]

    /// Connect continuation + guard. Resolved by whichever brings the link up first —
    /// our scan→sdk.connect, or the SDK's own reconnect (surfaced via didState(.connected)).
    private let resumeLock = NSLock()
    private var autoConnectResumed = false
    private var pendingConnect: CheckedContinuation<Void, Error>?
    /// The deviceId of an in-flight JS-initiated connect. nil means "no connect requested" — any
    /// `didState(.connected)` while this is nil is an unsolicited SDK auto-reconnect and is refused.
    private var pendingConnectDeviceId: String?
    /// Bounded retry for a connect attempt that drops before completing (.connecting → .disconnected).
    /// Guarded by `resumeLock`; reset at the start of each connectWithAutoScan.
    private var connectRetries = 0
    private let maxConnectRetries = 3

    /// CRP central power state (set by didBluetoothState). The central is created lazily on the
    /// first connect and reaches .poweredOn asynchronously; scanning no-ops until then.
    private let btLock = NSLock()
    private var btPoweredOn = false

    /// AI image capture continuation (resolved by receiveAirecognitionImageData).
    private let aiLock = NSLock()
    private var pendingAiImage: CheckedContinuation<String, Error>?

    // File sync state
    private let syncLock = NSLock()
    private var syncActive = false
    private var syncCancelled = false
    private var syncSsid = ""
    private var syncPassword = ""
    private var fileBaseUrl: String?
    private var wifiJoined = false
    private var syncFiles_total = 0
    private var syncFiles_done = 0
    private var syncBytes: Int64 = 0

    /// HTTP session pinned to Wi-Fi for talking to the glasses' local AP. The AP has NO internet,
    /// so the default session (and `URLSession.shared`) would route the IPv4 literal over cellular
    /// — on IPv6/NAT64 carriers iOS synthesizes a NAT64 address and uses pdp_ip0, never reaching the
    /// glasses → -1009 "no network route". Disallowing cellular forces traffic onto the AP link.
    /// (Mirrors CyanFileSyncManager.wifiSession.)
    private lazy var wifiSession: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 10
        config.timeoutIntervalForResource = 120
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.allowsCellularAccess = false
        config.waitsForConnectivity = false
        if #available(iOS 13.0, *) {
            config.allowsExpensiveNetworkAccess = true
            config.allowsConstrainedNetworkAccess = true
        }
        return URLSession(configuration: config)
    }()

    // MARK: - Init / Destroy

    override func initAdapter() async throws {
        // Intentionally inert: do NOT touch the CRP SDK here. Initializing
        // CRPSmartGlassesSDK.sharedInstance at startup would create its CBCentralManager and
        // let the SDK autonomously reconnect to a bonded device before any JS connect. The
        // SDK is initialized lazily on the first JS-initiated connect (ensureSdkReady()).
        print("[CyanSportsAdapter] initAdapter (deferred — SDK initialized on first connect)")
    }

    /// Initialize the CRP SDK on the first JS-initiated connect: hook up the delegate and
    /// create the underlying central. Idempotent.
    private func ensureSdkReady() {
        guard !sdkInitialized else { return }
        sdkInitialized = true
        print("[CyanSportsAdapter] ensureSdkReady — initializing CRP SDK")
        sdk.delegate = self
        // Do NOT sdk.remove() here. The lazy-init guard (`sdkInitialized`) already guarantees the
        // SDK/central isn't touched until the first JS-initiated connect, so the "don't connect
        // before JS asks" goal is met. ensureSdkReady() only ever runs from inside connectWithAutoScan
        // — i.e. JS already wants this device — so removing/unbinding it here is pointless and
        // actively harmful: it tears down the bond right before we sdk.connect(), causing the link to
        // come up (.connecting) then immediately drop (.disconnected) on boot reconnect. With the bond
        // intact, the connect completes (.connecting → .syncing → .connected). The SDK's own bond-based
        // reconnect is handled by the pendingConnectDeviceId adoption path in didState(.connected).
    }

    override func destroy() {
        print("[CyanSportsAdapter] destroy")
        resumeLock.withLock { pendingConnectDeviceId = nil }
        failPendingConnect(NSError(domain: "CyanSportsAdapter", code: -99,
            userInfo: [NSLocalizedDescriptionKey: "Adapter destroyed"]))
        aiLock.withLock {
            pendingAiImage?.resume(throwing: NSError(domain: "CyanSportsAdapter", code: -99,
                userInfo: [NSLocalizedDescriptionKey: "Adapter destroyed"]))
            pendingAiImage = nil
        }
        // Only touch the SDK if we ever initialized it — otherwise we'd instantiate the
        // singleton just to tear it down (defeating the lazy-init guard).
        if sdkInitialized {
            sdk.interruptScan()
            if sdk.delegate === self { sdk.delegate = nil }
        }
        super.destroy()
    }

    // MARK: - Connection

    /// Connect by re-scanning for the peripheral (covers fresh connect + reconnect on restart).
    /// Only ever reached via a JS-initiated `connect` — the adapter never connects on its own.
    func connectWithAutoScan(deviceId: String) async throws {
        ensureSdkReady()
        currentDeviceId = deviceId
        updateState(.connecting)
        resumeLock.withLock { autoConnectResumed = false; pendingConnectDeviceId = deviceId; connectRetries = 0 }

        // Adopt an existing connection to the SAME device (e.g. a reconnect we already drove) so we
        // don't scan for a device that isn't advertising. didState(.connected) may already have fired.
        if connected || sdk.connectedPeripherial().contains(where: { $0.identifier.uuidString == deviceId }) {
            print("[CyanSportsAdapter] connectWithAutoScan — already connected to target, adopting")
            markConnected()
            resumeLock.withLock { pendingConnectDeviceId = nil }
            return
        }

        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            resumeLock.withLock { pendingConnect = cont }
            // Discover the peripheral and initiate the bind. The connect is NOT resolved here — the
            // authoritative signal is didState(.connected), which resolves `pendingConnect`.
            //
            // Wait for the CRP central to power on before scanning. On the first connect of a session
            // ensureSdkReady() has only just created the central, which reaches .poweredOn
            // asynchronously — sdk.scan() silently no-ops until then, which was the first-connect /
            // auto-reconnect-on-boot failure. Best-effort: proceed even if unconfirmed so we never
            // regress a case where didBluetoothState didn't fire; the 20s timeout still guards.
            Task { [weak self] in
                guard let self else { return }
                let ready = await self.awaitBluetoothReady()
                if !ready { print("[CyanSportsAdapter] proceeding to scan without confirmed power-on") }
                // Bail if the connect was already resolved/failed (timeout or disconnect) during the wait.
                let stillPending = self.resumeLock.withLock { !self.autoConnectResumed }
                guard stillPending else { return }
                self.performScanAndConnect(deviceId: deviceId)
            }

            // Overall connect timeout (covers both "never discovered" and "discovered but never
            // reached .connected"). Generous enough to cover the bounded connect retries below.
            Task { [weak self] in
                try? await Task.sleep(nanoseconds: 30_000_000_000)
                self?.failPendingConnect(NSError(domain: "CyanSportsAdapter", code: -3,
                    userInfo: [NSLocalizedDescriptionKey: "Connect timeout: device not found or did not connect"]))
            }
        }
    }

    /// Scan for `deviceId` and initiate the bind once discovered. The connect is NOT resolved here —
    /// the authoritative signal is didState(.connected). Used for the initial attempt and re-scans.
    private func performScanAndConnect(deviceId: String) {
        sdk.scan(15, progressHandler: { [weak self] discoveries in
            guard let self else { return }
            for d in discoveries {
                let id = d.remotePeripheral.identifier.uuidString
                self.discoveryMap[id] = d
                self.onDeviceFound(deviceId: id, deviceName: d.localName ?? "", rssi: d.RSSI)
                if id == deviceId {
                    self.sdk.interruptScan()
                    self.currentDeviceName = d.localName ?? "Cyan Sports Glass"
                    self.sdk.connect(d)
                }
            }
        }, completionHandler: { _, _ in })
    }

    /// Wait for the CRP central to reach .poweredOn before scanning. The central is created lazily
    /// in ensureSdkReady() and powers on asynchronously (reported via didBluetoothState); sdk.scan()
    /// silently no-ops until then. Returns true if powered on within the timeout.
    private func awaitBluetoothReady(timeoutMs: Int = 6000) async -> Bool {
        if btLock.withLock({ btPoweredOn }) { return true }
        let steps = max(1, timeoutMs / 100)
        for _ in 0..<steps {
            try? await Task.sleep(nanoseconds: 100_000_000) // 100ms
            if btLock.withLock({ btPoweredOn }) { return true }
        }
        return btLock.withLock { btPoweredOn }
    }

    /// Resolve the in-flight connect continuation exactly once (success).
    private func resolvePendingConnect() {
        let cont = resumeLock.withLock { () -> CheckedContinuation<Void, Error>? in
            guard !autoConnectResumed else { return nil }
            autoConnectResumed = true
            let c = pendingConnect; pendingConnect = nil; return c
        }
        cont?.resume()
    }

    /// Fail the in-flight connect continuation exactly once (clears intent + stops scan).
    private func failPendingConnect(_ error: Error) {
        let cont = resumeLock.withLock { () -> CheckedContinuation<Void, Error>? in
            guard !autoConnectResumed else { return nil }
            autoConnectResumed = true
            pendingConnectDeviceId = nil
            let c = pendingConnect; pendingConnect = nil; return c
        }
        guard let cont else { return }
        sdk.interruptScan()
        cont.resume(throwing: error)
    }

    /// Mark the link up (idempotent) and pull initial device data.
    private func markConnected() {
        guard !connected else { return }
        connected = true
        onConnected()
        requestInitialData()
    }

    override func connect(deviceId: String) async throws {
        // Not the primary path for cyan_sports (GlassManager calls connectWithAutoScan), but keep it
        // JS-driven and consistent.
        try await connectWithAutoScan(deviceId: deviceId)
    }

    override func disconnect() async throws {
        print("[CyanSportsAdapter] disconnect")
        updateState(.disconnecting)
        resumeLock.withLock { pendingConnectDeviceId = nil }
        failPendingConnect(NSError(domain: "CyanSportsAdapter", code: -98,
            userInfo: [NSLocalizedDescriptionKey: "Disconnected during connect"]))
        sdk.remove()
        connected = false
        onDisconnected(reason: "Disconnected by user", wasExpected: true)
    }

    override func isConnected() -> Bool { connected }

    // MARK: - Scanning (GlassManager uses the generic scanner; expose CRP scan too)

    override func startScan() async throws {
        ensureSdkReady()
        updateState(.scanning)
        discoveryMap.removeAll()
        // Wait for the central to power on — sdk.scan() no-ops before .poweredOn (same race as connect).
        let ready = await awaitBluetoothReady()
        if !ready { print("[CyanSportsAdapter] startScan proceeding without confirmed power-on") }
        sdk.scan(15, progressHandler: { [weak self] discoveries in
            guard let self else { return }
            for d in discoveries {
                let id = d.remotePeripheral.identifier.uuidString
                self.discoveryMap[id] = d
                self.onDeviceFound(deviceId: id, deviceName: d.localName ?? "", rssi: d.RSSI)
            }
        }, completionHandler: { _, _ in })
    }

    override func stopScan() async throws {
        sdk.interruptScan()
        if connectionState == .scanning { updateState(.idle) }
    }

    // MARK: - System / Storage info

    override func getSystemInfo() async throws -> GlassSystemInfo {
        sdk.getBattery { [weak self] info in self?.handleBattery(info) }
        return systemInfo
    }

    override func getStorageInfo() async throws -> GlassStorageInfo {
        let once = ResumeOnce()
        return try await withCheckedThrowingContinuation { cont in
            // getFileCount's handler can fire more than once — keep updating storage/posting on
            // every callback, but resume the continuation only the first time.
            sdk.getFileCount { [weak self] fc in
                let info = GlassStorageInfo(
                    totalSize: 0, freeSize: 0, usedSize: 0,
                    photoCount: Int(fc.pictureCount), videoCount: Int(fc.videoCount), audioCount: Int(fc.audioCount))
                self?.updateStorageInfo(info)
                self?.postEvent(GlassEventMsg(cmd: GlassEventConstants.msgStorageInfo,
                                              n1: Int(fc.pictureCount), n2: Int(fc.videoCount)))
                if once.claim() { cont.resume(returning: info) }
            }
            // Don't leak the continuation if getFileCount never answers (e.g. the link dropped).
            // Resume once with the last-known storage info after a timeout.
            Task { [weak self] in
                try? await Task.sleep(nanoseconds: 8_000_000_000) // 8s
                if once.claim() { cont.resume(returning: self?.storageInfo ?? .empty) }
            }
        }
    }

    // MARK: - Camera

    override func takePhoto(id: Int? = nil, filename: String? = nil) async throws -> PhotoResult {
        print("[CyanSportsAdapter] takePhoto (modeNormal)")
        sdk.setTakePhoto(takePhoto: CRPTakePhoto(mode: .modeNormal, param: Data()))
        return PhotoResult(success: true, errorCode: 0, photoCount: 0, filename: nil)
    }

    override func takeAiImage() async throws -> String {
        print("[CyanSportsAdapter] takeAiImage (modeAirecognition)")
        return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<String, Error>) in
            aiLock.withLock { pendingAiImage = cont }
            sdk.setTakePhoto(takePhoto: CRPTakePhoto(mode: .modeAirecognition, param: Data()))
        }
    }

    // MARK: - Video

    override func startVideo(id: Int? = nil, filename: String? = nil) async throws -> VideoResult {
        sdk.setVideoRecord(video: CRPVideoRecord(action: .startRecord, config: CRPVideoConfig(fps: 30, maxDuration: 0)))
        return VideoResult(success: true, errorCode: 0, videoCount: 0)
    }

    override func stopVideo() async throws {
        sdk.setVideoRecord(video: CRPVideoRecord(action: .stopRecord, config: CRPVideoConfig(fps: 0, maxDuration: 0)))
    }

    // MARK: - Audio

    override func startAudio(filename: String? = nil) async throws -> AudioResult {
        // On-device audio recording (writes a file to glasses storage). totalTime 0 = unlimited.
        sdk.setAudioRecord(ctrl: CRPAudioRecordCtrl(type: .Start, totalTime: 0))
        return AudioResult(success: true, errorCode: 0, audioCount: 0)
    }

    override func stopAudio() async throws {
        sdk.setAudioRecord(ctrl: CRPAudioRecordCtrl(type: .Stop, totalTime: 0))
    }

    // MARK: - Media config (video only — photo/audio resolution not exposed)

    override func getMediaConfig() async throws -> MediaConfigData {
        let once = ResumeOnce()
        return try await withCheckedThrowingContinuation { cont in
            sdk.getVideoConfig(handler: { cfg in
                guard once.claim() else { return }
                cont.resume(returning: MediaConfigData(
                    photo: PhotoConfigData(width: 0, height: 0),
                    video: VideoConfigData(width: 0, height: 0, quality: Int(cfg.fps), duration: Int(cfg.maxDuration)),
                    audio: AudioConfigData(duration: 0)))
            })
        }
    }

    override func setMediaConfig(_ config: [String: Any]) async throws -> Bool {
        guard let video = config["video"] as? [String: Any] else { return false }
        let fps = UInt32((video["quality"] as? Int) ?? (video["fps"] as? Int) ?? 30)
        let duration = UInt32((video["duration"] as? Int) ?? 0)
        sdk.setVideoConfig(videoConfig: CRPVideoConfig(fps: fps, maxDuration: duration))
        return true
    }

    // MARK: - Device control

    override func reboot() async throws { sdk.restart() }
    override func factoryReset() async throws { sdk.reset() }
    override func setDeviceName(_ name: String) async throws {
        print("[CyanSportsAdapter] setDeviceName not supported")
    }
    override func clearMedia() async throws {
        sdk.deleteFile(file: CRPFileDelete(type: .typeAll, name: "", fileType: .allFiles))
    }
    override func setAiStatus(_ status: String) async throws {
        // Agent-driven: flow status follows the agent's audio-enabled state (the only place we send it).
        switch status {
        case "speaking_start", "thinking_start":
            sdk.setAIReplyStatus(status: CRPFlowStatus(type: .flowStatusStart))
        case "speaking_stop", "thinking_stop":
            // sdk.setAIReplyStatus(status: CRPFlowStatus(type: .flowStatusComplete))
            sdk.exitAIReply()
        default:
            print("[CyanSportsAdapter] setAiStatus unknown: \(status)")
        }
    }
    override func sendCommand(_ command: String, params: [String: Any] = [:]) async throws {
        print("[CyanSportsAdapter] sendCommand not implemented: \(command)")
    }

    // MARK: - File sync (Wi-Fi AP + HTTP download, then setFileSyncModeExit)

    override func syncFiles() async throws {
        syncLock.withLock {
            syncActive = true; syncCancelled = false; wifiJoined = false
            fileBaseUrl = nil; syncFiles_total = 0; syncFiles_done = 0; syncBytes = 0
            let suffix = String((currentDeviceId ?? "0000").suffix(4))
            syncSsid = "CyanSports_\(suffix)"
            syncPassword = "12345678"
        }
        onSyncStarted()
        // Device starts its Wi-Fi AP with these credentials; we join it in receiveActionResult.
        sdk.setFileSyncModeEnter(wifiCtrl: CRPWifiCtrl(mode: .startAp, ssid: syncSsid, password: syncPassword, channel: 0))
    }

    override func cancelSync() async {
        syncLock.withLock { syncCancelled = true }
        finishSync(success: false, error: "Cancelled")
    }

    private func joinDeviceWifi() {
        let config = NEHotspotConfiguration(ssid: syncSsid, passphrase: syncPassword, isWEP: false)
        config.joinOnce = false
        NEHotspotConfigurationManager.shared.apply(config) { [weak self] error in
            guard let self else { return }
            if let error = error, (error as NSError).code != NEHotspotConfigurationError.alreadyAssociated.rawValue {
                print("[CyanSportsAdapter] wifi join failed: \(error)")
                self.finishSync(success: false, error: "Wi-Fi join failed")
                return
            }
            print("[CyanSportsAdapter] wifi joined: \(self.syncSsid)")
            self.syncLock.withLock { self.wifiJoined = true }
            self.tryStartDownload()
        }
    }

    /// Begin the HTTP download once both Wi-Fi is joined and the base URL is known.
    private func tryStartDownload() {
        let (ready, base) = syncLock.withLock { (wifiJoined && fileBaseUrl != nil && syncActive, fileBaseUrl) }
        guard ready, let base else { return }
        Task { await downloadManifestAndFiles(baseUrl: base) }
    }

    private func downloadManifestAndFiles(baseUrl: String) async {
        let manifestUrl = URL(string: baseUrl + "media.config")!

        // The AP's HTTP server may need a moment to come up after we join — retry the manifest.
        var data: Data?
        for attempt in 1...3 {
            if syncLock.withLock({ syncCancelled }) { finishSync(success: false, error: "Cancelled"); return }
            do {
                let (body, response) = try await wifiSession.data(from: manifestUrl)
                // Log the raw HTTP response from media.config so we can inspect exactly what the
                // device returned (status, byte count, raw body — even if it doesn't parse).
                let status = (response as? HTTPURLResponse)?.statusCode ?? -1
                let rawBody = String(data: body, encoding: .utf8) ?? "<non-utf8 \(body.count) bytes>"
                print("[CyanSportsAdapter] media.config raw response (status=\(status), \(body.count) bytes):\n\(rawBody)")
                data = body
                break
            } catch {
                print("[CyanSportsAdapter] manifest fetch attempt \(attempt) failed: \(error)")
                if attempt < 3 { try? await Task.sleep(nanoseconds: 1_000_000_000) }
            }
        }
        guard let data else { finishSync(success: false, error: "Manifest fetch failed"); return }

        let rawManifest = String(data: data, encoding: .utf8) ?? ""
        let names = rawManifest
            .components(separatedBy: "\n").map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }

        // Log the media.config manifest so we can see exactly which files / formats the device
        // is offering for sync.
        print("[CyanSportsAdapter] media.config manifest (\(names.count) files):\n\(rawManifest)")
        let formatCounts = Dictionary(grouping: names) {
            ($0 as NSString).pathExtension.lowercased()
        }.mapValues { $0.count }
        print("[CyanSportsAdapter] media.config formats: \(formatCounts)")

        syncLock.withLock { syncFiles_total = names.count }
        if names.isEmpty { finishSync(success: true, error: nil); return }

        for (idx, name) in names.enumerated() {
            if syncLock.withLock({ syncCancelled }) { finishSync(success: false, error: "Cancelled"); return }
            do {
                let fileUrl = URL(string: baseUrl + name)!
                let (fileData, _) = try await wifiSession.data(from: fileUrl)
                let savedPath = try saveSyncedFile(name: name, data: fileData)
                syncLock.withLock { syncFiles_done += 1; syncBytes += Int64(fileData.count) }
                // Notify JS per-file so the gallery indexes + thumbnails this file incrementally.
                let sub = subDir(for: name)
                if sub == "photo" || sub == "video" || sub == "audio" {
                    onFileSynced(path: savedPath, type: sub)
                }
                // Tell the glasses to delete the file now that we've saved it — fire-and-forget BLE
                // command (no ack). Without this the device keeps the file and the media count never drops.
                sdk.deleteFile(file: CRPFileDelete(type: .typeByName, name: name, fileType: crpFileType(for: name)))
            } catch {
                print("[CyanSportsAdapter] download failed for \(name): \(error)")
            }
            onSyncProgress(current: idx + 1, total: names.count)
        }
        finishSync(success: true, error: nil)
    }

    /// Writes the synced file to its category subdir and returns the on-disk path.
    @discardableResult
    private func saveSyncedFile(name: String, data: Data) throws -> String {
        let sub = subDir(for: name)
        let dir = URL(fileURLWithPath: getMediaDirectory()).appendingPathComponent(sub)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        // Raw .opus from the glasses is headerless CBR Opus — re-containerize to Ogg/Opus so it's
        // playable (mirrors CyanFileSyncManager). Other types are written as-is.
        let dataToWrite = name.lowercased().hasSuffix(".opus") ? OpusOggWrapper.wrapIfNeeded(data) : data
        let dest = dir.appendingPathComponent(localFilename(for: name))
        try dataToWrite.write(to: dest)
        return dest.path
    }

    private func subDir(for name: String) -> String {
        let n = name.lowercased()
        if n.hasSuffix(".jpg") || n.hasSuffix(".jpeg") || n.hasSuffix(".png") { return "photo" }
        if n.hasSuffix(".mp4") || n.hasSuffix(".mov") || n.hasSuffix(".avi") { return "video" }
        if n.hasSuffix(".mp3") || n.hasSuffix(".wav") || n.hasSuffix(".aac") || n.hasSuffix(".m4a") || n.hasSuffix(".opus") { return "audio" }
        // The device names videos with a `video-` prefix and NO extension — classify those as video.
        if n.hasPrefix("video") { return "video" }
        return "other"
    }

    /// On-disk filename: extension-less `video-…` files get a `.mp4` suffix so the gallery (which
    /// filters by extension) recognizes them. Everything else keeps its original name.
    private func localFilename(for name: String) -> String {
        let n = name.lowercased()
        let hasVideoExt = n.hasSuffix(".mp4") || n.hasSuffix(".mov") || n.hasSuffix(".avi")
        if subDir(for: name) == "video" && !hasVideoExt { return name + ".mp4" }
        return name
    }

    /// Map a filename to the CRP file category for `deleteFile`.
    private func crpFileType(for name: String) -> CRPFileType {
        switch subDir(for: name) {
        case "video": return .video
        case "audio": return .audio
        default:      return .photo
        }
    }

    private func finishSync(success: Bool, error: String?) {
        let (wasActive, files, bytes) = syncLock.withLock { () -> (Bool, Int, Int64) in
            let a = syncActive; syncActive = false; return (a, syncFiles_done, syncBytes)
        }
        guard wasActive else { return }
        sdk.setFileSyncModeExit()
        if !syncSsid.isEmpty {
            NEHotspotConfigurationManager.shared.removeConfiguration(forSSID: syncSsid)
        }
        onSyncCompleted(totalFiles: files, totalBytes: bytes, success: success, error: error)
        // One media-count refresh after the sync terminates (success OR failure) — a partial/failed
        // sync may still have deleted some files. Give the firmware a moment to apply the deletes.
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            guard let self else { return }
            self.sdk.getFileCount { [weak self] fc in
                let info = GlassStorageInfo(
                    totalSize: 0, freeSize: 0, usedSize: 0,
                    photoCount: Int(fc.pictureCount), videoCount: Int(fc.videoCount), audioCount: Int(fc.audioCount))
                self?.updateStorageInfo(info)
                self?.postEvent(GlassEventMsg(cmd: GlassEventConstants.msgStorageInfo,
                                              n1: Int(fc.pictureCount), n2: Int(fc.videoCount)))
            }
        }
    }

    // MARK: - CRPManagerDelegate

    func didState(_ state: CRPState) {
        print("[CyanSportsAdapter] didState \(state.rawValue)")
        switch state {
        case .connected:
            // Already up (adopted an existing system-level connection in connectWithAutoScan, or a
            // duplicate .connected): this is a re-confirmation, NOT an unsolicited auto-connect.
            // Resolve any pending connect and clear intent, but never sdk.remove() — that would drop a
            // live, wanted link (the cold-boot self-disconnect bug).
            if connected {
                resumeLock.withLock { pendingConnectDeviceId = nil }
                resolvePendingConnect()
                return
            }
            // No in-flight JS connect. Two cases:
            //  - currentDeviceId != nil → our JS-managed device auto-recovered after a transient drop
            //    (e.g. SCO/HFP engaging churns the BLE link; the glasses drop and immediately
            //    reconnect on their own). Adopt it — sdk.remove() here kills a link we want, which was
            //    the "glasses disconnect when voice starts" bug.
            //  - currentDeviceId == nil → genuine cold-boot unsolicited auto-connect; refuse as before.
            let expected = resumeLock.withLock { pendingConnectDeviceId }
            guard expected != nil else {
                if currentDeviceId != nil {
                    print("[CyanSportsAdapter] adopting auto-reconnect for managed device \(currentDeviceId ?? "?")")
                    markConnected()   // idempotent; fires onConnected + requestInitialData
                } else {
                    print("[CyanSportsAdapter] refusing unsolicited auto-connect — sdk.remove()")
                    sdk.remove()
                }
                return
            }
            resumeLock.withLock { pendingConnectDeviceId = nil }
            markConnected()
            resolvePendingConnect()
        case .disconnected:
            if connected {
                connected = false
                onDisconnected(reason: "BLE connection lost", wasExpected: false)
                return
            }
            // A connect attempt failed before completing (.connecting → .disconnected, never
            // .connected). First-attempt BLE connects to these glasses can drop transiently — retry a
            // bounded number of times instead of hanging until the overall connect timeout.
            let retryId = resumeLock.withLock { () -> String? in
                guard !autoConnectResumed, let id = pendingConnectDeviceId,
                      connectRetries < maxConnectRetries else { return nil }
                connectRetries += 1
                return id
            }
            if let deviceId = retryId {
                let attempt = connectRetries
                Task { [weak self] in
                    try? await Task.sleep(nanoseconds: 1_500_000_000) // brief backoff
                    guard let self else { return }
                    guard self.resumeLock.withLock({ !self.autoConnectResumed }) else { return }
                    print("[CyanSportsAdapter] connect attempt failed — retrying (\(attempt)/\(self.maxConnectRetries))")
                    if let d = self.discoveryMap[deviceId] {
                        self.sdk.connect(d)                            // re-bind the already-discovered peripheral
                    } else {
                        self.performScanAndConnect(deviceId: deviceId) // re-discover, then connect
                    }
                }
            }
        default: break
        }
    }

    func receiveActionResult(actionResult: CRPActionResult) {
        print("[CyanSportsAdapter] receiveActionResult code=\(actionResult.code) msg=\(actionResult.msg)")
        // code 0 after setFileSyncModeEnter → device AP is up; join it.
        if actionResult.code == 0, syncLock.withLock({ syncActive }) {
            joinDeviceWifi()
        }
    }

    func receiveFileBaseUrl(fileUrlBase: CRPFileUrlBase) {
        print("[CyanSportsAdapter] receiveFileBaseUrl \(fileUrlBase.urlBase)")
        syncLock.withLock { fileBaseUrl = fileUrlBase.urlBase }
        tryStartDownload()
    }

    func receiveAirecognitionImageData(data: Data) {
        print("[CyanSportsAdapter] receiveAirecognitionImageData \(data.count) bytes")
        let cont = aiLock.withLock { () -> CheckedContinuation<String, Error>? in
            let c = pendingAiImage; pendingAiImage = nil; return c
        }
        guard let cont else { return }
        let bytes = [UInt8](data.prefix(2))
        guard bytes.count >= 2, bytes[0] == 0xFF, bytes[1] == 0xD8 else {
            cont.resume(throwing: NSError(domain: "CyanSportsAdapter", code: -11,
                userInfo: [NSLocalizedDescriptionKey: "Invalid AI image (not JPEG), size=\(data.count)"]))
            return
        }
        let dest = FileManager.default.temporaryDirectory
            .appendingPathComponent("cyan_sports_ai_\(Int(Date().timeIntervalSince1970 * 1000)).jpg")
        do { try data.write(to: dest); cont.resume(returning: dest.path) }
        catch { cont.resume(throwing: error) }
    }

    func didBluetoothState(_ state: CRPBluetoothState) {
        btLock.withLock { btPoweredOn = (state == .poweredOn) }
    }

    // Required delegate stubs (unused for now)
    func receiveOTA(state: CRPOTAState) {}
    func receiveAudioState(_ state: CRPAudioCtrl) {}
    func receiveAudioData(frameIndex: Int, data: Data) {}
    func receiveTranslateAudioData(frameIndex: Int, data: Data) {}
    func receiveACKError(errorCode: CRPACKError) {}
    func receiveRunningStatus(status: CRPRunningStatus) {
        print("[CyanSportsAdapter] runningStatus aiDialogue=\(status.aiDialogue) aiVisual=\(status.aiVisual) takePicture=\(status.takePicture) audioRec=\(status.audioRecording) videoRec=\(status.videoRecording)")
        // Only act on aiDialogue == true (wakeup); ignore false and hold no dialogue state. Repeated
        // true frames are deduped downstream — handleVoiceWakeup ignores wakeups when the mic is
        // already on — and the agent drives the device flow status from its audio-enabled state.
        if status.aiDialogue {
            print("[CyanSportsAdapter] AI dialogue true → wakeup")
            onVoiceWakeup()
        }
    }
    func deviceClearPair() {}

    /// The glasses push an AI-interaction intent when the user wakes the assistant (button/wakeword)
    /// — surface it as a voice wakeup so JS can start the voice agent.
    /// MUST be `@objc`: this is an *optional* CRPManagerDelegate requirement, so without it the ObjC
    /// SDK's respondsToSelector check fails and the callback is never delivered.
    @objc func receiveAIGreetingWord(_ intention: CRPAiDialogueIntention) {
        print("[CyanSportsAdapter] AI greeting intention=\(intention.type.rawValue) → wakeup")
        // Re-enabled: the agent now owns the AVAudioSession, so wakeup no longer drops the BLE link.
        onVoiceWakeup()
    }

    // MARK: - Helpers

    private func requestInitialData() {
        sdk.getBattery { [weak self] info in self?.handleBattery(info) }
        // NOTE: do NOT call setVoiceWakeup() here. Enabling the wakeword reconfigures the device's
        // mic/AI subsystem and was dropping the BLE link on every connect — which, with reconnect
        // recovery, looped (connect → enable → drop → reconnect → …). The wakeup TRIGGER still works
        // passively via receiveAIGreetingWord (button/wakeword press), which can't cause a drop.
        onReady()
    }

    private func handleBattery(_ info: CRPBatteryInfo) {
        let level = Int(info.lvl)
        print("[CyanSportsAdapter] battery=\(level) charging=\(info.charging)")
        updateSystemInfoFields(batteryLevel: level, isCharging: info.charging, batteryVoltage: Float(info.volt))
        postEvent(GlassEventMsg(cmd: GlassEventConstants.msgBattery, n1: level, n2: info.charging ? 1 : 0))
    }
}

#endif // !targetEnvironment(simulator)
