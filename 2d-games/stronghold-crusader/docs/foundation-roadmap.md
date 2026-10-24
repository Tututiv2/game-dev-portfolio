# Foundations: Maps, Campaign, Editor, Multiplayer

## Map and Campaign Expansion

- Add two late-campaign nodes with higher tactical pressure (`m7`, `m8`).
- Ensure every new mission ships with:
  - a biome identity
  - at least one mixed-unit wave
  - one anti-stall objective
- Continue adding mission-specific AI profiles instead of default-only behavior.

## Scenario Editor Foundations

- Add a mission validator pass before save:
  - keep/campfire existence
  - wave trigger ordering
  - invalid water-locked spawn checks
- Add terrain overlays:
  - walkable grid
  - water-blocked lanes
  - buildability heatmap
- Add objective templates:
  - survive waves
  - defeat lord
  - economy thresholds

## Multiplayer Reliability Foundations

- Keep simulation deterministic where possible:
  - seeded strategy decisions
  - consistent wave composition ordering
  - explicit fixed-step simulation updates
- Add desync diagnostics:
  - compare periodic checksums of units/enemies/resources
  - log mismatch frame and entity IDs
- Add reconnect flow:
  - restore snapshot
  - replay buffered commands

## Bugfix and Playtest Loop Budget

- Reserve 25% of each sprint for bugfix and QA hardening.
- Regression checklist every release candidate:
  - no unit can cross `lake` terrain
  - recruit dots and workers recover from blocked routes
  - lord patrol returns to route after threat state
  - queue and command UI remains responsive after save/load
- Track and review:
  - `pathBlocks`
  - `waterCommandRejections`
  - `strategyShifts`
