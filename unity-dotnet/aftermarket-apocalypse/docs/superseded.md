<!-- Split out of CLAUDE.md on 2026-08-01. The text below is copied
     byte-for-byte; CLAUDE.md now carries a one-line index entry for each
     landmine here. Read this file before touching the systems it covers. -->

# Superseded — kept for the failure shape, not for the facts

**Nothing in this file is currently true.** Each box described a real state
of the project and was fixed; they were pulled out of CLAUDE.md on 2026-08-01
because a session reading them as current would act on a map that no longer
exists. The lesson from each is carried forward in CLAUDE.md's
*Recurring failure classes* list.

> ### ✅ FIXED — the currency/UI/rounds gap below is closed. Kept for the lesson.
>
> Everything from here to the end of the ★ box is **the state before
> 2026-07-31** and is no longer true. `Tools > Aftermarket > Build Core Loop`
> put a permanent `ScoreManager`, `GobblegumManager`, `EventSystem`, HUD,
> `PauseMenu` and `EnemySpawner` into the scene under one `Systems` root, and
> `PlaytestRig.cs` is **deleted**. See "The core loop" further down.
>
> The lesson is what stays: **an absence has no properties, so no test that
> inspects a thing can notice that the thing is missing.** 121 tests passed
> while the map had no currency, no Canvas, no EventSystem and no spawner.
> `CoreLoopTests` is written as existence-and-wiring checks specifically to
> cover that class, and several of its assertions check the *second* half of a
> pair — not "there is a promptText" but "the promptText has a font", because a
> TMP component with a null font renders nothing while looking healthy.

> ### ⚠ (HISTORICAL) "Phase done" means BUILT AND TESTED, not PLAYABLE
>
> Seven phases are signed off and **nothing across any of them can be used in
> play**, for two reasons that no test could see because both are absences.
>
> **1. There is no `ScoreManager` in `AshfallDepot.unity`.** `TrySpend` opens with
> `if (instance == null) return false;` (`ScoreManager.cs:71`), so every door,
> wallbuy, box, perk machine, Pack-a-Punch, ammo crate and gum machine **refuses**.
> `Award` returns early the same way (`:62`), so repairing a window board pays
> nothing and you cannot earn your way out of it either. `GameBootstrap` does not
> create one **on purpose** — its own comment says it "does NOT create gameplay
> content (spawners, managers with scene references)".
>
> **2. There is no screen-space UI at all.** The scene contains zero `Canvas` and
> zero `TextMeshProUGUI`, and the Player's `InteractionSystem.promptText` is null.
> `SetPrompt` opens with `if (promptText == null) return;`, so every prompt string
> is computed and thrown away. `GameFeedback` does not supply one — it builds
> **world-space `TextMeshPro`**, not `TextMeshProUGUI`. So you walk up to a machine
> and **no prompt appears at all**, which reads as "this machine is broken" rather
> than "you cannot afford it". That is a worse playtest signal than a refusal.
>
> **Fixing the currency alone is not enough**, and this is the trap worth
> remembering: `GobblegumMachine.Interact` returns false at
> `GobblegumManager.instance == null` (`:23`) **before** it ever reaches
> `TrySpend`, and nothing anywhere creates a `GobblegumManager` — no scene object,
> no `GameBootstrap` branch, no `AddComponent` in any script. Add a `ScoreManager`
> and the 500-cost gum machine still silently refuses.
>
> **The TMP Essential Resources have never been imported into this project.** No
> `Assets/TextMesh Pro/` folder, no TMP Settings asset, and none ships inside
> `com.unity.ugui`. Without them a `TextMeshProUGUI` has no font and no material
> and renders **nothing** while looking healthy in the hierarchy — so
> `GameFeedback`'s world-space popups have never rendered either.
>
> `Tools > Aftermarket > Build Playtest Rig` (`Editor/PlaytestRig.cs`) supplies all
> three — `ScoreManager`, `GobblegumManager`, and a Canvas whose prompt is wired
> into `InteractionSystem` — under one `PlaytestRig` root, importing the TMP
> essentials first and **refusing to build anything if it cannot get a font**.
> Starting Essence is **derived** from the sum of every price in the scene ×3,
> not typed, so it cannot become "not quite enough to reach Pack-a-Punch" the
> first time a price changes. `Remove Playtest Rig` deletes the root and clears
> the one reference that necessarily lives outside it.
>
> **This is scaffolding.** The rig exists so a walk-around can tell you whether
> Phases 4–7 work, instead of telling you sixteen machines are silent.
>
> ### ★ DO NOT repeat the claim that rounds and the HUD are "Phases 8–12"
>
> This box used to say exactly that. **It is not in the spec and never was.**
> `REBUILD_SPEC.md`'s §17 build order lists twelve phases and **none of them is
> enemies, rounds or the HUD**. Phase 5 is *"Barriers / windows — the round-one
> loop"* and Phase 7 is *"Perks, PaP, power-ups, **dogs**"*; both are meaningless
> unless ordinary zombies already work. Phases 8–12 are the skin/Sacred economy,
> co-op, the real backend, trading/IAP and content cadence.
>
> Enemies and rounds are **foundation**, which is why `EnemySpawner.cs` and
> `RoundRules.cs` have existed since Phase 1. They are missing from
> `AshfallDepot.unity` (0 `EnemySpawner`, 0 `EventSystem`, 0 `GameBootstrap`) —
> that is a **gap, not a deferral**, and nothing in Phases 8–12 will fill it.
>
> **The zombie is not something to build either.** `REBUILD_SPEC.md` §1.4 lists
> `ZombieM_02` (1,707 MB, mesh + 14 animation clips) as **Load-bearing** art in the
> project being rebuilt from. It is still on disk at
> `Ai Game/Game creation/Zombies/Zombies Unity/Assets/ZombieM_02/`, with Built-in
> and HDRP prefab variants only — this project is URP, so carrying it over means
> converting its materials. That is the task; authoring a zombie is not.
>
> Saying "that comes later" about work the spec never scheduled is how seven
> phases got signed off on a map you cannot play. Check §17 before deferring
> anything to a phase number.

