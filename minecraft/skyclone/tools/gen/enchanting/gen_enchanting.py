#!/usr/bin/env python3
"""
Generates the enchanting module's data from the research dump.

    python tools/gen/enchanting/gen_enchanting.py

Reads
  research/data/neu-repo/items/<ENCHANT>;<LEVEL>.json   one file per enchanted book level: levels that
                                                        exist, rarity per level, per-level numbers (parsed
                                                        out of the book text), "Applied To", sources,
                                                        level-by-use thresholds, "cannot be combined"
  research/data/neu-repo/constants/enchants.json        item-type pools, conflict pools, table XP costs,
                                                        table max levels
  research/02-skills-collections-minions-level.md       Enchanting skill level that unlocks each enchant
  research/data/neu-repo/items/LIBRARIAN_NPC.json       the Librarian's shop

Writes (plugin/src/main/resources/data/enchanting/)
  enchants.json      one EnchantSpec per enchantment (see EnchantSpec.java)
  experiments.json   Experimentation Table tiers + reward pools
  librarian.json     Librarian shop rows
  items.json         exp bottles, experimentation table, generic enchanted book
  recipes.json       their recipes

The book TEXT is NOT copied: every description below is written in our own words. Only numbers are
taken from the data. Proper nouns are renamed (see docs/names/enchanting.md).
"""
import collections
import glob
import json
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
NEU = os.path.join(ROOT, "research", "data", "neu-repo")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "enchanting")
SKILLS_MD = os.path.join(ROOT, "research", "02-skills-collections-minions-level.md")

ROMAN = {"I": 1, "II": 2, "III": 3, "IV": 4, "V": 5, "VI": 6, "VII": 7, "VIII": 8, "IX": 9, "X": 10}
COLOR = re.compile("§.")
NUM = re.compile(r"-?\d[\d,]*\.?\d*")

# ---------------------------------------------------------------------------------------------
# Renames (proper nouns of the reference game). id -> our display name. Recorded in docs/names.
# ---------------------------------------------------------------------------------------------
RENAMES = {
    "thunderlord": "Stormlord",
    "aiming": "Dragon Seeker",
    "ultimate_jerry": "Ultimate Jest",
    "ultimate_bobbin_time": "Bobber's Chorus",
    "ultimate_habanero_tactics": "Pepper Tactics",
    "tabasco": "Scoville",
    "transylvanian": "Sanguine",
    "pesterminator": "Pestbane",
    "petalfall": "Petal Shower",
    "bug_blender": "Pest Pulper",
    "counter_strike": "Riposte",
    "great_spook": "Dread",
}

# Value overrides where research 01 §14 (the 2026 values) disagrees with the book text in the dump.
OVERRIDES = {
    "sharpness": {"values": [[5, 10, 15, 20, 30, 40, 50]]},                 # 01 §14.3 (2026 nerf; old set ended 45/65)
    "bane_of_arthropods": {"values": [[5, 10, 15, 20, 30, 40, 50]]},        # 01 §14.3 (dump text lost level IV)
    "harvesting": {"values": [[12.5, 25, 37.5, 50, 62.5, 75]]},             # 01 §14.6
    "titan_killer": {"values": [[2, 4, 6, 8, 10, 12, 14], [6, 12, 18, 24, 40, 60, 80]]},  # 01 §14.3
    "efficiency": {"maxLevel": 10, "values": [[30, 50, 70, 90, 110, 130, 150, 170, 190, 210]]},  # 01 §14.6 (VI-X via special items)
    "flame": {"values": [[3.5, 4], [3, 6]]},                                # 01 §14.4
    "true_protection": {"values": [[5]]},
    "ultimate_one_for_all": {"values": [[500]]},
    "piercing": {"values": [[25]]},
    "counter_strike": {"minLevel": 3, "values": [[0, 0, 6, 8, 10]]},       # 01 §14.5 (+6/8/10 for III-V)
    "ultimate_habanero_tactics": {"values": [[0, 0, 0, 20, 25], [0, 0, 0, 6, 7.5], [0, 0, 0, 2.5, 5], [0, 0, 0, 10, 15]]},
    "syphon": {"maxLevel": 6, "values": [[0.5, 1, 1.5, 2, 2.5, 3]]},     # 01 §6.2/§14.3: Vitality per hit, I-VI
    "scuba": {"maxLevel": 6, "values": [[1, 2, 3, 4, 5, 6]]},              # 01 §14 armor table: +1->6 Pressure Resistance
    "mana_vampire": {"values": [[0.55, 0.6, 0.65, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95, 1]]},  # 01 §6.2 (0.55 -> 1)
    "small_brain": {"values": [[0, 0, -15, -20, -25], [0, 0, 1, 2, 3]]},    # 01 §14.5
    "ultimate_no_pain_no_gain": {"values": [[20, 40, 60, 80, 100], [10, 10, 10, 10, 10]]},  # 01 §14.8 (10 orbs)
}

# Level-by-use enchants: the book is level I, the enchant climbs to X by doing the activity.
# Values at I and X from research 01 §14.3/14.6/14.5; the levels between are linear (the reference's
# own Champion table is linear: 3, 3.78, 4.56 ... 10).
LEVEL_BY_USE = {
    "champion":    {"type": "combat_xp",   "series": [(3, 10), (1.4, 5)]},
    "toxophilite": {"type": "bow_combat_xp", "series": [(3, 10), (3.7, 10)]},
    "compact":     {"type": "blocks_mined", "series": [(1, 10), (0.25, 0.57)]},
    "cultivating": {"type": "crops_harvested", "series": [(1, 10), (2, 20)]},
    "absorb":      {"type": "logs_chopped", "series": [(1, 10), (2, 20)],
                    # TUNE: the dump only says "1k logs to tier up"; we read it as 1k more per tier.
                    "thresholds": [1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000, 9000]},
    "expertise":   {"type": "sea_creature_kills", "series": [(0.6, 6), (2, 20)]},
    "hecatomb":    {"type": "s_runs", "series": [(0.28, 1), (0.56, 2), (2.6, 8)]},
}

