# -*- coding: utf-8 -*-
"""
Generates the rift module's data.

    py -3 tools/gen/rift/gen_rift.py          (run from the SkyblockClone folder)

Reads   research/data/items.json             (tier, material, skull, colour, rift stats, motes_sell_price,
                                              rift_transferrable, requirements)
        research/data/neu-repo/items/*.json  (crafting grids)
        plugin/src/main/resources/data/*/items*.json  (which ids other modules already register)
Writes  plugin/src/main/resources/data/rift/items.json      ItemDef[]   rift gear + rift consumables we own
        plugin/src/main/resources/data/rift/mobs.json       MobDef[]    rift mobs and bosses (Rift HP scale)
        plugin/src/main/resources/data/rift/spawns.json     SpawnRule[] one per generator mob area
        plugin/src/main/resources/data/rift/recipes.json    Recipe[]    NEU grids for our items (only when every cell resolves)
        plugin/src/main/resources/data/rift/economy.json    {sell: id->motes, shops: {...}, transferable: [...]}
        docs/names/rift.md                                  rename table

Numbers: Rift Damage base 20 and Hearts base 10 are research 01 sec 2.3; Rift Time 480 s, motes per orb,
the area gates and the infusion rule are research 03 sec 19 / research 08 sec 5. Mob health, damage in
hearts, drop chances and shop prices are not in the reference data: they are marked TUNE here.
"""
import json, os, re, glob

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
DATA = os.path.join(ROOT, "research", "data")
RES = os.path.join(ROOT, "plugin", "src", "main", "resources", "data")
OUT = os.path.join(RES, "rift")
NAMES = os.path.join(ROOT, "docs", "names", "rift.md")

MATERIAL = {"WOOD_SWORD": "WOODEN_SWORD", "GOLD_SWORD": "GOLDEN_SWORD", "WOOD_SPADE": "WOODEN_SHOVEL",
            "GOLD_SPADE": "GOLDEN_SHOVEL", "DIAMOND_SPADE": "DIAMOND_SHOVEL", "IRON_SPADE": "IRON_SHOVEL",
            "SKULL_ITEM": "PLAYER_HEAD", "SPECKLED_MELON": "GLISTERING_MELON_SLICE", "GOLD_PICKAXE": "GOLDEN_PICKAXE",
            "WOOD_PICKAXE": "WOODEN_PICKAXE", "WOOD": "OAK_PLANKS", "FISHING_ROD": "FISHING_ROD", "LEASH": "LEAD",
            "SLIME_BALL": "SLIME_BALL", "STRING": "STRING", "STICK": "STICK", "BOW": "BOW",
            # integrator: legacy CARPET (durability 14 = red) is not a 26.x item; it loaded as BARRIER
            "CARPET": "RED_CARPET",
            # wave 5: legacy names on the new tools
            "RED_ROSE": "POPPY", "DIAMOND_BARDING": "DIAMOND_HORSE_ARMOR"}
TOOLS = {"BERBERIS_BLOWGUN", "DEAD_CAT_DETECTOR", "TIME_GUN"}   # wave 5: one per slot

