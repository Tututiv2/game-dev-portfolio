"""Generate the accessories module's data from research/data.

Outputs (all under plugin/src/main/resources/data/accessories/):
  items.json       every accessory (talisman/ring/artifact/relic), power stone and enrichment
  equipment.json   every necklace / cloak / belt / gloves (bracelets count as gloves)
  recipes.json     crafting + forge recipes whose RESULT is one of the above
  powers.json      the 32 Accessory Powers (10 starter, 22 learned from power stones)
and docs/names/accessories.md (the rename table).

Run:  py -3 tools/gen/accessories/gen_accessories.py

Sources: research/data/items.json (ids, rarity, stats, skins, requirements), neu-repo/items/*.json
(lore stat lines, recipes, crafttext unlocks, power-stone numbers), neu-repo/constants/misc.json
(talisman_upgrades = families, ignored_talisman = no Accessory Power), collections.json (names ->
collection keys), research/01 (starter power tables).  Effects are hand-authored in SPEC below and
every description is OUR wording (rule 1 of the contract); numbers are the reference's.
"""
import json
import math
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
D = os.path.join(ROOT, "research", "data")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "accessories")
NAMES_MD = os.path.join(ROOT, "docs", "names", "accessories.md")

GLYPH = re.compile("[-]")


def strip(s):
    return GLYPH.sub("", re.sub("§.", "", s or "")).strip()


def load(p):
    with open(os.path.join(D, p), encoding="utf-8") as f:
        return json.load(f)


ITEMS = {i["id"]: i for i in load("items.json")["items"]}
MISC = load(os.path.join("neu-repo", "constants", "misc.json"))
UPGRADES = MISC["talisman_upgrades"]
IGNORED = set(MISC["ignored_talisman"])
COLL = load("collections.json")["collections"]
COLL_BY_NAME = {v["name"].lower(): k for cat in COLL.values() for k, v in cat["items"].items()}


def neu(iid):
    p = os.path.join(D, "neu-repo", "items", iid + ".json")
    if not os.path.exists(p):
        return {}
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def colon(neu_id):
    m = re.match(r"^(.*)-(\d+)$", neu_id)
    return m.group(1) + ":" + m.group(2) if m else neu_id


# ---------------------------------------------------------------------------------------------
# stats
# ---------------------------------------------------------------------------------------------
STAT_KEYS = {
    "HEALTH", "DEFENSE", "TRUE_DEFENSE", "STRENGTH", "INTELLIGENCE", "CRIT_CHANCE", "CRIT_DAMAGE",
    "ATTACK_SPEED", "ABILITY_DAMAGE", "FEROCITY", "HEALTH_REGEN", "VITALITY", "MENDING", "SWING_RANGE",
    "SPEED", "MAGIC_FIND", "PET_LUCK", "MINING_SPEED", "MINING_FORTUNE", "ORE_FORTUNE", "BLOCK_FORTUNE",
    "GEMSTONE_FORTUNE", "DWARVEN_METAL_FORTUNE", "MINING_SPREAD", "BREAKING_POWER", "PRISTINE",
    "COLD_RESISTANCE", "HEAT_RESISTANCE", "FARMING_FORTUNE", "FORAGING_FORTUNE", "SWEEP",
    "SEA_CREATURE_CHANCE", "FISHING_SPEED", "TROPHY_FISH_CHANCE", "DOUBLE_HOOK_CHANCE", "TREASURE_CHANCE",
    "PEST_CHANCE", "COMBAT_WISDOM", "MINING_WISDOM", "FARMING_WISDOM", "FORAGING_WISDOM", "FISHING_WISDOM",
    "ENCHANTING_WISDOM", "ALCHEMY_WISDOM", "CARPENTRY_WISDOM", "RUNECRAFTING_WISDOM", "SOCIAL_WISDOM",
    "TAMING_WISDOM", "MANA_REGEN", "HUNTING_FORTUNE", "HUNTING_WISDOM",
}
ALIAS = {
    "HEALTH_REGENERATION": "HEALTH_REGEN", "CRITICAL_DAMAGE": "CRIT_DAMAGE", "CRITICAL_CHANCE": "CRIT_CHANCE",
    "WALK_SPEED": "SPEED", "BONUS_PEST_CHANCE": "PEST_CHANCE", "TROPHY_CHANCE": "TROPHY_FISH_CHANCE",
    "BONUS_ATTACK_SPEED": "ATTACK_SPEED", "HUNTER_FORTUNE": "HUNTING_FORTUNE",
}
LORE_NAME = {
    "Health": "HEALTH", "Defense": "DEFENSE", "True Defense": "TRUE_DEFENSE", "Strength": "STRENGTH",
    "Intelligence": "INTELLIGENCE", "Crit Chance": "CRIT_CHANCE", "Crit Damage": "CRIT_DAMAGE",
    "Attack Speed": "ATTACK_SPEED", "Bonus Attack Speed": "ATTACK_SPEED", "Ability Damage": "ABILITY_DAMAGE",
    "Ferocity": "FEROCITY", "Health Regen": "HEALTH_REGEN", "Vitality": "VITALITY", "Mending": "MENDING",
    "Speed": "SPEED", "Magic Find": "MAGIC_FIND", "Pet Luck": "PET_LUCK", "Mining Speed": "MINING_SPEED",
    "Mining Fortune": "MINING_FORTUNE", "Gemstone Fortune": "GEMSTONE_FORTUNE",
    "Dwarven Metal Fortune": "DWARVEN_METAL_FORTUNE", "Cold Resistance": "COLD_RESISTANCE",
    "Heat Resistance": "HEAT_RESISTANCE", "Farming Fortune": "FARMING_FORTUNE",
    "Foraging Fortune": "FORAGING_FORTUNE", "Sweep": "SWEEP", "Sea Creature Chance": "SEA_CREATURE_CHANCE",
    "Fishing Speed": "FISHING_SPEED", "Trophy Chance": "TROPHY_FISH_CHANCE", "Treasure Chance": "TREASURE_CHANCE",
    "Bonus Pest Chance": "PEST_CHANCE", "Combat Wisdom": "COMBAT_WISDOM", "Mining Wisdom": "MINING_WISDOM",
    "Farming Wisdom": "FARMING_WISDOM", "Foraging Wisdom": "FORAGING_WISDOM", "Fishing Wisdom": "FISHING_WISDOM",
    "Enchanting Wisdom": "ENCHANTING_WISDOM", "Alchemy Wisdom": "ALCHEMY_WISDOM", "Mana Regen": "MANA_REGEN",
    "Breaking Power": "BREAKING_POWER", "Pristine": "PRISTINE",
    # wave 5: core added the Hunting stats; the reference's older "Hunter Fortune" is the same stat
    "Hunter Fortune": "HUNTING_FORTUNE", "Hunting Fortune": "HUNTING_FORTUNE", "Hunting Wisdom": "HUNTING_WISDOM",
}
LABEL = {
    "HEALTH": "&c❤ Health", "DEFENSE": "&a❈ Defense", "TRUE_DEFENSE": "&f❂ True Defense",
    "STRENGTH": "&c❁ Strength", "INTELLIGENCE": "&b✎ Intelligence", "CRIT_CHANCE": "&9☣ Crit Chance",
    "CRIT_DAMAGE": "&9☠ Crit Damage", "ATTACK_SPEED": "&e⚔ Bonus Attack Speed",
    "ABILITY_DAMAGE": "&c๑ Ability Damage", "FEROCITY": "&c⫽ Ferocity",
    "HEALTH_REGEN": "&c❣ Health Regen", "VITALITY": "&4♨ Vitality", "MENDING": "&a☄ Mending",
    "SPEED": "&f✦ Speed", "MAGIC_FIND": "&b✯ Magic Find", "PET_LUCK": "&d♣ Pet Luck",
    "MINING_SPEED": "&6⸕ Mining Speed", "MINING_FORTUNE": "&6☘ Mining Fortune",
    "FARMING_FORTUNE": "&6☘ Farming Fortune", "FORAGING_FORTUNE": "&6☘ Foraging Fortune",
    "FISHING_SPEED": "&b☂ Fishing Speed", "SEA_CREATURE_CHANCE": "&3α Sea Creature Chance",
    "TREASURE_CHANCE": "&6⛃ Treasure Chance", "COMBAT_WISDOM": "&3☯ Combat Wisdom",
    "MINING_WISDOM": "&3☯ Mining Wisdom", "FARMING_WISDOM": "&3☯ Farming Wisdom",
    "FORAGING_WISDOM": "&3☯ Foraging Wisdom", "FISHING_WISDOM": "&3☯ Fishing Wisdom",
    "PRISTINE": "&5✧ Pristine", "TROPHY_FISH_CHANCE": "&6♔ Trophy Fish Chance",
    "MINING_SPREAD": "&e▚ Mining Spread",
    "HUNTING_FORTUNE": "&d☘ Hunting Fortune", "HUNTING_WISDOM": "&3☯ Hunting Wisdom",
}
PERCENT = {"CRIT_CHANCE", "CRIT_DAMAGE", "ATTACK_SPEED", "ABILITY_DAMAGE", "SEA_CREATURE_CHANCE",
           "TROPHY_FISH_CHANCE", "TREASURE_CHANCE", "PEST_CHANCE", "MANA_REGEN"}


def num(v):
    if abs(v - round(v)) < 1e-9:
        return str(int(round(v)))
    return ("%.3f" % v).rstrip("0").rstrip(".")


def statline(stat, v):
    lab = LABEL.get(stat, "&a" + stat.replace("_", " ").title())
    col = lab[:2]
    return "%s%s%s %s" % (col, "+" if v >= 0 else "", num(v) + ("%" if stat in PERCENT else ""), lab[2:].strip())


def upper_key(name):
    return re.sub(r"[^A-Z0-9]+", "_", name.upper()).strip("_")


def map_items_stat(k):
    k = k.upper()
    k = ALIAS.get(k, k)
    return k


def parse_lore_stats(lore):
    """Leading 'Name: +X' lines of a NEU lore, up to the first blank line."""
    known, other = {}, {}
    for raw in lore:
        line = strip(raw)
        if not line:
            break
        m = re.match(r"^([A-Za-z' ]+): ([+-]?[0-9.,]+)(%|s)?$", line)
        if not m:
            continue
        name, val = m.group(1).strip(), float(m.group(2).replace(",", ""))
        if name == "Accessory Power":
            continue
        if name in LORE_NAME:
            known[LORE_NAME[name]] = val
        else:
            other[upper_key(name)] = val
    return known, other


# ---------------------------------------------------------------------------------------------
# names (contract rule 1)
# ---------------------------------------------------------------------------------------------
NAME_OVERRIDES = {
    "RIFT_NECKLACE_OUTSIDE": "Dimensional Necklace",
    "RIFT_NECKLACE_INSIDE": "Inner Dimensional Necklace",
    "BLUETOOTH_RING": "Signal Ring",
    "BLUERTOOTH_RING": "Strong Signal Ring",
    "ZORROS_CAPE": "Masked Hopper's Cape",
    "DIVAN_PENDANT": "Pendant of the Deep King",
    "SHENS_REGALIA": "Collector's Regalia",
    "SHENS_RINGALIA": "Collector's Ringalia",
    "MELODY_HAIR": "Harpist's Hair",
    "JAKE_PLUSHIE": "Tracker's Plushie",
    "DAVIDS_CLOAK": "Huntmaster's Cloak",
    "JACOBUS_REGISTER": "Bagwright's Register",
    "ARGOFAY_TRINKET": "Mirrorwick Trinket",
    "MANDRAA": "Mandrake Bulb",
    "ABICASE": "Phone Case",
    "BUBBA_BLISTER": "Bloated Blister",
    "CHOCOLATE_CHIP": "Fang-tastic Chocolate Chip",
    "GLACITE_SHARD": "Glacite Chunk",
}
WORD_RENAMES = [
    ("Anita's", "Harvesta's"), ("Arachne's", "Spider Queen's"), ("Agarimoo", "Moocress"),
    ("Kuudra's", "Leviathan's"), ("Kuudra", "Leviathan"), ("Dante's", "Tyrant's"), ("Jerry", "Gerald"),
    ("Odger's", "Old Gill's"), ("Scarf's", "Professor's"), ("Moonglade", "Moonmarsh"), ("Torrhus", "Torrent"),
    ("Fermento", "Brewbloom"), ("Cropie", "Cropling"), ("Soulflow", "Soulstream"),
]
RENAMES = []   # (id, reference, ours)


