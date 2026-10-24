# VESPER

First-person cinematic space sandbox. Space Engineers x No Man's Sky x Stronghold
Crusader x a roguelike multiverse.

**Status: build 1b — the planet renderer, baked. Nothing else is built yet, on purpose.**

The surface is no longer recomputed for every pixel of every frame. It is evaluated
once into three cubemaps when a world is built, and drawing it is three texture
fetches. On an RTX 4070 SUPER at 2560x1440 that took the worst case from 28 fps to
131, and made the cost *flat* — it no longer gets worse as the planet fills the
screen. See `DECISIONS.md`.

**First launch on a new machine takes about half a minute** and shows the dark
loading panel the whole time. That is Chrome compiling the shader; it caches the
result, and every launch after that is under two seconds.

---

## For the owner: two files, both double-click

| Double-click this | You get |
|---|---|
| **`VESPER.html`** | the game. Fly around a world. |
| **`shots\index.html`** | the gallery — 12 worlds on one page. |

No terminal. No `npm`. No server. Ever. `VESPER.html` is one self-contained file;
you can move it, rename it, put it on the desktop, and it still works.

**Controls:** drag to orbit · wheel to zoom · **arrow keys move the star** ·
**n / p** change world type · **]** new random world · **space** spin.

---

## THE RULE THIS PROJECT KEEPS BREAKING

**The owner never runs a command.** Not `npm`, not a dev server, not a script.
Anything they touch has to be a file they double-click.

That is why `VESPER.html` exists and why `tools/bundle.mjs` builds it:

- Chrome **refuses** to load `type="module"` scripts over `file://`. A normal
  Vite build opens to a dead black page — which is exactly what happened.
- So the build emits an **IIFE**, not an ES module, and inlines it into the HTML.
- And it inlines it **at the end of `<body>`**, because an inline classic script
  in `<head>` runs before the DOM exists and dies on `getElementById` returning
  null. Also exactly what happened.

`tests/verify_oneclick.mjs` opens the built file over `file://` with no special
flags — the same way a double-click does — and proves it renders an actual planet.
**Run it after every change to the source.** It is the only test that checks the
thing the owner actually touches.

---

## For me (the dev loop)

```
npm run dev       # vite dev server -> http://localhost:5273/orbit.html
npm run build     # rebuild VESPER.html   <- DO THIS AFTER EVERY SOURCE CHANGE
npm run verify    # prove the double-click path still works
npm run shots     # render the 12-world gallery into shots/
npm run ablate    # find out WHY something looks wrong (see below)
```

More gallery options:
```
node tests/shoot.mjs --set=all                   # one of every archetype
node tests/shoot.mjs --set=same --arch=glacier   # 12 seeds of ONE archetype
node tests/shoot.mjs --w=1600 --h=1600
node tests/shoot.mjs --sw                        # force the old SwiftShader path
```
The shoot runs on the **real GPU** now — twelve worlds in about 70 seconds. It used
to use SwiftShader, which since the bake also trips the software-renderer detector
in `boot.js` and quietly renders the gallery at reduced quality.

### Proving a change did not break the planet
```
node tests/perf.mjs              # fps at four distances. THE before/after number
node tests/attrib.mjs            # where did the frame go — one layer off at a time
node tests/ab.mjs                # baked vs ?analytic=1, same world, differenced
node tests/bake_conformance.mjs  # is the baked cube this world, the right way up
node tests/conformance.mjs       # GPU height vs the CPU mirror (binding rule 3)
node tests/terrain_conformance.mjs   # is the ground you see the ground you stand on
node tests/boottime.mjs          # double-click to planet, cold shader cache
```
`?analytic=1` renders the old per-pixel way. It is the reference the bake is
checked against — **keep it working.**

### Finding out why something looks wrong

```
node tests/ablate.mjs --arch=volcanic --seed=EMBERFALL
node tests/ablate.mjs --sweep=uWarpAmt:0,0.35,0.7,1.18
```

Renders one world repeatedly with a single shader term removed or swept. This is
how the fingerprint-texture bug was found after three rounds of guessing had
failed. Turn a term off, look, compare — do not theorise.

---

## Where things are

```
src/core/          rng, hashing            deterministic; the save format IS the seed
src/world/
  archetypes.js    THE ART BIBLE            14 world recipes, as data
  planetDef.js     roll a world from a seed pure function; nothing random at runtime
  planetView.js    assemble one world       surface + clouds + rings + atmosphere
src/render/
  planetShader.js  the orbital planet       one sphere, one draw call, all per-pixel
  glsl/noise.glsl  simplex / worley / warp  band-limited by on-screen pixel size
  atmosphere.js    the rim                  shaded by optical path length, not fresnel
  clouds.js        weather                  bands + real spiral cyclones
  rings.js         rings                    real geometry, analytic planet shadow
  nebula.js        the backdrop             placeholder for vesper's cosmic streamer
  view.js          HDR -> bloom -> ACES     the bloom threshold is load-bearing
tools/bundle.mjs   builds VESPER.html
tests/             shoot / ablate / verify / lint
DECISIONS.md       every non-obvious choice and why
DESIGN.md          living design, incl. the world-ship siege system
```

## Before you change the shader

Read `DECISIONS.md`. Most of what looks like an odd number in there is the fix for
a specific failure, and several of them cost a full debugging round:

- the bloom **threshold** must sit above lit rock and below emissive, or the whole
  disc glows and ACES turns the blown reds magenta
- every noise octave is band-limited by **`uEps`**, the on-screen size of one pixel
- fBm **gain is 0.40, not 0.50**, or every octave contributes equal slope and the
  terrain reads as noise
- **domain warping only the big shapes** — warping the fine octaves shears them
  into contour loops and the world looks like a fingerprint
- **hot emissive gets white added**, or saturated red goes pink through ACES
- **no backticks inside a shader string.** `tests/lint_glsl.mjs` enforces it.
