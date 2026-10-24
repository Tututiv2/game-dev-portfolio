# soulflow — hand-off

New module (wave 5). Entry `com.skyclone.mod.soulflow.SoulflowModule` — **the integrator adds it to `core.ModuleList`**
(no ordering constraint: its three items register in `enable()`, materials' fallbacks register in `postEnable`).

## Wave 5 (2026-10-06)

### Done
- **Soulstream (reference: Soulflow) as a per-player pool** of whole units ⸎ (member key `soulflow`, `SoulflowData`):
  internalized pool, Overflow Mana, toggles, lifetime gained/spent/converted.
- **`Features.Soulflow` provided** (`SoulflowService`): `soulflow(p)` = what can be spent now, `consume` all-or-nothing
  (pool first, then loose Raw Soulstream in the inventory — the pre-accessory behaviour, dial), `add` (capped by dial).
  With `soulflow.enabled` off, `consume` returns true and charges nothing.
- **Feeding it:** right-click Raw Soulstream (+1) / Soulstream (+160) to internalize one, sneak + right-click for every
  one in the inventory; `/soulflow internalize`; menu buttons for inventory and **sacks** (`Features.Sacks`). Needs a
  Soulstream accessory (dial). The Soulstream Engines (minions) already distil Raw Soulstream into minions, so engines
  feed the pool through the same items.
- **Ability costs charged here, without touching the owners' code** (`AbilityGate`): a LOW interact listener mirrors
  core's trigger rules; a hard cost the player cannot pay is refused with `AbilityService.consumeClick` ("You don't
  have enough Soulstream!", no mana or cooldown spent); the cost is taken on `SkyEvents.AbilityUsed` (only fires after a
  SUCCESSFUL cast, so cooldown/no-target refusals cost nothing). Table (`data/soulflow/soulflow.json`, numbers from
  research 06 + NEU lore):
  | ability | item | cost |
  |---|---|---|
  | Phasewarp `weapons_ether_transmission` | Phasewarp Conduit | 1 |
  | Phasewarp `weapons_ether_transmission_void` | Riftwalker Blade, only when merged (`extra.ethermerge`) | 1 |
  | Soul Howl `weapons_soulcry` / `_vorpal` / `_atomsplit` | Voidedge / Vorpal / Fissionblade Katana | 1 / 1 / 2 |
  | Judgement Beam `weapons_salvation` | Oblivion Shortbow | 1 (**soft**, see risks) |
  | Gravity Well / Cell Sync | Gyrokinetic Wand | 10 / 2 |
  | Life Tap `slayer_grimoire` | Gloomlock Grimoire | 15 |
  | Soulguard `soulflow_soulward` | Soulguard Sprig | 10 |
  Matched by ability id, else by (item id, trigger), so a module that re-registers a katana under its own ability id is
  still charged. A lore line `Soul Howl Soulstream Cost: 1⸎` is added to those items (BEFORE_FOOTER).
