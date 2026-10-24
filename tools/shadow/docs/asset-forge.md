# Asset Forge — Studio's mesh pipeline

> **Status:** Stage 1 built and tested (2026-07-30). Blender bridge, central library, IPC, and the
> council-author briefing are live. Stages 2–4 (ComfyUI, image→3D, Studio UI) are not built yet — see
> the roadmap at the bottom.

## What it is

Turns **any** mesh into a game-ready asset: triangle-budgeted, UV-unwrapped, optionally
normal-baked, exported as GLB with LODs. Runs entirely on the local machine through the Blender the
owner already has installed.

**No API, no network, no cost.** That is a hard requirement, not a preference — see `HANDOFF.md`:
the owner has a Claude subscription and **no API budget**, so anything that bills per call is not an
option for this project.

```
source mesh (.ply .obj .glb .gltf .fbx .stl .dae)
      │
      ▼
  clean          remove doubles, delete loose, fix winding
  decimate       quadric-error collapse to a triangle budget
  unwrap         smart UV project
  bake           high-poly surface → tangent-space normal map   (optional)
  export         GLB (+ LOD1, LOD2, … at ratios you choose)
      │
      ▼
  Shadow Library/assets/<slug>-<hash>/
```

### Why decimation is the load-bearing step

Generated and scanned meshes arrive dense — a barrel can easily be 100k triangles. Two things break
at that size: Roblox refuses meshes over roughly **10k triangles**, and a Unity scene spawning 125
zombies has no budget for it either.

Naive reduction wrecks the silhouette. Quadric-error collapse repeatedly removes whichever edge
changes the shape *least*, so a 3k budget gets spent on the curved parts and starved on the flat
panels. That is what "lowpoly mode" has to mean to be useful.

### Why the normal bake matters just as much

Decimation alone throws detail away — a 100k rock becomes a 2k blob that reads as smooth and cheap.
Baking projects the **pre-decimation** surface into a normal map, so the bumps, cracks and dents move
from geometry into a texture. You keep the look at 2% of the triangles. Same principle as the
`normals.png` in any PBR material.

## Using it

### From an agent (council author, Terminal build agent)

The author agent runs with `full` tool policy, so it has `Bash` and calls the pipeline directly.
It is told about this automatically: `forgeAgentBriefing()` in `src/assetForge.ts` injects the exact
command into the author prompt — **but only when Blender is actually found.** Advertising a tool that
will fail is worse than staying quiet, because the agent burns a turn on it and then reports a broken
tool as a broken project.

```bash
"<blender>" --background --factory-startup --python "<pipeline>" -- \
    --input source.ply --output out.glb --target-tris 3000 --bake --lods 0.5,0.2
```

The last stdout line is `__SHADOW_MESH_RESULT__` followed by JSON. Parse that line and ignore
everything else — Blender's own chatter is unpredictable and none of it is ours.

```json
{"ok": true, "sourceTris": 20480, "finalTris": 2000, "decimateRatio": 0.09766,
 "normalMap": "…_normal.png", "bakeError": null,
 "lods": [{"level": 1, "path": "…_LOD1.glb", "tris": 1000}], "bytes": 70564}
```

### From the renderer (Studio UI)

```ts
await window.shadow.forgeStatus();          // { blenderFound, blenderPath, libraryRoot }
await window.shadow.forgeMesh({ inputPath, name: "Rusted Drum", targetTris: 3000, bake: true });
await window.shadow.forgeLibrary(100);      // newest first
await window.shadow.forgeImportToProject(assetId);   // copy into the open project
window.shadow.onForgeProgress(({ line }) => …);      // Blender's progress, live
```

Both paths run the same script. One pipeline, one place for it to break.

## Flags

