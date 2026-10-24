"""BUS events -> overlay messages (CONTRACT.md §3 -> §5), plus the voice queue and the run clock.

    from show import server, overlay_bridge
    srv = server.start()
    bridge = overlay_bridge.start(srv)          # subscribes to BUS, owns a TTS
    bridge.set_episode_dir(ep_dir)              # clips land in ep_dir/audio, served at /audio/
    bridge.tts.start_indextts()                 # RECORD TIME ONLY (GPU) - else SAPI speaks

Mapping:
  inner_voice -> TTS queue -> caption (audio_url when a clip was made; caption-only when
                 data.voice is False, the char is over voice_lines_per_min, TTS failed, or stale)
                 and emits tts_start / tts_end on the BUS (the director must not cut during a line)
  say / server chat -> chat bubble (deduped: the agent's say and ShowControl's chat echo are one line)
  agent_turn / agent_action / agent_stuck -> dashboard state (+ status goal on the overlay)
  cost -> cost      server events with drama >= 3 -> ticker
  director CINEMATIC / cinematic_start -> scene cinematic + letterbox + lower third
  cinematic_end -> scene grid      director CUT_TO -> scene grid with focus
  narrate (or director NARRATE) -> narrator TTS + narrate caption (deduped)
  episode_start -> title card + clock      profile_ready{first_join} -> per-character intro card
"""
from __future__ import annotations

import collections
import json
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

from .config import SHOW
from .dials import DIALS
from .events import BUS
from .tts import TTS, Line

TICKER_MIN_DRAMA = 3
CHAT_DEDUPE_S = 12.0
OWN_TYPES = {"tts_start", "tts_end"}

HEADLINES = {
    "boss_spawn": "BOSS SPAWNED", "near_death": "NEAR DEATH", "death": "DOWN!",
    "first_of_kind": "FIRST!", "skill_level_up": "LEVEL UP", "item_gain": "RARE DROP",
    "collection_tier_up": "COLLECTION UP", "fish_caught": "SEA CREATURE", "big_hit": "MASSIVE HIT",
    "calendar_start": "EVENT", "mob_kill": "BOSS DOWN", "island_change": "NEW ISLAND",
}


def caption_ms(text: str) -> int:
    """How long a caption without audio stays readable."""
    return int(max(2500, min(9000, 1200 + 55 * len(text or ""))))


def fmt_coins(n) -> str:
    try:
        n = float(n)
    except (TypeError, ValueError):
        return "?"
    for div, suf in ((1e9, "B"), (1e6, "M"), (1e3, "k")):
        if abs(n) >= div:
            return f"{n / div:.1f}{suf}".replace(".0", "")
    return f"{int(n)}"


