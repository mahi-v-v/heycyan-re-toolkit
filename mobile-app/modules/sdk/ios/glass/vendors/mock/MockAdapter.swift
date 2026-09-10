import Foundation

/// Mock adapter for development and testing without physical hardware
/// Simulates all glass device behaviors with fake data (mirrors MockAdapter.kt)
class MockAdapter: BaseGlassAdapter {

    override var capabilities: [String: Bool] {
        [
            "audio": false,
            "sensors": false,
            "display": false,
            "wakeword": false,
            "battery": true,
            "camera": false
        ]
    }

    private var simulatedBattery = 95
    private var isDeviceReady = false
    private var simulationTask: Task<Void, Never>?

    private var mockMediaConfig = MediaConfigData(
        photo: PhotoConfigData(width: 1920, height: 1080),
        video: VideoConfigData(width: 1920, height: 1080, quality: 2, duration: 30),
        audio: AudioConfigData(duration: 60)
    )

    // MARK: - GlassAdapter

    override func initAdapter() async throws {
        // Mock initialization — instant success
    }

    override func connect(deviceId: String) async throws {
        currentDeviceId = deviceId
        currentDeviceName = "Mock Glass Device"

        updateState(.connecting, reason: "Simulating connection")

        updateStorageInfo(GlassStorageInfo(
            totalSize: 32 * 1024 * 1024 * 1024,
            freeSize:  28 * 1024 * 1024 * 1024,
            usedSize:   4 * 1024 * 1024 * 1024,
            photoCount: 42,
            videoCount: 12,
            audioCount: 8
        ))

        try await Task.sleep(nanoseconds: 2_000_000_000) // 2s

        // 5% chance of simulated failure
        if Float.random(in: 0...1) < 0.05 {
            throw NSError(domain: "MockAdapter", code: -1,
                          userInfo: [NSLocalizedDescriptionKey: "Simulated connection failure"])
        }

        onConnected()
        startSimulation()
    }

    override func disconnect() async throws {
        updateState(.disconnecting, reason: "User requested disconnect")
        cancelReconnection()
        stopSimulation()
        try await Task.sleep(nanoseconds: 500_000_000) // 0.5s
        onDisconnected(reason: "User disconnected", wasExpected: true)
    }

    override func isConnected() -> Bool {
        return connectionState == .connected
    }

    override func sendCommand(_ command: String, params: [String: Any] = [:]) async throws {
        switch command {
        case "vibrate":
            let durationMs = (params["duration"] as? Int) ?? 200
            try await Task.sleep(nanoseconds: UInt64(durationMs) * 1_000_000)
        case "set_brightness":
            break // No-op in mock
        default:
            break
        }
    }

    override func getSystemInfo() async throws -> GlassSystemInfo {
        updateSystemInfoFields(
            firmwareVersion: "1.0.5-mock",
            appVersion: "2.1.0-mock",
            deviceModel: "Mock Glass Device",
            batteryLevel: simulatedBattery,
            isCharging: Float.random(in: 0...1) < 0.1,
            batteryVoltage: 3.7 + Float.random(in: 0...0.5),
            isReady: isDeviceReady
        )
        return systemInfo
    }

    override func takePhoto(id: Int? = nil, filename: String? = nil) async throws -> PhotoResult {
        guard isConnected() else {
            throw NSError(domain: "MockAdapter", code: -1,
                          userInfo: [NSLocalizedDescriptionKey: "Not connected to mock device"])
        }
        try await Task.sleep(nanoseconds: 500_000_000)
        let success = Int.random(in: 0..<100) < 90
        if success {
            let newCount = storageInfo.photoCount + 1
            updateStorageInfo(storageInfo.copy(photoCount: newCount))
            return PhotoResult(
                success: true, errorCode: 0, photoCount: newCount,
                filename: filename ?? "photo_\(Date().timeIntervalSince1970).jpg"
            )
        } else {
            return PhotoResult(success: false, errorCode: -3, photoCount: storageInfo.photoCount)
        }
    }

    override func takeAiImage() async throws -> String {
        guard isConnected() else {
            throw NSError(domain: "MockAdapter", code: -1,
                          userInfo: [NSLocalizedDescriptionKey: "Not connected to mock device"])
        }
        try await Task.sleep(nanoseconds: 1_500_000_000)
        if Int.random(in: 0..<100) < 90 {
            return "/mock/cache/mock_ai_image_\(Date().timeIntervalSince1970).jpg"
        } else {
            throw NSError(domain: "MockAdapter", code: -2,
                          userInfo: [NSLocalizedDescriptionKey: "Mock AI image capture failed"])
        }
    }

    override func startVideo(id: Int? = nil, filename: String? = nil) async throws -> VideoResult {
        guard isConnected() else {
            throw NSError(domain: "MockAdapter", code: -1,
                          userInfo: [NSLocalizedDescriptionKey: "Not connected to mock device"])
        }
        try await Task.sleep(nanoseconds: 500_000_000)
        let success = Int.random(in: 0..<100) < 90
        return VideoResult(success: success, errorCode: success ? 0 : -3, videoCount: storageInfo.videoCount)
    }

    override func stopVideo() async throws {
        guard isConnected() else { return }
        try await Task.sleep(nanoseconds: 500_000_000)
        if Int.random(in: 0..<100) < 90 {
            updateStorageInfo(storageInfo.copy(videoCount: storageInfo.videoCount + 1))
        }
    }

