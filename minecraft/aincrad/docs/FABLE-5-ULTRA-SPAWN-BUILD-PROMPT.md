# Ready-to-paste prompt for Fable 5 Ultra Code

Copy everything below the divider into Fable 5 Ultra Code.

> **Correction, 2026-07-23:** this prompt originally stated the locked mask as 6,290 chunks
> throughout. An independent brute-force recompilation of the exact union rule below (unchanged
> radius, unchanged town_core rectangle, unchanged corridor segments) reproduced the exact stated
> chunk bounds (X `-60..67`, Z `190..314`) but a total of **7,912** chunks, not 6,290. All counts
> below have been corrected to 7,912 to match. See `docs/ARCHITECTURE-DECISION.md` §5 and
> `scripts/verify_chunk_mask.py` for the evidence. Owner-approved correction; no geometry changed.

---

You are the lead Minecraft world-generation engineer, Paper plugin engineer, technical artist,
level designer, and verification owner for a standalone Sword Art Online / Aincrad prison-MMO
server. Work directly in the local project and carry the task through implementation and
evidence-based verification. Do not stop at plans, pseudocode, prose, mockups, or an attractive
but nonfunctional shell.

## Mission

Prepare the new Paper 26.1.2 development environment safely, install the permanent custom-height
datapack before first world creation, boot-test the empty custom dimension, and then build the
best possible **Town of Beginnings spawn review slice** at true 1:1 scale.

The spawn must feel like a real city at the southern edge of Aincrad—not a Minecraft network
lobby, minigame hub, movie set, or collection of disconnected façades. It must be beautiful,
navigable, functional, deterministic, restart-safe, versioned, and ready for later Shadow
Prison systems without copying the old server blindly.

Your generated scope is only the bounded review slice described below. Do not generate the
remaining approximately 306,800 chunks of Floor One.

## Workspace and authority

New standalone project:

```text
D:\projects\Minecraft Sword Art Online Prison Server
```

Existing Shadow Prison project, read-only unless the owner separately authorizes migration:

```text
D:\projects\Minecraft game im making
```

You may create and modify files only inside the new SAO project for this task. You may inspect
the existing Shadow Prison project after reading and obeying its `AGENTS.md`, but do not change
it, start it, deploy to it, copy its world, modify its player data, or contact its live server.
Never touch `mc.shadowprison.org`.

This prompt authorizes:

- preparing the new local Paper development server;
- creating the permanent custom-height datapack;
- implementing a clean, maintainable generator/runtime plugin or generation tool;
- performing the empty-world boot test after all prerequisites pass;
- generating only the locked Town review-slice mask;
- stopping the server, validating the result, and producing review artifacts.

This prompt does **not** authorize:

- full Floor One generation;
- generating Floors Two through Ten;
- creating any other Aincrad dimension group beyond definitions/metadata needed by the permanent
  datapack;
- public deployment;
- opening firewall ports;
- publishing downloads, repositories, builds, or server addresses;
- migrating Shadow Prison players, inventories, databases, worlds, secrets, or production
  configuration;
- accepting Mojang's EULA on the owner's behalf.

If EULA acceptance is required and an accepted `eula.txt` is not already present, finish every
safe preparation step, report the exact blocker, and wait for the owner. Never bypass the
first-boot guard or fabricate readiness.

## Mandatory read order

Before changing anything:

1. Search for and read every applicable `AGENTS.md`.
2. Read the new project's `README.md`.
3. Read `RESUME-CHAT.md`.
4. Read `docs/FLOOR-01-RECONSTRUCTION-SPEC.md` completely.
5. Read `docs/floor-01-layout.json` completely and parse it as JSON.
6. Read `docs/FLOOR-01-SOURCES.md`.
7. Read `docs/PRISON-SERVER-MASTER-MAP.md`.
8. Read `server/README-FIRST-BOOT.md`.
9. Inspect `Dev Server.bat`, `scripts/start-dev-server.ps1`, and the complete current file tree.
10. Inspect Git/worktree state if available and preserve all existing owner changes.

Treat `docs/floor-01-layout.json` as the machine-readable geometry authority. Treat the world
bible as the semantic and artistic authority. If they conflict, stop before generation, explain
the exact conflict, and make the smallest reviewed documentation correction. Do not silently
choose one.

