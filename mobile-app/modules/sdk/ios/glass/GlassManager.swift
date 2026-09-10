import CoreBluetooth
import Foundation

// MARK: - Bluetooth state helper

private class BluetoothStateChecker: NSObject, CBCentralManagerDelegate {
    private var continuation: CheckedContinuation<Bool, Never>?
    private var manager: CBCentralManager?

    func check() async -> Bool {
        return await withCheckedContinuation { continuation in
            self.continuation = continuation
            self.manager = CBCentralManager(
                delegate: self, queue: nil,
                options: [CBCentralManagerOptionShowPowerAlertKey: false]
            )
        }
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard let cont = continuation else { return }
        continuation = nil
        cont.resume(returning: central.state == .poweredOn)
    }
}

/// Central manager for glass devices (mirrors GlassManager.kt)
/// Singleton — handles adapter lifecycle and API routing. Vendor detection lives
/// in JS; native is a pure router keyed by the vendor string ("k900"/"cyan"/"mock").
/// Adapters are initialized ONCE at startup and persist for the app lifetime.
class GlassManager {
    static let shared = GlassManager()
    private init() {}

    private let lock = NSLock()
    /// Persistent adapter pool — keyed by the JS vendor string, initialized once, never destroyed at runtime.
    private var adapters: [String: BaseGlassAdapter] = [:]
    /// Which vendor is currently connected (nil when disconnected).
    private var activeVendor: String?

    // MARK: - Initialization

    /// Creates and initializes all vendor adapters once. Called from GlassModule.OnCreate.
    func initialize() async {
        #if targetEnvironment(simulator)
        guard lock.withLock({ adapters["mock"] == nil }) else { return }
        let mock = createAdapter("mock")
        try? await mock.initAdapter()
        lock.withLock { adapters["mock"] = mock; activeVendor = "mock" }
        #else
        for vendor in ["k900", "cyan", "cyan_sports"] {
            guard lock.withLock({ adapters[vendor] == nil }) else { continue }
            let adapter = createAdapter(vendor)
            do {
                try await adapter.initAdapter()
                lock.withLock { adapters[vendor] = adapter }
                print("[GlassManager] initialize — adapter ready: \(vendor)")
            } catch {
                print("[GlassManager] initialize — adapter failed: \(vendor): \(error)")
                adapter.destroy()
            }
        }
        #endif
    }

    // MARK: - Adapter factory

    private func createAdapter(_ vendor: String) -> BaseGlassAdapter {
        switch vendor {
        case "k900":
            #if targetEnvironment(simulator)
            return MockAdapter()
            #else
            return K900Adapter()
            #endif
        case "cyan":
            #if targetEnvironment(simulator)
            return MockAdapter()
            #else
            return CyanAdapter()
            #endif
        case "cyan_sports":
            #if targetEnvironment(simulator)
            return MockAdapter()
            #else
            return CyanSportsAdapter()
            #endif
        default:       return MockAdapter() // "mock" / unknown
        }
    }

    /// Returns the current vendor string, or nil if no adapter is active.
    func getCurrentVendorString() -> String? {
        lock.withLock { activeVendor }
    }

    // MARK: - Connection

    /// Connect to a device. Vendor is decided in JS and passed in — native routes
    /// straight to the matching adapter. On real devices, k900/cyan rediscover the
    /// peripheral via `connectWithAutoScan` (covers fresh-scan connects and
    /// reconnect-after-restart). `name` is unused on iOS (no foreground notification).
    func connect(deviceId: String, vendor: String, name: String?) async throws {
        guard let adapter = lock.withLock({ adapters[vendor] }) else {
            throw NSError(domain: "GlassManager", code: -5,
                userInfo: [NSLocalizedDescriptionKey: "No adapter for vendor \(vendor) — not initialized"])
        }

        // Stop the generic scan so the vendor SDK's central can re-scan/connect unobstructed.
        #if !targetEnvironment(simulator)
        GlassScanner.shared.stop()
        #endif

        // Set activeVendor BEFORE connect so getCurrentVendorString() is correct when
        // the GLASS_CONNECTED event fires inside onConnected() during the connect call.
        lock.withLock { activeVendor = vendor }

        do {
            #if !targetEnvironment(simulator)
            if vendor == "k900", let k900 = adapter as? K900Adapter {
                try await k900.connectWithAutoScan(deviceId: deviceId)
            } else if vendor == "cyan", let cyan = adapter as? CyanAdapter {
                try await cyan.connectWithAutoScan(deviceId: deviceId)
            } else if vendor == "cyan_sports", let cs = adapter as? CyanSportsAdapter {
                try await cs.connectWithAutoScan(deviceId: deviceId)
            } else {
                try await adapter.connect(deviceId: deviceId)
            }
            #else
            try await adapter.connect(deviceId: deviceId)
            #endif
        } catch {
            lock.withLock { activeVendor = nil }
            throw error
        }
    }

