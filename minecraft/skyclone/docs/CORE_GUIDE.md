# CORE GUIDE — how the core actually behaves

> Read this next to `CONTRACT.md`. CONTRACT says what the rules are; this says what the code DOES,
> measured on the live server on 2026-10-02 (a real 26.1.2 client joined, killed mobs, clicked an
> NPC, opened menus; profiles survived a restart). The API javadoc in `plugin/src/main/java/com/skyclone/api/`
> is still the contract. Per-area detail: `docs/handoff/core-*.md`. What the gate changed: `docs/handoff/core-gate.md`.

---

## 0. The fifteen things that will bite you

1. **Never touch per-player data before `SkyEvents.ProfileReady`.** `member()`/`shared()` before that (or for an NPC
   Player) returns a THROWAWAY object: your writes vanish, with one log line. Use `Sky.profiles().isReady(p)`.
2. **Never keep a `member()` object across ticks.** Re-fetch at use time (it is a map lookup). After quit it is evicted.
3. **Changing a data field's TYPE poisons that key** for the player: they get a fresh object, the stored JSON is
   kept, and NOTHING of that key is saved that session. Add fields; do not retype them.
4. **Damage modifiers run on EVERY hit**: mob->player, environment and PvP included. A "+X% damage" modifier must check
   `ctx.playerAttacker() != null`, or it also boosts the damage players TAKE.
5. **Area boxes are where mobs STAND** (feet space). The spawner looks for the floor from `minY-1` up to `maxY`, so a
   box at ground level+1 works, and a box with `y=0` never spawns anything. Give areas real y ranges.
6. **Layouts exist BEFORE blocks.** `layout()`/`anchor()` answer the moment the generator runs; blocks go in
   over the following seconds. Check `Sky.worlds().isBuilt(island)` or listen for `SkyEvents.IslandBuilt`.
7. **Instanced islands (private_island, garden, dungeon runs) are template-relative.** Use
   `Sky.worlds().instanceAnchor(island, key, anchor)`. `anchor(island, name)` gives slot 0's position.
   Core never places NPCs on instanced islands: use `Sky.npcs().spawnAt(npcId, key, location)`.
8. **Storage menus MUST call `menu.keepFreeSlotItems()`** and save their free slots in `onClose`. Without it, core
   hands every free-slot item back to the player on close, and if you also saved them that is a DUPE.
9. **Slot handlers run on the NEXT tick.** Do not expect the inventory to look the way it did at click time; read
   `click.current()` (a snapshot) and re-validate.
10. **Clicks on chests, workstations and similar blocks are denied at HIGH outside BUILD zones.** If you give such a
    block a meaning (the enchanting table, the brewing stand), listen at NORMAL or lower and open your own menu.
11. **RCON only shows what you print while the command runs.** Anything printed after a DB future completes is lost.
    Keep admin and self-test output synchronous.
12. **Sky mobs are not persistent.** A chunk unload removes them silently (no loot). Slayer and dungeon code must
    handle its boss vanishing (`isSkyMob(le)` / `le.isValid()`).
13. **Owned mobs only fight their owner** unless you call `Sky.mobs().setAllies(mob, predicate)`. Loot always goes to the owner.
14. **Unique items: never `clone()` one to give it away.** Same uuid = dupe, and the scanner deletes one. Use `create(id)`.
15. **Everything you give a player that they EARNED goes through `Sky.loot().give(...)`**, so collections and sacks see it.

---

## 1. Your workflow

| Step | Command | Notes |
|---|---|---|
| compile | `bash tools/check.sh <id>` | api + core + gen + your package only. A cross-module import fails here. Also fails on control characters in source |
| unit tests | put pure logic in a class with no Bukkit, test under `plugin/src/test/java/com/skyclone/mod/<id>/` | the integrator runs `gradlew build` |
| see an island | `bash tools/preview.sh com.skyclone.mod.<id>.<Gen> [seed] [crop=x1,z1,x2,z2]` | Read the PNGs (section 6) |
| live checks | self-tests: `Sky.commands().selfTest("<id>.<what>", () -> null-or-error)` | the integrator runs `/sb selftest <id>` over RCON. Tests run on the main thread at most `selftest.tick-budget-ms` per tick, but one test is never split: keep each one well under a second (over 1 s is named in the log) |

The integrator's live loop (so you know what your self-tests and admin commands are for):
```
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-server.ps1 -Detached -WaitReady   # builds, deploys, boots :25630
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\rcon.ps1 -Strip "sb selftest slayer"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\rcon.ps1 "stop"
```
Admin commands that already exist for testing your system: `/sb info`, `/sb selftest [filter]`, `/sb dial list|get|set|reset`,
`/sb give <p> <id> [n]`, `/sb items [filter]`, `/sb item info`, `/sb stats <p>`, `/sb heal`, `/sb mana`, `/sb coins|bits|bank <p> give|take|set`,
`/sb ledger <p> [n]`, `/sb profile <p>`, `/sb save`, `/sb requirement <p> <key>`, `/sb calendar [force <id> on|off|clear | skip <days>]`,
`/sb mob spawn|list|killall|info`, `/sb spawner list|pause`, `/sb damage <n> [mob|true|env]`, `/sb combat debug|modifiers|stats`,
`/sb island list|build|tp|anchors|zones|preview|info|instance|release`, `/sb whereami`, `/sb buildmode`, `/sb warpto <warp> [p]`,
`/sb npc list|tp|respawn|here`, `/sb ui demo|freeslots|confirm|input|number|sidebar|refresh|star`, `/sb commands dump`.
Full list: `docs/COMMANDS.md` (generated).

**Make your module testable over RCON:** an admin sub that takes a player name (`/sb slayer spawn <player> zombie 4`) is
testable without a client; one that only works for "the player who typed it" is not. `/execute as <player> run <cmd>`
also works for player commands.

---

## 2. A complete module, every registration in one place

