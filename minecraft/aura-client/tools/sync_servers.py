"""Copies every server you already have into the dev client's Multiplayer list.

Play.bat runs this before every launch, so a server added in your normal
Minecraft (or a MyFire Launcher instance) shows up here next time too.

Rules:
  * Nothing is ever removed. Servers already in the dev list stay exactly as
    they are - including ones you added or renamed inside the dev client.
  * One entry per ADDRESS. hypixel.net and hypixel.net:25565 are the same
    server, so the first name seen wins and later duplicates are skipped.
  * Server icons are carried over, so the list looks like it does elsewhere.
  * Stdlib only, and any failure leaves the existing file untouched: a broken
    sync must never stop the game from launching.

servers.dat is uncompressed NBT (a compound holding a list "servers" of
compounds), which is small enough to read and write by hand here.
"""
import os
import struct
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
TARGET = HERE / "run" / "client" / "servers.dat"
GAME = HERE.parent

SOURCES = [
    Path(os.environ.get("APPDATA", "")) / ".minecraft" / "servers.dat",
    *sorted((GAME / "MinecraftLauncher" / "data" / "instances").glob("*/servers.dat")),
    *sorted((GAME / "MyFireServer" / "_client").glob("*/servers.dat")),
]

# ---------------------------------------------------------------- NBT


class Reader:
    def __init__(self, data):
        self.d = data
        self.p = 0

    def take(self, n):
        v = self.d[self.p:self.p + n]
        if len(v) != n:
            raise ValueError("truncated NBT")
        self.p += n
        return v

    def string(self):
        n = struct.unpack(">H", self.take(2))[0]
        return self.take(n).decode("utf-8")

    def payload(self, t):
        if t == 1:
            return struct.unpack(">b", self.take(1))[0]
        if t == 2:
            return struct.unpack(">h", self.take(2))[0]
        if t == 3:
            return struct.unpack(">i", self.take(4))[0]
        if t == 4:
            return struct.unpack(">q", self.take(8))[0]
        if t == 5:
            return struct.unpack(">f", self.take(4))[0]
        if t == 6:
            return struct.unpack(">d", self.take(8))[0]
        if t == 7:
            n = struct.unpack(">i", self.take(4))[0]
            return self.take(n)
        if t == 8:
            return self.string()
        if t == 9:
            et = self.take(1)[0]
            n = struct.unpack(">i", self.take(4))[0]
            return (et, [self.payload(et) for _ in range(n)])
        if t == 10:
            out = []  # ordered (name, type, value) - order is preserved on write
            while True:
                tt = self.take(1)[0]
                if tt == 0:
                    return out
                name = self.string()  # name BEFORE value: that is the byte order
                out.append((name, tt, self.payload(tt)))
        if t == 11:
            n = struct.unpack(">i", self.take(4))[0]
            return list(struct.unpack(">%di" % n, self.take(4 * n)))
        if t == 12:
            n = struct.unpack(">i", self.take(4))[0]
            return list(struct.unpack(">%dq" % n, self.take(8 * n)))
        raise ValueError("unknown NBT tag %d" % t)


def write_payload(out, t, v):
    if t == 1:
        out += struct.pack(">b", v)
    elif t == 2:
        out += struct.pack(">h", v)
    elif t == 3:
        out += struct.pack(">i", v)
    elif t == 4:
        out += struct.pack(">q", v)
    elif t == 5:
        out += struct.pack(">f", v)
    elif t == 6:
        out += struct.pack(">d", v)
    elif t == 7:
        out += struct.pack(">i", len(v)) + v
    elif t == 8:
        b = v.encode("utf-8")
        out += struct.pack(">H", len(b)) + b
    elif t == 9:
        et, items = v
        out += struct.pack(">bi", et if items else 0, len(items))
        for item in items:
            write_payload(out, et, item)
    elif t == 10:
        for name, tt, val in v:
            out += bytes([tt])
            write_payload(out, 8, name)
            write_payload(out, tt, val)
        out += b"\x00"
    elif t == 11:
        out += struct.pack(">i", len(v)) + struct.pack(">%di" % len(v), *v)
    elif t == 12:
        out += struct.pack(">i", len(v)) + struct.pack(">%dq" % len(v), *v)
    else:
        raise ValueError(t)


def load_servers(path):
    """List of server compounds, or [] if the file is missing."""
    if not path.is_file():
        return []
    r = Reader(path.read_bytes())
    if r.take(1)[0] != 10:
        raise ValueError("%s is not an NBT compound" % path)
    r.string()
    for name, t, v in r.payload(10):
        if name == "servers" and t == 9:
            return v[1]
    return []


def field(server, key):
    for name, _, v in server:
        if name == key:
            return v
    return None


def norm(ip):
    ip = (ip or "").strip().lower().rstrip(".")
    if ip.endswith(":25565"):
        ip = ip[: -len(":25565")]
    return ip


def main():
    existing = load_servers(TARGET)
    seen = {norm(field(s, "ip")) for s in existing}
    merged = list(existing)
    added = []
    for src in SOURCES:
        try:
            servers = load_servers(src)
        except Exception as e:  # one unreadable file must not stop the rest
            print("  skipped %s: %s" % (src, e))
            continue
        for s in servers:
            key = norm(field(s, "ip"))
            if not key or key in seen:
                continue
            seen.add(key)
            # "hidden" servers are ones the game added for itself (e.g. a
            # Quick Play target); show them like any other you kept.
            s = [(n, t, (0 if n == "hidden" else v)) for n, t, v in s]
            merged.append(s)
            added.append("%s (%s)" % (field(s, "name"), field(s, "ip")))

    if not added:
        print("  Server list up to date (%d servers)." % len(merged))
        return

    out = bytearray(b"\x0a")
    write_payload(out, 8, "")
    write_payload(out, 10, [("servers", 9, (10, merged))])
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    tmp = TARGET.with_suffix(".dat.tmp")
    tmp.write_bytes(bytes(out))
    # Prove the file we are about to install reads back, before replacing.
    assert len(load_servers(tmp)) == len(merged)
    os.replace(tmp, TARGET)
    print("  Added %d server(s) to the Multiplayer list (%d total):" % (len(added), len(merged)))
    for a in added:
        print("    + " + a)


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print("  Server sync failed, list left as it was: %s" % e)
        sys.exit(0)
