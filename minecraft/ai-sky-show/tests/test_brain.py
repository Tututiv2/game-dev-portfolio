"""B1: brain.py + cost.py. A fake `claude -p` process replays the stream-json shapes observed in
real calls on 2026-10-03 (see brain.py docstring), so nothing here touches the network."""
from __future__ import annotations

import json
import queue
import tempfile
import time
import unittest
from pathlib import Path

import _fakes_b1  # noqa: F401  (sys.path)
from _fakes_b1 import FakeDials
from show import brain as B
from show.cost import CostMeter, TurnBudget, usage_cost
from show.events import EventBus


# --- a fake claude process -----------------------------------------------------------------------
class _Stdin:
    def __init__(self, proc):
        self.proc = proc
        self.buf = b""
        self.closed = False

    def write(self, data):
        self.buf += data
        return len(data)

    def flush(self):
        while b"\n" in self.buf:
            line, self.buf = self.buf.split(b"\n", 1)
            self.proc.on_message(json.loads(line))

    def close(self):
        self.closed = True
        self.proc.out.put(None)


class _Stream:
    def __init__(self, q):
        self.q = q

    def __iter__(self):
        while True:
            item = self.q.get()
            if item is None:
                return
            yield item


class FakeClaude:
    """script: list of callables(msg) -> list of stdout dicts (or "die" / "hang")."""
    instances: list["FakeClaude"] = []

    def __init__(self, cmd, cwd=None, script=None, **kw):
        self.cmd, self.cwd, self.kw = cmd, cwd, kw
        self.script = script
        self.out: queue.Queue = queue.Queue()
        self.err: queue.Queue = queue.Queue()
        self.stdin = _Stdin(self)
        self.stdout = _Stream(self.out)
        self.stderr = _Stream(self.err)
        self.received: list[dict] = []
        self.cum = 0.0
        self._rc = None
        self.inited = False
        FakeClaude.instances.append(self)

    def poll(self):
        return self._rc

    def wait(self, timeout=None):
        self._rc = 0
        return 0

    def kill(self):
        self._rc = -9

    def on_message(self, msg):
        self.received.append(msg)
        action = self.script.pop(0) if self.script else "ok"
        if action == "die":
            self.err.put(b"boom: something broke\n")
            self._rc = 1
            self.out.put(None)
            return
        if action == "hang":
            return
        lines = []
        if not self.inited:
            self.inited = True
            lines.append({"type": "system", "subtype": "init", "cwd": self.cwd, "tools": [], "mcp_servers": [],
                          "model": "claude-opus-5-5"})
        if action == "refusal":
            lines += [{"type": "system", "subtype": "model_refusal_no_fallback", "api_refusal_category": "cyber"},
                      {"type": "assistant", "message": {"stop_reason": "refusal", "content": [{"type": "text", "text": "API Error"}]}},
                      {"type": "result", "subtype": "success", "is_error": True, "stop_reason": "refusal",
                       "result": "API Error: safeguards flagged this session", "total_cost_usd": self.cum + 0.001,
                       "usage": {"input_tokens": 4, "output_tokens": 0}}]
        elif action == "429":
            lines += [{"type": "rate_limit_event", "rate_limit_info": {"status": "allowed"}},
                      {"type": "result", "is_error": True, "api_error_status": 429, "result": "API Error: rate limit",
                       "total_cost_usd": self.cum, "usage": {}}]
        else:
            self.cum += 0.01
            text = action if isinstance(action, str) and action != "ok" else '{"inner_voice": "hi", "actions": []}'
            lines += [{"type": "assistant", "message": {"content": [{"type": "thinking", "thinking": ""}]}},
                      {"type": "assistant", "message": {"content": [{"type": "text", "text": text}]}},
                      {"type": "rate_limit_event", "rate_limit_info": {"status": "allowed"}},
                      {"type": "result", "subtype": "success", "is_error": False, "result": text,
                       "total_cost_usd": self.cum,
                       "usage": {"input_tokens": 3, "output_tokens": 40, "cache_read_input_tokens": 1000,
                                 "cache_creation_input_tokens": 0}}]
        for l in lines:
            self.out.put((json.dumps(l) + "\n").encode())


