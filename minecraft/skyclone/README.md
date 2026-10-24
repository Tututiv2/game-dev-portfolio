# SkyClone

A SkyBlock-genre MMO as a single Paper plugin, modelled system-for-system on Hypixel SkyBlock, and built by about 60 coordinated AI agents against a frozen API contract.

SkyClone recreates the mechanics and numbers of Hypixel SkyBlock: private islands, skills, collections, minions, enchanted-material crafting, reforges, enchants, pets, accessories, slayers, mining with a Heart of the Mountain tree, farming and the Garden, fishing with sea creatures, the Bazaar, the Auction House, banks, mayors and the calendar, dungeons F1-F7 with puzzles and classes, Kuudra and the Rift. Mechanics and numbers are kept; every proper noun, line of lore and piece of art is renamed or rewritten, with each rename logged for review. Every world is generated in code. It is an independent fan project and is not affiliated with Hypixel.

## Features

- 40 feature modules on a shared core (items, stats, combat, mobs, loot, worlds, NPCs, menus, sidebar, economy, profiles)
- Data generated from public sources (Hypixel's keyless API, the NEU and SkyHanni data repos) by scripts kept in `tools/gen/<module>/`, never typed by hand
- Built for a public server: menus cancel every click outside declared slots, unique items carry a UUID, and anything that moves coins or items between players is a database transaction with the item escrowed first
- No pay-to-win and no real-money currency; anything the reference game gates behind a paid rank is gated by level or free
- Every tunable is an in-game dial (`/sb dial set`), every admin action an `/sb` command
- `/sb selftest`: 680 live self-tests that run inside the server across ticks
- Islands built by pure-Java generators with PNG previews and a walkability flood check
- ViaVersion/ViaBackwards so 26.1.x to 26.3 clients can join

## How it works

- **AI orchestration over a frozen contract.** The project ran in waves: research agents first (8 reference docs, about 17,000 lines), then a frozen API (`Sky` is the one door to every service), 5 core agents, 32 wave-1 module agents with reviewers, integration rounds, then later waves for dungeons, Kuudra, the Rift and more. `CONTRACT.md` sets the lanes: each agent owns only its module's files, modules may not import each other, and cross-module talk goes through `Sky.service(...)`, events, IDs and requirement keys. `tools/check.sh` compiles each module against the API and core only, so a cross-module import fails that agent's check. The first integration round compiled 717 files clean, booted 32 of 32 modules and passed 415 unit tests.
- **Bugs only integration could find.** The Park and Moonmarsh islands crashed on first boot: the game runs a generator twice (layout, then build), and both generators kept reservation state in fields, so the second run collided with the first. The preview tool runs once, which is why it was green. All per-run state now resets at the top of `generate()`. The largest island, Crystal Caverns, builds 27.7 million blocks in 28 seconds.
- **Testing a real client without stealing the keyboard.** Early playtests drove the game with Windows SendInput and took over the desktop. I replaced that with **TestPilot**, a Fabric client mod that drives the game from inside over a localhost port and never takes focus. Its first run found every player walking 7x vanilla speed, because `Attribute#getDefaultValue()` returns the registry default (0.7), not the player's 0.1.
- **Audited against the real game.** A mechanics audit compared each module with fresh research on the live game: 172 matches, 60 wrong and 23 missing (an outdated Kuudra loot model, a pest economy about 15x too small, mining RNG tables). The fixes landed with the suite at 1,143 unit tests and 680 live self-tests passing.

## Tech

- Java 25, Paper 26.2, Gradle Kotlin DSL, SQLite
- JUnit 5 plus in-server self-tests
- Python generators for data, a Fabric 26.x client mod (TestPilot) for real-client playtests
- Bash/PowerShell tooling: per-module compile check, island preview renderer, deploy/start/RCON scripts

## Running it

- `Dev Server.bat` builds the plugin, deploys it and starts the local dev server.
- `cd plugin` then `gradlew.bat build` builds the jar and runs the unit tests.
- `tools/check.sh <module|core|all>` runs the per-module isolation compile check.
- `tools/preview.sh <GeneratorClass>` renders an island to PNGs with a walkability report.
- In game, `/sb selftest` and then `/sb selftest report` run the live self-tests.

The dev server, generated worlds, the research data dumps and downloaded dependencies are not included in the repo.
