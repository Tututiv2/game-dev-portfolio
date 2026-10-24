# Character Forge — modular characters from independently-generated parts

> **Status:** **built and wired end to end** (2026-08-06). Blender assembler, TypeScript bridge,
> IPC, agent briefing and the **Character Forge** Studio tab (☗). Verified in the running app over
> the DevTools protocol: a four-part character assembled through the real IPC into the real
> library. Not yet driven by a human, and not yet fed a genuinely AI-generated part.

## The idea, and the part of it that is load-bearing

The owner's observation: AI character generators do not model a whole person: they **generate each
piece separately and combine them** — eyes, head, hair, arms, torso, shirt, shorts, a sword.

That is correct, and it is how VRoid, DAZ, The Sims and Roblox layered clothing all work. But
"generate the pieces and combine them" is the easy half. Parts produced by separate Hunyuan3D runs
share **nothing** — not scale, not origin, not orientation, not topology. There is no geometry to
match, so there is no weld that produces a clean surface.

**The invariant is the skeleton, not a base mesh.**

A skeleton is a fixed table of bone positions, so *"where does the head go"* has an answer before
any mesh exists. Every part is fitted to the bones it belongs to. Nothing has to agree with anything
except the rig.

### Why generating pieces separately is what makes rigging solvable

This is the real payoff, and it is the opposite of the intuition that per-part generation is merely
an authoring convenience.

Auto-weighting a whole generated body is where these pipelines normally die. Blender's heat-map
solver needs clean manifold geometry; generated meshes have fused limbs, self-intersections and
non-manifold edges, and the solver either fails or produces weights that tear on the first frame of
animation.

**We never run it.** A part in the `head` slot is 100% the `Head` bone. An arm is a linear blend
along a known four-joint chain. A garment inherits its weights from the body part it was conformed
to. Each piece arrives *already knowing its bones*, so weighting is arithmetic instead of a solve.

### Why seams are hidden rather than matched

Two generated parts will never share a cross-section at the neck or the shoulder. So sockets
deliberately **overlap** — `overlap` in the slot table fits a part to 1.04–1.08× its bone chain, and
the excess buries the seam inside its neighbour. Joint-covering geometry (collar, cuffs, belt,
boots) is the art-side answer for the rest. Ordinary kitbash practice, not a compromise.

## Running it

```bash
"<blender>" --background --factory-startup --python character_assemble.py -- \
    --spec spec.json --output character.glb --format both
```

Last stdout line is `__SHADOW_CHARACTER_RESULT__` + JSON — same contract as `mesh_pipeline.py`.
Parse that line and ignore the rest; Blender's chatter is not ours.

```json
{
  "height": 1.75,
  "parts": [
    {"slot": "torso",  "path": "torso.glb"},
    {"slot": "head",   "path": "head.glb"},
    {"slot": "hair",   "path": "hair.glb"},
    {"slot": "arm.L",  "path": "arm.glb"},
    {"slot": "top",    "path": "shirt.glb", "conformTo": "torso", "looseness": 1.06},
    {"slot": "held.R", "path": "sword.glb", "rotation": [0, 0, 90]}
  ]
}
```

| Flag | Default | Notes |
|---|---|---|
| `--spec` | required | The part list above |
| `--output` | required | Extension is set by `--format` |
| `--height` | `1.75` | Metres. Scales the whole bone table — proportions cannot drift |
| `--format` | `glb` | `glb`, `fbx`, or `both` |
| `--fuse` | off | Voxel-remesh the assembly into one watertight mesh. **Discards skinning** |
| `--hide-threshold` | `0.008` | Metres. How deep under a garment a body face must be to be deleted |
| `--no-hide-covered` | off | Keep the body intact under clothing |
| `--selftest` | off | Run the whole path on primitives — no spec, no GPU |

## The slot table is the whole extension point — and it lives in ONE file

Adding a body part is adding a row to **`scripts/blender/character_slots.json`**, not writing
fitting code.

That table is read by `character_assemble.py` **and** served to the UI by `shadow:characterSlots`.
It is a JSON file rather than a Python dict mirrored by a TypeScript const because that mirror is a
drift bug waiting to be written: add a slot on one side only and you get either a slot the UI offers
and the assembler rejects, or one the assembler supports and nobody can reach. Neither failure is
loud. One file, two readers, nothing to keep in sync — `contracts.ts` deliberately contains no slot
list at all.

