<!-- Split out of CLAUDE.md on 2026-08-01. The text below is copied
     byte-for-byte; CLAUDE.md now carries a one-line index entry for each
     landmine here. Read this file before touching the systems it covers. -->

# ZombieM_02 — carrying it across, all three skins

Covers `Tools/import_zombie.py`, `Editor/ZombieImporter.cs`, `ZombieSkinAudit`.

### The zombie — it is carried over, and the URP variant already existed

> **The brief for this work said ZombieM_02 "ships Built-in and HDRP prefab
> variants only". It does not.** There is a `URP/` folder with its own prefabs
> and its own materials, and those materials are on shader GUID
> `933532a4fcc9baf4fa0491de14d08ed7` — **URP/Lit**, the same shader this
> project's own kit materials use. No shader conversion was needed and none was
> done.
>
> The reason everyone reads it as "no URP variant" is that the vendor named the
> URP materials **`Buit-in_ZombieM_02_Body_Skin1.mat`** — their own typo, inside
> a folder called `URP`. They are renamed to `ZombieM_02_Body/Cloth.mat` on the
> way in, `.meta` included so the GUIDs survive.

> ### ⚠ The FBX remaps its materials to the BUILT-IN set even for the URP variant
>
> All three pipelines share **one mesh**, `Mesh/SK_ZombieM_02_Mesh2.fbx`, and
> that model's importer `externalObjects` block points its two slots — `body_`
> and `cloth` — at the **Built-in** materials. The URP and HDRP variants never
> touch the model; they override the material array on their own **prefabs**.
>
> So importing the FBX and building a fresh prefab from it — which is what has to
> happen, because the pack's prefab carries the vendor's own parameterless
> controller and no ragdoll — gets the **Built-in** materials, which were not
> copied. **Unity reports nothing for this.** A missing material is default grey
> at best and magenta at worst, and the import log stays clean.
>
> `import_zombie.py:repoint_fbx_materials_at_urp()` rewrites those GUIDs, and
> verifies the **end state** — that the block resolves to exactly the copied URP
> materials — rather than the absence of the old strings.
>
> **Its first version verified vacuously.** The built-in material folder was
> hardcoded to `Material_1`, so importing skin 2 paired nothing, the swap loop
> iterated an empty dict, and it printed *"verified: no built-in material guid
> remains"* — true, and meaningless. It now hard-errors on an empty pairing.

**The vendor's AnimatorController has ZERO parameters.** It is a showcase reel.
`EnemyAI` drives six — `IsWalking`, `IsRunning`, `IsSprinting`, `Attack`,
`AttackIndex`, `WalkIndex` — and setting a parameter an Animator does not have
logs an error *per call*, so using it gives a wall of console spam and a zombie
frozen in place. `ZombieImporter.BuildController()` authors an 11-state graph.
`Zombie_TheControllerDeclaresEveryParameterEnemyAIDrives` is the guard, and it
was **proved by breaking it**: renaming `IsSprinting` to `IsSprintinX` in the
built controller made the suite fail with *"The zombie controller has no
'IsSprinting' parameter, but EnemyAI sets it"*, then `Build Everything` restored
it. (The controller is a pure build output, so unlike the scene-revert trap
recorded further down, this proof leaves nothing broken behind.)

There is **no death state, on purpose** — `EnemyAI.Die` sets `anim.enabled =
false` and hands over to the ragdoll, so a death clip would be cut off on frame
one. The pack's three Death clips are not even copied. **Sprint reuses `Run1` at
1.6x**; the pack ships no sprint clip and the old project's controller did the
same.

> **The clips are Generic, so they bind by transform PATH, not by avatar.**
> Every animation FBX is `animationType: 2` with `avatarSetup: 1`, so each one
> carries its own avatar while the prefab's Animator uses the *mesh's*. That
> works only while the two hierarchies match exactly, and if they ever diverge
> the clip binds to nothing and the zombie **T-poses through the entire match** —
> with the controller's six parameters intact, every state still carrying a
> motion, and every other check still green.
>
> `Zombie_TheAnimationClipsActuallyMoveThisModelsBones` samples `Walk1` onto a
> real prefab instance at t=0 and t=half and asserts the watched bones actually
> rotated. It is the only test here that asks the real question.

**The ragdoll is built from the skeleton's own measurements**, not from typed
extents — Unity's Ragdoll Wizard is an interactive window and cannot be
scripted. 12 bodies, 10 joints, capsule length and axis derived from where each
child bone actually is. Twist bones, fingers and the `Zombie02_Ctrl_*` IK rig
are excluded: twist bones are driven *by* their parent, so jointing them fights
the skeleton.

