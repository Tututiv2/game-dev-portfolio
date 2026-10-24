# Aincrad Floor 1 — Source & Confidence Ledger

Snapshot date: 2026-07-23

This project does **not** have a single official, complete survey map of Aincrad's First Floor.
The novels/anime establish dimensions, relative geography, settlements, and landmark appearance,
but they do not publish enough coordinates, contours, streets, or building plans to reproduce
every square meter exactly.

`FLOOR-01-RECONSTRUCTION-SPEC.md` is therefore a **canon-constrained reconstruction**:

- confirmed facts remain fixed;
- relative relationships are honored;
- missing coordinates and connective terrain are explicitly derived;
- invented fill is labeled and may be changed without contradicting canon.

## Evidence grades

| Grade | Meaning | Builder rule |
|---|---|---|
| `C1` | Directly stated canon fact or dimension, with a cited primary-work reference recorded by a specialist source | Treat as locked unless a better primary source contradicts it |
| `C2` | Canon relationship or appearance without a precise measurement | Preserve the relationship; geometry may be tuned |
| `A1` | Officially licensed game/adaptation presentation | Useful visual/gameplay reference, not automatically novel canon |
| `F1` | High-quality fan synthesis that cites canon | Use only to bridge gaps; never call it official |
| `D1` | Derived by this reconstruction from C1/C2 constraints | Keep internally consistent; may be revised |
| `O1` | Original connective content added so the world is playable and buildable | Freely replace while preserving neighboring constraints |

## Project-locked architecture decisions

The following are owner-approved server architecture, not claims about SAO canon:

- ten vertically stacked Aincrad floors per custom dimension group;
- 160-block local floor pitch with surface datums from `Y=320` through `Y=1760`;
- ten group dimensions from `aincrad:floors_01_10` through `aincrad:floors_91_100`;
- a physical upper-floor plate, deterministic artificial ceiling light, and optional
  resource-pack/client atmosphere;
- layer-aware mob, NPC, encounter, random-tick, hopper, and machine control because one loaded
  chunk column spans all ten vertical floors;
- same-dimension transitions after Floors 1–9 and cross-dimension transitions after every tenth
  Floor Boss;
- the First Floor Labyrinth's twenty levels are physically co-located inside its
  300 × 300 × 100 exterior in the Floor 1–10 dimension group;
- the first generated artifact remains only the bounded Town of Beginnings review slice.

These decisions use `O1`/`D1` labels in the world bible and manifest. They must not be cited as
novel, anime, or licensed-game facts.

## Sources

### S1 — First Floor overview

URL: https://swordartonline.fandom.com/wiki/1st_Floor_%28Aincrad%29

Used for:

- First Floor is approximately circular and 10 km across.
- Town of Beginnings occupies the southern end.
- Grasslands surround the starting city.
- Deep forest lies northwest; lake region lies northeast/east.
- More dangerous mountain country, ruins, and valleys lie farther north.
- Labyrinth stands at the far northern edge.
- Labyrinth exterior is approximately 300 m wide and 100 m tall.
- Labyrinth has twenty internal levels before/including the boss level.
- Tolbana is roughly 200 m across and approximately a thirty-minute walk from the Labyrinth.
- Horunka lies northwest of the Town of Beginnings.
- Medai exists on the First Floor.

Reliability note: community wiki, but it records citations to the light novels, Progressive,
anime, and side stories. Prefer the cited primary volume if a later dispute matters.

### S2 — Town of Beginnings

URL: https://swordartonline.fandom.com/wiki/Town_of_Beginnings

Used for:

- Largest settlement and First Floor main settlement.
- Located at the southern tip.
- More than half a mile across; Progressive is cited for an approximately 1 km semicircular city.
- Semicircular fortification.
- Large stone roads and street lamps.
- Circular central plaza.
- Black Iron Palace north of the plaza and the city's largest building.
- Bottom of the Second Floor is visible roughly 100 yards above town ground.
- Central market and church exist.

### S3 — Tolbana

URL: https://swordartonline.fandom.com/wiki/Tolbana

Used for:

- Approximately 200 m edge-to-edge.
- Walled town in a valley.
- White-walled windmill towers.
- Closest settlement to the Labyrinth, around thirty minutes on foot.
- Three inns, bars/restaurants, NPC homes, and multiple fountains.
- Central/largest fountain hosts the first boss strategy conference.

### S4 — Japanese SAO Progressive location index

URL: https://w.atwiki.jp/saop/pages/13.html

Used for:

- 10 km First Floor diameter.
- Anime minimap reportedly shows six settlement/safe-area dots.
- Town of Beginnings described as an approximately 1 km semicircle at the southern edge.
- Black Iron Palace is a large domed building reached from Central Plaza.
- Central Plaza is circular, paved, ringed by an arcade, with bell/clock tower and Teleport Gate.
- Horunka is an agricultural village beyond the northwest gate and forest, with inn,
  weapon shop, item shop, pasture, and cow shed.
