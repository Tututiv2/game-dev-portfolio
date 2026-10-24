<!-- Split out of CLAUDE.md on 2026-08-01. The text below is copied
     byte-for-byte; CLAUDE.md now carries a one-line index entry for each
     landmine here. Read this file before touching the systems it covers. -->

# Characters, the recolour theme system, and weapon skins

Covers `import_inquisitor.py`, `Recolor.shadergraph`, `_RegionMask`,
`ThemeSystem`, the 7-character roster, and the Sakura Fang.

## The theme system and the Assassin (2026-07-31)

`Tools > Aftermarket > Build Character Select` after
`python Tools/import_inquisitor.py`.

### The Assassin — it rendered white because of the slot-binding bug, again

> **`MAT_INQUISITOR_CLOTHES_06` is the "Black Variant" and its `_BaseMap` points
> at `T_INQUISITOR_CLOTHES_BaseColor_05` — the WHITE texture.** Only
> `_BaseColor_Map` (with the extra underscore, a property of the vendor's own
> Shader Graph) holds BaseColor_06. **URP/Lit reads `_BaseMap`.** So you get the
> white one, out of a material named Black, with no error anywhere.
>
> **It is NOT true that every colourway has this shape** — that was the brief's
> assumption and it is wrong. Checked all eight: only **02 (Blue) and 06 (Black)**
> are mismatched; 01, 03, 04, 05, 06_Custom and 01_Dirty agree with themselves. A
> rule that holds for two thirds of a set is exactly the kind that gets applied
> blindly to the other third, so `import_inquisitor.py` **compares the two slots
> per material and logs a disagreement** rather than always preferring one — and
> it picks the colourway albedo **by filename**, so a broken binding cannot decide
> which colour ships.

**Two more of the same class, found on the way in and fixed.**
`T_INQUISITOR_CLOTHES_MetallicSmoothness`, `T_INQUISITOR_CLOTHES_MaskMap`,
`T_HEAD_M_MaskMap`, `T_HEAD_MetallicSmoothness` and
`T_INQUISITOR_BODY_MetallicSmoothness` all ship **`sRGBTexture: 1`**. They are
data maps — metallic, smoothness, occlusion — not colour, so URP gamma-decodes
them on upload and every value arrives wrong and darker, on a surface that still
renders and still looks plausible. The copies are forced linear and the script
**prints each flag it changed** instead of doing it quietly.

**161.3 MB added**, not 2,905. Two filters, both derived rather than assumed:

- **The mesh has exactly three material slots** — `HEAD`, `MAT_BODY`,
  `MAT_CLOTHES` — read out of the FBX. It is hooded, masked and gloved, so there
  is no hair, no eyes and no lashes on this model at all. The pack's other 13
  slots belong to its other outfits.
- **Only textures URP/Lit can sample.** The vendor materials also bind dirt
  masks, blood masks, subsurface, MatID, pattern and per-region aegyosal normals.
  Copying "every bound texture" is technically accurate and **496 MB** — 3.4x the
  size for zero pixels on screen.

Two clothes colourways are imported: **06 Black** (the look asked for) and
**04 Red**. Red is there because it is the *recolour source* — see below. Each
extra colourway is ~19 MB; the list is `COLOURWAYS` at the top of the script.

### The recolor shader — and what a hue shift genuinely cannot do

`Assets/_Project/Art/Shaders/Recolor.shadergraph`, per §15.11.1:
`albedo → RGB2HSV → hue+shift / sat×mul / value×mul → HSV2RGB`, and **then**
`× _BaseColor`. Shader name is **`Aftermarket/Recolor`**. Regenerate with
`python Tools/shadergraph/gen_recolor.py` — the `.shadergraph` is hand-authored
MultiJson (160 objects, 34 edges) and the generator is the only sane way to edit
it outside the ShaderGraph window.

**Verified three ways, in Unity, not by reading the file**
(`Tools > Aftermarket > Check Recolor Shader` → `Screenshots/shader/`):

1. `ShaderUtil.ShaderHasError` false, **0 shader messages**, all 12 properties
   present under URP/Lit's own names with the right flags (`_BumpMap` carries
   `Normal`, `_EmissionColor` carries `HDR`).
2. On a solid red albedo, measured hue at `_HueShift` 0.00 / 0.33 / 0.66 came
   back **0.0000 / 0.3300 / 0.6600** — exact.
