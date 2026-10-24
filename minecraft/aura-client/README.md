# Aura Client

A client-only Fabric mod that turns vanilla Minecraft into a Lunar-style client: custom main menu, cosmetics locker, account switcher and in-game mod manager.

It started as a cape mod and grew into the "client" half of a two-mod setup (its sibling, Aura QoL, holds the HUD and quality-of-life features). One source tree builds jars for both Minecraft 26.2 and 26.1.2.

## Features

- **Main menu** that replaces the vanilla title screen, with the window titled "Aura Client"
- **Cosmetics locker**: a 3D player preview (hover to try a cape on, click to wear it) plus a cape grid
- **Account switcher** for offline accounts, switchable from the main menu
- **Nickname** shown only on your own screen (it never changes what other players see)
- **Installed Mods manager** (`/modjars`): switch whole jars on and off, with presets, from inside the game
- **Reconnect button** on every disconnect screen (kick, lost connection, failed connect)
- **Freeze catcher**: when the game stalls for more than 2 seconds it samples the game thread every 250 ms and logs the duration, the suspect mod and the top stack frames
- A separate **Forge 1.20.1 port** with shared capes and hold-C zoom

## How it works

- **The menu extends `TitleScreen` on purpose**, because other mods check `instanceof TitleScreen`. A mixin swaps it in at the head of `setScreen`, including the `setScreen(null)` case.
- **Account switching needs two fields, not one.** `getGameProfile()` prefers a cached profile future, and singleplayer builds the host from it, so swapping only the user object leaves the old identity in place. Both are swapped (verified by the server logging the new name on join), switching is refused while a world or connection exists, and the launch account is restored from the original objects.
- **Disabling a loaded jar on Windows.** A loaded jar is locked, so turning one off queues it and a helper started on shutdown (through WMI, so it escapes the launcher's kill-on-close job) waits for the game process to exit and renames it to `.jar.disabled`. Dependency rules live in a pure, unit-tested `ModPlan`: dependencies are matched by what a jar provides, including nested jar-in-jar modules, turning a mod on pulls its dependencies in, and turning one off is refused while something enabled still needs it.
- **Two Minecraft versions from one tree.** 26.1 and 26.2 differ in where `setScreen` lives and what the skin renderer takes, so those calls go through a small `Compat` class that exists once per version; `build.gradle` picks the folder from `minecraft_version`.
- **A Forge port with no Gradle.** The 1.20.1 port is built by a Python script that merges Mojang and MCP mappings into a Mojang-to-SRG table, remaps Forge's jars, compiles with `javac --release 17` and remaps the mod forward. Forge 1.20.1 runs Mojang class names but SRG member names, so an un-remapped jar loads and then fails with `NoSuchMethodError`. Capes are shared between players through a network channel that tolerates either side lacking the mod.

## Tech

- Java 25, Fabric Loader + Fabric API, Mixin, Minecraft 26.2 and 26.1.2
- Forge 1.20.1 port (Java 17) built with a custom Python toolchain
- Python helper tools (server-list sync, logo render, mod-pack fetcher with SHA-512 checks)

## Running it

- `Play.bat` launches a dev client (`gradlew runClient`).
- `Build.bat` builds jars for both Minecraft versions into `build/libs/`.
- Forge port: `py -3 build.py` inside `forge-1.20.1/`.

Downloaded dependencies, the dev run folder and built jars are not included in the repo. Offline accounts cannot join online-mode servers; real Microsoft login is out of scope for this mod.
