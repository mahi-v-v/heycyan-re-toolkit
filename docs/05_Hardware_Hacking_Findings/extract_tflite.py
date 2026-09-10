import sys
import struct

def find_tflite_models(firmware_path):
    print(f"Scanning {firmware_path} for TFLite models...")
    try:
        with open(firmware_path, "rb") as f:
            data = f.read()
    except Exception as e:
        print(f"Error reading file: {e}")
        return

    # TFLite FlatBuffer identifier is 'TFL3' at offset 4 of the buffer.
    # We will search for 'TFL3' and then try to parse it as a FlatBuffer.
    magic = b'TFL3'
    offset = 0
    found = False
    
    while True:
        idx = data.find(magic, offset)
        if idx == -1:
            break
            
        # The true start of the flatbuffer is 4 bytes BEFORE the magic string 'TFL3'
        model_start = idx - 4
        
        if model_start >= 0:
            found = True
            print(f"\n[+] Found 'TFL3' magic bytes at file offset: {hex(idx)}")
            print(f"[+] FlatBuffer start offset: {hex(model_start)}")
            
            # Try to read the length. The root table is usually at an offset.
            # However, for a simple dump, we can just dump the next 55KB from the start.
            dump_size = 55 * 1024 # Max allowed tensor arena is 57KB, so model is likely smaller than 55KB
            end_idx = min(model_start + dump_size, len(data))
            model_data = data[model_start:end_idx]
            
            out_file = f"extracted_model_{hex(model_start)}.tflite"
            with open(out_file, "wb") as out:
                out.write(model_data)
            print(f"[+] Dumped ~55KB chunk to {out_file}")
            
        offset = idx + 1

    if not found:
        print("[-] No TFLite magic bytes found. Model might be encrypted or using a different DSP library (not TFLM).")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python extract_tflite.py <path_to_riscv_binary>")
    else:
        find_tflite_models(sys.argv[1])