| Flag | Default | Notes |
|---|---|---|
| `--input` | required | `.ply .obj .glb .gltf .fbx .stl .dae` |
| `--output` | required | Always GLB |
| `--target-tris` | `0` | `0` leaves the mesh alone. Roblox caps ~10k. |
| `--bake` | off | **Requires `--target-tris`** — it bakes the pre-decimation surface onto the decimated one. |
| `--bake-size` | `1024` | Normal map resolution |
| `--lods` | none | Ratios of the final budget, e.g. `0.5,0.2` |
| `--normalize-scale` | `0` | Fit largest axis to N metres. `0` leaves scale alone. |
| `--smooth-angle` | `30` | `0` disables auto-smooth |
| `--no-center` | off | By default the asset is moved to origin |

## The library

Assets land in **one central library**, not per-project:

```
Documents/Shadow Library/
  index.jsonl                    append-only, one line per asset
  assets/<slug>-<hash8>/
      asset.glb
      asset_normal.png
      asset_LOD1.glb
      manifest.json
```

**Why central:** a generated barrel is worth having in every game, not only the one that happened to
be open when it was made. `forgeImportToProject` *copies* into the project, so the library stays the
master and deleting a project never destroys an asset.

**Why `index.jsonl` is append-only:** same reason `backend/app/taste.py` uses a JSONL ledger — a
crash mid-write costs one record, not the whole history. `listLibrary` skips entries whose folder no
longer exists, so deleting an asset folder by hand is a supported way to remove it.

## Design decisions worth not re-litigating

- **A script, not a `bpy` dependency.** `bpy` only imports inside Blender; the standalone wheel is a
  200 MB dependency that breaks on every Blender version bump. Spawning the user's Blender costs one
  process and survives upgrades.
- **`--factory-startup` always.** It ignores the user's addons and preferences, so a plugin installed
  last week cannot silently change what the pipeline produces.
- **Newest Blender wins.** `findBlender` scores `Blender 5.1` above `Blender 4.2` numerically — a
  plain string sort gets this wrong the moment versions reach double digits.
- **Triangles, not polygons.** An n-gon is (n−2) triangles on a GPU, so counting faces under-reports
  badly on quad meshes. The budget is honoured in triangles.
- **The Blender scripts ship as `extraResources`, outside `app.asar`.** Blender is a separate process
  and cannot read inside an asar. Packaging them with `files` would put them somewhere Blender
  reports as "file not found".
- **A failed bake is not a failed asset.** `bakeError` is reported and the GLB is still valid. Cycles
  can legitimately run out of VRAM on a dense source.

## Roadmap

| Stage | What | Status |
|---|---|---|
| 1 | Blender bridge, library, IPC, agent briefing | **Built + tested** |
| 2 | ComfyUI bridge — detect/start server, build graph, poll, retrieve | **Built + tested** |
| 3 | Image→3D (Hunyuan3D 2.1, native ComfyUI nodes) | **Built + tested** |
| 4 | Studio UI — prompt box, triangle slider, live preview, recent-generations gallery | **Built** |
| 5 | Engine targets — Unity + Roblox export | **Built + tested** |
| 6 | Mesh ingest (file picker) + the 3D viewer (Model Viewer tab) | **Built; compile-verified, not yet driven by a human** |

## Image→3D broke, and why — diagnosed and FIXED 2026-08-07

Stage 3 produced rubble for a day: flat slabs, shredded debris, valid GLBs with
plausible triangle counts and no error anywhere. **Shadow's code had not changed** — the whole
uncommitted diff to `comfyBridge.ts` was purely additive (0 lines removed). What moved was the
floor: **ComfyUI went 0.9.2 → 0.30.0.**

The graph was rebuilt against ComfyUI's own reference workflow, which ships in the install at
`blueprints/Image to Model (Hunyuan3d 2.1).json`. Four differences, all of them ours:

