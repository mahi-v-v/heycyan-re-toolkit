# Cyan Glasses — Custom Firmware OTA Flash: Debugging Log & Findings

> 📄 **This is the debugging *journey*. The concluded root cause + fix lives in
> [`swu_flash_halt_root_cause.md`](./swu_flash_halt_root_cause.md) (2026-07-27): in-place SPI-NOR
> self-flash contention, not the truncated-download bug this log originally centered on.**

**Objective:** Flash a custom `.swu` onto the Cyan/HeyCyan glasses — specifically an
image whose RISC-V coprocessor wake-word ("Hey Cyan") acoustic-model regions are
zeroed — by forcing the **official** Android/iOS app to accept a forged cloud
"update available" response and then serving our patched image over the local
network. (A parallel custom-mobile-app transport path also exists but is not the
active route.)

> ### ⚠️ 2026-07-11 UPDATE — supersedes parts of this log
> - **Primary root cause of the 29%/55% reset = in-place SPI-NOR contention** (all
>   partitions on one NOR die; erasing it while the live squashfs root pages from it
>   resets the whole SoC). The truncating-download bug in §1 is real but SECONDARY.
>   Fix = flash from a non-mounted context (FEL/xfel, SD-NAND, or switch_root-off-NOR).
> - **The patched file targeted the wrong chip anyway:** the `riscv` density regions are
>   camera ISP LUTs, not the KWS model — even a clean flash would not have removed the
>   wake word. The wake word is on the encrypted AM01CY BLE MCU.
> - **The wake word can be DISABLED over BLE** (`aiVoiceWake`, opcode 0x44) — now wired
>   into the app (CyanAdapter/`Glass.setWakeWord`/OTA screen).
> - Full detail + ownership roadmap: **`docs/00_Executive_Summaries/ownership_and_wakeword_roadmap.md`**.

**Status (2026-07-11):**
- **Forge / MITM = SOLVED.** The official app accepts the forged `last-ota`
  response, auto-downloads our patched `.swu`, and begins flashing.
- **BLOCKER = the phone→glasses transfer truncates.** swupdate then fails at an
  inconsistent point (observed **29%** and **55%**). Root cause is now identified
  directly in the glasses' OTA daemon binary (see §1).
- The failure reproduces **identically on the stock image and the patched image**,
  which exonerates our patched file and the cloud/proxy layer.

---

## 1. Confirmed Root Cause — the daemon flashes *truncated* downloads

> ⚠️ **SUPERSEDED (2026-07-27, multi-agent adjudication + new user evidence).** This section's
> title — truncation as THE root cause — is **demoted to a secondary** reliability bug. The
> **primary** cause of the 29%/55% halt is **in-place SPI-NOR self-flash contention**: the live
> root is a squashfs demand-paged from `mtdblock5` on the single PY25Q256 die, and
> `swupdate -e stable,nor` erases that same die (kernel→mtd3 first) with the vendor's
> `switch_root`-off-NOR pivot in `preinstall_nor.sh` **commented out**, so the first erase busies
> the die and the SoC livelocks on the next demand page-fault (b4=0). Truncation is refuted for
> these halts because: a proxy that **completed the full download** still stalled the flash, and a
> **~3 MB file** (impossible to truncate) failed at the same 29%. The download-phase decode below
> remains valid as an *independent secondary* bug. Full adjudication: `cyan_reverse_engineering.md` §13-equiv + this doc's top banner.

Decoded directly from the glasses' OTA daemon ELF
`hardware_research/firmware_and_os/extracted_fw/rootfs_unpacked/bin/ai_glass_ota`
(Andes AndeStar V5 / RV32IMAFDC, musl, PIE, **not stripped**; the Linux-SoC-side
daemon, distinct from the `riscv` KWS coprocessor image). Launched from
`etc/media/rtc_init.sh:78` (`execute_if_exists "/bin/ai_glass_ota" $BOOT_MEDIA`) when
`/sys/kernel/aglink_mode == 3`; `argv[1]` = boot media (`0`=nor, `1`=sdnand).

### The download routine `start_down_ota_firmware` @ 0x13968 has three fatal properties
1. **5-second receive timeout.** `setsockopt(SO_RCVTIMEO)` @ 0x13cd0 with
   `tv_sec = 5, tv_usec = 0`.
2. **No resume.** The GET request carries **no `Range:` header**; there is no
   `lseek`/`fseek` — any re-download rewrites the file from byte 0.
3. **No completeness check.** On a 5 s stall the timeout-errno branch (0x13eca)
   **returns success (0)**; on an early peer FIN (`read()==0`) it also returns 0.
   It **never compares `downloaded_bytes` vs `total_bytes` (Content-Length)**.
   There is **no size check and no md5 check in the daemon** — integrity is
   delegated entirely to swupdate's per-CPIO-item `cpio_item_md5`.

