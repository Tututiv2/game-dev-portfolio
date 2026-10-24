"""Generate the crafting module's SkyBlock-side tables from the research data.

    python tools/gen/crafting/gen_skyblock.py

Writes into plugin/src/main/resources/data/crafting/:

  skyblock_recipes.json   Every 3x3 crafting recipe of the reference game (NEU `recipe` and
                          `recipes[type=crafting]`) for NON-vanilla results, as core Recipe JSON
                          with an `unlock` requirement key. The crafting module registers these
                          at boot ONLY for results that exist and have no CRAFTING recipe from
                          their owning module, so the book is complete even where an owner
                          registered nothing. Owners always win.
  neu_aliases.json        legacy NEU id -> Material ("LOG:2" -> "BIRCH_LOG"), inverted from
                          core's vanilla_aliases.json, so a recipe naming "LOG:2" still works when
                          no module registered that id (then it is the plain BIRCH_LOG item).
  collections.json        collection id -> {name, category}  (recipe book categories)

Unlock keys, in priority order:
  1. collections.json unlock strings ("Enchanted Diamond Recipe" at Diamond IV)
     and "<X> Minion Recipes" (every tier of that minion)
  2. NEU crafttext ("Requires: Diamond IV", "Requires: HotM 5", "Requires: Zombie Slayer 3",
     "Requires: Mithril X & HotM 2", "Requires: Fishing XXXV" (skill), "Bonzo VI" (boss collection ->
     collection:BOSS_BONZO:6), "20 Museum Donations" (museum:20); crop analyzer milestones have no
     resolver yet and stay never:)
  3. NEU slayer_req ("ZOMBIE_5")
  4. none -> always craftable
"""
import glob
import json
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
DATA = os.path.join(ROOT, "research", "data")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "crafting")
ALIASES = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "core-items", "vanilla_aliases.json")

SLOTS = ["A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3"]
ROMAN = {"I": 1, "V": 5, "X": 10, "L": 50, "C": 100}
SLAYERS = {"zombie": "zombie", "revenant": "zombie", "spider": "spider", "tarantula": "spider",
           "wolf": "wolf", "sven": "wolf", "enderman": "enderman", "eman": "enderman",
           "voidgloom": "enderman", "blaze": "blaze", "inferno": "blaze", "vampire": "vampire",
           "riftstalker": "vampire"}
# Dungeon bosses as the collections module names their collections (BOSS_<KEY>).
BOSSES = {"bonzo": "BONZO", "scarf": "SCARF", "the professor": "THE_PROFESSOR", "professor": "THE_PROFESSOR",
          "thorn": "THORN", "livid": "LIVID", "sadan": "SADAN", "necron": "NECRON", "kuudra": "KUUDRA"}
SKILLS = {"farming", "mining", "combat", "foraging", "fishing", "enchanting", "alchemy",
          "carpentry", "taming", "runecrafting", "social"}


def strip(s):
    return re.sub("§.", "", s or "").strip()


def roman(s):
    total, prev = 0, 0
    for ch in reversed(s):
        v = ROMAN.get(ch, 0)
        if v < prev:
            total -= v
        else:
            total += v
            prev = v
    return total


def fix_id(i):
    """NEU file ids use '-' for the legacy damage value; the game id uses ':' (LOG-2 -> LOG:2)."""
    m = re.match(r"^([A-Z0-9_]+)-(\d+)$", i)
    return m.group(1) + ":" + m.group(2) if m else i


