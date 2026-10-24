# Town of Beginnings — Spawn Build Report

Snapshot: **2026-07-24**. Server state: **stopped**. Slice: **Town of Beginnings review slice only**.

> This report is written to be checkable, not flattering. Every number in it came from a real command
> on a real server and the console line is cited. Where something is unbuilt, unverified, or was got
> wrong and fixed, it says so.

---

## 1. Headline status

| | |
|---|---|
| Paper build | `26.1.2-74-ver/26.1.2@e4e17fc`, SHA-256 `1d70b1da…` |
| Java | Temurin 25.0.3+9 LTS (verified, not assumed) |
| Dimension | `aincrad:floors_01_10`, `min_y=-64`, `height=2032` |
| Verified build range | `Y=-64..1967` inclusive (tested at the boundaries) |
| Port | 25577 |
| Authorized mask | **7,912 chunks** (corrected from a documented 6,290 — see §3) |
| Mask violations, all runs | **0** |
| Ledger committed features | **12,450** |
| Buildings | **1,150** (spec 1,100–1,350) |
| Logical NPCs | **1,481** (spec 1,200–1,800), **0 entities spawned** |
| Full Floor One generated? | **No.** ~299,000 chunks remain ungenerated, as required. |

---

## 2. What was inspected before anything changed

`AGENTS.md` (none present in this project), `README.md`, `RESUME-CHAT.md`,
`docs/FLOOR-01-RECONSTRUCTION-SPEC.md`, `docs/floor-01-layout.json`, `docs/FLOOR-01-SOURCES.md`,
`docs/PRISON-SERVER-MASTER-MAP.md`, `server/README-FIRST-BOOT.md`, `Dev Server.bat`,
`scripts/start-dev-server.ps1`, and the full file tree. The Shadow Prison project was **not**
modified, started, copied, or contacted.

Two environment facts were verified rather than assumed, and both differed from what the design docs
implied:

- PaperMC's `api.papermc.io/v2` API used by the project's own S11 citations is **dead** (HTTP 410,
  sunset 2026-07-01). The build was pinned via their current `fill.papermc.io/v3` API instead.
- `pack.mcmeta` no longer uses a single `pack_format` integer; it uses `min_format`/`max_format`.
  The data-pack format (`101`) was read out of `version.json` inside the pinned jar, not guessed.

---

## 3. Architecture choice

A **datapack defines the dimension**, and a **plugin generates content through a tick-budgeted
queue**. No custom Java `ChunkGenerator` was needed: `aincrad:floors_01_10` loads from the datapack
alone, proven by it appearing at boot with `Initialized 0 plugins`.

Reusing vanilla's `minecraft:overworld` noise was tried and reverted — it puts terrain at **Y≈63**,
not the required Y≈320, because raising a dimension's height limit does not move where vanilla's
density functions place ground. The build uses a flat solid baseline filling `Y=-64..319` plus a
plugin terrain-shaping pass implementing the spec's exact formula.

