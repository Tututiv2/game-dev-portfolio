# Minecraft Sword Art Online Prison Server

Design workspace for combining Shadow Prison's proven progression/economy systems with an
SAO-style shared floor-clearing MMO.

## Start here

1. `RESUME-CHAT.md` — ready-to-paste context for continuing this project in a new AI chat.
2. `docs/PRISON-SERVER-MASTER-MAP.md` — everything available from Shadow Prison and the
   transfer/adaptation decisions.
3. `docs/FLOOR-01-RECONSTRUCTION-SPEC.md` — human-readable 3D build specification for the
   First Floor.
4. `docs/floor-01-layout.json` — machine-readable coordinates, polygons, routes, elevations,
   build order, and validation rules.
5. `docs/FLOOR-01-SOURCES.md` — canon/adaptation/fan/derived evidence ledger.
6. `visuals/floor-01-plan-and-elevation.png` — static plan and vertical profile.
7. `visuals/aincrad-floor-one.html` — interactive 3D/plan/elevation explorer.
8. `scripts/render_floor01_plan.py` — deterministic PNG renderer driven by the JSON manifest.
9. `Dev Server.bat` + `server/README-FIRST-BOOT.md` — guarded dev launcher and the mandatory
   custom-height first-boot checklist.

## Accuracy statement

No complete official survey of Aincrad's First Floor is known. This package is a
**canon-constrained reconstruction**: confirmed dimensions and relationships are preserved,
while missing coordinates, streets, contours, and connective content are explicitly labeled
as derived or original.

## Selected world scale

- True 1:1 horizontal and vertical scale.
- 10,000-block diameter / 5,000-block radius.
- Approximately 78.54 million surface columns and 306,800 in-circle chunks.
- Custom dimension build range `Y=-64..1967` using `min_y=-64`, `height=2032`, and
  `logical_height=2032`.
- Hybrid vertical architecture: ten Aincrad floors per custom dimension group, using surface
  datums `Y=320, 480, 640, 800, 960, 1120, 1280, 1440, 1600, 1760`.
- Ten permanent groups cover Floors 1–100: `aincrad:floors_01_10` through
  `aincrad:floors_91_100`. Every tenth boss performs the cross-dimension transition.
- Physical upper-floor plates require artificial ceiling light and layer-aware activation;
  inactive vertical floors may not tick mobs, NPCs, encounters, hoppers, or machines.

## Current state

Snapshot: 2026-07-23. Design phase is complete. **Stage 1–3 are done: the empty-boot test has
actually passed**, with real console evidence — see `docs/ARCHITECTURE-DECISION.md` for the full
trail. Stage 4 (generation ledger) and Stage 5 (the actual Town of Beginnings build) have not been
started.

- Paper `26.1.2` build 74 downloaded, SHA-256-verified, and **confirmed live** in its own console
  output (`server/boot-test2.log`, `server/boot-test3.log`).
- Java 25 (Temurin) confirmed.
- The permanent custom-height datapack (`aincrad:floors_01_10`) is authored, fixed after a real
  first-boot failure (see below), and **confirmed working**: the dimension auto-loads from the
  datapack alone with zero plugins installed. Current hash `110b069d2ea9...`.
- `server/server.properties`, `server/eula.txt` (owner-accepted) are in place.
- The guarded launcher's checklist was completed for real; a pre-boot snapshot
  (`backups/server-pre-first-boot-2026-07-23.zip`) and a post-boot-test snapshot
  (`backups/server-post-boot-test-2026-07-23.zip`) both exist.
- **First boot attempt failed for a real, useful reason:** the original `dimension_type` JSON used
  an int-provider shape and was missing a required field (`has_ender_dragon_fight`) that this
  version of Minecraft actually requires — both real facts this assistant didn't have reliably in
  its training data, only discoverable by actually booting. Fixed and reverified.
- **Second and third boot attempts passed cleanly**: Paper build, port 25577, and the
  `aincrad:floors_01_10` dimension all confirmed via real console output. The `Y=-64..1967`
  inclusive build range was rigorously tested with real `setblock` commands at the boundaries
  (not assumed) — see `docs/ARCHITECTURE-DECISION.md` §7 for the exact command/result table.
  Clean stop and restart confirmed twice.
- Mask count discrepancy (6,290 documented vs. 7,912 mechanically verified) is resolved — the
  owner chose to correct the documentation; all docs now state 7,912 as authoritative.

