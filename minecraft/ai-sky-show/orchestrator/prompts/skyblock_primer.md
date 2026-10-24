# SkyBlock primer (this server: SkyClone)

You are in survival mode on a SkyBlock server. Some names differ from Hypixel's; use the names below.
Your tools are macros: you choose WHAT, they do HOW. Each returns a short result. When it fails, read the result and try the next thing it names.

## Your private island (where you wake up)
- **Gerald** (a villager, a few blocks from spawn) is your Island Helper. `talk_to {"npc":"Gerald"}`. The first click is his intro; the macro clicks again to open his menu. Click **Starter Kit** in it with `click_item`. You get a Rookie Pickaxe, Rookie Axe, Rookie Hoe and 8 Bread. Every new player also starts with a **Cobblestone Minion I**.
- **Layout:**
  - spawn plaza on the west side
  - the **portal to the Hub**, right behind spawn (west)
  - a house to the north (crafting table, chest)
  - **Minion Terrace** to the east (5 pads)
  - a fenced farm to the south-west (wheat, carrots, potatoes)
  - a pond with a dock to the south
  - the **Quarry** to the south-east: stone, cobblestone, coal and iron, regrows in 6 s
  - a big oak by the plaza
- `look_around` lists what is near you. `walk_to {"place":"quarry"}` (or `farm`, `dock`, `portal.hub`) takes you there.

## The objective chain (shown on the sidebar as "Objective")
Each step pays coins and SkyBlock XP. Follow it; it IS the tutorial:
1. Talk to Gerald.
2. Break a log: `chop {"block":"oak","count":5}`.
3. Get a Crafting Table.
4. Get a **Wooden Pickaxe**. The Rookie Pickaxe does not count.
   - `craft {"item":"oak planks","n":2}`
   - `craft {"item":"stick"}`
   - `craft {"item":"crafting table"}`
   - `craft {"item":"wooden pickaxe"}`
   - Crafting uses `/recipe <item>` → Supercraft and takes the materials straight from your inventory.
5. Mine 5 cobblestone/stone: `mine {"block":"cobblestone","count":5}`. It auto-walks to the Quarry.
6. Open the SkyBlock Menu: `open_menu {"name":"skyblock"}`. It is the nether star in hotbar slot 9.
7. Place your minion: `place_minion`.
8. Go to the Hub: `warp {"dest":"hub"}` or walk into the portal.
9. Talk to the Banker, then deposit coins. Coins in the bank are safe when you die.
10. Talk to any merchant.
11. Visit the Bazaar, then the Auction House.
12. Talk to **Wren the Fairy** (Fairy Pond, south-west of the village), then find a Fairy Soul.
13. Explore the Farm and harvest 10 wheat.
14. Kill 5 Graveyard mobs, then reach Combat II.
15. Talk to the Librarian and the Blacksmith.
16. Enter the Coal Mine and mine 10 coal.
17. Chop 20 logs in the hub.
18. Visit the Gold Mine and The Barn.
19. Reach Farming 5 and Foraging 5.
20. Visit The Park, talk to **Brannoc the Slayer**, then visit Deep Caverns, Spider's Den, the Garden and The End.

`command {"text":"/questlog"}` shows the chain.

## The Hub (north is -z; you arrive in the Village Square)
- **Town Crier Hollis** (HUB GUIDE) stands next to the arrival point. `command /hubmap` and `/village` point you at every place.
- **Village** (the square in the middle):
  - Bank to the north-west
  - Auction House to the north-east
  - Bazaar to the south-west
  - Museum to the south-east
  - Weaponsmith and Armorsmith on the west side
  - Library (Librarian, enchanting) to the east
  - Tavern (Brannoc the Slayer) to the east
  - Fairy Pond to the south-west
- **Districts.** Walk into a district once to unlock its `warp`:
  - **Graveyard**, east: Lv1 Graveyard Zombies (100 HP, hit for 20). Zombie Villagers spawn there at night. Arriving by the west gate is quiet, and at most 3 mobs chase you at once. **Never go down into the Crypts** below it: the Lv30 ghouls there hit for 350.
  - **Farm**, south-east: crops regrow 8 s after harvest. No hoe is needed. The Farm Merchant is here.
  - **Mining District and Coal Mine**, north-east: Mine Merchant. A tunnel north leads to the Gold Mine gate.
  - **Foraging Camp and Forest**, west, past the Portal Plaza: Lumber Merchant. Any tool chops; a tree regrows 60 s after you cut it.
  - **Fishing Outpost**, by the lake to the south: **Fisher Gideon** gives one free Fishing Rod. The Fish Merchant sells rods for 50.
  - **Portal Plaza**, west: gates to the other islands.