def our_name(iid, ref):
    ref = strip(ref).replace("%%light_purple%%", "")
    if iid in NAME_OVERRIDES:
        ours = NAME_OVERRIDES[iid]
    else:
        ours = ref
        for a, b in WORD_RENAMES:
            ours = ours.replace(a, b)
    if ours != ref:
        RENAMES.append((iid, ref, ours))
    return ours


# ---------------------------------------------------------------------------------------------
# materials, rarity, requirements, unlocks
# ---------------------------------------------------------------------------------------------
MATERIAL_MAP = {
    ("SKULL_ITEM", 3): "PLAYER_HEAD", ("SKULL_ITEM", 2): "ZOMBIE_HEAD", ("SKULL_ITEM", 0): "SKELETON_SKULL",
    ("SKULL_ITEM", None): "SKELETON_SKULL", ("SKULL_ITEM", 1): "WITHER_SKELETON_SKULL",
    ("IRON_BARDING", None): "IRON_HORSE_ARMOR", ("GOLD_BARDING", None): "GOLDEN_HORSE_ARMOR",
    ("REDSTONE_COMPARATOR", None): "COMPARATOR", ("INK_SACK", 4): "LAPIS_LAZULI", ("WATCH", None): "CLOCK",
    ("SULPHUR", None): "GUNPOWDER", ("RAW_FISH", None): "COD", ("FIREWORK_CHARGE", None): "FIREWORK_STAR",
    ("STAINED_GLASS", None): "WHITE_STAINED_GLASS", ("MONSTER_EGG", None): "EGG", ("WOOL", None): "WHITE_WOOL",
    ("CARPET", None): "WHITE_CARPET", ("RED_ROSE", None): "POPPY", ("YELLOW_FLOWER", None): "DANDELION",
    ("SAPLING", None): "OAK_SAPLING", ("LOG", None): "OAK_LOG", ("LEAVES", None): "OAK_LEAVES",
    ("BOOK_AND_QUILL", None): "WRITABLE_BOOK", ("EXP_BOTTLE", None): "EXPERIENCE_BOTTLE",
    ("FIREBALL", None): "FIRE_CHARGE", ("EYE_OF_ENDER", None): "ENDER_EYE", ("WOOD_SWORD", None): "WOODEN_SWORD",
    ("GOLD_HELMET", None): "GOLDEN_HELMET", ("SNOW_BALL", None): "SNOWBALL", ("QUARTZ", None): "QUARTZ",
}


def material(it):
    mat, dur = it.get("material", "STONE"), it.get("durability")
    if (mat, dur) in MATERIAL_MAP:
        return MATERIAL_MAP[(mat, dur)]
    if (mat, None) in MATERIAL_MAP:
        return MATERIAL_MAP[(mat, None)]
    return mat


RARITY = {"COMMON": "COMMON", "UNCOMMON": "UNCOMMON", "RARE": "RARE", "EPIC": "EPIC", "LEGENDARY": "LEGENDARY",
          "MYTHIC": "MYTHIC", "DIVINE": "DIVINE", "SUPREME": "DIVINE", "SPECIAL": "SPECIAL",
          "VERY_SPECIAL": "VERY_SPECIAL", "UNOBTAINABLE": "SPECIAL"}


def requirements(it):
    out = []
    for r in it.get("requirements", []) or []:
        t = r.get("type")
        if t == "SLAYER":
            out.append("slayer:%s:%d" % (r["slayer_boss_type"], r["level"]))
        elif t == "HEART_OF_THE_MOUNTAIN":
            out.append("hotm:%d" % r["tier"])
        elif t == "SKILL":
            out.append("skill:%s:%d" % (r["skill"].lower(), r["level"]))
        elif t == "GARDEN_LEVEL":
            out.append("garden:%d" % r["level"])
        # wave 5: these kinds have keys now (dungeons / fishing / galatea / extras register the resolvers)
        elif t == "DUNGEON_TIER" and r.get("dungeon_type", "CATACOMBS") == "CATACOMBS":
            out.append("dungeon_completion:F%d" % r["tier"])
        elif t == "TROPHY_FISHING" and r.get("reward") in TROPHY_TITLE:
            if r.get("trophy_type") == "FROG":
                out.append("frogs:title:%d" % TROPHY_TITLE[r["reward"]])
            else:
                out.append("trophy:TITLE:%d" % TROPHY_TITLE[r["reward"]])
        elif t == "MELODY_HAIR":
            out.append("harp:songs:%d" % harp_song_count())
        # wave 5 (chocolate request 3): Zorro's Cape needs the Masked Hopper (chocolate rabbit id "zorro")
        elif t == "EASTER_RABBIT" and r.get("rabbit"):
            out.append("chocolate:rabbit:%s" % r["rabbit"].lower())
    return out


TROPHY_TITLE = {"BRONZE": 1, "SILVER": 2, "GOLD": 3, "DIAMOND": 4}


def harp_song_count():
    """'Requires Melody Harp completion' = every song extras ships (data/extras/songs.json)."""
    p = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "extras", "songs.json")
    with open(p, encoding="utf-8") as f:
        return len(json.load(f))


ROMAN = {"I": 1, "V": 5, "X": 10, "L": 50, "C": 100}


def roman(s):
    if s.isdigit():
        return int(s)
    t, prev = 0, 0
    for ch in reversed(s):
        v = ROMAN.get(ch, 0)
        t = t - v if v < prev else t + v
        prev = max(prev, v)
    return t


UNRESOLVED_UNLOCKS = set()


def unlock_of(crafttext):
    c = strip(crafttext)
    m = re.match(r"^Requires: (.+) ([IVXLC]+|\d+)$", c)
    if not m:
        return None
    what, lvl = m.group(1), roman(m.group(2))
    if what.endswith(" Slayer"):
        return "slayer:%s:%d" % (what[:-7].lower(), lvl)
    if what == "HotM":
        return "hotm:%d" % lvl
    key = COLL_BY_NAME.get(what.lower())
    if key:
        return "collection:%s:%d" % (key, lvl)
    UNRESOLVED_UNLOCKS.add(c)
    return None


# ---------------------------------------------------------------------------------------------
# families
# ---------------------------------------------------------------------------------------------
def families():
    """id -> (familyRoot, rank). Root = a chain head that is never an upgrade of anything."""
    upgraded = {u for lst in UPGRADES.values() for u in lst}
    fam = {}
    for root, chain in UPGRADES.items():
        if root in upgraded:
            continue
        full = [root] + chain
        for rank, iid in enumerate(full):
            prev = fam.get(iid)
            if prev is None or len(full) > prev[2]:
                fam[iid] = (root, rank, len(full))
    return {k: (v[0], v[1]) for k, v in fam.items()}


FAM = families()


# ---------------------------------------------------------------------------------------------
# description text (OUR wording)
# ---------------------------------------------------------------------------------------------
ISLAND_TEXT = {
    "hub": "the &bHub", "the_end": "&5The End", "crimson_isle": "the &cCrimson Isle",
    "jerrys_workshop": "the &bWinter Workshop", "backwater_bayou": "the &2Backwater Bayou",
    "garden": "the &aGarden", "dwarven_mines": "the &2Dwarven Mines", "crystal_hollows": "the &5Crystal Caverns",
    "mushroom_desert": "the &6Mushroom Desert", "park": "&aThe Park", "barn": "the &eBarn",
    "gold_mine": "the &6Gold Mine", "deep_caverns": "the &bDeep Caverns", "spiders_den": "the &4Spider's Den",
    "kuudra": "&cLeviathan's Hollow", "mineshaft": "a &bRimestone Mineshaft",
}
# wave 5: calendar events a condition can name (event:<id>), in our words
EVENT_TEXT = {"farming_contest": "a &eHarvest Fair", "mythological_ritual": "&6the Mythic Ritual"}
# wave 5: "while mining X" (Cond kind mining:<block ids>): ids are the mining module's data/mining/blocks.json ids;
# accessories resolves the block a player is digging to one of them (MineTargets). Our words for each set of ids.
MITHRIL_BLOCKS = "mithril_gray|mithril_prismarine|mithril_blue"
GEM_BLOCKS = "gem_ruby|gem_amber|gem_amethyst|gem_jade|gem_sapphire|gem_opal|gem_topaz|gem_jasper|gem_onyx|gem_aquamarine|gem_citrine|gem_peridot"
MINING_TEXT = {"gem_amber": "&6Amber", "gem_amethyst": "&5Amethyst", "gem_jade": "&aJade", "gem_sapphire": "&bSapphire",
               MITHRIL_BLOCKS: "&2Mithril", MITHRIL_BLOCKS + "|titanium": "&2Mithril &7or &fTitanium",
               GEM_BLOCKS: "&dGemstones"}
MINING_ISLANDS = "gold_mine|deep_caverns|dwarven_mines|crystal_hollows|glacite_tunnels"
MOB_TEXT = {
    "undead": "&2Undead", "arthropod": "&4Arthropod", "ender": "&5Ender", "animal": "&aAnimal",
    "skeletal": "&fSkeletal", "wither": "&8Wither", "infernal": "&cInfernal", "sea_creature": "&3Sea Creature",
    "dragon": "&5Dragon", "woodland": "&2Woodland", "kuudra_follower": "&cLeviathan Follower",
    "magmatic": "&6Magmatic", "mythological": "&6Mythological",
}
POTION_TEXT = {"haste": "Haste", "night_vision": "Night Vision", "jump_boost": "Jump Boost"}
SEASONS = ["Spring", "Summer", "Autumn", "Winter"]


def when_text(when):
    parts = []
    for w in when.split("&"):
        if w == "day":
            parts.append("during the day")
        elif w == "night":
            parts.append("at night")
        elif w == "burning":
            parts.append("while you are on fire")
        elif w == "dungeon":
            parts.append("while inside a dungeon")
        elif w == "master":
            parts.append("while inside a &cMaster Mode &7dungeon")
        elif w.startswith("event:"):
            parts.append("during " + " or ".join(EVENT_TEXT.get(i, i) + "&7" for i in w[6:].split("|")))
        elif w.startswith("island:"):
            ids = w[7:].split("|")
            if w[7:] == MINING_ISLANDS:
                parts.append("on any mining island")
            else:
                parts.append("on " + ", ".join(ISLAND_TEXT.get(i, i) + "&7" for i in ids))
        elif w.startswith("zone:"):
            ids = w[5:].split("|")
            parts.append("in the " + ", ".join("&a" + i.replace("_", " ").title() + "&7" for i in ids))
        elif w.startswith("season:"):
            parts.append("during &e" + SEASONS[int(w[7:])] + "&7")
        elif w.startswith("class:"):
            parts.append("while &b" + w[6:].title() + " &7is your dungeon class")
        elif w.startswith("mining:"):
            parts.append("while mining " + MINING_TEXT.get(w[7:], w[7:]) + "&7")
        else:
            parts.append(w)
    return " ".join(parts)


def wrap(text, width=34):
    out, line, vis, carry = [], "", 0, "&7"
    for word in text.split(" "):
        wv = len(re.sub(r"&[0-9a-fk-or]", "", word))
        if vis > 0 and vis + 1 + wv > width:
            out.append(line)
            line, vis = carry, 0
        if vis > 0:
            line += " "
            vis += 1
        line += word
        vis += wv
        for m in re.finditer(r"&[0-9a-fk-or]", word):
            carry = "&7" if m.group() == "&r" else m.group()
    if line:
        out.append(line)
    # collapse runs of colour codes ("&2&7word" -> "&7word"): only the last one is visible
    return [re.sub(r"(?:&[0-9a-f])+(&[0-9a-f])", lambda m: m.group(1), x) for x in out]