**The zombie is on the `Enemy` layer, root and every bone.** It worked on
Default — the shot mask is `whatIsEnemy | whatIsWorld`, which is `~0` either way,
and the damage branch is chosen by `GetComponentInParent<Target>()`, not by
layer. But `WeaponRigBuilder` sets every gun's `whatIsEnemy` to exactly layer 6,
so on Default **that field matched nothing**: a serialized knob that turns
nothing, which is this project's signature bug. Safe to set because the camera
`cullingMask` is all layers and the physics collision matrix is all-on — and
`Map_EveryCameraRendersTheEnemyLayer_Law9` now asserts the first of those,
because narrowing it later would make the whole horde **invisible while staying
fully lethal**, with nothing logged.

**The head collider carries the `Head` tag.** `GunSystem.ApplyHit` decides
headshots with a bare `hit.collider.CompareTag("Head")` — on the collider, not
the object, not a parent. Miss it and headshots silently stop existing: no
130-Essence kills, no camo progression, no ding, nothing logged.

> ### ★ The model's facing is MEASURED, and the hand-written literal was wrong
>
> The first version set the model child to `Quaternion.Euler(0, 180, 0)` with a
> confident comment about DCC export conventions. `DeriveFacingYaw` measures the
> ankle-to-toe vector of **both** feet (summed, because feet splay) and snaps to
> the nearest 90°. It reported **0.0° off +Z — the literal was 180° wrong**, and
> shipping it would have made the entire horde moonwalk.
>
> This is the same shape as everything else in this file: *a plausible constant
> is not a measurement.*

### All three zombie looks are imported and the spawner rolls between them

> **Owner's decision, 2026-07-31 (later the same day), reversing "skin 2 only":**
> *"add ALL skins for zombies."* All three are now on disk, each with its own
> Enemy prefab, and `EnemySpawner` picks one per spawn. The earlier box saying
> 1 and 3 "stay in the old project" is gone — it was right when there was no
> spawner path to use them, and that path now exists.

The pack ships three, and they are not colourways — the **meshes differ too**, so
mesh, texture folder and material folder must move together.
`python Tools/import_zombie.py` imports all three; `--skin 1,3` or `--skin 2`
narrows it.

| N | Look (read off `Screenshots/zombie/skin<N>/`) | Prefab |
|---|---|---|
Names come from the **albedo texture**, with the mean `_BaseMap` RGB Unity itself
reports (`Tools > Aftermarket > Audit Zombie Skins` →
`Screenshots/zombie/skin_audit.txt`):

| N | Body albedo, measured | Look | Prefab |
|---|---|---|---|
| 1 | (0.262, **0.323**, **0.393**) blue-dominant | **ICE** — blue crystals on grey flesh | `Enemy_Skin1.prefab` |
| 2 | (**0.457**, 0.412, 0.387) warm | **BONE** — pale cream flesh, bone spurs | `Enemy.prefab` |
| 3 | (0.397, **0.445**, 0.393) green + green emissive | **TOXIC** | `Enemy_Skin3.prefab` |

Skin 2 is the primary purely because the scene already references
`Enemy.prefab`. There is no art reason.

> ### ★★ SKIN 1 RENDERS WITH ITS BLUE CHANNEL AT ZERO — cause NOT found
>
> **Measured, not inferred.** On `Screenshots/zombie/skin1/front.png`, **51.9% of
> the body's pixels have B == 0 exactly** — against **0.0%** for skins 2 and 3 —
> and *every* pixel sampled across the chest row is `(r, g, 0)`: `(19,20,0)`,
> `(43,76,0)`, `(52,81,0)`. A blue-dominant albedo under any positive light
> cannot produce that. The eyes and teeth are affected too, so it is global to
> the object, not a texture region.
>
> **Ruled out so far** (each checked, none of them it):
> - The asset chain. `Enemy_Skin1.prefab → SK_ZombieM_02_Mesh1.fbx →
>   ZombieM_02_Body_Skin1.mat → TX_ZombieM_02_Body_Skin1_Albedo.png` resolves
>   correctly *in Unity*, not just in the YAML — `ZombieSkinAudit` prints it.
> - The texture content. The imported PNG is pixel-equivalent to the vendor's own
>   `Texture_1/...Albedo.tga`, checked at 64² signature.
> - A tint. `_BaseColor` is `(1,1,1,1)` and there is no `MaterialPropertyBlock` in
>   play — this is a static editor render, `EnemyAI.TintRenderers` never runs.
> - Duplicate GUIDs. Zero across the whole of `Assets/`.
> - Emission. Setting `_EmissionColor` to black and re-rendering produced a
>   **byte-identical** PNG. (Which is itself odd and may mean the re-import did
>   not take — treat that one as *untested* rather than *ruled out*.)
>
> Until this is found, **skin 1 is an ICE zombie that renders mossy.**
>
> ### ★ "MOSS" was a name for that bug, and "ICE" then got moved onto the wrong skin
>
> Three passes, each arguing from a picture, none opening the texture.
>
> 1. This file and `import_zombie.py` both called skin 1 *"ICE / blue frost
>    crystals"* while only skin 2 was imported, so nobody had rendered skin 1.
> 2. Someone rendered it, saw olive green, and relabelled it **"MOSS"** — naming
>    the broken render. "ICE" was then **deleted** rather than tried against the
>    other two numbers, and skin 2 — *still* never rendered — was handed a freshly
>    invented *"BONE, the ordinary zombie"*.
> 3. Skin 2 was finally rendered, came out cold blue-grey, and "ICE" was moved
>    onto it — **which is where the brief for this session had it.**
>
> The texture says otherwise, and the texture is the art. Skin 2's albedo is
> **warm** (R > G > B) and nearly neutral, so it takes the colour of whatever
> lights it — and the map's HDRI is `belfast_sunset_puresky`, a blue-grey sky.
> *"Cold blue-grey" describes the sky, not the zombie.* Under neutral light it is
> a bone/cream body. So the original labels were right about the pack, and every
> "correction" since has been chasing two rendering artefacts.
>
> **The rule is not "render it before you name it" — that is what produced all
> three errors.** It is:
> - **Name the art from the art.** Record how it renders *today* as a separate,
>   dated fact, because a render is albedo × lighting × whatever is broken.
> - **Refuting a label where it sits does not make it false at every other
>   address.** Move it, or prove it belongs nowhere.
>
> `Capture Zombie Screenshots` shooting every skin was necessary and **not
> sufficient**: by pass 3 every render existed on disk and the wrong conclusion
> was drawn from them anyway.

