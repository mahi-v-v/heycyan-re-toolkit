# 99_Archive — Superseded / Refuted / Withdrawn Docs

These documents are **kept for the reasoning trail** (the portfolio journey), not because they are
current. Each was refuted or withdrawn by later analysis and carries a correction banner at its top.
**For the current state, see [`../00_Executive_Summaries/status_ledger_and_options.md`](../00_Executive_Summaries/status_ledger_and_options.md).**

| File | Why archived | Where the truth now lives |
|---|---|---|
| `Executive_Summary.md` / `.docx` | Manager-facing wake-word summary (project pivoted; no longer a deliverable). Its live recommendation (phone-side recognizer) is now captured elsewhere. | `status_ledger_and_options.md`; wake-word memory |
| `Cloud_OTA_WakeWord_Implementation_Plan.md` | **Withdrawn** — assumed a firmware-resident TFLM wake model that does not exist; also mis-stated the `.swu` transport and used sha256/BLE. | `software_only_ownership_plan.md`; `swu_flash_halt_root_cause.md` |
| `Current_Architecture.md` | **Refuted** — claimed the KWS runs on the E907/RISC-V coprocessor with the model in `.data_unsaved`. It does not. | `status_ledger_and_options.md` §2 (wake word) |
| `Custom_Implementation_Strategy.md` | **Superseded** — its "CHOSEN" Option D (hex-patch the `riscv` binary) rests on that disproven premise; the "REJECTED" phone-side option is now the leading path. | `status_ledger_and_options.md`; `02_Firmware_and_OS.md` |
| `Shared_Physical_Memory_Patch_Guide.md` | **Refuted** — a technique to relocate a nonexistent RISC-V-resident model. | `status_ledger_and_options.md` §2 |

**The reconciling ground truth** (2026-07-27): the wake word is **not** in the V821/RISC-V image nor in
any decrypted variant's flashable firmware → most likely a **JieLi JL7018F built-in DSP voice-wake** the
firmware only toggles (BLE `0x44`); a custom *phrase* most likely needs a **phone-side recognizer**, not a
firmware patch. Full chronology: `../06_Reference_Materials/cyan_reverse_engineering.md`.
