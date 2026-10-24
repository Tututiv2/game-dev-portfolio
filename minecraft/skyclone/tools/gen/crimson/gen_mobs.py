"""Generate data/crimson/mobs.json + spawns.json.

    python tools/gen/crimson/gen_mobs.py

Numbers: level / health from research 03 §15.2-15.3 where the research has them (marked R below),
coins / combat XP / drop tables from the NEU repo's crimson_isle mob pages (marked N). Damage per hit
is not published anywhere we have, so every damage value is a TUNE number scaled to the level band
(a Combat 22-30 player has ~600-1,200 HP and ~250-400 Defense).

Drops: NEU ids are kept as-is (the item owners register them). Dyes and attribute shards are left out
(cosmetic dyes have no owner; attribute shards belong to the 2025 Hunting system, which nobody builds).
Every boss also drops a little Crimson Essence and Kuudra Teeth: an INTERIM source (TUNE) so the
armor prestige ladder is playable before the wave-2 Leviathan (Kuudra) fight exists.
"""
import json
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
NEU = os.path.join(ROOT, "research", "data", "neu-repo", "items")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "crimson")

# id, name (ours), entity, level, health, damage(TUNE), neu page, flags, slayer, types, equipment, behaviours
MOBS = [
    # ---- Smoldering Tomb / blazes (Blaze slayer family) ----
    ("crimson_blaze", "Blaze", "BLAZE", 70, 60_000, 650, "BLAZE_MONSTER", ["aggressive", "fire_immune"], "blaze", ["infernal"], {}, ["crimson_fireball"]),
    ("crimson_mutated_blaze", "Mutated Blaze", "BLAZE", 70, 70_000, 700, "MUTATED_BLAZE_MONSTER", ["aggressive", "fire_immune"], "blaze", ["infernal"], {}, ["crimson_fireball"]),
    ("crimson_bezal", "Scorchling", "BLAZE", 80, 90_000, 800, "BEZAL_MONSTER", ["aggressive", "fire_immune"], "blaze", ["infernal"], {}, ["crimson_fireball"]),
    ("crimson_smoldering_blaze", "Smoldering Blaze", "BLAZE", 95, 2_500_000, 2_000, "SMOLDERING_BLAZE_MONSTER", ["aggressive", "fire_immune"], "blaze", ["infernal"], {}, ["crimson_fireball"]),
    ("crimson_millennia_blaze", "Aeon-Old Blaze", "BLAZE", 110, 30_000_000, 4_000, "MILLENIA_AGED_BLAZE_MONSTER", ["aggressive", "fire_immune", "no_knockback"], "blaze", ["infernal"], {}, ["crimson_fireball", "crimson_flame_ring"]),
    # ---- Blazing Volcano ----
    ("crimson_flare", "Flare", "BLAZE", 90, 200_000, 1_200, "FLARE_MONSTER", ["aggressive", "fire_immune"], "blaze", ["infernal"], {}, ["crimson_fireball"]),
    ("crimson_cinderbat", "Emberwing Bat", "BAT", 90, 2_500_000, 0, "CINDERBAT_MONSTER", ["passive", "flying", "fire_immune"], None, ["animal", "infernal"], {}, []),
    ("crimson_matcho", "Tinderling", "ZOMBIE", 100, 300_000, 1_300, "MATCHO_MONSTER", ["aggressive", "fire_immune", "baby"], None, ["infernal"], {"HEAD": "REDSTONE_TORCH"}, ["crimson_ignite"]),
    # ---- Crimson Fields ----
    ("crimson_magma_cube", "Pack Magma Cube", "MAGMA_CUBE", 75, 75_000, 600, "MAGMA_CUBE_MONSTER", ["aggressive", "fire_immune"], None, ["magmatic", "infernal", "slime"], {}, []),
    ("crimson_ghast", "Dive Ghast", "GHAST", 90, 150_000, 1_500, "GHAST_MONSTER", ["aggressive", "fire_immune", "flying"], None, ["infernal"], {}, []),
    # ---- Burning Desert ----
    ("crimson_magma_cube_rider", "Magma Cube Rider", "ZOMBIFIED_PIGLIN", 90, 300_000, 1_200, "MAGMA_CUBE_RIDER_MONSTER", ["aggressive", "fire_immune"], None, ["undead", "infernal"], {"HAND": "GOLDEN_SWORD"}, []),
    ("crimson_flaming_spider", "Flaming Spider", "SPIDER", 80, 50_000, 700, "FLAMING_SPIDER_MONSTER", ["aggressive", "fire_immune"], "spider", ["arthropod", "infernal"], {}, ["crimson_ignite"]),
    # ---- Stronghold ----
    ("crimson_wither_skeleton", "Wither Skeleton", "WITHER_SKELETON", 70, 50_000, 800, "WITHER_SKELETON_MONSTER", ["aggressive", "fire_immune"], None, ["undead", "skeletal", "wither"], {"HAND": "STONE_SWORD"}, []),
    ("crimson_wither_spectre", "Wither Spectre", "WITHER_SKELETON", 70, 120_000, 900, "WITHER_SPECTRE_MONSTER", ["aggressive", "fire_immune"], None, ["undead", "skeletal", "wither"], {"HAND": "BOW"}, []),
    # ---- Mystic Marsh ----
    ("crimson_mushroom_bull", "Mushroom Bull", "MOOSHROOM", 80, 150_000, 800, "MUSHROOM_BULL_MONSTER", ["aggressive"], None, ["animal"], {}, ["crimson_charge"]),
    # ---- faction territory ----
    ("crimson_kada_knight", "Ashen Knight", "SKELETON", 90, 200_000, 1_100, "KADA_KNIGHT_MONSTER", ["aggressive", "fire_immune"], None, ["undead", "skeletal"], {"HEAD": "GOLDEN_HELMET", "CHEST": "GOLDEN_CHESTPLATE", "HAND": "GOLDEN_SWORD"}, []),
    ("crimson_fire_mage", "Fire Mage", "ZOMBIE", 75, 60_000, 750, None, ["aggressive", "fire_immune"], None, ["infernal"], {"HEAD": "RED_STAINED_GLASS", "HAND": "BLAZE_ROD"}, ["crimson_firebolt"]),
    ("crimson_barbarian", "Barbarian", "VINDICATOR", 80, 80_000, 900, None, ["aggressive"], None, [], {"HAND": "IRON_AXE"}, []),
    ("crimson_barbarian_brute", "Barbarian Brute", "PIGLIN_BRUTE", 90, 500_000, 1_600, None, ["aggressive", "no_knockback"], None, [], {"HAND": "GOLDEN_AXE"}, []),
    # ---- rare spawn on kills ----
    ("crimson_vanquisher", "Vanquisher", "WITHER_SKELETON", 100, 42_000_000, 5_000, "VANQUISHER_MONSTER", ["aggressive", "fire_immune", "no_knockback"], None, ["undead", "wither", "skeletal"], {"HEAD": "WITHER_SKELETON_SKULL", "HAND": "NETHERITE_SWORD"}, ["crimson_flame_ring"]),
    # ---- mini-bosses (R: health, respawn) ----
    ("crimson_bladesoul", "Edgewraith", "WITHER_SKELETON", 200, 50_000_000, 6_000, "BLADESOUL_MINIBOSS", ["boss", "aggressive", "fire_immune", "no_knockback"], None, ["undead", "skeletal"], {"HEAD": "WITHER_SKELETON_SKULL", "HAND": "NETHERITE_SWORD"}, ["crimson_blade_storm"]),
    ("crimson_mage_outlaw", "Rogue Pyromancer", "ZOMBIE", 200, 70_000_000, 6_500, "MAGE_OUTLAW_MINIBOSS", ["boss", "aggressive", "fire_immune", "no_knockback"], None, ["infernal"], {"HEAD": "MAGMA_BLOCK", "HAND": "BLAZE_ROD"}, ["crimson_firebolt", "crimson_flame_ring"]),
    ("crimson_barbarian_duke", "Duke Brakkus the Tenth", "PIGLIN_BRUTE", 200, 70_000_000, 7_000, "BARBARIAN_DUKE_X_MINIBOSS", ["boss", "aggressive", "no_knockback"], None, [], {"HAND": "NETHERITE_AXE"}, ["crimson_ground_slam"]),
    ("crimson_ashfang", "Cindermaw", "BLAZE", 200, 50_000_000, 6_000, "ASHFANG_MINIBOSS", ["boss", "aggressive", "fire_immune", "no_knockback"], None, ["infernal"], {}, ["crimson_fireball", "crimson_twin_flames"]),
    ("crimson_magma_boss", "Magma Colossus", "MAGMA_CUBE", 500, 200_000_000, 9_000, "MAGMA_CUBE_BOSS", ["boss", "aggressive", "fire_immune", "no_knockback"], None, ["magmatic", "slime"], {}, ["crimson_magma_size", "crimson_ground_slam", "crimson_flame_ring"]),
]

