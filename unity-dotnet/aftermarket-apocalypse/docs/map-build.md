<!-- Split out of CLAUDE.md on 2026-08-01. The text below is copied
     byte-for-byte; CLAUDE.md now carries a one-line index entry for each
     landmine here. Read this file before touching the systems it covers. -->

# The map — kit, seeding, baking, and the phase builders

Covers `MapKitBuilder`, `MapSeeder`, `MapBake`, `PropDresser`, `SceneShot`,
the kit materials, and the Phase 4–7 placement passes.

> ### * THE PLAYER SPAWNED INSIDE A WALL FOR SEVEN PHASES
>
> Found on 2026-07-30 by a clearance audit written for an unrelated review point.
> `MapSeeder` typed the player start at cell **(6, 1)** and `BuildCover`
> independently placed a `Kit_Wall_4_Half` across that same cell — bounds
> `(22, 0, 3.75)` to `(26, 2, 4.25)`, with the start at its exact centre. Two
> placements, both written in cell coordinates, and nothing ever compared them.
>
> The `y` was wrong too, for a separate reason. `CharacterController.center` is
> `(0, 0.9, 0)` over a height of 1.8, so **`transform.position.y` is the FEET**,
> not the capsule centre — `y = 1.2` floated the player 1.2 m over a floor at 0.
>
> **111 tests passed the entire time and 16 map screenshots looked correct**,
> because every check that existed tested the POINT. Only the CAPSULE fails. This
> is the point-vs-volume class, the same shape as the Law 7 collider bug recorded
> below: a guard that measures something adjacent to what it guards.
>
> Fixed at source in `MapSeeder` (cell 5, `y = 0.05`) **and** in the existing
> scene by `Tools > Aftermarket > Fix Player Start` — the seeder is one-shot, so
> fixing its literal does not fix the scene that already exists.
> `Map_PlayerDoesNotStartInsideStructure` was verified to fail on the committed
> scene, naming `Kit_Wall_4_Half_Mesh`.
>
> **The first repair produced a wrong answer and made it permanent.** It searched
> outward from the CURRENT position and offset only x and z, so the probe kept the
> broken start's `y = 1.2` — and `NavMesh.SamplePosition`'s 1.0 m reach could not
> touch the yard navmesh at `y = 0.04`, 1.16 m below. Every ring over flat floor
> therefore failed and the first spot that *could* succeed was raised geometry: it
> landed at r = 10.5 on the loading-dock ramp, `(34.14, 0.287, 6.72)`. Then, since
> the next run started from there, the ramp sampled fine and the tool reported
> "already clear" — self-sealing. The `y <= 0.5` guard added after an earlier
> "9 m away at y = 0.87" round did not touch this class; it only lowered a ceiling
> the ramp's 0.237 still fits under.
>
> The search is now anchored to **`MapSeeder.PlayerStart`**, a public constant, so
> the probe y is floor level and the repair converges to the same answer every
> time. It lands at `(20.00, 0.09, 4.00)` — 0.04 m from design, on navmesh at
> 0.040. `Map_PlayerStartsWhereItWasDesignedToOnFlatGround` asserts both *near the
> design position* and *at ground level*, because either alone passes on the ramp.

## Phase 3 art route — per §17's rewritten art box (spec updated 2026-07-30)

§17 no longer asks for a decision and no longer allows buying a kit. **Budget is
zero.** Three layers:

| Layer | How it gets made | Why |
|---|---|---|
| **Structure** — walls, floors, ramps, doorways, stairs | A modular kit on a fixed grid, built through the **ProBuilder scripting API** in an editor tool, so every piece stays ProBuilder-editable by hand afterwards | Architecture has to tile and snap, so it cannot be generated. Building it through the API rather than by hand means it is reproducible and diffable; ProBuilder-editable means it is still hand-tunable |
| **Surface** — the actual look | The **432 PBR material sets in `ShadowVultureMaterialPack/Material Pack URP/`**, copied in as a curated subset. §17: quality lives in the material, not the mesh | Real 4-map PBR that responds to light, versus the old build's painted greyscale with no normal maps |
| **Props** — barrels, wrecks, debris, shrine, machine housings | Generated locally and free: Flux → Hunyuan3D via ComfyUI, then Blender for triangle budget / UV / normal bake / LODs. **Built: `Tools/forge/forge_props.py`, 6 props made.** | This is exactly what the generator is good at |
| **Sky** — ambient light | A free CC0 HDRI from polyhaven.com | Image-based lighting with real colour and direction, no attribution needed |

### Two things §17's new art box gets wrong — checked, both confirmed

The box is right about the plan and wrong about two facts. Do not follow it on these:

1. **The material pack is not in this project.** §17 says
   "`Assets/ShadowVultureMaterialPack/Material Pack URP/` — 432 PBR material sets
   already in this project". It is in the **old** project,
   `../Game creation/Zombies/Zombies Unity/Assets/ShadowVultureMaterialPack/`.
   432 `.mat` files across 405 texture folders, **580 MB**. Copying all of it into
   a git repo is not sensible; copy the sets a map actually uses, **with their
   `.meta` files**, so the material→texture GUID links survive the move.
2. **Asset Forge's Unity target emits GLB, and Unity 6 cannot import GLB.**
   §17 says the Unity export target gives you a GLB "ready to drop into
   `Assets/`". It is not. Re-verified for this spec revision: there is no glTF
   importer in `Packages/manifest.json` and none in Unity 6000.3.1f1's
   `BuiltInPackages` (only `com.unity.textmeshpro`). A `.glb` lands as an
   unrecognised `DefaultAsset` with **no import error at all**. Export **FBX**
   from the pipeline, or add a glTF package first.

What still applies to the structure kit, from §14 — these are the ⚙ laws and they
do not care how the geometry was made:

- **Law 7** collision must be ≥ 0.5 m thick even when the visual is thin, or the
  0.12 m navmesh voxel misses it and zombies walk through the fence.
- **Law 6** stacked clutter keeps ONE collider, or colliders become navmesh
  stairs and zombies climb junk piles.
- **Law 13** bake the navmesh at 0.12 m voxels.
- **Law 8** spawn points need ≥ 2 m clearance, cover ~3 m in front.
- **Law 9** OR every new layer into every camera's `cullingMask`.
- **Law 12** door debris is excluded from the bake and blocks via a carving
  `NavMeshObstacle` — already implemented in `BuyableDoor`.

Laws 4, 5, 10 and 16 are marked ▢ in the spec (artifacts of the retired
primitive-kitbashing method) and stop mattering once props are real meshes.

Packages already installed for this: **ProBuilder 6.0.8** and **AI Navigation
2.0.9** (`Packages/manifest.json`). Nothing further needs adding for structure
or navmesh.

### Phase 3 progress — structure, map scene and navigation are done

