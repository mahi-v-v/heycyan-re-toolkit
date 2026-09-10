#!/usr/bin/env python
"""
AM01CY firmware.bin analysis pipeline.

Layer model (recovered by workflows wghiybaw0 / wyelmuep3):
  file = 0x50-byte plaintext header + payload
  payload = OUTER self-synchronizing linear scrambler over INNER strong block cipher
  OUTER descramble (verified, reproducible):  p[0]=c[0];  p[i] = c[i] ^ ((c[i-1] << 1) & 0xFF)

Usage:
  python descramble_xor.py FILE.bin                 # descramble one file, report stats
  python descramble_xor.py FILE_A.bin FILE_B.bin    # the killer 2-version XOR test

The 2-version test: if the INNER cipher is a stream/CTR cipher with a REUSED keystream
across versions, then descramble(A) ^ descramble(B) == P_A ^ P_B, and regions where the
firmware is unchanged (P_A == P_B) show up as long 0x00 runs. Long zero runs => WIN
(crib-drag to full plaintext). No zero runs => keystream not reused => path dead, go hardware.
"""
import sys, math, collections

HDR = 0x50

def entropy(b):
    if not b: return 0.0
    c = collections.Counter(b); n = len(b)
    return -sum((v/n) * math.log2(v/n) for v in c.values())

def parse(path):
    d = open(path, "rb").read()
    magic = d[0:4].hex()
    size1 = int.from_bytes(d[4:8], "little")
    size2 = int.from_bytes(d[8:12], "little")
    cksum = int.from_bytes(d[12:16], "little")
    ver = d[0x10:0x2c].split(b"\x00")[0].decode("latin1")
    hw  = d[0x30:0x40].split(b"\x00")[0].decode("latin1")
    payload = d[HDR:]
    return dict(path=path, size=len(d), magic=magic, size1=size1, size2=size2,
                cksum=cksum, ver=ver, hw=hw, payload=payload)

def descramble(c):
    out = bytearray(len(c))
    prev = 0
    for i, ci in enumerate(c):
        out[i] = ci ^ ((prev << 1) & 0xFF)
        prev = ci
    return bytes(out)

def sum32(b):
    return sum(b) & 0xFFFFFFFF

def zero_run_stats(b):
    longest = cur = 0; total_zero = 0; runs = []
    for x in b:
        if x == 0:
            cur += 1; total_zero += 1
        else:
            if cur: runs.append(cur)
            cur = 0
    if cur: runs.append(cur)
    longest = max(runs) if runs else 0
    return dict(zero_frac=total_zero/len(b), longest_run=longest,
                runs_ge8=sum(1 for r in runs if r >= 8),
                runs_ge16=sum(1 for r in runs if r >= 16))

def ecb_dups(b, bs=16):
    seen = collections.Counter(b[i:i+bs] for i in range(0, len(b) - bs + 1, bs))
    return sum(1 for v in seen.values() if v > 1), len(seen)

def report_one(f):
    p = f["payload"]
    ds = descramble(p)
    print(f"  file       : {f['path']}")
    print(f"  version    : {f['ver']}   hw: {f['hw']}   magic: {f['magic']}")
    print(f"  size       : {f['size']}  payload: {len(p)}  size1/2: {f['size1']}/{f['size2']}")
    print(f"  cksum @0x0c: 0x{f['cksum']:08x}   sum32(payload)=0x{sum32(p):08x}  "
          f"{'MATCH' if f['cksum']==sum32(p) else 'no-match'}")
    print(f"  entropy    : raw {entropy(p):.4f}  ->  descrambled {entropy(ds):.4f}")
    dup, tot = ecb_dups(ds)
    print(f"  descr ECB  : {dup} dup / {tot} blocks (16B)   zero-runs: {zero_run_stats(ds)}")
    return ds

def main():
    files = [parse(p) for p in sys.argv[1:]]
    if not files:
        print(__doc__); return
    print("=== per-file ===")
    dss = [report_one(f) for f in files]
    if len(files) < 2:
        print("\n(only one file: supply a 2nd version to run the XOR keystream test)")
        return
    print("\n=== 2-VERSION XOR TEST (descramble both, then XOR) ===")
    a, b = dss[0], dss[1]
    n = min(len(a), len(b))
    x = bytes(a[i] ^ b[i] for i in range(n))
    zr = zero_run_stats(x)
    print(f"  aligned length : {n}")
    print(f"  XOR entropy    : {entropy(x):.4f}   (LOW / <7.9 or long zero-runs => keystream reuse => WIN)")
    print(f"  XOR zero stats : {zr}")
    # also raw-ciphertext XOR for cross-check (linear scrambler => same info)
    ra, rb = files[0]["payload"], files[1]["payload"]
    m = min(len(ra), len(rb))
    rx = bytes(ra[i] ^ rb[i] for i in range(m))
    print(f"  raw-XOR entropy: {entropy(rx):.4f}   zero-frac {zero_run_stats(rx)['zero_frac']:.4f}")
    verdict = ("KEYSTREAM REUSE LIKELY -> crib-drag next" if zr["longest_run"] >= 16 or entropy(x) < 7.5
               else "no cancellation -> inner cipher not reused-keystream; go hardware")
    print(f"\n  VERDICT: {verdict}")

if __name__ == "__main__":
    main()
