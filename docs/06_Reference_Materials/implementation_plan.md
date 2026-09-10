# HeyCyan Smart Glasses SDK Phase 1: Understanding & Integration Plan

This document covers the comprehensive analysis of the HeyCyan Smart Glasses SDK (Android) and provides the implementation plan for integrating it into a React Native application.

## User Review Required
> [!IMPORTANT]
> Please review the documented features, API endpoints, and React Native bridge design. Let me know if you would like me to prioritize any specific module (e.g., Media Downloading vs BLE controls) in the initial implementation.

## Open Questions
> [!WARNING]
> 1. Are there specific React Native BLE libraries you prefer (e.g., `react-native-ble-plx` vs `react-native-ble-manager`), or should we wrap the native HeyCyan SDK directly in a custom Native Module? (Wrapping the SDK directly is heavily recommended since it manages the proprietary BLE payload structure).
> 2. How should the downloaded media (Photos/Videos) be exposed to React Native (e.g., file paths, base64, or injected directly into the RN media gallery)?

---

## 1. SDK Architecture Documentation

The HeyCyan SDK employs a **dual-protocol architecture**:
1. **Bluetooth Low Energy (BLE)**: Used for discovery, pairing, state management, sending control commands, and receiving real-time status notifications (e.g., battery, current mode).
2. **WiFi Direct / Hotspot**: Used exclusively for high-bandwidth data transfer, such as downloading photos, videos, and audio files from the glasses.

### Core Modules
* **`com.oudmon.ble.base.bluetooth`**: Handles BLE connection, scanning, and bonding.
  * `BleOperateManager`: Singleton managing connections (`connectDirectly`, `unBindDevice`).
  * `DeviceManager`: Maintains the currently connected device state.
* **`com.oudmon.ble.base.communication`**: Handles the proprietary byte-level protocol.
  * `LargeDataHandler`: Primary interface for sending operational commands (take photo, sync time, get battery).
* **`com.oudmon.ble.base.scan`**: BLE Scanning abstractions.
  * `BleScannerHelper`: Wrapper around Android's BluetoothLeScanner.
* **`com.glasssutdio.wear.wifi`**: WiFi connection management for media transfer.

---

## 2. Feature Matrix

| Feature | Supported | Implementation Status | Notes |
| :--- | :---: | :--- | :--- |
| **BLE Scanning & Connect** | Yes | Fully Implemented | Handled by `BleScannerHelper` and `BleOperateManager`. |
| **Photo Capture** | Yes | Fully Implemented | Triggered via `LargeDataHandler.glassesControl`. |
| **Video Recording** | Yes | Fully Implemented | Start/stop via a single **toggle** opcode (`{2,1,2}`); app tracks state locally. |
| **Audio Recording** | Yes | Fully Implemented | Start/stop via a single **toggle** opcode (`{2,1,8}`); app tracks state locally. |
| **AI Image Gen / Chat** | Yes | Partially Implemented | Appears to trigger a mode on the glasses, returning thumbnails via BLE/WiFi. |
| **Media Transfer (WiFi)**| Yes | Fully Implemented | HTTP server runs on the glasses (`http://<device_ip>/files/...`). |
| **Device Info (Versions)**| Yes | Fully Implemented | Returns HW/FW and WiFi HW/FW versions. |
| **Battery Status** | Yes | Fully Implemented | Returns battery % and charging state. |
| **Volume Control** | Yes | Fully Implemented | Returns min/max/current volume for Music, Call, System. |
| **Media Counts** | Yes | Fully Implemented | Returns pending sync count for Images, Videos, Audio. |

---

## 3. API Reference & BLE Communication Flow

### Command Protocol Structure
All commands are sent as Byte Arrays through `LargeDataHandler.getInstance().glassesControl(byteArray)`.
The response is parsed and returned in a callback providing the `dataType`, `errorCode`, and `workTypeIng` (current mode).

### Device Modes (`workTypeIng`)
* `1, 6`: Photo Mode
* `2`: Video Mode
* `4`: Transfer Mode (WiFi hotspot active)
* `5`: OTA (Firmware Update) Mode
* `7`: AI Chat/Dialog Mode
* `8`: Audio Recording Mode

### Exposed APIs

#### 1. Device Connection
* **Scan**: `BleScannerHelper.getInstance().scanDevice(...)`
* **Connect**: `BleOperateManager.getInstance().connectDirectly(macAddress)`
* **Disconnect**: `BleOperateManager.getInstance().unBindDevice()`

#### 2. Media Controls
* **Take Photo**: `byteArrayOf(0x02, 0x01, 0x01)`
* **Start / Stop Video (toggle)**: `byteArrayOf(0x02, 0x01, 0x02)`
* **Start / Stop Audio (toggle)**: `byteArrayOf(0x02, 0x01, 0x08)`