| Layer | State |
|---|---|
| **Structure kit** | **done.** `Tools > Aftermarket > Build Map Kit` (`Editor/MapKitBuilder.cs`) authors 9 pieces into `Art/Kit/` via the ProBuilder API. **GUID-stable but NOT fileID-stable — it refuses to run once the map scene exists.** See below |
| **Materials** | **done.** 19 URP PBR sets, 27 MB, in `Art/Materials/` |
| **The map scene** | **done.** `Tools > Aftermarket > Seed Map Scene` (`Editor/MapSeeder.cs`) → `_Project/Scenes/AshfallDepot.unity`, 397 kit pieces |
| **Navmesh + spawn validation** | **done.** Baked at 0.12 m voxels, DoorDebris excluded, 8 spawn points *derived from* the navmesh |
| **HDRI sky** | **done.** `Tools > Aftermarket > Apply Sky` (`Editor/SkySetup.cs`) — CC0 `belfast_sunset_puresky` 2k, ambient source is the skybox |
| **Props** | **five of six usable, placed in the map.** `generator_unit` still fails. See the DINOv2 root cause below |

**213 EditMode tests, all passing** (the same suites plus `SakuraFangTests` 5 — the weapon-skin guards).

There is also a **PlayMode** assembly now, `Assets/Tests/PlayMode/` —
`ZombieRuntimeDiagnostics`, six *diagnostic* tests that load the map in Play and
photograph a zombie. They assert almost nothing on purpose; they exist because
the editor render of the zombie is perfect and a runtime complaint could not be
reproduced from any static check. `Screenshots/zombie_play/`.

### Look at it before believing it — `Tools > Aftermarket > Capture Map Screenshots`

`Assets/_Project/Editor/SceneShot.cs` renders 16 viewpoints of the map plus a
contact sheet of every prop, and writes them to `Screenshots/` (gitignored). It is
read-only with respect to the scene, so it is safe to re-run while dressing.

```
Unity.exe -batchmode -projectPath . -executeMethod SceneShot.CaptureBatch -quit
```

**No `-nographics`** — that gives a null graphics device and every PNG comes out a
flat fill. The tool errors out rather than writing 16 blank files.

> **`01_player_eye_spawn` was a hard-coded literal captioned as a fact.** Its
> position was `new Vector3(W(6), 1.6f, W(1))` = (24, 1.6, 4) with the caption
> "exactly what the player sees on spawn" — which stopped being true the moment
> the player start moved, and which had been rendering from *inside* a
> `Kit_Wall_4_Half` before that. It was noticed because two renders taken 14 m
> apart came out looking identical. The shot now reads the live
> `Player/CameraHolder` (`Shot.FromPlayerEye`) and `report.txt` says
> "(read from the live Player)". This is the same rule the section below already
> stated about report.txt, applied to the thing that chooses the picture.

**Why it is safe during dressing, specifically.** It writes nothing to the scene
(every object it makes is `HideAndDontSave`), *and* — the part the first version got
wrong — it does not reload. `EditorSceneManager.OpenScene` from a script does not
prompt: it discards unsaved changes and clears the undo stack. So the tool now uses
the map if it is already the open scene, and only otherwise loads it, behind
`SaveCurrentModifiedScenesIfUserWantsTo()`. Screenshotting the live unsaved state is
also what you want mid-session.

**The same trap was set in three places and all three are now guarded.**
`SkySetup.Apply` needed it more than `SceneShot` did, because it also calls
`MarkSceneDirty` and `SaveScene`, so the discarded state was overwritten on disk as
well. `MapSceneTests.OpenTheMap` needed it most of all: pressing Run in the Test
Runner is the routine thing to do, Unity does **not** save the open scene first, and
the suite is what this file tells you to use for verification. All three now use the
open map if it is already open, and otherwise go through
`SaveCurrentModifiedScenesIfUserWantsTo()`. The test setup calls `Assert.Ignore`
rather than proceeding if you decline.

Three habits baked in, all because the first version got them wrong:

- **Every fact in `report.txt` is read from the scene, never asserted.** One line
  used to be a hardcoded string saying the player camera had post-processing off. It
  was true when written and false as soon as `MapSeeder` was fixed, so the report went
  on describing a bug that no longer existed. A report that describes the code instead
  of the scene is worse than no report.

- **A 1 m cyan cube** is placed by raycasting where the camera looks. Texture scale
  is unanswerable by eye; the cube is what turned "the brick looks too big" into
  "a course is 0.217 m and should be 0.086". Hand-typed ruler coordinates put the
  first cube *behind the camera* in a shot that otherwise looked fine.
- **Every render reports mean luma and distinct-colour count.** A blank render looks
  identical to a good one on disk. "1 distinct colour" is the tell.

### What the first render actually showed — and the four bugs it found

Nothing in this project had rendered a frame until 2026-07-30. Everything up to
that point was verified by tests, and tests could not see any of this:

1. **Tonemapping was off** (`DefaultVolumeProfile`, mode None), so every HDR value
   over 1.0 clipped and the sky was a blank white sheet. Now Neutral.
2. **The player camera had no post-processing at all.** A bare `AddComponent<Camera>()`
   gets no `UniversalAdditionalCameraData`, and `renderPostProcessing` defaults to
   **false** — so the whole HDRI-and-tonemapping setup did nothing in play.
3. **The brick was 2.8x life size** — one `k_MetresPerTile = 2f` for the whole kit
   meant each material depicted its subject at whatever size 2 m implied. Now
   per-piece; brick is **0.7 m**, measured against the cube, not chosen.
4. **The ramp and the staircase both ended four metres up in open air.** Nothing was
   ever placed at storey height for them to arrive on.

### The material pack ships every set as a mirror — check this on any set you copy in

Distant walls broke into a bright diamond lattice. Two plausible causes were wrong
and are recorded so they are not re-tried: it is **not** anisotropic filtering (the
textures already had mipmaps, aniso 8 and trilinear, and the PC quality level forces
aniso on), and **not** parallax (removing `_ParallaxMap` changed nothing).

The cause is that every set is in **specular workflow** (`_WorkflowMode: 0`,
`_SPECULAR_SETUP`) with its **`roughness.png` bound to `_SpecGlossMap`**. A roughness
map is mostly bright, so every wall in the project was rendering as a near-mirror and
reflecting the sky — an environment reflection modulated by the normal map, therefore
repeating exactly with the tiling. **Tools > Aftermarket > Condition Kit Materials**
(`MapKitBuilder.ConditionMaterialsForDistance()`) clears it and sets `_SpecColor` to
the dielectric F0. Fixed completely.

Two traps in the *diagnosis*, both paid for:

- Clearing `_MetallicGlossMap` reported "0 materials conditioned" while the file
  still showed a map bound, because in specular workflow the live slot is
  `_SpecGlossMap`. **Read `_WorkflowMode` before touching gloss slots.**
