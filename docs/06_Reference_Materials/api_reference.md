# HeyCyan Smart Glasses API Reference

This document provides an exhaustive reference for the exposed public APIs in the HeyCyan Smart Glasses SDK, detailing exactly how they should be called, prerequisites, expected responses, failure cases, and how to verify them on real hardware.

---

## 1. Device Discovery & Connection

### 1.1 Start BLE Scan
**How it should be called:**
`BleScannerHelper.getInstance().scanDevice(context, serviceUuid, bleScanCallback)`

> Note: the middle argument is a single service `UUID` to filter on (not a list of filters).

**Prerequisites:**
- Location permissions (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`)
- Bluetooth permissions (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`)
- Bluetooth must be enabled.

**Expected Response:**
- `bleScanCallback.onLeScan(device, rssi, scanRecord, scanResult)` is invoked repeatedly as devices are found (the callback receives a 4th `ScanResult` parameter alongside `device`, `rssi`, and `scanRecord`).

**Possible Failure Cases:**
- Bluetooth disabled (returns no results or throws exception).
- Missing permissions.
- Location services disabled (Android requirement for BLE scanning).

**How to test on a real device:**
1. Put glasses in pairing mode.
2. Trigger scan from the app.
3. Verify the glasses MAC address and name appear in the callback with a valid RSSI.

### 1.2 Connect Device
**How it should be called:**
`BleOperateManager.getInstance().connectDirectly(macAddress)`

**Prerequisites:**
- A valid MAC address obtained from the scan.
- Glasses must not be actively connected to another master.

**Expected Response:**
- EventBus event `BluetoothEvent(connect = true)` is broadcasted.
- `BleOperateManager.getInstance().isConnected` becomes `true`.

**Possible Failure Cases:**
- Timeout if the device is out of range.
- Connection dropped if pairing is rejected by the glasses.

**How to test on a real device:**
1. Call connect with the glasses' MAC.
2. Observe the Bluetooth connection state change in the OS settings and the app.
3. Verify subsequent BLE commands (like fetching battery) succeed.

---

## 2. Media & Device Controls (via `LargeDataHandler`)

All controls are sent as raw byte arrays through `LargeDataHandler.getInstance().glassesControl(byteArray) { dataType, response -> }`.

### 2.1 Take Photo
**How it should be called:**
`LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x01, 0x01))`

**Prerequisites:**
- Device must be connected via BLE.
- Device cannot be in OTA mode or actively recording video.

**Expected Response:**
- Callback returns `dataType == 1`, `errorCode == 0`.
- `workTypeIng` returns `1` or `6` (Photo Mode).
- A subsequent notification may return a thumbnail over BLE.

**Possible Failure Cases:**
- `errorCode != 0` (e.g., storage full, device busy).

**How to test on a real device:**
1. Send the command.
2. Listen for the shutter sound or observe the LED indicator on the glasses.
3. Use the Media Count API to verify the image count increased by 1.

### 2.2 Start Video Recording
**How it should be called:**
`LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x01, 0x02))`

**Prerequisites:**
- Device connected via BLE.
- Sufficient battery and storage.
- Not currently recording audio or in Transfer Mode.

**Expected Response:**
- `dataType == 1`, `errorCode == 0`.
- `workTypeIng == 2` (Video Mode).

**Possible Failure Cases:**
- Returns failure if already recording or storage is full.

**How to test on a real device:**
1. Send start command.
2. Observe video recording LED indicator.
3. Send stop command, then verify video count increased.

### 2.3 Stop Video Recording
**How it should be called (confirmed behavior):**
`LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x01, 0x02))`

The shipping reference app has **no dedicated stop opcode for video**. It stops by **re-sending the same START toggle** (`byteArrayOf(0x02, 0x01, 0x02)`) and tracking a local boolean to know whether the next toggle means start or stop. Send the start command again to stop.

> Footnote: `byteArrayOf(0x02, 0x01, 0x03)` appears only in the official SDK sample and in our own untested wrapper as a distinct stop opcode. It is **UNVERIFIED — needs hardware/dynamic test** and is not used by the shipping app.

**Prerequisites:**
- Device must currently be in Video Mode (`workTypeIng == 2`).

**Expected Response:**
- `dataType == 1`, `errorCode == 0`.
- Mode transitions back to idle (e.g., `workTypeIng == 1`).

**Possible Failure Cases:**
- Command ignored if not currently recording.

**How to test on a real device:**
1. Ensure glasses are recording.
2. Send stop command.
3. Verify LED indicator turns off and file is saved.

### 2.4 Start/Stop Audio Recording
**How it should be called (confirmed behavior):**
- **Start:** `byteArrayOf(0x02, 0x01, 0x08)`
- **Stop:** re-send the same START toggle `byteArrayOf(0x02, 0x01, 0x08)`.