# Our own wording. {0} {1} {2} are the per-level value series (index 0 = first changing number).
# "short" is what an item's lore shows (core fills ONE value, series 0); only needed when desc uses {1}+.
DESC = {
    "prosecute": "Increases damage dealt by &a{0}%&7 for every percent of health your target still has.",
    "absorb": ("Grants &3+{0}☯ Foraging Wisdom&7 and &6+{1}☘ Foraging Fortune&7. Levels up as you chop logs.",
               "Grants &3+{0}☯ Foraging Wisdom&7 and Foraging Fortune; levels up as you chop logs."),
    "aiming": "Your arrows curve toward dragons within &a{0}&7 blocks.",
    "angler": "Grants &3+{0}α Sea Creature Chance&7.",
    "aqua_affinity": "Mine at full speed while underwater.",
    "arcane": "Increases damage dealt to woodland mobs by &a{0}%&7.",
    "bane_of_arthropods": "Increases damage dealt to arthropod mobs by &a{0}%&7.",
    "big_brain": "Grants &b+{0}✎ Intelligence&7.",
    "blast_protection": "Grants &a+{0}❈ Defense&7 against explosions.",
    "blessing": "Raises the chance that treasure catches are GREAT or OUTSTANDING by &a{0}%&7.",
    "bug_blender": "Grants &6+{0}☘ Farming Fortune&7 while vacuuming pests.",
    "caster": "&a{0}%&7 chance to keep your bait when you cast.",
    "cayenne": ("Grants &c+{0}❤ Health&7 and &f+{1}❂ True Defense&7 for each digit of your Accessory Power.",
                "Grants &c+{0}❤ Health&7 and True Defense for each digit of your Accessory Power."),
    "champion": ("Gain &a{0}%&7 extra Combat XP, and every kill pays &6+{1} coins&7. Levels up from the Combat XP you earn.",
                 "Gain &a{0}%&7 extra Combat XP. Levels up from the Combat XP you earn."),
    "chance": "Increases the chance of a monster dropping an item by &a{0}%&7.",
    "charm": "Raises the chance of a higher-tier trophy catch by &a{0}%&7.",
    "cleave": ("Deals &a{0}%&7 of your hit to other monsters within &a{1}&7 blocks of the target.",
               "Deals &a{0}%&7 of your hit to other monsters near the target."),
    "compact": ("Grants &3+{0}☯ Mining Wisdom&7 and a &a{1}%&7 chance for a mined block to drop its enchanted form. Levels up as you mine.",
                "Grants &3+{0}☯ Mining Wisdom&7 and a chance at enchanted drops; levels up as you mine."),
    "corruption": "&a{0}%&7 chance for a sea creature to surface corrupted.",
    "counter_strike": "The first hit an enemy lands on you grants &a+{0}❈ Defense&7 for &a7s&7.",
    "critical": "Increases &9☠ Crit Damage&7 by &a{0}%&7.",
    "cubism": "Increases damage dealt to slimes, magma cubes and creepers by &a{0}%&7.",
    "cultivating": ("Grants &3+{0}☯ Farming Wisdom&7 and &6+{1}☘ Farming Fortune&7. Levels up as you harvest crops.",
                    "Grants &3+{0}☯ Farming Wisdom&7 and Farming Fortune; levels up as you harvest."),
    "dedication": "Grants &6{0}☘ Farming Fortune&7 for a crop, multiplied by your milestone in that crop.",
    "delicate": "Leaves stems and young crops standing when you swing through them.",
    "depth_strider": "Cuts how much water slows you down by &a{0}%&7.",
    "divine_gift": "Grants &b+{0}✯ Magic Find&7.",
    "dragon_hunter": "Increases damage dealt to airborne mobs by &a{0}%&7.",
    "efficiency": "Grants &6+{0}⸕ Mining Speed&7.",
    "ender_slayer": "Increases damage dealt to ender mobs by &a{0}%&7.",
    "execute": "Increases damage dealt by &a{0}%&7 for every percent of health your target is missing.",
    "experience": "&a{0}%&7 chance for mobs and ores to drop double experience.",
    "expertise": ("Grants &3+{0}α Sea Creature Chance&7 and &3+{1}☯ Fishing Wisdom&7. Levels up as you kill sea creatures.",
                  "Grants &3+{0}α Sea Creature Chance&7; levels up from sea creature kills."),
    "feast": "Grants &a+{0}&7 Overbloom.",
    "feather_falling": ("You can fall &a{0}&7 extra blocks safely, and fall damage is cut by &a{1}%&7.",
                        "You can fall &a{0}&7 extra blocks without getting hurt."),
    "ferocious_mana": "Turns &a{0}%&7 of the Vitality you spend into Attack Speed for 10s (up to 5).",
    "fire_aspect": ("Sets enemies alight for &a{0}s&7, burning them for &a{1}%&7 of your hit every second.",
                    "Sets enemies alight for &a{0}s&7."),
    "fire_protection": "Grants &f+{0}❂ True Defense&7 against fire and lava.",
    "first_strike": "Your first hit on a mob deals &a{0}%&7 more melee damage.",
    "flame": ("Arrows set enemies alight for &a{0}s&7, burning them for &a{1}%&7 of the hit every second.",
              "Arrows set enemies alight for &a{0}s&7."),
    "forest_pledge": "Grants &6+{0}☘ Foraging Fortune&7.",
    "fortune": "Grants &6+{0}☘ Mining Fortune&7, raising your odds of extra drops.",
    "frail": "Sea creatures you hook surface with &a{0}%&7 less health.",
    "frost_walker": "Walking over water freezes a path of ice &a{0}&7 blocks wide around you.",
    "giant_killer": ("Increases damage dealt by &a{0}%&7 for each percent of max health your target has above yours, up to &a{1}%&7.",
                     "Increases damage by &a{0}%&7 for each percent of extra health your target has over you."),
    "great_spook": "Grants &a+1&7 Fear while you wear the haunted set.",
    "green_thumb": "Grants &6{0}☘ Farming Fortune&7 for each unique garden visitor you have served.",
    "growth": "Grants &c+{0}❤ Health&7.",
    "hardened_mana": "Turns &a{0}%&7 of the Vitality you spend into Defense for 10s (up to 25).",
    "harvesting": "Grants &6+{0}☘ Farming Fortune&7, raising your odds of extra crops.",
    "hecatomb": ("Gain &a+{0}%&7 Catacombs XP and &a+{1}%&7 class XP (doubled on S+ runs), plus &c+{2}❤&7 per 10 Catacombs levels. Levels up from S runs.",
                 "Gain &a+{0}%&7 Catacombs XP and more class XP; levels up from S runs."),
    "ice_cold": "Grants &b+{0}❄ Cold Resistance&7.",
    "impaling": "Increases damage dealt to aquatic mobs by &a{0}%&7.",
    "infinite_quiver": "&a{0}%&7 chance not to use up an arrow when you fire.",
    "karma": "&a{0}%&7 chance for tree gifts to hold double loot.",
    "knockback": "Knocks enemies back an extra &a{0}&7 blocks.",
    "lapidary": ("Grants &6+{0}☘ Gemstone Fortune&7 and &6+{1}⸕ Mining Speed&7 while mining gemstones.",
                 "Grants &6+{0}☘ Gemstone Fortune&7 and faster gemstone mining."),
    "lethality": "Each melee hit lowers your target's ❈ Defense by &a{0}%&7 for 4s, stacking up to 4 times.",
    "life_steal": "Heals you for &c{0}❤&7 every time you hit a mob.",
    "looting": "Increases the chance of a monster dropping an item by &a{0}%&7.",
    "luck": "Increases the chance of monsters dropping their armor by &a{0}%&7.",
    "luck_of_the_sea": "Grants &6+{0}⛃ Treasure Chance&7.",
    "lure": "Cuts the longest possible wait for a bite by &a{0}%&7.",
    "magmarizer": "Increases damage dealt to magmatic mobs by &a{0}%&7.",
    "magnet": "Grants &a{0}&7 extra experience orbs for every catch.",
    "mana_steal": "Restores &b{0}%&7 of your max mana each time you hit.",
    "mana_vampire": "Heals &c{0}❤&7 for every point of Vitality you spend.",
    "overload": ("Increases &9☠ Crit Damage&7 by &a{0}%&7 and &9☣ Crit Chance&7 by &a{1}%&7. Above 100% Crit Chance, hits may turn into Mega Crits dealing &a{2}%&7 extra.",
                 "Increases Crit Damage and Crit Chance by &a{0}%&7, with a shot at Mega Crits."),
    "paleontologist": "Raises the chance of finding Suspicious Scrap in glacite shafts by &a{0}%&7.",
    "pesterminator": ("Grants &6+{0}☘ Farming Fortune&7 and &2+{1}ൠ Bonus Pest Chance&7.",
                      "Grants &6+{0}☘ Farming Fortune&7 and Bonus Pest Chance."),
    "petalfall": "Grants &a+{0}&7 Timber.",
    "piercing": "Arrows pass through enemies; every extra target takes &a{0}%&7 of the damage.",
    "piscary": "Grants &b+{0}☂ Fishing Speed&7.",
    "power": "Increases bow damage by &a{0}%&7.",
    "pristine": "Grants &5+{0}✧ Pristine&7, raising the quality of gemstones you mine.",
    "projectile_protection": "Grants &a+{0}❈ Defense&7 against projectiles.",
    "prosperity": "Grants &c+{0}❤ Health&7.",
    "protection": "Grants &a+{0}❈ Defense&7.",
    "punch": "Arrows knock enemies back an extra &a{0}&7 blocks.",
    "quantum": ("Grants &4+{0}♨ Vitality&7 on weekdays, and &3+{1}☯&7 of a random Wisdom on weekends.",
                "Grants &4+{0}♨ Vitality&7 on weekdays and Wisdom on weekends."),
    "quick_bite": "Fish swim toward your bobber &a{0}%&7 faster.",
    "rainbow": "Sheared sheep drop wool of a random colour, and take on that colour.",
    "reflection": ("Grants &b+{0}✎ Intelligence&7 and &f+{1}❂ True Defense&7. An arrow that hits you costs its shooter &a{2}x&7 your Intelligence.",
                   "Grants &b+{0}✎ Intelligence&7 and True Defense, and punishes archers."),
    "rejuvenate": "Grants &c+{0}❣ Health Regen&7.",
    "replenish": "Breaking crops replants them from the seeds in your inventory.",
    "respiration": "Grants &a+{0}&7 Respiration, letting you stay underwater longer.",
    "respite": "Grants &c+{0}❣ Health Regen&7 while out of combat.",
    "scavenger": "Collect &6{0} coins&7 per monster level on every kill.",
    "scuba": "Grants &9+{0} Pressure Resistance&7 for diving deep.",
    "sharpness": "Increases melee damage dealt by &a{0}%&7.",
    "silk_touch": "Lets you collect blocks that would normally drop something else.",
    "small_brain": ("Changes your Intelligence by &b{0}✎&7 and grants &f+{1}❂ True Defense&7.",
                    "Changes your Intelligence by &b{0}✎&7 in exchange for True Defense."),
    "smarty_pants": "Grants &b+{0}✎ Intelligence&7.",
    "smelting_touch": "Blocks you break come out already smelted.",
    "smite": "Increases damage dealt to undead, skeletal and wither mobs by &a{0}%&7.",
    "smoldering": "Increases damage dealt to infernal mobs by &a{0}%&7.",
    "snipe": "Arrows deal &a+{0}%&7 damage for every 10 blocks they travel.",
    "spiked_hook": "Your fishing rod deals &a{0}%&7 more damage to monsters.",
    "stealth": "Timid creatures have a &a{0}%&7 chance to hold still instead of fleeing.",
    "strong_mana": "Turns &a{0}%&7 of the Vitality you spend into Strength for 10s (up to 30).",
    "sugar_rush": "Grants &f+{0}✦ Speed&7.",
    "sunder": "Grants &6+{0}☘ Farming Fortune&7 on axes.",
    "syphon": "Every hit on a mob restores &4+{0}♨ Vitality&7 (1s cooldown).",
    "tabasco": "Grants &c+{0}&7 weapon damage.",
    "thorns": "&a50%&7 chance to send &a{0}%&7 of the damage you take back at the attacker.",
    "thunderbolt": "Every 3rd hit on a monster calls lightning, dealing &a{0}%&7 of the hit to up to 10 monsters within 2 blocks.",
    "thunderlord": "Every 3rd hit on a monster calls lightning, dealing &a{0}%&7 of the hit's damage.",
    "tidal": "Grants &a+{0}%&7 ❈ Defense against sea creatures.",
    "titan_killer": ("Increases damage dealt by &a{0}%&7 for every 100 Defense your target has, up to &a{1}%&7.",
                     "Increases damage dealt by &a{0}%&7 for every 100 Defense your target has."),
    "toxophilite": ("Gain &a{0}%&7 extra Combat XP and &9+{1}☣ Crit Chance&7. Levels up from the Combat XP you earn with a bow.",
                    "Gain &a{0}%&7 extra Combat XP and some Crit Chance; levels up as you shoot."),
    "transylvanian": ("Heals &c{0}❤&7 per second for each enemy within 10 blocks, up to &c{1}❤&7 per second.",
                      "Heals &c{0}❤&7 per second for each enemy close by."),
    "triple_strike": "Your first three hits on a mob deal &a{0}%&7 more melee damage.",
    "true_protection": "Grants &f+{0}❂ True Defense&7.",
    "ultimate_bank": ("Keep &a{0}%&7 of your purse when you die, and enemies drop &6+{1} coins&7.",
                      "Keep &a{0}%&7 of your purse when you die."),
    "ultimate_bobbin_time": "Boosts every fishing stat and Magic Find by &a{0}%&7 for each bobber within 30 blocks (up to 5).",
    "ultimate_chimera": "Copies &a{0}%&7 of your active pet's stats.",
    "ultimate_combo": ("Each kill adds &a+{0}%&7 damage, stacking up to &a{1}&7 kills that land within &a{2}s&7 of each other.",
                       "Each kill adds &a+{0}%&7 damage while your streak lasts."),
    "ultimate_crop_fever": "Breaking crops has a &a{0}%&7 chance to start 60s of Crop Fever: extra crop rolls, +15 Overbloom and &6+100☘ Farming Fortune&7.",
    "ultimate_fatal_tempo": "Each hit raises your ⫽ Ferocity by &a{0}%&7, up to 200%, until 3s after your last attack.",
    "ultimate_first_impression": ("Log breaks made with a melee swing grant &a+{0}∮ Sweep&7, and the first log of each tree grants &a+{1}∮ Sweep&7.",
                                  "Log breaks made with a melee swing grant &a+{0}∮ Sweep&7."),
    "ultimate_flash": "&a{0}%&7 chance for a fish to bite the moment your bobber lands.",
    "ultimate_flowstate": "Each block in a mining streak grants &6+{0}⸕ Mining Speed&7, up to 200 blocks; the streak breaks after 10s idle.",
    "ultimate_habanero_tactics": ("With a slayer weapon: deal &a+{0}%&7 damage, gain &3+{2}☯ Combat Wisdom&7, and heal &a+{1}%&7 more from wands.",
                                  "Deal &a+{0}%&7 damage with slayer weapons, with more wisdom and healing."),
    "ultimate_inferno": "Every 10th hit on a mob traps it in flames that deal &a{0}%&7 of that hit over 5s.",
    "ultimate_jerry": "Raises the base damage of the jester's blade by &a{0}%&7.",
    "ultimate_last_stand": ("Dropping below 40% health grants &a+{0}%&7 ❈ Defense for 10s and restores &a{1}&7 Vitality (30s cooldown).",
                            "Dropping below 40% health grants &a+{0}%&7 Defense for 10s."),
    "ultimate_legion": "Boosts every combat stat and Magic Find by &a{0}%&7 for each player within 30 blocks (up to 20).",
    "ultimate_missile": "Cuts the log penalty on thrown axes by &a{0}%&7.",
    "ultimate_no_pain_no_gain": ("&a{0}%&7 chance to gain &a{1}&7 experience orbs whenever a mob hits you.",
                                 "&a{0}%&7 chance to gain experience whenever a mob hits you."),
    "ultimate_one_for_all": "Strips every other enchantment from the weapon but raises its damage by &a{0}%&7.",
    "ultimate_refrigerate": "Turns &a{0}%&7 of the mana you spend into ❈ Defense for 10s (up to 150).",
    "ultimate_reiterate": ("Fires a second arrow dealing &a{0}%&7 of the first; targets it hits take &a{1}x&7 fire damage for 60s.",
                           "Fires a second arrow dealing &a{0}%&7 of the first."),
    "ultimate_rend": "Left-click to rip your arrows out of nearby enemies, each dealing &a{0}%&7 of your last critical shot (up to 5 arrows, 2s cooldown).",
    "ultimate_soul_eater": "Stores &a{0}x&7 the damage of the last monster you killed and adds it as ❁ Strength to your next critical hit.",
    "ultimate_sunset": ("By day, &a+{0}&7 Overbloom; by night, your garden visitor cooldown changes by &a{1}%&7.",
                        "By day, &a+{0}&7 Overbloom; by night, visitors come back sooner."),
    "ultimate_swarm": "Increases your damage by &a{0}%&7 for each enemy within 10 blocks (up to 10).",
    "ultimate_the_one": ("Grants &c+{0}❤ Health&7 and &c+{1}❁ Strength&7 for every collection you have maxed.",
                         "Grants &c+{0}❤ Health&7 and Strength for every maxed collection."),
    "ultimate_wisdom": ("Grants &b+{0}✎ Intelligence&7 for every 5 experience levels you hold, up to &b{1}&7.",
                        "Grants &b+{0}✎ Intelligence&7 for every 5 experience levels you hold."),
    "ultimate_wise": "Lowers this item's ability mana costs by &a{0}%&7.",
    "vampirism": "Heals &c{0}❤&7 every time you kill a mob (0.5s cooldown).",
    "venomous": ("Each hit slows the target by &a{0}%&7 and poisons it for &a{1}%&7 of your damage per second, stacking up to 40 hits for 5s.",
                 "Each hit slows the target by &a{0}%&7 and poisons it."),
    "vicious": "Grants &c+{0}⫽ Ferocity&7.",
}
TURBO = {"turbo_cactus": "Cactus", "turbo_cane": "Sugar Cane", "turbo_carrot": "Carrot", "turbo_coco": "Cocoa Bean",
         "turbo_melon": "Melon", "turbo_moonflower": "Moonflower", "turbo_mushrooms": "Mushroom", "turbo_potato": "Potato",
         "turbo_pumpkin": "Pumpkin", "turbo_rose": "Wild Rose", "turbo_sunflower": "Sunflower", "turbo_warts": "Nether Wart",
         "turbo_wheat": "Wheat"}
