# Hardware Architecture

Based on the firmware and software artifacts, the hardware architecture of the smart glasses relies on a highly efficient asymmetric multi-processor design with various external peripherals.

> ## ⚠️ 2026-07-27 — Chip-ID supersedes ALL banners below
> The BLE/control/audio MCU is a **JieLi JL7018F** (+ Allwinner **V821L2**), 4-source confirmed
> (`cyan_reverse_engineering.md` §12.F). Every "**Nordic nRF-class**" (2026-07-11 banner) and
> "**BlueX RF03 / Cortex-M0+**" (2026-07-16 banner) label below is **RETRACTED** — kept for history.
> The `.bin` (`AM01CY_2.00.10`) **is the JL7018 firmware** (JieLi `q32s`), not an opaque blob needing
> an on-chip-ROM dump; it is partially decryptable in software. The 2026-07-16 banner's "**NOT
> encrypted / keyless `shl1` scrambler**" claim is likewise **retracted** — it's a **two-time-pad**
> XOR-keystream cipher, decryptable with a 2nd AM01CY version. The "Hey Cyan" wake word is on the
> JL7018 but is **not in the flashable firmware of any decrypted variant** (module map has no
> `DBG_KWS/VAD/ASR`) → most likely a **JieLi built-in DSP voice-wake** the firmware only toggles, so a
> custom *phrase* probably can't be done by firmware patching (phone-side recognizer is the path).
> **No USB on this unit** (charge contacts power-only) → xfel/FEL & adb-over-USB unavailable; the
> no-USB anti-brick net is a CH341A/SOIC8 offline NOR reflash. Current neutral snapshot:
> `docs/00_Executive_Summaries/status_ledger_and_options.md`.

> ## ⚠️ 2026-07-11 — Corrections
> - There is a **third processor**: a separate **AM01CY BLE/control MCU** (Nordic
>   nRF-class, encrypted firmware) that terminates the app BLE link and — per converging
>   evidence — **hosts the "Hey Cyan" KWS**. §1's claim that the E907/RISC-V "statically
>   runs the KWS" is **refuted** (no `TFL3` / audio-MMIO / NN symbols in the `riscv`
>   image; it does camera ISP + AON housekeeping).
> - SoC cores are Andes **A27L2** (Linux) + XuanTie **E907** (AON sensor/ISP + Wi-Fi PHY), 64 MB DDR2.
>   Flash is a single **32 MB SPI-NOR (PY25Q256)** (mtd0/3/4/5) **plus** an SD-NAND die
>   (mmcblk0) — not NAND-only.
>   ⚠️ *2026-08-21: the SD-NAND die is **4 GB** (~3.6–3.7 GiB usable at `/mnt/UDISK`) — see §2.
>   The 32 MB NOR is the **system** flash only; it is **NOT** the glasses' storage capacity.*
> - Debug/ownership: **UART3** (PL2/PL3, 115200) → unauthenticated root shell;
>   **xfel/FEL** (V821 officially supported) → unbrickable NOR access; no secure-boot.
>   ⚠️ *2026-07-27: 'unbrickable NOR access' via xfel/FEL assumes USB0 D+/D- is reachable —
>   **UNCONFIRMED** (charge contacts power-only; user reports no USB). The confirmed no-USB
>   anti-brick path is a **CH341A/SOIC8 offline NOR backup+restore**; UART3 gives root without USB.*
>   See **`docs/00_Executive_Summaries/ownership_and_wakeword_roadmap.md`**.

> ## ✅ 2026-07-16 — Device CONFIRMED + corrections (via the official-app About screen)
> The user's physical unit is the **"CY" variant**: BT name `CY01_03DC`, HW `AM01CY_V2.0`,
> SW `2.00.10_260411` (= the `test_ota.bin` we hold), WiFi HW `WIFIAM01CY_V2.0`, MAC
> `66:C6:66:D9:03:DC`. **Two physical chips: Allwinner V821 (wifi/camera/Linux, dual-core
> A27+E907) + AM01CY BLE/control MCU. No JL7018** (that is the separate "G1" variant —
> `AM01G1_V9.2` — which the Reddit RE community targets). Corrections to this doc:
> - The AM01CY MCU is a **BlueX RF03 (Cortex-M0+)**, *not* "Nordic nRF-class".
> - Its OTA `.bin` is **NOT encrypted** — it is a **keyless `shl1` scrambler** over a
>   custom-format **data/model blob** (fully descramble+re-scramble round-trippable; the
>   detection *code* is in RF03 ROM). See `docs/06_Reference_Materials/cyan_reverse_engineering.md` §10.
> - **Wake word confirmed on the AM01CY** (not "UNVERIFIED"): the entire plaintext V821
>   firmware (Linux daemons + `aglink` IPC + RISC-V) was software-verified to contain **no**
>   KWS/voice-wake logic. "Hey Cyan" is an **optional** always-on voice mode; the **primary
>   activation is the right-rear button**. A custom wake phrase needs the RF03 chip dump.

