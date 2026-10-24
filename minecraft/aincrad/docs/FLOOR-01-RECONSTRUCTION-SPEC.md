# Aincrad Floor One — AI-Buildable World Bible and Reconstruction Specification

> Purpose: give a world-building AI enough spatial, architectural, terrain, and validation
> information to recreate a coherent First Floor map in Minecraft without pretending that
> invented coordinates are official canon.

Companion files:

- `floor-01-layout.json` — machine-readable coordinates, polygons, routes, landmarks, and rules.
- `FLOOR-01-SOURCES.md` — citations, evidence grades, conflicts, and uncertainty ledger.
- `PRISON-SERVER-MASTER-MAP.md` — systems available for the Prison × SAO game.

Status: design specification, not generated terrain.

---

## 1. Reconstruction contract

There is no known official street-by-street, contour-by-contour map of the entire First Floor.
The canon provides a strong skeleton:

- approximately circular, 10 km diameter;
- Town of Beginnings at the southern tip;
- surrounding grasslands;
- Horunka and deep forest to the northwest;
- lakes to the east/northeast;
- a central plain;
- a valley/cliff divide, caves, ruins, and mountain country farther north;
- Tolbana in a northern valley close to the Labyrinth;
- forest between Tolbana and the Labyrinth;
- the Labyrinth at the northern edge.

This specification locks that skeleton and supplies the missing geometry. Every feature carries
one of the evidence grades defined in `FLOOR-01-SOURCES.md`.

### Claim labels used in this bible

Every build instruction must retain one or more of these labels in design data and review
exports:

- **`[CANON — C1/C2]`**: a fact, measurement, relationship, or appearance established by the
  source material. `C1` is locked; `C2` may move geometrically while preserving the relationship.
- **`[ADAPTATION — A1/F1]`**: a useful licensed-game presentation or a cited synthesis. It can
  guide a build, but it is not promoted to canon.
- **`[DERIVED — D1]`**: coordinates or dimensions calculated by this reconstruction to join the
  canon anchors coherently.
- **`[ORIGINAL GAMEPLAY — O1]`**: new connective content, encounters, interiors, or systems
  created for this server.

An unlabelled coordinate is a defect. Builder exports, structure manifests, quest definitions,
and review screenshots must display the owning label or a link to the labelled manifest entry.

### Builder behavior

1. Never describe `D1` or `O1` coordinates as canon.
2. Keep all `C1` dimensions and `C2` relative relationships unless a better primary reference
   is supplied.
3. If a route must move, move its `D1` control points, not the canon landmarks it connects.
4. If gameplay scale conflicts with strict scale, record the exception in the manifest.
5. Preserve long sightlines. Aincrad must feel like a contained world, not unrelated arenas.
6. Treat every town, road, forest, and dungeon as a functioning MMO place, not a film set.
7. Generate the terrain offline or in bounded slices. The recommended map contains millions
   of columns and must never be pasted synchronously by a live Paper task.

---

## 2. Coordinate and scale law

### 2.1 Axes

Use the First Floor's geometric center as `(x=0, z=0)`.

- East = `+X`
- West = `-X`
- South = `+Z`
- North = `-Z`
- Up = `+Y`
- Default grassland walking datum = `Y=320`

All coordinates in this document are recommended **Minecraft block coordinates** unless the
unit explicitly says meters.

### 2.2 Recommended scale

Use:

- horizontal: **1 block = 1 canon meter**;
- vertical: **1 block = 1 canon meter**;
- floor radius: **5,000 blocks**;
- floor diameter: **10,000 blocks**;
- canonical represented diameter: **10 km**;
- circular surface footprint: **approximately 78.54 million block columns**;
- chunks intersecting the ideal circle: **approximately 306,800**, before border/buffer chunks.

This is true 1:1 geographic scale. It preserves the absurd size that makes Aincrad feel like a
world instead of a theme park. It also means the terrain must be created with an offline,
resumable generator and pregenerated before the public server opens.

### 2.3 Alternate scale profiles

| Profile | Floor diameter | Town of Beginnings | Tolbana | Use |
|---|---:|---:|---:|---|
| **Selected canon 1:1** | **10,000 blocks** | **~1,000 blocks** | **~200 blocks** | Full realistic MMO world |
| Operational 1:2 | 5,000 blocks | ~500 blocks | 100–132 blocks | Lower-storage fallback only |
| Compact 1:4 | 2,500 blocks | 250–360 blocks | 80-block minimum | Prototype only |
| Vertical slice | 1,200–1,600 blocks | 320 blocks | 96 blocks | Floor One gameplay proof, not geographic fidelity |

Never uniformly shrink important interiors below playable minimums. At 1:1, most spaces are
already generous; only unusually narrow canon combat rooms may receive a documented gameplay
expansion.

### 2.4 Distance expectations

At 1:1 scale:

- Town of Beginnings to Horunka: approximately 3,200–4,000 blocks along the route.
- Town to Medai: approximately 5,000–6,000 blocks along the eastern route.
- Horunka/Medai routes converge at Tolbana after crossing Dallhari.
- Tolbana to Labyrinth: approximately 1,700–2,000 blocks.
- An ordinary uninterrupted Minecraft sprint is much faster than an SAO walking pace.

Do not tune geography around vanilla sprint time alone. Use terrain, encounters, road turns,
stamina/combat pressure, and fast travel to create the intended journey length.

---

## 3. The First Floor as a 3D object

### 3.1 Top plate

The playable land is clipped by:

```text
r = sqrt(x² + z²)
playable if r <= 4976
physical shell if r <= 5000
```

The top is not a flat disc. It is a continuous terrain surface with regional height masks:

- southern grasslands: `Y 316–334`;
- central plain: `Y 318–331`;
- western forest: `Y 322–358`;
- lake country: water `Y=316`, banks `Y 314–336`;
- Dallhari divide: canyon floor `Y 294–308`, cliff shoulders `Y 336–372`;
- east ruins: `Y 332–366`;
- Tolbana valley: `Y 344–372`;
- northern mountain belt: `Y 345–408`;
- Labyrinth shelf/entrance: approximately `Y=376`.

### 3.2 Rim

The final 32-block radial band is the exposed perimeter:

- `r 4936–4976`: walkable overlook band;
- `r 4976–4988`: guardrail, columns, and maintenance lip;
- `r 4988–5000`: exterior shell crown and drop.

Use low stone railings rather than a visible force field. Players must be able to see and feel
the drop. A server-side fall boundary handles gameplay consequences below the shell.

Recommended rim grammar:

- primary rail: polished andesite or smooth stone, 1 block thick;
- cap: stone-brick slab;
- buttress every 24–40 blocks depending on terrain;
- viewing gaps at towns and deliberate scenic points;
- no dense trees within 8 blocks of the edge;
- waterfalls pass through carved spillways rather than flowing randomly down the underside.

### 3.3 Underside

Use the following baseline:

```text
underY = round(-48 + 0.0000136 * r²)
underY = clamp(underY, worldMinY + 6, surfaceY - 28)
```

This produces:

- a deep armored belly near the center;
- a thinner but still structural outer rim;
- visible upward curvature when viewed from below;
- enough volume for Black Iron's hidden dungeon and infrastructure.

Underside layers:

1. 3–7 blocks of local stone immediately under terrain.
2. Mixed deep stone and structural fill.
3. Dark outer armor skin, 2–4 blocks thick.
4. Twenty-four radial support ribs, every 15 degrees.
5. Concentric reinforcement rings at radii 1100, 2200, 3300, 4200, and 4840.
6. Iron/blackstone nodes where radial ribs cross rings.

Palette:

- deepslate bricks;
- polished blackstone bricks;
- iron blocks and iron trapdoors;
- occasional cut copper/oxidized copper;
- dark prismarine only in water-management areas;
- no exposed bedrock unless used as an unreachable safety liner.

### 3.4 Physical ceiling and the Floor Two plate

**`[CANON — C2]`** The underside of Floor Two is visible roughly 100 yards above the Town of
Beginnings. **`[DERIVED — D1]`** In the selected stack, Floor Two's structural plate begins at
`Y=420` and its walking datum is `Y=480`. Over the Town datum near `Y=322`, that puts the visible
underside about 98 blocks overhead and makes the First Floor Labyrinth top at `Y≈476` meet Floor
Two almost exactly.

The ceiling is physical, not merely a skybox:

- Floor One's ordinary playable-air band is `Y=320..419`;
- Floor Two's 60-block structural plate occupies `Y=420..479`;
- the plate underside uses broad radial ribs, recessed luminous coffers, drainage channels,
  distant city-foundation silhouettes, and haze so it does not read as a flat stone grid;
- Floor One terrain and normal structures remain at or below `Y=408`, preserving at least
  11 blocks of emergency clearance in the highest ordinary northern terrain;
- the Labyrinth alone may enter the plate, reaching approximately `Y=476`, before its stair
  emerges at Floor Two's `Y≈480` surface;
- penetrations through the plate are limited to versioned Labyrinth transitions, drainage,
  maintenance shafts, and explicitly authored secrets.

The resource pack and layer-aware client effects may make the ceiling appear luminous and
atmospheric, but collision, drainage, and the load-bearing shell remain server-authoritative.

### 3.5 High-build-height dimension profile

Use a permanent custom `dimension_type` from the world's first creation:

```json
{
  "min_y": -64,
  "height": 2032,
  "logical_height": 2032
}
```

That produces a legal inclusive build range of `Y=-64..1967`: 2,032 vertical blocks, with the
top near 1.9k as requested. If the design specifically needs a top block at `Y=2031`, use
`height=2096` and `logical_height=2096` with the same `min_y=-64`.

The height value must be a multiple of 16. This is dimension identity, not a casual Paper
configuration toggle: install the datapack before creating the world, keep it permanently, and
take a full backup before Minecraft/Paper upgrades. Do not retrofit or remove it from a populated
world.

The datapack defines the common height identity for every Aincrad group dimension. The locked
logical dimension IDs are:

```text
aincrad:floors_01_10
aincrad:floors_11_20
aincrad:floors_21_30
aincrad:floors_31_40
aincrad:floors_41_50
aincrad:floors_51_60
aincrad:floors_61_70
aincrad:floors_71_80
aincrad:floors_81_90
aincrad:floors_91_100
```

