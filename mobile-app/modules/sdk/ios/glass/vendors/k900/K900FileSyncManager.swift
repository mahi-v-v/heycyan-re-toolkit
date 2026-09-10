#if !targetEnvironment(simulator)
import Foundation
import NetworkExtension
import XingYiGlassesSDK

/// Manages WiFi hotspot connection + socket-based file transfer from K900 glass.
/// Mirrors K900FileSyncManager.kt exactly.
class K900FileSyncManager {

    private weak var adapter: K900Adapter?

    private var hotspotContinuation: CheckedContinuation<Void, Error>?
    private var syncContinuation: CheckedContinuation<Void, Error>?
    private let lock = NSLock()

    private var totalFiles = 0
    private var receivedFiles = 0
    private var totalBytes: Int64 = 0
    private var isCancelled = false

    // Timeouts for sync operations
    private let hotspotConnectionTimeout: UInt64 = 30_000_000_000 // 30 seconds
    private let firstFileTimeout: UInt64 = 60_000_000_000          // 60 seconds - first file must arrive

    private let mediaDirectory: String

    init(adapter: K900Adapter) {
        self.adapter = adapter
        self.mediaDirectory = GlassManager.shared.getMediaDirectory()
        ensureMediaDirectoryExists()
    }

    // MARK: - Public entry point

    func sync() async throws {
        isCancelled = false
        print("[K900FileSyncManager] sync() — starting, mediaDirectory=\(mediaDirectory)")
        adapter?.onSyncStarted()

        // Step 1: Request glass to open WiFi hotspot
        XYBleManager.sharedInstance().requestServerOpenHotspot()

        // Step 2: Wait for WiFi + socket connection ("sr_apsr" → handleHotspotResult → connectToHotspot → resumeHotspot)
        do {
            try await withThrowingTaskGroup(of: Void.self) { group in
                group.addTask {
                    try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
                        self.lock.withLock { self.hotspotContinuation = cont }
                    }
                }
                group.addTask {
                    try await Task.sleep(nanoseconds: self.hotspotConnectionTimeout)
                    throw NSError(domain: "K900FileSyncManager", code: -3,
                                userInfo: [NSLocalizedDescriptionKey: "Hotspot connection timeout"])
                }
                try await group.next()
                group.cancelAll()
            }
        } catch {
            print("[K900FileSyncManager] sync() — hotspot FAILED: \(error.localizedDescription)")
            throw error
        }

