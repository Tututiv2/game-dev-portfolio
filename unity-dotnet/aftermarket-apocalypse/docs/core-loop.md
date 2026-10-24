<!-- Split out of CLAUDE.md on 2026-08-01. The text below is copied
     byte-for-byte; CLAUDE.md now carries a one-line index entry for each
     landmine here. Read this file before touching the systems it covers. -->

# Runtime foundations — the Systems root, input, and compiling

Covers `CoreLoopBuilder`, `GameInput`, `Tools/verify_compile.py`, and the
three contradictions found inside `REBUILD_SPEC.md`.

## The core loop — currency, HUD, rounds and the zombie (2026-07-31)

`Tools > Aftermarket > Build Everything (zombie + core loop)`. Two builders,
run in that order because the second wires the first's output:

| Tool | Builds |
|---|---|
| `Editor/ZombieImporter.cs` | `ZombieM_02.controller`, the ragdoll, `Enemy.prefab` |
| `Editor/CoreLoopBuilder.cs` | the permanent `Systems` scene root |
| `Tools/import_zombie.py` | copies the art across from the old project (run this first) |
| `Editor/ZombieShot.cs` | renders the zombie from four sides and measures it |
| `Tools/verify_compile.py` | compiles all three assemblies at Unity's own C# 9 |

**`PlaytestRig.cs` is deleted.** It was scaffolding for exactly this and said so
in its own log. `CoreLoopBuilder` deletes any leftover `PlaytestRig` root from
the scene before building, and `CoreLoopTests.Map_NoPlaytestRigRootSurvives`
keeps it gone — two `ScoreManager`s is a load-order coin toss, because
`ScoreManager.Awake` destroys one of them.

**`Systems` is NOT a phase root.** Phase roots are documented undo units
("deleting the root undoes the whole pass"). Deleting this one breaks the game.
That distinction is what `Phase7Builder` got wrong by putting `PowerUpManager`
inside `Phase7`.

**Starting Essence is `RoundRules.StartingEssence` (500), and
`ScoreManager.essence` is now `[NonSerialized]`.** It was a public inspector
field seeded at build time by the old rig, which made the opening balance a
literal baked into `AshfallDepot.unity` that no test could see. A serialized
value always beats a script default — the Part I "READ FIRST" bug.
`Map_ScoreManagerHasNoSerializedStartingBalance` asserts the attribute.

**The EventSystem must carry `InputSystemUIInputModule`, never
`StandaloneInputModule`.** `activeInputHandler` is 1, so the legacy module reads
`UnityEngine.Input` and throws every frame. It is added with a plain
`AddComponent`, *not* `ObjectFactory`, deliberately: Unity's `Reset()` path would
serialize a reference to `Assets/InputSystem_Actions.inputactions`, the template
this file already says is **not** the gameplay source. With no asset assigned,
`OnEnable` calls `AssignDefaultActions()` and uses the package's built-in
`DefaultInputActions` — nothing to wire and nothing to lose.

**The round text is anchored at the screen centre and that is load-bearing.**
`EnemySpawner.AnimateRoundStart` drives `anchoredPosition` from `textCenterPos`
to `textCornerPos`, and those are *offsets from the anchor*. Anchored anywhere
else the round announcement flies off-screen — and it would still pass any
"roundText is not null" check. `Map_TheRoundReadoutIsAnchoredAtTheScreenCentre`.

### Verify it compiles at Unity's language version — `Tools/verify_compile.py`

