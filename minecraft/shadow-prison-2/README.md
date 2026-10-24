# Shadow Prison 2

A ground-up rewrite of my live prison server, where every bug class the first version shipped is designed out of the architecture instead of remembered.

Shadow Prison 1 grew to hundreds of thousands of lines and collected 35 "golden rules", each one a lesson from a real bug. Season 2 starts from an empty repo with a different question: instead of guarding against NaN balances, spoofed menus or items dropped on the floor, make them impossible to express. The target is a prison server that feels like a professional network: one visual language, every number explained, every unlock visible, and a SkyBlock layer of skills, collections, slayers and minions on top of the prison loop.

## Features (Phase 0: the foundation)

- Service kernel with named boot phases and isolated, reverse-order shutdown
- SQLite store in WAL mode with a single writer thread and versioned, append-only migrations
- Money as `long` cents with `1.5k` / `2m` shorthand, multi-currency wallets and a ledger of every move
- Item registry with persistent IDs, unique-instance tracking and SkyBlock-style lore with stats and rarity
- `Grants.give()`: the single way to give an item; inventory first, otherwise a `/claim` box
- Holder-based menus, a paged list primitive and one shared UI style
- A dial registry that generates the `/config` admin UI and records who changed what
- Requirements ("Requires Mining 12") whose lore and refusal messages come from the same object
- Additive stat aggregation
- Commands via Brigadier: `/menu`, `/claim`, `/balance`, `/config`, `/sp health|give|money`

## How it works

| Version 1 bug class | Version 2 design that makes it impossible |
|---|---|
| NaN / Infinity balances | Money is `long` cents; there is no `double` balance anywhere, plus a database `CHECK (amount >= 0)` |
| Menus spoofed by an anvil-renamed chest | Every menu is its own `InventoryHolder`; titles are decoration only |
| Overflow items dropped on the floor or rebuilt from descriptors | `/claim` stores the real ItemStack bytes; `take()` delivers only if this call deleted the row, so a claim is exactly-once |
| YAML write races and half-loaded files saved over good ones | SQLite WAL, one writer, batched transactions; profiles load off-thread at pre-login and the player is kicked if a load fails |
| One shutdown step throwing skipped every save after it | `ServiceStack` stops each service in its own try/catch |
| Tunables with no admin UI | Registering a dial is what creates its `/config` entry |
| Same-tick double clicks causing dupes | One menu action per player per tick |

- **A crash gate, not a promise.** `CrashTest` hard-kills a JVM five times in the middle of writing (`destroyForcibly`, equivalent to `kill -9`). Across the runs: **334,858 acknowledged writes, 0 lost**.
- **Pay-to-win is unrepresentable.** The paid `Perk` type has no gameplay fields at all, so a purchased perk cannot express a stat. Drop odds live only inside loot internals; the lore builder has no API that prints a chance.
- Dev boot: plugin enabled in about 130 ms with 0 errors and a clean shutdown.

## Tech

- Java 25, Paper 26.1.2, Gradle Kotlin DSL
- SQLite (WAL), JUnit 5
- PowerShell scripts for deploy, headless start and RCON

## Running it

- `Test.bat` runs the tests.
- `Deploy.bat` runs all tests and only deploys the jar to the dev server if they are green.
- `Dev Server.bat` starts the local dev server; `scripts/start-server.ps1 -Detached -WaitReady` then `scripts/rcon.ps1 "sp health"` drives it headless.

The dev server, worlds and downloaded dependencies are not included in the repo.

**Status:** Phase 0 (foundation) is built and machine-verified. Next is Phase 1: generated mines with timed resets, A-Z ranks, selling, a sidebar, a generated hub and first-minute onboarding.
