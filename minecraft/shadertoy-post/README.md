# Cool Shaders

Shadertoy shaders, running as post-processing in Minecraft Java (Iris / Fabric).

Minecraft draws the world. This pack gets the finished frame and does whatever it
likes to it before it reaches your screen — which is exactly the shape of a
Shadertoy image shader, so they port over with barely an edit.

---

## Get it running

1. Double-click **`Install Shaderpack.bat`** — copies the pack into
   `%APPDATA%\.minecraft\shaderpacks\`.
2. In game: **Options → Video Settings → Shader Packs → Shadertoy Post**.
3. **Shader Pack Settings** → pick an **Effect**, drag **Intensity**.

Needs Iris on Fabric (or OptiFine). Java Edition only — Bedrock's Render Dragon
can't load GLSL shaderpacks at all.

## What's in it

| # | Effect | |
|---|---|---|
| 0 | Off | passthrough |
| 1 | CRT | curved glass, aperture grille, scanlines, misconverged guns |
| 2 | VHS | tape wobble, tracking band, chroma bleed, head noise |
| 3 | Kaleidoscope | screen folded into six mirrored wedges, slowly rotating |
| 4 | Underwater | crossed-wave refraction, caustics, red absorbed with depth |
| 5 | Retro | 320px chunky pixels, 6 colour levels, ordered dithering |
| 6 | Dream Glow | bloom — torches, lava and the sun bleed light into the air |
| 7 | Cel / Outline | banded shading, outlines traced from **depth** not colour |
| 8 | Custom | **your slot** — paste a Shadertoy shader here |

Everything reads through one compatibility layer, so `iTime`, `iResolution` and
`iChannel0` already mean the right thing.

## Put your own shader in

Paste the Shadertoy Image tab into
`ShadertoyPost/shaders/effects/custom.glsl`, between the two markers. Select
**Custom** in game. That's the whole loop.

**`CONVERT.md`** is the real manual: the full uniform mapping table, what doesn't
port (Buffer A–D, bound textures, `iMouse`), and the gotchas — clamped edges,
`frameTimeCounter` wrapping every hour, no headroom above white.

## Iterate without launching Minecraft

Double-click **`Check Shaders.bat`**. Two tabs open:

- **preview.html** — all nine effects rendered live, side by side, on a synthetic
  blocky scene with a real depth buffer (so the depth-based outline effect is
  exercised properly).
- **verify.html** — compiles and links every effect against your actual GPU
  driver and prints any error with the source line.

Edit a `.glsl`, refresh the tab. Both read the pack's real files with `#include`
resolved the way Iris resolves it, so what you see is what the game compiles.

Needs Python on PATH — only to serve the folder over localhost, because browsers
won't `fetch` from `file://`.