`csc` defaults to C# 10+; Unity forces **`-langversion:9.0`**. A default
`dotnet build` is therefore **not** verification, and that is not hypothetical —
an interpolation hole spanning a newline (CS8967, a C# 11 feature) broke the
whole editor assembly once and blocked Play mode entirely.

The script reuses Unity's real response files from
`Library/Bee/artifacts/*.dag/*.rsp`, so every `-define`, every `-r:` and the
`-langversion` come from Unity. It regenerates the source list from disk (the rsp
is a snapshot and would silently skip a new file) and redirects `-out` into
`Tools/_verify/`.

> **What it cannot see:** the `-r:` list is also a snapshot, so **editing an
> asmdef's `references` has no effect until Unity has compiled once.** Adding
> `Unity.InputSystem` and immediately running this reports a bogus
> `CS0234 ... 'InputSystem' does not exist`. The dangerous direction is the
> reverse: it can compile *green* against a reference list that no longer matches
> the asmdefs. It is a fast check on **language-level** correctness, not a
> substitute for letting Unity build.

### The Input System migration — done, and it was eight files not six

This file used to say six. It was **eight**: `WeaponSway` and `WeaponAiming` also
read the legacy axes and were missed by whoever counted.

Everything now goes through **`Assets/_Project/Scripts/Core/GameInput.cs`**, a
static facade. Call sites stayed one-liners; all bindings live at one address.

Three things that are load-bearing:

- **`activeInputHandler` is 1 (Input System only), not 2 (Both).** On "Both",
  nothing is broken and nothing is finished — that is precisely how this sat as
  debt. On 1, `UnityEngine.Input` throws at runtime, so the migration cannot rot.
- **The 0.1 mouse compensation is measured, not chosen.**
  `ProjectSettings/InputManager.asset` gives Mouse X and Mouse Y a sensitivity of
  **0.1**, and legacy `Input.GetAxis("Mouse X")` returns the frame delta times
  that. `Mouse.delta` in the new system is raw pixels. Reading it straight makes
  looking about ten times faster and silently invalidates every `lookSpeed` and
  `swayMultiplier` already tuned here.
- **`Assets/InputSystem_Actions.inputactions` is still NOT the gameplay source.**
  It is Unity's template; the only reference to its GUID is
  `EditorBuildSettings.asset`, where it is the UI module default. It defines no
  Reload, Aim, Pause or weapon slots. `GameInput` builds its actions in code, so
  there is no scene wiring and no serialized reference to lose. **Editing that
  asset will not change the game.**

`interactKey` and `pauseKey` were `KeyCode` inspector fields and are **gone**. A
knob that turns nothing is the Part I "READ FIRST" bug this project keeps
designing out.

The guard was tested by putting a legacy call back into `WeaponSway` — the suite
failed and named the file. Note it still **compiles** with the handler off, so the
test, not the compiler, is what catches a regression.

Content lives in `Assets/_Project/Content/` and is regenerated by
**Tools > Aftermarket > Rebuild Phase 2 Content** (`Assets/_Project/Editor/ContentBootstrap.cs`).
It is idempotent — existing assets are updated in place so GUIDs survive.
`ContentTests` loads the real assets off disk, so "Phase 2 content exists" cannot
be claimed again without it being true.

### Three contradictions found inside REBUILD_SPEC.md

None was silently "corrected". Each is resolved in favour of the spec's own
higher authority — the formula over the prose, the ★ over the code sample — and
each has a test naming it.

1. **§10.3 Soul Crystal cap.** Formula `min(0.3 + (r-10)*0.03, 0.85)` is annotated
   "caps at R28", but round 28 gives 0.84; the clamp first bites at round 29.
   The formula wins.
2. **§15.6 premium float curve.** §15.6 says lower float is cleaner *and* that a
   `floatCurveExponent` of 0.5 "skews clean". It cannot do both: `pow(x, 0.5) ≥ x`,
   so 0.5 pushes floats **up** — dirtier. Skewing clean needs an exponent **above**
   1. Left as written because paying players getting worse wear than free players
   is a money decision. Set `SacredDefinition.floatCurveExponent ≈ 2.0` to get the
   prose behaviour.
3. **§18.3 vs §21 — the word "Case".** §18.3 writes the interface as
   `Task<CaseResult> OpenCase(string userId, string caseId)`, using the exact word
   §21 retires ("Crate / Case / Loot Box → Sacred"). §15.8 settles it with a ★:
   "The cases are called SACREDS. Not crates, not cases, not boxes." Since the
   spec's provenance rule says ★ owner decisions win, and §18.3's code block
   illustrates the interface's *shape* rather than mandating identifiers, the API
   is **`OpenSacred` / `SacredResult`** and "Case" is banned by test.
