#if !targetEnvironment(simulator)
import Foundation
import NetworkExtension
import CoreBluetooth
import QCSDK

/// Handles WiFi hotspot file sync for Cyan glasses.
/// Flow: openWifi (BLE) → join hotspot (NEHotspot) → test connection → discover IP → fetch manifest → download files.
class CyanFileSyncManager {

    // MARK: - Sync events

    enum SyncEvent {
        case started
        case progress(current: Int, total: Int)
        case fileSynced(path: String, type: String)
        case completed(totalFiles: Int, totalBytes: Int64, success: Bool, error: String?)
    }

    // MARK: - Errors

    enum CyanSyncError: LocalizedError {
        case cancelled
        case wifiCredentialsFailed(code: Int)
        case wifiVerificationFailed
        case noReachableIP
        case manifestFetchFailed
        case hotspotJoinFailed(ssid: String, code: Int, message: String)
        case stuckOnOtherNetwork(actual: String, target: String)

        var errorDescription: String? {
            switch self {
            case .cancelled: return "Sync cancelled"
            case .wifiCredentialsFailed(let code): return "Failed to get WiFi credentials (code=\(code))"
            case .wifiVerificationFailed: return "WiFi verification failed after retries"
            case .noReachableIP: return "No reachable IP found"
            case .manifestFetchFailed: return "Failed to fetch file manifest"
            case .hotspotJoinFailed(let ssid, let code, _):
                return "iOS didn't join '\(ssid)' automatically (code=\(code)). Open Settings ▸ Wi-Fi and connect to '\(ssid)' manually, then retry."
            case .stuckOnOtherNetwork(let actual, let target):
                return "Your phone stayed on Wi-Fi '\(actual)' and won't switch to the glasses' '\(target)'. Forget or disconnect '\(actual)' (Settings ▸ Wi-Fi), or join '\(target)' manually, then retry."
            }
        }
    }

    // MARK: - Constants

    /// Glasses firmware returns wrong password — the actual hotspot password is hardcoded (confirmed by HeyCyan demo).
    private let hotspotPassword = "123456789"

    /// 3.192.168.31 is the Cyan glasses' own HTTP server IP (matches the BLE-reported device IP
    /// and the HeyCyan demo's known-IP list). Tried before 192.168.x.1 gateways to avoid colliding
    /// with the user's existing router (e.g. a Jio router also at 192.168.31.1).
    private let priorityIPs = ["3.192.168.31", "192.168.43.1", "192.168.4.1", "192.168.31.1"]
    private let fallbackIPs = ["192.168.1.1", "192.168.0.1", "192.168.100.1",
                                "192.168.123.1", "192.168.137.1", "10.0.0.1", "172.20.10.1"]

    private let endpointPaths = ["/files/media.config", "/media.config",
                                  "/files/manifest", "/manifest",
                                  "/api/media", "/api/files",
                                  "/config", "/files", "/"]

    // MARK: - State

    private weak var adapter: CyanAdapter?
    private let onEvent: (SyncEvent) -> Void
    private var cancelled = false
    private var discoveredEndpoint: String?
    private var deviceIP: String?
    /// SSID we joined, so the cancel path can remove the hotspot config (out of `sync()` local scope).
    private var joinedSSID: String?

    /// Last battery % read at sync start (-1 = unknown). The WiFi AP is power-hungry and the
    /// firmware refuses to enable it when low, so this is the first thing to check on failure.
    private var lastBattery: Int = -1

    /// URLSession for glasses HTTP communication.
    /// Pinned to Wi-Fi: the glasses serve plain IPv4 over their local AP, but on an IPv6-only /
    /// NAT64 carrier network (e.g. Jio) iOS otherwise synthesizes a NAT64 address for every IPv4
    /// literal and routes it over cellular (pdp_ip0) to the internet — so the local glasses IP is
    /// never reached. Disallowing cellular/constrained/expensive paths forces traffic onto the
    /// glasses Wi-Fi link only.
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

    init(adapter: CyanAdapter, onEvent: @escaping (SyncEvent) -> Void) {
        self.adapter = adapter
        self.onEvent = onEvent
    }

    func cancelSync() { cancelled = true }

    // MARK: - Main entry point

