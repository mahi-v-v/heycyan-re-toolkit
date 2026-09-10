# AM01CY OTA `.bin` — Crypto Analysis & Chip-Dump Recovery Plan

**Target:** `mobile-app/test_ota.bin` (byte-identical to `hardware_research/am01cy_crypto/fw_AM01CY_2.00.10_260411.bin`) — the encrypted AM01CY BLE-MCU firmware, version `AM01CY_2.00.10_260411`, hw `AM01CY_V2.0`, 1,029,105 bytes.

**Date:** 2026-07-13. **Tooling this session:** WSL Ubuntu (`strings`, `binwalk`, `xxd`, `python3`, `openssl`); Windows Ghidra 12.1.2 headless (`analyzeHeadless.bat`); `javap` on the decompiled SDK.

**TL;DR:** The payload is **not** protected by a strong (AES-class) cipher — it is a lightweight, position-preserving, **32-byte (256-bit)-periodic XOR-class transform over already-compressed firmware**. But it still cannot be decrypted from the file alone: the key + algorithm live entirely on the MCU (the phone streams the `.bin` verbatim), the plaintext is compressed (no usable known-plaintext), and only one full image exists (no cross-version key cancellation). The realistic break is a **chip dump** of the BlueX RF03 (no readout protection) followed by Ghidra analysis of the on-chip decrypt routine — that plan is in Part 2.

> ### ⚠️ 2026-07-24 — this TL;DR + §1.9 + Part 2 are PARTLY SUPERSEDED (see §1.10)
> This doc was written with **one** AM01CY file and **within-file** tests only, and it concluded
> "proper cipher → the only break is a chip dump." A later multi-version analysis
> (2026-07-21, via FerSaiyan's `HeyCyan-Firmware-Dump`; see
> `docs/06_Reference_Materials/cyan_reverse_engineering.md` **§12** and memory `am01cy-bin-crypto`)
> established:
> - The scheme is a **two-time-pad** — `C = P ⊕ K` with the keystream **reused across firmware
>   versions**. It is breakable in software with **≥2 same-model `.bin`s** (`C1⊕C2 = P1⊕P2` →
>   crib-drag). No chip dump is strictly required *to decrypt this image*.
> - The decrypted payload is an **Allwinner sunxi eGON multi-component image** (`STAMP_VALUE
>   0x5F0A6C39` + `eGON` magic recovered), carried over BLE — and the control chip is a **JieLi
>   JL7018F**, not a BlueX RF03. Part 2's "dump the RF03 for *this file's* key" premise is retracted;
>   the RF03 pinout/memory-map below is void (kept for history).
> - §1.9's **within-file** "no keystream reuse" result still holds — a stream cipher has no
>   *self*-repeat. Its **"unbreakable" inference does not**, because the reuse is on the
>   cross-**version** axis a single file cannot see.
>
> **§1.10** records this session's (2026-07-24) empirical confirmation on our unit and why AM01CY is
> still blocked (no 2nd sample obtainable). Everything in Part 1 about the **container format, the
> phone-is-passthrough finding, and the additive checksum** remains correct.

---

## Part 1 — Findings

### 1.1 Container / header layout (80-byte plaintext header, verified)

| Offset | Bytes | Meaning |
|---|---|---|
| `0x00` | `e5 c3 bd 81` | Magic |
| `0x04` | `a1 b3 0f 00` | Payload size = `0x000fb3a1` = 1,029,025 (LE) |
| `0x08` | `a1 b3 0f 00` | Same value again (**size1 == size2**) |
| `0x0c` | `37 de cf 07` | Checksum / id = `0x07cfde37` (LE). Prior work confirmed this is a **plain 32-bit additive byte-sum** of the stored payload — not a CRC/MAC/signature, trivially recomputable (no forge barrier). |
| `0x10` | `AM01CY_2.00.10_260411\0…` | Version string (32 bytes, null-padded) |
| `0x30` | `AM01CY_V2.0\0…` | HW string (32 bytes, null-padded) |
| `0x50` | … | Encrypted payload begins (1,029,025 bytes to EOF) |

