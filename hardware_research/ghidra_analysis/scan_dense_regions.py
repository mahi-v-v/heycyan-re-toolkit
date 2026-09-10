#!/usr/bin/env python3
import sys
import math

def calculate_entropy(data):
    if not data:
        return 0
    entropy = 0
    for x in range(256):
        p_x = data.count(x) / len(data)
        if p_x > 0:
            entropy += - p_x * math.log2(p_x)
    return entropy

def scan_data_unsaved(file_path):
    # .data_unsaved starts at file offset 0xa1e38 and size is 0xe0488
    start_offset = 0xa1e38
    size = 0xe0488
    end_offset = start_offset + size
    
    with open(file_path, 'rb') as f:
        f.seek(start_offset)
        data = f.read(size)
        
    print(f"Scanning .data_unsaved from offset {hex(start_offset)} to {hex(end_offset)} ({len(data)} bytes)...")
    
    block_size = 1024
    num_blocks = len(data) // block_size
    
    blocks_info = []
    for i in range(num_blocks):
        block = data[i*block_size : (i+1)*block_size]
        zero_count = block.count(0)
        zero_pct = (zero_count / block_size) * 100
        entropy = calculate_entropy(block)
        
        # Calculate standard deviation or other features to see if it's float-like
        # e.g., density of non-zero bytes
        blocks_info.append({
            'index': i,
            'offset': start_offset + i*block_size,
            'zero_pct': zero_pct,
            'entropy': entropy
        })
        
    # Let's print blocks that have low zero percentage (i.e. dense data) and high entropy
    # (indicating model weights or compressed arrays rather than code patterns or sparse configs)
    dense_blocks = [b for b in blocks_info if b['zero_pct'] < 50 and b['entropy'] > 4.5]
    print(f"Found {len(dense_blocks)} dense data blocks out of {num_blocks} total blocks.")
    
    # Group contiguous blocks
    if not dense_blocks:
        print("No dense blocks found!")
        return
        
    groups = []
    current_group = [dense_blocks[0]]
    for b in dense_blocks[1:]:
        if b['index'] == current_group[-1]['index'] + 1:
            current_group.append(b)
        else:
            groups.append(current_group)
            current_group = [b]
    groups.append(current_group)
    
    print("\n=== Candidate Dense Data Regions (Potential Model Weights) ===")
    for idx, g in enumerate(groups):
        start_off = g[0]['offset']
        end_off = g[-1]['offset'] + block_size
        size_bytes = end_off - start_off
        avg_zero = sum(b['zero_pct'] for b in g) / len(g)
        avg_entropy = sum(b['entropy'] for b in g) / len(g)
        print(f"Region {idx+1}:")
        print(f"  File Offset Range: {hex(start_off)} - {hex(end_off)}")
        print(f"  RAM Address Range: {hex(0x80900e38 + (start_off - 0xa1e38))} - {hex(0x80900e38 + (end_off - 0xa1e38))}")
        print(f"  Size: {size_bytes} bytes ({size_bytes/1024:.1f} KB)")
        print(f"  Avg Zeros: {avg_zero:.1f}%")
        print(f"  Avg Entropy: {avg_entropy:.4f} bits/byte")
        print()

if __name__ == '__main__':
    scan_data_unsaved('./hardware_research/firmware_and_os/firmware/riscv')
