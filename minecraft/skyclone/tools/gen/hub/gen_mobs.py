"""Generate plugin/src/main/resources/data/hub/{mobs,spawns}.json from the research data.

Stats come from research/03-world-zones-mobs-npcs.md section 4.3 (HP, damage, level) and the
drop tables, coins and combat XP from research/data/neu-repo/items/<MOB>_MONSTER.json, where the
NEU repo has them. Anything neither source gives is marked "tune" in the output's extra block.

    python tools/gen/hub/gen_mobs.py
"""
import json
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
NEU = os.path.join(ROOT, "research", "data", "neu-repo", "items")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "hub")


def neu_drops(file, name_filter=None):
    """(level, coins, combat_xp, drops) from a NEU monster page."""
    with open(os.path.join(NEU, file), encoding="utf-8") as f:
        d = json.load(f)
    for r in d.get("recipes", []):
        if r.get("type") != "drops":
            continue
        if name_filter and name_filter not in re.sub("§.", "", r.get("name", "")):
            continue
        drops = []
        for x in r.get("drops", []):
            ident = x["id"]
            # "ROTTEN_FLESH:1" -> id + amount; pet ids contain ';' and keep it
            m = re.match(r"^(.*?)(?::(\d+))?$", ident)
            item, amount = m.group(1), int(m.group(2) or 1)
            lo, hi = amount, amount
            for e in x.get("extra", []) or []:
                mm = re.search(r"x(\d+)-(\d+)", re.sub("§.", "", e))
                if mm:
                    lo, hi = int(mm.group(1)), int(mm.group(2))
            ch = x.get("chance", "100%")
            chance = float(ch.rstrip("%")) / 100.0
            if any("Pocket Black Hole" in (e or "") for e in x.get("extra", []) or []):
                continue      # attribute shards: a mechanic we do not have
            drops.append({"item": item, "min": lo, "max": hi, "chance": round(chance, 8)})
        return r.get("level"), r.get("coins"), r.get("combat_xp"), drops
    raise SystemExit("no drops in " + file)


def mob(mid, name, entity, level, hp, dmg, xp, coins, drops, family, slayer=None, flags=(), equip=None, types=(), speed=1.0, tune=None, behaviours=()):
    m = {
        "id": mid, "name": name, "level": level, "entity": entity, "health": hp, "damage": dmg,
        "combatXp": xp, "coins": coins, "drops": drops, "family": family, "flags": list(flags),
        "equipment": equip or {}, "speed": speed,
        "extra": {"mobTypes": list(types), "types": list(types), "source": "research 03 section 4.3 + neu-repo"},
    }
    if slayer:
        m["slayer"] = slayer
    if behaviours:
        m["behaviours"] = list(behaviours)
    if tune:
        m["extra"]["tune"] = tune
    return m


# GraveyardPack (HubModule): short aggro, at most N chasers per player, a quiet arrival gate.
# Integration round: with vanilla's 35-block follow range the night's 24 mobs all converged on one
# player and killed him in ~3 s even at 320 Defense. Night total is also 20 now (was 24).
PACK = "hub_graveyard_pack"
# The same rule for the Crypt (GraveyardPack.CRYPT): playtest "loops" 2026-10-03, /warp crypt landed a
# 600 HP / 700 Defense player in the ghoul pack and he died within a second, twice.
CRYPT_PACK = "hub_crypt_pack"
# GoldenVault (HubModule): wave-4 playtest, the Golden Ghoul (Lv60, 800 a hit) patrolled the main hall where
# everyone grinds Crypt Ghouls; it spawns in its own vault (area crypt_vault) and fights only players inside it
# or players who hit it.
GOLDEN_VAULT = "hub_golden_vault"


