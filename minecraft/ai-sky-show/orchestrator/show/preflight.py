"""Preflight: refuse to start an episode that would ruin the owner's evening (CONTRACT §13 step 1).

    from show.preflight import run_preflight
    report = run_preflight(mock=False)
    print(report.text()); report.ok      # False -> do not start

FAIL  Rocket League running (GPU), orchestrator port 5650 taken, ShowControl down, OBS unreachable,
      < 5 GB free on the episodes drive, `claude` CLI missing for the claude_code backend.
WARN  Streamlabs running (GPU contention with five clients + OBS + TTS), < 20 GB free, OBS
      websocket disabled in its config, a pilot port answering before launch (stray client).
"""
from __future__ import annotations

import json
import shutil
import socket
import subprocess
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
BLOCKERS = ("RocketLeague.exe",)
WARNERS = ("Streamlabs OBS.exe", "Streamlabs Desktop.exe")


@dataclass
class Check:
    name: str
    status: str            # ok | warn | fail | info
    detail: str = ""


@dataclass
class Report:
    checks: list[Check] = field(default_factory=list)

    def add(self, name: str, status: str, detail: str = "") -> None:
        self.checks.append(Check(name, status, detail))

    @property
    def ok(self) -> bool:
        return not any(c.status == "fail" for c in self.checks)

    def text(self) -> str:
        tag = {"ok": " OK ", "warn": "WARN", "fail": "FAIL", "info": "info"}
        lines = [f"  [{tag.get(c.status, c.status)}] {c.name}" + (f" - {c.detail}" if c.detail else "")
                 for c in self.checks]
        lines.append("  => " + ("READY" if self.ok else "NOT READY (fix the FAIL lines)"))
        return "\n".join(lines)

    def as_dict(self) -> dict:
        return {"ok": self.ok, "checks": [c.__dict__ for c in self.checks]}


def running_processes() -> set[str]:
    try:
        out = subprocess.run(["tasklist", "/FO", "CSV", "/NH"], capture_output=True, text=True,
                             creationflags=NO_WINDOW, timeout=20).stdout
    except Exception:
        return set()
    names = set()
    for line in out.splitlines():
        if line.startswith('"'):
            names.add(line.split('","', 1)[0].strip('"').lower())
    return names


def port_in_use(port: int, host: str = "127.0.0.1") -> bool:
    try:
        with socket.create_connection((host, port), timeout=0.4):
            return True
    except OSError:
        return False


def _get_json(url: str, timeout: float = 2.0):
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8") or "{}")


def run_preflight(mock: bool = False, *, procs: Callable[[], set[str]] = running_processes,
                  port_check: Callable[[int], bool] = port_in_use, check_obs: bool = True,
                  get_json: Callable = _get_json) -> Report:
    from .config import SHOW
    from .dials import DIALS
    r = Report()
    running = procs()

    for p in BLOCKERS:
        if p.lower() in running:
            r.add(f"{p} not running", "warn" if mock else "fail",
                  "Rocket League is open" + (" (fine for a mock run: no GPU work)" if mock else " - close it first (GPU)"))
        else:
            r.add(f"{p} not running", "ok")
    sl = [p for p in WARNERS if p.lower() in running]
    if sl:
        r.add("Streamlabs", "warn", f"{', '.join(sl)} running - GPU contention with 5 clients + OBS + TTS")
    else:
        r.add("Streamlabs", "ok", "not running")

    orch = SHOW.ports["orchestrator"]
    if port_check(orch):
        r.add(f"port {orch} free (dashboard/overlay)", "fail" if not mock else "warn",
              "something already listens there - is a dashboard/episode already running?")
    else:
        r.add(f"port {orch} free (dashboard/overlay)", "ok")

    if mock:
        r.add("game server / ShowControl", "info", "mock world - not needed")
    else:
        try:
            h = get_json(f"http://127.0.0.1:{SHOW.ports['showcontrol']}/health")
            r.add("ShowControl /health", "ok" if h.get("ok") else "fail", f"tps={h.get('tps', '?')}")
        except Exception as e:
            r.add("ShowControl /health", "fail", f"show server not up on :{SHOW.ports['showcontrol']} ({e.__class__.__name__}) - run Setup / start_server first")
        live = [str(p) for p in list(SHOW.ports["pilot"]) + [SHOW.ports["pilot_camera"]] if port_check(p)]
        r.add("TestPilot clients", "info", (f"already answering on {', '.join(live)} (will be reused)" if live
                                            else "none running yet (the episode launches them)"))
        if (DIALS.get("backend") or "claude_code") == "claude_code":
            r.add("claude CLI", "ok" if shutil.which("claude") else "fail",
                  "" if shutil.which("claude") else "`claude` not on PATH (needed for the claude_code backend)")

    if check_obs and not mock:
        from .obs import read_obs_websocket_config, connect_from_config, OBSAuthError
        cfg = read_obs_websocket_config()
        if cfg and not cfg.get("server_enabled", False):
            r.add("OBS websocket enabled", "warn", "disabled in OBS (Tools -> WebSocket Server Settings) - see docs/OBS_SETUP.md")
        try:
            cl = connect_from_config(timeout=2.0)
            try:
                v = cl.request("GetVersion")
                r.add("OBS reachable", "ok", f"OBS {v.get('obsVersion', '?')}, websocket {v.get('obsWebSocketVersion', '?')}")
            finally:
                cl.close()
        except OBSAuthError as e:
            r.add("OBS reachable", "fail", str(e))
        except Exception as e:
            r.add("OBS reachable", "fail", f"cannot reach OBS on :{SHOW.ports.get('obs', 4455)} ({e.__class__.__name__}: {e}) - start OBS, enable websocket, or pass --no-obs")
    elif not mock:
        r.add("OBS", "info", "skipped (--no-obs): nothing will be recorded")

    ep = Path(SHOW.paths.get("episodes", "."))
    try:
        ep.mkdir(parents=True, exist_ok=True)
        free_gb = shutil.disk_usage(ep).free / 1e9
        status = "fail" if free_gb < 5 else "warn" if free_gb < 20 else "ok"
        r.add("disk space", status, f"{free_gb:.0f} GB free on {ep.anchor or ep}")
    except OSError as e:
        r.add("disk space", "warn", str(e))
    return r
