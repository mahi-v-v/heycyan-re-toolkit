# External Repos & Firmware Distribution — Intel from `CyanBridge` / FerSaiyan

> ## ⚠️ §5 "Verdict" SUPERSEDED (2026-07-27) — §1–§4 remain accurate
> §1–§4 (distribution mechanism, opcode map, OSS probe, SDK deltas) are still correct. **§5's crypto
> verdict is retracted:** the `.bin` is **not** an unbreakable "crypto wall" — it is a **two-time-pad**
> (keystream reused across firmware *versions*), so a **2nd same-model `.bin` breaks it in software**
> (`C1 ⊕ C2`), with **no on-MCU key needed**. "More `.bin`s wouldn't help" is exactly backwards — a
> **2nd AM01CY version is *the* break** (currently unavailable; acquisition channels closed). The chip
> is a **JieLi JL7018F** (not "BlueX RF03"), and there is no "on-chip AES key" to recover. See
> `am01cy_ota_crypto_analysis.md` §1.10 + `../00_Executive_Summaries/status_ledger_and_options.md`.

**Date:** 2026-07-19
**Sources analysed (public GitHub):**
- **`haha2345/CyanBridge`** — mirror of **FerSaiyan**'s open-source alternative HeyCyan app (pkg `com.fersaiyan.cyanbridge`). Jetpack Compose accessibility app for the blind. 64 MB. The valuable parts are its *reverse-engineering artifacts*, not the app.
- **`FerSaiyan/Alternative-HeyCyan-App-and-SDK`** — FerSaiyan's primary repo (161 MB, updated 2026-07-17). Superset: same RE docs + iOS/Android/Python SDKs + official SDK PDFs.
- **`ebowwa/HeyCyanSmartGlassesSDK`** — the upstream cross-platform SDK drop (iOS `QCSDK.framework` + Android `glasses_sdk_20250723_v01.aar` + sample). This is the origin of our bundled aar.
- **`xcrotonai/CyanBridgeAI`** — a small (71 KB) multi-LLM vision/translate app. **Nothing new** (no firmware, no RE, no SDK internals). Ignore.

> Bottom line up front: **neither repo contains any firmware image** (`.bin`/`.swu`), encrypted or decrypted. What they *do* give is the vendor's **firmware-distribution mechanism**, the **complete BLE opcode map**, and a **second V821 `.swu`** on the same Tina platform. See the verdict in §5.

---

## 1. Firmware distribution mechanism (how the official app gets firmware)

Reverse-engineered from the official app `com.glasssutdio.wear` (250 MB, decompiled with JADX — the "第三方浏览器APK逆向分析" folder) — specifically `OTAViewModel.java`, `FirmwareOtaResp.java`, and the RE report.

There are **two independent firmware channels**, and they behave very differently:

| | **BLE `.bin`** (AM01CY MCU) | **WiFi `.swu`** (V821 Linux) |
|:--|:--|:--|
| URL source | **Server-provided** in the API response (`FirmwareOtaResp.downloadUrl`) | **Client-constructed**, fixed pattern |
| Pattern | opaque, whatever the server returns | `https://qcwxfactory.oss-cn-beijing.aliyuncs.com/bin/glasses/{hwVersionWifi}.swu` |
| Versions available | **latest only** (server decides) | **latest per hardware line** (one object per hw key) |
| Local filename | `{version}.bin` | `{version}.swu` |
| Auth to fetch | signed API (`@RequiresSignature`) → download | **none** — plain public OSS GET |

**OTA-check API** (`OTADepository.checkOtaFromServer(hwName, fmVersion)`):
- Intl: `POST https://www.qlifesnap.com/glasses/app-update/last-ota`
- CN:  `POST https://www.qlifesnap.com/glasses/app-update/last-ota/china`
- Response = `FirmwareOtaResp { hardwareVersion, version, downloadUrl, isEnforceUpdate, enforceUpdateFrom/To, openOrNot, uploadDate, os, updateDesc }`.
- `openOrNot`: **2** = release (auto-download), **3** = debug (auto-download only if app in debug). Otherwise no download.

**Backend / infra domains:**
| Domain | Role |
|:--|:--|
| `www.qlifesnap.com/glasses/` | main API (OTA check, keys, token, effect pics) |
| `qcwxfactory.oss-cn-beijing.aliyuncs.com` | **firmware store** (Aliyun OSS, Beijing) |
| `api1.qcwxkjvip.com` | fallback API (Huawei channel) |
| `china.qcwxwire.com` | CN-only API |

