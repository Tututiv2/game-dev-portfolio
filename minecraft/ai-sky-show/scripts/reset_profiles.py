"""Offline profile reset for the show server (the server MUST be stopped).

What it does, for every cast account (config/cast.json) or the names given:
  1. SkyClone DB (server/plugins/SkyClone/skyclone.db, SQLite): closes every open membership
     (profile_members.left_at = now, active = 0) - exactly SkyClone's own closeMembership. SkyClone
     ignores closed memberships, so the next login creates a brand-new first profile (fresh purse,
     fresh member data, a new private island instance, ProfileReady firstJoin=true).
  2. Vanilla player files (world/players/data|stats|advancements/<uuid>.*): the inventory lives
     there, not in SkyClone's DB, so it is deleted.
Nothing else is touched: islands, the hub, bazaar/auction state and other accounts stay.

Usage:
    py -3 scripts/reset_profiles.py                 # whole cast
    py -3 scripts/reset_profiles.py Kaito Ren       # some accounts
    py -3 scripts/reset_profiles.py --dry-run
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sqlite3
import sys
import time
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)


def offline_uuid(name: str) -> str:
    """Java UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(UTF_8))."""
    md5 = hashlib.md5(("OfflinePlayer:" + name).encode("utf-8")).digest()
    return str(uuid.UUID(bytes=md5, version=3))


def cast_accounts() -> list[str]:
    with open(os.path.join(ROOT, "config", "cast.json"), encoding="utf-8") as f:
        cast = json.load(f)
    return [c["account"] for c in cast.get("characters", [])]


def reset(server: str, names: list[str], dry_run: bool = False) -> dict:
    report: dict = {"accounts": {}, "db": None}
    db = os.path.join(server, "plugins", "SkyClone", "skyclone.db")
    ids = {n: offline_uuid(n) for n in names}
    if os.path.exists(db):
        con = sqlite3.connect(db, timeout=10)
        try:
            con.execute("BEGIN IMMEDIATE")
            now = int(time.time() * 1000)
            for n, u in ids.items():
                rows = con.execute("SELECT profile_id FROM profile_members WHERE uuid = ? AND left_at = 0", (u,)).fetchall()
                report["accounts"].setdefault(n, {})["closed_profiles"] = [r[0] for r in rows]
                if not dry_run:
                    con.execute("UPDATE profile_members SET left_at = ?, active = 0 WHERE uuid = ? AND left_at = 0", (now, u))
            if dry_run:
                con.rollback()
            else:
                con.commit()
            report["db"] = db
        except sqlite3.OperationalError as e:
            con.rollback()
            raise SystemExit(f"database is busy or missing tables ({e}) - is the server still running?")
        finally:
            con.close()
    else:
        report["db"] = f"(no database at {db} - nothing to close)"

    players = os.path.join(server, "world", "players")
    for n, u in ids.items():
        removed = []
        for sub, exts in (("data", (".dat", ".dat_old")), ("stats", (".json",)), ("advancements", (".json",))):
            for ext in exts:
                p = os.path.join(players, sub, u + ext)
                if os.path.exists(p):
                    removed.append(os.path.relpath(p, server))
                    if not dry_run:
                        os.remove(p)
        report["accounts"].setdefault(n, {})["uuid"] = u
        report["accounts"][n]["removed_files"] = removed
    return report


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("names", nargs="*")
    ap.add_argument("--server", default=os.path.join(ROOT, "server"))
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args(argv)
    names = a.names or cast_accounts()
    if not os.path.isdir(a.server):
        print(f"no server folder at {a.server} (run scripts/setup_server.ps1)", file=sys.stderr)
        return 1
    rep = reset(a.server, names, a.dry_run)
    print(json.dumps(rep, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
