# -*- coding: utf-8 -*-
"""
Generates the weapons module's data from the research dumps + the hand-written layer in spec.py.

    python tools/gen/weapons/gen_weapons.py          (run from the SkyblockClone folder)

Reads   research/data/items.json             (stats, tier, material, requirements, sell price, gems)
        research/data/neu-repo/items/*.json  (crafting recipes, crafttext unlocks, slayer_req)
        research/data/collections.json       (which collection tier unlocks which recipe)
        tools/gen/weapons/spec.py            (our names, our descriptions, abilities, traits)
Writes  plugin/src/main/resources/data/weapons/items.json           ItemDef[]  registered in enable()
        plugin/src/main/resources/data/weapons/items_fallback.json  ItemDef[]  registered in postEnable()
                                                                     ONLY if the owner (slayer/dungeons)
                                                                     did not register the id
        plugin/src/main/resources/data/weapons/recipes.json          Recipe[] for items.json
        plugin/src/main/resources/data/weapons/recipes_fallback.json Recipe[] for the fallback items
        plugin/src/main/resources/data/weapons/meta.json             collection id lists the code needs
        docs/names/weapons.md                                         rename table

Ownership (CONTRACT §3 + the weapons brief): weapons owns every sword / longsword / bow / wand /
arrow / arrow poison + the Grappling Hook, EXCEPT
  - SLAYER-requirement items     -> slayer   (we ship a fallback copy, used only if slayer has none)
  - dungeon_item drops            -> dungeons (wave 2; same fallback rule)
  - STARRED_* (fragged dungeon variants), Rift / Bingo items, Kuudra items, UNOBTAINABLE/test/admin
"""
import json, os, re, sys, collections

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from spec import SPEC, TYPE_WORD, EXTRA_RENAMES  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
DATA = os.path.join(ROOT, "research", "data")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "weapons")
NAMES = os.path.join(ROOT, "docs", "names", "weapons.md")
STAT_JAVA = os.path.join(ROOT, "plugin", "src", "main", "java", "com", "skyclone", "api", "stat", "Stat.java")

CATS = ("SWORD", "LONGSWORD", "BOW", "WAND", "ARROW", "ARROW_POISON")
EXTRA_IDS = ("GRAPPLING_HOOK", "ETHERWARP_CONDUIT")
# Items whose reference requirement would hand them to another module but which that module left to us
# (slayer hand-off: "Etherwarp Conduit ... belongs with the weapons module's AOTV").
OWN_OVERRIDE = {"ETHERWARP_CONDUIT": "weapons"}
EXCLUDE = {"WIZARD_WAND", "ARCHER_DUNGEON_ABILITY_2", "ARCHER_DUNGEON_ABILITY_3", "HEALING_CIRCLE_ITEM",
           "ELECTRIC_WAND_ITEM", "SEISMIC_WAVE_STICK", "RAYGUN", "NOVA_SWORD"}

DYE = ["INK_SAC", "RED_DYE", "GREEN_DYE", "COCOA_BEANS", "LAPIS_LAZULI", "PURPLE_DYE", "CYAN_DYE", "LIGHT_GRAY_DYE",
       "GRAY_DYE", "PINK_DYE", "LIME_DYE", "YELLOW_DYE", "LIGHT_BLUE_DYE", "MAGENTA_DYE", "ORANGE_DYE", "BONE_MEAL"]
MATERIAL = {"WOOD_SWORD": "WOODEN_SWORD", "GOLD_SWORD": "GOLDEN_SWORD", "WOOD_AXE": "WOODEN_AXE", "GOLD_AXE": "GOLDEN_AXE",
            "WOOD_SPADE": "WOODEN_SHOVEL", "GOLD_SPADE": "GOLDEN_SHOVEL", "DIAMOND_SPADE": "DIAMOND_SHOVEL",
            "IRON_SPADE": "IRON_SHOVEL", "STONE_SPADE": "STONE_SHOVEL", "GOLD_HOE": "GOLDEN_HOE", "WOOD_HOE": "WOODEN_HOE",
            "GOLD_PICKAXE": "GOLDEN_PICKAXE", "WOOD_PICKAXE": "WOODEN_PICKAXE", "GOLD_BARDING": "GOLDEN_HORSE_ARMOR",
            "RED_ROSE": "POPPY", "YELLOW_FLOWER": "DANDELION", "DOUBLE_PLANT": "SUNFLOWER",
            "REDSTONE_TORCH_ON": "REDSTONE_TORCH", "SKULL_ITEM": "PLAYER_HEAD", "PORK": "PORKCHOP"}
