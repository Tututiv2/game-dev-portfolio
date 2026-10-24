"""Generate data/hollows/mobs.json and spawns.json from the NEU mob drop pages.

    python tools/gen/hollows/gen_data.py

Levels, health, coins, XP and drop chances come from research/data/neu-repo/items/*_MONSTER/_BOSS.json
(the "drops" recipes). Where NEU has no number (damage, a missing health line) the value is marked
TUNE in the "extra.tune" field and listed in the hand-off. Display names are OURS (docs/names/hollows.md).
"""
import json, os, re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
NEU = os.path.join(ROOT, "research", "data", "neu-repo", "items")
ITEMS = os.path.join(ROOT, "research", "data", "items.json")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "hollows")
strip = lambda s: re.sub(r"§.", "", s or "")


def drops_page(file, which=None):
    d = json.load(open(os.path.join(NEU, file + ".json"), encoding="utf-8"))
    pages = [r for r in d.get("recipes", []) if r.get("type") == "drops"]
    if which is not None:
        pages = [p for p in pages if strip(p["name"]) == which] or pages
    page = pages[0]
    health = None
    for line in d.get("lore", []):
        m = re.search(r"Health: ([\d,]+)", strip(line))
        if m:
            health = int(m.group(1).replace(",", ""))
    return page, health


def parse_drops(page, skip=()):
    out = []
    for dr in page["drops"]:
        raw = dr["id"]
        if raw.startswith("ATTRIBUTE_SHARD") or raw.startswith("SKYBLOCK_POWDER") or raw.startswith("DYE_"):
            continue
        item, _, n = raw.rpartition(":") if raw.count(":") and raw.split(":")[-1].isdigit() else (raw, "", "1")
        if item in skip:
            continue
        chance = strip(dr.get("chance", "100%")).replace("per Hit", "").strip().rstrip("%")
        lo = hi = int(n or 1)
        for e in dr.get("extra", []):
            m = re.search(r"x(\d+)-(\d+)", strip(e))
            if m:
                lo, hi = int(m.group(1)), int(m.group(2))
        out.append({"item": item, "min": lo, "max": hi, "chance": round(float(chance) / 100.0, 7)})
    return out


def skin(item_id):
    for it in json.load(open(ITEMS, encoding="utf-8"))["items"]:
        if it["id"] == item_id and it.get("skin"):
            return it["skin"]["value"]
    return None


mobs = []


def mob(id, name, entity, file=None, page=None, level=None, health=None, damage=None, coins=None, xp=None,
        drops=None, extra_drops=(), flags=(), family=None, equipment=None, skull=None, speed=1.0, behaviours=(),
        defense=0, tune=None, skip=()):
    p, h = (drops_page(file, page) if file else (None, None))
    d = {
        "id": id, "name": name, "entity": entity,
        "level": level if level is not None else (p.get("level") if p else 1),
        "health": health if health is not None else (h or 1000),
        "damage": damage if damage is not None else 100,
        "defense": defense,
        "speed": speed,
        "combatXp": xp if xp is not None else (p.get("xp") or 0 if p else 0),
        "coins": coins if coins is not None else (p.get("coins") or 0 if p else 0),
        "drops": (drops if drops is not None else (parse_drops(p, skip) if p else [])) + list(extra_drops),
        "family": family or id,
        "flags": sorted(set(flags) | {"aggressive"} - ({"aggressive"} if "passive" in flags else set())),
        "behaviours": list(behaviours),
        "extra": {"tune": tune} if tune else {},
    }
    if equipment:
        d["equipment"] = equipment
    if skull:
        d["skull"] = skull
    mobs.append(d)


goblin_skull = skin("GOBLIN_HELMET")
EGGS = [{"item": "GOBLIN_EGG", "min": 1, "max": 1, "chance": 0.006}, {"item": "GOBLIN_EGG_YELLOW", "min": 1, "max": 1, "chance": 0.001},
        {"item": "GOBLIN_EGG_RED", "min": 1, "max": 1, "chance": 0.0004}, {"item": "GOBLIN_EGG_GREEN", "min": 1, "max": 1, "chance": 0.0025},
        {"item": "GOBLIN_EGG_BLUE", "min": 1, "max": 1, "chance": 0.0002}]
YOG_ARMOR = [{"item": f"ARMOR_OF_YOG_{p}", "min": 1, "max": 1, "chance": 0.0025} for p in ("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS")]

# ---------------- crystal caverns ----------------
mob("ch_automaton", "Automaton", "IRON_GOLEM", "AUTOMATON_MONSTER", "Automaton", damage=1000, speed=0.9, flags=("no_knockback",),
    tune="damage from research 03 (1,000)")