```java
package com.skyclone.mod.example;

public final class ExampleModule implements GameModule, Listener {
    public static final class Data { public int kills; public Map<String, Integer> tiers = new HashMap<>(); }  // Gson-friendly
    private Dials.Dial<Double> bonus;

    @Override public String id() { return "example"; }

    @Override public void enable() {
        bonus = Sky.dials().number("example.bonus", 0.25, "Extra damage vs. example mobs (fraction)");
        Sky.items().loadJson("example", "items.json");          // data/example/items.json
        Sky.recipes().loadJson("example", "recipes.json");
        Sky.mobs().loadJson("example", "mobs.json");
        Sky.mobs().loadSpawnRules("example", "spawns.json");
        Sky.abilities().register("example_zap", use -> { /* ... */ return true; });   // true = charge mana + cooldown
        Sky.combat().addModifier("example_bonus", 400, ctx -> {
            if (ctx.playerAttacker() == null) return;                 // ONLY outgoing player hits
            MobDef d = ctx.victim instanceof LivingEntity le ? Sky.mobs().defOf(le) : null;
            if (d != null && "example".equals(d.familyId())) ctx.additive += bonus.get() * 100;
        });
        Sky.provide(Features.Collections.class, new MyCollections());   // only if you OWN that feature
        Sky.requirements().register("example", new MyResolver());       // only if you own the prefix
        Sky.npcs().register(NpcDef.of("example_guide", "&fGuide Ana", "hub", "npc.example_guide").title("&e&lCLICK"),
                p -> Sky.npcs().talk(p, "example_guide", List.of("&fHello!"), () -> openMenu(p)));
        Sky.sidebar().section("example", 300, p -> List.of("&eExample", " Kills: &a" + data(p).kills));
        Sky.commands().register(SkyCommand.of("example", "Open the example menu").category("General").playerOnly()
                .run((s, a) -> openMenu((Player) s)));
        Sky.commands().admin("example", "<player> <kills>", "Set example kills",
                (s, a) -> { Player t = Bukkit.getPlayerExact(a[0]); if (t != null) data(t).kills = Integer.parseInt(a[1]); });
        Sky.commands().selfTest("example.drops-exist", () -> /* null = pass */ null);
        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());
    }

    @Override public void postEnable() { /* cross-module wiring: other modules' services/items now exist */ }

    private Data data(Player p) { return Sky.profiles().member(p, "example", Data.class); }   // re-fetch every time

    @EventHandler public void onReady(SkyEvents.ProfileReady e) { /* per-player setup HERE, not on join */ }
    @EventHandler public void onKill(SkyEvents.MobKill e) { if (e.killer != null && Sky.profiles().isReady(e.killer)) data(e.killer).kills++; }
}
```
A missing module class logs one INFO line (`module X not present`). A module that throws from `enable()` is a SEVERE
`FAILED` line with the stack; the rest of the game still boots. Listeners it registered before throwing stay live.

---

## 3. Core services — real behaviour

### 3.1 Profiles — `Sky.profiles()`
- Load happens in `AsyncPlayerPreLoginEvent`. **If the DB read fails, the login is refused**; nobody is ever let in on empty data.
  `isLoaded` is true from join; `ProfileReady` fires the tick after join (`isReady` true from then on).
- Saving: every 10 s (`profiles.autosave-seconds`), only documents whose JSON changed, in one transaction; also on quit and
  at shutdown (BEFORE modules disable). Use `saveNow(p)` after something that must never be lost.
- Data class rules: public no-arg constructor; field initialisers are the defaults for missing JSON fields; enums are read
  case-insensitively and an unknown constant becomes `null`; map entries with removed enum keys are dropped.
- `shared(p, key, T)` is ONE object for all online co-op members.
- Offline: `modifyOffline(uuid, key, T, fn)` / `readOffline(...)` (verified across a restart). Online → the live object;
  offline → one DB transaction on the ACTIVE profile; a write that lands during their login is re-applied when they attach.
- `membersAsync(profileId)` works for offline profiles; `members()` only for loaded ones.
- Profile switching and co-op join/leave KICK the player with a friendly message; they rejoin into the new profile.
- Reserved member keys used by core: `vitals`, `world`. Don't use them.

### 3.2 Economy — `Sky.economy()`
- Stored as whole **tenths**. Credits round DOWN, debits round UP. Negative, NaN or >1e15 amounts are refused and logged.
- Every change is written straight through, with a ledger row in the same transaction. `/sb ledger <p>` shows it.
- `withdraw` returns false and takes nothing when the purse is short. `withdraw(0)` is true.
- Auctions/bazaar: store `Sky.profiles().profileId(seller)` WITH the listing and pay with
  `depositOffline(profileId, uuid, amount, reason)` → it lands on the profile that listed it, even if the seller switched.
- Events: `CoinsChange` (purse only), and `CurrencyChange` for purse, bank and bits (`currency` = "purse"/"bank"/"bits"),
  both on the main thread.
- Bank capacity defaults to `economy.bank-default-capacity` (50M) until the bank module calls `setBankCapacity`.
  `bankCredit` ignores capacity (interest/admin).
- Reason strings are audit data: `"bazaar: sell ENCHANTED_DIAMOND x64"`, not `"sell"`.

### 3.3 Database — `Sky.db()`
- One thread, FIFO; a read queued after a write sees it. Futures complete ON THE DB THREAD → hop back with
  `Bukkit.getScheduler().runTask(Sky.plugin(), ...)` before touching Bukkit.
- `migrate("example", 1, "CREATE TABLE example_x (...)", "CREATE INDEX ...")` in `enable()`: **one statement per
  string**, blocking, each version runs once, and a failure throws (so your enable fails loudly).
- `transaction(c -> ...)`: BEGIN IMMEDIATE; nested calls from inside DB work run inline as a SAVEPOINT (no deadlock).
- Logins wait on this same thread: keep your work short.
- Tables are prefixed with your module id.

### 3.4 Dials — `Sky.dials()`
```java
Dials.Dial<Integer> cap = Sky.dials().integer("example.cap", 64, "Max things per player");  // register in enable()
int c = cap.get();                       // read at USE time
cap.onChange(v -> reschedule(v));        // only if you must react (timers)
```
`dials.yml` holds only overrides. Values accept `2.5k`, `3m`, `1e3`. `/sb dial list example` shows value vs default.
Registering the same key twice with the same type returns the same dial. `server.display-name` is the server's name:
never hard-code "SkyClone".

### 3.5 Commands — `Sky.commands()`
- `register(SkyCommand)`: name `[a-z0-9_-]`, **first registration wins**, aliases already taken are dropped. Register in enable/postEnable.
- Executor exceptions are caught ("Something went wrong (logged)"). Non-admins are limited to 8 commands/s.
- `admin(sub, usage, desc, exec[, tab])` → `/sb <sub>` (permission `skyclone.admin`). `args` exclude the sub name.
- `run(player, "bz")` runs another module's registered command through the same guards. This is how a menu opens a
  feature you do not import. Never pass player-typed text as the name.
