"""The Director: decides what the audience looks at (build prompt §4.3, CONTRACT §11).

Two layers, and the first one is enough on its own:

1. **Rubric** — `score_event(ev)` is a PURE function (event -> score 0..10, reason, headline).
   Boss spawns, deaths, near-deaths, firsts, rare drops, skill milestones, two characters meeting
   for the first time, arguments in chat, plans going wrong, episode beats. `rubric_decide()` turns
   a scored moment into decisions. This is the default and the fallback whenever the LLM errs.
2. **LLM** — only for candidate moments at/above `director_prethreshold`, a Brain (B1) with
   `director_model` / `director_effort` picks HOLD / CUT_TO / CINEMATIC / NARRATE / REPLAY_MARK and
   replies with strict JSON, parsed defensively. It runs on a worker thread so a slow reply never
   blocks the feed; huge moments (score >= 9) never wait for it.

Whatever decides, the RULES are enforced here, in code, after the decision:
  * hysteresis — one cinematic per `cinematic_min_gap_s` unless the score is >= 9;
  * never cut (or return from a cut) while anyone's voice line is playing (tts_start/tts_end);
    the decision is held and executed when the line ends, or dropped if it went stale;
  * always return to the grid.

The Director talks to the screen only through a `stage` object (episode.Stage in production, a
recorder in tests): stage.grid(), stage.cut_to(char), stage.cinematic(target, shot, seconds,
lower_third), stage.narrate(line), stage.ticker(text, drama). It emits `director`,
`cinematic_start/end`, `replay_mark` and `meeting` events on the bus.
"""
from __future__ import annotations

import itertools
import json
import math
import queue
import re
import threading
import time
from dataclasses import dataclass, field, asdict
from typing import Any, Callable

from .dials import DIALS

DECISIONS = ("HOLD", "CUT_TO", "CINEMATIC", "NARRATE", "REPLAY_MARK")
SHOTS = ("orbit", "pushin", "follow", "crane", "dolly", "static")

RARITY_SCORE = {"COMMON": 1, "UNCOMMON": 2, "RARE": 4, "EPIC": 6, "LEGENDARY": 8,
                "MYTHIC": 9, "DIVINE": 9.5, "SPECIAL": 7, "VERY_SPECIAL": 8}
INSULTS = ("idiot", "stupid", "dumb", "trash", "noob", "shut up", "stfu", "wtf", "loser", "clown",
           "bozo", "heck", "stuff", "bitch", "moron", "cringe", "ratio", "scam", "liar", "coward",
           "useless", "pathetic", "garbage", "brain dead", "braindead", "dork", "nerd", "ass",
           "skill issue", "get good", "cope", "seethe", "rat", "thief", "you suck")
_INSULT_RE = re.compile(r"\b(" + "|".join(re.escape(w) for w in INSULTS) + r")\b", re.I)

SHOT_FOR = {"boss_spawn": "orbit", "death": "crane", "near_death": "pushin", "meeting": "orbit",
            "item_gain": "pushin", "first_of_kind": "crane", "big_hit": "pushin", "chat": "follow",
            "say": "follow", "mob_kill": "orbit", "fish_caught": "pushin", "agent_stuck": "static",
            "skill_level_up": "follow", "calendar_start": "crane"}


# =============================================================================== rubric
def _text_of(ev: dict) -> str:
    d = ev.get("data") or {}
    return str(d.get("message") or d.get("text") or ev.get("text") or "")


def _cast_names() -> list[str]:
    try:
        from .config import SHOW
        return [c.name for c in SHOW.characters]
    except Exception:
        return []


def argument_score(text: str, speaker: str = "", names: list[str] | None = None) -> tuple[float, str]:
    """Heuristic heat of a chat line: insults, shouting, naming someone, exclamation."""
    if not text:
        return 0.0, ""
    names = names if names is not None else _cast_names()
    score, why = 1.0, []
    insults = len(_INSULT_RE.findall(text))
    if insults:
        score += min(3.0, 1.5 * insults)
        why.append("insult")
    letters = [ch for ch in text if ch.isalpha()]
    if len(letters) >= 8 and sum(ch.isupper() for ch in letters) / len(letters) > 0.6:
        score += 1.5
        why.append("shouting")
    named = [n for n in names if n.lower() != speaker.lower() and re.search(rf"\b{re.escape(n)}\b", text, re.I)]
    if named:
        score += 1.0
        why.append("calls out " + "/".join(named))
    if text.count("!") >= 2 or text.count("?!"):
        score += 0.5
    if insults and named:
        score += 1.0
        why.append("roast")
    return min(7.0, score), ", ".join(why)