    override func startAudio(filename: String? = nil) async throws -> AudioResult {
        guard isConnected() else {
            throw NSError(domain: "MockAdapter", code: -1,
                          userInfo: [NSLocalizedDescriptionKey: "Not connected to mock device"])
        }
        try await Task.sleep(nanoseconds: 500_000_000)
        let success = Int.random(in: 0..<100) < 90
        return AudioResult(success: success, errorCode: success ? 0 : -3, audioCount: storageInfo.audioCount)
    }

    override func stopAudio() async throws {
        guard isConnected() else { return }
        try await Task.sleep(nanoseconds: 500_000_000)
        if Int.random(in: 0..<100) < 90 {
            updateStorageInfo(storageInfo.copy(audioCount: storageInfo.audioCount + 1))
        }
    }

    // MARK: - Device Management

    override func clearMedia() async throws {
        guard isConnected() else { return }
        try await Task.sleep(nanoseconds: 300_000_000)
        updateStorageInfo(storageInfo.copy(photoCount: 0, videoCount: 0, audioCount: 0))
        print("[MockAdapter] clearMedia — storage cleared")
    }

    override func reboot() async throws {
        print("[MockAdapter] reboot (simulated)")
    }

    override func factoryReset() async throws {
        print("[MockAdapter] factoryReset (simulated)")
    }

    override func setAiStatus(_ status: String) async throws {
        print("[MockAdapter] setAiStatus(\(status)) (simulated)")
    }

    override func getMediaConfig() async throws -> MediaConfigData {
        guard isConnected() else {
            throw NSError(domain: "MockAdapter", code: -1,
                          userInfo: [NSLocalizedDescriptionKey: "Not connected to mock device"])
        }
        try await Task.sleep(nanoseconds: 300_000_000)
        return mockMediaConfig
    }

    override func setMediaConfig(_ config: [String: Any]) async throws -> Bool {
        guard isConnected() else {
            throw NSError(domain: "MockAdapter", code: -1,
                          userInfo: [NSLocalizedDescriptionKey: "Not connected to mock device"])
        }
        try await Task.sleep(nanoseconds: 300_000_000)
        if let photoMap = config["photo"] as? [String: Any],
           let w = photoMap["width"] as? Int, let h = photoMap["height"] as? Int {
            mockMediaConfig.photo = PhotoConfigData(width: w, height: h)
        }
        if let videoMap = config["video"] as? [String: Any],
           let w = videoMap["width"] as? Int, let h = videoMap["height"] as? Int,
           let q = videoMap["quality"] as? Int, let d = videoMap["duration"] as? Int {
            mockMediaConfig.video = VideoConfigData(width: w, height: h, quality: q, duration: d)
        }
        if let audioMap = config["audio"] as? [String: Any],
           let d = audioMap["duration"] as? Int {
            mockMediaConfig.audio = AudioConfigData(duration: d)
        }
        return true
    }

    override func syncFiles() async throws {
        guard isConnected() else {
            throw NSError(domain: "MockAdapter", code: -1,
                          userInfo: [NSLocalizedDescriptionKey: "Not connected to mock device"])
        }
        onSyncStarted()
        try await Task.sleep(nanoseconds: 1_000_000_000)

        let totalFiles = storageInfo.photoCount + storageInfo.videoCount + storageInfo.audioCount
        var completedFiles = 0
        var totalBytes: Int64 = 0

        onSyncProgress(current: 0, total: totalFiles)

        let fileCounts = [storageInfo.photoCount, storageInfo.videoCount, storageInfo.audioCount]
        for count in fileCounts {
            for _ in 0..<count {
                try await Task.sleep(nanoseconds: 600_000_000)
                completedFiles += 1
                totalBytes += Int64(Int.random(in: 100_000..<5_000_000))
                onSyncProgress(current: completedFiles, total: totalFiles)
            }
        }

        updateStorageInfo(storageInfo.copy(photoCount: 0, videoCount: 0, audioCount: 0))
        onSyncCompleted(totalFiles: completedFiles, totalBytes: totalBytes, success: true)
    }

    // MARK: - Simulation

    private func startSimulation() {
        simulationTask?.cancel()
        simulationTask = Task {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            guard !Task.isCancelled else { return }
            isDeviceReady = true
            onReady()

            var iteration = 0
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                guard !Task.isCancelled else { break }
                iteration += 1
                simulateSystemUpdate()
                if iteration % 10 == 0 { simulateStorageChange() }
                if Float.random(in: 0...1) < 0.02 {
                    onError("Simulated error: Random glitch", isFatal: false)
                }
            }
        }
    }

    private func stopSimulation() {
        simulationTask?.cancel()
        simulationTask = nil
    }

    private func simulateSystemUpdate() {
        simulatedBattery = max(20, simulatedBattery - Int.random(in: 0..<2))
        if Float.random(in: 0...1) < 0.1 {
            simulatedBattery = min(100, simulatedBattery + 5)
        }
    }

    private func simulateStorageChange() {
        let photoChange = Int.random(in: -2..<3)
        let videoChange = Int.random(in: -1..<2)
        let audioChange = Int.random(in: -1..<2)
        let newUsed = max(0, min(storageInfo.totalSize,
            storageInfo.usedSize + Int64.random(in: -100_000_000..<100_000_000)))
        updateStorageInfo(GlassStorageInfo(
            totalSize: storageInfo.totalSize,
            freeSize: storageInfo.totalSize - newUsed,
            usedSize: newUsed,
            photoCount: max(0, storageInfo.photoCount + photoChange),
            videoCount: max(0, storageInfo.videoCount + videoChange),
            audioCount: max(0, storageInfo.audioCount + audioChange)
        ))
    }

    override func destroy() {
        stopSimulation()
        super.destroy()
    }
}