Those are logical Minecraft resource locations; deployment tooling may map them to
Paper-managed world folders. The resource locations, dimension type, namespace, and vertical
formula are permanent world identity and may not be renamed after generation.

### 3.6 Locked ten-floor vertical stack

**`[ORIGINAL GAMEPLAY — O1]`** Aincrad uses roughly ten physical floors per dimension, not one
dimension per floor and not all one hundred floors in one dimension. For a floor's one-based
local index `i` inside its ten-floor group:

```text
surfaceY(i) = 320 + 160 × (i - 1)
ceilingPlate(i) = surfaceY(i) + 100 .. surfaceY(i) + 159
```

| Local floor | Global floors in each group | Surface datum | Playable air/terrain band | Next plate / terminal cap |
|---:|---|---:|---|---|
| 1 | 1, 11, 21, …, 91 | 320 | 320..419 | 420..479 |
| 2 | 2, 12, 22, …, 92 | 480 | 480..579 | 580..639 |
| 3 | 3, 13, 23, …, 93 | 640 | 640..739 | 740..799 |
| 4 | 4, 14, 24, …, 94 | 800 | 800..899 | 900..959 |
| 5 | 5, 15, 25, …, 95 | 960 | 960..1059 | 1060..1119 |
| 6 | 6, 16, 26, …, 96 | 1120 | 1120..1219 | 1220..1279 |
| 7 | 7, 17, 27, …, 97 | 1280 | 1280..1379 | 1380..1439 |
| 8 | 8, 18, 28, …, 98 | 1440 | 1440..1539 | 1540..1599 |
| 9 | 9, 19, 29, …, 99 | 1600 | 1600..1699 | 1700..1759 |
| 10 | 10, 20, 30, …, 100 | 1760 | 1760..1859 | 1860..1919; safety/transition 1920..1967 |

The 100-block band is not a promise that every point has a 100-block clear ceiling. Terrain,
towns, caves, and towers occupy part of it. The 60-block plate belongs primarily to the floor
above: its armor, caves, foundations, utilities, and drainage must never puncture the inhabited
layer below unless the penetration is authored.

Floor One is the special base layer. `Y=-64..319` contains its deep shell, Black Iron hidden
dungeon, reservoirs, support ribs, and protected infrastructure. Floors Two through Ten receive
only their normal 60-block underplate unless a separately reviewed exception is placed in an
unused structural cell.

Boss transitions obey the stack:

- clearing Floors 1–9 opens a controlled stair/portal into the next vertical layer in the same
  dimension group;
- clearing Floor 10 performs an atomic cross-dimension transfer from
  `aincrad:floors_01_10` to the Floor 11 arrival layer in `aincrad:floors_11_20`;
- the same rule repeats after Floors 20, 30, …, 90;
- Floor 100 has no automatic next-group destination.

### 3.7 Ceiling light, weather, and inactive-layer safety

A physical upper plate blocks vanilla skylight. Each layer therefore owns a deterministic
`environment_profile`:

1. Recessed ceiling luminaires and invisible server light volumes provide actual block light.
2. A resource pack makes the coffered underside read as a distant artificial sky.
3. Layer-aware client packets may provide fog color, cloud illusion, sun position, and weather;
   these effects are cosmetic and may never be required for navigation or collision.
4. Towns, main roads, and combat arenas must remain mechanically readable with the resource
   pack disabled.
5. Light volumes are generated from stable IDs and checked for dark holes; they are not emitted
   by thousands of ticking entities.

One loaded `(chunkX, chunkZ)` column contains all ten vertical floors. Loading Floor One can
therefore load blocks and entities above it even when nobody is playing there. The server must
use a layer-aware activation controller:

- calculate a ten-bit active-layer mask per loaded chunk column from nearby players;
- instantiate mobs and NPCs only in active layers;
- serialize or freeze custom mobs before their layer loses its final player ticket;
- prohibit always-ticking vanilla farms, hopper clocks, unattended redstone, and free-running
  block entities in inactive layers;
- set dimension-wide random ticking to a controlled policy and simulate authored crop/resource
  growth only for active layer cells;
- keep encounter state, NPC schedules, and resource resets keyed by global floor number, never
  merely by chunk coordinate;
- fail performance acceptance if a player on Floor One causes mob AI, machines, or scripted
  encounters to tick on Floors Two through Ten.

---

## 4. Macro plan

### 4.1 Landmark coordinates

| ID | X | Surface Y | Z | Evidence | Role |
|---|---:|---:|---:|---|---|
| Floor center | 0 | 320 | 0 | D1 | coordinate origin |
| Town of Beginnings | 0 | 322 | 4500 | C1 + D1 | primary city/start |
| Horunka | -2780 | 330 | 1840 | C2 + D1 | western agricultural safe village |
| Rata field-boss basin | -320 | 319 | 420 | F1/O1 | central field landmark |
| Medai | 2560 | 326 | -500 | C2/O1 | eastern-route safe village |
| Dallhari west crossing | -2560 | 338 | -1580 | C2/D1 | cave/ledge progression gate |
| Eastern ruins | 2780 | 348 | -1960 | C2/D1 | eastern dungeon route |
| Tolbana | 700 | 358 | -3480 | C1/C2/D1 | northern staging town |
| Labyrinth entrance | -500 | 376 | -4550 | C1/D1 | final dungeon entrance |
| Labyrinth center | -500 | 376 | -4700 | C1/D1 | far-north anchor |

### 4.2 Readable regional sequence

From south to north, the silhouette must read:

```text
southern rim
  Town of Beginnings
  open grasslands and starter roads
  Horunka branch ← central Rata Plain → lake/Medai branch
  Nepenthes Forest ← Dallhari Divide → eastern ruins
  northern foothills and converging routes
  Tolbana Valley
  dark northern forest
  Labyrinth shelf at the far rim
northern drop
```

From the Town's north gate, the Labyrinth should be a tiny recognizable shape in clear weather.
From Rata Plain it should be obvious. From Tolbana it should dominate the skyline.

### 4.3 Progression topology

The geography supports two routes:

- **Western route:** Town → Horunka → Nepenthes Forest → North Cave → western Dallhari crossing
  → northern foothills → Tolbana.
- **Eastern route:** Town → lake country → Medai → ruins dungeon → eastern Dallhari crossing
  → northern terraces → Tolbana.

Both routes must be individually complete. Players can choose one, cross-link between them in
the central/southern zone, or revisit the other for quests and resources.

### 4.4 Exact regional build envelopes

The polygons in `floor-01-layout.json` are authoritative. The table below gives their axis-aligned
review bounds; generators must use the polygons, not fill each entire rectangle.

| Region | X review bounds | Z review bounds | Surface Y | Evidence |
|---|---:|---:|---:|---|
| Southern Grasslands | -4500..4500 | 400..5000 | 316..334 | `[CANON — C2]` + `[DERIVED — D1]` |
| Rata Plain | -2100..1800 | -700..2100 | 318..331 | `[ADAPTATION — F1]` + `[DERIVED — D1]` |
| Nepenthes Forest | -4760..-1640 | -1640..2240 | 322..358 | `[CANON — C2]` + `[DERIVED — D1]` |
| Eastern Lake Country | 1320..4720 | -1960..2520 | 312..336; water 316 | `[CANON — C2]` + `[DERIVED — D1]` |
| Dallhari Divide | -4600..4500 | -2560..-860 | floor 294..308; shoulders 336..372 | `[CANON — C2]` + `[DERIVED — D1]` |
| Eastern Ruins | 1040..4240 | -3260..-1320 | 332..366; chambers to 288 | `[CANON — C2]` + `[ORIGINAL GAMEPLAY — O1]` |
| Northern Mountains | -4520..4360 | -5000..-1700 | 345..408 | `[CANON — C2]` + `[DERIVED — D1]` |
| Tolbana Valley | -600..1860 | -4440..-2860 | 344..372 | `[CANON — C1/C2]` + `[DERIVED — D1]` |
| Northern Labyrinth Forest | -2360..1200 | -4760..-3680 | 358..391 | `[CANON — C2]` + `[DERIVED — D1]` |

Overlaps are intentional blend zones, not conflicting ownership. At an overlap, the terrain
pass blends elevation and geology, the biome pass chooses the dominant landcover by weight, and
the encounter pass uses the highest unlocked threat band. No generator may independently run
two destructive passes over the same block without recording which pass owns the final cell.

---

## 5. Terrain generation recipe

### 5.1 Base height

Start with a calm floor:

```text
base = 320
macro = simplex2D(x / 520, z / 520) * 7
micro = simplex2D(x / 68, z / 68) * 2
surface = base + macro + micro
```

Then apply region masks. Do not allow independent noise functions to make seams at polygon
edges. Blend every mask across 40–120 blocks using smoothstep.

### 5.2 Southern grasslands

Shape:

- broad sightlines;
- gentle 2–8 block rises;
- no random mountains;
- scattered trees in groups of 1–7;
- shallow drainage channels;
- fieldstone clusters;
- maintained road shoulders near town;
- wilder grass and monster cover farther north.

Density targets per 100 × 100 blocks:

- 8–20 trees near the town edge;
- 20–45 trees in transition belts;
- 4–8 rock clusters;
- 1–3 shallow ponds;
- 3–6 flower/grass patches;
- never decorate every square meter uniformly.

### 5.3 Rata Plain

Rata is the central visual breath:

- oval, open, and readable from hills around it;
- shallow basin centered near `(-320, 420)`;
- arena/field-boss bowl at `Y≈319`;
- 6–8 block earthen berm;
- scattered cattle fences and abandoned carts;
- clear route visibility toward both Dallhari crossings;
- a few large lone trees for navigation.

Keep at least 60% of the plain free from trees or structures.

### 5.4 Nepenthes Forest

The forest should become progressively dangerous:

- southern fringe: oak/birch, clear daylight, 10–14 block canopy;
- middle: dark oak/spruce pockets, root gullies, 12–18 block canopy;
- northern/western core: mist, giant carnivorous-plant clearings, deadwood, narrow trails;
- quest grove west of Horunka for the Secret Medicine route;
- no continuous leaf roof over primary roads;
- 30–60 block irregular clearings around quest encounters.