p = json.load(open(os.path.join(NEU, "AUTOMATON_MONSTER.json"), encoding="utf-8"))["recipes"]
elite = [r for r in p if r.get("type") == "drops"][1]
mob("ch_automaton_elite", "Automaton Sentinel", "IRON_GOLEM", level=150, health=30000, damage=1500, drops=parse_drops(elite),
    coins=elite.get("coins"), xp=elite.get("xp"), flags=("no_knockback",), family="ch_automaton", tune="health/damage (NEU lists only Lv150 drops)")
mob("ch_sludge", "Sludge", "SLIME", "SLUDGE_MONSTER", level=5, health=5000, damage=200, family="ch_sludge")
mob("ch_sludge_10", "Sludge", "SLIME", "SLUDGE_MONSTER", level=10, health=10000, damage=400, coins=2, xp=2, family="ch_sludge",
    extra_drops=[{"item": "SLUDGE_JUICE", "min": 1, "max": 1, "chance": 0.5}], tune="Lv10/Lv100 sizes from research 03 (5k/10k/25k)")
mob("ch_sludge_100", "Sludge", "SLIME", "SLUDGE_MONSTER", level=100, health=25000, damage=800, coins=5, xp=8, family="ch_sludge",
    extra_drops=[{"item": "SLUDGE_JUICE", "min": 1, "max": 2, "chance": 1.0}])
mob("ch_yog", "Cinderling", "MAGMA_CUBE", "YOG_MONSTER", damage=1000, flags=("fire_immune",), extra_drops=YOG_ARMOR,
    behaviours=("hollows_ignite",), tune="armor drop 0.25% each (research: 'rare')")
mob("ch_thyst", "Amethyst Leech", "ENDERMITE", "THYST_MONSTER", damage=300)
mob("ch_goblin", "Goblin", "ZOMBIE", "GOBLIN_MONSTER", "Goblin", health=1500, damage=250, flags=("baby",), skull=goblin_skull,
    equipment={"HAND": "STONE_SWORD"}, family="ch_goblin", tune="goblin health 1.5k-5k (research 03), damage")
mob("ch_goblin_knifethrower", "Knifethrower", "ZOMBIE", "GOBLIN_MONSTER", "Knifethrower", health=2000, damage=350, flags=("baby",),
    skull=goblin_skull, equipment={"HAND": "IRON_SWORD"}, family="ch_goblin")
mob("ch_goblin_firethrower", "Goblin Fireslinger", "ZOMBIE", "GOBLIN_MONSTER", "Fireslinger", health=2500, damage=300, flags=("baby",),
    skull=goblin_skull, equipment={"HAND": "BLAZE_ROD"}, family="ch_goblin", behaviours=("hollows_ignite",))
mob("ch_goblin_flamethrower", "Goblin Flamethrower", "ZOMBIE", "GOBLIN_MONSTER", "Goblin Flamethrower", health=25000, damage=900,
    skull=goblin_skull, equipment={"HAND": "BLAZE_ROD"}, family="ch_goblin", behaviours=("hollows_ignite",))
mob("ch_goblin_bomber", "Goblin Bomber", "ZOMBIE", "GOBLIN_MONSTER", "Creeperlobber", health=25000, damage=1100,
    skull=goblin_skull, equipment={"HAND": "TNT"}, family="ch_goblin")
mob("ch_goblin_cutthroat", "Goblin Cutthroat", "ZOMBIE", "GOBLIN_MONSTER", "Murderlover", health=50000, damage=1800, speed=1.2,
    skull=goblin_skull, equipment={"HAND": "GOLDEN_SWORD"}, family="ch_goblin")