3. Sprinter order: at shift 0.33, applying `_BaseColor = white × 0.62` through a
   MaterialPropertyBlock left hue at **0.3300 → 0.3300** and dropped value
   0.436 → 0.155. Same hue, darker.

> **The grey-tint test alone cannot see the bug it is aimed at**, and this is
> worth keeping. A *neutral* multiply moves no hue on **either** side of an HSV
> round trip, so it passes for the correct wiring and the broken one alike. The
> decisive test uses a **coloured** `_BaseColor` (0.2, 0.2, 1) over an orange
> albedo: a graph that tints *before* the conversion must show a hue difference
> of exactly the shift between shift 0 and shift 0.33, because
> `frac(h + s) − h == s` for any `h`. Measured 0.7646 → 0.6398, difference
> **0.8752**, not 0.33 — and both numbers match the hand-computed prediction for
> the correct wiring to three decimals. *A test that passes on both answers is
> not a test.*

> **The `_BaseColor` multiply must be the LAST step and this is load-bearing.**
> `EnemyAI.TintRenderers` marks sprinters by writing `_BaseColor` into a
> MaterialPropertyBlock (`RoundRules.SprinterTint` = 0.62). §15.11.3: the theme
> sets the base hue and the type signal applies **on top**. Wire `_BaseColor`
> into the *input* of the HSV conversion instead and the sprinter tint gets
> hue-shifted along with everything else — the readability signal is destroyed,
> at round 10+ when everything sprints, with nothing logged.

> ### ⚠ A hue shift cannot invent saturation, and this decided which texture to import
>
> `saturation × mul` is still 0 for any multiplier when the source is grey, and
> `value × mul` only ever darkens. **A near-white or near-black source cannot be
> themed into a colour at all.** Measured mean saturation across the Inquisitor's
> six clothes atlases: **04 = 0.206** and **03 = 0.175** are the only two with
> anything to rotate; **05 "White" = 0.007** and **02 "Blue" = 0.010** are
> effectively greyscale. So the Black texture gives the look that was asked for
> and is a dead end for themes, which is why 04 comes too.
>
> This is the limit of the *approach*, not a bug — and it is still much better
> than a flat colour multiply, which §15.11.1 rules out because it flattens the
> model into a paint swatch and throws away the wear and stitching.
>
> **Both halves are visible in one report.**
> `Tools > Aftermarket > Capture Character Screenshots` shoots the Assassin under
> every theme in **both** clothes materials, under flat neutral light, and
> measures the saturation-weighted circular mean hue
> (`Screenshots/character/report.txt`):
>
> | clothes | Default | Ember +0.05 | Verdant +0.32 | Azure +0.55 | Rose +0.88 |
> |---|---|---|---|---|---|
> | **Black** (sat 0.094) | 0.636 | 0.620 | 0.537 | 0.591 | 0.676 |
> | **Red** (sat 0.205) | 0.928 | 0.055 | 0.390 | 0.558 | **0.809** |
>
> The Black row barely moves — that is the greyscale limit, not a broken shader.
> The Red row swings right around the wheel, and Rose lands on 0.809 against a
> predicted `frac(0.928 + 0.88)` = 0.808. Saturation tracks `saturationMul`
> (0.205 → 0.297 at ×1.25) and Void's ×0.45 value drops it to 0.294.
>
> **The armour stayed silver even before the mask existed.** The metal is already
> desaturated in the atlas, so a hue rotation could not move it. That bought
> §15.11.1's "blue assassin, not everything-including-his-face is blue" for free
> — but only by luck, and only for hue. The mask below makes it deliberate and
> extends it to saturation and value.

### The `_RegionMask` — derived from the pack, not painted

`python Tools/make_region_mask.py`. §15.11.1's three channels:
**R = cloth, G = armour, B = leather/skin**, each with its own hue/sat/value set
(`_HueShift` / `_HueShift2` / `_HueShift3`, and the same for saturation and value).

**It is derived, because a better mask was already in the pack.**
`T_INQUISITOR_CLOTHES_MatID.png` is a flat region-id map in ~11 colours, authored
by whoever made the mesh, in the albedo's own UV layout. Painting one by hand
would have been inventing data that exists.

