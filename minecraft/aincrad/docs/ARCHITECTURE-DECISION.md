# Stage 1 — Architecture Decision Record

Status: **decided for Phase 0 (empty boot) and the generation-ownership model; terrain-authoring
technique for Stage 4/5 intentionally left as an open sub-decision, see §6.**

## 1. Pinned environment (verified, not assumed)

| Item | Value | How verified |
|---|---|---|
| Paper build | `paper-26.1.2-74.jar` | Pulled from PaperMC's **current** API, `https://fill.papermc.io/v3/projects/paper/versions/26.1.2/builds`. The `api.papermc.io/v2` endpoint used by prior research is dead (`410 Gone`, sunset 2026-07-01) — this project's own `FLOOR-01-SOURCES.md` S11 citations predate that sunset. |
| SHA-256 | `1d70b1dab9cf4a6de615209a536f3a45a2186240253c428213ce2188ab95e5f7` | Downloaded to `server/paper-26.1.2-74.jar`, hash confirmed locally with `sha256sum`. |
| File size | 52,893,229 bytes | Confirmed on disk. |
| Java | Temurin 25.0.3+9 LTS | Already installed at `%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-25.0.3.9-hotspot`, matches `Find-Java25` in `start-dev-server.ps1` and the jar's own `version.json` (`"java_version": 25`). |
| Data pack format | major `101`, minor `1` | Extracted directly from `version.json` inside the downloaded jar (`pack_version.data_major/data_minor`) — not guessed. |
| Resource pack format | major `84` | Same source; matches independent web confirmation ("26.1 ships with format 84"). |

Minecraft/Paper versioning changed from `1.21.x` to a year-based `26.x` scheme sometime after this
assistant's training cutoff; `pack.mcmeta` also changed from a single `pack_format` integer to
`min_format`/`max_format` pairs (confirmed via web search, since 25w31a). Both facts were verified
against live sources rather than assumed from stale training knowledge.

## 2. Generator approach

Two independent problems, deliberately solved with two different mechanisms:

### 2a. Dimension identity and empty/base terrain — datapack-native, not a custom Java `ChunkGenerator`

The permanent height profile and the `aincrad:floors_01_10` dimension are defined entirely through
a **datapack** (`dimension_type` + `dimension` JSON), not a plugin-registered `ChunkGenerator`. This
matches the precedent already cited in `FLOOR-01-SOURCES.md` S11 (the "2032-world-height"-style
datapack) and Paper 26.1's own storage model, confirmed from `papermc.io/news/26-1/`: dimensions now
live under `world/dimensions/` and datapacks under `world/datapacks/`, both inside the world folder
rather than the server root.

For **this** empty-boot proof, the dimension's generator is `minecraft:flat` with `layers: []` and
`biome: minecraft:the_void` — a deliberately empty world, which is exactly what Stage 3 asks for
("create only the empty/test world", "no full generation starts automatically").

For **Stage 4/5** (real terrain), two compatible options remain open and are not yet chosen — see §6.

### 2b. Hand-authored features (walls, roads, Palace, districts, buildings) — tick-budgeted placement queue

Town of Beginnings is not "terrain" in the noise-function sense; it is thousands of hand-authored
parcels. These are written through a **block-budgeted, resumable placement queue**, not a single
synchronous loop:

- Every feature (a wall segment, a building, a road tile) is decomposed into a list of block-edit
  batches keyed by a stable ID (`f01/tob/market/block_07/parcel_014`, etc.).
- A scheduler drains a bounded number of block edits per server tick (reusing the *pattern*, not
  the code, of Shadow Prison's already-proven "mine reset is block-budget sliced" rule from
  `PRISON-SERVER-MASTER-MAP.md` §3 — that project is read-only for this task, so this is
  independently re-implemented here under the new namespace).
- This satisfies "no synchronous live-server block-paste loop stalls Paper" and "no runtime
  world-building task exceeds the configured per-tick block budget" without inventing a new
  offline Anvil/NBT writer, which would duplicate a large amount of Minecraft's own chunk-format
  logic for no real safety benefit over a budgeted in-process queue.

## 3. Why this is Paper-26.1.2-compatible

- Uses only stable, decade-old datapack primitives (`dimension_type`, `dimension`, flat generator)
  whose structure predates the "dimension format changes in 26.3" flagged by `minecraft.wiki` —
  confirmed **not yet in effect** for our pinned 26.1.2 target.
- Uses Paper's own documented 26.1 world/datapack folder layout (`world/datapacks/...`), not the
  pre-26.1 server-root layout implied by some older tutorials.
- No custom Java registry code is required to merely change world height — avoiding an area of the
  Paper/Vanilla API (custom dimension + custom Java `ChunkGenerator` combined) that is the least
  standardized and hardest to verify without an interactive boot, which is currently blocked (§7).

## 4. Deterministic feature ownership / protected owner edits

`generation-ledger.sqlite` (Stage 4, not yet created) will record, per feature: `world_seed`,
`datapack_hash`, `generator_build`, `layout_schema_version`, `floor_design_version`, `phase_id`,
`feature_id`, `feature_version`, its chunk list, lifecycle state (`PLANNED → GENERATED → VALIDATED
→ COMMITTED`), and a committed content hash. Before any (re)write, the generator recomputes the
current content hash of the target region; a mismatch against the expected predecessor hash halts
that write and reports a protected-edit conflict instead of overwriting it. This is the same
contract already written into `floor-01-layout.json:generation_contract`.

## 5. Mask enforcement — and a discrepancy found while implementing it

The chunk mask compiler (town_core rectangle ∪ 3 corridor buffers, deduplicated) was implemented
and run **independently of any Minecraft/Paper code** to catch geometry mistakes before they become
generation mistakes. Result:

- `town_core`: exactly **5,680** chunks (chunk X `-40..39`, chunk Z `244..314`) — matches the spec.
- Full union bounds: chunk X `-60..67`, chunk Z `190..314` — **matches** the spec exactly.
- Full deduplicated union **count: 7,912 chunks**, not the 6,290 stated in
  `floor-01-layout.json:first_review_slice` and `FABLE-5-ULTRA-SPAWN-BUILD-PROMPT.md`.

This was re-run with a deliberately oversized search window to rule out a truncation artifact; the
result was identical both times. Since the bounding box matches exactly but the count doesn't, the
most likely explanation is that the geometry (radius = 128 blocks, the three centerline segments)
is correct and locked, but the "6,290" figure itself was never mechanically computed — it reads as
an estimate that was asserted as exact.

**Resolved 2026-07-23:** the owner chose to correct the documentation rather than shrink the
corridor radius. `docs/floor-01-layout.json` (`known_corrections`), `FLOOR-01-RECONSTRUCTION-SPEC.md`
§21, and `FABLE-5-ULTRA-SPAWN-BUILD-PROMPT.md` now all state **7,912** chunks as the authoritative
Town review-slice mask size, with the exact same bounds, radius, and segments as before. The
verification script lives at `scripts/verify_chunk_mask.py` for independent re-checking, and now
also asserts against the corrected values.

## 6a. Terrain height pivot — resolved empirically, 2026-07-23

Before mask enforcement existed (§9), an attempt to use vanilla's unmodified `minecraft:overworld`
noise settings was tried and reverted. Real evidence why: with mask enforcement now active
(spawn point + world border, §9), a boot test with `type: minecraft:noise, settings:
minecraft:overworld` produced terrain at **Y 62-71** (`Terrain sample (0,4416): highestY=71
block=GRASS_BLOCK`), not anywhere near the required Y≈320 surface datum. This makes sense in
hindsight: vanilla's density-function graph is anchored to specific Y values internally (sea
level ~63, splines tuned for the -64..319 range) - simply raising the dimension_type's `height` to
2032 does not shift where vanilla's own terrain formula puts ground; it only adds unused empty
space above the same vanilla-height terrain. Reusing vanilla's settings verbatim was never going
to hit Y=320 without deep density-function graph surgery (which was already flagged as high-risk
in the original §6 below).