for k, crop in TURBO.items():
    DESC[k] = "Grants &6+{0}☘ " + crop + " Fortune&7."

# Enchant -> stats it grants on the item (ItemStatContributor). series index, scale.
STATS = {
    "protection": [("DEFENSE", 0)], "growth": [("HEALTH", 0)], "true_protection": [("TRUE_DEFENSE", 0)],
    "sugar_rush": [("SPEED", 0)], "smarty_pants": [("INTELLIGENCE", 0)], "big_brain": [("INTELLIGENCE", 0)],
    "small_brain": [("INTELLIGENCE", 0), ("TRUE_DEFENSE", 1)], "reflection": [("INTELLIGENCE", 0), ("TRUE_DEFENSE", 1)],
    "rejuvenate": [("HEALTH_REGEN", 0)], "critical": [("CRIT_DAMAGE", 0)],
    "overload": [("CRIT_DAMAGE", 0), ("CRIT_CHANCE", 1)], "vicious": [("FEROCITY", 0)], "divine_gift": [("MAGIC_FIND", 0)],
    "efficiency": [("MINING_SPEED", 0)], "fortune": [("MINING_FORTUNE", 0)], "harvesting": [("FARMING_FORTUNE", 0)],
    "sunder": [("FARMING_FORTUNE", 0)], "cultivating": [("FARMING_WISDOM", 0), ("FARMING_FORTUNE", 1)],
    "compact": [("MINING_WISDOM", 0)], "absorb": [("FORAGING_WISDOM", 0), ("FORAGING_FORTUNE", 1)],
    "expertise": [("SEA_CREATURE_CHANCE", 0), ("FISHING_WISDOM", 1)], "angler": [("SEA_CREATURE_CHANCE", 0)],
    "piscary": [("FISHING_SPEED", 0)], "luck_of_the_sea": [("TREASURE_CHANCE", 0)], "pristine": [("PRISTINE", 0)],
    "lapidary": [("GEMSTONE_FORTUNE", 0)], "pesterminator": [("FARMING_FORTUNE", 0), ("PEST_CHANCE", 1)],
    "forest_pledge": [("FORAGING_FORTUNE", 0)], "ice_cold": [("COLD_RESISTANCE", 0)], "prosperity": [("HEALTH", 0)],
    "champion": [("COMBAT_WISDOM", 0)], "toxophilite": [("COMBAT_WISDOM", 0), ("CRIT_CHANCE", 1)],
}

