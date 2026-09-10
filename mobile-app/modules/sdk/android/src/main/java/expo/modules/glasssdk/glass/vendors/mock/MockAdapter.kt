package expo.modules.glasssdk.glass.vendors.mock

import android.content.Context
import expo.modules.glasssdk.glass.AudioConfigData
import expo.modules.glasssdk.glass.AudioResult
import expo.modules.glasssdk.glass.BaseGlassAdapter
import expo.modules.glasssdk.glass.GlassConnectionState
import expo.modules.glasssdk.glass.GlassStorageInfo
import expo.modules.glasssdk.glass.GlassSystemInfo
import expo.modules.glasssdk.glass.MediaConfigData
import expo.modules.glasssdk.glass.PhotoConfigData
import expo.modules.glasssdk.glass.PhotoResult
import expo.modules.glasssdk.glass.VideoConfigData
import expo.modules.glasssdk.glass.VideoResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Mock adapter for development and testing without physical hardware
 * Simulates all glass device behaviors with fake data
 */
class MockAdapter : BaseGlassAdapter() {

    override val capabilities = mapOf(
        "audio" to false,
        "sensors" to false,
        "display" to false,
        "wakeword" to false,
        "battery" to true,
        "camera" to false
    )

    private var simulatedBattery = 95
    private var isDeviceReady = false
    private var simulationJob: Job? = null

    // Simulated media config
    private var mockMediaConfig = MediaConfigData(
        photo = PhotoConfigData(width = 1920, height = 1080),
        video = VideoConfigData(width = 1920, height = 1080, quality = 2, duration = 30),
        audio = AudioConfigData(duration = 60)
    )

    override suspend fun init(context: Context) {
        // Mock initialization - instant success
        Log.d(TAG, "Mock adapter initialized")
    }

    override suspend fun connect(deviceId: String) {
        currentDeviceId = deviceId
        currentDeviceName = "Mock Glass Device"

        updateState(GlassConnectionState.CONNECTING, "Simulating connection")

        // Initialize storage info using base class
        updateStorageInfo(
            GlassStorageInfo(
                totalSize = 32L * 1024 * 1024 * 1024,  // 32 GB
                freeSize = 28L * 1024 * 1024 * 1024,   // 28 GB free
                usedSize = 4L * 1024 * 1024 * 1024,    // 4 GB used
                photoCount = 42,
                videoCount = 12,
                audioCount = 8
            )
        )

        // Simulate connection delay
        delay(2000)

        // Random 5% chance of connection failure to test error handling
        if (Random.nextFloat() < 0.05) {
            throw Exception("Simulated connection failure")
        }

        onConnected()
        startSimulation()
    }

    override suspend fun disconnect() {
        updateState(GlassConnectionState.DISCONNECTING, "User requested disconnect")
        cancelReconnection()
        stopSimulation()
        delay(500) // Simulate disconnect delay
        onDisconnected("User disconnected", wasExpected = true)
    }

    override fun isConnected(): Boolean {
        return connectionState == GlassConnectionState.CONNECTED
    }

    override suspend fun sendCommand(command: String, params: Map<String, Any>) {
        Log.d(TAG, "Mock command: $command with params: $params")

        when (command) {
            "vibrate" -> {
                val duration = (params["duration"] as? Number)?.toLong() ?: 200L
                Log.d(TAG, "Simulating vibration for ${duration}ms")
                delay(duration)
            }

            "set_brightness" -> {
                val level = (params["level"] as? Number)?.toInt() ?: 50
                Log.d(TAG, "Simulating brightness set to $level")
            }

            else -> {
                Log.w(TAG, "Unknown command: $command")
            }
        }
    }

    override suspend fun getSystemInfo(): GlassSystemInfo {
        // Build fresh system info with current simulated values
        updateSystemInfo(
            firmwareVersion = "1.0.5-mock",
            appVersion = "2.1.0-mock",
            deviceModel = "Mock Glass Device",
            batteryLevel = simulatedBattery,
            isCharging = Random.nextFloat() < 0.1,  // 10% chance
            batteryVoltage = 3.7f + Random.nextFloat() * 0.5f,
            isReady = isDeviceReady
        )

        return systemInfo
    }

    override suspend fun takePhoto(id: Int?, filename: String?): PhotoResult {
        Log.d(TAG, "Mock taking photo (id=$id, filename=$filename)")

        if (!isConnected()) {
            throw IllegalStateException("Not connected to mock device")
        }

        // Simulate async operation
        delay(500)

        // Simulate success with 90% probability
        val success = Random.nextInt(100) < 90

        if (success) {
            val current = storageInfo
            val newPhotoCount = current.photoCount + 1
            updateStorageInfo(current.copy(photoCount = newPhotoCount))

            return PhotoResult(
                success = true,
                errorCode = 0,
                photoCount = newPhotoCount,
                filename = filename ?: "photo_${System.currentTimeMillis()}.jpg"
            )
        } else {
            return PhotoResult(
                success = false,
                errorCode = -3, // Device error
                photoCount = storageInfo.photoCount
            )
        }
    }

