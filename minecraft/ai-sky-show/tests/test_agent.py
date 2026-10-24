"""B1: agent.py - reply parsing + the turn loop against a fake brain / pilot / macros / observe."""
from __future__ import annotations

import json
import time
import unittest

import _fakes_b1  # noqa: F401
from _fakes_b1 import FakeBrain, FakeDials, FakePilot, fake_macros, fake_observe
from show import agent as A
from show.brain import Reply
from show.config import SHOW
from show.cost import CostMeter, TurnBudget
from show.events import EventBus


def J(**kw):
    base = {"inner_voice": None, "emotion": "calm", "say": None, "goal": "grind", "actions": []}
    base.update(kw)
    return json.dumps(base)


class ParseTests(unittest.TestCase):
    def test_plain(self):
        self.assertEqual(A.first_json_object('{"a": 1}'), {"a": 1})

    def test_fenced_and_prose(self):
        t = 'Sure! Here you go:\n```json\n{"say": "yo", "actions": []}\n```\nhope that helps'
        self.assertEqual(A.first_json_object(t)["say"], "yo")

    def test_braces_inside_strings(self):
        t = 'x {"say": "look a } brace and a { too", "n": {"deep": [1, 2]}} trailing {"b": 2}'
        o = A.first_json_object(t)
        self.assertEqual(o["say"], "look a } brace and a { too")
        self.assertEqual(o["n"]["deep"], [1, 2])

    def test_skips_invalid_candidate(self):
        t = '{not json} then {"ok": true}'
        self.assertEqual(A.first_json_object(t), {"ok": True})

    def test_escaped_quote(self):
        self.assertEqual(A.first_json_object(r'{"say": "he said \"hi\" {"}')["say"], 'he said "hi" {')

    def test_none(self):
        self.assertIsNone(A.first_json_object("no json here"))
        self.assertIsNone(A.first_json_object(""))
        self.assertIsNone(A.first_json_object("[1, 2, 3]"))

    def test_normalize(self):
        r = A.normalize_reply({"inner_voice": "One. Two! Three? Four.", "emotion": "FURIOUS", "say": "null",
                               "goal": "a b c d e f g h i j", "actions": ["look_around",
                               {"tool": "warp", "args": {"dest": "hub"}}, {"name": "mine", "input": {"block": "coal"}},
                               {"tool": "x", "args": "raw"}, 5, {"args": {}}]}, max_actions=3)
        self.assertEqual(r["inner_voice"], "One. Two! Three?")
        long = "A" * 200 + ". " + "B" * 60 + "."
        self.assertEqual(A.normalize_reply({"inner_voice": long}, 3)["inner_voice"], "A" * 200 + ".")
        self.assertEqual(r["emotion"], "calm")
        self.assertIsNone(r["say"])
        self.assertEqual(r["goal"], "a b c d e f g h")
        self.assertEqual([a["tool"] for a in r["actions"]], ["look_around", "warp", "mine"])
        self.assertEqual(r["actions"][2]["args"], {"block": "coal"})

    def test_say_equal_to_inner_voice_is_dropped(self):
        r = A.normalize_reply({"inner_voice": "I hate this.", "say": "i hate this.", "emotion": "angry"}, 3)
        self.assertIsNone(r["say"])
        self.assertEqual(r["inner_voice"], "I hate this.")

    def test_multiline_say_flattened(self):
        r = A.normalize_reply({"say": "LET'S\nGO", "inner_voice": None}, 3)
        self.assertEqual(r["say"], "LET'S GO")


