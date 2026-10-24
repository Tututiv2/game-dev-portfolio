# rift — hand-off

Package `com.skyclone.mod.rift`, entry `RiftModule`. The Rift (research 08 sec 5, research 03 sec 19, research 01
sec 2.3): its own island, its own inventory, its own stat sheet, draining Rift Time, Motes, 52 Riddle Souls, 8
Timecharms behind the research area gates, puzzles, quests, three Rift bosses, Rift gear, and the vampire-slayer hook.
Data is generated: `py -3 tools/gen/rift/gen_rift.py` writes `data/rift/{items,mobs,spawns,recipes,economy}.json`
and `docs/names/rift.md` from `research/data/items.json` + NEU recipe grids.

## What works (verified how)

Verified by `bash tools/check.sh rift` (**CHECK OK**, 187 files), `bash tools/preview.sh com.skyclone.mod.rift.gen.RiftGenerator`
(**RESULT: OK**, 0 unreachable or buried anchors, 0 floating non-decor blocks, 0 writes out of bounds, all block names
valid, ~0.8 s), and **11 JUnit tests** (14 since wave 3) (`RiftMathTest`, `RiftGeneratorTest`, run locally with javac + the JUnit 5.11
launcher from the gradle cache: **11/11 pass**; the generator test runs the real generator and the walkability flood).
I also flood-checked every collectible: all 52 souls, 5 eggs, 8 heart spots and 6 race banners are reachable on foot
except the three that are meant to be jumped to (half-way up the Great Tree, the Crown deck, the far end of the
upside-down room). **Nothing has run on the live server** (rule 10). Start with `/sb selftest rift`.

