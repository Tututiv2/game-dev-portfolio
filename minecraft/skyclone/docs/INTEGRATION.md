# SkyClone — integration log

One section per integrator round. Each round: what was fixed, what still fails, self-test totals,
and the screenshots taken in the real client.

---

## Round 1 — 2026-10-02

### Result

| Gate | Result |
|---|---|
| `bash tools/check.sh all` | **CHECK OK**, 717 files (it compiled clean on arrival; no cross-module mismatches) |
| `gradlew build` | **415 unit tests, 0 failures** (one test went red mid-round from an integrator price change and was fixed at the data, not quarantined) |
| Boot | **32 / 32 modules enabled, 0 FAILED**, 3 not present (dungeons, kuudra, rift: wave 2) |
| Island builds | All 18 islands built. Park and Moonmarsh **crashed on the first boot** (fixed, below); the slowest build is Crystal Caverns, 27.7M blocks in 28 s |
| `/sb selftest` | first run **299 passed, 22 failed** → final **318 passed, 3 failed** |
| Real client | joined (MyFire launcher, dev account `IntegBot`, `online-mode=false` for the session only), every SkyBlock Menu icon clicked, 5 NPCs talked to, a mob killed, a block mined |
| Clean-up | `online-mode=true` restored, `IntegBot` de-opped and kicked, client closed, server **stopped**. `IntegBot`'s test profile (coins, Combat 20, Superior Dragon armour) is still in `skyclone.db`; nothing reads it |

### Fixed this round

