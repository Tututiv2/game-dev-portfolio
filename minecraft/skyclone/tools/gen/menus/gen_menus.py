"""Generate the menus module's data tables from research/data.

    python tools/gen/menus/gen_menus.py

Writes plugin/src/main/resources/data/menus/:
  collections.json  every collection (category, id, maxTiers) - the SkyBlock Menu's
                    "Collections Unlocked: x%" line and the profile viewer's summary.
  boss_collections.json  the boss collections (id, maxTiers), read from the collections
                    module's own data (research has none): the "Boss Collections" line.
  textures.json     skull textures for menu icons (island portal heads for Fast Travel,
                    bank / bag / bestiary heads), lifted from the NEU repo item files.

Nothing here is typed by hand except the list of NEU ids we want textures for.
"""
import json
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
RESEARCH = os.path.join(ROOT, "research", "data")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "menus")

# icon key -> NEU item id whose skull texture we borrow
TEXTURE_SOURCES = {
    # Fast Travel: one portal head per island / sub-warp
    "island.hub": "HUB_PORTAL",
    "island.barn": "FARMING_1_PORTAL",
    "island.mushroom_desert": "FARMING_2_PORTAL",
    "island.park": "FORAGING_1_PORTAL",
    "island.gold_mine": "MINING_1_PORTAL",
    "island.deep_caverns": "MINING_2_PORTAL",
    "island.dwarven_mines": "DWARVEN_MINES_PORTAL",
    "island.crystal_hollows": "CRYSTAL_HOLLOWS_PORTAL",
    "island.glacite_tunnels": "BASE_CAMP_PORTAL",
    "island.spiders_den": "DANGER_1_PORTAL",
    "island.crimson_isle": "DANGER_2_PORTAL",
    "island.the_end": "DANGER_3_PORTAL",
    "island.backwater_bayou": "FISHING_1_PORTAL",
    "island.rift": "RIFT_WIZARD_TOWER_PORTAL",
    "island.kuudra": "KUUDRA_SKULL_PORTAL",
    "warp.castle": "HUB_CASTLE_PORTAL",
    "warp.crypt": "HUB_CRYPTS_PORTAL",
    "warp.museum": "MUSEUM_PORTAL",
    "warp.da": "DARK_AUCTION_PORTAL",
    "warp.wizard": "WIZARD_TOWER_PORTAL",
    "warp.jungle": "FORAGING_3_PORTAL",
    "warp.howl": "HOWLING_CAVE_PORTAL",
    "warp.nest": "TOP_OF_SPIDERS_DEN_PORTAL",
    "warp.arachne": "ARACHNE_SANCTUARY_PORTAL",
    "warp.drag": "DRAGON_NEST_PORTAL",
    "warp.void": "VOID_SEPULTURE_PORTAL",
    "warp.forge": "FORGE_PORTAL",
    "warp.camp": "BASE_CAMP_PORTAL",
    "warp.nucleus": "CRYSTAL_NUCLEUS_PORTAL",
    "warp.trapper": "TRAPPER_DEN_PORTAL",
    "warp.tomb": "SMOLDERING_TOMB_PORTAL",
    "warp.mushroom_cave": "GLOWING_MUSHROOM_CAVE_PORTAL",
    # SkyBlock Menu / bags / profile icons
    "icon.bank": "PERSONAL_BANK_ITEM",
    "icon.bags": "SMALL_BACKPACK",
    "icon.storage": "JUMBO_BACKPACK",
    "icon.bestiary": "BESTIARY_NPC",
}

VALUE = re.compile(r'Value:\\?"([A-Za-z0-9+/=]+)\\?"')


def texture_of(neu_id):
    path = os.path.join(RESEARCH, "neu-repo", "items", neu_id + ".json")
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as f:
        d = json.load(f)
    m = VALUE.search(d.get("nbttag", ""))
    return m.group(1) if m else None


def collections():
    with open(os.path.join(RESEARCH, "collections.json"), encoding="utf-8") as f:
        d = json.load(f)
    out = []
    for cat, block in d["collections"].items():
        for item_id, item in block["items"].items():
            out.append({"category": cat, "id": item_id, "maxTiers": int(item["maxTiers"])})
    return out


def boss_collections():
    path = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "collections", "collections.json")
    with open(path, encoding="utf-8") as f:
        d = json.load(f)
    return [{"category": "BOSS", "id": c["id"], "maxTiers": len(c["tiers"])}
            for c in d["collections"] if c.get("boss") and c.get("tiers")]


def main():
    os.makedirs(OUT, exist_ok=True)
    cols = collections()
    with open(os.path.join(OUT, "collections.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(cols, f, indent=1)
    bosses = boss_collections()
    with open(os.path.join(OUT, "boss_collections.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(bosses, f, indent=1)
    tex = {}
    missing = []
    for key, neu in TEXTURE_SOURCES.items():
        t = texture_of(neu)
        if t:
            tex[key] = t
        else:
            missing.append(neu)
    with open(os.path.join(OUT, "textures.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(tex, f, indent=1, sort_keys=True)
    tiers = sum(c["maxTiers"] for c in cols)
    print(f"collections.json: {len(cols)} collections, {tiers} tiers")
    print(f"boss_collections.json: {len(bosses)} boss collections")
    print(f"textures.json: {len(tex)} textures" + (f", MISSING {missing}" if missing else ""))
    return 0 if not missing else 1


if __name__ == "__main__":
    sys.exit(main())
