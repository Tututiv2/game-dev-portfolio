"""One episode, start to finish (CONTRACT §13, DECISIONS #5).

    py -3 run.py episode                     # the real thing (preflight first)
    py -3 run.py episode --mock              # no Minecraft, no OBS, no model calls: whole pipeline
    py -3 run.py episode --mock --brain real # mock world, real `claude -p` agents (short smoke only)

Flow
  0. episode folder + events.jsonl; dashboard/overlay server + overlay bridge (voices, captions);
     dashboard control hook (pause / resume / stop / cinematic / grid / mark / kill / restart agent)
  1. `/show reset` + ruleset; the five clients are launched BEFORE recording (a client takes a minute
     or two to load, and the intro needs ~25 s beats) and the world is frozen
  2. OBS: scenes built idempotently, ambient audio muted, recording into the episode folder,
     `recording.json` written with the instant the file starts (the edit's 0:00)
  3. SLOW INTRO: title card (camera client behind it) -> one character at a time: their POV full
     screen + lower third (name / archetype) while their first "where am I?" turn plays and is voiced,
     then the camera client orbits their island if ShowCam is up; the others hold still meanwhile
     -> all four on the 2x2 grid for a beat
  4. play: agents run (their prompts nudge: find the hub, meet the others), the Director cuts
  5. wrap at `episode_minutes`: `/show stop`, each says a last line, record stop, outputs
     (chapters, best moments, EDL, shorts, transcript, cost) via show.edit
Everything is restored in `finally`: OBS record directory + mutes, freeze off, feeds stopped.
"""
from __future__ import annotations

import itertools
import json
import math
import random
import re
import threading
import time
import traceback
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path
from typing import Any, Callable

from .config import SHOW, AGENT_HOME, Character
from .dials import DIALS
from .events import BUS, EventBus, ServerFeed, showcontrol, now_ms

TITLE = "CAN 4 AIs BEAT HYPIXEL SKYBLOCK?"


# =============================================================================== clocks / dials
class EpisodeClock:
    """Epoch-seconds clock that can run faster than real time (mock only)."""

    def __init__(self, speed: float = 1.0, real: Callable[[], float] = time.time,
                 sleeper: Callable[[float], None] = time.sleep):
        self.speed = max(0.001, float(speed))
        self._real = real
        self._sleep = sleeper
        self.t0 = real()

    def now(self) -> float:
        return self.t0 + (self._real() - self.t0) * self.speed

    def ms(self) -> int:
        return int(self.now() * 1000)

    def sleep(self, sim_s: float) -> None:
        if sim_s > 0:
            self._sleep(sim_s / self.speed)


class DialsView:
    """Read-through view of DIALS with local overrides (never persisted)."""

    def __init__(self, base: Any = DIALS, **over: Any):
        self.base, self.over = base, dict(over)

    def __getitem__(self, k: str) -> Any:
        return self.over[k] if k in self.over else self.base[k]

    def get(self, k: str, d: Any = None) -> Any:
        return self.over[k] if k in self.over else self.base.get(k, d)

    def set(self, k: str, v: Any) -> Any:
        if k in self.over:
            self.over[k] = v
            return v
        return self.base.set(k, v)

    def on_change(self, fn) -> None:
        if hasattr(self.base, "on_change"):
            self.base.on_change(fn)


def _reply(text: str, latency: float = 0.05):
    try:
        from .brain import Reply
        return Reply(text, {"input_tokens": 0, "output_tokens": 0}, 0.0, latency, None)
    except Exception:
        from types import SimpleNamespace
        return SimpleNamespace(text=text, usage={}, cost_usd=0.0, latency_s=latency, error=None)


# =============================================================================== fake brains
LINES: dict[str, dict[str, list]] = {
    "kaito": {
        "wake": [("...Where the hell am I? Why is there an island floating in the sky?!", "confused", "HELLO?? ANYONE??")],
        "play": [("I can't lose here. Not to a zombie. Not to anyone.", "determined", None),
                 ("This sword is garbage but so is everything I'm hitting.", "hype", "COME ON THEN"),
                 ("Ren's going to lecture me again. Whatever.", "angry", "REN STOP BEING A NERD AND FIGHT"),
                 ("Mochi just tried to sell me my own sword...", "angry", "MOCHI YOU RAT")],
        "end": [("Not bad. Next time I'm the strongest, no question.", "determined", "GG. I CARRIED.")],
    },
    "ren": {
        "wake": [("Unfamiliar sky. Island of roughly forty blocks. Inventory: almost nothing. Fine.", "calm", None)],
        "play": [("If I get a minion down now, it compounds while the others flail.", "calm", "minions first. trust me."),
                 ("Kaito charged in again. Exactly as I predicted.", "smug", "kaito that's a level 10 mob. you are level 2."),
                 ("Mochi's 'deal' is a 40 percent markup. Cute.", "smug", "mochi no.")],
        "end": [("The plan held. Mostly. Kaito survived, which I did not budget for.", "calm", "acceptable run.")],
    },
    "mochi": {
        "wake": [("Hehehe... a whole island and nobody to sell it to. Yet.", "smug", "yo who owns this place lmaooo")],
        "play": [("Coins coins coins. They have no idea what this is worth.", "happy", "selling wheat 5 coins each, friends price"),
                 ("Why is it attacking ME, I'm a businessman!", "scared", "KAITO HELP ME BRO"),
                 ("Yuki looked at me. I'm sweating.", "scared", "yuki you look amazing today")],
        "end": [("Richest one here. Obviously.", "smug", "gg everyone, invoices coming tomorrow")],
    },
    "yuki": {
        "wake": [("Quiet. Pretty. I wonder what's out there.", "calm", None)],
        "play": [("They're so loud. It's cute. I'll finish this before they notice.", "calm", None),
                 ("That one was weak. Next.", "calm", "i'll handle it :)"),
                 ("Kaito keeps dying. I should probably help him.", "happy", "kaito, behind you.")],
        "end": [("Fun. Let's do it again.", "happy", "goodnight :)")],
    },
}