**Which region is metal is MEASURED.** For each MatID region the script reads the
mean metallic out of `T_INQUISITOR_CLOTHES_MetallicSmoothness.png`, and the
regions fall into three clusters with enormous gaps:

| cluster | area | metallic | albedo saturation | → channel |
|---|---|---|---|---|
| mid | 48.6% | 0.311 | **0.329** | **R** cloth |
| high | 39.2% | **0.978** | 0.069 | **G** armour |
| low | 11.3% | 0.010 | 0.025 | **B** leather |

The split points are the two largest gaps in the sorted per-region metallic, so
they come from the data; the script **refuses** if the largest gaps fall below
0.15, rather than picking a threshold in the middle of a blob. Highest metallic
is armour by definition of metallic; of the other two, the one carrying more
albedo saturation is the cloth, because cloth is the thing that gets dyed.

**15.4 KB for the whole feature.** The clothes mask is 1024² (flat regions
compress to 15 KB); `Body` and `Head` are **4×4 constants** — a mask that says
the same thing everywhere does not need pixels.

Three things that are load-bearing:

- **`_RegionMask` defaults to `red`, not white.** Unbound, it samples (1,0,0), so
  the weights are (1,0,0) = 100% region 0 = exactly the pre-mask behaviour.
  Defaulting to white would give (1,1,1) and silently average all three parameter
  sets on every material that does not carry a mask.
- **The masks are linear and UNCOMPRESSED.** sRGB would gamma-decode the weights;
  block compression averages across 4×4 blocks and bleeds one region's id into
  its neighbour at exactly the boundaries the texture exists to define.
  `Theme_TheRegionMasksAreLinearAndUncompressed` asserts both.
- **A region an author did not supply stays at IDENTITY, never falls back to
  region 0.** A fallback would make every cloth-only theme hue-shift the metal
  and the face with it — the exact failure the mask prevents, reintroduced where
  nobody would look. `Theme_ARegionTheAuthorDidNotSupplyStaysAtIdentity`.

The **Gilded** theme exists to exercise region 1 on the real model: cloth is
desaturated, metal goes gold. Every other theme leaves the armour alone, which is
the right default and means nothing else would ever prove the armour channel is
wired — and an unexercised channel is how a three-channel mask quietly becomes a
one-channel one.

**Proved in the shader on synthetic masks, then on the actual character.**
`Check Recolor Shader` puts a three-band mask on a quad and measures each band
separately: region shifts 0.00 / 0.33 / 0.66 came back **0.0000 / 0.3300 /
0.6601**, and with `_SaturationMul2 = 0` region 1 measured saturation **0.000**
while regions 0 and 2 held 0.993 and 0.983 — neither knob leaks across regions.
The whole-image mean was 0.3291 and is reported as meaningless, which is the
point of measuring bands separately.

> **The mask's LINEAR sampling had to be proved with a mid-grey texel.** A
> three-band mask cannot show it: 0 and 255 are fixed points of the sRGB curve,
> and the weight normalise cancels any common factor, so an sRGB-decoded mask
> gives the *same* three answers. A texel of **(255, 128, 0)** is where the two
> readings diverge — linear weights (0.666, 0.334, 0) predict hue **0.2005**,
> sRGB-decoded (0.822, 0.178, 0) predicts **0.1065**. Measured **0.2010**.
> *A test that both answers pass is not a test* — the same lesson as the
> grey-tint case above, hit twice in one feature.

On the Assassin (`Screenshots/character/report.txt`), with the Red clothes and
the render split by saturation into cloth and metal buckets:

| theme | cloth shift | **CLOTH hue** | **ARMOUR hue** | armour sat |
|---|---|---|---|---|
| Default | 0.00 | 0.969 | 0.692 | 0.074 |
| Ember | +0.05 | 0.065 | 0.688 | 0.070 |
| Azure | +0.55 | 0.548 | 0.658 | 0.069 |
| Verdant | +0.32 | 0.355 | 0.643 | 0.072 |
| Rose | +0.88 | 0.829 | 0.668 | 0.072 |
| **Gilded** | armour +0.12, sat ×2.2 | 0.316 | **0.752** | **0.090** |

The cloth swings right around the wheel; the armour sits within ~0.05 of where it
started and its saturation does not move. Gilded is the one row that moves the
metal, and it moves *only* the metal.

