r"""Build ``MyHorrorMod-26.2.mrpack`` -- the client side of the horror server.

A ``.mrpack`` is a zip holding ``modrinth.index.json`` plus an ``overrides/`` tree. Any
modern launcher imports one and gets an isolated instance: Lovely Launcher (Import pack),
Prism, the Modrinth app. That is strictly better than ``FriendPack``'s five hand-typed
steps, and it is the same file for you and for whoever you are playing with.

**The split between `files` and `overrides` here is the whole design.**

* ``fabric-api`` and ``voicechat`` are *referenced*, not carried: the index gives their
  Modrinth CDN URL, SHA-1 and size, and the importing launcher fetches and verifies them.
  The pack stays ~160 KB instead of ~8 MB, and nobody is redistributing somebody else's
  mod. Both hashes are checked against the local copies before the pack is written, so a
  URL that has silently started serving a different build is caught here rather than as a
  version mismatch mid-game.
* ``secondwatch`` is *carried* in ``overrides/``, because it is ours and it is not
  published anywhere to reference.

``overrides/servers.dat`` puts the horror server in the multiplayer list already, so the
import ends at "double-click, join" rather than "now type an address". servers.dat is
**uncompressed** NBT -- level.dat is gzipped and the two look interchangeable, which is
the trap that eats an afternoon. Written by hand here to keep this tool dependency-free,
then parsed straight back before the pack is sealed.
"""
from __future__ import annotations

import hashlib
import json
import struct
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "MyHorrorMod-26.2.mrpack"

MC_VERSION = "26.2"
LOADER_VERSION = "0.19.3"
MOD_VERSION = "0.1.0"

#: The server this pack is built to join. Port 25597 is the horror server in
#: MyFireServer/; 25595 and 25596 are the two Forge servers. The gradle dev server
#: moved to 25599 so it can never fight this one for the world lock again.
SERVERS = [
    {"name": "Second Watch (this PC)", "ip": "localhost:25597"},
]

#: Referenced, not carried. `sha1` is asserted against the local jar before packing, so
#: these cannot drift apart silently -- and both ends running identical builds is not a
#: nicety for this mod: a mismatched phantom is interpreted differently on each end.
REFERENCED = [
    {
        "path": "mods/fabric-api-0.157.0+26.2.jar",
        "local": ROOT / "FriendPack" / "mods" / "fabric-api-0.157.0+26.2.jar",
        "url": "https://cdn.modrinth.com/data/P7dR8mSH/versions/vmQp7ixA/"
               "fabric-api-0.157.0%2B26.2.jar",
        "sha1": "0deb9d110def35ebcaf5f15a9e6c8b8e85685319",
        "size": 2533297,
    },
    {
        "path": "mods/voicechat-fabric-2.6.22+26.2.jar",
        "local": ROOT / "FriendPack" / "mods" / "voicechat-fabric-2.6.22+26.2.jar",
        "url": "https://cdn.modrinth.com/data/9eGKb6K1/versions/DKSq5wO6/"
               "voicechat-fabric-2.6.22%2B26.2.jar",
        "sha1": "f36fca7d4d091421e55f2a22d4e3cd0f462082b9",
        "size": 5576838,
    },
]

CARRIED = ROOT / "build" / "libs" / f"secondwatch-{MOD_VERSION}.jar"

READ_ME = """Second Watch - co-op horror for Minecraft 26.2 (Fabric)

This pack installs three mods into their own isolated instance:

  secondwatch      the mod itself
  fabric-api       it does not run without this
  voicechat        Simple Voice Chat - the phantom's voice needs it

The server is already in your multiplayer list as "Second Watch (this PC)".
That address is localhost:25597, which only works on the machine running
MyFireServer\\Start Horror Server.bat. Playing with someone else? They join
the address you give them, not this one.

Both of you must run the SAME version of every mod. A mismatched build means
each end interprets the phantom differently, and the horror becomes a crash.

You must be logged into a REAL Minecraft account and the server must be
online-mode. The phantom wears your partner's signed skin texture; an offline
server never receives one, and a phantom in the wrong skin is not a weaker
scare, it is a mannequin standing in a field.
"""


# ---------------------------------------------------------------------------------
# servers.dat -- uncompressed NBT, root compound with an empty name
# ---------------------------------------------------------------------------------

TAG_END, TAG_STRING, TAG_LIST, TAG_COMPOUND = 0, 8, 9, 10


def _string(value: str) -> bytes:
    raw = value.encode("utf-8")
    return struct.pack(">H", len(raw)) + raw