class FakeShowBrain:
    """In-character canned replies — the whole pipeline with zero model calls."""

    def __init__(self, char_id: str, *_, actions: bool = True, **__):
        self.cid = char_id if char_id in LINES else "kaito"
        self.n = 0
        self.memory = ""
        self.actions = actions
        self.closed = False

    def _payload(self, kind: str) -> dict:
        opts = LINES[self.cid][kind]
        iv, emo, say = opts[self.n % len(opts)]
        acts = []
        if self.actions and kind != "end":
            acts = [{"tool": "look_around", "args": {}}] if self.n < 2 else [{"tool": "warp", "args": {"dest": "hub"}}]
        return {"inner_voice": iv if (self.n % 2 == 0 or kind != "play") else None, "emotion": emo, "say": say,
                "goal": {"wake": "figure out where I am", "play": "get stronger", "end": "wrap up"}[kind],
                "actions": acts}

    def turn(self, blocks: list[dict]):
        text = " ".join(b.get("text", "") for b in blocks if isinstance(b, dict))
        kind = "end" if re.search(r"wrap|last words|episode is over|final", text, re.I) else \
            ("wake" if self.n == 0 else "play")
        out = self._payload(kind)
        self.n += 1
        return _reply(json.dumps(out))

    def reset(self, summary: str) -> None:
        self.memory = summary

    def close(self) -> None:
        self.closed = True


class FakeDirectorBrain:
    """Exercises the Director's LLM path (and its defensive parser) without a model."""

    def __init__(self, *_, **__):
        self.n = 0

    def turn(self, blocks: list[dict]):
        self.n += 1
        text = blocks[0].get("text", "") if blocks else ""
        m = re.search(r'"rubric_score":\s*([\d.]+)', text)
        score = float(m.group(1)) if m else 0
        pm = re.search(r'"players":\s*\[\s*"([^"]+)"', text)
        tgt = pm.group(1) if pm else None
        if score >= 6.5 and tgt:
            obj = {"decision": "CINEMATIC", "target": tgt, "shot": "orbit", "seconds": 9, "also_mark": True,
                   "label": "big moment", "reason": "fake director: it's big"}
        elif score >= 5 and tgt:
            obj = {"decision": "CUT_TO", "target": tgt, "reason": "fake director: worth a look"}
        else:
            obj = {"decision": "HOLD", "reason": "fake director: grid is fine"}
        body = json.dumps(obj)
        return _reply(f"```json\n{body}\n```" if self.n % 3 == 0 else body)   # sometimes fenced, like real models

    def reset(self, summary: str) -> None:
        pass

    def close(self) -> None:
        pass


