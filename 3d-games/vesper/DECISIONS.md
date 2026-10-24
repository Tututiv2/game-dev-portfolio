# DECISIONS — append-only

Every non-obvious choice and why. Newest at the bottom. If you are about to
"fix" something here, read the entry first — most of these are the fix.

---

### 2026-08-01 · Build order: the planet renderer comes before everything
Owner's instruction. No gameplay is built until 12 worlds from orbit are approved.
Everything else in the game sits on top of a world that has to be worth looking at,
and a week of dissatisfaction with this project traced to exactly that.

### 2026-08-01 · Project layout: multi-file ES modules + Vite
The single-HTML-file constraint is dropped (owner, 2026-07-24). Three from npm, not
a CDN import map — the old game needed internet to boot.

### 2026-08-01 · Fragment-shader planet, not vertex colours
The old renderer put colour on mesh vertices, so detail was capped by triangle size
and no amount of palette tuning could ever have produced the reference images. Every
crack, vein and grain is now computed per pixel. This is `render/planetShader.js`.
The chunked LOD terrain will cross-fade in on descent and reads the SAME height field.

### 2026-08-01 · Bloom is mandatory, and its threshold is the whole ball game
Emissive without a bloom pass is bright, not luminous. So the scene renders LINEAR HDR
into a half-float target and `OutputPass` tone-maps once at the end; every material
sets `toneMapped = false`.

The threshold must sit ABOVE the brightest lit surface and BELOW the emissive. The
first pass used 0.6, which is under lit rock, so the entire disc bloomed — planets came
out as pink smears, and ACES pushed the blown saturated reds to magenta. Now:
threshold 0.95, base albedos authored under ~0.75 linear, emissive 2-12 linear.
**If a planet ever looks washed out, check this before touching colours.**

### 2026-08-01 · Every noise octave is band-limited by pixel size (`uEps`)
A 4-octave ridged fBm at base frequency 8 has detail at frequency 75 — under three
pixels from orbit. The finite-difference normal then samples pure noise and the whole
world looks like etched fingerprints. `uEps` is the angular size of one pixel,
recomputed each frame from camera distance, and `bandw()` fades out any octave
approaching it. This is why the surface is clean rather than fizzing.
It also means the normal step and the detail cutoff can never disagree.

### 2026-08-01 · Calderas work through crack DENSITY, not through a glowing disc
The first version emitted a smooth radial glow inside each caldera. Bloom turned every
one into a featureless white ball stuck to the planet. A caldera now mostly raises the
local *activity* — the crack network gets dense and bright inside it — with only a
small textured direct glow. Luminous rings are still smooth on purpose: a clean glowing
circle is what that feature is for.

### 2026-08-01 · The wide plate mask carves the canyon; only its floor glows
`crackWidth` sets the canyon, `plateGlowLo` sets how much of it lights up. Using the
full mask for both lit the whole canyon and lost the glacier reference completely —
that image is a dark rock canyon with a thin bright seam at the bottom.

### 2026-08-01 · Atmosphere is shaded by optical path length, not by fresnel
A fresnel on a back-face shell puts its brightest pixel at the OUTSIDE of the shell, so
the glow floats off the planet and reads as a halo sticker. Path length through the
shell is maximal at the planet's edge and zero at the shell's edge — which is exactly
what all four reference images show, and it comes out of the geometry for free.

### 2026-08-01 · TRAP: a backtick inside a GLSL comment breaks the whole page
Every shader lives in a JS template literal. One backtick in a comment ends the string
and the rest of the GLSL is parsed as JavaScript. The error is
`SyntaxError: Unexpected identifier 'plate'` pointing at a line of shader code, and the
page never boots. **Cost two debugging rounds on day one.** Now `tests/lint_glsl.mjs`
runs `node --check` over every source file before any shoot, and points at the culprit
line when it fails.

### 2026-08-01 · TRAP: `smoothstep(hi, lo, x)` is UNDEFINED in GLSL, not reversed
Not "works backwards" — undefined, and drivers disagree. Descending ramps are written
as `1.0 - smoothstep(lo, hi, x)`.

### 2026-08-01 · TRAP: don't use `networkidle` with the Vite dev server
It holds an HMR websocket open forever, so the network never goes idle and every
navigation times out at full health. Use `domcontentloaded` plus an explicit
`waitForFunction` on a real readiness signal.

### 2026-08-01 · Clouds get exactly ONE light term
The first version multiplied by both a shade term and `(1 + lit*0.5)`, peaking at 1.66x
cloud albedo. A bright white deck went over the bloom threshold and every ocean world
rendered as a featureless white ball.

### 2026-08-01 · Base colours must be dark but NOT black
`lo`/`mid` around 0.05-0.12 linear. At 0.01 the sun has nothing to model and the
mountains are invisible — the lava reference has clearly three-dimensional grey-brown
rock, and what reads as black there is the CONTRAST, not the value.

### 2026-08-01 · fBm gain is 0.40, not the textbook 0.50
Textbook fBm halves amplitude and doubles frequency per octave, so every octave
contributes the SAME amount of SLOPE. Shade that with a normal and the finest
octave always wins, because it is the densest, and terrain reads as noise however
good the big shapes are. At 0.40 each octave contributes 0.81 of the previous
octave's slope and detail decays instead of piling up.

### 2026-08-01 · Domain warping is applied to the BIG SHAPES ONLY
**Found by ablation, after three rounds of guessing failed.** Every world was
covered in a maze/fingerprint texture. `tests/ablate.mjs --sweep=uWarpAmt:0,0.35,0.7,1.18`
showed it vanishes completely at 0, is clean at 0.7 and is total by 1.18.

Cause: warping compresses the domain locally, so a warped octave carries several
times its nominal frequency; warping the fine octaves shears them into closed
contour loops. Now the warp runs at 45% of the nominal frequency over two octaves
(fractal coastlines, which is what warping is FOR) and unwarped octaves carry the
small scale. Rocky worlds sit near 0.6-0.8; water worlds go higher because there
the warp buys coastlines and any residual swirl lands under an ocean ramp.

**Keep `tests/ablate.mjs`.** Turning one term off and looking answered in one run
what four rounds of theorising did not.

### 2026-08-01 · A caldera is a LAVA LAKE, not a bright patch
Three failed versions: a smooth glowing disc (bloom made it a white ball); a
region of denser cracks (averaged out to a pale disc); a textured version of the
same (still a disc). What the reference actually shows is the INVERSE of the
surrounding terrain — mostly molten, with dark crust rafts floating in it, the
rafts cut apart by the fine crack network. Figure and ground swap over inside the
caldera. Its outline is also noise-perturbed, because a perfect circle with a soft
edge reads as a decal stuck on the planet.

### 2026-08-01 · Hot emissive gets WHITE added, it does not just get brighter
A pure saturated red at 3x intensity has nowhere to go in a tone mapper: ACES
desaturates it sideways and the result is PINK. That is why every lava world in
this project's history looked like bubblegum. Two fixes, both needed:
`emis += white * sat(luma - 1.15) * 0.55` (physically right — something that
bright radiates across the spectrum), and authoring hot colours with the green
channel pre-lifted, because red saturates long before green does.

### 2026-08-01 · Terrain casts real shadows (4-tap march toward the star)
Diffuse lighting gives a mountain a bright face and a dark face. A mountain that
throws a shadow ACROSS the ground beside it is what makes it read as an object
standing on a world. Four height samples marched along the tangent plane toward
the sun, steps scaled by `uEps` so the shadow is the same length on screen at any
altitude, skipped entirely on the night side.

### 2026-08-01 · The height function is mirrored on the CPU, and a test guards it
Binding rule 3: one height function, shared by the mesh and by collision. The GPU
draws with GLSL, the CPU builds meshes and answers "where is the ground" with
`src/world/heightField.js`. `tests/conformance.mjs` renders the REAL GPU source
into a float buffer and compares 4096 directions per world, every run.

It earned its keep immediately — five genuine bugs, none of which would have been
found by reading:

1. **`warp1`/`warp2` use a different fBm gain** (0.6667/0.3333) than `fbmB` does
   (0.40). Mirroring the wrong one put continents in the wrong place.
2. **A wrong constant in the simplex gradient table** (`ns.x` subtracting 1.0).
3. **`1/7` rounds UP in 32-bit and DOWN in 64-bit**, so `floor(7 * 1/7)` is 1 on
   the GPU and 0 in JavaScript, and six of the forty-nine gradients fell off the
   end of the table. Noise ranged +-4.2 instead of +-1. Fixed with exact integer
   indexing on the CPU side.
4. **`pow(negative, 2.46)`** — simplex overshoots +-1, so `1 - |noise|` goes
   slightly negative and ridged terrain produced NaN. NaN height is a hole you
   fall through. Clamped in both languages.
5. **Seven of the forty-nine gradients sit exactly on `h == 0`** (0.5+0.5,
   0.786+0.214, 0.929+0.071, 0.643+0.357). Whether the fold branch is taken was
   decided purely by the sign of a rounding error, which differs between the two
   float sizes — putting **32% of all samples on different gradients**. Fixed by
   testing against -1e-4 rather than 0, which has four orders of magnitude of
   headroom either way.

### 2026-08-01 · The Worley cell hash is built from permute(), not fract()
Multiply/fract hashes (Hoskins et al.) work by squeezing entropy out of the low
mantissa bits of a `fract()`. There are 24 of those on the GPU and 53 in
JavaScript, so such a hash gives **different answers in the two languages by
construction** — it moved every crack boundary and left a ~30 m median
disagreement across whole worlds.

Rebuilt on `permute()`, the same primitive simplex already uses, with every
intermediate under 2^24 where both float sizes are exact. Two independently
salted chains give 289^2 = 83,521 cell signatures — about a 0.4% chance of one
collision among the 27 cells of a Worley neighbourhood, invisible in jitter.
Costs ~4x the fract hash on the crack path and is worth every cycle.

Result: all nine test worlds agree to **0.0003%-0.065% of relief** on the mean,
and the two terraced archetypes agree exactly. What remains lands only ON crack
edges, where a smoothstep of slope 1/crackWidth amplifies 1e-6 of drift.

### 2026-08-01 · TRAP: float textures must use NearestFilter
A `DataTexture` of `FloatType` sampled with the default `LinearFilter` needs
`OES_texture_float_linear`; without it **every fetch silently returns zero**. The
conformance harness read back all-zeros for two runs and it looked exactly like
the shader computing the wrong thing. `tests/bisect_noise.mjs` now probes the
harness itself (`d.x`, `d.y`) before probing anything else, so a broken harness
announces itself instead of being mistaken for a broken shader.

### 2026-08-01 · THE OWNER NEVER RUNS A COMMAND — ship one double-clickable file
Owner ruling, stated after the viewer opened to a dead page:
*"i should never have to do stuff manually btw like npm run or anything EVER,
should open site and run from one click please."*

Treat this as binding for the life of the project. Everything they touch is a file
they double-click. Source stays multi-file; the BUILD produces one self-contained
`VESPER.html`. Three things make that work, and each one was a separate failure
first:

1. **Chrome refuses `type="module"` over `file://`.** Double-clicking the normal
   Vite output gave a page that loaded, showed the HUD, and ran no JavaScript at
   all — visually identical to a crash. The build now emits an **IIFE**.
2. **The inlined script must go at the END of `<body>`.** Vite puts the bundle in
   `<head>`; module scripts are deferred, inline classic scripts are not. Leaving
   it in `<head>` ran it before the DOM existed and it died on
   "Cannot read properties of null (reading 'width')".
3. **Absolute paths (`src="/src/orbit.js"`) resolve to the drive root** over
   `file://` — that is literally where `D:\src\orbit.js` came from. The bundler
   asserts no absolute path survives.

`tests/verify_oneclick.mjs` opens the built file over `file://` with no extra
flags and asserts a planet is actually drawn. **Run it after every source change.**

### 2026-08-01 · A WHITE screen is the default failure mode, and it says nothing
Owner report: "launching vesper site stays white". The file was fine —
`tests/diag_realgpu.mjs` runs it on the owner's exact GPU (RTX 4070 SUPER via
ANGLE/D3D11) at full quality with zero errors.

The lesson is that **white is what a browser shows when it has not painted yet**,
so a slow start and a hard crash are indistinguishable from the outside, and the
only bug report available is "it's white". Three fixes, all in `src/boot.js`:

1. **Paint before working.** A dark curtain is in the HTML, and boot yields to
   the browser twice (`painted()`) before touching WebGL, so the curtain is
   genuinely on screen before the shader-compile stall begins. It lifts only
   after a frame has actually been drawn.
2. **Errors go ON THE SCREEN**, with the stack, in a red panel that says to send
   the text. Never guess at a white page again.
3. **Detect the software renderer.** If hardware acceleration is off Chrome
   silently falls back to SwiftShader, where this shader takes ~25 s per frame —
   which IS a white screen. Boot now probes the renderer string on a throwaway
   canvas, explains `chrome://gpu` in plain language, and drops to 0.4x
   resolution, no MSAA, no self-shadowing, and a 96-segment sphere so it still
   runs.

**Always run `tests/diag_realgpu.mjs` as well as the SwiftShader tests.** Every
test before this one used a software renderer, which is far more permissive than
a real driver and hides both compile errors and cost.

### 2026-08-01 · TRAP: screenshotting a WebGL page too early returns pure WHITE
Under SwiftShader a frame takes seconds. Waiting for 3 rAF callbacks and then
screenshotting caught the canvas before the compositor had ever received a painted
frame; the capture came back pure white and looked like a catastrophic render bug.
It is not — `tests/diag_white.mjs` renders the same scene through the dev server
and the built file and both come out identical. Wait for 6+ frames plus a settle.

Related: `drawImage()` on the WebGL canvas ALSO returns black, because there is no
`preserveDrawingBuffer`. Measure brightness from puppeteer's screenshot, fed back
in as a `data:` URL. And bound the assertion at BOTH ends — "something is lit"
passed happily on a pure-white frame, which is the exact thing it was meant to catch.

### 2026-08-01 · The recorded "1–4 fps" was an artefact of the screenshot harness
**Correcting the record before building on it.** CLAUDE.md said the planet ran at
76 fps at arm's length and 1–4 fps when it filled the frame. It does not. Measured on
the owner's RTX 4070 SUPER with `tests/perf.mjs`, which opens the shipping file with no
flags and times a fixed window of frames:

| | 1280x900 | 1920x1080 | 2560x1440 |
|---|---|---|---|
| far (3.15R) | 89 | 72 | 48 |
| mid (1.9R) | 59 | 45 | 28 |
| close (1.22R) | 93 | 53 | 31 |
| grazing (1.045R) | 167 | 109 | 61 |

The 1–4 figure came from `closeup.mjs`, which runs with `?pdb=1`
(`preserveDrawingBuffer`) so it can read the canvas back. On ANGLE/D3D11 that forces a
full framebuffer copy every frame and roughly halves everything, uniformly — which is
why that harness reported the same 2 fps at every distance, a number that should have
looked suspicious on its own.

**Two lessons.** A measurement harness is part of the measurement. And the worst case
is NOT "the planet fills the screen" — it is **mid distance**, where the whole disc is
visible: at 1.045R most of the globe is outside the frustum and `featureField`'s
`t > 2.4` early-out fires for nearly every feature, so the shader gets *cheaper* as you
close in. Never re-quote a number from a doc; re-measure it.

### 2026-08-01 · Ablate for COST, not just for looks — the surface was 74% of the frame
`tests/attrib.mjs` turns one layer off at a time and re-times. At 1920x1080, 1.9R:

```
everything           44.6 fps   22.4 ms
without surface     168.3 fps    5.9 ms   costs 16.5 ms
without clouds/rings/atmo/backdrop/bloom     all within +-2 ms of noise
```

So the planet surface was three quarters of the frame and everything else was
rounding error. That is what justified the bake and ruled out optimising anything
else. `ablate.mjs` answers "why does it look like that"; this answers "where did the
frame go", and neither question should be answered by reasoning.

### 2026-08-01 · THE BAKE: the surface is evaluated once into cubemaps, not per pixel
`render/planetBake.js`. Nothing in `surfaceAt()` depends on the sun or the camera — it
is a pure function of a direction — so it is computed once per direction on arrival and
looked up thereafter. Three RGBA16F cubemaps at 1024 per face:

```
col   rgb = albedo     a = height     emis  rgb = emissive   a = spare
nrm   rgb = normal     a = ambient occlusion
```

`planetShader.js` is now three GLSL strings assembled three ways, so the art is written
down **once**: `SURFACE_SRC` (expensive, bakeable), `LIGHT_SRC` (cheap, per frame,
cannot be baked because the star moves), and the two ways of obtaining a `Surf` —
ANALYTIC (`?analytic=1`, the reference) and BAKED (shipping). Self-shadowing survives
because its four height taps become four texture fetches.

**Gas giants stay analytic on purpose.** Their bands drift with `uTime` and they have no
height field to evaluate, so a bake would freeze the weather to save nothing.

**Sea level is baked in.** DESIGN.md now wants tides and tsunamis, so a moving sea means
a re-bake and eventually a separate water surface drawn over baked land. Do not build
the ocean assuming this texture can be edited.

### 2026-08-01 · TRAP: reassigning `material.uniforms` silently keeps the OLD values
**The worst bug of the session, and it produced no error of any kind.** The bake reused
one `ShaderMaterial` and pointed it at each new world with
`material.uniforms = {...}; material.needsUpdate = true`. Three caches the uniform list
against the program the first time the material is drawn, so the shader went on reading
whatever it was bound to first.

What that looks like: **a completely plausible planet that is not the one you asked
for** — right archetype, right palette, right kind of features, wrong everything else,
and *identical for every seed*. It survived a screenshot, a boot check with zero console
errors, a brightness assertion, and my own eyes comparing two renders.

