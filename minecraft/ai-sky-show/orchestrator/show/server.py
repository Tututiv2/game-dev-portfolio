"""The orchestrator's web face on 127.0.0.1:5650 (CONTRACT.md §5).

    from show import server
    srv = server.start()                       # background thread, returns ShowServer
    server.push({"kind": "ticker", "text": "Kaito killed a zombie", "drama": 3})
    server.on_control(lambda action, body: ... )   # return a dict if you handled it, else None
    srv.set_audio_dir(ep_dir / "audio")

Routes:
  GET  /                dashboard.html (the owner's control room)
  GET  /overlay         overlay.html (OBS browser source, 1920x1080, transparent)
  GET  /static/<path>   files under overlay/
  GET  /audio/<file>    wav clips from the current episode audio dir (Range supported)
  GET  /stream          SSE of overlay messages; new connections get the sticky state replayed
                        (status per char, clock, scene, letterbox, cost) so an OBS reload is never blank
  GET  /bus             SSE of raw bus events for the dashboard feed (last 150 replayed)
  GET  /api/state       full snapshot           GET/POST /api/dials   read / set dials
  POST /api/control     {"action": ...}         POST /api/push        push one overlay message (debug)

`py -3 -m show.server --demo` (from orchestrator/) serves the pages and loops a scripted fake episode.
"""
from __future__ import annotations

import collections
import json
import mimetypes
import queue
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Callable
from urllib.parse import unquote, urlparse

from .config import ROOT, SHOW
from .dials import DIALS
from .events import BUS

OVERLAY_DIR = ROOT / "overlay"
DEFAULT_AUDIO_DIR = ROOT / "episodes" / "_scratch" / "audio"
STICKY_KINDS = ("clock", "scene", "letterbox", "cost")


def now_ms() -> int:
    return int(time.time() * 1000)


# ---------------------------------------------------------------------------------------------
# overlay message hub
# ---------------------------------------------------------------------------------------------
class Hub:
    """Fan-out of overlay messages to every SSE client, plus sticky state for late joiners."""

    def __init__(self, keep: int = 300) -> None:
        self._lock = threading.Lock()
        self._subs: list[queue.Queue] = []
        self._sticky: dict[str, dict] = {}
        self._recent: collections.deque[dict] = collections.deque(maxlen=keep)
        self._seq = 0

    def push(self, msg: dict) -> dict:
        if not isinstance(msg, dict) or not msg.get("kind"):
            raise ValueError("overlay message needs a 'kind'")
        with self._lock:
            self._seq += 1
            msg = dict(msg, seq=self._seq, ts=msg.get("ts") or now_ms())
            kind = msg["kind"]
            if kind == "status" and msg.get("char"):
                key = f"status:{msg['char']}"
                merged = dict(self._sticky.get(key, {}))
                merged.update({k: v for k, v in msg.items() if v is not None})
                self._sticky[key] = merged
                msg = merged
            elif kind in STICKY_KINDS:
                self._sticky[kind] = msg
            self._recent.append(msg)
            subs = list(self._subs)
        for q in subs:
            try:
                q.put_nowait(msg)
            except queue.Full:
                self.unsubscribe(q)          # a client that stopped reading is dropped, not waited on
        return msg

    def replay(self) -> list[dict]:
        with self._lock:
            sticky = list(self._sticky.values())
        # status first so plates exist before the scene decides what is visible
        sticky.sort(key=lambda m: (m["kind"] != "status", m.get("seq", 0)))
        return [dict(m, replay=True) for m in sticky]

    def subscribe(self, replay: bool = True, maxsize: int = 2000) -> queue.Queue:
        q: queue.Queue = queue.Queue(maxsize=maxsize)
        if replay:
            for m in self.replay():
                q.put_nowait(m)
        with self._lock:
            self._subs.append(q)
        return q

    def unsubscribe(self, q: queue.Queue) -> None:
        with self._lock:
            if q in self._subs:
                self._subs.remove(q)

    def close_all(self) -> None:
        with self._lock:
            subs, self._subs = list(self._subs), []
        for q in subs:
            try:
                q.put_nowait(None)
            except queue.Full:
                pass

    def recent(self, n: int = 100) -> list[dict]:
        with self._lock:
            return list(self._recent)[-n:]

    def clients(self) -> int:
        with self._lock:
            return len(self._subs)


