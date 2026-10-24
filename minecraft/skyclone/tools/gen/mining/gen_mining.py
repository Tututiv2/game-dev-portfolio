#!/usr/bin/env python3
"""
Generates every data file of the mining module from the research data.

    python tools/gen/mining/gen_mining.py

Writes plugin/src/main/resources/data/mining/:
    items.json        ItemDefs this module OWNS (pickaxes, drills, parts, fuel, gems, crystals, metals,
                      Crystal Hollows loot, fossils, keys, chisels, refined metals and plates...)
    blocks.json       the mineable-block table per island (strength, breaking power, drops, XP, powder)
    hotm.json         the Heart of the Mountain tree: tiers, every perk with its per-level cost and value
                      tables (NEU's formulas are evaluated here, so Java never parses them)
    forge.json        every Forge recipe from the reference data (Recipe JSON, type FORGE)
    recipes.json      gem compression + the Pickaxe Smith / Hollows merchant shop lines
    commissions.json  every commission per island
    loot.json         treasure chest, nucleus, corpse, goblin, raffle, Fetchur tables
    mobs.json         the module's own rare / event mobs

Names shown to players are OUR names (see docs/names/mining.md); ids stay the reference ids.
Descriptions are written here in our own words; numbers come from the research data.
"""
import json
import math
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
RES = os.path.join(ROOT, "research", "data")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "mining")


def load(p):
    with open(os.path.join(RES, p), encoding="utf-8") as f:
        return json.load(f)


REF_ITEMS = {i["id"]: i for i in load("items.json")["items"]}
for _i in REF_ITEMS.values():
    _i["stats"] = {k.upper(): v for k, v in _i.get("stats", {}).items()}


def neu(iid):
    p = os.path.join(RES, "neu-repo", "items", iid + ".json")
    if not os.path.exists(p):
        return None
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def strip(s):
    return re.sub(r"§.", "", s)


RARITY = ["COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE", "SPECIAL", "VERY_SPECIAL"]


def ref_rarity(iid, default="COMMON"):
    r = REF_ITEMS.get(iid, {}).get("tier")
    if r in RARITY:
        return r
    return default


def ref_sell(iid):
    v = REF_ITEMS.get(iid, {}).get("npc_sell_price")
    return float(v) if v else 0.0


def ref_skin(iid):
    sk = REF_ITEMS.get(iid, {}).get("skin")
    if isinstance(sk, dict):  # newer reference entries carry {value, signature}; ItemDef.skull is the value string
        sk = sk.get("value")
    return sk


def hotm_req(iid):
    out = []
    for r in REF_ITEMS.get(iid, {}).get("requirements", []) or []:
        if r.get("type") == "HEART_OF_THE_MOUNTAIN":
            out.append("hotm:%d" % r["tier"])
    return out


def wrap(text, width=36, color="&7"):
    """Wrap a sentence into lore lines, keeping the colour code at the start of every line."""
    words = text.split(" ")
    lines, cur, vis = [], color, 0
    for w in words:
        wv = len(re.sub(r"&.", "", w))
        if vis and vis + 1 + wv > width:
            lines.append(cur)
            last = re.findall(r"&[0-9a-fk-or]", cur)
            cur = (last[-1] if last else color)
            vis = 0
        if vis:
            cur += " "
            vis += 1
        cur += w
        vis += wv
    if vis:
        lines.append(cur)
    return lines


ITEMS = []


def item(iid, name, material, rarity=None, category="MATERIAL", lore=None, stats=None, tags=None,
         req=None, sell=None, extra=None, glow=False, skull=None, gem_slots=None, abilities=None, max_stack=0):
    d = {
        "id": iid,
        "name": name,
        "material": material,
        "rarity": rarity or ref_rarity(iid),
        "category": category,
    }
    st = stats if stats is not None else {}
    if st:
        d["stats"] = st
    if lore:
        d["lore"] = lore
    if abilities:
        d["abilities"] = abilities
    t = set(tags or [])
    t.add("mining")
    d["tags"] = sorted(t)
    rq = req if req is not None else hotm_req(iid)
    if rq:
        d["requirements"] = rq
    s = ref_sell(iid) if sell is None else sell
    if s:
        d["npcSell"] = s
    if extra:
        d["extra"] = extra
    if glow:
        d["glow"] = True
    sk = skull if skull is not None else (ref_skin(iid) if material == "PLAYER_HEAD" else None)
    if sk:
        d["skull"] = sk
    if gem_slots:
        d["gemSlots"] = gem_slots
    if max_stack:
        d["maxStack"] = max_stack
    ITEMS.append(d)
    return d


def ref_gem_slots(iid):
    out = []
    for s in REF_ITEMS.get(iid, {}).get("gemstone_slots", []) or []:
        out.append(s["slot_type"])
    return out


PICK_ABILITY = {
    "id": "mining_pickaxe_ability",
    "name": "Pickaxe Ability",
    "trigger": "RIGHT_CLICK",
    "mana": 0,
    "cooldown": 0,
    "lore": ["&7Fires the ability you picked in your", "&5Heart of the Mountain&7. Choose it", "&7with &e/hotm&7."],
}

# ----------------------------------------------------------------------------------------------
# Pickaxes
# ----------------------------------------------------------------------------------------------


def pick_stats(iid):
    s = REF_ITEMS[iid]["stats"]
    out = {}
    for k in ("BREAKING_POWER", "MINING_SPEED", "MINING_FORTUNE", "ORE_FORTUNE", "GEMSTONE_FORTUNE", "PRISTINE", "STRENGTH"):
        if s.get(k):
            out[k] = s[k]
    return out, s.get("DAMAGE", 0)


def pickaxe(iid, name, material, desc=None, rarity=None, vanilla=False, extra=None, tags=None, cat="PICKAXE", abilities=None):
    st, dmg = pick_stats(iid)
    lore = []
    if desc:
        lore += wrap(desc)
    t = ["reforgeable", "enchantable", "pickaxe"] + (tags or [])
    if vanilla:
        t.append("vanilla")
    d = item(iid, name, material, rarity or ref_rarity(iid), cat, lore=lore, stats=st, tags=t, extra=extra,
             abilities=(abilities if abilities is not None else [PICK_ABILITY]), gem_slots=ref_gem_slots(iid))
    if dmg:
        d["damage"] = dmg
    return d


pickaxe("WOOD_PICKAXE", "Wooden Pickaxe", "WOODEN_PICKAXE", rarity="COMMON", vanilla=True)
pickaxe("STONE_PICKAXE", "Stone Pickaxe", "STONE_PICKAXE", rarity="COMMON", vanilla=True)
pickaxe("IRON_PICKAXE", "Iron Pickaxe", "IRON_PICKAXE", rarity="COMMON", vanilla=True)
pickaxe("GOLD_PICKAXE", "Golden Pickaxe", "GOLDEN_PICKAXE", rarity="COMMON", vanilla=True)
pickaxe("DIAMOND_PICKAXE", "Diamond Pickaxe", "DIAMOND_PICKAXE", rarity="UNCOMMON", vanilla=True)
# not in the reference (no netherite there): TUNE, one step above diamond so it is never a dead item
item("NETHERITE_PICKAXE", "Netherite Pickaxe", "NETHERITE_PICKAXE", "UNCOMMON", "PICKAXE",
     stats={"BREAKING_POWER": 4, "MINING_SPEED": 260}, tags=["reforgeable", "enchantable", "pickaxe", "vanilla"],
     abilities=[PICK_ABILITY], extra={"tune": "not in the reference; one step above diamond"})["damage"] = 35
pickaxe("ROOKIE_PICKAXE", "Rookie Pickaxe", "STONE_PICKAXE", "A sturdy first pick sold to every new miner.", rarity="COMMON")
pickaxe("PROMISING_PICKAXE", "Promising Pickaxe", "IRON_PICKAXE",
        "Stored Potential: gains &6+10 ⸕ Mining Speed &7for every &a100 &7blocks you mine with it, up to &6+250&7.",
        extra={"storedPotential": {"perBlocks": 100, "speed": 10, "max": 250}})
pickaxe("ZOMBIE_PICKAXE", "Zombie Pickaxe", "IRON_PICKAXE",
        "Rotten: &6+5 ☘ Mining Fortune &7for every undead mob within &a30 &7blocks of you (max &6+25&7).",
        extra={"undeadFortune": {"per": 5, "max": 25, "radius": 30}})
pickaxe("LAPIS_PICKAXE", "Lapis Pickaxe", "DIAMOND_PICKAXE",
        "Has a &a25% &7chance to double the experience orbs you get from ores.", extra={"doubleExpChance": 0.25})
pickaxe("STONK_PICKAXE", "Lucky Pick", "GOLDEN_PICKAXE",
        "A gaudy golden pick that cuts end stone like butter and sometimes finds a little extra.",
        extra={"endStoneBonus": 0.05})
pickaxe("JUNGLE_PICKAXE", "Jungle Pickaxe", "WOODEN_PICKAXE",
        "Sometimes turns up &aSludge Juice &7while you mine in the jungle of the crystal caverns.",
        extra={"sludgeChance": 0.01})
pickaxe("FRACTURED_MITHRIL_PICKAXE", "Fractured Mithril Pickaxe", "STONE_PICKAXE", "Cracked, but it still bites into mithril.")
pickaxe("BANDAGED_MITHRIL_PICKAXE", "Bandaged Mithril Pickaxe", "STONE_PICKAXE", "Patched together and holding, for now.")
pickaxe("MITHRIL_PICKAXE", "Mithril Pickaxe", "DIAMOND_PICKAXE", "A proper dwarven pick, forged from mithril.")
pickaxe("REFINED_MITHRIL_PICKAXE", "Refined Mithril Pickaxe", "DIAMOND_PICKAXE", "Mithril, refined until it shines.")
pickaxe("RUSTY_TITANIUM_PICKAXE", "Rusty Titanium Pickaxe", "WOODEN_PICKAXE",
        "Grants &6+25 ☘ Mining Fortune &7when mining &fTitanium&7.", extra={"titaniumFortune": 25})
pickaxe("TITANIUM_PICKAXE", "Titanium Pickaxe", "IRON_PICKAXE",
        "Grants &6+50 ☘ Mining Fortune &7when mining &fTitanium&7.", extra={"titaniumFortune": 50})
pickaxe("REFINED_TITANIUM_PICKAXE", "Polished Titanium Pickaxe", "IRON_PICKAXE",
        "Grants &6+100 ☘ Mining Fortune &7when mining &fTitanium&7.", extra={"titaniumFortune": 100})
pickaxe("PICKONIMBUS", "Stratopick 2000", "DIAMOND_PICKAXE",
        "A cloud-light pick that shatters after &a2,000 &7blocks.", extra={"uses": 2000})
gg = pickaxe("GEMSTONE_GAUNTLET", "Gemstone Gauntlet", "PLAYER_HEAD",
             "Mobs you hit on a mining island spill rough copies of the gems slotted in this gauntlet &8(2s cooldown)&7. Killing mobs on a mining island shaves &a0.5s &7per Perfect gem off your Forge timers.",
             cat="GAUNTLET", extra={"gauntlet": True})
gg["skull"] = ref_skin("GEMSTONE_GAUNTLET")

