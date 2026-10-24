# crimson - hand-off

Package `com.skyclone.mod.crimson` (entry `CrimsonModule`), island generator in `com.skyclone.mod.crimson.gen`
(pure, no Bukkit). Data in `plugin/src/main/resources/data/crimson/` - **all generated**, never hand-edit:

| file | from | what |
|---|---|---|
| `items.json` (111: + X/Y/Z, wave 5) | `tools/gen/crimson/gen_items.py` <- research `items.json` + NEU | 100 Leviathan-line armor pieces (Crimson/Aurora/Terror/Fervor/Hollow x Basic/Hot/Burning/Fiery/Infernal x 4), `RUNIC_STAFF`, `HOLLOW_WAND`, `TORMENTOR`, 5 `KUUDRA_*_TIER_KEY` |
| `recipes.json` (4) | same | 4 keys of a tier -> 1 key of the next (research 04 §4.3) |
| `mobs.json` (25), `spawns.json` (25) | `tools/gen/crimson/gen_mobs.py` <- research 03 §15 + NEU drop pages | 20 mobs + 5 mini-bosses, drop tables, spawn rules |
| `npcs.json` (14) | `tools/gen/crimson/gen_npcs.py` | our NPCs, skins from NEU, our dialogue |
| `quests.json` (50), `shop.json` (39), `factions.json`, `dojo.json` | `tools/gen/crimson/gen_tables.py` | quest pool, faction shops, ranks, Dojo trials/belts |

## What works (verified how)

Verified offline only (the dev server is the integrator's): `bash tools/check.sh crimson` -> **CHECK OK**;
**7 JUnit tests pass** (`plugin/src/test/java/com/skyclone/mod/crimson/`, run with plain javac + the JUnit
launcher from the gradle cache: ranks vs research, quest board determinism/eligibility, Dojo grades/belts and
belt SkyBlock XP vs research 02, prestige ladder, the whole island built + flood-checked); the generated JSON
was parsed with **core's own `DefJson.readItems/readRecipes`** + MobDef Gson: 108/108 items, 4/4 recipes, 25/25
mobs, zero warnings, every material/entity/equipment name valid; `bash tools/preview.sh
com.skyclone.mod.crimson.gen.CrimsonIsleGenerator` -> **WALKABILITY OK**, 0 unreachable anchors, 0 floating
non-decorative blocks, all 150+ block names valid (PNGs in `previews/crimson_isle/`).

- **The island `crimson_isle`** (NETHER environment, ~490 x 450, built in ~0.7 s, 4.4M blocks). District map
  (north is -Z):
  ```
            Mystic Marsh        BLAZING VOLCANO (crater lava)      Dojo      Forgotten Skull -> Leviathan dock
       Cinder Shore (lava hut)   (Magma Chamber under it)
   DRAKEHOLD (barbarians)        CRIMSON FIELDS + crossroads        CARMINE SPIRE (mages)
                                 STRONGHOLD (spawn, hub portal)  Wraith Yard
   The Dukedom  Burning Desert   Smoldering Tomb (+crypt)   Ruins of the Twin Pyres   Courtyard
  ```
  Stronghold: black fortress, 4 corner towers (climbable, one has a soul on top), keep, obelisk plaza, SAFE
  courtyard, `portal.hub` on its west side. Volcano: 62-block cone with a switchback road to a rim lookout over
  a lava crater, magma streaks, 40 quartz veins + 16 glowstone outcrops; a tunnel from its south foot descends
  to the **Magma Chamber** arena (Magma Colossus). Two walled towns with real interiors (halls, taverns, forges,
  banks, quartermasters, homes, market stalls, gates; a bone dragon skull in Drakehold's square, gold-capped
  spires and a throne hall in Carmine Spire). Sunken Smoldering Tomb with a crypt below, the Forgotten Skull (a
  walk-in bone skull) with a pier to Leviathan's Hollow, a Japanese-style Dojo (arena + master's hall), the
  ruined Dukedom with tents and a throne, the Courtyard garden, ruins of the Twin Pyres, marsh mushrooms,
  desert dunes/fossils, fungi everywhere, lamps along every road. 29 fairy souls (ledges, tower tops,
  upstairs rooms, crypt, crater rim, fossils...). Zones for every district (towns, Dojo and the Stronghold
  courtyard are SAFE). Warps: `crimson_isle` (island requirement = dial, default `skill:combat:22`, research
  03 §15), `crimson_drakehold` (`faction:barbarian:1000`), `crimson_carmine` (`faction:mage:1000`),
  `crimson_tomb` (`faction:any:3000`).
- **Mobs** (research HP/levels, NEU coins/combat XP/drops): blazes (4 kinds incl. the 30M Aeon-Old Blaze),
  Flares, Emberwing Bats, Tinderlings, Pack Magma Cubes, Dive Ghasts (night), Magma Cube Riders, Flaming
  Spiders, Wither Skeletons/Spectres, Mushroom Bulls (charge), Ashen Knights, Fire Mages (fire bolts),
  Barbarians, Barbarian Brutes, the Vanquisher (rare call on any isle kill, dial). Blazes carry
  `slayer: blaze`, Flaming Spiders `slayer: spider`; `extra.types/mobTypes` set for weapons/accessories.
  Extra Flares pour down the volcano during eruptions.