def describe(e):
    t = e["type"]
    if t == "stat_if":
        if not e.get("when") or e["when"] == "always":
            return "&7Grants %s&7." % statline(e["stat"], e["value"])
        return "&7Grants %s &7%s." % (statline(e["stat"], e["value"]), when_text(e["when"]))
    if t == "stat_pct":
        w = (" " + when_text(e["when"])) if e.get("when") and e["when"] != "always" else ""
        return "&7Increases your total %s &7by &a%s%%&7%s." % (LABEL[e["stat"]], num(e["pct"]), w)
    if t == "stat_zero":
        return "&7Sets your %s &7to &c0&7." % LABEL[e["stat"]]
    if t == "dmg_taken":
        w = (" " + when_text(e["when"])) if e.get("when") and e["when"] != "always" else ""
        if e["mob"] == "all":
            return "&7Reduces all damage you take by &a%s%%&7%s." % (num(e["pct"]), w)
        return "&7Take &a%s%% &7less damage from %s &7monsters%s." % (num(e["pct"]), MOB_TEXT[e["mob"]], w)
    if t == "dmg_dealt":
        who = "&7Your axes deal" if e.get("weapon") == "AXE" else "&7Deal"
        if e.get("mult"):
            return "%s &a%sx &7damage to %s &7monsters." % (who, num(1 + e["pct"] / 100.0), MOB_TEXT[e["mob"]])
        return "%s &a%s%% &7more damage to %s &7monsters." % (who, num(e["pct"]), MOB_TEXT[e["mob"]])
    if t == "burst":
        return ("&7When a single hit would take at least &c50%% &7of your max &c❤ Health&7, "
                "its damage is multiplied by &a%s&7." % num(e["mult"]))
    if t == "coins_minute":
        return "&7Earn &6%s coins &7every minute." % num(e["coins"])
    if t == "coin_find":
        return "&7Every so often you spot &6%s-%s coins &7on the ground of a public island." % (num(e["min"]), num(e["max"]))
    if t == "scavenger":
        return "&7Killing a monster pays &6%s coins &7extra per level of that monster." % num(e["per"])
    if t == "piggy":
        if e["save"] >= 100:
            return "&7When you die, the coins you would lose stay in your purse. &cIt cracks when it saves you!"
        return "&7When you die, &a%s%% &7of the coins you would lose stay in your purse. &cIt breaks when it saves you!" % num(e["save"])
    if t == "fall":
        s = "&7You can fall &a%s &7extra blocks before taking damage" % num(e["blocks"])
        if e.get("pct"):
            s += ", and fall damage is &a%s%% &7lower" % num(e["pct"])
        return s + "."
    if t == "fire_immune":
        return "&7Fire cannot hurt you. On the &cCrimson Isle &7it only lowers fire damage by &a%s%%&7." % num(e["crimsonPct"])
    if t == "lava_immune":
        return "&7Most lava can no longer hurt you."
    if t == "potion":
        return "&7Grants a permanent &a%s %s &7effect." % (POTION_TEXT[e["effect"]], "I" * (e["amp"] + 1) if e["amp"] < 3 else str(e["amp"] + 1))
    if t == "intimidate":
        if e["level"] <= 1:
            return "&7Level &a1 &7monsters no longer attack you."
        return "&7Monsters of level &a%s &7or lower no longer attack you." % num(e["level"])
    if t == "heal_on_kill":
        return "&7Killing %s &7monsters heals you for &c%s❤ &8(%ss cooldown)&7." % (MOB_TEXT[e["mob"]], num(e["hp"]), num(e["cd"]))
    if t == "regen":
        return "&7Restores &c%s❤ Health &7every second while equipped." % num(e["hp"])
    if t == "reaper":
        return "&7Each monster you killed in the last &a%ss &7grants &c+%s ❁ Strength&7." % (num(e["window"]), num(e["str"]))
    if t == "counter":
        return ("&7Counts every monster you kill while it is in your bag. Gain &c+%s ❁ Strength &7for each "
                "digit of the count &8(max 7 digits)&7." % num(e["per"]))
    if t == "nth_hit":
        return "&7Every &a%sth &7melee hit on the same target deals &a%s%% &7more damage." % (num(e["n"]), num(e["pct"]))
    if t == "wedding":
        return "&7A &a1 in %s &7chance on each hit to deal &cdouble &7damage." % format(int(round(1 / e["chance"])), ",")
    if t == "crowd":
        return "&7Hits deal &c+%s &7damage while at least &a%s &7players share your island." % (num(e["dmg"]), num(e["players"]))
    if t == "vamp_regen":
        return "&7Landing a melee hit triggers an extra natural regeneration tick &8(%ss cooldown)&7." % num(e["cd"])
    if t == "xp_boost":
        return "&7Experience orbs you pick up are worth &a%s%% &7more." % num(e["pct"])
    if t == "bits_double":
        return "&7Whenever you earn Bits, there is a &a%s%% &7chance to earn them twice." % num(e["chance"])
    if t == "gravity":
        return ("&7Grants between &c+1 &7and &c+10 ❁ Strength &7and &a❈ Defense&7, more the closer you stand "
                "to the spawn point of the island you are on.")
    if t == "auto_recomb":
        return "&7Unique drops from monsters have a &a%s%% &7chance to arrive already recombobulated." % num(e["chance"])
    if t == "compactor":
        return ("&7Turns up to &a%s &7chosen materials in your inventory into their enchanted form automatically. "
                "&eUse /compactor to choose them!" % num(e["slots"]))
    if t == "deletor":
        return ("&7Destroys up to &a%s &7chosen items the moment you would pick them up. "
                "&eUse /deletor to choose them!" % num(e["slots"]))
    if t == "equip_mult":
        where = []
        if e.get("island"):
            where.append("while you are on %s&7" % ISLAND_TEXT.get(e["island"], e["island"]))
        if e.get("when"):
            where.append(when_text(e["when"]))
        return "&7Every stat on this piece counts &a%sx &7%s." % (num(e["mult"]), " ".join(where))
    if t == "nearby_def":
        who = "burning enemy" if e.get("mob") == "burning" else "enemy"
        s = "&7Gain &a+%s ❈ Defense &7for each %s within &a%s &7blocks &8(max +%s)&7." % (
            num(e["per"]), who, num(e["range"]), format(int(e["cap"]), ","))
        if e.get("tankRange"):
            s += (" As a &bTank &7in dungeons the range grows to &a%s &7blocks and the cap to &a+%s&7."
                  % (num(e["tankRange"]), num(e["tankCap"])))
        return s
    if t == "mf_vs":
        if e.get("also"):
            return "&7and &b+%s ✯ Magic Find &7on their drops." % num(e["value"])
        return "&7Grants &b+%s ✯ Magic Find &7on drops from %s &7monsters." % (num(e["value"]), MOB_TEXT[e["mob"]])
    if t == "bat_person":
        return ("&7Gain &a+%s ❈ Defense&7, &c❁ Strength &7and &b✎ Intelligence &7for each bat you have summoned. "
                "&8Counts your Bat pet, bat-swarm masks and guided bats." % num(e["per"]))
    if t == "fire_taken":
        if e.get("per"):
            return "&cFire damage you take rises by %s%% for every ❁ Strength on this piece." % num(e["per"])
        s = "&cFire damage you take is increased by %s%%." % format(int(e["pct"]), ",")
        if e.get("carry"):
            s += " &7Your next hit adds &a%s%% &7of the last fire damage you took." % num(e["carry"])
        return s
    if t == "fire_fist":
        return ("&7Melee hits launch &a%s &7flaming fists that burn the target for &c%s &7fire damage over &a%ss&7."
                % (num(e["n"]), format(int(e["dmg"]), ","), num(e["window"])))
    if t == "haunt_skull":
        return ("&7While in a dungeon, a &5Haunted Skull &7circles you every &a%ss &7and after each melee kill. "
                "Enemies it touches are stunned for &a%ss &7and deal &a%s%% &7less damage."
                % (num(e["cd"]), num(e["window"]), num(e["pct"])))
    if t == "runic_counter":
        return ("&7Counts the &5Runic &7monsters you kill while it is in your bag or inventory. Each digit of the count "
                "raises its rarity in the bag &8(max %s digits)&7." % num(e["cap"]))
    if t == "keep_bag":
        what = {"mask": "carnival masks. Each different mask", "cake": "New Year cakes. Each different year"}[e["kind"]]
        return "&7Holds %s inside grants %s&7. &eRight-click to open!" % (what, statline(e["stat"], e["per"]))
    if t == "abicase":
        return ("&7Grants &6+1 Accessory Power &7for every &a%s &7Ringphone contacts you have, on top of its own."
                % num(e["per"]))
    if t == "magnet":
        return "&7Pulls in dropped items from &a%sx &7further away." % num(e["mult"])
    if t == "unseen":
        return "&7%s can no longer see you." % e["label"]
    if t == "poison_resist":
        return "&7Poison is &a%s%% &7weaker on you." % num(e["pct"])
    if t == "reflect":
        return "&7Has a &a%s%% &7chance to send fireballs back at whoever threw them." % num(e["chance"])
    if t == "contagion":
        return ("&7Kills explode for &a%s%% &7of the victim's max health, spreading to enemies within &a%s &7blocks."
                % (num(e["pct"]), num(e["range"])))
    if t == "drop_boost":
        return "&7Monster drops give you &a%s%% &7more %s&7." % (num(e["pct"]), e["label"])
    if t == "ability_boost":
        return "&7Explosion damage you deal is increased by &a%s%%&7." % num(e["pct"])
    if t == "health_ability":
        return "&7Abilities that cost health deal &a%s%% &7more damage." % num(e["pct"])
    if t == "dungeon_xp":
        if e["skill"] == "catacombs":
            return "&7Dungeon runs grant &a%s%% &7more Catacombs experience." % num(e["pct"])
        return "&7Dungeon class experience is &a%s%% &7higher." % num(e["pct"])
    if t == "weapon_buff":
        return "&7While equipped, your %s &7gains &c+%s &7Damage and &c+%s ❁ Strength&7." % (e["label"], num(e["dmg"]), num(e["str"]))
    if t == "vs_stat":
        if e.get("also"):
            return "&7and %s &7against them." % statline(e["stat"], e["value"])
        return "&7Against %s &7monsters: %s&7." % (MOB_TEXT[e["mob"]], statline(e["stat"], e["value"]))
    if t == "static":
        return ("&7Gains &a1 &7static charge every &a%ss &8(max %s)&7. Your next hit discharges it, dealing &a%s%% &7more "
                "damage per charge." % (num(e["window"]), num(e["cap"]), num(e["pct"])))
    if t == "pet_copy":
        return "&7Copies &a%s%% &7of the combat stats your active pet gives you." % num(e["pct"])
    if t == "piece_bonus":
        what = ("Mine &f%s" % e["label"]) if e.get("item") else ("Kill &f%s" % e["label"])
        return ("&6Piece Bonus: %s &7%s &7to build up %s &7on this piece: &a+1 &7per &a1,000&7, up to &a+10&7."
                % (e["name"], what, LABEL[e["stat"]]))
    if t == "tp_strike":
        return ("&7After you teleport, your next melee hit within &a%ss &7deals &a%s%% &7more damage."
                % (num(e["window"]), num(e["pct"])))
    if t == "per_skill":
        return "&7Gain %s &7per &a%s &7level." % (statline(e["stat"], e["per"]), e["skill"].title())
    if t == "corpse_ding":
        return "&7Sends out a &aDing &7when you come within &a%s &7blocks of an unlooted &bFrozen Corpse&7." % num(e["range"])
    if t == "magic8":
        return "&7Every SkyBlock season it settles on one blessing: Farming, Foraging, Mining, Fishing or Combat."
    raise ValueError("no description for " + t)


def desc_lines(effects, notes):
    lines = []
    by_class = {}
    for e in effects:
        if e["type"] == "stat_if" and e.get("when", "").startswith("class:"):
            by_class.setdefault(e["when"][6:], []).append(statline(e["stat"], e["value"]))
    if by_class:
        lines.extend(wrap("&7Grants extra stats based on your selected dungeon class:"))
        for c in DUNGEON_CLASSES:
            if c in by_class:
                lines.extend(wrap("&b%s&7: %s" % (c.title(), "&7, ".join(by_class[c])), 44))
    for e in effects:
        if e["type"] == "stat_if" and e.get("when", "").startswith("class:"):
            continue
        if e["type"] == "set":
            if e.get("perPiece"):
                head = "&6Tiered Bonus: %s &7(per piece worn, up to %s)" % (e["name"], e["need"])
            else:
                head = "&6Set Bonus: %s &7(%s pieces%s)" % (e["name"], e["need"], ", scales" if e.get("scale") else "")
            lines.append(head)
            for sub in e["effects"]:
                lines.extend(wrap(describe(sub)))
            continue
        lines.extend(wrap(describe(e)))
    for n in notes:
        lines.extend(wrap(n))
    return lines


# ---------------------------------------------------------------------------------------------
# SPEC — what each accessory DOES beyond its stat lines. effects = implemented by the module,
# notes = our-wording text for effects that belong to another system (hook named in extra.hook).
# ---------------------------------------------------------------------------------------------
SPEC = {}


def spec(iid, effects=None, notes=None, hook=None):
    SPEC[iid] = {"effects": effects or [], "notes": notes or [], "hook": hook}


def tiers(ids, fn):
    for i, iid in enumerate(ids):
        e, n, h = fn(i)
        spec(iid, e, n, h)


