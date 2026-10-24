"""Integrator round 1: ingredient ids other modules' recipes/trades reference that NO module registered.

Writes plugin/src/main/resources/data/materials/items_integration.json (family "integration"). Items only -
recipes stay with their owners. gen_materials.py calls main() at the end of every run (and must never delete
this file - it once did, and X/Y/Z & co. vanished); an id a family file already defines is skipped.

Like the rest of the materials catalogue these register in postEnable ONLY IF ABSENT, so the day a
specialist module (dungeons, mayors events, rift...) registers its own definition, it wins and this
fallback is skipped. Names: proper nouns renamed (see docs/names/materials.md); lore rewritten.

    py -3 tools/gen/materials/gen_materials.py      (runs this too)
    py -3 tools/gen/materials/gen_integration.py    (this file alone)
"""
import glob
import json
import os

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
REF = json.load(open(os.path.join(ROOT, "research", "data", "items.json"), encoding="utf8"))
REF = REF if isinstance(REF, list) else REF.get("items", [])
REF = {i["id"]: i for i in REF}
NEU = os.path.join(ROOT, "research", "data", "neu-repo", "items")

MAT = {"SKULL_ITEM": "PLAYER_HEAD", "INK_SACK": "BONE_MEAL", "PAPER": "PAPER", "STICK": "STICK"}

