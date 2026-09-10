import ExpoModulesCore

/// Expo module for glass device control (mirrors GlassModule.kt)
/// Thin layer that forwards calls to GlassManager and emits events to JS.
public class GlassModule: Module {

    private var notificationObserver: NSObjectProtocol?

    public func definition() -> ModuleDefinition {
        Name("GlassModule")

        Events(
            "GLASS_DEVICE_FOUND",
            "GLASS_STATE_CHANGED",
            "GLASS_CONNECTED",
            "GLASS_DISCONNECTED",
            "GLASS_BATTERY",
            "GLASS_READY",
            "GLASS_ERROR",
            "GLASS_SYNC_STARTED",
            "GLASS_SYNC_PROGRESS",
            "GLASS_SYNC_COMPLETED",
            "GLASS_SYNC_FILE",
            "GLASS_WEAR_STATUS",
            "GLASS_BT_STATUS",
            "GLASS_VOICE_WAKEUP",
            "GLASS_STREAM_STATUS"
        )

        OnCreate {
            Task {
                await GlassManager.shared.initialize()
            }
            self.notificationObserver = NotificationCenter.default.addObserver(
                forName: .glassEvent,
                object: nil,
                queue: .main
            ) { [weak self] notification in
                if let msg = notification.userInfo?["msg"] as? GlassEventMsg {
                    self?.emitGlassEvent(msg)
                }
            }
        }

        OnDestroy {
            if let observer = self.notificationObserver {
                NotificationCenter.default.removeObserver(observer)
                self.notificationObserver = nil
            }
            GlassManager.shared.destroy()
        }

        AsyncFunction("connect") { (deviceId: String, vendor: String, name: String?, promise: Promise) in
            Task {
                do {
                    try await GlassManager.shared.connect(deviceId: deviceId, vendor: vendor, name: name)
                    promise.resolve(nil)
                } catch {
                    promise.reject("CONNECT_FAILED", error.localizedDescription)
                }
            }
        }

        AsyncFunction("disconnect") { (promise: Promise) in
            Task {
                do {
                    try await GlassManager.shared.disconnect()
                    promise.resolve(nil)
                } catch {
                    promise.reject("DISCONNECT_FAILED", error.localizedDescription)
                }
            }
        }

        AsyncFunction("sendCommand") { (command: String, params: [String: Any], promise: Promise) in
            Task {
                do {
                    try await GlassManager.shared.sendCommand(command, params: params)
                    promise.resolve(nil)
                } catch {
                    promise.reject("COMMAND_FAILED", error.localizedDescription)
                }
            }
        }

        AsyncFunction("getCapabilities") { () -> [String: Bool] in
            return GlassManager.shared.getCapabilities()
        }

        AsyncFunction("getCurrentState") { () -> String in
            return GlassManager.shared.getCurrentState().rawValue
        }

        AsyncFunction("isConnected") { () -> Bool in
            return GlassManager.shared.isConnected()
        }

        AsyncFunction("getStorageInfo") { (promise: Promise) in
            Task {
                do {
                    let info = try await GlassManager.shared.getStorageInfo()
                    promise.resolve([
                        "totalSize": info.totalSize,
                        "freeSize": info.freeSize,
                        "usedSize": info.usedSize,
                        "photoCount": info.photoCount,
                        "videoCount": info.videoCount,
                        "audioCount": info.audioCount
                    ])
                } catch {
                    promise.reject("GET_STORAGE_INFO_FAILED", error.localizedDescription)
                }
            }
        }

        AsyncFunction("getSystemInfo") { (promise: Promise) in
            Task {
                do {
                    let info = try await GlassManager.shared.getSystemInfo()
                    promise.resolve([
                        "firmwareVersion": info.firmwareVersion,
                        "appVersion": info.appVersion,
                        "deviceModel": info.deviceModel,
                        "batteryLevel": info.batteryLevel,
                        "isCharging": info.isCharging,
                        "batteryVoltage": Double(info.batteryVoltage),
                        "isReady": info.isReady
                    ])
                } catch {
                    promise.reject("GET_SYSTEM_INFO_FAILED", error.localizedDescription)
                }
            }
        }

        AsyncFunction("setDeviceName") { (name: String, promise: Promise) in
            Task {
                do {
                    try await GlassManager.shared.setDeviceName(name)
                    promise.resolve(nil)
                } catch {
                    promise.reject("SET_DEVICE_NAME_FAILED", error.localizedDescription)
                }
            }
        }

        AsyncFunction("clearMedia") { (promise: Promise) in
            Task {
                do {
                    try await GlassManager.shared.clearMedia()
                    promise.resolve(nil)
                } catch {
                    promise.reject("CLEAR_MEDIA_FAILED", error.localizedDescription)
                }
            }
        }

        AsyncFunction("reboot") { (promise: Promise) in
            Task {
                do {
                    try await GlassManager.shared.reboot()
                    promise.resolve(nil)
                } catch {
                    promise.reject("REBOOT_FAILED", error.localizedDescription)
                }
            }
        }

        AsyncFunction("factoryReset") { (promise: Promise) in
            Task {
                do {
                    try await GlassManager.shared.factoryReset()
                    promise.resolve(nil)
                } catch {
                    promise.reject("FACTORY_RESET_FAILED", error.localizedDescription)
                }
            }
        }

        AsyncFunction("setAiStatus") { (status: String, promise: Promise) in
            Task {
                do {
                    try await GlassManager.shared.setAiStatus(status)
                    promise.resolve(nil)
                } catch {
                    promise.reject("SET_AI_STATUS_FAILED", error.localizedDescription)
                }
            }
        }

        AsyncFunction("startScan") { (promise: Promise) in
            Task {
                do {
                    try await GlassManager.shared.startScan()
                    promise.resolve(nil)
                } catch {
                    promise.reject("START_SCAN_FAILED", error.localizedDescription)
                }
            }
        }

        AsyncFunction("stopScan") { (promise: Promise) in
            Task {
                do {
                    try await GlassManager.shared.stopScan()
                    promise.resolve(nil)
                } catch {
                    promise.reject("STOP_SCAN_FAILED", error.localizedDescription)
                }
            }
        }

        AsyncFunction("isBluetoothEnabled") { (promise: Promise) in
            Task {
                promise.resolve(await GlassManager.shared.isBluetoothEnabled())
            }
        }

        AsyncFunction("takePhoto") { (id: Int?, filename: String?, promise: Promise) in
            Task {
                do {
                    let result = try await GlassManager.shared.takePhoto(id: id, filename: filename)
                    var dict: [String: Any] = [
                        "success": result.success,
                        "errorCode": result.errorCode,
                        "photoCount": result.photoCount
                    ]
                    if let fn = result.filename { dict["filename"] = fn }
                    promise.resolve(dict)
                } catch {
                    promise.reject("TAKE_PHOTO_FAILED", error.localizedDescription)
                }
            }
        }

        AsyncFunction("takeAiImage") { (promise: Promise) in
            Task {
                do {
                    let path = try await GlassManager.shared.takeAiImage()
                    promise.resolve(path)
                } catch {
                    promise.reject("TAKE_AI_IMAGE_FAILED", error.localizedDescription)
                }
            }
        }

        AsyncFunction("startVideo") { (id: Int?, filename: String?, promise: Promise) in
            Task {
                do {
                    let result = try await GlassManager.shared.startVideo(id: id, filename: filename)
                    promise.resolve([
                        "success": result.success,
                        "errorCode": result.errorCode,
                        "videoCount": result.videoCount
                    ])
                } catch {
                    promise.reject("START_VIDEO_FAILED", error.localizedDescription)
                }
            }
        }

        AsyncFunction("stopVideo") { (promise: Promise) in
            Task {
                do {
                    try await GlassManager.shared.stopVideo()
                    promise.resolve(nil)
                } catch {
                    promise.reject("STOP_VIDEO_FAILED", error.localizedDescription)
                }
            }
        }

        AsyncFunction("startAudio") { (filename: String?, promise: Promise) in
            Task {
                do {
                    let result = try await GlassManager.shared.startAudio(filename: filename)
                    promise.resolve([
                        "success": result.success,
                        "errorCode": result.errorCode,
                        "audioCount": result.audioCount
                    ])
                } catch {
                    promise.reject("START_AUDIO_FAILED", error.localizedDescription)
                }
            }
        }

        AsyncFunction("stopAudio") { (promise: Promise) in
            Task {
                do {
                    try await GlassManager.shared.stopAudio()
                    promise.resolve(nil)
                } catch {
                    promise.reject("STOP_AUDIO_FAILED", error.localizedDescription)
                }
            }
        }

        AsyncFunction("getMediaDirectory") { (promise: Promise) in
            promise.resolve(GlassManager.shared.getMediaDirectory())
        }

        AsyncFunction("getMediaConfig") { (promise: Promise) in
            Task {
                do {
                    let config = try await GlassManager.shared.getMediaConfig()
                    promise.resolve([
                        "photo": ["width": config.photo.width, "height": config.photo.height],
                        "video": [
                            "width": config.video.width,
                            "height": config.video.height,
                            "quality": config.video.quality,
                            "duration": config.video.duration
                        ],
                        "audio": ["duration": config.audio.duration]
                    ])
                } catch {
                    promise.reject("GET_MEDIA_CONFIG_FAILED", error.localizedDescription)
                }
            }
        }

        AsyncFunction("setMediaConfig") { (config: [String: Any], promise: Promise) in
            Task {
                do {
                    let success = try await GlassManager.shared.setMediaConfig(config)
                    promise.resolve(success)
                } catch {
                    promise.reject("SET_MEDIA_CONFIG_FAILED", error.localizedDescription)
                }
            }
        }

        AsyncFunction("syncFiles") { (promise: Promise) in
            Task {
                do {
                    try await GlassManager.shared.syncFiles()
                    promise.resolve(nil)
                } catch {
                    promise.reject("SYNC_FILES_FAILED", error.localizedDescription)
                }
            }
        }

        AsyncFunction("cancelSync") { (promise: Promise) in
            Task {
                do {
                    try await GlassManager.shared.cancelSync()
                    promise.resolve(nil)
                } catch {
                    promise.reject("CANCEL_SYNC_FAILED", error.localizedDescription)
                }
            }
        }

        AsyncFunction("startStream") { (ssid: String, pwd: String, url: String, streamKey: String?, fps: Int, bitrate: Int, promise: Promise) in
            Task {
                do {
                    try GlassManager.shared.startStream(ssid: ssid, pwd: pwd, url: url, streamKey: streamKey, fps: fps, bitrate: bitrate)
                    promise.resolve(nil)
                } catch {
                    promise.reject("START_STREAM_FAILED", error.localizedDescription)
                }
            }
        }

        AsyncFunction("stopStream") { (promise: Promise) in
            GlassManager.shared.stopStream()
            promise.resolve(nil)
        }

        AsyncFunction("startLiveStream") { (promise: Promise) in
            promise.reject("NOT_SUPPORTED", "Use startStream for WHIP streaming on iOS")
        }

        AsyncFunction("startLiveStreamWithWifi") { (_: String, _: String, promise: Promise) in
            promise.reject("NOT_SUPPORTED", "Use startStream for WHIP streaming on iOS")
        }

        AsyncFunction("stopLiveStream") { (promise: Promise) in
            GlassManager.shared.stopStream()
            promise.resolve(nil)
        }

        AsyncFunction("isStreamingActive") { () -> Bool in
            return AgentManager.shared.isGlassVideoStreamingActive()
        }
    }

