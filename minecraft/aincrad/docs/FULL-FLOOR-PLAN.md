# Full Floor One — Scope, Cost and Staged Plan

Authorization recorded 2026-07-24 in `floor-01-layout.json:first_review_slice.full_floor_authorization`.
The owner accepted the review slice and asked for the full floor with working behaviour.

> This document exists so the size of that request is written down honestly. "Floor One fully built
> with all behaviour working" is not one job; it is roughly three, of very different sizes, and only
> the first is a generation problem.

---

## 1. Measured scale

Everything below is extrapolated from **measured** review-slice figures, not estimated from scratch.

| Quantity | Review slice (actual) | Full floor (projected) |
|---|---:|---:|
| Chunks | 7,912 | **308,028** (spec said ~306,800 — confirmed) |
| Scale factor | 1× | **38.9×** |
| Terrain edits | 4,970,281 | ~194 million |
| All-phase edits | ~11.5 million | ~202 million (realistic mix) |
| Server folder | 232 MB | **~4 GB** |
| Terrain wall-clock | ~2.5 min | **~1.6 h** at slice throughput |

Dense content (settlements, dungeons) is only ~10,800 of the 308,028 chunks; the other ~297,000 need
terrain and shell only, which is why the edit total does not scale linearly with area.

---

### 1a. Corrected cost, measured rather than estimated

The §1 table's terrain figure was too optimistic. It assumed the review slice's ~628 edits/chunk,
but the slice is flat grassland sitting almost exactly on the datapack's flat Y=319 baseline. The
real floor spans **Y=290..408**, so the baseline has to be filled or carved by up to ~89 blocks per
column. Sampling the actual terrain function over 200,000 random points inside the disc:

| Measured | Value |
|---|---:|
| Mean edits per column | 14.0 |
| Mean edits per chunk | 3,583 |
| **Full-floor total** | **~1.10 billion edits** |
| Observed throughput (3,000 real chunks) | 30.7 chunks/s |
| **Projected wall-clock** | **~2.8 hours** |

The run is **chunk-I/O bound, not edit bound** — which is why 1.1 billion edits still comes out at
under three hours. Real run evidence: 3,000 chunks, 3,413,781 edits, cursor persisted at 3,000.

**Architectural note for anyone extending this.** The flat-baseline-plus-block-edits approach works
but is not the cheapest possible: a datapack `noise_settings` with custom density functions would
generate this terrain during vanilla worldgen at essentially zero plugin cost. That was rejected
earlier for the review slice because authoring density functions blind is high-risk and unverifiable
without a boot, and it remains the right long-term optimisation if the floor ever needs regenerating
from scratch.

## 2. The three jobs, honestly separated

### Job A — Terrain and shell across the disc — *feasible now*

Whole-floor terrain is implemented (`terrain/FloorOneTerrain`) and driveable
(`generation/PregenerationDriver`). It covers grasslands, the Nepenthes forest rise, eastern lake
country with water at Y=316, the Dallhari canyon with both raised crossings, the northern mountain
belt, the Tolbana valley notch, the Labyrinth shelf, and the rim flattening.