LABELS = {
    "Sword": ["SWORD", "DUNGEON_WEAPON"], "Longsword": ["LONGSWORD"], "Gauntlet": ["GAUNTLET"],
    "Fishing Weapon": ["FISHING_WEAPON"], "Fishing Rod": ["FISHING_ROD"], "Bow": ["BOW"], "Axe": ["AXE"],
    "Pickaxe": ["PICKAXE"], "Drill": ["DRILL"], "Shovel": ["SHOVEL"], "Shears": ["SHEARS"], "Hoe": ["HOE"],
    "Farming Tool": ["HOE"], "Vacuum": ["VACUUM"], "Wand": ["WAND"], "Necklace": ["NECKLACE"],
    "Armor": ["HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"], "Helmet": ["HELMET"], "Chestplate": ["CHESTPLATE"],
    "Leggings": ["LEGGINGS"], "Boots": ["BOOTS"],
    "Tools": ["AXE", "PICKAXE", "SHOVEL", "DRILL", "SHEARS", "GAUNTLET", "HOE"],
}
POOL_KEYS = {
    "SWORD": ["SWORD", "DUNGEON_WEAPON"], "BOW": ["BOW"], "AXE": ["AXE"], "PICKAXE": ["PICKAXE"], "DRILL": ["DRILL"],
    "FISHING ROD": ["FISHING_ROD"], "FISHING WEAPON": ["FISHING_WEAPON"], "SHOVEL": ["SHOVEL"],
    "FARMING_TOOL": ["HOE"], "FARMING TOOL": ["HOE"], "HELMET": ["HELMET"], "CHESTPLATE": ["CHESTPLATE"],
    "LEGGINGS": ["LEGGINGS"], "BOOTS": ["BOOTS"], "LONGSWORD": ["LONGSWORD"], "GAUNTLET": ["GAUNTLET"],
    "GLOVES": ["GLOVES"], "BELT": ["BELT"], "NECKLACE": ["NECKLACE"], "CLOAK": ["CLOAK"], "CARNIVAL MASK": ["HELMET"],
}
FARMING_TOOL = {"replenish", "harvesting", "cultivating", "delicate", "dedication", "feast", "ultimate_sunset",
                "ultimate_crop_fever"} | set(TURBO)
