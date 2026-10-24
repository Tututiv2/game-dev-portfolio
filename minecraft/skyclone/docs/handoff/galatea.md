# galatea - hand-off

New module (wave 5): the islands of the Foraging / fishing updates the game was missing, as pure generators,
plus what lives on them. Entry class `com.skyclone.mod.galatea.GalateaModule`.

## Wave 5 (2026-10-06)

### Done (verified how)
- `bash tools/check.sh galatea` -> **CHECK OK** (187 files).
- **9 JUnit tests pass** (javac + JUnit launcher locally; not through gradle): `FrogMathTest` (6: species/ids match
  storage's frog sacks, conditions, tier table + pity, titles, Lotus denominations = the crafting recipes, a seeded
  10,000-cast roll) and `GalateaIslandsTest` (3: both islands generated with the shipping seed and flood-checked,
  fairy-soul counts, zones, mob areas, portals, every npcs.json row's anchor exists).
- `tools/preview.sh` -> **RESULT: OK** for both generators, 0 unreachable anchors, PNGs looked at.
- **Not run live** (no server this wave).

**LOTUS ATOLL** (`gen/LotusAtollGenerator`, island + warp `lotus_atoll`, ~290x280, research 03 §21): a tropical ring of
land round a lily-choked lagoon. Atoll Landing (spawn plaza with a fountain, Professor Quennell's research station,
the field lab, a cottage, a frog-gear stall, two portal arches: `portal.hub` and `portal.backwater_bayou`), a 50-block
pier with a fishing platform out to a cherry-wood shrine islet, the Lotus Highlands (a 28-block hill with a stone
crown ring, reached on foot or by Hobb's launch pad), the Lotus Eater's Cave (a tunnel into the hill to a glowing
froglight chamber with the wishing pool), Shellback Tunnel (ref. Tewtil Tunnel) through the east ridge to Tidepool
Cove. Palms, cherry/jungle/azalea, pink petals, peonies, coral and sea pickles in the lagoon, a dense lily bloom in
the west lagoon. **6 fairy souls** (research count). Fishing anchors `fishing.spot.pier|islet|cove`,
`fishing.hotspot.1-2` (research: 2 hotspots). Zones (all prefixed so a keyword match on `lotus_atoll` works
everywhere): `lotus_atoll`, `lotus_atoll_landing` (SAFE), `_lagoon`, `_highlands`, `_cave`, `_tunnel`, `_cove`.
Areas `lagoon_water`, `lily_bloom`, `cave_pool`, `town`. Warp requirement dial `galatea.atoll-requirement`
(default `skill:fishing:10`), so **the hub ship's "Lotus Atoll" entry is now charted**.

**TORRENT CANYON** (ref. Torrhus Canyon; `gen/TorrhusCanyonGenerator`, island + warp `torrhus_canyon`, ~355x300,
research 03 §24): a sandstone canyon with striped terracotta walls. Canyon Camp on the south rim (spawn: journal hall,
safari office, contest pavilion, well, arches `portal.hub` + `portal.moonmarsh`), the gentle south slope down to
Torrent Springs (a 50x17 pool: Spring Shallows ring 4 deep round Spring Depths ~26 deep), the sealed stepped Desert
Temple (walkable to its top), Hotspot Haven (palm-ringed pool, `fishing.hotspot.1-2`), Ant's Cave (tunnel under the
south wall to a chamber), the Spring Path (the one gentle ramp up the steep north wall) to Torrent Heights: ~43
spiral trees (red core, pale corkscrew, pink crown; anchors `tree.helix.N`, area `helix_grove`), six honey hives,
the Critter Safari gate. **12 fairy souls**. Zones `torrhus_canyon`, `_camp` (SAFE), `_springs`, `_shallows`,
`_depths`, `_path`, `_heights`, `_ants`, `_temple`, `_haven`, `_safari`. Warps `torrhus_canyon` (dial
`galatea.canyon-requirement`, default `skill:foraging:12 & hotf:4`), `torrhus_springs`, `torrhus_safari` (each + its
travel scroll). Fishing spots `fishing.spot.springs|haven`.

**Trophy Frogs** (`FrogMath` pure + `Frogs`): 12 species x Bronze/Silver/Gold/Diamond = **48 items** (reference ids
`COMMON_FROG_BRONZE`... `PUDDLE_JUMPER_DIAMOND`, the ids storage's frog sacks already list; soulbound). Every catch on
the Atoll rolls `galatea.frog-chance` (0.10, TUNE) x (1 + Trophy Chance/100); the species is picked among those whose
condition holds (NEU card text): Common anywhere, Leap mid-air, Exploding among >=4 lily pads, Reality Hopper wearing
Froggles, Wetlands in the Atoll rain, Sea with your head under water, Bullfrog in a Red Sweater, Blessed during a
Frogcoin blessing, Tree standing on leaves, Cave in the cave, Highlands on the crown, Puddle Jumper while flying (and
from the Puddle Jumper sea creature, NEU odds). Quality table = trophy fishing's (25 / 2 / 0.2 %, pity Gold 100 /
Diamond 600, dials). **Professor Quennell** (ref. Researcher Ribery): ledger, collector titles
(Novice/Adept/Expert/Master Frog Collector = every species at Bronze/Silver/Gold/Diamond), the title-gated shop at the
NEU prices (sacks, Applicant's Statement..PhD's Grimoire, the Atoll travel scroll, Ribbit the Fish), and **Release
Frogs -> Lotus** (rarity 4/8/16/32/64 x quality 1/4/16/64 Lotus, paid as Diamond/Gold/Silver Lotus/Lotus at the
recipe ratios 80/80/20) - the module's LOTUS / LOTUS_SILVER / LOTUS_GOLD / LOTUS_DIAMOND source (the fishing
module's sea creatures drop Lotus too). Seven field researchers each explain their species' trick and your best
quality. Requirement prefix **`frogs`**: `frogs:title:<1-4>`, `frogs:caught:<ITEM>`.
- **Frogcoins**: right-click the cave pool with one (or click The Wishing Waters): one random 10-minute blessing on the
  Atoll (Fishing Speed +15 / Sea Creature Chance +3 / Treasure +2 / Trophy Chance +10 / Fishing Wisdom +10, TUNE).
  Every 4th coin wakes **the Lotus Eater** for an hour (dials): he trades the Lily Pad Minion XII stone for a
  Diamond Lotus (NEU).
- **Atoll rain**: calendar event `lotus_atoll_rain` (60-minute cycle, 20 minutes of rain = research's 40/20), +25
  Fishing Speed on the Atoll (dial), real rain shown per player, chat line on start, sidebar timer.
- **Launcher Hobb**: the launch pad throws you up to the Highlands crown.

**Torrent Canyon life**: mobs **Parched** (Lv58 husk, floor), **Grizzly Bear** (Lv101 polar bear, Heights woods, one
at a time), **Queen Ant** (Lv69, x3 silverfish, Ant's Cave) and her **Soldier Ants** (ours); levels from NEU's
bestiary (`foraging_3`), stats TUNE (no NEU cards). 5 spawn rules. **Canyon Journal** (ref. Helia's chapters): 4
chapters / 18 tasks (zones, NPC talks, kills, catches in the canyon, 1,000 Spiral Logs gathered), each chapter pays
Foraging XP + coins (TUNE, dial `galatea.journal-reward-scale`); sidebar shows the next task on the canyon.
**The Ant Overseer** sells the six Log Minion XII stones (2,000,000 coins + 1,024 enchanted logs, NEU). Steward
Odalys opens `/foraging` (the Starfall Contest lives there), Hunter/Forager/Diver open `/hunting` `/foraging`
`/fishing` when those exist, the Safari clerk and receptionist explain the Safari (runs `/safari` with Hunting XV if a
module registers it). Travel scrolls: Torrent Canyon (8x64 Spiral Logs + 16 Ench. Ender Pearl, Helix I), Torrent
Springs (Ruby Veilshroom, Veilshroom I) - NEU recipes; Safari Entrance and Lotus Atoll scrolls (the latter from the
professor at Adept). Requirement prefix **`galatea`**: `galatea:scroll:<id>`, `galatea:journal:<chapter>`.

### Commands added
Player: `/galatea` (`/lotusatoll`, `/atoll`) travel + overview menu · `/frogs` (`/trophyfrogs`, `/frogledger`) ·
`/canyonjournal` (`/torrhus`, `/journal`). NPC clicks open the rest (professor, release, shops, journal).
Admin `/sb galatea`: `info` · `frog <p> <SPECIES> <tier> [n]` · `title <p> <0-4>` · `bless <p> [speed|creatures|treasure|trophy|wisdom]`
· `wake <p>` · `rain on|off|clear` · `journal <p> reset|complete` · `scroll <p> <id> [remove]` · `simulate <p> <casts>`
(frog odds at that player's spot) · `where <p>` (zone + eligible species) · `reset <p> confirm`.

### Dials added
`galatea.atoll-requirement`, `galatea.canyon-requirement` (live: warps re-register on change), `galatea.frog-chance`,
`galatea.frog-gold-pity`, `galatea.frog-diamond-pity`, `galatea.rain-fishing-speed`, `galatea.blessing-minutes`,
`galatea.lotus-eater-coins`, `galatea.lotus-eater-minutes`, `galatea.launch-cooldown-seconds`,
`galatea.journal-reward-scale`, `galatea.trophy-frogs`, `galatea.announce-diamond-frogs`,
`galatea.puddle-jumper-drop`, `galatea.rain-weather`, `galatea.sidebar`.

### Self-tests added
`galatea.items-and-mobs`, `galatea.layouts` (spawn, portal.hub, 6 / 12 souls, island zone, every NPC anchor),
`galatea.warps` (incl. hub / backwater_bayou / moonmarsh for the second arches), `galatea.frog-math`,
`galatea.shops`, `galatea.menus-build`.

### Still left
- **The Moonglade Marsh itself is foraging's `galatea` island** (named "The Moonmarsh"); this module did not touch it.
- Exploding Lily Pads (push pads together to size 8) are simplified to "stand among >= 4 lily pads"; wormholes are
  "wear Froggles"; Lotus Highlands' frog is "on the crown zone". No Trophy Frog sack logic here (storage's).
- Critters (Lotusfish, Tewtil, Seashine..., the canyon's 20+ critters), nets/lassos/shards: the hunting module's.
  Parched etc. drop no shards yet (wire `Features.Hunting.addShards` once shard ids are fixed).
- Spring Depths' Respiration / Pressure Resistance rule, the Desert Temple puzzle (it is sealed), the Critter
  Safari instance, honey-hive harvesting, Desert Whispers / Safari Essence journal rewards, Researcher Gilbert in
  the Bayou (the Fishing X unlock is the warp requirement instead).
- Fairy-soul "exchange" is progression's; it picks the `fairy_soul.*` anchors up automatically.

## Fix (2026-10-06, PLAYTEST open #1: drowning at the Torrent Springs spot)
**Cause:** `fishing.spot.springs` (-7.5, 68, 25.5) stood on a one-block bank over the Springs' 4-deep ring, and the
bank was a **two-block lip** above that ring's bed - so a Sprawl's / Haggard's knockback put the player on the bottom,
head under, with no way out except swimming up. A TestPilot client never swims up and drowned (3 repros, half the
purse each). The Haven, the Atoll's islet (a W+2 island straight into 13-deep lagoon) and the Atoll cove had the same
shape. **Fix, in the generators (both islands' `version()` 1 -> 2, so they rebuild on next boot):**
- `GKit.wadingShelf`: every water column within N blocks of a bank spot is **1 deep** (a standing player's eyes are
  above the surface), the bed then falls back to the pool's own depth (Springs r10, Haven r6, Cove r5, all 1/block;
  islet sandbar r11, 3/block so the lagoon stays deep beyond it). Sea creatures still spawn at the bobber and come
  to you; the fight is on the shelf.
- `GKit.beachStep` + `easeAroundSteps`: the shelf's edge against dry land becomes a dry step at water level and the
  land touching it comes down to the bank height, so **shelf -> step -> bank is one-block steps** (no swim, no
  two-block climb). The islet got a beach ring (W+1) and a water-line step (W) round the W+2 shrine top.
- The pier spot is a railed deck and is unchanged. Spot coordinates are unchanged (Springs still -7.5, 68, 25.5).
- **New JUnit `GalateaIslandsTest.bankFishingSpotsCannotDrownYou`**: for springs/haven/islet/cove, no water within 5
  blocks is more than 1 deep and every such column walks back to the spot in <=1-block steps. Verified it FAILS on
  the old geometry ("-8,20 water 4 deep" ... - the playtest report exactly) and with the steps disabled ("no
  one-block way back"); passes now. 10/10 galatea JUnit, `check.sh galatea` CHECK OK, `preview.sh` RESULT OK on both
  islands (0 unreachable), crops of all four spots looked at.
- Not touched: TestPilot (its "hold jump while the head is under water" is the other half of the suggestion and
  lives in `tools/testpilot`, outside this module). Not re-run live.

## Requests (the integrator reads this)
1. **core / ModuleList**: add `com.skyclone.mod.galatea.GalateaModule` (anywhere after fishing/foraging/hub so the
   `hub`, `backwater_bayou`, `moonmarsh` warps exist for the second arches; enable order does not matter otherwise).
2. **fishing**: pools on islands `lotus_atoll` and `torrhus_canyon` (every zone there starts with the island id, so a
   keyword match on it also works). The Galatea pools that were moved into the Bayou (`lagoon` = the Lotus Atoll
   creatures) belong on `lotus_atoll` now; the Torrhus pool (Haggard, Brineling, Sprawl, Torrid, Silkbreeze, Giant
   Isopod, NEU bestiary levels 44/58/71/85/105/186) on `torrhus_canyon` (spot `fishing.spot.springs`; Spring Depths
   zone `torrhus_canyon_depths` if you want depth-only creatures). Hotspot anchors `fishing.hotspot.1-2` on both
   (research: 2 each). Fishing Speed cap 250 on the Atoll (research 01). `PUDDLE_JUMPER_<TIER>` items exist now: if
   you restore the Puddle Jumper's frog drop, set `galatea.puddle-jumper-drop false`. Your FishCaught event drives the
   frog roll - keep firing it for every catch.
3. **foraging**: Torrent Canyon is a separate island now. Your `canyon` warp / `torrent_canyon` zone on the Moonmarsh
   use the same name - please either retarget warp `canyon` to island `torrhus_canyon` (`spawn`) or rename the mesa.
   The spiral trees here stand on `tree.helix.N` anchors inside area `helix_grove` (stripped_mangrove core +
   stripped_birch corkscrew + cherry leaves); add `torrhus_canyon` to the Chopper's islands to make them fellable
   and count Canyon Starfall points here.
4. **menus**: Fast Travel entries for `lotus_atoll`, `torrhus_canyon`, `torrhus_springs`, `torrhus_safari`
   (TravelLayout EXTRA), and a SkyBlock Menu path to `/galatea` and `/frogs`.
5. **progression**: bestiary families `parched`, `grizzly_bear`, `queen_ant`, `soldier_ant` (island Torrhus
   Canyon); fairy souls are the usual `fairy_soul.*` anchors.
6. **accessories / storage**: `frogs:title:<n>` now resolves - APPLICANT_STATEMENT..PHD_GRIMOIRE (1-4) and the
   frog sacks (1/2) can carry the reference's "Requires X Frog Collector".
7. **hunting**: critters for both islands; a `safari` command would be run by the Safari gate NPCs.

## Known risks
- Frog odds, Lotus release values, blessing stats, journal rewards and all four mobs' stats are ours (TUNE) - no
  published tables.
- `Player#isOnGround` is client-reported; a cheater could fake "mid-air" for Leap Frogs (cosmetic-value only).
- Both islands are big (≈1.4M / 1.2M blocks): first boot builds them at core's 12 ms/tick, a few minutes each.
- The Atoll rain sets per-player weather; another module that also sets player weather would fight it (dial
  `galatea.rain-weather`).