# Goblin Queen's Den (research 03: "Goblin Queen | Queen's Den | (?) | (?) | Amber Crystal"). NEU has no page for
# her, so every number is ours. Her behaviour hands each participant the Amber crystal + a Goblin Egg; this
# table rolls for the killer on top. Brood Guards are den goblins whose eggs drop ~10x the Holdout rate.
mob("ch_goblin_queen", "Goblin Queen", "ZOMBIE", level=200, health=250000, damage=1400, coins=400, xp=600, speed=0.9,
    drops=[{"item": "GOBLIN_EGG", "min": 1, "max": 3, "chance": 1.0},
           {"item": "GOBLIN_EGG_GREEN", "min": 1, "max": 1, "chance": 0.25}, {"item": "GOBLIN_EGG_YELLOW", "min": 1, "max": 1, "chance": 0.12},
           {"item": "GOBLIN_EGG_RED", "min": 1, "max": 1, "chance": 0.06}, {"item": "GOBLIN_EGG_BLUE", "min": 1, "max": 1, "chance": 0.03},
           {"item": "GOBLIN_HELMET", "min": 1, "max": 1, "chance": 0.05}, {"item": "GOBLIN_CHESTPLATE", "min": 1, "max": 1, "chance": 0.05},
           {"item": "GOBLIN_LEGGINGS", "min": 1, "max": 1, "chance": 0.05}, {"item": "GOBLIN_BOOTS", "min": 1, "max": 1, "chance": 0.05},
           {"item": "ROUGH_AMBER_GEM", "min": 40, "max": 80, "chance": 1.0}],
    flags=("boss", "no_knockback"), skull=goblin_skull, equipment={"CHEST": "GOLDEN_CHESTPLATE", "HAND": "GOLDEN_SWORD"},
    family="ch_goblin", behaviours=("hollows_goblin_queen",), tune="all numbers (NEU/research have none): Lv200, 250k HP, 1,400 dmg, drops")
mob("ch_goblin_brood_guard", "Brood Guard", "ZOMBIE", level=50, health=6000, damage=450, coins=15, xp=12, flags=("baby",),
    drops=[{"item": "GOBLIN_EGG", "min": 1, "max": 1, "chance": 0.06}, {"item": "GOBLIN_EGG_GREEN", "min": 1, "max": 1, "chance": 0.015},
           {"item": "GOBLIN_EGG_YELLOW", "min": 1, "max": 1, "chance": 0.006}, {"item": "ROUGH_AMBER_GEM", "min": 2, "max": 6, "chance": 1.0}],
    skull=goblin_skull, equipment={"HAND": "IRON_SWORD", "CHEST": "LEATHER_CHESTPLATE"}, family="ch_goblin",
    tune="den goblin: Lv50 / 6k HP / 6% egg (10x the Holdout goblins) are ours")
for exe, name in (("SEBASTIAN", "Executive Sable"), ("VIPER", "Executive Asp"), ("WENDY", "Executive Wynn")):
    mob("ch_treasurite_" + exe.lower(), name, "ZOMBIE", f"EXECUTIVE_{exe}_MONSTER", damage=1000,
        equipment={"HEAD": "LEATHER_HELMET", "CHEST": "LEATHER_CHESTPLATE", "HAND": "IRON_SWORD"}, family="ch_treasurite")
mob("ch_treasurite_grunt", "Gang Grunt", "ZOMBIE", "GRUNT_MONSTER", damage=600, family="ch_treasurite",
    equipment={"HEAD": "LEATHER_HELMET", "HAND": "STONE_AXE"}, tune="damage; health is NEU's 153,000 (wave 5: was research 03's guessed 10k)")
mob("ch_boss_corleone", "Gang Boss Vittore", "ZOMBIE", "BOSS_CORLEONE_BOSS", damage=2000, flags=("boss", "no_knockback"),
    equipment={"HEAD": "GOLDEN_HELMET", "CHEST": "GOLDEN_CHESTPLATE", "HAND": "GOLDEN_SWORD"}, family="ch_treasurite_boss")
mob("ch_key_guardian", "Key Guardian", "WITHER_SKELETON", "KEY_GUARDIAN_MONSTER", flags=("no_knockback",),
    equipment={"HAND": "STONE_SWORD"})
mob("ch_temple_guardian", "Tidecarver Door Guardian", "ZOMBIE", level=100, health=50000, damage=800, coins=50, xp=60,
    drops=[{"item": "ROUGH_AMETHYST_GEM", "min": 20, "max": 40, "chance": 1.0}], flags=("no_knockback",),
    equipment={"HEAD": "KALHUIKI_MASK", "HAND": "TRIDENT"}, tune="NEU has no page for the door guardian")
mob("ch_magmaw", "Magmaw", "MAGMA_CUBE", "BAL_BOSS", damage=1500, flags=("boss", "no_knockback", "fire_immune"), drops=[],
    behaviours=("hollows_magmaw",), tune="damage; per-hit drops are rolled by the behaviour from data/hollows/magmaw.json")
mob("ch_magmaw_blaze", "Emberdeep Blaze", "BLAZE", level=60, health=8000, damage=700, coins=5, xp=20, flags=("fire_immune",),
    drops=[{"item": "ROUGH_TOPAZ_GEM", "min": 2, "max": 4, "chance": 1.0}], tune="summon of Magmaw: all numbers")