def S(stat, value, when):
    return {"type": "stat_if", "stat": stat, "value": value, "when": when}


DUNGEON_CLASSES = ["BERSERK", "HEALER", "MAGE", "TANK", "ARCHER"]
CLASS_LINE = re.compile(r"^(Berserk|Healer|Mage|Tank|Archer): (.+)$")


def class_stats(iid):
    """Adaptive Belt: the per-class bonus lines of its NEU lore ("Healer: +10 Health, +5 Mending") as
    stat_if effects gated on the selected dungeon class (Cond kind 'class')."""
    out = []
    for raw in neu(iid).get("lore", []):
        m = CLASS_LINE.match(strip(raw))
        if not m:
            continue
        for amt, name in re.findall(r"\+([\d.]+) ([A-Za-z ]+?)\s*(?:,|$)", m.group(2)):
            stat = upper_key(name)
            assert stat in LABEL, (iid, name)
            out.append(S(stat, float(amt), "class:" + m.group(1).upper()))
    assert {e["when"][6:] for e in out} == set(DUNGEON_CLASSES), (iid, out)
    return out


def health_cost_items():
    """Ancient Cloak: every item whose ability lore in the research carries a 'Health Cost:' line."""
    out = []
    for iid in sorted(ITEMS):
        if any(strip(l).startswith("Health Cost:") for l in neu(iid).get("lore", [])):
            out.append(iid)
    assert out, "no health-cost abilities found in the research"
    return out


def DT(mob, pct, when=None):
    d = {"type": "dmg_taken", "mob": mob, "pct": pct}
    if when:
        d["when"] = when
    return d


def DD(mob, pct, mult=False, weapon=None):
    d = {"type": "dmg_dealt", "mob": mob, "pct": pct}
    if mult:
        d["mult"] = True
    if weapon:
        d["weapon"] = weapon
    return d


tiers(["ACCRETION_TALISMAN", "ACCRETION_RING", "ACCRETION_ARTIFACT"],
      lambda i: (None, ["&7Your pocket black holes pull &a%d%% &7faster." % [5, 10, 15][i]], "foraging"))
# wave 5: live during the farming module's Harvest Fair (calendar event farming_contest)
tiers(["ANITA_TALISMAN", "ANITA_RING", "ANITA_ARTIFACT"],
      lambda i: ([S("FARMING_FORTUNE", [5, 15, 25][i], "event:farming_contest")], None, None))
spec("ARCHAEOLOGIST_COMPASS", notes=["&7Right-click it to point at the nearest hidden relic."], hook="rift")
spec("ARTIFACT_OF_CONTROL", notes=["&7Your votes in mayor elections count &atwice&7."], hook="mayors")
tiers(["COIN_TALISMAN", "RING_OF_COINS", "ARTIFACT_OF_COINS", "RELIC_OF_COINS"],
      lambda i: ([{"type": "coin_find", "min": [1, 3, 5, 10][i], "max": [5, 15, 30, 60][i], "chance": [0.04, 0.06, 0.08, 0.1][i]}], None, None))
tiers(["POTION_AFFINITY_TALISMAN", "RING_POTION_AFFINITY", "ARTIFACT_POTION_AFFINITY"],
      lambda i: (None, ["&7Potions you drink last &a%d%% &7longer." % [10, 25, 50][i]], "alchemy"))
spec("ATMOSPHERIC_FILTER", [S("FARMING_FORTUNE", 25, "island:garden&season:0"), S("FARMING_WISDOM", 20, "island:garden&season:1")],
     ["&7In &eAutumn &7pests show up &a15% &7more often on your Garden, and in &eWinter &7visitors pay &a5% &7more copper."], "garden")
spec("AUTO_RECOMBOBULATOR", [{"type": "auto_recomb", "chance": 1}])
spec("BAIT_RING", notes=["&7Each cast has a &a5% &7chance not to use up your bait."], hook="fishing")
spec("SPIKED_ATROCITY", notes=["&7Each cast has a &a10% &7chance not to use up your bait."], hook="fishing")
tiers(["BAT_PERSON_TALISMAN", "BAT_PERSON_RING", "BAT_PERSON_ARTIFACT"],
      lambda i: ([{"type": "bat_person", "per": [2, 5, 10][i]}], None, None))
for t, v in (("COMMON", 0.1), ("UNCOMMON", 0.2), ("RARE", 0.3), ("EPIC", 0.4), ("LEGENDARY", 0.5)):
    spec("BEASTMASTER_CREST_" + t, notes=["&7Pets gain &a%s%% &7more experience, and the crest grows with every mythical beast you slay." % num(v)], hook="pets")
for b in ("BINGO_TALISMAN", "BINGO_RING", "BINGO_ARTIFACT", "BINGO_RELIC"):
    spec(b, notes=["&7Proof that you finished a bingo card. Shows off your bingo rank."], hook="events")
tiers(["BIOANALYSIS_TALISMAN", "BIOANALYSIS_RING", "BIOANALYSIS_ARTIFACT"],
      lambda i: (None, ["&7Crops are &a%d%% &7more likely to mutate." % [5, 10, 15][i]], "garden"))
spec("BITS_TALISMAN", [{"type": "bits_double", "chance": 10}])
spec("BLAZE_TALISMAN", [DT("infernal", 5)])
spec("BLOOD_GOD_CREST", [{"type": "counter", "per": 1}])
spec("BLOOD_GOD_SIGIL", [{"type": "counter", "per": 2}])
spec("BLUETOOTH_RING", [{"type": "crowd", "dmg": 1, "players": 6}])
spec("BLUERTOOTH_RING", [{"type": "crowd", "dmg": 2, "players": 6}])
tiers(["WHITE_GIFT_TALISMAN", "GREEN_GIFT_TALISMAN", "BLUE_GIFT_TALISMAN", "PURPLE_GIFT_TALISMAN", "GOLD_GIFT_TALISMAN"],
      lambda i: (None, ["&7Gifts you open give &a%d%% &7more coins and skill XP." % [5, 10, 15, 20, 25][i]], "events"))
spec("BOOK_OF_PROGRESSION", notes=["&7A book that grows with you. Its modifiers are picked from its own menu."], hook="progression")
spec("BROKEN_PIGGY_BANK", notes=["&7Shattered. Craft it back into a working piggy bank."])
spec("CRACKED_PIGGY_BANK", [{"type": "piggy", "save": 75, "next": "BROKEN_PIGGY_BANK", "min": 20000}])
spec("PIGGY_BANK", [{"type": "piggy", "save": 100, "next": "CRACKED_PIGGY_BANK", "min": 20000}])
spec("BUCKET_OF_DYE", notes=["&7Dyes drop &a1% &7more often for you."], hook="loot")
for i in range(1, 30):
    spec("CAMPFIRE_TALISMAN_%d" % i, [S("HEALTH_REGEN", i + 1, "burning")])
    spec("SOUL_CAMPFIRE_TALISMAN_%d" % i, [S("DEFENSE", i + 1, "burning")])
tiers(["CANDY_TALISMAN", "CANDY_RING", "CANDY_ARTIFACT", "CANDY_RELIC"],
      lambda i: (None, ["&7Monsters drop candy &a%d%% &7more often during the Spooky Festival." % [5, 10, 15, 20][i]], "mayors"))
spec("CARNIVAL_MASK_BAG", [{"type": "keep_bag", "kind": "mask", "stat": "INTELLIGENCE", "per": 1}])
spec("CATACOMBS_EXPERT_RING", [{"type": "dungeon_xp", "skill": "catacombs", "pct": 10}])
tiers(["CENTURY_TALISMAN", "CENTURY_RING", "CENTURY_ARTIFACT"],
      lambda i: (None, ["&7Eating a century cake is &a%d%% &7more likely to reveal a cake soul." % [1, 2, 3][i]], "events"))
tiers(["COPPER_TALISMAN", "COPPER_RING", "COPPER_ARTIFACT"],
      lambda i: (None, ["&7Rare or better Garden visitors are &a%d%% &7more likely to arrive." % [4, 8, 12][i]], "garden"))
spec("CROOKED_ARTIFACT", notes=["&7Most NPC shops give you a &a2% &7discount."], hook="shops")
spec("SHADY_RING", notes=["&7Most NPC shops give you a &a1% &7discount."], hook="shops")
spec("SEAL_OF_THE_FAMILY", notes=["&7Most NPC shops give you a &a3% &7discount."], hook="shops")
spec("DANTE_TALISMAN", notes=["&7A medal of loyalty to a fallen tyrant. Guards still respect it."])
spec("DANTE_RING", notes=["&7Dug out of a hidden warehouse. Mostly decorative."])
spec("DAY_CRYSTAL", [S("STRENGTH", 5, "day"), S("DEFENSE", 5, "day")])
spec("NIGHT_CRYSTAL", [S("STRENGTH", 5, "night"), S("DEFENSE", 5, "night")])
spec("SUNSHINE_CRYSTAL", [S("STRENGTH", 10, "day"), S("DEFENSE", 10, "day")])
spec("MOONLIGHT_CRYSTAL", [S("STRENGTH", 10, "night"), S("DEFENSE", 10, "night")])
spec("DEVOUR_RING", [{"type": "heal_on_kill", "mob": "undead", "hp": 10, "cd": 0.5}])
tiers(["DRACONIC_TALISMAN", "DRACONIC_RING", "DRACONIC_ARTIFACT"], lambda i: ([DD("dragon", [2, 3, 5][i])], None, None))
tiers(["RAGGEDY_SHARK_TOOTH_NECKLACE", "DULL_SHARK_TOOTH_NECKLACE", "HONED_SHARK_TOOTH_NECKLACE",
       "SHARP_SHARK_TOOTH_NECKLACE", "RAZOR_SHARP_SHARK_TOOTH_NECKLACE"],
      lambda i: (None, ["&7Shark teeth are &a%d%% &7more likely during fishing festivals." % [5, 10, 15, 20, 25][i]], "fishing"))
spec("EMERALD_RING", [{"type": "coins_minute", "coins": 5}])
spec("EMERALD_ARTIFACT", [{"type": "coins_minute", "coins": 20}])
spec("ENDER_ARTIFACT", [DT("ender", 20)])
spec("ENDER_RELIC", [DT("ender", 25)])
spec("LUCKY_HOOF", notes=["&7Gives a &a1% &7chance to find trick-or-treat chests."], hook="mayors")
spec("ETERNAL_HOOF", notes=["&7Gives a &a2% &7chance to find trick-or-treat chests."], hook="mayors")
spec("EXPERIENCE_ARTIFACT", [{"type": "xp_boost", "pct": 25}])
spec("FARMER_ORB", notes=["&7Crops near you on public islands regrow one extra crop every &a3s&7."], hook="farming")
tiers(["FEATHER_TALISMAN", "FEATHER_RING", "FEATHER_ARTIFACT"],
      lambda i: ([{"type": "fall", "blocks": [5, 7, 10][i], "pct": [0, 5, 15][i]}], None, None))
spec("FIRE_TALISMAN", [{"type": "fire_immune", "crimsonPct": 20}])
for t, v in (("TALISMAN", 1), ("RING", 2), ("ARTIFACT", 3), ("RELIC", 4), ("HEIRLOOM", 5)):
    spec("FRESHLY_BAKED_" + t, notes=["&7Its Overbloom doubles during feast events."], hook="events")
for c, v in (("NIBBLE_CHOCOLATE_STICK", (2, 10)), ("SMOOTH_CHOCOLATE_BAR", (4, 20)), ("RICH_CHOCOLATE_CHUNK", (6, 30)),
             ("GANACHE_CHOCOLATE_SLAB", (8, 40)), ("PRESTIGE_CHOCOLATE_REALM", (10, 50))):
    spec(c, notes=["&7The chocolate factory makes &6+%d chocolate &7per second, and new rabbits are &a%d%% &7easier to find." % (v[1], v[0])], hook="events")
spec("GENERAL_MEDALLION", notes=["&7Inside dungeons, your dungeon gear's base stats rise &a1% &7per digit of your secrets found &8(max 5%)&7."], hook="dungeons")
tiers(["GLACIAL_TALISMAN", "GLACIAL_RING", "GLACIAL_ARTIFACT"], lambda i: ([S("MINING_WISDOM", [5, 10, 15][i], "island:jerrys_workshop")], None, None))
tiers(["GRATITUDE_TALISMAN", "GRATITUDE_RING", "GRATITUDE_ARTIFACT"],
      lambda i: (None, ["&7Garden visitors arrive &a%d%% &7faster while you are there." % [1, 3, 5][i]], "garden"))
