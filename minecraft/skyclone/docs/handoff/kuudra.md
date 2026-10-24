# kuudra - hand-off

The Magma Leviathan hunt (reference: Kuudra), research 04 sec 4. Package `com.skyclone.mod.kuudra` (entry
`KuudraModule`), arena generator in `com.skyclone.mod.kuudra.gen` (pure). Data in
`plugin/src/main/resources/data/kuudra/`, **all generated** by `tools/gen/kuudra/gen_kuudra.py` (never hand-edit):
`tiers.json` (5 tiers + fight constants), `mobs.json` (5 bosses + 14 brood kinds x 5 tiers = 75), `items.json` (3),
`perks.json` (12), `loot.json` (5 chest tables), `npcs.json` (2 skins from NEU).

## What works (verified how)

Verified offline (the dev server is the integrator's): `bash tools/check.sh kuudra` -> **CHECK OK**;
**8 JUnit tests pass** (`plugin/src/test/java/com/skyclone/mod/kuudra/`, run with javac + the JUnit launcher from
the gradle cache): research numbers, formulas, 20,000 loot rolls (slot-2 shares within 3% of research's 30/35/35,
teeth/essence ranges, armor always the run's prestige), perk cost tables, the whole arena built + flood-checked,
the Lair/Gullet floors, organs and lake spots, the ballista build order. `bash tools/preview.sh
com.skyclone.mod.kuudra.gen.KuudraArenaGenerator` -> **RESULT: OK**, 0 unreachable anchors, 0 floating components,
all block names valid. Cut-away previews of the inside: `KuudraArenaCutaway` (dome lifted), `KuudraArenaBelow`
(Lair + Gullet); PNGs in `previews/kuudra*/`. `check.sh all` fails only in dungeons and rift (other agents, in
progress), never in kuudra.

- **The arena** (`kuudra`, INSTANCED, spacing 512, NETHER sky, fixed night): a 190-block blackstone dome over a
  lava lake (north) and a cavern floor (south) with basalt/crimson regions, pillars, a beast skeleton, lava pools,
  stalactites, rock spires in the lake and a wrecked jetty. On the shore: the **ballista platform** (ballista,
  six supply pads, the cannon, two reward chests, arrival point, two NPCs, soul lamps). Under the lake: **the Lair**
  (Infernal), with the head's lava pit. Under the floor: **the Gullet**, a fleshy chamber with six glowing organs on
  pedestals. ~330k blocks. Arenas are kept BUILT between hunts and **reset by the module** (ballista cleared to
  its foundation, organs restored, leftovers removed) so a hunt starts instantly; a generator version bump
  rebuilds them (table `kuudra_arenas`).
- **Tiers** Basic/Hot/Burning/Fiery/Infernal: requirement per tier is a dial (default research rep
  `faction:any` / `faction:any:1000` / `:3000` / `:7000` / `:12000`, crimson's resolver) plus one clear of the tier
  below (dial). SkyBlock XP on the FIRST clear of each tier: 20/40/60/80/100 (`neu constants sblevels
  defeat_kuudra_xp`), key `kuudra:<tier>`. Boss collection `BOSS_KUUDRA` +1..+5 per clear to every member (the
  boss ids `leviathan_<tier>` deliberately avoid the word "kuudra" so collections' id heuristic does not double
  count). Wave interval 35/30/25/20/15 s. Boss HP 10M/25M/60M/150M/800M (research clone defaults).
- **The fight** (`Run`, ticks every 5 ticks):
  1. **Supplies**: 6 crates on 12 floor spots; crouch beside one to lift (1.5 s channel), carry it (slowed, a
     chest floats over your head), crouch on a pad to set it down (3 s). A hit resets the channel; dying drops it.
  2. **Build**: crouch beside the ballista; every builder adds 1.4 %/s; the ballista really rises block by block.
  3. **Ballista**: fuel cells wash up on 6 shore spots (4 on the ground, 6 s respawn) and drop from 25% of brood
     kills; crouch to grab, crouch by the ballista to load; 4 loaded -> right-click the ballista -> a fire bolt
     arcs to the beast: 15% of max HP. The beast moves between six lake spots every 25 s. **Burning+**: unstunned
     volleys do 1.5%; ride the **cannon** (Cannon Pass perk) or be **devoured** (every 40 s a free hunter is
     swallowed) into the Gullet and break all six organs (3 strikes each, 30 s, 2.5% max HP/s digestion) ->
     **STUNNED** 15 s.
  4. **Kill (DPS)**: at 40% the ballista can do no more; the beast beaches at the shore and every weapon works.
     Elites arrive (Wither Sentry; Fiery+ Blazing Golem + Leviathan Slasher).
  5. **Lair (Infernal)**: at 25% the floor gives way; everyone falls into the Lair. Deaths there make you a
     spectator ghost with a soul-fire marker; a crewmate with **Lifeline** crouches beside it 3 s to revive. All
     fallen = fail.
  Throughout: brood waves (size grows per wave and per player, cap 28 alive) from 12 rim/shore spots, targeting
  the nearest hunter; **dropships** (Hot+, every 3rd wave from wave 4) fly in and land 3 followers on the platform
  unless shot down (+150 tokens). The beast attacks with telegraphed **Magma Spit** (ring on a random hunter),
  **Tentacle Slam** (ring on the platform / a hunter, knock-up) and, beached, **Rampage** (ring around itself).
  Deaths outside the Lair: back on the platform, blinded briefly, no coins lost (dial). Time limit 25 min.
- **Tokens and the perk shop**: tokens for lifting/delivering/building/loading/firing/stunning/organs/kills/
  dropships; 12 perks (`Hunt Perks` item in hotbar slot 8, the Quartermaster NPC, or `/kuudra perks`): faster
  carrying, faster building, cannon access, fewer organ strikes, Second Wind (survive one killing blow), Lifeline,
  +damage, -damage taken, Overcharge (volleys), speed, Fuel Magnet, Mending Tonic. Perks end with the hunt.
- **Crews**: `/kuudra crew invite|accept|leave|kick|disband`, clickable chat invites (60 s), up to 4 (dial). Solo
  is always valid; the beast's HP scales 50% solo -> 100% at four (dial, TUNE).
- **Rewards**: on the kill every hunter still in the hunt gets a chest pair stored in their profile (claimable
  later from `/kuudra chests`, so leaving or a crash loses nothing; cap 10). **Free chest**: Crimson Essence
  (research 1-2 / 14-22 / 25-30 / 80-120 / 200, + the Kuudra pet's Crimson Tithe %). **Paid chest** (consumes the
  tier's Leviathan Key, contents shown before you pay): slot 1 = the run's prestige of one of the 100
  Leviathan-line armor pieces (60%), a Kuudra weapon (15%: Hollow Wand / Aurora Staff Hot+ / Tormentor Burning+ /
  Fire Fury Staff Fiery+) or molten equipment (25%); slot 2 = a book (30%: Fatal Tempo, Inferno, Strong /
  Ferocious / Hardened Mana, Mana Vampire, higher levels at higher tiers), Leviathan Teeth (35%) or Crimson Essence
  (35%); rares (Enrager, Heat Core, Burning/Fiery/Infernal cores, Mandible 0.005%, Tentacle Dye). Optional reroll
  with a `KISMET_FEATHER` once dungeons registers it. Claims mark the chest before giving; keys are taken before
  items; unique items are created fresh at claim time.
- **Entry**: the crimson dock - right-clicking crimson's dockhand (`leviathan_dock`) or walking to the end of the
  pier (`leviathan_passage`) opens the hunt board; `/warp kuudra` lands there; `/kuudra` works anywhere (starting
  needs the Crimson Isle unless dial `kuudra.queue-anywhere`). postEnable switches off crimson's placeholder
  dockhand dial (`crimson.leviathan-placeholder`, their Request 6).
- **Accessories hook**: Leviathan's Kidney/Lung/Heart (accessories' `hook: kuudra`) give 10/20/30% faster carrying
  and 5/10/15% faster building here. The Kuudra pet's Leviathan Bane works through pets (`match: kuudra` hits the
  `kuudra` family/mob ids); accessories' Follower Artifact sees the brood as `kuudra_follower`.
- **Records**: clears and best time per tier, tokens earned, hunts, brood slain, deaths (menu + requirement
  resolver).
- **Menus**: hunt board (5 tier cards with lock reasons, clears, best time, key count, rewards), crew, reward
  chests (paged) and the chest pair, loot preview per tier, records, how-to guide, in-run panel (live), perk shop
  (live), travel to the dock.

## What is partial / not built
*Rewritten wave 5 (2026-10-06); see "Wave 5" at the end for what changed.*
- **Not run on a live server since wave 3.** Wave 3 (hunt again, surfacing, collapse, narration) and wave 5
  (cutscenes, questline, shards, Wheel) are compiled and unit-tested, not seen. Worth a live look, in this order:
  the full opening cutscene (tentacle displays rising out of the lake, the freeze, crouch-to-skip with two hunters),
  the beast's 3 s crawl ashore, the victory carcass sinking, Nell's questline at the dock, a shard landing in the
  Hunting Box from `/kuudra chests`, and the collapse / slow-fall into the Lair (Infernal).
- **Cutscenes are camera-free by necessity**: a Paper server cannot move a client's camera, so the crew is frozen
  (transient attribute modifiers) and turned to face the lake while titles, Brask's lines, sounds, particles and
  display entities play. Spectator-camera tricks were rejected (they break teleports and can strand a player).
- The **questline's steps are ours** (the reference publishes only "Basic: Elle introduction"); it gates every tier
  via `kuudra.intro-required`. The reference's Crimson Isle *main* questline ("Hot: main quest + 1,000 rep") is
  crimson's to build; Hot here needs the rep and a Basic clear.
- **Shard pool and its rarity weights are ours** (TUNE): the reference does not publish which shards a Kuudra chest
  holds. Built from nether shards + the legendaries hunting earmarked for this chest.
- The Lair collapse is display entities + effects; the arena blocks themselves are never broken (so the reset stays
  cheap). Deliberate.
- Kuudra minion damage/HP, perk costs, token values, ballista/stun/build numbers, the pet / Wheel drop chances, the
  intro kill count are TUNE (research gives none); all marked in the generator and dialled.

## Commands added (player + admin)
Player: `/kuudra` (`/leviathan`, `/hunt`) opens the hunt board (or the run panel during a hunt);
`/kuudra start <tier>` · `again` (`requeue`: same tier, same crew; works from the Victory/Failed screen) · `leave` · `perks` · `chests` · `stats` · `loot [tier]` ·
`crew [invite|accept|leave|kick|disband|list] [player]` · `intro` (`quest`: The Leviathan's Wake quest log).
Admin (all take a player name, RCON-friendly): `/sb kuudra start <p> [tier]` (bypasses requirements and location) ·
`phase <p> <supplies|build|ballista|stun|dps|lair|win>` · `tokens <p> [n]` · `fuel <p> [n]` · `hp <p> <percent>` ·
`end <p> [fail]` · `list` · `complete <p> <tier> [n]` · `chest <p> <tier>` (grant a rolled chest pair) ·
`reset <p>` · `loot <tier> [rolls]` (simulated paid-chest distribution) · `open <p> <main|perks|chests|crew|stats|loot|run>` ·
`arenas [rebuild]` · (wave 5) `intro <p> [show|complete|reset|step <n>|kills <n>]` · `scene <p> <skip|forget|opening|victory>`.

Fast test: `/sb kuudra start Steve basic` then `/sb kuudra phase Steve ballista`, right-click the ballista;
`/sb dial set kuudra.boss-hp-scale 0.02` to kill it quickly; `/sb kuudra phase Steve win`.

## Dials added
`kuudra.boss-hp-scale` 1.0 (**try 0.05 for a solo playtest**: research HP is built for endgame crews) ·
`kuudra.solo-hp-fraction` 0.5 · `kuudra.mob-hp-scale` 1.0 · `kuudra.mob-damage-scale` 1.0 · `kuudra.solo-mob-damage-fraction` 0.6 · `kuudra.boss-damage-scale` 1.0 ·
`kuudra.wave-seconds-scale` 1.0 · `kuudra.volley-scale` 1.0 · `kuudra.build-speed` 1.0 · `kuudra.channel-speed` 1.0 ·
`kuudra.token-scale` 1.0 · `kuudra.essence-bonus-percent` 0 · `kuudra.time-limit-minutes` 25 · `kuudra.reward-seconds` 90 ·
`kuudra.max-party` 4 · `kuudra.max-arenas` 6 · `kuudra.pending-chest-cap` 10 · `kuudra.supplies` 6 · `kuudra.fuel-per-volley` 4 ·
`kuudra.stun-seconds` 15 · `kuudra.stomach-seconds` 30 · `kuudra.start-tokens` 25 · `kuudra.max-alive` 28 ·
`kuudra.queue-anywhere` false · `kuudra.require-previous-tier` true · `kuudra.cannon-needs-perk` true ·
`kuudra.death-coin-loss` false · `kuudra.take-over-dock` true · `kuudra.dock-trigger` true · `kuudra.sidebar` true ·
`kuudra.announce-infernal` true · `kuudra.dock-briefing` true · `kuudra.req.basic|hot|burning|fiery|infernal` (requirement keys per tier) ·
(wave 5) `kuudra.cutscenes` true · `kuudra.cutscene-repeat` false · `kuudra.cutscene-skip` true · `kuudra.intro-required` true ·
`kuudra.intro-kills` 15 · `kuudra.intro-key` true · `kuudra.shard-to-box` true.

## Self-tests added
`kuudra.research-numbers` (rep, SB XP, waves, HP, collection points, free essence, phase flags, fight constants,
slot-2 weights) · `kuudra.items-exist` (own items, essence, teeth, 5 keys, armor of every prestige, books) ·
`kuudra.mobs-exist` (5 bosses at research HP, 70 brood defs, non-empty wave pools) · `kuudra.formulas` · `kuudra.pet-drops` (every tier lists `KUUDRA;<tier index>` and the pets module registered it) ·
`kuudra.loot-rolls` (2,000 rolls, only registered ids, essence in every free chest) · `kuudra.arena-layout`
(generator run in memory: every anchor/area/data key, bounds, walkability) · `kuudra.crimson-dock` (crimson's dock
anchor + passage area + `/warp kuudra`) · `kuudra.menus-build` · (wave 5) `kuudra.shard-pool` · `kuudra.intro-steps` ·
`kuudra.cutscenes`.

## Requests (things you need from core or another module - the integrator reads this)
1. **api / dungeons**: a `Features.Party` (`members(p)`, `leader(p)`) so the hunt can use the dungeons party
   instead of its own crews. Today `/kuudra crew` is separate from `/party`.
2. **crimson**: in `CrimsonMenus.main` slot 32 ("Leviathan's Hollow", currently "The hunts are not running yet"),
   click -> `Sky.commands().run(p, "kuudra")`. Optional: Kuudra-completion requirements on the Leviathan-line armor
   can now use `kuudra:<tier>` (e.g. `kuudra:hot`), and the dockhand's lines can mention `/kuudra`.
3. **menus**: a SkyBlock Menu / combat entry for `/kuudra`. Fast Travel's `kuudra` slot 32 ("Magmaw's Hollow")
   never fills because no warp lives on island `kuudra` (the hollow is per-hunt); the warp is `kuudra` on island
   `crimson_isle` - map slot 32 to that warp id.
4. ~~**accessories / menus**: the boss is "Magmaw" there and "Magma Leviathan" everywhere else; please align
   the display names.~~ **Done 2026-10-02** (naming fixer, see "Changes after integrate-w2-1" below).
5. **dungeons**: register `KISMET_FEATHER`; the paid-chest reroll button appears by itself once it exists.
6. **minions**: `HEAT_CORE` is registered here now (their Request 5) - Magma Bucket / Plasma Bucket / Everburning
   Flame recipes become craftable.
7. **core**: `Slime.setSize` resets vanilla max-health/speed; we restore the gauge right after (`Run.resize`). A
   core-side `MobDef.extra.size` honoured in `configure()` would be cleaner for everyone spawning slimes.

## Known risks
1. **Never seen live.** Highest-risk paths: the carried crate is a block display that follows the hunter's head
   (re-placed every 5 ticks with client interpolation - deliberately NOT a passenger, which would block every
   teleport), the beast as a scaled no-AI magma cube (size 12 x scale 2.4, gravity off)
   and its nameplate/hitbox, the arena reset after `ensureInstance`, and the dockhand click hook (MONITOR on the
   crimson NPC's entity).
2. **Balance**: research HP (10M Basic .. 800M Infernal) is tuned for endgame crews; a fresh Combat-22 player
   cannot solo it at 1.0. `kuudra.boss-hp-scale` and the solo fraction exist for that. Token costs/values, brood
   stats, ballista/stun numbers are TUNE.
3. **Shared world**: all hunts live in `sb_kuudra` 512 blocks apart; landmine/imp explosions only check distance,
   so they are safe, but a player leaving the arena world is dropped from the hunt by design (lair spectators
   stay in it). `crimson.leviathan-placeholder` is switched off in `dials.yml` by postEnable (dial
   `kuudra.take-over-dock` false prevents it).

## Changes after integrate-w2-1 (naming fixer, 2026-10-02)

Fixes the integrator's open item "kuudra Request 4 still open: Fast Travel says 'Magmaw's Hollow', the hunt board
says 'Leviathan's Hollow' / 'The Magma Leviathan'".

- **Root cause**: accessories' generator renamed every "Kuudra" to "Magmaw", and menus' Fast Travel fallback
  copied it - but "Magmaw" is the Crystal Hollows boss (reference Bal: hollows `ch_magmaw`, the `BAL` pet, the
  bestiary's `bal` entry). Two different bosses shared one name. Resolved to the name crimson coined first and
  this module, progression and crimson's keys already use: **the Magma Leviathan / Leviathan's Hollow**.
- `tools/gen/accessories/gen_accessories.py`: `WORD_RENAMES` Kuudra -> Leviathan, the `kuudra_follower` mob text,
  the organ notes ("In the Leviathan hunt ...") and the molten-set note ("... fighting the Magma Leviathan"), and
  the rename-table row. Regenerated: `data/accessories/items.json`, `equipment.json`, `docs/names/accessories.md`
  (before the edit the generator was re-run and reproduced the shipped files byte-for-byte, so the only diff is
  the 10 names + 7 lore lines above; recipes.json / powers.json unchanged).
- `menus/TravelLayout.java`: kuudra fallback name "Magmaw's Hollow" -> "Leviathan's Hollow" (one string; the
  runtime name already came from this module's `IslandDef`, so this only matters with kuudra absent).
  `docs/names/menus.md` row updated (it claimed to match "the accessories/pets Magmaw", which was Bal's pet).
- `docs/names/kuudra.md`: the "Inconsistency for the owner" section is now the settled table, plus the list of
  places a future boss rename has to touch.
- Not changed, on purpose: hollows / pets / progression "Magmaw" (that IS the Bal boss) and every id.
- Verified: `bash tools/check.sh kuudra`, `accessories`, `menus` -> CHECK OK; kuudra + accessories + menus unit
  tests 42/42 pass (compiled against the last built core, since core was mid-edit by another fixer). Not run on
  a server (fixers do not start it).

## Changes after playtest-endgame (kuudra fixer, 2026-10-03)

Fixes for the real-client playtest (docs/PLAYTEST.md: "solo Basic took 11 min with 18 deaths", "the beast beaches in
the lava out of sword reach", "Hunt Perks lands in inventory slot 15").

- **The beached beast is in sword reach.** Root cause: `kuudra.dps` (0.5, 60, -22.5) placed the CENTRE of the beast
  13 blocks past the rail, and nothing related that point to its hitbox or to the shore, so its front face hung over
  the lava. `Run.beachSpot()` now keeps the anchor's x/height and moves the centre along z so the front face of the
  **live bounding box** lands on the beach line (`KMath.beachedCentreZ` / `beachedFeetY`), one block onto the dry
  shore in front of the rail gap. New generator anchor `kuudra.beach` (0.5, 64, `BEACH_Z` = -13) plus a constant
  fallback, because a persisted template layout (no version bump; blocks unchanged) will not carry the new anchor
  until the island's layout is regenerated. The beast is no longer collidable (its hitbox overlaps the shore and
  would shove hunters into the lava). The DPS title now tells you to go through the rail gap. The Rampage ring is
  drawn at shore height (it was drawn at the beast's feet, under the lava, i.e. invisible) and sized to the hitbox
  + 5 blocks. Unit tests: `beachedBeastIsInSwordReach` (the shipping 15-block hitbox: face at z -13, centre in the
  lava, the platform lip within 3 blocks) and `beachIsDryAndWalkableFromTheRailGap` (dry shore under the face, lava
  right behind it, floor + headroom on every step from the rail gap to the beach).
- **Hits no longer kill every channel.** `Run.hurt(p, attacker)`: only the beast's own blows (spit, slam, rampage)
  interrupt (as before); brood and environment hits now SLOW lifting / setting down / grabbing / loading and building
  to 50% for 0.6 s (`KMath.BROOD_HIT_SLOW`), shown as "(hit - slowed)". Before, any hit within 750 ms zeroed the
  build contribution, so with brood on you the ballista never rose.
- **The brood pauses.** (1) The alive cap scales with the crew: `kuudra.max-alive` (28) is now the four-hunter number,
  40% solo (11), +20% per extra hunter (`KMath.crowdCap`). (2) A due wave waits while more than half that cap still
  stands (landmines and dropships do not count, they never walk at you), at most one extra interval
  (`KMath.waveDue`), with an action-bar note "The brood holds back while N still stand". (3) Every phase change
  (build, ballista, DPS) gives one wave interval of quiet (`Run.breather()`).
- **Fuel cells.** Pick-up distance is horizontal (2.6 blocks, within 2.5 vertically) instead of 3-D from the cell's
  anchor, and the label says "Crouch beside it to grab". Brood-dropped cells used to spawn where the mob died - in
  mid-air for a blaze, or in the lava - and are now dropped onto dry ground under the kill (none if there is none).
  The "crouching 1.5 blocks away did not pick it up" report was marked unverified; the likeliest real cause was the
  old rule that any brood hit reset the 0.6 s channel, which the slow-not-reset change above removes.
- **Hunt Perks goes on the hotbar.** `KuudraModule.giveRunItems`: slot 8 (index 7) if free; otherwise the item in
  slot 8 moves into the main inventory (the player is told) and Hunt Perks takes its place; otherwise any free hotbar
  slot. It used to `addItem` into the main inventory whenever slot 8 was taken.
- Verified: `bash tools/check.sh kuudra` -> CHECK OK; kuudra JUnit 11/11 (was 8; +`pacingFormulas`,
  +`beachedBeastIsInSwordReach`, +`beachIsDryAndWalkableFromTheRailGap`). Not run on a server (fixers do not start it).
  Worth a live look: the beast's face over the shore in the DPS phase, and solo Basic pacing.

### Request 8 (collections): the boss collection is named "Vorgath"
`data/collections/collections.json` names `BOSS_KUUDRA` "Vorgath" with rewards "Vorgath Pet", "Vorgath Teeth",
"Vorgath Chunk" (+ Meaty / Hardened / Enriched), from collections' rename table (`docs/names/collections.md` rows
37, 63-68). Everywhere else the beast is **the Magma Leviathan** (settled in Request 4 above, see
`docs/names/kuudra.md`): this module's items are "Leviathan Teeth" etc. Please rename in the collections generator:
`BOSS_KUUDRA` -> "Magma Leviathan", and Vorgath -> Leviathan in the reward labels. Outside this module's files, so
not changed here.

## Wave 3 (2026-10-04)

Worked the "partial" list and the open playtest line "the second hunt could not start while the first sat in
Victory" (docs/PLAYTEST.md, endgame table). Highest player impact first.

- **Hunt again (the playtest blocker).** Root cause: after a win the crew stays attached to the finished hunt for
  `kuudra.reward-seconds` (90 s) so they can open chests at the platform; `Runs.start` refused anyone in `byPlayer`
  ("You are already on a hunt") and also demanded the Crimson Isle, which a hunter standing in the hollow is not.
  Now `Runs.busyIn()` treats a VICTORY / FAILED hunt as not busy, the location check is waived for a hunter in a
  finished hollow, and `start` detaches everyone from their finished hunt before picking an arena (closing it frees
  that arena, so it is usually reused at once; chests were stored at the kill, nothing is lost). If no arena is free
  after all, anyone just detached is sent to the dock rather than left in the hollow with no hunt.
  Ways in: a clickable **[HUNT AGAIN] [LEAVE]** chat line at victory and at failure (non-leaders are told who
  starts), a **Hunt Again** button in the run panel (finished hunts), in the victory chest menu (slot 50) and on the
  hunt board (slot 13, your last tier this server session, when you meet its requirement),
  `/kuudra again` (alias `requeue`; remembers your last tier after the hollow closed, via `Runs.lastTier`). The
  boss bar counts down "home in Ns | /kuudra again" in Victory and "back to the dock in Ns" in Failed (now 10 s,
  was 6). The non-leader error now names the leader.
- **Leviathan pet in the paid chest.** `KUUDRA;<tier index>` (pets module's ids: Common at Basic .. Legendary at
  Infernal) as a rare, 0.3% per paid chest (TUNE: the reference does not publish it). Generator edit +
  regenerated `loot.json` only (the other five files came out byte-identical). Unit test asserts the rarity per tier;
  self-test `kuudra.pet-drops` asserts it live and that pets registered the id.
- **The beast surfaces.** During the 5 s countdown the Leviathan rises `KMath.RISE_DEPTH` (10) blocks out of the
  lake to `kuudra.rise` on an ease-out curve, with lava spray and smoke on the lake surface, lava pops and roars;
  invulnerable while it climbs through the rock. Skipping the countdown (admin `phase`) snaps it into place.
- **The Lair collapse (Infernal).** The 3 s before the drop are now 12 beats of rumble (explosions, deepslate
  breaking, a heartbeat), dust and lava under every hunter, small sideways tremors (never upward: no free fall
  damage), rock (block displays) raining from the dome around the ballista, then "COLLAPSE!". Hunters drop in
  `KMath.LAIR_DROP` (10) blocks above the Lair floor, 4 blocks further in where the dome is higher, with Slow
  Falling and a short blackout, with rock falling in beside them. Unit test `lairDropHasHeadroom` checks open air
  over the whole drop column (it failed first at the spawn itself: only ~12 blocks of headroom there, which is why
  the drop moved inward). No arena block is broken, so the reset is unchanged and the generator version did not move.
- **Ferryman Brask narrates the hunt** (the reference's dock NPC calls every phase in chat): supplies up, half / last
  crate, all crates, half built, ballista ready (a different line on stun tiers), first direct hit, devoured (names
  the victim), stunned, beached, the lair, victory, failure. One-shot per hunt except devour/stun. `Run.narrate`.
- **Dockhand Nell's briefing** (stands in for the reference's Elle intro): the first time the dock (clicking Nell or
  walking to the end of the pier) opens the hunt board, Nell explains the hunt in six lines through
  `Sky.npcs().talk`, then the board opens. Once per profile (`KData.introduced`); dial `kuudra.dock-briefing`.
  `/kuudra` itself never plays it. Not a gate.
- **Rare chest drops are called out.** Opening a paid chest that holds anything from the tier's rare table (pet,
  Enrager, cores, Mandible, dye) shows "CRAZY RARE LEVIATHAN LOOT!" (Legendary+ items still show "LEVIATHAN LOOT!"),
  and crewmates still in the hollow are told. Pet items read "<name> Pet" in the chest and loot preview.
- How-to guide mentions `/kuudra again`.
- Verified: `bash tools/check.sh kuudra` -> CHECK OK; kuudra JUnit **12/12** (was 11: +`lairDropHasHeadroom`; the
  loot test now also asserts the pet per tier). Not run on a server (this phase does not start it).

### Status of the older Requests (read from the other hand-offs, 2026-10-04)
1 (party) done by the integrator (`Features.Party`); 2 (crimson slot 32) done; 3 (SkyBlock Menu + Fast Travel)
done by menus; 4 (names) done; 5 (`KISMET_FEATHER`) already registered by dungeons; 7 (`MobDef.extra.size`) done by
core - `Run.resize` stays, harmless; 8 (collections "Vorgath") done by collections.

### Request 9 (pets): the Kuudra pet earns no XP in the hunt
`PetService` line ~170 zeroes Kuudra-pet XP off island `crimson_isle`. In the reference the Hollow is part of the
Crimson Isle, so a Kuudra pet levels during the hunt; here the hunt runs on island `kuudra`, so it never does.
Please treat island `kuudra` like `crimson_isle` in that check. Outside this module; not changed here.


## Fix pass (2026-10-04, wave4-endgame playtest)
A won hunt keeps its members (Hunt Again), so the sidebar section (priority 50) and the tokens action-bar segment followed the player to every island. Both are hidden outside the arena once the hunt is VICTORY. Verified live.

## Fix pass 2 (2026-10-04, wave4-endgame playtest: knocked down mid-carry, killed after VICTORY)

- **Killed by a stray arrow after VICTORY ("You died (projectile)", no name).** Root cause: `victory()` /
  `fail()` cleared the brood but not what the brood had already loosed. An arrow whose shooter is gone reaches core
  as nameless *environment* damage ("projectile" group, `combat.environment-percent` 5% of max HP per vanilla point),
  so it both hit hard and had no source to name. Fixes: (1) `Run.clearShots(shooter)` removes non-hunter
  projectiles in the hollow - all of them in `clearMobs()` (victory, fail, lair drop, close), and a mob's own shots
  when it dies (`onKill`), so an orphan arrow never lands mid-fight either; (2) `Run.sheltered(p)`: once a hunt is
  VICTORY/FAILED, `Runs.onDamage` cancels every hit on its hunters while they are in the hollow (stray shots,
  burning, a Blight's wither); (3) `stopHarm` puts out fire and clears Wither/Poison at victory and failure.
  Outside the hollow (a winner who warped away keeps the run for Hunt Again) damage is normal.
- **Knocked down while setting a crate on a pad; the crate fell beside the pad.** Three causes, all fixed:
  - A crate that falls ON a free pad is set down on it: `onDeath` -> `padUnder()` (the pad being set down on,
    even if a knockback shoved the carrier a step off, else any free pad within 2.2 blocks) -> `deliver()`, with
    "The crate tumbles onto the pad as X falls." Anywhere else it still drops where the carrier fell.
  - A knockback off the pad (or letting go of crouch) restarted the 3 s set-down at 0%. Progress now holds for
    `KMath.CHANNEL_GRACE_MILLIS` (1.5 s) after the target is lost, shown as "(held - get back and crouch)";
    applies to lift / set-down / fuel grab / load. The beast's own blows still interrupt outright.
  - Brood damage did not scale with the crew (the beast's HP did). New dial **`kuudra.solo-mob-damage-fraction`
    0.6** (linear to 1.0 at four, `KMath.broodDamageScale`), applied in the `kuudra_perks` modifier with
    `kuudra.mob-damage-scale`. The playtest hunter (741 Def) takes ~28 per Basic Follower hit solo, was ~48. TUNE.
- **Request 10 (core combat)**: a projectile whose shooter was removed is treated as environment damage with no
  name. Attributing it to the original shooter's def (or dropping it) would fix the "(projectile)" message
  everywhere, not only in the hollow. Outside this module; not changed here.
- Verified: `bash tools/check.sh kuudra` -> CHECK OK; kuudra JUnit 12/12 (`pacingFormulas` now also covers the
  channel grace and the brood damage scale; self-test `kuudra.formulas` too). Not run on a server (fixers do not
  start it). Worth a live look: solo Basic supplies with Followers on you; win with a Wither Sentry mid-draw.

## Wave 5 (2026-10-06)

Task: camera-free cutscenes, the intro questline, attribute shards via `Features.Hunting`, the whole "partial" list,
and the accessories / pets / reforges effects that belong to the hunt.

### Done
- **Cutscenes** (`KScript` = pure timelines, `Scenes` = the beats, `Run.playScene/sceneStep/endScene`). A scene freezes
  the crew with **transient** attribute modifiers (`skyclone:kuudra_freeze`: Movement Speed and Jump Strength x0; never
  saved, so a crash cannot leave anyone frozen; also cleared on leave/close/ProfileReady), turns them to face the
  action, and `Runs.onDamage` cancels every hit on a frozen hunter. Every hunter crouching skips it (dial); a skipped
  scene still runs its `end`, so the world is left exactly as the fight expects.
  - **Opening** (12.5 s): "LEVIATHAN'S HOLLOW" title, Brask's lines, the lake churns, **four tentacles** (stacks of
    7 shrinking block displays) rise out of the lava by client interpolation, sway, sink as the beast heaves itself
    up (the wave-3 surfacing, now driven by the "rise" beat), roar + explosion, "THE MAGMA LEVIATHAN" with its health,
    then straight into SUPPLIES. Plays in full when anyone in the crew has never seen it (`KData.scenesSeen`);
    veterans get the old 5 s countdown with the surfacing (dial `kuudra.cutscene-repeat` forces the full one).
  - **Beaching** (DPS): the beast drags itself from its lake spot to the shore over 3 s (smoothstep, basalt and lava
    at its front, ground-shaking steps), invulnerable and immune while it crawls ("IT COMES ASHORE"), then a shock
    burst and "KILL IT!". The first Rampage waits for the crawl. Not a freeze: hunters keep moving.
  - **Stun**: a "STUNNED / Ns" text display rides over the beast's head with circling sparks for the stun.
  - **Victory** (4 s): a carcass (scaled magma block + four flesh lumps) sinks into the lake in bursts of fire; the
    chest pair opens when it ends. Slain in the Lair: it goes under in the lake above, where the crew now stands.
  - The Lair collapse (wave 3) stays as it was.
- **The intro questline "The Leviathan's Wake"** (`KIntro`, pure state machine; reference: "Basic | Elle
  introduction", and crimson's Dockhand Nell wears Elle's skin): 1 talk to Nell at the dock -> 2 pledge to a faction
  -> 3 slay 15 Crimson Isle creatures (dial) -> 4 report to Nell, who hands over **one Basic Leviathan Key** (once per
  profile, dial) and sends you out -> 5 bring the beast down once. Steps 1 and 4 happen at Nell / the end of the pier
  (her lines, then the hunt board); the others finish by themselves (pledges are noticed every 2 s on the isle, kills
  counted from `MobKill`, the clear from `reward`). Every step ends in a "QUEST STEP" box with the next objective.
  Gate: every tier needs Nell's send-off (`kuudra.intro-required`); requirement keys `kuudra:intro` (sent out) and
  `kuudra:intro:done`. Veterans with a clear are finished silently on login; players who heard the wave-3 briefing
  skip step 1. Shown on: the hunt board (slot 11 quest icon, and the Basic tier's lock reason), a quest log page
  (`/kuudra intro`, travel-to-dock button on talk steps), and a sidebar section on the Crimson Isle.
- **Attribute shards in the paid chest** (research 04 sec 4.5 slot 1, the 2025 rework): slot 1 is now armor 40 /
  **shard 40** / weapon 10 / molten equipment 10 (research's clone default 40/50/10, with molten taking 10 from
  shards). 19 nether shards in the pool, rarity by tier (Basic mostly Common/Uncommon .. Infernal Epic/Legendary;
  rarities read from `data/hunting/shards.json` by the generator). Claimed shards go **straight into the Hunting Box
  through `Features.Hunting.addShards`** (with its Hunting XP), dial `kuudra.shard-to-box`; without hunting they are
  items, and with no hunting module at all a shard roll falls back to armor.
- **Wheel of Embers** (`WHEEL_OF_FATE`, registered here; reference lore: "reroll the Shard inside into a higher
  rarity one"): a rare in every tier's paid chest (0.4-0.5%, TUNE); the unopened paid chest grows a "Spin" button
  (slot 41) that takes one wheel first, then turns the chest's shard into a random shard of the next rarity up.
  Loot preview lists every shard with its share of shard rolls.
- **Hunting attributes that belong to the hunt**: `kuudra_box` (Leviathan's Box, +5%/level) adds extra Leviathan
  Teeth to the paid chest (every full 100% is a sure one); `crimson_essence` (Lava Leech, +1%/level) adds to every
  chest's Crimson Essence. Both via `attributeLevel(p, "value:<attr>")`.
- Checked the other hand-offs: Leviathan organs (carry/build) were already here; Crimson Tithe (pet) already
  multiplies chest essence; the Follower Artifact/Relic, Molten set and Leviathan cores are applied by accessories
  (`dmg_taken` with `mobTypes`, `island:kuudra`, stats); Leviathan Bane / Wither Lure / Lava Fortune by pets; nothing
  in reforges is the hunt's (Kuudra Mandible's Chomp is fishing's).
- Verified: `bash tools/check.sh kuudra` -> CHECK OK; kuudra JUnit **19/19** (was 12; new `KuudraWave5Test`: the
  shard pool against hunting's catalog, 20,000 rolls per tier = 40% shards and rarer every tier, no-hunting
  fallback, the wheel climbing Common -> Legendary in 4 spins, Leviathan's Box, a full questline playthrough with no
  reference name in Nell's lines, the cutscene timelines stepped exactly as `Run` steps them). Data regenerated:
  `tiers/mobs/perks/npcs.json` byte-identical, `items.json` (+1) and `loot.json` changed. The server was NOT started.

### Still left
- Nothing of the old partial list except seeing it live (above). Owner-facing calls: the shard pool, the intro
  kill count and the Wheel chance are TUNE; a per-player "skip cutscenes" setting would need menus (Request 12).

### Requests (wave 5)
11. **extras (Ringphone)**: the reference contacts **Vulcan** ("complete an Infernal tier", key `kuudra:infernal`) and
    **Crag** (call name "kuudra") are not in `contacts.json`; our resolver answers `kuudra:infernal` already.
12. **menus (Settings)**: optional per-player key `kuudra.cutscenes` (default on); this module would read it through
    `Features.Settings.flag(p, "kuudra.cutscenes", true)` alongside the server dial.
13. **reforges / hunting**: `crimson_essence` is applied here AT THE SOURCE for Leviathan chests (like foraging does
    for `forest_essence`). If a global "% more essence" pass is added on essence pickup, skip Crimson Essence from
    `LootSource.DUNGEON` claims of this module, or it counts twice.
14. **crimson**: the reference's Crimson Isle main questline ("Hot: main quest + 1,000 rep") is yours; once it exists,
    set `kuudra.req.hot` to `faction:any:1000 & quest:<id>` (or ask for an AND key). `kuudra:intro:done` is available
    if the main quest should wait for Nell's questline.

### Known risks (wave 5)
1. The freeze rides on Paper's Jump Strength / Movement Speed modifiers; a client with high latency may rubber-band
   once when frozen mid-stride. The 5 s countdown path (veterans) never freezes.
2. Tentacles / carcass are block displays moved by teleport interpolation (`setTeleportDuration`); if a client skips
   the interpolation they pop instead of glide - cosmetic only, all are removed at scene end and at close.
3. Requirement `kuudra:intro` now gates EVERY tier; a live player with no clear who joined before wave 5 has to do the
   questline (2 talks + a pledge + 15 kills) before hunting. `/sb kuudra intro <p> complete` or dial
   `kuudra.intro-required false` lifts it.

## Real-game audit, lane 1 (2026-10-06): the June-2025 chest

Fixes rows K1-K12 and K19-K23 of `docs/AUDIT_REAL_GAME_2026-10-06.md` sec 1. Source of every number:
`SkyblockMoney/work/kuudra.md` sec 1-2 (wiki Kuudra/Loot, all five tabs, sum-checked; official June-2025 changelog).
The chest model is replaced, so the "Rewards" bullet and the wave-5 shard/Wheel notes above describe the OLD chest.

**A paid chest is now: the guaranteed part + one slot-1 roll + one slot-2 roll + the independent rares** (`KLoot.roll`).
- **Guaranteed** (K5-K7): Kraken shards 1/1/2/2/3 (into the Hunting Box like every chest shard), Leviathan Teeth
  1/1/2/2/3, Crimson Essence 100/250/500/1,000/2,000 (**estimate**: the reference publishes only Infernal 2,000,
  unconfirmed; lower tiers are 10x the free chest) - dial `kuudra.paid-essence-scale` 1.0.
- **Free chest** (K8): 10/25/50/100/200 essence (official June-2025 values; was the old wiki 1-2/14-22/...).
- **Slot 1** (K9, K10, K12, K20, K21, K23): the 20 **Basic-tier** armour pieces at 2.875 each from every tier (no
  HOT_/BURNING_/FIERY_/INFERNAL_ piece ever drops; higher tiers come only from crimson's prestige), Aurora Staff
  (`RUNIC_STAFF`) + Hollow Wand 0.7 each at every tier, the four Molten pieces 0.8..1.2, Burning Kuudra Core
  0.1/0.3/0.5 (Burning+), Mandraa 0.8/1.0/1.2 (Burning+), Tormentor / Hellstorm Wand 0.1 each and Ananke Feather
  0.2625 (Infernal only), and the reference's per-tier shard rows (2/2/6/11/17 kinds; Kraken is NOT among them).
  Fire Fury Staff, the Fiery/Infernal Leviathan Cores (crafted by accessories) and prestige armour left the chest.
- **Slot 2** (K1-K4, K11, K19, K22): four mana books x16.5 at **level == tier** (I at Basic .. V at Infernal), the
  **Leviathan Tentacle 16** (17.2% of Basic slot 2 -> 8.5% at Infernal, measured over 20,000 rolls), the Wheel of
  Embers 2.2..4.6, Fatal Tempo I / Inferno I 0.02/0.11/0.2/0.29/0.38 each (1 in ~4,662 chests at Basic, 1 in ~493 at
  Infernal; was 3.75% everywhere), teeth x5 / essence x500 (Burning+), Heavy Pearl x3 + teeth x25 / essence x2,500
  (Fiery+), and at Infernal teeth x100 / essence x10,000 / Heavy Pearl x10 / the Mandible (3) / the Kuudra-Skull
  travel scroll (1.5, `optional`: nobody registers `NETHER_FORTRESS_BOSS_TRAVEL_SCROLL` yet, so its weight drops out).
- **Rares** (independent, kept from research 04, TUNE): Leviathan pet `KUUDRA;<tier>` 0.3%, Enrager, Heat Core,
  Tentacle Dye (Infernal). The Wheel and the Mandible moved out of the rares into slot 2.
- The **Wheel of Embers** ladder is every shard any Leviathan chest can roll (17, Common..Legendary, from
  `hunting/shards.json`), the same at every tier; the guaranteed Kraken is never "the chest's shard" (`shardIn`
  skips it) and is not on the ladder.
- **Ananke shard's Wings of Destiny** (`value:wings_of_destiny`) now multiplies the Ananke Feather row's weight.
- **Mandible salvage** (K22): `/kuudra salvage [all]` or the grindstone on the hunt board (slot 42; shift-click = all)
  turns a Leviathan Mandible into 500 Crimson Essence (dial `kuudra.mandible-salvage-essence`). Registered as an
  OTHER recipe `kuudra:mandible_salvage` too, so the bazaar's market makers never bid essence above a bought and
  salvaged Mandible (the recipe uses the dial value at boot). The dungeons Gatekeeper salvage table already lists
  the Mandible but refuses bazaar goods, so it never worked there.
- **`ANANKE_FEATHER` registered here** (Ananke Feather, RARE consumable, `extra.rngMeterFeather`). **Hellstorm Wand is
  `HELLSTORM_STAFF`** (the reference id) and was already registered by weapons - the audit grepped `HELLSTORM_WAND`.
- Data shape: `loot.json` rows are `{item, count, weight, optional?}` in `guaranteed` / `slot1` / `slot2`;
  `tiers.json` drops `armorPrefix` / `bookLevelMax` and gains `kraken` / `bookLevel`. Loot preview (`/kuudra loot`)
  lists every row with its exact odds ("17.16%" or "1 in 4,662").

Verified: `bash tools/check.sh kuudra` -> CHECK OK; kuudra JUnit **22/22** (javac + JUnit launcher, no gradle): the
guaranteed part in 20,000 rolls per tier, Tentacle share per tier within 1% of research, Basic-only armour, book
level == tier, exact odds for Fatal Tempo / Wheel / Burning Core / Ananke Feather, the paid-essence dial and essence
bonus, every row names an item some module ships, rolled-shard share = 1-(1-p1)(1-p2), Kraken never the Wheel's
shard. Live self-tests `kuudra.research-numbers` / `items-exist` / `shard-pool` rewritten for the new model. Not run
on the server (integrator).

### Requests (lane 1)
15. **dungeons / slayer**: spending the **Ananke Feather** on an RNG meter is yours (item `ANANKE_FEATHER`,
    `extra.rngMeterFeather`); the reference says "a large amount of progress" without a number.
16. **crimson**: the reference's "Dusty Travel Scroll to the Kuudra Skull" (`NETHER_FORTRESS_BOSS_TRAVEL_SCROLL`) needs a
    Forgotten Skull warp on the Crimson Isle; once you register the item, it drops at Infernal by itself.
17. **hunting**: `SHARD_KRAKEN` now comes from every paid chest (1/1/2/2/3); its netted-critter source can go (your C16).
    If you change a shard's rarity, rerun `tools/gen/kuudra/gen_kuudra.py` (the Wheel ladder copies rarities; a unit
    test fails on a mismatch).
