# AFTERMARKET APOCALYPSE — Complete Rebuild Specification

> **What this document is.** A self-contained build spec for *Aftermarket Apocalypse*: a round-based
> zombies survival shooter (Black Ops 2 Zombies lineage) with a serial-numbered, tradable skin economy
> layered on top. It is written so that a developer or AI session with **zero prior context** can rebuild
> the game in Unity from scratch.
>
> **Target engine: Unity 6 (6000.3.1f1) + URP.** An older companion document targets Roblox; that build
> order is superseded and should be ignored.
>
> **How to read it.** The document is in three parts and they have different authority:
>
> - **PART I — AS BUILT.** Everything that currently exists and works, at implementation depth: exact
>   field defaults, formulas, timings, synthesis algorithms. This is *description*. Where the current
>   code is wrong or awkward, it says so rather than dressing it up.
> - **PART II — THE FULL VISION.** The designed game that was never built, principally the skin/case
>   economy. This is *prescription* — build this.
> - **PART III — DIRECTIVES.** Architecture rules, what to reuse, and the specific traps that have
>   already cost this project weeks. Read this before writing code.
>
> **Provenance.** Part I was reconstructed by reading all 71 C# scripts and both procedural map builders
> in full. Part II merges two design documents with decisions the project owner made directly; where the
> owner's decisions and the old docs disagree, **the owner's decisions win** and are marked ★.

---

## 0. ORIENTATION

### 0.1 The pitch

You fight escalating waves of zombies on a hand-crafted map — buying doors open with points, flipping the
power on, drinking perks, gambling at the mystery box, upgrading your gun at Pack-a-Punch — and try to
survive. That is the *activity*.

The *reason to keep doing it* is the skin economy. Playing earns persistent currency. Currency buys cases.
Cases very occasionally yield a **serialized** item: a globally unique, permanently numbered skin —
"Starfade Camo **#37 of 250**" — that belongs to whoever pulled it, forever, and can be **traded to other
players**. A serialized pull can promote to **SCT**, and an SCT can promote again to **RGB**, the animated
top variant. Serial #1 of anything is a one-time event in the lifetime of the game.

Zombies is the engine. The chase is the retention.

### 0.2 The two halves

| | Half A — Zombies | Half B — The Economy |
|---|---|---|
| Currency | **Essence** (in-match points) | **Embers**, **Soul Crystals**, **Shards** (persistent) |
| Resets? | Every match | Never |
| Buys | Doors, perks, mystery box, Pack-a-Punch, ammo | Cases, trades |
| Purpose | The activity | The reason to repeat the activity |
| Status | **Largely built** | **Not built** |

**Critical rule: the economy is cosmetic-only.** Skins have zero gameplay effect. No stat, no advantage,
no exception. The game is not pay-to-win, and every design decision must preserve that.

### 0.3 Honest status of the existing build

The current Unity project is **not** a partial version of the designed game. It drifted into something
adjacent and, in places, better:

**What it actually is today:** a single-player, atmosphere-heavy zombies shooter with two large original
procedurally-generated maps, a quest chain, an extraction ending, a substantial game-feel layer, and a
*small local* serialized-collectible mint priced in match points.

**What the design documents describe:** a multiplayer BO2-parity zombies game with a CS:GO-style economy
running on persistent, tradable currency.

The single largest inversion: **`LootCrate.cost = 1500` is priced in Essence**, i.e. in-match points. The
designed economy is bought with *persistent* currency earned *across* matches. A chase economy cannot run
on a currency that evaporates when you die. **Part II fixes this.**

### 0.4 What to keep

The existing project contains real, hard-won value. A rebuild should carry forward, not discard:

1. **The mint** (`CollectionManager`) — sequential serials, edition caps, provenance, JSON persistence.
   Genuinely correct in structure. It needs a server, not a rewrite.
2. **The two maps' *design*** — zone progression, door pricing, spawn placement, the power cascade,
   extraction staging. All playtested. **Not** their construction method — see §12, which retires it.
3. **The game-feel layer** (`GameFeel`, `BloodFX`, `HitSounds`, `ZombieVoice`, `AmbientAudio`) — entirely
   self-building, zero art dependencies, all audio synthesized from raw samples.
4. **The 16 laws** (§14). Each one is a bug that already happened. They are the most valuable page here.
5. **The camo system** — 14 finished camo materials and a working unlock ladder. This is the Common tier
   of the skin economy, already made.

---

# PART I — AS BUILT

> ### ⚠ READ FIRST — script defaults are NOT what actually runs
>
> Part I was reconstructed by reading **source code**. But Unity serializes inspector values **per
> instance into the scene**, and a serialized value **always wins over the script default**. Several
> values below were tuned in code and then never re-applied to the scene objects — so the scene is still
> running the old numbers.
>
> **Confirmed divergences in `Map2.unity`:**
>
> | Field | Script default (this doc) | **Actually in the scene** |
> |---|---|---|
> | `EnemySpawner.timeBetweenSpawns` | `2` s | **`5` s** (`:192952`) |
> | `WeaponBob.walkBobSpeed` / `sprintBobSpeed` | `3.5` / `5.5` | **`14` / `18`** on *both* guns (`:195629`, `:198877`) |
> | `WeaponSway.swayMultiplier` / `smooth` | `1.1` / `4.5` | **`2` / `8`** (`:182681-2`) |
> | `GunSystem.reserveAmmo` | `180` (= `maxReserve`) | **`300`** on one gun, `maxReserve` still `180` (`:181360-1`) |
>
> **What this means concretely:**
> - The "way too fast" weapon bob that §4.2 describes as tuned away **is still live in every playable
>   scene** at 4× the intended speed. Same for the sway that was halved.
> - At 5 s between spawns, round 20's 100 zombies take **over 8 minutes** just to finish spawning. The
>   game's actual pacing is half what the code implies.
> - `RefillAmmo()` does `reserveAmmo = maxReserve` with no upper guard, so on the 300-reserve gun,
>   **paying 1000 points at the Ammo Crate destroys 120 rounds.**
>
> **Rule for the rebuild:** treat every number in Part I as the *intent*, and the scene as the *truth*.
> When they disagree, the divergence is itself a bug. In the new project, drive tuning values from
> **ScriptableObjects** (§18.1) — then there is exactly one source of truth and this class of bug becomes
> impossible.

## 1. PROJECT CONFIGURATION

### 1.1 Engine

| | |
|---|---|
| Unity | **6000.3.1f1** (Unity 6). Project originated from the **URP Empty Template**. |
| Pipeline | **URP 17.3.0**, **Deferred** rendering, **Linear** color space, SRP Batcher on. Shaders resolved by name at runtime: `Universal Render Pipeline/Lit`, `.../Particles/Unlit`, `Skybox/Procedural` |
| Renderer feature | **SSAO** — intensity 0.4, direct-lighting strength 0.25, radius 0.3, 1 sample |
| Quality tiers | Exactly 2: `Mobile_RPAsset` (Forward) and **`PC_RPAsset`** (active). Shadow distance **50** in the asset, but the Ashfall builder overrides it to **130** at runtime. |
| Input | **Active Input Handling = "Both".** Gameplay uses the **legacy Input Manager** exclusively (zero custom axes — stock template). The Input System asset is the unmodified Unity template and drives only the UI EventSystem. **Set this to "Both" or the project breaks in one direction or the other.** |
| Serialization | **Force Text** — all scenes are readable YAML |
| Assemblies | **No `.asmdef` anywhere**; everything compiles into `Assembly-CSharp` / `Assembly-CSharp-Editor`. No namespaces. |
| Text | TextMeshPro (`TextMeshProUGUI` for UI, `TextMeshPro` for world) |
| Nav | `com.unity.ai.navigation` 2.0.9 — `NavMeshSurface`, runtime `NavMeshAgent` |

Key packages: `ai.navigation` 2.0.9, `cinemachine` 2.10.5, `inputsystem` 1.17.0, `postprocessing` 3.5.1,
`probuilder` 6.0.8, `render-pipelines.universal` 17.3.0, `shadergraph` 17.3.0, `timeline` 1.8.9,
`ugui` 2.0.0, `visualscripting` 1.9.9.

> **Removed deliberately:** `com.unity.ai.assistant`, `ai.generators`, `ai.inference` — these spammed
> `ApiNoLongerSupported` errors. Do not re-add. `ai.navigation` is required and stays.

### 1.2 Tags and layers — load-bearing

| Tag | Used by | Consequence if missing |
|---|---|---|
| `Interactable` | `InteractionSystem` raycast gate | **Nothing is buyable.** No machine, door, or box responds. |
| `Head` | `GunSystem` headshot test (`hit.collider.CompareTag("Head")`) | **Headshots never register.** No 130-point kills, no camo progression, no ding. |

**Layers — exact indices** (`TagManager.asset`). Indices 3 and 6–9 are project-defined:

| Index | Layer | Notes |
|---|---|---|
| 3 | **`Player`** | Normally blank in stock Unity |
| 6 | **`Enemy`** | Zombie root, `Head` and `BodyAdjuster` all sit here |
| 7 | **`Minimap`** | |
| 8 | **`Weapon`** | |
| 9 | **`DoorDebris`** | Created by the map builders. Excluded from the navmesh bake; blocking is done by a carving `NavMeshObstacle`. |

> **The physics layer collision matrix is completely default — every layer collides with every layer.**
> All layer separation in this game is done by raycast `LayerMask` fields in scripts, not by the matrix.

Two rules attach to `DoorDebris` and both are mandatory:

```csharp
cam.cullingMask |= (1 << debrisLayer);          // every camera — else doors render INVISIBLE
surface.layerMask = ~(1 << debrisLayer);        // navmesh bake — else debris bakes as solid floor
```

> Layer **indices** are assigned by the editor and were not verified during this reconstruction. Read them
> from `TagManager.asset`; never hard-code an index, always resolve by name.

### 1.3 Scenes

| Scene | Role |
|---|---|
| `MainMenu` | Entry point |
| `CharacterSelect` | 3-model carousel; writes `SelectedCharacter`, loads `SampleScene` |
| `SampleScene` | The original test map — still the default gameplay scene |
| `Map2` | **Ashfall Ridge** — generated by `Tools > Ashfall Ridge > Build Map` |
| `Map3` | **Last Ride / Blackout Carnival** — generated by `Tools > Last Ride > Build Map` |

Build settings order: `MainMenu` (0), `SampleScene` (1), `CharacterSelect` (2), `Map2` (3), `Map3` (4).
All scene loading is **by name**, so only index 0 matters.

Map2 and Map3 are **build outputs**. Each builder copies `SampleScene`, then `PruneOldLevel()` destroys
every root that doesn't contain one of: `FPSController, Canvas, EventSystem, ScoreManager, EnemySpawner,
GameFeedback, CollectionManager, PowerUpManager, GobblegumManager, PlayerPerks`.

> **That keeper list has a serious omission.** `PauseMenu`, `SettingsMenu` and `CrosshairManager` all live
> on a `System` GameObject that is **not** in the list, so it is destroyed. **Map2 and Map3 ship with no
> pause controller at all** — the `PauseMenu` and `SettingsMenuFull` panels still exist under the Canvas
> with nothing driving them, so `Esc` does nothing on either real map. `Minimap Camera` is pruned the same
> way while the HUD `RawImage` that displays it remains.

**Player rig** (identical object IDs across all three gameplay scenes):

```
Player [FPSController, CharacterController, PlayerHealth, InteractionSystem, (+PlayerPerks in Map2/3)]
└─ CameraHolder
   └─ Main Camera [Camera, AudioListener, «Recoil» MISSING SCRIPT]
      ├─ WeaponHolder [WeaponSway, «CameraRecoil» MISSING SCRIPT, WeaponSwitcher]
      │  ├─ Gun      [WeaponBob, SmartCamoScroller] └─ Glock [GunSystem, CamoSystem]
      │  └─ SuperGun [WeaponBob, SmartCamoScroller] └─ M4    [GunSystem, CamoSystem, WeaponAiming]
      ├─ 3 character bodies (Miyoko, Inquisitor M, Inquisitor F)
      └─ WeaponCamera [Camera, AudioListener]
