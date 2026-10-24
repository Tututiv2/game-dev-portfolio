"""The edit: turn an episode's events.jsonl into things that cut editing time (build prompt §4.6).

    py -3 run.py edit episodes/2026-10-04_ep1

Reads   events.jsonl  +  recording.json ({"rec_start_ms": ..., "record_path": ...}, written the moment
        OBS reported the recording active; without it the first event is taken as 0:00).
Writes  chapters.txt      YouTube chapters: first at 0:00, >= 10 s apart, <= 12, punchy titles
        best_moments.md   ranked by score: timestamp, who, what, suggested clip in/out
        highlights.edl    CMX3600 EDL of the top moments (Resolve / Premiere), source TC = recording
        shorts.md         60 s windows with the highest drama density + hook line + title idea
        transcript.md     every chat line, inner voice and narration, in order, timestamped
        cost.json         spend/turns/latency (meter snapshot if given, else from the cost events)
Timestamps are seconds into the RECORDING, so they line up with the file OBS wrote.
"""
from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

from .director import score_event

SKIP_TYPES = {"agent_turn", "agent_action", "cost", "scene", "tts_start", "tts_end", "director",
              "cinematic_start", "cinematic_end", "status", "clock", "inner_voice", "narrate"}


# ----------------------------------------------------------------------------- io + time
def load_events(path: Path) -> list[dict]:
    evs = []
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    evs.append(json.loads(line))
                except ValueError:
                    continue
    except OSError:
        return []
    evs.sort(key=lambda e: (e.get("t", 0), e.get("bus_id", 0)))
    return evs


def fmt_ts(s: float) -> str:
    s = max(0, int(s))
    h, m, sec = s // 3600, (s % 3600) // 60, s % 60
    return f"{h}:{m:02d}:{sec:02d}" if h else f"{m}:{sec:02d}"


def timecode(s: float, fps: int) -> str:
    frames = max(0, int(round(s * fps)))
    f = frames % fps
    total = frames // fps
    return f"{total // 3600:02d}:{(total % 3600) // 60:02d}:{total % 60:02d}:{f:02d}"


def title_case(s: str) -> str:
    small = {"a", "an", "the", "to", "of", "in", "on", "at", "vs", "and", "for"}
    words = s.split()
    out = []
    for i, w in enumerate(words):
        if w.isupper() and len(w) > 1:
            out.append(w)
        elif i and w.lower() in small:
            out.append(w.lower())
        else:
            out.append(w[:1].upper() + w[1:])
    return " ".join(out)


# ----------------------------------------------------------------------------- moments
def punchy(ev: dict, headline: str, firsts: set) -> str:
    t = ev.get("type", "")
    d = ev.get("data") or {}
    ps = ev.get("players") or []
    p0 = ps[0] if ps else "Someone"
    if t == "meeting":
        if d.get("all") or len(ps) >= 4:
            return "The Squad Assembles"
        return f"{ps[0]} Meets {ps[1]}" if len(ps) >= 2 else "First Contact"
    if t == "boss_spawn":
        return f"BOSS: {d.get('name') or d.get('mob_id') or 'It Wakes'}"
    if t == "mob_kill" and d.get("boss"):
        return f"{p0} Slays {d.get('mob_name', 'the Boss')}"
    if t == "death":
        if "death" not in firsts:
            firsts.add("death")
            return f"First Blood: {p0} Dies"
        return f"{p0} Dies Again"
    if t == "near_death":
        return f"{p0} Almost Dies"
    if t == "first_of_kind":
        what = str(d.get("what") or ev.get("text") or "")
        if "hub" in what.lower():
            return f"First to the Hub: {p0}"
        return f"{p0}: {what}"[:60]
    if t == "item_gain":
        rar = str(d.get("rarity", "")).upper()
        return f"{p0} Pulls a {rar or 'New'} {str(d.get('item_id', 'Drop')).replace('_', ' ').title()}"
    if t in ("chat", "say"):
        others = [n for n in _names() if n != p0 and re.search(rf"\b{re.escape(n)}\b", _text(ev), re.I)]
        if others:
            return f"{p0} vs {others[0]}"
        return f"{p0} Loses It"
    if t == "agent_stuck":
        return f"{p0} Gets Lost"
    if t == "skill_level_up":
        return f"{p0} Hits {str(d.get('skill', 'Skill')).title()} {d.get('level', '')}".strip()
    if t == "island_change":
        return f"{p0} Reaches the {str(d.get('to', 'Hub')).title()}"
    if t in ("join", "profile_ready"):
        return f"{p0} Wakes Up"
    if t == "episode_end":
        return "Time's Up"
    if t in ("mark", "replay_mark"):
        return title_case(str(d.get("label") or ev.get("text") or "Moment"))[:60]
    return title_case(headline)[:60]