- **`_SpecColor` must not be written as 0.04.** It is a Color-typed property, and in
  a linear-colour-space project Unity gamma-decodes Color properties on upload, so an
  authored 0.04 arrives at the shader as **0.0031** — thirteen times too dark, which
  is specular switched off rather than a dielectric. URP's own `Lit.shader` defaults
  `_SpecColor` to `(0.2, 0.2, 0.2)` for exactly this reason. The code asks for linear
  0.04 and runs it through `Color.gamma`, which lands on **0.2209163** authored. The
  first version of this fix shipped 0.04 and got a fully diffuse map that happened to
  look right because the moire was gone.

- **Three sets ship at `_Smoothness: 0`** — `Concrete`, `Concrete (5)` and
  `Cracked concrete`; `Concrete.mat` also had `_SpecColor` at pure black. Once
  `_SpecGlossMap` is cleared, URP Lit reads that float, and 0 is perfectly rough:
  no specular lobe at all. So the ground was flat paint no matter what F0 sat next
  to it, and a conditioning guard that only ever *lowered* smoothness could never
  fix it. `KitPiece.Smoothness` now states a per-piece value and the tool **asserts**
  it rather than clamping. Because it asserts, do not run it after hand-tuning a
  material — that is why it is a separate menu item and not part of `Build()`.

This is a property of the pack, not of these nine materials — **check
`_WorkflowMode`, `_SpecGlossMap` and `_Smoothness` on any set you copy in.**

> ### ⚠ Nothing is interactable until it is TAGGED and the Player has the component
>
> Phases 4 and 5 shipped two buyable doors, a power switch and seven boarded
> windows, and **not one of them could be used in play**. Two silent omissions:
>
> 1. `InteractionSystem.FindTarget` returns null unless `hit.collider` passes
>    `CompareTag("Interactable")`. The tag existed in `TagManager.asset` and
>    **nothing in the scene carried it**, so every prompt died before reaching
>    `GetComponentInParent<IInteractable>()`.
> 2. The `Player` rig had **no `InteractionSystem` at all** — `MapSeeder` adds a
>    CharacterController, an FPSController and PlayerHealth and stops.
>
> No error, no warning, tests green, feature absent. The barrier test at the time
> asserted only "a Collider exists", with a comment naming the exact failure it
> did not check.
>
> Fixed by `Tools > Aftermarket > Wire Interaction` (`Editor/InteractionWiring.cs`,
> idempotent), by both phase builders now setting the tag, and guarded by
> `MapSceneTests.Map_EveryInteractableIsReachableInPlay`. **The tag goes on the
> COLLIDER**, not merely the object holding the script — `FindTarget` tests
> `hit.collider`, and resolves the script with `GetComponentInParent` precisely so
> a machine can keep its collider on a child mesh.
>
> **`InteractionSystem` fires on `WasPressedThisFrame`, so there is no "hold to
> interact".** `WindowBarrier` originally accumulated `Time.deltaTime` per call
> behind a "Hold [F]" prompt; a full rebuild would have needed ~216 taps at 60 fps.
> It is one press per board now.

### `MapBake` — the checks that were private to one file, and so were skipped twice

`Editor/MapBake.cs`. `PropDresser` had both a navmesh rebake and a spawn keep-out
test, correct and well-commented, **private to itself**. Phases 6 and 7 then put
**thirteen solid colliders** into the same map and did neither, so the navmesh
went on reporting open floor where a 1.8 m perk machine stood, and
`MysteryBox_Warehouse` was hand-typed **2.00 m** from a derived spawn point.

> **Phases 4 and 5 not rebaking is a decision; 6 and 7 not rebaking was an
> omission.** Door debris must *not* be baked (Law 12 — excluded layer, carving
> obstacle) and window barriers block with a runtime `NavMeshLink`. Both builders
> say so in their own doc comments. An ordinary solid machine has no such reason
> and said nothing at all.

Five things live here now, and each exists because something failed:

- **`Rebake`** — with the `CopySerialized`-before-`SaveScene` ordering that stops
  the scene silently turning binary.
- **`FindClearSpot`** — nudges a machine off a spawn, verifying the new spot is on
  the navmesh so "two metres left" cannot mean "two metres left, inside the wall".
  A *search*, not a corrected literal: spawn points are derived, so re-typing a
  coordinate fixes today's scene and re-arms the trap for the next re-seed.
- **`SeatOnWall`** — derives a wallbuy's position *and* facing from the surface
  behind it. `WallBuy_Yard_Pistol` was typed at (24, 1.5, 1.8) facing +Z with the
  nearest wall at z = 4, i.e. *behind* its back face and 1.9 m away: chalk hanging
  in open air. The other two were seated correctly — the signature of a rule
  applied by hand rather than by the tool.
- **`Fix Player Start`** and **`Audit Spawn Clearance`** — see the box at the top.
- **`IsUsable`** — the one predicate `FindClearSpot` runs. It probes the navmesh
  from **ground level** rather than from the caller's y: machines and box markers
  are authored at y = 0.6 against a floor navmesh at 0.04, and the old 0.6 m reach
  left four centimetres of margin, so a marginally different bake would have made
  every lookup start failing.

> **`FindClearSpot` did not validate the coordinate it actually returned.** It
> opened with `if (IsClearOfSpawns(desired)) return true;`, so the navmesh and
> occupancy tests its own doc calls load-bearing ran **only on ring candidates** —
> that is, only when the typed point had already failed the spawn check. Of sixteen
> placements exactly one was ever nudged; the other fifteen kept a literal nothing
> had checked. That mattered most for the three Mystery Box relocation markers,
> because `Relocate` teleports with a bare `transform.position =
> destination.position` and no further test — so the fix for "destinations that
> consume themselves" had shipped as "destinations nobody validated". Turning the
> check on immediately found **`BoxSpot_Yard` inside `Kit_Ramp_4`**. A marker that
> cannot be validated is now **not created at all**, and `Phase6Builder` errors if
> fewer than two survive.
>
> **Re-running Phases 6 and 7 through the fixed predicate moved two more machines,
> and neither had ever been suspected.** `AmmoCrate` was typed at (18, 0.35, 4.5),
> which is **inside `Kit_Wall_4_Mesh`**; `GobblegumMachine` was typed at
> (44, 0.9, 6), which has **no walkable navmesh under it at all**. They now sit at
> (17.13, 0.35, 5) and (47, 0.9, 6), both found by ring search. So the count is:
> of sixteen placements, **four** were wrong and only one of the four (the 2.00 m
> spawn conflict) was catchable by the check that existed.
>
> `Map_FloorStandingMachinesStandOnGroundFloorNavmesh` is what stops that
> regressing, and it reads the **scene**, not the builder. The two tests that
> looked like they covered this each measured one axis of it —
> `Map_SolidMachinesClearTheSpawnKeepOut` measures distance to a spawn and
> `Map_MachinesSitOnTheFloorNotInIt` measures `renderer.bounds.min.y` — and a
> machine standing 0.4 m inside a warehouse wall passes both. It tests the
> **oriented** collider box, not `renderer.bounds`: perk machines carry a yaw, and
> a world AABB of a rotated box is already inflated, so testing that would report
> walls the machine does not touch.

