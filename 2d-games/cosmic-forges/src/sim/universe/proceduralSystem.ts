import { mix4 } from "./hash";

export type BodyKind = "rock" | "ice" | "gas" | "moon";

export type CelestialBody = {
  id: number;
  name: string;
  kind: BodyKind;
  /** orbit radius, stylized units */
  orbit: number;
  color: number;
  /** 0-1, ring hint for render */
  hasRings: boolean;
};

const NAMES = [
  "Keth",
  "Orin",
  "Vesra",
  "Yul-9",
  "Nerith",
  "Solace",
  "Dran",
  "Hex",
] as const;

/**
 * 3–7 deterministic bodies for a system seed.
 */
export function getSolarBodies(systemSeed: number): CelestialBody[] {
  const n = 3 + (mix4(systemSeed, 1, 2, 3) % 5);
  const out: CelestialBody[] = [];
  for (let i = 0; i < n; i++) {
    const t = mix4(systemSeed, i, 0x9e37, 0x5bd1);
    const kinds: BodyKind[] = ["rock", "ice", "gas", "moon", "rock", "ice"];
    const k = kinds[t % kinds.length]!;
    const orbit = 3 + i * 2.1 + (t % 100) * 0.01;
    const color = (0x203040 + (t & 0x1f1f1f)) & 0xffffff;
    const hasRings = k === "gas" && (t & 0xf) < 3;
    const name = `${NAMES[i % NAMES.length]}-${(t >>> 0) & 0xff}`;
    out.push({ id: i, name, kind: k, orbit, color, hasRings });
  }
  return out;
}

export function getSystemName(systemSeed: number): string {
  const t = mix4(systemSeed, 0xabad1de, 0, 0);
  return `S-${(t & 0xffff).toString(16).toUpperCase()}`;
}

/** Rare systems: render as a dark central mass in the system map. */
export function isBlackHoleSystem(systemSeed: number): boolean {
  return mix4(systemSeed, 0xdead, 0xbeef, 3) % 19 === 0;
}