- Self-tests run on the main thread; a thrown exception is a FAIL. Output: `PASS id` / `FAIL id: reason`, then a summary.

### 3.6 Requirements — `Sky.requirements()`
Unknown prefix → FALSE (fail closed, logged once). `"a & b"` = all. Built in: `always`, `never[:text]`, `perm:<node>`,
`mode:<mode>`, `purse:<coins>`, `coop`, `visited:<island>` (world core). `checkOrTell` names only the failing parts:
`&cRequires &aCombat Skill 15&c!`. Resolver: `check(p, parts)` + `describe(parts)` (parts[0] is the prefix).

### 3.7 Calendar — `Sky.calendar()`
Date = wall clock + an epoch dial, so restarts never drift. 1 in-game day = 20 real minutes (`calendar.day-minutes`),
so one in-game hour is 50 s. `SkyDate(year, month 1-12, day, hour, minute)`, with `dayLabel()`, `timeLabel()` and
`isNight()` (19:00-06:00). Events: `CalendarEvent.onDate(id, name, month, day, durationDays)` (**month 0 = every month**)
or `realtime(id, name, intervalMin, offsetMin, durationMin)`. **On the first tick after boot every event already in
progress gets a `CalendarStart`**, so handle "started" idempotently. Test: `/sb calendar force <id> on`.

### 3.8 Items — `Sky.items()`
- Every vanilla `Material` already has a plain COMMON def (id = material name). A module def with the same id REPLACES
  it; any other duplicate id keeps the FIRST and logs the second.
- `loadJson` is lenient: a bad entry is logged and skipped; `"stats":{"DAMAGE":35}` becomes `damage`; unknown stat keys warn
  once; a bad material renders as BARRIER.
- A stackable stack carries ONLY `skyclone:id` (so it stacks with converted pickups). A unique item gets `uuid` + `created`,
  max stack 1, unbreakable, and **empty attribute modifiers** (no vanilla armour/damage numbers: stats are everything).
- Instance state lives in `ItemData`; use `extra` for your fields. `Sky.items().update(stack, d -> d.extra.addProperty("kills", 3))`
  writes and re-renders in place. `data()` is a copy. Your own PDC keys on the stack survive `update`/`refresh`.
- Vanilla → sky conversion happens on item spawn, on pickup, and on the next tick after any inventory click, drag, close
  or join. Code that writes straight into an inventory (vanilla `/give`) stays vanilla until then; `count`/`remove`
  accept both forms. Content items (maps, books, potions, shulkers, ...) are never converted.
- `vanillaId(Material)`: a def tagged `vanilla`, else the generated alias (`OAK_LOG`→`LOG`, `COCOA_BEANS`→`INK_SACK:3`, ...)
  if a module registered it, else the material name.
- Tags core ACTS on: `vanilla`, `placeable`, `consumable`, `throwable`, `usable`, `glow`, `reforgeable`, `no_reforge`, `soulbound`.
  Vanilla guards block placing, eating, throwing, crafting, smelting, anvils and enchanting tables for custom items unless the tag allows it.
- Anti-dupe: every 30 s and on join. The same uuid twice in ONE player's inventory/ender chest → the newer copy is deleted;
  the same uuid across players → logged + admins pinged.
- Lore contributors and stat contributors must be PURE functions of (def, data). Stackable renders are cached.
  After something lore depends on changes, `refresh(stack)` or `/sb item refresh all`.

**Lore format** (verified in the client tooltip):
```
&5Gate Tester                              <rarity colour><Reforge >Name[ stars]
&7Damage: &c+120                           stat lines: &c for offensive stats, &a otherwise; (+x) parts coloured by source
&7Strength: &c+75
&7Crit Damage: &c+30%
&7Intelligence: &a+50

&7<def.lore lines>

&6Ability: Test Zap &e&lRIGHT CLICK        ability lore: %d or {damage} = AbilityDef.damage, with commas
&7Deal &c1,000 &7damage to the mob ...
&8Mana Cost: &350                          mana in (0,1) = "% of max"
&8Cooldown: &a1s

&8This item can be reforged!
&5&lEPIC SWORD                             recombobulated: &d&l&ka&r &d&lMYTHIC SWORD &d&l&ka
```
Requirements always show in the footer (`&4❣ &cRequires ...`); lore is not per-viewer.

### 3.9 Recipes — `Sky.recipes()`
Cells are `"ID:amount"`. Ids may contain ':' (`INK_SACK:3` is cocoa beans): a cell that IS a registered id means x1,
otherwise the part after the LAST ':' is the amount → `"INK_SACK:3:16"`. `"COINS:1000"` is coins. Duplicate recipe
ids keep the first. `producing(id)` / `using(id)` / `byType` / `unlocked(p, r)` (null unlock = true).
Self-test `recipes.ingredients-exist` fails on any unknown id in any recipe.

### 3.10 Stats — `Sky.stats()`
- Providers run in order: `armor`, `held`, `equipment`, then modules' (`addProvider(id, (p, map) -> map.add(Stat.X, v))`;
  also `addPercent`, `multiply`). Recomputed every 10 ticks and the tick after `invalidate(p)` (call it when your bonus changes).
- `held` counts the main hand only for weapons/tools/rods/wands; `armor` only for armour categories whose requirements are met.
- Base values: Health 100, Intelligence **0**, Crit Chance 30, Crit Damage 50, Speed 100, Swing Range 3, Health Regen 100 ...
  (`Stat.base`). Speed drives the movement attribute (cap 400); Swing Range drives interaction range; attack cooldown is off.
- `/sb stats <p>` prints every stat with its per-provider breakdown.

### 3.11 Vitals — `Sky.vitals()`
- Health/mana/absorption live in member data `vitals`. Max HP = HEALTH; **max mana = 100 + Intelligence** (+ MANA_POOL_BONUS).
- Hearts are a gauge (20 half-hearts below 125 HP, growing to 40). Hunger is pinned full; vanilla regen is off.
- Regen: health every 2 s by `(1.5 + max/100) × HealthRegen/100`; mana 2% of max per second × (1 + ManaRegen/100).
- Action bar (every 5 ticks): `&c1,234/1,500❤     &a612❈ Defense     &b850/950✎ Mana`; `flash(p, text, ticks)` replaces the
  middle; `actionBarSegment(id, priority, p -> text)` appends after mana (drill fuel, Rift time, secrets). Blank hides it.
