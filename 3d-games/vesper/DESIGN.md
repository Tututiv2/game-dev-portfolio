# VESPER — living design

> This is the design doc **for the new project**. `Space Game!\VESPER_SPEC.md` is the
> archived source of truth from 2026-07-24; the build prompt says that folder is read-only,
> so new design lands here and the two should be reconciled when the owner says so.
>
> Rules for this file: correct it, don't re-explain the game. Every entry says what it is,
> what it composes from, and what would break the law.

---

## THE LAW ABOVE ALL OTHERS

> **"There always has to be a path in game."**
> No capability is ever granted by a menu, an unlock screen, or a tech tree. Every capability
> is a physical object you built, from materials you gathered, with a machine you also built.
> **If you find yourself writing a "press go" button, you have violated the design.**

---

## THE BUILD ORDER — all of it, in one place

*Canonical source: `Space Game!\VESPER_BUILD_PROMPT.md` §8, which is read-only. Copied
here because a fresh session should not have to go looking in the archive to find out
where the whole thing is going. Coarse on purpose — the detail belongs to whichever
stage is actually being built.*

| Stage | Contents | Done when |
|---|---|---|
| **B1** | Engine in modules. Varied planet sizes. **The planet renderer.** Belts + rings from space. | Boots, flies, lands, walks. **The owner looks at 12 seeds and says they look "fire".** |
| **B2** | **Block building.** Blocks with size classes, grids, ghost placement, weld/grind with costs, island splitting, flight from real grid mass and thrust, rovers. | Build a ship from scratch and fly it. Cut it in half — both halves behave. |
| **B3** | **Survival ladder.** Mining + terrain deformation, ores, inventory, refinery/assembler, power, suit, death/respawn, drop pod. | The bootstrap ladder closes on the home planet, on paper AND in game. |
| **B4** | **Civilisations + megastructures.** Ages, cities, traffic, domains, raids; Dyson swarms/rings/gates visible from interstellar range, flyable, landable. | A Dyson sphere is visible from 3 ly, you fly to it, you land on it. |
| **B5** | **Combat + factions + the construction ladder.** Weapons, turrets, grid-vs-grid, reputation, stations, contracts, wars, manipulation. Drones, shipyard, blueprints, construction ships. | You can make two empires destroy each other. You can build a ship that builds a bigger ship. |
| **B6** | Multiverse crossing, the Edge, the Timeless Void, ocean worlds + leviathans, hostile fauna, cataclysms, **player-built megastructures**. Plus, new: **the siege**, and **mundicunnai**. | You can build a Dyson sphere by climbing the whole ladder. |

**B1 is where we are.** Its sub-stages, because B1 turned out to be much bigger than one
line in a table: B1a the orbital renderer (approved), B1b the bake, B1c terrain as real
geometry, B1d motion — all done. **B1e is surface detail**, and after that descent,
walking and the quadtree.

### Why the roadmap does NOT live in CLAUDE.md
CLAUDE.md is auto-loaded into every session, so everything in it is paid for every time
and every stale line in it actively misleads. That is not hypothetical: it carried a
wrong fps figure for a day, and the only reason it did not send a session optimising
the wrong thing is that the number happened to get re-measured.

A roadmap is the most perishable thing in a project. So the auto-loaded file carries
**where we are, the next three things, and the constraints later stages impose on
today's code** — and the schedule lives here, where it is read deliberately.

---

## CONSTRAINTS THAT LATER STAGES PUT ON TODAY

*This is the part of a long-range plan that actually earns its keep. Not the order of
the work — the decisions that are cheap now and a rewrite later. Every one of these has
already been stated somewhere in this file or the spec; they are gathered here so that
"what am I about to box in?" is one list rather than an archaeology exercise.*