# id -> (our name, category or None, own-words description lines, ability or None, extra)
# Descriptions are written fresh; the numbers are the reference's.
OWN = {
    # ---- the Wildthorn set (Glade Peddlers) ----
    "WYLD_HELMET": ("Wildthorn Hood", "HELMET", ["&7Woven from thicket vines. Each", "&7monster you slay in the Rift gives", "&7back &a+1s &7of Rift Time."], None, {"killTime": 1}),
    "WYLD_CHESTPLATE": ("Wildthorn Jerkin", "CHESTPLATE", ["&7Scratchy, but it keeps the", "&7Rift's chill off."], None, {}),
    "WYLD_LEGGINGS": ("Wildthorn Breeches", "LEGGINGS", ["&7Mostly bark."], None, {}),
    "WYLD_BOOTS": ("Wildthorn Treads", "BOOTS", ["&7Leave no tracks in the moss."], None, {}),
    "WYLD_SWORD": ("Wildthorn Blade", "SWORD", ["&7A branch that decided to", "&7become a sword."], None, {}),
    "LEECH_SWORD": ("Leech Sword", "SWORD", ["&7Restores &b3 Mana &7every time", "&7it hits a Rift monster."], None, {"manaOnHit": 3}),
    "TACTICIAN_MURDER_WEAPON": ("Inspector's Exhibit A", "SWORD", ["&7Evidence from a crime that", "&7has not happened yet."], None, {}),
    "SELF_RECURSIVE_PICKAXE": ("Looping Pickaxe", "SWORD", ["&7A sword that remembers being", "&7a pickaxe. Mines &9Living Metal", "&7faster."], None, {"livingMetal": True}),
    "ANTI_SENTIENT_PICKAXE": ("Mindless Pickaxe", "SWORD", ["&7It stopped thinking. It did", "&7not stop swinging."], None, {"livingMetal": True}),
    "EON_PICKAXE": ("Aeon Pickaxe", "SWORD", ["&7Older than the cave it digs."], None, {"livingMetal": True}),
    "CHRONO_PICKAXE": ("Clockwork Pickaxe", "SWORD", ["&7Ticks in time with your", "&7Rift Time."], None, {"livingMetal": True}),
    "SILVER_LACED_KARAMBIT": ("Silverthread Karambit", "SWORD", ["&7Deals &c+50% &7Rift Damage to", "&7vampires and their thralls."], None, {"vampireBonus": 0.5}),
    "SILVERTWIST_KARAMBIT": ("Silvercoil Karambit", "SWORD", ["&7Deals &c+100% &7Rift Damage to", "&7vampires and their thralls."], None, {"vampireBonus": 1.0}),
    "ASPECT_OF_THE_LEECH_1": ("Leechstep Wand I", "WAND", ["&7A wand that drinks the space", "&7between here and there."],
                              {"id": "rift_leechstep", "name": "Leechstep", "trigger": "RIGHT_CLICK", "mana": 20, "cooldown": 0.5,
                               "lore": ["&7Step &a5 blocks &7ahead of you.", "&7Only works inside the Rift."]}, {"blink": 5}),
    "ASPECT_OF_THE_LEECH_2": ("Leechstep Wand II", "WAND", ["&7It has learned to drink more."],
                              {"id": "rift_leechstep", "name": "Leechstep", "trigger": "RIGHT_CLICK", "mana": 20, "cooldown": 0.5,
                               "lore": ["&7Step &a7 blocks &7ahead of you.", "&7Only works inside the Rift."]}, {"blink": 7}),
    "ASPECT_OF_THE_LEECH_3": ("Leechstep Wand III", "WAND", ["&7It drinks whole hallways."],
                              {"id": "rift_leechstep", "name": "Leechstep", "trigger": "RIGHT_CLICK", "mana": 20, "cooldown": 0.5,
                               "lore": ["&7Step &a9 blocks &7ahead of you.", "&7Only works inside the Rift."]}, {"blink": 9}),
    # ---- armour from quests and shops ----
    "NOT_DEADGEHOG_MASK": ("Spineback Mask", "HELMET", ["&7The deadgehog would like it back."], None, {}),
    "AGARICUS_CAP_CAP": ("Mushroom Cap Cap", "HELMET", ["&7A cap made of caps."], None, {}),
    "DETRANSFIGURED_MASK": ("Borrowed Face", "HELMET", ["&7Someone in the Plaza is looking", "&7for this."], None, {}),
    "ETALPTSEHC_NORI": ("etalptsehC deppilF", "CHESTPLATE", ["&7.sdrawkcab no ti tup uoY"], None, {}),
    "ORANGE_CHESTPLATE": ("Carrot Chestplate", "CHESTPLATE", ["&7Rabbits will not stop staring."], None, {}),
    "CHICKEN_LEGGS": ("Hen Leggings", "LEGGINGS", ["&7Farmer Wilma's thanks, sewn", "&7from her best feathers."], None, {}),
    "FEMURGROWTH_LEGGINGS": ("Bonegrowth Leggings", "LEGGINGS", ["&7Heavy, but the Rift lasts longer", "&7in them."], None, {}),
    "GUNTHER_SNEAKERS": ("Racer's Sneakers", "BOOTS", ["&7Earned by beating the West Village", "&7race in under a minute."], None, {}),
    "EXCEEDINGLY_COMFY_SNEAKERS": ("Cloudstep Sneakers", "BOOTS", ["&7Like walking on Rift fog."], None, {}),
    "SNAKE_IN_A_BOOT": ("Serpent Boots", "BOOTS", ["&7There is definitely something", "&7alive in the left one."], None, {}),
    "UGLY_BOOTS": ("Hideous Boots", "BOOTS", ["&7So ugly that time itself looks", "&7away and forgets to pass."], None, {}),
    "BURNED_PANTS": ("Singed Trousers", "LEGGINGS", ["&7They cost you time to wear.", "&7Nobody knows why you would."], None, {}),
    "WIZARD_FACE": ("Archmage's Visage", "HELMET", ["&7The Starkeeper's prize for", "&7those who reached the summit."], None, {}),
    "WARM_WIZARD_FACE": ("Warm Visage", "HELMET", ["&7Still warm. Do not ask."], None, {}),
    "WARM_WIZARD_FACE_2": ("Warmer Visage", "HELMET", ["&7Warmer still."], None, {}),
    "WIZARDMAN_LEGGINGS": ("Spellwright Leggings", "LEGGINGS", ["&7Stitched with spare spells."], None, {}),
    "S_LOGO_CHESTPLATE": ("Champion's Crest", "CHESTPLATE", ["&7For champions of the Rift."], None, {}),
    "ANTI_BITE_SCARF": ("Fangguard Scarf", "HELMET", ["&7Bloodfiends find your neck far", "&7less appetising."], None, {}),
    "ANTI_BITE_SCARF_2": ("Fangguard Scarf II", "HELMET", ["&7Bloodfiends refuse to even", "&7look at your neck."], None, {}),
    "LIVELY_SEPULTURE_CHESTPLATE": ("Living Crypt Chestplate", "CHESTPLATE", ["&7A tomb that walks with you."], None, {}),
    "LEGGINGS_OF_THE_COVEN": ("Leggings of the Circle", "LEGGINGS", ["&7Worn by those who hunt the", "&7hunters."], None, {}),
    # ---- consumables ----
    "HEALING_MELON": ("Healing Melon", None, ["&7A slice that remembers being", "&7alive."],
                      {"id": "rift_melon", "name": "Mend", "trigger": "RIGHT_CLICK", "mana": 0, "cooldown": 1,
                       "lore": ["&7Eat to restore &c2❤ &7Hearts.", "&7Only works inside the Rift."]}, {"heal": 2}),
    "JUICY_HEALING_MELON": ("Juicy Healing Melon", None, ["&7Drips everywhere."],
                            {"id": "rift_melon", "name": "Mend", "trigger": "RIGHT_CLICK", "mana": 0, "cooldown": 1,
                             "lore": ["&7Eat to restore &c4❤ &7Hearts.", "&7Only works inside the Rift."]}, {"heal": 4}),
    "LUSCIOUS_HEALING_MELON": ("Luscious Healing Melon", None, ["&7Almost too good to eat."],
                               {"id": "rift_melon", "name": "Mend", "trigger": "RIGHT_CLICK", "mana": 0, "cooldown": 1,
                                "lore": ["&7Eat to restore &c6❤ &7Hearts.", "&7Only works inside the Rift."]}, {"heal": 6}),
    "RIFT_SPEED_ELIXIR": ("Rift Speed Elixir", None, ["&7Fizzes when you shake it."],
                          {"id": "rift_elixir", "name": "Drink", "trigger": "RIGHT_CLICK", "mana": 0, "cooldown": 1,
                           "lore": ["&7Gain &f+30 ✦ Speed &7for &a2 minutes&7.", "&7Only works inside the Rift."]}, {"elixir": "speed"}),
    "RIFT_STRENGTH_ELIXIR": ("Rift Might Elixir", None, ["&7Tastes like iron filings."],
                             {"id": "rift_elixir", "name": "Drink", "trigger": "RIGHT_CLICK", "mana": 0, "cooldown": 1,
                              "lore": ["&7Gain &5+5 Rift Damage &7for &a2 minutes&7.", "&7Only works inside the Rift."]}, {"elixir": "damage"}),
    "RIFT_JUMP_ELIXIR": ("Rift Jump Elixir", None, ["&7Your knees feel springy."],
                         {"id": "rift_elixir", "name": "Drink", "trigger": "RIGHT_CLICK", "mana": 0, "cooldown": 1,
                          "lore": ["&7Jump much higher for &a2 minutes&7.", "&7Only works inside the Rift."]}, {"elixir": "jump"}),
    "HOT_DOG": ("Hot Dog", None, ["&7From the cart in the Plaza.", "&7Restores &c1❤ &7Heart."],
                {"id": "rift_melon", "name": "Eat", "trigger": "RIGHT_CLICK", "mana": 0, "cooldown": 1,
                 "lore": ["&7Restore &c1❤ &7Heart.", "&7Only works inside the Rift."]}, {"heal": 1}),
    # ---- valuables and reversed junk (sold to the Mote Monger) ----
    "DISCRITE": ("Shardlite", None, ["&7A crystal that only grows", "&7where time pools."], None, {}),
    "SPOTLITE": ("Glimmerlite", None, ["&7Catches the light from", "&7tomorrow."], None, {}),
    "LARVA_HOOK": ("Larva Hook", None, ["&7Sticky. Very sticky."], None, {}),
    "MIRRORED_STICK": ("kcitS", None, ["&7.kcits a s'tI"], None, {}),
    "MIRRORED_STRING": ("gnirtS", None, ["&7.gnirts emoS"], None, {}),
    "MIRRORED_SLIME_BALL": ("llabemilS", None, ["&7.ymils llitS"], None, {}),
    "MIRRORED_WOOD": ("knalP dooW", None, ["&7.doow sdrawkcaB"], None, {}),
    "MIRRORED_LEASH": ("daeL", None, ["&7.daeL"], None, {}),
    # ---- wave 5: riddle and quest tools ----
    "BERBERIS_BLOWGUN": ("Berberis Blowgun", None, ["&7Thorns, a hollow stem and a lot", "&7of patience."],
                         {"id": "rift_blowgun", "name": "Swhooomp", "trigger": "RIGHT_CLICK", "mana": 5, "cooldown": 0.4,
                          "lore": ["&7Shoot a thorn. It does no damage, except", "&7to chickens and Pufflings. Pops balloons", "&7and presses buttons from afar."]}, {}),
    "DEAD_CAT_DETECTOR": ("Whisker Finder", None, ["&7Hums when a piece of Pharaoh", "&7is near."],
                          {"id": "rift_meownar", "name": "Meowdar", "trigger": "RIGHT_CLICK", "mana": 100, "cooldown": 9,
                           "lore": ["&7Ping the &5Rift &7for the pieces of", "&9Pharaoh&7's soul."]}, {}),
    "TIME_GUN": ("Timeshift Pistol", None, ["&7Points at rocks. Changes their", "&7birthday."],
                 {"id": "rift_time_gun", "name": "Quantum Shift", "trigger": "RIGHT_CLICK", "mana": 2, "cooldown": 0.5,
                  "lore": ["&7Aim at &9Timite &7to make it &aYoungite&7.", "&7Sneak to make it &8Obsolite &7instead.", "&7It changes back after a minute."]}, {}),
}
# materials registers some Rift items as a plain fallback (postEnable, only if absent); ours carry the ability
OVERRIDE_MATERIALS = {"BERBERIS_BLOWGUN"}

