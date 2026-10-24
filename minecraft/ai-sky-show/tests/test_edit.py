"""Builder C: edit outputs from a synthetic events.jsonl."""
from __future__ import annotations

import json
import re
import sys
import tempfile
import unittest
from pathlib import Path

ORCH = Path(__file__).resolve().parents[1] / "orchestrator"
if str(ORCH) not in sys.path:
    sys.path.insert(0, str(ORCH))

from show import edit as E  # noqa: E402

T0 = 1_760_000_000_000          # recording starts here (ms)


def synth(rec_offset_s: float = 0.0) -> list[dict]:
    evs, n = [], [0]

    def add(sec, src, type, players=(), drama=0, line="", **data):
        n[0] += 1
        evs.append({"id": n[0], "bus_id": n[0], "t": int(T0 + sec * 1000), "src": src, "type": type,
                    "players": list(players), "drama": drama, "text": line, "loc": None, "data": data})
    add(0.5, "server", "episode_start", [], 5, "The episode begins", minutes=30)
    for i, c in enumerate(["Kaito", "Ren", "Mochi", "Yuki"]):
        add(10 + 25 * i, "server", "profile_ready", [c], 3, f"{c} wakes up", first_join=True)
        add(12 + 25 * i, "agent", "inner_voice", [c], 2, "", char=c, text=f"{c}: where am I?", emotion="confused")
    add(130, "agent", "say", ["Kaito"], 2, "", char="Kaito", text="REN YOU IDIOT WHERE ARE YOU")
    add(131, "server", "chat", ["Kaito"], 0, "Kaito: REN YOU IDIOT WHERE ARE YOU", message="REN YOU IDIOT WHERE ARE YOU")
    add(200, "server", "first_of_kind", ["Ren"], 6, "Ren is first to the hub", what="first to reach the hub")
    add(260, "director", "meeting", ["Kaito", "Ren"], 7, "Kaito meets Ren", a="Kaito", b="Ren")
    add(400, "server", "mob_kill", ["Kaito"], 1, "Kaito killed a Zombie", mob_name="Zombie")
    add(520, "server", "near_death", ["Kaito"], 6, "Kaito at 8%", health=8, max=100)
    add(540, "server", "death", ["Kaito"], 7, "Kaito died", cause="Zombie Villager")
    add(541, "director", "replay_mark", [], 7, "Kaito DIES", start=(T0 / 1000 + 520), end=(T0 / 1000 + 550),
        label="Kaito DIES", score=7)
    add(560, "director", "narrate", [], 0, "Pride comes before the fall.", text="Pride comes before the fall.")
    add(900, "server", "boss_spawn", ["Yuki"], 9, "A Nullshade Seraph has spawned!", name="Nullshade Seraph")
    add(905, "tts", "tts_start", ["Yuki"], 0, "", char="Yuki", clip="a.wav", duration_ms=3000)
    add(960, "server", "mob_kill", ["Yuki"], 8, "Yuki slew the Seraph", mob_name="Nullshade Seraph", boss=True)
    add(975, "server", "item_gain", ["Yuki"], 8, "LEGENDARY drop", item_id="SERAPH_BLADE", rarity="LEGENDARY")
    add(1300, "server", "skill_level_up", ["Ren"], 3, "Ren Farming 10", skill="FARMING", level=10)
    for k in range(40):                      # noise
        add(600 + k * 5, "server", "gather_summary", ["Mochi"], 1, "Mochi mined stuff", kind="mining")
    add(1500, "agent", "agent_turn", ["Ren"], 0, "", char="Ren", latency_s=4.0)
    add(1501, "agent", "agent_turn", ["Ren"], 0, "", char="Ren", latency_s=6.0)
    add(1790, "server", "episode_end", [], 5, "Time's up", reason="time")
    return evs