`size1 == size2` (both = payload length) → the two size fields are **not** a compressed/uncompressed pair.

### 1.2 `binwalk` / entropy / `strings` — the file is opaque

- **`binwalk` signature scan: zero hits.** No embedded filesystem, no compression magic, no recognized code section.
- **Entropy: flat ~7.99 bits/byte across every 64 KB window.** `binwalk -E` reports only *rising* entropy edges (~0.95–0.97 normalized) and **no falling edges** — i.e. no low-entropy region at that granularity. Edges cluster in the `0x7E800`–`0xFA400` tail, which matches the handful of sub-7.0 512-byte windows the earlier work found there (32-byte-record data tables leaking through).
- **`strings`: nothing beyond the header.** Raw file and the prior `shl1`-descrambled candidate (`decode_best.bin`) both yield only random-noise short runs (~1800 "strings," the baseline for 1 MB of high-entropy data). **The descramble does not reveal plaintext.**

### 1.3 Cipher-class verdict — NOT strong AES (this is the key result)

Byte-equality autocorrelation `P(c[i] == c[i+P])` over the payload (baseline `1/256 = 0.00391`):

| P | 8 | 16 | 24 | **32** | 48 | **64** | **96** | **128** |
|---|---|---|---|---|---|---|---|---|
| value | .0038 | .0039 | .0040 | **.0152** | .0039 | **.0142** | **.0112** | **.0126** |

**Every multiple of 32 spikes ~3–4× above baseline; all non-multiples are flat.** Control: `os.urandom` and AES-CTR(zeros) both measure ~0.0040 at *all* periods. A correctly-used strong stream/CBC/CTR cipher **cannot** produce a period-32 spike — this is ~65σ above baseline.

Interpretation:
- The transform is **position-preserving and 32-byte (256-bit) periodic** — almost certainly the `shl1` byte-scrambler (`p[i] = c[i] ^ ((c[i-1] << 1) & 0xFF)`, `p[0]=c[0]`) plus a 256-bit repeating/positional key element.
- The flat ~8.0 entropy is **not** from strong encryption. XOR-class transforms don't raise entropy, so the plaintext underneath must already be high-entropy → **the firmware is compressed**, then lightly obfuscated. (If the plaintext were uncompressed ARM code, code regions would read <7 bits/byte through a weak XOR — they don't.)
- The period-32 leak is the compressed stream's residual structure plus genuine 32-byte-record data tables in the `0x7f000–0xfb000` tail.

**The "weak security" intuition is correct about the algorithm.** This is lightweight obfuscation, not cryptography.

### 1.4 The phone applies NO crypto — key is on-chip

Read from the decompiled SDK (`hardware_research/decompilers_and_extracts/apk_extract/sources/com/oudmon/ble/base/communication/DfuHandle.java`, the same SDK bundled as `cyan_sdk.aar`):

- `checkFile(path)` reads the entire `.bin` into `dfuData` and computes only a **32-bit additive checksum** and **CRC16** (`CRC16.calcCrc16`). No transform.
- `init()` sends `length + CRC16 + checksum`.
- `sendPacket()` slices `dfuData` into 1024-byte packets (2-byte index prefix) and sends the bytes **verbatim** (`System.arraycopy(this.dfuData, idx*1024, pkt, 2, min)`).
- The **entire `com.oudmon.ble` package contains no cipher** — the only crypto-adjacent class is `CRC16.java`.

⇒ The AM01CY MCU receives the raw `.bin` and does the decrypt + integrity + decompress + flash **internally**. The 256-bit key and the algorithm exist only in the MCU's ROM/flash.

### 1.5 Why it can't be broken from the file alone

- **Compressed plaintext** → no usable known-plaintext beyond a possible compression magic (and the compressor is unknown / possibly custom).
- **256-bit unknown key**, and blind statistical attacks are exhausted: `hardware_research/am01cy_crypto/try_common_keys_results.txt` documents a 14,916-trial common/weak/default-key sweep (AES/DES/3DES/XTEA/TEA/XOR × modes × ~300 keys) with **zero** valid decrypts, plus a period-32 column-frequency attack that fails (compressed plaintext has no per-column bias).
- **Single full image** → no `c1 ⊕ c2` key cancellation. Only `AM01CY_2.00.10` is network-obtainable; the `fw_AM01CY_2.00.06` / `2.00.09` files in the repo are 159-byte stubs, not full images.