### The forged props are all unusable — do not place them

`Screenshots/props/` has a picture of each one beside a 1 m cube. The barrel is a
flat **8 mm sheet**, the generator is a bent tube, the obelisk is a slab of rubble
lying flat, the pallet stack is shredded floating fragments, the crate is a lump of
putty. **None has any texture** — the pipeline bakes a normal map and there is no
albedo step anywhere in the chain, so they were never going to have colour.

### The earlier masking fix — real, necessary, and not sufficient

**The cause is that Hunyuan3D was also fed an unmasked image — fixed.** §17's prompt
template asks Flux for "a plain flat white studio background", and that opaque RGB
frame went straight into `LoadImage` → `CLIPVisionEncode` with no alpha and no
background-removal step anywhere in the graph. Hunyuan3D 2.x expects the subject
**masked**; unmasked, the backdrop is part of the shape it is asked to reconstruct
and the reconstruction collapses toward the picture plane. That is why the barrel
came back as a 7.8 mm sheet.

`forge_props.cutout()` now mattes the image before upload. It is done in PIL rather
than with a ComfyUI background-removal node so it needs no custom node installed, is
deterministic, and can be checked offline. Two things it gets right that the obvious
version does not:

- The backdrop is **not flat white** — it is a soft grey gradient with a contact
  shadow running roughly 200–250. A fixed near-white threshold left most of it
  opaque (the first attempt scored the barrel at 81% subject coverage and visibly
  kept the background). The test is "light and neutral" at a much looser tolerance.
- **Connectivity from the frame edge**, not a global threshold, so a white highlight
  *inside* the subject is not punched out. Verified on all six cached images
  (`Screenshots/props/_mask_*.png`): the barrel mattes to a clean silhouette at 39.6%
  coverage, and the pale wooden crate survives intact at 64.5% while the fill still
  correctly reaches through the gaps between its top slats.

**This is not yet proven to fix the meshes** — that needs a GPU run. It is the
identified cause with the fix in place and the mask verified.

**Decimation was blamed for this and decimation is largely innocent.** The raw
Hunyuan3D output was re-measured off `Tools/forge/_work/*/`*`_raw.glb`, which
survived: `barrel_rusted_raw.glb` is **0.0078 x 1.0117 x 0.786 m at 52,648
triangles** — already a 7.8 mm sheet before anything touched it. The Flux reference
image (`_work/barrel_rusted/barrel_rusted.png`) is a perfectly good rusted drum. So
the failure is inside Hunyuan3D, not the budgeting, and the most likely cause is
that it was fed a **white-background RGB image with no alpha**; Hunyuan3D 2.x wants
the subject masked. That is unverified — it needs a GPU run to confirm.

The raw meshes also carry **no vertex colours, no UVs, no materials and no images**
(checked in Blender). So "there is no albedo step" is true but the fix is not "add a
bake" — there is nothing to bake *from*. Colour needs either Hunyuan3D's paint stage
or a camera projection of the Flux image.

Two pipeline fixes are in and tested offline:

- **`decimate_to` is staged**, halving repeatedly instead of one 99.7% collapse.
  Collapse decimation scores each edge against the mesh it currently holds, so a
  single huge cut makes every catastrophic collapse at once, judged against a surface
  it has not yet simplified. A/B on the crate at the same 1,200 triangles
  (`Screenshots/props/_crate_ONESHOT.png` vs `_crate_STAGED.png`): staged gives
  planar faces and defined corners where one-shot gives a rounded pillow. **It does
  not rescue these six** — the 349k-triangle source is itself a blob.
- **`flatness()` + a `degenerate` flag in the result JSON.** Smallest bounding
  dimension over largest; under 0.02 is a sheet, not a prop. The barrel scores 0.008
  and would now be flagged at forge time instead of at screenshot time.

This was previously recorded here as "proven end to end". It was not. The forge
produced six FBX files with plausible triangle counts, valid LODs and
`bakeError: null` — every check that was run is a check that passes on garbage.
Nobody rendered one. That is the whole reason `SceneShot` now shoots a prop contact
sheet.