| Constraint | Imposed by | Cost if ignored |
|---|---|---|
| **Grids support size classes from day one** | B2 | A world-ship is a grid. Hardcode 2.5 m blocks and the siege is a rewrite. |
| **Megastructures need a 4th LOD tier — visible from a planet's SURFACE at the correct angular size** | B4/B6 siege | The moment the owner described ("look up and it is *there*") is the one that tier exists for. |
| **Named NPCs exist at a walkable position inside a grid** | B5 | "Go and find the commander" becomes a menu, which violates the design law. |
| **Sea level must stay ANIMATABLE** | B6 oceans | Tides and tsunamis. It is currently baked into the surface cubemap, so a moving sea means a re-bake — acceptable now, wrong for a wavefront. **Do not build the ocean assuming that texture can be edited.** |
| **Terrain must not assume the camera is OUTSIDE the sphere** | B6 mundicunnai | You stand on the inside of a living world. Checked 2026-08-01: `terrain.js` generates a shell at `dir * groundR`, which is orientation-agnostic — only winding order and the altitude sign would need a flag. Not boxed in. Keep it that way. |
| **The unloaded-simulation rule needs an exception path** | B6 siege | Nothing simulates beyond ~10 km of the player, by design. A siege has to progress on its own clock. Cleanest: a deterministic function of (seed, gameTime) that only *resolves* when you are present. |
| **Everything stays a pure function of a seed** | all | The save format IS the seed. Anything that quietly accumulates runtime state breaks the multiverse. |

---

## SIEGE — the world-ship, and the many ways to survive it
*Owner ruling, 2026-08-01. This is new design; the 2026-07-24 audit found that "planetary
invasions" existed only as pitch prose with no section, no numbers and no phase.*

> *"civilizations to build a massive planet sized ship, they fly it towards my planet, i look up
> in the sky and see their huge ship, they can send missiles to my planet, theres ofc a commander
> somewhere on the ship, maybe i can build planetary shields before hand to defend or maybe ship
> army with missiles, or i can go to the commander and seduce him, or assasinate him, like i want
> this game to be so in depth its crazy"*

### What it is
A high-tier civilisation builds a **world-ship** — a megastructure that moves. It leaves its home
system, crosses interstellar space over days of real play, enters your system, and takes station
over your planet. From the ground you look up and it is *there*, a hard-edged shape with visible
structure, big enough to be lit by your sun and to cast a shadow across the terrain.

Then it starts firing.

### Why it belongs here
It resolves three things the project already wanted but had never joined up:
- **Megastructures were scenery.** A Dyson swarm you fly to is a landmark. A world-ship that is
  coming for you is a *story*, and it uses the same tech: a galaxy-LOD representation, a mid-range
  silhouette, and a landable interior.
- **Space felt empty.** Empty space is not fixed by more objects; it is fixed by something out
  there having intent.
- **"Living AI civilisations" had no stakes.** Civs progressed through ages whether or not you
  watched. Now what they build can arrive at your door.

### The rule that makes it a design and not a cutscene
**Every threat is a physical object with a physical weakness, and there is never one answer.**
The world-ship is not a difficulty number. It is a machine, crewed by people, with a power plant,
a magazine, a bridge, and a commander who wants things. Anything true about it is something you
can act on.

Known paths, none of them privileged:

| Path | What you actually do | Built on |
|---|---|---|
| **Shield the planet** | Climb the construction ladder to orbital-scale manufacturing and build a real shield array, before it arrives | construction ladder, power, industry |
| **Fight it** | Build a fleet, or one very good ship, and fight the escorts and the guns | grids, weapons, thrust, mass |
| **Break its supply** | Find where it refuels or rearms and take that away | civ economy, ore tiering |
| **Board it** | Fly to it, get inside, walk its corridors | it is a grid; you already walk on grids |
| **Kill the commander** | Find them, on the ship, in a place, and do it | combat, stealth, the person exists in space |
| **Turn the commander** | Give them a reason to turn: leverage you manufactured | the manipulation layer |
| **Turn its rivals on it** | Arm or provoke another civ into hitting it | false flags, arming both sides |
| **Leave** | Take your ship and go. The planet is lost. That is a real outcome | the multiverse |