    /// Flow: wait for peripheral idle → openWifi (boots AP + returns credentials) → wait for BLE
    /// hotspot IP → confirm broadcast → apply NEHotspotConfiguration + probe until reachable →
    /// fetch manifest → download → stop transfer.
    func sync() async {
        cancelled = false
        joinedSSID = nil
        onEvent(.started)

        // Emit exactly one terminal .completed event, no matter which path we exit through —
        // the frontend clears its "syncing" state on this event, so it must always fire.
        var terminalSent = false
        func complete(files: Int = 0, bytes: Int64 = 0, success: Bool, error: String?) {
            guard !terminalSent else { return }
            terminalSent = true
            onEvent(.completed(totalFiles: files, totalBytes: bytes, success: success, error: error))
        }
        // Safety net: if we ever fall out without emitting a terminal event (unexpected throw or
        // early return), report failure so the UI can't get stuck spinning.
        defer {
            complete(success: false, error: "Sync ended unexpectedly")
        }

        // First signal to check on failure: the AP is power-hungry and the firmware won't enable
        // it when low, which presents exactly as "hotspot never broadcasts".
        await readBatteryLevel()

        do {
            // Boot the AP and reach the device, retrying/escalating if a boot yields a dead AP.
            let (ssid, reachableIP) = try await bootAndReachDevice()
            try checkCancelled()

            // Fetch + parse manifest.
            let endpoint = discoveredEndpoint ?? endpointPaths[0]
            guard let manifest = await fetchManifest(baseURL: "http://\(reachableIP)", endpointPath: endpoint) else {
                cleanupHotspot(ssid: ssid)
                await returnToCaptureMode()
                complete(success: false, error: "Failed to fetch manifest")
                return
            }
            let files = parseManifest(manifest)

            // Download (empty manifest is a successful no-op).
            let (completedFiles, totalBytes) = files.isEmpty
                ? (0, Int64(0))
                : await downloadFiles(baseURL: "http://\(reachableIP)", files: files)

            cleanupHotspot(ssid: ssid)
            await returnToCaptureMode()
            complete(files: completedFiles, bytes: totalBytes, success: true, error: nil)

        } catch let error as CyanSyncError where error.errorDescription == CyanSyncError.cancelled.errorDescription {
            // User cancelled: tear down the hotspot + return the device to capture mode
            // (the success/failure paths do this inline, but the cancel throw skips them).
            if let ssid = joinedSSID { cleanupHotspot(ssid: ssid) }
            await returnToCaptureMode()
            complete(success: false, error: "Cancelled")
        } catch {
            await returnToCaptureMode()
            // Low battery is the most likely reason the AP never came up — say so plainly.
            let message = (lastBattery >= 0 && lastBattery < 25)
                ? "Glasses battery is low (\(lastBattery)%). Charge the glasses, then sync again."
                : error.localizedDescription
            complete(success: false, error: message)
        }
    }

    /// Boot the WiFi AP and reach the device, retrying up to 3× because a single openWifi boot can
    /// leave a "hollow" AP (BLE acks + returns creds, but the radio never broadcasts). Each attempt
    /// is the proven openWifi-only sequence (no setDeviceMode). Returns once the device is reachable;
    /// only throws after all attempts fail.
    private func bootAndReachDevice() async throws -> (ssid: String, ip: String) {
        let maxBootAttempts = 3
        var lastError: Error = CyanSyncError.noReachableIP

        for attempt in 1...maxBootAttempts {
            try checkCancelled()

            await waitForPeripheralIdle()

            do {
                // openWifi returns credentials (SSID/pass) but our bundled QCSDK's command omits the
                // trailing 0x02 byte the official app sends — and that byte is what actually powers
                // on the AP radio on V2.0 firmware. So after getting the SSID, send the CORRECTED
                // command ourselves to physically enable the AP.
                let (ssid, blePassword) = try await requestWifiCredentials()
                joinedSSID = ssid
                try checkCancelled()

                sendCorrectedEnableAP()
                try? await Task.sleep(nanoseconds: 2_000_000_000) // let the AP radio come up
                try checkCancelled()

                await checkDeviceStatus()
                let bleIP = try await waitForHotspotReady()
                deviceIP = bleIP
                try checkCancelled()

                await checkDeviceStatus()
                await confirmHotspotBroadcasting()
                try checkCancelled()

                let password = blePassword.isEmpty ? hotspotPassword : blePassword
                let ip = try await joinAndReachDevice(ssid: ssid, password: password)
                return (ssid, ip)
            } catch let e as CyanSyncError where e.errorDescription == CyanSyncError.cancelled.errorDescription {
                throw e // cancellation: stop immediately
            } catch {
                // If the user cancelled (even if this attempt failed with a different error first),
                // stop now instead of starting another boot attempt.
                if cancelled { throw CyanSyncError.cancelled }
                lastError = error
                // Clear any partial hotspot config before the next attempt.
                if let ssid = joinedSSID { cleanupHotspot(ssid: ssid) }
            }
        }

        throw lastError
    }

