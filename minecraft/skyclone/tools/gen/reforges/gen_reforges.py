"""Generate plugin/src/main/resources/data/reforges/*.json and docs/names/reforges.md

Sources (research/data):
  neu-repo/constants/reforges.json       50 blacksmith reforges (stats per rarity)
  neu-repo/constants/reforgestones.json  85 reforge stones (stats, apply costs, bonus text)
  neu-repo/constants/gemstones.json      gem stat per quality x rarity, removal fees, chisel %
  items.json                             stone rarity/skin, upgrade_costs (stars), gemstone_slots,
                                         reference category of every reforgeable item
  bazaar.json                            snapshot prices for the stone-merchant fallback shop
  neu-repo/items/*.json                  skins for essence, "Requires Mining Skill N" on stones

Every player-facing string is OUR wording: reforge-bonus text is rewritten below (BONUS) and the
numbers per rarity are pulled out of the reference text in order, so only the numbers survive.

Run:  py -3 tools/gen/reforges/gen_reforges.py
"""
import json
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
DATA = os.path.join(ROOT, "research", "data")
NEU = os.path.join(DATA, "neu-repo")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "reforges")
NAMES = os.path.join(ROOT, "docs", "names", "reforges.md")

RARITIES = ["COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE", "SPECIAL", "VERY_SPECIAL"]
RCOL = {"COMMON": "&f", "UNCOMMON": "&a", "RARE": "&9", "EPIC": "&5", "LEGENDARY": "&6", "MYTHIC": "&d",
        "DIVINE": "&b", "SPECIAL": "&c", "VERY_SPECIAL": "&c"}

# reference stat key -> our Stat enum name ("DAMAGE" = weapon damage, handled by a contributor).
STAT = {
    "health": "HEALTH", "defense": "DEFENSE", "true_defense": "TRUE_DEFENSE", "strength": "STRENGTH",
    "intelligence": "INTELLIGENCE", "crit_chance": "CRIT_CHANCE", "crit_damage": "CRIT_DAMAGE",
    "bonus_attack_speed": "ATTACK_SPEED", "ability_damage": "ABILITY_DAMAGE", "ferocity": "FEROCITY",
    "speed": "SPEED", "magic_find": "MAGIC_FIND", "sea_creature_chance": "SEA_CREATURE_CHANCE",
    "fishing_speed": "FISHING_SPEED", "mining_speed": "MINING_SPEED", "mining_fortune": "MINING_FORTUNE",
    "block_fortune": "BLOCK_FORTUNE", "breaking_power": "BREAKING_POWER", "farming_fortune": "FARMING_FORTUNE",
    "foraging_fortune": "FORAGING_FORTUNE", "farming_wisdom": "FARMING_WISDOM", "foraging_wisdom": "FORAGING_WISDOM",
    "mining_wisdom": "MINING_WISDOM", "heat_resistance": "HEAT_RESISTANCE", "bonus_pest_chance": "PEST_CHANCE",
    "vitality": "VITALITY", "sweep": "SWEEP", "damage": "DAMAGE",
    # wave 5: core added both hunting stats
    "hunting_fortune": "HUNTING_FORTUNE", "hunting_wisdom": "HUNTING_WISDOM",
}
# Reference stats with no Stat in this game. They are NOT dropped any more (wave 5): each row carries them as
# "otherStats" {RARITY: {key: v}}, so the module that owns the mechanic reads the number by reforge id
# (weapons: fear, garden/farming: overbloom, hunting: pull).
OTHER_STAT = {"fear": ("Fear", "5"), "overbloom": ("Overbloom", "d"), "pull": ("Pull", "b")}
STAT_LABEL = {
    "HEALTH": ("Health", "c", False), "DEFENSE": ("Defense", "a", False), "TRUE_DEFENSE": ("True Defense", "a", False),
    "STRENGTH": ("Strength", "c", False), "INTELLIGENCE": ("Intelligence", "a", False),
    "CRIT_CHANCE": ("Crit Chance", "c", True), "CRIT_DAMAGE": ("Crit Damage", "c", True),
    "ATTACK_SPEED": ("Bonus Attack Speed", "c", True), "ABILITY_DAMAGE": ("Ability Damage", "c", True),
    "FEROCITY": ("Ferocity", "c", False), "SPEED": ("Speed", "a", False), "MAGIC_FIND": ("Magic Find", "a", False),
    "SEA_CREATURE_CHANCE": ("Sea Creature Chance", "a", True), "FISHING_SPEED": ("Fishing Speed", "a", False),
    "MINING_SPEED": ("Mining Speed", "a", False), "MINING_FORTUNE": ("Mining Fortune", "a", False),
    "BLOCK_FORTUNE": ("Block Fortune", "a", False), "BREAKING_POWER": ("Breaking Power", "a", False),
    "FARMING_FORTUNE": ("Farming Fortune", "a", False), "FORAGING_FORTUNE": ("Foraging Fortune", "a", False),
    "FARMING_WISDOM": ("Farming Wisdom", "a", False), "FORAGING_WISDOM": ("Foraging Wisdom", "a", False),
    "MINING_WISDOM": ("Mining Wisdom", "a", False), "HEAT_RESISTANCE": ("Heat Resistance", "a", False),
    "PEST_CHANCE": ("Bonus Pest Chance", "a", True), "VITALITY": ("Vitality", "a", False), "SWEEP": ("Sweep", "a", False),
    "DAMAGE": ("Damage", "c", False),
    "HUNTING_FORTUNE": ("Hunting Fortune", "d", False), "HUNTING_WISDOM": ("Hunting Wisdom", "3", False),
}

