# Feature Opportunities — Genuinely-Missing, Hardware-Valid (Ordered by Impact)

**Date:** 2026-07-12 (v2 — corrected)
**App audited:** `the production app` (full app layer, not just `modules/sdk`).
**Hardware ground truth:** [`01_Hardware_Architecture.md`](../01_Hardware_Architecture.md).

> ## v2 corrections (why this replaces v1)
> **1. The glasses have NO display.** Real peripherals: microphone, speaker, camera+ISP, capacitive touch/button, **2 status LEDs** (solid/heartbeat/blink/morse via `/sys/class/leds`), and a **gyroscope** (serviced by the E907 AON core). There is no panel, framebuffer, MIPI-DSI, or waveguide. → All on-lens features (teleprompter, on-screen notes, screen-brightness/menu/language settings, notification-to-lens) are **removed as invalid**. Note the SDK's `capabilities["display"] = true` in `CyanAdapter.kt:74` is a **bug** and should be `false`.
> **2. The app already ships most "AI" features.** Confirmed present: realtime **voice + vision assistant** (LiveKit transport → backend agent), STT, TTS, camera-streaming **UI** (`app/stream.tsx`; ⚠️ the *glasses* live feed is **not** functional on Cyan — see the 2026-07-17 correction), media **gallery + BLE glasses sync**, **on-device semantic photo search** (MobileCLIP/ONNX), a **photo/video AI-edit suite** (recognize/OCR/faces/upscale/touch-up/AI-edit), connectors incl. WhatsApp, workflows/apps store, and basic device settings. → The AI-voice-pipeline, live-preview, translation, and image-AI items from v1 are **removed as already-implemented**.

> ## ⚠️ 2026-07-17 correction — "live camera streaming" is NOT shipped for the Cyan glasses
> A dedicated streaming/RTMP investigation ([`cyan_reverse_engineering.md`](../06_Reference_Materials/cyan_reverse_engineering.md) §11) overturns the v2 claim that live camera streaming is "already shipped." **Streaming the glasses camera live is not available on the Cyan hardware:** the V821 firmware has **no live-stream server** (ELF dyn-syms — `ai_glass_video` is UDP-only, no `listen`/`accept`; the only video TCP server, `ai_glass_download`, is an HTTP file server for *recorded* clips), the official app's Live-View (RTSP `:8554/ch0`) is **capability-gated OFF** for this unit (`supportLiveReview` bit = 0), and the app LiveKit path (`GlassVideoCapturer`) is **dormant scaffolding with zero callers**. What exists today is a camera-streaming **UI** plus **record→WiFi-download** of glasses clips — not a live glasses feed. Live glasses streaming/RTMP is therefore a genuinely-missing item needing a firmware mod (see **A5**).

This list is filtered by three tests: **(A)** not already in the app, **(B)** valid on the real hardware (mic/speaker/camera/touch/LED/gyro, no display), and **(C)** ideally leverages what we learned about the firmware/SDK. Ordered by impact.

**Feasibility legend:** 🟢 ready-now · 🟡 needs-backend/model · 🟠 needs-hardware-access · 🔴 needs-hardware-dump · ❓ needs-hardware-confirmation.

---

## Group A — What our reverse-engineering uniquely unlocks (highest impact)

These are the reason the RE work pays off. None exist in the app today.

### A1. Real firmware / OTA update system
Replace the fake "you're up to date" alert (`app/settings/device.tsx`, hardcoded `hasUpdate = false`) with a working two-path updater: **AM01CY BLE-DFU** for the control MCU and **`.swu` WiFi-P2P** for the camera SoC.
- **Impact: High.** No real update path exists today; this is table-stakes for a shipping product and the carrier for everything custom later.
- **Feasibility: 🟢 ready-now for stock/vendor images** (the `DfuHandle` BLE-DFU sender is **already compiled into your bundled `cyan_sdk.aar`**; the `.swu`/`0xFC writeIpToSoc` orchestration is already prototyped in the tinker `mobile-app`). 🔴 for *custom* AM01CY images (the `.bin` is a **two-time-pad** — decrypting our unit needs a **2nd AM01CY version**, not an "AES key from a chip dump"; currently unavailable). **Difficulty:** easy (BLE-DFU) / moderate (WiFi `.swu`).
- **Leverages RE:** container format, DFU opcodes 1–5, additive-sum checksum, and the no-secure-boot `.swu` path are all fully mapped.