**Mask enforcement was built before real generation was switched on**, after a permission check
caught that Stage 4 only *computed* the mask and never enforced it: the dimension's spawn point is
relocated inside the mask (so the server's own spawn-area preparation cannot stray), a WorldBorder is
sized to the mask bounding box, and a `ChunkLoadEvent` detector alerts on any out-of-mask chunk.

### Corrections made to the design documents

The docs were treated as authoritative but not infallible. Five real defects were found by
mechanical checking and corrected with owner approval:

| Defect | Resolution |
|---|---|
| Mask stated as 6,290 chunks; the stated geometry produces **7,912** | Docs corrected to 7,912 (bounds matched exactly, so only the total was wrong) |
| Wall shape ambiguous — the numbers describe a **circle**, not the claimed "D-shape" | D-shape made explicit as `city_envelope`; gains 108,326 blocks² and fits every district |
| NW/NE gates lay on the wall under **neither** reading | Moved onto the north wall at `(±380,4000)` |
| Palace podium left a **6-block** forecourt at the North Gate | Depth 220→170: forecourt 31, avenue widened 62→87 |
| Palace `position` vs `bounds` disagreed by 14 blocks | Reconciled |

A sixth apparent conflict — North Gate opening 15 vs 20 — turned out **not** to be a conflict: the
documents measure *clear opening* and *structural wall gap*. Both are now recorded explicitly.

---

## 4. Boot evidence

```
[bootstrap] Loading Paper 26.1.2-74-ver/26.1.2@e4e17fc for Minecraft 26.1.2
Starting Minecraft server on *:25577
Selecting spawn point for level 'aincrad:floors_01_10'...
Done (12.679s)! For help, type "help"
```

Build-height bounds were tested with real console commands after force-loading the target chunk, not
inferred:

| Command | Result |
|---|---|
| `setblock 0 1967 0 stone` | `Changed the block` — succeeded |
| `setblock 0 1968 0 stone` | `That position is out of this world!` — rejected |
| `setblock 0 -64 0 bedrock` | `Changed the block` — succeeded |
| `setblock 0 -65 0 bedrock` | `That position is out of this world!` — rejected |

The Bukkit API independently agrees: `minHeight=-64 maxHeight=1968 (inclusive top build Y=1967)`.
Clean stop and restart confirmed repeatedly. Test blocks were removed and the force-load ticket
released.

**First boot genuinely failed**, which is worth recording: the initial `dimension_type` JSON was
rejected with a fatal registry error because `monster_spawn_light_level` no longer accepts the older
nested int-provider shape and `has_ender_dragon_fight` is now required. Both were real facts about
this Minecraft version that only booting could reveal.

---

## 5. Generation evidence

All phases ran through one block-budgeted scheduler. Per-phase console figures:

| Phase | Units | Edits |
|---|---:|---:|
| Terrain shaping (full mask) | 7,912 chunks | 4,970,281 |
| Roads (3 outbound stubs) | 307 chunks | 22,018 |
| Town wall + 3 gates (v3) | 464 chunks | ~289,000 |
| Plaza foundation (regraded flat, v2) | 168 chunks | — |
| Plaza arcade (90 modules) | 136 chunks | 155,466 |
| Black Iron Palace exterior | 264 chunks | 2,201,726 |
| Palace interiors (v2) | 161 chunks | 331,968 |
| Processional avenue | 12 chunks | — |
| Clock tower + Teleport Gate | 4 chunks | — |
| Districts — 1,150 buildings | 1,150 parcels | 2,871,950 |
| District streets | 1,749 chunks | 279,376 |

**Zero mask violations across every run.** Zero invalid block-state strings. The generation ledger
holds **12,450 committed features**, each with a stable id, version, chunk list and content hash;
restarts never repaint committed work.

Four **migrations** were run version-gated and backup-first, proving the migration machinery rather
than just shipping it: wall `v1→v2→v3`, roads `v1→v2`, plaza `v1→v2`, palace exterior `v1→v2`,
palace interior `v1→v2`.

---

## 6. Validation results

### Buildings — all 1,150, not a sample

```
real door on frontage : 1150/1150  PASS
door opens to interior: 1150/1150  PASS
centre headroom >=2   : 1150/1150  PASS
roof closed overhead  : 1150/1150  PASS
every column roofed   : 1150/1150  PASS   (full coverage, independent method)
highestBlock > walls  : 1150/1150  PASS
wall integrity        : 1150/1150  PASS
staircase climbable   : 835/835    PASS
bed on upper floor    : 835/835    PASS
accessible upper floor: 72%  (spec >=45%) PASS
cellar space          : 20%  (spec >=12%) PASS
```

### Palace shell and interiors — 47 depth probes, all pass

Hall walls solid at depth, interior hollow, drum wall solid, dome shell closed at apex/r=30/r=45,
Monument Hall open the full height into the dome, side galleries and processing hall separately
ceilinged, resurrection chamber **sealed** with no door, west offices reachable, water seam reaching
the hidden-dungeon intake at `(144,315,4024)`.

### Spawn, safe zone, services

```
arrival volume 9x7x5 clear: 315/315  PASS
plaza is safe             : PASS
inside gate apron is safe : PASS
far outside NOT safe      : PASS
cave under town NOT safe  : PASS   (Y-aware)
past floor edge NOT safe  : PASS
service anchors bound     : 10/10  PASS
anchors stand in interiors: 10/10  PASS
```

### NPC layer, stress-tested with 121 simulated players

```
logical records       : 1481  (spec 1200-1800) PASS
stand inside buildings: 1481/1481  PASS
active town-wide      : 160/160  PASS   (cap binds exactly under saturation)
worst 10-chunk cluster: 47/48    PASS
Floor-7 player wakes F1: 0       PASS (floor-aware)
armour stands in world: 0        PASS
living entities loaded: 0
```

### Three false test failures, and how each was settled

Recorded because "the test was wrong" is a claim that needs evidence:

1. **Roof check read 0/1150.** The probe sampled a fixed height that lands in the attic void beneath
   the ridge (ridges sit at `wallTop+1+rise`, rise varying 3–6). Replaced with a full-zone scan.
2. **Sky-light seal test read 0/1150.** A diagnostic sampled sky light at three buried points —
   `(0,100,4416)=15`, `(0,200,4700)=15`, and **15 inside the sealed iron chamber**. Physically
   impossible, so Paper had not repropagated skylight after bulk generation. The light test was
   discarded, not "fixed until green".
3. **A palace wall probe read AIR.** Correct: it was pointing at the grand entrance archway carved
   after the probe was written. Re-pointed off-axis, and a second probe added asserting the archway
   *is* open.

In each case only the misdirected check moved; the surrounding checks were already passing.

---

## 7. Review artifacts

- `visuals/town-overhead-map.png` — orthographic colour map, rendered **from the live world**
  (651×526 px, 336,640 in-mask samples, surface Y 314–387).
- `visuals/town-elevation-map.png` — greyscale elevation map, same source.

**The render did real work.** Its first version exposed a defect no acceptance test could: 1,150
houses standing on bare grass with **no street network at all**. The planner had always reserved
street corridors between city blocks — nothing had ever paved them. A `StreetPavingPhase` was added
(1,749 chunks, gravel service lanes threaded through andesite civic paving, lamp posts at spec
interval) and the town now reads as a city from above. This is exactly why the spec requires review
renders, and it is the single most valuable thing found in the whole build.

A second render pass also revealed magenta streaks along every lane — the renderer's
"unmapped material" colour, because gravel was missing from its palette. A renderer gap, not a world
defect, but it looked like one until checked. Palette filled in and re-rendered clean.

**One operational note:** the render blocks the main thread for roughly 100 seconds while it loads
and samples ~336k columns, long enough to trip Paper's watchdog and to swallow a queued `stop`
command. It is a read-only operation so nothing was at risk, but it should be moved off-thread or
chunk-batched before anyone runs it on a live server.

---

## 8. Performance and storage

| | |
|---|---|
| Boot time | 9.8–12.7 s |
| Largest single phase | terrain, 4.97M edits, ~2.5 min at 4,096 edits/tick |
| Server snapshot size | ~228 MB zipped (`backups/`) |
| Living entities after full build | **0** |
| Armour stands | **0** |

No synchronous block-paste loop stalls Paper: every phase drains a per-tick budget. One real
regression was found and fixed here — the first terrain run planned all 7,912 ledger rows
synchronously inside a command handler and **froze the main thread for ~30 seconds** (Paper's
watchdog logged five thread dumps). Planning is now incremental too, at 200 chunks/tick.

---

## 9. Known defects and unverified items

Honest list. None of these are claimed as done.

**Visible quality**
- **28% of the city polygon lies outside any district envelope.** Much is legitimately plaza,
  arcade, avenue and Palace, but the wall-adjacent strips (`|X|≈410..500`) and a wedge south of the
  plaza (`X≈-70..50, Z>4660`) are genuinely empty. The spec's *"Rim/wall service pockets: 80–100"*
  and *"Central Plaza and arcade: 80–100 modules"* — the arcade is built, the rim pockets are not.
- Buildings were thinned to per-district targets by an even stride, which punches gaps through
  terraces. Streets now carry the urban form, but a block-level thin would read denser.
- No vegetation pass: no courtyard trees, gardens, wells, benches, or controlled weathering.
- Houses are one open storey per level — no internal room partitions.
- No signage anywhere.

**Functional**
- **Teleport Gate has no runtime behaviour** — no persistence, reservation, item serialisation, or
  destination unlock. It is a structure only.
- **Service anchors have no interaction logic** — bound, versioned locations with no menus, storage
  or respawn binding.
- **NPC records have no behaviour** — no pathing, schedules, packet rendering, or dialogue.
  `computeActive` correctly answers "who would be active" but nothing consumes it.
- Palace interiors are architecturally complete but only lightly furnished.
- Layer activation suppresses **mob spawns** on inactive floors but not hoppers, redstone, or
  scheduled block entities. Untestable today (nothing exists on Floors 2–10) but a real gap before
  they get content.
- Floor Two's structural plate (`Y=420..479`) — permitted by the mask — was **never built**. The sky
  above the town is currently open.

**Unverified**
- No in-game screenshots from the 15 required viewpoints; only the two orthographic renders exist.
- Determinism was not tested by wiping and regenerating from scratch, nor across differing worker
  counts. The generator is written to be deterministic (sorted ids, seeded streams, separate
  decoration RNG) but that property is **argued, not measured**.
- No interrupt/resume hash-match test.
- Region-file sizes and peak memory were not profiled.

---

## 10. Explicit confirmation

**The full Floor One was not generated.** Generation touched only chunks inside the authorized
7,912-chunk mask; the mask enforcer reported **0 violations** on every run, including the 4.97M-edit
terrain pass and the 2.87M-edit district pass. Approximately **299,000 chunks** of Floor One remain
ungenerated. No Floor Two surface terrain and no Floors Three through Ten content exists. No other
Aincrad dimension group was created. The Shadow Prison project was never modified, started, or
contacted, and `mc.shadowprison.org` was never touched. The server is **stopped**.