> The Zod schema on `shadow:characterAssemble` validates `slot` as a plain string rather than a
> `z.enum` for the same reason. Enumerating the ids there would re-introduce the mirror one layer
> down. `characterForge.ts` validates against the real table and names the offending slot.

| Slot | Fit | Weights |
|---|---|---|
| `torso` | spans the Hips→Neck chain | blended along the chain |
| `head`, `hand.L/R`, `foot.L/R` | scaled to a size, centred on one bone | rigid to that bone |
| `hair` | as `head`, one layer out | rigid to `Head` |
| `eye.L`, `eye.R` | socket with an explicit offset | rigid to `Head` |
| `arm.L/R`, `leg.L/R` | spans a 3–4 joint chain | blended along the chain |
| `underwear_top/bottom`, `bottom`, `top`, `outer` | fitted into a z-region of the **assembled body**, then conformed | inherited from the body underneath |
| `held.L/R`, `back` | socket at a bone head; scale is a **multiple of bone length**, so a sword scales with the hand that holds it | rigid |

**Garments are fitted at assembly time and never pre-fitted.** The rig is a stick figure with no
width — a shirt scaled to it would be pencil-thin. Only the body part underneath knows how wide
*this* character is, which is also the answer to "clothing fitted to body A will not fit body B":
a garment is stored as raw generated mesh plus a conform recipe, and refits whenever the body slot
changes.

## Why `OUTSIDE` is the default conform and `tight` is not

A plain shrinkwrap drags every garment vertex onto the body surface. That turns a jacket into body
paint — it destroys the silhouette that made the garment worth generating in the first place.

`outside` moves **only** the vertices that are inside the body, and leaves the rest alone. It fixes
poke-through and nothing else, which is the actual problem. `tight` (`ON_SURFACE`) is available for
genuinely skin-tight items.

Each layer conforms to its own offset (`0.0035 × layer`), so five stacked garments sit on five
surfaces instead of z-fighting on one.

> ⚠ **Two constants here are coupled and both bit.** See landmines **31** (the conform offset must
> be added back into the hide threshold, or the mask silently matches zero faces) and **32** (fit
> snug and let the conform push out — a garment fitted loose never intersects, so an `OUTSIDE`
> shrinkwrap is a no-op indistinguishable from a perfect one).

## Bone names are Unity Humanoid on purpose

`Hips / Spine / Chest / Neck / Head / Shoulder.L / UpperArm.L / …` is the Unity + Mixamo
convention. Unity's avatar mapper recognises them without hand-assignment, which is the entire
difference between "drop the FBX in and it animates" and "map fifteen bones by hand for every
character you generate". GLB and FBX are exported from the **same assembled scene** rather than by
converting one into the other, because conversion is where bone names and axis conventions go wrong.

T-pose, not A-pose: a T-pose gives the arm chain one unambiguous axis to fit along, so no per-part
rotation solve is needed.

## Verified — 2026-08-06

`--selftest` builds primitive stand-ins for ten slots, exports them to GLB at deliberately wrong
scale and 7 m off-origin, wipes the scene, and runs the real path on them. **The point is that it
spends no GPU time**: if the plumbing is broken it is broken for a cylinder too, and a cylinder
takes no minutes to find out. Generation only gets involved once this passes.

```
PASS  armature built                     21 bones
PASS  every part placed                  10 placed
PASS  no unweighted vertices             0 stray
PASS  all parts bound to rig
PASS  all parts fitted near the rig
PASS  head above torso                   head 1.763 vs torso 1.540
PASS  garment inherited body weights     Chest,Hips,Neck,Spine
PASS  hide-mask removed covered faces    4992 faces
PASS  wrote selftest.glb                 724,572 bytes
PASS  wrote selftest.fbx                 556,332 bytes
```

The GLB was then parsed independently of Blender: **1 skin, 21 joints, inverse-bind matrices
present, 10/10 meshes bound to the skin, every primitive carrying `JOINTS_0` and `WEIGHTS_0`**, and
all Unity Humanoid bone names present in the node list.

Two of those checks exist because they failed first — see landmines 31 and 32. A third, "all parts
fitted near the rig", exists because a fitter that does nothing passes every other check: the
stand-ins are exported at `(3, −2, 7)` specifically so that a no-op fit leaves them there.

## Mesh backends are an adapter, not a hardcode

