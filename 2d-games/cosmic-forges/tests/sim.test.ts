import { describe, it, expect } from "vitest";
import { createInitialState, START_FERRIUM } from "../src/sim/initialState";
import { simStep } from "../src/sim/tick";
import { EventBus } from "../src/core/eventBus";
import { tryPlaceBuilding, tryPlaceExtractor } from "../src/sim/placeBuilding";
import { EXTRACTOR, PURIFIER } from "../src/content/buildings";
import { FIRST_WAVE_AT, SENTRY_DMG_PER_TICK } from "../src/content/waves";
import { serializeState } from "../src/core/save";
import { runAerialEnemyMovement, runSentryFire } from "../src/sim/systems/movementAndCombat";

describe("sim", () => {
  it("places extractor and produces ferrium on schedule", () => {
    const bus = new EventBus();
    const s = createInitialState(1);
    const ok = tryPlaceExtractor(s, 4, 0, bus);
    expect(ok).toBe(true);
    expect(s.ferrium).toBe(START_FERRIUM - EXTRACTOR.cost);
    const f0 = s.ferrium;
    for (let i = 0; i < 19; i++) simStep(s, bus);
    expect(s.ferrium).toBe(f0);
    simStep(s, bus);
    expect(s.ferrium).toBe(f0 + 1);
  });

  it("places a purifier without producing ferrium on extractor schedule", () => {
    const bus = new EventBus();
    const s = createInitialState(1);
    const ok = tryPlaceBuilding(s, 4, 0, "purifier", bus);
    expect(ok).toBe(true);
    expect(s.ferrium).toBe(START_FERRIUM - PURIFIER.cost);
    const f0 = s.ferrium;
    for (let i = 0; i < 25; i++) simStep(s, bus);
    expect(s.ferrium).toBe(f0);
  });

  it("rejects relay before tier 1", () => {
    const bus = new EventBus();
    const s = createInitialState(1);
    s.ferrium = 200;
    s.techTier = 0;
    const ok = tryPlaceBuilding(s, 4, 0, "relay", bus);
    expect(ok).toBe(false);
  });

  it("moves aerial hostiles toward the flight core (M5)", () => {
    const s = createInitialState(1);
    s.flight = { active: true, y: 400, speedMul: 1 };
    s.coreX = 10;
    s.coreZ = 0;
    s.enemies.push({ id: 1, x: 50, y: 400, z: 0, hp: 20 });
    const d0 = Math.hypot(50 - 10, 0, 0);
    runAerialEnemyMovement(s);
    const e = s.enemies[0]!;
    const d1 = Math.hypot(e.x - 10, (e.y ?? 0) - 400, e.z);
    expect(d1).toBeLessThan(d0);
  });

  it("sentry does not target aerial hostiles", () => {
    const s = createInitialState(1);
    s.techTier = 1;
    s.buildings.push({ id: 100, x: 0, z: 6, type: "sentry" });
    s.enemies.push({ id: 1, x: 0, z: 8, y: 50, hp: 12 });
    const hp0 = s.enemies[0]!.hp;
    runSentryFire(s);
    expect(s.enemies[0]!.hp).toBe(hp0);
  });

  it("sentry damages nearest enemy in range", () => {
    const s = createInitialState(1);
    s.ferrium = 200;
    s.techTier = 1;
    s.buildings.push({ id: 100, x: 0, z: 6, type: "sentry" });
    s.enemies.push({ id: 1, x: 0, z: 8, hp: 12 });
    const hp0 = s.enemies[0]!.hp;
    runSentryFire(s);
    expect(s.enemies.length).toBe(1);
    expect(s.enemies[0]!.hp).toBeCloseTo(hp0 - SENTRY_DMG_PER_TICK);
  });

  it("places relay when tier allows", () => {
    const bus = new EventBus();
    const s = createInitialState(1);
    s.ferrium = 200;
    s.techTier = 1;
    const ok = tryPlaceBuilding(s, 4, 0, "relay", bus);
    expect(ok).toBe(true);
    expect(s.ferrium).toBe(200 - 95);
  });

  it("spawns the first wave at the configured tick", () => {
    const bus = new EventBus();
    const s = createInitialState(1);
    for (let t = 0; t < FIRST_WAVE_AT - 1; t++) simStep(s, bus);
    expect(s.enemies.length).toBe(0);
    simStep(s, bus);
    const body = JSON.parse(serializeState(s)) as { state: { tick: number; enemies: unknown[] } };
    expect(body.state.enemies.length).toBeGreaterThan(0);
  });

  it("is deterministic for the same seed and step count", () => {
    const bus = new EventBus();
    const a = createInitialState(2026);
    const b = createInitialState(2026);
    for (let i = 0; i < 500; i++) {
      simStep(a, bus);
      simStep(b, bus);
      expect(serializeState(a)).toBe(serializeState(b));
    }
  });
});