# ----------------------------------------------------------------------------------------------
# Drills
# ----------------------------------------------------------------------------------------------
DRILLS = [
    ("MITHRIL_DRILL_1", "Mithril Drill MK-I", {"mithrilPowder": 20}),
    ("MITHRIL_DRILL_2", "Mithril Drill MK-II", {"mithrilPowder": 40}),
    ("TITANIUM_DRILL_1", "Titanium Drill TX-1", {}),
    ("TITANIUM_DRILL_2", "Titanium Drill TX-2", {}),
    ("TITANIUM_DRILL_3", "Titanium Drill TX-3", {}),
    ("TITANIUM_DRILL_4", "Titanium Drill TX-4", {}),
    ("GEMSTONE_DRILL_1", "Ruby Drill R-15", {"gemPowder": 5, "gemSpeed": 800}),
    ("GEMSTONE_DRILL_2", "Gemstone Drill G-52", {"gemPowder": 10, "gemSpeed": 800}),
    ("GEMSTONE_DRILL_3", "Topaz Drill T-12", {"gemPowder": 15, "gemSpeed": 800}),
    ("GEMSTONE_DRILL_4", "Jasper Drill J-X", {"gemPowder": 20, "gemSpeed": 800}),
    ("DIVAN_DRILL", "Varnas Drill", {}),
]
for iid, name, ex in DRILLS:
    st, dmg = pick_stats(iid)
    lore = []
    if ex.get("mithrilPowder"):
        lore += wrap("Grants &2+%d%% Mithril Powder&7." % ex["mithrilPowder"])
    if ex.get("gemPowder"):
        lore += wrap("Grants &d+%d%% Gemstone Powder &7and &6+%d ⸕ Mining Speed &7when mining gemstones."
                     % (ex["gemPowder"], ex["gemSpeed"]))
    ex = dict(ex)
    ex["drill"] = True
    ex["baseFuel"] = 3000
    d = item(iid, name, "PRISMARINE_SHARD", ref_rarity(iid), "DRILL", lore=lore, stats=st,
             tags=["reforgeable", "enchantable", "drill"], extra=ex, abilities=[PICK_ABILITY],
             gem_slots=ref_gem_slots(iid), glow=True)
    d["damage"] = dmg

# ----------------------------------------------------------------------------------------------
# Drill parts and fuel
# ----------------------------------------------------------------------------------------------
ENGINES = [("MITHRIL_DRILL_ENGINE", "Mithril-Plated Drill Engine", "PISTON", 75, 5),
           ("TITANIUM_DRILL_ENGINE", "Titanium-Plated Drill Engine", "STICKY_PISTON", 150, 15),
           ("RUBY_POLISHED_DRILL_ENGINE", "Ruby-Polished Drill Engine", "CRAFTER", 250, 30),
           ("SAPPHIRE_POLISHED_DRILL_ENGINE", "Sapphire-Polished Drill Engine", "DISPENSER", 400, 50),
           ("AMBER_POLISHED_DRILL_ENGINE", "Amber-Polished Drill Engine", "OBSERVER", 600, 100)]
for iid, name, mat, ms, mf in ENGINES:
    item(iid, name, mat, ref_rarity(iid, "RARE"), "MATERIAL",
         lore=["&8Drill Part", "", "&6+%d ⸕ Mining Speed" % ms, "&6+%d ☘ Mining Fortune" % mf, ""]
         + wrap("Fit it into the &aDrill Engine &7slot at the &2Drill Mechanic&7."),
         extra={"drillPart": "engine", "speed": ms, "fortune": mf}, glow=True)
TANKS = [("MITHRIL_FUEL_TANK", "Mithril-Infused Fuel Tank", 10000, 2),
         ("TITANIUM_FUEL_TANK", "Titanium-Infused Fuel Tank", 25000, 4),
         ("GEMSTONE_FUEL_TANK", "Gemstone Fuel Tank", 50000, 6),
         ("PERFECTLY_CUT_FUEL_TANK", "Perfectly-Cut Fuel Tank", 100000, 10)]
for iid, name, cap, cd in TANKS:
    item(iid, name, "CAULDRON", ref_rarity(iid, "RARE"), "MATERIAL",
         lore=["&8Drill Part", "", "&2%s &7max fuel" % format(cap, ","), "&a-%d%% &7Pickaxe Ability cooldown" % cd, ""]
         + wrap("Fit it into the &aFuel Tank &7slot at the &2Drill Mechanic&7."),
         extra={"drillPart": "tank", "capacity": cap, "cooldownCut": cd}, glow=True)
MODULES = [
    ("GOBLIN_OMELETTE", "Goblin Omelette", "PUMPKIN_PIE", "A &a25% &7chance that a block costs no fuel.", {"fuelSave": 0.25}),
    ("GOBLIN_OMELETTE_PESTO", "Pesto Goblin Omelette", "PUMPKIN_PIE", "A &a75% &7chance that a block costs no fuel.", {"fuelSave": 0.75}),
    ("GOBLIN_OMELETTE_SPICY", "Spicy Goblin Omelette", "PUMPKIN_PIE", "Earn &a+20% &7more powder from every source.", {"powder": 20}),
    ("GOBLIN_OMELETTE_SUNNY_SIDE", "Sunny Side Goblin Omelette", "PUMPKIN_PIE", "Grants &6+50 ☘ Mining Fortune&7, but the drill burns &cfour times &7the fuel.", {"fortune": 50, "fuelMult": 4}),
    ("GOBLIN_OMELETTE_BLUE_CHEESE", "Blue Cheese Goblin Omelette", "PUMPKIN_PIE", "Adds &a+1 level &7to every Heart of the Mountain perk you have unlocked.", {"perkLevels": 1}),
    ("TUNGSTEN_KEYCHAIN", "Tungsten Regulator", "TRIPWIRE_HOOK", "Lowers your &c♨ Heat &7or &b❄ Cold &7by &a1 &7for every &a500 &7fuel burned.", {"tempPer": 500}),
    ("STARFALL_SEASONING", "Starfall Seasoning", "SUGAR", "Grants &6+25 ⸕ Mining Speed &7and &6+10 ☘ Mining Fortune&7, doubled within &a20 &7blocks of a Fallen Star.", {"speed": 25, "fortune": 10, "starDouble": 20}),
]
for iid, name, mat, desc, ex in MODULES:
    ex = dict(ex)
    ex["drillPart"] = "module"
    item(iid, name, mat, ref_rarity(iid, "RARE"), "MATERIAL",
         lore=["&8Drill Part", ""] + wrap(desc) + [""] + wrap("Fit it into the &aUpgrade Module &7slot at the &2Drill Mechanic&7."),
         extra=ex, glow=True)
item("DIVAN_POWDER_COATING", "Varnas Powder Coating", "GLOWSTONE_DUST", "LEGENDARY", "MATERIAL",
     lore=wrap("Apply it to a drill at the &2Drill Mechanic &7for &6+500 ⸕ Mining Speed&7. One coat per drill."),
     extra={"coating": 500}, glow=True)
FUELS = [("VOLTA", "Charge Cell", "LIGHTNING_ROD", 10000, "Crackles with stored power."),
         ("OIL_BARREL", "Oil Barrel", "BARREL", 10000, "Thick, black and very flammable."),
         ("BIOFUEL", "Biofuel", "GREEN_DYE", 3000, "Brewed from plants; burns clean.")]
for iid, name, mat, amt, desc in FUELS:
    item(iid, name, mat, ref_rarity(iid, "RARE"), "MATERIAL",
         lore=["&8Machine Fuel", ""] + wrap("Adds &2+%s Fuel &7to a drill at the &2Drill Mechanic&7." % format(amt, ",")) + ["", "&8" + desc],
         extra={"fuel": amt})
item("DRILL_ENGINE", "Drill Motor", "PISTON", "RARE", lore=wrap("The beating heart of every drill. Forged at the Forge."))
item("FUEL_TANK", "Fuel Canister", "CAULDRON", "RARE", lore=wrap("Holds the fuel that keeps a drill turning."))

# ----------------------------------------------------------------------------------------------
# Metals, refined metals, plates
# ----------------------------------------------------------------------------------------------
item("MITHRIL_ORE", "Mithril", "PRISMARINE_CRYSTALS", "COMMON", lore=wrap("A blue-green metal the dwarves prize above gold."), tags=["bazaar"])
item("TITANIUM_ORE", "Titanium", "IRON_NUGGET", "RARE", lore=wrap("Bright, light and stubborn. Appears where mithril has been worked."), tags=["bazaar"])
item("GLACITE", "Rimestone", "PACKED_ICE", "COMMON", lore=wrap("Ice so old and so cold it has turned to stone."), tags=["bazaar"])
item("UMBER", "Umber", "BRICK", "COMMON", lore=wrap("Earthy brown ore from the frozen tunnels."), tags=["bazaar"])
item("TUNGSTEN", "Tungsten", "NETHERITE_SCRAP", "COMMON", lore=wrap("Dense grey ore from the frozen tunnels."), tags=["bazaar"])
item("HARD_STONE", "Hard Stone", "STONE", "COMMON", lore=wrap("Compressed rock from the crystal caverns."), tags=["bazaar"])
item("REFINED_MITHRIL", "Refined Mithril", "PRISMARINE_SHARD", "EPIC", lore=wrap("Mithril purified at the Forge."), glow=True, tags=["bazaar"])
item("REFINED_TITANIUM", "Refined Titanium", "IRON_INGOT", "LEGENDARY", lore=wrap("Titanium purified at the Forge."), glow=True, tags=["bazaar"])
item("REFINED_DIAMOND", "Refined Diamond", "DIAMOND", "EPIC", lore=wrap("Diamond pressed until it is flawless."), glow=True, tags=["bazaar"])
item("REFINED_UMBER", "Refined Umber", "BRICK", "EPIC", lore=wrap("Umber purified at the Forge."), glow=True, tags=["bazaar"])
item("REFINED_TUNGSTEN", "Refined Tungsten", "NETHERITE_SCRAP", "EPIC", lore=wrap("Tungsten purified at the Forge."), glow=True, tags=["bazaar"])
item("GOLDEN_PLATE", "Golden Plate", "LIGHT_WEIGHTED_PRESSURE_PLATE", "RARE", lore=wrap("A thin sheet of gold, used in drill making."), glow=True)
item("MITHRIL_PLATE", "Mithril Plate", "HEAVY_WEIGHTED_PRESSURE_PLATE", "RARE", lore=wrap("Layered mithril and titanium, beaten flat."), glow=True)
item("TUNGSTEN_PLATE", "Tungsten Plate", "POLISHED_BLACKSTONE_PRESSURE_PLATE", "RARE", lore=wrap("A heavy plate of refined tungsten."), glow=True)
item("UMBER_PLATE", "Umber Plate", "CRIMSON_PRESSURE_PLATE", "RARE", lore=wrap("A warm plate of refined umber."), glow=True)
item("PERFECT_PLATE", "Perfect Plate", "LIGHT_WEIGHTED_PRESSURE_PLATE", "LEGENDARY", lore=wrap("Three plates, fused into one that never bends."), glow=True)
item("GLACITE_JEWEL", "Rimestone Jewel", "HEART_OF_THE_SEA", "RARE", lore=wrap("A frozen gem that never melts."))
item("GLACITE_AMALGAMATION", "Rimestone Amalgam", "BLUE_ICE", "EPIC", lore=wrap("Rimestone fused with the four frozen-tunnel gems."), glow=True)
item("GEMSTONE_MIXTURE", "Gemstone Mixture", "GUNPOWDER", "RARE", lore=wrap("Fine gems bound together with something very sticky."), glow=True)
item("GEMSTONE_CHAMBER", "Gemstone Chamber", "AMETHYST_CLUSTER", "EPIC", lore=wrap("Unlocks an extra gemstone slot on Varnas gear."))
item("GLOSSY_GEMSTONE", "Glossy Gemstone", "AMETHYST_SHARD", "EPIC", lore=wrap("Its facets throw back a thousand tiny reflections. Found while mining gems during a Mining Jubilee."), glow=True)
item("REFINED_MINERAL", "Refined Mineral", "GLOW_INK_SAC", "RARE", lore=wrap("Comes out of the rock already pure. Found while mining ores during a Mining Jubilee."), glow=True)
item("STARFALL", "Starfall", "FIREWORK_STAR", "RARE", lore=wrap("A shard of a fallen star, still faintly warm."), tags=["bazaar"])
item("TREASURITE", "Hoardite", "RAW_GOLD", "EPIC", lore=wrap("Treasure hoarders hide it; miners take it."), tags=["bazaar"])
item("PLASMA", "Plasma", "ECHO_SHARD", "RARE", lore=wrap("Contained starfire. Handle with gloves."), tags=["bazaar"])
item("MAGMA_CORE", "Magma Core", "MAGMA_CREAM", "RARE", lore=wrap("The molten heart of the deepest caverns."), tags=["bazaar"])
item("CORLEONITE", "Gangsterite", "GOLD_NUGGET", "RARE", lore=wrap("Taken from the treasure gang that runs the mithril deposits."), tags=["bazaar"])
item("DIVAN_FRAGMENT", "Varnas Fragment", "GOLD_NUGGET", "EPIC", lore=wrap("A shard of the old dwarven king's armour."), glow=True)
item("DIVAN_ALLOY", "Varnas Alloy", "GOLD_INGOT", "LEGENDARY", lore=wrap("The metal of the old dwarven king's tools. Forge it into a drill."), glow=True)
item("PRECURSOR_APPARATUS", "Precursor Apparatus", "CLOCK", "EPIC", lore=wrap("A machine from a city that no longer exists."))
item("SLUDGE_JUICE", "Sludge Juice", "SLIME_BALL", "UNCOMMON", lore=wrap("It drips. It smells. Merchants still want it."), tags=["bazaar"])
item("YOGGIE", "Cinder Morsel", "FIRE_CHARGE", "UNCOMMON", lore=wrap("Still smouldering. Do not eat."), tags=["bazaar"])
item("WORM_MEMBRANE", "Worm Membrane", "ROTTEN_FLESH", "UNCOMMON", lore=wrap("Tough skin shed by cave worms."), tags=["bazaar"])
item("JUNGLE_HEART", "Jungle Heart", "CREAKING_HEART", "LEGENDARY", lore=wrap("Beats slowly in your hand."))
item("ASCENSION_ROPE", "Ascension Rope", "LEAD", "RARE", lore=["&8Consumed on use", ""] + wrap("Hauls you back to the island's entrance from anywhere in the crystal caverns or frozen tunnels.") + ["", "&eRight-click to use!"])
item("WISHING_COMPASS", "Wishing Compass", "COMPASS", "UNCOMMON", lore=["&8Consumed on use", ""] + wrap("Points toward the nearest structure of the crystal caverns you have not finished yet.") + ["", "&eRight-click to use!"])
for iid, name, mat in [("CONTROL_SWITCH", "Control Switch", "LEVER"), ("ELECTRON_TRANSMITTER", "Electron Transmitter", "REDSTONE_TORCH"),
                       ("FTX_3070", "Cogitator Card", "COMPARATOR"), ("ROBOTRON_REFLECTOR", "Automaton Reflector", "DAYLIGHT_DETECTOR"),
                       ("SUPERLITE_MOTOR", "Featherweight Motor", "TARGET"), ("SYNTHETIC_HEART", "Synthetic Heart", "RESIN_BLOCK")]:
    item(iid, name, mat, "RARE", lore=wrap("A salvaged part of a precursor automaton."))