| | Was | Now | Why it mattered |
|---|---|---|---|
| loader | `CheckpointLoaderSimple` + `CLIPVisionLoader` | **`ImageOnlyCheckpointLoader`** | see below — the big one |
| model patch | *(none)* | **`ModelSamplingAuraFlow`, shift 1.0** | the sigma schedule this model samples on |
| voxel→mesh | `VoxelToMeshBasic` | **`VoxelToMesh`, "surface net"** | Basic is now `is_deprecated=True` and emits cube faces |
| latent | 3072 | **4096** | what the blueprint uses |

### ⚠ The checkpoint carries its own image encoder. Do not load one.

`ImageOnlyCheckpointLoader` returns `(MODEL, CLIP_VISION, VAE)`. The CLIP_VISION inside
`hunyuan_3d_v2.1.safetensors` is the encoder the model was **trained against**. We were loading
`clip_vision_l.safetensors` separately and feeding it in.

It does not error, and that is the whole trap. CLIP ViT-L/14 is 1024-wide, the model declares
`context_dim: 1024`, and `Hunyuan3Dv2Conditioning` passes `last_hidden_state` through with **no
projection** — so the shapes line up perfectly while the model receives features from a network it
has never seen. Output: a valid GLB full of noise.

**The old note in this document was solving the wrong problem.** It said Hunyuan3D "needs a
1024-wide vision encoder, `clip_vision_l`, not SigCLIP". SigCLIP threw a shape error; CLIP-L
silently did not; and matching the shape got mistaken for matching the model. `findMeshModels()`
no longer looks for a vision encoder at all — that absence is the fix, not an omission.

### Result, same image, same prompt

```
before  →  a flat slab, or scattered slivers, or shredded noise
after   →  a recognisable stone skull: eye sockets, brow, teeth, ear
```

### Two artefacts that remain, both now reported rather than silent

**1. The background becomes geometry.** Hunyuan3D rebuilds the surface it reads the subject as
standing on or in front of — measured the same day, from equally clean white-background images:

- a stone head arrived sitting on a 1.96 × 1.96 ground **disc**
- a statue torso arrived welded to a 1.96 × 1.96 back **wall**

Same defect, different face of the volume. `--drop-ground` (`dropGround` on `forgeMesh`, on
automatically for anything generated, off for a mesh you supplied) removes it. Detection is
**vertex density at an extreme**, not flatness — measured share of vertices in the outermost 1/64
slice: **49.8%** for the back wall, **62.2%** for the ground plate, **13.9%** for a plain
cylinder's own flat caps. The threshold is 0.30, sitting in the gap with room on both sides.

Prompting cannot fix this: "shadow on background" was already in the negative prompt, and the
strengthened version produced an image with no visible shadow that *still* reconstructed a plane.

**2. Reconstruction sometimes collapses to a bas-relief.** A statue torso came back as a shallow
relief on a backing plane — 97.6% of its vertices inside the outer 10% of its depth. There is
nothing to salvage there, so the pipeline says so in `notes` instead of cutting the asset away.
The same prompt often succeeds on a second attempt; **image→3D is not deterministic**, and the same
image at the same settings produced 11,132 triangles of slivers on one run and 163,932 on the next.

**3. The triangle budget is now guaranteed, not attempted.** Blender's collapse decimator will not
collapse an edge that would go non-manifold, so on generated geometry it stops early and *reports
success*. Measured, all asked for 3,000 triangles: **386,188 · 522,864 · 1,495,132**.

When collapse misses by more than 2×, the surface is voxel-remeshed into a guaranteed-manifold
shell and decimated again. Destructive — sharp edges soften, UVs are rebuilt — so it runs **only
after collapse has already failed**, where the real choice is not "clean vs remeshed" but
"remeshed vs an asset 500× over budget that no engine will accept". Detail is not lost: the normal
bake samples the untouched pre-decimation copy, which is taken before any of this.

```
maul     1,495,132  ->  4,186     (remeshed)
torso      386,188  ->  3,000     (remeshed)
cylinder     5,276  ->  2,000     (collapse alone; no remesh, no note)
```

## THE FULL CHAIN WORKS — measured 2026-07-30

