"""Builder C: a mock end-to-end episode — 2 simulated minutes, accelerated — with NO Minecraft,
NO OBS, NO model calls and NO network port. Runs the real Episode, Stage, Director (LLM path via a
fake brain), the overlay bridge (captions/chat/scene messages, voice disabled) and the edit."""
from __future__ import annotations

import json
import sys
import tempfile
import time
import unittest
from pathlib import Path

ORCH = Path(__file__).resolve().parents[1] / "orchestrator"
if str(ORCH) not in sys.path:
    sys.path.insert(0, str(ORCH))

from show.events import EventBus  # noqa: E402
from show import episode as EP  # noqa: E402
from show.config import SHOW  # noqa: E402

OUTPUTS = ("events.jsonl", "overlay.jsonl", "recording.json", "meta.json", "chapters.txt", "best_moments.md",
           "highlights.edl", "shorts.md", "transcript.md", "cost.json")


class TestMockEpisode(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.bus = EventBus()
        opts = EP.EpisodeOptions(mock=True, brain="fake", minutes=2, speed=40, title_s=3, join_gap_s=6,
                                 beat_s=3, start_server=False, episodes_dir=Path(cls.tmp.name), log=lambda m: None)
        cls.ep = EP.Episode(opts, bus=cls.bus)
        t0 = time.time()
        cls.res = cls.ep.run()
        cls.wall = time.time() - t0
        cls.events = [json.loads(l) for l in (cls.ep.dir / "events.jsonl").read_text(encoding="utf-8").splitlines() if l]
        cls.overlay = [json.loads(l) for l in (cls.ep.dir / "overlay.jsonl").read_text(encoding="utf-8").splitlines() if l]

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def of(self, type, **match):
        return [e for e in self.events if e["type"] == type and all(e["data"].get(k) == v for k, v in match.items())]

    def test_ok_fast_and_every_output(self):
        self.assertTrue(self.res["ok"], self.res)
        self.assertLess(self.wall, 30)
        for f in OUTPUTS:
            p = self.ep.dir / f
            self.assertTrue(p.exists(), f)
            self.assertGreater(p.stat().st_size, 10, f)
        self.assertIn("mock", self.ep.dir.name)

    def test_slow_intro_one_at_a_time(self):
        intros = [e for e in self.of("scene", mode="intro") if e["data"].get("target")]
        names = [e["data"]["target"] for e in intros]
        self.assertEqual(names, [c.name for c in SHOW.characters])
        gaps = [(b["t"] - a["t"]) / 1000 for a, b in zip(intros, intros[1:])]
        self.assertTrue(all(5 <= g <= 8 for g in gaps), gaps)          # join_gap_s=6 simulated seconds
        for e in intros:
            self.assertIn("lower_third", e["data"])
        # wake-up thoughts come in join order, before the grid beat
        firsts = []
        for e in self.of("inner_voice"):
            if e["players"][0] not in firsts:
                firsts.append(e["players"][0])
        self.assertEqual(firsts, [c.name for c in SHOW.characters])
        grid = self.of("scene", mode="grid")
        self.assertTrue(grid and grid[0]["t"] > intros[-1]["t"])
        orbits = [s for s in self.ep.stage.log if s[0] == "orbit"]
        self.assertEqual([o[1] for o in orbits], [c.name for c in SHOW.characters])

    def test_director_ran(self):
        dirs = self.of("director")
        self.assertTrue(dirs)
        kinds = {d["data"].get("decision") for d in dirs}
        self.assertIn("CINEMATIC", kinds)
        self.assertTrue(self.of("cinematic_start"))
        self.assertTrue(self.of("cinematic_end"))                       # always back to the grid
        self.assertTrue(self.of("meeting"))
        self.assertTrue(any(e["data"].get("all") for e in self.of("meeting")))
        self.assertTrue(self.of("replay_mark"))
        self.assertTrue(any(str(d["data"].get("by", "")).startswith("llm") for d in dirs))
        # never a cut while a voice line was playing
        ends = {}
        for e in self.events:
            if e["type"] == "tts_end":
                ends.setdefault(e["data"]["clip"], e["t"])
        for d in dirs:
            if d["data"].get("decision") not in ("CINEMATIC", "CUT_TO"):
                continue
            for s in self.events:
                if s["type"] != "tts_start":
                    continue
                end = ends.get(s["data"]["clip"], s["t"] + s["data"]["duration_ms"])
                self.assertFalse(s["t"] < d["t"] < end, f"cut at {d['t']} during a line {s['t']}-{end}")

    def test_wrap_up(self):
        finals = [e for e in self.of("agent_turn") if e["data"].get("final")]
        self.assertEqual(sorted(e["players"][0] for e in finals), sorted(c.name for c in SHOW.characters))
        self.assertTrue(self.of("episode_start"))
        self.assertTrue(self.of("episode_end"))
        self.assertGreater(self.of("episode_end")[0]["t"], self.of("episode_start")[0]["t"] + 110_000)

    def test_overlay_messages(self):
        kinds = {m.get("kind") for m in self.overlay}
        for k in ("caption", "chat", "scene", "title_card", "ticker", "letterbox"):
            self.assertIn(k, kinds)

    def test_edit_outputs_make_sense(self):
        ch = (self.ep.dir / "chapters.txt").read_text(encoding="utf-8").splitlines()
        self.assertTrue(ch[0].startswith("0:00"))
        self.assertGreaterEqual(len(ch), 3)
        tr = (self.ep.dir / "transcript.md").read_text(encoding="utf-8")
        for c in SHOW.characters:
            self.assertIn(f"**{c.name}**", tr)
        bm = (self.ep.dir / "best_moments.md").read_text(encoding="utf-8")
        self.assertIn("BOSS", bm)
        rec = json.loads((self.ep.dir / "recording.json").read_text(encoding="utf-8"))
        self.assertEqual(rec["source"], "mock")

    def test_numbering(self):
        # mock runs never consume a real episode number
        self.assertEqual(EP.next_episode_number(Path(self.tmp.name)), 1)
        (Path(self.tmp.name) / "2026-10-01_ep4").mkdir()
        self.assertEqual(EP.next_episode_number(Path(self.tmp.name)), 5)


if __name__ == "__main__":
    unittest.main()