EQUIPMENT_ONLY = {"cayenne", "prosperity", "green_thumb", "quantum", "ultimate_the_one"}
EXCLUDE = {"telekinesis"}   # auto-pickup is the default here (research 08 §3.2)

# Table max levels named in research 01 §14.3/14.4 and 08 §3.2 that constants/enchants.json lacks.
EXTRA_TABLE_MAX = {"fire_aspect": 2, "knockback": 2, "punch": 2, "flame": 2, "piercing": 1, "impaling": 3,
                   "snipe": 3, "aiming": 5}

# Extra conflict groups named in research 01 §14.2 that the pools file does not carry.
EXTRA_CONFLICTS = [
    ["big_brain", "small_brain"],
    ["hardened_mana", "strong_mana", "ferocious_mana", "mana_vampire"],
    ["reflection", "thorns"],
    ["silk_touch", "smelting_touch", "fortune"],
    sorted(TURBO),
]


def roman_range(s):
    s = s.strip()
    if "-" in s:
        a, b = s.split("-", 1)
        return list(range(ROMAN.get(a.strip(), 0), ROMAN.get(b.strip(), 0) + 1))
    return [ROMAN[s]] if s in ROMAN else []


def short_num(txt):
    t = txt.strip().lower().replace(",", ".")
    mult = 1
    if t.endswith("k"):
        mult, t = 1000, t[:-1]
    elif t.endswith("m"):
        mult, t = 1_000_000, t[:-1]
    return int(round(float(t) * mult))


def load_books():
    books = collections.defaultdict(dict)
    for f in glob.glob(os.path.join(NEU, "items", "*;*.json")):
        d = json.load(open(f, encoding="utf-8"))
        m = re.search(r"enchantments:\{([a-zA-Z_]+):(\d+)\}", d.get("nbttag", ""))
        if not m:
            continue
        books[m.group(1).lower()][int(m.group(2))] = [COLOR.sub("", x) for x in d.get("lore", [])]
    return books


def parse_book(lore):
    """name, description lines, applied-to labels, source entries, thresholds, combinable."""
    name = re.sub(r"\s+([IVX]+|\d+)$", "", lore[2]).strip()
    desc = []
    for x in lore[3:]:
        if x.strip() == "":
            break
        desc.append(x)
    applied = []
    if "Applied To:" in lore:
        for x in lore[lore.index("Applied To:") + 1:]:
            if not x.startswith("- "):
                break
            applied.append(x[2:].strip())
    sources = []
    for key in ("Source:", "Sources:"):
        if key in lore:
            for x in lore[lore.index(key) + 1:]:
                if x.strip() == "" or x.startswith("Applied"):
                    break
                m = re.match(r"^([IVX]+(?:-[IVX]+)?):\s*(.*)$", x.strip())
                if m:
                    sources.append([m.group(1), m.group(2)])
                elif sources:
                    sources[-1][1] += " " + x.strip()
    thresholds = []
    for x in lore:
        m = re.search(r"(\d[\d.,]*[kKmM]?)\s.*for Tier ([IVX]+)", x)
        if m:
            thresholds.append(short_num(m.group(1)))
    combinable = not any("cannot be combined" in x for x in lore)
    return name, desc, applied, sources, thresholds, combinable


def series_of(levels, by_level):
    rows = []
    for lv in levels:
        _, desc, *_ = parse_book(by_level[lv])
        rows.append([float(n.replace(",", "")) for n in NUM.findall(" ".join(desc))])
    n = min(len(r) for r in rows) if rows else 0
    out, consts = [], []
    for k in range(n):
        col = [r[k] for r in rows]
        (out if len(set(col)) > 1 else consts).append(col if len(set(col)) > 1 else col[0])
    return out, consts


def clean(v):
    return int(v) if float(v).is_integer() else round(float(v), 4)


def snake(name):
    """The id other modules derive from an enchant NAME (same rule as tools/gen/skills snake())."""
    s = name.lower().replace("'", "").replace("-", "_").replace("!", "")
    return re.sub(r"[^a-z0-9]+", "_", s).strip("_")


def add_aliases(specs):
    """Alternative ids per enchant: what a module gets by snake-casing the reference name or our name,
    and the id without its 'ultimate_' prefix. The skills table names its unlocks that way
    ('woodsplitter' for arcane, 'bank' for ultimate_bank); the module registers each alias so every
    such lookup resolves. An alias never shadows a real id and never belongs to two enchants."""
    real = {s["id"] for s in specs}
    owner = {}
    for s in specs:
        cands = [snake(s["referenceName"]), snake(s["name"])]
        if s["id"].startswith("ultimate_"):
            cands.append(s["id"][len("ultimate_"):])
        for a in cands:
            if not a or a == s["id"] or a in real:
                continue
            if owner.get(a, s["id"]) != s["id"]:
                raise SystemExit(f"alias {a} claimed by both {owner[a]} and {s['id']}")
            owner[a] = s["id"]
    for s in specs:
        s["aliases"] = sorted(a for a, o in owner.items() if o == s["id"])


