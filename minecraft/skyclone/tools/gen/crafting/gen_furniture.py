"""Generate data/crafting/furniture.json: the Carpentry furniture line.

    python tools/gen/crafting/gen_furniture.py

The reference game unlocks one furniture recipe per Carpentry level (research/02, Carpentry table,
levels 1-25). Ids, rarities and skull textures come from the NEU repo; the BUILD (which blocks a
placed piece is made of) and the RECIPE are our own design (TUNE) because the reference builds them
from armour stands and its recipes are not in the data.

Structure coordinates: x = to the player's right, y = up, z = toward the player (so -z is "away").
The piece is authored for a player facing NORTH and rotated to the player's facing when placed.
"HEAD" = a player head wearing the furniture's own texture. "seat" = where a player sits (right-click).
"""
import json
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
DATA = os.path.join(ROOT, "research", "data")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "crafting", "furniture.json")

# level, NEU id, display name, our description, recipe (9 cells, Material names), blocks, seats
F = [
    (1, "MINION_CHAIR", "Minion Chair", "A tiny chair sized for a minion.",
     ["", "", "", "OAK_PLANKS:4", "", "", "OAK_SLAB:4", "OAK_SLAB:4", ""],
     [[0, 0, 0, "oak_stairs[facing=north]"]], [[0, 0, 0]]),
    (1, "SKYBLOCK_CHAIR", "Dark Oak Chair", "A sturdy armchair for long evenings.",
     ["DARK_OAK_PLANKS:8", "", "", "DARK_OAK_PLANKS:8", "DARK_OAK_PLANKS:8", "", "STICK:4", "STICK:4", ""],
     [[0, 0, 0, "dark_oak_stairs[facing=north]"], [-1, 0, 0, "dark_oak_trapdoor[facing=west,open=true]"],
      [1, 0, 0, "dark_oak_trapdoor[facing=east,open=true]"]], [[0, 0, 0]]),
    (2, "FANCY_FLOWER_POT", "Flower Pot", "A bloom in a painted pot.",
     ["", "", "", "BRICK:8", "POPPY:4", "BRICK:8", "", "BRICK:8", ""],
     [[0, 0, 0, "potted_red_tulip"]], []),
    (3, "SKYBLOCK_BENCH", "Dark Oak Bench", "Room for the whole co-op.",
     ["", "", "", "DARK_OAK_STAIRS:8", "DARK_OAK_STAIRS:8", "DARK_OAK_STAIRS:8", "STICK:8", "", "STICK:8"],
     [[-1, 0, 0, "dark_oak_stairs[facing=north]"], [0, 0, 0, "dark_oak_stairs[facing=north]"],
      [1, 0, 0, "dark_oak_stairs[facing=north]"]], [[-1, 0, 0], [0, 0, 0], [1, 0, 0]]),
    (4, "SKYBLOCK_TABLE", "Dark Oak Table", "Every cozy room needs a table.",
     ["DARK_OAK_SLAB:16", "DARK_OAK_SLAB:16", "DARK_OAK_SLAB:16", "", "DARK_OAK_FENCE:8", "", "", "DARK_OAK_FENCE:8", ""],
     [[0, 0, 0, "dark_oak_fence"], [0, 1, 0, "brown_carpet"]], []),
    (5, "ARMOR_SHOWCASE", "Armor Stand", "A display pedestal for your finest gear.",
     ["", "STONE_SLAB:8", "", "", "STICK:16", "", "STONE_SLAB:8", "SMOOTH_STONE:8", "STONE_SLAB:8"],
     [[0, 0, 0, "smooth_stone_slab[type=bottom]"]], []),
    (6, "SCARECROW", "Scarecrow", "Keeps the birds off your crops. Mostly.",
     ["", "CARVED_PUMPKIN:1", "", "STICK:8", "HAY_BLOCK:4", "STICK:8", "", "OAK_FENCE:4", ""],
     [[0, 0, 0, "oak_fence"], [0, 1, 0, "hay_block"], [-1, 1, 0, "oak_fence"], [1, 1, 0, "oak_fence"],
      [0, 2, 0, "carved_pumpkin[facing=south]"]], []),
    (7, "DESK", "Desk", "A tidy place to plan your next grind.",
     ["SPRUCE_SLAB:16", "SPRUCE_SLAB:16", "SPRUCE_SLAB:16", "SPRUCE_PLANKS:16", "BOOK:4", "SPRUCE_PLANKS:16", "STICK:8", "", "STICK:8"],
     [[0, 0, 0, "spruce_slab[type=top]"], [1, 0, 0, "spruce_slab[type=top]"], [1, 1, 0, "HEAD"],
      [-1, 0, 0, "spruce_trapdoor[facing=west,open=true]"], [2, 0, 0, "spruce_trapdoor[facing=east,open=true]"]], []),
    (8, "BOOKCASE", "Bookcase", "Filling a room with books makes anyone look smarter.",
     ["OAK_PLANKS:16", "OAK_PLANKS:16", "OAK_PLANKS:16", "BOOK:16", "BOOK:16", "BOOK:16", "OAK_PLANKS:16", "OAK_PLANKS:16", "OAK_PLANKS:16"],
     [[0, 0, 0, "bookshelf"], [1, 0, 0, "bookshelf"], [0, 1, 0, "bookshelf"], [1, 1, 0, "bookshelf"],
      [0, 2, 0, "oak_slab[type=bottom]"], [1, 2, 0, "oak_slab[type=bottom]"]], []),
    (9, "SMALL_SHELVES", "Small Shelves", "Little shelves for little treasures.",
     ["", "", "", "SPRUCE_SLAB:16", "SPRUCE_SLAB:16", "SPRUCE_SLAB:16", "FLOWER_POT:4", "", "FLOWER_POT:4"],
     [[0, 0, 0, "spruce_slab[type=top]"], [0, 1, 0, "spruce_slab[type=top]"], [0, 2, 0, "flower_pot"]], []),
    (10, "WEAPON_RACK", "Weapon Rack", "Show off the blades you are proudest of.",
     ["", "", "", "SPRUCE_FENCE:16", "IRON_INGOT:8", "SPRUCE_FENCE:16", "SPRUCE_SLAB:16", "", "SPRUCE_SLAB:16"],
     [[0, 0, 0, "spruce_fence"], [1, 0, 0, "spruce_fence"], [0, 1, 0, "spruce_slab[type=bottom]"],
      [1, 1, 0, "spruce_slab[type=bottom]"], [0, 2, 0, "HEAD"]], []),
    (11, "FIRE_PIT", "Fire Pit", "A crackling ring of stone and flame.",
     ["", "COAL:32", "", "COBBLESTONE:32", "OAK_LOG:16", "COBBLESTONE:32", "", "COBBLESTONE:32", ""],
     [[0, 0, 0, "campfire[lit=true]"], [-1, 0, 0, "cobblestone_slab"], [1, 0, 0, "cobblestone_slab"],
      [0, 0, -1, "cobblestone_slab"], [0, 0, 1, "cobblestone_slab"]], []),
    (12, "TIKI_TORCH", "Tiki Torch", "A tall torch with a tropical attitude.",
     ["", "COAL:16", "", "", "JUNGLE_FENCE:8", "", "", "JUNGLE_FENCE:8", ""],
     [[0, 0, 0, "jungle_fence"], [0, 1, 0, "jungle_fence"], [0, 2, 0, "torch"]], []),
    (13, "FURNACE_PLUS", "Furnace+", "A grand stone oven that really cooks.",
     ["STONE_BRICKS:32", "STONE_BRICKS:32", "STONE_BRICKS:32", "STONE_BRICKS:32", "FURNACE:4", "STONE_BRICKS:32", "COAL:32", "COAL:32", "COAL:32"],
     [[0, 0, 0, "furnace[facing=south,lit=true]"], [-1, 0, 0, "stone_bricks"], [1, 0, 0, "stone_bricks"],
      [0, 1, 0, "stone_brick_slab[type=bottom]"], [-1, 1, 0, "stone_brick_stairs[facing=east]"], [1, 1, 0, "stone_brick_stairs[facing=west]"]], []),
    (13, "FIREPLACE", "Fireplace", "Warm bricks and a warmer fire.",
     ["BRICKS:32", "BRICKS:32", "BRICKS:32", "BRICKS:32", "OAK_LOG:32", "BRICKS:32", "BRICKS:32", "COAL:32", "BRICKS:32"],
     [[-1, 0, 0, "bricks"], [1, 0, 0, "bricks"], [0, 0, 0, "campfire[lit=true]"], [-1, 1, 0, "bricks"],
      [1, 1, 0, "bricks"], [0, 1, 0, "brick_slab[type=top]"], [-1, 2, 0, "brick_stairs[facing=east]"],
      [0, 2, 0, "bricks"], [1, 2, 0, "brick_stairs[facing=west]"]], []),
    (14, "CHEST_SHELVES", "Chest Storage", "Stacks of crates for that well-stocked look.",
     ["OAK_PLANKS:32", "OAK_PLANKS:32", "OAK_PLANKS:32", "OAK_PLANKS:32", "CHEST:8", "OAK_PLANKS:32", "OAK_PLANKS:32", "OAK_PLANKS:32", "OAK_PLANKS:32"],
     [[0, 0, 0, "barrel[facing=south]"], [1, 0, 0, "barrel[facing=south]"], [0, 1, 0, "barrel[facing=south]"]], []),
    (15, "BEST_WEAPON_RACK", "Weapon Rack+", "A rack worthy of a legendary arsenal.",
     ["DARK_OAK_FENCE:16", "GOLD_INGOT:32", "DARK_OAK_FENCE:16", "DARK_OAK_PLANKS:32", "IRON_BLOCK:2", "DARK_OAK_PLANKS:32", "DARK_OAK_SLAB:32", "", "DARK_OAK_SLAB:32"],
     [[-1, 0, 0, "dark_oak_fence"], [0, 0, 0, "dark_oak_fence"], [1, 0, 0, "dark_oak_fence"],
      [-1, 1, 0, "dark_oak_slab[type=bottom]"], [0, 1, 0, "dark_oak_slab[type=bottom]"], [1, 1, 0, "dark_oak_slab[type=bottom]"],
      [0, 2, 0, "HEAD"]], []),
    (16, "LARGE_BED", "Large Bed", "Room to stretch out after a long day.",
     ["", "", "", "WHITE_WOOL:32", "WHITE_WOOL:32", "WHITE_WOOL:32", "OAK_PLANKS:32", "OAK_PLANKS:32", "OAK_PLANKS:32"],
     [[0, 0, 0, "red_bed[part=foot,facing=north]"], [0, 0, -1, "red_bed[part=head,facing=north]"],
      [1, 0, 0, "red_bed[part=foot,facing=north]"], [1, 0, -1, "red_bed[part=head,facing=north]"]], []),
    (16, "HAY_BED", "Hay Bed", "Rustic, scratchy, surprisingly comfy.",
     ["", "", "", "HAY_BLOCK:16", "HAY_BLOCK:16", "WHITE_WOOL:8", "OAK_PLANKS:16", "OAK_PLANKS:16", "OAK_PLANKS:16"],
     [[0, 0, 0, "hay_block"], [0, 0, -1, "hay_block"], [0, 1, 0, "white_carpet"], [0, 1, -1, "white_carpet"]], [[0, 1, 0]]),
    (17, "FOOD_TROUGH", "Food Trough", "Keeps the animals fed and happy.",
     ["", "", "", "SPRUCE_PLANKS:32", "WHEAT:64", "SPRUCE_PLANKS:32", "SPRUCE_SLAB:32", "SPRUCE_SLAB:32", "SPRUCE_SLAB:32"],
     [[-1, 0, 0, "composter[level=7]"], [0, 0, 0, "composter[level=7]"], [1, 0, 0, "composter[level=7]"]], []),
    (17, "WATER_TROUGH", "Water Trough", "Fresh water, always in reach.",
     ["", "", "", "STONE_BRICKS:32", "WATER_BUCKET:1", "STONE_BRICKS:32", "STONE_BRICK_SLAB:32", "STONE_BRICK_SLAB:32", "STONE_BRICK_SLAB:32"],
     [[-1, 0, 0, "water_cauldron[level=3]"], [0, 0, 0, "water_cauldron[level=3]"], [1, 0, 0, "water_cauldron[level=3]"]], []),
    (18, "MEDIUM_SHELVES", "Medium Shelves", "Shelves for the collector in you.",
     ["SPRUCE_SLAB:32", "SPRUCE_SLAB:32", "SPRUCE_SLAB:32", "BOOK:16", "FLOWER_POT:8", "BOOK:16", "SPRUCE_SLAB:32", "SPRUCE_SLAB:32", "SPRUCE_SLAB:32"],
     [[0, 0, 0, "spruce_slab[type=top]"], [1, 0, 0, "spruce_slab[type=top]"], [0, 1, 0, "spruce_slab[type=top]"],
      [1, 1, 0, "spruce_slab[type=top]"], [0, 2, 0, "flower_pot"], [1, 2, 0, "HEAD"]], []),
    (19, "CRAFTING_PLUS", "Crafting Table+", "A real workbench: it opens the crafting grid.",
     ["OAK_PLANKS:32", "OAK_PLANKS:32", "OAK_PLANKS:32", "OAK_LOG:16", "CRAFTING_TABLE:16", "OAK_LOG:16", "OAK_LOG:16", "", "OAK_LOG:16"],
     [[0, 0, 0, "crafting_table"], [-1, 0, 0, "oak_log"], [1, 0, 0, "oak_log"], [0, 1, 0, "HEAD"]], []),
    (20, "REDSTONE_CHEST", "Redstone Chest+", "A chest that hums with power.",
     ["REDSTONE:64", "REDSTONE:64", "REDSTONE:64", "REDSTONE:64", "CHEST:4", "REDSTONE:64", "REDSTONE:64", "REDSTONE:64", "REDSTONE:64"],
     [[0, 0, 0, "red_nether_bricks"], [0, 1, 0, "HEAD"]], []),
    (20, "EMERALD_CHEST", "Emerald Chest+", "A chest that smells faintly of trades.",
     ["EMERALD:32", "EMERALD:32", "EMERALD:32", "EMERALD:32", "CHEST:4", "EMERALD:32", "EMERALD:32", "EMERALD:32", "EMERALD:32"],
     [[0, 0, 0, "prismarine_bricks"], [0, 1, 0, "HEAD"]], []),
    (20, "ENDER_PLUS", "Ender Chest+", "A chest that seems bigger on the inside.",
     ["OBSIDIAN:32", "ENDER_PEARL:16", "OBSIDIAN:32", "ENDER_PEARL:16", "CHEST:4", "ENDER_PEARL:16", "OBSIDIAN:32", "ENDER_PEARL:16", "OBSIDIAN:32"],
     [[0, 0, 0, "crying_obsidian"], [0, 1, 0, "HEAD"]], []),
    (20, "ENDSTONE_CHEST", "End Stone Chest+", "Carved from the far edge of the world.",
     ["END_STONE:64", "END_STONE:64", "END_STONE:64", "END_STONE:64", "CHEST:4", "END_STONE:64", "END_STONE:64", "END_STONE:64", "END_STONE:64"],
     [[0, 0, 0, "end_stone_bricks"], [0, 1, 0, "HEAD"]], []),
    (20, "GOLD_CHEST", "Gold Chest+", "Gilded, and proud of it.",
     ["GOLD_INGOT:32", "GOLD_INGOT:32", "GOLD_INGOT:32", "GOLD_INGOT:32", "CHEST:4", "GOLD_INGOT:32", "GOLD_INGOT:32", "GOLD_INGOT:32", "GOLD_INGOT:32"],
     [[0, 0, 0, "smooth_sandstone"], [0, 1, 0, "HEAD"]], []),
    (20, "WOOD_CHEST", "Wood Chest+", "Honest wood, honest work.",
     ["OAK_PLANKS:64", "OAK_PLANKS:64", "OAK_PLANKS:64", "OAK_PLANKS:64", "CHEST:4", "OAK_PLANKS:64", "OAK_PLANKS:64", "OAK_PLANKS:64", "OAK_PLANKS:64"],
     [[0, 0, 0, "stripped_oak_wood"], [0, 1, 0, "HEAD"]], []),
    (20, "DIAMOND_CHEST", "Diamond Chest+", "For people who want everyone to know.",
     ["DIAMOND:16", "DIAMOND:16", "DIAMOND:16", "DIAMOND:16", "CHEST:4", "DIAMOND:16", "DIAMOND:16", "DIAMOND:16", "DIAMOND:16"],
     [[0, 0, 0, "light_blue_glazed_terracotta"], [0, 1, 0, "HEAD"]], []),
    (20, "LAPIS_CHEST", "Lapis Chest+", "Deep blue, like a summer sky.",
     ["LAPIS_LAZULI:64", "LAPIS_LAZULI:64", "LAPIS_LAZULI:64", "LAPIS_LAZULI:64", "CHEST:4", "LAPIS_LAZULI:64", "LAPIS_LAZULI:64", "LAPIS_LAZULI:64", "LAPIS_LAZULI:64"],
     [[0, 0, 0, "blue_terracotta"], [0, 1, 0, "HEAD"]], []),
    (20, "IRON_CHEST", "Iron Chest+", "Reinforced, riveted, reliable.",
     ["IRON_INGOT:32", "IRON_INGOT:32", "IRON_INGOT:32", "IRON_INGOT:32", "CHEST:4", "IRON_INGOT:32", "IRON_INGOT:32", "IRON_INGOT:32", "IRON_INGOT:32"],
     [[0, 0, 0, "polished_andesite"], [0, 1, 0, "HEAD"]], []),
    (21, "BREWING_PLUS", "Brewing+", "An alchemist's showpiece.",
     ["", "BLAZE_ROD:16", "", "NETHER_WART:32", "BREWING_STAND:4", "NETHER_WART:32", "COBBLESTONE:32", "COBBLESTONE:32", "COBBLESTONE:32"],
     [[0, 0, 0, "polished_blackstone_bricks"], [0, 1, 0, "brewing_stand"], [-1, 0, 0, "polished_blackstone_brick_wall"],
      [1, 0, 0, "polished_blackstone_brick_wall"]], []),
    (22, "ENCHANTING_PLUS", "Enchanting Table+", "Arcane decor for the well-read wizard.",
     ["BOOKSHELF:8", "LAPIS_LAZULI:64", "BOOKSHELF:8", "OBSIDIAN:16", "ENCHANTING_TABLE:1", "OBSIDIAN:16", "BOOKSHELF:8", "LAPIS_LAZULI:64", "BOOKSHELF:8"],
     [[0, 0, 0, "obsidian"], [0, 1, 0, "enchanting_table"], [-1, 0, 0, "bookshelf"], [1, 0, 0, "bookshelf"]], []),
    (23, "BLACKSMITH_PLUS", "Blacksmith+", "Anvil, coal and the ring of hammer on steel.",
     ["IRON_BLOCK:2", "IRON_BLOCK:2", "IRON_BLOCK:2", "COAL:64", "ANVIL:1", "COAL:64", "STONE_BRICKS:32", "STONE_BRICKS:32", "STONE_BRICKS:32"],
     [[0, 0, 0, "smithing_table"], [-1, 0, 0, "blast_furnace[facing=south]"], [1, 0, 0, "polished_andesite"], [1, 1, 0, "anvil[facing=east]"]], []),
    (24, "SKULL_CHEST", "Skull Chest++", "A chest that stares back.",
     ["BONE:64", "BONE:64", "BONE:64", "BONE:64", "CHEST:8", "BONE:64", "BONE:64", "BONE:64", "BONE:64"],
     [[0, 0, 0, "bone_block"], [0, 1, 0, "HEAD"]], []),
    (25, "GRANDFATHER_CLOCK", "Grandfather Clock", "Tells the island time. The pinnacle of carpentry.",
     ["DARK_OAK_PLANKS:64", "GOLD_INGOT:32", "DARK_OAK_PLANKS:64", "DARK_OAK_PLANKS:64", "CLOCK:4", "DARK_OAK_PLANKS:64", "DARK_OAK_PLANKS:64", "GOLD_INGOT:32", "DARK_OAK_PLANKS:64"],
     [[0, 0, 0, "dark_oak_log"], [0, 1, 0, "dark_oak_log"], [0, 2, 0, "HEAD"]], []),
]