## Non-negotiable inherited server rules

Preserve these Shadow Prison principles from the beginning:

- no gambling;
- no loot-box, roulette, casino, wagering, or chance-purchase mechanics;
- no pay-to-win;
- do not expose player-facing exact reward odds;
- serialized item safety is sacred;
- every grant is atomic, idempotent, inventory-full safe, and offline safe;
- every gameplay tuning value has an in-game `/config` or equivalent permission-protected admin
  surface;
- every generated chunk, structure, parcel, light field, encounter area, and migration has a
  persistent generation/design version;
- server restart never repaints owner decoration or rebuilds a town;
- generator updates require explicit, backup-first migrations;
- never claim something was generated, booted, tested, rendered, or measured unless it actually
  was.

## Locked world architecture

These are hard requirements, not suggestions:

- Minecraft/Paper target: **Paper 26.1.2**.
- Development port: **25577**.
- Horizontal scale: **1 block = 1 canon meter**.
- Vertical scale: **1 block = 1 canon meter**.
- First Floor diameter: **10,000 blocks**.
- First Floor radius: **5,000 blocks**.
- Approximate complete Floor One size: **306,800 chunks**.
- Coordinate axes:
  - east = `+X`;
  - west = `-X`;
  - south = `+Z`;
  - north = `-Z`;
  - up = `+Y`.
- Default Floor One surface datum: **Y=320**.
- Permanent dimension range:

```json
{
  "min_y": -64,
  "height": 2032,
  "logical_height": 2032
}
```

- Inclusive build range: **Y=-64..1967**.
- First dimension group resource location: **`aincrad:floors_01_10`**.
- Ten local floors share the dimension, with a 160-block pitch:

| Local floor | Surface Y | Playable air/terrain | Upper plate or cap |
|---:|---:|---|---|
| 1 | 320 | 320..419 | 420..479 |
| 2 | 480 | 480..579 | 580..639 |
| 3 | 640 | 640..739 | 740..799 |
| 4 | 800 | 800..899 | 900..959 |
| 5 | 960 | 960..1059 | 1060..1119 |
| 6 | 1120 | 1120..1219 | 1220..1279 |
| 7 | 1280 | 1280..1379 | 1380..1439 |
| 8 | 1440 | 1440..1539 | 1540..1599 |
| 9 | 1600 | 1600..1699 | 1700..1759 |
| 10 | 1760 | 1760..1859 | 1860..1919; safety 1920..1967 |

Floor One's underbelly, hidden spaces, shell, supports, and infrastructure may use
`Y=-64..319`. Floor Two's physical plate begins at `Y=420`; its surface is `Y=480`.

The First Floor Labyrinth later rises from approximately `Y=376..476`, meeting Floor Two near
`Y=480`. Do not generate the Labyrinth during this spawn task.

Because one loaded chunk column contains all ten vertical layers, implement or scaffold a
layer-aware activation controller. A player on Floor One must not cause mobs, NPC entities,
encounters, hopper clocks, machines, or scripted systems on Floors Two through Ten to tick.
All floor-sensitive keys include global floor number and cannot rely only on X/Z.

## Exact authorized generation mask

Generate only the union below in `aincrad:floors_01_10`:

```text
town_core:
  X = -640 .. +639
  Z = 3904 .. 5039

north_spine_corridor:
  centerline = (0,4000) -> (-80,3160)
  radius = 128 horizontal blocks

northwest_corridor:
  centerline = (-380,4090) -> (-840,3520)
  radius = 128 horizontal blocks

northeast_corridor:
  centerline = (380,4090) -> (960,3380)
  radius = 128 horizontal blocks
```

All 5,680 `town_core` chunks are included. A corridor chunk is included only when the center of
that chunk is within 128 blocks of the named finite centerline segment. Deduplicate the union.
The exact result must be:

```text
7,912 chunks
chunk X bounds: -60..67
chunk Z bounds: 190..314
```

Allowed vertical ownership for this proof:

```text
Y=-64..479
```

`Y=420..479` may contain only Floor Two's reviewed underside/structural plate. Do not generate
Floor Two surface terrain at `Y=480` or anything belonging to Floors Three through Ten. Empty
sections above the generated band are acceptable and expected.

