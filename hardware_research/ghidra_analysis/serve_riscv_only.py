#!/usr/bin/env python3
"""
serve_riscv_only.py — serve the MINIMAL riscv-only .swu at the SAME URL path
(/firmware_patched.swu) the forge already points at.

Purpose: defeat the phone->glasses transfer truncation by shrinking the payload.
The glasses' OTA daemon has a 5 s no-resume receive timeout and flashes truncated
downloads; a ~3 MB image transfers in a couple of seconds and cannot truncate, and
it writes ONLY the coprocessor partition (mtdblock4 / mmcblk0p7) so it cannot brick
the SoC. Build it first with build_minimal_swu.py.

Keep the EXACT SAME Reqable forge body (downloadUrl still ".../firmware_patched.swu");
any GET returns firmware_riscv_only.swu.

Usage:
    python serve_riscv_only.py [--port 8080] [--file <minimal .swu>]
"""
import argparse
import http.server
import os
import socket
import sys

PROJ_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
DEFAULT_FILE = os.path.join(PROJ_ROOT, 'hardware_research', 'firmware_and_os',
                            'firmware_riscv_only.swu')


def get_local_ip() -> str:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(('8.8.8.8', 80))
            return s.getsockname()[0]
    except Exception:
        return '0.0.0.0'


def serve(swu_path: str, port: int) -> None:
    if not os.path.exists(swu_path):
        print(f"ERROR: {swu_path} not found. Run build_minimal_swu.py first.")
        sys.exit(1)

    size = os.path.getsize(swu_path)
    local_ip = get_local_ip()

    print(f"\n{'='*60}")
    print(f"  SWU HTTP Server — MINIMAL riscv-only image")
    print(f"{'='*60}")
    print(f"  Serving : {swu_path}")
    print(f"  Size    : {size/1024/1024:.2f} MB ({size:,} bytes)")
    print(f"  Port    : {port}")
    print(f"\n  Any GET (incl. /firmware_patched.swu) returns this minimal image.")
    print(f"  Keep the SAME forge body — do not change the downloadUrl.")
    print(f"    http://{local_ip}:{port}/firmware_patched.swu")
    print(f"{'='*60}\n")
    print("Press Ctrl+C to stop the server.\n")

    class Handler(http.server.SimpleHTTPRequestHandler):
        def translate_path(self, path):
            return swu_path

        def log_message(self, fmt, *args):
            print(f"  [{self.address_string()}] {fmt % args}")

    with http.server.HTTPServer(('0.0.0.0', port), Handler) as httpd:
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nServer stopped.")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--port', type=int, default=8080)
    ap.add_argument('--file', default=DEFAULT_FILE, help='Path to the minimal .swu')
    args = ap.parse_args()
    serve(args.file, args.port)


if __name__ == '__main__':
    main()
