# core-combat — hand-off

Package `com.skyclone.core.combat`. Boot class `CombatCore` (`boot(JavaPlugin)`, `afterModules()`, `shutdown()`).
Provides **CombatService**, **MobService**, **LootService**.

## What works (verified how)

Verified by: `bash tools/check.sh core` -> **CHECK OK: core (142 files)**; `javac -Xlint:all` clean on api + this
package alone; **18 JUnit tests** for the pure maths/formatting
(`plugin/src/test/java/com/skyclone/core/combat/`, run here with the JUnit platform launcher, 18/18 green);
8 live self-tests registered for the integrator. Nothing has been run on a live server yet (not allowed in phase B).

- **Damage pipeline** (`CombatServiceImpl`), formulas in `DamageMath` (pure, unit-tested against every worked
  example in research 01 §4-5: 450 / 402.5 / 1,811 / 47 / Apex 2,311,153 / AOTD 18,000):
  - melee & bow: `(5 + weapon + flatBonus) * (1 + str/100) * (1 + additive/100) * multiplicative * (crit ? 1 + cd/100 : 1)`
  - magic: `base * (1 + int/100 * scaling) * (1 + abilityDamage/100) * (1 + additive/100) * multiplicative` (never crits)
  - then mob side: `* (1 - magicResistance)` -> boss cap `T + 0.1*(dmg - T)` -> `* 100/(100+defense)` -> `floor`
  - to a player: `(base + flat) * (1 + additive/100) * mult * 100/(100+Defense)`; TRUE kind uses True Defense.
  - Ferocity: `floor(fer/100)` guaranteed extra strikes + `(fer%100)%` chance of one more (cap 500, dial), 0.2s apart,
    same final damage and crit state, red dust slash + sound, own indicator, own `SkyEvents.Damage` (kind FEROCITY,
    tag `"ferocity"`). Only MELEE/PROJECTILE hits proc it.
- **Indicators**: non-persistent TextDisplays, hard cap 40 alive (oldest removed), one 1-tick task drives all of
  them (client-interpolated rise, removed after 20 ticks). Normal `&71,234`, crit `&f✧&f1&e,&62&c3&f4&e✧`
  (first char white, then every char cycles f/e/6/c — commas included), TRUE white. View range 0.5 (32 blocks).
