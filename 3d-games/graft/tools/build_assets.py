#!/usr/bin/env python3
"""Drives Blender headless and turns its output into a pack the game can load.

    py -3 tools/build_assets.py            build every model into app/assets/models
    py -3 tools/build_assets.py --check    report on the pack already on disk
    py -3 tools/build_assets.py --clean    delete the pack (proves the fallback)

The pack is an ENHANCEMENT. `app/assets/models/` may be absent, half-written or
deleted and the game plays exactly as it does with no models at all -- the
runtime treats a missing pack.json as "there are no models" and the renderer
falls back to the procedural shapes it has always drawn. `--clean` exists so
that claim can be exercised rather than asserted.

Blender is found in this order: $GRAFT_BLENDER, the standard install path, then
whatever `blender` is on PATH. If none of them run, this says so with the real
error and changes nothing.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "tools" / "blender_models.py"
OUT = ROOT / "app" / "assets" / "models"

CANDIDATES = [
    r"C:\Program Files\Blender Foundation\Blender 5.1\blender.exe",
    r"C:\Program Files\Blender Foundation\Blender 4.2\blender.exe",
    r"/Applications/Blender.app/Contents/MacOS/Blender",
    "/usr/bin/blender",
]

# A phone has to draw these a thousand at a time through InstancedMesh.
TRI_BUDGET = 900


def find_blender() -> str | None:
    env = os.environ.get("GRAFT_BLENDER")
    if env and Path(env).exists():
        return env
    for c in CANDIDATES:
        if Path(c).exists():
            return c
    return shutil.which("blender")


def human(n: int) -> str:
    return f"{n/1024:.1f} KB" if n >= 1024 else f"{n} B"


def build(out: Path) -> int:
    blender = find_blender()
    if not blender:
        print("Blender not found. Set GRAFT_BLENDER to blender.exe, or install it.")
        print("Nothing was written. The game runs unchanged without a model pack.")
        return 2

    out.mkdir(parents=True, exist_ok=True)
    cmd = [blender, "--background", "--factory-startup", "--python", str(SCRIPT),
           "--", "--out", str(out)]
    print("blender:", blender)
    t0 = time.time()
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=900)
    except Exception as e:                       # noqa: BLE001 - report, never raise
        print("Blender could not be started:", e)
        return 2
    dt = time.time() - t0

    if p.returncode != 0 or not (out / "_meta.json").exists():
        print(f"Blender exited {p.returncode} after {dt:.1f}s and produced no manifest.")
        tail = (p.stdout or "").strip().splitlines()[-25:]
        err = (p.stderr or "").strip().splitlines()[-25:]
        for line in tail + err:
            print("  |", line)
        return 1

    for line in (p.stdout or "").splitlines():
        if line.startswith("MODEL "):
            print("  " + line[6:])

    meta = json.loads((out / "_meta.json").read_text(encoding="utf-8"))
    return write_pack(out, meta, dt)


def write_pack(out: Path, meta: dict, seconds: float) -> int:
    models, total, over = {}, 0, []
    for kind, m in sorted(meta.get("models", {}).items()):
        f = out / m["file"]
        if not f.exists():
            print(f"  {kind}: exporter reported it but {m['file']} is not on disk -- skipped")
            continue
        size = f.stat().st_size
        total += size
        if m["tris"] > TRI_BUDGET:
            over.append(f"{kind} {m['tris']}")
        models[kind] = {
            "file": m["file"],
            "bytes": size,
            "tris": m["tris"],
            "verts": m["verts"],
            "materials": m["materials"],
            "fit": m["fit"],
        }

    pack = {
        "v": 1,
        "generated": meta.get("blender", "blender"),
        "budget": TRI_BUDGET,
        "models": models,
    }
    (out / "pack.json").write_text(json.dumps(pack, indent=1), encoding="utf-8")

    print()
    print(f"{'model':<12}{'tris':>7}{'verts':>7}{'mats':>6}{'fit':>7}{'size':>11}")
    for kind, m in models.items():
        print(f"{kind:<12}{m['tris']:>7}{m['verts']:>7}{m['materials']:>6}"
              f"{m['fit']:>7.2f}{human(m['bytes']):>11}")
    print(f"{'':<12}{'':>7}{'':>7}{'':>6}{'':>7}{human(total):>11}  total")
    print(f"\n{len(models)} models in {seconds:.1f}s -> {out}")
    if over:
        print("OVER BUDGET (a phone draws these a thousand at a time):", ", ".join(over))
        return 1
    return 0


def check(out: Path) -> int:
    pj = out / "pack.json"
    if not pj.exists():
        print(f"No pack at {pj}. That is a supported state: the game draws its")
        print("procedural shapes and nothing is missing from it.")
        return 0
    try:
        pack = json.loads(pj.read_text(encoding="utf-8"))
    except Exception as e:                       # noqa: BLE001
        print("pack.json is not readable JSON:", e)
        return 1
    bad = 0
    for kind, m in pack.get("models", {}).items():
        f = out / m["file"]
        if not f.exists():
            print(f"  {kind}: {m['file']} listed but missing")
            bad += 1
            continue
        size = f.stat().st_size
        flag = "" if size == m.get("bytes") else f"  (size {size} != recorded {m['bytes']})"
        print(f"  {kind:<12}{m['tris']:>6} tris {human(size):>10}{flag}")
    print(f"{len(pack.get('models', {}))} models, {bad} broken")
    return 1 if bad else 0


def clean(out: Path) -> int:
    if not out.exists():
        print("nothing to clean")
        return 0
    n = 0
    for f in out.iterdir():
        if f.is_file():
            f.unlink()
            n += 1
    print(f"removed {n} files from {out} -- the game now runs on procedural shapes alone")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--out", default=str(OUT))
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--clean", action="store_true")
    a = ap.parse_args()
    out = Path(a.out).resolve()
    if a.check:
        return check(out)
    if a.clean:
        return clean(out)
    return build(out)


if __name__ == "__main__":
    sys.exit(main())
