#!/usr/bin/env python3
"""
build_minimal_swu.py — build a MINIMAL riscv-only .swu.

Why: the glasses' OTA daemon (`ai_glass_ota`) flashes whatever it downloaded with
NO completeness check and a 5 s no-resume receive timeout, so a 20 MB transfer over
the flaky phone->glasses link truncates and swupdate fails at 29% / 55%. The ONLY
image we actually patched is `riscv` (the wake-word coprocessor); kernel/rootfs/
user/boot0 are byte-identical to stock. So we ship a tiny .swu containing only the
patched `riscv` (+ `riscv_sdnand`, its identical twin for the sdnand boot path).

Result: ~3.2 MB instead of ~20 MB — transfers in a couple of seconds (well within
the 5 s timeout) so it can't truncate; and it writes ONLY the coprocessor partition
(mtdblock4 / mmcblk0p7), never the mounted rootfs, so it also can't brick the SoC.

The minimal `sw-description` keeps BOTH selectors (the daemon picks nor/sdnand by
boot media) but lists only the coprocessor image under each, with NO preinstall/
postinstall scripts and NO bootenv changes (no boot-register poke, no migration).

Source of truth: the verified `firmware_patched.swu`. We reuse its exact CPIO
headers (magic 070702 newc-CRC; `check` = arithmetic byte sum) for the reused
members, and only rebuild headers for the two text members we change.

Usage:  python build_minimal_swu.py
Output: hardware_research/firmware_and_os/firmware_riscv_only.swu
"""
import hashlib
import io
import os
import sys

PROJ = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
SWU_SRC = os.path.join(PROJ, 'hardware_research', 'firmware_and_os', 'firmware_patched.swu')
SWU_DST = os.path.join(PROJ, 'hardware_research', 'firmware_and_os', 'firmware_riscv_only.swu')

HEADER_SIZE = 110
PATCHED_RISCV_MD5 = 'c533f6fcaea7c7e12c5a0c5b7e007d5e'  # sanity anchor

MINIMAL_SW_DESCRIPTION = """software =
{
    version = "0.1.0";
    description = "Firmware update for Tina Project (riscv coprocessor only)";

    stable = {

        nor = {
            images: (
                {
                    filename = "riscv";
                    device = "/dev/mtdblock4";
                    installed-directly = true;
                }
            );
        };

        sdnand = {
            images: (
                {
                    filename = "riscv_sdnand";
                    device = "/dev/mmcblk0p7";
                    installed-directly = true;
                }
            );
        };
    };
}
"""


def pad4(n):
    return (n + 3) & ~3


def arith(data):
    return sum(data) & 0xffffffff


def parse(swu_bytes):
    """Parse a CPIO newc/crc archive into ordered members."""
    s = io.BytesIO(swu_bytes)
    members = []
    while True:
        raw = s.read(HEADER_SIZE)
        if len(raw) < HEADER_SIZE:
            break
        if raw[:6] not in (b'070701', b'070702'):
            raise ValueError(f"bad magic {raw[:6]!r} at {s.tell() - HEADER_SIZE}")
        namesize = int(raw[94:102], 16)
        filesize = int(raw[54:62], 16)
        name_raw = s.read(namesize)
        name = name_raw.rstrip(b'\x00').decode('utf-8', 'replace')
        s.read(pad4(HEADER_SIZE + namesize) - (HEADER_SIZE + namesize))
        data = s.read(filesize)
        s.read(pad4(filesize) - filesize)
        members.append({'raw': raw, 'name': name, 'name_raw': name_raw, 'data': data})
        if name == 'TRAILER!!!':
            break
    return members


def header_with(raw, filesize, check):
    """Copy a 110-byte header, overriding only filesize (54:62) and check (102:110)."""
    return raw[:54] + f"{filesize:08X}".encode() + raw[62:102] + f"{check:08X}".encode()


def emit(members):
    out = io.BytesIO()
    for m in members:
        name_raw = m['name_raw']
        data = m['data']
        namesize = len(name_raw)
        out.write(m['raw'])
        out.write(name_raw)
        out.write(b'\x00' * (pad4(HEADER_SIZE + namesize) - (HEADER_SIZE + namesize)))
        out.write(data)
        out.write(b'\x00' * (pad4(len(data)) - len(data)))
    return out.getvalue()


def main():
    src = open(SWU_SRC, 'rb').read()
    members = {m['name']: m for m in parse(src)}

    for req in ('sw-description', 'riscv', 'riscv_sdnand', 'cpio_item_md5', 'TRAILER!!!'):
        if req not in members:
            print(f"ERROR: source .swu missing member {req!r}")
            sys.exit(1)

    riscv = members['riscv']
    riscv_sdnand = members['riscv_sdnand']
    got = hashlib.md5(riscv['data']).hexdigest()
    if got != PATCHED_RISCV_MD5:
        print(f"ERROR: source riscv md5 {got} != expected patched {PATCHED_RISCV_MD5}")
        print("       Is firmware_patched.swu actually the patched image? Aborting.")
        sys.exit(1)
    if riscv['data'] != riscv_sdnand['data']:
        print("WARNING: riscv and riscv_sdnand differ in source (expected identical).")

    # New sw-description member (reuse original header template).
    sd_text = MINIMAL_SW_DESCRIPTION.encode('utf-8')
    sd = members['sw-description']
    sd_new = {
        'name': sd['name'], 'name_raw': sd['name_raw'], 'data': sd_text,
        'raw': header_with(sd['raw'], len(sd_text), arith(sd_text)),
    }

    # New cpio_item_md5 member (only the two images we ship).
    md5_text = (f"{PATCHED_RISCV_MD5}  riscv\n"
                f"{PATCHED_RISCV_MD5}  riscv_sdnand\n").encode('utf-8')
    m5 = members['cpio_item_md5']
    m5_new = {
        'name': m5['name'], 'name_raw': m5['name_raw'], 'data': md5_text,
        'raw': header_with(m5['raw'], len(md5_text), arith(md5_text)),
    }

    out_members = [sd_new, riscv, riscv_sdnand, m5_new, members['TRAILER!!!']]
    result = emit(out_members)

    with open(SWU_DST, 'wb') as f:
        f.write(result)

    print(f"Built: {SWU_DST}")
    print(f"Size : {len(result):,} bytes  (was {len(src):,})")
    print(f"MD5  : {hashlib.md5(result).hexdigest()}")
    print("Members: " + ", ".join(m['name'] for m in out_members))


if __name__ == '__main__':
    main()