If your mask compiler does not reproduce exactly 7,912 deduplicated chunks, stop. Do not
generate approximately the right area.

## Required implementation sequence

### Stage 1 — Audit and architecture decision

Create a short decision record before implementation. Prefer a clean standalone plugin/tool
that imports selected Shadow Prison infrastructure later, rather than copying the old plugin
wholesale. Explain:

- generator approach;
- why it is compatible with Paper 26.1.2;
- how chunks are produced without synchronous live-server block pasting;
- how the permanent datapack is installed before first world creation;
- how deterministic feature ownership and protected owner edits work;
- how the 7,912-chunk sparse mask is enforced;
- how upper vertical layers remain inactive.

Use the simplest architecture that can be tested and maintained. A Paper `ChunkGenerator`,
offline Anvil generator, or staged hybrid is acceptable only if you verify it against the exact
server version. Do not guess APIs or datapack formats. Check official Paper/Minecraft sources
available to you and pin every dependency.

### Stage 2 — Paper and permanent-height datapack

Follow `server/README-FIRST-BOOT.md` literally.

- Pin the exact Paper 26.1.2 build and record its source, filename, and SHA-256.
- Verify the required Java version rather than assuming it.
- Preserve the guarded launcher.
- Configure port 25577.
- Do not enable RCON, query, Votifier, web admin, or public listeners unless explicitly needed
  for the local test.
- Create an owned datapack with a stable namespace and recorded version/hash.
- Define the permanent high dimension type and `aincrad:floors_01_10`.
- Install it before first creation of the Aincrad world.
- Confirm no accidental default-height world or previous `level.dat` exists.
- Never create `.aincrad-first-boot-ready` until every checklist item has genuinely passed.
- Never accept the EULA for the owner.

### Stage 3 — Empty boot test

Before generating one spawn block:

- boot the server locally;
- confirm Paper reports the intended build;
- confirm port 25577;
- confirm the custom dimension resource location;
- query or test minimum and maximum build heights;
- place/test temporary blocks only in a disposable verification area if required, then remove
  them safely;
- confirm clean stop and restart;
- confirm the datapack remains enabled;
- confirm no Floor One generator starts automatically;
- confirm no unrelated server process or folder was touched;
- stop the server;
- take or create a recoverable pre-generation snapshot/backup manifest.

Record the actual console evidence. Do not paste enormous logs into the report; include the
relevant lines and paths.

### Stage 4 — Deterministic generator foundation

The generator must:

- parse the existing layout JSON rather than duplicating its coordinate truth in many classes;
- use a fixed, recorded world seed;
- derive feature seeds from stable IDs;
- sort dimension, floor, phase, region, chunk, parcel, and feature IDs before work;
- produce identical output regardless of worker count;
- maintain a persistent ledger such as `generation-ledger.sqlite`;
- track at minimum:
  - world seed;
  - datapack hash;
  - generator build;
  - layout schema version;
  - floor design version;
  - phase ID;
  - feature ID;
  - feature version;
  - affected chunks;
  - state `PLANNED`, `GENERATED`, `VALIDATED`, or `COMMITTED`;
  - committed content hash;
  - owner-edit/protection status;
- resume after interruption without repainting committed chunks;
- refuse to overwrite an owner-edited/protected cell;
- provide a dry-run/mask-inspection mode;
- provide a status/report command;
- never regenerate Town geometry merely because Paper or the plugin restarted.

Recommended stable feature naming:

```text
f01/tob/plaza/...
f01/tob/palace/...
f01/tob/market/block_07/parcel_014
f01/tob/wall/tower_...
f01/tob/road/north/...
f01/shell/rib_...
f01/ceiling/light_cell_...
```

### Stage 5 — Generation phases

Run generation only against the authorized mask and in this dependency order:

1. Sparse chunk mask and ownership metadata.
2. Floor One shell, southern rim, underside, armor, supports, and sealed drainage.
3. Macro terrain and Town foundation grading.
4. Floor Two underside/plate at `Y=420..479`.
5. Roads, gutters, culverts, bridge B01, gate approaches, and sightline corridors.
6. Town wall, towers, wall walk, and three gatehouses.
7. Central Plaza, arrival point, clock tower, and Teleport Gate.
8. Black Iron Palace exterior and required interiors.
9. District parcel graph and every building shell.
10. Real interiors, stairs, roofs, chimneys, doors, windows, lighting, and furnishings.
11. Spawn services, safe-zone metadata, navigation markers, NPC/service anchors, and admin
    verification hooks.