**`Enemy.prefab` is skin 2 and keeps the unsuffixed name.** That asymmetry is
deliberate: `AshfallDepot.unity`'s spawner already references it, and renaming it
would leave a missing reference in the one file this project intends hand edits
to accumulate in.

**`EnemySpawner.variantPrefabs` is rebuilt from disk by `CoreLoopBuilder` every
run**, never preserved — a stale entry would point at a prefab a `--skin`
re-import had deleted. `PickPrefab` rolls uniformly across `enemyPrefab` plus
every non-null variant, and **skips nulls rather than counting them**: an empty
inspector slot would otherwise be a one-in-three `Instantiate(null)`, which
throws, eats the spawn, and leaves the round unable to end.

> ### ⚠ The legacy unsuffixed materials must be deleted, or Unity re-mints a GUID
>
> The materials used to be renamed to skin-INdependent names on the way in
> (`ZombieM_02_Body.mat`). That was correct while one skin was installed at a
> time and is a collision now, so they are `ZombieM_02_Body_Skin<N>.mat`.
>
> **Leaving the old pair behind silently corrupts the import.** They carry the
> vendor's original material GUIDs, so when the suffixed copy arrives with the
> same `.meta` Unity sees the GUID taken and **mints a fresh one for the
> newcomer** — while the model's `externalObjects` remap still points at the
> original. Observed, not theorised: skin 2 came out rendering through
> `ZombieM_02_Body.mat` while `ZombieM_02_Body_Skin2.mat` sat beside it on a
> freshly minted GUID, unused. `clear_skin(2)` now removes the legacy pair.
>
> It rendered correctly the whole time, because the orphan held the same
> textures. Only the *report* showed it.

`Zombie_EachPrefabWearsItsOwnSkinsTextures` is the guard that matters, and it was
**proved by breaking it**: pointing `ZombieM_02_Body_Skin1.mat` at skin 2's
albedo made the suite fail with *"Enemy_Skin1.prefab (skin 1) is rendering with
'TX_ZombieM_02_Body_Skin2_Albedo', which belongs to another skin"*. That is the
body-wearing-another-body's-skin failure — it renders perfectly and is simply the
wrong zombie, so no existence check can see it.

**There are no more than three.** `Mesh/` holds `SK_ZombieM_02_Mesh1/2/3.fbx` and
`Textures/` holds `Texture_1/2/3`; that is the whole pack. The old project *does*
carry a separate, much cruder `Assets/Zombie/` pack — `Zombie1/2/3.FBX` sharing
**one** texture set — which is the old build's zombie and is not imported here.

**Textures are re-encoded TGA → PNG and it is provably lossless.** The pack is
1,708 MB, of which 1,588 MB is uncompressed 4096² TGA across three skins. One
skin's *bound* textures are 368 MB; as PNG they are 130 MB, and every image is
re-opened and compared pixel-for-pixel with numpy — the run fails on a single
differing pixel. **Resolution is not reduced**: the vendor's own `.meta` decides
the built size and second-guessing it would be a quality decision made by
accident. Only the 9 textures the two materials actually bind are copied; the
HDRP-only `MaskMap`s are not, and the list is read out of the materials rather
than typed.