Fix: build a **new ShaderMaterial per bake** (`PlanetBake.setWorld`). That recompiles
nothing — three caches programs by source, so it is a hash lookup.

The general rule: **treat a ShaderMaterial's `uniforms` object as fixed at
construction.** Mutate `uniforms.foo.value`, never `uniforms`.

### 2026-08-01 · How that bug was actually found, after four wrong theories
Worth recording as method, because the wrong theories were all reasonable and each one
cost a round trip. In order: a flipped cube face; the bake not running at all; band
limiting differing between the two paths; float precision in the half-float height.

What found it was **adding probe passes to the bake shader that read one thing at a
time** (`uPass` 3–8, still in `planetShader.js`, keep them):

- **pass 3** bakes a known function of direction and reads it back → is the cube the
  right way round, separately from whether the field is right.
- **pass 4** bakes `heightAt(n)` with no `surfaceAt`, no struct, no out-parameter →
  splits "wrong place" from "wrong value".
- **passes 5–8** bake a *uniform* straight into the output → is the value even
  arriving. This is the one that found it: the GPU reported `uSeedOff.x` as 8.0156
  where JavaScript held 1.1925, **and reported the same wrong number for two different
  worlds**.

**The first orientation probe was `n.y`, and it passed while the bake was wrong.** Of
course it did: `n.y` is invariant under every rotation and mirror about the Y axis, so
it cannot see four of the six faces being wrong. A probe function must share no
symmetry with the thing it is probing; it is now `dot(n, vec3(0.3, 0.5, 0.8122))`.

`tests/bake_conformance.mjs` is the permanent version: it correlates the baked cube
against the CPU height mirror per face. Right world, right way up → r > 0.997. Wrong
world → r ≈ 0. It also round-trips the directions first, because a broken harness and
a broken bake look the same. All nine worlds now agree to 0.06–0.53% of relief.

### 2026-08-01 · TRAP: `String.replace` expanded `$&` INSIDE the minified bundle
`tools/bundle.mjs` spliced the script in with
`html.replace(/<\/body>/i, block + '</body>')` — a **string** replacement whose text was
the entire minified bundle. String replacements interpret `$&`, `` $` ``, `$'` and `$$`,
and minified three contains `...,$&&m(M,N,k,B),...`. That `$&` expanded to the matched
text, splicing a literal `</body>` into the middle of a JavaScript expression.

The page then died with `SyntaxError: Unexpected token '<'` pointing at vendor code,
with nothing wrong in any source file. It appears and disappears depending on which
names the minifier picks, so it is triggered by unrelated edits.

Fixed two ways: a **function** replacer (never substitutes), and the bundler now pulls
the inlined script back out of the finished HTML and parses it before writing the file.
The failure mode this guards is a page that loads, shows the HUD and runs nothing —
which has reached the owner once already.

### 2026-08-01 · The bake costs 25 ms. Allocating its textures cost 1.3 SECONDS
A viewer that built a fresh `PlanetBake` per world spent 1330 ms per switch. Almost none
of that was rendering: allocating three half-float cubemaps and freeing the previous
three is what D3D11 charges for, at roughly fifty times the cost of the 18 draws that
actually do the work. One `PlanetBake`, owned by the viewer, reused for every world —
25 ms per switch and half the peak VRAM. `tests/bakecost.mjs` guards it.

Also: time the compile separately (`r.compile()` before the loop) or the first bake
reports 25 seconds and you go optimising something that was never slow.

### 2026-08-01 · The real boot cost is a 25 second SHADER COMPILE, once per machine
`tests/boottime.mjs` / `boottime_warm.mjs`, cold Chrome profile, real GPU:

| | first frame |
|---|---|
| baked (shipping) | 28.0 s |
| analytic (old path) | 33.7 s |
| **second launch, warm cache** | **1.7 s** |

So the first double-click on a given machine spends about half a minute compiling this
shader through ANGLE's HLSL translator, and every launch after that is under two
seconds because Chrome caches the compiled program on disk. **This was already true
before the bake** — the bake is 5.7 s faster, not slower. It is very probably part of
what "launching vesper site stays white" was, alongside the software-renderer fallback
already recorded above. The boot curtain covers it, but it should say so.

### 2026-08-01 · `gEps` is a global, not a uniform, because a cube face is not a screen
Band-limiting needs the angular size of one SAMPLE. For a camera that is one number for
the whole frame, so `uniform float uEps` was fine. For a cube-map bake it is not: a
texel at a face corner subtends about 47% of the angle a texel at the face centre does,
and using one value per face leaves eight visibly soft patches where the corners meet.
So `bandw()` reads a global `gEps` that every `main()` assigns as its first statement,
and the bake computes it per pixel from the exact face geometry.

**A note here previously blamed a stale Vite module graph for a conformance failure
around this change. That was wrong; see the entry below. Corrected rather than
deleted, because a confident wrong explanation in this file is worse than none.**

### 2026-08-01 · TRAP: a debug pass in a shader cost 13 s of every first launch
The bake shader carried diagnostic passes (`uPass` 3–8) left in after the
wrong-planet hunt. One of them, `heightAt(n)` on its own, inlines the entire noise
library a **fourth** time. Measured with `tests/boottime.mjs`, three cold runs each:

| shipping shader | first frame |
|---|---|
| with the probes compiled in | 41.0 / 41.9 / 42.3 s |
| probes moved behind a flag | 25.3 / 26.1 / 26.5 s |

The unchanged analytic path sat at 32.7 / 33.0 / 33.2 s throughout, which is what
says the harness is trustworthy and the difference is real.

**Debug code in a shader is not free the way debug code in JavaScript is.** Every
extra call to a big function is another full inline for the driver to translate.
`bakeFrag(probes)` takes a flag; the game passes false, `checkOrientation` passes
true and pays for its own compile.

### 2026-08-01 · The shipping draw shader has no noise in it at all
`VERT_BAKED` + `FRAG_BAKED` are compiled as **GLSL 300 es**, alone in this project.
The reason is one line: the vertex shader samples the baked height cubemap to
displace the limb, and sampling a texture in a vertex shader needs an explicit mip
level (there are no derivatives there). GLSL ES 1.00 spells that `textureCubeLod`,
which ANGLE does not expose in a WebGL2 ESSL1 vertex shader — *"no matching
overloaded function found"*. `textureLod` is core in 300 es.

Worth it because it takes the **shipping** program down to zero copies of the
height field: three texture fetches, four more for the self-shadow, and the
lighting. It bought about 2 s of first-launch compile now (28.0 → 26.1 s) and it
stops the draw path growing when terrain arrives. It costs nothing in quality — at
320 segments a vertex is ~6 km apart on a 600 km world and a baked texel is ~1.2 km,
so the map is already finer than the mesh it displaces.

Note three does **not** alias `gl_FragColor` for a raw ShaderMaterial under GLSL3;
declare the output.

### 2026-08-01 · The stall bar is CSS on purpose, not a progress callback
Compiling blocks the main thread for ~25 seconds on a machine that has not seen
this shader. **No script runs during that** — no timer ticks, no repaint, nothing
can be reported. Anything driven from JavaScript freezes mid-way and reads as a
crash, which is the report this project already got once.

A CSS animation of `transform`/`opacity` runs on the compositor, so it keeps moving
straight through the stall. That is why `#bootBar` and the world-switch `#busy` chip
are stylesheet animations. Everything the owner needs to know is also said BEFORE
the stall starts, because afterwards is too late — and only on the first run,
tracked in `localStorage` (best-effort; `file://` may refuse, so it must not throw).

### 2026-08-01 · TERRAIN: the ground is real geometry, and it proves the next problem
`world/terrain.js`. Below 10% of a radius the surface becomes a mesh built from
`groundR()` — the same function collision answers with. `tests/terrain_conformance.mjs`
takes the vertices back out of the finished BufferGeometry, undoes the chunk-local
transform and asks the height field again:

```
VESPER-1 glacier    25 chunks  3900 verts   mean 0.004 m   worst 0.018 m
VESPER-1 volcanic   25 chunks  3900 verts   mean 0.003 m   worst 0.027 m
ASHFALL  crystal    25 chunks  3900 verts   mean 0.000 m   worst 0.000 m
```

Millimetres. **Binding rule 3 holds for the mesh**, and it holds by construction
rather than by promise. 165 fps with the chunks on.

Three things that were not obvious and are now settled:

- **Vertices are stored relative to their chunk's centre.** float32 gives out at
  about 0.1 m on an 850 km world, which is the difference between walking and
  vibrating. The big number lives in the chunk transform.
- **The direction is a vertex ATTRIBUTE, not reconstructed from the position.**
  Reconstructing means normalising a number that has already lost the precision
  the local frame exists to protect.
- **The ground reuses `FRAG_BAKED` verbatim** — the identical fragment shader the
  orbital sphere uses, sharing one uniforms object. The cut from "planet in front
  of you" to "ground under you" is the most visible transition in the renderer and
  two copies of the art WILL drift. Only the vertex stage differs.

**What it is not: a quadtree.** Every visible chunk is one depth, so there are no
LOD cracks and also no distant terrain — past the 5x5 grid you are still looking at
the orbital sphere, whose displacement is driven toward the true relief as you
descend so it does not swallow the mesh whole. That is the next piece.

### 2026-08-01 · Standing on a planet found two bugs that only exist at the surface
Neither is exotic; both render as a featureless screen, which is the failure mode
this project keeps meeting.

1. **`dist` is measured from the DATUM, and the ground is not at the datum.**
   1.004 radii on a world with 20 km of relief is two kilometres UNDERGROUND. The
   result is a flat screen the colour of the atmosphere seen from inside, and it
   reads exactly like the terrain having failed to generate. The camera is now
   placed against `groundR()` along its own direction, never below it.
2. **`lookAt(0,0,0)` means staring at your own feet.** Correct from orbit, useless
   on the ground — a black screen. The look target now blends to the horizon as
   altitude drops.

Also: `camera.near` was a fixed 5% of the radius — **15 km** on a small world. Stand
on the ground with that and everything in front of you is clipped away. It follows
altitude now.

### 2026-08-01 · The bake is 1.2 km per texel, and that is a SECOND detail floor
Known before: the height field's finest feature is about 4 km across, so close up
the ground is smooth (Trap 6). Now measured: the baked cubemap at 1024 per face is
**~1.2 km per texel**, so even where the field HAS detail, the texture cannot show
it. From 14 km up the glowing plate seams are correct and correctly placed, and
smeared across dozens of pixels each.

So there are two floors stacked, and both have to lift before the surface can look
like `refs/SURFACE.md`:

- **geometry** — needs octaves finer than 4 km, which a single global noise domain
  physically cannot provide (snoise wraps every 289 units, capping the biggest-to-
  smallest ratio at ~289:1 where ground detail needs a million to one). It has to
  be generated in a **chunk-local frame**, which is the other half of why chunked
  terrain exists.
- **shading** — the terrain material must stop being a pure cubemap lookup up
  close and add an analytic detail layer on top. Cheap: a few octaves of noise,
  not the seven full height-field evaluations the orbital path used to do.

### 2026-08-01 · The glow MOVES, without un-baking anything
Owner, on first seeing the finished renderer: *"the planet isn't moving, would be
cool if the glowing stuff was moving on a planetary scale"*.

The surface is a baked texture, so the emissive pattern is frozen. The way out is
that **you do not have to animate the texture, you animate what it is worth**:
everything in `glowPulse()` is a MULTIPLIER on the baked emissive. Dead rock has
emis ~= 0, so no amount of modulation lights it, and the motion is therefore
confined to the crack network by construction — it reads as light travelling
through the veins rather than as an effect laid over the picture.

Measured on EMBERFALL at three pinned times against t=0:

| | peak change | change on DARK ROCK |
|---|---|---|
| t=2 | 94 / 255 | 0.22 / 255 |
| t=4 | 116 / 255 | 0.52 / 255 |
| t=6 | 125 / 255 | 0.73 / 255 |

Three layers at deliberately unrelated rates so nothing visibly repeats: rings
surging outward from each caldera (scaled by that feature's own glow, so a lava
world throbs and an ice sheet only breathes — no new per-archetype data), two slow
plane waves for a global uneven breath, and a fast low-amplitude flicker.

Costs no noise, no texture and no re-bake: 2560x1440 stayed at its measurement
ceiling. It lives in both the analytic and the baked path from one source, so
`ab.mjs` still compares like for like.

**The pulse is asymmetric on purpose** — it dips 1.35x further than it spikes and
is clamped at +0.55. The bloom threshold is load-bearing (see the very first
entry in this file); a pulse that pushes ordinary lit rock over it makes the whole
disc glow and ACES turns the blown reds magenta.

### 2026-08-01 · Spin is time-based, and it stops when you are standing on it
It was `rotation.y += 0.002` per FRAME, so the world turned at whatever rate the
machine happened to render — a faster GPU meant a faster planet. It is now driven
by `def.day`, the world's own day length, already rolled from the seed. Default
ON: it used to default to off, which is why the first report was "it isn't moving".

**Held while the terrain is up.** The chunk grid is chosen from the camera's
position in the planet's frame, so a spinning planet slides the ground out from
under it and it regenerates continuously — and one chunk is a thousand height-field
evaluations. Standing still on a spinning world is the player's own frame anyway,
and that arrives properly with walking.

### 2026-08-01 · `?time=N` pins the clock, and every shooting harness passes it
Clouds drift and the glow now pulses, so two runs of the gallery differ even with
identical source — which destroys the thing the fixed seeds were for, that "a
change to the shader is visible as a diff, not as a different planet". `shoot.mjs`
and `ab.mjs` both pin it. Without this the A/B test would have started reporting a
difference between the baked and analytic paths that was only the two browser
sessions being started a second apart.

### 2026-08-01 · The SAME uniforms trap, a second time, in the conformance harness
`conformance.mjs` began failing at 11–21% of relief. Identical numbers every run, so
not flaky — and it had passed at 0.0003–0.065% earlier the same day on the same source.

It is the trap from earlier in this file, in a second place: `gpuHeights()` kept one
`ShaderMaterial` and bolted each world's uniforms onto `mat.uniforms` afterwards.
Three binds the uniform list to the program the first time the material is drawn, and
`material.needsUpdate = true` does not rescue it. **Every world uniform was arriving
at the shader as zero.** Fixed the same way as the bake: build the material fresh per
call with the uniforms present at construction. Programs are cached by source, so it
costs nothing.

**Three wrong diagnoses came first, and each one was cheap only because it was
checked rather than acted on:**

1. *"gEps as a global is not surviving the compiler."* Plausible — the failure
   signature is exactly what band-limiting-left-on looks like. Killed by adding a
   probe mode that reads `gEps` back out of the shader at the point of use: it
   reported `1.0000000116860974e-7`, precisely what was assigned.
2. *"SwiftShader computes the noise differently from ANGLE."* Killed by
   `bisect_noise.mjs`: every primitive — simplex, fBm, ridged, Worley, both warps —
   matched to 1e-5 or better.
3. *"a stale Vite module graph"* — the earlier note, now retracted above.

**The lesson is about the instrument, not the bug.** `bisect_noise.mjs` gave a clean
bill of health while the height field was 20% wrong, because its probes use hardcoded
frequencies (`snoise(d*7.3)`) and never read a uniform at all. A bisection tool that
cannot see a whole class of input will confidently exonerate it. `conformance.js` now
has probe modes 20-24 that read a world uniform straight back out, which is what
found it — the same technique, and the same bug, as `uPass` 5-8 in the bake.

**If a uniform is not doing anything, do not reason about it. Read it back.**
Owner's mid-session ruling. Recorded as a real system because the 2026-07-24 audit found
"planetary invasions" had zero design anywhere. Key constraint it adds early:
megastructures need a fourth LOD tier — visible from a planet's SURFACE at the correct
angular size — and named NPCs must exist at a walkable position inside a grid.

---

# B1e — THE DETAIL LAYER (2026-08-02)

## snoise's true period is 867 per axis, and 289 is not a period at all

`noise.glsl.js` has always said "snoise wraps at +-289" and warned that a frequency
past ~250 makes a world visibly tile. Both halves of that are wrong, and the correct
version is what makes a metre-scale detail layer possible at all.

Measured, 20,000 random points, CPU mirror:

| shift | result |
|---|---|
| 289 on one axis | **NOT periodic** — values differ by up to 0.5 |
| 867 on one axis | **periodic**, 0 mismatches, worst 3e-13 |
| 289 along (1,1,1) | periodic |

The reason is the skew. `i = floor(v + (x+y+z)/3)`, so a shift only lands on the same
lattice cell when the sum of the shifts divides by 3 — true for 289 along the diagonal,
false for 289 on one axis, true again for 867 (= 3 x 289) on any axis.

**Why it matters:** exact periodicity is a licence to RANGE-REDUCE. `p` and
`p - 867*round(p/867)` are the same point, so a coordinate can be folded back to +-433
before any float32 precision is spent on it. Without that, a detail octave at frequency
75,000 resolves to ~0.009 of a noise cell on the GPU and arrives visibly quantised.
With it, the same octave is exact. `world/detail.js` folds every octave independently
(`p_k` is not 2.03 x `p_(k-1)` once folded, so the reduction cannot be carried forward).

One caveat found on the way: periodicity holds *except* exactly on gradient tie-break
boundaries, where a hand-picked round-number coordinate produced a 4e-3 discrepancy.
Same class as trap 4. Harmless here — it is a measure-zero set and 4e-3 of a noise
octave is nothing — but do not expect bit-exactness at a tie.

## The floor was measured, and it was not what this file said

`tests/detail_floor.mjs`. Old CLAUDE.md claimed a "~4 km" geometry floor, reasoned
from the finest octave's frequency and never measured. The measurement says:

- **worst case 4096 m** — so the number was right, as a worst case
- but per world it ranges 1024 m to 4096 m, and
- **terraced worlds are a separate and worse pathology**: Shattered and Crystalline
  quantise elevation in steps of **1.8 - 3.6 km** and are EXACTLY flat between them
  (rms height difference 0.0000 m at 0.25 m, 1 m and 4 m separation). `refs/SURFACE.md`
  independently says terraces want to be 5-15 m steps. That is a 200x error and it was
  hiding inside a single averaged number.

After the detail layer: **worst floor 4 m**, with an unbroken fractal ladder
(curvature ratios 4.9, 4.0, 4.3, 4.5, 4.2, 3.8, 3.9 across five decades).

### The instrument had to be rebuilt twice, and that is the real lesson
1. **First version measured the wrong thing.** It took the height difference over a
   step d. That is dominated by the local TILT — these worlds average a 0.37 gradient,
   so at d = 0.25 m the height changes ~9 cm regardless, and a detail layer with tens
   of metres of amplitude moved it by under 1%. The instrument could not see the thing
   it was built for. Fixed by using the SECOND difference, `h(a) - 2h(m) + h(b)` along
   one great circle: a tilted plane gives exactly zero at any tilt, so what survives is
   structure at scale d and nothing else.
2. **Then the verdict logic was inverted.** For a fractal surface `slope ~ d^(H-1)`
   FALLS as d grows; constant slope means planar. The first detector called that
   "detail". Both bugs were in the test, not the terrain.

## THE DETAIL LAYER IS NOT PART OF heightNorm(), ON PURPOSE

`world/detail.js` is added by `groundR()` and by nothing else.

- The orbital sphere, the bake and `tests/conformance.mjs` all read `heightNorm`, and
  none of them can represent a 7 m feature — one baked texel is 1.2 km. Putting detail
  there would change every world that has ever existed to no visible end, and would
  break the CPU/GPU agreement conformance exists to guard, because the analytic GPU
  path has no range reduction.
- `groundR` is read by the terrain mesh and by collision. Both sides of binding rule 3
  get the detail, so what you see is still what you stand on.
- The finest octave is a FIXED wavelength (3.5 m), not a camera-derived one. Physics
  may not depend on where you are standing — same argument as heightField.js.

Gain here is **0.50**, where `fbmB` uses 0.40. That is not an inconsistency: 0.40 is an
orbital art direction (its finest octave lands near pixel size, and at 0.5 every octave
carries equal slope so the densest wins and the disc reads as fizz). Down here the
finest octave is metres across and the job is the opposite one. At 0.40 the fine end
came out at **1.8 cm of relief across 7 m of ground** — measurably present, visually a
smooth ramp.

## reliefBoost MUST NOT be applied to terrain normals

This one cost the most and is the most transferable.

`uReliefBoost` (1.7 - 2.4 per archetype) exaggerates the SPHERE's shading normal,
because at true scale a 10 km peak on a 600 km world tilts the normal 12 degrees and
reads as a gentle bump. The first version of the terrain normal applied the same factor,
for continuity across the transition.

But the terrain is real geometry, and the height field already averages a **0.68
gradient — a 34 degree slope — at 3.7 m vertex spacing**. Multiplied by 2.2 that is 56
degrees; measured mean normal came out **71 degrees off vertical**. With the star 34
degrees above the horizon, nearly every normal then faced AWAY from it, so `lit` and
`wrap` both clamped to zero and every pixel reduced to `s.col * uAmbient`.

**Result: a 1280x720 ground shot containing exactly ONE distinct colour**, sitting on
top of a height field with five measured decades of detail and a per-vertex normal that
was completely correct.

If the transition ever visibly flattens, ease the SPHERE's exaggeration out with
altitude. Do not fake the ground's.

### A false trail worth recording: the "factor of 2"
Chasing this, the mesh normal was compared against a reference gradient and came out
2.0x too steep — a suspiciously round number that looked exactly like a central-
difference spacing bug, and sent the search into the indexing. **The reference was
wrong, not the normal.** It divided the height change by the 3D CHORD between the two
sample points, which includes the vertical component, instead of by the true arc
length. Recomputed against `radius * angle(dirA, dirB)`, mesh normal vs gradient normal
agree to **tan ratio 0.996**. A round number in a discrepancy is not evidence that the
code is wrong; check the yardstick first, especially one written in the same hour.

## The haze term is a limb effect and the ground is not a limb

Second, independent cause of the same flat frame.

`shadeSurf` had `mix(outc, uAtmo*hazeLit, pow(fres, 4.5)*uAtmoAmt*0.5)` where
`fres = 1 - dot(n, viewDir)`. Seen from space that peaks at the limb, which is right —
that is where a sight line really does cross the most air. Stand on the planet and every
sight line is nearly tangential, so **fres is ~1 at every pixel** and the term stops
being a limb and becomes a flat film over the whole frame.

The numbers: the surface's own albedo is ~0.012 linear (the near-black rock the entire
art direction rests on — law: the bright things are emissive). The haze was adding a
constant ~0.12. **Ten times the picture, all of it one colour.**

Fix: scale by distance, which is what inscatter actually depends on —
`sat(dist/(uRadius*0.03))`. Nothing at your feet, full strength by the horizon. At
orbital range dist is many radii, this saturates at 1, and the approved view is
unchanged (verified: `closeup.mjs` mid shot is the same picture, `perf.mjs` flat at
165-169 fps, cold boot 24.3 s vs 26 s before).

Real aerial perspective from inside the shell is still unbuilt — `refs/SURFACE.md`
item 5 wants the horizon to GLOW, which is this same optical-path argument seen from
underneath. This change removes the wrong thing; it does not add the right one.

## THE PROBE IS WHY ANY OF THIS WAS FOUND

`uTerrDbg` in `fragBaked(true)`: 1 mesh normal, 2 baked normal, 3 difference, 4 lit,
5 sun shadow, 6 baked albedo, 7 AO. One run separates "the normal is wrong" from "the
normal is fine and the lighting is discarding it", and those two have identical
symptoms from outside.

It found, in one pass:

| probe | colours in frame | reading |
|---|---|---|
| mesh normal | **8531** | the geometry is rich and correct |
| baked normal | 80 | near-constant, as a 1.2 km texel must be |
| lit = sat(N.L) | 119, mean 6.5/255 | the surface is unlit almost everywhere |
| baked albedo | 4 | near-black by design |
| final shaded | **4** | — |

Before building it, three causes were guessed in a row (the haze, the atmosphere shell,
the cloud sphere) and ablated by setting `.visible = false` on scene objects. **Every
one of those ablations was a no-op, because orbit.js reasserts visibility every frame.**
Four measurements that all looked like evidence and were not. If a value is not doing
anything, read it back — do not reason about it, and do not ablate through a path that
something else is writing every frame.

## Two things the ground shot exposed that are NOT fixed

1. **Below the datum you are inside the orbital sphere.** Where the ground sits under
   `def.radius` (routinely — measured -911 m on VESPER-1 glacier) the sphere is over
   your head and front-face-culled, so past the chunk grid there is nothing at all.
   This is the same item as "retire the orbital sphere below the transition", and it is
   now urgent rather than tidy.
2. **`?dist=1.002` does not stand you on the ground.** `dist` multiplies the RADIUS,
   i.e. the datum, and the viewer's floor clamp only catches the case where the ground
   is ABOVE the datum. On VESPER-1 glacier that documented setting leaves the eye 5.7 km
   up. `tests/groundshot.mjs` solves for the altitude instead: place the camera, ask
   `groundR` where the ground is along that direction, then set
   `dist = (ground + eye)/radius`.

---

# B1f — THE QUADTREE (2026-08-02)

## Why the single-LOD grid had to go, in one number

At the depth that resolves metre-scale detail, a 5x5 grid of chunks covers **300 m**.
The horizon from 120 m up on a 310 km world is **8.6 km**. So 96% of a ground frame
was empty space with an island of terrain in it — and because the ground routinely
sits below the datum, the camera was inside the front-face-culled orbital sphere, so
there was not even a coarse planet behind the island.

`tests/quadtree.mjs` is the instrument. It runs without a GPU and prints the two
numbers that trade against each other — leaf count (7.1 ms of main thread each) and
vertex spacing under the camera (the real resolution of the ground you stand on):

| eye | leaves | deepest | vertex at foot | horizon |
|---|---|---|---|---|
| 200 km | 33 | 2 | 3.8 km | 405 km |
| 8 km | 152 | 7 | 118.9 m | 70.9 km |
| 2 km | 199 | 9 | 29.7 m | 35.3 km |
| 120 m | 324 | 13 | **1.86 m** | 8.6 km |
| 2 m | 444 | 16 | 0.23 m | 1.1 km |

The leaf count stays roughly flat because the split test is ANGULAR: subdivide while
the node's edge is more than `splitK` of the distance to it. Tune `splitK` there, with
the numbers in front of you; guessing it from the formula gives either a slideshow or
a smooth ramp.

## Skirts, and why their depth is measured rather than assumed

Two neighbouring leaves at different depths do not share edge vertices, so the finer
one's edge sags between the coarser one's and you get a seam you can see the sky
through. The options are stitching index buffers (needs cross-face neighbour depths on
a cube-sphere, which is where this kind of code goes to die), forcing a 2:1 balanced
tree, or skirts. Skirts cost one ring of vertices and cannot fail.

**But the depth of a skirt has nothing to do with the width of a chunk.** It has to
exceed the HEIGHT DISCONTINUITY it is plugging. The first version used `edge*0.35`,
about 13 m on a fine chunk. The crystal and shattered archetypes quantise elevation
into terraces **1.8-3.6 km** tall, so one chunk edge sits a kilometre and a half above
its neighbour, and the ground came back with hard black gaps exactly where the
terraces are — which is exactly where that archetype is most dramatic.

Fixed by measuring the chunk's own vertical range out of the sample grid, which bounds
any disagreement a neighbour can have with its edge:
`skirt = max(edge*0.35, (rHi - rLo)*0.75, 1)`.

The tell that it was cracks and not dark terrain: the colour count in the frame went
**7262 -> 2238** when the skirts were fixed. The gaps were leaking the bright glowing
terrace behind them, so closing them REMOVED colours. A number going down can be the
fix working.

## Band-limiting the detail by LOD does not break binding rule 3

`groundR(def, dir, minWL)`. The mesh asks for octaves down to twice its vertex
spacing; anything finer cannot be represented, costs 2.0 of the 7.1 ms a chunk takes,
and ALIASES — adjacent vertices sample unrelated phases of an octave the mesh cannot
carry, which is what makes distant terrain shimmer and pop across an LOD change.

**Collision never passes it.** The default is 0, which is the whole truth.

`terrain_conformance.mjs` now checks BOTH, and the second check is the real one:

- every chunk against the field *at its own filter* — 0.000-0.005 m mean
- the FINEST leaves against the **unfiltered** field collision reads — 0.000-0.006 m
  mean, 0.021 m worst

The second check also caught a real subtlety on the way in: it failed at first because
the harness sat at `?dist=1.002`, which is 5.7 km up, where the tree only subdivides
to depth 9 and legitimately carries a 19 m filter. Binding rule 3 is a promise about
the ground under the PLAYER. Put the camera where the player would be before asking
whether it holds — the harness now solves for the altitude the way groundshot does.

## camera.up was world +Y, and that was the owner's bug

Reported as *"ground is on my left"*.

`camera.lookAt()` resolves the camera's ROLL from `camera.up`, which three defaults to
`(0,1,0)`. That is fine in orbit, where up is a free choice. Standing on a sphere it is
wrong everywhere except the equator, and near a pole it is catastrophic: the local
radial is nearly parallel to world +Y, `lookAt` has almost no plane left to work with,
and the frame rolls up to 90 degrees. **The horizon ran vertically down the screen with
the ground stacked along one side.**

The ground was never misplaced. The camera was on its ear.

Now `camera.up` is the local radial, lerped back to +Y as you climb so the approved
orbital framing is untouched. This is worth remembering as a class: a bug that presents
as "the world is in the wrong place" is often the camera's basis, and no amount of
staring at terrain code will find it.

## The orbital sphere is retired below 2% of a radius

Once the tree reaches the horizon by construction, the sphere has no job down there:
below the datum the camera is inside it and it is front-face culled anyway. Dropped
only after the tree has finished streaming, or the gaps would show sky.

## What this exposed and did not fix

- **The ground is too steep to walk on.** 0.68 mean gradient (34 degrees) at metre
  scale, maxing at 8.0 (83 degrees). That number was authored for the orbital view and
  has never been judged from underneath. Walking needs it addressed.
- **Terraces are still 200x too large** — and they are what tears the skirts, so the
  fix pays for itself twice.

## Ground shots are dominated by WHERE YOU STAND, and the harness has to choose

A ground shot of a volcanic world came back at 35 distinct colours, essentially
black, and looked like the renderer failing. It was the harness. An arbitrary point
on a volcanic world IS dead black rock, and correctly so: the art law is that the
cracks concentrate into blazing regions with dead rock between them, which is what
makes them read as fire rather than as texture.

`groundshot.mjs` now sweeps camera azimuth/elevation, converts each to the direction
it would actually look from, and stands nearest a CALDERA (feature kind 1) — where a
world puts its light. Same world, same code: **35 -> 6723 colours, sd 0.55 -> 47.3**.

That shot immediately exposed the next real problem: standing inside a caldera the
frame is blown flat gold. Emissive runs 2-12 and is tuned for orbit, where a caldera
is a few pixels against a dark disc; filling the frame with it overruns the tone
mapper completely. Needs a falloff that knows how close the camera is, and it must
not touch the orbital look, which sits deliberately tight against the bloom knee.

---

# B1g — METRE-SCALE TERRACES (2026-08-02)

`microTerrace`, in METRES, per archetype: glacier 9, crystal 12, shattered 14,
tundra 6, everything else 0. Applied by `groundR()` only, so the orbital sphere and
the bake never see it.

Metres, not normalised height, and that is the whole distinction from the existing
`terrace` field. `terrace` scales with relief and lands at 1.8-3.6 km: the shattered
plateau silhouette from orbit, which is intended and approved. refs/SURFACE.md is
about a different thing entirely — the ice plates you STAND on, 5-15 m steps with
near-vertical risers — and that has to be an absolute size or it changes with the
world's drama.

Measured plate treads: glacier 12.5 m median, crystal 40 m, shattered 50 m. SURFACE.md
asks for 10-50 m plates.

## Three things went wrong in a row, each one instructive

### 1. floor() amplifies an epsilon into a WHOLE STEP
`terrain_conformance` came back with a worst error of exactly **9.000 m** on glacier
and exactly **12.000 m** on crystal. Those are the two step sizes to the millimetre.
Mean stayed at 0.005 m, so it was a handful of vertices.

First theory was the LOD detail filter: the mesh feeds groundR a filtered height,
collision feeds it the full one, they differ by ~0.2 m, and a sample near a boundary
gets pushed across it. Fixing that (terraced worlds now always evaluate full detail)
was correct and necessary — and did not change the number at all.

The actual cause: **`aDir` is stored as float32**. The test re-evaluates the field at
the rounded direction, which differs from the float64 direction that built the vertex
in the last few bits. On a smooth field that is a micron. On a staircase it picks the
other side of the riser.

That is not fixable and should not be — a vertex sitting exactly on a riser is
genuinely ambiguous. So the test now characterises it instead of tolerating it: count
vertices off by >0.5 m, and require every one of them to be a whole step and to be
under 2% of the total. Glacier: 24 of 48,840, all whole steps. That distinguishes
"the mesh is a staircase" from "the mesh is wrong", which a single tolerance cannot.

### 2. A vertical riser cannot be drawn by any mesh
A hard `floor()` puts the entire step inside one quad, and that quad's diagonal turns
it into a zigzag. It rendered as a row of dark triangular teeth along every plate
edge. **No amount of resolution removes it** — the artefact scales with the grid.

I first blamed the skirts and rewrote them to measure the chunk's PERIMETER rather
than its whole area (a real improvement — a chunk with a cliff through its middle no
longer wears a curtain hundreds of metres deep all the way round). The re-render was
byte-identical: same colour count, same sd, same picture. The skirts were not it.

Fix: smooth the step over a fraction of its own height, `w = 0.16`. On a 9 m step at
a 0.7 gradient that is a riser about 4 m across, two vertices at standing LOD. It also
removes the epsilon amplification in (1) entirely — **0 of 49,500 vertices** off by
>0.5 m afterwards, down from 24.

`w = 0.22` was tried first and turns the whole landscape into snow drifts. The window
is narrower than it looks.

### 3. Quantising a height that contains the fine detail gives drifts, not plates
Even at w = 0.16 the plates read as soft mounds. The sub-metre octaves drag the
surface back and forth across each step boundary, so the riser is ragged and the plate
top is not flat.

What the reference actually shows is **flat plates with texture lying on them**, which
is a different composition: quantise the COARSE field to get clean plates, then add the
fine octaves on top of the result. `detailSplit(def, dir, splitWL)` returns
{coarse, fine} in one pass, so this costs nothing extra — and terraced worlds were
already paying full detail because of (1).

## Terracing is also the answer to "too steep to walk on"

`tests/walkable.mjs` prints the gradient distribution over 2 m of ground, in bands a
person cares about. It was written for the steepness item and immediately explained
the terrace result:

| world | step | flat <10 | walkable <30 | cliff >60 |
|---|---|---|---|---|
| glacier | 9 m | **51.9%** | 10.2% | 21.9% |
| crystal | 12 m | **72.3%** | 12.9% | 1.0% |
| shattered | 14 m | **72.0%** | 14.2% | 1.0% |
| tundra | 6 m | **54.8%** | 12.6% | 8.7% |
| volcanic | none | 15.3% | 47.3% | 17.1% |
| oceanic | none | 20.9% | 57.4% | 0.9% |