### A2. Hands-free "Hey Cyan" → your AI agent
The glasses' hardware wake word fires today, but the SDK **receives the event and throws it away** (`NOTIFY_MIC` → `onVoiceWakeup()` posts an event that production `Glass.ts` never exposes to JS). Surface it and route it to launch the existing LiveKit voice mode — plus expose enable/disable/query (`aiVoiceWake` opcode `0x44`).
- **Impact: High.** Voice mode is tap-only today; wiring the hardware wake word makes the assistant genuinely hands-free — the whole point of glasses. The app has **zero** wake-word handling currently.
- **Feasibility: 🟢 ready-now.** `aiVoiceWake(write,isOpen,cb)` is in the aar; the tinker app already prototyped `setWakeWord/getWakeWord`. **Difficulty:** easy.
- **Leverages RE:** the wake engine is toggled by `0x44`; it runs on the AM01CY/**JL7018F** side — most likely a **JieLi built-in DSP feature** (inferred; not found in the flashable firmware of any decrypted variant).

### A3. Custom wake word (your own phrase instead of "Hey Cyan")
Two paths, both informed by the RE. **Phone-side:** silence stock KWS (`0x44 {2,0}`) and detect a custom phrase on the phone. **On-device (north star):** dump the AM01CY (**JieLi JL7018F**) → recover the model/algorithm → retrain → re-wrap → flash via A1. *(Caveat: the wake model is likely a JieLi built-in DSP feature **not in the flashable firmware**, so an on-device custom phrase may be impossible even with a dump — see `status_ledger_and_options.md`.)*
- **Impact: High** (the project's headline differentiator).
- **Feasibility:** phone-side is 🟡 but **the real blocker is a live glasses-mic uplink, which is unproven** (today audio only lands as `.opus` files after WiFi sync — no confirmed realtime mic stream). On-device is 🔴 needs-hardware-dump. **Difficulty:** very-hard either way.
- **Leverages RE:** this is entirely built on the wake-word/crypto/dump findings.

### A4. Full device ownership + custom-firmware features
The strategic endgame: root the V821 (unauthenticated **UART3** shell, or the **software-only NOR-free script `.swu`** — no USB on this unit, so xfel/FEL is unavailable; no secure boot) and/or ship the already-built backdoor `.swu` for adb-over-TCP. Once we own the firmware, we can add behaviors the vendor never shipped — e.g. **custom LED status/notification patterns** (the 2 LEDs already support morse/heartbeat/blink via `leds.sh`), a custom boot chime, offline operation, or custom touch/gesture actions.
- **Impact: High strategic** (turns the glasses into an open platform). **Feasibility: 🟠 needs-hardware-access** (teardown/UART to seed reliably; the OTA-based delivery is unreliable per the roadmap). **Difficulty:** hard.
- **Leverages RE:** the ownership roadmap (UART pads; the **software-only script-`.swu` → adb-over-TCP** path via `build_backdoor_swu.py --scripts-only`) is mapped. *(No USB → no xfel/FEL; never re-enable the `switch_root` block — it destroys NOR boot0. See `software_only_ownership_plan.md`.)*
- **⚠️ adb-over-TCP is a security surface — owner-gated, off by default.**

### A5. Live glasses-camera streaming / RTMP  (needs firmware — gated on A4)
Stream the glasses' camera **live** — to the app, or push **RTMP** to a CDN (YouTube/Twitch/nginx-rtmp). Genuinely missing: the Cyan firmware only records-then-serves-files (no live server; §11), so this requires an on-device streamer **added after root (A4)** — tap the VENC H.264 output → serve RTSP/TCP or push RTMP directly over Wi-Fi, then relay via the phone (LiveKit **egress** off the republished track, or `ffmpeg -c copy -f flv rtmp://…`; no re-encode needed since the feed is already H.264).
- **Impact: High** (live POV streaming is a headline glasses use-case; also the user's stated interest). **Feasibility: 🔴 needs-hardware/firmware** — no stock live path exists; the UI gate + missing firmware server are both proven in §11. **Difficulty:** hard.
- **Leverages RE:** §11 (no live server; VENC record-only pipeline; `supportLiveReview` capability gate) + the A4 ownership route (UART3/xfel). A vendor firmware that flips `supportLiveReview` for AM01CY would be a shortcut, but none is known to exist.

---

## Group B — Genuinely-missing device controls that fit the real hardware

Small, honest gaps. All valid for mic/speaker/camera/touch hardware; all reuse the bundled aar.

### B1. Make the capture-settings UI actually work on Cyan
`app/settings/glass-controls.tsx` already has UI for photo/video resolution, quality, and length — but on Cyan it calls into `CyanAdapter.setMediaConfig()`/`getMediaConfig()` which are **stubs that return `false`/zeros**. The settings screen is currently a no-op on these glasses.
- **Impact: Medium** (fixes a dead settings screen). **Feasibility: 🟢 ready-now.** **Difficulty:** moderate (multi-round-trip reads; resolution is a device-list index). **Leverages RE:** the real `0x02` sub-opcodes + `GlassModelControlResponse` decode are documented.

### B2. Volume control `(0x51)`
Read/set system/media/call volume from the app — there is no volume UI anywhere today, and the glasses have a speaker.
- **Impact: Medium.** **Feasibility: 🟢 ready-now** (`get/setVolumeControl` in the aar). **Difficulty:** easy. Also fixes a dropped volume-notify byte in the adapter.

### B3. Time sync / RTC `(0x40)`
Set the glasses' clock from the phone so captured photos/videos carry correct timestamps — nothing sets it today.
- **Impact: Medium** (correctness). **Feasibility: 🟢 ready-now.** **Difficulty:** trivial (1 line in `requestInitialData()`).

### B4. Wear / on-face detection `(0x46)`
If the glasses have a wear/proximity sensor, use it for auto-pause, capture-gating, and power saving. The whole JS event pipeline (`onWearStatus`) already exists in the SDK, unused.
- **Impact: Medium.** **Feasibility: ❓ needs-hardware-confirmation** — the `0x46` opcode is in the shared oudmon SDK, but our hardware evidence lists touch + gyro, **not** a confirmed proximity/wear sensor. Verify on-device before building. **Difficulty:** easy if the sensor exists.

### B5. Mute shutter/system beeps `(0x52)`
Toggle the glasses' capture/system sounds — useful for discreet capture.
- **Impact: Low-Medium.** **Feasibility: 🟢 ready-now** (`speakSoundSwitch` in the aar). **Difficulty:** trivial.

### B6. Route assistant / notification audio to the glasses speaker `(0x48 aiVoicePlay)`
Since there's no screen, the natural output for alerts/answers is the **speaker**: play TTS/notification audio on the glasses instead of (or in addition to) the phone.
- **Impact: Medium** (display-less-appropriate output channel). **Feasibility: ❓ needs-verification** — the stock app pushed TTS to the glasses via `0x48` over BLE large-data, but the current app plays agent audio via LiveKit (likely to the phone), and the glasses' opus decode path is a known gap (`onDialogueAudioChange` stubbed). Confirm the `0x48` playback path works before committing. **Difficulty:** moderate–hard.

---

## Group C — App-layer gaps (not firmware-related; lower priority given your framing)

Real, but they're ordinary app/backend work, not leverage of what we learned about the hardware. Listed for completeness only.

- **Live-conversation translation mode** — today's translate (`apps/translate.tsx`) is turn-based; a continuous bidirectional mode is missing. 🟡
- **Meeting / minutes feature** — the pieces exist (glasses audio → sync → `/media/transcribe` with summarize), but there's no dedicated flow. 🟡
- **Reminders / timers** — no app UI at all (only unused reference files). 🟡
- **Re-enable AI image Q&A + add collages** — "Ask about media" is implemented in `services/media-ai` but commented out in the UI; collages don't exist. 🟢/🟡

---

## Explicitly dropped (and why) — so we don't revisit them

| Dropped item | Reason |
|---|---|
| Teleprompter, on-lens notes, screen brightness/menu/language/time-format settings (`0x4D/0x4E/0x4B`) | **No display on this hardware.** |
| Notification/now-playing relay *to the lens* (`0x4F/0x60`) | No display; only an *audio* readout (B6) is possible. |
| Real-time AI voice assistant, STT, TTS | **Already shipped** (LiveKit + backend). |
| Live camera preview / streaming | ⚠️ **Corrected 2026-07-17:** only a **UI** ships (`app/stream.tsx`). Live **glasses**-camera streaming is **NOT possible on stock Cyan fw** (no live server; capability-gated off — §11). Re-added as a firmware-gated opportunity: **A5**. |
| Image AI (recognize/OCR/upscale/edit) | **Already shipped** (media-AI suite). |
| Agora ConvoAI pipeline | Not used — the app uses LiveKit. |
| iOS Cyan adapter "not started" | Stale — `ios/glass/vendors/cyan/CyanAdapter.swift` exists. |
| Head-angle/gyro EIS + gyro-license (`0x53/0x55`) | Video-stabilization config + a proprietary vendor-licensed feature; not a user feature, and `0x55` is blocked. |
| MP3/music device management (`0x70`), classic-BT/A2DP toggles (`0x49/0x2E`), factory PCM (`0xF2`), heartbeat (`0x45`) | Low value and/or unverified on this hardware; the only worthwhile slice (make `clearMedia` actually delete) folds into B1-class work. |

---

## Recommended sequence

1. **A1 + A2 together** — a real OTA updater plus hands-free wake-word → agent. Both are ready-now, both are high-impact, and both are the clearest payoff from the RE. (A2's wake event is literally being computed and discarded today.)
2. **B1–B3, B5** — one small "device-controls" PR: fix the dead capture-settings screen, add volume, add time-sync, add beep-mute. A day of glue on the bundled aar.
3. **Verify-then-build:** B4 (wear sensor?) and B6 (`0x48` audio path?) — each needs one on-device check before committing.
4. **A3 / A4** — the ownership endgame, gated on one hardware session (chip dump for the custom wake word; UART/xfel for root). No app code removes those gates.
