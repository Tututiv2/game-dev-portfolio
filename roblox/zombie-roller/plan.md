# Aftermarket Apocalypse — Roblox Build Plan

Round-based co-op zombie survival shooter (BO2-style) **+** a CS:GO-style
serial-numbered skin economy. This file is the living build plan and task list.
The full design lives in the game design summary; this file is the *how/when*.

> **Golden rules (never break — §9):** No pay-to-win (skins are 100% cosmetic).
> The **server** decides every valuable outcome (case rolls, serials, currency,
> Tracker increments). Serial numbers are global + permanent. Original owner is
> immortal. Float + Tracker count travel with the item through every trade.

---

## Game modes

The game ships with **two modes** sharing the same skin economy, inventory, and
currencies. Skins you own work in both — you grind/flex across the whole game.

1. **Zombies (Co-op)** — teams of up to 4, BO2-style round survival. This is the
   primary mode and the focus of Phases 1–4. First-person.
2. **Sniper Arena (FFA)** — free-for-all PvP, inspired by the Roblox game
   *Sniper Arena*: everyone vs everyone, sniper-focused combat, respawns, score
   by eliminations. First-person. Reuses the weapon/skin systems with PvP damage
   instead of zombie damage.

**Architectural implication:** build a thin **GameMode layer** so map, win/lose
rules, spawning, and damage routing are swappable, while shared systems (weapons,
shooting, inventory, skins, economy, HUD) stay mode-agnostic. We build Zombies
first, then add Sniper Arena as a second mode on the same foundation.

---

## How we work

- **Rojo** syncs this repo into Roblox Studio live. Workflow below.
- I (the agent) edit files here; you press play in Studio. Tell me what to build
  or change and I'll do it.
- One source of truth for tuning numbers: `src/shared/Config/`. Never hard-code
  magic numbers in services — read them from config.

### Rojo workflow (every session)
1. In a terminal at the project root: `rojo serve`
2. In Roblox Studio: install the **Rojo** plugin (once), open it, click **Connect**.
3. Edits to files in `src/` now sync into Studio automatically.
4. To produce a standalone place file: `rojo build --output AftermarketApocalypse.rbxlx`.

**Studio setup (once):** enable **Studio Access to API Services** (Game Settings →
Security) so DataStores work. Until then, `SerialService` uses an in-memory
fallback so the game is still testable offline.

---

## Toolchain
- `aftman.toml` pins **Rojo 7.4.4**. Run `aftman install` after cloning.
- Language: **Luau** (`--!strict` where practical).

## Project structure
```
default.project.json      Rojo mapping (folders -> Roblox services)
aftman.toml               toolchain pin
src/
  shared/                 -> ReplicatedStorage.Shared   (config, types, remotes)
    Config/
      Rarities.luau       the 8 rarity tiers + promotion-tree metadata
      GameConfig.luau     round/points/health/economy tuning numbers
      (Skins, Cases, Weapons, Perks, Rounds — added as we build)
    Remotes.luau          single registry of every client<->server remote
    Types.luau            shared Luau types (SkinInstance is the big one)
  server/                 -> ServerScriptService.Server  (authoritative logic)
    init.server.luau      bootstrap: creates remotes, starts services
    Services/
      SerialService.luau   global atomic serial allocator (DONE)
      (Round, Zombie, Economy, Case, Inventory, Shooting — added as we build)
  client/                 -> StarterPlayerScripts.Client (input + presentation)
    init.client.luau      bootstrap: waits for remotes, starts controllers
    (Input, Shooting, Hud, CaseOpen, Inventory controllers — added as we build)
```

---

## Architecture principles
1. **Server-authoritative.** Client sends *requests* via RemoteEvents; server
   validates, mutates state, replicates results. Client only animates.
2. **Config-driven.** All designs (skins, cases, weapons, perks) are data tables
   in `src/shared/Config`. Adding content = editing data, not logic.
3. **Design vs. instance.** `SkinDefinition` = the authored design. `SkinInstance`
   = a unique owned item (float, serial, Tracker, owner history). See `Types.luau`.
4. **DataStore = the cloud.** No separate backend. Player saves + the global
   serial counter all live in DataStores (MemoryStore later for speed/market).

---

## Build order & task list