spec("GRAVITY_TALISMAN", [{"type": "gravity", "min": 1, "max": 10}])
# wave 5: the soulflow module runs these (pool, overflow mana, combat drains); "Soulstream" is our name (its request 3)
spec("HANDY_BLOOD_CHALICE", notes=["&7When switched on in &e/soulflow&7, burns Soulstream in combat to grant &c+20 ⫽ Ferocity&7."], hook="soulflow")
spec("POCKET_ESPRESSO_MACHINE", notes=["&7When switched on in &e/soulflow&7, burns Soulstream in combat to grant &b+100 ✎ Intelligence&7."], hook="soulflow")
for n, mana in (("SOULFLOW_PILE", 200), ("SOULFLOW_BATTERY", 400), ("SOULFLOW_SUPERCELL", 600)):
    spec(n, notes=["&7Stores Soulstream and turns it into &b%d overflow mana &7when you leave combat." % mana], hook="soulflow")
spec("HASTE_RING", [{"type": "potion", "effect": "haste", "amp": 1}])
spec("HASTE_ARTIFACT", [{"type": "potion", "effect": "haste", "amp": 2}])
spec("HEGEMONY_ARTIFACT", notes=["&7Counts &atwice &7toward your Accessory Power."])
spec("HOCUS_POCUS_CIPHER", notes=["&7In the Rift: &a+65 &7Rift Time, &b+5 ✎ Intelligence &7and &b+5 Mana Regen&7."], hook="rift")
tiers(["HONEYCOMB_TALISMAN", "HONEYCOMB_RING", "HONEYCOMB_ARTIFACT"],
      lambda i: (None, ["&7Trees smeared with honeycomb lure monsters &a%d%% &7faster." % [5, 10, 15][i]], "foraging"))
tiers(["INTIMIDATION_TALISMAN", "INTIMIDATION_RING", "INTIMIDATION_ARTIFACT", "INTIMIDATION_RELIC"],
      lambda i: ([{"type": "intimidate", "level": [1, 5, 25, 30][i]}], None, None))
spec("IQ_POINT", [{"type": "stat_pct", "stat": "INTELLIGENCE", "pct": 1}])
spec("TWO_IQ_POINT", [{"type": "stat_pct", "stat": "INTELLIGENCE", "pct": 2}])
spec("JAKE_PLUSHIE", notes=["&7Hunting animals yields &a+1 &7extra pelt."], hook="farming")
for j in ("JERRY_TALISMAN_GREEN", "JERRY_TALISMAN_BLUE", "JERRY_TALISMAN_PURPLE", "JERRY_TALISMAN_GOLDEN"):
    spec(j, notes=["&7Wear it to show you love Gerald. Or that you can't stand him."])
tiers(["JUNK_TALISMAN", "JUNK_RING", "JUNK_ARTIFACT"], lambda i: ([S("TREASURE_CHANCE", [2.5, 5, 7.5][i], "island:backwater_bayou")], None, None))
spec("KING_TALISMAN", notes=["&7Each commission you finish grants &a+10 &7extra mountain experience."], hook="mining")
for n, v in (("KUUDRAS_KIDNEY", (10, 5)), ("KUUDRAS_LUNG", (20, 10)), ("KUUDRAS_HEART", (30, 15))):
    spec(n, notes=["&7In the Leviathan hunt you carry crates &a%d%% &7faster and build the ballista &a%d%% &7faster." % v], hook="kuudra")
spec("KUUDRA_FOLLOWER_ARTIFACT", [DT("kuudra_follower", 50)])
spec("KUUDRA_FOLLOWER_RELIC", [DT("kuudra_follower", 65)])
for n, v in (("SMALL_FISH_BOWL", "30%"), ("MEDIUM_FISH_BOWL", "60%"), ("LARGE_FISH_BOWL", "100%")):
    spec(n, notes=["&7Rare shards are &a%s &7more likely when fishing." % v], hook="fishing")
spec("LAVA_TALISMAN", [{"type": "lava_immune"}])
tiers(["LUMBERJACK_TALISMAN", "LUMBERJACK_RING", "LUMBERJACK_ARTIFACT"],
      lambda i: (None, ["&7Your thrown foraging axe comes back &a%d%% &7sooner." % [5, 10, 15][i]], "foraging"))
spec("MAGIC_8_BALL", [{"type": "magic8"}])
spec("MAGNETIC_TALISMAN", [{"type": "magnet", "mult": 3}])
for t, v in ((1, 1), (2, 2), (3, 3), (4, 4), (5, 6), (6, 8), (7, 10)):
    # wave 5: Features.Dungeons.masterMode exists, so "while in Master Mode Dungeons" is exact now
    spec("MASTER_SKULL_TIER_%d" % t, [{"type": "stat_pct", "stat": "HEALTH", "pct": v, "when": "master"},
                                      {"type": "stat_pct", "stat": "STRENGTH", "pct": v, "when": "master"}])
spec("MINE_TALISMAN", [S("SPEED", 25, "island:" + MINING_ISLANDS)])
spec("NETHER_ARTIFACT", [DT("infernal", 5), DT("all", 5, "island:crimson_isle")])
spec("NETHERRACK_LOOKING_SUNSHADE", [{"type": "unseen", "mob": "ghast", "label": "Ghasts"}])
spec("NEW_YEAR_CAKE_BAG", [{"type": "keep_bag", "kind": "cake", "stat": "HEALTH", "per": 1}])
spec("NIGHT_VISION_CHARM", [{"type": "potion", "effect": "night_vision", "amp": 0}])
# wave 5: live here - the mining module's Frozen Corpses are armour stands, removed when looted (Tunnels.spawnCorpses)
tiers(["ORGAN_DONOR_TALISMAN", "ORGAN_DONOR_RING", "ORGAN_DONOR_ARTIFACT"],
      lambda i: ([{"type": "corpse_ding", "range": 20}], None, None))
spec("PANDORAS_BOX", notes=["&7Complete its challenges to level it up and earn stats."], hook="progression")
for i, n in enumerate(("PERSONAL_COMPACTOR_4000", "PERSONAL_COMPACTOR_5000", "PERSONAL_COMPACTOR_6000", "PERSONAL_COMPACTOR_7000")):
    spec(n, [{"type": "compactor", "slots": [1, 3, 7, 12][i]}])
for i, n in enumerate(("PERSONAL_DELETOR_4000", "PERSONAL_DELETOR_5000", "PERSONAL_DELETOR_6000", "PERSONAL_DELETOR_7000")):
    spec(n, [{"type": "deletor", "slots": [1, 3, 7, 12][i]}])
tiers(["POTATO_TALISMAN", "POTATO_RING"], lambda i: (None, ["&7Your potato minions work &a%d%% &7faster while you are on your island." % [5, 10][i]], "minions"))
tiers(["POWER_TALISMAN", "POWER_RING", "POWER_ARTIFACT", "POWER_RELIC"],
      lambda i: (None, ["&7Holds &a%d &7gemstones, which give half of their usual stats." % [2, 4, 7, 12][i]], None))
# wave 5: the halving is live here (extra.gemPowerMult 0.5, applied by accessories' item stat contributor, which is
# registered in postEnable so it runs after reforges' gem contributor)
GEM_POWER_MULT = {"POWER_TALISMAN": 0.5, "POWER_RING": 0.5, "POWER_ARTIFACT": 0.5, "POWER_RELIC": 0.5}
# wave 5: the Rift's own stats while in the Rift (NEU lore); the rift module reads extra.riftStats of bag accessories
RIFT_EXTRA = {"HOCUS_POCUS_CIPHER": {"RIFT_TIME": 65, "RIFT_INTELLIGENCE": 5, "RIFT_MANA_REGEN": 5}}
spec("PULSE_RING", notes=["&7Adds &a1% &7charge when filling thunder bottles. Feed it thunder to raise its rarity."], hook="fishing")
spec("REAPER_ORB", [{"type": "reaper", "str": 2, "window": 5}])
spec("RIFT_PRISM", notes=["&7While it is in your bag, entering the Rift is free.",
                          "&7The &dThaumaturgist &7can consume it for a permanent &6+11 Accessory Power&7."], hook="rift")
tiers(["RUNEBLADE_TALISMAN", "RUNEBLADE_RING", "RUNEBLADE_ARTIFACT"], lambda i: ([DD("woodland", [5, 10, 15][i], weapon="AXE")], None, None))
# wave 5: the counter and the rarity are ours (on the item); the Wisdom is the skills module's (do not add it twice)
spec("RUNEBOOK", [{"type": "runic_counter", "cap": 4}],
     ["&7Each digit also grants &3+1 ☯ Runecrafting Wisdom&7."], "skills")
for n, v in (("SCARF_STUDIES", 2), ("SCARF_THESIS", 4), ("SCARF_GRIMOIRE", 6)):
    spec(n, [{"type": "dungeon_xp", "skill": "class", "pct": v}])
tiers(["SCAVENGER_TALISMAN", "SCAVENGER_RING", "SCAVENGER_ARTIFACT"], lambda i: ([{"type": "scavenger", "per": [0.5, 0.6, 0.75][i]}], None, None))
tiers(["SEA_CREATURE_TALISMAN", "SEA_CREATURE_RING", "SEA_CREATURE_ARTIFACT"], lambda i: ([DT("sea_creature", [5, 10, 15][i])], None, None))
spec("SHENS_REGALIA", notes=["&7Carry it to be let into a secret room beneath the Auction House."], hook="auction")
spec("SKELETON_TALISMAN", [DT("skeletal", 5)])
tiers(["SPIDER_TALISMAN", "SPIDER_RING", "SPIDER_ARTIFACT"], lambda i: ([DT("arthropod", [5, 10, 15][i])], None, None))
spec("TARANTULA_TALISMAN", [{"type": "nth_hit", "n": 10, "pct": 10}])
spec("TARANTULA_RING", [{"type": "nth_hit", "n": 10, "pct": 15}])
spec("TRAPPER_CREST", notes=["&7Tracker quests pay &a1.1x &7pelts. Collect 100 pelts to raise its rarity."], hook="farming")
tiers(["TREASURE_TALISMAN", "TREASURE_RING", "TREASURE_ARTIFACT"],
      lambda i: (None, ["&7Dungeon reward chests hold &a%d%% &7more loot." % [1, 2, 3][i]], "dungeons"))
tiers(["VACCINE_TALISMAN", "VACCINE_RING", "VACCINE_ARTIFACT"], lambda i: ([{"type": "poison_resist", "pct": [10, 25, 50][i]}], None, None))
spec("VAMPIRE_DENTIST_RELIC", [{"type": "vamp_regen", "cd": 20}])
spec("VILLAGE_TALISMAN", [S("SPEED", 10, "zone:village")])
# the brief asks for the classic farming-talisman speed bonus on top of today's +3 Farming Fortune line
spec("FARMING_TALISMAN", [S("SPEED", 10, "zone:farm"), S("SPEED", 10, "island:barn|mushroom_desert|garden")])
spec("WOOD_TALISMAN", [S("SPEED", 10, "zone:forest|graveyard|wilderness"), S("SPEED", 10, "island:park")])
for n, v in (("VOTER_BADGE", 3), ("VOTER_BADGE_VIP", 6), ("VOTER_BADGE_ELITE", 9), ("VOTER_BADGE_SUPREME", 12)):
    spec(n, notes=["&7Adds &a+%d &7votes to your ballot in mayor elections." % v], hook="mayors")
for i in range(10):
    spec("WEDDING_RING_%d" % i, [{"type": "wedding", "chance": 10.0 ** -(12 - i)}])
tiers(["WITHER_ARTIFACT", "WITHER_RELIC"], lambda i: ([DT("wither", [20, 25][i])], None, None))
tiers(["WOLF_TALISMAN", "WOLF_RING"], lambda i: ([DT("animal", [5, 10][i])], None, None))
tiers(["ZOMBIE_TALISMAN", "ZOMBIE_RING", "ZOMBIE_ARTIFACT"], lambda i: ([DT("undead", [5, 10, 15][i])], None, None))
spec("ABICASE", [{"type": "abicase", "per": 2}])
# wave 5: Hunter Fortune is core's Hunting Fortune stat now (a real stat line, no note, no hook)

# ---- equipment -------------------------------------------------------------------------------
GEM_SET = ["AMBER_NECKLACE", "AMETHYST_GAUNTLET", "JADE_BELT", "SAPPHIRE_CLOAK"]
# wave 5: the per-piece "while mining X" lines are live here (Cond kind mining:, the block being dug), so the
# mining sets no longer wait on the mining module
for g, ore in zip(GEM_SET, ("gem_amber", "gem_amethyst", "gem_jade", "gem_sapphire")):
    spec(g, [S("MINING_FORTUNE", 30, "mining:" + ore),
             {"type": "set", "name": "Gemstone Gatherer", "members": GEM_SET, "need": 4,
              "effects": [S("MINING_FORTUNE", 25, "island:crystal_hollows")]}])
