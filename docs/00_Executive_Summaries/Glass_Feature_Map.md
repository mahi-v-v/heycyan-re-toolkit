# Cyan Glasses — Feature Implementation Map

**Date:** 2026-07-17
**Target app:** the main production companion app (out of scope for this repo). The `heycyan-re-toolkit/mobile-app` in this repo is an **isolated test sandbox** — some features (BLE/WiFi OTA, wake-word toggle, corrected OTA-progress decode) are prototyped there first and are **not yet ported** to the production app.
**Device:** Cyan / HeyCyan — AM01CY (**JieLi JL7018F** BLE/control/audio MCU; *"BlueX RF03" retracted 2026-07-27 — see `status_ledger_and_options.md`*) + Allwinner V821 (camera/Wi-Fi/Linux). No display.
**SDK:** the bundled oudmon `cyan_sdk.aar` (`com.oudmon.ble…` / `LargeDataHandler`) + the `glassesControl(byte[])` raw passthrough. The catalog below is a property of the **shared SDK + glasses firmware**, so it applies identically to both apps.

Baseline verified against the freshly-pulled `the production app` on 2026-07-17 (`…/glass/vendors/cyan/CyanAdapter.kt`, `CyanBleConnection.kt`, `CyanFileSyncManager.kt`, `agent/*`, `app/**`).

**Legend —** 🟢 ready now · 🟡 needs backend/SDK glue · 🟠 needs hardware access (root) · 🔴 needs hardware dump · ❓ needs on-device verification.

---

## Tier 1 — Already implemented in the app

### Working
| Feature | Command / SDK call | Where (`the production app`) |
|:---|:---|:---|
| BLE connect / scan / disconnect | `CyanBleConnection` + `LargeDataHandler.initEnable` | `…/cyan/CyanBleConnection.kt:126`, `CyanAdapter.kt:78` |
| Battery status + charging | `syncBattery` + `addBatteryCallBack` + notify `0x05` | `CyanAdapter.kt:116,160,421` |
| Device info (fw / wifi-fw version) | `syncDeviceInfo` | `CyanAdapter.kt:161` |
| **Take photo** | `glassesControl {2,1,1}` | `CyanAdapter.kt:210` |
| **Take AI image (vision)** | `{2,1,6,2,2}` → `{2,1,1}` → notify `0x02` → `getPictureThumbnails` → JPEG | `CyanAdapter.kt:224,433` |
| **Record video** start/stop | `{2,1,2}` / `{2,1,3}` | `CyanAdapter.kt:242,256` |
| **Record audio** start/stop | `{2,1,8}` / `{2,1,0x0c}` | `CyanAdapter.kt:260,274` |
| Storage info (photo/video/audio counts) | `{2,4}` query + notify parse | `CyanAdapter.kt:171,393` |
| **Media sync over Wi-Fi Direct** (gallery download) | transfer `{2,1,4}`, exit `{2,1,9}`, reset-P2P `{2,1,0x0F}` + `CyanFileSyncManager` | `CyanAdapter.kt:318`; `app/(app)/media.tsx:396` |
| Reboot / Factory reset | `{2,1,0x0E}` / `{2,1,0x0A}` | `CyanAdapter.kt:296,301`; `app/settings/device.tsx:168` |
| **Wake-word EVENT received** | notify `0x03` (`NOTIFY_MIC`) → `onVoiceWakeup()` | `CyanAdapter.kt:469` — *event only; no enable/disable toggle (see Tier 2)* |
| Device notify events | battery, ai-result, wifi-ip, wifi-error, mic-wake, ota-progress, pause, unbind, memory-low, translation-pause, volume | `CyanAdapter.kt:416-500` |
| **LiveKit voice + vision AI agent** (the core AI feature: STT/TTS/tools/RPC) | `agent/Agent.kt`, `AgentManager.kt`, `tools/ToolManager.kt` | — |
| Device settings / storage screens | — | `app/settings/device.tsx`, `app/settings/storage-info.tsx` |

