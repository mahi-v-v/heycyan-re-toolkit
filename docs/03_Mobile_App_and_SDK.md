# Mobile App & SDK Integration

The software ecosystem surrounding the glasses is built on Bluetooth Low Energy (BLE). The companion app connects to the glasses, configures them, and handles the heavy lifting for Voice AI processing. (Earlier drafts claimed "classic Bluetooth profiles" for audio — this is wrong: there is no A2DP/SCO path. See §3.)

## 1. HeyCyanSmartGlassesSDK
Located in ``HeyCyanSmartGlassesSDK``.
- **Core Purpose**: Acts as the communication bridge between a phone and the glasses over BLE.
- **Commands & Configuration**: It exposes APIs to read/write device states. For example:
  - iOS uses `QCSDKCmdCreator.setVoiceWakeup(enabled)`.
  - Android uses `LargeDataHandler.java` and `aiVoiceWake()` to handle AI voice wake commands.

## 2. Companion Android App (`com.glasssutdio.wear`)
A decompiled version of the official app exists in ``apk_extract``.

### 2.1. Wake Word Event Handling
> **Corrected 2026-07-11.** The previous version of this section described a "WakeUpTask BLE interrupt → AIWakeUpActivity → wake chime → SCO recording" flow. That flow was fabricated. The corrected flow, verified against the decompiled app, is below.

The wake word is detected on-device (the KWS host is **UNVERIFIED — needs hardware/dynamic test**; most plausibly the encrypted AM01CY BLE MCU, stated as inference, not confirmed). When the glasses fire the wake event, it surfaces to the app as a BLE notification:
- The wake event arrives as **`GlassesAiVoiceRsp`** on the BLE command/notify service — the `aiVoiceWake` channel (**BLE opcode `0x44`**, wake-word on/off). This is the actual entry point.
- ``WakeUpTask.java`` is **thread-queue plumbing**, not the BLE interrupt: `WakeUpTask.wakeUp()` enqueues work onto a worker thread. It does not receive the radio event.
- ``AIWakeUpActivity.java`` is a **non-exported settings/help screen**, not a runtime wake destination — the app does not transition to it when the wake word fires.
- `heyCyan_voice.MP3` plays **only from a manual `ivPlay` preview button** in settings (a user tapping "play" to hear the sample). It is **not** a wake chime played on detection.
- There is **no Bluetooth-classic SCO/A2DP audio path**. Mic and TTS audio flow over **BLE large-data** to Agora RTC (`RealtimeAudioPusher` → `CovRtcManager`) / Azure. See §3.

### 2.2. Machine Learning Assets (Intents)
The app bundles multiple TensorFlow Lite models inside ``resources/assets``.
- **Important**: These models are **not** wake-word models. They are NLP Intent classifiers that process the user's speech *after* the wake word triggers.
- Models included:
  - `intent_model.tflite`
  - Various language intent models (`es_intent_model_with_noise.tflite`, `de_intent_model_with_noise.tflite`, etc.)
  - `reminder_intent.tflite`

## 3. Communication Flow
> **Corrected 2026-07-11.** The previous version claimed continuous sensor telemetry over BLE and a Bluetooth-Classic (A2DP/SCO) audio path. Both were wrong. Corrected below.

1. **App to Glasses**: Configuration/command packets are sent via BLE on the command/notify service `de5bf728-d711-4e47-af26-65e3012a5dc7` (write characteristic `de5bf72a`, notify characteristic `de5bf729`) — e.g. LED controls, volume, and the wake-word toggle (`aiVoiceWake`, opcode `0x44`). Note this is the app's command service, **not** the Nordic NUS `6e40fff0` (a defined-but-unused legacy band service).
2. **Glasses to App**: Responses and events arrive as BLE notifications on `de5bf729`, including the wake event `GlassesAiVoiceRsp` (opcode `0x44`) and battery/status responses. Continuous **sensor telemetry over BLE is UNVERIFIED — needs hardware/dynamic test** (no such stream is confirmed in the decompiled app).
3. **Audio Routing**: There is **no Bluetooth-Classic audio path** (no A2DP, no Hands-Free/SCO). Mic capture and TTS playback audio flow over **BLE large-data** to Agora RTC (`RealtimeAudioPusher` → `CovRtcManager`) / Azure.

## 4. Camera / Video & Live-Streaming Path
> **Added 2026-07-17.** Full methodology + evidence in [`cyan_reverse_engineering.md`](06_Reference_Materials/cyan_reverse_engineering.md) §11.

The glasses' camera path is **record-to-storage → download**, not live. Key points:
- **No live streaming on the Cyan hardware.** The V821 firmware has **no live A/V server** (`ai_glass_video` H.264-encodes via VENC to *files*; the only video-serving daemon, `ai_glass_download`, is a plain HTTP/1.1 file server for recorded `.mp4`/`.jpg`). No `rtsp`/`rtmp`/`ffmpeg` in the firmware.
- **The official app's "Live View" is capability-gated.** `HomeFragment.clsLiveView` → `RealTimePreviewActivity` (which pulls `rtsp://<glassIP>:8554/ch0` via libVLC) is shown only when the glasses advertise `supportLiveReview` — decoded from the BLE `GlassesTouchSupportRsp` bitmask (`bArr[12] & 0x80`; set in `DeviceCmdInit`). The user's AM01CY reports this bit as **0**, so the card is hidden. That RTSP path is for other models (K900/V881).
- **The app's streaming code is dormant.** `Agent.startGlassVideoStream` → `GlassVideoCapturer` (raw-NAL-over-TCP → LiveKit `glass_camera`) has **zero callers**; it targets the K900 family (`K900StreamManager.STREAM_PORT = 8554`), not Cyan. `CyanAdapter.startVideo` (BLE `CMD_START_VIDEO 0x02`) records to storage.
- **Implication:** live glasses streaming / RTMP requires a **V821 firmware mod** (root + a streamer tapping VENC → RTSP/RTMP). See `SDK_Feature_Opportunities.md` A5.