Terracing moves mass OUT of the middle of the histogram: flat plate you can stand on,
and a riser you go around, instead of a uniform 34-degree slog. The un-terraced worlds
are the ones with 15-21% flat ground and half the surface at a permanent trudge.

So the fix for walkability is probably not "reduce the relief" — which would cost the
approved orbital drama — but "give more archetypes a terrace character of their own".
Lava has plate boundaries too; they are just not ice steps.

## Note on the detail floor

Terraced worlds now report a COARSER floor (glacier 0.25 m, crystal 16 m) and that is
correct rather than a regression: a plate really is flat, so there is no curvature to
find inside one. `detail_floor.mjs` labels macro and micro terraces separately now,
because reading a 16 m floor without knowing the world is terraced looks like a bug.

## OPEN ARTEFACT: the picket fence on terraced cliffs

`shots/ground/crystal-night.png`. Standing at the foot of a terrace on crystal or
shattered, every cliff edge is a comb of evenly-spaced vertical bars with gaps
between them, a kilometre tall. It is the most visible remaining defect on the
ground and it is NOT yet explained.

Ruled out, each by direct test rather than by reasoning:

1. **Skirts.** Changed the skirt depth from `max(edge*0.15, perimeterRelief*0.6)` to
   `max(edge*0.35, perimeterRelief*1.1)` — a factor of nearly two — and re-rendered.
   The image came back BYTE-IDENTICAL: same colour count, same standard deviation,
   same picture. Whatever the bars are, they are not skirt geometry.
2. **The micro-terrace riser.** Softened to w = 0.16 and the fine detail moved to
   after the quantisation. Fixed the sawtooth teeth on glacier completely; did
   nothing to the fence.
3. **The macro-terrace riser.** `heightAt` did a hard `floor()` at 1.9-3.6 km, which
   really is unmeshable — the whole step lands inside one quad. Softened over 12% of
   the step, in BOTH the CPU and the GLSL (conformance re-run: crystal and shattered
   go from exactly 0.0000% disagreement to 0.0005%, still passing, and the orbital
   silhouette is unchanged because 88% of the step is still flat). The fence
   survived that too.

That change was then REVERTED, and the revert is the part worth remembering. It was
principled, it was applied symmetrically to the CPU and the GLSL, and conformance
passed - so it looked like a win. It was not one:

  - it did not fix the fence, and
  - it made terrain_conformance WORSE. smoothstep(0.44, 0.56) has a slope of about
    12 inside its band, so it multiplies the float32 epsilon in the aDir attribute
    up into metres. Crystal went from agreeing EXACTLY to 55 vertices off by up to
    3.4 m. A hard floor() quantises that epsilon away completely - which is exactly
    why the terraced worlds had always been the only ones with zero disagreement, a
    line that had been sitting in the conformance output the whole time.

A change to heightAt is a world-version event. It has to buy something, and "the
reasoning is sound" is not the same as "it bought something".

Best remaining hypothesis, untested: this is a heightfield-on-a-grid trying to
represent a near-vertical wall. Each grid column drops independently, adjacent
columns differ slightly, and the triangles between them turn edge-on — so you get
vertical strips with slivers of background between. If that is right, the fix is not
in the terrace at all; it is that a cube-sphere heightfield cannot express a cliff,
and cliffs need either much finer LOD locally or to be a separate representation.

**Do not start by re-tuning the skirts.** That was the first instinct twice and it
was wrong both times, and the byte-identical re-render is the proof.

---

# B1h — THE MULTI-AGENT RUN, AND FOUR MISTAKES I MADE READING IT (2026-08-04)

Three agents on disjoint files (terrain data / lighting shader / read-only diagnosis),
then an adversarial reviewer per change. 809k subagent tokens, 48 minutes, 0 errors.
The agents' work was fine. My handling of it was not, and that is the transferable part.

## THE REVIEWER FOUND A BUG IN MY HARNESS THAT INVALIDATED A DAY OF ART JUDGEMENT

`tests/groundshot.mjs` shot every world "day" and "night" and printed the label.
`setSun(az, el)` sets a direction in WORLD space; whether that is night where the
CAMERA is standing depends on where the camera is — and once the harness started
aiming itself at a caldera, the camera moved a long way.

Measured after the reviewer pointed at it: the frame written as `volcanic-night.png`
had the sun **+17 degrees above the local horizon**. Broad daylight. Every "night"
shot in this session was daylit, on a project whose own art direction says both
reference images are the night side and that a daylit render "will look fine and
prove nothing".

Fixed: LIGHTS is now the sun's elevation above the CAMERA'S OWN horizon in degrees
(day +35, night -12), solved by sweeping sunEl after the camera is placed, and the
achieved angle is printed on every line with a DAY/NIGHT tag. The harness now says
which it is instead of asserting it.

**The reviewer found this by reading the instrument, not the picture.** Nobody
looking at the images would have found it, because a daylit frame looks fine.

## MISTAKE 1 — I REVERTED ON THREE REASONS AND TWO WERE WRONG

I reverted the whole lighting change citing: (1) it does not fix the blowout,
(2) it costs 2x fps at grazing, (3) it was tuned against a mislabelled frame.

- (2) was FALSE. After reverting, grazing was still 72 fps. The cost is chunk
  STREAMING, not shading — grazing is the only perf distance where the quadtree is
  actively building, and terracing volcanic forces the full-detail path (my own
  earlier rule: a terraced world cannot take a filtered height). Reverting a shader
  to fix a CPU cost was never going to work, and one control run would have said so.
- (1) was FALSE. Re-shot at TRUE night with the lighting reverted, the frame was a
  total white-out — visibly worse than with the agent's change in. The change was
  helping; it just was not finishing the job.

Only (3) survived. **Two of my three stated reasons were inferences I had not
tested, in a session whose whole discipline is not doing that.**

## MISTAKE 2 — I DESTROYED WORK I COULD NOT GET BACK

`cp _src_backup_b1h/render/*.js src/render/` overwrote the agent's lighting work with
no copy kept. This repo is not under git. The backup protected me from the agents;
nothing protected the agents' work from me. **Before reverting someone else's change,
copy it aside first** — the cost is one `cp` and the alternative is unrecoverable.

## MISTAKE 3 — I PUT BACKTICKS INSIDE A SHADER STRING

Re-implementing the emissive fix by hand, I wrote a GLSL comment containing
`+sat(...)+` in backticks. That is TRAP 1 — the first entry in the list in CLAUDE.md,
which I wrote earlier the same day. The build failed at planetShader.js 467:6.

`tests/lint_glsl.mjs` exists exactly for this and it did not catch it, because I had
chained `npm run build && node tests/lint_glsl.mjs` — the build failed first, so the
linter never ran. **Lint BEFORE build, not after.** A guard placed downstream of the
thing it guards is not a guard.

## MISTAKE 4 — A PROBE THAT COULD NOT SEE THE THING IT WAS PROBING

To test whether `uEmisNear` was reaching the shader I stood the camera at the default
position and swept the uniform 0 -> 1. No change at any value. That looked like proof
the uniform was dead.

It was not: the default position on a volcanic world is DEAD BLACK ROCK (frame mean
2.9/255), so there is no emissive there to multiply. The probe was blind to the only
input it was testing — the exact failure already written down in this file as "an
instrument that cannot see a class of input will confidently exonerate it".

## WHAT IS ACTUALLY IN THE TREE

KEPT — the terrace work (`microRiser` per archetype, all 13 worlds terraced). It is
measured, reproduced independently by its reviewer, and conformance-clean:
volcanic 15.3% -> 42.2% flat, oceanic 20.9% -> 54.0%, no regression on the four that
already had terraces, and mean gradient went UP on both (redistribution, not
softening). Its reviewer did find three comment numbers stated as measured that are
computed or wrong — those are listed under "still to fix" below.

LOST — the lighting agent's night-side work (inner glow, wet specular, horizon glow).
Destroyed by mistake 2. A minimal `uEmisNear` proximity fade was written by hand to
replace the emissive half of it; it is IN the tree, it builds and lints, and it is
NOT yet proven to do anything, because the probe that was supposed to prove it was
mistake 4. Treat it as unverified.

## STILL TO FIX
- Verify or remove `uEmisNear` — probe it AT A CALDERA, at true night, with
  groundshot's own aiming, not at the default camera.
- Redo the night-side art pass, now that the harness can actually produce a night.
- Three comments in archetypes.js/heightField.js state computed values as "measured"
  (the 9 m / 15 m treads and the riser angles). Label them as derived or measure them.
- volcanic at microRiser 0.26 needs a real look now the lighting is settled — it is
  past the w=0.22 line that was rejected on sight for ice, and the argument that
  "drifted" is a material verdict rather than a geometric one is reasoned, not seen.
- perf grazing 168 -> 72 fps is chunk streaming on newly-terraced worlds. Not a
  rendering regression, but it is a real cost and the budget may need revisiting.

## uEmisNear — verified, and it uncovered the REAL ground-emissive problem

Fixed and measured after the four mistakes above. Three things had to be right before
the uniform did anything at all, and each failed silently:

1. **The gate was a distance to the DATUM.** `length(uCamObj - n*uRadius)` is the
   distance to the datum sphere, and a caldera floor is kilometres below it, so the
   fade saturated off at every pixel. Sweeping the uniform 1.0 -> 0.06 changed the
   frame by NOTHING: six identical readings.
2. **Then the CPU fed it datum-relative altitude**, which goes strongly NEGATIVE
   inside a caldera — the same mistake one level up. It now passes
   `R - groundR(...)`, the true height above the ground, one 7 microsecond call/frame.
   *Twice in one change I used the datum as a stand-in for the ground.*
3. **Then a comment ended early and the prose became GLSL.** The shader failed to
   compile, the frame went black, and `lint_glsl.mjs` passed — it checks for
   backticks, not validity. `diag_realgpu.mjs` printed the offending line. **If a
   shader edit makes the frame go uniformly dark, run diag_realgpu before anything
   else**; a compile failure and a lighting bug look identical from outside.

With all three fixed, the sweep finally responds — standing in a caldera on
VESPER-1 volcanic at a true -12 degree night:

    value   mean   blown>250   sd (structure)
     1.00   216.5      3.2%     23.5   <- the blown cream this replaces
     0.50   169.2      0.0%     49.8   <- peak contrast, shipped
     0.25   128.6      0.0%     40.5
     0.06    59.3      0.0%     19.6   <- fire becoming a dim stain

Shipped at 0.5. The blow-out is gone: 3.2% of the frame at pure white -> 0.0%.

### BUT THE GROUND EMISSIVE IS STILL STRUCTURELESS, AND EXPOSURE WAS NEVER GOING TO FIX IT

Look at the frame, not the number. sd 49.8 is the best of the five, and the picture is
still a flat gold field — the standard deviation is coming from the horizon gradient,
not from the crack network. The reason is already written down in this file for a
different channel: **one baked texel is 1.2 km**, and a ground view spans about 2 km.
The emissive cubemap has essentially ONE texel across the whole frame.

That is exactly the failure the NORMAL map had, and the fix there was to stop reading
the bake and use the mesh (`fragBaked(true)`). The emissive needs the same treatment:
a crack network evaluated at ground scale near the camera, faded into the baked one
with distance. That is the real B1h item, it is a much bigger piece of work than an
exposure constant, and the exposure constant was hiding it.

**The lesson for the metric: sd told me 0.5 was the best of the five values, and it
was right. It could not tell me that all five were the wrong question.** A number that
ranks options cannot tell you the options are all bad — only the picture does that.

---

# RESOLVED: conformance.mjs was never measuring the height field (2026-08-05)

`node tests/conformance.mjs` reported **9 CHECK(S) FAILED**, CPU vs GPU heightAt
disagreeing by 11-21% of relief, on every world, repeatably, across rebuilds.

**The height field was correct the entire time. The WebGL context was dead.**

Under `--use-gl=swiftshader`, SwiftShader lost this page's context during module
init — before a single probe ran. A lost context reads back as all-zeros, so the
harness computed `|cpu - 0|` and called it a divergence. It is now on
`--use-angle=swiftshader`, which reaches the IDENTICAL SwiftShader device (both
report `Vulkan 1.3.0 (SwiftShader Device (Subzero))`, verified by reading
`UNMASKED_RENDERER_WEBGL` back), and all 9 worlds pass at a worst mean of 0.065%
of relief.

### What made this cost a session

**Every ruled-out item in the old note was ruled out correctly, and none of them
mattered.** Setting `uSeamGlow` to 0 changed nothing — because the context was
dead. The rebuild changed nothing — because the context was dead. The macro
terrace lines really were character-identical. Each check was sound and each
one confirmed the same invisible fact.

**Two clues were sitting in the output and were read backwards:**

- `crystal` p50 was exactly **9.3750%** of relief and `shattered` exactly
  **15.6250%** — 3/32 and 5/32. Those are terrace-quantised numbers, and they
  were read as "the terraced worlds quantise the drift away." They are actually
  what `|cpu - 0|` looks like on a world whose heights are quantised. A suspiciously
  round number in an error metric is a fingerprint of the metric, not of the error.
- `terrain_conformance.mjs` still PASSED, which was read as "the mesh is fine, so
  the analytic GPU path has diverged." The real reason it passed is that it
  launches with `--use-angle=default --enable-gpu` — **it runs on the real GPU.**
  It was not exonerating the mesh; it was the one instrument still alive.

**The instrument reported "console errors: 0" while its context was gone.**
Chrome logs a context loss as a WARNING (`CONTEXT_LOST_WEBGL: loseContext`), and
the harness only counted `error`-type messages. So the one message that named the
actual fault was filtered out by the fault detector.

### What found it

The technique already written down twice in this file: **read a value back out of
the shader instead of reasoning about it.** Probe mode 9 returns `gEps`, which is
a literal assigned at the top of `main()`. It came back **0**. A literal cannot be
wrong, so the fragment shader was not running at all — which took the diagnosis
from "which term diverged" to "nothing executed" in one number. Modes 20-24 then
showed every world uniform reading 0 as well, and the same page on the real GPU
agreed with the CPU to 1e-5.

### Why the flag is not the fix

The legacy flag is *unreliable*, not reliably broken: `bisect_noise.mjs` loads the
same page with the same flag and ran clean once, then failed 12 consecutive times
afterwards on unchanged code. `verify_oneclick.mjs` (orbit.html, not
conformance.html) passes under it throughout. Chasing why Chrome's software GL
does this is a rabbit hole and was not pursued past establishing that
`--use-angle=swiftshader` is the same device and has been alive on every run.

**The durable fix is that an instrument must prove it is alive before it is
allowed to report numbers.** Now enforced:

- `conformance.mjs` checks the context is not lost, checks `gEps` reads back as
  1e-7 (i.e. the shader executes), prints the renderer string so a silent
  fallback to the real GPU would be visible, and catches "context lost" by TEXT
  wherever it is logged, at any log level.
- `compare()` returns the GPU's own spread and the test rejects a constant one.
  The test already refused a constant CPU mirror, on the grounds that a constant
  "agrees" with anything; the GPU side of that same guard was missing, and it is
  exactly the guard that a readback of 4096 zeros would have tripped.
- `bisect_noise.mjs` ABORTS with exit 2 on a dead context instead of reporting
  every primitive as DIFFER. This tool has now failed in both directions — it
  once exonerated a 20%-wrong height field (its probes never read a uniform), and
  it has now indicted a correct one (its probes read a dead context). Both times
  it was confident.

**The general rule, and it is the third instance of it in this file: a test that
cannot detect its own death does not measure what it claims to. Assert liveness
before reporting a number.** The 11-21% figure was reproducible, stable across
runs, consistent across all 9 worlds, and completely meaningless — reproducibility
is evidence about the instrument, not about the thing measured.

**Housekeeping:** the eight harnesses that load `conformance.html` or a page under
software GL were moved off the legacy flag. `tests/diag_conf.mjs` is kept — it is
the uniform/gEps readback rig that found this, and it has now paid for itself
three times.

---

# 2026-08-05 · The seam term: emissive with ground-scale structure at last

Recorded after the fact, because the session that built it spent its remaining
runway on what turned out to be a dead GPU (above) and never wrote it down.
**The numbers in this section were measured by that session, not re-measured
here; what IS verified here is that the code is present and conformance-clean.**

The standing problem was that the ground read as flat gold. The cause was already
understood and is the same one solved for the normal map: **one baked texel is
1.2 km and a ground view spans 2 km**, so the crack network — the entire emissive
structure of a world — has no structure at all down there. `uEmisNear` fixed the
blow-out (3.2% of the frame at pure white -> 0.0%) but a constant cannot add
structure, and it was hiding the fact that none was there.

**The fix does not evaluate the network near the camera.** That was the expected
shape (`fragBaked(true)` again) and it would have cost noise in the fragment
shader, which is the thing keeping the first-launch compile at 24 s. Instead the
seam is computed **on the CPU, per vertex, from samples that already exist**: a
discrete Laplacian of the ground radius over the four neighbours, positive where
the surface is CONCAVE. A hollow, a crack bottom, the inside corner at the foot
of a riser — which is where light coming out of the ground would be. It rides to
the fragment shader as one float attribute (`aSeam` -> `vSeam`) and multiplies
the baked emissive.

Free three times over: the samples are already fetched for the normals, it adds
no per-pixel noise, and it is derived from **the same height field the mesh and
collision read** — so the light is in a crack you can actually walk into, which
is binding rule 3 applied to the lighting.

Measured by that session: ground went **6,390 -> 19,482 distinct colours**,
sd 49.85 -> 63.06. Terraces with real depth, seams reading as light out of the
cracks rather than gold paint.