### 1.6 Corrections to earlier notes

- ❌ Prior "inner **strong ~16-byte-block AES-class cipher**" → **REFUTED** by the period-32 autocorrelation (a strong cipher is flat at all periods). It is a weak 32-byte-periodic XOR-class transform over compressed data. Memory `am01cy-bin-crypto` updated accordingly.
- ✅ "Key on the MCU, phone is passthrough" → **re-confirmed** by reading `DfuHandle`.
- ✅ "Additive-checksum, no signature" → still holds; no forge barrier on the container.

### 1.7 Session-2 experiments (2026-07-13) — descramble is keyless; decompressor gamble is negative

- **The `shl1` descramble is keyless and is the correct inverse for the data sections.** Descrambling drops the tail region entropy **7.73 → 4.14**, revealing structured `0x21`/`0x00` data tables. A strong cipher cannot produce this. The full descrambled image is saved as `hardware_research/am01cy_crypto/descrambled_full.bin`.
- **No recoverable 32-byte mask.** The persistent period-32 signal is a weak statistical artifact, not a clean key: per-column (mod-32) dominant-byte fraction is only 0.013 (a real XOR-over-constant mask would be ~0.5–1.0), and `code[i] ^ code[i+32]` has entropy 7.99 (no collapse). ⇒ there is **no small seed/mask to brute-force**.
- **Exhaustive decompressor sweep = NEGATIVE.** Neither the descrambled nor the raw stream decompresses under any of: raw-DEFLATE (65,536-offset sweep), zlib, gzip, LZMA (alone + raw filter sweep), LZ4 (block + frame, size-guess sweep), zstd, bzip2, brotli (1,024-offset sweep), **heatshrink (full window 4–15 × lookahead 2–10 param sweep, 9 offsets)**, and **classic LZSS (N/F/threshold variants, 128 offsets)**. The heatshrink decoder was unit-tested (literal, backref, overlapping-backref) so the negative is trustworthy. All apparent "hits" were the LZSS ring-buffer `0x20` pre-fill leaking through — false positives.
- **Interpretation:** the high-entropy code region is **not** any common/standard/embedded compressor, and **not** a recoverable weak XOR. It is therefore either a **proprietary/custom packer** (candidate: the JieLi/BlueX lineage — note `com/oudmon/.../spp/jieli`) or a **genuine lightweight cipher**. Both are opaque without the on-chip algorithm. Brute-force is not viable (256-bit effective key, no small seed, no known-plaintext). This **confirms the reframed conclusion**: the remaining work is recovering the packer/cipher routine from the MCU (Part 2), not cryptanalysis of the file. `[2026-07-27 SUPERSEDED — see §1.10 + top banner: it IS a two-time-pad; a 2nd AM01CY version breaks it in software, no chip dump needed.]`

### 1.8 Vendor-packer chase (2026-07-13) — LZO ruled out; points to encryption

