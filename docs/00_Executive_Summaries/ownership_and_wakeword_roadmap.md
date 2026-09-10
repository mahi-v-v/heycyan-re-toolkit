# Cyan Glasses — Ownership & Wake-Word Roadmap (Master Findings)

**Last updated: 2026-07-11.** This is the durable capture of everything learned across
the multi-agent investigations. It supersedes the earlier working assumption that the
"Hey Cyan" wake word lives in the `riscv` coprocessor image. Companion docs:
`docs/06_Reference_Materials/cyan_reverse_engineering.md`,
`docs/05_Hardware_Hacking_Findings/ota_flash_debugging_log.md`, and the rest of the `docs/` tree.

Raw workflow outputs (ephemeral scratch, may be cleared):
- OTA-failure diagnosis: `…/tasks/w2y5suxfk.output`
- Ownership+wake-word roadmap: `…/tasks/wzdd9j1f0.output`
(Distilled fully below so nothing is lost if those are cleared.)

> ### ✅ 2026-07-16 update — device confirmed; AM01CY `.bin` decoded (not encrypted); wake word localized
> ⚠️ **PARTLY SUPERSEDED (2026-07-27):** chip = **JieLi JL7018F** (not "No JL7018" / "BlueX RF03"); the
> `.bin` **IS encrypted** — a **two-time-pad**, not a "keyless `shl1` scrambler" (decryptable with a
> **2nd AM01CY version**, no chip dump/ROM needed); and the wake word, while on the JL7018 side, is
> **not in the flashable firmware** (likely a JieLi built-in DSP feature) → a custom phrase likely needs
> a phone-side recognizer. See §2b + `status_ledger_and_options.md`. The block below is kept for history.
> Full detail: `docs/06_Reference_Materials/cyan_reverse_engineering.md` §10 +
> `docs/05_Hardware_Hacking_Findings/am01cy_ota_crypto_analysis.md`.
> - **Device CONFIRMED (official-app About screen)** = **"CY" variant**: `AM01CY_V2.0` /
>   SW `2.00.10_260411` (= our `test_ota.bin`, provenance proven) + `WIFIAM01CY_V2.0` (V821);
>   MAC `66:C6:66:D9:03:DC`. **No JL7018** — that is the separate "G1" variant (`AM01G1_V9.2`)
>   the Reddit RE folks (FerSaiyan et al.) target; their crack does **not** apply here.
> - **G1 refined:** the AM01CY `.bin` is **NOT encrypted** — a **keyless `shl1` scrambler**
>   over a custom-format **data/model blob** (we can descramble **and** re-scramble+re-checksum →
>   valid flashable `.bin`). But it holds **no code** (no ARM, no compression, no cipher, no
>   standard model magic) → the interpreting **code is in RF03 ROM**. So a custom wake phrase is
>   **software-exhausted**; the definitive route is the **RF03 SWD/UART dump** (no readout
>   protection — `am01cy-rf03-dump-path`), which yields the code+model in one read.
> - **Wake word now CONFIRMED on the AM01CY (not just inferred):** the entire plaintext V821
>   firmware (Linux daemons + `aglink` IPC + RISC-V) is software-verified to contain **no** KWS.
>   "Hey Cyan" is an **optional** always-on voice mode; **primary activation = right-rear button**.
> - **AM01CY MCU = BlueX RF03 (Cortex-M0+)**, SRAM `0x00100000` / flash `0x00800000`.

---

## 0. TL;DR — the two goals now diverge sharply

- **G1 — change the wake word to a custom phrase:** HARD on-device / the original
  premise is **REFUTED**. The KWS model is **not** in the E907 `riscv` image we
  patched. It almost certainly lives on the **encrypted AM01CY BLE MCU**. The only
  no-hardware win is a **phone-side recognizer** (disable stock wake via BLE `0x44`,
  run a keyword model on the phone). A true on-device custom phrase needs hardware
  access to the encrypted MCU (SWD RAM dump) or the vendor key.