    /// The official HeyCyan app's openWifi(.transfer) command sends payload `02 01 04 02`; our
    /// bundled QCSDK sends `02 01 04` (missing the trailing `02`) — and that byte is what actually
    /// powers on the AP radio on AM01CY V2.0 firmware. The SDK builds the short command internally,
    /// so we write the corrected 10-byte command straight to the command characteristic ourselves.
    ///
    /// Captured from the official app over BLE:  BC 41 04 00 D3 5D 02 01 04 02
    /// (BC=start, 41=opcode, 04 00=payload len, D3 5D=crc16, 02 01 04 02=payload).
    private func sendCorrectedEnableAP() {
        let cmdCharUUID = CBUUID(string: "de5bf72a-d711-4e47-af26-65e3012a5dc7")
        // connectedPeripheral is imported as non-optional (nonnull), but can be nil at runtime.
        let connected: CBPeripheral? = QCCentralManager.shared().connectedPeripheral
        guard let peripheral = connected else { return }
        let characteristic = (peripheral.services ?? [])
            .flatMap { $0.characteristics ?? [] }
            .first { $0.uuid == cmdCharUUID }
        guard let characteristic else { return }
        let command = Data([0xBC, 0x41, 0x04, 0x00, 0xD3, 0x5D, 0x02, 0x01, 0x04, 0x02])
        peripheral.writeValue(command, for: characteristic, type: .withoutResponse)
    }

    /// Read battery level — the AP is power-hungry and the firmware disables it when low, the most
    /// common reason the hotspot never broadcasts. Stored in `lastBattery` for the failure message.
    private func readBatteryLevel() async {
        let level = await withCheckedContinuation { (cont: CheckedContinuation<Int, Never>) in
            QCSDKCmdCreator.getDeviceBattery({ level, _ in
                cont.resume(returning: Int(level))
            }, fail: {
                cont.resume(returning: -1)
            })
        }
        lastBattery = level
    }

    // MARK: - Step 0: Wait for the BLE peripheral to be idle

    /// Wait (briefly) for the BLE peripheral to stop being busy (the continuous type-11 report
    /// flood) before opening WiFi, so the openWifi command isn't dropped. Matches the demo's
    /// isPeripheralFreeNow gate. No setDeviceMode — the AP is booted by openWifi itself.
    private func waitForPeripheralIdle() async {
        for _ in 1...5 {
            if QCSDKCmdCreator.isPeripheralFreeNow() { return }
            try? await Task.sleep(nanoseconds: 1_500_000_000)
        }
    }

    // MARK: - Step 1: Request WiFi Credentials (openWifiWithMode)

    private enum OpenWifiOutcome {
        case ok(ssid: String, password: String)
        case fail(code: Int)
    }

    /// Ask the glasses to open their WiFi AP and return credentials. openWifi can transiently fail
    /// (code -2 = device busy / no response) right after the mode change, so retry a few times.
    private func requestWifiCredentials() async throws -> (ssid: String, password: String) {
        let maxAttempts = 4
        var lastCode = 0

        for attempt in 1...maxAttempts {
            try checkCancelled()

            let outcome: OpenWifiOutcome = await withCheckedContinuation { cont in
                QCSDKCmdCreator.openWifi(with: .transfer) { ssid, password in
                    cont.resume(returning: .ok(ssid: ssid, password: password))
                } fail: { code in
                    cont.resume(returning: .fail(code: Int(code)))
                }
            }

            switch outcome {
            case .ok(let ssid, let password):
                return (ssid, password)
            case .fail(let code):
                lastCode = code
                if attempt < maxAttempts {
                    try await Task.sleep(nanoseconds: 2_500_000_000)
                }
            }
        }

        throw CyanSyncError.wifiCredentialsFailed(code: lastCode)
    }