- `damage(p, amount, cause)` = absorption → health → kill (no defense). `kill(p, cause)` = genre death: lose half the purse (whole coins, rounded down - also after SkyDeath listeners scale it)
  (not in SAFE zones or on the private island), `SkyDeath` fires (mutable `coinsLost`, `respawnAt`), instant respawn with
  full HP/mana at the island's `spawn` (private island → `PrivateIsland.home`; another instanced island → the spawn of the slot you died in; the default world, an unbuilt island or a spawn with no floor → home, i.e. private island, else hub spawn, else limbo + queued for the hub). No coin loss outside game worlds. Mobs cannot hurt
  a freshly respawned player for `combat.respawn-grace-seconds` (3).

### 3.12 Abilities — `Sky.abilities()`
Triggers on the MAIN-hand sky item: RIGHT_CLICK, SNEAK_RIGHT_CLICK (falls back to RIGHT_CLICK), LEFT_CLICK, SNEAK; at most
one per player per tick; NPC clicks never trigger. Order: handler registered? → requirements → cooldown (`&cThis ability is on
cooldown for 3s.`) → mana (`&cYou do not have enough mana!`) → handler. Return `true` and core charges the mana, starts the
cooldown, flashes `&b-50 Mana (&6Name&b)` and fires `AbilityUsed`; return `false` and nothing is charged.
PASSIVE/ON_HIT/ON_KILL are lore only: you listen to the events yourself. Mana discounts: `addCostModifier(id, (use, cost) -> cost * 0.9)`.
`registerSetTick(setId, minPieces, p -> ...)` runs every 20 ticks.

### 3.13 Combat — `Sky.combat()`
```
melee/bow : (5 + weapon + flatBonus) × (1 + Str/100) × (1 + additive/100) × multiplicative × (crit ? 1 + CD/100 : 1)
magic     : base × (1 + Int/100 × scaling) × (1 + AbilityDamage/100) × (1 + additive/100) × multiplicative   (never crits)
mob side  : × (1 - magicResistance) → boss cap T + 0.1×(dmg - T) → × 100/(100+defense) → floor
to player : (base + flat) × (1 + additive/100) × mult × 100/(100 + Defense)   (TRUE: True Defense)
```
Verified live: 1000 base magic, scaling 1, Int 0 → **1000**; Int 50 → **1500**.
- Modifier priorities: 100 enchants, 200 sets, 300 pets, 400 slayer/dungeon/mayor, 900 final. Modifiers mutate INPUTS;
  `ctx.crit` is rolled before them (you may read or force it); `finalDamage` is computed after. To clamp the FINAL number
  use `MobBehaviour.onDamaged` or `SkyEvents.Damage` (cancellable).
- Ferocity: extra strikes 0.2 s apart (cap 500). Each is its own `Damage` event with kind FEROCITY and tag `ferocity`.
- `playerAttack(p, victim, weapon, kind[, multiplier])` from code skips the per-attacker hit-rate limit, so abilities may multi-hit.
  `magicDamage(p, victim, base, scaling, name)`, `rawDamage` (no formula, still fires events), `damagePlayer` (defense applies).
- Vanilla hits on sky mobs are zeroed and routed through the pipeline; vanilla invulnerability on sky mobs is off, and each
  player is limited per mob (10 ticks ÷ attack speed). PvP only when BOTH players stand in a `PVP` zone.
- Environment damage to players = % of max HP (dials `combat.*-percent`, all TUNE); the void kills.
- `/sb combat debug` prints a breakdown of every hit you deal.

### 3.14 Mobs & spawning — `Sky.mobs()`
- `MobDef` JSON (see the api javadoc). Flags: `boss`, `aggressive`, `passive`, `no_knockback`, `no_ai`, `baby`, `silent`,
  `burns`, `sea_creature`, `fire_immune`. `magicResistance` (0..1) and `damageCap` are fields now.
- Spawned mobs: custom HP tracked by core (the vanilla health bar is a fixed 1024 gauge), nameplate
  `&8[&7Lv5&8] &cTest Zombie &a500&f/&a500&c❤` (k/M/B above 100,000; bosses `&c☠ &4Name &a50M&c❤`), not persistent.
- `spawn(id, at[, owner])` throws for an unknown id and returns null if a listener refused. `setMaxHealth` on a full mob keeps
  it full. `setHealth(mob, 0)` = a kill WITH loot; `remove(mob)` = silent.
- Death order: `onDeath` → `MobKill` (mutable `coins`, `combatXp`; killer = last player hit within 15 s) → coins paid
  (`mob_kill:<id>` in the ledger, private `+X coins` float) → loot roll with the killer's Magic Find. Core does NOT grant
  combat XP; skills reads `MobKill.combatXp`.
- **Spawn rules** (`SpawnRule` JSON or `addSpawnRule`): `island` + `area` (box) or `anchors` (names; `"spawn.zombie.*"` =
  prefix). Active only while world `sb_<island>` is loaded AND a player is within `activationRange` (64). Keeps
  `max - live - pendingRespawns` topped up (≤4 per rule per second); a death starts `respawnSeconds`. Spots need a solid
  floor and 2 free blocks above and are never inside SAFE/NO_MOBS zones. `condition`: `night`, `day`, `event:<id>`, with `!` to negate.
- **Leash**: rule-spawned mobs further than `mob.leash-range` (24) outside their area, or standing in a SAFE/NO_MOBS zone,
  are put back on a spot with their target cleared. Mobs never target or hurt a player inside a SAFE zone.
- `/sb spawner list` shows each rule's status: `ok`, `no player near`, `no valid spot`, `world not loaded`, `no area/anchors in layout`.