- **G2 — full persistent ownership (root + custom firmware):** **ACHIEVABLE.** The
  device has no secure boot / no dm-verity; the only integrity gate is a CPIO md5.
  One hardware foothold (the internal **UART3** pads → unauthenticated root shell)
  unlocks almost everything; **xfel/FEL** gives an unbrickable NOR backbone (⚠️ *2026-07-27:
  xfel/USB-FEL requires USB0 D+/D- reachability, which is **UNCONFIRMED** on this unit — §8 #3;
  the user reports **no USB data connection**. The confirmed no-USB anti-brick net is a
  **CH341A + SOIC8** full-chip NOR backup+restore*).

---

## 1. Corrected hardware architecture

Allwinner **V821** SoC (RISC-V), on Tina Linux 5.0 (OpenWrt-based, musl), 64 MB DDR2:
- **Main core:** Andes **A27L2** (rv32, runs the Linux userland — `ai_glass_ota`,
  `swupdate`, etc. are RV32 Andes musl binaries).
- **AON coprocessor:** XuanTie **E907** (Wi-Fi PHY + always-on housekeeping). Runs
  from reserved DRAM, **boot0/SPL-loaded in remoteproc ATTACH mode** ("Wait master
  update resource_table"; DTB auto-boot + skip-shutdown) — Linux never
  `request_firmware()`s it, so `firmware_class.path` overrides are a NON-path.
- **Flash:** a single **32 MB SPI-NOR (PY25Q256)** carrying ALL of `mtd0/3/4/5`
  (boot0/kernel/riscv/rootfs) + a separate **SD-NAND** die (`mmcblk0…`) for the
  sdnand system + `/mnt/UDISK` (user data).
- **Separate BLE/control MCU: `AM01CY`** — a separate BLE/control MCU with an
  encrypted firmware and a custom `0xBC` DFU scheme (no Nordic/nRF/Softdevice strings
  present). Terminates the app's BLE GATT link, and — **by inference, not confirmed** —
  is the most plausible **host of the "Hey Cyan" KWS**. Its firmware (`firmware.bin`)
  body is **encrypted** (~7.98 bits/byte).

Open near-twin for pinouts/schematics: **AvaotaF1** (`AvaotaSBC/AvaotaF1`), same V821.

> ⚠️ The older docs (`docs/01_Hardware_Architecture.md`,
> `docs/05_Hardware_Hacking_Findings/hardware_hacking_log.md`,
> `docs/06_Reference_Materials/cyan_reverse_engineering.md §1–4`) say the E907/RISC-V "statically runs the KWS."
> That is now **refuted** — see §2.

---

## 2. G1 — the wake word is NOT in the `riscv` image (mission-premise correction)

Adversarial re-analysis of `riscv` / `riscv_sdnand`:
- **Zero `TFL3`** (TFLite-Micro flatbuffer magic) and zero `min_runtime_version` in
  either image; the two are byte-identical in size (1,593,684) — differ only by a
  ~9-byte version string.
- **No audio MMIO** (0x42030000 / 0x42032000 absent) and **no MFCC / mel / FFT / NN
  symbols** (the image is NOT stripped, so they would appear).
- The three density-scanned "candidate model" regions (`0x11ba38 / 0x138238 /
  0x154638`) are **int16 ISP gamma/DRC lookup tables** (camera tuning; the regions
  contain negative values) — NOT neural-net weights. **`patch_riscv.py` zeroes ~91,816
  bytes of camera ISP tables** and would break the camera, not the wake word.

**Consequence:** the E907 splice, the SD-NAND `riscv` patch, and `firmware_class.path`
are all **dead ends for G1**. The wake word almost certainly runs on the **encrypted
AM01CY MCU**, which owns `aiVoiceWake` (BLE opcode `0x44`).

**Not yet hardware-confirmed.** Final proof = teardown: mic-net continuity (which chip
the mic MICP/MICN reaches; does AM01CY have its own PDM mic) + logic-analyze the
AM01CY↔V821 link + `sunxi_wupio` wake GPIO at the instant of "Hey Cyan". A wake edge
originating from AM01CY confirms it.

### G1 realistic paths
- **Phone-side custom phrase (software, no hardware — recommended first win):** send
  `aiVoiceWake {2,0}` (BLE `0x44`) to silence stock "Hey Cyan", stream the glasses
  mic to the phone, run a keyword recognizer (Microsoft `KeywordRecognizer` is already
  bundled; or openWakeWord / **microWakeWord** `github.com/kahrendt/microWakeWord`)
  trained on the new phrase, and drive the existing assistant flow. Trade-off: not
  always-on / low-power; needs the link up.
- **On-device custom phrase (hardware, now VERY feasible — see §2b):** localize the
  detector → AM01CY **SWD/UART dump** to recover the decrypted model + feature pipeline
  → retrain → re-wrap + re-encrypt → push via BLE DFU (or write flash over SWD). The
  earlier *"BLOCKED if APPROTECT-class readback protection"* fear is **RESOLVED**: the
  AM01CY is a **BlueX RF03 with no readback protection** (§2b).

---

## 2b. 2026-07-11 — AM01CY = BlueX RF03: `.bin` crypto structure + the open dump path

> ### ⚠️ SUPERSEDED 2026-07-21/24 — chip is JieLi JL7018F, `.bin` is a two-time-pad
> This section's two load-bearing claims are **retracted** (detail: `docs/06_Reference_Materials/cyan_reverse_engineering.md`
> §12 + §13; `docs/05_Hardware_Hacking_Findings/am01cy_ota_crypto_analysis.md` §1.10; memories
> `am01cy-bin-crypto`, `am01cy-needs-sibling-firmware`):
> - **Silicon:** the control/BLE chip is a **JieLi JL7018F** (+ Allwinner V821L2 co-proc), 4-source
>   confirmed — **not** a BlueX RF03 (that was an unconfirmed analogy from the Colmi/QRing rings). The
>   RF03 SWD pinout / memory map / "no readout protection" below are **void**; re-derive for JL7018F.
> - **Crypto:** the `.bin` is **not** a strong "inner AES-class cipher over compressed data." It is a
>   **two-time-pad** (`C = P ⊕ K`, keystream reused across versions) wrapping an **Allwinner sunxi eGON
>   multi-component image**. Breakable in software with a **2nd AM01CY `.bin`** — no chip dump needed
>   to decrypt this image. Our unit is blocked only because no 2nd AM01CY version is currently
>   obtainable (all remote channels 404/latest-only as of 2026-07-24).
>
> Kept verbatim below for history.