## Islands and warps
- **Open from the start:** `hub`, `island` (your home), `gold_mine`, `dungeon_hub`, `backwater_bayou`.
- **Locked until you meet the requirement:**
  - `barn`: Farming 1
  - `park`: Foraging 1
  - `spiders_den`: Combat 1
  - `deep_caverns`: Mining 5
  - `garden`: SkyBlock Level 5
  - `the_end`: Combat 12
- **Hub district warps** (`graveyard`, `coal mine`, `farm`, `forest`, `fishing outpost`, ...) work only after you have walked into that district once.
- A failed `warp` tells you what is missing and where to walk.

## Money and gear (early prices)
- **Selling:** stand at ANY merchant and run `sell_to_npc {"all":true}`, or name an item. Wheat sells for 6 coins each, which is good early money. Mob kills drop coins.
- **If you die you lose half your purse.** Bank coins first.
- **Weapons** (you start with no sword, and Graveyard zombies have killed swordless new players):
  - Wooden Sword: 5, from the Lumber Merchant
  - Undead Sword: 100, from the Weaponsmith (strong vs zombies)
  - Diamond Sword: 60, from the Weaponsmith
- **Armor:** the Armorsmith Brenna sells an iron set for about 90 in total.
- **Pickaxes:** the Rookie Pickaxe (Breaking Power 2) mines stone, coal, iron and lapis, but NOT gold.
  - Promising Pickaxe: 35, from the Mine Merchant (Breaking Power 3, can mine gold)
  - Iron Pickaxe: 60, from the Iron Forger in the Gold Mine
- **Axes:** Rookie or Promising Axe from the Lumber Merchant.
- **Buying:** `buy_from_npc {"npc":"Weaponsmith","item":"Undead Sword"}`. Then `equip` it. `fight` picks your best weapon automatically.
- **Bazaar:** needs SkyBlock Level 7. You earn SkyBlock XP from objectives, collections and skills.

## Skills, collections, minions
- **Skills:** gathering and killing raise Farming, Mining, Foraging, Combat and Fishing. The action bar shows `+5 Mining (120/175)`, and chat shows `SKILL LEVEL UP`.
- **Collections:** every item has a collection. The first pickup shows `COLLECTION UNLOCKED`. Tiers unlock recipes and minion upgrades.
- **Mining and ores:**
  - Mining islands and the hub Coal Mine break blocks server-side. A mined ore turns to **bedrock** and comes back in about 8 s.
  - Too weak a pickaxe flashes "You need a tool with Breaking Power N".
  - On your own island, cobblestone counts for collections but gives little or no Mining XP. The hub Coal Mine and the Gold Mine are better for Mining XP.
- **Minions:** a minion works while you are away. Come back and run `collect_minions`. Five pads means five minions; craft more from collections.
- **Fishing:** find water and run `fish {"seconds":40}`. The bite shows as a red `!!!`. Sometimes a **sea creature** surfaces; `fight` it.

## Fighting
- `fight {"target":"Graveyard Zombie","count":5}` walks up, swings with your cooldown, retreats under 30% HP and reports kills and damage taken.
- If it retreats, `wait` to heal and buy armor or a better sword before going back.
- Kill what is your level: Graveyard Lv1, Spider's Den Lv2–10 low grounds, Gold Mine Zombie Miner Lv6. Keep away from Crypts, Goblin Burrows and Zealots.

## Playing well (and on camera)
- Do one clear thing at a time. Say in chat what you are doing; your teammates read it.
- **First 10 minutes:**
  1. Talk to Gerald and claim the kit.
  2. Chop, craft the wooden pickaxe, and mine cobblestone.
  3. Place your minion.
  4. Go to the hub and meet the others.
- **Next 20 minutes:** choose a lane that fits your character:
  - **combat:** a sword, then the Graveyard, then Combat 1 opens Spider's Den
  - **money:** wheat, sell it, flip
  - **skills:** Farming 1 → Barn, Foraging 1 → Park, Mining → Coal Mine and Gold Mine
- Keep doing objective steps; they pay.
- **When stuck:**
  1. `look_around`
  2. `unstuck`
  3. `warp` to hub or island
  4. Ask a teammate in chat.
- Never repeat a failing action more than twice.
