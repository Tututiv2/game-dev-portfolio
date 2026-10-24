"""overlay_bridge: BUS event -> overlay message mapping. Synchronous, fake TTS, no network."""
import sys
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "orchestrator"))

from show import overlay_bridge as OB  # noqa: E402
from show.events import EventBus  # noqa: E402
from show.server import ShowState  # noqa: E402
from show.tts import Clip  # noqa: E402
from show.config import SHOW  # noqa: E402

# the cast is a draft the owner renames freely: never hard-code a name or colour here
K, R, M, Y = (c.name for c in SHOW.characters[:4])
COL = {c.name: c.color for c in SHOW.characters}
M_ACCOUNT = SHOW.characters[2].account.lower()
R_ARCH = SHOW.characters[1].archetype.upper()


class Sink:
    def __init__(self):
        self.msgs = []

    def push(self, m):
        self.msgs.append(m)

    def kinds(self, k):
        return [m for m in self.msgs if m["kind"] == k]


class Dials(dict):
    def get(self, k, d=None):
        return super().get(k, d)


class FakeTTS:
    """Calls back synchronously: voiced lines 'play' a 1.2 s clip; voice=False lines are captions."""

    def __init__(self):
        self.lines = []

    def say(self, line, on_start=None, on_skip=None, on_end=None):
        self.lines.append(line)
        if line.voice:
            clip = Clip("x.wav", f"/audio/{len(self.lines)}.wav", 1200, "sapi")
            on_start(line, clip)
            on_end(line, clip)
        else:
            on_skip(line, "caption")

    def set_audio_dir(self, p):
        pass

    def status(self):
        return {}

    def stop(self):
        pass


def ev(typ, src="agent", players=(), drama=0, text="", **data):
    return {"id": 1, "t": int(time.time() * 1000), "src": src, "type": typ, "players": list(players),
            "drama": drama, "text": text, "loc": None, "data": data}


