# `.swu` OTA Flash Halt (29% / 55%) — Definitive Root Cause & Fix

**Date:** 2026-07-27 · **Status:** CONCLUDED (confidence 88/100; on-device confirmation test defined below)
**Method:** 8-agent adjudication workflow (`swu-flash-halt-rootcause`, run `wf_083217d5-44d`) — 4 parallel
investigators → 3 adversarial verifiers → 1 synthesizer, run against the on-device binaries
(`ai_glass_ota`, `swupdate`), `sw-description`, `preinstall_nor.sh`, and `rtc_init.sh`.

This is the **conclusion** doc. The chronological journey is in
[`ota_flash_debugging_log.md`](./ota_flash_debugging_log.md); the daemon disassembly is in
[`../06_Reference_Materials/cyan_reverse_engineering.md`](../06_Reference_Materials/cyan_reverse_engineering.md) §9.
Both were corrected on 2026-07-27 to subordinate the earlier "truncated download" framing to this finding.

---

## TL;DR

The custom `.swu` (Allwinner V821 / Linux) OTA halts at **29%** (LED on) or **55%** (LED off) because of
**in-place SPI-NOR self-flash contention** — the SoC is erasing the very flash die it is executing from.
It is **not** a transport/download bug and **not** the image. The user's own intuition — *"the main SoC is
flashing the main SoC"* — is essentially correct. The fix is to flash from a context that does **not**
demand-page from the die being erased.

---

## 1. The conclusion

The V821 runs its root filesystem as a **read-only squashfs demand-paged from `mtdblock5`** on the single
**PY25Q256 32 MB SPI-NOR** die. `/sbin/swupdate` *and* the `ai_glass_ota` BLE-progress reporter execute from
that same squashfs. `swupdate -e stable,nor` erases and rewrites partitions on that **identical die**
(`kernel→mtd3` first, then `rootfs→mtd5`, `riscv→mtd4`, `boot0→mtd0`) **without first pivoting root off NOR**.

A serial-NOR die **cannot service reads while an erase/program is in flight** (the sunxi spi-nor MTD driver
holds its mutex across the operation; no erase-suspend for foreground page faults). The instant the first
sector-erase begins, the next *uncached* demand page-fault from the running userland — swupdate's own code,
its writer loop, libc, kernel read paths — blocks on the busy die. The SoC **livelocks**. It is a hang, not a
reboot.

**Confidence 88/100.** It survived three independent adversarial refutation attempts (transport-resurrection,
third-cause hunt, mechanism-coherence). Not 100 because it rests on static analysis + field evidence, not yet
a live `dmesg` — see the confirmation test in §7.

---

## 2. The smoking gun

`firmware/preinstall_nor.sh` contains the vendor's **own** safe-flash mitigation — a `switch_root` pivot that
moves the running root onto the **SD-NAND** die (`mount /dev/mmcblk0p9 … ; switch_root`) *before* NOR is
erased — and it is **commented out**. The vendor knew in-place NOR flashing is unsafe and shipped the fix
**disabled** in this image. This is the decisive artifact: it both confirms the mechanism and explains why
the failure is deterministic for this build.

(Also commented out in the same manifest: `swu_next="reboot"`. So the daemon never auto-reboots — consistent
with the observed *hang*, not a reset.)

---

## 3. Why the transport / truncated-download theory is refuted

The earlier hypothesis was that `ai_glass_ota`'s download routine (5 s `SO_RCVTIMEO`, no resume, no
completeness check) flashes a **truncated** `.swu`. That is a real, independent **secondary** bug — but it is
**not** the cause of these halts:

| Evidence | Kills transport theory because… |
|---|---|
| A **~3 MB file** also fails at 29% | Too small to truncate over the 5 s window; a first-erase hang is payload-size-independent. |
| **Proxy served the full file; download completed; flash still stalled** | All 3 transport bugs live only in the download phase; swupdate then reads a *complete local file*. Zero network I/O in the flash step. |
| Freeze at **exactly 29%** | 29% = download 100%, flash 0%. A truncated transfer freezes *below* 29% (proportional to bytes received), not at the boundary. |
| **Stock and patched images fail identically** | Payload/content-independent → structural, not a file/transfer defect. |
| **`.bin`/JL7018 BLE OTA works reliably** | Different chip, per-packet-ACK transport, and it never self-flashes the die the running SoC pages from. |

---

## 4. One mechanism, two stopping points (29% vs 55%)