12. Vegetation, props, controlled weathering, sound anchors, and final visual polish.
13. Determinism, navigation, lighting, collision, mask, performance, and restart tests.
14. Review maps/renders and build report.

Never flatten the entire slice. Grade only what roads, walls, plazas, and foundations need.
The city should look constructed on real terrain at Aincrad's rim.

## Spawn experience

Set the intended new-player arrival near:

```text
position: (0,323,4416)
facing: north, toward Black Iron Palace
safe collision volume: minimum 9 × 7 × 5
```

The player materializes beneath or immediately beside the Arrival Rotunda/Teleport Gate in a
brief, restrained blue-white particle effect. Do not trap the player in a forced cinematic.
Within the first five seconds, the composition must communicate:

1. this is the Town of Beginnings;
2. Black Iron Palace dominates the north;
3. the city is enormous and inhabited;
4. Aincrad's upper plate hangs overhead;
5. the main adventure exits are north;
6. the southern rim is behind the player;
7. this is a serious shared world, not a monetized lobby.

Optional first-arrival text, presented once and configurable:

```text
Floor One — Town of Beginnings
You have entered Aincrad.
Grow stronger. Protect what you carry. Clear the floors.
```

No crates, daily spin, roulette, store hologram, premium rank wall, giant floating text swarm,
forced tutorial GUI, starter-key animation, or pay-to-win advertisement may interrupt arrival.

Use environmental wayfinding:

- Black Iron Palace and the North Gate pull the eye north;
- the clock tower identifies the plaza from every district;
- market awnings pull east/southeast;
- forge smoke and inn signs pull west;
- church bell tower identifies southwest;
- the curved wall and open horizon reveal the southern rim;
- paving patterns and lamp rhythm differentiate road classes.

The spawn must remain navigable without a resource pack, minimap, scoreboard arrow, or floating
UI marker.

## Town of Beginnings geometry

Hard envelope:

```text
center: (0,322,4500)
city bounds: X=-500..500, Z=4000..5000
northern chord wall: approximately Z=4000
southern curved wall: radius approximately 500 around (0,4500)
safe zone: city polygon, walls, gatehouses, and 16-block exterior apron
```

The city is a D-shaped fortified semicircle touching Aincrad's southern rim.

### Wall and gates

- Wall base thickness: 5 blocks.
- Parapet height: 13–16 blocks above grade.
- Wall-walk clear width: 3 blocks.
- Towers every 45–65 blocks.
- Towers must have interiors, stairs/ladders, roof access where safe, and exterior rhythm.
- Pale exterior stone, darker footings, warm lamps every 12–16 blocks.
- Do not copy/paste one tower without controlled variation.

North Gate:

```text
position: (0,323,4000)
clear opening: 15 blocks
gatehouse footprint: approximately 30 × 20
route: Main North Road / Rata Plain
```

Northwest Gate:

```text
position: (-380,322,4090)
clear opening: 11 blocks
route: Horunka / Nepenthes Forest
```

Northeast Gate:

```text
position: (380,322,4090)
clear opening: 11 blocks
route: lakes / Medai
```

All gates remain normally open in this safe-zone review build. Portcullises may be visual but
must not randomly trap players.

### Central Plaza

```text
center: (0,322,4416)
paved radius: 108
arcade radius: 136
clear crowd surface: at least 140 × 140
lamp ring radius: 84
```

Requirements:

- concentric paving with subtle Aincrad/floor motifs;
- four readable ingress corridors;
- no stalls in the inner 68-block radius;
- benches and trees only in the outer ring;
- drainage that does not leak into the underside;
- spawn collision and logout/login safety;
- crowd exits without one-block chokepoints;
- visual hierarchy survives night, rain effects, and pack-disabled rendering.

Clock tower:

- base approximately 22 × 22;
- about 44 blocks high;
- readable analog faces;
- bell level below roof;
- restrained spire/crystal crown;
- visible from all primary district collectors;
- no excessive animated entities.

Teleport Gate:

- minimum clear floor 9 × 7;
- visual height about 12 blocks;
- muted but functional Floor One return-anchor presentation;
- particles/displays are bounded, pooled, and configurable;
- gate state is persistent and idempotent;
- teleport reservation and item serialization hooks are scaffolded even if no second
  destination exists yet.

### Black Iron Palace

```text
position: (0,324,4136)
podium: approximately 328 × 220
main mass: approximately 224 × 152
drum radius: approximately 84
dome apex: approximately Y=386
processional avenue: 17 blocks wide
```

This must be the largest, darkest, most commanding structure in Town. It should feel precise,
institutional, and quietly terrifying—not like a generic evil castle.

Required exterior qualities:

- perfectly legible silhouette from spawn;
- broad podium and ceremonial stairs;
- deep colonnades and layered massing;
- dark iron/blackstone/deepslate against the Town's pale stone;
- restrained copper/gold detail;
- no cheap nether-fortress look;
- no giant flat blank walls;
- believable roof drainage, buttresses, and service access.

Required interiors:

- Monument Hall;
- processional nave;
- side galleries;
- prison-processing hall prepared for later systems;
- offices/service rooms;
- locked resurrection-chamber reference;
- maintenance corridors;
- underground water-access seam;
- exterior path toward the future hidden-dungeon intake near `(144,315,4024)`.

The hidden dungeon itself may be structurally reserved/sealed, but do not create future
encounters or loot in this spawn pass.

### Districts

Central Market:

```text
X=110..410
Z=4320..4680
```

- 220–270 parcel/building target before overlap resolution;
- two market squares joined by a 9-block street;
- 80–120 permanent shopfronts;
- 40–72 temporary stall pads;
- food, basic gear, tailoring, storage, crafting, and general-goods service anchors;
- 7–11 block frontages, 10–16 block depths;
- upper apartments and 3-block-minimum service alleys;
- busy and irregular, never a perfect mall grid.

Inn and Crafting Quarter:

```text
X=-430..-110
Z=4280..4700
```

- three large inns;
- boarding houses;
- blacksmith court;
- weapon/armor service anchors;
- storage/bank anchor;
- training yard near the northern side;
- strong forge-chimney landmark;
- alleys 3–5 blocks wide with no accidental dead-end traps.

Church and Orphan Quarter:

```text
X=-380..-70
Z=4680..4910
```

- church approximately 35 × 20;
- 10-block-clear nave;
- 28-block bell tower;
- garden, dormitory/classroom wing, and safe courtyard;
- quiet lighting and planted transition from the busier plaza.

Residential Quarter:

```text
X=50..410
Z=4660..4920
```

- dense rowhouses near the plaza;
- smaller homes and pocket gardens near the rim;
- bathhouse and wells;
- all ground-floor homes traversable;
- no identical house more than four consecutive times;
- streets feel lived-in, not filled with random clutter.

North-Gate Services:

```text
X=-420..420
Z=4000..4320
```

- training, expedition preparation, guild/service counters, cart yards, guard posts, and
  northbound supplies;
- preserve the direct spawn-to-Palace-to-North-Gate visual axis;
- never place a building across the processional sightline.

### Building and interior standard

Resolve the final non-overlapping parcel ledger to **1,100–1,350 primary building shells**.

- Every public-facing door opens into a real interior.
- No painted doors, one-block fake rooms, façade-only shops, or inaccessible advertised stairs.
- All shops, inns, service counters, civic buildings, and ground-floor homes are traversable.
- Public/service buildings are fully furnished.
- At least 45% of buildings have accessible upper floors.
- At least 12% have accessible cellar or attic space.
- Standard rowhouses: 7–11 wide, 10–18 deep.
- Corner buildings: up to roughly 16 × 22.
- Ordinary ground-floor clearance: 4–5.
- Upper floors: 3–4 clear.
- Ordinary roof ridges: 10–18 blocks above street.
- Civic roofs: 20–44 blocks above street.
- Validate chimneys against every floor and roof.
- Validate doors, stairs, headroom, railings, windows, beds, and escape paths.
- Never repeat one façade/floor-plan/roof combination more than four consecutive parcels or
  eight times within one 64-block view.

Suggested material balance:

- walls: calcite, pale stone, smooth sandstone accents, and restrained plaster-like variants;
- frames: dark oak/spruce;
- footings: stone brick/andesite;
- roofs: 65–75% red/brown terracotta family, 15–25% slate/dark family, no more than 10% special
  civic/copper roofs;
