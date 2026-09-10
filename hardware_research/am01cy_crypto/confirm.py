import lzma, math
from collections import Counter
payload = open('payload_wf.bin','rb').read()

def entropy(data):
    if not data: return 0
    c=Counter(data); n=len(data)
    return -sum((v/n)*math.log2(v/n) for v in c.values())

# Decode a large chunk with lzma_raw to see if output is structured or garbage
filt=[{"id":lzma.FILTER_LZMA2,"preset":6}]
d=lzma.LZMADecompressor(format=lzma.FORMAT_RAW, filters=filt)
try:
    out=d.decompress(payload[96:96+200000], 500000)
    print("lzma_raw@96 output len:", len(out))
    print("  output entropy:", round(entropy(out),4))
    print("  first 48 bytes:", out[:48].hex())
    # count printable ascii
    printable=sum(1 for b in out if 32<=b<127)
    print("  printable ascii ratio:", round(printable/len(out),3))
    # longest ascii run
    best=cur=0
    for b in out:
        if 32<=b<127: cur+=1; best=max(best,cur)
        else: cur=0
    print("  longest ascii run:", best)
except Exception as e:
    print("lzma_raw large decode error:", e)

# ASCII string search in raw payload: any run >= 6 printable chars?
runs=[]
cur=b''
for b in payload:
    if 32<=b<127:
        cur+=bytes([b])
    else:
        if len(cur)>=6: runs.append(cur)
        cur=b''
print("\nASCII runs >=6 chars in raw payload:", len(runs))
for r in runs[:15]:
    print("   ", r.decode('latin1'))

# Byte-value coverage: are all 256 values present? (compression & encryption both yes)
c=Counter(payload)
print("\ndistinct byte values present:", len(c), "of 256")

# Look at the LAST bytes of payload (compressors leave trailing checksums/padding; ciphers just end)
print("last 32 bytes:", payload[-32:].hex())
