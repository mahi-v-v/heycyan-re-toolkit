#!/usr/bin/env python3
"""
patch_riscv.py — Wake Word Zeroing-Out Test (Phase 2)

Zeroes out the three highest-priority dense data regions inside the
.data_unsaved segment of the riscv ELF binary, producing riscv_patched.

These regions were identified by scan_dense_regions.py as having:
  - Very low zero density (1.5–3.1%)
  - High entropy (~6.0–6.1 bits/byte)
  - Sizes of 30–31 KB each

They are the strongest candidates for quantized neural-network weight arrays
(the wake word acoustic model).

Usage:
    python3 patch_riscv.py [--region 5,9,16] [--dry-run]

    --region  Comma-separated list of region numbers to zero (1-indexed).
              Defaults to 5,9,16 (the three densest regions).
    --dry-run Print what would be zeroed without writing any file.

Output:
    hardware_research/firmware_and_os/firmware/riscv_patched
"""

import argparse
import hashlib
import math
import os
import sys
import shutil

# ── Binary paths ─────────────────────────────────────────────────────────────
PROJ_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
RISCV_SRC = os.path.join(PROJ_ROOT, 'hardware_research', 'firmware_and_os',
                         'firmware', 'riscv')
RISCV_DST = os.path.join(PROJ_ROOT, 'hardware_research', 'firmware_and_os',
                         'firmware', 'riscv_patched')

# ── Candidate regions from scan_dense_regions.py ─────────────────────────────
# Each entry: (region_number, file_offset_start, file_offset_end, size_KB,
#              avg_zero_pct, avg_entropy, description)
REGIONS = {
    1:  (0x0a1e38, 0x0a2a38,  3.0, 37.0, 4.657,  "Small dense region"),
    2:  (0x105238, 0x105a38,  2.0, 25.1, 4.645,  "Small dense region"),
    3:  (0x107a38, 0x10aa38, 12.0, 11.6, 5.880,  "Medium dense region"),
    4:  (0x10ca38, 0x113638, 27.0, 11.7, 5.580,  "Large medium-density region"),
    5:  (0x11ba38, 0x123638, 31.0,  3.1, 6.067,  "★ HIGH PRIORITY — 31KB, 3.1% zeros, 6.07 entropy"),
    6:  (0x123a38, 0x123e38,  1.0, 16.5, 5.017,  "Small"),
    7:  (0x126a38, 0x126e38,  1.0, 17.7, 4.517,  "Small"),
    8:  (0x129238, 0x12fa38, 26.0, 11.3, 5.256,  "Large medium-density region"),
    9:  (0x138238, 0x13fa38, 30.0,  1.5, 6.131,  "★ HIGH PRIORITY — 30KB, 1.5% zeros, 6.13 entropy"),
    10: (0x13fe38, 0x140238,  1.0, 16.8, 5.225,  "Small"),
    11: (0x141e38, 0x142238,  1.0, 13.1, 4.657,  "Small"),
    12: (0x142638, 0x142a38,  1.0, 13.1, 4.512,  "Small"),
    13: (0x143238, 0x143638,  1.0, 32.1, 5.099,  "Small"),
    14: (0x145638, 0x145a38,  1.0, 39.9, 4.511,  "Small"),
    15: (0x145e38, 0x14c238, 25.0,  9.9, 5.726,  "Large medium-density region"),
    16: (0x154638, 0x15c238, 31.0,  2.9, 6.034,  "★ HIGH PRIORITY — 31KB, 2.9% zeros, 6.03 entropy"),
    17: (0x15c638, 0x15ca38,  1.0, 10.7, 5.125,  "Small"),
    18: (0x15e238, 0x15e638,  1.0, 13.1, 4.712,  "Small"),
    19: (0x15ee38, 0x15f238,  1.0, 15.9, 4.503,  "Small"),
    20: (0x15fa38, 0x160e38,  5.0, 22.5, 5.396,  "Medium"),
    21: (0x161e38, 0x162238,  1.0, 26.8, 4.987,  "Small"),
    22: (0x162e38, 0x164638,  6.0,  7.7, 6.432,  "Medium high-entropy"),
    23: (0x164a38, 0x165238,  2.0, 15.0, 4.611,  "Small"),
    24: (0x165638, 0x166e38,  6.0, 19.9, 5.064,  "Medium"),
    25: (0x168a38, 0x16ba38, 12.0, 17.5, 5.870,  "Medium dense region"),
    26: (0x16be38, 0x16de38,  8.0,  6.5, 5.531,  "Medium"),
    27: (0x16e238, 0x16fa38,  6.0,  0.3, 5.605,  "Near-zero zeros — ISP/audio tables?"),
    28: (0x170238, 0x172638,  9.0,  8.2, 5.804,  "Medium"),
    29: (0x178238, 0x178a38,  2.0, 13.1, 4.674,  "Small"),
    30: (0x17a238, 0x17ca38, 10.0,  6.2, 6.196,  "Medium high-entropy"),
    31: (0x17d638, 0x17e638,  4.0,  6.6, 5.999,  "Medium"),
}