### Partial / stubbed (declared but no-op or fake)
| Item | State | Where |
|:---|:---|:---|
| Capture settings (resolution/quality/length) | `getMediaConfig` returns zeros, `setMediaConfig` returns `false` — **but the official SDK exposes `setVideoInfo/setAudioInfo` = `angle` + `duration`** (not resolution), and the response bean already decodes `videoAngle`/`videoDuration`/`recordAudioDuration`. So angle+duration are implementable. See [external SDK-guide delta §6](../05_Hardware_Hacking_Findings/external_repos_and_firmware_distribution.md) | `CyanAdapter.kt:278,286` |
| `setDeviceName` / `clearMedia` / `setAiStatus` / `sendCommand` | no-ops ("not supported on Cyan") — **but the official iOS SDK has `deleteAllMedias` + `deleteMedia(name)`**, so media-delete IS supported (Android path likely the WiFi HTTP server, not a named aar call). `getDeviceMacAddress` also available. | `CyanAdapter.kt:291-344` |
| **Firmware update UI** | `const hasUpdate = false` hardcoded — **no real OTA** (neither BLE-DFU nor WiFi) | `app/settings/device.tsx:177` |
| OTA progress decode | notify `0x04` handled as a **single byte** (old mis-decode); the corrected weighted 3-byte decode exists only in the sandbox | `CyanAdapter.kt:474` |
| **Glass-camera streaming** | scaffolding present (`GlassManager.startStream`, `agent.startGlassVideoStream`, `GlassVideoCapturer`, LiveKit ingress) but built for the **K900 raw-NAL transport**; **non-functional on Cyan** (no live server — §11) | `AgentManager.kt:317-335`; `stream/GlassVideoCapturer.kt` |

---

## Tier 2 — Can implement now (already in the bundled SDK / reachable today)

Nothing new to ship in the `.aar`; these are wiring jobs against the SDK the app already links.

### A. Unused `LargeDataHandler` methods (in the bundled aar, never called by the app)
| Method → response | Feature | Impact / effort |
|:---|:---|:---|
| **`wearFunctionSupport` → `GlassesTouchSupportRsp`** | **Query the device capability bitmask** (supportLiveReview, supportAov, supportShortcut, support1300W, resolution, RTChat…). The official app does this on connect; the app never asks. | 🟢 **High** — makes every other feature self-gating per unit. Do first. |
| **`aiVoiceWake(write,isOpen)`** (`0x44`) | **Enable / disable / query the "Hey Cyan" wake word.** the app receives the wake *event* but can't toggle it. | 🟢 High (hands-free). Prototyped in sandbox. |
| `syncTime` | Set the glasses RTC → correct timestamps on captures | 🟢 easy |
| `setVolumeControl` / `getVolumeControl` | Read/set music/call/system volume (notify `0x12` already handled) | 🟢 easy–moderate |
| `aiVoicePlay(status)` (`0x48`) | Play TTS/answer audio on the **glasses speaker** (display-less output) | 🟡 ❓ verify opus path |
| `wearCheck(write,isOpen)` | Wear / on-face detection → auto-pause, capture-gating | 🟡 ❓ verify sensor |
| `speakSoundSwitch(phone)` (`0x52`) | Mute/unmute shutter + system beeps (discreet capture) | 🟢 trivial |
| `openBT` / `syncClassicBluetooth` | Enable/query classic Bluetooth | ❓ unverified audio path |
| `initPackageNotify` / `removeGptNotify` | On-device AI-chat streaming over BLE | low (the app uses its own LiveKit agent) |

