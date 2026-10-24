# core-world — hand-off

Packages: `com.skyclone.core.world` (the WorldService, Bukkit) and `com.skyclone.gen` (the PURE builder
toolkit — no `org.bukkit` import anywhere under it), plus `tools/preview.sh`.

## What works (verified how)

| Piece | Verified by |
|---|---|
| `bash tools/check.sh core` | **CHECK OK** (165 files) |
| Builder toolkit + walkability + renderer | 11 JUnit tests in `plugin/src/test/java/com/skyclone/core/world/GenToolkitTest.java`, all passing (run with a reflective runner against the JUnit API jar, since gradle is shared) |
| `tools/preview.sh` | Run on `com.skyclone.gen.sample.SampleIslandGenerator` (default seed and seed 12345): `RESULT: OK`, PNGs read and judged legible |
| Scale | 60M MemoryCanvas writes in 0.44 s (197 MB heap). A 620x620 island with mountains, caves and ~3,000 trees (11M blocks): generate 0.8 s, walkability 1.7 s, all 4 PNGs 1.4 s |
| Floating-block check | Caught real bugs in my own builders (diagonal tree limbs, hip-roof rings, coastline islets) — all fixed, now 0 non-decorative floating components |
| WorldService live (Paper) | **NOT run on a server** (the gate agent does that). Compiles against paper-api 26.1.2; uses the 26.1 `org.bukkit.GameRules` names |

## For island builders (≈10 agents) — read this

```
bash tools/preview.sh com.skyclone.mod.<id>.<YourGenerator> [seed] [crop=x1,z1,x2,z2] [--full]
```
- Writes `previews/<islandId>/top.png, iso.png, iso_nw.png, walk.png, report.txt` and prints the paths.
  **Read the PNGs with the Read tool.** `crop=` adds `crop_*.png` zoomed on one district (use it on a 600x600 hub — the
  whole-island iso is only 2 px per block there).
- Compiles ONLY what your generator references (`javac -sourcepath`), so broken code elsewhere cannot block you.
  `--full` compiles api+core+gen+your whole module instead.
- Exit 0 OK · 1 spawn missing / anchor or portal unreachable / anchor inside a block · 2 compile error or crash.
- `walk.png`: green = reachable from `spawn`, orange = standable but cut off, red X = failed anchor (label says why),
  magenta box = `portal.*` area, coloured rectangles = zones. Tree canopies are excluded from "cut off".
- `report.txt` also flags: writes outside your declared `bounds()` (they will NOT be cleared on rebuild), **unknown block
  names** (checked against the 1,168 block ids of 26.1.2 in `resources/gen/known_blocks.txt`; they become stone in game),
  cut-off standable regions (rooftops are normal; a plaza is not), floating components (SMALL = ≤8 blocks, almost always a bug).
- Copy the shape of `com.skyclone.gen.sample.SampleIslandGenerator`: heightmap → flatten plots → fill → carve → reserve
  each structure → build → paths between door steps → plants/trees with keep-out → anchors from what builders return.

