"""Generate data/crimson/items.json + recipes.json from the research data.

    python tools/gen/crimson/gen_items.py

Sources:
  research/data/items.json (Hypixel API): stats, colours, skins, requirements, prestige costs, gem slots
  research/data/neu-repo/items/*.json:    the per-tier set-bonus numbers (lore), Kuudra key recipes
  research/data/neu-repo/constants/essencecosts.json: the COIN part of each essence star (audit A1: stars 4+ cost
                                          coins too, 10k..1M, Infernal 11-15 up to 50M; the Hypixel API omits it)
  research/data/skyhanni-repo/constants/Items.json "crimson_prestige_costs": the COIN part of each prestige
                                          (audit K14: to Hot 2M / Burning 5M / Fiery 10M / Infernal 20M per piece;
                                          its essence + teeth match the API, which confirms the API's tiers - the
                                          audit's "Hot->Burning 4,500 + 50" was the research reading one row low)
Owned here (CONTRACT item table: "crimson | Crimson Isle drops, crimson armors, faction items"):
  - the five Kuudra armor lines x five prestige tiers x four pieces (100 items)
  - the three Kuudra-gated weapons (Aurora Staff, Hollow Wand, Tormentor) that weapons excluded
  - the five Kuudra key tiers (sold by the faction envoys, consumed by the wave-2 kuudra module)
Every description is rewritten in our own words; the numbers are the reference's.
"""
import json
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
RES = os.path.join(ROOT, "research", "data")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "crimson")

STAT_MAP = {
    "HEALTH": "HEALTH", "DEFENSE": "DEFENSE", "STRENGTH": "STRENGTH", "INTELLIGENCE": "INTELLIGENCE",
    "CRITICAL_DAMAGE": "CRIT_DAMAGE", "CRITICAL_CHANCE": "CRIT_CHANCE", "WALK_SPEED": "SPEED",
    "TRUE_DEFENSE": "TRUE_DEFENSE", "MENDING": "MENDING", "ABILITY_DAMAGE_PERCENT": "ABILITY_DAMAGE",
}

TIERS = ["", "HOT_", "BURNING_", "FIERY_", "INFERNAL_"]
TIER_NAMES = ["Basic", "Hot", "Burning", "Fiery", "Infernal"]
PIECES = ["HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"]
SETS = ["CRIMSON", "AURORA", "TERROR", "FERVOR", "HOLLOW"]

# Per-tier set-bonus numbers, read off the reference lore (NEU) for each prestige.
DOMINION = [  # swing range per stack, ferocity per stack, +damage% per stack, cleave multiplier
    (0.05, 0, 0, 0.5), (0.1, 0, 0, 0.62), (0.1, 1, 0, 0.75), (0.1, 2, 0, 0.88), (0.1, 2, 10, 1.0)]
ARCANE_MISSILES = [3, 4, 4, 5, 5]
HYDRA = [  # damage% per stack, arrow speed% per stack, extra-arrow damage fraction
    (2, 1, 0.20), (2.8, 1.5, 0.30), (3.5, 2, 0.40), (4.2, 2.5, 0.50), (5, 3, 0.60)]
FERVOR_POUND = [(1, 6), (1, 7), (2, 8), (2, 9), (2, 10)]  # pounds, radius
SPIRIT_MAX = [10, 15, 20, 30, 40]

SET_FLAVOUR = {
    "CRIMSON": "Forged for the frontline brawlers of the isle.",
    "AURORA": "Woven with ley-light by the isle's spellcasters.",
    "TERROR": "Feathered plate for archers who never stop shooting.",
    "FERVOR": "Heavy plate that dares every beast to come closer.",
    "HOLLOW": "Light robes that drink in the courage of allies.",
}