### 3.15 Loot — `Sky.loot()`
`give(p, stack|id, n, source)` → `ItemGain` (cancellable = a sack took it) → inventory (merges) → overflow to the
**item stash** (profile member key `core_item_stash`; players empty it with `/pickupstash`, browse with `/viewstash`, get a
clickable reminder on login; admins `/sb stash <player> [clear]`). Rewards/trades/auction/bazaar/NPC-shop/admin items and
unique items are named in chat and saved at once — a burst of them (a kit, a DA lot) is ONE line a tick later
("17 items went to your item stash: A, B, C and 14 more", full list on hover; at most one such line per 2 s). Only a FULL stash (`loot.stash-max-entries`, 720 kinds) — or
`loot.stash-enabled false` — drops overflow at the feet, owned by them for 60 s. An offline player's items go to their
active profile's stash. Pickup is refused in the Rift (its own inventory). `give` still returns the amount that hit the
FLOOR, so it is 0 when the stash took it. Core's own menu free-slot returns (`UiUtil.give`) use the stash too.
Pickup (and the `/viewstash` list) is NOT oldest-first: rewards/uniques first, then rarer, then smaller piles
(`StashData.pickupOrder`), so a reward stashed mid-grind comes back before the 4,000 wheat around it. Bulk overflow is
said in chat once per episode (until the next pickup) and re-said every `loot.stash-reminder-seconds` (300) with the
total; in between it is an action-bar flash. A module that counts a currency item in the inventory (tickets, medals)
will not see it while it sits in the stash. `roll(p, drops, source, magicFind)`: Magic Find only
on drops under 5%, capped at 900; each unique item is created separately (own uuid); `RARE DROP!` messages by label.
`dropFor(p, at, stack)`: an item only that player can see or pick up. `LootSource.natural` decides whether collections count it.

### 3.16 Worlds — `Sky.worlds()`
- Each shared island is a void world `sb_<id>` (no natural mobs, no weather or daylight cycle, keepInventory; time follows
  the calendar unless `fixedTime`). Register in `enable()`: `registerIsland(IslandDef.of(id, name, generator))` +
  `registerWarp(warpId, island, "spawn", "Display Name", requirementOrNull)`.
- Builds: on boot, any island whose generator `version()` went up is generated off-thread, then placed at 12 ms/tick
  (verified: 32,506 blocks in 1 s using 124 ms of tick time). **Bump `version()` to rebuild.** A rebuild first clears old
  blocks the new build does not write. `/sb island build <id> force` rebuilds now. Layouts persist in
  `plugins/SkyClone/islands/<id>.json`; a restart does not rebuild.
- `isBuilt(id)`: blocks are in place. `IslandBuilt(islandId, instanceKey)` fires when placement finishes.
- Zones: smallest box wins. Flags `BUILD`, `PVP`, `SAFE`, `NO_MOBS`, `NO_FALL`. First entry shows `NEW AREA DISCOVERED!`;
  a zone `requirement` pushes the player back. `ZoneChange`/`IslandChange` events; `zoneOf(p)` is cached (10 ticks).
- Portals: an area named `portal.<warpId>` warps whoever walks into it (3 s cooldown). It must be a registered warp
  (self-test `world.portals-have-warps`).
- Warps check the island's and the warp's requirement, refuse an island that is not built yet, and teleport to the anchor.
  `/warp` lists them; the Fast Travel menu reads `warpInfos()`.
- `sendHome(p)`: private island home, else the hub spawn, else **limbo** (a 7×7 platform at the void overworld's spawn,
  y64). While the hub is still building, joiners wait in limbo and are sent on when it finishes. First join → home;
  later joins → home unless `world.join-location = last`.
- **Instanced islands** (`def.instanced = true`, `instanceSpacing`): one world, slots on a spiral grid (slot 0 at 0,0, then
  spacing apart). `ensureInstance(island, key)` builds once per key and completes with the slot's spawn; keys persist in
  `world_instances`, so a restart never rebuilds them (verified). `releaseInstance` frees a slot for reuse.
  `instanceAnchor(island, key, anchor)` gives world positions; zones and portals translate automatically. All instances
  share the island's base seed and one template layout.
- Block rules (game worlds only): breaking is cancelled outside BUILD and then offered to break handlers by ascending
  `priority()`; the first whose `handles()` is true gets `onBreak` (the event is already cancelled: you drop and regenerate).
  Placing, buckets, fire, leaf decay, crop trampling and so on are blocked outside BUILD. `/sb buildmode` bypasses all of it.
- **Placed blocks**: every block a player places in a game world is marked (chunk data, no cap, follows pistons and
  falling blocks; cleared when broken or when something natural takes the spot). `playerPlaced(block)` answers it, and
  stays true through every BlockBreakEvent listener of the break itself — use it so place-and-break pays no XP.
- **Gathering in BUILD zones** (islands in `world.gather-islands`, default `private_island`): a hand break of a block a
  player did NOT place gives its drops through `Sky.loot().give` with a natural source (logs FORAGING, crops FARMING,
  else MINING) — straight to the inventory, so collections and sacks count them. Ripe crops always count, unripe never;
  placed blocks keep plain vanilla drops. Skill XP there is the skill module's job (foraging/farming do it); never give
  the base drop again or the collection doubles.
- All core teleports clear fall distance (warping mid-fall used to cost HP).

### 3.17 NPCs — `Sky.npcs()`
- Register in `enable()`. Body = Mannequin (skin from `def.skin`, base64 texture) or any `EntityType`; invulnerable, no AI,
  not persistent. Holograms (top to bottom): title, lines, name. The body turns to face the nearest player within 8 blocks.
- Spawned only when `sb_<island>` is loaded and the anchor's chunk is loaded (a watchdog respawns every 2 s). A missing
  anchor places it near `spawn` with one log line (self-test `ui.npc-anchors` fails).
- Click (right, left or hit) → `onClick(player)` on the next tick, debounced 500 ms, ignored while `talk()` runs for that player.
  `talk(p, id, lines, then)`: `[NPC] Name: line` every 1.2 s, then `then` (verified live).
- Instanced islands: `spawnAt(npcId, key, location)` / `despawn(npcId, key)`. Not persisted: re-place on ProfileReady/IslandBuilt.
- `entity(id)` is null whenever the chunk is unloaded. Never cache it.
- **One name, one person** (2026-10-04): no personal name word may belong to two different NPCs, mayors, garden
  visitors, contest ghosts or puzzle strangers ("go to Bram" must mean one person on the AI show). Job words
  (`Smith`, `Ranger`, `Keeper`...) are ignored; a mirror of the same character keeps the SAME full name. Guarded by
  `NpcNamesTest` (build) and self-test `ui.npc-names-unique` (live). Rules + word lists: `core/ui/NpcNames.java`;
  history: `docs/handoff/names.md`.