# --- mobs ------------------------------------------------------------------------------------------------
# (id, name, entity, rift hp, hearts per hit, combatXp, motes, level, skull-from-item, drops[(item,min,max,chance)], extra)
# TUNE: the reference publishes none of these per-mob numbers; they are sized so a fresh Rift player
# (20 Rift Damage) kills a common in 2-5 hits and the late mobs in 12-25.
MOBS = [
    ("rift_shy", "Bashful", "ZOMBIE", 60, 0.5, 4, 5, 5, "SHAME_CRUX", [("SHAME_CRUX", 1, 1, 0.25)], {"area": "wyld_woods"}),
    ("rift_larva", "Silk Larva", "SILVERFISH", 40, 0.5, 3, 3, 3, None, [("LARVA_SILK", 1, 2, 0.30)], {"area": "wyld_woods"}),
    ("rift_lagoon_leech", "Lagoon Leech", "DROWNED", 100, 1.0, 6, 8, 12, None, [("LIL_PAD", 1, 1, 0.20)], {"area": "black_lagoon", "timeDrain": 1}),
    ("rift_deadgehog", "Deadgehog", "HUSK", 140, 1.0, 8, 10, 15, None, [("DEADGEHOG_SPINE", 1, 1, 0.25)], {"area": "black_lagoon"}),
    ("rift_shadow", "Shade", "ZOMBIE", 160, 1.0, 10, 12, 20, "SHADOW_CRUX", [("SHADOW_CRUX", 1, 1, 0.25)], {"area": "west_village"}),
    ("rift_vermin", "Vermin", "SILVERFISH", 30, 0.5, 2, 2, 2, None, [], {"area": "west_village", "vermin": True}),
    ("rift_volt", "Sparkling", "ZOMBIE", 200, 1.0, 12, 14, 25, "VOLT_CRUX", [("VOLT_CRUX", 1, 1, 0.25)], {"area": "dreadfarm"}),
    ("rift_scribe", "Inkwraith", "ZOMBIE", 240, 1.5, 14, 16, 30, "SCRIBE_CRUX", [("SCRIBE_CRUX", 1, 1, 0.25)], {"area": "village_plaza"}),
    ("rift_autonull", "Autonull", "ZOMBIE", 300, 1.5, 16, 18, 35, None, [("NULLIFIED_METAL", 1, 1, 0.20), ("LIVING_METAL", 1, 1, 0.10)], {"area": "living_cave"}),
    ("rift_frozille", "Frostling", "STRAY", 360, 1.5, 18, 20, 40, None, [("FROSTY_CRUX", 1, 1, 0.25), ("FROZEN_WATER", 1, 1, 0.10)], {"area": "living_cave"}),
    ("rift_splatter", "Splatterling", "ZOMBIE", 400, 2.0, 35, 22, 50, "SPLATTER_CRUX", [("SPLATTER_CRUX", 1, 1, 0.25), ("HEMOVIBE", 1, 2, 0.50)], {"area": "chateau"}),
    ("rift_thrall", "Chateau Thrall", "ZOMBIE_VILLAGER", 400, 2.0, 40, 24, 55, None,
     [("HEMOVIBE", 1, 3, 0.60), ("HEALING_MELON", 1, 1, 0.08), ("VAMPIRIC_MELON", 1, 1, 0.05)], {"area": "chateau"}),
    ("rift_puff", "Puffling", "ZOMBIE", 480, 2.0, 22, 30, 60, "PUFF_CRUX", [("PUFF_CRUX", 1, 1, 0.25), ("TIMITE", 1, 1, 0.10)], {"area": "mountaintop"}),
    # bosses (summoned by an NPC, one at a time each; research 03 sec 19.2 gives Leech ~1,000 and Bacte ~5,000 Rift HP)
    ("rift_leech_sovereign", "Leech Sovereign", "DROWNED", 1000, 2.0, 120, 150, 80, None,
     [("LEECH_SUPREME_FRAGMENT", 1, 2, 1.0), ("SUPER_LEECH_MODIFIER", 1, 1, 0.05)], {"area": "black_lagoon", "boss": True, "timeDrain": 2}),
    ("rift_bloatmass", "The Bloatmass", "RAVAGER", 2500, 2.5, 250, 400, 120, None,
     [("BACTE_FRAGMENT", 1, 2, 1.0)], {"area": "colosseum", "boss": True}),   # TUNE: 2,500 not 5,000 so it can be soloed
    ("rift_chateau_lord", "Chateau Lord", "WITHER_SKELETON", 1500, 3.0, 300, 500, 150, None,
     [("HEMOGLASS", 1, 1, 0.40), ("HEMOVIBE", 5, 10, 1.0), ("UNFANGED_VAMPIRE_PART", 1, 1, 0.01)], {"area": "chateau", "boss": True}),
    # boss adds
    ("rift_leechling", "Leechling", "SILVERFISH", 30, 0.5, 2, 1, 40, None, [], {"area": "black_lagoon", "add": True, "timeDrain": 1}),
    ("rift_bloatling", "Bloatling", "ZOMBIE", 80, 1.0, 4, 2, 60, None, [], {"area": "colosseum", "add": True, "baby": True}),
    ("rift_crypt_bat", "Crypt Bat", "BAT", 40, 0.5, 4, 2, 70, None, [], {"area": "chateau", "add": True}),
]