# Renamed reforges (proper nouns of the reference game). Everything else is a plain adjective.
REFORGE_RENAME = {"Jerry's": "Goober's"}

# Renamed stone / item display names (proper nouns or invented brand words).
ITEM_RENAME = {
    "SADAN_BROOCH": "Warden's Brooch",
    "JADERALD": "Jadecrown",
    "TERRY_SNOWGLOBE": "Tobin's Snowglobe",
    "DAEDALUS_NOTES": "Artificer's Notes",
    "PRECURSOR_GEAR": "Forerunner Gear",
    "SKYMART_BROCHURE": "Market Brochure",
    "PITCHIN_KOI": "Fastball Koi",
    "MIDAS_JEWEL": "Goldtouch Jewel",
    "JERRY_STONE": "Goober Stone",
    "KUUDRA_MANDIBLE": "Leviathan Mandible",
    "MOONGLADE_JEWEL": "Moonmeadow Jewel",
    "MARSHROOM": "Bogcap",
    "BLAZEN_SPHERE": "Blazing Sphere",
    "DIAMONITE": "Diamondine",
    "RECOMBOBULATOR_3000": "Rarity Reshaper 3000",
    "HOT_POTATO_BOOK": "Roasted Potato Tome",
    "FUMING_POTATO_BOOK": "Smoking Potato Tome",
}

# Reforge bonus text, OUR wording. {0},{1}.. are filled with the numbers found (in order) in the
# reference text for that rarity, so the values stay exact. "impl" says whether code implements it.
BONUS = {
    "Ambered": ("Treasure goblins appear &a+{0}% &7more often while you mine.", True),
    "Beady": ("&6+{0}☘ Farming Fortune &7against pests.", False),
    "Blessed": ("A &a{0}% &7chance for each harvested crop to drop as its enchanted form.", True),
    "Thorny": ("Adds Overbloom for every tier of Thorns across your armor.", False),
    "Blazing": ("Every &c1♨ Heat &7makes worms &a+{1}% &7more likely to dig out of the rock.", True),
    "Greater Spook": ("&5+{0} Fear&7.", False),
    "Calcified": ("&a+{0}❈ Defense &7against sea creatures.", True),
    "Candied": ("&a+{0}% &7chance to find candy during the autumn festival.", True),
    "Buzzing": ("Vacuums deal &cdouble ❁ damage&7.", False),
    "Erudite": ("Burrow trails always run &a{0} &7digs longer.", False),
    "Fleet": ("&e+{0}▚ Mining Spread &7when breaking plain blocks.", True),
    "Fabled": ("Critical hits may deal up to &c{0}% &7bonus damage.", True),
    "Renowned": ("All combat stats and &b✯ Magic Find &7are raised by &a{0}%&7.", True),
    "Perfect": ("Raises your ❈ Defense by &a{0}%&7.", True),
    "Royal": ("&a+{0}% &2᠅ Mithril Powder &7from mining while a Mining Jubilee is on.", True),
    "Hyper": ("Landing a kill gives &f+{0}✦ Speed &7for &a{1}s&7.", True),
    "Coldfused": ("While a Wisp pet is out: &c+{0}❁ Strength&7, &9+{1}☠ Crit Damage&7, and fire pillars break twice as fast.", False),
    "Sticky": ("&a+{0} &7hunting fortune on water shards.", False),
    "Glacial": ("&6+{0}☘ Mining Fortune &7for every &b{1}❄ Cold &7you carry.", True),
    "Fanged": ("Every &a{0}th &7melee hit on a foe deals &c+{1}% &7damage.", True),
    "Lustrous": ("&e+{0}▚ Mining Spread &7during mining festivals.", True),
    "Bountiful": ("Earn &6+{0} coins &7for every crop you harvest.", True),
    "Deep Fried": ("Seasonings turn up &a+{0}% &7more often.", False),
    "Heated": ("&6+{0}☘ Mining Fortune &7and &6+{1}⸕ Mining Speed &7per &c{2}♨ Heat&7.", True),
    "Chomp": ("Each distinct lava sea creature slain with this rod trims their ❤ Health by &a{0}%&7, down to &a-{1}%&7.", True),
    "Magnetic": ("Mining grants &a+{0}% &7more skill experience.", True),
    "Earthy": ("&a+{0}% &7sowdust from farming.", False),
    "Mantid": ("Each pest slain grants &2+{0}ൠ Bonus Pest Chance &7for &a{1} &7minutes, up to &2{2}ൠ&7.", True),
    "Marshy": ("&a+{0}% &7forest whispers.", False),
    "Gilded": ("Kills sometimes shower a nearby player with a handful of coins.", True),
    "Moil": ("&3+{0}☯ Foraging Wisdom&7.", False),
    "Cubic": ("Take &a{0}% &7less damage from Nether creatures.", True),
    "Majestic": ("&f+{0} Speed &7while on a Critter Safari hunt.", True),
    "Fruitful": ("&a+{0}❈ Defense &7while on a mining island.", True),
    "Precise": ("Arrows that strike a mob's head deal &c+{0}% &7damage.", True),
    "Stellar": ("Near a fallen star: &a+{0}% &2᠅ Mithril Powder &7and &6+{1}☘ Mining Fortune&7.", True),
    "Scraped": ("&6+{0}☘ Mining Fortune &7on blocks that match this tool's Ⓟ Breaking Power.", True),
    "Ancient": ("&9+{0}☠ Crit Damage &7for each Catacombs level.", True),
    "Undead": ("Take &a{0}% &7less damage from zombies, skeletons, withers and zombified piglins.", True),
    "Blood-Soaked": ("Life-stealing heals you &a{0}x &7as much.", False),
    "Mithraic": ("&6+{0}☘ Mining Fortune &7on mithril and titanium.", True),
    "Ridiculous": ("Sneak to let one rip: your ☣ Crit Chance drops by &c{0}% &7for &a{1}s&7, but you gain &a+{2}❈ Defense &7for &a{3}s &7and &b+{4} mana&7. Needs &9{5}% ☣ Crit Chance&7.", True),
    "Loving": ("&c+{0}% &7ability damage.", False),
    "Refined": ("A &a{0}% &7chance for each ore you mine to drop as its enchanted form.", True),
    "Auspicious": ("&6+{0}% ☘ Mining Fortune&7.", True),
    "Empowered": ("&a+{0}☄ Mending &7while inside a dungeon.", True),
    "Spiritual": ("A &a{0}% &7chance to summon a spirit decoy when you slay a dungeon foe; nearby foes chase it instead of you.", True),
    "Squeaky": ("Pests return &a{0}% &7sooner.", False),
    "Suspicious": ("&c+{0} &7weapon damage.", False),
    "Dimensional": ("Titanium ore grants &2+{0}᠅ Mithril Powder&7.", True),
    "Toil": ("&3+{0}☯ Foraging Wisdom&7.", False),
    "Withered": ("&c+{0}❁ Strength &7for each Catacombs level.", True),
    "Headstrong": ("Arrows that strike a mob's head deal &c+{0}% &7damage.", True),
}

