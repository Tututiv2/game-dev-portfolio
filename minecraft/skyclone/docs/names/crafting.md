# crafting — renames

The crafting module shows almost no proper nouns of its own: recipe results are shown with the name
their OWNING module registered, categories and menus use generic words. What it does name:

| id | reference name | our name |
|---|---|---|
| FIRE_PIT | Fire Pit (reference id `CAMPFIRE`) | Fire Pit — **id changed**, not the name: the reference id is also the vanilla `CAMPFIRE` material, and registering it would turn every vanilla campfire into this furniture |
| SKYBLOCK_CHAIR | Dark Oak Chair | Dark Oak Chair (generic, kept) |
| SKYBLOCK_BENCH | Dark Oak Bench | Dark Oak Bench (generic, kept) |
| SKYBLOCK_TABLE | Dark Oak Table | Dark Oak Table (generic, kept) |
| ARMOR_SHOWCASE | Armor Stand | Armor Stand (generic, kept) |
| FANCY_FLOWER_POT | Flower Pot | Flower Pot (generic, kept) |

All 37 Carpentry furniture names (Minion Chair, Desk, Bookcase, Small/Medium Shelves, Weapon Rack(+),
Tiki Torch, Furnace+, Fireplace, Chest Storage, Large Bed, Hay Bed, Food/Water Trough, Crafting Table+,
the nine "<Material> Chest+", Brewing+, Enchanting Table+, Blacksmith+, Skull Chest++, Grandfather Clock)
are generic descriptions and are kept. Every furniture DESCRIPTION is our own wording (the reference
lore is not used). They are registered by this module only when no other module registered the id.

Menu / feature words used, all generic: Craft Item, Recipe Book, Quick Crafting, Supercraft, Trades,
Raw Materials, Used In, Craftable Now, Recently Crafted, Favorites, Carpentry.

Trade names ("Dirt Trade", "Water Bucket Trade"...) name vanilla items and are generic.