    // MARK: - Device Status Check (getDeviceConfigWithFinished)

    /// Check device status — demo uses this between major steps to catch state inconsistencies.
    private func checkDeviceStatus() async {
        await withCheckedContinuation { cont in
            QCSDKCmdCreator.getDeviceConfig { _, _, _ in
                cont.resume()
            }
        }
    }

    // MARK: - Step 2: Wait for Hotspot Ready (getDeviceWifiIPSuccess)

    /// Poll BLE until glasses confirm hotspot is broadcasting — demo says "never skip" this.
    private func waitForHotspotReady() async throws -> String {
        for _ in 1...10 {
            let ip = await withCheckedContinuation { cont in
                QCSDKCmdCreator.getDeviceWifiIPSuccess({ ip in
                    cont.resume(returning: ip)
                }, failed: {
                    cont.resume(returning: nil)
                })
            }
            if let ip = ip, !ip.isEmpty {
                return ip
            }
            try await Task.sleep(nanoseconds: 1_500_000_000) // 1.5s between polls
        }
        throw CyanSyncError.wifiCredentialsFailed(code: -1)
    }

    /// Demo's confirmHotspotIsBroadcastingOverBluetooth: re-confirm the hotspot IP over BLE up to
    /// 3× (2s apart) before applying the WiFi config — giving the AP radio extra time to come up.
    private func confirmHotspotBroadcasting() async {
        for _ in 1...3 {
            let ip = await withCheckedContinuation { (cont: CheckedContinuation<String?, Never>) in
                QCSDKCmdCreator.getDeviceWifiIPSuccess({ ip in
                    cont.resume(returning: ip)
                }, failed: {
                    cont.resume(returning: nil)
                })
            }
            if let ip = ip, !ip.isEmpty {
                deviceIP = ip
                return
            }
            try? await Task.sleep(nanoseconds: 2_000_000_000)
        }
    }

    // MARK: - Step 4: Join the AP and reach the device (apply config + probe, retried)

    /// Apply the WiFi config ONCE, then patiently wait/probe for iOS to associate. Re-applying
    /// (each apply calls removeConfiguration first) tears down an in-progress join — manual joins
    /// work precisely because nothing interrupts them — so we apply a single time and only re-apply
    /// rarely as a last resort. joinOnce=false makes iOS treat CY01 as a committed saved network,
    /// closer to a manual join, instead of a one-shot it readily abandons.
    private func joinAndReachDevice(ssid: String, password: String) async throws -> String {
        // If the phone is already on the glasses' network (e.g. joined manually, or left over from
        // a prior sync), skip the reconfigure — re-applying would needlessly drop and rejoin.
        var code = 0
        if await currentSSID() != ssid {
            // Let the AP radio settle for a moment before applying the WiFi config.
            try await Task.sleep(nanoseconds: 3_000_000_000)
            let (_, c) = await applyHotspotConfigOnce(ssid: ssid, password: password)
            code = c
        }

        // Short loop: when the AP is hollow every probe just times out, so a long loop only delays
        // the reset-based retry in bootAndReachDevice. Give the AP ~30s to become reachable.
        let maxRounds = 6
        for _ in 1...maxRounds {
            try checkCancelled()
            try await Task.sleep(nanoseconds: 3_000_000_000)

            if let ip = await probeOnce() {
                return ip
            }
        }

        if code != 0 && code != NEHotspotConfigurationError.alreadyAssociated.rawValue {
            throw CyanSyncError.hotspotJoinFailed(ssid: ssid, code: code, message: "join failed")
        }
        throw CyanSyncError.noReachableIP
    }

    /// Single NEHotspotConfiguration apply. Returns (joined?, errorCode) — code 0 = success,
    /// 13 = already associated (treated as success).
    private func applyHotspotConfigOnce(ssid: String, password: String) async -> (ok: Bool, code: Int) {
        NEHotspotConfigurationManager.shared.removeConfiguration(forSSID: ssid)
        try? await Task.sleep(nanoseconds: 300_000_000) // settle

        let config = NEHotspotConfiguration(ssid: ssid, passphrase: password, isWEP: false)
        config.joinOnce = false

        return await withCheckedContinuation { (cont: CheckedContinuation<(Bool, Int), Never>) in
            NEHotspotConfigurationManager.shared.apply(config) { error in
                if let error = error as NSError? {
                    if error.domain == NEHotspotConfigurationErrorDomain,
                       error.code == NEHotspotConfigurationError.alreadyAssociated.rawValue {
                        cont.resume(returning: (true, error.code))
                    } else {
                        cont.resume(returning: (false, error.code))
                    }
                } else {
                    cont.resume(returning: (true, 0))
                }
            }
        }
    }