item("JUNGLE_KEY", "Jungle Key", "TRIPWIRE_HOOK", "EPIC", lore=wrap("Opens the door of the temple deep in the jungle."))
item("BOB_OMB", "Blast Bomb", "TNT", "RARE", lore=wrap("Blows a hole in the crystal caverns and keeps half of what it breaks.") + ["", "&eRight-click to use!"])
item("PREHISTORIC_EGG", "Prehistoric Egg", "SNIFFER_EGG", "COMMON", lore=wrap("Carry it and walk. Something inside is waiting to hatch."))
for iid, name, mat, rar in [("GOBLIN_EGG", "Goblin Egg", "EGG", "RARE"), ("GOBLIN_EGG_GREEN", "Green Goblin Egg", "TURTLE_EGG", "UNCOMMON"),
                            ("GOBLIN_EGG_RED", "Red Goblin Egg", "BROWN_EGG", "RARE"), ("GOBLIN_EGG_YELLOW", "Yellow Goblin Egg", "EGG", "RARE"),
                            ("GOBLIN_EGG_BLUE", "Blue Goblin Egg", "BLUE_EGG", "RARE")]:
    item(iid, name, mat, rar, lore=wrap("Laid by goblins, cooked by the brave."), tags=["bazaar"])
item("SUSPICIOUS_SCRAP", "Suspicious Scrap", "SUSPICIOUS_GRAVEL", "RARE", lore=wrap("Heavy rubble from the frozen mineshafts. Put it in the Fossil Excavator to see what is inside."), tags=["bazaar"])
FOSSILS = [("SPINE_FOSSIL", "Spine Fossil", "BONE"), ("CLUBBED_FOSSIL", "Clubbed Fossil", "BONE_BLOCK"),
           ("UGLY_FOSSIL", "Ugly Fossil", "SKELETON_SKULL"), ("FOOTPRINT_FOSSIL", "Footprint Fossil", "COARSE_DIRT"),
           ("WEBBED_FOSSIL", "Webbed Fossil", "COBWEB"), ("TUSK_FOSSIL", "Tusk Fossil", "POINTED_DRIPSTONE"),
           ("CLAW_FOSSIL", "Claw Fossil", "BONE"), ("HELIX", "Spiral Fossil", "NAUTILUS_SHELL")]
for iid, name, mat in FOSSILS:
    item(iid, name, mat, "LEGENDARY", lore=wrap("The remains of something enormous, dug out of the frozen tunnels."))
# research 2.14: the excavator's pool also holds the Fossil-the-Fish curio (NEU: a SPECIAL cooked fish, a museum
# "special" item - not a pet) and, on 1 roll in 500,000, the Fossil Dye (NEU DYE_FOSSIL, LEGENDARY, #866F12).
item("FOSSIL_THE_FISH", "Stonefin the Fish", "COD", "SPECIAL",
     lore=wrap("Older than the mountain and never once changed, because he was already perfect."), tags=["museum"])
item("DYE_FOSSIL", "Fossil Dye", "BROWN_DYE", "LEGENDARY", "COSMETIC",
     lore=wrap("Turns an armor piece the colour of old bone, &8#866F12&7.") + ["", "&8Apply it to dyeable armor."], tags=["museum"])
item("UMBER_KEY", "Umber Key", "TRIAL_KEY", "EPIC", lore=wrap("Unlocks an &6Umber &7frozen corpse."), tags=["corpse_key"])
item("TUNGSTEN_KEY", "Tungsten Key", "TRIAL_KEY", "EPIC", lore=wrap("Unlocks a &8Tungsten &7frozen corpse."), tags=["corpse_key"])
item("SKELETON_KEY", "Skeleton Key", "OMINOUS_TRIAL_KEY", "LEGENDARY", lore=wrap("Unlocks a &dVanguard &7frozen corpse."), tags=["corpse_key"])
item("SECRET_RAILROAD_PASS", "Rimestone Rail Pass", "PAPER", "SPECIAL", lore=wrap("Lets you ride the old railway down into the frozen tunnels."), glow=True)
item("BEJEWELED_HANDLE", "Bejeweled Handle", "STICK", "RARE", lore=wrap("A handle studded with rimestone jewels."), glow=True)
item("MATCH_STICKS", "Match-Sticks", "STICK", "RARE", lore=wrap("Strike one. Get warm."))
item("PORTABLE_CAMPFIRE", "Portable Campfire", "CAMPFIRE", "RARE",
     lore=["&8Consumed on use", ""] + wrap("Light it in the frozen tunnels to drop your &b❄ Cold &7by &a50&7. One every &a5 &7minutes.") + ["", "&eRight-click to use!"])
CHISELS = [("CHISEL", "Chisel", 16), ("REINFORCED_CHISEL", "Reinforced Chisel", 18), ("GLACITE_CHISEL", "Rimestone-Plated Chisel", 20), ("PERFECT_CHISEL", "Perfect Chisel", 22)]
for iid, name, ch in CHISELS:
    item(iid, name, "BRUSH", ref_rarity(iid, "COMMON"), "CHISEL",
         lore=wrap("Use it in the &aFossil Excavator &7with a &9Suspicious Scrap &7to dig.") + ["", "&7Charges: &a%d" % ch],
         extra={"charges": ch}, gem_slots=ref_gem_slots(iid))
for iid, name, ms, mf, heat, cold, spread in [("MITHRIL_LANTERN", "Mithril Lantern", 40, 10, 0, 0, 0),
                                               ("TITANIUM_LANTERN", "Titanium Lantern", 60, 15, 5, 0, 0),
                                               ("GLACITE_LANTERN", "Rimestone Lantern", 80, 20, 10, 5, 0),
                                               ("WILL_O_WISP", "Will-o'-wisp", 100, 25, 20, 10, 2.5)]:
    lines = ["&8Mining Deployable", ""] + wrap(
        "Sets down an orb for &a5m &7that buffs up to &a%d &7players within &a30 &7blocks. Costs &b50%% &7of your max mana." % (10 if iid == "WILL_O_WISP" else 5)) + ["", "&aOrb Buff:"]
    lines.append("&8• &6+%d ⸕ Mining Speed" % ms)
    lines.append("&8• &6+%d ☘ Mining Fortune" % mf)
    if heat:
        lines.append("&8• &c+%d ♨ Heat Resistance" % heat)
    if cold:
        lines.append("&8• &b+%d ❄ Cold Resistance" % cold)
    if spread:
        lines.append("&8• &e+%s ▚ Gemstone Spread" % spread)
    lines += ["", "&8Only one deployable buff applies."]
    item(iid, name, "LANTERN" if iid != "GLACITE_LANTERN" else "SOUL_LANTERN", ref_rarity(iid, "RARE"), "DEPLOYABLE", lore=lines,
         abilities=[{"id": "mining_deploy", "name": "Deploy", "trigger": "RIGHT_CLICK", "mana": 0.5, "cooldown": 5,
                     "lore": ["&7Sets the orb down where you stand."]}],
         extra={"deployable": {"speed": ms, "fortune": mf, "heat": heat, "cold": cold, "spread": spread,
                               "players": 10 if iid == "WILL_O_WISP" else 5, "radius": 30, "seconds": 300}})
item("ROYAL_PIGEON", "Royal Pigeon", "FEATHER", "LEGENDARY", "MATERIAL",
     lore=wrap("Sends word to the King: opens your commissions from anywhere."),
     abilities=[{"id": "mining_royal_pigeon", "name": "Send for the King", "trigger": "RIGHT_CLICK", "mana": 0, "cooldown": 5,
                 "lore": ["&7Opens the &aCommissions &7menu."]}], tags=["soulbound"], glow=True, max_stack=1)
for iid, name, warp in [("FORGE_TRAVEL_SCROLL", "Travel Scroll to the Forge", "forge"),
                        ("CRYSTAL_HOLLOWS_TRAVEL_SCROLL", "Travel Scroll to the Crystal Caverns", "crystals"),
                        ("BASE_CAMP_TRAVEL_SCROLL", "Travel Scroll to the Base Camp", "base_camp"),
                        ("CRYSTAL_NUCLEUS_TRAVEL_SCROLL", "Travel Scroll to the Crystal Nucleus", "nucleus"),
                        ("DWARVEN_MINES_TRAVEL_SCROLL", "Travel Scroll to the Dwarven Mines", "mines_scroll")]:
    item(iid, name, "MAP", "RARE", "TRAVEL_SCROLL",
         lore=wrap("Use it to add this place to your fast travel list.") + ["", "&eRight-click to consume!"], extra={"warp": warp})

