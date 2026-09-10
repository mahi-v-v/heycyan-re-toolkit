#!/usr/bin/env python3
"""Verify claims from the documentation against the actual firmware binaries."""
import math
import struct
import sys
import os

def entropy(data):
    freq = [0]*256
    for b in data:
        freq[b] += 1
    total = len(data)
    return -sum((c/total) * math.log2(c/total) for c in freq if c > 0)

def find_tfl3_magic(data):
    """Search for TFLite FlatBuffer magic 'TFL3' at the standard offset (+4 from start)."""
    magic = b'TFL3'
    results = []
    offset = 0
    while True:
        idx = data.find(magic, offset)
        if idx == -1:
            break
        results.append(idx)
        offset = idx + 1
    return results

def analyze_riscv(path):
    print(f"\n=== Analyzing: {path} ===")
    with open(path, 'rb') as f:
        data = f.read()

    print(f"File size: {len(data)} bytes ({len(data)/1024:.1f} KB)")

    # ELF header check
    if data[:4] == b'\x7fELF':
        print("Format: ELF executable")
        ei_class = data[4]
        ei_data = data[5]
        print(f"  Class: {'32-bit' if ei_class == 1 else '64-bit'}")
        print(f"  Endian: {'Little' if ei_data == 1 else 'Big'}")
        e_machine = struct.unpack_from('<H', data, 18)[0]
        print(f"  Machine: {hex(e_machine)} ({'RISC-V' if e_machine == 0xF3 else 'Unknown'})")
    else:
        print(f"NOT an ELF file. First 4 bytes: {data[:4].hex()}")

    # Entropy
    ent = entropy(data)
    print(f"\nShannon entropy: {ent:.4f} bits/byte (max 8.0)")
    if ent > 7.99:
        print("  -> VERY HIGH entropy: likely compressed or encrypted")
    elif ent > 7.5:
        print("  -> HIGH entropy: possibly partially compressed")
    else:
        print("  -> MODERATE entropy: appears to be uncompressed code/data")

    # Search for TFL3 magic
    tfl3_locs = find_tfl3_magic(data)
    if tfl3_locs:
        print(f"\nTFLite 'TFL3' magic found at offsets: {[hex(x) for x in tfl3_locs]}")
    else:
        print("\nTFLite 'TFL3' magic: NOT FOUND")

    # Search for key strings
    key_strings = [
        b'tensorflow', b'tflite', b'TFL3', b'tflm', b'tensor_arena',
        b'interpreter', b'invoke', b'micro_interpreter',
        b'sensory', b'cyberon', b'Sensory', b'Cyberon',
        b'kws', b'keyword', b'wake_word',
        b'rpbuf', b'wakeup buffer', b'FreeRTOS',
        b'V821', b'E907', b'Xuantie', b'AllwinnerTech',
        b'csi_nn', b'csi_dsp',
        b'model_data', b'g_model', b'model_tflite',
    ]
    print("\n--- String Search Results ---")
    for s in key_strings:
        count = data.count(s)
        if count > 0:
            idx = data.find(s)
            print(f"  FOUND: '{s.decode(errors='replace')}' x{count} (first at offset {hex(idx)})")
        else:
            print(f"  NOT FOUND: '{s.decode(errors='replace')}'")

def analyze_firmware_bin(path):
    print(f"\n=== Analyzing firmware.bin: {path} ===")
    with open(path, 'rb') as f:
        data = f.read()

    print(f"File size: {len(data)} bytes ({len(data)/1024:.1f} KB)")

    # Entropy
    ent = entropy(data)
    print(f"Shannon entropy: {ent:.4f} bits/byte (max 8.0)")

    # Check header for version string
    header = data[:80]
    print(f"First 80 bytes (hex): {header.hex()}")
    # Try to find ASCII in first 80 bytes
    ascii_parts = []
    current = []
    for b in header:
        if 32 <= b <= 126:
            current.append(chr(b))
        else:
            if len(current) >= 4:
                ascii_parts.append(''.join(current))
            current = []
    if current and len(current) >= 4:
        ascii_parts.append(''.join(current))
    if ascii_parts:
        print(f"ASCII strings in header: {ascii_parts}")
    else:
        print("No readable ASCII strings in first 80 bytes")

    # TFL3 search
    tfl3_locs = find_tfl3_magic(data)
    if tfl3_locs:
        print(f"TFLite 'TFL3' magic found at offsets: {[hex(x) for x in tfl3_locs]}")
    else:
        print("TFLite 'TFL3' magic: NOT FOUND")

if __name__ == '__main__':
    base = os.path.dirname(os.path.abspath(__file__))
    proj = os.path.dirname(os.path.dirname(base))

    riscv_path = os.path.join(proj, 'hardware_research', 'firmware_and_os', 'firmware', 'riscv')
    fw_bin_path = os.path.join(proj, 'hardware_research', 'am01cy_crypto', 'fw_AM01CY_2.00.10_260411.bin')

    if os.path.exists(riscv_path):
        analyze_riscv(riscv_path)
    else:
        print(f"riscv not found at {riscv_path}")

    if os.path.exists(fw_bin_path):
        analyze_firmware_bin(fw_bin_path)
    else:
        print(f"firmware.bin not found at {fw_bin_path}")