**Silicon ID** (verified via atc1441's RE of the identical sibling Colmi/QRing ring +
the BlueX datasheet DS-RF03-01 V3.2): the AM01CY MCU is a **BlueX RF03** family ARM
Cortex-M0+.

**The RF03 has NO security to defeat** — no readout protection, no secure boot, no
debug-lock fuse (only a runtime MPU). Three unauthenticated read channels:
- **SWD** — P00 = SWCLK, P01 = SWDIO (standard CoreSight, unlocked).
- **UART2AHB backdoor** — P12/P13; reads all memory + registers *without the CPU*.
- **UART0 ROM ISP** — strap P16 high, 115200; boot a custom reader stub.

atc1441 published full **ROM (0x0, 128 KB) + flash (0x800000, 512 KB)** dumps of the
identical chip → the read path is proven. Dumping the AM01CY yields **both the decrypted
firmware and the on-chip OTA AES key in one read**, using pyOCD/OpenOCD (ST-Link/J-Link)
or atc1441's ~$5 `ESP32_nRF52_SWD`. **No cryptanalysis, no glitching.** Only unknown: SWD
is proven open on the ring, untested on the glasses (fallbacks: connect-under-reset /
UART2AHB / forced UART0 boot).

**`firmware.bin` crypto** (reversed this session; tool `hardware_research/am01cy_crypto/descramble_xor.py`):
- Container: 0x50 plaintext header {magic `e5c3bd81`; payload size `0x000fb3a1` ×2;
  checksum `0x07cfde37` @0x0c = **plain 32-bit additive byte-sum of the payload**,
  verified exact (no CRC/MAC/signature → trivial to forge); version strings; no IV}.
