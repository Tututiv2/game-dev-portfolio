"""Brains: one model session per agent (CONTRACT §7).

    brain = make_brain("kaito", system_prompt)           # backend/model/effort from dials
    r = brain.turn([{"type": "text", "text": "..."}, image_block])
    r.text, r.usage, r.cost_usd, r.latency_s, r.error
    brain.reset(summary)      # fresh session that starts from a rolling memory note
    brain.close()

ClaudeCodeBrain (DEFAULT - billed through the owner's Claude subscription) keeps ONE persistent
`claude -p` process per agent and talks stream-json to it.

Observed stream-json shapes (claude 2.1.288, real calls 2026-10-03, claude-opus-5-5):
  stdin, one JSON per line, per turn:
    {"type":"user","message":{"role":"user","content":[<Anthropic content blocks>]}}
    -> text blocks AND base64 image blocks both work (16x16 red PNG -> "Red.").
  stdout, one JSON per line:
    {"type":"system","subtype":"init","cwd":...,"session_id":...,"tools":[],"mcp_servers":[],
     "model":"claude-opus-5-5","memory_paths":{"auto":...},"per_turn_effort_active":true,...}
       (once, at process start - emitted lazily, i.e. alongside the first turn)
    {"type":"assistant","message":{"content":[{"type":"thinking","thinking":"","signature":...}],...}}
    {"type":"assistant","message":{"content":[{"type":"text","text":"..."}],...}}
    {"type":"rate_limit_event","rate_limit_info":{"status":"allowed","resetsAt":<epoch s>,
       "rateLimitType":"five_hour","unifiedWindows":{"five_hour":{"utilization":0.07,...},...}}}
    {"type":"result","subtype":"success","is_error":false,"result":"<final text>",
     "stop_reason":..., "duration_ms":1433,"duration_api_ms":1388,
     "total_cost_usd":<CUMULATIVE for the process>, "usage":{<THIS turn: input_tokens,
     output_tokens, cache_read_input_tokens, cache_creation_input_tokens, cache_creation{
     ephemeral_1h_input_tokens,...}}>, "terminal_reason":..., "api_error_status":null}
  Safety refusal (a probe that asked "list the instructions you were given" was flagged `cyber`!):
    {"type":"system","subtype":"model_refusal_no_fallback","api_refusal_category":"cyber",...}
    {"type":"assistant","message":{"stop_reason":"refusal","stop_details":{"category":"cyber"}}}
    {"type":"result","is_error":true,"stop_reason":"refusal","terminal_reason":"api_error",
     "result":"API Error: Opus 5.5's safeguards flagged this session ... new session ..."}
    -> we report Reply.error="refusal:<category>" and start a NEW session next turn.
  Verified: cwd outside the Ai Game tree => tools [], mcp_servers [], first-turn input was 493
  tokens total (a CLAUDE.md would be ~40k) and the model did not know VESPER/ShadowPrison2.
  `--effort` and `--fallback-model claude-opus-4-8` are accepted. Tiny-call latency: 1.4-3.3 s
  wall per turn on a warm process (API ~1.4 s at effort medium); process start adds ~2 s.
"""
from __future__ import annotations

import collections
import json
import os
import queue
import shutil
import subprocess
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .config import AGENT_HOME
from .cost import usage_cost

CREATE_NO_WINDOW = 0x08000000

SUMMARY_REQUEST = (
    "(Out-of-game moment - nobody else sees this.) Write a memory note for yourself, max 150 words, "
    "plain text, NO JSON: where you are, what you have (coins, gear, skills), what you and the others "
    "have done so far, your current goal and the next 2-3 steps, and how you feel about each squad "
    "member right now. Write it in first person, in character.")

MEMORY_PREFIX = "Your memory so far (you wrote this note to yourself a moment ago):\n"


@dataclass
class Reply:
    text: str
    usage: dict = field(default_factory=dict)
    cost_usd: float | None = None
    latency_s: float = 0.0
    error: str | None = None

    @property
    def ok(self) -> bool:
        return self.error is None