# ---------------------------------------------------------------------------------------------
# show state (what the dashboard reads)
# ---------------------------------------------------------------------------------------------
class ShowState:
    def __init__(self) -> None:
        self._lock = threading.RLock()
        self.chars: dict[str, dict] = {}
        for c in SHOW.characters:
            self.chars[c.name] = {
                "name": c.name, "id": c.id, "account": c.account, "slot": c.slot, "color": c.color,
                "archetype": c.archetype, "goal": "", "level": None, "purse": None, "island": "",
                "zone": "", "health": None, "maxHealth": None, "online": False,
                "last_inner": "", "last_emotion": "", "last_say": "", "last_action": "",
                "last_action_ok": None, "latency_s": None, "turns": 0, "cost_usd": 0.0,
                "stuck": False, "stuck_s": 0, "voice_lines": 0, "updated": 0}
        self.clock = {"elapsed_s": 0, "total_s": float(DIALS.get("episode_minutes", 30)) * 60, "running": False}
        self.scene = {"mode": "grid"}
        self.cost = {"usd": 0.0, "turns": 0, "per_hour": 0.0}
        self.director: collections.deque[dict] = collections.deque(maxlen=40)
        self.episode: dict = {"name": None, "dir": None, "state": "idle"}
        self._turn_times: collections.deque[float] = collections.deque(maxlen=5000)
        self.providers: dict[str, Callable[[], Any]] = {}      # extra snapshot sections (tts...)

    def char(self, name: str) -> dict:
        with self._lock:
            if name not in self.chars:
                self.chars[name] = {"name": name, "slot": len(self.chars), "color": "#ffffff", "turns": 0,
                                    "cost_usd": 0.0, "stuck": False}
            return self.chars[name]

    def update_char(self, name: str, **fields) -> dict:
        with self._lock:
            c = self.char(name)
            c.update(fields)
            c["updated"] = now_ms()
            return dict(c)

    def note_turn(self, name: str, t: float | None = None) -> None:
        with self._lock:
            self._turn_times.append(t or time.time())
            c = self.char(name)
            c["turns"] = c.get("turns", 0) + 1

    def turns_per_hour(self, now: float | None = None) -> float:
        now = now or time.time()
        with self._lock:
            ts = [t for t in self._turn_times if now - t <= 600]
            if not ts:
                return 0.0
            window = max(60.0, min(600.0, now - ts[0]))
            return round(len(ts) * 3600.0 / window, 1)

    def snapshot(self) -> dict:
        with self._lock:
            snap = {
                "title": SHOW.title,
                "chars": sorted((dict(c) for c in self.chars.values()), key=lambda c: c.get("slot", 99)),
                "clock": dict(self.clock), "scene": dict(self.scene),
                "cost": dict(self.cost, turns_per_hour=self.turns_per_hour(),
                             max_turns_per_hour=DIALS.get("max_turns_per_hour")),
                "director": list(self.director)[::-1], "episode": dict(self.episode),
                "dials": DIALS.snapshot(), "t": now_ms(),
            }
        for k, fn in list(self.providers.items()):
            try:
                snap[k] = fn()
            except Exception as e:
                snap[k] = {"error": str(e)}
        return snap


HUB = Hub()
STATE = ShowState()
_CONTROL: list[Callable[[str, dict], Any]] = []


def push(msg: dict) -> dict:
    return HUB.push(msg)