# ----------------------------------------------------------------------------------------------
# Gemstones: 12 types x 5 qualities, plus crystals
# ----------------------------------------------------------------------------------------------
GEMS = load(os.path.join("neu-repo", "constants", "gemstones.json"))["gemstoneTypes"]
GEM_INFO = {  # symbol, name colour, glass colour, stat key
    "RUBY": ("❤", "c", "RED", "HEALTH"),
    "AMBER": ("⸕", "6", "ORANGE", "MINING_SPEED"),
    "SAPPHIRE": ("✎", "b", "LIGHT_BLUE", "INTELLIGENCE"),
    "JADE": ("☘", "a", "LIME", "MINING_FORTUNE"),
    "AMETHYST": ("❈", "5", "PURPLE", "DEFENSE"),
    "TOPAZ": ("✧", "e", "YELLOW", "PRISTINE"),
    "JASPER": ("❁", "d", "MAGENTA", "STRENGTH"),
    "OPAL": ("❂", "f", "WHITE", "TRUE_DEFENSE"),
    "ONYX": ("☠", "8", "BLACK", "CRIT_DAMAGE"),
    "AQUAMARINE": ("☂", "3", "BLUE", "FISHING_SPEED"),
    "CITRINE": ("☘", "6", "BROWN", "FORAGING_FORTUNE"),
    "PERIDOT": ("☘", "2", "GREEN", "FARMING_FORTUNE"),
}
QUALITIES = ["ROUGH", "FLAWED", "FINE", "FLAWLESS", "PERFECT"]
QUALITY_RARITY = {"ROUGH": "COMMON", "FLAWED": "UNCOMMON", "FINE": "RARE", "FLAWLESS": "EPIC", "PERFECT": "LEGENDARY"}
RARITY_CODE = {"COMMON": "f", "UNCOMMON": "a", "RARE": "9", "EPIC": "5", "LEGENDARY": "6", "MYTHIC": "d", "DIVINE": "b", "SPECIAL": "c"}
QUALITY_TEXT = {
    "ROUGH": "Chipped straight out of a gemstone vein.",
    "FLAWED": "Cleaner than a rough gem, still far from perfect.",
    "FINE": "A well-cut gem with few faults.",
    "FLAWLESS": "Not a single fault anywhere in it.",
    "PERFECT": "Cut at the Forge into the finest gem there is.",
}
for gem, (sym, col, glass, stat) in GEM_INFO.items():
    gname = gem.title()
    stat_name = GEMS[gem]["statName"]
    for qi, q in enumerate(QUALITIES):
        iid = "%s_%s_GEM" % (q, gem)
        rar = QUALITY_RARITY[q]
        vals = GEMS[gem]["stats"][q]
        table = " &8/ ".join("&%s%s" % (RARITY_CODE[r], ("%g" % vals[r])) for r in ["COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC"] if r in vals)
        lore = ["&8Gemstone", ""] + wrap(QUALITY_TEXT[q]) + [""] + wrap("Set into a matching slot at the &aGemstone Grinder &7for &%s%s %s&7:" % (col, sym, stat_name)) + [" " + table, "&8(by the rarity of the item it sits in)"]
        mat = "%s_STAINED_GLASS_PANE" % glass if q == "ROUGH" else "%s_STAINED_GLASS" % glass
        sell = ref_sell(iid)
        item(iid, "&%s%s &%s%s %s Gemstone" % (col, sym, RARITY_CODE[rar], q.title(), gname), mat, rar, "GEMSTONE",
             lore=lore, extra={"gem": gem, "quality": q, "qualityIndex": qi, "stat": stat},
             glow=(qi >= 2), tags=["bazaar", "gemstone"], sell=sell)
    item("%s_CRYSTAL" % gem, "&%s%s Crystal" % (col, gname), "END_CRYSTAL", "SPECIAL", "MATERIAL",
         lore=wrap("A living crystal of %s. The Crystal Nucleus wants it, and the Forge can cut it into a Perfect gem." % gname),
         extra={"crystal": gem})

# ----------------------------------------------------------------------------------------------
# Block table
# ----------------------------------------------------------------------------------------------
SURFACE = ["hub", "gold_mine", "deep_caverns"]
BLOCKS = []


def block(bid, name, kind, islands, materials, strength, bp, drop, xp, dmin=1, dmax=1, powder=None, pamt=0,
          quality=1, fuel=1, gem=None, area_only=False):
    BLOCKS.append({
        "id": bid, "name": name, "kind": kind, "islands": islands, "materials": [m.upper() for m in materials],
        "strength": strength, "breakingPower": bp, "drop": drop, "min": dmin, "max": dmax, "xp": xp,
        "powder": powder, "powderAmount": pamt, "quality": quality, "fuel": fuel, "gem": gem, "areaOnly": area_only,
    })


GEM_BLOCK = {  # strength, bp, islands, xp (TUNE where the research says "varies"), quality, fuel
    # quality = mineshaft pity points (audit 2026-10-06 M10, SkyblockMoney/work/mining.md 3f): every gemstone 8
    "RUBY": (2300, 6, ["crystal_hollows", "glacite_tunnels", "mineshaft"], 70, 8, 1),
    "AMBER": (3000, 7, ["crystal_hollows", "glacite_tunnels", "mineshaft"], 70, 8, 1),
    "AMETHYST": (3000, 7, ["crystal_hollows", "glacite_tunnels", "mineshaft"], 70, 8, 1),
    "JADE": (3000, 7, ["crystal_hollows", "glacite_tunnels", "mineshaft"], 70, 8, 1),
    "SAPPHIRE": (3000, 7, ["crystal_hollows", "glacite_tunnels", "mineshaft"], 70, 8, 1),
    "OPAL": (3000, 7, ["mineshaft"], 70, 8, 1),
    "TOPAZ": (3800, 8, ["crystal_hollows", "glacite_tunnels", "mineshaft"], 70, 8, 1),
    "JASPER": (4800, 9, ["crystal_hollows", "mineshaft"], 70, 8, 1),
    "ONYX": (5200, 9, ["glacite_tunnels", "mineshaft"], 100, 8, 2),
    "AQUAMARINE": (5200, 9, ["glacite_tunnels", "mineshaft"], 100, 8, 2),
    "CITRINE": (5200, 9, ["glacite_tunnels", "mineshaft"], 100, 8, 2),
    "PERIDOT": (5200, 9, ["glacite_tunnels", "mineshaft"], 100, 8, 2),
}
for gem, (stren, bp, isl, xp, qual, fuel) in GEM_BLOCK.items():
    glass = GEM_INFO[gem][2].lower()
    block("gem_" + gem.lower(), gem.title() + " Gemstone", "GEMSTONE", isl, [glass + "_stained_glass", glass + "_stained_glass_pane"],
          stren, bp, "ROUGH_%s_GEM" % gem, xp, powder="GEMSTONE", pamt=2, quality=qual, fuel=fuel, gem=gem)

# Hard stone first in the hollows, because there gray wool / cyan terracotta ARE hard stone (NEU).
block("hard_stone", "Hard Stone", "HARD_STONE", ["crystal_hollows"],
      ["stone", "black_terracotta", "gray_terracotta", "light_gray_terracotta", "brown_terracotta", "cyan_terracotta", "light_gray_wool", "gray_wool"],
      50, 4, "HARD_STONE", 2)
block("hard_stone_ms", "Hard Stone", "HARD_STONE", ["mineshaft", "glacite_tunnels"],
      ["stone", "black_terracotta", "gray_terracotta", "light_gray_terracotta", "light_gray_wool"], 50, 4, "HARD_STONE", 2)
# Rimestone tunnel metals before mithril/ores (cobblestone and clay ARE tungsten there).
block("glacite", "Rimestone", "METAL", ["glacite_tunnels", "mineshaft"], ["packed_ice"], 6000, 9, "GLACITE", 150,
      powder="GLACITE", pamt=2, quality=4, fuel=3)
block("umber", "Umber", "METAL", ["glacite_tunnels", "mineshaft"], ["brown_terracotta", "terracotta", "red_sandstone", "smooth_red_sandstone"],
      5600, 9, "UMBER", 150, powder="GLACITE", pamt=2, quality=4, fuel=3)
block("tungsten", "Tungsten", "METAL", ["glacite_tunnels", "mineshaft"], ["cobblestone", "clay"], 5600, 9, "TUNGSTEN", 150,
      powder="GLACITE", pamt=2, quality=4, fuel=3)
# Mithril: three grades (research 2.2: 500 / 800 / 1500)
MITHRIL_ISL = ["dwarven_mines", "glacite_tunnels", "mineshaft"]
block("mithril_gray", "Mithril", "METAL", MITHRIL_ISL, ["gray_wool", "cyan_terracotta"], 500, 4, "MITHRIL_ORE", 45, powder="MITHRIL", pamt=2, quality=2)
block("mithril_prismarine", "Mithril", "METAL", MITHRIL_ISL + ["crystal_hollows"], ["prismarine", "prismarine_bricks", "dark_prismarine"], 800, 4,
      "MITHRIL_ORE", 45, powder="MITHRIL", pamt=2, quality=2)
block("mithril_blue", "High-Grade Mithril", "METAL", MITHRIL_ISL + ["crystal_hollows"], ["light_blue_wool"], 1500, 4, "MITHRIL_ORE", 45,
      dmin=2, dmax=2, powder="MITHRIL", pamt=2, quality=2)
block("titanium", "Titanium", "METAL", ["dwarven_mines", "glacite_tunnels", "mineshaft"], ["polished_diorite"], 2000, 5, "TITANIUM_ORE", 100,
      powder="MITHRIL", pamt=5, quality=8)   # pity points 8 (audit M10)
# Pure ore blocks (Dwarven Mines / hollows / Obsidian Sanctuary)
PURE = [("coal_block", "Pure Coal", "COAL", 15), ("iron_block", "Pure Iron", "IRON_INGOT", 15), ("gold_block", "Pure Gold", "GOLD_INGOT", 20),
        ("lapis_block", "Pure Lapis", "INK_SACK:4", 15), ("redstone_block", "Pure Redstone", "REDSTONE", 15),
        ("emerald_block", "Pure Emerald", "EMERALD", 15), ("diamond_block", "Pure Diamond", "DIAMOND", 15), ("quartz_block", "Pure Quartz", "QUARTZ", 15)]
for mat, name, drop, xp in PURE:
    block("pure_" + mat.replace("_block", ""), name, "PURE_ORE", ["dwarven_mines", "crystal_hollows", "mineshaft"], [mat], 600, 3, drop, xp, quality=2)
block("diamond_block_dc", "Block of Diamond", "ORE", ["deep_caverns"], ["diamond_block"], 600, 3, "DIAMOND_BLOCK", 15)
# Vanilla ores everywhere mining happens
ALL = ["hub", "gold_mine", "deep_caverns", "dwarven_mines", "crystal_hollows", "glacite_tunnels", "mineshaft"]
ORES = [("coal_ore", "Coal Ore", "COAL", 5, 1, 1, 1), ("iron_ore", "Iron Ore", "IRON_INGOT", 5, 1, 1, 2),
        ("gold_ore", "Gold Ore", "GOLD_INGOT", 6, 1, 1, 3), ("lapis_ore", "Lapis Ore", "INK_SACK:4", 7, 4, 8, 2),
        ("redstone_ore", "Redstone Ore", "REDSTONE", 7, 4, 5, 3), ("emerald_ore", "Emerald Ore", "EMERALD", 9, 1, 1, 3),
        ("diamond_ore", "Diamond Ore", "DIAMOND", 10, 1, 1, 3)]
for mat, name, drop, xp, mn, mx, bp in ORES:
    block(mat, name, "ORE", ALL, [mat, "deepslate_" + mat], 30, bp, drop, xp, dmin=mn, dmax=mx)
block("nether_quartz_ore", "Nether Quartz Ore", "ORE", ALL, ["nether_quartz_ore"], 30, 3, "QUARTZ", 5)
block("obsidian", "Obsidian", "OBSIDIAN", ["deep_caverns", "dwarven_mines", "crystal_hollows"], ["obsidian"], 500, 4, "OBSIDIAN", 20)
block("glowstone", "Glowstone", "STANDARD", ["deep_caverns", "dwarven_mines"], ["glowstone"], 30, 1, "GLOWSTONE_DUST", 7, dmin=2, dmax=4)
# Plain stone: only inside ore areas (ores.* / the hub's coal_mine) so towns are never dug up.
block("stone", "Stone", "STANDARD", SURFACE + ["dwarven_mines"], ["stone", "andesite", "diorite", "granite", "deepslate", "tuff"], 15, 1, "COBBLESTONE", 1, area_only=True)
block("cobblestone", "Cobblestone", "STANDARD", SURFACE + ["dwarven_mines"], ["cobblestone"], 20, 1, "COBBLESTONE", 1, area_only=True)
block("gravel", "Gravel", "STANDARD", SURFACE, ["gravel"], 18, 0, "GRAVEL", 4, area_only=True)
block("ice", "Ice", "STANDARD", ["dwarven_mines", "glacite_tunnels"], ["ice"], 15, 0, "ICE", 1, area_only=True)

