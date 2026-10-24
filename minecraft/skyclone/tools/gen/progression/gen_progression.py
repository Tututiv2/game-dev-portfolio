#!/usr/bin/env python3
"""
Generates every data file of the `progression` module from research/data (+ a few design tables
that live only in this script because no data file has them: the objective chain, renames).

    python tools/gen/progression/gen_progression.py

Outputs (all under plugin/src/main/resources/data/progression/):
    bestiary.json    bestiary categories -> families (bracket, kill cap, max tier, skull, match keys)
    museum.json      museum categories -> donatable entries (single items and armour sets), XP, stage, child links
    levels.json      SkyBlock Level: level rewards, emblems, task categories (+ the research maxima)
    objectives.json  the guided new-player objective chain (design data, see research 03 section 2)
    items.json       Century Cakes (consumable stat buffs, research 08 section 6.7)
    misc.json        fairy soul skull texture, fairy soul totals by island (reference)
and docs/names/progression.md (every rename made in this module).

Every number comes from research/data (NEU-REPO constants) or the research markdown; anything else
is marked TUNE in the output's "_note" fields.
"""
import base64
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
DATA = os.path.join(ROOT, "research", "data")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "progression")
NAMES = os.path.join(ROOT, "docs", "names", "progression.md")


def L(*p):
    with open(os.path.join(DATA, *p), encoding="utf-8") as f:
        return json.load(f)


def strip(s):
    return re.sub(r"§.", "", s or "")


def snake(s):
    s = strip(s).lower().replace("'", "")
    s = re.sub(r"[^a-z0-9]+", "_", s)
    return s.strip("_")


def write(name, obj):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, indent=1, ensure_ascii=True)
        f.write("\n")
    print("wrote", os.path.relpath(path, ROOT), os.path.getsize(path), "bytes")


RENAMES = []   # (kind, id, reference, ours)


def rn(kind, ident, ref, ours):
    if ref != ours:
        RENAMES.append((kind, ident, ref, ours))
    return ours


# ---------------------------------------------------------------------------------------------
# Bestiary
# ---------------------------------------------------------------------------------------------

# NEU category id -> (our display name, islands where these mobs live, icon material fallback)
BESTIARY_CATEGORIES = [
    ("dynamic", "Private Island", ["private_island"]),
    ("hub", "Hub", ["hub"]),
    ("farming_1", "The Farming Islands", ["barn", "mushroom_desert"]),
    ("combat_1", "Spider's Den", ["spiders_den"]),
    ("combat_3", "The End", ["the_end"]),
    ("crimson_isle", "Crimson Isle", ["crimson_isle"]),
    ("mining_2", "Deep Caverns", ["deep_caverns", "gold_mine"]),
    ("mining_3", "Dwarven Mines", ["dwarven_mines", "glacite_tunnels"]),
    ("crystal_hollows", "Crystal Caverns", ["crystal_hollows"]),
    ("foraging_1", "The Park", ["park"]),
    ("spooky_festival", "Spooky Festival", []),
    ("mythological_creatures", "Mythological Creatures", ["hub"]),
    ("jerry", "Jerry", ["jerrys_workshop"]),
    ("kuudra", "Kuudra", ["kuudra"]),
    ("fishing/fishing", "Fishing", []),
    ("fishing/lava", "Lava Fishing", ["crimson_isle"]),
    ("fishing/spooky_festival", "Spooky Fishing", []),
    ("fishing/fishing_festival", "Fishing Festival", []),
    ("fishing/winter", "Winter Fishing", ["jerrys_workshop"]),
    ("fishing/backwater_bayou", "Backwater Bayou", ["backwater_bayou"]),
    ("catacombs", "Catacombs", ["catacombs", "dungeon_hub"]),
    ("garden", "Garden", ["garden"]),
]
# Galatea (wave 5): the foraging module now builds it as island "galatea" ("The Moonmarsh"). Its NEU
# categories hold ~120 invented-name families and this game registers only a handful of their mobs, so a
# Galatea family is curated ONLY when some module's mobs.json carries its family id / name (the rest would be
# renames of things nobody can meet). Anything else a module registers there still gets an auto-family.
GALATEA_CATEGORIES = [
    ("foraging_2", "Moonmarsh", ["galatea"]),
    ("foraging_3", "Torrent Canyon", ["galatea"]),
    ("lotus_atoll", "Lotus Lagoon", ["galatea", "backwater_bayou"]),
]
# safari (Critter Safari, four biome sub-categories) is the Hunting skill's; left to the hunting module's mobs +
# the auto-family fallback.

# Categories whose tiers pay a gathering Fortune instead of damage/Magic Find (research 08 6.1: "Farming/Hunting
# families give Farming Fortune / Hunting Fortune instead"; Pest bestiary +0.4 FF a tier, 102 FF at max).
CATEGORY_FORTUNE = {"garden": "FARMING_FORTUNE"}

CATEGORY_RENAMES = {"Jerry": "Festive Imps", "Kuudra": "Leviathan's Lair"}

FAMILY_RENAMES = {
    "Arachne": "Silkmother", "Arachne's Brood": "Silkmother's Brood", "Arachne's Keeper": "Silkmother's Keeper",
    "Voidling Extremist": "Voidspawn Extremist", "Voidling Fanatic": "Voidspawn Fanatic",
    "Ashfang": "Cinderfang", "Barbarian Duke X": "Warlord Duke", "Bladesoul": "Edgewraith",
    "Kada Knight": "Lava Knight", "Mage Outlaw": "Rogue Magus", "Matcho": "Matchstick",
    "Glacite Bowman": "Rimestone Bowman", "Glacite Caver": "Rimestone Caver", "Glacite Mage": "Rimestone Mage",
    "Glacite Mutt": "Rimestone Mutt", "Glacite Walker": "Rimestone Walker", "Littlefoot": "Little Wanderer",
    "Bal": "Magmaw", "Boss Corleone": "Boss Vitello", "Thyst": "Shardling", "Yog": "Cinderling",
    "Scary Jerry": "Scary Imp",
    "Blue Jerry": "Blue Imp", "Golden Jerry": "Golden Imp", "Green Jerry": "Green Imp", "Purple Jerry": "Purple Imp",
    "Kuudra Berserker": "Leviathan Berserker", "Kuudra Follower": "Leviathan Follower",
    "Kuudra Knocker": "Leviathan Knocker", "Kuudra Landmine": "Leviathan Landmine", "Kuudra Slasher": "Leviathan Slasher",
    "Agarimoo": "Mooshroom Diver", "Mithril Grubber": "Ore Grubber", "Inkling": "Inkspawn", "Wiki Tiki": "Tiki Totem",
    "Lord Jawbus": "Lord Gnashmaw", "Moogma": "Magma Cow", "Plhlegblast": "Phlegmburst",
    "Frozen Steve": "Frozen Wanderer", "Grinch": "Gift Goblin", "Reindrake": "Frost Drake",
    "Fels": "Tendril Fiend", "Skeletor": "Skeletal Overseer", "Zombuddy": "Zombie Pal",
    # Galatea families that exist in this game (foraging's marsh mobs; their own renames)
    # (sea creatures keep the fishing module's names: docs/names/fishing.md)
    "Tidetot": "Silt Wretch", "Chill": "Frostkin", "Nessie": "Loch Wyrm", "Stridersurfer": "Lava Strider",
    "gorF": "Grof", "The Loch Emperor": "Loch Sovereign",
}