```

Note **3 AudioListeners** in the scene (Main Camera, WeaponCamera, Minimap Camera) — Unity warns about this.
Fix it in the rebuild.

### 1.4 Art assets — and how much of it is dead weight

`Assets/` is **~6.9 GB**. Very little of it is used.

| Pack | Size | Used for | Verdict |
|---|---|---|---|
| **IdaFaber** | 2,905 MB | 2 of 3 playable characters (Inquisitor M/F) | 2 of 25 prefabs used |
| **ZombieM_02** | 1,707 MB | **The zombie** — mesh + 14 animation clips | Load-bearing |
| **Ida Faber** *(with space — a different pack)* | 594 MB | 3rd character (Miyoko) | 1 prefab used |
| **ShadowVultureMaterialPack** | 575 MB | **3 texture folders + 1 shader** for camos | ~99% dead |
| **SnowyCocoon** | 377 MB | **4 camo textures** | Bloody Cellar sub-pack entirely unused |
| **FPS Gun Pack Vol. 1** | 312 MB | `Pistol 2.fbx` → the Glock | 1 of 3 guns used |
| **DelthorGames MCX** | 76 MB | `AKM.fbx` → **the "M4" is actually an AKM mesh** | 1 of 3 guns |
| **SciFi Assets** | 9.1 MB | 4 UI sprites | 0 prefabs used |
| **Zombie**, **Low Poly Weapons VOL.1**, **Sherbbs Particles**, **Particle Attractor** | 53 MB | currently unreferenced | **Keep** — paid assets, and a natural source for extra weapons/enemies |
| **TextMesh Pro** | 3.9 MB | Every text element in the game | Critical |

Own content: **14 camo materials** in `Assets/Camos/` (4 of which use a custom `PlasmaShader` Shader Graph),
2 fonts (LEMONMILK for UI, brush for score popups), and **exactly 5 MP3s** — gunshot, reload click, and 3 UI
sounds. **Everything else you hear — zombie groans, wind, ambience, hit sounds, the carousel melody — is
synthesized in C# at runtime from raw samples. Zero audio files.**

> ★ **Keep every asset pack. They were bought and paid for — do not delete or prune them.**
>
> The disk figures above are *not* a shipping problem. **Unity only includes assets that are actually
> referenced** from a scene in the build list (or placed in a `Resources/` folder). Unreferenced packs add
> **zero bytes to the built game** — they cost editor import time and local disk only. There is no `Resources/`
> folder in this project apart from TextMeshPro's, so nothing is being pulled in by string lookup either.
>
> The unused packs are **inventory, not waste**: Low Poly Weapons VOL.1 has 16 guns ready for the wallbuy
> system (§16), the `Zombie` pack has 3 alternate enemy meshes for special enemies, and Sherbbs has 35
> particle prefabs. Treat them as content already bought for later phases.
>
> **The apparent gap — no environment kit — is covered without spending anything.** See the Phase 3 art
> box in §17: structure is a small modular kit authored on a grid and surfaced with the **432 PBR material
> sets already in `ShadowVultureMaterialPack`**, and props are generated by **Shadow's Asset Forge**.
> A wall's quality lives in its material, not its mesh.

**Key prefabs** (`Assets/Prefabs/`, 8 total):

- **`Enemy.prefab`** — the zombie. Layer 6, full ragdoll (12 Rigidbodies, 10 CharacterJoints, 12 colliders),
  Animator on `ZombieController`, and a child **`Head` correctly tagged `Head`** on layer 6.
  **It has no NavMeshAgent** — `EnemyAI.Start()` adds one at runtime (§5.1).
- `Glock.prefab` / `M4.prefab` — but note the scenes **do not instance `Glock.prefab`**; they instance the
  raw `Pistol 2.fbx` and add `GunSystem`/`CamoSystem` on the instance. The pistol therefore exists in two
  independently-wired forms, and only `MasterWeaponList.asset` reaches the prefab.
- `BulletTracer`, `DamagePopup` (world TMP), `ScorePopup_UI` (screen TMP).

`Assets/MasterWeaponList.asset` is a `WeaponLibrary` ScriptableObject holding exactly 2 guns. It is the only
weapon registry.

> **IP note.** Anything derived from Black Ops 2 directly — audio, models, textures, the name — is fine for
> private learning and **not** fine for public release. "Black Ops 2 Zombies" is Activision IP. The game
> already has its own name, its own maps, and its own characters; keep it that way and the problem
> disappears.

### 1.5 Animator contract

The zombie animator (`ZombieController`) exposes exactly these parameters, and `EnemyAI` drives them:

| Parameter | Type | Driven by |
|---|---|---|
| `IsWalking` / `IsRunning` / `IsSprinting` | bool | Round-band speed state; exactly one true at a time |
| `Attack` | trigger | Swing start |
| `WalkIndex` | int | `Random.Range(0, walkCount)` — walk variant, set once at spawn |
| `AttackIndex` | int | `Random.Range(0, attackCount)` — attack variant, set per swing |

---

## 2. ARCHITECTURE MAP

### 2.1 Globals

| Global | Type | Notes |
|---|---|---|
| `ScoreManager.instance` | singleton | Points + round number. **Lowercase `instance`** — the convention across this project. |
| `PowerUpManager.instance` | singleton | Power-ups **and the game's general announcement banner** |
| `GameFeedback.instance` | singleton | Floating point popups |
| `PlayerPerks.instance` | singleton | Perk ownership + all perk multipliers |
| `CollectionManager.instance` | singleton | The mint |
| `ObjectiveManager.instance` | singleton | Quest chain |
| `ExtractionSystem.instance` | singleton | Extraction |
| `PowerSwitch.instance` | singleton | Plus `static bool IsPowered` |
| `GameFeel.instance` | singleton | **Only assigned inside `EnsureExists()`**, never in `Awake` |
| `PauseMenu.GameIsPaused` | static bool | The most-read global in the project |
| `BuyableDoor.totalOpened` | static int | Reset by `ObjectiveManager.Awake` |
| `ObjectiveManager.mysteryBoxUsed` | static bool | Reset by `ObjectiveManager.Awake` |
| `BloodFX`, `HitSounds` | pure static | MonoBehaviours never attached to anything |

> No singleton uses `DontDestroyOnLoad`, and none null-check before assigning. They are per-scene and the
> last loaded wins. Part III replaces this pattern.

### 2.2 Self-building systems (require no prefabs, no scene setup)

`GameFeel` (own GameObject + vignette canvas + muzzle light + 3 synthesized clips) · `BloodFX`
(particle system + generated softdot texture + 2 materials + 60-decal pool) · `HitSounds` · `ZombieVoice`
(auto-added by `EnemyAI`) · `AmbientAudio` · `PowerUpManager` (announce text + orbs from primitives) ·
`ExtractionSystem` (fade overlay) · `WindPaper`.

This is a genuinely good property. Preserve it.

### 2.3 The pause contract

`PauseMenu` sets `Time.timeScale = 0` **and** `GameIsPaused = true`. Both are needed: input still fires at
`timeScale 0`, so `Input.GetKeyDown(KeyCode.F)` would silently spend points while paused.

Every gameplay `Update()` early-returns on `PauseMenu.GameIsPaused`. `PauseMenu.Awake()` resets the flag to
false — without that, pausing and quitting to menu leaves the static `true` and the *next* match starts
frozen.

---

## 3. THE PLAYER

### 3.1 `FPSController` — movement and look

`[RequireComponent(typeof(CharacterController))]`

| Field | Default | Unit |
|---|---|---|
| `walkSpeed` | `6` | m/s |
| `sprintSpeed` | `10` | m/s |
| `jumpForce` | `8` | m/s initial |
| `gravity` | `20` | m/s² (applied manually) |
| `lookSpeed` | `2.0` | deg per mouse-axis unit |
| `lookXLimit` | `85` | deg pitch clamp ± |
| `recoilSnappiness` | `10` | lerp rate current→target |
| `recoilReturnSpeed` | `15` | lerp rate target→0 |
| `speedMultiplier` | `1` | set by `SlowZone` |
| `isSprinting` | `false` | **public; written by `GunSystem`** |

Sprint: `isSprinting = Input.GetKey(LeftShift) && !Input.GetButton("Fire1")`. Stamin-Up multiplies
`sprintSpeed` by `1.3` → 13 m/s.

Recoil is a **camera-visual offset only** — subtracted from pitch when composing the camera rotation, never
fed back into `rotationX`. It does not permanently alter aim, but because the bullet ray originates at the
camera, an active kick *does* deflect shots while decaying.

```csharp
public void AddRecoil(float up, float side, float tilt)  // `tilt` is accepted and IGNORED
{
    targetRecoilXPos += up;
    targetRecoilYPos += Random.Range(-side, side);
}
```

### 3.2 `PlayerHealth`

| Field | Default |
|---|---|
| `maxHealth` | `100` (→ `250` with Juggernog) |
| `regenDelay` | `4` s since last damage |
| `regenRate` | `10` HP/s |
| `isInvincible` | `false` — set true by the extraction cinematic |

`TakeDamage(int)` → early-out if invincible; subtract; stamp `lastDamageTime`; `GameFeel.OnPlayerHit()`;
`Die()` at ≤ 0.

`Die()` checks Quick Revive first: if `PlayerPerks.instance.UseQuickRevive()` returns true, revive at
**50% max HP** and consume the perk. Otherwise **reload the current scene** — there is no death screen, no
score summary, and no run is recorded. (Part II replaces this: death must bank the run's rewards.)

### 3.3 `PlayerPerks` — the perk registry

Perks live in a `List<PerkType>`, **max 4**, **not persisted**, lost on death (scene reload).

| Perk | Field | Value | Consumer queries it… |
|---|---|---|---|
| Juggernog | `juggernogHealth` | `250` HP | pushed once at purchase via `ApplyJuggernog` |
| Speed Cola | `speedColaMultiplier` | `0.5` × reload time | `GunSystem.Reload` every reload |
| Double Tap | `doubleTapDamage` | `2.0` × damage | `GunSystem.Shoot`, **after** the headshot multiplier |
| Double Tap | `doubleTapFireRate` | `0.75` × interval (+33% RPM) | `GunSystem.Update` |
| Stamin-Up | `staminUpMultiplier` | `1.3` × sprint | `FPSController.Update` |
| Quick Revive | — | cheat death once | `PlayerHealth.Die` |

`GivePerk` only guards duplicates — **the machine must call `CanBuyPerk` itself** to enforce the 4-slot cap.

### 3.4 `InteractionSystem` — every purchase in the game

Raycast `3 m` from the camera, no layer mask, must hit tag `Interactable`, key **F**. Blocked entirely
while paused. Branches evaluated in order, first match wins:

| # | Target | Detection | Action |
|---|---|---|---|
| 1 | Ammo Crate | `GetComponent<AmmoCrate>()` | `BuyAmmo()` — 1000 |
| 2 | **Mystery Box** | **`hit.collider.name.Contains("MysteryBox")`** | `BuyMysteryBox()` — 950 hardcoded |
| 3 | Gobblegum | `GetComponent<GobblegumMachine>()` | `BuyGum()` — 500 |
| 4 | Loot Crate / Sacred Case | `GetComponent<LootCrate>()` | `OpenCrate()` |
| 5 | Perk machine | `GetComponent<PerkMachine>()` | `BuyPerk()` |
| 6 | Pack-a-Punch | `GetComponent<PackAPunchMachine>()` | `UpgradeWeapon()` — 5000 |
| 7 | Buyable door | `GetComponent<BuyableDoor>()` | `Buy()` |
| 8 | Power switch | `GetComponent<PowerSwitch>()` | `ActivatePower()` |

The mystery box is **name-string matched with no component** and always grants weapon slot 1. It is a
placeholder, not a feature. Part II replaces it.

### 3.5 Input scheme (complete)

| Input | Effect |
|---|---|
| `W A S D` | Move |
| Mouse | Look (pitch clamped ±85°) |
| `Shift` | Sprint |
| `Space` | Jump |
| `LMB` | Fire (hold = auto if `allowButtonHold`) |
| `RMB` | ADS |
| `R` | Reload |
| `F` | Interact / buy |
| `1` `2` | Weapon slots |
| Scroll (either direction) | Toggle weapon |
| `5` | Eat gobblegum |
| `C` | Collection menu |
| `Esc` | Pause |

---

## 4. WEAPONS

### 4.1 `GunSystem` — one instance per weapon object

| Field | Default | Notes |
|---|---|---|
| `damage` | `10` | per body shot |
| `fireRate` | `0.1` | **seconds between shots** → 600 RPM |
| `headshotMultiplier` | `5` | |
| `allowButtonHold` | `true` | full-auto |
| `impactForce` | `2` | impulse on non-kinematic hits |
| `recoilUp` | `2` | deg pitch per shot |
| `recoilSide` | `1` | ± deg yaw |
| `magazineSize` | `30` | |
| `reserveAmmo` / `maxReserve` | `180` | |
| `reloadTime` | `1.5` | s |
| `sprintToFireDelay` | `0.3` | s after sprint before the gun fires |
| `tracerWidth` | `0.05` | |
| `whatIsEnemy` | LayerMask | **the only mask for the damage ray** |

**Firing is a perfectly accurate hitscan. There is no spread, bloom, or cone of fire.**
`Physics.Raycast(camera.position + forward * 0.1f, forward, out hit, 100f, whatIsEnemy)`.

**Damage math, in order:**

```
final = damage
      × (headshot ? headshotMultiplier : 1)      // ×5
      × (DoubleTap ? doubleTapDamage : 1)        // ×2, applied AFTER headshot