- **Vanilla interception** (game worlds = name starts `sb_` or `Sky.worlds().islandOf()` knows it), `CombatListener`
  at `HIGH, ignoreCancelled=true` (so NPC/safe-zone cancels at lower priority win):
  - player -> sky mob melee: vanilla damage set to 0 (knockback + hurt flash kept) and routed through `playerAttack`.
  - player arrow -> sky mob: PROJECTILE using the bow captured at `EntityShootBowEvent` (damage scaled by draw force,
    dial). Thrown trident uses its own stack. Snowballs/eggs/hooks do nothing to sky mobs.
  - sweep attacks on sky mobs cancelled (dial).
  - sky mob -> player (melee, its arrows, creeper blast): vanilla zeroed (or cancelled, dial) then
    `damagePlayer(def.damage, mob, MOB)`, which runs the mob's `MobBehaviour.onAttack` first.
  - environment -> player: `% of max health` per vanilla damage point, per cause group (dials): fall 5%/block beyond 3
    (vanilla's fall number, so Feather Falling/jump boost already apply), fire/lava 5%/point (lava = 4 points = 20%),
    drowning, suffocation, everything else. Same-cause cooldown 10 ticks. VOID and /kill = `vitals.kill`.
    Zone flag `NO_FALL` cancels fall damage. Defense does NOT reduce environment damage unless `combat.env-defense`.
  - PvP only when BOTH players stand in a zone flagged `PVP` (or `combat.pvp-everywhere` for tests); otherwise cancelled.
  - anything else that would touch a sky mob's vanilla health (mob vs mob, TNT, fire, cactus, suffocation...) is
    cancelled; void / KILL removes the mob silently.
- **Hit rate**: vanilla invulnerability is OFF on sky mobs (`maxNoDamageTicks 0`). Core rate-limits melee **per
  attacker per mob** (`combat.mob-invuln-ticks` 10, divided by `1 + AttackSpeed/100`, AS capped 100). Several
  players can hit one mob at full speed; every arrow lands.
- **MobService**: register/loadJson (lenient Gson; bad entries logged and skipped; duplicate id keeps the FIRST),
  behaviours, spawn rules, spawn/defOf/isSkyMob/health/max/setHealth/setMaxHealth/owner/remove/live.
  Spawned mobs: `persistent=false`, `removeWhenFarAway=false`, no item pickup, PDC `skyclone:mob=<id>` (+ `mob_owner`,
  `mob_hp`, `mob_max`), vanilla MAX_HEALTH = 1024 gauge that never moves, speed attribute x `def.speed`,
  `no_knockback`, `baby`, `silent`, `no_ai`, equipment (sky id via `Sky.items().create`, else Material; drop chance 0),
  `skull` texture on HEAD, nameplate always visible.
- **Nameplate**: `&8[&7Lv30&8] &cCrypt Ghoul &a1,800&f/&a2,000&c❤`, current HP green >50%, yellow >25%, red below;
  k/M/B at >= 100,000 (dial). Mobs flagged `boss`: `&c☠ &4Name &a50M&c❤` (genre boss style). Updated on every change.
- **Behaviours**: onSpawn after tracking; onTick every 5 ticks (tick counter in ticks); onDamaged after the formula
  and before `SkyEvents.Damage` (can change `ctx.finalDamage` or cancel); onAttack before modifiers when the mob hurts
  a player; onDeath before MobKill. Every hook is try/caught (logged once per hook+mob).
- **Death** (custom hp <= 0): untrack -> onDeath -> `SkyEvents.MobKill` (killer = last player hit within 15 s, owned
  mobs: always the owner if online) -> pay `ev.coins` via `Sky.economy().deposit(killer, coins, "mob_kill:<id>")`
  with a private floating `+X coins` -> `Sky.loot().roll(killer, def.drops, source, MAGIC_FIND)` -> equipment cleared,
  AI off, vanilla `setHealth(0)` ONE TICK LATER for the death animation (drops/xp cleared by tag). Loot source:
  `extra.lootSource` if set, else FISHING for `sea_creature`, SLAYER for `boss` with a `slayer`, else MOB_DROP.
- **Lifecycle guards**: no burning (unless flag `burns`), no transform/conversion, no slime split, no block griefing
  (explosions keep entity damage, lose block damage), no egg laying, target only players (never for `passive`, only
  the owner for owned mobs). `aggressive` mobs that are not hostile in vanilla pick the nearest player within 16.
  Mobs that leave the world (chunk unload with persistent=false, plugin removal) are untracked automatically.
- **Ghosts**: `afterModules()` and every `EntitiesLoadEvent` remove tagged mobs/indicators core is not tracking.
- **Spawn rules** (`MobSpawner`, once a second): never loads a world or chunk. Active when world `sb_<island>` is
  loaded, the condition holds and any player is within `activationRange` of the area box / any anchor. Keeps
  `max - live - pendingRespawns` topped up, at most 4 per rule and 60 total per second, stops at 2,000 live
  server-wide. A death starts a `respawnSeconds` timer; mobs removed for other reasons are replaced at once.
  Area spawning caches up to 24 standable spots (solid floor, 2 passable non-liquid blocks above, not in a SAFE or
  NO_MOBS zone), revalidated on use. Anchors are cycled. Conditions: null, `night`, `day`, `event:<id>`, any of them
  prefixed `!`. Unknown condition = never (closed by default).
- **LootService**: `give` fires ItemGain (cancelled = absorbed: returns 0, adds nothing), then `addItem` (merges via
  isSimilar, splits oversize stacks), overflow dropped at feet split to legal stack sizes, owned 60 s (vanilla owner +
  PDC + pickup events + hopper block), `&cYour inventory is full!` throttled 3 s. `roll`: requirement gate, MF only
  when base chance < 5% (dial) and `magicFind` true, MF capped 900, amount uniform min..max, unique items created one
  by one (each gets its uuid), labelled drops via `Msg.rareDrop` (`&8 3x ` prefix when amount > 1, MF suffix only
  when MF actually applied). `dropFor`: invisible to everyone else (`setVisibleByDefault(false)` + `showEntity`),
  permanently owner-only, never merges with other items.

## Behaviours module authors MUST know

1. **Modifiers run on EVERY hit**, including mob -> player (`ctx.kind == MOB`, `ctx.playerVictim() != null`),
   environment and PvP. A "+X% damage" modifier must check `ctx.playerAttacker() != null` (and usually
   `ctx.weapon`). A "take X% less damage" armour bonus checks `ctx.playerVictim()` and lowers `ctx.multiplicative`.
   Modifiers do NOT run for `rawDamage` or ferocity follow-ups (ferocity copies the first hit's numbers).
2. **Modifiers mutate inputs** (`base`, `flatBonus`, `additive`, `multiplicative`, `crit`, `tags`, `cancelled`);
   `ctx.finalDamage` is computed AFTER all of them. To clamp/override the final number (damage caps, immunity),
   use `MobBehaviour.onDamaged` or listen to `SkyEvents.Damage` and set `ctx.finalDamage`. `ctx.crit` is rolled
   BEFORE modifiers so a modifier can read or force it.
3. Mob-only knobs live in `MobDef.extra`: `"magicResistance": 0.15` (fraction), `"damageCap": 100000` (boss cap
   threshold, any mob), `"nameFormat": "{name} {hpc}{hp}&c❤"` (placeholders `{level} {name} {hp} {max} {hpc}`),
   `"nameplate": "none"` (hide), `"lootSource": "DUNGEON"`. Flag `burns` lets a mob catch fire.
4. Tags core sets: `"ability"`, `"ability:<name>"` (magic), `"ferocity"`, `"raw"`, `"projectile"` (mob arrows),
   `"mob:<id>"` (incoming hits from a sky mob).
5. `spawn()` throws `IllegalArgumentException` for an unknown id; returns **null** if a CreatureSpawnEvent listener
   refused the spawn. Spawned mobs are NOT persistent: a chunk unload removes them (they are untracked, no loot).
   Slayer/dungeon code must handle its boss disappearing (check `isSkyMob`/`isValid`).
6. `setMaxHealth(mob, x)`: a mob at full health stays full (hp = x); otherwise hp is clamped. So
   `spawn` -> `setMaxHealth(20_000_000)` gives a full 20M boss.
7. `setHealth(mob, 0)` kills it WITH loot (killer = last hitter); `remove(mob)` kills silently with no loot.
8. `rawDamage` applies exactly `floor(amount)` — no defense, no modifiers — but still runs onDamaged and fires
   `SkyEvents.Damage`. `damagePlayer` always applies Defense (True Defense for TRUE), per the API javadoc.
9. `MobKill` fires even with `killer == null`; core pays coins and rolls loot only when the killer is online.
   Combat XP is NOT granted by core (skills listens to MobKill and reads `e.combatXp`).
10. Owned mobs (`spawn(id, at, owner)`): only the owner (or an admin in creative) can damage them; they only target
    and hurt their owner. Party support needs an API hook (see requests).
11. `playerAttack(..)` from code works for any LivingEntity victim that is a sky mob or player; returns 0 for anything
    else (NPCs, vanilla animals). It does NOT apply the per-attacker hit-rate limit (that is the vanilla-click path),
    so abilities that call it can multi-hit.
12. Behaviour ids in `MobDef.behaviours` are resolved at call time, so registering a behaviour after the mobs load is
    fine; an unregistered id is logged once and skipped (and fails the `mob.defs-valid` self-test).
13. Spawn rules assume `Sky.worlds().area()` boxes and `anchor()` locations are **world coordinates** of the shared
    island world `sb_<island>`.

## Commands added (admin, all under /sb)

| Command | What |
|---|---|
| `/sb mob spawn <id> [count]` | spawn at the block you look at (max 100) |
| `/sb mob list [filter]` | mob types with level/health/damage/alive count (40 shown) |
| `/sb mob killall [radius]` | remove sky mobs (no loot): within radius, or the whole current world |
| `/sb mob info` | the sky mob you look at: def, live health, owner, rule, flags, behaviours, drop table |
| `/sb spawner list [filter]` | every rule: active/paused, live/max, pending respawns, status reason |
| `/sb spawner pause <ruleId>` | toggle one rule |
| `/sb damage <amount> [mob\|true\|env]` | hurt yourself through the real pipeline |
| `/sb combat debug` | toggle a chat breakdown of every hit you deal (base, str, additive, mult, crit, def, final, tags) |
| `/sb combat modifiers` | registered damage modifiers in run order |
| `/sb combat stats` | live mobs / indicators / types / rules |

No player commands (combat has no player-facing menu).

## Dials added

`combat.mob-invuln-ticks` 10 · `combat.mob-invuln-min` 2 · `combat.attack-speed-cap` 100 · `combat.ferocity-cap` 500 ·
`combat.ferocity-delay-ticks` 4 · `combat.indicator-max` 40 · `combat.indicator-ticks` 20 ·
`combat.player-indicators` false · `combat.mob-knockback` true · `combat.cancel-sweep` true ·
`combat.bow-force-scaling` true · `combat.enforce-weapon-requirements` true · `combat.pvp-everywhere` false ·
`combat.env-defense` false · `combat.fall-percent-per-block` 5 (TUNE) · `combat.fire-percent` 5 ·
`combat.drowning-percent` 5 · `combat.suffocation-percent` 5 · `combat.environment-percent` 5 ·
`combat.environment-cooldown-ticks` 10 · `mob.gauge-health` 1024 · `mob.short-num-threshold` 100000 ·
`mob.killer-window-seconds` 15 · `mob.spawner-enabled` true · `mob.spawn-per-rule-per-second` 4 ·
`mob.spawn-global-per-second` 60 · `mob.max-alive` 2000 · `mob.aggro-range` 16 · `mob.coin-flash` true ·
`loot.mf-threshold` 0.05 · `loot.mf-cap` 900 · `loot.owner-seconds` 60 · `loot.full-message-cooldown-seconds` 3

The no-attack-cooldown attribute is owned by core.items (`stats.attack-speed-attribute`), not here.

## Self-tests added

`combat.formula-research-examples`, `combat.ferocity-and-defense`, `combat.indicator-and-nameplate-format`,
`loot.magic-find-and-labels`, `mob.drops-exist` (every drop item is registered), `mob.defs-valid` (entity types,
behaviours registered, equipment slots), `mob.spawn-rules-resolve` (mob exists, island known, layout built, area /
anchors present), `combat.services-installed`.

## API change requests

1. **`VitalsService.damage(Player, double amount, String cause)`** that consumes absorption, then health, then kills.
   Core-combat currently does `addAbsorption(p, -x)`, `setHealth`, `kill` — core.items' vitals documents and accepts
   exactly that, so this is a cleanliness request, not a bug.
2. **`MobService.setAllies(LivingEntity mob, Predicate<Player>)`** (or an `owners` set) so parties/co-op can share a
   boss. Today an owned mob is owner-only.
3. **`MobDef.damageCap` / `magicResistance` as real fields** instead of `extra` keys.
4. **`CombatService.playerAttack` overload with a damage multiplier** (bow draw, Terminator-style partial shots);
   core currently passes bow force through an internal one-shot field.
5. Consider documenting on `DamageContext` that modifiers run for incoming hits too (point 1 above).

## Shared-file change (please review)

`tools/check.sh`, two one-line fixes, behaviour otherwise unchanged:
1. The @file of sources used unquoted `cygpath -w` paths; the project path contains a space ("Ai Game"), so javac
   split every path and **no module could ever print CHECK OK**. Now writes quoted forward-slash paths
   (`cygpath -m | sed 's/.*/"&"/'`).
2. **The control-character guard was silently off.** Under Git Bash's default locale `grep -P` refuses to run
   ("supports only unibyte and UTF-8 locales"); with `2>/dev/null` that refusal produced an empty result, which the
   script read as "clean" — the exact bug class rule 9 exists for. Now runs with `LC_ALL=C.UTF-8`. Proven both
   ways: it flags a planted `\b` in a scratch file, and `check.sh core` is still OK (143 files), so api/core are clean.

## Known risks

1. **Never run live.** The vanilla-interception assumptions most likely to need a tweak on the real server: that
   `setDamage(0)` on a player keeps knockback (if not, set `combat.mob-knockback` false), and that `maxNoDamageTicks 0`
   on sky mobs plays well with very fast clicking (knockback every hit; the per-attacker limiter caps the rate).
2. **Environment numbers are TUNE** (5% of max HP per vanilla point; lava = 20%/hit) — the research has no exact
   figures for fall/fire/drowning in the genre. All dials.
3. `check.sh core` was green at hand-off (142 files) but the other cores were still being written alongside; this
   package also compiles clean on its own against api+gen with `-Xlint:all`. Re-run `bash tools/check.sh core` once
   every core agent has finished.
4. Spawn rules depend on area boxes being world coordinates and the shared-island world being named `sb_<island>`;
   if core.world names worlds differently, rules report `world not loaded` in `/sb spawner list`.
5. Owned-mob drops roll for the owner even if someone else's DoT landed the last tick (by design: loot only to owner).

## Fixes after integration round 3 (core fixer, nice-to-have requests)
- **kuudra Request 7 — `MobDef.extra.size` is honoured at spawn.** `MobServiceImpl.configure` calls `Slime.setSize`
  (Slime and MagmaCube only, clamped 1..127) BEFORE it sets the health gauge and the `speed` multiplier, because
  `setSize` resets vanilla max health / speed / attack damage. `extra.scale` sets the SCALE attribute (0.0625..16;
  1 or absent = untouched). Modules can drop their own resize-and-restore (kuudra `Run.resize`, mines `MineMobs`
  SLIME behaviour) whenever they like; leaving it is harmless.
- **Safety net for the modules that still resize in `onSpawn`** (mines, crimson `Bosses`, garden pests, slayer
  props): after the behaviours' `onSpawn`, a slime whose MAX_HEALTH base is no longer core's gauge gets the gauge
  back (and, if its speed is vanilla's `0.2 + 0.1 × size`, the def's `speed` multiplier). Before this, e.g. a mines
  slime ran on a 4-HP vanilla gauge.
- Unit test `core/combat/MobExtraTest` (clamping, non-numeric size, scale, vanilla speed formula). Never seen live.

## Fixes after playtest "endgame" (core fixer, 2026-10-03)
- **[major] Overflow goes to an ITEM STASH, not the floor.** A Dark Auction prize collected with a full inventory lay
  on the ground as an item entity ("skull's Head"). `LootServiceImpl.give` now sends whatever does not fit to the
  player's item stash (`core/combat/ItemStash` + pure `StashData`, profile member key `core_item_stash`):
  identical items merge into one row (Base64 `serializeAsBytes` of a one-count copy + a count), unique items never
  merge. Player commands `/pickupstash` (alias `/pickstash`) and `/viewstash` (alias `/stash`, a paged menu: click a
  row or "Pick Up Everything"); a clickable `[PICK UP]` chat button on every stash notice and on login. Rewards,
  trades, auction/bazaar/NPC-shop/admin items and unique items are named in chat and `saveNow`'d at once; grinding
  overflow shares the existing throttle. An OFFLINE player's items are written into their active profile's stash
  via `modifyOffline` (previously dropped where they logged out). Pickup is refused in the Rift (its swapped
  inventory would park the item there), spam-guarded at 2/s, saves player data before the profile. Only a FULL stash
  (`loot.stash-max-entries` 720 kinds) or `loot.stash-enabled false` falls back to the old owner-only floor drop.
  The same routing covers core's other two floor drops: menu free-slot returns (`core/ui/UiUtil.give`) and the
  vanilla-conversion sweep overflow (`ItemServiceImpl.sweep`).
- **[minor] Death coin loss is whole coins.** `VitalsMath.deathLoss` floors to a whole coin (was a tenth: "lost
  6,247,670.5 coins!") and `kill` floors again after `SkyDeath` listeners (piggy bank, enchants) scale it; the line
  prints via `Text.commasInt`. The player keeps the fraction.
- Dials: `loot.stash-enabled`, `loot.stash-max-entries`. Admin: `/sb stash <player> [clear]`. Self-tests:
  `loot.stash-roundtrip`, `loot.stash-merge-and-cap`. Unit test `core/combat/StashDataTest` (5) + new death-loss
  asserts in `ItemsPureTest`; all 117 core unit tests pass; `check.sh core` OK (172 files).
- **Requests:** (api owner) `LootService` javadoc still says overflow "drops at the player's feet" — it now goes to the
  stash and `give` returns only what reached the floor. (menus) add an "Item Stash" button to the SkyBlock Menu that
  runs `/viewstash`. (mayors) `Claims` needs no change — its hand-over goes through `Sky.loot().give`, so a full
  inventory now stashes the prize.
- **Never run live.** Unverified in a client: the clickable chat button, `serializeAsBytes` round-trip of a sky item
  with PDC data (the self-test round-trips a diamond, ENCHANTED_DIAMOND and ASPECT_OF_THE_END when registered, but has never run live), and the paged menu's slot 50 button.

## Fixes after playtest "wave4-loops" (core fixer, 2026-10-04)
- **[minor] The stash no longer hands bulk back first.** Harvest Fair Tickets and Fossil Essence stashed during a farming
  session sat behind 4,000+ wheat and seeds (oldest first), so each `/pickupstash` refilled the inventory with wheat and
  Harvesta's exchange read "Harvest Fair Tickets: 0" for six rounds. `StashData.Entry` gained `priority` (the item was
  named = reward/trade/unique) and `rank` (rarity ordinal at stash time); merging lifts them, never lowers them.
  `StashData.pickupOrder()` = priority, then rarer, then SMALLER pile, then oldest; `/pickupstash`, the menu's
  "Pick Up Everything" and the `/viewstash` list all use it. Pickup also no longer stops at the first entry that does
  not fit — a later entry can still top up a stack the player already holds. Old saved rows (no fields) read as
  bulk, rank 0, which sorts them by pile size: fine.
- **[minor] Overflow chat is per episode, not per 20 s.** Bulk overflow (farming/mining/mob drops) prints
  "Your inventory is full! Items are going to your item stash (N items waiting)." ONCE, then again only every
  `loot.stash-reminder-seconds` (new dial, 300) until the player picks up (a pickup starts a new episode). Between
  those, an action-bar flash `Inventory full - N in /pickupstash` at most every `loot.full-message-cooldown-seconds`
  (20). 6 minutes of farming = 2 chat lines instead of 17. Named items (rewards, trades, uniques) are still named in
  chat every time.
- Self-test `loot.stash-merge-and-cap` now also asserts the order; `StashDataTest` 5 → 8 (8/8 pass via a plain JUnit
  launcher). `check.sh core` OK (173 files).
- **Requests:** (farming) Harvesta's exchange counts tickets in the inventory only; it could say "N more in your item
  stash - /pickupstash" when the count is short (read profile member `core_item_stash`, `StashData.items[].id`).
  (farming/mining) wheat, seeds and essence in an AI-show grind should reach a sack before the stash at all — if the
  player has no sack, that is where the 4,000 rows come from.
- **Never run live**: the action-bar flash (it uses `Sky.vitals().flash`, which replaces the bar's middle segment for
  2 s) and the episode reset after a pickup.

## Fixes after playtest "wave4-endgame" (core fixer, 2026-10-04)
- **[minor] A burst of named stash notices is one chat line.** `/sb foraging kit` (17 unique axes) into a full inventory
  printed 17 "Your inventory is full! X went to your item stash" lines: the bulk throttle covered stacks, but every
  unique item is its own `give` and named items bypassed it. `ItemStash.notifyStashed` now queues named items into a
  per-player `StashBatch` (new, pure) flushed ONE tick later; a later burst waits until 2 s
  (`ItemStash.NAMED_WINDOW_MS`) after the previous named line. One item keeps the old wording; several become
  "Your inventory is full! 17 items went to your item stash: Axe A, Axe B, Axe C and 14 more. [PICK UP]" with the
  full list (up to 20) on hover. Same name twice adds up ("x100"). Saving is unchanged (still `saveNow` per item);
  a player who logs out before the flush gets the existing login reminder instead.
- Unit test `core/combat/StashBatchTest` (5/5 via a plain JUnit launcher). `check.sh core` OK (175 files).
- **Never run live**: the one-tick flush, the hover list on the chat line.