The exact halt point is set **stochastically** by page-cache residency (which uncached page the running system
faults on next, and which region is under erase):

- **29% · LED ON · no reboot** — the common early death. The **first** erase (`mtd3`) busies the die; the very
  next uncached page-fault wedges the SoC before swupdate emits any step → `b4 = 0`. The Wi-Fi driver is
  RAM-resident and the flash path never calls `wifi_off`, so the hung SoC **holds the blue LED in its last
  (on) state**.
- **55% · LED OFF** — a run where enough of swupdate's working set was already cached to progress through the
  kernel write and reach the `rootfs → mtd5` step. Erasing `mtd5` destroys the **live squashfs backing store**
  under every running process (SIGBUS / soft-lockup), **and** the phone's ~60 s no-progress P2P watchdog tears
  down Wi-Fi Direct → LED off.

Neither is a power/brownout reset (the daemon has no `/dev/watchdog`, no `reboot()`; `swu_next=reboot` is
commented out; it fails at full charge).

---

## 5. "So how was OTA *supposed* to work?" (the vendor-intent question)

A shipping product must have working OTA, so this halt is almost certainly a **misconfiguration/regression**,
not the intended design. The evidence supports a clear reconciliation, with the boot-media as the pivot:

- **The daemon flashes the media it booted from.** `rtc_init.sh` passes `argv[1] = BOOT_MEDIA` to
  `ai_glass_ota` (`0`=nor → `-e stable,nor`; `1`=sdnand → `-e stable,sdnand`). So a unit that **boots from NOR**
  self-flashes NOR; a unit that **boots from SD-NAND** flashes SD-NAND.
- **SD-NAND tolerates concurrent read/write; raw serial-NOR does not.** `mmcblk0` (SD-NAND) is a managed-NAND
  device with its own controller and can service reads from one area while programming another. So an
  SD-NAND-booted unit can OTA-flash SD-NAND **without** the fatal contention that NOR self-flash hits.
- **The `switch_root`-off-NOR pivot is the belt-and-suspenders for the NOR-boot case** — and it is disabled.

**Most-supported reconciliation (hypotheses, ranked):**
1. **H1 — Boot-media mismatch (leading).** Production/normal operation runs from **SD-NAND**, where field OTA
   works. This unit/build boots from **NOR** (`root=/dev/mtdblock*` per `rtc_init.sh`), where OTA self-flashes
   NOR and needs the (disabled) `switch_root` pivot. The bug is that this unit is NOR-booting *and* the pivot
   is off — a build/provisioning regression, exactly the "this bug shouldn't be here" the user sensed.
2. **H2 — Disabled safe path.** Even accepting NOR boot, the vendor's intended flow was
   *download → pivot root to SD-NAND → erase NOR → reboot*. Someone commented out the pivot (and the
   auto-reboot). Re-enabling it restores working NOR OTA.
3. **H3 — Field OTA is SD-NAND-only by design.** `-e stable,nor` is a factory/recovery path run from a
   non-mounted context (fixture/FEL), never in the field; field `.swu` OTA targets the SD-NAND system, and NOR
   holds an immutable recovery image.
4. **H4 (weak) — V821 field `.swu` OTA is simply not exercised**; the "OTA that works" users see is the BLE
   JL7018 `.bin`. Unlikely, given the whole `WIFIAM01CY` / `last-ota` / `ai_glass_ota` machinery exists for the
   `.swu`.

All four are **testable from a UART3 shell** by reading the actual boot media (`cat /proc/cmdline`, `mount`,
`cat /proc/mtd`) and whether `-e stable,sdnand` completes. H1/H2 are the working hypotheses and both imply the
**same fix** below.

---

## 6. The fix + safe flash paths (no USB available on this unit)

**Fix class:** flash from a context that does **not** page from the die being erased. Given **no USB**
(so no xfel/FEL, no adb-over-USB), best-first:

1. **Target the SD-NAND die** — `swupdate -i <image>.swu -e stable,sdnand`. Writes only `mmcblk0`
   (`kernel_sdnand→p4`, `rootfs_sdnand→p9`, `riscv_sdnand→p7`, `user→p14`, `boot0_sdnand`), leaving the mounted
   NOR root untouched. **Lowest risk — no NOR write at all**, and NOR stays a bootable fallback.
