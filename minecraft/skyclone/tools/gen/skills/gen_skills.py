#!/usr/bin/env python3
"""Generate plugin/src/main/resources/data/skills/*.json from research/data/skills.json.

    python tools/gen/skills/gen_skills.py

Every number (cumulative XP, coins, SkyBlock XP, per-level stats, perk values) is parsed out of the
reference API dump; nothing numeric is typed here. What IS typed here is OUR wording: skill
descriptions, perk names, perk sentences, and the renamed / dropped unlock lines (see
docs/names/skills.md).

Outputs:
  data/skills/skills.json          the whole skill table the module loads
  data/skills/stone_unlocks.json   {ITEM_ID: miningOrCombatLevel} for the reforges / accessories
                                   modules (reforge stones need Mining N, power stones Combat N)
"""
import io
import json
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
SRC = os.path.join(ROOT, "research", "data", "skills.json")
ITEMS = os.path.join(ROOT, "research", "data", "items.json")
OUT_DIR = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "skills")

ARROW = "➜"
PUA = re.compile("[-]")

# Skills our API knows (api.skill.SkillType). HUNTING joined the enum in wave 5 (core-wave5).
SKILLS = ["FARMING", "MINING", "COMBAT", "FORAGING", "FISHING", "ENCHANTING", "ALCHEMY",
          "CARPENTRY", "TAMING", "RUNECRAFTING", "SOCIAL", "HUNTING"]

# Our own wording. {v} is replaced at runtime by the perk value ("+8", or "+4->8" on a level-up).
OURS = {
    "FARMING": {
        "description": ["Harvest crops and shear sheep", "to earn Farming XP!"],
        "perkName": "Harvester", "perkStat": "FARMING_FORTUNE",
        "perkText": "&fGrants {v} &6☘ Farming Fortune&f, raising your odds of extra crops.",
        "defaultCap": 50,
        "capHint": ["&7Raise it with gold medals earned", "&7in farming contests."],
    },
    "MINING": {
        "description": ["Dig into deep caves for rare ores", "and materials to earn Mining XP!"],
        "perkName": "Prospector", "perkStat": "MINING_FORTUNE",
        "perkText": "&fGrants {v} &6☘ Mining Fortune&f, raising your odds of extra ore drops.",
    },
    "COMBAT": {
        "description": ["Fight mobs and bosses to earn", "Combat XP!"],
        "perkName": "Veteran", "perkStat": None,
        "perkText": "&fDeal {v}% &fmore damage to mobs.",
    },
    "FORAGING": {
        "description": ["Chop trees and gather plants to", "earn Foraging XP!"],
        "perkName": "Timberjack", "perkStat": "FORAGING_FORTUNE",
        "perkText": "&fGrants {v} &6☘ Foraging Fortune&f, raising your odds of extra logs.",
        "defaultCap": 50,
        "capHint": ["&7Raise it by reaching high log", "&7collection milestones."],
    },
    "FISHING": {
        "description": ["Cast a line at any pond or sea", "to earn Fishing XP!"],
        "perkName": "Beachcomber", "perkStat": "TREASURE_CHANCE",
        "perkText": "&fGrants {v} &6⛃ Treasure Chance&f.",
    },
    "ENCHANTING": {
        "description": ["Enchant your gear to earn", "Enchanting XP!"],
        "perkName": "Arcanist", "perkStat": None,
        "perkText": "&fGain {v}% &fmore experience orbs from every source.",
    },
    "ALCHEMY": {
        "description": ["Brew potions to earn Alchemy", "XP!"],
        "perkName": "Distiller", "perkStat": None,
        "perkText": "&fPotions you brew last {v}% &flonger.",
    },
    "CARPENTRY": {
        "description": ["Craft items to earn Carpentry", "XP!"],
        "perkName": None, "perkStat": None, "perkText": None,
    },
    "TAMING": {
        "description": ["Level up your pets to earn", "Taming XP!"],
        "perkName": "Beastkeeper", "perkStat": None,
        "perkText": "&fYour pets gain {v}% &fmore pet XP.",
        "perk2Text": "&fPets you are not using share {v}% &fof pet XP.",
        "defaultCap": 50,
        "capHint": ["&7Raise it by handing rare pets to", "&7the pet collector."],
    },
    "RUNECRAFTING": {
        "description": ["Slay runic mobs and fuse runes", "to earn Runecrafting XP!"],
        "perkName": None, "perkStat": None, "perkText": None,
    },
    "SOCIAL": {
        "description": ["Host guests on your island and", "visit others to earn Social XP!"],
        "perkName": None, "perkStat": None, "perkText": None,
    },
    # Wave 5 (core). The reference perk is "Charming" (+Charm Chance); ours is the Snarewright perk. The
    # hunting module decides what Charm Chance does (extra shard odds); the number is the reference's.
    "HUNTING": {
        "description": ["Track and trap creatures for", "their shards to earn Hunting XP!"],
        "perkName": "Snarewright", "perkStat": None,
        "perkText": "&fGrants {v}% &dCharm Chance&f, your odds of an extra shard from a catch.",
    },
}

