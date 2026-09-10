# Current Wake Word Architecture ("Hey Cyan")

> ## ⚠️ RETRACTED (2026-07-27) — RISC-V KWS premise disproven, kept for history
> This doc's claim that the **E907/XuanTie RISC-V coprocessor runs the KWS** (model compiled into
> `.data_unsaved`) is **false** — the `riscv` image has no `TFL3`/NN/MFCC; those regions are camera ISP
> LUTs, and address `0x808e5958` was a mislabel (see `../02_Firmware_and_OS.md`). The wake word is **not
> in the V821**, nor in any decrypted variant's flashable firmware → most likely a **JieLi JL7018F
> built-in DSP voice-wake** the firmware only toggles (BLE `0x44`). App-flow claims here (e.g.
> `AIWakeUpActivity`, `heyCyan_voice.MP3` on detection) are also superseded by
> `../03_Mobile_App_and_SDK.md` §2.1. Current state: `../00_Executive_Summaries/status_ledger_and_options.md`.

The default "Hey Cyan" wake word feature is distributed across hardware, firmware, and mobile app components to optimize for ultra-low power consumption.

## 1. Always-on Listening (Hardware/Coprocessor)
The **XuanTie-900 (T-Head E90x)** RISC-V coprocessor runs the ``riscv`` firmware. This firmware maintains an active audio buffer (`wakeup buffer`) and runs a low-power Keyword Spotting (KWS) algorithm.

Through static analysis and web research on the Allwinner V821 ecosystem, we confirmed:
*   The hardware does not use a dedicated "Voice DSP" chip. Instead, it relies on the XuanTie E90x's mathematical extensions to process audio and run lightweight neural networks locally.
*   There is no third-party vendor (like Sensory or Cyberon). The original engineers built a custom keyword spotting system. It is hypothesized to use TensorFlow Lite for Microcontrollers (TFLM) since the official Android app packages `.tflite` intent models, but no string identifiers for TFLM are present in the `riscv` binary itself.
*   This model is compiled directly into the data segments of the statically compiled `riscv` executable binary (specifically within the massive `.data_unsaved` segment of 897 KB) as an array of mathematical weights, without standard model tags or headers.

The logic for handling the audio buffer was found at memory address `0x808e5958` (file offset `0x86941`), where the `wakeup buffer %s, state %u` log string is processed. Hardware IO wakeups (like physical touch buttons) are handled separately by a function near `0x8088a498` which sets hardware registers directly.

## 2. Trigger Event & IPC
When the TFLM acoustic footprint of "Hey Cyan" is detected, the RISC-V coprocessor fires an interrupt/event to the main Linux SoC. The RISC-V firmware utilizes an Inter-Processor Communication (IPC) system called `rpbuf` (Remote Processor Buffer) to pass the audio chunks and wakeup notification to the Linux core. 

Specifically, the function at `FUN_ram_80882ddc` sends messages over `rpbuf` using commands like `rpbuf_notify_by_link` and `rpbuf_compose_service_message` when the wake word is triggered. The main CPU's audio daemon, ``ai_glass_audio``, then receives and processes this stream.

## 3. BLE Transmission to App
A BLE notification is sent from the glasses to the connected smartphone. The SDK translates this packet and triggers the wakeup listeners.

## 4. App-Side Processing
In the Android companion app:
- ``WakeupThread.java`` and ``WakeUpTask.java`` receive the BLE signal.
- The app transitions to ``AIWakeUpActivity.java``.
- The app acknowledges the wake-up by playing the sound file `heyCyan_voice.MP3` as an auditory cue over the Bluetooth stream.
- Further spoken commands are recorded by the phone and passed through the `.tflite` intent models (e.g., `intent_model.tflite`) on the phone to determine what action to execute.
