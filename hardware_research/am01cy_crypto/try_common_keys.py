#!/usr/bin/env python3
"""
try_common_keys.py  --  Phase A: exhaustive common/weak/default key search against
the AM01CY (BlueX RF03) OTA firmware inner cipher.

Container model (see descramble_xor.py / MEMORY):
  firmware.bin = 0x50 plaintext header + payload (1,029,025 B)
  Recovery model:  stored payload --[outer descramble]--> inner-ciphertext --[inner cipher decrypt]--> plaintext firmware
  outer descramble (verified): p[0]=c[0]; p[i]=c[i]^((c[i-1]<<1)&0xFF)

This harness feeds BOTH the raw payload AND the descrambled payload into a large
matrix of {cipher x mode x IV x key}. Every trial is SCORED for "does this look like
decrypted ARM Cortex-M0+ firmware?" and the top results are ranked.

Success signals scored (higher score = more plausible plaintext):
  * valid ARMv6-M vector table at offset 0 (SP in RAM window, odd handler ptrs in flash)
  * sharp entropy drop in any 4 KB window (< 7.5, ideally < 7.0)  -- strongest generic tell
  * emergence of printable-ASCII runs / long 0x00 padding runs / low distinct-byte count

Usage:  python try_common_keys.py            (runs the full sweep, prints ranked table)
        python try_common_keys.py --full KEYHEX MODE INPUT  (dump a full decrypt of one hit)

Requires pycryptodome (AES/DES/DES3). XTEA/TEA implemented in pure python.
"""
import os, sys, math, hashlib, struct, collections

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
FW   = os.path.join(HERE, "fw_AM01CY_2.00.10_260411.bin")
HDR  = 0x50
SCORE_PREFIX = 0x10000     # bytes of each trial we decrypt+score (fast)
XTEA_PREFIX  = 0x2000      # pure-python ciphers scored on a smaller window

try:
    from Crypto.Cipher import AES, DES, DES3
    HAVE_PYCRYPTO = True
except Exception:
    HAVE_PYCRYPTO = False
    print("!! pycryptodome missing -- AES/DES trials disabled", file=sys.stderr)

# ---------------------------------------------------------------- helpers
def entropy(b):
    if not b: return 0.0
    c = collections.Counter(b); n = len(b)
    return -sum((v/n)*math.log2(v/n) for v in c.values())

def min_window_entropy(b, win=4096):
    if len(b) < win: return entropy(b)
    m = 9.0
    for i in range(0, len(b)-win+1, win):
        e = entropy(b[i:i+win])
        if e < m: m = e
    return m

def descramble(c):
    out = bytearray(len(c)); prev = 0
    for i, ci in enumerate(c):
        out[i] = ci ^ ((prev << 1) & 0xFF); prev = ci
    return bytes(out)

def printable_stats(b):
    best = cur = 0; total = 0
    for x in b:
        if 32 <= x < 127:
            cur += 1; total += 1
            if cur > best: best = cur
        else:
            cur = 0
    return total/len(b), best

def zero_run_stats(b):
    longest = cur = 0
    for x in b:
        if x == 0:
            cur += 1
            if cur > longest: longest = cur
        else:
            cur = 0
    return longest

def vector_table_score(b):
    """Return (score, why) for an ARMv6-M / Cortex-M0+ vector table at offset 0.
    word0 = initial SP (top of RAM). word1..= exception handlers (odd, in flash)."""
    if len(b) < 64: return 0, ""
    words = struct.unpack_from("<16I", b, 0)
    sp = words[0]
    # RF03 SRAM is small; accept the classic 0x2000_xxxx window (and a little above).
    sp_ok = 0x20000000 <= sp <= 0x20020000
    # flash could be mapped at 0x0000_0000 (typical M0+) or 0x0800_0000 (ST-style).
    def flash_ok(v):
        return (v & 1) == 1 and ((0x00000000 <= (v & ~1) <= 0x00200000) or
                                 (0x08000000 <= (v & ~1) <= 0x08200000))
    reset = words[1]
    reset_ok = flash_ok(reset)
    handlers = words[1:16]
    good_h = sum(1 for h in handlers if flash_ok(h))
    score = 0; why = []
    if sp_ok:   score += 200; why.append(f"SP=0x{sp:08x}")
    if reset_ok:score += 200; why.append(f"reset=0x{reset:08x}")
    if good_h >= 4:  score += 60*good_h; why.append(f"{good_h}/15 handlers-in-flash")
    return score, " ".join(why)