# Mobs with no NEU page: coins/xp/drops of our own (TUNE), in the same band as their neighbours.
OWN = {
    "crimson_fire_mage": (20, 70, [("BLAZE_POWDER", 1, 3, 1.0), ("SULPHUR", 1, 1, 0.2), ("MAGMA_CREAM", 1, 1, 0.1)]),
    "crimson_barbarian": (20, 80, [("PORK", 1, 3, 1.0), ("LEATHER", 1, 1, 0.3), ("SAND:1", 1, 2, 0.2)]),
    "crimson_barbarian_brute": (40, 150, [("PORK", 2, 4, 1.0), ("GOLD_INGOT", 1, 2, 0.5), ("ENCHANTED_PORK", 1, 1, 0.02)]),
}

# Prestige-material drops on the mini-bosses (TUNE). Heavy Pearls left this list in wave 3: the Matriarch in
# the Belly of the Beast (Pearls.java) is their source now, as in the reference.
BOSS_EXTRA = [("ESSENCE_CRIMSON", 5, 15, 1.0), ("KUUDRA_TEETH", 1, 2, 0.25)]
# Wave 5: the Synthesizers' X / Y / Z (TUNE - the research has the items, not their sources).
XYZ_REGULAR = ("X", 1, 1, 0.04)                 # every regular isle mob
XYZ_BOSS = ("Y", 2, 4, 1.0)                     # every mini-boss (shared by everyone who fought it)
XYZ_RARE = {"crimson_vanquisher": ("Z", 8, 16, 1.0), "crimson_millennia_blaze": ("Z", 4, 8, 1.0)}
BOSS_XP_OVERRIDE = {"crimson_ashfang": 2000, "crimson_magma_boss": 6000}  # NEU lists none for the Magma Boss