def on_control(fn: Callable[[str, dict], Any]) -> None:
    """Register a handler for POST /api/control. fn(action, body) -> dict if handled, None if not."""
    _CONTROL.append(fn)


def run_control(action: str, body: dict) -> dict:
    results = []
    for fn in list(_CONTROL):
        try:
            r = fn(action, body)
        except Exception as e:
            r = {"ok": False, "error": f"{type(e).__name__}: {e}"}
        if r is not None:
            results.append(r)
    # built-in baseline so the buttons always do *something* even with no episode attached
    if action in ("pause", "resume"):
        DIALS.set("paused", action == "pause")
    if action == "mark" and not results:
        BUS.emit("system", "mark", text=f"MARK {body.get('label') or 'moment'}",
                 data={"label": body.get("label") or "moment"}, drama=5)
        results.append({"ok": True, "marked": True})
    if not results and action not in ("pause", "resume"):
        return {"ok": False, "error": f"no handler for {action!r} (is an episode running?)"}
    return {"ok": all(r.get("ok", True) for r in results if isinstance(r, dict)), "results": results}


# ---------------------------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------------------------
_RANGE = re.compile(r"bytes=(\d*)-(\d*)")


class Handler(BaseHTTPRequestHandler):
    server_version = "AISkyShow/1"
    srv: "ShowServer"          # set on the subclass

    def log_message(self, fmt, *args):          # quiet: this runs next to a live stream console
        pass

    # -- helpers ----------------------------------------------------------------------------
    def _send(self, code: int, body: bytes, ctype: str, extra: dict | None = None) -> None:
        try:
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Access-Control-Allow-Origin", "*")
            for k, v in (extra or {}).items():
                self.send_header(k, v)
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass

    def _json(self, obj: Any, code: int = 200) -> None:
        self._send(code, json.dumps(obj, ensure_ascii=False, default=str).encode("utf-8"),
                   "application/json; charset=utf-8")

    def _body(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n > 0 else b""          # always consume the body before answering
        if not raw:
            return {}
        try:
            v = json.loads(raw.decode("utf-8"))
            return v if isinstance(v, dict) else {"value": v}
        except ValueError:
            return {}

    def _file(self, path: Path, ctype: str | None = None) -> None:
        try:
            data_len = path.stat().st_size
        except OSError:
            return self._json({"error": "not found"}, 404)
        ctype = ctype or mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        if ctype.startswith("text/") or ctype in ("application/javascript",):
            ctype += "; charset=utf-8"
        rng = self.headers.get("Range")
        start, end = 0, data_len - 1
        code = 200
        if rng:
            m = _RANGE.match(rng.strip())
            if m and (m.group(1) or m.group(2)):
                if m.group(1):
                    start = int(m.group(1))
                    end = int(m.group(2)) if m.group(2) else data_len - 1
                else:                                   # suffix range: last N bytes
                    start = max(0, data_len - int(m.group(2)))
                end = min(end, data_len - 1)
                if start > end or start >= data_len:
                    return self._send(416, b"", "text/plain", {"Content-Range": f"bytes */{data_len}"})
                code = 206
        try:
            with open(path, "rb") as f:
                f.seek(start)
                body = f.read(end - start + 1)
        except OSError:
            return self._json({"error": "unreadable"}, 500)
        extra = {"Accept-Ranges": "bytes"}
        if code == 206:
            extra["Content-Range"] = f"bytes {start}-{end}/{data_len}"
        self._send(code, body, ctype, extra)

    def _sse(self, q: queue.Queue, unsubscribe: Callable[[queue.Queue], None], fmt) -> None:
        try:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "keep-alive")
            self.send_header("X-Accel-Buffering", "no")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(b"retry: 1500\n\n")
            self.wfile.flush()
            while not self.srv.stopping:
                try:
                    msg = q.get(timeout=10)
                except queue.Empty:
                    self.wfile.write(b": hb\n\n")
                    self.wfile.flush()
                    continue
                if msg is None:
                    break
                self.wfile.write(fmt(msg))
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError):
            pass
        finally:
            unsubscribe(q)

    # -- routes -----------------------------------------------------------------------------
    def do_HEAD(self):
        self.do_GET()

    def do_OPTIONS(self):
        self._send(204, b"", "text/plain", {"Access-Control-Allow-Methods": "GET, POST, OPTIONS",
                                            "Access-Control-Allow-Headers": "Content-Type, Range"})

    def do_GET(self):
        url = urlparse(self.path)
        p = unquote(url.path)
        if p in ("/", "/dashboard", "/dashboard.html"):
            return self._file(OVERLAY_DIR / "dashboard.html", "text/html")
        if p in ("/overlay", "/overlay.html"):
            return self._file(OVERLAY_DIR / "overlay.html", "text/html")
        if p.startswith("/static/"):
            f = _safe_join(OVERLAY_DIR, p[len("/static/"):])
            return self._file(f) if f else self._json({"error": "bad path"}, 404)
        if p.startswith("/audio/"):
            name = p[len("/audio/"):]
            if not re.fullmatch(r"[A-Za-z0-9_.\-]+\.(wav|mp3|ogg)", name):
                return self._json({"error": "bad name"}, 404)
            for d in self.srv.audio_dirs():
                f = d / name
                if f.is_file():
                    return self._file(f, "audio/mpeg" if name.endswith(".mp3") else
                                      "audio/ogg" if name.endswith(".ogg") else "audio/wav")
            return self._json({"error": "not found"}, 404)
        if p == "/stream":
            q = self.srv.hub.subscribe(replay=True)
            return self._sse(q, self.srv.hub.unsubscribe,
                             lambda m: ("data: " + json.dumps(m, ensure_ascii=False, default=str)
                                        + "\n\n").encode("utf-8"))
        if p == "/bus":
            q = self.srv.bus.subscribe(maxsize=2000)
            for ev in self.srv.bus.recent(150):
                try:
                    q.put_nowait(ev)
                except queue.Full:
                    break
            return self._sse(q, self.srv.bus.unsubscribe,
                             lambda e: (f"id: {e.get('bus_id', '')}\ndata: "
                                        + json.dumps(e, ensure_ascii=False, default=str) + "\n\n").encode("utf-8"))
        if p == "/api/state":
            snap = self.srv.state.snapshot()
            snap["overlay_clients"] = self.srv.hub.clients()
            return self._json(snap)
        if p == "/api/dials":
            return self._json(DIALS.snapshot())
        if p == "/api/recent":
            return self._json(self.srv.hub.recent(200))
        if p == "/api/health":
            return self._json({"ok": True, "port": self.srv.port, "clients": self.srv.hub.clients()})
        self._json({"error": "not found", "path": p}, 404)

    def do_POST(self):
        p = urlparse(self.path).path
        body = self._body()
        if p == "/api/dials":
            items = {body["key"]: body.get("value")} if "key" in body else body
            out, errors = {}, {}
            for k, v in items.items():
                try:
                    out[k] = DIALS.set(k, v)
                except (KeyError, ValueError, TypeError) as e:
                    errors[k] = str(e)
            return self._json({"ok": not errors, "set": out, "errors": errors, "dials": DIALS.snapshot()},
                              200 if not errors else 400)
        if p == "/api/control":
            action = str(body.get("action") or "")
            if not action:
                return self._json({"ok": False, "error": "missing action"}, 400)
            return self._json(run_control(action, body))
        if p == "/api/push":
            try:
                return self._json({"ok": True, "msg": self.srv.hub.push(body)})
            except ValueError as e:
                return self._json({"ok": False, "error": str(e)}, 400)
        self._json({"error": "not found"}, 404)