> **The cloth/armour split in that report is a PROXY, not a readout of the mask.**
> It segments the finished image by saturation at 0.18, so some dark cloth lands
> in the "armour" bucket — which is why the armour hue drifts by a few hundredths
> instead of being pinned. That is deliberate: reading the mask back would tell
> you what the mask says, and the question is what the pixels did.

### `ThemeSystem` — the two ways this ships broken

`Scripts/Content/ThemeDefinition.cs`, `ThemeCatalog.cs`, `ThemeSystem.cs`.
Themes are `.assets` (§18.1), so adding a colourway is authoring, never code.

- **`SetPropertyBlock` REPLACES the block, it does not merge.** A plain
  `SetPropertyBlock(new MaterialPropertyBlock())` here would erase the
  `_BaseColor` that `TintRenderers` wrote — a cosmetic feature silently deleting
  a gameplay signal. `ThemeSystem.Apply` does get → modify → set, and
  `Theme_ApplyDoesNotEraseTheSprinterTint` is the guard.
- **A MaterialPropertyBlock accepts any property name; the shader decides whether
  it means anything.** Applying a theme to a plain URP/Lit renderer does exactly
  nothing and every log line still says it worked. `Apply` returns the count of
  renderers that could *actually* respond (`HasProperty("_HueShift")`), warns
  naming the offending renderer and shader, and
  `Theme_ApplyReportsZeroWhenTheShaderCannotRecolour` holds it to that.

`ThemeSystem.ZombieTheme` is read once in `EnemyAI.Start`, **before**
`ApplyRoundStats` runs the sprinter tint. Different properties, same block, so
they compose instead of fighting.

### The character-select screen — because it is an FPS

`Assets/_Project/Scenes/CharacterSelect.unity`, built by
`Editor/CharacterSelectBuilder.cs`. Turntable preview, theme swatch row,
character arrows, choice persisted to `PlayerPrefs` per §15.12.4.

**This is the preview half of §15.12, not the store** — no prices, no purchase
flow, no Sacreds. Those need the economy wired up. Seeing the character and its
themes is what makes the art visible, which is the point.

**Locked themes are rendered but never trusted (§15.12.2).** The lock comes from
`IBackend.HasEntitlement`, not from `ThemeDefinition.adminOnly` — the flag decides
how it *looks*, the backend decides whether it can be equipped. Three of the six
seeded themes are admin-only so the locked presentation is exercised rather than
merely written. `CharacterSelect.SeedLocalEntitlements` grants the free ones and
is **guarded by `Backend.Current as LocalBackend`**, so it is the stand-in server
seeding its own list; point the composition root at a real backend and the block
does nothing and no other code path changes.

**Ambient light in that scene is flat neutral grey, deliberately.** A themed model
has to be judged on its own colour. That is the exact trap the zombie labelling
fell into three times — "cold blue-grey" turned out to describe
`belfast_sunset_puresky`, not the zombie.

> ### ⚠ Switch EVERY material in the character folder to Recolor, not just the ones the prefab wears
>
> `BuildInquisitorPrefab`'s first version walked the **prefab's renderers**, so
> `Inquisitor_Clothes_Red` — imported specifically to *be* the recolour source —
> stayed on plain URP/Lit and every theme was a silent no-op on it.
>
> **Rendering caught it; reading would not have.** `ThemeSystem` reported
> "**3** renderer(s) recoloured" instead of 22, and the measured hue did not move
> by 0.002 across six themes. That is the `Apply`-returns-a-count guard earning
> its place on its first outing: without it the log would have said the theme was
> applied and the picture would simply have been wrong.
>
> An alternate colourway is exactly the thing a prefab-only walk cannot see, so
> the builder now enumerates `t:Material` under the character folder.

**213 EditMode tests, all passing, none skipped.** The "none skipped" matters:
`ThemeTests` self-skips when the Recolor shader is absent, so a green run with
skips would have proved nothing about composition.

## Weapon skins — the Sakura Fang (rebuilt from measurement, 2026-08-01)

`Tools/skins/`. A pink cat-themed sniper, 4,424 tris, 1.30 x 0.07 x 0.27 m.
`Tools > Aftermarket > Build Sakura Fang`, then `Build Weapon Rig`.

