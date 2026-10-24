# TREES.html — the tree bake-off

Double-click **`TREES.html`**. Nothing to run, works offline, three.js is inlined.

Eleven candidate trees on their own patches of ground, all rotating so you can catch
the ones that look the same from every angle. Click one to blow it up, click again
to come back.

Three views, top-right: **BREED · LIBRARY · ORIGINALS** — and above them, a **WORLD**
selector. It **opens on BREED**, showing the planet you have selected and its own eleven
trees, because ORIGINALS is the one shelf that is identical on every world and opening
there made switching planet look like it only changed the paint.

## WORLDS — the theme was never the trees

Eighteen kept trees all looked like one forest, because every one of them was painted
from verdant's two colours: near-black trunk, green canopy. A tree is **geometry**; a
world is **paint**. The selector switches between all thirteen surfaced archetypes and
repaints everything — trunk, foliage, glow, sky, sun and ground — so the same shapes
become a magenta Crystalline forest, a rust-and-turquoise Arid oasis, a cyan Glacial
stand. `[` and `]` cycle.

`tools/trees/worlds.js` is **generated** by `node tools/gen_worlds.mjs` straight out of
the game's own `archetypes.js` and `objectKinds.js` — trunk from each roster's
`colFrom`, tip from `colTipFrom`, glow from `emisB`. A palette typed out by hand in a
second place is a palette that drifts the first time a world is retuned. `gas` is
absent because it has no surface, which is a design decision and not an omission.

**Each world grows DIFFERENT TREES, not the same trees recoloured.** `recipes.js`
gives every world its own recipe — which trunk / branch / foliage / symmetry it draws
from, and the band each gene is sampled inside — and generation zero is eleven genomes
rolled from it. Frozen opens on drooping whorled conifers, Oceanic on kelp clumps of
thin stems and long hanging ribbons, Arid on fat barely-tapering columns under flat
caprock, Crystalline on rib cages and faceted blades, Ashen on bare wandering trunks
with a wisp left on them. Deterministic in (world, index): the same planet always
opens on the same eleven. It is still ONE grammar and ONE builder, so a Frozen conifer
can be crossed with an Arid hoodoo.

A gene a recipe does not mention is sampled from the **middle 60%** of its range, not
the whole of it — sampling the full range drowns the recipe in noise, which is the
failure mode of every "just randomise it" generator.

Switching world mid-run **repaints and keeps your shapes**; it only reseeds when
generation zero is untouched, so you cannot lose work by looking at another planet.

A world's own emissive scales whatever the genome asked for, verdant's 0.15 being the
unit — so Arid and Frozen ship 0 and **nothing on them glows at all**, which is true of
those worlds. Capped at 0.55: crystal's roster emissive is 0.9, and at 1.1 a broad
canopy stopped being a glowing shape and became a white light panel. In game that never
shows, because crystal's spire is a needle at `rBase 0.11`. A canopy is not.


## BREED — selective breeding, with your eye as the fitness function

**Click a tree to KEEP it.** Kept trees are ringed green, survive both buttons, and go
straight into your library — there is no separate save step.

- **BREED** makes the next eleven from the kept ones. Those come back *unchanged*,
  marked `KEPT`, in the front slots, and stay kept — so pressing BREED again pushes
  further on the same thing rather than throwing it away.
- **NEW TREES** rerolls every slot you have **not** kept, from the whole space —
  genuinely new, never a tree already in your library, and never a population you have
  been shown before. Breeding can only recombine what is already in the pool, so a
  population that has converged has no way out of itself; this is the way out.
- **SUBTLE / NORMAL / WILD** is the mutation rate. **BACK** undoes a generation *or* a
  reroll. **RESET** returns to generation 0.
- **Double-click** to enlarge. The enlarged view shows the tree's **68-character
  genome**; click it to copy. Paste one to me and I can grow it back byte-identical.

Nothing reloads: a genome is 34 bytes, growing a tree takes **0.12 ms**, so a whole
generation is rebuilt in about **1.3 ms** — between two frames.

Generation 0 is eleven points as far apart as the grammar allows: all three trunk
modes, all three branch modes, all five foliage kinds, all three symmetries. They
echo the hand-built eleven and are deliberately **not** identical to them — these are
what one shared grammar can say. Starting from noise instead would die in generation
one, because a random genome is almost always ugly.

## LIBRARY — everything you have kept

**One collection per world.** Each theme keeps its own shelf, so a Frozen set and a
Verdant set never mix. **ALL WORLDS** concatenates them and paints every shape you have
ever kept in the palette you are standing in — the fastest way to find out whether the
eighteen verdant trees also work as crystal.

