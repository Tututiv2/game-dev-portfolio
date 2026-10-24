"""Camera: ShowCam (smooth, client-side, port 25660) or the ShowControl fallback (CONTRACT §11).

    cam = Camera()
    cam.shot("orbit", "Kaito", seconds=10)          # target = character name, account, or {"x","y","z"}
    cam.return_to_grid()

ShowCam is preferred whenever `GET /status` answers (checked at most every 10 s). Otherwise the
server-side fallback `/show cam orbit|follow|pushin|stop <account> [seconds]` is sent through
ShowControl's `/cmd`. The fallback only knows three moves, so crane/dolly/static are mapped onto the
closest one; it cannot aim at coordinates, so a coordinate target is skipped there.
"""
from __future__ import annotations

import json
import time
import urllib.request
from typing import Any, Callable

SHOTS = ("orbit", "pushin", "follow", "crane", "dolly", "static")

# per-shot framing defaults (ShowCam fields): radius, height, fov, speed
SHOT_DEFAULTS: dict[str, dict] = {
    "orbit":  {"radius": 7.0, "height": 2.5, "fov": 60, "speed": 0.6},
    "pushin": {"radius": 4.0, "height": 1.8, "fov": 50, "speed": 1.0},
    "follow": {"radius": 4.5, "height": 2.2, "fov": 70, "speed": 1.0},
    "crane":  {"radius": 6.0, "height": 9.0, "fov": 60, "speed": 0.5},
    "dolly":  {"radius": 6.0, "height": 2.0, "fov": 55, "speed": 0.8},
    "static": {"radius": 6.0, "height": 2.0, "fov": 60, "speed": 0.0},
}
FALLBACK_MAP = {"orbit": "orbit", "crane": "orbit", "pushin": "pushin", "static": "pushin",
                "follow": "follow", "dolly": "follow"}


def _http_json(url: str, body: dict | None = None, timeout: float = 2.0) -> Any:
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read().decode("utf-8") or "{}"
    return json.loads(raw)


class Camera:
    def __init__(self, showcam_port: int | None = None,
                 showcontrol: Callable[[str, dict | None], Any] | None = None,
                 account_of: Callable[[str], str] | None = None,
                 http: Callable[..., Any] = _http_json, clock: Callable[[], float] = time.time):
        from .config import SHOW
        self.base = f"http://127.0.0.1:{showcam_port or SHOW.ports['showcam']}"
        if showcontrol is None:
            from .events import showcontrol as _sc
            showcontrol = _sc
        self._sc = showcontrol
        self._account_of = account_of or self._default_account
        self._http = http
        self._clock = clock
        self._avail: tuple[float, bool] | None = None
        self.last: dict | None = None          # last shot sent (for status/tests)
        self.active_until = 0.0

    @staticmethod
    def _default_account(name: str) -> str:
        from .config import SHOW
        for c in SHOW.characters:
            if name.lower() in (c.name.lower(), c.account.lower(), c.id.lower()):
                return c.account
        return name

    # -- availability
    def showcam_up(self, force: bool = False) -> bool:
        now = self._clock()
        if not force and self._avail and now - self._avail[0] < 10:
            return self._avail[1]
        try:
            self._http(self.base + "/status", None, 1.0)
            ok = True
        except Exception:
            ok = False
        self._avail = (now, ok)
        return ok

    def mode(self) -> str:
        return "showcam" if self.showcam_up() else "fallback"

    # -- shots
    def shot(self, kind: str, target: Any, seconds: float = 8.0, **overrides: Any) -> dict:
        kind = kind if kind in SHOTS else "orbit"
        seconds = max(1.0, float(seconds))
        if isinstance(target, dict):
            tgt = {k: float(target[k]) for k in ("x", "y", "z") if k in target}
        else:
            tgt = {"player": self._account_of(str(target))}
        if self.showcam_up():
            body = {"type": kind, "target": tgt, "seconds": seconds, **SHOT_DEFAULTS[kind], **overrides}
            try:
                self._http(self.base + "/shot", body, 2.0)
                self.last = {"via": "showcam", **body}
                self.active_until = self._clock() + seconds
                return {"ok": True, **self.last}
            except Exception as e:
                self._avail = (self._clock(), False)
                err = str(e)
        else:
            err = "showcam down"
        if "player" not in tgt:
            self.last = {"via": "none", "type": kind, "target": tgt, "error": "fallback cannot aim at coordinates"}
            return {"ok": False, **self.last}
        cmd = f"show cam {FALLBACK_MAP[kind]} {tgt['player']} {int(round(seconds))}"
        try:
            out = self._sc("/cmd", {"cmd": cmd})
            ok = bool(out.get("ok", True)) if isinstance(out, dict) else True
        except Exception as e:
            ok, err = False, f"{err}; fallback: {e}"
        self.last = {"via": "fallback", "type": kind, "cmd": cmd, "target": tgt, "seconds": seconds}
        if ok:
            self.active_until = self._clock() + seconds
            return {"ok": True, **self.last}
        return {"ok": False, "error": err, **self.last}

    def stop(self) -> None:
        self.active_until = 0.0
        if self.showcam_up():
            try:
                self._http(self.base + "/shot", {"type": "stop"}, 2.0)
                self.last = {"via": "showcam", "type": "stop"}
                return
            except Exception:
                pass
        try:
            self._sc("/cmd", {"cmd": "show cam stop"})
        except Exception:
            pass
        self.last = {"via": "fallback", "type": "stop"}

    def return_to_grid(self) -> None:
        """Release the camera; the vision mixer (episode Stage) switches OBS back to the grid."""
        self.stop()