def score_event(ev: dict) -> tuple[float, str, str]:
    """PURE: event -> (score 0..10, reason, punchy headline)."""
    t = ev.get("type", "")
    d = ev.get("data") or {}
    who = ", ".join(ev.get("players") or []) or "someone"
    p0 = (ev.get("players") or ["someone"])[0]
    hint = float(ev.get("drama") or 0)
    s, reason, head = 0.0, "", ev.get("text") or t

    if t == "boss_spawn":
        s, reason, head = 9, "boss spawn", f"BOSS: {d.get('name') or d.get('mob_id') or 'something huge'}"
    elif t == "mob_kill" and d.get("boss"):
        s, reason, head = 8, "boss kill", f"{p0} kills {d.get('mob_name', 'the boss')}"
    elif t == "mob_kill":
        s, reason, head = 1, "kill", f"{p0} kills a {d.get('mob_name', 'mob')}"
    elif t == "death":
        s, reason, head = 7, "death", f"{p0} DIES" + (f" to {d['cause']}" if d.get("cause") else "")
    elif t == "near_death":
        s, reason, head = 6, "near death", f"{p0} almost dies"
    elif t == "first_of_kind":
        what = str(d.get("what") or ev.get("text") or "a first")
        s, reason, head = 6, "first of its kind", f"{p0}: {what}"
        if "hub" in what.lower() or "death" in what.lower():
            s = 7
    elif t == "item_gain":
        rar = str(d.get("rarity", "")).upper()
        s = RARITY_SCORE.get(rar, 3)
        reason, head = f"{rar.lower() or 'new'} drop", f"{p0} gets {('a ' + rar + ' ') if rar else ''}{d.get('item_id', 'item')}"
    elif t == "skill_level_up":
        lvl = int(d.get("level") or 0)
        s = 5 if lvl and lvl % 5 == 0 else 3
        reason, head = "skill milestone" if s == 5 else "skill level", f"{p0} hits {str(d.get('skill', 'skill')).title()} {lvl}"
    elif t == "collection_tier_up":
        s, reason, head = 2, "collection", f"{p0}: {d.get('collection', 'collection')} tier {d.get('tier', '?')}"
    elif t == "coins_milestone":
        s, reason, head = 3, "coins", f"{p0} reaches {d.get('purse', '?')} coins"
    elif t == "meeting":
        allfour = bool(d.get("all")) or len(ev.get("players") or []) >= 4
        s = 8 if allfour else 7
        reason = "all four together" if allfour else "first meeting"
        head = "THE SQUAD ASSEMBLES" if allfour else f"{' meets '.join((ev.get('players') or ['?', '?'])[:2])}"
    elif t in ("chat", "say"):
        s, why = argument_score(_text_of(ev), p0)
        reason = f"chat ({why})" if why else "chat"
        head = f"{p0}: \"{_text_of(ev)[:60]}\""
    elif t == "agent_stuck":
        secs = float(d.get("seconds") or 0)
        s = 5 if secs >= 60 else 3
        reason, head = "plan going wrong", f"{p0} is stuck"
    elif t == "island_change":
        to = str(d.get("to", ""))
        s = 4 if "hub" in to.lower() else 2
        reason, head = "travel", f"{p0} arrives at {to or 'a new island'}"
    elif t == "big_hit":
        s, reason, head = 4, "big hit", f"{p0} takes {d.get('damage', 'a huge')} damage"
    elif t == "fish_caught":
        s = 5 if d.get("treasure") else 4 if d.get("sea_creature") else 1
        reason, head = "fishing", f"{p0} reels in {d.get('what', 'something')}"
    elif t == "calendar_start":
        s, reason, head = 4, "event", f"{d.get('event', 'An event')} begins"
    elif t == "ability_used":
        s, reason, head = 2, "ability", f"{p0} uses {d.get('ability', 'an ability')}"
    elif t in ("episode_start", "episode_end"):
        s, reason = 5, "episode beat"
        head = "It begins" if t == "episode_start" else "Time's up"
    elif t in ("join", "profile_ready"):
        s, reason, head = 3, "arrival", f"{p0} wakes up"
    elif t == "mark":
        s, reason, head = 6, "manual mark", str(d.get("label") or ev.get("text") or "Marked moment")
    elif t == "replay_mark":
        s, reason, head = 0, "", str(d.get("label") or "replay")
    elif t == "gather_summary":
        s, reason, head = 1, "grind", ev.get("text") or f"{p0} gathers"
    elif t == "agent_action" and not d.get("ok", True):
        s, reason, head = 2, "action failed", f"{p0}'s plan stumbles"

    s = max(s, hint)
    if len(ev.get("players") or []) >= 2 and t not in ("meeting",):
        s += 0.5
    return max(0.0, min(10.0, float(s))), reason or t, head