def registered_families():
    """Every family id / name / id-stem a module's mobs.json declares (used to curate Galatea families)."""
    keys = set()
    base = os.path.join(ROOT, "plugin", "src", "main", "resources", "data")
    for mod in sorted(os.listdir(base)):
        p = os.path.join(base, mod, "mobs.json")
        if not os.path.isfile(p):
            continue
        try:
            with open(p, encoding="utf-8") as f:
                d = json.load(f)
        except Exception:
            continue
        if isinstance(d, dict):
            d = d.get("mobs", [])
        for m in d:
            # Hunting critters (critter_*) are the hunting module's shard creatures: the runtime files them under its
            # own Critter Safari category (Hunting Fortune), so they never pull a curated combat family in here
            if str(m.get("id", "")).startswith("critter_"):
                continue
            for v in (m.get("family"), m.get("name"), m.get("id")):
                if v:
                    keys.add(snake(v))
    return keys


def gen_bestiary():
    b = L("neu-repo", "constants", "bestiary.json")
    brackets = {int(k): v for k, v in b["brackets"].items()}
    cats = []
    seen_ids = set()
    ours_reg = registered_families()
    plan = [(k, n, i, False) for k, n, i in BESTIARY_CATEGORIES] + [(k, n, i, True) for k, n, i in GALATEA_CATEGORIES]
    for key, name, islands, only_registered in plan:
        if "/" in key:
            a, c = key.split("/")
            src = b[a][c]
        else:
            src = b[key]
        cname = rn("bestiary category", key, name, CATEGORY_RENAMES.get(name, name))
        fams = []
        for m in src["mobs"]:
            ref = strip(m["name"])
            fid = snake(ref)
            if only_registered:
                stems = {fid} | {snake(re.sub(r"_\d+$", "", x)) for x in m.get("mobs", [])}
                if not (stems & ours_reg):
                    continue
            # the same name in two categories (Bat, Enderman...) is two families: suffix the later one
            uid = fid if fid not in seen_ids else fid + "_" + snake(key.split("/")[-1])
            seen_ids.add(uid)
            ours = rn("bestiary family", uid, ref, FAMILY_RENAMES.get(ref, ref))
            br = int(m["bracket"])
            cap = int(m["cap"])
            th = brackets[br]
            max_tier = sum(1 for t in th if t <= cap)
            if max_tier == 0:
                max_tier = 1
            fams.append({
                "id": uid,
                "name": ours,
                "matchNames": sorted({snake(ref), snake(ours), fid}),
                "neuMobs": m.get("mobs", []),
                "texture": m.get("texture", ""),
                "bracket": br,
                "cap": cap,
                "maxTier": max_tier,
            })
        icon = src.get("icon", {}) if isinstance(src.get("icon"), dict) else {}
        if not fams:
            continue
        cat = {
            "id": key.replace("/", "_"),
            "name": cname,
            "islands": islands,
            "texture": icon.get("texture", ""),
            "families": fams,
        }
        if key in CATEGORY_FORTUNE:
            cat["fortune"] = CATEGORY_FORTUNE[key]
        cats.append(cat)
    total_fams = sum(len(c["families"]) for c in cats)
    write("bestiary.json", {
        "_note": "Generated from research/data/neu-repo/constants/bestiary.json by tools/gen/progression. "
                 "brackets[k] = cumulative kills for tiers 1..25 of a bracket-k family; a family stops at "
                 "its kill cap (maxTier = thresholds <= cap).",
        "brackets": {str(k): v for k, v in brackets.items()},
        "familyXp": b.get("familyXp", 1),
        "categories": cats,
    })
    return total_fams


# ---------------------------------------------------------------------------------------------
# Museum
# ---------------------------------------------------------------------------------------------

MUSEUM_CATS = [("combat", "Weapons", "DIAMOND_SWORD"), ("armor_sets", "Armor Sets", "DIAMOND_CHESTPLATE"),
               ("farming", "Farming", "GOLDEN_HOE"), ("mining", "Mining", "IRON_PICKAXE"),
               ("fishing", "Fishing", "FISHING_ROD"), ("foraging", "Foraging", "JUNGLE_SAPLING"),
               ("dungeoneering", "Dungeoneering", "SKELETON_SKULL"), ("hunting", "Hunting", "LEAD"),
               ("special", "Special Items", "CAKE")]


def gen_museum():
    m = L("neu-repo", "constants", "museum.json")
    items = {i["id"]: i for i in L("items.json")["items"]}
    sets = m["sets_to_items"]
    xp = m["itemToXp"]
    stage = m["itemToStage"]
    children = m["children"]          # parent -> child (lower tier it also counts for)
    entries = {c[0]: [] for c in MUSEUM_CATS}
    for cat, ids in m["items"].items():
        for eid in ids:
            is_set = eid in sets
            target = "armor_sets" if (is_set and cat != "special") else cat
            if target not in entries:
                continue
            e = {
                "id": eid,
                "xp": 0 if cat == "special" else int(xp.get(eid, 1)),
                "stage": stage.get(eid, "STARTER"),
                "skill": cat,
            }
            if is_set:
                e["pieces"] = sets[eid]
            if eid in children:
                e["child"] = children[eid]
            entries[target].append(e)
    # A child link must point at an entry: map a bare set piece to its set, drop anything else.
    all_ids = {e["id"] for v in entries.values() for e in v}
    piece_to_set = {p: s for s, ps in sets.items() for p in ps}
    dropped = 0
    for v in entries.values():
        for e in v:
            c = e.get("child")
            if c is None or c in all_ids:
                continue
            if piece_to_set.get(c) in all_ids:
                e["child"] = piece_to_set[c]
            else:
                del e["child"]
                dropped += 1
    print("museum: dropped", dropped, "child links to non-entries")
    out = []
    for cid, name, icon in MUSEUM_CATS:
        out.append({"id": cid, "name": name, "icon": icon, "entries": entries[cid]})
    write("museum.json", {
        "_note": "Generated from research/data/neu-repo/constants/museum.json. xp = SkyBlock XP for the first "
                 "donation (research 08 section 6.2, +1..+25); 'child' = the lower-tier entry a donation of this "
                 "one also counts for; Special Items give no XP. Entries whose item ids are not registered in "
                 "game are hidden at runtime.",
        "stageOrder": ["STARTER", "AMATEUR", "INTERMEDIATE", "SKILLED", "EXPERT", "PROFESSIONAL", "MASTER"],
        "categories": out,
    })
    return sum(len(v) for v in entries.values())