_NAMES: list[str] | None = None


def _names() -> list[str]:
    global _NAMES
    if _NAMES is None:
        try:
            from .config import SHOW
            _NAMES = [c.name for c in SHOW.characters]
        except Exception:
            _NAMES = []
    return _NAMES


def _text(ev: dict) -> str:
    d = ev.get("data") or {}
    return str(d.get("message") or d.get("text") or ev.get("text") or "")


def build_moments(events: list[dict], rec_start_ms: int) -> list[dict]:
    firsts: set = set()
    out = []
    chat_seen: list[tuple[str, str, float]] = []
    for ev in events:
        t = ev.get("type", "")
        if t in ("chat", "say"):      # the agent's say and ShowControl's chat echo are one line
            who = ((ev.get("players") or [""])[0]).lower()
            key = re.sub(r"\W+", "", _text(ev).lower())
            ts = ev.get("t", 0) / 1000.0
            if any(w == who and k == key and abs(ts - r) <= 10 for w, k, r in chat_seen):
                continue
            chat_seen.append((who, key, ts))
        if t in SKIP_TYPES and t != "replay_mark":
            continue
        if ev.get("src") == "director" and t not in ("meeting", "replay_mark"):
            continue
        score, reason, head = score_event(ev)
        rel = (ev.get("t", rec_start_ms) - rec_start_ms) / 1000.0
        if t == "replay_mark":
            d = ev.get("data") or {}
            score = float(d.get("score") or ev.get("drama") or 6)
            start = (float(d.get("start", 0)) * 1000 - rec_start_ms) / 1000.0 if d.get("start") else rel - 20
            end = (float(d.get("end", 0)) * 1000 - rec_start_ms) / 1000.0 if d.get("end") else rel + 10
        else:
            start, end = rel - 12, rel + 8
        if score < 3 and t not in ("episode_start", "episode_end"):
            continue
        if t == "replay_mark":
            # the director marks the moment it just scored: widen that moment's clip instead of listing it twice
            twin = next((m for m in reversed(out) if abs(m["rel"] - rel) <= 30 and m["type"] != "replay_mark"
                         and m["score"] >= score - 0.5), None)
            if twin:
                twin["clip_in"] = max(0.0, min(twin["clip_in"], start))
                twin["clip_out"] = max(twin["clip_out"], end)
                continue
        out.append({"t": ev.get("t"), "rel": rel, "score": round(score, 1), "type": t,
                    "who": ", ".join(ev.get("players") or []) or "-", "what": head,
                    "title": punchy(ev, head, firsts), "reason": reason,
                    "clip_in": max(0.0, start), "clip_out": max(0.0, end)})
    return out


# ----------------------------------------------------------------------------- outputs
def chapters(moments: list[dict], duration: float, max_n: int = 12, min_gap: float = 10.0) -> list[tuple[float, str]]:
    gap = max(min_gap, min(90.0, duration / (max_n * 2.0))) if duration > 0 else min_gap
    chosen: list[tuple[float, str, float]] = [(0.0, "Cold Open: Where Am I?", 99.0)]
    cands = [m for m in moments if m["score"] >= 4 and m["type"] not in ("episode_start", "replay_mark")]
    wake = next((m for m in moments if m["type"] in ("profile_ready", "join")), None)
    if wake and wake["rel"] >= gap:
        chosen.append((wake["rel"], "Wake-Up Call", 50.0))
    end = next((m for m in moments if m["type"] == "episode_end"), None)
    for m in sorted(cands, key=lambda m: -m["score"]):
        if len(chosen) >= max_n - (1 if end else 0):
            break
        if m["rel"] < min_gap or (duration and m["rel"] > duration - min_gap):
            continue
        if all(abs(m["rel"] - c[0]) >= gap for c in chosen):
            if any(c[1] == m["title"] for c in chosen):
                continue
            chosen.append((m["rel"], m["title"], m["score"]))
    if end and all(abs(end["rel"] - c[0]) >= min_gap for c in chosen):
        chosen.append((end["rel"], "Time's Up: The Last Words", 50.0))
    chosen.sort(key=lambda c: c[0])
    # enforce >= min_gap strictly after sorting (YouTube rejects closer chapters)
    final: list[tuple[float, str]] = []
    for rel, title, _ in chosen:
        if not final or rel - final[-1][0] >= min_gap:
            final.append((rel, title))
    return final


