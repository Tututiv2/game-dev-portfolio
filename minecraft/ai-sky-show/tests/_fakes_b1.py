"""Small fakes shared by B1's tests (brain/agent/prompts). Headless: no network, no game."""
from __future__ import annotations

import sys
import types
from pathlib import Path

ORCH = Path(__file__).resolve().parents[1] / "orchestrator"
if str(ORCH) not in sys.path:
    sys.path.insert(0, str(ORCH))

from show.dials import DEFAULTS  # noqa: E402
from show.brain import Reply  # noqa: E402


class FakeDials:
    """Dict-backed stand-in for show.dials.DIALS that never touches config/dials.json."""

    def __init__(self, **over):
        self.v = dict(DEFAULTS)
        self.v.update(over)

    def __getitem__(self, k):
        return self.v[k]

    def get(self, k, d=None):
        return self.v.get(k, d)

    def set(self, k, val):
        self.v[k] = val
        return val


class FakeBrain:
    """Scripted brain: pops replies (str or Reply) in order; records every turn's blocks."""

    def __init__(self, replies):
        self.replies = list(replies)
        self.calls: list[list[dict]] = []
        self.resets: list[str] = []
        self.closed = False
        self.memory = ""

    def turn(self, blocks):
        self.calls.append(blocks)
        r = self.replies.pop(0) if self.replies else '{"inner_voice": null, "emotion": "calm", "say": null, "goal": "idle", "actions": []}'
        if isinstance(r, Reply):
            return r
        return Reply(r, {"input_tokens": 10, "output_tokens": 5, "cache_read_input_tokens": 100}, 0.001, 0.01, None)

    def reset(self, summary):
        self.resets.append(summary)
        self.memory = summary

    def close(self):
        self.closed = True


class FakePilot:
    def __init__(self, pos=(0.0, 70.0, 0.0)):
        self.pos = pos

    def state(self, **_):
        x, y, z = self.pos
        return {"player": {"x": x, "y": y, "z": z}}


def fake_macros(ok=True):
    calls = []

    def run_action(ctx, tool, args):
        calls.append((tool, dict(args)))
        return {"ok": ok, "result": f"did {tool}"}
    return types.SimpleNamespace(run_action=run_action, calls=calls,
                                 describe_tools=lambda: "- fake_tool() - does nothing")


def fake_observe(screens=True):
    seen = []

    def observe(ctx, since):
        seen.append(since)
        return f"You are on an island. chat#{since}", since + 1

    def screenshot_block(ctx, max_w=768):
        if not screens:
            return None
        return {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": "AAAA"}}
    return types.SimpleNamespace(observe=observe, screenshot_block=screenshot_block, seen=seen)
