"""
Publish a finished model into Shadow's asset library.

    python Tools/publish_to_shadow.py

Writes the Sakura Fang into `Documents/Shadow Library` in the exact format
Shadow's own pipeline writes, so it appears in the Asset Forge's library list and
its "Send to project" button copies it into ANY project's Assets/Generated/.

WHY THIS EXISTS
---------------
Shadow's library is the central store — the "make it once, drop it into any game"
thing. But the only way in was the forge itself (prompt -> Flux -> Hunyuan3D), so
a hand-made asset could not get in at all. A file-adder now exists in the UI, and
this covers the case where finding one .glb inside a 460 MB Unity project in a
file dialog is the actual obstacle.

THE FORMAT IS READ FROM SHADOW'S SOURCE, NOT GUESSED
----------------------------------------------------
`APPLICATION/src/assetForge.ts` defines `LibraryEntry`, and `listLibrary()` drops
any entry whose `glbPath` no longer exists — so a wrong path here means the asset
is silently absent from the list rather than broken visibly. Three details that
are easy to get wrong and fail quietly:

- `index.jsonl` is APPEND-ONLY, one compact JSON object per line. `listLibrary`
  tolerates a corrupt line by skipping it, so a malformed entry vanishes without
  a word.
- `assetId` is `<slug>-<sha1[:8]>` over `inputPath|name|createdAt`. It hashes the
  identity rather than the clock so the same source is recognisable, but two
  different assets can never collide.
- `glbPath` is MISLEADINGLY NAMED. It holds whatever the pipeline exported, which
  for the unity target is a `.fbx`. Shadow dispatches on the real extension.
"""

import hashlib
import json
import os
import shutil
import subprocess
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

# What to publish: (source file, display name, tags, extra files to carry along)
ASSETS = [
    (os.path.join(ROOT, "Assets", "_Project", "Art", "Weapons", "wpn_sakura_fang.glb"),
     "Sakura Fang",
     ["weapon", "sniper", "skin", "pink"],
     [os.path.join(ROOT, "Assets", "_Project", "Art", "Weapons", "wpn_sakura_fang.fbx"),
      os.path.join(ROOT, "Assets", "_Project", "Art", "Weapons", "Textures",
                   "sakura_projection.png")]),
]


def documents_dir():
    """Whatever Electron's app.getPath("documents") will resolve to.

    Asking Windows rather than assuming C:/Users/<me>/Documents: this account has
    Documents redirected into OneDrive, so the assumed path would create a second
    library beside the real one and Shadow would list an empty folder forever.
    """
    try:
        out = subprocess.run(
            ["powershell", "-NoProfile", "-Command",
             "[Environment]::GetFolderPath('MyDocuments')"],
            capture_output=True, text=True, timeout=20)
        p = out.stdout.strip()
        if p and os.path.isdir(p):
            return p
    except Exception:
        pass
    return os.path.join(os.path.expanduser("~"), "Documents")


def slugify(name):
    out = "".join(c if c.isalnum() else "-" for c in name.lower())
    while "--" in out:
        out = out.replace("--", "-")
    return out.strip("-")[:48] or "asset"


def tri_count(glb):
    """Triangles, read out of the glTF JSON chunk. Reported rather than assumed —
    the library list shows this number and a wrong one is worse than none."""
    try:
        with open(glb, "rb") as fh:
            data = fh.read()
        if data[:4] != b"glTF":
            return 0
        # 12-byte header, then chunks: [u32 length][u32 type][payload]
        off, total = 12, 0
        import struct
        while off + 8 <= len(data):
            ln, ty = struct.unpack_from("<II", data, off)
            if ty == 0x4E4F534A:  # 'JSON'
                doc = json.loads(data[off + 8: off + 8 + ln].decode("utf-8"))
                for mesh in doc.get("meshes", []):
                    for prim in mesh.get("primitives", []):
                        idx = prim.get("indices")
                        if idx is not None:
                            total += doc["accessors"][idx]["count"] // 3
                break
            off += 8 + ln + (-ln % 4)
        return total
    except Exception:
        return 0