> ### ⚠ Verifying a guard means reverting the SCENE — do not commit in that window
>
> The way these guards are proved is to keep the new code and
> `git checkout HEAD -- AshfallDepot.unity AshfallDepot_NavMesh.asset`, run the
> suite, and confirm the new tests fail naming the real defect. That works, and it
> leaves the broken scene sitting in the working tree.
>
> Commit `f219650` was made inside exactly that window. It carries every code fix
> and a commit message describing them accurately, and the **scene it captured has
> the player still on the ramp at (34.14, 0.287, 6.72) and `BoxSpot_Yard` still
> inside `Kit_Ramp_4`** — so that commit fails its own three new tests.
> **`a695576` is what actually contains the fixed scene.**
>
> Restore from `Backups/` before committing, and **check the scene, not the diff
> stat**: `Assets/_Project/Scenes/AshfallDepot.unity` being listed as changed says
> nothing about *which* version landed. This is the same shape as everything else
> in this file — a check that measures something adjacent to what it guards.

> **`SeatOnWall`'s first version seated the yard wallbuy 0.28 m from the player**,
> because it raycast against `~0` and what it found at 1.9 m was the **player's own
> CharacterController** — a collider like any other, on the Default layer like the
> walls. It reported success. `IsStructure` now requires a `Kit_` ancestor and
> rejects anything under a CharacterController or an `IInteractable`. *A raycast
> that hits something is not a raycast that hit the right thing* — the same shape
> as "a dimension match is not a model match" recorded above.
>
> Its second version then answered with a spot **9 m away at y = 0.87**: the
> navmesh bakes onto the flat top of a crate, and nothing said the player start had
> to be at ground level. Now constrained to `y <= 0.5`, the rule `MapSeeder`
> already applied to derived spawn candidates.

### Phase 7 — perks, Pack-a-Punch, power-ups, and the colour that was nearly useless

Placement again, not new systems. `PerkMachine`, `PackAPunchMachine`, `AmmoCrate`,
`GobblegumMachine`, `PowerUpManager` and `PlayerPerks` all came out of Phase 1
complete and the five perk `.assets` already existed; none of them was in the map.
`Editor/Phase7Builder.cs`, additive under one `Phase7` root, refuses to re-run.

Five perks **one per zone** (Quick Revive 1500 in zone A, Juggernog 2500 and Speed
Cola 3000 in the warehouse, Double Tap 2000 and Stamin-Up 2000 around the
substation), so buying the set means opening the map — which is what makes §12.1's
door prices worth paying. PaP at 5000 in the substation, Ammo Crate 1000 and
Gobblegum 500 in zone A.

**Perks and PaP set `requiresPower`, and that is what finally gives Phase 4's
switch a payoff.** Before this phase the light cascade was the only consequence of
throwing it. The gum machine and the ammo crate are deliberately *not* gated —
§6's table lists neither, and they are the only sinks that exist in the opening
rounds.

> **Machine colours are the entire readability budget, so two must never collide.**
> There is no machine art, so five identical boxes are told apart by colour alone,
> and each perk's comes from its own `PerkDefinition.machineColor` rather than being
> retyped. Pack-a-Punch has no asset, so its colour is typed in the builder — and the
> first version typed **gold `(0.85, 0.65, 0.1)`**, which is `Perk_DoubleTap`'s
> `(1, 0.85, 0.3)`. The map's two most expensive interactables stood a few metres
> apart in the same room in the same colour. It is now violet, which is also BO2's
> own PaP lighting.
>
> **The render is what caught it**, not the tests — `Screenshots/22_pap_substation.png`.
> Nothing about "each machine gets its asset's colour" is false, and no assertion
> about two colours being distinct existed to fail.

**Three further Phase 7 defects, all found in review, all fixed:**

- **The Ammo Crate was buried 0.2 m in the floor.** `BuildBox` anchored with
  `at + up * (size.y * 0.5f - 0.9f)`, which puts a base on y = 0 only when the
  caller passes `at.y = 0.9`. Every caller did except the Ammo Crate, which passed
  0.7. **An anchor formula that depends on a magic constant appearing in the
  caller's data is a trap the data cannot see** — `at.y` is now simply the floor
  the machine stands on.
- **`PowerUpManager` was created *inside* the `Phase7` root.** The phase roots are
  the documented undo unit ("deleting the root undoes the whole pass"), so undoing
  Phase 7 also removed the banner API that Phase 4's PowerSwitch and Phase 6's
  MysteryBox both route through — degrading two signed-off phases with no error
  anywhere. It is a scene root now, and every build log names the shared
  components that sit outside the phase roots rather than leaving it implied.
- **No spawn keep-out and no navmesh rebake.** See `MapBake` above.

### Phase 6 — wallbuys and the box are built but NOT playable yet