def _safe_join(base: Path, rel: str) -> Path | None:
    try:
        f = (base / rel).resolve()
        f.relative_to(base.resolve())
    except (ValueError, OSError):
        return None
    return f if f.is_file() else None


class _Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = False      # a second copy must fail loudly, not split the clients

    def handle_error(self, request, client_address):
        import sys
        if isinstance(sys.exc_info()[1], (ConnectionError, TimeoutError)):
            return                   # browsers drop pre-connected sockets all the time: not an error
        super().handle_error(request, client_address)


class ShowServer:
    def __init__(self, host: str = "127.0.0.1", port: int | None = None, hub: Hub | None = None,
                 state: ShowState | None = None, bus=None) -> None:
        self.hub = hub or HUB
        self.state = state or STATE
        self.bus = bus or BUS
        self.audio_dir = DEFAULT_AUDIO_DIR
        self.stopping = False
        handler = type("BoundHandler", (Handler,), {"srv": self})
        self.httpd = _Server((host, SHOW.ports["orchestrator"] if port is None else port), handler)
        self.port = self.httpd.server_address[1]
        self.url = f"http://{host}:{self.port}"
        self._thread: threading.Thread | None = None

    def audio_dirs(self) -> list[Path]:
        return [self.audio_dir] + ([DEFAULT_AUDIO_DIR] if self.audio_dir != DEFAULT_AUDIO_DIR else [])

    def set_audio_dir(self, path: Path | str) -> None:
        self.audio_dir = Path(path)
        self.audio_dir.mkdir(parents=True, exist_ok=True)

    def push(self, msg: dict) -> dict:
        return self.hub.push(msg)

    def start(self) -> "ShowServer":
        self._thread = threading.Thread(target=self.httpd.serve_forever, kwargs={"poll_interval": 0.25},
                                        daemon=True, name="show-server")
        self._thread.start()
        return self

    def stop(self) -> None:
        self.stopping = True
        self.hub.close_all()
        self.httpd.shutdown()
        self.httpd.server_close()


