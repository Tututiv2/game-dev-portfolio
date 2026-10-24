"""Launch / kill the five TestPilot Minecraft clients (four players + the camera).

Same launch semantics as TestPilot's `pilot.py launch` (read it: SkyblockClone/tools/testpilot/pilot.py):
    py -3 -m launcher launch Fabric-26.1.2 --dev-user <account> --server localhost:25640 --detach
run from MinecraftLauncher/, with the TestPilot env added (the launcher passes its environment to the
game). Every client also gets SHOWCAM_TITLE="AISky - <Name>" so OBS can window-capture each one; only
the camera client gets SHOWCAM=1 + SHOWCAM_PORT.

    from show import clients
    clients.plan()                  # the 5 launch specs (pure; what the tests check)
    clients.launch_all()            # staggered, waits until each is in the world
    clients.kill("Kaito")           # ONLY java processes whose command line has `--username Kaito`

Never run while the owner is gaming: `launch*` refuses while a process in `GUARD_PROCESSES` runs
(RocketLeague by default; OBS is expected at record time) unless force=True.
"""
from __future__ import annotations

import os
import subprocess
import time
from dataclasses import dataclass, field

from .config import SHOW, Character

GUARD_PROCESSES = ("RocketLeague.exe",)
CAMERA_NAME = "Camera"
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


@dataclass
class LaunchSpec:
    account: str
    title: str
    port: int
    cmd: list[str]
    cwd: str
    env_add: dict[str, str] = field(default_factory=dict)
    camera: bool = False

    def env(self, base: dict | None = None) -> dict:
        e = dict(os.environ if base is None else base)
        e.update(self.env_add)
        return e


def _pos(xy) -> str:
    return f"{int(xy[0])},{int(xy[1])}"


def server_address() -> str:
    return f"localhost:{SHOW.ports['server']}"


def build_cmd(account: str, server: str | None = None) -> list[str]:
    cmd = ["py", "-3", "-m", "launcher", "launch", SHOW.paths.get("launcher_instance", "Fabric-26.1.2"),
           "--dev-user", account, "--server", server or server_address(), "--detach"]
    return cmd


def spec_for(char: Character) -> LaunchSpec:
    mon = SHOW.monitor or {}
    tiles = mon.get("tiles") or []
    env = {
        "TESTPILOT": "1",
        "TESTPILOT_PORT": str(char.pilot_port),
        "TESTPILOT_WINDOW": str(mon.get("tile_window", "1280x720")),
        "TESTPILOT_FPS": str((SHOW.client or {}).get("fps", 30)),
        "SHOWCAM_TITLE": f"AISky - {char.name}",
    }
    if char.slot < len(tiles):
        env["TESTPILOT_POS"] = _pos(tiles[char.slot])
    return LaunchSpec(account=char.account, title=env["SHOWCAM_TITLE"], port=char.pilot_port,
                      cmd=build_cmd(char.account), cwd=SHOW.paths["launcher"], env_add=env)


def camera_spec() -> LaunchSpec:
    mon = SHOW.monitor or {}
    env = {
        "TESTPILOT": "1",
        "TESTPILOT_PORT": str(SHOW.ports["pilot_camera"]),
        "TESTPILOT_WINDOW": str(mon.get("camera_window", "1920x1080")),
        "TESTPILOT_FPS": str((SHOW.client or {}).get("fps", 30)),
        "SHOWCAM_TITLE": f"AISky - {CAMERA_NAME}",
        "SHOWCAM": "1",
        "SHOWCAM_PORT": str(SHOW.ports["showcam"]),
    }
    if mon.get("camera_pos"):
        env["TESTPILOT_POS"] = _pos(mon["camera_pos"])
    return LaunchSpec(account=SHOW.camera_account, title=env["SHOWCAM_TITLE"], port=SHOW.ports["pilot_camera"],
                      cmd=build_cmd(SHOW.camera_account), cwd=SHOW.paths["launcher"], env_add=env, camera=True)


def plan(include_camera: bool = True) -> list[LaunchSpec]:
    specs = [spec_for(c) for c in SHOW.characters]
    if include_camera:
        specs.append(camera_spec())
    return specs


def spec_by_account(account: str) -> LaunchSpec:
    for s in plan():
        if s.account.lower() == account.lower():
            return s
    raise KeyError(account)