# ----------------------------------------------------------------------------------------------
# Heart of the Mountain (evaluate NEU's lisp so Java gets plain tables)
# ----------------------------------------------------------------------------------------------


def tokenize(s):
    return re.findall(r'\(|\)|"[^"]*"|[^\s()]+', s)


def parse(tokens):
    t = tokens.pop(0)
    if t == "(":
        out = []
        while tokens[0] != ")":
            out.append(parse(tokens))
        tokens.pop(0)
        return out
    return t


def ev(x, env):
    if isinstance(x, list):
        op = x[0]
        a = lambda i: ev(x[i], env)
        if op == "if":
            return a(2) if a(1) else a(3)
        args = [ev(y, env) for y in x[1:]]
        if op == "pow": return math.pow(args[0], args[1])
        if op == "+": return sum(args)
        if op == "-": return args[0] - args[1] if len(args) > 1 else -args[0]
        if op == "*":
            r = 1
            for v in args: r *= v
            return r
        if op == "/": return args[0] / args[1]
        if op == "lt": return args[0] < args[1]
        if op == "gt": return args[0] > args[1]
        if op == "=": return args[0] == args[1]
        if op == "floor": return math.floor(args[0])
        if op == "round": return math.floor(args[0] + 0.5)
        if op == "round-decimals": return round(args[0], int(args[1]))
        if op == "min": return min(args)
        if op == "max": return max(args)
        if op == "id": return args[0]
        if op == "list.new": return args
        if op == "list.at": return args[0][int(args[1])]
        raise ValueError("op " + op)
    if x.startswith('"'):
        s = x[1:-1]
        try:
            return float(s)
        except ValueError:
            return s
    if x.startswith(":"):
        return x[1:]
    try:
        return float(x)
    except ValueError:
        if x == "true": return True
        if x in env: return env[x]
        if x.isupper(): return x          # a bare constant such as MITHRIL
        raise KeyError(x)


def evs(expr, env):
    if expr is None or expr == "":
        return None
    return ev(parse(tokenize(expr)), env)


HOTM_SRC = load(os.path.join("neu-repo", "constants", "hotmlayout.json"))["hotm"]["perks"]
HOTM_XP = load(os.path.join("neu-repo", "constants", "leveling.json"))["HOTM"]
PERK_NAMES = {  # reference perk name -> our name (only the invented ones change)
    "titanium_insanium": "Titanium Fever", "pickobulus": "Pickblast", "luck_of_the_cave": "Cave Luck", "sky_mall": "Deep Market",
    "speedy_mineman": "Speedy Miner", "fortunate_mineman": "Fortunate Miner", "seasoned_mineman": "Seasoned Miner",
    "mole": "Burrower",
}
# Our own one-line descriptions ({v} = value at the level; {v2} second value)
PERK_DESC = {
    "mining_speed": "Grants &6+{v} ⸕ Mining Speed&7.",
    "mining_fortune": "Grants &6+{v} ☘ Mining Fortune&7.",
    "titanium_insanium": "Mining &2Mithril &7has a &a{v}% &7chance to turn the block into &fTitanium&7.",
    "luck_of_the_cave": "Rare finds in the mines happen &a{v}% &7more often: treasure goblins, fallen stars and powder ghasts.",
    "efficient_miner": "Grants &e+{v} ▚ Mining Spread&7.",
    "quick_forge": "Forging takes &a{v}% &7less time.",
    "old_school": "Grants &6+{v} ☘ Ore Fortune&7.",
    "professional": "Gain &6+{v} ⸕ Mining Speed &7while mining gemstones.",
    "mole": "Grants &e+{v} ▚ Mining Spread &7while mining Hard Stone.",
    "gem_lover": "Grants &6+{v} ☘ Gemstone Fortune&7.",
    "seasoned_mineman": "Grants &3+{v} ☯ Mining Wisdom&7.",
    "blockhead": "Grants &6+{v} ☘ Block Fortune&7.",
    "subterranean_fisher": "Grants &b+{v} ☂ Fishing Speed &7and &3+{v2} α Sea Creature Chance &7on mining islands.",
    "keep_it_cool": "Grants &c+{v} ♨ Heat Resistance&7.",
    "lonesome_miner": "Your &c❁ Strength&7, &9☣ Crit Chance&7, &9☠ Crit Damage&7, &a❈ Defense &7and &c❤ Health &7are &a{v}% &7higher on mining islands.",
    "great_explorer": "Treasure chests turn up &a{v}% &7more often in the crystal caverns and have &a{v2} &7fewer locks.",
    "powder_buff": "Gain &a+{v}% &7more powder from every source.",
    "speedy_mineman": "Grants &6+{v} ⸕ Mining Speed&7.",
    "fortunate_mineman": "Grants &6+{v} ☘ Mining Fortune&7.",
    "warm_heart": "Grants &b+{v} ❄ Cold Resistance&7.",
    "no_stone_unturned": "Suspicious Scrap turns up &a{v}% &7more often in the frozen mineshafts.",
    "strong_arm": "Gain &6+{v} ⸕ Mining Speed &7while mining dwarven metals.",
    "steady_hand": "Grants &e+{v} ▚ Gemstone Spread &7inside the frozen mineshafts.",
    "surveyor": "Frozen mineshafts open up &a{v}% &7more often while you mine the tunnels.",
    "metal_head": "Grants &6+{v} ☘ Dwarven Metal Fortune&7.",
    "rags_to_riches": "Grants &6+{v} ☘ Mining Fortune &7inside the frozen mineshafts.",
    "eager_adventurer": "Gain &6+{v} ⸕ Mining Speed &7inside the frozen mineshafts.",
    "crystalline": "Mineshafts holding a Gemstone Crystal are &a{v}% &7more likely.",
    "gifts_from_the_departed": "A &a{v}% &7chance to take one extra item from a frozen corpse.",
    "mining_master": "Grants &5+{v} ✧ Pristine&7.",
    "dead_mans_chest": "A &a{v}% &7chance that a mineshaft holds one more frozen corpse.",
    "vanguard_seeker": "Mineshafts holding a Vanguard corpse are &a{v}% &7more likely.",
    "precision_mining": "Mining ores or dwarven metals shows a spark on the block. Aim at it for &6+30% ⸕ Mining Speed&7.",
    "sky_mall": "Every SkyBlock day you get a random mining buff on every mining island.",
    "front_loaded": "Your first &a2,500 &7gemstones each day give &d3x Gemstone Powder&7, &6+150 ☘ Gemstone Fortune &7and &6+250 ⸕ Mining Speed&7.",
    "daily_grind": "Your first commission each day on each mining island pays &a+{v} &7powder of that island's kind.",
    "daily_powder": "The first block you mine each day pays &a+{v} &7powder of its kind.",
    "miners_blessing": "Grants &b+30 ✯ Magic Find &7on every mining island.",
    "mineshaft_mayhem": "Each time you enter a frozen mineshaft you get one random buff for the visit.",
    "mining_speed_boost": "Pickaxe ability: a burst of &6Mining Speed&7.",
    "pickobulus": "Pickaxe ability: throw a blast that mines every ore around where it lands.",
    "anomalous_desire": "Pickaxe ability: rare finds happen far more often for a while.",
    "maniac_miner": "Pickaxe ability: &2+1 Ⓟ Breaking Power &7and stacking fortune for every block, at the cost of mana.",
    "gemstone_infusion": "Pickaxe ability: the gems slotted in your tool count double for a while.",
    "sheer_force": "Pickaxe ability: &e+200 ▚ Mining Spread &7for a while.",
    "core_of_the_mountain": "The heart of the tree. Every level unlocks something bigger.",
}
ABILITIES = {"mining_speed_boost", "pickobulus", "anomalous_desire", "maniac_miner", "gemstone_infusion", "sheer_force"}
# Pickaxe ability numbers per ability level 1..3 (Peak of the Mountain 1 / 2 / 3+). Levels 1-2 are checked below
# against NEU's hotmlayout expressions (potm 1 and 2); level 3 is research 2.7.4. value = the ability's number
# (% speed, Pickblast radius, % rare finds, fortune per block, gem multiplier, Mining Spread), duration and cooldown in s.
ABILITY_NUMBERS = {
    "mining_speed_boost": {"value": [200, 250, 300], "duration": [10, 15, 20], "cooldown": [120, 120, 120]},
    "pickobulus":         {"value": [2, 2, 2],       "duration": [0, 0, 0],    "cooldown": [60, 50, 40]},
    "anomalous_desire":   {"value": [30, 40, 50],    "duration": [30, 30, 30], "cooldown": [120, 110, 100]},
    "maniac_miner":       {"value": [5, 10, 15],     "duration": [25, 30, 35], "cooldown": [120, 120, 120]},
    "gemstone_infusion":  {"value": [2, 2, 2],       "duration": [20, 25, 30], "cooldown": [140, 140, 140]},
    "sheer_force":        {"value": [200, 200, 200], "duration": [20, 25, 30], "cooldown": [120, 120, 120]},
}
# which NEU field means what ("stat" differs per ability)
_NEU_ABILITY_FIELDS = {"statBoost": "value", "statDuration": "duration", "statCooldown": "cooldown"}
_NEU_ABILITY_STAT = {"pickobulus": "cooldown", "anomalous_desire": "value", "maniac_miner": "value", "sheer_force": "duration"}
for _pid, _nums in ABILITY_NUMBERS.items():
    _src = HOTM_SRC[_pid]
    for _k, _expr in _src.items():
        _what = _NEU_ABILITY_FIELDS.get(_k) or (_NEU_ABILITY_STAT.get(_pid) if _k == "stat" else None)
        if not _what or not _expr:
            continue
        for _potm in (1, 2):
            _v = float(evs(_expr, {"potm": _potm, "level": 1, "maxLevel": 1, "hotm": 10}))
            if abs(_v - _nums[_what][_potm - 1]) > 1e-9:
                raise SystemExit("ability %s %s at PotM %d: NEU %s, ours %s" % (_pid, _what, _potm, _v, _nums[_what][_potm - 1]))
perks = []
for pid, p in HOTM_SRC.items():
    mx = int(p["maxLevel"])
    tier = 10 - int(p["y"])
    pw = p.get("powder")
    entry = {"id": pid, "name": PERK_NAMES.get(pid, p["name"]), "x": int(p["x"]), "tier": tier, "maxLevel": mx,
             "ability": pid in ABILITIES, "desc": PERK_DESC.get(pid, "")}
    if pid in ABILITY_NUMBERS:
        entry["abilityValue"] = ABILITY_NUMBERS[pid]["value"]
        entry["abilityDuration"] = ABILITY_NUMBERS[pid]["duration"]
        entry["abilityCooldown"] = ABILITY_NUMBERS[pid]["cooldown"]
    if pid == "core_of_the_mountain":
        costs, powders = [], []
        for lvl in range(0, mx):
            env = {"level": lvl}
            costs.append(int(evs(p["cost"], env)))
            powders.append(evs(pw, env))
        entry["costs"] = costs          # costs[L] = price to go from level L to L+1 (0 = a token)
        entry["powders"] = powders
        entry["powder"] = "MITHRIL"
    else:
        entry["powder"] = pw
        costs = []
        for lvl in range(0, mx):
            if lvl == 0 or not p.get("cost") or p.get("cost") == "0":
                costs.append(0)
            else:
                costs.append(int(math.floor(evs(p["cost"], {"level": lvl}) + 1e-9)))
        entry["costs"] = costs
        for key in [k for k in p if k.startswith("stat")]:
            if key in ("statBoost", "statDuration", "statCooldown") or pid in ABILITIES:
                continue
            vals = []
            for lvl in range(0, mx + 2):
                v = evs(p[key], {"level": lvl, "maxLevel": mx, "hotm": 10, "potm": 1})
                vals.append(round(float(v), 4) if v is not None else 0.0)
            entry["values" if key == "stat" else "values_" + key[4:].lower()] = vals
    perks.append(entry)

