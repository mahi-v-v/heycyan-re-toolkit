import sys, struct

full = open('fw_AM01CY_2.00.10_260411.bin','rb').read()
payload = open('payload_wf.bin','rb').read()

print("=== SIZES ===")
print("full", len(full), "payload", len(payload))
print("payload first16", payload[:16].hex())

# Magic signatures: name -> bytes
magics = {
    'gzip':        bytes.fromhex('1f8b08'),
    'zlib_78_01':  bytes.fromhex('7801'),
    'zlib_78_9c':  bytes.fromhex('789c'),
    'zlib_78_da':  bytes.fromhex('78da'),
    'zlib_78_5e':  bytes.fromhex('785e'),
    'xz':          bytes.fromhex('fd377a585a00'),
    'lzma_alone':  bytes.fromhex('5d0000'),
    'lz4_frame':   bytes.fromhex('04224d18'),
    'lz4_legacy':  bytes.fromhex('02214c18'),
    'bzip2':       bytes.fromhex('425a68'),
    'zstd':        bytes.fromhex('28b52ffd'),
    'lzop':        bytes.fromhex('894c5a4f'),
    'brotli?none': b'',  # brotli has no magic
    'uImage':      bytes.fromhex('27051956'),
    'android_sparse': bytes.fromhex('3aff26ed'),
    'cramfs_le':   bytes.fromhex('453dcd28'),
    'cramfs_be':   bytes.fromhex('28cd3d45'),
    'squashfs_le': b'hsqs',
    'squashfs_be': b'sqsh',
    'jffs2_le':    bytes.fromhex('1985'),
    'ubi':         b'UBI#',
    'ubifs':       bytes.fromhex('31181006'),
    'elf':         bytes.fromhex('7f454c46'),
    'ext_magic':   bytes.fromhex('53ef'),  # at 0x438 within sb
}

def scan(name, data):
    print(f"\n=== MAGIC SCAN: {name} (len {len(data)}) ===")
    anyhit=False
    for mname, mg in magics.items():
        if not mg:
            continue
        off = 0
        hits=[]
        while True:
            i = data.find(mg, off)
            if i < 0: break
            hits.append(i)
            off = i+1
            if len(hits) > 20: break
        if hits:
            anyhit=True
            # For very common short magics, report count + first few offsets
            print(f"  {mname:16s} count={len(hits):>3} offsets(first up to 12): {hits[:12]}")
    if not anyhit:
        print("  NO magic hits at all")

scan("FULL FILE", full)
scan("PAYLOAD", payload)
