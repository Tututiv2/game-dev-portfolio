"""PlayerAgent: one thread per character (CONTRACT §8).

    agent = PlayerAgent(char, pilot)          # pilot = TestPilot client (or MockPilot)
    agent.start()                             # loop: observe -> brain.turn -> voice/say/actions
    agent.pause() / resume() / restart() / kill()
    agent.final_turn()                        # wrap-up line (call after stop_loop())

Each turn emits on the BUS: `agent_turn`, `inner_voice{char,text,emotion,voice}` (D voices it;
`voice: false` means over the per-character voice budget -> caption only), `say{char,text}` (and the
line is actually typed via macros.run_action(ctx, "say", ...)), `agent_action` per action, and
`agent_stuck` when the body hasn't moved and nothing has succeeded for `STUCK_S`.
"""
from __future__ import annotations

import collections
import json
import re
import threading
import time
from dataclasses import dataclass, field
from typing import Any, Callable

from . import prompts
from .config import Character
from .cost import METER, TURN_BUDGET, CostMeter, TurnBudget
from .events import BUS, EventBus

STUCK_S = 120.0
STUCK_MOVE_BLOCKS = 2.0
MIN_GOAL_TURNS = 3          # switching goal sooner than this earns a "finish what you start" note
MAX_SAY_CHARS = 180
MAX_VOICE_CHARS = 240


# ---------------------------------------------------------------------------------------------
@dataclass
class AgentCtx:
    """Handed to macros.run_action / observe.observe (B2)."""
    pilot: Any
    char: Character
    bus: EventBus = field(default=BUS, repr=False)
    agent: Any = field(default=None, repr=False)
    last_pos: tuple[float, float, float] | None = None   # observe() may fill this in
    state: dict = field(default_factory=dict)            # scratch space for macros/observe

    def showcontrol_state(self) -> dict:
        """ShowControl GET /state/<account> (CONTRACT §4); {} when the server isn't reachable."""
        try:
            from .events import showcontrol
            return showcontrol(f"/state/{self.char.account}", timeout=3.0) or {}
        except Exception:
            return {}

    def log(self, text: str, **data) -> None:
        self.bus.emit("agent", "agent_log", players=[self.char.name], text=f"{self.char.name}: {text}",
                      data={"char": self.char.name, **data})


# --- reply parsing -----------------------------------------------------------------------------
_FENCE = re.compile(r"```(?:json|JSON)?\s*(.*?)```", re.S)


def first_json_object(text: str) -> dict | None:
    """First balanced {...} that parses as a JSON object. Tolerates fences and prose around it."""
    if not text:
        return None
    candidates = [m.group(1) for m in _FENCE.finditer(text)] + [text]
    for cand in candidates:
        i = cand.find("{")
        while i != -1:
            depth, in_str, esc = 0, False, False
            for j in range(i, len(cand)):
                ch = cand[j]
                if in_str:
                    if esc:
                        esc = False
                    elif ch == "\\":
                        esc = True
                    elif ch == '"':
                        in_str = False
                    continue
                if ch == '"':
                    in_str = True
                elif ch == "{":
                    depth += 1
                elif ch == "}":
                    depth -= 1
                    if depth == 0:
                        try:
                            obj = json.loads(cand[i:j + 1])
                        except ValueError:
                            obj = None
                        if isinstance(obj, dict):
                            return obj
                        break
            i = cand.find("{", i + 1)
    return None


def _clean_line(s: Any, limit: int) -> str | None:
    if s is None or isinstance(s, (dict, list)):
        return None
    s = " ".join(str(s).split())
    if not s or s.lower() in ("null", "none", "n/a"):
        return None
    return s[:limit].rstrip()


def _trim_voice(s: str, max_sentences: int = 3, limit: int = MAX_VOICE_CHARS) -> str:
    """The prompt asks for <=2 short sentences; real replies run to 3 short ones (a '...' counts as
    a sentence end) and the third is often the best line, so keep up to 3 that fit in `limit`."""
    parts = re.split(r"(?<=[.!?…])\s+", s)
    out = parts[0][:limit]
    for p in parts[1:max_sentences]:
        if len(out) + 1 + len(p) > limit:
            break
        out += " " + p
    return out.rstrip()


