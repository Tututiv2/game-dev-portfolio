"""Generate data/crimson/quests.json, shop.json, factions.json and dojo.json.

    python tools/gen/crimson/gen_tables.py

Sources and status of every number:
  * Faction rank thresholds 0 / 1,000 / 3,000 / 7,000 / 12,000 = research 03 §15.5 (they line up with
    the Leviathan tiers in research 04 §4.3).
  * SkyBlock XP per rank (total 75 per faction) and per Dojo belt (20/30/50/75/100/150) = research 02
    (SkyBlock XP sources).
  * Quest reputation = the reference's per-rarity reward (minecraft.wiki Faction Quests, via
    SkyblockMoney/work/kuudra.md sec 6): Common 30 / Uncommon 50 / Rare 75 / Epic 100 / Legendary 125.
    Our difficulties map onto those rarities (easy..legendary). Audit row K13 (2026-10-06).
  * Leviathan-key Emissary discount (audit K17) = SkyHanni repo constants/misc/ItemDiscounts.json: -5/10/15/20%
    on the key's COIN part at 1k/3k/7k/12k reputation; the shop entries carry "emissary": true.
  * Shop prices and Dojo belt point thresholds are not published in the research we have: TUNE values.
"""
import json
import os

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "crimson")

# reference rarity:  Common       Uncommon       Rare         Epic          Legendary
REP = {"easy": 30, "medium": 50, "hard": 75, "elite": 100, "legendary": 125}
RANK = {"easy": 0, "medium": 0, "hard": 1, "elite": 3, "legendary": 3}   # minimum faction rank index to be offered