    override suspend fun takeAiImage(): String {
        Log.d(TAG, "Mock taking AI image")

        if (!isConnected()) {
            throw IllegalStateException("Not connected to mock device")
        }

        // Simulate AI processing (longer delay)
        delay(1500)

        // Simulate 90% success rate
        if (Random.nextInt(100) < 90) {
            val filename = "mock_ai_image_${System.currentTimeMillis()}.jpg"
            Log.d(TAG, "Mock AI image captured: $filename")
            return "/mock/cache/$filename"
        } else {
            throw Exception("Mock AI image capture failed")
        }
    }

    override suspend fun startVideo(id: Int?, filename: String?): VideoResult {
        Log.d(TAG, "Mock starting video recording")

        if (!isConnected()) {
            throw IllegalStateException("Not connected to mock device")
        }

        delay(500)

        val success = Random.nextInt(100) < 90

        return VideoResult(
            success = success,
            errorCode = if (success) 0 else -3,
            videoCount = storageInfo.videoCount
        )
    }

    override suspend fun stopVideo() {
        Log.d(TAG, "Mock stopping video recording")

        if (!isConnected()) {
            throw IllegalStateException("Not connected to mock device")
        }

        delay(500)

        val success = Random.nextInt(100) < 90

        if (success) {
            val current = storageInfo
            val newVideoCount = current.videoCount + 1
            updateStorageInfo(current.copy(videoCount = newVideoCount))
        }
    }

    override suspend fun startAudio(filename: String?): AudioResult {
        Log.d(TAG, "Mock starting audio recording")

        if (!isConnected()) {
            throw IllegalStateException("Not connected to mock device")
        }

        delay(500)

        val success = Random.nextInt(100) < 90

        return AudioResult(
            success = success,
            errorCode = if (success) 0 else -3,
            audioCount = storageInfo.audioCount
        )
    }

    override suspend fun stopAudio() {
        Log.d(TAG, "Mock stopping audio recording")

        if (!isConnected()) {
            throw IllegalStateException("Not connected to mock device")
        }

        delay(500)

        val success = Random.nextInt(100) < 90

        if (success) {
            val current = storageInfo
            val newAudioCount = current.audioCount + 1
            updateStorageInfo(current.copy(audioCount = newAudioCount))
        }
    }

    override suspend fun clearMedia() {
        if (!isConnected()) return
        delay(300)
        updateStorageInfo(storageInfo.copy(photoCount = 0, videoCount = 0, audioCount = 0))
        Log.d(TAG, "Mock clearMedia — storage cleared")
    }

    override suspend fun reboot() {
        Log.d(TAG, "Mock reboot (simulated)")
    }

    override suspend fun factoryReset() {
        Log.d(TAG, "Mock factoryReset (simulated)")
    }

    override suspend fun setAiStatus(status: String) {
        Log.d(TAG, "Mock setAiStatus($status) (simulated)")
    }

    override suspend fun getMediaConfig(): MediaConfigData {
        Log.d(TAG, "Mock getting media config")

        if (!isConnected()) {
            throw IllegalStateException("Not connected to mock device")
        }

        // Simulate async delay
        delay(300)

        return mockMediaConfig
    }

    override suspend fun setMediaConfig(config: Map<String, Any>): Boolean {
        Log.d(TAG, "Mock setting media config: $config")

        if (!isConnected()) {
            throw IllegalStateException("Not connected to mock device")
        }

        // Simulate async delay
        delay(300)

        // Update mock config with provided values
        config["photo"]?.let { photoMap ->
            if (photoMap is Map<*, *>) {
                val width = (photoMap["width"] as? Number)?.toInt()
                val height = (photoMap["height"] as? Number)?.toInt()
                if (width != null && height != null) {
                    mockMediaConfig = mockMediaConfig.copy(
                        photo = PhotoConfigData(width, height)
                    )
                    Log.d(TAG, "Mock updated photo config: ${width}x${height}")
                }
            }
        }

        config["video"]?.let { videoMap ->
            if (videoMap is Map<*, *>) {
                val width = (videoMap["width"] as? Number)?.toInt()
                val height = (videoMap["height"] as? Number)?.toInt()
                val quality = (videoMap["quality"] as? Number)?.toInt()
                val duration = (videoMap["duration"] as? Number)?.toInt()
                if (width != null && height != null && quality != null && duration != null) {
                    mockMediaConfig = mockMediaConfig.copy(
                        video = VideoConfigData(width, height, quality, duration)
                    )
                    Log.d(TAG, "Mock updated video config: ${width}x${height}, q=$quality, d=$duration")
                }
            }
        }

        config["audio"]?.let { audioMap ->
            if (audioMap is Map<*, *>) {
                val duration = (audioMap["duration"] as? Number)?.toInt()
                if (duration != null) {
                    mockMediaConfig = mockMediaConfig.copy(
                        audio = AudioConfigData(duration)
                    )
                    Log.d(TAG, "Mock updated audio config: duration=$duration")
                }
            }
        }

        // Always succeeds in mock
        return true
    }