# Spawn rules: (id, mob, area-or-anchor-prefix, max, respawn seconds, condition)
SPAWNS = [
    ("crimson_tomb_blazes", "crimson_blaze", "area:mobs.smoldering_tomb", 14, 8, None),
    ("crimson_tomb_mutated", "crimson_mutated_blaze", "area:mobs.smoldering_tomb", 5, 15, None),
    ("crimson_tomb_smoldering", "crimson_smoldering_blaze", "area:mobs.tomb_depths", 4, 30, None),
    ("crimson_tomb_millennia", "crimson_millennia_blaze", "area:mobs.tomb_depths", 1, 600, None),
    ("crimson_bezal_flats", "crimson_bezal", "area:mobs.bezal_flats", 8, 12, None),
    ("crimson_volcano_flares", "crimson_flare", "area:mobs.volcano_slopes", 8, 15, None),
    ("crimson_volcano_eruption_flares", "crimson_flare", "area:mobs.volcano_slopes", 6, 6, "event:crimson_volcano_eruption"),
    ("crimson_volcano_cinderbats", "crimson_cinderbat", "area:mobs.volcano_rim", 3, 90, None),
    # Tinderlings (Lv100, 1,300 damage) live on the rim, not the base: from mobs.volcano_base they chased players
    # into the Cinder Shore lava-fishing pool 20 blocks away (wave-4 loops playtest: a Fishing 15 player died there twice).
    ("crimson_volcano_matcho", "crimson_matcho", "area:mobs.volcano_rim", 2, 120, None),
    ("crimson_fields_cubes", "crimson_magma_cube", "area:mobs.crimson_fields", 14, 8, None),
    ("crimson_fields_ghasts", "crimson_ghast", "area:mobs.fields_sky", 4, 30, "night"),
    ("crimson_desert_riders", "crimson_magma_cube_rider", "area:mobs.burning_desert", 8, 12, None),
    ("crimson_desert_spiders", "crimson_flaming_spider", "area:mobs.burning_desert", 10, 10, None),
    ("crimson_stronghold_skeletons", "crimson_wither_skeleton", "area:mobs.stronghold_grounds", 10, 10, None),
    ("crimson_stronghold_spectres", "crimson_wither_spectre", "area:mobs.stronghold_grounds", 5, 15, None),
    ("crimson_marsh_bulls", "crimson_mushroom_bull", "area:mobs.mystic_marsh", 10, 12, None),
    ("crimson_courtyard_knights", "crimson_kada_knight", "area:mobs.courtyard", 6, 15, None),
    ("crimson_courtyard_mages", "crimson_fire_mage", "area:mobs.courtyard", 8, 10, None),
    ("crimson_dukedom_barbarians", "crimson_barbarian", "area:mobs.dukedom", 8, 10, None),
    ("crimson_dukedom_brutes", "crimson_barbarian_brute", "area:mobs.dukedom", 3, 30, None),
    # mini-bosses: one each, on their anchor; respawn per research 03 §31.3 (2 min, Magma Boss 5 min)
    ("crimson_boss_bladesoul", "crimson_bladesoul", "anchor:boss.bladesoul", 1, 120, None),
    ("crimson_boss_mage_outlaw", "crimson_mage_outlaw", "anchor:boss.mage_outlaw", 1, 120, None),
    ("crimson_boss_barbarian_duke", "crimson_barbarian_duke", "anchor:boss.barbarian_duke", 1, 120, None),
    ("crimson_boss_ashfang", "crimson_ashfang", "anchor:boss.ashfang", 1, 120, None),
    ("crimson_boss_magma_boss", "crimson_magma_boss", "anchor:boss.magma_boss", 1, 300, None),
]


