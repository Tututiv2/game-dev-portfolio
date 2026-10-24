# core-gate — hand-off (2026-10-02)

The gate took the five core packages from "compiles" to "proven on a live Paper 26.1.2 server with a
real client". Module authors: read `docs/CORE_GUIDE.md`. This file records what the gate changed,
how it was verified, and what is still unproven.

## How it was verified

| Check | Result |
|---|---|
| `bash tools/check.sh core` / `all` | CHECK OK (165 files) |
| `gradlew clean build` | 87 unit tests, 0 failures. Jar `plugin/build/libs/SkyClone.jar`, 830 KB, no `mod/` classes |
| Clean boot, no modules | zero exceptions; `modules: 0 enabled, 0 FAILED, 35 not present` (one INFO line per missing module) |
| `/sb selftest` (no modules) | 42 passed, 0 failed |
| `/sb selftest` (with the temp test module, player online) | 46 passed, 0 failed |
| **A real 26.1.2 client joined** (MyFire launcher, dev account `GateBot`, server briefly `online-mode=false`, restored to `true`) | PreLogin -> Join -> ProfileReady, sidebar, tab, action bar, star in slot 9, chat and NPC dialogue all seen in client screenshots |

A temporary module `com.skyclone.mod.coretest` (since deleted, with its ModuleList entry, world and
data) proved the whole pipeline end to end:

- **Items.** `create()` round-trips the id and data. A unique item gets a uuid; stackables stack. Lore
  renders in the genre format, both over RCON and in the client tooltip. `/sb give` works. A vanilla
  log dropped into the world converted to a sky item on pickup.
- **Recipes.** `producing` and `using` both resolve.
- **Island.** It generated and built 32,506 blocks in 1 s using 124 ms of tick time. The layout
  was persisted to `islands/coretest.json`, and a restart did not rebuild it.
- **NPC.** It spawned exactly on its anchor (distance 0.00). A real right-click from the client
  played the dialogue, then the menu opened. Clicking the icon ran the handler next tick, the item
  came through `loot.give`, and the icon could not be taken.
- **Mobs.** The spawner filled the pen to 4/4. A kill through `playerAttack` paid +10 coins (in the
  ledger), rolled loot (+2 shards), incremented the module's member data, and the 5 s respawn timer
  refilled the pen.
- **Damage formula through the real pipeline.** 1000 base magic damage at scaling 1 on a dummy with
  0 defence dealt **1000** with Intelligence 0, and **1500** with the 50-Int test sword. Before the
  gate's fix it would have dealt 2000 (see below).
- **Economy.** deposit 1234.5, withdraw 234.5, a refused overdraw, and a bank deposit gave an exact
  ledger. The ledger is readable from the DB over RCON.
- **Profiles.** A value written while online, then `stop`, start and read while offline, came back
  intact. A value written while offline was applied at the next login. Purse 900 and bank 100
  survived the restart.
- **Dials.** `2.5k` parses, garbage is refused, and reset works.
- **New APIs.** Every API added below was exercised live with `/sb coretest api`: all OK.
- **Instanced islands.** Tested by a second temp module `gateinst`, also deleted. `ensureInstance`
  built slots at (0,0) and (256,0) with correct anchor translation and zone lookup. A released slot
  was reused. A restart re-resolved existing keys without rebuilding.

## Bugs found live and fixed (core)

| # | Bug | Fix |
|---|---|---|
| 1 | **Magic damage doubled for everyone**: `Stat.INTELLIGENCE.base` was 100, and the magic formula uses total Int | `Stat.INTELLIGENCE.base` = 0 (research 01: base 0, max mana = 100 + Int); `VitalsServiceImpl.maxMana` = 100 + Int + bonus |
| 2 | **Void death loop**: the overworld is pure air. With no hub, or with the hub's blocks not yet placed on the first boot, players were sent into the air and died every ~2 s for ever | `WorldService.isBuilt`. A limbo platform at the default world's spawn (7x7, y64) is created at boot. `sendHome` parks the player there while the hub builds and sends them on when it finishes. `warp` refuses an unbuilt island (admin `warpto` still allowed). Respawn never targets an unbuilt island |
| 3 | **Spawner never spawned** in a box drawn over the ground ("no valid spot"): it scanned only `minY..maxY` for the FLOOR block | The floor scan now covers `minY-1..maxY`. Draw areas as the space mobs stand in |
| 4 | **Mob camping a SAFE spawn plaza**: a pen zombie walked onto the spawn plaza and killed an AFK player about 12 times a minute | New dials: `mob.leash-range` (24; strays and SAFE/NO_MOBS intruders are returned to their area), `combat.safe-zone-protects` (mobs never target or hurt a player in a SAFE zone), `combat.respawn-grace-seconds` (3). Verified with 60 s AFK by the pen: 0 deaths |
| 5 | Warping while falling kept the fall distance (lost 43 HP on landing) | Every core teleport goes through `WorldServiceImpl.teleport`, which clears fall distance and velocity before and after |
| 6 | `/execute as <player> run warp ...` answered "Only players can use /warp" | The Brigadier adapter uses the EXECUTOR when it is a player |
| 7 | `/sb ledger` over RCON could not read the database ("a player can run...") | Console/RCON waits (5 s max) for the DB read |
| 8 | `/sb commands dump` wrote into `server/plugins/SkyClone/` instead of `docs/` | Path normalised. `docs/COMMANDS.md` is regenerated |
| 9 | Boot summary listed the 35 unwritten modules as "35 failed", which would hide real failures | `report.missing`: one INFO `module X not present` line each. Real failures are SEVERE `FAILED` lines. `/sb info` shows both |