    // MARK: - Event emission (mirrors GlassModule.kt emitEvent)

    private func emitGlassEvent(_ event: GlassEventMsg) {
        let states = GlassConnectionState.allCases

        switch event.cmd {
        case GlassEventConstants.msgDeviceFound:
            // s2 is the advertisement JSON (incl. name); extract name + forward the JSON for JS classification.
            let advJson = event.s2 ?? "{}"
            var name = ""
            if let d = advJson.data(using: .utf8),
               let obj = try? JSONSerialization.jsonObject(with: d) as? [String: Any] {
                name = obj["name"] as? String ?? ""
            }
            var data: [String: Any] = [
                "deviceId": event.s1 ?? "", "deviceName": name, "advertisement": advJson,
            ]
            if event.n1 != 0 { data["rssi"] = event.n1 }
            sendEvent("GLASS_DEVICE_FOUND", data)

        case GlassEventConstants.msgStateChanged:
            var data: [String: Any] = [
                "oldState": (states[safe: event.n1] ?? .idle).rawValue,
                "newState": (states[safe: event.n2] ?? .idle).rawValue
            ]
            if let r = event.s1 { data["reason"] = r }
            sendEvent("GLASS_STATE_CHANGED", data)

        case GlassEventConstants.msgConnected:
            var connectedData: [String: Any] = ["deviceId": event.s1 ?? "", "deviceName": event.s2 ?? ""]
            if let vendor = GlassManager.shared.getCurrentVendorString() { connectedData["vendor"] = vendor }
            sendEvent("GLASS_CONNECTED", connectedData)

        case GlassEventConstants.msgDisconnected:
            sendEvent("GLASS_DISCONNECTED", [
                "deviceId": event.s1 ?? "",
                "reason": event.s2 ?? "",
                "wasExpected": event.n1 == 1
            ])

        case GlassEventConstants.msgBattery:
            var data: [String: Any] = ["level": event.n1, "isCharging": event.n2 == 1]
            if let v = event.s1 { data["voltage"] = v }
            sendEvent("GLASS_BATTERY", data)

        case GlassEventConstants.msgReady:
            sendEvent("GLASS_READY", [:])

        case GlassEventConstants.msgError:
            var data: [String: Any] = ["error": event.s1 ?? "", "isFatal": event.n1 == 1]
            if let c = event.s2 { data["code"] = c }
            sendEvent("GLASS_ERROR", data)

        case GlassEventConstants.msgSyncStarted:
            sendEvent("GLASS_SYNC_STARTED", [:])

        case GlassEventConstants.msgSyncProgress:
            let total = event.n2
            let current = event.n1
            sendEvent("GLASS_SYNC_PROGRESS", [
                "current": current,
                "total": total,
                "percent": total > 0 ? (current * 100 / total) : 0
            ])

        case GlassEventConstants.msgSyncCompleted:
            var data: [String: Any] = [
                "totalFiles": event.n1,
                "totalBytes": Int64(event.s1 ?? "0") ?? 0,
                "success": event.n2 == 1
            ]
            if let e = event.s2 { data["error"] = e }
            sendEvent("GLASS_SYNC_COMPLETED", data)

        case GlassEventConstants.msgSyncFile:
            var data: [String: Any] = [:]
            if let p = event.s1 { data["path"] = p }
            if let t = event.s2 { data["type"] = t }
            sendEvent("GLASS_SYNC_FILE", data)

        case GlassEventConstants.msgWearStatus:
            sendEvent("GLASS_WEAR_STATUS", ["worn": event.n1 == 1])

        case GlassEventConstants.msgBtStatus:
            sendEvent("GLASS_BT_STATUS", ["connected": event.n1 == 1])

        case GlassEventConstants.msgVoiceWakeup:
            sendEvent("GLASS_VOICE_WAKEUP", [:])

        case GlassEventConstants.msgStreamStatus:
            var data: [String: Any] = ["status": event.s1 ?? ""]
            if let msg = event.s2 { data["msg"] = msg }
            sendEvent("GLASS_STREAM_STATUS", data)
            if event.s1 == "stopped" || event.s1 == "error" {
                AgentManager.shared.stopGlassStream()
            }

        default:
            break
        }
    }
}

// MARK: - Safe array subscript

extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
