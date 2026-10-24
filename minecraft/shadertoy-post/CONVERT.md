# Porting a Shadertoy shader into Minecraft

The short version: a Shadertoy image shader and a Minecraft `final` pass are the
same thing wearing different names. One fullscreen fragment shader, the previous
image on a texture, a clock. Rename the globals and it runs.

The long version is the rest of this file, which is mostly about the cases where
that isn't true.

---

## 1. The five-minute version

1. Open the Shadertoy shader. Confirm it is a **single Image tab** with no
   Buffer A/B/C/D and no texture bound to a channel. (Check §4 if it isn't.)
2. Copy everything in the Image tab.
3. Open `ShadertoyPost/shaders/effects/custom.glsl`.
4. Paste it between the two `PASTE SHADERTOY CODE` markers, replacing what's
   there.
5. In game: **Video Settings → Shader Packs → Shader Pack Settings**, set
   **Effect** to **Custom**.

The compatibility layer (`shaders/lib/shadertoy.glsl`) has already defined
`iTime`, `iResolution`, `iChannel0` and the rest, so the pasted code compiles
without edits.

To check it compiles without launching the game, double-click
**`Check Shaders.bat`** — it renders every effect on a test scene and lists any
compile errors with the offending line.

---

## 2. What maps to what

| Shadertoy | Minecraft (Iris/OptiFine) | Notes |
|---|---|---|
| `iResolution` | `vec3(viewWidth, viewHeight, 1.0)` | Your render resolution, so it already accounts for GUI scale and fullscreen |
| `iTime` | `frameTimeCounter` | **Wraps to 0 every 3600 s.** See §5 |
| `iTimeDelta` | `frameTime` | Seconds the last frame took |
| `iFrame` | `frameCounter` | Wraps at 720720 |
| `iFrameRate` | `1.0 / frameTime` | |
| `iChannel0` | `colortex0` | The finished frame — world, sky, entities, weather |
| `iChannel1` | `depthtex0` | Shadertoy has no equivalent; this is a bonus |
| `iChannel2` | `noisetex` | Iris' built-in 64×64 noise tile |
| `iMouse` | *nothing* | See §4 |
| `iDate` | `worldTime` | Wired to Minecraft's clock, not the real one |
| `iChannelResolution[0]` | `iResolution` | Can't be a macro — substitute by hand |
| `fragCoord` | `gl_FragCoord.xy` | Both are pixels, both origin bottom-left. **No flip needed** |
| `mainImage(out fragColor, in fragCoord)` | called from `final.fsh`'s `main()` | |

Minecraft additionally offers, with no Shadertoy counterpart: `near`/`far`,
`worldTime`, `rainStrength`, `isEyeInWater`, `cameraPosition`, and the full set
of view/projection matrices. `isEyeInWater` and `rainStrength` are the easy wins
— gating an effect on "am I underwater" or "is it raining" costs one line and
makes the shader feel like part of the game rather than a filter over it.

---

## 3. Where the code goes

```
ShadertoyPost/shaders/
├── final.vsh              fullscreen quad — never needs touching
├── final.fsh              the harness: options, includes, the effect switch
├── shaders.properties     labels for the in-game options screen
├── lib/
│   └── shadertoy.glsl     the compatibility layer — iTime, iResolution, SAMPLE…
└── effects/
    ├── crt.glsl           ─┐
    ├── vhs.glsl            │
    ├── kaleidoscope.glsl   │ seven built-ins, each guarded by
    ├── ripple.glsl         │ #if EFFECT == n so only the selected
    ├── retro.glsl          │ one compiles
    ├── dreamglow.glsl      │
    ├── edges.glsl         ─┘
    └── custom.glsl        ← your paste goes here
```

Each effect file renames its entry point with `#define mainImage mainImage_XXX`
at the top and `#undef` at the bottom. That is what lets every file contain a
function literally called `mainImage` without colliding — the paste stays
verbatim and the rename happens in the preprocessor.

Because of the `#if` guards, a paste that doesn't compile only breaks **Custom**.
The other seven keep working while you fix it.

### Adding a permanent slot

Copy `custom.glsl` to `effects/myeffect.glsl`, change `#if EFFECT == 8` to `9`
and `mainImage_CUSTOM` to `mainImage_MYEFFECT`, then in `final.fsh`: add `9` to
the `// [0 1 2 …]` list on the `EFFECT` line, add the `#include`, and add an
`#elif EFFECT == 9` branch. Finally add `value.EFFECT.9 = My Effect` to
`shaders.properties`.

---

## 4. What does not port

**Buffer A/B/C/D.** Shadertoy's multipass shaders feed buffers into each other,
often reading last frame's own output to accumulate. Minecraft has exactly this —
`composite1.fsh` … `composite99.fsh` writing to `colortex1`+ — but it is not a
copy-paste, you have to declare the buffers and wire the passes. If the shader
you want has Buffer tabs, that is a real (if not difficult) port, not a paste.

**Textures on channels.** If a channel is set to a stock Shadertoy texture (wood,
rock, London, the abstract ones), that image isn't in Minecraft. Options: point
it at `noisetex` if it was only being used as noise, generate the pattern
procedurally, or drop a PNG next to the shaders and declare it. Cubemap channels
are a bigger lift.

**`iMouse`.** There is no cursor. Shaders that use it to orbit a camera will sit
at whatever `vec4(0.0)` gives them, usually a corner view. Replace it with a
constant you like, or drive it from `cameraPosition`/view direction — which is
usually better anyway, because then the effect responds to where you're looking.

**Audio channels and `iChannelTime`.** Not available. Nothing to map them to.

**Anything reading `iChannel0` as a *world*, not as an *image*.** A Shadertoy
raymarcher generates its scene from nothing and ignores its channels. Those port
fine, but note they will *replace* your view of Minecraft rather than
post-process it — the game will be behind the shader, invisible. That's a sky
shader's job, not a `final` pass's. Blend it against `SAMPLE(uv)` if you want
both.

---

## 5. Gotchas that will actually bite you

**`frameTimeCounter` wraps at 3600 seconds.** After an hour of play, `iTime`
snaps back to 0 and any animation driven by it jumps. Most effects don't care.
Anything that accumulates — `sin(iTime * bigNumber)` — will visibly pop. If it
matters, use `frameCounter` (wraps far later) or accept the hourly blink.

**Read offset UVs through `SAMPLE(uv)`, not `texture(iChannel0, uv)`.**
Shadertoy channels default to *repeat* wrapping, so reading off the edge tiles
harmlessly. Minecraft's colour buffers *clamp*, which smears the border pixel
into a streak across the whole edge of the screen. `SAMPLE` clamps the UV for
you. `toy_mirror(uv)` mirrors instead, which is what you want for kaleidoscopes.

**Shadertoy runs in a small window. Minecraft runs fullscreen.** A shader that
holds 60 fps in a 640×360 Shadertoy preview is being asked to do ~9× the work at
1440p. Raymarchers with 128-step loops are the usual casualty. Check the frame
time before assuming it's fine.

**Precision.** Shadertoy is `highp` everywhere by default and so is desktop GLSL,
so this rarely bites on PC — but hash functions built on `fract(sin(x)*43758.5)`
are fragile at large coordinates regardless of platform. If you get banding or a
static pattern that should be moving, that hash is the first suspect.

**Colour space.** `colortex0` in `final` is what Minecraft has already tonemapped
for display, low dynamic range, 0..1. There is no headroom above white. This is
why the bloom effect thresholds at 0.88 — anything lower and the daytime sky
itself qualifies as "bright" and the whole frame washes out. It did, during
development; that's how the number got picked.

**The HUD is drawn after this pass.** Hotbar, chat and inventory stay undistorted
no matter what you do to the world. Screenshots (F2) include the effect.

**Changing an option recompiles the pack.** A short freeze when you move the
Effect slider is normal, not a hang.

---

## 6. Licensing

Shadertoy's default licence is **CC BY-NC-SA 3.0** unless the author states
otherwise in a comment. Using someone's shader privately in your own game is
fine; publishing a shaderpack built on it means attribution, non-commercial use,
and share-alike. Check the individual shader — plenty of authors are more
permissive, and a fair number say so in the header. If you release a pack, credit
the shader and link the Shadertoy page.
