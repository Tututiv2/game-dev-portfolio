# Stronghold Crusader Remaster Backlog

This backlog mirrors the approved roadmap and tracks implementation progress.

## Phase 1 - Immediate Gameplay Correctness

| Priority | Item | Status | Notes |
| --- | --- | --- | --- |
| P0 | Terrain-aware movement (no water traversal) for military and dots | Done | Includes right-click command validation and in-sim movement blocking. |
| P1 | Blocked-target fallback behavior and clear command feedback | Done | Invalid move targets now snap to nearest walkable tile or reject with toast. |
| P1 | Regression mission cases (lakes, choke points) | Planned | Add dedicated scenario maps and scripted validation paths. |

## Phase 2 - Lord Dot + Keep Identity

| Priority | Item | Status | Notes |
| --- | --- | --- | --- |
| P0 | Add Lord dot entity per keep | Done | Patrol loop generated from keep footprint. |
| P1 | Threat state transitions (patrol, alert, return) | Done | Driven by nearby enemy pressure and keep threat timer. |
| P1 | Render and panel visibility improvements | Done | Distinct render color and keep panel status summary. |

## Phase 3 - Enemy AI Less Predictable

| Priority | Item | Status | Notes |
| --- | --- | --- | --- |
| P0 | AI personality profiles and deterministic strategy selection | Done | Profiles include raider, turtler, eco, and hybrid modes. |
| P0 | Strategy scheduler (probe, flank, push, retreat) | Done | Uses phase windows and per-profile weights. |
| P1 | Seed-based replayable variation | Done | Mission seed and ai hash influence profile/wave composition. |

## Phase 4 - Economy Expansion

| Priority | Item | Status | Notes |
| --- | --- | --- | --- |
| P0 | Optional modernization toggles | Done | Research and upkeep can be toggled in mission rules. |
| P0 | Army upkeep (food + gold) and morale pressure | Done | Periodic drain with desertion penalties when deficit persists. |
| P1 | Research progression for economy and military tempo | Done | Capacity, throughput, and recruitment speed upgrades. |
| P2 | Trade agreements/contracts framework | Planned | Data model stubbed; multiplayer sync pending. |

## Phase 5 - Units + Battle Strategy

| Priority | Item | Status | Notes |
| --- | --- | --- | --- |
| P0 | New/returning troop roles and counters | Done | Added skirmisher and templar with role-specific stats. |
| P1 | Squad-level commands (hold line, focus fire, fallback) | Done | Command mode hooks into stance and target selection. |
| P1 | Extended stance behavior and leash tuning | Done | Stance now adjusts pursuit cap and retreat discipline. |
| P2 | Balance tests and matchup matrix | Planned | Add scripted bot-vs-bot sweep and telemetry dashboard. |

## Phase 6 - Content, UX, Stability

| Priority | Item | Status | Notes |
| --- | --- | --- | --- |
| P0 | Expanded roadmap for maps/campaign/editor/multiplayer | Done | Added scoped milestones and blockers in this file. |
| P1 | Dedicated bugfix budget per sprint | Done | Included in release checklist and acceptance criteria. |
| P1 | Multiplayer reliability slice | In progress | Deterministic sim sync checks still needed. |

## Cross-Cutting Bugfix Budget

- Reserve at least 25% of each sprint for bugfix and polish work.
- Track and triage these recurring issue classes:
  - pathing stalls and illegal terrain traversal
  - peasant worker deadlocks and route desync
  - combat target acquisition edge cases
  - mission panel stale state and context mismatches
- Require at least one regression checklist run before each release candidate.

## Content and UX Expansion Milestones

1. Campaign and scenario expansion
   - 6-10 remastered missions with distinct AI personalities.
   - New skirmish trails with map-scale variation.
2. Map and scenario editor improvements
   - Better terrain painting tools and mission validation.
   - Explicit water chokepoint overlays for pathing readability.
3. Multiplayer foundation pass
   - Lobby quality improvements and reconnect handling.
   - Input delay and sync diagnostics overlay.