STAT_NAMES = {
    "Health": "HEALTH", "Defense": "DEFENSE", "Crit Chance": "CRIT_CHANCE", "Strength": "STRENGTH",
    "Ability Damage": "ABILITY_DAMAGE", "Intelligence": "INTELLIGENCE", "Pet Luck": "PET_LUCK",
    "Hunting Fortune": "HUNTING_FORTUNE",
}

# "+Access to X": island id (or None = the place is not part of this game, line dropped).
ACCESS = {
    "The Barn": ("island", "barn", "The Barn"),
    "Mushroom Desert": ("island", "mushroom_desert", "Mushroom Desert"),
    "Gold Mine": ("island", "gold_mine", "Gold Mine"),
    "Deep Caverns": ("island", "deep_caverns", "Deep Caverns"),
    "Dwarven Mines": ("island", "dwarven_mines", "Dwarven Mines"),
    "Crystal Hollows": ("island", "crystal_hollows", "Crystal Caverns"),
    "Spider's Den": ("island", "spiders_den", "Spider's Den"),
    "The End": ("island", "the_end", "The End"),
    "Crimson Isle": ("island", "crimson_isle", "Crimson Isle"),
    "The Park": ("island", "park", "The Park"),
    "Zealot Bruiser Hideout": ("text", None, "the Bruiser Hideout in The End"),
    "Galatea": None,        # island not in this game
    "Lotus Atoll": None,    # island not in this game
    "Critter Safari": None,  # Hunting level 1 area; the hunting module (wave 5) gates its own grounds
}

# Fallback display names for enchant ids that are the reference game's own coinage. At runtime the
# name registered by the enchanting module wins; these only show if that module is absent.
ENCHANT_FALLBACK = {"ultimate_jerry": "Ultimate Jester", "pesterminator": "Pest Bane"}

TRAINER = {"Free": "Free", "Light": "Light", "Moderate": "Moderate", "Expert": "Expert",
           "Ultra": "Ultra", "Turbo!": "Turbo"}


def num(s):
    return float(s.replace(",", ""))


def snake(name):
    s = name.lower().replace("'", "").replace("-", "_").replace("!", "")
    s = re.sub(r"[^a-z0-9]+", "_", s).strip("_")
    return s