2. **Re-enable the vendor pivot** — uncomment the `mount /dev/mmcblk0p9 /tmp/tmp_root; switch_root` block in
   `preinstall_nor.sh` so root moves to SD-NAND *before* any NOR erase, then `-e stable,nor` works.
   *(Leave the `dd if=/dev/zero of=/dev/mtdblock0` boot0-destroy line commented — never zero boot0 without a
   verified restore.)* Repackage the `.swu` with the edited script.
3. **UART3 root shell** (PL2/PL3, 115200, unauthenticated, no secure-boot) → copy swupdate + the `.swu` into
   tmpfs/RAM, pivot/stop the NOR-resident root, then drive the NOR write so the writer never demand-pages from
   the die under erase. Also gives live `dmesg`/`logread`.
4. **CH341A SPI-NOR programmer + SOIC8 clip** on the PY25Q256 — full offline read/modify/write with the SoC
   powered down (no contention possible).

**The transport-hardening "fix" (stabilize the P2P download) does NOT fix this** — it only addresses the
secondary download bug. A clean, complete download still hangs.

---

## 7. Anti-brick guarantee (without USB-FEL) + the confirmation test

**Unbrickable net (no USB):** a **CH341A + SOIC8 clip** full **32 MB NOR dump taken *before* touching
anything**. Any bad NOR write is then recoverable by clip-reflashing that golden image offline (SoC powered
down → no self-contention, no dependency on a working bootloader). Softer nets: prefer the **SD-NAND target**
(NOR, which holds last-known-good boot, is never written) and keep **UART3** for a console foothold.
**Do not rely on xfel/USB-FEL or adb-over-USB** — the charge contacts are power-only and USB0 D+/D-
reachability is unconfirmed on this unit.

**Definitive on-device test (UART3, no USB):** prove the local file is byte-perfect (`md5sum`), then run the
**same complete `.swu`** two ways:
- `swupdate -i <file>.swu -e stable,sdnand` → **predicted: COMPLETES** (separate die).
- `swupdate -i <file>.swu -e stable,nor` → **predicted: WEDGES** at the first `MEMERASE`, `b4=0`.

Same file, **opposite outcome selected purely by target die** = confirms in-place NOR self-flash contention
and refutes truncation/md5. While the `nor` run is wedged, `dmesg`/`logread` should show swupdate in
**D-state** on an MTD/spi-nor busy stall (or a squashfs read fault), with **no oops and no reset**.
**Disproof conditions:** swupdate exits non-zero with an md5/CPIO error on the complete file (resurrects
integrity); or it reaches `b4>0`/completes the kernel step before dying later; or `-e stable,sdnand` **also**
hangs identically (points away from NOR-specific contention).

---

## 8. Open questions (all resolved by one UART3 shell)

1. Not yet observed on-device that erasing `mtd3` (written first) alone wedges page-ins from `mtd5` before
   swupdate reaches `mtd5` — the exact 29% first-erase hang is inferred from single-serial-NOR-die behavior.
2. The single-die assignment of `mtd0/3/4/5` to the one PY25Q256 is from the documented hardware map +
   `sw-description`, not a live `cat /proc/mtd` / `dmesg`.
3. Whether this build's sunxi spi-nor driver supports **erase-suspend** (which would let `mtd5` reads be
   serviced during an `mtd3` erase and weaken the first-erase mechanism).
4. **Actual boot media of this unit** (`/proc/cmdline`, `mount`) — decides between H1/H2/H3 in §5.
5. Whether `-e stable,sdnand` completes cleanly and a subsequent gprcm/bootenv boot-switch to the SD-NAND
   system boots end-to-end.
6. `/mnt/UDISK` free space and true A/B partition byte-sizes (secondary).

---

## 9. Provenance

- Workflow: `swu-flash-halt-rootcause`, run `wf_083217d5-44d` (7/8 agents OK; the `nor-mechanism`
  investigator errored on schema retries — its role was covered by the daemon-flow investigator and both
  verifiers). Per-agent results: the run's `journal.jsonl`.
- Adjudication: in-place NOR contention scored **88/100** vs truncated-download **20/100**; third causes
  (disk-full, brownout, md5/CPIO, sw-description mismatch, E907/mtd4) each refuted by ≥1 hard fact.
- Corrected companion docs (2026-07-27): `ota_flash_debugging_log.md` (§1, §8), `cyan_reverse_engineering.md`
  (§9, append-only annotation), `ownership_and_wakeword_roadmap.md` (§0, §6 xfel caveats),
  `01_Hardware_Architecture.md`, `Cloud_OTA_WakeWord_Implementation_Plan.md` (A/B brick-safety refuted).