Chasing the "custom packer" hypothesis to ground: the vendor SDK bundles a real compressor — `com.oudmon.ble.base.communication.CompressUtils` calls `org.jvcompress.lzo.MiniLZO.lzo1x_1_compress` (**LZO1X-1**), which the earlier Python sweep could not test.
- **Method:** the two decompiled `MiniLZO` copies are un-compilable (CFR left `** GOTO` stubs; JADX left `Method not decompiled` throws). Pulled the canonical upstream source (`github.com/joshsh/twitlogic`, same `org.jvcompress` library), compiled it with JDK 21, and **verified the codec round-trips** (4096→2117→4096, `lzo1x_1_compress`↔`lzo1x_decompress`). Note the port's `lzo1x_decompress_safe` over-reads on valid input — must use the non-safe `lzo1x_decompress`.
- **Result — NEGATIVE.** With the verified decoder: single-stream LZO across offsets 0–0x800, and length-prefixed block-mode (2/4-byte LE/BE headers at 6 base offsets), on **both** the descrambled and raw streams → zero substantial decompressions. Driver: `scratchpad/lzo/LzoSweep{,2}.java`.
- **Where LZO is actually used:** app→device file pushes only (music `BleMusicHandle`, `AvatarHandle`/`EbookHandle`/`AlbumHandle`, watch faces, `TemperatureHandle`) over the JieLi SPP channel — **never** the AM01CY firmware (which `DfuHandle` streams verbatim). So LZO was a reasonable but incorrect lead.
- **Decisive context:** the **public** BlueX OTA builder produces a *plaintext* raw binary (`fromelf --bincombined`, no compression, no encryption — per prior art). A stock RF03 image is therefore uncompressed low-entropy code. Our AM01CY code region is ~7.9 entropy after a correct descramble. Since it is (a) not compressed by any tested codec incl. the vendor's own LZO, and (b) not what the public builder emits, the most supported conclusion is that **qcwxfactory added a private *cipher* over the code section** (leaving the data tables in the clear), rather than compression. ⇒ software-only decode of the file is exhausted; the on-chip cipher/key must come from the chip (Part 2). `[2026-07-27 SUPERSEDED — see §1.10: "software-only decode exhausted" was true for a SINGLE file; a 2nd AM01CY version decrypts it via two-time-pad.]`

### 1.9 Final software-exhaustion pass (2026-07-13) — image fully characterized; cipher is proper

Goal: exhaust every software avenue before any hardware teardown. Results (scripts in `scratchpad/`: `chars.py`, `code_scan.py`, `cipher_attack.py`):

- **Region map of the descrambled image (2KB-window entropy):** ~**80% encrypted** (the front ~500KB code region, `0x0–0x7e000`, and interspersed high-entropy patches), ~**20% recoverable** data (concentrated in the back half `0x7e000–0xfb3a1`). Cleanest plaintext windows: `0xc3000`, `0xcf800`, `0xe4000` (entropy 4.14, ~72% `0x00`/`0x21`), `0xfa000`.
- **No standard container/model/audio magic** anywhere in the descrambled image: no `TFL3`, `ONNX`, `GGUF`, `RIFF`, `ELF`, `wasm`, `PK`, `uImage`. The recovered data tables are a **raw/custom format** with no code to interpret them.
- **No plaintext ARM code anywhere.** Total `bx lr` (`0x4770`) across the whole descrambled image = **4** (random expectation ~7.9), and =12 in raw — i.e. *below* chance. A single 8KB region of real Cortex-M code would show dozens. There is **no unencrypted bootstub** to get a foothold on. (The elevated `push{lr}`/`0xb5xx` count is a byte-distribution artifact — the matched returns `bx lr`/`pop pc` are absent, so it isn't code.)
- **Cipher is proper (ciphertext-only unbreakable):** on the `0x0–0x7e000` code region — **ECB block-collision test: 0 duplicates** in 32,256×16-byte and 16,128×32-byte blocks (not ECB); **keystream-reuse test:** `code[i]^code[i+P]` entropy stays 7.995 for every P from 0x800–0x20000 (no repeating keystream); **column (mod-P) bias** only 0.012–0.023 (too weak to extract). So it is a block cipher in a chaining/counter mode or a full-length-keystream stream cipher — no exploitable structure.

**SOFTWARE IS EXHAUSTED.** Everything solvable without the chip is solved (container, keyless `shl1` scramble, ~20% plaintext data tables). The remaining ~80% is a proper cipher with an on-chip key and no recoverable structure; there is no plaintext code, no standard compression, no weak key/seed, and no ECB/keystream leak. The custom data tables are recoverable but un-interpretable without the (encrypted) code that references them. **Proceeding to hardware (Part 2) is now justified — it is the only remaining path.** `[2026-07-24 correction — see §1.10: "exhausted / only remaining path" was true for a SINGLE file; multi-version differencing (a 2nd AM01CY .bin) breaks it in software. The within-file tests here are correct; the "unbreakable" inference is not.]`

---

### 1.10 2026-07-24 — cross-family two-time-pad test on our AM01CY + acquisition recon

This section supersedes the "chip-dump-only" inference of §1.9 / the TL;DR (see the banner up top). It records a direct re-test of our `test_ota.bin` against FerSaiyan's multi-version corpus and the current state of getting a 2nd sample. Full journal entry: `docs/06_Reference_Materials/cyan_reverse_engineering.md` §13.

**Why §1.9's "unbreakable" was wrong (but its measurements weren't):** §1.9 tested keystream reuse *within one file* (`code[i] ⊕ code[i+P]`) and correctly found none — a stream cipher never self-repeats. The break lives on a different axis: the same keystream is reused **across firmware versions**, so `C1 ⊕ C2 = P1 ⊕ P2` cancels K given **two** same-model images. A single file simply cannot observe that. (Established 2026-07-21; RE-log §12.B.)

