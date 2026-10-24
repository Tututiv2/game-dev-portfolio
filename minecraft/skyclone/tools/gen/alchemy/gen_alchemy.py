#!/usr/bin/env python3
"""Generate the alchemy module's data from research/data.

    python tools/gen/alchemy/gen_alchemy.py

Reads   research/data/neu-repo/items/POTION_*.json   (every potion level: rarity, duration, numbers)
        research/data/neu-repo/items/ALCHEMIST_NPC.json   (the Alchemist's shop)
        research/data/neu-repo/items/GOD_POTION*.json     (skull texture)
Writes  plugin/src/main/resources/data/alchemy/effects.json   (effect table + ingredients + modifiers)
        plugin/src/main/resources/data/alchemy/items.json     (every potion item)
        plugin/src/main/resources/data/alchemy/recipes.json   (brewing recipes for the recipe book)
        plugin/src/main/resources/data/alchemy/shop.json      (Alchemist NPC shop)

Numbers (levels, rarities, durations, stat values) come from the NEU item files. Descriptions are
our own wording. Ingredient -> potion mapping and the alchemy XP per brew come from research 02
section 2.4.7 and research 08 section 2 (marked TUNE where the research is unsure).
"""
import json
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
NEU = os.path.join(ROOT, "research", "data", "neu-repo", "items")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "alchemy")