- **Overflow Mana** (research 01: second pool, max 780): leaving combat (no hit dealt/taken for
  `soulflow.combat-seconds`), the best accessory converts up to Pile 5⸎→200ʬ / Battery 4⸎→400ʬ / Supercell 4⸎→600ʬ,
  topping up only what is missing; End Portal Fumes +30% (600→780) through requirement key
  `slayer:mixin:END_PORTAL_FUMES_MIXIN` (fails closed until slayer answers it). Overflow pays ability mana once ordinary
  mana is short (moved into the mana pool right before core's check). Shown on the action bar (`600ʬ`) and tab list.
  Per-player toggle (reference "Enabled: On / right-click to toggle" — see partial).
- **Combat drains:** Handy Blood Chalice (1⸎/5s in combat → +20 Ferocity) and Pocket Espresso Machine (1⸎/5s → +100
  Intelligence) now WORK (accessories listed them as "no runtime"); default Off like the reference; stats via provider
  `soulflow`; toggling never charges twice inside a paid period. Last Horizon (Final Destination) set: 2⸎/5s while
  sneaking in combat — **off by default** behind `soulflow.charge-owner-gated-drains` (see requests).
- **Soulguard Sprig** (`SOUL_ESOWARD`, Rare, Enderman 3; crafting's NEU recipe `sb:SOUL_ESOWARD#0` appears now that the
  item exists): 350 mana, 20s, 10⸎ — invulnerable and harmless for 5s, then outgoing damage ×0.5 for 2s (dials).
- **Items now owned here:** `RAW_SOULFLOW`, `SOULFLOW` (same names/material/tags as materials' fallback, new lore with
  the right-click rule), `SOUL_ESOWARD`.
- `/soulflow` menu (alias `/soulstream`): pool summary, internalize from inventory / sacks, Overflow toggle, drain
  toggles, Last Horizon info, the full cost table, Go Back → SkyBlock Menu. Also opened by left-clicking the air with
  Raw Soulstream / Soulstream in hand.
- Verified: `bash tools/check.sh soulflow` → `CHECK OK`; 9 JUnit tests (`SoulflowMathTest`, `SoulflowDataFilesTest`)
  pass (run with a standalone JUnit launcher against the module + api/core; the integrator's `gradlew build` runs them
  too). Server NOT started (lane rule): the 5 self-tests are for the integrator.

### Still left / partial
- **Internalized Soulstream is per player, not stored on the accessory item** (reference lore "Internalized: 0⸎" sits on
  the item). The bag's stacks belong to accessories, so the pool lives in member data; trading an accessory does not move
  its Soulstream.
- **Toggles live in `/soulflow`, not on right-clicking the accessory**: accessories already uses right-click-in-hand to
  drop an accessory into the bag.
- End Portal Fumes' +30% waits for slayer's `slayer:mixin:<id>` key (request 1).
- Judgement Beam is a soft cost (charged when possible, never refused) — weapons' handler decides charged-beam vs normal
  shot internally, so refusing the click would also eat the normal left-click shot (request 2).
- Raw Soulstream's only source is the Soulstream Engines (minions); research has no other drop source.
- Travel: the Void Sepulture scroll is combatzones' (recipe uses Raw Soulstream); no other soulflow travel item exists in
  the research.

### Fix pass (2026-10-06): playtest open issue 7, "Phasewarp landed 8 of 17 blocks" (shot 078)
**No code change: no defect found, and none of it is soulflow's code.** What was checked, offline (server not started):
- Soulflow's only part in a Phasewarp is `AbilityGate`: a LOW pre-check that may refuse the click (no Soulstream) or
  move Overflow into mana, and the 1⸎ charge on `AbilityUsed`. Neither touches position, rotation or range. The warp
  itself is weapons' `Teleporters.ether` (ray `rayTraceBlocks(eye, look, 57, NEVER, ignorePassable=true)`, land on top
  of the hit block if feet + head are passable).
- The world at the spot, read straight from `server/world/dimensions/minecraft/sb_torrhus_canyon/region` (saved at the
  15:53 shutdown, two minutes after the shot): Canyon Camp is **flat ground at y=87 from x=-10 to x=-45 on rows
  z=80..88**, and **nothing with a collision box stands at y 88..91 between x=-20 and x=-45** on the rows the line can
  have used (only grass/fern/bush/wildflowers, which the ray ignores; the first solid thing is the house wall at x=-46).
  The server log puts the start near the Torrent Canyon warp (-20.9, 88, 85.5): `/warp torrhus_canyon` at 15:46:59,
  conduit given 15:50:45, landing at (-28, 88, 84) on cut sandstone. So the ray hit the GROUND ~7 blocks out; nothing
  "in the way" stopped it.
- On flat ground the landing distance is fixed by the pitch alone: 1.62 / tan(pitch). 17 blocks = 5.4° down, 7-8 blocks
  = ~12° down. 078's crosshair (pitch carried over by the warp) sits just in front of the house base, i.e. ~6-7° —
  so either the server saw a steeper pitch than the client showed at the click, or the "17 blocks" was read off the
  post-warp view. Neither is in soulflow's lane; weapons' raytrace is the reference rule and is correct for flat ground.
- To settle it next playtest: stand on Canyon Camp, `look` at a fixed pitch (e.g. `pilot.py look <yaw> 5.4`), right-click
  the Phasewarp Conduit, and compare the landing with 1.62/tan(5.4°) = 17.1 blocks. A short warp there is a weapons
  issue (rotation at click time), not soulflow.