**(A) Our AM01CY shares no usable pad with the 19-firmware dump** (payloads aligned at `0x50`; session scratchpad scripts):
- XOR `test_ota` vs all 20 dumped payloads → identical-byte fraction **2.13–2.49%**, flat across all 4 families (baseline `1/256 = 0.39%`): shared header islands + zero-runs, not a shared pad.
- Applied all **3,476** recovered `ks_window` files → max **65%** printable, output *smudged* (`DBF_AWI` vs true `DBG_AXI`), no clean decrypt.
- Consensus keystream (52,785 all-agree offsets) → **33.5%** printable garbage.
- **Contrast:** two AM01W siblings = **100.00%** identical ciphertext (one reused pad); AM01W vs AM01CY = **2.32%**. Per-model-line reuse is real, but **AM01CY's pad is its own** — matches §12.D (cross-family 1.7–6%, direct 0%).
- ⇒ AM01CY needs a **2nd AM01CY_\* version**; it cannot be crib-dragged against this corpus.

**(B) Remote acquisition of a 2nd AM01CY `.bin` — all channels currently CLOSED:**
- `last-ota` (`POST https://www.qlifesnap.com/glasses/app-update/last-ota`, JWT `token`, body `{appId:1,hardwareVersion:"AM01CY_V2.0",romVersion:<v>,…}`): spoofing an old version returns **latest = `AM01CY_2.00.10_260411`** (uploadDate 2026-04-11); `2.00.10` / very-old → 60001 "No upgraded version". Latest-only. Leaked older names: `enforceUpdateFrom=AM01CY_2.00.06_251219`, `enforceUpdateTo=AM01CY_2.00.09_260330`.
- CDN `http://api2.qcwxkjvip.com/download/ota/AM01CY_V2.0/<version>.bin`: `2.00.10_260411` = 200 (our file); older `2.00.09_260330` / `2.00.06_251219` / `2.00.09_260411` = **404 (purged)**.
- ⇒ Sibling only obtainable via **(a)** next release (2.00.11+) capture, **(b)** BLE-OTA / phone `dfu`-cache sniff, or **(c)** the JL7018 chip dump (Part 2 — yields plaintext directly). See memory `am01cy-needs-sibling-firmware`.

**Net:** the fastest software win is a **2nd AM01CY `.bin`** (two-time-pad → full decrypt, no hardware). Part 2 (chip dump) remains valid and is still the route to the **on-chip key + the wake-word code/model** if no 2nd sample appears — but read its "RF03" target as **JieLi JL7018F** (§12.F).

---

## Part 2 — Recovery Plan (Option B): ~~RF03~~ JL7018 SWD/UART dump → Ghidra

> ⚠️ **2026-07-24:** the chip is a **JieLi JL7018F**, not a BlueX RF03 (RE-log §12.F, 4-source
> confirmed). The SWD/UART pinout, memory map, and "no readout protection" claims below are **RF03
> specifics and are now void** — kept verbatim for history. Re-derive the dump path for the JL7018F
> (JieLi tooling: `jl-uboot-tool` / `plated_lead`) before any hardware work. A 2nd AM01CY `.bin`
> (§1.10) decrypts *this image* without any chip dump; the dump's remaining value is the on-chip
> key + the interpreting code.

