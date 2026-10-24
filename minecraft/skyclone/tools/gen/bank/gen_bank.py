#!/usr/bin/env python3
"""
Generates every data file of the BANK module from the research, so nothing is typed by hand.

    python tools/gen/bank/gen_bank.py            (run from the SkyblockClone folder, or anywhere)

Reads
  research/05-economy-events-mayors.md   section 1.2  bank account tiers           -> tiers.json
                                         section 1.3  interest bands               -> interest.json
                                         section 6.3  fame ranks                   -> fame.json
                                         section 6.4  community shop upgrades      -> upgrades.json (checked against the table)
  research/02-skills-collections-minions-level.md   "Bank upgrades" SkyBlock XP    -> tiers.json sbxp
  research/data/neu-repo/items/ELIZABETH_NPC.json    bits shop (npc_shop recipes)   -> bits_shop.json
  research/data/neu-repo/items/PERSONAL_BANK_ITEM.json  recipe + skull              -> items.json / recipes.json
  research/data/neu-repo/items/BANKER_*_NPC.json, ELIZABETH_NPC.json  skins         -> npcs.json

Writes plugin/src/main/resources/data/bank/*.json. Re-run after editing the research; never hand-edit the JSON.
All player-facing text here is OUR wording (the reference lore is not copied); numbers are the research's.
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
RES = os.path.join(ROOT, "research")
NEU = os.path.join(RES, "data", "neu-repo", "items")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "bank")


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def neu(item_id):
    with open(os.path.join(NEU, item_id + ".json"), encoding="utf-8") as f:
        return json.load(f)


def skull_texture(item):
    m = re.search(r'Value:"([A-Za-z0-9+/=]+)"', item.get("nbttag", ""))
    return m.group(1) if m else None


def section(md, heading):
    """Text of a markdown section from its heading line up to the next heading of the same or higher level."""
    lines = md.splitlines()
    start = None
    level = 0
    for i, line in enumerate(lines):
        if start is None and line.strip().startswith("#") and heading in line:
            start = i
            level = len(line) - len(line.lstrip("#"))
            continue
        if start is not None and line.startswith("#"):
            lv = len(line) - len(line.lstrip("#"))
            if lv <= level:
                return "\n".join(lines[start:i])
    if start is None:
        sys.exit("research heading not found: " + heading)
    return "\n".join(lines[start:])


def table_rows(text):
    """Every markdown table row (list of stripped cells), header and separator rows excluded."""
    rows = []
    header_seen = False
    for line in text.splitlines():
        line = line.strip()
        if not line.startswith("|"):
            header_seen = False
            continue
        cells = [c.strip() for c in line.strip("|").split("|")]
        if all(re.fullmatch(r":?-{2,}:?", c) for c in cells if c):
            continue
        if not header_seen:
            header_seen = True
            continue
        rows.append(cells)
    return rows


def num(s):
    """'50,000,000' -> 50000000 ; '1.0M' -> 1000000 ; '5.16B' -> 5160000000 ; '10M' -> 10000000."""
    s = s.strip().replace(",", "").replace("**", "")
    m = re.fullmatch(r"([0-9.]+)\s*([kKmMbB]?)", s)
    if not m:
        raise ValueError("not a number: " + s)
    v = float(m.group(1))
    v *= {"": 1, "k": 1e3, "K": 1e3, "m": 1e6, "M": 1e6, "b": 1e9, "B": 1e9}[m.group(2)]
    return int(round(v))


TIER_NAMES = ["Starter", "Gold", "Deluxe", "Super Deluxe", "Premier", "Luxurious", "Palatial"]


def tier_index(text):
    # longest name first so "Super Deluxe" is not read as "Deluxe"
    for name in sorted(TIER_NAMES, key=len, reverse=True):
        if text.startswith(name):
            return TIER_NAMES.index(name)
    raise ValueError("unknown tier in: " + text)


def gen_tiers(md05, md02):
    rows = table_rows(section(md05, "### 1.2 Bank account tiers"))
    # research 02: "| Bank upgrades | Gold 20, Deluxe 25, Super Deluxe 30, Premier 35, Luxurious 40, Palatial 50 | 200 |"
    m = re.search(r"\|\s*Bank upgrades\s*\|([^|]+)\|", md02)
    sbxp = {}
    if m:
        for part in m.group(1).split(","):
            mm = re.match(r"\s*(.+?)\s+(\d+)\s*$", part)
            if mm:
                sbxp[mm.group(1).strip()] = int(mm.group(2))
    out = []
    for cells in rows:
        name, cap, cost, req, maxint = cells[:5]
        idx = TIER_NAMES.index(name)
        coins = 0
        blocks = 0
        if cost.strip() not in ("—", "-", ""):
            mc = re.search(r"([0-9,]+)\s*coins", cost)
            mb = re.search(r"\+\s*([0-9,]+)\s*Enchanted Gold Block", cost)
            coins = num(mc.group(1)) if mc else 0
            blocks = num(mb.group(1)) if mb else 0
        gold = 0
        museum = 0
        mg = re.search(r"Gold Collection\s*([0-9.,]+[KMkm]?)", req) or re.search(r"Gold\s+([0-9.,]+[KMkm]?)", req)
        if mg:
            gold = num(mg.group(1))
        mm = re.search(r"Museum Milestone\s*(\d+)", req)
        if mm:
            museum = int(mm.group(1))
        out.append({
            "index": idx,
            "id": name.lower().replace(" ", "_"),
            "name": name,
            "capacity": num(cap),
            "coins": coins,
            "enchantedGoldBlocks": blocks,
            "goldCollection": gold,
            "museumMilestone": museum,
            "maxInterest": num(maxint),
            "sbxp": sbxp.get(name, 0),
        })
    out.sort(key=lambda t: t["index"])
    if [t["name"] for t in out] != TIER_NAMES:
        sys.exit("tier table changed shape: " + str([t["name"] for t in out]))
    return out


def gen_interest(md05):
    rows = table_rows(section(md05, "### 1.3 Interest brackets"))
    bands = []
    for cells in rows:
        rng, rate, full, unlock = cells[:4]
        lo_s, hi_s = re.split(r"\s*[–-]\s*", rng, maxsplit=1)
        rate_v = float(rate.replace("%", "").strip()) / 100.0
        bands.append({
            "lo": num(lo_s),
            "hi": num(hi_s),
            "rate": rate_v,
            "fullBand": num(full),
            "tier": tier_index(unlock),
        })
    # check: each band's "coins from full band" equals width x rate
    for b in bands:
        if abs((b["hi"] - b["lo"]) * b["rate"] - b["fullBand"]) > 1:
            sys.exit("interest band does not add up: " + str(b))
    return bands


def gen_fame(md05):
    rows = table_rows(section(md05, "### 6.3 Fame ranks"))
    out = []
    for cells in rows:
        name, fame, mult, votes = cells[:4]
        out.append({"name": name, "fame": num(fame), "mult": float(mult.replace("×", "").strip()), "votes": int(votes)})
    return out


def shop_category(item_id):
    if item_id.startswith("ABICASE") or item_id.endswith("CONTACTS_ADDON"):
        return "Ringphone"
    if item_id.startswith("DYE_"):
        return "Dyes"
    if ";" in item_id:
        return "Enchanted Books"
    if item_id.startswith("TALISMAN_ENRICHMENT"):
        return "Enrichments"
    if "SACK" in item_id:
        return "Sacks"
    return "Items"


def gen_bits_shop():
    eliz = neu("ELIZABETH_NPC")
    out = []
    for r in eliz.get("recipes", []):
        if r.get("type") != "npc_shop":
            continue
        cost = r.get("cost", [])
        if len(cost) != 1 or not cost[0].startswith("SKYBLOCK_BIT:"):
            continue
        bits = int(cost[0].split(":")[1])
        item = r["result"]
        alts = [item]
        if ";" in item:  # NEU book id "EXPERTISE;1" -> our enchanting module's ids
            ench, lvl = item.split(";", 1)
            alts = ["ENCHANTMENT_" + ench + "_" + lvl, ench + "_" + lvl, item]
        out.append({"item": alts[0], "alternatives": alts[1:], "bits": bits, "category": shop_category(item)})
    if not out:
        sys.exit("no bits shop entries found in ELIZABETH_NPC.json")
    return out


# ---- community shop upgrades (research 05 section 6.4) --------------------------------------------------
# The research gives each upgrade's tier count, effect range and cost/time RANGES; per-tier values in
# between are linear interpolations (marked TUNE in the output). Gem prices are kept as gems and turned
# into coins at runtime by the dial bank.cshop.coins-per-gem (we sell nothing for real money).

def lerp_list(n, a, b):
    if n == 1:
        return [a]
    return [round(a + (b - a) * i / (n - 1), 2) for i in range(n)]


def roman(n):
    vals = [(10, "X"), (9, "IX"), (5, "V"), (4, "IV"), (1, "I")]
    s = ""
    for v, r in vals:
        while n >= v:
            s += r
            n -= v
    return s


UPGRADES = [
    # id, scope, name, row label in the research table, tiers, effect(tier)->text, value(tier),
    # gems per tier, days per tier, research text it must match
    dict(id="island_size", scope="profile", name="Island Size", row="Island Size", tiers=10,
         value=lambda t: 161 + 8 * t, unit="blocks wide",
         gems=[0] + [175] * 5 + [200] * 4, days=[2] + lerp_list(5, 4, 10) + [10] * 4),
    dict(id="minion_slots", scope="profile", name="Minion Slots", row="Minion Slots", tiers=5,
         value=lambda t: t, unit="extra minion slots",
         gems=[0] * 5, days=lerp_list(5, 1, 24)),
    dict(id="guests_limit", scope="profile", name="Guests Limit", row="Guests Limit", tiers=5,
         value=lambda t: t, unit="extra guest slots",
         gems=[0] + [50] * 4, days=[2] + lerp_list(4, 4, 16)),
    dict(id="coop_slots", scope="profile", name="Co-op Slots", row="Co-op Slots", tiers=3,
         value=lambda t: 5 + t, unit="co-op members",
         gems=[600] * 3, days=[0] * 3),
    dict(id="coins_allowance", scope="profile", name="Coins Allowance", row="Coins Allowance", tiers=5,
         value=lambda t: 10000 * t, unit="coins per day",
         gems=[0] * 5, days=lerp_list(5, 5, 25)),
    dict(id="armor_wardrobe", scope="account", name="Armor Wardrobe Slots", row="Armor Wardrobe Slots", tiers=9,
         value=lambda t: t, unit="extra wardrobe slots",
         gems=[0] + lerp_list(8, 250, 500), days=[6] * 9),
    dict(id="equipment_wardrobe", scope="account", name="Equipment Wardrobe Slots", row="Equipment Wardrobe Slots", tiers=9,
         value=lambda t: t, unit="extra equipment wardrobe slots",
         gems=[250] * 9, days=[6] * 9),
    dict(id="loadout_slots", scope="account", name="Loadout Slots", row="Loadout Slots", tiers=9,
         value=lambda t: t, unit="extra loadout slots",
         gems=[500] * 9, days=[6] * 9),
    dict(id="hotm_slots", scope="account", name="Mountain Tree Slots", row="HotM / HotF slots", tiers=3,
         value=lambda t: t, unit="extra tree loadouts",
         gems=[150] * 3, days=[6] * 3),
    dict(id="ender_chest_pages", scope="account", name="Ender Chest Pages", row="Ender Chest Pages", tiers=8,
         value=lambda t: t, unit="extra ender chest pages",
         gems=[0] + lerp_list(7, 75, 150), days=[6] * 8),
    dict(id="accessory_bag", scope="account", name="Accessory Bag", row="Accessory Bag", tiers=6,
         value=lambda t: 2 * t, unit="extra accessory bag slots",
         gems=[0] * 6, days=lerp_list(6, 4, 18)),
    dict(id="sack_of_sacks", scope="account", name="Sack of Sacks", row="Sack of Sacks", tiers=8,
         value=lambda t: t, unit="extra sack slots",
         gems=[0, 0] + lerp_list(6, 100, 300), days=[12] * 8),
    dict(id="magic_find", scope="account", name="Magic Find", row="Magic Find", tiers=5,
         value=lambda t: t, unit="Magic Find",
         gems=[0] * 5, days=lerp_list(5, 1, 16)),
    dict(id="bazaar_flipper", scope="account", name="Bazaar Flipper", row="**Bazaar Flipper**", tiers=2,
         value=lambda t: 7 * t, unit="extra bazaar orders (and -0.125% tax per tier)",
         gems=[0, 100], days=[5, 10]),
    dict(id="garden_farming_fortune", scope="account", name="Garden Farming Fortune", row="Garden Farming Fortune", tiers=10,
         value=lambda t: 4 * t, unit="Farming Fortune in the Garden",
         gems=[0] * 10, days=lerp_list(10, 3, 30)),
]


def gen_upgrades(md05):
    text = section(md05, "### 6.4 Community Shop")
    labels = {cells[0] for cells in table_rows(text)}
    out = []
    for u in UPGRADES:
        if u["row"] not in labels:
            sys.exit("upgrade row missing from research 6.4: " + u["row"])
        assert len(u["gems"]) == u["tiers"] and len(u["days"]) == u["tiers"], u["id"]
        tiers = []
        for t in range(1, u["tiers"] + 1):
            tiers.append({
                "tier": t,
                "label": roman(t),
                "value": u["value"](t),
                "gems": int(round(u["gems"][t - 1])),
                "hours": round(u["days"][t - 1] * 24, 1),
            })
        out.append({"id": u["id"], "scope": u["scope"], "name": u["name"], "unit": u["unit"], "tiers": tiers,
                    "note": "TUNE: per-tier values interpolated from research 05 section 6.4 row '" + u["row"].strip("*") + "'"})
    return out


# ---- items ------------------------------------------------------------------------------------------------

COOKIE_LORE = [
    "&7Eat it to gain the &dBoon Buff &7for",
    "&b4 &7days. Time stacks and keeps ticking",
    "&7while you are offline.",
    "",
    "&8- &7Earn &bBits &7while you play",
    "&8- &3+25 Wisdom &7on every skill",
    "&8- &b+15 Magic Find",
    "&8- &7Your &6coins &7are safe when you die",
    "&8- &eFlight &7on your island and garden",
    "&8- &7Use &a/bank &7from anywhere",
    "&8- &7Remote access to menus that honour it",
    "",
    "&eRight-click to eat!",
]


def gen_items():
    pbank = neu("PERSONAL_BANK_ITEM")
    items = [
        {
            "id": "BOOSTER_COOKIE",
            "name": "Boon Cookie",
            "material": "COOKIE",
            "rarity": "LEGENDARY",
            "category": "CONSUMABLE",
            "glow": True,
            "lore": COOKIE_LORE,
            "abilities": [{"id": "bank_eat_cookie", "name": "Eat", "trigger": "RIGHT_CLICK", "mana": 0, "cooldown": 0,
                           "lore": []}],
            "tags": ["bazaar"],
            "extra": {"bank": "cookie", "hours": 96},
        },
        {
            "id": "FREE_COOKIE",
            "name": "Boon Cookie &8(Gift)",
            "material": "COOKIE",
            "rarity": "LEGENDARY",
            "category": "CONSUMABLE",
            "glow": True,
            "lore": COOKIE_LORE[:-1] + ["&8A welcome gift. It cannot be traded.", "", "&eRight-click to eat!"],
            "abilities": [{"id": "bank_eat_cookie", "name": "Eat", "trigger": "RIGHT_CLICK", "mana": 0, "cooldown": 0,
                           "lore": []}],
            "tags": ["soulbound", "no_trade", "no_ah"],
            "extra": {"bank": "cookie", "hours": 96},
        },
        {
            "id": "PERSONAL_BANK_ITEM",
            "name": "Pocket Bank",
            "material": "PLAYER_HEAD",
            "rarity": "COMMON",
            "category": "NONE",
            "skull": skull_texture(pbank),
            "maxStack": 1,
            "lore": ["&7Your bank account, carried", "&7in your pocket.", ""],
            "abilities": [
                {"id": "bank_pocket_open", "name": "Open Bank", "trigger": "RIGHT_CLICK", "mana": 0, "cooldown": 0,
                 "lore": ["&7Opens your bank account from", "&7anywhere."]},
                {"id": "bank_pocket_deposit", "name": "Quick Deposit", "trigger": "LEFT_CLICK", "mana": 0, "cooldown": 0,
                 "lore": ["&7Puts your whole purse into", "&7the bank."]},
            ],
            "tags": [],
            "extra": {"bank": "pocket"},
        },
    ]
    rec = pbank.get("recipe", {})
    grid = []
    for row in "ABC":
        for col in "123":
            cell = rec.get(row + col, "")
            grid.append(cell if cell else "")
    recipes = [{
        "type": "CRAFTING",
        "result": "PERSONAL_BANK_ITEM",
        "amount": 1,
        "grid": grid,
        "unlock": "collection:EMERALD:6",
        "station": "Crafting Table",
    }]
    return items, recipes


def gen_npcs():
    out = {}
    for key, item in (("banker", "BANKER_BARRY_NPC"), ("banker_2", "BANKER_BONSAI_NPC"), ("community_shop", "ELIZABETH_NPC")):
        out[key] = skull_texture(neu(item))
    return out


def write(name, data):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print("wrote", os.path.relpath(path, ROOT), len(data) if hasattr(data, "__len__") else "")


def main():
    md05 = read(os.path.join(RES, "05-economy-events-mayors.md"))
    md02 = read(os.path.join(RES, "02-skills-collections-minions-level.md"))
    tiers = gen_tiers(md05, md02)
    bands = gen_interest(md05)
    # check: summing every unlocked band reproduces each tier's "Max interest / season"
    for t in tiers:
        cap = sum(b["fullBand"] for b in bands if b["tier"] <= t["index"])
        if cap != t["maxInterest"]:
            sys.exit("interest cap mismatch for %s: bands %d vs table %d" % (t["name"], cap, t["maxInterest"]))
    items, recipes = gen_items()
    write("tiers.json", tiers)
    write("interest.json", bands)
    write("fame.json", gen_fame(md05))
    write("bits_shop.json", gen_bits_shop())
    write("upgrades.json", gen_upgrades(md05))
    write("items.json", items)
    write("recipes.json", recipes)
    write("npcs.json", gen_npcs())


if __name__ == "__main__":
    main()