**OPEN — it reads as crumpled foil, and the cause is known.** A 4-neighbour
Laplacian at 1.9 m vertex spacing sits directly on the finest detail octave
(~3.5 m), i.e. it is differencing at Nyquist and returning noise as often as
shape. **The fix is a wider stencil, not a smaller gain** — turning the gain down
dims the seams without unmixing the noise from them. Not attempted; recorded
rather than rushed, which was the right call.

Also in that session, and separately verified by the mesh-normal probe: the
multi-agent `microRiser` values contradicted their own stated rule — ice, basalt
and caprock stand vertical, ash and regolith ramp — yet basalt (volcanic, 0.26)
had been given a SOFTER lip than ice (0.16). Corrected to volcanic 0.16 and
inferno 0.18; volcanic went 42.2% -> 54.5% flat with cliffs 19.4% -> 21.8%.
**These two edits were the prime suspect for the conformance failure and are
entirely innocent** — `microRiser` is read only by `groundR()`, never by
`heightNorm()`, exactly as the file says.

---

# 2026-08-05 · The crumpled foil: smooth BEFORE you differentiate

The seam term above worked and looked wrong — volcanic came back uniformly gold
and crinkled, like foil, instead of dark rock with light in its cracks. The
diagnosis in that session was right and is now confirmed by the fix:

**A 4-neighbour Laplacian at 1.9 m vertex spacing is differencing at the Nyquist
limit of the finest detail octave (~3.5 m).** At that spacing the stencil cannot
tell a crack from noise, so it reported roughly half the vertices as concave. On
a world whose emissive is strong, half the vertices boosted 2.3x IS the whole
frame — which is why volcanic blew out and glacier, whose emissive is subtle,
looked fine the whole time. The artefact was never in the seam idea; it was in
the sampling.

**Turning the gain down would not have worked, and this is the general point.**
Gain scales the real seams and the noise by the same factor. Only a low-pass
separates them, because they differ in FREQUENCY and not in amplitude.

So the seam now smooths and then differentiates: a 3x3 box over the radius,
computed once per chunk, then the same 1-ring Laplacian over the smoothed field.
That reaches two vertices out, which needs a SECOND ring of padding — the
existing one ring was sized for normals, which only ever need a central
difference.

Measured, before -> after on volcanic-night:
  - distinct colours   19,482 -> 16,849   (fewer, because the noise is gone)
  - standard deviation  63.06 -> 65.59    (higher, because real structure has
                                           more contrast than noise did)
  Those two moving in OPPOSITE directions is the signature of a successful
  low-pass, and is a better check than either number alone: denoising raises sd
  only if there was structure underneath to uncover.
  - chunk build         7.03 -> 7.76 ms   (+10.4%, one extra ring of groundR;
                                           the 7.03 reproduces the 7.1 ms this
                                           file already documented)
  - frame rate          165 -> 165 fps    (untouched; this is streaming latency)
  - glacier             visually unchanged; walkable histogram unchanged on all
                        six worlds, as it must be — the seam is a shading
                        attribute and touches no geometry
  - `terrain_conformance` still passes at 0.001-0.006 m, which is the check that
    mattered: re-indexing the padded grid from one ring to two is exactly the
    kind of off-by-one that silently moves the mesh off the height field.

**Watch for this trap if you touch the padding again.** `isSkirt` used to be
`(gi !== si) || (gj !== sj)` — true when the clamp had moved the index. With two
rings the sample index no longer equals the grid index even for interior
vertices, so that test would have called EVERY vertex a skirt and dropped the
whole chunk to the skirt radius. It is now an explicit test against the rendered
grid's edges.

**The picket fence is now the dominant artefact on terraced worlds, and it is
UNCHANGED — it was simply being hidden by the noise this removed.** Every riser
renders as a comb of vertical triangular teeth. Still open, still not the
skirts (ruled out by test, see above), and the standing best guess is still that
a heightfield on a grid cannot express a vertical wall: at w=0.16 the riser is
about 4 m across and the vertices are 1.9 m apart, so the step lands in one or
two quads and their diagonals zigzag it. **Do not start by re-tuning the
skirts.**

---

# 2026-08-05 · THE PICKET FENCE, SOLVED — and it was two artefacts, not one

"Terraced cliffs render as a comb of vertical bars with gaps" was one description
covering **two unrelated faults**, which is most of why it survived several
attempts. Separating them took one instrument each and then both fell in an hour.

## Fault 1 — the bars ARE the skirts, and the alibi was arithmetic

`--dbg=1` (write the mesh normal straight out) showed the bars as large SMOOTH
vertical surfaces with streaked normals: not terrain. Setting `skirt = 1` made
them vanish completely and exposed real holes underneath. So the skirts were
never innocent.

**Why they had been cleared, and it is worth knowing:** the ablation that
"proved" it was a change to skirt DEPTH which re-rendered byte-identically.

    const skirt = Math.max(edgeMetres * 0.35, (rHi - rLo) * 1.1, 1);

On a terraced world the relief term dominates that `max()`, so editing the
`0.35` changes nothing at all. **A byte-identical render is not an alibi when
the knob you turned was not connected** — it is the same shape of mistake as
`bisect_noise` exonerating a field its probes could not see.

The real defect is that the perimeter RANGE is the wrong quantity. What a skirt
must cover is not how much the edge varies, but how far the edge SAGS AWAY from
the chord a coarser neighbour draws past it — a centred second difference, which
is bounded by local CURVATURE rather than by the chunk's relief. Measured, mean
skirt depth over 60 chunks per world:

    crystal    71.9 ->  4.28 m      worst 1967.1 -> 1412.4 m
    shattered 437.1 -> 42.85 m      worst 4820.4 -> 3500.0 m
    volcanic   72.7 ->  3.37 m      worst  653.8 ->   10.5 m
    glacier    75.5 ->  3.75 m      worst  356.9 ->    8.2 m
    tundra     41.9 ->  1.62 m      worst   87.3 ->    5.0 m

crystal and shattered keep a deep worst case and that is CORRECT: their MACRO
terrace quantises elevation into steps 1.8-3.6 km tall, and no LOD scheme can
share a vertical wall, so at such a step the crack really is a kilometre. Which
is why the depth is now **per vertex** — a single scalar has to be sized for the
chunk's worst point, so one macro step anywhere put a kilometre-deep curtain
around the whole chunk. Per-vertex leaves the rest of the perimeter hugging the
ground.

**And then dilated along the edge, which is correctness and not polish.** The
pointwise value drops exactly the vertex that would fall through, and one
dropped vertex is a NEEDLE that plugs nothing — the gap spans the whole segment
either side of it. A max-filter (never an average: averaging lets the deep point
be diluted by its flat neighbours) turns each needle into a short local wall.

**A bug this introduced, caught by the measurement and not by the test suite:**
`sagRaw` reads +-2 samples, so computing it over `[1, P-2]` reads row `P` —
past the end of the array — which is `undefined`, which is NaN, which the
dilation then spreads into a real skirt vertex's POSITION. `terrain_conformance`
does NOT catch this, because it checks real vertices and a skirt vertex is not
one. The valid range is exactly `[R0, R1]`. It surfaced only because the
side-by-side measurement printed NaN.

## Fault 2 — the teeth are the SEAM ATTRIBUTE, not the geometry

The comb of triangular teeth along every riser survived the skirt fix untouched,
because it was never the same thing. Three measurements, and the third is the
one that named it:

  - **`--dbg=1`:** the mesh normal shows perfectly smooth terrace bands. No teeth.
  - **the ground profile:** walking 260 CPU samples at 0.45 m along the surface,
    worst step-to-step is **0.60 m** and the mean is 0.078 m. A 12 m riser
    descends as a ~45 degree ramp over ~12 m. **There is no vertical step in the
    height field at all** — the micro-terrace's smoothstep is doing its job.
  - **`--seam=0`** (new knob): the teeth vanish, AND so does every trace of the
    terraces — 12,531 -> 1,823 colours, a flat gold field. On volcanic at night
    the seam term is the ONLY thing rendering the terrain.

So the artefact is in the attribute. `seam` saturates to 1 within a vertex or
two of a riser foot, so the boundary of the saturated region falls INSIDE single
quads — and a quad splits along one diagonal, so the edge of the lit band
zigzags at 45 degrees and reads as teeth. **That is DECISIONS item 10's "a hard
step lands inside one quad and its diagonal zigzags", except it was never the
geometry doing it.** The note had the mechanism exactly right and the subject
wrong, which is why looking at skirts, micro-riser and macro-riser all failed.

Fixed by blurring the seam across the rendered vertex grid (3x3 box, one pass
over G^2 floats, no height evaluations), so the band edge is wider than a quad
and no diagonal can show through. volcanic close-up 12,531 -> 8,642 colours,
sd 66.31 -> 59.21 — both down, which is what removing a high-frequency artefact
looks like when the artefact was adding contrast rather than detail.

## What is verified

`terrain_conformance` passes on all four worlds (0.000-0.004 m); the walkable
histogram is unchanged on all six (the seam and the skirt are shading and
border geometry, neither touches the surface you stand on); `conformance` still
0.065% worst mean; `verify_oneclick` passes; 165 fps throughout.

**Still gold.** Both faults were structural. That the whole surface is one
colour family is the SEPARATE, still-open emissive problem — the baked crack
network is one 1.2 km texel across a ground view, so the seam modulates a
constant. See the next-steps list in CLAUDE.md.

---

# 2026-08-05 · YOU CAN LAND ON IT AND WALK — and why nobody could before

The owner: *"i'm just confused what i'm supposed to do, cause i've only been able
to just see the planet from orbit and i can zoom in, like should i be able to
walk on the planet?"*

**The answer was no, and the reason is worth writing down because it was not a
missing feature — it was a missing DOOR.** Every piece of standing on the ground
already worked: the camera clamps to the height field, the up-vector swings to
the local radial, the look direction blends to the horizon, the quadtree streams
in. All of it had been built, measured and screenshotted. What did not exist was
any way for a person holding a keyboard to ask for it. `orbit.js` bound drag,
wheel, `[ ]`, `n p`, arrows and space — and nothing that meant "go down there".

Every ground screenshot in `shots/` was taken by a harness that teleports itself
with `?dist=1.002&look=-0.15`. So a whole session could be spent fixing skirts
and seams on terrain the owner had never once seen, and was not able to see.
**A capability nobody can reach is not a capability, and a test that reaches it
by a private door does not prove otherwise.**

## What was added

- **`L` lands, `L` launches.** `state.mode` is what was asked for; `state.land`
  is where the camera actually is, animated 0..1 over 4 s and eased at both
  ends, so the two distances (orbit, measured from the datum; standing, measured
  from the GROUND plus eye height) blend rather than cut.
- **WASD walks, shift runs**, 6 m/s and 90 m/s, measured through real key events.
- **Drag turns your head on the ground** instead of sliding your body. Panning
  az/el in first person moves the whole body while the view appears to pan,
  which is the most disorienting thing a camera can do.
- **The prompt.** A line on screen naming every control, per mode, with live
  altitude. It includes the arrow keys because on the night side legibility
  depends entirely on where the star is, and a player who cannot find that key
  concludes the planet is broken rather than dark.

## Two things that had to be right

**Movement is in az/el, on the sphere.** A step north is `ds/R` of latitude and
a step east is `ds/(R cos el)` of longitude — the `cos` is the metric of the
parameterisation, and without it you accelerate as you approach the poles. There
is no flat-world patch to break down when you walk a long way.

**Looking and walking share one basis.** North and east come from the spherical
parameterisation, not from a cross product against world +Y. If the two derive
their axes differently they disagree, and W sends you off at an angle to the way
you are facing — a bug that reads as bad controls rather than bad maths.

`W`/`A`/`S`/`D` are read from `e.code`, not `e.key`: on the ground they are a
SHAPE on the keyboard, and `e.key` breaks them under any non-QWERTY layout.

## The test, and the bug it caught in itself

`tests/walk.mjs` presses `L`, waits out the descent, holds `W`, and measures the
great-circle distance actually travelled — against `VESPER.html` over `file://`,
because that is the artefact the owner double-clicks.

Its first run reported **"standing -7953 m above the ground"**. The camera was
in fact at exactly 2.00 m. The test had built a direction from `state.az/el` and
called `groundR()` on it directly, ignoring that the planet GROUP IS TILTED —
so it was asking about a different point on the world, and on a 310 km planet
with 23 km of relief the two answers differ by kilometres. **A test that
recomputes what the code already knows is testing its own copy of the maths.**
`window.__V.groundInfo()` now reports the game's own answer — altitude, terrain
visibility, chunks built, deepest depth — and the test asks that instead.

Verified on the real GPU, real keys, real file: terrain quadtree UP with 447
chunks at depth 16, eye height 2.00 m before AND after walking, 6.06 m/s walking
and 90.9 m/s running, turning changes heading and leaves az/el bit-identical,
launch returns to r/R 3.15, orbit still renders after the round trip, 0 console
errors.

---

# 2026-08-05 · B1e: the crack network, continued down to ground scale

The ground was one flat colour, and `--seam=0` had already proved why: turn the
seam term off and the terrain vanished entirely into featureless gold. The seam
says where the ground is CONCAVE, which is a property of the shape. Where a
world's cracks are is a property of the MATERIAL, it lives in the baked
emissive, and at ground range one baked texel is 1.2 km across a 2 km view — so
down there the bake is a constant and no exposure could ever fix it.

**The fix is the one the file had already predicted: evaluate the same network
live near the camera and fade into the bake with distance.** Same seed offset,
same width, same warp as the `c2` term in SURFACE_SRC, at a frequency chosen for
metres instead of kilometres (`crackFreq2 * 140`, ~15 m per cell on a 310 km
world — well clear of the float32 trouble that forces `detail.js` to
range-reduce).

Measured, volcanic close-up: **8,642 -> 21,358 colours, sd 59.21 -> 66.93.**
tundra night 399 -> 1,933. volcanic day 5,605 -> 18,191.

### Three decisions worth keeping

**NOISE goes in the TERRAIN program and nowhere else.** The orbital sphere still
reads three cubemaps and does no noise at all, so the frame rate and the 24 s
first-compile that were measured and approved are untouched by construction. Two
programs, one piece of art. Cost of the split, measured: **165 fps at far, mid
and close (unchanged), 51.6 vs 54.6 fps grazing** — about 5% at the one distance
where terrain fills the screen — and a **278 ms worst frame during the descent**,
which is the terrain program linking, hidden inside a 4 s drop.

**The distance fade IS the band-limit.** `gEps` is set from the fragment's own
pixel footprint (`vDist * uPixA / uRadius`), so `bandw()` takes the network to
zero exactly when it stops being resolvable, and that same weight is reused as
the blend back to the bake. One number does both jobs and cannot drift out of
step with itself the way a hand-tuned near/far pair would.

**It MULTIPLIES, and it is MONOTONE.** Multiply, because dead rock has emis ~= 0,
so the network can only appear where the art already put glow — the same
guarantee `glowPulse` relies on. Monotone (floor exactly 1.0), because the first
version used `mix(0.30, 2.40, f)`, which looked superb on volcanic and **blacked
crystal out** — 4,098 colours to 1,793, sd 17.3 to 4.1. A world whose glow is
broad rather than filamentary has f = 0 almost everywhere, and multiplying by
0.30 there does not carve a network into the light, it removes the light. The
general hazard is worse than one world: anything below 1.0 dims the NEAR field
while the far field keeps the bake, i.e. a dark disc that follows the camera.
Contrast now scales with `uFineGlow`, which is already the world's own answer to
"is the fine network a co-star or a texture" (ice 0.30, lava 1.0) — reading the
existing art direction instead of adding a second knob that could contradict it.

### Two link errors, and what caught them

`'sat' : function already has a body`, then `'sat' : no matching overloaded
function`. NOISE already opens with GLSL_HELPERS, so including both duplicates
them; and LIGHT_SRC calls `sat()`, so declaring them after it is too late. NOISE
therefore goes in the helpers SLOT, not appended lower down. **Neither error was
caught by the shader lint or by any orbital test** — only by `walk.mjs`, because
it is the only harness that puts the ground on a real driver. A shader variant
nothing renders is a shader variant nothing checks.

### A measurement trap, recorded because it nearly became a false regression

The ground gallery's numbers moved on every world when walking landed, and
crystal's went DOWN. It was not a regression and it was not variance — two
consecutive gallery runs are byte-identical. **The ground camera now has a
heading.** It used to look along a fixed `(0,1,0) x up` tangent; walking replaced
that with the north/east basis so that looking and walking agree. So the gallery
is pointing a different way and photographing different scenery. Ground-gallery
figures from before that change are not comparable with ones after it.

Ruled out first, each by ablation rather than argument: the network itself
(crystal is 1,774 with `--detail=0` and 2,179 with it on — the network HELPS
crystal), and the near-plane change from `d - radius` to `d - gR` (2,181 vs
2,179, i.e. nothing).

**Open, seen once, not reproduced:** one `terrain_conformance` run reported
"1 world FAILED"; three consecutive runs afterwards passed. The failure mode it
prints in that case is "no chunks generated", i.e. a streaming timeout rather
than a mesh disagreement. Recorded rather than dismissed — this file has already
paid once for calling a real signal a flake.

---

# 2026-08-05 · THE SKY: attempted, REVERTED — and it uncovered a blank image in the approved gallery

