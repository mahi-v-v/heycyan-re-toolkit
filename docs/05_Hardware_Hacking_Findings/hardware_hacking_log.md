# Hardware Hacking Log: Wake Word Neural Network Reverse Engineering

This document logs the technical parameters and findings of the embedded "Hey Cyan" wake word neural network, discovered through headless Ghidra analysis of the `riscv` coprocessor binary.

> ## ⚠️ 2026-07-11 — This document's core premise is REFUTED
> Later adversarial analysis shows the "Hey Cyan" KWS is **NOT** in the `riscv`
> coprocessor image. `riscv`/`riscv_sdnand` contain **zero `TFL3`** (TFLite-Micro magic),
> **no NN/TFL3/KWS model or MFCC pipeline** (audio-clock strings such as `i2s0` / `audio-adc` /
> `audio-dac` exist, but no keyword-spotting inference), and **no NN/MFCC symbols**;
> `FUN_ram_80882ddc` and the 57,145-byte
> `.bss_unsaved` block are AON/ISP housekeeping (not a tensor arena), and the
> density-scanned "candidate" regions are **camera ISP gamma/DRC lookup tables**. The KWS
> almost certainly runs on the separate **encrypted AM01CY BLE MCU**. Treat the
> "wake-word NN in riscv" framing below as a disproven hypothesis.
> See **`docs/00_Executive_Summaries/ownership_and_wakeword_roadmap.md`**.

---

## 1. Phase 1: Entry Point Identification
- **Target Function**: `FUN_ram_80882ddc` (handles the audio buffer via `rpbuf` notifications).
- **Behavior**: This function acts as the Inter-Processor Communication (IPC) trigger for the main Linux SoC. It delegates the actual audio chunking and inference to four child functions: 
  - `FUN_ram_80898f18`
  - `FUN_ram_80898caa`
  - `FUN_ram_80898d0c`
  - `FUN_ram_80898ec2`
- **Finding**: The neural network inference loop (TensorFlow Lite for Microcontrollers) is nested inside this call tree.

---

## 2. Phase 2: Input Shape Tracing
- **Target Functions**: `FUN_ram_80898f18`, `FUN_ram_80898caa`, `FUN_ram_80898d0c`, `FUN_ram_80898ec2`
- **Analysis**: We traced the audio buffer pointers through these child functions.
- **Finding**: The pointers do not go directly into a math loop. Instead, they hit RTOS (Real-Time Operating System) wrapper functions (checking mutexes/queues at offset `param_1 + 0x50` and calling things like `thunk_FUN_ram_808bfe54`).
- **Conclusion**: The audio chunks are pushed onto a thread-safe RTOS queue. The actual neural network is running in a completely separate, asynchronous worker thread pulling from this queue. 
- **Verdict**: Without a live dynamic emulator (e.g. QEMU) to freeze CPU execution and inspect the RTOS task table in memory, tracing the input shape manually through the RTOS queue via static assembly analysis is virtually impossible.

---

## 3. Phase 3: Tensor Arena (Memory Limit)
- **Analysis**: Scanned the `.bss` and `.bss_unsaved` memory blocks for large uninitialized allocations (where TFLM allocates its `tensor_arena` buffer).
- **Finding**: The maximum contiguous uninitialized memory block is exactly **57,145 bytes** (`.bss_unsaved`).
- **Conclusion**: Any custom TFLM model compiled for this firmware must have a memory footprint strictly under ~55 KB to avoid immediate memory overflow crashes.

---

## 4. Associated Files in this Directory
The following files have been saved to this findings directory for future reference:
- `GhidraTraceNN.java`: Ghidra Java script used to trace entry point and memory blocks.
- `GhidraPhase2.java`: Ghidra Java script used to decompile and trace child functions of the audio handler.
- `decompiled_nn.txt`: Decompiled outputs and memory analysis from Phase 1/3.
- `decompiled_phase2.txt`: Decompiled function outputs from Phase 2.

---

## 5. Script Setup & Execution Guide (For Teammates)

When other developers try to run these headless Ghidra scripts locally, they must configure their environment precisely to avoid common Java compilation and lock issues.

### System Requirements
* **Ghidra**: Version 12.1.2 PUBLIC (or similar 12.x version).
* **Java SDK**: JDK 21 (Temurin 21.0.11+10 64-Bit is verified and recommended). 

### How to Run the Scripts Headlessly
Run the following PowerShell command from your local Ghidra installation's `support/` directory:

```powershell
.\analyzeHeadless.bat "<absolute_path_to_project>\docs\05_Hardware_Hacking_Findings" "ghidra_proj" -process riscv -scriptPath "<absolute_path_to_project>\docs\05_Hardware_Hacking_Findings" -postScript GhidraTraceNN.java "<absolute_path_to_project>\docs\05_Hardware_Hacking_Findings\decompiled_nn.txt"
```

### Critical Gotchas & Troubleshooting
1. **Error: `Could not find project`**
   * *Cause:* Ghidra's headless mode is extremely strict about paths. 
   * *Fix:* The project directory parameter must point directly to the folder containing the `.gpr` file, and the second parameter must be the exact name of the project file (minus `.gpr`).
2. **Error: `Ghidra was not started with PyGhidra`**
   * *Cause:* The `ghidra_find_wake.py` script was written in Python, but headless Ghidra does not support PyGhidra out-of-the-box on standard Windows installations.
   * *Fix:* Use the Java equivalents (`GhidraTraceNN.java` or `GhidraPhase2.java`), which compile natively via Ghidra's built-in Java/OSGi compiler.
3. **Error: `Failed to get OSGi bundle containing script` / Compilation Crash**
   * *Cause:* The `-scriptPath` was set to the root folder or a directory containing other non-script `.java` files (like the decompiled Android app). Ghidra tries to compile *every single* `.java` file in the script path and will crash if it finds syntax errors in unrelated files.
   * *Fix:* Always set `-scriptPath` to a clean, isolated directory containing ONLY your Ghidra script files (like the `docs/05_Hardware_Hacking_Findings/` folder).
4. **Error: `Project is locked` / Class Loading Failures**
   * *Cause:* The Ghidra GUI is open and actively displaying the project.
   * *Fix:* You **must** close the Ghidra GUI before running headless commands to release the project database lock.

