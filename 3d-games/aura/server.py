#!/usr/bin/env python3
"""AURA dev server. Stdlib only.

  /            -> web/
  /music/...   -> music/   (your own phonk: drop .mp3/.ogg/.wav/.m4a/.flac in there)
  /api/tracks  -> the files in music/, merged with music/tracks.json overrides
"""
from __future__ import annotations

import http.server
import json
import socketserver
import sys
import urllib.parse
import webbrowser
from pathlib import Path

ROOT = Path(__file__).resolve().parent
WEB = ROOT / "web"
MUSIC = ROOT / "music"
PORT = 5620
AUDIO_EXT = {".mp3", ".ogg", ".wav", ".m4a", ".flac", ".aac", ".opus", ".webm"}


def list_tracks() -> list[dict]:
    overrides = {}
    meta = MUSIC / "tracks.json"
    if meta.exists():
        try:
            data = json.loads(meta.read_text(encoding="utf-8"))
            # {"file.mp3": {"name": "...", "drop": 43.2, "bpm": 140, "firstBeat": 0.12}}
            if isinstance(data, dict):
                overrides = {k: v for k, v in data.items() if isinstance(v, dict) and not k.startswith("_")}
        except Exception as e:  # a typo in tracks.json must not take the game down
            print(f"  music/tracks.json unreadable: {e}")
    out = []
    if MUSIC.is_dir():
        for p in sorted(MUSIC.iterdir(), key=lambda p: p.name.lower()):
            if p.suffix.lower() not in AUDIO_EXT or not p.is_file():
                continue
            o = overrides.get(p.name, {})
            rec = {
                "id": "file:" + p.name,
                "file": p.name,
                "url": "/music/" + urllib.parse.quote(p.name),
                "name": o.get("name") or p.stem,
            }
            for k in ("drop", "bpm", "firstBeat"):
                if isinstance(o.get(k), (int, float)):
                    rec[k] = o[k]
            out.append(rec)
    return out


class Handler(http.server.SimpleHTTPRequestHandler):
    extensions_map = {
        **http.server.SimpleHTTPRequestHandler.extensions_map,
        ".js": "text/javascript",
        ".mjs": "text/javascript",
        ".m4a": "audio/mp4",
        ".opus": "audio/ogg",
        ".flac": "audio/flac",
    }

    def __init__(self, *a, **kw):
        super().__init__(*a, directory=str(WEB), **kw)

    def end_headers(self):
        # An edit is a save plus a refresh. Never serve yesterday's build.
        self.send_header("Cache-Control", "no-store, must-revalidate")
        super().end_headers()

    def translate_path(self, path):
        clean = urllib.parse.unquote(urllib.parse.urlsplit(path).path)
        if clean.startswith("/music/"):
            rel = clean[len("/music/"):]
            target = (MUSIC / rel).resolve()
            if MUSIC.resolve() in target.parents and target.suffix.lower() in AUDIO_EXT:
                return str(target)
            return str(MUSIC / "__missing__")
        return super().translate_path(path)

    def do_GET(self):
        if urllib.parse.urlsplit(self.path).path == "/api/tracks":
            body = json.dumps(list_tracks()).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        super().do_GET()

    def log_message(self, fmt, *args):
        pass


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = False  # a second copy must fail loudly, not share the port


def main() -> None:
    MUSIC.mkdir(exist_ok=True)
    url = f"http://127.0.0.1:{PORT}/"
    try:
        httpd = Server(("127.0.0.1", PORT), Handler)
    except OSError:
        print(f"\n  AURA is already running (port {PORT} is taken). Opening it.\n")
        if "--no-open" not in sys.argv:
            webbrowser.open(url)
        return
    n = len(list_tracks())
    print()
    print("  AURA")
    print(f"  play      {url}")
    print(f"  music/    {n} track(s) of your own" + ("" if n else "  (drop phonk .mp3s in AURA\\music)"))
    print("  ctrl+c to stop.")
    print()
    if "--no-open" not in sys.argv:
        try:
            webbrowser.open(url)
        except Exception:
            pass
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n  stopped.")


if __name__ == "__main__":
    main()