def main():
    mobs = []
    _, _, _, zdrops = neu_drops("ZOMBIE_MONSTER.json")
    # research 4.3: Graveyard Zombie Lv1, 100 HP, 20 dmg, 6 XP, 1 coin
    mobs.append(mob("hub_graveyard_zombie", "Graveyard Zombie", "ZOMBIE", 1, 100, 20, 6, 1, zdrops, "graveyard_zombie",
                    "zombie", ["aggressive"], {"HEAD": "LEATHER_HELMET"}, ["undead"], behaviours=[PACK]))
    lv, coins, xp, d = neu_drops("ZOMBIE_VILLAGER_MONSTER.json")
    mobs.append(mob("hub_zombie_villager", "Zombie Villager", "ZOMBIE_VILLAGER", lv, 120, 24, xp, coins, d, "zombie_villager",
                    "zombie", ["aggressive"], None, ["undead"], behaviours=[PACK]))
    lv, coins, xp, d = neu_drops("CRYPT_GHOUL_MONSTER.json")
    mobs.append(mob("hub_crypt_ghoul", "Crypt Ghoul", "ZOMBIE", lv, 2000, 350, xp, coins, d, "crypt_ghoul",
                    "zombie", ["aggressive"], {"HEAD": "CHAINMAIL_HELMET", "CHEST": "CHAINMAIL_CHESTPLATE", "HAND": "IRON_SWORD"}, ["undead"], behaviours=[CRYPT_PACK]))
    lv, coins, xp, d = neu_drops("GOLDEN_GHOUL_MONSTER.json")
    mobs.append(mob("hub_golden_ghoul", "Golden Ghoul", "ZOMBIE", lv, 45000, 800, xp, coins, d, "golden_ghoul",
                    "zombie", ["aggressive"], {"HEAD": "GOLDEN_HELMET", "CHEST": "GOLDEN_CHESTPLATE", "LEGS": "GOLDEN_LEGGINGS",
                                               "FEET": "GOLDEN_BOOTS", "HAND": "GOLDEN_SWORD"}, ["undead"], behaviours=[CRYPT_PACK, GOLDEN_VAULT]))
    lv, coins, xp, d = neu_drops("WOLF_MONSTER.json")
    mobs.append(mob("hub_wolf", "Wolf", "WOLF", lv, 250, 90, xp, coins, d, "wolf", "wolf", ["aggressive"], None, ["wolf", "animal"],
                    speed=1.2, tune="damage 90 is research (?)"))
    lv, coins, xp, d = neu_drops("OLD_WOLF_MONSTER.json")
    mobs.append(mob("hub_old_wolf", "Old Wolf", "WOLF", lv, 15000, 800, xp, coins, d, "old_wolf", "wolf", ["aggressive"], None,
                    ["wolf", "animal"], speed=1.15, tune="damage 800 is research (?)"))
    # farm animals (NEU: 50 HP, combat XP 2-3, no coins)
    for kind, ent, fam in [("cow", "COW", "COW_ANIMAL.json"), ("pig", "PIG", "PIG_ANIMAL.json"),
                           ("chicken", "CHICKEN", "CHICKEN_ANIMAL.json"), ("sheep", "SHEEP", "SHEEP_ANIMAL.json")]:
        lv, coins, xp, d = neu_drops(fam)
        mobs.append(mob("hub_" + kind, kind.capitalize(), ent, lv or 1, 50, 0, xp or 2, coins or 0, d, kind, None, ["passive"], None, ["animal"]))
    # rabbits: research 4.3 lists them with the farm animals (Lv1, 50-100 HP, 0 dmg) but NEU has no
    # page; drops follow the vanilla rabbit (meat always, hide/foot sometimes), chances TUNE
    mobs.append(mob("hub_rabbit", "Rabbit", "RABBIT", 1, 50, 0, 2, 0,
                    [{"item": "RABBIT", "min": 1, "max": 1, "chance": 1.0},
                     {"item": "RABBIT_HIDE", "min": 1, "max": 1, "chance": 0.5},
                     {"item": "RABBIT_FOOT", "min": 1, "max": 1, "chance": 0.1}],
                    "rabbit", None, ["passive"], None, ["animal"], tune="no NEU page: 50 HP, 2 XP, drop chances TUNE"))
    # sewer rats: research gives only "low" HP and 1 XP / 1 coin, and "Rat pet chance (?)"
    mobs.append(mob("hub_rat", "Rat", "SILVERFISH", 1, 40, 8, 1, 1,
                    [{"item": "RAT;4", "min": 1, "max": 1, "chance": 0.0002}], "rat", None, ["aggressive"], None, ["animal"],
                    tune="HP 40 and damage 8: research says only 'low'; Rat pet 1/5000 is TUNE (research: chance (?))"))

    spawns = [
        {"id": "hub_graveyard_zombies", "island": "hub", "area": "graveyard", "mob": "hub_graveyard_zombie", "max": 10, "respawnSeconds": 8},
        {"id": "hub_graveyard_zombies_night", "island": "hub", "area": "graveyard", "mob": "hub_graveyard_zombie", "max": 4, "respawnSeconds": 8, "condition": "night"},
        {"id": "hub_zombie_villagers", "island": "hub", "area": "graveyard", "mob": "hub_zombie_villager", "max": 6, "respawnSeconds": 8, "condition": "night"},
        {"id": "hub_crypt_ghouls", "island": "hub", "area": "crypt", "mob": "hub_crypt_ghoul", "max": 16, "respawnSeconds": 6},
        {"id": "hub_golden_ghoul", "island": "hub", "area": "crypt_vault", "mob": "hub_golden_ghoul", "max": 1, "respawnSeconds": 180},
        {"id": "hub_ruins_wolves", "island": "hub", "area": "ruins", "mob": "hub_wolf", "max": 8, "respawnSeconds": 10},
        {"id": "hub_ruins_old_wolves", "island": "hub", "area": "ruins", "mob": "hub_old_wolf", "max": 2, "respawnSeconds": 60},
        {"id": "hub_wilderness_wolves", "island": "hub", "area": "wilderness", "mob": "hub_wolf", "max": 8, "respawnSeconds": 12},
        {"id": "hub_farm_cows", "island": "hub", "area": "farm.pen.cow", "mob": "hub_cow", "max": 4, "respawnSeconds": 20},
        {"id": "hub_farm_pigs", "island": "hub", "area": "farm.pen.pig", "mob": "hub_pig", "max": 4, "respawnSeconds": 20},
        {"id": "hub_farm_chickens", "island": "hub", "area": "farm.pen.chicken", "mob": "hub_chicken", "max": 5, "respawnSeconds": 15},
        {"id": "hub_farm_sheep", "island": "hub", "area": "farm.pen.sheep", "mob": "hub_sheep", "max": 4, "respawnSeconds": 20},
        {"id": "hub_farm_rabbits", "island": "hub", "area": "farm.pen.chicken", "mob": "hub_rabbit", "max": 3, "respawnSeconds": 20},
        {"id": "hub_sewer_rats", "island": "hub", "area": "sewer", "mob": "hub_rat", "max": 4, "respawnSeconds": 20},
    ]
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "mobs.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(mobs, f, indent=1)
    with open(os.path.join(OUT, "spawns.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(spawns, f, indent=1)
    print("wrote", len(mobs), "mobs,", len(spawns), "spawn rules")


if __name__ == "__main__":
    main()
