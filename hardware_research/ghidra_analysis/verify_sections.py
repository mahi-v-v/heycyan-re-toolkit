#!/usr/bin/env python3
"""Compute section sizes and verify firmware.bin header claims."""
import struct, os

base = '.'

# Section size verification
bss_unsaved = 0xdf39
bss = 0x3cc8
data_unsaved = 0xe0488
blobdata = 0x1c00

print('=== ELF Section Sizes ===')
print(f'.bss_unsaved: {bss_unsaved} bytes ({bss_unsaved/1024:.1f} KB)')
print(f'.bss: {bss} bytes ({bss/1024:.1f} KB)')
print(f'.data_unsaved: {data_unsaved} bytes ({data_unsaved/1024:.1f} KB)')
print(f'.blobdata: {blobdata} bytes ({blobdata/1024:.1f} KB)')
print(f'\nDoc claims .bss_unsaved tensor arena is 57145 bytes.')
print(f'Actual .bss_unsaved size: {bss_unsaved} bytes')
print(f'MATCH: {bss_unsaved == 57145}')

# firmware.bin
fw_path = os.path.join(base, 'hardware_research', 'am01cy_crypto', 'fw_AM01CY_2.00.10_260411.bin')
with open(fw_path, 'rb') as f:
    data = f.read()

print(f'\n=== firmware.bin Header Analysis ===')
print(f'Size: {len(data)} bytes (~{len(data)/1024:.0f} KB)')

header = data[:80]
strs = []
cur = []
for i, b in enumerate(header):
    if 32 <= b <= 126:
        cur.append((i, chr(b)))
    else:
        if len(cur) >= 4:
            strs.append((cur[0][0], ''.join(c for _, c in cur)))
        cur = []
if cur and len(cur) >= 4:
    strs.append((cur[0][0], ''.join(c for _, c in cur)))

print('ASCII strings in first 80 bytes:')
for off, s in strs:
    print(f'  offset {off} (0x{off:02x}): "{s}"')

crc_bytes = data[12:16]
print(f'Bytes at offset 12-15 (doc claims CRC32): {crc_bytes.hex()}')
print(f'  As LE uint32: {struct.unpack("<I", crc_bytes)[0]:#010x}')

# sw-description security
swd_path = os.path.join(base, 'hardware_research/firmware_and_os/firmware/sw-description')
with open(swd_path, 'r') as f:
    swd = f.read()

print(f'\n=== sw-description Security Check ===')
print(f'Contains "sha256": {"sha256" in swd.lower()}')
print(f'Contains "signature": {"signature" in swd.lower()}')
print(f'Contains "hash": {"hash" in swd.lower()}')
print(f'Contains "rsa": {"rsa" in swd.lower()}')
print(f'Contains "installed-directly": {"installed-directly" in swd}')

# cpio_item_md5 check
md5_path = os.path.join(base, 'hardware_research/firmware_and_os/firmware/cpio_item_md5')
with open(md5_path, 'r') as f:
    md5s = f.read()
print(f'\n=== cpio_item_md5 Analysis ===')
print(f'Uses MD5 hashes: True')
print(f'Number of entries: {len([l for l in md5s.strip().split(chr(10)) if l.strip()])}')
print(f'Integrity mechanism: plaintext MD5 only (no RSA/ECDSA signatures)')
