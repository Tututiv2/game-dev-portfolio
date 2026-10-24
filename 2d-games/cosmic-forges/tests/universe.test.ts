import { describe, it, expect } from "vitest";
import { createInitialState } from "../src/sim/initialState";
import { tryWarpTo, selectBody, openGalaxy } from "../src/sim/universe/travel";
import { starAtSector, sectorId } from "../src/sim/universe/galaxyModel";

describe("universe", () => {
  it("warp updates system seed to match new sector", () => {
    const s = createInitialState(99);
    const a = s.universe.galaxyX;
    const b = s.universe.galaxyY;
    const t = a + 1;
    if (t < 256) {
      const ok = tryWarpTo(s, t, b);
      expect(ok).toBe(true);
      const { systemSeed } = starAtSector(t, b);
      expect(s.universe.systemSeed).toBe(systemSeed);
    }
  });

  it("select body keeps sector discovered", () => {
    const s = createInitialState(5);
    openGalaxy(s);
    const sid = sectorId(s.universe.galaxyX, s.universe.galaxyY);
    expect(s.universe.discovered).toContain(sid);
    selectBody(s, 0);
    expect(s.universe.viewMode).toBe("surface");
    expect(s.universe.bodyIndex).toBe(0);
  });
});