class Bridge:
    def __init__(self, server=None, state=None, bus=BUS, tts: TTS | None = None, dials=DIALS,
                 poll_server: bool = True) -> None:
        from . import server as server_mod
        self.server = server or server_mod.HUB          # anything with .push(msg)
        self.state = state or server_mod.STATE
        self.bus = bus
        self.dials = dials
        audio_dir = getattr(self.server, "audio_dir", None)
        self.tts = tts or TTS(audio_dir=audio_dir, dials=dials)
        self.poll_server = poll_server
        self._q = None
        self._stop = threading.Event()
        self._threads: list[threading.Thread] = []
        self._chat_seen: collections.deque = collections.deque(maxlen=200)
        self._narr_seen: collections.deque = collections.deque(maxlen=50)
        self._voiced: dict[str, collections.deque] = collections.defaultdict(lambda: collections.deque(maxlen=50))
        self._last_big: dict | None = None
        self._status_cache: dict[str, dict] = {}
        self.scene = {"mode": "grid"}
        self._cine_token = 0
        # clock
        self._clock_lock = threading.Lock()
        self._started: float | None = None
        self._paused_at: float | None = None
        self._paused_total = 0.0
        self._frozen = 0.0
        self._total_s = float(dials.get("episode_minutes", 30)) * 60
        self._episode_n = 1
        if hasattr(self.state, "providers"):
            self.state.providers["tts"] = self.tts.status
        if hasattr(dials, "on_change"):
            dials.on_change(self._on_dial)

    # -- plumbing ---------------------------------------------------------------------------
    def push(self, msg: dict) -> None:
        try:
            self.server.push(msg)
        except Exception:
            pass

    def color(self, name: str) -> str:
        if name.lower() == "narrator":
            return SHOW.narrator.get("color", "#ffffff")
        for c in SHOW.characters:
            if c.name == name:
                return c.color
        return "#ffffff"

    @staticmethod
    def is_cast(name: str) -> bool:
        return any(c.name == name for c in SHOW.characters)

    @staticmethod
    def char_of(ev: dict) -> str:
        d = ev.get("data") or {}
        name = d.get("char") or (ev.get("players") or [None])[0] or ""
        return SHOW.char_name(name) if name else ""

    def start(self) -> "Bridge":
        self._q = self.bus.subscribe()
        for target, name in ((self._run_bus, "bridge-bus"), (self._run_clock, "bridge-clock")):
            t = threading.Thread(target=target, daemon=True, name=name)
            t.start()
            self._threads.append(t)
        if self.poll_server:
            t = threading.Thread(target=self._run_poll, daemon=True, name="bridge-poll")
            t.start()
            self._threads.append(t)
        from . import server as server_mod
        server_mod.on_control(self._control)          # dashboard: Load IndexTTS2 / Test voice
        return self

    def _control(self, action: str, body: dict):
        if self._stop.is_set():
            return None
        if action == "tts_warmup":
            def _warm():
                r = self.tts.start_indextts()
                self.bus.emit("system", "tts_warmup", text=f"IndexTTS2: {r}", data=r)
            threading.Thread(target=_warm, daemon=True, name="tts-warmup").start()
            return {"ok": True, "started": "loading IndexTTS2 in the background (~25 s)"}
        if action == "test_voice":
            ch = SHOW.char_name(body.get("char") or SHOW.characters[0].name)
            self.bus.emit("system", "inner_voice", players=[ch],
                          data={"char": ch, "text": body.get("text") or "Testing, one two. Can you hear me?",
                                "emotion": body.get("emotion") or "calm"})
            return {"ok": True}
        return None

    def stop(self) -> None:
        self._stop.set()
        from . import server as server_mod
        if self._control in server_mod._CONTROL:
            server_mod._CONTROL.remove(self._control)
        if self._q is not None:
            self.bus.unsubscribe(self._q)
        self.tts.stop()

    def set_episode_dir(self, ep_dir: Path | str) -> None:
        ep_dir = Path(ep_dir)
        audio = ep_dir / "audio"
        audio.mkdir(parents=True, exist_ok=True)
        if hasattr(self.server, "set_audio_dir"):
            self.server.set_audio_dir(audio)
        self.tts.set_audio_dir(audio)
        if hasattr(self.state, "episode"):
            self.state.episode.update(name=ep_dir.name, dir=str(ep_dir))

    def _run_bus(self) -> None:
        while not self._stop.is_set():
            try:
                ev = self._q.get(timeout=0.5)
            except Exception:
                continue
            try:
                self.handle(ev)
            except Exception as e:           # one bad event must never stop the overlay
                self.push({"kind": "debug", "error": f"{type(e).__name__}: {e}", "type": ev.get("type")})

    # -- the mapping ------------------------------------------------------------------------
    def handle(self, ev: dict) -> None:
        typ = ev.get("type", "")
        d = ev.get("data") or {}
        if typ in OWN_TYPES:
            return
        char = self.char_of(ev)
        fn = getattr(self, f"_on_{typ}", None)
        if fn:
            fn(ev, d, char)
        if ev.get("src") == "server":
            self._server_event(ev, d, char)

    # agents ---------------------------------------------------------------------------------
    def _on_inner_voice(self, ev, d, char):
        text = (d.get("text") or ev.get("text") or "").strip()
        if not text or not char:
            return
        emotion = d.get("emotion") or "calm"
        self.state.update_char(char, last_inner=text, last_emotion=emotion)
        voice = d.get("voice") is not False and self._voice_allowed(char)
        created = (ev.get("t") or time.time() * 1000) / 1000.0
        if getattr(self.server, "audio_dir", None):
            self.tts.set_audio_dir(self.server.audio_dir)
        self.tts.say(Line(char, text, emotion, voice=voice, created=created),
                     on_start=self._voice_start, on_skip=self._voice_skip, on_end=self._voice_end)

    def _voice_allowed(self, char: str) -> bool:
        per_min = float(self.dials.get("voice_lines_per_min", 3))
        now = time.time()
        q = self._voiced[char]
        while q and now - q[0] > 60:
            q.popleft()
        if per_min <= 0 or len(q) >= per_min:
            return False
        q.append(now)
        return True

    def _caption(self, line: Line, clip=None) -> None:
        if line.meta.get("kind") == "narrate":
            msg = {"kind": "narrate", "text": line.text,
                   "duration_ms": clip.duration_ms if clip else caption_ms(line.text)}
        else:
            msg = {"kind": "caption", "char": line.char, "text": line.text, "emotion": line.emotion,
                   "color": self.color(line.char),
                   "duration_ms": clip.duration_ms if clip else caption_ms(line.text)}
        if clip:
            msg["audio_url"] = clip.url
        self.push(msg)

    def _voice_start(self, line: Line, clip) -> None:
        self._caption(line, clip)
        st = self.state.char(line.char) if line.char != "Narrator" else {}
        if st is not None and line.char != "Narrator":
            st["voice_lines"] = st.get("voice_lines", 0) + 1
        self.bus.emit("tts", "tts_start", players=[line.char] if self.is_cast(line.char) else [],
                      text=line.text, data={"char": line.char, "clip": clip.url,
                                            "duration_ms": clip.duration_ms, "engine": clip.engine})

    def _voice_end(self, line: Line, clip) -> None:
        self.bus.emit("tts", "tts_end", players=[line.char] if self.is_cast(line.char) else [],
                      data={"char": line.char, "clip": clip.url, "duration_ms": clip.duration_ms})

    def _voice_skip(self, line: Line, reason: str) -> None:
        self._caption(line, None)

    def _on_say(self, ev, d, char):
        self._chat(char, d.get("text") or ev.get("text") or "")

    def _on_chat(self, ev, d, char):
        if ev.get("src") == "server":
            self._chat(char, d.get("message") or d.get("text") or "")

    def _chat(self, char: str, text: str) -> None:
        text = (text or "").strip()
        if not text or not self.is_cast(char):
            return
        if text.lower().startswith(f"{char.lower()}:"):
            text = text[len(char) + 1:].strip()
        key = (char, " ".join(text.lower().split()))
        now = time.time()
        if any(k == key and now - t < CHAT_DEDUPE_S for k, t in self._chat_seen):
            return
        self._chat_seen.append((key, now))
        self.state.update_char(char, last_say=text)
        self.push({"kind": "chat", "char": char, "text": text, "color": self.color(char)})

    def _on_agent_turn(self, ev, d, char):
        if not char:
            return
        fields: dict[str, Any] = {"stuck": False, "stuck_s": 0}
        if d.get("goal"):
            fields["goal"] = d["goal"]
        if d.get("latency_s") is not None:
            fields["latency_s"] = round(float(d["latency_s"]), 1)
        st = self.state.char(char)
        if d.get("cost_usd") is not None:
            fields["cost_usd"] = round(float(st.get("cost_usd") or 0) + float(d["cost_usd"]), 4)
        self.state.update_char(char, **fields)
        self.state.note_turn(char, (ev.get("t") or time.time() * 1000) / 1000.0)
        if d.get("goal"):
            self._status(char, goal=d["goal"])

    def _on_agent_action(self, ev, d, char):
        if not char:
            return
        args = d.get("args") or {}
        argtxt = ", ".join(f"{v}" for v in args.values())[:40] if isinstance(args, dict) else str(args)[:40]
        res = str(d.get("result") or "")[:80]
        self.state.update_char(char, last_action=f"{d.get('tool', '?')}({argtxt}) - {res}".strip(" -"),
                               last_action_ok=d.get("ok"))

    def _on_agent_stuck(self, ev, d, char):
        if char:
            self.state.update_char(char, stuck=True, stuck_s=d.get("seconds", 0))

    def _on_cost(self, ev, d, char):
        cost = {k: d.get(k) for k in ("usd", "turns", "per_hour") if d.get(k) is not None}
        self.state.cost.update(d)
        self.push({"kind": "cost", **cost})

    # director / scenes ----------------------------------------------------------------------
    def _on_director(self, ev, d, char):
        dec = str(d.get("decision") or "").upper()
        rec = {"t": ev.get("t"), "decision": dec, "target": d.get("target"), "shot": d.get("shot"),
               "score": d.get("score"), "reason": d.get("reason"), "line": d.get("line")}
        self.state.director.append(rec)
        target = SHOW.char_name(d["target"]) if d.get("target") else None
        if dec == "CINEMATIC":
            self.cinematic(target, d.get("lower_third") or d.get("line"), d.get("seconds"))
        elif dec in ("CUT_TO", "CUT"):
            if self.scene.get("mode") != "cinematic":
                self._scene({"mode": "grid", "focus": target})
        elif dec == "HOLD":
            if self.scene.get("mode") == "grid" and self.scene.get("focus"):
                self._scene({"mode": "grid", "focus": None})
        elif dec == "NARRATE" and d.get("line"):
            self.narrate(d["line"])

    def _on_cinematic_start(self, ev, d, char):
        target = SHOW.char_name(d["target"]) if d.get("target") else (char or None)
        self.cinematic(target, d.get("lower_third") or d.get("line"), d.get("seconds"))

    def _on_cinematic_end(self, ev, d, char):
        self.end_cinematic()

    def _on_scene(self, ev, d, char):
        mode = d.get("mode") or "grid"
        msg = {"mode": mode}
        for k in ("target", "lower_third", "focus"):
            if d.get(k) is not None:
                msg[k] = d[k]
        self._scene(msg)

    def _on_narrate(self, ev, d, char):
        self.narrate(d.get("text") or ev.get("text") or "", voice=d.get("voice") is not False)

    def _scene(self, msg: dict) -> None:
        self.scene = dict(msg)
        self.state.scene = dict(msg)
        self.push({"kind": "scene", **msg})
        self.push({"kind": "letterbox", "on": msg.get("mode") == "cinematic"})

    def lower_third_for(self, target: str | None) -> dict | None:
        big = self._last_big
        if (big and time.time() * 1000 - (big.get("t") or 0) < 45000
                and (not target or target in (big.get("players") or []))):
            return headline(big)
        if target:
            return {"title": target.upper(), "subtitle": "", "color": self.color(target)}
        return None

    def cinematic(self, target: str | None, lower_third=None, seconds=None) -> None:
        if isinstance(lower_third, str) and lower_third:
            title, _, sub = lower_third.partition(" — ")
            lt = {"title": title, "subtitle": sub}
        elif isinstance(lower_third, dict):
            lt = lower_third
        else:
            lt = self.lower_third_for(target)
        if lt is not None and target and not lt.get("color"):
            lt["color"] = self.color(target)
        msg = {"mode": "cinematic", "target": target, "lower_third": lt}
        self._scene(msg)
        # safety net: if cinematic_end is lost, never stay letterboxed for ever
        self._cine_token += 1
        tok = self._cine_token
        limit = float(seconds or 12) + 6
        t = threading.Timer(limit, lambda: self._cine_timeout(tok))
        t.daemon = True
        t.start()

    def _cine_timeout(self, tok: int) -> None:
        if tok == self._cine_token and self.scene.get("mode") == "cinematic":
            self.end_cinematic()

    def end_cinematic(self) -> None:
        self._cine_token += 1
        self._scene({"mode": "grid"})

    def narrate(self, text: str, voice: bool = True) -> None:
        text = (text or "").strip()
        if not text:
            return
        key = " ".join(text.lower().split())
        now = time.time()
        if any(k == key and now - t < 30 for k, t in self._narr_seen):
            return
        self._narr_seen.append((key, now))
        self.tts.say(Line("Narrator", text, "calm", voice=voice, priority=0, meta={"kind": "narrate"}),
                     on_start=self._voice_start, on_skip=self._voice_skip, on_end=self._voice_end)

    # episode / clock ------------------------------------------------------------------------
    def _on_episode_start(self, ev, d, char):
        minutes = float(d.get("minutes") or self.dials.get("episode_minutes", 30))
        self._episode_n = int(d.get("episode") or self._episode_n)
        self.start_clock(minutes * 60)
        if hasattr(self.state, "episode"):
            self.state.episode["state"] = "running"
        self.push({"kind": "title_card", "title": SHOW.title, "subtitle": f"EPISODE {self._episode_n:02d}",
                   "seconds": float(d.get("title_seconds") or 7)})
        self._scene({"mode": "intro"})

    def _on_episode_end(self, ev, d, char):
        self.stop_clock()
        if hasattr(self.state, "episode"):
            self.state.episode["state"] = "ended"
        self.push({"kind": "title_card", "title": "TIME'S UP", "subtitle": d.get("reason") or "", "seconds": 6})

    def _on_profile_ready(self, ev, d, char):
        if char and self.is_cast(char) and d.get("first_join", True):
            self.intro_char(char)

    def intro_char(self, char: str) -> None:
        c = next((c for c in SHOW.characters if c.name == char), None)
        if not c:
            return
        self._scene({"mode": "intro", "target": char,
                     "lower_third": {"title": char.upper(), "subtitle": c.archetype.upper(),
                                     "color": c.color, "slot": c.slot}})

    def start_clock(self, total_s: float) -> None:
        with self._clock_lock:
            self._started = time.time()
            self._paused_total = 0.0
            self._paused_at = time.time() if self.dials.get("paused", False) else None
            self._total_s = total_s
        self._push_clock()

    def stop_clock(self) -> None:
        el = self.elapsed()
        with self._clock_lock:
            self._started = None
            self._frozen = el
        self._push_clock()

    def elapsed(self) -> float:
        with self._clock_lock:
            if self._started is None:
                return getattr(self, "_frozen", 0.0)
            end = self._paused_at or time.time()
            return max(0.0, end - self._started - self._paused_total)

    def _on_dial(self, key: str, value) -> None:
        if key != "paused":
            return
        with self._clock_lock:
            if value and self._paused_at is None:
                self._paused_at = time.time()
            elif not value and self._paused_at is not None:
                self._paused_total += time.time() - self._paused_at
                self._paused_at = None
        self._push_clock()

    def _push_clock(self) -> None:
        running = self._started is not None and self._paused_at is None
        clock = {"elapsed_s": round(self.elapsed(), 1), "total_s": self._total_s, "running": running,
                 "episode": self._episode_n}
        self.state.clock = dict(clock)
        self.push({"kind": "clock", **clock})

    def _run_clock(self) -> None:
        while not self._stop.wait(1.0):
            if self._started is not None:
                self._push_clock()

    # server events --------------------------------------------------------------------------
    def _server_event(self, ev, d, char) -> None:
        typ = ev.get("type")
        drama = float(ev.get("drama") or 0)
        if drama >= 6:
            self._last_big = ev
        if char and self.is_cast(char):
            if typ == "join":
                self.state.update_char(char, online=True)
            elif typ == "quit":
                self.state.update_char(char, online=False)
            elif typ == "island_change" and d.get("to"):
                self._status(char, island=d["to"])
            elif typ == "zone_change" and d.get("to"):
                self.state.update_char(char, zone=d["to"])
            elif typ == "coins_milestone" and d.get("purse") is not None:
                self._status(char, purse=d["purse"])
            elif typ == "near_death" and d.get("health") is not None:
                self._status(char, health=d["health"], maxHealth=d.get("max"))
        if drama >= TICKER_MIN_DRAMA and typ not in ("chat", "mark") and (ev.get("text") or ""):
            self.push({"kind": "ticker", "text": ev["text"], "drama": drama, "type": typ,
                       "char": char if self.is_cast(char) else None,
                       "color": self.color(char) if self.is_cast(char) else None})

    def _status(self, char: str, **fields) -> None:
        fields = {k: v for k, v in fields.items() if v is not None}
        cur = self._status_cache.setdefault(char, {})
        changed = {k: v for k, v in fields.items() if cur.get(k) != v}
        if not changed:
            return
        cur.update(changed)
        self.state.update_char(char, **changed)
        c = next((c for c in SHOW.characters if c.name == char), None)
        self.push({"kind": "status", "char": char, "color": self.color(char),
                   "archetype": c.archetype if c else "", "slot": c.slot if c else 0, **changed})

    def seed_status(self) -> None:
        """Push a plate for every character so the overlay is never empty."""
        for c in SHOW.characters:
            self.push({"kind": "status", "char": c.name, "color": c.color, "archetype": c.archetype,
                       "slot": c.slot})

    # ShowControl poller ---------------------------------------------------------------------
    def _get(self, path: str, timeout: float = 2.0):
        url = f"http://127.0.0.1:{SHOW.ports['showcontrol']}{path}"
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8"))

    def _run_poll(self) -> None:
        n = 0
        while not self._stop.is_set():
            delay = 3.0
            try:
                players = self._get("/players")
                for p in players if isinstance(players, list) else []:
                    char = SHOW.char_name(p.get("name", ""))
                    if not self.is_cast(char):
                        continue
                    self.state.update_char(char, online=bool(p.get("online")))
                    self._status(char, island=p.get("island"), purse=p.get("purse"),
                                 health=_num(p.get("health")), maxHealth=_num(p.get("maxHealth")))
                if n % 4 == 0:
                    for c in SHOW.characters:
                        try:
                            st = self._get(f"/state/{c.account}")
                        except (urllib.error.URLError, OSError, ValueError):
                            continue
                        lvl = level_of(st)
                        if lvl is not None:
                            self._status(c.name, level=lvl)
                n += 1
            except (urllib.error.URLError, OSError, ValueError):
                delay = 10.0                      # ShowControl not up yet: back off quietly
            self._stop.wait(delay)