### 3.18 Menus — `Sky.menus()` / `new Menu(...)`
```java
Menu m = new Menu("Your Pets", 6).chrome();                      // black glass + Close at 49
m.back("Your Pets", () -> buildPetsMenu(p));                    // Go Back at 48, to a Menu you build
// back to the SkyBlock Menu (SkyMenu.open is void, so use set() on the back slot):
m.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
m.set(13, Icons.of(Material.BONE, "&aPet", "&7Level 5", "", "&eClick to summon!"), c -> summon(c.player()));
m.open(p);
Sky.menus().paged("Recipes", list, r -> icon(r), pc -> open(pc.click().player(), pc.entry()), page -> page.back(...), 0);
Sky.menus().confirm(p, "Buy?", List.of("&7Costs &65,000"), () -> buy(p), null);   // Esc does NOT call onNo
Sky.menus().inputNumber(p, "Amount?", n -> { if (n != null) ... });               // "64", "1.5k", "2m"; validate yourself
```
- Every click is cancelled except in `free(...)` slots. Number keys, double-click collect and drags are handled.
  Handlers run next tick and only if the same menu is still open. Exceptions are caught and logged.
- `onClose` runs first; then core returns every free-slot item to the player (crafting grids and sell slots are safe by
  default). Storage menus: `keepFreeSlotItems()` + save in `onClose`. `onClose` also runs when navigating to another menu,
  so never open a menu from `onClose`.
- One Menu instance per viewer. `refreshEvery(ticks)` + `drawer(menu -> ...)` for live menus.
- `input()` opens a sign editor AND listens to chat (whichever answers first; times out after 30 s; colour codes stripped).

### 3.19 Sidebar, tab, chat, star
- Sidebar frame (10 lines): title, date + shard, day, time, `⏣ zone`, purse, bits, footer. **Only 5 lines are left for ALL
  module sections, and each section costs 1 + its lines** (a blank separator). Sections go in priority order (lower first);
  one that does not fit is skipped. Keep yours to 1-3 lines and return `List.of()` when irrelevant.
- Each player has their OWN scoreboard. Teams go on `Sky.sidebar().scoreboard(p)`. Never `p.setScoreboard`.
- Tab widgets: `tabWidget(id, priority >= 100, p -> lines)`. Chat: `&7[<level>] &7Name&f: msg` (level from `Features.SkyLevel`).
- The SkyBlock Menu nether star is locked in hotbar slot 9 and opens `Features.SkyMenu` (the **menus** module provides it).
  To borrow slot 9 (dungeon class item), set the player PDC key `skyclone:no_sbmenu`.

### 3.20 Events (all on the main thread)
| Event | Fired by | Notes |
|---|---|---|
| `ProfileReady(player, firstJoin)` / `ProfileUnload` | core | set up / tear down per-player state |
| `MobKill(killer?, mob, def, location)` | core | mutable `coins`, `combatXp` |
| `ItemGain(player, itemId, amount, stack, source)` | `loot.give` | cancel = absorbed (sack); collections still count it |
| `Damage(ctx)` | core combat | after modifiers, before applying; cancellable; may set `ctx.finalDamage` |
| `SkyDeath(player, cause, coinsLost)` | core vitals | mutable `coinsLost`, `respawnAt` |
| `CoinsChange` / `CurrencyChange` | core economy | CurrencyChange covers purse, bank and bits |
| `ZoneChange` / `IslandChange` | core worlds | `to` null = left all zones |
| `IslandBuilt(islandId, instanceKey?)` | core worlds | the blocks are in place |
| `CalendarStart` / `CalendarEnd` | core calendar | Start also fires at boot for events in progress |
| `AbilityUsed` | core abilities | after mana is charged |
| `Gather`, `SkillXpGain`, `SkillLevelUp`, `CollectionGain`, `CollectionTierUp`, `FishCaught` | their modules | |

---

## 4. Feature services
`Sky.service(Features.X.class)` NEVER returns null: if the owner is absent you get a no-op returning 0/false/null/empty.
Resolve at CALL time, not in `enable()`. Handle `null` returns (e.g. `PrivateIsland.home`). If you own one,
`Sky.provide(Features.X.class, impl)` in `enable()`. `Sky.has(X.class)` tells you whether a real one is installed.
A no-op answers a DEFAULT method by running its body, so interfaces whose methods are all `default` (Settings,
Soulflow, Hunting, Abiphone, Bank, Rift, Essence, Party) answer with sensible values with no owner: `Settings.flag(p,
key, def)` returns `def`, `Bank.cookieActive` falls back to the `cookie` requirement, `Bank.bitsMultiplier` is 1.0.

---

## 5. The gen toolkit (`com.skyclone.gen`, pure Java, no Bukkit)

A generator writes into a `VoxelCanvas` and describes itself in a `Layout`. The SAME code runs in preview and in game,
and the default seed is identical, so **the preview IS what gets built**. Shape to copy:
`com.skyclone.gen.sample.SampleIslandGenerator` (heightmap → flatten plots → fill → carve → reserve → build → paths → trees → anchors).

