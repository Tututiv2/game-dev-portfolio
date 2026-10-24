"""Download the SkyBlock + performance client mods (and their dependencies) for one Minecraft version from Modrinth.

    py -3 tools/fetch_skyblock_mods.py [26.2]   ->  pack/mods-<mc>/ + pack/mods-<mc>/MANIFEST.json

Every file is checked against Modrinth's published SHA-512 before it is kept. Nothing is executed.
Licences: SkyHanni LGPL-2.1, Skyblocker LGPL-3.0, the rest per their Modrinth pages; they are
redistributed unmodified, with this manifest naming each source.
"""
import hashlib
import json
import sys
import urllib.parse
import urllib.request
from pathlib import Path

MC = sys.argv[1] if len(sys.argv) > 1 else "26.2"
SLUGS = ["skyhanni", "skyblocker-liap", "fabric-api", "fabric-language-kotlin", "hypixel-mod-api", "modmenu",
         # performance (2026-10-10). cloth-config is MoreCulling's required dependency, not a feature.
         "sodium", "lithium", "ferrite-core", "immediatelyfast", "entityculling", "moreculling", "cloth-config",
         "dynamic-fps", "sodium-extra", "reeses-sodium-options"]
UA = {"User-Agent": "aura-client/1.0 (Tutut)"}
OUT = Path(__file__).resolve().parents[1] / "pack" / f"mods-{MC}"
OUT.mkdir(parents=True, exist_ok=True)


def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
        return r.read()


previous = {}
if (OUT / "MANIFEST.json").exists():
    previous = {m["slug"]: m["file"] for m in json.loads((OUT / "MANIFEST.json").read_text(encoding="utf-8"))}

manifest = []
for slug in SLUGS:
    q = urllib.parse.urlencode({"loaders": '["fabric"]', "game_versions": f'["{MC}"]'})
    versions = json.loads(get(f"https://api.modrinth.com/v2/project/{slug}/version?{q}"))
    if not versions:
        print(f"{slug}: no {MC} build")
        continue
    v = versions[0]
    f = next(x for x in v["files"] if x["primary"])
    data = get(f["url"])
    if hashlib.sha512(data).hexdigest() != f["hashes"]["sha512"]:
        sys.exit(f"{slug}: SHA-512 mismatch, not saved")
    old = previous.get(slug)  # only ever replace this slug's own previous jar
    if old and old != f["filename"] and (OUT / old).exists():
        (OUT / old).unlink()
    (OUT / f["filename"]).write_bytes(data)
    manifest.append({"slug": slug, "version": v["version_number"], "file": f["filename"], "sha512": f["hashes"]["sha512"],
                     "url": f["url"], "size": f["size"]})
    print(f"{slug}: {v['version_number']} ok ({f['size'] // 1024} KB)")

(OUT / "MANIFEST.json").write_text(json.dumps(manifest, indent=1), encoding="utf-8")