Use terrain shelves and roots to prevent players from seeing the entire forest from one point.

### 5.5 Eastern lake country

Three principal water bodies:

1. Mirror Lake around `(3320, 1080)`.
2. North Marsh around `(3680, -500)`.
3. Medai Lake around `(2360, -80)`.

Water level: `Y=316`.

Bank rules:

- ordinary bank slope no steeper than 1:3;
- occasional 3–7 block limestone shelves;
- reeds and wetland plants in water 1–2 blocks deep;
- navigable stone/wood causeways on the primary route;
- 20–40 block dry clearings for combat;
- lake depths between 6 and 20 blocks;
- no vast empty water plane.

Hydrology is derived, not canon. It exists to make the documented lake region coherent.

Exact authored water anchors:

| Feature | Center / polyline | Surface | Depth / width |
|---|---|---:|---:|
| Mirror Lake | center `(3320,1080)`, elliptical radii `620 × 420` | Y=316 | maximum 20 |
| North Marsh | center `(3680,-500)`, elliptical radii `500 × 340` | Y=316 | maximum 8 |
| Medai Lake | center `(2360,-80)`, elliptical radii `340 × 260` | Y=316 | maximum 14 |
| Eastern river | `(4040,-1440) → (3760,-720) → (3520,160) → (3040,840) → (2600,1760) → (2240,2640)` | graded toward Y=316 | width 10–32 |

The river is generated as a connected graded channel with floodplain masks, not as independent
noise cuts between control points. Every outflow terminates in a rim spillway or authored
aqueduct intake. A catch basin and sealed conduit carry water through the shell; no vanilla
water column may cascade freely through the structural underside or a lower playable layer.

### 5.6 Dallhari Divide

Dallhari is the major mid-floor progression wall.

Form:

- irregular east–west band around `Z=-1400` to `Z=-2300`;
- canyon floor mostly `Y=294–308`;
- shoulders `Y=336–372`;
- cliff heights 28–58 blocks;
- dry stream, broken road, and rockfall fields below;
- narrower in the west near the cave crossing;
- wider and more architectural in the east near the ruins/aqueduct.

Crossings:

- west: cave-and-ledge route, 7-block minimum passage;
- east: ruined causeway/aqueduct bridge, 9-block minimum deck;
- optional dangerous central descent, not required for main progression.

The divide must be visible on the map and in the world. Do not reduce it to a shallow stream.

### 5.7 Eastern ruins

Surface envelope: roughly 680 × 650 blocks centered near `(2780,-1960)`.

Composition:

- broken outer wall following an older settlement grid;
- collapsed aqueduct aligned toward the Dallhari crossing;
- central forum or temple footprint;
- three underground sublevels;
- lowest chambers around `Y=288`;
- at least two surface-to-underground entrances;
- one puzzle-gated main route;
- one shortcut that opens from the far side;
- rubble should preserve navigable silhouettes rather than become random block noise.

Architectural grammar:

- pale limestone/calcite walls;
- mossed stone-brick foundations;
- cracked pillars;
- copper/bronze fittings;
- water channels connecting toward Medai;
- taller surviving structures on the north side to frame Tolbana's distant windmills.

### 5.8 Northern mountains

Mountains frame, not bury, the route:

- foothills rise from `Y≈345`;
- ridges reach `Y=385–408`;
- Tolbana valley cuts a readable notch;
- Labyrinth shelf is carved into the far-north ridge;
- peaks should never hide the Labyrinth from the intended approach;
- slopes near main roads stay under 1:2 where possible;
- side ridges may be 1:1 cliffs with switchbacks/caves;
- snow is optional only on the highest shaded peaks; First Floor is not a snow-themed floor.

Use pine, exposed stone, high meadow, talus, and narrow streams.

---

## 6. Road and navigation grammar

### 6.1 Road classes

| Class | Width | Shoulder | Surface | Use |
|---|---:|---:|---|---|
| Grand urban avenue | 13–17 | 2–4 | patterned stone | Town of Beginnings axes |
| Primary field road | 7–9 | 2 | packed dirt + stone | town-to-town routes |
| Secondary road | 5 | 1–2 | dirt/gravel | route branches |
| Trail | 3 | 0–1 | dirt/grass | quests and exploration |
| Dungeon approach | 8–11 | 2 | worn stone | Tolbana to Labyrinth |

### 6.2 Road construction

- Avoid straight lines longer than 220 blocks outside cities.
- Primary roads may deviate 20–60 blocks around terrain rather than flattening it.
- Grade target: maximum 1 block rise per 4 forward blocks.
- Short mountain exceptions: 1:2 with stairs/slabs.
- Put milestones at approximately 250-block intervals.
- Add a visual navigation landmark every 120–220 blocks.
- Use drainage ditches in wet regions.
- Use retaining walls only where the road clearly cuts a slope.
- Town approaches widen gradually; they do not jump from a 5-block trail to a 17-block plaza.

### 6.3 Route validation

An automated validator should:

1. Sample every route at 4-block intervals.
2. Confirm surface continuity and headroom.
3. Fail if the route crosses water deeper than 1 block without a bridge/causeway tag.
4. Fail if local grade exceeds the route's class limit for more than 10 blocks.
5. Confirm all settlement gates connect to the intended route.
6. Confirm no route exits the shell.

### 6.4 Route distance, crossing, and sightline ledger

Polyline lengths are derived from the locked control points; final graded roads may be up to
five percent longer but may not become shorter by cutting through protected terrain.

| Route | Derived centerline length | Width | Required destination/gate |
|---|---:|---:|---|
| Main North Road, Town to Dallhari approach | 4,964 blocks | 9 | Town North Gate `(0,323,4000)` |
| Town to Horunka | 3,299 blocks | 7 | Northwest Gate `(-380,322,4090)` |
| Horunka–North Cave–Tolbana | 7,520 blocks | 5 | Horunka north exit; Tolbana south gate |
| Town to Medai | 5,137 blocks | 7 | Northeast Gate `(380,322,4090)` |
| Medai–Ruins–Tolbana | 4,072 blocks | 5 | Medai causeway gate; Tolbana south gate |
| Tolbana to Labyrinth | 1,626 blocks | 8 | Tolbana north gate; Labyrinth entrance |
| Lake Country ring trail | 5,914 blocks | 3 | no mandatory progression gate |

The western Town-to-Tolbana journey is approximately 10,819 centerline blocks; the eastern
journey is approximately 9,209. Fast travel shortens repeat travel only after the relevant safe
area or gate has been mapped.

Required crossings, all `[DERIVED — D1]` unless noted:

| ID | Center | Form | Clear deck/passage | Constraint |
|---|---|---|---:|---|
| B01 | `(720,321,3675)` | Southern drainage bridge | 9 × 18 | preserve Town-gate sightline |
| B02 | `(1740,318,1950)` | Mirror Lake inlet causeway | 9 × 64 | two navigable culverts |
| B03 | `(2490,318,-250)` | Medai lake causeway | 7 × 72 | fishing boats pass on east side |
| B04 | `(-2560,338,-1580)` | West cave-and-ledge crossing | 7 minimum | roof 5 minimum; no single-file choke |
| B05 | `(-480,310,-1740)` | Optional central descent | 5 minimum | dangerous shortcut, never mandatory |
| B06 | `(2740,345,-1540)` | Ruined aqueduct bridge | 9 × 112 | broken silhouette; continuous safe deck |
| B07 | `(700,360,-3480)` | Tolbana suspension bridge | 6 × 28–36 | `[ADAPTATION — A1/F1]`; defining town landmark |

Sightline tests use eye height 1.62 blocks and clear-weather render distance 32 chunks:

- from Town North Gate, the Labyrinth crown must occupy at least 2 pixels at 1080p with the
  project resource pack, or a canon-consistent crown beacon must mark it;
- Rata's boss basin must show both a Dallhari crossing cue and the Labyrinth crown;
- Dallhari's shoulders must show Tolbana's windmills in at least two authored windows;
- Tolbana north gate must frame the Labyrinth without a permanent tree or roof obstruction;
- primary road landmarks repeat every 120–220 blocks and milestones every approximately
  250 blocks, so navigation never depends on floating UI alone.

---

## 7. Town of Beginnings — complete 3D plan

### 7.1 Envelope

Center: `(0,322,4500)`.

Build a D-shaped fortified city. **The authoritative wall polygon is
`floor-01-layout.json:settlements[town_of_beginnings].city_envelope`** — read it before building
anything; the prose below is a summary of it, not an independent source:

- straight northern wall along `Z=4000`, from `X=-500` to `X=500`;
- straight side walls at `X=-500` and `X=+500`, running from `Z=4000` south to `Z=4500`;
- curved southern wall: semicircle of radius 500 centered on `(0,4500)`, from `(-500,4500)`
  through `(0,5000)` to `(500,4500)`;
- outer city bounds `X=-500..500`, `Z=4000..5000`; polygon area 893,675 blocks².
- the curved southern edge meets Aincrad's outer rim: 512 blocks of it (`X=-256..256`) lie inside
  the rim band `r=4936..5000`, with its southernmost point at exactly `r=5000`;
- safe zone includes walls, gatehouses, and a 16-block exterior approach apron **except on the
  southern arc**, where the apron would fall past the floor edge into the void — there the wall
  face itself is the safe-zone boundary.

> **Correction, 2026-07-23.** This section previously said "D-shaped semicircle" with a "northern
> chord wall." Those numbers actually describe a *full circle* (`Z=4000` is that circle's tangent
> point, not a chord), which left the northwest/northeast gates 59 blocks outside the wall and the
> `north_gate_services` district 153 blocks outside it. The D-shape above is the reading that
> satisfies the stated bounds, the "D-shaped" description, canon, and the district envelopes
> simultaneously. See `docs/ARCHITECTURE-DECISION.md` §12.

Wall:

- 5 blocks thick at base;
- 13–16 blocks to parapet;
- 2-block crenellation rhythm;
- interior wall-walk 3 blocks wide;
- round or octagonal tower every 45–65 blocks;
- gate towers 20–24 blocks high;
- pale stone exterior with darker structural footings;
- warm lamps at 12–16 block intervals.