| Class | One-line example |
|---|---|
| `Terrain` | `var s = new Terrain.IslandShape(seed); s.radiusX = 60; s.radiusZ = 50; s.baseY = 70; s.hills = 6; s.cliff = 0.5; var h = Terrain.island(s);` |
| `Terrain.Heightmap` | `int y = h.average(plot); h.flatten(plot, y, 6);` **before** `Terrain.fill(c, h, new Terrain.SurfaceRules());` |
| `Terrain` extras | `Terrain.pond(c, h, x, z, radius, depth, seed)` · `Terrain.carveCaves(c, h, seed, 1.0, 3, 6, 4)` · `Terrain.grassAndFlowers(c, h, seed, 0.2, 0.03)` · `Terrain.fillBasin(...)` refuses to leak |
| `Palette` / `Fill` | `Palette.of("stone_bricks:6,mossy_stone_bricks:2,cracked_stone_bricks:1").at(x, y, z)` (deterministic per position) · `Fill.of("stone")` |
| `Blocks` | `Blocks.stairs("oak", "north")` (facing = the direction you walk to go UP) · `Blocks.slab("stone", "bottom")` · `Blocks.log("oak_log", 'x')` · `Blocks.door(c, x, y, z, "oak", "south", "left", false)` · `Blocks.lantern(true)` |
| `Shapes` | `Shapes.box(c, box, fill)` · `Shapes.hollowBox` · `Shapes.cylinder(c, cx, y0, cz, r, h, fill, shell)` · `Shapes.dome` · `Shapes.sphere` · `Shapes.arch(...)` · `Shapes.prism(c, poly, y0, y1, fill, hollow)` · `Shapes.line(...)` |
| `Reservations` | `var res = new Reservations(); if (res.reserve("bank", house.reserve())) house.build(c);` (an overlap is refused; `layout.data("reservations", res.summary())`) |
| `Builders.House` | `var b = new Builders.House(x, y, z, 9, 7); b.floors = 2; b.door = "south"; b.roof = "dark_oak"; b.roofStyle = "hip"; b.build(c);` → `b.doorStep`, `b.inside`, `b.peakY` |
| `Builders.Tower` | `var t = new Builders.Tower(cx, y, cz); t.height = 14; t.top = "battlements"; t.build(c);` → `t.platform` (walkable top) |
| `Builders` small | `marketStall(c, x, y, z, "south", "red", "white", seed)` → vendor/customer spots · `fountain` · `well` · `lampPost(c, x, y, z, "street", "south")` (styles post/street/torch/soul) · `bench` · `crates` · `campfire` · `banner` · `gate` · `fenceLine(c, points, "oak_fence", closed)` |
| `Builders.path` | `var pr = Builders.path(c, new int[][]{{x1,z1},{x2,z2}}, 3, Palette.of("dirt_path:6,gravel:1"), "cobblestone_stairs", null);` follows the terrain, adds steps, never paves buildings; `pr.near(x, z, 2)` for keep-outs |
| `Builders.bridge` | `bridge(c, x1, y1, z1, x2, y2, z2, 3, deck, "oak_fence", "oak_log", "oak_stairs")` |
| `Trees` | `Trees.grow(c, "oak", x, y, z, seed, 1.0)` · `Trees.forest(c, h, area, seed, 0.03, 5, "oak:4,birch:2", 3, (x, z) -> res.covers(x, z, 4) || pr.near(x, z, 3))` (keep-out is tested at the TRUNK: use a margin ≥ the canopy radius or canopies overhang roofs, as in the sample) |
| `Noise` / `Hash` | `new Noise(seed).fbm2(x / 48.0, z / 48.0, 4)` (divide by feature size) · `Hash.chance(seed, x, z, 0.1)` · `Hash.salt(seed, "rocks")` (order-independent randomness) |
| `Connect` | fences, panes, walls and bars connect automatically after generation: write plain `oak_fence` |
| canvases | `MemoryCanvas` (in memory, what preview and game use) · `OffsetCanvas(target, dx, dy, dz)` (build a piece at an offset) · `GuardCanvas(target, box)` (count out-of-bounds writes) |
| checkers | `IslandRun.run(gen, seed)` · `Walkability.analyse(canvas, layout, new Walkability.Options())` (usable from a self-test, see `world.sample-island-walkable`) |

Layout calls: `layout.standAt("npc.banker", x, groundY, z, yaw)` (pass the GROUND block; the anchor is centred on the block
above) · `layout.area("graveyard", box)` (feet-space y range!) · `layout.zone("village", "Village", 'b', box, null, Layout.Zone.SAFE)` ·
`layout.data("walk.ignore", "cam.,spawn.mob.")` (anchor prefixes the walk check skips).

Rules the checker enforces: every anchor and portal reachable from `spawn` (step ≤ 1.19, drop ≤ 4, 1.8 head room; doors
and gates count as open); no anchor inside a block (a slab counts: put NPCs ON a full block); nothing floating; every
block name valid for 26.1.2 (`resources/gen/known_blocks.txt`; renamed ids such as `chain` → `iron_chain` are flagged).
`bounds()` must contain every block you write: writes outside it are reported and NOT cleared on rebuild.

---

## 6. `tools/preview.sh` — how to read its output

```
bash tools/preview.sh com.skyclone.mod.hub.HubGenerator                 # default seed (same as in game)
bash tools/preview.sh com.skyclone.mod.hub.HubGenerator 42 crop=-60,-60,60,60
```
It compiles only what your generator references (half-written code elsewhere cannot block you; `--full` compiles all of
it). Output in `previews/<islandId>/` (Read them with the Read tool and LOOK):
- `top.png`: map, north up, 1 cell = 1 block, coordinate ticks every 16.
- `iso.png` / `iso_nw.png`: 3D views from the SE and NW (roofs, overhangs, things hidden behind buildings).
- `walk.png`: **green** = reachable from spawn, **orange** = standable but cut off (rooftops and fence tops are fine,
  plazas are not), **red X** = a failed anchor (the label says why), **magenta** = portal areas, rectangles = zones.
- `crop_*.png` (with `crop=`): a zoomed district. On a 600×600 hub the whole-island iso is only 2 px per block.
- `report.txt`: the layout (anchors, areas, zones), walkability per anchor (`ok`, `NEAR`, `UNREACHABLE`, `SOLID` = inside
  a block), cut-off regions largest first, floating components (`SMALL` ≤ 8 blocks is almost always a bug), unknown blocks,
  out-of-bounds writes, reservations.

Exit code: **0** OK · **1** spawn missing / an anchor or portal unreachable or inside a block · **2** compile error or crash.
Zero red X before you hand off. The sample took 0.6 s; a 620×620 island takes a few seconds (heap: `PREVIEW_XMX`, default 6g).

---

## 7. Known limitations (as of the gate)
- **Instances share one seed and one template layout.** Per-run variety (dungeons) has to place rooms itself (e.g.
  `ensureInstance` + your own placement through a BuildJob-like loop, or a `MobService`/block pass you time-budget yourself).
- **Not tested live:** the sign-editor input (chat fallback works), storage/free-slot menus driven by a real player,
  THE_END/NETHER island environments, hub-scale build timing, knockback tuning.
- Lore is not per-viewer: requirements always show.
- No close reason on `onClose`; no API to create Ironman/Stranded/Bingo profiles.
- Environment damage percentages and the out-of-combat regen multiplier are TUNE dials, not research numbers.
- `/give` and other direct inventory writes stay vanilla until the next inventory click, close or join.
- Death in an instanced island other than the private island respawns at that world's spawn unless your module sets
  `SkyDeath.respawnAt`.

---

## 8. API additions after the gate (all additive, `default` methods; no-op proxies keep working)