| System | What it does |
|---|---|
| **Island `rift`** (`gen/RiftGenerator`, `RiftBuild`) | ~490 x 470 blocks, permanent violet dusk. 10 districts, each a zone with its research gate: **Wildwood Thicket** (spawn: the Cradle with the portal home, the Rift's Wizard Tower, the Great Wyld Tree, the Rift Gallery, peddlers' stall, stone circle, pond, pale-oak woods), **Black Lagoon** (sunken pools, stilt hut, giant mangrove, the Leeches Lair in a hollow mound), **West Village** (6 odd houses incl. Cake House, Infested House, Mirror House; race track), **Looking-Glass Halls** (underground under the village: corridor + 3 puzzle rooms), **Dread Acres** (berberis rows, mushroom beds, barn, silo, a 33-high beanstalk with a ladder), **Village Plaza** (fountain, town hall, 4 clerk offices, tailor, clock tower, carrot patch), **Living Cave** (a hill with a chamber; the frozen east half is the Living Stillness), **Colosseum** (pit, 11 tiers of stands with steps up), **Grimhollow Chateau** (walls, 4 towers, 3-floor keep, sunken oubliette, blood crystals), **The Mountaintop** (56-block mountain, 2.25-turn spiral road to a summit altar). 57 mote orbs along the roads, 52 soul anchors with a hint each (written into the layout as `soul.N.hint`), 1 fairy soul (`fairy_soul.rift`, progression finds it). |
| **Entry / exit** (`RiftTravel`) | Warden of the Arch (registered in the Cradle, copied into the hub next to the Wizard Tower's arch with `spawnAt`). Pay with a **Planar Infusion** (1 free every 4 h, 3 stored), **200 Bits**, or **32 Grand Experience Bottles**; a **Rift Prism** in bag or inventory makes it free (research). `/warp rift` and Fast Travel slot 31 also work: warp requirement `rift:pass`, charged on arrival. Arriving any other way without paying (portal, admin tp, relog into the Rift world) is charged or bounced to the hub. |
| **Separate inventory** (`RiftInventory`) | On entry the main inventory (armour, off-hand, all slots but the SkyBlock Menu star) is parked and the Rift inventory comes out; reversed on exit. Parked items live in the **player's PersistentDataContainer**, which is written in the same `.dat` save as the inventory, so a crash at any moment can neither dupe nor lose (see the class javadoc). State machine checked every second + on join, quit, death, island change and disable: Rift inventory is live iff the player stands in the Rift with a session. Rift **materials** (collection items, cruxes, boss fragments, hemo-items, enrichments, Rift Prism) and the research's 68 transferable items cross both ways; Rift **gear** stays in the Rift. Dial `rift.separate-inventory` turns it off. |
| **Rift Time** | Base 480 s + 60 s per Timecharm + 5 s per 4 souls + gear/accessory/equipment `riftStats` + core `RIFT_TIME` (slayer's Vampire levels). Drains 1/s; **frozen** in the Rift Gallery, the upper Wizard Tower and the Looking-Glass Halls, **half** in the Colosseum (research). Warnings at 60/30/10 s, no penalties under 10 s (grace), +1 s per kill (+1 more with the Wildthorn Hood), +2 s per mote orb, -30 s on death. At 0 you are thrown out to the hub's Wizard Tower. Action bar `7m 32sф Left    7.5/10❤`. |
| **Rift stat sheet** (`RiftStats`) | Rift Damage (base 20), Hearts (base 10), Rift Intelligence / Speed / Mana Regen. Every hit on a Rift monster deals exactly your Rift Damage (main gear, Strength, crits and Ferocity do not count); Rift monsters hit for whole hearts (a heart = max HP / Rift Hearts). Rift Intelligence, Speed and Mana Regen are added to the real stats while inside. |
| **Area gates** | Zone requirement `rift:gate:<area>` = research 19.1 (e.g. Colosseum = Quickmetal Timecharm + 15:00 left). Once passed, a gate stays open for the rest of the visit (core re-checks zone requirements continuously; without this, time draining below the gate would push you out mid-area). `/rift areas` shows what each gate wants. Dial `rift.gate-mode` = remaining (research) or max. |
| **Motes** | Orbs 25 (Cradle 10, no time), kills 2-30, bosses, quest rewards, selling to the Mote Monger (73 items, research `motes_sell_price`; click items in your inventory or "Sell Rift Items"). Spent in 7 shops (57 offers). |
| **Riddle Souls** | 52, walk into one (sparkles within 28 blocks); title + chat + 3 SkyBlock XP (`rift:soul:N`); milestone every 4. Riddler Nyx and `/rift souls` show per-area counts and every soul's hint. |
| **8 Timecharms** | Crown (climb the Great Tree's 44-step spiral), Hatchling (Farmer Wilma: find 5 eggs + 64 Wilted Berberis + 32 Agaricus Caps -> Hen Leggings), Glass (all three Looking-Glass rooms), Paperwork (4 clerks in order, each costs 10 s of Rift Time, then the Mayor), Quickmetal (hit the Living Heart 6 times as it jumps around the cave), Gelatinous (kill the Bloatmass), Sanguine (kill the Chateau Lord), Starbound (bring the other 7 to the summit). Each: +60 s max Rift Time, +100 SkyBlock XP (`rift:timecharm:<id>`). |
| **Puzzles** (`RiftPuzzles`) | Upside-down room (fall = back to start), hidden-path maze (path glows 6 s, wrong tile = back to start; generated per attempt), dance floor (10 rounds, colour called by title, windows shrink 2.6 -> 1.2 s), Living Heart, West Village race (6 banners in 75 s; first finish = Racer's Sneakers, best time kept). |
| **Quests / NPCs** (`RiftNpcs`) | 21 NPCs: Warden (entry / overview), 2 Mote Mongers, Riddler Nyx, Glade Peddler, Tower Sage, Old Vel'ka (3 healing melons), Dr. Marsh (Leech Sovereign), Racer Gustav (race + Infested House: 15 vermin -> Disinfestor Gloves + 200 Motes), Dr. Quillon, Farmer Wilma, Clerks Pomfrey/Quine/Rumbold/Sable, Mayor Barnaby, Tailor Penrose, Cave Warden Ostrel, Arena Keeper Brutus (Bloatmass), Vampire Hunter Moira (shop + Chateau Lord + opens `/slayer vampire`), The Starkeeper. |
| **Mobs & bosses** (`RiftCombat`, data) | 13 Rift monsters in 13 spawn areas (cruxes, spines, silk, living metal, hemovibe...), Rift HP scale; 3 summoned bosses (one alive each, anyone in the Rift may help, helpers get motes + the charm): **Leech Sovereign** 1,000 HP (leechlings, hits drain Rift Time), **Bloatmass** 2,500 (bloatlings, knock-back pulse under half), **Chateau Lord** 1,500 (drinks hearts and heals, crypt bats). |
| **Gathering** | Dead bush -> Wilted Berberis, brown mushroom -> Agaricus Cap, big dripleaf -> Caducous Stem, carrots -> Half-Eaten Carrot, red glass -> Hemovibe, purple glass -> Timite (+Youngite/Obsolite), blue glass -> Living Metal (+2% Living Metal Heart). Break handler, `Sky.loot().give` (so the 7 Rift collections count), `Gather` event, regrows after 30 s; restored on shutdown. |
| **Items** (52, ours) | Wildthorn set + blade, Leech Sword (mana on hit), 4 pickaxe-swords, 2 karambits (+50/+100% vs vampires), Leechstep Wand I-III (blink 5/7/9), 21 more armour pieces (quest/shop/summit/vampire-gated), healing melons (2/4/6 hearts), 3 elixirs (speed, +5 Rift Damage, jump), hot dog, valuables, reversed junk. 29 NEU recipes (only those whose every cell resolves). |
| **Vampire slayer hook** | Chateau mobs are tagged `"slayer": "vampire"` (Splatterling, Chateau Thrall; 35-40 Combat XP each, slayer T1 needs 600). Slayer bosses fought inside the Rift take Rift Damage x `slayer.vampire-hp-scale` (read live), via a priority-950 modifier so slayer's own thresholds still apply; Ferocity is off for them in the Rift. |

## What is partial / not built
(Updated wave 5; struck items are done, see "Wave 5" at the end.)
- Health regen inside the Rift is core's normal regen (scaled by `rift.heart-regen`). ~~Strength, crit and Ferocity
  still show in `/sb stats`~~ (wave 5: 0 inside, dial `rift.zero-main-combat`). Defense still applies to non-Rift
  damage inside (slayer's vampire bosses rely on it).
- ~~Souls are places, not 52 puzzles~~ - wave 5: every soul is one of the reference's 52 Enigma Soul riddles.
- Not built from the research list: Reed's boat, Shen's auction, Wizardman trials, Living Metal suit "juicing", a
  Rift Guide menu (the `/rift` quest book lists our quests + wave 5 progress). ~~Montezuma / Rift pet~~,
  ~~Porhtal's eyes~~, ~~the Time Gun~~ - wave 5. The Rift Ferret pet exists in pets data with no source here.
- Elixir "speed" and "damage" are tracked per visit; "jump" is a potion effect.
- Mob and boss numbers (HP, hearts, drops, motes) and all shop prices are TUNE: the reference data has none.

## Wave 3 (2026-10-04)

Verified by `bash tools/check.sh rift` (**CHECK OK**, 197 files), `bash tools/preview.sh com.skyclone.mod.rift.gen.RiftGenerator`
(**RESULT: OK**, both new NPC anchors ok, a crop of the gallery + stone circle looked at) and **14/14 JUnit** (3 new:
Split or Steal payouts + cooldown, crux milestones/fortune, gallery scale + the new anchors in the catalog). Not run live.
**Generator version 1 -> 2**: the Rift rebuilds once on the next boot (4.3M blocks, ~16 s, off-thread).

| What | How it works |
|---|---|
| **Rift stats replace the main ones** | `RiftStats.contribute` now uses core's `StatMap.set` (rift Request 5, done by core): inside a session Intelligence = Rift Intelligence, Speed = 100 + Rift Speed, Mana Regen = Rift Mana Regen, Magic Find = 0 (research 08 sec 5.2 "the only stats that apply"). Skills, pets and accessories no longer leak in. Dial `rift.replace-main-stats` false = the old add-on-top behaviour. |
| **Rift Gallery** (`RiftGallery`) | **Curator Ilsabet** (`npc.curator`, east end of the gallery) secures each Timecharm you own: one click per charm or "Secure Everything", +100 Motes (`rift.gallery-secure-motes`) and 5 SkyBlock XP each (`rift:gallery:<id>`). A secured charm **floats and spins over its pedestal** (an ItemDisplay with `visibleByDefault=false`, shown only to players who secured it; everyone sees the pedestal's name label). Her shop "Curator's Necklaces": Inner Dimensional Necklace 6,000 Motes (`rift:gallery:1`), Dimensional Necklace 40,000 (`rift:gallery:8`). The inner necklace's Rift stats (riftStats + otherStats HEARTS/RIFT_DAMAGE) **scale with secured/8** ("becomes more powerful as you fill the Rift Gallery"). Both necklaces cross the Rift boundary. Overview menu slot 33 opens the gallery; the Timecharm toast points at the curator. New requirement key `rift:gallery:<n>`. Exhibit spots are layout data `gallery.exhibit.1..8`. |
| **Split or Steal** (`RiftSplitSteal`) | The reference's Ubik's Cube, renamed. **Ubaldo Twofold** (`npc.split_steal`, inside the Wildwood stone circle, no gate) runs a 2,000-Mote pot (`rift.split-steal-pot`). Pick SPLIT or STEAL; you are paired with the next player who picks (anywhere in the Rift), or after 45 s (`rift.split-steal-wait`) with the Cube's Echo, which splits 60% of the time. Both split = half each, one steals = all, both steal = nothing. One round per 2 h (`rift.split-steal-hours`), charged only when the round resolves (leaving while waiting costs nothing). Every result is **announced to the whole Rift** and revealed with a title, built for the four AI players on camera. Stats + last result on the menu. |
| **Odonata** (`RiftOdonata`) | 10 glowing dragonflies (particles, like souls and orbs: no entities) dart over the two Wildwood monster meadows (`mobs.wyld_shy`, `mobs.wyld_larva`). Buy an **Empty Odonata Bottle** from the Glade Peddlers (15 Motes), right-click while looking at one within 4.5 blocks: it becomes a **Bottled Odonata** (the Mote Monger pays 40; it crosses the boundary and is an ingredient of the Wildwood trophy recipe). A caught one respawns elsewhere after 20 s (`rift.odonata`, `rift.odonata-respawn`). The bottle never fills with water. |
| **Crux talisman kill scaling** | The crux line (`CRUX_TALISMAN_1..7`, accessories' items) now counts as one family (only the highest owned tier's stats apply) and tier n tracks the first n crux monsters (shy, shadow, volt, scribe, frozille, splatter, puff: the recipe order). Each kill milestone (10/50/100/250/500/1000 per monster; research gives only the first) = **+5 Rift Time and +5 Crux Fortune** (research 06); Crux Fortune rolls bonus cruxes on those kills (100 = +1 guaranteed). Milestones announce themselves; the Rift Stats icon shows "Crux milestones" as a time source and the Crux Fortune. |
| Quest book | + Fill the Rift Gallery, Play Split or Steal, Bottle an Odonata. |

Paid for: `gen_rift.py` is reproducible (re-ran it before editing: byte-identical output), so the data was changed
through the script, never by hand (curator shop, odonata bottle offer + sell price, necklace/odonata exports, names).

## Commands added (player + admin)
- `/rift` (overview) · `/rift souls|charms|areas|leave|shop|enter` · `/motes`.
- Menu paths: Warden of the Arch (hub, by the Wizard Tower arch / in the Cradle), Fast Travel -> The Rift, every Rift NPC.
- `/sb rift info <p>` · `enter <p>` (free) · `leave <p>` · `time <p> <s>` · `motes <p> give|take|set <n>` ·
  `charm <p> <id|all|reset>` · `souls <p> <all|reset|n>` · `infusions <p> <n>` · `boss <p> leech|bloatmass|lord` ·
  `tp <p> <anchor>` (starts a free session) · `quest <p> wilma|paperwork|mirror|race|vermin` · `reset <p>` ·
  `stats <p>` · `heart` (move the Living Heart) · `hubnpc` (re-place the hub Warden) · wave 3: `gallery <p> [reset]`
  (secure every owned charm, or clear) · `splitsteal <p>` (clear the cooldown and any waiting choice).

## Dials added
`rift.base-time` 480 · `rift.base-damage` 20 · `rift.base-hearts` 10 · `rift.drain-scale` 1 · `rift.kill-time` 1 ·
`rift.timecharm-seconds` 60 · `rift.soul-step` 4 · `rift.soul-step-seconds` 5 · `rift.mote-orb-time` 2 ·
`rift.mote-orb-motes` 25 · `rift.cradle-orb-motes` 10 · `rift.mote-orb-respawn` 300 · `rift.death-time-penalty` 30 ·
`rift.infusion-hours` 4 · `rift.max-infusions` 3 · `rift.bits-cost` 200 · `rift.bottle-cost` 32 · `rift.heart-regen` 1 ·
`rift.vampire-hp-scale-fallback` 1000 · `rift.heart-hits` 6 · `rift.race-seconds` 75 · `rift.vermin-needed` 15 ·
`rift.wilma-berberis` 64 · `rift.wilma-agaricus` 32 · `rift.gather-regen` 30 · `rift.maze-reveal` 6 ·
`rift.dance-rounds` 10 · `rift.paperwork-seconds` 10 · `rift.summon-leech` 150 · `rift.summon-bloatmass` 400 ·
`rift.summon-lord` 600 · `rift.boss-helper-motes` 50 · `rift.grace-seconds` 10 · `rift.separate-inventory` true ·
`rift.free-entry` false · `rift.gates` true · `rift.particles` true · `rift.sidebar` true ·
`rift.hub-npc-offset` "-17,0,8" · `rift.gate-mode` "remaining".
Wave 3: `rift.replace-main-stats` true · `rift.gallery-exhibits` true · `rift.gallery-secure-motes` 100 ·
`rift.split-steal-pot` 2000 · `rift.split-steal-hours` 2 · `rift.split-steal-wait` 45 · `rift.split-steal-echo-split` 0.6 ·
`rift.odonata` 10 · `rift.odonata-respawn` 20.

## Self-tests added
`rift.items-exist` (shops, gathering, rewards, mob drops, recipe cells) · `rift.layout` (every NPC/point anchor, mob
and puzzle area, 52 souls + hints, eggs, heart spots, race banners, >= 40 orbs) · `rift.gates` (each area has its
zone, the right `rift:gate:` key, NO_FALL) · `rift.math` (infusions, hearts, max time, clock, maze connectivity,
Colosseum gate) · `rift.economy` (loaded, no mote loop: nothing sells for >= its shop price) · `rift.mobs` (>= 15
Rift mobs with hearts, bosses have behaviours, a vampire-tagged mob exists) · `rift.menus-build` (builds every menu
for the first online player; passes empty; now also the gallery, Split or Steal and the curator shop) ·
`rift.warp-and-npcs` · wave 3 `rift.wave3` (Split or Steal never creates Motes, curator shop loaded, necklace + odonata
items registered, the Mote Monger buys Bottled Odonata, 8 exhibit spots + both new anchors in the built layout).

## Requests (things you need from core or another module — the integrator reads this)
1. **slayer**: Chateau mobs now carry `"slayer": "vampire"`, so set `slayer.vampire-any-mob false`. Rift Hearts here
   are "max HP / Rift Hearts" (10 base), so `slayer.vampire-heart-percent` 4 (= 25 hearts) could become 10 to match;
   your choice. Your Riftstalker fees are coins; Motes are in `rift` member data (`RiftData.motes`) if you want them.
2. **hub**: optional `portal.rift` area at the Wizard Tower arch -> warp `rift` (registered, requirement `rift:pass`,
   arrivals are charged). Until then the Warden copy stands 17 west / 8 south of `warp.wizard_tower`
   (dial `rift.hub-npc-offset`); please confirm that spot is open ground after any hub rebuild.
3. **menus**: Fast Travel slot 31 already lists the `rift` warp. A SkyBlock Menu "The Rift" icon could run `/rift`.
4. **accessories**: the Mote Monger now sells `RIFT_PRISM` (4,000 Motes, 10 souls) and every `TALISMAN_ENRICHMENT_*`
   (3,500) + the swapper (1,500) — your Request 9. Rift accessories' `extra.riftStats` are read here (Accessories.has).
   Crux talismans do not scale with kills.
5. ~~**core**: per-player stat override~~: done by core (`StatMap.set`), used since wave 3.
6. ~~**slayer / core**~~ (done: core added `Features.Rift`, rift provides it since wave 5): Riftstalker fees could be Motes, but there is no `Features` service to spend them
   through. If core adds e.g. `Features.Rift { long motes(Player); boolean spendMotes(Player, long, String); }`, rift
   implements it in a few lines (`RiftModule.takeMotes`).
7. **pets** (wave 3, optional): a source for `RIFT_FERRET;3`, or a Rift-only Montezuma pet. Rift can hand the Ferret
   out as a quest reward once pets confirms that is the right id to give.

## Known risks
1. **Never run live.** Most likely rough edges: the inventory swap on real clients (cursor items, open menus — it
   closes the inventory first), mob entity choices (Ravager/Drowned/Stray AI inside small spaces), the hub Warden spot,
   and the gate push-back feel. `/sb rift enter <p>` + `/sb rift info <p>` + `/sb rift tp <p> <anchor>` exercise it fast.
2. **Balance is TUNE**: max Rift Time vs the 15:00 Colosseum gate needs ~+450 s of bonuses (5 charms = 300 + gear/souls);
   if players stall there, raise `rift.timecharm-seconds`. Mob HP/hearts and shop prices are invented.
3. **Wave 3, untested live**: the exhibit displays (per-player visibility via `showEntity`), their interpolated spin,
   and the odonata aim feel. Split or Steal can be farmed by one person with two accounts (Motes only, one round per
   2 h per account).
4. **The inventory stash is per Minecraft player, not per profile** (it follows the vanilla inventory, which core
   also does not split per profile). A player who switches profile while their Rift inventory is parked keeps it.

## Wave 5 (2026-10-06)

Verified by `bash tools/check.sh rift` (**CHECK OK**, 204 files), `bash tools/preview.sh com.skyclone.mod.rift.gen.RiftGenerator`
(**RESULT: OK**: every NPC anchor reachable incl. the 12 new ones, 0 SMALL floating components, a crop of the Leap of
Faith pillar looked at) and **20/20 JUnit** (new `RiftEnigmaTest` x5, `RiftGeneratorTest.everyRiddleHasItsSoulAndItsProps`;
run with javac + the JUnit 5.11 launcher from the gradle cache). **Not run live** (lane rule).
**Generator version 2 -> 3**: the Rift rebuilds once on the next boot (riddle props, NPC anchors, cat pieces, eyes, buttons).
`gen_rift.py` re-run (data changed only through it): 55 items (+3), 31 recipes (+2), names table regenerated.

### Done
| What | How it works |
|---|---|
| **52 Riddle Soul puzzles** (`RiftEnigma` pure catalogue, `RiftRiddles` runtime, `gen/RiftProps` props) | Every soul is one reference Enigma Soul (id = NEU `rift_guide.json` task id, split per area as SkyHanni `EnigmaSouls.json`), renamed. **24 kinds**: walk-in (`PLACE`, 17 souls), **Dry Feet** (no water touched for 60 s), **Leap of Faith** (land on it from 6+ blocks: a ladder pillar is built beside it), **Twin Plates** (soul in a glass cage; two players on the two plates open it for 12 s), **Behind the Living Wall** (breakable chiseled-tuff shell, grows back after 20 s once nobody stands in it), **flower-pot trails** x5 (tap pots in order from the farthest; the next pot glows; wrong order wilts), **balloons** x4 (6 per-player particle balloons; pop by left-click within 5 blocks or the Berberis Blowgun at 40), **Flitwraith** x4 (walk to its road spot; it flees toward the soul while you stay within 7, gives up if you trail 16+ blocks for 12 s), **Turning Runes** (4 glazed terracotta, per-player rotation via block changes, all must face the soul), **cakes** x2 (7 bites, per player), **Beacon and Hopper** (two players use them within 2.5 s), **Every Button** (8 wooden buttons across West Village + Dread Acres, click or blowgun, one visit), **shops** x4 (Rourke 600, Blitzcoin 1,000, Faeosopher 2,000, Solar Skink 1,500 Motes, TUNE x `rift.riddle-shop-scale`), **Old Vel'ka's quiz** (3 questions, wrong = 60 s wait), **Hot Dog Showdown** (menu: 20 dogs in 25 s), **Pest Control** (granted with the vermin reward; catch-up for old players), **Lonely Yxvael** (stay 3-10 blocks away for 30 s), **Rabbit in the Hat** (do not move for 8 s), **Squat With Okrel** (20 sneaks in 30 s), **Full Circle** (Digby the Mole: unwrapped angle around the Colosseum >= 2 pi in 150 s, then back to him), **Ubaldo's Averages** (pick = rounded mean of your pick and the last 6 picks), **Ubaldo's Boxes** (Monty Hall; a loss = 5 min retry), **Tit for Tat** (any Split or Steal round). Locked souls smoke instead of sparkling and say what they want when you come close; solving a "reveal" riddle near its soul grants it at once, far away it frees it to walk in. The soul menu shows each riddle's name and task. |
| **Riddle NPCs** | 11 new + Weeping Maribel, anchors `npc.riddle_<key>` beside their soul (else the nearest road). Names in `docs/names/rift.md`; the summit Ubaldo is the same person as the stone-circle one. |
| **Pharaoh** (reference Montezuma, `RiftCat`) | Weeping Maribel (by the Great Tree road) starts the quest and gives a **Whisker Finder** (`DEAD_CAT_DETECTOR`, ability Meowdar: nearest unfound piece's distance + direction + a 10 s glow). 9 pieces `cat.1..9` (one beside a lectern = "inside a book"). Numbers are the NEU lore's: Rift Time 10 + 15/piece, +2 Mana Regen/piece from EPIC (6+); rarity RARE 1+, EPIC 6+, LEGENDARY 9 (TUNE). Applied by RiftStats while the pets module's `FRACTURED_MONTEZUMA_SOUL` is the active pet; **until pets registers that pet, Pharaoh rides along as a Rift companion** (toggle in Maribel's menu). |
| **The Warden's Eyes** (reference Porhtal's eyes) | 7 eyes `eye.1..7`, one per district, beside a road. Found by walking in (portal particles within 12). `/rift eyes` or the overview's eye icon: fast travel to any found eye for 10 s of Rift Time (`rift.eye-travel-seconds`); area gates still apply. |
| **Enigma Cloak** (accessories' item) | Riddler Nyx gives it on the next visit after meeting him, then stitches in souls every 4 (max 42, the item's lore): +20 Rift Time per held soul while worn. |
| **Timeshift Pistol** (`TIME_GUN`, Starkeeper 2,500 Motes TUNE, NEU recipe from Youngite + Blowgun) | Right-click Timite (purple glass) within 24: Youngite (lime) or, sneaking, Obsolite (gray) for 60 s; breaking it gives YOUNGITE / OBSOLITE and Timite regrows. |
| **Berberis Blowgun** | Rift registers `BERBERIS_BLOWGUN` with its ability (materials' plain fallback no longer wins); Farmer Wilma gives one on finishing her quest (research: the blowgun riddles follow "Harvest for Shania"). Pops balloons, presses buttons, hurts only chickens and Pufflings. |
| **Rift-only effects of other modules' items** (`RiftEffects`, RiftStats, RiftTravel.drainHere) | Rift monsters' melee hits take 1 s Rift Time (`rift.melee-hit-time`, research "damage taken removes time"); **Silkrider / Leech / Vermin / Golden Belt** take 1 less. **Golden Belt** +5% max Rift Time, drains 3% faster. **Satelite** 20% slower drain at y >= 100 (`rift.elevated-y`). **Respiration Talisman/Ring/Artifact** freeze the drain for the first 10/20/30 s of a visit. **Punchcard Artifact**: punch another Rift player, both +25 s (once per player per visit, max 20). **Garlic Gummy Polar Bear**: +3 Combat XP per Rift kill. Strength / Crit / Ferocity read 0 inside (`rift.zero-main-combat`). |
| **`Features.Rift`** | Provided: `motes`, all-or-nothing `spendMotes`, `addMotes` (rift Request 6 closed). |
| Quest book | + Pharaoh's pieces, the eyes, riddles solved (x/35 puzzles). |

### Commands added
`/rift eyes` · `/rift cat`. Admin: `/sb rift riddle <p> <id|all|reset|list>` (list: each riddle's soul, kind,
"no props" fallbacks, solved/found) · `/sb rift cat <p> <n|all|reset>` · `/sb rift eyes <p> <n|all|reset>` ·
`/sb rift cloak <p> [reset]`. `/sb rift reset` also clears the wave 5 state.

### Dials added
`rift.riddle-solo` false · `rift.cage-open-seconds` 12 · `rift.wall-regrow` 20 · `rift.riddle-shop-scale` 1 ·
`rift.riddle-retry` 300 · `rift.hotdog-seconds` 25 · `rift.spook-lose` 12 · `rift.lonely-seconds` 30 ·
`rift.squat-seconds` 30 · `rift.circle-seconds` 150 · `rift.dry-seconds` 60 · `rift.leap-height` 6 ·
`rift.eye-travel-seconds` 10 · `rift.melee-hit-time` 1 · `rift.elevated-y` 100 · `rift.zero-main-combat` true ·
`rift.punchcard-seconds` 25. All TUNE except the punchcard's 25 (item lore).

### Self-tests added
`rift.riddles-catalog` (52 unique riddles split like the souls, shops priced, every riddle NPC registered, the 3 new
items exist) · `rift.riddles-layout` (every riddle has its soul, NPC anchor and cage; at most 4 riddles without props;
buttons; cat pieces, eyes, Maribel) · `rift.riddles-math` (Pharaoh numbers = NEU lore, cloak steps, Monty Hall,
averages) · `rift.features-motes` (`Features.Rift` agrees with the profile and refuses overspending) ·
`rift.menus-build` now also builds Maribel's menu and the eyes menu.

### Still left (wave 5)
Reed's boat, Shen's auction, Wizardman trials, Living Metal suit juicing, Eleanor's tasks, the Reverse-Murder line,
Seraphine, Cowboy Nick's rabbits, Barry's protestors, a Rift Guide menu built from `rift_guide.json` (the 52 riddles,
9 pieces and 7 eyes already use its ids, so a guide can key on them), Rift health regen rules.

### Requests (wave 5)
8. **pets**: register `FRACTURED_MONTEZUMA_SOUL` (display "Pharaoh", "Fractured Soul Pet", RARE/EPIC/LEGENDARY, no
   main-world stats, `rift_transferable`; NEU has `;2` and `;3`). Rift gives it from Weeping Maribel's menu once it
   exists and then applies its Nine Lives / Trickery numbers only while it is the active pet. Until then the Rift
   companion toggle stands in. Also: should `RIFT_FERRET` be a reward here?
9. **slayer**: `Features.Rift.spendMotes(p, n, reason)` now exists if Riftstalker fees should be Motes.
10. **accessories**: the rift-hooked effects are applied here now (Enigma Cloak, belts, Golden Belt, Satelite,
    Respiration line, Punchcard, Garlic Gummy Bear). **Archaeologist's Compass** is not a Rift item (its relics are the
    Spider's Den's 28): please re-hook it to combatzones.
11. **NpcNamesTest**: the riddle NPCs are registered from `RiftEnigma.NPCS` (a map, not `NpcDef.of(..., "literal")`),
    so the build test does not see their names; the live `ui.npc-names-unique` does. Names were checked by hand
    against the codebase (Tobin was a garden visitor, so Mossbeard is Quorrin).
12. **materials**: rift now registers `BERBERIS_BLOWGUN` (with its ability); your fallback copy stays unused.

### Known risks (wave 5)
1. **Never run live.** Likely rough edges: particle balloons / Flitwraith visibility at range, the Flitwraith flying
   straight through walls (you walk round), core's block-click denial vs our LOW listeners on cake / beacon / hopper /
   buttons / pots (we cancel first and handle), the Twin Plates cage closing (it waits until the cells are empty), and
   fall capture for Leap of Faith (PlayerMoveEvent within 1.6 blocks while falling 6+).
2. Per-player visuals (runes, cake bites) are client block changes: a chunk reload shows the real blocks again; the
   puzzle state is kept and the next click re-sends.
3. A riddle whose props found no room silently becomes a walk-in (`/sb rift riddle <p> list` marks it; the generator
   test fails if any expected prop is missing on the default seed).
4. Respiration / Satelite / belt checks run every drain tick through `Accessories.has` (per player per second).