- **Mini-bosses** on their anchors via spawn rules with the research respawns (2 min, Magma 5 min - dials):
  Edgewraith 50M (lunges), Rogue Pyromancer 70M (fire bolts + flame ring), Duke Brakkus the Tenth 70M
  (ground slams), Cindermaw 50M (fireballs; splits two Smoldering Blazes at half health), Magma Colossus 200M
  (giant cube, slams + flame ring). Kills: reputation (rival faction's champion pays more), 30 bits for the
  Colossus and the Cindermaw (research 05), kill counts, island-wide announcement, respawn timers in
  `/minibosses`. Interim drops: Crimson Essence/teeth/heavy pearls (TUNE, until the Leviathan hunts exist).
- **Factions**: pledge to the Mages or the Barbarians (leaders, `/faction`); reputation per faction; ranks
  Outsider/Ally/Trusted/Honored/Champion at 0/1,000/3,000/7,000/12,000 (research) with rank-up boxes and 75
  SkyBlock XP per faction (research 02); switching costs coins + a cooldown (dials). Requirement resolver
  **`faction:<mage|barbarian|any>[:<rep>]`** (plural names accepted, e.g. the minions' `faction:barbarians:500`)
  and **`dojo:<belt>` / `dojo:points:<n>`**.
- **Daily quest board**: 5 quests/day (dial) per player, rolled deterministically from uuid + UTC day (reset
  hour dial) out of 47: slay (18), champion hunts (5), deliveries (14, handed in to your leader or in the
  menu, inventory + sacks), lava fishing (2, from `FishCaught.lava`), Dojo scores (8). Faction-specific raids
  (barbarians hunt the mages' knights and the Pyromancer; mages purge the Dukedom). Clearing the board pays a
  bonus. Sidebar line on the isle.
- **Faction shops** (quartermasters + `/factionshop`): faction minion recipes' first tier, Leviathan keys, the
  Basic Leviathan-line armor of the faction's sets (Trusted), the faction weapons (Honored/Champion) - coins +
  materials, checked again after the confirm, items taken before coins and refunded if the coins fail.
- **Dojo** (`/dojo`, Sensei Kiyoshi): 7 trials, one player in the arena at a time, all our own designs:
  Force (hit wandering dummies, crits score more), Stamina (client-side glass walls with a gap sweep the
  arena), Mastery (targets count down and turn green - strike then), Discipline (STRIKE vs HOLD dummies),
  Swiftness (client-side lit tiles, faster = more), Control (keep your eyes on a blinking shade), Tenacity
  (simulated embers, dodge). Best per trial (max 1,000, graded S..F) sums to belts White..Black (thresholds
  TUNE, SkyBlock XP per belt research 02); belts are given via `Sky.loot()` if the accessories module
  registered them. Leaving the arena, quitting or dying ends the run cleanly.
- **Leviathan-line armor**: 100 pieces with research stats/requirements (Combat 22/27/32/37/42), gem slots,
  colours/skins, reforgeable/enchantable/recombable; tiered set bonuses implemented per reference numbers,
  scaled by pieces/4 and a dial: **Dominion** (melee stacks -> swing range/ferocity/damage, cleave at 10),
  **Arcane Energy** (magic stacks -> sneak for 3-5 homing missiles), **Hydra Strike** (arrow stacks -> damage,
  phantom arrows at 10), **Fervor** (stacks -> sneak Ground Pound scaling with EHP) + **Intimidate** (aggro pull),
  **Spirit** (allies' attacks feed stacks). Stacks on the action bar.
- **Weapons**: Aurora Staff (Runic Zap: beam, 10,000 base, falls off past 10 blocks), Hollow Wand (Spirit Spark
  10 stacks, Ichor Pool 30 stacks heals the group), Tormentor (+50% damage taken while held; Chain of Agony
  toggle chains melee to 5 nearby mobs).
- **Armor prestige** (Forgemaster Ignatius, `/prestige`): Basic -> ... -> Infernal for the reference's
  per-piece costs (Crimson Essence + Leviathan teeth); keeps reforge/enchants/books/recomb/gems, resets stars.
  Missing essence/teeth can be paid in coins at the bazaar price (dial; needed because essence goes into the
  reforges pouch, which we cannot read).
- **Gathering**: break handler (priority 500) inside the generator's `gather.volcano / gather.desert /
  gather.marsh` areas: netherrack, nether quartz ore, glowstone, red sand, mycelium -> NETHERRACK, QUARTZ,
  GLOWSTONE_DUST, SAND:1, MYCEL via `Sky.loot()` (MINING source, so collections count), Mining XP (research
  02: 0.5/5/7/3, mycelium TUNE 5), Mining Fortune, `SkyEvents.Gather`, blocks deplete and grow back (dial).
- **Volcano eruptions**: calendar event `crimson_volcano_eruption` (real time, every 135 min for 15 min, TUNE
  within research's "every 2-2.5 h"): announcements, crater lava/smoke particles, extra Flares.
- **Menus**: `/crimson` hub (standing, faction, quests, shop, mini-bosses, Dojo, prestige, isle guide,
  Leviathan info), faction/pledge/ranks, quests, shop (paged), mini-bosses, Dojo, prestige (paged).

## Wave 3 (2026-10-04)
Verified offline: `bash tools/check.sh crimson` -> CHECK OK; **11 JUnit tests pass** (4 new in
`CrimsonPearlsTest`); `tools/preview.sh` -> **RESULT: OK** (all 9 pearl spots + the new soul reachable from
spawn, 0 non-decorative floating blocks). **Not run live** (no server in this phase).
- **Crimson Essence in the pouch now pays.** Picked-up essence goes straight into the reforges module's pouch
  (`Features.Essence`), so prestige, shop costs and deliveries - which only counted inventory + sacks - could
  never see what players actually owned and fell through to coins. `CUtil.have/take` now include the pouch
  for any `ESSENCE_*` id (pouch first, then inventory, then sacks) and `CUtil.refund` puts essence back in
  the pouch. (Request 1 answered by the API that now exists.)
- **Belly of the Beast + the Matriarch (Heavy Pearls).** Generator v2 (bumped from 1): a dead leviathan half
  sunk in the Mystic Marsh at (-140, -152) - a ribbed, bulging flesh hump with an open jaw facing the marsh
  lane (new lane from the hut road). Inside: a 27-wide stomach shaft from 12 below the marsh to a dome 24
  above it, shroomlight glands, a 3-wide ledge spiralling two full turns from the floor through the jaw's
  level to a top landing, and the Matriarch (a glowing sac with a ring of eyes and tendrils) hanging from the
  roof. Anchors `crimson.belly`, `crimson.matriarch`, `crimson.pearl.1..9` (3 floor, 4 spiral, 2 landing),
  zone `crimson_belly`. `Pearls.java`: each player gets `crimson.pearls-per-day` (3) pearls a day on a seeded
  per-player/per-day shuffle of the spots; each pearl is a glowing spinning ItemDisplay **hidden by default
  and shown only to its owner** (so nobody steals anyone's), plus particles; walking into it gives
  `HEAVY_PEARL`. Action bar "Heavy Pearls 1/3" inside the shaft, a TextDisplay over the Matriarch.
  **Matriarch's Perfume** (`MATRIARCH_PARFUM`, materials' item, sold in the bits shop) is now usable:
  right-click releases `crimson.perfume-pearls` (3) more, up to `crimson.perfume-uses-per-day` (4, the
  reference lore), and can never be drunk as stew. `/crimson` slot 33 shows today's pearls + directions.
  The interim 5% Heavy Pearl drop was removed from the mini-bosses (`gen_mobs.py`), since the Matriarch is the
  source now, as in the reference (teeth/essence boss drops stay).
- **Leviathan clear on the armor** (reference `KUUDRA_COMPLETION`): in `postEnable`, when a `kuudra:` resolver
  answers, every Leviathan-line piece gets `kuudra:<its tier>` (Basic piece = a Basic clear ... Infernal =
  an Infernal clear), Aurora Staff / Hollow Wand `kuudra:basic`, Tormentor `kuudra:infernal`. Dial
  `crimson.armor-needs-kuudra` (on) takes it off again live; without the kuudra module nothing is added (an
  unknown prefix fails closed and would lock the armor).
- **All five Leviathan keys in the faction shops** (only the Basic key was sold, so Hot+ paid chests were
  unopenable): research 04 §4.3 coins (200k/400k/750k/1.5M/3M) + 2/6/20/60/120 enchanted mycelium (mages) or
  red sand (barbarians) + 2 nether stars, each from the rank its tier needs (Outsider/Ally/Trusted/Honored/
  Champion = 0/1k/3k/7k/12k rep).
- **Leviathan Hunt faction quests** (reference "Kuudra" quests): `hunt_leviathan` (any tier, medium),
  `hunt_leviathan_hot` (Hot+, hard), `hunt_leviathan_burning` (Burning+, elite). Progress is read from the
  kuudra module's clear records through its resolver (`kuudra:<tier>:<n>`, binary-searched) against a
  baseline taken when the board rolled - so a hunt counts wherever it ran, with no shared code. Checked every
  5 s for every online player; only offered when the resolver exists. Boards already rolled today keep their
  quests; the new ones appear from the next reset.
- Admin: `/sb crimson pearls <p> [show|reset|collect|perfume]`, `/sb crimson kuudrareq`.
- Self-tests: `crimson.pearls` (lair anchors, >=6 spots, items exist, roll pays exactly the allowance),
  `crimson.kuudra-requirements` (5 sample pieces carry their tier's clear when kuudra is up).
- Dials: `crimson.armor-needs-kuudra` true · `crimson.pearls-per-day` 3 · `crimson.perfume-pearls` 3 ·
  `crimson.perfume-uses-per-day` 4.
- **Live check list for the integrator**: `/warp crimson_isle`, walk the marsh lane north-west into the jaw,
  climb the spiral (pearls glow only for you; `/sb crimson pearls <p>` lists where), pick three up; buy a
  perfume in the bits shop and right-click it; `/sb crimson kuudrareq`; open `/fshop` at Ally+ for the Hot key.

## Wave 5 (2026-10-06)
Verified offline: `bash tools/check.sh crimson` -> **CHECK OK (206 files)**; **20 JUnit tests pass** (7 new in
`CrimsonWave5Test`, run with javac + the JUnit platform launcher from the gradle cache against the shipping JSON);
`tools/preview.sh com.skyclone.mod.crimson.gen.CrimsonIsleGenerator` -> **RESULT: OK** at **generator v4**
(every anchor + `portal.hub` reachable, 4 floating components, all leaves/vines/lanterns; iso.png looked at).
Data regenerated by the three gen scripts; `mobs.json` diffed against the old file: only the X/Y/Z lines changed.
**The server was NOT started; nothing below has run live.**

### Done
- **Mini-bosses pay every real contributor, not only the last hit** (`BossLoot.java`). Core's MobKill pays the
  killer only, so a crowd fight on a 50M-200M world boss paid one player. Now every hit a player lands on a crimson
  mini-boss (and on a Cindermaw's split blazes, which count toward their Cindermaw) is booked per fight from
  `SkyEvents.Damage` at MONITOR (capped at the boss's remaining HP). On death every booked player who dealt
  `crimson.boss-loot-min-percent` (1%, TUNE) of its health, is online and within 64 blocks shares the kill: the
  boss's own drop table rolled with THEIR Magic Find (`Sky.loot().magicFind(p, def)`), its coins and Combat XP
  (core already paid the killer), the kill count, reputation, bits and BOSS-quest progress (the research: "all
  count toward faction daily quests"). Each sharer gets a box with their damage and place; the island-wide DOWN!
  line names the top three. `keepSingle` carries the ledger to the copy that takes over a fight. Owned bosses
  (`spawn <mob> <player>`) still pay their owner only. Dial `crimson.boss-shared-loot` off = the old killer-only.
- **Faction-specific mini-boss loot** (`CrimsonRules.spoils`, data `factions.json boss_loot`, all TUNE - the
  research says only "faction reputation, themed armour/weapon pieces, essences"). Pledged sharers roll:
  their faction's Leviathan-key material from ANY mini-boss (mages 1-3 Enchanted Mycelium, barbarians 1-3 Enchanted
  Red Sand, 50%); the **rival champion's spoils** when they bring down the champion their faction hunts (Duke for
  mages: Leather Cloth + 10-20 Crimson Essence + 50% Leviathan Teeth + 10% Corrupted Nether Star; Pyromancer for
  barbarians: Spell Powder + the same); and a **second roll at the Leviathan keys** (6% Basic / 4% Hot, the NEU
  key lines) when a BOSS quest for that boss was open on their board. All through `Sky.loot().roll` (Magic Find,
  RARE DROP lines, sacks, collections). `/minibosses` shows each player their own spoils for each boss.
- **Fixed: rival-champion reputation went the wrong way round.** `Bosses.onKill` compared the boss's OWN faction
  with the killer's, so a barbarian killing his own Duke was paid the rival bonus and a mage hunting the Duke was
  not. `CrimsonRules.rivalChampion` reads `rival_boss` directly; unit-tested both ways.
- **Attribute shards through `Features.Hunting`** (the hunting module exists now and already rolls every
  crimson mob's mapped shard for the killer on MobKill): non-killer sharers of the Duke roll
  `SHARD_BARBARIAN_DUKE_X` at their own Charm Chance (`attributeLevel(p, "stat:charm_chance")`) via `addShards`.
  The attributes that land in OUR systems are read with `attributeLevel(p, "value:<key>")` (`Attrs.java`):
  **Matriarch Cubs** (+x% chance a Heavy Pearl comes with a second one), **Crimson Serendipity** (Vanquisher odds
  x(1 + x/100)), **Essence of Crimson** (+x% of every natural Crimson Essence gain, into the pouch via
  `Features.Essence`; `ItemGain` at LOWEST, before reforges absorbs it), **Ghastly Distraction** (mining a tomb
  Opal pacifies non-slayer mobs for x s: targets cleared, re-targeting and their hits cancelled, your own attack
  ends it).
- **Pets**: the Leviathan pet's **Crimson Tithe** (`abilityValue(p, "kuudra_crimson", "a")`, "+a% crimson
  essence") now applies to essence earned outside the hunt's chests (kuudra already applies it to its chests -
  source DUNGEON is skipped so it never pays twice). The pet's Wither Lure (Vanquisher odds) is the pets module's
  own `kill_bonus` on our dial - untouched.
- **Accessories: Synthesizers** (their items, hook "crimson"). Hold one and `/synthesizer` (`/synth`) or click it in
  `/crimson` (slot 29): feed **X / Y / Z** from inventory + sacks (left 1, right 10, shift all), one stat point
  each up to the reference lore caps (v1 X->30 Health; v2 + Y->30 Defense; v3 40 Health / 40 Defense / Z->60
  Intelligence). Stored on the item (`extra.synth_x/y/z`), applied by an `ItemStatContributor` (so they count
  wherever it is worn and show in its lore: "Current Bonuses: 12/30 Health"). Items are taken before the stack is
  written; a stack that changed under the menu is refused, a failed write refunds. 1 item = 1 point is TUNE.
- **X, Y, Z registered here** (EPIC paper "a shape that only knows one/two/three dimensions", npc 20/30/50 from the
  research API). Sources are ours (TUNE): X 4% from every regular isle mob, Y 2-4 from every mini-boss (so every
  sharer gets some), Z 8-16 from a Vanquisher and 4-8 from the Aeon-Old Blaze. Accessories' recipes (320 X ->
  v1, 512 Y + v1 -> v2, 512 Z + v2 -> v3) now have their ingredients.
- **Generator v4** (blocks change: the island is re-placed on the next boot):
  - **Opal veins in the Smoldering Tomb** (the reference's tomb opals; ROUGH_OPAL_GEM's own model sits under the
    blaze slayer): 16 2x2 white-glass veins set into the pit and crypt walls between the pillars (64 blocks, every
    one faces open air - unit-tested), area `gather.tomb_opal`. Mined by our break handler with mining's own opal
    numbers (Breaking Power 7, 70 Mining XP, 2 Gemstone Powder via `Features.Mining.addPowder`, Gemstone Fortune),
    they turn to blackstone and grow back (`crimson.gather-regen-seconds`).
  - **Sulphur Ore** (sponge, research 07 "Crimson Isle: Sulphur Ore"): 18 deposits on the volcano's lower flanks and
    3 on the shore of each lava pool (hut, desert, both field pools) within 2 blocks of the lava - which is what the
    fishing module's Sulphur Skitter looks for. Mines into `SULPHUR_ORE` (materials' item; the Sulphur collection)
    with Mining Fortune, 10 Mining XP (TUNE).
- Admin: `/sb crimson bossloot <p> <boss> [damage%]` (pay one share as a non-killer: loot, rep, spoils, quest),
  `ledger [boss]` (damage booked on live fights), `synth <p> <x|y|z> <n>` (set what a held Synthesizer has eaten).
- Self-tests: `crimson.boss-loot-table`, `crimson.faction-spoils-rules`, `crimson.boss-damage-ledger`,
  `crimson.synthesizer`, `crimson.attribute-hooks`.
- Dials: `crimson.boss-loot-min-percent` 1.0 · `crimson.boss-shared-loot` true · `crimson.faction-spoils` true ·
  `crimson.synthesizer` true.
- **Live check list for the integrator**: boot (island rebuild for v4); two players hit the Edgewraith with
  `/sb dial set crimson.boss-hp-scale 0.0005`, the one who did not land the kill gets the DOWN! box with damage,
  loot and spoils; `/sb crimson bossloot <p> barbarian_duke 10` as a mage shows "(rival champion!)"; walk into the
  tomb pit, mine a white-glass vein with a Titanium Drill (BP 7) and with a Diamond Pickaxe (refused); mine a
  sponge on the volcano flank; hold a Synthesizer + `/sb crimson synth <p> x 12` -> lore 12/30 Health and +12
  Health in `/stats` when worn; `/sb hunting level <p> matriarch_cubs 10` then collect pearls.

### Still left (wave 5)
- **Sulphur/Opal break SPEED**: our handler breaks them at vanilla speed (glass/sponge are instant) - only
  Breaking Power is checked. Mining's dig engine owns block strength (opal 3,000). Request 11.
- Spoils numbers, X/Y/Z sources and the 1-item-1-point Synthesizer rate are TUNE (no research numbers).
- A Plhlegblast-style pool zone (fishing's Phlegmburst needs a zone named "phlegm") and `geyser*` anchors were not
  added; fishing falls back to all Crimson lava / finds geysers in the crater, so nothing is broken.
- The Synthesizer cannot be fed while worn (only held): the necklace sits in accessories' equipment storage,
  which only they can write. Request 12.

## What is partial / not built
- **Heavy Pearl numbers** (3 a day, 3 per perfume) are TUNE: the research has the perfume's 4/day lore only.
  ~~Matriarch Cubs does not exist~~ **wave 5: read from hunting** (`value:matriarch_cubs`).
- ~~**Attribute shards** skipped~~ **wave 5**: the hunting module owns the shards and rolls our mobs' on MobKill;
  crimson rolls the Duke's for non-killer sharers and applies the four crimson attributes (see Wave 5).
- **Lava fishing / trophy fish** belong to the fishing module (built there, see fishing.md); this module provides
  lava pools/areas, sulphur ore by the pools (wave 5) and counts `FishCaught(lava)` for quests.
- Dojo belt point thresholds, shop prices, mob damage per hit, boss attack patterns,
  Vanquisher chance, eruption cadence and the wave-5 spoils are TUNE (not in the research; re-checked wave 5) -
  each is a dial or marked in the gen scripts.
- ~~Mini-boss loot ... faction-specific extras are folded into the reputation bonus~~ **wave 5: real faction
  spoils + shared loot** (see Wave 5).
- Not tested live: everything Bukkit-side (menus, Dojo client-side blocks, TextDisplays, set bonuses, the wave-5
  ledger/spoils/synthesizer/tomb mining).

## Commands added (player + admin)
Player: `/crimson` (`/crimsonisle`, `/ci`), `/faction` (`/factions`, `/reputation`), `/factionquests`
(`/fquests`, `/crimsonquests`), `/factionshop` (`/fshop`), `/minibosses` (`/crimsonbosses`), `/dojo`,
`/dojoquit`, `/prestige` (`/armorprestige`), wave 5: `/synthesizer` (`/synth`, also `/crimson` slot 29). Warps: `/warp crimson_isle|crimson_drakehold|crimson_carmine|crimson_tomb`.

Wave 3 admin: `/sb crimson pearls <p> [show|reset|collect|perfume]` · `kuudrareq`.
Wave 5 admin: `/sb crimson bossloot <p> <boss> [damage%]` · `ledger [boss]` · `synth <p> <x|y|z> <n>`.

Admin (all take a player name, RCON-friendly): `/sb crimson info <p>` · `rep <p> <mage|barbarian> <set|add> <n>` ·
`faction <p> <mage|barbarian|none>` · `quests <p> [show|reroll|complete <i>|progress <i> <n>]` ·
`boss <list|spawn|kill> [bladesoul|mage_outlaw|barbarian_duke|ashfang|magma_boss]` · `spawn <mobId> <p>` ·
`vanquisher <p>` · `dojo <p> <trial> <points>` / `dojo start <p> <trial>` / `dojo stop` / `dojo reset <p>` ·
`armor <p> <crimson|aurora|terror|fervor|hollow> <0-4>` · `eruption <on|off>` · `tp <p> <anchor>` ·
`open <p> <main|faction|quests|shop|bosses|dojo|prestige>`.

## Dials added
`crimson.warp-requirement` (skill:combat:22, restart) · `crimson.mob-hp-scale` 1.0 · `crimson.boss-hp-scale` 1.0
(**for a solo playtest try 0.02** - 50M-200M HP world bosses are research-true but built for crowds) ·
`crimson.set-bonus-scale` 1.0 · `crimson.quest-rep-scale` 1.0 · `crimson.vanquisher-chance` 0.002 ·
`crimson.essence-coin-value` 1166 · `crimson.teeth-coin-value` 7046 · `crimson.boss-respawn-seconds` 120 ·
`crimson.magma-boss-respawn-seconds` 300 · `crimson.quests-per-day` 5 · `crimson.daily-bonus-rep` 25 ·
`crimson.boss-rep` 10 · `crimson.rival-boss-rep` 25 · `crimson.switch-fee` 50000 · `crimson.switch-cooldown-days` 7 ·
`crimson.boss-bits` 30 · `crimson.quest-reset-hour-utc` 0 · `crimson.dojo-seconds-percent` 100 ·
`crimson.gather-regen-seconds` 20 · `crimson.sidebar` true · `crimson.prestige-accepts-coins` true ·
`crimson.leviathan-placeholder` true · `crimson.eruption-effects` true · `crimson.shop-needs-membership` true.

## Self-tests added
`crimson.items-exist` (owned armor/weapons/keys, shop goods + costs, deliveries, prestige targets + costs,
belts, every drop) · `crimson.mobs-and-spawns` (rules -> mobs, quest targets, bosses flagged, every mob drops
something) · `crimson.anchors` (every NPC/boss/warp/dojo anchor incl. the shops module's 13, portal.hub, every
spawn area, 10-40 fairy souls) · `crimson.research-numbers` (boss HP 50M/70M/70M/50M/200M, Hot = Basic x1.26
and Infernal x2.52, rank thresholds 0/1k/3k/7k/12k, 75 rank SkyBlock XP, belts increasing, prestige ladder,
boss respawn dial applied) · `crimson.quests-roll` (5 distinct eligible quests for both factions at every
rank, deterministic).

## Requests (things you need from core or another module - the integrator reads this)
1. ~~reforges: essence pouch API~~ **done** (wave 3): `Features.Essence` exists and prestige/shop/deliveries
   now spend pouch essence first.
2. **fishing**: lava fishing on `crimson_isle` in the areas `fishing.lava.crater`, `fishing.lava.hut` and the
   desert pool (layout data `crimson.pool.desert` = its lava level; it sits at (-98, 176)). Please fire
   `FishCaught(lava=true)` for lava catches (faction quests count them) and hook Thunder/Jawbus/eruption
   creatures to calendar event `crimson_volcano_eruption` if you want the reference's eruption spawns.
3. **menus**: a "Crimson Isle" entry (combat column) -> `Sky.commands().run(p, "crimson")`.
4. **minions**: the Mycelium/Red Sand minion reputation gates can use `faction:mage:<rep>` /
   `faction:barbarian:<rep>` (plural forms accepted).
5. **shops**: the envoys sell Leviathan keys without a faction check; consider `faction:mage` /
   `faction:barbarian` on those entries and rep gates per tier (research 04 §4.3: 0/1k/3k/7k/12k).
6. **kuudra (wave 2)**: use the dock anchors `npc.leviathan_dock` and area `leviathan_passage`; turn off dial
   `crimson.leviathan-placeholder`. **Done**: kuudra registered the `kuudra:<tier>` resolver and turns the
   placeholder off; wave 3 put the completion requirement on the armor (dial `crimson.armor-needs-kuudra`)
   and counts clears for the Leviathan Hunt quests through that resolver.
7. **core (gen toolkit)**: `Trees.redMushroom` leaves its cap's side walls only diagonally connected to the cap
   (they report as floating 15-block components); we use our own mushroom in `CKit`.
8. **accessories**: Dojo belts `DOJO_*_BELT` are yours; we give them as Dojo rewards.
9. **core (vitals) - playtest 2026-10-03, "[minor] You died (lava)"**: this is NOT crimson code and was left
   untouched here (lane rule). Crimson never names a death cause: `CombatListener` (EntityDamageEvent ->
   group `lava`/`fire`/`fall`/`drowning`/`suffocation`/`void`/`kill`/`cause.name().toLowerCase()`) passes the
   raw group to `VitalsServiceImpl.kill`, and `deathCauseText` only translates `mob:`/`player:`/`entity:`/
   `mob`/`true` - every environmental cause falls through verbatim, so lava, fire, fall, drowning, void etc. all
   read `(lava)` beside `(killed by X)`. Root fix, one switch in `deathCauseText` before `return why`:
   `lava`->"burned in lava", `fire`->"burned", `fall`->"fell", `drowning`->"drowned",
   `suffocation`->"suffocated", `void`->"fell into the void", `kill`->"slain", `lightning`->"struck by
   lightning", `contact`->"pricked by a cactus", `freeze`->"froze", `starvation`->"starved",
   `poison`/`wither`/`magic`->"withered away", `unknown`/`died` -> drop the parenthesis entirely, and a
   default of `why.replace('_', ' ')` so a future `block_explosion` never prints an underscore. Keep the raw
   cause on `SkyDeath.cause` and the ledger (quests/Dojo key off it - crimson's `Dojo.onDeath` ignores it).

## Fix 2026-10-03: one of each mini-boss (playtest-endgame "two Edgewraiths")
Root cause confirmed in core's `MobSpawner`: a rule counts only the copies IT spawned (`LiveMob.ruleId`), and
`Sky.mobs().remove()` untracks with `died=false`, so no respawn timer starts. The playtester's `boss kill` freed
the rule, which put a fresh Edgewraith on the anchor within a second; `boss spawn` then added a second one the
rule could not see. Two kills, two "DOWN!" lines, two bestiary ticks, +20 rep. The same happened to any
boss spawned outside the rule (core's generic mob spawn) while a player stood within 48 blocks.
- `Bosses.keepSingle` (run from the `crimson_scaled` onSpawn of every `boss` def): when a second unowned copy
  of a mini-boss appears in the same world, the newcomer takes over the most-damaged older copy's health, max,
  position, target, attack timers and Cindermaw split state, and the older copies are removed (no loot, no
  MobKill). The newcomer survives deliberately - it settles within one spawner tick on a single copy the RULE
  owns, so its death starts the real respawn timer. Owned copies (`spawn <mob> <player>`) are left alone.
- `/sb crimson boss spawn` refuses while the boss is alive (prints where and its HP); `boss kill` now says the
  rule brings it straight back, so there is nothing to spawn after it.
- Self-test `crimson.boss-singleton`: spawns a boss with no live copy high over world 0's spawn, damages it to
  40%, spawns a second, asserts exactly one survives with the fight's HP at the first one's place, cleans up.
  (Not yet run live - the fixer did not start a server.)

## Known risks
1. **Nothing here has run on a live server.** Highest-risk paths: the Dojo (client-side block changes,
   TextDisplay labels, cancelling `SkyEvents.Damage` on dojo dummies) and set-bonus secondary hits
   (`playerAttack` from inside a damage modifier, guarded by a re-entrancy flag). `/sb crimson dojo start <p>
   <trial>` and `/sb crimson armor <p> crimson 4` test them fast.
2. **Balance**: research mob/boss HP is enormous for an early Combat-22 player (Blaze 60k, bosses 50M+); the
   HP dials exist for that. Quest rep, shop prices and Dojo thresholds are TUNE.
3. **Ownership overlaps**: we register only the Leviathan-line armor, the 3 Kuudra-gated weapons and the 5 keys;
   every drop id is someone else's (all exist today). `postEnable` prunes drop lines whose item vanished and
   logs them.

## Playtest fix (2026-10-04, wave-4 loops)
Spawn rule `crimson_volcano_matcho` (Tinderling, Lv100, 1,300 damage) moved from `mobs.volcano_base` to `mobs.volcano_rim` in `gen_mobs.py` and `spawns.json`: from the base they chased players into the Cinder Shore lava-fishing pool 20 blocks away (a Fishing 15 player died there twice). Verified: 30 casts at the pool, none within 48 blocks. ~~**Still open:** the pool lies inside the `mobs.mystic_marsh` box~~ **fixed, see below.**

## Fix 2026-10-04 (wave-4 loops): Mushroom Bulls at the Cinder Shore pool
Root cause: `mobs.mystic_marsh` was one rectangle (x -215..-90) drawn over the whole marsh district, so it
swallowed set pieces that are not marsh - the Cinder Shore lava pool (x -103..-93) and the Belly of the Beast
(whose spiral ledges are valid spawn spots). **Generator v3** (layout-only; same blocks, the bump makes the
server re-place the island and reload the layout on next boot):
- `mobs.mystic_marsh` east edge -90 -> **-128** (`CSites.districtZones`): every bull spawn spot is now >= 24
  blocks from the pool (core `mob.aggro-range` 16 + margin). Bull count unchanged (10).
- Zone `crimson_lava_hut` "Cinder Shore" is **NO_MOBS** and its south edge moved -64 -> -60 (`CVolcano.hut`).
  NO_MOBS, deliberately not SAFE: rule-spawned mobs never spawn there and core's leash puts back any that walk
  in (target cleared), which also covers the fields' magma cubes 18 blocks away - but SAFE would also stop every
  mob hurting players inside, and that would make the lava sea creatures the fishing module spawns harmless.
  Those are not rule-spawned, so NO_MOBS leaves them alone.
- Zone `crimson_belly` is **NO_MOBS** (`CBelly`): no bull on the 3-wide ledges over the stomach shaft.
- New JUnit `CrimsonSpawnSafetyTest` (12 tests pass): both zones NO_MOBS, the shore zone covers the whole pool
  and no smaller zone overrides it, every `mobs.*` area > 16 blocks (horizontal) from the pool, the marsh >= 24.
  `tools/preview.sh` -> **RESULT: OK** (version 3). `check.sh crimson` -> CHECK OK. **Not run live.**
- Live check for the integrator: after the boot rebuild, `/sb island zones` should list Cinder Shore and Belly
  as `[NO_MOBS]`; `/sb spawner list` -> `crimson_marsh_bulls ok`; stand at the pool for a minute.


## Fix pass (2026-10-04, wave4-endgame playtest)
Dojo Test of Force: the Training Dummies (wander AI) walked out through the open south gate, so chasing one ended the trial ("you left the arena"). `Dojo.force` now puts any dummy outside `dojo.arena` back on the mat. Verified: 0 of 4 outside over two 30 s rounds; a played round scored 244 and the quest counted it. ~~Open: Cindermaw's split blazes ignore `crimson.boss-hp-scale`; there is no boss-damage dial for testing.~~ **fixed, see below.**

## Fix pass 2 (2026-10-04, wave4-endgame): boss damage, the Cindermaw's splits, the silent HP bleed
Verified offline: `bash tools/check.sh crimson` -> **CHECK OK**; **13 JUnit tests pass** (new
`CrimsonRulesTest.bossDamageDialNeverHeals`). **Not run live** (no server in this pass).
- **New dial `crimson.boss-damage-scale` (1.0).** Applied in ONE place, the `onAttack` hook of `crimson_scaled`:
  core runs it for every `damagePlayer(.., mob, ..)`, i.e. vanilla melee AND every behaviour (fire bolts, flame
  rings, slams, blade storms), so no attack can miss it. Covers the 5 mini-bosses and the Cindermaw's split
  blazes. 0 = harmless (hit cancelled); negative/NaN read as 1.0, never healing (`CrimsonRules.damageScale`).
  The default stays 1.0: Lv200 bosses killing a 983 HP player is endgame, and the dial is the testing lever.
- **The Cindermaw's split Smoldering Blazes are part of the boss fight now** (`Bosses.split`): HP from
  `crimson.boss-hp-scale` (they were plain spawns and got the full 2.5M while the Cindermaw itself was scaled),
  the boss damage dial, the Cindermaw's owner when it has one (a private test fight stays private), its target,
  a **leash** of 12 blocks / 10 up-down around where they were born (blazes fly; one followed the playtester out
  over the void), and they **burn out 30 s after their Cindermaw is gone** - nothing counted them, so every
  fight used to leave two 2.5M blazes on the isle for ever. `keepSingle` hands them over to the surviving copy.
- **The silent bleed (978 -> 412 HP walking to the Dojo, no mob within 16, no chat line): root cause found in
  code, not reproduced live.** Every crimson BLAZE (Blaze, Flare, Bezal, Smoldering, Millennia, the Cindermaw)
  fought twice: our `crimson_fireball` behaviour (range 16, line of sight) AND vanilla's blaze AI, which shoots
  a 3-fireball volley from up to its 48-block follow range. Core routes each vanilla fireball as a full
  `def.damage` hit (Flare 1,200 raw) **and each one sets the player on fire** - and fire is environment damage,
  5% of MAX health a second with no Defense. The Dojo road runs 40-55 blocks from `mobs.volcano_slopes`
  (8 Flares). Two volleys = ~10 s of burning = the reported loss, with the shooter out of sight. Fixed:
  `Bosses.onLaunch` cancels vanilla fireballs from crimson mobs that carry a designed ranged attack
  (`crimson_fireball` / `crimson_firebolt`); `Bosses.onIgnite` stops any crimson mob or its projectile setting
  a player alight. Ghasts keep their vanilla fireballs (their only attack; they do not ignite on a hit). This
  also took the Cindermaw's hidden 3 x 6,000 volley out of the "dead in 1-10 s" fights.
- Self-test **`crimson.cindermaw-split`**: spawns a Cindermaw high over world 0's spawn (only when none is up),
  splits it, asserts 2 blazes (and no second split), their max HP = 2.5M x boss-hp-scale, the leash pulls one
  back from 32 blocks out and leaves one at home alone; removes everything.
- **Live check for the integrator**: `/sb dial set crimson.boss-damage-scale 0.05` and fight the Pyromancer;
  `/sb dial set crimson.boss-hp-scale 0.002`, hit the Cindermaw under half -> both blazes show ~5k HP and snap back when
  pulled away; stand 30-45 blocks from a Flare on the volcano slopes for a minute -> no fire, no HP loss.
- **Request 10 (core, combat)**: the same vanilla-ranged leak exists for every OTHER module's blazes/ghasts
  (slayer, kuudra, dungeons...): core's `CombatListener` routes a vanilla fireball as a mob hit but never
  undoes the ignite, so each one adds 5%-of-max-HP-per-second true fire damage. One fix for all: cancel
  `EntityCombustByEntityEvent` on players when the combuster (or its shooter) is a sky mob, beside
  `MobListener.onMobHitEffect` which already does this for Poison/Wither.

Dials added: `crimson.boss-damage-scale` 1.0. Self-tests added: `crimson.cindermaw-split`.

## Requests added in wave 5
11. **mining**: own the break SPEED of the isle's two new ore kinds if you like - opal (`white_stained_glass`) only
    inside area `gather.tomb_opal` on `crimson_isle`, and sulphur ore (`sponge`) anywhere on `crimson_isle`
    (research 07: Crimson Isle "Sulphur Ore, Opal"). Add `crimson_isle` to `gem_opal.islands` with that area and a
    sulphur entry (drop `SULPHUR_ORE`); give your handler a priority above 500 and ours stops being reached. Until
    then crimson's handler (priority 500) mines them at vanilla speed with BP 7 / mining's opal XP and powder.
12. **accessories**: (a) sneak + right-click a `SYNTHESIZER_V*` currently equips it (your `onUse` does not check
    cancellation); please skip equipping when sneaking on a Synthesizer and run `Sky.commands().run(p,
    "synthesizer")` instead (the reference "Right-click to open!"). (b) Optional: let `/synthesizer` feed the WORN
    necklace - expose a way to rewrite the worn stack (e.g. `Features.Equipment.update(p, slot, Consumer<ItemData>)`).
13. **reforges**: the Essence of Crimson attribute and the Leviathan pet's Crimson Tithe are applied by crimson on
    `ItemGain` of `ESSENCE_CRIMSON` (LOWEST, before your LOW pouch intake). If reforges ever applies hunting's
    `value:<type>_essence` keys generically, skip `crimson_essence` (or tell us and we drop ours) - never both.

## Fix 2026-10-06 (real-game audit, lane 2: K13 K14 K16 K17 K18 A1)
Verified offline only: `bash tools/check.sh crimson` -> **CHECK OK (206 files)**, `check.sh reforges` -> CHECK OK;
**26 JUnit tests pass** (6 new in `CrimsonAuditTest`, against the shipping JSON). Data regenerated with
`gen_items.py`, `gen_tables.py`, `gen_npcs.py` and `tools/gen/reforges/gen_reforges.py`; every other generated file
came out byte-identical (reforges: only `stars.json`, exactly the 100 Leviathan-line entries). **Not run live.**
- **K13 quest reputation** = the reference's per-rarity reward: easy 30 / medium 50 / hard 75 / elite 100 / new
  **legendary 125** (Common..Legendary). Legendary: `kill_aeon_blaze`, `hunt_leviathan_burning` (min rank Honored as
  before). Quest menu shows "Legendary". `crimson.quest-rep-scale` still multiplies.
- **K14 prestige coin part**: SkyHanni `constants/Items.json` `crimson_prestige_costs` lists to Hot 150 ess + 10 teeth
  + **2M**, Burning 800 + 20 + **5M**, Fiery 4,500 + 50 + **10M**, Infernal 25,500 + 80 + **20M**. Its essence/teeth
  match the Hypixel API we already had, so the suspected one-tier shift was the research's (it read the
  Burning->Fiery row as Hot->Burning) - essence/teeth unchanged, coin part added as `extra.prestigeCoins` (per
  piece; separate from `prestigeCost` so the item-exists self-test keeps working). `Prestige` shows and charges it
  (fixed coins + any shortfall coins, one withdrawal, items refunded if it fails). Dial `crimson.prestige-coin-scale` 1.0.
- **K16 Leviathan reputation**: +10/20/30/40/50 (Basic..Infernal) for the **first clear of each tier per day**
  (quest reset hour). `Factions.checkLeviathanRep` runs on the existing 5 s hunt poll: per tier it reads the clear
  count through `kuudra:<tier>:<n>` (same binary search as the hunt quests) and pays when it rose since the last
  poll and that tier has not paid today. The first poll only stores a baseline (`CrimsonData.kuudraSeen`), so clears
  from before this change never pay; unpledged players update the baseline but earn nothing. No kuudra code touched.
  Dial `crimson.kuudra-rep` "10/20/30/40/50".
- **K17 Emissary discount**: faction-shop Leviathan keys (`shop.json` `"emissary": true`, the 10 key rows) cost
  -5/10/15/20% on the **coin part only** at 1k/3k/7k/12k reputation with that faction (SkyHanni
  `misc/ItemDiscounts.json`). Shop icon shows the struck-through full price + the percent; buy/confirm charge the
  discounted price. Dial `crimson.emissary-discount` "1000:5/3000:10/7000:15/12000:20" (empty = no discount).
- **K18 Rollim**: Watchman Orrin (already our name for Rollim, wearing his skin) gives **+25 rep once per 24 h, 7
  times** (SkyHanni todo timer 86,400 s from the claim, not the daily reset) when a pledged player talks to him;
  new greeting line says so. `CrimsonData.rollimClaims/rollimLast`. Dials `crimson.rollim-rep` 25,
  `crimson.rollim-days` 7, `crimson.rollim-hours` 24. The board-clear bonus (`crimson.daily-bonus-rep` 25) is
  ours, not the reference's; left as is (set the dial to 0 for reference behaviour).
- **A1 star coins**: NEU `essencecosts.json` charges coins on stars 4-10 (10k/25k/50k/100k/250k/500k/1M; stars
  8-10 = 1.75M per piece) and Infernal 11-15 (2.5M/5M/10M/25M/50M). The stars the Essence menu sells come from the
  **reforges** module's `stars.json`, so the fix is in `tools/gen/reforges/gen_reforges.py` (merges NEU's coin parts
  for the 100 Leviathan-line pieces only); crimson's own `extra.starCosts` carries the same `COINS:n` cells
  (nothing reads it). Reforges `Gear.pay` already charges `{"coins": n}` cells.
- Self-test `crimson.audit-rep-and-coins` (quest rep range, Leviathan rep per tier via the dial, discount at each
  rank, 10 Emissary keys, prestige coin part on the live defs - assumes default dials). `/sb crimson info` prints
  the Leviathan baselines/paid days and Rollim claims.
- **For other modules**: the **shops** module's envoys (`data/shops/shops.json`) also sell the five keys, at full
  price - they are the reference's Emissary, so they should take the same discount (crimson cannot read or write
  their prices); until then the crimson quartermaster is the cheaper door. NEU charges star coins on ~530 items of
  every essence type (dungeon, dragon, spider...); only the Kuudra armour got them here - a wider reforges row.

## Cross-module follow-ups (2026-10-06, integrator list in docs/FIX_BRIEF_2026-10-06.md)
- `crimson.daily-bonus-rep` default **0** (not in the reference); the board menu hides the bonus line at 0 and no 0-rep
  message is sent. gen_tables.py + CrimsonTables default changed, factions.json regenerated. A saved override keeps 25.
- **Forgotten Skull fast travel**: `/warp crimson_skull` (anchor `warp.skull` at the skull's mouth, requirement
  `crimson:scroll:crimson_skull`). Reading `NETHER_FORTRESS_BOSS_TRAVEL_SCROLL` (materials item, `extra.warp`) unlocks it
  once (`CrimsonScrolls`, `CrimsonData.scrolls`); a known one is refused without consuming. **Generator version 4 -> 5**,
  so the Crimson Isle rebuilds on the next boot.
- Tests: `CrimsonAuditTest.noBoardClearBonusInTheReferenceGame`; `CrimsonIsleGeneratorTest` now requires `warp.skull`.
