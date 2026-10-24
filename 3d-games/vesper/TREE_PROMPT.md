# PROMPT — ten trees, one page, pick the winner

Paste everything below the line into a fresh session. It is self-contained.

---

Build me **one double-clickable HTML file** called `TREES.html` that shows **11
different procedurally-generated alien trees** side by side so I can look at
them and pick the best one.

I will not run a command. Not npm, not a dev server, not a script. I double-click
the file and it works, offline. So three.js must be **inlined into the HTML**, not
loaded from a CDN and not imported as a module from disk (Chrome blocks ES module
imports over `file://`). There is a vendored copy at
`D:\projects\VESPER\node_modules\three\build\three.module.js` — inline
it, or copy what `D:\projects\VESPER\tools\bundle.mjs` already does to
produce a single self-contained file. **Verify it actually opens over `file://`
and renders before you tell me it is done** — open it headless with puppeteer,
count the pixels, and read the console for errors. Do not report success you have
not seen.

## What this is for

These trees are going into a game called VESPER — a stylised first-person space
sandbox where you land on procedural planets and walk around. There is a forest
world with tens of thousands of trees drawn at once. I have looked at the current
one and my exact words were *"is it that hard to make a tree lol"*. It is a
7-sided cone with a smaller cone on top. I want better.

## The look — this is settled, do not reinterpret it

**No Man's Sky, not Space Engineers.** Stylised: saturated, high contrast, bold
readable silhouettes, alien forms, sometimes glowing. A poster, not a photograph.
Detail comes from **shape and colour**, never from texture — there are no image
files anywhere in this project and there will not be.

## HARD CONSTRAINTS — a tree that breaks these cannot ship

These come from the real renderer. A design that violates them is useless to me
no matter how good it looks.

1. **One single `BufferGeometry` per tree.** No groups, no multi-material, no
   `Object3D` with children. Every tree is drawn with `InstancedMesh` and ONE
   `ShaderMaterial`. If you need separate trunk and foliage meshes, merge them
   into one geometry (`BufferGeometryUtils.mergeGeometries`, vendored the same
   way, or just build the vertex arrays by hand).
2. **Authored normalised.** Base sits at **y = 0**, tip at **y = 1**, and the
   widest radius is about **1.0**. The game scales each instance by
   `(radius, height, radius)`, so anything else lands the tree buried or floating.
3. **Triangle budget: aim for 150, hard ceiling 400.** There can be 20,000+
   instances in view. State the actual count for each tree.
4. **No textures, no UVs, no image assets.** Colour comes from the three sources
   in the next section and nothing else.
5. **Built in code from a seed**, so the same tree is identical every time. No
   `Math.random()` at draw time.

## How colour works in the real shader

You get exactly these, and they are the whole palette system:

- `aCol` and `aCol2` — two colours per instance, taken from the planet's own
  palette. The fragment shader currently does
  `mix(aCol, aCol2, smoothstep(0.16, 0.52, vY))` where `vY` is `position.y`
  (0 at the base, 1 at the tip). That is how the current tree gets a dark trunk
  and a green canopy.
- `aShade` — one brightness multiplier per instance, so a stand is not one flat
  tone.
- `aEmis` — how much this instance glows. Some worlds' plants are light sources.

**You may propose ONE extra per-vertex attribute** — for example a float that
says "this vertex is foliage, that one is bark", or a baked ambient-occlusion
value. This is free: it lives on the shared per-family geometry, not per
instance. I think this is probably the unlock, because right now colour can only
vary by HEIGHT, which is why a low branch and a high trunk cannot be different
materials. If you use it, say clearly what it is and how the shader should read
it.

## The thing I think is actually wrong with the current tree

It is a `LatheGeometry` — a 2D outline spun about the vertical axis. That means
it is **rotationally symmetric: identical from every angle**. A forest of them
looks like stamped copies no matter how much you jitter size and lean, because
every one presents the same silhouette from every direction. I am not telling you
the answer, but I want at least some of the eleven to break that.

## What to build

**Attempt 0 is the CONTROL — the current VESPER tree, so I can see whether the
others are actually better.** Its profile, as `THREE.Vector2(radius, height)`
pairs run bottom to top, spun with 10 radial segments:

```
[0.34,0.00] [0.13,0.10] [0.11,0.26] [1.00,0.30] [0.42,0.52]
[0.78,0.55] [0.34,0.74] [0.55,0.77] [0.18,0.92] [0.00,1.00]
```

Then **ten genuinely different approaches — not ten tweaks of one idea.** Vary
the underlying strategy, not the parameters. Some directions worth considering,
though I would rather you surprise me than work down this list: real branching
structure with limbs; a broadleaf canopy built from clustered forms; stacked or
floating discs; something crystalline and faceted; a fern or frond; a gnarled
asymmetric trunk; several thin trunks from one root; a canopy that is one bold
shape rather than many small ones; something that leans into the glow.

For each one give me, on screen: a **number**, a **short name**, the **triangle
count**, and **one line** on what the idea is.

## The page itself

- All 11 visible at once in a grid, each on its own little patch of ground,
  **slowly rotating** so I can see the silhouette from every angle. This is the
  single most important feature of the page — rotation is how I will catch the
  ones that look the same from all sides.
- Click one to blow it up large on its own. Click again to go back.
- **A day/night toggle**, and make night the default. Every judgement in this
  project has been made at night and things that look fine lit fall apart in the
  dark. I need both.
- **A size toggle** showing each tree at roughly 3 m, 12 m and 30 m tall against
  a human-height reference, because these have to read at both ends.
- Use the real forest world's palette so the colours are honest — VESPER's
  `verdant` archetype:
  `lo #1c5f59` · `mid #2b8763` · `hi #33254f` · `peak #b6cbd8` ·
  `emisB #33ff77` (the glow colour). The current tree uses `hi` for the trunk
  and `mid` for the canopy.
- Light it roughly like the game does: one directional sun, a weak hemispheric
  sky term from above, and a rim light in the sky colour around the silhouette.
  Keep the total reflected light under about 0.9 so nothing blooms out to white
  — that has bitten this project three times.
- Dark background, and please make the page itself look decent. I am judging by
  eye, so the presentation matters.

## What to hand back

The file, plus a short note listing each tree's name, triangle count, and the
one-sentence idea — so when I say "number 7" you know exactly which is which.

Do not tell me it works unless you have opened it and looked at the output.
