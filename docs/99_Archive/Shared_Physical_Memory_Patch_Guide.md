# Shared Physical Memory Patch Guide

> ## ⚠️ RETRACTED (2026-07-27) — premise disproven, kept for history
> This guide's entire premise — that the "Hey Cyan" wake-word model lives in the **RISC-V/E907 `riscv`
> firmware** as a statically-compiled TFLM array — is **false**. That image has zero `TFL3`/NN/MFCC
> symbols; the "high-entropy blocks" are **camera ISP gamma/DRC lookup tables**, not model weights. The
> wake word is **not in the V821 at all**, and not in any decrypted variant's flashable firmware either
> → it is most likely a **JieLi JL7018F built-in DSP voice-wake** the firmware only toggles (BLE `0x44`).
> ⇒ redirecting a nonexistent model to writable RAM is **moot**. A custom phrase most likely needs a
> **phone-side recognizer**, not a firmware patch. Current state:
> `../00_Executive_Summaries/status_ledger_and_options.md`.

This guide details the technical steps to redirect the statically compiled wake word model in the RISC-V firmware to a dynamically writable physical memory address, enabling instant wake word updates without OTA flashes.

## 1. Locating the Target Address
Through entropy analysis of the `riscv` ELF binary, we found several large, high-entropy data blocks in the `.text` segment (e.g., at Virtual Address `0x808d1200`). This is a classic signature of a statically compiled TensorFlow Lite Micro (TFLM) model array.

## 2. Choosing a Shared Memory Address
The RISC-V coprocessor and the Linux SoC share the same physical DDR RAM, which originates at `0x80000000`. 
To avoid colliding with Linux OS allocations and the RTOS heaps, we reserve a 1MB block at the very top of a standard 64MB RAM layout: **`0x83F00000`**.

## 3. RISC-V Assembly Patch Strategy
In the `riscv` binary, the TFLM model pointer is loaded into a register (e.g., `a0`) via the standard RISC-V two-instruction sequence:
```assembly
# Original instructions loading 0x808d1200
LUI  a0, 0x808d1       # Load upper 20 bits
ADDI a0, a0, 0x200     # Add lower 12 bits
```

Using Ghidra or a hex editor, we locate these specific opcodes and patch them to point to our reserved physical memory bridge:
```assembly
# Patched instructions loading 0x83f00000
LUI  a0, 0x83f00
ADDI a0, a0, 0x000
```
*Note: Once patched, the firmware is flashed **one final time** via the standard OTA process.*

## 4. The Linux Injection Script (`inject_model.sh`)
With the RISC-V coprocessor now actively reading its neural network weights directly from physical address `0x83F00000`, the mobile app simply transfers the new `.tflite` model over WiFi to the Linux file system (e.g., `/tmp/new_model.tflite`).

The Linux side then executes the following command to overwrite the physical RAM instantly:

```bash
#!/bin/sh
# WARNING: Writing directly to /dev/mem is dangerous. Ensure the seek address is exact.
MODEL_PATH="/tmp/new_model.tflite"
TARGET_PHYSICAL_ADDR=2213535744 # 0x83F00000 in decimal

echo "Injecting custom wake word model into shared RAM at 0x83F00000..."
dd if=$MODEL_PATH of=/dev/mem bs=1 seek=$TARGET_PHYSICAL_ADDR
echo "Injection complete. RISC-V coprocessor will use the new model on next audio frame."
```

## 5. Security & Stability Implications
- **Linux Kernel Configuration**: The Linux kernel must be booted with `mem=63M` (or similar) to prevent the OS from allocating memory in the 63MB-64MB region, reserving it safely for this bridge.
- **`CONFIG_STRICT_DEVMEM`**: If the Linux kernel has strict `/dev/mem` protections enabled, we may need to write a tiny custom kernel module instead of using `dd`, or write to `/dev/kmem`.