# Bonuses carried out by ANOTHER module's code (it reads `ItemData.reforge` itself). Shown as bonus:<module>.
BONUS_BY = {"Blood-Soaked": "enchanting"}

ITEM_TYPE_PHRASE = {
    "ARMOR": "armor piece", "EQUIPMENT": "equipment piece", "SWORD": "melee weapon", "SWORD/ROD": "melee weapon or rod",
    "BOW": "bow", "AXE": "axe", "PICKAXE": "pickaxe or drill", "FARMING_TOOL": "farming tool", "ROD": "fishing rod",
    "FISHING_NET": "fishing net", "VACUUM": "vacuum", "CLOAK": "cloak", "BELT": "belt", "HELMET": "helmet",
    "CHESTPLATE": "chestplate",
}

LEGACY_MAT = {"SKULL_ITEM": "PLAYER_HEAD", "INK_SACK": "GRAY_DYE", "REDSTONE_COMPARATOR": "COMPARATOR",
              "GOLD_AXE": "GOLDEN_AXE", "WOOD_SWORD": "WOODEN_SWORD"}

NUM = re.compile(r"[-+]?\d+(?:\.\d+)?")
SECTION = re.compile("§.")


def load(p):
    with open(p, encoding="utf8") as f:
        return json.load(f)


def clean(s):
    return SECTION.sub("", s or "")


def numfmt(v):
    v = float(v)
    return str(int(v)) if v == int(v) else ("%g" % v)


def stat_rows(raw):
    """{RARITY: {ref_stat: v}} -> {RARITY: {OUR_STAT: v}}, dropping stats we do not have."""
    out = {}
    for r, m in (raw or {}).items():
        row = {}
        for k, v in m.items():
            s = STAT.get(k)
            if s:
                row[s] = float(v)
        out[r] = row
    return out


def other_rows(raw):
    """{RARITY: {ref_stat: v}} -> {RARITY: {other_key: v}} for the stats in OTHER_STAT (empty dict when none)."""
    out = {}
    for r, m in (raw or {}).items():
        row = {k: float(v) for k, v in m.items() if k in OTHER_STAT}
        if row:
            out[r] = row
    return out


# Bonus numbers where the reference wiki is newer than NEU's lore. The garden applies these itself (its own dial),
# so the text must say the same number. Squeaky: the wiki's 2.5% pest cooldown per piece at every rarity (NEU: 1%);
# garden dial garden.squeaky-pct.
BONUS_VALUES = {
    "Squeaky": [2.5],
}


def bonus_rows(name, ability):
    if name not in BONUS or not ability:
        return None, False
    tmpl, impl = BONUS[name]
    per = {}
    if isinstance(ability, str):
        ability = {r: ability for r in RARITIES[:7]}
    for r, txt in ability.items():
        nums = [abs(float(x)) for x in NUM.findall(clean(txt))]
        if name in BONUS_VALUES:
            nums = list(BONUS_VALUES[name])
        try:
            per[r] = {"text": "&7" + tmpl.format(*[numfmt(n) for n in nums]), "values": nums}
        except IndexError:
            per[r] = {"text": "&7" + re.sub(r"\{\d\}", "?", tmpl), "values": nums}
    return per, impl


