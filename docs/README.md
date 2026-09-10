# Smart Glasses Documentation Hub

Reverse-engineering and ownership documentation for the Cyan / HeyCyan smart glasses
(**Allwinner V821** camera/Wi-Fi/Linux SoC + **JieLi JL7018F** BLE/audio/control MCU), built from
firmware dumps and the decompiled companion app.

**Project goal:** (1) exercise the SDK's exposed functionality, (2) reverse-engineer and **own** the
firmware, (3) extend the SDK — ultimately, **total software-only ownership** of the glasses.

> ### 🧭 START HERE
> - **[status_ledger_and_options.md](./00_Executive_Summaries/status_ledger_and_options.md)** — the
>   neutral current snapshot: every finding labeled **Confirmed / Inferred / Unverified / Refuted**,
>   plus the full options register. Read this first to orient and steer.
> - **[software_only_ownership_plan.md](./00_Executive_Summaries/software_only_ownership_plan.md)** —
>   the concrete, actionable recipe: a NOR-free script `.swu` → adb-over-TCP root shell — software-only,
>   no hardware, no USB, brick-safe by construction.
>
> Some early conclusions were **refuted** by later analysis; where an older doc disagrees with the two
> above, the newer finding wins. Fully superseded docs are moved to **[99_Archive/](./99_Archive/)**
> (kept for the reasoning trail); any older doc still in the active tree carries a correction banner at
> the top rather than being rewritten. The fullest chronological record is the append-only journal
> **[06_Reference_Materials/cyan_reverse_engineering.md](./06_Reference_Materials/cyan_reverse_engineering.md)**.

### Table of Contents

1. **[00_Executive_Summaries/](./00_Executive_Summaries/)** — summaries, plans, feature maps.
   - **[status_ledger_and_options.md](./00_Executive_Summaries/status_ledger_and_options.md)** — 🧭 neutral current snapshot (findings by confidence) + options register. **Start here.**
   - **[software_only_ownership_plan.md](./00_Executive_Summaries/software_only_ownership_plan.md)** — ⭐ the actionable software-only, no-USB, no-hardware ownership recipe (10-agent adversarially-verified).
   - **[ownership_and_wakeword_roadmap.md](./00_Executive_Summaries/ownership_and_wakeword_roadmap.md)** — the earlier master roadmap: detailed hardware/root paths, safe-flash options, and a shopping list. Partially superseded (banners inside); `status_ledger` is the current summary.
   - **[Glass_Feature_Map.md](./00_Executive_Summaries/Glass_Feature_Map.md)** — three-tier map of glasses features (implemented in the app / reachable in the bundled SDK today / extensible with firmware+RE).
   - **[SDK_Feature_Opportunities.md](./00_Executive_Summaries/SDK_Feature_Opportunities.md)** — catalog of unused SDK capabilities and feature opportunities.
2. **[01_Hardware_Architecture.md](./01_Hardware_Architecture.md)** — the dual-SoC design: Allwinner V821 + the **JieLi JL7018F** BLE/audio MCU, RISC-V cores, SPI-NOR + SD-NAND storage, camera ISP, LEDs, touch.
3. **[02_Firmware_and_OS.md](./02_Firmware_and_OS.md)** — the Asymmetric Multi-Processing (AMP) setup, Tina Linux partitions, RTOS firmware, and `rpbuf` IPC.
4. **[03_Mobile_App_and_SDK.md](./03_Mobile_App_and_SDK.md)** — how the companion Android app uses BLE + the bundled oudmon SDK to talk to the glasses.
5. **[05_Hardware_Hacking_Findings/](./05_Hardware_Hacking_Findings/)** — the hands-on analysis.
   - **[swu_flash_halt_root_cause.md](./05_Hardware_Hacking_Findings/swu_flash_halt_root_cause.md)** — ⭐ why the `.swu` OTA halts at 29%/55% (**in-place SPI-NOR self-flash contention**) + the no-USB fix + anti-brick path.
   - **[am01cy_ota_crypto_analysis.md](./05_Hardware_Hacking_Findings/am01cy_ota_crypto_analysis.md)** — the `.bin` crypto: a **two-time-pad** (needs a 2nd AM01CY version to decrypt); chip-dump plan (banners reconcile it to the current view).
   - **[ota_flash_debugging_log.md](./05_Hardware_Hacking_Findings/ota_flash_debugging_log.md)** — the chronological flash-halt debugging journey behind the root-cause doc.
   - **[cyan_ble_control_probes.md](./05_Hardware_Hacking_Findings/cyan_ble_control_probes.md)** — live on-device BLE probe results (capability query, `{2,1,N}` work-type map, inbound notifies).
   - **[external_repos_and_firmware_distribution.md](./05_Hardware_Hacking_Findings/external_repos_and_firmware_distribution.md)** — distribution mechanism (OSS bucket + `last-ota`), the BLE opcode map, SDK-guide deltas (§5 crypto verdict superseded — banner inside).
   - **[hardware_hacking_log.md](./05_Hardware_Hacking_Findings/hardware_hacking_log.md)** — early headless-Ghidra RISC-V findings. ⚠️ "wake-word NN in RISC-V" framing refuted (banner inside); memory-map/RTOS detail still accurate.
6. **[06_Reference_Materials/](./06_Reference_Materials/)** — SDK/BLE references + the RE journal.
   - **[cyan_reverse_engineering.md](./06_Reference_Materials/cyan_reverse_engineering.md)** — the **append-only RE journey log** — the fullest chronological record (portfolio journal; may be git-ignored/local).
   - **[protocol_specification.md](./06_Reference_Materials/protocol_specification.md)** · **[api_reference.md](./06_Reference_Materials/api_reference.md)** · **[implementation_plan.md](./06_Reference_Materials/implementation_plan.md)** · **[testing_plan.md](./06_Reference_Materials/testing_plan.md)** · **[walkthrough.md](./06_Reference_Materials/walkthrough.md)** — BLE protocol + SDK/app-integration references.
7. **[99_Archive/](./99_Archive/)** — superseded/refuted/withdrawn docs, kept for history (the wake-word-in-RISC-V plans, the Cloud-OTA plan, the manager Executive Summary). See its [README](./99_Archive/README.md).

> **Wake-word status** (since its dedicated section is now archived): the "Hey Cyan" detector is **not**
> in the flashable firmware of any variant → most likely a **JieLi JL7018F built-in DSP voice-wake** the
> firmware only toggles (BLE `0x44`); a custom *phrase* most likely needs a **phone-side recognizer**.
> Full reasoning in `status_ledger_and_options.md` §2 and `99_Archive/`.

Analysis scripts and curated notes live under [`../hardware_research/`](../hardware_research/);
the companion app is in [`../mobile-app/`](../mobile-app/). Vendor SDKs, firmware images, and the
decompiled application are not redistributed here — see [`../VENDOR_ASSETS.md`](../VENDOR_ASSETS.md).