Vendor: **QC / qcwx** (青柠/qlifesnap). Same ODM behind AM01CY *and* the "A03" line.

---

## 2. Live probe of the OSS firmware store (2026-07-19)

Empirically tested `qcwxfactory.oss-cn-beijing.aliyuncs.com`:

- **Bucket listing is denied** — `GET /?list-type=2&prefix=bin/glasses/` → `403 AccessDenied` ("Anonymous user has no right to access this bucket"). Can't enumerate.
- **Individual objects are public-read** — a `GET` on a *known* object key succeeds. Missing/private keys return `403` (not 404) for anonymous, so 403 ≠ "definitely absent".
- **Confirmed downloadable:** `bin/glasses/WIFIA03_V2.0.swu` → **HTTP 200, 20 987 904 bytes**, `application/octet-stream`. SHA-256 `d1fdb824…c5a5d5`. Magic `30 37 30 37 30 32` = ASCII **"070702"** = CPIO/SWUpdate archive. **Valid, complete V821 firmware.**
- **The object key is the *hardware version*** (`WIFIA03_V2.0`), **not** the firmware-version string. Our AM01CY unit reports firmware `WIFIAM01CY_0.13.14_2505282110`, but the OSS *hardware key* is a different field (`UserConfig.hwVersionWifi`, e.g. `WIFIAM01CY_V2.0`-style). Every guessed AM01CY/A03 `.bin` and AM01CY `.swu` key → 403. **We cannot pull our unit's exact image by guessing.**
  - → To get **our** `.swu` directly: read `hwVersionWifi` off the device over BLE (the field the official app substitutes into the URL), then `GET bin/glasses/{hwVersionWifi}.swu`. No signature needed. *(actionable next step)*
- **`.bin` is never on OSS via a guessable key** — the BLE `.bin` URL is server-issued only.

### What `WIFIA03_V2.0.swu` contains (from its `sw-description`)
Same **Allwinner Tina Project** platform as our unit — dual boot target, full partition map:

```
software.stable.nor (NOR variant):
  boot0   -> /dev/mtdblock0
  kernel  -> /dev/mtdblock3
  riscv   -> /dev/mtdblock4      (XuanTie E907 image)
  rootfs  -> /dev/mtdblock5
  user    -> /dev/mmcblk0p13
  scripts: preinstall_nor.sh, postinstall_nor.sh
software.stable.sdnand (SD-NAND variant):
  kernel_sdnand, rootfs_sdnand, riscv_sdnand, user,
  uboot_sdnand (awuboot), boot0_sdnand (awboot0),
  preinstall_sdnand.sh, postinstall_sdnand.sh
bootenv: swu_version=...
```
Plus an OpenSBI RISC-V boot banner (Boot HART ID/ISA/PMP) → confirms the E907 RISC-V core. This **confirms our unit's partition layout without a hardware dump**, and is a second V821 image to diff against our `firmware.swu` (`hardware_research/firmware_and_os/`). Note it is the **A03** hardware line, not AM01CY — informative, not flashable to our unit.
*(File pulled to scratchpad; re-fetchable anytime from the URL above — not committed, ~20 MB.)*

---

## 3. Complete BLE opcode map (from iOS `QCDFU_Utils.h`)

The iOS `QCSDK.framework/Headers/QCDFU_Utils.h` is the cleanest authoritative opcode reference we've found. Two enums matter.

### 3.1 `ODM_DFU_Operation` — the BLE control/DFU opcodes
| Op | Meaning | Op | Meaning |
|:--|:--|:--|:--|
| `0x01` | StartDfuRequest | `0x44` | **VoiceWakeup (AI wake word)** |
| `0x02` | InitializeDfuParameters | `0x45` | VoiceHeartbeat |
| `0x03` | ReceiveFirmwareImage | `0x46` | WearingDetection (calibrate) |
| `0x04` | ValidateFirmware | `0x47` | DeviceConfig |
| `0x05` | ActivateAndReset | `0x48` | **AISpeak (TTS on glasses)** |
| `0x06` | CheckStatus | `0x51` | Volume |
| `0x40` | SetupDeviceStatus | `0x52` | BTStatus |
| `0x41` | SetDeviceMode | `0xFC` | **OTAFileDownloadLink** |
| `0x42` | GetDeviceBattery | `0xFD` | Thumbnail (AI photo) |
| `0x43` | GetDeviceVersion | `0x73` | DataUpdate (device→app report) |

