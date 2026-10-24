<!-- Split out of CLAUDE.md on 2026-08-01. The text below is copied
     byte-for-byte; CLAUDE.md now carries a one-line index entry for each
     landmine here. Read this file before touching the systems it covers. -->

# The prop forge — Flux → Hunyuan3D → Blender → FBX

Covers `Tools/forge/`, ComfyUI operation on this 12 GB card, and prop materials.

> ### ⚠ The props rendered as TEAL CHROME because their NORMAL map was their ALBEDO — fixed
>
> `python Tools/fix_prop_materials.py`. This is the third instance of the slot
> binding class in this project, after the ZombieM_02 remap and the Inquisitor's
> `_BaseColor_Map`.
>
> Every prop `.fbx.meta` had **`externalObjects: {}` with `materialLocation: 1`**,
> so Unity rendered each prop through the material *Blender* wrote into the FBX:
> an `FbxSurfacePhong` named `<slug>_bakemat` with `mesh_pipeline.py`'s baked
> **`<slug>_normal.png` hanging off its DIFFUSE input**. There is no albedo step
> in the forge, so the only image it had was the normal map.
>
> A tangent-space normal map is overwhelmingly `(128,128,255)` — flat
> lavender-blue. Read as albedo under a Phong specular lobe, that is a pale blue
> mirror. Nothing errored: the FBX is valid, the material is valid, the texture is
> valid, and it was bound to a slot that means something else.
>
> The fix writes a real URP/Lit `.mat` per prop (`Art/Props/Materials/`), binds
> the normal to **`_BumpMap`** with the **`_NORMALMAP` keyword set** — bound
> without the keyword is the same bug again, so the verify pass asserts the
> keyword and not just the binding — copies the normal in as a `textureType: 1`
> texture, and writes an `externalObjects` remap into the base FBX **and both
> LODs**. `AshfallDepot.unity` needed no edit: it carries no
> `propertyPath: m_Materials` override on any prop instance, so all 22 placed
> props picked the remap up from the importer.
>
> **`_BaseColor` is measured, not typed** — the mean of the masked subject in each
> prop's own Flux reference (`_work/<slug>/<slug>_cutout.png`), averaged in
> **linear** light and re-encoded to sRGB for authoring, because Unity
> gamma-decodes Color properties on upload. Same trap as `_SpecColor: 0.04`,
> other direction. The script **refuses** if the reference image is missing rather
> than inventing a colour, and reads the embedded material name and normal-map
> filename **out of the FBX binary** rather than assuming `<slug>_bakemat` — an
> `externalObjects` entry whose `name` matches no embedded material is ignored
> *with no warning at all*.
>
> **Verified by render, not by the script's own say-so.**
> `Screenshots/props/crate_wooden.png`: the crate is now wood-brown with visible
> plank relief, measured at hue **20.2°**, sat 0.58, against an authored hue of
> 27.0° — the gap is the lighting. It was a pale blue-white chrome box before.
>
> Still true: these props have **no albedo texture and nothing in the pipeline can
> produce one**. A measured flat tint is honest about that, not a substitute for
> it. Colour proper needs Hunyuan3D's paint stage or a camera projection of the
> Flux image.

### ★ THE ACTUAL ROOT CAUSE: the wrong image encoder. Fixed and proven.

**`hunyuan_3d_v2.1.safetensors` ships its own image encoder and the graph ignored
it.** 1,601 tensors: `model` 752, **`conditioner` 439**, `vae` 410. The conditioner
is **DINOv2-large**, not CLIP — `patch_embeddings.projection` is `[1024, 3, 14, 14]`,
`position_embeddings` is `[1, 1370, 1024]` (1 cls + 37x37 patches = **518 px**), and
every layer carries `layer_scale1/2.lambda1`, which is LayerScale and which CLIP
ViT-L does not have.

