# GLEANER

A top-down tower defence where the base drives, and the throttle that earns your money is the same one that calls the swarm.

Three thousand years after the machine age, a crystalline growth called the Goldsea has drowned the continent, and it is full of stored light. You drive one of the last reaping engines across it, mounting weapons on its hull while hundreds of enemies close in. The engine does not roar, it sings: drive the choir harder and you cut more crystal (Lumen, which buys weapons), but the song carries further and the spawn rate scales with its radius. There is no separate difficulty setting. There is one lever, and your hand is on it.

## Features

- A driving base with weapon hardpoints: Thresher, Glasshot, Sunlance and Censer, mounted in hull space so they ride the deck while aiming at the world
- The Chorus, three enemy types: Husks that are faster than you, and Reavers and Cantors that are slower, so they are threats you drive into rather than threats that chase (Cantors sing back and call more)
- An infinite, chunked Goldsea whose cut swath is permanent: drive back an hour later and your trail is still there
- Ruins scattered through the field (toppled columns, standing arches, a face looking up out of the crystal)
- Hundreds to thousands of live enemies on screen, with every sprite drawn in code at boot. There is not one asset file in the project

## How it works

- **Two rules keep the lever honest.** Husks move at 296 px/s against the Gleaner's 215, so speed can never buy safety, only quiet can. And escalation is per metre, not per second, so driving fast burns through the difficulty curve faster as well as spawning more.
- **The design claim is measured, not assumed.** The simulation (`sim.js`) is split from the renderer, so `?test` runs 3 seeded headless runs at each of 4 throttle settings in a few seconds and asserts the result. It currently passes 8/8, with the best distance at 75% throttle and worse at both ends:

  ```
  choir   survived  deaths  metres  lumen  killed  peak-alive
    15%      240s     0/3     774    517    1871        168
    45%     90.6s     3/3     872    582    3036        326
    75%     65.3s     3/3    1042    695    5529       1148
   100%     41.2s     3/3     867    579    4606       1993
  ```

- **The self-test caught three things play-testing would not have.** The population cap was acting as a shield: spawning stopped at the cap, so outrunning the swarm filled the pool with stragglers and silenced the spawner exactly when you pushed hardest. The furthest bodies are now recycled instead. Ramming was free (the 192-wide reaping arc killed 21,000 enemies a run at no cost), so its price now scales with throttle squared, because impact energy goes as v squared and a linear price still let flat-out win. And one run per setting was noise: verdicts flipped between identical builds until every random call went through a seedable RNG and every point became a 3-seed mean.
- **Performance.** Enemy sprites are pre-baked at 16 angles by 2 frames, so the hot loop draws them with a plain `drawImage` and no per-unit rotate. The spatial hash cell is tuned to the separation radius (24 px), cutting a query in a packed blob from about 90 candidates to 16. Measured: 1.44 ms of simulation per frame at 2,112 live units.
- **The reskin proved the split.** The game started life with a different theme and was reskinned without touching one number; the self-test returned byte-identical results afterwards. (The folder name and the `window.HARV` debug handle are leftovers from that first skin.)

## Tech

- Vanilla JavaScript ES modules and Canvas2D, no build step, no dependencies
- Structure-of-arrays swarm with a spatial hash
- A tiny Node static server (`serve.js`) that sends `no-cache`, with a Python fallback

## Running it

ES modules do not load from `file://`, so the folder must be served. On Windows, double-click `Play.bat`; otherwise:

```
node serve.js 5490      # or: python -m http.server 5490
```

Then open `http://localhost:5490/`. Open `http://localhost:5490/?test` for the headless self-test.

**Status:** playable runs with distance as the score. Weapons can be mounted and sold but not upgraded, there is no meta-progression between runs, ruins do not collide yet, and there is no touch input.