- palace: polished blackstone brick, deepslate tile, iron, tinted glass, restrained metal;
- roads: stone/andesite civic paving and gravel/packed-dirt service lanes.

Use block palettes as weighted deterministic families, not uncontrolled random selections.
Palette choice must respect district, building role, adjacency, and view-level repetition.

### Streets, lighting, and accessibility

- Palace/plaza/North Gate axis: 17 blocks.
- East/west plaza avenues: 13 blocks.
- District collectors: 7–9 blocks.
- Residential lanes: 5 blocks.
- Service alleys: 3 blocks.
- Arcades: 4 clear pedestrian blocks.
- Field roads: 7–9 blocks plus shoulders.

Road grade target is no steeper than 1 rise per 4 forward blocks. Use stairs/slabs intentionally,
not noisy auto-slabbing. Add gutters, curb cuts, culverts, retaining walls, loading bays, and
bridge transitions where physically justified.

Lighting:

- grand avenues and wall: every 12–16 blocks;
- collectors: every 18–24 blocks, alternating sides;
- residential lanes: intersections plus no more than 28 blocks between anchors;
- service-alley centerline: block light 5 or greater;
- plaza: block light 8 or greater;
- lighting must be hidden within believable lanterns, sconces, windows, ceiling coffers, and
  architectural fixtures;
- no exposed carpets of invisible lights, glowstone spam, or thousands of ticking displays.

Provide step-free or slab/ramp alternatives on primary public routes wherever Minecraft geometry
allows. A player must be able to reach every required service without parkour.

## Aincrad rim, underside, and ceiling

The southern wall must feel attached to a floating world.

Rim bands:

- `r=4936..4976`: overlook/walkable band;
- `r=4976..4988`: guardrail, columns, maintenance lip;
- `r=4988..5000`: exterior shell crown and drop.

Use physical railings, buttresses, spillways, structural ribs, and deep perspective. Do not use
a visible world-border wall as the fiction.

Floor One underside baseline:

```text
underY = round(-48 + 0.0000136 × r²)
clamp to [minY+6, surfaceY-28]
```

Required underside grammar:

- local stone crust;
- deep structural fill;
- 2–4 block armor skin;
- dark ribs and concentric support logic;
- sealed water conduits;
- no exposed bedrock;
- no holes into the void;
- no water leak into lower shell space;
- collision and fall-recovery behavior prepared for the runtime plugin.

Floor Two plate:

- physical underside starts at `Y=420`;
- structure continues through `Y=479`;
- use recessed luminous coffers, large ribs, haze, and distant foundation silhouettes;
- do not make it a flat gray checkerboard;
- actual mechanical light comes from deterministic non-ticking light volumes;
- the resource pack may enhance the artificial sky, but pack-disabled navigation must remain
  safe;
- do not place Floor Two grass, roads, towns, mobs, or surface decoration.

## NPC and service preparation

Town spatial capacity is approximately 5,000 people, but do not spawn thousands of entities.

- Logical NPC target: 1,200–1,800 records.
- Peak event schedule: up to 2,400 logical records.
- Initial active budget: 160 Town-wide.
- Initial local budget: 48 within any 10-chunk cluster.
- Virtualize, packet-render, schedule, or leave inactive all other NPCs.
- Do not use armor stands as a mass population system.
- NPC/service anchors need stable IDs and versioned configuration.
- Spawn review may use restrained placeholder NPCs only if behavior is stable and measurable.
- All combat, hostile spawning, PvP, theft, fire spread, and destructive mechanics are disabled
  inside the exact Town safe polygon and gate apron.
- A cave beneath Town is not safe merely because it shares X/Z.

Required service anchors:

- starter information and route guidance;
- safe storage;
- food/general goods;
- repair/crafting;
- inn/respawn binding;
- guild/party preparation;
- Floor Mapping introduction;
- North Gate expedition board;
- item-safe lost-and-found/claim interface;
- permission-protected administrator verification/configuration interface.

Do not build player-facing gambling, odds displays, premium shortcuts, purchasable combat power,
or casino-like presentation.

## Commands and diagnostics

Provide permission-protected, documented commands or equivalent admin surfaces:

```text
/aincrad status
/aincrad verify world
/aincrad verify spawn
/aincrad verify mask
/aincrad gen plan
/aincrad gen status
/aincrad gen resume
/aincrad layer status
/aincrad layer debug
/aincrad config
```

Names may differ if the project already establishes a command convention, but the capabilities
must exist. Destructive generation/migration commands require confirmation, explicit scope, and
a preview/dry run. Read-only status and verification commands must be safe.

The configuration surface must expose relevant tunables without requiring a source-code edit,
including:

- active NPC budgets;
- particle/sound density;
- ceiling light profile;
- safe-zone behavior;
- arrival message;
- generation worker count;
- per-tick/runtime budgets if any runtime work exists;
- layer activation radius;
- diagnostic verbosity.

Immutable geometry facts such as the 7,912-chunk mask, dimension identity, and 160-block pitch
are not casual tunables.

## Visual quality bar

The build should withstand inspection from:

- ground-level survival movement;
- rooftops and wall walks;
- 16–32 chunk render distance;
- overhead map render;
- night;
- pack disabled;
- repeated routes through each district;
- the southern rim looking inward;
- the Central Plaza looking north;
- the North Gate looking south;
- the first 700–900 blocks of each outbound road.

Required review viewpoints:

1. Spawn at `(0,323,4416)` facing Black Iron Palace.
2. Plaza edge looking back toward clock tower/Teleport Gate.
3. Palace podium looking south across the plaza.
4. North Gate looking north along the Main North Road.
5. North Gate looking south toward Palace/plaza skyline.
6. Northwest Gate looking toward Horunka route.
7. Northeast Gate looking toward Medai route.
8. Church courtyard.
9. Central Market at street height.
10. Inn/crafting quarter at dusk/night.
11. Southern wall walk looking over Aincrad's rim.
12. Below-rim/underside inspection view.
13. Upward view of the Floor Two plate.
14. Overhead orthographic map.
15. District-density map showing parcel IDs and accessible interiors.

Avoid:

- huge empty plazas;
- flat terrain extending to chunk edges;
- repeated WorldEdit-looking rows;
- random block-noise weathering;
- excessive gradients made from unrelated block colors;
- inaccessible towers;
- fake windows revealing solid stone;
- doors opening into walls;
- chimneys cutting through bedrooms;
- lamps at inconsistent heights;
- roofs with no drainage;
- overgrown vegetation blocking roads;
- floating leaves;
- mobs/NPCs embedded in blocks;
- medieval façades pasted onto modern lobby logic;
- hologram clutter;
- unexplained barriers at review-mask edges.

Hide the sparse review boundary with honest temporary review treatments such as graded terrain,
construction fences, distant haze, closed maintenance gates, or explicit development barriers.
Mark those treatments as temporary versioned features. Never imply the whole Floor One exists
when it does not.

## Acceptance tests

Implement automated tests wherever practical and record manual checks where automation is not
possible.

### Datapack and boot

- Exact Paper 26.1.2 build recorded and hashed.
- Required Java version verified.
- Datapack loads before Aincrad world creation.
- Dimension is `aincrad:floors_01_10`.
- Minimum build Y is `-64`.
- Maximum inclusive build Y is `1967`.
- Port is `25577`.
- Clean stop/restart passes.
- No unrelated server/world is touched.

### Mask and generation

- Mask compiler returns exactly 7,912 chunks.
- Chunk bounds are X `-60..67`, Z `190..314`.
- Every generated chunk belongs to the union rule.
- No chunk outside the mask is generated by the spawn job.
- No owned block is written below `Y=-64` or above `Y=479`.
- No Floor Two surface content exists.
- No Floors Three through Ten content exists.
- Interrupt/resume produces matching committed hashes.
- Different worker counts produce matching output.
- Restart performs zero implicit Town repaint.
- Simulated owner edits block an incompatible migration.

### Town geometry

- Complete D-shaped city envelope exists.
- All three gates connect to their intended corridors.
- City wall has no accidental gaps.
- Wall walk is continuous except at intentionally secured sections.
- Central Plaza clear area is at least 140 × 140.
- Black Iron Palace dominates the spawn sightline.
- Final unique parcel ledger contains 1,100–1,350 primary buildings.
- Every public-facing door resolves to a valid interior.
- Required accessible-floor ratios pass.
- No stairs, doors, beds, counters, or primary corridors fail collision/headroom checks.
- No foundation floats.
- No road exceeds allowed grade without a tagged exception.
- B01 drainage bridge exists and remains navigable.