SPAWNS = [  # (rule id, generator area, mob, max, respawn seconds)
    ("rift_wyld_shy", "mobs.wyld_shy", "rift_shy", 12, 8),
    ("rift_wyld_larva", "mobs.wyld_larva", "rift_larva", 10, 8),
    ("rift_lagoon_leech", "mobs.lagoon_leech", "rift_lagoon_leech", 10, 10),
    ("rift_lagoon_deadgehog", "mobs.lagoon_deadgehog", "rift_deadgehog", 8, 10),
    ("rift_west_shadow", "mobs.west_shadow", "rift_shadow", 10, 10),
    ("rift_west_vermin", "mobs.west_vermin", "rift_vermin", 6, 6),
    ("rift_dread_volt", "mobs.dread_volt", "rift_volt", 10, 10),
    ("rift_plaza_scribe", "mobs.plaza_scribe", "rift_scribe", 8, 12),
    ("rift_cave_autonull", "mobs.cave_autonull", "rift_autonull", 8, 12),
    ("rift_cave_frozille", "mobs.cave_frozille", "rift_frozille", 6, 12),
    ("rift_chateau_splatter", "mobs.chateau_splatter", "rift_splatter", 10, 10),
    ("rift_chateau_thrall", "mobs.chateau_thrall", "rift_thrall", 10, 10),
    ("rift_mountain_puff", "mobs.mountain_puff", "rift_puff", 12, 12),
]