if (InstaKill) final = 999999
```

**Points:**

| Event | Points |
|---|---|
| Hit, target survives | **+5** |
| Body kill | **+90** |
| Headshot kill | **+130** |
| Hitting a corpse | **0** |

Corpses remain collider-hittable for seconds after death. `bool wasAlive = target.health > 0` is sampled
**before** `TakeDamage` and gates all payout. Without it, corpses could be farmed indefinitely.

Default zombie has `Target.health = 50`, so a base gun is **5 body shots or 1 headshot**.

**Reload:** `reloadTilt = sin(π · clamp01(elapsed / duration))` — a 0→1→0 curve published for
`WeaponAiming` to read. Speed Cola halves the duration; the reload *sound* is not re-pitched.

**Pack-a-Punch** (`PackAPunch()`, idempotent): `damage ×2`, `magazineSize ×1.5` (30→45),
`maxReserve ×2` (180→360), then `RefillAmmo()`. `fireRate`, `reloadTime` and `headshotMultiplier` are
untouched.

**Camo progression hook:** `RegisterKill(bool wasHeadshot)` feeds `CamoSystem`. A **rapid-kill streak** =
10 kills with no more than **5 s** between consecutive kills.

**Casings:** a cube scaled `(0.018, 0.018, 0.045)`, collider destroyed, ejected right+up with random spin,
destroyed after 1 s. Shared static material, URP/Lit, colour `(0.78, 0.6, 0.22)`, smoothness 0.7.

### 4.2 Weapon pose stack

Three scripts write to the weapon transform and they must not fight:

| Script | Writes | Rule |
|---|---|---|
| `WeaponAiming` | `localPosition` + `localRotation` (lerp toward pose) | Hip / ADS / sprint poses, rate `Time.deltaTime * 8` |
| `WeaponBob` | `localPosition` **as a delta** (`+= offset - lastOffset`) | Composes safely with WeaponAiming |
| `WeaponSway` | `localRotation` **absolutely** | **Must live on a different GameObject** than WeaponAiming |

**`WeaponBob` v2 — figure-8, velocity-driven:**

```csharp
offset = new Vector3(
    Mathf.Sin(timer)      * currentAmount * sideSwayRatio,   // X at step rate
    Mathf.Sin(timer * 2f) * currentAmount + landOffset,      // Y at DOUBLE step rate
    0f);
transform.localPosition += offset - lastOffset;   // delta, never absolute
```

Defaults: `walkBobSpeed 3.5`, `walkBobAmount 0.03`, `sprintBobSpeed 5.5`, `sprintBobAmount 0.055`,
`sideSwayRatio 0.6`, `smoothing 8`, `landDip 0.06`.

> **Law:** the Y axis runs at **2× the speed field**, so speed values must be roughly half what a naive
> up-down bob would use. These numbers are post-tuning after "way too fast" feedback. Amplitude is driven
> by real `CharacterController.velocity`, not by input, so it fades correctly when you walk into a wall.

**`WeaponSway`:** `smooth 4.5`, `swayMultiplier 1.1` (both were doubled originally — "the gun snapped
around too fast"). Uses `GetAxisRaw`.

**Reload flourish** (applied by `WeaponAiming` from `GunSystem.reloadTilt`): pitch `+32° × tilt`,
roll `−26° × tilt`, position `0.06 m × tilt` down.

### 4.3 `CamoSystem` — the existing skin system

Per-weapon progression saved to PlayerPrefs, keyed by `weaponName` (`"Glock"`, `"M4"`).

| Key | Default | Meaning |
|---|---|---|
| `<Weapon>_Headshots` | 0 | Stage-1 counter |
| `<Weapon>_RapidKills` | 0 | Stage-2 counter |
| `<Weapon>_Camo_<0..13>` | 0 | 1 = unlocked |
| `<Weapon>_EquippedCamo` | **−1** | −1 = default material |

**The 14 camos, index order:** Dust, Ice, Python, Bengal, Crimson, Blue Tiger, Urban, Red Hex, Gold,
Diamond, Digital, Dark Matter, Abyssal Flow, Cosmic Aether.

| Tier | Indices | Requirement |
|---|---|---|
| Stage 1 | 0–9 | 10 / 25 / 50 / 75 / 100 / 150 / 200 / 250 / 300 / **350** headshots |
| Stage 2 | 10–12 | *after 350 headshots*: 5 / 10 / 15 rapid-kill streaks |
| Stage 3 | 13 | camo 12 unlocked on **every** weapon |

Headshots stop counting once stage 1 completes; rapid-kill streaks don't count until it does.

**Application:** `ApplyCamo(index)` overwrites every material slot on every child renderer, skipping
renderers named `Flash`/`Muzzle`, `ParticleSystemRenderer`, `LineRenderer`, and UI text. It is **private
and called only from `Awake`** — equipping a camo requires a scene reload to take effect. `SmartCamoScroller`
animates `_BaseMap` UV offset when the material name contains `"Cosmic"` (matched with `Contains` because
runtime instances get a `" (Instance)"` suffix).

> This system is ~80% of the skin equip UI already. Part II extends it rather than replacing it.

### 4.4 `WeaponSwitcher` — a known limitation

Two hardcoded slots. `Start()` **overwrites** `weaponsUnlocked` to `{ true, false }` every scene load, so
you always begin with the pistol only. Scroll wheel toggles regardless of direction. **No pause guard.**
This must be replaced with a list-driven inventory to support wallbuys and 30+ weapons.

---

## 5. ENEMIES

### 5.1 `EnemyAI` — the zombie brain

**The single most important fact:** `Start()` **adds the `NavMeshAgent` from code if one is missing.**

```csharp
agent = GetComponent<NavMeshAgent>() ?? gameObject.AddComponent<NavMeshAgent>();
```

The zombie prefab never actually had an agent. Every zombie silently ran a legacy
`LookAt + Translate` path — which is why they walked through walls, always faced the player, and
spun in place, and why months of "agent tuning" changed nothing. The legacy branch is now deleted
entirely: **zombies pathfind or they wait and warp. They never ghost.**

**Agent settings written from code:**

```
height              = 2
speed               = (from round band)
stoppingDistance    = attackRange * 0.9   → 2.25
angularSpeed        = 480 deg/s
acceleration        = 30 m/s²
autoBraking         = false
radius              = 0.35            (slim, so hordes fit doorways)
obstacleAvoidanceType = MedQuality
avoidancePriority   = Random.Range(20, 80)   ← anti-oscillation
```

Root `Rigidbody` is forced `isKinematic = true` — a dynamic root RB fights the agent and produces walk
stutter.

**Round bands** (`ApplyRoundStats`):

| Round | Speed | State |
|---|---|---|
| 1–4 | `1.5` m/s | walk |
| 5–9 | `3.5` m/s | run |
| 10+ | `6.0` m/s | sprint |

`Target.health *= 1.1^(min(round, 50) − 1)` — **+10% compounding, capped at round 50** (×106.7 → 5,335 HP).

**Variety, rolled at spawn:**
- Scale jitter `×Random.Range(0.93, 1.07)`
- **12% crawlers**: speed `×0.6`, model pitched `+60°` and dropped, voice pitch `×0.7`, never run/sprint
- Sprinters tinted to `0.62` brightness via `MaterialPropertyBlock`
- `Stagger()` on **25%** of non-killing hits: agent stops, model scales to `0.94` for **0.22 s**

**Repath throttling** — both conditions required:
`Time.time >= nextRepathTime` (0.35 s → max ~2.9/s) **and** the player has moved > 1 m.
Per-frame repathing was itself a source of walk stutter.

**Off-mesh recovery:** every 1 s, `NavMesh.SamplePosition(pos, 6f)` → `agent.Warp(hit.position)`.

**Attack:** at ≤ 2.5 m the agent stops and the zombie turns *physically* at **240°/s** (`RotateTowards`) —
sprint past one and it must actually turn around. Swing: growl → trigger → wait `damageDelay 0.5 s` →
**abort if dead** → if still within `attackRange + 0.5` deal **10** damage → wait `1.5 s`. Cadence
`animationTotalTime + 0.1` = **2.1 s**.

**Death sequence — exact:**

```
Die()  → root collider off, agent off, animator off
       → SetRagdollState(true)
       → EnemySpawner.EnemyKilled()
       → PowerUpManager.TryDrop(position)
       → Destroy(gameObject, 25f)          // failsafe only

FreezeRagdoll():
  wait 1.1 s
  all child RBs: linearDamping 4, angularDamping 6
  poll every 0.25 s (max 4 s) until no child RB exceeds 0.01 sqrMagnitude
  all child RBs → isKinematic = true
  wait 3.5 s                                // the corpse lies there
  sink at 0.55 m/s for 2.5 s                // 1.375 m into the ground
  Destroy
```

Corpse lifetime **7.1 – 11.1 s**. The component stays *enabled* after death (Update guards on `isDead`) so
the coroutines keep running.

**Ragdoll rules:**
- Child colliders stay **enabled at all times**, alive or dead. Disabling them also disabled the head
  hitbox, so headshots stopped registering. Alive = kinematic bones; dead = dynamic.
- On activation every `CharacterJoint` gets `enableProjection = true`, `projectionDistance = 0.05`,
  `projectionAngle = 60` — this is the fix for heads stretching away from bodies when a kill shot's impulse
  lands on a freshly-dynamic head.

### 5.2 `EnemySpawner` — the round director

| Field | Default |
|---|---|
| `initialGameDelay` | `5` s |
| `timeBetweenSpawns` | `2` s |
| `roundEndPulseDuration` | `3` s |
| `breakBetweenRounds` | `3` s |
| `preferMinDistance` / `preferMaxDistance` | `18` / `70` m |

**`zombiesPerRound = round * 5`** — R1=5, R10=50, R25=125. Spawned on a flat 2 s metronome.

> **There is no cap on concurrent zombies.** Round 25 spawns 125 zombies over 250 seconds regardless of how
> many are alive. This is a performance and design problem; Part II adds a cap.

**Spawn selection** — up to 12 attempts:
1. Pick a random spawn point.
2. *Attempts 0–7 only:* reject if outside 18–70 m of the player. (Attempts 8–11 accept any distance so
   rounds can never stall.)
3. `NavMesh.SamplePosition` on both the candidate and the player; if either fails, return the candidate
   (no navmesh baked → legacy behaviour).
4. **Sky-walker guard:** reject if the sampled navmesh sits > 1.2 m *above* the candidate (mesh on a prop
   roof).
5. Require a `PathComplete` path from spawn to player.

Then ground-snap: raycast down 50 m and place at the hit.

**Round start:** increment → write `ScoreManager.roundNumber` → update `BestRound` → **roll Ash Shards** →
set counts → animate the round number → `AmbientAudio.PlayRoundSting()` → spawn wave.

**Round number UI:** Roman numerals I–XXV, then plain digits. Animation: fade in over 1 s at 300 pt centre
screen → hold 1.5 s → 1 s simultaneous move to the corner and shrink to 100 pt.

> `RoundEndSequence` must null-guard `roundText`. A missing reference used to throw and **stop rounds
> forever**.

### 5.3 `Target` — health and damage routing

`health = 50` default. `TakeDamage(float amount, int pointsToAdd, bool isHeadshot, Vector3 hitPoint)`.

Awards **no score** — `GunSystem` owns scoring. (Both paying was the original double-scoring bug: +90 popup,
+180 actual.)

Damage popup is suppressed when `points == 0` — that's the **Nuke** case, which calls
`TakeDamage(health + 1, 0, ...)` on every zombie and would otherwise carpet the map in "+0".

Death routes through `EnemyAI.Die()` when present (ragdoll path); otherwise falls back to
`EnemySpawner.EnemyKilled()` + `Destroy`.

### 5.4 `ZombieVoice` — synthesized groans

3 clips, 22050 Hz mono, base frequencies **68 / 82 / 98 Hz**, durations 2.1 / 2.4 / 2.7 s, deterministic
seeds. Ambient groan every **4–11 s** at pitch `0.82–1.18 × pitchScale`; `AttackGrowl()` at **1.2–1.4×**.
3D, linear rolloff, 2–24 m, volume 0.55.

**The recipe:**

```
every 441 samples (50×/s):  freqTarget = base × Random(0.82 .. 1.22)
each sample:  freq = Lerp(freq, freqTarget, 0.004)          // slow glide
              phase += 2π · freq / rate