The macro shape is a **direct port of this project's own** `scripts/render_floor01_plan.py:
terrain_height()`, chosen deliberately so the generated world matches the plan render the owner has
been reviewing rather than diverging from it.

Two engineering problems had to be solved before this was possible at all:

- **Chunk lifecycle.** Every earlier phase left chunks loaded — fine at 7,912, fatal at 308,028
  (it will not fit in RAM). The driver loads, edits, saves and unloads each chunk, so peak memory is
  flat regardless of how many remain.
- **Resumability at scale.** Progress is a single deterministic cursor in the ledger rather than
  308k rows. An interrupt at worst redoes a few chunks, and every edit is idempotent.

The Town's 7,912 committed chunks are **skipped**, not re-shaped — they were built with the older
`TerrainProfile` and re-running the new function over them would destroy finished work.

### Job B — The remaining places — *large, many sessions*

Not started. Each is comparable in effort to the Town, which took one full session on its own:

| Place | Spec requirement |
|---|---|
| Horunka | 32–46 buildings, inn, forge, item shop, pasture, cow shed, quest house |
| Medai | 24–36 buildings, guide kiosk, pier, causeway gate |
| Tolbana | 58–82 buildings, **central ravine**, suspension bridge, 4 windmills, amphitheatre, 3 inns |
| Two hamlets | 22–34 buildings each |
| First Floor Labyrinth | 300×300×100 exterior + **twenty** connected interior levels + boss arena |
| Eastern Ruins | 680×650 surface plus **three** underground sublevels to Y=288 |
| North Cave, Dallhari crossings | cave-and-ledge route, ruined aqueduct bridge |
| Hidden Dungeon | water intake, descent to Y≈264, black-stone dungeon |
| Roads | ~26,000 blocks of remaining centreline across seven routes |
| Rim / underside / shell | ribs, rings, armour skin, sealed drainage over the whole disc |
| Floor Two plate | `Y=420..479` — permitted by the mask, still unbuilt, so the sky is open |

### Job C — "All behaviour working" — *a game project, not a build task*

This is the big one, and it is the part most likely to be over-promised. The project's own
`PRISON-SERVER-MASTER-MAP.md` §8 lists these as **new systems that do not exist yet**:

- action combat: weapon families, Sword Skills with wind-up/recovery/cancel, dodge/block/parry,
  server-authoritative hit validation;
- mob and boss encounters: floor mob families, elites, the Illfang phase engine, wipe/recovery;
- party and raid layer: invitations, ready checks, rosters, reconnect grace, Last Attack attribution;
- floor content framework: per-floor definitions, Floor Mapping contribution, unlock graph;
- character and gear model: combat level, stats, proficiency, equipment upgrade paths.

Plus, closer to hand, the things this slice deliberately left as scaffolding: Teleport Gate runtime
(persistence, arrival reservation, item serialisation), service-anchor interaction, and NPC
behaviour (pathing, schedules, packet rendering, dialogue).

Realistically this is **months** of development, and much of it is gameplay design as much as code.
It cannot be honestly delivered in a session, and a version that merely *appears* to work would be
worse than nothing — the project's own rules forbid claiming behaviour that was never verified.

---

## 3. Recommended order

1. **Job A now** — run terrain to completion. Everything else stands on it, and it is the single
   change that makes the floor feel like a 10 km world instead of one town on a green plate.
2. **Floor Two's plate** — cheap, and it is what makes the sky read as Aincrad rather than open air.
3. **Tolbana, then Horunka and Medai** — reuse the existing parcel/building engine; each is a
   fraction of the Town's cost now that the engine exists.
4. **The Labyrinth** — twenty levels, well specified, mostly mechanical once the room-graph
   generator exists.
5. **Behaviour, smallest-useful-first** — Teleport Gate runtime, then service-anchor interaction,
   then NPC schedules. Combat and the boss last, because they need the most design.

---

### 1b. Observed reliability — log truncation, not a crash

Worth recording precisely, because the first reading of the evidence was wrong.

The second measured run resumed correctly from cursor 3,000, held **37–39.9 chunks/s**, and logged
normal progress up to cursor 13,000 — then the log simply stopped with no shutdown sequence. That
initially looked like a crash mid-run. It was not:

- No `OutOfMemoryError`, no `hs_err_pid` file, no `crash-reports/` entry, no watchdog message.
- **The persisted cursor read 16,136 — higher than the last logged line.** The periodic save writes
  every 2,000 chunks (…13,000, 15,000), so a value of 16,136 can only have been written by the
  driver's `stop()` path. `stop()` therefore ran and completed.

So the server shut down through its normal path; only the *log capture* was truncated, when the
wrapping shell job that owned the output redirect was reaped. A later verification boot confirmed the
world was healthy (`minHeight=-64 maxHeight=1968`), the cursor was intact at 16,136 (5.24%), and the
server stopped cleanly.

**Lesson for long unattended runs:** trust the persisted cursor over the tail of a redirected log —
the cursor is written by the code and survives the shell, whereas a redirect dies with its owner.
`Pregen Floor One.bat` mirrors output to `server/pregen-run.log` from inside the launcher for exactly
this reason.

Either way the design property holds: progress is durable state, so re-running resumes rather than
restarting. Check `aincrad pregen status`; the run is finished only when the cursor reaches 308,028.

**Measured disk rate:** 13,000 chunks produced 85.8 MB of region data (~6.8 kB/chunk), which
projects to **~2.1 GB** for the full floor — better than the 4 GB estimate in §1, because sparse
terrain chunks compress far better than the dense Town chunks that estimate was scaled from.

## 3a. How to run it

Double-click **`Pregen Floor One.bat`** at the project root and leave it. Notes that matter:

- **Safe to close at any time.** Progress is a cursor in the generation ledger; re-running resumes
  rather than restarting.
- **It refuses to start if a server is already running on the world.** Minecraft holds a session
  lock, and a second process dies with *"another process has locked a portion of the file"* — a
  mistake made twice during this session's testing, hence the guard.
- **It waits for the server to actually report ready** before issuing commands. A fixed delay is
  unsafe: boot time grows with the world (≈10 s empty, ≈36 s once chunks exist), and a command
  arriving at the exact instant the console becomes ready hits a Paper startup race where the console
  command source has no level yet and the command dies with an NPE. Observed, not theoretical.
- Progress lines print every 2,000 chunks. Check `aincrad pregen status` afterwards for the cursor.

## 4. What must not be claimed

Until each is measured, none of the following may be stated as done: full-floor generation
completion, determinism across a wipe-and-regenerate, region-file size at scale, the twenty Labyrinth
levels being connected, any combat or boss behaviour, or NPCs visibly doing anything. The build
report's §9 known-defects list is the authoritative record of what is genuinely unfinished.