### B. Reachable via `glassesControl(byte[])` (the raw method the app already calls)
| Command | Feature | Notes |
|:---|:---|:---|
| **`{2,1,11}`** | **AI voice / speech capture** — start the glasses mic for STT | the live-mic path (official app's speech recognizer) |
| **`{2,1,8}` + `dataType 8` responses** | **Resolution + AOV (time-lapse) config** — enumerate photo/video resolutions & FPS, AOV speeds & durations | fixes the dead capture-settings screen (Tier 1 stub) |
| `{1,10}` | Query current work-type / device state | app never polls state |
| work-types `7, 11, 18, 20, 33, 39` | **PROBED on-device 2026-07-17, RESOLVED 2026-07-19** against the vendor `QCOperatorDeviceMode` enum: **7 = SpeechRecognition** (fires mic notify `0x3`), **11 = SpeechRecognitionStop**, **18 = TranslateStart**, **20 = undefined** (past the last enum value `0x13` → no-ack), **33 & 39 = rejected**. Also `13 = FindDevice`. `{2,1,8}` = *start audio*, not resolutions. Detail: [`../05_Hardware_Hacking_Findings/cyan_ble_control_probes.md`](../05_Hardware_Hacking_Findings/cyan_ble_control_probes.md), full enum in [`external_repos_and_firmware_distribution.md §3.2`](../05_Hardware_Hacking_Findings/external_repos_and_firmware_distribution.md) | 🔬 neighbors include `0x0A` factory-reset / `0x0E` restart |

### C. Port from the sandbox (SDK methods exist; orchestration already prototyped)
| Feature | Status |
|:---|:---|
| **WiFi OTA** (`writeIpToSoc` + P2P HTTP server + corrected 3-byte progress) | `writeIpToSoc` is in the aar; full flow built in the sandbox — **port to the app**. 🟡 |
| **BLE-DFU** for the AM01CY MCU (`DfuHandle`) | sender is compiled into the aar; not wired in the app. 🟢 for stock/vendor images |
| Act on ignored notifies (memory-low → storage warning, unbind handling, pause) | currently only logged/relayed as `BT_STATUS`. 🟢 |

---

## Tier 3 — Can extend with our firmware / RE knowledge (beyond the stock app)

### A. Needs an SDK/aar bump (opcodes in the **official app's** newer `LargeDataHandler`, absent from your bundled aar)
| Method | Feature | Value |
|:---|:---|:---|
| **`aiShortCut(write,id,…)` → `AiShortCutResponse`** | **Configure the physical button / gesture shortcut action** (gated by `supportShortcut`) | 🟡 on-theme for "owning" the glasses — custom hardware-button behavior |
| `gyroConfig(…)` → `GyroConfigRsp` | Gyroscope / **EIS (electronic image stabilization)** config | 🟡 better video |
| `deviceGyroAuthor` / `onAuthorPacketAck` | Gyro EIS **license authorization** handshake (the "0x55" gate) | 🟡 explains the blocked gyro feature |

### B. Needs firmware modification / root (the RE deep-dive payoff)
| Feature | Path | Feasibility |
|:---|:---|:---|
| **Live video streaming / RTMP** | Cyan fw has **no live server** (§11) → root V821 (UART3 / software-only script-`.swu`) + tap VENC H.264 → RTSP/RTMP; relay via phone (LiveKit egress / `ffmpeg -c copy -f flv`). Makes the existing streaming scaffolding work on Cyan. | 🟠 needs root |
| **Custom wake PHRASE** (your own phrase) | AM01CY/**JL7018F** SWD dump → recover model/algorithm (OR a **2nd AM01CY `.bin`** — two-time-pad break) → edit → re-wrap → flash. *Caveat: wake model likely a JieLi built-in DSP feature not in the flashable firmware → phone-side recognizer may be the only route* | 🔴 needs dump / a 2nd version |
| **Full root / ownership** (UART3, or the **software-only NOR-free script `.swu`**; **no USB → no xfel/FEL**, CH341A/SOIC8 for anti-brick; no secure-boot) unlocks: custom **LED** status/notification patterns (`leds.sh`: solid/heartbeat/blink/morse), custom **boot chime**, custom button/gesture actions, offline operation, arbitrary custom-firmware behavior; owner-gated **adb-over-TCP** backdoor `.swu` | ownership roadmap | 🟠 needs UART/teardown |
| **Custom AM01CY firmware** | the `.bin` is a **two-time-pad** (keystream reused across *versions*) — NOT a keyless scrambler. Decrypting our AM01CY needs a **2nd AM01CY version** (not independently reversible from one file); then edit → re-wrap → recompute the byte-sum tag → flash | 🔴 blocked on a 2nd AM01CY `.bin` |
| **WiFi-OTA of patched V821 images** | deliver custom `.swu` via the P2P HTTP path (flash-from-non-mounted-context caveat — §9); or via BLE `0xFC OTAFileDownloadLink` pointing the device at our own URL | 🟠 |
| **Fetch stock V821 `.swu` from the vendor OSS** | read `wifiHardwareVersion` via `syncDeviceInfo` → `GET https://qcwxfactory.oss-cn-beijing.aliyuncs.com/bin/glasses/{wifiHardwareVersion}.swu` (public, no auth). Gets our unit's exact firmware. Sibling `WIFIA03_V2.0.swu` already confirmed downloadable. See [firmware distribution](../05_Hardware_Hacking_Findings/external_repos_and_firmware_distribution.md) | 🟢 |

---

## Recommended sequence for the app
1. **`wearFunctionSupport` capability query** (Tier 2A) — cheap; unlocks per-unit gating for everything else.
2. **`aiVoiceWake` toggle** (Tier 2A) + **port the sandbox's WiFi-OTA & corrected progress** (Tier 2C) — high-impact, ready, and the clearest payoff from the RE.
3. **Device-controls batch** — `syncTime`, volume, `speakSoundSwitch` (Tier 2A) + **resolution/AOV config** to revive the dead capture-settings screen (Tier 2B).
4. **`aiShortCut`** (Tier 3A) — custom button action; needs an aar update.
5. **Ownership track** (Tier 3B) — root → LED/boot-chime/streaming/custom-firmware. Gated on one hardware session; no app code removes those gates.

## References
- Reverse-engineering deep dive: [`../06_Reference_Materials/cyan_reverse_engineering.md`](../06_Reference_Materials/cyan_reverse_engineering.md) — esp. §9 (OTA), §10 (device/crypto/wake), §11 (streaming).
- Prior opportunities analysis: [`SDK_Feature_Opportunities.md`](SDK_Feature_Opportunities.md).
- Ownership/root & custom wake word: [`ownership_and_wakeword_roadmap.md`](ownership_and_wakeword_roadmap.md).
