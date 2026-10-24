"""Headless tests for scripts/reset_profiles.py (offline profile reset) on a throwaway SQLite file."""
from __future__ import annotations

import os
import sqlite3
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "scripts"))

import reset_profiles as rp  # noqa: E402

# SkyClone's real schema (core/foundation/ProfileStore.SCHEMA_V1), trimmed to what the reset touches.
SCHEMA = [
    "CREATE TABLE profiles (id TEXT PRIMARY KEY, name TEXT NOT NULL, mode TEXT NOT NULL DEFAULT 'normal', "
    "created INTEGER NOT NULL, bank INTEGER NOT NULL DEFAULT 0, bank_cap INTEGER NOT NULL DEFAULT 0)",
    "CREATE TABLE profile_members (profile_id TEXT NOT NULL, uuid TEXT NOT NULL, joined INTEGER NOT NULL, "
    "active INTEGER NOT NULL DEFAULT 0, left_at INTEGER NOT NULL DEFAULT 0, seen INTEGER NOT NULL DEFAULT 0, "
    "last_name TEXT, purse INTEGER NOT NULL DEFAULT 0, bits INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (profile_id, uuid))",
]


class OfflineUuidTests(unittest.TestCase):
    def test_matches_java_nameUUIDFromBytes(self):
        # Known pairs taken from SkyClone's dev-server usercache.json (offline-mode joins).
        self.assertEqual(rp.offline_uuid("ViaTest"), "afc53a43-78b8-3d49-a229-11f4ca5bd795")
        self.assertEqual(rp.offline_uuid("FinalFV"), "79c6ef0d-bc26-3939-889b-f1e02ab9f4f7")


class ResetTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.server = self.tmp.name
        os.makedirs(os.path.join(self.server, "plugins", "SkyClone"))
        self.db = os.path.join(self.server, "plugins", "SkyClone", "skyclone.db")
        con = sqlite3.connect(self.db)
        for s in SCHEMA:
            con.execute(s)
        self.kaito = rp.offline_uuid("Kaito")
        self.other = rp.offline_uuid("Stranger")
        con.execute("INSERT INTO profiles(id,name,created) VALUES('p1','Apple',1),('p2','Banana',1)")
        con.execute("INSERT INTO profile_members(profile_id,uuid,joined,active,purse) VALUES('p1',?,1,1,500)", (self.kaito,))
        con.execute("INSERT INTO profile_members(profile_id,uuid,joined,active,purse) VALUES('p2',?,1,1,900)", (self.other,))
        con.commit()
        con.close()
        data = os.path.join(self.server, "world", "players", "data")
        os.makedirs(data)
        self.dat = os.path.join(data, self.kaito + ".dat")
        self.other_dat = os.path.join(data, self.other + ".dat")
        for p in (self.dat, self.other_dat):
            open(p, "wb").close()

    def tearDown(self):
        self.tmp.cleanup()

    def rows(self):
        con = sqlite3.connect(self.db)
        try:
            return {u: (a, l) for u, a, l in con.execute("SELECT uuid, active, left_at FROM profile_members")}
        finally:
            con.close()

    def test_closes_only_named_and_deletes_their_player_file(self):
        rep = rp.reset(self.server, ["Kaito"])
        r = self.rows()
        self.assertEqual(r[self.kaito][0], 0)
        self.assertGreater(r[self.kaito][1], 0)          # left_at set
        self.assertEqual(r[self.other], (1, 0))          # untouched
        self.assertFalse(os.path.exists(self.dat))
        self.assertTrue(os.path.exists(self.other_dat))
        self.assertEqual(rep["accounts"]["Kaito"]["closed_profiles"], ["p1"])

    def test_dry_run_changes_nothing(self):
        rp.reset(self.server, ["Kaito"], dry_run=True)
        self.assertEqual(self.rows()[self.kaito], (1, 0))
        self.assertTrue(os.path.exists(self.dat))

    def test_idempotent(self):
        rp.reset(self.server, ["Kaito"])
        rep = rp.reset(self.server, ["Kaito"])
        self.assertEqual(rep["accounts"]["Kaito"]["closed_profiles"], [])

    def test_missing_db_is_not_an_error(self):
        os.remove(self.db)
        rep = rp.reset(self.server, ["Kaito"])
        self.assertIn("no database", rep["db"])


if __name__ == "__main__":
    unittest.main()