voice = sin(phase)·0.5 + sin(phase·1.98)·0.26
      + sin(phase·3.07)·0.12 + sin(phase·4.13)·0.05          // DETUNED, never integer harmonics
breath = onePoleLowpass(white, 0.92 / 0.08)
envelope = max over 2–3 bursts of  sin(π · bt / burstLength)  // 0.50–1.05 s bursts, 0.15–0.40 s gaps
sample = (voice·0.6 + breath·0.85) · envelope · 0.55
```

> **SYNTH LAW (learned the hard way).** Version 1 used sine vibrato and perfect integer harmonics and
> "sounded like bees". A voice needs: **random-walk pitch + detuned partials + filtered noise + burst
> envelopes.** Never sine-vibrato.

### 5.5 `ZombieStabilizer`

Sinks the model to `spawnHeight −1.0` at spawn, then eases to `0` at **1.5 m/s** (a snap caused visible
spawn stutter). `LateUpdate` **early-outs when `EnemyAI.IsDead`** — clamping a corpse's height every frame
made dead zombies twitch forever.

> Known mismatch: `spawnDuration = 3.5` s vs `EnemyAI.standUpTime = 3.0` s. The AI unfreezes half a second
> before the model finishes rising. Pick one value.

---

## 6. MACHINES

| Machine | Cost | Power? | Behaviour |
|---|---|---|---|
| `AmmoCrate` | **1000** | no | Refills every active `GunSystem` |
| `GobblegumMachine` | **500** | no | 2 s dispense, grants a random gum 1–3 |
| `PerkMachine` | per-machine | **yes** | Gates: power → singleton → not owned → `CanBuyPerk` → spend → grant |
| `PackAPunchMachine` | **5000** | **yes** | 3 s upgrade, once per gun; spends *before* the wait |
| `LootCrate` | **1500** pts | no | Mints a collectible |
| `LootCrate` (Sacred Case) | **3 Ash Shards** | no | Mints with premium odds |
| Mystery Box | **950** | no | Name-matched placeholder; always grants slot 1 |

Perk costs vary per map (see §13). Machines without power show **"REQUIRES POWER"**.

**`GobblegumManager`** — one gum at a time, eaten with **`5`**:

| Gum | Effect | Duration |
|---|---|---|
| `InPlainSight` | `walkSpeed += 5` — **the "zombies ignore you" part is not implemented** | 10 s |
| `MaxAmmo` | Refills all guns *including inactive ones* | 1 s |
| `Perkaholic` | **Not implemented — logs to console and does nothing** | 30 s |

---

## 7. POWER-UPS

`PowerUpManager` drops on **4%** of kills, uniform 25% across four types, orb lives **20 s**.

| Type | Colour | Effect |
|---|---|---|
| Max Ammo | blue `(0.2, 0.6, 1)` | Refills all guns, inactive included |
| Insta-Kill | red | `instaKillActive` for **30 s** → damage becomes 999999 |
| Double Points | gold `(1, 0.85, 0.2)` | `doublePointsActive` for **30 s** |
| Nuke | green `(0.4, 1, 0.3)` | Kills all via `TakeDamage(health+1, 0, …)`, awards flat **400** |

Orbs are built from primitives at runtime: a 0.5-scale trigger sphere with a kinematic rigidbody, a point
light (range 6, intensity 3), spin 90°/s, bob 0.25 m at 2 rad/s.

Double Points multiplies **at credit time** inside `ScoreManager.AddScore`, so points earned during the
window are permanently banked.

> Bug to fix: the two timed power-ups don't stack. Picking up a second Insta-Kill starts a second coroutine
> whose expiry clears the flag early.

`PowerUpManager.Announce(string)` is the game's **general banner API** — quests, extraction, shards and the
power switch all route through it. It self-builds a 44 pt centre-top text if unwired.

---

## 8. THE FEEL LAYER

### 8.1 `GameFeel` — self-building, zero setup

| Effect | Numbers |
|---|---|
| Muzzle light | Shared point light, `(1, 0.7, 0.3)`, range 8, intensity 2.8, **2 frames** |
| Shot punch | FOV `+2` per shot, capped `+5`, decays at rate 14 |
| Sprint FOV | `+6`, lerp rate 6 |
| Landing dip | 0.06 m + thud, decay rate 8 |
| Damage response | vignette pulse 0.6, shake 0.09, whump at pitch 0.9–1.05 |
| Shake | Perlin at 30 Hz, decay rate 6 |
| Vignette | `(0.45, 0, 0)`, max alpha 0.5 |
| Low-health | below **35%** HP: red glow + heartbeat, interval `Lerp(0.55, 1.15, frac/0.35)` s |
| Hit-stop | `timeScale 0.05` for **45 ms real time**, on headshot kills only |

**Synthesized clips** (22050 Hz mono):

| Clip | Length | Formula |
|---|---|---|
| whump | 0.35 s | `(sin(2π·70·t)·0.8 + noise·0.3·e^(−40t)) · e^(−14t)` |
| thud | 0.22 s | `(sin(2π·58·t)·0.8 + noise·0.12·e^(−40t)) · e^(−22t)` |
| heartbeat | 0.50 s | two 58 Hz thumps 170 ms apart, second at 80%, decay 20 |
| headshot ding | 0.28 s | `(sin(2π·**1568**·t)·0.6 + sin(2π·**2352**·t)·0.28) · e^(−20t)`, played at volume 0.28 |

1568 Hz with a partial at exactly 1.5× "reads as *ding*, not *beep*".

> `GameFeel` writes `cam.transform.localPosition` every `LateUpdate` and captures its baseline the first
> frame it finds the camera. Nothing else may write camera local position. It also captures `baseFov` once,
> so changing FOV in settings mid-game is overwritten every frame.

### 8.2 `BloodFX` — pure static

One shared world-space particle system, `maxParticles 800`, burst-only via `Emit`, gravity 1.5.

| Call | Content |
|---|---|
| `Spawn(pos, normal, big)` | 15 (26 if headshot) droplets 0.06–0.14 + 3–4 mist puffs 0.28–0.5 at alpha 0.4; **50% chance of a decal** |
| `HeadPop(pos)` | 30 droplets, 6 mist, guaranteed decal |
| `Gibs(pos)` | 2–3 rigidbody cubes 0.05–0.1, destroyed after 1.4–2.2 s |
| `WallImpact(pos, normal)` | 8 grey chips + 1 dust puff |

**Decals — a 60-entry round-robin pool.** Each is a **3-blob irregular cluster** of flattened cubes, not a
single quad. Placement rules, all of which were bug fixes:

```
RaycastAll downward, sorted by distance, then reject:
  · anything with a Target in its parents      → the zombie's own hitboxes ("floating red rectangle")
  · anything with a BuyableDoor                → debris sinks later, orphaning the splat
  · any non-kinematic rigidbody                → movable surfaces
require ground.normal.y >= 0.65                → floors only, no wall carpets
hover = 0.008 + (i % 12) * 0.0006              → millimetres, and WRAPPED
```

> The old hover was `i * 0.0004`, which reached a visible **4 cm** by splat #60. Cap and wrap it.

**`MakeSoftDot()`** — a generated 64×64 RGBA radial-alpha sprite: `alpha = clamp01(1 − dist)²`.

> **PARTICLE LAW.** Particle materials had **no texture**, so every particle in the game rendered as a
> literal white square. Every particle material gets `softdot` **and** transparent surface setup
> (`_Surface = 1`, SrcAlpha/OneMinusSrcAlpha, ZWrite 0, `_SURFACE_TYPE_TRANSPARENT`, transparent queue).

---

## 9. MAP RUNTIME COMPONENTS

| Component | Key values |
|---|---|
| `BuyableDoor` | `cost 750`, sinks `4 m` over `1.5 s`; disables its `NavMeshObstacle` + collider **immediately** on purchase; `static totalOpened` |
| `PowerSwitch` | `static IsPowered` — **true when no switch exists in the scene** (older maps keep working); cascade rolls main lights on **0.12 s apart, sorted nearest-first** |
| `SlowZone` | `slowMultiplier 0.8`; sets `FPSController.speedMultiplier`; no reference counting |
| `KillPit` | `fallDamage 50`; teleports the player (disabling the CharacterController for the frame); kills zombies for **0 points** |
| `Rotator` | `(0, 45, 0)` deg/s, bob 0.15 m at 1.5 rad/s, optional `requirePower` |
| `FlickerLight` | Flicker: random `0.15–1.0 ×` base every 0.03–0.3 s. Pulse: `0.3 + 0.7·sin` |
| `EnableWithPower` | Keeps child renderers + lights disabled until power, then enables after `delay` — this is how light cascades work |
| `ScrollTexture` | `_BaseMap` offset at `(0.006, 0.014)` per second — the sea |
| `WindPaper` | 7 tumbling scraps, 1.2–2.6 m/s, 220–480 °/s, hopping 0.12–0.47 m above ground |
| `SparkArc` | 2–4 flashes per burst, 50 ms on / 50–200 ms off, every 3–8 s powered vs 6–15 s unpowered |
| `ElectricBuzz` | 120 Hz **square** + 240 Hz sine + 4.5% noise, 1 s loop, audible only within 9 m |
| `FireCrackle` | Plays the shared `AmbientAudio.crackleClip` at a random pitch and **random start offset** so barrels never sync |

### 9.1 `AmbientAudio` — the whole soundscape, synthesized

All clips 22050 Hz mono, built in `Awake` (before any `FireCrackle.Start` consumes them).

| Clip | Length | Recipe |
|---|---|---|
| Wind | 6 s | Brown noise (leaky integrator, step 0.04, leak 0.997) × swell `0.55 + 0.45·sin(4πt)` — **exactly 2 cycles per loop** |
| Crackle | 2 s | 2.5% hiss floor + **35 additive pops**, each 40–400 samples with `e^(−k/45)` decay |
| Rumble | 4 s | Brown noise + **35 Hz** throb (140 whole cycles = seamless) × 1-cycle LFO; 3D at (130, 10, 20), 40–170 m |
| Drone | 8 s | `sin(55)·0.35 + sin(55.7)·0.30 + sin(65.4)·0.18` — a **0.7 Hz beat** from the detune; played 2D at volume **0.13** |
| Round sting | 1.4 s | Phase-accumulated sweep **68 → 42 Hz** over 1.2 s × `e^(−2.2t)`, plus a noise slap `e^(−26t)` |

**Wind gusts** every 20–40 s: a 3.5 s half-sine swell to **1.9×** volume, during which every particle
system named `Ash*` has its `simulationSpeed` set to 1.9.

> **TWO SYNTH LAWS.**
> 1. Every looping clip is either built from a **whole number of cycles per loop** or **tail-into-head
>    crossfaded**. Otherwise it clicks.
> 2. Any oscillator whose frequency changes over time must use **phase accumulation**
>    (`phase += 2π·f/rate`), never `sin(2π·f·t)`. The latter produces garbage when `f` varies.

`CarnivalAmbience` (Map3) adds double-filtered night wind, a two-leg pitch-sliding sign squeak with an
**inharmonic 2.9× partial** (metal, not flute), and four-slap canvas flapping.

`PoweredMelody` plays a plucked, detuned tune muted until power, with a two-LFO tape warp
(`0.7 rad/s` at full depth + `2.3 rad/s` at 40%).

---

## 10. QUESTS AND EXTRACTION

### 10.1 `ObjectiveManager`

Main chain, one step at a time, top-left HUD:

| Step | Completes when |
|---|---|
| 0 | `BuyableDoor.totalOpened > 0` — **any** door |
| 1 | Player within `crestReachDistance` (**14 m**) of `crestPoint` |
| 2 | `PowerSwitch.instance.isPowerOn` |
| 3 | `ScoreManager.roundNumber >= 10` |
| 4 | Endgame — never completes |

Recommended checklist (latches once each): use the Mystery Box · buy a perk (`PerkCount > 0`) ·
Pack-a-Punch any gun · unbox a collectible. Rendered with `<color=#7CFC7C>[X]</color>` / `[  ]`.