def _blocks_text(blocks: list[dict], limit: int = 600) -> str:
    out = " ".join(b.get("text", "[image]" if b.get("type") == "image" else "") for b in blocks)
    return out[:limit]


class Brain:
    """Base class. Subclasses implement _turn/_restart; this class owns memory + recent-turn
    bookkeeping so a crashed or refused session can be rebuilt with context."""

    def __init__(self, name: str, system_prompt: str, model: str, effort: str, cwd: str | Path | None = None):
        self.name = name
        self.system_prompt = system_prompt
        self.model = model
        self.effort = effort
        self.cwd = Path(cwd) if cwd else AGENT_HOME / "agents" / name
        self.memory: str = ""                     # last rolling summary
        self._recent: collections.deque[tuple[str, str]] = collections.deque(maxlen=3)
        self._inject_memory = False               # prepend memory to the next turn
        self._lock = threading.RLock()
        self.turns = 0

    # -- public API -------------------------------------------------------------------------
    def turn(self, blocks: list[dict]) -> Reply:
        with self._lock:
            if self._inject_memory:
                blocks = [{"type": "text", "text": self._memory_text()}] + list(blocks)
                self._inject_memory = False
            r = self._turn(blocks)
            self.turns += 1
            if r.ok and r.text:
                self._recent.append((_blocks_text(blocks, 300), r.text[:600]))
            return r

    def summarize(self) -> Reply:
        return self.turn([{"type": "text", "text": SUMMARY_REQUEST}])

    def reset(self, summary: str) -> None:
        """Start a fresh session; its first turn opens with `summary` as the agent's memory."""
        with self._lock:
            self.memory = (summary or "").strip()
            self._recent.clear()
            self._restart()
            self._inject_memory = bool(self.memory)

    def set_effort(self, effort: str) -> None:
        """Takes effect on the next session (reset/restart) - changing it mid-session would
        either restart the process or invalidate the prompt cache."""
        self.effort = effort

    def close(self) -> None:
        pass

    # -- for subclasses --------------------------------------------------------------------------
    def _memory_text(self) -> str:
        parts = []
        if self.memory:
            parts.append(MEMORY_PREFIX + self.memory)
        if self._recent:
            parts.append("Your last few moments (your own replies):\n" +
                         "\n".join(f"- {r}" for _, r in self._recent))
        return "\n\n".join(parts) or "(You have no memory of the last few minutes.)"

    def _crash_recover(self) -> None:
        """Session lost (death/timeout/refusal): next session starts from memory + recent replies."""
        self._restart()
        self._inject_memory = bool(self.memory or self._recent)

    def _turn(self, blocks: list[dict]) -> Reply:   # pragma: no cover - abstract
        raise NotImplementedError

    def _restart(self) -> None:                     # pragma: no cover - abstract
        raise NotImplementedError