def skull_from_neu(item_id):
    p = os.path.join(NEU, "items", item_id + ".json")
    if not os.path.exists(p):
        return None
    m = re.search(r'Value:\\?"([A-Za-z0-9+/=]+)\\?"', load(p).get("nbttag", ""))
    return m.group(1) if m else None


def neu_mining_req(item_id):
    p = os.path.join(NEU, "items", item_id + ".json")
    if not os.path.exists(p):
        return None
    roman = {"I": 1, "V": 5, "X": 10}
    for line in load(p).get("lore", []):
        t = clean(line)
        m = re.search(r"Requires Mining Skill (\d+)", t)
        if m:
            return int(m.group(1))
        m = re.search(r"Requires Mining Skill Level ([IVX]+)", t)
        if m:
            s = m.group(1)
            total = 0
            for i, ch in enumerate(s):
                v = roman[ch]
                total += -v if i + 1 < len(s) and roman[s[i + 1]] > v else v
            return total
    return None


def material(item):
    m = item.get("material", "STONE")
    return LEGACY_MAT.get(m, m)


def main():
    items = {i["id"]: i for i in load(os.path.join(DATA, "items.json"))["items"]}
    bazaar = load(os.path.join(DATA, "bazaar.json"))["products"]
    bs = load(os.path.join(NEU, "constants", "reforges.json"))
    st = load(os.path.join(NEU, "constants", "reforgestones.json"))
    gems = load(os.path.join(NEU, "constants", "gemstones.json"))

    reforges = []
    renames = []
    ignored = set()

    def rid(name):
        return re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")

    for key, v in bs.items():
        name = REFORGE_RENAME.get(v["reforgeName"], v["reforgeName"])
        for rr in v["reforgeStats"].values():
            ignored.update(k for k in rr if k not in STAT and k not in OTHER_STAT)
        reforges.append({
            "id": rid(v["reforgeName"]), "name": name, "kind": "blacksmith", "types": v["itemTypes"],
            "itemIds": [], "materials": [], "rarities": v.get("requiredRarities") or RARITIES[:6],
            "stats": stat_rows(v["reforgeStats"]), "costs": {}, "stone": None, "bonus": None, "bonusImpl": False,
        })

    item_defs = []
    shop = {}
    for stone_id, v in st.items():
        name = REFORGE_RENAME.get(v["reforgeName"], v["reforgeName"])
        if name != v["reforgeName"]:
            renames.append(("reforge:" + rid(v["reforgeName"]), v["reforgeName"], name))
        types = v.get("itemTypes")
        item_ids, mats, type_str = [], [], None
        if isinstance(types, str):
            type_str = types
        elif isinstance(types, dict):
            item_ids = list(types.get("internalName") or [])
            mats = [x.split(":")[1].upper() for x in (types.get("itemId") or [])]
        for rr in (v.get("reforgeStats") or {}).values():
            ignored.update(k for k in rr if k not in STAT and k not in OTHER_STAT)
        bonus, impl = bonus_rows(v["reforgeName"], v.get("reforgeAbility"))
        reforges.append({
            "id": rid(v["reforgeName"]), "name": name, "kind": "stone", "types": type_str,
            "itemIds": item_ids, "materials": mats, "rarities": v.get("requiredRarities") or RARITIES[:6],
            "stats": stat_rows(v.get("reforgeStats")), "costs": {k: float(c) for k, c in (v.get("reforgeCosts") or {}).items()},
            "stone": stone_id, "bonus": bonus, "bonusImpl": impl,
        })
        if other_rows(v.get("reforgeStats")):
            reforges[-1]["otherStats"] = other_rows(v.get("reforgeStats"))
        if v["reforgeName"] in BONUS_BY:
            reforges[-1]["bonusBy"] = BONUS_BY[v["reforgeName"]]

        # ---- the stone item ----
        ref = items.get(stone_id, {})
        neu_path = os.path.join(NEU, "items", stone_id + ".json")
        ref_name = ref.get("name") or (clean(load(neu_path).get("displayname", "")) if os.path.exists(neu_path) else "")             or stone_id.replace("_", " ").title()
        our_name = ITEM_RENAME.get(stone_id, ref_name)
        if our_name != ref_name:
            renames.append((stone_id, ref_name, our_name))
        rarity = ref.get("tier") or "RARE"
        lore = ["&8Reforge Stone", ""]
        phrase = ITEM_TYPE_PHRASE.get(type_str or "", "few special items")
        article = "an" if phrase[:1].lower() in "aeiou" else "a"   # wave-4 playtest: "a armor piece"
        lore += ["&7Combine with " + article + " &f" + phrase + " &7in a", "&7Reforge Anvil to give it the", "&9" + name + " &7reforge.", ""]
        stats = stat_rows(v.get("reforgeStats"))
        show = "LEGENDARY" if "LEGENDARY" in stats else (list(stats)[-1] if stats else None)
        if show:
            lore.append("&9" + name + " &7(" + RCOL.get(show, "&f") + show.title() + "&7):")
            for s, val in stats[show].items():
                lab, col, pct = STAT_LABEL[s]
                lore.append("&7" + lab + ": &" + col + ("+" if val >= 0 else "") + numfmt(val) + ("%" if pct else ""))
            for k, val in other_rows(v.get("reforgeStats")).get(show, {}).items():
                lab, col = OTHER_STAT[k]
                lore.append("&7" + lab + ": &" + col + ("+" if val >= 0 else "") + numfmt(val))
        if bonus and show and show in bonus:
            lore += ["", "&9" + name + " Bonus"] + wrap(bonus[show]["text"], 38)
        skin = (ref.get("skin") or {}).get("value") if isinstance(ref.get("skin"), dict) else ref.get("skin")
        if not skin:
            skin = skull_from_neu(stone_id)
        mat = material(ref) if ref else "PLAYER_HEAD"
        if mat == "PLAYER_HEAD" and not skin:
            mat = "AMETHYST_SHARD"
        req = neu_mining_req(stone_id)
        d = {"id": stone_id, "name": our_name, "material": mat, "rarity": rarity, "category": "REFORGE_STONE",
             "lore": lore, "tags": ["reforge_stone", "bazaar"], "extra": {"reforge": rid(v["reforgeName"])}}
        if skin:
            d["skull"] = skin
        if req:
            d["requirements"] = ["skill:mining:%d" % req]
        if ref.get("npc_sell_price"):
            d["npcSell"] = float(ref["npc_sell_price"])
        item_defs.append(d)
        # fallback merchant price: bazaar snapshot buy price, floored by stone rarity  // TUNE
        floor = {"UNCOMMON": 5000, "RARE": 10000, "EPIC": 50000, "LEGENDARY": 250000, "MYTHIC": 1000000}.get(rarity, 10000)
        bz = bazaar.get(stone_id, {}).get("quick_status", {}).get("buyPrice", 0) or 0
        price = max(floor, bz)
        mag = 10 ** max(0, len(str(int(price))) - 2)
        shop[stone_id] = float(int(round(price / mag)) * mag)

    # ---- one-off upgrade items ----
    def plain(iid, name, mat, rarity, lore, tags=(), skin=None, cat="MATERIAL", extra=None, max_stack=0):
        ref = items.get(iid, {})
        ref_name = ref.get("name", name)
        our = ITEM_RENAME.get(iid, name)
        if our != ref_name:
            renames.append((iid, ref_name, our))
        d = {"id": iid, "name": our, "material": mat, "rarity": rarity, "category": cat, "lore": lore, "tags": list(tags)}
        sk = skin or ((ref.get("skin") or {}).get("value") if isinstance(ref.get("skin"), dict) else None) or skull_from_neu(iid)
        if mat == "PLAYER_HEAD":
            if sk:
                d["skull"] = sk
            else:
                d["material"] = "NETHER_STAR"
        if ref.get("npc_sell_price"):
            d["npcSell"] = float(ref["npc_sell_price"])
        if extra:
            d["extra"] = extra
        if max_stack:
            d["maxStack"] = max_stack
        item_defs.append(d)

    plain("HOT_POTATO_BOOK", "Roasted Potato Tome", "BOOK", "EPIC",
          ["&7Bind it to a weapon or armor piece", "&7at an anvil for a permanent boost:", "",
           "&7Weapons: &c+2 Damage &7and &c+2❁ Strength", "&7Armor: &a+4❤ Health &7and &a+2❈ Defense", "",
           "&7Up to &a10 &7per item."], tags=["glow", "upgrade_item"])
    plain("FUMING_POTATO_BOOK", "Smoking Potato Tome", "BOOK", "EPIC",
          ["&7Same boost as a Roasted Potato Tome,", "&7but it only binds to an item that", "&7already holds &a10 &7of those.", "",
           "&7Weapons: &c+2 Damage &7and &c+2❁ Strength", "&7Armor: &a+4❤ Health &7and &a+2❈ Defense", "",
           "&7Up to &a5 &7more per item (&a15 &7total)."], tags=["glow", "upgrade_item"])
    plain("RECOMBOBULATOR_3000", "Rarity Reshaper 3000", "PLAYER_HEAD", "LEGENDARY",
          ["&7Fold it into an item at an anvil to", "&7raise that item's rarity by &aone tier&7.", "",
           "&7Reforges, gems and costs follow the", "&7new rarity. Once per item."], tags=["upgrade_item"])
    plain("THE_ART_OF_WAR", "The Art of War", "BOOK", "LEGENDARY",
          ["&7Study it at an anvil with a weapon", "&7to grant it &c+5❁ Strength&7.", "", "&7Once per weapon."],
          tags=["glow", "upgrade_item"])
    plain("THE_ART_OF_PEACE", "The Art of Peace", "BOOK", "LEGENDARY",
          ["&7Study it at an anvil with an armor", "&7piece to grant it &a+40❤ Health&7.", "", "&7Once per armor piece."],
          tags=["glow", "upgrade_item"])
    plain("POLARVOID_BOOK", "Polarvoid Book", "BOOK", "RARE",
          ["&8Combinable in an anvil", "", "&7Bind it to a drill for &6+10⸕ Mining", "&6Speed&7. Up to &a5 &7per drill.", "",
           "&7With at least one bound, the drill", "&7also gains &6+5☘ Mining Fortune&7."], tags=["glow", "upgrade_item"])
    plain("BOOK_OF_STATS", "Book of Stats", "BOOK", "EPIC",
          ["&8Combinable in an anvil", "", "&7Bind it to a weapon to count its", "&7kills, or to a farming tool to", "&7count the crops it breaks."],
          tags=["glow", "upgrade_item", "bazaar"], cat="COSMETIC")
    # wave 5: Silex (NEU lore: pickaxe or drill, Efficiency +1 per Silex up to X, needs Efficiency V; Hard Stone VII recipe)
    plain("SIL_EX", "Silex", "FLINT", "EPIC",
          ["&8Combinable in an anvil", "", "&7Bind it to a pickaxe or drill to", "&7raise its &9Efficiency &7by one level,",
           "&7up to &9Efficiency X&7.", "", "&7The tool needs &9Efficiency V &7first."], tags=["glow", "upgrade_item", "bazaar"])
    # wave 5: power scrolls (NEU lore: one per item with a RIGHT CLICK ability; Gemstone collection VII recipes).
    # extra = what the scroll does when that ability is used; the module reads it, so the numbers live here only.
    for gem, col, effect, lore_fx in [
        ("RUBY", "c", {"heal_missing_pct": 1, "cooldown": 5}, ["&7Heal &c1% &7of your missing &c❤ Health&7.", "&85s cooldown."]),
        ("SAPPHIRE", "b", {"mana": 5, "cooldown": 5}, ["&7Gain &b+5 Mana&7.", "&85s cooldown."]),
        ("AMBER", "6", {"stat": "MINING_SPEED", "value": 30, "seconds": 5}, ["&7Gain &6+30⸕ Mining Speed &7for &a5s&7."]),
        ("AMETHYST", "5", {"stat": "DEFENSE", "value": 10, "seconds": 5}, ["&7Gain &a+10❈ Defense &7for &a5s&7."]),
        ("JASPER", "d", {"stat": "STRENGTH", "value": 10, "seconds": 5}, ["&7Gain &c+10❁ Strength &7for &a5s&7."]),
        ("OPAL", "f", {"stat": "TRUE_DEFENSE", "value": 10, "seconds": 5}, ["&7Gain &f+10❂ True Defense &7for &a5s&7."]),
    ]:
        title = gem.title() + " Power Scroll"
        plain(gem + "_POWER_SCROLL", title, "PAPER", "EPIC",
              ["&8Combinable in an anvil", "", "&7Bind it to an item with a &eRIGHT", "&eCLICK &7ability. Each use of that", "&7ability:"]
              + lore_fx + ["", "&7One power scroll per item; a new one", "&7replaces the old."],
              tags=["glow", "upgrade_item", "bazaar"], extra=dict({"power_scroll": gem, "color": col}, **effect))
    # fishing hand-off request 8: the gorF drop (also a forge/crafting ingredient elsewhere); a curio, written backwards
    plain("REVERSE_REFORGE_STONE_CORE", "eroC enotS derorriM", "CLAY_BALL", "RARE",
          ["&7?retaw eht ni gnihtemos morf", "&7erehwemos fo traeh ehT", "", "&8A curio. Other recipes use it."],
          tags=["bazaar"])
    ordinal = ["FIRST", "SECOND", "THIRD", "FOURTH", "FIFTH"]
    for n, o in enumerate(ordinal, 1):
        plain(o + "_MASTER_STAR", o.title() + " Master Star", "PLAYER_HEAD", "EPIC" if n < 5 else "LEGENDARY",
              ["&7Set it into a fully starred &c✪✪✪✪✪", "&7dungeon item at an anvil to add a", "&cmaster star &7(" + "&c" + "➊➋➌➍➎"[n - 1] + "&7).",
               "", "&7Each master star raises the item's", "&7stats by &a5% &7in master-mode dungeons.",
               "", "&7Needs master star &c#" + str(n - 1) + " &7first." if n > 1 else "&7The first of five."],
              tags=["upgrade_item", "dungeon"], extra={"master_star": n})
    enrich = [("STRENGTH", "Strength", "c", 1), ("INTELLIGENCE", "Intelligence", "b", 2), ("CRITICAL_CHANCE", "Crit Chance", "9", 1),
              ("CRITICAL_DAMAGE", "Crit Damage", "9", 1), ("DEFENSE", "Defense", "a", 1), ("HEALTH", "Health", "c", 3),
              ("WALK_SPEED", "Speed", "f", 1), ("MAGIC_FIND", "Magic Find", "b", 0.5), ("FEROCITY", "Ferocity", "c", 0.3),
              ("SEA_CREATURE_CHANCE", "Sea Creature Chance", "3", 0.3), ("ATTACK_SPEED", "Bonus Attack Speed", "e", 0.5)]
    enrich_rows = {}
    for key, lab, col, val in enrich:
        stat = {"CRITICAL_CHANCE": "CRIT_CHANCE", "CRITICAL_DAMAGE": "CRIT_DAMAGE", "WALK_SPEED": "SPEED"}.get(key, key)
        enrich_rows[key.lower()] = {"stat": stat, "value": val, "label": lab, "color": col}
        plain("TALISMAN_ENRICHMENT_" + key, lab + " Enrichment", "PLAYER_HEAD", "SPECIAL",
              ["&7Fuse it into a &6Legendary &7or rarer", "&7accessory at an anvil to grant", "&" + col + "+" + numfmt(val) + " " + lab + " &7on that accessory.",
               "", "&7One enrichment per accessory; a new", "&7one replaces the old."],
              tags=["upgrade_item"], extra={"enrichment": key.lower()})
    # Essence: a currency the player carries. Picking one up deposits it.
    essence_types = ["WITHER", "UNDEAD", "DRAGON", "SPIDER", "CRIMSON", "ICE", "GOLD", "DIAMOND", "FOREST", "FOSSIL"]
    for e in essence_types:
        plain("ESSENCE_" + e, e.title() + " Essence", "PLAYER_HEAD", "RARE",
              ["&7A wisp of distilled power, spent to", "&7add &6✪ stars &7to gear at the", "&7Essence menu (&e/essence&7).", "",
               "&eRight-click to store it!"], tags=["essence", "usable"], extra={"essence": e})

    # ---- stars: upgrade_costs per item ----
    stars = {}
    for iid, it in items.items():
        uc = it.get("upgrade_costs")
        if not uc:
            continue
        lst = []
        for step in uc:
            row = []
            for c in step:
                if c.get("type") == "ESSENCE":
                    row.append({"essence": c["essence_type"], "amount": int(c["amount"])})
                elif c.get("type") == "ITEM":
                    if len(c["item_id"]) < 3:
                        continue  # "X"/"Y"/"Z": variable inputs the reference API leaves unnamed  // TUNE
                    row.append({"item": c["item_id"], "amount": int(c.get("amount", 1))})
                elif c.get("type") == "COINS":
                    row.append({"coins": float(c.get("coins", 0))})
            lst.append(row)
        stars[iid] = {"costs": lst, "dungeon": bool(it.get("dungeon_item"))}

    # Audit A1 (2026-10-06, crimson lane): the Leviathan-line (Kuudra) armour pays COINS on stars 4-10 (and Infernal
    # 11-15): 10k/25k/50k/100k/250k/500k/1M, then 2.5M..50M. The Hypixel API's upgrade_costs leave the coin part out;
    # NEU constants/essencecosts.json has it. Only these 100 pieces for now - NEU lists coin parts for ~530 items of
    # every essence type, which is a wider change for this module's owner.
    # Follow-up (2026-10-06, integrator): now applied to EVERY starrable item NEU lists a coin part for (~527 items:
    # dungeon gear 10k/25k on stars 4-5, dragon/spider/ice/gold/diamond/crimson ladders 10k..1M on 4-10, 15-star
    # ladders 2.5M..50M on 11-15). A star that already carries a coin cell is left alone (no double charge).
    with open(os.path.join(NEU, "constants", "essencecosts.json"), encoding="utf8") as f:
        ess_costs = json.load(f)
    for iid, st in stars.items():
        for star, cells in ((ess_costs.get(iid) or {}).get("items") or {}).items():
            n = int(star)
            if n < 1 or n > len(st["costs"]):
                continue
            if any("coins" in c for c in st["costs"][n - 1]):
                continue
            for c in cells:
                if c.startswith("SKYBLOCK_COIN:"):
                    st["costs"][n - 1].append({"coins": float(c.split(":")[1])})

    # ---- gem slots ----
    slots = {}
    for iid, it in items.items():
        gs = it.get("gemstone_slots")
        if not gs:
            continue
        out = []
        for s in gs:
            costs = []
            for c in s.get("costs") or []:
                if c.get("type") == "COINS":
                    costs.append({"coins": float(c["coins"])})
                elif c.get("type") == "ITEM":
                    costs.append({"item": c["item_id"], "amount": int(c.get("amount", 1))})
            out.append({"type": s["slot_type"], "costs": costs})
        slots[iid] = out

    # ---- gem stats ----
    gem_stat = {"Health": "HEALTH", "Mining Speed": "MINING_SPEED", "Pristine": "PRISTINE", "Mining Fortune": "MINING_FORTUNE",
                "Intelligence": "INTELLIGENCE", "Defense": "DEFENSE", "Strength": "STRENGTH", "True Defense": "TRUE_DEFENSE",
                "Crit Damage": "CRIT_DAMAGE", "Critical Damage": "CRIT_DAMAGE", "Fishing Speed": "FISHING_SPEED",
                "Sea Creature Chance": "SEA_CREATURE_CHANCE", "Foraging Fortune": "FORAGING_FORTUNE", "Farming Fortune": "FARMING_FORTUNE"}
    gem_out = {"types": {}, "removal": gems.get("removalCosts", {}), "chisel": gems.get("chiselPercentages", {})}
    for g, gd in gems["gemstoneTypes"].items():
        s = gem_stat.get(gd["statName"])
        if not s:
            print("WARN unknown gem stat", g, gd["statName"])
            continue
        gem_out["types"][g] = {"stat": s, "values": {q: {r: float(x) for r, x in row.items()} for q, row in gd["stats"].items()}}

    # ---- reference categories of every item a reforge or gem could touch ----
    cats = {}
    for iid, it in items.items():
        c = it.get("category")
        if c in ("SWORD", "LONGSWORD", "BOW", "WAND", "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS", "NECKLACE", "CLOAK", "BELT",
                 "GLOVES", "BRACELET", "PICKAXE", "DRILL", "GAUNTLET", "AXE", "FARMING_TOOL", "FISHING_ROD", "FISHING_WEAPON",
                 "FISHING_NET", "VACUUM", "SPADE", "SHEARS", "LASSO", "HOE", "ACCESSORY"):
            cats[iid] = c

    # ---- recipes for the items we own (registered at runtime only when every ingredient exists) ----
    coll = load(os.path.join(DATA, "collections.json"))["collections"]
    unlock_by_name = {}
    for cat in coll.values():
        for cid, c in cat["items"].items():
            for t in c.get("tiers", []):
                for u in t.get("unlocks", []):
                    m = re.match(r"^(.*) Recipes?$", clean(u).strip())
                    if m:
                        unlock_by_name.setdefault(m.group(1).strip(), "collection:%s:%d" % (cid, t["tier"]))
    cells = ["A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3"]

    def cell(v):
        if not v:
            return ""
        iid, _, amt = v.rpartition(":")
        if not iid:
            return v
        try:
            n = int(float(amt))
        except ValueError:
            return v
        return iid if n == 1 else "%s:%d" % (iid, n)

    recipes = []
    owned = [d["id"] for d in item_defs]
    for iid in owned:
        p = os.path.join(NEU, "items", iid + ".json")
        if not os.path.exists(p):
            continue
        d = load(p)
        ref_name = items.get(iid, {}).get("name") or clean(d.get("displayname", ""))
        unlock = unlock_by_name.get(ref_name)
        cands = []
        if d.get("recipe"):
            cands.append(dict(d["recipe"], type="crafting"))
        for r in d.get("recipes") or []:
            cands.append(r)
        for n, r in enumerate(cands):
            t = (r.get("type") or "crafting").lower()
            rec = {"id": "%s#reforges%d" % (iid, n), "result": iid, "amount": int(float(r.get("count", 1) or 1)), "unlock": unlock}
            if t == "crafting":
                rec["type"] = "CRAFTING"
                rec["grid"] = [cell(r.get(k, "")) for k in cells]
                if not any(rec["grid"]):
                    continue
            elif t == "forge":
                rec["type"] = "FORGE"
                rec["inputs"] = [cell(x) for x in r.get("inputs", [])]
                rec["durationSeconds"] = int(r.get("duration", 0))
                rec["station"] = "Forge"
            else:
                continue
            recipes.append(rec)

    os.makedirs(OUT, exist_ok=True)

    def dump(name, obj):
        with open(os.path.join(OUT, name), "w", encoding="utf8", newline="\n") as f:
            json.dump(obj, f, ensure_ascii=False, indent=1 if name in ("reforges.json", "items.json") else None, sort_keys=False)
        print("wrote", name, os.path.getsize(os.path.join(OUT, name)), "bytes")

    dump("reforges.json", reforges)
    dump("items.json", item_defs)
    dump("stars.json", stars)
    dump("gem_slots.json", slots)
    dump("gems.json", gem_out)
    dump("categories.json", cats)
    dump("stone_shop.json", shop)
    dump("enrichments.json", enrich_rows)
    dump("recipes.json", recipes)
    print("recipes:", len(recipes))
    dump("npc_skins.json", {"blacksmith": skull_from_neu("BLACKSMITH_NPC"), "anvil": skull_from_neu("SMITHMONGER_NPC")})
    print("reforges:", len(reforges), "items:", len(item_defs), "star items:", len(stars), "gem items:", len(slots))
    print("stats with no equivalent (dropped):", sorted(ignored))

    # ---- names table ----
    os.makedirs(os.path.dirname(NAMES), exist_ok=True)
    seen = set()
    rows = []
    for r in renames:
        if r[0] in seen:
            continue
        seen.add(r[0])
        rows.append(r)
    with open(NAMES, "w", encoding="utf8", newline="\n") as f:
        f.write("# reforges — renames\n\n")
        f.write("Generated by `tools/gen/reforges/gen_reforges.py` (edit `ITEM_RENAME` / `REFORGE_RENAME` there and re-run).\n")
        f.write("Reforge names that are plain English adjectives (Spicy, Sharp, Heroic, Fabled, Withered...) and generic item\n")
        f.write("names (Dragon Claw, Wither Essence, The Art of War...) are kept. All reforge-bonus and item lore text is our own wording.\n\n")
        f.write("| id | reference name | our name |\n|---|---|---|\n")
        for iid, a, b in rows:
            f.write("| `%s` | %s | %s |\n" % (iid, a, b))
        f.write("| `npc:reforges_blacksmith` | Blacksmith | Smith Ottmar (title: Blacksmith) |\n")
        f.write("| `npc:reforges_anvil` | Reforge Anvil | Anvil-keeper Sigrun (title: Reforge Anvil) |\n")
        f.write("| menu | Malik's essence shop / Essence upgrade | Essence & Stars |\n")
        f.write("| menu | The Hex / Gemstone Grinder | Gemstone Grinder |\n")
        f.write("| text | Spooky Festival | autumn festival |\n")
        f.write("| text | Golden / Diamond Goblins | treasure goblins |\n")
    print("wrote", NAMES)


def wrap(text, width):
    out, line, vis, carry = [], "", 0, "&7"
    for word in text.split(" "):
        wv = len(re.sub("&[0-9a-fk-or]", "", word))
        if vis and vis + 1 + wv > width:
            out.append(line)
            line, vis = carry, 0
        if vis:
            line += " "
            vis += 1
        line += word
        vis += wv
        for m in re.finditer("&[0-9a-fk-or]", word):
            carry = m.group(0)
    if line:
        out.append(line)
    return out


if __name__ == "__main__":
    main()
