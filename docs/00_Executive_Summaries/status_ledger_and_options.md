# Cyan Glasses — Project Status Ledger & Options Register

**Last updated: 2026-08-21.** Neutral status snapshot: what is known (and how well), what is refuted, and every open option with honest tradeoffs. No recommendation is implied by ordering. Companion detail docs are cross-referenced.

> **Honesty note on confidence.** Almost everything below is **static analysis of firmware/SDK artifacts on one unit**, plus a few **live network probes** and the user's **field observations**. Very little is hardware-verified (no teardown/UART/USB has been done). Labels: **[Confirmed]** = directly observed/tested or multi-source; **[Inferred]** = reasoned from evidence, not directly seen; **[Unverified]** = plausible but untested; **[Refuted]** = previously believed, now shown false.

---

## 1. Device model (neutral)

Two independent processors, two firmwares, two update channels:

| | **Allwinner V821** | **JieLi JL7018F** |
|---|---|---|
| Role | Camera / Wi-Fi / Linux (Tina/OpenWrt) | Always-on BLE / audio / control; hosts the wake word |
| Firmware | `.swu` (~21 MB, plaintext) | `.bin` (~1 MB, encrypted) = our `test_ota.bin` |
| Update transport | Wi-Fi Direct P2P → `swupdate` | BLE DFU (`0xBC` ops 1–5) |
| Chip ID confidence | [Confirmed] V821 (multiple sources) | [Inferred] JL7018F — 4 sources, **not hardware-confirmed**; some older docs still say "BlueX RF03" (retracted) |

---

## 2. Findings ledger

### [Confirmed] — directly observed, tested, or multi-source
- `test_ota.bin` == `hardware_research/am01cy_crypto/fw_AM01CY_2.00.10_260411.bin` (identical sha256). Container = 0x50 plaintext header, magic `0x81bdc3e5`, tag = payload byte-sum.
- **AM01CY shares no usable keystream with the 19 dumped firmwares** (this session: 2.32% identical vs 100% for same-family siblings; 3,476 windows applied → smudged, no clean decrypt). ⇒ can't crib-drag it; needs a 2nd AM01CY version. Detail: `../05_Hardware_Hacking_Findings/am01cy_ota_crypto_analysis.md` §1.10.
- **Remote acquisition of a 2nd AM01CY `.bin` is currently closed** (this session, live): CDN serves latest-only (old builds 404); `last-ota` returns latest 2.00.10; OSS bucket = `.swu` only.
- **swupdate has no code signing** (v2019.11, no sig strings, invoked with no `-k`); the only gate is per-item md5 (recomputable). No `hardware-compatibility`/anti-rollback in the stock manifest.
- **App-side BLE DFU does no model/version gating** (`DfuHandle.checkFile` only checks existence + size < 12 MB, then streams).
- **No wake-word/KWS strings in any decrypted variant** (AM01C 60.8%, AM01G1/AM01W/A02E02) nor in the V821 firmware; the AM01C `DBG_*` module map has no `DBG_KWS/VAD/ASR/VOICE`. (Coverage is partial — see Inferred.)
- The decrypted variant firmware = JieLi BT-audio/HFP + Allwinner boot (`tone_en/*` prompts, `AT+` stack, SBC/AAC/AEC, `LOADER.BIN`, `dw_update`). Outputs on disk: `HeyCyan-Firmware-Dump/RESULTS/decrypted/`.