| # | Where | Problem | Fix |
|---|---|---|---|
| 1 | foraging `ParkGenerator`, `MarshGenerator` | **The Park and the Moonmarsh never built.** The game runs a generator twice (layout ahead of the build, then the build). Both kept a `Reservations` and tree/keep lists in fields, so on the second run every `reserve()` collided with the first run's boxes, no house was built, and `lodge.doorStep` was null → NPE. Preview runs once, which is why `preview.sh` was green | Reset all per-run state at the top of `generate()`. Other generators checked: Barn/Desert already reset, Crimson/Hollows/Hub use a per-run context object |
| 2 | combatzones `EndGenerator` | `cracked_end_stone_bricks` is not a block (placed as stone; `world.block-states-valid`) | Removed from the three palettes; generator version → 2 so The End rebuilds |
| 3 | mining data + generator | `GEMSTONE_GAUNTLET` skipped at load: `skull` was the reference's `{value, signature}` object | `gen_mining.py` `ref_skin()` takes `.value`; data patched |
| 4 | mining forge data + generator | Beacon II–V forge unlocks asked for Mithril collection **X**, but the collection maxes at IX (NEU is newer than `collections.json`) → never unlockable | Generator clamps to the collection's max tier; data patched (`collection:MITHRIL_ORE:9`) |
| 5 | pets data + generator | `LOG-3:32` / `LOG-1:512` (NEU's damage-value form) never resolved | `colon()` handles `ID-N:amount`; data → `LOG:3:32` |
| 6 | farming data + generator | Fungi Cutter unlock `collection:RED_MUSHROOM:5` (crafting cannot categorise it; the reference collection is `MUSHROOM_COLLECTION`) | `COLL_KEY` map in `gen_farming.py`; data patched |
| 7 | farming self-test | `farming.island-anchors` looked for `portal.*` as ANCHORS; portals are AREAS (it already checked them as areas) | Removed from the anchor list |
| 8 | 19 missing ids | Recipes/trades/shops referenced ids nobody registered: `WOOD_SINGULARITY`, `FRESHLY_MINTED_COINS`, `AVARICIOUS_CHALICE`, `BLOOD_STAINED/SOAKED_COINS`, `CONDENSED_FERMENTO`, `GLACIAL_FRAGMENT`, `STRETCHING_STICKS`, `X/Y/Z`, `LOTUS_DIAMOND`, `HILT_OF_REVELATIONS`, `NECRON_HANDLE`, `ENCHANTED_BONE_MEAL`, `GEOMETRIC_ODDITY`, `LUSHLILAC`, `RECORD_13`, `LUCKY_CLOVER` | New materials family file `items_integration.json` from `tools/gen/materials/gen_integration.py` (reference rarity/price/skull, our names + lore, `extra.fallbackFor` = the module that should own it). Registered only-if-absent like the rest of materials, so the real owner wins the day it registers one. Renames in `docs/names/materials.md` |
| 9 | materials self-test | `ENCHANTED_BONE_MEAL` is shop/trade-only, not a compression | Def tagged `no_recipe`; `materials.enchanted-have-recipes` honours that tag |
| 10 | materials prices | The Trades menu printed coins: 1 log (2) → 4 leaves (4 × 2); 2 jungle logs (4) → 2 vines (2 × 5) | `SELL_OVERRIDE` in `gen_materials.py`: leaves 0.5, vine 2, mossy cobblestone 3 (= cobble + vine, so the compression test stays green) |
| 11 | core `WorldCore` + hub self-tests | `portal.dungeon_hub` is built ahead of the wave-2 dungeons module → two permanent FAILs | Not an error while nobody provides `Features.Dungeons` |
| 12 | core `NpcServiceImpl` | `/sb npc list` and `ui.npc-alive` looked up `spawnAt` copies (`id#key`, e.g. the island helper on every private island) by the BASE id, so a live copy always read "not spawned" (shops/island Requests) | Both read the entry's own body; the list prints the copy key |
| 13 | core `ui` (new `VanillaNoise`) | Real client: vanilla **"New Recipe(s) Unlocked!" and "Advancement Made!" toasts** popped over the HUD on every pickup | Cancelled at the source; dials `ui.vanilla-recipe-toasts`, `ui.vanilla-advancements` (default off). After the restart, receiving new items showed no toast |
| 14 | core `/warp` | No-arg `/warp` printed a chat list (menus Request 1) | Opens Fast Travel when the menus module registers `fasttravel`, else the chat list |
| 15 | api (additive) | collections Request 1, pets Request 2 | `Features.Collections.add(p, id, amount)` (implemented by collections), `Features.Pets.abilityValue(p, key, token)` (PetService already had it). Both `default` methods, so the no-op proxies still work |

### Still failing (self-test, final run: 318 passed, 3 failed)

| Self-test | Owner | Problem |
|---|---|---|
| `skills.unlock-enchants-exist` | enchanting (or skills) | 34 enchant ids the Enchanting skill table names as unlocks are not registered: woodsplitter, dragon_tracer, pyroclasm, missile, first_impression, drain, flowstate, gravity, bank, refrigerate, vampiric_vitality, hardened_vitality, … Either enchanting adds them, or skills drops unlock lines for enchants this game does not have |
| `crafting.no-coin-loops` | accessories (+ crafting) | 61 recipes sell for more than 2× their inputs, mostly accessory/equipment NPC sell prices: `DELIRIUM_NECKLACE` 4,000 → 52,000, `SCOURGE_CLOAK` 14,000 → 57,000, `SCOVILLE_BELT` 10,000 → 55,000, `EMPEROR_ARTIFACT` 3,600 → 8,100, `RESPIRATION_TALISMAN` 0 → 1,000. A craft-and-sell coin printer on a public server |
| `bazaar.no-craft-arbitrage` | bazaar | The market maker's bid on a craftable sits above its inputs' ask. It moves: `BEJEWELED_HANDLE` on the first boots, `METEOR_SHARD` (359,488 vs 309,432) on the last. The guard needs to clamp the maker's bid to the recipe's input cost, not just report it |

### Real-client smoke test (what was driven, what was seen)

The client was the gate's method: `py -3 -m launcher launch Fabric-26.1.2 --dev-user IntegBot --server localhost:25630 --detach`
from `MinecraftLauncher/`, with the server briefly `online-mode=false`. Input was sent with a
small stdlib `SendInput` driver and screenshots were taken with a screen BitBlt (`PrintWindow`
returns white for the GL window). The window was 1280×720 at GUI scale 3.

- **Join / private island.** The island instance built in 1 s on first join (75,534 blocks). Sidebar, action bar, nether star in slot 9 and the objective all render. Gerald (island helper) answers a right-click with his dialogue and advances the objective to "Break a Log".
- **SkyBlock Menu — every icon clicked (26).** Each opened its own menu or answered in chat: Stats & Equipment, Your Skills, Collections, Recipe Book, SkyBlock Leveling, Quest Log, Calendar and Events, Storage, Your Bags, Pets, Craft Item, Wardrobe ("unlocks at SkyBlock Level 5"), Personal Bank ("only at a Banker in the Hub"), Museum, Bestiary, Active Effects, SkyBlock Guide, Your Island (warps), Bazaar ("Requires SkyBlock Level 7"), Auction House, Fast Travel, Profile Management, Settings, Boon Buff. None threw.
- **Hub.** `/hub` lands on the Village Square. Banker → Bank Account menu; Bazaar agent → Bazaar (Farming) once at level 8; Auction Master → Auction House; Slayer master → three dialogue lines then the Slayer menu.
- **Mob kill.** A Graveyard Zombie killed with a sword paid +1 coin (`mob_kill` in the ledger) and unlocked the Rotten Flesh collection.
- **Mining.** Breaking stone in the hub Coal Mine gave Cobblestone, +1 Mining XP and unlocked the Cobblestone collection.
- **Persistence.** After a server restart the profile came back intact (purse, gear, Combat 20).

Screenshots are in `docs/integration/round1/` (contact sheets `sheet_sb1..5.png` cover every menu icon, `sheet_npc3/4.png` the hub NPCs, `sheet_helper.png` Gerald, `35_hit.png` the kill, `sheet_mine.png` mining).

### Observed, not fixed (judgement calls for the owner or a later round)

1. **The Graveyard at night is a death trap for a new player.** 24 zombies (10 day + 6 night + 8 villagers) all crowd the middle of the area, and 28 were standing on one spot when cleared. A fresh player (140 HP, no armour) teleported there died within about 3 s, twice, and a third time with 320 Defense. One zombie alone does the expected damage (20 per hit), so it is density and clustering (spawn points, or the leash returning everything to the centre), not the damage formula. hub module.
2. **Opening the Auction House from the SkyBlock Menu completes the "Visit the Auction House" objective** without visiting it. progression/auction.
3. **Leggings and boots would not equip with right-click from the hotbar** while helmet and chestplate did (Superior Dragon set, requirements met). Not chased further: the timing of the driver may be the cause. armor, verify in round 2.
4. NPCs read as small on screen because this client's FOV is 110 (Quake Pro). They are the right size.

### Requests answered / not answered

Answered: collections 1, pets 2, menus 1, island 2 / shops 8 (NPC copy keys), combatzones 1 and hollows 1 / mines (warps exist: `world.portals-have-warps` passes), alchemy 8, armor 4, weapons 7, garden 5, shops 5, foraging (`STRETCHING_STICKS`).

Not done (need design or are wave 2): `FishCaught.treasure` (a record change, every caller would move), `Features.Bank`, per-player sidebar hiding, `CommandService.exists`, `CalendarService.millisRemaining` (and its million-iteration loop on forced-only events, menus Request 4: a real performance trap worth doing next round), economy calls that join a caller's DB transaction (auction 4), a per-player fast-travel unlock API (weapons 9), `isAutoVanilla` on the API.

### Next round should start with

1. The three self-test FAILs above (module fixers).
2. Graveyard density.
3. `CalendarMath.dateUntil` million-iteration loop.
4. A second client pass on the islands nobody walked this round (Barn, Desert, Park, Moonmarsh, Gold Mine → Deep Caverns → Dwarven Mines → Crystal Caverns, Spider's Den, The End, Crimson Isle, Bayou, Garden).

---

## Round 2 — 2026-10-02

Module fixers ran between the rounds on enchanting, accessories, bazaar, hub, progression and armor (each hand-off
has a fix section). This round verified those fixes live and cleared what was left.

### Result

| Gate | Result |
|---|---|
| `bash tools/check.sh all` | **CHECK OK**, 721 files (clean on arrival and after every change) |
| `gradlew build` | **439 unit tests, 0 failures** (436 on arrival, plus 3 new in `SellCapTest`) |
| Boot | **32 / 32 modules enabled, 0 FAILED** on all five boots; 3 not present (dungeons, kuudra, rift: wave 2) |
| Island builds | nothing to build (all 18 built in round 1); the Garden instance built on first visit |
| `/sb selftest` | first run **322 passed, 1 failed** → **323 passed, 0 failed** on three separate boots, once with a player online. `bazaar.no-craft-arbitrage`, which changed from boot to boot in round 1, passed every time |
| Real client | fresh dev account `Integ2` (MyFire launcher, `online-mode=false` for the session only). Every round-1 finding was rechecked live; also all 4 hub NPC menus, a kill, coal mined, and **every island visited** |
| Clean-up | `online-mode=true` restored; `Integ2` de-opped and kicked; client closed; server **stopped** (`ops.json` is empty). `Integ2`'s test profile (Combat 20, SB level 10, Superior Dragon set) is still in `skyclone.db`; nothing reads it |

### Fixed this round

| # | Where | Problem | Fix |
|---|---|---|---|
| 1 | crafting (new `SellCap`) | `crafting.no-coin-loops`: 55 recipes owned by OTHER modules sold for more than 2× their inputs (armor Yog/Ember/Rekindled/Werewolf, weapons, foraging Jungle Axe, Miner Outfit, Hotspot Bait…). Accessories capped only its own items | Crafting owns the recipe index, so after late registration it now APPLIES the rule. It lowers npcSell to `crafting.sell-cap-factor` (2.0) × one craft's input value, as a fixpoint, and covers gated recipes too (`crafting.sell-cap-gated`). It only ever lowers. `/sb crafting sellcap [dry]`. 3 unit tests |
| 2 | crafting self-test + cap | The first cap counted an unsellable input (npcSell 0) as FREE. It zeroed **143** prices, among them IRON_INGOT (9 nuggets → 1 ingot), planks, enchanted iron and every armour made of mob drops. The self-test valued inputs the same way, so it would have "passed" a broken economy | Both now share one valuation: a cell counts at its cheapest PRICED id, and a cell with no priced id makes the recipe unjudgeable. After the fix, 7 prices drop: Hotspot Bait 5,000 → 1,713, Jungle Axe 480 → 386, the Miner Outfit set, Squid Hat |
| 3 | core `CalendarMath.dateUntil` | Round-1 carry-over: it looped every year up to `everyYears`, which is a million iterations per call for the mayors' forced-only events | Jumps straight to the qualifying years (≤ 2 iterations). Same result |
| 4 | core `VitalsServiceImpl` | The death chat line showed the raw cause: "You died (mob:hub_graveyard_zombie)" | Now "You died (killed by Crypt Ghoul) and lost …" (mob name, player name or entity type). The raw cause still goes to `SkyDeath.cause` and the ledger. Seen in the client |
| 5 | menus `FastTravelMenu.subWarps` | hub Request 1: the island sub-menu held 14 warps, but the hub has 19, so 5 districts could not be reached from it | Grows to 6 rows (28 warps) when an island has more than 14. Seen in the client: all 19 hub warps |
| 6 | api + core ui + progression | progression Request 3: the emblem showed nowhere outside the leveling menu | `Features.SkyLevel.emblem(p)` (default ""). Progression implements it; core shows it after the name in chat and the tab list. Seen in the client: `[10] Integ2 ◆` |
| 7 | api + core ui | menus Request 3 / slayer Request 9: per-player sidebar section hiding | `SidebarService.setHidden/hidden` (session only; the module that owns the setting re-applies it on ProfileReady) |
| 8 | api + core | menus Requests 2 and 4 | `CommandService.exists(name)`, `CalendarService.millisRemaining(eventId)` |
| 9 | api + fishing | fishing Request 5 | `FishCaught.treasure` + `.quality` (a new constructor; the old one still compiles). Fishing fills them on treasure catches |
| 10 | api | materials Request 4 | `ItemService.isAutoVanilla(id)` is on the API (materials' reflective read still works) |

All API changes are additive `default` methods. They are listed in `docs/CORE_GUIDE.md` §8.

### Round-1 findings, rechecked in the real client

| Round-1 finding | Owner fix | Live result |
|---|---|---|
| Opening the AH from the SkyBlock Menu completed "Visit the Auction House" | progression: completes only from an AH NPC or the `auction_house` zone | **Fixed.** Slot 43 opened the AH and the step stayed current. Walking into the Auction House completed it (+50 coins) |
| The Graveyard at night killed a new player in about 3 s | hub `GraveyardPack`: aggro range 16, at most 3 chasers, a quiet gate, 20 mobs at night | **Fixed.** At 8:40pm the player stood 5 s at the gate with no damage (`pack 18`, `gate ok`). Standing still in the middle with the chat open (not fighting back), only **1 chaser** at any time, and 100 HP lasted about 15–20 s (was about 3 s) |
| Leggings and boots would not equip with right-click | armor: armor swaps every piece itself, with a de-dupe | **Fixed. The round-1 cause was partly the client:** the owner's options bind **hotbar 5 to mouse 5 and hotbar 6 to mouse 4**, so the round-1 driver's "5"/"6" keys never selected those slots. Helmet, chestplate and leggings went on with key + right-click, the boots with scroll + right-click. All four are worn (`data get` confirms), Defense 630 |
| Self-test failures in enchanting, accessories and bazaar | module fixers | all pass |

### Real-client smoke test (what was driven, what was seen)

- **Join.** A fresh profile landed on the private island with the welcome banner, sidebar, action bar and the star in slot 9.
- **SkyBlock Menu** opens. The AH from slot 43 is covered above.
- **Hub NPCs.** Banker → Bank Account; Bazaar agent → Bazaar (Farming); Auction Master → Auction House; Slayer master → dialogue, then the Slayer menu; Thaumaturgist → dialogue (`sheet_npc.png`, `18_thaum.png`).
- **Kill.** Three Graveyard Zombies killed with a sword: +1 coin each in the ledger (`mob_kill:hub_graveyard_zombie`).
- **Mining.** Coal ore in the hub Coal Mine: +5 Mining XP, "Collection unlocked: Coal", and the ore was replaced by its regen placeholder (`25_mine.png`).
- **The islands nobody walked in round 1 were all reached** (`sheet_isl*.png`): Barn, Mushroom Desert, Park, Moonmarsh, Backwater Bayou, Gold Mine, Deep Caverns, Dwarven Mines, Crystal Caverns, Frozen Tunnels, Spider's Den, The End, Crimson Isle, Winter Workshop, Garden (its instance built on the first visit). Each "Visit X" objective paid out on arrival.
- **Persistence.** After a restart the profile came back intact (purse, gear, level, emblem).
- **`/warp`** with no arguments opens Fast Travel. Right-clicking the Hub shows its 19 warps (`70_fasttravel.png`, `71_hub_warps.png`).

Screenshots are in `docs/integration/round2/` (21 files).

### Observed, not fixed (judgement calls; none blocks shipping)

1. **Crypt Ghouls (Lv30)** killed a player with 630 HP and 630 Defense in about 8 s (my driver had teleported into the Crypt). That is Lv30 content doing its job, but the ghouls keep vanilla's 35-block follow range, as the hub fixer noted. If players report swarming, attach `hub_graveyard_pack` to them. Owner: hub.
2. **The Banker's counter lantern** sits between the player and the Banker at chest height, so a click aimed at the body hits the lantern. The head is still clickable. Cosmetic hub geometry.
3. **Admin-spawned mobs** (`/sb mob spawn`) left in a SAFE zone stay there for ever, because they have no area to be leashed back to. Harmless, since SAFE zones protect players. Admin-only.
4. **A dark-purple square** was drawn at the mouse cursor twice, right after clicking a menu icon. It was gone at the next redraw and no item was gained. Unconfirmed; it looks client-side.
5. "Open the SkyBlock Menu" completed while the current step was still "Talk to your Island Helper". This is by design: fire-and-forget triggers may complete any open step.

### Requests answered / not answered

Answered this round: menus 2, 3, 4; hub 1; slayer 9; fishing 5; materials 4; progression 3.
Not done (they need design, or wave 2): `Features.Bank` (bank 5), economy calls that join a caller's DB transaction (auction 4), a per-player fast-travel unlock API (weapons 9), per-player speed/attack-speed caps (armor 12), per-instance nameplates (combatzones 7) and pet display names (pets 3), multi-value enchant descriptions (enchanting 1), "N of an id whose `ID:N` is also an id" in recipe cells (materials 3, crafting 9), shared data of an offline profile (minions 9), `HUNTING` / `FORAGING.maxLevel` (skills 8), a storage hook in the dupe scanner (storage 7), per-instance seeds.

### Next round should start with

1. Wave 2 (dungeons, kuudra, rift) once it lands: `portal.dungeon_hub` is already built, and armor registers four dungeon sets as wave-1 copies.
2. A real-player pass on storage-style menus (accessory bag, wardrobe, backpacks). Free-slot menus are still the least-proven path.
3. The open API requests that more than one module asked for (fast-travel unlocks, economy calls inside a caller's transaction).

---

## Round 3 — 2026-10-02 (post-wave-2, round 1)

Wave 2 landed: **dungeons** (framework + bosses/gear/loot), **kuudra** and **rift**. This round put all three on the
live server and in the real client for the first time.

### Result

| Gate | Result |
|---|---|
| `bash tools/check.sh all` | **CHECK OK**, 845 files (clean on arrival and after every change) |
| `gradlew build` | **482 unit tests, 0 failures** |
| Boot | **35 / 35 modules enabled, 0 FAILED, 0 not present** on all three boots |
| Island builds | the two new islands built on the first boot: Dungeon Hub 527,189 blocks in 4 s, The Rift 4,323,400 blocks in 16 s (1.9 s of tick time). Catacombs and Kuudra arenas are instanced and built per run |
| `/sb selftest` | **357 passed, 0 failed** on every boot, once with a player online (was 323 before wave 2) |
| Real client | dev account `Integ3` (MyFire launcher, `online-mode=false` for the session only). Walked the Dungeon Hub to the Catacombs Gate. **Played F1 five times, reached the boss three times and killed Grinko once** (Floor I DEFEATED, score 189 B, Gold chest). Started a Kuudra Basic hunt from the dock. Entered the Rift through the Warden and left again |
| Clean-up | `online-mode=true` restored; `Integ3` de-opped and kicked; client closed; server **stopped** (`ops.json` is `[]`); the three dungeon dials put back to 1. `Integ3`'s test profile (Catacombs 10, Combat 25, mage faction, about 3.2M purse) is still in `skyclone.db`; nothing reads it |

### Fixed this round

| # | Where | Problem | Fix |
|---|---|---|---|
| 1 | dungeons `boss/FightBase.spawnBoss` | **`dungeons.boss-damage-scale` did not reach the boss body.** It scaled only the scripted attacks. The boss mob's own melee used the floor's research damage, so at scale 0.02 Grinko still killed a player with 588 HP and 630 Defense in one swing ("Another volunteer from the audience!"). The dial could not make a boss testable or tunable | The boss's damage multiplier now includes the dial, and the dial's description says so. Seen live: with the dial at 0.02 the same player survived and killed Grinko |
| 2 | core `VitalsServiceImpl.deathCauseText` | A scripted boss hit (no attacker entity) printed **"You died (mob)"** | `mob` → "killed by a monster", `true` → "struck down" |
| 3 | rift data + `gen_rift.py` | `ANTI_BITE_SCARF` / `_2` loaded as **BARRIER**: the legacy material `CARPET` is not a 26.x item | `MATERIAL` maps `CARPET` → `RED_CARPET` (the reference's durability 14); data patched. The boot WARN is gone |
| 4 | api + reforges + dungeons | dungeons part B Request 1 / reforges Request 3: the essence shop could not see the `/essence` pouch, so perks could only be bought with essence ITEMS | New `Features.Essence` (`essence`, `takeEssence`, `addEssence`), provided by reforges. The Essence Shop counts pouch + items, pays from the pouch first, and refunds the pouch if the item part fails |
| 5 | api + dungeons + kuudra | kuudra Request 1: `/kuudra crew` was separate from `/party` | New `Features.Party` (`members`, `leader`), provided by dungeons from its parties. A player with no crew now hunts with their `/party` (capped at the crew size). An explicit crew still wins |
| 6 | crimson `CrimsonMenus` slot 32 | kuudra Request 2: "Leviathan's Hollow" said "The hunts are not running yet" | Clicking it runs `/kuudra` |
| 7 | menus `FastTravelMenu` | kuudra Request 3: slot 32 "Magmaw's Hollow" showed "not open yet", because island `kuudra` is per-hunt and owns no warp | An island with no warps of its own uses the warp named after it on another island (`kuudra` on `crimson_isle`) |
| 8 | slayer dial default | rift Request 1: Chateau mobs are tagged `slayer: vampire` now | `slayer.vampire-any-mob` defaults to false (heart percent stays at 4; that is slayer's call) |
| 9 | collections dial default | dungeons Requests A4/B3: dungeons credits the `BOSS_*` collections itself | `collections.boss-from-kills` defaults to false |

API changes are additive (`default` methods) and listed in `docs/CORE_GUIDE.md` §8.

### Real-client pass (what was driven, what was seen)

Screenshots are in `docs/integration/round3/` (23 files; `sheet_*.png` are contact sheets).

- **Dungeon Hub.** `/warp dungeon_hub` lands on the forecourt of a dark graveyard plateau with soul lanterns. Walking north up the causeway into the mausoleum's arch opens the **Catacombs Gate** floor selector (`22_gate.png`: Entrance, Floors I–VII and the Master row).
- **F1, first run.** Clicking Floor I starts the run: countdown, "GO!", Sexton Abel's line, and the map in slot 9 (`27_map.png`: the starting room is green, with a `?` room to the north). I killed three room mobs with a sword (Lonely Spider, Skeleton Grunt, Crypt Souleater); each unlocked its bestiary entry and gave +1 SkyBlock XP. `/sb dungeons skip <p> blood` opened the blood door: the Onlooker speaks and blood mobs spawn (`29_door.png`, `sheet_blood.png`). The player died to a Lv90 Rotter (F1 blood mobs are Lv70–90 with about 11k HP), and the run **failed correctly** ("The whole party fell", +12 Catacombs XP).
- **Boss room.** `skip boss` puts you in front of the iron-bar portal (`43_frame.png`). Walking through it lands in Grinko's circus tent, with the boss bar "Grinko — 250k ❤ | First life" and his opening lines (`44_boss.png`). Twice he killed the player in one hit even with the damage dial at 0.02 (fix 1). After the fix: **Floor I DEFEATED in 1m 44s**, Team Score 189 (B), Catacombs and Berserk XP (+40% daily), first-clear SkyBlock XP, the Grinko collection unlocked, and one Gold Chest (`sheet_f1done.png`).
- **Kuudra.** `/warp kuudra` refuses until the Crimson Isle has been visited ("Requires Visiting Crimson Isle"). After that it lands at the dock and opens the hunt board (`60_dock.png`). The Basic card explains health, waves, phases and rewards, and stays locked until you join a faction (`61_basic.png`). After `/sb crimson faction` the confirm screen starts a hunt: the arena is built and ready, a 5-second countdown runs, then Supplies 0/6 with Brood wave 1. A Magma Spit ring landed on the player within the first ten seconds (`63`–`64`). `/kuudra leave` ends the hunt and frees the arena.
- **Rift.** `/rift` shows the overview, and "Enter the Rift" warps to the Wizard Tower. The Warden of the Arch stands on open ground by the obsidian arch (`73_warden.png`), so the spot in rift Request 2 is fine. His menu offers entry by infusion, bits, experience or prism, with costs and counts (`sheet_warden.png`). With a free Planar Infusion the player arrives in the Cradle with a fresh Rift inventory, 10/10 Rift hearts and the Rift Time counter in the action bar (`77_rift.png`). Leaving asks for confirmation, restores the inventory, and reports time inside and motes earned (`79_back.png`).

### Still failing / open (owner or module fixers)

No self-test fails. These are the problems the client showed:

| Owner | Problem |
|---|---|
| dungeons (balance) | **F1 is not survivable at the default dials for a mid-game player.** With Superior-Dragon-class gear (588 HP, 630 Defense), Grinko's body one-shots: the research boss damage of 2,664 per hit is applied as melee. Blood mobs are Lv70–90 with 11k HP and 420 base damage, and a Zombie Grunt also killed the player in the first room. In the reference game F1 is a Catacombs 5 floor. Either the boss's melee should not use the floor's headline damage, or `dungeons.boss-damage-scale` / `mob-damage-scale` need lower defaults. TUNE, the owner's call |
| dungeons | **Once, walking through the open boss portal did nothing** (run 5): the player crossed the frame and came out the other side. Walking back through worked. The first try came right after `skip boss`, so the cause may be the moment the portal opens. Watch for it in normal play |
| dungeons | `/dungeons chests`, run inside a finished run (during the 120-second claim window), opened nothing and printed nothing visible. This is probably intended, since chests are claimed in the room, but it should say so |
| core / driver | Four **void deaths in a row** in the Dungeon Hub early in the session, each taking half the purse (50M → 3.2M). One position read put the player on the limbo platform in the default world. The likely cause is my driver: it right-clicked with a teleport sword (Highlord) while facing the south edge. Not reproduced; noted because a respawn that lands in limbo would be a real bug |
| kuudra / accessories (names) | kuudra Request 4 ("Magmaw" vs "Magma Leviathan") is still open. The Fast Travel icon says "Magmaw's Hollow", while the hunt board says "Leviathan's Hollow" and "The Magma Leviathan". This is a naming call for the owner (`docs/names/kuudra.md`) |
| menus | rift Request 3 and kuudra Request 3 (SkyBlock Menu icons for `/rift` and `/kuudra`) are not done, because the star menu's layout is research-fixed and unit-tested. Both destinations are reachable through Fast Travel, `/rift` and `/kuudra` |
| core | `/sb selftest` (357 tests) holds the main thread for over 10 seconds with a player online, enough to trigger Paper's watchdog thread dump (the armor menu render test was on the stack). Admin-only, but it could yield between tests |
| core (nice to have) | kuudra Request 7 (`MobDef.extra.size` for slimes), rift Request 5 (per-player stat override), dungeons Requests B4 / A5 (never auto-remove untagged entities in game worlds; nothing removed run props this round) |

### Requests answered / not answered

Answered: dungeons B1 (essence API), A4/B3 (boss-from-kills off), kuudra 1 (party), 2 (crimson slot 32), 3 (fast travel), 5 (`KISMET_FEATHER` was already registered by dungeons), rift 1 (vampire-any-mob), rift 2 (the Warden's spot, checked in the client).
Not done: kuudra 4 (names, owner), the menu icons (rift 3, kuudra 3), kuudra 7, rift 5, dungeons B4, and the older carry-overs from round 2 (`Features.Bank`, economy calls inside a caller's transaction, per-player fast-travel unlocks).

### Next round should start with

1. Dungeons balance at the default dials (above): play F1 with Catacombs 5 gear and decide the numbers.
2. A full F1 room clear without `skip` (keys, doors, puzzles, secrets, the Watcher's blood room). This round skipped to the blood room.
3. A Kuudra Basic hunt played through supplies → build → ballista → kill.
4. The Rift: walk the areas, collect a Riddle Soul and Motes, fight the vampire slayer in the Chateau.
5. SkyBlock Menu icons for the Rift and Kuudra, if the owner wants them.

## Wave 3 integration — 2026-10-04

22 module agents worked their "partial" lists in parallel (the `Wave 3 (2026-10-04)` sections of
`docs/handoff/*.md`). This round compiled, tested and booted their work, answered the reasonable Requests and left
the server running. **No client was used**: everything below is compile, JUnit, boot and `/sb selftest`.

### Result

| Check | Result |
|---|---|
| `bash tools/check.sh all` | **CHECK OK (901 files)** on the agents' work as delivered, and again after this round's edits |
| `gradlew build` | **664 unit tests in 78 classes, 0 failures** (was 482) |
| Boot | **35 / 35 modules enabled, 0 FAILED** on both boots. No new WARN lines; the old `BEJEWELED_COLLAR` forge warning is gone (pets registers it now). The duplicate-id WARNs are the known ones |
| Island builds | 8 islands rebuilt on the first boot, all in ~2 minutes: Winter Workshop v2 (361k blocks), Moonmarsh/`galatea` v2 (866k), hub v7 (5.13M, 24 s), Dwarven Mines v3 (2.74M), Crystal Hollows v2 (27.7M), Glacite Tunnels v2 (8.27M), Crimson Isle v2 (4.56M), Rift v2 (4.32M). Second boot: 0 queued. The Private Island v4 Portal Isle is per-instance (new islands get it; old ones via Gerald / `/sb isle retrofit`) |
| `/sb selftest` | first boot **399 passed, 1 failed**; after the fix **400 passed, 0 failed** (4.1 s of main thread spread over 38 ticks) |
| Server | left **running**, `online-mode=true`, `ops.json` empty |

### Fixed this round

| # | Where | Problem | Fix |
|---|---|---|---|
| 1 | mines `MinesSelfTests` | `mines.mobs-registered` FAILED: "only 19 mobs". Wave 3 deliberately removed the Lv70/100/200 goblins (Crystal Hollows mobs) from the Dwarven Mines, leaving 19 defs; the threshold still said 20 | threshold 19, message says why |
| 2 | rift `RiftNpcs.placeHubCopy` | hub Request (wave 3): the hub's arch moved to the Wizard Tower plot's north-west corner and the hub now builds `npc.rift_warden` beside it; the Warden still stood at the old offset, 20 blocks away | at the dial's default the Warden stands on `npc.rift_warden` when the anchor exists; a changed `rift.hub-npc-offset` still wins |
| 3 | pets `PetService` | kuudra Request 9: a Kuudra pet earned no XP during a hunt (the check allowed only island `crimson_isle`, the hunt runs on island `kuudra`) | `kuudra` counts like `crimson_isle` |
| 4 | fishing `Casting.land` | accessories Request 15: Bait Ring / Spiked Atrocity "chance not to use bait" did nothing | +5% / +10% (best one owned) added to the keep-bait chance beside Caster and the sinkers |
| 5 | enchanting `CombatEnchants` | reforges Request 14: Blood-Soaked ("life-stealing heals you 1.15x as much") was lore only | Life Steal, Syphon and Vampirism heals x1.15 while any worn equipment piece is reforged Blood-Soaked |
| 6 | reforges `ItemMods` / `BonusLore` / module | reforges Request 11: Mana Disintegrator had no use | Reforge Anvil accepts it on any item with a mana-costing ability (max 10, `extra.mana_disintegrator_count`, lore line); a core cost modifier `reforges_mana_disintegrator` takes 1% off the ability's mana per one. (The Transmission Tuner half was already done by weapons, `Teleporters`) |
| 7 | api + mining + reforges | reforges Request 12: Glacial / Heated need the player's Cold / Heat | `Features.Mining.heat/cold` (default methods), implemented by mining's `Hotm`; Glacial (+Mining Fortune per Cold) and Heated (+Fortune and +Mining Speed per Heat) are behaviour now (generator flags `bonusImpl` true, data regenerated; diff = those two flags + Fossil Essence). Blazing (worm chance) and Ambered (goblin odds) still need mining-internal hooks |
| 8 | reforges generator + `ReforgeData.ESSENCES` | shops Request 10: `ESSENCE_FOSSIL` was unregistered, so the Fossil Researcher's lines could never trade | registered as the 10th essence (pouch, `/essence` slot 45, `/sb reforges` essence admin). Nothing GIVES fossil essence yet (the reference's source is the Glacite Fossil Excavator, mining) |
| 9 | api + dungeons | accessories Request 12 (first half) | `Features.Dungeons.selectedClass(p)`, implemented by `Progress`. The Adaptive Belt's per-class numbers are accessories' to add |
| 10 | core `MobServiceImpl.updatePlate` | skills Request 11: Runic mobs' purple name was repainted red on every health change (skills re-tinted it a tick later) | core draws the name `&5` when the mob's PDC has `skyclone:runic` = 1 (skills' tint stays as a harmless fallback) |
| 11 | core `VitalsServiceImpl.deathCauseText` | crimson Request 9 / PLAYTEST minor: "You died (lava)" | environmental groups read in words: burned in lava, burned, fell, drowned, suffocated, fell into the void, struck by lightning, froze, starved, withered away, blown up |

API changes are additive (`default` methods) and listed in `docs/CORE_GUIDE.md` §8.

### Requests answered / not answered

Answered: hub → rift (Warden anchor), kuudra 9, accessories 12 (API half) / 15, reforges 11 / 12 (Glacial, Heated) / 14,
shops 10 (item registered), skills 11, crimson 9. Already done before this round (checked): reforges 13 (fishing ships
the gorF drop and the Lotus orb recipe), foraging 7 (Fast Travel's Moonmarsh has a description), storage ← skills 9,
foraging 6 (Moonmarsh rebuilt on boot).

Not done: accessories 12 second half (Catacombs/class XP event for Catacombs Expert Ring / Scarf books) and 14
(Ancient Cloak needs a "costs health" flag on abilities); reforges 10 (Enrichment Swapper is accessories'), reforges 12
Blazing / Ambered; skills 10 (a Runic Pedestal NPC in the hub's Mining District is a hub generator job); hub → shops
(gate the Fletcher's better arrows on `hub:practice:<n>`, optional); rift 6 (`Features.Rift` motes) and 7 (pets id for a
Rift Ferret reward); pets 3 (per-instance display name hook); island 1 / 8 (core command forwarding, co-op roles).

### Not verified live (needs a client next round)

Every wave-3 feature is compile + unit + boot + self-test verified only. Highest value to look at first, from the hand-offs:
the hub's Rift arch (walk in with and without a Planar Infusion) and Target Practice at the Archery Range; the Private
Island Portal Isle (new account → walk south over the bridge; visit The Barn → its portal lights within 2 s); Kuudra
"Hunt Again" after a win (the playtest blocker); the Moonmarsh resources, mobs and Starfall Contest; the Belly of the
Beast and Heavy Pearls on the Crimson Isle; the Goblin Queen's Den; the weapons Quiver Arrow with a Hunter Bow and an
arrow-only quiver; pet riding (Rock seat, Armadillo mount); the bank's account-wide sync on a profile switch; the Rift
Gallery, Split or Steal and Odonata; the Runic Pedestal (`/runes`); the Carnival games; `/gfs`.

### Next round should start with

1. A real-client pass over the list above (TestPilot, `online-mode=false` for the session only, restore `true`).
   It is scripted now: `tools/testpilot/wave3_pass.py` (14 checks, report in `docs/playtest/wave3/`); rebuild and
   reinstall the TestPilot jar first (new `/usehold` endpoint for the two bow checks). See `docs/handoff/all.md`.
2. Dungeons balance at default dials (carried over from round 3).
3. Fossil Essence has no source: mining's fossil excavation (or a temporary shop line) should pay it.

## Wave 5 integration — 2026-10-06

35 module agents worked their wave-5 partial lists, core added hooks first (`docs/handoff/core-wave5.md`), and five NEW
modules arrived: **hunting** (Hunting skill, 322 attribute shards, Critter Safari), **galatea** (Lotus Atoll + Torrent
Canyon), **chocolate** (Chocolate Factory + Hopsworth's Hunt), **extras** (Ringphone/Abiphone, City Projects, Fire
Sales, skins, the harp) and **soulflow** (Soulstream / Overflow). This round wired them in, compiled, tested, booted
and answered the reasonable Requests. **No client was used**: compile, JUnit, boot and `/sb selftest` only.

### Result

| Check | Result |
|---|---|
| `core.ModuleList` | 35 -> **40**: `soulflow`, `hunting` after `garden` (systems); `galatea` after `crimson` (islands; after fishing/foraging/hub so its second arches' warps exist); `chocolate`, `extras` last (they read other services at use time) |
| `bash tools/check.sh all` | **CHECK OK (1104 files)** before and after this round's edits |
| `gradlew build` | **1058 unit tests in 119 classes, 0 failures** (was 741). First run: 3 `NpcNamesTest` failures (fixed, below) |
| Boot | **40 / 40 modules enabled, 0 FAILED** on all four boots. New WARN only: `/hunt` alias of `/kuudra` dropped (hunting owns `/hunt`; `/kuudra` still works) |
| Island builds | first boot queued and built 14, ~2.5 min total: carnival (142k), Moonmarsh/`galatea` v3 (870k), **Critter Safari** (622k, new), hub (5.13M, 24 s), Gold Mine (890k), Deep Caverns (812k), Dwarven Mines (2.74M), Crystal Hollows (27.7M), Spider's Den (2.26M), The End (1.02M), Crimson Isle (4.56M), **Lotus Atoll** (1.42M, new), **Torrent Canyon** (1.25M, new), Rift (4.32M). Later boots: 0 queued |
| `/sb selftest` | first boot **641 passed, 5 failed**; final **648 passed, 0 failed** (15.7 s of main thread over 91 ticks) |
| Server | left **running** (port 25630), `online-mode=true`, `ops.json` empty |

### Fixed this round

| # | Where | Problem | Fix |
|---|---|---|---|
| 1 | `NpcNamesTest` (unit) | name clashes from the new NPCs: Hunter Dorran / Dorran the Guard, Clerk Pella / Pella, Receptionist Liesl / agent Liesl Brackwell, Herbalist Wren / Wren the Fairy, contest ghosts Jessamy + Wilf vs the hub's errand NPCs, Hopsworth vs Hopsworth's Hunt | renamed **Hunter Ossian**, **Clerk Isaura** (hunting), **Receptionist Jutta** (galatea), **Herbalist Jorunn** (foraging), ghosts **Jolene / Wolcott**; `hopsworth` added to `NpcNames.SAME_PERSON` (the visitor runs the Hunt). docs/names updated |
| 2 | `ui.npc-names-unique` (live) | Mossbeard (foraging's jungle elder) vs Mossbeard Quorrin (rift riddle NPC) | rift's is **Capwort Quorrin** / soul "Capwort's Secret" (RiftEnigma, gen_rift.py, docs/names/rift.md) |
| 3 | `materials.wave5-fallbacks` | no live recipe used `GOLDEN_FRAGMENT`: crafting's starred Midas/Daedalus recipes are dropped because no module registered the STARRED_ items | dungeons' starred-copy mechanism now also makes **STARRED_MIDAS_SWORD / _STAFF / _DAEDALUS_AXE** (rows with `"dungeon": false`, deltas 0 = the API data; recipe = 8 Golden Fragments around the base; Daedalus' grid falls back to crafting's table since NEU has none). `gen_starred.py`, `StarredWeapons.Row.dungeon`; the self-test checks the flag both ways |
| 4 | `garden.market-gates` | farming now gates 11 crop tools on their Garden level (from the tool spec, at boot); the Copper Market still sold them at Garden 1 | `gen_garden.py` reads `extra.<spec>.gardenLevel` too; skymart.json regenerated (only those 11 `level` lines changed) |
| 5 | `hunting.mob-sources-exist` | mayors' wave-5 Mythic Ritual moved the Minotaur / Inquisitor to tiers 4-6 / 5-6 | King Minos shard -> `myth_inquisitor_5/6`; `gen_hunting.py` re-run (it also picked up fishing's new Torrent Canyon sea creatures; the Sphinx shard is now a Mythic Ritual drop, so 247 critters) |
| 6 | `auction.vault-ironman` | this year's Ironman lots were made by an older build (stock = classic) | data, not code: `/sb auction vault roll` (no bids existed) -> 14 fresh lots, 7 Ironman at 50% |
| 7 | `fishing.puddle-jumper-elusive`, `fishing.chum-bucket-live` | passed on the island-building boot, failed on every later one: with nobody online the world spawn chunk is not loaded, so the test entities never appeared | both tests hold a plugin chunk ticket on the spawn chunk for their duration |
| 8 | foraging `Harp` / extras | **double Intelligence**: two harps both granted permanent Int (extras request 1) | Lyra's "Play the Harp" runs extras' `/harp` when it exists; `foraging.harp-int-scale` default 1.0 -> **0** (the memory-game harp stays as the fallback) |
| 9 | soulflow | slayer now grants Vivid Darkness only while Soulstream can pay (slayer request 13) | `soulflow.charge-owner-gated-drains` default **true** |
| 10 | shops | slayer request 14: anyone could BUY a slayer mixin | the Bartender's five mixins carry `slayer:<type>:8` (gen_shops.py `LINE_REQ`; diff = those 5 lines) |
| 11 | chocolate + materials | chocolate request 6: nothing gave `ENCHANTED_TIME_CLOCK` its ability | the Clock has **Time Warp** (`time_warp`, Gold Ingot X, NEU lore); chocolate handles it with a **48 h cooldown stored in the profile** (dial `chocolate.time-warp-cooldown-hours`; core's cooldown map is lost on relog) and pays one hour of production. The lore names only the factory, the one system that reads it |
| 12 | chocolate | chocolate request 4: `HOT_CHOCOLATE_MIXIN` did not exist (the shop line is `optional`, so it was hidden) | registered by chocolate (gen_chocolate.py): needs CF VI and a Boon Cookie, 36 h stacking (dial `chocolate.mixin-hours`), +0.05x factory and +15 Pet Luck. Brewing it into God Potions is not built (alchemy has no mixin route) |
| 13 | accessories | soulflow request 3: items still named "Soulflow Pile / Battery / Supercell" (a reference noun shown to players) | `WORD_RENAMES` Soulflow -> **Soulstream**; the generator now also writes the wave-5 doc rows that a regen used to drop |
| 14 | storage | galatea request 6: the frog sacks had no title gate | Bronze / Silver Trophy Frog Sack require `frogs:title:1/2` (NEU: Novice / Adept Frog Collector). The regen also picked up crafting's `garden:analyzer:N` recipe unlocks... |
| 15 | garden | ...which nothing resolved (`garden:analyzer:5` parsed as Garden Level "analyzer" = locked for ever) | garden's resolver answers `garden:analyzer:<n>` ("Crop Analyzer Milestone V") |
| 16 | hunting | reforges request 16: Majestic logged "does nothing yet" | hunting registers requirement prefix `safari` (`safari:active` = a Critter Safari hunt is running) |
| 17 | api | hunting request 2, extras request 3, menus request 13 | `Features.Hunting.openMenu`, `Features.Abiphone.contacts` (extras implements it); `Msg.rareDrop` honours Settings `chat.rare_drops` (CORE_GUIDE §8) |

New self-tests: `chocolate.time-warp-and-mixin`, `hunting.integration-hooks` (safari prefix, skill prefix, `/hunting`).

### Requests not done (with reason)

- extras 2 (bank fame API + Community Shop tabs), 4 (the 8 Abicase variants: the bits shop still lists them as
  unregistered; naming the parody brands is an owner call), 5 (trading over a phone call), 7 (pet/backpack skins in
  Fire Sales) — each is a design, not a hook.
- chocolate 7 (`Features.Chocolate`), soulflow 5 (Overflow pool in `VitalsService`) and Life Tap -> Overflow, slayer 19
  (`Features.Slayers.mixins`), dungeons 7 (potions off inside the Catacombs) — larger API designs.
- hunting 4 (the 171 attribute HOOK keys): owners read many of theirs this wave (storage, shops, chocolate, fishing,
  foraging, crimson, kuudra, slayer, pets, skills, mayors); the rest still resolve to a number nobody applies.
  hunting 7 (Safari tickets from Starfall contests), 8 (treasure-catch shards) — owners' calls.
- menus 13: the other Settings keys (skills `chat.xp_popup` / `numbers.roman`, bank, garden, mining, extras) are the
  owners' to read; only core's `chat.rare_drops` was done here.
- Fast Travel entries for `torrhus_springs` / `torrhus_safari` (galatea 4): menus lists the islands, not the sub-warps.

### Notes for the next integrator

- `rcon.ps1 "sb selftest"`: the poll connection is dropped when one test holds the main thread ~3 s
  (`combatzones.walkable`, `garden.island-walkable`), so the script exits 1 mid-run. The run itself finishes: read
  `SELFTEST:` from `server/logs/latest.log` or run `sb selftest report` again. The FIRST RCON connection after a boot
  also tends to be closed with 0 bytes; just retry.
- A self-test that spawns entities at the world spawn must load/ticket the chunk itself: it passes on an
  island-building boot (chunks happen to be loaded) and fails on every later one.
- Regenerating a module's data picks up OTHER modules' renames and requirements (garden read farming's gates,
  storage read crafting's unlocks, hunting read mayors' mobs, materials read a renamed contest). Diff every regen;
  this round each diff was the intended lines plus those cross-module updates.

### Next round should start with

1. A real-client pass over the five new modules (TestPilot, `online-mode=false` for the session only): `/hunting`
   + a lasso catch + the Safari; `/galatea` -> Lotus Atoll frogs and the Torrent Canyon journal; `/cf` + an egg in
   the Hunt; `/phone`, and `/harp` at Lyra; `/soulflow` with a Soulstream Pile.
2. The carried-over list in Wave 3 integration ("Not verified live").

## 2026-10-07 — real-game audit fixes (gate)
Research on the owner's real Hypixel account (SkyblockMoney/work/*.md) was turned into an audit
(`docs/AUDIT_REAL_GAME_2026-10-06.md`: 172 match, 60 wrong, 23 missing) and fixed in 13 parallel lanes per
`docs/FIX_BRIEF_2026-10-06.md` (kuudra, crimson, mining, mayors, fishing, slayer, dungeons, hunting, garden incl. Sowdust
and the Pests currency, armor, enchanting, shops/materials, plus a cross-module pass). Gate: `deploy.ps1 -Test` 1143/1143
unit tests, boot 40/40 modules with 0 failed, `/sb selftest` 680/680, `docs/COMMANDS.md` regenerated. Not yet played in
a client. Open: Ananke Feather RNG-meter amount (no published number); F7 per-chest table; Master Mode chest cost x2 unverified.