def bonus(set_id, t):
    """(name, lore lines) for one set at prestige tier t, in our own words."""
    if set_id == "CRIMSON":
        sr, fer, dmg, cleave = DOMINION[t]
        per = f"&e+{sr:g} Swing Range"
        if fer:
            per += f"&7, &c+{fer} Ferocity"
        if dmg:
            per += f"&7 and &c+{dmg}% Damage"
        return "Dominion", [
            "&7Melee hits build &6ᝐ Dominion &7(one stack every",
            "&a1.5s&7, up to &c10&7). A stack fades after &a4s",
            "&7without a hit.",
            "",
            "&7Each stack: " + per + "&7.",
            "",
            f"&7At &c10 &7stacks your strikes cleave every mob",
            f"&7ahead of you for &c{cleave:g}x &7of the hit.",
            "&8Scales with pieces worn (x/4).",
        ]
    if set_id == "AURORA":
        n = ARCANE_MISSILES[t]
        return "Arcane Energy", [
            "&7Dealing ability damage builds &bѪ Arcane Energy",
            "&7(one stack per &a1s&7, up to &c10&7). A stack fades",
            "&7after &a4s &7without casting.",
            "",
            "&6Homing Missiles &e&lSNEAK",
            f"&7At &c10 &7stacks, sneak to spend them and loose",
            f"&c{n} &7seeking bolts for &c5,000 &7damage each.",
            "&8Scales with pieces worn (x/4).",
        ]
    if set_id == "TERROR":
        d, s, extra = HYDRA[t]
        return "Hydra Strike", [
            "&7Arrow hits build &5⁑ Hydra Strike &7(one stack per",
            "&a0.2s&7, up to &c10&7). A stack fades after &a4s&7.",
            "",
            f"&7Each stack: &c+{d:g}% Damage &7and &f+{s:g}% Arrow Speed&7.",
            "",
            f"&7At &c10 &7stacks every arrow is shadowed by &c2",
            f"&7phantom shots dealing &c{int(extra * 100)}% &7of its damage.",
            "&8Scales with pieces worn (x/4).",
        ]
    if set_id == "FERVOR":
        pounds, radius = FERVOR_POUND[t]
        times = "a Ground Pound" if pounds == 1 else f"{pounds} Ground Pounds"
        return "Fervor", [
            "&7Hitting a mob builds &a҉ Fervor &7(one stack every",
            "&a1.5s&7, up to &c10&7). A stack fades after &a4s&7.",
            "",
            "&6Ground Pound &e&lSNEAK",
            f"&7At &c10 &7stacks, sneak to slam out {times}",
            f"&7hitting every mob within &a{radius} &7blocks. Damage",
            "&7grows with your effective health.",
            "",
            "&6Intimidate &7(full set): hits have a &a50% &7chance",
            "&7to drag the attention of &c3 &7nearby mobs onto you.",
        ]
    if set_id == "HOLLOW":
        mx = SPIRIT_MAX[t]
        return "Spirit", [
            "&7Every &a3s&7, attacks from up to &a5 &7nearby players",
            f"&7feed you &3⚶ Spirit &7(max &c{mx}&7). A stack fades",
            "&7after &a4s &7without one.",
            "",
            "&7A &6Hollow Wand &7spends Spirit to cast its spells.",
            "&8Scales with pieces worn (x/4).",
        ]
    raise ValueError(set_id)


def color_hex(c):
    if not c:
        return None
    r, g, b = (int(x) for x in c.split(","))
    return "#%02X%02X%02X" % (r, g, b)


def stats_of(api):
    out = {}
    for k, v in (api.get("stats") or {}).items():
        k2 = STAT_MAP.get(k.upper())
        if k2:
            out[k2] = v
    return out


def cost_cells(costs):
    cells = []
    for c in costs:
        if c["type"] == "ESSENCE":
            cells.append(f"ESSENCE_{c['essence_type']}:{c['amount']}")
        elif c["type"] == "ITEM":
            cells.append(f"{c['item_id']}:{c['amount']}")
        elif c["type"] == "COINS":
            cells.append(f"COINS:{c['coins']}")
    return cells


def neu_star_coins():
    """item id -> {star number: coins} from NEU essencecosts (SKYBLOCK_COIN lines)."""
    with open(os.path.join(RES, "neu-repo", "constants", "essencecosts.json"), encoding="utf-8") as f:
        ec = json.load(f)
    out = {}
    for iid, e in ec.items():
        if not isinstance(e, dict):
            continue
        for star, cells in (e.get("items") or {}).items():
            for c in cells:
                if c.startswith("SKYBLOCK_COIN:"):
                    out.setdefault(iid, {})[int(star)] = int(c.split(":")[1])
    return out


def prestige_coins():
    """target tier prefix ("HOT_".."INFERNAL_") -> coins, from SkyHanni's crimson_prestige_costs."""
    with open(os.path.join(RES, "skyhanni-repo", "constants", "Items.json"), encoding="utf-8") as f:
        pc = json.load(f)["crimson_prestige_costs"]
    return {k + "_": int(v.get("SKYBLOCK_COIN", 0)) for k, v in pc.items()}


def neu(name):
    p = os.path.join(RES, "neu-repo", "items", name + ".json")
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def skull_value(api):
    sk = api.get("skin")
    if isinstance(sk, dict):
        return sk.get("value")
    return None


