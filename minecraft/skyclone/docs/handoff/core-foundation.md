# core-foundation — hand-off

Package `com.skyclone.core.foundation` + `com.skyclone.SkyPlugin` + `scripts/` + `Dev Server.bat`.
Implements: `Database`, `ProfileService`, `EconomyService`, `Dials`, `CommandService`,
`Requirements`, `CalendarService`.

## What works (verified how)

| Piece | Verified by |
|---|---|
| Everything compiles | `bash tools/check.sh core` -> `CHECK OK: core (146 files)` (with the other cores' code present), plus an isolated `javac -Xlint:all` of api + foundation + SkyPlugin with zero warnings in my files |
| SQLite layer: WAL, FIFO single thread, BEGIN IMMEDIATE, nested = SAVEPOINT, migrate idempotent + versioned, close drains the queue | `ProfileStoreTest` against a real SQLite file (29/29 unit tests pass, run with the JUnit launcher outside gradle) |
| Profile SQL: first login creates one profile, relog finds it, extra profiles get distinct fruit names, switching, co-op join caps (members / profile count), leaving returns to your oldest profile or creates a fresh one, dangling memberships repaired | `ProfileStoreTest` (real SQL, real DB thread) |
| Coin maths (tenths, rounding direction, cap, parse "2.5k") | `CoinsTest` |
| Calendar maths (units, research's year-517 check, multi-day/year-wrapping events, everyYears/yearOffset, every-month extension, realtime events) | `CalendarMathTest` |
| Lenient Gson (unknown enum -> null, EnumMap/Map<Enum> drop removed keys, NaN/Infinity, stable output) | `GsonsTest` |
| `rcon.ps1` multi-packet replies (601 lines across 3 packets), colour strip, leading `/` | run against a fake vanilla-behaviour RCON server |
| `deploy.ps1`, `start-server.ps1`, `rcon.ps1` | parse-checked with the PowerShell parser; all ASCII (Windows PowerShell 5.1 misreads BOM-less UTF-8) |

**Not verified live**: I was told not to start the server, so nothing here has run inside Paper yet.
First live run: `scripts\rcon.ps1 "sb selftest foundation"` and `"sb info"`.

## Behaviours module authors MUST know

### Profiles (`Sky.profiles()`)
- Load happens in `AsyncPlayerPreLoginEvent` (DB read, blocking that async thread). **If the read fails
  the login is refused** ("could not be loaded, try again") — a player is never let in on empty data.
  `ProfileReady` fires the tick after `PlayerJoinEvent`. `isLoaded(p)` is true from join;
  ProfileReady is the signal to set things up.
- `member()/shared()` return the **live object**. Mutate it in place; core serialises every 10 s
  (`profiles.autosave-seconds`) and writes only documents whose JSON changed, in ONE transaction.
  **Do not keep references across ticks/sessions** — after quit the object is evicted and mutations
  are lost. Re-fetch at use time (it is a map lookup).
- Called before load (or for a non-player / NPC Player object): you get a **throwaway** new instance
  and a one-time warning with a stack trace. Nothing you do to it is saved.
- Your data class: public no-arg constructor (field initialisers are your defaults — missing JSON
  fields keep them), Gson-friendly fields. Enum values are read case-insensitively; an **unknown
  enum constant reads as null**; map entries whose enum KEY no longer exists are **dropped**.
- If stored JSON cannot be read as your class (you changed a field's type incompatibly), the slot is
  marked **POISONED**: you get a fresh object, the stored JSON is left untouched and **nothing is saved
  for that key this session**. It is logged SEVERE and shown by `/sb profile <p>` and the
  `foundation.profiles-loaded` self-test. Fix the class or write a migration; never silently wipe.
- Same key asked with two different classes: converted through JSON with a warning (don't do it).
- `shared()` is ONE object for all online members of a co-op (same instance), so two members see
  each other's changes immediately.
- `saveNow(p)` after anything that must never be lost. A failed write is retried next save and the JSON
  is also dumped to `plugins/SkyClone/rescue/`.
- `modifyOffline`/`readOffline`: decided on the main thread. Online -> live object (+ save; future completes
  when durable). Offline -> one DB transaction on the player's ACTIVE profile. A change that lands while
  the player is mid-login is re-applied when they attach (sequenced), so it is never overwritten.
  `readOffline` returns a COPY; null if the player has never joined.
- `members(profileId)` answers only for profiles currently loaded (someone online); otherwise `List.of()`.
- `profileId(p)` is null when not loaded; `profileName` "" ; `mode` "normal".
- Switching profile / joining or leaving a co-op **kicks with a friendly message**; the player rejoins
  and loads through the normal path. Reloading every module's live state in place is not safe.
- Tables: `profiles`, `profile_members` (purse, bits, active, `left_at` — a left membership is kept,
  not deleted, for a future salvage), `profile_member_data`, `profile_shared_data`, `economy_ledger`,
  `schema_versions`.

### Economy (`Sky.economy()`)
- Stored as long **tenths**. Credits round DOWN, debits round UP (the server can't mint by rounding).
  Negative / NaN / infinite / > 1e15 amounts are refused and logged once with a stack trace.
- Every mutation is written through immediately (absolute balances + a ledger row in the same
  transaction). In-memory and DB can't drift: writes are queued in mutation order on the one DB thread.
- `withdraw` of 0 returns true. `bankDeposit` respects capacity; `bankCredit` (interest/admin) does NOT.
- Default bank capacity comes from dial `economy.bank-default-capacity` (50M) until the bank module
  calls `setBankCapacity` (which is stored per profile).
- `CoinsChange` fires only for **purse** changes (including the purse side of bank deposit/withdraw),
  always on the main thread. Bits and bank changes fire no event (API request 4).
- `depositOffline` pays into the player's **active** profile (API request 1). Online -> live deposit.
- Bits are whole numbers (not tenths).

### Database (`Sky.db()`)
- One thread, FIFO. A read queued after a write sees it. Futures complete on the DB thread.
- Calling `query`/`transaction` from INSIDE DB work runs inline (nested transaction = SAVEPOINT),
  so a nested `.join()` cannot deadlock.
- `migrate(module, N, ...)`: **one SQL statement per string**. Blocking; throws (so your enable fails) if
  a statement fails. Each version runs once.
- Failures are logged with the stack AND where the work was submitted from.

### Commands (`Sky.commands()`)
- Names `[a-z0-9_-]{1,32}`. **First registration wins**, later ones are logged and ignored; an alias
  already taken is dropped. Register during enable/postEnable (afterModules also fine). Anything
  registered after Paper's COMMANDS lifecycle event goes through a Bukkit command-map fallback (works,
  logs a warning). If the lifecycle event never fires at all, a check 1 s after boot registers everything
  through the fallback.
- Executor exceptions are caught: logged with stack, player sees `&cSomething went wrong (logged)`.
- Non-admin players are rate limited (`commands.max-per-second`, default 8/s across all our commands).
- `/sb <sub>` subs are looked up at run time, so `admin(...)` works at any time. Sub `help` is reserved.
- `/sb selftest` output is plain text, one `PASS id` / `FAIL id: reason` line each, then
  `SELFTEST: x passed, y failed`. Tests run on the main thread; a thrown exception is a FAIL.
- **RCON returns only what is sent while the command runs.** Anything you print after a DB future
  completes goes nowhere over RCON. Keep admin/self-test output synchronous.
- To open another feature's menu from code with a CONSTANT command string, `player.performCommand("profiles")`
  is fine (the rule is about player-typed text); better is API request 6.

### Requirements (`Sky.requirements()`)
- Unknown prefix -> false, logged once (fail closed). A resolver that throws -> false, logged once.
- `"a & b"` all must hold; `describe` joins with `&c, ` / `&c and `; `checkOrTell` names only the
  FAILING parts: `&cRequires &aCombat Skill 15&c!` (+ the villager "no" sound).
- Builtin prefixes: `always`, `never[:text]` (shows the text, e.g. `never:Coming soon`),
  `perm:<node>`, `mode:<mode>`, `purse:<coins>`, `coop`.
- Prefix registered twice: the first wins.

### Calendar (`Sky.calendar()`)
- Date derives from the wall clock + `calendar.epoch-millis` (set to first boot, persisted).
  `calendar.day-minutes` = 20. Nothing ticks or is stored, so restarts never drift.
- Calendar-date events: `month` 1..12, **or 0 = every month** (extension), `day` 1..31,
  `durationDays` may run past month/year end, `(year - yearOffset) % everyYears == 0`.
- Realtime events count from the Unix epoch (UTC): interval 60 + offset 55 = every hour at :55.
- A 1 s task fires `CalendarStart`/`CalendarEnd` on transitions. **On the first tick after boot every
  event already in progress gets a CalendarStart** — handle "started" idempotently.
- Invalid events are refused with a log line (they never silently do nothing). Duplicate id: first wins.
- `millisUntil` = 0 when active, -1 for an unknown id.
- `force(id, true)` = active for one natural occurrence length; `force(id, false)` = off until the
  current natural occurrence ends; `/sb calendar force <id> clear` returns to natural.

### Dials (`Sky.dials()`)
- `dials.yml` holds ONLY values set in game; reset removes them. `/sb dial list` shows value and
  default side by side, `*` = overridden, `*(=default)` = an override that currently equals the
  default (it will stop following the code default), `?` = an override nobody registers.
- Same key registered twice with the same type returns the SAME dial; different type -> a private
  dial + warning. Number/integer values accept `2.5k`, `3m`, `1e3`.
- `server.display-name` (text, "SkyClone"): the server's display name. **ui/menus: read this dial
  instead of hard-coding the name.**

### SkyPlugin boot / shutdown
- foundation (fatal if it fails: plugin disables itself) -> items, combat, world, ui cores by reflection
  (missing class logged; a throwing boot logged, that core skipped) -> every module `enable()` (throw =
  logged with full stack, skipped; listeners it registered before throwing stay live) -> `postEnable()`
  (throw = logged, module's `disable()` called, counted failed) -> `afterModules()` on FoundationCore
  then each booted core.
- Boot summary in the log: core status, N enabled / M failed with reasons, items/mobs/recipes/npcs/
  islands/warps counts.
- Shutdown: **ProfileUnload for every online player + save BEFORE modules disable**, then modules
  `disable()` in reverse, cores `shutdown()` in reverse, foundation last (final save, DB drained and closed,
  up to 30 s).

## Commands added

Player: `/profiles` (`/profile`, `/pm`) — Profile Management menu (switch, create up to 5, co-op button;
`list`, `switch <name>`, `create` sub-commands) · `/coop invite <p> | accept | deny | list | leave`
· `/coopadd <p>` · `/commands` (`/cmds`) — paged menu of every command by category (`/commands chat`
for text) · `/calendar` (`/cal`) — ONLY if no module registered `/calendar` (the menus module owns it per
its brief; mine is a fallback with date + every upcoming event, refreshing every second).

Admin (`/sb ...`, permission `skyclone.admin`): `help [filter]` · `info` · `selftest [filter]` ·
`commands dump|list` (dump writes `docs/COMMANDS.md` next to `server/` with dials appended, else
`plugins/SkyClone/COMMANDS.md`) · `dial list [filter] | get | set | reset` · `coins <p> give|take|set <amt>`
(offline `give` works) · `bits <p> give|take|set <n>` · `bank <p> give|take|set|cap <amt>` ·
`ledger <p> [n]` (in-memory rows since boot; a player sender with none falls back to the DB) ·
`profile <p>` · `save` · `calendar [force <id> on|off|clear | skip <days>]` · `requirement <p> <key>`.

## Dials added
`server.display-name` · `commands.max-per-second` (8) · `profiles.autosave-seconds` (10) ·
`profiles.max-profiles` (5) · `profiles.max-coop-members` (5) · `profiles.load-timeout-seconds` (15) ·
`profiles.coop-invite-seconds` (60) · `economy.bank-default-capacity` (50,000,000) ·
`economy.ledger-memory` (200) · `calendar.epoch-millis` (0 = first boot) · `calendar.day-minutes` (20).

## Self-tests added
`foundation.coins-format`, `foundation.calendar-math` (incl. research 05 §7.3 year 517),
`foundation.calendar-events-valid` (every registered event valid and occurring),
`foundation.dial-parse` (also: every dial has a description), `foundation.requirements-fail-closed`,
`foundation.gson-lenient`, `foundation.db-roundtrip` (schema version, WAL on, nested transaction),
`foundation.commands-described` (also fails if Paper's command event never fired),
`foundation.profiles-loaded` (every online player loaded + ready, nothing poisoned),
`foundation.menus-build` (profile + calendar menus build for an online player).

## Scripts
- `Dev Server.bat` — double-click: build (`gradle jar`), deploy, start Paper on **25630** in that window;
  stays open on exit so errors can be read.
- `scripts/deploy.ps1` — `-SkipBuild`, `-Test` (runs `gradle build` incl. unit tests; default is `jar` so one
  flaky module test cannot block a deploy), `-Quiet`. Floor raised to **300 KB** (api + foundation alone is
  ~250 KB). Fails if plugin.yml/SkyPlugin/Sky/ModuleList/FoundationCore are missing, warns per missing core,
  prints module/data-file counts, removes stale `SkyClone*.jar` copies, verifies the copy's size.
- `scripts/start-server.ps1` — finds Java 25 (JAVA_HOME, PATH, Adoptium dirs), refuses if 25630 is already
  listening, `-NoDeploy`, `-Detached` (prints `PID=n`), `-WaitReady` (polls RCON, never the log), `-MemoryGb`.
- `scripts/rcon.ps1` usage:
  ```
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\rcon.ps1 "sb selftest"
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\rcon.ps1 -Strip "sb info"
  ... -Port 25631 -Password skydev -TimeoutMs 5000      (defaults)
  ```
  Exit 0 = ok, 1 = connect/auth failure. No command = just checks the server answers (used by
  `-WaitReady`). Reads multi-packet replies to the end (fixed: it used to read ONE packet, which
  truncated long `/sb selftest` output and hid the summary line). `-Strip` removes `§` colour codes.
  A leading `/` is ignored.

## Unit tests
`plugin/src/test/java/com/skyclone/core/foundation/` — `CoinsTest`, `CalendarMathTest`, `GsonsTest`,
`DialsParseTest`, `ProfileStoreTest` (29 tests). They need only paper-api + sqlite-jdbc + JUnit
(`gradle test`). The DB tests use a temp directory and clean up.

## API change requests
1. **`EconomyService.depositOffline(String profileId, UUID player, ...)`** — auction/bazaar proceeds must
   go to the profile that listed the item, not whichever profile is active when it sells. Today it pays
   the active profile.
2. **`ProfileService.membersAsync(String profileId)`** — `members()` can only answer for loaded profiles.
3. **`ProfileService.isReady(Player)`** — loaded AND ProfileReady fired (implemented on the impl already).
4. **Bits / bank change events** (or a `currency` field on CoinsChange) — the sidebar's "Bits: (+250)"
   flash and bank UIs have nothing to listen to.
5. **`Dials.Dial#onChange(Consumer<T>)`** — modules that schedule tasks from a dial cannot react to `/sb dial set`.
6. **`CommandService.run(Player, String name, String... args)`** — lets the SkyBlock Menu open
   `/profiles`, `/calendar`, `/commands` etc. through the registry (guards included) without
   `performCommand`. Also `Features.Profiles { void openMenu(Player) }` would do for Profile Management.
7. Document in `CalendarEvent` javadoc: `month = 0` means every month; CalendarStart fires at boot for
   events in progress.
8. `ProfileService.createProfile(Player, String mode)` for Ironman/Stranded/Bingo — today every new
   profile is "normal" and there is no API to make another mode.

## Known risks
1. **Never run inside Paper.** The Brigadier lifecycle wiring, PreLogin -> Join -> ProfileReady timing and
   RCON capture are written to Paper's API but unproven live. Fallbacks exist (command-map fallback,
   kick-on-missing-profile) and the self-tests are designed to expose each failure on the first boot.
2. **Logins block on the DB thread.** A huge autosave or a slow module transaction delays every login
   behind it (FIFO by design — that is what makes relogs safe). Timeout is `profiles.load-timeout-seconds`;
   past it the login is refused, never let in empty. Modules must keep DB work short.
3. **Modules holding object references** from `member()` past quit, or calling it before ProfileReady,
   lose writes silently-except-for-a-log-line. The throwaway-object behaviour is deliberate (no NPEs) but
   it is the most likely source of "my data didn't save" bugs.
4. `/reload` is handled (online players are loaded at enable) but not recommended — listeners of a module
   that threw from enable stay registered.
5. Co-op leave keeps the leaver's purse on the closed membership (no salvage command yet); co-op kick is
   not implemented.

## Fix 2026-10-02 (integrate-w2-1): `/sb selftest` no longer trips Paper's watchdog
Found in round 3: 357 self-tests run back to back held the main thread for 10+ s with a player online
and Paper dumped every thread (the stack happened to be in `ArmorSelfTests.menusBuild`; that test is just
where the clock ran out, not a hang). Root cause: `runSelfTests` ran every test in one tick.

- New `core/foundation/SelfTestRun` (no Bukkit): runs tests until a per-tick budget is spent, at least one
  per slice, timing each. `CommandServiceImpl.runSelfTests` runs the first slice inside the command, then
  one slice per tick from a scheduler task. Budget is the dial **`selftest.tick-budget-ms`** (default 25,
  clamped 5..1000), so it can be changed live with `/sb dial set`.
- A run that fits in the first slice (any small filtered run) answers **exactly as before**, in one reply.
  A longer one answers `SELFTEST RUNNING n/N ...`; the player who ran it keeps getting PASS/FAIL lines and
  the summary as it goes. RCON cannot receive anything after the command returns, so:
- **`/sb selftest report`** (alias `status`; reserved words, no test id contains either) prints every line
  so far plus `SELFTEST RUNNING ...` or the final `SELFTEST: x passed, y failed` + a timing line (main-thread
  ms, ticks, wall ms, three slowest tests). The last report is also written to
  `plugins/SkyClone/selftest-last.txt`.
- **`scripts/rcon.ps1`**: when an `sb selftest` reply contains `SELFTEST RUNNING`, it polls
  `sb selftest report` once a second on the same connection (`-SelfTestWaitSec`, default 600) and prints the
  final report, so `rcon.ps1 -Strip "sb selftest"` still ends in the summary line. Verified against a fake
  RCON server (running -> report x2 -> summary, exit 0; a non-selftest command unchanged). ASCII, parses.
- A second `/sb selftest` while one runs is refused with the progress line. Any single test over 1 s is
  logged as a WARNING naming it, because one test cannot be split; only its owner can make it cheaper.
- Verified: `SelfTestRunTest` 6/6 (fake clock: 357 x 30 ms tests never hold a slice past 30 ms; fast tests
  share a slice; small filtered run is one slice with the old output; a throwing test fails and the run
  continues; report shows RUNNING mid-run and names slow tests; an empty filter is done, not a hang).
  `bash tools/check.sh core` CHECK OK. **Not verified live** (told not to start the server): first boot,
  run `scripts\rcon.ps1 -Strip "sb selftest"` with a player online and confirm no watchdog dump.