```
python Tools/skins/make_projection.py    # the albedo AND the crop sidecar — run FIRST
blender --background --factory-startup --python Tools/skins/forge_gun_sakura.py -- --project --render --export
blender ... forge_gun_sakura.py -- --silhouette   # then:
python Tools/skins/check_silhouette.py   # measure the shape against the art
python Tools/skins/make_decals.py        # the vector decal sheet (non-projected builds)
```

> ### ★★ THE SHAPE IS MEASURED NOW. It used to be invented, and that shipped.
>
> The first model was a box-modelled AWP with the owner's art projected onto it.
> **Projection fixes the SURFACE and does nothing about the SHAPE**, and that was
> named twice in session summaries before anything was done about it — three
> rounds went into tuning the projection's saturation instead.
>
> Every outline in `build_gun()` is now a list of **pixel coordinates read off
> the reference**, so re-checking a number means opening the picture and looking.
> `sakura_ref.py` holds the frame (rotation, scale, origin) and is shared with
> `make_projection.py`, so the art and the geometry cannot drift apart.
>
> What changed concretely: the barrel is **skeletal open rails**, not a tube in a
> handguard; the scope is **23 mm on low rings**, not 66 mm on tall ones; the
> stock is a **flat hexagonal paddle with ribbon fins**, not a comb and butt
> plate; the handguard has a stepped top edge and a lobe carrying the coral inset.
>
> **The primitive set was choosing the design.** Every part had been a box plus a
> vertex rule, and a box cannot express any of the above — so the silhouette
> converged on what boxes can make, which is a conventional rifle. `plate()`
> extrudes a measured 2D outline with a proper constant-distance chamfer, and it
> is what unlocked the real shape. (Its first version scaled the rim toward the
> centroid instead, which is a chamfer *proportional to the part*: 3 mm on the
> trigger and 20 mm on the stock paddle, rounding every hexagon into a lump.
> **A chamfer is a distance, not a ratio.**)

> ### ★★ The projection was misregistered by 6.66 DEGREES the entire time
>
> The weapon sits at 6.657° in the reference photograph and the model's bore is
> horizontal. `make_projection.py` cropped the reference **as shot**, so the art
> was mapped onto the geometry with a **330 px end-to-end vertical drift against
> a weapon only ~430 px tall** — the art slid up the barrel and down the stock.
> It looked ALMOST right, which is why the previous rounds went after saturation.
>
> Fixed by putting the rotation in `sakura_ref.py`, having both scripts convert
> through it, and writing the crop rectangle to a **JSON sidecar** that the forge
> maps UVs through. Registration is now a shared coordinate system rather than
> two bounding boxes hoped to describe the same span — which they never did, since
> the warm-vs-cool mask cannot see the near-white muzzle and was always short by
> its length. **The forge REFUSES to build if the sidecar is missing** rather than
> falling back to bounding-box UVs, because that failure mode looks almost right.
>
> **Every obvious way to measure that angle is wrong.** The weapon is a wedge —
> thin at the muzzle, deep at the stock — so silhouette-based estimators measure
> the taper: PCA +11.04°, per-column centroid +9.51°, extent midpoint +10.95°,
> top-edge fit +5.62°. The scope tube is the only true cylinder parallel to the
> bore, and a fit through its centreline gives **+6.657°**. *A silhouette's
> principal axis is not a bore line.*

**It is SCRIPTED, not forged.** `Tools/forge/` returns a barrel as a 7.8 mm sheet;
a rifle is its worst case — a thin barrel, a hollow trigger guard and a scope on
two rings are exactly what image-to-3D collapses. Scripting also gives a diffable
source file, which is what the rest of this repo prefers.

The gun is separated from its backdrop by **RED >= BLUE, not brightness** — sky
and cloud are blue-dominant, the weapon is pink. That misses the **white** muzzle
group entirely, so a second near-neutral test runs inside a measured box around
the barrel. Both of that box's bounds were paid for: without the X bound it takes
half the sky; with only the X bound it took a cloud bank under the rails, which
made the end-discrimination answer RIGHT and would have **mirrored the art onto
the weapon** from a mask defect three functions upstream.

**Which end is the muzzle is stated by the landmarks and VERIFIED against the
pixels, not inferred twice.** The old test compared column thickness — thin
barrel, bulky stock. True of an ordinary rifle and **false of this one**, whose
barrel is two open rails with a gap that `binary_fill_holes` fills solid: the two
ends measure 101 px and 105 px, a ratio of 1.03. Saturation separates them 3.8x
(near-white 0.084 vs deep rose 0.318) and is what the check uses now.