The old graph loaded a **separate** `clip_vision_l.safetensors` instead. Both are
1024-wide, so cross-attention accepted it and **nothing ever errored** — the DiT just
received embeddings from an encoder it had never been trained against and returned
plausible-looking mush.

ComfyUI already handles this and needs no extra file:

- `comfy/supported_models.py:1214` — `clip_vision_prefix = "conditioner.main_image_encoder.model."` (inherited by `Hunyuan3Dv2_1`)
- `comfy/clip_vision.py:135` — detects `encoder.layer.23.layer_scale2.lambda1` and loads `dino2_large.json`

**The fix is `ImageOnlyCheckpointLoader` instead of `CheckpointLoaderSimple`**, with
`CLIPVisionLoader` deleted and `clip_vision` wired to `["1", 1]`. It returns
(MODEL, CLIP_VISION, VAE), so the VAE stays at output 2.

**Measured, same seed and same input image throughout:**

| Encoder | source tris | dims (m) | shape |
|---|---|---|---|
| CLIP-L, unmasked | 52,648 | 0.008 x 1.012 x 0.786 | an 8 mm sheet |
| CLIP-L, masked | 83,820 | 0.778 x 0.934 x 1.183 | two plates in an L |
| **DINOv2 (bundled)** | **493,792** | **1.416 x 1.424 x 1.969** | **a barrel** |

X and Y within 0.6% of each other and taller than wide — a circular upright
cross-section. `Screenshots/props/_dino_raw_front.png` shows the drum with both
rolling hoops and its top rim.

> **The comment that caused this is the trap in miniature.** It read "Hunyuan3D needs
> clip_vision_l (1024-wide); sigclip is 1152 and dies at sample time." True about
> sigclip, and completely the wrong conclusion. SigCLIP crashing only proved
> 1152 != 1024. **A dimension match is not a model match, and "the other one crashed"
> is not evidence that this one is right.**

### ⚠ Hunyuan3D 2.1 needs `--novram` on this card

Sampling dies with `not enough values to unpack (expected 2, got 1)` unless ComfyUI
batches the positive and negative conditioning into ONE call:

- `comfy/ldm/hunyuan3dv2_1/hunyuandit.py:608` — `uncond_emb, cond_emb = context.chunk(2, dim=0)`, unconditional; the model *requires* both.
- `comfy/samplers.py:264` — `if model.memory_required(...) * 1.5 < free_memory:` — otherwise it runs one cond at a time and `chunk(2)` gets 1.

`Hunyuan3Dv2.memory_usage_factor` is 3.5 and the model is 5.8 GB resident, so on a
12.3 GB card with a normal desktop (browser, Wallpaper Engine, Discord) holding
~5.8 GB there is under 1 GB spare and the check fails every time.

**Start ComfyUI with `--novram`** — weights stream from system RAM, `get_free_memory()`
reports nearly the whole card, and the batch fits. Slower, and it works.
Tried and did NOT help: dropping the latent 3072 -> 2048, and `--lowvram`.

### Masking is still required, and it was never the cause

`forge_props.cutout()` mattes the Flux image before upload — Hunyuan3D 2.x expects
the subject masked, and §17's prompt asks for a white studio backdrop that would
otherwise be part of the shape it reconstructs. It uses **connectivity from the
frame edge**, not a brightness threshold: the backdrop is a soft grey gradient with
a contact shadow, and a fixed near-white cut left most of it opaque while punching
holes in the subject's own highlights. Verified on all six cached images
(`Screenshots/props/_mask_*.png`).

**It was diagnosed as the root cause and it was not.** Masking alone still produced
an L of two plates; the encoder above is what produced a barrel. The full
investigation — including the staged-decimation fix and the `flatness()` guard, both
of which are real and still in — is in `superseded.md`. Kept here because the
mask is load-bearing and someone will otherwise remove it as ineffective.

### The prop forge — built, run, and the traps it cost

