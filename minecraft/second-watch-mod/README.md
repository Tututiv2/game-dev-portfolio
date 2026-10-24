# Second Watch

A two-player co-op horror mod for Minecraft 26.2 (Fabric) where the monster is made of the other player.

Most Minecraft horror mods are single-player shaped: something spawns and hunts you. Second Watch is built around one law: **it can only say things you have already said.** A phantom wearing your partner's skin walks ahead of you and speaks in your partner's real recorded voice from proximity chat. Talking is how you stay together, and talking is what arms the thing that separates you. The design gate for every system was that it must be undefined with one player, not just weaker.

## Features

- **The phantom**: a mannequin wearing your partner's game profile (and signed skin), steered by a director that keeps it deliberately boring: it walks, waits at doors, mines, and repeats real lines
- **Voice corpus**: every word either player speaks through Simple Voice Chat is segmented and filed; a line becomes usable 90 seconds after it is said, so a spoken passphrase is eventually turned against you
- **A per-run roll from the world seed**: one of 6 tells (fixed volume, mono, no breath, only stale lines, never speaks first, repeats itself), one of 6 triggers (talking, light, darkness, stillness, separation, digging), 4-6 nights, a previous group of 3-6
- **A generated journal** written by this run's dead previous group, with 1-2 pages that are confidently wrong
- **Escalating nights**: an endless night sent per player, a bestiary that arrives by dread tier (Follower, Watcher, Mimic, Swarm, Crawler, Tall One), a boss fought with a crafted resonator that is the loudest thing in the game
- **The ending**: a craftable Seat that only works on the final night and fits one person
- `/secondwatch start | status | book | night | phantom | voice | seat | release`

## How it works

- **Three layers, and the render layer never touches the world.** The server keeps vanilla truth (collision, damage, blocks); a "lie" layer decides what each client is told; the client draws that as pure visuals and audio. You can walk straight through a phantom, and nothing fabricated can kill you.
- **The design is a test suite.** `com.secondwatch.core` has zero Minecraft imports, so 72 tests cover the whole design in about 12 seconds. They assert the thesis directly: the director throws below two live players, phantom audio always traces back to a real utterance in the right person's voice, dread never decreases, and the same seed always produces identical rules, group and journal text.
- **Bugs that would have been near-impossible to diagnose live.** A cooldown timestamp initialised to `Long.MIN_VALUE` overflowed on subtraction, so the cooldown read as permanently active and the phantom could never fire; in game that looks like "the mod does nothing". The tests also caught a journal writer who died on night 2 and kept writing until night 5, and template names crediting people who were never in the group.
- **A missing mechanic found by watching a real server.** On a four-night run the linear dread ramp jumped straight past the tier that spawns the first lethal mob, so a whole mechanic never happened and nothing failed. The schedule is now stated explicitly, and a test over 400 seeds asserts every run reaches each tier.
- **Skins are part of the design.** The skin is a signed texture Mojang issues at login, which an offline server never receives, so a dev server can only test that the body renders. I set up a separate online-mode server to test the actual illusion, and ship the client as a 141 KB `.mrpack` that carries the mod and pins Fabric API and Simple Voice Chat by URL and SHA-1, because mismatched builds on two ends turn the horror into a crash.

## Tech

- Java 25, Fabric (Loom 1.17, Gradle 9.5), Fabric API, Minecraft 26.2 (ships deobfuscated, so no mappings line)
- Simple Voice Chat API 2.6.20 as an optional, isolated dependency
- JUnit for the pure core

## Running it

- `Play.bat` starts a dev server and a dev client (offline mode, so phantoms appear as the default skin).
- `Launcher Pack.bat` builds the `.mrpack` for any launcher that imports Modrinth packs.
- `gradlew.bat build` builds the mod jar; `gradlew.bat test` runs the core tests.

The dev world, server and downloaded dependencies are not included. The real phantom needs two players on an online-mode server with Simple Voice Chat.