- Payload = **two layers**. OUTER (broken): self-sync linear scrambler, descramble
  `p[i] = c[i] ^ ((c[i-1]<<1)&0xFF)` (entropy 7.9994→7.8969). INNER (unbroken): a strong
  ~16-byte-block AES-class cipher; key on-MCU; **not** breakable from one file.
- Key is **not** in the app / V821 / E907 — qcwxfactory-private; the public BlueX SDK
  builds PLAINTEXT OTA (the sibling ring ships plaintext). So the ONLY blocker to a
  custom-firmware wake word is the inner key → get it from the chip dump above.
- **2-version XOR test blocked**: only `AM01CY_2.00.10` is network-obtainable (last-ota
  returns only latest; CDN `api2.qcwxkjvip.com/download/ota/AM01CY_V2.0/<ver>.bin` keeps
  only latest, historical = 404; no alt HW rev; no version-history API).

**Three-chip stack** (teardown-level): BlueX RF03 (AM01CY) + Allwinner V821L2 (RISC-V
camera, Tina Linux, plaintext `.swu`) + **Jieli JL7018F6** (BT-audio SoC, "main
controller" — NEW; re-examine whether it/the V821 gates voice activation).

**Key repos:** `atc1441/ATC_RF03_Ring` (RF03 dumps, BlueX SDK, datasheet, `ATC_RF03_Writer`
0xBC DFU flasher, cert-unpinned QRing APK); **`FerSaiyan/Alternative-HeyCyan-App-and-SDK`**
(glasses alt-app "CyanBridge" w/ local-ASR wake-word bypass + vendor SDK `.aar` +
`parse_swu.py`); `gitee.com/BXMicro/SDK3` (buildable BlueX SDK C source); `atc1441/ESP32_nRF52_SWD`
($5 SWD reader).

---

## 3. Wake-word BLE control (`aiVoiceWake`, opcode 0x44) — implemented

`LargeDataHandler.aiVoiceWake(boolean write, boolean on, cb)` — opcode **68 = 0x44**:
- **Disable:** `aiVoiceWake(true, false)` → payload `{2, 0}`
- **Enable:** `aiVoiceWake(true, true)` → payload `{2, 1}`
- **Query:** `aiVoiceWake(false, false)` → payload `{1, 0}`
- It is **on/off only — never a phrase.** Same get/set shape as `wearCheck`, so it's a
  device setting that likely persists across reboots (needs a real test to confirm).

**Wired into `mobile-app`** (this repo):
- `…/vendors/cyan/CyanAdapter.kt` `sendCommand` handles `"setVoiceWake"` / `"getVoiceWake"`
  → `LargeDataHandler.getInstance().aiVoiceWake(...)`; ACK posted to the OTA-screen log
  as `[WAKE] …`.
- `modules/sdk/src/native/Glass.ts`: `Glass.setWakeWord(enabled)` / `Glass.getWakeWord()`.
- `app/(tabs)/ota.tsx`: **Wake Word Control** section (Disable / Enable / Query buttons).
- Build risk: verify `aiVoiceWake` exists in the bundled `cyan_sdk.aar` (same SDK class
  the adapter already calls). If Gradle errors "unresolved reference: aiVoiceWake", the
  AAR version differs.

---

## 4. The OTA flash failure — root cause (why every `.swu` flash reset at 29/55%)

**In-place SPI-NOR flashing resets the SoC.** All partitions live on ONE NOR die; the
live root is a squashfs demand-paged from `mtdblock5` on that same die. When swupdate
erases/writes any partition (even a 3 MB `riscv`-only image → `mtd4`), the die is busy,
the running OS can't page → **whole-SoC reset**. Payload-independent; the blue LED is a
whole-SoC status LED (AMP-driven; the "PWM ch7" attribution is UNVERIFIED — needs
hardware) so **LED-off = the SoC reset**; "restart from 0" = reboot.

- Secondary suspect: power brownout (weakened — fails at full charge too).
- The vendor's SAFE path (`swu_next="reboot"` + `switch_root`-off-NOR in
  `preinstall_nor.sh`) EXISTS but is **commented out**.