mob("ch_butterfly", "Butterfly", "ALLAY", "BUTTERFLY_MONSTER", damage=0, flags=("passive", "flying"))
# wave 5: NEU BAL_BOSS lore - "At various points in the fight Bal will summon Fire Bats and Blazes"; no page for the bats
mob("ch_magmaw_bat", "Ember Bat", "BAT", level=60, health=3000, damage=400, coins=3, xp=10, flags=("fire_immune", "flying"),
    drops=[{"item": "ROUGH_TOPAZ_GEM", "min": 1, "max": 2, "chance": 1.0}], behaviours=("hollows_ember_bat",),
    tune="summon of Magmaw: all numbers; damage is dealt by the behaviour as a burning touch")
# wave 5: the Kalhuiki tribe (research 03 lists 'Kalhuiki tribe | Jungle Temple'; KALHUIKI_MASK: 'members of the Kalhuiki
# Tribe will not attack you'). No NEU page, so every number is ours; the mask hook lives in Kalhuiki.java
mob("ch_kalhuiki_tribesman", "Tidecarver Tribesman", "ZOMBIE", level=75, health=12000, damage=550, coins=6, xp=14,
    drops=[{"item": "SLUDGE_JUICE", "min": 1, "max": 2, "chance": 0.25}, {"item": "ROUGH_AMETHYST_GEM", "min": 2, "max": 5, "chance": 1.0}],
    equipment={"HEAD": "KALHUIKI_MASK", "CHEST": "LEATHER_CHESTPLATE", "HAND": "BAMBOO"}, family="ch_kalhuiki",
    tune="all numbers (no NEU page): Lv75 / 12k HP / 550 dmg / drops")

# ---------------- frozen tunnels ----------------
WALKER = drops_page("ICE_WALKER_MONSTER")[0]
mob("gt_glacite_walker", "Rimestone Walker", "ZOMBIE", level=60, health=40000, damage=1500, coins=8, xp=40,
    drops=parse_drops(WALKER) + [{"item": "GLACITE", "min": 1, "max": 3, "chance": 1.0}],
    equipment={"HEAD": "PACKED_ICE", "HAND": "IRON_PICKAXE"}, family="gt_glacite_walker", tune="Lv60 / 40,000 HP from research 03; damage")
for f, name, ent, eq in (("GLACITE_BOWMAN_MONSTER", "Rimestone Bowman", "STRAY", {"HAND": "BOW"}),
                         ("GLACITE_MAGE_MONSTER", "Rimestone Mage", "ZOMBIE", {"HEAD": "BLUE_ICE", "HAND": "BLAZE_ROD"}),
                         ("GLACITE_CAVER_MONSTER", "Rimestone Caver", "ZOMBIE", {"HEAD": "PACKED_ICE", "HAND": "IRON_PICKAXE"}),
                         ("GLACITE_MUTT_MONSTER", "Rimestone Mutt", "WOLF", None)):
    page, h = drops_page(f)
    lore = json.load(open(os.path.join(NEU, f + ".json"), encoding="utf-8"))["lore"]
    dmg = defn = None
    for line in lore:
        m = re.search(r"Damage: ([\d,]+)", strip(line))
        if m: dmg = int(m.group(1).replace(",", ""))
        m = re.search(r"Defense: ([\d,]+)", strip(line))
        if m: defn = int(m.group(1).replace(",", ""))
    mid = "gt_glacite_" + name.split()[1].lower()
    mob(mid, name, ent, f, damage=dmg, defense=defn or 0, equipment=eq, family="gt_glacite_guardian",
        extra_drops=[], skip=())

# ---------------- spawn rules ----------------
def rule(id, island, mob_id, max_, resp, area=None, anchors=None, cond=None):
    r = {"id": id, "island": island, "mob": mob_id, "max": max_, "respawnSeconds": resp}
    if area: r["area"] = area
    if anchors: r["anchors"] = anchors
    if cond: r["condition"] = cond
    return r