## Commands added
- Player: `/soulflow [internalize | toggle <overflow|chalice|espresso>]` (alias `/soulstream`).
- Admin: `/sb soulflow <player> [info | give <n> | take <n> | set <n> | overflow <n> | combat on|off]` — `combat off`
  then waiting one second runs the leaving-combat conversion (RCON-testable).

## Dials added
`soulflow.enabled` · `soulflow.costs-enabled` · `soulflow.cost-scale` (1.0) · `soulflow.pool-needs-accessory` (true) ·
`soulflow.internalize-needs-accessory` (true, TUNE) · `soulflow.inventory-fallback` (true) · `soulflow.pool-max` (0 = none) ·
`soulflow.combat-seconds` (8, TUNE) · `soulflow.overflow-enabled` · `soulflow.overflow-scale` (1.0) ·
`soulflow.overflow-pays-abilities` · `soulflow.drains-enabled` · `soulflow.charge-owner-gated-drains` (false) ·
`soulflow.soulward-seconds` (5) · `soulflow.soulward-weak-seconds` (2) · `soulflow.soulward-weak-multiplier` (0.5) ·
`soulflow.message-cooldown-seconds` (3) · `soulflow.sack-internalize-max` (100k).

## Self-tests added
`soulflow.items-exist` (ours registered and ours, every tier/drain/currency id exists) · `soulflow.costs-match-items`
(every cost hits a real ability on its item — a renamed ability id would silently make it free) ·
`soulflow.research-numbers` (1/160, 5→200 / 4→400 / 4→600, 780 cap, each ability's cost) · `soulflow.service-provided`
(ours is the provider, null-safe, all-or-nothing plan) · `soulflow.menu-and-names` (every menu lore builds; no reference
proper noun in our text).

## Requests
1. **slayer**: answer `slayer:mixin:<MIXIN_ID>` in the `slayer` requirement resolver (true while that mixin is active);
   End Portal Fumes' +30% Overflow then works with no change here. Also: grant Vivid Darkness (Last Horizon sneaking
   bonus) only when `Sky.service(Features.Soulflow.class).soulflow(p) > 0`, then the integrator flips
   `soulflow.charge-owner-gated-drains` on. Optional: Life Tap could add Overflow instead of mana (reference) — would need
   a `Features.Soulflow.addOverflow`; today it stays mana and soulflow charges the 15⸎.
2. **weapons**: in `salvation`, call `Sky.service(Features.Soulflow.class).consume(p, 1, "judgement_beam")` before firing
   the charged beam (and fall back to a normal shot when it is false); then change its entry to `"gate": "hard"` — or
   leave it and soulflow keeps charging after the fact. Drop the "Soulflow costs are ignored" line from your hand-off.
3. **accessories**: lore of SOULFLOW_PILE / BATTERY / SUPERCELL and the Chalice/Espresso says lower-case "soulflow"
   (a reference name): "Soulstream". Remove "soulflow items" and Chalice/Espresso from the "no runtime" list.
4. **menus**: a "Soulstream" button (e.g. in the SkyBlock Menu's combat area or Settings) that runs `/soulflow`.
5. **core (optional)**: an Overflow Mana pool in `VitalsService` (spent by `useMana` once mana is empty, shown as `ʬ`) would
   cover mana spent outside abilities; this module tops mana up only for item abilities.

## Known risks
- The pre-check mirrors core's trigger rules (`AbilityListener`); if core changes them (new trigger, new "interactable
  block" rule), refusals and charges can drift apart. The charge itself is always on `AbilityUsed`, so the worst case is
  a cast that was refused by us but would have failed anyway, or a cast that slips through and is charged soft.
- Overflow top-up estimates the cost from `AbilityDef.mana` (no cost modifiers / Mana Disintegrator), so it may move a
  little more Overflow into mana than the final cost; never more than what is missing below the base cost.
- Taking over `SOULFLOW` / `RAW_SOULFLOW` from materials: same name, material and tags, so stacks, sacks and bazaar are
  unchanged; if another module registers them first, self-test `soulflow.items-exist` fails.
