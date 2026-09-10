#!/usr/bin/env python3
"""
serve_stock.py — DIAGNOSTIC: serve the STOCK firmware.swu at the same URL
(/firmware_patched.swu) the patched server used.

Purpose: isolate whether OTA failures are caused by the patched image or by the
phone<->glasses Wi-Fi transfer. Keep the exact same forged breakpoint body
(downloadUrl still ".../firmware_patched.swu"); this server just returns the
unmodified stock firmware for any GET.

    If stock flashes to 100%  -> transport is fine, the patched file is the problem.
    If stock also fails ~29%  -> transport / glasses storage is the problem.

Usage:
    python serve_stock.py [--port 8080] [--file <stock .swu>]
"""

import argparse
import http.server
import os
import socket
import sys

PROJ_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
DEFAULT_STOCK = os.path.join(PROJ_ROOT, 'hardware_research', 'firmware_and_os',
                             'firmware.swu')


def get_local_ip() -> str:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(('8.8.8.8', 80))
            return s.getsockname()[0]
    except Exception:
        return '0.0.0.0'


def serve(stock_path: str, port: int) -> None:
    if not os.path.exists(stock_path):
        print(f"ERROR: {stock_path} not found.")
        sys.exit(1)

    size = os.path.getsize(stock_path)
    local_ip = get_local_ip()

    print(f"\n{'='*60}")
    print(f"  SWU HTTP Server — STOCK FIRMWARE (diagnostic)")
    print(f"{'='*60}")
    print(f"  Serving : {stock_path}")
    print(f"  Size    : {size/1024/1024:.1f} MB ({size} bytes)")
    print(f"  Port    : {port}")
    print(f"\n  Any GET (incl. /firmware_patched.swu) returns the STOCK image.")
    print(f"  Keep the SAME breakpoint body — do not change the downloadUrl.")
    print(f"    http://{local_ip}:{port}/firmware_patched.swu")
    print(f"\n  If stock flashes 100% -> transport OK, patched file is the issue.")
    print(f"  If stock fails ~29%   -> Wi-Fi transfer / glasses storage issue.")
    print(f"{'='*60}\n")
    print("Press Ctrl+C to stop the server.\n")

    class Handler(http.server.SimpleHTTPRequestHandler):
        # Serve the stock file for ANY requested path, while keeping
        # SimpleHTTPRequestHandler's correct Range/Content-Length/HEAD handling.
        def translate_path(self, path):
            return stock_path

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
    ap.add_argument('--file', default=DEFAULT_STOCK,
                    help='Path to the STOCK .swu file')
    args = ap.parse_args()
    serve(args.file, args.port)


if __name__ == '__main__':
    main()