```
"a police car"
   │  Flux schnell, ~20 s        1024x1024, 96% white background
   ▼
image.png
   │  Hunyuan3D 2.1, ~33 s       200,052 triangles, 6.87 MB
   ▼
raw.glb
   │  Blender pipeline, ~3 s     decimate + unwrap + bake + LODs
   ▼
police_car.glb                   4,000 tris · normal map · LOD1 1,599 · LOD2 984 · 244 KB
```

A **50× triangle reduction** with the surface detail preserved in a baked normal map. Every step runs
on the local GPU. Total cost: nothing.

> Useful discovery while testing stage 2: ComfyUI's **embedded** Python runs
> **torch 2.9.1+cu128**, much newer than the system-wide `2.7.1+cu118`. Install image→3D nodes into
> ComfyUI's environment, not the system one — the CUDA-version objection to stage 3 mostly evaporates
> there.

## Stage 5 — engine targets (`--engine`)

`--engine generic | unity | roblox`, or `engine` on `forgeMesh`.

| Target | Format | Triangle cap | Units |
|---|---|---|---|
| `generic` | GLB | none | metres |
| `unity` | **FBX** | none | metres — drop straight into `Assets/` |
| `roblox` | **OBJ + MTL** | **10,000, hard** | **studs** (×3.571) |

> **The Unity row said GLB and the code has said FBX for some time.** Unity 6 ships no
> glTF importer, so a `.glb` dropped into `Assets/` becomes a `DefaultAsset` with no mesh
> and **no import error** — see the comment in `engine_profile()`. The consequence worth
> carrying: **`ForgeAsset.glbPath` is not necessarily a GLB.** It is whatever the engine
> profile emitted. Dispatch on the real extension of the real path, never on the field
> name.

**Verified 2026-07-30:** asked for 25,000 triangles with `--engine roblox` → clamped to 10,000, output
extension rewritten `.glb` → `.obj`, scaled ×3.571, `withinEngineLimits: true`.

### Why Roblox needs its own rules

- **10,000 triangles is a hard platform limit, not advice.** An over-budget mesh fails at import with a
  message that does not name the number. The pipeline clamps and says so in `notes` rather than letting
  you find out in Studio.
- **1 stud = 0.28 m.** Roblox's importer has no dependable metres mode, so a mesh authored in metres
  arrives ~3.5× too small and every prop needs hand-rescaling. The conversion is applied last, after all
  triangle work.
- **The output extension is rewritten to match the target.** A `.obj` named `.glb` imports as nothing,
  silently — so the script owns the extension and reports the real path in `output`. **Read that field
  rather than assuming the path you passed.**
- **OBJ over FBX** deliberately: plain text, no version drift, and it carries the UVs and material
  reference. FBX exporter version mismatches are a class of bug worth not having.

### What this does NOT do

It does not upload to Roblox. `MeshPart`s reference meshes in Roblox's asset system, and mesh data
cannot be embedded in an `.rbxm` — so a genuine one-click publish needs an Open Cloud API key and an
upload step. What you get is a **validated, correctly-scaled, under-cap file** to import via a
MeshPart's `MeshId` or the Asset Manager. Open Cloud upload is the natural next increment.

## Stage 4 — the Studio UI (`renderer/src/AssetForge.tsx`)

A cockpit tab, **Asset Forge** (⬢), alongside the other forges. One prompt box drives the whole chain.

| Area | What it does |
|---|---|
| **Status strip** | Blender found? ComfyUI running? Free VRAM. A **Start ComfyUI** button when it is down — no dropping to a terminal. |
| **Prompt** | Plain text. Ctrl/Cmd+Enter fires it. |
| **Triangle budget** | Slider 0–30,000, plus presets: **Roblox** 8k (under the mesh cap), **Prop** 4k, **Hero** 20k, **Raw** 0. |
| **Toggles** | Normal bake (auto-disabled at budget 0, where it is meaningless), LODs at 40%/15%, detail grid 128–512, scale-to-metres. |
| **Live log** | Streams `shadow:forgeProgress` from all three stages. Capped at 200 lines — a bake emits hundreds and an unbounded array in React state is a slow leak. |
| **Result** | The generated image beside before/after triangle counts, LOD count, file size, and **Send to project**. |
| **Recent assets** | The library, newest first, each with **Send to project**. |