Roblox collapses Unity's hardest phases (multiplayer + backend + payments are
built in), so we lead with the **economy hook** — that's the differentiator.

### Phase 0 — Project foundation  ✅ DONE
- [x] Rojo + Aftman toolchain, `default.project.json`
- [x] Shared config layer: `Rarities`, `GameConfig`
- [x] `Remotes` registry + `Types` (incl. `SkinInstance`)
- [x] Server/client bootstraps
- [x] `SerialService` — the atomic global serial allocator (§12)

### Phase 1 — Zombies core loop (solo, multiplayer-ready)  ✅ MOSTLY DONE
- [x] `GameMode` layer (`GameModeService` + `modes/ZombiesMode`): start/stop
      interface so Zombies and Sniper Arena swap cleanly.
- [x] `RoundService`: round counter, spawn `round*5` zombies, scale health
      `base*1.1^(round-1)` (cap r50), intermission, round-start announce, dog rounds.
- [x] `ZombieService`: code-built rig, **PathfindingService** to nearest player,
      melee attack, health, death; on-screen cap; Nuke-style clearAll.
      **Object-pooled** (reuses zombie models instead of create/destroy) and the
      Humanoid stays alive permanently with gameplay health tracked separately —
      built for hundreds of zombies without replication churn.
- [x] Movement tiers: walk (r1-4) / run (r5-9) / sprint (r10+).
- [x] `CombatService`: server-auth raycast hitscan from client aim ray, headshot
      multiplier, awards Essence (Pistol + M4 configured).
- [x] `PointsService` (in-match Essence): per-player, server-auth, HUD push.
- [x] Player health + BO2-style delayed regen + first-person camera + HUD.
- [ ] Solo death → score screen (highest round) + game-over/restart. *(pending:
      currently uses default Roblox respawn so the run continues on death.)*
- [ ] Zombies should spawn from window barriers (Phase 2) instead of a ring.
- [ ] Tune zombie rig height/visuals live in Studio.

### Phase 2 — Map & spending
**Architecture:** map objects are tag/attribute-driven (`CollectionService`), so
systems work on code-generated placeholders AND on hand-built Studio maps. Tag
scheme is documented at the top of `Services/MapService.luau`.

Phase 2a ✅ DONE:
- [x] `Interaction` helper (ProximityPrompt, server-auth `.Triggered`).
- [x] `MapService`: generates tagged placeholder stations (set `GENERATE=false`
      once you build your own map). Also adds a SpawnLocation.
- [x] `DoorService`: buy-to-open, shared (removes the door for everyone).
- [x] `PowerService`: power switch → global power state (gates perks).
- [x] `LoadoutService`: server-owned weapons (max 2), ownership validated in combat.
- [x] `WallbuyService`: buy gun, repurchase = half-price ammo refill.
- [x] `MysteryBoxService`: $950 → random gun (spin animation + relocate later).
- [x] `PerkService` + Jugger-Nog (power-gated, max HP 250, resets on death).
- [x] Client weapon switching (1/2/Q), per-gun ammo, refills.

Phase 2b ✅ DONE:
- [x] `WindowService`: tagged windows with board planks; zombies tear boards,
      players hold E to rebuild (+10/board), boards reset each round.
- [x] Zombies spawn FROM windows and breach (tear → climb in → chase); ring
      spawn is the fallback when no windows exist.
- [x] Custom red health bar above each (pooled) zombie.
- [x] Demo window walls generated in the test map (`MapService`).
- [ ] First real "Town"-style map geometry (replace placeholder stations) — best
      done by hand in Studio using the documented tags.

Phase 2c ✅ DONE:
- [x] `PackAPunchService` ($5000, power-gated): upgrades the equipped weapon —
      2x damage (server), bigger mag/reserve + new name (client).
- [x] `PowerUpService`: ~2% drop on kill — Max Ammo, Bonus/Double Points,
      Carpenter, Insta-Kill, Nuke; spinning glowing pickups, on-screen callouts.
- [x] Dog rounds → guaranteed Max Ammo on clear.
- [x] Premium layered zombie health bar (chip-damage ghost + gloss).

Phase 2 remaining (do when building real maps, not blocking v1.0 economy work):
- [ ] First real "Town"-style map geometry (replace placeholder stations).
- [ ] Solo death → score screen + game-over/restart (also helps PaP/perk reset).