def main():
    with open(os.path.join(RES, "items.json"), encoding="utf-8") as f:
        api = {i["id"]: i for i in json.load(f)["items"]}

    items, recipes = [], []
    star_coins = neu_star_coins()
    pres_coins = prestige_coins()

    # ---- 1. the Kuudra armor lines -------------------------------------------------------
    for s in SETS:
        for t, prefix in enumerate(TIERS):
            bname, blore = bonus(s, t)
            for piece in PIECES:
                iid = f"{prefix}{s}_{piece}"
                a = api[iid]
                d = {
                    "id": iid,
                    "name": a["name"],
                    "material": "PLAYER_HEAD" if a["material"] == "SKULL_ITEM" else a["material"],
                    "rarity": a.get("tier", "LEGENDARY"),
                    "category": a.get("category", piece),
                    "stats": stats_of(a),
                    "lore": [f"&8{TIER_NAMES[t]} prestige", "&7" + SET_FLAVOUR[s]],
                    "setId": "crimson_" + s.lower(),
                    "setBonusName": bname,
                    "setBonusLore": blore,
                    "requirements": [f"skill:combat:{22 + 5 * t}"],
                    "gemSlots": [g["slot_type"] for g in a.get("gemstone_slots", []) or []],
                    "tags": ["reforgeable", "enchantable", "recombable", "museum", "crimson_kuudra_armor"],
                    "extra": {"kuudraSet": s.lower(), "kuudraTier": t, "piece": piece.lower()},
                }
                if d["material"] == "PLAYER_HEAD":
                    d["skull"] = skull_value(a)
                col = color_hex(a.get("color"))
                if col:
                    d["color"] = col
                if a.get("npc_sell_price"):
                    d["npcSell"] = a["npc_sell_price"]
                pr = a.get("prestige")
                if pr:
                    d["extra"]["prestigeTo"] = pr["item_id"]
                    d["extra"]["prestigeCost"] = cost_cells(pr["costs"])
                    to_prefix = TIERS[t + 1]
                    if pres_coins.get(to_prefix):
                        d["extra"]["prestigeCoins"] = pres_coins[to_prefix]
                if a.get("upgrade_costs"):
                    sc = star_coins.get(iid, {})
                    d["extra"]["starCosts"] = [cost_cells(c) + ([f"COINS:{sc[i + 1]}"] if sc.get(i + 1) else [])
                                               for i, c in enumerate(a["upgrade_costs"])]
                items.append(d)

    # ---- 2. Kuudra-gated weapons -------------------------------------------------------------
    w = api["RUNIC_STAFF"]
    items.append({
        "id": "RUNIC_STAFF", "name": "Aurora Staff", "material": "BLAZE_ROD", "rarity": "LEGENDARY",
        "category": "WAND", "damage": 20, "stats": {"INTELLIGENCE": 250},
        "lore": ["&7A focus cut for &bAurora &7wearers: its bolt", "&7lands hardest up close."],
        "abilities": [{"id": "crimson_runic_zap", "name": "Runic Zap", "trigger": "RIGHT_CLICK", "mana": 10,
                       "cooldown": 1, "damage": 10000, "scaling": w.get("ability_damage_scaling", 0.2),
                       "lore": ["&7Throws a beam that strikes the first", "&7enemy in its way for &c{damage} &7damage.",
                                "&7Loses power beyond &a10 &7blocks."]}],
        "requirements": ["skill:combat:22"], "npcSell": w.get("npc_sell_price", 0),
        "gemSlots": ["SAPPHIRE", "SAPPHIRE"], "tags": ["reforgeable", "enchantable", "recombable", "museum"],
        "extra": {"kuudraTier": 0},
    })
    w = api["HOLLOW_WAND"]
    items.append({
        "id": "HOLLOW_WAND", "name": "Hollow Wand", "material": "FEATHER", "rarity": "LEGENDARY", "category": "WAND",
        "damage": 150, "stats": {"STRENGTH": 20, "INTELLIGENCE": 120}, "glow": True,
        "lore": ["&7Spends the &3⚶ Spirit &7a &6Hollow &7set gathers."],
        "abilities": [
            {"id": "crimson_hollow_spark", "name": "Spirit Spark", "trigger": "RIGHT_CLICK", "mana": 0, "cooldown": 1,
             "damage": 15000, "scaling": 0.2,
             "lore": ["&7Costs &310 ⚶&7: a spark that hits up to &a5", "&7nearby enemies for &c{damage} &7damage."]},
            {"id": "crimson_hollow_ichor", "name": "Ichor Pool", "trigger": "SNEAK_RIGHT_CLICK", "mana": 0,
             "cooldown": 5,
             "lore": ["&7Costs &330 ⚶&7: heals you and every player", "&7within &a8 &7blocks for &c20% &7of their Health."]},
        ],
        "requirements": ["skill:combat:22"], "npcSell": w.get("npc_sell_price", 0),
        "tags": ["reforgeable", "enchantable", "recombable", "museum"], "extra": {"kuudraTier": 0},
    })
    w = api["TORMENTOR"]
    items.append({
        "id": "TORMENTOR", "name": "Tormentor", "material": "IRON_SWORD", "rarity": "LEGENDARY", "category": "SWORD",
        "damage": 525, "stats": {"STRENGTH": 30, "CRIT_DAMAGE": 100},
        "lore": ["&7A cursed blade from the deepest", "&7leviathan hunts."],
        "abilities": [
            {"id": "crimson_tormentor_angered", "name": "Angered", "trigger": "PASSIVE",
             "lore": ["&7While held you take &c+50% &7damage."]},
            {"id": "crimson_chain_of_agony", "name": "Chain of Agony", "trigger": "RIGHT_CLICK", "mana": 200,
             "cooldown": 0.5,
             "lore": ["&7Toggle: melee hits arc to up to &a5", "&7nearby enemies."]},
        ],
        "requirements": ["skill:combat:42"], "npcSell": w.get("npc_sell_price", 0),
        "gemSlots": ["COMBAT", "COMBAT"], "tags": ["reforgeable", "enchantable", "recombable", "museum"],
        "extra": {"kuudraTier": 4},
    })

    # ---- 3. Leviathan (Kuudra) keys ----------------------------------------------------------
    keys = [("KUUDRA_TIER_KEY", "Leviathan Key", "RARE", "Basic"),
            ("KUUDRA_HOT_TIER_KEY", "Hot Leviathan Key", "EPIC", "Hot"),
            ("KUUDRA_BURNING_TIER_KEY", "Burning Leviathan Key", "EPIC", "Burning"),
            ("KUUDRA_FIERY_TIER_KEY", "Fiery Leviathan Key", "LEGENDARY", "Fiery"),
            ("KUUDRA_INFERNAL_TIER_KEY", "Infernal Leviathan Key", "LEGENDARY", "Infernal")]
    prev = None
    for kid, name, rar, tier in keys:
        n = neu(kid)
        m = re.search(r'Value:\\?"([A-Za-z0-9+/=]+)\\?"', n.get("nbttag", ""))
        items.append({
            "id": kid, "name": name, "material": "PLAYER_HEAD", "rarity": rar, "category": "MATERIAL",
            "skull": m.group(1) if m else None,
            "lore": [f"&7Opens the reward chest after a", f"&7{tier}-tier hunt of the &cMagma Leviathan&7."],
            "tags": ["soulbound", "no_ah"], "extra": {"leviathanTier": tier.lower()},
        })
        n_rar = rar
        if prev:
            recipes.append({"id": f"crimson_{kid.lower()}", "type": "CRAFTING", "result": kid, "amount": 1,
                            "grid": [prev, prev, "", prev, prev, "", "", "", ""], "station": "Crafting"})
        prev = kid

    # ---- 4. X / Y / Z (wave 5): what the accessories module's Synthesizers eat. Reference: EPIC paper
    # "One/Two/Three Dimensional", npc 20/30/50 (research items.json). Sources are ours (TUNE, gen_mobs.py):
    # X from the isle's regular mobs, Y from the mini-boss crowds, Z from Vanquishers and the Aeon-Old Blaze.
    for xid, flat, npc in (("X", "a single dimension", 20), ("Y", "two dimensions", 30), ("Z", "three dimensions", 50)):
        api_x = api.get(xid, {})
        items.append({
            "id": xid, "name": xid, "material": "PAPER", "rarity": "EPIC", "category": "MATERIAL",
            "npcSell": api_x.get("npc_sell_price", npc),
            "lore": [f"&7A shape that only knows {flat}.", "&7Synthesizers drink it in."],
            "tags": ["bazaar"], "glow": True,
        })

    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "items.json"), "w", encoding="utf-8") as f:
        json.dump(items, f, indent=1, ensure_ascii=False)
    with open(os.path.join(OUT, "recipes.json"), "w", encoding="utf-8") as f:
        json.dump(recipes, f, indent=1, ensure_ascii=False)
    print(f"items: {len(items)}  recipes: {len(recipes)}  -> {OUT}")


if __name__ == "__main__":
    main()