The image preview uses ComfyUI's own `/view` URL (`previewUrl` on the result) rather than the temp
file path: a `file://` path will not render under the renderer's origin, and shipping the bytes
through IPC as base64 is wasteful when a local HTTP server is already serving them.

**The UI does not implement the pipeline.** Every button calls the same IPC an agent calls, so there
is one implementation and one place for it to break.

## Stage 6 — mesh ingest and the 3D viewer (2026-07-31)

Two things that turn the forge from "generate a thing" into a workshop for **any** asset.

### Ingest — a file picker in front of the IPC that already accepted one

`shadow:forgeMesh` has always taken an arbitrary `inputPath`; nothing in the renderer ever
called it with one. `shadow:forgePickModel` is a native `dialog.showOpenDialog` filtered to
`FORGE_SOURCE_EXTENSIONS`, which is `mesh_pipeline.py`'s own `IMPORTERS` dict written down —
**not a wider list.** A dialog that accepts `.blend` and then fails deep inside Blender reads
as a broken forge rather than an unsupported input.

The Asset Forge tab gains an **Import existing model** panel that shares every control with
the generate path (triangle budget, bake, LODs, engine, scale) and calls the same
`forgeFrom()` tail. Two copies of that call is how one of them quietly stops honouring a new
control.

> ### ⚠ Readiness is PER-CAPABILITY. One boolean killed the feature before it shipped.
>
> `AssetForge.tsx` gated its whole surface on `blenderFound && comfy.running`. Generation
> needs both; **importing a mesh you already have needs Blender and nothing else** — and
> ComfyUI pins ~16 GB of VRAM while it runs, so requiring it to import an OBJ is requiring a
> model server to open a file. It is now `generateReady` vs `importReady`, and the banner
> **names the missing dependency** instead of greying everything out: "ComfyUI is not
> running, so text-to-3D is unavailable; the import path below is live."

### The viewer — `renderer/src/ModelViewer.tsx`, one component, three mount points

A **Model Viewer** tab (⬡) in Studio's top tab strip opens any model off disk, independent of
the forge and the library; the same component also renders the Asset Forge result and expands
inside a library row. `three` (0.180) with `OrbitControls`, a neutral studio light rig
(hemisphere + key + fill, nothing coloured — a themed light would lie about the asset's own
colour) and a **grid re-sized to the model**, one square per unit, so a 0.3 m bolt and a 40 m
building both have a readable floor. The model is bottom-centred at the origin rather than
dropped in wherever its authoring origin was.

**How the bytes get to the renderer, and why not a URL.** The window is loaded with
`mainWindow.loadFile(...)` and `sandbox: true`, so the renderer's origin is `file://` and
Chromium blocks `fetch("file:///…")` outright. That is not a CSP setting — **this app sets no
CSP header at all**, and relaxing one would not have helped. A custom protocol would work but
widens what the renderer can read to whatever that scheme resolves. Instead
`shadow:forgeReadModel` reads the file in main, extension-checked against
`MODEL_PREVIEW_EXTENSIONS` and capped at 128 MB, and returns a `Uint8Array`. Every loader
here parses from memory. **`webSecurity` is untouched.**

The cost of that choice is stated in the UI rather than hidden: a loader parsing from memory
has no directory to resolve siblings against, so a `.gltf` with an external `.bin`, an `.obj`
with an `.mtl` or an `.fbx` with textures beside it shows geometry without the extras. A
`.glb` is one file and is unaffected — which is why GLB is the priority format.