- `0x01–0x06` = classic **nRF-style DFU** (start → send info → stream image → validate → activate). Header note: "适用于所有芯片在正常模式下的DFU升级" — the app-side DFU path for pushing an MCU `.bin`.
- **`0xFC OTAFileDownloadLink`** is the important one: the app **hands the device a firmware download URL over BLE**, and the **V821 fetches it itself over WiFi**. This is the WiFi-OTA trigger — and it means **we control the URL the device downloads from**. Point it at our own server → deliver a custom `.swu` without the vendor backend. (Pairs with the flash-from-non-mounted-context caveat in `cyan_reverse_engineering.md §9`.)

### 3.2 `QCOperatorDeviceMode` — the `{2,1,N}` work-type enum (resolves our probe ambiguity)
This is the authoritative source for the `glassesControl {2,1,N}` values we probed live:

| N | Mode | N | Mode |
|:--|:--|:--|:--|
| `0x00` | Unknown | `0x0B` (11) | SpeechRecognition**Stop** |
| `0x01` (1) | Photo | `0x0C` (12) | AudioStop |
| `0x02` (2) | Video | `0x0D` (13) | **FindDevice** |
| `0x03` (3) | VideoStop | `0x0E` (14) | Restart |
| `0x04` (4) | Transfer | `0x0F` (15) | NoPowerP2P |
| `0x05` (5) | OTA | `0x10` (16) | SpeakStart |
| `0x06` (6) | AIPhoto | `0x11` (17) | SpeakStop |
| `0x07` (7) | **SpeechRecognition** | `0x12` (18) | **TranslateStart** |
| `0x08` (8) | Audio | `0x13` (19) | TranslateStop |
| `0x09` (9) | TransferStop | ≥ `0x14` (20) | **undefined** (out of enum) |
| `0x0A` (10) | FactoryReset | | |

**Reconciles the paused probe questions in `cyan_ble_control_probes.md §4 #2`:**
- **`18` (0x12) = TranslateStart** — was "accepted, role TBD". ✅ resolved. (The state notifies `0xf`/`0x10` we saw are the translation-pause family — consistent.)
- **`20` (0x14) = past the last defined value (`0x13`)** → undefined → the device gives no standard `dataType` ack. ✅ explains the "uncertain, no stop-tone" behaviour.
- **`7` = SpeechRecognition** (start) — consistent with our "AI assistant fires mic notify `0x3`".
- **`13` = FindDevice** — never probed; should trigger a locate/beep. Worth a controlled probe.
- ⚠️ **`11` (0x0B) = SpeechRecognition*Stop*** per this enum — mildly at odds with our earlier "AI voice/speech *capture*" note. Flag for re-probe: 7 = start, 11 = stop of the same speech-recognition session is the coherent reading.

Other headers in the framework: `QCSDKCmdCreator.h`, `QCSDKManager.h`, `QCVolumeInfoModel.h`, `OdmBleConstants.h`, plus `ODM_RES_UIType_{StandBy,Boot,ShutDown}` resource types (display-panel dial resources — N/A for our display-less unit).

---

## 4. SDK aar comparison — the newer `glasses_sdk_20250723_v01.aar` is *not* an upgrade

Downloaded and unpacked `glasses_sdk_20250723_v01.aar` (com.oudmon.ble). Compared against our bundled `cyan_sdk.aar`:
- **`GlassesTouchSupportRsp` decodes the same 4 capability fields only** — `glassesModel, isTranslationSupport, isWearCheckSupport, isVolumeControl`. **No** `liveReview` / `aov` / `shortcut` / `1300W` / `resolution` getters. Our stale-aar limitation is **not** fixed by swapping in this aar.
- Has `DfuHandle`, `aiVoiceWake`, `SwitchOTARsp` (same as ours). The `support*` strings present are all **watch-band** features (`supportRingVideo/Music/Game/Ebook`, `supportHeart`, `supportLongSit`, `supportGesture`, …) — this is the shared Oudmon wearable SDK; glasses use a small subset.
- **`DfuHandle` + OTA classes contain no crypto** (no AES/cipher/decrypt/key/xor strings). Confirms the `.bin` is decrypted **on-MCU**, not in the app.

→ **Don't bother switching aars.** To read the newer capability bits, build the **raw-bytes `GlassesTouchSupportRsp` parser** (probe doc §4 #3) — still the right move.