# =============================================================================== decisions
@dataclass
class Decision:
    kind: str
    target: Any = None
    shot: str = ""
    seconds: float = 0.0
    line: str = ""
    start: float = 0.0          # epoch seconds (REPLAY_MARK)
    end: float = 0.0
    label: str = ""
    score: float = 0.0
    reason: str = ""
    by: str = "rubric"
    created: float = 0.0
    event_id: Any = None

    def public(self) -> dict:
        d = {k: v for k, v in asdict(self).items() if v not in (None, "", 0, 0.0) or k == "score"}
        d.pop("created", None)
        d["decision"] = d.pop("kind")
        return d


NARRATION = {
    "boss_spawn": ["And then... the sky answered.", "Something old just woke up. And it is hungry."],
    "death": ["Pride comes before the fall. So does {p}.", "{p} learned a lesson today. The hard way."],
    "meeting": ["Two strangers. One island. This could only go well.", "Fate has a sense of humour."],
    "meeting_all": ["Four minds. No memories. One team. ...Probably.", "The squad, at last, assembles."],
    "first_of_kind": ["History, of a sort, was made.", "First. Remember that name: {p}."],
    "near_death": ["One heartbeat from the end.", "So close to the void, {p} could hear it."],
}


def _pick(lines: list[str], seed: int, **kw) -> str:
    return lines[seed % len(lines)].format(**kw)


def rubric_decide(ev: dict, score: float, reason: str, headline: str, *, now: float,
                  dials: Any = DIALS) -> list[Decision]:
    """Deterministic fallback: scored moment -> decisions (rules are applied later by Director)."""
    t = ev.get("type", "")
    players = ev.get("players") or []
    target = players[0] if players else (ev.get("loc") or None)
    out: list[Decision] = []
    if t == "mark":      # a human pressed "mark": keep the moment for the edit, don't touch the screen
        return [Decision("REPLAY_MARK", start=now - 30, end=now + 5, label=headline, score=score, reason=reason)]
    cine_th = float(dials.get("cinematic_threshold", 6.0))
    cut_th = float(dials.get("director_cut_threshold", 5.0))
    if score >= cine_th and target is not None:
        secs = 12.0 if score >= 9 else 9.0
        out.append(Decision("CINEMATIC", target=target, shot=SHOT_FOR.get(t, "orbit"), seconds=secs,
                            label=headline, score=score, reason=reason))
    elif score >= cut_th and players:
        out.append(Decision("CUT_TO", target=players[0], seconds=float(dials.get("director_cut_seconds", 8.0)),
                            label=headline, score=score, reason=reason))
    if score >= 7:
        out.append(Decision("REPLAY_MARK", start=now - 20, end=now + 10, label=headline, score=score,
                            reason=reason))
    key = "meeting_all" if t == "meeting" and (len(players) >= 4 or (ev.get("data") or {}).get("all")) else t
    if score >= 7 and key in NARRATION:
        seed = int(ev.get("bus_id") or ev.get("id") or 0)
        out.append(Decision("NARRATE", line=_pick(NARRATION[key], seed, p=players[0] if players else "someone"),
                            score=score, reason=reason))
    return out


# =============================================================================== LLM layer
DIRECTOR_SYSTEM = """You are the DIRECTOR of a live-recorded show: "Can 4 AIs Beat Hypixel SkyBlock?".
Four AI characters play a SkyBlock game; the screen normally shows all four in a 2x2 grid.
You decide, for ONE candidate moment, what the audience should see. Keep it watchable:
cut rarely and with purpose; cinematics are special; narration is RARE (a deep anime-trailer
narrator, one short line, dry and funny, swearing allowed but not needed).

Reply with ONE JSON object and nothing else:
{"decision": "HOLD|CUT_TO|CINEMATIC|NARRATE|REPLAY_MARK",
 "target": "<character name>" (CUT_TO/CINEMATIC),
 "shot": "orbit|pushin|follow|crane|dolly|static" (CINEMATIC),
 "seconds": 6-15 (CINEMATIC),
 "line": "<narrator line, <= 14 words>" (NARRATE),
 "start_s_ago": 20, "end_s_after": 10, "label": "<short punchy label>" (REPLAY_MARK),
 "also_mark": true|false,
 "reason": "<= 12 words"}
HOLD means stay on the grid. If a cinematic happened recently the code will refuse it anyway."""