# ---------------------------------------------------------------------------------------------
# SkyBlock Level
# ---------------------------------------------------------------------------------------------

def gen_levels():
    s = L("neu-repo", "constants", "sblevels.json")
    cx = s["category_xp"]
    # Level rewards, research 02 section 6.2 (Book of Progression renamed; features generic).
    rewards = [
        {"level": 3, "text": "Access to the Community Shop"},
        # "dial": the feature's own gate dial; the game lists the reward at the level that dial asks for
        # (level here is only the fallback). Bazaar: reference Level 7, the owner chose 5 on 2026-10-04.
        {"level": 5, "text": "Access to the Garden", "dial": "garden.requirement"},
        {"level": 5, "text": "Access to the Wardrobe"},
        {"level": 6, "text": "Auto-pickup of block and mob drops"},
        {"level": 5, "text": "Access to the Bazaar", "dial": "bazaar.access-requirement"},
        {"level": 12, "text": "Access to the Wizard Tower portal"},
        {"level": 25, "text": "Tome of Progress accessory"},
        {"level": 25, "text": "Stacked Healing: bonus Vitality"},
        {"level": 50, "text": "Defensive Strongarm: bonus Defense"},
        {"level": 50, "text": "Daily coin trading limit raised"},
        {"level": 75, "text": "Tome of Progress upgrades to Uncommon"},
        {"level": 75, "text": "Extra Infliction: more damage to mobs"},
        {"level": 100, "text": "Daily coin trading limit raised again"},
        {"level": 150, "text": "Tome of Progress upgrades to Rare"},
        {"level": 225, "text": "Tome of Progress upgrades to Epic"},
        {"level": 300, "text": "Tome of Progress upgrades to Legendary"},
        {"level": 400, "text": "Tome of Progress upgrades to Mythic"},
    ]
    rn("level reward", "BOOK_OF_PROGRESSION", "Book of Progression", "Tome of Progress")
    emblems = [("diamond", "Diamond", "♦", 10), ("spade", "Spade", "♠", 20), ("heart", "Heart", "❤", 30),
               ("pristine", "Pristine", "✧", 40), ("arc_reactor", "Arc Reactor", "⎊", 50),
               ("marker", "Marker", "፠", 60), ("badge", "Badge", "☬", 70), ("star", "Star", "⚝", 80),
               ("boxes", "Boxes", "⧉", 90), ("imp", "Imp", "ꈔ", 100), ("globe", "Globe", "㋖", 120),
               ("soulstream", "Soulstream", "⸎", 140), ("warning", "Warning", "⚠", 160),
               ("mustache", "Mustache", "ꕁ", 180), ("helmet", "Helmet", "〠", 200),
               ("smiley", "Sideways Smiley", "ツ", 250), ("spaceship", "Spaceship", "⥈", 300),
               ("toxic", "Toxic", "☢", 350), ("biohazard", "Biohazard", "☣", 400),
               ("florette", "Florette", "✾", 450), ("fleur", "Golden Fleur De Lis", "⚜", 500)]
    rn("emblem", "imp", "Jerry", "Imp")
    rn("emblem", "soulstream", "Soulflow", "Soulstream")
    # Task categories: research 02 section 6.3 + sblevels.json category_xp. Each task matches award
    # keys by their first ':' segment (prefixes) - see docs/handoff/progression.md for the convention.
    categories = [
        {"id": "core", "name": "Core Tasks", "icon": "NETHER_STAR", "max": cx["core_task"], "tasks": [
            {"name": "Skill Level Ups", "prefixes": ["skill", "skills"], "max": s["core_task"]["skill_level_up"]},
            {"name": "Museum Progression", "prefixes": ["museum"], "max": 3522},
            {"name": "Fairy Souls", "prefixes": ["fairy"], "max": 530},
            {"name": "Accessory Bag", "prefixes": ["accessory", "accessories", "mp", "magical_power"], "max": 2007},
            {"name": "Pet Score", "prefixes": ["pet", "pets", "pet_score"], "max": 1518},
            {"name": "Collections", "prefixes": ["collection", "collections"], "max": 3160},
            {"name": "Craft Minions", "prefixes": ["minion", "minions"], "max": 2963},
            {"name": "Bank Upgrades", "prefixes": ["bank"], "max": s["core_task"]["bank_upgrades"]},
            {"name": "Fast Travel Unlocked", "prefixes": ["travel", "warp", "scroll"], "max": 300},
        ]},
        {"id": "event", "name": "Event Tasks", "icon": "CLOCK", "max": cx["event_task"], "tasks": [
            {"name": "Mining Fiesta", "prefixes": ["fiesta", "mining_fiesta"], "max": s["event_task"]["mining_fiesta"]},
            {"name": "Fishing Festival", "prefixes": ["fishing_festival", "festival"], "max": s["event_task"]["fishing_festival"]},
            {"name": "Spooky Festival", "prefixes": ["spooky", "spooky_festival"], "max": s["event_task"]["spooky_festival"]},
            {"name": "Farming Contests", "prefixes": ["contest", "jacob", "farming_contest"], "max": 130},
            {"name": "Other Events", "prefixes": ["event"], "max": 0},
        ]},
        {"id": "dungeon", "name": "Dungeon Tasks", "icon": "WITHER_SKELETON_SKULL", "max": cx["dungeon_task"], "tasks": [
            {"name": "Catacombs Levels", "prefixes": ["catacombs"], "max": s["dungeon_task"]["catacombs_level_up"]},
            {"name": "Class Levels", "prefixes": ["class"], "max": s["dungeon_task"]["class_level_up"]},
            {"name": "Floor Completions", "prefixes": ["dungeon", "floor"], "max": s["dungeon_task"]["complete_dungeon"]},
        ]},
        {"id": "essence", "name": "Essence Shop Tasks", "icon": "GHAST_TEAR", "max": cx["essence_shop_task"], "tasks": [
            {"name": "Essence Shop Perks", "prefixes": ["essence"], "max": cx["essence_shop_task"]},
        ]},
        {"id": "slaying", "name": "Slaying Tasks", "icon": "GOLDEN_SWORD", "max": cx["slaying_task"], "tasks": [
            {"name": "Slayer Levels", "prefixes": ["slayer"], "max": s["slaying_task"]["slayer_level_up"]},
            {"name": "Boss Collections", "prefixes": ["boss_collection", "boss"], "max": s["slaying_task"]["boss_collections"]},
            {"name": "Bestiary", "prefixes": ["bestiary"], "max": s["slaying_task"]["bestiary_progress"]},
            {"name": "Dragons Slain", "prefixes": ["dragon"], "max": s["slaying_task"]["slay_dragons"]},
            {"name": "Slayer Bosses Defeated", "prefixes": ["slayer_boss", "defeat_slayer"], "max": s["slaying_task"]["defeat_slayers"]},
            {"name": "Leviathan Tiers", "prefixes": ["kuudra"], "max": s["slaying_task"]["defeat_kuudra"]},
            {"name": "Silkmother", "prefixes": ["arachne"], "max": s["slaying_task"]["defeat_arachne"]},
            {"name": "Mythological Creatures", "prefixes": ["mythological", "diana"], "max": s["slaying_task"]["mythological_kills"]},
        ]},
        {"id": "skill_related", "name": "Skill Related Tasks", "icon": "DIAMOND_PICKAXE", "max": cx["skill_related_task"], "tasks": [
            {"name": "Heart of the Mountain", "prefixes": ["hotm", "potm", "powder"], "max": s["skill_related_task"]["mining"]["hotm"] + 1000},
            {"name": "Commission Milestones", "prefixes": ["commission", "commissions"], "max": 255},
            {"name": "Crystal Nucleus", "prefixes": ["nucleus", "crystal_nucleus"], "max": 200},
            {"name": "Garden", "prefixes": ["garden", "visitor", "visitors", "crop", "plot", "composter", "greenhouse"], "max": 1500},
            {"name": "Trophy Fish", "prefixes": ["trophy", "trophy_fish"], "max": s["skill_related_task"]["fishing"]["trophy_fish"]},
            {"name": "Heart of the Forest", "prefixes": ["hotf"], "max": 800},
            {"name": "Other Skill Tasks", "prefixes": ["mining", "farming", "fishing", "foraging", "anita", "dolphin", "rock"], "max": 0},
        ]},
        {"id": "misc", "name": "Miscellaneous Tasks", "icon": "BOOK", "max": cx["miscellaneous_task"], "tasks": [
            {"name": "Accessory Bag Upgrades", "prefixes": ["bag_upgrade", "accessory_bag"], "max": 198},
            {"name": "Community Shop Upgrades", "prefixes": ["community", "community_shop"], "max": 120},
            {"name": "Personal Bank", "prefixes": ["personal_bank"], "max": 110},
            {"name": "Consumables", "prefixes": ["consumable", "cake"], "max": 135},
            {"name": "Faction Reputation", "prefixes": ["faction", "reputation", "crimson"], "max": 150},
            {"name": "Everything Else", "prefixes": [], "max": 0},
        ]},
        {"id": "story", "name": "Story Tasks", "icon": "WRITABLE_BOOK", "max": cx["story_task"], "tasks": [
            {"name": "Objectives", "prefixes": ["objective", "quest", "hub_guide", "island_guide"], "max": 0},
            {"name": "Rift Guide", "prefixes": ["rift"], "max": s["story_task"]["rift_guide"]},
        ]},
    ]
    write("levels.json", {
        "_note": "SkyBlock Level data. 100 SB XP per level, +5 Health per level, +1 Strength per 5 levels "
                 "(research 02 section 6.1). Colours change every 40 levels. Task maxima from "
                 "neu-repo/constants/sblevels.json and research 02 section 6.3; a max of 0 = no cap shown. "
                 "'objectives' max is filled at runtime from the chain length.",
        "xpPerLevel": 100,
        "healthPerLevel": 5,
        "strengthEveryLevels": 5,
        "rewards": rewards,
        "emblems": [{"id": e[0], "name": e[1], "symbol": e[2], "level": e[3]} for e in emblems],
        "categories": categories,
        "objectiveXp": s["story_task"]["complete_objectives_xp"],
        "fairyExchangeXp": s["core_task"]["fairy_souls_xp"],
        "bestiaryFamilyXp": s["slaying_task"]["bestiary_family_xp"],
        "bestiaryMilestoneXp": s["slaying_task"]["bestiary_milestone_xp"],
    })


