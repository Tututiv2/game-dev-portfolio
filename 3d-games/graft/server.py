#!/usr/bin/env python3
"""Serves app/ and prints a QR for the LAN address. Stdlib only."""
from __future__ import annotations

import http.server
import os
import socket
import socketserver
import sys
import webbrowser
from pathlib import Path

ROOT = Path(__file__).resolve().parent
APP = ROOT / "app"
PORT = 5600

sys.path.insert(0, str(ROOT / "tools"))
try:
    import qr
except Exception:
    qr = None


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=str(APP), **kw)

    def end_headers(self):
        # An edit must be one save plus a refresh. Never serve yesterday's build.
        self.send_header("Cache-Control", "no-store, must-revalidate")
        super().end_headers()

    def log_message(self, fmt, *args):
        pass


def lan_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


def print_qr(text: str) -> None:
    if qr is None:
        return
    try:
        m = qr.encode(text)
    except Exception:
        return
    n = len(m)
    pad = 2
    grid = [[0] * (n + pad * 2) for _ in range(n + pad * 2)]
    for y in range(n):
        for x in range(n):
            grid[y + pad][x + pad] = m[y][x]
    # Half-blocks: two rows per line. Windows consoles are cp1252 and cannot
    # encode these, so fall back to two spaces / two hashes.
    try:
        "▀".encode(sys.stdout.encoding or "utf-8")
        top, bot, both, none = "▀", "▄", "█", " "
        for y in range(0, len(grid), 2):
            line = ""
            for x in range(len(grid[0])):
                a = grid[y][x]
                b = grid[y + 1][x] if y + 1 < len(grid) else 0
                line += both if a and b else top if a else bot if b else none
            print(line)
    except Exception:
        for row in grid:
            print("".join("##" if v else "  " for v in row))


def main() -> None:
    url_local = f"http://127.0.0.1:{PORT}/"
    url_lan = f"http://{lan_ip()}:{PORT}/"
    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.TCPServer(("0.0.0.0", PORT), Handler) as httpd:
        print()
        print("  GRAFT")
        print(f"  this PC   {url_local}")
        print(f"  phone     {url_lan}")
        print()
        print_qr(url_lan)
        print()
        print("  scan it with your camera. ctrl+c to stop.")
        if "--no-open" not in sys.argv:
            try:
                webbrowser.open(url_local)
            except Exception:
                pass
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\n  stopped.")


if __name__ == "__main__":
    main()