    /// One pass over candidate IPs (BLE-reported IP first), returning the first that answers 200.
    private func probeOnce() async -> String? {
        // Keep this fast: while not joined every IP just times out, so a long list makes each
        // round drag for ~20s. Probe only the IPs the glasses actually reported (BLE deviceIP +
        // gateway) plus the priority set, with a short timeout.
        var candidates = [String]()
        if let bleIP = deviceIP, !bleIP.isEmpty { candidates.append(bleIP) }
        candidates.append(contentsOf: priorityIPs)
        // De-dup, preserving order.
        var seen = Set<String>()
        let ips = candidates.filter { seen.insert($0).inserted }

        for ip in ips {
            guard let url = URL(string: "http://\(ip)/files/media.config") else { continue }
            var request = URLRequest(url: url, timeoutInterval: 1.5)
            request.httpMethod = "GET"
            request.cachePolicy = .reloadIgnoringLocalCacheData
            do {
                let (_, response) = try await wifiSession.data(for: request)
                if let http = response as? HTTPURLResponse, http.statusCode == 200 {
                    deviceIP = ip
                    if let endpoint = await probeEndpoints(ip: ip) {
                        discoveredEndpoint = endpoint
                    }
                    return ip
                }
            } catch {
                continue
            }
        }
        return nil
    }

    /// SSID of the Wi-Fi the phone is currently on, or nil if it can't be read. Works now that the
    /// `com.apple.developer.networking.wifi-info` entitlement is present (+ location granted / we
    /// configured the network). Returns nil rather than failing, so callers treat it as "unknown".
    private func currentSSID() async -> String? {
        await withCheckedContinuation { (cont: CheckedContinuation<String?, Never>) in
            NEHotspotNetwork.fetchCurrent { network in
                cont.resume(returning: network?.ssid)
            }
        }
    }

    // MARK: - Step 3: Endpoint probing

    private func probeEndpoints(ip: String) async -> String? {
        for path in endpointPaths {
            guard let url = URL(string: "http://\(ip)\(path)") else { continue }
            var request = URLRequest(url: url, timeoutInterval: 5)
            request.httpMethod = "GET"
            request.cachePolicy = .reloadIgnoringLocalCacheData
            do {
                let (_, response) = try await wifiSession.data(for: request)
                if let http = response as? HTTPURLResponse, http.statusCode == 200 {
                    return path
                }
            } catch {
                continue
            }
        }
        return nil
    }

    // MARK: - Step 4: Fetch Manifest

    private func fetchManifest(baseURL: String, endpointPath: String) async -> String? {
        let pathsToTry = [endpointPath] + endpointPaths.filter { $0 != endpointPath }

        for path in pathsToTry {
            guard let url = URL(string: "\(baseURL)\(path)") else { continue }
            var request = URLRequest(url: url, timeoutInterval: 10)
            request.httpMethod = "GET"
            request.cachePolicy = .reloadIgnoringLocalCacheData
            do {
                let (data, response) = try await wifiSession.data(for: request)
                if let http = response as? HTTPURLResponse, http.statusCode == 200,
                   let body = String(data: data, encoding: .utf8), !body.isEmpty {
                    return body
                }
            } catch {
                continue
            }
        }
        return nil
    }

    // MARK: - Step 5: Parse Manifest

