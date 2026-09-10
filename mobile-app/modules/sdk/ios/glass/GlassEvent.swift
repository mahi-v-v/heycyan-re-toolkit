import Foundation

// MARK: - Connection State (mirrors GlassConnectionState Kotlin enum)
enum GlassConnectionState: String, CaseIterable {
    case idle          = "IDLE"
    case scanning      = "SCANNING"
    case connecting    = "CONNECTING"
    case connected     = "CONNECTED"
    case disconnecting = "DISCONNECTING"
    case error         = "ERROR"
}

// MARK: - Event Constants (mirrors GlassEventConstants Kotlin object)
struct GlassEventConstants {
    static let msgDeviceFound    = 70001
    static let msgStateChanged   = 70002
    static let msgConnected      = 70003
    static let msgDisconnected   = 70004
    static let msgBattery        = 70005
    static let msgError          = 70006
    static let msgStorageInfo    = 70007
    static let msgSystemInfo     = 70008
    static let msgReady          = 70009
    static let msgSyncStarted    = 70010
    static let msgSyncProgress   = 70011
    static let msgSyncCompleted  = 70012
    static let msgWearStatus     = 70013
    static let msgVoiceWakeup    = 70014
    static let msgQrScanResult   = 10020
    static let msgBtStatus       = 70015
    static let msgStreamStatus   = 70016
    static let msgVoiceStop       = 70017
    static let msgSyncFile       = 70018  // a single file finished syncing (s1=path, s2=type)
}

// MARK: - Internal notification name (replaces EventBus)
extension Notification.Name {
    static let glassEvent = Notification.Name("GlassSdkGlassEvent")
}

// MARK: - Event Message (mirrors GlassEventMsg Kotlin data class)
struct GlassEventMsg {
    let cmd: Int
    var n1: Int = 0
    var n2: Int = 0
    var s1: String? = nil
    var s2: String? = nil

    init(cmd: Int, n1: Int = 0, n2: Int = 0, s1: String? = nil, s2: String? = nil) {
        self.cmd = cmd
        self.n1 = n1
        self.n2 = n2
        self.s1 = s1
        self.s2 = s2
    }
}

// MARK: - Data structs (mirrors Kotlin data classes)

public struct GlassStorageInfo {
    var totalSize: Int64
    var freeSize: Int64
    var usedSize: Int64
    var photoCount: Int
    var videoCount: Int
    var audioCount: Int

    static let empty = GlassStorageInfo(
        totalSize: 0, freeSize: 0, usedSize: 0,
        photoCount: 0, videoCount: 0, audioCount: 0
    )

    func copy(
        totalSize: Int64? = nil, freeSize: Int64? = nil, usedSize: Int64? = nil,
        photoCount: Int? = nil, videoCount: Int? = nil, audioCount: Int? = nil
    ) -> GlassStorageInfo {
        GlassStorageInfo(
            totalSize: totalSize ?? self.totalSize,
            freeSize: freeSize ?? self.freeSize,
            usedSize: usedSize ?? self.usedSize,
            photoCount: photoCount ?? self.photoCount,
            videoCount: videoCount ?? self.videoCount,
            audioCount: audioCount ?? self.audioCount
        )
    }
}

public struct GlassSystemInfo {
    var firmwareVersion: String
    var appVersion: String
    var deviceModel: String
    var batteryLevel: Int
    var isCharging: Bool
    var batteryVoltage: Float
    var isReady: Bool

    static let empty = GlassSystemInfo(
        firmwareVersion: "", appVersion: "", deviceModel: "",
        batteryLevel: 0, isCharging: false, batteryVoltage: 0, isReady: false
    )

    func copy(
        firmwareVersion: String? = nil, appVersion: String? = nil,
        deviceModel: String? = nil, batteryLevel: Int? = nil,
        isCharging: Bool? = nil, batteryVoltage: Float? = nil, isReady: Bool? = nil
    ) -> GlassSystemInfo {
        GlassSystemInfo(
            firmwareVersion: firmwareVersion ?? self.firmwareVersion,
            appVersion: appVersion ?? self.appVersion,
            deviceModel: deviceModel ?? self.deviceModel,
            batteryLevel: batteryLevel ?? self.batteryLevel,
            isCharging: isCharging ?? self.isCharging,
            batteryVoltage: batteryVoltage ?? self.batteryVoltage,
            isReady: isReady ?? self.isReady
        )
    }
}

public struct PhotoResult {
    var success: Bool
    var errorCode: Int
    var photoCount: Int
    var filename: String?
}

public struct VideoResult {
    var success: Bool
    var errorCode: Int
    var videoCount: Int
}

public struct AudioResult {
    var success: Bool
    var errorCode: Int
    var audioCount: Int
}

public struct PhotoConfigData  { public var width: Int; public var height: Int }
public struct VideoConfigData  { public var width: Int; public var height: Int; public var quality: Int; public var duration: Int }
public struct AudioConfigData  { public var duration: Int }

public struct MediaConfigData {
    var photo: PhotoConfigData
    var video: VideoConfigData
    var audio: AudioConfigData
}