_SERVER: ShowServer | None = None


def start(port: int | None = None, host: str = "127.0.0.1") -> ShowServer:
    """Start the singleton server in the background (idempotent)."""
    global _SERVER
    if _SERVER is None:
        _SERVER = ShowServer(host, port).start()
    return _SERVER


def get() -> ShowServer | None:
    return _SERVER


def main(argv: list[str] | None = None) -> int:
    import argparse
    import webbrowser
    ap = argparse.ArgumentParser(description="AISkyShow dashboard + overlay server")
    ap.add_argument("--port", type=int, default=None)
    ap.add_argument("--demo", action="store_true", help="loop a scripted fake episode")
    ap.add_argument("--voice", action="store_true", help="demo: speak captions with SAPI")
    ap.add_argument("--open", action="store_true", help="open the dashboard in the browser")
    a = ap.parse_args(argv)
    try:
        srv = start(a.port)
    except OSError as e:
        print(f"port {a.port or SHOW.ports['orchestrator']} is busy - is the dashboard already running? ({e})")
        return 1
    from . import overlay_bridge
    bridge = overlay_bridge.start(srv, poll_server=not a.demo)
    print(f"AISkyShow  dashboard {srv.url}/   overlay {srv.url}/overlay")
    if a.demo:
        print("demo episode looping (Ctrl+C to stop)")
        threading.Thread(target=overlay_bridge.run_demo, args=(bridge,), kwargs={"voice": a.voice},
                         daemon=True, name="demo").start()
    if a.open:
        webbrowser.open(srv.url + "/")
        if a.demo:
            webbrowser.open(srv.url + "/overlay?demo=1")
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    bridge.stop()
    srv.stop()
    return 0


if __name__ == "__main__":
    # `-m show.server` runs this file as __main__; the bridge imports `show.server`. Run main() from
    # the package module so there is exactly ONE HUB / STATE / control-handler list.
    from show import server as _pkg
    raise SystemExit(_pkg.main())