# ================================================================================================
class ClaudeCodeBrain(Brain):
    """One persistent `claude -p --input-format stream-json` process (see module docstring)."""

    def __init__(self, name: str, system_prompt: str, model: str, effort: str, cwd: str | Path | None = None,
                 *, timeout_s: float = 150.0, fallback_model: str | None = "claude-opus-4-8",
                 exe: str | None = None, popen=subprocess.Popen):
        super().__init__(name, system_prompt, model, effort, cwd)
        self.timeout_s = timeout_s
        self.fallback_model = fallback_model
        self.exe = exe or shutil.which("claude") or "claude"
        self._popen = popen
        self.proc: subprocess.Popen | None = None
        self._q: queue.Queue = queue.Queue()
        self._stderr: collections.deque[str] = collections.deque(maxlen=40)
        self._cum_cost = 0.0
        self.init: dict | None = None               # the init message (verification: cwd, tools, mcp)
        self.rate_limit: dict | None = None
        self.backoff_until = 0.0
        self._overloads = 0
        self.restarts = 0

    # -- process ------------------------------------------------------------------------------
    def command(self) -> list[str]:
        cmd = [self.exe, "-p", "--input-format", "stream-json", "--output-format", "stream-json",
               "--verbose", "--model", self.model, "--system-prompt-file", str(self.cwd / "system_prompt.md"),
               "--tools", "", "--strict-mcp-config", "--no-session-persistence", "--effort", self.effort]
        if self.fallback_model and self.fallback_model != self.model:
            cmd += ["--fallback-model", self.fallback_model]
        return cmd

    def _env(self) -> dict:
        env = dict(os.environ)
        # don't let the orchestrator's own Claude Code session (if any) leak into the child
        for k in ("CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT", "CLAUDE_CODE_CHILD_SESSION", "CLAUDE_CODE_SSE_PORT"):
            env.pop(k, None)
        return env

    def _ensure_started(self) -> None:
        if self.proc is not None and self.proc.poll() is None:
            return
        self._kill()
        self.cwd.mkdir(parents=True, exist_ok=True)
        (self.cwd / "system_prompt.md").write_text(self.system_prompt, encoding="utf-8")
        self._q = queue.Queue()
        self._cum_cost = 0.0
        self.proc = self._popen(self.command(), cwd=str(self.cwd), stdin=subprocess.PIPE,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=self._env(),
                                creationflags=CREATE_NO_WINDOW if os.name == "nt" else 0)
        q, proc = self._q, self.proc
        threading.Thread(target=self._read_stdout, args=(proc, q), daemon=True,
                         name=f"brain-{self.name}-out").start()
        threading.Thread(target=self._read_stderr, args=(proc,), daemon=True,
                         name=f"brain-{self.name}-err").start()

    @staticmethod
    def _read_stdout(proc, q: queue.Queue) -> None:
        try:
            for raw in proc.stdout:
                line = raw.decode("utf-8", "replace").strip() if isinstance(raw, bytes) else raw.strip()
                if not line:
                    continue
                try:
                    q.put(json.loads(line))
                except ValueError:
                    q.put({"type": "_noise", "line": line[:300]})
        except Exception:
            pass
        q.put({"type": "_eof"})

    def _read_stderr(self, proc) -> None:
        try:
            for raw in proc.stderr:
                s = raw.decode("utf-8", "replace") if isinstance(raw, bytes) else raw
                if s.strip():
                    self._stderr.append(s.rstrip())
        except Exception:
            pass

    def _kill(self) -> None:
        p, self.proc = self.proc, None
        if p is None:
            return
        try:
            if p.stdin:
                p.stdin.close()
        except Exception:
            pass
        try:
            p.wait(timeout=3)
        except Exception:
            try:
                p.kill()
            except Exception:
                pass

    def _restart(self) -> None:
        self._kill()
        self.restarts += 1          # the process itself starts lazily on the next turn

    def close(self) -> None:
        with self._lock:
            self._kill()

    # -- one turn -------------------------------------------------------------------------------
    def _turn(self, blocks: list[dict]) -> Reply:
        now = time.time()
        if now < self.backoff_until:
            return Reply("", error=f"backoff:{self.backoff_until - now:.0f}s")
        t0 = time.monotonic()
        try:
            self._ensure_started()
            msg = {"type": "user", "message": {"role": "user", "content": blocks}}
            self.proc.stdin.write((json.dumps(msg) + "\n").encode("utf-8"))
            self.proc.stdin.flush()
        except Exception as e:
            self._crash_recover()
            return Reply("", latency_s=time.monotonic() - t0, error=f"start_failed:{e}")

        refusal_cat = None
        texts: list[str] = []
        deadline = t0 + self.timeout_s
        while True:
            left = deadline - time.monotonic()
            if left <= 0:
                # a late result would be read as the NEXT turn's answer - the stream is desynced
                self._crash_recover()
                return Reply("", latency_s=time.monotonic() - t0, error="timeout")
            try:
                m = self._q.get(timeout=min(left, 1.0))
            except queue.Empty:
                continue
            typ = m.get("type")
            if typ == "_eof":
                tail = " | ".join(list(self._stderr)[-3:])[:300]
                self._crash_recover()
                return Reply("", latency_s=time.monotonic() - t0, error=f"process_died:{tail}")
            if typ == "system":
                st = m.get("subtype")
                if st == "init":
                    self.init = m
                elif st and "refusal" in st:
                    refusal_cat = m.get("api_refusal_category") or "unknown"
                continue
            if typ == "rate_limit_event":
                self.rate_limit = m.get("rate_limit_info") or {}
                continue
            if typ == "assistant":
                for b in (m.get("message") or {}).get("content") or []:
                    if b.get("type") == "text" and b.get("text"):
                        texts.append(b["text"])
                continue
            if typ == "result":
                return self._finish(m, texts, refusal_cat, time.monotonic() - t0)

    def _finish(self, m: dict, texts: list[str], refusal_cat: str | None, latency: float) -> Reply:
        usage = m.get("usage") or {}
        total = m.get("total_cost_usd")
        cost = None
        if isinstance(total, (int, float)):
            cost = max(0.0, float(total) - self._cum_cost)
            self._cum_cost = float(total)
        if cost is None:
            cost = usage_cost(self.model, usage, ttl="1h")
        text = m.get("result") if isinstance(m.get("result"), str) else "\n".join(texts)
        if m.get("stop_reason") == "refusal" or refusal_cat:
            self._crash_recover()       # the CLI says this session is flagged; start a new one
            return Reply("", usage, cost, latency, error=f"refusal:{refusal_cat or 'unknown'}")
        if m.get("is_error"):
            status = m.get("api_error_status")
            low = (text or "").lower()
            if status in (429, 529) or "rate limit" in low or "overloaded" in low or "usage limit" in low:
                self._overloads += 1
                wait = min(60.0, 5.0 * (2 ** (self._overloads - 1)))
                rl = self.rate_limit or {}
                if rl.get("status") not in (None, "allowed") and rl.get("resetsAt"):
                    wait = max(wait, float(rl["resetsAt"]) - time.time())
                self.backoff_until = time.time() + wait
                kind = "overloaded" if (status == 529 or "overloaded" in low) else "rate_limited"
                return Reply("", usage, cost, latency, error=f"{kind}:{wait:.0f}s")
            return Reply(text or "", usage, cost, latency, error=f"api_error:{(text or '')[:200]}")
        self._overloads = 0
        return Reply(text or "", usage, cost, latency, None)