`refs/SURFACE.md` ranks the sky second only to terrain ("half the frame,
independent of terrain, currently a placeholder"), and it must come before the
wet-specular work for a concrete reason: **what a wet ground reflects is the
nebula.** So the sky was next. It is not done, and the attempt was reverted.

### What was tried
A rewrite of `nebula.js` aiming at the reference: ridged filaments instead of
billowing fBm; colour driven by DENSITY (violet fringes, cyan-white cores) rather
than by an unrelated second noise field; and stars rejection-sampled against the
cloud envelope so the densest star fields sit inside the gas, as they do in the
reference, instead of scattered evenly over it.

### Why it was reverted
It corrupted the orbital gallery. Frames came back as **TILES** — two rectangular
strips of real image with pure black everywhere else — with the planet itself cut
by the boundary. volcanic went from 46,205 pure-black pixels to 1,020,415.

Three explanations were tested and all three were wrong:
- *a stall in the star loop* (9,000 stars x up to 360k rejection tries x 24
  `Math.sin` hashes). Replaced with an integer hash and cut to 6,000 stars and
  N*5 tries. **The torn image was byte-identical afterwards.**
- *brightness* — tried at 3.4x, 11x and 4x. Identical tearing at every value.
- *a capture race* — retrying the capture up to five times with animation-frame
  settles changed nothing. **It is deterministic**, which is what rules a race out.

Reverting `nebula.js` restored the gallery exactly: volcanic back to 46,205
pure-black pixels and glacier to 384,359, matching the pre-change gallery to the
pixel. So the correlation is established and the mechanism is not. **Do not
re-attempt the sky by tuning constants** — the next step is to find why a heavier
backdrop program tears `toDataURL` deterministically, and the first cheap test is
whether the live canvas tears too or only the readback does (they disagreed once
already — see below).

### The thing worth keeping: an approved gallery image was 100% blank

`shots/12-gas-LEVIATHAN.png` is **1,440,000 of 1,440,000 pixels pure black**, byte
for byte identical across every run, and had been for as long as anyone had
looked. It sat in the twelve-world gallery the owner approved. A gas giant is the
slowest world in the set — it is analytic, it skips the bake — and a black square
in a contact sheet of planets on black backgrounds reads as "space", so nobody saw
it.

`tests/shoot.mjs` now measures every frame it writes and re-captures up to five
times, and reports anything still >92% pure black. It correctly flags
`gas/LEVIATHAN 100.0% black` today.

**And the first version of that guard did not work, for a reason worth recording:**
it measured the canvas with `drawImage()` into a 2D context, which came back
FINE while `toDataURL()` — the call whose output actually gets written to disk —
came back torn. Two reads of the same drawing buffer that do not agree. The guard
now decodes the PNG it is about to write and counts that, so what it reports is
exactly what lands on disk. A check that measures something adjacent to the
artefact is not a check.

**Open:** the gas giant renders blank. It survives five re-captures, so it is a
render failure and not a torn read. Nothing else in the gallery does it, and gas
is the only archetype that skips the bake.

---

# 2026-08-05 · The sky, strengthened the safe way — and the tearing is HALVED to one file

Owner on seeing the volcanic backdrop: *"the sky has red strokes looks beautiful,
but need more ofc"*. The strokes are the `fil` filament term, so that is what was
pushed, rather than re-attempting the rewrite that tore the gallery.

**What changed, all of it inside the existing shader:**
- a SECOND filament layer — a tighter, brighter core thread at a higher threshold
  and power, mixed toward white. Both layers are built from `m` and `m2`, the two
  noise fields the shader had already fetched. **No new octaves, no new
  primitives**, because a heavier backdrop program is the thing under suspicion.
- `uAmt` 0.24-0.50 -> 0.40-0.74. The old range was set when the backdrop's only
  job was to stop space being flat black; SURFACE.md item 4 says the sky is half
  the picture.
- `uContrast` 3.2-5.4 -> 2.5-4.2. The exponent decides how much of the sky has
  any gas in it at all, and the reference has cloud reaching the horizon on both
  sides rather than a few isolated patches.
- stars 3200 -> 6000.

**THE STAR RAISE WAS ALSO A CONTROLLED EXPERIMENT, and it paid.** The reverted
rewrite changed the dome shader AND the star count together, so either could have
caused the tiling. Raising only the count, against the known-good dome, does NOT
tear. **So the star loop is innocent and the cause is in the dome fragment
program.** That halves the search for whoever picks it up: the suspects are now
the `ridged4` call, the density-driven colour ramp, or simply the program's cost
— and the star loop, the integer hash and the rejection sampling are all cleared.

Verified: no torn frames on any of the 12 worlds, `verify_oneclick` passes,
`walk.mjs` passes, and frame rate is unmoved — 165/168/165 fps at far/mid/close
and 52.0 grazing, the same figures as before the sky work.

**Still short of the reference.** It reads as patches rather than threads, and the
star field is nowhere near the density of `refs/surface-01-ice-plain.png`. Getting
the rest almost certainly needs the filament structure the reverted attempt was
reaching for — which means the tearing has to be understood first, not tuned
around.

---

# 2026-08-05 · SKY LIGHT — the second of the reference's three light sources

Owner: *"i think its time to make the terrain for the planets no?"* The terrain
existed. **What did not exist was anything to light it**, and the way to find
that out was to stop reasoning and go and stand on the seven archetypes nobody
had ever looked at.

Ground shots at true night, before this change:

    verdant    22,140 colours   sd 43.30      shattered  5,058   sd 64.64
    oceanic    14,103           sd 21.95      glacier    3,212   sd 12.55
    desert      1,776           sd  6.39      fungal     1,733   sd 10.40
    ashen          56           sd  0.71   <-- a 1280x720 frame

**ashen was a black rectangle.** Its terrain was perfectly correct underneath —
the mesh, the terraces, the height field all fine — and completely invisible,
because a world whose rock does not glow had NO LIGHT SOURCE AT ALL on the night
side. The renderer had exactly one: the ground's own emissive. `refs/SURFACE.md`
opens by naming three — the glow, **the nebula**, and a scatter along the horizon
— and adds "*and none of them is a star*".

### What was added
A hemispheric sky term in `shadeSurf`:

    float skyF = 0.5 + 0.5*dot(s.nrm, n);
    outc += s.col * uSkyCol * (uSkyAmt * (0.35 + 0.65*night) * skyF * s.ao);

Three decisions in that one line:

**Hemispheric, not constant.** `dot(s.nrm, n)` is the surface normal against the
local UP, so ground facing the sky catches it and ground under a cliff does not.
That means this term MODELS THE TERRAIN at night. The existing flat `uAmbient`
could only raise everything uniformly, which is why turning it up had never been
the answer — it is the difference between visible and *shaped*.

**Coloured by the actual sky.** `uSkyCol` is read from `NEBULA_SETS[def.key]` —
the same table `nebula.js` draws the backdrop from — so the light on the ground
is the colour of the thing in the sky by construction, and cannot drift from it
as a second, independent art decision would. (Import direction: planetShader ->
nebula. nebula imports nothing from planetShader, so this is a leaf, not a cycle.)

**Weighted toward night (0.35 + 0.65*night).** Physically a sky does not switch
off in daylight, but the sun is orders of magnitude brighter when it is up, and
the twelve-world orbital gallery is an APPROVED ARTEFACT. Keeping a third of the
term in daylight fixes the night side while leaving what the owner signed off
essentially where it was.

### Measured

    ashen       56 -> 274 colours,  sd 0.71 -> 9.37   (13x the variation)
    desert   1,776 -> 2,744,        sd 6.39 -> 7.29
    glacier  3,212 -> 3,739,        sd 12.55 -> 23.50
    volcanic 24,040 -> 25,560       (unchanged in character: its own glow dominates)

ashen now reads as a rust-coloured ash plain under an orange sky with a visible
horizon. Its colour COUNT is still low and that is honest — it is a smooth,
near-monochrome surface with little relief — but sd rising 13x is the number that
says the terrain is being modelled rather than merely raised.

Cost: 166/165/168 fps at far/mid/close, unchanged. Grazing 52.0 -> 47.8 (-8%),
which is the one distance where terrain fills the screen. `terrain_conformance`,
`verify_oneclick` and `walk.mjs` all pass; the orbital gallery does not tear and
volcanic's night side now shows crack structure near the terminator instead of
going flat black.

**The lesson is the cheapest one in this file and it keeps recurring: seven
worlds had never been looked at, and one of them was a black rectangle.** No
amount of reasoning about terrain would have found that. `npm run shots` and
`tests/groundshot.mjs --arch=<name>` exist precisely so that "go and look" costs
a minute.

---

# 2026-08-05 · THINGS THAT STAND ON THE GROUND — and a world you can change

Owner: *"i asked for terrain, that means like trees, animals, materials"* — then,
on being asked whether these should be scenery or interactive: *"yes we'll need
to save the world cause of course we gotta be able to edit it! thats what makes
it fun!"*

So this is two systems: surface objects, and the edit layer that lets you take
one and have it stay taken.

## The rule that changed, on the owner's ruling

DESIGN.md's constraint table said **"Everything stays a pure function of a seed |
The save format IS the seed."** That was right about the danger and wrong about
the conclusion — a world you cannot change is a diorama. The rule is now:

    the world = f(seed) + a SPARSE DIFF of what you changed

and it is only safe because the diff is keyed by identities that are THEMSELVES
pure functions of the seed. An object's id is `face:cellI:cellJ:k` — a fixed
lattice cell, never a runtime index or spawn order. A seed alone still
reproduces the whole world, an empty diff is byte-identical to the old
behaviour, and a save is a short list of "and this one is gone".

## Placement is on a fixed lattice, and that is the load-bearing decision

The obvious implementation scatters N objects inside each chunk as it is built.
It is wrong in a way that only appears once you walk: chunk boundaries move with
the LOD, so the same ground is one big chunk from far away and four small ones up
close, and it would grow a different forest each time. Objects would swim as you
approached them — and the edit layer could not work at all, because an object's
identity would change with your distance from it.

So position is a pure function of a cell on a fixed grid in face-space, at a
fixed size in metres (CELL_M = 64), independent of depth.

**The bug that cost the most, and it is a good one.** A chunk originally claimed
the objects whose CELL CENTRES fell inside it — then jittered each position by up
to ±0.46 of a cell (29 m) so the lattice would not read as rows. The finest
chunks are about 7 m across. So an object claimed by a 7 m chunk almost always
landed outside it and was silently dropped by the containment test. The symptom:
objects existed at middle distance and thinned to NOTHING exactly where you were
standing. A chunk now claims an object by its FINAL position, scanning a cell
range padded by one cell either side — exactly one chunk claims each object, no
duplicates, no holes.

Found by measurement, not by reading: `gotoObject()` reports the round-trip error
of its own frame conversion, and it printed **0.000 m** — which cleared the
suspect I would otherwise have spent the session on (az/el are world-space, an
object's direction is in the planet's tilted frame, and that conversion HAD been
wrong once already) and pointed straight at placement instead.

## Three more traps, all caught by tests rather than by reading

**The shared geometry.** `geometryFor()` caches one geometry per object family —
correct for position/normal, since every spire on a world is the same cone. But
the per-instance attributes (colour, up-vector, shade) live ON the geometry, so
every chunk was overwriting the previous chunk's with a buffer sized for a
different instance count. The result was not a subtle mis-colour: the whole
ground frame came back holding **one distinct colour**, and `walk.mjs` caught it
as "the landed frame is not a scene". Geometry is now cloned per chunk — a cone
is ~20 vertices, so it costs nothing worth measuring.

**Taken objects must be marked, not spliced.** The array index IS the instance
index in the InstancedMesh, so removing an entry silently repoints every later
object at the wrong instance.

**Harvesting collapses the instance to zero scale rather than rebuilding the
chunk.** A rebuild is 7.8 ms of height-field evaluation, and it would stall the
one frame the player is looking at when they press the key. The edit is already
recorded, so the object simply is not there next time the chunk is built.

## Colour comes from the world, never from the roster

`objectKinds.js` gives a FIELD NAME (`colFrom: 'emisB'`), never hex. Retune a
world and its objects follow. Same argument that made the sky light read
NEBULA_SETS instead of carrying its own colour — a second, independent art
decision is a thing that drifts.

**And the sky light had to be desaturated because of it.** At the strength ashen
needed, the sky term repainted every world in its nebula's hue: verdant, a green
world, came back BRIGHT MAGENTA because its nebula core is `#b055ff`. Real
skylight is broad-band — a nebula tints it, it does not replace the albedo — so
uSkyCol is now mixed 62% toward its own luminance. Verdant's trees are green
again and its ground is lavender rather than magenta.

## Verified

`npm run harvest` — ten checks, all passing, on `VESPER.html` over `file://`
through real key events: 342 spires stand on the world, walk onto one (aim error
0.000 m), it is in reach at 2.0 m, **E** takes it, `findObject` says it is gone,
**the page is fully reloaded** and it is still gone, the rest of the world came
back (325 loaded), and **R** resets to the seed and regenerates it.

Also passing: `walk`, `verify_oneclick`, `terrain_conformance`, no torn gallery
frames. 167/169/167 fps at far/mid/close; grazing 46-50 fps.

**Known and not hidden:** objects stop at a hard radius (OBJ_EDGE_MAX 1100 m)
where leaves get coarser than that — visible as a line in the far distance on the
dense worlds; the shapes are cones and lumps, not modelled geometry; and nothing
collides with them yet, so you walk through a tree.

---

# 2026-08-05 · REGIONS — "patches of forest, different environments"

Owner on the first pass: *"there's only like stumps in the ground, should have
patches of forest, different environments all that stuff"*. Three separate faults
in that sentence, and they needed three different fixes.

## 1. "Stumps" — a cone IS a stump

Every family was a single cone. A cone has no trunk, no waist and no tiers, so
at any distance it reads as a lump of ground rather than as a thing growing out
of it. They are now **LatheGeometry profiles**: a 2D silhouette (radius against
height) spun about the vertical. A tiered conifer, a snapped snag, a capped
fungal stalk, a waisted crystal needle, a mushroom-capped hoodoo.

That buys a real outline for the same vertex budget and — the part that matters
— the same INSTANCED draw. Merging separate trunk and canopy meshes would have
needed BufferGeometryUtils and two draw calls per family.

## 2. "Patches" — cover has to reach ZERO, and at the right scale

`biome.js` adds two low-frequency fields, both pure functions of the seed:
COVER (how much stands here at all) and REGION (which of the world's two rosters
grows here).

**Cover must go all the way to zero.** A density that merely varies gives
uniform mush at varying thickness; an EDGE you can walk out of is the entire
difference between "patches of forest" and "some places have more trees".

**And the first frequency was wrong, by a factor of forty.** COVER_FREQ started
at 3.1 on the reasoning that "a biome must be bigger than the view or you cannot
be inside one" — which is true of a REGION and false of a STAND. Measured: at
60 km patches, **two of three landing sites had nothing within 1.1 km**, because
you are either deep inside a forest or deep inside a clearing and can see the
edge of neither. Cover now runs at ~5 km so the edge is IN VIEW from a standing
eye; regions stay at ~35 km so an environment is somewhere you travel through.

`tests/biomes.mjs` samples 20k points per world: ~25% bare, ~28% thin, ~47%
full, regions splitting 50/50. That distribution is the claim "patches of
forest" makes, and a single ground shot cannot test it — land in a clearing and
the forest looks broken, land in a stand and the clearings look absent.

## 3. "Different environments" — two rosters per world

Every world now has a second family (`alt`) and a pair of region names, designed
to contrast in silhouette AND palette rather than being a smaller copy: glacier's
open spire field against a serac maze that BLOCKS the horizon; crystal's 40 m
magenta needles against cyan scree you push through; verdant's closed canopy
against open heath with one pale tor every 130 m.

A chunk straddling a boundary therefore contains two kinds, and an InstancedMesh
draws one geometry — so chunks now build **one mesh per family present**, and
emissive moved from a per-world uniform to a per-instance attribute.

## The guard that came out of it

`tests/roster.mjs` (no GPU, no browser) validates all 26 rosters. It exists
because `surfaceObjects.js` resolves colour as `def.art[spec.colFrom] ||
def.art.mid` — **a typo in colFrom does not throw**. It silently paints that
object in the world's mid tone, which looks like a slightly-off art choice and
would survive every visual test. 26 hand-written field names is exactly the
population where one is wrong. It also asserts each alt's colFrom DIFFERS from
its primary's (or the two regions read as one palette) and that no roster's peak
density exceeds the lattice's 6-per-cell clip, which would silently truncate it.

## Verified, and one thing not

`roster`, `harvest`, `walk`, `verify_oneclick`, `terrain_conformance` all pass.

**OPEN: orbital frame rate is 130-135 fps, down from 165-168 before this
session's object work.** It is NOT the objects: ablating them completely
(OBJ_EDGE_MAX = 0) gives 133-134 fps, identical. Measured repeatedly and stable,
so it is not noise either. Unattributed, and it predates the object system —
somewhere between the sky work and here. Grazing is unchanged at 48-53.

**Also open, unchanged:** objects stop at a hard 1100 m radius; nothing collides
with them.

---

# 2026-08-05 · "just rocks every once in a while" — it was a LANDING bug

Owner, on the first regions build: *"now there's just rocks every once and a
while, this is terrible"*.

**The dominant cause was not density at all. Pressing L dropped you exactly
below the camera, and on most worlds that is THE SEA.** verdant is 59% ocean,
oceanic 79%, tundra 62%, fungal 63%. Nothing grows in water — `objectsForChunk`
correctly refuses to place anything below sea level — and `groundR` clamps to
sea level, so you stand ON the water looking at an empty plane, with whatever
was on the nearest shore reading as "rocks every once in a while".

Measured at the default landing site on verdant, before and after:

    1,923 objects loaded, nearest 2,586 m   ->   17,438 loaded, nearest 24 m

Landing now nudges to the nearest spot that is dry AND has cover — a widening
spiral in the planet's own frame, capped at ~35 km, which is far enough to clear
a coastline and near enough that you land where you were looking. Across five
archetypes the nearest object at the landing site is now 23-40 m.

**And I nearly diagnosed this wrong twice.** A CPU probe said 580 objects/km²
right at the landing point while the running game said nothing within 2.5 km —
because the probe used the world-space direction as if it were the planet's
frame. **That is the third time the tilt has done this** (walk.mjs thought the
camera was 8 km underground; gotoObject walked 150 m wide). Any code that turns
an az/el into a height-field query MUST go through `planet.group.worldToLocal`.

## The density work was still needed, and the warning was on the record

Two multipliers were also stacking against every roster: **50/50 regions** meant
half of every world was the second-choice object, and the sparse alts (heaths,
pavements, megablock fields) were exactly the ones a player was most likely to be
standing in; and **25% of the world was bare**. A nominal 60/km² heath was
delivering ~20/km² of actual standing objects — one rock every 220 m.

The roster's own design notes had flagged this before it shipped: *"verdant's
open heath at 60/km² and tundra's open fell at 55/km² may read as EMPTY rather
than as the other region... raise these to 90-120 before changing anything
else."* It was written down and shipped anyway. **A predicted failure that is
allowed through is worse than an unpredicted one, because the cost of listening
was zero.**

Fixed together: all 26 densities raised (nothing below 60/km²), bare 25% -> 5%,
and the region split biased to **74/26 in favour of the primary** — the world's
signature object should be what the world mostly IS, with the alt as a place you
walk into. Mean delivered density is now 158-466 objects/km² depending on world.

## Correction: the frame-rate regression was not real

The previous entry recorded an open item — "orbital fps 130-135, down from
165-168, unattributed". It is **contention, not a regression**: measured again
on a quiet machine it is 165.2 / 167.9 / 55.5 fps at mid / close / grazing, in
line with every earlier figure. The ablation that "ruled out the objects" was
correct and the baseline it was compared against was the polluted one — dozens of
headless Chrome instances had been launched in the preceding minutes. **A
performance number taken while the machine is busy is not a measurement.**


---

# 2026-08-05 Â· THE GROUND HAD NO MATERIAL, AND THE NEAR FIELD HAD NOTHING ON IT

The owner's verdict on the surface was that it reads as scattered props on
flat-coloured ground â€” *"all im seeing is basalt columns every 100 feet
apart... should feel like an actual planet right now feels like i dont even
KNOW!"* Three gaps were named: ground material at metre scale, clustering that
reads, and a size hierarchy. Standing in it, a fourth was more obvious than any
of them: **more than half of every frame was bare ground with nothing on it.**

## The diagnosis, and it was not a tuning failure

`fragBaked(true)` gave the ground metre-scale structure in `s.emis` and **in
nothing else**. `s.col` was one texel of the baked albedo cubemap â€” 1.2 km
across â€” so from a standing eye the whole visible world was a SINGLE colour,
shaded only by the angle it faced. The crack network could not help: it may only
redistribute emissive, and a world whose rock does not glow has none to
redistribute.

Measured before any change, near ground only (bottom 40% of frame):

| world | distinct colours | sd |
|---|---|---|
| volcanic | **24** | 1.46 |
| verdant | one flat teal | â€” |
| crystal | one flat blue | â€” |

Twenty-four colours. No exposure or lighting change could have reached that,
because the missing thing was never brightness â€” **the term did not exist.**

## What was added

**1. A ground material, terrain program only.** Bands of band-limited noise
modulating albedo, plus a slope term â€” `n` IS the local radial, so
`dot(meshNormal, n)` is the true ground angle for free, and fines settling on
the flats against bare rock on the steeps is what ties the material to the SHAPE
rather than laying fog over it. `gndAmt`/`gndScale`/`gndDust` per archetype.

It is mean-preserving by construction (`mix(lo,hi,m)/mix(lo,hi,0.5)`). The first
version was `mix(0.45, 1.28, m)` read straight, whose midpoint is 0.86, so
switching texture ON made every world 14% darker as a side effect â€” volcanic's
near-ground mean fell 17.4 to 14.5. It also carries a headroom guard: a world
already near the 0.75-linear albedo cap gets the darkening half only, so
archetypes.js rule 1 is enforced in code instead of trusted to every future edit.

**Nothing here can touch the approved orbital look.** `fragBaked(false)` does not
compile any of it â€” verified by perf: far/mid/close sit at 167/165/166 fps,
exactly the approved baseline.

**2. A clutter tier (`scatter`), and it is most of the screen.** Its rules differ
from the stand roster deliberately: it IGNORES `cover` (a clearing is not a bare
plane, it is ground with stones on it, and the clearings were the emptiest
places), it IGNORES `region` (one roster per world â€” ties the two halves
together AND keeps it to one extra InstancedMesh per chunk), and it stops at
140 m instead of 1100, which it can afford because the objects are twenty times
smaller. Own lattice at 12 m, own id namespace.

**3. A size ladder at both ends.** `markChance`/`markScale` promote about 1 in 55
objects PAST hMax. hMax is a hard ceiling, so without this the tallest thing on a
world is only ~3x the typical one â€” a bounded distribution at uniform density is
exactly what "every 100 feet apart" looks like from standing height.

**4. Clumping that is not blocky.** The clump term was `hash3(f, ci>>3, cj>>3)`
read straight: one value per 512 m block with a HARD edge at every boundary. It
is now the same hash bilinearly blended with a smoothstep fade, so a thicket has
a middle and an edge. Four integer hashes, no noise call â€” as cheap as what it
replaced, which matters because it runs inside `buildChunk`.

## THE MULTIPLIERS ON A FOLDED COORDINATE MUST BE WHOLE NUMBERS

Found while writing the comment explaining the fold, which is the only reason it
was found at all â€” it renders as a subtle line, not as a failure.

`fold867` works because snoise has period 867, so two points either side of a
fold boundary sit 867 apart and evaluate identically. **Scale that folded
coordinate by a non-integer and the property is destroyed.** At x3.7 an 867 jump
becomes 3208.9, which is not a whole number of periods, so the band is
discontinuous across every fold â€” a hard line across the ground roughly every
15 km on a 600 km world. The first version used 3.7, 13.0 and 7.3; two of the
three were broken. They are 4, 13 and 7 now. Offsets are added AFTER the scaling,
which is safe (a constant shifts both sides alike) and decorrelates bands that
would otherwise share a lattice.

The coarse band is computed from the UNFOLDED direction instead: at ~500 m its
coordinate is only in the hundreds, where float32 is exact and folding would buy
nothing while breaking it.

## The coarse band exists because the MIDDLE DISTANCE was cardboard

Every other band dies by about 5 km and the bake it hands back to has 1.2 km
texels, so between roughly 1 and 5 km the ground had structure at NO scale. A
hill a kilometre off was a flat cut-out of colour while the ground at your feet
was fully textured, and the step between them read as a ring around the camera.

## OBJECTS WERE EXEMPT FROM THE EXPOSURE RULE BY ACCIDENT

Desert's hoodoos rendered as flat white parasols with no modelling in them.
**It was not a wrong colour and no palette edit would have fixed it.** Proved by
ablation (`--sweep=uSkyAmt:0,0.5,1.5`): with the sky term off, the same hoodoos
come back the authored tan (#c1a171).

They were OVEREXPOSED, and ACES rolls blown colour off toward white. archetypes.js
sizes every albedo against a lit multiplier peaking near 1.22 â€” but objects take
the sky term at full strength and an upward-facing cap gets `skyF` = 1, putting
their total near **1.6**. Scaling the object sky term by 0.55 brings it to ~1.38,
under the line, and is the more physical number anyway: flat ground sees the whole
hemisphere, a convex object standing on it shades itself.

Also added: contact occlusion up every object (`vY`, normalised height, which is
free because every geometry in GEOM is authored 0..1). Objects met the terrain at
a hard edge with no darkening at all, and the eye reads that as a cut-out. A
dimmer flat shape is still a flat shape â€” what was missing was the shadow.

## The harness was shooting from INSIDE the object

`standshot.mjs` without `--here` calls `gotoObject()`, which stands you ON the
thing â€” i.e. inside its geometry, seeing through the backfaces. The first
volcanic shot came back an empty frame while reporting an object 2.0 m away.
**An empty frame and a broken renderer look identical from outside.** Use
`--here` to judge; the walk-to-object mode is for the harvest path.

## Instruments changed

- **`standshot.mjs` gained `--set` and `--sweep`,** and it is not a duplicate of
  `ablate.mjs`: ablate renders the ORBITAL sphere, and half the ground's material
  now lives in the terrain program, which the orbital sphere does not compile.
  A term that only exists in `fragBaked(true)` is invisible to every other
  instrument here. It also prints distinct colours / mean / sd of the bottom 40%
  of frame â€” counting the whole frame dilutes the ground with sky, which is how a
  change can look like it did something when it did not. It asserts the context
  is alive and that the terrain still SHARES the world uniform object before
  printing a number.
- **`roster.mjs` now validates all three tiers** (39 rosters, was 26) with
  per-tier bounds, importing `CELL_M`/`SCAT_CELL_M` rather than copying them.
  It immediately caught fungal's clutter at 8.3/cell against a clip of 8 â€” the
  densest thickets were being silently truncated and the roster was overstating
  itself.
- **`npm run walk` did not exist.** CLAUDE.md documented it; package.json did not
  have it. Added, with `npm run stand`.

## Verified

- `npm run harvest` â€” 10/10. The object it took was `s1:7712:-11176:0`: a
  SCATTER object, so clutter is harvestable and survives a page reload. The id
  prefix works.
- `npm run walk` â€” 16/16, real keys on `VESPER.html` over `file://`.
- `npm run verify` â€” 11/11, the double-click path.
- `npm run roster` â€” 39 rosters consistent.
- `perf.mjs` â€” far/mid/close **167/165/166 fps**, the approved baseline.

## The grazing figure is PRE-EXISTING, and that was proved rather than argued

Grazing reads 32.9â€“34.1 fps across three runs against the 47.8 written in
CLAUDE.md. The reasoning said it could not be this work (the ground material
band-limits to zero at that altitude, and no chunk up there is fine enough to
carry clutter) â€” but reasoning is what trap 14 warns about, so it was ablated:
**with `gndAmt` set to 0 and rebuilt, grazing is 34.3 fps.** Identical. This
work costs nothing there and the gap predates it. Cause still unknown; it is
NOT the ground material.

## Costs that ARE this work

- **The descent link stall went 278 ms to 585 ms.** The terrain program is the
  one carrying the noise library and it now carries five more calls. One-off per
  machine, inside the 4 s descent; `walk.mjs` still passes "no landing freeze".
- Ground-level steady-state fps is **not measured by any existing harness** â€”
  perf.mjs's four distances are all above the terrain, and `closeup.mjs` uses
  `?pdb=1`, whose fps the docs say not to trust. That is a real gap.

## Still open

- **Volcanic's immediate foreground is still bare.** Ember clinker (emisA at 0.42
  emissive) made the mid-distance read â€” it is the only thing in that palette
  visible against it, since `lo` #332720 is volcanic's BRIGHTEST field and `peak`
  is #070505 â€” but at 20,000/km2 the nearest few metres are statistically empty,
  and the world sits at mean 14.2 where glacier is 116.
- **Desert's hoodoo profile reads as a parasol** now that the exposure is right.
  That is the authored silhouette (caprock mushroom on a narrow neck), not a bug,
  but it is worth a second look.
- **8 of 14 archetypes still have not been looked at** at ground level.



---

# 2026-08-05 (later) Â· THE OWNER LOOKED AT IT IN DAYLIGHT AND IT FELL APART

His words: *"how are you gonna tell me this is good terrain, this stuff is
disgusting twin... look at how no mans sky or space engineers makes their
planets."* He was right, and the frame he sent showed three things at once.

**I had judged every world at NIGHT.** `standshot` pins `time=9` and `spin=0`, so
the sun sits at a fixed angle and the LANDING SITE decides day or night â€”
`--time` does nothing. Every ground judgement in this project, mine and the ones
before it, was made on the night side, where emissive carries the picture and
flat unlit material hides. `--az/--el` and a seed sweep now reach the lit side;
mean frame brightness tells you which you got (volcanic VESPER-1 lands at mean
10, VESPER-4 at mean 97).

## What was actually wrong in his frame

1. **A field of red d20s.** I had given every lava clinker `emisA` at 0.42
   emissive. Two mistakes compounding: a bare `IcosahedronGeometry(0.5, 0)` is
   instantly readable as a DIE, and 100% of them glowing violates archetypes.js
   rule 5 in the file's own words â€” *"30% ablaze and 70% dead reads as fire;
   100% evenly cracked reads as texture"*. Now: one subdivision with a
   position-hashed vertex displacement (hashed by POSITION, not index, or the
   non-indexed geometry splits open at every shared corner), and `emisChance`
   0.11 with a separate `colGlowFrom`, so 89% are dead basalt rubble.
2. **Columns as black cardboard.** `colFrom: 'hi'` â€” and on volcanic value goes
   DOWN with elevation, so `hi` (#110d0b) is nearly black. A black object in
   daylight has no modelling in it, only a silhouette. Moved to `lo` (#332720).
3. **Ground with no relief in its lighting.** The material tinted albedo and
   nothing else, so the shading normal was still the smooth mesh normal and
   every light in the scene fell on polished plastic. That is the whole of the
   No Man's Sky comparison: the eye reads material from how light MOVES across
   a surface, not from its colour.

## THE BUMP WAS DEAD FOR A WEEK OF MY OWN REASONING â€” TWO SEPARATE BUGS

### Bug 1: dFdx inside NON-UNIFORM control flow is undefined

The bump lived inside `if(wg > 0.001)`, an early-out I had added for speed.
`wg` comes from gEps comes from vDist â€” it varies per pixel, so that branch is
non-uniform, and derivatives are computed across a 2x2 quad. If part of the quad
took the other branch there is nothing to difference against, and the driver
returned ZERO. No error, no warning, a completely plausible picture.

Sweeping `uGndBump` from 0 to 4.0 moved the near field from 4451 to 4725
distinct colours â€” i.e. not at all. **The dune-like ripples I had been admiring
and then "improving" were the terrain mesh's own detail the whole time.** I
changed the bump's weighting once and declared it better on a byte-identical
frame, which is trap 14 exactly.

What caught it was the probe, trap 17: `uTerrDbg=1` writes the shading normal
straight out, and it read 5,893 colours at bump 0 against **97,820** at bump 4.
The normal was fine; the LIT frame did not respond because volcanic's ground
light is its own emissive, not sun times albedo times normal. Judge a normal
change on an albedo-lit world (desert), never on a glowing one.

### Bug 2: screen-space derivatives DO NOT WORK at planetary coordinate scale

With the branch fixed the bump appeared â€” as a salt-and-pepper carpet across the
whole near field.

The noise coordinate is up to 433 after range reduction, so one float32 ulp of
it is ~3e-5. Standing two metres away, one screen pixel spans under a
millimetre, which is about **three ulps**. The per-pixel difference is therefore
not a gradient at all, it is a quantisation staircase jumping between zero and
three ulps, and differentiating it returns noise. No band limit can help: the
fault is in the COORDINATE, not the field.

I first tried to fix it by weighting the bump toward the FINEST bands, reasoning
that rock is sharp. That is right about rock and backwards about arithmetic â€”
differentiation amplifies high frequency, so weighting toward the bands nearest
Nyquist returns nothing but noise. Trap 16, "smooth before you differentiate",
already recorded for the Laplacian in terrain.js, applies verbatim.

**The fix is to choose the differentiation distance yourself.** The gradient is
now two extra noise samples at a fixed 0.55 m in a branchless orthonormal
tangent basis (Duff et al. â€” a fixed reference vector goes singular at the poles
and swirls the relief there). 0.55 m is about a thousand ulps, far above the
floor, and it does not change with camera distance. Fizz gone.

## Verified after all of it

`walk` 16/16 Â· `harvest` 10/10 Â· `verify` 11/11 Â· `roster` 39 consistent.

## The cost that keeps growing, and should be watched

**The descent link stall is now 873 ms** â€” it was 278 before this session's work
and 585 midway. The terrain program is the one carrying the noise library, and
every band added lengthens its one-off compile. It is inside the 4 s descent and
`walk.mjs` still passes "no landing freeze", but the trend is the wrong way and
there is not much headroom left.

## Still not good, and stated plainly

The ground has relief now and the objects are no longer dice, but it reads SOFT
and blobby rather than like rock, and the mid-distance mottle reads as
camouflage. That is art, not a bug, and it should be tuned with the owner
looking rather than guessed at a fourth time.



---

# 2026-08-05 (later still) Â· "MAYBE IM UNDERWATER? BUT I DONT SEE ANY WATER"

The owner asked whether he was underwater and could not tell. He was right to
suspect it, and the reason he could not tell is that **there is nothing at all
to tell him.**

## The finding

`groundR()` clamps at sea level â€” heightField.js says so in as many words:
*"this clamps to sea level, so a naive spawn using it stands the player ON THE
OCEAN as if it were solid."* So standing over an ocean puts the camera on a
perfectly flat plane at sea level, drawn with the baked sea colour as if it were
ordinary ground. No surface, no specular, no shore, no waves, no depth cue.
Altitude cannot reveal it either: you are always exactly eye height above
SOMETHING.

Measured: **verdant, VESPER-4, landed the player on 5,593 m of open sea with
zero objects loaded.** The frame is a flat blue field to a dead-straight
horizon. That is the picture he was describing.

These worlds are 59-79% ocean (verdant 59, oceanic 79, tundra 62), so this is
not a corner case â€” it is a large fraction of all landings.

## Two bugs in the landing search, both of which stranded you there

`findLandingSite()` was written to spiral out to dry ground with cover. It did
not work, for two independent reasons, and either alone was enough:

1. **Dry but BARE ground scored exactly 0, and the acceptance test is
   `best > 0`.** So the search would find perfectly good dry land, score it
   zero, reject it, and leave the player on the ocean. The score now has a
   floor of 0.05 for any dry sample: ANY dry ground beats ANY sea.
2. **The search gave up after 6 rings â€” about 43 km** â€” and when every sample
   came back sea it simply left `state.az/el` untouched, i.e. standing on the
   water. It now runs 22 rings (roughly a quarter of the planet). The early-out
   at cover > 0.6 means an ordinary landing still stops in the first ring or two
   and costs nothing.

After: six landings across verdant / oceanic / tundra on two seeds each are all
on dry land. verdant VESPER-4 went from 0 objects on open sea to 17,543 objects
and 14,219 distinct colours in the near ground.

## The readout, because he should never have to ask this again

`groundInfo()` now returns `onWater` and `seaDepth`, computed by asking the
height field directly (`heightNorm <= seaNorm`) rather than from altitude, which
cannot know. The in-game HUD shows **"ON WATER â€” N m of sea below you"** in the
warning colour, or "on dry land". `standshot.mjs` prints the same on every shot.

## WHAT IS STILL NOT BUILT: the ocean

Landing no longer strands you, but you can still WALK into the sea, and when you
do it is an invisible flat plane. **There is no water surface in this renderer
at ground level.** That is a missing feature, not a bug, and it is deliberately
not being improvised: DESIGN.md requires sea level to stay ANIMATABLE (tsunamis)
and warns that it is baked into the surface cubemap today, so the right shape is
a separate water surface at sea-level radius that is never baked â€” which also
satisfies that constraint instead of fighting it.

`walk` 16/16 Â· `harvest` 10/10 after the change.



---

# 2026-08-05 Â· THE ART DIRECTION IS SETTLED, AND OBJECTS GOT A SECOND COLOUR

The owner was asked which of the two games he had named VESPER actually is â€”
they are opposite looks â€” and chose **No Man's Sky: stylised. Saturated, high
contrast, bold readable shapes, alien forms, sometimes glowing. A poster, not a
photograph.** Priority: rocks and trees first, then ground material, then the
ocean. Write that down; it decides every art argument from here.

## The one thing wrong with every object: they were ONE FLAT COLOUR

A tree was a green cone. A boulder was a grey lump. Nothing in a stylised look
survives that â€” the entire read of an NMS prop is that its PARTS are different
colours. So objects now carry a second palette colour, `colTipFrom`, mixed up
the object by normalised height (`vY`, which every geometry here already has
because they are all authored 0..1 tall).

It costs one instance attribute and one mix, and it pays for itself immediately:

- verdant: dark trunk under a green canopy â€” a tree instead of a cone
- tundra: dark conifer with a pale snow crown
- desert: **a hoodoo is a rust column with a pale caprock on top** â€” that is what
  the word means, and painting the whole thing from `peak` is why they had been
  coming back as white parasols. Only the cap is pale now, which fixes the look
  and the exposure in one move.
- glacier / crystal / fungal: dark base, glowing tip â€” and because the emissive
  term multiplies the SAME two-tone colour, the glow concentrates toward the tip
  instead of lighting the whole spire evenly. That is what refs/SURFACE.md means
  by light that is INSIDE the ice.

Also: a view-space **rim light** in the sky colour, because objects are painted
from the same palette as the ground they stand on (deliberately) and therefore
vanish into it; **per-instance width** varying 0.72-1.36x independently of
height, because scaling one cone up and down leaves it the same SHAPE, which was
most of why a stand read as stamped copies; and lathe segments 7 -> 10, since
these are instanced so the count is paid once per family for the whole world and
at 7 a near tree is visibly a heptagon.

## CAP THE LIGHTING SUM, NOT THE ALBEDO

Third exposure bug on objects in one day, and the first two fixes were both too
narrow. Tundra's new snow crowns bloomed into glowing white cones.

Clamping the ALBEDO was tried first. It did nothing â€” 4516 colours before,
4506 after â€” and that null result is what proved the fault was downstream:
albedo was never what crossed the line. Sun 1.06 + ambient + sky + rim came to
about **1.9**, so even a legal 0.72 albedo landed far over the 0.95 bloom
threshold and ACES rolled it to white.

**All reflected light is now gathered first and capped at 0.92; emissive is
added after and is the only thing allowed over the line.** That is precisely
what archetypes.js rule 1 has always said, and objects had simply never obeyed
it. Capping the sum closes every route at once â€” the sky term, the tip colour,
the rim, a bright per-object shade, and whatever the next roster invents.
Scaled uniformly, never per channel, or a saturated colour gets pulled toward
grey: the same failure wearing different clothes.

`roster.mjs` now also validates `colTipFrom` and `colGlowFrom`, which fall back
to the base colour on a typo and would otherwise render as a one-tone object â€”
i.e. exactly the bug this file exists to catch, in a new field.

## Verified

`harvest` 10/10 Â· `verify` 11/11 Â· `roster` 39 consistent Â· `walk` passes.

**`walk` is now MARGINAL and that is worth watching**: its landed frame scores
951 colours at mean 15.4 and the "is this a scene or a constant" check failed
once and passed on re-run. The cause is the landing fix from earlier today â€”
walk lands somewhere different now, and volcanic's night side is dark enough to
sit on the threshold. Not a false alarm: it is telling us that world is nearly
unlit.

## THE PERF NUMBERS IN THIS SESSION'S LAST RUNS ARE NOT USABLE

far/mid/close read 105-120 fps against the 167/165/166 recorded earlier, and
grazing 24 against 34. Before blaming the work: the drop is UNIFORM across all
four distances **including the orbital sphere, whose shader contains none of
this code** â€” fragBaked(false) cannot see any of it. `nvidia-smi` showed the GPU
at 78% utilisation and thermally throttled to 2715 of 3105 MHz, with 28 chrome
processes alive â€” **all of them the owner's own interactive browser, none
headless**, so this is contention on a busy machine and not a leak from the test
harness either. Re-measure on an idle machine before trusting any of it.

The descent link stall is now **1370 ms** (278 at the start of the day). Same
caveat applies â€” it is measured on the same busy machine â€” but the trend across
the day is real and the terrain program is the one carrying the noise.


---

# 2026-08-23 · THE GROUND HAD NO DAY, AND FOUR WORLDS WERE BLACK AT NOON

The session opened on the owner's item 0 — *"volcanic's immediate foreground is
still bare; decide whether that darkness is the art or a bug before tuning
density again."* It is neither, and the answer took three separate measurements
because three separate things were wrong.

## It is NOT density, and that was settled first

`tests/nearfield.mjs` is new: it walks the terrain's own chunk lists after a
landing and reports, per distance band, how many objects the game has BUILT and
what density that implies. A picture cannot tell "there is nothing there" from
"there is something there the same colour as the ground", and those need
opposite fixes.

volcanic VESPER-1 delivers **20,690–25,465 items/km² in every band from 0 m to
300 m** against a roster asking for 20,000. Placement is exactly right. The near
field looks empty because 20,000/km² is one item per 50 m², so there are 2
objects within 5 m and 25 within 20 m — small ABSOLUTE numbers are what correct
density looks like up close.

The probe was wrong first, in a way worth recording: it multiplied each object's
unit `dir` by the radius and measured a 3-D chord to the camera. The camera sits
at `groundR + eye`, and on a world with 23 km of relief that radial gap is
kilometres, so **every object reported as "700 m+ away" wherever you stood**.
Distance along the surface is the angle between the two directions times the
radius.

## THE STAR WAS DOING ALMOST NOTHING, ON EVERY WORLD

Nobody had ever measured whether the ground CHANGES when the sun comes up.
`tests/daynight.mjs` puts the star 35° above the player's own horizon and then
12° below it, at the same landing site in the same world, and reports the
near-ground mean both ways — with the sky term at its authored value and again
at zero.

| | sky ON day/night | sky OFF day/night | the star is worth |
|---|---|---|---|
| glacier | 1.13 | **4.76** | 61.7 |
| toxic | 0.99 | **9.05** | 33.8 |
| tundra | 0.79 | **8.42** | 23.0 |
| desert | 1.23 | 4.48 | 32.0 |
| volcanic | **0.59** | 1.39 | 1.5 |

**Not one of twelve worlds reached 1.25 with the sky term on, and seven were
BRIGHTER AT NIGHT than in daylight.** With it off, the star is worth 3.0–9.1×.
So the star was never weak — it was drowned.

The arithmetic is not subtle and it was sitting in the file. The star's entire
contribution is capped at `s.col * 0.92`; the sky term ran
`s.col * uSkyCol * uSkyAmt * (0.35 + 0.65*night)` with `uSkyAmt` 3.4, i.e. 1.19
in daylight and **3.40** at night. The comment directly above it states the
opposite intent in as many words: *"the sun is three orders of magnitude
brighter when it is up, so the day side keeps a third of it."* The code and the
comment disagreed and the code won, for as long as the term has existed.

`0.65` was a literal, and **a literal cannot be swept**, which is most of why it
survived. It is `uSkyNight` now. Swept over all thirteen worlds against two
numbers at once — the ratio AND the night colour count, because the constraint
on lowering it is READABILITY (the term exists because ashen once came back as
56 distinct colours) and a value that restores day by making night black has
only moved which half of the clock is broken.

**0.22.** glacier 1.13 → 1.59, oceanic 1.11 → 1.55, verdant 1.11 → 1.57, toxic
0.99 → 1.51, desert 1.23 → 1.76 — and it costs almost nothing in structure:
glacier's night colours go 1422 → 1331, oceanic's 6683 → 4737 against a DAY
count of 4194, inferno's actually rise. That is the term behaving as what it is,
a smooth hemispheric multiplier, which adds brightness and not detail, so taking
brightness back does not take detail with it. The glow worlds deliberately stay
near parity (volcanic 0.88, inferno 0.86, fungal 0.85) because on those the
night light is the EMISSIVE term and a lava world genuinely is as bright at
night — the art law working, not a value that missed.

## AND IT IS RAMPED BY ALTITUDE, BECAUSE "THE DAY SIDE IS UNTOUCHED" IS NOT ENOUGH

Holding the day weight at exactly `0.35*uSkyAmt` sounds like it makes the
approved orbital gallery safe by construction. It does not: the night ramp is
`1.0 - sat(geoNdl*2.4)`, which stays non-zero out to a sun elevation of 24.6°,
and on a sphere seen from outside that band is most of the limb. So orbit keeps
0.65 (`uSkyNightOrbit`) and the ground gets 0.22, crossfaded on the same
`uCamAlt/(uRadius*0.03)` ramp `uEmisNear` already uses — the same fix, in the
same function, for the same reason: a quantity authored for one viewing distance
being asked to work at another.

## THE INSTRUMENT WAS THE BROKEN THING, FOR THE THIRD TIME IN THIS PROJECT

The claim "orbit is untouched" was first tested with `ablate.mjs`, which
reported **67% of glacier/HALCYON's pixels moved, mean delta 26, worst 203** — a
confident, stable, wrong number that was written into a code comment as fact
before it was controlled.

ablate calls `clearUniforms() -> build() -> setWorld()` between variants, which
re-rolls and RE-BAKES the world, then captures at `frames >= 3`. The control
settles it: **rendering uSkyNight=0.22 twice through ablate differs in 91.98% of
pixels** — more than the "effect". An instrument noisier than its signal will
report the signal anyway.

`tests/uniform_ab.mjs` is the replacement: one page, no rebuild, and "settled"
means two consecutive captures are byte-identical strings rather than a guessed
delay. Through it, the same 0.65 → 0.22 move from orbit changes **one pixel in
490,000, by 1/255**, and the same-value control is exactly identical. `uGndLift`
below is likewise byte-identical from orbit.

*Before believing a number, render the SAME input twice and diff it.* That cost
nothing and would have caught this before the comment was written.

## FOUR WORLDS WERE BLACK AT NOON, AND NOTHING WAS BROKEN

With day and night finally separable, `tests/groundgallery.mjs` (new: all
thirteen worlds, player eye height, day and night, one contact sheet at
`shots/ground/index.html`) showed four worlds unviewable in BROAD DAYLIGHT:
**barren 66 colours at mean 4.6, shattered 177 at 2.9, ashen 657 at 4.2,
volcanic 102 at 10.6** — against desert 4,947 at 52 and glacier 2,106 at 96.
barren was a black rectangle with two grey pebbles in it.

The terrain probe (`uTerrDbg`, which has existed all along) took four shots to
close it out. Mode 4, `sat(dot(s.nrm, uSun))`: mean **116/255, sd 84** — the
lambert term is healthy and the geometry faces the star. Mode 5, sun shadow: a
flat **250** — nothing is shadowed. Mode 6, the baked albedo: mean **10.9**.

That is the answer, and it is not a bug. barren's base colour is `#37373d`, a
mid grey at 55/255 in sRGB, which is **0.040 in linear light**; times a lambert
of 0.46 that is 0.018, and 0.018 through ACES is black. It works from orbit
because from orbit the disc is a bright object against empty space with bloom
around it, judged as a shape. On the ground it is the whole frame with nothing
to read it against. The four worlds affected are exactly the four with the
darkest palettes — which is what archetypes.js rule 1 asks for on purpose.

**Rule 1 is an ORBITAL rule that had never been given a ground-side counterpart.**

`gndLift` is that counterpart: an exponent applied to the ground's LUMINANCE
only, ramped out with altitude. Three things about its shape are load-bearing.
It scales the colour by `pow(L,g)/L` rather than running `pow()` per channel,
because per-channel quietly desaturates every dark colour toward grey — and on
worlds selected for being dark, that is the entire palette. It is a curve rather
than a multiplier. And it is **per-archetype, in archetypes.js, because it is a
per-world art decision**: a single global exponent that lifts 0.040 four times
over also lifts a mid-tone twice, which measured as glacier 90.6 → 137.0 and
desert 59.4 → 96.1 — blowing out the worlds the owner has already approved.
Set on four worlds only. volcanic 0.70 (10.6 → 41.3 mean, 102 → 629 colours),
ashen 0.55 (4.2 → 48.7, 460 → 1330), barren 0.55, shattered 0.50. Deliberately
NOT applied to `s.emis`: rule 1 trades a dark base against a bright glow, and
lifting both would cancel the trade.

## LANDING NOW LOOKS FOR SOMEWHERE THE WORLD IS ALIVE

Law 3 is that the art CONCENTRATES — *"30% ablaze and 70% dead reads as fire."*
So on a world built to that law most of the surface is deliberately dead black
rock, and `findLandingSite` scored only `0.05 + cover`: dry, with something
growing. Pressing L was a coin flip, and volcanic VESPER-1 landed in the dead
70%. The foreground WAS bare. It was also correct.

`def.features` is CPU data rolled from the seed, carrying a unit position, an
angular radius and a `glow`, so "is anything happening here" costs one dot
product per feature — no new noise, no GPU readback.

**It runs as a second pass, not as a term in `score()`, and that is the design.**
Folding it in is tidier and wrong twice: the early-out ("where you are is fine")
fires on a covered-but-dead spot before liveness is ever consulted, and on a
world whose features barely glow every landing would run all 22 rings and then
drag the player a quarter of the way round the planet chasing a number that was
never there. Phase 1's dry-land guarantee is untouched byte for byte; phase 2
re-picks within six rings (~43 km), never trading dry for sea or cover for bare.

Connected, and proved so rather than assumed — `window.__V.landInfo()` reports
what the search did, because a landing change that finds nothing better renders
byte-identically to no landing change at all (trap 14). **verdant's landing went
from 2,401 to 18,227 near-ground colours**; oceanic 4,194 → 6,140. volcanic
barely moved (102 → 102): there is nothing better within 43 km of where it puts
you, which is a fact about that world and not a failure of the search.

## STILL OPEN: ONE WORLD'S NEAR FIELD HAS NO MATERIAL, AND I DO NOT KNOW WHY

Now that volcanic is lit, the remaining fault at its VESPER-1 landing site is
unmistakable and it is NOT brightness and NOT clutter. Shot with `uTerrDbg:6` —
the albedo written straight out, lighting removed entirely — looking straight
down at ground 3-8 m away, the frame is a **smooth blur of 90 distinct colours**.
The broad 12 m mottle is there as soft blotches; the fine bands (3 m, 1.7 m,
0.9 m) are absent completely.

**The first version of this entry called that a general near-field failure of the
band weights. It is not, and the controls say so.** Same probe, same distance,
same settings:

| | near-ground colours |
|---|---|
| volcanic VESPER-1, r 310 km | **90** |
| ashen VESPER-1, r 310 km | 869 |
| volcanic VESPER-4, r 764 km | 1,559 |

So it is not the renderer in general; it is not the planet's radius (ashen and
volcanic VESPER-1 are the same 310 km, and the one large world is the CLEAN one,
which is the opposite of what a `gEps` floor argument predicts); and it is not a
per-world material setting, because there are none — `gndAmt 0.90 / gndScale 12 /
gndDust 0.35` is a single default and no archetype overrides it.

I do not have the cause. What is established is that two worlds at identical
radius with identical ground-material parameters, probed identically, differ by
10x in near-field albedo structure. **Do NOT tune `gndAmt` to chase this** — the
amplitude is shared by the world that works and the world that does not.

The next step is a bisect, not a guess: the material is four explicitly weighted
bands (`b0` at uGndFreq, `b1` x4, `b2` x13, `b3` x7 creased) summed into `m`, so
write each band out on its own through a probe mode and find which of them is
dead on volcanic VESPER-1 and alive on ashen VESPER-1. `uTerrDbg` already exists
for exactly this and adding modes to it is cheap.

## Verified

`verify_oneclick`, `walk`, `harvest`, `roster`, `lint_glsl` all pass after every
change. `uniform_ab` shows both new uniforms are byte-identical from orbit.
`npm run shots` renders all 12 (gas still 100% black — deferred, unchanged).