Intro: wait 1.2 s → title card → wait 3.0 s → first objective.

> Bug: both intro strings are **hardcoded to Ashfall** even though every step text is a per-map field.
> Map3 announces "ASHFALL RIDGE" during its intro.

### 10.2 `ExtractionSystem`

Windows open on rounds **10, 20, 30…** only (`round % 10 == 0`). Green beacon appears; hold within
**5 m** for **30 s**. Leaving drains progress at **2× real time**, and progress fully resets when the
window closes.

**The cinematic:**

```
disable FPSController · isInvincible = true · hide all guns
disable InteractionSystem and EnemySpawner        // world stays alive, zombies keep coming
Camera.main detaches from the head
walk 6.5 s at 2 m/s, character turning to face the walk direction
camera trails 4.5 m behind, 2.3 m up, aiming 1.2 m up and 2 m ahead
fade to black from t=3.5 s to t=6.0 s
stats card, 6 real seconds → MainMenu
```

Stats: rounds survived · serials owned · Ash Shards banked · career `Extractions`.

### 10.3 The mint — `CollectionManager` (as built)

```csharp
class OwnedItem {
    string designName;   // "Starfade Camo"
    int    serial;       // 37 — permanent, sequential, per design
    int    editionCap;   // 250 — copied at mint time
    string mintDate;     // "2026-07-04 21:15"
    string source;       // "Loot Crate" / "Sacred Case"
}
```

`TryMint(source, premiumOdds)`:
1. Build the pool of designs where `mintedCount < editionCap`. Empty → **return null (sold out)**.
2. Weighted roll across the pool.
3. `serial = ++counter` for that design. **The serial IS the mint counter.**
4. Unlock a linked camo if the design specifies one (`<Weapon>_Camo_<index> = 1`).
5. Save.

| Rarity | Normal weight | Sacred Case weight |
|---|---|---|
| Legendary | 3 | 25 |
| Epic | 12 | 45 |
| Rare | 40 | 60 |
| Common | 100 | 30 |

Saved pretty-printed to **`collection.json`** in `Application.persistentDataPath`.

**Ash Shards** (`PlayerPrefs["AshShards"]`) — earned at round end from round 10:

```csharp
chance = min(0.3 + (finishedRound - 10) * 0.03, 0.85);   // 30% at R10, +3%/round, caps at R28
shards = Random.Range(1, 3) + finishedRound / 20;        // 1–2, +1 per 20 rounds
```

---

## 11. UI AND PERSISTENCE

| PlayerPrefs key | Default | Written by |
|---|---|---|
| `SelectedCharacter` | 0 | CharacterSelectMenu |
| `BestRound` | 0 | EnemySpawner |
| `AshShards` | 0 | CollectionManager |
| `Extractions` | 0 | ExtractionSystem |
| `<Weapon>_Headshots` / `_RapidKills` | 0 | CamoSystem |
| `<Weapon>_Camo_<0..13>` | 0 | CamoSystem, CollectionManager |
| `<Weapon>_EquippedCamo` | **−1** | LoadoutMenu |

Menus: `MainMenu` (scene loads, guarded by `Application.CanStreamedLevelBeLoaded`), `PauseMenu`,
`SettingsMenu` (**not persisted**), `SettingsTabs`, `LoadoutMenu` (camo grid, locked/unlocked/equipped
colouring), `CharacterSelectMenu` (clamps the saved index), `CrosshairManager` (not persisted),
`MenuButtonSound` (+10% hover scale from a cached original).

---

## 12. THE MAPS

> ### ★★ READ THIS BEFORE TOUCHING LEVEL ART ★★
>
> **The owner does not like how the current maps look, and the map-generation approach is being retired.
> Do not continue it. Do not extend the builders. Do not write a third one.**
>
> **What exists today and why it looks wrong.** Both maps are assembled *in code* out of Unity primitive
> cubes and cylinders — every wall, prop, tree, vehicle and building is a scaled `PrimitiveType.Cube` —
> and surfaced with grayscale PNG textures that the builder *paints pixel by pixel at build time*, then
> tints. That is why the world reads as boxy and flat: it is boxes, wearing procedurally-noised
> greyscale, lit well. The atmosphere work (fog, grading, lighting, audio) is genuinely good and is
> carrying an art style that was never actually authored.
>
> **What to do instead.** Build the maps as **real, authored level art**:
> - Model or source proper meshes — modular kits, real props, actual buildings. Synty POLYGON-style
>   packs, Kenney assets, or purpose-modelled geometry. Not primitives.
> - Use **real materials and shaders** — PBR with authored albedo/normal/roughness/AO maps, Shader Graph
>   for anything stylized (wetness, wear, vertex-blended terrain, emissive signage, animated water).
>   The project already has Shader Graph 17.3 and a working custom `PlasmaShader` used by four camos, so
>   the pipeline is proven.
> - Light and dress the scene **in the editor**, by hand and by eye. Level design is a visual craft; doing
>   it through `Box(name, position, size, material)` calls in C# fights that at every step.
> - Keep the atmosphere layer — the fog/grading/audio/power-cascade systems are engine-agnostic and good.
>
> **Several of the sixteen laws (§14) exist only because of primitive kitbashing** and stop mattering once
> you author real art — they are marked **▢** there. The ones marked **⚙** are engine-level truths that
> apply no matter how the geometry is made, especially the navmesh, collider and particle rules. Read §14
> and obey the ⚙ ones.
>
> **What is still worth taking from the existing maps:** the *design*, not the *construction*. The zone
> progression, the door-pricing curve, the spawn-placement rules, the power-cascade concept and the
> extraction staging are all proven by playtesting. Sections 12.1–12.2 record that design so a properly
> authored map can reproduce the *layout and pacing* without inheriting the boxes.

Both existing maps are generated by editor scripts, copied from `SampleScene` so player/HUD/manager wiring
survives. The generated scenes and material folders are build outputs — the next build wipes any hand edit.

### 12.1 Ashfall Ridge (Map2) — `Tools > Ashfall Ridge > Build Map`

Dusk, warm, ash-blown. A **3.5° tilted hill** you fight *up*:

```
GroundY(z) = (min(z, 48) + 88) * tan(3.5°)      // flat crest cap above z = 48, at y = 8.318
```

Footprint ≈ x −98…64, z −88…98, with backdrop rings out to ±150. Sun `(1, 0.58, 0.32)` at intensity 1.35;
fog `(0.5, 0.4, 0.31)` ExpSq 0.013; shadow distance 130.

**Doors, in progression order:**

| Cost | Door | Opens |
|---|---|---|
| **750** | `Door_RoadChoke` at `At(1, −24)` | North out of the convoy spawn |
| **1000** | `Door_CityGate` at `At(42, −31)` | Zone B, the city street |
| **1250** | `Door_PlazaArch` at `At(48, 44)` | City → crest plaza |
| **1500** | `Door_ParkArch` at `At(42, 12)` | Park ↔ city shortcut |
| **1750** | `Door_CrestGate` at `At(1.5, 40)` | The road's own way onto the crest |

**Machines:**

| Machine | Cost | Where |
|---|---|---|
| Quick Revive | 1500 | Zone A, west of spawn |
| Ammo Crate ×3 | 1000 | convoy · park · crest plaza |
| Mystery Box ×3 | 950 | gas shop · arcade · radio shack |
| Gobblegum | 500 | Diner interior |
| Speed Cola | 3000 | Pharmacy interior |
| Double Tap | 2000 | Hardware interior |
| Stamin-Up | 2000 | outside the barn |
| Juggernog | 2500 | substation east fence |
| **Power Switch** | — | inside the substation, `(0, 8.318, 79.5)` |
| **Pack-a-Punch** | 5000 | the crashed helicopter, `(−31, 8.318, 83)` |
| Loot Crate | 1500 pts | crest shrine |
| **Sacred Case** | 3 Shards | its own pedestal beside the shrine |

**17 spawn points**, every one behind cover: 3 in the convoy zone (overpass, treeline, gas shop), 2 in the
park (mausoleum, cemetery hedge), 4 in the city (three alley alcoves + the toppled bus), 4 in the field
(two west hedges, south hedge, barn), 4 on the crest (three ridge rocks + the radio shack).

Extraction pad on the road at `At(1, −46)`; the ending walks **south** into the ash.

Five particle systems: 15 barrel flames, a smoke column, large ash flakes, 2 steam wisps, ambient ashfall.

### 12.2 Last Ride / Blackout Carnival (Map3) — `Tools > Last Ride > Build Map`

True night. Flat ground at y = 0, footprint x ±77, z −52…72. Moon at intensity **0.16**; fog
`(0.045, 0.05, 0.09)`; a coaster-ring silhouette forms the horizon.

A north–south spine: entrance plaza (spawn) → midway boulevard → wheel plaza, with the carousel plaza west
and the funhouse east.

| Cost | Door | Opens |
|---|---|---|
| **750** | Midway Gate | The boulevard |
| **1000** | Carousel | West plaza |
| **1250** | Funhouse | East attraction |
| **1500** | Wheel Plaza | The Midnight Wheel, PaP and extraction |

Perks: Quick Revive 1500 · Double Tap 2000 · Stamin-Up 2000 · Juggernog 2500 · Speed Cola 3000.
Two mystery boxes, two ammo crates, gobblegum 500, loot crate 1500, Sacred Case 3 shards, PaP 5000 at the
gondola station. **9 spawn points**; two of them are *through the walk-through stalls* (RING TOSS and
DARTS), so zombies come at you through the fairground booths.

**The power cascade is this map's signature.** One switch in the generator shed at `(16, 0, 36)` triggers:

- Every bulb string lights with `delay = distance(string, generator) * 0.045` — a visible wave of light
  sweeping outward across the park
- The Ferris wheel's 24 rim bulbs are hard-delayed to **3.2 s** so the wheel lights **last**
- Three rides start turning (`Rotator.requirePower`): carousel 11°/s, duck carousel 30°/s, the
  Midnight Wheel 3.2°/s about Z
- The carousel's music box unmutes — a 12-note A-minor tune with tape warp

Extraction is at the gondola station; the ending walks **north**, deeper into the park.

### 12.3 The retired construction method — recorded so it is not repeated

For completeness, this is what the builders do. **It is being replaced; do not reimplement it.**

- Every object is a scaled primitive cube or cylinder placed by a `Box()` / `Cyl()` helper.
- Textures are **painted from code at build time** — grayscale PNGs (brick, planks, concrete, asphalt,
  rust, metal, rock, dirt, grass, hedge, corn, sand, waves, stripes) written into
  `Assets/Textures/<Map>/`, with normal maps derived by central differences, then tinted per material.
- `ResolveTiled()` computes world-correct UV tiling (≈1 tile per 2 m) and caches a material instance per
  tiling ratio — producing **164 auto-generated `tiled_*.mat` files for Ashfall alone**, on top of 57 base
  materials.
- Everything regenerates on every build; the committed scenes are 9.2 MB and 4.6 MB of serialized YAML
  holding 2,393 and 1,162 GameObjects respectively.

The scale of that output is itself the argument against the approach: ~2,400 primitive GameObjects and 221
materials to express one map, none of it directly editable by eye.

**Carry forward instead:** the atmosphere systems (fog/grading/light cascade/synthesized audio), the zone
and pacing design in §12.1–12.2, and the navmesh/collider laws. Author the geometry properly.

---

## 13. KNOWN DEFECTS IN THE CURRENT BUILD

Carry these into the rebuild as *fixed*, not reproduced:

1. **No concurrent-zombie cap** — `round * 5` spawns unconditionally on a 2 s timer.
2. **`Perkaholic` is a 30-second no-op** that only writes to the console.
3. **`InPlainSight` is a speed buff**, not stealth; zombies still track you.
4. **The Mystery Box is a name-string placeholder** — no spin, no random pool, no relocation, always
   grants slot 1.
