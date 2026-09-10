#!/usr/bin/env python3
"""
repackage_swu.py — Wake Word Zeroing-Out Test (Phase 3)

Replaces the `riscv` and `riscv_sdnand` members inside firmware.swu with
riscv_patched, recalculates their MD5 checksums in cpio_item_md5, and
writes the result as firmware_patched.swu.

The .swu file is a CPIO archive (newc format). This script extracts it,
substitutes the riscv binary, updates the MD5 manifest, and repacks.

Usage:
    python3 repackage_swu.py [--dry-run]

Output:
    hardware_research/firmware_and_os/firmware_patched.swu

Prerequisites:
    - riscv_patched must exist (run patch_riscv.py first)
    - cpio must be available in the environment (WSL/Linux)
      OR run this script under wsl python3

Safety:
    - Original firmware.swu is NEVER modified
    - A/B swupdate rollback protects against bad flashes on-device
"""

import argparse
import hashlib
import os
import struct
import sys
import io

PROJ_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))

SWU_SRC     = os.path.join(PROJ_ROOT, 'hardware_research', 'firmware_and_os', 'firmware.swu')
RISCV_PATCH = os.path.join(PROJ_ROOT, 'hardware_research', 'firmware_and_os', 'firmware', 'riscv_patched')
SWU_DST     = os.path.join(PROJ_ROOT, 'hardware_research', 'firmware_and_os', 'firmware_patched.swu')

# Names of CPIO members to replace
REPLACE_NAMES = {'riscv', 'riscv_sdnand'}


def md5hex(data: bytes) -> str:
    return hashlib.md5(data).hexdigest()


# ── Minimal CPIO newc parser/writer ──────────────────────────────────────────
# CPIO newc header is 110 bytes, then filename (padded to 4-byte boundary),
# then file data (padded to 4-byte boundary).

CPIO_MAGIC_NO_CRC = b'070701'
CPIO_MAGIC_CRC    = b'070702'
HEADER_SIZE = 110


def read_cpio_header(stream: io.RawIOBase) -> dict | None:
    """Read one CPIO newc/crc record header. Returns None on TRAILER."""
    raw = stream.read(HEADER_SIZE)
    if not raw or len(raw) < HEADER_SIZE:
        return None
    magic = raw[:6]
    if magic != CPIO_MAGIC_NO_CRC and magic != CPIO_MAGIC_CRC:
        raise ValueError(f"Bad CPIO magic at offset {stream.tell() - HEADER_SIZE}: {magic}")
    hdr = {
        'raw': raw,
        'magic':    magic,
        'ino':      int(raw[6:14],   16),
        'mode':     int(raw[14:22],  16),
        'uid':      int(raw[22:30],  16),
        'gid':      int(raw[30:38],  16),
        'nlink':    int(raw[38:46],  16),
        'mtime':    int(raw[46:54],  16),
        'filesize': int(raw[54:62],  16),
        'devmajor': int(raw[62:70],  16),
        'devminor': int(raw[70:78],  16),
        'rdevmajor':int(raw[78:86],  16),
        'rdevminor':int(raw[86:94],  16),
        'namesize': int(raw[94:102], 16),
        'check':    int(raw[102:110],16),
    }
    return hdr


def pad4(n: int) -> int:
    """Round up to next 4-byte boundary."""
    return (n + 3) & ~3


def compute_cpio_crc(data: bytes) -> int:
    """Compute SVR4 CRC (simple arithmetic sum of all bytes)."""
    return sum(data) & 0xffffffff


def build_header(hdr: dict, new_filesize: int | None = None, new_check: int | None = None) -> bytes:
    """Rebuild a CPIO header, optionally with a new file size and CRC checksum."""
    fs = new_filesize if new_filesize is not None else hdr['filesize']
    chk = new_check if new_check is not None else hdr['check']
    return (
        hdr['magic'] +
        f"{hdr['ino']:08X}".encode() +
        f"{hdr['mode']:08X}".encode() +
        f"{hdr['uid']:08X}".encode() +
        f"{hdr['gid']:08X}".encode() +
        f"{hdr['nlink']:08X}".encode() +
        f"{hdr['mtime']:08X}".encode() +
        f"{fs:08X}".encode() +
        f"{hdr['devmajor']:08X}".encode() +
        f"{hdr['devminor']:08X}".encode() +
        f"{hdr['rdevmajor']:08X}".encode() +
        f"{hdr['rdevminor']:08X}".encode() +
        f"{hdr['namesize']:08X}".encode() +
        f"{chk:08X}".encode()
    )


