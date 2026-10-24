"""Builder C: Director rubric, hysteresis, never-cut-during-voice, LLM parsing, camera fallback."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

ORCH = Path(__file__).resolve().parents[1] / "orchestrator"
if str(ORCH) not in sys.path:
    sys.path.insert(0, str(ORCH))

from show.dials import DEFAULTS  # noqa: E402
from show.events import EventBus  # noqa: E402
from show import director as D  # noqa: E402
from show.camera import Camera  # noqa: E402


class Dials(dict):
    def __init__(self, **over):
        super().__init__(DEFAULTS)
        self.update(over)

    def get(self, k, d=None):
        return super().get(k, d)


class Stage:
    def __init__(self):
        self.calls = []

    def grid(self):
        self.calls.append(("grid",))

    def cut_to(self, n):
        self.calls.append(("cut", n))

    def cinematic(self, target, shot, seconds, label=""):
        self.calls.append(("cine", target, shot, seconds))

    def narrate(self, line):
        self.calls.append(("narrate", line))

    def ticker(self, text, drama):
        self.calls.append(("ticker", text))

    def kinds(self):
        return [c[0] for c in self.calls if c[0] != "ticker"]


class Clock:
    def __init__(self, t=1000.0):
        self.t = t

    def __call__(self):
        return self.t


def ev(type, players=("Kaito",), drama=0, text="", src="server", **data):
    return {"id": 1, "t": 0, "src": src, "type": type, "players": list(players), "drama": drama,
            "text": text, "loc": None, "data": data}


NAMES = ["Kaito", "Ren", "Mochi", "Yuki"]


def make(**dial_over):
    bus, stage, clock = EventBus(), Stage(), Clock()
    d = D.Director(bus, stage, clock=clock, dials=Dials(**dial_over), names=NAMES, async_llm=False)
    return d, bus, stage, clock


class TestRubric(unittest.TestCase):
    def test_ordering(self):
        s = lambda e: D.score_event(e)[0]
        self.assertGreaterEqual(s(ev("boss_spawn", name="Seraph")), 9)
        self.assertGreater(s(ev("death")), s(ev("near_death")))
        self.assertGreater(s(ev("near_death")), s(ev("skill_level_up", skill="FARMING", level=3)))
        self.assertGreater(s(ev("item_gain", rarity="LEGENDARY", item_id="X")),
                           s(ev("item_gain", rarity="RARE", item_id="X")))
        self.assertEqual(s(ev("skill_level_up", skill="MINING", level=5)), 5)
        self.assertLess(s(ev("mob_kill", mob_name="Zombie")), 3)
        self.assertGreaterEqual(s(ev("mob_kill", mob_name="Seraph", boss=True)), 8)
        self.assertGreaterEqual(s(ev("meeting", players=NAMES, all=True)), 8)
        self.assertGreaterEqual(s(ev("first_of_kind", what="first to reach the hub")), 7)
        self.assertGreaterEqual(s(ev("agent_stuck", seconds=120, src="agent")), 5)

    def test_drama_hint_is_a_floor(self):
        self.assertEqual(D.score_event(ev("mob_kill", drama=6))[0], 6)

    def test_pure(self):
        e = ev("death", cause="Zombie")
        self.assertEqual(D.score_event(e), D.score_event(dict(e)))

    def test_argument_heuristic(self):
        calm, _ = D.argument_score("anyone know where the hub is", "Kaito", NAMES)
        roast, why = D.argument_score("REN YOU ABSOLUTE IDIOT STOP!!", "Kaito", NAMES)
        self.assertLess(calm, 2)
        self.assertGreaterEqual(roast, 5)
        self.assertIn("roast", why)
        # naming yourself is not calling someone out
        self.assertNotIn("calls out", D.argument_score("Kaito is the best", "Kaito", NAMES)[1])

    def test_headline_is_punchy(self):
        self.assertIn("DIES", D.score_event(ev("death", cause="Zombie"))[2])


class TestDecisions(unittest.TestCase):
    def test_big_moment_gets_cinematic_and_mark(self):
        d, bus, stage, clock = make()
        d.handle(ev("boss_spawn", players=["Yuki"], name="Seraph"))
        self.assertIn("cine", stage.kinds())
        marks = bus.recent(50, {"replay_mark"})
        self.assertEqual(len(marks), 1)
        self.assertEqual(d.mode, "cinematic")

    def test_always_returns_to_grid(self):
        d, bus, stage, clock = make()
        d.handle(ev("death", players=["Kaito"]))
        self.assertEqual(d.mode, "cinematic")
        clock.t += 30
        d.tick()
        self.assertEqual(d.mode, "grid")
        self.assertEqual(stage.calls[-1], ("grid",))
        self.assertTrue(bus.recent(50, {"cinematic_end"}))

    def test_hysteresis(self):
        d, bus, stage, clock = make(cinematic_min_gap_s=120.0)
        d.handle(ev("death", players=["Kaito"]))           # 7 -> cinematic
        clock.t += 15
        d.tick()                                            # back to grid
        clock.t += 10
        d.handle(ev("near_death", players=["Ren"]))         # 6, inside the gap -> downgraded to a cut
        self.assertEqual(stage.kinds().count("cine"), 1)
        self.assertIn(("cut", "Ren"), stage.calls)
        clock.t += 15
        d.tick()
        clock.t += 10
        d.handle(ev("boss_spawn", players=["Yuki"]))        # 9 = huge: overrides the gap
        self.assertEqual(stage.kinds().count("cine"), 2)

    def test_gap_expires(self):
        d, bus, stage, clock = make(cinematic_min_gap_s=60.0)
        d.handle(ev("death", players=["Kaito"]))
        clock.t += 15
        d.tick()
        clock.t += 61
        d.handle(ev("death", players=["Ren"]))
        self.assertEqual(stage.kinds().count("cine"), 2)

    def test_small_events_hold(self):
        d, bus, stage, clock = make()
        for e in (ev("mob_kill"), ev("collection_tier_up", collection="WHEAT", tier=1), ev("join")):
            d.handle(e)
        self.assertEqual(stage.kinds(), [])

    def test_manual_mark_only_marks(self):
        d, bus, stage, clock = make()
        d.handle(ev("mark", players=[], label="that was insane"))
        self.assertEqual(stage.kinds(), [])
        self.assertTrue(bus.recent(10, {"replay_mark"}))

    def test_narration_is_sparse(self):
        d, bus, stage, clock = make(cinematic_min_gap_s=0.0, narrate_min_gap_s=90.0)
        d.handle(ev("death", players=["Kaito"]))
        clock.t += 20
        d.tick()
        d.handle(ev("death", players=["Ren"]))
        self.assertEqual(stage.kinds().count("narrate"), 1)


class TestVoiceLock(unittest.TestCase):
    def test_never_cut_during_voice(self):
        d, bus, stage, clock = make()
        d.handle(ev("tts_start", players=["Ren"], src="tts", char="Ren", duration_ms=4000))
        d.handle(ev("boss_spawn", players=["Yuki"]))
        self.assertNotIn("cine", stage.kinds())             # held, not executed
        self.assertIsNotNone(d.pending)
        clock.t += 2
        d.tick()
        self.assertNotIn("cine", stage.kinds())             # still speaking
        d.handle(ev("tts_end", players=["Ren"], src="tts", char="Ren"))
        self.assertIn("cine", stage.kinds())                # fires the moment the line ends

    def test_voice_lock_expires_if_end_is_lost(self):
        d, bus, stage, clock = make()
        d.handle(ev("tts_start", players=["Ren"], src="tts", char="Ren", duration_ms=3000))
        d.handle(ev("death", players=["Kaito"]))
        clock.t += 5                                        # 3 s line + 0.5 s safety has passed
        d.tick()
        self.assertIn("cine", stage.kinds())

    def test_stale_held_decision_is_dropped(self):
        d, bus, stage, clock = make(director_stale_s=12.0)
        d.handle(ev("tts_start", players=["Ren"], src="tts", char="Ren", duration_ms=60000))
        d.handle(ev("death", players=["Kaito"]))
        clock.t += 40
        d.handle(ev("tts_end", players=["Ren"], src="tts", char="Ren"))
        self.assertNotIn("cine", stage.kinds())

    def test_no_return_to_grid_mid_line(self):
        d, bus, stage, clock = make()
        d.handle(ev("death", players=["Kaito"]))
        clock.t += 9
        d.handle(ev("tts_start", players=["Kaito"], src="tts", char="Kaito", duration_ms=5000))
        clock.t += 1                                        # cinematic over, line still playing
        d.tick()
        self.assertEqual(d.mode, "cinematic")
        d.handle(ev("tts_end", players=["Kaito"], src="tts", char="Kaito"))
        self.assertEqual(d.mode, "grid")


class TestMeetings(unittest.TestCase):
    def test_first_meeting_once_and_all_four(self):
        d, bus, stage, clock = make()
        far = {"Kaito": {"island": "hub", "x": 0, "y": 70, "z": 0}, "Ren": {"island": "hub", "x": 50, "y": 70, "z": 0}}
        self.assertEqual(d.check_meetings(far, clock.t), [])
        near = {"Kaito": {"island": "hub", "x": 0, "y": 70, "z": 0}, "Ren": {"island": "hub", "x": 6, "y": 70, "z": 0}}
        self.assertEqual(len(d.check_meetings(near, clock.t)), 1)
        self.assertEqual(d.check_meetings(near, clock.t), [])        # only the first time
        other_island = dict(near, Mochi={"island": "private_mochi", "x": 1, "y": 70, "z": 0})
        self.assertEqual(d.check_meetings(other_island, clock.t), [])
        allfour = {n: {"island": "hub", "x": i * 3, "y": 70, "z": 0} for i, n in enumerate(NAMES)}
        d.check_meetings(allfour, clock.t)
        mts = bus.recent(50, {"meeting"})
        self.assertTrue(any(m["data"].get("all") for m in mts))
        # the meeting event is itself a big moment
        score = d.handle(mts[-1])
        self.assertGreaterEqual(score, 8)


class TestLLMLayer(unittest.TestCase):
    def test_parse_defensive(self):
        self.assertEqual(D.parse_json_reply('Sure!\n```json\n{"decision": "HOLD"}\n```')["decision"], "HOLD")
        self.assertEqual(D.parse_json_reply('blah {"a": "}{"} {"decision":"NARRATE","line":"x"}')["a"], "}{")
        self.assertIsNone(D.parse_json_reply("no json here"))
        self.assertIsNone(D.parse_json_reply("{broken"))

    def test_validation(self):
        e = ev("death", players=["Kaito"])
        decs = D.decision_from_llm({"decision": "cinematic", "target": "ren", "shot": "WEIRD", "seconds": 99},
                                   e, 7, "h", 100.0, NAMES)
        self.assertEqual(decs[0].kind, "CINEMATIC")
        self.assertEqual(decs[0].target, "Ren")
        self.assertEqual(decs[0].shot, "orbit")
        self.assertLessEqual(decs[0].seconds, 20)
        self.assertIsNone(D.decision_from_llm({"decision": "EXPLODE"}, e, 7, "h", 100.0, NAMES))
        mk = D.decision_from_llm({"decision": "REPLAY_MARK", "start_s_ago": 30, "end_s_after": 5, "label": "lol"},
                                 e, 7, "h", 100.0, NAMES)
        self.assertEqual((mk[0].start, mk[0].end), (70.0, 105.0))

    def test_llm_decides_but_rules_still_apply(self):
        class B:
            def __init__(self):
                self.n = 0

            def turn(self, blocks):
                self.n += 1
                from types import SimpleNamespace
                return SimpleNamespace(text='{"decision":"CINEMATIC","target":"Kaito","shot":"crane","seconds":8}',
                                       error=None)
        d, bus, stage, clock = make(cinematic_min_gap_s=120.0)
        d.brain = B()
        d.handle(ev("death", players=["Kaito"]))
        self.assertIn(("cine", "Kaito", "crane", 8.0), stage.calls)
        clock.t += 10
        d.tick()
        clock.t += 5
        d.handle(ev("death", players=["Ren"]))   # LLM asks for another cinematic inside the gap: refused
        self.assertEqual(stage.kinds().count("cine"), 1)
        self.assertEqual(d.brain.n, 2)

    def test_brain_error_falls_back_to_rubric(self):
        class Broken:
            def turn(self, blocks):
                from types import SimpleNamespace
                return SimpleNamespace(text="", error="rate limited")
        d, bus, stage, clock = make()
        d.brain = Broken()
        d.handle(ev("death", players=["Kaito"]))
        self.assertIn("cine", stage.kinds())
        dirs = [e for e in bus.recent(50, {"director"}) if "rubric-fallback" in str(e["data"].get("by"))]
        self.assertTrue(dirs)

    def test_huge_moments_skip_the_llm(self):
        class Slow:
            calls = 0

            def turn(self, blocks):
                Slow.calls += 1
                raise AssertionError("should not be asked")
        d, bus, stage, clock = make()
        d.brain = Slow()
        d.handle(ev("boss_spawn", players=["Yuki"]))
        self.assertEqual(Slow.calls, 0)
        self.assertIn("cine", stage.kinds())


class TestCamera(unittest.TestCase):
    def test_showcam_preferred(self):
        sent = []

        def http(url, body, timeout):
            sent.append((url, body))
            return {"ok": True}
        cam = Camera(showcam_port=25660, showcontrol=lambda p, b=None: {"ok": True}, http=http,
                     account_of=lambda n: n)
        r = cam.shot("pushin", "Kaito", 6)
        self.assertTrue(r["ok"])
        self.assertTrue(sent[-1][0].endswith("/shot"))
        self.assertEqual(sent[-1][1]["type"], "pushin")
        self.assertEqual(sent[-1][1]["target"], {"player": "Kaito"})

    def test_fallback_to_showcontrol(self):
        cmds = []

        def http(url, body, timeout):
            raise OSError("refused")
        cam = Camera(showcam_port=25660, showcontrol=lambda p, b=None: cmds.append(b["cmd"]) or {"ok": True},
                     http=http, account_of=lambda n: n.lower())
        r = cam.shot("crane", "Kaito", 9.4)
        self.assertTrue(r["ok"])
        self.assertEqual(cmds[-1], "show cam orbit kaito 9")
        cam.return_to_grid()
        self.assertEqual(cmds[-1], "show cam stop")
        self.assertFalse(cam.shot("orbit", {"x": 1, "y": 2, "z": 3}, 5)["ok"])


if __name__ == "__main__":
    unittest.main()
