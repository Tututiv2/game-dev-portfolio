# Shadow Prison 2 — Master Plan

> Owner, 2026-10-02: *"recreate my server, like a clone, except in your own way... make it feel
> like a professional server, think Hypixel... and add MOST of Hypixel SkyBlock — skills with
> rewards, requirements for stuff."*
>
> Decisions taken that night (AskUserQuestion):
> - **A brand-new server**, written from scratch — not an overhaul of `Minecraft game im making/`.
>   Shadow Prison 1 keeps running live, untouched, until this one is ready.
> - **Season reset at launch.** Everyone starts fresh. Serials, paid ranks and paid cosmetics
>   carry over (they were bought or are real-money collectibles).
> - **SkyBlock first wave: Skills (with level rewards + requirements), Collections, Slayers,
>   Minions** — all four.
> - **Builds are generated in code**, versioned, verifiable — never hand-typed coordinate tables.
> - **Separate from `SkyblockClone/`** (another session's project, started the same night). We
>   share no code. Do not edit that folder.

## The one sentence

**A prison server where every block you break feeds three ladders at once — your rank, your
skills, your collections — and every ladder unlocks something you can SEE.**

Hypixel-quality is not "more features". Shadow Prison 1 has more features than most networks.
It is: one visual language everywhere, nothing that ever looks broken, every number explained,
every unlock announced, a first 60 seconds that teaches without a wall of text, and menus that
feel like one product rather than forty.

## The five laws (inherited, enforced structurally where possible)

| Law | v1 enforced it by… | v2 enforces it by… |
|---|---|---|
| No gambling | review | review (lottery is the ceiling) |
| No pay-to-win | review; VIP multiplier removed by hand | `Perk` type has no gameplay fields at all — a paid perk *cannot* express a stat |
| No odds shown | review; leaked into 4 menus | Odds live only in `Loot` internals; the lore builder has no API that prints a chance |
| Item safety | consume-1/grant-1 by convention | ONE `Grants` door: inventory-or-claim, never the floor; a serial `Ledger` that knows where every instance is |
| Every dial gets UI | by hand, often forgotten | `Dial` registration *is* the UI — `/config` is generated from the registry |

## What v1 taught (golden rules 1–35 → designed out, not remembered)

| v1 bug class | v2 design that makes it impossible |
|---|---|
| NaN/Infinity money (rules 27) | Money is `long` cents. There is no `double` balance anywhere. |
| Menus spoofed by an anvil-renamed chest (28) | Every menu has its own `InventoryHolder`; titles are decoration only |
| Overflow rebuilt from descriptors / dropped on floor (29) | `Grants.give()` stores the real ItemStack in /claim when it does not fit |
| Timed reveals lose loot on quit/restart (30) | Rewards are granted at ROLL time; animations only display |
| Name-based privilege (31) | Staff are UUIDs in the store |
| YAML write races, half-loaded saves (32) | SQLite WAL, one writer thread, per-row transactions (THE STILL proved 10× kill -9) |
| Escrow GUIs re-render their own slots (33) | Trade/AH escrow lives in the store, never in a GUI |
| Donor-block money printers (34) | Custom blocks are tracked by position in the store, not by borrowed Material |
| Cleanup touching other sessions (35) | Every session object is keyed and owned |
| A list grows past one screen (10) | `Menu.paged()` is the only list primitive |
| One bad config row aborts boot (8) | Content loaders validate per row and report in `/config → Health` |

## Architecture

```
ShadowPrison2/
  plugin/                     Gradle, Java 25, Paper 26.1.2, package com.shadowprison
    core/       Kernel, Service, ServiceStack, Module, Log, Scheduler helpers
    store/      Database (SQLite WAL, single writer), Schema migrations, Repos
    money/      Money (long cents), Wallet (multi-currency), Ledger of transactions
    item/       ItemDef registry, ItemId (PDC), Rarity, Lore builder, Stats on items
    grant/      Grants (inventory-or-claim), ClaimStore, Serial Ledger
    menu/       Menu (holder-based), Button, Paged lists, Confirm, Anvil input
    dial/       Dial registry → generated /config UI
    profile/    PlayerProfile cache, per-module data rows
    req/        Requirement (skill lvl / collection tier / rank / prestige) — SkyBlock core
    stat/       Stats (mining speed, fortune, strength, crit…) aggregated from sources
  game/  (later phases) mine/, rank/, sell/, skill/, collection/, slayer/, minion/, ...
  server/                     dev Paper server, port 25610 (gitignored)
  scripts/                    deploy / start / rcon
```

One plugin, one composition root, modules registered in order through `ServiceStack` so a stop
that throws can never skip the saves after it.

## Phases — each ends in a GATE the owner signs off

| # | Phase | Gate (must be true to move on) |
|---|---|---|
| **0** | **Foundation** — kernel, store, money, items, grants/claim, menus, dials→/config, profiles, requirements, stats | Boots clean; unit tests green; store survives `kill -9` with no lost acknowledged writes; a demo menu, a demo dial and a demo grant all work in-game |
| 1 | **The prison loop** — mines (gen/reset), A–Z ranks, sell, sidebar, generated hub v0, first-60-seconds onboarding | A fresh player can mine → sell → rank up to C with no help |
| 2 | **Skills + Collections** — Mining, Foraging, Farming, Combat, Fishing, Enchanting, Alchemy…; a reward on EVERY level; requirements gate gear, mines, areas | Every level and tier shows what it unlocked; a requirement blocks and explains |
| 3 | **Slayers + combat stats** — summonable bosses, slayer XP, drops; strength/crit/defense | One full slayer ladder playable end to end |
| 4 | **Minions** — placeable offline workers, upgrades, storage | Minions produce while offline; nothing dupes on pickup |
| 5 | **v1 economy layer** — enchants, crates/cases, serials, prestige P1–P30, ascension, AH, trade, bazaar-style Mall | Serial ledger audit clean after a scripted stress session |
| 6 | **Social + identity** — gangs, pets, cosmetics, chat/roles, store (cosmetic-only) | |
| 7 | **The world** — professional hub/spawn, prestige islands, events, raids | Owner walks it and says it looks good |
| 8 | **Launch** — S1 → S2 carry-over (serials, paid ranks/cosmetics), live cutover on mc.shadowprison.org | Snapshot-backed cutover, every carried item verified |

## Open questions for the owner (do not guess)

1. **Theme/identity.** Keep the void-mothership + "Shadow" identity, or a new look for S2?
2. **Name.** "Shadow Prison 2" is a working title.
3. **Which v1 systems to drop** rather than clone (Phase 5–6 is big; some v1 systems were
   barely used — measure with live logs before porting).