### Measure the silhouette — `check_silhouette.py`

**A pink sniper rifle beside a picture of a pink sniper rifle reads as agreement.**
That is why the wrong gun survived three review rounds of people looking at
renders. So the model renders one **orthographic** side view framed on exactly the
reference's crop rectangle, its alpha is the silhouette, and that is compared to
the reference's own mask: an IoU plus a per-station table of where the outlines
differ, in millimetres. Currently **0.7625, covering 87% of the reference**.

It found three faults in itself before it found any in the model, and each is
recorded in its header as a **KNOWN BLIND SPOT — do not tune geometry to these**:
the FX shards were being measured as part of the weapon (157 mm of spurious
height at the muzzle); the mask's barrel box was clipping the lower rail so part
of what the reference "had" there was the clip; and the reference cannot see white
features at all, so the scope's shade and the receiver's panel border read as
model defects. Hole-filling is applied to **both** sides deliberately — filling
only the reference scores the model's real trigger loop as missing shape, filling
neither scores the reference's white-detail dropouts as invented shape, and that
swing is worth 10 IoU points in opposite directions from runs that both look like
model changes.

> Honest limits: correct from ONE side, far flank mirrored, top and bottom smeared
> along the projection axis, and the reference's own lighting baked into the albedo
> (so the render rig drops to 42% power in projected mode — lighting it again with
> a rig tuned for flat colours bleached the first attempt). Decal quads are REMOVED
> when projecting, or the cat doubles up 1.5 mm from itself.

> ### ⚠ Three bugs this found, none of which any test could see
>
> 1. **`W` is a HALF-width and `box()` takes FULL sizes.** Everything positioned as
>    `W * 2 + offset` floated ~34 mm off the flank it belonged to — the bow, both
>    receiver panels, both chevrons, both hearts. All rendered, all looked attached,
>    all welded to nothing.
> 2. **The glowing scope lens was sealed INSIDE its own rim cylinder**, lighting the
>    interior of the part covering it, invisible from every angle.
> 3. **The muzzle pointed BACKWARDS.** The prefab builder measured which axis was
>    *longest*, which aligns the barrel and says nothing about which way it points.
>    Ends are now told apart by cross-section (0.0566 m vs 0.0213 m, ratio 2.66) and
>    it **refuses to guess below a 15% difference** rather than flipping a coin.
>    This is the zombie-facing bug exactly — "longest axis" is adjacent to "facing".

### ★ Every weapon in the game was rendering with its back end sliced off

`MapSeeder` sets `farClipPlane` and **never sets `nearClipPlane`**, so the player
camera sat on Unity's default **0.3 m** — the only camera in this project still on
it, and the only one with a gun in front of it (SceneShot, ZombieShot and
RecolorShaderCheck all use 0.05 or 0.01). Measured from the live rig: the Glock and
the M4 were each cut **0.170 m** into their length, since Phase 6, in every build.
Near-plane clipping raises no error and the weapon still looks like a weapon.
`WeaponRigBuilder` now derives it from the closest view-model vertex.

### ★ `WeaponDefinition.viewModelPrefab` was read by NOTHING

A serialized inspector slot on every weapon asset, promising to hold your gun art,
and `CreateViewModel` always built a grey placeholder cube. Assign a prefab, watch
it sit in the inspector looking connected, get a box in game. Same class as
`interactKey`/`pauseKey` (deleted for it) and `whatIsEnemy` pointing at a layer no
zombie was on. `SakuraFangTests` guards it.

**Weapon asset filenames decide SLOT ORDER.** `LoadDefinitions` sorts by name and
`Phase6Builder` writes slot NUMBERS into wallbuys, so a new weapon sorting before
an existing one makes every chalk outline sell a different gun.
`Weapon_SakuraFang` sorts after `Weapon_M4`; the test asserts the ordering
property, not the indices.

## Characters — a roster of seven (2026-07-31)

`Tools > Aftermarket > Build Character Select`, then `Capture Roster Screenshots`.

