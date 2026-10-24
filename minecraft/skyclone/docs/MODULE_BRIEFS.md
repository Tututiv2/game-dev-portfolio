# MODULE BRIEFS — wave 1

> One section per feature module. An agent building module `<id>` reads: `CONTRACT.md` →
> `docs/CORE_GUIDE.md` → the API javadoc → **its section here** → the research sections named in
> it. "Research" refers to `research/NN-*.md` and `research/data/` (see `research/09-api-data.md`).
>
> Every module: package `com.skyclone.mod.<id>`, entry `<Id>Module`, data in
> `resources/data/<id>/`, generator scripts in `tools/gen/<id>/`, hand-off `docs/handoff/<id>.md`,
> renames `docs/names/<id>.md`, ≥3 self-tests, `bash tools/check.sh <id>` = CHECK OK.
>
> **Depth markers.** ★★★ = owner's top priority: build it deeper than the reference, polish every
> menu, cover every tier. ★★ = full breadth. ★ = full breadth, simpler presentation is fine.

---

## skills ★★
Provide `SkillService` (api.skill). All 11 skills with the EXACT XP curves from
`research/data/skills.json` (and research 02): per-level XP, max level, per-level stat rewards
(Farming → Farming Fortune + Health, Mining → Mining Fortune + Defense, Combat → Crit Chance,
Foraging → Foraging Fortune + Strength, Fishing → Health + Sea Creature Chance?, Enchanting →
Intelligence + Ability Damage, Alchemy → Intelligence, Taming → Pet Luck, Carpentry → Health,
Runecrafting/Social → none... take the real table), coin rewards, SkyBlock XP awards
(Features.SkyLevel.award), level-up message box exactly like the genre ("SKILL LEVEL UP Farming
IV ➜ V" + REWARDS list), sound. A `StatProvider` "skills". Combat XP from `SkyEvents.MobKill.combatXp`
(only for the killer; owned mobs to owner). Wisdom stat applies. Action-bar flash on XP gain:
"&3+4.5 Farming (1,234/5,000)". Caps (Farming 50→60 unlocked by an item/contest; Taming via pet
score) through setCap. Skill average.
Menus: `/skills` "Your Skills" (the genre layout: one icon per skill with progress bar and
"&eClick to view!"), per-skill "Farming Skill" menu with the 25-or-60 level road paged (each level
an icon, green done / yellow current / red locked, rewards in lore). Requirement resolver `skill`.
Admin: `/sb skill <player> <skill> addxp|setlevel <n>`. Tab widget "Skills".

## collections ★★
Provide `Features.Collections`. All 90 collections, all 790 tiers from `research/data/collections.json`
(+ research 02). Count from `SkyEvents.ItemGain` when `source.natural` (item id → collection id;
enchanted forms do NOT count). Co-op: count per member, tier from the profile's combined total
(store in shared data). Tier-up: message box "COLLECTION LEVEL UP Wheat VII ➜ VIII" + rewards
(recipe unlocks are just requirement keys `collection:WHEAT:8` used by recipes — the crafting
module checks them; you list "Recipe: X" in the reward text by scanning `Sky.recipes().all()` for
that unlock key), SkyBlock XP award. Menus: `/collection` → categories (Farming, Mining, Combat,
Foraging, Fishing, Rift, Boss) → collection list (locked "?" until first item) → tier road.
Requirement resolver `collection:<ITEM>:<tier>`. Admin `/sb collection <player> <item> add <n>`.

## progression ★★
Provide `Features.SkyLevel`, `Features.Objectives`, `Features.Bestiary`, `Features.Museum`.
- SkyBlock Level: 100 XP per level, award(key) one-time; level colour brackets for chat/tab; the
  "SkyBlock Leveling" menu (tasks by category from research 02, emblem/colour rewards).
- Objectives: the guided new-player chain shown on the sidebar ("Objective" section, priority
  100): talk to the island helper → break a log → craft a workbench → … → go to the hub → talk
  to the banker → … → reach Combat 5 etc. Write a 25-30 step chain from research 03/08 that walks
  a new player through every early system in a sensible order; complete steps by listening to
  events (Gather, ItemGain, IslandChange, SkillLevelUp, NPC talks via a tiny objective hook other
  modules call: `Sky.service(Features.Objectives.class).complete(p, "talk_banker")`). Publish the
  list of objective ids other modules should complete in your hand-off. Rewards per step.
- Fairy Souls: 240+ hidden souls (skull of a fairy) placed across islands. You do not build
  islands — after `afterModules`, scan every island layout for anchors named `fairy_soul.*`
  (island builders are told to place them) and spawn a collectible soul there (ItemDisplay/
  armor stand skull + particles), claimed per profile; the Fairy NPC at hub `npc.fairy` exchanges
  each 5 for rewards (health/defense/strength/speed per research 08 + SkyBlock XP).
- Bestiary: listens MobKill; families from `MobDef.familyId()`; tiers/milestones per research 08;
  rewards (Magic Find, Strength…) via a StatProvider; `/bestiary` menu by island.
- Museum: hub `npc.museum` / area `museum`: donate items (weapons/armor/rarities/special),
  milestones, rewards per research 08; donated items are stored (not destroyed) and can be
  borrowed back per genre rules. `/museum`.
- Century cakes/misc small progress counters if research covers them.

## materials ★★
The fallback CATALOGUE. Generate (python script in `tools/gen/materials/` reading
`research/data/items.json` + `neu-repo/items`) an `items.json` covering every MATERIAL-like item
(category none / material) of the reference game: vanilla-derived resources with proper rarity,
ENCHANTED_* compressions (Enchanted Cobblestone ← 160 Cobblestone, Enchanted Diamond Block ← 160
Enchanted Diamond…), all the "compacted" chains, boss/mob drop materials if no specialist will
obviously own them (check the ownership table — when in doubt include it; core keeps the specialist's
because you register LAST), with npcSell prices and correct skull textures. Plus `recipes.json`
for every compression recipe with the right collection unlock key (`collection:<BASE>:<tier>` from
the NEU data). Register in **postEnable** with `if (!Sky.items().exists(id))`. Renames only for
proper nouns. Self-tests: every recipe input/output exists; no ENCHANTED_ item without a recipe.
Expect 1,500+ items; split files by category if large.

## crafting ★★★
The crafting experience the whole game runs on. Right-clicking ANY crafting table (and `/craft`,
and the SkyBlock Menu's Crafting Table) opens the genre's 6-row "Craft Item" menu: a 3×3 FREE grid
(slots 10-12, 19-21, 28-30), result slot 23 showing the match with lore "&eClick to craft!", plus
quick-craft slots on the right showing up to 3 craftable recipes from inventory. Matching supports
per-cell AMOUNTS ("ENCHANTED_DIAMOND:32"), shaped (position-independent offsetting like vanilla)
and shapeless; pulls from inventory only (and from sacks if `Features.Sacks` is present, dial).
Shift-click crafts max. Unlocked check via `Sky.recipes().unlocked`. Vanilla recipes for vanilla
items still work for basics (generate a recipes.json of the useful vanilla recipes as Recipe data so
everything runs through one engine — planks, sticks, crafting table, chests, tools, torches...).
Recipe Book: `/recipe <item>` and `/recipes` — browse by category, search (Sky.menus().input),
each recipe shows a 3×3 preview with "&eClick to craft!" when you have the materials, "Used in"
pages. Carpentry XP for crafting furniture-like items per research. Never dupe: verify inputs at
the moment of crafting, remove, then give via Sky.loot().give(source CRAFT). Free-slot items are
returned on close (core does it — test it).

## enchanting ★★
All enchantments as `EnchantDef` (from research 01 + `neu-repo/constants/enchants.json`), each
EFFECT implemented: damage enchants as DamageModifiers (Sharpness, Smite vs undead, Bane vs
arthropods, Cubism, Ender Slayer, Dragon Hunter, Giant Killer, Execute, Prosecute, First Strike,
Triple-Strike, Critical, Overload…), stat enchants as ItemStatContributors (Protection, Growth,
True Protection, Critical, Sugar Rush, Efficiency → mining speed…), on-hit/kill enchants as
listeners (Life Steal, Syphon, Vampirism, Looting, Scavenger, Luck, Experience, Venomous, Thunderlord,
Fire Aspect, Telekinesis is default-on), ultimates (One For All, Soul Eater, Chimera, Legion, Last
Stand, Wisdom, Combo, Swarm, Ultimate Jerry, Bank, Rend, Duplex, Fatal Tempo, Inferno, Refrigerate,
the Ultimate Wise family...) — research 01 has the table; use the 2026 values. Enchanting table:
right-click a real enchanting table → "Enchant Item" menu (place item in the free slot, list of
applicable enchants with level choices costing XP levels per research, bookshelf power), anvil
menu ("Combine Items": book+item, book+book same level → +1 up to the cap, hot potato books
handed off to reforges? — no: potato books are applied in the anvil here, ask reforges for nothing),
Experimentation Table (Superpairs, Chronomatron, Ultrasequencer) with the reward tables, enchanting
XP awards. ENCHANTED_BOOK items carry their enchant in ItemData.extra. Enchanted book naming.

## reforges ★★
All reforges as `ReforgeDef` (blacksmith random sets per category + every reforge stone with its
bonus ability) from research 01 / `neu-repo/constants/reforges.json`. Blacksmith at hub
`npc.blacksmith` and the reforge anvil: "Reforge Item" menu (free slot, cost by rarity, random
reforge for the category, "&eClick to reforge!"), "Reforge Anvil" for stones. Reforge-stone bonus
abilities implemented (DamageModifiers/listeners/StatProviders). Also: Recombobulator 3000 (apply
via anvil-like menu), Hot/Fuming Potato Books (cap 10/15), essence + dungeon stars (upgrade menu:
1-5 stars with essence costs per item from items.json `upgrade_costs`; master stars 6-10),
gemstone slots: "Gemstone Grinder"-style menu to apply/remove gems, unlock slot costs, gem stat
values per rarity/quality via an ItemStatContributor (gem ITEMS are owned by mining — reference
their ids: ROUGH_RUBY_GEM … PERFECT_*). Enrichments, Art of War/Peace.

## pets ★★
Every pet (research 06 + `neu-repo/constants/pets.json`): types, rarities, the exact pet XP
table per rarity, max level 100 (golden dragon 200), stats scaling per level, EVERY ability with
numbers implemented (as StatProviders, DamageModifiers, listeners on Gather/MobKill/FishCaught).
Pet items (stat or ability items), pet candy, pet XP from skill XP (`SkyEvents.SkillXpGain`: full
XP for the pet's own skill type, reduced for others, per research), Taming XP, exp share. Pet items
in inventory are right-click-to-learn → stored in the Pets menu; `/pets` menu (summon/despawn,
convert to item, auto-pet rules optional), a floating pet that follows the player (ItemDisplay
skull bobbing at the shoulder with a name tag "&8[Lvl 23] &6Bee"), level-up message. Kat
(`npc.pet_sitter`): rarity upgrades with coins + materials + time. Provide `Features.Pets`.

## accessories ★★
Every accessory/talisman/ring/artifact/relic (research 06 appendix: 423): stats or effects
implemented, families (only the highest tier of a family counts), Accessory Bag (`/accessories`,
slots unlocked per Redstone collection + SkyBlock level per research), Magical Power per rarity,
power stones (learn by consuming; select in the Thaumaturgy menu), the MP → stats formula, tuning
points. Equipment slots too: provide `Features.Equipment` — `/equipment` menu with necklace,
cloak, belt, gloves slots + their items (owned here). Recombobulated accessories bump MP. Provide
`Features.Accessories`. Active effects like Speed Talisman, Zombie Talisman (-5% from zombies),
Farming Talisman (speed in farm zones) are listeners/modifiers keyed on zone/island.

## armor ★★
Every armor set not owned by slayer/dungeons/crimson (research 06: 325 sets). Generate item JSON
from data (stats, colours, skulls, requirements), then hand-write the FULL SET BONUSES as code
(StatProviders checking `Sky.items().fullSet(p)`, DamageModifiers, set ticks): Young/Strong/
Unstable/Superior/Old/Wise/Protector/Holy dragon, Ender (x2 in the End), Lapis (+XP), Miner's,
Hardened Diamond, Golem, Mushroom (night), Farm Suit/Fermento chain, Cactus, Leaflet, Rotten,
Skeleton grunt, Spooky, Emerald, Pumpkin, Angler, Sponge, Glacite, Divan's, Sorrow, Heavy, Super
Heavy, Bat person, Crystal, Perfect, Speedster, Snow suit, Frozen blaze, Tarantula? (no — slayer),
Growth, Elegant tux, Shark scale, Thunder, Magma lord, Diver's... Recipes for every craftable one
(from neu-repo) with the right collection unlocks. Armor stats show in lore via core.

## weapons ★★
Every sword/longsword/bow/wand/fishing weapon not owned by slayer/dungeons (research 06: 219).
Item JSON generated from data; abilities written as code with the exact numbers: Aspect of the End
teleport (8 blocks, +speed), Aspect of the Dragons breath, Rogue Sword speed, Ember Rod fireballs,
Spirit Sceptre bats, Midas staff, Jerry-chine, Ice spray, Leaping sword, Silk-edge, Ink wand,
Pooch sword (wolf damage), Shaman sword, Emerald blade (coins), Raider axe, Bows: Runaan's
triple shot, Hurricane, Explosive bow, Magma bow, Mosquito, Terminator-equivalent (instant 3
arrows, no draw), Juju; arrows (types + quiver consumption via `Features.Sacks`/storage? — arrows
are stored in the quiver owned by storage; you implement arrow effects), Grappling Hook, Travel
items not owned elsewhere. Recipes from neu-repo with unlocks. Shortbow vs normal bow logic.

## alchemy ★
Brewing: right-click a brewing stand → genre brewing menu (3 bottle free slots, ingredient,
modifier slots) with brew time; every potion of research 08 with levels/durations; modifiers
(Enchanted Sugar/Redstone/Glowstone tiers), splash; drinking applies effects as stats (provide
`Features.Potions`, a StatProvider "potions"), Potion Bag, God Potion (all effects), effect timers
persist through relog; Alchemy XP; `/effects` menu (active effects, toggle off). Bartender potion
shop goes in shops (sells your ids).

## storage ★★
Provide `Features.Storage` and `Features.Sacks`. Ender Chest (multiple pages, unlocked per research),
Backpacks (small→jumbo; items + storage pages), `/storage` menu (the genre's storage overview:
ender chest pages top, backpack slots bottom), Wardrobe (`/wardrobe`: 9 armor sets per page, 2
pages, swap instantly, gated by SkyBlock level instead of paid ranks), Personal Vault (bank-tier
gated), Sacks: every sack type (agronomy, mining, combat, foraging, fishing, dungeon, ...) with
sizes; picked-up items auto-go into owned sacks (listen to `SkyEvents.ItemGain` and cancel when
stored), `/sacks` menu with take-out, Sack of Sacks. Quiver (arrows; weapons module reads it via
Sacks.count/remove on ids starting ARROW?), Potion Bag storage for alchemy (`/potionbag` – alchemy
stores via your Storage? NO: alchemy owns the potion bag itself). All contents persist; anti-dupe
(no duplicate uuids across pages).

## menus ★★★
The SkyBlock Menu (Nether Star, `/sbmenu`) EXACTLY in the genre's slot layout (research 08 has it
slot by slot, renamed "Stats & Equipment" for the profile button): Your SkyBlock Profile / Stats,
Skills, Collections, Recipe Book, SkyBlock Leveling, Quest Log/Objectives, Calendar and Events,
Storage, Active Effects, Pets, Crafting Table, Wardrobe, Personal Bank, Fast Travel, Profile
Management, Settings, Booster Cookie/bits, Museum, Bestiary, Sacks, Accessory bag, Potion bag,
Quiver… each icon opening the owning module via its Features interface (null-safe: "&cComing
soon" if absent). Provide `Features.SkyMenu`. Plus:
- Profile/Stats viewer (`/pv [player]`, also click a player while sneaking): armor, equipment, pet,
  every stat category with its breakdown tooltip (`Sky.stats().breakdown`), skills summary,
  slayer/catacombs summaries via Features.
- Fast Travel menu (`/warp` with no args too): every registered warp with unlock state, icons per
  island, "&eClick to warp!".
- Calendar and Events menu (`/calendar`): current date, every upcoming CalendarEvent with time
  until, current mayor via Features.Mayors.
- Settings menu (`/settings`): per-player toggles (sounds, chat filters, sidebar sections, pet
  visibility, damage indicators…) persisted; expose a tiny API? No — store in member data "settings"
  with documented keys others can READ via `Sky.profiles().member(p,"settings",...)`; list keys in hand-off.
- Quest Log menu showing Objectives.
- Tab list widgets (skills, stats, pet, profile) via `Sky.sidebar().tabWidget`.
This module is the face of the game: every icon has a real item, lore in the genre's voice, click
hints, and Go Back arrows everywhere.

## bank ★★
Bank NPC at hub `npc.banker`/`npc.banker_2`, `/bank` (via menu; genre requires being at a
banker or having the Personal Bank item), Deposit (whole purse / half / 20% / custom amount via
inputNumber), Withdraw, account tiers (Starter → … per research 05) with upgrade costs (coins +
Gold collection gates), interest every 31 in-game days (`Sky.calendar()`), co-op shared balance +
personal bank, transaction history (last 10) from your own table, Personal Bank item (accessible
anywhere for a cooldown). Also `/trade <player>`: the genre's secure trade window — both sides
offer items + coins in a 6-row menu, both must "Accept", a 3s countdown resets on any change, both
inventories checked for space, executed as one DB transaction (items via profile-safe swap; coins
via economy) — public-server critical: no dupes, no partial trades, cancel on move/damage/quit,
trade request expiry, distance limit, logs. Booster Cookie equivalent (our name) sold at an NPC for
coins: consuming grants bits per hour + cookie buffs (bank anywhere, +magic find etc. per research)
for 4 days; the bits shop (Community Shop at `npc.community_shop` — coordinate: shops module owns
the NPC, YOU provide the cookie item + its buff + the bits accrual and expose bits spending as a
menu the shops module opens? Simpler: you own both the cookie and the Community Shop menu; register
the NPC yourself at `npc.community_shop`). No real money anywhere.

## bazaar ★★★
The Bazaar: `/bz` and hub `npc.bazaar`. Categories (Farming, Mining, Combat, Woods & Fishes,
Oddities) → product groups → product page: Buy Instantly, Sell Instantly, Create Buy Order, Create
Sell Offer, with top-of-book prices and the order book (top 7 each side), price history, "Manage
Orders" (claim filled, cancel → refund/escrow back). Order-book engine per research 05: price-time
priority, partial fills, tax (dial, default 1.25% on sell side), order limits, instant buy/sell walk
the book, spread rules (new orders must be within 5%? per research), claimables persisted. All in DB
transactions; items escrowed when an offer is created (removed from inventory into the order),
coins escrowed for buy orders. NPC MARKET MAKERS so it works with 2 players: each product has a
reference price (seed from research `bazaar.json` sample, scaled by a dial) and bots keep a few
orders on both sides around a slowly drifting price, refreshing every minute — they must never be
exploitable (bot sell price ≥ bot buy price + spread; bots never pay more than NPC sell × some
multiple...). Provide `Features.Bazaar` (instantBuy/instantSell lookups are used by other modules for
value display). Which items are bazaar items: tag "bazaar" or a generated list from the research
sample (~2,000 product ids; only those that exist in Sky.items()). Sell inventory/sacks to bazaar
button. Rate limits.

## auction ★★★
The Auction House: `/ah` and hub `npc.auction_master` (+ agents). Browse (categories: Weapons,
Armor, Accessories, Consumables, Blocks, Tools & Misc), search (text), sort (highest bid, lowest
price, ending soon, most bids), filter rarity/BIN-only; item page (bid with min increment per
research, BIN buy with confirm); Create Auction (free slot, starting bid / BIN price via
inputNumber, duration choices with fees per research 05, listing fee %), "View Bids", "Manage
Auctions" (claim coins/items, cancel without bids), max active auctions (dial). Escrow: the item
leaves the seller's inventory into the DB the moment the auction is created; coins leave the bidder
when bidding (refunded when outbid, to purse if online, else depositOffline); claim tax per research;
everything in transactions; auctions survive restarts; expiry sweeper. Provide `Features.Auctions`
(lowestBin). Items tagged "no_ah" or soulbound are refused. Unique item uuids preserved (no dupes).

## shops ★★
Every NPC merchant (research 03 + `neu-repo` NPC shop data — 1,097 entries): Farm/Mine/Lumber/Fish
Merchants, Weaponsmith, Armorsmith, Adventurer, Builder (blocks), Librarian (books/exp bottles),
Bartender (alchemy's potions), Pet shop? and every merchant on other islands (register them at
anchors on those islands — coordinate names via the island builders' hand-offs; use
`<island>:npc.<name>` anchors and fall back gracefully). Genre shop menu: items with "&7Cost &6X
coins" (and item costs), click buys 1, shift buys a stack, "Sell" by clicking items in your own
inventory or putting them in the sell slot (sell price = def.npcSell; must be > 0), BUYBACK slot
(last 10 sold, rebuy at the same price), stock limits where the genre has them. Also the generic
`/sell`? no — keep NPC-only. Hub NPC dialogue lines for flavour. Hub-wide non-shop NPCs that only
talk (lore NPCs) go here too. Selling to NPCs fires CoinsChange via economy (reason "npc-sell:<id>").

## mayors ★★
Elections and mayors (research 05): candidates rotate, each with 1-4 perks rolled from their perk
list; a full election cycle on `Sky.calendar()` (an in-game year: election opens/closes on the
genre's dates), voting menu at hub `npc.election`/area `election_room`, results, minister; ALL
perks implemented or wired: expose `Features.Mayors.perkActive("<perk_id>")` and document every
perk id so owning modules read them (e.g. "marina.fishing_festival" → fishing; "cole.mining_fiesta"
→ mining; "aatrox.slayer_xp" → slayer; "diana.mythological" → you implement the Mythological
Ritual event yourself; "paul.ezpz" → dungeons; "jerry" special mayors…). Calendar events YOU own:
Spooky Festival (spooky mobs + candy shop), New Year Celebration (cake), Traveling Zoo (pet
shop), Season of Jerry + Jerry's Workshop island (build `jerrys_workshop` generator: snowy island,
Gifts, the Jerry siege waves per research), Dark Auction (`npc.dark_auction`, hourly, 8 rounds,
bid in coins). Fishing Festival / Mining Fiesta / Jacob's contests belong to fishing/mining/farming
(they listen to your perk ids). Sidebar section during events.

## mining ★★★
The deepest system. Read research 07 Mining completely.
- Custom block breaking: in islands gold_mine, deep_caverns, dwarven_mines, crystal_hollows,
  glacite_tunnels (+ hub coal_mine area): BLOCK_BREAK_SPEED attribute 0 so vanilla can't break,
  your own break progress from MINING_SPEED and block strength (`ticks = round(strength*30/speed)`,
  4-tick floor, insta-mine thresholds), breaking power gate, block-break animation via
  `Player#sendBlockDamage`, drops with Mining Fortune/Ore/Block/Gemstone fortune, Pristine (gem
  quality upgrade), Mining Spread, ore regeneration (bedrock/placeholder then back after N seconds,
  per area), Mining XP, `SkyEvents.Gather`, through `Sky.loot().give(..., MINING)`.
- Powders (Mithril, Gemstone, Glacite) from mining, `Features.Mining`; Heart of the Mountain:
  `/hotm` tree menu with EVERY perk, levels, costs, tokens, Peak of the Mountain, abilities
  (Mining Speed Boost, Pickobulus, Maniac Miner...) bound to pickaxes/drills; HOTM levels from
  HOTM XP. Requirement resolver `hotm`.
- Commissions: `npc.commissions` in dwarven_mines, 2-4 active, the full list per research, progress
  via Gather/MobKill in areas, rewards (HOTM XP, powder, Mithril), milestones; King Yolkar-alike
  daily reward; "Royal Pigeon" equivalent optional.
- The Forge (`npc.forge`): forge slots, timed FORGE recipes (all from neu-repo forge data — 120),
  claiming, Quick Forge.
- Gemstones: all gem types rough→perfect (items owned here), Crystal Hollows crystals + Nucleus
  placement, gemstone mining.
- Pickaxes and Drills (items, parts: engines/fuel tanks/upgrade modules, fuel consumption, drill
  menu at the Drill Mechanic), Titanium, Mithril, Glacite tunnels mechanics (cold), Mineshafts
  (instanced? optional), Fossils/excavator if time, Mining events (2x powder, Goblin Raid, Raffle,
  Better Together, Gone with the Wind, Mithril Gourmand) on a timer per research, Mining Fiesta
  (mayor perk). Goblin/treasure mobs belong to the mines/hollows world modules; you own systems.
- Sidebar section in mining islands: "&2᠅ Mithril: 12,345" / "&d᠅ Gemstone: 345", commissions.
- Mining speed boost etc. as pickaxe abilities through Sky.abilities().
Admin: `/sb mining powder|hotm|commission|regen|event ...`.

## farming ★★★
Read research 08 Farming + research 07 cross refs. Crops (wheat, carrot, potato, pumpkin, melon,
sugar cane, cactus, cocoa, nether wart, mushrooms + the 2025 crops Sunflower/Moonflower/Wild Rose
if in research) broken in farming zones of hub `farm`, barn, mushroom_desert and the garden: a
BreakHandler that drops with FARMING_FORTUNE (crop-specific fortune too), auto-replants (crop
returns to age 0 then grows on a timer — no random ticks in our worlds, you drive growth), Farming
XP, `SkyEvents.Gather(FARMING)`, `Sky.loot().give(FARMING)`. Farming tools: hoes (Theoretical/
Euclid's/Gauss/Pythagorean/Turing/Newton/... equivalents, counters & Cultivating levels per
research — owned here), dicers (melon/pumpkin), Fungi cutter, Cactus knife, Coco chopper. Jacob's
Farming Contests: every 3 in-game days, 3 crops, 20 minutes, brackets (bronze→diamond) by
percentile of participants (with NPC "ghost" competitors so it works with 2 players), medals and
the Contest Host shop (`npc.contest_host`) with tickets/medals. Build the FARMING ISLANDS:
generators `barn` (The Barn: wheat/carrot/potato/pumpkin/melon/sugar cane fields with fences, barn
building, windmill) and `mushroom_desert` (desert, mushroom fields, cacti, cocoa on jungle logs,
nether wart), their mobs (cows/pigs/chickens/sheep/rabbits/mushroom cows, Cactus? — per research
03), NPCs, warps, portals back to hub, ~10 fairy soul anchors each (`fairy_soul.N`). Anita/Jacob
shop. Sidebar section during contests.

## garden ★★★
Read research 08 Garden completely. The Garden is an INSTANCED island (one per profile, island id
`garden`, `instanced=true`): generator with the barn/greenhouse and a 5×5 grid of 96×96 plots
(centre plot unlocked; 24 plots with compost costs to unlock, cleaning plots), BUILD inside owned
plots (crops planted by the player). Garden levels (I–XV), Garden XP, crop milestones (46 tiers
per crop), crop upgrades (copper), Visitors (spawn interval, offers with requested items, rewards:
copper, garden XP, items; accept/refuse; visitor rarity tiers; the full visitor roster renamed),
Composter (organic matter + fuel → compost, upgrades), SkyMart (copper shop), Pests (all 15:
spawn while farming, vacuum to kill, pest drops, sprays, the farming-fortune penalty), the
Greenhouse (2025 mechanics per research), Desk menu (`/garden` + desk in the barn): plots, crop
milestones, visitors log, configure plots (presets). Provide `Features.Garden`, requirement
resolver `garden`. Gardener NPC in the hub `npc.gardener` warps you there. Reuse farming's crop
breaking via the same rules: you register YOUR OWN BreakHandler for the garden world with the
same mechanics (copy the logic; no cross-module import), plus pests/visitor hooks.

## fishing ★★★
Read research 07 Fishing completely. Custom fishing: intercept PlayerFishEvent, control bite time
with FISHING_SPEED, Sea Creature Chance roll, location-based sea creature pools (water: hub pond,
park, spider's den, crimson lava, backwater bayou, jerry's workshop winter, hollows crystal/
precursor pools, the end?), treasure catches (TREASURE_CHANCE), double hook, catch items from the
fishing loot tables, Fishing XP, `SkyEvents.FishCaught`. Sea creatures as MobDefs (all of them,
with HP/damage/XP/drops; flag `sea_creature`; spawned at the bobber, owned by the fisher), rare
creature announcements. Lava fishing in crimson_isle (lava rods required). Trophy Fishing (all
trophy fish, bronze→diamond tiers, requirements, Odger trophy NPC equivalent). Fishing rods + rod
parts (hooks/lines/sinkers) + baits (consumed from inventory/sacks). Fishing Festival (mayor perk
"marina…"): sharks. Hotspots (particles + boosted stats). Fishing gear sets belong to armor —
reference their ids. Build `backwater_bayou` island (swampy fishing hub with a dock, NPC fishing
merchant/rod-part NPC, portal to hub) and register its warp. Hub `npc.fishing_guide`. Admin
`/sb fishing seacreature <id>` etc.

## foraging ★★
Logs in foraging zones (hub `forest`, the Park, Galatea-equivalent if built): BreakHandler with
FORAGING_FORTUNE, sweep/treecapitator logic (break the connected tree up to N logs with cooldown),
tree regeneration (logs restore after N seconds; leaves never decay), Foraging XP,
Gather(FORAGING). Axes (Jungle Axe, Treecapitator, Sweet Axe, ...) owned here. Build THE PARK
generator (`park`): Birch Park, Spruce Woods, Dark Thicket, Savanna Woodland, Jungle Island,
Howling Cave (wolves, Soul Ward?) — distinct biomes with dense, varied, harvestable trees, paths,
NPCs (Charlie/Melancholic Viking/Gustave-alikes renamed), mobs (Howling Spirit, Pack Spirit, Soul of
the Alpha, wolves), ~20 fairy soul anchors, portal + warp. Heart of the Forest / Galatea systems from
research 07 if the time allows (mark in hand-off).

## slayer ★★★
Read research 04 Slayers completely. The slayer master at hub `npc.slayer_master` (tavern):
"Slayer" menu → choose boss + tier → pay coins → quest starts (sidebar: "Slayer Quest / <Boss>
<tier> / (123/1,000) Combat XP"); combat XP from matching mobs (`MobDef.slayer` category) fills
the bar; boss spawns owned by the player with its full mechanics per tier; kill → slayer XP,
loot table (weighted, with RNG meter progress and selectable RNG drop), Maddox-batphone-equivalent
to start quests remotely after a level, quest failure (death / timeout 3 min?), slayer levels 1-9
with the exact XP table and level rewards (stats via StatProvider, recipe unlocks via
`slayer:<type>:<lvl>` resolver, which you register). ALL SIX bosses, every tier (T1-T5 where they
exist), every ability: Revenant Horror (+ Atoned Horror T5) → our name; Tarantula Broodfather;
Sven Packmaster (pups, true damage); Voidgloom Seraph (hits shield, beacons, Yang glyphs, heads);
Inferno Demonlord (attunement daggers, demons, pillars); Riftstalker Bloodfiend (Rift — only if the
rift module exists; otherwise implement with a fallback location and mark it). Each boss is a
MobDef + MobBehaviour state machine (phases, immunity, minions, true damage, heal). Slayer drops
(items owned here) and slayer gear (weapons/armor crafted from drops: Revenant armor/Reaper
falchion/Scythe/Tarantula armor/Recluse fang/Shaman/Pooch?/Final destination armor/... per
research). Aatrox mayor perks via `Features.Mayors`. Provide `Features.Slayers`. Admin:
`/sb slayer spawn <type> <tier>`, `/sb slayer xp <player> <type> <n>`, `/sb slayer rng ...`.
Sidebar section priority 200. The fights must FEEL good: telegraphs (particles/sounds) before big
hits, boss bar with HP, clear chat messages.

## minions ★★★
Read research 02 Minions completely. Every minion type (≈60) and tier I–XII (recipes from data,
unlocked via collections), placed only on the private island (BUILD zone; a minion is an armor
stand with a small player-head, generated armour colours, holding the tool), the 5×5 work area
(blocks placed/broken around it as it works: cobblestone generator, crops, trees, mobs spawning for
combat minions — killing them gives drops), action time per tier, storage per tier, fuel (%,
duration), upgrades (Compactor, Super Compactor 3000, Diamond Spreading, Auto Smelter, Budget/
Enchanted Hopper selling to NPC price, Flycatcher, Minion Expander...), storage chests attached,
skins optional. Collect: right-click → minion menu (storage grid, collect all, pickup, upgrade,
fuel/upgrade free slots). Minion slots: unique minions crafted → more slots (table per research),
plus community-shop-free slots. OFFLINE production: when the island is unloaded, compute what was
produced since last seen (bounded by storage) the next time it loads. Production counts toward
collections with source MINION. Performance: don't animate minions on unloaded chunks; batch.
Provide nothing global; read `Features.PrivateIsland` for the island location.

## island ★★
Provide `Features.PrivateIsland`. The Private Island: an INSTANCED island (`private_island`,
`instanced=true`): generator = the genre's starter island (small grass island with a few trees,
a stone/cobble area, a pond, the starter house with chests, a minion slot area marked, `spawn`,
`npc.island_helper` (our Jerry-equivalent), a portal to the hub). `/is` / `/island` → your island
(ensureInstance), `/visit <player>` (guests can't build), island settings menu (guest permissions,
visiting on/off, PvP off), co-op members all share it (use profileId as the instance key),
island expansion tiers optional. canBuild: members only, within the island bounds + generous build
margin. New players: first join → island → objective chain starts (progression owns it). Hub
portal (`portal.private_island` on the hub) → warp "home". Island helper NPC sells/gives starter
kit (Rookie hoe/pickaxe/axe equivalents? reference ids) and explains basics.

## hub ★★★
Build the HUB generator (`hub`, ~600×600, `IslandGenerator`, pure). It is the first thing every
player sees for hours — make it a real town. Read research 03 Hub (including the 2026 skill
districts rework) and design OUR version: a central Village (cobble/wood houses with real
interiors, market square with the Bazaar building, Bank, Auction House, Blacksmith, Library with
an enchanting table, Tavern for the slayer master, Alchemist, Pet sitter's house, Community center,
Election hall, Museum, Fairy's pond, Builder's shop, Bartender), and around it the districts with
terrain: the Farm (fields, in a `farm` area + zone), the Mountain (tall, with a path to the top and
the Coal Mine entrance + `coal_mine` area of ores), Forest (`forest`, oak/birch), Wilderness and
Ruins (`ruins`, `wilderness` — wolves), Graveyard (`graveyard` with the `crypt` underground),
Colosseum, Wizard tower, the hub pond (`pond`, fishing), and a PORTAL PLAZA with every
`portal.<warpId>` listed in CONTRACT §4 (framed, labelled by blocks/banners). EVERY anchor in the
CONTRACT §4 hub table must exist and be walkable from `spawn`. Zones for every district (sidebar
names), SAFE zones in the village. ~40 `fairy_soul.N` anchors hidden around the hub. Hub mobs
(MobDefs + SpawnRules): graveyard zombies & zombie villagers (night), crypt ghouls (slayer-
relevant, flagged slayer zombie), old wolves & ruins wolves, coal mine? none, plus flavour animals.
Register the `hub` island + warp "hub". Verify with preview.sh again and again — LOOK at the
PNGs, fix anything floating, cramped, flat or ugly, and get walkability to zero unreachable anchors.
This is a big job: plan the district map first (write it in your hand-off), reserve a box per
building, then build.

## mines ★★
Build three islands (generators + mobs + NPC placements, mining SYSTEMS are the mining module's):
`gold_mine` (gold-ore cave town, Gold Forger at `npc.gold_forger`, lapis/iron/gold ore areas,
zombie miners), `deep_caverns` (the descending layers: Gunpowder Mines, Lapis Quarry, Pigmen's Den,
Slimehill, Diamond Reserve, Obsidian Sanctuary — each a zone with its ores and mobs (Lapis zombies,
Redstone pigmen, Emerald slimes, Diamond zombies/skeletons, Sneaky Creepers) and a descent path/
minecart), `dwarven_mines` (big underground city: Royal Palace with `npc.mine_king`, Forge with
`npc.forge`, `npc.commissions` board, `npc.hotm`, Ramparts Quarry, Upper Mines, Lava Springs, Cliffside
Veins, Royal Mines, Far Reserve, Goblin Burrows, Great Ice Wall, Divan's Gateway; mithril (wool/
prismarine/cyan clay "mithril" blocks) and titanium (polished diorite) deposits in mining areas
with `area`s named `ores.<zone>` so the mining module knows where ore regen applies; mobs: goblins,
goblin weakling/knifethrower/flamethrower, ice walkers, ghosts (in the Mist), Treasure hoarder,
Star sentries; `portal.crystal_hollows`, `portal.glacite_tunnels`). Anchors in CONTRACT §4. Fairy
soul anchors. Make them feel like the genre's mines: verticality, carved stone, minecart tracks,
lamps. preview.sh each.

## hollows ★★
Build `crystal_hollows` (procedural, ~512×512×~200: a huge cave-world in 5 zones — Jungle, Mithril
Deposits, Goblin Holdout, Precursor Remnants, Magma Fields — around the Nucleus, with gemstone
veins (stained glass/panes) placed by zone type, structures: Jungle Temple, Mines of Divan,
Goblin Queen's Den, Lost Precursor City, Khazad-dûm-alike (Bal's lair), Fairy Grotto, crystal
nucleus with five crystal sockets, lava/water lakes), its mobs (Automatons, Sludges, Yogs, Goblins,
Team Treasurite, Butterflies?, Bal boss, Thyst, Kalhuiki?), `npc.hollows_merchant`, and
`glacite_tunnels` (icy tunnels below the dwarven mines with glacite + new gem types, Glacite
walkers, Base camp with NPCs, `spawn`, portal back). The hollows in the reference regenerate per
lobby; ours is generated once from a seed (an admin rebuild with a new seed = "new lobby").
Gem blocks must be placed where the mining module's BreakHandler will treat them as gems
(stained glass + panes by colour = gem type — document the mapping in your hand-off).

## combatzones ★★
Build `spiders_den` (a mountain of spider webs and caves, Arachne's sanctuary/altar, gravel, slime
hill, the Spider Mound; mobs: Dasher spiders, Splitter spiders, Weavers, Voracious spiders, Silverfish,
Cave spiders, Broodmother (world boss), Arachne (summoned at the altar with Arachne's Calling/
Keeper fragments), Rain slimes during rain?) and `the_end` (End island with the Dragon's Nest
altar (8 Summoning Eye sockets), Void Sepulture, Zealot Bruiser Hideout, Obsidian Sanctuary; mobs:
Endermen, Zealots (+ Special Zealot dropping Summoning Eye 1/420), Watchers, Obsidian Defenders,
Endermites, Voidling…), the DRAGON FIGHT (place eyes → dragon type roll: Protector, Old, Wise,
Unstable, Young, Strong, Superior; dragon HP/abilities; damage ranking; loot by placement with
dragon fragments + armor pieces chance + the pet). Drops owned here (fragments, Spider's Den
materials). Warps (`spiders_den` free? requirement per research), portals, fairy souls.

## crimson ★★
Build `crimson_isle` (nether-like island: Stronghold/Barbarian side (Dragontail) vs Mage side
(Scarleton), Blazing Volcano, Burning Desert, Mystic Marsh, Crimson Fields, Smoldering Tomb,
Forgotten Skull, Magma Chamber (Kuudra entrance — wave 2), Dojo, lava fishing spots; environment
NETHER-look via IslandDef.environment). Factions: choose Mage or Barbarian, reputation, daily
quests (kill X, fish Y, dojo, mini-boss), faction ranks + rewards; Dojo trials (7 challenges);
mini-bosses (Bladesoul, Mage Outlaw, Barbarian Duke X, Ashfang, Magma Boss) on respawn timers per
research; mobs (blazes, magma cubes, Flaming spiders, Wither skeletons, Krondor?...), crimson
armor sets (Crimson/Aurora/Terror/Fervor/Hollow — owned here), attribute shards ONLY if research
says current. Requirement resolver `faction`. Warp gated per research (Combat 24).

---

## wave 2 (built after wave 1 is integrated): dungeons, kuudra, rift

Wave 1 is live: 32 modules, 323 self-tests passing, every island visited in a real client. Read
`docs/INTEGRATION.md` (both rounds) and the hand-offs of the modules you touch before starting —
especially armor (it registered four dungeon armour sets as wave-1 copies you now OWN and should
replace/extend), weapons, reforges (stars/essence), collections (boss collections are guessed from
mob ids — publish your real boss mob ids and add a `Features.Collections`-friendly path, see their
hand-off), mayors (perk ids for dungeons), menus (SkyBlock Menu icons waiting for you), and hub
(`portal.dungeon_hub` / `npc.dungeon_guide` already exist).

## dungeons ★★★ (two agents in sequence: A = framework, B = content)
Read research 04 Dungeons completely. Provide `Features.Dungeons`; requirement resolvers
`catacombs` and `class`.
**A — framework:** the `dungeon_hub` island (generator: a gothic town square with Mort-equivalent
at the gate, the party finder board, a class selection altar, a shop, the catacombs entrance,
`portal.hub`; register warp `dungeon_hub`), party system (`/party invite|accept|leave|kick|list|
warp`, 1-5 players, works for solo), Party Finder menu, Catacombs entrance menu (Entrance + F1-F7,
Master Mode M1-M7 gated per research), classes (Healer, Mage, Berserk, Archer, Tank: passives,
the class ability, ultimate, class XP, class levels 1-50), Catacombs levels with the XP table and
the stat boost dungeon items get from level, dungeon item scaling (`ItemDef.dungeon` +
stars), the run itself: an INSTANCED island per run (`catacombs`, instanced, key = run id) whose
layout is GENERATED PER RUN from room tiles (core's instances share one generator; your generator
must read the run's seed/floor from a static registry keyed by instance key before
`ensureInstance`, or place rooms yourself into the instance world — choose and document) on a 6×6
grid (smaller for early floors): Entrance, Fairy room, Blood room (watcher + blood mobs wave),
trap/puzzle/mini-boss rooms, normal rooms in 1×1/1×2/1×3/L/2×2 shapes, wither doors + blood door
and their keys, secrets (chests/items/bats/levers) per room, the map item (a filled map that
reveals as you explore), death/revive (ghost mode, Healer revive / Spirit Leap), the Watcher, the
score system (Skill, Exploration, Speed, Bonus → S+), the run timer and sidebar section, ending →
boss room entrance. Puzzles: implement ALL listed in research (Three Weirdos, Water Board,
Creeper Beams, Teleport Maze, Tic Tac Toe, Ice Path, Ice Fill, Higher or Lower/Blaze, Boulder,
Quiz/Trivia, Bomb Defuse? (if current)) — each a self-contained room tile with its solve logic.
Dungeon mobs (zombies/skeletons/lost adventurers/shadow assassins/fels/...) scaled by floor.
**B — content:** every boss floor F1-F7 (+ M1-M7 scaling) with its full fight per research
(phases, mechanics, immunities, terminals/devices on F7 — simplified but real), dungeon loot:
end-of-run chests Wood→Bedrock with costs and the reference loot tables (renamed items: dungeon
weapons/armor/accessories/pets/books owned by YOU — Bonzo/Scarf/Professor/Thorn/Livid/Sadan/
Necron-equivalent sets and weapons with their abilities, essence types, Kismet-like rerolls,
dungeon RNG meter), essence + stars integration with reforges (their upgrade menu reads
items.json `upgrade_costs`; make sure your items carry the costs), Mort shop, dungeon collections
via collections. Make the boss fights FEEL like events (titles, boss bars, telegraphs).

## kuudra ★★
Read research 04 Kuudra. Crimson Isle's Magma Chamber entrance (coordinate with crimson's hand-off
anchors; if missing, add a `kuudra` instanced island reached by the crimson NPC/portal), tiers
Basic → Infernal with key costs (faction rep + materials), the 4-phase fight: supply recovery and
delivery, building the ballista with fuel, stun/eat phase, DPS phase (+ the Infernal lair phase),
Kuudra minions, party up to 4 (reuse a party only via `Sky.commands().run` of `/party` if dungeons
exposes it, else solo-capable), paid chest + free chest loot tables (Kuudra armor upgrades, crimson
essence, attribute shards ONLY if research says current), Kuudra collection. Crimson armor sets
are owned by crimson — reference them.

## rift ★★
Read research 08 Rift. The Rift dimension as island `rift` (warped, eerie: Wyld Woods, Black
Lagoon, West Village, Dreadfarm, Village Plaza, Mirrorverse, Colosseum, Stillgore Château, Living
Cave, Mountaintop) entered through a portal in the hub (wizard tower — add your own entry: a
`portal.rift` area/NPC near hub `npc.dungeon_guide`? no: register an NPC "Rift Guide" at a hub
anchor named in hub's hand-off if one exists, otherwise use `Sky.worlds().anchor("hub","spawn")`
offset and say so). The Rift's own rules: separate inventory? (the reference uses a separate
inventory and stats — implement Rift Time as a draining timer stat that kicks you out at 0, Motes
as the Rift currency, Rift-only stats), 52 Enigma Souls (anchors), 8 timecharms, the Rift NPC
quests/trades per research, the vampire slayer (Riftstalker) if slayer left a hook — read slayer's
hand-off. Mark depth honestly in the hand-off; breadth of zones + time mechanic + motes + souls +
a handful of signature puzzles is the bar.