### Seduction must not become a dialogue menu
This is where the law is easiest to break. A persuasion option that opens a wheel and rolls a skill
check is a menu wearing a costume — the exact thing §2.1b already rejected for building.

**Social influence is leverage, and leverage is a physical fact you created.**
You do not persuade the commander because you picked the charming line. You persuade them because
you are holding something true:
- you supplied the fuel their fleet is running on, and you can stop
- you have evidence — real or forged by you — that their rival ordered the strike
- their home system is one jump from a civ you just armed
- you saved their people from something, and it is on record
- you are standing in their reactor room and the conversation is happening there

So the "seduce him" path is the same system as the "make two empires destroy each other" endgame,
aimed at one person instead of a faction. **Same mechanics, different scale.** That satisfies
"systems over content" and it satisfies the law: the capability came from something you did in
the world, not from a stat.

### Requirements this places on earlier builds
Recording these now because they are cheap to honour early and expensive to retrofit:

1. **Grids must support size classes from day one** (already a B2 constraint). A world-ship is a
   grid. If a block is hardcoded to 2.5 m, this becomes a rewrite.
2. **Megastructures need a representation at every scale**, including *from a planet's surface,
   in the sky, at the correct angular size*. That is a fourth LOD tier beyond the three the spec
   already names, and it is the one that produces the moment the owner described.
3. **Named NPCs must exist at a position**, inside a grid, walkable-to. Not an entry in a faction
   table. The commander is somewhere.
4. **Threats need a clock.** Approach has to take long enough to prepare for and be legible while
   it happens — you should be able to look up and see it is closer than yesterday.
5. **The unloaded-simulation rule needs an exception path.** Nothing simulates beyond ~10 km of the
   player, by design, so your distant base is safe while you are away. A siege is the one thing
   that must progress on its own clock. Cleanest fix: the siege is a deterministic function of
   (seed, gameTime) like everything else, and only *resolves* against your defences when you are
   present or when the clock runs out.

### Where it lands in the build order
- **B4** civilisations + megastructures — the world-ship exists, is visible from interstellar
  range and from the ground, is flyable-to and boardable.
- **B5** combat + factions — guns, escorts, the commander, reputation, leverage.
- **B6** the full siege loop — approach clock, planetary shields, the eight paths above.

**Nothing about this changes B1.** The planet still has to be worth defending first.

---

## MUNDICUNNAI — the worlds that are alive
*Owner ruling, 2026-08-01. Captured the hour it was thought of, before it could turn back into
"wouldn't it be cool if".*

> *"they're not a species, more a category of being. Any hyperorganism dwelling in the space
> between spaces that is large enough to host complex life inside of itself. Living universes,
> creatures that have grown so large that they serve as worlds unto themselves. Some no larger
> than a small moon, others outsize galaxies… some bigger than galaxies holy cow"*

### What it is
Not a species — a **size class of creature**. Any hyperorganism big enough to hold complex life
inside itself qualifies. A mundicunnai is a *destination*: you fly to it, you go **in**, and the
inside is a world with its own sky, its own weather, its own civilisations — some of them very old,
some of them stranded there, some of them native and having never once seen a star.

They are not hostile. They are **not aware of you** at your scale, in the way you are not aware of
a mite. That is the whole horror and the whole safety of them.

### The one mechanic that makes it a design and not a skybox
**A mundicunnai has an immune system, and it is the difficulty curve.**

Everything you do inside one is measured against a single hidden quantity: how much you have
irritated the host. Mining it, burning it, building on it, killing things in it — every action has an
irritation cost, and the response is not a health bar going down, it is *weather*. Ducts close.
Pressure changes. Something the size of a mountain range moves, and the terrain you built on is now
somewhere else. White blood cells the size of freighters.

This is exactly the shape the rest of VESPER already has — a physical system with a physical
cause — and it answers the thing that kills most "giant creature" content: there is no boss fight.
**You cannot beat a mundicunnai. You can only be quiet in one, or leave.**