# ---------------------------------------------------------------------------------------------
# Objectives (design data; research 03 section 2.1/2.2 + sblevels.json story objective names)
# ---------------------------------------------------------------------------------------------

def O(id, text, trigger, hint, coins=0, skippable=False):
    return {"id": id, "text": text, "trigger": trigger, "hint": hint, "coins": coins, "skippable": skippable}


MERCHANTS = ["npc.farm_merchant", "npc.mine_merchant", "npc.lumber_merchant", "npc.fish_merchant",
             "npc.weaponsmith", "npc.armorsmith", "npc.adventurer", "npc.builder", "npc.bartender"]
LOGS = ["OAK_LOG", "BIRCH_LOG", "SPRUCE_LOG", "JUNGLE_LOG", "ACACIA_LOG", "DARK_OAK_LOG", "MANGROVE_LOG",
        "CHERRY_LOG", "PALE_OAK_LOG"]


def gen_objectives():
    chain = [
        O("talk_island_helper", "Talk to your Island Helper",
          {"type": "npc", "anchors": ["npc.island_helper"]},
          "Your helper stands right next to where you woke up. Right-click them.", 0, True),
        O("break_log", "Break a Log",
          {"type": "break", "materials": LOGS, "gather": "FORAGING", "amount": 1},
          "Punch the tree on your island until a log drops.", 10),
        O("craft_workbench", "Craft a Workbench",
          {"type": "have", "materials": ["CRAFTING_TABLE"]},
          "Open your inventory crafting grid and turn planks into a crafting table.", 10),
        O("craft_wood_pickaxe", "Craft a Wood Pickaxe",
          {"type": "have", "materials": ["WOODEN_PICKAXE"]},
          "Turn planks into sticks, then craft a Wooden Pickaxe (3 planks over 2 sticks).", 10),
        O("mine_cobblestone", "Mine Cobblestone",
          {"type": "break", "materials": ["COBBLESTONE", "STONE"], "gather": "MINING", "what": ["COBBLE", "STONE"], "amount": 5},
          "Mine 5 cobblestone with a pickaxe: the rocky quarry mound south of the minion terrace grows its stone back.", 25),
        O("open_sbmenu", "Open the SkyBlock Menu",
          {"type": "menu", "title": ["skyblock menu"], "commands": ["sbmenu", "skyblockmenu", "menu"]},
          "Right-click the Nether Star in your last hotbar slot.", 25),
        O("place_minion", "Place a Minion",
          {"type": "use_category", "category": "MINION", "island": "private_island"},
          "Right-click a minion onto your island. It works while you are away.", 50, True),
        O("go_to_hub", "Go to the Hub",
          {"type": "island", "island": "hub"},
          "Walk into the portal on your island, or type /hub.", 25),
        O("talk_banker", "Talk to the Banker",
          {"type": "npc", "anchors": ["npc.banker", "npc.banker_2"]},
          "The Bank faces the Village square from the north (/village points your compass at its door). Coins in the bank are safe when you die.", 50),
        O("deposit_coins", "Deposit coins in the Bank",
          {"type": "bank"},
          "Open the bank and deposit some of your purse. Banked coins earn interest.", 50, True),
        O("talk_merchant", "Talk to a Merchant",
          {"type": "npc", "anchors": MERCHANTS},
          "Merchants around the Hub buy and sell the basics: the Builder, Weaponsmith, Armorsmith and Adventurer are round the Village square (/village).", 25),
        O("visit_bazaar", "Visit the Bazaar",
          # completes at the bazaar itself (its NPC or standing in its zone), never from a menu or /bz opened
          # anywhere else: the SkyBlock Menu and a Booster Cookie open the same menu without a visit
          {"type": "npc", "anchors": ["npc.bazaar"], "island": "hub", "zones": ["bazaar"]},
          "The Bazaar trades materials between every player at live prices. Trading there needs a higher SkyBlock Level, but walking in already counts - /sblevel shows how to earn SkyBlock XP.", 50),
        O("visit_auction_house", "Visit the Auction House",
          # same rule: the Auction House NPCs or the auction_house zone, never the menu (SkyBlock Menu slot 43) or /ah
          {"type": "npc", "anchors": ["npc.auction_master", "npc.auction_agent_1", "npc.auction_agent_2"],
           "island": "hub", "zones": ["auction_house"]},
          "Gear and rare items are bought and sold at the Auction House.", 50),
        O("talk_fairy", "Talk to the Fairy",
          {"type": "npc", "anchors": ["npc.fairy"]},
          "Wren the Fairy stands on the north-east bank of the Fairy Pond, south-west of the Village square (/village points your compass there). She trades for the souls of her kin.", 25),
        O("find_fairy_soul", "Find a Fairy Soul",
          {"type": "fairy_found", "amount": 1},
          "Fairy Souls hide all over every island: look for a floating pink head and click it to collect it.", 50),
        O("explore_farm", "Explore the Farm",
          {"type": "area", "island": "hub", "areas": ["farm"], "zones": ["farm"]},
          "The fields are south-east of the Village (/hubmap gives directions).", 25),
        O("harvest_wheat", "Harvest Wheat",
          {"type": "break", "materials": ["WHEAT"], "gather": "FARMING", "what": ["WHEAT"], "amount": 10},
          "Break 10 fully grown wheat in the Farm.", 50, True),
        O("slay_graveyard", "Slay Zombies in the Graveyard",
          {"type": "kill", "island": "hub", "areas": ["graveyard", "crypt"], "amount": 5},
          "Zombies roam the Graveyard east of the Village, more of them at night. Bank your coins and buy the Armorsmith's iron set first: a death costs half your purse.", 100),
        O("reach_combat_2", "Reach Combat II",
          {"type": "requirement", "key": "skill:combat:2"},
          "Every kill gives Combat XP.", 100, True),
        O("talk_librarian", "Talk to the Librarian",
          {"type": "npc", "anchors": ["npc.librarian", "npc.enchanting_table"]},
          "The Library holds the enchanting table. It is north-east of the Village square; /village points your compass at its door.", 50),
        O("talk_blacksmith", "Learn about Reforges",
          {"type": "npc", "anchors": ["npc.blacksmith", "npc.reforge_anvil"]},
          "The Blacksmith reforges gear for coins. He works in the Mining District, north-east of the Village (/hubmap gives directions).", 50),
        O("enter_coal_mine", "Travel to the Coal Mine",
          {"type": "area", "island": "hub", "areas": ["coal_mine"], "zones": ["coal_mine"]},
          "The Coal Mine is dug into the mountain north-east of the Village (/hubmap gives directions).", 25),
        O("mine_coal", "Mine Coal",
          {"type": "break", "materials": ["COAL_ORE", "DEEPSLATE_COAL_ORE"], "gather": "MINING", "what": ["COAL"], "amount": 10},
          "Mine 10 coal ore in the Coal Mine.", 100, True),
        O("chop_forest", "Chop logs in the Forest",
          {"type": "break", "materials": LOGS, "gather": "FORAGING", "amount": 20, "island": "hub"},
          "Chop 20 logs in the Hub's Forest, west of the Portal Plaza (/hubmap gives directions). The Plaza's own trees are decoration.", 100, True),
        O("visit_gold_mine", "Visit the Gold Mine",
          {"type": "island", "island": "gold_mine"},
          "The Gold Mine lies past the Coal Mine.", 100),
        O("visit_barn", "Visit The Barn",
          {"type": "island", "island": "barn"},
          "The Barn is the first Farming Island. Its launch pad is at the east end of the Farm and needs Farming I: harvest some wheat first.", 100),
        O("reach_farming_5", "Reach Farming V",
          {"type": "requirement", "key": "skill:farming:5"},
          "Farming crops gives Farming XP.", 150, True),
        O("reach_foraging_5", "Reach Foraging V",
          {"type": "requirement", "key": "skill:foraging:5"},
          "Chopping trees gives Foraging XP.", 150, True),
        O("visit_park", "Visit The Park",
          {"type": "island", "island": "park"},
          "Five forests, one island. Reach it from the Forest.", 150),
        O("talk_slayer_master", "Talk to the Slayer Master",
          {"type": "npc", "anchors": ["npc.slayer_master"]},
          "Find the Slayer Master in the Tavern, east of the Village square (/village). Slayer bosses drop the best early gear.", 150),
        O("visit_deep_caverns", "Visit the Deep Caverns",
          {"type": "island", "island": "deep_caverns"},
          "The Deep Caverns are below the Gold Mine.", 150),
        O("visit_spiders_den", "Visit the Spider's Den",
          {"type": "island", "island": "spiders_den"},
          "Spiders, webs and a lot of string.", 150),
        O("reach_sblevel_5", "Reach SkyBlock Level 5",
          {"type": "requirement", "key": "sblevel:5"},
          "Nearly everything you do for the first time gives SkyBlock XP.", 250),
        O("visit_garden", "Visit the Garden",
          {"type": "island", "island": "garden"},
          "Your own farm, unlocked at SkyBlock Level 5. Ask the Gardener in the Hub.", 250),
        O("donate_museum", "Donate to the Museum",
          {"type": "museum", "amount": 1},
          "The Museum keeps your finest items on display and pays in SkyBlock XP.", 250, True),
        O("visit_the_end", "Visit The End",
          {"type": "island", "island": "the_end"},
          "The End needs a stronger fighter. Keep raising Combat.", 500),
    ]
    write("objectives.json", {
        "_note": "The guided new-player chain (research 03 section 2). Design data: order, texts, triggers. Each "
                 "completed objective pays 5 SkyBlock XP (sblevels.json complete_objectives_xp) plus 'coins' "
                 "(TUNE: small starter rewards). Trigger types are documented in ObjectiveTrigger.java. "
                 "'skippable' objectives can be skipped from the Quest Log (no reward) so a missing module can "
                 "never strand a player.",
        "chain": chain,
    })
    return len(chain)