CH, GT = "crystal_hollows", "glacite_tunnels"
spawns = [
    rule("ch_jungle_sludge", CH, "ch_sludge", 8, 10, "mobs.jungle"),
    rule("ch_jungle_sludge_10", CH, "ch_sludge_10", 4, 15, "mobs.jungle"),
    rule("ch_jungle_sludge_100", CH, "ch_sludge_100", 2, 40, "mobs.jungle"),
    rule("ch_jungle_thyst", CH, "ch_thyst", 2, 30, "mobs.jungle"),
    rule("ch_temple_key_guardian", CH, "ch_key_guardian", 1, 240, "mobs.temple_court"),
    rule("ch_city_automaton", CH, "ch_automaton", 10, 10, "mobs.relic_city"),
    rule("ch_city_automaton_elite", CH, "ch_automaton_elite", 2, 60, "mobs.relic_city"),
    rule("ch_goblin_basic", CH, "ch_goblin", 6, 8, "mobs.goblin_holdout"),
    rule("ch_goblin_knife", CH, "ch_goblin_knifethrower", 3, 12, "mobs.goblin_holdout"),
    rule("ch_goblin_fire", CH, "ch_goblin_firethrower", 3, 12, "mobs.goblin_holdout"),
    rule("ch_goblin_flame", CH, "ch_goblin_flamethrower", 2, 30, "mobs.goblin_holdout"),
    rule("ch_goblin_bomb", CH, "ch_goblin_bomber", 2, 30, "mobs.goblin_holdout"),
    rule("ch_goblin_cutthroat", CH, "ch_goblin_cutthroat", 1, 60, "mobs.goblin_holdout"),
    rule("ch_goblin_guard", CH, "ch_goblin_knifethrower", 2, 20, "mobs.goblin_guard"),
    rule("ch_queen_brood", CH, "ch_goblin_brood_guard", 4, 20, "mobs.goblin_queen_den"),
    rule("ch_gang_grunt", CH, "ch_treasurite_grunt", 4, 15, "mobs.gang"),
    rule("ch_gang_sebastian", CH, "ch_treasurite_sebastian", 1, 45, "mobs.gang"),
    rule("ch_gang_viper", CH, "ch_treasurite_viper", 1, 45, "mobs.gang"),
    rule("ch_gang_wendy", CH, "ch_treasurite_wendy", 1, 45, "mobs.gang"),
    rule("ch_magma_yog_hall", CH, "ch_yog", 4, 20, "mobs.magma.emberdeep"),
    rule("ch_magma_yog_east", CH, "ch_yog", 6, 20, "mobs.magma.east"),
    rule("ch_magma_yog_west", CH, "ch_yog", 6, 20, "mobs.magma.west"),
    rule("ch_grotto_1", CH, "ch_butterfly", 3, 30, "mobs.grotto.1"),
    rule("ch_grotto_2", CH, "ch_butterfly", 3, 30, "mobs.grotto.2"),
    rule("ch_grotto_3", CH, "ch_butterfly", 3, 30, "mobs.grotto.3"),
    rule("gt_walkers_west", GT, "gt_glacite_walker", 6, 12, "mobs.tunnels.west"),
    rule("gt_walkers_east", GT, "gt_glacite_walker", 6, 12, "mobs.tunnels.east"),
    rule("gt_walkers_mid", GT, "gt_glacite_walker", 4, 12, "mobs.tunnels.mid"),
    rule("gt_walkers_lake", GT, "gt_glacite_walker", 4, 15, "mobs.lake"),
    # wave 5: the four Rimestone guardians are MINESHAFT guardians in the reference (research 03 §11.3), and mining
    # spawns them over the corpses of every shaft. The Frozen Depths get walkers; HollowsModule adds the old
    # depth-guardian rules back only when dial hollows.depths-guardians is on.
    rule("gt_walkers_depths", GT, "gt_glacite_walker", 5, 15, "mobs.depths"),
    rule("ch_temple_tribe", CH, "ch_kalhuiki_tribesman", 3, 30, "mobs.temple_court"),
    rule("ch_jungle_tribe", CH, "ch_kalhuiki_tribesman", 3, 30, "mobs.jungle"),
]

# Magmaw's per-hit table (NEU BAL_BOSS: chance per hit)
bal, _ = drops_page("BAL_BOSS")
magmaw = {"perHit": parse_drops(bal), "crystal": "TOPAZ_CRYSTAL", "health": 200}

os.makedirs(OUT, exist_ok=True)
json.dump(mobs, open(os.path.join(OUT, "mobs.json"), "w", encoding="utf-8"), indent=1, ensure_ascii=False)
json.dump(spawns, open(os.path.join(OUT, "spawns.json"), "w", encoding="utf-8"), indent=1)
json.dump(magmaw, open(os.path.join(OUT, "magmaw.json"), "w", encoding="utf-8"), indent=1)
print(len(mobs), "mobs,", len(spawns), "spawn rules ->", OUT)
for m in mobs:
    print(f"  {m['id']:28s} Lv{m['level']:<4} {m['health']:>9,} hp  {len(m['drops'])} drops")