        // Step 3: WiFi+socket connected — now wait for all files to be received ("sr_askFileFinish" → handleSyncComplete)
        // First file MUST arrive within 60 seconds, or sync is considered failed
        do {
            try await withThrowingTaskGroup(of: Void.self) { group in
                group.addTask {
                    try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
                        self.lock.withLock { self.syncContinuation = cont }
                    }
                }
                group.addTask {
                    try await Task.sleep(nanoseconds: self.firstFileTimeout)
                    let filesReceived = self.lock.withLock { self.receivedFiles }
                    if filesReceived == 0 {
                        throw NSError(domain: "K900FileSyncManager", code: -4,
                                    userInfo: [NSLocalizedDescriptionKey: "Sync failed: no files received within 60 seconds"])
                    }
                    // Files have been received — wait indefinitely for completion signal
                    try await Task.sleep(nanoseconds: UInt64.max)
                }
                try await group.next()
                group.cancelAll()
            }
        } catch {
            print("[K900FileSyncManager] sync() — sync FAILED: \(error.localizedDescription)")
            throw error
        }
        print("[K900FileSyncManager] sync() — completed: receivedFiles=\(receivedFiles) totalBytes=\(totalBytes)")
    }

    func cancelSync() {
        print("[K900FileSyncManager] cancelSync() — receivedFiles=\(receivedFiles)")
        lock.withLock {
            isCancelled = true
            let err = NSError(domain: "K900FileSyncManager", code: -1,
                userInfo: [NSLocalizedDescriptionKey: "Sync cancelled"])
            hotspotContinuation?.resume(throwing: err)
            hotspotContinuation = nil
            syncContinuation?.resume(throwing: err)
            syncContinuation = nil
        }
        XYBleManager.sharedInstance().closeHotspot()
    }

    // MARK: - Called by K900Adapter when "sr_apsr" arrives

    func handleHotspotResult(_ data: [String: Any]) {
        let ssid = data["ssid"] as? String ?? ""
        let pwd  = data["pwd"]  as? String ?? ""
        guard !ssid.isEmpty else {
            print("[K900FileSyncManager] handleHotspotResult — ERROR: empty SSID, data=\(data)")
            resumeHotspot(error: NSError(domain: "K900FileSyncManager", code: -2,
                userInfo: [NSLocalizedDescriptionKey: "Empty SSID in hotspot response"]))
            return
        }
        Task {
            do {
                try await connectToHotspot(ssid: ssid, password: pwd)
            } catch {
                print("[K900FileSyncManager] handleHotspotResult — connectToHotspot failed: \(error.localizedDescription)")
                resumeHotspot(error: error)
            }
        }
    }

    // MARK: - Called by K900Adapter when "sr_asfl" arrives

    func handleSyncComplete() {
        let total = lock.withLock { totalBytes }
        let count = lock.withLock { receivedFiles }
        print("[K900FileSyncManager] handleSyncComplete — receivedFiles=\(count) totalBytes=\(total)")
        adapter?.onSyncCompleted(totalFiles: count, totalBytes: total, success: true)
        lock.withLock {
            syncContinuation?.resume(returning: ())
            syncContinuation = nil
        }
    }

    // MARK: - WiFi connection

    private func connectToHotspot(ssid: String, password: String) async throws {
        let config = NEHotspotConfiguration(ssid: ssid, passphrase: password, isWEP: false)
        config.joinOnce = true

        // Suppress "no internet connection" popup - this is a local hotspot for file transfer
        // Setting joinOnce=true helps, but the popup may still appear
        // User should tap "Use Without Internet" or equivalent to proceed

        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            NEHotspotConfigurationManager.shared.apply(config) { error in
                if let error = error as? NEHotspotConfigurationError,
                   error == .alreadyAssociated {
                    cont.resume(returning: ())
                } else if let error {
                    print("[K900FileSyncManager] connectToHotspot — NEHotspotConfiguration FAILED: \(error.localizedDescription) (code=\((error as NSError).code))")
                    cont.resume(throwing: error)
                } else {
                    cont.resume(returning: ())
                }
            }
        }

        // Per K900 demo app: wait 7 seconds for the glass socket server to be ready
        try await Task.sleep(nanoseconds: 7_000_000_000)

        let cancelled = lock.withLock { isCancelled }
        guard !cancelled else {
            throw NSError(domain: "K900FileSyncManager", code: -1,
                        userInfo: [NSLocalizedDescriptionKey: "Sync cancelled"])
        }

        setupSocketCallbacks()
        XYSocketManager.sharedInstance().xy_connectSocket()
        // Socket connectStatusBlock will trigger askFile
    }

    // MARK: - Socket file transfer

    private func setupSocketCallbacks() {
        XYSocketManager.sharedInstance().connectStatusBlock = { [weak self] error in
            guard let self else { return }
            if let error {
                print("[K900FileSyncManager] socket — connect FAILED: \(error.localizedDescription)")
                self.resumeHotspot(error: error)
                return
            }
            self.resumeHotspot(error: nil)
            XYBleManager.sharedInstance().ask(XYFileTypeAll)
        }

        XYSocketManager.sharedInstance().receiveDataBlock = { [weak self] isCompleted, fileType, fileName, data, fileSize in
            guard let self else { return }
            let cancelled = self.lock.withLock { self.isCancelled }
            guard !fileName.isEmpty, !cancelled else { return }

            if isCompleted {
                self.lock.withLock {
                    self.receivedFiles += 1
                    self.totalBytes += Int64(fileSize)
                }
                self.saveFile(data: data, name: fileName, type: fileType, size: fileSize)
                let xyFileType: XYFileType
                switch fileType {
                case XYFileDataTypePhoto: xyFileType = XYFileTypePhoto
                case XYFileDataTypeVideo: xyFileType = XYFileTypeVideo
                case XYFileDataTypeAudio: xyFileType = XYFileTypeAudio
                default:                  xyFileType = XYFileTypePhoto
                }
                XYBleManager.sharedInstance().sendFileRecv(xyFileType, fileName: fileName)
            }
        }
    }

    private func resumeHotspot(error: Error?) {
        lock.withLock {
            if let error {
                hotspotContinuation?.resume(throwing: error)
            } else {
                hotspotContinuation?.resume(returning: ())
            }
            hotspotContinuation = nil
        }
    }

    // MARK: - File saving

    private func saveFile(data: Data, name: String, type: XYFileDataType, size: Int) {
        let subdir: String
        switch type {
        case XYFileDataTypePhoto: subdir = "photo"
        case XYFileDataTypeVideo: subdir = "video"
        case XYFileDataTypeAudio: subdir = "audio"
        default:                  subdir = "files"
        }

        let dirURL = URL(fileURLWithPath: mediaDirectory).appendingPathComponent(subdir)
        let _ = try? FileManager.default.createDirectory(at: dirURL, withIntermediateDirectories: true)
        let fileURL = dirURL.appendingPathComponent(name)

        do {
            try data.write(to: fileURL)
            print("[K900FileSyncManager] saved '\(name)' (\(data.count) bytes)")
            // Notify per-file so the gallery indexes + thumbnails this file incrementally.
            if subdir == "photo" || subdir == "video" || subdir == "audio" {
                adapter?.onFileSynced(path: fileURL.path, type: subdir)
            }
        } catch {
            print("[K900FileSyncManager] saveFile — ERROR writing '\(name)': \(error.localizedDescription)")
            adapter?.onError("Failed to save file \(name): \(error.localizedDescription)")
        }
    }

    private func ensureMediaDirectoryExists() {
        let url = URL(fileURLWithPath: mediaDirectory)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    }
}
#endif // !targetEnvironment(simulator)