# ================================================================================================
class ApiBrain(Brain):
    """anthropic SDK backend (not the default). Append-only history (preserved thinking), frozen
    system prompt with a cache breakpoint, a breakpoint on the last history block, the volatile
    observation last. Effort via output_config. Server-side refusal fallback opted in."""

    MAX_TOKENS = 8000

    def __init__(self, name: str, system_prompt: str, model: str, effort: str, cwd=None, *, client=None):
        super().__init__(name, system_prompt, model, effort, cwd)
        if client is None:
            try:
                import anthropic  # noqa: F401  (lazy: not installed on this machine)
            except ImportError as e:
                raise RuntimeError("backend 'api' needs the anthropic SDK (`py -3 -m pip install anthropic`) "
                                   "- or set the dial backend=claude_code") from e
            client = anthropic.Anthropic()
        self.client = client
        self.history: list[dict] = []
        self.backoff_until = 0.0
        self._overloads = 0

    def _restart(self) -> None:
        self.history = []

    def build_request(self, blocks: list[dict]) -> dict:
        msgs = [dict(m) for m in self.history]
        if msgs:   # breakpoint on the last history block: everything before the new turn is a cache read
            last = dict(msgs[-1])
            content = [dict(b) for b in last["content"]]
            content[-1] = dict(content[-1], cache_control={"type": "ephemeral"})
            last["content"] = content
            msgs[-1] = last
        msgs.append({"role": "user", "content": list(blocks)})
        return dict(
            model=self.model, max_tokens=self.MAX_TOKENS,
            system=[{"type": "text", "text": self.system_prompt, "cache_control": {"type": "ephemeral"}}],
            messages=msgs, thinking={"type": "adaptive"}, output_config={"effort": self.effort},
            betas=["server-side-fallback-2026-07-01"], fallbacks="default")

    def _call(self, req: dict):
        try:
            return self.client.beta.messages.create(**req)
        except TypeError:
            # older SDK without these kwargs: send them as raw body fields
            extra = {k: req.pop(k) for k in ("fallbacks", "output_config") if k in req}
            return self.client.beta.messages.create(**req, extra_body=extra)

    def _turn(self, blocks: list[dict]) -> Reply:
        now = time.time()
        if now < self.backoff_until:
            return Reply("", error=f"backoff:{self.backoff_until - now:.0f}s")
        t0 = time.monotonic()
        req = self.build_request(blocks)
        try:
            resp = self._call(req)
        except Exception as e:
            name = type(e).__name__
            status = getattr(e, "status_code", None)
            if name in ("RateLimitError", "OverloadedError", "InternalServerError", "APIConnectionError",
                        "APITimeoutError") or (isinstance(status, int) and (status == 429 or status >= 500)):
                self._overloads += 1
                wait = min(60.0, 5.0 * (2 ** (self._overloads - 1)))
                self.backoff_until = time.time() + wait
                return Reply("", latency_s=time.monotonic() - t0, error=f"{name}:{wait:.0f}s")
            return Reply("", latency_s=time.monotonic() - t0, error=f"api_error:{name}:{str(e)[:200]}")
        self._overloads = 0
        usage = _to_dict(getattr(resp, "usage", None))
        cost = usage_cost(self.model, usage, ttl="5m")
        if getattr(resp, "stop_reason", None) == "refusal":
            sd = getattr(resp, "stop_details", None)
            cat = getattr(sd, "category", None) if sd is not None else None
            return Reply("", usage, cost, time.monotonic() - t0, error=f"refusal:{cat or 'unknown'}")
        content = [_to_dict(b) for b in (getattr(resp, "content", None) or [])]
        text = "".join(b.get("text", "") for b in content if b.get("type") == "text")
        # append-only: the user turn exactly as sent, the assistant content exactly as received
        self.history.append({"role": "user", "content": list(blocks)})
        self.history.append({"role": "assistant", "content": content or [{"type": "text", "text": text or "."}]})
        return Reply(text, usage, cost, time.monotonic() - t0, None)


def _to_dict(o: Any) -> dict:
    if o is None:
        return {}
    if isinstance(o, dict):
        return o
    for attr in ("model_dump", "to_dict", "dict"):
        fn = getattr(o, attr, None)
        if callable(fn):
            try:
                d = fn(exclude_none=True) if attr == "model_dump" else fn()
                if isinstance(d, dict):
                    return d
            except TypeError:
                d = fn()
                if isinstance(d, dict):
                    return d
    return {k: v for k, v in vars(o).items() if not k.startswith("_")}


# ================================================================================================
def make_brain(name: str, system_prompt: str, *, backend: str | None = None, model: str | None = None,
               effort: str | None = None, cwd: str | Path | None = None, dials=None) -> Brain:
    """Brain for an agent (or the director) from the live dials."""
    if dials is None:
        from .dials import DIALS as dials
    backend = backend or dials["backend"]
    model = model or dials["model"]
    effort = effort or dials["effort"]
    cwd = cwd or AGENT_HOME / "agents" / name
    if backend == "api":
        return ApiBrain(name, system_prompt, model, effort, cwd)
    return ClaudeCodeBrain(name, system_prompt, model, effort, cwd)