# What a placed piece DOES, from the reference lore (NEU): "Seat", "Storage" ("three pages of items"),
# "Item Showcase", "Opens the Crafting menu!", "Use to set your spawn location!", "An infinite source of water",
# "accurately tells the time". Pieces not listed are cosmetic. Page counts the lore does not give are TUNE.
#   seat            right-click to sit (seats come from the build)
#   minion_seat     seats a minion (needs the minions module; players can sit meanwhile)
#   storage:N       N pages of 45 slots, kept per placed piece
#   showcase:armor  4 armour pieces + 1 weapon worn by a display stand
#   showcase:weapons:N   N weapons on the rack     showcase:best  1 weapon + a stats hologram
#   station:<kind>  a real block another system answers (crafting, furnace, brewing, enchanting, anvil)
#   bed             sets your island spawn           water   fills buckets and bottles     clock   tells the time
FUNC = {
    "MINION_CHAIR": "minion_seat", "SKYBLOCK_CHAIR": "seat", "SKYBLOCK_BENCH": "seat",
    "ARMOR_SHOWCASE": "showcase:armor", "WEAPON_RACK": "showcase:weapons:4", "BEST_WEAPON_RACK": "showcase:best",
    "FURNACE_PLUS": "station:furnace", "CRAFTING_PLUS": "station:crafting", "BREWING_PLUS": "station:brewing",
    "ENCHANTING_PLUS": "station:enchanting", "BLACKSMITH_PLUS": "station:anvil",
    "CHEST_SHELVES": "storage:3",          # TUNE: lore only says "extra large chest"
    "MEDIUM_SHELVES": "storage:3", "SKULL_CHEST": "storage:6",
    "REDSTONE_CHEST": "storage:3", "EMERALD_CHEST": "storage:3", "ENDER_PLUS": "storage:3", "ENDSTONE_CHEST": "storage:3",
    "GOLD_CHEST": "storage:3", "WOOD_CHEST": "storage:3", "DIAMOND_CHEST": "storage:3", "LAPIS_CHEST": "storage:3",
    "IRON_CHEST": "storage:3",
    "LARGE_BED": "bed", "HAY_BED": "bed", "WATER_TROUGH": "water", "GRANDFATHER_CLOCK": "clock",
}
# Blocks that must be free when placing but are not part of the build (the armour stand stands there).
SPACE = {"ARMOR_SHOWCASE": [[0, 1, 0], [0, 2, 0]]}