def skill_requirements(name_to_id):
    req = {}
    txt = open(SKILLS_MD, encoding="utf-8").read()
    start = txt.find("Special unlocks (beyond stats/coins/SB XP)", txt.find("**Perk:** Conjurer"))
    block = txt[start:txt.find("####", start)]
    for line in block.splitlines():
        m = re.match(r"^\|\s*(\d+)\s*\|\s*(.+?)\s*\|$", line)
        if not m:
            continue
        lvl = int(m.group(1))
        for part in m.group(2).split(";"):
            part = part.strip()
            if not part.endswith("Enchantment"):
                continue
            nm = part[: -len("Enchantment")].strip().lower()
            if nm in name_to_id:
                req[name_to_id[nm]] = lvl
            else:
                print("  (skill table) no enchant named", repr(nm))
    return req


def main():
    consts = json.load(open(os.path.join(NEU, "constants", "enchants.json"), encoding="utf-8"))
    pools = {k: [x.lower() for x in v] for k, v in consts["enchants"].items()}
    costs = {k.lower(): v for k, v in consts["enchants_xp_cost"].items()}
    table_max = {k.lower(): v for k, v in consts["max_xp_table_levels"].items()}
    books = load_books()

    conflicts = collections.defaultdict(set)
    for group in [[x.lower() for x in g] for g in consts["enchant_pools"]] + EXTRA_CONFLICTS:
        for a in group:
            for b in group:
                if a != b:
                    conflicts[a].add(b)

    specs = []
    name_to_id = {}
    for eid in sorted(books):
        if eid in EXCLUDE:
            continue
        by_level = books[eid]
        levels = sorted(by_level)
        top = by_level[levels[-1]]
        name, _, applied, _, thresholds, combinable = parse_book(top)
        name_to_id[name.lower()] = eid
        series, cst = series_of(levels, by_level) if len(levels) > 1 else ([], [])
        o = OVERRIDES.get(eid, {})
        min_level = o.get("minLevel", levels[0])
        max_level = o.get("maxLevel", levels[-1])

        lbu = LEVEL_BY_USE.get(eid)
        if lbu:
            max_level = 10
            min_level = 1
            series = [[a + (b - a) * (i / 9.0) for i in range(10)] for (a, b) in lbu["series"]]
            if lbu.get("thresholds"):
                thresholds = lbu["thresholds"]
        values = []
        if "values" in o:
            values = [list(s) for s in o["values"]]
        else:
            for s in series:
                pad = [0] * (min_level - 1) + list(s)
                values.append(pad)
        values = [[clean(v) for v in (s + [s[-1]] * (max_level - len(s)))[:max_level]] for s in values]

        # rarity per level (pad below min with the first, above the books with the last)
        rar = []
        for lv in range(1, max_level + 1):
            src = by_level.get(lv) or by_level[min(by_level, key=lambda x: abs(x - lv))]
            rar.append(src[-1].strip() or "COMMON")

        cats = set()
        for lab in applied:
            cats.update(LABELS.get(lab, []))
        for key, members in pools.items():
            if eid in members or (eid == "ultimate_reiterate" and "ultimate_duplex" in members):
                cats.update(POOL_KEYS.get(key, []))
        if eid in EQUIPMENT_ONLY:
            cats.update(["NECKLACE", "CLOAK", "BELT", "GLOVES"] if eid in ("cayenne", "prosperity", "green_thumb") else ["NECKLACE"])
        tags = ["farming_tool"] if eid in FARMING_TOOL else []
        item_ids = ["ASPECT_OF_THE_JERRY"] if eid == "ultimate_jerry" else []
        if eid == "ultimate_jerry":
            cats.discard("SWORD")
            cats.discard("DUNGEON_WEAPON")

        # sources
        experiments = set()
        exp_src = []
        for lv in levels:
            for rng, text in parse_book(by_level[lv])[3]:
                if "Experiment" in text:
                    experiments.update(roman_range(rng))
        tmax = table_max.get(eid, EXTRA_TABLE_MAX.get(eid, 0))

        d = DESC.get(eid)
        if d is None:
            raise SystemExit("no description written for " + eid)
        desc, short = (d if isinstance(d, tuple) else (d, None))
        if short is None:
            if re.search(r"\{[1-9]\}", desc):
                raise SystemExit("description of " + eid + " uses {1}+ but has no short form")
            short = desc
        need = max([int(x) for x in re.findall(r"\{(\d)\}", desc)] + [-1]) + 1
        if need > len(values):
            raise SystemExit(f"{eid}: description needs {need} value series, data has {len(values)}: {values} {cst}")

        spec = {
            "id": eid,
            "name": RENAMES.get(eid, name),
            "referenceName": name,
            "ultimate": eid.startswith("ultimate_"),
            "minLevel": min_level,
            "maxLevel": max_level,
            "tableMax": tmax,
            "combinable": combinable and not lbu,
            "categories": sorted(cats),
            "tags": tags,
            "itemIds": item_ids,
            "conflicts": sorted(c for c in conflicts.get(eid, set()) if c in books and c not in EXCLUDE),
            "values": values,
            "rarity": rar,
            "cost": costs.get(eid, []),
            "skill": 0,
            "desc": desc,
            "short": short,
            "stats": [{"stat": s, "series": i} for (s, i) in STATS.get(eid, [])],
            "experiments": sorted(experiments),
            "levelBy": ({"type": lbu["type"], "thresholds": thresholds} if lbu else None),
        }
        if not spec["categories"]:
            print("  WARNING no categories for", eid)
        specs.append(spec)

    req = skill_requirements(name_to_id)
    for s in specs:
        s["skill"] = req.get(s["id"], 0)
    add_aliases(specs)

    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "enchants.json"), "w", encoding="utf-8") as f:
        json.dump(specs, f, ensure_ascii=False, indent=1)
    print("enchants.json:", len(specs), "enchants,", sum(s["maxLevel"] - s["minLevel"] + 1 for s in specs), "book levels")

    write_experiments(specs)
    write_anvil_upgrades(specs)
    write_librarian()
    write_items_and_recipes()