    override suspend fun syncFiles() {
        Log.d(TAG, "Mock syncing files")

        if (!isConnected()) {
            throw IllegalStateException("Not connected to mock device")
        }

        // Emit sync started
        onSyncStarted()

        // Simulate async file sync
        delay(1000)

        // Get total files to sync from storage info
        val totalFiles = storageInfo.photoCount + storageInfo.videoCount + storageInfo.audioCount
        val filesPerType = mapOf(
            1 to storageInfo.photoCount,  // Photos
            2 to storageInfo.videoCount,  // Videos
            3 to storageInfo.audioCount   // Audio
        )

        var completedFiles = 0
        var totalBytes = 0L

        // Emit initial progress
        onSyncProgress(0, totalFiles)

        // Simulate syncing each file type
        filesPerType.forEach { (fileType, count) ->
            repeat(count) { index ->
                val typeName = when (fileType) {
                    1 -> "photo"
                    2 -> "video"
                    3 -> "audio"
                    else -> "file"
                }
                val fileName = "mock_${typeName}_${System.currentTimeMillis()}_$index.jpg"
                val fileSize = Random.nextInt(100_000, 5_000_000).toLong()

                // Simulate file download delay
                delay(600)

                completedFiles++
                totalBytes += fileSize

                // Emit overall progress after each file completes
                onSyncProgress(completedFiles, totalFiles)

                Log.d(TAG, "Mock file completed: $fileName ($completedFiles/$totalFiles)")
            }
        }

        // Update storage info - clear all media counts
        updateStorageInfo(storageInfo.copy(
            photoCount = 0,
            videoCount = 0,
            audioCount = 0
        ))

        // Emit completion
        onSyncCompleted(
            totalFiles = completedFiles,
            totalBytes = totalBytes,
            success = true
        )

        Log.d(TAG, "Mock file sync completed: $completedFiles files, $totalBytes bytes")
    }

    /**
     * Start simulating device behaviors
     */
    private fun startSimulation() {
        simulationJob?.cancel()
        simulationJob = scope.launch {
            // Emit initial ready event after 2 seconds
            delay(2000)
            isDeviceReady = true
            onReady()

            var iteration = 0
            while (isActive) {
                iteration++
                delay(3000) // 3 second intervals

                // Update simulated data internally without emitting events
                simulateSystemUpdate()

                // Storage changes every 10th iteration (30 seconds)
                if (iteration % 10 == 0) {
                    simulateStorageChange()
                }

                // Random error every ~2 minutes (2% chance per iteration)
                if (Random.nextFloat() < 0.02) {
                    onError("Simulated error: Random glitch", null, isFatal = false)
                }
            }
        }
    }

    /**
     * Stop all simulations
     */
    private fun stopSimulation() {
        simulationJob?.cancel()
        simulationJob = null
    }

    /**
     * Simulate system/battery updates (internal data only, no events)
     */
    private fun simulateSystemUpdate() {
        // Slowly drain battery (95% -> 20% over time)
        simulatedBattery = (simulatedBattery - Random.nextInt(0, 2)).coerceAtLeast(20)

        val isCharging = Random.nextFloat() < 0.1 // 10% chance of charging
        if (isCharging) {
            simulatedBattery = (simulatedBattery + 5).coerceAtMost(100)
        }
        // No event emission - data updated internally only
    }

    /**
     * Simulate storage changes (internal data only, no events)
     */
    private fun simulateStorageChange() {
        // Get current storage info from base class
        val current = storageInfo ?: return

        // Randomly add/remove files
        val photoChange = Random.nextInt(-2, 3)
        val videoChange = Random.nextInt(-1, 2)
        val audioChange = Random.nextInt(-1, 2)

        val updated = current.copy(
            photoCount = (current.photoCount + photoChange).coerceAtLeast(0),
            videoCount = (current.videoCount + videoChange).coerceAtLeast(0),
            audioCount = (current.audioCount + audioChange).coerceAtLeast(0),
            usedSize = (current.usedSize + Random.nextLong(-100_000_000, 100_000_000))
                .coerceAtLeast(0)
                .coerceAtMost(current.totalSize)
        )

        val freeSize = updated.totalSize - updated.usedSize
        updateStorageInfo(updated.copy(freeSize = freeSize))
        // No event emission - data updated internally only
    }

    override fun destroy() {
        stopSimulation()
        super.destroy()
    }

    // Import android.util.Log for logging
    private object Log {
        fun d(tag: String, message: String) = android.util.Log.d(tag, message)
        fun w(tag: String, message: String) = android.util.Log.w(tag, message)
    }
}