District build envelopes and target building counts:

| District | Bounds / form | Building shells | Public/service interiors |
|---|---|---:|---:|
| Black Iron precinct | `X=-190..190`, `Z=4020..4270` | 9–14 | all |
| Central Plaza and arcade | center `(0,4416)`, radius 136 | 80–100 modules | 48–70 |
| Central Market | `X=110..410`, `Z=4320..4680` | 220–270 | 150–200 |
| Inn and crafting quarter | `X=-430..-110`, `Z=4280..4700` | 190–230 | 90–125 |
| Church/orphan quarter | `X=-380..-70`, `Z=4680..4910` | 85–110 | 30–45 |
| Residential quarter | `X=50..410`, `Z=4660..4920` | 300–360 | 30–50 public; all homes traversable |
| North-gate services | `X=-420..420`, `Z=4000..4320` outside Palace overlap | 150–180 | 70–100 |
| Rim/wall service pockets | curved residual cells | 80–100 | 20–35 |

The non-overlapping final parcel ledger must resolve to **1,100–1,350 primary building shells**.
The table contains planning ranges and overlapping district envelopes; it is not permission to
double-count a parcel.

### 7.2 Gate plan

North Gate `(0,323,4000)`:

- primary route to Rata Plain;
- 15-block **clear opening** within a 20-block structural wall gap (see
  `floor-01-layout.json:gates[].width_semantics` — the two documents were measuring different
  things, not disagreeing);
- double gatehouse;
- 30 × 20 footprint;
- portcullis visual, but safe-zone gameplay gate remains normally open.

Northwest Gate `(-380,322,4000)`:

- route to Horunka;
- 11-block clear opening within a 15-block structural wall gap;
- forest/pasture iconography;
- interior gate-yard/staging anchor at `(-380,322,4090)`.

Northeast Gate `(380,322,4000)`:

- route to lake country and Medai;
- 11-block clear opening within a 15-block structural wall gap;
- water/ruin iconography;
- interior gate-yard/staging anchor at `(380,322,4090)`.

> **Correction, 2026-07-23.** Both flanking gates were previously placed at `Z=4090`, which put
> them 90 blocks *inside* the city wall — their openings never intersected it, so the wall sealed
> shut and neither route could physically leave the city. They now sit on the northern wall at
> `Z=4000`; their former positions are retained as interior staging anchors.
>
> **Follow-up correction, same day.** An earlier version of this note claimed "the road manifest
> needed no change." That was wrong, and a verification pass caught it: with the gates moved to
> `Z=4000` but the road polylines still starting at `Z=4090`, the Horunka route crossed the wall
> line at `X=-453` and the Medai route at `X=+454` — 73 and 74 blocks away from their own gates.
> The gates would have opened onto blank ground while the roads rammed into solid wall. Both
> routes' first points were moved to `(±380, 4000)` to start at their gates; centerline lengths
> recomputed 3299→3232 and 5137→5069.

### 7.3 Central Plaza

Center `(0,322,4416)`.

- paved radius 108 blocks;
- arcade outer radius 136;
- concentric stone pattern;
- four cardinal entrance corridors;
- clock/bell tower centered over or immediately adjacent to Teleport Gate;
- minimum uninterrupted crowd surface: 140 × 140 blocks;
- no market stalls within the central 68-block radius;
- lamp ring at radius 84;
- benches and trees only in outer ring.

Clock tower:

- 22 × 22 base;
- 44 blocks high;
- analog clock faces;
- bell level below roof;
- narrow spire or glowing crystal crown;
- readable from every radial avenue.

Teleport Gate:

- at plaza center/beneath tower;
- 9 × 7 block clear floor;
- 12-block visual height;
- inactive state uses muted stone/glass;
- active state uses particles/displays, not a permanently solid colored wall.

### 7.4 Black Iron Palace

Position `(0,324,4136)`, directly north of Central Plaza.

Processional axis:

- 17-block avenue from plaza north edge;
- 9-block-wide stair/podium approach;
- no building blocks the direct view from plaza.

Podium:

- 328 × 170 blocks (`Z=4051..4221`);
- 4–7 blocks above surrounding street;
- broad front stairs;
- black/dark material contrasted against the pale city.

> **Correction, 2026-07-23.** Podium depth was 220, putting its north edge at `Z=4026` — only 26
> blocks from the North Gate, or a 6-block clear forecourt once the ~20-deep gatehouse is placed.
> That made required review viewpoint 5 ("North Gate looking south toward Palace/plaza skyline")
> impossible. Depth 170 gives a 31-block forecourt and simultaneously widens the processional
> avenue from 62 to 87 blocks. Width (328) and dome apex (`Y=386`) are unchanged, so the Palace's
> silhouette dominance is untouched.

Main mass:

- central hall 224 × 152;
- side wings or colonnades;
- drum radius approximately 84;
- dome apex approximately `Y=386`;
- largest and darkest structure in town;
- interior nave/hall must support thousands symbolically, but player navigation can use
  a 60 × 46 central chamber plus side galleries.

Required interiors:

- Monument hall;
- prison-processing hall;
- Army/home-base offices as later-era layers;
- locked resurrection chamber reference;
- service corridors;
- underground water access seam;
- hidden-dungeon portal/entrance outside the palace, not openly inside the main hall.

Palette:

- polished blackstone brick;
- deepslate tile;
- iron;
- tinted glass;
- restrained gold/copper details;
- deep red banners only as removable faction dressing.

### 7.5 Central Market

East/southeast of plaza, approximately `X=110..410`, `Z=4320..4680`.

- covered arcades;
- two market squares linked by a 9-block street;
- 80–120 permanent shopfronts;
- 40–72 temporary stall pads;
- food, general goods, starter gear, tailoring, and storage;
- upper-floor apartments;
- central-market concept should feel busy and European, not perfectly symmetrical.

Shop module:

- frontage 7–11 blocks;
- depth 10–16;
- ground floor 5-block clear height;
- upper floor 4-block clear height;
- roof ridge 10–15 blocks above street;
- service alley 3 blocks minimum.

### 7.6 Inn and crafting quarter

West of plaza, approximately `X=-430..-110`, `Z=4280..4700`.

- blacksmith court;
- armor/weapon merchants;
- three large inns;
- multiple cheap boarding houses;
- storage/bank;
- training yard near north gate;
- alleys 3–5 blocks, not labyrinthine dead ends;
- chimneys and forge ventilation must not intersect upper interiors.

### 7.7 Church/orphan quarter

Southwest, approximately `X=-380..-70`, `Z=4680..4910`.

Church:

- 35 × 20 footprint;
- 10-block nave clearance;
- 28-block bell tower;
- small walled garden;
- dormitory/classroom wing;
- courtyard large enough for child-player scenes and safe community events.

### 7.8 Residential quarter

Southeast/south, approximately `X=50..410`, `Z=4660..4920`.

- dense rowhouses near plaza;
- smaller homes near the rim;
- pocket gardens;
- bathhouse;
- neighborhood wells;
- no identical house repeated more than four times consecutively;
- roof colors remain mostly terracotta/red-brown with limited gray variation.

### 7.9 Street hierarchy

- plaza to North Gate/Black Iron axis: 17 blocks;
- east/west plaza avenues: 13 blocks;
- district collectors: 7–9 blocks;
- residential lanes: 5 blocks;
- service alleys: 3 blocks;
- arcades: 4-block clear pedestrian band.

Use curved/radial collectors around Central Plaza, then transition into irregular medieval
blocks. A perfect spoke-wheel city looks synthetic and is not required by canon.

### 7.10 Building, interior, roof, light, and population contract

**Buildings and interiors**

- Every visible public-facing door opens into a real, collision-safe interior; painted doors
  and one-block façade rooms are forbidden.
- All shops, inns, guild/service counters, quest buildings, civic buildings, and ground-floor
  homes are traversable.
- Public/service buildings are fully furnished. Residential buildings use deterministic room
  graphs with entry, circulation, sleeping, storage, light, and chimney checks.
- At least 45 percent of all buildings have accessible upper floors; at least 12 percent have
  accessible cellar or attic space; inaccessible upper rooms must not advertise usable doors.
- Standard rowhouse footprints are 7–11 blocks wide and 10–18 deep. Corner buildings may reach
  16 × 22. Civic parcels require individually authored footprints.
- Ordinary ground-floor clearance is 4–5 blocks; upper floors 3–4; roof ridges stand 10–18
  blocks above street. Civic roofs may reach 20–44 blocks above street. Black Iron Palace
  remains the only mass with an apex around `Y=386`.
- No procedural façade, floor plan, or roof combination repeats more than four consecutive
  parcels or more than eight times within one 64-block view.

**Materials**

- Primary walls: calcite, smooth sandstone accents, pale stone/andesite, white or warm plaster
  resource-pack variants.
- Structure: dark oak/spruce timber, stone-brick footings, iron ties.
- Roofs: 65–75 percent red/brown terracotta family, 15–25 percent dark slate family, no more than
  10 percent copper or special civic roof.
- Roads: andesite/stone-brick civic paving, gravel/packed-dirt service lanes, defined gutters.
- Black Iron materials never spread casually into ordinary housing; the palace must remain
  visually singular.

**Lighting**

- Grand avenues and the wall walk: lamp centers every 12–16 blocks.
- District collectors: every 18–24 blocks, alternating sides.
- Residential lanes: at every intersection and no more than 28 blocks between light anchors.
- Service alleys: doorway light or wall lantern sufficient to keep the navigable centerline at
  block light 5 or greater.
- Plaza crowd surface: block light 8 or greater at night; no exposed light-block carpets.
- Hostile-spawn validation is independent of visual lighting because the whole city is a safe
  zone.

**Population and navigation capacity**

- Built-form capacity: approximately 5,000 residents/players, `[DERIVED — D1]`; this is spatial
  capacity, not a promise that 5,000 entities will tick.
- Baseline logical NPC records: 1,200–1,800. Peak event schedule: up to 2,400 logical NPCs.
- Initial active-NPC budget: 160 town-wide and 48 within any 10-chunk local cluster, pending
  profiling. All others use schedules, packets/displays, or virtual records rather than ticking
  server entities.