ROMAN = ["", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"]
ROMAN_IDX = {r: i for i, r in enumerate(ROMAN) if r}


def strip(s):
    return re.sub("§.", "", s)


def nums(text):
    return [float(x) for x in re.findall(r"[-+]?\d+(?:\.\d+)?", text)]


def fmt(v):
    v = float(v)
    return str(int(v)) if v == int(v) else ("%g" % v)


# ---------------------------------------------------------------------------------------------
# Effect specs. id = lower_snake of the reference potion key (POTION_<KEY>;<level>).
#   name       our display name (generic names kept; proper nouns renamed - docs/names/alchemy.md)
#   line       colour of the "<Name> <Roman> (m:ss)" line
#   color      potion liquid colour
#   kind       buff | debuff | instant | xp | special
#   stats      stat names for the extracted numbers, in order (None = not a stat)
#   params     param names for the extracted numbers, in order (None = skip)
#   desc       our wording, {0} {1} ... = extracted numbers (absolute value)
#   ext        duration modifiers (redstone) apply
#   corrupt    fermented spider eye turns it into this effect
#   vanilla    vanilla PotionEffectType applied while active (amplifier = level-1 unless params.amp)
#   brew_max   highest level a brewing stand may make (default: highest level in the data)
# ---------------------------------------------------------------------------------------------
E = {}


def eff(id, name, line, color, kind, desc, stats=(), params=(), ext=True, corrupt=None, vanilla=None,
        brew_max=None, negate=False, splash_only=False, seconds=None, fixed=None):
    """splash_only: the reference sells it only as a splash potion (POTION_<KEY>;<L> is thrown).
    seconds: override a NEU duration of 0 (TUNE, see the call). fixed: extra params on every level."""
    E[id] = dict(name=name, line=line, color=color, kind=kind, desc=desc, stats=list(stats), params=list(params),
                 ext=ext, corrupt=corrupt, vanilla=vanilla, brew_max=brew_max, negate=negate,
                 splash_only=splash_only, seconds=seconds, fixed=fixed or {})


eff("speed", "Speed", "&9", "#7CAFC6", "buff", "&7Increases your &f✦ Speed &7by &a{0}&7.", stats=["SPEED"],
    corrupt="slowness")
eff("strength", "Strength", "&4", "#932423", "buff", "&7Increases your &c❁ Strength &7by &a{0}&7.",
    stats=["STRENGTH"], corrupt="weakness")
eff("healing", "Healing", "&c", "#F82423", "instant", "&7Restores &c{0}❤ Health &7every second while it lasts.",
    params=["heal_per_sec"], ext=False, corrupt="damage")
eff("damage", "Damage", "&4", "#430A09", "instant", "&7Deals &c{0} &7damage the moment it lands.",
    params=["damage"], ext=False)
eff("jump_boost", "Jump Boost", "&b", "#22FF4C", "buff", "&7Lets you jump up to &a{0} &7blocks high.",
    params=["jump_height"], vanilla="JUMP_BOOST", corrupt="slowness")
eff("resistance", "Resistance", "&a", "#99453A", "buff", "&7Increases your &a❈ Defense &7by &a{0}&7.",
    stats=["DEFENSE"])
eff("water_breathing", "Water Breathing", "&3", "#2E5299", "buff",
    "&7Gives a &a{0}% &7chance to shrug off each drowning hit.", params=["drown_block"])
eff("regeneration", "Regeneration", "&d", "#CD5CAB", "buff", "&7Increases your &c❣ Health Regen &7by &a{0}&7.",
    stats=["HEALTH_REGEN"], brew_max=8)
eff("mana", "Mana", "&b", "#3D8AE8", "buff", "&7Refills &b{0}✎ Mana &7every second.", params=["mana_per_sec"])
eff("critical", "Critical", "&4", "#B02E26", "buff",
    "&7Increases your &9☣ Crit Chance &7by &a{0}% &7and your &9☠ Crit Damage &7by &a{1}%&7.",
    stats=["CRIT_CHANCE", "CRIT_DAMAGE"])
eff("archery", "Archery", "&b", "#5BC0DE", "buff", "&7Your arrows deal &a{0}% &7more damage.", params=["archery"])
eff("haste", "Haste", "&e", "#D9C043", "buff", "&7Increases your &6⸕ Mining Speed &7by &a{0}&7.",
    stats=["MINING_SPEED"])
eff("absorption", "Absorption", "&6", "#2552A5", "buff", "&7Wraps you in &6{0} &7points of absorption health.",
    params=["absorption"])
eff("adrenaline", "Adrenaline", "&c", "#E04E3B", "buff",
    "&7Wraps you in &6{0} &7absorption health and adds &a{1} &f✦ Speed&7.", params=["absorption"],
    stats=[None, "SPEED"])
eff("rabbit", "Rabbit", "&a", "#B5A07F", "buff", "&7A light step: &aJump Boost {J} &7and &a+{0} &f✦ Speed&7.",
    stats=["SPEED"], vanilla="JUMP_BOOST")
eff("agility", "Agility", "&5", "#7B3FA0", "buff",
    "&7Adds &a{0} &f✦ Speed &7and gives mob attacks a &a{1}% &7chance to miss you.",
    stats=["SPEED"], params=[None, "dodge"])
eff("night_vision", "Night Vision", "&9", "#1F1FA1", "buff", "&7Lets you see clearly in the dark.",
    vanilla="NIGHT_VISION", corrupt="invisibility")
eff("invisibility", "Invisibility", "&7", "#7F8392", "buff", "&7Hides you from sight.", vanilla="INVISIBILITY")
eff("fire_resistance", "Fire Resistance", "&6", "#E49A3A", "buff", "&7Takes &a{0}% &7off fire and lava damage.",
    params=["fire_reduce"])
eff("spelunker", "Spelunker", "&6", "#E1B04A", "buff", "&7Increases your &6☘ Mining Fortune &7by &a{0}&7.",
    stats=["MINING_FORTUNE"], brew_max=4)  # one level-I ingredient + glowstone reaches IV; V comes from the God Potion
eff("experience", "Experience", "&a", "#99FF33", "buff", "&7Experience orbs you collect are worth &a{0}% &7more.",
    params=["exp"])
eff("stun", "Stun", "&8", "#4A4A4A", "buff",
    "&7Your hits have a &a{0}% &7chance to freeze a mob for &a{1}s&7. Thrown, it freezes mobs for &a{2}s&7.",
    params=["stun_chance", "stun_secs", "stun_splash"])
eff("dodge", "Dodge", "&8", "#A8A8A8", "buff", "&7Mob attacks have a &a{0}% &7chance to miss you.", params=["dodge"])
eff("true_defense", "True Resistance", "&f", "#F2F2F2", "buff", "&7Increases your &f❂ True Defense &7by &a{0}&7.",
    stats=["TRUE_DEFENSE"])
eff("spirit", "Spirit", "&b", "#9AD7EC", "buff",
    "&7Adds &a{0} &f✦ Speed &7and &a{1}% &9☠ Crit Damage&7.", stats=["SPEED", "CRIT_DAMAGE"])
eff("pet_luck", "Pet Luck", "&d", "#E28CC8", "buff",
    "&7Increases your &d♣ Pet Luck &7by &a{0}&7, so pets drop and roll higher more often.", stats=["PET_LUCK"])
eff("knockback", "Knockback", "&2", "#4C7A2A", "buff", "&7Your hits push enemies back &a{0}% &7further.",
    params=["knockback"])
eff("burning", "Burning", "&6", "#FF7F27", "buff", "&7Fire you set on enemies burns &a{0}% &7longer.",
    params=["burning"])
eff("venomous", "Venomous", "&2", "#4E9331", "debuff",
    "&7Slows by &c{0} &f✦ Speed &7and deals &c{1} &7damage every second.", stats=["SPEED"],
    params=[None, "poison_dps"])
eff("wounded", "Wounded", "&4", "#5A1A1A", "debuff", "&7Cuts all healing received by &c{0}%&7.",
    params=["heal_reduce"])
eff("cold_resistance", "Cold Resistance", "&b", "#BDE7F2", "buff",
    "&7Increases your &b❄ Cold Resistance &7by &a{0}&7.", stats=["COLD_RESISTANCE"])
eff("magic_find", "Magic Find", "&b", "#55FFFF", "buff",
    "&7Adds &a{0} &b✯ Magic Find&7, for better odds at rare drops.", stats=["MAGIC_FIND"])
eff("stamina", "Stamina", "&a", "#7CE36B", "instant",
    "&7Instantly restores &c{0}❤ Health &7and &b{1}✎ Mana&7.", params=["heal_instant", "mana_instant"],
    ext=False)
eff("slowness", "Slowness", "&8", "#5A6C81", "debuff", "&7Drags your &f✦ Speed &7down by &c{0}&7.",
    stats=["SPEED"], negate=True)
eff("weakness", "Weakness", "&8", "#484D48", "debuff", "&7Your attacks deal &c{0} &7less damage.",
    params=["weakness"])
eff("poison", "Poison", "&2", "#4E9331", "debuff", "&7Deals &c{0} &7damage every second.", params=["poison_dps"])
eff("blindness", "Blindness", "&8", "#1F1F23", "debuff", "&7Clouds your sight.", vanilla="BLINDNESS")
for skill, stat in [("combat", "COMBAT"), ("mining", "MINING"), ("foraging", "FORAGING"), ("fishing", "FISHING"),
                    ("farming", "FARMING"), ("enchanting", "ENCHANTING"), ("alchemy", "ALCHEMY")]:
    eff(skill + "_xp_boost", skill.capitalize() + " XP Boost", "&6", "#E8B830", "xp",
        "&7Adds &3{0}☯ " + skill.capitalize() + " Wisdom&7, so " + skill.capitalize()
        + " &7XP comes faster.", stats=[stat + "_WISDOM"], ext=False)
eff("mushed_glowy_tonic", "Glowmash Tonic", "&3", "#3FD0B3", "special",
    "&7Adds &b{0} ☂ Fishing Speed&7.", stats=["FISHING_SPEED"], ext=False)
eff("stinky_cheese", "Pungent Cheese Mist", "&e", "#E2D06A", "special",
    "&7Adds &2{0} ൠ Bonus Pest Chance&7. Pests cannot resist the smell.", stats=["PEST_CHANCE"], ext=False)
eff("harvest_harbinger", "Harvest Herald", "&6", "#E2A03A", "special",
    "&7Adds &6{0} ☘ Farming Fortune&7.", stats=["FARMING_FORTUNE"], ext=False)
eff("wisp_ice", "Chilled Spirit Water", "&b", "#A0E8FF", "special",
    "&7Adds &c{0} ❣ Health Regen &7and &f{1} ❂ True Defense&7.", stats=["HEALTH_REGEN", "TRUE_DEFENSE"],
    ext=False, splash_only=True)   # NEU: "Wisp's Ice-Flavored Water I Splash Potion"
# Rift: "Gravity I Splash Potion (10:00)". The rift module reads it through Features.Potions.active ("rift_gravity").
eff("rift_gravity", "Gravity", "&d", "#B58CFF", "special",
    "&7Raises the gravity around you, so &dPufflings &7turn up more often in the Rift.", ext=False,
    splash_only=True, fixed={"puff_rate": 1})
# Dungeons: "Secret I", soulbound, no duration in the NEU lore. TUNE: 20 minutes, ticking as a dungeon potion
# (it keeps running inside dungeons); the dungeons module reads Features.Potions.active ("secrets").
eff("secrets", "Secret", "&5", "#5E2D79", "special",
    "&7One sip and the hidden &dsecrets &7of a dungeon show themselves to you.", ext=False, seconds=1200,
    fixed={"secret_sight": 1})

# ---------------------------------------------------------------------------------------------
# Ingredients: (item, effect, level, xp, unlock). Base is the Awkward Potion unless base="WATER".
# XP: research 02 section 2.4.7 (per potion). TUNE marks numbers the research does not give.
# Unlock: research/data/collections.json ("<X> Potion Recipe" tier rewards).
# ---------------------------------------------------------------------------------------------
ING = []


def ing(item, effect, level, xp, unlock=None, base="AWKWARD", tune=False):
    ING.append(dict(item=item, effect=effect, level=level, xp=xp, unlock=unlock, base=base, tune=tune))


ing("SUGAR", "speed", 1, 5); ing("ENCHANTED_SUGAR", "speed", 3, 300); ing("ENCHANTED_SUGAR_CANE", "speed", 5, 15000)
ing("BLAZE_POWDER", "strength", 1, 10); ing("ENCHANTED_BLAZE_POWDER", "strength", 3, 500)
ing("ENCHANTED_BLAZE_ROD", "strength", 5, 23000)
ing("SPECKLED_MELON", "healing", 1, 10); ing("ENCHANTED_MELON", "healing", 3, 250)
ing("ENCHANTED_GLISTERING_MELON", "healing", 5, 500)
ing("RABBIT_FOOT", "jump_boost", 1, 12); ing("ENCHANTED_RABBIT_FOOT", "jump_boost", 3, 600)
ing("CACTUS", "resistance", 1, 10, "collection:CACTUS:3"); ing("ENCHANTED_CACTUS_GREEN", "resistance", 3, 250, "collection:CACTUS:3")
ing("ENCHANTED_CACTUS", "resistance", 5, 500, "collection:CACTUS:3")
ing("RAW_FISH:3", "water_breathing", 1, 12); ing("ENCHANTED_PUFFERFISH", "water_breathing", 3, 600)
ing("GHAST_TEAR", "regeneration", 1, 30); ing("ENCHANTED_GHAST_TEAR", "regeneration", 5, 120, tune=True)  # V so VIII is reachable (research: max VIII)
ing("MUTTON", "mana", 1, 0, "collection:MUTTON:4"); ing("ENCHANTED_MUTTON", "mana", 3, 0, "collection:MUTTON:4")
ing("ENCHANTED_COOKED_MUTTON", "mana", 5, 0, "collection:MUTTON:4")
ing("FLINT", "critical", 1, 10, "collection:GRAVEL:7", tune=True)
ing("FEATHER", "archery", 1, 10, "collection:FEATHER:3")
ing("COAL", "haste", 1, 5, "collection:COAL:3")
ing("GOLD_INGOT", "absorption", 1, 5, "collection:GOLD_INGOT:6"); ing("ENCHANTED_GOLD", "absorption", 3, 300, "collection:GOLD_INGOT:6")
ing("ENCHANTED_GOLD_BLOCK", "absorption", 5, 15000, "collection:GOLD_INGOT:6")
ing("INK_SACK:3", "adrenaline", 1, 5, "collection:INK_SACK:3:7", tune=True)
ing("ENCHANTED_COCOA", "adrenaline", 3, 300, "collection:INK_SACK:3:7", tune=True)
ing("ENCHANTED_COOKIE", "adrenaline", 5, 15000, "collection:INK_SACK:3:7", tune=True)
ing("RABBIT", "rabbit", 1, 10, "collection:RABBIT:3", tune=True)
ing("ENCHANTED_RABBIT", "rabbit", 3, 300, "collection:RABBIT:3", tune=True)
ing("ENCHANTED_CAKE", "agility", 1, 400, "collection:RAW_CHICKEN:8")
ing("GOLDEN_CARROT", "night_vision", 1, 10)
ing("MAGMA_CREAM", "fire_resistance", 1, 10)
ing("MITHRIL_ORE", "spelunker", 1, 10, "collection:MITHRIL_ORE:3", tune=True)
ing("INK_SACK:4", "experience", 1, 5, "collection:INK_SACK:4:6")
ing("OBSIDIAN", "stun", 1, 15, "collection:OBSIDIAN:6")
ing("RAW_FISH:1", "dodge", 1, 5, "collection:RAW_FISH:1:2")
ing("TRUE_ESSENCE", "true_defense", 1, 10, tune=True)
ing("FOUL_FLESH", "spirit", 1, 10, tune=True)
ing("ENCHANTED_RABBIT_HIDE", "pet_luck", 1, 10, "collection:RABBIT:8", tune=True)
ing("SLIME_BALL", "knockback", 1, 10, "collection:SLIME_BALL:4")
ing("SAND:1", "burning", 1, 5, "collection:SAND:6")
ing("POISONOUS_POTATO", "venomous", 1, 20, "collection:POTATO_ITEM:5")
ing("NETHERRACK", "wounded", 1, 5, "collection:NETHERRACK:2")
ing("ENCHANTED_GLACITE", "cold_resistance", 1, 10, "collection:GLACITE:2", tune=True)
ing("SPIDER_EYE", "poison", 1, 10); ing("ENCHANTED_SPIDER_EYE", "poison", 3, 0, tune=True)
ing("INK_SACK", "blindness", 1, 0, "collection:INK_SACK:6"); ing("ENCHANTED_INK_SACK", "blindness", 3, 0, "collection:INK_SACK:6", tune=True)
ing("FERMENTED_SPIDER_EYE", "weakness", 1, 10, base="WATER")
ing("ENCHANTED_FERMENTED_SPIDER_EYE", "weakness", 3, 15000, base="WATER")
# XP boost potions: research 08 lists these ingredients with (?) - TUNE
for sk, item in [("combat", "ENCHANTED_ROTTEN_FLESH"), ("mining", "ENCHANTED_COBBLESTONE"),
                 ("foraging", "ENCHANTED_OAK_LOG"), ("fishing", "ENCHANTED_RAW_FISH"), ("farming", "ENCHANTED_WHEAT"),
                 ("enchanting", "ENCHANTED_LAPIS_LAZULI"), ("alchemy", "ENCHANTED_NETHER_STALK")]:
    ing(item, sk + "_xp_boost", 1, 300, tune=True)

MODS = [
    dict(item="NETHER_STALK", type="AWKWARD", amount=0, xp=1),
    dict(item="GLOWSTONE_DUST", type="LEVEL", amount=1, xp=5),
    dict(item="ENCHANTED_GLOWSTONE_DUST", type="LEVEL", amount=2, xp=5),
    dict(item="ENCHANTED_GLOWSTONE", type="LEVEL", amount=3, xp=5),
    dict(item="REDSTONE", type="DURATION", amount=480, xp=5),
    dict(item="ENCHANTED_REDSTONE", type="DURATION", amount=960, xp=5),
    dict(item="ENCHANTED_REDSTONE_BLOCK", type="DURATION", amount=2400, xp=5),
    # research 08 lists the lamp as "+16 minutes and +3 levels (?)": both modifiers in one item
    dict(item="ENCHANTED_REDSTONE_LAMP", type="BOTH", amount=960, levels=3, xp=5),
    dict(item="SULPHUR", type="SPLASH", amount=1, xp=5),
    dict(item="ENCHANTED_GUNPOWDER", type="SPLASH", amount=2, xp=5),
    dict(item="FERMENTED_SPIDER_EYE", type="CORRUPT", amount=0, xp=10),
]

# God Potion effects (research 08 section 2.4; level capped at the highest level that exists).
GOD = {"speed": 8, "strength": 8, "resistance": 8, "regeneration": 9, "mana": 8, "critical": 4, "archery": 4,
       "haste": 4, "jump_boost": 4, "adrenaline": 8, "water_breathing": 6, "night_vision": 2,
       "fire_resistance": 1, "rabbit": 6, "agility": 4, "spelunker": 5, "experience": 4, "dodge": 4,
       "true_defense": 4, "spirit": 4, "pet_luck": 4, "stun": 4, "burning": 4}


# Basic Brews (NEU item files; sold by the hub Bartender and the Crimson Isle taverns). A Basic Brew goes in
# a bottle slot INSTEAD of an Awkward Potion; it only brews the potions it can boost, and the potion it
# makes carries the bonus (stored on the item as extra.brew, then on the active effect).
#   flat     stat/param -> points added      pct   stat/param -> percent added
#   effects  if set, the only effects it brews (else: every effect whose numbers it boosts)
#   check    numbers that must appear in the NEU lore (keeps the table honest)
BREWS = [
    dict(item="CHEAP_COFFEE", name="Cheap Coffee", flat={"SPEED": 5}, check=[5],
         desc="Adds &f+5 Speed &7to potions with that stat."),
    dict(item="DECENT_COFFEE", name="Decent Coffee", flat={"SPEED": 8}, check=[8],
         desc="Adds &f+8 Speed &7to potions with that stat."),
    dict(item="BLACK_COFFEE", name="Black Coffee", flat={"SPEED": 12}, check=[12],
         desc="Adds &f+12 Speed &7to potions with that stat."),
    dict(item="TEPID_GREEN_TEA", name="Tepid Green Tea", pct={"DEFENSE": 10}, check=[10],
         desc="Raises the &aDefense &7of potions by &a+10%&7."),
    dict(item="PULPOUS_ORANGE_JUICE", name="Pulpous Orange Juice", pct={"heal_per_sec": 5, "heal_instant": 5},
         check=[5], desc="Raises the &cHealth &7restored by potions by &a+5%&7."),
    dict(item="BITTER_ICE_TEA", name="Bitter Iced Tea", pct={"mana_per_sec": 20, "mana_instant": 20, "exp": 20},
         check=[20], desc="Raises the &bMana &7and &3Experience &7of potions by &a+20%&7."),
    dict(item="KNOCKOFF_COLA", name="Knockoff Cola", pct={"STRENGTH": 5}, check=[5],
         desc="Raises the &cStrength &7of potions by &a+5%&7."),
    dict(item="SCARLETON_PREMIUM", name="Carmine Premium", pct={"heal_reduce": 10}, check=[10],
         desc="Makes &4Wounded &7potions &a10% &7stronger."),
    dict(item="RED_THORNLEAF_TEA", name="Red Thornleaf Tea", flat={"reflect": 5}, effects=["resistance"], check=[5],
         desc="Brews &aResistance &7potions only. While it lasts, &a5% &7of the damage a monster deals you is thrown back at it."),
]


def load(name):
    p = os.path.join(NEU, name + ".json")
    if not os.path.exists(p):
        return None
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def duration(line):
    m = re.search(r"\((\d+):(\d+)(?::(\d+))?\)", line)
    if not m:
        return 0
    a, b, c = m.group(1), m.group(2), m.group(3)
    return int(a) * 3600 + int(b) * 60 + int(c) if c else int(a) * 60 + int(b)


def parse_levels(key):
    out = []
    for L in range(1, 11):
        d = load("POTION_%s;%d" % (key, L))
        if d is None:
            continue
        lore = [strip(x) for x in d.get("lore", [])]
        lines = [x for x in lore if x.strip()]
        head = lines[0] if lines else ""
        rarity = lines[-1].strip().split(" ")[0] if lines else "COMMON"
        body = " ".join(lines[1:-1])
        out.append(dict(level=L, head=head, rarity=rarity, seconds=duration(head), body=body))
    return out


def build_effects():
    effects = []
    for eid, s in E.items():
        key = eid.upper()
        levels = parse_levels(key)
        if not levels:
            raise SystemExit("no data for " + key)
        lv_out = []
        for lv in levels:
            n = nums(lv["body"])
            if eid == "rabbit":
                jm = re.search(r"Jump Boost ([IVX]+)", lv["body"])
                jump = ROMAN_IDX[jm.group(1)] if jm else 1
                n = nums(lv["body"].replace("Jump Boost " + (jm.group(1) if jm else ""), ""))
            stats, params = {}, {}
            for i, st in enumerate(s["stats"]):
                if st and i < len(n):
                    v = -abs(n[i]) if s["negate"] else n[i]
                    stats[st] = v
            for i, pa in enumerate(s["params"]):
                if pa and i < len(n):
                    params[pa] = abs(n[i])
            if eid == "rabbit":
                params["amp"] = jump - 1
            if eid == "jump_boost":
                params["amp"] = lv["level"] - 1
            desc = s["desc"]
            for i, v in enumerate(n):
                desc = desc.replace("{%d}" % i, fmt(abs(v)))
            if eid == "rabbit":
                desc = desc.replace("{J}", ROMAN[jump])
            if "{" in desc:
                raise SystemExit("unfilled description for %s %d: %s / %s" % (eid, lv["level"], desc, lv["body"]))
            params.update(s["fixed"])
            secs = lv["seconds"] or (s["seconds"] or 0)
            lv_out.append(dict(level=lv["level"], rarity=lv["rarity"], seconds=secs, desc=desc,
                               stats=stats, params=params))
        brewable = any(i["effect"] == eid for i in ING) or any(
            E[o]["corrupt"] == eid for o in E if E[o]["corrupt"])
        mx = max(l["level"] for l in lv_out)
        e = dict(id=eid, name=s["name"], line=s["line"], color=s["color"], kind=s["kind"],
                 extendable=s["ext"], corruptsTo=s["corrupt"], vanilla=s["vanilla"],
                 brewable=brewable, brewMax=min(mx, s["brew_max"] or mx), levels=lv_out)
        if s["splash_only"]:
            e["splashOnly"] = True
        effects.append(e)
    effects.extend(special_effects())
    return effects


# ---------------------------------------------------------------------------------------------
# Special potions that are not POTION_<KEY>;<L> files (the item carries the effect in extra.effect).
# Every number is asserted against the NEU lore so the table cannot drift from the reference.
# ---------------------------------------------------------------------------------------------
def special_effects():
    out = []
    d = load("GREAT_SPOOK_POTION")
    lore = " ".join(strip(x) for x in d["lore"])
    assert "+3 Fear for 24 hours" in lore, lore
    # Fear is the Dread festival's own number (mayors); no Stat exists, so it is a param the festival reads.
    out.append(dict(id="great_spook", potionItem="GREAT_SPOOK_POTION", name="Dread", line="&6", color="#FF8C1A", kind="special", extendable=False,
                    corruptsTo=None, vanilla=None, brewable=False, brewMax=1,
                    levels=[dict(level=1, rarity="COMMON", seconds=24 * 3600,
                                 desc="&7Adds &6+3 Fear&7: the Dread festival's horrors find you sooner.",
                                 stats={}, params={"fear": 3})]))
    # Midnight Wit (reference "Bottle of Jyrre"): the level IS the Intelligence bonus (scaled: stats x level).
    for f in ("NEW_BOTTLE_OF_JYRRE", "REFINED_BOTTLE_OF_JYRRE", "BOTTLE_OF_JYRRE"):
        assert load(f), f
    ref = " ".join(strip(x) for x in load("REFINED_BOTTLE_OF_JYRRE")["lore"])
    assert "+300" in ref and "60m" in ref and "5 of these" in ref, ref
    out.append(dict(id="jyrre", potionItem="NEW_BOTTLE_OF_JYRRE", name="Midnight Wit", line="&b", color="#5BC8F5", kind="special", extendable=False,
                    corruptsTo=None, vanilla=None, brewable=False, brewMax=1, scaled=True,
                    levels=[dict(level=1, rarity="COMMON", seconds=3600,
                                 desc="&7Adds &b✎ Intelligence &7for as long as it lasts.",
                                 stats={"INTELLIGENCE": 1}, params={})]))
    return out


# The Midnight Wit bottles (reference Bottle of Jyrre). minutes = effect length; cap = highest bonus;
# evolves = what a bottle that reached its cap turns into. Growth speed is a dial (alchemy.jyrre-minutes-per-int).
JYRRE = [
    dict(id="BOTTLE_OF_JYRRE", name="Lost Bottle of Midnight", kind="lost", minutes=10, cap=300, evolves=None,
         check=["10 Minutes"],
         lore=["&7Keep it in your inventory and it", "&7grows sharper: the longer you carry",
               "&7it, the more &b✎ Intelligence &7it", "&7gives when you finally drink it."]),
    dict(id="NEW_BOTTLE_OF_JYRRE", name="Bottle of Midnight", kind="new", minutes=60, cap=300,
         evolves="REFINED_BOTTLE_OF_JYRRE", check=["60m", "+300"],
         lore=["&7Drink it for a burst of &b✎ Intelligence&7.", "&7Carry it around and the bonus grows, up",
               "&7to &b+300&7. Then the bottle evolves!"]),
    dict(id="REFINED_BOTTLE_OF_JYRRE", name="Refined Bottle of Midnight", kind="refined", minutes=60, cap=300,
         evolves=None, check=["+300", "60m", "+1", "5 of these"],
         lore=["&7Drink it for &b+300✎ Intelligence &7for", "&a60m&7, and keep &b+1✎ Intelligence",
               "&7for good.", "", "&8Aged to perfection. Only your first",
               "&85 refined bottles leave a permanent mark."]),
]


def dungeon_bundles(effects):
    by_name = {e["name"]: e["id"] for e in effects}
    by_name["True Resistance"] = "true_defense"
    out = []
    for L in range(1, 8):
        d = load("POTION_DUNGEON;%d" % L)
        lore = [strip(x) for x in d["lore"] if strip(x).strip()]
        rarity = lore[-1].split(" ")[0]
        effs = {}
        secs = 0
        for line in lore:
            m = re.match(r"^(.+?) ([IVX]+) \((\d+:\d+)\)$", line.strip())
            if m and m.group(1) in by_name:
                effs[by_name[m.group(1)]] = ROMAN_IDX[m.group(2)]
                secs = duration(line)
        out.append(dict(level=L, rarity=rarity, seconds=secs, effects=effs))
    return out


def roman(n):
    return ROMAN[n]


def potion_name(e, L, splash=False):
    base = e["name"]
    tail = "" if base.endswith(("Tonic", "Water", "Mist")) else " Potion"
    if splash:
        tail = " Splash" + (tail if tail else "")
    return "%s %s%s" % (base, roman(L), tail)


def build_items(effects, bundles):
    items = []
    # water bottle + awkward potion (stackable: no instance data)
    items.append(dict(id="POTION", name="Water Bottle", material="POTION", rarity="COMMON", category="POTION",
                      color="#385DC6", maxStack=64, npcSell=2.0,
                      lore=["&7Plain water in a bottle. Add &cNether Wart", "&7at a brewing stand to begin a potion."],
                      tags=["alchemy", "alchemy_base"], extra={"alchemy": "WATER"}))
    items.append(dict(id="POTION_AWKWARD", name="Awkward Potion", material="POTION", rarity="COMMON",
                      category="POTION", color="#385DC6", maxStack=64, npcSell=3.0,
                      lore=["&7A cloudy base for real brewing. Add a", "&7&8Brewing Ingredient &7at a brewing stand",
                            "&7to give it an effect."],
                      tags=["alchemy", "alchemy_base"], extra={"alchemy": "AWKWARD"}))
    for e in effects:
        if e["id"] in NO_POTION_ITEM:
            continue
        for lv in e["levels"]:
            L = lv["level"]
            base = dict(material="POTION", rarity=lv["rarity"], category="POTION", color=e["color"], maxStack=1,
                        lore=[], tags=["alchemy", "potion"], extra={"effect": e["id"], "level": L})
            if e.get("splashOnly"):
                # sold only as a splash potion: the plain id IS the thrown one
                items.append(dict(base, id="POTION_%s;%d" % (e["id"].upper(), L),
                                  name="%s %s Splash Potion" % (e["name"], roman(L)), material="SPLASH_POTION",
                                  tags=["alchemy", "potion", "splash", "throwable"],
                                  extra={"effect": e["id"], "level": L, "splash": True}))
                continue
            if e["id"] == "secrets":
                base = dict(base, tags=["alchemy", "potion", "soulbound"], extra={"effect": e["id"], "level": L,
                                                                                 "source": "dungeon"})
            items.append(dict(base, id="POTION_%s;%d" % (e["id"].upper(), L), name=potion_name(e, L)))
            if e["brewable"] and L <= e["brewMax"]:
                sp = dict(base, id="SPLASH_POTION_%s;%d" % (e["id"].upper(), L), name=potion_name(e, L, True),
                          material="SPLASH_POTION", tags=["alchemy", "potion", "splash", "throwable"],
                          extra={"effect": e["id"], "level": L, "splash": True})
                items.append(sp)
    for b in bundles:
        items.append(dict(id="POTION_DUNGEON;%d" % b["level"], name="Dungeon %s Potion" % roman(b["level"]),
                          material="POTION", rarity=b["rarity"], category="POTION", color="#8B0000", maxStack=1,
                          lore=[], tags=["alchemy", "potion", "bundle"],
                          extra={"bundle": "dungeon", "level": b["level"]}))
    god_tex = None
    d = load("GOD_POTION_2")
    m = re.search(r'Value:\\?"([A-Za-z0-9+/=]+)\\?"', d["nbttag"]) if d else None
    if m:
        god_tex = m.group(1)
    for gid, hours, rar in [("GOD_POTION_2", 12, "SPECIAL"), ("GOD_POTION", 24, "SPECIAL")]:
        items.append(dict(id=gid, name="God Potion", material="PLAYER_HEAD", rarity=rar, category="POTION",
                          skull=god_tex, maxStack=1,
                          lore=["&7Drink it to gain a whole cabinet of", "&7helpful &dpotion effects &7at once.", "",
                                "&7Several God Potions add their time", "&7together instead of overwriting."]
                          if gid == "GOD_POTION_2" else
                          ["&8Legacy", "", "&7An old recipe: one drink grants", "&7every helpful effect for a full day."],
                          tags=["alchemy", "potion", "god_potion"], extra={"god": True, "legacy": gid == "GOD_POTION"}))
    # Basic Brews: heads, stackable, rarity and texture from NEU
    for b in BREWS:
        d = load(b["item"])
        assert d, b["item"]
        lore = " ".join(strip(x) for x in d["lore"])
        for n in b["check"]:
            assert fmt(n) in re.findall(r"\d+", lore), (b["item"], n, lore)
        items.append(dict(id=b["item"], name=b["name"], material="PLAYER_HEAD", rarity=neu_rarity(d),
                          category="POTION", skull=skull_of(d), maxStack=64, npcSell=npc_sell(b["item"]),
                          lore=["&8Basic Brew", "", "&7Put it in a brewing stand's bottle", "&7slot instead of an "
                                "&fAwkward Potion&7.", ""] + wrap("&7" + b["desc"], 36),
                          tags=["alchemy", "alchemy_base", "basic_brew"], extra={"alchemy": "BREW"}))
    # Tavern drinks that are not brews: Hot Chocolate (a mixin ingredient) and Bitterclaw Brew (a drink)
    d = load("HOT_CHOCOLATE")
    items.append(dict(id="HOT_CHOCOLATE", name="Hot Chocolate", material="PLAYER_HEAD", rarity=neu_rarity(d),
                      category="MATERIAL", skull=skull_of(d), maxStack=64, npcSell=npc_sell("HOT_CHOCOLATE"),
                      lore=["&7Rich, steaming and thick enough", "&7to stand a spoon in. Brewers say it",
                            "&7keeps the volcano's heat at bay."],
                      tags=["drink"]))
    d = load("SCORNCLAW_BREW")
    items.append(dict(id="SCORNCLAW_BREW", name="Bitterclaw Brew", material="PLAYER_HEAD", rarity=neu_rarity(d),
                      category="CONSUMABLE", skull=skull_of(d), maxStack=64, npcSell=npc_sell("SCORNCLAW_BREW"),
                      lore=["&7Sweet, then bitter, then it bites", "&7back. A barbarian favourite.", "",
                            "&eRight-click to consume!"],
                      tags=["alchemy", "drink", "tavern_drink"], extra={"alchemy": "DRINK"}))
    # Dread Draught (reference Great Spook Potion): a skull, right-click to consume, soulbound
    d = load("GREAT_SPOOK_POTION")
    items.append(dict(id="GREAT_SPOOK_POTION", name="Dread Draught", material="PLAYER_HEAD", rarity=neu_rarity(d),
                      category="POTION", skull=skull_of(d), maxStack=1,
                      lore=["&7Drinking it grants &6+3 Fear &7for", "&a24 hours&7. Brave souls only."],
                      tags=["alchemy", "potion", "skull_potion", "soulbound"],
                      extra={"effect": "great_spook", "level": 1}))
    # Midnight Wit bottles (reference Bottle of Jyrre): drinkable POTION items whose bonus grows while carried
    for j in JYRRE:
        d = load(j["id"])
        lore = " ".join(strip(x) for x in d["lore"])
        for c in j["check"]:
            assert c in lore, (j["id"], c, lore)
        items.append(dict(id=j["id"], name=j["name"], material="POTION", rarity=neu_rarity(d), category="POTION",
                          color="#5BC8F5", maxStack=1, npcSell=npc_sell(j["id"]), lore=j["lore"],
                          tags=["alchemy", "jyrre"],
                          extra={"jyrre": j["kind"], "minutes": j["minutes"], "cap": j["cap"],
                                 "evolves": j["evolves"]}))
    # Recall Potion: save a spot in the Crystal Caverns, drink to return to it (reference Ancient Recall)
    d = load("RECALL_POTION")
    lore = " ".join(strip(x) for x in d["lore"])
    assert "save" in lore and "warp back" in lore, lore
    items.append(dict(id="RECALL_POTION", name="Recall Potion", material="PLAYER_HEAD", rarity=neu_rarity(d),
                      category="POTION", skull=skull_of(d), maxStack=1, npcSell=npc_sell("RECALL_POTION"),
                      lore=["&7A glowing draught that remembers", "&7where you stood.", "",
                            "&6Ability: Echo Recall &e&lRIGHT CLICK", "&7In the &5Crystal Caverns&7: the first use",
                            "&7saves your spot, the next one drinks", "&7the potion and takes you back there."],
                      tags=["alchemy", "recall_potion"], extra={"alchemy": "RECALL"}))
    return items


# effects whose potion is a special item above, not a POTION_<KEY>;<L> id
NO_POTION_ITEM = {"great_spook", "jyrre"}


def skull_of(d):
    m = re.search(r'Value:\\?"([A-Za-z0-9+/=]+)\\?"', d.get("nbttag", "")) if d else None
    return m.group(1) if m else None


def neu_rarity(d):
    for line in reversed(d["lore"]):
        s = strip(line).strip()
        for r in ("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "SPECIAL"):
            if s == r or s.startswith(r + " "):
                return r
    return "COMMON"


_API_ITEMS = None


def npc_sell(item_id):
    """NPC sell price from the Hypixel items API dump (0 when it has none)."""
    global _API_ITEMS
    if _API_ITEMS is None:
        p = os.path.join(ROOT, "research", "data", "items.json")
        with open(p, encoding="utf-8") as f:
            raw = json.load(f)
        lst = raw["items"] if isinstance(raw, dict) else raw
        _API_ITEMS = {x["id"]: x for x in lst}
    x = _API_ITEMS.get(item_id) or {}
    return float(x.get("npc_sell_price") or 0)


def wrap(text, width):
    """Wrap a &-coloured line, carrying the last colour onto the next line."""
    out, cur, col = [], "", "&7"
    for word in text.split(" "):
        vis = len(re.sub("&.", "", cur)) + len(re.sub("&.", "", word)) + (1 if cur else 0)
        if cur and vis > width:
            out.append(cur)
            cur = col + word
        else:
            cur = (cur + " " + word) if cur else word
        cs = re.findall("&[0-9a-fk-or]", cur)
        if cs:
            col = cs[-1]
    if cur:
        out.append(cur)
    return out


def build_recipes(effects):
    eff_by_id = {e["id"]: e for e in effects}
    rec = [dict(id="alchemy:awkward", type="OTHER", result="POTION_AWKWARD", amount=1,
                inputs=["POTION:1", "NETHER_STALK:1"], station="Brewing Stand")]
    for i in ING:
        e = eff_by_id[i["effect"]]
        L = min(i["level"], e["brewMax"])
        base = "POTION:1" if i["base"] == "WATER" else "POTION_AWKWARD:1"
        r = dict(id="alchemy:%s:%s" % (i["effect"], i["item"].lower()), type="OTHER",
                 result="POTION_%s;%d" % (i["effect"].upper(), L), amount=1, inputs=[base, i["item"] + ":1"],
                 station="Brewing Stand")
        if i["unlock"]:
            r["unlock"] = i["unlock"]
        rec.append(r)
    # the same potions brewed from a Basic Brew instead of an Awkward Potion (EffectTable.BasicBrew.appliesTo)
    for b in BREWS:
        for i in ING:
            e = eff_by_id[i["effect"]]
            if i["base"] != "AWKWARD" or not brew_applies(b, e):
                continue
            L = min(i["level"], e["brewMax"])
            r = dict(id="alchemy:%s:%s:%s" % (i["effect"], i["item"].lower(), b["item"].lower()), type="OTHER",
                     result="POTION_%s;%d" % (i["effect"].upper(), L), amount=1,
                     inputs=[b["item"] + ":1", i["item"] + ":1"], station="Brewing Stand")
            if i["unlock"]:
                r["unlock"] = i["unlock"]
            rec.append(r)
    return rec


def brew_applies(b, e):
    """Mirror of EffectTable.BasicBrew.appliesTo (the Java side is what brews; this only lists recipes)."""
    if not e["levels"]:
        return False
    if b.get("effects"):
        return e["id"] in b["effects"]
    keys = list(b.get("flat", {})) + list(b.get("pct", {}))
    for lv in e["levels"]:
        for k in keys:
            if lv["stats"].get(k, 0) > 0 or lv["params"].get(k, 0) > 0:
                return True
    return False


def build_shop():
    d = load("ALCHEMIST_NPC")
    out = []
    for r in d.get("recipes", []):
        if r.get("type") != "npc_shop":
            continue
        res = r["result"]
        amt = 1
        if ":" in res and res.rsplit(":", 1)[1].isdigit() and res != "INK_SACK:3":
            res, amt = res.rsplit(":", 1)[0], int(res.rsplit(":", 1)[1])
        cost = 0
        for c in r["cost"]:
            if c.startswith("SKYBLOCK_COIN:"):
                cost = float(c.split(":")[1])
        out.append(dict(item=res, amount=amt, price=cost))
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    effects = build_effects()
    bundles = dungeon_bundles(effects)
    effects_ids = {e["id"] for e in effects}
    for i in ING:
        assert i["effect"] in effects_ids, i
    for g in GOD:
        assert g in effects_ids, g
    brews = []
    for b in BREWS:
        for k in b.get("effects") or []:
            assert k in effects_ids, (b["item"], k)
        brews.append(dict(item=b["item"], name=b["name"], flat=b.get("flat", {}), pct=b.get("pct", {}),
                          effects=b.get("effects"), desc=b["desc"]))
    table = dict(effects=effects, ingredients=ING, modifiers=MODS, god=GOD, bundles={"dungeon": bundles},
                 brews=brews)
    items = build_items(effects, bundles)
    recipes = build_recipes(effects)
    shop = build_shop()

    def dump(name, obj):
        with open(os.path.join(OUT, name), "w", encoding="utf-8", newline="\n") as f:
            json.dump(obj, f, ensure_ascii=False, indent=1)
            f.write("\n")

    dump("effects.json", table)
    dump("items.json", items)
    dump("recipes.json", recipes)
    dump("shop.json", shop)
    print("effects=%d levels=%d ingredients=%d brews=%d items=%d recipes=%d shop=%d" % (
        len(effects), sum(len(e["levels"]) for e in effects), len(ING), len(brews), len(items), len(recipes),
        len(shop)))


if __name__ == "__main__":
    main()