## 1. Processing Units & Architecture (Asymmetric Multi-Processing)
- **Main System-on-Chip (SoC)**: **Allwinner V821**
  - **OS**: Runs Allwinner Tina Linux (an OpenWrt-based embedded Linux) with `musl` libc and kernel 5.4.220.
  - **Role**: Handles heavy-lifting tasks like Wi-Fi/Bluetooth networking, camera processing, and running the `ai_glass_audio` daemon to negotiate audio streams with the phone.
- **Sensor Coprocessor**: **Xuantie-900 (T-Head E907)** 32-bit RISC-V Core.
  - **OS**: Runs FreeRTOS (unknown minor version), built with the **Xuantie-900 bare-metal newlib GCC 10.4.0** toolchain (10.4.0 is the GCC version — **not** `riscv32-linux-musl`; `grep -c musl` = 0 in the `riscv` image).
  - **Role**: Operates as an Always-On (AON) sensor/ISP hub. It handles camera ISP + AON housekeeping and communicates with the Linux core via `rpbuf` (OpenAMP). **It does NOT run the "Hey Cyan" KWS** (no `TFL3`/NN/MFCC/tensor symbols, no audio MMIO in the `riscv` image); per the top banner the KWS most plausibly lives on the encrypted AM01CY BLE MCU (**UNVERIFIED — needs hardware/dynamic test**).

## 2. Memory & Storage

> ### ⚠️ Read this first: there are **TWO** flash media. "32 MB" is NOT the storage capacity.
> The often-quoted **32 MB SPI-NOR** is the *system* flash (bootloader/kernel/riscv/rootfs).
> **User media lives on a completely separate 4 GB SD-NAND die.** Do not conflate them.

| | **System flash** | **Media storage** |
|---|---|---|
| Part | SPI-NOR, **~32 MB** (PY25Q256) | **SD-NAND, 4 GB** (surface-mount die) |
| Linux device | `mtdblock0`–`mtdblock8` | `mmcblk0` (≥15 partitions) |
| Holds | `boot0 / env / env-redund / bootA / riscv0 / rootfsA / isp_param / rootfs_data / UDISK` | `kernel_sdnand`(p4), `riscv_sdnand`(p7), `rootfs_sdnand`(p9), `user`(p13/p14), **`/mnt/UDISK`(p15)** |
| Capacity confidence | **[Inferred]** — asserted in docs; the 32 MB / PY25Q256 part number is *not* re-derivable from any repo artifact | **[Inferred, high]** — 3 sibling SKUs on the same chipset (below) + matches the firmware's separate `mmcblk0` die |