# NEU id differs from ours where NEU's id is also a vanilla Material (core would convert every vanilla
# campfire into this furniture otherwise).
NEU_ID = {"FIRE_PIT": "CAMPFIRE"}


def texture(nbt):
    m = re.search(r'Value:"([A-Za-z0-9+/=]+)"', nbt or "")
    return m.group(1) if m else None


def main():
    items = {i["id"]: i for i in json.load(open(os.path.join(DATA, "items.json"), encoding="utf-8"))["items"]}
    out = []
    for level, fid, name, desc, recipe, blocks, seats in F:
        neu_id = NEU_ID.get(fid, fid)
        path = os.path.join(DATA, "neu-repo", "items", neu_id + ".json")
        skull = None
        if os.path.exists(path):
            skull = texture(json.load(open(path, encoding="utf-8")).get("nbttag"))
        tier = items.get(neu_id, {}).get("tier") or "COMMON"
        if tier == "UNOBTAINABLE":
            tier = "COMMON"
        assert len(recipe) == 9, fid
        out.append({"id": fid, "name": name, "description": desc, "level": level, "rarity": tier,
                    "skull": skull, "recipe": recipe, "blocks": blocks, "seats": seats,
                    "function": FUNC.get(fid, "cosmetic"), "space": SPACE.get(fid, [])})
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        json.dump(out, f, indent=1)
    print("furniture:", len(out), "with textures:", sum(1 for o in out if o["skull"]))


if __name__ == "__main__":
    main()