def _num(v):
    try:
        return round(float(v), 1)
    except (TypeError, ValueError):
        return None


def level_of(state: dict) -> float | None:
    """The plate's LV: SkyBlock level if ShowControl exposes one, else the skill average."""
    if not isinstance(state, dict):
        return None
    for k in ("sb_level", "level", "skyblock_level"):
        if isinstance(state.get(k), (int, float)):
            return state[k]
    skills = state.get("skills") or {}
    lv = [s.get("level", 0) for s in skills.values() if isinstance(s, dict)]
    return round(sum(lv) / len(lv), 1) if lv else None


def headline(ev: dict) -> dict:
    """A cinematic lower third for a server event: {'title': 'BOSS SPAWNED', 'subtitle': name}."""
    typ = ev.get("type", "")
    d = ev.get("data") or {}
    who = (ev.get("players") or [""])[0]
    title = HEADLINES.get(typ, typ.replace("_", " ").upper())
    if typ == "boss_spawn":
        sub = d.get("name") or d.get("mob_id") or ev.get("text", "")
    elif typ == "near_death":
        sub = f"{who} at {int(float(d.get('health', 0)))} HP"
    elif typ == "skill_level_up":
        sub = f"{who} - {str(d.get('skill', '')).title()} {d.get('level', '')}"
    elif typ == "item_gain":
        sub = f"{who} - {d.get('item_id', '')} ({d.get('rarity', '')})"
    elif typ == "mob_kill":
        sub = f"{who} killed {d.get('mob_name') or d.get('mob_id', '')}"
    else:
        sub = ev.get("text", "")
    return {"title": title, "subtitle": sub}