Per the repo convention (`CLAUDE.md`: *providers are swappable behind an adapter layer on purpose*),
the character pipeline is designed against a mesh-backend interface rather than calling Hunyuan3D
directly. The owner's instinct that a second model is worth having is right; the useful split is
**organic vs hard-surface**, not items vs buildings:

| Backend | Good at | Status |
|---|---|---|
| Hunyuan3D 2.1 | heads, bodies, hair, organic props | installed, see `asset-forge.md` |
| TRELLIS | hard-surface — weapons, armour plates, structures | **not installed** |

Buildings remain subject to the standing rule in `asset-forge.md`: generative 3D is good for props
and bad for architecture, because walls have to tile and snap. A second model improves the props; it
does not make a generated wall line up with the next wall.

## Checkpoint choice inverts for characters

`asset-forge.md` records that `ponyDiffusionV6XL` failed the prop test — *"a police car"* produced a
police **officer**, with `person, people, hands` all in the negative prompt.

That is not a bad checkpoint. It is a **character model** doing exactly what it was trained to do,
and for this pipeline it is the correct one while Flux is the wrong one. The failed experiment is
the right tool for the new job. Part prompts still need `assetMode` framing (one object, centred,
plain background) — a part generated with a background reconstructs the background as geometry.

## Roadmap

| Stage | What | Status |
|---|---|---|
| 1 | Canonical rig, slot table, fit / conform / weight / export | **Built + verified** |
| 2 | `src/characterForge.ts` + `shadow:characterStatus/Slots/Assemble` + preload | **Built + verified** |
| 3 | Character Forge tab (☗) — slot list, per-slot generate, result preview | **Built + verified** |
| 4 | Agent briefing so the council author can assemble characters itself | **Built** |
| 5 | Posing / animation retarget, and a part library keyed by slot | Not built |
| 6 | TRELLIS as a second mesh backend behind the adapter | Not built |

## Wired end to end — verified in the running app, 2026-08-06

Driven over the DevTools protocol rather than by hand, per landmine 29:

```
bridge methods present : characterStatus / characterSlots / characterAssemble  = function
characterStatus        : blenderFound ✓  scriptFound ✓  slotTableFound ✓  slotCount 21
characterSlots         : v1, 21 slots, groups body/face/worn/held
bad slot is rejected   : "Unknown slot 'nostril'. Known slots: torso, arm.L, …"
surface mounted        : 4 groups / 21 slot rows / "Assembler ready"
live assemble          : OK — 4 parts | 21 bones | 72,284 tris | 6.17 MB | 2 files | 0 warnings
landed in library      : listed alongside existing forged assets
```

Three seams were only exercised by this pass, and one of them was broken:

- **`--spec` read from a file** — the self-test builds its spec in memory, so the file-reading path
  the TypeScript bridge actually uses had never run. It works, and the spec is written into the
  asset folder as a record of what produced the character.
- **Resource resolution from `dist/`** — both the script and the slot table resolve, and
  `package.json`'s `extraResources` filter was `**/*.py` only, which would have shipped a build with
  the assembler and no slot table. Now `**/*.py` + `**/*.json`.
- **Triangle counts were over-reported.** A part is counted when it is placed, but a garment placed
  later *deletes* faces from the body under it — so the torso reported 5,276 triangles after the
  shirt had removed 2,640 of them. The report is now re-counted after every hide-mask has run.
  That number goes into the library as `finalTris` and onto the UI as the budget the owner decides
  against, so it has to be the mesh that actually shipped.

## Honest limits

- **Fit rules are heuristics over unpredictable geometry and will be wrong sometimes.** Every part
  accepts `scale`, `offset` and `rotation` overrides applied after the automatic fit, so the UI can
  correct a bad fit without anyone editing the slot table.
- **`--fuse` discards skinning.** It resolves every intersection into one closed surface, which is
  what a silhouette, a physics proxy or a triangle-budgeted export wants — and it throws away UVs,
  materials and per-part swapping doing it. Fuse for static exports only.
- **Nothing here has been driven by a human yet.** The assembler is verified against primitives and
  a GLB parser, not against real generated parts or a real engine import.
- **A standalone arm may not reconstruct well.** Image→3D wants a whole, centred, recognisable
  object, and a disembodied forearm is not one. The slot table supports per-limb parts, but the
  first real test should also try `torso` as one piece with only the head, hair and garments split
  out. Test it; do not assume either way.