### [Inferred] — reasoned from evidence, not directly verified on hardware
- **The `.swu` 29%/55% flash halt = in-place NOR self-flash contention** (SoC erases the die it demand-pages its root from). Adjudicated **88/100**, adversarially verified — but from static analysis + field symptoms, **not a live `dmesg`**. Detail: `../05_Hardware_Hacking_Findings/swu_flash_halt_root_cause.md`.
- **Device is NOR-boot-by-design; the vendor's own in-place `.swu` OTA self-contends too** (from `preinstall_nor.sh` / `postinstall_nor.sh` / `erase_sdnand_boot0`).
- **The `switch_root` block is commented out because it's factory/efex tooling, not a safe pivot** (`switch_root` never returns; it also `dd`-zeroes NOR boot0).
- **The wake word is a JieLi built-in DSP feature** (acoustic model not in the flashable firmware) — leading explanation for the total KWS absence; **caveat: ~40% of the `.bin` is still undecrypted, and JieLi libs can be strings-less**, so not conclusive.
- **The software-only NOR-free script-`.swu` → adb path is feasible and brick-safe by construction** (10-agent plan). Two nodes unverified (below). Detail: `software_only_ownership_plan.md`.
- **A bad `.bin` flash likely = hard brick with no BLE recovery** (DFU handled inline by running firmware; no resident bootloader mode; opcode `0x0F` unused).
- **Onboard media storage = 4 GB SD-NAND** (~3.6–3.7 GiB usable at `/mnt/UDISK`). Vendor-sourced (3 sibling SKUs on the same JL7018F+V821 platform: X01 "SD NAND patch 4GB", W610 "4GB", CY01 "JL7018F with 4GB flash memory") **and** corroborated by our own DTB/`sw-description`, which show a separate `mmcblk0` SD-NAND die carrying `/mnt/UDISK` (p15). ⚠️ **The oft-quoted 32 MB SPI-NOR is the *system* flash, NOT the storage capacity** — two distinct media. Byte-level sizes still need a shell. Detail: `../01_Hardware_Architecture.md` §2, `../06_Reference_Materials/cyan_reverse_engineering.md` §15.
- **Battery = 270 mAh / 3.8 V** LiPo (~5 h music at 80% vol, charge ≤2 h). Two independent vendor sources; no mAh figure in any firmware artifact.
- **Vendor spec sheets are usable but sloppy — corroborate before quoting.** The same sheets that got the chipset and storage right claim the camera is "8MP"/"IMX219"/"Sony 918", while the DTB + ISP tuning tables prove **GalaxyCore GC05A2, 5 MP**. Chipset-naming listings agree on 4 GB; non-chipset listings variously claim 16/32 GB (rejected).
- **Flashing a sibling `.bin` (AM01C→AM01CY) would likely fail/brick** (per-SKU keystream + hardware-config mismatch), and even on success gives no new capability (~99% shared code; re-encryption is the same blocker).

### [Unverified] — open, resolved by the first shell / a live test / external event
1. Does swupdate run a group's `scripts:` when its `images:` list is empty? (Fails **safely** if not — nothing written.)
2. Does `adbd` bind `0.0.0.0:5555` when `ADB_TRANSPORT_PORT` is set on this build?
3. In the OTA P2P group, is the glasses the Group Owner (`192.168.6.1`) or client (`192.168.49.x`)?
4. Actual boot media of **this** unit (NOR vs SD-NAND) — decides the exact "intended OTA" story. *(Unrelated to capacity: the SD-NAND die is 4 GB either way — see Inferred.)*
5. Any whole-file `.swu` integrity check (md5/fileSize) in the SDK / `ai_glass_ota` before flashing? (Likely absent — downloads complete today.)
6. Device-side `.bin` model-gating (would a sibling be rejected vs. flashed-and-bricked?).
7. Where the JieLi KWS model physically lives (firmware data partition vs. DSP library/ROM) — decides whether a custom wake **phrase** is possible on-device at all.
8. Exact `wifi -o connect` arg order + wlan `.ko` filename (for persistent STA).

### [Refuted] — previously believed, now shown false
- Wake word in the V821 RISC-V image — **the "model" regions are camera ISP lookup tables.**
- "BlueX RF03" chip ID — **retracted → JL7018F.**
- Transport/truncated-download as the **primary** cause of the flash halt — **it's a secondary bug; the primary is NOR contention** (a 3 MB file and a completed proxy download both fail).
- "A/B rollback eliminates bricking risk" for the `.swu` — **unverified/likely absent; the NOR rootfs target is the mounted `mtdblock5`.**
- xfel/FEL and adb-over-USB as a recovery net — **no USB data path on this unit.**
- The stock in-place `.swu` OTA ever succeeding in the field — **structurally broken (self-contention).**