Also present in FerSaiyan's repo (not yet mined, potential future value):
- `Android_SDK_Development_Guide_CN.pdf` (193 KB) + `iOS_SDK_Development_Guide.pdf` (142 KB) — **official SDK docs**.
- `examples/untested/python_sdk/` — a **Python BLE SDK** (script the glasses without Android).
- `com/jieli/jl_audio_decode` + `libjl_opus.so`/`libjl_speex.so` — confirms the **JieLi** Opus/Speex audio path (matches `reddit-intel-jl7018-pivot`). BLE audio is Opus; decode is JieLi.

---

## 5. Verdict against the goal ("a decrypted `.bin`")

| Question | Answer |
|:--|:--|
| Do these repos contain firmware? | **No** — zero `.bin`/`.swu` in either tree. |
| A *decrypted* AM01CY `.bin`? | **No**, and none is reachable via the vendor's public infra. |
| Can we pull *more* `.bin` versions? | Only the **latest, encrypted** one. The BLE `.bin` lives on the CDN `api2.qcwxkjvip.com/download/ota/AM01CY_V2.0/{ver}.bin` (per [`ownership_and_wakeword_roadmap.md`](../00_Executive_Summaries/ownership_and_wakeword_roadmap.md)) — **latest only, historical → 404**; not on the OSS bucket by guessable key. More encrypted `.bin`s wouldn't help anyway (still need the on-MCU key). |
| Is the crypto wall real / still there? | **Yes** — descrambled `.bin` entropy 7.885 b/B, no plaintext vectors; SDK/DFU carry no key. |
| Path to a decrypted `.bin`? | **Unchanged:** RF03 SWD/UART dump → recover on-chip AES key (or dump running decrypted image). See `am01cy-rf03-dump-path`. |