MITH = ["MITHRIL_NECKLACE", "MITHRIL_CLOAK", "MITHRIL_BELT", "MITHRIL_GAUNTLET"]
TITA = ["TITANIUM_NECKLACE", "TITANIUM_CLOAK", "TITANIUM_BELT", "TITANIUM_GAUNTLET"]
for m in MITH:
    spec(m, [S("MINING_FORTUNE", 5, "mining:" + MITHRIL_BLOCKS),
             {"type": "set", "name": "Dwarf Wannabe", "members": MITH, "need": 4,
              "effects": [S("MINING_SPEED", 30, "island:dwarven_mines"), S("MINING_FORTUNE", 5, "island:dwarven_mines")]}])
for m in TITA:
    spec(m, [S("MINING_FORTUNE", 10, "mining:" + MITHRIL_BLOCKS + "|titanium"),
             {"type": "set", "name": "True Dwarf", "members": TITA, "need": 4,
              "effects": [S("MINING_SPEED", 50, "island:dwarven_mines"), S("MINING_FORTUNE", 40, "island:dwarven_mines")]}])
for a in ("ANGLER_NECKLACE", "ANGLER_CLOAK", "ANGLER_BELT", "ANGLER_BRACELET"):
    spec(a, [{"type": "set", "name": "Depth Champion", "prefix": "ANGLER_", "need": 8, "perPiece": True, "effects": [DT("sea_creature", 3)]},
             {"type": "set", "name": "Deepness Within", "prefix": "ANGLER_", "need": 4, "perPiece": True, "effects": [{"type": "per_skill", "stat": "HEALTH", "per": 1.5, "skill": "FISHING"}]}])
for a in ("ARACHNE_NECKLACE", "ARACHNE_CLOAK", "ARACHNE_BELT", "ARACHNE_GLOVES"):
    spec(a, [{"type": "set", "name": "Queen's Faithful", "prefix": "ARACHNE_", "need": 8, "perPiece": True,
              "effects": [S("HEALTH", 5, "always"), S("DEFENSE", 5, "always")]}])
for a in ("BACKWATER_NECKLACE", "BACKWATER_CLOAK", "BACKWATER_BELT", "BACKWATER_GLOVES"):
    spec(a, [{"type": "set", "name": "Swamp Soldier", "prefix": "BACKWATER_", "need": 8, "perPiece": True, "effects": [DD("sea_creature", 5)]}])
spec("ANNIHILATION_CLOAK", [{"type": "regen", "hp": 25}])
spec("DESTRUCTION_CLOAK", [{"type": "regen", "hp": 15}])
spec("GHAST_CLOAK", [{"type": "regen", "hp": 5}])
spec("VANQUISHED_GHAST_CLOAK", [{"type": "regen", "hp": 15},
                                {"type": "piece_bonus", "name": "Ghast Blaster", "mob": "ghast", "label": "Ghasts", "stat": "DEFENSE"}])
spec("BALLOON_SNAKE", [{"type": "potion", "effect": "jump_boost", "amp": 1}])
for b in ("BONE_NECKLACE", "STARRED_BONE_NECKLACE"):
    spec(b, [{"type": "nearby_def", "per": 3, "cap": 30, "range": 10, "tankRange": 30, "tankCap": 60}],
         ["&7Increases the range of your Diversion passive by &a15 &7blocks."], "dungeons")
for v, pct in (("WHITE", 0.5), ("YELLOW", 1), ("GREEN", 3), ("BLUE", 4), ("BROWN", 5), ("BLACK", 6)):
    spec("DOJO_%s_BELT" % v, [DT("all", pct)])
spec("DEMONLORD_GAUNTLET", [DD("infernal", 15, mult=True)])
spec("DRAGONFADE_CLOAK", [{"type": "equip_mult", "mult": 3, "island": "the_end"}, DD("dragon", 1, mult=True)])
for e in ("ENDER_NECKLACE", "ENDER_CLOAK", "ENDER_BELT", "ENDER_GAUNTLET"):
    spec(e, [{"type": "equip_mult", "mult": 2, "island": "the_end"}])
spec("LAVA_SHELL_NECKLACE", [{"type": "stat_zero", "stat": "HEALTH_REGEN"}, {"type": "stat_zero", "stat": "MENDING"}],
     ["&cYou can no longer be healed by anything else."])
spec("PELT_BELT", [S("SPEED", 30, "island:mushroom_desert")], ["&7Tracker quests give &a+1 &7pelt."], "farming")
for a in ("ADAPTIVE_BELT", "STARRED_ADAPTIVE_BELT"):
    spec(a, class_stats(a))
spec("ANCIENT_CLOAK", [{"type": "health_ability", "pct": 10, "members": health_cost_items()}])
spec("BLAZE_BELT", [{"type": "reflect", "chance": 10}])
spec("VANQUISHED_BLAZE_BELT", [{"type": "reflect", "chance": 20},
                               {"type": "piece_bonus", "name": "Blaze Consumer", "mob": "blaze", "label": "Blazes", "stat": "STRENGTH"}])
for g in ("BLOSSOM", "LOTUS"):
    for p in ("NECKLACE", "CLOAK", "BELT", "BRACELET"):
        spec("%s_%s" % (g, p), notes=["&7Completing Garden visitor offers slowly adds &6☘ Farming Fortune &7to this piece."], hook="garden")
UNEARTHED = ["%s_%s" % (g, p) for g in ("CHALLENGER", "MYTHOS")
             for p in ("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS", "NECKLACE", "CLOAK", "BELT", "BRACELET")]
for g in ("CHALLENGER", "MYTHOS"):
    for p in ("NECKLACE", "CLOAK", "BELT", "BRACELET"):
        # wave 5: x2 in the Hub while the Mythic Ritual runs (calendar event mythological_ritual), and the
        # Unearthed tier the armour module pays per armour piece, paid here per equipment piece (5% each, armour's dial)
        spec("%s_%s" % (g, p), [{"type": "equip_mult", "mult": 2, "island": "hub", "when": "event:mythological_ritual"},
                                {"type": "set", "name": "Unearthed", "members": UNEARTHED, "need": 8, "perPiece": True,
                                 "effects": [DT("mythological", 5)]}])
spec("DAVIDS_CLOAK", notes=["&7Grows stronger the more animals you hunt."], hook="farming")
spec("SAFARI_BELT", notes=["&7Grows stronger with every safari milestone."], hook="farming")
# wave 5: the downsides are live (dial accessories.fire-downsides turns them off); the lore says so in red
spec("DELIRIUM_NECKLACE", [{"type": "fire_taken", "per": 8}])
# wave 5: "Tiered Bonus: Legacy (0/1)" - +0.25 Pristine and +2.5 Gemstone Spread inside the Glacite Mineshafts (NEU lore);
# our Mining Spread stat, only while the block being dug is a gemstone, is the Gemstone Spread
spec("DIVAN_PENDANT", [S("PRISTINE", 0.25, "island:mineshaft"),
                       S("MINING_SPREAD", 2.5, "island:mineshaft&mining:" + GEM_BLOCKS)])
spec("DRAGONFUSE_GLOVE", [{"type": "weapon_buff", "item": "ASPECT_OF_THE_DRAGON", "label": "&6Aspect of the Dragons", "dmg": 35, "str": 50}])
spec("FLAMING_FIST", [{"type": "fire_fist", "n": 3, "dmg": 10000, "window": 3}])
spec("GAUNTLET_OF_CONTAGION", [{"type": "contagion", "pct": 10, "range": 2}])
# wave 5: core's per-target Magic Find hook makes the first half exact; burrow tracking is the events owner's
spec("GOLDEN_FLEECE", [{"type": "mf_vs", "mob": "mythological", "value": 5}],
     ["&7Ability: &a+15 &7tracking on burrows."], "events")
spec("IMPLOSION_BELT", [{"type": "ability_boost", "pct": 25, "match": "implosion|explos|blast|wither impact"}])
spec("MAGMA_NECKLACE", [{"type": "drop_boost", "item": "MAGMA_CREAM", "label": "Magma Cream", "pct": 10}])
spec("VANQUISHED_MAGMA_NECKLACE", [{"type": "drop_boost", "item": "MAGMA_CREAM", "label": "Magma Cream", "pct": 20},
                                   {"type": "piece_bonus", "name": "Magma Cube Absorber", "mob": "magma_cube", "label": "Magma Cubes", "stat": "CRIT_DAMAGE"}])
spec("VANQUISHED_GLOWSTONE_GAUNTLET", [{"type": "piece_bonus", "name": "Glowing", "item": "GLOWSTONE_DUST", "label": "Glowstone", "stat": "INTELLIGENCE"}])
spec("MAGMA_LORD_GAUNTLET", notes=["&7Pieces of the magma lord set make you immune to island lava and add sea creature chance."], hook="fishing")
spec("MANTICORE_CLAW", [{"type": "pet_copy", "pct": 10}])
MOLTEN = ["MOLTEN_NECKLACE", "MOLTEN_CLOAK", "MOLTEN_BELT", "MOLTEN_BRACELET"]
for m in MOLTEN:
    # wave 5: tiered like every other (k/n) bonus here: 5% per piece, inside the Leviathan hunt (island kuudra). TUNE
    spec(m, [{"type": "set", "name": "Molten Core", "members": MOLTEN, "need": 4, "perPiece": True,
              "effects": [DT("all", 5, "island:kuudra")]}])
for p in ("PESTHUNTERS_NECKLACE", "PESTHUNTERS_CLOAK", "PESTHUNTERS_BELT", "PESTHUNTERS_GLOVES"):
    spec(p, notes=["&7Pests come back &a10% &7sooner."], hook="garden")
spec("PEST_VEST", notes=["&7Pests come back &a15% &7sooner."], hook="garden")
spec("SCOURGE_CLOAK", [{"type": "fire_taken", "pct": 10000, "carry": 20}])
spec("SCOVILLE_BELT", [{"type": "nearby_def", "per": 50, "cap": 1000, "range": 10, "mob": "burning"}])
for s in ("SHADOW_ASSASSIN_CLOAK", "STARRED_SHADOW_ASSASSIN_CLOAK"):
    spec(s, [{"type": "tp_strike", "window": 5, "pct": 10}])
# wave 5: the Magic Find half is per target now (core's Magic Find modifier), so it applies to arthropod drops only
spec("SHRIVELED_BRACELET", [{"type": "vs_stat", "mob": "arthropod", "stat": "COMBAT_WISDOM", "value": 5},
                            {"type": "mf_vs", "mob": "arthropod", "value": 10, "also": True}])
for s in ("SNOW_NECKLACE", "SNOW_CLOAK", "SNOW_BELT", "SNOW_GLOVES"):
    spec(s, notes=["&7Each piece improves your gift chance in the winter gift fight."], hook="events")
spec("SOULWEAVER_GLOVES", [{"type": "haunt_skull", "cd": 15, "window": 2, "pct": 5, "range": 2.5}])
for v in ("V1", "V2", "V3"):
    spec("SYNTHESIZER_" + v, notes=["&7Feed it materials to raise its stats."], hook="crimson")
spec("TERA_SHELL_NECKLACE", notes=["&cTrophy fish refuse to bite while you wear it."], hook="fishing")
spec("THE_PRIMORDIAL", notes=["&7Slayer bosses and minibosses have a &a10% &7chance to come back for a second kill."], hook="slayer")
spec("THUNDERBOLT_NECKLACE", [{"type": "static", "window": 30, "cap": 2, "pct": 15}],
     ["&8With the full Thunder armour set, the set's own Static Charge takes over."])
spec("ZORROS_CAPE", [{"type": "equip_mult", "mult": 2, "when": "event:farming_contest"}],
     ["&7You also have a &a20% &7chance to earn an extra medal from a Harvest Fair."], "farming")
spec("LUMINOUS_BRACELET", notes=["&7Filling someone ELSE's chum bucket pays you &6+20 coins &7and &3+10 &7Fishing XP."], hook="fishing")
spec("MANGROVE_VINE", notes=["&7Earns &a5% &7more contest points."], hook="foraging")
spec("TORRHUS_BELT", notes=["&7Earns &a10% &7more contest points."], hook="foraging")
spec("SPARKLING_AMULET", notes=["&7Sparkling critters show up twice as often."], hook="events")
for r in ("GOLDEN_BELT", "LEECH_BELT", "SILKRIDER_SAFETY_BELT", "VERMIN_BELT", "DISINFESTOR_GLOVES", "ENIGMA_CLOAK", "RIFT_NECKLACE_INSIDE", "SHENS_RINGALIA"):
    spec(r, notes=["&dA Rift piece: its bonuses only apply inside the Rift."], hook="rift")


