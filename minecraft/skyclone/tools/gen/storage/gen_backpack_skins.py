"""Backpack skins for the storage module (wave 5).

Writes plugin/src/main/resources/data/storage/backpack_skins.json: one ItemDef per reference backpack skin
(every NEU card whose lore says "This skin can be applied to Backpacks"), with the reference id, rarity and
head texture, OUR lore, and our name where the reference one is a proper noun (see NAME_OVERRIDE; the
renames are listed in docs/names/storage.md by gen_storage.py).

Run from the repo root:  python tools/gen/storage/gen_backpack_skins.py
Pure: reads research/data only, writes one JSON file. Kept apart from gen_storage.py so re-running it never
touches the sack / backpack catalogue.
"""
import json
import os
import re
import sys
from collections import OrderedDict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
NEU = os.path.join(ROOT, "research", "data", "neu-repo", "items")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "storage", "backpack_skins.json")

# reference name -> ours, only where the reference name is a proper noun / in-joke
NAME_OVERRIDE = {
    "HAMBAGGER_BACKPACK": "Burger Backpack Skin",
}

MARK = "applied to Backpacks"
TEXTURE = re.compile(r'Value:\\?"([A-Za-z0-9+/=]+)\\?"')
COLOUR = re.compile("§.")


def strip(s):
    return COLOUR.sub("", s or "")


def rarity_of(lore):
    for line in reversed(lore):
        plain = strip(line).strip().upper()
        for r in ("MYTHIC", "LEGENDARY", "EPIC", "RARE", "UNCOMMON", "COMMON", "SPECIAL"):
            if plain.startswith(r + " "):
                return r
    return "EPIC"


def main():
    skins = []
    for name in sorted(os.listdir(NEU)):
        if not name.endswith(".json"):
            continue
        with open(os.path.join(NEU, name), encoding="utf8") as f:
            card = json.load(f)
        lore = card.get("lore") or []
        if MARK not in " ".join(strip(line).strip() for line in lore):
            continue
        sid = name[:-5]
        m = TEXTURE.search(card.get("nbttag", ""))
        if not m:
            print("no texture, skipped:", sid, file=sys.stderr)
            continue
        display = NAME_OVERRIDE.get(sid, strip(card.get("displayname")).strip())
        d = OrderedDict([
            ("id", sid), ("name", display), ("material", "PLAYER_HEAD"), ("rarity", rarity_of(lore)),
            ("category", "COSMETIC"), ("maxStack", 1),
            ("lore", ["&7Gives one of your &aBackpacks &7a", "&7new look. Right-click a placed",
                      "&7backpack in &a/storage &7and pick", "&aApply Skin&7. Remove it any time",
                      "&7and the skin comes back.", "", "&8Cosmetic only."]),
            ("tags", ["backpack_skin", "no_reforge"]),
            ("skull", m.group(1)),
        ])
        skins.append(d)
    with open(OUT, "w", encoding="utf8") as f:
        json.dump(skins, f, ensure_ascii=False, indent=1)
    print("backpack skins:", len(skins))


if __name__ == "__main__":
    main()
