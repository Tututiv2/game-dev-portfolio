# hunting - hand-off


## Real-game audit, lane 7 (2026-10-06, AUDIT_REAL_GAME_2026-10-06.md C14 C15 C16 C20)
- **C14/C20 Shellwise + Joydive** are netted critters at `loch` (`gen_hunting.py` NETTED_AT). There is no separate
  Murkwater habitat because `loch` IS it: the reference's Murkwater Loch (/warp murk) is our Dimwater Loch
  (docs/names/foraging.md `dimwater_loch`). Shellwise was fusion-only by the "Reptile EPIC" rule; it is excepted.
  Not done (not this lane): a `/warp murk`-style warp to the loch - foraging/menus own warps.
- **C15 Paragon**: source type `slayer` listing every T4/T5 boss (zombie 4-5, spider 4-5, wolf 4, enderman 4, blaze 4).
  Display only: it is no longer a `mob` source, so Charm Chance / Black Holes no longer yield Paragon from
  Revenant/Tarantula bosses (the real shard comes only from the slayer module's own pool).
- **C16 Kraken**: source type `kuudra`, no critter (`critter_kraken` is gone from mobs.json and every habitat). The
  kuudra lane makes it a guaranteed paid-chest drop; the SHARD_KRAKEN item stays registered here.
- Attribute names vs shards checked against NEU constants/attribute_shards.json for all 322: attr ids, rarity and codes
  all match (Lunar Power = Lunar Moth, Solar Power = Firefly, Pest Fortune = Cricket, Infiltration = SHARD_TERMITE
  shown as Earthworm); 11 ability names are renamed proper nouns.
- **Tracking**: `stat:tracking` (read by mayors' Mythology for Griffin Burrow pools) now adds Diana's Huntress'
  Intuition (+10, `diana.huntress_intuition` via Features.Mayors perkActive/perkValue; `Hunt.perkTracking`). Nothing in
  hunting itself spends Tracking (research: "chance to find Elusive mobs" has no system here yet).
- `HuntingMenus.sourceLore` and the admin `spawn` message know the new source types. Tests: HuntingPureTest +4 (21/21).

## Fixes from the wave-5 playtest (2026-10-06, PLAYTEST.md "Found" 2 and 3)
- **Water critters were out of net reach** (Lotus Lagoon: 8-12 blocks down, 20-60 blocks from the pier; a net
  reaches ~7). Two causes, both fixed in `Critters`: (1) the spawn spot was random across the whole lagoon box -
  water critters now appear 3-6 blocks (`HuntMath.WATER_SPAWN_MIN/MAX`) from a player standing in or within 8 blocks
  of the habitat (`waterNearHunter`; falls back to the old whole-box spot when nobody is there or no water is near);
  (2) vanilla fish AI dives and wanders - every second a live Water critter is `keepShallow`ed: pushed up past
  `hunting.water-critter-depth` (2), steered back past `hunting.water-critter-leash` (8 blocks from where it
  appeared), and teleported back when nudging is not enough (> depth+4 down, or > 2x leash out - squid AI overwrites
  its velocity every tick). Beached / admin-spawned-on-land critters and leashed ones are left alone. Numbers are
  `// TUNE` (pure, in `HuntMath.surfacePush/leashPull/snapBack`). Two new dials.
- **`/sb hunting spawn <p> SHARD_INKLOTL` -> "Unknown critter"**: ids stay the reference's (`SHARD_SEPIALOT`), and
  `ShardCatalog.find` only knew ids/attribute keys/codes. It now also takes what players SEE - display name and
  attribute name, normalized (case, spaces, underscores, `&`-codes ignored): `Inklotl`, `SHARD_INKLOTL`, `king_minos`,
  `"Bucket Greed"`, plus a critter mob id (`critter_sepialot`). Ids are tried first, so a name never shadows an id;
  shard names win over attribute names. `spawn` joins the rest of the line (two-word names work), says WHERE a
  shard comes from when it has no critter, and every shard argument of `/sb hunting` tab-completes names.
- **Found on the way (real bug)**: `find` tried the `SHARD_<x>` guess before the attribute key, and the Sun Fish's
  attribute is `starborn` while `SHARD_STARBORN` is the Starchild - so `Features.Hunting.attributeLevel(p,
  "starborn")` read the Starchild's echo attribute. Attribute key now comes before the guess.
- Verified: `bash tools/check.sh hunting` -> CHECK OK; `HuntingPureTest` 17/17 (3 new: `findAcceptsWhatPlayersSee`,
  `everyShardIsFoundByItsDisplayNameAndIdsWinOverNames` - which caught the starborn bug -,
  `waterCrittersAreKeptWithinANetThrow`). New live self-tests `hunting.find-by-name`,
  `hunting.water-critters-netable` (not run: no server in this lane). **Not checked live**: a net from the Lotus
  pier - re-run PLAYTEST 063 (`/sb hunting critters`, stand on the pier, the critters should surface within 6).

## Wave 5 (2026-10-06)

New module `com.skyclone.mod.hunting.HuntingModule` (the integrator adds it to `core.ModuleList`). The Hunting skill
(12th skill, core's `SkillType.HUNTING`; skills owns the XP table, hunting grants the XP), critters, the catching
tools, ATTRIBUTE SHARDS (the 2025 rework: all 322 reference shards), the Hunting Box, syphoning, fusion, the
Attribute Menu, Salts / Charm Chance, Huntraps, and the Critter Safari island. Provides `Features.Hunting`.

### Done (verified how)
- `bash tools/check.sh hunting` -> `CHECK OK: hunting (195 files)`.
- 14 unit tests, `plugin/src/test/java/com/skyclone/mod/hunting/HuntingPureTest.java`, all green (run with the JUnit
  launcher against the shipping JSON): levelling == the reference table, Hunting Fortune math, capture XP, catalog
  (322 shards, unique codes/attributes/names, every shard has a source), reference names never reach players,
  habitats hold real critters, effects parse, every fusion-only shard is made by a FAMILY fusion (this test caught
  the Chameleon being unobtainable; it is now a critter), the Chameleon gives the next 3 on the line, echoes.
- `bash tools/preview.sh com.skyclone.mod.hunting.gen.SafariGenerator` -> RESULT OK, every anchor + `portal.hub`
  reachable; iso.png LOOKED at: plaza with four canopied desks, a forest of oak/birch/cherry (north), a stone dome
  cavern (east, open to the west), snow + spruce + ice spikes + campfires (south), dark oak + a mansion (west).
- 10 live self-tests registered (not run: no server in this lane).
- The server was NOT started.

### The data (generated, never hand-typed)
`tools/gen/hunting/gen_hunting.py` reads `neu-repo/constants/attribute_shards.json` (322 shards: rarity, alignment,
families, id line C1..L60, levelling table) and every `ATTRIBUTE_SHARD_*` item's lore (the level-I effect), plus every
other module's `mobs.json`, and writes `data/hunting/`:
- `shards.json` - per shard: our name, attribute key (`fishing_speed` - the NEU attribute id lower-snaked), our
  attribute name, the effect PARSED into a kind (`stat`, `stat_pct`, `pseudo`, `damage_vs`, `defense_vs`, `mf_vs`,
  `echo`, `hf_rarity`, `melee`, `ranged`, `full_hp`, `low_hp`, `payback`, `hook`), per-level value(s), a lore template,
  echo groups, and sources (mob kills, a critter + capture method + habitats, or fusion only).
- `items.json` (357): every shard (`SHARD_*`, PLAYER_HEAD with the reference skull), 4 lassos, 4 nets, 3 black holes,
  5 huntaxes, 5 huntraps, 6 salts, 2 capsules, 4 Safari tickets, `ESSENCE_SAFARI` (extra.essence = SAFARI, so the
  reforges pouch absorbs it), `RAINBOW_FEATHER`.
- `mobs.json` - 248 critter MobDefs (`critter_<shard>`), bodies chosen by name/family (fish for water, birds,
  bugs, pandas...); Combat ones are hostile "shard creatures" with real HP.
- `habitats.json` - 27 habitats on existing islands' named areas (galatea forest.marsh/forest.canyon/mobs.loch,
  park forests, hub forest/pond/wilderness/crypt, barn fields, bayou waters, rimestone lake, hollows, dwarven,
  spider's den, the end, crimson) + the Safari's 4 biomes.
- `recipes.json` - the 24 reference recipes of the tools; registered one tick after boot ONLY when every ingredient
  exists (logged), so `recipes.ingredients-exist` can never fail on them.
- `levelling.json`, `salts.json`.

### How it plays
- **Capture** (`Hunt.capture`): 1 shard + 1 per 100 Hunting Fortune (+ the remainder as a chance; research 01), tool
  Hunting Fortune ("+5 on Forest Shards caught with this Lasso") and the Karma attributes add to it. Hunting XP per
  shard by rarity: 75 / 150 / 300 / 500 / 1,000 (research 02 2.4.8). Shards arrive as ITEMS (loot.give); right-click
  one (shift = all) to send it to the Hunting Box.
- **Lasso** (Forest critters): right-click a critter, it is held still while its stamina drains (Vinerip/Entangler/
  Everstretch drain 20/40/60% faster, Hunter's Suppress more); when it runs out, REEL IN within 1.5 s. Rare+ need 2,
  Legendary 3 reels; 3 misses and it breaks free.
- **Fishing Net** (Water critters): Pull vs the critter's weight; heavy ones take several throws (Eelastic adds Pull).
- **Pocket Black Hole** (Combat): 180 s; any monster that has a shard (a mapped mob OR a shard creature) below 10% HP
  inside it is pulled in and captured after 3 s (Hunter's Pressure faster), killed with its loot.
- **Huntaxe**: each strike capped at 15-50% of max HP and never fatal (so the black hole can take it); "Borrowed
  Edge" attunes a weapon you carry and borrows 5-25% of its damage and stats (Hunter's Fang more). Nothing is stored
  inside the axe (no dupe surface).
- **Huntrap**: set inside a habitat, catches one of its shards after 20 min (-10/20/35/50% by trap, Forest/Combat Trap
  attributes faster); collect from `/hunting traps`; max 3.
- **Charm Chance** = Hunting skill perk (0.04 / level) + Charmed + active Salts. Every kill of a mob that names a shard
  rolls it. Salts (Lushlilac 0.5%/8h ... Brine Tonic 2.75%/24h, numbers from the reference lore) by left-click.
- **Fishing**: a plain catch has `hunting.fish-shard-chance` (1%) to bring up a Water Shard of that island.
- **Hunting Box -> Syphon**: shards needed per level by rarity (Common 1,3,5,6,7,8,10,14,18,24 = 96 ... Legendary 24);
  syphon XP 10/25/50/100/200 per shard (TUNE).
- **Attributes** apply through: a StatProvider (64 stat effects, conditional ones like "while on Fishing Islands" /
  "during the night" / "in the Garden"), `stat_pct` (Unlimited ...), a damage modifier at 250 (rulers vs mob types,
  Warrior, Deadeye, Dominance, Lifeline, Payback, Elite vs bosses), defense vs mob types (incoming), per-target Magic
  Find (`addMagicFindModifier("hunting")`), Echoes (+x% to a group; Echo of Echoes first). Toggle any attribute
  off in the Attribute Menu without losing it. Hooks honoured inside hunting: Midas Touch (coins per kill), Life
  Recovery, Mana Steal, Breeze (mana cost), Catacombs Explorer / Graduate (`DungeonXpGain`), Unity is Strength,
  Berry Mogul (salts last longer), Pure Reptile, Splendor, Ticket Discount, all the trap/lasso/net/black-hole ones.
- **Fusion Machine**: two shards from the box (cost C5/U4/R3/E2/L1 each, TUNE). Rules (ours, TUNE - the reference
  table is not in the data): LINE (next on the id line of the higher rarity; same rarity gives 2), FAMILY (shared
  family -> a member one rarity up, of the first shard's alignment; the only way to the 25 fusion-only shards: upper
  Elementals, top Reptiles), CHAMELEON (+X -> the next 3 on X's line). Results go back into the box.
- **Critter Safari** (`critter_safari`, warp `safari`, needs Hunting XV, zones gated the same): a Safari Ticket
  (right-click on the island) starts a 20-min hunt with 32/40/48/56 Critter Capsules (+10/25/50% essence, +5/15/25
  Hunting Wisdom; research 03 25). Capsule catch chance by rarity (90..20%, Masterful 100%). Each catch: shard, XP,
  Safari Essence by rarity; 1/4,096 Sparkling (10x, Rainbow Feather). Hunts per biome = milestones (10/50/150/400/
  1,000) -> Safari Belt (worn) +1 Hunting Fortune +0.5 Hunting Wisdom per tier (TUNE). Leaving / time up ends the
  hunt and capsules vanish. Perks (Safari Essence): +2 capsules, +5% essence, +10% sparkle per level; Morning Dew 2,500.
  Ticket upgrades cost Forest Essence (500/1,500/5,000, TUNE) from the essence pouch.
- Critter spawner: per habitat, up to `hunting.critter-cap` (6) critters while a player is near, rarity-weighted
  (50/26/14/7/3); they leave 90 s after the last player.

### Commands
Player: `/hunting` (`/hunt`) `[box|attributes|fusion|collection|traps|habitats|tools|salts|safari]`, `/huntingbox`
(`/hbox`), `/attributes`, `/fusion`, `/shards`, `/safari` (warps there; on the island opens the desk - galatea's
gate NPC already calls it). Menu path: SkyBlock Menu -> (Request 3) -> Hunting; every sub-menu has Go Back.
Admin `/sb hunting info|give|capture|level|syphon|reset|spawn|critters|fuse|safari|charm|salt` - all take a player
name (RCON-testable). `/sb hunting level <p> fishing_speed 10` sets an attribute; `fuse <a> <b>` prints outputs.

### Dials (`/sb dial list hunting`)
critters, critter-cap, critter-interval-seconds, critter-despawn-seconds, reel-window-seconds, lasso-misses,
blackhole-hp-percent, blackhole-capture-seconds, trap-minutes, trap-max, fish-shard-chance, xp-multiplier,
charm-scale, safari-minutes, sparkling-chance, sparkling-multiplier, ticket-price, safari-level, water-critter-depth,
water-critter-leash.

### Self-tests (`/sb selftest hunting`)
catalog, levelling-matches-research, critters-registered, mob-sources-exist, every-shard-obtainable,
effects-resolve, tools, safari-layout, habitats-exist, critter-defs-sane.

### Features.Hunting (for other modules)
```java
Features.Hunting h = Sky.service(Features.Hunting.class);
h.addShards(p, "SHARD_REVENANT", 1, "slayer_boss");   // straight into the Hunting Box + Hunting XP (capture XP)
h.shards(p, "SHARD_ZEALOT");                          // box + inventory
h.attributeLevel(p, "fishing_speed");                 // 0..10
h.attributeLevel(p, "value:sack_size");               // EXTRA FORM: level x per-level x echo = the number to apply
h.attributeLevel(p, "stat:tracking");                 // EXTRA FORM: pseudo stats (charm_chance, pull, tracking,
                                                      //   respiration, pressure_resistance, overbloom, gemstone_spread,
                                                      //   fig_fortune, mangrove_fortune, helix_fortune, timber, crop_growth)
```
Attribute keys and their per-level numbers: `data/hunting/shards.json` (`attr`, `effect.per`, `text`).

### Shard ids other modules should drop
Hunting ALREADY rolls these on `MobKill` (Charm Chance) and through Black Holes for the mob ids in `shards.json`
`sources[type=mob]` (90 shards: sea creatures, crimson, end, den, dungeon, slayer minis, pests, mining...). Chest /
reward drops are yours, through `addShards`:
- **kuudra** (paid chest slot 1 "attribute shard", research 04 4.6): `SHARD_HELLWISP`, `SHARD_KRAKEN`, `SHARD_DAEMON`,
  `SHARD_MOLTHORN`, `SHARD_ETHERDRAKE`, `SHARD_JORMUNG`, `SHARD_TIAMAT` (Demon/Dragon/Reptile legendaries) // TUNE weights
- **slayer** (boss loot, research 04: Revenant w100-200, Paragon w0.0025-0.005, Primordial 0.71%): `SHARD_REVENANT`
  (zombie T3+), `SHARD_PARAGON` (zombie/spider T4+), `SHARD_PRIMORDIAL` (spider T5), `SHARD_BURNINGSOUL` (blaze T3+),
  `SHARD_SYCOPHANT`, `SHARD_ARACHNE`. (Their boss mob ids are mapped too, so a Charm drop also works.)
- **crimson**: `SHARD_BEZAL`, `SHARD_FLARE`, `SHARD_CINDER_BAT`, `SHARD_MATCHO`, `SHARD_KADA_KNIGHT`,
  `SHARD_WITHER_SPECTER`, `SHARD_FLAMING_SPIDER`, `SHARD_BARBARIAN_DUKE_X`, `SHARD_DIVE_GHAST` (all mob-mapped).
- **fishing** (lava/sea): `SHARD_LAVA_LEECH`, `SHARD_LAVA_FLAME`, `SHARD_FIRE_EEL`, `SHARD_TAURUS`, `SHARD_THUNDER`,
  `SHARD_LORD_JAWBUS`, `SHARD_MAGMA_SLUG`, `SHARD_STRIDER_SURFER`, `SHARD_MOLTENFISH`, `SHARD_INFERNO_KOI`,
  `SHARD_SEA_ARCHER`, `SHARD_WATER_HYDRA`, `SHARD_SEA_EMPEROR`, `SHARD_NESSIE`, `SHARD_TITANOBOA`, `SHARD_WIKI_TIKI`
  (sea creatures are mob-mapped; treasure catches could add `SHARD_GHOST_CRAB`/`SHARD_BLUE_CRAB`).

### Still left
- Attribute HOOKS other modules must read (171 effects are numbers for another system: sack size, essences, powder,
  garden visitors, chocolate, pets, slayer quest discount, dungeon score...). They resolve now via
  `attributeLevel(p, "value:<key>")`; nothing reads them yet (Requests 4).
- Shell (knockback), Sky High (fall distance), Experience (orbs), Ignition, Combo, Soul Snatcher - hooks, unbuilt.
- Safari Birdfeeder / quest items (Bag of Seeds, gems, Shining Coin...), the 7 hidden bells, the Critterdex NPC -
  not built; the capsule is the only Safari method.
- Hunting Toolkit (tool storage) not built.
- Fusion rules and every TUNE number await a real comparison; critter stamina/pull/HP are by rarity only.
- Nothing was tested in a live client.

### Requests
1. **Integrator**: add `HuntingModule` to `core.ModuleList` (anywhere after skills/collections/materials; it reads
   other modules' mob defs only at use time).
2. **core** (optional): `Features.Hunting.openMenu(p)` default running `/hunting` so menus can call it like the others.
3. **menus**: a "Hunting" button in the SkyBlock Menu -> `Sky.commands().run(p, "hunting")`; **skills**: the
   Hunting skill page could link to `/hunting`.
4. **Owners of hooked systems** - read `attributeLevel(p, "value:<key>")` at use time (examples):
   storage `sack_size` (% capacity); accessories `accessory_size` (+slots), `cloak_improvement` (Huntmaster's Cloak
   True Defense); reforges `*_essence` keys (`undead_essence`, `crimson_essence`...: % more essence), Sticky reforge
   Pull on nets; mining `atomized_mithril/_crystals/_glacite`, `pickaxe_cooldown`, `deep_technique`,
   `mountain_climber`, `dwarven_training`, `dwarven_serendipity`, `crystal_serendipity`, `bigger_box`,
   `glossy_fortune`, `refined_luck`, `scrap_chance`, `quartz_speed`; foraging/galatea `stat:fig_fortune`,
   `stat:mangrove_fortune`, `stat:helix_fortune`, `fig_sharpening`, `mangrove_sharpening`, `helix_sharpening`,
   `fig_collector`..., `chop`, `first_sweep`, `axe_cooldown`, `tough_claws`, `honey_*`, `*_serendipity`,
   `galatea_training`, `starlyn_training`, `berry_enjoyer`, `atomized_whispers`, `forest_essence`; fishing
   `cheapstake`, `fisherman`, `lucky_rod`, `outstanding_fisher`, `great_treasure`, `boot_fisher`, `buckle_fisher`,
   `lost_and_found`, `infection`, `star_bait`, `gold_bait`, `bucket_*`; garden `visitor_*`, `crop_speed`,
   `pest_*`, `compost_speed`, `greenhouse_speed`, `fancy_visit`, `pretty_clothes`, `infiltration`,
   `mutation_serendipity`, `enchanted_farmer`, `wart_eater`, `filter_upgrade`, `sprayonator_serendipity`,
   `groovy_radar`; slayer `slayer_progress`, `slayer_discount`, `cocoon_chance`; dungeons `reborn`, `rekindle`,
   `faker`, `catacomb_box`, `floor_fortune`; enchanting `bone_font`; pets `owl_friend`, `why_not_more`,
   `battle_experience`, `kat_favorite`, `battle_frog`, `foraging_frog`, `battle_arachnid`, `greedy_goblin`;
   chocolate `chocolate_speed`, `rabbit_*`, `egg_finder`, `hitman_cooldown`; shops `builder_discount`,
   `bartender_discount`; bank `cookie_eater`; kuudra `kuudra_box`; mayors `king_of_greed`, `big_game_hunter`.
5. **progression (bestiary)**: 248 `critter_*` mob defs exist; list them as one "Critters" family group (or skip the
   `critter_` prefix) so the bestiary does not grow 248 rows. They carry `extra.types = ["critter", ...]`.
6. **accessories**: `SAFARI_BELT` is yours; hunting adds its milestone stats when it is worn
   (`Features.Equipment.worn`), so keep the item's own stats at 0 to avoid doubling.
7. **galatea**: your gate NPC's `/safari` now warps to `critter_safari`; the Safari tickets come from your Starfall
   contests in the reference - you can `Sky.loot().give(p, "SAFARI_TICKET_BASIC", 1, REWARD)` (ids above). The
   `critter_safari` island has its own `portal.hub`; a portal back to your canyon gate would be nicer (your call).
8. **fishing**: if you fire `FishCaught` with `treasure=true`, hunting could roll the treasure-fish shards
   (`SHARD_PIRANHA`, `SHARD_SNOWFIN`...) there instead of the plain 1%.

### Known risks
1. Critter spawning, the lasso hold (teleport every 2 ticks) and nets are untested live; flying bodies (parrot, bee,
   phantom) may drift, water spots need real water inside the named area (`/sb hunting critters`, self-test
   `hunting.habitats-exist` names any missing area).
2. 248 extra MobDefs + 357 items: any module that iterates all mobs/items (bestiary, museum, bazaar groups) sees them.
3. Hunting XP is per shard, so Hunting Fortune multiplies XP too (TUNE `hunting.xp-multiplier`); fusion rules and
   prices are ours.

## Cross-module follow-ups (2026-10-06, integrator list in docs/FIX_BRIEF_2026-10-06.md)
- `pest_cooldown` (Moth) had `per` 0 because its lore has no "+N"; gen_hunting.py now gives it 0.5 s a level (text
  "by {v1}s"), so `attributeLevel(p, "value:pest_cooldown")` returns seconds; the garden subtracts them.
- Test: `HuntingPureTest.gardenPestAttributesHaveNumbers`.