The shipping reference app has **no dedicated stop opcode for audio**. As with video, it stops by **re-sending the START toggle** (`byteArrayOf(0x02, 0x01, 0x08)`) and tracking a local boolean to decide whether the next toggle starts or stops recording.

> Footnote: `byteArrayOf(0x02, 0x01, 0x0c)` appears only in the official SDK sample and in our own untested wrapper as a distinct stop opcode. It is **UNVERIFIED — needs hardware/dynamic test** and is not used by the shipping app.

**Prerequisites:**
- Device connected.
- Not recording video.

**Expected Response:**
- `workTypeIng == 8` (Audio Recording Mode) upon start.
- `errorCode == 0`.

**Possible Failure Cases:**
- Rejected if video is actively recording.

**How to test on a real device:**
1. Start audio recording.
2. Speak near the glasses.
3. Stop recording, download the file via WiFi, and verify audio playback.

---

## 3. Status and Data Retrieval

### 3.1 Sync Time
**How it should be called:**
`LargeDataHandler.getInstance().syncTime { dataType, response -> }`

**Prerequisites:**
- Device connected via BLE.

**Expected Response:**
- Glasses internally update their RTC to match the phone's timestamp.
- Callback invoked with success status.

**Possible Failure Cases:**
- BLE disconnection during sync.

**How to test on a real device:**
1. Sync time.
2. Take a photo.
3. Download the photo and check the EXIF/file metadata timestamp matches the current time.

### 3.2 Get Battery Status
**How it should be called:**
1. Register listener: `LargeDataHandler.getInstance().addBatteryCallBack("init") { dataType, response -> }`
2. Trigger sync: `LargeDataHandler.getInstance().syncBattery()`

**Prerequisites:**
- Device connected via BLE.

**Expected Response:**
- Callback triggered with `response` containing battery level (0-100) and `charging` / `isCharging` (charging boolean).

**Possible Failure Cases:**
- Fails to return if listener isn't registered properly.

**How to test on a real device:**
1. Call syncBattery.
2. Verify UI updates with a reasonable battery percentage.
3. Plug glasses into charger, call again, verify charging status is true.

### 3.3 Get Device Info (Versions)
**How it should be called:**
`LargeDataHandler.getInstance().syncDeviceInfo { dataType, response -> }`

**Prerequisites:**
- Device connected via BLE.

**Expected Response:**
- Returns `wifiFirmwareVersion`, `wifiHardwareVersion`, `hardwareVersion`, `firmwareVersion`.

**Possible Failure Cases:**
- Timeout if device is unresponsive.

**How to test on a real device:**
1. Invoke command.
2. Verify version strings are populated and match the expected format (e.g., "v1.2.0").

### 3.4 Get Media Count
**How it should be called:**
`LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x04))`

**Prerequisites:**
- Device connected via BLE.

**Expected Response:**
- `dataType == 4`.
- Object contains `imageCount`, `videoCount`, `recordCount`.

**Possible Failure Cases:**
- None typical, assuming connection is stable.

**How to test on a real device:**
1. Fetch count.
2. Take a photo.
3. Fetch count again and verify `imageCount` incremented by 1.

---

## 4. WiFi Media Transfer

### 4.1 Initiate Data Download (P2P Handshake)
**How it should be called:**
- Establish a connection via `WifiP2pManagerSingleton`.
- Obtain the IP address (the dynamic Wi-Fi P2P group-owner address, typically `192.168.49.1`, or extracted via BLE characteristic).

**Prerequisites:**
- `NEARBY_WIFI_DEVICES` permission (Android 13+).
- Location permission.
- Glasses must be in Transfer Mode.

**Expected Response:**
- WiFi P2P connection successfully formed as Group Owner (GO) or client.
- Network path to the dynamic Wi-Fi P2P group-owner address (typically `192.168.49.1`) is routable.

**Possible Failure Cases:**
- P2P group negotiation fails (common on some Android skins).
- User denies WiFi connection prompt.

**How to test on a real device:**
1. Trigger P2P connection.
2. Ping the device IP to verify connectivity.

### 4.2 Download Manifest & Files
**How it should be called:**
- Fetch manifest: `GET http://<deviceIp>/files/media.config`
- Download file: `GET http://<deviceIp>/files/<filename>`

**Prerequisites:**
- Active WiFi P2P connection.
- `android:usesCleartextTraffic="true"` in AndroidManifest.xml (since it uses HTTP, not HTTPS).

**Expected Response:**
- `media.config` returns a newline-separated list of filenames (e.g., `.jpg`, `.mp4`).
- File endpoint returns raw bytes of the media.

**Possible Failure Cases:**
- `java.net.ConnectException`: WiFi disconnected or wrong IP.
- `Cleartext HTTP traffic not permitted`: App manifest missing network security config.

**How to test on a real device:**
1. Download manifest.
2. Parse string.
3. Download a `.jpg` from the manifest list.
4. Save to Android `DCIM` directory and verify it opens in the gallery.