### What we *gained* (worth the dig)
1. **`0xFC OTAFileDownloadLink`** — we can make the V821 download firmware from a **URL we choose** → custom-`.swu` delivery path (ownership/root track).
2. **Work-type disambiguation for free** — `18=TranslateStart`, `20=undefined`, `13=FindDevice` (resolves paused probe TODO #2).
3. **Partition map confirmed** (`mtdblock0/3/4/5`, `mmcblk0p13`) without a dump.
4. **A second V821 `.swu`** (A03 line) to diff against our image.
5. **Negative result:** newer aar ≠ better → commit to the raw capability parser, not an aar swap.
6. **Firmware-store map** — if we ever learn our unit's `hwVersionWifi`, its `.swu` is one public GET away.

## 6. Official SDK guides (PDF) — what's NEW vs our docs

Mined the two **official** guides from FerSaiyan's repo — `Android_SDK_Development_Guide_CN.pdf` and `iOS_SDK_Development_Guide.pdf` (both **v1.0.0, 2025-07-23**, "Shenzhen QC.wireless Technology Co., Ltd", author "James"). The iOS one is the more complete API reference (`QCSDKCmdCreator.h` command surface). Most of it **confirms** our RE; the genuinely new/actionable deltas:

| # | New fact | Why it matters | Where |
|:--|:--|:--|:--|
| 1 | **`syncDeviceInfo` returns `wifiHardwareVersion`** (alongside wifiFirmwareVersion, hardwareVersion, firmwareVersion). Confirmed present in the Android aar's `DeviceInfoResponse`. *(Same field the other docs call `UserConfig.hwVersionWifi` / `{hwVersionWifi}` — the `syncDeviceInfo` getter name vs the UserConfig field name.)* | **This is the OSS `.swu` object key** (§2). Read it off the device → `GET bin/glasses/{wifiHardwareVersion}.swu` gets our unit's firmware. The missing piece. | Android guide p7; `DeviceInfoResponse` |
| 2 | **`isPeripheralFreeNow`** — check device-not-busy before a critical op. | **Likely explains our `takeAiImage` 120 s timeout** — concurrent probing left the device busy. Gate captures on this. | iOS `QCSDKCmdCreator.h` |
| 3 | **AI-image cmd = `{0x02,0x01,0x06, size, size, 0x02}`**, `size`=thumbnailSize **0..6**; thumbnail returns via notify `0x02` → `getPictureThumbnails`. | Refines our `{2,1,6,2,2}→{2,1,1}` model — it's a **single** 6-byte command with a 0..6 size param. | Android guide p11 |
| 4 | **Video/Audio config = `angle` + `duration`** (`setVideoInfo:angle duration:`, `getVideoInfo`; same for audio). Response bean already decodes `videoAngle`/`videoDuration`/`recordAudioDuration`. | The "dead" capture-settings stub is **partially real** — angle + duration are settable (not resolution). | iOS SDK; guide |
| 5 | **`deleteAllMedias` / `deleteMedia(name)`** exist in the iOS SDK. | Contradicts our adapter's "clearMedia not supported" no-op — **deletion is supported** (Android path likely via the WiFi HTTP server; not a named aar method). | iOS `QCSDKCmdCreator.h` |
| 6 | **`openWifiWithMode` → returns SSID + password**; **`getDeviceWifiIP`** → device IP (also our `p2pIp` field). | Device SoftAP credentials + IP API. (Android uses `writeIpToSoc` + P2P.) Relevant to media-sync/streaming transport. | iOS SDK |
| 7 | **Full BLE-DFU push recipe:** `switchToDFU` → `initDFUFirmwareType(type, binFileSize, checkSum, crc16)` → `sendFilePacketData(sn)` → `checkMyFirmware` → `finishDFU`; plus `getDFUBandTypeInfo` (single/dual **band**) and `switchToOneBandDFU`. Firmware types: Application / Bootloader / Softdevice. | The exact procedure to push a `.bin` to the AM01CY MCU over BLE — needs **CRC16 + checksum** of the image. (The `.bin` is still validated/decrypted on-MCU, so this pushes *vendor* images cleanly; custom images need the on-chip key.) | iOS SDK; aar has `Crc16`/`Checksum`/`DfuHandle` |
| 8 | **`QGAISpeakMode`**: Start(0x01)/Hold/Stop/ThinkingStart/ThinkingHold/ThinkingStop/**NoNet(0xf1)** — sub-states of AISpeak (`0x48`). | Drives the on-glasses TTS "thinking/speaking/no-net" audio states. | both guides |
| 9 | **BT pairing = `createBondBluetoothJieLi(device)`** — classic-BT scan, bond when BT addr == BLE addr. | Confirms a **JieLi** classic-BT audio component paired separately from BLE. | Android guide p12 |
| 10 | **WiFi-OTA progress is 3-part**: delegate `didUpdateWiFiUpgradeProgressWithDownload:upgrade1:upgrade2:` = notify `0x04` bytes `[7]=download, [8]=soc, [9]=nor`. | **Authoritative confirmation** of our weighted 3-byte OTA-progress decode. | Android guide p5; iOS `QCSDKManager.h` |
| 11 | `getDeviceMacAddress`; two service UUIDs `QCSDKSERVERUUID1/2`; protocol is **"Bluetooth 1.6.x" (<1.5.x uses the V1 command set)**; cloud error codes 1000–3002 imply an **appId/secret** auth layer. | Minor/context. Service UUID literals live in the compiled `QCSDK` binary; we already have a working UUID in the adapter. | iOS headers |

**Net:** the PDFs don't change the crypto/streaming verdicts, but they hand us (a) the **`wifiHardwareVersion` → firmware-fetch** link, (b) a **busy-check** that probably fixes the AI-image timeout, and (c) confirmation that **capture-config (angle/duration)** and **media-delete** are real SDK features, not dead stubs. These flow into the feature map.

## 7. Python SDKs — no new protocol (negative result)

Both `examples/untested/python/` and `examples/untested/python_sdk/` are **non-functional scaffolds**:
- `python_sdk/heycyan_sdk.py` — connection/lifecycle state machine only; uses the **stock Arduino BLE example UUIDs** (`19B10000-…-D104768A1214`), no command protocol. Only lifecycle detail of note: iOS persists the bound device under `QCLastConnectedIdentifier` (NSUserDefaults), 6 s reconnect.
- `python/heycyan_sdk/` — `api.py` is a facade that delegates to a compiled `._impl.GlassesImpl` **not present in the repo** ("would load compiled/obfuscated module"); `client.py` reads char UUIDs from **empty env vars** and uses placeholder command bytes (`bytes([0x01,0x00]) # Example`, `bytes([0xFF,0xEE,0xDD,0xCC]) # Example key`); the real `commands/device_commands.py` is referenced but **absent**.

→ The Python SDKs contain **nothing** our aar/QCDFU/live-probe RE doesn't already have, and less. Ignore for protocol; the only reuse value is the `bleak`-based connection scaffold if we ever want a pure-Python BLE harness.

## References
- Probe findings + work-type table: [`cyan_ble_control_probes.md`](cyan_ble_control_probes.md)
- Deep dive (OTA/crypto/streaming): [`../06_Reference_Materials/cyan_reverse_engineering.md`](../06_Reference_Materials/cyan_reverse_engineering.md)
- Feature map: [`../00_Executive_Summaries/Glass_Feature_Map.md`](../00_Executive_Summaries/Glass_Feature_Map.md)
