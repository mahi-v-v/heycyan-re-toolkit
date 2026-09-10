import zlib, gzip, lzma, bz2, io, struct, math

full = open('fw_AM01CY_2.00.10_260411.bin','rb').read()
payload = open('payload_wf.bin','rb').read()

def entropy(data):
    if not data: return 0
    from collections import Counter
    c = Counter(data)
    n = len(data)
    return -sum((v/n)*math.log2(v/n) for v in c.values())

print("payload entropy (full):", round(entropy(payload),4))
print("payload entropy first 64KB:", round(entropy(payload[:65536]),4))
print("payload entropy last 64KB:", round(entropy(payload[-65536:]),4))

# chi-square style: byte histogram flatness
from collections import Counter
c = Counter(payload)
n = len(payload); exp = n/256
chi = sum((c.get(b,0)-exp)**2/exp for b in range(256))
print("chi-square (256 bins, flat~255):", round(chi,1), " min/max count:", min(c.values()), max(c.values()))

def try_all(data, label):
    results = []
    # raw deflate
    for wbits, tag in [(-15,'raw_deflate'), (15,'zlib_hdr'), (31,'gzip'), (47,'auto')]:
        try:
            d = zlib.decompressobj(wbits)
            out = d.decompress(data, 200000)
            if len(out) > 32:
                results.append((tag, len(out), out[:32]))
        except Exception as e:
            pass
    # lzma
    for fmt, tag in [(lzma.FORMAT_AUTO,'lzma_auto'), (lzma.FORMAT_ALONE,'lzma_alone'), (lzma.FORMAT_XZ,'xz'), (lzma.FORMAT_RAW,'lzma_raw')]:
        try:
            if fmt == lzma.FORMAT_RAW:
                filt=[{"id":lzma.FILTER_LZMA2,"preset":6}]
                d = lzma.LZMADecompressor(format=fmt, filters=filt)
            else:
                d = lzma.LZMADecompressor(format=fmt)
            out = d.decompress(data, 200000)
            if len(out) > 32:
                results.append((tag, len(out), out[:32]))
        except Exception:
            pass
    # bz2
    try:
        d = bz2.BZ2Decompressor()
        out = d.decompress(data, 200000)
        if len(out) > 32:
            results.append(('bz2', len(out), out[:32]))
    except Exception:
        pass
    if results:
        print(f"\n[{label}] DECOMPRESS SUCCESS:")
        for tag,ln,pre in results:
            print(f"    {tag}: outlen={ln} first32={pre.hex()}  ascii={pre.decode('latin1').encode('ascii','replace').decode()}")
        return True
    return False

# Try optional modules
try:
    import lz4.frame, lz4.block
    HAVE_LZ4=True
except Exception:
    HAVE_LZ4=False
try:
    import zstandard
    HAVE_ZSTD=True
except Exception:
    HAVE_ZSTD=False
try:
    import brotli
    HAVE_BROTLI=True
except Exception:
    HAVE_BROTLI=False
print("optional modules: lz4=%s zstd=%s brotli=%s" % (HAVE_LZ4, HAVE_ZSTD, HAVE_BROTLI))

print("\n=== Try at payload offset 0 (file 0x50) ===")
try_all(payload, "payload@0")

print("\n=== Try at file offset 0 (with header) ===")
try_all(full, "full@0")

# Sweep offsets 0..256 in payload
print("\n=== SWEEP payload offsets 0..512 (any success prints) ===")
any_sweep=False
for off in range(0,512):
    if try_all(payload[off:off+4096], f"payload@{off}"):
        any_sweep=True
if not any_sweep:
    print("  NO decompression success anywhere in 0..512 sweep")

# Try at each zlib-candidate offset from the magic scan
zlib_offs = [25415,174937,200184,208758,264200,420178,247616,396790,776564,342282,459611,41995,51868]
print("\n=== Try inflate at each zlib-magic candidate offset ===")
any_z=False
for off in zlib_offs:
    for wbits,tag in [(15,'zlib'),(-15,'raw')]:
        try:
            d=zlib.decompressobj(wbits)
            out=d.decompress(payload[off:], 100000)
            if len(out)>64:
                print(f"  off {off} {tag}: outlen={len(out)} first={out[:32].hex()}")
                any_z=True
        except Exception:
            pass
if not any_z:
    print("  NO zlib candidate produced >64 bytes of output")