# id -> (our name, fallback material, rarity fallback, lore lines, owner who should take it over)
ITEMS = {
    "WOOD_SINGULARITY": ("Wood Singularity", None, "LEGENDARY",
                         ["&8Combinable in Anvil", "", "&7Fuse it onto an &9Axe &7for", "&6+25 Foraging Fortune&7."], "foraging"),
    "FRESHLY_MINTED_COINS": ("Freshly-Minted Coins", None, "RARE",
                             ["&7Still warm from the press. Bankers", "&7pay well for a pouch like this."], "mayors"),
    "AVARICIOUS_CHALICE": ("Avaricious Chalice", None, "EPIC",
                           ["&7A cup that is never quite full", "&7enough for its owner."], "mayors"),
    "BLOOD_STAINED_COINS": ("Blood-Stained Coins", None, "RARE",
                            ["&7Coins that changed hands at the", "&7end of a blade."], "mayors"),
    "BLOOD_SOAKED_COINS": ("Blood-Soaked Coins", None, "EPIC",
                           ["&7Nobody asks where these came from."], "mayors"),
    "CONDENSED_FERMENTO": ("Condensed Fermenta", "PAPER", "EPIC",
                           ["&7Nine Fermenta pressed into one", "&7sticky, potent block."], "garden"),
    "GLACIAL_FRAGMENT": ("Glacial Fragment", "PAPER", "EPIC",
                         ["&7A shard of ice that never melts,", "&7found during the winter season."], "mayors"),
    "STRETCHING_STICKS": ("Stretching Sticks", "STICK", "UNCOMMON",
                          ["&7Slender, springy sticks that bend", "&7without snapping. Prized by", "&7axe makers."], "foraging"),
    "X": ("X", "PAPER", "EPIC", ["&7One dimension."], "mayors"),
    "Y": ("Y", "PAPER", "EPIC", ["&7Two dimensions."], "mayors"),
    "Z": ("Z", "PAPER", "EPIC", ["&7Three dimensions."], "mayors"),
    "LOTUS_DIAMOND": ("Diamond Lotus", "PAPER", "LEGENDARY",
                      ["&8Collection Item", "", "&7The rarest bloom of the lotus", "&7waters."], "fishing"),
    "HILT_OF_REVELATIONS": ("Hilt of Omens", "STICK", "RARE",
                            ["&7Dug up during the mythic ritual.", "&7A blade could be fitted to it."], "mayors"),
    "NECRON_HANDLE": ("Warden's Handle", "STICK", "LEGENDARY",
                      ["&7Pried from the last warden of", "&7the catacombs."], "dungeons"),
    "ENCHANTED_BONE_MEAL": ("Enchanted Bone Meal", "BONE_MEAL", "COMMON",
                            ["&7Makes crops and saplings shoot", "&7up in an instant."], "farming"),
    "GEOMETRIC_ODDITY": ("Geometric Oddity", None, "RARE",
                         ["&8Reforge Stone", "&8Combinable in Anvil", "", "&7Gives goggles of the bayou the",
                          "&9Geometric &7reforge."], "reforges"),
    "LUSHLILAC": ("Velvet Lilac", "PAPER", "UNCOMMON",
                  ["&8Collection Item", "", "&7A soft purple bloom from the", "&7moonlit marsh."], "foraging"),
    "RECORD_13": ("Music Disc 13", "MUSIC_DISC_13", "COMMON", ["&7An eerie old record."], "garden"),
    "LUCKY_CLOVER": ("Lucky Clover", "OAK_SAPLING", "RARE",
                     ["&7A four-leaf clover. Some visitors", "&7will pay a fortune for one."], "garden"),
    # wave 5 (2026-10-06): reference ingredients/trade goods that other modules' recipes, shops, sacks and the
    # bazaar name and NOBODY registers (found by cross-referencing every data file against every definition):
    # starred Midas/Daedalus/Glacial gear, the Snowman workshop and Courier trades, the Plasmaflux orb, the
    # Droplet Wisp, Capsaicin Eyedrops, the Colossal Experience Bottle all had a dead ingredient.
    "GOLDEN_FRAGMENT": ("Golden Fragment", None, "EPIC",
                        ["&7Gold, broken into shards. Smiths", "&7use them to star golden gear."], "mayors"),
    "WINTER_FRAGMENT": ("Winter Fragment", None, "EPIC",
                        ["&7A sliver of festive frost. Smiths", "&7use them to star winter gear."], "mayors"),
    "VOLCANIC_ROCK": ("Volcanic Rock", None, "EPIC",
                      ["&7Still warm from the eruption that", "&7made it. A courier on the glacier",
                       "&7trades fragments for it."], "mayors"),
    "SHIELD_DUST": ("Shield Dust", None, "RARE",
                    ["&7Scraped off shielded monsters in a", "&7witching year. Brewers fold it into",
                     "&7tonics."], "mayors"),
    "PLASMA_NUCLEUS": ("Plasma Nucleus", "PURPLE_DYE", "LEGENDARY",
                       ["&7A sphere of contained plasma. Fuse", "&7it into an Overflux power orb to",
                        "&7make it burn brighter."], "slayer"),
    "OMEGA_EGG": ("Omega Enchanted Egg", "GHAST_SPAWN_EGG", "EPIC",
                  ["&7Nine super eggs in one shell.", "&7Used to craft the strangest of pets."], "farming"),
    "EXPORTABLE_CARROTS": ("Exportable Carrots", None, "UNCOMMON",
                           ["&7Rift carrots, crated for export.", "&7A carrot collector on the Crimson",
                            "&7Isle pays in strength for them."], "crimson"),
    "COLOSSAL_EXP_BOTTLE_UPGRADE": ("Colossal Experience Bottle Upgrade", None, "SPECIAL",
                                    ["&7Craft it with a Titanic Experience", "&7Bottle to double what it holds."],
                                    "bank"),
    # 2026-10-06 real-game audit follow-ups (integrator): ids other modules' loot names that nobody registers.
    # mining: the Dwarven O's cereals (Scatha drops, the Metallic Minis come from glacite corpses). Eating one is
    # +2 of its fortune for good, the first five times (each cereal counts on its own); wired in Consumables.java.
    "DWARVEN_OS_ORE_OATS": ("Delver's O's Ore Oats", None, "UNCOMMON",
                            ["&7Part of a balanced mining breakfast."], "mining"),
    "DWARVEN_OS_BLOCK_BRAN": ("Delver's O's Block Bran", None, "RARE",
                              ["&7Gritty. Possibly actual gravel."], "mining"),
    "DWARVEN_OS_GEMSTONE_GRAHAMS": ("Delver's O's Gemstone Grahams", None, "EPIC",
                                    ["&7They sparkle. Do not ask why."], "mining"),
    "DWARVEN_OS_METALLIC_MINIS": ("Delver's O's Metallic Minis", None, "LEGENDARY",
                                  ["&7Crunchy in a way teeth object to."], "mining"),
    # kuudra: optional T5 chest roll. Reading it should unlock a crimson fast-travel point at the Forgotten Skull;
    # that warp is crimson's to add (request in docs/handoff/materials.md), until then it is a trade good.
    "NETHER_FORTRESS_BOSS_TRAVEL_SCROLL": ("Dusty Travel Scroll to the Forgotten Skull", "MAP", "EPIC",
                                           ["&7An old map, brittle at the folds.", "", "&7Island: &cCrimson Isle",
                                            "&7Destination: &bForgotten Skull"], "crimson"),
    # dungeons: a Floor 6 chest reward nobody registered, so the chest roll was silently skipped. Its Raise
    # Souls ability runs on the weapons module's soul machinery (extra.src "weapons", w.souls 2; see EXTRA_FIELDS).
    "SUMMONING_RING": ("Summoning Ring", None, "RARE",
                       ["&8Dungeon item"], "dungeons"),
}

# Fields merged into a fallback's def beyond the common shape (abilities, category, tags).
CEREALS = {"DWARVEN_OS_ORE_OATS": "Ore Fortune", "DWARVEN_OS_BLOCK_BRAN": "Block Fortune",
           "DWARVEN_OS_GEMSTONE_GRAHAMS": "Gemstone Fortune", "DWARVEN_OS_METALLIC_MINIS": "Dwarven Metal Fortune"}