    func disconnect() async throws {
        let adapter = lock.withLock { activeVendor.flatMap { adapters[$0] } }
        try await adapter?.disconnect()

        lock.withLock {
            activeVendor = nil
        }
    }

    func isConnected() -> Bool {
        guard let vendor = lock.withLock({ activeVendor }) else { return false }
        return lock.withLock({ adapters[vendor] })?.isConnected() ?? false
    }

    func getCurrentState() -> GlassConnectionState {
        guard let vendor = lock.withLock({ activeVendor }) else { return .idle }
        return lock.withLock({ adapters[vendor] })?.connectionState ?? .idle
    }

    func getCapabilities() -> [String: Bool] {
        guard let vendor = lock.withLock({ activeVendor }) else { return [:] }
        return lock.withLock({ adapters[vendor] })?.capabilities ?? [:]
    }

    // MARK: - Bluetooth state

    func isBluetoothEnabled() async -> Bool {
        return await BluetoothStateChecker().check()
    }

    // MARK: - Scanning

    func startScan() async throws {
        #if targetEnvironment(simulator)
        try? await lock.withLock({ adapters["mock"] })?.startScan()
        #else
        // One generic scanner surfaces ALL devices; JS classifies the vendor.
        GlassScanner.shared.start()
        #endif
    }

    func stopScan() async throws {
        #if targetEnvironment(simulator)
        try? await lock.withLock({ adapters["mock"] })?.stopScan()
        #else
        GlassScanner.shared.stop()
        #endif
    }

    // MARK: - Device operations (forward to active adapter)

    func sendCommand(_ command: String, params: [String: Any] = [:]) async throws {
        guard let vendor = lock.withLock({ activeVendor }),
              let adapter = lock.withLock({ adapters[vendor] }) else {
            throw NSError(domain: "GlassManager", code: -1,
                          userInfo: [NSLocalizedDescriptionKey: "No adapter connected"])
        }
        try await adapter.sendCommand(command, params: params)
    }

    func getStorageInfo() async throws -> GlassStorageInfo {
        guard let vendor = lock.withLock({ activeVendor }),
              let adapter = lock.withLock({ adapters[vendor] }) else { return .empty }
        return try await adapter.getStorageInfo()
    }

    func getSystemInfo() async throws -> GlassSystemInfo {
        guard let vendor = lock.withLock({ activeVendor }),
              let adapter = lock.withLock({ adapters[vendor] }) else { return .empty }
        return try await adapter.getSystemInfo()
    }

    func setDeviceName(_ name: String) async throws {
        guard let vendor = lock.withLock({ activeVendor }),
              let adapter = lock.withLock({ adapters[vendor] }) else { return }
        try await adapter.setDeviceName(name)
    }

    func clearMedia() async throws {
        guard let vendor = lock.withLock({ activeVendor }),
              let adapter = lock.withLock({ adapters[vendor] }) else { return }
        try await adapter.clearMedia()
    }

    func reboot() async throws {
        guard let vendor = lock.withLock({ activeVendor }),
              let adapter = lock.withLock({ adapters[vendor] }) else { return }
        try await adapter.reboot()
    }

    func factoryReset() async throws {
        guard let vendor = lock.withLock({ activeVendor }),
              let adapter = lock.withLock({ adapters[vendor] }) else { return }
        try await adapter.factoryReset()
    }

    func setAiStatus(_ status: String) async throws {
        guard let vendor = lock.withLock({ activeVendor }),
              let adapter = lock.withLock({ adapters[vendor] }) else { return }
        try await adapter.setAiStatus(status)
    }

    func takePhoto(id: Int? = nil, filename: String? = nil) async throws -> PhotoResult {
        guard let vendor = lock.withLock({ activeVendor }),
              let adapter = lock.withLock({ adapters[vendor] }) else {
            throw NSError(domain: "GlassManager", code: -1,
                          userInfo: [NSLocalizedDescriptionKey: "No adapter connected"])
        }
        return try await adapter.takePhoto(id: id, filename: filename)
    }