# ---------------------------------------------------------------------------------------------
# Island Guides (research 03 section 2.2: the 2026 rework's per-district FIRST-ENTRY objectives; "let each island
# register its own first-entry objectives"). The Hub's districts are the hub module's District Guides
# (data/hub/guides.json); these are the same idea for every other island. Design data, numbers TUNE.
# Trigger types (IslandGuides.java): npc (anchors), zone (zones), kill (what = mob id prefixes), gather
# (gather = MINING|FARMING|FORAGING, what = substrings of the gathered id, empty = anything), fish (amount),
# requirement (key). All steps are on the guide's island.
# ---------------------------------------------------------------------------------------------

def G(text, trigger, hint):
    t = dict(trigger)
    t.setdefault("amount", 1)
    return {"text": text, "hint": hint, "trigger": t}


def npc(*anchors):
    return {"type": "npc", "anchors": list(anchors)}


def zone(*zones):
    return {"type": "zone", "zones": list(zones)}


def kill(n, *prefixes):
    return {"type": "kill", "what": list(prefixes), "amount": n}


def gather(kind, n, *what):
    return {"type": "gather", "gather": kind, "what": list(what), "amount": n}


GUIDES = [
    ("barn", "The Barn", "WHEAT", 1000, "Meet the farmers of the Barn and work its fields.", [
        G("Talk to the Farm Guide", npc("npc.farm_guide"), "The Farm Guide waits by the windmill path, north of the portal."),
        G("Visit the Wheat Fields", zone("barn.wheat"), "The wheat fields lie in the north-west corner of the island."),
        G("Harvest 64 crops", gather("FARMING", 64), "Any fully grown crop on the Barn counts."),
        G("Visit the Windmill", zone("barn.windmill"), "The windmill turns at the heart of the island."),
    ]),
    ("mushroom_desert", "Mushroom Desert", "RED_MUSHROOM", 1500, "Cross the desert, meet the Trapper and find the gorge.", [
        G("Visit the Desert Settlement", zone("desert.settlement"), "The settlement sits just past the portal from the Barn."),
        G("Talk to the Trapper", npc("npc.trapper"), "The Trapper's Den is up on the north-east ridge."),
        G("Explore the Mushroom Gorge", zone("desert.gorge"), "The gorge cuts into the west side of the desert."),
        G("Harvest 64 Cactus or Mushrooms", gather("FARMING", 64, "CACTUS", "MUSHROOM"), "Cactus grows in the fields by the settlement, mushrooms in the gorge."),
    ]),
    ("park", "The Park", "BIRCH_LOG", 1500, "Five forests and the cave the wolves call home.", [
        G("Talk to the Park Ranger", npc("npc.park_ranger"), "The Ranger's hut is a short walk from the portal."),
        G("Chop 64 logs", gather("FORAGING", 64), "Any tree in the Park counts; Birch Park is right by the portal."),
        G("Find the Howling Cave", zone("howling_cave", "howl_landing"), "The cave mouth opens under the Spruce Woods."),
        G("Slay 5 Pack Wraiths", kill(5, "park_pack_spirit"), "Pack Wraiths haunt the Howling Cave at night."),
    ]),
    ("gold_mine", "Gold Mine", "GOLD_INGOT", 1000, "Meet the forgers and dig your first gold.", [
        G("Visit the Miners' Camp", zone("camp"), "The camp is right at the top of the mine."),
        G("Talk to the Gold Forger", npc("npc.gold_forger"), "The Gold Forger works at the camp."),
        G("Mine 32 Gold", gather("MINING", 32, "GOLD"), "Gold ore lines the Gold Gallery and the Pit."),
        G("Explore the Lapis Tunnel", zone("lapis_tunnel"), "A blue-lit tunnel runs off the west side of the mine."),
    ]),
    ("deep_caverns", "Deep Caverns", "LAPIS_LAZULI", 2000, "Six layers down to the Obsidian Sanctuary.", [
        G("Ride a Lift", npc("npc.lift.1", "npc.lift.2", "npc.lift.3", "npc.lift.4", "npc.lift.5", "npc.lift.6"),
          "The Lift Operator at the centre of each layer takes you to any floor you have reached."),
        G("Reach the Lapis Quarry", zone("lapis_quarry"), "Take the Haul Road down from the Gunpowder Mines."),
        G("Slay 10 Lapis Zombies", kill(10, "deep_caverns_lapis_zombie"), "Lapis Zombies wander the Lapis Quarry."),
        G("Reach the Diamond Reserve", zone("diamond_reserve"), "Keep going down: the Diamond Reserve is the fifth layer."),
    ]),
    ("dwarven_mines", "Dwarven Mines", "PRISMARINE_CRYSTALS", 2500, "Commissions, the Heart of the Mountain and the Forge.", [
        G("Take a Commission", npc("npc.commissions"), "The commission board is in the Dwarven Village, by the portal."),
        G("Visit the Heart of the Mountain", npc("npc.hotm"), "The keeper of the Heart stands in the middle of the Village."),
        G("Mine 64 Mithril", gather("MINING", 64, "MITHRIL"), "Mithril lines every mining area outside the Village."),
        G("Visit the Forge", npc("npc.forge"), "The Forge is east of the Village; the minecart there takes you."),
    ]),
    ("crystal_hollows", "Crystal Caverns", "AMETHYST_SHARD", 5000, "A cavern of gemstones, ruins and goblins.", [
        G("Talk to the Hollows Guide", npc("npc.hollows_guide"), "The guide waits at the Caverns Entrance."),
        G("Mine 32 Gemstones", gather("MINING", 32, "GEM", "RUBY", "AMBER", "SAPPHIRE", "JADE", "AMETHYST", "TOPAZ"),
          "Gemstone veins glow all over the Hollows."),
        G("Reach the Crystal Nucleus", zone("crystal_nucleus"), "The Nucleus is at the very centre of the cavern."),
        G("Find the Lost Relic City", zone("precursor_city"), "The ruined city lies in the south-east quarter."),
    ]),
    ("glacite_tunnels", "Glacite Tunnels", "PACKED_ICE", 5000, "Base Camp and the frozen tunnels below the Mines.", [
        G("Talk to the Excavator", npc("npc.camp_excavator"), "The Excavator works at Base Camp."),
        G("Check the Base Camp commissions", npc("npc.camp_commissions"), "The camp has its own commission board."),
        G("Reach the Great Rimestone Lake", zone("tunnels_lake"), "Follow the tunnels down to the frozen lake."),
        G("Slay 10 Rimestone Walkers", kill(10, "gt_glacite_walker"), "Walkers patrol the tunnels around the camp."),
    ]),
    ("spiders_den", "Spider's Den", "STRING", 1500, "Webs, slime and the Silkmother's sanctuary.", [
        G("Talk to the Den Ranger", npc("npc.den_guide"), "The ranger stands at the Den Entrance, by the portal."),
        G("Slay 20 Spiders", kill(20, "den_"), "Spiders crawl all over the mound."),
        G("Visit Granny's Cottage", zone("grannys_cottage"), "Granny lives on the west side of the Den and knows every creature."),
        G("Climb to the Top of the Nest", zone("top_of_the_nest"), "Climb the mound to its very top."),
    ]),
    ("the_end", "The End", "ENDER_PEARL", 3000, "Endermen, Zealots and the Dragon's Nest.", [
        G("Talk to the End Warden", npc("npc.end_guide"), "The warden stands at the End Gate, by the portal."),
        G("Slay 25 Endermen", kill(25, "end_enderman"), "Endermen roam everywhere past the gate."),
        G("Slay a Zealot", kill(1, "end_zealot"), "Zealots gather in the Dragon's Nest."),
        G("Reach the Dragon's Nest", zone("dragons_nest", "drag_landing"), "The Nest is the great crater to the north."),
    ]),
    ("crimson_isle", "Crimson Isle", "BLAZE_POWDER", 5000, "Two factions, one volcano.", [
        G("Talk to the Crimson Watchman", npc("npc.crimson_guide"), "The Watchman greets every arrival by the portal."),
        G("Meet a faction leader", npc("npc.barbarian_leader", "npc.mage_leader"),
          "The Barbarians hold the west town, the Mages the east. Pick a side - you can only join one."),
        G("Slay 10 Blazes", kill(10, "crimson_blaze"), "Blazes swarm the Crimson Fields and the volcano slopes."),
        G("Meet the Trophy Keeper", npc("npc.trophy_keeper"), "The Trophy Keeper's hut is at the foot of the volcano."),
    ]),
    ("jerrys_workshop", "Winter Workshop", "SNOWBALL", 1000, "Gerald's workshop, the Festival Hall and the mountain.", [
        G("Talk to Gerald", npc("npc.jerry"), "Gerald runs the workshop right by the portal."),
        G("Visit the Emporium", zone("emporium"), "The Emporium is across the plaza from the workshop."),
        G("Visit the Festival Hall", zone("festival_hall"), "Every festival's merchant has a stall in the hall."),
        G("Climb Mount Gerald", zone("mount_gerald"), "The mountain rises behind the workshop."),
    ]),
    ("dungeon_hub", "Dungeon Hub", "SKELETON_SKULL", 2000, "Get ready for your first dungeon run.", [
        G("Choose a Class at the altar", npc("npc.class_altar"), "The Class Altar is east of Gravewatch Square."),
        G("Check the Party Board", npc("npc.party_board"), "Find a group on the Party Board, west of the square."),
        G("Visit the Provisioner", npc("npc.provisioner"), "The Provisioner sells supplies south of the square."),
        G("Stand at the Catacombs Gate", zone("catacombs_gate"), "The gate is at the far end of the Mausoleum."),
    ]),
    ("backwater_bayou", "Backwater Bayou", "LILY_PAD", 1500, "Rods, bait and whatever lives in the swamp.", [
        G("Talk to the Tackle-smith", npc("npc.roddy"), "The Tackle-smith's shop is on the Bayou Docks."),
        G("Visit the Bait Shop", npc("npc.bait_shop"), "The Bait-seller is next door on the docks."),
        G("Catch 10 fish in the Bayou", {"type": "fish", "amount": 10}, "Cast into the bayou waters from the docks."),
        G("Visit the Moonlit Marsh", zone("moonlit_marsh"), "The marsh is the far west end of the bayou."),
    ]),
    ("galatea", "The Moonmarsh", "MANGROVE_LOG", 3000, "Knotwick, the loch and the Heart of the Forest.", [
        G("Visit Knotwick", zone("knotwick"), "The village of Knotwick is right by the arrival point."),
        G("Talk to the Heart of the Forest keeper", npc("npc.hotf_keeper"), "The keeper lives in Knotwick."),
        G("Chop 64 logs", gather("FORAGING", 64), "Any tree on the Moonmarsh counts."),
        G("Slay 5 Silt Wretches", kill(5, "marsh_tidetot", "marsh_seacurse", "marsh_hydrospear"), "They crawl out of the Dimwater Loch."),
    ]),
]