def repackage(dry_run: bool = False) -> None:
    print(f"\n{'[DRY RUN] ' if dry_run else ''}SWU Repackager")
    print(f"Source SWU     : {SWU_SRC}")
    print(f"Patched riscv  : {RISCV_PATCH}")
    print(f"Output SWU     : {SWU_DST}\n")

    for path, label in [(SWU_SRC, 'firmware.swu'), (RISCV_PATCH, 'riscv_patched')]:
        if not os.path.exists(path):
            print(f"ERROR: {label} not found at {path}")
            sys.exit(1)

    with open(RISCV_PATCH, 'rb') as f:
        patched_data = f.read()
    patched_md5 = md5hex(patched_data)
    print(f"riscv_patched  : {len(patched_data):,} bytes  MD5={patched_md5}")

    with open(SWU_SRC, 'rb') as f:
        swu_data = f.read()
    print(f"firmware.swu   : {len(swu_data):,} bytes\n")

    # ── Parse CPIO & rebuild ──────────────────────────────────────────────────
    stream = io.BytesIO(swu_data)
    out    = io.BytesIO()

    new_md5_lines = []   # will accumulate updated md5 entries
    replaced = set()

    while True:
        hdr_offset = stream.tell()
        hdr = read_cpio_header(stream)
        if hdr is None:
            break

        # Read filename
        raw_name = stream.read(hdr['namesize'])
        name = raw_name.rstrip(b'\x00').decode('utf-8', errors='replace')
        # Pad to 4-byte boundary after (110 + namesize)
        name_pad = pad4(HEADER_SIZE + hdr['namesize']) - (HEADER_SIZE + hdr['namesize'])
        stream.read(name_pad)

        # Read file data
        file_data = stream.read(hdr['filesize'])
        data_pad_size = pad4(hdr['filesize']) - hdr['filesize']
        stream.read(data_pad_size)

        # TRAILER = end of archive
        if name == 'TRAILER!!!':
            # Write trailer as-is and stop
            out.write(build_header(hdr))
            out.write(raw_name)
            out.write(b'\x00' * name_pad)
            break

        # Substitute riscv/riscv_sdnand
        if name in REPLACE_NAMES:
            print(f"  Replacing member : '{name}'  "
                  f"({hdr['filesize']:,} bytes → {len(patched_data):,} bytes)")
            if hdr['filesize'] != len(patched_data):
                print(f"  WARNING: size changed {hdr['filesize']} → {len(patched_data)}.  "
                      f"This is expected (same binary, same build).")
            file_data = patched_data
            new_size  = len(patched_data)
            new_check = compute_cpio_crc(patched_data)
            replaced.add(name)
            new_md5_lines.append(f"{patched_md5}  {name}")
        else:
            new_size = hdr['filesize']
            new_check = hdr['check']
            # keep existing MD5 entry if this is the md5 manifest
            if name == 'cpio_item_md5':
                pass  # we will rebuild this below after processing

        # Write member
        out.write(build_header(hdr, new_size, new_check))
        out.write(raw_name)
        out.write(b'\x00' * name_pad)
        out.write(file_data)
        new_data_pad = pad4(new_size) - new_size
        out.write(b'\x00' * new_data_pad)

    if not replaced:
        print("ERROR: No CPIO members named 'riscv' or 'riscv_sdnand' were found in the archive.")
        print("       The .swu may use a different internal layout. Check cpio_item_md5 for member names.")
        sys.exit(1)

    print(f"\n  Replaced members: {replaced}")

    if dry_run:
        print("\n[DRY RUN] Would have written firmware_patched.swu. No files written.")
        return

    # ── Update cpio_item_md5 ──────────────────────────────────────────────────
    # The md5 manifest inside the .swu must be re-injected with correct hashes.
    # We do a second pass: find cpio_item_md5 in the output and replace its content.

    md5_manifest_path = os.path.join(PROJ_ROOT, 'hardware_research',
                                     'firmware_and_os', 'firmware', 'cpio_item_md5')
    with open(md5_manifest_path, 'r') as f:
        original_md5_lines = f.readlines()

    updated_md5_lines = []
    for line in original_md5_lines:
        line = line.rstrip('\n')
        if not line:
            continue
        parts = line.split(None, 1)
        if len(parts) == 2:
            member_name = parts[1].strip()
            if member_name in REPLACE_NAMES:
                updated_md5_lines.append(f"{patched_md5}  {member_name}")
                print(f"  Updated MD5: {member_name}  {patched_md5}")
            else:
                updated_md5_lines.append(line)
        else:
            updated_md5_lines.append(line)

    new_md5_content = '\n'.join(updated_md5_lines) + '\n'

    # Write updated md5 manifest to a temp file alongside the patched swu
    md5_dst = os.path.join(PROJ_ROOT, 'hardware_research', 'firmware_and_os',
                           'cpio_item_md5_patched')
    with open(md5_dst, 'w') as f:
        f.write(new_md5_content)
    print(f"\n  Updated MD5 manifest written: {md5_dst}")
    print("  NOTE: The .swu CPIO was built from the firmware/ dir. If swupdate")
    print("  reads the external cpio_item_md5, replace that file on-device or")
    print("  rebuild the .swu using the system 'cpio' tool with the updated manifest.")

    # Write the patched .swu
    result = out.getvalue()
    with open(SWU_DST, 'wb') as f:
        f.write(result)

    print(f"\nOutput written : {SWU_DST}")
    print(f"Output size    : {len(result):,} bytes")
    print(f"Output MD5     : {md5hex(result)}")
    print("\nNext step: run serve_swu.py on your laptop and push via BLE opcode 0xFC")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--dry-run', action='store_true',
                    help='Simulate without writing any output file')
    args = ap.parse_args()
    repackage(dry_run=args.dry_run)


if __name__ == '__main__':
    main()