# ---------------------------------------------------------------------------------------------
# build an ItemDef
# ---------------------------------------------------------------------------------------------
EQUIP_CATS = {"NECKLACE": "NECKLACE", "CLOAK": "CLOAK", "BELT": "BELT", "GLOVES": "GLOVES", "BRACELET": "GLOVES"}
RIFT_ONLY_EQUIP = {"GOLDEN_BELT", "LEECH_BELT", "SILKRIDER_SAFETY_BELT", "VERMIN_BELT", "DISINFESTOR_GLOVES",
                   "ENIGMA_CLOAK", "RIFT_NECKLACE_INSIDE", "SHENS_RINGALIA"}


def base_def(iid, it, category):
    n = neu(iid)
    d = {"id": iid, "name": our_name(iid, it["name"]), "material": material(it),
         "rarity": RARITY.get(it.get("tier", "COMMON"), "COMMON"), "category": category}
    skin = it.get("skin")
    if isinstance(skin, dict) and skin.get("value") and d["material"] == "PLAYER_HEAD":
        d["skull"] = skin["value"]
    if it.get("color"):
        d["color"] = "#%02X%02X%02X" % tuple(int(x) for x in it["color"].split(","))
    if it.get("glowing"):
        d["glow"] = True
    if it.get("npc_sell_price"):
        d["npcSell"] = it["npc_sell_price"]
    req = requirements(it)
    if req:
        d["requirements"] = req
    return d, n


def stats_for(iid, it, n, rift_only):
    lore_known, lore_other = parse_lore_stats(n.get("lore", []))
    stats, other, rift = {}, {}, {}
    for k, v in (it.get("stats") or {}).items():
        k = map_items_stat(k)
        if k.startswith("RIFT_"):
            rift[k] = v
        elif k in STAT_KEYS:
            stats[k] = v
        elif k != "DAMAGE":
            other[k] = v
    if lore_known or lore_other:
        # the NEU lore is the more complete source (it carries e.g. Sea Creature Chance that the API omits)
        stats = dict(lore_known)
        other = {}
        for k, v in lore_other.items():
            if k == "RIFT_TIME":
                rift["RIFT_TIME"] = v
            else:
                other[k] = v
    if rift_only:
        for k, v in stats.items():
            rift["RIFT_" + k] = v
        stats = {}
    return stats, other, rift


def accessory(iid, it):
    d, n = base_def(iid, it, "ACCESSORY")
    rift_only = iid in IGNORED
    stats, other, rift = stats_for(iid, it, n, rift_only)
    if iid == "MAGIC_8_BALL":
        stats = {}   # its lore shows the current season's roll; the roll itself is the magic8 effect
    if iid in RIFT_EXTRA:
        rift = dict(rift, **RIFT_EXTRA[iid])
    if stats:
        d["stats"] = stats
    sp = SPEC.get(iid, {"effects": [], "notes": [], "hook": None})
    tags = ["accessory"]
    if not it.get("cannot_reforge") and "CANNOT be reforged" not in " ".join(strip(x) for x in n.get("lore", [])):
        tags.append("reforgeable")
    if it.get("can_recombobulate") is not False:
        tags.append("recombable")
    if it.get("soulbound"):
        tags.append("soulbound")
    lore_text = " ".join(strip(x) for x in n.get("lore", []))
    dungeon = it.get("dungeon_item") or "DUNGEON ACCESSORY" in lore_text
    if dungeon:
        tags.append("dungeon_accessory")
    d["tags"] = tags
    extra = {}
    fam = FAM.get(iid)
    if fam:
        extra["family"], extra["familyRank"] = fam
    if rift_only:
        extra["noPower"] = True
    if iid == "HEGEMONY_ARTIFACT":
        extra["mpMult"] = 2
    if iid in GEM_POWER_MULT:
        extra["gemPowerMult"] = GEM_POWER_MULT[iid]
    if dungeon:
        extra["dungeon"] = True
    if other:
        extra["otherStats"] = other
    if rift:
        extra["riftStats"] = rift
    if sp["effects"]:
        extra["effects"] = sp["effects"]
    if sp["hook"]:
        extra["hook"] = sp["hook"]
    gs = it.get("gemstone_slots")
    if gs:
        d["gemSlots"] = [g.get("slot_type", "UNIVERSAL") for g in gs]
    d["extra"] = extra
    lore = desc_lines(sp["effects"], sp["notes"])
    if rift_only:
        lore += ["&dA Rift curio: it grants no Accessory Power", "&dand its stats only count in the Rift."]
    d["lore"] = lore
    return d


def equipment(iid, it):
    d, n = base_def(iid, it, EQUIP_CATS[it["category"]])
    rift_only = iid in RIFT_ONLY_EQUIP
    stats, other, rift = stats_for(iid, it, n, rift_only)
    if stats:
        d["stats"] = stats
    sp = SPEC.get(iid, {"effects": [], "notes": [], "hook": None})
    d["tags"] = ["equipment", "reforgeable", "recombable"] + (["soulbound"] if it.get("soulbound") else [])
    extra = {"slot": d["category"]}
    if other:
        extra["otherStats"] = other
    if rift:
        extra["riftStats"] = rift
    if sp["effects"]:
        extra["effects"] = sp["effects"]
    if sp["hook"]:
        extra["hook"] = sp["hook"]
    if it.get("dungeon_item"):
        extra["dungeon"] = True
    gs = it.get("gemstone_slots")
    if gs:
        d["gemSlots"] = [g.get("slot_type", "UNIVERSAL") for g in gs]
    d["extra"] = extra
    d["lore"] = desc_lines(sp["effects"], sp["notes"])
    return d


# ---------------------------------------------------------------------------------------------
# powers
# ---------------------------------------------------------------------------------------------
def mult(mp):
    return 29.97 * math.log(0.0019 * mp + 1) ** 1.2 if mp > 0 else 0.0


M250 = mult(250)
STONES = ["DISPLACED_LEECH", "ENDER_MONOCLE", "LUXURIOUS_SPOOL", "ROCK_CANDY", "ACACIA_BIRDHOUSE", "BEATING_HEART",
          "DARK_ORB", "END_STONE_SHULKER", "CHOCOLATE_CHIP", "FURBALL", "GLACITE_SHARD", "OBSIDIAN_TABLET",
          "SUNFLOWER_BUTTER", "BUBBA_BLISTER", "ECCENTRIC_PAINTING", "HORNS_OF_TORMENT", "MAGMA_URCHIN",
          "PRECIOUS_PEARL", "HAZMAT_ENDERMAN", "MANDRAA", "VITAMIN_DEATH", "SCORCHED_BOOKS"]
# research/01 section 20.3: starter powers, base values per 1.00 multiplier
STARTER = [
    ("fortuitous", "Fortuitous", 0, {"HEALTH": 3.35, "DEFENSE": 1.2, "STRENGTH": 4.8, "CRIT_CHANCE": 4.35, "CRIT_DAMAGE": 4.8}),
    ("pretty", "Pretty", 0, {"HEALTH": 1.65, "DEFENSE": 1.2, "SPEED": 0.65, "STRENGTH": 4.8, "INTELLIGENCE": 10.8, "CRIT_CHANCE": 0.475, "CRIT_DAMAGE": 1.2}),
    ("protected", "Protected", 0, {"HEALTH": 11.75, "DEFENSE": 10.8, "STRENGTH": 2.4, "CRIT_CHANCE": 0.475, "CRIT_DAMAGE": 1.2}),
    ("simple", "Simple", 0, {"HEALTH": 5.02, "DEFENSE": 3.6, "SPEED": 1.2, "STRENGTH": 3.6, "INTELLIGENCE": 5.4, "CRIT_CHANCE": 1.45, "CRIT_DAMAGE": 3.6}),
    ("warrior", "Warrior", 0, {"HEALTH": 3.35, "DEFENSE": 1.2, "STRENGTH": 8.4, "CRIT_CHANCE": 2.4, "CRIT_DAMAGE": 6}),
    ("commando", "Commando", 15, {"HEALTH": 5.02, "DEFENSE": 2.4, "STRENGTH": 8.4, "CRIT_CHANCE": 0.475, "CRIT_DAMAGE": 8.4}),
    ("disciplined", "Disciplined", 15, {"HEALTH": 5.02, "DEFENSE": 2.4, "STRENGTH": 7.2, "CRIT_CHANCE": 1.45, "CRIT_DAMAGE": 7.2}),
    ("inspired", "Inspired", 15, {"HEALTH": 1.65, "DEFENSE": 1.2, "STRENGTH": 4.8, "INTELLIGENCE": 16.2, "CRIT_CHANCE": 0.95, "CRIT_DAMAGE": 3.6}),
    ("ominous", "Ominous", 15, {"HEALTH": 5.02, "SPEED": 0.95, "STRENGTH": 3.6, "INTELLIGENCE": 6.1, "CRIT_CHANCE": 1.45, "CRIT_DAMAGE": 3.6, "ATTACK_SPEED": 0.9}),
    ("prepared", "Prepared", 15, {"HEALTH": 12.4, "DEFENSE": 11.3, "STRENGTH": 1.95, "CRIT_CHANCE": 0.4, "CRIT_DAMAGE": 0.95}),
]


POWER_RENAMES = {"Bubba": "Bloated"}   # the reference's power is named after its own NPC


def parse_power_lore(lore):
    """(powerName, stats@250, unique, combatReq) from a power stone's NEU lore."""
    name, at, uniq, req, mode = None, {}, {}, 0, None
    for raw in lore:
        line = strip(raw)
        m = re.match(r"^(\w[\w ]*) power\.$", line)
        if m:
            name = m.group(1)
        if line.startswith("At 250 Accessory Power"):
            mode = "at"
            continue
        if line.startswith("Unique Power Bonus"):
            mode = "uniq"
            continue
        m = re.search(r"Requires Combat Skill (\d+)", line)
        if m:
            req = int(m.group(1))
        m = re.match(r"^([+-][0-9.]+) ?(.+)$", line)
        if m and mode in ("at", "uniq"):
            st = LORE_NAME.get(m.group(2).strip())
            if st:
                (at if mode == "at" else uniq)[st] = float(m.group(1))
            continue
        if not line:
            mode = None if mode == "uniq" else mode
    return name, at, uniq, req


def build_powers():
    out = []
    for pid, name, req, base in STARTER:
        out.append({"id": pid, "name": name, "stone": None, "requirement": ("skill:combat:%d" % req) if req else None,
                    "starter": True, "base": base, "unique": {}})
    stones = []
    for sid in STONES:
        it = ITEMS[sid]
        n = neu(sid)
        pname, at, uniq, req = parse_power_lore(n.get("lore", []))
        if not pname or not at:
            raise SystemExit("power stone %s: could not parse lore" % sid)
        base = {k: round(v / M250, 4) for k, v in at.items()}
        shown = POWER_RENAMES.get(pname, pname)
        if shown != pname:
            RENAMES.append(("power: " + pname.lower(), pname, shown))
        out.append({"id": pname.lower(), "name": shown, "stone": sid, "requirement": "skill:combat:%d" % req,
                    "starter": False, "base": base, "unique": uniq, "at250": at})
        d, _ = base_def(sid, it, "POWER_STONE")
        d["tags"] = ["power_stone"]
        d["maxStack"] = 64
        if req:
            d["requirements"] = []   # the combat gate is on the POWER, checked when learning; keep the stone tradeable/usable
        lore = ["&7Bring &a9 &7of these to the &dThaumaturgist &7(or",
                "&7use &e/powers&7) to learn the &a%s &7power." % shown, "",
                "&7At &6250 Accessory Power&7:"]
        lore += [statline(k, v) for k, v in at.items()]
        if uniq:
            lore += ["", "&7Unique Power Bonus:"] + [statline(k, v) for k, v in uniq.items()]
        lore += ["", "&4❣ &cLearning it requires &aCombat Skill %d&c." % req, "", "&eRight-click to learn!"]
        d["lore"] = lore
        d["extra"] = {"power": pname.lower()}
        stones.append(d)
    return out, stones