### Why it belongs here, structurally
It is the cheapest possible reuse of things B1–B4 have to build anyway:
- **it is a planet turned inside out.** Same height field, same chunked terrain, same shader —
  the camera is just on the concave side and the "sky" is the far interior wall.
- **it is a grid** at the megastructure LOD tier — the same fourth tier the world-ship needs
  (SIEGE, requirement 2). Two systems, one representation.
- **it is a roguelike run's best possible room.** The multiverse already needs places that are not
  star systems. A creature you can be inside is the strongest version of that.
- **it solves "space feels empty" from the other direction.** The world-ship makes space
  threatening. The mundicunnai makes it *inhabited*.

### The natives are the tutorial, and they are the point
> *"those who dwell within them know best how to avoid stimulating an immune response, and you
> would do well to seek out and heed their advice"*

So the survival information is **held by people, in a place**, and getting it is a journey — not a
tooltip. That is the law ("there always has to be a path in game") applied to *knowledge* instead of
to capability, and it is the first time this project has had a reason for an NPC to know something
the player does not.

Remnant archaean factions living inside one is the best dungeon prompt the setting has produced:
a faction that got lost during the first expansion, has been inside a living thing for an age, and
has built accordingly.

### OCEANS — and the fact that water moves
*Same conversation. Recorded together because they need the same thing from the engine.*

> *"also like tsunamis, maybe underwater civilizations also on planets?"*

The renderer already draws seas (`uHasSea`, the shelf ring, `uSeaGlow`), but they are a **colour
ramp under a height threshold** — paint, not water. Two upgrades, and the first is what the second
is built on:

1. **Sea level is a variable, not a constant.** The moment `uSeaLevel` can change over time, you get
   tides, you get a drained basin with a city in it, and you get a **tsunami** — a travelling
   displacement of the sea surface with a real wavefront, which floods a coastline you built on
   because you built on a coastline. Cause must be physical: an impact, a quake along a plate seam
   (the crack network is *already* the plate boundary), a world-ship's shot, a mundicunnai shifting.
2. **The seabed is terrain.** It already exists — the height field goes below sea level and is
   already correct down there. So an **underwater civilisation** is not a new system: it is the
   ordinary settlement system placed below `uSeaLevel`, plus pressure, plus light falloff, plus
   the fact that its people cannot survive a drained ocean. Which gives the tsunami somewhere to
   matter in both directions.

**What this places on B1 (i.e. now):** nothing expensive, one thing to not get wrong —
**sea level must be a uniform that can be animated, and must never be baked into anything that
cannot be re-baked cheaply.** Baking the ocean colour into the surface texture is the exact mistake
that would make this a rewrite later. (See DECISIONS, the surface bake.)

### Where it lands in the build order
- **B1** — nothing built. Two constraints honoured: sea level stays animatable; the terrain system
  must not assume the camera is on the *outside* of a sphere.
- **B3/B4** — the concave-terrain path and the megastructure LOD tier arrive together.
- **B5+** — natives, the immune system, the archaean remnant.

---

## OPEN QUESTIONS FOR THE OWNER
1. Should a world-ship ever be **stoppable by being out-teched**, or should it always cost you
   something — a lost base, a dead ally, a burned planet?
2. If you kill or turn the commander, does the ship **leave**, **fall to a successor**, or **go
   derelict and become a dungeon you can scan for blueprints**? (The third is the most VESPER-ish
   answer: the reward for winning is a design you could not have invented.)
3. Do you want to be able to **capture** a world-ship and fly it yourself?
4. Can a mundicunnai **die**? A dead one — cooling, its weather stopped, its natives leaving — is a
   completely different place to visit than a live one, and it costs nothing extra to build.
5. Is a mundicunnai ever a **route**? Enter one side of a galaxy-scale one, come out somewhere you
   could not otherwise reach. That would make them infrastructure as well as scenery.
