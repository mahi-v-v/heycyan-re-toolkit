# Cyan Smart Glasses Firmware Reverse Engineering & Security Analysis

> ## 📌 This file is an APPEND-ONLY LOG — do not delete or overwrite
> **Purpose:** this file is the author personally documenting their reverse-engineering **journey** on this
> device, written to be included in their **cybersecurity portfolio**. It is a chronological log kept precisely
> so that no prior work is ever lost — it must retain **every** finding, attempt, tool tried, and dead-end,
> because the visible reasoning and course-corrections are exactly what make it portfolio-worthy.
> **Rules for anyone (human or AI) editing this file:**
> - Only ever **ADD** — append new dated `## N. Session <date>` sections in the existing numbered style.
> - **Never delete or rewrite** existing text, even when it's wrong.
> - Correct earlier claims **additively**: leave the original verbatim and annotate it inline
>   (`[<date> correction — see §N: …]`) and/or add a `> ⚠️ SUPERSEDED …` banner under the section header,
>   with details in the new session's "Corrections to prior sections" subsection (precedent: §9.D, §12.H).
> - This rule is **ONLY for this file.** Other docs under `docs/**` are normal working docs — update/delete
>   them freely so they always hold the latest info.

This document provides a highly detailed technical breakdown of the reverse-engineering analysis performed on the **Cyan (Oudmon/HeyCyan) Smart Glasses** firmware. It details the dual-processor architecture, OTA update mechanisms, wake-word processing pipeline, and the concrete methodologies developed to patch and verify the firmware.

