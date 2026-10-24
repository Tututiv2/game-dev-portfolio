"""Wave 5 (2026-10-06): the starred (fragment-upgraded) dungeon weapons.

The base weapons (SHADOW_FURY, BONZO_STAFF, ...) are registered by the weapons module (its items_fallback.json, with
"owner": "dungeons"). Their starred forms are ours: at boot the dungeons module copies the live base def and applies the
DELTAS written here (reference starred stats minus reference base stats, from research/data/items.json), so the weapons
module's own tuning of the base carries over. Recipes are NEU's: the base weapon ringed by 8 boss fragments.

Writes plugin/src/main/resources/data/dungeons/starred_weapons.json. Run: python tools/gen/dungeons/gen_starred.py
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
ITEMS = os.path.join(ROOT, "research", "data", "items.json")
NEU = os.path.join(ROOT, "research", "data", "neu-repo", "items")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "dungeons", "starred_weapons.json")

# starred id -> (base id, the floor whose completion unlocks the recipe, like the base's floor)
STARRED = {
    "STARRED_BONZO_STAFF": ("BONZO_STAFF", "F1"),
    "STARRED_STONE_BLADE": ("STONE_BLADE", "F3"),
    "STARRED_ITEM_SPIRIT_BOW": ("ITEM_SPIRIT_BOW", "F4"),
    "STARRED_BAT_WAND": ("BAT_WAND", "F4"),
    "STARRED_BONE_BOOMERANG": ("BONE_BOOMERANG", "F4"),
    "STARRED_SHADOW_FURY": ("SHADOW_FURY", "F5"),
    "STARRED_LAST_BREATH": ("LAST_BREATH", "F5"),
    "STARRED_FELTHORN_REAPER": ("FELTHORN_REAPER", "F6"),
    "STARRED_ICE_SPRAY_WAND": ("ICE_SPRAY_WAND", None),
}
# wave-5 integration (materials' GOLDEN_FRAGMENT had no live recipe): the Mythic Ritual's Golden Fragment gear uses the
# same copy-and-delta mechanism, but these are NOT dungeon items (row "dungeon": false) and have no floor unlock.
NON_DUNGEON = {
    "STARRED_MIDAS_SWORD": "MIDAS_SWORD",
    "STARRED_MIDAS_STAFF": "MIDAS_STAFF",
    "STARRED_DAEDALUS_AXE": "DAEDALUS_AXE",
}

STAT = {"WALK_SPEED": "SPEED", "CRITICAL_DAMAGE": "CRIT_DAMAGE", "CRITICAL_CHANCE": "CRIT_CHANCE",
        "INTELLIGENCE": "INTELLIGENCE", "STRENGTH": "STRENGTH", "DEFENSE": "DEFENSE", "HEALTH": "HEALTH",
        "SWING_RANGE": "SWING_RANGE", "ATTACK_SPEED": "ATTACK_SPEED", "FEROCITY": "FEROCITY"}


def main():
    data = json.load(open(ITEMS, encoding="utf-8"))
    items = data["items"] if isinstance(data, dict) and "items" in data else data
    by = {it["id"]: it for it in items}
    out = []
    rows = [(sid, base, floor, True) for sid, (base, floor) in STARRED.items()]
    rows += [(sid, base, None, False) for sid, base in NON_DUNGEON.items()]
    for sid, base, floor, dungeon in rows:
        s, b = by.get(sid), by.get(base)
        if s is None or b is None:
            print("skip (no reference data):", sid)
            continue
        ss, bs = s.get("stats") or {}, b.get("stats") or {}
        delta, damage, ability = {}, 0, 0
        for k in set(ss) | set(bs):
            d = (ss.get(k, 0) or 0) - (bs.get(k, 0) or 0)
            if not d:
                continue
            if k == "DAMAGE":
                damage = d
            elif k == "WEAPON_ABILITY_DAMAGE":
                ability = d
            elif k in STAT:
                delta[STAT[k]] = d
            else:
                print("unmapped stat", sid, k, d)
        neu = os.path.join(NEU, sid + ".json")
        grid = None
        if os.path.exists(neu):
            r = json.load(open(neu, encoding="utf-8")).get("recipe")
            if r:
                grid = [r.get(k, "") or "" for k in ("A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3")]
        if grid is None:
            # NEU has no recipe for some (Starred Daedalus Axe): crafting's converted table does
            sb = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "crafting", "skyblock_recipes.json")
            if os.path.exists(sb):
                with open(sb, encoding="utf-8") as fh:
                    for line in fh:
                        line = line.strip().rstrip(",")
                        if not line.startswith("{"):
                            continue
                        rec = json.loads(line)
                        if rec.get("result") == sid and rec.get("type", "CRAFTING") == "CRAFTING" and len(rec.get("grid") or []) == 9:
                            grid = list(rec["grid"])
                            break
        row = {"id": sid, "base": base, "rarity": s.get("tier"), "damage": damage, "abilityDamage": ability,
               "stats": delta, "grid": grid, "unlock": ("dungeon_completion:" + floor + ":1") if floor else None}
        if not dungeon:
            row["dungeon"] = False
        out.append(row)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=1, ensure_ascii=False)
    print("wrote", OUT, len(out))


if __name__ == "__main__":
    main()