# id, type, target, amount, faction(any/mage/barbarian), difficulty, description
QUESTS = [
    # ---- slay ----
    ("kill_blazes", "KILL", "crimson_blaze", 25, "any", "easy", "Slay {n} Blazes in the Smoldering Tomb."),
    ("kill_magma_cubes", "KILL", "crimson_magma_cube", 25, "any", "easy", "Squash {n} Pack Magma Cubes in the Crimson Fields."),
    ("kill_wither_skeletons", "KILL", "crimson_wither_skeleton", 20, "any", "easy", "Break {n} Wither Skeletons around the Stronghold."),
    ("kill_mushroom_bulls", "KILL", "crimson_mushroom_bull", 15, "any", "easy", "Cull {n} Mushroom Bulls in the Mystic Marsh."),
    ("kill_flaming_spiders", "KILL", "crimson_flaming_spider", 20, "any", "easy", "Burn out {n} Flaming Spiders in the Burning Desert."),
    ("kill_riders", "KILL", "crimson_magma_cube_rider", 15, "any", "medium", "Unseat {n} Magma Cube Riders in the Burning Desert."),
    ("kill_spectres", "KILL", "crimson_wither_spectre", 15, "any", "medium", "Banish {n} Wither Spectres around the Stronghold."),
    ("kill_mutated", "KILL", "crimson_mutated_blaze", 10, "any", "medium", "Put down {n} Mutated Blazes."),
    ("kill_scorchlings", "KILL", "crimson_bezal", 12, "any", "medium", "Snuff out {n} Scorchlings at the edge of the desert."),
    ("kill_flares", "KILL", "crimson_flare", 10, "any", "medium", "Douse {n} Flares on the Blazing Volcano."),
    ("kill_ghasts", "KILL", "crimson_ghast", 5, "any", "medium", "Shoot down {n} Dive Ghasts over the fields (they hunt at night)."),
    ("kill_smoldering", "KILL", "crimson_smoldering_blaze", 5, "any", "hard", "Defeat {n} Smoldering Blazes in the tomb's crypt."),
    ("kill_aeon_blaze", "KILL", "crimson_millennia_blaze", 1, "any", "legendary", "Defeat the Aeon-Old Blaze in the tomb's crypt."),
    ("kill_vanquisher", "KILL", "crimson_vanquisher", 1, "any", "elite", "Defeat a Vanquisher (they answer the deaths of the isle's beasts)."),
    ("raid_knights", "KILL", "crimson_kada_knight", 12, "barbarian", "medium", "Raid the Courtyard: defeat {n} Ashen Knights."),
    ("raid_fire_mages", "KILL", "crimson_fire_mage", 15, "barbarian", "easy", "Raid the Courtyard: defeat {n} Fire Mages."),
    ("raid_barbarians", "KILL", "crimson_barbarian", 15, "mage", "easy", "Purge the Dukedom: defeat {n} Barbarians."),
    ("raid_brutes", "KILL", "crimson_barbarian_brute", 4, "mage", "hard", "Purge the Dukedom: defeat {n} Barbarian Brutes."),
    # ---- mini-bosses ----
    ("boss_edgewraith", "BOSS", "crimson_bladesoul", 1, "any", "hard", "Defeat the Edgewraith in the Wraith Yard."),
    ("boss_cindermaw", "BOSS", "crimson_ashfang", 1, "any", "hard", "Defeat the Cindermaw at the Ruins of the Twin Pyres."),
    ("boss_pyromancer", "BOSS", "crimson_mage_outlaw", 1, "barbarian", "hard", "Defeat the Rogue Pyromancer in the Courtyard."),
    ("boss_duke", "BOSS", "crimson_barbarian_duke", 1, "mage", "hard", "Defeat Duke Brakkus in the Dukedom."),
    ("boss_colossus", "BOSS", "crimson_magma_boss", 1, "any", "elite", "Defeat the Magma Colossus in the Magma Chamber."),
    # ---- deliveries (handed in at your faction leader) ----
    ("fetch_magma_cream", "FETCH", "MAGMA_CREAM", 64, "any", "easy", "Deliver {n} Magma Cream."),
    ("fetch_blaze_rods", "FETCH", "BLAZE_ROD", 64, "any", "easy", "Deliver {n} Blaze Rods."),
    ("fetch_glowstone", "FETCH", "GLOWSTONE_DUST", 128, "any", "easy", "Deliver {n} Glowstone Dust."),
    ("fetch_nether_wart", "FETCH", "NETHER_STALK", 128, "any", "easy", "Deliver {n} Nether Wart."),
    ("fetch_quartz", "FETCH", "QUARTZ", 64, "any", "easy", "Deliver {n} Nether Quartz."),
    ("fetch_mycelium", "FETCH", "MYCEL", 128, "mage", "easy", "Deliver {n} Mycelium for the order's gardens."),
    ("fetch_red_sand", "FETCH", "SAND:1", 128, "barbarian", "easy", "Deliver {n} Red Sand for the forges."),
    ("fetch_ench_blaze_powder", "FETCH", "ENCHANTED_BLAZE_POWDER", 4, "any", "medium", "Deliver {n} Enchanted Blaze Powder."),
    ("fetch_blaze_ashes", "FETCH", "BLAZE_ASHES", 8, "any", "medium", "Deliver {n} Blaze Ashes."),
    ("fetch_magma_chunks", "FETCH", "MAGMA_CHUNK", 4, "any", "medium", "Deliver {n} Magma Chunks."),
    ("fetch_spectre_dust", "FETCH", "SPECTRE_DUST", 4, "any", "medium", "Deliver {n} Spectre Dust."),
    ("fetch_digested", "FETCH", "DIGESTED_MUSHROOMS", 4, "any", "medium", "Deliver {n} Digested Mushrooms."),
    ("fetch_ench_mycelium", "FETCH", "ENCHANTED_MYCELIUM", 8, "mage", "hard", "Deliver {n} Enchanted Mycelium."),
    ("fetch_ench_red_sand", "FETCH", "ENCHANTED_RED_SAND", 8, "barbarian", "hard", "Deliver {n} Enchanted Red Sand."),
    # ---- lava fishing (counted from the fishing module's FishCaught events) ----
    ("fish_lava", "FISH", "any", 15, "any", "easy", "Catch {n} things from the isle's lava."),
    ("fish_lava_creatures", "FISH_CREATURE", "any", 3, "any", "medium", "Fish up {n} lava sea creatures."),
    # ---- the Dojo ----
    ("dojo_force", "DOJO", "force", 400, "any", "easy", "Score {n}+ in the Test of Force at the Dojo."),
    ("dojo_stamina", "DOJO", "stamina", 400, "any", "easy", "Score {n}+ in the Test of Stamina at the Dojo."),
    ("dojo_mastery", "DOJO", "mastery", 400, "any", "easy", "Score {n}+ in the Test of Mastery at the Dojo."),
    ("dojo_discipline", "DOJO", "discipline", 400, "any", "easy", "Score {n}+ in the Test of Discipline at the Dojo."),
    ("dojo_swiftness", "DOJO", "swiftness", 400, "any", "easy", "Score {n}+ in the Test of Swiftness at the Dojo."),
    ("dojo_control", "DOJO", "control", 400, "any", "easy", "Score {n}+ in the Test of Control at the Dojo."),
    ("dojo_tenacity", "DOJO", "tenacity", 400, "any", "easy", "Score {n}+ in the Test of Tenacity at the Dojo."),
    ("dojo_master", "DOJO", "any", 800, "any", "hard", "Score {n}+ in any Dojo test."),
    # ---- Leviathan hunts (wave 3; the reference's "Kuudra" quests). Target = the tier to clear (or any);
    # counted from the kuudra module's clear records, so a hunt finished anywhere counts. ----
    ("hunt_leviathan", "KUUDRA", "any", 1, "any", "medium", "Bring down the Magma Leviathan in any tier."),
    ("hunt_leviathan_hot", "KUUDRA", "hot", 1, "any", "hard", "Bring down the Magma Leviathan in the Hot tier or higher."),
    ("hunt_leviathan_burning", "KUUDRA", "burning", 1, "any", "legendary", "Bring down the Magma Leviathan in the Burning tier or higher."),
]