Other loop facts: HTTP only ("Not support https"), default port 80, streams to
disk with `fwrite()` in 4096-byte `read()` chunks, save path
`/mnt/UDISK/openwrt_v821_aiglass-ab.swu`. On `read()` error it retries up to 3
consecutive times then aborts (return 6); on a short `fwrite` (disk full) it aborts
(return 3).

### The caller flashes immediately on "success"
`aglink_ota_task` (0x13766–0x137be), single-threaded:
```
pthread_create(progress threads)
r = start_down_ota_firmware(url)      // blocking download
if (r == 0) start_ota_sh()            // runs swupdate right away
```
`start_ota_sh` @ 0x1197c calls, via libc `system()` (blocking), then `sync()`:
```
swupdate -i /mnt/UDISK/openwrt_v821_aiglass-ab.swu -e stable,nor      (boot media 0)
swupdate -i /mnt/UDISK/openwrt_v821_aiglass-ab.swu -e stable,sdnand   (boot media 1)
```
Only pre-check anywhere is `access(swu, F_OK)` (existence).

**Net effect:** over a flaky phone→glasses Wi-Fi link, any >5 s stall or early
connection close produces a **truncated `.swu` that the daemon treats as complete
and immediately flashes**. swupdate's md5 check then fails **wherever the
truncation landed** → inconsistent failure point.

---

## 2. Progress-bar protocol (what the % actually measures)

The "wifi upgrading" percentage is **100% reported by the glasses over BLE** — the
phone measures nothing about the transfer itself.

- **Glasses side (two independent threads):**
  - *Download %* — `get_download_firware_schedule` @ 0x1394a = `100 * downloaded /
    total`; pushed every **500 ms** over BLE (opcode `0x73`, subtype `4`). This is
    the **byte-based download percentage**, not flash progress.
  - *Flash %* — `aglink_ota_get_swuupdata_bar_task` @ 0x11a16 connects to swupdate's
    UNIX socket `/tmp/swupdateprog`, reads its 2400-byte progress struct, computes
    `total% = (cur_step-1)*100/nsteps + cur_percent/nsteps` → global
    `g_swuipdata_bar`.

- **Phone side** (`apk_extract/.../ota/OTAActivity1.java`):
  - Notify handler `MyDeviceNotifyListener.parseData` (lines 843-949): on
    `loadData[6]==4`, reads three stage bytes `b2=loadData[7]`, `b3=loadData[8]`,
    `b4=loadData[9]`.
  - `combineProgress(100, b2, b3, b4, 0.0, 0.29, 0.01, 0.70)` (line 926) =
    `round(0.29·b2 + 0.01·b3 + 0.70·b4)`.
  - **Mapping:** `b2` (0–29%) = phone→glasses **download**; `b3` (29–30%) = the
    download→swupdate handoff; `b4` (30–100%) = **swupdate flashing**.
  - Raw HTTP file server: `handleClient` (lines 750-776) — 4096-byte loop,
    `Content-Length` set, `Connection: close`, **no integrity check**.
  - **60 s no-progress watchdog** (line 925): every progress packet resets a 60 s
    timer; if the glasses go silent for 60 s → `otaFail()` → `finish()`.
  - `onResume` (line 585): the OTA screen **`finish()`es if there is no active
    network** — so the phone cannot be left with zero networks during the flash.
  - Transport = Wi-Fi Direct P2P (`WifiP2pManagerSingleton`); phone = group
    owner/server, glasses pull the file.

---

## 3. The two failure modes decoded (29% vs 55%)

**One root cause, two landing spots** — the truncation point varies with when the
link stalls, so the same firmware fails at different places.

- **Mode A — freeze at 29% (b2≈100, b4=0), Wi-Fi LED stays ON.**
  Truncation near the front → swupdate rejects the file almost instantly → `b4`
  never advances past 0. The LED stays lit because **the daemon never touches Wi-Fi
  on the flash path** (all `wifi_off`/`stop_p2p` live only in the P2P/AP state
  machine). The phone sits at 29% until its 60 s watchdog gives up.

- **Mode B — freeze at 55% (b4≈36%), Wi-Fi LED turns OFF then freezes.**
  Enough arrived that swupdate flashes the early images and reaches ~36% of the step
  sequence (writing the 3.4 MB `kernel` / `rootfs` to NOR), then stalls on the
  corrupt region. The LED going dark = the **P2P group being torn down** (phone's
  60 s timeout → `finish()` → teardown), and/or swupdate overwriting the live
  mounted rootfs and taking the Wi-Fi driver down with it. **Not** a reboot or
  brownout.

---

## 4. Corrections to earlier assumptions