> ## ⚠️ 2026-07-11 — Major corrections (read first)
> Later multi-agent analysis overturns two assumptions in this doc. Full detail + roadmap:
> **`docs/00_Executive_Summaries/ownership_and_wakeword_roadmap.md`**.
>
> 1. **The wake word is NOT in the `riscv` image.** `riscv`/`riscv_sdnand` contain zero
>    `TFL3`, no audio MMIO, and no NN/MFCC symbols; the "model candidate" density regions
>    in §4 are **camera ISP gamma/DRC lookup tables**, not neural-net weights — so
>    `patch_riscv.py` would corrupt the camera, not the wake word. The KWS almost certainly
>    runs on the **encrypted AM01CY BLE MCU** (which owns `aiVoiceWake`, opcode 0x44).
>    Treat §§2–4 and §7 below (RISC-V wake-word premise) as a disproven hypothesis.
> 2. **The `.swu` flash reset (29%/55%) is in-place-NOR contention, not the patched file.**
>    All partitions share one SPI-NOR die; erasing it while the live squashfs root is
>    demand-paged from it resets the whole SoC. Fix = flash from a non-mounted context
>    (FEL/xfel, the SD-NAND die, or the vendor's commented `switch_root`). See §9 + the log.
>
> Also new: the wake word can be **disabled** (not changed) over BLE via `aiVoiceWake`
> (0x44) — now wired into `mobile-app`. Full ownership (root via UART3 / xfel-FEL; no
> secure-boot) is achievable — see the roadmap doc.

> ## ⚠️ 2026-07-16 — Session update (device confirmed; AM01CY `.bin` is keyless, not encrypted; wake word localized)
> New findings supersede/extend several claims above — full detail in **§10** and in
> **`docs/05_Hardware_Hacking_Findings/am01cy_ota_crypto_analysis.md`**:
> 1. **Device identity CONFIRMED** (official-app About screen): the user's unit is the
>    **"CY" variant — AM01CY (BlueX RF03, BLE) + V821 (Allwinner). NO JL7018.**
>    `test_ota.bin` (`AM01CY_2.00.10_260411`) is this exact unit's running firmware.
> 2. **The AM01CY `.bin` is NOT encrypted** — it's a **keyless self-synchronizing
>    scrambler** ("shl1"), fully reversible. We can descramble **and** re-scramble +
>    fix the checksum → produce valid flashable `.bin`s. The payload is a custom-format
>    **data/model blob** (no code, no standard compression, no cipher). The `.bin`
>    checksum @0x0c is an **additive 32-bit sum** (`0x07cfde37`), **not a CRC32** as §5.A said.
> 3. **Wake word is NOT in the V821 firmware** (Linux + RISC-V, software-verified) — it's
>    on the **AM01CY**. Primary activation is the **right-rear button**; "Hey Cyan" is an
>    optional always-on extra. A *custom* wake phrase needs the RF03 chip dump.
> 4. **Reddit "crack" ≠ this device.** FerSaiyan cracked the **"G1" variant's JL7018**
>    firmware — a different machine. See §10.D.

> ## ⚠️ 2026-07-17 — Session update (live-streaming / RTMP feasibility) — see **§11**
> Investigated whether the glasses can do live video streaming / push **RTMP**.
> **Verdict: NOT on the stock Cyan firmware — it has no live-stream server at all** (the
> pipeline is record-to-storage → HTTP file download).
> 1. The official app's **"Live View"** (RTSP `rtsp://<ip>:8554/ch0`) is **capability-gated
>    OFF** for this unit: the card is hidden unless the glasses advertise a `supportLiveReview`
>    bit (`GlassesTouchSupportRsp`, `bArr[12] & 0x80`), which the AM01CY reports as **0**. That
>    RTSP path targets *other* models (K900/V881).
> 2. **ELF dynamic-symbol analysis** proves the V821 video daemon `ai_glass_video` is **UDP-only
>    (no `listen`/`accept`)** — it records H.264 to files; the only video TCP server is
>    `ai_glass_download`, a plain **HTTP file server** for recorded `.mp4`/`.jpg`. No
>    `rtsp`/`rtmp`/`ffmpeg` anywhere in the firmware.
> 3. The app's LiveKit streaming path (`GlassVideoCapturer`) is **dormant scaffolding
>    with zero callers**, written for the K900 family, not Cyan.
> 4. Live RTMP therefore requires a **V821 firmware mod** (root + a streamer tapping the VENC
>    H.264 output → RTSP/RTMP, relayed by the phone/LiveKit egress).

---

## 1. System Architecture (AMP Design)

The smart glasses utilize an **Asymmetric Multi-Processing (AMP)** architecture to balance compute capabilities (audio streaming, camera ISP, Wi-Fi connectivity) with ultra-low standby power consumption (always-on microphone, BLE, physical touch events).

```
 ┌────────────────────────────────────────────────────────┐
 │                      SMART GLASSES                     │
 │                                                        │
 │  ┌────────────────────────┐  rpbuf (OpenAMP)   ┌────┐  │
 │  │  Allwinner V821 SoC    │◄──────────────────►│RISC│  │
 │  │  (Tina Linux / v5.4)   │   Shared Memory    │-V  │  │
 │  │  Main Core (WiFi/Cam)  │                    │Core│  │
 │  └────────────────────────┘                    └────┘  │
 └────────────────────────────────────────────────────────┘
```

### A. Main Application Processor (SoC)
* **Processor**: **Allwinner V821** application processor.
* **Operating System**: **Tina Linux** (a customized OpenWrt-based distribution using the `musl` standard C library and Linux Kernel 5.4.220).
* **Role**: Handles heavy computation, Wi-Fi P2P audio/video transfer, system status, and maintains the classic Bluetooth profiles (A2DP for audio playback and SCO for microphone routing).

### B. Sensor Coprocessor
* **Processor**: **XuanTie-900 (T-Head E90x)** 32-bit RISC-V Core.
* **Operating System**: **FreeRTOS** (minor version unknown), built with the **Xuantie-900 bare-metal newlib GCC 10.4.0** toolchain (**not** `riscv32-linux-musl` — the `10.4.0` is the GCC version, not a FreeRTOS version).
* **Role**: Serves as the Always-On (AON) low-power controller — mic buffering, camera-ISP support, touch inputs, and power-state management. **It does NOT run the "Hey Cyan" KWS** (no `TFL3`/NN/audio-MMIO symbols in the `riscv` image — see the top banner; the KWS most plausibly lives on the encrypted AM01CY BLE MCU, UNVERIFIED — needs hardware). **[2026-07-22 correction — see §12.F:** the always-on KWS chip is the **JieLi JL7018F**, not a "BlueX RF03". This §1 diagram models only the V821's *internal* A27+E907 AMP and omits the JL7018F; the corrected top-level architecture is **JL7018F (main/BLE/audio) + V821L2 (camera/WiFi co-processor)**.**]**

### C. Inter-Processor Communication (IPC)
* **Framework**: Uses the OpenAMP standard **`rpbuf` (Remote Processor Buffer)**.
* **Mechanism**: Shared memory partitions are mapped between the Linux SoC and the RISC-V core. When the RISC-V core detects a wake word or physical event, it sends notifications via functions like `rpbuf_notify_by_link` to the Linux core's `ai_glass_audio` daemon to activate the main SoC pipelines.

---

## 2. ELF Partition & Memory Map Analysis

Through static analysis of the extracted `riscv` coprocessor executable, we determined it is a statically-linked, stripped 32-bit ELF binary.

### ELF Properties (`readelf -h` Verification)
```
Magic:   7f 45 4c 46 01 01 01 00 00 00 00 00 00 00 00 00
Class:                             ELF32
Data:                              2's complement, little endian
Type:                              EXEC (Executable file)
Machine:                           RISC-V
Entry point address:               0x80862e00
Flags:                             0x3, RVC, single-float ABI
```

### Linker Sections & Segment Offsets (`readelf -S`)
The RISC-V binary memory layout maps critical segments to specific physical memory regions (**reserved DDR** around `0x80860000` — the `0x808xxxxx` link addresses are DDR, not internal SRAM):

| Section Name | Type | Link Address | File Offset | Size (Bytes) | Description |
|:---|:---|:---|:---|:---|:---|
| `.text` | `PROGBITS` | `0x80862e00` | `0x003e00` | `647,168` (632 KB) | Executable instruction code |
| `.data_unsaved`| `PROGBITS` | `0x80900e38` | `0x0a1e38` | `918,664` (897 KB) | Initialized mutable data; holds arrays |
| `.blobdata` | `PROGBITS` | `0x809e2128` | `0x183128` | `7,168` (7 KB) | Read-only static data structures |
| `.bss_unsaved` | `NOBITS` | `0x809e3d40` | `0x184d28` | `57,145` (55.8 KB) | Uninitialized data; holds tensor arena |
| `.bss` | `NOBITS` | `0x809f1c80` | `0x184d28` | `15,560` (15.2 KB) | Standard unitialized global structures |

### Memory Limit (The Tensor Arena)
The `.bss_unsaved` section of exactly **57,145 bytes** is where the TensorFlow Lite Micro (TFLM) runtime dynamically allocates memory buffers (the `tensor_arena`). Any replacement keyword spotting model must have a footprint under **55 KB** to prevent runtime memory overflow crashes on the coprocessor.

---

## 3. Wake Word Call Tree & Dynamic Queue Analysis

### A. Entry Point Flow
The main handler function is `FUN_ram_80882ddc`. It runs continuously to pull from the microphone buffers:

```c
undefined4 FUN_ram_80882ddc(int param_1)
{
  int iVar1;
  undefined4 uVar2;
  
  iVar1 = param_1 + 0x1c;
  FUN_ram_80898f18(*(undefined4 *)(param_1 + 0xc));
  uVar2 = FUN_ram_80898caa(iVar1);
  if (*(int *)(param_1 + 4) == 0) {
    FUN_ram_80891214(&DAT_ram_808e5978,"rpbuf_notify_by_link",0x28a,param_1);
    FUN_ram_80898d0c(iVar1,uVar2);
    uVar2 = 0xfffffffe;
  }
  else {
    FUN_ram_80898d0c(iVar1,uVar2);
    *(undefined1 *)(gp + 0x2bce) = 0;
    FUN_ram_80891214(&DAT_ram_808e59ac,"rpbuf_compose_service_message",0x24f);
    FUN_ram_80891214(&DAT_ram_808e59f8,"rpbuf_notify_by_service",0x268);
    uVar2 = 0xffffffea;
    FUN_ram_80891214(&DAT_ram_808e5ab4,"rpbuf_notify_by_link",0x293);
  }
  FUN_ram_80898ec2(*(undefined4 *)(param_1 + 0xc));
  return uVar2;
}
```

### B. Methodology: Locating the Model Weights Pointer
* **Roadblock**: Static code tracing inside Ghidra failed to resolve any direct pointers into `.data_unsaved` from the wake-word handler logic.
* **Investigation**: A custom Java script `GhidraFindModelRef.java` performed a Headless Breadth-First Search (BFS) traversing 59 child functions down from `FUN_ram_80882ddc`. It returned zero static pointers into `.data_unsaved`.
* **Conclusion**: The coprocessor utilizes a **FreeRTOS task queue** (`*(undefined4 *)(param_1 + 0x50)`). Audio buffers are pushed onto this queue dynamically by Thread A and popped off by Thread B (the neural network inference thread). The memory address of the weights is loaded indirectly via pointer registers in the inference thread.
* **Fallback Strategy**: Because we could not dynamically trace registers statically without JTAG debuggers, we developed a **structural density-scanning methodology** to locate candidate regions in raw binary file space.

---

## 4. Model Candidate Isolation (Density Scanning)

> ⚠️ **Refuted (2026-07-11):** these three "candidate" regions are **camera ISP
> gamma/DRC lookup tables** (monotonic int16), NOT neural-net weights — there is no
> `TFL3`/NN model anywhere in this image. Zeroing them (`patch_riscv.py`) corrupts the
> camera ISP, not the wake word. Retained for the record; see the top banner + roadmap doc.

Because the binary is statically linked and uncompressed (entropy: 5.57 bits/byte), any pre-trained neural network weights or DSP coefficients must be stored as dense arrays inside `.data_unsaved`.

A script (`scan_dense_regions.py`) evaluated 1024-byte blocks for two criteria matching typical quantized models:
1. Low zero-byte density (non-zero array values).
2. High Shannon entropy (complex patterns of trained weights).

### Isolated High-Priority Candidate Regions
The scan identified three distinct **~30 KB** contiguous blocks in `.data_unsaved` with very low zero density (1.5% - 3.1%) and high entropy:

```
                  .data_unsaved Segment (897 KB Total)
 0x80900e38                                                         0x809e1c80
┌──────────────────────┬──────────┬──────────┬──────────┬──────────┬──────────┐
│                      │ Region 5 │ Region 9 │Region 16 │          │          │
│        Zeros         │   31KB   │   30KB   │   31KB   │  Zeros   │  Zeros   │
│                      │  (3.1%)  │  (1.5%)  │  (2.9%)  │          │          │
└──────────────────────┴──────────┴──────────┴──────────┴──────────┴──────────┘
```

1. **Region 5**: File Offset `0x11ba38` – `0x123638` (RAM `0x8097aa38` - `0x80982638`). Size: **31,744 bytes**. Zeros: **3.1%**. Entropy: **6.067 bits/byte**.
2. **Region 9**: File Offset `0x138238` – `0x13fa38` (RAM `0x80997238` - `0x8099ea38`). Size: **30,720 bytes**. Zeros: **1.5%**. Entropy: **6.131 bits/byte** (Strongest candidate).
3. **Region 16**: File Offset `0x154638` – `0x15c238` (RAM `0x809b3638` - `0x809bb238`). Size: **31,744 bytes**. Zeros: **2.9%**. Entropy: **6.034 bits/byte**.

---

## 5. OTA Pipelines & Security Analysis

We identified a major security disparity between the BLE DFU update pipeline and the Wi-Fi Cloud-OTA framework.

### A. BLE Coprocessor DFU (Insecure BLE Service)
* **File Format**: `1MB` raw binary (`firmware.bin`).
* **Header Structure**: plaintext version strings (e.g., `AM01CY_2.00.10_260411` at offset `0x10` and `AM01CY_V2.0` at `0x30`).
* **Integrity**: A **32-bit additive byte-sum** of the payload at offset `0x0c` (`0x07cfde37`, verified — **not** a CRC32; corrected 2026-07-16). No cryptographic signatures are enforced; the checksum is trivially recomputable. See §10.B.
* **Obfuscation (2026-07-16):** the payload is **not encrypted** — it is a **keyless `shl1` self-synchronizing scrambler**, fully reversible both ways. Full analysis: §10.B + `am01cy_ota_crypto_analysis.md`.
* **Risk**: High. Pushing a bad BLE binary can crash the BLE stack, leaving the glasses permanently bricked.

### B. Wi-Fi OTA Partition Flash (`swupdate` & `ai_glass_ota` Daemon)
* **File Format**: `.swu` package (standard CPIO archive using `SVR4 with CRC` / `newc` format).
* **Update Engine**: The glasses run a dedicated compiled binary at `/bin/ai_glass_ota` which manages the download and updates.
* **Flashing Mechanism**: Once the file is fetched, the daemon executes standard SWUpdate commands depending on the active boot partition:
  * `swupdate -i /mnt/UDISK/openwrt_v821_aiglass-ab.swu -e stable,sdnand`
  * `swupdate -i /mnt/UDISK/openwrt_v821_aiglass-ab.swu -e stable,nor`
* **Version/Downgrade Checks**: None. The version configuration in `sw-description` is statically hardcoded to `0.1.0`. The `ai_glass_ota` daemon does not restrict re-flashing the same version or downgrading.
* **Security Verification**: 
  - There are no cryptographic keys (RSA/CMS) configured inside `/etc/swupdate/`.
  - The `.swu` file contains no signature manifest file (e.g. `sw-description.sig`), and the `sw-description` lacks `sha256` keys for images.
  - Verification is purely CRC/MD5 based. MD5 hashes of individual images are stored in a plaintext `cpio_item_md5` manifest inside the CPIO archive. Bypassing validation simply requires updating the image MD5 inside `cpio_item_md5` and generating a correct CPIO format.
* **Critical Protocol Quirk (long-URL buffer-overflow crash) — SUPERSEDED HYPOTHESIS:**
  > ⚠️ **Superseded (see §9, binary-confirmed):** the long-URL/buffer-overflow theory below was **not** the actual cause. Disassembly of `ai_glass_ota` shows the confirmed failure driver is **transfer truncation + in-place-NOR flash contention** (a >5 s stall or early close yields a truncated `.swu` that the daemon flashes; erasing the shared SPI-NOR die while the live squashfs root is demand-paged resets the SoC). No URL-length buffer-overflow path was found in the binary. Retained below only for the record.
  - ~~The `/bin/ai_glass_ota` daemon is compiled in C and has a strict size limit on the URL string parsed from BLE.~~
  - ~~Very long URLs (e.g., GUID filenames generated by Expo/Android DocumentPicker cache paths like `http://192.168.49.1:8080/24715fd9-7ffc-4c5a-a83f-32d8c4ade838.swu`) exceed internal buffer thresholds, causing the daemon to silently crash after downloading the file, leaving the device in a hung state with no flashing triggered.~~
  - ~~**Mitigation**: The URL must use a short filename target (e.g., `/fw.swu`) in the BLE notify payload and the HTTP headers (such as the `Content-Disposition` header) to keep the URL string below 64 characters.~~ (Using a short filename is still harmless hygiene, but it is **not** the fix — the fix is flashing from a non-mounted context; see §9.)
* **Safety Property**: Low risk of bricking. The system runs an A/B partition configuration with active fallback. If the custom coprocessor image fails to execute, the Linux bootloader automatically rolls back to the previous partition.
  * ⚠️ **Correction (see §9, binary-confirmed):** This A/B/rollback assumption is **UNVERIFIED**. Disassembly of `ai_glass_ota` shows the `nor` selector's `rootfs` image targets `mtdblock5` — the **mounted, running** root — and no inactive-slot separation could be confirmed. Overwriting the live rootfs is a genuine mid-flash hang/brick hazard. Do not rely on rollback until the on-device partition table is read.

---

## 6. Wi-Fi Connectivity & Hidden SSID Resolution

During our efforts to establish a local firmware delivery path from our laptop HTTP server, we encountered and resolved several networking roadblocks:

### A. The BLE vs. Classic Bluetooth Illusion
* **Roadblock**: When connecting the glasses, the smartphone's system Bluetooth settings panel reported the glasses as "Disconnected" or "Unpaired," despite the companion app actively reading and responding to real-time charging status and battery changes.
* **Methodology**: Evaluated Bluetooth communication APIs.
* **Conclusion**: Bluetooth Classic (audio streams, hands-free profile) operates at the OS system level, while **Bluetooth Low Energy (BLE)** GATT communication occurs directly within the sandboxed app environment. The app can read and write to the glasses' GATT characteristics without registering the device as "Connected" in system settings.

### B. The P2P Subnet Isolation Roadblock
* **Roadblock**: We initially triggered `writeIpToSoc` using the laptop's home Wi-Fi IP (e.g. `192.168.29.3`), but the Python HTTP server never logged any incoming connections from the glasses.
* **Methodology**: Investigated the Android SDK's `CyanFileSyncManager.kt` and `GlassVideoCapturer.kt` classes, and evaluated the strings inside the glasses' `ai_glass_ota` binary.
* **Findings**: 
  - The glasses do not connect to a standard home router. Wi-Fi is powered off by default to save energy.
  - When the OTA is triggered, the glasses start their own Wi-Fi card in **Access Point (AP) / Hotspot** mode. 
  - The glasses' `ai_glass_ota` binary executes the `hostapd` service configured in `/etc/wifi/hostapd/hostapd.conf`.
  - The default configuration uses an open network (`tmp-ssid`) which the glasses rewrite dynamically to `CY01_<MAC_ADDRESS>`.
* **The SSID Revelation**: Testing on iOS revealed that the phone attempts to connect to the SSID **`CY01_66C666D903DC`** (where `66C666D903DC` is the clean MAC address of the glasses).
* **The Hidden Network Roadblock**: The glasses broadcast this AP as a **Hidden SSID** (does not advertise its SSID name in beacon packets). Standard Wi-Fi scans on a laptop will completely ignore it.
* **The Security Key Challenge (WPA2 Prompt)**:
  - *Methodology*: Attempted to connect a Windows laptop to the hidden SSID as an open network. Windows requested a WPA2 security key (network password), indicating the AP mode is not open but utilizes a pre-shared key.
  - *Code Audit & Discovery*: Conducted a grep search for default Wi-Fi passwords and credentials across the decompiled companion app codebase (`apk_extract` and `HeyCyanSmartGlassesSDK`).
  - *Finding*: Located a hardcoded password string of **`123456789`** in the following SDK files:
    - `MyBluetoothReceiver.java`: `UserConfig.Companion.getInstance().setGlassDeviceWifiPassword("123456789");`
    - `WiFiConnectActivity.java`: `this.wifiPassword = "123456789";`
* **The P2P Connection Timeout Roadblock**:
  - *Methodology*: Attempted to trigger the SDK's built-in peer-to-peer file synchronization (`Glass.syncFiles()`) to automatically establish a Wi-Fi Direct connection and route update traffic through the phone's P2P gateway.
  - *Observation*: The connection failed to establish, and the Python HTTP server received no requests.
  - *Analysis*: Code tracing inside `CyanFileSyncManager.kt` showed that if the phone's P2P discovery doesn't locate the glasses within 16 seconds, it triggers `SyncEvent.Completed` with a timeout error. This immediately calls `cleanup()`, sending the `CMD_EXIT_TRANSFER` command to the glasses, which shuts down their Wi-Fi card entirely. P2P discovery fails if the glasses are already in AP mode or if local radio state/permissions block standard P2P discovery.
* **Resolution**: The optimal and most reliable way to deliver the local update is to connect the laptop directly to the glasses' AP by typing the SSID manually (`CY01_66C666D903DC`), selecting WPA2-Personal, and entering the hardcoded password **`123456789`**. This avoids all phone P2P discovery timeout loops.

---

## 7. The Proof-of-Concept Pipeline

To test if we have located the model weights, we created a full pipeline script to patch, repackage, and serve the modified firmware.

### Phase 1: Patching (`patch_riscv.py`)
This script zeroes out target dense data segments in the `riscv` executable binary.

```python
with open('riscv', 'rb') as f:
    original = bytearray(f.read())

# Zero out the three high-priority regions
regions = [
    (0x11ba38, 0x123638), # Region 5
    (0x138238, 0x13fa38), # Region 9
    (0x154638, 0x15c238)  # Region 16
]

for start, end in regions:
    original[start:end] = bytes(end - start)

with open('riscv_patched', 'wb') as f:
    f.write(original)
```

### Phase 2: Repackaging CPIO (`repackage_swu.py`)
This script parses the CPIO newc archive structure, swaps out the binary members, updates the MD5 manifest file, and outputs a valid `.swu` file.

```python
# Minimally rebuild CPIO archive structures (supporting SVR4 portable CPIO with CRC magic 070702)
# Recompute payload arithmetic byte checksums and update the 'check' headers
# Re-inject recalculated MD5 hashes into cpio_item_md5
```

### Phase 3: local Deployment (`serve_swu.py`)
This runs a local HTTP server on port `8080` to serve the custom `.swu` file.
Flashing is initiated by sending BLE Opcode `0xFC` containing the URL:
`http://<laptop_ip>:8080/firmware_patched.swu`

```
Glasses (Tina Linux) ── [Connects to WiFi] ──► Laptop (httpd serve)
       │                                              │
       ◄──────── [Downloads & verifies MD5] ──────────┘
```

If the zeroed-out firmware successfully boots but the glasses stop reacting to "Hey Cyan," the target region is confirmed as the acoustic model weight storage block.

---

## 8. Official Wi-Fi OTA Handshake Protocol

Following our initial roadblocks where the local HTTP server received no connections, we audited the official Android application's decompiled source code (`apk_extract/sources/com/glasssutdio/wear/ota/OTAActivity1.java`) to reverse-engineer the exact sequence of events during a firmware update.

### A. Methodology: Decompiled APK Static Analysis
* **Initial Assumption**: We believed the glasses connected to the home Wi-Fi network or that standard P2P discovery (via `CMD_TRANSFER_MODE`) was required.
* **Tracing `writeIpToSoc`**: A `grep` search for `writeIpToSoc` across the decompiled app revealed its usage inside `OTAActivity1.java`. The command always took the format `"http://" + str + ":8080/" + this.wifiFirmWareName`. This proved the app itself hosts the HTTP server on port `8080`, rather than relying on a cloud server for the final transfer.
* **Server Implementation Audit**: Searching for HTTP server libraries (like NanoHTTPD) yielded nothing. Code review of `startServer()` revealed the app instantiates a raw, completely custom `java.net.ServerSocket` on port 8080 that manually sends back raw `HTTP/1.1 200 OK` headers and the file byte stream.
* **Discovering the Handshake Triggers**: By tracing backward from `startSocOtaServer()`, we analyzed the `startSocOta()` method. It executes `LargeDataHandler.getInstance().glassesControl(new byte[]{2, 1, 5})`. `glassesControl` is **BLE opcode `0x41`**; the `{2, 1, 5}` is the **payload** it carries (the leading `0x02`/`0x04` are payload/subtype bytes — a mode/sub-command selector — **not** the BLE opcode). So the correct trigger is `glassesControl` (0x41) with the `{2,1,5}` payload, distinct from the media-sync/transfer path (`{4,...}` payload) we were previously testing.
* **The IP Address Revelation**: We discovered a nested class `MyDeviceNotifyListener` extending `GlassesDeviceNotifyListener`. It listens for a BLE notification where the 7th byte (`loadData[6]`) equals `8`. When this notification arrives, bytes 7 through 10 contain four octets representing an IP address. The app then calls `getClientIPAddress(sb)` to iterate over the smartphone's network interfaces, finding the smartphone's local IP address that exists on the *same subnet* as the IP provided by the glasses.

### B. The Complete Sequence of Events
Through this analysis, we concretely established the correct flow for triggering an OTA update:

1. **Pre-OTA Command**: The companion app sends a BLE command via `glassesControl` (**BLE opcode `0x41`**) with the payload bytes `{2, 1, 5}` (the `0x02` is a payload/subtype byte selecting the OTA mode, not the BLE opcode). This instructs the glasses to transition into OTA mode and boot up their `CY01_<MAC>` Wi-Fi Access Point.
2. **Network Transition**: The smartphone connects to the glasses' AP (either automatically via Android Wi-Fi APIs or via manual WPA2 connection).
3. **The IP Broadcast**: Once the glasses' Wi-Fi stack is fully initialized, the glasses send a BLE notification back to the app (`loadData[6] == 8`), delivering their active IP address (e.g., `192.168.43.134`).
4. **Server Initialization**: The companion app calculates its own IP on that subnet (e.g., `192.168.43.1`), spins up a raw `ServerSocket` on port 8080, and binds it to that IP.
5. **The Handoff**: The app executes `writeIpToSoc` with the payload `http://192.168.43.1:8080/firmware.swu`.
6. **Execution**: The Linux SoC on the glasses fetches the `.swu` file from the smartphone and triggers `swupdate`.

### C. Implications for Local Tinkering
Our previous attempts failed because we were attempting to use the media-synchronization/transfer path (which triggers aggressive P2P discovery timeouts) instead of the dedicated `glassesControl` (**BLE opcode `0x41`**) OTA sequence carrying the `{2,1,5}` payload. Furthermore, we expected the glasses to route through the home LAN, whereas the glasses strictly enforce a direct AP connection model. To replicate this locally using a laptop, the developer must manually connect the laptop to the `CY01_<MAC>` network before initiating the `writeIpToSoc` command.

---

## 9. OTA Daemon Internals & Flash-Failure Root Cause (Binary-Confirmed)

> ⚠️ **ANNOTATION (2026-07-27, append-only; adjudicated by multi-agent review + new user evidence).**
> This section frames the daemon's **truncated-download** bug as THE flash-failure root cause. That
> is now **subordinated to secondary**. The adjudicated **primary** cause of the 29%/55% halt is
> **in-place SPI-NOR self-flash contention**: the running squashfs root is demand-paged from
> `mtdblock5` on the single PY25Q256 die, and `swupdate -e stable,nor` erases that same die
> (kernel→mtd3 first) with `preinstall_nor.sh`'s `switch_root`-off-NOR pivot **commented out** — so
> the first erase busies the die and the SoC livelocks on the next demand page-fault (b4=0 → 29%,
> LED held on, no reboot; the 55%/LED-off run is the same mechanism one stage later + the phone's
> ~60 s P2P teardown). Refuted truncation: a **complete proxy download** still stalls, and a **~3 MB
> file** stalls identically. The truncation decode below remains valid as an *independent secondary*
> reliability bug confined to the download (b2) phase. The top banner already states
> NOR-contention-primary; this annotation reconciles §9 with it.

Disassembly of the glasses-side OTA daemon
`hardware_research/firmware_and_os/extracted_fw/rootfs_unpacked/bin/ai_glass_ota`
(Andes AndeStar V5 / RV32IMAFDC, musl, **ET_EXEC (non-PIE)**, **not stripped**, GCC 10.4.0; the
Linux-SoC-side daemon — distinct from the `riscv` KWS coprocessor image analysed in
§2). Launched from `etc/media/rtc_init.sh:78` when `/sys/kernel/aglink_mode == 3`;
`argv[1]` selects boot media (`0`=nor, `1`=sdnand). Full narrative in
`docs/05_Hardware_Hacking_Findings/ota_flash_debugging_log.md`.

### A. The download routine (`start_down_ota_firmware` @ 0x13968) — the bug
A minimal in-daemon HTTP/1.1 client (HTTP only — logs *"Not support https"*; default
port 80; streams to disk via `fwrite` in 4096-byte `read()` chunks; save path
`/mnt/UDISK/openwrt_v821_aiglass-ab.swu`). It has three fatal properties:

1. **5-second receive timeout** — `setsockopt(SO_RCVTIMEO)` @ 0x13cd0, `tv_sec=5`.
2. **No resume** — the GET carries **no `Range:` header**; any re-download restarts
   from byte 0 (matches the observed "restart from 0" on every retry).
3. **No completeness check** — a 5 s stall (timeout-errno branch @ 0x13eca) **returns
   success (0)**, as does an early peer FIN (`read()==0`). It **never compares
   `downloaded_bytes` vs the `Content-Length` `total_bytes`.** No size/md5 check in
   the daemon at all; integrity is delegated entirely to swupdate's per-CPIO-item
   `cpio_item_md5`.

The caller `aglink_ota_task` (0x13766–0x137be) is single-threaded and runs swupdate
**immediately** when the download "succeeds":
```
r = start_down_ota_firmware(url)   // blocking
if (r == 0) start_ota_sh()         // system("swupdate -i ...swu -e stable,{nor|sdnand}"); sync()
```
Only pre-check anywhere is `access(swu, F_OK)` (existence).

> **Consequence:** over a flaky phone→glasses Wi-Fi link, any >5 s stall or early
> close yields a **truncated `.swu` that the daemon treats as complete and flashes**.
> swupdate's md5 then fails **wherever the truncation landed** → the failure point
> varies with the link, which is exactly the observed behaviour.

### B. Progress protocol (what the % measures)
Progress is **entirely glasses-reported over BLE** (`0x73`, subtype `4`); the phone
measures nothing about the transfer. Two daemon threads produce two numbers:

| Phone stage (`combineProgress` weight) | Source | Meaning |
|:---|:---|:---|
| `b2` — 0→29% (0.29) | `get_download_firware_schedule` @ 0x1394a = `100·downloaded/total`, pushed every 500 ms | phone→glasses **download %** (bytes) |
| `b3` — 29→30% (0.01) | handoff | download done → swupdate connecting to `/tmp/swupdateprog` |
| `b4` — 30→100% (0.70) | `aglink_ota_get_swuupdata_bar_task` @ 0x11a16 reads swupdate's `/tmp/swupdateprog` | **swupdate flash %** |

Phone: `OTAActivity1.java:926` →
`round(0.29·b2 + 0.01·b3 + 0.70·b4)`; notify handler at lines 843-949; raw HTTP file
server `handleClient` at 750-776 (no integrity); **60 s no-progress watchdog** at
line 925 (silent glasses → `otaFail()`); `onResume` (585) `finish()`es with no active
network.

### C. The two failure modes (29% vs 55%) — one cause, two landing spots
- **Mode A — 29% (b2≈100, b4=0), Wi-Fi LED stays ON.** Truncation near the front →
  swupdate rejects the file instantly → `b4` never advances. LED stays lit because
  the daemon **never touches Wi-Fi on the flash path** (all `wifi_off`/`stop_p2p`
  are in the P2P/AP state machine only). Phone quits after the 60 s watchdog.
- **Mode B — 55% (b4≈36%), Wi-Fi LED turns OFF then freezes.** Enough arrived to
  flash the early images and reach ~36% (writing 3.4 MB `kernel`/`rootfs` to NOR),
  then it stalls on the corrupt region. LED-off = **P2P group teardown** (phone 60 s
  timeout → `finish()`) and/or the live-rootfs overwrite killing the Wi-Fi driver.

### D. Corrections to prior sections
- **No watchdog, no `reboot()`** in the daemon; swupdate auto-reboot
  (`swu_next="reboot"`) is commented out in `sw-description`. Any "brownout/reset
  during flash" theory is **not supported** by the binary.
- Brick-safety / clean A/B rollback (§5.B) is **UNVERIFIED** — the `nor` `rootfs`
  target is `mtdblock5` (the mounted root); no inactive-slot separation was
  confirmed.
- The MITM/forge and the `.swu` content are **not** the blocker — stock and patched
  images fail identically; the blocker is transfer truncation.

**Undetermined (needs on-device shell):** exact `/mnt/UDISK` & mtd partition sizes;
the exact BLE `tx` op that emits the flash-% (Andes GP-relative ops not byte-decodable
with available tooling); the definitive P2P/LED-drop trigger.

---

## 10. Session 2026-07-16 — Device identity, AM01CY `.bin` cryptanalysis, wake-word localization

> ⚠️ **SUPERSEDED IN PART BY §12 (2026-07-22).** Two conclusions below are now corrected: (a) the
> BLE/main chip is a **JieLi JL7018F**, *not* a "BlueX RF03" (§10.A/C/D); (b) the `.bin` is a
> **reused-keystream cipher** (breakable with ≥2 versions) and *is* the JL7018 firmware, *not* a
> "keyless scrambler / on-chip-ROM model blob" (§10.B). The device-identity About-screen table
> (§10.A) and the V821-elimination (§10.C) remain valid.

This section consolidates a full session of analysis on the **user's specific unit**. Deep crypto
detail lives in `docs/05_Hardware_Hacking_Findings/am01cy_ota_crypto_analysis.md`; working scripts
are in `hardware_research/am01cy_crypto/` (`descrambled_full.bin` = the recovered plaintext).

### A. Device identity — CONFIRMED via the official-app "About" screen
| Field | Value | Note |
|:---|:---|:---|
| Bluetooth name | `CY01_03DC` | Cyan device (the `CY01_` prefix is in the app's `CyanAdapter`) |
| Hardware version | `AM01CY_V2.0` | BLE/control MCU |
| Software version | `2.00.10_260411` | **exact match to `test_ota.bin`** → that file IS this unit's running firmware (provenance proven) |
| WiFi hardware version | `WIFIAM01CY_V2.0` | the Allwinner V821 (wifi/camera side) |
| WiFi software version | `0.13.14_2505282110` | our local `.swu` is a *different* build (not this one) |
| MAC | `66:C6:66:D9:03:DC` | locally-administered/private (bit 1 set) — no vendor OUI lookup |

⇒ **The user's device is the "CY" (Cyan) variant: two chips — AM01CY (BlueX RF03, BLE/control) + V821 (Allwinner, wifi/camera/Linux). No JL7018.** This is a *different revision* from the Reddit RE community's "G1" variant (`AM01G1_V9.2`, which has a JL7018 JieLi main SoC — see §10.D).

### B. The AM01CY `.bin` is a KEYLESS scrambler, NOT encryption
- **Container:** 0x50-byte plaintext header — magic `e5c3bd81` @0x00; payload size `0x000fb3a1` @0x04 **and** @0x08; **additive 32-bit byte-sum checksum `0x07cfde37` @0x0c** (verified exact — trivially recomputable, no forge barrier; corrected from the "CRC32" in §5.A); version strings @0x10/@0x30 — then a **1,029,025-byte payload**.
- **Obfuscation = keyless `shl1` self-synchronizing scrambler:** descramble `p[i] = c[i] ^ ((c[i-1]<<1)&0xFF)`, `p[0]=c[0]`. **Proven optimal** across a broad transform search (byte 1/2-tap, 16/32-bit word, position-XOR, delta, cumulative — scored by chi² and by structured-region entropy; nothing beats plain `shl1`).
- **Proof it is NOT a cipher:** the raw payload is 1st-order uniform (chi²≈349), but **descrambling makes it strongly non-uniform (chi²=5796)**. A self-sync scrambler *whitens* structured plaintext into uniform-looking output; descrambling recovers the bias. A real cipher (AES-CTR/CBC/ECB/stream) emits *independent* uniform bytes that stay uniform under `shl1` (chi²≈255) — so chi²=5796 mathematically excludes strong encryption. Also: ECB test = **0** duplicate 16/32-byte blocks; no keystream reuse at any period; not a seeded PRNG/LFSR/RC4 (all swept).
- **Full container round-trip achieved:** descramble → (edit) → re-scramble reproduces the original **byte-for-byte**, and the additive checksum recomputes and matches → **we can produce valid, flashable `.bin` files.**
- **The descrambled payload is a custom-format data/model blob** (`descrambled_full.bin`): ~20% clearly-structured tables (`0x00`/`0x21`-heavy, 32-byte records), ~80% high-entropy structured data (chi²-biased, 32-byte record stride). It contains **no ARM code** (`bx lr`/`0x4770` count = 4, below chance; no RF03 pointer tables even at the correct `0x008xxxxx`/`0x001xxxxx` addresses), **no standard compression** (deflate/zlib/lzma/lz4/zstd/bz2/brotli/heatshrink/classic-LZSS **and the vendor's own bundled LZO1X** — all negative, decoders verified), **no standard model/audio magic** (TFL3/ONNX/RIFF/…), and it is **not** float16/float32/int8 weights.
- **Interpretation:** the `.bin` is the AM01CY's wake-word **model/config data**, keyless-scrambled. The **program code** that interprets it lives in the RF03's on-chip ROM (not shipped in OTA), so a software-only *interpretation* (and thus a custom-model edit) is blocked without that code. Definitive path = **RF03 SWD/UART dump** (no readout protection — see `am01cy_ota_crypto_analysis.md` Part 2).
- **RF03 memory map** (from the atc1441 Colmi R02 plaintext firmware, downloaded as a known-plaintext reference): **SRAM `0x00100000`, flash/XIP `0x00800000`** — NOT the standard `0x20000000`/`0x08000000`. (This corrected an earlier failed vector-table search that used the wrong ranges.)

### C. Wake-word localization — NOT on the V821 (software-verified)
The entire plaintext V821 firmware was searched for the always-on wake path:
- **Linux daemons** (`bin/ai_glass_*`): `ai_glass_audio` is an opus recorder fed over `aglink`; `ai_glass_video` is ISP/mux; the rest are mode/photo/ota/download. **No KWS / keyword / voice-wake logic.**
- **`libaglink.so` inter-core IPC carries ONLY media** (`AG_AD_AUDIO`, `AG_MODE_RECORD_AUDIO`, `AG_VD_RECORD_AUDIO_START/STOP`) — **no wake event crosses cores.**
- **RISC-V/E907 strings**: camera ISP + power-management "wakeup" (rpbuf/suspend) only — **no KWS** (the `kws`/`rcsp` hits were binary-garbage / `strcspn` false positives).
- The V821 mic (`MicPlusAec`/`MicHard`/`MIC1`/`codec_mach`) is for **post-activation recording**, not wake detection.

⇒ The always-on "Hey Cyan" detector is on the **AM01CY (BlueX RF03)** — the only low-power always-on chip — by thorough elimination + the app's "operate continuously / battery drains" description (an always-on KWS). This finally corrects §§2–4/§7 (RISC-V wake premise) *and* rules out the Linux side.

**Interaction model (app strings, `AM01` variant):** two activations — (1) optional always-on voice **"Say HeyCyan"** (`h_glass_162`; `h_glass_168`: *"operate continuously… battery will drain… switch off when not in use"*), and (2) **PRIMARY = "Click the right rear button"** (`h_glass_163_am01` / `h_glass_164_am01`). The wake word is the secondary/optional path; the button needs no wake word.

⇒ A **custom wake phrase is not achievable from the plaintext V821 firmware** (verified) — it needs the AM01CY model + RF03 code (chip dump). The wake word can already be **disabled** over BLE (`aiVoiceWake` 0x44, wired into `mobile-app`).

### D. Community / Reddit intel + the "CY" vs "G1" variant split
The user's own Reddit threads (fetch via `https://www.reddit.com/comments/<id>/.rss` — `www.reddit.com` is blocked for WebFetch/WebSearch and `.json` returns 403; the `.rss` endpoint works):
- **r/SmartGlasses `1qlncxz`** ("$10 bounty: help me dump the firmware"): `/u/plated_lead` has **~19 firmware copies** (offers private-repo invites); `/u/Choice_Complaint9171` has a consistent wifi-chip dump method. The bounty poster's device = model **AIMB-G3, `AM01G1_V9.2`, JL7018F main + V821L2** → the **"G1" variant**.
- **r/SmartGlasses `1so7utm`** ("HeyCyan Help"): `/u/nodatauser0` = **FerSaiyan** (CyanBridge alt-app dev) — *"able to **crack encryption, decompile the code into assembly/pseudo c**… reencrypt the firmware and test flashing it"* (~85% done; goal = remove shutter sound + LED flash). This is the **JL7018 (JieLi) encrypted CODE firmware of the "G1" variant** — a **different machine** than the user's "CY" variant. JieLi is dumpable via `kagaimiq/jl-uboot-tool` + the open `Jieli-Tech/*` SDK.
- **r/augmentedreality `1ub1fs9`**: FerSaiyan's CyanBridge release notes (P2P sync, RE with Ghidra).
- **Red herring:** the app endpoint `POST /glasses/encryption/getKeys` + `SecureKeyManager` (`QcService.java`, `APPKeyData.encryptedAESKey`, RSA-wrapped AES-GCM) is **API-payload** encryption, **not** firmware.

⇒ **The Reddit "crack" does not transfer to the user's device.** The user's "CY" variant has no JL7018; its wake-word target is the AM01CY/RF03.

### E. `mobile-app` OTA improvements shipped this session
- **WiFi OTA progress bar:** wired the real glasses-reported combined progress (`NOTIFY_OTA` `0x04`; weighted `round(0.29·b2 + 0.01·b3 + 0.70·b4)` per §9.B) into the OTA screen — it was previously mis-decoded (single byte → wrong event). See `CyanAdapter.kt` `notifyListener`.
- **WiFi OTA transport:** rerouted the OTA-mode ACK from the AP-join path (which forced a failing system Wi-Fi prompt) to `startOtaP2pDiscovery()` — **Wi-Fi Direct auto-connect**, matching the official app.

### F. Net status for the user's goals (CY variant)
| Goal | Path | State |
|:---|:---|:---|
| Custom wake *phrase* | AM01CY/RF03 SWD dump (get the code) | software-exhausted; hardware next |
| Disable wake word | BLE `aiVoiceWake` 0x44 | ✅ done, no flashing |
| Kill shutter sound / LED flash | plaintext V821 firmware/scripts (likely) | **not yet investigated — highest-value software target** |
| General root/ownership/audit | plaintext V821 + UART3 root | software-accessible |
| Live video streaming / RTMP | V821 firmware mod (root + tap VENC → RTSP/RTMP) | **not in stock fw — no live server; needs firmware. See §11** |

---

## 11. Session 2026-07-17 — Live-Streaming & RTMP Feasibility Analysis

**Question:** can the Cyan glasses stream live video — and specifically push **RTMP** to a
CDN/media server (YouTube/Twitch/nginx-rtmp)? **Verdict: not on the stock firmware.** The
camera pipeline is **record-to-storage → HTTP file download**; there is **no live network
video path** on the device, and the official app's live-preview feature is capability-gated
**OFF** for this exact unit. This section documents the methodology, because *"the feature
exists in the app's code"* turned out **not** to mean *"this device can do it"* — a
distinction only the firmware and the BLE capability handshake could settle.

### A. What the video pipeline actually is
The Allwinner V821 has a hardware **H.264 encoder (VENC, via the CedarX / `librt_media.so`
media stack)**. String analysis of the video daemon `rootfs_unpacked/bin/ai_glass_video`
shows it drives VENC (`VENC`, `H264`, `NALU`, `GetStream`) but writes to **files**
(`stream_file`, `stream_mkdir`, `record`); the recorded media is served **after the fact** by
a separate daemon:

| Daemon | Role | Network |
|:---|:---|:---|
| `ai_glass_video` | VENC H.264 capture → files on `/mnt/UDISK` | UDP control socket only (see §11.C) |
| `ai_glass_download` | serve recorded `.mp4`/`.jpg` to the phone | **HTTP/1.1 file server** (`GET`, `Content-Length`, `200 OK`, `/mnt/UDISK/`) |
| `ai_glass_ota` | pull `.swu` over Wi-Fi (see §9) | HTTP **client** |

Two live-preview transports appear in code, but **neither belongs to this device's firmware**:
- The **official HeyCyan app** plays `rtsp://<glassIP>:8554/ch0` via libVLC
  (`RealTimePreviewActivity` + `RealTimePreviewVlcConfig`: HW decode, `--rtsp-tcp`,
  `:no-audio`, 300 ms cache).
- The **the app** carries `GlassVideoCapturer.kt` — a LiveKit `VideoCapturer` that reads
  **length-prefixed raw H.264 NAL units over a TCP socket**, decodes them with `MediaCodec`,
  and republishes to a LiveKit room as track `glass_camera`.

These are **two different wire formats** (RTSP/RTP vs raw-NAL-over-TCP), and §11.C proves the
Cyan firmware serves *neither*: the RTSP:8554 path is for models that advertise the
live-review capability (K900/V881); the app raw-NAL path is dormant scaffolding (§11.D).

### B. Layer 1 — the UI is capability-gated by the glasses themselves
The reason no "stream/live" control appears anywhere in the official app is a
**device-advertised capability bit**, not a hidden menu.

- The home screen's **"Live View" card** (`HomeFragment.clsLiveView`) launches
  `RealTimePreviewActivity` on click (`loadDataData$lambda$15$lambda$6` → `new Intent(...,
  RealTimePreviewActivity.class)`), gated at click only by a 5 s re-entry throttle
  (`RealTimePreviewEntryThrottle`) and a >15% battery check.
- Its **visibility** is `clsLiveView.setVisibility(getSupportLiveReview() ? VISIBLE : GONE)`
  (`HomeFragment` lines 596 / 2393).
- `supportLiveReview` is set in `DeviceCmdInit` from a **BLE "glasses feature support"
  response** (`GlassesTouchSupportRsp`) that decodes a multi-byte capability bitmask. The
  live-review flag is **`bArr[12]`, bit 7 (0x80)**: `supportLiveReview = (bArr[12] & 0x80) !=
  0`. Default `false`.

The user's AM01CY unit reports that bit as **0**, so the card is `GONE` — the app is faithfully
reflecting the glasses' own declaration that they do not do live review.

**Reverse-engineered capability bitmask** (`GlassesTouchSupportRsp.acceptData`, byte offsets
into the BLE response payload) — a reusable protocol artifact:

| Payload byte | Bits → capability |
|:---|:---|
| `bArr[7]` | `glassesModel` (int) · `bArr[8]` = `translationSupport` · `bArr[9]` = `wearCheck` |
| `bArr[10]` | 0 volumeControl · 1 earphone · 2 (¬)supportAI · 4 gyro · 5 rotation · 6 waveGuide · 7 videoDirection |
| `bArr[11]` | 0 offlineVoice · 1 videoTimestamp · 5 videoCut · 6 videoInterpolation · 7 imageEnhancement |
| `bArr[12]` | 0 gyroAuthor · 3 supportV881 · 4 horizontalCorrection · 5 supportResolution · 6 supportRTChat · **7 supportLiveReview** |
| `bArr[13]` | 0 support1300W · 1 isPureBluetooth · 2 currWorkType · 3 supportShortcut · 4 supportAov · 5 moreGyroList · 6 localMusic |

### C. Layer 2 — the firmware has no live-stream server (ELF dynamic-symbol proof)
UI gating alone could be bypassed, so the decisive question was whether the firmware can serve
a live stream *at all* if the gate is ignored. **Methodology: a binary's imported dynamic
symbols reveal its socket role** without executing it —
- a TCP **server** must import `listen` **and** `accept`;
- a TCP **client** imports `connect`;
- `bind` + `recvfrom` + `sendto` with **no** `listen`/`accept`/`connect` = a **UDP** endpoint
  (local control/IPC), not a stream server.

`readelf --dyn-syms` across the socket-capable binaries in the V821 rootfs:

| Binary | Imported socket symbols | Role |
|:---|:---|:---|
| `bin/ai_glass_video` | `socket, bind, recvfrom, sendto` | **UDP** control only — **no `listen`/`accept`** ⇒ not a video server |
| `bin/ai_glass_download` | `socket, bind, listen, accept, htons` | TCP **server** — but an HTTP **file** server (`GET .../*.mp4|*.jpg`) |
| `bin/ai_glass_ota` | `socket, connect, htons` | TCP **client** (pulls `.swu`, §9) |
| `lib/librt_media.so` | `socket, bind, recvfrom, sendto` | UDP control only |
| `lib/libaglink.so` | `socket, bind` | inter-core IPC |
| `bin/ai_glass_photo`, `bin/ai_glass_normal` | *(none)* | no sockets |

The **only** rootfs binaries importing `listen`+`accept` are `adbd`, `ai_glass_download`,
`wifi_daemon`, and `swupdate` — i.e. adb, the recorded-file server, the Wi-Fi manager, and the
OTA flasher. **None is a live A/V streamer**, and the rootfs contains **no `rtsp` / `8554` /
`rtmp` / `ffmpeg` / `librtmp` strings anywhere.** Conclusion: this firmware records, then
serves files; it never opens a live video stream.

### D. Layer 3 — the app's streaming code is dormant scaffolding
To rule out "it's just unwired in the app," the app side was traced:
- `Agent.kt` defines `startGlassVideoStream(host, port)` → `publishGlassVideoTrack` →
  `GlassVideoCapturer` → LiveKit `glass_camera`. But `startGlassVideoStream` has **zero
  callers** anywhere in the app — nothing invokes the pipeline for *any* vendor.
- The **K900** vendor has a `K900StreamManager` (`STREAM_PORT = 8554`) and a `streamMode` flag;
  the **Cyan** vendor has neither.
- `CyanAdapter`'s only video command, `startVideo()`, sends BLE `CMD_START_VIDEO (0x02)` and
  returns a `VideoResult` — i.e. **record to glasses storage**, not a live stream.

So even the app-side capability was written speculatively for the K900 family, not Cyan.

### E. RTMP feasibility verdict
Live streaming — hence live RTMP — is **not available on stock Cyan firmware**; it is
**absent, not merely hidden**. The V821 hardware *is* capable (it already H.264-encodes via
VENC for recording, and has Wi-Fi + Wi-Fi Direct), so the gap is firmware/config. Realistic
paths:

1. **On-glasses firmware mod (the real path).** Root the V821 (UART3 / xfel-FEL — see the
   ownership roadmap) and add a small process that taps the VENC H.264 output (or the
   `ai_glass_video` encode buffer) and either serves it over TCP/RTSP or **pushes RTMP
   directly** over Wi-Fi. A phone/media-server then relays to the CDN — trivially, since the
   stream is already H.264 (`ffmpeg -i <src> -c copy -f flv rtmp://…`, or a **LiveKit egress**
   off the republished track). The only route to true live RTMP from these glasses.
2. **A vendor firmware that enables `supportLiveReview` for AM01CY** — would light up the
   stock RTSP:8554 path, after which RTMP is a pure relay. No evidence such a build exists for
   this model; uncertain.
3. **Non-live (zero firmware work).** Pull recorded clips from `ai_glass_download`'s HTTP
   server and push them to an RTMP/HLS endpoint as VOD. Not live, but available today.

**Correction to any earlier "≈90% there for RTMP" framing:** that conflated the official app's
RTSP code + the app's dormant scaffolding with actual *device* capability. The firmware
evidence (no live server + capability bit off) shows the user's Cyan unit does **not**
live-stream. Methodology note for the portfolio: the load-bearing step was the **ELF
dynamic-symbol role test** (§11.C) — it converted an ambiguous "maybe the stream is
elsewhere" into a definitive "no server exists," which string-grep alone could not.

---

## 12. Session 2026-07-21/22 — FerSaiyan firmware-dump repo, two-time-pad crypto break, JL7018F chip confirmation

This session obtained a multi-version firmware corpus and, with it, **overturned three earlier
conclusions**: (1) the `.bin` is a *cipher* we can break, not a "keyless scrambler"; (2) the
main/BLE chip is a **JieLi JL7018F**, not a "BlueX RF03"; (3) the `.bin` is real multi-processor
firmware, not an opaque model blob. See §12.H for the explicit corrections.

### A. Source: FerSaiyan `HeyCyan-Firmware-Dump` (private repo)
A working RE workspace (~157 MB hydrated). What it gave us:
- **19 firmware `.bin` images across 4 model families** — all container magic `0x81bdc3e5`, 0x50
  header, additive-sum tag:
  | Family | # | Versions |
  |:--|:-:|:--|
  | AM01W | 11 | 2.00.02–2.00.17, 2.20.05–08 |
  | A02E02 | 5 | 3.00.01/03/04, 3.20.01/02 |
  | AM01C | 2 | 2.00.02/03 |
  | AM01G1 | 1 | 9.20.03 |
  - **No `AM01CY` image** — i.e. not our exact SKU (the one thing that would let us decrypt our
    unit directly). We still hold only one real AM01CY file (`2.00.10`); the local `2.00.06`/`2.00.09`
    turned out to be **159-byte HTML error stubs**, not firmware.
- **49-script two-time-pad toolchain** (`crib_drag.py`, `derive_ks_from_ref_pair.py`,
  `xfam_keystream_validate.py`, `pipeline_expand_plaintext.py`), **3,476 recovered keystream
  windows** (157 cross-family *verified*), 171 XOR-pair files, **654 reports** (incl. headless-Ghidra
  RV32 + JieLi `q32s` decompile/anchor reports), and a distilled `AGENTS_ARCHIVE` log.
- Excluded by `.gitignore`: the fully-decrypted reference-union blobs and Ghidra projects
  (regenerable from the scripts).

### B. The cipher is a TWO-TIME PAD (XOR keystream), broken by version-differencing
This corrects §10.B. `C = P ⊕ K`, where the **keystream K is reused across a family's firmware
versions** (a CTR-like construction — recovered keystream shows 16-byte block repeats at `0x40000`
spacing, i.e. AES-CTR with a wrapping counter). The break requires **≥2 versions**, which is why the
single-file analysis in §10 got stuck:
```
C1 = P1 ⊕ K
C2 = P2 ⊕ K   ⇒   C1 ⊕ C2 = P1 ⊕ P2
```
XORing two same-family ciphertexts cancels K and yields `P1⊕P2`; crib-dragging a known plaintext
(Allwinner `eGON.BT0`, `UART_UPDATE_CUSTOM`, version strings) then peels out K and decrypts. We
**reproduced this**: a same-family A02E02 XOR surfaces SoC/boot strings (`_C_AXI_M`, `GPU0`, DDR/UART
register names, incrementing tables). The old `shl1` "keyless scrambler" (§10.B) was a red herring —
a transform that raised chi² on one file without recovering real plaintext; the "no ARM code" finding
was actually a *clue* the ARM/RF03 premise was wrong (see §12.F).

### C. Verified on OUR AM01CY: it is an Allwinner sunxi eGON multi-component image
Applying the cross-family header keystream to `fw_AM01CY_2.00.10` decrypts:
| payload off | AM01CY decrypts to | meaning |
|:--|:--|:--|
| `0x04` | `eGON‥T0` | Allwinner eGON boot magic (middle bytes family-specific) |
| `0x0C` | `0x5F0A6C39` | sunxi `STAMP_VALUE` — exact 32-bit match |
| `0x14` | `0x00000020` | sunxi public-header size — exact |

Two exact 32-bit constants ⇒ not chance. The `LOADER.BIN` scatter-manifest names the bundled
components: `boot0`, `fes` (sunxi), `app.bin`, `LOADER.BIN`, `sh.bin`, `cfg_tool.bin`, `stream.bin`,
JieLi `ble_bb`/`ble_mult`, OTA sub-targets `uart_ota2.bin`/`nor_ota.bin`. **⇒ the `.bin` is a
whole-subsystem, multi-processor package — not a single BLE-chip firmware.**

### D. Keystream is per-family; decryption coverage
Measured (`derive_ks_from_ref_pair` validation): a family's keystream decrypts **its own** 2nd
version at **99.9%** but a *different* family at **1.7–6%**. So:
- **Plaintext is ~99% shared across families** (same SDK/code), but **each SKU has its own keystream.**
- Using the repo's verified keystream we decrypted **~60% of the AM01C sibling** into readable
  plaintext (`heycyan`, `app.bin`, `LOADER.BIN`, `UART_UPDATE_CUSTOM`, `tone_en/*`, HFP AT-stack).
- **Our AM01CY decrypts to 0% directly** — its keystream coincides with the siblings' nowhere useful.
  We can *read the shared code* (≈ our firmware) but cannot decrypt our unit's own bytes without a
  **2nd AM01CY version**. That remains the single blocker.

### E. What the decrypted firmware contains (and wake-word status)
The ~60% we can read is dominated by: **JieLi Bluetooth-audio stack** (HFP `AT+BRSF/BVRA/NREC`,
SBC/AAC, `resample`, `C_AEC` echo-cancel, `tone_en/*` prompts), **Allwinner sunxi boot**, and the
**OTA updater** (`LOADER.BIN`, `dw_update`, SHA-256 manifest). **No obvious neural KWS** (`tflite`/
`onnx`/`kws` are absent from clean plaintext; the earlier `kws`/`asr` counts were XOR-artifact noise).
The always-on "Hey Cyan" detector is therefore either a **JieLi built-in voice-wake feature**
(often strings-less), in the **undecrypted ~40%**, or needs the full AM01CY decrypt. FerSaiyan's
JieLi `q32s` Ghidra decompiles are currently `halt_baddata` (imperfect processor spec) — not yet
readable logic.

### F. CHIP IDENTITY — CONFIRMED (4 independent sources)
| Source | Evidence |
|:--|:--|
| Vendor product specs (W610/X01) | "**main chip JL7018F**, co-processor **Allwinner V821L2**", BT 5.3, dual-mic ENC |
| Decrypted firmware | JieLi `q32s`/`pi32`, `jl_ble_test`, `JL_SPP/HID/HDP`, HFP AT-stack, AC-series codenames; **zero** ARM/Nordic/BlueX |
| Phone-side SDK | `com.jieli.jl_audio_decode`, oudmon `…/spp/jieli/`, `createBondBluetoothJieLi`, `JL7018` ×11 |
| FerSaiyan RE | "Allwinner V821 + JieLi companion (`q32s`)" |

- **JieLi JL7018F** = always-on **main controller + BLE chip** (BLE control + BT-Classic/SPP audio,
  mics/ENC/HFP, power, buttons). The chip the app pairs with; the About screen's `AM01CY`. Firmware =
  the **BLE-OTA `.bin`**.
- **Allwinner V821L2** = on-demand camera/WiFi/Linux co-processor (dual RISC-V: Andes A27 Linux +
  XuanTie E907 AON). Firmware = the **WiFi-OTA `.swu`** (`WIFIAM01CY`).
- **RULED OUT:** *BlueX RF03* (no evidence anywhere — it was analogy from Colmi/QRing rings, never
  confirmed); *Nordic* (the NUS-style GATT UUID `6e40fff0…/6e400002/3` and nRF-style DFU are
  conventions JieLi clones; firmware `nrf` "hits" were XOR garbage).

### G. Two OTA paths, reconciled (dual-brain model)
| | Control brain | Camera/WiFi brain |
|:--|:--|:--|
| Chip | **JieLi JL7018F** (always-on) | **Allwinner V821L2** (on-demand) |
| Version tag | `AM01CY_2.00.10` | `WIFIAM01CY_0.13.14` |
| Update image | `.bin` (~1 MB) | `.swu` (~20 MB) |
| Transport | **Bluetooth LE** | **WiFi (Wi-Fi Direct)** |

Two subsystems, two images, two transports — no conflict. The "updating WiFi" progress the user sees
is the `.swu` waking the V821; its progress is still reported back over BLE (`NOTIFY_OTA 0x04`, §9.B).
Flashing a *sibling* `.bin` (e.g. AM01W) onto AM01CY is **rejected-at-best / brick-at-worst** (model
gating + per-SKU keystream/config; recovery needs UART/FEL) — do not do it.

### H. Corrections to prior sections
- **§1 (Architecture):** add the **JieLi JL7018F** as the always-on main/BLE controller. Prior §1
  modeled only the V821's *internal* A27+E907 AMP and mislabeled BLE/control as an "AM01CY BLE MCU
  (RF03)". Correct model = **JL7018F (main/BLE/audio) + V821L2 (camera/WiFi co-proc, itself A27+E907)**.
- **§10.A/C/D:** "AM01CY (BlueX RF03) … No JL7018" is **wrong** — the CY unit **is** JL7018F-based
  (same JL7018 as the "G1"); CY vs G1 = two SKUs of one JL7018+V821 platform, not different chipsets.
- **§10.B:** "keyless scrambler, NOT encryption; code on RF03 ROM; needs a chip dump" is **wrong** —
  it is a reused-keystream **cipher** (breakable in software with ≥2 versions), and the `.bin` **is**
  the JL7018 firmware, not a model blob. The RF03 memory-map / SWD pinout is void.

### I. Net status & next levers
| Goal | State after this session |
|:--|:--|
| Read the firmware code | ✅ ~60% of the shared (sibling) code decrypted & readable |
| Decrypt OUR AM01CY fully | ❌ blocked on a **2nd AM01CY firmware version** (unlocks the keystream) |
| Locate the wake word | Narrowed to the **JL7018 (JieLi)**; not yet pinpointed in decrypted code |
| Chip identity | ✅ **JL7018F + V821L2**, 4-source confirmed |

**Next levers:** (1) source a 2nd AM01CY version (vendor OTA API / phone `dfu` cache / Reddit
`plated_lead`) → full AM01CY decrypt; (2) expand sibling coverage past 60% via AM01W's 11 versions +
the pipeline; (3) fix the JieLi `q32s` Ghidra spec to decompile the audio/voice region.

**Methodology note (portfolio):** the decisive move was **multi-version differencing** — a cipher
that is "ciphertext-only unbreakable" on one sample collapses to a two-time-pad the moment a second
version exists; and **cross-checking a hardware claim against 4 independent sources** (specs, firmware
strings, phone SDK, third-party RE) is what turned an inherited assumption ("RF03") into a retraction.

## 13. Session 2026-07-24 — AM01CY two-time-pad test (empirical), acquisition recon, doc reconciliation

This session hydrated FerSaiyan's dump on Windows and **quantitatively re-tested** whether our
`test_ota.bin` (= `AM01CY_2.00.10_260411`, provenance re-confirmed byte-identical to
`hardware_research/am01cy_crypto/fw_AM01CY_2.00.10_260411.bin`, sha256 `84b7fe5a…`) can be broken
against the existing corpus. It **confirms §12.C/D empirically** and closes out the remote-acquisition
question. No prior conclusion is overturned; §12 stands.

### A. Cross-family two-time-pad test — AM01CY shares no usable pad with the 19 dumps
Direct measurement (payloads aligned at file `0x50`; scripts in the session scratchpad):
- **XOR `test_ota` against all 20 dumped `.bin` payloads:** identical-byte fraction **2.13–2.49%**
  (best `AM01W_2.20.08` = 2.49%), **flat across all four families** vs a `1/256 = 0.39%` random
  baseline. Elevated but uniform ⇒ shared header islands + zero-runs, **not** a shared keystream.
- **Applied all 3,476 recovered `ks_window` files** to `test_ota` at their offsets: max **65%**
  printable, output *smudged* (`DBF_AWI` where clean plaintext reads `DBG_AXI`) — coincidental
  grazing, zero clean decrypts.
- **Consensus keystream** (majority-vote across families; 52,785 offsets where all agree) applied to
  `test_ota` → **33.5%** printable garbage, no real strings.
- **The mechanism-proving contrast:** two AM01W siblings (`2.00.13` vs `2.20.05`) are **100.00%**
  identical ciphertext (one reused pad — the two-time-pad that broke the other families); `AM01W`
  vs `AM01CY` only **2.32%**. So per-model-line keystream reuse is real, but **AM01CY's pad is its
  own** — consistent with §12.D's "1.7–6% cross-family / 0% direct."
- ⇒ **Reconfirmed:** AM01CY cannot be crib-dragged against this corpus; it needs a **2nd AM01CY_\*
  version** (or a chip dump). Cf. memory `am01cy-needs-sibling-firmware`.

### B. Live acquisition recon — every remote channel for a 2nd AM01CY `.bin` is currently CLOSED
Chasing §12 "next lever (1) source a 2nd AM01CY version":
- **`last-ota` API** — `POST https://www.qlifesnap.com/glasses/app-update/last-ota`, JWT `token`
  header (decoded exp 2026-08-08, valid), body
  `{appId:1,hardwareVersion:"AM01CY_V2.0",romVersion:<v>,os:1,mac,country,dev:2}`. Spoofing an old
  `romVersion` returns the **latest = `AM01CY_2.00.10_260411`** (uploadDate 2026-04-11 17:49:34);
  querying as `2.00.10` **or a very-old version** → `"No upgraded version"` (retCode 60001).
  **Latest-only; no version-history API.** The response also **leaked older build names**:
  `enforceUpdateFrom=AM01CY_2.00.06_251219`, `enforceUpdateTo=AM01CY_2.00.09_260330`.
- **CDN** — pattern `http://api2.qcwxkjvip.com/download/ota/AM01CY_V2.0/<version>.bin`.
  `AM01CY_2.00.10_260411.bin` = **200** (== our file); the leaked older names `2.00.09_260330`,
  `2.00.06_251219`, and the app's hardcoded `2.00.09_260411` all = **404 (purged)**. **CDN keeps
  the latest build only.**
- ⇒ Only viable sibling sources now: **(a)** wait for the next release (2.00.11+) then capture via
  CDN/`last-ota`; **(b)** BLE-OTA sniff / phone `dfu`-cache of a prior `.bin`; **(c)** JL7018 chip
  dump (yields plaintext directly, mooting the two-time-pad).

### C. Corrections / reconciliations to prior docs
- **`docs/05_Hardware_Hacking_Findings/am01cy_ota_crypto_analysis.md` (2026-07-13) was stale** — its
  TL;DR/§1.9/Part 2 "proper cipher → the only break is a chip dump" verdict was an artifact of a
  **single** AM01CY file + within-file tests. §12.B/H already corrected the substance; this session
  added a **superseded banner + a §1.10** to that doc so the file itself is no longer misleading.
  Note the within-file result (`code[i]⊕code[i+P]` flat) is *not* wrong — it just can't see the
  cross-**version** reuse axis, which is where the break lives.
- **`docs/00_Executive_Summaries/ownership_and_wakeword_roadmap.md` §2b** ("AM01CY = BlueX RF03",
  "inner strong AES-class cipher") banner-marked superseded → JL7018F + two-time-pad (§12.F, §12.B).
- Memory `am01cy-bin-crypto` (2026-07-21) is current and matches this session; linked it to
  `am01cy-needs-sibling-firmware` (this session's data).

**Methodology note (portfolio):** a *negative* result, measured properly, is still a finding — the
20-file XOR sweep + 3,476-window application + consensus vote didn't decrypt our unit, but together
they **prove** the block is "no keystream twin in hand," not "cipher too strong." That distinction
(need a *sample*, not a *break*) is what points the next effort at acquisition/hardware rather than
more cryptanalysis.

## 14. Session 2026-07-27 — `.swu` flash-halt root cause, software-only ownership path, wake-word cross-variant check, sibling-flash dead-end

Resumed after a laptop shutdown. This session resolved **why** custom `.swu` OTA bricks, found the **software-only, no-hardware, no-USB** ownership path, and closed the **wake-word-location** and **sibling-flash** questions. The two hard adjudications each used a multi-agent workflow (fan-out of independent investigators → adversarial verifiers → synthesis). **Honesty note:** these are strong *static-analysis + field-evidence* conclusions, **not yet hardware-verified**; every residual unknown is engineered to fail non-destructively.

### A. The 29%/55% `.swu` flash halt = in-place SPI-NOR self-flash contention (corrects §9's truncation framing)
8-agent adjudication, **88/100**, survived 3 adversarial refutation attempts. Mechanism: the running root is a squashfs **demand-paged from `mtdblock5`** on the single PY25Q256 NOR die; `swupdate -e stable,nor` erases that same die (`kernel→mtd3` first); a serial-NOR die cannot service reads mid-erase, so the **first erase livelocks the SoC** before any flash progress → **29%** (b4=0, LED on, no reboot). **55%/LED-off** = the same mechanism later (the `mtd5` erase corrupts the live root + the phone's ~60 s P2P watchdog drops Wi-Fi). **Transport/truncation REFUTED as primary** (it's a real *secondary* daemon bug): the user's decisive field evidence — a **3 MB file fails identically**, and a **completed proxy download still stalls**, and the freeze sits at *exactly* 29% (download done) — cannot be truncation. Full detail: `docs/05_Hardware_Hacking_Findings/swu_flash_halt_root_cause.md`.

### B. Why the `switch_root` pivot is commented out (resolved)
It is bundled **factory/efex conversion tooling, not a safe-OTA pivot**: `switch_root` replaces init and never returns (a preinstall script could not continue to `return 0`), AND it `dd`-zeroes NOR boot0 (makes NOR *unbootable*). It is the mirror of `erase_sdnand_boot0()` (which forces permanent NOR boot). ⇒ the device is **NOR-boot-by-design**, so the vendor's own `-e stable,nor` field OTA self-contends too — this is a latent vendor bug, not a user error. Never re-enable this block; never set `swu_next=reboot`.

### C. Software-only ownership = NOR-free script `.swu` → adb-over-TCP (10-agent workflow)
- **`swupdate` v2019.11 has NO code signing** (no `.sig/RSA/CMS/publickey` strings; invoked with no `-k`) → md5-per-item is the only gate → a self-built `.swu` is accepted. App-side BLE DFU (`DfuHandle`) also does **no** model/version gating.
- A `.swu` whose `stable.nor` group carries a **root script and NO `images:`** writes zero NOR → the contention **cannot fire** → the script runs as root. **Brick-safe by construction.** Prior art: `hardware_research/ghidra_analysis/postinstall_backdoor.sh` (installs adb-over-TCP :5555, writes no partitions, `setsid` activation, always exit 0).
- **Reachability (no USB):** Route 1 = immediate over the live OTA Wi-Fi-Direct P2P link, `adb` **from the phone** (P2P group member); Route 2 = persistent `/etc/init.d/S99zbackdoor` that self-joins the home Wi-Fi as STA → **PC-reachable** `adb connect <lan-ip>:5555` (host `TinaLinux`).
- **2 nodes unverified on-device** (does swupdate run scripts with an empty `images:` list; does `adbd` bind TCP :5555) — **both fail non-destructively**; a **SD-NAND-carrier fallback** (one tiny image → `/dev/mmcblk0p14`, a separate die that reads-while-writes) removes the first unknown. ⚠️ **`build_backdoor_swu.py` DEFAULT mode writes a riscv carrier → `/dev/mtdblock4` (NOR) → WILL brick this NOR-booting unit; use `--scripts-only`.** Full recipe: `docs/00_Executive_Summaries/software_only_ownership_plan.md`.

### D. Wake-word cross-variant check = NEGATIVE everywhere → JieLi built-in DSP feature
Decrypted every decryptable variant (AM01C **60.8%**, AM01G1/AM01W/A02E02) and searched the firmware's own **`DBG_*` module map** + a broad `wake|kws|vad|asr|mfcc|tflite|voice` regex → **ZERO** wake-word evidence in ANY variant. AM01C's module map = `DBG_AEC/BT/AXI/MMC/USB/CTM/CPU3_RD/WR` — **no `DBG_KWS/VAD/ASR/VOICE/WAKE`**. ⇒ leading conclusion: **"Hey Cyan" is a JieLi built-in DSP voice-wake feature** (acoustic model in JieLi mask-ROM/DSP library or a strings-less blob); the firmware only *toggles* it (BLE `0x44`). **Implication:** you likely cannot change the wake *phrase* by patching firmware → the practical custom-wake-word path is **phone-side** (disable stock via `0x44` + a recognizer on the phone). Caveat: ~40% of the `.bin` is still opaque; not conclusive.

### E. Sibling-flash (AM01C→AM01CY) is a dead end
It targets the **JieLi chip, which has no BLE software recovery** — the `.bin` DFU is handled **inline by the running firmware** (no bootloader-mode switch; opcode `0x0F` unused), so a bad flash = **hard brick** (hardware-only recovery). App-side `DfuHandle` does zero model gating (streams any file < 12 MB); device-side gating is unknown. Even ignoring the brick risk: **per-SKU keystream** → an AM01C image likely won't decrypt on AM01CY; hardware-config mismatch would break it even if it booted; the code is **~99% shared** (no new capability); and re-encryption for AM01CY is the *same* crypto blocker relocated. Not viable.

### F. Constraint captured: NO USB on this unit
Charge contacts are power-only → **xfel/USB-FEL and adb-over-USB are unavailable.** The realistic no-USB hardware anti-brick net is a **CH341A/SOIC8 offline NOR backup+restore** (not FEL). Corrected inline across `ownership_and_wakeword_roadmap.md`, `01_Hardware_Architecture.md`, and `ota_flash_debugging_log.md`.

### G. Docs produced / corrected
- **New:** `swu_flash_halt_root_cause.md`, `software_only_ownership_plan.md`, `status_ledger_and_options.md` (neutral findings-by-confidence + options register), `SESSION_RESUME_2026-07-27.md`.
- **Corrected (non-destructive):** this file §9 (append-only annotation), `ota_flash_debugging_log.md`, `ownership_and_wakeword_roadmap.md`, `am01cy_ota_crypto_analysis.md`, `01_Hardware_Architecture.md`, `Cloud_OTA_WakeWord_Implementation_Plan.md`, `docs/README.md`.

**Methodology note (portfolio):** both hard questions were settled by *independent-agent fan-out + adversarial refutation*, not a single pass — and in each case the decisive move was letting the **user's field evidence** overturn a prior static conclusion (the "truncated download" theory fell to "a 3 MB file also fails"). The honest posture throughout: label confidence explicitly, keep the risky chip (JieLi, no recovery) out of the plan, and make every unverified step fail safe.

## 15. Session 2026-08-21 — Onboard storage capacity resolved (4 GB SD-NAND); battery mAh closed; "32 MB storage" misreading corrected

Question posed: *are the hardware specs already answered by our research, and how much onboard storage does the device have?* Answer: **specs yes, storage no** — the capacity was absent from every artifact we hold, and was closed this session from vendor spec sheets cross-validated against the firmware. Two long-standing UNKNOWNs (storage capacity, battery mAh) are now [Inferred, high]; one framing error in `01_Hardware_Architecture.md` is corrected.

### A. The capacity gap was real — it is in NO artifact we hold
Confirmed by exhaustive search across both repos (firmware dumps, DTB, `sw-description`, rootfs, SDK decompiles, vendor APK):
- **No GPT/`sys_partition.fex`/partition-size manifest** exists in the repo. `mbr_offset=311296` in the cmdline is a NOR GPT *offset*, not a capacity.
- **No `df`, no `/proc/partitions`, no capacity string** anywhere. §9's note *"Undetermined (needs on-device shell): exact `/mnt/UDISK` & mtd partition sizes"* was accurate and remains accurate for **byte-level** sizes.
- Only **lower bounds** are derivable from OTA payload sizes: `rootfs_sdnand` 6 MB, `user` (p13/p14) 2 MB each, kernel 3.4 MB, riscv 1.5 MB.

### B. Capacity = **4 GB SD-NAND** (~3.6-3.7 GiB usable) — three sibling SKUs, same reference platform
| SKU | Vendor wording |
|---|---|
| **X01** | "JL7018F6 Bluetooth main chip, Allwinner V821 auxiliary chip, **SD NAND patch 4GB storage memory**" |
| **W610** | "JL7018F (main), Allwinner V821L2 (co-processor)", **4GB** |
| **CY01** ← *our unit* (`CY01_03DC`) | "Main chip CPU: **JL7018F with 4GB flash memory**; Auxiliary Chip: Allwinner V821" |

"SD NAND patch" = 贴片 SD NAND (surface-mount SD-NAND) - **exactly** the separate `mmcblk0` die the DTB and `sw-description` show carrying `/mnt/UDISK` (p15). Marketing and firmware converge independently; that convergence, not the marketing alone, is what makes this [Inferred, **high**] rather than [Unverified]. Note these are the *same* vendor sheets that gave us the JL7018F + V821L2 chip ID in Section 12.F - consistent sourcing.

### C. CORRECTION to Section 12 / `01_Hardware_Architecture.md`: "32 MB" is the SYSTEM NOR, not the storage
The docs stated *"Flash is a single 32 MB SPI-NOR (PY25Q256) plus an SD-NAND die (mmcblk0)"*. Literally true, but it reads as **"the glasses have 32 MB of storage."** They do not. **Two media, two roles:**
- **~32 MB SPI-NOR** → `mtdblock0-8`: boot0/env/bootA/riscv0/rootfsA/isp_param/rootfs_data/UDISK. **System only.**
- **4 GB SD-NAND** → `mmcblk0`, ≥15 partitions, `/mnt/UDISK` = `mmcblk0p15`. **All user media.**
Annotated inline in `01_Hardware_Architecture.md` (2026-07-11 banner) and rewritten in its Section 2.

### D. CORRECTION: the vendor "4GB RAM" label is wrong; RAM is 64 MiB (re-derived)
TVCMALL files the W610's 4 GB under *RAM* ("4GB (32-bit)"). Independently re-derived from the DTB memory node this session: `reg = 0x80000000 / 0x04000000` = **64 MiB @ 0x80000000** — matching the docs' existing figure. The 4 GB is **storage, mislabeled**. Recorded so nobody "corrects" 64 MiB from a marketing sheet later.

### E. Battery closed: **270 mAh / 3.8 V** (was UNKNOWN)
Two independent vendor sources agree — W610 ("270mAh high-density polymer lithium battery") and CY01 ("3.8V/270mAh", ~5 h music at 80% volume, charge ≤2 h). No mAh figure exists in any firmware artifact; this is vendor-sourced only.

### F. Reliability caveat on vendor sheets — stated deliberately
These same listings claim the camera is **"8MP" / "IMX219" / "Sony 918"**, while the DTB (`sensor0_mname = gc05a2_mipi`, `isp_used = 1`) and the RISC-V ISP tuning tables (the *only* tuned configs are `gc05a2_*`) prove **GalaxyCore GC05A2, 5 MP**. Listings that do **not** name the chipset variously claim 16 GB or 32 GB. **Therefore:** trust these sheets only where (a) they name the exact chipset and (b) an independent firmware artifact corroborates. Storage meets both tests; the camera spec fails and is rejected.

### G. Why the app can never show free space today (closes a recurring question)
There is **no BLE opcode carrying byte sizes**. The sole storage query is big-data `{0x02, 0x04}` (ACTION 65); its response frame decodes **three counts only** — `imageCount`/`videoCount`/`recordCount` (+ `configFileType` byte 14 in the newer SDK). Ref `GlassModelControlResponse.java:31-35`. Downstream:
- `CyanAdapter.kt` hardcodes `totalSize/freeSize/usedSize = 0L`.
- **iOS defect:** `QCSDKCmdCreator.h:51-55` documents the 4th callback arg as `totalSize`; it is actually byte 14 = `configFileType`. `CyanAdapter.swift:430-436` stores that enum into `totalSize` — a meaningless value that must not be displayed.
- Symptoms in the product: `storage-info.tsx` renders `0.00G` and a `width:"NaN%"` bar (0/0); the "N GB FREE" chip is gated on `freeSize > 0` so it silently never renders.
- Only real signals available: **`NOTIFY_MEMORY_LOW = 0x0e`** (boolean, currently unhandled) and summing `Content-Length` per file over the Wi-Fi HTTP manifest (which is a plain-text filename list with **no** sizes — `CyanFileSyncManager.kt:565-595`). The vendor's own app shows no numeric storage either, only "Memory full!".

### H. What would make this [Confirmed]
One command in the **UART3** root shell (option J in the status ledger — **not** xfel/FEL, which is void on this unit: no USB data path, Section 14.F):
```sh
cat /sys/block/mmcblk0/size   # sectors x 512 → expect ~3.9-4.0e9 bytes raw
cat /proc/partitions ; cat /proc/mtd ; df -h /mnt/UDISK
```
This also settles the PY25Q256 assertion and every per-partition size in the same read. **No extra work** — it falls out of a session already planned for wake-word ownership.

**Methodology note (portfolio):** the useful move here was recognising that an *absent* number is not always an unanswerable one. Static analysis had genuinely exhausted the artifacts, so the next-cheapest evidence was external (vendor sheets) — but external evidence is only worth quoting when an internal artifact corroborates its *mechanism*. "SD NAND patch 4GB" was credible precisely because our own DTB independently shows a separate SD-NAND die doing exactly that job; the same sheets' camera claim had no such corroboration and was rejected. Cheap sources are usable **if** you state what they'd have to get right to be trusted, and check that specific thing.
