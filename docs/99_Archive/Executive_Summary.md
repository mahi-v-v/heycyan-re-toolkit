# Executive Summary: Custom Wake Word Implementation Strategy

**Date:** July 2026  
**Subject:** Implementation Strategy for Custom Wake Word App Feature

---

> ## RETRACTION & CORRECTION — 2026-07-11
>
> **The original Sections 2–4 of this summary (the "TFLM model baked into the RISC-V image →
> hex-patch a new KWS model into `firmware.swu` → 60-second OTA over Bluetooth" plan) were
> WRONG and have been rewritten below.** Reconciled against the primary artifacts:
>
> - **There is NO "Hey Cyan" TFLM/keyword-spotting model in the E907 `riscv` firmware image,
>   nor anywhere on the V821 Linux side.** `grep -c TFL3` = 0 across the riscv
>   rootfs/kernel/user partitions and in the non-stripped `ai_glass_audio`; there are no
>   NN/MFCC/tensor symbols. The dense byte regions once mistaken for model weights are camera
>   ISP gamma/DRC lookup tables. **There is no raw C-array model to hex-patch and no `<50KB`
>   file to overwrite — that artifact does not exist.**
> - **The KWS host is UNVERIFIED.** Most plausibly it runs on the encrypted **AM01CY BLE MCU**,
>   but this is an inference, NOT confirmed.
> - **The app already bundles a Microsoft `KeywordRecognitionModel`,** so the old "no
>   third-party vendor" claim is false — a phone-side recognizer already ships in-tree.
> - **"60-second OTA over Bluetooth" conflated two separate transports** (corrected in Section 3).
>
> Confirmed facts retained: **"Hey Cyan" branding**, **`aiVoiceWake` = BLE opcode `0x44`**
> (wake-word on/off), and **per-CPIO-item md5** as the only on-device integrity gate.
> For the full corrected analysis and the actual path forward, see
> **[`ownership_and_wakeword_roadmap.md`](../00_Executive_Summaries/ownership_and_wakeword_roadmap.md)**.

## 1. Project Overview
I conducted a deep-dive analysis of the smart glasses' compiled firmware and companion Android application. The goal was to determine the best path forward for implementing a "Custom Wake Word" feature as an update to my mobile app.

## 2. The "Custom Wake Word" Challenge
Currently, the glasses wake on the stock wake word, branded **"Hey Cyan"** (CONFIRMED). The
BLE opcode that toggles the wake engine on/off is **`aiVoiceWake = 0x44`** (CONFIRMED).

**Where the wake-word model actually lives is UNVERIFIED — needs hardware/dynamic test.** The
earlier claim that the engineers baked a TensorFlow-Lite-for-Microcontrollers (TFLM) "Hey
Cyan" model as a raw C-array into the low-power firmware is **REFUTED**: the E907 `riscv`
image and the entire V821 Linux side contain no TFLM runtime, no NN/MFCC/tensor symbols, and
no keyword-spotting model (`grep -c TFL3` = 0 everywhere, including the non-stripped
`ai_glass_audio`; the byte blobs previously read as "weights" are camera ISP gamma/DRC lookup
tables). The most plausible remaining host is the **encrypted AM01CY BLE MCU** — but that is
an inference, **NOT confirmed**, and reaching it would need an SWD RAM dump of the MCU or the
vendor key.

Note also that the premise "there is no 3rd-party vendor (like Sensory or Cyberon) supplying
a wake engine" is incorrect: the companion app **already bundles a Microsoft
`KeywordRecognitionModel`**, so a phone-side keyword recognizer already ships in the codebase.

## 3. Strategic Decision: How do we build a Custom Wake Word?

The original "Cloud-OTA hex-patch" plan is **withdrawn**. It depended on a firmware-resident
KWS model that does not exist (see Section 2), so there is nothing to retrain, hex-edit into
`firmware.swu`, or checksum-patch. The realistic options are:

### A. Phone-side recognizer (the only no-hardware path)
Disable the stock wake engine over BLE (`aiVoiceWake = 0x44`) and run a keyword model on the
phone. The app already ships a Microsoft `KeywordRecognitionModel`, so this path is partly
in-tree already. Trade-off: it relies on a phone-side audio stream rather than the glasses'
listener, with the usual OS always-on-microphone indicators on iOS/Android.

### B. On-device custom phrase (needs hardware access)
A true on-glasses custom wake word requires reaching whatever actually hosts the KWS — most
plausibly the encrypted AM01CY MCU (UNVERIFIED). That means hardware access (SWD RAM dump) or
the vendor signing key, which is out of scope for a pure app update.

### Correcting the transport: the old "60-second OTA over Bluetooth" was two things at once
"Firmware update over Bluetooth" conflated two distinct, separate pipelines:
*   **V821 SoC firmware — the `.swu` (WIFIAM01CY):** moves over **Wi-Fi-P2P HTTP**, *not*
    Bluetooth. The phone opens a `ServerSocket` on **:8080**, the glasses HTTP-GET the file in
    ~4 KB TCP chunks, and `swupdate` applies it on-device. On-device integrity is
    **per-CPIO-item md5 only** (no secure boot, no signing).
*   **AM01CY MCU firmware — the `.bin`:** moves over **BLE DFU** (`0xBC` frames) on the
    `de5bf728-…` serial-port service. This is the only firmware that actually travels over
    Bluetooth.

The "~60 seconds" figure is **UNVERIFIED — needs hardware/dynamic test.**

## 4. Final Recommendation
Do **not** pursue the withdrawn Cloud-OTA hex-patch plan; the firmware artifact it targeted
does not exist. For an app-only deliverable, the **phone-side recognizer (Option A)** is the
only viable path today, and it can build on the Microsoft `KeywordRecognitionModel` already
bundled in the app. A genuine on-device custom phrase remains blocked on hardware access to
the (UNVERIFIED, most-plausibly-AM01CY) KWS host.

For the full corrected findings — hardware architecture, both firmware transports, the
ownership/root path, and the wake-word decision tree — see
**[`ownership_and_wakeword_roadmap.md`](../00_Executive_Summaries/ownership_and_wakeword_roadmap.md)**.