# Faction shops. coins / items are TUNE (interim source of Leviathan-line gear until wave-2 hunts exist).
def armor(prefix, sets, rank, coins, faction):
    out = []
    for s in sets:
        for piece, mult in (("HELMET", 0.8), ("CHESTPLATE", 1.4), ("LEGGINGS", 1.2), ("BOOTS", 0.8)):
            out.append({"item": f"{prefix}{s}_{piece}", "coins": int(coins * mult), "items": ["ESSENCE_CRIMSON:20"],
                        "rank": rank, "faction": faction})
    return out

SHOP = (
    [{"item": "MYCELIUM_GENERATOR_1", "coins": 20000, "items": ["MYCEL:160"], "rank": 0, "repMin": 500, "faction": "mage"},
     {"item": "RED_SAND_GENERATOR_1", "coins": 20000, "items": ["SAND:1:160"], "rank": 0, "repMin": 500, "faction": "barbarian"},
     ]
    # Leviathan keys, all five tiers (research 04 §4.3): coins + enchanted mycelium (mages) / red sand
    # (barbarians) + 2 nether stars, sold from the rank whose reputation the tier needs (0/1k/3k/7k/12k).
    + [{"item": key, "coins": coins, "items": [f"{mat}:{n}", "CORRUPTED_NETHER_STAR:2"], "rank": rank, "faction": fac,
        "emissary": True}
       for (key, coins, n, rank) in (("KUUDRA_TIER_KEY", 200000, 2, 0), ("KUUDRA_HOT_TIER_KEY", 400000, 6, 1),
                                     ("KUUDRA_BURNING_TIER_KEY", 750000, 20, 2), ("KUUDRA_FIERY_TIER_KEY", 1500000, 60, 3),
                                     ("KUUDRA_INFERNAL_TIER_KEY", 3000000, 120, 4))
       for (fac, mat) in (("mage", "ENCHANTED_MYCELIUM"), ("barbarian", "ENCHANTED_RED_SAND"))]
    + armor("", ["AURORA", "HOLLOW", "FERVOR"], 2, 500000, "mage")
    + armor("", ["CRIMSON", "TERROR", "FERVOR"], 2, 500000, "barbarian")
    + [{"item": "RUNIC_STAFF", "coins": 3000000, "items": ["ESSENCE_CRIMSON:250"], "rank": 3, "faction": "mage"},
       {"item": "HOLLOW_WAND", "coins": 3000000, "items": ["ESSENCE_CRIMSON:250"], "rank": 3, "faction": "mage"},
       {"item": "TORMENTOR", "coins": 15000000, "items": ["ESSENCE_CRIMSON:1000", "HEAVY_PEARL:4"], "rank": 4, "faction": "barbarian"}]
)

