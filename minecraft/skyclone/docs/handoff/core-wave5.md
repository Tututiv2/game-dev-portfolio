# core-wave5 — hand-off

Additive API + core changes made before the wave-5 module agents start. Nothing was renamed or removed.
Every new interface method is `default`, so existing implementations, lambdas (`Features.Equipment` is provided
as `p -> bags.worn(p)`) and the no-op proxies keep working.

## What works (verified how)
- `bash tools/check.sh all` -> `CHECK OK: all (914 files)`.
- `cd plugin && ./gradlew.bat build` -> BUILD SUCCESSFUL, 741 unit tests (one, `MenusPureTest.everyStatOnExactlyOnePage`,
  needed the two new stats on the stats pages; done).
- `tools/gen/skills/gen_skills.py` re-run: the 11 existing skills came out byte-identical (compared as JSON),
  `stone_unlocks.json` unchanged, HUNTING added (50 levels, Hunting Fortune +1/level, perk 0.04 -> 2.0).
- 8 new live self-tests (below). The server was NOT started (lane rule); the integrator runs them.

## 1. Hunting skill, stats, Foraging 57
- `SkillType.HUNTING("Hunting", 50, Stat.HUNTING_WISDOM, cosmetic=true, icon "LEAD")`, appended LAST (no ordinal shift).
  Cosmetic = does not count toward skill average, so adding it lowers nobody's average. // TUNE: flip if the
  reference counts it.
- `Stat.HUNTING_FORTUNE` ("Hunting Fortune", `&d☘`) in the gathering block, `Stat.HUNTING_WISDOM` in the wisdom block.
  `Stat.parse("Hunting Fortune")` works. Both are on the menus stat pages (`StatCategories`).