class LoopTests(unittest.TestCase):
    def make(self, replies, *, char=None, macros=None, observe=None, dials=None, bus=None, pilot=None):
        self.bus = bus or EventBus()
        self.brain = FakeBrain(replies)
        self.macros = macros or fake_macros()
        self.obs = observe or fake_observe()
        self.dials = dials or FakeDials(turn_min_gap_s=0.0, screenshot_every=3, max_actions_per_turn=2,
                                        voice_lines_per_min=2, summary_every_turns=0)
        self.meter = CostMeter()
        t = [0.0]
        a = A.PlayerAgent(char or SHOW.by_id("kaito"), pilot or FakePilot(), lambda c, s: self.brain,
                          bus=self.bus, dials=self.dials, meter=self.meter, turn_budget=TurnBudget(),
                          clock=lambda: (t[0], 1800.0), macros=self.macros, observe=self.obs,
                          system_prompt="SYS")
        self.t = t
        return a

    def types(self):
        return [e["type"] for e in self.bus.recent(500)]

    def test_five_turns(self):
        replies = [
            J(inner_voice="Where... am I?", emotion="confused", goal="figure out where I am",
              actions=[{"tool": "look_around", "args": {}}]),
            "I think I'll go now ```json\n" + J(say="ANYONE HERE?!", goal="figure out where I am",
                                                 actions=[{"tool": "a", "args": {}}, {"tool": "b", "args": {}},
                                                          {"tool": "c", "args": {}}]) + "```",
            "total garbage, no json",                      # -> repair retry
            J(inner_voice="Fine. Fine!", emotion="angry", goal="find the hub"),   # repair answer
            J(inner_voice="Third line.", say="ANYONE HERE?!", goal="find the hub",
              actions=[{"tool": "warp", "args": {"dest": "hub"}}]),
            J(inner_voice="Fourth line.", goal="find the hub", actions=[{"tool": "say", "args": {"text": "yo ren"}}]),
        ]
        a = self.make(replies)
        outs = []
        for i in range(5):
            self.t[0] = 60.0 * i
            outs.append(a.step())
        self.assertEqual(a.turn_n, 5)
        self.assertTrue(a.first_turn_done.is_set())
        self.assertEqual(len(self.brain.calls), 6, "5 turns + 1 repair retry")
        # turn 1 is the wake-up prompt with a screenshot
        self.assertIn("You open your eyes", self.brain.calls[0][0]["text"])
        self.assertEqual(self.brain.calls[0][-1]["type"], "image")
        # turn 3 has a screenshot (every 3rd), turn 2 doesn't
        self.assertEqual(self.brain.calls[1][-1]["type"], "text")
        self.assertEqual(self.brain.calls[2][-1]["type"], "image")
        self.assertIn("ONLY the JSON", self.brain.calls[3][0]["text"])
        # last turn's results reach the next observation
        self.assertIn("look_around", self.brain.calls[1][0]["text"])
        # actions capped at 2 (a, b; never c), say sent through run_action
        tools = [t for t, _ in self.macros.calls]
        self.assertNotIn("c", tools)
        self.assertIn(("say", {"text": "ANYONE HERE?!"}), self.macros.calls)
        # duplicate chat line on turn 4 suppressed
        self.assertEqual(sum(1 for t, a_ in self.macros.calls if a_ == {"text": "ANYONE HERE?!"}), 1)
        self.assertIn(("say", {"text": "yo ren"}), self.macros.calls)
        ty = self.types()
        self.assertEqual(ty.count("agent_turn"), 5)
        says = [e for e in self.bus.recent(500) if e["type"] == "say"]
        self.assertEqual([e["data"]["text"] for e in says], ["ANYONE HERE?!", "yo ren"])
        # voice budget 2/min: 4 inner voices -> first 2 voiced, rest caption-only
        iv = [e for e in self.bus.recent(500) if e["type"] == "inner_voice"]
        self.assertEqual(len(iv), 4)
        self.assertEqual([e["data"]["voice"] for e in iv], [True, True, False, False])
        self.assertEqual(iv[0]["data"]["char"], "Kaito")
        self.assertEqual(iv[0]["data"]["emotion"], "confused")
        self.assertEqual(iv[0]["players"], ["Kaito"])
        acts = [e for e in self.bus.recent(500) if e["type"] == "agent_action"]
        self.assertTrue(all("ok" in e["data"] and "tool" in e["data"] for e in acts))
        self.assertEqual(self.meter.snapshot()["turns"], 6)
        # observe got the advancing chat cursor
        self.assertEqual(self.obs.seen, [0, 1, 2, 3, 4])

    def test_goal_flip_flop_note(self):
        a = self.make([J(goal="mine coal"), J(goal="fight zombies"), J(goal="fight zombies")])
        a.step(); a.step(); a.step()
        self.assertIn("Finish what you start", self.brain.calls[2][0]["text"])

    def test_brain_error_is_a_quiet_turn(self):
        a = self.make([Reply("", {}, None, 0.1, "overloaded:5s"), J(say="back")])
        self.assertIsNone(a.step())
        e = [x for x in self.bus.recent() if x["type"] == "agent_turn"][0]
        self.assertEqual(e["data"]["error"], "overloaded:5s")
        self.assertGreater(a._cooldown_until, time.monotonic() + 3)
        self.assertEqual(A._backoff_seconds("rate_limited:20s"), 20.0)
        self.assertEqual(A._backoff_seconds("RateLimitError:10s"), 10.0)
        self.assertEqual(A._backoff_seconds("timeout"), 0.0)
        self.assertEqual(A._backoff_seconds("api_error:bad: 5s"), 0.0)
        a.step()
        self.assertIn(("say", {"text": "back"}), self.macros.calls)

    def test_unrepairable_reply(self):
        a = self.make(["nope", "still nope"])
        self.assertIsNone(a.step())
        self.assertEqual(self.macros.calls, [])
        self.assertEqual([x["data"].get("error") for x in self.bus.recent() if x["type"] == "agent_turn"], ["unparseable"])

    def test_stuck_detection(self):
        old = A.STUCK_S
        A.STUCK_S = 0.0
        try:
            a = self.make([J(actions=[{"tool": "walk_to", "args": {}}]), J(), J()], macros=fake_macros(ok=False))
            a.step()
            a.step()
            a.step()
        finally:
            A.STUCK_S = old
        stuck = [e for e in self.bus.recent() if e["type"] == "agent_stuck"]
        self.assertEqual(len(stuck), 1, "reported once, not every turn")
        self.assertIn("haven't moved", self.brain.calls[2][0]["text"])

    def test_other_agents_inner_voice_never_reaches_a_brain(self):
        bus = EventBus()
        secret = "I secretly think Ren is a fraud"
        kaito = self.make([J(inner_voice=secret, say="ren u good?")], bus=bus)
        kaito_brain = self.brain
        kaito.step()
        ren = self.make([J()], char=SHOW.by_id("ren"), bus=bus)
        ren.step()
        self.assertNotIn(secret, json.dumps(self.brain.calls))
        self.assertIn(secret, json.dumps([e for e in bus.recent() if e["type"] == "inner_voice"]))
        self.assertTrue(kaito_brain.calls)

    def test_summary_roll(self):
        d = FakeDials(turn_min_gap_s=0.0, summary_every_turns=2, voice_lines_per_min=5)
        a = self.make([J(), J(), "I remember the hub. Ren is annoying.", J()], dials=d)
        a.step(); a.step()
        self.assertEqual(self.brain.resets, ["I remember the hub. Ren is annoying."])
        self.assertIn("memory note", self.brain.calls[2][0]["text"])

    def test_final_turn(self):
        a = self.make([J(inner_voice="We did it.", say="gg squad", actions=[{"tool": "warp", "args": {}}])])
        r = a.final_turn()
        self.assertEqual(r["actions"], [])
        self.assertIn("countdown hits zero", self.brain.calls[0][0]["text"])
        self.assertEqual(self.macros.calls, [("say", {"text": "gg squad"})])

    def test_thread_respects_turn_budget_and_pause_and_kill(self):
        d = FakeDials(turn_min_gap_s=0.0, max_turns_per_hour=3, summary_every_turns=0)
        a = self.make([J()] * 10, dials=d)
        a.start()
        deadline = time.time() + 5
        while a.turn_n < 3 and time.time() < deadline:
            time.sleep(0.02)
        time.sleep(0.3)
        self.assertEqual(a.turn_n, 3, "shared hourly budget caps turns")
        a.kill()
        a.join(3)
        self.assertFalse(a.is_alive())
        self.assertTrue(self.brain.closed)

    def test_paused_dial_blocks_turns(self):
        d = FakeDials(turn_min_gap_s=0.0, paused=True)
        a = self.make([J()] * 5, dials=d)
        a.start()
        time.sleep(0.3)
        self.assertEqual(a.turn_n, 0)
        d.set("paused", False)
        deadline = time.time() + 3
        while a.turn_n == 0 and time.time() < deadline:
            time.sleep(0.02)
        a.kill()
        a.join(3)
        self.assertGreaterEqual(a.turn_n, 1)

    def test_restart_keeps_memory(self):
        a = self.make([J()])
        a.step()
        self.brain.memory = "remember this"
        a.restart()
        self.assertTrue(self.brain.closed)
        self.assertEqual(self.brain.resets[-1], "remember this")


if __name__ == "__main__":
    unittest.main()
