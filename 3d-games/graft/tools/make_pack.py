#!/usr/bin/env python3
"""
Build a downloadable content pack from a directory.

The point of this script is that nothing downstream is ever hand-edited. Drop a
folder of models, textures or audio in, and it writes the two JSON files the
game's downloader reads, with a real size and a real SHA-256 for every file:

    app/packs/packs.json        the index -- one summary record per pack
    app/packs/<id>/pack.json    the file list, with sizes and hashes
    app/packs/<id>/<path>       the files themselves

Usage
    py -3 tools/make_pack.py build <src-dir> --id voices --name "Voice pack"
    py -3 tools/make_pack.py build app/packs/voices --id voices --in-place
    py -3 tools/make_pack.py index          # rebuild packs.json from what is there
    py -3 tools/make_pack.py verify         # re-hash every file on disk
    py -3 tools/make_pack.py list

Versions are content-addressed in effect: the manifest records a hash of the
whole pack, and a rebuild whose hash changed bumps the version. The version is
part of the browser cache name, so a bumped version is a NEW cache -- a stale
half of an old pack can never be mistaken for the new one.
"""
from __future__ import annotations

import argparse
import fnmatch
import hashlib
import json
import shutil
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUT = ROOT / "app" / "packs"
INDEX_NAME = "packs.json"
MANIFEST_NAME = "pack.json"

# app/src/view/packs.js holds the whole of one file in memory to hash it, so
# file size is the memory bound, not pack size. Split anything larger.
MAX_FILE_BYTES = 64 * 1024 * 1024

SKIP_DIRS = {"__pycache__", ".git", ".svn", "node_modules", ".DS_Store"}
SKIP_FILES = {MANIFEST_NAME, ".DS_Store", "Thumbs.db", "desktop.ini"}


def sha256_file(path: Path) -> tuple[str, int]:
    h = hashlib.sha256()
    n = 0
    with path.open("rb") as f:
        while True:
            b = f.read(1 << 20)
            if not b:
                break
            h.update(b)
            n += len(b)
    return h.hexdigest(), n


def gather(src: Path, excludes: list[str]) -> list[Path]:
    out = []
    for p in sorted(src.rglob("*")):
        if p.is_dir():
            continue
        rel = p.relative_to(src)
        if any(part in SKIP_DIRS or part.startswith(".") for part in rel.parts[:-1]):
            continue
        if rel.name in SKIP_FILES or rel.name.startswith("."):
            continue
        s = rel.as_posix()
        if any(fnmatch.fnmatch(s, g) or fnmatch.fnmatch(rel.name, g) for g in excludes):
            continue
        out.append(p)
    return out


def pack_hash(files: list[dict]) -> str:
    """One hash over the whole pack: the file list AND every file's hash."""
    h = hashlib.sha256()
    for f in files:
        h.update(f["path"].encode("utf-8"))
        h.update(b"\x00")
        h.update(f["sha256"].encode("ascii"))
        h.update(b"\x00")
        h.update(str(f["bytes"]).encode("ascii"))
        h.update(b"\n")
    return h.hexdigest()[:16]


def next_version(old: str | None, explicit: str | None, changed: bool) -> str:
    if explicit:
        return str(explicit)
    if old is None:
        return "1"
    if not changed:
        return old
    if old.isdigit():
        return str(int(old) + 1)
    raise SystemExit(
        "the pack changed and its version %r is not a plain number, so it cannot be\n"
        "bumped automatically. Pass --version with the new one." % old)


def build(args) -> int:
    out_root = Path(args.out).resolve()
    src = Path(args.src).resolve()
    if not src.is_dir():
        raise SystemExit("no such directory: %s" % src)

    pack_id = args.id or src.name
    if not pack_id.replace("-", "").replace("_", "").replace(".", "").isalnum():
        raise SystemExit("a pack id becomes a URL and a cache name, so it must be "
                         "letters, digits, dot, dash or underscore: %r" % pack_id)

    dst = out_root / pack_id
    if args.in_place:
        if src != dst:
            raise SystemExit("--in-place means the source IS %s (got %s)" % (dst, src))
    else:
        if args.clean and dst.exists():
            shutil.rmtree(dst)
        dst.mkdir(parents=True, exist_ok=True)

    picked = gather(src, args.exclude)
    if not picked:
        raise SystemExit("%s has no files to pack" % src)

    files = []
    total = 0
    big = []
    for p in picked:
        rel = p.relative_to(src).as_posix()
        target = dst / rel
        if not args.in_place:
            target.parent.mkdir(parents=True, exist_ok=True)
            if not args.dry_run:
                shutil.copy2(p, target)
        digest, n = sha256_file(p)
        if n > MAX_FILE_BYTES:
            big.append((rel, n))
        files.append({"path": rel, "bytes": n, "sha256": digest})
        total += n

    h = pack_hash(files)
    manifest_path = dst / MANIFEST_NAME
    old = read_json(manifest_path) or {}
    changed = old.get("hash") != h
    version = next_version(old.get("version"), args.version, changed)

    manifest = {
        "id": pack_id,
        "name": args.name or old.get("name") or pack_id,
        "description": args.description or old.get("description") or "",
        "version": version,
        "hash": h,
        "bytes": total,
        "count": len(files),
        "built": time.strftime("%Y-%m-%d %H:%M:%S"),
        "files": files,
    }
    if args.dry_run:
        print(json.dumps({k: v for k, v in manifest.items() if k != "files"}, indent=2))
        print("  %d files, %s (dry run, nothing written)" % (len(files), human(total)))
        return 0

    write_json(manifest_path, manifest)
    write_index(out_root)

    print("pack %-16s v%-6s %5d files  %10s  %s" % (pack_id, version, len(files), human(total), h))
    if not changed and old:
        print("  content unchanged, version kept at %s" % version)
    for rel, n in big:
        print("  WARNING %s is %s -- the downloader holds one file in memory to hash it.\n"
              "          Split it under %s." % (rel, human(n), human(MAX_FILE_BYTES)))
    print("  -> %s" % manifest_path)
    print("  -> %s" % (out_root / INDEX_NAME))
    return 0