    private func parseManifest(_ manifest: String) -> [String] {
        return manifest
            .components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty && !$0.hasPrefix("#") }
            .map { line in
                if line.hasPrefix("http://") || line.hasPrefix("https://") {
                    return line
                } else if line.hasPrefix("/") {
                    return line
                } else {
                    return "/files/\(line)"
                }
            }
    }

    // MARK: - Step 6: Download Files

    private func downloadFiles(baseURL: String, files: [String]) async -> (count: Int, bytes: Int64) {
        let mediaDir = mediaDirectory()
        var completedFiles = 0
        var totalBytes: Int64 = 0

        for (index, filePath) in files.enumerated() {
            guard !cancelled else { break }

            let urlString: String
            if filePath.hasPrefix("http://") || filePath.hasPrefix("https://") {
                urlString = filePath
            } else {
                urlString = "\(baseURL)\(filePath)"
            }

            let filename = (filePath as NSString).lastPathComponent
            let destDir = subDirectory(for: filename, in: mediaDir)
            let dest = destDir.appendingPathComponent(filename)

            let written = await downloadFile(url: urlString, to: dest)
            if written > 0 {
                completedFiles += 1
                totalBytes += written
                // Glasses store audio as a raw Opus packet stream (no container); rewrite it as a
                // standard Ogg/Opus file in place so the media player can open it.
                if filename.lowercased().hasSuffix(".opus") {
                    wrapOpusFileInPlace(at: dest)
                }
                // Notify per-file so the gallery indexes + thumbnails this file incrementally.
                let sub = dest.deletingLastPathComponent().lastPathComponent
                if sub == "photo" || sub == "video" || sub == "audio" {
                    onEvent(.fileSynced(path: dest.path, type: sub))
                }
            }
            onEvent(.progress(current: index + 1, total: files.count))
        }

        return (completedFiles, totalBytes)
    }

    private func downloadFile(url urlString: String, to dest: URL) async -> Int64 {
        guard let url = URL(string: urlString) else { return 0 }
        do {
            let (tempURL, response) = try await wifiSession.download(from: url)
            let status = (response as? HTTPURLResponse)?.statusCode ?? -1
            guard status == 200 else { return 0 }
            let attrs = try FileManager.default.attributesOfItem(atPath: tempURL.path)
            let size = attrs[.size] as? Int64 ?? 0
            if FileManager.default.fileExists(atPath: dest.path) {
                try FileManager.default.removeItem(at: dest)
            }
            try FileManager.default.moveItem(at: tempURL, to: dest)
            return size
        } catch {
            return 0
        }
    }

    /// Rewrite a downloaded glasses `.opus` (raw Opus packets) in place as a standard Ogg/Opus file.
    /// No-op if the file is already Ogg or the packet layout can't be detected.
    private func wrapOpusFileInPlace(at url: URL) {
        guard let data = try? Data(contentsOf: url) else { return }
        let wrapped = OpusOggWrapper.wrapIfNeeded(data)
        if wrapped != data {
            try? wrapped.write(to: url)
        }
    }

    // MARK: - Step 7: Cleanup

    private func cleanupHotspot(ssid: String) {
        NEHotspotConfigurationManager.shared.removeConfiguration(forSSID: ssid)
    }

    /// End WiFi transfer cleanly. setDeviceMode triggers OPERATIONS, so .photo/.video here would
    /// literally take a photo / start a recording — .transferStop stops the transfer and powers
    /// the AP back down.
    private func returnToCaptureMode() async {
        _ = await setDeviceMode(.transferStop)
    }

    private func setDeviceMode(_ mode: QCOperatorDeviceMode) async -> Bool {
        return await withCheckedContinuation { cont in
            QCSDKCmdCreator.setDeviceMode(mode, success: {
                cont.resume(returning: true)
            }, fail: { _ in
                cont.resume(returning: false)
            })
        }
    }

    // MARK: - Helpers

    private func checkCancelled() throws {
        if cancelled { throw CyanSyncError.cancelled }
    }

    private func mediaDirectory() -> URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let dir = docs.appendingPathComponent("GlassMedia")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private func subDirectory(for filename: String, in base: URL) -> URL {
        let lower = filename.lowercased()
        let subdir: String
        if lower.hasSuffix(".jpg") || lower.hasSuffix(".jpeg") || lower.hasSuffix(".png") {
            subdir = "photo"
        } else if lower.hasSuffix(".mp4") || lower.hasSuffix(".mov") || lower.hasSuffix(".avi") {
            subdir = "video"
        } else if lower.hasSuffix(".mp3") || lower.hasSuffix(".wav") || lower.hasSuffix(".aac") ||
                    lower.hasSuffix(".m4a") || lower.hasSuffix(".opus") {
            subdir = "audio"
        } else {
            subdir = "other"
        }
        let dir = base.appendingPathComponent(subdir)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }
}
#endif // !targetEnvironment(simulator)