**Stage 4 foundation is also built and boot-tested**: `plugin/` (Gradle, Java 25 toolchain) builds
a real plugin with a chunk-mask compiler (confirms 7,912 chunks live, matching the layout JSON), a
SQLite-backed generation ledger, a layer-activation controller, and `/aincrad` admin commands.
Installed and exercised on the real server — see `docs/ARCHITECTURE-DECISION.md` §8.

**Stage 5 Phase 2 (macro terrain) is done and real**: all 7,912 mask chunks generated with the
spec-exact terrain formula (~5 million block edits), zero mask violations, verified by real
Bukkit-API height samples. See `docs/ARCHITECTURE-DECISION.md` §10.

**Roads, Town Wall + 3 gates, and the Central Plaza's paved foundation are done and real** (see
`docs/ARCHITECTURE-DECISION.md` §11): verified via real block samples on both sides of the wall
ring and at each gate opening, zero mask violations.

**The city layout has since been audited and corrected** (§12). The vertical/3D architecture passed
cleanly; the horizontal footprint had five genuine defects — an ambiguous wall shape that actually
described a circle rather than the claimed D-shape, two gates that sat on the wall under *neither*
reading, districts overflowing the wall by up to 153 blocks, a Palace leaving a 6-block forecourt
at the North Gate, and a 14-block `position`/`bounds` disagreement. All five are fixed, the wall
rebuilt through a backup-first ledger migration, and the result verified by labelled block samples.

**The Central Plaza is now graded flat (Y=322) and Black Iron Palace's exterior is built** (§13):
podium, ceremonial stair, colonnade, hall shell, drum, roof terrace, and dome to the locked Y=386
apex — 2.2M block edits, with all 12 `/aincrad verify palace` shell probes passing. Palace
interiors are deliberately left hollow for a later phase.

**The processional avenue, clock tower, and Teleport Gate are built** (§14): the Palace and plaza
now connect as a step-free 17-wide lamplit avenue, and an open "Arrival Rotunda" stands over the
gate at the spawn point, carrying a 44-block clock tower with readable analog dials and a bell.
A genuine accessibility defect in the Palace stair (2-block risers — unclimbable without jumping)
was found and migrated to walkable 1-block steps. All 29 shell/walkability probes pass.

**Black Iron Palace interiors are built** (§15): Monument Hall (open the full height into the
dome), processional nave, side galleries, prison-processing hall, offices, maintenance corridors,
and a sealed underground water seam to the hidden-dungeon intake — all reachable on foot from the
plaza, with the resurrection chamber deliberately sealed. All 47 shell/interior probes pass.

**The five districts and 1,150 buildings are built** (§16) — a real parcel graph of
street-separated city blocks, each plot with a graded pad, framed walls, a real door onto its
street, windows, climbable stairs, an upper floor, a closed pitched roof and often a cellar.
Verified across *every* building: doors 1150/1150, **doors opening into genuine interiors
1150/1150**, headroom 1150/1150, roofs closed 1150/1150, 72% with accessible upper floors (spec
≥45%), 20% with cellars (spec ≥12%).

**Safe zone and service anchors are live** (§18): the safe polygon is Y-aware (a cave beneath the
town is correctly *not* safe), a guard blocks hostile spawns/PvP/fire/explosions inside it, and all
ten required service anchors are bound to real built parcels standing in actual interiors.

**The NPC layer is in** (§19): 1,481 logical inhabitants, each living in a house that actually
exists, persisted with stable ids — and **zero entities spawned**. Activation is capped at 160
town-wide / 48 per 10-chunk cluster and is floor-aware; stress-tested with 121 simulated players,
the world still holds 0 armour stands and 0 living entities.

**The plaza arcade, the district street network, and the review artifacts are done.**
`docs/SPAWN-BUILD-REPORT.md` is the full, checkable build report — the last formally required
deliverable — and `visuals/town-overhead-map.png` / `town-elevation-map.png` are rendered from the
live world. The first render did real work: it exposed that 1,150 houses were standing on bare grass
with no paved streets at all, which no acceptance test had caught.

**Full Floor One is now authorized and terrain generation is implemented.** Run
`Pregen Floor One.bat` to generate all 308,028 chunks — it is resumable, safe to close, and skips the
already-finished Town. Measured cost: **~1.1 billion block edits, ~2.3–2.8 hours, ~4 GB**.
See `docs/FULL-FLOOR-PLAN.md` for the honest scope breakdown — "full Floor One with all behaviour"
splits into terrain (done and runnable), the remaining settlements and the Labyrinth's twenty levels
(many sessions), and the combat/boss/party systems (months, and as much design as code).

Still to come after terrain: Floor Two's structural plate, Tolbana / Horunka / Medai, the Labyrinth,
vegetation, and all gameplay behaviour.