| id | who | on disk |
|---|---|---|
| `inquisitor` | The Assassin (Black) | 162 MB |
| `sentinel` / `operative` / `vanguard` | IdaFaber M_02 / M_03 / M_04 | 23 / **5.1** / 48 MB |
| `seraph` / `nomad` | IdaFaber F_01 / F_02 | 36 / **6.8** MB |
| `miyoko` | Stellar Girl Miyoko | 123 MB |

Only the standard outfit variants of each pack are imported.

**Characters are `.assets` now (§18.1).** `CharacterDefinition` + `CharacterCatalog`,
and the builder DISCOVERS them from `Art/Characters/*`. The roster used to be a
hardcoded one-element array inside the builder — which is why Next/Prev arrows sat
on that screen since it was built, stepping through a roster of one.

> **Two saves were keyed by ROSTER POSITION.** `PlayerPrefs` stored the selected
> character as an INDEX, and the per-character theme as `k_ThemeKeyPrefix + index`.
> Inserting a character would hand every existing player a different one and move
> their saved themes with it — silently, and permanently once written back. Same
> shape as the scene build-index warning in `NavBarBuilder`. Both key on
> `characterId` now.

**`import_inquisitor.py` is no longer M_01-only** — `--mesh`, `--name`, `--pack`.
It was specific in three separate ways, each an assumption about one mesh stated as
an invariant: the destination folder, a slot→material table holding exactly
HEAD/MAT_BODY/MAT_CLOTHES, and an assertion that any other slot set was an error.
The Girl meshes carry **no HEAD slot** and Miyoko declares **eight**, so "this
character is different" was reported as "the importer is broken".

> **Texture dedup is by SIZE AND CONTENT HASH, never by filename.** The pack ships
> one shared library across the family, so importing M_03 planned 162.6 MB of which
> 157.7 MB was already on disk pixel-for-pixel. Two files here can share a name and
> differ, and reusing on the name alone would dress one character in another's
> textures — which renders perfectly and is simply the wrong character.
> The importer's closing line also used to report the PLAN ("Added 162.6 MB" while
> adding 5.1); it measures the filesystem now.

**14 themes.** Hue is in turns and they are SPACED, not picked — the original six
left the 0.60–0.85 and 0.10–0.30 arcs empty, and two swatches at the same hue are
two a player cannot tell apart when swatch colour is the whole affordance. Three of
the new ones touch region 1, because Gilded was the armour channel's only witness.

### Looking at things — three new tools, and the trap the newest one fell into

| Tool | Shoots |
|---|---|
| `Editor/WeaponShot.cs` | every view model from the live player camera, with screen coverage and near-plane clearance in metres |
| `Editor/RosterShot.cs` | every character in the catalog, with mean saturation and % of pixels near URP's error magenta |
| `Editor/UIShot.cs` | Store / Inventory / CharacterSelect, by flipping each Overlay canvas to ScreenSpaceCamera for one frame |

> ### ★★ RosterShot did not pin its ambient, and the Assassin came back SALMON
>
> Its first version set three white lights and left `RenderSettings` alone, so it
> inherited the ambient of whatever scene happened to be open. The Assassin's
> clothes are (0.318, 0.310, 0.308) and its body (0.607, 0.605, 0.598) — both
> essentially **neutral** — so the model takes the colour of whatever lights it.
> **Miyoko came back correct in the same pass**, because her suit carries its own
> value, which is exactly what lets this survive a look.
>
> Four checks were spent proving the texture, the FBX remap, the material bindings
> and the persisted theme parameters were all fine before the instrument was
> suspected. **This is the zombie-skin mistake for the fourth time.** A render is
> albedo x lighting x whatever is broken, so a shot used to JUDGE albedo has to own
> its lighting. Ambient is now flat neutral grey with the skybox cleared, restored
> afterwards.

> **"Batchmode cannot capture a ScreenSpaceOverlay canvas" is true and is not the
> end of the story.** An Overlay canvas composites straight to the backbuffer and
> never passes through a camera, so `Camera.Render()` returns empty. Render mode is
> a runtime property: flip to `ScreenSpaceCamera`, point at a temp camera, render,
> flip back. `UIShot` never saves.
>
> **What it still cannot show is runtime content.** The store's Sacred tiles, the
> collection grid and the balance readouts are built at runtime, so an editor render
> shows a correct, complete, EMPTY shop. `Assets/Tests/PlayMode/UIPlayShots.cs`
> covers that half and asserts the store ends with more than zero tiles.