3 chalk wallbuys and 2 Mystery Boxes (950 each, §6's table), placed and tagged.
`IInteractable` did its job: neither needed a line changed in
`InteractionSystem` — §3.4's eight-branch if/else and the
`name.Contains("MysteryBox")` string match are both gone for good.

**The blocker is now cleared.** The map had no `WeaponInventory` at all until
this phase added one, and its list was empty until
`Tools > Aftermarket > Build Weapon Rig` filled it — Glock in slot 0 (the
starting weapon), M4 in slot 1. The guard below stays in regardless:

> Both `WallBuy.Interact` and `MysteryBox.StartSpin` **refuse before charging**
> when the slot cannot be delivered. A machine that takes Essence and hands back
> nothing is worse than an absent one, and that is exactly what they would have
> done — `GiveWeapon` returns -1 for an out-of-range slot, and spending first and
> discovering that afterwards is the shape of bug this file keeps recording.

> **`WeaponRigBuilder` destroyed hand work and called itself idempotent.** It
> opened with `DestroyImmediate` on the whole `WeaponHolder` subtree — not
> `Undo.DestroyObjectImmediate`, so not undoable — then `MarkSceneDirty` +
> `SaveScene` in the same call, writing the loss to disk before you could react.
> Follow this file's own instruction to "re-run the tool after editing an asset"
> having replaced the placeholder cube with a real gun mesh, and the mesh,
> materials, transform tuning and any added components were gone and already
> saved. **Idempotent is not the same as non-destructive.** It now matches view
> models by name, reuses them, re-pushes only the asset-derived stats, and reports
> rather than deletes anything left over.

**The view models are scene objects, not prefabs**, because
`WeaponInventory.Equip` switches guns with `weapons[i].SetActive(i == slot)`.
Every stat is pushed from the `WeaponDefinition` asset through `ApplyTo`, so
nothing is typed into a scene component — re-run the tool after editing an asset.
They are sorted by asset name so slot indices stay stable, because Phase 6 writes
slot numbers into each wallbuy.

**View models carry no colliders.** One sitting inside the camera's near plane
would eat the player's own interaction raycast: you would walk up to a door and
be told about your gun. `Map_PlayerHasWeaponViewModelsMatchingTheirAssets`
asserts it.

Two design points worth keeping:

- **The teddy bear is rolled at spin time, not at grab time.** Rolling it when
  the player reaches for the gun means the reward you were shown gets snatched
  back, which reads as the game cheating.
- **Relocation was a one-shot that permanently destroyed itself, and the banner
  lied.** Each box's only relocation target was the *other box's live Transform*,
  so the first teddy bear moved box A exactly on top of box B; from then on every
  candidate sat at distance zero from the box weighing it and `Relocate` found
  nothing **forever**, while `Announce("THE BOX HAS MOVED")` fired unconditionally
  *before* the move was even attempted. Every later bear took 950 Essence, gave no
  weapon, moved nothing, and said it had moved. Fixed with **dedicated empty
  markers** — destinations that cannot be consumed by being arrived at — a rule
  rejecting any point another box occupies, and `Relocate` returning a bool so the
  banner follows the move instead of preceding it. If nothing is free the spin
  pays out a weapon instead: the box never charges for a move it cannot make.
- **A finished spin cancelled a LATER offer.** `SpinRoutine` slept the full 10 s
  grab window and then cleared state if it found `Offering`, with no identity
  check, while `Take` returned the box to Idle without stopping the coroutine.
  Spin at t=0, take at t=3, pay again at t=4 — and at t=12.5 the *first* coroutine
  wiped the second purchase's weapon, six seconds into a window the prompt
  advertises as ten, with the Essence already spent. An `offerToken`, bumped on
  every spin and every take, is what makes the timeout belong to an offer rather
  than to the box.
- **Two boxes minimum**, each the other's relocation target. §1622 asks for
  relocation, and a single box has nowhere to go, so the bear would just eat
  950 Essence.

Both classes resolve the inventory with `FindFirstObjectByType<WeaponInventory>()`,
not `FindGameObjectWithTag("Player")` — **the Player object in this map carries no
tag**, so the tag lookup returns null and the box would have rolled slot 0 every
time, recreating §1129's "always granted slot 1" placeholder exactly.

### Phase 5 — window barriers, and why they are NavMeshLinks

§17 calls this "the round-one loop" and §1623 says flatly that it "doesn't
exist" in the old build: *boarded windows zombies tear down and you rebuild for
+10 per board*. Before wallbuys and before the box, repairing barriers is the
entire early-round economy.

`Scripts/Map/WindowBarrier.cs` + `Editor/Phase5Builder.cs`. Seven windows, six
boards each, so a full rebuild is worth **60 Essence**.

> **The load-bearing design fact: a boarded window was never a navmesh route.**
> A `Kit_Wall_4_Window` opening runs from **1.0 m to 2.6 m** off the floor, so
> nothing was ever pathing through it, boarded or not. Carving a
> `NavMeshObstacle` there — the obvious move, and what the doors do — would block
> a route that does not exist and change nothing at all.
>
> What actually makes a window an entrance is a **`NavMeshLink` across the sill**,
> `enabled = false` while any board remains and enabled when the last one comes
> off. Repairing re-disables it, because otherwise boarding a window back up is
> cosmetic — exactly the complaint §1623 makes.

**Barriers are found, not typed.** `Phase5Builder` walks the scene for
`Kit_Wall_4_Window` prefab instances and derives each barrier's position and yaw
from the piece it sits in. Seven hand-written coordinate sets would be a second
source of truth that drifts the first time a window moves.

**The zombie hook is guarded.** `EnemyAI` attacks a barrier only when
`WindowBarrier.All.Count > 0` **and** there is no complete navmesh path to the
player. Without that second test the horde would chew a window while an open
door stood ten metres away, which reads as broken AI rather than as pressure on
the window. On a map with no barriers the whole block is a no-op.

The boards have **no colliders** — they are visual state. One trigger collider on
the barrier root is what `InteractionSystem` raycasts, so a half-torn window can
still be aimed at and repaired, which is precisely when you need to.

### Phase 4 — the gameplay was already written, this was wiring

`BuyableDoor`, `PowerSwitch` and `EnableWithPower` all came out of Phase 1
complete, Law 12 and Law 9 included. Phase 4 was placing them where `MapSeeder`
deliberately left holes. `Tools > Aftermarket > Build Phase 4 (Doors + Power)`
(`Editor/Phase4Builder.cs`) is additive under one `Phase4` root and refuses to
re-run, same contract as `PropDresser`.

- **Two doors on the zone divider at z = 18**, costing **750** and **1000** per
  §12.1. The debris fills the `Kit_Wall_4_Doorway` opening exactly — 1.8 x 2.4 m,
  which is `Grid - legWidth*2` by `WallHeight - ledgeHeight`.
- **The third opening stays free.** Zone B to zone C at z = 9 has no door, on
  purpose: it is what stops the two halves being dead ends.
- **One power switch** inside the substation, plus **nine `EnableWithPower`
  point lights**. Without lights the switch is a prompt that changes nothing, and
  §12.2 calls the cascade the signature moment.

> **It does NOT rebake the navmesh, and must not.** That is the whole of Law 12:
> the debris sits on the `DoorDebris` layer, which `MapSeeder` excluded from the
> bake, and blocks with a carving `NavMeshObstacle` that `BuyableDoor.Awake` adds
> and disables the frame you pay. A door left on the Default layer would bake as
> **solid floor**, making the doorway impassable before it was ever bought, with
> no error anywhere. `Map_DoorDebrisIsExcludedFromTheNavmeshBake_Law12` and
> `Map_DoorwaysArePassableInTheBakedNavmesh` guard both halves.

### Props are placed by `PropDresser`, and the owner asked for that

> **Owner's decision, 2026-07-30, superseding "dressing is entirely by hand" for
> the first pass only:** asked what to do after the prop scale was fixed, the
> answer was *"Fix scale, then place them."* So `Tools > Aftermarket > Dress Map
> With Props` exists and has run once, placing 22 props.

It is **additive and reversible**, which is what keeps §12 intact: everything goes
under one `Props` root, the tool refuses to run if that root exists, and deleting
the root undoes the entire pass. It does not touch structure, spawn points or the
`NavMeshSurface`. `MapSeeder`'s one-shot rule is untouched.

Two things it gets right that cost a round of review:

- **Spawn keep-out is checked against the LIVE scene**, not against the placement
  list. Spawn points are derived, so their coordinates are unknowable when writing
  coordinates by hand; one crate did land on one and was skipped automatically.
- **It rebakes the navmesh, then writes the data back into the existing
  `.asset` before saving.** `BuildNavMesh()` hands back a fresh in-memory
  `NavMeshData` and drops the old reference, so saving in that state embeds it and
  silently forces the scene to binary — the same trap `MapSeeder` documents.

> ### ⚠ Never build a BoxCollider from `InverseTransformVector(worldBounds.size)`
>
> This shipped and was wrong on 19 of 22 props. A `Renderer.bounds` is world
> **axis-aligned**: for a rotated prop it is already the inflated box enclosing the
> rotated mesh. Inverse-transforming its *size* rotates that inflated box a second
> time, which is not a coordinate conversion of an extent. At yaw 44° it collapses
> an axis — the 0.64 m barrel got `m_Size.x = 0.0224`, a 22 mm plate. Eight of
> twenty-three colliders came out under the 0.5 m Law 7 floor.
>
> It is not cosmetic. The `NavMeshSurface` bakes from `PhysicsColliders` at 0.12 m
> voxels, so a 22 mm box is thinner than one voxel and **carves nothing** — the prop
> looks like cover and blocks no one, while an over-inflated one blocks empty air.
>
> Build the box from **local** bounds: push each mesh's local-bounds corners through
> to the root's local space and take their AABB (`PropDresser.LocalBounds`).
>
> **And the Law 7 guard read the wrong variable.** It measured the world AABB
> (~0.9 m on the barrel's thinnest axis, comfortably passing) instead of the
> `box.size` it had just written, so it stayed silent through all eight failures.
> A guard that reads a different variable from the one it guards is not a guard —
> the same class this file keeps recording.

### The map scene is seeded ONCE and never rebuilt

> **It was re-seeded once, on 2026-07-30, on the owner's explicit call.** The first
> seed had no roofs at all (`Kit_Ceiling_4x4` was in the kit and never placed), a
> ramp and staircase that dead-ended four metres up in open air, and a Zone A that
> was 56 x 20 m of empty yard. None of that is fixable by dressing, and all of it
> lives in `MapSeeder`. Nothing was lost: git was clean and the scene was still
> byte-identical to what the seeder had written. **The rule stands from here** — the
> scene now has roofs, verticality that arrives somewhere, and a spawn yard with
> shape, and the next thing that happens to it is hand dressing.

The current seed builds: walkable roofs on the warehouse and substation at storey
height, each reached by an **interior** staircase climbing out through a hole in its
own roof; skylight holes so the interiors are not sealed boxes; a guard office and a
raised loading dock with a ramp, breaking Zone A's long axis. 397 kit pieces,
7 derived spawn points.

**The staircases are inside on purpose.** An outside staircase landing beside a
building puts the agent on a 0.5 m wall top, and a 0.5 m ledge carries **no navmesh
at all** once the 0.5 m agent radius is eroded from both sides. It would look
connected in the editor and be unreachable in play. Inside, the stair's top edge
shares a full 4 m boundary with a roof slab.

**A roofed room must be at least THREE cells deep to take a staircase.** A
`Kit_Stairs_4` fills one whole cell and is entered from its low end only —
`buildSides` makes its flanks solid. In a two-deep room every cell touches both the
front and the back wall, so the stair's foot is buried in a wall and its head lands
over the eaves. The guard office was built two deep, and the result was a stair that
baked navmesh nobody could stand on plus a 12 x 8 m roof island nobody could reach.
Three deep puts the stair in the middle row.

**Back it up before you delete it.** `Backups/` (gitignored) holds a copy of the
scene from before each re-seed. Deleting a scene is not undoable, and if it has
uncommitted hand dressing then git cannot get it back either.

Owner's decision, 2026-07-30: a one-shot seed, then hand dressing.
`MapSeeder.Seed()` **refuses to overwrite an existing scene** and says so. That is
the whole design — §12 retired the old builders partly because the scenes were
build outputs and "the next build wipes any hand edit". To start over you delete
the scene file yourself.

Scope is exactly §17's Phase 3: geometry + navigation. Buyable doors and power
are Phase 4, wallbuys Phase 6, perks and PaP Phase 7 — the blockout deliberately
leaves the openings and zone shape those will attach to, and places none of them.

**Spawn points are derived, not typed.** Hand-picked coordinates were tried and
one was quietly wrong — 0.97 m of walkable surface against Law 8's 1 m, which
reading the numbers would never have caught. The seeder now scores every cell
centre against the baked navmesh and keeps only points that are on the navmesh,
have enough surrounding walkable area, and have a **complete path to the player**.
A Law 8 violation can no longer be written into the scene.

### Two scene-serialization traps — both cost real time, both now tested

1. **Inline `NavMeshData` forces the whole scene to binary.** `BuildNavMesh()`
   leaves the data unsaved; if the scene is written while it is still ownerless
   the data is embedded, and that **silently overrides `ForceText`** — you get a
   ~1 MB binary scene that cannot be diffed or merged, in a project where every
   other asset is YAML. `AssetDatabase.ForceReserializeAssets` does **not** undo
   it. The fix is ordering: create the `.asset` **before** `SaveScene`.
2. **A live `ProBuilderMesh` rebuilds a private mesh per instance.** 341 placed
   pieces embedded **341 separate meshes** into the scene. `MapSeeder` strips
   `ProBuilderMesh` from placed instances and repoints them at the kit's shared
   `.mesh` assets. Trade-off, deliberate: instances cannot be vertex-edited in
   place, but the kit *prefabs* stay fully ProBuilder-editable and editing one
   updates every instance. Moving, rotating, deleting and re-materialing
   instances — what dressing actually consists of — all still work.

`MapSceneTests` asserts the scene starts with `%YAML` and contains no `pb_Mesh`,
so neither can come back silently.

**The grid contract** — anything placing kit pieces must obey it:
4 m cells, 4 m storey height, 0.5 m thickness. A piece's root origin is the
**bottom-centre of its footprint**, except `Kit_Floor_4x4`, whose origin is its
**walkable top surface** so floors sit at y = 0. Nothing overhangs its cell.
`MapKitTests` enforces all of that.

### Build Map Kit is GUID-stable but NOT fileID-stable — it now refuses by default

`PrefabUtility.SaveAsPrefabAsset` mints **fresh local fileIDs for a prefab's child
objects** every time. Verified on this kit: rebuilding `Kit_Wall_4_Window` reassigned
all four of its children. Asset *paths* and GUIDs are fixed, so anything referencing a
piece keeps working — but the scene stores per-instance overrides against those child
fileIDs, including the `m_Mesh` re-point `MapSeeder` writes on every placed piece.
Orphaned overrides are dropped silently on the next save. No error, no undo.

So there are now three separate tools instead of one:

| Menu item | Touches | Safe to re-run mid-dressing? |
|---|---|---|
| **Build Map Kit** | prefabs + `.mesh` assets | **No — it refuses and says why** |
| **Build Map Kit (FORCE)** | same | Only if you re-seed straight after |
| **Condition Kit Materials** | the 9 shared `.mat` assets **and their texture importers** | **No — it overwrites surfacing. Geometry is safe; hand-tuned materials are not** |

> ### ⚠ Condition Kit Materials is not a read-only tool
>
> An earlier version of this table said it touched "`.mat` properties only" and was
> safe once the map exists. Both halves were wrong, and they contradicted the
> paragraph above about asserting smoothness.
>
> It **asserts** rather than clamps: `ConditionMaterialsForDistance()` unconditionally
> writes `_Smoothness` and `_SpecColor` and clears `_ParallaxMap`, `_SpecGlossMap` and
> `_MetallicGlossMap` on the nine shared kit materials — the ones **397 placed
> instances render with**. `ApplyAnisotropicFiltering()` then calls
> `TextureImporter.SaveAndReimport()`, so it rewrites texture import settings too, not
> just material properties. It ends in `AssetDatabase.SaveAssets()`. **There is no undo.**
>
> What it *cannot* do is disturb geometry: it mints no fileIDs, so unlike Build Map Kit
> it can never orphan a per-instance override. That is the only sense in which it is
> the safe one of the three.
>
> Run it to establish the baseline, then hand-tune. Not the other way round.

The material conditioning used to run at the end of `Build()`, which meant a geometry
change also re-asserted surfacing over shared `.mat` assets. Splitting it into its own
menu item means an unrelated kit rebuild no longer silently reverts material work —
but running *this* item still will, by design.

**Four ProBuilder/Unity traps already paid for** — do not rediscover them:

0. **A ramp built as a rotated slab has a step at its foot.** Tilting a 0.5 m slab
   about its own centre lifts the low end by `Thickness * cos(angle)` = 0.45 m —
   over the navmesh step height, so no zombie can get onto it, and over
   `CharacterController.stepOffset`, so nor can the player. Every kit test passed
   on it, because every kit test measures **bounds**, and its bounds did start at
   y = 0. `Kit_Ramp_4` is now a true wedge, made by collapsing a cube's two low-end
   top corners (which keeps ProBuilder's winding, so normals cannot silently
   invert), and `Kit_TheRampsWalkableSurfaceStartsOnTheFloor` reads the vertices
   rather than the bounding box.


1. `PrefabUtility.SaveAsPrefabAsset` does **not** pull a ProBuilder-generated
   mesh into the prefab. You get a prefab with a **null `sharedMesh`** — no
   geometry, no collision, **no error**. ProBuilder only saves meshes itself
   when the experimental `meshesAreAssets` preference is on, and it is not.
   `MapKitBuilder.PersistMeshes` writes the `.mesh` assets explicitly.
2. `ShapeGenerator.GenerateDoor`'s `depth` is the **full** thickness, not a
   half-extent. Passing `Thickness * 0.5f` gives a 0.25 m wall — under the
   Law 7 floor.
3. `ShapeGenerator.GeneratePrism` is a **roof** (it has a peak), not a wedge.
   The ramp is a rotated slab whose length is solved so the rotated footprint,
   thickness included, is exactly one cell.


---

## Kit modules cut from a downloaded .blend (2026-08-21)

`Corridor__Stairs.blend` (455 MB, 27 meshes, 351k tris, all 114 textures **packed**)
became eight kit prefabs under `Art/Kit/Modules/`, on the same 4 m grid as the nine
hand-authored pieces. Two tools do it and both are measured rather than trusted:

| Tool | Job |
|---|---|
| `Tools/map/blend_probe.py` | read a .blend before believing anything about it |
| `Tools/map/kit_cut.py` | slice it to the grid, build collision, convert materials |
| `KitModuleBuilder` (`Tools > Aftermarket > Build Kit Modules`) | FBX + manifest → prefabs |
| `KitModuleShot` (`Tools > Aftermarket > Shoot Kit Modules`) | put them back together and photograph it |
| `KitModuleTests` | 8 tests, the contract above |

**It does NOT go through `MapKitBuilder`, and must not.** That tool refuses to run
once the map scene exists, because `SaveAsPrefabAsset` mints fresh child fileIDs and
the dressed scene's per-instance overrides are then dropped silently. `KitModuleBuilder`
only ever creates new assets, so it cannot orphan an override on one of the nine.

### What the asset actually measured

Worth recording, because it is why this asset was worth using at all: its
floor-to-floor is **exactly 4.00 m, twice** — the project's own storey height — so it
drops onto the grid with no rescaling. Its stairs are 19 risers at 0.160 m rise,
30.7°, comfortably inside Unity's 0.30 m `stepOffset` and the 45° navmesh slope
limit. The shell is **1,375 tris**; a single decorative cable is 193k and a pipe run
is 73k, which is why `--only Corridor,Wall,Ceiling` exists. The props are still
unimported and belong to `PropDresser`, not to the kit.

### The landmines, all of which shipped working and wrong

0. **The collision proxy is not an optimisation, it is the navmesh.** `MapSeeder`
   bakes with `useGeometry = PhysicsColliders`, so what zombies can walk on is the
   collider, never the render mesh. The downloaded walls are ~0.2 m; Law 7 wants
   0.5 m; so every module gets generated box collision and the render mesh gets
   none.
1. **Thickening a surface "behind its own normal" is wrong for a floor.** A floor
   slab has a downward-facing underside, so that rule grew its collider *upward* to
   +0.3 m — an invisible step running the length of every corridor. Which side is
   "into the wall" is not a property of the face; it is a property of where people
   can stand, so `kit_cut.py` ray-casts the standable set first and thickens away
   from it.
2. **A surface's bounding box fills its own hole.** The ring of faces where a
   corridor ends, or the frame around a doorway, has an AABB that covers the
   opening — so every door in the asset was invisibly bricked up. Footprints are
   rasterised and greedily merged into rectangles now, holes included.
3. **Law 7 is about the thinnest axis, not the surface's own axis.** The top edge of
   a 0.2 m wall rasterises to a 0.25 m strip and is thin *sideways*. Enforcing one
   axis produced boxes 0.25 m across that failed the project's own kit test.
4. **`bisect_plane` un-triangulates the mesh.** Cutting a triangle yields a triangle
   *and a quad*, and the footprint rasteriser reads three vertices per face, so
   surfaces came out speckled and the greedy merge returned 130 boxes per corridor
   cell instead of 14.
5. **The asset has no normal maps.** Every material feeds a greyscale **height** map
   into the Normal input through a Bump node. Copying that PNG into `_BumpMap` is a
   valid binding that decodes grey as a garbage normal and reports nothing;
   `kit_cut.py` traces *what the link passed through* and converts height → tangent
   normal when it came via a Bump node.
6. **`bake_space_transform=False` leaves the axis conversion on the object node.**
   Unity honours that node when you instantiate the FBX — but the **Mesh asset** is
   still Z-up, and these prefabs are built from the mesh. Every module arrived lying
   on its side with the wall height along Unity Z.
7. **Deriving the axis mapping per module is under-determined.** A module whose
   local bounds are symmetric (−2..2 on X) matches +X and −X equally: an AABB
   carries nothing that could tell them apart. Different modules picked different
   signs, which scattered the reassembled corridor across 18.8 m of ground where the
   source is 10.8 m — and **every test still passed**, because collider size and
   cell-fit are both sign-invariant. One export ran with one set of axis settings, so
   `DeriveManifestMapping` intersects the candidates across all modules and refuses
   unless exactly one survives.
8. **UVs live on loops, not on vertices.** Rebuilding a mesh vertex-by-vertex drops
   every texture coordinate. The modules came out geometrically perfect and
   completely untextured, with correct materials, correct textures, and no surface
   to put them on. Nothing errored. The tell was the render: 41 distinct colours
   instead of 224.

### Two checks that caught what the tests could not

**Reassembly is a measurement.** `KitModuleShot` puts every module back at its own
cell and reports the combined footprint: **10.80 × 12.32 m**, exactly the source
asset's own dimensions. That number is what caught landmine 7 — the tests were all
green at the time.

**Tri counts must sum.** Every run prints the per-module triangle counts, and they
sum to the post-bisect total exactly (1,673). Nothing is lost or duplicated at a
seam, and a seam is invisible in a wireframe.

### The subway is a set-piece, not a module set

`Subway_Apocalypse.blend` (96 MB, 1,018,840 tris) measures 27.4 × 59.0 × 10.3 m —
6.85 × 14.75 cells. Cutting it to the grid yields **50-odd fragments**, some with 120
collider boxes, because most of it is one large hall rather than tiling architecture.
Its value is different: the station as a single zone, plus separable props — the
**train alone is 833,300 tris**, the rubble 105k, against a 62,784-tri shell. Its own
stairs are 0.295 m rise, i.e. **0.005 m inside** Unity's default `stepOffset`, so any
downscaling makes them unclimbable.


---

## Ashfall Metro — the second map, and the first authored as DATA (2026-08-21)

`Tools > Aftermarket > Build New Map (Ashfall Metro) - EVERYTHING` builds a complete
round-based map from a table of rectangles: geometry, doors, power, barriers,
wallbuys, boxes, perks, Pack-a-Punch, validated spawns, navmesh, the Systems root,
then a verification pass. Six zones, 386 kit pieces, the eight imported
corridor/stairwell modules as one of them.

**It is a BUILD OUTPUT**, rebuilt from scratch every run like `Store.unity`, NOT
seeded-once-and-hand-dressed like `AshfallDepot`. Hand edits to it are lost. That is
the trade for being able to move a room by editing one line.

### The reusable half

Three new files, and none of them know anything about this particular map:

| File | Job |
|---|---|
| `MapPlan.cs` | rooms as rectangles on the 4 m grid; floors, ceilings and every wall DERIVED from them; an ASCII dump of the plan |
| `MapProps.cs` | every gameplay object a round map needs — doors, power, lights, wallbuys, boxes, perks, PaP, ammo, gum, barriers — built to this project's conventions |
| `MetroVerify.cs` | is everything reachable, and is there a LOOP |

`MapBake.OpenSceneIfNeeded(tag, path)` and `CoreLoopBuilder.Build(scenePath)` are new
overloads: everything those files do is a property of *a playable scene*, and the
project has more than one now. The old entry points are unchanged and still default
to `AshfallDepot`.

**A third map is a new table, not new code.**

### The two checks that decide whether a map is playable

**Reachability.** Every `IInteractable` in the scene must be walkable-to from the
player start, on the baked navmesh. Ten objects failed this on the first run —
including Quick Revive standing in the middle of the spawn room, which cannot be
true. It was the instrument: a machine is a solid box, the bake runs over the top of
it, so the navmesh nearest a machine's own centre is its own ROOF, a 1 m island
joined to nothing. The question is not "is its position on the navmesh", it is "can
a player stand next to it", so `ReachableNear` samples a ring at floor height.

**The loop.** The entire game past round 10 is walking a horde in a circle, so a map
that is a tree of dead ends is not a hard map, it is an unplayable one. "There is a
loop" is exactly "there are two internally vertex-disjoint routes between the spawn
and the far side", which is a max-flow question, so `DisjointRoutes` asks it as one
(Edmonds-Karp over cells split in/out at capacity 1; Menger does the rest). The first
layout scored **1**: everything north of the spine hung off a single doorway. One
extra row of cells joining the maintenance wing to the vault made it 2.

Neither of these is visible in a screenshot, and 230 tests were green while the map
had no loop at all.

### What the imported modules cost at the junction

The corridor modules are real architecture, and real architecture does not tile to a
4 m grid. Three defects, all found by LOOKING after everything measured clean:

1. **A void cell beside a module is a hole in the floor.** The kit does not floor a
   cell the module claims, and the module never floored the part of the cell it does
   not occupy. Fixed by making the whole east strip a room rather than leaving the
   uncovered cells as void.
2. **A module does not FILL its cell.** A 2 m corridor inside a 4 m cell leaves the
   rest with no floor and no ceiling. Reserved cells now get a backing floor 2 cm
   below the walkable plane and a backing ceiling above the module's own — filling
   the gaps without z-fighting the geometry that is there, and 2 cm is far under the
   0.12 m navmesh voxel.
3. **Reserved cells still need EXTERIOR walls.** Skipping them entirely, on the
   reasoning that a module brings its own, let you see straight out of the level at
   the map edge — because the module's walls sit inside its cell and several are
   single-sided planes. A kit wall on the cell boundary sits outside the module's own
   and seals it.

Plus one in the asset itself: several of its walls are **zero-thickness single-sided
planes**, so from behind they render as nothing. `KitModuleBuilder` sets `_Cull` to
0 on the module materials. The collision proxy was never fooled; only the eye was.

### Two instrument bugs, both worth remembering

- **A top-down shot of a roofed building is a photograph of its roof.** The plan view
  uses the camera's near plane as a section cut — and the near plane is measured FROM
  THE CAMERA, so the cut height is `camY - near`. Getting that arithmetic wrong put
  the cut at 6.6 m, above the 3.5 m ceilings, and the "cut plan" showed roofs.
- **`Quaternion.LookRotation(-Y, +Y)` is degenerate.** Looking straight down, Unity
  picks an arbitrary roll, which silently rotated the plan shot so north was not up —
  and the plan shot exists to confirm the ASCII plan it no longer matched.

### The layout

```
 14 ################     A  Platform Hall   spawn, Quick Revive, ammo, pistol wallbuy
 13 #...~~~~~~~~...#     B  West Concourse  Speed Cola, Mystery Box, gum, rifle
 12 #...~~~~~~~~...#     C  Maintenance     the imported corridor + stairwell (M)
 11 #...~~~~~~~~...#     D  North Hall      roof collapsed, open to sky, Juggernog
 10 #...~~~~~~~~...#     E  Plant Room      THE POWER SWITCH, Stamin-Up
  9 #...~~~~~~~~...#     F  Vault           Pack-a-Punch, Double Tap
  8 #####......#...#
  7 #####..........#     Doors: A->B 750, A->C 1000, B->spine 1250,
  6 #..............#            spine->D 1500, D->E 1250, D->F 1250,
  5 #....######....#            service stair 1000
  4 #..........#MMM#
  3 #...........MMM#     Two ways north, so the horde can be walked in a circle:
  2 #..........###M#       A -> B -> spine -> D
  1 #..........###M#       A -> C -> link -> service stair -> F -> D
  0 ################
    0123456789012345
```