class TestEdit(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name) / "2026-10-04_ep1"
        self.dir.mkdir()
        with open(self.dir / "events.jsonl", "w", encoding="utf-8") as f:
            for e in synth():
                f.write(json.dumps(e) + "\n")
        (self.dir / "recording.json").write_text(json.dumps({"rec_start_ms": T0, "record_path": "D:/x/2026-10-04 20-00-00.mkv"}))
        self.out = E.generate(self.dir, meter={"usd": 0.0, "turns": 2})

    def tearDown(self):
        self.tmp.cleanup()

    def test_all_files(self):
        for k in ("chapters", "best_moments", "edl", "shorts", "transcript", "cost"):
            self.assertTrue(self.out[k].exists(), k)
            self.assertGreater(self.out[k].stat().st_size, 20, k)

    def test_chapters_youtube_rules(self):
        lines = self.out["chapters"].read_text(encoding="utf-8").strip().splitlines()
        self.assertTrue(lines[0].startswith("0:00 "))
        self.assertGreaterEqual(len(lines), 3)
        self.assertLessEqual(len(lines), 12)
        secs = []
        for l in lines:
            ts = l.split(" ", 1)[0]
            parts = [int(x) for x in ts.split(":")]
            secs.append(parts[0] * 60 + parts[1] if len(parts) == 2 else parts[0] * 3600 + parts[1] * 60 + parts[2])
        self.assertEqual(secs, sorted(secs))
        self.assertTrue(all(b - a >= 10 for a, b in zip(secs, secs[1:])))
        text = "\n".join(lines)
        self.assertIn("BOSS", text)
        self.assertIn("First Blood: Kaito Dies", text)

    def test_best_moments_ranked(self):
        md = self.out["best_moments"].read_text(encoding="utf-8")
        rows = [r for r in md.splitlines() if re.match(r"\| \d+ \|", r)]
        scores = [float(r.split("|")[3]) for r in rows]
        self.assertEqual(scores, sorted(scores, reverse=True))
        self.assertIn("BOSS", rows[0])
        self.assertNotIn("Mochi mined", md)                 # grind noise filtered
        # the director's replay mark widened the death clip instead of being a second row
        death = next(r for r in rows if "Dies" in r and "Kaito" in r)
        self.assertIn("8:40 -> 9:10", death)
        self.assertEqual(sum(1 for r in rows if "Kaito DIES" in r and "First Blood" not in r), 0)

    def test_edl_cmx3600(self):
        edl = self.out["edl"].read_text(encoding="utf-8").splitlines()
        self.assertTrue(edl[0].startswith("TITLE: "))
        self.assertEqual(edl[1], "FCM: NON-DROP FRAME")
        events = [l for l in edl if re.match(r"^\d{3}  ", l)]
        self.assertGreaterEqual(len(events), 3)
        tc = re.compile(r"^(\d{3})  AX       AA/V  C        (\S+) (\S+) (\S+) (\S+)$")
        prev_rec_out = None
        for l in events:
            m = tc.match(l)
            self.assertIsNotNone(m, l)
            for t in m.groups()[1:]:
                self.assertRegex(t, r"^\d\d:\d\d:\d\d:\d\d$")
                self.assertLess(int(t.split(":")[3]), 30)
            if prev_rec_out:
                self.assertEqual(m.group(4), prev_rec_out)       # record side is contiguous
            prev_rec_out = m.group(5)
        self.assertTrue(events[0].split()[6] == "01:00:00:00")
        self.assertIn("* FROM CLIP NAME: 2026-10-04 20-00-00.mkv", edl)

    def test_shorts(self):
        md = self.out["shorts"].read_text(encoding="utf-8")
        self.assertIn("## 1.", md)
        self.assertIn("Title idea", md)
        self.assertIn("Hook", md)
        first = re.search(r"## 1\. (\S+) -> (\S+)", md)
        a, b = [sum(int(x) * 60 ** i for i, x in enumerate(reversed(t.split(":")))) for t in first.groups()]
        self.assertEqual(b - a, 60)
        self.assertTrue(880 <= a <= 980, a)                 # the boss window is the densest minute

    def test_transcript(self):
        tr = self.out["transcript"].read_text(encoding="utf-8")
        self.assertEqual(tr.count("REN YOU IDIOT WHERE ARE YOU"), 1)   # say + server echo deduped
        self.assertIn("*thinks (confused)*", tr)
        self.assertIn("NARRATOR", tr)
        self.assertLess(tr.index("Kaito: where am I?"), tr.index("Yuki: where am I?"))

    def test_cost(self):
        c = json.loads(self.out["cost"].read_text(encoding="utf-8"))
        self.assertEqual(c["agents_from_events"]["Ren"]["turns"], 2)
        self.assertEqual(c["agents_from_events"]["Ren"]["avg_latency_s"], 5.0)
        self.assertEqual(c["meter"]["turns"], 2)
        self.assertEqual(c["voice_lines"], 1)

    def test_timecode(self):
        self.assertEqual(E.timecode(3661.5, 30), "01:01:01:15")
        self.assertEqual(E.fmt_ts(59.9), "0:59")
        self.assertEqual(E.fmt_ts(3725), "1:02:05")

    def test_no_recording_json(self):
        (self.dir / "recording.json").unlink()
        out = E.generate(self.dir)
        self.assertTrue(out["chapters"].read_text(encoding="utf-8").startswith("0:00"))


if __name__ == "__main__":
    unittest.main()