# --- shops (motes) --- TUNE: no reference prices in the data; sized against mote income (25 per orb,
# 2-30 per kill) so the Wildthorn set is a first-visit buy and the summit gear is a long-term goal.
SHOPS = {
    "glade_peddler": {"name": "Glade Peddlers", "items": [
        ("WYLD_HELMET", 150, None), ("WYLD_CHESTPLATE", 200, None), ("WYLD_LEGGINGS", 175, None), ("WYLD_BOOTS", 125, None),
        ("WYLD_SWORD", 120, None), ("HEALING_MELON", 30, None), ("RIFT_SPEED_ELIXIR", 80, None), ("LARVA_HOOK", 40, None),
        ("EMPTY_ODONATA_BOTTLE", 15, None)]},
    "tower_sage": {"name": "Tower Sage", "items": [
        ("ASPECT_OF_THE_LEECH_1", 500, None), ("ASPECT_OF_THE_LEECH_2", 1500, "rift:charm:mirror"),
        ("ASPECT_OF_THE_LEECH_3", 4000, "rift:charm:living"), ("RIFT_STRENGTH_ELIXIR", 120, None), ("RIFT_JUMP_ELIXIR", 90, None),
        ("TACTICIAN_MURDER_WEAPON", 900, "rift:charm:citizen")]},
    "tailor": {"name": "Tailor Penrose", "items": [
        ("NOT_DEADGEHOG_MASK", 400, None), ("AGARICUS_CAP_CAP", 600, None), ("ORANGE_CHESTPLATE", 1800, None),
        ("FEMURGROWTH_LEGGINGS", 2600, None), ("EXCEEDINGLY_COMFY_SNEAKERS", 2400, None), ("SNAKE_IN_A_BOOT", 7500, "rift:charm:living"),
        ("DETRANSFIGURED_MASK", 3000, "rift:charm:citizen"), ("ETALPTSEHC_NORI", 900, "rift:charm:mirror"), ("HOT_DOG", 15, None)]},
    "cave_warden": {"name": "Cave Warden", "items": [
        ("SELF_RECURSIVE_PICKAXE", 1200, None), ("ANTI_SENTIENT_PICKAXE", 3000, "rift:charm:living"),
        ("EON_PICKAXE", 6000, "rift:charm:living"), ("CHRONO_PICKAXE", 9000, "rift:charm:globulate")]},
    "chateau_hunter": {"name": "Vampire Hunter Moira", "items": [
        ("JUICY_HEALING_MELON", 60, "slayer:vampire:2"), ("LUSCIOUS_HEALING_MELON", 120, "slayer:vampire:4"),
        ("SILVER_LACED_KARAMBIT", 4000, "slayer:vampire:1"), ("SILVERTWIST_KARAMBIT", 12000, "slayer:vampire:4"),
        ("ANTI_BITE_SCARF", 6000, "slayer:vampire:1"), ("ANTI_BITE_SCARF_2", 15000, "slayer:vampire:4"),
        ("WARM_WIZARD_FACE", 9000, "slayer:vampire:1"), ("WARM_WIZARD_FACE_2", 20000, "slayer:vampire:4"),
        ("LEGGINGS_OF_THE_COVEN", 10000, "slayer:vampire:3"), ("LIVELY_SEPULTURE_CHESTPLATE", 22000, "slayer:vampire:5")]},
    "starkeeper": {"name": "The Starkeeper", "items": [
        ("WIZARD_FACE", 30000, "rift:charm:celestial"), ("WIZARDMAN_LEGGINGS", 25000, "rift:charm:celestial"),
        ("S_LOGO_CHESTPLATE", 25000, "rift:charm:celestial"), ("UGLY_BOOTS", 20000, "rift:charm:celestial"),
        ("TIME_GUN", 2500, None)]},   # wave 5, TUNE: the reference hands it out on the Timite quest
    # wave 3: the Rift Gallery's curator. The necklaces "become more powerful as you fill the Rift Gallery"
    # (research 06); the module scales the inner one's Rift stats by secured/8.
    "curator": {"name": "Curator Ilsabet", "items": [
        ("RIFT_NECKLACE_INSIDE", 6000, "rift:gallery:1"), ("RIFT_NECKLACE_OUTSIDE", 40000, "rift:gallery:8")]},
    "mote_monger": {"name": "Mote Monger", "items": [
        ("MCGRUBBER_BURGER", 32000, "rift:charm:citizen"), ("HOT_DOG", 15, None), ("HEALING_MELON", 30, None),
        ("RIFT_PRISM", 4000, "rift:souls:10"), ("TALISMAN_ENRICHMENT_SWAPPER", 1500, "rift:charm:citizen")]
        + [("TALISMAN_ENRICHMENT_" + e, 3500, "rift:charm:citizen") for e in
           ["ATTACK_SPEED", "CRITICAL_CHANCE", "CRITICAL_DAMAGE", "DEFENSE", "FEROCITY", "HEALTH", "INTELLIGENCE",
            "MAGIC_FIND", "SEA_CREATURE_CHANCE", "STRENGTH", "WALK_SPEED"]]},
}
# quest / race rewards that are not sold
# Materials cross the boundary freely (they feed main-world recipes: vampire minions, crux talismans, Blood
# Donor accessories...); Rift GEAR stays in the Rift. Added on top of the research rift_transferrable list.
MATERIAL_EXPORTS = ["WILTED_BERBERIS", "AGARICUS_CAP", "CADUCOUS_STEM", "HALF_EATEN_CARROT", "HEMOVIBE", "METAL_HEART", "TIMITE",
                    "HEMOGLASS", "HEMOBOMB", "COVEN_SEAL", "UNFANGED_VAMPIRE_PART", "BLOODBADGE", "VAMPIRIC_MELON", "LIVING_METAL",
                    "SHAME_CRUX", "SHADOW_CRUX", "VOLT_CRUX", "SCRIBE_CRUX", "FROSTY_CRUX", "SPLATTER_CRUX", "PUFF_CRUX",
                    "LEECH_SUPREME_FRAGMENT", "BACTE_FRAGMENT", "DEADGEHOG_SPINE", "LIL_PAD", "LARVA_SILK", "NULLIFIED_METAL",
                    "FROZEN_WATER", "YOUNGITE", "OBSOLITE", "RIFT_PRISM", "TALISMAN_ENRICHMENT_SWAPPER", "BOTTLED_ODONATA",
                    "RIFT_NECKLACE_INSIDE", "RIFT_NECKLACE_OUTSIDE"] + [
                    "TALISMAN_ENRICHMENT_" + e for e in ["ATTACK_SPEED", "CRITICAL_CHANCE", "CRITICAL_DAMAGE", "DEFENSE", "FEROCITY",
                                                         "HEALTH", "INTELLIGENCE", "MAGIC_FIND", "SEA_CREATURE_CHANCE", "STRENGTH", "WALK_SPEED"]]