def score_plaintext(b):
    """Composite plausibility score for a candidate decrypted firmware prefix."""
    e_min = min_window_entropy(b)
    vscore, vwhy = vector_table_score(b)
    pfrac, prun = printable_stats(b)
    zrun = zero_run_stats(b)
    score = 0.0; why = []
    # entropy drop is the single most reliable generic tell for a successful decrypt
    if e_min < 7.90:
        score += (7.95 - e_min) * 400
        why.append(f"minWinEnt={e_min:.3f}")
    if vscore:
        score += vscore; why.append("VEC[" + vwhy + "]")
    if prun >= 8:
        score += min(prun, 64) * 2; why.append(f"asciiRun={prun}")
    if pfrac > 0.20:
        score += (pfrac-0.20) * 200; why.append(f"printFrac={pfrac:.2f}")
    if zrun >= 8:
        score += min(zrun, 256); why.append(f"zeroRun={zrun}")
    return score, "; ".join(why) if why else "(random-looking)"

# ---------------------------------------------------------------- XTEA / TEA (pure python)
def _u32(x): return x & 0xFFFFFFFF
def xtea_decrypt_block(v0, v1, key, rounds=32, delta=0x9E3779B9):
    total = _u32(delta*rounds); k = key
    for _ in range(rounds):
        v1 = _u32(v1 - (_u32((_u32(v0<<4) ^ (v0>>5)) + v0) ^ _u32(total + k[(total>>11)&3])))
        total = _u32(total - delta)
        v0 = _u32(v0 - (_u32((_u32(v1<<4) ^ (v1>>5)) + v1) ^ _u32(total + k[total&3])))
    return v0, v1
def tea_decrypt_block(v0, v1, key, rounds=32, delta=0x9E3779B9):
    total = _u32(delta*rounds)
    for _ in range(rounds):
        v1 = _u32(v1 - (_u32((_u32(v0<<4)+key[2]) ^ _u32(v0+total) ^ _u32((v0>>5)+key[3]))))
        v0 = _u32(v0 - (_u32((_u32(v1<<4)+key[0]) ^ _u32(v1+total) ^ _u32((v1>>5)+key[1]))))
        total = _u32(total - delta)
    return v0, v1
def block_cipher_decrypt(data, key16, fn, big_endian=False):
    fmt = ">II" if big_endian else "<II"
    out = bytearray()
    k = struct.unpack((">4I" if big_endian else "<4I"), key16)
    for i in range(0, len(data)-7, 8):
        v0, v1 = struct.unpack_from(fmt, data, i)
        d0, d1 = fn(v0, v1, k)
        out += struct.pack(fmt, d0, d1)
    return bytes(out)

