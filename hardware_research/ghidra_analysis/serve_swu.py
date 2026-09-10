#!/usr/bin/env python3
"""
serve_swu.py — Wake Word Zeroing-Out Test (Phase 4)

Hosts firmware_patched.swu over a simple HTTP server so the glasses can
download it when you send the URL via BLE opcode 0xFC (writeIpToSoc).

Usage (run on laptop connected to same WiFi as phone hotspot):
    python3 serve_swu.py [--port 8080] [--file path/to/firmware_patched.swu]

Then in your app / BLE tool, send opcode 0xFC with payload:
    http://<YOUR_LAPTOP_IP>:8080/firmware_patched.swu

The glasses will connect, download, verify MD5, and flash via swupdate.
A/B rollback protects against a bad patch — glasses revert automatically.
"""

import argparse
import http.server
import os
import socket
import sys

PROJ_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
DEFAULT_SWU = os.path.join(PROJ_ROOT, 'hardware_research', 'firmware_and_os',
                           'firmware_patched.swu')


def get_local_ip() -> str:
    """Get the machine's outbound IP (not 127.0.0.1)."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(('8.8.8.8', 80))
            return s.getsockname()[0]
    except Exception:
        return '0.0.0.0'


def serve(swu_path: str, port: int) -> None:
    if not os.path.exists(swu_path):
        print(f"ERROR: {swu_path} not found. Run repackage_swu.py first.")
        sys.exit(1)

    size_mb = os.path.getsize(swu_path) / 1024 / 1024
    local_ip = get_local_ip()
    serve_dir = os.path.dirname(swu_path)
    filename  = os.path.basename(swu_path)

    print(f"\n{'='*60}")
    print(f"  SWU HTTP Server — Wake Word Zeroing Test")
    print(f"{'='*60}")
    print(f"  Serving : {swu_path}")
    print(f"  Size    : {size_mb:.1f} MB")
    print(f"  Port    : {port}")
    print(f"\n  ▶ Send this URL to glasses via BLE opcode 0xFC:")
    print(f"    http://{local_ip}:{port}/{filename}")
    print(f"\n  After flashing, observe one of:")
    print(f"    ✅ Wake word stops working  → model location confirmed")
    print(f"    ⚠️  Reboot loop             → hit init data, not just weights")
    print(f"    ❌ No change                → target another region")
    print(f"{'='*60}\n")
    print("Press Ctrl+C to stop the server.\n")

    os.chdir(serve_dir)

    class Handler(http.server.SimpleHTTPRequestHandler):
        def log_message(self, format, *args):
            print(f"  [{self.address_string()}] {format % args}")

    with http.server.HTTPServer(('0.0.0.0', port), Handler) as httpd:
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nServer stopped.")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--port', type=int, default=8080,
                    help='Port to listen on (default: 8080)')
    ap.add_argument('--file', default=DEFAULT_SWU,
                    help='Path to the patched .swu file')
    args = ap.parse_args()
    serve(args.file, args.port)


if __name__ == '__main__':
    main()