DEFAULT_TARGETS = [5, 9, 16]  # The three densest ~30KB blocks


def entropy(data: bytes) -> float:
    if not data:
        return 0.0
    freq = [0] * 256
    for b in data:
        freq[b] += 1
    n = len(data)
    return -sum((c / n) * math.log2(c / n) for c in freq if c > 0)


def md5hex(data: bytes) -> str:
    return hashlib.md5(data).hexdigest()


def patch(regions_to_zero: list[int], dry_run: bool = False) -> None:
    print(f"\n{'[DRY RUN] ' if dry_run else ''}Wake Word Zeroing-Out Patch")
    print(f"Source  : {RISCV_SRC}")
    print(f"Output  : {RISCV_DST}")
    print(f"Regions : {regions_to_zero}\n")

    if not os.path.exists(RISCV_SRC):
        print(f"ERROR: Source binary not found at {RISCV_SRC}")
        sys.exit(1)

    with open(RISCV_SRC, 'rb') as f:
        original = bytearray(f.read())

    print(f"Original size  : {len(original):,} bytes")
    print(f"Original MD5   : {md5hex(original)}")
    print()

    total_zeroed = 0
    for rn in regions_to_zero:
        if rn not in REGIONS:
            print(f"WARNING: Region {rn} not in catalogue, skipping.")
            continue
        start, end, size_kb, zero_pct, ent, desc = REGIONS[rn]
        byte_count = end - start
        total_zeroed += byte_count

        # Sanity checks
        if end > len(original):
            print(f"ERROR: Region {rn} end offset {hex(end)} exceeds binary size {hex(len(original))}")
            sys.exit(1)

        before_ent = entropy(bytes(original[start:end]))
        print(f"  Zeroing Region {rn:2d}: file [{hex(start)} – {hex(end)}]  "
              f"{byte_count:6,} bytes  entropy before={before_ent:.3f}  ({desc})")

        if not dry_run:
            original[start:end] = bytes(byte_count)  # zero out

    if dry_run:
        print(f"\n[DRY RUN] Would have zeroed {total_zeroed:,} bytes across "
              f"{len(regions_to_zero)} region(s). No file written.")
        return

    patched = bytes(original)

    # Verify: entropy of zeroed regions should now be 0
    print()
    for rn in regions_to_zero:
        if rn not in REGIONS:
            continue
        start, end, *_ = REGIONS[rn]
        after_ent = entropy(patched[start:end])
        ok = "✓" if after_ent == 0.0 else "✗ (NOT ZEROED!)"
        print(f"  Region {rn:2d} post-patch entropy: {after_ent:.4f}  {ok}")

    # Write output
    with open(RISCV_DST, 'wb') as f:
        f.write(patched)

    patched_md5 = md5hex(patched)
    print(f"\nPatched size   : {len(patched):,} bytes  (must match original!)")
    print(f"Size match     : {'✓ OK' if len(patched) == len(original) else '✗ MISMATCH — fatal'}")
    print(f"Patched MD5    : {patched_md5}")
    print(f"Total zeroed   : {total_zeroed:,} bytes ({total_zeroed / 1024:.1f} KB)")
    print(f"\nOutput written : {RISCV_DST}")

    # Verify still a valid ELF
    with open(RISCV_DST, 'rb') as f:
        magic = f.read(4)
    elf_ok = magic == b'\x7fELF'
    print(f"ELF magic      : {'✓ valid' if elf_ok else '✗ CORRUPTED'}")
    if not elf_ok:
        print("FATAL: ELF magic corrupted — do not flash this binary!")
        sys.exit(1)

    print("\nNext step: run repackage_swu.py to rebuild firmware_patched.swu")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--region',
                    default=','.join(str(r) for r in DEFAULT_TARGETS),
                    help='Comma-separated region numbers to zero (default: 5,9,16)')
    ap.add_argument('--dry-run', action='store_true',
                    help='Print what would happen without writing any file')
    args = ap.parse_args()

    targets = [int(x.strip()) for x in args.region.split(',')]
    patch(targets, dry_run=args.dry_run)


if __name__ == '__main__':
    main()
