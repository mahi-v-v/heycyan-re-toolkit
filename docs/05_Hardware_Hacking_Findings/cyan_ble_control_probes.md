# Cyan BLE Control — Live-Device Probe Findings

**Date:** 2026-07-17
**Device under test:** `CY01_03DC` (MAC `66:C6:66:D9:03:DC`) — HW `AM01CY_2.00.10_260411`, WiFi `WIFIAM01CY_0.13.14_2505282110`, **capability model = 9**.
**Method:** the sandbox diagnostic app (`heycyan-re-toolkit/mobile-app`, Debug tab) sending raw `glassesControl` payloads (`{2,1,N}`) over BLE and logging the parsed `GlassModelControlResponse` + all inbound device notifies. All results are empirical, from the physical unit.

> These are runtime observations to complement the static analysis in
> [`../06_Reference_Materials/cyan_reverse_engineering.md`](../06_Reference_Materials/cyan_reverse_engineering.md) and the
> [`../00_Executive_Summaries/Glass_Feature_Map.md`](../00_Executive_Summaries/Glass_Feature_Map.md).

---

## 1. Capability query (`wearFunctionSupport` → `GlassesTouchSupportRsp`)
Result on this unit:

| Field | Value |
|:---|:---|
| `glassesModel` | **9** |
| `translationSupport` | **true** |
| `wearCheckSupport` | **false** → this unit has **no wear/on-face sensor** |
| `volumeControl` | **true** |

**SDK limitation (confirmed at runtime):** the **bundled `cyan_sdk.aar`'s `GlassesTouchSupportRsp.acceptData` stops parsing at byte 10** — it decodes only the 4 fields above. The newer capability bits (`liveReview` / `aov` / `shortcut` / `1300W` / `resolution` / `rtChat` / `v881`) are present in the wire response but **not decodable without an aar update** (or a raw-bytes parser). Same SDK-version gap as `aiShortCut` / `gyroConfig`.

## 2. `glassesControl {2,1,N}` work-type map
Response convention: `dataType=1` with `errorCode=-1` ("accepted / processing", like photo/AI); `errorCode=1` = default/unhandled ⇒ **rejected / unsupported**.

| N (dec / hex) | Behaviour | Status | Evidence |
|:--|:--|:--|:--|
| 1 | Take photo | known | `CMD_TAKE_PHOTO` |
| 2 / 3 | Video start / stop | known | `CMD_START_VIDEO` / `CMD_STOP_VIDEO` |
| 4 | Wi-Fi transfer (media sync) mode | known | `CMD_TRANSFER_MODE` |
| 5 | OTA mode | known | `CMD_OTA_MODE` |
| 6 | AI photo capture | known | `CMD_AI_MODE` |
| **7** | **AI assistant / voice mode** | **confirmed accepted** | `err=-1`; **triggers `[NOTIFY] type=0x3`** (mic/voice-wake); user-observed AI behaviour |
| **8** | **Start audio recording** (NOT a resolution query) | **confirmed** | `err=-1`; `8 == CMD_START_AUDIO`. *Correction: `{2,1,8}` does not query resolutions — it records audio.* |
| 9 | Exit transfer | known | `CMD_EXIT_TRANSFER` |
| 10 (0x0A) | Factory reset | known | `CMD_FACTORY_RESET` |
| **11 (0x0B)** | **SpeechRecognitionStop** (vendor enum) — pairs with `7`=start ⚠️ *revised from "speech capture"* | accepted | `err=-1`; **triggers `[NOTIFY] type=0xb`**; vendor enum `QCOperatorDeviceMode 0x0B`=stop — re-probe to confirm start(7)/stop(11) pairing |
| **13 (0x0D)** | **FindDevice** (locate/beep) — not yet probed | untested | vendor enum `0x0D`; safe to probe (should beep) |
| 12 (0x0C) | Stop audio | known | `CMD_STOP_AUDIO` |
| 14 (0x0E) | Restart | known | `CMD_RESTART` |
| 15 (0x0F) | Reset P2P | known | `CMD_RESET_P2P` |
| **18 (0x12)** | **TranslateStart** ✅ *(was "role TBD")* | **confirmed** | matches vendor enum `QCOperatorDeviceMode 0x12`; the `0xf`/`0x10` state notifies are the translation family |
| **20 (0x14)** | **Undefined** ✅ — `0x14` is **past the last enum value** (`0x13`=TranslateStop), so the firmware has no handler → no `dataType` ack, no stop-tone | **resolved** | vendor enum `QCOperatorDeviceMode` ends at `0x13`; explains the earlier ambiguity |
| **33 (0x21)** | **Rejected / unsupported** | rejected | `err=1` |
| **39 (0x27)** | **Rejected / unsupported** | rejected | `err=1` |