HOTM = {
    "xp": HOTM_XP,                          # xp needed for each tier (cumulative sums in Java)
    "tokens": [1, 2, 2, 2, 2, 2, 2, 2, 2, 2],   # (?) research: 1 then +2 per tier
    "skyblockXp": [35, 45, 60, 60, 90, 110, 130, 180, 210, 240],
    "perks": perks,
}

# ----------------------------------------------------------------------------------------------
# Forge recipes (all 120 from NEU)
# ----------------------------------------------------------------------------------------------
COLL = {"Mithril": "MITHRIL_ORE", "Gemstone": "GEMSTONE_COLLECTION", "Glacite": "GLACITE", "Tungsten": "TUNGSTEN",
        "Umber": "UMBER", "Hard Stone": "HARD_STONE", "Sulphur": "SULPHUR_ORE"}
def _coll_max():
    try:
        import json as _j, os as _o
        d = _j.load(open(_o.path.join(_o.path.dirname(__file__), "..", "..", "..", "research", "data", "collections.json"), encoding="utf8"))
        out = {}
        def walk(x):
            if isinstance(x, dict):
                for k, v in x.items():
                    if isinstance(v, dict) and "maxTiers" in v:
                        out[k] = v["maxTiers"]
                    else:
                        walk(v)
        walk(d)
        return out
    except (OSError, ValueError):
        return {}


COLL_MAX = _coll_max()
ROMAN = {"I": 1, "II": 2, "III": 3, "IV": 4, "V": 5, "VI": 6, "VII": 7, "VIII": 8, "IX": 9, "X": 10, "XI": 11, "XII": 12}


def craft_unlock(text):
    parts = []
    t = (text or "").replace("Requires:", "").strip()
    for chunk in [c.strip() for c in t.split("&") if c.strip()]:
        m = re.match(r"HotM (\d+)", chunk)
        if m:
            parts.append("hotm:" + m.group(1))
            continue
        m = re.match(r"(.+) ([IVX]+)$", chunk)
        if m and m.group(1) in COLL:
            cid = COLL[m.group(1)]
            # NEU is newer than research/data/collections.json (Mithril X vs a max of IX there): clamp so the key resolves.
            parts.append("collection:%s:%d" % (cid, min(ROMAN[m.group(2)], COLL_MAX.get(cid, 99))))
    return " & ".join(parts) if parts else None


def cell(s):
    iid, _, n = s.rpartition(":")
    n = int(float(n))
    if iid == "SKYBLOCK_COIN":
        return "COINS:%d" % n
    if iid == "INK_SACK-4":
        iid = "INK_SACK:4"
    return "%s:%d" % (iid, n)


def forge_unlock(u):
    """Audit 2026-10-06 M2: the Forge opens at HOTM II (wiki /The_Forge, /Mithril_Belt: the Mithril equipment needs
    HOTM II). NEU's crafttext says "HotM 1" for the four Mithril pieces; nothing can be forged below HOTM II anyway."""
    if u is None:
        return None
    return " & ".join("hotm:2" if p == "hotm:1" else p for p in u.split(" & "))


FORGE_CAT = {}
forge = []
import glob
for f in sorted(glob.glob(os.path.join(RES, "neu-repo", "items", "*.json"))):
    with open(f, encoding="utf-8") as fh:
        d = json.load(fh)
    for r in d.get("recipes", []):
        if r.get("type") != "forge":
            continue
        out = r.get("overrideOutputId", d["internalname"])
        forge.append({
            "id": "mining_forge:" + out,
            "type": "FORGE",
            "result": out,
            "amount": int(float(r.get("count", 1))),
            "inputs": [cell(c) for c in r["inputs"]],
            "durationSeconds": int(r.get("duration", 0)),
            "unlock": forge_unlock(craft_unlock(d.get("crafttext"))),
            "station": "Forge",
        })
forge.sort(key=lambda x: x["id"])
# Perfect gems for every type are in the data already; check
assert sum(1 for x in forge if x["result"].startswith("PERFECT_") and x["result"].endswith("_GEM")) == 12

# ----------------------------------------------------------------------------------------------
# Other recipes: gem compression (80 -> 1 as five stacks of 16) + shop lines
# ----------------------------------------------------------------------------------------------
recipes = []
for gem in GEM_INFO:
    for lo, hi in [("ROUGH", "FLAWED"), ("FLAWED", "FINE"), ("FINE", "FLAWLESS")]:
        src = "%s_%s_GEM:16" % (lo, gem)
        recipes.append({"id": "mining:%s_%s_GEM" % (hi, gem), "type": "CRAFTING", "result": "%s_%s_GEM" % (hi, gem), "amount": 1,
                        "grid": ["", src, "", src, src, src, "", src, ""], "unlock": None})
SMITH = [("FRACTURED_MITHRIL_PICKAXE", ["COINS:10000"]),
         ("BANDAGED_MITHRIL_PICKAXE", ["FRACTURED_MITHRIL_PICKAXE:1", "ENCHANTED_MITHRIL:2"]),
         ("MITHRIL_PICKAXE", ["BANDAGED_MITHRIL_PICKAXE:1", "ENCHANTED_MITHRIL:64", "BEJEWELED_HANDLE:1", "ENCHANTED_GOLD:32"]),
         ("REFINED_MITHRIL_PICKAXE", ["MITHRIL_PICKAXE:1", "REFINED_MITHRIL:2"]),
         ("RUSTY_TITANIUM_PICKAXE", ["COINS:50000"]),
         ("TITANIUM_PICKAXE", ["RUSTY_TITANIUM_PICKAXE:1", "ENCHANTED_TITANIUM:4", "BEJEWELED_HANDLE:1"]),
         ("REFINED_TITANIUM_PICKAXE", ["TITANIUM_PICKAXE:1", "REFINED_TITANIUM:1"]),
         ("BIOFUEL", ["COINS:20000"]),
         ("ROOKIE_PICKAXE", ["COINS:12"]),
         ("PROMISING_PICKAXE", ["COINS:35"]),
         ("CHISEL", ["COINS:5000"])]       # TUNE: the reference sells it for 5 Fossil Essence
for res, inp in SMITH:
    recipes.append({"id": "mining_smith:" + res, "type": "NPC_SHOP", "result": res, "amount": 1, "inputs": inp, "station": "Pickaxe Smith", "unlock": None})
HOLLOWS_SHOP = [("JUNGLE_PICKAXE", ["SLUDGE_JUICE:200"], "hotm:4"),
                ("WISHING_COMPASS", ["COINS:5000"], None),       # TUNE
                ("ASCENSION_ROPE", ["COINS:25000"], None),       # TUNE
                ("PORTABLE_CAMPFIRE", ["COINS:15000", "MATCH_STICKS:16"], "hotm:8"),   # TUNE
                # alchemy's Recall Potion (only works in the caverns; alchemy request 16). Research names no source; TUNE
                ("RECALL_POTION", ["COINS:40000"], None)]
for res, inp, unl in HOLLOWS_SHOP:
    recipes.append({"id": "mining_hollows:" + res, "type": "NPC_SHOP", "result": res, "amount": 1, "inputs": inp, "station": "Hollows Merchant", "unlock": unl})

# ----------------------------------------------------------------------------------------------
# Commissions
# ----------------------------------------------------------------------------------------------
C = []


def comm(cid, island, name, kind, amount, match=None, zone=None, tier=1, event=None):
    C.append({"id": cid, "island": island, "name": name, "kind": kind, "amount": amount, "match": match or [],
              "zone": zone, "tier": tier, "event": event})


# Dwarven Mines (research 2.8)
comm("mithril_miner", "dwarven_mines", "Mithril Miner", "MINE", 350, ["mithril_gray", "mithril_prismarine", "mithril_blue"], tier=2)
comm("titanium_miner", "dwarven_mines", "Titanium Miner", "MINE", 15, ["titanium"], tier=2)
comm("goblin_slayer", "dwarven_mines", "Goblin Slayer", "KILL", 100, ["goblin"], tier=2)
comm("glacite_walker_slayer", "dwarven_mines", "Rimestone Walker Slayer", "KILL", 50, ["ice_walker", "glacite_walker"], tier=2)
comm("treasure_hoarder_puncher", "dwarven_mines", "Treasure Hoarder Puncher", "HIT", 10, ["treasure_hoarder"], tier=1)
comm("star_sentry_puncher", "dwarven_mines", "Star Sentry Puncher", "HIT", 10, ["star_sentry"], tier=1, event="fallen_star")
comm("goblin_raid", "dwarven_mines", "Goblin Raid", "EVENT", 1, ["goblin_raid"], tier=1, event="goblin_raid")
comm("goblin_raid_slayer", "dwarven_mines", "Goblin Raid Slayer", "KILL", 20, ["mining_raid"], tier=2, event="goblin_raid")
comm("elusive_goblin", "dwarven_mines", "Elusive Goblin Slayer", "KILL", 1, ["golden_goblin", "diamond_goblin"], tier=2)
comm("raffle", "dwarven_mines", "Raffle", "EVENT", 1, ["raffle"], tier=1, event="raffle")
comm("lucky_raffle", "dwarven_mines", "Lucky Raffle", "RAFFLE", 20, [], tier=2, event="raffle")
comm("powder_collector", "dwarven_mines", "2x Mithril Powder Collector", "POWDER", 500, ["MITHRIL"], tier=1, event="double_powder")
comm("mines_slayer", "dwarven_mines", "Mines Slayer", "KILL", 50, ["*"], tier=1)
# zone-specific mithril/titanium commissions are made at runtime from the island's ores.<zone> areas
# Crystal Hollows
for gem in ["RUBY", "AMBER", "SAPPHIRE", "JADE", "AMETHYST", "TOPAZ"]:
    comm(gem.lower() + "_collector", "crystal_hollows", gem.title() + " Gemstone Collector", "COLLECT", 1000, ["ROUGH_%s_GEM" % gem], tier=2)
for gem in ["AMETHYST", "JADE", "AMBER", "SAPPHIRE", "TOPAZ"]:
    comm(gem.lower() + "_crystal_hunter", "crystal_hollows", gem.title() + " Crystal Hunter", "CRYSTAL", 1, [gem + "_CRYSTAL"], tier=2)
comm("hard_stone_miner", "crystal_hollows", "Hard Stone Miner", "MINE", 1000, ["hard_stone"], tier=1)
comm("automaton_slayer", "crystal_hollows", "Automaton Slayer", "KILL", 13, ["automaton"], tier=2)
comm("sludge_slayer", "crystal_hollows", "Sludge Slayer", "KILL", 25, ["sludge"], tier=1)
comm("treasurite_slayer", "crystal_hollows", "Treasure Gang Slayer", "KILL", 13, ["treasurite", "treasure_gang"], tier=2)
comm("goblin_slayer_ch", "crystal_hollows", "Goblin Slayer", "KILL", 13, ["goblin"], tier=1)
comm("yog_slayer", "crystal_hollows", "Cinderling Slayer", "KILL", 13, ["yog", "cinderling"], tier=2)
comm("thyst_slayer", "crystal_hollows", "Amethyst Leech Slayer", "KILL", 5, ["thyst"], tier=2)
comm("chest_looter", "crystal_hollows", "Chest Looter", "CHEST", 3, [], tier=1)
comm("boss_slayer", "crystal_hollows", "Gang Boss Slayer", "KILL", 1, ["corleone", "vitello"], tier=2)
# Rimestone (Glacite) Tunnels
for mid, nm in [("glacite", "Rimestone"), ("umber", "Umber"), ("tungsten", "Tungsten")]:
    comm(mid + "_collector", "glacite_tunnels", nm + " Collector", "COLLECT", 1500, [{"glacite": "GLACITE", "umber": "UMBER", "tungsten": "TUNGSTEN"}[mid]], tier=2)
for gem in ["AQUAMARINE", "ONYX", "CITRINE", "PERIDOT"]:
    comm(gem.lower() + "_collector_gt", "glacite_tunnels", gem.title() + " Gemstone Collector", "COLLECT", 1500, ["ROUGH_%s_GEM" % gem], tier=2)