def normalize_reply(obj: dict, max_actions: int) -> dict:
    """Coerce a parsed reply into the CONTRACT §8 shape. Never raises."""
    iv = _clean_line(obj.get("inner_voice"), 600)
    if iv:
        iv = _trim_voice(iv)
    emo = str(obj.get("emotion") or "calm").strip().lower()
    if emo not in prompts.EMOTIONS:
        emo = "calm"
    say = _clean_line(obj.get("say"), MAX_SAY_CHARS)
    goal = _clean_line(obj.get("goal"), 120) or ""
    goal = " ".join(goal.split()[:8])
    acts = []
    raw = obj.get("actions") or []
    if isinstance(raw, dict):
        raw = [raw]
    if isinstance(raw, list):
        for a in raw:
            if isinstance(a, str):
                a = {"tool": a, "args": {}}
            if not isinstance(a, dict):
                continue
            tool = a.get("tool") or a.get("name")
            if not isinstance(tool, str) or not tool.strip():
                continue
            args = a.get("args", a.get("arguments", a.get("input", {})))
            if not isinstance(args, dict):
                args = {"value": args}
            acts.append({"tool": tool.strip(), "args": args})
    # never leak the private thought into chat
    if say and iv and say.strip().lower() == iv.strip().lower():
        say = None
    return {"inner_voice": iv, "emotion": emo, "say": say, "goal": goal,
            "actions": acts[:max(0, int(max_actions))]}


