# Neon Nomad 3D

A mobile-base tower defence on a real, drivable planet: the drills that make your money make the pollution that wakes the nests.

You are the base. You drive a mobile core across a hostile alien world, over the horizon and around the globe. When you find ore you anchor, and build walls, extractors and cannons around yourself. Extractors emit pollution, and pollution is what the nests smell. It is not a bar but a field that spreads across the planet's surface, decays, and is absorbed by the glowing flora, so you can watch it creep toward the nests you would rather it did not reach. When the waves come, pack up (for a 50% refund) and run. The whole design rests on one tension: the thing that makes money is the thing that gets you killed.

This is a 3D remake of my 2D game *Project Neon: Warlord Update*.

## Features

- A real spherical planet you can drive all the way around, with a chase camera while mobile that blends into an RTS orbit camera when you anchor
- A build grid that wraps the planet and curves with it, with walls, extractors and cannons, and an exact 50% refund on unanchor
- A pollution field that diffuses, decays and is absorbed by flora; nests inside it wake up and send waves
- Ore veins that glow on the ground from the same function the extractors read, so the planet advertises its own economy
- About 50,000 rocks and 9,000 bioluminescent flora placed procedurally, a nebula sky, a gas giant, and a world-fixed sun, so driving far enough drives you into night
- Bloom, wheel dust as a speed cue, and terraced terrain that gives structure at base scale

## How it works

- **A tangent-warped cube-sphere.** Six grid faces projected onto the ball with a `tan(a * pi / 4)` warp, which spreads cells evenly and inverts exactly with `atan` (round-trip error 1.1e-16). Because of that, `cellAt()` returned real wrapping grid cells from day one, and a GLSL twin of the same mapping draws the build grid on the ground, so building arrived with no retrofit.
- **Movement with no poles.** Position and heading are two unit vectors, never angles; driving rotates both around their cross product. A full 18,850 m lap returns to the start with `dot = 1.000000` after passing within 8e-6 of both poles, with zero invariant violations, because nothing in the maths knows the poles exist.
- **One height function for everything.** Mesh, wheels, build cursor and placement all read the same terrain function at every LOD (dropping octaves on distant chunks tears holes at chunk seams). The build cursor ray-marches that function with about 40 calls instead of raycasting around 900 chunk meshes, so it agrees with collision by construction. Scatter comes from a hash of a fixed global cell grid, and 2,634 objects were verified byte-identical after a 5 km round trip that unloaded them.
- **Measured performance.** Boosting at 274 km/h with about 1,000 chunks, 50,000 rocks and 9,000 flora live: median frame 10.9 ms, p95 16.5 ms, zero frames over 33 ms in 900. A pollution step costs 0.2 ms. A 224 ms GC hitch was traced to cloning a `Matrix4` per placed object and fixed by writing transforms into reused `Float32Array`s. Other bugs paid for along the way: `flat` is a reserved word in GLSL (the terrain silently never drew), and a backtick inside a GLSL comment ends the template literal and kills the whole module.
- **A mechanic that silently never fired.** Pollution spread goes as `cell * sqrt(DIFFUSE * t / TICK)`, and the diffusion rate is capped while cell size is not. At 18.4 m cells the cloud plateaued at 146 m and could not reach a nest 170 m away; 36.8 m cells doubled the reach for less work.

## Tech

- three.js (vendored, no CDN or npm), ES modules, custom GLSL shaders
- Seeded 3D simplex noise, quadtree LOD chunks on a cube-sphere, instanced meshes
- A small Node static server (`tools/serve.js`)

## Running it

ES modules do not load from `file://`, so the server is required:

```
node tools/serve.js
```

Then open `http://localhost:5480/` (on Windows, `Play.bat` starts the server and opens the browser). There is nothing to install.

**Status:** the core loop (drive, anchor, build, drill, pollution, waves, cannons, pack up) is closed and measured. Not built yet: core HP and a lose condition, walls that block enemies, Tech spending and a minimap.