comm("mineshaft_explorer", "glacite_tunnels", "Mineshaft Explorer", "MINESHAFT", 1, [], tier=2)
comm("corpse_looter", "glacite_tunnels", "Corpse Looter", "CORPSE", 2, [], tier=2)
comm("scrap_collector", "glacite_tunnels", "Scrap Collector", "SCRAP", 1, [], tier=2)
comm("glacite_slayer", "glacite_tunnels", "Rimestone Mob Slayer", "KILL", 10, ["glacite", "ice_walker", "frost"], tier=1)

COMMISSIONS = {
    "commissions": C,
    "rewards": {  # research 2.8: mining XP / HOTM XP / powder by island, [tier1, tier2]
        "dwarven_mines": {"miningXp": [5000, 10000], "hotmXp": [200, 400], "powder": "MITHRIL", "powderAmount": [500, 1000]},
        "crystal_hollows": {"miningXp": [10000, 20000], "hotmXp": [400, 400], "powder": "GEMSTONE", "powderAmount": [1000, 2000]},
        "glacite_tunnels": {"miningXp": [12500, 25000], "hotmXp": [750, 750], "powder": "GLACITE", "powderAmount": [1500, 3000]},
    },
    "dailyBonus": {"count": 4, "hotmXp": 900, "maxedPowder": {"MITHRIL": 1600, "GEMSTONE": 3200, "GLACITE": 4000}},
    "milestones": [
        {"count": 5, "miningXp": 100000, "skyblockXp": 20, "reward": "emissaries"},
        {"count": 25, "miningXp": 200000, "skyblockXp": 30, "reward": None},
        {"count": 100, "miningXp": 400000, "skyblockXp": 30, "reward": "slot"},
        {"count": 250, "miningXp": 800000, "skyblockXp": 50, "reward": "DWARVEN_MINES_TRAVEL_SCROLL"},
        {"count": 500, "miningXp": 1200000, "skyblockXp": 50, "reward": "ROYAL_PIGEON"},
        {"count": 750, "miningXp": 1600000, "skyblockXp": 75, "reward": "CRYSTAL_NUCLEUS_TRAVEL_SCROLL"},
    ],
}

# ----------------------------------------------------------------------------------------------
# Loot tables (all chances TUNE unless the research gives them)
# ----------------------------------------------------------------------------------------------
GEM_ZONES = {"jungle": "AMETHYST", "mithril": "JADE", "precursor": "SAPPHIRE", "goblin": "AMBER", "magma": "TOPAZ", "fairy": "JASPER"}


def _nucleus():
    """Crystal Nucleus bundle (audit 2026-10-06 M9/M14/M15/M16; SkyblockMoney/work/mining.md 3d, wiki /Crystal_Nucleus):
    14-19 rolls (16.5 avg) on a W=307,212 table, plus one of each Fine gem and 800 HOTM XP every run.

    Four lines are anchored to the research's PER-BUNDLE chances (Divan Fragment 31.8%, Jaderald 7.7%, Gemstone
    Mixture 3.7%, Divan's Alloy 0.09%): the per-roll weight p*W solves 1 - mean_n (1-p)^n = P over n = 14..19.
    The rest of W is the common pool (robot parts, goblin eggs, Hoardite, consumables, flawed gems); the research
    gives no per-line odds for it, so its split is TUNE. The Precursor Apparatus is NOT in the table: the Professor
    quest consumes one to give the Sapphire crystal, so a Nucleus that pays one back would refund its own cost."""
    W = 307212
    anchored = [("DIVAN_FRAGMENT", 7058, 1, 1), ("JADERALD", 1489, 1, 1), ("GEMSTONE_MIXTURE", 701, 1, 1), ("DIVAN_ALLOY", 17, 1, 1)]
    rest = W - sum(a[1] for a in anchored)
    common = [   # (item, share of the rest in %, min, max) - TUNE
        ("CONTROL_SWITCH", 2, 1, 1), ("ELECTRON_TRANSMITTER", 2, 1, 1), ("FTX_3070", 2, 1, 1),
        ("ROBOTRON_REFLECTOR", 2, 1, 1), ("SUPERLITE_MOTOR", 2, 1, 1), ("SYNTHETIC_HEART", 2, 1, 1),
        ("TREASURITE", 10, 1, 3), ("GOBLIN_EGG", 6, 1, 3), ("GOBLIN_EGG_GREEN", 3, 1, 2), ("GOBLIN_EGG_RED", 2, 1, 1),
        ("GOBLIN_EGG_YELLOW", 2, 1, 1), ("GOBLIN_EGG_BLUE", 1, 1, 1), ("SLUDGE_JUICE", 8, 2, 6), ("YOGGIE", 6, 1, 3),
        ("WISHING_COMPASS", 8, 1, 1), ("OIL_BARREL", 6, 1, 1), ("ASCENSION_ROPE", 6, 1, 1), ("BOB_OMB", 4, 1, 1),
        ("JUNGLE_HEART", 1, 1, 1), ("PICKONIMBUS", 1.5, 1, 1), ("PREHISTORIC_EGG", 1, 1, 1), ("ZONE_GEM_FLAWED", 22.5, 1, 4),
    ]
    assert abs(sum(c[1] for c in common) - 100) < 1e-9
    table = [{"item": i, "min": a, "max": b, "weight": w} for (i, w, a, b) in anchored]
    table += [{"item": i, "min": a, "max": b, "weight": round(rest * pct / 100.0, 2)} for (i, pct, a, b) in common]
    return {
        "powder": [2500, 5000], "miningXp": 20000,   # powder / Mining XP: ours (TUNE), not in the audit
        "hotmXp": 800,
        "always": [{"item": "FINE_%s_GEM" % g, "min": 1, "max": 1} for g in ("AMETHYST", "JADE", "SAPPHIRE", "AMBER", "TOPAZ")],
        "rolls": [14, 19],
        "table": table,
    }


NUCLEUS = _nucleus()

# Frozen Corpses (audit 2026-10-06 M12/M13): tables straight from NEU (items/SKYBLOCK_CORPSE_*.json = wiki /Frozen_Corpses;
# each line's chance is its per-roll weight), rolls from the same files. Powder / HOTM XP per corpse from
# SkyblockMoney/work/mining.md 3f: Lapis 500 Glacite powder + 100 HOTM XP; Umber/Tungsten 5,000; Vanguard 25,000.
CORPSE_REF = {"lapis": ("SKYBLOCK_CORPSE_LAPIS", None, 500, 100), "umber": ("SKYBLOCK_CORPSE_UMBER", "UMBER_KEY", 5000, 0),
              "tungsten": ("SKYBLOCK_CORPSE_TUNGSTEN", "TUNGSTEN_KEY", 5000, 0), "vanguard": ("SKYBLOCK_CORPSE_VANGUARD", "SKELETON_KEY", 25000, 0)}
# Items to leave out of the corpse tables (their weight renormalises away). Empty since 2026-10-06: materials now
# registers the Dwarven O's Metallic Minis cereal.
CORPSE_SKIP = set()


def _corpse(ref, key, powder, hotm_xp):
    d = neu(ref)
    r = d["recipes"][0]
    m = re.search(r"roll (\d+) to (\d+) times", strip(r["extra"][-1]))
    table = []
    for dr in r["drops"]:
        for x in [dr] + dr.get("alternatives", []):
            iid, _, n = x["id"].partition(":")
            if ";" in iid:   # enchanted book ICE_COLD;1 -> our book id
                e, _, lvl = iid.partition(";")
                iid = "ENCHANTMENT_%s_%s" % (e, lvl)
            if iid in CORPSE_SKIP:
                continue
            n = int(n) if n else 1
            table.append({"item": iid, "min": n, "max": n, "weight": float(strip(x["chance"]).rstrip("%"))})
    out = {"key": key, "powder": [powder, powder], "rolls": [int(m.group(1)), int(m.group(2))], "table": table}
    if hotm_xp:
        out["hotmXp"] = hotm_xp
    return out


CORPSES = {k: _corpse(*v) for k, v in CORPSE_REF.items()}

LOOT = {
    # zoneGem: the line belongs to that zone's chests (research 2.11 lists robot parts, goblin eggs, sludge juice...;
    # per-zone odds are TUNE, dials mining.chest-zone-boost / mining.chest-off-zone)
    "treasure_chest": {   # research 2.11: chests are THE gemstone powder source
        "powder": [200, 1000],
        # audit 2026-10-06 M8 (wiki /Treasure_Chest): 4.5 rolls on average -> 3..6
        "rolls": [3, 6],
        "table": [
            {"item": "ZONE_GEM_ROUGH", "min": 20, "max": 60, "weight": 40},
            {"item": "ZONE_GEM_FLAWED", "min": 1, "max": 4, "weight": 20},
            {"item": "ZONE_GEM_FINE", "min": 1, "max": 1, "weight": 2},
            {"item": "GOBLIN_EGG", "min": 1, "max": 3, "weight": 10, "zoneGem": "AMBER"},
            {"item": "GOBLIN_EGG_GREEN", "min": 1, "max": 2, "weight": 6, "zoneGem": "AMBER"},
            {"item": "GOBLIN_EGG_RED", "min": 1, "max": 1, "weight": 3, "zoneGem": "AMBER"},
            {"item": "GOBLIN_EGG_YELLOW", "min": 1, "max": 1, "weight": 2, "zoneGem": "AMBER"},
            {"item": "GOBLIN_EGG_BLUE", "min": 1, "max": 1, "weight": 1, "zoneGem": "AMBER"},
            {"item": "SLUDGE_JUICE", "min": 2, "max": 6, "weight": 8, "zoneGem": "AMETHYST"},
            {"item": "TREASURITE", "min": 1, "max": 3, "weight": 6, "zoneGem": "JADE"},
            {"item": "WISHING_COMPASS", "min": 1, "max": 1, "weight": 5},
            {"item": "OIL_BARREL", "min": 1, "max": 1, "weight": 4},
            {"item": "ASCENSION_ROPE", "min": 1, "max": 1, "weight": 3},
            {"item": "YOGGIE", "min": 1, "max": 3, "weight": 4, "zoneGem": "TOPAZ"},
            {"item": "BOB_OMB", "min": 1, "max": 1, "weight": 2},
            {"item": "CONTROL_SWITCH", "min": 1, "max": 1, "weight": 1, "zoneGem": "SAPPHIRE"},
            {"item": "ELECTRON_TRANSMITTER", "min": 1, "max": 1, "weight": 1, "zoneGem": "SAPPHIRE"},
            {"item": "FTX_3070", "min": 1, "max": 1, "weight": 1, "zoneGem": "SAPPHIRE"},
            {"item": "ROBOTRON_REFLECTOR", "min": 1, "max": 1, "weight": 1, "zoneGem": "SAPPHIRE"},
            {"item": "SUPERLITE_MOTOR", "min": 1, "max": 1, "weight": 1, "zoneGem": "SAPPHIRE"},
            {"item": "SYNTHETIC_HEART", "min": 1, "max": 1, "weight": 1, "zoneGem": "SAPPHIRE"},
            {"item": "JUNGLE_HEART", "min": 1, "max": 1, "weight": 0.3, "zoneGem": "AMETHYST"},
            {"item": "PICKONIMBUS", "min": 1, "max": 1, "weight": 0.5},
            {"item": "PREHISTORIC_EGG", "min": 1, "max": 1, "weight": 0.3},
            {"item": "ZONE_CRYSTAL", "min": 1, "max": 1, "weight": 0.2},
        ],
    },
    "nucleus": NUCLEUS,
    "corpse": CORPSES,
    "goblins": {"golden": {"powder": [800, 1500]}, "diamond": {"powder": [2500, 5000]}},
    "raffle": [
        {"item": "MITHRIL_POWDER", "min": 1000, "max": 5000, "weight": 50},
        {"item": "COINS", "min": 10000, "max": 50000, "weight": 30},
        {"item": "TREASURITE", "min": 1, "max": 3, "weight": 10},
        {"item": "GOBLIN_EGG_BLUE", "min": 1, "max": 1, "weight": 3},
        {"item": "DIVAN_FRAGMENT", "min": 1, "max": 1, "weight": 1},
    ],
    "fetchur": [   # daily item request (the reference's list, in our ids)
        {"item": "YELLOW_STAINED_GLASS", "amount": 20}, {"item": "COMPASS", "amount": 1}, {"item": "MITHRIL_ORE", "amount": 20},
        {"item": "FIREWORK_ROCKET", "amount": 1}, {"item": "RED_WOOL", "amount": 50}, {"item": "IRON_DOOR", "amount": 1},
        {"item": "RABBIT_FOOT", "amount": 3}, {"item": "TNT", "amount": 1}, {"item": "FLINT", "amount": 6},
        {"item": "PRISMARINE", "amount": 1}, {"item": "OBSIDIAN", "amount": 10}, {"item": "COBBLESTONE", "amount": 50},
        {"item": "PUMPKIN", "amount": 2}, {"item": "PRISMARINE_SHARD", "amount": 1}, {"item": "PACKED_ICE", "amount": 4},
    ],
    "excavator": {   # research 2.14
        "fossilChance": 0.10, "fossils": ["SPINE_FOSSIL", "CLUBBED_FOSSIL", "UGLY_FOSSIL", "FOOTPRINT_FOSSIL", "WEBBED_FOSSIL", "TUSK_FOSSIL"],
        "rolls": [10, 14],
        "dyeChance": 1 / 500000, "dye": "DYE_FOSSIL",   # research 2.14: every roll, 1 in 500,000
        "table": [
            {"item": "GLACITE_POWDER", "min": 1000, "max": 10000, "weight": 30},
            {"item": "FLAWED_AQUAMARINE_GEM", "min": 1, "max": 3, "weight": 8}, {"item": "FLAWED_ONYX_GEM", "min": 1, "max": 3, "weight": 8},
            {"item": "FLAWED_CITRINE_GEM", "min": 1, "max": 3, "weight": 8}, {"item": "FLAWED_PERIDOT_GEM", "min": 1, "max": 3, "weight": 8},
            {"item": "ENCHANTED_GLACITE", "min": 1, "max": 8, "weight": 10}, {"item": "ENCHANTED_UMBER", "min": 1, "max": 8, "weight": 8},
            {"item": "ENCHANTED_TUNGSTEN", "min": 1, "max": 8, "weight": 8}, {"item": "GLACITE_JEWEL", "min": 1, "max": 2, "weight": 6},
            # research 2.14: "Fossil Essence (10-40 each)". Weight TUNE: ~1 roll in 6 -> ~2 essence lines per dig, about
            # 40% of them under a cell a 16-22 charge chisel reaches -> ~20 essence per excavation, so the Fossil Researcher's
            # Suspicious Scrap (100) costs ~5 digs and a Chisel (5) is in reach after the first.
            {"item": "ESSENCE_FOSSIL", "min": 10, "max": 40, "weight": 20},
            # research 2.14 lists both, with no odds (weights TUNE): a Paleontologist I book ~1 dig in 8 (before the
            # chisel has to reach it) and the Stonefin curio ~1 in 30. Lines whose item is not registered are skipped.
            {"item": "ENCHANTMENT_PALEONTOLOGIST_1", "min": 1, "max": 1, "weight": 1.2},
            {"item": "FOSSIL_THE_FISH", "min": 1, "max": 1, "weight": 0.3},
        ],
    },
}

