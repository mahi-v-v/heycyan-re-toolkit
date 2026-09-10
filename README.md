# HeyCyan Smart Glasses — Reverse-Engineering Toolkit

A reverse-engineering investigation into the **Cyan (a.k.a. HeyCyan / Oudmon) smart glasses** —
their hardware, firmware, OTA mechanism, and BLE control protocol — plus a **React-Native (Expo)
companion "tinker" app** built to exercise what the analysis uncovered.

It is written to be picked up by another engineer: everything needed to understand *what was
found, how, and how confident we are of it* lives in [`docs/`](docs/), with the supporting
analysis scripts and app code alongside.

> **What this repo contains** — the author's own work: narrative RE documentation, analysis and
> tooling scripts (Ghidra, crypto, OTA), and the companion app source.
>
> **What it deliberately does not contain** — the vendor's proprietary SDKs, firmware images,
> and decompiled application. Those are third-party material and are not redistributed here.
> See [`VENDOR_ASSETS.md`](VENDOR_ASSETS.md) for what is referenced and how to obtain it.

> ### Read the findings status before trusting any claim
> This was an active investigation and several early conclusions were later **refuted**. Every
> document is annotated, but the single reconciled source of truth is
> **[`docs/00_Executive_Summaries/ownership_and_wakeword_roadmap.md`](docs/00_Executive_Summaries/ownership_and_wakeword_roadmap.md)**,
> which labels each claim **CONFIRMED**, **INFERRED**, or **REFUTED**. When an older doc and the
> roadmap disagree, the roadmap wins.

---

## Findings at a glance

| Claim | Status | Basis |
|-------|--------|-------|
| Stock wake word is branded **"Hey Cyan"** | **Confirmed** | Firmware/app strings + assets |
| Wake word can be **disabled** over BLE (`aiVoiceWake`, opcode `0x44`, on/off only — never a phrase) | **Confirmed** | Decompiled vendor SDK (`LargeDataHandler`); wired into the tinker app |
| The wake word is **NOT** in the V821 RISC-V (`riscv`) coprocessor image | **Confirmed** | No `TFL3`/NN/MFCC symbols; the "model" regions are camera ISP lookup tables |
| The wake word most likely runs on the **BLE MCU** | **Inferred, not hardware-confirmed** | It owns `aiVoiceWake`; needs a teardown to prove |
| OTA `.swu` flash reset (~29% / ~55%) is caused by **in-place SPI-NOR contention** | **Strong inference** | Binary analysis of the `ai_glass_ota` daemon; reproduces on stock + patched images |
| Device has **no secure boot / no dm-verity**; the only integrity gate is a **per-CPIO-item md5** | **Confirmed** | `sw-description` + extracted rootfs |
| Full persistent root/ownership (UART3 console, `xfel`/FEL over USB) is **achievable** | **Planned / feasible — not yet performed** | Derived from the DTB + reference board |

If you take away one thing: the "hex-patch a wake-word model into `firmware.swu` and OTA it" plan
**does not work** — that model is not in the image that gets flashed.

---

## Repository layout

```
.
├── docs/               # Narrative documentation & reconciled findings (start here)
├── hardware_research/  # Analysis & tooling scripts + curated string/decompiler notes
└── mobile-app/         # React-Native (Expo) companion app + custom native SDK module
```

### `docs/` — the documentation hub
Numbered to read top-to-bottom; see [`docs/README.md`](docs/README.md) for the full index.
- **`00_Executive_Summaries/`** — high-level summaries and the reconciled **[ownership & wake-word roadmap](docs/00_Executive_Summaries/ownership_and_wakeword_roadmap.md)**.
- **`01_Hardware_Architecture.md`** — Allwinner V821 SoC, the RISC-V cores, the separate BLE MCU, flash/storage, peripherals.
- **`02_Firmware_and_OS.md`** — Tina Linux layout, AMP setup, `swupdate` OTA packaging.
- **`03_Mobile_App_and_SDK.md`** — BLE commands/opcodes and protocol offsets.
- **`05_Hardware_Hacking_Findings/`** — Ghidra scripts, the hardware-hacking log, and the OTA flash debugging log.
- **`06_Reference_Materials/`** — API/protocol references and the append-only reverse-engineering journey log.

### `hardware_research/` — analysis & tooling
- **`am01cy_crypto/`** — descrambler / key-search scripts for the BLE-MCU firmware, plus their string/analysis notes.
- **`firmware_and_os/strings/`** — curated RISC-V string dumps referenced by the docs.
- **`ghidra_analysis/`** — headless Ghidra Java scripts plus the Python helpers used to analyse, patch, and repackage `.swu` images and to verify claims.

> These scripts operate on vendor firmware/SDK inputs that are **not included** here
> (see [`VENDOR_ASSETS.md`](VENDOR_ASSETS.md)); paths are relative to the repo root.

### `mobile-app/` — the companion "tinker" app
An Expo Router app that talks to the glasses over BLE.
- **`modules/sdk/`** — a custom Expo **native module** (`expo.modules.glasssdk`) that wraps the
  vendor Android/iOS SDKs. The vendor SDK binaries are not committed — drop them in per
  [`VENDOR_ASSETS.md`](VENDOR_ASSETS.md) before building.
- **`app/(tabs)/ota.tsx`** — the OTA screen, including the **Wake Word Control** section (Disable / Enable / Query).
- **Status:** work in progress — builds as a custom dev client, not a finished product.

---

## Getting started

### Run the companion app
```bash
cd mobile-app
npm install
# supply the vendor SDK binaries first — see ../VENDOR_ASSETS.md
npx expo prebuild      # generates android/ with the custom native module
npx expo run:android   # builds & boots on a connected device/emulator
```
(iOS is scaffolded but Android is the tested target.)

### Read the investigation
Start at [`docs/00_Executive_Summaries/ownership_and_wakeword_roadmap.md`](docs/00_Executive_Summaries/ownership_and_wakeword_roadmap.md),
then use [`docs/README.md`](docs/README.md) as the index into the deeper docs.

---

## A note on grounding

Claims in these docs are tagged CONFIRMED / INFERRED / REFUTED, and the correction banners at the
top of superseded documents are intentional history, not clutter — they show which hypotheses
were tested and dropped. This is a security-research / portfolio record of the reverse-engineering
process, including the wrong turns.
