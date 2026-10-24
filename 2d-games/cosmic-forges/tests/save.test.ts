import { describe, it, expect } from "vitest";
import { createInitialState } from "../src/sim/initialState";
import { deserializeState, serializeState, SAVE_VERSION } from "../src/core/save";
import { simStep } from "../src/sim/tick";
import { EventBus } from "../src/core/eventBus";

describe("save round-trip", () => {
  it("restores an identical sim after JSON serialize/parse", () => {
    const s0 = createInitialState(7);
    s0.ferrium = 123;
    s0.nextWaveAt = 200;
    s0.waveIndex = 2;
    s0.rng.state = 0xdeadbeef;
    s0.nextEntityId = 99;
    s0.coreHp = 50;
    s0.buildings.push(
      { id: 1, x: 2, z: 4, type: "extractor" },
      { id: 98, x: 6, z: 0, type: "purifier" }
    );
    s0.enemies.push({ id: 2, x: 10, z: 0, hp: 5 });
    s0.gameOver = false;
    s0.pollution = 33.5;
    s0.science = 200;
    s0.techTier = 1;
    s0.ferriumCarry = 0.3;
    s0.coreX = 3;
    s0.coreZ = -7;
    s0.flight = { active: true, y: 44.2, speedMul: 1.75, vx: 1.2, vy: 0, vz: -3.4 };
    s0.universe.navTarget = { x: 5, y: 9 };
    s0.universe.galacticCruiseProgress = 0.33;
    s0.universe.pinnedStarIndex = 42;
    s0.universe.gposX = 120_000.5;
    s0.universe.gposY = -3_300.2;
    s0.universe.gposZ = 88_000.25;
    s0.spaceCombatEnabled = true;
    s0.spaceNextWaveAt = 777;
    s0.spaceWaveIndex = 3;
    s0.enemies.push({ id: 50, x: 1, z: 2, y: 400, hp: 8 });

    const j = serializeState(s0);
    const s1 = deserializeState(j);
    const body = JSON.parse(j) as { version: number };
    expect(body.version).toBe(SAVE_VERSION);

    expect(s1.seed).toBe(s0.seed);
    expect(s1.ferrium).toBe(123);
    expect(s1.tick).toBe(0);
    expect(s1.rng.state).toBe(0xdeadbeef);
    expect(s1.nextEntityId).toBe(99);
    expect(s1.coreHp).toBe(50);
    expect(s1.nextWaveAt).toBe(200);
    expect(s1.waveIndex).toBe(2);
    expect(s1.buildings[0]!.type).toBe("extractor");
    expect(s1.buildings[1]!.type).toBe("purifier");
    expect(s1.buildings).toEqual(s0.buildings);
    expect(s1.enemies).toEqual(s0.enemies);
    expect(s1.universe.systemSeed).toBe(s0.universe.systemSeed);
    expect(s1.universe.galaxyX).toBe(s0.universe.galaxyX);
    expect(s1.universe.viewMode).toBe(s0.universe.viewMode);
    expect(s1.pollution).toBe(33.5);
    expect(s1.science).toBe(200);
    expect(s1.techTier).toBe(1);
    expect(s1.ferriumCarry).toBeCloseTo(0.3);
    expect(s1.coreX).toBe(3);
    expect(s1.coreZ).toBe(-7);
    expect(s1.flight).toEqual({ active: true, y: 44.2, speedMul: 1.75, vx: 1.2, vy: 0, vz: -3.4 });
    expect(s1.universe.navTarget).toEqual({ x: 5, y: 9 });
    expect(s1.universe.galacticCruiseProgress).toBeCloseTo(0.33);
    expect(s1.universe.pinnedStarIndex).toBe(42);
    expect(s1.universe.gposX).toBeCloseTo(120_000.5);
    expect(s1.universe.gposY).toBeCloseTo(-3_300.2);
    expect(s1.universe.gposZ).toBeCloseTo(88_000.25);
    expect(s1.spaceCombatEnabled).toBe(true);
    expect(s1.spaceNextWaveAt).toBe(777);
    expect(s1.spaceWaveIndex).toBe(3);
    expect(s1.enemies.some((e) => e.id === 50 && e.y === 400)).toBe(true);
  });

  it("stays identical through round-trip and another tick", () => {
    const bus = new EventBus();
    const a = createInitialState(0xab);
    for (let i = 0; i < 100; i++) simStep(a, bus);
    const b = deserializeState(serializeState(a));
    expect(serializeState(a)).toBe(serializeState(b));
    simStep(a, bus);
    simStep(b, bus);
    expect(serializeState(a)).toBe(serializeState(b));
  });
});