# =============================================================================== mock world + agents
class SimWorld:
    """Stands in for the Paper server + ShowControl: emits server events on a script and keeps
    positions so the Director's meeting detection has something to find."""

    def __init__(self, bus: EventBus, chars: list[Character], clock: EpisodeClock, total_s: float, seed: int = 7):
        self.bus, self.chars, self.clock, self.total = bus, chars, clock, total_s
        self.rnd = random.Random(seed)
        self.t_play = None
        self.pos: dict[str, dict] = {}
        self.awake: set[str] = set()
        self.fired: set[int] = set()
        self.voice_ends: list[tuple[float, str, str]] = []
        names = [c.name for c in chars]
        K, R, M, Y = (names + names)[:4]
        # (fraction of episode, type, players, drama, text, data)
        self.script = [
            (0.22, "island_change", [R], 3, f"{R} arrives at the Hub", {"from": "private", "to": "hub"}),
            (0.22, "first_of_kind", [R], 6, f"{R} is the first to reach the Hub", {"what": "first to reach the hub"}),
            (0.25, "island_change", [K], 3, f"{K} arrives at the Hub", {"from": "private", "to": "hub"}),
            (0.27, "island_change", [M], 3, f"{M} arrives at the Hub", {"from": "private", "to": "hub"}),
            (0.29, "island_change", [Y], 3, f"{Y} arrives at the Hub", {"from": "private", "to": "hub"}),
            (0.36, "mob_kill", [K], 2, f"{K} killed a Graveyard Zombie", {"mob_id": "ZOMBIE", "mob_name": "Graveyard Zombie", "coins": 5}),
            (0.40, "gather_summary", [R], 1, f"{R} harvested 64 Wheat", {"kind": "farming", "what": "WHEAT", "amount": 64}),
            (0.44, "item_gain", [M], 4, f"{M} found a RARE Golden Tooth", {"item_id": "GOLDEN_TOOTH", "amount": 1, "rarity": "RARE", "source": "drop"}),
            (0.48, "skill_level_up", [R], 3, f"{R} reached Farming 5", {"skill": "FARMING", "level": 5}),
            (0.55, "near_death", [K], 6, f"{K} is at 9% health!", {"health": 9, "max": 100}),
            (0.58, "death", [K], 7, f"{K} died to a Zombie Villager", {"cause": "Zombie Villager", "coins_lost": 12}),
            (0.58, "first_of_kind", [K], 6, f"{K} is the first to die", {"what": "first death"}),
            (0.68, "boss_spawn", [Y], 9, "A Nullshade Seraph has spawned!", {"mob_id": "NULLSHADE_SERAPH", "name": "Nullshade Seraph"}),
            (0.76, "mob_kill", [Y], 8, f"{Y} slew the Nullshade Seraph", {"mob_id": "NULLSHADE_SERAPH", "mob_name": "Nullshade Seraph", "boss": True, "coins": 900}),
            (0.80, "item_gain", [Y], 8, f"{Y} found a LEGENDARY Seraph Blade", {"item_id": "SERAPH_BLADE", "amount": 1, "rarity": "LEGENDARY", "source": "boss"}),
            (0.86, "coins_milestone", [M], 3, f"{M} reached 1,000 coins", {"purse": 1000}),
        ]
        self.hub_t: dict[str, float] = {}

    def _emit(self, type: str, players: list[str], drama: float, text: str, data: dict) -> None:
        self.bus.emit("server", type, players=players, drama=drama, text=text, data=data, t=self.clock.ms())

    def wake(self, ch: Character) -> None:
        self.awake.add(ch.name)
        a = 2 * math.pi * ch.slot / 4
        self.pos[ch.name] = {"island": f"private_{ch.id}", "x": 300 * math.cos(a), "y": 100, "z": 300 * math.sin(a)}
        self._emit("join", [ch.name], 2, f"{ch.name} joined", {})
        self._emit("profile_ready", [ch.name], 3, f"{ch.name} wakes up on a private island", {"first_join": True})

    def start_play(self, start_t: float, episode_n: int, minutes: float) -> None:
        self.t_play = start_t
        self._emit("episode_start", [], 5, "The episode begins", {"minutes": minutes, "episode": episode_n})

    def end(self, reason: str = "time") -> None:
        self._emit("episode_end", [], 5, "Time's up", {"reason": reason})

    def say_echo(self, char: str, text: str) -> None:
        self._emit("chat", [char], 0, f"{char}: {text}", {"message": text})

    def positions(self) -> dict:
        return {k: dict(v) for k, v in self.pos.items()}

    def step(self, now: float, ep_start: float) -> None:
        frac = (now - ep_start) / self.total if self.total else 1.0
        for i, (f, typ, ps, drama, text, data) in enumerate(self.script):
            if i not in self.fired and frac >= f and all(p in self.awake for p in ps):
                self.fired.add(i)
                self._emit(typ, ps, drama, text, data)
                if typ == "island_change":
                    self.hub_t[ps[0]] = now
                    slot = next(c.slot for c in self.chars if c.name == ps[0])
                    self.pos[ps[0]] = {"island": "hub", "x": 40.0 * (1 if slot % 2 else -1), "y": 70,
                                       "z": 40.0 * (1 if slot >= 2 else -1)}
        for name, t in self.hub_t.items():          # converge on the hub fountain
            p = self.pos[name]
            k = min(1.0, (now - t) / 25.0)
            p["x"] *= (1 - 0.15 * k)
            p["z"] *= (1 - 0.15 * k)
        for end_t, char, clip in list(self.voice_ends):
            if now >= end_t:
                self.voice_ends.remove((end_t, char, clip))
                self.bus.emit("tts", "tts_end", players=[char], data={"char": char, "clip": clip}, t=self.clock.ms())


class SimAgent:
    """PlayerAgent stand-in for the accelerated mock: same events, no threads, no pilot."""

    def __init__(self, char: Character, bus: EventBus, clock: EpisodeClock, world: SimWorld,
                 brain: Any = None, gap_s: float = 9.0):
        self.char, self.bus, self.clock, self.world = char, bus, clock, world
        self.brain = brain or FakeShowBrain(char.id, actions=False)
        self.gap = gap_s
        self.next_t = 0.0
        self.started = False
        self._paused = False
        self.turn_n = 0
        self.first_turn_done = threading.Event()

    def emit(self, _type: str, _line: str = "", drama: float = 0, **data) -> None:
        self.bus.emit("agent", _type, players=[self.char.name], drama=drama, text=_line,
                      data={"char": self.char.name, **data}, t=self.clock.ms())

    def start(self) -> None:
        self.started = True
        self.next_t = self.clock.now()

    def pause(self) -> None:
        self._paused = True

    def resume(self) -> None:
        self._paused = False

    def stop_loop(self) -> None:
        self.started = False

    def kill(self) -> None:
        self.started = False

    def restart(self) -> None:
        self.emit("agent_log", f"{self.char.name}: brain restarted", restarted=True)

    def _speak(self, rep: dict, final: bool = False) -> None:
        if rep.get("inner_voice"):
            self.emit("inner_voice", f"{self.char.name} (thinking): {rep['inner_voice']}", 3 if final else 2,
                      text=rep["inner_voice"], emotion=rep.get("emotion", "calm"), voice=True)
            dur = 600 + 55 * len(rep["inner_voice"])
            clip = f"sim_{self.char.id}_{self.turn_n}.wav"
            self.bus.emit("tts", "tts_start", players=[self.char.name],
                          data={"char": self.char.name, "clip": clip, "duration_ms": dur}, t=self.clock.ms())
            self.world.voice_ends.append((self.clock.now() + dur / 1000.0, self.char.name, clip))
        if rep.get("say"):
            self.emit("say", f"{self.char.name}: {rep['say']}", 2, text=rep["say"])
            self.world.say_echo(self.char.name, rep["say"])

    def step(self, now: float) -> None:
        if not self.started or self._paused or DIALS.get("paused", False) or now < self.next_t:
            return
        self.turn_n += 1
        r = self.brain.turn([{"type": "text", "text": "wake up" if self.turn_n == 1 else "continue"}])
        try:
            rep = json.loads(r.text)
        except ValueError:
            rep = {}
        self.emit("agent_turn", f"{self.char.name}: {rep.get('goal', '')}", turn=self.turn_n,
                  goal=rep.get("goal", ""), actions=rep.get("actions", []), latency_s=4.0)
        self._speak(rep)
        self.next_t = now + self.gap
        self.first_turn_done.set()

    def final_turn(self) -> dict | None:
        r = self.brain.turn([{"type": "text", "text": "The episode is over - wrap-up, last words."}])
        try:
            rep = json.loads(r.text)
        except ValueError:
            return None
        self.emit("agent_turn", f"{self.char.name}: final words", turn=self.turn_n + 1, goal="wrap-up",
                  actions=[], latency_s=3.0, final=True)
        self._speak(rep, final=True)
        return rep