# ----------------------------------------------------------------------------------------------
# Mobs this module owns (rare finds and event mobs)
# ----------------------------------------------------------------------------------------------
RAID_TUNE = ("Goblin Raiders (NEU bestiary mining_3): ids/levels from the reference roster; HP inside the reference Goblin "
             "range 200-50,000 (NEU GOBLIN_MONSTER); damage/xp/coins/drops are ours")

def WORM_GEMS(grade):
    """Worm gem drops: Topaz always, Amethyst 40%, Jade 20%, Amber 10%, Sapphire 10% (NEU WORM_MONSTER / SCATHA_MONSTER)."""
    return [{"item": "%s_%s_GEM" % (grade, g), "min": 1, "max": 1, "chance": c}
            for g, c in (("TOPAZ", 1.0), ("AMETHYST", 0.4), ("JADE", 0.2), ("AMBER", 0.1), ("SAPPHIRE", 0.1))]


MOBS = [
    {"id": "mining_golden_goblin", "name": "Golden Goblin", "level": 50, "entity": "ZOMBIE", "health": 100, "damage": 0, "speed": 1.6,
     "combatXp": 50, "coins": 200, "family": "golden_goblin", "flags": ["baby", "passive", "no_knockback"],
     "equipment": {"HEAD": "GOLDEN_HELMET", "CHEST": "GOLDEN_CHESTPLATE", "LEGS": "GOLDEN_LEGGINGS", "FEET": "GOLDEN_BOOTS"},
     "drops": [{"item": "GOBLIN_EGG", "min": 1, "max": 2, "chance": 0.5}]},
    {"id": "mining_diamond_goblin", "name": "Diamond Goblin", "level": 100, "entity": "ZOMBIE", "health": 500, "damage": 0, "speed": 1.8,
     "combatXp": 200, "coins": 1000, "family": "diamond_goblin", "flags": ["baby", "passive", "no_knockback"],
     "equipment": {"HEAD": "DIAMOND_HELMET", "CHEST": "DIAMOND_CHESTPLATE", "LEGS": "DIAMOND_LEGGINGS", "FEET": "DIAMOND_BOOTS"},
     "drops": [{"item": "GOBLIN_EGG_BLUE", "min": 1, "max": 1, "chance": 0.25}]},
    {"id": "mining_powder_ghast", "name": "Powder Ghast", "level": 250, "entity": "GHAST", "health": 3000, "damage": 0, "speed": 1.0,
     "combatXp": 300, "coins": 500, "family": "powder_ghast", "flags": ["passive", "no_knockback", "fire_immune"], "drops": []},
    {"id": "mining_star_sentry", "name": "Star Sentry", "level": 70, "entity": "BLAZE", "health": 2500, "damage": 300, "speed": 1.0,
     "combatXp": 80, "coins": 60, "family": "star_sentry", "flags": ["aggressive", "fire_immune"],
     "drops": [{"item": "STARFALL", "min": 2, "max": 5, "chance": 1.0}]},
    # Goblin Raid (wave-5 regression fix): the reference's "Goblin Raiders" bestiary family (NEU mining_3) - mostly
    # Lv5 weaklings, plus a Lv60 Battler, a Lv90 Creepertamer and the Lv150 Murderlover (our Cutthroat) / Golem.
    # The old Lv40 "Raid Goblin" (250 dmg) and Lv90 "Goblin Warlord" (700 dmg) killed miners in seconds and are gone.
    # Spawn weights live in MiningMath.RAID_IDS / RAID_WEIGHTS.
    *[{"id": i, "name": n, "level": lv, "entity": ent, "health": hp, "damage": dmg, "speed": spd, "combatXp": xp, "coins": co,
       "family": "goblin_raiders", "flags": fl, "equipment": eq, "drops": dr,
       "extra": dict({"tune": RAID_TUNE}, **({"scale": sc} if sc else {}))}
      for (i, n, lv, ent, hp, dmg, spd, xp, co, fl, eq, dr, sc) in [
        ("mining_raid_weakling", "Weakling Goblin", 5, "ZOMBIE", 200, 40, 1.0, 4, 3, ["aggressive"],
         {"HAND": "WOODEN_SWORD", "HEAD": "LEATHER_HELMET"}, [{"item": "GOBLIN_EGG", "min": 1, "max": 1, "chance": 0.01}], 0.8),
        ("mining_raid_weakling_bow", "Weakling Goblin", 5, "SKELETON", 200, 35, 1.0, 4, 3, ["aggressive"],
         {"HAND": "BOW", "HEAD": "LEATHER_HELMET"}, [{"item": "GOBLIN_EGG", "min": 1, "max": 1, "chance": 0.01}], 0.8),
        ("mining_raid_battler", "Goblin Battler", 60, "ZOMBIE", 3000, 200, 1.1, 30, 15, ["aggressive"],
         {"HAND": "IRON_SWORD", "HEAD": "IRON_HELMET", "CHEST": "CHAINMAIL_CHESTPLATE"},
         [{"item": "GOBLIN_EGG", "min": 1, "max": 1, "chance": 0.03}], 0.9),
        ("mining_raid_creepertamer", "Creepertamer", 90, "ZOMBIE", 6000, 260, 1.0, 60, 30, ["aggressive"],
         {"HAND": "STICK", "HEAD": "CREEPER_HEAD"},
         [{"item": "GOBLIN_EGG", "min": 1, "max": 1, "chance": 0.05}, {"item": "GOBLIN_EGG_GREEN", "min": 1, "max": 1, "chance": 0.02}], 0.9),
        ("mining_raid_cutthroat", "Goblin Cutthroat", 150, "ZOMBIE", 15000, 380, 1.15, 150, 100, ["aggressive", "no_knockback"],
         {"HAND": "IRON_SWORD", "HEAD": "GOLDEN_HELMET", "CHEST": "CHAINMAIL_CHESTPLATE"},
         [{"item": "GOBLIN_EGG_RED", "min": 1, "max": 1, "chance": 0.1}, {"item": "TREASURITE", "min": 1, "max": 2, "chance": 0.5}], None),
        ("mining_raid_golem", "Goblin Golem", 150, "IRON_GOLEM", 25000, 320, 0.8, 150, 100, ["aggressive", "no_knockback"], {},
         [{"item": "GOBLIN_EGG_YELLOW", "min": 1, "max": 1, "chance": 0.1}, {"item": "TREASURITE", "min": 1, "max": 2, "chance": 0.5}], None),
      ]],
    {"id": "mining_worm", "name": "Cave Worm", "level": 5, "entity": "SILVERFISH", "health": 5000, "damage": 300, "speed": 1.0,
     "combatXp": 60, "coins": 100, "family": "worm", "flags": ["aggressive"],
     # audit 2026-10-06 M6: 1 Worm Membrane + rough gems (NEU items/WORM_MONSTER.json chances; amounts not given -> 1)
     "drops": [{"item": "WORM_MEMBRANE", "min": 1, "max": 1, "chance": 1.0}] + WORM_GEMS("ROUGH")},
    {"id": "mining_scatha", "name": "Tunnel Wyrm", "level": 10, "entity": "ENDERMITE", "health": 10000, "damage": 500, "speed": 1.1,
     "combatXp": 250, "coins": 400, "family": "scatha", "flags": ["aggressive"],
     # audit 2026-10-06 M7: 4 Worm Membrane + Fine Topaz (100%) + fine-gem chances (NEU items/SCATHA_MONSTER.json);
     # pets Rare 0.24% / Epic 0.12% / Legendary 0.04%. NEU's 10% Dwarven O's cereal is left out (not registered).
     "drops": [{"item": "WORM_MEMBRANE", "min": 4, "max": 4, "chance": 1.0}] + WORM_GEMS("FINE")
              + [{"item": "SCATHA;2", "min": 1, "max": 1, "chance": 0.0024, "label": "CRAZY_RARE"},
                 {"item": "SCATHA;3", "min": 1, "max": 1, "chance": 0.0012, "label": "CRAZY_RARE"},
                 {"item": "SCATHA;4", "min": 1, "max": 1, "chance": 0.0004, "label": "RNGESUS"}]},
]


# ----------------------------------------------------------------------------------------------
def dump(name, obj):
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, name), "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)
        f.write("\n")


ids = [x["id"] for x in ITEMS]
assert len(ids) == len(set(ids)), [i for i in ids if ids.count(i) > 1]
dump("items.json", ITEMS)
dump("blocks.json", {"blocks": BLOCKS})
dump("hotm.json", HOTM)
dump("forge.json", forge)
dump("recipes.json", recipes)
dump("commissions.json", COMMISSIONS)
dump("loot.json", LOOT)
dump("mobs.json", MOBS)
print("items %d, blocks %d, perks %d, forge %d, recipes %d, commissions %d, mobs %d"
      % (len(ITEMS), len(BLOCKS), len(perks), len(forge), len(recipes), len(C), len(MOBS)))
