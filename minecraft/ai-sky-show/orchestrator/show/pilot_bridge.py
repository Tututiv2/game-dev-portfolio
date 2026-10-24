"""Bridge to TestPilot's `pilot.py` (SkyblockClone/tools/testpilot) - imported, never copied or edited.

    from show.pilot_bridge import get_pilot, health, PilotError
    p = get_pilot(25650)            # a pilot.Pilot bound to that client's TestPilot port
    health(25650)                   # {"ok", "inWorld", "player", "screen", "error"} - never raises

TestPilot drives the Minecraft client from INSIDE the game over localhost HTTP. Nothing here (or in
pilot.py) touches OS input, window focus or the desktop.
"""
from __future__ import annotations

import importlib
import sys
import threading
from pathlib import Path
from typing import Any

from .config import SHOW

_lock = threading.Lock()
_mod = None


class BridgeError(RuntimeError):
    """TestPilot's pilot.py could not be imported (path missing / broken)."""


def testpilot_dir() -> Path:
    return Path(SHOW.paths["testpilot"])


def pilot_module():
    """Import `pilot` from the TestPilot folder via sys.path (once)."""
    global _mod
    with _lock:
        if _mod is not None:
            return _mod
        d = testpilot_dir()
        if not (d / "pilot.py").is_file():
            raise BridgeError(f"TestPilot pilot.py not found in {d} (config paths.testpilot)")
        if str(d) not in sys.path:
            sys.path.insert(0, str(d))
        try:
            _mod = importlib.import_module("pilot")
        except Exception as e:  # noqa: BLE001
            raise BridgeError(f"cannot import TestPilot pilot.py: {e}") from e
        return _mod


try:  # `except PilotError` must name a real class (exception matching ignores __subclasscheck__)
    PilotError = pilot_module().PilotError
except BridgeError:  # TestPilot folder missing: macros still import; every call then fails cleanly
    PilotError = RuntimeError


def get_pilot(port: int, timeout: float = 60.0) -> Any:
    """A `pilot.Pilot` for the client on `port`. `timeout` = default per-request HTTP timeout."""
    return pilot_module().Pilot(port=port, timeout=timeout)


def health(port: int) -> dict:
    """Quick health probe of one client. Never raises."""
    out = {"ok": False, "port": port, "inWorld": False, "player": None, "screen": None, "error": None}
    try:
        ping = get_pilot(port, timeout=5).ping()
        out.update(ok=True, inWorld=bool(ping.get("inWorld")), player=ping.get("player"),
                   screen=ping.get("screenType"), focused=bool(ping.get("focused") or ping.get("osFocused")))
    except Exception as e:  # noqa: BLE001
        out["error"] = str(e)[:200]
    return out


def ready(port: int) -> bool:
    """In a world, no loading screen."""
    h = health(port)
    if not (h["ok"] and h["inWorld"]):
        return False
    try:
        return h["screen"] not in pilot_module().Pilot.LOADING_SCREENS
    except BridgeError:
        return True


def all_health() -> dict[str, dict]:
    """Health of the four player clients + the camera client, keyed by account."""
    out = {}
    for c in SHOW.characters:
        out[c.account] = health(c.pilot_port)
    out[SHOW.camera_account] = health(SHOW.ports["pilot_camera"])
    return out


def wait_ready(port: int, timeout: float = 180) -> dict:
    return get_pilot(port).wait_until_ready(timeout)