class FakeCamera:
    def __init__(self) -> None:
        self.shots: list[tuple] = []

    def showcam_up(self, force: bool = False) -> bool:
        return True

    def shot(self, kind: str, target: Any, seconds: float = 8.0, **kw: Any) -> dict:
        self.shots.append((kind, target, seconds))
        return {"ok": True, "via": "fake"}

    def return_to_grid(self) -> None:
        self.shots.append(("stop", None, 0))

    stop = return_to_grid


class ListHub:
    """Overlay sink for headless runs: keeps every overlay message (and appends to a jsonl)."""

    def __init__(self, path: Path | None = None) -> None:
        self.msgs: list[dict] = []
        self.path = path
        self.audio_dir: Path | None = None
        self._lock = threading.Lock()

    def push(self, msg: dict) -> dict:
        with self._lock:
            self.msgs.append(dict(msg))
            if self.path:
                with open(self.path, "a", encoding="utf-8") as f:
                    f.write(json.dumps(msg, ensure_ascii=False) + "\n")
        return msg

    def set_audio_dir(self, p) -> None:
        self.audio_dir = Path(p)


# =============================================================================== the vision mixer
class Stage:
    """What the audience sees: OBS scenes + the camera client + scene/narrate events (the overlay
    bridge turns those events into overlay messages). Every OBS/camera failure is swallowed and
    reported once — a broken scene switch must never end an episode."""

    def __init__(self, bus: EventBus, clock: EpisodeClock, chars: list[Character], *, rig: Any = None,
                 camera: Any = None, push: Callable[[dict], Any] | None = None):
        self.bus, self.clock, self.chars = bus, clock, chars
        self.rig, self.camera, self._push = rig, camera, push
        self.log: list[tuple] = []
        self._errors: set[str] = set()

    def _emit(self, type: str, **kw: Any) -> None:
        self.bus.emit(kw.pop("src", "director"), type, t=self.clock.ms(), **kw)

    def _safe(self, what: str, fn: Callable, *a: Any) -> Any:
        try:
            return fn(*a)
        except Exception as e:
            if what not in self._errors:
                self._errors.add(what)
                self._emit("system_warning", src="system", text=f"{what} failed: {e}", data={"what": what, "error": str(e)})
            return None

    def push(self, msg: dict) -> None:
        if self._push:
            self._safe("overlay push", self._push, msg)

    def char(self, name: str) -> Character | None:
        return next((c for c in self.chars if c.name.lower() == str(name).lower()), None)

    def lower_third(self, c: Character) -> dict:
        return {"title": c.name.upper(), "subtitle": c.archetype.upper(), "color": c.color, "slot": c.slot}

    # -- director interface
    def grid(self) -> None:
        self.log.append(("grid",))
        if self.rig:
            self._safe("OBS grid", self.rig.grid)
        if self.camera:
            self._safe("camera stop", self.camera.return_to_grid)
        self._emit("scene", data={"mode": "grid"}, text="grid")

    def cut_to(self, name: str) -> None:
        self.log.append(("cut", name))
        if self.rig:
            self._safe("OBS focus", self.rig.focus, name)

    def cinematic(self, target: Any, shot: str, seconds: float, label: str = "") -> None:
        self.log.append(("cinematic", target, shot, seconds))
        ok = None
        if self.camera:
            ok = self._safe("camera shot", self.camera.shot, shot, target, seconds)
        if self.rig:
            if ok and ok.get("ok"):
                self._safe("OBS cinematic", self.rig.cinematic)
            elif isinstance(target, str):      # no camera: the player's own POV is the next best shot
                self._safe("OBS focus", self.rig.focus, target)

    def narrate(self, line: str) -> None:
        self.log.append(("narrate", line))
        self._emit("narrate", text=line, data={"text": line})

    def ticker(self, text: str, drama: float) -> None:
        self.push({"kind": "ticker", "text": text, "drama": round(float(drama), 1)})

    # -- episode-only shots
    def intro_focus(self, c: Character, pov: bool = True) -> None:
        self.log.append(("intro", c.name, pov))
        if self.rig:
            self._safe("OBS focus", self.rig.focus, c.name if pov else None)
        self._emit("scene", players=[c.name], text=f"intro {c.name}",
                   data={"mode": "intro", "target": c.name, "lower_third": self.lower_third(c)})

    def intro_orbit(self, c: Character, seconds: float) -> bool:
        if not self.camera:
            return False
        r = self._safe("camera shot", self.camera.shot, "orbit", c.name, seconds)
        if r and r.get("ok"):
            self.log.append(("orbit", c.name, seconds))
            if self.rig:
                self._safe("OBS focus", self.rig.focus, None)
            return True
        return False

    def title(self, n: int, seconds: float) -> None:
        self.log.append(("title", n))
        if self.rig:   # the camera client behind the title card (or the first player if there is no camera)
            self._safe("OBS focus", self.rig.focus, None if self.camera else (self.chars[0].name if self.chars else None))
        # the title card itself is pushed by the overlay bridge when episode_start arrives (one card, not two)
        self._emit("scene", data={"mode": "intro"}, text="title")