def parse_json_reply(text: str) -> dict | None:
    """Strip fences/prose, return the first balanced {...} object that parses, else None."""
    if not text:
        return None
    s = text.strip()
    s = re.sub(r"^```(?:json)?\s*|\s*```$", "", s, flags=re.I | re.M)
    start = s.find("{")
    while start != -1:
        depth, in_str, esc = 0, False, False
        for i in range(start, len(s)):
            ch = s[i]
            if in_str:
                if esc:
                    esc = False
                elif ch == "\\":
                    esc = True
                elif ch == '"':
                    in_str = False
            elif ch == '"':
                in_str = True
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    try:
                        obj = json.loads(s[start:i + 1])
                        if isinstance(obj, dict):
                            return obj
                    except ValueError:
                        pass
                    break
        start = s.find("{", start + 1)
    return None


def decision_from_llm(obj: dict | None, ev: dict, score: float, headline: str, now: float,
                      names: list[str]) -> list[Decision] | None:
    """Validate an LLM reply into decisions; None if unusable (caller falls back to the rubric)."""
    if not obj:
        return None
    kind = str(obj.get("decision", "")).strip().upper().replace("-", "_").replace(" ", "_")
    if kind not in DECISIONS:
        return None
    reason = str(obj.get("reason", ""))[:120]
    players = ev.get("players") or []

    def norm_target(x: Any) -> str | None:
        if isinstance(x, str):
            for n in names:
                if n.lower() == x.strip().lower():
                    return n
        return players[0] if players else None

    out: list[Decision] = []
    if kind == "CINEMATIC":
        tgt = norm_target(obj.get("target"))
        if tgt is None:
            return None
        shot = str(obj.get("shot", "orbit")).lower()
        try:
            secs = float(obj.get("seconds", 9))
        except (TypeError, ValueError):
            secs = 9.0
        out.append(Decision("CINEMATIC", target=tgt, shot=shot if shot in SHOTS else "orbit",
                            seconds=max(4.0, min(20.0, secs)), label=headline, score=score, reason=reason, by="llm"))
    elif kind == "CUT_TO":
        tgt = norm_target(obj.get("target"))
        if tgt is None:
            return None
        out.append(Decision("CUT_TO", target=tgt, seconds=8.0, label=headline, score=score, reason=reason, by="llm"))
    elif kind == "NARRATE":
        line = str(obj.get("line", "")).strip().strip('"')
        if not line:
            return None
        out.append(Decision("NARRATE", line=" ".join(line.split()[:20]), score=score, reason=reason, by="llm"))
    elif kind == "REPLAY_MARK":
        pass
    else:
        out.append(Decision("HOLD", score=score, reason=reason, by="llm"))
    if kind == "REPLAY_MARK" or obj.get("also_mark"):
        try:
            a = float(obj.get("start_s_ago", 20))
            b = float(obj.get("end_s_after", 10))
        except (TypeError, ValueError):
            a, b = 20.0, 10.0
        out.append(Decision("REPLAY_MARK", start=now - max(0.0, min(120.0, a)), end=now + max(0.0, min(60.0, b)),
                            label=str(obj.get("label") or headline)[:80], score=score, reason=reason, by="llm"))
    return out