Not yet probed / unknown: other values in the firmware's accepted set (the static parser also listed 33/39 as *nominally* valid work-type ints, but the device rejects them here).

> **Authoritative map (2026-07-19):** the vendor's iOS `QCDFU_Utils.h` `QCOperatorDeviceMode` enum defines `{2,1,N}` for `N = 0x00..0x13` (Photo/Video/…/TranslateStop). Full table + opcode map (incl. `0xFC OTAFileDownloadLink`, `0x44 VoiceWakeup`, DFU `0x01–0x06`) is in [`external_repos_and_firmware_distribution.md`](external_repos_and_firmware_distribution.md). Anything `≥ 0x14` is undefined → rejected/no-ack.

## 3. Inbound device notifies (`GlassesDeviceNotifyRsp`, selector = `loadData[6]`)
Frame shape observed: `[0xBC, 0x73, len, 0x00, ctrH, ctrL, TYPE, VALUE, …]` (header `0xBC73`, a counter, then the type byte and value). Types seen live:

| type (`loadData[6]`) | Meaning | Handled in adapter? | Example payload |
|:--|:--|:--|:--|
| `0x01` | device state/status (structure `[…,1,0,0,0,0,1,0,1]`) | **no** — surfaced only via the new `[NOTIFY]` catch-all | `188,115,8,0,17,199,1,0,0,0,0,1,0,1` |
| `0x03` | **mic / voice-wake active** (`NOTIFY_MIC`) — fires when AI/voice modes engage (work-type 7) | yes (`onVoiceWakeup`) | `188,115,2,0,192,128,3,1` |
| `0x0B` | device state during AI voice/speech capture (work-type 11) | **no** | `188,115,2,0,7,93,11,44` |
| `0x0F` | device state (fires around work-type 18) | **no** | `188,115,2,0,197,128,15,1` |
| `0x10` | translation-pause (`NOTIFY_TRANSLATION_PAUSE`) | yes | `188,115,2,0,205,176,16,1` |

The `[NOTIFY]` catch-all (added in the sandbox adapter) is what exposed the previously-invisible `0x01 / 0x0B / 0x0F` events — useful for discovering button/gesture/state events.

## 4. Next steps (⏸ paused 2026-07-17 — resume here)
1. **Clean `takeAiImage` retry.** The AI-image flow (`{2,1,6,2,2}` → `{2,1,1}` → wait for `NOTIFY_AI_RESULT` `type=0x2` → `getPictureThumbnails`) times out at 120 s — likely because concurrent probing kept the camera busy. Retry **in isolation** (Capture tab, nothing else running) and watch for `[NOTIFY] type=0x2`. If it still hangs, the AI-image flow itself needs debugging.
2. ✅ **RESOLVED (2026-07-19) via the vendor `QCOperatorDeviceMode` enum** — `18`=TranslateStart, `20`=undefined (out of enum), `7`=SpeechRecognition, `13`=FindDevice. See [`external_repos_and_firmware_distribution.md §3.2`](external_repos_and_firmware_distribution.md). Remaining: a quick re-probe to confirm the `7`=start / `11`=stop speech-recognition pairing, and probe `13`=FindDevice.
3. **Raw-bytes capability parser.** Bypass the stale bundled aar (which decodes only 4 capability fields) and parse the full `GlassesTouchSupportRsp` bitmask ourselves — to read `liveReview` / `aov` / `shortcut` / `1300W` / `resolution` without waiting on an SDK/aar bump.
4. *(bonus)* **Decode the `0xBC73` notify frame** — the counter + value fields for the unhandled `0x01` / `0x0B` / `0x0F` events.

_Status: sandbox diagnostic app is built and working; native compiles; docs are uncommitted working-tree changes._

## 5. Tooling note
Findings produced with the sandbox diagnostic build (Connect / Capture / Controls / OTA / Debug tabs). Every BLE command and inbound notify is traced (`[CMD]`, `[NOTIFY]`, `[CAP]`, `[PROBE]`, `[WAKE]`, `[VOL]`, `[WEAR]`, `[AUDIO]`, …) to a shared on-screen log and to Metro as `[GLASS] …`. The native command handlers live in `mobile-app/.../glass/vendors/cyan/CyanAdapter.kt` (`sendCommand` + `sendProbe`).
