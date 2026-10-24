#!/usr/bin/env python3
"""Generate data/chocolate/*.json and docs/names/chocolate.md for the chocolate module.

Sources (all under research/data):
  neu-repo/constants/hoppity.json            rabbit ids by rarity, per-rarity CpS / multiplier, specials,
                                             prestige multipliers, chocolate talismans
  skyhanni-repo/constants/HoppityEggLocations.json
                                             island residents, factory + shop milestones, hitman slot costs
  skyhanni-repo/constants/HoppityRabbitTextures.json
                                             rabbit head textures (by reference display name)
  neu-repo/items/*.json                      item skulls (Egglocator, faction rabbits, dye, fish, NPC skin)

Every reference rabbit name is replaced by one of OUR names (generated deterministically below) except the
Greek-letter rabbits, which are plain words. Ids stay the reference ids.

Run:  python tools/gen/chocolate/gen_chocolate.py
"""
import json
import os
import random
import re

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
RES = os.path.join(ROOT, "research", "data")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "chocolate")
NAMES_MD = os.path.join(ROOT, "docs", "names", "chocolate.md")

RARITIES = ["common", "uncommon", "rare", "epic", "legendary", "mythic", "divine"]
GREEK = ["alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta", "iota", "kappa", "lambda", "mu",
         "nu", "xi", "omicron", "pi", "rho", "sigma", "tau", "upsilon", "phi", "chi", "psi", "omega"]

# reference island key -> our island id(s). Galatea is a wave-5 island: when it is not registered at runtime,
# its residents fall back to "found anywhere".
ISLANDS = {
    "HUB": ["hub"], "CRIMSON_ISLE": ["crimson_isle"], "GOLD_MINES": ["gold_mine"], "DEEP_CAVERNS": ["deep_caverns"],
    "DUNGEON_HUB": ["dungeon_hub"], "SPIDER_DEN": ["spiders_den"], "DWARVEN_MINES": ["dwarven_mines"],
    "THE_END": ["the_end"], "THE_PARK": ["park"], "CRYSTAL_HOLLOWS": ["crystal_hollows"],
    "THE_FARMING_ISLANDS": ["barn", "mushroom_desert"], "BACKWATER_BAYOU": ["backwater_bayou"], "GALATEA": ["galatea"],
}

# ---- our names -----------------------------------------------------------------------------------------------

# Factory milestone rabbits are alliterative desserts A..Z in the reference; ours are alliterative too.
FACTORY_NAMES = [
    "Apricot Arabesque", "Brownie Bonbon", "Caramel Cascade", "Date Dacquoise", "Eclair Ember", "Fig Fondant",
    "Gelato Gale", "Halva Hush", "Icebox Iris", "Jam Jubilee", "Kulfi Kite", "Licorice Lantern", "Marzipan Moon",
    "Nectar Nimbus", "Oatcake Orbit", "Praline Pebble", "Quark Quill", "Rhubarb Rhapsody", "Sherbet Spiral",
    "Treacle Tide", "Umber Upsidedown", "Velvet Vapor", "Wafer Whirlwind", "Xigua Xylo", "Yuzu Yonder",
    "Zabaione Zenith",
]

# Mythic rabbits: each has its own ability (see MYTHIC_ABILITY). Greek mythics keep their letter.
MYTHIC_NAMES = {
    "dante": "Cinderwick", "einstein": "Professor Tock", "galaxy": "Starlop", "king": "Monarch Mallow",
    "napoleon": "Generalissimo Nib", "zorro": "Masked Hopper", "el_dorado": "Goldenpaw",
}
DIVINE_NAMES = {
    "aurora": "Dawnfluff", "celestia": "Moonmallow", "orion": "Starbelt", "starfire": "Emberlight", "vega": "Lyrabun",
}
LEGENDARY_POOL = [
    "Sir Truffleton", "Duchess Fudgewick", "Baron Butterscotch", "Lady Lavenderloaf", "Captain Crumble",
    "Count Cocoabean", "Marquis Meringue", "Admiral Almondine", "Professor Praline", "Dame Dulcet",
    "Lord Licorice", "Madame Macaron", "Archduke Nougat", "Viscount Vanillin", "Captain Cobbler",
    "Squire Shortbread", "Countess Cocoa", "Sheriff Sorbet", "Maestro Mocha", "Judge Gingersnap",
    "Abbess Angelica", "Prince Pistachio",
]
# rare / epic: "Adjective Noun"
ADJ = ["Velvet", "Copper", "Misty", "Amber", "Dapper", "Frosty", "Hazy", "Ivory", "Jolly", "Lucky", "Merry",
       "Nutty", "Plummy", "Rusty", "Silky", "Toasty", "Mossy", "Sunny", "Sleepy", "Twinkly", "Fuzzy", "Gentle",
       "Breezy", "Cosy", "Dusky", "Speckled", "Wispy", "Snowy", "Starry", "Tawny"]