5. **`WeaponSwitcher` is hardcoded to 2 slots** and resets its unlock array every scene load.
6. **Insta-Kill / Double Points don't stack** — a second pickup's expiry clears the flag early.
7. **`ZombieStabilizer.spawnDuration` (3.5 s) ≠ `EnemyAI.standUpTime` (3.0 s).**
8. **Dead inspector fields:** `EnemyAI.moveSpeed` and `timeBetweenAttacks`, `GunSystem.recoilKickBack`,
   `CamoSystem.gunRenderer`, `FloatingText.offset`.
9. **`ScoreManager.instance` is dereferenced unguarded** by six machine scripts.
10. **`CamoSystem.ApplyCamo` runs only in `Awake`** — equipping requires a scene reload.
11. **Death loses everything** — scene reload, no summary, no reward banking.
12. **`ObjectiveManager` intro strings are hardcoded to Ashfall** on every map.
13. **Perks don't survive death** and aren't persisted anywhere.
14. **No object pooling** except blood decals — zombies, popups, tracers and casings all `Instantiate`.

Found during the project-settings audit — these are scene/config defects, not code defects:

15. **You cannot pause on Map2 or Map3.** `PruneOldLevel()` destroys the `System` object carrying
    `PauseMenu`, `SettingsMenu` and `CrosshairManager`. The UI panels survive with no controller. `Esc` is
    dead on both real maps. **This is the most user-visible bug in the project.**
16. **Two missing scripts are serialized into every gameplay scene** — `Recoil` (on Main Camera, disabled)
    and `CameraRecoil` (on WeaponHolder, *enabled*). Neither source file exists anywhere. They were deleted
    when recoil was consolidated into `FPSController.AddRecoil`, but the components were never stripped.
17. **Map3 and CharacterSelect are unreachable from the main menu** — `MainMenu.cs` defines `PlayMap3()`
    and `OpenCharacterSelect()`, but no button is wired to either.
18. **`SampleScene` has no navmesh, no `NavMeshSurface`, and an empty `spawnPoints` array** — zombie
    spawning and pathing are almost certainly non-functional there. It is a template, not a playable map,
    despite being what the Play button and character select both load.
19. **Three AudioListeners** in every gameplay scene.
20. **`Assets/_Recovery/0.unity`** is an orphaned crash-recovery snapshot — it is Map3 plus ~10
    URP-auto-added light components, from the editor session of 2026-07-29. No lost work; archive it.

### 13.1 Severe bugs — verified against source and scene, fix these first

21. **🔴 Staggered zombies shrink permanently and their head hitbox moves.**
    `EnemyAI.cs:361-363` — `StaggerRoutine` writes an **absolute** `Vector3.one * 0.94f` and restores to
    `Vector3.one`. But that model transform is scaled **1.5** in `Enemy.prefab` (`:381-392`). `Target.cs`
    staggers **25% of non-killing hits**, so most of the horde drops to two-thirds size within your first
    magazine **and never recovers**. The ragdoll bones are children of it, so the `Head` collider shrinks
    and drops with them — **your headshots stop landing where you aim.**
    *Fix:* cache the original localScale on Start and multiply against it, never write `Vector3.one`.

22. **🔴 `GameFeel` breaks the extraction ending.** `ExtractionSystem.cs:125` un-parents the camera — after
    which `localPosition` **is** world position — but `GameFeel.cs:157` keeps writing
    `cam.transform.localPosition` every `LateUpdate`, yanking it back to the old head offset. **The game's
    only ending films from the map origin for all 6.5 seconds.** This is the §20.6 law being violated by
    the shipped code.
    *Fix:* `GameFeel` must disable itself while a cinematic owns the camera.

23. **🔴 A round can hard-lock forever.** `EnemySpawner.cs:211` — after 12 failed attempts,
    `PickSpawnPosition` returns a spawn point **with no reachability check at all**. `zombiesAlive` is
    seeded with the whole round count, so a single zombie stranded on an unreachable navmesh island means
    the round never ends and **the run is dead**. §5.2 describes the late attempts as a safety net; they
    are the opposite.
    *Fix:* validate reachability on the fallback too, and add a liveness timeout that culls unreachable
    zombies.

24. **🔴 The collection file can be wiped.** `CollectionManager.cs:237-244` — `JsonUtility.FromJson` has
    **no try/catch** and runs in `Awake` *after* `instance = this`; `SaveCollection` (`:234`) is a plain
    non-atomic `File.WriteAllText`. Kill the process mid-write and the next launch has **no items** and
    will re-issue `#1` of a design whose `#1` was already minted — permanently breaking the one guarantee
    the whole economy rests on.
    *Fix:* write to a temp file and atomically replace; wrap the load in try/catch and keep a `.bak`.
    §18.5 covers atomic serial allocation on the server — **this is the local file, and it is the bigger
    risk today.**

25. **🟡 Bullets pass through unopened doors.** `GunSystem.whatIsEnemy` is `m_Bits: 65` = `Default | Enemy`.
    **DoorDebris is layer 9 and is not in the mask**, so shots pass clean through a 750-point barricade.
    And because `Default` *is* in the mask, the world-impact `else` branch (`GunSystem.cs:296-311` —
    `BloodFX.WallImpact` dust and tracers stopping at walls) is **unreachable in every shipped scene**.

26. **🟡 The free Max Ammo is better than the paid one.** `AmmoCrate.cs:17` uses the default
    `FindObjectsInactive.Exclude`, so **1000 points refills only the gun in your hands**, while
    `PowerUpManager.cs:134` and `GobblegumManager.cs:60` both deliberately `Include` inactive guns and
    refill everything for free.

27. **🟡 Every unbox message is invisible.** Neither map builder assigns `LootCrate.unboxText`, so the
    reveal — *"Starfade Camo — #37 of 250"* — plus "SOLD OUT" and "Need 3 Shards" all go to `Debug.Log`
    and nowhere the player can see. **The single most important moment in the economy currently has no
    on-screen output on either playable map.**

---

## 14. THE SIXTEEN LAWS

Every one of these is a bug that already happened. Violating them re-creates it.

**Marked ⚙ = engine-level truth, applies no matter how you build the art. Marked ▢ = an artifact of the
retired primitive-kitbashing method (§12); once you author real level art these stop mattering.**

| # | Law |
|---|---|
| 1 ▢ | **Never regex-rewrite or `Set-Content` the map builder files** — it double-encodes the UTF-8 comments. Use targeted edits only. |
| 2 ⚙ | **TMP text renders from its −Z side.** Every world-space label needs a 180° flip, and double-sided signs need a mirrored backing child. |
| 3 ⚙ | **All three `velocityOverLifetime` axes must use the same curve mode** or Unity throws. Always set Z even when it's zero. |
| 4 ▢ | **Nothing flush — everything proud or clear.** Coplanar faces shimmer. Doors shrink inside frames; frame posts stand 3 cm clear; overlapping patches use a *different* thickness; vehicle cabs are never exactly body width; stacked slabs get a millimetre stagger. |
| 5 ▢ | **No single-primitive props.** Minimum 3 pieces, hash-jittered pose, collider on the main mass only. |
| 6 ⚙ | **Stacked clutter keeps one collider.** Colliders on stacked props become **navmesh stairs** — zombies climb junk piles and appear to hover. |
| 7 ⚙ | **Colliders must be thicker than the navmesh voxel.** A 0.15 m fence collider is thinner than the 0.12 m voxel, the bake misses it, and **zombies path straight through the fence.** Keep collision ≥ 0.5 m thick even when the visual is thin. |
| 8 ⚙ | **Spawn points need ≥ 2 m of clearance** between cover and boundary, or the navmesh can't reach them. Put cover ~3 m in front of the spawn. |
| 9 ⚙ | **OR every new layer into every camera's `cullingMask`.** A non-`Everything` mask culls new layers and the geometry renders invisible. |
| 10 ▢ | **No unframed holes.** Every opening gets posts and a header, or rubble/gateposts. Zombies come from behind props, never through wall gaps. |
| 11 ⚙ | **Every particle material gets a texture and transparent setup** — untextured particles render as literal white squares. |
| 12 ⚙ | **Door debris is excluded from the navmesh bake** and blocks via a carving `NavMeshObstacle`, so opening a door re-carves instead of needing a rebake. |
| 13 ⚙ | **Use 0.12 m navmesh voxels** — finer voxels give clean paths through doorways and around props. |
| 14 ▢ | **At most one sanctioned invisible wall**, marked visually so the player can read it. Teleport-based boundaries feel like lag. |
| 15 ⚙ | **When tuning has no effect, verify the component exists.** Months of NavMeshAgent tuning did nothing because the prefab had no agent. |
| 16 ▢ | **Removed by playtest verdict — do not re-add:** grass tufts, the beach smoke column, free serial mints for surviving rounds. |

---

# PART II — THE FULL VISION

Everything below is **designed and not built**. Build it.

## 15. THE ECONOMY

### 15.1 Currencies

★ **Names are final. Use these exact words in code, UI and content — no synonyms, no placeholders.**

| Currency | Persists | Earned by | Spends on |
|---|---|---|---|
| **Essence** | ✗ resets each match | Killing zombies | Doors, perks, box, PaP, ammo |
| **Embers** ◆ | ✓ | Playing | Standard Sacreds |
| **Soul Crystals** ✦ | ✓ | Real money, **and surviving past round 10** | Premium Sacreds |
| **Shards** | ✓ | Scrapping duplicates | Trade-ups |

```
embers = (highestRound × 50) + (totalKills × 5) + (firstWinOfDay ? 200 : 0)
```

> **Renames from the current build.** `Credits` → **Embers**. `Cash` → **Soul Crystals**.
> **`Ash Shards` is retired entirely** — the name, the PlayerPrefs key `AshShards`, the HUD counter and the
> announcement string all become Soul Crystals. The existing round-survival drop (30% at round 10, +3%/round,
> capping at 85%) now awards **Soul Crystals**, which means the premium currency has both a paid and an
> earned path. Keep that — it's what stops the top tier feeling purely paywalled.

> ★ **This is the single most important correction to the current build.** `LootCrate` currently charges
> **1500 Essence**. Sacreds must be bought with **Embers or Soul Crystals**, never with in-match points. A chase
> economy cannot run on a currency that evaporates on death.

### 15.2 The rarity ladder

```
Common → Rare → Epic → Legendary → Mythic → Limited → Secret → Secret RGB
                                             └── serialized ──┘
```

The bottom five are ordinary cosmetics. The top three are **serialized**: permanently numbered, capped,
tradable. In the owner's shorthand: **serialized = Limited**, **SCT = Secret**, **RGB = Secret RGB**.

### 15.3 ★ The promotion tree — the core odds

Rolled on every case open, in order:

```
5%  of opens yield a SERIALIZED item
     └─ 5% of those promote to SCT
             └─ 5% of those promote to RGB
```

| Outcome | Rate of all opens | Odds |
|---|---|---|
| Non-serialized cosmetic | 95% | — |
| **Serialized** (plain) | **4.75%** | ~1 in 21 |
| **SCT** | **0.2375%** | ~1 in 421 |
| **RGB** | **0.0125%** | **~1 in 8,000** |

★ **All serialized designs share the same pull chance** — flat, not rarity-weighted. Selection is uniform
across whatever designs still have edition capacity.

RGB **cannot** appear on a non-SCT item. The chain is strictly sequential.

### 15.4 ★ Pity and bulk

| Rule | Value |
|---|---|
| **RGB pity** | `RGB_PITY_THRESHOLD = 101` — the 101st open after the last RGB is a guaranteed RGB |
| **Bulk bonus** | Buy 10 cases → **11th free** |
| F2P pity (from docs) | 50 opens → guaranteed Epic |
| Premium pity (from docs) | 10 premium opens → a **Rainbow Sacred** |

> **Recorded consequence, deliberately accepted by the owner.** A guaranteed RGB every 101 opens produces
> RGB at roughly **1%** of all opens, versus **0.0125%** from the tree — about **79× the natural rate**,
> meaning nearly all RGB in the game will come from pity rather than luck. RGB ends up ~4.8× rarer than a
> plain serialized pull. This is intentional. Implement it as the named constant above so it is a one-line
> tune, and **do not silently "correct" it.**

### 15.5 ★ Edition caps and serials

Caps stay, exactly as the current code already implements:

