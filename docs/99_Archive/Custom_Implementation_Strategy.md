# Custom Wake Word: Implementation Strategy

> ## ⚠️ SUPERSEDED (2026-07-27) — the chosen strategy rests on a disproven premise
> **Option D ("hex-patch the `riscv` binary", marked CHOSEN) is invalidated:** the wake-word model is
> **not** in the RISC-V/E907 image (no TFLM array; those regions are camera ISP LUTs) and not in any
> decrypted variant's flashable firmware → most likely a **JieLi JL7018F built-in DSP voice-wake**, which
> a firmware patch cannot change. Conversely **Option B (phone-side recognizer), marked REJECTED, is now
> the leading practical path** for a custom phrase (disable stock via BLE `0x44` + run a keyword model on
> the phone). Also: the "60-second OTA" assumption is unsafe — in-place `.swu` flash **halts** (NOR
> self-flash contention, see `../05_Hardware_Hacking_Findings/swu_flash_halt_root_cause.md`). Current
> state: `../00_Executive_Summaries/status_ledger_and_options.md`. Kept for history.

**Objective:** Implement a "Custom Wake Word" feature for the smart glasses, allowing users to wake the system with a phrase of their choosing.

---

## 1. The Core Limitation
Through reverse engineering the Android APK and the hardware firmware via Ghidra, we mapped out exactly how the glasses currently detect "Hey Cyan". 

We discovered that there is no third-party vendor providing a standard voice engine file. The wake word detection is compiled directly into the `riscv` binary.

Based on the companion app bundling `.tflite` intent files, the wake word system is hypothesized to be a custom TensorFlow Lite for Microcontrollers (TFLM) model. The model weights are baked as a raw array directly into the binary's initialized data segments (likely in the 897 KB `.data_unsaved` section).

Because it is compiled directly as raw weights without standard headers, tags, or known boundaries, it is difficult to locate and swap without breaking the firmware, *unless* we systematically isolate the data regions.

---

## 2. Implementation Options Evaluated

We evaluated four potential architectural approaches for adding a Custom Wake Word.

### Option A: The "Hardware" Approach (Source Rebuild)
Completely rewrite the RISC-V firmware to accommodate dynamic models.
* **Pros:** Maintains the ultra-low power consumption profile of the glasses.
* **Cons:** We do not have the C++ source code.
* **Status:** **REJECTED**

### Option B: The "Software Simulation" Approach (Phone-Based)
Since the phone app does all the intent processing anyway, we keep a continuous Bluetooth microphone connection open to the glasses and use the phone's CPU to listen for the wake word.
* **Pros:** Exceptionally easy to implement. Instant wake-word switching.
* **Cons:** Keeping the Bluetooth microphone open constantly drains the glasses' battery and, crucially, triggers permanent **privacy warnings** (e.g., the orange mic dot on iOS/Android) making users feel like they are being spied on.
* **Status:** **REJECTED (Due to Privacy Concerns)**

### Option C: The Hybrid Approach
Keep "Hey Cyan" as the hardware trigger to wake up the system. Once the system is awake, the mobile app takes over the audio stream.
* **User Flow:** The user must say "Hey Cyan" to wake the device, but the app can process whatever happens next.
* **Status:** **ALREADY IMPLEMENTED** (The LiveKit voice agent already does this).

### Option D: The Cloud-OTA Workaround (CHOSEN STRATEGY)
Instead of forcing the glasses to load models dynamically (which requires source code), we use a cloud server to automate the generation and flashing of a brand new firmware image. 
1. User types in a custom word on their app.
2. App sends word to the cloud.
3. Cloud trains a new 50KB model, hex-patches the `riscv` binary, updates the OTA checksums, and sends a new `firmware.swu` file back.
4. App pushes the firmware file to the glasses over Bluetooth for a 60-second OTA update.
* **Pros:** 100% privacy-safe (phone mic is off). True custom wake words. No C++ source code needed.
* **Cons:** Takes ~60 seconds to switch wake words.
* **Status:** **CHOSEN FOR IMPLEMENTATION**

---

## 3. Next Steps
See [`Cloud_OTA_WakeWord_Implementation_Plan.md`](./Cloud_OTA_WakeWord_Implementation_Plan.md) for the technical specifications of Option D.