# =============================================================================== the director
class Director:
    def __init__(self, bus: Any, stage: Any, *, brain: Any = None,
                 positions: Callable[[], dict] | None = None,
                 look: Callable[[str], str | None] | None = None,
                 clock: Callable[[], float] = time.time, dials: Any = DIALS,
                 names: list[str] | None = None, async_llm: bool = True,
                 voice_busy: Callable[[], bool] | None = None):
        self.bus = bus
        self.stage = stage
        self.brain = brain
        self.positions = positions
        self.look = look
        self.clock = clock
        self.dials = dials
        self.names = names if names is not None else _cast_names()
        self.async_llm = async_llm
        self.voice_busy = voice_busy        # authoritative TTS state (bridge.tts.busy) on top of the events
        # state
        self.mode = "grid"                  # grid | cut | cinematic
        self.mode_target: Any = None
        self.return_at = 0.0
        self.last_cinematic_end = -1e12
        self.last_cut = -1e12
        self.last_narrate = -1e12
        self.voicing: dict[str, float] = {}  # char -> expiry (epoch s)
        self.pending: Decision | None = None
        self.met: set[frozenset] = set()
        self.all_met = False
        self.history: list[dict] = []       # scored moments (for the LLM context)
        self.decisions: list[Decision] = [] # executed
        self.frames: dict[str, str] = {}
        self._next_pos = 0.0
        self._next_look = 0.0
        self._look_cycle = 0
        self._llm_busy = False
        self._batching = False
        self._inbox: queue.Queue = queue.Queue()
        self._q = None
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()
        self._lock = threading.RLock()

    # ------------------------------------------------------------------ plumbing
    def _emit(self, type: str, *, players=None, drama: float = 0, text: str = "", data=None) -> None:
        try:
            self.bus.emit("director", type, players=players or [], drama=drama, text=text, data=data or {},
                          t=int(self.clock() * 1000))
        except Exception:
            pass

    def start(self, threaded: bool = True) -> None:
        """Subscribe to the bus. threaded=False: the caller drives it with pump() (accelerated mock)."""
        self._q = self.bus.subscribe()
        if threaded:
            self._thread = threading.Thread(target=self._run, daemon=True, name="Director")
            self._thread.start()

    def pump(self, max_events: int = 1000) -> int:
        """Process queued bus events then tick, synchronously. Returns events handled."""
        batch = []
        while self._q is not None and len(batch) < max_events:
            try:
                batch.append(self._q.get_nowait())
            except queue.Empty:
                break
        # voice events first: a line that started in this batch must block a cut decided in it
        batch.sort(key=lambda e: 0 if e.get("type") in ("tts_start", "tts_end") else 1)
        self._batching = True
        try:
            for ev in batch:
                try:
                    self.handle(ev)
                except Exception:
                    pass
        finally:
            self._batching = False
        self.tick()
        return len(batch)

    def stop(self) -> None:
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=3)
        if self._q is not None:
            self.bus.unsubscribe(self._q)

    def _run(self) -> None:
        while not self._stop.is_set():
            try:
                ev = self._q.get(timeout=0.2)
                self.handle(ev)
            except queue.Empty:
                pass
            except Exception:
                pass
            try:
                self.tick()
            except Exception:
                pass

    # ------------------------------------------------------------------ voice lock
    def voice_active(self, now: float | None = None) -> bool:
        now = self.clock() if now is None else now
        if self.voice_busy is not None:
            try:
                if self.voice_busy():
                    return True
            except Exception:
                pass
        with self._lock:
            for k in [k for k, exp in self.voicing.items() if exp <= now]:
                self.voicing.pop(k, None)
            return bool(self.voicing)

    # ------------------------------------------------------------------ input
    def handle(self, ev: dict, now: float | None = None) -> float:
        """Feed one bus event. Returns its rubric score."""
        now = self.clock() if now is None else now
        t, d = ev.get("type"), ev.get("data") or {}
        if t == "tts_start":
            ch = str(d.get("char") or (ev.get("players") or ["?"])[0])
            dur = float(d.get("duration_ms") or 10000) / 1000.0
            with self._lock:
                self.voicing[ch] = now + dur + 0.5
            return 0.0
        if t == "tts_end":
            ch = str(d.get("char") or (ev.get("players") or ["?"])[0])
            with self._lock:
                self.voicing.pop(ch, None)
            # act on a held decision right away - unless more events are waiting (the next line may
            # already have started: cutting in the gap between two lines is still cutting mid-speech)
            if not self._batching and (self._q is None or self._q.empty()):
                self.tick(now)
            return 0.0
        if ev.get("src") == "director" and t != "meeting":
            return 0.0
        if t in ("agent_turn", "inner_voice", "cost", "scene", "narrate", "tts", "status", "clock"):
            return 0.0
        score, reason, head = score_event(ev)
        if score >= 4 and t not in ("chat", "say") and ev.get("src") != "server":   # the bridge tickers server events
            try:
                self.stage.ticker(head, score)
            except Exception:
                pass
        if score >= float(self.dials.get("director_prethreshold", 4.0)):
            with self._lock:
                self.history.append({"t": now, "type": t, "players": ev.get("players") or [],
                                     "score": round(score, 1), "headline": head})
                self.history = self.history[-40:]
            self.consider(ev, score, reason, head, now)
        return score

    def consider(self, ev: dict, score: float, reason: str, head: str, now: float) -> None:
        use_llm = (self.brain is not None and bool(self.dials.get("director_llm", True)) and score < 9)
        if use_llm and not self._llm_busy:
            self._llm_busy = True
            if self.async_llm:
                threading.Thread(target=self._llm_job, args=(ev, score, reason, head, now), daemon=True,
                                 name="DirectorLLM").start()
            else:
                self._llm_job(ev, score, reason, head, now)
            return
        for dec in rubric_decide(ev, score, reason, head, now=now, dials=self.dials):
            dec.created, dec.event_id = now, ev.get("bus_id") or ev.get("id")
            self.apply(dec, now)

    # ------------------------------------------------------------------ LLM
    def _llm_prompt(self, ev: dict, score: float, head: str, now: float) -> str:
        recent = [f"{int(now - h['t'])}s ago: [{h['score']}] {h['headline']}" for h in self.history[-12:]]
        state = {
            "screen": self.mode, "screen_target": self.mode_target if isinstance(self.mode_target, str) else None,
            "seconds_since_last_cinematic": int(min(9999, now - self.last_cinematic_end)),
            "cinematic_min_gap_s": float(self.dials.get("cinematic_min_gap_s", 120)),
            "seconds_since_last_narration": int(min(9999, now - self.last_narrate)),
            "someone_is_speaking": self.voice_active(now),
            "characters": self.names,
        }
        cand = {"type": ev.get("type"), "players": ev.get("players"), "text": ev.get("text"),
                "data": ev.get("data"), "rubric_score": round(score, 1), "headline": head}
        return ("CANDIDATE MOMENT:\n" + json.dumps(cand, ensure_ascii=False) +
                "\n\nSTATE:\n" + json.dumps(state) +
                "\n\nRECENT MOMENTS:\n" + ("\n".join(recent) or "(none)") +
                "\n\nDecide. JSON only.")

    def _llm_job(self, ev: dict, score: float, reason: str, head: str, now: float) -> None:
        decs = None
        err = None
        try:
            blocks: list[dict] = [{"type": "text", "text": self._llm_prompt(ev, score, head, now)}]
            tgt = (ev.get("players") or [None])[0]
            img = self.frames.get(tgt) if tgt else None
            if img and score >= 7:
                b = _image_block(img)
                if b:
                    blocks.append(b)
            reply = self.brain.turn(blocks)
            err = getattr(reply, "error", None)
            if not err:
                decs = decision_from_llm(parse_json_reply(getattr(reply, "text", "")), ev, score, head, now,
                                         self.names)
        except Exception as e:
            err = str(e)
        self._inbox.put((ev, score, reason, head, now, decs, err))
        if not self.async_llm:
            self._drain_inbox(self.clock())

    def _drain_inbox(self, now: float) -> None:
        while True:
            try:
                ev, score, reason, head, t0, decs, err = self._inbox.get_nowait()
            except queue.Empty:
                return
            self._llm_busy = False
            if decs is None:
                decs = rubric_decide(ev, score, reason, head, now=t0, dials=self.dials)
                for d in decs:
                    d.by = "rubric-fallback" + (f" ({err[:60]})" if err else "")
            if now - t0 > float(self.dials.get("director_stale_s", 12.0)):
                decs = [d for d in decs if d.kind in ("REPLAY_MARK", "NARRATE", "HOLD")]
            for d in decs:
                d.created, d.event_id = t0, ev.get("bus_id") or ev.get("id")
                self.apply(d, now)

    # ------------------------------------------------------------------ rules + execution
    def apply(self, dec: Decision, now: float | None = None) -> bool:
        """Enforce the rules, then execute (or defer). Returns True if executed now."""
        now = self.clock() if now is None else now
        dec.created = dec.created or now
        k = dec.kind
        if k == "HOLD":
            self._emit("director", drama=dec.score, text="HOLD", data=dec.public())
            return True
        if k == "REPLAY_MARK":
            self._emit("replay_mark", drama=dec.score, text=dec.label,
                       data={"start": dec.start, "end": dec.end, "label": dec.label, "score": dec.score})
            self._emit("director", drama=dec.score, text=f"REPLAY_MARK {dec.label}", data=dec.public())
            self.decisions.append(dec)
            return True
        if k == "NARRATE":
            if now - self.last_narrate < float(self.dials.get("narrate_min_gap_s", 90.0)):
                return False
            self.last_narrate = now
            self._emit("director", drama=dec.score, text=f"NARRATE {dec.line}", data=dec.public())
            try:
                self.stage.narrate(dec.line)
            except Exception:
                pass
            self.decisions.append(dec)
            return True
        if k == "CINEMATIC":
            huge = dec.score >= 9
            gap_ok = (now - self.last_cinematic_end) >= float(self.dials.get("cinematic_min_gap_s", 120.0))
            if self.mode == "cinematic" or not (gap_ok or huge):
                # downgrade: a cut still shows the moment without spending the cinematic budget
                if self.mode == "grid" and dec.score >= float(self.dials.get("director_cut_threshold", 5.0)) \
                        and isinstance(dec.target, str):
                    dec = Decision("CUT_TO", target=dec.target, seconds=float(self.dials.get("director_cut_seconds", 8.0)),
                                   label=dec.label, score=dec.score, reason=dec.reason + " (cinematic on cooldown)",
                                   by=dec.by, created=dec.created, event_id=dec.event_id)
                    return self.apply(dec, now)
                return False
        if k == "CUT_TO":
            if self.mode != "grid":
                return False
            if now - self.last_cut < float(self.dials.get("director_cut_min_gap_s", 25.0)):
                return False
        if k in ("CINEMATIC", "CUT_TO"):
            if self.voice_active(now):
                with self._lock:
                    if self.pending is None or dec.score >= self.pending.score:
                        self.pending = dec
                return False
            self._execute_visual(dec, now)
            return True
        return False

    def _execute_visual(self, dec: Decision, now: float) -> None:
        self._emit("director", players=[dec.target] if isinstance(dec.target, str) else [], drama=dec.score,
                   text=f"{dec.kind} {dec.target if isinstance(dec.target, str) else ''} {dec.shot}".strip(),
                   data=dec.public())
        if dec.kind == "CINEMATIC":
            try:
                self.stage.cinematic(dec.target, dec.shot, dec.seconds, dec.label)
            except Exception:
                pass
            self.mode, self.mode_target = "cinematic", dec.target
            self.return_at = now + dec.seconds
            self._emit("cinematic_start", players=[dec.target] if isinstance(dec.target, str) else [],
                       drama=dec.score, text=dec.label, data={"target": dec.target, "shot": dec.shot,
                                                              "seconds": dec.seconds})
        else:
            try:
                self.stage.cut_to(dec.target)
            except Exception:
                pass
            self.mode, self.mode_target = "cut", dec.target
            self.last_cut = now
            self.return_at = now + (dec.seconds or 8.0)
        self.decisions.append(dec)

    def return_to_grid(self, now: float | None = None, force: bool = False) -> bool:
        now = self.clock() if now is None else now
        if self.mode == "grid":
            return True
        if not force and self.voice_active(now) and now < self.return_at + float(self.dials.get("director_return_grace_s", 6.0)):
            return False
        was = self.mode
        try:
            self.stage.grid()
        except Exception:
            pass
        if was == "cinematic":
            self.last_cinematic_end = now
            self._emit("cinematic_end", text="back to grid", data={"target": self.mode_target})
        self.mode, self.mode_target = "grid", None
        return True

    def force(self, dec: Decision) -> bool:
        """Dashboard override (cinematic/grid buttons): bypasses hysteresis, still respects voice."""
        now = self.clock()
        dec.created = now
        if dec.kind == "HOLD":
            return self.return_to_grid(now, force=True)
        if dec.kind in ("CINEMATIC", "CUT_TO"):
            if self.mode != "grid":
                self.return_to_grid(now, force=True)
            if self.voice_active(now):
                self.pending = dec
                return False
            self._execute_visual(dec, now)
            return True
        return self.apply(dec, now)

    # ------------------------------------------------------------------ clock-driven
    def tick(self, now: float | None = None) -> None:
        now = self.clock() if now is None else now
        self._drain_inbox(now)
        if self.mode != "grid" and now >= self.return_at:
            self.return_to_grid(now)
        if self.pending is not None and self.mode == "grid" and not self.voice_active(now):
            dec, self.pending = self.pending, None
            stale = float(self.dials.get("director_stale_s", 12.0)) * (2.5 if dec.score >= 9 else 1.0)
            if now - dec.created <= stale:
                self.apply(dec, now)
        if self.positions and now >= self._next_pos:
            self._next_pos = now + float(self.dials.get("director_meeting_poll_s", 5.0))
            try:
                self.check_meetings(self.positions(), now)
            except Exception:
                pass
        if self.look and now >= self._next_look:
            self._next_look = now + float(self.dials.get("director_interval_s", 20.0))
            self._look_cycle += 1
            if self._look_cycle % max(1, int(self.dials.get("director_look_every", 3))) == 0:
                threading.Thread(target=self._grab_frames, daemon=True, name="DirectorLook").start()

    def _grab_frames(self) -> None:
        for n in self.names:
            try:
                p = self.look(n)
                if p:
                    self.frames[n] = p
            except Exception:
                pass

    def check_meetings(self, pos: dict, now: float) -> list[frozenset]:
        """pos: {char: {"island": str, "x","y","z"}}. Pairs within 10 blocks on the same island meet
        for the first time. Pairs that meet in the same check are ONE event (a group walking up is
        one moment, not three), and the first time all four stand within 16 blocks of each other is
        its own, bigger event (`data.all`)."""
        new: list[frozenset] = []
        dists: dict[frozenset, float] = {}
        names = [n for n, p in pos.items() if p and all(k in p for k in ("x", "y", "z"))]
        # every player's private island can share one name AND one coordinate frame (one instance
        # slot per profile), so standing on "the same" private island is never a meeting
        pos = {n: dict(p, island=(f"{p.get('island')}#{n}" if "private" in str(p.get("island", "")).lower()
                                  or str(p.get("island", "")).lower() in ("home", "island") else p.get("island")))
               for n, p in pos.items() if n in names}
        for a, b in itertools.combinations(sorted(names), 2):
            pa, pb = pos[a], pos[b]
            if (pa.get("island") or "") != (pb.get("island") or ""):
                continue
            dist = math.dist((pa["x"], pa["y"], pa["z"]), (pb["x"], pb["y"], pb["z"]))
            key = frozenset((a, b))
            if dist <= 10 and key not in self.met:
                self.met.add(key)
                new.append(key)
                dists[key] = round(dist, 1)
        all_now = False
        if not self.all_met and len(names) >= 4:
            islands = {pos[n].get("island") for n in names}
            all_now = len(islands) == 1 and all(
                math.dist((pos[a]["x"], pos[a]["y"], pos[a]["z"]), (pos[b]["x"], pos[b]["y"], pos[b]["z"])) <= 16
                for a, b in itertools.combinations(names, 2))
        if all_now:
            self.all_met = True
            self.met.update(frozenset(p) for p in itertools.combinations(sorted(names), 2))
            self._emit("meeting", players=sorted(names), drama=8, text="All four are together for the first time",
                       data={"all": True, "island": pos[names[0]].get("island"),
                             "pairs": [sorted(k) for k in new]})
        elif new:
            who = sorted({n for k in new for n in k})
            pairs = [sorted(k) for k in new]
            if len(new) == 1:
                a, b = pairs[0]
                text = f"{a} meets {b} for the first time"
            else:
                text = f"{', '.join(who[:-1])} and {who[-1]} meet for the first time"
            island = pos[pairs[0][0]].get("island")
            self._emit("meeting", players=who, drama=7, text=text,
                       data={"a": pairs[0][0], "b": pairs[0][1], "pairs": pairs, "island": island,
                             "dist": min(dists.values())})
        return new


def _image_block(path: str, max_bytes: int = 3_500_000) -> dict | None:
    import base64
    import os
    try:
        if os.path.getsize(path) > max_bytes:
            return None
        with open(path, "rb") as f:
            data = base64.b64encode(f.read()).decode()
    except OSError:
        return None
    mt = "image/png" if path.lower().endswith(".png") else "image/jpeg"
    return {"type": "image", "source": {"type": "base64", "media_type": mt, "data": data}}


def positions_from_showcontrol(characters: list, showcontrol: Callable | None = None) -> Callable[[], dict]:
    """Default positions provider: ShowControl /state/<account> for each character."""
    if showcontrol is None:
        from .events import showcontrol as _sc
        showcontrol = _sc

    def get() -> dict:
        out = {}
        for c in characters:
            try:
                st = showcontrol(f"/state/{c.account}", None, 2.0)
            except Exception:
                continue
            p = st.get("pos") or {}
            if st.get("online", True) and all(k in p for k in ("x", "y", "z")):
                out[c.name] = {"island": st.get("island"), "x": p["x"], "y": p["y"], "z": p["z"]}
        return out
    return get