- Central Plaza provides at least 140 × 140 unobstructed blocks and four exits; crowd simulation
  must sustain evacuation to a gate without a one-block choke.
- Required navigation landmarks are the clock tower, Black Iron dome, three gatehouse pairs,
  church bell tower, market awning street, forge chimney court, and southern rim overlook.

**Generation ownership**

- Town design version starts as `town_of_beginnings:v1`; no structure is unversioned.
- Every parcel owns a stable ID such as `f01/tob/market/block_07/parcel_014`.
- The authoritative generation ledger records `world_seed`, `datapack_hash`,
  `generator_build`, `layout_schema_version`, `floor_design_version`, `feature_id`,
  `feature_version`, chunk list, generation status, and committed content hash.
- A generator may write only to an ungenerated cell or to a generator-owned cell whose current
  hash matches the expected predecessor.
- Player or builder edits break generator ownership for the edited cell and enter a protected
  override mask. Restarts never repaint the Town.
- Upgrades run as explicit, backup-first migrations with a declared from-version, to-version,
  preview diff, and rollback snapshot. A plugin version change alone never authorizes a rebuild.

### 7.11 Hidden Dungeon

Water intake position: approximately `(144,315,4024)`, outside the palace precinct.

- 5-block canal;
- disguised maintenance grate;
- descending water tunnel to `Y≈264`;
- black-stone columned dungeon;
- primary depth band `Y=222..272`;
- route must remain inside the thick central underside;
- keep it sealed from launch content until its intended progression threshold.

---

## 8. Horunka Village — complete plan

Center: `(-2780,330,1840)`.

Envelope:

- irregular 220 × 250 block agricultural village;
- low stone/palisade boundary rather than monumental walls;
- primary east/southeast road enters from Town;
- northern road exits toward Nepenthes Forest and cave route.

Required composition:

- central well square, radius 12;
- one inn, 18 × 14;
- weapon shop/forge, 16 × 13;
- item shop, 12 × 10;
- quest house for Secret Medicine, 12 × 14 with visible simmering kitchen;
- pasture approximately 45 × 34;
- cow shed approximately 24 × 14;
- 18–26 homes;
- 6–10 barns/sheds;
- kitchen gardens and grain plots;
- orchard at the forest edge.

Parcel and service target:

- 32–46 primary building shells total, including 18–26 homes and 6–10 barns/sheds;
- every shop, inn, quest house, cow shed, and ground-floor home is enterable;
- 42–68 logical NPC residents, with 18–28 active at once before profiling;
- safe polygon is the irregular 124-block-radius parcel hull plus roads/yards assigned to the
  village, followed by a 16-block encounter-exclusion buffer;
- services: sleep/respawn binding, food, basic item sales, early weapon repair, safe storage,
  quest turn-in, livestock contract, and mapped-route return point; no Teleport Gate.

Height:

- village ground `Y=328–334`;
- one- and two-storey buildings;
- roof ridges `Y=340–348`;
- forge chimney up to `Y=351`.

Palette:

- oak/spruce frames;
- cobblestone field walls;
- mud/packed dirt lanes;
- dark wood or thatch-like roofs;
- warm lanterns;
- fewer white-plaster façades than Tolbana.

Quest landscape:

- Secret Medicine trail leaves west/northwest;
- first combat clearings 80–140 blocks from village;
- rare flowering Nepenthes grove at least 300 blocks away;
- village must remain visible from pasture but disappear within the deep forest.

---

## 9. Medai Village — constrained original plan

Canon confirms the name and route relevance but does not provide a meaningful visual plan.
Everything below is `O1` except its existence and eastern-route function.

Center: `(2560,326,-500)`.

Concept: a lake-edge guide and provision village built where old roads meet a causeway.

Envelope:

- irregular 190 × 220 blocks;
- low wall on the ruins-facing north side;
- open fishing edge to the southwest/east lake;
- raised causeway gate.

Required composition:

- Argo guide-distribution kiosk or quest-board equivalent;
- item shop;
- one inn;
- fisher pier;
- cart yard;
- ruined-stone fragments reused in newer buildings;
- 14–22 houses;
- small shrine or map archive;
- bridge/causeway toward the eastern ruins.

Parcel and service target:

- 24–36 primary buildings, including 14–22 homes;
- every public building and ground-floor home is enterable;
- 34–56 logical NPC residents, with 16–24 active at once before profiling;
- safe polygon is the authored 108-block-radius lakeside hull, clipped at the waterline and
  expanded by a 16-block encounter-exclusion buffer on land;
- services: inn, food, item shop, safe storage, fishing/crafting contracts, guide/map archive,
  ruins expedition board, and mapped-route return point; no Teleport Gate.

Visual identity:

- pale reused stone bases;
- blue-gray roofs;
- timber upper floors;
- reeds, willow, and water wheels;
- several carved route stones pointing to Tolbana and Town.

Medai should feel like the last comfortable stop before the eastern ruins, but it must remain
smaller and less important than Horunka or Tolbana.

---

## 10. Tolbana — complete 3D plan

Center: `(700,358,-3480)`.

Canon strict diameter at 1:1 scale is approximately 200 blocks. Use a modest 220-block gameplay
envelope so the town can contain its required ravine, bridge, theater, inns, and windmills
without warping its canon scale.

### 10.1 Terrain and wall

- oval/irregular walled footprint, approximately 220 × 200 blocks;
- valley ground `Y=352–366`;
- northern and southern cliffs split the town around a central ravine;
- white stone wall 4 blocks thick, 10–12 blocks high;
- main south gate from the converging routes;
- north gate toward the Labyrinth;
- smaller farm/service gate.

### 10.2 Central ravine

- runs approximately east–west through the center;
- 16–24 blocks wide;
- 12–22 blocks deep;
- stream or dry watercourse at bottom;
- suspension bridge aligned north–south;
- bridge deck 6 blocks wide and 28–36 blocks long;
- secondary stone footbridge near market if space allows.

The ravine is a defining silhouette. Do not flatten it to make building easier.

### 10.3 Fountain Square

- south or southwest side of the ravine;
- approximately 28 × 34 blocks;
- central fountain radius 5–7;
- grass patches and benches;
- enough clear area for the boss strategy meeting;
- adjacent inn/restaurant façades;
- direct signposted route to amphitheater.

### 10.4 Amphitheater

- northwestern or north-central terrace;
- outer diameter 32–38 blocks;
- 4–6 stepped seating rings;
- arena/stage diameter 14–18;
- ruined/classical appearance;
- open sky;
- at least two entrances so crowds do not funnel through one door.

### 10.5 Windmills

Four required windmills:

- northwest ridge;
- northeast ridge;
- southwest farm edge;
- southeast farm edge.

Each:

- white cylindrical or tapered tower;
- base diameter 9–11;
- body 18–24 blocks tall;
- cap/roof;
- blade diameter 15–21;
- orient blades differently for a natural skyline;
- use display entities/resource-pack models if moving blades are desired.

### 10.6 Inns and businesses

Three inns are canon-required:

1. Fountain Inn — busiest, beside the square.
2. Bridge Inn — overlooks ravine.
3. Northgate Inn — serves Labyrinth parties.

Add:

- 4–7 bars/restaurants;
- blacksmith;
- item shop;
- repair/storage counter;
- bakery;
- 25–40 NPC homes;
- terraced gardens and small fields.

Parcel and service target:

- 58–82 primary building shells, including the required three inns, four windmills, 4–7
  bars/restaurants, civic structures, shops, and 25–40 homes;
- every business, inn, civic building, and ground-floor home is enterable;
- 110–170 logical NPC residents and expedition staff, with 36–56 active at once before
  profiling;
- safe polygon follows the 220 × 200 wall exterior, both bridge heads, and gatehouses, plus a
  16-block exterior encounter-exclusion apron;
- services: three spawn-binding inns, food, advanced repair, item shop, safe storage, party
  recruitment, raid supply contracts, Floor Mapping office, boss-strategy amphitheater, and
  Labyrinth expedition board; no independent Teleport Gate.

### 10.7 Tolbana-to-Labyrinth departure

The north gate should frame the Labyrinth tower.

- gate road width 8 blocks;
- first 120 blocks cross open terraces;
- route then enters northern forest;
- final approach rises to `Y≈376`;
- no blind 90-degree turn immediately outside town;
- warning boards, party staging space, and supply carts near the gate.

---

## 11. Unidentified safe hamlets

The anime minimap reportedly shows more settlement dots than the four best-known names.
This spec reserves two replaceable safe hamlets:

- Western Safe Hamlet: `(-4100,344,-2640)`;
- Eastern Safe Hamlet: `(3920,336,-2840)`.

Rules:

- 22–34 buildings each, all with traversable ground floors;
- 28–50 logical NPC records and an initial active budget of 12–20;
- one fully furnished inn/rest point;
- one route-specific service: mountain rescue/supply in the west, ruin salvage/cart repair in
  the east;
- safe polygon follows the stated radius plus a 16-block exterior encounter-exclusion apron;
- no public-facing fake doors;
- no teleport gate;
- use neutral internal IDs;
- do not invent public canon names in lore;
- if a future primary source identifies a settlement, replace the O1 label and revise the
  structure rather than adding a duplicate town.

---

## 12. First Floor Labyrinth — exterior

### 12.1 Canon envelope

- far northern edge;
- approximately 300 m wide;
- approximately 100 m tall;
- twenty levels;
- Kobold/Ruin architectural association;
- boss room on the top/final level;
- transition stair to Floor Two beyond the boss.

At true 1:1 horizontal and vertical scale:

- external footprint: 300 × 300 blocks;
- external height: 100 blocks;
- center: `(-500,376,-4700)`;
- entrance center: `(-500,376,-4550)`;
- top approximately `Y=476`.

### 12.2 Exterior massing

The tower is not a skinny keep. It is a 300-block-wide dungeon mass integrated into the north
rim:

- central rectangular/cylindrical fortress;
- stepped lower plinth carved into mountain shelf;
- buttresses every 36–48 blocks;
- upper crown narrowing around the boss level;
- sealed continuation into the implied underside of Floor Two;
- visible entrance portal 30–38 blocks wide, 18–24 blocks high;
- broken statues and Kobold reliefs;
- approach court approximately 180 × 130 blocks.

Palette:

- stone bricks;
- deepslate brick lower courses;
- tuff;
- cracked/mossy variants;
- dark oak/iron gate;
- muted bronze;
- restrained red accents for boss architecture.

### 12.3 Skyline law

Visibility targets:

- Town north gate in clear weather: tiny silhouette or beacon-like crown.
- Rata Plain: clearly recognizable tower.
- Dallhari ridge: dominant northern anchor.
- Tolbana north gate: tower occupies a major vertical field of view.
- final forest: periodic disappearance/reappearance for tension.

Run ray samples from those points. Remove peaks or trees that permanently block the intended
views.

---

## 13. Labyrinth interior — twenty-level generator contract

**`[CANON — C1]`** The exterior is approximately 100 meters tall and has twenty levels.
**`[DERIVED — D1]`** At true vertical scale, the interior is physically co-located inside the
300 × 300 × 100 exterior in `aincrad:floors_01_10`. It is not a hidden extra dimension.

The compact vertical pitch is deliberate. Floors 1–16 use three-block-clear dungeon spaces;
Floors 17–19 gain four-block-clear ceremonial spaces; Floor 20 consumes the remaining crown for
the boss chamber. Large-feeling rooms come from horizontal breadth, multi-level cutouts, long
axes, lighting, and selective voids rather than violating the 100-meter exterior.

### 13.1 Interior world

- physical center `(-500,-4700)`;
- exterior base/entrance deck `Y=376`;
- Levels 1–16 deck Ys: `376, 380, 384, …, 436`;
- Levels 17–19 deck Ys: `441, 446, 451`;
- Level 20 boss deck Y: `456`;
- boss clear volume: `Y=457..474`; crown/roof: `Y=475..476`;
- 20 physical floors contained inside the exterior;
- typical navigable envelope 220 × 220 within the 300 × 300 outer mass;
- outer wall/buttress depth 18–32 blocks;
- central or offset vertical voids may join adjacent levels but must have guarded falls;
- no portal discontinuity between the exterior entrance, twenty levels, boss room, and exit
  stair.

### 13.2 Level families

| Levels | Theme | Navigation |
|---|---|---|
| 1–4 | Ruined barracks | wide halls, tutorial loops, obvious stairs |
| 5–8 | Storehouses and armories | side rooms, keys, first locked shortcuts |
| 9–12 | Collapsed sanctum | vertical breaches, bridge rooms, ambush pockets |
| 13–16 | Kobold warrens | tighter loops, patrol routes, false branches |
| 17–19 | Royal approach | long axes, elite guard rooms, raid preparation |
| 20 | Boss level | antechamber, boss room, exit stair |

Resolved twenty-level spine, all `[DERIVED — D1]` / `[ORIGINAL GAMEPLAY — O1]` except the
twenty-level count and boss association:

| Level | Deck Y | Mandatory identity and landmark |
|---:|---:|---|
| 1 | 376 | Gate Hall; entrance checkpoint and first two-loop barracks graph |
| 2 | 380 | Muster Rooms; patrol tutorial and visible locked shortcut |
| 3 | 384 | Training Gallery; first mapping alcove |
| 4 | 388 | Broken Command Post; shortcut back to Level 1 opens from far side |
| 5 | 392 | Provision Stores; crate maze and key pedestal |
| 6 | 396 | Armory Ring; mapping alcove and repair-material salvage |
| 7 | 400 | Sealed Vaults; paired-key route with non-mandatory treasure dead ends |
| 8 | 404 | Quartermaster Hall; elite encounter and stair-control lever |
| 9 | 408 | Collapsed Chapel; first double-height breach and mapping alcove |
| 10 | 412 | Bridge Hall; guarded void crossings with two recoverable fall routes |
| 11 | 416 | Flooded Breach; drainage puzzle that never leaks into the world shell |
| 12 | 420 | Lower Sanctum; mapping alcove and persistent shortcut lift |
| 13 | 424 | Outer Warrens; three patrol loops and false branch teaching |
| 14 | 428 | Kennel Galleries; sound-telegraphed ambush cells |
| 15 | 432 | Burrow Crossroads; mapping alcove and multi-party route split |
| 16 | 436 | Captain's Camp; elite key bearer and final low-ceiling shortcut |
| 17 | 441 | Hall of Standards; four-block-clear ceremonial axis |
| 18 | 446 | Royal Guard Circuit; mapping alcove, raid regroup room, two elite loops |
| 19 | 451 | Crown Approach; boss antechamber access, final map terminal, sealed retreat doors |
| 20 | 456 | Boss deck; Illfang arena, rear dais, Floor Two stair |

### 13.3 Per-level graph

Every non-boss level:

- 1 entry stair room;
- 1 exit stair room separated by at least 55 blocks straight-line;
- 10–18 rooms;
- 2–4 loops;
- 1–3 dead-end reward rooms;
- 1 elite or hazard room;
- 1 safe mapping alcove every 2–3 levels, not a full town;
- 2–5 destructible/shortcut seams;
- no single mandatory corridor longer than 60 blocks without a chamber.

Room sizes:

- small: 9 × 9 to 13 × 15;
- medium: 17 × 17 to 25 × 31;
- large: 30 × 30 to 45 × 55;
- corridor: 5–9 wide; 3 clear blocks on Levels 1–16 and 4 clear blocks on Levels 17–19;
- double-height rooms may consume the level above only when the graph reroutes that level around
  the void and still passes independent connectivity tests.

### 13.4 Determinism

Each level uses:

```text
seed = hash(worldSeed, floorNumber=1, labyrinthLevel, layoutVersion)
```

Generate the abstract room graph first, validate connectivity, then place geometry. Save the
resolved graph so updates never silently rearrange an active dungeon.

### 13.5 Mapping gameplay

- map cells reveal in 8 × 8 or 16 × 16 block chunks;
- scouting progress persists per player and contributes to public Floor Mapping;
- boss door remains sealed until required scouting/quest conditions;
- shortcuts persist once opened;
- respawns must not reset permanent map geometry;
- monsters and loot reset independently from structure.

---

## 14. Boss floor and room

### 14.1 Antechamber

- party staging room 45 × 35;
- repair/supply checkpoint outside boss lock;
- raid briefing wall;
- clear line to boss door;
- two escape corridors back into Level 19;
- no shop inside the actual boss arena.

### 14.2 Boss door

- approximately 15 blocks wide;
- 20 blocks high;
- double-leaf stone/metal;
- Kobold relief;
- opening animation uses block displays or staged segments;
- do not move thousands of blocks per frame.

### 14.3 Boss room

Canon descriptions suggest a long narrow room roughly 20 m wide and 100 m deep. Even at strict
1:1, a 20-block raid width is too cramped for Minecraft combat and spectators. Use:

- gameplay width: 44 blocks;
- depth: 110 blocks;
- clear height: 18 blocks;
- entrance at south;
- boss spawn 72 blocks north of entrance;
- rear dais/exit stair at north end;
- side aisles/pillars that do not create unavoidable camera traps;
- arena floor mostly flat, with 1–2 block dais changes;
- retreat path remains physically readable unless game design deliberately changes it.

Capacity:

- designed around up to 48 canon raid members conceptually;
- Minecraft combat may cap lower;
- allocate approximately 8–12 square blocks of unobstructed movement space per active player
  after pillars/hazards.

### 14.4 Transition to Floor Two

- rear door opens after boss defeat;
- stair rises through the remaining plate seam from the boss crown at `Y≈476`;
- one-way first-clear ceremony moves the raid into Floor Two near its `Y≈480` arrival surface
  within the same `aincrad:floors_01_10` dimension;
- afterward, Teleport Gate activation provides ordinary fast travel;
- do not require players to repeat all twenty Labyrinth levels for routine travel.

---

## 15. Architecture kit

### 15.1 Town of Beginnings

Style:

- broad European medieval-fantasy metropolis;
- pale stone/plaster;
- red, brown, and gray roofs;
- arcades, towers, markets, street lamps;
- Black Iron Palace as deliberate dark contrast.

House modules:

| Module | Footprint | Floors | Ridge height | Use |
|---|---:|---:|---:|---|
| Narrow rowhouse | 7 × 11 | 2–3 | 13–17 | market/residential |
| Merchant house | 11 × 15 | 2–3 | 15–19 | major streets |
| Inn | 18 × 24 | 3 | 19–23 | plazas/gates |
| Workshop | 13 × 17 | 2 | 13–16 | crafting quarter |
| Civic hall | 24 × 36 | 2 | 18–24 | district anchor |

### 15.2 Horunka

- timber frame;
- fieldstone;
- thatch/dark wood roofs;
- barns and fenced plots;
- irregular village lanes;
- low warm light.

### 15.3 Medai

- reused ruin stone;
- blue-gray slate roofs;
- timber galleries;
- piers and causeways;
- route/map motifs.

### 15.4 Tolbana

- white plaster/stone;
- red-brown roofs;
- windmill silhouette;
- cliff terraces;
- bright fountains;
- more open green space than Town of Beginnings.

### 15.5 Universal construction rules

- ground every building into terrain with foundation courses;
- avoid copy-paste façades longer than four buildings;
- place doors where roads actually reach them;
- roofs require drainage and believable structural support;
- every major shop needs a functional interior or a deliberate locked façade class;
- NPC housing may use shell interiors, but player-facing buildings cannot be empty boxes;
- service alleys and back doors exist in large towns;
- lamps must be spaced by function, not uniform global grids.

---

## 16. Vegetation and resource distribution

### 16.1 Regional vegetation

| Region | Dominant | Secondary | Avoid |
|---|---|---|---|
| Southern grasslands | meadow, oak | birch, hedges | dense spruce |
| Rata Plain | grass, flowers | lone oak | continuous canopy |
| Nepenthes Forest | dark oak, spruce | giant plants, roots | bright uniform orchard |
| Lake country | willow-like trees, reeds | birch, wet grass | dry conifer forest |
| Dallhari | scrub, grass ledges | deadwood | lush flat lawn |
| East ruins | moss, vines | dry shrubs | dense random trees over ruins |
| North mountains | pine, rock | high meadow | tropical plants |
| Tolbana Valley | grass, farm crops | orchard, windbreak trees | heavy forest inside walls |
| Labyrinth forest | pine/dark oak | fog clearings | open starter meadow |