# ---------------------------------------------------------------------------------------------
# enrichments
# ---------------------------------------------------------------------------------------------
def build_enrichments():
    out = []
    for iid, it in ITEMS.items():
        if not iid.startswith("TALISMAN_ENRICHMENT_"):
            continue
        n = neu(iid)
        d, _ = base_def(iid, it, "CONSUMABLE")
        d["maxStack"] = 64
        d["tags"] = ["enrichment"]
        if iid == "TALISMAN_ENRICHMENT_SWAPPER":
            d["name"] = "Enrichment Swapper"
            d["extra"] = {"swapper": True}
            d["lore"] = ["&7Changes the enrichment on every", "&7enriched accessory in your bag to", "&7a stat of your choice.", "", "&eRight-click to use!"]
            out.append(d)
            continue
        stat, val = None, None
        for raw in n.get("lore", []):
            m = re.search(r"power of ([+-][0-9.]+) ?([A-Za-z ]+?) when applied", strip(raw))
            if m:
                val, stat = float(m.group(1)), LORE_NAME.get(m.group(2).strip())
        if stat is None:
            # the sentence wraps over two lines in some files: search the joined text
            joined = " ".join(strip(x) for x in n.get("lore", []))
            m = re.search(r"power of ([+-][0-9.]+) ?([A-Za-z ]+?) when applied", joined)
            if m:
                val, stat = float(m.group(1)), LORE_NAME.get(m.group(2).strip())
        if stat is None:
            raise SystemExit("enrichment %s: no stat" % iid)
        d["name"] = "%s Enrichment" % LABEL.get(stat, stat)[2:].split(" ", 1)[1]
        d["extra"] = {"enrichStat": stat, "enrichValue": val}
        d["lore"] = ["&7Enriches a &6Legendary &7or better", "&7accessory with %s&7." % statline(stat, val), "",
                     "&7Only one enrichment fits on each", "&7accessory.", "", "&eRight-click to use!"]
        out.append(d)
    return out


# ---------------------------------------------------------------------------------------------
# recipes
# ---------------------------------------------------------------------------------------------
CELLS = ["A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3"]


def cell(c):
    if not c:
        return ""
    iid, _, amt = c.rpartition(":")
    iid = colon(iid)
    if iid == "SKYBLOCK_COIN":
        iid = "COINS"
    a = float(amt) if amt else 1
    return "%s:%s" % (iid, num(a))


def recipes_for(results):
    out = []
    for rid in sorted(results):
        n = neu(rid)
        if not n:
            continue
        unlock = unlock_of(n.get("crafttext", ""))
        raw = []
        if "recipe" in n:
            raw.append(dict(n["recipe"], type="crafting"))
        raw += n.get("recipes", [])
        for r in raw:
            t = r.get("type", "crafting")
            res = r.get("overrideOutputId", rid)
            if res != rid:
                continue
            if t == "crafting":
                grid = [cell(r.get(k, "")) for k in CELLS]
                if not any(grid):
                    continue
                out.append({"type": "CRAFTING", "result": rid, "amount": int(float(r.get("count", 1) or 1)),
                            "grid": grid, "unlock": unlock, "station": "Crafting Table"})
            elif t == "forge":
                out.append({"type": "FORGE", "result": rid, "amount": int(float(r.get("count", 1) or 1)),
                            "inputs": [cell(x) for x in r.get("inputs", [])], "durationSeconds": int(r.get("duration", 0)),
                            "unlock": unlock, "station": "Forge"})
    return out


# ---------------------------------------------------------------------------------------------
# NPC sell cap: a crafted item never sells to an NPC for more than its inputs sell for.
#
# The reference's npc_sell_price was set for a game whose inputs cost what the reference's economy
# says; ours come from other modules' data, and e.g. 20 Gazing Pearls (200 each) crafted a
# Delirium Necklace that sold for 52,000 -- a craft-and-sell coin printer
# (crafting.no-coin-loops). So: value every input at the npcSell the server will actually have
# (other modules' item data; our own items recursively, already capped; unknown ids and COINS
# count 0, which can only make the cap lower), take the CHEAPEST recipe for the item, and cap at
# SELL_CAP_FACTOR x that. The runtime re-applies the same rule in postEnable (SellCap.java) with
# the dial accessories.sell-cap-factor, so a later price change in another module cannot reopen it.
# ---------------------------------------------------------------------------------------------
SELL_CAP_FACTOR = 1.0
DATA = os.path.dirname(OUT)
CAPPED = []


def outside_prices():
    """id -> npcSell from every OTHER module's item data. Fallback copies (pets/fallback_items.json and
    the like) carry no price, so a priced entry wins over an unpriced one; two priced entries that
    disagree give the lower."""
    out = {}
    for mod in sorted(os.listdir(DATA)):
        p = os.path.join(DATA, mod)
        if mod == "accessories" or not os.path.isdir(p):
            continue
        for fn in sorted(os.listdir(p)):
            if not fn.endswith(".json"):
                continue
            try:
                with open(os.path.join(p, fn), encoding="utf-8") as f:
                    j = json.load(f)
            except (OSError, ValueError):
                continue
            if not isinstance(j, list):
                continue
            for o in j:
                if isinstance(o, dict) and "id" in o and "name" in o and "rarity" in o:
                    v = float(o.get("npcSell", 0) or 0)
                    have = out.get(o["id"], 0.0)
                    out[o["id"]] = v if have <= 0 else (have if v <= 0 else min(v, have))
    return out


def extra_recipes_for(results):
    """Other modules' copies of recipes for OUR results (crafting/skyblock_recipes.json mirrors them)."""
    p = os.path.join(DATA, "crafting", "skyblock_recipes.json")
    if not os.path.exists(p):
        return []
    with open(p, encoding="utf-8") as f:
        return [r for r in json.load(f) if isinstance(r, dict) and r.get("result") in results]


def recipe_inputs(r):
    tot = {}
    for c in (r.get("grid") or []) + (r.get("inputs") or []):
        if not c:
            continue
        iid, _, amt = c.rpartition(":")
        try:
            a = float(amt)
        except ValueError:
            iid, a = c, 1.0
        if not iid:
            iid, a = c, 1.0
        tot[iid] = tot.get(iid, 0.0) + a
    return tot


def cap_sell_prices(defs, recipes):
    by_id = {d["id"]: d for d in defs}
    outside = outside_prices()
    aliases = {}
    ap = os.path.join(DATA, "crafting", "neu_aliases.json")
    if os.path.exists(ap):
        with open(ap, encoding="utf-8") as f:
            aliases = json.load(f)
    by_result = {}
    for r in list(recipes) + extra_recipes_for(set(by_id)):
        by_result.setdefault(r["result"], []).append(r)
    done, busy = {}, set()

    def price(iid):
        if iid in by_id:
            return value(iid)
        if iid in outside:
            return outside[iid]
        a = aliases.get(iid)
        return outside.get(a, 0.0) if a else 0.0

    def value(iid):
        if iid in done:
            return done[iid]
        d = by_id[iid]
        ref = float(d.get("npcSell", 0) or 0)
        if iid in busy or ref <= 0:       # a cycle back to itself: its own price, never higher
            return ref
        busy.add(iid)
        cheapest = None
        for r in by_result.get(iid, []):
            cost = sum(price(i) * a for i, a in recipe_inputs(r).items()) / max(1, int(r.get("amount", 1) or 1))
            cheapest = cost if cheapest is None else min(cheapest, cost)
        busy.discard(iid)
        v = ref
        if cheapest is not None and ref > cheapest * SELL_CAP_FACTOR:
            v = math.floor(cheapest * SELL_CAP_FACTOR)
            CAPPED.append((iid, ref, v))
            if v > 0:
                d["npcSell"] = v
            else:
                d.pop("npcSell", None)
        done[iid] = v
        return v

    for iid in sorted(by_id):
        value(iid)


# ---------------------------------------------------------------------------------------------
def main():
    acc, eq = [], []
    for iid in sorted(ITEMS):
        it = ITEMS[iid]
        c = it.get("category")
        if c == "ACCESSORY":
            acc.append(accessory(iid, it))
        elif c in EQUIP_CATS:
            eq.append(equipment(iid, it))
    powers, stones = build_powers()
    enrich = build_enrichments()
    results = {d["id"] for d in acc + eq + stones}
    recipes = recipes_for(results)
    cap_sell_prices(acc + eq + stones, recipes)

    missing_spec = [k for k in SPEC if k not in results]
    if missing_spec:
        print("WARNING spec ids not generated:", missing_spec)
    os.makedirs(OUT, exist_ok=True)

    def dump(name, obj):
        with open(os.path.join(OUT, name), "w", encoding="utf-8") as f:
            json.dump(obj, f, ensure_ascii=True, indent=1)
            f.write("\n")

    dump("items.json", acc + stones + enrich)
    dump("equipment.json", eq)
    dump("recipes.json", recipes)
    dump("powers.json", powers)

    # rename table
    for p in powers:
        pass
    os.makedirs(os.path.dirname(NAMES_MD), exist_ok=True)
    with open(NAMES_MD, "w", encoding="utf-8") as f:
        f.write("# accessories - renames\n\n")
        f.write("Generated by `tools/gen/accessories/gen_accessories.py` (item names) plus the hand-written\n")
        f.write("rows at the bottom (NPCs, menus). Ids never change; only what players read.\n\n")
        f.write("| id | reference name | our name |\n|---|---|---|\n")
        for iid, ref, ours in sorted(set(RENAMES)):
            f.write("| %s | %s | %s |\n" % (iid, ref, ours))
        f.write("| npc: thaumaturgist | Maxwell (the Thaumaturgist) | Thaumaturgist Ophira |\n")
        f.write("| npc/menu: bag upgrades | Jacobus | Bagwright Jory (Bag Upgrades menu) |\n")
        f.write("| text: Jerry's Workshop | Jerry's Workshop | the Winter Workshop (island id unchanged) |\n")
        f.write("| text: Kuudra | Kuudra | the Magma Leviathan (one name with crimson, kuudra and progression; \"Magmaw\" is the Crystal Hollows boss and pet, see docs/names/kuudra.md) |\n")
        f.write("| text: Erihann (Rift Prism) | Erihann | the Thaumaturgist consumes it |\n")
        f.write("| text: Elizabeth (account upgrades) | Elizabeth / Community Center | SkyBlock level milestones |\n")
        # wave 5 rows (hand-added to the doc by the wave-5 agents; kept here so a regen keeps them)
        f.write("| text (wave 5): Abiphone contacts (Abicase) | Abiphone | Ringphone (extras' name, docs/names/extras.md) |\n")
        f.write("| text (wave 5): Soulflow (soulflow items, lore) | Soulflow | Soulstream (soulflow's name; also in the item names via WORD_RENAMES) |\n")
        f.write("| text (wave 5): Jacob's Farming Contest (Anita talismans, Zorro's Cape) | Jacob's Farming Contest | Harvest Fair (farming's name) |\n")
        f.write("| text (wave 5): Diana's Mythological Ritual (Mythos / Challenger pieces) | Mythological Ritual | the Mythic Ritual (armor's name) |\n")
        f.write("| text (wave 5): Glacite Mineshaft (Divan Pendant) | Glacite Mineshaft | Rimestone Mineshaft (mining's island name) |\n")
        f.write("| text (wave 5): Zorro (Zorro's Cape requirement) | Zorro | Masked Hopper (chocolate's rabbit name; id `zorro`) |\n")
        f.write("| text (wave 5): Frozen Corpse (Organ Donor) | Frozen Corpse | Frozen Corpse (generic words, kept; mining uses them too) |\n")
        f.write("\nAll item DESCRIPTIONS are rewritten in our own words by the generator (effect templates and the\n")
        f.write("`SPEC` notes); none of the reference lore text is shipped. Power names (Fortuitous, Silky, ...) are\n")
        f.write("ordinary English adjectives and are kept.\n")

    print("accessories %d, equipment %d, power stones %d, enrichments %d, recipes %d, powers %d, renames %d"
          % (len(acc), len(eq), len(stones), len(enrich), len(recipes), len(powers), len(set(RENAMES))))
    if CAPPED:
        print("npcSell capped to the cheapest recipe's inputs (x%g): %d items" % (SELL_CAP_FACTOR, len(CAPPED)))
        for iid, ref, v in sorted(CAPPED):
            print("  %-34s %12s -> %s" % (iid, "{:,.0f}".format(ref), "{:,.0f}".format(v)))
    if UNRESOLVED_UNLOCKS:
        print("unresolved unlock texts (recipe left always-unlocked):", sorted(UNRESOLVED_UNLOCKS))


if __name__ == "__main__":
    main()