# ---- Experimentation Table -------------------------------------------------------------------
# Tier table from research 08 §3.4 (grid, add-on rounds, Enchanting level). Click counts, XP and
# reward weights are not published: marked TUNE and exposed as dials where it matters.
TIERS = [
    # id, name, skill, rows, cols, chrono rounds, ultra rounds, base clicks (TUNE), pair XP (TUNE), specials, keys
    # Enchanting gates and start fees: wiki Superpairs/Experiments tables, checked 2026-10-06 (audit G12):
    # X / XX / ? / XXX / XL / L. Grand's cell reads "Enchanting II" on the wiki (garbled); 25 is taken from
    # the same page's Chronomatron Grand row (XXV) and the community norm (SkyblockMoney farming_passive.md).
    ("beginner", "Beginner", 10, 3, 4, 5, 5, 8, 350, 0, 4),
    ("high", "High", 20, 3, 6, 7, 7, 10, 600, 2, 5),
    ("grand", "Grand", 25, 4, 7, 9, 9, 12, 900, 2, 6),
    ("supreme", "Supreme", 30, 4, 7, 11, 12, 13, 1300, 2, 6),
    ("transcendent", "Transcendent", 40, 4, 7, 13, 15, 14, 1800, 2, 7),
    ("metaphysical", "Metaphysical", 50, 5, 7, 15, 18, 15, 2500, 3, 7),
]
# Superpairs starting cost in coins, by tier (wiki: 50k / 75k / 100k / 200k / 300k / 400k).
FEES = [50000, 75000, 100000, 200000, 300000, 400000]
# Chance (percent) per Superpairs game that one pair is an ultra-rare Endcap Upgrade. Supreme and up only.
# Wiki: ultra-rares come from Supreme and above; at Metaphysical each one is 0.027% (1 in 3,700), so the
# 7-item pool is 7 x 0.027 = 0.19%. Supreme/Transcendent are not published: TUNE, scaled down.
ULTRA_RARE_CHANCE = [0, 0, 0, 0.10, 0.14, 0.19]
# The Endcap Upgrades the Experimentation Table pays out (wiki Superpairs "Ultra-Rare Items").
ULTRA_RARE = ["ENDSTONE_IDOL", "GOLDEN_BOUNTY", "GOLD_BOTTLE_CAP", "SEVERED_HAND", "TROUBLED_BUBBLE",
              "FATEFUL_STINGER", "PESTHUNTING_GUIDE"]
# Extra games bought once the charges are spent, per UTC day: XP levels + bits, each dearer than the last.
EXTRA_RUNS = [{"levels": 50, "bits": 150}, {"levels": 100, "bits": 300}, {"levels": 200, "bits": 500}]