`python Tools/forge/forge_props.py` → `Assets/_Project/Art/Props/*.fbx`.
Chain: Flux schnell (image) → Hunyuan3D 2.1 (mesh) → `Tools/forge/mesh_pipeline.py`
(budget, UV, normal bake, LODs, FBX). All local, all free. Every prop's prompt and
**seed** are recorded in `Tools/forge/props.json` — without the seed the input is
unreproducible.

`Tools/forge/mesh_pipeline.py` is a **local copy of Shadow's script, deliberately
patched.** Do not re-sync it from Shadow without re-applying the patch:
`engine_profile()` returned `.glb` for `--engine unity`; it now returns `.fbx`.
The same fix was needed a second time in the **LOD export loop**, which had no
FBX branch and silently produced `name_LOD1.fbx.glb` — the glTF exporter appends
its own extension, so you get LOD files Unity cannot read, named as though it can.

**Four things about this 12 GB card that turn a 2-minute job into an hour:**

1. **`--fast fp16_accumulation` is not optional.** Start ComfyUI with
   `run_nvidia_gpu_fast_fp16_accumulation.bat`, not `run_nvidia_gpu.bat`. Without
   it the fp8 weights are dequantised in `eager` mode (triton is not installed and
   the fast CUDA backend reports itself *disabled*), and a **single** 4-step Flux
   image did not finish in 13 minutes. With it, 4 steps take ~2 seconds. The
   symptom is deceptive: `nvidia-smi` shows 100% GPU at only ~68 W, because it is
   doing millions of tiny serialised casts.
2. **Batch by stage, never per-prop.** Flux fp8 + T5-XXL is ~16 GB against a
   12.9 GB card. Doing flux→mesh→flux→mesh reloads Flux for every prop. Generate
   all the images first, then all the meshes.
3. **Call `/free` between stages.** If Hunyuan3D's weights are still resident when
   Flux loads, Flux loads *partially* and sampling degrades to a measured
   **232 seconds per step** while still looking like it is working. Note `/free`
   returns HTTP 200 with an **empty body** — parsing it as JSON throws.
4. **Model load dominates, not sampling.** A cold first image is ~500 s, almost
   all of it reading weights off disk; every subsequent image in the same batch is
   8–36 s. Budget for the first one and make timeouts generous.

**`forge_props.py` now stops ComfyUI itself when a batch ends**, in a `finally` so a
crash or a Ctrl-C releases the GPU too. `--keep-alive` opts out.

**It only stops a server it actually used.** The forge never *starts* ComfyUI — when
none is running it tells you to start it by hand — so any server it finds is yours.
An unconditional shutdown therefore kills a process the script does not own: with all
six props already built, a bare `forge_props.py` prints "Every requested prop already
exists" and would have taskkilled a server midway through your own generation. The
`BATCH_STARTED` flag is set only once stage 1 begins, and the shutdown is gated on it;
the no-match, already-built and server-unreachable paths print "No batch ran, so
ComfyUI was left exactly as it was found."

**A batch killed from outside does not run its `finally`.** Learned the hard way on
2026-07-30: the batch was terminated by the session ending rather than by Ctrl-C, so
the shutdown never fired and ComfyUI sat holding **11.5 GB of 12.2 GB VRAM at 92%**
until it was killed by hand. If a run disappears, check
`curl http://127.0.0.1:8188/system_stats` before assuming the GPU is free. It also prints a
warning banner with a time estimate *before* starting, because the machine is
unusable for games or streaming while a batch runs.

The obvious implementation is a trap: `taskkill /F /IM python.exe /T` is the right
command on Windows (`pkill` does not match the process, and without `/T` the child
holding the CUDA context survives) — but **the forge is itself python**, so the
by-image-name form kills the script mid-manifest-write. `comfy_pid()` resolves the
single PID listening on 8188 via `netstat -ano` and kills that, verified against a
dummy listener. A stale server otherwise keeps the port **and ~16 GB of VRAM**, and a
"restart" silently talks to the old process.

### Notes from before the forge existed — still true

These are the facts that cost time to establish, kept so they are not rediscovered:

- **Target FBX, not GLB.** Unity 6 ships **no glTF importer**. A `.glb` dropped
  into `Assets/` becomes an unrecognised `DefaultAsset` — no mesh, no material,
  no prefab, and **no import error**. Verified: FBX out of Blender imports
  natively with zero packages. Shadow's `mesh_pipeline.py` returns `.glb` for
  `--engine unity` with a comment claiming "Unity imports GLB/FBX directly";
  that is true of FBX and false of GLB.
- **Copy `mesh_pipeline.py` into this repo**, do not reference Shadow's copy at
  `%TEMP%\<random-hash>\resources\scripts\blender\` — that path is deleted by
  Storage Sense and the hash changes on every re-extraction.
- **Two ComfyUI traps**, both of which fail in ways that look like a broken
  install. Copy the graphs from Shadow's `APPLICATION/src/comfyBridge.ts`:
  Flux schnell is guidance-distilled so `cfg` **must** be 1.0 with 4 steps and
  `EmptySD3LatentImage` (16-channel), not `EmptyLatentImage`; and Hunyuan3D
  needs `clip_vision_l` (1024-wide) — `sigclip_vision_patch14_384` is 1152 and
  dies at sample time with a matrix-shape error.
- A generated mesh arrives at ~200k triangles. Always pass a triangle budget.
- **★ DO NOT DELETE A PROP'S `.meta` SIDECAR. The props are placed now.**
  `PropDresser` put 22 prop instances into `AshfallDepot.unity`, and
  `barrel_rusted`'s GUID alone appears **128 times** in that file. Deleting the
  `.meta` mints a fresh GUID, which does not protect those placements — it
  **orphans** them. Every placed prop becomes a missing-model reference, in the one
  file this project intends hand dressing to accumulate in, recoverable only from
  git.

  This reverses what this section used to say, and the reversal is in the shipped
  code: `clear_stale_outputs(slug)` deletes the slug's `.fbx` and `_LOD*.fbx`
  **and deliberately keeps every `.meta`** (`forge_props.py:698`, and the long
  comment at the call site, `:607`). Before the props were placed, a fresh GUID was
  the safer choice — overwriting an `.fbx` in place keeps the old GUID while the
  geometry changes underneath it, a silent substitution. Once instances exist,
  "same asset, better mesh" is exactly what a re-forge is *for*, so GUID stability
  is the property you want. **If you find yourself running `del *.fbx.meta` before
  a re-forge, or "restoring" the deletion to match an older comment, stop — that is
  the failure this paragraph exists to prevent.**

  The half-regenerated-set problem the deletion was really guarding is handled by
  clearing the whole slug at once, which never needed the sidecars: delete
  `barrel_rusted.fbx` but leave `barrel_rusted_LOD1.fbx` and the LOD used to be
  overwritten in place with stale geometry.

  `clear_stale_outputs` prints how many scene placements are about to change before
  it touches anything, so a re-forge is loud rather than silent — verified, it
  reports `barrel_rusted` at 128 references in `AshfallDepot.unity`. **A bare
  `python Tools/forge/forge_props.py --from-cache` with no slug rewrites every
  prop**; that is survivable *only* because the sidecars are kept. The skip filter
  (base `.fbx` exists → skip) is what protects the non-`--from-cache` path.
  The seed is stored in a `.seed` sidecar next to the cached image, so reusing a
  cached image cannot record a seed that does not reproduce it.
- Cold runs are slow: Flux fp8 + T5-XXL do not both fit in 12 GB, so the first
  image can page for many minutes. Budget for it and make timeouts recoverable.

`Assets/Scenes/SampleScene.unity` is still the untouched URP template scene. The real map is
`Assets/_Project/Scenes/AshfallDepot.unity` — walkable on open (there is a Player rig with a
CharacterController and FPSController), lit, fogged and navmesh-baked, but **not yet dressed**.
Dressing it is a hand job in the editor, by design.