1. The pool = designs where `mintedCount < editionCap`.
2. Uniform selection among the survivors.
3. `serial = ++mintCounter` — sequential, permanent, per design.
4. At cap, the design **retires forever**.
5. When every design is retired, the case reports **SOLD OUT and does not charge**.

Serial #1 of any design is a one-time event in the game's lifetime. Surface it loudly.

### 15.6 Float / wear

Every skin instance rolls a float in `0.0 – 1.0` at mint. Lower is cleaner. The float is permanent and
travels with the item through trades.

| Band | Range |
|---|---|
| Factory New (FN) | 0.00 – 0.07 |
| Minimal Wear (MW) | 0.07 – 0.15 |
| Field-Tested (FT) | 0.15 – 0.38 |
| Well-Worn (WW) | 0.38 – 0.45 |
| Battle-Scarred (BS) | 0.45 – 1.00 |

```csharp
float roll = Mathf.Pow(Random.value, floatCurveExponent);   // premium 0.5 (skews clean), F2P 1.0
float f    = Mathf.Lerp(design.floatMin, design.floatMax, roll);
```

Per-design float caps let a skin exist only in certain bands. Float drives a wear shader parameter
(`_FloatValue`) — scratches, edge wear, fading.

### 15.7 Variants

| Variant | Rule |
|---|---|
| **Tracker** | ~5% of items. Counts zombie kills forever, displayed on the gun as `T: 147`. Survives trades. Increments client-side, batch-synced, **server-clamped**. |
| **SCT (Secret)** | White-gold border, distinct presentation, no animation |
| **RGB (Secret RGB)** | Animated rainbow. Reuse `SmartCamoScroller` — it already animates the Cosmic camo's UVs. |

### 15.8 Cases

★ **The cases are called SACREDS.** Not crates, not cases, not boxes. "You opened an Ashfall Sacred."

| Sacred | Price | Currency | Class |
|---|---|---|---|
| **Recruit Sacred** | free | daily login + first win of day | F2P |
| **Ash Sacred** | 500 | Embers ◆ | F2P |
| **Ember Sacred** | 2,000 | Embers ◆ | F2P |
| **Phantom Sacred** | 12 | Soul Crystals ✦ | Premium |
| **Starfade Sacred** | 40 | Soul Crystals ✦ | Premium, 14-day window |
| **Founder's Sacred** | earned | round 30 solo / 50 co-op | Premium |
| **Midnight Sacred** | 25 | Soul Crystals ✦ | Premium, yearly event |
| **Rainbow Sacred** | earned | every 10 premium opens | Pity |

### 15.8.1 ★ The opening — this is the product, treat it that way

The moment a Sacred opens is the single most-repeated piece of UI in the game and the thing players will
screenshot. Budget real time for it.

**The sequence:**

| Beat | Duration | What happens |
|---|---|---|
| 1. Approach | — | The Sacred sits in the centre, slowly rotating, lit from below. Audio drops to a low hum. |
| 2. Commit | 0.4 s | Press to open. The Sacred cracks; light bleeds from the seams; the camera pushes in slightly. |
| 3. The spin | **5–6 s** | Horizontal reel of ~30 cards scrolls past a centre marker, **cubic ease-out** so it decelerates hard at the end. Each card passing the marker gives a tick — pitch rising as it slows. |
| 4. The near-miss | — | The reel is seeded so a high-rarity card passes 1–2 slots *before* the winner. This is not a trick; it is the whole reason the animation works. |
| 5. Settle | 0.6 s | The reel stops. Screen shake proportional to rarity. Rarity-specific audio sting. |
| 6. Reveal | hold | The card grows to fill the frame. |

**The reveal card must show, in this priority order:**

1. **The serial** — `#37 / 250`. Largest element on the card for a serialized pull. This is the thing that
   is unique in the world; nothing else on the card matters as much.
2. The skin name and its weapon
3. Rarity, with the tier's colour and border treatment
4. **Float band + the raw float** — `Factory New (0.0412)`
5. Variant badge if present — Tracker / **SCT** / **RGB**

**Escalation by outcome — the presentation must be unmistakably different at each tier:**

| Outcome | Treatment |
|---|---|
| Non-serialized | Standard card, single sting, skippable immediately |
| **Serialized** | Reel slows *further* than normal before landing. Gold seam light. Longer hold. |
| **SCT** | Screen desaturates to near-black except the card. White-gold border. Distinct, lower sting. |
| **RGB** | Full-screen takeover. Animated rainbow border. The card renders in 3D and turns. Do not allow skipping for ~2 s. |
| **Serial #1** | **Anything numbered #1 gets a unique, one-off presentation.** "FIRST OF ITS KIND." This happens once per design in the game's entire lifetime. |

**Implementation notes:**
- Pool the reel cards (§18.6) — 30 instantiations per open is a stutter.
- The **result is decided by the server before the animation starts** (§18.4). The reel is theatre played
  over a known outcome. Never let the animation determine the item.
- Bulk opens (the 10+1) must support a **skip-to-summary** that still plays the full reveal for anything
  serialized or above.
- Publish the drop table in the UI (§15.8 note below).

> **Publish the drop table in the UI.** Several jurisdictions require disclosed odds for paid boxes, and it
> costs nothing. Belgium effectively bans paid loot boxes outright — worth knowing before you ship there.

### 15.9 ★ Trading — Steam-style, no cash-out

- Players trade **items for items**, directly, peer to peer.
- **Nothing in the game converts items back into money.** There is no in-game marketplace selling for
  cash, no payout, no withdrawal.
- Real money flows **in only**, buying Soul Crystals which buy Sacreds.
- Full trade history is retained per item.

> ★ **Build for the possibility, not the feature.** If a third-party market ever appears, the only thing
> that makes it viable is **stable globally-unique item IDs plus a server-authoritative owner record**.
> Both are nearly free to include now and effectively impossible to retrofit onto items already issued.
> Include them. Ship no market.

Item provenance, permanent:

```csharp
class SkinInstance {
    string   itemId;              // globally unique, permanent — the anchor for everything
    string   designId;
    int      serial;              // 0 for non-serialized
    int      editionCap;
    float    wear;                // 0..1, permanent
    Variant  variant;             // None | Tracker | SCT | RGB
    int      trackerKills;
    string   originalOwnerUserId; // NEVER changes — the "OG" badge
    string[] previousOwnerIds;
    string   currentOwnerId;
    DateTime mintDate;
    string   acquisitionSource;
}
```

### 15.10 Progression and retention

Daily login → **Recruit Sacred** · 3 daily challenges · first-win-of-day **+200 Embers** · end-of-match
reward screen · scrap duplicates into Shards → trade-ups.

---

## 15.11 ★ MODEL RECOLORING — THE THEME SYSTEM

**The goal:** the purchased character and zombie models ship in fixed colors (the Inquisitors are white,
the zombies green/blue). Recolor them at runtime to produce black, blue, pink, red — any hue — **without
duplicating a single texture or mesh.**

> **Before writing any shader: check what you already own.** The IdaFaber pack contains **25 prefabs and
> only 2 are used**, and the ones in use are named "White **Variant**" — other colorways very likely ship
> in the pack already. ZombieM_02 has 18 materials across 3 meshes. Ten minutes in the Project window may
> hand you finished variants for free. The shader below then extends that to unlimited colors.

### 15.11.1 How it works

A single Shader Graph, `Recolor.shadergraph`, used by every themed model:

```
albedo ──▶ RGB→HSV ──▶ [hue    = frac(H + _HueShift)      ]
                       [sat    = S * _SaturationMul       ] ──▶ HSV→RGB ──▶ base color
                       [value  = V * _ValueMul            ]
```

- **Hue shift preserves all texture detail** — wear, stitching, shading, dirt. It moves the color and
  leaves the craftsmanship. A flat color multiply would flatten the model into a paint swatch; do not do that.
- **Optional 3-channel region mask** (`_RegionMask`): R = cloth, G = armor/metal, B = skin/face. Each region
  gets its own hue/sat/value set. **This is what separates "blue assassin" from "everything including her
  face is blue."** Author one mask per character; it's a single texture and it makes the feature look
  professional instead of cheap.
- **Applied with `MaterialPropertyBlock`**, never by instancing materials. Costs nothing, preserves SRP
  batching, and doesn't leak material instances the way `CamoSystem.ApplyCamo` currently does.

### 15.11.2 Data

```csharp
[CreateAssetMenu(menuName = "Aftermarket/Theme")]
class ThemeDefinition : ScriptableObject {
    string   themeId;          // "inquisitor_rose"
    string   displayName;      // "Rose"
    Sprite   swatch;           // for the store grid
    RegionTint[] regions;      // hueShift, saturationMul, valueMul per mask channel
    bool     adminOnly;        // see §15.12.2
}
```

Themes are ScriptableObjects (§18.1) — adding a colorway is authoring an `.asset`, never a code change.

### 15.11.3 ★ Zombie themes must not break readability

Zombies already use color as a **gameplay signal**: sprinters are tinted to **0.62 brightness** so you can
identify them at a glance, and crawlers read distinctly. A flat recolor would destroy that, and at round
10+ — when everything sprints — losing that read is genuinely dangerous, especially for co-op callouts.

**The rule: a theme sets the base hue; the existing type signals apply on top of it.**

```csharp
// EnemyAI.ApplyRoundStats — theme first, THEN the type multiplier
Color baseColor  = currentZombieTheme.Resolve(sharedMaterial._BaseColor);
if (isSprinter) baseColor *= 0.62f;      // the existing signal, unchanged
block.SetColor("_BaseColor", baseColor);
```

| Theme | Walker | Sprinter (×0.62) | Crawler |
|---|---|---|---|
| Classic | grey-green | dark olive | grey-green, pitched-down voice |
| Red | bright red | dark crimson | red |
| Pink | hot pink | deep magenta | pink |

The player never loses the read, whatever theme is active.

---

## 15.12 ★ THE STORE

A proper store page — this is a storefront, not a settings menu, and it should look like one.

### 15.12.1 What it sells

| Product | Price | Notes |
|---|---|---|
| **Character models** (white/default variant) | Embers ◆ | The three existing models plus any future ones |
| **Character themes** (recolors) | **LOCKED — admin only** | Built and shippable, held back for now |
| **Zombie themes** | **LOCKED — admin only** | Same |
| **Sacreds** | Embers ◆ / Soul Crystals ✦ | The case ladder from §15.8 |
| **Soul Crystal bundles** | real money | The only real-money product |

> Default pricing intent: base character models cost **Embers** so they're reachable by playing. If the
> colorways are ever released publicly, **Soul Crystals** is the natural tier for them. Both are single
> values in a ScriptableObject — trivially changed once you see how the economy actually plays.

### 15.12.2 ★ Admin-only content — do this correctly the first time

Locked themes must be visible-but-unavailable to normal players and fully usable by the owner.

**The entitlement check is server-side. Never a client bool.**

```csharp
// WRONG — one memory edit or one decompile and everyone has everything
if (Application.isEditor || playerName == "Tutut") unlockAll = true;

// RIGHT — an entitlement the backend grants, checked through IBackend (§18.3)
bool owned = await backend.HasEntitlement(userId, theme.themeId);
```

While running on `LocalBackend` this is a local list — but **write it through the interface now**, so the
day it moves to a real server it is already correct and not a rewrite. Same principle as §18.4: the client
may *render* a locked item, it may never *decide* it is unlocked.

**Presentation of locked items:** show them. A store with visible locked content is more compelling than
one that hides it — players learn what exists and want it. Render the swatch greyed with a lock badge and
"Coming soon", not a blank slot.

### 15.12.3 Store page layout

```
┌──────────────────────────────────────────────────────────┐
│  STORE                          ◆ 12,480    ✦ 27         │
├────────────┬─────────────────────────────────────────────┤
│ SACREDS    │   ┌────────┐  ┌────────┐  ┌────────┐        │
│ CHARACTERS │   │ ▓▓▓▓▓▓ │  │ ▓▓▓▓▓▓ │  │ ▓▓▓▓▓▓ │        │
│ THEMES     │   │ Ash    │  │ Ember  │  │Phantom │        │
│ CRYSTALS   │   │ 500 ◆  │  │2,000 ◆ │  │  12 ✦  │        │
│            │   └────────┘  └────────┘  └────────┘        │
│            │                                             │
│            │   ┌─────────────────────────────────────┐   │
│            │   │   [ live 3D preview, rotating ]     │   │
│            │   │   Inquisitor — Rose        🔒       │   │
│            │   │   ○ ● ○ ○   theme swatches          │   │
│            │   └─────────────────────────────────────┘   │
└────────────┴─────────────────────────────────────────────┘
```