FACTIONS = {
    "ranks": [
        {"name": "Outsider", "rep": 0, "color": "&7", "sbxp": 0},
        {"name": "Ally", "rep": 1000, "color": "&a", "sbxp": 10},
        {"name": "Trusted", "rep": 3000, "color": "&9", "sbxp": 15},
        {"name": "Honored", "rep": 7000, "color": "&5", "sbxp": 20},
        {"name": "Champion", "rep": 12000, "color": "&6", "sbxp": 30},
    ],
    "factions": {
        "mage": {"display": "Mages", "color": "&5", "leader": "Archmage Seraphine", "town": "Carmine Spire",
                 "icon": "BLAZE_POWDER", "rival_boss": "crimson_barbarian_duke"},
        "barbarian": {"display": "Barbarians", "color": "&c", "leader": "Warchief Grathe", "town": "Drakehold",
                      "icon": "IRON_AXE", "rival_boss": "crimson_mage_outlaw"},
    },
    "quests_per_day": 5,
    "daily_bonus_rep": 0,  # board-clear bonus: ours, not the reference game's (follow-up 2026-10-06); dial crimson.daily-bonus-rep
    "boss_rep": 10,
    "rival_boss_rep": 25,
    "boss_loot": None,  # filled from BOSS_LOOT below
    # audit K16: the reference pays reputation for a Leviathan (Kuudra) kill ONCE a day per tier
    # (Basic..Infernal; SkyblockMoney/work/kuudra.md sec 6). Dial crimson.kuudra-rep.
    "kuudra_rep": [10, 20, 30, 40, 50],
    # audit K17: SkyHanni ItemDiscounts.json "scaling_discounts.Emissary" - rep -> percent off the key's coin part.
    "emissary_discount": {"1000": 5, "3000": 10, "7000": 15, "12000": 20},
    # audit K18: Rollim (our Watchman Orrin, who already wears his skin) - +25 rep a day for 7 days, 24 h apart.
    "rollim": {"rep": 25, "days": 7, "hours": 24},
}

# Wave 5: mini-boss spoils (all TUNE - the research says only "faction reputation, themed armour/weapon pieces,
# essences; all count toward faction daily quests"). Every player who dealt min_damage_percent of a boss's health
# shares the kill (its own drop table with their Magic Find, coins, Combat XP, rep, bits, quest credit); pledged
# players add their faction's table, the rival champion's spoils, and a second key roll when the boss is a target
# on their quest board. "shards": the attribute shard the hunting module maps to a boss (non-killer sharers roll
# it at their own Charm Chance; hunting rolls it for the killer).
def line(item, lo, hi, ch):
    return {"item": item, "min": lo, "max": hi, "chance": ch}

BOSS_LOOT = {
    "min_damage_percent": 1.0,
    "max_range": 64,
    "faction": {
        # the faction's own Leviathan-key material (the shops sell keys for enchanted mycelium / red sand)
        "mage": [line("ENCHANTED_MYCELIUM", 1, 3, 0.5)],
        "barbarian": [line("ENCHANTED_RED_SAND", 1, 3, 0.5)],
    },
    "champion": {
        # the rival champion's own signature drop again, plus prestige materials and a shot at a nether star
        "crimson_barbarian_duke": [line("LEATHER_CLOTH", 1, 2, 1.0), line("ESSENCE_CRIMSON", 10, 20, 1.0),
                                   line("KUUDRA_TEETH", 1, 1, 0.5), line("CORRUPTED_NETHER_STAR", 1, 1, 0.1)],
        "crimson_mage_outlaw": [line("SPELL_POWDER", 1, 2, 1.0), line("ESSENCE_CRIMSON", 10, 20, 1.0),
                                line("KUUDRA_TEETH", 1, 1, 0.5), line("CORRUPTED_NETHER_STAR", 1, 1, 0.1)],
    },
    # a BOSS quest for it open on your board: a second roll at the reference's key lines (NEU: 6% / 4%)
    "quest": [line("KUUDRA_TIER_KEY", 1, 1, 0.06), line("KUUDRA_HOT_TIER_KEY", 1, 1, 0.04)],
    "shards": {"crimson_barbarian_duke": "SHARD_BARBARIAN_DUKE_X"},
}
FACTIONS["boss_loot"] = BOSS_LOOT