def gen_guides():
    guides = []
    for island, name, icon, coins, about, steps in GUIDES:
        # authoring check against the builders' last preview report (the live self-test progression.guide-targets
        # is the real gate; this only catches typos early)
        rep = os.path.join(ROOT, "previews", island, "report.txt")
        if os.path.isfile(rep):
            with open(rep, encoding="utf-8", errors="replace") as f:
                txt = f.read()
            have = set(re.findall(r"^\s+(?:anchor|zone|area)\s+(\S+)", txt, re.M))
            for st in steps:
                t = st["trigger"]
                for k in t.get("anchors", []) + t.get("zones", []):
                    if k not in have:
                        print("  WARN guide %s step '%s': %s not in previews/%s/report.txt" % (island, st["text"], k, island))
        guides.append({"id": island, "island": island, "name": name, "icon": icon, "coins": coins, "sbxp": 5,
                       "about": about, "steps": steps})
    write("island_guides.json", {
        "_note": "Per-island first-entry objective mini-chains (research 03 2.2). A guide starts the first time the "
                 "player stands on its island, its steps go in order, finishing pays 'coins' (TUNE) + 'sbxp' "
                 "(key island_guide:<id>, Story > Objectives). Hub districts are the hub module's District Guides.",
        "guides": guides,
    })
    return len(guides)