REWARD_ONLY = ["DEAD_CAT_DETECTOR", "CHICKEN_LEGGS", "GUNTHER_SNEAKERS", "BURNED_PANTS", "DISCRITE", "SPOTLITE", "MIRRORED_STICK", "MIRRORED_STRING",
               "MIRRORED_SLIME_BALL", "MIRRORED_WOOD", "MIRRORED_LEASH", "LEECH_SWORD"]

RIFT_STAT_KEYS = {"rift_time": "RIFT_TIME", "rift_intelligence": "RIFT_INTELLIGENCE", "rift_walk_speed": "RIFT_SPEED",
                  "rift_health": "RIFT_HEALTH", "rift_mana_regen": "RIFT_MANA_REGEN", "rift_damage": "RIFT_DAMAGE"}
STAT_LINE = {"RIFT_TIME": ("Rift Time", "a", "s"), "RIFT_DAMAGE": ("Rift Damage", "5", ""), "RIFT_HEALTH": ("Hearts", "c", "❤"),
             "RIFT_INTELLIGENCE": ("Intelligence", "b", ""), "RIFT_SPEED": ("Speed", "f", ""), "RIFT_MANA_REGEN": ("Mana Regen", "b", "%")}


# wave 5: riddle souls, their NPCs, the lost cat and the eyes (RiftEnigma.java holds the same names)
WAVE5_NAMES = [
    ("(npc) riddle_rourke", "Roy", "Rourke the Fence"), ("(npc) riddle_mossbeard", "Mushroom Guy", "Capwort Quorrin"),
    ("(npc) riddle_vinny", "Joey McPizza", "Vinny Saucetoss"), ("(npc) riddle_yxvael", "Lonely Avaeikx", "Lonely Yxvael"),
    ("(npc) riddle_blitzcoin", "BZT seller", "Blitzcoin Barnes"), ("(npc) riddle_prestwick", "Rabbit-in-the-hat magician", "Magician Prestwick"),
    ("(npc) riddle_digby", "The Mole", "Digby the Mole"), ("(npc) riddle_thistledown", "Fairylosopher", "Faeosopher Thistledown"),
    ("(npc) riddle_okrel", "Ikrus", "Okrel the Squatter"), ("(npc) riddle_skink", "Sun Gecko", "Solar Skink"),
    ("(npc) riddle_ubaldo", "Ubik (mountaintop)", "Ubaldo Twofold"), ("(npc) riddle_maribel", "Sad Jacquelle", "Weeping Maribel"),
    ("(mob-like) spook", "Fleespook", "Flitwraith"), ("(pet) FRACTURED_MONTEZUMA_SOUL", "Montezuma", "Pharaoh"),
    ("(collectible) cat pieces", "Montezuma's Soul Pieces", "Pharaoh's pieces"), ("(collectible) eyes", "Porhtal's Eyes", "the Warden's Eyes"),
    ("(token) BLZ", "BZT", "BLZ"),
    ("(soul) rift_enigma_original", "Next to Enigma", "Beside the Riddler"), ("(soul) rift_enigma_jump_pad_1", "Up in the Sky", "Up Among the Clouds"),
    ("(soul) rift_enigma_two_plates", "Two Plates", "Twin Plates"), ("(soul) rift_enigma_phantom", "Fleespook", "Flitwraith of the Thicket"),
    ("(soul) rift_enigma_flower_pot_1", "Woods Flower Pot", "Thicket Flower Pots"), ("(soul) rift_enigma_pressure_water", "Pressurized", "Under Pressure"),
    ("(soul) rift_enigma_bark", "Tough Bark", "Toughest Bark"), ("(soul) rift_enigma_wither_cage", "Wither Cage", "Hidden in Plain Sight"),
    ("(soul) rift_enigma_silk_1", "Between Branches", "Among the Branches"), ("(soul) rift_tel_kar", "Tel Kar", "Old Vel'ka's Lessons"),
    ("(soul) rift_lagoon_parkour", "Lagoon Cave", "Dry Feet"), ("(soul) rift_lagoon_shop", "Roy", "Rourke's Bargain"),
    ("(soul) rift_rocks_enigma", "Spinning Runes", "Turning Runes"), ("(soul) rift_long_shrooms", "Mushroom Guy", "Capwort's Secret"),
    ("(soul) rift_hot_dog", "Hot Dog Contest", "Hot Dog Showdown"), ("(soul) rift_kat_soul", "Fake Neuroscience", "Pest Control"),
    ("(soul) rift_two_player_soul", "2 Players Soul", "Beacon and Hopper"), ("(soul) rift_dolphin_soul", "Dolphin Parkour", "Mirror Dive"),
    ("(soul) rift_buttons_soul", "Buttons", "Every Button"), ("(soul) rift_lonely_dave", "Lonely Avaeikx", "Lonely Yxvael"),
    ("(soul) rift_scammer", "Buy BZT Today", "Buy BLZ Today"), ("(soul) rift_rabbit_under_hat", "Rabbit in the Hat!", "Rabbit in the Hat"),
    ("(soul) rift_village_phantom", "Plaza Fleespook", "Flitwraith of the Plaza"), ("(soul) rift_cave_wall", "Behind a Living Wall", "Behind the Living Wall"),
    ("(soul) rift_mole", "Full Circle", "Full Circle"), ("(soul) rift_fairylosopher_soul", "Fairylosopher", "The Faeosopher's Stock"),
    ("(soul) rift_castle_standing", "Standing there", "Just Standing There"), ("(soul) rift_mountain_ubik_averages", "Ubik's Averages", "Ubaldo's Averages"),
    ("(soul) rift_mountain_ubik_boxes", "Ubik's Boxes", "Ubaldo's Boxes"), ("(soul) rift_mountain_ubik_tats", "Ubik's Tit for Tats", "Ubaldo's Tit for Tat"),
    ("(soul) rift_mountain_ikrus", "Ikrus", "Squat With Okrel"), ("(soul) rift_mountain_sungecko_perks", "Sun Gecko", "Solar Skink's Wares"),
    ("(soul) rift_mountain_flowerpot", "Rose's End Flower Pot", "Summit Flower Pots"),
    ("(eye) 1-7", "The Intruder / Gillman / Baba Yaga / Bankster / Gooey / Prince / 7th Sin",
     "The Trespasser's / Gill-Folk / Crone's / Banker's / Gooey / Prince's / Seventh Sin's Eye"),
]