- `SkillType.FORAGING.maxLevel` 54 -> **57** (the data's max). Two places assumed 54 and were fixed:
  - `mod/collections/CollectionsService.applySkillCaps` computed `maxLevel + bonus`; it now computes
    `(maxLevel - every cap reward the catalogue can give) + bonus` = `57 - 3 + n`, the same numbers as before.
  - `mod/skills/SkillTable` javadoc.
- Skills module data: `SkillTable.load` throws if any enum value is missing from `data/skills/skills.json`, so
  HUNTING was added through the generator (`SKILLS`, `OURS["HUNTING"]`, `STAT_NAMES["Hunting Fortune"]`,
  `ACCESS["Critter Safari"] = None`). Perk renamed Charming -> **Snarewright** (`docs/names/skills.md`).
- Your Skills menu: HUNTING at slot 34 (next to Dungeoneering 33).
- **For the hunting agent:** grant XP with `Sky.service(SkillService.class).addXp(p, SkillType.HUNTING, xp, "hunting:<what>")`
  (Hunting Wisdom applies automatically). The perk value is `Charm Chance` %; nothing reads it yet — read
  `data/skills/skills.json` HUNTING `levels[L-1].perk` or ask skills for a reader. `skill:hunting:N` requirement keys
  already resolve (skills' resolver parses any SkillType).

## 2. New Features interfaces (`api.feature.Features`)
All methods are `default`; a no-op answers with the default body.

```java
// menus provides; returns def until then
boolean hide = Sky.service(Features.Settings.class).flag(p, "notify.event_start", true);
String scope  = Sky.service(Features.Settings.class).text(p, "notify.event_scope", "important");

// soulflow provides; consume is all-or-nothing
Features.Soulflow sf = Sky.service(Features.Soulflow.class);
if (!sf.consume(p, 1, "overflux")) { Msg.send(p, "&cYou are out of Soulflow!"); return; }
sf.add(p, 160, "soulflow_item");

// hunting provides
Features.Hunting h = Sky.service(Features.Hunting.class);
h.addShards(p, "SHARD_X", 1, "trap");
double lvl = h.attributeLevel(p, "fishing_speed");   // 0 when none

// extras provides
Sky.service(Features.Abiphone.class).addContact(p, "trevor");
if (!Sky.service(Features.Abiphone.class).hasContact(p, "trevor")) ...
```

Also added from the hand-off Requests (all defaults, owners implement when they like):

| Interface | Methods | Default | Request |
|---|---|---|---|
| `Features.Bank` | `openMenu`, `cookieActive`, `cookieMillisLeft`, `bitsMultiplier` | runs `/bank`; `cookie` requirement; 0; 1.0 | bank 5, menus 6, slayer 10 |
| `Features.Rift` | `motes`, `spendMotes`, `addMotes` | 0 / false / no-op | rift 6 |
| `Features.Mining.openMenu` | | runs `/mining` | mining 1, 14 |
| `Features.Garden.openMenu` | | runs `/desk` | garden 2 |
| `Features.Bestiary.openMenu`, `Museum.openMenu`, `SkyLevel.openMenu` | | `/bestiary`, `/museum`, `/sblevel`; **progression's real menus now override them** (`void openMenu` made `@Override public`) | progression 2 |
| `Features.Equipment.openMenu` | | runs `/equipment` | accessories 6 |
| `Features.Potions.openEffects` / `openBag` | | runs `/effects` / `/potionbag` | alchemy 2 |
| `Features.Museum.milestone` | | 0 | bank 6, auction 6 |
| `Features.Dungeons.masterMode` | | false | accessories 8 |
| `Features.Objectives.currentText` | | `current(p)` | menus 7 |
| `Features.SkyLevel.award` javadoc | says it prints `+N SkyBlock XP (reason)` unless the prefix is in `progression.sblevel.quiet-prefixes` | | progression 10 |

## 3. Per-target Magic Find — `LootService`
```java
// bestiary: +MF per family tier, only against that family
Sky.loot().addMagicFindModifier("bestiary", (p, def) -> def.family == null ? 0.0 : bonusFor(p, def.family));
// a module that rolls a mob's table itself
Sky.loot().roll(p, def.drops, LootSource.MOB_DROP, Sky.loot().magicFind(p, def));
```
Core's mob-death roll (`MobServiceImpl.die`) now uses `Sky.loot().magicFind(killer, def)` = `MAGIC_FIND` stat + the
sum of every modifier; still clamped by dial `loot.mf-cap`. A throwing modifier counts 0 (logged once); `null`
removes an id.

## 4. `SkyEvents.DungeonXpGain`
```java
// dungeons, Progress.addCataXp (first line after the xp <= 0 guard):
var ev = new SkyEvents.DungeonXpGain(p, "catacombs", selectedClass, floor, master, xp, "run");
Bukkit.getPluginManager().callEvent(ev);
xp = Math.max(0, ev.amount);
// addClassXp: track = c.name(), dungeonClass = c.name(); /sb dungeons xp passes source "admin"
```
Fields: `player`, `track` ("catacombs" or class id), `dungeonClass`, `floor` (0 = Entrance), `master`, `source`,
mutable `amount`; `catacombs()` helper. Accessories' `DungeonXpHook` binds `player/track/amount` by name and
turns green as soon as dungeons fires it (nothing else to change on its side).

## 5. Dupe-scan sources — `ItemService.addDupeScanSource`
```java
Sky.items().addDupeScanSource("storage", p -> storage.allStacks(p));   // backpacks + ender pages + wardrobe
```
Called on the main thread per scanned online player (join, periodic, admin). Stacks' unique uuids are checked
against the inventory, the vanilla ender chest and every other source. A copy INSIDE a source is logged to
`items_dupe_log` (`/sb dupes`) and announced to admins as `src:<id>#<index>` but never deleted by core; a copy in the
inventory is deleted by the usual rules even when the older one is in a source. A throwing source is skipped.

## 6. Other small hooks from the Requests (all clearly right, all additive)
- `ItemService.addNamePrefix(id, (def, data) -> "&7[Lvl " + lvl + "] ")` (pets 3). Drawn before the rarity colour:
  `&7[Lvl 23] &6Bee`. Must be pure/cheap; `null` removes.
- `AbilityService.consumeClick(p)` (enchanting 15): call from a NORMAL-or-lower priority interact handler right after
  cancelling; core's AbilityListener (HIGH) then skips the held item's ability for that tick (blocks and entities).
- Mana Disintegrator (reforges 11): core reads `ItemData.extra.mana_disintegrator_count` (the reference key) and cuts
  the ability's mana cost 1% each, max 10, before cost modifiers. Reforges can now add the anvil line.
- `MobService.setDisplayName(mob, name)` (combatzones 7): per-instance nameplate name (PDC `skyclone:mob_name`, so it
  survives chunk reloads); `null` restores. Level/health/colours stay core's.
- `Requirements.known(prefix)` (crafting 9): the impl already had it; now on the API, so crafting's reflection can go.

## Self-tests added
`api.skilltype-hunting`, `api.feature-noop-defaults`, `api.dungeon-xp-event-shape` (foundation);
`loot.magic-find-modifiers`, `mob.display-name-plate` (combat); `items.name-prefix`, `items.dupe-scan-sources`,
`abilities.mana-disintegrator` (items).

## Files touched
api: `skill/SkillType`, `stat/Stat`, `feature/Features`, `event/SkyEvents`, `loot/LootService`, `item/ItemService`,
`ability/AbilityService`, `mob/MobService`, `util/Requirements`.
core: `combat/LootServiceImpl`, `combat/MobServiceImpl`, `combat/CombatKeys`, `combat/CombatCommands`,
`items/ItemServiceImpl`, `items/LoreRenderer`, `items/DupeScanner`, `items/AbilityServiceImpl`, `items/AbilityListener`,
`items/ItemsSelfTests`, `foundation/RequirementsImpl`, `foundation/FoundationAdmin`.
modules (only where the API change required it): `collections/CollectionsService` (Foraging base cap),
`skills/SkillMenus` (Hunting slot), `skills/SkillTable` (javadoc), `progression/{Bestiary,Museum,SkyLevels}`
(`openMenu` now public @Override), `menus/StatCategories` (two stats).
data/tools/docs: `tools/gen/skills/gen_skills.py`, `data/skills/skills.json`, `docs/names/skills.md`,
`docs/CORE_GUIDE.md` (§4 + §8 wave 5), `CONTRACT.md` (feature list pointer).

## Not done (deliberately)
- Economy calls that join a caller's DB transaction (auction 4) — not small; needs a Connection-aware economy API.
- `LootService.sourcesOf(itemId)` (crafting 10), Vitality pool (enchanting 13), multi-value EnchantDef lore (enchanting 1),
  offline shared-profile reads (minions 9), co-op roles (island 8), `Features.Currencies` (shops 4) — larger designs.
- Firing `DungeonXpGain` is the dungeons agent's job (exact sites in section 4).

## Known risks
- HUNTING `cosmetic=true` is a guess; if the reference counts Hunting in skill average, flip it (averages drop for all).
- A new SkillType value must always be added to `data/skills/skills.json` too, or the skills module throws at boot.
- Name prefixes run on every render; a slow prefix slows every inventory re-render.