- ❌ **"Brownout / watchdog reset during flash"** (a prior hypothesis) is **not
  supported**: the daemon has **no `/dev/watchdog`, no `reboot()`**, and swupdate's
  auto-reboot (`swu_next="reboot"`) is **commented out** in `sw-description`. The
  LED-off at 55% is P2P teardown / live-partition corruption, not power.
- ⚠️ **"Low brick risk, clean A/B with rollback"** is **unconfirmed**. For `nor`
  boot the selector appears to target `mtdblock5` — the **mounted** rootfs — which
  is a real mid-flash hang/brick hazard. Despite the `-ab` in the filename, binary
  analysis could **not** confirm true inactive-slot A/B separation. Treat
  brick-safety as UNVERIFIED until we read the partition table on-device.
- ✅ The `.swu` file itself is fine — stock and patched fail identically, and the
  cached image on the phone was md5-verified byte-perfect.
- ✅ The MITM/forge is fine and was never the blocker.

**Could not determine (needs on-device shell):** exact `/mnt/UDISK` and mtd
partition byte-sizes (no partition table in the extracted artifacts); the exact BLE
`tx` instruction that ships the flash-% during flashing (Andes GP-relative ops not
byte-decodable with available tooling); the definitive P2P/LED-drop trigger
(phone-side P2P lifetime).

---

## 5. Timeline of attempts

1. **Custom mobile-app transport** (`CyanAdapter.kt`, AP-join to `CY01_<MAC>` via
   `WifiNetworkSpecifier` + `requestNetwork` + `bindProcessToNetwork`). Built,
   extensively edited, **never fully built/tested**. Parked in favor of the
   official-app MITM.
2. **Official app + MITM (Reqable Desktop)** — forge the `last-ota` response to
   `retCode:0, openOrNot:2, downloadUrl→our server`. **Works**: forge fires, phone
   downloads our `.swu`, flashing starts.
3. **Stock vs patched A/B test** (`serve_stock.py` serves the stock image at the
   same URL) — **both fail identically at 29% / 55%** → file exonerated, transport
   isolated.
4. **iOS attempt** — floods of failing pinned-service CONNECTs (icloud / google
   play / apple) are expected MITM noise; fix is to scope decryption to
   `qlifesnap.com`. iOS uses the AP-join model ("app wants to join the network").
5. **Proxy troubleshooting** — the proxy makes home Wi-Fi show "limited
   connectivity" (it intercepts Android's connectivity probe), which nudges MIUI to
   churn the single Wi-Fi radio that P2P shares → the >5 s stall that truncates the
   transfer. Confirmed via a Reqable screenshot that it is a **plain Wi-Fi HTTP
   proxy** on `192.168.29.3:9000` (NOT a VPN capture), with decryption correctly
   scoped to only the qlifesnap `last-ota` (background traffic tunnels through).
6. **mitmproxy auto-forge (planned)** — `hardware_research/ghidra_analysis/forge_ota.py`
   written (auto-forges every `last-ota`, incl. re-polls, WIFIAM01CY only). Install
   of mitmproxy was interrupted; `pip` had to be re-bootstrapped
   (`python -m ensurepip`).

---

## 6. Observations log

- Very consistent behaviour: stalls at **29%**, retry stalls at **55%**, next retry
  **restarts from 0** (matches "no resume").
- **29%**: blue Wi-Fi LED **stays on** for a while after progress freezes.
- **55%**: blue Wi-Fi LED **turns off**, then progress freezes.
- Identical on **stock and patched** images.
- **Removing the proxy mid-flow makes the app say "download/update finished"** — the
  app **re-polls `last-ota` during the flow**; without the forge it gets the real
  "no update" and bails to a finished/idle state. ⇒ the proxy/forge must stay live
  for the entire flash.
- Reqable screenshot: only the qlifesnap `last-ota` (row 6942) is decrypted and
  returns 200; all other hosts (Apple/Spotify/WhatsApp/Xiaomi) tunnel through
  untouched (`Application = Unknown` confirms a network proxy, not a per-app VPN).
- The firmware transfer **never appears in the proxy log** — it is a direct P2P
  connection that does not route through the proxy, so the proxy cannot corrupt the
  bytes directly (its only effect is the indirect radio churn).

---

## 7. Key artifacts, values & commands

- **Firmware**
  - `hardware_research/firmware_and_os/firmware.swu` — stock, 21,262,336 B,
    md5 `adea8da7db1e8a9d3ad769c165acb3ca`.
  - `hardware_research/firmware_and_os/firmware_patched.swu` — patched,
    21,262,116 B, md5 `e9ce15449aa621e9715848e9506d3a1e`.
  - Patch zeroes 3 regions in the `riscv` image: file offsets `0x11ba38`,
    `0x138238`, `0x154638` (KWS-model candidates). CPIO CRC + `cpio_item_md5` correct,
    ELF intact.