> [!NOTE]
> **Confirmed shipping behavior (toggle model):** The shipping reference app does **not** send distinct stop opcodes. It re-sends the same **start** toggle (`{2,1,2}` for video, `{2,1,8}` for audio) to stop, and tracks recording state with a local boolean. This is the verified behavior.
>
> **UNVERIFIED — needs hardware/dynamic test:** Distinct stop opcodes `Stop Video = byteArrayOf(0x02, 0x01, 0x03)` and `Stop Audio = byteArrayOf(0x02, 0x01, 0x0c)` appear only in the official SDK sample (`MainActivity.kt`) and in our own untested wrapper. They have not been confirmed against hardware — do not rely on them until validated on-device.

#### 3. Device Status & Sync
* **Sync Time**: `LargeDataHandler.getInstance().syncTime(...)`
  * *Expected Behavior*: Syncs the glasses RTC with the smartphone.
* **Get Version Info**: `LargeDataHandler.getInstance().syncDeviceInfo(...)`
  * *Expected Response*: Object containing `hardwareVersion`, `firmwareVersion`, `wifiHardwareVersion`, `wifiFirmwareVersion`.
* **Get Battery**: `LargeDataHandler.getInstance().syncBattery()`
  * *Note*: Requires `addBatteryCallBack` to be registered first.
* **Get Media Count**: `byteArrayOf(0x02, 0x04)`
  * *Expected Response*: `imageCount`, `videoCount`, `recordCount`.

#### 4. Media Transfer Flow (WiFi)
1. Request glasses to enter transfer mode (or rely on them broadcasting P2P).
2. Connect smartphone to the glasses' WiFi network. The device IP is a **dynamic Wi-Fi P2P group-owner address (typically `192.168.49.1`)** — do not hardcode it; resolve it from the negotiated P2P group.
3. Fetch manifest: `GET http://<device_ip>/files/media.config`.
4. Parse manifest and download individual files: `GET http://<device_ip>/files/<filename>`.

---

## 4. React Native Integration Plan

Since the HeyCyan SDK handles complex byte-level protocols and state management, the best approach is to build **React Native Native Modules** wrapping the Android SDK, rather than rewriting the BLE logic in JS.

### Proposed Native Modules

#### `HeyCyanBleModule`
Exposes the core BLE controls to React Native.

**Methods**:
* `startScan()`: Emits `onDeviceFound` events to JS.
* `stopScan()`
* `connect(macAddress: String): Promise<Boolean>`
* `disconnect(): Promise<Boolean>`
* `syncTime(): Promise<Boolean>`
* `getDeviceInfo(): Promise<Object>`
* `getBatteryLevel(): Promise<Object>` (Returns `{ level: 85, isCharging: false }`)
* `takePhoto(): Promise<Boolean>`
* `startVideoRecording(): Promise<Boolean>`
* `stopVideoRecording(): Promise<Boolean>`
* `startAudioRecording(): Promise<Boolean>`
* `stopAudioRecording(): Promise<Boolean>`

#### `HeyCyanMediaModule`
Exposes the WiFi transfer capabilities.

**Methods**:
* `connectToGlassesWiFi(): Promise<Boolean>` (Triggers Android 10+ Network Request or P2P connect)
* `fetchMediaManifest(): Promise<Array<String>>`
* `downloadFile(filename: String): Promise<String>` (Returns local URI of downloaded file)

### Event Emitters
* `onDeviceStateChanged`: Emits when device connects, disconnects, or changes modes.
* `onBatteryChanged`: Emits when battery drops or charging starts.
* `onMediaTransferProgress`: Emits download progress for large files.

---

## 5. Verification & Testing Plan

### Testing Checklist (On Physical Device)
- [ ] **Discovery**: Verify the app can scan and discover the HeyCyan glasses.
- [ ] **Connection**: Verify the app can connect and bond with the glasses securely.
- [ ] **State Sync**: Verify Time, Battery, and Device Version endpoints return valid data.
- [ ] **Photo Trigger**: Trigger a photo from the app and verify the glasses react.
- [ ] **Video Trigger**: Start and stop a video recording; verify mode transitions via BLE callbacks.
- [ ] **Audio Trigger**: Start and stop audio recording.
- [ ] **WiFi Handshake**: Verify the Android device successfully negotiates a WiFi P2P/Hotspot connection.
- [ ] **Manifest Download**: Verify `media.config` can be retrieved over HTTP.
- [ ] **Media Download**: Download a JPG and an MP4, verifying file integrity on the phone.

## Known Limitations & Potential Improvements
* **Cleartext HTTP**: Media is transferred over unencrypted HTTP. Network security config must explicitly allow cleartext traffic to the glasses' IP.
* **Concurrent Modes**: Mode-exclusivity (e.g., cannot record video and audio simultaneously) is enforced **device-side**, not by the SDK. The glasses reject a conflicting command by returning a non-zero `errorCode` and/or reporting the busy mode in `workTypeIng`; the shipping reference app merely surfaces a toast in response. The RN wrapper should inspect `errorCode`/`workTypeIng` on each response and reject the corresponding promise rather than assuming the SDK will block the call.
* **WiFi P2P Instability**: Android's WiFi P2P API can be flaky across different manufacturer skins (Samsung vs Pixel). Comprehensive timeout and retry mechanisms are required in `HeyCyanMediaModule`.