STAT_ALIAS = {"WALK_SPEED": "SPEED", "CRITICAL_DAMAGE": "CRIT_DAMAGE", "CRITICAL_CHANCE": "CRIT_CHANCE",
              "ABILITY_DAMAGE_PERCENT": "ABILITY_DAMAGE"}
SLAYER_CODE = {"ZOMBIE": "zombie", "SPIDER": "spider", "WOLF": "wolf", "EMAN": "enderman", "BLAZE": "blaze", "VAMPIRE": "vampire"}
ROMAN = {r: i for i, r in enumerate(["", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII", "XIII",
                                     "XIV", "XV"]) if r}
VANILLA_IDS = {"WOOD_SWORD", "STONE_SWORD", "IRON_SWORD", "GOLD_SWORD", "DIAMOND_SWORD", "BOW", "ARROW"}


def stat_names():
    src = open(STAT_JAVA, encoding="utf-8").read()
    return set(re.findall(r"^\s{4}([A-Z_]+)\(\"", src, re.M))


def material(it):
    m = it["material"]
    if m == "INK_SACK":
        return DYE[(it.get("durability", 0) or 0) % 16]
    return MATERIAL.get(m, m)


def strip(s):
    return re.sub(r"§.", "", s or "")


def owner_of(it):
    iid = it["id"]
    if iid in OWN_OVERRIDE:
        return OWN_OVERRIDE[iid]
    rq = {r["type"] for r in it.get("requirements", [])}
    if iid in EXCLUDE or it.get("tier") == "UNOBTAINABLE" or iid.startswith("TEST_") or iid.startswith("STARRED_"):
        return None
    if it.get("origin") in ("RIFT", "BINGO") or any(k.lower().startswith("rift_") for k in it.get("stats", {})):
        return None
    if "KUUDRA_COMPLETION" in rq:
        return None
    if "SLAYER" in rq:
        return "slayer"
    if it.get("dungeon_item"):
        return "dungeons"
    return "weapons"


def requirement_keys(it, notes):
    out = []
    for r in it.get("requirements", []):
        t = r["type"]
        if t == "SKILL":
            out.append("skill:%s:%d" % (r["skill"].lower(), r["level"]))
        elif t == "COLLECTION":
            out.append("collection:%s:%d" % (r["collection"], r["tier"]))
        elif t == "SLAYER":
            out.append("slayer:%s:%d" % (r["slayer_boss_type"].lower(), r["level"]))
        elif t == "DUNGEON_SKILL":
            # The reference enforces a Catacombs level only INSIDE a dungeon (playtest: AOTD was dead weight
            # in the Graveyard), so it gets the weapons-owned dungeon-only key, never plain "catacombs:".
            out.append("dungeon_catacombs:%d" % r["level"])
        elif t == "DUNGEON_TIER":
            notes["floorReq"] = ("M" if r.get("dungeon_type") == "MASTER_CATACOMBS" else "F") + str(r.get("tier"))
        elif t == "TARGET_PRACTICE":
            notes["targetPractice"] = r.get("mode", "")   # TUNE: no Target Practice quest exists; the Combat level gate stays
    for r in it.get("catacombs_requirements", []) or []:
        lvl = r.get("level")
        if lvl and not any(k.startswith("dungeon_catacombs:") for k in out):
            out.append("dungeon_catacombs:%d" % lvl)
    return out


def norm_cell(cell):
    if not cell:
        return ""
    cell = cell.strip()
    if ":" in cell:
        iid, amt = cell.rsplit(":", 1)
    else:
        iid, amt = cell, "1"
    iid = re.sub(r"-(\d+)$", r":\1", iid)
    try:
        a = int(float(amt))
    except ValueError:
        a = 1
    return "%s:%d" % (iid, a)