### 16.2 Prison-server mine integration

Do not paste A–Z square mines into the landscape unchanged.

Convert them into resource sites:

- southern quarry pits;
- forest root caverns;
- lake clay/ore beds;
- ruins excavation;
- mountain mine adits;
- Labyrinth salvage zones.

Each resource site still uses the prison engine underneath:

- configured bounds;
- material palette;
- reset budget;
- progression access;
- per-block hooks;
- pity/rare finds;
- quests;
- public Floor Mapping contribution.

World-facing shapes may be irregular, but keep an internal safe reset mask. The resetter must
touch only tagged resource volumes, never surrounding art.

### 16.3 Regional gameplay, mobs, quests, caves, and resources

The following is **`[ORIGINAL GAMEPLAY — O1]`** unless a row explicitly names a canon/adaptation
anchor. Threat bands `T0..T8` are relative encounter bands, not player levels. Exact item odds
are intentionally absent from player-facing design; reward tuning stays configurable through
the server's admin configuration surface.

| Region / encounter envelope | Threat | Encounter families | Quest and resource purpose |
|---|---:|---|---|
| Town exterior apron, `Z=3650..4000` and within 700 blocks of center road | T0–T1 | training boars, wasps, tutorial patrols | combat tutorial, road repair, herbs, surface stone, starter hides |
| Southern Grasslands outside apron | T1–T2 | boars, wolves, field insects, caravan ambushes | mapping beacons, escort/rescue, quarry permits, fiber/herb gathering |
| Horunka pasture/forest fringe | T1–T3 | wolves, plant sprouts, livestock threats | village contracts, cooking, timber, leather, early iron |
| Nepenthes Forest | T2–T4 | Nepenthes family `[CANON/ADAPTATION — C2/A1]`, wolves, poison clearings | Secret Medicine chain, rare flower objective, resin, medicinal herbs, root-cavern ore |
| Rata Plain | T2–T4 | mounted/charging field beasts, wasp swarms, named Rata field encounter `[ADAPTATION — F1]` | public boss scouting, lost carts, fieldstone, grain, cross-route mapping |
| Eastern Lake Country | T2–T4 | marsh slimes, crabs, amphibious hunters, drowned ruin guardians | fishing, boat rescue, clay, reeds, salt, shallow ore beds, causeway repair |
| Medai and outer ruins road | T3–T5 | scavengers, ruin kobold scouts, trap rooms | guide pages, expedition supplies, copper/bronze salvage, map-fragment recovery |
| Dallhari west cave | T4–T5 | bats, rock lizards, kobold scouts, falling-rock hazards | cave route unlock, iron/coal, shortcut winch, missing expedition |
| Dallhari central descent | T5–T6 | elite patrols and environmental hazards | optional high-risk shortcut, deep ore, bridge restoration |
| Eastern Ruins, surface through `Y=288` | T4–T6 | ruin kobolds, animated bronze sentinels, puzzle traps | three-sublevel dungeon, aqueduct restart, relic salvage, eastern route unlock |
| Northern Mountains | T5–T7 | mountain wolves, kobold warbands, cliff predators | Tolbana supply line, mine adits, stone/iron, rescue and route fortification |
| Tolbana–Labyrinth Forest | T6–T8 | elite kobold scouts, ambush patrols, armored beasts | raid reconnaissance, party contracts, final Floor Mapping cells |
| Labyrinth Levels 1–19 | T6–T8 | Ruin Kobold hierarchy `[CANON — C1/C2]`, traps, elites | keys, shortcuts, mapping alcoves, boss-door prerequisites, salvage |
| Labyrinth Level 20 | Boss | Illfang encounter family `[CANON — C1/C2]` plus configurable raid support mechanics | atomic Floor Clear, Last Attack handling, Floor Two transition |

Required authored caves and mine sites:

- `f01/south/quarry_01` near `(-1180,323,2860)`, a safe-edged starter quarry;
- `f01/nepenthes/root_cavern_01` near `(-3260,306,820)`, reached by two forest entrances;
- `f01/lake/clay_beds_01` near `(3180,307,640)`, partly flooded and tide-independent;
- `f01/dallhari/west_cave_01` centered on `(-2560,338,-1580)`, mandatory western crossing;
- `f01/ruins/sublevels_01` centered on `(2780,348,-1960)`, descending to `Y≈288`;
- `f01/north/mine_adit_01` near `(1500,350,-2780)`, a Tolbana supply contract;
- Black Iron's hidden dungeon and water route remain separate from ordinary mine reset systems.

Encounter zones are stored as polygons with vertical bounds, leash volumes, threat band,
spawn budget, owning quest IDs, and a stable version. No encounter may infer its floor solely
from `(chunkX,chunkZ)` because nine other floors share that column.

---

## 17. Lighting, atmosphere, and sound

### 17.1 Day

- warm clear light in southern plains;
- slight blue haze over lake country;
- cooler shadows in Nepenthes Forest;
- dusty haze in Dallhari;
- mountain mist north of Tolbana;
- Labyrinth visible as a dark stable silhouette.

### 17.2 Night

- Town of Beginnings must remain the brightest region;
- roads have sparse way lamps near settlements, not across the entire wilderness;
- Tolbana windmill windows and fountain square create a northern beacon;
- forest interiors use moon gaps rather than hidden glowstone carpets;
- Labyrinth entrance has deliberate ominous light.

### 17.3 Sound zones

- town plaza: bells, crowd bed, fountains;
- market: merchants, forge, footsteps;
- Horunka: cattle, wind, wood;
- Nepenthes: insects, leaves, distant monster cues;
- lake: water, reeds, birds;
- Dallhari: wind and rockfall;
- ruins: echoes and dripping channels;
- Tolbana: windmills, fountains, bridge creak;
- Labyrinth forest: reduced wildlife and distant drums/stone;
- dungeon: layer-specific ambience with clear enemy telegraph space.

Do not play constant loud sounds. Use sparse positional cues and cooldowns.

---

## 18. Safe zones, encounter zones, and fast travel

### Safe zones

- Town of Beginnings: D-shaped wall/parcel polygon, `X=-500..500`, `Z=4000..5000`, clipped to
  the southern arc, including gatehouses and a 16-block exterior apron;
- Horunka: authored village parcel hull within 124 blocks of `(-2780,1840)`, plus its 16-block
  exterior apron;
- Medai: authored lakeside parcel hull within 108 blocks of `(2560,-500)`, water-clipped, plus
  its 16-block land apron;
- Tolbana: authored 220 × 200 wall polygon centered on `(700,-3480)`, including bridge heads,
  gatehouses, and a 16-block exterior apron;
- two unidentified hamlets if retained: radius 68 at `(-4100,-2640)` and radius 60 at
  `(3920,-2840)`, each plus 16 blocks;
- Labyrinth mapping alcoves are rest pockets, not full safe towns: they suppress new spawns
  inside the room but do not provide permanent invulnerability or fast travel.

Safe status is polygon- and Y-bound. A player in a cave directly beneath a safe town is not
safe unless that cave is explicitly included.

### Floor Mapping

- The world is partitioned into stable 16 × 16 map cells keyed by `(globalFloor, cellX, cellZ)`;
  Labyrinth mapping uses its physical room/cell IDs.
- A player's discoveries persist as a compact bitset plus timestamped discovery events.
- Public Floor Mapping is an append-only aggregate unlocked by legitimate scouting, map
  terminals, quest surveys, and Labyrinth route confirmation.
- Mapping a road reveals the road corridor, not every secret cave within the same chunk.
- Regeneration, restart, or dimension transfer never clears mapping progress.
- Admin corrections are audited and configurable in game; direct database edits are recovery
  operations only.

### Teleport Gate

Only Town of Beginnings requires the full main-settlement Teleport Gate on Floor One.
Smaller safe settlements use waypoints, crystals, or paid/limited travel according to the final
game design.

Floor One's Town gate is the initial return anchor. On later floors, a gate becomes generally
available only after the previous Floor Boss is defeated and the destination-floor gate is
physically activated by a first-clear party member. Gate state is global, persistent, and
idempotent.

Teleport sequence:

1. validate the destination floor and gate unlock in persistent state;
2. reserve an arrival slot in the safe collision volume;
3. serialize carried/custom items before movement;
4. transfer within the current dimension for Floors 1–9 or across dimension groups after
   Floors 10, 20, …, 90;
5. confirm arrival and release the reservation;
6. on failure, restore the player to the source safe point with inventory unchanged.

No fee, animation, disconnect, or full inventory may destroy or duplicate a serialized item.

### Boss unlock and Floor Clear

- The Floor One boss door requires the configured mapping threshold, mandatory route quest
  flags, and a valid raid instance; all thresholds are server-configurable.
- A boss defeat first commits a unique `floor_clear` transaction. Rewards, Last Attack
  attribution, stair opening, gate unlock eligibility, and announcements consume that single
  transaction id.
- Duplicate death events, plugin reloads, reconnects, or crash recovery replay the committed
  transaction idempotently and cannot mint a second high-value reward.
- Inventory-full or offline recipients receive an atomic claim record; items are never dropped
  into an unsafe arena merely because delivery failed.
- Floors 1–9 open the next vertical layer in the same group. Floor 10 opens
  `aincrad:floors_11_20` at its Floor 11 arrival zone. Equivalent boundaries apply every ten
  floors.
- Floor Clear never regenerates terrain, rewrites a town, deletes mobs on unrelated active
  floors, or grants paid power. Monetization cannot bypass mapping, raid, boss, or gear
  progression.

### Encounter exclusion

- no hostile spawns within safe-zone polygon plus 16-block buffer;
- no ambush trigger within 32 blocks of a settlement gate;
- no field boss leash entering a safe-zone buffer;
- no mine reset or procedural rebuild crossing settlement foundations.

---