NOUN = ["Biscuit", "Dumpling", "Pudding", "Truffle", "Waffle", "Pretzel", "Scone", "Crumpet", "Muffin", "Toffee",
        "Brioche", "Cobbler", "Popover", "Strudel", "Tartlet", "Fritter", "Pastille", "Mochaccino", "Gumdrop",
        "Marshmallow", "Parfait", "Custard", "Bonbon", "Snickerdoodle", "Teacake"]
# common / uncommon: invented single names from two syllables
SYL_A = ["Bun", "Pip", "Mof", "Tuf", "Wib", "Nib", "Dap", "Fen", "Hob", "Lop", "Mip", "Nub", "Pom", "Quib", "Rus",
         "Sop", "Tib", "Cob", "Dib", "Fiz", "Gub", "Hum", "Jib", "Kip", "Lum", "Mub", "Nim", "Obb", "Pud", "Rib",
         "Tam", "Vim", "Wob", "Yip", "Zib", "Bop", "Cuf", "Dov", "Flo", "Gim", "Hap", "Jun", "Kob", "Lin", "Mel"]
SYL_B = ["kin", "ble", "sy", "ton", "per", "let", "wick", "bean", "by", "dle", "ny", "pot", "sie", "mo", "bit",
         "zel", "ford", "ling", "nik", "roo"]

# Mythic abilities (ours; the reference gives each mythic a unique perk). Keys are read by ChocMath/Factory.
# TUNE: designs faithful in spirit; only dante / galaxy / zest_zephyr numbers come from the data (specials).
MYTHIC_ABILITY = {
    "dante": ("flat_cps", 0, "Its kiln never cools: the CpS above comes from it alone."),
    "einstein": ("tt_charge", 1, "Your Time Tower holds &a+1 &7charge."),
    "galaxy": ("flat_mult", 0, "Shines on every bar: the multiplier above is its gift."),
    "king": ("golden_stray", 2.0, "Golden stray rabbits appear &a2x &7as often."),
    "mu": ("stray_linger", 5, "Stray rabbits stay &a+5s &7longer."),
    "napoleon": ("click_bonus", 0.5, "Hand-Baked clicks make &a+50% &7Chocolate."),
    "omega": ("unique_chance", 0.05, "&a+5% &7chance that an egg holds a rabbit you have not found."),
    "sigma": ("tt_duration", 15, "Your Time Tower lasts &a+15 &7minutes longer."),
    "zeta": ("stray_rate", 0.10, "Stray rabbits appear &a10% &7more often."),
    "zorro": ("dupe_bonus", 0.20, "Duplicate rabbits give &a+20% &7Chocolate."),
    "zest_zephyr": ("flat_cps", 0, "The last of the Factory milestones."),
}


def load(*parts):
    with open(os.path.join(RES, *parts), encoding="utf-8") as f:
        return json.load(f)


def strip(s):
    return re.sub("§.", "", s)


def norm(name):
    return strip(name).strip().lower().replace(" ", "_").replace("-", "_")


def skull(item_id):
    path = os.path.join(RES, "neu-repo", "items", item_id + ".json")
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as f:
        d = json.load(f)
    m = re.search(r'Value:\\?"([A-Za-z0-9+/=]+)\\?"', d.get("nbttag", ""))
    return m.group(1) if m else None


