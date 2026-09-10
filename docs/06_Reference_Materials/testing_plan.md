# HeyCyan SDK Comprehensive Testing Plan

This document outlines the step-by-step testing plan to rigorously verify every exposed SDK feature on physical HeyCyan smart glasses. 

**Note on Environment:** Testing must be performed on a physical Android device (Android 11+) paired with a physical pair of HeyCyan glasses. Emulators do not support BLE or WiFi Direct testing.

---

## Phase 1: Connectivity & State Management

### 1.1 Device Discovery
1. **Action:** Put glasses in pairing mode. Initiate scan via `BleScannerHelper`.
2. **Verification:** Ensure the MAC address and device name appear in the UI list within 5 seconds.
3. **Edge Case:** Disable Bluetooth, verify app handles failure gracefully. 

### 1.2 Device Pairing & Binding
1. **Action:** Select glasses from scan list and call `BleOperateManager.getInstance().connectDirectly()`.
2. **Verification:** EventBus broadcast fires, connection state changes to connected, and glasses LED indicates success.
3. **Edge Case:** Disconnect abruptly (turn off glasses). Verify app detects disconnection and updates UI.

### 1.3 State Synchronization
1. **Action:** Call `syncDeviceInfo()`, `syncBattery()`, and `syncTime()`.
2. **Verification:** 
   - Hardware/Firmware strings populate correctly.
   - Battery level reflects real battery status (0-100%).
   - Time sync completes without error.

---

## Phase 2: Media Controls (BLE Payload Validation)

### 2.1 Photo Capture
1. **Action:** Call `glassesControl` with `0x02, 0x01, 0x01`.
2. **Verification:** Glasses emit shutter sound. `workTypeIng` in callback shows `1` or `6`. **UNVERIFIED — the shutter/LED hardware behavior, and whether the returned `workTypeIng` indicates success vs. a busy/conflict state (success-vs-conflict semantics), need a live device to confirm.**
3. **Validation:** Fetch `mediaCount` and verify `imageCount` increased by 1.

### 2.2 Video Recording
1. **Action:** Call `glassesControl` with `0x02, 0x01, 0x02` (toggle Video). The shipping reference app STOPS recording by re-sending this same toggle (`{2,1,2}`) and tracking a local boolean — there is no distinct stop opcode in the app path. (The `0x03` "Stop Video" opcode appears only in the official SDK sample / our own untested wrapper — **UNVERIFIED SDK-sample value, needs hardware/dynamic test**.)
2. **Verification:** Glasses LED pulses (recording mode) and `workTypeIng` shows `2`. **UNVERIFIED — LED behavior and the exact `workTypeIng` value need a live device.**
3. **Action:** Wait 5 seconds, re-send `0x02, 0x01, 0x02` to stop (toggle off), tracked via the local recording boolean.
4. **Validation:** Fetch `mediaCount` and verify `videoCount` increased by 1.

### 2.3 Audio Recording
1. **Action:** Call `glassesControl` with `0x02, 0x01, 0x08` (toggle Audio). As with video, the shipping reference app STOPS by re-sending this same toggle (`{2,1,8}`) and tracking a local boolean. (The `0x0c` "Stop Audio" opcode appears only in the official SDK sample / our own untested wrapper — **UNVERIFIED SDK-sample value, needs hardware/dynamic test**.)
2. **Verification:** `workTypeIng` shows `8`. **UNVERIFIED — exact `workTypeIng` value needs a live device.**
3. **Action:** Speak into the glasses mic.
4. **Action:** Re-send `0x02, 0x01, 0x08` to stop (toggle off), tracked via the local recording boolean.
5. **Validation:** Fetch `mediaCount` and verify `recordCount` increased by 1.

### 2.4 Concurrent Action Rejection
1. **Action:** Start video recording.
2. **Action:** While recording, attempt to start audio recording or take a photo.
3. **Verification:** The SDK/Glasses should reject the command (return an error code) and the video recording should remain uninterrupted. **UNVERIFIED — whether a conflicting command surfaces as a distinct `workTypeIng` conflict value vs. a silent no-op (success-vs-conflict semantics) must be observed on a live device.**

---

## Phase 3: High-Bandwidth Media Transfer (WiFi P2P)

### 3.1 P2P Handshake
1. **Action:** Trigger the Data Download flow.
2. **Verification:** App requests Android permissions. `WifiP2pManagerSingleton` successfully forms a P2P group.
3. **Validation:** P2P connection success callback fires. Ping the device's **dynamic Wi-Fi P2P group-owner address** (typically `192.168.49.1`) — resolve it from the P2P group-owner info at runtime rather than hardcoding, since the address is assigned dynamically. (`192.168.49.79` is only a sample placeholder.)

### 3.2 Manifest Retrieval
1. **Action:** App executes `GET http://<group-owner-ip>/files/media.config` (where `<group-owner-ip>` is the dynamic Wi-Fi P2P group-owner address, typically `192.168.49.1`).
2. **Verification:** HTTP 200 OK. Content is a plaintext list of filenames using the device's actual naming scheme (e.g., `video-<ts>.mp4` for video and `video-<ts>.pcm` for audio, where `<ts>` is a capture timestamp). Note: `IMG_001.jpg` / `VID_001.mp4` are NOT the real names.

### 3.3 Payload Integrity Download
1. **Action:** Iterate over filenames in the manifest and execute `GET` for each.
2. **Verification:** Files are successfully written to the Android device's storage (e.g., `DCIM` folder).
3. **Validation:** Open the downloaded `.mp4` (video) in the native Android gallery and play back the `.pcm` (audio). Verify the files are not corrupt, resolution is correct, and timestamps match the capture time.

---

## Phase 4: Edge Cases & Stability

### 4.1 Battery Drain Under Load
1. **Action:** Record 10 minutes of continuous video.
2. **Verification:** Periodically poll `syncBattery()`. Ensure battery percentage accurately reflects drain. Ensure thermal constraints do not force the glasses to shut down unexpectedly.

### 4.2 Large File Transfer Interruptions
1. **Action:** Begin transferring a large video file over WiFi.
2. **Action:** Mid-transfer, force close the app or turn off the glasses.
3. **Verification:** App catches the `IOException` or `SocketTimeoutException` without crashing.

### 4.3 Connection Recovery
1. **Action:** Walk out of BLE range (~10 meters) until disconnection occurs.
2. **Action:** Walk back into range.
3. **Verification:** App successfully detects the device again and can re-establish the connection without requiring a full restart of the Bluetooth adapter.