A running **strip** down the left while you breed (name, triangle count, `×` to
remove, click to bring one back into the pool), and a full **shelf** view where the
saved trees stand on the same pods under the same light, rotating, so you judge them
the way you judge everything else — by looking. The grid resizes to fit however many
you have saved.

- Click one on the shelf, then **SEND TO BREEDER** to cross it with something else.
  Recall two old favourites and breed them: that is the whole point.
- **COPY ALL CODES** dumps every saved genome as text.
- It persists in `localStorage`, which does work from a double-clicked file — verified.
  If it ever can't, the strip says `THIS SESSION ONLY` and the copy button is the
  backstop.

## ORIGINALS — the eleven hand-built candidates

Reference only: eleven separate algorithms, so there is nothing shared to breed, and
**the same eleven whatever world you pick** — only their colour changes here. The
header says so, because when this was the default view the page appeared to ignore the
world selector entirely.

| controls | |
|---|---|
| **click** | enlarge here; in BREED and LIBRARY it keeps, and double-click enlarges |
| **M** | cycle the three views |
| **enter / R** | breed / new trees (in BREED) |
| **7 8 9** | backdrop — dark · mid · **light** (default) |
| **[ ]** | previous / next world |
| **N** | night ↔ day (night is the default) |
| **1 2 3** | height — 3 m · 12 m · 30 m, with a 1.8 m human on every pad |
| **4 5 6** | width — 1:1 · broad (rBase 0.45) · narrow (rBase 0.16, what verdant ships) |
| **space** | stop the spin |
| **B** | bloom · **O** ambient occlusion · **G** turn the new `aPart` attribute off |

## The roster

Palette is verdant's, unedited: `lo #1c5f59 · mid #2b8763 · hi #33254f · peak #b6cbd8 ·
emisB #33ff77`. Trunk paints from `hi`, canopy from `mid`, exactly as the game does.

| # | name | tris | the idea |
|---|---|---|---|
| **00** | TIERED LATHE · **CONTROL** | 180 | the tree VESPER ships today — one profile spun about the vertical |
| **01** | BOUGHWORK | 168 | real recursion: a trunk that forks twice, leaves only on the outer tips |
| **02** | PARASOL | **78** | one bold drooping canopy with a scalloped 15-gon rim, not many small forms |
| **03** | PAGODA | 122 | five separated discs threaded on a stem, each offset sideways and turned |
| **04** | PRISM | 164 | thirteen faceted blades bursting from one root; not a curve anywhere on it |
| **05** | FROND | 174 | nine arcing V-section ribbons that rise, turn over and droop |
| **06** | BRAID | 168 | five thin trunks from one root, converging under one shared crown |
| **07** | GNARL | 228 | the trunk IS the tree — a wandering, fluted, twisting sweep with reaching limbs |
| **08** | LANTERN | 142 | a dark cage of ribs around a pod that is the light source |
| **09** | CROWN | 146 | a broadleaf mass from seven overlapping lumps in three sizes |
| **10** | SAIL | 136 | bilateral, not radial — a wall face-on, a line edge-on |

Mean 155 triangles, worst 228, ceiling 400. Every one is normalised in code:
base `y=0`, tip `y=1`, widest radius exactly `1.00` — `tests/trees_verify.mjs`
measures it in the page and fails the run if any tree drifts.

## The one new vertex attribute — `vec2 aPart`

```glsl
in vec2 aPart;      // .x  material: 0 = bark/stem, 1 = foliage/blade/membrane
                    // .y  exposure: 0 = buried in the canopy, 1 = out in the open
```

It lives on the shared per-family geometry, so it is free per instance. It replaces
`objectShader.js`'s `smoothstep(0.16, 0.52, vY)` — the guess that anything above 16%
of the height is canopy. **Press G to switch back to that guess and watch what it
costs.** Two lines in the vertex shader, two in the fragment shader:

```glsl
// vertex
vMat = aPart.x;  vAo = aPart.y;

// fragment — replaces  base = mix(vCol, vCol2, smoothstep(0.16,0.52,vY))
vec3  base = mix(vCol, vCol2, vMat);
vec3  col  = base * vShade * mix(0.34, 1.0, vAo);
```

The `.y` channel is a free ride in the same attribute slot — an attribute is a vec4
slot either way. Drop it if you don't want it; `.x` is the one that matters.

## The backdrop is a separate dial from the light, and it has to be

