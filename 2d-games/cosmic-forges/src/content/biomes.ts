/**
 * Data-driven biomes for Phase 1+ surface variety. Stub for Phase 4.
 */
export const BIOMES = ["crimson_badlands", "frost", "jungle", "toxic", "shattered"] as const;
export type BiomeId = (typeof BIOMES)[number];

export function biomeForBody(_bodyKind: "rock" | "ice" | "gas" | "moon", seed: number): BiomeId {
  return BIOMES[seed % BIOMES.length]!;
}