def strip(s):
    return re.sub(r"§.", "", s or "")


def registered_elsewhere():
    ids = {}
    for f in glob.glob(os.path.join(RES, "*", "*.json")):
        mod = os.path.basename(os.path.dirname(f))
        if mod == "rift":
            continue
        try:
            d = json.load(open(f, encoding="utf-8"))
        except Exception:
            continue
        if isinstance(d, list):
            for x in d:
                if isinstance(x, dict) and "id" in x and ("material" in x or "rarity" in x):
                    ids.setdefault(x["id"], mod)
    return ids


def rift_stats(it):
    out = {}
    for k, v in (it.get("stats") or {}).items():
        kk = RIFT_STAT_KEYS.get(k.lower())
        if kk:
            out[kk] = out.get(kk, 0) + v
    return out


def stat_lore(rs):
    lines = []
    for k in ["RIFT_DAMAGE", "RIFT_HEALTH", "RIFT_TIME", "RIFT_INTELLIGENCE", "RIFT_SPEED", "RIFT_MANA_REGEN"]:
        if k in rs:
            lbl, col, suf = STAT_LINE[k]
            v = rs[k]
            vs = ("+" if v >= 0 else "") + (str(int(v)) if float(v).is_integer() else str(v))
            lines.append("&7%s: &%s%s%s" % (lbl, col, vs, suf))
    return lines


def requirement_keys(it):
    out = []
    for r in it.get("requirements", []) or []:
        if r.get("type") == "SLAYER":
            out.append("slayer:%s:%d" % (r["slayer_boss_type"].lower(), r["level"]))
    return out