def vs_lines(w):
    out = []
    for t, pct in (w.get("vs") or {}).items():
        if pct:
            out.append("&7Hits on %s &7foes deal &a+%d%% &7damage." % (TYPE_WORD[t], pct))
    for t, x in (w.get("vsMult") or {}).items():
        out.append("&7Deals &a%sx &7damage to %s &7foes." % (("%g" % x), TYPE_WORD[t]))
    for t, pct in (w.get("lessFrom") or {}).items():
        out.append("&7Take &a%d%% &7less damage from %s &7foes" % (pct, TYPE_WORD[t]))
        out.append("&7while you hold it.")
    for t, mf in (w.get("mfVs") or {}).items():
        out.append("&7Grants &b+%g✯ Magic Find &7on %s &7foes." % (mf, TYPE_WORD[t]))
    for t, pct in (w.get("wisdomVs") or {}).items():
        out.append("&7Gain &3+%d☯ Combat Wisdom &7from %s &7kills." % (pct, TYPE_WORD[t]))
    if w.get("shortbow"):
        out.append("&7Shot Cooldown: &a%gs" % w["shortbow"])
    return out


def main():
    known = stat_names()
    items = json.load(open(os.path.join(DATA, "items.json"), encoding="utf-8"))["items"]
    by_id = {i["id"]: i for i in items}
    cols = json.load(open(os.path.join(DATA, "collections.json"), encoding="utf-8"))["collections"]
    unlock_by_name, col_by_name = {}, {}
    for cat in cols.values():
        for cid, col in cat["items"].items():
            col_by_name[col["name"].lower()] = cid
            for t in col["tiers"]:
                for u in t["unlocks"]:
                    m = re.match(r"(.+?) Recipes?$", u.strip())
                    if m:
                        unlock_by_name.setdefault(m.group(1).strip(), "collection:%s:%d" % (cid, t["tier"]))
    col_by_name.update({"gunpowder": "SULPHUR", "slimeball": "SLIME_BALL", "ink sac": "INK_SACK", "raw porkchop": "PORK",
                        "end stone": "ENDER_STONE", "ice": "ICE", "string": "STRING", "bone": "BONE"})

    picked = [i for i in items if i.get("category") in CATS or i["id"] in EXTRA_IDS]
    out = {"weapons": [], "slayer": [], "dungeons": []}
    rec = {"weapons": [], "slayer": [], "dungeons": []}
    renames, skipped, excluded = [], [], collections.Counter()
    dropped_stats = collections.Counter()
    unused_spec = set(SPEC)

    for it in sorted(picked, key=lambda x: x["id"]):
        iid = it["id"]
        own = owner_of(it)
        if own is None:
            excluded[iid] += 1
            continue
        sp = SPEC.get(iid, {"lore": [], "abil": [], "w": {}, "tags": []})
        unused_spec.discard(iid)
        notes = {}
        ref_name = strip(it["name"])
        name = sp.get("name", ref_name)
        if name != ref_name:
            renames.append((iid, ref_name, name))
        d = {"id": iid, "name": name, "material": sp.get("material") or material(it),
             "rarity": it.get("tier", "COMMON") if it.get("tier") not in (None, "UNOBTAINABLE", "SUPREME") else "COMMON",
             "category": sp.get("category") or it.get("category", "MATERIAL")}
        if d["category"] == "ARROW_POISON":
            d["category"] = "MATERIAL"
        if iid in ("SOUL_WHIP", "FLAMING_FLAY"):
            d["category"] = "FISHING_WEAPON"
        # ---- stats
        src = dict(it.get("stats", {}))
        for sk, vals in (it.get("tiered_stats") or {}).items():
            src.setdefault(sk, vals[0])   # TUNE: dungeon quality rolls -> lowest roll until dungeons owns these
        stats, ability_damage = {}, None
        for sk, v in src.items():
            k = sk.upper()
            if k == "DAMAGE":
                d["damage"] = v
                continue
            if k == "WEAPON_ABILITY_DAMAGE":
                ability_damage = v
                continue
            k = STAT_ALIAS.get(k, k)
            if k in known:
                stats[k] = stats.get(k, 0) + v
            else:
                dropped_stats[k] += 1
        if stats:
            d["stats"] = stats
        # ---- requirements
        reqs = requirement_keys(it, notes)
        if reqs:
            d["requirements"] = reqs
        if it.get("npc_sell_price"):
            d["npcSell"] = it["npc_sell_price"]
        gems = [g["slot_type"] for g in it.get("gemstone_slots", []) or []]
        if gems:
            d["gemSlots"] = gems
        if it.get("skin", {}).get("value"):
            d["skull"] = it["skin"]["value"]
        # ---- tags
        cat = d["category"]
        tags = set(sp.get("tags", []))
        if cat in ("SWORD", "LONGSWORD", "BOW", "WAND", "FISHING_WEAPON"):
            tags.update({"enchantable", "recombable", "reforgeable"})
        if cat == "ARROW":
            tags.add("arrow")
        if iid.endswith("_ARROW_POISON"):
            tags.add("arrow_poison")
        if it.get("soulbound"):
            tags.add("soulbound")
        if it.get("museum_data"):
            tags.add("museum")
        if iid in VANILLA_IDS:
            tags.add("vanilla")
        w = dict(sp.get("w", {}))
        # wave 5: dungeon floor rolls. The def carries the lowest roll; an item stamped with the reference key
        # ItemData.extra.item_tier (the floor it dropped on) adds tiered[item_tier] - tiered[0] (Passives.itemStats).
        tiered = {}
        for sk, vals in (it.get("tiered_stats") or {}).items():
            if sk.upper() == "WEAPON_ABILITY_DAMAGE" or len(vals) < 2 or len(set(vals)) < 2:
                continue
            k = sk.upper()
            k = k if k == "DAMAGE" else STAT_ALIAS.get(k, k)
            tiered[k] = vals
        if tiered:
            w["tiered"] = tiered
        if w.get("shortbow"):
            tags.add("shortbow")
        d["tags"] = sorted(tags)
        if it.get("dungeon_item"):
            d["dungeon"] = True
        if it.get("glowing"):
            d["glow"] = True
        # ---- text
        lore = vs_lines(w) + list(sp.get("lore", []))
        if lore:
            d["lore"] = lore
        abilities = []
        scaling = it.get("ability_damage_scaling")
        for a in sp.get("abil", []):
            a = dict(a)
            a["id"] = "weapons_" + a["id"]   # module-prefixed ability ids (CONTRACT §3); handlers live in Abilities.java
            if "damage" not in a and ability_damage is not None:
                a["damage"] = ability_damage
            if scaling is not None:
                a["scaling"] = scaling
            elif "scaling" not in a:
                a["scaling"] = 0.3   # research 06 §4.1: every ability not listed scales 0.3
            abilities.append(a)
        if abilities:
            d["abilities"] = abilities
        if (sp.get("extra") or {}).get("maxStack"):
            d["maxStack"] = sp["extra"]["maxStack"]
        extra = {"src": "weapons", "refName": ref_name, "owner": own}
        extra.update({k: v for k, v in (sp.get("extra") or {}).items() if k != "maxStack"})
        extra.update(notes)
        if w:
            extra["w"] = w
        if it.get("ability_damage_scaling") is not None:
            extra["abilityScaling"] = it["ability_damage_scaling"]
        d["extra"] = extra
        out[own].append(d)

        # ---- recipes
        p = os.path.join(DATA, "neu-repo", "items", iid + ".json")
        if not os.path.exists(p) or iid in VANILLA_IDS:
            continue   # vanilla swords/bow/arrow: the crafting module's vanilla recipes (with plank/log groups) cover them
        n = json.load(open(p, encoding="utf-8"))
        rs = []
        if isinstance(n.get("recipe"), dict):
            rs.append(dict(n["recipe"], type="crafting"))
        rs += [r for r in (n.get("recipes") or []) if isinstance(r, dict)]
        unlock = unlock_by_name.get(ref_name)
        ct = strip(n.get("crafttext", ""))
        m = re.match(r"Requires: (.+) ([IVX]+)$", ct)
        if not unlock and m and m.group(1).lower() in col_by_name:
            unlock = "collection:%s:%d" % (col_by_name[m.group(1).lower()], ROMAN[m.group(2)])
        m = re.match(r"Requires: (\w+) Slayer (\d+)$", ct)
        if not unlock and m:
            unlock = "slayer:%s:%s" % (m.group(1).lower(), m.group(2))
        sr = n.get("slayer_req")
        if not unlock and sr:
            mm = re.match(r"([A-Z]+)_(\d+)$", sr)
            if mm and mm.group(1) in SLAYER_CODE:
                unlock = "slayer:%s:%s" % (SLAYER_CODE[mm.group(1)], mm.group(2))
        for idx, r in enumerate(rs):
            if r.get("type", "crafting") != "crafting":
                continue   # npc_shop / trade: the shops module owns merchants
            grid = [norm_cell(r.get(c + str(k), "")) for c in "ABC" for k in (1, 2, 3)]
            if not any(grid):
                continue
            out_id = r.get("overrideOutputId") or iid
            if out_id != iid:
                continue
            bad = [c.rsplit(":", 1)[0] for c in grid if c and c.rsplit(":", 1)[0] not in by_id]
            if bad:
                skipped.append((iid, bad))
                continue
            rr = {"id": "weapons:%s#%d" % (iid, idx), "type": "CRAFTING", "result": iid,
                  "amount": int(r.get("count", 1) or 1), "grid": grid, "station": "Crafting Table"}
            if unlock:
                rr["unlock"] = unlock
            rec[own].append(rr)

    # ---- meta for the code
    meta = {
        "woodCollections": ["LOG", "LOG:1", "LOG:2", "LOG:3", "LOG_2", "LOG_2:1", "MANGROVE_LOG", "FIG_LOG"],
        "combatCollections": list(cols["COMBAT"]["items"].keys()),
        "miningIslands": ["gold_mine", "deep_caverns", "dwarven_mines", "crystal_hollows", "glacite_tunnels"],
    }

    os.makedirs(OUT, exist_ok=True)

    def dump(name, obj):
        json.dump(obj, open(os.path.join(OUT, name), "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    dump("items.json", out["weapons"])
    dump("items_fallback.json", out["slayer"] + out["dungeons"])
    dump("recipes.json", rec["weapons"])
    dump("recipes_fallback.json", rec["slayer"] + rec["dungeons"])
    dump("meta.json", meta)

    with open(NAMES, "w", encoding="utf-8") as f:
        f.write("# weapons — renames\n\n")
        f.write("Generated by `tools/gen/weapons/gen_weapons.py` from `tools/gen/weapons/spec.py`.\n")
        f.write("Every description and ability text is rewritten in our own words; the numbers are the reference game's.\n")
        f.write("Rows marked (fallback) are slayer / dungeon weapons this module only registers when their owner has not.\n\n")
        f.write("| id | reference name | our name |\n|---|---|---|\n")
        fb = {d["id"] for d in out["slayer"] + out["dungeons"]}
        for a, b, c in renames:
            f.write("| %s%s | %s | %s |\n" % (a, " (fallback)" if a in fb else "", b, c))
        for a, b, c in EXTRA_RENAMES:
            f.write("| %s | %s | %s |\n" % (a, b, c))

    print("weapons %d, fallback slayer %d, fallback dungeons %d; recipes %d (+%d fallback); renames %d" % (
        len(out["weapons"]), len(out["slayer"]), len(out["dungeons"]), len(rec["weapons"]),
        len(rec["slayer"]) + len(rec["dungeons"]), len(renames)))
    print("excluded:", sorted(excluded))
    print("dropped stat keys:", dict(dropped_stats))
    print("skipped recipes:", skipped)
    print("spec entries matching no item:", sorted(unused_spec))


if __name__ == "__main__":
    main()
