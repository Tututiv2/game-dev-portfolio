"""
Rocket League Ledger — watches your replay folder and keeps a running record.

Leave it running. Every time Rocket League finishes a game and writes a replay,
this parses it and appends it to the ledger. Then open dashboard.html.

    python rl_watch.py            # scan everything, then watch for new games
    python rl_watch.py --once     # scan once and exit

Nothing is uploaded anywhere. It only ever reads the .replay files already on
your drive and writes one file next to this script.
"""
import argparse
import json
import os
import struct
import sys
import time

# --- where things live ------------------------------------------------------

HERE = os.path.dirname(os.path.abspath(__file__))
LEDGER_JS = os.path.join(HERE, "ledger.js")
LEDGER_JSON = os.path.join(HERE, "ledger.json")

# OneDrive relocates Documents on a lot of machines, so try both.
CANDIDATE_FOLDERS = [
    os.path.expanduser(r"~\OneDrive\Documents\My Games\Rocket League\TAGame\Demos"),
    os.path.expanduser(r"~\Documents\My Games\Rocket League\TAGame\Demos"),
]

# Set this if auto-detection ever picks the wrong person.
PLAYER_NAME = None

# Games more than this far apart start a new session.
SESSION_GAP_MINUTES = 45


def find_demos_folder():
    for path in CANDIDATE_FOLDERS:
        if os.path.isdir(path):
            return path
    return None


# --- replay header parsing --------------------------------------------------
# Only the header, never the network stream. The header holds the box score;
# the stream holds every frame of positions and is a far bigger job.


class Reader:
    def __init__(self, data):
        self.d = data
        self.i = 0

    def u32(self):
        v = struct.unpack_from("<I", self.d, self.i)[0]
        self.i += 4
        return v

    def i32(self):
        v = struct.unpack_from("<i", self.d, self.i)[0]
        self.i += 4
        return v

    def u64(self):
        v = struct.unpack_from("<Q", self.d, self.i)[0]
        self.i += 8
        return v

    def f32(self):
        v = struct.unpack_from("<f", self.d, self.i)[0]
        self.i += 4
        return v

    def byte(self):
        v = self.d[self.i]
        self.i += 1
        return v

    def string(self):
        n = self.i32()
        if n == 0:
            return ""
        if n < 0:  # negative length = UTF-16, counted in characters
            raw = self.d[self.i : self.i - n * 2]
            self.i += -n * 2
            return raw.decode("utf-16-le", errors="replace").rstrip("\x00")
        raw = self.d[self.i : self.i + n]
        self.i += n
        return raw.decode("utf-8", errors="replace").rstrip("\x00")


def read_property(r):
    name = r.string()
    if name in ("None", ""):
        return None, None
    ptype = r.string()
    size = r.u64()
    start = r.i

    if ptype == "IntProperty":
        value = r.i32()
    elif ptype == "FloatProperty":
        value = r.f32()
    elif ptype == "QWordProperty":
        value = r.u64()
    elif ptype in ("StrProperty", "NameProperty"):
        value = r.string()
    elif ptype == "BoolProperty":
        # Declares size 0 but still consumes a byte.
        return name, bool(r.byte())
    elif ptype == "StructProperty":
        # The struct's TYPE NAME is written first and is NOT counted in size.
        # Skipping by size alone lands short and corrupts everything after it.
        r.string()
        r.i += size
        return name, "<struct>"
    elif ptype == "ByteProperty":
        # Same trap: the enum name precedes the value and isn't counted.
        key = r.string()
        if key != "None":
            return name, r.string()
        value = key
    elif ptype == "ArrayProperty":
        # Parsed inside its own slice so one odd element cannot shift the rest.
        sub = Reader(r.d[start : start + size])
        count = sub.u32()
        value = []
        try:
            for _ in range(count):
                item = {}
                while True:
                    k, v = read_property(sub)
                    if k is None:
                        break
                    item[k] = v
                value.append(item)
        except Exception:  # noqa: BLE001 - keep whatever parsed
            pass
    else:
        value = f"<{ptype}>"

    r.i = start + size
    return name, value


def parse_header(path):
    with open(path, "rb") as fh:
        data = fh.read(500_000)
    r = Reader(data)
    r.u32()  # header size
    r.u32()  # crc
    engine = r.u32()
    licensee = r.u32()
    if engine >= 868 and licensee >= 18:
        r.u32()  # net version
    r.string()  # class name
    props = {}
    while True:
        k, v = read_property(r)
        if k is None:
            break
        props[k] = v
    return props


# --- turning a header into a ledger row -------------------------------------


