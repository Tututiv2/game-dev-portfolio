"""Prompt assembly for the player agents (CONTRACT §8).

    system = build_system_prompt(char)                 # frozen per agent (cacheable)
    blocks = build_turn_blocks(char, elapsed_s=..., total_s=..., digest=..., results=[...],
                               notes=[...], beat=beat_for(...), screenshot=block_or_None)

The system prompt is frozen for the whole session (time, state and nudges go in the TURN, never in
the system prompt, or the prompt cache breaks every turn). Another character's inner voice never
appears in anything built here: the only thing about the others that reaches a character is the
chat digest that observe.py produces from in-game chat.
"""
from __future__ import annotations

import re
from pathlib import Path

from .config import SHOW, Character, ROOT

PROMPTS_DIR = ROOT / "orchestrator" / "prompts"
PRIMER_PLACEHOLDER = ("(SkyBlock primer not written yet - rely on what you already know about "
                      "Hypixel SkyBlock.)")
EMOTIONS = ("hype", "calm", "angry", "scared", "smug", "confused", "sad", "determined", "happy")

# Fallback when show.macros is not importable yet (CONTRACT §9 list).
_FALLBACK_TOOLS = """\
- look_around() - what's near you
- walk_to(x, y, z) or walk_to(anchor) - walk somewhere
- warp(dest) - fast-travel ("hub", "home", ...)
- open_menu(name) / click_item(name) / close_menu() - use menus (SkyBlock menu, shops)
- talk_to(npc_name) - talk to an NPC
- fight(target, seconds|count) - fight mobs
- mine(block, count) / chop(log, count) / farm(crop, count) / fish(seconds) - gather
- sell_to_npc(item?, all?) / buy_from_npc(npc, item, n) / bazaar(...) - trade
- craft(item_name, n) / equip(item_name) / use_ability() - gear
- say(text) - chat (you can also use the top-level "say")
- wait(seconds)
- command(text) - player commands like /warp hub, /hub, /is"""


def _read(name: str) -> str:
    return (PROMPTS_DIR / name).read_text(encoding="utf-8")


def _fill(template: str, values: dict[str, str]) -> str:
    out = template
    for k, v in values.items():
        out = out.replace("{{" + k + "}}", str(v))
    return out


def tools_text() -> str:
    try:
        from . import macros   # B2
        txt = macros.describe_tools()
        if txt and txt.strip():
            return txt.strip()
    except Exception:
        pass
    return _FALLBACK_TOOLS


def primer_text() -> str:
    try:
        txt = _read("skyblock_primer.md").strip()
        return txt or PRIMER_PLACEHOLDER
    except OSError:
        return PRIMER_PLACEHOLDER


def others(char: Character, show=SHOW) -> list[Character]:
    return [c for c in show.characters if c.id != char.id]


def relationships_text(char: Character, show=SHOW) -> str:
    lines = []
    for o in others(char, show):
        feel = char.relationships.get(o.id) or char.relationships.get(o.name) or "you know them well"
        lines.append(f"- **{o.name}** ({o.archetype}): {feel}")
    return "\n".join(lines)


def build_system_prompt(char: Character, show=SHOW, *, tools: str | None = None, primer: str | None = None,
                        minutes: float | None = None, max_actions: int | None = None) -> str:
    if minutes is None or max_actions is None:
        from .dials import DIALS
        minutes = minutes if minutes is not None else DIALS["episode_minutes"]
        max_actions = max_actions if max_actions is not None else DIALS["max_actions_per_turn"]
    return _fill(_read("player_system.md"), {
        "name": char.name, "archetype": char.archetype, "personality": char.personality,
        "chat_style": char.chat_style, "inner_voice_style": char.inner_voice_style,
        "playstyle": char.playstyle, "relationships": relationships_text(char, show),
        "primer": primer if primer is not None else primer_text(),
        "tools": tools if tools is not None else tools_text(),
        "minutes": f"{minutes:g}", "max_actions": str(max_actions),
    }).strip() + "\n"