### Toolkit map (`com.skyclone.gen`)
| Class | Use |
|---|---|
| `MemoryCanvas` | the in-memory canvas (also what the game builds from) |
| `OffsetCanvas`, `GuardCanvas` | translate / count out-of-bounds writes |
| `Noise` | seeded Perlin 2D/3D, `fbm2/3`, `ridged2/3`, `billow2`, `warped2`, `warp2` (inputs: divide block coords by feature size) |
| `Hash` | order-independent per-position randomness: `unit`, `range`, `chance`, `salt(seed,"name")`, `random(seed,"name")` |
| `Palette`, `Fill` | `Palette.of("stone_bricks:6,mossy_stone_bricks:2,cracked_stone_bricks:1")`; `palette.at(x,y,z)` is deterministic |
| `Blocks` | correct states: `stairs(id, facing[, half, shape])`, `slab(id, type)`, `log(id, axis)`, `door(c,x,y,z,wood,facing,hinge,open)`, `ladder`, `wallTorch`, `lantern(hanging)`, `leaves`, `trapdoor`, `fenceGate`, `barrel`, `banner`, `wood(type, part)`, `cw/ccw/opposite/dirOf` |
| `Connect` | fences/panes/bars/walls are connected AUTOMATICALLY after generation (core and preview both run it). Write plain `oak_fence` |
| `Shapes` | box, boxShell, hollowBox, walls, replaceAir, replace, ellipsoid, sphere, hollowSphere, dome, cylinder, disk, ring, cone, torus, line (thick), arch, prism (polygon, hollow) |
| `Terrain` | `IslandShape` → `island()` heightmap (radial falloff, coast noise, cliffs, terraces, beaches, ridged mountains, rocky underside with stalactites; detached islets removed unless `keepIslets`), `Heightmap.flatten(plot, y, blend)`, `average`, `inland`, `slope`; `fill(c, h, SurfaceRules)` (grass/dirt/stone, cliff rock on steep slopes, snow above, sand below, underside palette); `pond`, `fillBasin` (refuses to write if it would leak), `fillLevel`, `carveCaves`, `carveTunnel`, `grassAndFlowers`, `scatter` |
| `Trees` | oak, big_oak, birch, spruce, mega_spruce (2x2), pine, dark_oak (2x2), jungle (2x2, vines), acacia, cherry, red/brown giant mushroom, dead, palm, azalea; `grow(c,type,...)`, `forest(c,h,area,seed,density,spacing,"oak:4,birch:2",coastMargin,keepOut)` |
| `Builders` | `House` (floors, gable/hip/flat roofs with correct stair facing + outer corners, door + outside step, glass-pane windows, ladder between floors, hanging lanterns, furniture, chimney → `doorStep`, `inside`, `peakY`, `reserve()`), `Tower` (round/square, cone or battlements, ladder to a walkable top → `platform`), `marketStall` (→ vendor/customer spots), `fountain`, `well`, `lampPost` (post/street/torch/soul), `path` (3-wide, follows terrain, smoothed to ≤1 block per block, stairs/slab steps, clears terrain above, never paves buildings → `PathResult` for keep-outs), `bridge`, `fenceLine`, `gate`, `crates`, `flowerPot`, `banner`, `wallBanner`, `bench`, `campfire`, `ground()`, `footing()` |
| `Reservations` | reserve each building's box first; overlapping claims are refused and listed in the report via `layout.data("reservations", res.summary())` |
| `Walkability`, `PreviewRenderer`, `IslandReport`, `IslandRun`, `BlockColors`, `KnownBlocks`, `BlockInfo`, `BlockState` | the checker/renderer (also used live by `/sb island preview`) |

### Exact behaviours builders must know
- `y` passed to every builder is the FLOOR/GROUND block; people stand at y+1. Use `layout.standAt(name, x, y, z, yaw)` with
  the ground block. Builders extend foundations/posts down to the first solid block (≤12), so they never float.
- Stairs `facing` = direction you walk to go UP. A roof's south slope faces north.
- Walkability model: step up ≤ 1.19 blocks (a full block, or a stair/slab step — NOT a slab on top of a block),
  drop ≤ 4, 1.8 head room, doors and fence gates count as open, fences/walls/panes/bars/chains/hanging lanterns block,
  ladders/vines/scaffolding climb, water is swimmable. An anchor passes as "near" if a reachable cell is within 2 blocks
  (an NPC behind a counter). Carpets under an anchor are fine.
- Skip anchors that are not meant to be walked to with `layout.data("walk.ignore", "cam.,spawn.mob.")` (name prefixes).
- Leaves are always placed persistent in game, and leaf decay is cancelled anyway.
- `nether_portal[axis=x]` / `end_gateway` blocks are fine as portal decoration: vanilla portal travel is cancelled in every
  game world; the `portal.<warpId>` AREA is what warps (it must be registered as a warp, see the self-test).
- Renamed ids are aliased in game (`chain`→`iron_chain`, `grass`→`short_grass`) but the preview flags them — use the new names.
- Default seed = `IslandRun.seedOf(islandId)`, identical in preview and game, so the preview IS what gets built.