def edl(moments: list[dict], *, title: str, fps: int = 30, clip_name: str = "", top: int = 8,
        max_total_s: float = 180.0) -> str:
    picks = sorted(moments, key=lambda m: -m["score"])[:top]
    wins = sorted([[m["clip_in"], m["clip_out"], m] for m in picks if m["clip_out"] > m["clip_in"]],
                  key=lambda w: w[0])
    merged: list[list] = []
    for w in wins:
        if merged and w[0] <= merged[-1][1] + 1.0:
            merged[-1][1] = max(merged[-1][1], w[1])
            merged[-1][2] = merged[-1][2] if merged[-1][2]["score"] >= w[2]["score"] else w[2]
        else:
            merged.append(list(w))
    lines = [f"TITLE: {title}", "FCM: NON-DROP FRAME", ""]
    rec = 3600.0           # record side starts at 01:00:00:00, the NLE convention
    total = 0.0
    for i, (a, b, m) in enumerate(merged, 1):
        if total >= max_total_s:
            break
        dur = b - a
        lines.append(f"{i:03d}  AX       AA/V  C        {timecode(a, fps)} {timecode(b, fps)} "
                     f"{timecode(rec, fps)} {timecode(rec + dur, fps)}")
        if clip_name:
            lines.append(f"* FROM CLIP NAME: {clip_name}")
        lines.append(f"* COMMENT: [{m['score']}] {m['title']} ({m['who']})")
        lines.append("")
        rec += dur
        total += dur
    return "\n".join(lines) + "\n"


def shorts(moments: list[dict], events: list[dict], rec_start_ms: int, n: int = 5, window: float = 60.0) -> list[dict]:
    starts = sorted({max(0.0, m["rel"] - 15) for m in moments})
    scored = []
    for s in starts:
        inside = [m for m in moments if s <= m["rel"] < s + window and m["type"] not in ("episode_start", "episode_end")]
        if not inside:
            continue
        density = sum(m["score"] for m in inside) + 2 * max(m["score"] for m in inside)
        scored.append((density, s, inside))
    out: list[dict] = []
    for density, s, inside in sorted(scored, key=lambda x: -x[0]):
        if len(out) >= n:
            break
        if any(abs(s - o["start"]) < window for o in out):
            continue
        best = max(inside, key=lambda m: m["score"])
        hook = None
        for ev in events:
            rel = (ev.get("t", 0) - rec_start_ms) / 1000.0
            if s <= rel < s + window and ev.get("type") in ("inner_voice", "say", "chat"):
                txt = _text(ev)
                if txt and (hook is None or len(txt) > len(hook[1])) and len(txt) <= 140:
                    hook = ((ev.get("players") or ["?"])[0], txt)
        out.append({"start": s, "end": s + window, "density": round(density, 1), "best": best,
                    "moments": inside,
                    "hook": f"{hook[0]}: \"{hook[1]}\"" if hook else best["title"],
                    "title": f"AI {best['title']} in Hypixel SkyBlock"[:95]})
    return sorted(out, key=lambda o: -o["density"])


def transcript(events: list[dict], rec_start_ms: int) -> str:
    lines = ["# Transcript", "", "_Chat (typed in game), **thinks** (inner voice, heard), narrator._", ""]
    seen: list[tuple[str, str, float]] = []
    for ev in events:
        t = ev.get("type")
        if t not in ("chat", "say", "inner_voice", "narrate"):
            continue
        rel = (ev.get("t", rec_start_ms) - rec_start_ms) / 1000.0
        who = (ev.get("players") or [None])[0] or (ev.get("data") or {}).get("char") or "?"
        txt = _text(ev).strip()
        if not txt:
            continue
        if t in ("chat", "say"):
            key = (who.lower(), re.sub(r"\W+", "", txt.lower()))
            if any(k == key[0] and v == key[1] and abs(rel - r) <= 10 for k, v, r in seen):
                continue
            seen.append((key[0], key[1], rel))
            seen = seen[-50:]
            lines.append(f"- `[{fmt_ts(rel)}]` **{who}** (chat): {txt}")
        elif t == "inner_voice":
            emo = (ev.get("data") or {}).get("emotion") or ""
            lines.append(f"- `[{fmt_ts(rel)}]` **{who}** *thinks{(' (' + emo + ')') if emo else ''}*: _{txt}_")
        else:
            lines.append(f"- `[{fmt_ts(rel)}]` **NARRATOR**: _{txt}_")
    return "\n".join(lines) + "\n"