`MODEL_PREVIEW_EXTENSIONS` is deliberately a **separate constant** from
`FORGE_SOURCE_EXTENSIONS`. They coincide today; coupling them would mean a new Blender
importer silently promising a preview that does not exist.

**Verified headlessly** (three's own loaders, run in Node against a hand-built GLB, an OBJ and
an ASCII STL): each parses from an `ArrayBuffer` sliced exactly the way the IPC path slices
it, the triangle count comes back right, and a mesh authored at `translation: [5, 9, -4]`
re-seats to `y = 0.0000, x = 0.0000`. FBX, PLY and Collada are wired the same way but have not
been run against a real file.

## Stage 2 — ComfyUI bridge (`src/comfyBridge.ts`)

Local image generation on the owner's GPU. **No API key, no per-image bill.**

```ts
await window.shadow.forgeComfyStatus();  // { running, url, installPath, checkpoints, vramFreeMb }
await window.shadow.forgeComfyStart();   // spawns the launcher, waits for /system_stats
await window.shadow.forgeImage({ prompt: "a rusted oil drum", assetMode: true });
```

**Verified 2026-07-30:** ComfyUI 0.9.2 auto-started in 24 s; a 1024² SDXL image took 24 s on the
RTX 4070 SUPER.

### Why the graph is built in code

A saved `.json` workflow hard-codes a checkpoint filename. The moment a model is added or renamed it
fails with a node error that reads like a Shadow bug. The bridge queries
`/object_info/CheckpointLoaderSimple` for what is actually installed and builds a plain-node graph
(`CheckpointLoaderSimple → CLIPTextEncode ×2 → EmptyLatentImage → KSampler → VAEDecode → SaveImage`)
that runs on a stock install with **zero custom nodes**, for SD1.5 and SDXL alike.

### `assetMode` — the difference between a picture and an asset

Image→3D wants **one object, whole, centred, evenly lit, plain background**. A cinematic shot
reconstructs the background too; a cropped object reconstructs as an amputated one.

Two things are applied automatically:

1. **Framing language.** Diffusion models largely ignore "entire object visible" but respond well to
   distance vocabulary — hence `wide shot, zoomed out, object small in frame, generous empty margin`.
   This was changed *because* the first test cropped the subject.
2. **Checkpoint-family tokens.** Pony/Illustrious/NoobAI checkpoints are trained with an explicit
   quality-score vocabulary and produce markedly worse output without a
   `score_9, score_8_up, score_7_up` prefix. `modelTuning()` adds it. Documented model behaviour, not
   superstition.

Resolution is also picked from the checkpoint name: SDXL-family → 1024², otherwise 512². Running
either at the other's native size is the most common cause of melted output.

### ⚠ The installed checkpoints cannot do isolated props

Tested with `assetMode` and full negative prompts on `ponyDiffusionV6XL`:

| Prompt | What came out |
|---|---|
| "a police car" | a police **officer** |
| "an elden ring greatsword" | a **character holding** a sword |
| "a ladybug" | a ladybug perched on an invented vessel |

`person, people, hands` were all in the negative prompt. **Pony Diffusion V6 XL is a character
model**, and `AnythingV5` is pure anime — neither can reliably produce an isolated object, which is
exactly what an asset pipeline needs.

**The bridge is not the limitation; the checkpoint is.** Fixing it is a free download into
`ComfyUI/models/checkpoints/`:

| Model | Size | Why |
|---|---|---|
| **SDXL base 1.0** | ~6.9 GB | Official, general-purpose, safe default |
| **Juggernaut XL** / **RealVisXL** | ~7 GB | Much stronger realism, still SDXL |
| **Flux.1 schnell** (fp8) | ~11 GB + encoders | Best prompt adherence by a distance; Apache-2.0; fits 12 GB |
| SD 1.5 | ~4 GB | Smallest fallback |

All are free. Nothing about the bridge changes — it discovers whatever is in the folder.

### What was actually installed