## For module authors — WorldService behaviour

- **Worlds**: every island is a void world `sb_<id>` (no terrain/caves/structures/decorations/mobs). Game rules set on
  creation: natural mob spawning off (incl. phantoms/patrols/traders/wardens), weather and daylight cycle off, keepInventory,
  no fire spread, no mob griefing, immediate respawn, no advancement messages, no raids, no vine spread, difficulty NORMAL.
  Time is set from `Sky.calendar().now()` every `world.time-sync-seconds` (calendar 6:00 = tick 0) unless `fixedTime >= 0`.
  THE_END worlds: the vanilla dragon fight is switched off (previously-killed, dragon removed, exit portal cleared); natural
  ENDER_DRAGON spawns are cancelled — spawn yours with `SpawnReason.CUSTOM` (MobService does).
- **Building**: generation runs off-thread (`SkyClone-WorldGen`), placement on the main thread, chunk by chunk (async
  loads, plugin tickets), `world.build-budget-ms` per tick (default 12), physics off, centre chunks first. A rebuild first
  clears every non-air block of the previous build's bounds that the new build does not write. One build at a time;
  instance builds jump the queue. Progress every 5% to the sender. Interrupted builds are marked `"building"` with the
  union of old+new bounds and redone fully next boot.
- **Layout timing**: `layout(id)` is available as soon as the generator has run — BEFORE all blocks are placed.
  On the very first boot, `afterModules()` computes missing layouts synchronously (dial `world.boot-layouts`) so the UI
  core's NPC spawning finds anchors. Stored at `plugins/SkyClone/islands/<id>.json`
  (`{island, version, seed, builtAt, blocks, status, bounds, buildMillis, unknownBlocks, outsideBounds, layout{anchors, areas, zones, data}}`);
  a restart never rebuilds unless `generator.version()` went up.
