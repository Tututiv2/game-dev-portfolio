# Save format

- **Current version**: `3` — adds Phase 1 fields: `pollution`, `science`, `techTier`, `ferriumCarry` (see `src/core/save.ts`). `localStorage` key `cosmic-forges-save-v3`.
- **M5 (still v3 JSON)**: optional `enemies[].y` for aerial hostiles; `spaceNextWaveAt`, `spaceWaveIndex` for deep-orbit wave timing (default `0` if missing).
- **Galactic cruise (still v3)**: `universe.galacticCruiseProgress` (0–1) for FTL approach meter (defaults to `0` if missing).
- **Buildings** `type` is `extractor` | `purifier` | `relay` | `sentry` (v1–v2 saves only had `extractor`; any other string is coerced to `extractor` on load).
- **v2** JSON can still be loaded; Phase 1 fields default to 0. Legacy keys `v2` / `v1` are read once and migrated to v3.

## Migration

- **v1 →** full universe + Phase 1 defaults; **v2 →** Phase 1 defaults if missing in JSON.
- When incrementing the version, document changes here and extend `tests/save.test.ts`.
