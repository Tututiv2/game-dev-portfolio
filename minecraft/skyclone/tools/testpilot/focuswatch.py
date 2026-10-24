"""Foreground-window watchdog for TestPilot.  READ-ONLY.

Polls GetForegroundWindow() and records every change of foreground window, with
the owning process id, executable name and window title.  It never sets focus,
never sends input, never captures the screen: the only Win32 calls are
GetForegroundWindow, GetWindowThreadProcessId, GetWindowTextW,
OpenProcess(QUERY_LIMITED_INFORMATION) and QueryFullProcessImageNameW.

A "violation" is any moment the foreground window belongs to a java/javaw
process whose command line contains the watched marker (default
``--username PilotTest``), or, with --any-java, to any java process.

    py -3 focuswatch.py --seconds 60 --out focus.jsonl
    py -3 focuswatch.py --seconds 900 --marker "--username PilotTest"

Exit code 0 = the test client never became foreground, 1 = it did.
"""
from __future__ import annotations

import argparse
import ctypes
import ctypes.wintypes as w
import json
import subprocess
import sys
import time

user32 = ctypes.windll.user32
kernel32 = ctypes.windll.kernel32
PROCESS_QUERY_LIMITED_INFORMATION = 0x1000

user32.GetForegroundWindow.restype = w.HWND
user32.GetWindowThreadProcessId.argtypes = [w.HWND, ctypes.POINTER(w.DWORD)]
user32.GetWindowTextW.argtypes = [w.HWND, w.LPWSTR, ctypes.c_int]
kernel32.OpenProcess.restype = w.HANDLE
kernel32.QueryFullProcessImageNameW.argtypes = [w.HANDLE, w.DWORD, w.LPWSTR, ctypes.POINTER(w.DWORD)]


def fg_info() -> dict:
    hwnd = user32.GetForegroundWindow()
    pid = w.DWORD(0)
    if hwnd:
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
    title = ctypes.create_unicode_buffer(256)
    if hwnd:
        user32.GetWindowTextW(hwnd, title, 256)
    exe = ""
    if pid.value:
        h = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid.value)
        if h:
            buf = ctypes.create_unicode_buffer(1024)
            n = w.DWORD(1024)
            if kernel32.QueryFullProcessImageNameW(h, 0, buf, ctypes.byref(n)):
                exe = buf.value.rsplit("\\", 1)[-1]
            kernel32.CloseHandle(h)
    return {"hwnd": int(hwnd or 0), "pid": pid.value, "exe": exe, "title": title.value}


WS_EX_NOACTIVATE = 0x08000000
GWL_EXSTYLE = -20
user32.GetWindowLongPtrW.restype = ctypes.c_ssize_t
user32.GetWindowLongPtrW.argtypes = [w.HWND, ctypes.c_int]
user32.IsWindowVisible.argtypes = [w.HWND]


def windows_of(pid: int) -> list[dict]:
    """Visible top-level windows of ``pid`` with their extended style (read-only)."""
    found = []
    PROC = ctypes.WINFUNCTYPE(w.BOOL, w.HWND, w.LPARAM)

    def cb(hwnd, _):
        owner = w.DWORD(0)
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(owner))
        if owner.value == pid and user32.IsWindowVisible(hwnd):
            t = ctypes.create_unicode_buffer(256)
            user32.GetWindowTextW(hwnd, t, 256)
            ex = user32.GetWindowLongPtrW(hwnd, GWL_EXSTYLE) & 0xFFFFFFFF
            found.append({"hwnd": int(hwnd), "title": t.value, "exstyle": hex(ex),
                          "noactivate": bool(ex & WS_EX_NOACTIVATE)})
        return True

    user32.EnumWindows(PROC(cb), 0)
    return found


def java_pids(marker: str | None) -> set[int]:
    """PIDs of java processes (optionally only those whose command line has marker)."""
    flt = "Name='java.exe' OR Name='javaw.exe'"
    ps = ("Get-CimInstance Win32_Process -Filter \"%s\" | "
          "ForEach-Object { \"$($_.ProcessId)`t$($_.CommandLine)\" }" % flt)
    try:
        out = subprocess.run(["powershell", "-NoProfile", "-Command", ps],
                             capture_output=True, text=True, timeout=30,
                             creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)).stdout
    except Exception:
        return set()
    pids = set()
    for line in out.splitlines():
        pid, _, cmd = line.partition("\t")
        if pid.strip().isdigit() and (marker is None or marker in cmd):
            pids.add(int(pid))
    return pids


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=float, default=60)
    ap.add_argument("--interval", type=float, default=0.05)
    ap.add_argument("--marker", default="--username PilotTest")
    ap.add_argument("--any-java", action="store_true")
    ap.add_argument("--out", default=None, help="append JSON lines here")
    args = ap.parse_args()

    marker = None if args.any_java else args.marker
    watched = java_pids(marker)
    last_pid_scan = time.time()
    out = open(args.out, "a", encoding="utf-8") if args.out else None

    def emit(rec):
        line = json.dumps(rec)
        print(line, flush=True)
        if out:
            out.write(line + "\n")
            out.flush()

    start = time.time()
    prev = None
    samples = 0
    violations = 0
    emit({"t": 0.0, "event": "start", "watched_pids": sorted(watched), "marker": marker})
    reported: set[int] = set()
    while time.time() - start < args.seconds:
        now = time.time()
        if now - last_pid_scan > 3:
            nw = java_pids(marker)
            if nw != watched:
                emit({"t": round(now - start, 2), "event": "watched_pids", "pids": sorted(nw)})
            watched = nw
            last_pid_scan = now
            for pid in watched - reported:
                wins = windows_of(pid)
                if wins:
                    emit({"t": round(now - start, 2), "event": "window", "pid": pid, "windows": wins})
                    reported.add(pid)
        info = fg_info()
        samples += 1
        bad = info["pid"] in watched or (args.any_java and info["exe"].lower().startswith("java"))
        if bad:
            violations += 1
        key = (info["hwnd"], info["pid"])
        if key != prev or bad:
            if key != prev or violations == 1:
                emit({"t": round(now - start, 2), "event": "foreground", **info, "VIOLATION": bad})
            prev = key
        time.sleep(args.interval)
    emit({"t": round(time.time() - start, 2), "event": "end", "samples": samples,
          "violation_samples": violations, "watched_pids": sorted(watched)})
    return 1 if violations else 0


if __name__ == "__main__":
    sys.exit(main())