def main():
    hop = load("neu-repo", "constants", "hoppity.json")["hoppity"]
    eggs = load("skyhanni-repo", "constants", "HoppityEggLocations.json")
    tex = load("skyhanni-repo", "constants", "HoppityRabbitTextures.json")["textures"]

    rarity_of, ref_names = {}, set()
    for r in RARITIES:
        for rid in hop["rarities"][r]["rabbits"]:
            rarity_of[rid] = r
            ref_names.add(rid.replace("_", " ").lower())

    # display names of the reference (texture file has the canonical spelling, e.g. "El Dorado", "Mush Mush")
    ref_display, texture = {}, {}
    rarity_default_tex = {}
    for r, groups in tex.items():
        for g in groups:
            rarity_default_tex.setdefault(r, g["texture_value_b64"])
            for nm in g["rabbits"]:
                ref_display[norm(nm)] = nm
                texture[norm(nm)] = g["texture_value_b64"]

    factory = [(m["amount"], norm(m["rabbit"])) for m in eggs["chocolateFactoryMilestones"]]
    shop = [(m["amount"], norm(m["rabbit"])) for m in eggs["chocolateShopMilestones"]]
    assert len(factory) == len(FACTORY_NAMES) == 26, len(factory)
    for _, rid in factory + shop:
        assert rid in rarity_of, rid

    residents = {}
    for ref_isl, names in eggs["residentLocations"].items():
        for nm in names:
            rid = norm(nm)
            assert rid in rarity_of, nm
            residents[rid] = ISLANDS[ref_isl]

    # ---- assign our names --------------------------------------------------------------------------------------
    rng = random.Random(20261006)
    ours = {}
    for i, (_, rid) in enumerate(factory):
        ours[rid] = FACTORY_NAMES[i]
    for rid in GREEK:
        if rid in rarity_of:
            ours[rid] = rid.capitalize()
    ours.update(MYTHIC_NAMES)
    ours.update(DIVINE_NAMES)

    taken = set(n.lower() for n in ours.values())

    def pick(pool_iter, rid):
        for cand in pool_iter:
            low = cand.lower()
            if low in taken or low in ref_names or low.replace(" ", "_") in rarity_of:
                continue
            taken.add(low)
            ours[rid] = cand
            return
        raise SystemExit("ran out of names for " + rid)

    leg = list(LEGENDARY_POOL)
    rng.shuffle(leg)
    two = [a + " " + n for a in ADJ for n in NOUN]
    rng.shuffle(two)
    one = [a + b for a in SYL_A for b in SYL_B]
    rng.shuffle(one)
    pools = {"legendary": iter(leg), "epic": iter(two), "rare": iter(two), "uncommon": iter(one), "common": iter(one)}
    # rare + epic share one iterator; common + uncommon share one: no duplicates between them
    pools["rare"] = pools["epic"]
    pools["uncommon"] = pools["common"]
    for r in RARITIES:
        for rid in sorted(hop["rarities"][r]["rabbits"]):
            if rid in ours:
                continue
            if r in ("mythic", "divine"):
                raise SystemExit("no hand-picked name for " + r + " rabbit " + rid)
            pick(pools[r], rid)

    assert len(set(n.lower() for n in ours.values())) == len(ours), "duplicate our-name"

    # ---- rabbits.json ------------------------------------------------------------------------------------------
    special = hop.get("special", {})
    factory_ms = {rid: amt for amt, rid in factory}
    shop_ms = {rid: amt for amt, rid in shop}
    rabbits = []
    for r in RARITIES:
        base = hop["rarities"][r]
        for rid in sorted(base["rabbits"]):
            cps = base["chocolate"]
            mult = base["multiplier"]
            if rid in special:
                cps = special[rid]["chocolate"]
                mult = special[rid]["multiplier"]
            if rid in factory_ms:
                source = "factory_milestone"
            elif rid in shop_ms:
                source = "shop_milestone"
            elif rid == "el_dorado":
                source = "stray"
            else:
                source = "egg"
            e = {
                "id": rid,
                "name": ours[rid],
                "ref": ref_display.get(rid, rid.replace("_", " ").title()),
                "rarity": r.upper(),
                "cps": cps,
                "mult": mult,
                "source": source,
                "texture": texture.get(rid, rarity_default_tex.get(r)),
            }
            if rid in factory_ms:
                e["milestone"] = factory_ms[rid]
            if rid in shop_ms:
                e["milestone"] = shop_ms[rid]
            if rid in residents:
                e["islands"] = residents[rid]
            if rid in MYTHIC_ABILITY:
                k, v, txt = MYTHIC_ABILITY[rid]
                e["ability"] = k
                e["abilityValue"] = v
                e["abilityText"] = txt
            rabbits.append(e)

    # ---- items.json --------------------------------------------------------------------------------------------
    items = []
    items.append({
        "id": "EGGLOCATOR", "name": "Egg Seeker", "material": "PLAYER_HEAD", "rarity": "COMMON", "category": "NONE",
        "skull": skull("EGGLOCATOR"),
        "lore": ["&7Hums when a hidden &6Chocolate", "&6Rabbit Egg &7is near."],
        "abilities": [{"id": "chocolate_egglocator", "name": "Seek", "trigger": "RIGHT_CLICK", "cooldown": 5,
                       "lore": ["&7Points at the nearest egg on this", "&7island you have not found yet.",
                                "", "&8Only works during the Hunt."]}],
        "tags": ["soulbound"],
    })
    items.append({
        "id": "STEAMED_CHOCOLATE_FISH", "name": "Steamed Chocolate Fish", "material": "PLAYER_HEAD",
        "rarity": "EPIC", "category": "MATERIAL", "skull": skull("STEAMED_CHOCOLATE_FISH"),
        "lore": ["&7Hand this dish to &dHopsworth &7and he", "&7adds you to his Ringphone.", "",
                 "&8A fish with a cocoa crust. Odd, but", "&8he swears by it."],
    })
    items.append({
        "id": "CHOCOLATE_FACTORY_BARN_SKIN", "name": "Chocolate Factory Barn Skin", "material": "PLAYER_HEAD",
        "rarity": "LEGENDARY", "category": "COSMETIC", "skull": skull("CHOCOLATE_FACTORY_BARN_SKIN"),
        "lore": ["&7Consume to unlock the &6Chocolate", "&6Factory &7skin for your Garden barn."],
    })
    items.append({
        "id": "DYE_CHOCOLATE", "name": "Chocolate Dye", "material": "PLAYER_HEAD", "rarity": "EPIC",
        "category": "COSMETIC", "skull": skull("DYE_CHOCOLATE"),
        "lore": ["&7A rich brown dye, sometimes tucked", "&7inside a Chocolate Rabbit Egg.", "",
                 "&8Apply it to dyeable armor."],
    })
    # wave-5 integration (chocolate request 4): alchemy has no mixin system, slayer's mixins are slayer's, so the
    # factory's own mixin lives here. NEU lore: +15 Pet Luck, +0.05x Chocolate, 36 h stacking, needs a Boon Cookie,
    # requires Chocolate Factory VI. Brewing it into God Potions is not built (no God Potion mixin route exists).
    items.append({
        "id": "HOT_CHOCOLATE_MIXIN", "name": "Hot Chocolate Mixin", "material": "PLAYER_HEAD", "rarity": "RARE",
        "category": "NONE", "skull": skull("HOT_CHOCOLATE_MIXIN"),
        "lore": ["&8Brewing Ingredient", "", "&7Gain &d+15 Pet Luck &7and &6+0.05x", "&6Chocolate &7per second.", "",
                 "&7Duration: &a36h 0m", "", "&7The duration of Mixins can be stacked!"],
        "abilities": [{"id": "chocolate_hot_mixin", "name": "Drink", "trigger": "RIGHT_CLICK", "cooldown": 0,
                       "lore": ["&eRight-click to consume!", "&8(Requires an active Boon Cookie)"]}],
        "requirements": ["chocolate:prestige:6"],
    })
    faction = []
    for fname in sorted(os.listdir(os.path.join(RES, "neu-repo", "items"))):
        if not fname.startswith("FACTION_RABBIT_"):
            continue
        iid = fname[:-5]
        rid = iid[len("FACTION_RABBIT_"):].lower()
        if rid not in rarity_of:
            continue
        faction.append(rid)
        items.append({
            "id": iid, "name": ours[rid], "material": "PLAYER_HEAD", "rarity": rarity_of[rid].upper(),
            "category": "NONE", "skull": skull(iid) or texture.get(rid),
            "lore": ["&7A rabbit in a box, ready to move", "&7into your &6Chocolate Factory&7."],
            "abilities": [{"id": "chocolate_add_rabbit", "name": "Move In", "trigger": "RIGHT_CLICK", "cooldown": 0,
                           "lore": ["&7Adds this rabbit to your Factory", "&7if you have not found it yet."]}],
            "extra": {"rabbit": rid},
        })

    # ---- factory.json (numbers) ----------------------------------------------------------------------------------
    factory_json = {
        "_notes": "research/08 6.6 + NEU hoppity.json + SkyHanni HoppityEggLocations.json; every number marked TUNE in "
                  "docs/handoff/chocolate.md is a design value where the research has none.",
        "employees": [
            # cps per level from research/08 6.6; base hire cost / unlock prestige: TUNE
            {"id": "bro", "name": "Rabbit Bro", "cps": 1, "baseCost": 50, "prestige": 1, "youth": True},
            {"id": "cousin", "name": "Rabbit Cousin", "cps": 2, "baseCost": 200, "prestige": 1, "youth": True},
            {"id": "sis", "name": "Rabbit Sis", "cps": 3, "baseCost": 600, "prestige": 1, "youth": True},
            {"id": "daddy", "name": "Rabbit Daddy", "cps": 4, "baseCost": 1500, "prestige": 1, "youth": False},
            {"id": "granny", "name": "Rabbit Granny", "cps": 5, "baseCost": 3500, "prestige": 1, "youth": False},
            {"id": "uncle", "name": "Rabbit Uncle", "cps": 6, "baseCost": 7000, "prestige": 3, "youth": False},
            {"id": "dog", "name": "Rabbit Dog", "cps": 7, "baseCost": 14000, "prestige": 5, "youth": False},
        ],
        "employeeGrowth": 1.06,
        "levelCapBase": 100,
        "levelCapPerPrestige": 20,
        "prestiges": [
            {"level": 1, "requirement": 0, "bonus": hop["prestigeMultipliers"]["1"]},
            {"level": 2, "requirement": 150_000_000, "bonus": hop["prestigeMultipliers"]["2"]},
            {"level": 3, "requirement": 1_000_000_000, "bonus": hop["prestigeMultipliers"]["3"]},
            {"level": 4, "requirement": 4_000_000_000, "bonus": hop["prestigeMultipliers"]["4"]},
            {"level": 5, "requirement": 10_000_000_000, "bonus": hop["prestigeMultipliers"]["5"]},
            {"level": 6, "requirement": 30_000_000_000, "bonus": hop["prestigeMultipliers"]["6"]},
        ],
        "handBaked": {"max": 10, "costPerLevel": 500},
        "barn": {"start": 20, "perLevel": 2, "baseCost": 5000, "growth": 1.04},
        "timeTower": {"prestige": 2, "max": 15, "perLevel": 0.1, "charges": 3, "chargeHours": 8,
                      "minutes": 60, "baseCost": 5_000_000, "growth": 1.5},
        "shrine": {"prestige": 3, "max": 20, "perLevel": 0.01, "baseCost": 10_000_000, "growth": 1.25},
        "coach": {"prestige": 4, "max": 20, "perLevel": 0.01, "baseCost": 20_000_000, "growth": 1.3},
        "hitmanCosts": eggs["hitman_costs"],
        "factoryMilestones": [{"amount": a, "rabbit": r} for a, r in factory],
        "shopMilestones": [{"amount": a, "rabbit": r} for a, r in shop],
        "meals": [
            {"id": "breakfast", "name": "Chocolate Breakfast Egg", "hour": 7, "color": "6", "side": False},
            {"id": "brunch", "name": "Chocolate Brunch Egg", "hour": 10, "color": "e", "side": True},
            {"id": "lunch", "name": "Chocolate Lunch Egg", "hour": 14, "color": "9", "side": False},
            {"id": "dejeuner", "name": "Chocolate Dejeuner Egg", "hour": 17, "color": "d", "side": True},
            {"id": "dinner", "name": "Chocolate Dinner Egg", "hour": 21, "color": "a", "side": False},
            {"id": "supper", "name": "Chocolate Supper Egg", "hour": 0, "color": "c", "side": True},
        ],
        # TUNE: egg rarity weights (per mille)
        "eggOdds": {"COMMON": 450, "UNCOMMON": 300, "RARE": 150, "EPIC": 70, "LEGENDARY": 25, "MYTHIC": 4,
                    "DIVINE": 1},
        # TUNE: a duplicate pays this many seconds of current production (with a floor per rarity)
        "duplicateSeconds": {"COMMON": 120, "UNCOMMON": 240, "RARE": 480, "EPIC": 900, "LEGENDARY": 1800,
                             "MYTHIC": 3600, "DIVINE": 7200},
        "duplicateFloor": {"COMMON": 50, "UNCOMMON": 120, "RARE": 300, "EPIC": 800, "LEGENDARY": 2500,
                           "MYTHIC": 8000, "DIVINE": 20000},
        "talismans": [
            # cps from NEU hoppity.json talisman; unique-rabbit % from the item lore; prestige / cost: TUNE
            {"item": k, "cps": v, "unique": [0.02, 0.04, 0.06, 0.08, 0.10][i], "prestige": i + 2,
             "cost": [25_000_000, 100_000_000, 300_000_000, 1_000_000_000, 3_000_000_000][i]}
            for i, (k, v) in enumerate(hop["talisman"].items())
        ],
        "skyXp": {"prestige": 25, "mythic": 10, "divine": 10},
        "factionRabbits": faction,
        "npcSkin": skull("HOPPITY_NPC"),
    }

    shop_json = [
        # TUNE: every price (the reference shop has no public price table in our research)
        {"item": "EGGLOCATOR", "cost": 50_000, "prestige": 1},
        {"item": "STEAMED_CHOCOLATE_FISH", "cost": 10_000_000, "prestige": 1},
        {"item": "DYE_CHOCOLATE", "cost": 250_000_000, "prestige": 2},
        {"item": "PET_ITEM_CHOCOLATE_SYRINGE", "cost": 500_000_000, "prestige": 3},
        {"item": "CHOCOLATE_FACTORY_BARN_SKIN", "cost": 2_000_000_000, "prestige": 4},
        {"item": "HOT_CHOCOLATE_MIXIN", "cost": 1_500_000_000, "prestige": 6, "optional": True},
        {"item": "ZORROS_CAPE", "cost": 4_000_000_000, "prestige": 1, "requires": "zorro", "optional": True},
    ] + [{"item": t["item"], "cost": t["cost"], "prestige": t["prestige"], "talisman": True}
         for t in factory_json["talismans"]]

    os.makedirs(OUT, exist_ok=True)
    for name, data in (("rabbits.json", rabbits), ("items.json", items), ("factory.json", factory_json),
                       ("shop.json", shop_json)):
        with open(os.path.join(OUT, name), "w", encoding="utf-8", newline="\n") as f:
            json.dump(data, f, indent=1, ensure_ascii=False)
            f.write("\n")

    # ---- names table -------------------------------------------------------------------------------------------
    lines = [
        "# chocolate — renames",
        "",
        "Every reference proper noun the chocolate module shows, with our name. Ids keep the reference id.",
        "Generic words stay generic: Chocolate Factory, Chocolate Shop, Rabbit Barn, Time Tower, Rabbit Shrine,",
        "Hand-Baked Chocolate, the employee titles (Rabbit Bro, Cousin, Sis, Daddy, Granny, Uncle, Dog), the meal",
        "names (Breakfast, Brunch, Lunch, Dejeuner, Dinner, Supper), Jackpot, Chocolate Mountain and the 24",
        "Greek-letter rabbits (Alpha ... Omega). Generated by `tools/gen/chocolate/gen_chocolate.py`.",
        "",
        "| id | reference name | our name |",
        "|---|---|---|",
        "| `hoppity` (NPC) | Hoppity | Hopsworth (same as garden's visitor) |",
        "| `hoppitys_hunt` (event) | Hoppity's Hunt | Hopsworth's Hunt |",
        "| `hoppity` (collection menu) | Hoppity's Collection | Hopsworth's Collection |",
        "| `coach` | Coach Jackrabbit | Coach Longstride |",
        "| `hitman` | Rabbit Hitman | Rabbit Bounty Hunter |",
        "| `EGGLOCATOR` | Egglocator | Egg Seeker |",
        "| `STEAMED_CHOCOLATE_FISH` | Fish Chocolat a la Vapeur | Steamed Chocolate Fish |",
        "| `dorado` (golden stray) | El Dorado stray | Goldenpaw stray |",
    ]
    for e in rabbits:
        if e["name"].lower() != e["ref"].lower():
            lines.append("| `%s` | %s | %s |" % (e["id"], e["ref"], e["name"]))
    with open(NAMES_MD, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")

    print("rabbits", len(rabbits), "items", len(items), "faction", len(faction), "shop", len(shop_json))


if __name__ == "__main__":
    main()