def pct(s):
    return float(s.strip().rstrip("%")) / 100.0


def neu_page(name):
    with open(os.path.join(NEU, name + ".json"), encoding="utf-8") as f:
        d = json.load(f)
    for r in d.get("recipes", []) or []:
        if r.get("type") == "drops":
            return r
    raise ValueError(name)


def parse_drops(r):
    out = []
    for dr in r.get("drops", []):
        raw = dr["id"]
        if raw.startswith("DYE_") or raw.startswith("ATTRIBUTE_SHARD_"):
            continue
        item, amount = raw, 1
        m = re.match(r"^(.*):(\d+)$", raw)
        if m:
            item, amount = m.group(1), int(m.group(2))
        if ";" in item:  # enchanted books, pets and potions: their owners place them; skip here
            continue
        lo = hi = amount
        for e in dr.get("extra") or []:
            mm = re.search(r"x(\d+)-(\d+)", re.sub("§.", "", e))
            if mm:
                lo, hi = int(mm.group(1)), int(mm.group(2))
        ch = pct(dr.get("chance", "100%"))
        d = {"item": item, "min": max(0, lo), "max": max(lo, hi), "chance": round(ch, 6)}
        out.append(d)
    return out


def main():
    mobs = []
    for (mid, name, ent, lvl, hp, dmg, page, flags, slayer, types, equip, beh) in MOBS:
        coins, xp, drops = 0, 0, []
        if page:
            r = neu_page(page)
            coins = r.get("coins") or 0
            xp = r.get("combat_xp") or 0
            drops = parse_drops(r)
        if mid in OWN:
            coins, xp, own = OWN[mid]
            drops = [{"item": i, "min": a, "max": b, "chance": c} for (i, a, b, c) in own]
        if "boss" in flags:
            drops += [{"item": i, "min": a, "max": b, "chance": c} for (i, a, b, c) in BOSS_EXTRA]
            xp = BOSS_XP_OVERRIDE.get(mid, xp)
        xyz = XYZ_BOSS if "boss" in flags else XYZ_RARE.get(mid, XYZ_REGULAR)
        drops.append({"item": xyz[0], "min": xyz[1], "max": xyz[2], "chance": xyz[3]})
        d = {
            "id": mid, "name": name, "level": lvl, "entity": ent, "health": hp, "damage": dmg,
            "combatXp": xp, "coins": coins, "drops": drops, "family": mid, "flags": flags,
            "equipment": equip, "behaviours": beh,
            "extra": {"types": types, "mobTypes": types, "island": "crimson_isle"},
        }
        if slayer:
            d["slayer"] = slayer
        if "boss" in flags:
            d["extra"]["miniboss"] = True
        mobs.append(d)

    spawns = []
    for (sid, mob, where, mx, resp, cond) in SPAWNS:
        s = {"id": sid, "island": "crimson_isle", "mob": mob, "max": mx, "respawnSeconds": resp}
        kind, val = where.split(":", 1)
        if kind == "area":
            s["area"] = val
        else:
            s["anchors"] = [val]
            s["activationRange"] = 48
        if cond:
            s["condition"] = cond
        spawns.append(s)

    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "mobs.json"), "w", encoding="utf-8") as f:
        json.dump(mobs, f, indent=1, ensure_ascii=False)
    with open(os.path.join(OUT, "spawns.json"), "w", encoding="utf-8") as f:
        json.dump(spawns, f, indent=1, ensure_ascii=False)
    print(f"mobs: {len(mobs)}  spawn rules: {len(spawns)}")


if __name__ == "__main__":
    main()