- **Glasses daemon:** `.../extracted_fw/rootfs_unpacked/bin/ai_glass_ota`.
  swupdate manifest: `.../firmware_and_os/firmware/sw-description`
  (images `boot0`→mtd0, `kernel`→mtd3, `rootfs`→mtd5, `riscv`→mtd4, `user`→mmcblk0p13;
  `installed-directly=true`, auto-reboot commented out).
- **Tooling (repo `hardware_research/ghidra_analysis/`):**
  - `serve_swu.py` — serves the patched image on :8080.
  - `serve_stock.py` — serves the STOCK image at the same `/firmware_patched.swu`
    path (A/B diagnostic; keep the forged body unchanged).
  - `forge_ota.py` — mitmproxy addon, auto-forges the WIFIAM01CY `last-ota`.
- **Forged `last-ota` body (WIFIAM01CY only):**
  ```json
  {"retCode":0,"message":"success","data":{"hardwareVersion":"WIFIAM01CY_V2.0","version":"WIFIAM01CY_1.00.28_2601010000","isEnforceUpdate":"0","enforceUpdateFrom":"","enforceUpdateTo":"","downloadUrl":"http://192.168.29.3:8080/firmware_patched.swu","openOrNot":2,"uploadDate":"2026-07-10 10:00:00","os":1,"updateDesc":"custom"}}
  ```
  Note: the app POSTs `last-ota` **twice, same URL, different request bodies** — one
  for `AM01CY` (BLE/**JieLi JL7018F** MCU) and one for `WIFIAM01CY` (Wi-Fi/V821 SoC). **Only forge
  the WIFIAM01CY one**; leave AM01CY alone so the app does not try to BLE-update the
  JL7018. (`"WIFIAM01CY"` contains `"AM01CY"`, so discriminate on the WIFI prefix.)
- **Host / device:** PC `192.168.29.3` (Win 11); Reqable Desktop proxy on `:9000`;
  file server on `:8080`. adb at
  `<home>\AppData\Local\Android\Sdk\platform-tools\adb.exe`; app package
  `com.glasssutdio.wear`. Phone was `heory95hkjfmeem7` (Xiaomi 2312DRAABI, MIUI).
  In Git Bash use `export MSYS_NO_PATHCONV=1` for adb `/sdcard/...` paths.

---

## 8. Open questions / next steps

1. **Get a shell on the glasses (decisive).** ⚠️ *2026-07-27: adb-over-USB is NOT available —
   the charge contacts are power-only, no USB data path (roadmap §8 unknown #3). Use the **UART3**
   root shell (PL2/PL3, 115200) instead.* The decisive on-device test: run the **same complete**
   `.swu` two ways — `swupdate -e stable,sdnand` (writes the separate SD-NAND die → predicted to
   **COMPLETE**) vs `swupdate -e stable,nor` (writes the NOR die the root pages from → predicted to
   **WEDGE** at the first `MEMERASE`, b4=0). Opposite outcome by target die = confirms in-place NOR
   self-flash contention and refutes truncation. Also read `df -h /mnt/UDISK`, `logread`, `dmesg`
   (look for swupdate in D-state / an MTD busy stall with no oops/reset).
2. **Harden the transfer radio** so it never stalls >5 s — ⚠️ *2026-07-27: this does NOT fix the
   halt; it only addresses the secondary download bug. A clean, complete download still hangs (see
   the §1 SUPERSEDED banner). The real fix class is flashing from a **non-self-paging context**:
   `-e stable,sdnand` (separate die), re-enable the commented `switch_root`-off-NOR pivot in
   `preinstall_nor.sh`, or a CH341A/SOIC8 **offline** NOR write.* Original note: dedicate the phone's Wi-Fi
   radio to P2P (stable/validated home Wi-Fi so MIUI doesn't churn; screen on; MIUI
   "auto-switch to mobile data / Wi-Fi assistant" off). Keep the forge live
   throughout (the app re-polls).
3. **mitmproxy auto-forge** (`forge_ota.py`) to make the forge bulletproof against
   the re-polls that currently kick the app to "finished" — run
   `mitmdump -p 9000 -s forge_ota.py`, install the mitmproxy CA on the phone (app
   trusts user CAs — no pinning).
4. **Verify the outcome** after a successful flash: say "Hey Cyan"; no wake = the
   zeroed regions were the KWS model.
5. **Consider a daemon-tolerant image** if the link cannot be made reliable — e.g.
   confirm whether padding/ordering the `.swu` so the critical images sit early
   would let a partial flash still land the `riscv` image. (Needs the on-device
   partition/df data first.)