def cost_report(events: list[dict], meter: dict | None, duration: float) -> dict:
    per: dict[str, dict] = {}
    for ev in events:
        if ev.get("type") == "agent_turn":
            who = (ev.get("players") or [None])[0] or (ev.get("data") or {}).get("char") or "?"
            p = per.setdefault(who, {"turns": 0, "latency_s": 0.0})
            p["turns"] += 1
            p["latency_s"] += float((ev.get("data") or {}).get("latency_s") or 0)
    for p in per.values():
        p["avg_latency_s"] = round(p["latency_s"] / p["turns"], 2) if p["turns"] else 0
        p["latency_s"] = round(p["latency_s"], 1)
    last_cost = next((e.get("data") for e in reversed(events) if e.get("type") == "cost"), None)
    return {"duration_s": round(duration, 1), "agents_from_events": per,
            "director_decisions": sum(1 for e in events if e.get("type") == "director"),
            "voice_lines": sum(1 for e in events if e.get("type") == "tts_start"),
            "meter": meter or last_cost or {}}


def generate(ep_dir: Path | str, *, meter: dict | None = None, fps: int = 30) -> dict[str, Path]:
    ep_dir = Path(ep_dir)
    events = load_events(ep_dir / "events.jsonl")
    rec: dict[str, Any] = {}
    try:
        rec = json.loads((ep_dir / "recording.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        pass
    rec_start = int(rec.get("rec_start_ms") or (events[0]["t"] if events else 0))
    end_t = max((e.get("t", 0) for e in events), default=rec_start)
    duration = max(0.0, (end_t - rec_start) / 1000.0)
    clip_name = Path(rec.get("record_path") or "").name
    ep_name = ep_dir.name
    try:
        from .config import SHOW
        show_title = SHOW.title
    except Exception:
        show_title = "Can 4 AIs Beat Hypixel SkyBlock?"
    moments = build_moments(events, rec_start)
    out: dict[str, Path] = {}

    ch = chapters(moments, duration)
    p = ep_dir / "chapters.txt"
    p.write_text("\n".join(f"{fmt_ts(r)} {t}" for r, t in ch) + "\n", encoding="utf-8")
    out["chapters"] = p

    ranked = sorted([m for m in moments if m["type"] not in ("episode_start",)], key=lambda m: -m["score"])[:20]
    md = [f"# Best moments - {ep_name}", "",
          f"Recording: `{clip_name or '(no recording file recorded)'}` - timestamps are into that file.", "",
          "| # | Time | Score | Who | What | Clip in -> out |", "|---|---|---|---|---|---|"]
    for i, m in enumerate(ranked, 1):
        md.append(f"| {i} | {fmt_ts(m['rel'])} | {m['score']} | {m['who']} | **{m['title']}** - {m['what']} "
                  f"| {fmt_ts(m['clip_in'])} -> {fmt_ts(m['clip_out'])} |")
    p = ep_dir / "best_moments.md"
    p.write_text("\n".join(md) + "\n", encoding="utf-8")
    out["best_moments"] = p

    p = ep_dir / "highlights.edl"
    p.write_text(edl(moments, title=f"AISky {ep_name} highlights", fps=fps, clip_name=clip_name), encoding="utf-8")
    out["edl"] = p

    sh = shorts(moments, events, rec_start)
    md = [f"# Shorts candidates - {ep_name}", "", "60-second windows with the most drama per second.", ""]
    for i, s in enumerate(sh, 1):
        md += [f"## {i}. {fmt_ts(s['start'])} -> {fmt_ts(s['end'])}  (density {s['density']})",
               f"- **Title idea:** {s['title']}", f"- **Hook (first 2 s):** {s['hook']}",
               "- **Beats:** " + "; ".join(f"{fmt_ts(m['rel'])} {m['title']}" for m in s["moments"][:6]), ""]
    if not sh:
        md.append("_No moment dense enough yet._")
    p = ep_dir / "shorts.md"
    p.write_text("\n".join(md) + "\n", encoding="utf-8")
    out["shorts"] = p

    p = ep_dir / "transcript.md"
    p.write_text(transcript(events, rec_start).replace("# Transcript", f"# Transcript - {show_title} - {ep_name}", 1),
                 encoding="utf-8")
    out["transcript"] = p

    p = ep_dir / "cost.json"
    p.write_text(json.dumps(cost_report(events, meter, duration), indent=2), encoding="utf-8")
    out["cost"] = p
    return out