def to_entry(path, me_name):
    p = parse_header(path)
    players = [
        {
            "name": s.get("Name"),
            "team": s.get("Team"),
            "goals": s.get("Goals", 0) or 0,
            "assists": s.get("Assists", 0) or 0,
            "saves": s.get("Saves", 0) or 0,
            "shots": s.get("Shots", 0) or 0,
            "points": s.get("Score", 0) or 0,
            "bot": bool(s.get("bBot", False)),
        }
        for s in (p.get("PlayerStats") or [])
    ]
    me = next((x for x in players if x["name"] == me_name), None)
    if me is None:
        return None

    my_team = me["team"]
    # A team that finished on zero has no score property at all.
    blue = int(p.get("Team0Score", 0) or 0)
    orange = int(p.get("Team1Score", 0) or 0)
    goals_for = blue if my_team == 0 else orange
    goals_against = orange if my_team == 0 else blue

    fps = p.get("RecordFPS") or 30.0
    size = p.get("TeamSize") or len([x for x in players if x["team"] == my_team])

    return {
        "id": p.get("Id") or p.get("MatchGUID") or os.path.basename(path),
        "file": os.path.basename(path),
        "date": p.get("Date"),
        "epoch": p.get("MatchStartEpoch"),
        "playlist": f"{size}v{size}",
        "size": size,
        "duration": round(p.get("TotalSecondsPlayed") or 0),
        "overtime": bool(p.get("bOverTime")),
        "for": goals_for,
        "against": goals_against,
        "result": "W" if goals_for > goals_against else ("L" if goals_for < goals_against else "D"),
        "me": {k: me[k] for k in ("goals", "assists", "saves", "shots", "points")},
        "mates": [x for x in players if x["team"] == my_team and x["name"] != me_name],
        "opps": [x for x in players if x["team"] != my_team],
        "goals_timeline": [
            {
                "mine": g.get("PlayerTeam") == my_team,
                "scorer": g.get("PlayerName"),
                "second": round((g.get("frame") or 0) / fps),
            }
            for g in (p.get("Goals") or [])
        ],
    }


def detect_player(folder):
    """Whoever appears in the most replays is whose PC this is."""
    if PLAYER_NAME:
        return PLAYER_NAME
    counts = {}
    for name in os.listdir(folder):
        if not name.lower().endswith(".replay"):
            continue
        try:
            p = parse_header(os.path.join(folder, name))
        except Exception:  # noqa: BLE001
            continue
        owner = p.get("PlayerName")
        if owner:
            counts[owner] = counts.get(owner, 0) + 2  # the recorder counts double
        for s in p.get("PlayerStats") or []:
            n = s.get("Name")
            if n:
                counts[n] = counts.get(n, 0) + 1
    return max(counts, key=counts.get) if counts else None


def assign_sessions(entries):
    """Group games into sessions by wall-clock gap, so you can review a night."""
    entries.sort(key=lambda e: e.get("date") or "")
    session = 0
    previous = None
    for e in entries:
        stamp = e.get("epoch")
        if previous is not None and stamp and previous and stamp - previous > SESSION_GAP_MINUTES * 60:
            session += 1
        if stamp:
            previous = stamp
        e["session"] = session
    return entries


def write_ledger(entries, me_name):
    payload = {"player": me_name, "generated": int(time.time()), "games": entries}
    with open(LEDGER_JSON, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, indent=1)
    # The dashboard opens straight off the filesystem, and a file:// page is not
    # allowed to fetch a .json next to it. A .js file that assigns a global is,
    # so the whole thing works with no server and no install.
    with open(LEDGER_JS, "w", encoding="utf-8") as fh:
        fh.write("window.RL_LEDGER = ")
        json.dump(payload, fh)
        fh.write(";\n")


def scan(folder, me_name, known):
    added = 0
    for name in sorted(os.listdir(folder)):
        if not name.lower().endswith(".replay") or name in known:
            continue
        path = os.path.join(folder, name)
        try:
            entry = to_entry(path, me_name)
        except Exception as exc:  # noqa: BLE001 - one bad file must not stop the watch
            print(f"  ! could not read {name}: {type(exc).__name__}: {exc}")
            known.add(name)
            continue
        known.add(name)
        if entry is None:
            continue  # someone else's replay
        yield entry
        added += 1
    if added:
        print(f"  +{added} new")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--once", action="store_true", help="scan and exit")
    ap.add_argument("--folder", help="replay folder (auto-detected by default)")
    args = ap.parse_args()

    folder = args.folder or find_demos_folder()
    if not folder:
        print("Could not find your Rocket League replay folder. Pass --folder.")
        return 1
    print(f"Watching {folder}")

    me_name = detect_player(folder)
    if not me_name:
        print("No replays with player stats yet — play a game and I'll pick it up.")
        me_name = "?"
    print(f"Tracking: {me_name}")

    entries = []
    known = set()
    for entry in scan(folder, me_name, known):
        entries.append(entry)
    assign_sessions(entries)
    write_ledger(entries, me_name)
    print(f"{len(entries)} games in the ledger -> {os.path.basename(LEDGER_JS)}")
    print("Open dashboard.html")

    if args.once:
        return 0

    print("\nWatching for new games. Ctrl+C to stop.")
    try:
        while True:
            time.sleep(5)
            fresh = list(scan(folder, me_name, known))
            if fresh:
                entries.extend(fresh)
                assign_sessions(entries)
                write_ledger(entries, me_name)
                for e in fresh:
                    print(f"  {e['date']}  {e['playlist']}  {e['for']}-{e['against']}  {e['result']}")
    except KeyboardInterrupt:
        print("\nStopped.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