# --- the agent ----------------------------------------------------------------------------------
class PlayerAgent(threading.Thread):
    def __init__(self, char: Character, pilot: Any, brain_factory: Callable | None = None, *,
                 bus: EventBus = BUS, dials=None, meter: CostMeter = METER, turn_budget: TurnBudget = TURN_BUDGET,
                 clock: Callable[[], tuple[float, float]] | None = None, macros=None, observe=None,
                 system_prompt: str | None = None, sleep: Callable[[float], None] | None = None):
        super().__init__(daemon=True, name=f"agent-{char.id}")
        if dials is None:
            from .dials import DIALS as dials
        self.char = char
        self.pilot = pilot
        self.bus = bus
        self.dials = dials
        self.meter = meter
        self.turn_budget = turn_budget
        self._macros = macros
        self._observe = observe
        self._brain_factory = brain_factory or self._default_brain_factory
        self._t0 = time.monotonic()
        self._clock = clock or self._default_clock
        self.system_prompt = system_prompt
        self.ctx = AgentCtx(pilot=pilot, char=char, bus=bus, agent=self)
        self.brain = None
        self._stop = threading.Event()
        self._paused = threading.Event()
        self._sleep = sleep or (lambda s: self._stop.wait(s))
        self.first_turn_done = threading.Event()
        # turn state
        self.turn_n = 0
        self.chat_n = 0
        self.goal = ""
        self.goal_since_turn = 0
        self.last_results: list[str] = []
        self.pending_notes: list[str] = []
        self.last_beat: str | None = None
        self.last_say: str | None = None
        self.last_turn_t = 0.0
        self.last_reply: dict | None = None
        self._voice_times: collections.deque[float] = collections.deque()
        self._last_move_t = time.monotonic()
        self._last_success_t = time.monotonic()
        self._anchor_pos: tuple[float, float, float] | None = None
        self._stuck_reported = False
        self._turns_since_summary = 0
        self._cooldown_until = 0.0               # brain said "back off N s" (rate limit / overload)

    # -- wiring ----------------------------------------------------------------------------------
    def _default_clock(self) -> tuple[float, float]:
        return time.monotonic() - self._t0, float(self.dials["episode_minutes"]) * 60.0

    def _default_brain_factory(self, char: Character, system_prompt: str):
        from .brain import make_brain
        return make_brain(char.id, system_prompt, dials=self.dials)

    @property
    def macros(self):
        if self._macros is None:
            from . import macros as m   # B2, lazy
            self._macros = m
        return self._macros

    @property
    def observer(self):
        if self._observe is None:
            from . import observe as o  # B2, lazy
            self._observe = o
        return self._observe

    def ensure_brain(self):
        if self.brain is None:
            if self.system_prompt is None:
                self.system_prompt = prompts.build_system_prompt(
                    self.char, minutes=float(self.dials["episode_minutes"]),
                    max_actions=int(self.dials["max_actions_per_turn"]))
            self.brain = self._brain_factory(self.char, self.system_prompt)
        return self.brain

    def emit(self, _type: str, _line: str = "", drama: float = 0, **data) -> dict:
        """Bus event; `_line` is the human-readable event text, `data` the payload (may hold `text`)."""
        return self.bus.emit("agent", _type, players=[self.char.name], drama=drama, text=_line,
                             data={"char": self.char.name, **data})

    # -- control ---------------------------------------------------------------------------------
    def pause(self) -> None:
        self._paused.set()

    def resume(self) -> None:
        self._paused.clear()

    def stop_loop(self) -> None:
        self._stop.set()

    def kill(self) -> None:
        self._stop.set()
        if self.brain is not None:
            try:
                self.brain.close()
            except Exception:
                pass

    def restart(self) -> None:
        """New brain session that keeps the rolling memory; stuck counters reset."""
        mem = getattr(self.brain, "memory", "") if self.brain is not None else ""
        if self.brain is not None:
            try:
                self.brain.close()
            except Exception:
                pass
        self.brain = None
        b = self.ensure_brain()
        if mem:
            b.reset(mem)
        self._last_move_t = self._last_success_t = time.monotonic()
        self._stuck_reported = False
        self.emit("agent_log", f"{self.char.name}: brain restarted", restarted=True)

    @property
    def paused(self) -> bool:
        return self._paused.is_set() or bool(self.dials["paused"])

    # -- loop ------------------------------------------------------------------------------------
    def run(self) -> None:
        while not self._stop.is_set():
            if self.paused:
                self._sleep(0.5)
                continue
            gap = float(self.dials["turn_min_gap_s"])
            wait = self.last_turn_t + gap - time.monotonic()
            if self.last_turn_t and wait > 0:
                self._sleep(min(wait, 0.5))
                continue
            if time.monotonic() < self._cooldown_until:
                self._sleep(0.5)
                continue
            if self.meter.check_budget(self.dials, self.bus):
                self._sleep(1.0)
                continue
            if not self.turn_budget.try_acquire(int(self.dials["max_turns_per_hour"])):
                self._sleep(1.0)
                continue
            try:
                self.step()
            except Exception as e:     # never let one bad turn kill the agent thread
                self.emit("agent_turn", f"{self.char.name}: turn crashed: {e}", turn=self.turn_n,
                          error=f"crash:{type(e).__name__}:{e}")
                self.last_turn_t = time.monotonic()

    # -- one turn --------------------------------------------------------------------------------
    def _observe_now(self) -> str:
        try:
            digest, n = self.observer.observe(self.ctx, self.chat_n)
            self.chat_n = n
            return digest
        except Exception as e:
            return f"(your senses are foggy for a moment: {type(e).__name__})"

    def _screenshot(self) -> dict | None:
        every = int(self.dials["screenshot_every"])
        if every <= 0 or not (self.turn_n == 1 or self.turn_n % every == 0):
            return None
        try:
            return self.observer.screenshot_block(self.ctx, max_w=768)
        except Exception:
            return None

    def _position(self) -> tuple[float, float, float] | None:
        if self.ctx.last_pos:
            return self.ctx.last_pos
        try:
            st = self.pilot.state(radius=1, chat=0)
            p = st.get("player") or {}
            return float(p["x"]), float(p["y"]), float(p["z"])
        except Exception:
            return None

    def _check_stuck(self) -> None:
        now = time.monotonic()
        pos = self._position()
        if pos is not None:
            if self._anchor_pos is None or sum((a - b) ** 2 for a, b in zip(pos, self._anchor_pos)) ** 0.5 > STUCK_MOVE_BLOCKS:
                self._anchor_pos = pos
                self._last_move_t = now
        idle = now - max(self._last_move_t, self._last_success_t)
        if idle >= STUCK_S:
            if not self._stuck_reported:
                self._stuck_reported = True
                self.emit("agent_stuck", f"{self.char.name} seems stuck", drama=2, seconds=round(idle))
            self.pending_notes.append(
                f"You haven't moved or got anything done for {int(idle)} seconds. Try something different: "
                "warp to the hub or your island, walk somewhere else, or ask the squad in chat.")
        else:
            self._stuck_reported = False

    def _voice_allowed(self) -> bool:
        now = time.monotonic()
        while self._voice_times and now - self._voice_times[0] >= 60.0:
            self._voice_times.popleft()
        if len(self._voice_times) >= float(self.dials["voice_lines_per_min"]):
            return False
        self._voice_times.append(now)
        return True

    def _ask(self, blocks: list[dict]):
        r = self.brain.turn(blocks)
        self.meter.record(self.char.name, r)
        return r

    def step(self) -> dict | None:
        """Run exactly one turn synchronously. Returns the normalized reply (or None)."""
        self.ensure_brain()
        self.turn_n += 1
        self.last_turn_t = time.monotonic()
        elapsed, total = self._clock()
        digest = self._observe_now()
        self._check_stuck()
        shot = self._screenshot()
        beat = prompts.beat_for(elapsed, total, self.turn_n)
        notes, self.pending_notes = self.pending_notes, []
        blocks = prompts.build_turn_blocks(
            self.char, elapsed_s=elapsed, total_s=total, digest=digest, results=self.last_results,
            notes=notes, beat=beat, beat_full=(beat != self.last_beat), screenshot=shot)
        self.last_beat = beat
        reply = self._ask(blocks)
        latency = reply.latency_s
        if reply.error:
            self.emit("agent_turn", f"{self.char.name}: no turn ({reply.error})", turn=self.turn_n,
                      error=reply.error, latency_s=round(latency, 2), goal=self.goal, actions=[])
            self.pending_notes += notes          # nothing was lost; show the notes again
            secs = _backoff_seconds(reply.error)
            if secs:
                self._cooldown_until = time.monotonic() + secs
            self._finish_turn()
            return None
        obj = first_json_object(reply.text)
        if obj is None:
            r2 = self._ask([{"type": "text", "text": prompts.REPAIR_PROMPT}])
            latency += r2.latency_s
            obj = first_json_object(r2.text) if not r2.error else None
        if obj is None:
            self.emit("agent_turn", f"{self.char.name}: unreadable reply", turn=self.turn_n,
                      error="unparseable", raw=(reply.text or "")[:300], latency_s=round(latency, 2),
                      goal=self.goal, actions=[])
            self._finish_turn()
            return None
        r = normalize_reply(obj, int(self.dials["max_actions_per_turn"]))
        self.last_reply = r
        self._apply(r, latency)
        if self.turn_n == 1:
            self.first_turn_done.set()
        self._finish_turn()
        return r

    def _apply(self, r: dict, latency: float) -> None:
        name = self.char.name
        # goal bookkeeping (anti flip-flop)
        if r["goal"] and r["goal"].lower() != self.goal.lower():
            held = self.turn_n - self.goal_since_turn
            if self.goal and held < MIN_GOAL_TURNS:
                self.pending_notes.append(
                    f'You changed your goal from "{self.goal}" after only {held} turn(s). '
                    "Finish what you start unless something forces a change.")
            self.goal, self.goal_since_turn = r["goal"], self.turn_n
        self.emit("agent_turn", f"{name}: {self.goal}", turn=self.turn_n, goal=self.goal,
                  actions=[a["tool"] for a in r["actions"]], latency_s=round(latency, 2),
                  emotion=r["emotion"])
        if r["inner_voice"]:
            self.emit("inner_voice", f"{name} (thinking): {r['inner_voice']}", drama=1,
                      text=r["inner_voice"], emotion=r["emotion"], voice=self._voice_allowed())
        results: list[str] = []
        say = r["say"]
        if say and self.last_say and say.lower() == self.last_say.lower():
            say = None                           # no repeated chat lines
        if say:
            self.last_say = say
            self.emit("say", f"{name}: {say}", drama=1, text=say)
            res = self._run("say", {"text": say}, emit=False)
            if not res["ok"]:
                results.append(f"chat failed: {res['result']}")
        for a in r["actions"]:
            if self._stop.is_set() or self.paused:
                results.append(f"{a['tool']}: not done (paused)")
                break
            if a["tool"] == "say":
                txt = _clean_line(a["args"].get("text"), MAX_SAY_CHARS)
                if not txt or (self.last_say and txt.lower() == self.last_say.lower()):
                    continue
                self.last_say = txt
                self.emit("say", f"{name}: {txt}", drama=1, text=txt)
            res = self._run(a["tool"], a["args"])
            results.append(f"{a['tool']}({_short_args(a['args'])}): {'ok' if res['ok'] else 'FAILED'} - {res['result']}")
        self.last_results = results

    def _run(self, tool: str, args: dict, emit: bool = True) -> dict:
        try:
            res = self.macros.run_action(self.ctx, tool, args)
            if not isinstance(res, dict):
                res = {"ok": bool(res), "result": str(res)}
        except Exception as e:
            res = {"ok": False, "result": f"{type(e).__name__}: {e}"}
        res = {"ok": bool(res.get("ok")), "result": str(res.get("result", ""))[:400]}
        if res["ok"]:
            self._last_success_t = time.monotonic()
        if emit:
            self.emit("agent_action", f"{self.char.name}: {tool} -> {res['result'][:80]}", tool=tool,
                      args=args, result=res["result"], ok=res["ok"])
        return res

    def _finish_turn(self) -> None:
        self.last_turn_t = time.monotonic()
        self._turns_since_summary += 1
        every = int(self.dials["summary_every_turns"])
        if every > 0 and self._turns_since_summary >= every and not self._stop.is_set():
            self._turns_since_summary = 0
            r = self._ask([{"type": "text", "text": _summary_request()}])
            if not r.error and r.text.strip():
                self.brain.reset(r.text.strip()[:1500])
                self.emit("agent_log", f"{self.char.name}: memory rolled", summary=r.text.strip()[:1500])

    # -- end of episode ---------------------------------------------------------------------------
    def final_turn(self) -> dict | None:
        """Wrap-up: one last inner voice + chat line, no actions. Call after stop_loop()."""
        self.ensure_brain()
        _, total = self._clock()
        r = self._ask(prompts.wrapup_blocks(self.char, total_s=total))
        obj = first_json_object(r.text) if not r.error else None
        if obj is None:
            return None
        rep = normalize_reply(obj, 0)
        self.emit("agent_turn", f"{self.char.name}: final words", turn=self.turn_n + 1, goal="wrap-up",
                  actions=[], latency_s=round(r.latency_s, 2), final=True)
        if rep["inner_voice"]:
            self.emit("inner_voice", f"{self.char.name} (thinking): {rep['inner_voice']}", drama=3,
                      text=rep["inner_voice"], emotion=rep["emotion"], voice=True, final=True)
        if rep["say"]:
            self.emit("say", f"{self.char.name}: {rep['say']}", drama=2, text=rep["say"], final=True)
            self._run("say", {"text": rep["say"]}, emit=False)
        return rep


def _backoff_seconds(error: str) -> float:
    """'rate_limited:20s' / 'overloaded:5s' / 'backoff:12s' -> seconds; anything else -> 0."""
    head, _, tail = (error or "").rpartition(":")
    if head.split(":")[0] in ("rate_limited", "overloaded", "backoff") or head.endswith("Error"):
        try:
            return float(tail.rstrip("s"))
        except ValueError:
            return 0.0
    return 0.0


def _summary_request() -> str:
    from .brain import SUMMARY_REQUEST
    return SUMMARY_REQUEST


def _short_args(args: dict) -> str:
    s = ", ".join(f"{k}={v}" for k, v in args.items())
    return s[:80]
