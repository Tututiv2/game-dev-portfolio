"""Config loading: show.json + cast.json, and the account<->character map.

Everything reads paths/ports from here so nothing hard-codes them twice.
"""
from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]          # AISkyShow/
CONFIG_DIR = ROOT / "config"


def _load(name: str) -> dict:
    with open(CONFIG_DIR / name, encoding="utf-8") as f:
        return json.load(f)


@dataclass
class Character:
    id: str
    name: str
    account: str
    slot: int
    color: str
    archetype: str
    personality: str
    chat_style: str
    inner_voice_style: str
    playstyle: str
    relationships: dict
    voice: dict
    raw: dict = field(repr=False, default_factory=dict)

    @property
    def pilot_port(self) -> int:
        return SHOW.ports["pilot"][self.slot]


@dataclass
class Show:
    title: str
    paths: dict
    ports: dict
    rcon_password: str
    obs_password: str
    monitor: dict
    client: dict
    ruleset: str
    characters: list[Character]
    narrator: dict
    camera_account: str

    def by_id(self, cid: str) -> Character:
        for c in self.characters:
            if c.id == cid:
                return c
        raise KeyError(cid)

    def by_account(self, account: str) -> Character | None:
        a = account.lower()
        for c in self.characters:
            if c.account.lower() == a:
                return c
        return None

    def char_name(self, account_or_name: str) -> str:
        """Map an MC account name to the show character name (pass-through if unknown)."""
        c = self.by_account(account_or_name)
        return c.name if c else account_or_name


def load() -> Show:
    s = _load("show.json")
    cast = _load("cast.json")
    chars = []
    for c in cast["characters"]:
        chars.append(Character(
            id=c["id"], name=c["name"], account=c["account"], slot=int(c["slot"]),
            color=c["color"], archetype=c.get("archetype", ""), personality=c.get("personality", ""),
            chat_style=c.get("chat_style", ""), inner_voice_style=c.get("inner_voice_style", ""),
            playstyle=c.get("playstyle", ""), relationships=c.get("relationships", {}),
            voice=c.get("voice", {}), raw=c))
    chars.sort(key=lambda c: c.slot)
    return Show(
        title=s["title"], paths=s["paths"], ports=s["ports"],
        rcon_password=s.get("rcon_password", ""), obs_password=s.get("obs_password", ""),
        monitor=s.get("monitor", {}), client=s.get("client", {}), ruleset=s.get("ruleset", ""),
        characters=chars, narrator=cast.get("narrator", {}),
        camera_account=cast.get("camera_account", "ShowCam"))


SHOW = load()

# Agents run claude -p from a cwd OUTSIDE the Ai Game tree so no CLAUDE.md leaks into characters.
AGENT_HOME = Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "AISkyShow"