- **Instanced islands (IMPORTANT)**: `layout(id)`, `anchor(id, name)`, `anchors()`, `area()` and zone boxes are
  **TEMPLATE-relative**. Add `instanceOrigin(id, key)` (x and z; y is 0, so template y is world y). `anchor()` on an
  instanced island returns the template position in its world (= slot 0's position). Slots lie on a square spiral of
  `instanceSpacing` (slot 0 at 0,0). Table `world_instances(island, slot, key, state)` — 0 assigned, 1 built,
  2 released (blocks cleared lazily when the slot is reused). `ensureInstance` builds once per key and completes with
  origin + the template's `spawn` anchor. All instances use the island's base seed (see API requests).
  `zoneAt`/portals translate instanced positions to template space automatically.
- **Zones**: tracked every `world.zone-interval-ticks` (10), smallest box wins, `SkyEvents.IslandChange` /
  `SkyEvents.ZoneChange` fired (ZoneChange `to=null` when leaving all zones or the island). First entry into a zone:
  title `&6&lNEW AREA DISCOVERED!` / `&<color>⏣ <name>` + sound; remembered in member data `"world"`
  (`WorldData{islands:Set, zones:Set("island:zoneId")}`). A zone `requirement` blocks entry (pushed back to the last
  allowed position, else sent home) with `You need <describe> to enter ⏣ <name>!`. `zoneOf(p)` uses the cached state.
- **Requirement resolver** `visited:<island>` → member data `world.islands` (describe: `&aVisiting <Display Name>`).
- **Portals**: walking into any area named `portal.<warpId>` (feet or head inside) calls `warp(p, warpId)` with a 3 s
  cooldown, then a title (`&b<Warp name>` / `&7Warped!`) and an enderman sound. A portal without a registered warp says
  "This portal leads nowhere yet." once and logs it.
- **Warps**: `registerWarp(...)`; `warp()` checks the island's `IslandDef.requirement` AND the warp's requirement
  (`checkOrTell`), teleports async to the anchor (fallback `spawn`), says `&7Warping to &b<name>&7...`. A warp to an
  INSTANCED island uses the player's profile id as the key (ensureInstance, then teleport to origin + anchor).
  `/warp` with no args lists warps (clickable; locked ones show the requirement), tab-complete lists unlocked warps only.
  `/hub` (alias `/lobby`).
- **sendHome / join**: `sendHome` = `Features.PrivateIsland.home(p)` when that module is installed and returns non-null,
  else the hub warp/anchor, else the default world spawn. On `ProfileReady` players are sent home unless
  `world.join-location` = `last` and they logged out in a game world (first join always goes home).
- **Block rules** (game worlds only; other worlds untouched):
  - Break: bypass → allowed; zone flagged `BUILD` → allowed (on `private_island`, only if `PrivateIsland.canBuild`);
    otherwise the event is CANCELLED and then offered to break handlers by ascending `priority()`; the first whose
    `handles()` returns true gets `onBreak` (exceptions are caught and logged). Listener priority LOW.
  - Place / buckets / signs / bone meal / hanging & entity placement: cancelled outside BUILD.
  - Explosions remove protected blocks from the block list; fire never spreads or burns (flint & steel only in BUILD);
    leaves never decay; snow/ice/coral/farmland never form or fade outside BUILD; gravity blocks never start falling
    outside BUILD; liquid from a BUILD zone cannot flow out of it; farmland is never trampled (dial `world.no-trampling`).
  - Item frames, paintings, armor stands: no damage/rotation/removal outside BUILD.
  - **Right-clicking containers and workstations** (chests, barrels, furnaces, anvils, enchanting tables, brewing stands,
    crafting tables, beds, flower pots, signs, lecterns, …) outside BUILD gets `setUseInteractedBlock(DENY)` at
    **HIGH** priority. A module that gives such a block a meaning (the enchanting table at `npc.enchanting_table`,
    the brewing stand at `npc.brewing_stand`) must listen at **NORMAL or lower** (it still receives the click) and open
    its own menu. Doors, gates, trapdoors, buttons and levers stay usable.
  - `/sb buildmode [player]` toggles the bypass (cleared on quit).

## Fixes after integration round 3 (core fixer, vitals/world)

**Finding:** four void deaths in a row (each half the purse, 513M -> 32M in the ledger), then the player on the
limbo platform in the default world. **Cause, from the server log and the ledger:** the test driver ran the vanilla
`/tp Integ3 0.5 72 56.5` over RCON. A console `/tp` with bare coordinates resolves in the console's level, the
**default overworld**, not the player's world, so the player was dropped over the void next to limbo. The deaths are
exactly 4.8 s apart (`economy_ledger` ids 61-64, `death: Fell into the void`), which is the fall from y72/y65 to the
void line: the first death respawned on the 7x7 limbo platform, and the driver's teleport-sword clicks (Highlord)
carried the player straight off it again, three more times. Not a Dungeon Hub problem; the hub's spawn is fine.
Two real bugs sat under it, both fixed in `core/items/VitalsServiceImpl` + `PlayerStateListener`:

1. **A death outside a game world respawned in limbo and stranded the player there** (no message, not queued for
   the hub). `respawnPoint` now never returns limbo when anything better exists. Order: shared built island -> its
   `spawn`; private island -> `PrivateIsland.home`; another instanced island -> the `spawn` of the SLOT the player
   died in (found from the position, `VitalsMath.slotOrigin`, so no key is needed - this also closes core-items'
   old risk where dungeon/garden instance deaths went to slot 0's spawn); everything else (default world, unbuilt
   island, a spawn with no floor under it) -> home = private island home, else hub spawn, else limbo. Every
   candidate must have a floor within 8 blocks under the feet (`VitalsMath.hasFloor`), so a respawn can never be
   mid-air. A respawn that still lands in limbo calls `sendHome` next tick, which queues the player for the hub.
   The respawn teleport stays synchronous on purpose (an async one leaves the player in the void for void ticks).
2. **Deaths outside every game world cost half the purse.** Nothing there is gameplay; it is the staging void.
   `safeFromCoinLoss` is now true when `islandOf(world) == null`.

Tests: `plugin/src/test/java/com/skyclone/core/items/VitalsRespawnTest.java` (floor search incl. the round-3 spot,
the limbo platform, min-height stop; slot origin vs the instance grid). With `ItemsPureTest`: 24 passed, 0 failed.
`bash tools/check.sh core`: CHECK OK. **Not run on a server.**

**For whoever drives the client:** never use vanilla `/tp <player> x y z` from RCON - it lands in the overworld.
Use `/sb warpto <warp> <player>`, `/sb island tp <id> [anchor]` (as the player), or `execute in <world key> run tp ...`.

## Commands added
Player: `/warp [destination]` (aliases `/warps`, `/travel`), `/hub` (`/lobby`).
Admin (`/sb ...`):
- `island list` — id, built version vs generator version, blocks, last build, unknown blocks, anchors/zones, the queue
- `island build <id> [force]`, `island queue`, `island info <id>`
- `island tp <id> [anchor]` (instanced: your own instance), `island anchors <id> [prefix]` (click to tp), `island zones <id>`
- `island preview <id>` — runs the generator + walkability off-thread, prints the report (console: full, player: summary)
  and writes PNGs to `plugins/SkyClone/previews/<id>/`
- `island instance <id> [key]` (ensure + tp), `island release <id> <key>`
- `whereami` — island, zone (flags/requirement), template position for instances, 5 nearest anchors, can-build
- `buildmode [player]`, `warpto <warp> [player]` (ignores requirements)

## Dials added
`world.build-budget-ms` 12 · `world.build-preload-chunks` 6 · `world.build-progress-percent` 5 · `world.auto-build` true ·
`world.boot-layouts` true · `world.zone-interval-ticks` 10 · `world.time-sync-seconds` 30 · `world.portal-cooldown-seconds` 3 ·
`world.deny-message-seconds` 2 · `world.discover-titles` true · `world.protect-containers` true · `world.no-trampling` true ·
`world.join-location` "home" (home|last)

## Self-tests added
`world.memory-canvas`, `world.sample-island-walkable` (runs the sample generator + walkability live), `world.instance-grid-spiral`,
`world.islands-have-spawn`, `world.warps-resolve`, `world.portals-have-warps`, `world.block-states-valid`
(no stored build placed unknown blocks as stone), `world.builds-current` (every shared island built at its generator version;
passes while the queue is busy).

## API change requests (for the gate agent)
1. **Warp metadata**: `WorldService.warps()` returns ids only; the menus module's Fast Travel menu needs display name,
   island and requirement. Suggest `record WarpInfo(String id, String island, String anchor, String displayName, String requirement)`
   and `List<WarpInfo> warpInfos()` (implementation already has `WorldServiceImpl.warpDefs()`).
2. **Built notification**: `SkyEvents.IslandBuilt(String islandId, String instanceKey)` fired when placement finishes, so
   NPC/spawner/ore modules can (re)place things only after blocks exist. Today layouts exist before blocks do.
3. **Per-instance variety**: instanced islands all use one seed and one template layout. Dungeons need
   `ensureInstance(island, key, long seed)` + `layout(island, key)`; or dungeons should place rooms themselves.
4. **Instance anchor helper**: `Location instanceAnchor(String island, String key, String anchor)` (exists internally) so
   modules do not hand-add the origin.
5. `boolean isBuilt(String islandId)` / built version, for self-tests in island modules.

## Known risks
1. **Never run on a live server.** World creation, `getChunkAtAsync` + tickets, the new `GameRules` keys, and the
   THE_END dragon-fight suppression are API-correct but untested; the gate agent should run `/sb selftest world` and
   `/sb island build <id>` and watch TPS.
2. **First boot** computes every missing island layout synchronously (`world.boot-layouts`), which can add tens of
   seconds to startup when all ~20 islands are new; turn the dial off to trade that for NPCs appearing late.
3. **Vanilla interaction denial at HIGH** may hide a module's block interaction if that module listens at HIGH/HIGHEST
   with `ignoreCancelled = true`; modules must listen at NORMAL or lower (documented above).
4. Instanced islands are built from the template seed; a generator that varies by seed will have layouts that do not
   match its instances.
5. `MemoryCanvas` holds a whole island in memory during a build (≈200 MB per 60M blocks); builds are serialized so only
   one canvas is alive at a time.

No proper nouns were invented (core has no renames); the sample island ("Sample Isle") is not shipped content.

## Fixes after integration round 3 (core fixer, nice-to-have requests)
- **dungeons Requests A5 / B4 — untagged entities in game worlds are never auto-removed.** Core itself never removed
  them (its sweeps only touch entities carrying core's own PDC tags: `MobServiceImpl.cleanGhosts`, the NPC stray
  sweep), but VANILLA did: a plain Creeper / Silverfish / Bat / Sheep puzzle prop despawns as soon as no player is
  within 128 blocks (randomly beyond 32). `ProtectionListener.onSpawnKeep` (CreatureSpawnEvent, MONITOR) now calls
  `setRemoveWhenFarAway(false)` on every creature spawned in a game world for any reason except NATURAL (natural
  spawning is off there, so everything else was put there on purpose). It does not touch `setPersistent` (saving to
  disk), so a module's `setPersistent(false)` props still vanish on restart; removing props stays the spawner's job.
  Hanging props (ItemFrames) were already safe: physics/obstruction breaks are cancelled outside BUILD zones.

## Fix after playtest "newplayer" (core fixer, 2026-10-03) — island gathering counts toward collections
**Problem (major):** blocks broken by hand on the private island (a BUILD zone) were plain vanilla breaks, so their drops
landed as ordinary items and never went through `Sky.loot().give` — no `ItemGain`, so 5 oak logs + 6 cobblestone left
Collections at "Discovered 0/90", and the Cobblestone Minion's Quick Upgrade (Cobblestone I) was reachable only through the
minion itself. There was also no shared notion of "a player placed this", so routing drops naively would have let
place-and-break farm any collection.

**Fix (root):**
- `core/world/PlacedBlocks` — core now marks every block a player places in ANY game world: one bit per position, a
  `long[64]` per chunk section in the chunk's PDC (`skyclone:placed_<section+256>`), no cap, survives restarts. Marks
  move with pistons (extend/retract, by the piston's facing) and falling blocks (entity PDC `placed_falling`); a mark is
  cleared when the block is broken, and when something NATURAL takes the spot (cobblestone/obsidian forming in liquid,
  a crop/melon/cane growing into air, a tree growing, a mushroom spreading into air, liquid washing a block away, ice
  melting, explosions). Concrete powder hardening, grass creeping over placed dirt and coral dying do NOT clear it.
  `BuildJob` clears the marks of every chunk an island (re)build touches, so a reused instance slot starts clean.
- `core/world/GatherListener` — on a gather island (dial `world.gather-islands`, default `private_island`), a hand break
  that was allowed (BUILD zone, not bypass/creative, not a container) of a block that was NOT placed hands its drops to
  the player via `Sky.loot().give` with a natural source (`FORAGING` logs/leaves, `FARMING` crops/cane/melon/mushrooms,
  else `MINING`) — so collections count it, sacks absorb it, overflow drops owned at the feet. Like the reference, island
  drops now go straight into the inventory. Ripe crops always count (a planted seed is placed, a ripe crop is a harvest);
  unripe ones never do. Only item types the block itself drops are routed (`Block.getDrops` + gravel→flint); anything
  else in the event (a placed torch knocked off the wall) stays a vanilla drop. Placed blocks keep their vanilla drops.
  Cane/cactus/bamboo above the broken block are taken at once (top down): grown pieces count, stacked-up ones drop plain.
- **API (additive):** `WorldService.playerPlaced(Block)` (default `false`; core implements it). True for a marked block,
  and still true for the rest of the tick in which a placed block is broken, so a module's BlockBreakEvent listener gets
  the right answer at ANY priority (core un-marks at MONITOR).
- Dials: `world.gather-collections` (flag, default true), `world.gather-islands` (text, default `private_island`).
- Self-test `world.placed-blocks` (mark exactness, `playerPlaced`, piston move, broken-this-tick, double unmark).
  Unit tests: `plugin/src/test/java/com/skyclone/core/world/GatherMathTest.java` (5/5 passing).
- Verified: `bash tools/check.sh core` CHECK OK (170 files); `check.sh` mining / foraging / farming / island /
  collections all CHECK OK against the new core. NOT run on a server (by instruction) — needs a live check: chop the
  island oak + mine its cobblestone → Collections shows Oak Log / Cobblestone progress; place+break cobble → no progress.

**Requests (other modules):**
- **mining:** Mining XP on the private island is still 0 — `DigEngine.mineable` excludes BUILD zones and break handlers
  only see cancelled (non-BUILD) breaks, so no mining code ever runs there. Add a MONITOR `BlockBreakEvent` listener for
  `private_island` (like foraging's `Chopper.onVanillaBreak`) that awards the block table's XP and fires
  `SkyEvents.Gather(MINING, ...)`, skipping `Sky.worlds().playerPlaced(block)`. Do NOT give the drops — core already
  routes them through the loot service (giving them again would double the collection).
- **foraging / farming:** both keep their own placed-block lists in chunk PDC (`Chopper.placedKey`, `FarmListener.farm_placed`,
  foraging capped at 4096 per chunk). They can switch to `Sky.worlds().playerPlaced(block)`, which has no cap and follows
  pistons/falling blocks. Neither must start giving the BASE drop through loot on the private island (core does that now);
  extras (Foraging Fortune) through loot remain correct.

## Fix after playtest wave4-endgame (admin tools row 17, 2026-10-04) — `/sb warpto` to an instanced island

**Symptom:** `/sb warpto garden W4Endgame` over RCON answered "Sent" and the tester saw the player still on their
old island; `/warp garden` a minute later worked in 2 s.

**What the log shows:** the warpto queued a FIRST build of that player's Garden instance (`garden#4`, 19:47:48 ->
19:48:05, 16 s). The later `/warp garden` found the instance built, so it was instant. The old warpto replied
"Sent" the moment the warp was *queued*, gave the admin nothing about the 16 s build, and nobody read Paper's
teleport result, which is a silent `false` when a teleport is refused (vehicle, passenger, a cancelling listener).
Whether the late teleport was refused or simply not waited for cannot be told from the log, so both are closed:

- `WorldServiceImpl.warp(p, id, ignoreReq, done)`: new overload whose `done` runs on the main thread when the warp
  ENDS (`null` = arrived, else why not). `Sky.worlds().warp(p, id)` and every module caller are unchanged.
- The teleport result is read now (`arrive`): a refusal leaves the vehicle / ejects passengers and retries once;
  a second refusal is told to the player ("Could not take you to X") and logged `[world] warp of ... refused twice`.
  This applies to `/warp` too.
- A first instance visit tells the player "Preparing The Garden - the first visit builds it..." instead of a bare
  "Warping..." followed by 16 s of nothing.
- A newer warp supersedes an older one still waiting on a build (`warpSeq`), so a late build cannot yank someone
  back after they warped elsewhere.
- `/sb warpto`: lower-cases the warp id, names the valid warps on a typo (it used to print nothing to the admin),
  says "Building <p>'s instance of The Garden first; they are moved the moment it is ready" when a build is queued,
  says when the warp was refused at once, and reports the real outcome when it ends ("Sent ..." / "Could not send
  ...: reason"). Every outcome is also logged as `[world] warpto <id> <player>: arrived | NOT sent - <why>`, which
  is what an RCON caller should read (its reply has already been returned by the time a build finishes).
- New package helpers: `WorldServiceImpl.instanceKey(Player)` (profile id, else uuid) and `instancePending(island, key)`.

**For whoever drives the client:** after `/sb warpto garden <p>` on a first visit, wait for the
`[world] warpto garden <p>: arrived` log line (~16 s), not the RCON reply.

`bash tools/check.sh core`: CHECK OK (175 files); `check.sh all`: CHECK OK (914 files). **Not run on a server.**
