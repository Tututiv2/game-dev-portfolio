# Zombies 3D

A round-based zombies FPS in a single HTML file, built on three.js.

Survive escalating rounds of zombies on one of two maps, earn points for every hit and kill, and spend them at a Max Ammo station to refill your guns. The whole game, including the menus, HUD, maps and save system, is one self-contained `index.html`. I took it over as a completely non-functional build and made it playable and fast.

## Features

- Two maps: a suburban cul-de-sac with two houses, and a neon arena with emissive walls
- Round system: each round spawns `5 + 2 x round` zombies, a new round starts when the last one dies
- Points per hit and per kill, with floating damage numbers
- Max Ammo station (1,000 points) that refills every weapon
- Weapon table for an M1911 pistol (infinite reserve), Thompson SMG, an eight-pellet shotgun and a Ray Gun beam weapon, with per-weapon damage, fire rate, magazine, reserve, reload time and spread
- Health regeneration after 5 seconds without taking a hit
- Pause menu with a frame-rate cap (30 / 60 / 144 / unlimited), fullscreen, save and load to `localStorage`
- Live FPS counter

## How it works

- **From broken to playable.** As I received it, the game crashed on its first frame: undeclared variables in `update()`, a game state that never entered play, and a HUD that dereferenced a missing element every frame. Beyond making it start I fixed 12 bugs in all, including rounds that never advanced (an infinite spawn budget), zombies that could never damage the player (3D distance from feet to camera always exceeded melee range, so it is now horizontal), weapon hotkeys that did nothing, missing materials that rendered guns and walls flat white, failed spawns that left floating unkillable zombies, and health regeneration that overflowed the bar.
- **Performance without a rewrite.** Static collider bounding boxes are computed once instead of being rebuilt recursively per collider per check, several times a frame. Zombies and bullets come from fixed object pools, the bullet loop reuses one shared raycaster and scratch vectors, the weapon-switch material leak is cached, and per-frame allocations in movement and zombie AI are gone.
- **Frame-rate independent.** Movement, fire rate, reloads, buffs and spawning are all driven by delta time, so the selectable frame cap changes smoothness, not game speed.
- Cheap rendering choices keep it light: basic shadow maps, low-poly box zombies with animated legs, distance fog to hide the far plane.

## Tech

- Vanilla JavaScript in a single HTML file
- three.js r128, loaded from a CDN

## Running it

Open `index.html` in a modern desktop browser (it needs an internet connection the first time to load three.js from the CDN). Click to lock the mouse, WASD to move, space to jump, left mouse to shoot, R to reload, 1-4 to switch weapons, Escape to pause.

**Status:** the Max Ammo station is the only purchase wired up in this build. Weapon buying exists in code but has no in-world shop yet, so runs are played with the pistol, and the insta-kill and double-points buffs are implemented but have no drop source.