`flux1-schnell-fp8-e4m3fn` (11.07 GB, `models/diffusion_models/`) + `t5xxl_fp8_e4m3fn` (4.56 GB) and
`clip_l` (0.23 GB) in `models/text_encoders/` + `flux-vae-bf16` (0.16 GB) in `models/vae/`.

> **Do NOT use the all-in-one `flux1-schnell-fp8.safetensors` (16 GB).** It was tried first and is
> unusable on a 12 GB card: everything must be resident at once, so ComfyUI pages weights across PCIe
> every step — **measured at 145 s/step, ten minutes for one 4-step image.** Loaded as separate files
> ComfyUI runs the text encoder, frees it, then loads the transformer, so peak VRAM is ~11 GB. Same
> weights, same output, two orders of magnitude faster. `findFluxSplit()` prefers the split layout
> automatically.

## Stage 3 — image → 3D mesh (`generateMesh`)

**Hunyuan3D 2.1 runs on ComfyUI's *native* nodes** — nothing to install from the Manager:

```
CheckpointLoaderSimple ─┬─ MODEL ──────────────────────────┐
                        └─ VAE ─────────────┐              │
CLIPVisionLoader → CLIPVisionEncode → Hunyuan3Dv2Conditioning (→ positive AND negative)
                                                           │
EmptyLatentHunyuan3Dv2 ────────────────────────────────── KSampler
                                                           │
                              VAEDecodeHunyuan3D → VoxelToMeshBasic → SaveGLB
```

Models: `hunyuan_3d_v2.1.safetensors` (6.86 GB, `models/checkpoints/`) and
`clip_vision_l.safetensors` (1.59 GB, `models/clip_vision/`).

There is **no text prompt** in this graph — the model is conditioned purely on the image. That is why
stage 2's "isolated object on plain white" framing does so much work: a photo with a background
reconstructs the background as geometry.

### ⚠ The vision encoder must be exactly 1024-wide

`Hunyuan3Dv2Conditioning` passes `last_hidden_state` through with **no projection**, and the model
declares `context_dim: int = 1024` (`comfy/ldm/hunyuan3dv2_1/hunyuandit.py`).

Use **`clip_vision_l`** (CLIP ViT-L/14, 1024). **SigCLIP-384 is 1152 and fails**, at sample time,
with:

```
mat1 and mat2 shapes cannot be multiplied (1458x1152 and 1024x2048)
```

which reads like a broken install rather than a mismatched encoder. This cost a full debugging cycle;
`findMeshModels()` now explicitly refuses the SigCLIP family rather than letting it be chosen as a
fallback. Note ComfyUI's own model suggestions point at SigCLIP first — ignore them here.

### Defaults

| Knob | Default | Notes |
|---|---|---|
| `steps` | 30 | |
| `cfg` | 5 | Unlike Flux, this model **does** use guidance |
| `latentResolution` | 3072 | Detail budget |
| `octreeResolution` | 256 | Marching-cubes grid. 512 is very heavy |
| `threshold` | 0.6 | Lower = fatter surface |

Expect ~200k triangles out. **Always** run the result through stage 1 with a `--target-tris` budget
before it goes near a game.

### Hardware this was designed against

RTX 4070 SUPER (12 GB), 63 GB RAM, Python 3.10.9, torch 2.7.1+cu118 with CUDA available.
ComfyUI and stable-diffusion-webui are already installed under `C:\Users\you\Documents\`.
That is enough to run TRELLIS or Hunyuan3D-2 locally.

### Honest limits

Generative 3D is **good for props and bad for architecture.** Barrels, crates, statues, rocks,
weapons, a carousel horse — good. Walls, floors and buildings need *modular* geometry: clean
topology, exact dimensions, edges that tile and snap. Generative models produce lumpy, non-manifold
meshes at arbitrary scale with messy UVs, and will not give you a wall that lines up with the next
wall. Plan for structure to be authored or parametric, and let the forge handle props.