# ---------------------------------------------------------------- key candidates
def expand(seed, name):
    """Yield (keybytes, label) for a raw seed at lengths 16/24/32 with several norms."""
    out = []
    seed = bytes(seed)
    for L in (16, 24, 32):
        out.append(((seed + b"\x00"*L)[:L], f"{name}|nullpad{L}"))
        if len(seed):
            rep = (seed * (L//len(seed)+1))[:L]
            out.append((rep, f"{name}|repeat{L}"))
    # hash-derived (common "hash the passphrase" KDFs)
    out.append((hashlib.md5(seed).digest(),            f"{name}|md5"))
    out.append((hashlib.sha256(seed).digest(),         f"{name}|sha256"))
    out.append((hashlib.sha256(seed).digest()[:16],    f"{name}|sha256[:16]"))
    out.append((hashlib.sha1(seed).digest()[:16],      f"{name}|sha1[:16]"))
    return out

def build_keys(fw):
    header = fw[:HDR]
    magic  = fw[0:4]                       # e5 c3 bd 81
    cksum  = fw[12:16]                      # 37 de cf 07
    verstr = fw[0x10:0x2c].split(b"\x00")[0]   # AM01CY_2.00.10_260411
    hwstr  = fw[0x30:0x40].split(b"\x00")[0]   # AM01CY_V2.0
    keys = []                              # list of (keybytes, label)

    # --- fixed byte patterns ---
    keys.append((b"\x00"*16, "zeros16")); keys.append((b"\x00"*24, "zeros24")); keys.append((b"\x00"*32, "zeros32"))
    keys.append((b"\xff"*16, "ff16"));    keys.append((b"\xff"*24, "ff24"));    keys.append((b"\xff"*32, "ff32"))
    keys.append((bytes(range(16)), "incr00-0F"))
    keys.append((bytes(range(32)), "incr00-1F"))
    keys.append((bytes(range(24)), "incr00-17"))
    for v in (0x01, 0x11, 0x55, 0xAA, 0xDE, 0xAD, 0xBE, 0xEF):
        keys.append((bytes([v])*16, f"rep{v:02x}x16"))
        keys.append((bytes([v])*32, f"rep{v:02x}x32"))
    # classic test vectors
    keys.append((bytes.fromhex("000102030405060708090a0b0c0d0e0f"), "nist-000102..0f"))
    keys.append((bytes.fromhex("00112233445566778899aabbccddeeff"), "0011..ff"))

    # --- ASCII strings (raw + normalized + hash) ---
    ascii_seeds = [
        "0000000000000000", "1234567890123456", "0123456789abcdef", "0123456789ABCDEF",
        "abcdefghijklmnop", "AM01CY", "AM01CY_V2.0", "AM01CY_2.00.10", "AM01CY_2.00.10_260411",
        "BlueX", "BLUEX", "bluex", "RF03", "rf03", "bluex_rf03", "BlueX_RF03", "BLUEXRF03",
        "qcwxfactory", "qcwx", "qcwxkjvip", "qlifesnap", "oudmon", "OuDmon", "OUDMON",
        "heycyan", "HeyCyan", "cyan", "Cyan", "CYAN", "heyCyan",
        "changeme", "admin", "password", "Password", "default", "firmware", "Firmware",
        "secret", "12345678", "88888888", "abcdef", "mltcode", "jieli", "glasssutdio",
    ]
    for s in ascii_seeds:
        raw = s.encode()
        # exact-length raw keys too (some vendors use the literal string)
        if len(raw) in (16, 24, 32):
            keys.append((raw, f"str:{s}|exact"))
        keys.extend(expand(raw, f"str:{s}"))

    # --- header-derived material ---
    keys.extend(expand(magic, "magic"))
    keys.append((magic*4, "magic*4=16"))
    keys.extend(expand(cksum, "cksum"))
    keys.append((cksum*4, "cksum*4=16"))
    keys.extend(expand(verstr, "verstr"))
    keys.extend(expand(hwstr, "hwstr"))
    keys.append(((magic+cksum)*2, "magic+cksum*2"))
    keys.append(((header[:16]), "hdr[0:16]"))
    keys.append(((header[0x10:0x20]), "hdr[10:20]"))
    keys.append(((header[0x30:0x40]), "hdr[30:40]"))
    keys.append((magic + cksum + verstr[:8], "magic+cksum+ver8"))

    # de-dup by (bytes,len) keeping first label
    seen = {}
    uniq = []
    for kb, lab in keys:
        if len(kb) not in (16, 24, 32):
            continue
        if kb in seen:
            continue
        seen[kb] = lab
        uniq.append((kb, lab))
    return uniq, header

# ---------------------------------------------------------------- trial runner
def aes_trials(prefix, key, iv0, iv_hdr, iv_cipher):
    """Yield (modelabel, plaintext_prefix) for all AES modes for one key."""
    ivs = [("IV0", iv0), ("IVhdr", iv_hdr), ("IVcipher", iv_cipher)]
    yield ("AES-ECB", AES.new(key, AES.MODE_ECB).decrypt(prefix))
    for ivn, iv in ivs:
        yield (f"AES-CBC/{ivn}", AES.new(key, AES.MODE_CBC, iv).decrypt(prefix))
    # CTR: treat the 16-byte iv as the initial counter block
    for ivn, iv in [("ctr0", iv0), ("ctrHdr", iv_hdr), ("ctrCipher", iv_cipher)]:
        ctr = AES.new(key, AES.MODE_CTR, nonce=b"", initial_value=iv)
        yield (f"AES-CTR/{ivn}", ctr.decrypt(prefix))

def des_trials(prefix, key):
    out = []
    try:
        k8 = key[:8]
        out.append(("DES-ECB", DES.new(k8, DES.MODE_ECB).decrypt(prefix)))
        out.append(("DES-CBC0", DES.new(k8, DES.MODE_CBC, b"\x00"*8).decrypt(prefix)))
    except Exception: pass
    try:
        k24 = (key*3)[:24] if len(key) < 24 else key[:24]
        k24 = DES3.adjust_key_parity(k24)
        out.append(("3DES-ECB", DES3.new(k24, DES3.MODE_ECB).decrypt(prefix)))
        out.append(("3DES-CBC0", DES3.new(k24, DES3.MODE_CBC, b"\x00"*8).decrypt(prefix)))
    except Exception: pass
    return out

def xtea_trials(prefix, key):
    k16 = key[:16]
    out = []
    for be in (False, True):
        tag = "BE" if be else "LE"
        out.append((f"XTEA-{tag}", block_cipher_decrypt(prefix, k16, xtea_decrypt_block, be)))
        out.append((f"TEA-{tag}",  block_cipher_decrypt(prefix, k16, tea_decrypt_block,  be)))
    return out

def xorstream_trials(prefix, key):
    """key as repeating-XOR stream, and key as linear-scrambler seed override."""
    out = []
    kb = key
    x = bytes(prefix[i] ^ kb[i % len(kb)] for i in range(len(prefix)))
    out.append(("XORrep", x))
    return out

def main():
    fw = open(FW, "rb").read()
    raw_payload = fw[HDR:]
    ds_payload  = descramble(raw_payload)
    inputs = [("RAW", raw_payload), ("DESCR", ds_payload)]

    keys, header = build_keys(fw)
    iv0        = b"\x00"*16
    iv_hdr     = fw[0x3c:0x4c]            # header bytes 0x3c..0x4c (all zero here, but per-spec)
    if len(iv_hdr) < 16: iv_hdr = (iv_hdr + b"\x00"*16)[:16]

    print(f"# firmware.bin payload={len(raw_payload)}  candidate keys={len(keys)}  inputs={[i[0] for i in inputs]}")
    print(f"# scoring prefix={SCORE_PREFIX} bytes; pycryptodome={HAVE_PYCRYPTO}")
    baseline_raw = score_plaintext(raw_payload[:SCORE_PREFIX])[0]
    baseline_ds  = score_plaintext(ds_payload[:SCORE_PREFIX])[0]
    print(f"# baseline score (undecrypted): RAW={baseline_raw:.1f} DESCR={baseline_ds:.1f}")

    results = []   # (score, why, keylabel, keyhex, modelabel, inputname)
    for inp_name, payload in inputs:
        pfx  = payload[:SCORE_PREFIX]
        xpfx = payload[:XTEA_PREFIX]
        iv_cipher = payload[:16]
        for key, klabel in keys:
            trials = []
            if HAVE_PYCRYPTO:
                try:
                    trials += list(aes_trials(pfx, key, iv0, iv_hdr, iv_cipher))
                except Exception as e:
                    pass
                trials += des_trials(pfx, key)
            # pure-python ciphers on smaller window
            trials += [(m, pt) for (m, pt) in xtea_trials(xpfx, key)]
            trials += xorstream_trials(pfx, key)
            for mlabel, pt in trials:
                sc, why = score_plaintext(pt)
                results.append((sc, why, klabel, key.hex(), mlabel, inp_name))

    results.sort(key=lambda r: r[0], reverse=True)
    print(f"\n# total trials scored: {len(results)}")
    print("\n===== TOP 25 RANKED TRIALS =====")
    print(f"{'rank':>4} {'score':>8}  {'input':6} {'mode':14} {'keylabel':28} why")
    for i, (sc, why, klab, khex, mlab, inp) in enumerate(results[:25], 1):
        print(f"{i:>4} {sc:8.1f}  {inp:6} {mlab:14} {klab:28} {why}")
        if i <= 25 and sc > 50:
            print(f"        key={khex}")

    # explicit verdict on vector-table hits
    strong = [r for r in results if "VEC[" in r[1] and ("SP=" in r[1] and "reset=" in r[1])]
    print("\n===== VERDICT =====")
    if strong:
        print("POSSIBLE WIN -- trials with BOTH valid SP and reset handler:")
        for sc, why, klab, khex, mlab, inp in strong[:10]:
            print(f"   score={sc:.1f} {inp} {mlab} {klab} key={khex}\n     {why}")
    else:
        print("No trial produced a valid ARMv6-M vector table (SP+reset). No common key won on the vector-table test.")
    best = results[0]
    print(f"\nBest-scoring trial overall: score={best[0]:.1f} {best[5]} {best[4]} {best[2]} key={best[3]}")
    print(f"  why: {best[1]}")
    if best[0] <= max(baseline_raw, baseline_ds) + 5:
        print("  -> indistinguishable from undecrypted random baseline; NOT a real decrypt.")

if __name__ == "__main__":
    main()