### Phase 3 — Skin & case economy (THE differentiator)
Phase 3a ✅ DONE:
- [x] `Config/Skins.luau`: 10 designs across rarities (incl. Limited "Starfade").
- [x] `Config/Cases.luau`: Field/Veteran (f2p weighted) + Elite (premium tree).
- [x] `DataService` (DataStore profiles) + `CurrencyService` (Credits/Cash)
      + `InventoryService` (per-player `SkinInstance` list). Studio = in-memory fallback.
- [x] `CaseService`: **server rolls first** → rarity (weighted / promotion tree
      Limited→Secret→RGB @5%) → float (per-case curve, per-skin cap) → 5% Tracker
      → `SerialService.allocate` for Limited+.
- [x] `CasesController`: CS:GO spin (scroll → Quart ease-out → land on winner) +
      reveal card (name/rarity/float+band/serial/Tracker/OG).
- [x] `InventoryController`: grid UI with rarity glow, float bar, serial, Tracker.
- [x] Dev menu (`DevController`/`DevService`): owner-only, ~18 cheats incl. give
      Credits/Cash, power, doors, events, PaP, god mode, infinite ammo.

Hub ↔ Arena zoning ✅ DONE:
- [x] `Config/Zones.luau`: hub spawn + arena origin/spawn/ring coords (one source of truth).
- [x] `ArenaService`: participant set; **EnterZombies/ExitZombies** pads (Touched) teleport
      in/out, reset loadout→pistol + Essence→500, broadcast `ZoneChanged`.
- [x] `MapService` split into **HUB** (spawn + enter pad) and **ARENA** (all stations +
      windows + exit pad), offset by `Zones.Arena.origin`.
- [x] `RoundService`/`ZombieService`: rounds only run while ≥1 participant; zombies only
      target/chase participants (hub players are ignored). Resets to round 0 when empty.
- [x] Dev menu split into **🧟 ZOMBIE DEV** vs **💰 HUB DEV**, auto-swaps by zone.
- [x] Client bootstrap hardened: each controller starts in isolation (one failure can no
      longer blank the whole UI).

Phase 3b (in progress):
- [x] **Equip** a skin to its weapon (server-owned `profile.equipped` map, one skin
      per weapon) & **scrap** → Credits (by rarity, `GameConfig.ScrapCredits`). Inventory
      UI: EQUIP/UNEQUIP toggle + two-step SCRAP, green ✓ EQUIPPED badge on the grid.
      *(Visual skin ON the gun is deferred until the first-person viewmodel exists —
      no gun model is rendered yet; equip is data + UI for now.)*
- [x] **Credit reward at end of match** `(round*50)+(kills*5)+(firstWinOfDay?200)`.
      `ArenaService` tracks kills per run (via `CombatService.recordKill`) and the round
      reached (injected `RoundService.getCurrentRound`); pays out on death OR manual
      exit, then shows a `MatchSummary` card (HudController). First-win-of-day persists
      via `profile.lastRewardDay`.
- [x] First-person gun **viewmodel** (`ViewmodelController`): camera-attached gun with
      idle sway, walk bob, fire recoil + muzzle flash. Procedural placeholder gun per
      weapon `class` (pistol/smg/rifle/shotgun/raygun); auto-swaps to a real Model if one
      named `<viewModelId or id>` is dropped in `ReplicatedStorage.Assets` (the `assets/`
      folder). Equipped-skin rarity tints the gun (placeholder until real textures).
- [ ] Float wear visuals (SurfaceAppearance/texture blend) + RGB shimmer.
- [ ] Tracker: increment kills while equipped, persist on the item across matches.
- [ ] Weapon Inspect (hold gun up, rotate, see wear/serial/RGB).
- [ ] Full case roster + pity systems (Rainbow guaranteed every 10 premium opens).
- [ ] Real gun meshes + skin textures (drop models into `assets/`, add SurfaceAppearance).

### Phase 4 — Co-op polish
- [ ] Downed / bleed-out / revive; game over only when all downed.
- [ ] Replicate equipped skins onto teammates' guns (so they can inspect yours).
- [ ] Shared vs per-player state audit (Essence per-player; doors/power shared).