- Nepenthes Forest lies near Horunka.
- Rata Plain is central.
- A northern cave provides the western Horunka-to-Tolbana route.
- Medai is named but otherwise largely undefined and distributed Argo's guide.
- Eastern ruins lie on the Medai-to-Tolbana route.
- Dallhari is a valley/cliff system dividing the floor north-to-south.
- Tolbana details visible in the Progressive film: four windmills, fountain/grass plaza,
  circular theater, central cliff/ravine, and suspension bridge.
- Forest field lies between Tolbana and the Labyrinth.

Reliability note: fan-maintained but unusually useful because it records specific Progressive
volume/page references and clearly labels its own full-floor image as an “imagination map.”

### S5 — Aincrad environment

URL: https://swordartonline.fandom.com/wiki/Sword_Art_Online

Used for:

- Aincrad base is approximately 10 km in diameter.
- Floor edges are physical and may be jumped; they are not generic invisible walls.
- Each floor has a Labyrinth leading to the next and a boss at the top.

### S6 — Teleport Gate

URL: https://swordartonline.fandom.com/wiki/Teleport_Gate

Used for:

- Teleport Gates occupy the central plaza of a floor's main settlement.
- A higher floor's gate becomes useful after the previous boss is defeated and the gate is
  activated.

### S7 — Hidden Dungeon

URL: https://swordartonline.fandom.com/wiki/Hidden_Dungeon

Used for:

- Hidden dungeon lies below Black Iron Palace.
- Entrance is outside the palace through an underground water-supply route.
- Black-stone walls and columns.

### S8 — Official Bandai Namco “Echoes of Aincrad” guide

URL: https://en.bandainamcoent.eu/sword-art-online/news/echoes-of-aincrad-guide-aincrad

Used as licensed-adaptation inspiration for:

- Progressive map revelation through Safety Areas/Warp Terminals.
- Labyrinth visible at long distance.
- Treasure, sealed encounter, route-observation, and checkpoint patterns.

This is an official licensed adaptation, not proof that its exact field geometry matches the
novel/anime First Floor.

### S9 — Sword Art Online: Integral Factor world/First Floor guides

URLs:

- https://saointegralfactor.fandom.com/wiki/World_Map
- https://saointegralfactor.fandom.com/wiki/1st_Floor_Guide

Used only as adaptation inspiration for splitting the southern fields into multiple playable
zones and giving Tolbana a central area plus amphitheater. Adaptation-only names such as
“Origin Plains,” “Quest Plains,” and “Rivalry Plains” are not treated as novel canon.

### S10 — Existing Minecraft precedent, explicitly not copied

URL: https://www.planetminecraft.com/project/aro---sao-project---floor-1/

Observed only for feasibility:

- An earlier team targeted an 8k × 8k Floor 1.
- Their Town of Beginnings was approximately 1.1k × 1.1k.

Do **not** copy their street plan, structures, screenshots, or assets. The page states a
CC BY-NC-ND license and additional restrictions. This reconstruction independently derives its
geometry from S1–S9.

### S11 — Paper 26.1 custom-dimension and extended-height implementation

Primary references:

- https://papermc.io/news/26-1/
- https://docs.papermc.io/paper/reference/configuration/
- https://docs.papermc.io/paper/reference/world-configuration/
- https://github.com/PaperMC/Paper/issues/13111

Implementation precedent:

- https://modrinth.com/datapack/2032-world-height

Used for:

- Paper 26.1's per-dimension world organization and custom-dimension configuration paths.
- The requirement to define the high world through a custom dimension type/datapack rather
  than treating height as an ordinary Paper tuning value.
- The selected `min_y=-64`, `height=2032`, `logical_height=2032` profile, giving an inclusive
  top build coordinate of `Y=1967`.
- The alternate `height=2096` profile if the desired inclusive top coordinate is `Y=2031`.
- Backup-first handling and the rule that the world-defining datapack is never removed from a
  generated production world.

The Modrinth entry is implementation precedent, not a primary platform specification. Pin or
own the final datapack source, validate it against the exact server build, and test world
creation/upgrades on a disposable copy before production.

## Known uncertainties

1. No canonical coordinate survey exists.
2. The exact positions of Medai, Tolbana, the ruins, and unnamed settlement dots vary among
   adaptations and fan maps.
3. “Dallhari divides the floor north-to-south” can be translated as a separating escarpment,
   canyon, or valley band; its exact direction and crossings are not published.
4. Town of Beginnings' “twenty percent” wording conflicts with the approximately 1 km diameter
   description if interpreted literally as surface area. This spec trusts the direct 1 km
   settlement dimension and treats “twenty percent” as non-survey prose.
5. No canonical street-by-street plan exists for Town of Beginnings, Horunka, Medai, or Tolbana.
6. No canonical plan for all twenty Labyrinth levels is available.
7. The boss-room dimensions are described in translations/secondary references, but Minecraft
   raid play requires a wider room than strict horizontal scaling would produce.