**Goal:** extract the on-chip **256-bit OTA key + the decrypt/decompress routine** from the AM01CY MCU, then reproduce the transform offline to decrypt `test_ota.bin` and, ultimately, re-pack/re-encrypt a custom image (e.g. modified wake word).

**Why hardware, not cryptanalysis:** Part 1 proves the secret is not in the file or the app. But the chip that *holds* the secret is unlocked — see `am01cy-rf03-dump-path` memory and `docs/00_Executive_Summaries/ownership_and_wakeword_roadmap.md`.

### 2.1 Target & prior art

- **MCU:** BlueX **RF03** family, ARM **Cortex-M0+** (identified via atc1441's RE of the sibling Colmi/QRing rings on the identical Oudmon/qcwxfactory platform).
- **Security posture (BlueX DS-RF03-01 V3.2, text-verified): none to defeat** — no readout protection, no secure boot, no debug-lock fuse (runtime MPU only).
- **Proven read path:** atc1441 published full ROM(`0x0`) + flash(`0x800000`) dumps of the identical chip. Repos: `github.com/atc1441/ATC_RF03_Ring`, `github.com/atc1441/ESP32_nRF52_SWD` (~$5 reader), `gitee.com/BXMicro/SDK3` (buildable BlueX SDK + OTA image tool + SWD flasher).

### 2.2 Three unauthenticated read channels (pick one)

| Channel | Pins | Notes |
|---|---|---|
| **SWD** | `P00`=SWCLK, `P01`=SWDIO | Primary. pyOCD/OpenOCD with ST-Link/J-Link, or the ESP32 SWD reader. If firmware soft-muxes P00/P01 to GPIO, use **connect-under-reset**. |
| **UART2AHB backdoor** | `P12` / `P13` | Reads all memory/registers **without the CPU** — immune to GPIO re-muxing. Fallback if SWD is muxed away. |
| **UART0 ROM ISP** | strap `P16` high, 115200 | Forces the mask-ROM bootloader; read via the ISP protocol. Last-resort, always available. |

**Only load-bearing unknown:** SWD is proven open on the ring but **untested on the glasses**. Likely open; if not, fall back to UART2AHB or forced UART0 boot.

### 2.3 Step-by-step

**Step 0 — Teardown & test points.** Open the glasses, locate the AM01CY, and find test points/pads for `P00/P01` (SWD), plus `GND`, `VCC`, and `nRESET`. Cross-reference the Colmi ring teardown photos in `ATC_RF03_Ring` for pad geometry. Bring out ~4–5 wires (SWCLK, SWDIO, GND, RESET, and VCC if powering externally).

**Step 1 — Wire the reader.** ST-Link/J-Link or the ESP32 SWD reader → SWCLK/SWDIO/GND (+RESET for connect-under-reset). Power the board from its own battery/USB; share GND with the reader.

**Step 2 — Dump both regions** (do not assume the key is in one place):
- `0x00000000`, length `0x20000` — ROM / BLE stack.
- `0x00800000`, length `0x80000` — external/embedded flash (application, OTA staging, likely the decrypt routine + any stored key).

pyOCD sketch (adjust target/pack for RF03; use a generic Cortex-M0+ target if no pack):
```bash
pyocd cmd -t cortex_m
# in the shell:  savemem 0x0        0x20000  rom.bin
#                savemem 0x800000   0x80000  flash.bin
```
OpenOCD alternative: a generic `cortex_m` config + `dump_image rom.bin 0x0 0x20000` / `dump_image flash.bin 0x800000 0x80000`. If SWD is muxed, switch to the UART2AHB or UART0-ISP reader from the atc1441 repo.

**Step 3 — Load into Ghidra.**
- Language: **ARM Cortex, little-endian, `Cortex` variant (ARMv6-M / Thumb)**.
- Load `flash.bin` at base `0x00800000` and `rom.bin` at `0x0` (two program blocks, or two projects cross-referenced). Set the vector table so Ghidra resolves the reset handler and picks up Thumb entry points.
- If a BlueX SVD/peripheral map is available from `BXMicro/SDK3`, import it for register names.

**Step 4 — Locate the OTA decrypt routine.** Search strategies, roughly in order of yield:
1. **The DFU receive/flash handler.** Find the code that consumes the `0xBC` BLE DFU opcode (service `de5bf728`) and the `checkTheData`/CRC16 verification (mirror of `DfuHandle`), then follow where the received 1024-byte packets are written — the decrypt happens between "packet received" and "written to flash / executed."
2. **Constant hunts:** references to the header magic `0xe5c3bd81`; the `shl1` pattern (`<<1` then `^` in a per-byte loop); a 32-byte / 256-bit key buffer (look for a 32-byte `.data`/`.rodata` blob referenced by the decrypt loop); a decompressor signature (LZ-style: match/length loops, a ring buffer, or a known magic if not custom).
3. **Cross-reference the checksum:** the additive byte-sum + CRC16 verification is distinctive; the decrypt routine is adjacent in the call graph.
4. **Headless triage** (reuse the existing Ghidra setup, see `hardware_hacking_log.md` §5): run a script that lists functions with tight byte-loops containing shift+XOR, and functions that reference a 32-byte constant. Command template:
   ```powershell
   & "<home>\Downloads\ghidra_12.1.2_PUBLIC_20260605\ghidra_12.1.2_PUBLIC\support\analyzeHeadless.bat" `
     "<project_dir>" "rf03_proj" -import flash.bin -processor "ARM:LE:32:Cortex" `
     -scriptPath "<clean_script_dir>" -postScript FindOtaDecrypt.java "<out.txt>"
   ```
   (Keep `-scriptPath` to a folder containing ONLY the Ghidra script — Ghidra compiles every `.java` in it. Close the Ghidra GUI first to release the project lock. Both gotchas are documented in `hardware_hacking_log.md`.)

**Step 5 — Extract, reproduce, validate.**
- Pull the 32-byte key + the exact transform (scramble order, XOR/rotate, compression algo) from the decompiled routine.
- Reimplement it in Python next to `hardware_research/am01cy_crypto/descramble_xor.py`.
- **Validation oracle:** the header's additive checksum @`0x0c` is the plain byte-sum of the stored payload, and CRC16 is known — after your decrypt+decompress produces plaintext, a correct result should surface an ARMv6-M vector table (SP in `0x2000_xxxx`, odd Thumb reset handler) and readable strings. That flips the automated scorer in `try_common_keys.py` from "no win" to a clear hit.

### 2.4 Deliverable & follow-on

Once the key + algorithm are reproduced offline: modify the plaintext firmware (e.g. wake-word change), re-compress + re-encrypt with the dumped key, fix the header size/checksum (trivial — additive sum, no signature), and deliver via the **`0xBC` BLE DFU** path (`DfuHandle` / service `de5bf728`) or write flash directly over SWD. See `am01cy-rf03-dump-path` and `glasses-ownership-and-flash`.

### 2.5 Risks / open items

- SWD may be soft-muxed on the glasses (untested) → fall back to UART2AHB / UART0-ISP.
- The decompressor may be a custom/JieLi-style LZ (note the `com/oudmon/ble/base/bluetooth/spp/jieli` package) — budget time to reverse it, not just the XOR layer.
- Re-flashing risk: an incorrect image can brick the MCU. Keep the pristine `test_ota.bin` and both raw dumps as golden restore images before writing anything.

---

## Related
- Memories: `am01cy-bin-crypto`, `am01cy-rf03-dump-path`, `wake-word-not-in-riscv`, `glasses-ownership-and-flash`, `user-goal-own-the-glasses`.
- Docs: `docs/00_Executive_Summaries/ownership_and_wakeword_roadmap.md`, `docs/05_Hardware_Hacking_Findings/hardware_hacking_log.md` (§5 Ghidra headless setup), `docs/05_Hardware_Hacking_Findings/ota_flash_debugging_log.md`.
- Data/scripts: `hardware_research/am01cy_crypto/` (`descramble_xor.py`, `try_common_keys.py`, `try_common_keys_results.txt`, `decode_best.bin`).