def write_index(out_root: Path) -> dict:
    packs = []
    for m in sorted(out_root.glob("*/" + MANIFEST_NAME)):
        d = read_json(m)
        if not d or "id" not in d:
            continue
        packs.append({
            "id": d["id"],
            "name": d.get("name", d["id"]),
            "description": d.get("description", ""),
            "version": str(d.get("version", "1")),
            "bytes": d.get("bytes", 0),
            "count": d.get("count", len(d.get("files", []))),
            # The index stays small on purpose: the file list, which is the part
            # that grows with the pack, is fetched only when you install it.
            "url": "packs/%s/%s" % (d["id"], MANIFEST_NAME),
        })
    index = {"generated": time.strftime("%Y-%m-%d %H:%M:%S"), "packs": packs}
    out_root.mkdir(parents=True, exist_ok=True)
    write_json(out_root / INDEX_NAME, index)
    return index


def verify(args) -> int:
    out_root = Path(args.out).resolve()
    bad = 0
    seen = 0
    for m in sorted(out_root.glob("*/" + MANIFEST_NAME)):
        d = read_json(m)
        if not d:
            print("FAIL %s is not readable JSON" % m)
            bad += 1
            continue
        base = m.parent
        for f in d.get("files", []):
            seen += 1
            p = base / f["path"]
            if not p.is_file():
                print("FAIL %s/%s is missing" % (d["id"], f["path"]))
                bad += 1
                continue
            digest, n = sha256_file(p)
            if n != f["bytes"]:
                print("FAIL %s/%s is %d bytes, the manifest says %d" % (d["id"], f["path"], n, f["bytes"]))
                bad += 1
            elif digest != f["sha256"]:
                print("FAIL %s/%s does not match its hash" % (d["id"], f["path"]))
                bad += 1
        print("%-16s v%-6s %d files" % (d.get("id", "?"), d.get("version", "?"), len(d.get("files", []))))
    print("\n%d files checked, %d bad" % (seen, bad))
    return 1 if bad else 0


def do_index(args) -> int:
    out_root = Path(args.out).resolve()
    idx = write_index(out_root)
    print("%d packs -> %s" % (len(idx["packs"]), out_root / INDEX_NAME))
    return 0


def do_list(args) -> int:
    out_root = Path(args.out).resolve()
    idx = read_json(out_root / INDEX_NAME)
    if not idx:
        print("no pack index at %s -- the game plays fine without one." % (out_root / INDEX_NAME))
        return 0
    for p in idx.get("packs", []):
        print("%-16s v%-6s %5d files  %10s  %s" % (
            p["id"], p["version"], p["count"], human(p["bytes"]), p.get("description", "")))
    return 0


def human(n: int) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return "%.1f %s" % (n, unit) if unit != "B" else "%d B" % n
        n /= 1024.0
    return str(n)


def read_json(p: Path):
    try:
        return json.loads(p.read_text("utf-8"))
    except Exception:
        return None


def write_json(p: Path, data) -> None:
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=str(DEFAULT_OUT), help="where packs are served from (default app/packs)")
    sub = ap.add_subparsers(dest="cmd")

    b = sub.add_parser("build", help="build a pack from a directory")
    b.add_argument("src")
    b.add_argument("--id", help="pack id (default: the folder name)")
    b.add_argument("--name")
    b.add_argument("--description", default=None)
    b.add_argument("--version", default=None, help="explicit version; otherwise bumped when content changes")
    b.add_argument("--in-place", action="store_true", help="the source already IS app/packs/<id>")
    b.add_argument("--clean", action="store_true", help="wipe the destination first")
    b.add_argument("--dry-run", action="store_true")
    b.add_argument("--exclude", action="append", default=[], help="glob, may be repeated")
    b.add_argument("--out", default=str(DEFAULT_OUT))
    b.set_defaults(fn=build)

    i = sub.add_parser("index", help="rebuild packs.json from the pack manifests on disk")
    i.add_argument("--out", default=str(DEFAULT_OUT))
    i.set_defaults(fn=do_index)

    v = sub.add_parser("verify", help="re-hash every file and compare to its manifest")
    v.add_argument("--out", default=str(DEFAULT_OUT))
    v.set_defaults(fn=verify)

    l = sub.add_parser("list", help="show the index")
    l.add_argument("--out", default=str(DEFAULT_OUT))
    l.set_defaults(fn=do_list)

    args = ap.parse_args(argv)
    if not getattr(args, "fn", None):
        ap.print_help()
        return 2
    return args.fn(args)


if __name__ == "__main__":
    raise SystemExit(main())