Bark on this palette paints from `hi` (#33254f) — verdant's darkest field, near black.
Against a night sky that was also near black, a tree that is mostly trunk was **black
on black** and there was nothing to judge. Raising the *exposure* would not have fixed
it: that lifts the tree and the background together and the contrast stays exactly
where it was. **7 8 9** lift only the sky dome, the ground and the pod, toward a
neutral, so a dark trunk gets something to be seen against; a little fill rides along
so the shapes keep their modelling instead of flattening into cut-outs.

Night frame mean went 12.9 → **60.7**, lit area 4.5% → **86.3%**, distinct colours
902 → **1,726**, nothing blown. `DARK` is still there for the honest "does this
survive a real VESPER night" check.

## A bug worth remembering: a seed derived from what it produces

NEW TREES used to seed its RNG from `pop[0][0]` — one byte of the population it was
about to replace. A byte has 256 states and the seed produced that byte, so the whole
thing was a deterministic map from a 256-element set onto itself, and a map like that
**must** cycle. Measured: it came back around after **5 to 21 presses**, so hammering
the button walked a tiny loop and kept handing back trees already seen and saved.

It is fixed with a monotonic counter, persisted in storage so a new session does not
repeat the last one — deterministic, just never backwards. The lesson generalises:
*never derive a seed from state the seed itself generates.* The test now presses NEW
forty times and demands 440 distinct trees.

## Two things this turned up in the real code

1. **`o.spin` is computed and never used.** `surfaceObjects.js:251` and `:363` set a
   random yaw per instance; `terrain.js:191-197` composes the instance matrix from
   up-alignment and tilt only. Every object's yaw is currently a smooth function of
   where it stands on the sphere, so neighbours all face nearly the same way. Invisible
   on a lathe; fatal for anything asymmetric. Two lines in `buildObjects()`.

2. **`rBase` has to move with the geometry.** verdant's canopy tree ships `rBase: 0.10`,
   so the instance matrix squeezes a radius-1.0 geometry to a tenth of its height. Press
   **6** and watch most of these collapse — PAGODA becomes a spike, CROWN a lollipop.
   The control was designed for that ratio; nothing else here was.

## The genome

34 genes, integers 0..255, so the whole thing is a 68-character hex string that grows
back **exactly** the tree you picked. (Floats would decode to a *different* tree from
the one on screen — the sort of thing nobody notices until the shipped forest looks
wrong.)

```
trunk     mode(single|multi|cage) · height · girth · taper · wander · lean · sides
          · flute · twist · stems · splay · converge
branches  mode(none|whorl|fork) · count · levels · length · angle · droop · radius
foliage   kind(none|blob|disc|ribbon|blade|membrane) · count · size · flatness
          · spread · rise · droop · jag · symmetry(radial|bilateral|scattered)
          · phase · tilt
look      emissive · material split · occlusion depth · seed
```

The categorical genes are **weighted, not uniform** — `WEIGHT` in `grow.js`. With six
equal foliage bands, `none` was one value in six, so every mutation had a 17% chance
of deleting the leaves; five generations in, eight of eleven trees were bare trunks.
A bare trunk is a legitimate tree (ashen ships burnt snags) but a rare one, and a
uniform categorical gene makes every option equally likely whatever the art says.

## Where the code is

- `tools/trees/geom.js` — the toolkit: `rnd / MB / tri / quad / tube / blob / finish`.
  Six functions, no dependency beyond three's `IcosahedronGeometry` and
  `OctahedronGeometry` as vertex sources.
- `tools/trees/grow.js` — the genome and `growTree()`, the one builder the whole
  breeding space comes out of.
- `tools/trees/app.js` §1 — the eleven hand builders. §2 — generation 0. §3 — the
  material. §4 onward — the viewer and the breeder.
- `tools/build_trees.mjs` — inlines `three.cjs` (the CommonJS build; it has no
  `require()` at all, so an IIFE with a local `exports` gives the whole namespace)
  plus the three source files, in that order, and writes `TREES.html`.
- `tests/trees_verify.mjs` — the gallery, over `file://` on the real GPU.
- `tools/trees/recipes.js` — what grows where. Art direction, in the same sense
  `objectKinds.js` is: editable without reading a line of `grow.js`.
- `tests/trees_worlds.mjs` — all thirteen worlds render, none blows out, none is too
  dark, **every one is a different hue from every other**, **every one opens on its own
  eleven trees**, and each grew the character its recipe names — the check that catches a
  backdrop lift washing them all to the same teal. `--shots` writes a frame per world.
- `tools/gen_worlds.mjs` — regenerates `worlds.js` from the game. Re-run after art changes.
- `tests/trees_evolve.mjs` — the breeder. Grows **3,000 random genomes** and checks
  that none of them exceeds the triangle ceiling or breaks normalisation, because the
  space is 256³⁴ trees and nobody will ever look at more than a few hundred of them.
  `--deep` runs 12,000.