def main():
    raw = json.load(io.open(SRC, encoding="utf-8"))["skills"]
    items = json.load(io.open(ITEMS, encoding="utf-8"))["items"]
    by_name = {}
    for it in items:
        by_name.setdefault(re.sub("§.", "", it["name"]).lower(), it["id"])
    # the crafting module's furniture table (wave 5): reference name -> item id
    furniture = {}
    fpath = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "crafting", "furniture.json")
    if os.path.exists(fpath):
        for f in json.load(io.open(fpath, encoding="utf-8")):
            furniture[f["name"].lower()] = f["id"]

    out = {"version": 1, "source": "research/data/skills.json (Hypixel API 0.27.1.18)", "skills": {}}
    stones = {}
    problems = []

    for sid in SKILLS:
        src = raw[sid]
        ours = OURS[sid]
        levels = []
        for L in src["levels"]:
            lv = {"level": L["level"], "total": L["totalExpRequired"], "coins": 0, "sbxp": 0,
                  "stats": {}, "perk": 0.0, "perk2": 0.0, "unlocks": []}
            for u in L["unlocks"]:
                clean = PUA.sub("", u).replace("  ", " ").strip()
                m = re.match(r"^\+([\d,]+) Coins$", u)
                if m:
                    lv["coins"] = num(m.group(1)); continue
                m = re.match(r"^\+(\d+) SkyBlock XP$", u)
                if m:
                    lv["sbxp"] = int(m.group(1)); continue
                m = re.match(r"^\+([\d.]+)%? [-] (.+)$", u)
                if m:
                    stat = STAT_NAMES.get(m.group(2))
                    if stat is None:
                        problems.append(f"{sid} {L['level']}: unknown stat {m.group(2)!r}")
                    else:
                        lv["stats"][stat] = lv["stats"].get(stat, 0) + num(m.group(1))
                    continue
                # perk line: "<Name> <ROMAN>   <sentence with a value or a from->to range>"
                m = re.match(r"^[A-Z][A-Za-z ]+? [IVXLC]+\s{2,}(.*)$", u)
                if m:
                    v = re.search(r"([\d.]+)(?:" + ARROW + r"([\d.]+))?", m.group(1))
                    lv["perk"] = num(v.group(2) or v.group(1))
                    continue
                m = re.match(r"^\+Gain ([\d.]+)(?:" + ARROW + r"([\d.]+))?% Exp Share rate\.$", u)
                if m:
                    lv["perk2"] = num(m.group(2) or m.group(1)); continue
                m = re.match(r"^\+?Access to (.+)$", u)
                if m and m.group(1) in ACCESS:
                    a = ACCESS[m.group(1)]
                    if a is None:
                        continue
                    kind, iid, nm = a
                    if kind == "island":
                        lv["unlocks"].append({"type": "access", "id": iid, "name": nm})
                    else:
                        lv["unlocks"].append({"type": "text", "name": "&7Access to " + nm})
                    continue
                m = re.match(r"^(.*) (Reforge|Power Stone)$", u)
                if m:
                    iid = by_name.get(m.group(1).lower())
                    if iid is None:
                        problems.append(f"{sid} {L['level']}: no item for {u!r}")
                        continue
                    kind = "reforge_stone" if m.group(2) == "Reforge" else "power_stone"
                    lv["unlocks"].append({"type": kind, "id": iid})
                    stones[iid] = {"skill": sid.lower(), "level": L["level"], "kind": kind}
                    continue
                m = re.match(r"^(.*) Enchantment$", u)
                if m:
                    eid = snake(m.group(1))
                    lv["unlocks"].append({"type": "enchant", "id": eid,
                                          "name": ENCHANT_FALLBACK.get(eid, m.group(1))})
                    continue
                m = re.match(r"^(.*) Sea Creature$", u)
                if m:
                    if m.group(1) == "???":
                        lv["unlocks"].append({"type": "sea_creature", "id": None})
                    else:
                        lv["unlocks"].append({"type": "sea_creature", "id": snake(m.group(1))})
                    continue
                m = re.match(r"^(.*) Furniture Recipe$", u)
                if m:
                    # wave 5: crafting builds these (data/crafting/furniture.json); name the real item id so the road
                    # shows crafting's registered name and a self-test can prove every piece exists
                    fid = furniture.get(m.group(1).lower())
                    if fid is None:
                        problems.append(f"{sid} {L['level']}: no crafting furniture named {m.group(1)!r}")
                        continue
                    lv["unlocks"].append({"type": "furniture", "id": fid, "name": m.group(1)})
                    continue
                if u == "Furniture Recipe (COMING SOON)":
                    continue
                m = re.match(r"^(.*) Recipe$", u)
                if m:
                    iid = by_name.get(m.group(1).lower())
                    lv["unlocks"].append({"type": "recipe", "id": iid, "name": m.group(1)})
                    continue
                m = re.match(r"^\+(\S+) Training Session at Fann$", u)
                if m:
                    lv["unlocks"].append({"type": "text",
                                          "name": "&7" + TRAINER[m.group(1)] + " training sessions at the Pet Trainer"})
                    continue
                m = re.match(r"^Ability to purchase (.+) from Amelia$", u)
                if m:
                    lv["unlocks"].append({"type": "text", "name": "&7Buy " + m.group(1) + " from the Island Decorator"})
                    continue
                m = re.match(r"^Access to Level (\d+) Runes$", u)
                if m:
                    lv["unlocks"].append({"type": "text", "name": "&7Use Level " + m.group(1) + " Runes"})
                    continue
                if u in ("Intermediate Accessory Bag Powers", "Access to Quick Crafting System",
                         "Unlock /social Commands", "Unlock Island Ranks", "Unlock Island Naming"):
                    nice = {"Intermediate Accessory Bag Powers": "Intermediate accessory bag powers",
                            "Access to Quick Crafting System": "Quick Crafting",
                            "Unlock /social Commands": "Social commands",
                            "Unlock Island Ranks": "Island ranks",
                            "Unlock Island Naming": "Island naming"}[u]
                    lv["unlocks"].append({"type": "text", "name": "&7" + nice})
                    continue
                problems.append(f"{sid} {L['level']}: unparsed {clean!r}")
            levels.append(lv)

        maxl = src["maxLevel"]
        if len(levels) != maxl:
            problems.append(f"{sid}: {len(levels)} levels for max {maxl}")
        out["skills"][sid] = {
            "id": sid,
            "name": src["name"],
            "description": ours["description"],
            "maxLevel": maxl,
            "defaultCap": ours.get("defaultCap", maxl),
            "capHint": ours.get("capHint", []),
            "perkName": ours["perkName"],
            "perkStat": ours["perkStat"],
            "perkText": ours["perkText"],
            "perk2Text": ours.get("perk2Text"),
            "levels": levels,
        }

    if problems:
        print("PROBLEMS:")
        for p in problems:
            print("  " + p)
        sys.exit(1)

    os.makedirs(OUT_DIR, exist_ok=True)
    with io.open(os.path.join(OUT_DIR, "skills.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    with io.open(os.path.join(OUT_DIR, "stone_unlocks.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(dict(sorted(stones.items())), f, ensure_ascii=False, indent=1)

    # quick report of the research numbers the self-tests check
    f = out["skills"]["FARMING"]["levels"]
    print("FARMING total@60", f[-1]["total"], "coins sum", sum(l["coins"] for l in f),
          "health@50", sum(l["stats"].get("HEALTH", 0) for l in f[:50]))
    print("stones", len(stones), "skills", len(out["skills"]))


if __name__ == "__main__":
    main()