    func takeAiImage() async throws -> String {
        guard let vendor = lock.withLock({ activeVendor }),
              let adapter = lock.withLock({ adapters[vendor] }) else {
            throw NSError(domain: "GlassManager", code: -1,
                          userInfo: [NSLocalizedDescriptionKey: "No adapter connected"])
        }
        return try await adapter.takeAiImage()
    }

    func startVideo(id: Int? = nil, filename: String? = nil) async throws -> VideoResult {
        guard let vendor = lock.withLock({ activeVendor }),
              let adapter = lock.withLock({ adapters[vendor] }) else {
            throw NSError(domain: "GlassManager", code: -1,
                          userInfo: [NSLocalizedDescriptionKey: "No adapter connected"])
        }
        return try await adapter.startVideo(id: id, filename: filename)
    }

    func stopVideo() async throws {
        guard let vendor = lock.withLock({ activeVendor }),
              let adapter = lock.withLock({ adapters[vendor] }) else { return }
        try await adapter.stopVideo()
    }

    func startAudio(filename: String? = nil) async throws -> AudioResult {
        guard let vendor = lock.withLock({ activeVendor }),
              let adapter = lock.withLock({ adapters[vendor] }) else {
            throw NSError(domain: "GlassManager", code: -1,
                          userInfo: [NSLocalizedDescriptionKey: "No adapter connected"])
        }
        return try await adapter.startAudio(filename: filename)
    }

    func stopAudio() async throws {
        guard let vendor = lock.withLock({ activeVendor }),
              let adapter = lock.withLock({ adapters[vendor] }) else { return }
        try await adapter.stopAudio()
    }

    func getMediaDirectory() -> String {
        if let vendor = lock.withLock({ activeVendor }),
           let dir = lock.withLock({ adapters[vendor] })?.getMediaDirectory() {
            return dir
        }
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)
        let base = docs.first ?? URL(fileURLWithPath: NSTemporaryDirectory())
        return base.appendingPathComponent("GlassMedia").path
    }

    func getMediaConfig() async throws -> MediaConfigData {
        guard let vendor = lock.withLock({ activeVendor }),
              let adapter = lock.withLock({ adapters[vendor] }) else {
            throw NSError(domain: "GlassManager", code: -1,
                          userInfo: [NSLocalizedDescriptionKey: "No adapter connected"])
        }
        return try await adapter.getMediaConfig()
    }

    func setMediaConfig(_ config: [String: Any]) async throws -> Bool {
        guard let vendor = lock.withLock({ activeVendor }),
              let adapter = lock.withLock({ adapters[vendor] }) else {
            throw NSError(domain: "GlassManager", code: -1,
                          userInfo: [NSLocalizedDescriptionKey: "No adapter connected"])
        }
        return try await adapter.setMediaConfig(config)
    }

    func syncFiles() async throws {
        guard let vendor = lock.withLock({ activeVendor }),
              let adapter = lock.withLock({ adapters[vendor] }) else {
            throw NSError(domain: "GlassManager", code: -1,
                          userInfo: [NSLocalizedDescriptionKey: "No adapter connected"])
        }
        try await adapter.syncFiles()
    }

    func cancelSync() async throws {
        guard let vendor = lock.withLock({ activeVendor }),
              let adapter = lock.withLock({ adapters[vendor] }) else { return }
        await adapter.cancelSync()
    }

    // MARK: - Stream

    func startStream(ssid: String, pwd: String, url: String, streamKey: String?, fps: Int, bitrate: Int) throws {
        guard let adapter = lock.withLock({ activeVendor.flatMap { adapters[$0] } }) else {
            throw NSError(domain: "GlassManager", code: -1,
                userInfo: [NSLocalizedDescriptionKey: "No active glass connection"])
        }
        try adapter.startStream(ssid: ssid, pwd: pwd, url: url, streamKey: streamKey, fps: fps, bitrate: bitrate)
    }

    func stopStream() {
        lock.withLock { activeVendor.flatMap { adapters[$0] } }?.stopStream()
    }

    // MARK: - Destroy

    func destroy() {
        let all = lock.withLock { adapters }
        for (_, a) in all { a.destroy() }
        lock.withLock {
            adapters.removeAll()
            activeVendor = nil
        }
    }
}