# =============================================================================== episode
@dataclass
class EpisodeOptions:
    mock: bool = False
    brain: str = "real"                 # real | fake
    agents: str | None = None           # sim | real (default: sim for mock+fake, real otherwise)
    minutes: float | None = None
    speed: float = 1.0                  # >1 only with sim agents
    title_s: float = 7.0
    join_gap_s: float = 25.0
    beat_s: float = 8.0
    use_obs: bool = True
    launch_clients: bool = True
    start_server: bool = True
    voice: str = "auto"                 # auto | indextts | sapi | none
    episodes_dir: Path | None = None
    director_llm: bool = True
    step_s: float = 0.5                 # sim step
    log: Callable[[str], None] = print


def next_episode_number(episodes_dir: Path) -> int:
    n = 0
    for p in episodes_dir.glob("*_ep*"):
        m = re.search(r"_ep(\d+)$", p.name)
        if m and p.is_dir():
            n = max(n, int(m.group(1)))
    return n + 1


class Episode:
    def __init__(self, opts: EpisodeOptions | None = None, *, bus: EventBus | None = None,
                 overlay: Any = None, rig: Any = None, camera: Any = None):
        self.o = opts or EpisodeOptions()
        o = self.o
        if o.agents is None:
            o.agents = "sim" if (o.mock and o.brain == "fake") else "real"
        if o.agents != "sim":
            o.speed = 1.0
        self.bus = bus or BUS
        self.chars = list(SHOW.characters)
        self.clock = EpisodeClock(o.speed)
        self.minutes = float(o.minutes if o.minutes is not None else DIALS.get("episode_minutes", 30))
        self.total_s = self.minutes * 60.0
        root = Path(o.episodes_dir or SHOW.paths.get("episodes"))
        root.mkdir(parents=True, exist_ok=True)
        self.n = next_episode_number(root)
        tag = f"mock{self.n}" if o.mock else f"ep{self.n}"
        name = f"{date.today().isoformat()}_{tag}"
        k = 2
        while (root / name).exists():
            name = f"{date.today().isoformat()}_{tag}-{k}"
            k += 1
        self.dir = root / name
        self.overlay = overlay
        self.rig = rig
        self.camera = camera
        self.agents: dict[str, Any] = {}
        self.world: SimWorld | None = None
        self.mworld = None              # show.mock.MockWorld when real agents run against the mock
        self._mw_seen = 0
        self.director = None
        self.bridge = None
        self.server = None
        self.feed: ServerFeed | None = None
        self.stage: Stage | None = None
        self.rec_start: float | None = None
        self.record_path: str | None = None
        self.ep_start = 0.0
        self._paused_acc = 0.0
        self._paused_since: float | None = None
        self._stop_req = threading.Event()
        self.active = False
        self.outputs: dict[str, Path] = {}
        self.result: dict = {}

    # ------------------------------------------------------------------ helpers
    def log(self, msg: str) -> None:
        try:
            self.o.log(f"[episode] {msg}")
        except Exception:
            pass

    def emit(self, _type: str, _line: str = "", drama: float = 0, **data: Any) -> None:
        self.bus.emit("system", _type, text=_line, drama=drama, data=data, t=self.clock.ms())

    def sc(self, cmd: str) -> Any:
        if self.o.mock:
            self.emit("mock_cmd", cmd, cmd=cmd)
            return {"ok": True, "mock": True}
        try:
            return showcontrol("/cmd", {"cmd": cmd})
        except Exception as e:
            self.log(f"ShowControl '{cmd}' failed: {e}")
            return {"ok": False, "error": str(e)}

    def elapsed(self) -> float:
        """Episode seconds, not counting pauses."""
        if not self.ep_start:
            return 0.0
        now = self.clock.now()
        paused = self._paused_acc + ((now - self._paused_since) if self._paused_since else 0.0)
        return max(0.0, now - self.ep_start - paused)

    def agent_clock(self) -> tuple[float, float]:
        return self.elapsed(), self.total_s

    def wait(self, sim_s: float, until: Callable[[], bool] | None = None) -> None:
        """Sleep sim seconds while keeping the sim world, sim agents and director stepping."""
        end = self.clock.now() + sim_s
        while not self._stop_req.is_set():
            now = self.clock.now()
            if now >= end or (until and until()):
                return
            self._step(now)
            self.clock.sleep(min(self.o.step_s, max(0.01, end - now)))

    def _step(self, now: float) -> None:
        if self.world:
            self.world.step(now, self.ep_start or now)
        if self.mworld is not None:                       # forward the mock world's own events
            from .events import map_players
            evs = self.mworld.events[self._mw_seen:]
            self._mw_seen += len(evs)
            for e in evs:
                self.bus.publish(map_players(dict(e, src="server")))
        for a in self.agents.values():
            if isinstance(a, SimAgent):
                a.step(now)
        if self.director is not None and self.o.agents == "sim":
            self.director.pump()
        self._track_pause(now)

    def _track_pause(self, now: float) -> None:
        p = bool(DIALS.get("paused", False))
        if p and self._paused_since is None:
            self._paused_since = now
        elif not p and self._paused_since is not None:
            self._paused_acc += now - self._paused_since
            self._paused_since = None

    # ------------------------------------------------------------------ control hook
    def control(self, action: str, body: dict) -> dict | None:
        if not self.active:
            return None
        from .director import Decision
        body = body or {}
        target = body.get("char") or body.get("target")
        if action == "start":
            return {"ok": False, "error": "an episode is already running"}
        if action == "stop":
            self._stop_req.set()
            return {"ok": True, "stopping": True}
        if action in ("pause", "resume"):
            on = action == "pause"
            DIALS.set("paused", on)
            for a in self.agents.values():
                (a.pause if on else a.resume)()
            self.sc(f"show freeze {'on' if on else 'off'}")
            return {"ok": True, "paused": on}
        if action == "cinematic" and self.director:
            tgt = target or (self.chars[0].name if self.chars else None)
            ok = self.director.force(Decision("CINEMATIC", target=tgt, shot=body.get("shot", "orbit"),
                                              seconds=float(body.get("seconds", 10)), score=10,
                                              label=body.get("label") or f"{tgt}", reason="dashboard", by="human"))
            return {"ok": True, "executed": ok, "deferred_for_voice": not ok}
        if action == "grid" and self.director:
            self.director.force(Decision("HOLD", reason="dashboard", by="human"))
            return {"ok": True}
        if action == "mark":
            label = body.get("label") or "marked moment"
            if self.o.mock:
                self.bus.emit("system", "mark", text=f"MARK {label}", data={"label": label}, drama=6, t=self.clock.ms())
            else:
                self.sc(f"show mark {label}")
            return {"ok": True, "marked": label}
        if action in ("kill_agent", "restart_agent"):
            c = next((c for c in self.chars if target and target.lower() in (c.name.lower(), c.id, c.account.lower())), None)
            a = self.agents.get(c.name) if c else None
            if not a:
                return {"ok": False, "error": f"no agent {target!r}"}
            fn = a.kill if action == "kill_agent" else a.restart
            threading.Thread(target=fn, daemon=True).start()
            return {"ok": True, action: c.name}
        return None

    # ------------------------------------------------------------------ setup
    def _start_services(self) -> None:
        o = self.o
        overlay_log = self.dir / "overlay.jsonl"
        if o.start_server:
            try:
                from . import server as server_mod
                self.server = server_mod.start()
                server_mod.on_control(self.control)
                hub = self.server
                self.log(f"dashboard {self.server.url}/   overlay {self.server.url}/overlay")
            except Exception as e:
                self.log(f"dashboard server unavailable ({e}); overlay messages go to overlay.jsonl only")
                hub = self.overlay or ListHub(overlay_log)
        else:
            hub = self.overlay or ListHub(overlay_log)
        self.overlay = hub
        try:
            from .overlay_bridge import Bridge
            from .tts import TTS, NoneEngine
            voice = "none" if (o.mock and o.brain == "fake") else o.voice
            tdials = DialsView(DIALS, tts_engine=voice) if voice != "auto" else DIALS
            tts = TTS(audio_dir=self.dir / "audio", dials=tdials,
                      engines={"none": NoneEngine()} if voice == "none" else None)
            self.bridge = Bridge(server=hub, bus=self.bus, tts=tts, dials=DIALS, poll_server=not o.mock).start()
            self.bridge.set_episode_dir(self.dir)
            self.bridge._episode_n = self.n
            if voice in ("auto", "indextts") and not o.mock:
                try:
                    r = tts.start_indextts()
                    self.log(f"IndexTTS2: {r}")
                except Exception as e:
                    self.log(f"IndexTTS2 not started ({e}); SAPI speaks instead")
        except Exception as e:
            self.log(f"overlay bridge unavailable ({e}); captions/voices disabled")
            self.bridge = None

    def _start_feed(self) -> None:
        if self.o.mock:
            self.world = SimWorld(self.bus, self.chars, self.clock, self.total_s)
            if self.o.agents == "real":
                from .mock import MockWorld          # B2: a fake SkyClone the real macros can drive
                self.mworld = MockWorld()
        else:
            self.feed = ServerFeed(self.bus)
            self.feed.start()

    def _start_obs(self) -> None:
        o = self.o
        if self.rig is None and o.use_obs and not o.mock:
            try:
                from .obs import connect_from_config, OBSRig
                from .clients import CAMERA_NAME
                client = connect_from_config(timeout=5)
                url = (self.server.url if self.server else f"http://127.0.0.1:{SHOW.ports['orchestrator']}") + "/overlay"
                self.rig = OBSRig(client, self.chars, overlay_url=url, camera_title=f"AISky - {CAMERA_NAME}")
                summary = self.rig.ensure_scenes()
                self.log(f"OBS scenes ready: {summary}")
            except Exception as e:
                raise RuntimeError(f"OBS not usable: {e} (start OBS + enable websocket, or run with --no-obs)") from e
        if self.rig is not None:
            self.rig.mute_ambient()
            self.rec_start = self.rig.start_record(self.dir)
            self.log(f"recording -> {self.dir}")
        else:
            self.rec_start = self.clock.now()
        self._write_recording()

    def _write_recording(self) -> None:
        rec = {"rec_start_ms": int((self.rec_start or self.clock.now()) * 1000), "record_path": self.record_path,
               "source": "obs" if self.rig is not None else ("mock" if self.o.mock else "none"),
               "episode": self.n, "minutes": self.minutes}
        (self.dir / "recording.json").write_text(json.dumps(rec, indent=2), encoding="utf-8")

    def _launch_clients(self) -> None:
        if self.o.mock or not self.o.launch_clients:
            return
        from . import clients
        try:
            clients.launch(clients.camera_spec(), wait=True, log=self.log)
            self.sc(f"show camera {SHOW.camera_account}")
        except Exception as e:
            self.log(f"camera client failed ({e}) - cinematics fall back to player POV")
            self.camera = None
        for c in self.chars:
            clients.launch(clients.spec_for(c), wait=True, log=self.log)

    def _make_agent(self, c: Character) -> Any:
        o = self.o
        if o.agents == "sim":
            return SimAgent(c, self.bus, self.clock, self.world)
        from .agent import PlayerAgent
        if o.mock:
            from .mock import MockPilot
            pilot = MockPilot(self.mworld, c.account)
        else:
            from .pilot_bridge import get_pilot
            pilot = get_pilot(c.pilot_port)
        factory = (lambda ch, sp: FakeShowBrain(ch.id)) if o.brain == "fake" else None
        return PlayerAgent(c, pilot, factory, bus=self.bus, clock=self.agent_clock)

    def _make_director(self) -> None:
        from .director import Director, DIRECTOR_SYSTEM, positions_from_showcontrol
        brain = None
        if self.o.director_llm and DIALS.get("director_llm", True):
            if self.o.brain == "fake":
                brain = FakeDirectorBrain()
            else:
                try:
                    from .brain import make_brain
                    brain = make_brain("director", DIRECTOR_SYSTEM, model=DIALS["director_model"],
                                       effort=DIALS.get("director_effort", "low"),
                                       cwd=AGENT_HOME / "agents" / "director")
                except Exception as e:
                    self.log(f"director brain unavailable ({e}) - rubric only")
        if self.mworld is not None:
            from .mock import MockShowControl
            msc = MockShowControl(self.mworld)
            positions = positions_from_showcontrol(self.chars, lambda path, body=None, timeout=2.0:
                                                   msc.state(path.rsplit("/", 1)[-1]))
        elif self.world:
            positions = self.world.positions
        else:
            positions = positions_from_showcontrol(self.chars)
        look = None
        if brain is not None and not self.o.mock:
            frames = self.dir / "frames"
            frames.mkdir(exist_ok=True)

            def look(name: str) -> str | None:
                a = self.agents.get(name)
                pilot = getattr(a, "pilot", None)
                if pilot is None or not hasattr(pilot, "screenshot"):
                    return None
                return pilot.screenshot(frames / f"{name}.png")
        self.director = Director(self.bus, self.stage, brain=brain, positions=positions, look=look,
                                 clock=self.clock.now, names=[c.name for c in self.chars],
                                 async_llm=self.o.agents != "sim",
                                 voice_busy=(self.bridge.tts.busy if self.bridge is not None else None))
        self.director.start(threaded=self.o.agents != "sim")

    # ------------------------------------------------------------------ the show
    def _intro(self) -> None:
        o = self.o
        self.ep_start = self.clock.now()
        q = self.bus.subscribe()                 # before the command, so its echo cannot be missed
        try:
            self.sc(f"show start {int(math.ceil(self.minutes))}")
            if self.world:
                self.world.start_play(self.ep_start, self.n, self.minutes)
            else:
                self._ensure_episode_start(q)
        finally:
            self.bus.unsubscribe(q)
        self.stage.title(self.n, o.title_s)
        self.wait(o.title_s)
        self.sc("show freeze off")
        pause_others = bool(DIALS.get("intro_pause_others", True))
        for c in self.chars:
            if self._stop_req.is_set():
                return
            self.log(f"intro: {c.name} wakes up")
            if pause_others:
                for a in self.agents.values():
                    a.pause()
            if self.world:
                self.world.wake(c)
            self.stage.intro_focus(c, pov=True)
            a = self._make_agent(c)
            self.agents[c.name] = a
            a.start()
            joined = self.clock.now()
            half = o.join_gap_s * 0.55
            # their POV while the wake-up turn is thought and voiced ("huh? where am I")
            self.wait(half)
            # then the camera client orbits their island (if ShowCam / the fallback camera answers)
            self.stage.intro_orbit(c, o.join_gap_s - half + 4)
            # hold at least join_gap_s; longer if the first turn or its voice line is still running
            cap = joined + max(o.join_gap_s, float(DIALS.get("intro_join_max_s", 50.0)))
            self.wait(o.join_gap_s - half)
            self.wait(max(0.0, cap - self.clock.now()),
                      until=lambda: getattr(a, "first_turn_done", None) is None or
                      (a.first_turn_done.is_set() and not self._voice_busy()))
        for a in self.agents.values():
            a.resume()
        self.stage.grid()
        self.emit("intro_done", "All four are awake", 4)
        self.stage.ticker("All four are awake. None of them know where they are.", 5)
        self.wait(o.beat_s)

    def _voice_busy(self) -> bool:
        try:
            return bool(self.bridge is not None and (self.bridge.tts.busy() or self.bridge.tts.status().get("queued")))
        except Exception:
            return False

    def _ensure_episode_start(self, q) -> None:
        """ShowControl emits episode_start on `/show start` (that starts the overlay's title card and
        clock); if it has not arrived within 3 s, emit one so the show still opens properly."""
        deadline = time.time() + 3
        while time.time() < deadline:
            try:
                ev = q.get(timeout=0.3)
            except Exception:
                continue
            if ev.get("type") == "episode_start":
                return
        self.bus.emit("system", "episode_start", text="The episode begins", drama=5,
                      data={"minutes": self.minutes, "episode": self.n}, t=self.clock.ms())

    def _play(self) -> None:
        self._make_director()
        self.log(f"play: {self.minutes:g} minutes, director {'LLM+rubric' if self.director.brain else 'rubric'}")
        last_report = 0.0
        while not self._stop_req.is_set() and self.elapsed() < self.total_s:
            self.wait(min(1.0, self.total_s - self.elapsed() + 0.01))
            if self.elapsed() - last_report >= 60:
                last_report = self.elapsed()
                self.log(f"{int(self.elapsed() // 60)}:{int(self.elapsed() % 60):02d} / {int(self.minutes)}:00")

    def _wrap(self) -> None:
        self.log("wrap-up")
        self.sc("show stop")
        if self.world:
            self.world.end("time" if not self._stop_req.is_set() else "stopped")
        if self.director:
            self.director.stop()
            self.director.return_to_grid(force=True)
        for a in self.agents.values():
            try:
                a.stop_loop()
            except Exception:
                pass
        if self.agents and self.o.agents != "sim":
            for a in self.agents.values():         # their loops end on the next check; join briefly
                if hasattr(a, "join"):
                    a.join(timeout=45)
        threads = []
        for a in self.agents.values():
            t = threading.Thread(target=self._safe_final, args=(a,), daemon=True)
            t.start()
            threads.append(t)
        for t in threads:
            t.join(timeout=90)
        self.wait(6)                                  # let the last lines land
        if self.bridge is not None and not self.o.mock:
            deadline = time.time() + 30
            while time.time() < deadline and (self.bridge.tts.busy() or self.bridge.tts.status().get("queued")):
                time.sleep(0.5)
        self.stage.push({"kind": "title_card", "title": "SEE YOU NEXT EPISODE", "subtitle": TITLE, "seconds": 5})
        self.wait(5)

    def _safe_final(self, a: Any) -> None:
        try:
            a.final_turn()
        except Exception as e:
            self.log(f"final turn failed for {getattr(a, 'char', a)}: {e}")

    # ------------------------------------------------------------------ run
    def run(self) -> dict:
        o = self.o
        self.dir.mkdir(parents=True, exist_ok=True)
        self.bus.open_log(self.dir / "events.jsonl")
        meta = {"episode": self.n, "mock": o.mock, "brain": o.brain, "agents": o.agents, "minutes": self.minutes,
                "speed": o.speed, "started": time.strftime("%Y-%m-%d %H:%M:%S"),
                "cast": [{"name": c.name, "account": c.account, "slot": c.slot} for c in self.chars],
                "dials": DIALS.snapshot()}
        (self.dir / "meta.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
        self.active = True
        err = None
        if DIALS.get("paused", False):          # a pause left over from a previous run would freeze everyone
            DIALS.set("paused", False)
        try:
            from .cost import METER as _meter
            _meter.reset()
        except Exception:
            pass
        try:
            self._start_services()
            if self.camera is None:
                if o.mock:
                    self.camera = FakeCamera()
                else:
                    from .camera import Camera
                    self.camera = Camera()
            self._start_feed()
            if not o.mock:
                self.sc("show reset")
                if SHOW.ruleset:
                    self.sc(f"show ruleset {SHOW.ruleset}")
                self._launch_clients()
                self.sc("show freeze on")
            push = getattr(self.overlay, "push", None)
            self.stage = Stage(self.bus, self.clock, self.chars, rig=self.rig, camera=self.camera, push=push)
            self._start_obs()
            self.stage.rig = self.rig
            self._intro()
            self._play()
            self._wrap()
        except KeyboardInterrupt:
            err = "interrupted"
            self.log("interrupted - wrapping up")
        except Exception as e:
            err = f"{type(e).__name__}: {e}"
            self.log("episode error: " + err)
            (self.dir / "error.txt").write_text(traceback.format_exc(), encoding="utf-8")
        finally:
            self.active = False
            for a in self.agents.values():
                try:
                    a.kill()
                except Exception:
                    pass
            if self.director:
                try:
                    self.director.stop()
                except Exception:
                    pass
            if self.rig is not None:
                try:
                    self.record_path = self.rig.stop_record()
                except Exception as e:
                    self.log(f"OBS stop record: {e}")
                    try:
                        self.rig.restore_record_dir()
                    except Exception:
                        pass
                try:
                    self.rig.restore_mutes()
                    self.rig.c.close()
                except Exception:
                    pass
                self._write_recording()
            if not o.mock:
                self.sc("show freeze off")
            if self.feed:
                self.feed.stop()
            if self.bridge is not None:
                try:
                    self.bridge.stop()
                except Exception:
                    pass
            try:
                self.emit("episode_closed", "episode closed", 0, error=err)
            finally:
                self.bus.close_log()
        try:
            from . import edit
            meter = None
            try:
                from .cost import METER as _M
                meter = _M.snapshot()
            except Exception:
                pass
            self.outputs = edit.generate(self.dir, meter=meter)
        except Exception as e:
            self.log(f"edit outputs failed: {e}")
            err = err or f"edit: {e}"
        self.result = {"ok": err is None, "error": err, "dir": str(self.dir), "record_path": self.record_path,
                       "outputs": {k: str(v) for k, v in self.outputs.items()}}
        self.log(f"done -> {self.dir}" + (f" (error: {err})" if err else ""))
        return self.result