- **Fix class: flash from a non-mounted context** — FEL/xfel, or the SD-NAND die, or
  re-enable the switch_root pivot.
- Note: the daemon `ai_glass_ota` also flashes truncated downloads (5 s SO_RCVTIMEO, no
  resume, no completeness check) — a second, independent reliability bug.

Also relevant: **even a *successful* flash of our patched image would NOT have removed
the wake word** (§2) — it targets camera ISP tables.

---

## 5. The AM01CY `.bin` / BLE-DFU route

- Targets the **BLE/control MCU (AM01CY)**, NOT the V821/E907. A separate BLE/control
  MCU with encrypted firmware and a custom `0xBC` DFU scheme (no Nordic/nRF/Softdevice
  strings present).
- `firmware.bin` (1,029,105 B): 80-byte plaintext header — magic `e5 c3 bd 81`, body
  length @0x04/0x08, **CRC32 @0x0c**, version `AM01CY_2.00.10_260411` @0x10,
  `AM01CY_V2.0` @0x30 — then an **encrypted body** (~7.98 bits/byte).
- **BLE DFU frame:** `[0xBC][op][len16LE][crc16][payload]`. App-sent request opcodes
  `0x01`=Start, `0x02`=Init (size+CRC16+arith sum), `0x03`=Send, `0x04`=Validate,
  `0x05`=Activate&Reset — **only ops 1–5 are sent by the app.** `0x06` is a
  response/status code (not a request), status codes incl. `0x06`=NotEnoughPower.
  Stop-and-wait, per-pocket ACK.
- **Un-patchable without the key** (encrypted body). This route is the plausible carrier
  for a re-encrypted custom KWS model IF the AM01CY hosts the wake word and we recover the key/model.
- Two `last-ota` checks (same URL, different body): **`AM01CY`** → `.bin` (BLE DFU),
  **`WIFIAM01CY`** → `.swu` (Wi-Fi/V821 swupdate). Only ever forge WIFIAM01CY.

---

## 6. G2 — full ownership: paths

### Root shell
- **PRIMARY — UART3 console:** `console=ttyS3,115200`, mux `uart@42500c00`,
  `r_uart3_pins` = **PL2(TX)/PL3(RX)** on the AON/R_PIO domain; `etc/inittab:4`
  `/dev/console::respawn:-/bin/sh` (no getty/login), `selinux=0` → **unauthenticated
  uid-0 root**. Needs teardown (charge contacts carry no data). **Measure PL2 idle
  level first — PL2–PL7 are boot0-configurable 1.8 V OR 3.3 V.**
- **CONVENIENCE — adb-over-TCP:** 2-line fix to `etc/init.d/adbd` (uncomment
  `ADB_TRANSPORT_PORT=5555`; fix the buggy `[ -n $VAR ]` guard). Caveats: Wi-Fi is
  on-demand (rtc_init.sh loads wlan only for modes 2/3/8), so it's **not reachable
  while worn** without forcing Wi-Fi-always-on; adbd bind (0.0.0.0 vs loopback) and auth
  default are unconfirmed. Chicken-and-egg: needs a first shell to install.