class BridgeTests(unittest.TestCase):
    def setUp(self):
        self.sink = Sink()
        self.bus = EventBus()
        self.busq = self.bus.subscribe()
        self.tts = FakeTTS()
        self.dials = Dials(voice_lines_per_min=3, episode_minutes=30, paused=False)
        self.b = OB.Bridge(server=self.sink, state=ShowState(), bus=self.bus, tts=self.tts, dials=self.dials,
                           poll_server=False)

    def bus_types(self):
        out = []
        while not self.busq.empty():
            out.append(self.busq.get_nowait()["type"])
        return out

    def test_inner_voice_becomes_voiced_caption_and_tts_events(self):
        self.b.handle(ev("inner_voice", players=[K], char=K, text="I can't lose here.", emotion="determined"))
        cap = self.sink.kinds("caption")[0]
        self.assertEqual(cap["char"], K)
        self.assertEqual(cap["color"], COL[K])
        self.assertEqual(cap["audio_url"], "/audio/1.wav")
        self.assertEqual(cap["duration_ms"], 1200)
        self.assertEqual(cap["emotion"], "determined")
        self.assertEqual(self.bus_types(), ["tts_start", "tts_end"])
        self.assertEqual(self.b.state.chars[K]["last_inner"], "I can't lose here.")

    def test_voice_false_is_caption_only(self):
        self.b.handle(ev("inner_voice", players=[Y], char=Y, text="...", voice=False))
        cap = self.sink.kinds("caption")[0]
        self.assertNotIn("audio_url", cap)
        self.assertGreaterEqual(cap["duration_ms"], 2500)
        self.assertEqual(self.bus_types(), [])

    def test_voice_lines_per_min_cap(self):
        self.dials["voice_lines_per_min"] = 2
        for i in range(3):
            self.b.handle(ev("inner_voice", players=[R], char=R, text=f"thought {i}"))
        caps = self.sink.kinds("caption")
        self.assertEqual(["audio_url" in c for c in caps], [True, True, False])

    def test_account_names_map_to_characters(self):
        self.b.handle(ev("inner_voice", players=[M_ACCOUNT], text="hehe", emotion="smug"))
        self.assertEqual(self.sink.kinds("caption")[0]["char"], M)

    def test_say_and_server_chat_echo_are_one_bubble(self):
        self.b.handle(ev("say", players=[M], char=M, text="who wants a stick"))
        self.b.handle(ev("chat", src="server", players=[M], message="who wants a stick"))
        chats = self.sink.kinds("chat")
        self.assertEqual(len(chats), 1)
        self.assertEqual(chats[0], {"kind": "chat", "char": M, "text": "who wants a stick", "color": COL[M]})

    def test_chat_from_non_cast_is_ignored(self):
        self.b.handle(ev("chat", src="server", players=["ShowCam"], message="hi"))
        self.assertEqual(self.sink.kinds("chat"), [])

    def test_ticker_threshold(self):
        self.b.handle(ev("gather_summary", src="server", players=[R], drama=1, text="Ren gathered 64 wheat"))
        self.b.handle(ev("mob_kill", src="server", players=[K], drama=3, text=f"{K} killed a Zombie"))
        t = self.sink.kinds("ticker")
        self.assertEqual(len(t), 1)
        self.assertEqual(t[0]["text"], f"{K} killed a Zombie")
        self.assertEqual(t[0]["color"], COL[K])

    def test_cinematic_cycle_with_lower_third_from_big_event(self):
        self.b.handle(ev("boss_spawn", src="server", players=[Y], drama=8, text="A boss spawned",
                         mob_id="NULLSHADE", name="Nullshade Seraph"))
        self.b.handle(ev("director", src="director", decision="CINEMATIC", target=Y, shot="orbit", seconds=8,
                         score=8))
        scene = self.sink.kinds("scene")[-1]
        self.assertEqual(scene["mode"], "cinematic")
        self.assertEqual(scene["target"], Y)
        self.assertEqual(scene["lower_third"]["title"], "BOSS SPAWNED")
        self.assertEqual(scene["lower_third"]["subtitle"], "Nullshade Seraph")
        self.assertTrue(self.sink.kinds("letterbox")[-1]["on"])
        self.assertEqual(self.b.state.director[-1]["decision"], "CINEMATIC")
        self.b.handle(ev("cinematic_end", src="director", target=Y))
        self.assertEqual(self.sink.kinds("scene")[-1]["mode"], "grid")
        self.assertFalse(self.sink.kinds("letterbox")[-1]["on"])

    def test_lower_third_not_borrowed_from_another_player(self):
        self.b.handle(ev("boss_spawn", src="server", players=[Y], drama=8, name="Nullshade Seraph"))
        self.b.handle(ev("cinematic_start", src="director", target=K, shot="pushin"))
        lt = self.sink.kinds("scene")[-1]["lower_third"]
        self.assertEqual(lt["title"], K.upper())

    def test_cinematic_safety_timeout_returns_to_grid(self):
        self.b.cinematic(R, "PLAN — going wrong", seconds=-5.9)    # limit = seconds + 6 = 0.1 s
        time.sleep(0.4)
        self.assertEqual(self.sink.kinds("scene")[-1]["mode"], "grid")

    def test_cut_to_focuses_a_quadrant(self):
        self.b.handle(ev("director", src="director", decision="CUT_TO", target=K))
        self.assertEqual(self.sink.kinds("scene")[-1], {"kind": "scene", "mode": "grid", "focus": K})

    def test_narrate_deduped_between_director_and_narrate_event(self):
        self.b.handle(ev("narrate", src="director", text="Nobody saw it coming."))
        self.b.handle(ev("director", src="director", decision="NARRATE", line="Nobody saw it coming."))
        narr = self.sink.kinds("narrate")
        self.assertEqual(len(narr), 1)
        self.assertEqual(narr[0]["audio_url"], "/audio/1.wav")
        self.assertEqual(self.tts.lines[0].priority, 0)

    def test_narrate_voice_false_never_synthesises(self):
        """Regression: the demo's narrator used to render a SAPI clip every loop despite voice=False."""
        self.b.handle(ev("narrate", src="system", text="Quiet narration.", voice=False))
        self.b.narrate("Also quiet.", voice=False)
        narr = self.sink.kinds("narrate")
        self.assertEqual(len(narr), 2)
        self.assertFalse(any("audio_url" in n for n in narr))
        self.assertEqual(self.bus_types(), [])

    def test_episode_start_title_card_clock_intro(self):
        self.b.handle(ev("episode_start", src="server", minutes=30, episode=3))
        title = self.sink.kinds("title_card")[0]
        self.assertEqual(title["subtitle"], "EPISODE 03")
        clock = self.sink.kinds("clock")[-1]
        self.assertEqual(clock["total_s"], 1800)
        self.assertTrue(clock["running"])
        self.assertEqual(self.sink.kinds("scene")[-1]["mode"], "intro")

    def test_profile_ready_first_join_intro_card(self):
        self.b.handle(ev("profile_ready", src="server", players=[R], first_join=True))
        s = self.sink.kinds("scene")[-1]
        self.assertEqual((s["mode"], s["target"]), ("intro", R))
        self.assertEqual(s["lower_third"]["subtitle"], R_ARCH)

    def test_pause_freezes_clock(self):
        self.b.start_clock(1800)
        self.b._on_dial("paused", True)
        e1 = self.b.elapsed()
        time.sleep(0.15)
        self.assertAlmostEqual(self.b.elapsed(), e1, delta=0.01)
        self.assertFalse(self.sink.kinds("clock")[-1]["running"])
        self.b._on_dial("paused", False)
        time.sleep(0.1)
        self.assertGreater(self.b.elapsed(), e1 + 0.05)

    def test_agent_turn_action_stuck_cost(self):
        self.b.handle(ev("agent_stuck", players=[K], char=K, seconds=40))
        self.assertTrue(self.b.state.chars[K]["stuck"])
        self.b.handle(ev("agent_turn", players=[K], char=K, goal="get a sword", latency_s=4.24,
                         cost_usd=0.02))
        k = self.b.state.chars[K]
        self.assertEqual((k["goal"], k["latency_s"], k["turns"], k["stuck"]), ("get a sword", 4.2, 1, False))
        self.assertEqual(self.sink.kinds("status")[-1]["goal"], "get a sword")
        self.b.handle(ev("agent_action", players=[K], char=K, tool="warp", args={"dest": "hub"},
                         result="arrived", ok=True))
        self.assertIn("warp(hub)", self.b.state.chars[K]["last_action"])
        self.b.handle(ev("cost", src="system", usd=1.5, turns=100, per_hour=6.0))
        self.assertEqual(self.sink.kinds("cost")[-1], {"kind": "cost", "usd": 1.5, "turns": 100, "per_hour": 6.0})

    def test_status_dedupes_unchanged_fields(self):
        self.b._status(Y, purse=100, island="Hub")
        self.b._status(Y, purse=100, island="Hub")
        self.b._status(Y, purse=150)
        st = self.sink.kinds("status")
        self.assertEqual(len(st), 2)
        self.assertEqual(st[1]["purse"], 150)
        self.assertNotIn("island", st[1])

    def test_own_tts_events_and_junk_are_harmless(self):
        self.b.handle(ev("tts_start", src="tts", char=K))
        self.b.handle({"type": "weird"})
        self.b.handle(ev("director", src="director"))
        self.assertEqual(self.sink.kinds("caption"), [])

    def test_start_registers_control_and_stop_removes_it(self):
        from show import server as S
        self.b.start()
        try:
            self.assertIn(self.b._control, S._CONTROL)
            r = S.run_control("test_voice", {"char": R})
            self.assertTrue(r["ok"])
            got = [self.busq.get(timeout=1) for _ in range(1)]
            self.assertEqual((got[0]["type"], got[0]["data"]["char"]), ("inner_voice", R))
        finally:
            self.b.stop()
        self.assertNotIn(self.b._control, S._CONTROL)

    def test_level_and_headline_helpers(self):
        self.assertEqual(OB.level_of({"skills": {"A": {"level": 2}, "B": {"level": 3}}}), 2.5)
        self.assertEqual(OB.level_of({"sb_level": 7}), 7)
        self.assertIsNone(OB.level_of({}))
        h = OB.headline(ev("near_death", players=[K], health=9.4, max=100))
        self.assertEqual(h, {"title": "NEAR DEATH", "subtitle": f"{K} at 9 HP"})


if __name__ == "__main__":
    unittest.main()