# ---------------------------------------------------------------------------------------------
# Century Cakes (research 08 section 6.7) -> items.json
# ---------------------------------------------------------------------------------------------

CAKES = [
    ("EPOCH_CAKE_PINK", "Crab-Colored Century Cake", "Crab-Colored Century Cake", "HEALTH", 10, None),
    ("EPOCH_CAKE_GREEN", "Pet Rock Century Cake", "Pet Rock Century Cake", "DEFENSE", 3, None),
    ("EPOCH_CAKE_RED", "Fruit Century Cake", "Fruit Century Cake", "STRENGTH", 2, None),
    ("EPOCH_CAKE_YELLOW", "Potato-Style Century Cake", "Potato-Style Century Cake", "SPEED", 10, None),
    ("EPOCH_CAKE_AQUA", "Barry Century Cake", "Scholar's Century Cake", "INTELLIGENCE", 5, None),
    ("EPOCH_CAKE_BLUE", "Sea Emperor Century Cake", "Tidelord Century Cake", "SEA_CREATURE_CHANCE", 1, None),
    ("EPOCH_CAKE_BLACK", "Century Cake of the Next Dungeon Floor", "Century Cake of the Next Dungeon Floor", "MAGIC_FIND", 1, None),
    ("EPOCH_CAKE_ORANGE", "Latest Update Century Cake", "Latest Update Century Cake", "FEROCITY", 2, None),
    ("EPOCH_CAKE_PURPLE", "Streamer's Century Cake", "Streamer's Century Cake", "PET_LUCK", 1, None),
    ("EPOCH_CAKE_BROWN", "Chocolate Century Cake", "Chocolate Century Cake", "FARMING_FORTUNE", 5, None),
    ("EPOCH_CAKE_WHITE", "Cloudy Century Cake", "Cloudy Century Cake", "FORAGING_FORTUNE", 5, None),
    ("EPOCH_CAKE_CYAN", "Century Cake of Hype", "Century Cake of Hype", "MINING_FORTUNE", 5, None),
    ("EPOCH_CAKE_DARK_GREEN", "Undead Century Cake", "Undead Century Cake", "VITALITY", 1, None),
    ("EPOCH_CAKE_GRAY", "Not-a-lie Century Cake", "Not-a-lie Century Cake", "TRUE_DEFENSE", 1, None),
    ("EPOCH_CAKE_MAGENTA", "ekaC yrutneC tfiR", "Backwards Century Cake", "RIFT_TIME", 10, None),
    ("EPOCH_CAKE_SILVER", "Corpse-Flavored Century Cake", "Corpse-Flavored Century Cake", "COLD_RESISTANCE", 1, None),
    ("EPOCH_CAKE_SEVEN_SEAS", "Century Cake of the Seven Seas", "Century Cake of the Seven Seas", "TREASURE_CHANCE", 1, None),
    # The reference stats (Hunting Fortune, Tracking) do not exist in this game: TUNE substitutes.
    ("EPOCH_CAKE_STARBORN", "Starborn Century Cake", "Starborn Century Cake", "TROPHY_FISH_CHANCE", 1, "TUNE: reference grants +1 Hunting Fortune (no such stat here)"),
    ("EPOCH_CAKE_EXPIRED", "Long-Expired Century Cake", "Long-Expired Century Cake", "SWEEP", 5, None),
    ("EPOCH_CAKE_HEPHAESTUS", "Hephaestus' Century Cake", "Hephaestus' Century Cake", "MINING_SPEED", 5, "TUNE: reference grants +1 Tracking (no such stat here)"),
]