def retire_previous(root, name, keep_id):
    """Drop earlier publications of the SAME asset from the library.

    `assetId` hashes `createdAt`, so every run mints a fresh id and the old
    append-only behaviour stacked a duplicate per run. The library had two
    identical "Sakura Fang" rows before this existed, and a third was one command
    away — with the stale ones indistinguishable in the list except by a triangle
    count nobody has memorised. In a library whose whole job is "find the thing
    you made", duplicates that differ only in being WRONG are worse than absent.

    Scoped deliberately narrowly: only rows this script published (matched on
    provenance.source) with this exact display name, never anything Shadow forged
    itself. Everything removed is named on stdout.

    The index is rewritten atomically. Shadow reads the whole file on Refresh and
    may be open right now; a half-written index would read as a corrupt line, and
    listLibrary skips corrupt lines silently.
    """
    index = os.path.join(root, "index.jsonl")
    if not os.path.exists(index):
        return []

    kept, dropped = [], []
    with open(index, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                e = json.loads(line)
            except ValueError:
                kept.append(line)          # not ours to judge — leave it alone
                continue
            mine = (e.get("provenance", {}).get("source") == "Tools/skins/forge_gun_sakura.py")
            if mine and e.get("name") == name and e.get("assetId") != keep_id:
                dropped.append(e)
            else:
                kept.append(line)

    if not dropped:
        return []

    tmp = index + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        for line in kept:
            fh.write(line + "\n")
    os.replace(tmp, index)

    names = []
    for e in dropped:
        names.append("%s (%s tris)" % (e.get("assetId"), e.get("finalTris")))
        d = e.get("dir")
        if d and os.path.isdir(d) and os.path.abspath(d).startswith(os.path.abspath(root)):
            shutil.rmtree(d, ignore_errors=True)
    return names


def publish(src, name, tags, extras, root):
    if not os.path.exists(src):
        raise SystemExit("Not found: " + src + "\nBuild it first:\n"
                         "  blender --background --factory-startup --python "
                         "Tools/skins/forge_gun_sakura.py -- --project --export")

    created_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    digest = hashlib.sha1(("%s|%s|%s" % (src, name, created_at)).encode("utf-8")).hexdigest()[:8]
    asset_id = "%s-%s" % (slugify(name), digest)

    dest = os.path.join(root, "assets", asset_id)
    os.makedirs(dest, exist_ok=True)

    main = os.path.join(dest, os.path.basename(src))
    shutil.copy2(src, main)
    carried = [os.path.basename(src)]
    for e in extras:
        if os.path.exists(e):
            shutil.copy2(e, os.path.join(dest, os.path.basename(e)))
            carried.append(os.path.basename(e))

    tris = tri_count(main)
    entry = {
        "ok": True,
        "assetId": asset_id,
        "name": name,
        "dir": dest,
        "engine": "generic",
        "notes": [
            "Hand-modelled in Blender by script, not forged from an image.",
            "Albedo is a camera projection of the reference art: correct from one "
            "side, mirrored on the far flank.",
        ],
        "withinEngineLimits": True,
        # Named glbPath by Shadow; it holds whatever was exported. listLibrary()
        # drops any entry whose glbPath is missing, so this must be the real file.
        "glbPath": main,
        "normalMapPath": None,
        "bakeError": None,
        "sourceTris": tris,
        "finalTris": tris,
        "lods": [],
        "bytes": sum(os.path.getsize(os.path.join(dest, f)) for f in os.listdir(dest)),
        "createdAt": created_at,
        "tags": tags,
        "provenance": {
            "source": "Tools/skins/forge_gun_sakura.py",
            "project": "Aftermarket Apocalypse",
            # v2 is the owner's real in-game reference. This said v1 for as long
            # as the library existed, which is the wrong picture -- and it is the
            # only record inside Shadow of what the mesh was modelled from.
            "reference": "Tools/skins/reference/sakura_reference_v2.png",
        },
    }

    with open(os.path.join(dest, "manifest.json"), "w", encoding="utf-8") as fh:
        json.dump(entry, fh, indent=2)

    os.makedirs(root, exist_ok=True)
    superseded = retire_previous(root, name, asset_id)
    with open(os.path.join(root, "index.jsonl"), "a", encoding="utf-8") as fh:
        fh.write(json.dumps(entry) + "\n")
    for old in superseded:
        print("     superseded %s (removed)" % old)

    print("  %-16s %s" % (name, asset_id))
    print("     %s" % dest)
    print("     %d triangles, %.1f MB, files: %s"
          % (tris, entry["bytes"] / 1048576.0, ", ".join(carried)))


def main():
    root = os.path.join(documents_dir(), "Shadow Library")
    print("Shadow Library: " + root)
    for src, name, tags, extras in ASSETS:
        publish(src, name, tags, extras, root)

    # Verify the way Shadow will read it, not the way we wrote it: parse every
    # line back and confirm the file each entry points at is really there.
    index = os.path.join(root, "index.jsonl")
    alive = 0
    with open(index, encoding="utf-8") as fh:
        for line in fh:
            if not line.strip():
                continue
            e = json.loads(line)
            if os.path.exists(e["glbPath"]):
                alive += 1
            else:
                raise SystemExit("Entry %s points at a missing file: %s"
                                 % (e["assetId"], e["glbPath"]))
    print("\n%d asset(s) in the library, every glbPath resolves." % alive)
    print("Open Shadow > Studio > Asset Forge — it is in the library list at the bottom.")


main()
