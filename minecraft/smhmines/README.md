# SmhMines

A void-skyblock mining server where raw ore is the currency, villager trades are the whole shop, and the spawn is an 880-block hollow mountain whose road the plugin proves you can actually walk.

SmhMines is a from-scratch rebuild of a server design I originally ran on a hosted platform and lost the world files for. I reverse-engineered the design from 38 in-game screenshots into a written spec, then started rebuilding it as my own plugin. There is no economy plugin: you mine small, fast-resetting cubes of one material, and tools are unbreakable and upgrade in place when you hand a villager your tool plus material. The chain runs Wood, Stone, Coal, Steel, Gold, Diamond, then by elytra out to floating planets for Emerald, Sapphire, Ruby and Amethyst.

## Features

- A 2048-block-tall overworld via a datapack (y -64 to 1983, the vanilla ceiling)
- **The Hollow Mountain spawn**: 360 blocks across at the foot, 880 tall, hollow inside with glowing ore veins and gallery ledges, a 504-block plaza ring, an entrance arch, and a switchback road of 4.85 turns and 14 terraces (2,112 blocks of walking) with mine bays cut into the cliff
- `BuildQueue`: a chunk-ticketed, tick-budgeted world builder
- `ClimbVerifier`: after every build, proves the summit is reachable on foot from spawn
- A void-fall guard and a one-time bootstrap pad
- `/smhspawn build`, `/smhspawn verify`, and teardown that re-runs the build's own plan in reverse

## How it works

- **The path is the design.** Four earlier spawns were rejected, and the real defect in several was not the look but that the route did not connect: one helical ramp started eight blocks above the floor with no stairs onto it. So the build must prove itself: `ClimbVerifier` flood-fills standable space from spawn (steps of plus or minus one block, no jumps across gaps) and prints `CLIMB VERIFIED` or `CLIMB BROKEN`.
- **Constant slope, turn count as an output.** The first version fixed the number of turns; because the mountain tapers, that made the slope depend on radius, from 0.54 rise per step at the bottom to 1.28 at the top, an unclimbable ladder. Now the slope is a constant 0.5 (under Minecraft's 0.6 auto-step, so the climb is walked, never jumped) and the turn count falls out. The stair is a height field read from one integrated table, so two neighbouring blocks can never disagree about their height.
- **Bounded bulk writes.** `BuildQueue.shape()` is the only way to queue bulk work: it cuts X at 32 and Y at 3, then fills a 4,000-block budget along Z. Cutting Y is what bounds tall boxes, the shape that once ran a server out of memory. Chunks are async-loaded and pinned with plugin tickets, writes happen on the main thread with physics suppressed, every phase is individually try/caught, and textures come from a pure hash of position so re-runs are idempotent.
- **Making a huge build affordable.** A solid mountain this size is about 36 million block writes and a thick hollow one about 13 million. The shell is thin and the road brings its own rock, laying a local shoulder and carving itself out of it, so cost scales with road length rather than surface area.

## Tech

- Java 25, Paper API 26.1.2, Gradle Kotlin DSL
- A vanilla datapack for the world height
- WorldEdit and LuckPerms are the only planned third-party plugins; everything else is written in-house
- PowerShell scripts for deploy, managed start and world reset

## Running it

1. `Dev Server.bat` starts the local Paper server.
2. In the console: `op <name>`; in game: `/smhspawn build` where you want the plaza centred.
3. After a code change: `scripts/deploy-plugin.ps1` builds and deploys the jar.

Changing world height needs a fresh world: `scripts/reset-world.ps1` moves the old world aside and re-seeds the datapack from `datapacks/`. The server, world and downloaded dependencies are not included in the repo.

**Status:** the build pipeline, the verifier and the spawn generator are written; mines, the trade ladder, the traders hall and the planets are next.