def skull_texture(neu_item):
    tag = neu_item.get("nbttag", "")
    m = re.search(r'Value:"([A-Za-z0-9+/=]+)"', tag)
    return m.group(1) if m else ""


def gen_cakes():
    out = []
    for cid, ref, ours, stat, amount, note in CAKES:
        rn("item", cid, ref, ours)
        path = os.path.join(DATA, "neu-repo", "items", cid + ".json")
        tex = ""
        if os.path.exists(path):
            with open(path, encoding="utf-8") as f:
                tex = skull_texture(json.load(f))
        d = {
            "id": cid,
            "name": ours,
            "material": "PLAYER_HEAD",
            "rarity": "SPECIAL" if cid != "EPOCH_CAKE_HEPHAESTUS" else "SPECIAL",
            "category": "CONSUMABLE",
            "skull": tex,
            "lore": [
                "&7Baked for the turn of a century.",
                "&7Eat it for a bonus that lasts &a48 hours&7,",
                "&7even while you are offline. Every kind",
                "&7of cake stacks with the others.",
            ],
            "tags": ["consumable", "century_cake"],
            "extra": {"cakeStat": stat, "cakeAmount": amount},
        }
        if note:
            d["extra"]["note"] = note
        out.append(d)
    write("items.json", out)


def gen_misc():
    fs = L("neu-repo", "constants", "fairy_souls.json")
    p = os.path.join(DATA, "neu-repo", "items", "PLACEABLE_FAIRY_SOUL_RIFT.json")
    with open(p, encoding="utf-8") as f:
        tex = skull_texture(json.load(f))
    write("misc.json", {
        "_note": "fairySoulTexture: skull for the floating soul. referenceSouls: the reference game's soul count per "
                 "area (NEU fairy_souls.json, 'Max Souls' " + str(fs.get("Max Souls")) + "), shown nowhere; the real "
                 "souls in this game are the fairy_soul.* anchors the island builders place.",
        "fairySoulTexture": tex,
        "referenceSouls": {k: len(v) for k, v in fs.items() if isinstance(v, list)},
    })


NPC_RENAMES = [
    ("npc", "progression_fairy", "Tia the Fairy", "Wren the Fairy"),
    ("npc", "progression_curator", "Madame Eleanor Q. Goldsworth III", "Curator Ottoline Vane"),
]


def write_names():
    rows = list(NPC_RENAMES) + RENAMES
    os.makedirs(os.path.dirname(NAMES), exist_ok=True)
    with open(NAMES, "w", encoding="utf-8", newline="\n") as f:
        f.write("# progression - renames\n\n")
        f.write("Generated by `tools/gen/progression/gen_progression.py` (edit the tables there and re-run).\n")
        f.write("Kept as-is (generic words): Fairy Soul, SkyBlock Level, Bestiary, Museum, Century Cake, Quest Log,\n")
        f.write("every generic mob family (Zombie, Wolf, Zealot, Crypt Ghoul...), every emblem not listed below.\n\n")
        f.write("| kind | id | reference name | our name |\n|---|---|---|---|\n")
        for kind, ident, ref, ours in rows:
            f.write("| %s | %s | %s | %s |\n" % (kind, ident, ref, ours))
    print("wrote", os.path.relpath(NAMES, ROOT), len(rows), "renames")


if __name__ == "__main__":
    nf = gen_bestiary()
    nm = gen_museum()
    gen_levels()
    no = gen_objectives()
    ng = gen_guides()
    print("island guides:", ng)
    gen_cakes()
    gen_misc()
    write_names()
    print("bestiary families:", nf, " museum entries:", nm, " objectives:", no)