### Safety and gameplay preparation

- Town safe polygon and 16-block apron are exact and Y-aware.
- No hostile spawn or encounter trigger exists in the safe area.
- No field or resource reset volume overlaps Town art.
- Spawn/login/logout positions are collision-safe.
- Teleport failure path preserves inventory and returns safely.
- Item grant/claim scaffolding is atomic and idempotent.
- No gambling or pay-to-win feature exists.
- All tunables have an admin surface.

### Lighting and upper layers

- Plaza block light is 8 or greater.
- Service-alley centerlines are 5 or greater.
- No dark navigation holes exist on required public routes.
- Town remains readable with the resource pack disabled.
- Ceiling lighting uses no uncontrolled ticking-entity field.
- With players only on Floor One, Floors Two through Ten show zero active mob AI, NPC entity
  ticks, encounter ticks, hoppers, or scheduled machines.

### Performance and storage

- Record generation time, peak memory, final slice disk usage, and representative region-file
  sizes.
- Record active entity, block entity, display entity, and scheduled-task counts at required
  viewpoints.
- No synchronous live-server block-paste loop stalls Paper.
- Dense Town chunks remain within documented budgets.
- The server stops cleanly after verification.

## Required deliverables

Leave the project in a reproducible, stopped state with:

- pinned Paper preparation and hashes;
- permanent custom-height datapack source;
- clean plugin/generator source;
- reproducible build scripts;
- generated plugin/tool artifacts if builds succeed;
- deterministic seed and generation manifest;
- `generation-ledger.sqlite` or the selected equivalent;
- exact 7,912-chunk compiled mask artifact;
- tests and test results;
- configuration with comments;
- administrator command documentation;
- generated Town review slice only, if every prerequisite passed;
- overhead map and elevation/section render;
- required viewpoint screenshots or the closest truthful automated renders available;
- `docs/SPAWN-BUILD-REPORT.md` containing:
  - what was inspected;
  - architecture choice;
  - exact files created/changed;
  - Paper/Java/datapack versions and hashes;
  - boot evidence;
  - generation evidence;
  - validation results;
  - performance/storage measurements;
  - known defects or unverified items;
  - explicit confirmation that the full floor was not generated;
- updated `README.md` and `RESUME-CHAT.md` that state the truthful current status.

Do not include secrets, tokens, private production configuration, or massive raw logs.

## Working style

- Work autonomously when the answer is discoverable locally or from authoritative documentation.
- Ask only when a genuinely required owner decision or authority boundary blocks progress.
- Preserve unrelated work and never use destructive Git/filesystem commands casually.
- Back up before irreversible world changes.
- Keep progress updates short and evidence-based.
- If a test fails, diagnose and fix it when safe; do not redefine acceptance to make it pass.
- If a prerequisite fails, stop before world creation/generation and report the exact reason.
- Do not create the readiness marker merely to make the launcher run.
- Do not claim visual perfection without review artifacts.
- Do not call the task complete while safe in-scope verification remains.

## Definition of done

The task is complete only when:

1. Paper 26.1.2 and the permanent high-dimension datapack have passed the guarded empty boot;
2. the generator is deterministic, versioned, resumable, and protected against owner-edit
   overwrite;
3. the compiled sparse mask equals exactly 7,912 chunks;
4. only the Town review slice has been generated;
5. the Town of Beginnings is visually exceptional, fully navigable, internally functional, and
   faithful to the world bible;
6. the spawn, Palace, walls, gates, districts, real interiors, underside, and Floor Two plate
   pass their acceptance checks;
7. upper layers remain inactive;
8. review artifacts and a truthful build report exist;
9. the server is stopped;
10. the remaining approximately 300,000 Floor One chunks are still ungenerated.

The emotional target is simple: a new player should appear in the Central Plaza, look north at
Black Iron Palace, look up at the impossible underside of the next floor, turn south toward the
edge of a floating world, and immediately understand that Aincrad is magnificent, enormous,
alive—and a prison they will have to clear together.
