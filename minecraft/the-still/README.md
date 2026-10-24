# The Still

A vertical zombie-prison Minecraft server built on one rule: the infected perceive you through exactly one channel, and that channel is also your economy.

In The Still, noise calls the infected and heat keeps them. Mining makes both, and venting heat to stay safe yourself cooks your own mine into a nest. The world is a single custom dimension 3,840 blocks tall: a spawn town on oil-rig platforms at the top, a fog line beneath it, and 26 mining depths hung below, down to y -1850. There is no fast travel in either direction. You drop through a hole in the rig to reach the first depth, every descent after that is a cave, and the only way back up is a winch at a station that screams when you pull it.

## Features

- **Custom dimension** `thestill:depths`, y -1920 to 1919, defined in a datapack as pure void with every structure placed by the plugin
- **26 depths** with ore tables and time-budgeted refill, six 512-wide plains stations with nested quarries and rim cliffs, and cave descents that arrive through a mine wall
- **Spawn town** on a 193-block deck: plaza, NPC market, player plots, a derrick, 35 buildings on four interlocking floors
- **Economy and ladder**: `/sell`, `/sellall`, a 26-rung ladder where each rung is a depth, cosmetic-only paid ranks
- **The winch**: a 12-second haul that aborts if you walk more than 8 blocks away
- **Atmosphere for zero blocks**: a custom biome gives a black sky and real distance fog
- **Fog lamp**: a held lantern that cuts a cone through the fog, using a custom shader and a per-player packet
- Nicknames and chat colours (146 themes), sidebar, nametags, onboarding, a generated `/config` dial GUI

## How it works

- **A crash gate before any gameplay.** The store is SQLite in WAL mode with one writer thread. `scripts/crashtest.ps1` kills the server with `kill -9` at random moments over 10 rounds; every round recovered exactly the last acknowledged write, with 0 lost.
- **Building a world this size at a flat 20 TPS.** The rig town plus the depths come to 20,518,954 blocks, placed in about seven minutes at a constant 20 TPS under a 12 ms tick budget, with zero placement errors. One fix that mattered: a per-mine time budget with a minimum floor is not a budget. 26 simultaneous first fills each got the floor instead of their share, 13x the configured cost, so there is now one shared deadline per tick (1,497,600 blocks across 26 mines in about 20 seconds at 20.0 TPS).
- **Layout as a search, verified on foot.** Six hand-placed spawn rigs were rejected for stairs to nowhere, unconnected floors, open roofs and overlapping buildings, all one cause: hand-typed coordinate tables nothing validated. `RigLayout` now places a roster of buildings by search with no Bukkit dependency, so a unit test runs the real 34-building roster through the real algorithm in a second. Then `/still verify rig` flood-fills standable space in the built world and checks every door, stair end and floor: 35,413 standable blocks walked, 56 of 56 targets reached. It caught three faults the plan said were fine, including a staircase whose solid ramp cut the floor it started from in half.
- **Economy laws enforced in code.** A startup check finds craft printers (polished tuff at $7.25 crafts four-for-four from tuff at $0.70, a 10x printer), which is why the deep fillers are concrete. A test asserts income per block rises at every depth; the first draft dropped off a cliff when lapis left the ore mix.
- **No dependency for packets.** The fog lamp sends a hand-built biome packet assembled by reflection, so nothing in the world changes and a crash or relog restores the real fog automatically. A biome section has no length prefix; assuming one adds a stray byte that corrupts the next 239 sections, and a unit test pins the format.
- **Probing a world you cannot see.** I rebuilt the world empty and probed it block by block over RCON. Between probing and playing it, that found five bugs every unit test had passed, including twenty depths with no ore table and a decorative chain in the drop shaft that made the opening fall impossible, because chains are climbable.

## Tech

- Java 25, Paper API 26.1.2, Gradle Kotlin DSL, Brigadier commands
- SQLite (WAL), JUnit 5 (220 unit tests)
- Datapack (dimension, biomes), a resource pack with custom core shaders
- PowerShell scripts for deploy, headless start, RCON and the crash test

## Running it

- `Dev Server - The Still.bat` builds, deploys and runs the server in the foreground.
- `cd plugin` then `gradlew.bat test` runs the unit tests.
- `scripts/deploy.ps1` builds and validates the jar; `scripts/start-server.ps1 -Detached -WaitReady` starts it in the background; `scripts/rcon.ps1 "still verify rig"` walks the rig.
- In game (admin): `/still setup depths 26`, then `/still setup world`. Both are idempotent.

The dev server, generated world and downloaded dependencies are not included in the repo.

**Status:** the foundation gate is passed and the prison loop is built and the world has been played through. The infection systems (noise and heat) are designed and are the next phase.