def main():
    coll = json.load(open(os.path.join(DATA, "collections.json"), encoding="utf-8"))["collections"]
    items = {i["id"]: i for i in json.load(open(os.path.join(DATA, "items.json"), encoding="utf-8"))["items"]}

    coll_out = {}
    name_to_coll = {}
    recipe_unlock = {}        # display name (lower) -> unlock key
    minion_unlock = {}        # minion base name (lower) -> unlock key
    for cat, v in coll.items():
        for cid, c in v["items"].items():
            coll_out[cid] = {"name": c["name"], "category": cat}
            name_to_coll[c["name"].lower()] = cid
            for t in c["tiers"]:
                key = "collection:%s:%d" % (cid, t["tier"])
                for u in t["unlocks"]:
                    u = strip(u)
                    m = re.match(r"^(.*) Minion Recipes?$", u)
                    if m:
                        minion_unlock.setdefault(m.group(1).lower(), key)
                        continue
                    m = re.match(r"^(.*) Recipes?$", u)
                    if m:
                        recipe_unlock.setdefault(m.group(1).lower(), key)

    def crafttext_key(t):
        t = strip(t)
        if not t:
            return None
        t = re.sub(r"^Requires:?\s*", "", t)
        parts = []
        for p in [x.strip() for x in t.split("&")]:
            m = re.match(r"^(?:HotM|Heart of the Mountain Tier) (\d+)$", p)
            if m:
                parts.append("hotm:" + m.group(1))
                continue
            m = re.match(r"^(\w+) Slayer ([IVX\d]+)$", p)
            if m and m.group(1).lower() in SLAYERS:
                lvl = m.group(2)
                lvl = int(lvl) if lvl.isdigit() else roman(lvl)
                parts.append("slayer:%s:%d" % (SLAYERS[m.group(1).lower()], lvl))
                continue
            m = re.match(r"^(.*) ([IVXLC]+)$", p)
            if m:
                nm = m.group(1).lower()
                if nm in name_to_coll:
                    parts.append("collection:%s:%d" % (name_to_coll[nm], roman(m.group(2))))
                    continue
                if nm in SKILLS:
                    parts.append("skill:%s:%d" % (nm, roman(m.group(2))))
                    continue
            # Museum donations: progression registers `museum:<count>`.
            m = re.match(r"^(\d+) Museum Donations?$", p)
            if m:
                parts.append("museum:" + m.group(1))
                continue
            # Dungeon boss collections: collections owns BOSS_<NAME> (`collection:BOSS_BONZO:6`).
            m = re.match(r"^(.*) ([IVXLC]+)$", p)
            if m and m.group(1).lower() in BOSSES:
                parts.append("collection:BOSS_%s:%d" % (BOSSES[m.group(1).lower()], roman(m.group(2))))
                continue
            # Crop analyzer milestones (wave 5): garden owns the `garden` prefix; until its resolver parses
            # `garden:analyzer:N` the key fails closed (locked), and the crafting module names it itself
            # (CraftMath.analyzerText). See the crafting hand-off, Requests.
            m = re.match(r"^Crop Analyzer Milestone ([IVXLC]+)$", p)
            if m:
                parts.append("garden:analyzer:%d" % roman(m.group(1)))
                continue
            parts.append("never:" + p.replace(":", " "))
        return " & ".join(parts) if parts else None

    neu = {}
    for f in glob.glob(os.path.join(DATA, "neu-repo", "items", "*.json")):
        d = json.load(open(f, encoding="utf-8"))
        neu[d["internalname"]] = d

    recipes = []
    stats = {"recipes": 0, "unlock_coll": 0, "unlock_ct": 0, "unlock_slayer": 0, "free": 0, "never": 0}
    for iname in sorted(neu):
        d = neu[iname]
        if d.get("vanilla"):
            continue  # vanilla results come from the real server jar (gen_vanilla.py)
        rs = []
        if isinstance(d.get("recipe"), dict):
            rs.append(d["recipe"])
        rs += [r for r in d.get("recipes", []) if r.get("type") == "crafting"]
        if not rs:
            continue
        own = fix_id(iname)
        disp = strip(d.get("displayname", "")).lower()
        unlock = None
        src = None
        if disp in recipe_unlock:
            unlock, src = recipe_unlock[disp], "coll"
        elif d.get("crafttext"):
            unlock, src = crafttext_key(d["crafttext"]), "ct"
        if unlock is None:
            m = re.match(r"^(.*) minion [ivx]+$", disp)
            if m and m.group(1) in minion_unlock:
                unlock, src = minion_unlock[m.group(1)], "coll"
        if unlock is None and d.get("slayer_req"):
            m = re.match(r"^([A-Z]+)_(\d+)$", d["slayer_req"])
            if m and m.group(1).lower() in SLAYERS:
                unlock, src = "slayer:%s:%s" % (SLAYERS[m.group(1).lower()], m.group(2)), "slayer"
        for n, r in enumerate(rs):
            grid = []
            ok = True
            for s in SLOTS:
                v = (r.get(s) or "").strip()
                if not v:
                    grid.append("")
                    continue
                if ":" in v:
                    i, amt = v.rsplit(":", 1)
                    if not amt.isdigit():
                        i, amt = v, "1"
                else:
                    i, amt = v, "1"
                i = fix_id(i)
                if int(amt) <= 0:
                    ok = False
                grid.append("%s:%s" % (i, amt))
            if not ok or not any(grid):
                continue
            result = fix_id(r.get("overrideOutputId") or iname)
            rec = {"id": "sb:%s#%d" % (result, n), "type": "CRAFTING", "result": result,
                   "amount": int(r.get("count", 1) or 1), "grid": grid}
            if unlock:
                rec["unlock"] = unlock
            recipes.append(rec)
            stats["recipes"] += 1
            if unlock is None:
                stats["free"] += 1
            elif "never:" in unlock:
                stats["never"] += 1
            else:
                stats["unlock_" + src] += 1

    aliases = json.load(open(ALIASES, encoding="utf-8"))
    neu_alias = {}
    for material, ids in aliases.items():
        for i in ids:
            i2 = fix_id(i)
            neu_alias.setdefault(i2, material)
    # A few legacy ids the alias file does not carry but recipes use.
    for i, m in {"WOOD": "OAK_PLANKS", "LOG": "OAK_LOG", "SULPHUR": "GUNPOWDER", "SEEDS": "WHEAT_SEEDS",
                 "INK_SACK": "INK_SAC", "INK_SACK:4": "LAPIS_LAZULI", "INK_SACK:3": "COCOA_BEANS",
                 "ENDER_STONE": "END_STONE", "MELON": "MELON_SLICE", "NETHER_STALK": "NETHER_WART",
                 "CARROT_ITEM": "CARROT", "POTATO_ITEM": "POTATO", "RAW_FISH": "COD",
                 "RAW_FISH:1": "SALMON", "RAW_FISH:2": "TROPICAL_FISH", "RAW_FISH:3": "PUFFERFISH",
                 "PORK": "PORKCHOP", "RAW_CHICKEN": "CHICKEN", "WATER_LILY": "LILY_PAD",
                 "MYCEL": "MYCELIUM", "QUARTZ": "QUARTZ", "WORKBENCH": "CRAFTING_TABLE",
                 "WOOD_HOE": "WOODEN_HOE", "WOOD_SWORD": "WOODEN_SWORD", "WOOD_AXE": "WOODEN_AXE",
                 "WOOD_PICKAXE": "WOODEN_PICKAXE", "WOOD_SPADE": "WOODEN_SHOVEL",
                 "GOLD_HOE": "GOLDEN_HOE", "GOLD_SWORD": "GOLDEN_SWORD", "GOLD_AXE": "GOLDEN_AXE",
                 "GOLD_PICKAXE": "GOLDEN_PICKAXE", "GOLD_SPADE": "GOLDEN_SHOVEL",
                 "IRON_SPADE": "IRON_SHOVEL", "DIAMOND_SPADE": "DIAMOND_SHOVEL",
                 "STONE_SPADE": "STONE_SHOVEL", "SNOW_BALL": "SNOWBALL", "CLAY_BALL": "CLAY_BALL",
                 "SLIME_BALL": "SLIME_BALL", "SUGAR_CANE": "SUGAR_CANE", "LOG_2": "ACACIA_LOG",
                 "LOG_2:1": "DARK_OAK_LOG", "LOG:1": "SPRUCE_LOG", "LOG:2": "BIRCH_LOG",
                 "LOG:3": "JUNGLE_LOG", "SAND:1": "RED_SAND", "RED_ROSE": "POPPY",
                 "YELLOW_FLOWER": "DANDELION", "DOUBLE_PLANT": "SUNFLOWER", "EXP_BOTTLE": "EXPERIENCE_BOTTLE",
                 "FIREBALL": "FIRE_CHARGE", "SKULL_ITEM": "SKELETON_SKULL", "IRON_FENCE": "IRON_BARS",
                 "THIN_GLASS": "GLASS_PANE", "WEB": "COBWEB", "HUGE_MUSHROOM_1": "BROWN_MUSHROOM_BLOCK",
                 "HUGE_MUSHROOM_2": "RED_MUSHROOM_BLOCK", "STAINED_CLAY": "TERRACOTTA",
                 "HARD_CLAY": "TERRACOTTA", "NETHER_BRICK_ITEM": "NETHER_BRICK", "SMOOTH_BRICK": "STONE_BRICKS",
                 "WOOL": "WHITE_WOOL", "CARPET": "WHITE_CARPET", "GRILLED_PORK": "COOKED_PORKCHOP",
                 "COOKED_FISH": "COOKED_COD", "RABBIT_FOOT": "RABBIT_FOOT", "SPECKLED_MELON": "GLISTERING_MELON_SLICE",
                 "GOLDEN_CARROT": "GOLDEN_CARROT", "BOOK_AND_QUILL": "WRITABLE_BOOK", "EYE_OF_ENDER": "ENDER_EYE",
                 "LEASH": "LEAD", "BOAT": "OAK_BOAT", "STEP": "SMOOTH_STONE_SLAB", "WOOD_STEP": "OAK_SLAB",
                 "SAPLING": "OAK_SAPLING", "LEAVES": "OAK_LEAVES", "LEAVES_2": "ACACIA_LEAVES",
                 "LEAVES:1": "SPRUCE_LEAVES", "LEAVES:2": "BIRCH_LEAVES", "LEAVES:3": "JUNGLE_LEAVES",
                 "LEAVES_2:1": "DARK_OAK_LEAVES", "GOLDEN_APPLE:1": "ENCHANTED_GOLDEN_APPLE",
                 "MOB_SPAWNER": "SPAWNER", "ENCHANTMENT_TABLE": "ENCHANTING_TABLE", "SULPHUR_ORE": "GUNPOWDER"}.items():
        neu_alias.setdefault(i, m)

    # ---- Trades (Recipe Book -> Trades): collection-unlocked item swaps ----------------------------
    # Which trades exist and what unlocks them comes from the collection tiers ("Dirt Trade" at Seeds I).
    # The research has no price table (only "Water Bucket for 12 coins"), so the costs are ours:
    # TUNE, each priced above the result's NPC sell value so a trade can never print coins.
    trade_costs = {   # trade name -> (result id, amount, [cost cells])
        "Dirt": ("DIRT", 1, ["SEEDS:4"]),
        "Clay Ball": ("CLAY_BALL", 1, ["SEEDS:8"]),
        "Long Grass": ("LONG_GRASS:1", 1, ["SEEDS:3"]),
        "Fern": ("LONG_GRASS:2", 1, ["SEEDS:3"]),
        "Dead Bush": ("DEAD_BUSH", 1, ["SEEDS:3"]),
        "Double Tallgrass": ("DOUBLE_PLANT:2", 1, ["SEEDS:4"]),
        "Milk Bucket": ("MILK_BUCKET", 1, ["COINS:12", "LEATHER:1"]),
        "Soul Sand": ("SOUL_SAND", 1, ["SAND:4"]),
        "Nether Brick": ("NETHER_BRICK_ITEM", 1, ["NETHERRACK:2"]),
        "Teleport Pad": ("TELEPORT_PAD", 1, ["COINS:2500", "ENDER_PEARL:16"]),
        "Lava Bucket": ("LAVA_BUCKET", 1, ["COINS:12", "MAGMA_CREAM:1"]),
        "Enchanted Bone Meal": ("ENCHANTED_BONE_MEAL", 1, ["BONE:2"]),
        "Acacia Leaves": ("LEAVES_2", 4, ["LOG_2:1"]),
        "Spruce Leaves": ("LEAVES:1", 4, ["LOG:1:1"]),
        "Jungle Leaves": ("LEAVES:3", 4, ["LOG:3:1"]),
        "Vines": ("VINE", 2, ["LOG:3:2"]),
        "Birch Leaves": ("LEAVES:2", 4, ["LOG:2:1"]),
        "Oak Leaves": ("LEAVES", 4, ["LOG:1"]),
        "Dark Oak Leaves": ("LEAVES_2:1", 4, ["LOG_2:1:1"]),
        "Water Bucket": ("WATER_BUCKET", 1, ["COINS:12"]),
        "Sponge": ("SPONGE", 1, ["COINS:80", "PRISMARINE_SHARD:4"]),
    }
    trades = []
    for cid, c in sorted(((cid, c) for v in coll.values() for cid, c in v["items"].items())):
        for t in c["tiers"]:
            for u in t["unlocks"]:
                u = strip(u)
                m = re.match(r"^(.*) Trade$", u)
                if not m:
                    continue
                nm = m.group(1)
                if nm not in trade_costs:
                    print("trade without a cost:", nm)
                    continue
                res, amt, cost = trade_costs[nm]
                trades.append({"id": "trade_" + re.sub(r"[^a-z0-9]+", "_", nm.lower()).strip("_"),
                               "name": nm, "result": res, "amount": amt, "cost": cost,
                               "unlock": "collection:%s:%d" % (cid, t["tier"]),
                               "collection": cid})
    with open(os.path.join(OUT, "trades.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(trades, f, indent=1)
    print("trades:", len(trades))

    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "skyblock_recipes.json"), "w", encoding="utf-8", newline="\n") as f:
        f.write("[\n" + ",\n".join(json.dumps(r, separators=(",", ":")) for r in recipes) + "\n]\n")
    with open(os.path.join(OUT, "neu_aliases.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(dict(sorted(neu_alias.items())), f, indent=0)
    with open(os.path.join(OUT, "collections.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(coll_out, f, indent=1)
    print(stats, "aliases:", len(neu_alias), "collections:", len(coll_out))


if __name__ == "__main__":
    main()