| API | Added | Round |
|---|---|---|
| `Features.Collections.add(p, id, amount)` | collections implements it | integration 1 |
| `Features.Pets.abilityValue(p, key, token)` | PetService already had it | integration 1 |
| `CommandService.exists(name)` | true when a command or alias is registered through core | integration 2 |
| `CalendarService.millisRemaining(eventId)` | time left in the current occurrence (forced runs included), 0 when inactive | integration 2 |
| `SidebarService.setHidden(p, sectionId, hide)` / `hidden(p, sectionId)` | per-player section hiding, session only: the module owning the setting re-applies it on ProfileReady | integration 2 |
| `Features.SkyLevel.emblem(p)` | selected emblem as legacy text; core shows it after the name in chat and the tab list (progression implements it) | integration 2 |
| `ItemService.isAutoVanilla(id)` | true for a def core made itself for an unregistered vanilla material | integration 2 |
| `SkyEvents.FishCaught.treasure` / `.quality` | new 6-arg constructor; the old 4-arg one still compiles (treasure=false) | integration 2 |
| `Features.Essence` (`essence`, `takeEssence`, `addEssence`) | the reforges essence pouch; reforges provides it, the dungeon essence shop spends it | integration 3 |
| `Features.Party` (`members`, `leader`) | the dungeons party; dungeons provides it, kuudra's crew falls back to it | integration 3 |
| `StatMap.set` / `unset` / `override` / `overrideView` | per-player stat override: a provider REPLACES a stat's final value for the players it calls it for; later provider wins; `/sb stats` shows `<provider> (=)` | integration 4 |
| `MobDef.extra.size` / `extra.scale` | read by core at spawn: slime/magma cube size (before the health gauge, so no restore needed) and the SCALE attribute | integration 4 |
| `Features.Mining.heat(p)` / `cold(p)` | Crystal caverns Heat and Glacite Cold (0-100); mining implements them, reforges' Heated / Glacial read them | wave 3 integration |
| `Features.Dungeons.selectedClass(p)` | the selected class id (`HEALER`...`TANK`) or null; dungeons implements it (for accessories' Adaptive Belt) | wave 3 integration |

Behaviour changes in round 2: `CalendarMath.dateUntil` jumps straight to qualifying years (forced-only events with
`everyYears = 1,000,000` no longer cost a million iterations per call), and the death chat line reads
"killed by Graveyard Zombie" instead of the raw cause `mob:hub_graveyard_zombie` (the raw cause still goes to
`SkyDeath.cause` and the ledger).

Behaviour changes in round 3: the death line for a scripted hit with no attacker (cause `mob` / `true`) reads
"killed by a monster" / "struck down" instead of the bare kind.

Behaviour changes in round 4: every creature spawned in a game world (any reason but NATURAL) gets
`setRemoveWhenFarAway(false)`, so vanilla never despawns an untagged prop (dungeon puzzle mobs); `setPersistent` is not
touched. A slime that a behaviour resizes in `onSpawn` gets core's health gauge (and `speed` multiplier) put back.

Behaviour changes in wave 3 integration: environmental deaths read in words ("burned in lava", "fell", "drowned",
"fell into the void"...) instead of the raw damage group; a mob whose PDC holds `skyclone:runic` = 1 (skills' Runic
mobs) gets its name drawn in `&5` by core's nameplate renderer.

### Wave 5 (core-wave5; full notes and snippets in `docs/handoff/core-wave5.md`)

| API | What | Owner / reader |
|---|---|---|
| `SkillType.HUNTING` | "Hunting", max 50, wisdom `HUNTING_WISDOM`, cosmetic (not in skill average, // TUNE) | skills data has it; hunting grants the XP |
| `SkillType.FORAGING.maxLevel` | 57 (the data's absolute max; the starting cap stays the skills data's 50) | collections now adds its +1 caps to `57 - 3` |
| `Stat.HUNTING_WISDOM`, `Stat.HUNTING_FORTUNE` | new stats (on the stats pages) | hunting |
| `Features.Settings` (`flag`, `text`) | the player's Settings values, defaulting to `def` | menus provides |
| `Features.Soulflow` (`soulflow`, `consume`, `add`) | Soulflow fuel, all-or-nothing `consume` | soulflow provides |
| `Features.Hunting` (`shards`, `addShards`, `attributeLevel`) | shards and the attribute levels they buy | hunting provides |
| `Features.Abiphone` (`addContact`, `hasContact`) | Abiphone contacts | extras provides |
| `Features.Bank` (`openMenu`, `cookieActive`, `cookieMillisLeft`, `bitsMultiplier`) | bank + cookie buff | bank provides |
| `Features.Rift` (`motes`, `spendMotes`, `addMotes`) | Motes | rift provides |
| `openMenu(p)` defaults on `Mining`, `Garden`, `Bestiary`, `Museum`, `Equipment`, `SkyLevel`; `Potions.openEffects/openBag` | default runs the owner's command; progression's three now override | menus calls them |
| `Features.Museum.milestone(p)`, `Dungeons.masterMode(p)`, `Objectives.currentText(p)` | defaults 0 / false / `current(p)` | progression / dungeons implement |
| `SkyEvents.DungeonXpGain` | `player, track, dungeonClass, floor, master, source`, mutable `amount` | dungeons fires; accessories scales |
| `LootService.addMagicFindModifier(id, (p, def) -> mf)` / `magicFind(p, def)` | per-target MF summed into the stat when core rolls a mob's drops | bestiary, weapons, slayer |
| `ItemService.addDupeScanSource(id, p -> stacks)` | module containers join the anti-dupe scan (logged, never deleted by core) | storage, pets, accessories, minions |
| `ItemService.addNamePrefix(id, (def, data) -> "&7[Lvl 23] ")` | per-instance name prefix | pets |
| `AbilityService.consumeClick(p)` | a module that used this tick's click stops the held item's ability firing | enchanting, crafting, alchemy, minions |
| `MobService.setDisplayName(mob, name)` | per-instance nameplate name (stored on the entity) | combatzones (Special Zealot), dungeons |
| `Requirements.known(prefix)` | whether a resolver exists for a prefix | crafting self-test |

Behaviour change in wave 5: an ability's mana cost drops 1% per `ItemData.extra.mana_disintegrator_count` (max 10)
before cost modifiers run (reforges request 11). A name prefix is drawn BEFORE the rarity colour, so pets read
`&7[Lvl 23] &6Bee`.

Wave-5 integration additions (2026-10-06): `Features.Hunting.openMenu(p)` (default runs `/hunting`; hunting request 2),
`Features.Abiphone.contacts(p)` (default 0; extras implements it, extras request 3). Behaviour change:
`Msg.rareDrop` skips the chat line (keeps the sound) when the player's Settings flag `chat.rare_drops` is off
(menus request 13; shown when menus is absent).