# --- beats ------------------------------------------------------------------------------------
def beat_sections() -> dict[str, str]:
    """Parse wakeup.md into {key: text}."""
    out: dict[str, str] = {}
    key = None
    buf: list[str] = []
    for line in _read("wakeup.md").splitlines():
        m = re.match(r"^##\s+(\w+)\s*$", line)
        if m:
            if key:
                out[key] = "\n".join(buf).strip()
            key, buf = m.group(1), []
        elif line.startswith("#"):
            continue
        elif key:
            buf.append(line)
    if key:
        out[key] = "\n".join(buf).strip()
    return out


def beat_for(elapsed_s: float, total_s: float, turn_n: int) -> str:
    """Which beat of the episode we're in. Fractions are of the episode (30 min -> 3/6/10 min)."""
    if turn_n <= 1:
        return "wakeup"
    total_s = max(total_s, 60.0)
    remaining = total_s - elapsed_s
    if remaining <= min(300.0, total_s * 0.17):
        return "last5"
    f = elapsed_s / total_s
    if f < 0.10:
        return "explore"
    if f < 0.20:
        return "find_hub"
    if f < 0.33:
        return "meet"
    return "progress"


BEAT_SHORT = {
    "explore": "Phase: getting your bearings on your island.",
    "find_hub": "Phase: find the Hub and your squad.",
    "meet": "Phase: meet up with the squad at the Hub and make a plan.",
    "progress": "Phase: real progress - keep your goal and finish it.",
    "last5": "Phase: FINAL MINUTES - one more real milestone.",
}


def beat_text(beat: str, char: Character, *, elapsed_s: float, total_s: float, full: bool, show=SHOW) -> str:
    if not full and beat in BEAT_SHORT:
        return BEAT_SHORT[beat]
    secs = beat_sections()
    names = [o.name for o in others(char, show)]
    return _fill(secs.get(beat, ""), {
        "name": char.name, "minutes": f"{total_s / 60:g}", "remaining": fmt_mmss(total_s - elapsed_s),
        "others": ", ".join(names[:-1]) + " and " + names[-1] if len(names) > 1 else "".join(names),
    })


def fmt_mmss(s: float) -> str:
    s = max(0, int(s))
    return f"{s // 60}:{s % 60:02d}"


# --- per-turn blocks ----------------------------------------------------------------------------
def build_turn_blocks(char: Character, *, elapsed_s: float, total_s: float, digest: str,
                      results: list[str] | None = None, notes: list[str] | None = None,
                      beat: str | None = None, beat_full: bool = False,
                      screenshot: dict | None = None, show=SHOW) -> list[dict]:
    parts = [f"[Countdown] {fmt_mmss(elapsed_s)} elapsed, {fmt_mmss(total_s - elapsed_s)} left."]
    if beat:
        bt = beat_text(beat, char, elapsed_s=elapsed_s, total_s=total_s, full=beat_full, show=show)
        if bt:
            parts.append(bt)
    if results:
        parts.append("[What happened with your last actions]\n" + "\n".join(f"- {r}" for r in results))
    parts.append("[What you see and hear]\n" + (digest.strip() if digest else "(nothing new)"))
    if notes:
        parts.append("[Notes]\n" + "\n".join(f"- {n}" for n in notes))
    if screenshot:
        parts.append("(A picture of your view is attached.)")
    parts.append("Reply with the JSON object only.")
    blocks: list[dict] = [{"type": "text", "text": "\n\n".join(parts)}]
    if screenshot:
        blocks.append(screenshot)
    return blocks


def wrapup_blocks(char: Character, *, total_s: float, show=SHOW) -> list[dict]:
    txt = beat_text("wrapup", char, elapsed_s=total_s, total_s=total_s, full=True, show=show)
    return [{"type": "text", "text": txt + "\n\nReply with the JSON object only."}]


REPAIR_PROMPT = ("Your last reply could not be read. Reply with ONLY the JSON object - "
                 '{"inner_voice": ..., "emotion": ..., "say": ..., "goal": ..., "actions": [...]} - '
                 "no other text, no code fences.")