def main():
    items_json = json.load(open(os.path.join(DATA, "items.json"), encoding="utf-8"))["items"]
    ref = {i["id"]: i for i in items_json}
    elsewhere = registered_elsewhere()
    os.makedirs(OUT, exist_ok=True)

    items, names = [], []
    for iid, (name, cat, desc, abil, extra) in OWN.items():
        it = ref.get(iid)
        if it is None:
            print("missing reference item", iid)
            continue
        if iid in elsewhere and not (iid in OVERRIDE_MATERIALS and elsewhere[iid] == "materials"):
            print("skip (owned by %s): %s" % (elsewhere[iid], iid))
            continue
        mat = MATERIAL.get(it["material"], it["material"])
        rs = rift_stats(it)
        d = {"id": iid, "name": name, "material": mat, "rarity": it.get("tier", "COMMON"),
             "category": cat or ("CONSUMABLE" if abil and abil["id"] in ("rift_melon", "rift_elixir") else "MATERIAL"),
             "lore": stat_lore(rs) + ([""] if rs else []) + desc + ["", "&dRift item: works only inside the Rift."],
             "tags": ["rift"], "extra": dict(extra)}
        if rs:
            d["extra"]["riftStats"] = rs
        if mat == "PLAYER_HEAD" and it.get("skin"):
            d["skull"] = it["skin"]["value"] if isinstance(it["skin"], dict) else it["skin"]
        if it.get("color"):
            r, g, b = [int(x) for x in it["color"].split(",")]
            d["color"] = "#%02X%02X%02X" % (r, g, b)
        if abil:
            d["abilities"] = [abil]
        req = requirement_keys(it)
        if req:
            d["requirements"] = req
        if it.get("rift_transferrable"):
            d["tags"].append("rift_transferable")
        if cat in ("SWORD", "WAND", "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"):
            d["tags"].append("no_reforge")
        if not cat:
            d["maxStack"] = 1 if iid in TOOLS else 64
        items.append(d)
        names.append((iid, strip(it["name"]), name))

    # ---- mobs ----
    mobs = []
    for (mid, name, ent, hp, hearts, cxp, motes, lvl, skull, drops, extra) in MOBS:
        m = {"id": mid, "name": name, "level": lvl, "entity": ent, "health": hp, "damage": 10, "combatXp": cxp,
             "coins": 0, "family": mid, "drops": [{"item": i, "min": a, "max": b, "chance": c} for (i, a, b, c) in drops],
             "flags": ["aggressive"], "extra": {"rift": True, "hearts": hearts, "motes": motes}}
        m["extra"].update({k: v for k, v in extra.items() if k not in ("boss", "baby")})
        if extra.get("boss"):
            m["flags"] = ["boss", "aggressive", "no_knockback"]
        if extra.get("baby"):
            m["flags"].append("baby")
        if ent == "BAT":
            m["flags"].append("flying")
        if extra.get("area") == "chateau":
            m["slayer"] = "vampire"
        if skull and ref.get(skull, {}).get("skin"):
            s = ref[skull]["skin"]
            m["skull"] = s["value"] if isinstance(s, dict) else s
        if ent in ("ZOMBIE", "HUSK", "DROWNED", "ZOMBIE_VILLAGER", "STRAY", "WITHER_SKELETON"):
            m["flags"].append("fire_immune")
        if mid == "rift_autonull":
            m["equipment"] = {"CHEST": "CHAINMAIL_CHESTPLATE", "LEGS": "CHAINMAIL_LEGGINGS", "HAND": "IRON_SWORD"}
        mobs.append(m)
    names += [(m[0], "", m[1]) for m in MOBS]

    spawns = [{"id": rid, "island": "rift", "area": area, "mob": mob, "max": mx, "respawnSeconds": rs}
              for (rid, area, mob, mx, rs) in SPAWNS]

    # ---- recipes (NEU grids) for our items, only when every cell is an id we know ----
    known = set(elsewhere) | {d["id"] for d in items}
    recipes, skipped = [], []
    for d in items:
        p = os.path.join(DATA, "neu-repo", "items", d["id"] + ".json")
        if not os.path.exists(p):
            continue
        neu = json.load(open(p, encoding="utf-8"))
        r = neu.get("recipe")
        if not isinstance(r, dict):
            continue
        grid = []
        ok = True
        for k in ["A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3"]:
            cell = (r.get(k) or "").strip()
            if not cell:
                grid.append("")
                continue
            cid = cell.split(":")[0]
            if cid not in known:
                ok = False
            grid.append(cell)
        if not ok:
            skipped.append(d["id"])
            continue
        recipes.append({"id": "rift:" + d["id"], "type": "CRAFTING", "result": d["id"], "amount": int(r.get("count", 1) or 1),
                        "grid": grid, "station": "Crafting Table"})
    print("recipes:", len(recipes), "skipped (unknown ingredient):", skipped)

    # ---- economy ----
    sell = {}
    for it in items_json:
        v = it.get("motes_sell_price")
        if v:
            sell[it["id"]] = v
    # wave 3: the reference gives no price for a Bottled Odonata; TUNE above its 15-Mote bottle so catching pays
    sell.setdefault("BOTTLED_ODONATA", 40)
    transferable = sorted(set(it["id"] for it in items_json if it.get("rift_transferrable")) | set(MATERIAL_EXPORTS))
    shops = {k: {"name": v["name"], "items": [{"item": i, "motes": c, "requirement": q} for (i, c, q) in v["items"]]}
             for k, v in SHOPS.items()}
    econ = {"sell": sell, "transferable": transferable, "shops": shops, "rewardOnly": REWARD_ONLY}

    def dump(name, obj):
        with open(os.path.join(OUT, name), "w", encoding="utf-8", newline="\n") as f:
            json.dump(obj, f, indent=1, ensure_ascii=False)
            f.write("\n")
    dump("items.json", items)
    dump("mobs.json", mobs)
    dump("spawns.json", spawns)
    dump("recipes.json", recipes)
    dump("economy.json", econ)
    print("items", len(items), "mobs", len(mobs), "spawns", len(spawns), "sell", len(sell), "transferable", len(transferable))

    places = [
        ("(area) wyld_woods", "Wyld Woods", "Wildwood Thicket"), ("(area) dreadfarm", "Dreadfarm", "Dread Acres"),
        ("(area) chateau", "Stillgore Chateau", "Grimhollow Chateau"), ("(area) mirrorverse", "Mirrorverse", "Looking-Glass Halls"),
        ("(area) cradle", "Enigma's Crib", "The Cradle"), ("(collectible) soul", "Enigma Soul", "Riddle Soul"),
        ("(currency) infusion", "Dimensional Infusion", "Planar Infusion"),
        ("(charm) supreme", "Supreme Timecharm", "Crown Timecharm"), ("(charm) chicken", "Chicken N Egg Timecharm", "Hatchling Timecharm"),
        ("(charm) mirror", "mrahcemiT esrevrorriM", "mrahcemiT ssalG"), ("(charm) citizen", "SkyBlock Citizen Timecharm", "Paperwork Timecharm"),
        ("(charm) living", "Living Timecharm", "Quickmetal Timecharm"), ("(charm) globulate", "Globulate Timecharm", "Gelatinous Timecharm"),
        ("(charm) vampiric", "Vampiric Timecharm", "Sanguine Timecharm"), ("(charm) celestial", "Celestial Timecharm", "Starbound Timecharm"),
        ("(npc) mote_monger", "Motes Grubber", "Mote Monger"), ("(npc) riddler", "Enigma", "Riddler Nyx"),
        ("(npc) rift_wizard", "Porhtal / Wizard", "Warden of the Arch"), ("(npc) glade_peddler", "Argofay Traffickers", "Glade Peddlers"),
        ("(npc) vel_ka", "Tel Kar", "Old Vel'ka"), ("(npc) lagoon_scientist", "Lagoon researchers", "Dr. Marsh"),
        ("(npc) racer", "Gunther", "Racer Gustav"), ("(npc) quillon", "Dr. Emmett", "Dr. Quillon"),
        ("(npc) wilma", "Shania", "Farmer Wilma"), ("(npc) barnaby", "Barry", "Mayor Barnaby"), ("(npc) tailor", "Seymour", "Tailor Penrose"),
        ("(npc) cave_warden", "Rock denizens", "Cave Warden Ostrel"), ("(npc) arena_keeper", "Colosseum host", "Arena Keeper Brutus"),
        ("(npc) chateau_hunter", "Vampire hunter", "Vampire Hunter Moira"), ("(npc) starkeeper", "Wizardman", "The Starkeeper"),
        ("(npc) tower_sage", "Wizard Tower NPC", "Tower Sage Elm"), ("(npc) curator", "Elise", "Curator Ilsabet"),
        ("(npc) split_steal", "Ubik", "Ubaldo Twofold"), ("(minigame) split_steal", "Ubik's Cube", "Split or Steal"), ("(npc) clerks", "Election workers", "Clerks Pomfrey, Quine, Rumbold, Sable"),
        ("(boss) rift_leech_sovereign", "Leech Supreme", "Leech Sovereign"), ("(boss) rift_bloatmass", "Bacte", "The Bloatmass"),
    ] + WAVE5_NAMES
    with open(NAMES, "w", encoding="utf-8", newline="\n") as f:
        f.write("# rift: renames\n\nGenerated by `tools/gen/rift/gen_rift.py` (items and mobs) plus places, NPCs and Timecharms.\n"
                "Reversed names (etalptsehC, kcitS...) keep the reference's backwards-word joke but are new words.\n\n"
                "| id | reference name | our name |\n|---|---|---|\n")
        for (a, b, c) in places + names:
            f.write("| %s | %s | %s |\n" % (a, b or "(generic mob)", c))


if __name__ == "__main__":
    main()