**Revised approach:** `minecraft:flat` generator with real solid layers (bedrock/deepslate/stone/
dirt/grass_block) summing to exactly fill `Y=-64..319`, so the flat baseline surface is exactly
Y=319 (Y=320 = first air block = the spec's playable datum). Gentle height variation
(`base=320, macro=simplex(x/520,z/520)*7, micro=simplex(x/68,z/68)*2`, per
`FLOOR-01-RECONSTRUCTION-SPEC.md` §5.1) is then applied by the plugin itself as a tick-budgeted
terrain-shaping generation phase - a hand-rolled Java simplex implementation, not vanilla's noise
system - which is exactly the right size for this: only ~2 million columns in the 7,912-chunk
mask need a thin few-block surface adjustment, not deep volumetric regeneration. This is *more*
faithful to the spec's exact formula than reusing vanilla noise would have been, and keeps every
number under direct, testable control.

## 6. Terrain-authoring approach for Stage 4/5 — resolved empirically

Real Floor One terrain (the macro/micro simplex formulas in `FLOOR-01-RECONSTRUCTION-SPEC.md` §5)
could be produced two ways:

1. **Density-function datapack terrain** — translate the region masks and noise formulas into
   vanilla `worldgen/density_function` + `noise_settings` JSON (fully datapack-native, zero Java
   world-gen code).
2. **Custom Java `ChunkGenerator`** registered by a plugin, with `dimension_type` still supplied
   by the datapack.

**Resolved 2026-07-23, via the actual Stage 3 boot test (§7):** `aincrad:floors_01_10` is loaded
entirely as a vanilla registry dimension straight from the datapack — Paper auto-creates it at
startup (`Selecting spawn point for level 'aincrad:floors_01_10'...`) with **zero plugins loaded**
(`[PluginInitializerManager] Initialized 0 plugins`). This confirms option 1 end-to-end: no plugin
code is required to get a correctly-height-bounded custom dimension running under Paper 26.1.2.
Option 2 (attaching a plugin `ChunkGenerator` to this same vanilla-registered dimension) remains
untested and is now the lower-priority path, since option 1 is proven and sufficient. Stage 4/5
terrain authoring should proceed via `worldgen/density_function` + `noise_settings` JSON.

## 7. Stage 3 empty boot test — PASSED (2026-07-23)

Both prior blockers are resolved: the owner personally read and accepted the EULA
(`server/eula.txt`, `eula=true`), and the mask count was corrected (§5). The guarded first-boot
checklist was completed for real (pre-boot snapshot: `backups/server-pre-first-boot-2026-07-23.zip`),
the `.aincrad-first-boot-ready` marker was created only after every item genuinely passed, and the
server was booted three times total. Evidence, not assertion:

**First attempt failed for a real reason, and that's useful.** The original `dimension_type` JSON
(§copied from general Minecraft knowledge, not verified against this exact build) was rejected at
startup with a fatal registry error:

```
Failed to parse either. First: Not a number: {"type":"minecraft:uniform",...}; Second: No key
max_inclusive in MapLike[...]; No key has_ender_dragon_fight in MapLike[...]
```

This revealed two real schema facts about Minecraft 26.1.2 that this assistant's training data
did not reliably know: `monster_spawn_light_level` rejects the older nested
`{"type":...,"value":{"min_inclusive":...}}` int-provider shape, and `has_ender_dragon_fight` is
now a required top-level field. Both were fixed
(`server/world/datapacks/aincrad-dimension/data/aincrad/dimension_type/floors_01_10.json`), and the
datapack hash was recomputed: **`110b069d2ea9...`** (was `13dee3469c50...`).

**Second and third attempts booted clean.** Real console evidence (`server/boot-test2.log`,
`server/boot-test3.log`):

```
[bootstrap] Loading Paper 26.1.2-74-ver/26.1.2@e4e17fc (2026-07-06T16:51:09Z) for Minecraft 26.1.2
Starting Minecraft server on *:25577
Selecting spawn point for level 'aincrad:floors_01_10'...
Done (12.679s)! For help, type "help"
```

- Paper build: confirmed `26.1.2-74-ver/26.1.2@e4e17fc`, matching the pinned/hashed jar exactly.
- Port: confirmed `25577`.
- Dimension resource location: confirmed `aincrad:floors_01_10`, auto-loaded by vanilla registry
  from the datapack alone, with `[PluginInitializerManager] Initialized 0 plugins` (see §6).
- **Build height bounds rigorously tested via real console commands**, not assumed
  (`server/boot-test3.log` lines 54-61, after `forceload add 0 0` to guarantee the target chunk
  was actually loaded):

  | Command | Result |
  |---|---|
  | `setblock 0 1967 0 minecraft:stone` | `Changed the block at 0, 1967, 0` — succeeded |
  | `setblock 0 1968 0 minecraft:stone` | `That position is out of this world!` — correctly rejected |
  | `setblock 0 -64 0 minecraft:bedrock` | `Changed the block at 0, -64, 0` — succeeded |
  | `setblock 0 -65 0 minecraft:bedrock` | `That position is out of this world!` — correctly rejected |

  This is an exact match for the locked inclusive build range `Y=-64..1967`. Both test blocks were
  set back to air immediately after and the forceload ticket removed, per Stage 3's
  "disposable verification area, then remove safely" requirement.
- Clean stop confirmed **twice** (boot-test2 and boot-test3): orderly `Stopping the server` →
  per-dimension chunk save → `All dimensions are saved` → worker/I/O pool termination → process
  exit, confirmed via `tasklist` showing no remaining `java.exe`. This also serves as the required
  stop/restart cycle (boot-test2 was a restart of the world boot-test created).
- No unrelated Shadow Prison process or folder was touched; no plugin-driven Floor One generation
  started automatically (there are no plugins yet).
- Post-boot-test snapshot taken: `backups/server-post-boot-test-2026-07-23.zip`.

**Known cosmetic non-issue:** a non-fatal `ERROR: No key layers in MapLike[{}]` appears during
world creation, caused by `server.properties`' default (empty) `generator-settings` for the
*vanilla* `minecraft:overworld`/`level-type=flat` — unrelated to `aincrad:floors_01_10`, which has
its own explicit generator settings in the `dimension` datapack file and loaded without error.
Vanilla falls back to a default flat preset for the incidental overworld; since that dimension is
never used by this project, this is left as-is.

## 8. Stage 4 foundation — built, compiled, and boot-tested (2026-07-23)

`plugin/` is a Gradle project (`./gradlew build`, Java 25 toolchain, `com.gradleup.shadow` for a
shaded jar) targeting `io.papermc.paper:paper-api:26.1.2.build.74-stable` — the exact coordinate
for the pinned build, confirmed to exist via `repo.papermc.io`'s maven metadata rather than
guessed. Two real, non-obvious build facts surfaced and were fixed along the way: Gradle's
toolchain auto-detection did not find the already-installed JDKs without an explicit
`gradle.properties` path hint, and `paper-api:26.1.2.build.74-stable`'s own published metadata
requires a Java 25 (not 21) toolchain to resolve at all.

Implemented (`plugin/src/main/java/org/aincrad/plugin/`):

- **`mask/ChunkMaskCompiler`** - faithful Java port of `scripts/verify_chunk_mask.py`, parsing the
  bundled `floor-01-layout.json` at runtime (not re-encoding coordinates as scattered literals)
  and validating itself against whatever `first_review_slice.exact_deduplicated_chunk_count` /
  `chunk_bounds` the JSON currently states.
- **`ledger/GenerationLedger`** - SQLite-backed (`org.xerial:sqlite-jdbc`), schema for
  `PLANNED → GENERATED → VALIDATED → COMMITTED` feature lifecycle, chunk ownership, and
  owner-protection flags; `tryTransition` centrally refuses to overwrite a protected feature or
  one whose content hash doesn't match the expected predecessor - this is enforced in one place,
  not left to caller discipline.
- **`layer/LayerActivationController` + `LayerSpawnGuard`** - pure calculation of which of the 10
  local floors owns a given Y, and which floors are active for a chunk column given online player
  positions; a `CreatureSpawnEvent` listener enforces this for mob spawns. **Honest limitation:**
  hopper/redstone/scheduled-block-entity suppression on inactive layers is not yet implemented -
  there is nothing built on Floors 2-10 yet for this to matter against, but it remains a real
  open item before those floors get real content.
- **`command/AincradCommand`** - `/aincrad status|verify <world|spawn|mask>|gen <plan|status|resume>|layer|config`.

**Real boot-test evidence** (`server/boot-test4.log`, plugin installed at
`server/plugins/aincrad-plugin-0.1.0.jar`, ledger created at
`server/plugins/AincradPlugin/generation-ledger.sqlite`):

```
[AincradPlugin] Loading server plugin AincradPlugin v0.1.0
[AincradPlugin] Chunk mask compiled: 7912 chunks (matches layout JSON).
[AincradPlugin] AincradPlugin enabled.
=== Aincrad status ===
Mask: 7912 chunks (PASS)
Ledger feature states: {PLANNED=0, GENERATED=0, VALIDATED=0, COMMITTED=0}
aincrad:floors_01_10 loaded. minHeight=-64 maxHeight=1968 (inclusive top build Y=1967)
Town of Beginnings has not been generated yet (Stage 5 not started).
[AincradPlugin] Disabling AincradPlugin v0.1.0
```

The `minHeight=-64 maxHeight=1968` line is the Bukkit API (`World#getMinHeight/getMaxHeight`)
independently confirming the exact same bounds the manual `setblock` boundary tests found in §7 -
two different methods agreeing is stronger evidence than either alone. Clean enable → command
execution → disable → normal world-save shutdown, no exceptions.

**Process note, for anyone continuing this later:** mid-diagnosis a leftover `java.exe` was
briefly and wrongly suspected of being a hung server process; it was actually an idle Gradle
daemon from the build step, confirmed via `Get-CimInstance Win32_Process` showing its real command
line (`org.gradle.launcher.daemon.bootstrap.GradleDaemon`). The actual Paper server process had
already exited cleanly. Worth knowing if `tasklist` ever shows an unexpected `java.exe` again -
check the command line before assuming it's the server.

## 9. Mask enforcement — built before enabling real terrain (2026-07-23)

Stage 4's `ChunkMaskCompiler` only *computed and reported* the mask; nothing actually stopped
generation outside it. This surfaced for real when switching the dimension's generator from void
to real terrain was about to happen: the server's own automatic "prepare spawn area" step touches
~11x11 chunks around whatever the dimension's spawn point is, which defaults near world origin
(0,0,0) - nowhere near the mask (which starts around block Z=3040). With real terrain enabled and
no enforcement, that automatic step alone would have generated real content outside the authorized
7,912-chunk mask. The owner chose to build real enforcement before proceeding. Implemented,
`plugin/src/main/java/org/aincrad/plugin/mask/`:

- **Spawn relocation**: `AincradPlugin#enforceMaskBoundary` sets `aincrad:floors_01_10`'s spawn
  point to `(0,323,4416)` (the documented Town arrival point, inside the mask) every boot, so the
  automatic spawn-area preparation only ever touches in-mask chunks - this is the fix for the
  specific risk above, independent of any event-timing question.
- **WorldBorder**: sized to the mask's bounding box (chunk X -60..67, Z 190..314, converted to
  blocks) plus a 128-block buffer, centered accordingly - a second, independent layer using a real
  vanilla-enforced hard boundary, protecting against any other trigger (commands, future bugs).
