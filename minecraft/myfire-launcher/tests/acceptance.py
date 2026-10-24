"""Launches real instances and waits for the game to prove it reached the title screen.

Not part of ``run_tests.py``: it needs a GPU, a window, several gigabytes on disk and about
a minute per instance. It is the script that produced the evidence table in
``KNOWN_LIMITATIONS.md``, kept so those claims can be re-checked rather than trusted.

    py -3 tests/acceptance.py                 every instance that exists
    py -3 tests/acceptance.py MyFireServer    only instances whose slug matches

Each launch is killed once it reaches the title screen, so nothing is left running.
"""
from __future__ import annotations

import os
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
os.environ.setdefault("MYFIRE_DEV", "1")

from launcher import logs  # noqa: E402
from launcher.accounts import LocalAccount  # noqa: E402
from launcher.instances import InstanceStore  # noqa: E402
from launcher.launch import prepare, spawn  # noqa: E402
from launcher.paths import Layout  # noqa: E402

logs.setup(None, console=False)

#: Two of these together mean the game is up: sound is the last subsystem to start, and
#: the texture atlases are built for the title screen itself.
TITLE_MARKERS = ("Sound engine started", "OpenAL initialized", "Created:")
FATAL = ("Failed to start", "A fatal error", "Could not find or load main class",
         "UnsatisfiedLinkError", "NoClassDefFoundError")

TIMEOUT = 240


def run_one(layout: Layout, slug: str, username: str = "Tutut") -> bool:
    store = InstanceStore(layout)
    inst = store.get(slug)
    if inst is None:
        print(f"  ?     {slug}: no such instance")
        return False

    t0 = time.time()
    try:
        plan = prepare(layout, inst, LocalAccount(username))
    except Exception as exc:  # noqa: BLE001
        print(f"  FAIL  {slug}: prepare failed: {exc}")
        return False

    natives = len(list(plan.natives_dir.iterdir())) if plan.natives_dir.is_dir() else 0
    cp = plan.argv[plan.argv.index("-cp") + 1] if "-cp" in plan.argv else ""
    proc = spawn(plan)

    hits: set[str] = set()
    fatal = ""
    while time.time() - t0 < TIMEOUT and proc.running:
        for line in proc.tail_lines(4000):
            for m in TITLE_MARKERS:
                if m in line:
                    hits.add(m)
            for f in FATAL:
                if f in line:
                    fatal = line
        if len(hits) >= 2 or fatal:
            break
        time.sleep(1.0)

    elapsed = time.time() - t0
    good = len(hits) >= 2 and not fatal
    proc.kill()

    verdict = "ok  " if good else "FAIL"
    print(f"  {verdict}  {slug:<28} {elapsed:5.1f}s  java={plan.java.parents[1].name:<22}"
          f" natives={natives:<3} cp={cp.count(os.pathsep) + 1 if cp else 0}")
    if fatal:
        print(f"        fatal: {fatal.strip()[:110]}")
    elif not good:
        for line in proc.tail_lines(6):
            print(f"        | {line[:110]}")
    return good


def main(argv: list[str]) -> int:
    layout = Layout()
    wanted = argv[1:]
    slugs = [i.slug for i in InstanceStore(layout).all()
             if not wanted or any(w.lower() in i.slug.lower() for w in wanted)]
    if not slugs:
        print("no matching instances")
        return 1
    print(f"launching {len(slugs)} instance(s); each is killed at the title screen\n")
    results = [run_one(layout, s) for s in slugs]
    passed = sum(results)
    print(f"\n{passed}/{len(results)} reached the title screen")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