EXTRA_FIELDS = {
    iid: {"category": "MATERIAL", "tags": ["bazaar", "consumable"],
          "abilities": [{"id": "materials_" + iid.lower(), "name": "Breakfast", "trigger": "RIGHT_CLICK",
                         "lore": ["&7The first &a5 &7times you eat one,", "&7gain &6+2☘ " + stat + " &7for good."]}]}
    for iid, stat in CEREALS.items()
}
EXTRA_FIELDS["NETHER_FORTRESS_BOSS_TRAVEL_SCROLL"] = {"category": "TRAVEL_SCROLL", "extra_warp": "crimson_skull"}  # warp: crimson's zone id
# Raise Souls (NEU SUMMONING_RING lore): monsters killed while the ring is CARRIED drop their soul; right-click a
# soul with the ring to absorb it (max 2), right-click to raise them, sneak right-click to view/release; mana scales
# with the monsters' power; cooldown 1 s. The handler and soul drops are the weapons module's (Souls.java), which
# only acts on defs with extra.src = "weapons" - hence the two extra_ fields.
EXTRA_FIELDS["SUMMONING_RING"] = {
    "tags": ["dungeon_item"],
    "abilities": [{"id": "weapons_raise_souls", "name": "Raise Souls", "trigger": "RIGHT_CLICK", "mana": 0, "cooldown": 1,
                   "lore": ["&7Monsters you kill while you carry this", "&7ring leave their soul behind. Right-click",
                            "&7a soul with the ring to absorb it, then", "&7right-click to raise your souls to fight",
                            "&7for you.", "&8Mana depends on the monsters' power.", "&8Sneak right-click to release souls.",
                            "&8A raised soul that dies is lost.", "&8Max Souls: &a2"]}],
    "extra_src": "weapons",
    "extra_w": {"souls": 2},
}

# Unique in the reference (unstackable / has a uuid). Only the wave-5 additions: the older fallbacks shipped
# stackable and other modules' recipes may ask for several in one cell, so they are left as they were.
ONE_PER_SLOT = {"PLASMA_NUCLEUS", "OMEGA_EGG", "COLOSSAL_EXP_BOTTLE_UPGRADE",
                "DWARVEN_OS_ORE_OATS", "DWARVEN_OS_BLOCK_BRAN", "DWARVEN_OS_GEMSTONE_GRAHAMS", "SUMMONING_RING"}


def skin(iid):
    sk = REF.get(iid, {}).get("skin")
    if isinstance(sk, str) and sk.startswith("{"):
        try:
            sk = json.loads(sk.replace("'", '"'))
        except ValueError:
            return None
    if isinstance(sk, dict):
        sk = sk.get("value")
    return sk if isinstance(sk, str) and sk else None


OUT_DIR = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "materials")
OUT_PATH = os.path.join(OUT_DIR, "items_integration.json")


def family_ids():
    """Ids the generated family files (gen_materials.py) already define - never duplicated here."""
    ids = set()
    for f in glob.glob(os.path.join(OUT_DIR, "items_*.json")):
        if os.path.basename(f) == "items_integration.json":
            continue
        for d in json.load(open(f, encoding="utf8")):
            ids.add(d["id"])
    return ids


def main():
    """Writes items_integration.json; returns how many fallbacks it holds. gen_materials.py calls this."""
    out, skipped = [], []
    have = family_ids()
    for iid, (name, mat, rarity, lore, owner) in ITEMS.items():
        if iid in have:
            skipped.append(iid)
            continue
        ref = REF.get(iid, {})
        material = mat or MAT.get(ref.get("material", ""), ref.get("material") or "PAPER")
        d = {
            "id": iid,
            "name": name,
            "material": material,
            "rarity": ref.get("tier") or rarity,
            "category": "REFORGE_STONE" if iid == "GEOMETRIC_ODDITY" else "MATERIAL",
            "lore": lore,
            "extra": {"family": "misc", "fallbackFor": owner},
        }
        if material == "PLAYER_HEAD":
            s = skin(iid)
            if s:
                d["skull"] = s
            else:
                d["material"] = "PAPER"
        if iid == "ENCHANTED_BONE_MEAL":
            d["tags"] = ["no_recipe"]   # sold by the Farm Merchant and the Bone trade; not a compression
        if ref.get("glowing"):
            d["glow"] = True
        for k, v in EXTRA_FIELDS.get(iid, {}).items():
            if k.startswith("extra_"):
                d["extra"][k[len("extra_"):]] = v
            else:
                d[k] = v
        if iid in ONE_PER_SLOT:
            d["maxStack"] = 1   # one per slot, like the reference
        sell = ref.get("npc_sell_price")
        if isinstance(sell, (int, float)) and sell > 0:
            d["npcSell"] = sell
        out.append(d)
    with open(OUT_PATH, "w", encoding="utf8", newline="\n") as f:
        json.dump(out, f, indent=1, ensure_ascii=False)
        f.write("\n")
    if skipped:
        print("skipped (already in a family file):", ", ".join(skipped))
    print("wrote %d items -> %s" % (len(out), OUT_PATH))
    return len(out)


if __name__ == "__main__":
    main()
