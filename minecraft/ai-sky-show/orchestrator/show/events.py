"""The event bus: one event shape everywhere (CONTRACT.md §3).

    from show.events import BUS
    BUS.emit("agent", "say", players=["Kaito"], text="Kaito: LET'S GO", data={"char": "Kaito", ...})
    q = BUS.subscribe()            # queue.Queue of event dicts (every event from now on)
    BUS.recent(200)                # last N events

Server events arrive through `ServerFeed` (ShowControl SSE on :25642), get their account names
mapped to character names, and are re-emitted on the bus with src="server".
Every event is appended to the episode's events.jsonl once `BUS.open_log(path)` is called.
"""
from __future__ import annotations

import collections
import itertools
import json
import queue
import threading
import time
import urllib.request
from pathlib import Path
from typing import Any

from .config import SHOW


def now_ms() -> int:
    return int(time.time() * 1000)


class EventBus:
    def __init__(self, keep: int = 5000) -> None:
        self._lock = threading.Lock()
        self._ids = itertools.count(1)
        self._recent: collections.deque[dict] = collections.deque(maxlen=keep)
        self._subs: list[queue.Queue] = []
        self._log = None

    def open_log(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        with self._lock:
            if self._log:
                self._log.close()
            self._log = open(path, "a", encoding="utf-8")

    def close_log(self) -> None:
        with self._lock:
            if self._log:
                self._log.close()
                self._log = None

    def emit(self, src: str, type: str, *, players: list[str] | None = None, drama: float = 0,
             text: str = "", loc: dict | None = None, data: dict | None = None,
             t: int | None = None) -> dict:
        ev = {"id": 0, "t": t or now_ms(), "src": src, "type": type,
              "players": players or [], "drama": drama, "text": text,
              "loc": loc, "data": data or {}}
        return self.publish(ev)

    def publish(self, ev: dict) -> dict:
        with self._lock:
            ev = dict(ev)
            ev["bus_id"] = next(self._ids)
            self._recent.append(ev)
            subs = list(self._subs)
            if self._log:
                self._log.write(json.dumps(ev, ensure_ascii=False) + "\n")
                self._log.flush()
        for q in subs:
            try:
                q.put_nowait(ev)
            except queue.Full:
                pass
        return ev

    def subscribe(self, maxsize: int = 10000) -> queue.Queue:
        q: queue.Queue = queue.Queue(maxsize=maxsize)
        with self._lock:
            self._subs.append(q)
        return q

    def unsubscribe(self, q: queue.Queue) -> None:
        with self._lock:
            if q in self._subs:
                self._subs.remove(q)

    def recent(self, n: int = 200, types: set[str] | None = None) -> list[dict]:
        with self._lock:
            evs = list(self._recent)
        if types:
            evs = [e for e in evs if e["type"] in types]
        return evs[-n:]


BUS = EventBus()


def map_players(ev: dict) -> dict:
    ev = dict(ev)
    ev["players"] = [SHOW.char_name(p) for p in ev.get("players", [])]
    return ev


class ServerFeed(threading.Thread):
    """Reads ShowControl's SSE feed and republishes every event on the bus. Reconnects forever."""

    def __init__(self, bus: EventBus = BUS, port: int | None = None) -> None:
        super().__init__(daemon=True, name="ServerFeed")
        self.bus = bus
        self.url = f"http://127.0.0.1:{port or SHOW.ports['showcontrol']}/events"
        self.last_id: str | None = None
        self.connected = False
        self._stop = threading.Event()

    def stop(self) -> None:
        self._stop.set()

    def run(self) -> None:
        while not self._stop.is_set():
            try:
                req = urllib.request.Request(self.url, headers={"Accept": "text/event-stream"})
                if self.last_id:
                    req.add_header("Last-Event-ID", self.last_id)
                with urllib.request.urlopen(req, timeout=30) as r:
                    self.connected = True
                    data_lines: list[str] = []
                    for raw in r:
                        if self._stop.is_set():
                            return
                        line = raw.decode("utf-8", "replace").rstrip("\r\n")
                        if line.startswith("id:"):
                            self.last_id = line[3:].strip()
                        elif line.startswith("data:"):
                            data_lines.append(line[5:].lstrip())
                        elif line == "" and data_lines:
                            try:
                                ev = json.loads("\n".join(data_lines))
                                ev["src"] = "server"
                                self.bus.publish(map_players(ev))
                            except ValueError:
                                pass
                            data_lines = []
            except Exception:
                self.connected = False
                self._stop.wait(2.0)


def showcontrol(path: str, body: dict | None = None, timeout: float = 5.0) -> Any:
    """Tiny client for ShowControl's HTTP API. GET when body is None, else POST JSON."""
    url = f"http://127.0.0.1:{SHOW.ports['showcontrol']}{path}"
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))