---

## 3. Options register (all open follow-ups — neutral)

**Software-only, no hardware, actionable now:**

| ID | Option | Effort | Risk | What it unblocks | Depends on |
|---|---|---|---|---|---|
| A | Build the NOR-free script `firmware_backdoor.swu` (verify NOR-free) | Low–Med | None to build | A ready-to-flash root artifact | tooling in repo (exists) |
| B | Flash it for real + get the shell (phone forge/serve → `adb connect`) | Med | Low (fails safe) | Actual V821 root shell = the whole Linux side | A; a phone (Termux/rooted) + mitmproxy |
| C | Phone-side custom wake word (disable stock via BLE `0x44` + phone recognizer) | Med | None | A working custom wake phrase (not always-on) | app work |
| D | Check JieLi voice-wake SDK docs (web) | Low | None | Whether an on-device custom wake **phrase** is even possible; where the model lives | web access |
| E | Set up a "2nd AM01CY `.bin`" watch (next-release CDN poll + phone app-cache pull) | Low | None | Full AM01CY decrypt → own the wake-word firmware | a future release / cache |
| F | Maximize firmware decryption coverage (union across AM01W's 11 versions) + re-hunt | Med | None | More plaintext; possibly the wake-word region / more strings | — |
| G | Opcode gap analysis (firmware command handlers vs. SDK) | Med | None | Undocumented BLE opcodes / hidden capabilities | decrypted firmware + SDK |
| H | Static-analyze the `.bin` DFU for device-side gating + recovery | Med | None | Certainty on `.bin` brick-recoverability (Unverified #6) | — |
| I | Build the V821 custom rootfs (Buildroot riscv32-musl) | High | None (prep) | A full custom Linux image (needs B to deploy) | B eventually |

**Needs hardware (currently deprioritized by you):**

| ID | Option | Gets you | Note |
|---|---|---|---|
| J | UART3 root shell (teardown, PL2/PL3) | Root without the `.swu` path; resolves most Unverified items | measure pad voltage first |
| K | CH341A + SOIC8 NOR backup/restore | The only no-USB unbrickable net; safe NOR flashing | teardown |
| L | JL7018 chip dump (SWD/UART) | The `.bin` key + wake-word code/model in one read | teardown; JieLi tooling |

**Needs an external event:** M — a new AM01CY release (2.00.11+) appears → capture → full `.bin` decrypt (the clean route to the wake word).

---

## 4. Dead ends / do-not-do
- **Do not** flash a sibling `.bin` (AM01C/AM01W) onto the AM01CY — likely hard-brick on the un-recoverable chip, no gain.
- **Do not** run `build_backdoor_swu.py` in its default mode — it writes a riscv carrier to `/dev/mtdblock4` (NOR) → bricks this NOR-booting unit. Use `--scripts-only` or the SD-NAND-carrier fallback.
- **Do not** re-enable the `switch_root` block or set `swu_next=reboot`.
- **Do not** keep retrying the stock `.swu` OTA or "harden the transfer" — the halt is structural, not transport.

---

## 5. Cross-references
- Flash-halt root cause: `../05_Hardware_Hacking_Findings/swu_flash_halt_root_cause.md`
- Software-only ownership recipe: `software_only_ownership_plan.md`
- `.bin` crypto + acquisition: `../05_Hardware_Hacking_Findings/am01cy_ota_crypto_analysis.md`
- Master roadmap (older, partially superseded): `ownership_and_wakeword_roadmap.md`
- Hardware spec sheet (storage/RAM/camera/battery): `../01_Hardware_Architecture.md` §2
- Append-only RE journal: `../06_Reference_Materials/cyan_reverse_engineering.md`
- Session handoff: `../SESSION_RESUME_2026-07-27.md`