- **`MaskEnforcer`**: a `ChunkLoadEvent` listener that detects (cannot prevent - Bukkit has no
  event that vetoes vanilla chunk generation) any newly-generated chunk in `aincrad:floors_01_10`
  outside the precise compiled mask set, logs a severe alert, and increments a counter surfaced by
  `/aincrad status`. This is honestly a detection/alerting safety net, not a guarantee - the real
  prevention is the spawn point + world border, plus the discipline that all of this plugin's own
  generation code only ever requests coordinates from the compiled mask set.

**Verified, `server/boot-test5.log`:**

```
[AincradPlugin] Mask boundary set: spawn=(0,323,4416), border center=(63.5,4039.5) size=2176.0
World border: center=...x=63.5...z=4039.5... size=2176.0
Spawn location: ...x=0.0,y=323.0,z=4416.0...
Mask violations detected this session: 0
```

Zero violations, spawn/border set exactly as intended, on a boot that also generated real terrain
across several sample chunks — confirming the enforcement is active before any real generation
happens, not just present in code.

## 10. Phase 2 (macro terrain / Town foundation grading) — real generation, full mask, 2026-07-23

`plugin/src/main/java/org/aincrad/plugin/terrain/` implements the exact
`FLOOR-01-RECONSTRUCTION-SPEC.md` §5.1 formula (`base=320, macro=simplex(x/520,z/520)*7,
micro=simplex(x/68,z/68)*2`, own seeded gradient-noise implementation, not vanilla's) as a
tick-budgeted `GenerationScheduler` job, one ledger feature per chunk (`f01/terrain/shape/chunk_
X_Z`), triggered by `/aincrad gen start` and run to completion across the **entire real
7,912-chunk mask** (`server/boot-test8.log`):

```
Enqueued 7912 terrain-shaping chunk jobs (0 already committed, skipped).
Feature states: {PLANNED=0, GENERATED=0, VALIDATED=0, COMMITTED=7912}
Scheduler: pendingJobs=0 editsApplied=4970281 jobsCompleted=7912 hasWork=false
Terrain sample (0,4416): highestY=324 block=GRASS_BLOCK
Terrain sample (-400,4100): highestY=321 block=GRASS_BLOCK
Terrain sample (400,4100): highestY=318 block=GRASS_BLOCK
Terrain sample (0,4900): highestY=317 block=GRASS_BLOCK
```

All 7,912 chunks reached `COMMITTED`, ~4.97 million block edits applied, sample heights vary
naturally within the spec's 316-334 band (not the uniform Y=319 flat baseline anymore).
**Zero mask violations** (`grep -c "MASK VIOLATION" boot-test8.log` → `0`) across the entire run -
the enforcement built in §9 held under real load, not just the small earlier tests. Clean shutdown
confirmed afterward (same pattern as every prior boot).

**A real bug found and fixed along the way:** the first attempt at this (`server/boot-test7.log`,
then the corrected retry in `boot-test8.log` before the fix) called `GenerationLedger.planFeature`
7,912 times synchronously inside the `/aincrad gen start` command handler - one SQLite
transaction per chunk, all in a single command invocation. This froze the main server thread for
~30 seconds; Paper's watchdog logged five consecutive "server has not responded" thread dumps
before the command finally returned and generation proceeded normally. The block-EDIT budget was
already correctly tick-sliced throughout (evidenced by steady incremental progress once planning
finished) - only the one-time ledger-planning burst was not. Fixed by making planning itself
incremental (`TerrainShapingPhase#enqueueAllIncrementally`, 200 chunks/tick via its own
`BukkitRunnable`, `plugin/.../terrain/TerrainShapingPhase.java`), built and ready for the next
generation phase, not yet re-verified end-to-end with a fresh run (the already-COMMITTED chunks
from this run mean a repeat would just report 7,912 skipped - re-verification will happen
naturally as part of Phase 5+ generation).

## 11. Roads, Town Wall + gates, Plaza foundation — real generation, 2026-07-23

Implemented via three parallel subagents (given the exact spec geometry, the existing
`TerrainShapingPhase` as a required reference pattern, and an explicit instruction not to touch
any shared file), then integrated, built, and boot-tested by hand afterward:

- `plugin/src/main/java/org/aincrad/plugin/road/RoadGenerationPhase.java` - resurfaces the
  in-mask portions of the Main North Road / Horunka branch / Medai branch with andesite at
  `TerrainProfile.surfaceY`.
- `plugin/src/main/java/org/aincrad/plugin/wall/TownWallPhase.java` - the outer wall, modeled as a
  full circle (center `(0,4500)`, radius `500`) with three gate gaps, built from `surfaceY` up
  through a stone-brick base, walkway cap, and alternating-column crenellation. **Geometric
  decision, not derivable purely from the prose spec:** the outer_bounds/gate-position numbers in
  `floor-01-layout.json` are only mutually consistent with a full circle of that exact
  center/radius, not a literal chord-plus-semicircle reading of the prose "D-shaped" description -
  the JSON was treated as authoritative per this project's own reading-order rule.
- `plugin/src/main/java/org/aincrad/plugin/plaza/PlazaFoundationPhase.java` - the Central Plaza's
  paved disc (radius 108, concentric andesite/polished-andesite bands) only - not the clock tower,
  Teleport Gate, or Black Iron Palace, which remain separate later phases.

**A real, self-inflicted bug found via the boot test, not assumed fixed:** the shared
`GeometryUtils.thickArcColumns` helper (used only by the wall) took `startAngleDeg=0,
endAngleDeg=360` to mean "full circle," but normalizing angles mod 360 collapses `360 % 360` to
`0`, making the angular span exactly `[0, 0]` - i.e. only the single angle where the arc crosses
due east. First wall run: `server/boot-test9.log` shows only **1 chunk, 108 edits** touched, and
the verify-world sample at east `(500,4500)` showed `STONE_BRICK_WALL` while west `(-500,4500)`
showed plain `GRASS_BLOCK` - an asymmetry that would have been easy to miss without sampling both
sides. Fixed by special-casing spans `>= 360°` to skip the angle check entirely
(`GeometryUtils.java`). Re-ran: `server/boot-test10.log` shows **310 chunks, ~230k new edits**, and
both east and west samples now show `STONE_BRICK_WALL` at the same height. The one already-
COMMITTED sliver-chunk from the buggy run was correctly left alone and skipped, not re-painted -
the resumability contract held even across a mid-phase bug fix.

**Verified, `server/boot-test10.log` (after the fix):**

```
Incrementally planning and enqueueing 310 walls chunks (200/tick) - already-committed chunks are skipped as reached.
Feature states: {PLANNED=0, GENERATED=0, VALIDATED=0, COMMITTED=8697}
Terrain sample (0,4416): highestY=324 block=POLISHED_ANDESITE   <- plaza center, paved
Terrain sample (500,4500): highestY=333 block=STONE_BRICK_WALL  <- east wall
Terrain sample (-500,4500): highestY=333 block=STONE_BRICK_WALL <- west wall
Terrain sample (0,4000): highestY=319 block=ANDESITE            <- north gate opening: no wall, road surface only
```

Roads (`server/boot-test9.log`): 307 chunks, 22,018 edits. Plaza: 168 chunks, part of the same run's
58,751 cumulative edit count. **Zero mask violations across all of it.** Clean shutdown confirmed
after every run in this section.

## 12. Layout audit — five geometric defects found and fixed, 2026-07-23

The owner asked for a review of whether the 3D layout was actually any good. A numerical
consistency audit (`scratchpad/audit_layout.py`, re-runnable) checked the stated city footprint,
gates, districts, landmarks, and vertical bands against each other.

**The vertical architecture passed cleanly** and was left untouched: shell thickness runs 368
blocks at the floor's centre down to exactly 28 at the rim (the stated minimum); Floor 1's surface
to Floor 2's plate is exactly 100 blocks, matching canon's "~100 yards above town"; the Palace dome
clears the ceiling plate by 34 blocks; the hidden dungeon's depth band fits inside the shell at its
radius. The 160-block pitch across ten floors fits `Y=-64..1967` with room to spare.

**The horizontal city footprint had five real defects:**

| # | Defect | Measured |
|---|---|---|
| 1 | Wall shape admitted two incompatible readings | "D-shaped semicircle" + "northern chord at Z=4000" + "south arc r=500 @ (0,4500)" actually describe a **full circle** (Z=4000 is its tangent point, not a chord) |
| 2 | Flanking gates lay on the wall under **neither** reading | NW/NE gates 59 blocks *outside* the circle; 90 blocks *inside* the D-shape |
| 3 | District envelopes overflowed the wall | `north_gate_services` corners **153 blocks** past a circular wall; residential 87; church 59 |
| 4 | Palace crowded the North Gate | podium north edge Z=4026 vs gate Z=4000 = 26 blocks; minus a ~20-deep gatehouse = **6-block forecourt**, making review viewpoint 5 impossible |
| 5 | Palace `position` vs `bounds` disagreed | 14 blocks |

**Resolutions (owner-approved):**

- **D-shape adopted and made explicit** as `city_envelope` in the layout JSON, with the rejected
  circle reading recorded alongside it and why. The D-shape gains **+108,326 blocks²** of city area
  over the circle and brings every district to 91-100% retention after clipping.
- **Both flanking gates moved to `(±380, 4000)`** onto the north wall.
- **Palace podium depth 220 → 170**, giving a 31-block forecourt *and* widening the processional
  avenue 62 → 87 blocks. Width 328 and dome apex Y=386 unchanged, so silhouette dominance is
  preserved. `bounds` reconciled to `position ± depth/2`.
- **Wall rebuilt v1 → v2 → v3** as a real, backup-first ledger migration
  (`GenerationLedger#migrateFeatureVersion`, which refuses owner-protected or already-migrated
  features), demolishing the obsolete circular arc before building the D-shape.
- **Wall crest levelled** per 32×32 cell. v1 built every column off its own terrain height, so the
  parapet undulated with the noise; a single global crest would have produced 13-31 blocks of wall
  height. Per-cell levelling keeps it inside the spec's 13-16 band while reading as deliberate
  stepped masonry.

**Two follow-on defects that the fix itself introduced, caught by verification rather than
assumed away:**

1. *The migration grassed over a road.* v2's demolition used the raw v1 circle **without** v1's own
   gate exclusions — but v1 never placed wall inside its gate openings, so "demolishing" those
   columns cleared whatever legitimately occupied that ground. The verify sample at `(0,4000)` went
   `ANDESITE` → `GRASS_BLOCK` between `boot-test10.log` and `boot-test11.log`. Fixed in v3 via a
   `V1_GATES` exclusion list (legacy demolition columns 18,852 → 18,737).
2. *Moving the gates orphaned two roads.* An earlier version of the spec correction claimed "the
   road manifest needed no change." **That was wrong.** With gates at `Z=4000` but road polylines
   still starting at `Z=4090`, the Horunka route crossed the wall line at `X=-453` and the Medai
   route at `X=+454` — 73 and 74 blocks off-gate. The gates would have opened onto blank ground
   while the roads rammed into solid wall. Both routes' first points moved to `(±380,4000)`;
   lengths recomputed 3299→3232 and 5137→5069; road phase migrated v1 → v2 to repaint.

**Final verification, `server/boot-test12.log`** (every sample is labelled with what it is meant to
prove, so a wrong result is unmissable):

```
(0,4416)    Y=324 POLISHED_ANDESITE   <- plaza centre
(200,4000)  Y=335 STONE_BRICK_WALL    <- NEW north wall
(-200,4000) Y=334 STONE_BRICK_WALL    <- NEW north wall
(500,4200)  Y=336 STONE_BRICK_WALL    <- NEW east side wall
(-500,4200) Y=336 STONE_BRICK_WALL    <- NEW west side wall
(0,5000)    Y=333 STONE_BRICK_WALL    <- south arc, unchanged from v1
(400,4200)  Y=319 GRASS_BLOCK         <- WAS v1 circle wall, now interior (demolished)
(-400,4200) Y=321 GRASS_BLOCK         <- WAS v1 circle wall, now interior (demolished)
(0,4000)    Y=319 ANDESITE            <- north gate: road runs through it
(-380,4000) Y=319 ANDESITE            <- NW gate: road runs through it
(380,4000)  Y=320 ANDESITE            <- NE gate: road runs through it
```

753 chunk jobs, 694,301 edits, **zero mask violations**, clean shutdown. The three `ANDESITE` gate
readings are the load-bearing result: each gate now has road surface passing through it, which is
exactly what defects 2 and the road-orphaning follow-on both broke.

Both items left open at the end of §12 have since been resolved — see §13.

## 13. Gate-width reconciliation, plaza regrade, Black Iron Palace — 2026-07-24

**Gate widths: not actually a conflict.** The prose said the North Gate's *clear opening* was 15
while the JSON said `width: 20`. Read closely these measure different things — the structural gap
left in the wall versus the passable gap through the gatehouse. 20 structural / 15 clear leaves
2.5 blocks of jamb per side, consistent with the documented 30×20 gatehouse footprint, and the same
relationship holds for the flanking gates (15 / 11). Rather than overrule either document, both
values are now recorded explicitly on every gate (`width` + `clear_opening` + `width_semantics`).
No code change was needed: the wall generator already cut the *structural* gap.

**Central Plaza regraded flat (v1 → v2).** v1 paved each column at its own terrain height, so the
"crowd surface" undulated across roughly a 15-block spread — wrong for a monumental civic space and
a poor floor for the required 140×140 unobstructed area. v2 cuts and fills to a flat deck at
**Y=322**, taken from the layout JSON's own Town centre elevation (`center: [0,322,4500]`) and
consistent with the documented arrival point `(0,323,4416)` — a player on a Y=322 deck stands at
Y=323. Sub-base fill uses deepslate tile, with the outermost 2-block ring in stone brick so the
exposed embankment reads as a deliberate retaining course. Verified flat at four probes spanning
the disc:

```
(0,4416) Y=322   (0,4350) Y=322   (0,4480) Y=322   (-90,4416) Y=322   (90,4416) Y=322
```

**Black Iron Palace exterior built.** 264 chunks. Podium (328×170) graded flat at Y=328, broad
ceremonial stair on the plaza-facing side, colonnade on the southern terrace, main hall shell
(224×152, walls to Y=354, tinted-glass window bands), drum (r=84, to Y=366), flat roof terrace,
and dome to the locked apex **Y=386**.

*Massing decision worth recording:* a true hemisphere over the locked 84-block drum radius would
apex at **Y=452** — punching straight through Floor Two's structural plate at Y=420. The locked
386 apex therefore forces a shallow crown. Rather than stretch a 1:5 saucer across the whole drum,
this build places a properly-proportioned dome of radius 48 (rise 20, ≈1:2.4) at the centre and
treats the drum's outer ring (r=48..84) as a flat roof terrace. Every locked number is honoured and
34 blocks of clearance under the plate remain.

*Interiors are deliberately NOT built* — the hall is left hollow so the later interiors phase
(Monument Hall, processional nave, side galleries, prison-processing hall, offices, sealed
resurrection-chamber reference, maintenance corridors, water seam) can fill it without demolition.

**Shell verified by depth probes, not by `getHighestBlockYAt`.** An overhead height sample at
`(0,4210)` returned the drum's roof terrace rather than the hall wall beneath it, which looked like
a failure but was a mislabelled diagnostic — the drum (168 deep) legitimately overhangs the hall
(152 deep), so the terrace *is* the highest block there. Rather than assume, a
`/aincrad verify palace` subcommand was added that reads exact coordinates. All 12 probes pass:

```
PASS (0,328,4136) POLISHED_BLACKSTONE_BRICKS  <- hall floor = podium deck
PASS (0,340,4136) AIR                         <- hall interior hollow
PASS (0,340,4210) TINTED_GLASS                <- hall SOUTH wall exists at depth
PASS (0,340,4062) POLISHED_BLACKSTONE_BRICKS  <- hall NORTH wall exists at depth
PASS (110,340,4136) TINTED_GLASS              <- hall EAST wall exists at depth
PASS (83,360,4136) POLISHED_BLACKSTONE_BRICKS <- drum wall at r=83
PASS (70,360,4136) AIR                        <- drum interior open
PASS (0,386,4136) POLISHED_BLACKSTONE_BRICKS  <- dome apex shell
PASS (30,382,4136) POLISHED_BLACKSTONE_BRICKS <- dome shell at r=30
PASS (45,372,4136) POLISHED_BLACKSTONE_BRICKS <- dome shell near springline
PASS (0,375,4136) AIR                         <- under-dome void open
Palace shell: ALL PROBES PASS
```

Run totals: 432 jobs, **2,201,726 edits**, zero mask violations, clean shutdown, snapshot at
`backups/server-post-palace-2026-07-24.zip`.

The processional-avenue gap noted here was closed in §14.

**Still open from this section:** the palace podium leaves only 1 block of margin around the
168-diameter drum on its north/south edges (a consequence of the §12 depth reduction 220→170);
geometrically valid but tight, with no walkway on those two sides.

## 14. Processional avenue, clock tower, Teleport Gate, stair accessibility — 2026-07-24

**A real accessibility defect found in the Palace built in §13.** Its ceremonial stair used
`stepY = DECK_Y - step * 2` — **2-block risers**, which a Minecraft player cannot walk up without
jumping. That directly violates the spec's "a player must be able to reach every required service
without parkour", and it sat on the single most important route in the city (plaza → Palace).
Fixed as a v1 → v2 ledger migration: 6 steps of 1-block risers running Y=327→322 over Z=4222..4227,
landing exactly on the avenue deck. All 264 palace chunks re-ran through the migration (job count
264 + 12 + 4 = 280 confirms none were skipped as already-committed).

**Processional avenue built.** 17 blocks wide (X=-8..8) plus kerbs, Z=4228..4310, graded dead flat
at **Y=322** — the same level as both the plaza deck and the foot of the Palace stair, so the
entire spawn→Palace walk is now step-free. Lamp posts every 14 blocks along both kerbs (inside the
spec's 12–16-block grand-avenue interval), giving real block light rather than pack-dependent
lighting. Before this phase the Palace and plaza were 87 blocks apart across raw terrain and did
not connect as a designed route at all.

**Clock tower + Teleport Gate built as one structure** — an open-ground-floor "Arrival Rotunda"
standing over the gate, which satisfies the spec's three separate statements simultaneously (gate
"at plaza centre/beneath tower", tower "centred over… the Teleport Gate", player materialising
"beneath or immediately beside the Arrival Rotunda"). Composition, deck Y=322 → apex Y=366 (exactly
the spec's 44 blocks): open colonnade of twelve piers (323–336) → ceiling slab → shaft (338–350) →
clock band with a readable analog dial on all four faces, hands frozen at 10:10 (351–359) → bell
level with open arches and a real `BELL` (360–363) → stepped roof to a `SEA_LANTERN` crown at 366.
The Teleport Gate itself is a 9×7 polished-blackstone platform at the exact arrival point with a
12-block archway — walkable straight through, not a solid coloured wall, matching the spec's
"inactive state uses muted stone/glass".

**Verified — all 29 depth probes pass** (`server/boot-test16.log`), including the ones that
specifically test the accessibility fix rather than assuming it:

```
PASS (0,327,4222) POLISHED_BLACKSTONE_BRICKS <- palace stair tread 1 solid
PASS (0,328,4222) AIR                        <- stair 1 riser is ONE block (air above tread)
PASS (0,326,4223) POLISHED_BLACKSTONE_BRICKS <- palace stair tread 2 solid
PASS (0,327,4223) AIR                        <- stair 2 riser is ONE block
PASS (0,324,4225) POLISHED_BLACKSTONE_BRICKS <- palace stair tread 4 solid
PASS (0,325,4225) AIR                        <- stair 4 riser is ONE block
PASS (0,322,4227) POLISHED_BLACKSTONE_BRICKS <- stair foot lands on avenue level Y=322
PASS (0,322,4250) POLISHED_ANDESITE          <- processional avenue deck (flat Y=322)
PASS (0,322,4416) POLISHED_BLACKSTONE        <- Teleport Gate floor
PASS (0,330,4416) AIR                        <- Teleport Gate walkthrough clear (not a solid wall)
PASS (0,366,4416) SEA_LANTERN                <- clock tower crown at 44 blocks above deck
PASS (7,355,4416) BLACK_CONCRETE             <- clock dial, east face
PASS (0,361,4416) BELL                       <- bell
Palace shell: ALL PROBES PASS
```

280 jobs, 1,960,514 edits, zero mask violations, clean shutdown, snapshot at
`backups/server-post-avenue-clocktower-2026-07-24.zip`.

**Known spec tension, surfaced rather than silently resolved.** §7.3 demands a "minimum
uninterrupted crowd surface: 140 × 140 blocks" *and* a clock tower "centred over" the Teleport
Gate. A 23×23 tower at the exact plaza centre cannot coexist with a literally unobstructed 140×140
square centred there. This build treats the rotunda's twelve piers as a colonnade rather than an
obstruction — the paved deck is continuous and walkable straight through on every axis, with
13-block axial openings, so crowd flow and evacuation are unimpeded — but the strict reading is not
met. Moving the tower off-centre (the spec's alternative "immediately adjacent" wording) would
satisfy the literal number instead; that is an owner call, not one to make silently.

**Also not built (not claimed):** Teleport Gate *runtime* behaviour — persistence, arrival-slot
reservation, item serialisation, destination unlock — is server logic rather than geometry and
remains unimplemented. Bell-level interior access is deliberately absent rather than advertised
with a broken ladder. The tower footprint is 23×23 rather than the spec's 22×22, chosen so it is
exactly symmetric about the X=0 processional axis.

## 15. Black Iron Palace interiors — 2026-07-24

All eight interiors the spec names are built as a connected circulation graph, not decorated voids:

```
N   [ SEALED           ][  Prison-processing hall  ][ offices E1 ]
    [ resurrection ch. ][   (dais + counter line)  ][ offices E2 ]
    ----------------- maintenance cross corridor -----------------
    [ west gallery ][ MONUMENT HALL 61x47, open to the dome ][ east gallery ]
    ----------------- maintenance cross corridor -----------------
    [ west gallery ][      processional nave       ][ east gallery ]
S                    ^ grand entrance archway (17 wide, 16 tall)
```

Route from the plaza: avenue → ceremonial stair → grand archway → perimeter maintenance corridor →
nave → galleries / cross corridors → Monument Hall → processing hall → offices. A maintenance shaft
with a ladder drops from the perimeter corridor to a **sealed underground water-access seam** at
Y=315 that runs out toward the hidden-dungeon intake at `(144,315,4024)`. The seam carries a sealed
copper conduit run rather than flowing water — the spec forbids any leak into the shell below, so
there is deliberately no water source block anywhere in it.

The **resurrection chamber is the one deliberate dead end**: sealed behind solid iron with no door
at all. The spec asks for a "locked resurrection-chamber reference", and an advertised door opening
onto nothing would break the project's own no-fake-doors rule — so it is honestly walled.

**A real bug the probes caught, worth recording.** v1 modelled the Monument Hall as a *circle of
radius 60* centred under the dome. That was wrong twice: the spec asks for a "60 × 46 central
chamber" (a rectangle), and a 60-radius circle spans Z 4076..4196 — straight through both
cross-corridor walls into the nave and the processing hall. Since the "inside the rotunda" test
suppressed partitions and ceilings, it **silently deleted the processional nave's side walls and
its entire ceiling**. Three probes failed and pinned it exactly:

```
FAIL (17,330,4190) AIR  <- nave/gallery partition is solid here
FAIL (0,345,4190)  AIR  <- room ceiling present over nave
```

(The third "failure", `(0,340,4210)`, was a stale probe, not a defect — it was testing for wall at
the exact spot where the grand entrance archway had since been carved. Re-pointed off-axis and a
second probe added to assert the archway *is* open.)

Fixed as a v1 → v2 ledger migration using the rectangle the spec actually specifies, sized to sit
exactly inside the central band, plus new walls and a doorway between the Monument Hall and the
side galleries. **All 47 probes now pass** (`server/boot-test18.log`), including new ones asserting
the side galleries and processing hall have their own ceilings rather than being swallowed by the
hall:

```
PASS (17,330,4190) POLISHED_BLACKSTONE_BRICKS <- nave/gallery partition solid
PASS (17,330,4182) AIR                        <- nave->gallery DOORWAY open
PASS (0,345,4190)  DEEPSLATE_TILES            <- nave ceiling present
PASS (0,350,4136)  AIR                        <- Monument Hall open to the dome
PASS (31,330,4120) POLISHED_BLACKSTONE_BRICKS <- Monument Hall/gallery wall solid
PASS (31,330,4136) AIR                        <- Monument Hall/gallery DOORWAY open
PASS (50,345,4136) DEEPSLATE_TILES            <- side gallery has its own ceiling
PASS (-63,330,4080) IRON_BLOCK                <- resurrection chamber SEALED (no door)
PASS (-63,330,4097) AIR                       <- west offices reachable (door open)
PASS (144,317,4030) AIR                       <- seam reaches the dungeon intake
Palace shell: ALL PROBES PASS
```

161 chunks, 331,968 edits, zero mask violations, clean shutdown, snapshot at
`backups/server-post-palace-interiors-2026-07-24.zip`.

**Supporting change:** `BlockEdit` now carries an optional vanilla block-state string, applied via
`Bukkit.createBlockData` with a warn-and-fall-back path for malformed states. Without it a
generator could only place full cubes — fine for terrain and walls, but not for oriented blocks
(the seam's ladder needs it, and pitched roofs on 1,100+ buildings will need stairs and slabs).

**Not built (not claimed):** the interiors are architecturally complete but only lightly furnished —
the processing hall has a dais and counter line ready for later prison systems, but there is no
furniture pass, no signage, and no NPC/service anchors yet.

## 16. District parcel graph and 1,150 buildings — 2026-07-24

The largest remaining piece. Five new classes under `plugin/.../district/`:

- **`DistrictPlanner`** — lays street-separated perimeter city blocks, then subdivides each block
  edge into plots that front onto the street they came from. This is what makes "every public-facing
  door opens into a real interior" structural: a plot cannot exist without a street to open onto.
  Block interiors stay as courtyards.
- **`ExclusionZones`** — the D-shape city polygon plus every previously-committed feature (wall
  service band, three gate approaches, plaza + arcade, processional avenue, Palace precinct, the
  three outbound roads). Checked per-plot before a parcel is ever accepted.
- **`BuildingPalette`** — weighted deterministic families hitting the spec's roof balance directly
  (variants 0-6 terracotta ≈70%, 7-8 slate ≈20%, 9 copper/civic ≈10%) rather than by chance.
- **`BuildingBuilder`** — graded pad, footing, framed walls, real door, windows, climbable
  staircase, upper floor with bed, closed pitched roof with gable ends, optional cellar with
  ladder, role-specific furniture. The interior is carved as air *after* the shell is written, so
  there is no code path that yields a solid building with a door stuck on it.
- **`DistrictGenerationPhase`** — plans, resolves overlaps, thins to per-district targets, and runs
  one ledger feature **per parcel** (the unit the spec's acceptance test actually counts).

**Two real planner bugs, both caught by dry-running the parcel graph before writing any blocks.**
The `/aincrad gen plan` dry run reports counts, per-district and per-role breakdown, and an
all-pairs overlap check without touching the world — which is what made these cheap to find:

1. *486 parcels instead of 1,100+.* Corner-collision avoidance trimmed the maximum plot depth off
   **both ends of every edge run**. On a typical 36×36 block that left a 3-block run — under the
   7-block minimum frontage — so most block edges silently produced no plots at all. Fixed by
   giving the north/south terraces the block's full width (they own the corners) and trimming only
   the east/west runs by those two depths.
2. *2,602 parcels — overshoot.* Re-reading the spec, §7.1 gives **per-district** targets rather
   than one global number. Those are now enforced (market 270, inn/crafting 230, church 110,
   residential 360, north-gate 180 = **1,150**, inside the required 1,100-1,350), with even-stride
   thinning so unbuilt plots scatter as yards and gardens instead of leaving one bald corner.

**Result: 1,150 parcels, 0 overlapping pairs, 2,871,950 block edits, 0 invalid block-state strings,
0 mask violations, clean shutdown.**

**Acceptance verified over ALL 1,150 buildings** (not a sample), via `/aincrad verify buildings`
which derives each building's geometry from `BuildingBuilder.plan()` — the same source the builder
uses — so a checker can never drift from the thing it checks:

```
real door on frontage : 1150/1150  PASS
door opens to interior: 1150/1150  PASS      <- the no-fake-doors rule
centre headroom >=2   : 1150/1150  PASS
roof closed overhead  : 1150/1150  PASS
accessible upper floor: 835/1150 = 72%  (spec >=45%) PASS
cellar space          : 232/1150 = 20%  (spec >=12%) PASS
```

*A false failure worth recording:* the roof check first reported **0/1150**. A total zero is almost
always a bad test rather than 1,150 identically-broken roofs, and it was — the probe read a fixed
`wallTop+2/+3`, but the ridge sits at `wallTop+1+rise` with rise varying 3-6 by span, so it was
sampling the attic void underneath. Rewritten to scan the whole roof zone; the roofs were fine all
along.

**Supporting change:** `BuildingBuilder` was split into `plan()` (structural, derived values) and
`build()`, with furniture drawing from a separate random stream so that changing decoration can
never shift the structural draws verification depends on.

**Not built (not claimed):** interior room *partitions* within houses (each building is a single
open storey per level, furnished by role, not subdivided into rooms); no NPCs or service anchors;
no per-street lamp posts inside the districts (lighting exists on the avenue and inside buildings);
no signage. The spec's "Central Plaza and arcade: 80-100 modules" and "rim/wall service pockets:
80-100" are also not built — those would take the ledger from 1,150 toward the upper half of the
1,100-1,350 band.

## 17. Independent re-verification of the buildings — 2026-07-24

The owner reasonably pushed back on the roof result ("not sure what you mean the roofs are 0/1100,
just make sure they're all functional houses"). Since §16's fix was to the *verifier*, trusting it
would have been circular. Five further checks were added using deliberately different methods:

| Check | Result |
|---|---|
| every interior column roofed (full coverage, heightmap) | 1150/1150 PASS |
| `getHighestBlockYAt` > wall top (different API path) | 1150/1150 PASS |
| wall integrity above the window band | 1150/1150 PASS |
| staircase treads rise exactly 1 with 2 air above | 835/835 PASS |
| bed present on the upper floor | 835/835 PASS |

"Every interior column roofed" is the load-bearing one: it tests **every** column of **every**
building, so a hole anywhere in any roof fails, not just one at the centre. Wall integrity was
added specifically to hunt a suspected bug - neighbouring terrace roofs overhang 1 block into each
other's columns and the roof's clear-above pass could punch a taller neighbour's wall. It did not
occur.

**A second false failure, and how it was settled.** A sky-light-based seal test reported 0/1150.
Rather than assume, a diagnostic sampled sky light at three points that are physically buried:

```
[diag] skylight deep underground (0,100,4416)=15 (0,200,4700)=15
       sealed-chamber(-85,330,4080)=15  -> LIGHT DATA STALE, ignore light tests
```

Sky light reading 15 at 200+ blocks below ground, and inside the sealed iron resurrection chamber,
is impossible under correct lighting - Paper had not repropagated skylight after bulk generation.
The light test was therefore meaningless and was replaced by the geometric full-coverage test
above. Recorded here because "the test was wrong" is a claim that needs evidence, and this is it.

## 18. Safe zone and service anchors — 2026-07-24

**`safety/SafeZone`** implements the Town safe polygon as the D-shape envelope plus a 16-block
apron, and critically **Y-bound**: the spec warns that "a player in a cave directly beneath a safe
town is not safe unless that cave is explicitly included", so the safe band runs Y=300..420 - below
the deepest house cellar, up to Floor Two's plate - leaving the whole Floor One underbelly, water
seam and future hidden dungeon outside it. The southern apron is clipped at r=5000 rather than
extended, because 16 blocks past the southern arc is void, not ground.

**`safety/SafeZoneGuard`** enforces it: no hostile spawning, no PvP, no fire spread or ignition
(deliberate flint-and-steel excepted), no explosion block damage. It counts what it blocks so
verification can report actual behaviour instead of asserting the listener is installed.

**`service/ServiceAnchorRegistry`** binds all ten required services to real parcels, chosen
deterministically as the nearest unused parcel of a preferred role to a preferred anchor point.
Because it selects from the same parcel plan the buildings were generated from, every anchor is
guaranteed to sit inside a building that actually exists and has a working door.

**Verified** (`server/boot-test27.log`, via a `/aincrad verify spawn` that is now a real test rather
than the placeholder it had been since Stage 4):

```
arrival volume 9x7x5 clear: 315/315  PASS
stands on solid ground    : PASS
plaza is safe             : PASS
inside gate apron is safe : PASS
far outside NOT safe      : PASS
cave under town NOT safe  : PASS   (Y-aware)
past floor edge NOT safe  : PASS
service anchors bound     : 10/10  PASS
anchors stand in interiors: 10/10  PASS
```

Snapshot at `backups/server-post-safezone-services-2026-07-24.zip`.

**Not built (not claimed):** NPC *records* are not yet implemented - the spec wants 1,200-1,800
logical NPCs with a 160 active town-wide budget, and none of that exists; the anchors are locations
with no occupant. Service anchors have no interaction behaviour (no menus, no storage, no respawn
binding logic) - they are bound, versioned locations that later systems can attach to. The
Teleport Gate still has no runtime behaviour.

## 19. NPC layer — records, not entities — 2026-07-24

`npc/NpcRegistry` builds the town's logical population from the same parcel plan the buildings came
from, so every inhabitant lives in a house that genuinely exists. **Nothing spawns an entity.** The
spec allows 1,200-1,800 logical records but only 160 simultaneously active town-wide (48 in any
10-chunk cluster) and explicitly forbids armour stands as a mass population system - so the default
cost of a resident here is one database row and zero ticks.

Household sizes are tuned to land inside the band rather than being capped afterwards: homes 1-2,
inns 2-3, shops and workshops 1 each. Records carry stable ids derived from their parcel, a
schedule id, and a version, persisted to `npc_records` in the generation ledger via an idempotent
upsert so a restart re-registers the same roster instead of duplicating it.

**Floor-awareness is part of the identity, not inferred.** Ten floors share one dimension and one
`(chunkX, chunkZ)` column, so a controller keyed only on chunk coordinates would wake Floor 7's
residents because somebody walked through Floor 1's market. `NpcRecord` stores `globalFloor` and
`computeActive` checks the player's own floor band before considering distance.

**Budget order matters:** the local 10-chunk cap is applied before the town-wide cap, so one dense
crowd cannot swallow the entire global budget and starve the rest of the town.

**Verified** (`server/boot-test28.log`), including a deliberate worst-case stress test - 121
simulated players spread across every district at once:

```
logical records       : 1481  (spec 1200-1800) PASS
by occupation         : {ARTISAN=229, INNKEEPER=141, RESIDENT=752, SHOPKEEPER=359}
stand inside buildings: 1481/1481  PASS
persisted rows        : 1481
stress: 121 simulated players across town
active town-wide      : 160/160  PASS      <- cap binds exactly, does not exceed
worst 10-chunk cluster: 47/48    PASS
Floor-7 player wakes F1: 0       PASS (floor-aware)
armour stands in world: 0        PASS (no entity population)
living entities loaded: 0
```

The last two lines are the ones that matter for the spec's performance contract: with the entire
roster registered and a 121-player crowd simulated, the world still contains **zero** living
entities. "160/160 active" is the cap binding exactly at its limit under saturation, not an
overflow.

**Not built (not claimed):** these records have no behaviour - no pathing, no schedules actually
running, no packet-based rendering, no dialogue or trading. `computeActive` answers "who *would* be
active" but nothing consumes that answer yet. Service anchors likewise remain bound locations with
no interaction logic, and the Teleport Gate has no runtime behaviour.

Nothing past this point (vegetation and props, plaza arcade modules, rim service pockets, and the
review-render/build-report deliverables) has been attempted, claimed, or fabricated.

## 20. The map board, and a self-inflicted watchdog crash — 2026-07-25

The owner asked for "a big map everyone can see of floor 1 ... should start revealed for me". A
vanilla map is the wrong tool: it only draws terrain its holder has personally walked over, so a
fresh map of a 1,000-block town is a blank sheet. `FloorMapBoard` instead builds a physical grid of
item-framed maps whose pixels are sampled out of world blocks, so the board is complete the moment
it exists and identical for everyone who walks up to it.

**The crash.** The first version was a straightforward `build()` that walked every board pixel
calling `World#getHighestBlockYAt`. That froze the server for 60 seconds and the Paper watchdog
killed the process, disconnecting the owner mid-session. The failure was mine and it was not subtle:

```
[ChunkTaskScheduler] Chunk wait: [(-106,220) in 'aincrad:floors_01_10']
  currentGenStatus=minecraft:spawn, requestedGenStatus=minecraft:full
net.minecraft.server.level.ServerChunkCache.syncLoad(ServerChunkCache.java:119)
org.bukkit.craftbukkit.CraftWorld.getHighestBlockYAt(CraftWorld.java:907)
org.aincrad.plugin.render.FloorMapBoard.sampleColour(FloorMapBoard.java:171)
```

`getHighestBlockYAt` on a chunk that does not exist yet **synchronously generates it on the main
thread**. A 5x3 board at 6 blocks/pixel is 245,760 columns spanning 57,600 chunks, the large
majority of them ungenerated. So a command whose job was to *draw* the world was quietly trying to
*create* 57,600 chunks of it, one blocking call at a time. This is the same class of mistake as the
30-second freeze from 7,912 synchronous ledger writes recorded in §9 - main-thread work that scales
with world size - and it is worth naming as a recurring failure mode rather than a one-off.

**What replaced it.** Three rules, each of which the old code broke:

1. *Never touch an ungenerated chunk.* `World#isChunkGenerated` is consulted first and ungenerated
   area is painted as void. Drawing a map must not generate world as a side effect.
2. *Never load synchronously.* `getChunkAtAsync(x, z, false)` does the I/O off the main thread, with
   at most 64 requests in flight.
3. *Fixed work per tick.* 1,500 generated-checks, 32 load requests, and 2 tiles painted per tick,
   with progress reported to the caller. The server keeps ticking and nobody gets disconnected.

Sampling also inverted from pixel-driven to **chunk-driven**: each generated chunk is visited once
and its 256 surface columns are folded into whichever board pixels they land in. Cost now scales
with how much world actually exists rather than with board resolution, and each chunk is read once
instead of once per sample.

**Two harness bugs found while verifying this, both pre-existing.** Neither is in the plugin, but
both had been silently corrupting automation:

- Redirecting the server's stdout without draining it deadlocks the server. A `Process` object
  cannot carry live streams across a PowerShell job boundary, so the mirroring job in the original
  harness did nothing, the pipe filled after roughly 4KB, and Paper blocked on its own console
  writes and never reached "Done". Boots that looked like hangs were this.
- The StreamWriter behind a redirected stdin emits a UTF-8 BOM ahead of the first byte, and the
  Minecraft console reads it as part of the command name. Hexdumping the rejected line proved it:
  `357 273 277 a i n c r a d   m a p w a l l <--[HERE]`. **Every first command ever sent by
  `run-pregen.ps1` failed this way.** It went unnoticed only because that first command happened to
  be a status query whose output nobody checked. Writing raw bytes to `.BaseStream` does not avoid
  it and `ProcessStartInfo.StandardInputEncoding` does not exist on Windows PowerShell's .NET
  Framework, so the BOM is now absorbed by an explicit sacrificial `list` line instead of by luck.

**Scale, stated honestly.** A 5x3 board at 6 blocks/pixel covers 3,840x2,304 blocks - the town and
its road approaches, not the floor. The whole 1:1 floor needs `mapwall 16 5 5 0 0`, at which point
the entire Town of Beginnings is about 62 pixels across. That is not a rendering limitation; it is
the 1:1 scale being genuinely too large to read, and it is an independent argument for the pending
1:4 rescale, where the whole floor fits a 3x3 board at 8 blocks/pixel with the town at 31 pixels.

**Server config touched:** `paper-global.yml` `chunk-system.io-threads` was `-1` (auto), which this
machine resolved to a single I/O thread and capped bulk chunk reading at about 63 chunks/second -
the binding constraint on the board build, which is disk-bound, not CPU-bound. It is now `4`. Noted
here rather than as a YAML comment because Paper rewrites that file on every boot and discards
comments.

**Second crash, different cause.** After the sync-generation fix the command still stalled the server
for 45 seconds, on a stack that had nothing to do with generation:

```
org.bukkit.craftbukkit.CraftWorld.isChunkGenerated(CraftWorld.java:424)
net.minecraft.util.thread.BlockableEventLoop.managedBlock(BlockableEventLoop.java:192)
```

`isChunkGenerated` reads like a cheap predicate but is a blocking round-trip into the chunk system,
so the "cheap" 3,000-per-tick pruning scan was itself the stall. The fix was to stop asking the
server anything: an `.mca` region header is 1,024 four-byte entries whose sector offset is zero when
a chunk was never written, so ~33 8KB reads on an async thread answer the same question exactly.
Region-header scanning also turned out to be enormously faster end-to-end - **63 chunks/second before,
660 after** (the io-threads change below contributed) - and it gives an honest denominator for the
progress report, because the work is known up front instead of discovered as it goes.

**Verified result.** `mapwall` on the default 5x3 board at 6 blocks/pixel: 33 region files scanned,
**22,468 chunks found on disk, 14,604 read, 15 tiles painted, total 21 seconds, zero watchdog
warnings, server ticking throughout.**

**A real defect this surfaced.** 7,864 of those 22,468 chunks - about a third - exist on disk but
cannot be read with `gen=false`, because they were saved at a partial generation status when the
pregeneration run was interrupted (twice by world-lock collisions, once by the watchdog kill above).
The board draws them as void and now reports the count rather than letting them pass as unexplored
terrain. Finishing them would mean generating world from a draw command, which is exactly the bug
this section is about, so it is deliberately not done here. A fresh-world regeneration clears it.

## 21. The apron generator, and the premise that turned out to be wrong — 2026-07-25

The task was "grow natural terrain outward from the imported area's edge, ~50% bigger, then carve the
island edge on the outer boundary so villages sit inland". The geometry was built first and the world
was measured second, which was the wrong order: **the premise the task rested on does not hold.**

**What the world actually contains.** Read directly out of the region files, off any server:

```
imported build      X 3296..4895  Z -656..815   (1600 x 1472)
leftover pregen     X ~-1200..5150  Z ~-1570..1150
every column probed inside AND outside the build: 93 solid blocks, Y 227..319, flat grass on top
```

The imported build is **not an island in the void**. It sits inside a much larger slab of flat terrain
left by the interrupted pregeneration runs, uniformly 93 blocks thick from Y=227 to Y=319. So there was
nothing to "grow outward into", and the villages on the western edge are already about 4,000 blocks from
any edge. Two consequences, both of which invalidated a first implementation:

1. *An unbounded walk cannot find "the island".* Following connected land outward from the player returns
   **50,489 chunks spanning 6,944 x 7,664 blocks** - it walks straight off the build and across the
   floor. Which part of a continuous landmass is the island is a decision, not a measurement, so
   `//apron` takes the **selection**, exactly as `//island` does. The error message says why rather than
   just refusing.
2. *The operation is terraform-and-carve, not fill.* The first version masked its writes to `#air` on the
   theory that an apron only fills empty space. Against a belt that is already solid that mask would have
   made the entire command **silently do nothing** - the worst possible failure. There is no mask now;
   what protects the imported build is the plan never marking a column that holds source land.

**How the shape is decided.** Every column gets its distance to the nearest source-land column from a
two-pass chamfer transform, which also carries *which* column is nearest - so the seam height and surface
material come for free. The coastline is drawn where that distance crosses a noise-wobbled radius, which
means it is an offset of the build's real outline rather than a rectangle around it. A 4-connected
Manhattan flood was rejected for this: it would have produced a visibly diamond-cornered island.

Columns enclosed by the build are excluded by a flood fill inward from the outer boundary, so village
squares and courtyards are never filled in. Seam heights are the neighbouring **natural ground** height -
found by descending past up to 40 blocks of structure, because taking the literal highest block would
blend the new terrain up to the ridge of whatever house sits on the edge - blurred over 11x11, with the
new terrain's noise ramped in from zero over the first 20 blocks. The join is continuous by construction.

**Verified against the real world files, headless.** `ApronPlan` has no Bukkit dependency, so the whole
plan can be run and inspected without a server - which is how the two defects below were found before
the command was ever typed:

```
land box        X 3296..4895  Z -656..815  (8,913 chunks, 2,281,727 columns)
margin          172 blocks (derived)      -> island area 147.4% of current
thickness       72, tapering to under 10 at the rim
belt            1,117,456 new columns, surface Y 312..328, mean 319.6
water           Y=316, 86,662 blocks, none within 26 blocks of the edge
trees           4,691
blocks to write 96,456,904 over 4,628 chunks
chunks kept     13,540
```

Rock-depth histogram in buckets of ten, confirming the underside genuinely tapers rather than ending in
a cliff: `[7806, 38507, 54704, 54161, 53222, 52562, 326903, 529591]`.

**Two real defects the verification run caught.**

- *Water at ground level.* The commonest water surface in the imported build measures **Y=319** - the same
  as the ground - because wells, irrigated farmland and ground-level streams all vote for it. Taken
  literally, every column the noise dipped below the median became a lake, flooding most of the belt. A
  measured water level at or above the surrounding ground is now treated as a failed measurement and
  falls back to Y=316. Water blocks dropped from 733,159 to 86,662 and tree count rose from 2,511 to
  4,691 once the belt stopped being underwater.
- *Boxed-integer heap blowup.* Ground heights were being collected into a `List<Integer>` purely to take
  a median - one boxed object per column, tens of millions of them. Now a 221-bucket histogram.

**Not built, and why.** The trim half is **not implemented**. The plan reports which chunks the island
keeps, and the decision taken was to delete the rest from the region files offline rather than write a
billion air blocks. But the keep list covers only the apron's own box, and there are **104,417 chunks on
disk** - so "delete everything not kept" would remove **90,877 chunks including the entire generated Town
of Beginnings** at Z~4500, its avenue, and the Black Iron Palace. The trim therefore needs an explicit
bounded region, not a global sweep, and that boundary is the owner's call. Writing the destructive tool
before settling it would have been the `//island 50` mistake again.

Also not done: the far stale plain beyond the apron's outer box is untouched, so until a trim runs the
new coastline has 225 blocks of void beyond it and then the old flat slab resumes.