_BRIDGE: Bridge | None = None


def start(server=None, poll_server: bool = True) -> Bridge:
    global _BRIDGE
    if _BRIDGE is None:
        _BRIDGE = Bridge(server=server, poll_server=poll_server).start()
        _BRIDGE.seed_status()
    return _BRIDGE


# ---------------------------------------------------------------------------------------------
# demo: a scripted fake episode on a loop (py -3 -m show.server --demo)
# ---------------------------------------------------------------------------------------------
def run_demo(bridge: Bridge, voice: bool = False, loop: bool = True) -> None:
    from . import server as server_mod
    bus = bridge.bus
    K, R, M, Y = (c.name for c in SHOW.characters[:4])

    def ctl(action, body):          # make the dashboard buttons visibly do something in the demo
        if action == "cinematic":
            bridge.cinematic(body.get("char") or body.get("target") or K, None, 8)
            return {"ok": True, "demo": True}
        if action == "grid":
            bridge.end_cinematic()
            return {"ok": True, "demo": True}
        if action in ("start", "stop", "kill_agent", "restart_agent"):
            return {"ok": True, "demo": True, "note": "demo mode - nothing to " + action}
        return None
    server_mod.on_control(ctl)

    def ev(src, typ, players=(), drama=0, text="", **data):
        bus.emit(src, typ, players=list(players), drama=drama, text=text, data=data)

    def think(ch, text, emo="calm"):
        bus.emit("agent", "inner_voice", players=[ch],
                 data={"char": ch, "text": text, "emotion": emo, "voice": voice})

    def say(ch, text):
        bus.emit("agent", "say", players=[ch], text=f"{ch}: {text}", data={"char": ch, "text": text})

    def turn(ch, goal, lat=4.2):
        bus.emit("agent", "agent_turn", players=[ch],
                 data={"char": ch, "goal": goal, "actions": [], "latency_s": lat, "cost_usd": 0.012})

    def act(ch, tool, args, result, ok=True):
        bus.emit("agent", "agent_action", players=[ch],
                 data={"char": ch, "tool": tool, "args": args, "result": result, "ok": ok})

    def st(ch, **f):
        bridge._status(ch, **f)

    def w(s):
        time.sleep(s)

    while True:
        bridge.seed_status()
        for c, (lv, purse, hp) in zip((K, R, M, Y), ((1, 0, 100), (1, 0, 100), (1, 0, 100), (1, 0, 100))):
            st(c, level=lv, purse=purse, island="Private Island", health=hp, maxHealth=100, goal="")
        ev("system", "episode_start", minutes=30, episode=1)
        w(8)
        for ch, line, emo, goal in (
            (K, "Huh?! Where the hell am I? ...Whatever. Something here is gonna get punched.", "confused", "find something to fight"),
            (R, "A floating island. No memory. Fascinating... let's read every menu first.", "calm", "read the SkyBlock menu"),
            (M, "Hehehe... free land? Nobody's charging rent? This is a business opportunity.", "smug", "find something to sell"),
            (Y, "So quiet up here. I like it. I wonder who else woke up.", "calm", "look around"),
        ):
            bridge.intro_char(ch)
            w(1.2)
            think(ch, line, emo)
            turn(ch, goal)
            w(5.5)
        bridge._scene({"mode": "grid"})
        w(1.5)
        act(K, "fight", {"target": "Zombie", "count": 5}, "killed 5 Graveyard Zombies")
        ev("server", "mob_kill", [K], 3, "Kaito killed a Graveyard Zombie", mob_id="GRAVEYARD_ZOMBIE", coins=12)
        st(K, purse=140, health=72)
        say(K, "LETS GOOOO FIRST BLOOD")
        w(2.5)
        say(M, "bro thats a zombie not a boss lmaooo")
        w(2)
        think(R,"Kaito is wasting daylight on zombies. Wheat compounds. Zombies don't.", "smug")
        turn(R, "farm wheat for minion", 3.1)
        act(R, "farm", {"crop": "wheat", "count": 64}, "harvested 64 wheat")
        ev("server", "skill_level_up", [R], 3, "Ren reached Farming II", skill="FARMING", level=2)
        st(R, level=1.3, purse=55, island="The Hub")
        w(4.5)
        ev("server", "first_of_kind", [M], 4, "Mochi is the first to reach the Hub", what="first to the hub")
        st(M, island="The Hub", purse=0)
        say(M, "who wants to buy a slightly used stick. 50 coins. no refunds")
        w(3)
        think(M, "If I sell Kaito his own sword back, that's technically profit.", "happy")
        turn(M, "flip sticks at the bazaar")
        bus.emit("system", "cost", data={"usd": 0.84, "turns": 61, "per_hour": 6.2})
        w(5)
        say(Y, "okay")
        w(1.5)
        think(Y, "They're so loud. It's cute. I'll finish this before they notice.", "calm")
        turn(Y, "get iron pickaxe", 5.6)
        act(Y, "mine", {"block": "iron_ore", "count": 20}, "mined 20 iron ore")
        st(Y, level=1.6, purse=320, island="Gold Mine")
        w(5)
        ev("server", "item_gain", [Y], 4, "Yuki found a RARE Golden Tooth", item_id="GOLDEN_TOOTH", rarity="RARE")
        ev("server", "near_death", [K], 5, "Kaito is at 9% health!", health=9, max=100)
        st(K, health=9)
        think(K, "I can't lose here... not like this. Not in front of Ren.", "scared")
        w(5)
        bus.emit("director", "director", data={"decision": "CUT_TO", "target": K, "score": 5, "reason": "near death"})
        w(3)
        ev("server", "boss_spawn", [Y], 8, "A Nullshade Seraph has spawned near Yuki", mob_id="NULLSHADE_SERAPH",
           name="Nullshade Seraph")
        bus.emit("director", "director", data={"decision": "CINEMATIC", "target": Y, "shot": "orbit",
                                               "seconds": 9, "score": 8, "reason": "boss spawn"})
        bus.emit("director", "cinematic_start", players=[Y], data={"target": Y, "shot": "orbit", "seconds": 9})
        w(1.5)
        think(Y, "Oh. A big one. Don't worry, everyone. I'll handle it.", "determined")
        w(5)
        say(K, "YUKI RUN??")
        w(3)
        bus.emit("director", "cinematic_end", players=[Y], data={"target": Y})
        w(1)
        ev("server", "mob_kill", [Y], 7, "Yuki killed the Nullshade Seraph", mob_id="NULLSHADE_SERAPH", boss=True, coins=2500)
        st(Y, purse=2820, level=2.1)
        say(Y, "done :)")
        w(2.5)
        say(M, "...yuki can i borrow 2000 coins. for an investment")
        w(3)
        bridge.narrate("Twelve minutes in, and the quiet one already has the biggest kill. Nobody saw it coming.",
                       voice=voice)
        bus.emit("system", "cost", data={"usd": 1.37, "turns": 98, "per_hour": 6.4})
        w(8)
        if not loop:
            return