DOJO = {
    "trials": [
        {"id": "force", "name": "Test of Force", "icon": "IRON_SWORD", "seconds": 30,
         "how": ["Dummies wander the mat. Every hit scores;", "critical hits score more. Keep swinging!"]},
        {"id": "stamina", "name": "Test of Stamina", "icon": "FEATHER", "seconds": 45,
         "how": ["Walls sweep across the arena. Slip", "through the gap in each one untouched."]},
        {"id": "mastery", "name": "Test of Mastery", "icon": "BOW", "seconds": 40,
         "how": ["Targets rise and count down. Strike", "each one the moment it turns green."]},
        {"id": "discipline", "name": "Test of Discipline", "icon": "WOODEN_SWORD", "seconds": 35,
         "how": ["Hit the dummies marked &aSTRIKE&7.", "Hitting a &cHOLD &7dummy costs points."]},
        {"id": "swiftness", "name": "Test of Swiftness", "icon": "SUGAR", "seconds": 35,
         "how": ["A tile lights up on the floor. Reach", "it fast - quicker steps score more."]},
        {"id": "control", "name": "Test of Control", "icon": "ENDER_EYE", "seconds": 30,
         "how": ["A shade blinks around the arena. Keep", "your eyes on it for as long as you can."]},
        {"id": "tenacity", "name": "Test of Tenacity", "icon": "FIRE_CHARGE", "seconds": 40,
         "how": ["Embers fly at you from every side.", "Dodge them; each hit costs points."]},
    ],
    "max_points": 1000,
    "ranks": [{"rank": "S", "min": 1000}, {"rank": "A", "min": 800}, {"rank": "B", "min": 600},
              {"rank": "C", "min": 400}, {"rank": "D", "min": 200}, {"rank": "F", "min": 0}],
    "belts": [
        {"id": "DOJO_WHITE_BELT", "name": "White Belt", "points": 1000, "sbxp": 20},
        {"id": "DOJO_YELLOW_BELT", "name": "Yellow Belt", "points": 2000, "sbxp": 30},
        {"id": "DOJO_GREEN_BELT", "name": "Green Belt", "points": 3500, "sbxp": 50},
        {"id": "DOJO_BLUE_BELT", "name": "Blue Belt", "points": 5000, "sbxp": 75},
        {"id": "DOJO_BROWN_BELT", "name": "Brown Belt", "points": 6000, "sbxp": 100},
        {"id": "DOJO_BLACK_BELT", "name": "Black Belt", "points": 6500, "sbxp": 150},
    ],
}


def main():
    quests = [{"id": i, "type": t, "target": tg, "amount": n, "faction": f, "difficulty": d,
               "rep": REP[d], "minRank": RANK[d], "desc": desc.replace("{n}", f"{n:,}")}
              for (i, t, tg, n, f, d, desc) in QUESTS]
    os.makedirs(OUT, exist_ok=True)
    for name, obj in (("quests.json", quests), ("shop.json", SHOP), ("factions.json", FACTIONS), ("dojo.json", DOJO)):
        with open(os.path.join(OUT, name), "w", encoding="utf-8") as f:
            json.dump(obj, f, indent=1, ensure_ascii=False)
    print(f"quests: {len(quests)}  shop: {len(SHOP)}  dojo trials: {len(DOJO['trials'])}")


if __name__ == "__main__":
    main()