# ------------------------------------------------------------------ process guard
def running_processes() -> set[str]:
    try:
        out = subprocess.run(["tasklist", "/FO", "CSV", "/NH"], capture_output=True, text=True,
                             creationflags=NO_WINDOW, timeout=20).stdout
    except Exception:  # noqa: BLE001
        return set()
    names = set()
    for line in out.splitlines():
        if line.startswith('"'):
            names.add(line.split('","', 1)[0].strip('"').lower())
    return names


def busy(guard=GUARD_PROCESSES) -> list[str]:
    procs = running_processes()
    return [g for g in guard if g.lower() in procs]


# ------------------------------------------------------------------ launch
class LaunchError(RuntimeError):
    pass


def launch(spec: LaunchSpec, wait: bool = True, timeout: float = 240, force: bool = False,
           log=print) -> dict:
    """Start one client detached; optionally wait until it is in the world."""
    from .pilot_bridge import get_pilot, ready
    if not force:
        b = busy()
        if b:
            raise LaunchError(f"refusing to launch {spec.account}: {', '.join(b)} is running (force=True overrides)")
    if ready(spec.port):
        log(f"[clients] {spec.account} already in world on :{spec.port}")
        return {"ok": True, "account": spec.account, "already": True}
    log(f"[clients] launching {spec.account} ({spec.title}) on :{spec.port}")
    r = subprocess.run(spec.cmd, cwd=spec.cwd, env=spec.env(), capture_output=True, text=True,
                       creationflags=NO_WINDOW, timeout=600)
    if r.returncode != 0:
        raise LaunchError(f"launcher failed for {spec.account} ({r.returncode}): "
                          f"{(r.stdout or '')[-800:]} {(r.stderr or '')[-800:]}")
    out = {"ok": True, "account": spec.account, "launcher": (r.stdout or "").strip().splitlines()[-1:]}
    if wait:
        out["ping"] = get_pilot(spec.port).wait_until_ready(timeout)
    return out


def launch_all(include_camera: bool = True, stagger_s: float = 5.0, force: bool = False, log=print) -> list[dict]:
    """Launch the clients one at a time (each waits until in world), staggered."""
    results = []
    for i, spec in enumerate(plan(include_camera)):
        if i:
            time.sleep(stagger_s)
        try:
            results.append(launch(spec, wait=True, force=force, log=log))
        except Exception as e:  # noqa: BLE001
            log(f"[clients] {spec.account}: {e}")
            results.append({"ok": False, "account": spec.account, "error": str(e)})
    return results


# ------------------------------------------------------------------ kill
def matching_pids(listing: str, account: str) -> list[int]:
    """From `pid<TAB>commandline` lines, the pids whose command line has `--username <account>`
    as a whole token (so killing 'Ren' never kills 'Renato')."""
    marker = f"--username {account} "
    pids = []
    for line in listing.splitlines():
        pid, _, cmdline = line.partition("\t")
        if pid.strip().isdigit() and marker in (cmdline + " "):
            pids.append(int(pid.strip()))
    return pids


def _java_listing() -> str:
    ps = ("Get-CimInstance Win32_Process -Filter \"Name='java.exe' OR Name='javaw.exe'\" | "
          "ForEach-Object { \"$($_.ProcessId)`t$($_.CommandLine)\" }")
    return subprocess.run(["powershell", "-NoProfile", "-Command", ps], capture_output=True, text=True,
                          creationflags=NO_WINDOW, timeout=60).stdout


def kill(account: str) -> list[int]:
    """Kill ONLY the java processes whose command line contains `--username <account>`."""
    killed = []
    for pid in matching_pids(_java_listing(), account):
        subprocess.run(["taskkill", "/PID", str(pid), "/F"], capture_output=True, creationflags=NO_WINDOW)
        killed.append(pid)
    return killed


def kill_all(include_camera: bool = True) -> dict[str, list[int]]:
    listing = _java_listing()
    out = {}
    for spec in plan(include_camera):
        pids = matching_pids(listing, spec.account)
        for pid in pids:
            subprocess.run(["taskkill", "/PID", str(pid), "/F"], capture_output=True, creationflags=NO_WINDOW)
        out[spec.account] = pids
    return out


def restart(account: str, force: bool = False, log=print) -> dict:
    kill(account)
    time.sleep(3)
    return launch(spec_by_account(account), wait=True, force=force, log=log)