- **ULTIMATE — xfel over USB-FEL:** mask-ROM, **unbrickable** — ⚠️ *2026-07-27: only if USB0 D+/D-
  is reachable, which is **UNCONFIRMED** on this unit (charge contacts are power-only, user confirms
  no USB; §8 #3). Without USB the no-USB unbrickable net is a **CH341A/SOIC8 offline NOR reflash**,
  not FEL.* V821 officially supported
  (`xfel chips/v821.c`, detect `0x00188200`). NOR read/erase/write + `xfel ddr` + write-
  and-exec a RAM U-Boot. FEL auto-entered when no valid boot0 (order SMHC0→SMHC2→SPI0→FEL);
  Zadig WinUSB on `1f3a:efe8`.
- **BLUNT FALLBACK — direct SPI-NOR programmer:** CH341A (needs 3.3 V mod) + SOIC8/WSON8
  clip on the PY25Q256; read 32 MB twice + diff, overlay `riscv_patched` at the mtd4
  offset, write back with verify.

### Integrity model (why custom images are accepted)
Unsigned boot0/uboot, **no secure-boot, no dm-verity**; swupdate gate is **only** the
per-CPIO-item md5. We can build valid `.swu` with `repackage_swu.py` / `build_minimal_swu.py`.

### Custom firmware build
- Reuse the **byte-exact stock kernel** (`ANDROID!` boot.img on mtd3), `extracted.dtb`,
  `boot0`, `riscv` (all held known-good); rebuild **only the root squashfs**.
- Toolchain (NO NDA): **Buildroot `riscv32-linux-musl`, ABI ilp32d (rv32imafdc), musl
  1.2.4, interp `/lib/ld-musl-riscv32.so.1`** (matches `bin/adbd`, `bin/busybox`).
  `mksquashfs -comp lzo -b 32768 -all-root` to match the stock v4.0 LZO/32 KB superblock.
  Package with the existing repackage scripts.
- **Persistence (corrected):** the OpenWrt overlay is **DISABLED** on stock
  (`files/init` `MOUNT_OVERLAY=0`; `mount_overlay` never called) → `/` is RO squashfs;
  the "overlay survives OTA" story is FALSE. Persist instead via the **jffs2 `/etc`**
  (`MOUNT_ETC=1`): edit `/etc/init.d/adbd` + add `/etc/init.d/S99devroot` (rc.final loops S??*).
- Open BSP substitute for the login-walled Tina SDK if a modified kernel is ever needed:
  `AvaotaSBC/linux` + u-boot + **SyterKit** (`YuzukiHD/SyterKit`), same V821.

### Safe flash (defeat the §4 reset)
- `swupdate -e stable,sdnand` (writes the separate SD-NAND die) then boot-switch, OR
  re-enable the vendor `switch_root`-off-NOR then `-e stable,nor`, OR FEL/xfel `spinor write`.

---

## 7. Recommended sequence

1. **(now)** Stop all E907/`riscv`-splice + SD-NAND-`riscv` + `firmware_class.path` work
   for the wake word — confirmed wrong target.
2. **(SW, G1 quick win)** Phone-side custom phrase on top of the `aiVoiceWake` disable +
   the bundled `KeywordRecognizer`.
3. **(SW prep, G2)** Build the Buildroot toolchain + custom squashfs (dropbear +
   persistent-root `S99devroot` in jffs2 `/etc` + adbd fix); dry-run `repackage_swu.py`.
4. **(HW foothold)** Teardown → UART root shell (measure PL2 level first). Unblocks G2.
5. **(from shell)** One recon pass: `cat /proc/mtd`, `df -h /mnt/UDISK`, `cat /proc/cmdline`,
   `mount`, `dmesg`, `lsmod`, `netstat -ltn | grep 5555`,
   `cat /sys/class/remoteproc/remoteproc0/state`, `fw_printenv`, `/proc/asound`.
6. **(safe flash)** Stage the verified `.swu`; flash from a non-mounted context; install
   custom rootfs; verify persistent root across reboot.
7. **(HW)** `xfel`/FEL: full 32 MB NOR backup (`xfel spinor read 0 0x2000000 nor_backup.bin`)
   → safely iterable forever.
8. **(HW, G1)** Localize the detector; if AM01CY & not readback-protected, SWD-dump →
   retrain → BLE-DFU. Else keep the phone-side solution.

---

## 8. Critical unknowns (mostly resolved by one shell/teardown)

1. **Where the detector actually runs** (AM01CY vs E907) — biggest G1 determinant.
2. **AM01CY SWD readback protection** (APPROTECT) — gates the on-device G1 route.
3. **USB0 D+/D- reachable on any pad?** — gates xfel/FEL (charge contacts are power-only).
4. **Exact PL2/PL3 pad location + 1.8 vs 3.3 V level** — gates the UART shell.
5. **mtd partition sizes / `/mnt/UDISK` free** — squashfs budget; truncation-vs-disk-full.
6. **adbd bind (0.0.0.0 vs loopback) + auth default** — is adb-over-TCP usable.
7. **BROM SD-NAND-vs-NOR boot0 preference + gprcm value @0x4a000258 for SD-NAND**
   (only the NOR value 0x30000000 is byte-confirmed).
8. **NOR variant: 3.3 V py25q256hb vs a 1.8 V L-part** — verify marking before a programmer.

---

## 9. Hardware shopping list

- USB-UART TTL adapter with 1.8 V-capable VIO (FT2232H/FT4232H, or CP2102N/CH343 with VIO jumper).
- Bidirectional level shifter (TXS0108E) in case PL2/PL3 idle at 1.8 V.
- Multimeter + logic analyzer / low-end scope (find GND, measure pad level, trace AM01CY↔V821).
- CH341A **with 3.3 V mod** (or switchable XTW-3) + SOIC8/WSON8 clip for the PY25Q256.
- Fine-tip iron, 0.1 mm magnet wire, flux, hot-air station.
- USB breakout for FEL D+/D-; Zadig (Windows) → WinUSB on `1f3a:efe8`.
- SWD probe (J-Link/clone) for the AM01CY; RISC-V OpenOCD-capable JTAG probe for the V821 cores.
- Teardown kit + bench PSU; **≥1 sacrificial second unit** for destructive probing.

---

## 10. Prior art / references

- `FerSaiyan/Alternative-HeyCyan-App-and-SDK` — RE'd this device's OTA (opcodes 0xFC/0x73,
  `writeIpToSoc` pull-mode, `parse_swu.py`, `AGENTS.md` notes). OSS `.swu` URL template
  `qcwxfactory.oss-cn-beijing.aliyuncs.com/bin/glasses/<wifiHardwareVersion>.swu`
  (returns AccessDenied without a signed URL; server returns "no update / 60001" →
  **we cannot download the real `.swu`**; our `firmware.swu` is a device dump).
- `ebowwa/HeyCyanSmartGlassesSDK` — QCSDK/DFU headers; `VoiceWakeupControlTests`.
- `AvaotaSBC/AvaotaF1` (schematics), `AvaotaSBC/linux` (BSP), `YuzukiHD/SyterKit` (bootloader).
- `xfel.xboot.org` + `github.com/xboot/xfel` (V821 FEL), `linux-sunxi.org/V821` + `/FEL`.
- `github.com/kahrendt/microWakeWord` (TFLM int8 streaming log-mel KWS trainer, ~40–50 KB).

---

## 11. Workflow health / caveats

- OTA-diagnosis workflow: 17/18 agents OK. Ownership roadmap: 19/23 OK — 4 failed:
  one auto-blocked on a security-topic (the remote-exploit-without-hardware angle; not
  pursued), three schema timeouts (SD-NAND boot detail, exact PCB pad locations, part of
  the KWS-toolchain research). **So pad locations are from the AvaotaF1 reference + DTB,
  NOT a teardown of the actual unit yet.**
- The single biggest open item is a hardware confirmation (detector location + reachable
  test pads). Nearly everything else is software once a shell exists.