def write_experiments(specs):
    rarity_rank = {"COMMON": 0, "UNCOMMON": 1, "RARE": 2, "EPIC": 3, "LEGENDARY": 4, "MYTHIC": 5}
    book_pool = []
    for s in specs:
        for lv in s["experiments"]:
            if lv < s["minLevel"] or lv > s["maxLevel"]:
                continue
            r = rarity_rank.get(s["rarity"][lv - 1], 0)
            book_pool.append((s["id"], lv, r))
    # research 08 §3.4 names Feather Falling VI-X and Infinite Quiver VI-X as Superpairs books.
    for eid in ("feather_falling", "infinite_quiver"):
        for lv in range(6, 11):
            book_pool.append((eid, lv, 2 if lv < 8 else 3))
    tiers = []
    for i, (tid, name, skill, rows, cols, chrono, ultra, clicks, pxp, specials, keys) in enumerate(TIERS):
        rewards = [
            {"item": "EXP_BOTTLE", "amount": 8 + 4 * i, "weight": 30},
            {"item": "GRAND_EXP_BOTTLE", "amount": 1 + i // 2, "weight": 22 if i < 3 else 14},
        ]
        if i >= 2:
            rewards.append({"item": "TITANIC_EXP_BOTTLE", "amount": 1, "weight": 8 + 2 * i})
        if i >= 5:
            rewards.append({"item": "COLOSSAL_EXP_BOTTLE", "amount": 1, "weight": 4})
        for eid, lv, r in book_pool:
            # rarity gates the tier: common/uncommon from Beginner/High, rare from Supreme, epic+ from Transcendent
            min_tier = {0: 0, 1: 1, 2: 3, 3: 4}.get(r, 5)
            if i >= min_tier:
                w = max(1, 10 - 3 * r + (i - min_tier))
                rewards.append({"item": "ENCHANTMENT_" + eid.upper() + "_" + str(lv), "amount": 1, "weight": w})
        tiles = rows * cols
        if (tiles - specials) % 2:
            specials += 1
        tiers.append({"id": tid, "name": name, "skill": skill, "fee": FEES[i], "rows": rows, "cols": cols,
                      "chronomatronRounds": chrono, "ultrasequencerRounds": ultra, "clicks": clicks,
                      "pairXp": pxp, "specials": specials, "keys": keys,
                      "ultraRareChance": ULTRA_RARE_CHANCE[i], "rewards": rewards})
    with open(os.path.join(OUT, "experiments.json"), "w", encoding="utf-8") as f:
        json.dump({"tiers": tiers,
                   "addonRoundXp": [40, 70, 110, 160, 220, 300],     # TUNE per tier index
                   "completionXpFactor": 2.0,                         # TUNE: completion bonus = pairXp x this
                   "ultraRare": ULTRA_RARE,
                   "extraRuns": EXTRA_RUNS},
                  f, ensure_ascii=False, indent=1)
    print("experiments.json:", len(tiers), "tiers,", len(book_pool), "experiment book levels")


# Endcap Upgrades: anvil items that raise ONE enchant one level past its normal top, once per item.
# Read from NEU lore ("increases its X level by 1! Can be applied once. Requires X VI!").
# Skipped: FORESTS_FAVOR (foraging applies it itself, Boosters.java) and SIL_EX (not "once"; reforges anvil).
ENDCAP_SKIP = {"FORESTS_FAVOR", "SIL_EX"}


def write_anvil_upgrades(specs):
    by_ref = {}
    for s in specs:
        by_ref[s["referenceName"].lower()] = s
        by_ref[s["name"].lower()] = s
    rows = []
    pat = re.compile(r"increases\s+its\s+(.+?)\s+level\s+by\s+1!.*?Requires\s+(.+?)\s+([IVX]+)!")
    for p in sorted(glob.glob(os.path.join(NEU, "items", "*.json"))):
        iid = os.path.basename(p)[:-5]
        if iid in ENDCAP_SKIP or ";" in iid:
            continue
        try:
            d = json.load(open(p, encoding="utf-8"))
        except (ValueError, OSError):
            continue
        text = COLOR.sub("", " ".join(d.get("lore", [])))
        m = pat.search(text)
        if not m or "applied once" not in text:
            continue
        s = by_ref.get(m.group(1).strip().lower())
        need = ROMAN.get(m.group(3))
        if s is None or need is None:
            print("  (endcap) unknown enchant for", iid, repr(m.group(1)))
            continue
        if need + 1 > s["maxLevel"]:
            print("  (endcap)", iid, "would raise", s["id"], "past its max", s["maxLevel"])
            continue
        rows.append({"item": iid, "enchant": s["id"], "requires": need})
    with open(os.path.join(OUT, "anvil_upgrades.json"), "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False, indent=1)
    print("anvil_upgrades.json:", len(rows), "endcap upgrades")


def write_librarian():
    d = json.load(open(os.path.join(NEU, "items", "LIBRARIAN_NPC.json"), encoding="utf-8"))
    rows = []
    for r in d.get("recipes", []):
        if r.get("type") != "npc_shop":
            continue
        res = r["result"]
        if ";" in res:
            e, lv = res.split(";")
            res = "ENCHANTMENT_" + e.upper() + "_" + lv
        coins = 0
        for c in r.get("cost", []):
            if c.startswith("SKYBLOCK_COIN:"):
                coins = int(c.split(":")[1])
        rows.append({"item": res, "coins": coins})
    with open(os.path.join(OUT, "librarian.json"), "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False, indent=1)
    print("librarian.json:", len(rows), "rows")


def neu_skull(item_id):
    p = os.path.join(NEU, "items", item_id + ".json")
    if not os.path.exists(p):
        return None
    m = re.search(r'Value:"([A-Za-z0-9+/=]+)"', json.load(open(p, encoding="utf-8")).get("nbttag", ""))
    return m.group(1) if m else None


def write_items_and_recipes():
    items = [
        {"id": "ENCHANTED_BOOK", "name": "Enchanted Book", "material": "ENCHANTED_BOOK", "rarity": "COMMON",
         "category": "ENCHANTED_BOOK", "maxStack": 1, "glow": True,
         "lore": ["&7A book holding one or more enchantments.", "&7Combine it with an item in an &aAnvil&7."],
         "tags": ["enchanting_book"]},
        {"id": "EXP_BOTTLE", "name": "Experience Bottle", "material": "EXPERIENCE_BOTTLE", "rarity": "COMMON",
         "category": "MATERIAL", "lore": ["&7Smash it open to receive experience!"], "tags": ["vanilla", "throwable"],
         "npcSell": 2, "extra": {"exp": 7}},
        {"id": "GRAND_EXP_BOTTLE", "name": "Grand Experience Bottle", "material": "EXPERIENCE_BOTTLE", "rarity": "UNCOMMON",
         "category": "MATERIAL", "glow": True, "lore": ["&7Crack it open for a burst of experience.", "",
                                                        "&eRight-click to use!"], "npcSell": 25, "extra": {"exp": 1250}},
        {"id": "TITANIC_EXP_BOTTLE", "name": "Titanic Experience Bottle", "material": "EXPERIENCE_BOTTLE", "rarity": "RARE",
         "category": "MATERIAL", "glow": True, "lore": ["&7Crack it open for a flood of experience.", "",
                                                        "&eRight-click to use!"], "npcSell": 250, "extra": {"exp": 5000}},
        {"id": "COLOSSAL_EXP_BOTTLE", "name": "Colossal Experience Bottle", "material": "EXPERIENCE_BOTTLE", "rarity": "EPIC",
         "category": "MATERIAL", "glow": True, "lore": ["&7Crack it open for a tidal wave of experience.", "",
                                                        "&eRight-click to use!"], "npcSell": 1000, "extra": {"exp": 20000}},
        {"id": "EXPERIMENTATION_TABLE", "name": "Experimentation Table", "material": "PLAYER_HEAD", "rarity": "RARE",
         "category": "MATERIAL", "maxStack": 1, "skull": neu_skull("EXPERIMENTATION_TABLE"),
         "lore": ["&7Place this on your island!", "", "&7Run strange enchanting experiments",
                  "&7for bonus Enchanting XP and loot."], "tags": ["placeable"],
         "requirements": ["skill:enchanting:10"]},
        # Experimentation Table ultra-rares no other module registers (the other five live in slayer,
        # materials, fishing and garden). Ordinary-word names, kept; lore is ours.
        {"id": "GOLDEN_BOUNTY", "name": "Golden Bounty", "material": "PLAYER_HEAD", "rarity": "LEGENDARY",
         "category": "MATERIAL", "skull": neu_skull("GOLDEN_BOUNTY"), "npcSell": 1000000,
         "lore": ["&8Combinable in Anvil", "", "&7Raises a weapon's &9Scavenger &7by one level.",
                  "&7Needs Scavenger V. One per weapon.", "", "&7A purse that never quite empties."],
         "tags": ["bazaar", "experiments"]},
        {"id": "FATEFUL_STINGER", "name": "Fateful Stinger", "material": "PLAYER_HEAD", "rarity": "LEGENDARY",
         "category": "MATERIAL", "skull": neu_skull("FATEFUL_STINGER"), "npcSell": 1000000,
         "lore": ["&8Combinable in Anvil", "", "&7Raises a weapon's &9Venomous &7by one level.",
                  "&7Needs Venomous VI. One per weapon.", "", "&7Still wet with something that burns."],
         "tags": ["bazaar", "experiments"]},
    ]
    recipes = [
        {"id": "enchanting_grand_exp_bottle", "result": "GRAND_EXP_BOTTLE", "amount": 1, "type": "CRAFTING",
         "grid": ["", "ENCHANTED_LAPIS_LAZULI:1", "", "ENCHANTED_LAPIS_LAZULI:1", "GLASS_BOTTLE:1",
                  "ENCHANTED_LAPIS_LAZULI:1", "ENCHANTED_LAPIS_LAZULI:1", "ENCHANTED_LAPIS_LAZULI:1", "ENCHANTED_LAPIS_LAZULI:1"]},
        {"id": "enchanting_titanic_exp_bottle", "result": "TITANIC_EXP_BOTTLE", "amount": 1, "type": "CRAFTING",
         "grid": ["", "ENCHANTED_LAPIS_LAZULI_BLOCK:1", "", "ENCHANTED_LAPIS_LAZULI_BLOCK:1", "GLASS_BOTTLE:1",
                  "ENCHANTED_LAPIS_LAZULI_BLOCK:1", "ENCHANTED_LAPIS_LAZULI_BLOCK:1", "ENCHANTED_LAPIS_LAZULI_BLOCK:1",
                  "ENCHANTED_LAPIS_LAZULI_BLOCK:1"]},
        {"id": "enchanting_experimentation_table", "result": "EXPERIMENTATION_TABLE", "amount": 1, "type": "CRAFTING",
         "grid": ["LOG:1", "LOG:1", "LOG:1", "LOG:1", "EXP_BOTTLE:1", "LOG:1", "LOG:1", "LOG:1", "LOG:1"],
         "unlock": "skill:enchanting:10"},
    ]
    with open(os.path.join(OUT, "items.json"), "w", encoding="utf-8") as f:
        json.dump(items, f, ensure_ascii=False, indent=1)
    with open(os.path.join(OUT, "recipes.json"), "w", encoding="utf-8") as f:
        json.dump(recipes, f, ensure_ascii=False, indent=1)
    print("items.json:", len(items), "items; recipes.json:", len(recipes), "recipes")


if __name__ == "__main__":
    main()