## 19. Build pipeline

This is the future deterministic offline pipeline, not authorization to run it now. Each phase
produces a content-addressed manifest, a resumable work ledger, validation results, and a review
artifact before the next phase may commit.

Generation identity:

```text
generationKey = hash(
  worldSeed,
  datapackHash,
  generatorBuild,
  layoutSchemaVersion,
  floorDesignVersion,
  phaseId,
  featureId,
  featureVersion
)
```

The generator sorts dimension, floor, phase, region, chunk, and feature IDs before work.
Thread count may change completion order but never block output. Each chunk progresses through
`PLANNED → GENERATED → VALIDATED → COMMITTED`; interruption resumes the first incomplete state.
Committed output is immutable unless an explicit migration owns the predecessor version and
passes protected-edit checks.

### Phase 0 — Permanent height and empty boot

- pin Paper 26.1.2 and the required Java runtime;
- install the owned, hashed custom-height datapack before any Aincrad world creation;
- create only the empty/test `aincrad:floors_01_10` world;
- verify `min_y=-64`, inclusive maximum build Y `1967`, and the intended dimension resource
  location;
- verify port `25577`, clean stop/restart, datapack persistence, and absence of accidental
  default-height worlds;
- snapshot the stopped server;
- do not generate the Town or any other Floor One terrain during this phase.

### Phase 1 — Shell

- operate on the approved custom-height dimension;
- enforce world border;
- generate top disc and underside;
- add ribs/rings;
- verify no holes or fluid leaks;
- establish fall boundary.

### Phase 2 — Macro terrain

- apply region masks;
- carve Dallhari;
- create mountain belt and Tolbana valley;
- reserve flat/terraced settlement envelopes;
- verify sightlines.

### Phase 3 — Water

- carve lakes and rivers;
- create spillways/intakes;
- run flood-fill validation;
- freeze permanent water geometry before towns.

### Phase 4 — Roads

- generate primary routes from manifest polylines;
- grade terrain;
- add bridges/causeways;
- validate walkability.

### Phase 5 — Settlements

- Town walls and primary axes;
- Central Plaza and Black Iron Palace;
- Town districts;
- Horunka;
- Medai;
- Tolbana;
- optional hamlets.

### Phase 6 — Dungeon anchors

- east ruins;
- north cave;
- Labyrinth exterior and its co-located twenty physical levels;
- boss room and Floor Two transition.

### Phase 7 — Ecology and resources

- forests and vegetation;
- resource sites/mines;
- mob spawn regions;
- quest landmarks;
- loot and exploration nodes.

### Phase 8 — Game systems

- safe zones;
- NPCs;
- shops;
- quests;
- Floor Mapping hooks;
- party/raid staging;
- boss unlock;
- Last Attack delivery.

### Phase 9 — Polish and validation

- lighting;
- sounds;
- signage;
- map reveal;
- route and collision tests;
- chunk/performance profiling;
- restart/rebuild safety;
- screenshot and in-game traversal review.

For the first proof, Phases 1–9 run only against the bounded Town review-slice mask in Section
21. Full Floor One generation remains locked until the owner accepts the slice and separately
authorizes the 306,800-chunk build.

---

## 20. Automated acceptance tests

### Geometry

- Every land column inside `r<=4976` has a valid surface and structural underside.
- No generated block exists beyond `r>5000`, except deliberate visual particles/displays.
- Minimum shell thickness is 28 blocks at the rim.
- No lakes drain into unloaded underside cavities.
- Settlement foundations do not float.
- Floor One's ceiling underside begins at `Y=420`; Floor Two's surface datum is `Y=480`.
- The Floor One Labyrinth remains inside its 300 × 300 × 100 exterior, contains twenty connected
  physical levels, and reaches the Floor Two seam without a hidden dimension.
- No non-transition feature cuts an unregistered hole through a 60-block inter-floor plate.

### Navigation

- All primary routes are walkable without flight.
- Maximum ordinary road grade passes class rules.
- Every safe town has at least two usable entrances except deliberately isolated hamlets.
- Tolbana-to-Labyrinth route reaches the entrance.
- Labyrinth Levels 1–20 have entry-to-exit connectivity.

### Canon constraints

- Town of Beginnings touches the southern edge.
- Horunka is northwest of Town beyond forest.
- Lake country is east/northeast.
- Dallhari visibly divides southern and northern travel.
- Medai lies on the eastern ruins route.
- Tolbana is the closest meaningful safe town to Labyrinth.
- Labyrinth occupies far northern edge.
- Tolbana has four windmills, three inns, fountains, ravine, bridge, and amphitheater.
- Town has circular Central Plaza and Black Iron Palace to its north.

### Gameplay

- No hostile spawn in safe zones.
- No resettable resource volume overlaps art or settlement blocks.
- No high-value reward can be granted twice from one boss clear.
- Floor Mapping increments from intended activities only.
- Boss clear unlock is idempotent.
- Last Attack serial mint is atomic and safely claimable if inventory is full/offline.
- A same-group transition lands on the correct surface Y; a tenth-floor transition lands in the
  correct next dimension group.
- With players present only on Floor One, Floors Two through Ten have zero active mob AI,
  encounter controllers, hopper clocks, and scheduled NPC entity ticks.
- Town, road, and boss navigation remains readable with cosmetic resource-pack effects disabled.

### Performance

- No runtime world-building task exceeds the configured per-tick block budget.
- Approximately 306,800 in-circle Floor One chunks, plus the boundary buffer, are generated and
  verified before public launch; this production requirement does not apply to the first bounded
  review slice.
- Pregeneration can stop and resume without changing already committed chunk output.
- Representative region-file storage and generation time are profiled before full-world output;
  no fixed disk estimate is trusted without measuring the selected terrain palette.
- Dense Town chunks meet entity/tile-entity budgets.
- Vegetation and decorative entity counts have crowd/performance LOD.
- Restart does not repaint hand-built structures.

### Persistence and determinism

- Two clean generations with the same generation identity produce matching committed chunk and
  feature hashes.
- Changing worker count does not change output.
- Killing the generator during each phase and resuming does not rewrite committed chunks.
- Every generated feature has a stable ID, version, owning phase, and chunk list.
- A simulated owner edit causes the relevant migration to stop and report a protected-edit
  conflict instead of overwriting it.
- Datapack hash or dimension identity mismatch prevents boot/generation with a loud diagnostic.
- Floor Mapping, gate unlock, Floor Clear, and item-claim state survive clean restart and
  crash-recovery tests.

---

## 21. What another AI should generate first

The first generated proof is a sparse, chunk-aligned mask—not the entire ten-kilometer floor and
not every chunk inside one oversized rectangle.

Generate only the union of:

```text
town_core:
  X = -640 .. +639
  Z = 3904 .. 5039

north_spine_corridor:
  centerline = (0,4000) -> (-80,3160)
  horizontal buffer = 128 blocks

northwest_corridor:
  centerline = (-380,4090) -> (-840,3520)
  horizontal buffer = 128 blocks

northeast_corridor:
  centerline = (380,4090) -> (960,3380)
  horizontal buffer = 128 blocks

generated vertical block ownership:
  Y = -64 .. 479
```

All 5,680 chunks in `town_core` are included. A corridor chunk is included when its center point
lies no more than 128 blocks from the named finite centerline segment. After deduplication, the
locked mask contains **7,912 chunks**, spanning chunk X `-60..67` and chunk Z `190..314`; chunks
outside the union remain ungenerated even if they fall inside that outer bounding box.
`Y=420..479` contains only the reviewed underside/plate of Floor Two. No Floor Two surface
terrain or Floors Three through Ten are generated. The manifest compiler must reproduce the
7,912 count before generation or stop.

> **Correction, 2026-07-23:** this section originally stated 6,290 chunks. An independent
> brute-force mask compiler (`scripts/verify_chunk_mask.py`) reproduced the exact stated chunk
> bounds but a total of 7,912, not 6,290; the locked radius (128 blocks) and all coordinates were
> left unchanged. See `docs/ARCHITECTURE-DECISION.md` §5 for the evidence. Owner-approved
> correction.

It contains:

- southern rim;
- complete Town of Beginnings envelope;
- all three gates;
- Central Plaza;
- Black Iron Palace;
- all Town district, building, interior, roof, light, NPC-virtualization, and service grammar;
- approximately 840 blocks of the Main North Road, 730 blocks of the Horunka branch, and
  915 blocks of the Medai branch;
- Floor One underbelly directly beneath the slice;
- the physical Floor Two plate directly above it, sufficient to validate ceiling appearance,
  artificial lighting, drainage, and inactive-layer behavior.

Review:

1. Does the city feel large enough for thousands?
2. Is Black Iron Palace unmistakably dominant?
3. Can the player navigate using the clock tower, palace, walls, and gates?
4. Does the southern edge feel like a floating-world boundary?
5. Are streets and buildings dense without becoming an unreadable maze?
6. Are every public door and all required services backed by valid interiors?
7. Does the `Y=420` ceiling read as Aincrad's underside while remaining mechanically lit?
8. Does a Floor One player leave all upper-layer entities and machines inactive?
9. Can generation stop/resume and reproduce matching chunk hashes?
10. Does restarting preserve owner edits and all generation-version markers?

Current status remains **design only**: do not create this slice until Paper 26.1.2, the permanent
height datapack, and the empty-world boot test pass. Only after the slice is accepted and a
separate full-build approval is recorded may the generator commit to the full terrain.

---

## 22. Final design statement

The First Floor should feel like a complete country trapped inside architecture:

- the Town of Beginnings is civilization and fear;
- the southern fields are possibility;
- Horunka is the first fragile foothold;
- the forest and lake routes offer different adventures;
- Dallhari is the moment the world becomes dangerous;
- Tolbana is the community's last light;
- the Labyrinth is always waiting at the northern edge.

The map is successful when a player can stand on a Tolbana bridge, hear the windmills and
fountains behind them, see the Labyrinth through the trees ahead, and understand—without opening
a menu—why the entire server must prepare before moving north.

The architecture is successful when that journey can coexist with nine other vertically stacked
floors without leaking light, entities, machines, encounters, items, or progression state across
layer boundaries.