- **Media capacity**: **4 GB SD-NAND ⇒ ~3.6–3.7 GiB usable** at `/mnt/UDISK` after system partitions (~15–20 MB). Photos/videos/audio recorded by `ai_glass_video` land here and are served over HTTP during Wi-Fi transfer mode.
- **RAM**: **64 MiB** — **[Confirmed]** directly from the DTB memory node (`reg = 0x80000000 / 0x04000000` = 64 MiB @ `0x80000000`) in `hardware_research/firmware_and_os/extracted_fw/extracted.dtb`. The "DDR2" type label is asserted in docs, not independently proven.
  - ⚠️ Some vendor listings file the **4 GB under "RAM"** (e.g. TVCMALL's W610 page: *"4GB (32-bit)"*). **That is a mislabel** — the DTB proves 64 MiB. The 4 GB is storage. Do not "correct" the 64 MiB figure from a marketing sheet.
- **Filesystems**: rootfs = **squashfs** (`rootfstype=squashfs` in the DTB kernel cmdline); `/mnt/UDISK` mounts ext4/jffs2/ubifs depending on variant (`rootfs_extracted/etc/init.d/rcS:62-83`).
- **Memory Maps**: The RISC-V coprocessor executes from **reserved DDR at `0x80860000`** (entry `0x80862e00`), **not** internal SRAM. DTB carve-outs: `e907_mem_fw@82E00000` (~2.4 MB) and `isp_dram@8305B000`.
- **NOR MTD layout** (from the DTB kernel cmdline, verbatim):
  ```
  root=/dev/mtdblock5 ... partitions=env@mtdblock1:env-redund@mtdblock2:bootA@mtdblock3:
  riscv0@mtdblock4:rootfsA@mtdblock5:isp_param@mtdblock6:rootfs_data@mtdblock7:UDISK@mtdblock8
  ... androidboot.hardware=sun300iw1p1 boot_type=3 gpt=1 rootfstype=squashfs mbr_offset=311296
  ```

### 2.1 Sourcing for the 4 GB figure
No byte-level capacity exists anywhere in the firmware, the SDK, or the vendor APK — the number comes from vendor spec sheets for three sibling SKUs built on the **same JL7018F + Allwinner V821 reference platform**:

| SKU | Vendor wording |
|---|---|
| **X01** | "JL7018F6 Bluetooth main chip, Allwinner V821 auxiliary chip, **SD NAND patch 4GB storage memory**" |
| **W610** | "JL7018F (main), Allwinner V821L2 (co-processor)", **4GB** |
| **CY01** ← *our unit* (`CY01_03DC`) | "Main chip CPU: **JL7018F with 4GB flash memory**; Auxiliary Chip: Allwinner V821" |

"SD NAND patch" = 贴片 SD NAND (surface-mount SD-NAND) — precisely the separate `mmcblk0` die the DTB and `sw-description` show carrying `/mnt/UDISK`. Marketing and firmware agree independently.

**Reliability caveat — treat as strong convergent evidence, not ground truth.** Listings that do *not* name the chipset claim 16 GB or 32 GB (different SKUs or inflation). Worse, these same sheets get the camera demonstrably wrong — they claim "IMX219" / "Sony 918" / "8MP" while the DTB proves **GC05A2, 5 MP**. Vendor sheets on this platform are sloppy.

**To confirm definitively**, one command in the UART3 root shell (see §J of `status_ledger_and_options.md`; note **xfel/FEL is unavailable — no USB data path on this unit**):
```sh
cat /sys/block/mmcblk0/size   # sectors × 512 → expect ~3.9–4.0 × 10⁹ bytes raw
cat /proc/partitions ; cat /proc/mtd ; df -h /mnt/UDISK
```

### 2.2 Storage capacity is NOT reportable over BLE
There is **no opcode that returns byte sizes**. The only storage query is big-data `{0x02, 0x04}` (ACTION 65), whose response frame carries **three counts only** — `imageCount`, `videoCount`, `recordCount` (+ a `configFileType` byte in the newer SDK). See `GlassModelControlResponse.java:31-35`. Consequences:
- `CyanAdapter.kt` hardcodes `totalSize = 0L, freeSize = 0L, usedSize = 0L`.
- ⚠️ **iOS bug**: `QCSDKCmdCreator.h:51-55` documents the 4th callback arg as `totalSize`; it is actually byte 14 = **`configFileType`**. `CyanAdapter.swift:430-436` stores that enum into `totalSize` — it is **not** a capacity and must not be displayed.
- The only device-side storage signal is **`NOTIFY_MEMORY_LOW = 0x0e`**, a boolean "memory low" push (currently unhandled). The vendor's own app likewise shows no numeric storage — only *"Memory full!"* strings.
- The Wi-Fi media manifest is a **plain-text filename list with no sizes** (`CyanFileSyncManager.kt:565-595`); real used-bytes would require a `HEAD` per file for `Content-Length`.

## 3. Wireless Connectivity
- **Wi-Fi & Bluetooth**: Operated by the integrated **Allwinner V821** RF chipset. 
  - Firmware blobs: `fw_mac_v821.bin`, `boot_v821.bin`, and `sdd_v821.bin`.
  - Driven by the `v821_smac.ko` kernel module.
  - Supports BLE for data/app communication.
  - **HYPOTHESIS (UNVERIFIED — needs hardware/dynamic test):** the older claim of Classic Bluetooth A2DP/SCO audio routing is **not supported by the artifacts** — there is **no BT-classic A2DP/SCO audio code in the app**, and mic/TTS audio instead goes over **BLE large-data to Agora RTC / Azure**. Treat any BT-classic audio claim as UNVERIFIED at best.

## 4. Peripherals & Inputs
- **Microphone**: On-device microphone(s) for voice capture. The earlier claim that the mic is "monitored constantly by the RISC-V `wakeup buffer`" is **refuted** for the E907 (no audio MMIO / KWS in the `riscv` image, see §1); always-on wake-word buffering, if present, most plausibly sits on the encrypted AM01CY BLE MCU (**UNVERIFIED — needs hardware/dynamic test**).
- **Hardware IO / Touch**: The glasses expose capacitive touch / physical button input on the temple. **HYPOTHESIS (UNVERIFIED — needs hardware/dynamic test):** the earlier "up to 5 distinct Hardware IO pins" figure was inferred from a single Ghidra decompiler bound check (`if (uVar3 < 5)`) and should not be treated as a confirmed pin count — the specific loop/bound has not been tied to a touch-scan routine.
- **LED Indicators**: The system features up to 2 status LEDs (`status_led` and `status_led2`). These are managed by the Linux `/sys/class/leds` subsystem via `/lib/functions/leds.sh` and support solid, heartbeat, blinking, and "morse code" patterns.
- **Camera / Image Signal Processor (ISP)**: The firmware contains the `libisp-dev` library and logging strings like `[ISP_WARN]MSC 22x22 mode don't support OTP calibration`. This confirms an onboard camera sensor, with the V821 chip utilizing a hardware ISP to process raw image data. The V821 also has a **hardware H.264 video encoder (VENC, via the CedarX / `librt_media.so` stack)**, used by the `ai_glass_video` daemon for on-device **recording**. Note: the firmware exposes **no live video-streaming server** (record-to-file → HTTP download only) — see `02_Firmware_and_OS.md` §2.1 and `06_Reference_Materials/cyan_reverse_engineering.md` §11; live streaming / RTMP would require a firmware modification.
- **Inertial Sensors (IMU/Gyroscope)**: The device tree (DTB) declares a **gyroscope (`gyro-aglink`)** that is **serviced by the E907 AON core**, not by a dynamically loaded Linux `.ko`. This is consistent with the E907 acting as the AON sensor/ISP hub: the gyro is read on the always-on side rather than through a user-space Linux driver.
- **Speaker**: On-device speaker for audio output. The routing path is **UNVERIFIED — needs hardware/dynamic test**; the "standard (Classic) Bluetooth routing" assumption is not supported by the artifacts (no BT-classic A2DP/SCO audio code — TTS/playback audio arrives over BLE large-data from Agora RTC / Azure).

- **Camera sensor (identified)**: **GalaxyCore GC05A2, 5 MP, MIPI, rear/world-facing** — the DTB node `sensor@5812000` sets `sensor0_mname = gc05a2_mipi`, `sensor0_pos = rear`, `sensor0_isp_used = 1` (TWI `0x6f`). It is the **only** sensor with tuned ISP tables in the RISC-V image, which name the supported modes: `gc05a2_mipi_isp603_500w_30fps_rgb` (5 MP/30fps), `..._1080p_30fps_rgb`, `..._2k_day/night_reg_2in1_v821` (2K), `..._720p_day_reg_2in1_v821`, and one 60 fps mode. Two other DTB sensor nodes exist — `gc1084_mipi_2` (front, `isp_used=1`) and `ov5647` (front, `isp_used=0`) — plus a compiled-in unused driver list (`sc035hgs/gc2053/gc4663`); these are most likely **carried-over Allwinner EVB defaults**, not fitted parts.
  - ⚠️ Vendor listings claim **"8MP"**, "8MP interpolated to 32MP", "IMX219", or "Sony 918". The DTB and the ISP tuning tables both say **GC05A2 / 5 MP**. Trust the firmware; treat the marketing camera spec as wrong.
- **Battery**: **270 mAh, 3.8 V** high-density polymer lithium — **[Inferred]**, two independent vendor sources (W610 listing: *"270mAh high-density polymer lithium battery"*; CY01 listing: *"3.8V/270mAh"*, ~5 h continuous music at 80% volume, charge ≤2 h). No mAh figure exists in any firmware artifact. Charge level (0-100%) is reported over BLE via `syncBattery` / `NOTIFY 0x05`. Charging is magnetic; the contacts are **power-only (no USB data path)**.

## 5. Hardware Revisions
The companion app code (`AIWakeUpActivity.java`) reveals there are multiple hardware builds/variants in existence, designated by internal codenames:
- `AO3`
- `AM01`
- `KEY40`, `KEY41`, `KEY42`, `KEY43`
- `KEY31`, `KEY21`, `KEY22`, `KEY23`