## API changes (all ADDITIVE except one constant value; nothing renamed or removed)

| API | Change | Asked by |
|---|---|---|
| `stat.Stat.INTELLIGENCE` | base 100 -> **0** (constant value, documented) | items, combat |
| `player.VitalsService` | `damage(Player, double, String)`; `actionBarSegment(String id, int priority, Function<Player,String>)`; javadoc: max mana = 100 + Int | items, combat |
| `world.WorldService` | `isBuilt(String)`; `record WarpInfo(...)` + `warpInfos()`; `instanceAnchor(island, key, anchor)` | world + gate |
| `profile.ProfileService` | `membersAsync(String)`; `isReady(Player)` | foundation |
| `economy.EconomyService` | `depositOffline(String profileId, UUID, double, String)`: pays into that profile (falls back to the active one if the player left it) | foundation |
| `event.SkyEvents` | `CurrencyChange(player, currency "purse"/"bank"/"bits", delta, after, reason)`; `IslandBuilt(islandId, instanceKey)`; `SkyDeath.respawnAt` (mutable Location) | foundation, world, items |
| `config.Dials.Dial` | `default void onChange(Consumer<T>)` | foundation |
| `command.CommandService` | `run(Player, String name, String... args)`: a registered command through the same guards | foundation, ui |
| `ability.AbilityService` | `interface CostModifier` + `addCostModifier(String, CostModifier)` | items |
| `mob.MobService` | `setAllies(LivingEntity, Predicate<Player>)` (allies may fight it; loot still goes to the owner) | combat |
| `mob.MobDef` | fields `magicResistance`, `damageCap` (the `extra` keys still work as a fallback) | combat |
| `combat.CombatService` | `playerAttack(..., double multiplier)` | combat |
| `npc.NpcService` | `spawnAt(npcId, key, Location)`, `despawn(npcId, key)`, `talking(Player, npcId)` | ui |
| `ui.Menu` | `keepFreeSlotItems()` / `keepsFreeSlotItems()`: core will not hand free-slot items back (storage menus) | ui |
| `ui.SidebarService` | `scoreboard(Player)`: register Teams here | ui |
| javadoc only | `Features.SkyMenu` is provided by **menus**; `ItemDef.tags` lists the tags core acts on; `CalendarEvent` documents month 0 and the boot-time CalendarStart | ui, items, foundation |

**Not done** (needs design, not a gate patch):
- Per-instance seeds/layouts (`ensureInstance(island, key, seed)`). Dungeons should place their rooms themselves for now.
- A close reason for `onClose`.
- `ProfileService.createProfile(Player, mode)` (Ironman/Stranded/Bingo).

## Integrator notes
- `core.ModuleList` is back to the 35 planned ids (no test entries).
- `server/server.properties` is `online-mode=true` again. GateBot was de-opped. Its test profile
  (purse ~30, a `coretest` data key) is still in `skyclone.db`. It is harmless and nothing reads it.
- The server is **stopped**.

## Still unproven live (risks)
1. **The virtual sign editor** for `menus.input()`. The chat fallback is always on (dial `ui.input-sign`).
2. **Free-slot menus, shift-click into menus, drags.** The core logic is unit-tested and the click
   path is proven live, but storage-style menus have never been driven by a player.
3. **THE_END worlds** (dragon-fight suppression) and **NETHER** environments: never created.
4. **Knockback** from sky mobs and on sky mobs: present in play (`combat.mob-knockback`), not tuned.
5. **Performance at scale**: the hub is about 600x600 (tens of millions of blocks). The build budget
   is 12 ms per tick, but a hub-sized build has not been timed. The first boot also computes every
   island layout synchronously (`world.boot-layouts`).
6. `/give` and other code that writes straight into an inventory leave vanilla stacks unconverted
   until the next inventory click, close or join. `count`/`remove` accept both forms.