def make_brain(tmp, script, **kw):
    FakeClaude.instances.clear()
    popen = lambda cmd, **k: FakeClaude(cmd, script=script, **k)  # noqa: E731
    return B.ClaudeCodeBrain("kaito", "SYSTEM PROMPT", "claude-opus-5-5", "medium", Path(tmp) / "kaito",
                             popen=popen, exe="claude", **kw)


class ClaudeCodeBrainTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()

    def test_command_and_cwd(self):
        b = make_brain(self.tmp, ["ok"])
        r = b.turn([{"type": "text", "text": "hello"}])
        self.assertIsNone(r.error)
        p = FakeClaude.instances[0]
        cmd = p.cmd
        for flag in ("-p", "--verbose", "--strict-mcp-config", "--no-session-persistence"):
            self.assertIn(flag, cmd)
        self.assertEqual(cmd[cmd.index("--input-format") + 1], "stream-json")
        self.assertEqual(cmd[cmd.index("--output-format") + 1], "stream-json")
        self.assertEqual(cmd[cmd.index("--tools") + 1], "")
        self.assertEqual(cmd[cmd.index("--effort") + 1], "medium")
        self.assertEqual(cmd[cmd.index("--model") + 1], "claude-opus-5-5")
        spf = Path(cmd[cmd.index("--system-prompt-file") + 1])
        self.assertEqual(spf.read_text(encoding="utf-8"), "SYSTEM PROMPT")
        self.assertEqual(p.cwd, str(Path(self.tmp) / "kaito"))
        self.assertEqual(p.kw.get("creationflags"), B.CREATE_NO_WINDOW)
        self.assertEqual(b.init["tools"], [])

    def test_default_cwd_is_outside_ai_game_tree(self):
        from show.config import AGENT_HOME, ROOT
        b = B.ClaudeCodeBrain("ren", "x", "m", "low")
        ai_game = ROOT.parent.resolve()
        self.assertFalse(str(b.cwd.resolve()).lower().startswith(str(ai_game).lower()), b.cwd)
        self.assertTrue(str(b.cwd).startswith(str(AGENT_HOME)))

    def test_persistent_process_multi_turn_cost_delta_and_images(self):
        b = make_brain(self.tmp, ["ok", "ok"])
        img = {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": "QUJD"}}
        r1 = b.turn([{"type": "text", "text": "a"}, img])
        r2 = b.turn([{"type": "text", "text": "b"}])
        self.assertEqual(len(FakeClaude.instances), 1, "one persistent process")
        self.assertAlmostEqual(r1.cost_usd, 0.01)
        self.assertAlmostEqual(r2.cost_usd, 0.01)       # delta of the cumulative total, not 0.02
        sent = FakeClaude.instances[0].received[0]
        self.assertEqual(sent["type"], "user")
        self.assertEqual(sent["message"]["content"][1], img)
        self.assertEqual(r1.usage["cache_read_input_tokens"], 1000)

    def test_refusal_restarts_session_with_memory(self):
        b = make_brain(self.tmp, ["refusal", "ok"])
        b.memory = "I found the hub."
        r = b.turn([{"type": "text", "text": "x"}])
        self.assertEqual(r.error, "refusal:cyber")
        r2 = b.turn([{"type": "text", "text": "y"}])
        self.assertIsNone(r2.error)
        self.assertEqual(len(FakeClaude.instances), 2, "new session after a refusal")
        first = FakeClaude.instances[1].received[0]["message"]["content"][0]["text"]
        self.assertIn("I found the hub.", first)

    def test_process_death_then_recovery(self):
        b = make_brain(self.tmp, ["die", "ok"])
        r = b.turn([{"type": "text", "text": "x"}])
        self.assertTrue(r.error.startswith("process_died"), r.error)
        r2 = b.turn([{"type": "text", "text": "y"}])
        self.assertIsNone(r2.error)
        self.assertEqual(len(FakeClaude.instances), 2)

    def test_timeout(self):
        b = make_brain(self.tmp, ["hang", "ok"], timeout_s=0.3)
        r = b.turn([{"type": "text", "text": "x"}])
        self.assertEqual(r.error, "timeout")
        self.assertIsNone(b.turn([{"type": "text", "text": "y"}]).error)

    def test_rate_limit_backoff(self):
        b = make_brain(self.tmp, ["429", "ok"])
        r = b.turn([{"type": "text", "text": "x"}])
        self.assertTrue(r.error.startswith("rate_limited"), r.error)
        n = len(FakeClaude.instances[0].received)
        r2 = b.turn([{"type": "text", "text": "y"}])
        self.assertTrue(r2.error.startswith("backoff"), r2.error)
        self.assertEqual(len(FakeClaude.instances[0].received), n, "no call during backoff")

    def test_reset_injects_summary_once(self):
        b = make_brain(self.tmp, ["ok", "ok", "ok"])
        b.turn([{"type": "text", "text": "a"}])
        b.reset("my memory note")
        b.turn([{"type": "text", "text": "b"}])
        b.turn([{"type": "text", "text": "c"}])
        self.assertEqual(len(FakeClaude.instances), 2)
        msgs = FakeClaude.instances[1].received
        self.assertIn("my memory note", msgs[0]["message"]["content"][0]["text"])
        self.assertNotIn("my memory note", json.dumps(msgs[1]))

    def test_close(self):
        b = make_brain(self.tmp, ["ok"])
        b.turn([{"type": "text", "text": "a"}])
        b.close()
        self.assertIsNone(b.proc)


# --- ApiBrain with a fake SDK client ---------------------------------------------------------------
class _Obj:
    def __init__(self, **kw):
        self.__dict__.update(kw)


class FakeClient:
    def __init__(self, responses):
        self.responses = list(responses)
        self.requests = []
        self.beta = _Obj(messages=_Obj(create=self.create))

    def create(self, **req):
        self.requests.append(json.loads(json.dumps(req)))
        return self.responses.pop(0)


def _resp(text="{}", stop="end_turn", usage=None, cat=None):
    return _Obj(stop_reason=stop, stop_details=_Obj(category=cat) if cat else None,
                content=[{"type": "thinking", "thinking": "", "signature": "s"}, {"type": "text", "text": text}],
                usage=usage or {"input_tokens": 100, "output_tokens": 50, "cache_read_input_tokens": 2000,
                                "cache_creation_input_tokens": 300})


class ApiBrainTests(unittest.TestCase):
    def test_caching_layout_and_append_only(self):
        c = FakeClient([_resp("one"), _resp("two")])
        b = B.ApiBrain("kaito", "FROZEN", "claude-opus-5-5", "medium", client=c)
        b.turn([{"type": "text", "text": "obs1"}])
        b.turn([{"type": "text", "text": "obs2"}])
        r1, r2 = c.requests
        self.assertEqual(r1["system"][0]["cache_control"], {"type": "ephemeral"})
        self.assertEqual(r1["output_config"], {"effort": "medium"})
        self.assertNotIn("tool_choice", r1)
        self.assertEqual(r1["thinking"], {"type": "adaptive"})
        self.assertEqual(r1["fallbacks"], "default")
        # turn 2: breakpoint on the last HISTORY block, volatile observation last and unmarked
        msgs = r2["messages"]
        self.assertEqual(len(msgs), 3)
        self.assertEqual(msgs[1]["role"], "assistant")
        self.assertEqual(msgs[1]["content"][-1]["cache_control"], {"type": "ephemeral"})
        self.assertNotIn("cache_control", msgs[2]["content"][-1])
        self.assertEqual(msgs[2]["content"][0]["text"], "obs2")
        # stored history is never mutated (preserved thinking needs append-only)
        self.assertNotIn("cache_control", json.dumps(b.history))
        self.assertEqual(b.history[1]["content"][0]["type"], "thinking")

    def test_cost(self):
        c = FakeClient([_resp("x")])
        b = B.ApiBrain("k", "S", "claude-opus-5-5", "low", client=c)
        r = b.turn([{"type": "text", "text": "o"}])
        want = (100 * 4 + 50 * 20 + 2000 * 0.2 + 300 * 5) / 1e6
        self.assertAlmostEqual(r.cost_usd, want)

    def test_refusal(self):
        c = FakeClient([_resp("", stop="refusal", cat="cyber")])
        b = B.ApiBrain("k", "S", "claude-opus-5-5", "low", client=c)
        r = b.turn([{"type": "text", "text": "o"}])
        self.assertEqual(r.error, "refusal:cyber")
        self.assertEqual(b.history, [])

    def test_missing_sdk_is_a_clear_error(self):
        try:
            import anthropic  # noqa: F401
            self.skipTest("anthropic installed")
        except ImportError:
            pass
        with self.assertRaises(RuntimeError):
            B.ApiBrain("k", "S", "claude-opus-5-5", "low")

    def test_make_brain_respects_backend_dial(self):
        b = B.make_brain("kaito", "S", dials=FakeDials(backend="claude_code", effort="low"))
        self.assertIsInstance(b, B.ClaudeCodeBrain)
        self.assertEqual(b.effort, "low")


# --- cost.py -------------------------------------------------------------------------------------
class CostTests(unittest.TestCase):
    def test_prices_match_observed_real_call(self):
        # real claude -p call 2026-10-03: 493 in + 46 out -> total_cost_usd 0.002892
        self.assertAlmostEqual(usage_cost("claude-opus-5-5", {"input_tokens": 493, "output_tokens": 46}), 0.002892)
        # 576-token 1h cache write + 2 in + 5 out -> 0.004716 (Claude Code uses the 1-hour TTL)
        u = {"input_tokens": 2, "output_tokens": 5, "cache_creation_input_tokens": 576,
             "cache_creation": {"ephemeral_1h_input_tokens": 576, "ephemeral_5m_input_tokens": 0}}
        self.assertAlmostEqual(usage_cost("claude-opus-5-5", u), 0.004716)

    def test_turn_budget_window(self):
        t = [0.0]
        tb = TurnBudget(clock=lambda: t[0])
        self.assertTrue(tb.try_acquire(2))
        self.assertTrue(tb.try_acquire(2))
        self.assertFalse(tb.try_acquire(2))
        self.assertGreater(tb.wait_s(2), 3000)
        t[0] = 3600.0
        self.assertTrue(tb.try_acquire(2))
        self.assertFalse(tb.try_acquire(0))

    def test_meter_and_budget_stop(self):
        t = [0.0]
        m = CostMeter(clock=lambda: t[0])
        r = B.Reply("x", {"input_tokens": 10, "output_tokens": 5, "cache_read_input_tokens": 90}, 30.0, 2.0, None)
        m.record("Kaito", r)
        m.record("Ren", B.Reply("", {}, None, 1.0, "timeout"))
        t[0] = 1800.0
        s = m.snapshot()
        self.assertEqual(s["turns"], 2)
        self.assertEqual(s["errors"], 1)
        self.assertAlmostEqual(s["per_hour"], 60.0)
        self.assertAlmostEqual(s["cache_hit_ratio"], 0.9)
        bus = EventBus()
        d = FakeDials(backend="claude_code", budget_usd_stop=20.0)
        self.assertFalse(m.check_budget(d, bus))            # subscription: never a dollar stop
        d = FakeDials(backend="api", budget_usd_stop=20.0)
        self.assertTrue(m.check_budget(d, bus))
        self.assertTrue(d["paused"])
        self.assertEqual([e["type"] for e in bus.recent()], ["budget_stop"])
        m.check_budget(d, bus)
        self.assertEqual(len(bus.recent()), 1, "budget_stop fires once")


if __name__ == "__main__":
    unittest.main()