**Requirements:**
- **Live 3D preview.** A render-texture camera on the actual model with the theme applied in real time —
  clicking a swatch recolors it instantly. This is the whole sell; a static 2D thumbnail wastes the feature.
- Currency balances always visible, top right, with the ◆ / ✦ glyphs.
- Owned items read "OWNED" and offer **Equip**, not a price.
- Locked items show the lock badge and stay browsable.
- Purchase confirmation for anything costing Soul Crystals. Never one-click a premium spend.

### 15.12.4 Where the theme is applied

The current `CharacterSwapper` reads `PlayerPrefs["SelectedCharacter"]` and enables one of three body
objects. Extend, don't replace:

```csharp
int    character = PlayerPrefs.GetInt("SelectedCharacter", 0);
string themeId   = PlayerPrefs.GetString("SelectedTheme_" + character, "default");
ThemeSystem.Apply(activeBody, themeId);      // MaterialPropertyBlock, one call
```

Zombie themes apply once in `EnemyAI.Start()`, before the sprinter tint (§15.11.3).

> **Fix `CamoSystem`'s mistake while you're here.** It applies materials **only in `Awake`**, so changing a
> camo needs a scene reload (§13.10). `ThemeSystem.Apply` must work at any moment on a live object — the
> store preview depends on it, and so does equipping without restarting.

---

## 16. ZOMBIES FEATURES STILL MISSING

Ordered by how much each is missed:

| Feature | Notes |
|---|---|
| **Wallbuys** 🔥 | Chalk weapon outlines on walls. The core economy sink of BO2. Entirely absent. |
| **Real Mystery Box** 🔥 | Spin animation, random pool from all weapons, 10 s grab window, fly-away after N uses, teddy bear, relocation. |
| **Window barriers** 🔥 | Boarded windows zombies tear down and you rebuild for **+10 per board**. This is the round-one gameplay loop and it doesn't exist. |
| **Hellhound rounds** | Every ~5 rounds, fast dogs spawning in fire; last kill guarantees Max Ammo. |
| **Concurrent zombie cap** | ~24 alive at once. Required before any of the above matters at high rounds. |
| **Remaining perks** | Mule Kick, PHD Flopper, Tombstone, Who's Who, Vulture Aid, Electric Cherry, Deadshot Daiquiri. |
| **Perk presentation** | Drink animation, jingles, HUD perk icon row. |
| **Weapon inventory rework** | List-driven, per-weapon ammo state, PaP level, equipped skin. Blocks wallbuys. |
| **Equipment slot** | Grenades, Semtex, Bowie knife, Galvaknuckles, monkey bombs. |
| **Co-op, 4 players** | Netcode for GameObjects + Lobby/Relay. Downed state, bleed-out, revive, last-stand pistol. |
| **Death handling** | Bank the run's Embers, show a summary. Currently a bare scene reload. |

---

## 17. BUILD ORDER

Twelve phases. Do not reorder 1–3; everything else depends on them.

| # | Phase | Delivers |
|---|---|---|
| 1 | **Foundation** | Clean project, folder structure, fix the §13 defects |
| 2 | **Core infrastructure** | ScriptableObject content, `GameEvents` hub, **`IBackend`**, pooling, Input System |
| 3 | **Map + navigation** | One small contained map: a modular structure kit on a fixed grid surfaced with the **432 owned PBR materials**, props from **Shadow's Asset Forge**, a free HDRI sky, plus navmesh and spawn validation. **Not** code-placed primitives (§12). Nothing to buy — see the Phase 3 art box below. |
| 4 | **Doors, debris, power** | Points-gated progression |
| 5 | **Barriers / windows** 🔥 | The round-one loop |
| 6 | **Wallbuys + real Mystery Box** 🔥 | The real weapon economy |
| 7 | **Perks, PaP, power-ups, dogs** | BO2 parity |
| 8 | **Skin & Sacred economy** | Part II §15, on `LocalBackend` — mint, Sacreds, the opening (§15.8.1), **the Theme/recolor system (§15.11) and the Store (§15.12)** |
| 9 | **4-player co-op** | Netcode, downs, revives |
| 10 | **Real backend** | Swap `LocalBackend` → `PlayFabBackend`. **One line if §18.3 was respected.** |
| 11 | **Trading + IAP** | Item-for-item trades, Soul Crystal purchases |
| 12 | **Content cadence** | New maps, Sacreds, seasons |

> ### Phase 3 art — NOTHING NEEDS TO BE BOUGHT ★
>
> An earlier version of this spec said Phase 3 was blocked on buying a level-art kit. **That is no longer
> true and no money is to be spent.** Build the environment from what is already owned:
>
> **1. Structure — walls, floors, doorframes, stairs, ramps.**
> These are boxes and planes. Their quality comes from **materials, not mesh detail** — this is the single
> most important art fact in this document. The current maps look flat because they are untextured
> primitives wearing procedurally-painted greyscale with **no normal maps**, not because they are boxes.
>
> Author them as a small modular kit in Blender or ProBuilder on a fixed grid (2 m or 4 m), so pieces snap
> and tile. Then surface them with **`Assets/ShadowVultureMaterialPack/Material Pack URP/`** — **432 PBR
> material sets already in this project and almost entirely unused**, each with `albedo.png`,
> `normals.png`, `roughness.png`: 8 brick variants, 13 concrete, 8 cracked concrete, rust, metal grate,
> diamond plate, dirt, dry cracked dirt, mud, foliage, rock, mossy stone, burnt, grunge.
>
> That list is almost exactly what the old map builder was painting by hand in code — except these are real
> 4-map PBR sets that respond to light.
>
> **2. Props — barrels, gravestones, signage, wrecks, statues, market stalls.**
> Generate them with **Shadow's Asset Forge** (`Ai Game/Shadow`, Studio → ⬢ Asset Forge): a text prompt
> produces a triangle-budgeted, normal-baked GLB with LODs, entirely locally and free. Measured: ~1 minute
> per asset, 200k raw triangles reduced to a chosen budget. It has a **Unity** export target that emits GLB
> in metres, ready to drop into `Assets/`.
>
> **Its limit, and it is a real one:** generative 3D is good at props and bad at architecture. It will not
> give you a wall that tiles and snaps. That is why structure is authored (1) and props are generated (2).
>
> **3. Sky and ambient light.** A free CC0 HDRI (polyhaven.com) replaces Unity's procedural skybox and gives
> image-based lighting with real colour and direction. Costs nothing, no attribution required.
>
> **Do not tell the owner to buy an asset pack. The budget is zero and the pieces are already here.**

---

# PART III — DIRECTIVES FOR THE REBUILD

## 18. ARCHITECTURE RULES

**18.1 ScriptableObject-driven content.** Every weapon, perk, gum, power-up, map, character, skin, case and
design is an `.asset`. Adding a gun or a skin must never require touching code.

**18.2 A static event hub, not singleton spaghetti.** Keep singletons for true managers; route *broadcasts*
through `GameEvents`. With 12 perks, 8 power-ups, an economy and co-op, every system grabbing five
singletons becomes unmaintainable.

**18.3 `IBackend` from Phase 2 — the single most important instruction here.**

```csharp
interface IBackend {
    Task<PlayerProfile> LoadProfile(string userId);
    Task<CaseResult>    OpenCase(string userId, string caseId);   // server rolls, server allocates serial
    Task<bool>          ExecuteTrade(TradeOffer offer);
    Task                IncrementTracker(string itemId, int kills);
}
```

Ship `LocalBackend` first. The day you move to the cloud must be a **one-line composition-root change**.
This was skipped last time; `CollectionManager` now hard-codes the mint, the odds and JSON persistence in
one class, and every economy feature sits behind untangling it.

**18.4 Server-authoritative for anything valuable.** The client owns input, visual settings, and its own
animation. The server owns HP, Essence, Embers, Soul Crystals, **Sacred rolls, serial allocation, inventory
mutations, tracker increments**. Even before multiplayer exists, write the local stubs so they *behave*
server-authoritatively: reject client-supplied results, return immutable state. A client that says "I got
#1 RGB" is ignored unless the server generated it.

**18.5 Serial allocation must be atomic.** Two players opening the last case of a design simultaneously must
not both receive #250. This is a transaction, not an increment.

**18.6 Pool everything that spawns repeatedly** — zombies, popups, tracers, casings, gibs, decals, kill-feed
rows, case-spinner cards.

**18.7 Telemetry from day one.** Matches, rounds reached, weapons used, kills, case opens, drops by rarity,
currency in and out. **You cannot balance this economy without the data**, and it is worthless collected
retroactively.

## 19. WHAT TO REUSE VERBATIM

| Keep | Why |
|---|---|
| `CollectionManager`'s mint logic | The serial/cap/provenance model is correct; move it behind `IBackend` |
| `CamoSystem` + `LoadoutMenu` | ~80% of the skin equip and grid UI already exists |
| `SmartCamoScroller` | Becomes the RGB animated-variant shader driver |
| `GameFeel`, `BloodFX`, `HitSounds`, `ZombieVoice`, `AmbientAudio` | Self-building, zero art dependencies, genuinely good |
| The atmosphere layer — fog, grading, light cascade, `EnableWithPower`, `PoweredMelody` | Engine-agnostic; works with any geometry |
| Map **design** — zone progression, door pricing, spawn rules (§12.1–12.2) | Playtested and proven |
| The engine-level laws — 3, 7, 8, 9, 11, 12, 13, 15 (§14) | Non-negotiable regardless of art method |

**Do NOT reuse:** the two map builders or their primitive-kitbashing method (§12). Author level art properly.

## 20. THE TRAPS

The specific ways this project has already lost time:

1. **Verify the component exists before tuning it.** The NavMeshAgent was never on the prefab. Months of
   tuning did nothing. When a change has *no* effect, question the premise.
2. **The pause flag is not optional.** Input fires at `timeScale 0`.
3. **Sample `wasAlive` before applying damage.** Corpses stay hittable.
4. **Initialize magazines in `Awake`, not `Start`** — `OnEnable` runs first on a weapon swap.
5. **Only the active weapon may redraw the ammo HUD.**
6. **Never write camera local position from two scripts.**
7. **Weapon bob applies a delta; sway writes absolutely.** They must not share a GameObject.
8. **Particles need a texture.** Otherwise: white squares.
9. **Looping audio needs whole cycles or a crossfade**, and swept oscillators need phase accumulation.
10. **Decal hover must be capped and wrapped**, or splats visibly float.
11. **The camo apply path only runs in `Awake`.** Fix this — the economy needs live equipping.
12. **Don't reorder Part II §17 phases 1–3.** Everything sits on the navmesh and the backend interface.

---

## 21. GLOSSARY

**Essence** in-match points, resets · **Embers** ◆ earned persistent currency · **Soul Crystals** ✦ premium
currency (bought, *and* dropped for surviving past round 10) · **Shards** from scrapping duplicates ·
**Sacred** a case/loot box — the family name for all of them · **Round** a wave ·
**Wallbuy** gun bought off a wall outline · **Mystery Box** random-weapon gamble · **Perk** vending-machine
buff, lost on death · **Pack-a-Punch / PaP** 5000-point weapon upgrade · **Power-Up** floating drop ·
**Down / Bleed-out / Revive** co-op incapacitation · **Hellhound round** dog wave ending in Max Ammo ·
**Train** kiting a horde in a circle · **Crawler** slow legless zombie · **Case** loot box ·
**Serialized / Limited** permanently numbered item · **SCT / Secret** first promotion tier ·
**RGB / Secret RGB** animated top tier · **Serial** globally unique permanent number, #1 is forever ·
**Float / Wear** 0–1 condition value · **Tracker** kill-counting variant · **Original Owner** first puller,
never changes · **Theme** a runtime recolor of a character or zombie model, hue-shifted in-shader ·
**Store** the storefront selling models, Sacreds and Soul Crystals · **Admin-only** content that is built
and visible but entitlement-locked to the owner · **Extraction** voluntary run ending on rounds 10/20/30.

> *Retired names — do not use:* Credits (→ Embers), Cash (→ Soul Crystals), Ash Shards (→ Soul Crystals),
> Crate / Case / Loot Box (→ Sacred).

---

*End of specification.*