### Phase 4b — Sniper Arena (FFA mode)
- [ ] Second `GameMode`: free-for-all PvP on a sniper map (Sniper Arena style).
- [ ] PvP damage routing (player vs player) reusing `ShootingService`.
- [ ] Respawn system, elimination scoring, round/match timer, scoreboard.
- [ ] Sniper-focused weapon set; equipped skins carry over from inventory.
- [ ] Mode select UI (lobby): choose Zombies (Co-op) or Sniper Arena (FFA).

### Phase 5 — Trading & market
- [ ] Direct trade window (Steam-style: ready resets on change, atomic swap).
- [ ] Player stalls + global Market (list for Cash, 5% fee, instant transfer).
- [ ] Anti-fraud: rate limits, new-account limits, 7-day insurance window, 2FA gate.

### Phase 6 — Real money & live content
- [ ] Cash via `MarketplaceService` Developer Products.
- [ ] Daily login / 3 daily challenges / first-win bonus.
- [ ] Live events: timed Limited cases, global "someone pulled a Limited" feed.
- [ ] More maps, special enemies, easter eggs, seasonal cases.

---

## v1.0 definition (first shippable)
Through Phase 4: one full map, ~5 perks, Pack-a-Punch, wallbuys, mystery box,
power-ups, dog rounds, the full skin economy working on DataStores, and 4-player
co-op. Trading/market/real-money (Phase 5-6) come after.

## Decisions (confirmed)
- **Camera:** first-person for the MVP. Third-person is a later toggle.
- **Co-op size:** teams of up to 4 (Zombies mode).
- **Skin art pipeline:** TBD pending reference screenshot (see note below).

## Open questions
- Skin visual pipeline for the MVP — see "Skin art pipeline" note below; need
  the reference textures to lock this in.
- Sniper Arena specifics: map style, respawn rules, scoring, weapon set.

## Skin art pipeline (what "pipeline" means)
Just "how the gun textures get made and shown in-game." On Roblox the realistic
options are:
1. **SurfaceAppearance** (PBR) — upload a color/normal/metalness/roughness texture
   set onto the gun mesh. Best looking; what AAA-style skins use. The float/wear
   system blends a scratch overlay on top of this.
2. **Texture / Decal swaps** — simpler single-image textures per skin. Faster to
   ship, less fancy.
3. **MeshPart color + tints** — cheapest, for solid-color or simple skins.
We'll likely use **SurfaceAppearance** for the hero skins and decals for the
cheap rarities. (Locking this in once the reference screenshot is provided.)

---

## Cases-as-items economy (DONE)
Cases are now **tradable/openable inventory items**, not opened-on-purchase:
- **Buy** a case in the Shop → grants the case ITEM into your inventory
  (`profile.cases[caseId]`). Spends Cash/Credits. It does not roll yet.
- **Open** a case (from the Shop's per-case OPEN button, or the inventory CASES
  tab) → server consumes one owned item and rolls the skin via the spin reveal.
- **Luxury Case**: every `Shop.robuxPerLuxuryCase` of cumulative Robux spent adds
  a `luxuryClaimable` that waits in the Shop behind a **CLAIM** button. Claiming
  grants a Luxury Case item (exclusive-only pool) you can open or trade.
- Persistence: `DataService.Profile` gained `cases: {[id]=count}` and
  `luxuryClaimable` (replaced the old auto-granted `luxuryCases`).
- Server: `MonetizationService` owns buyCase/consumeCase/claimLuxury + addCase and
  pushes `ShopUpdated { robuxSpent, luxuryClaimable, cases }`. `CaseService.openCase`
  consumes an owned item (refunds on serial failure).
- Remotes: `+RequestBuyCase`, `+RequestClaimLuxury`, `-RequestOpenLuxury`.
- Client: Shop rows have BUY + OPEN(count); luxury section has CLAIM + OPEN.
  Inventory has a CASES tab; selecting a case shows an OPEN CASE action that reuses
  the shop spin animation. HUB DEV: Give Secret Case / Give Luxury Case / +10k Robux.
- TODO later: actual trading between players; real Developer Product ids in
  `Config/Shop.luau` for live Robux purchases.

---

*Update the checkboxes as we complete tasks. Tell me the next thing to build.*