def servers_dat(servers: list[dict]) -> bytes:
    body = bytearray()
    body += bytes([TAG_LIST]) + _string("servers")
    body += bytes([TAG_COMPOUND]) + struct.pack(">i", len(servers))
    for server in servers:
        for key in ("ip", "name"):
            body += bytes([TAG_STRING]) + _string(key) + _string(server[key])
        body += bytes([TAG_END])
    body += bytes([TAG_END])
    return bytes([TAG_COMPOUND]) + _string("") + bytes(body)


def check_servers_dat(data: bytes) -> list[dict]:
    """Parse it back. A malformed servers.dat does not error in game -- it silently
    shows an empty server list, which looks like the pack simply forgot to include one."""
    if data[0] != TAG_COMPOUND:
        raise SystemExit("servers.dat: root is not a compound")
    at = 1 + 2 + struct.unpack(">H", data[1:3])[0]
    if data[at] != TAG_LIST:
        raise SystemExit("servers.dat: first tag is not the server list")
    at += 1
    name_len = struct.unpack(">H", data[at:at + 2])[0]
    at += 2
    if data[at:at + name_len] != b"servers":
        raise SystemExit("servers.dat: the list is not called 'servers'")
    at += name_len
    if data[at] != TAG_COMPOUND:
        raise SystemExit("servers.dat: the list does not hold compounds")
    at += 1
    count = struct.unpack(">i", data[at:at + 4])[0]
    at += 4
    out = []
    for _ in range(count):
        entry = {}
        while data[at] != TAG_END:
            at += 1
            key_len = struct.unpack(">H", data[at:at + 2])[0]
            at += 2
            key = data[at:at + key_len].decode()
            at += key_len
            value_len = struct.unpack(">H", data[at:at + 2])[0]
            at += 2
            entry[key] = data[at:at + value_len].decode()
            at += value_len
        at += 1
        out.append(entry)
    return out


# ---------------------------------------------------------------------------------

def sha1(path: Path) -> str:
    return hashlib.sha1(path.read_bytes()).hexdigest()


def main() -> int:
    if not CARRIED.is_file():
        print(f"MISSING: {CARRIED}\n  Run gradlew.bat build first (or Friend Pack.bat).")
        return 1

    files = []
    for entry in REFERENCED:
        local = entry["local"]
        if not local.is_file():
            print(f"MISSING: {local}\n  Run Friend Pack.bat, or drop the jar in.")
            return 1
        actual = sha1(local)
        if actual != entry["sha1"]:
            print(f"HASH MISMATCH for {local.name}\n"
                  f"  index says {entry['sha1']}\n"
                  f"  local jar  {actual}\n"
                  f"  The pack would download a different build than the one this mod "
                  f"was tested against. Fix the index or the jar; do not ship both.")
            return 1
        if local.stat().st_size != entry["size"]:
            print(f"SIZE MISMATCH for {local.name}: index {entry['size']}, "
                  f"local {local.stat().st_size}")
            return 1
        files.append({
            "path": entry["path"],
            "hashes": {"sha1": entry["sha1"]},
            "env": {"client": "required", "server": "required"},
            "downloads": [entry["url"]],
            "fileSize": entry["size"],
        })
        print(f"  referenced  {local.name}  ({actual[:12]}... verified)")

    index = {
        "formatVersion": 1,
        "game": "minecraft",
        "versionId": MOD_VERSION,
        "name": "MyHorrorMod",
        "summary": "Second Watch - co-op horror. One of you is not who they were.",
        "files": files,
        "dependencies": {
            "minecraft": MC_VERSION,
            "fabric-loader": LOADER_VERSION,
        },
    }

    dat = servers_dat(SERVERS)
    parsed = check_servers_dat(dat)
    if parsed != [{"ip": s["ip"], "name": s["name"]} for s in SERVERS]:
        raise SystemExit(f"servers.dat did not round-trip: {parsed}")
    print(f"  servers.dat round-tripped: {parsed}")

    if OUT.exists():
        OUT.unlink()
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("modrinth.index.json", json.dumps(index, indent=2))
        zf.write(CARRIED, f"overrides/mods/{CARRIED.name}")
        zf.writestr("overrides/servers.dat", dat)
        zf.writestr("overrides/SECOND WATCH - read me.txt", READ_ME)
        zf.writestr("modlist.html",
                    "<ul><li>Second Watch " + MOD_VERSION + "</li>"
                    "<li>Fabric API 0.157.0+26.2</li>"
                    "<li>Simple Voice Chat 2.6.22+26.2</li></ul>")
    print(f"  carried     {CARRIED.name}")
    print(f"\nWrote {OUT}  ({OUT.stat().st_size / 1024:.0f} KB)")
    print("Import it: Lovely Launcher -> Import pack. Or send it to your friend.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
