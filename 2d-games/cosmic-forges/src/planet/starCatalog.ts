import { getStarCatalogPositionLy, lightYearsDistance3D } from "../sim/universe/galacticSpace";
import { mix4 } from "../sim/universe/hash";
import { pickableStarUnitDirection } from "./skydomeSeed";

const NAME_A = ["Ash", "Vor", "Kel", "Mir", "Sol", "Zen", "Rax", "Tyi", "Uln", "Hex"] as const;
const NAME_B = ["drin", "opus", "minor", "prime", "reach", "deep", "gate", "rift", "void", "halo"] as const;

/** Procedural star name from skydome index and world seed. */
export function getStarDisplayName(starIndex: number, gameSeed: number, skydomeSeed: number): string {
  const t = mix4(starIndex >>> 0, gameSeed, skydomeSeed, 0x51a7);
  const a = NAME_A[t % NAME_A.length]!;
  const b = NAME_B[(t >>> 8) % NAME_B.length]!;
  const id = (t & 0xffff).toString(16).toUpperCase();
  return `${a}-${b} ${id}`;
}

/**
 * Distance in ly: star lies on a shell from **anchorLy** along the skydome unit direction; `gpos`
 * is in the same frame.
 */
export function getStarDistanceToPlayerLy(
  starIndex: number,
  skydomeSeed: number,
  gameSeed: number,
  playerGposLy: { x: number; y: number; z: number },
  anchorLy: { x: number; y: number; z: number }
): number {
  const w = pickableStarUnitDirection(skydomeSeed, starIndex);
  const s = getStarCatalogPositionLy(starIndex, skydomeSeed, gameSeed, anchorLy, w);
  return lightYearsDistance3D(s, playerGposLy);
}

/** Deterministic “this star might host worlds” for UI (not full sim). */
export function starMayHavePlanets(starIndex: number, gameSeed: number, skydomeSeed: number): boolean {
  const t = mix4(starIndex >>> 0, gameSeed, skydomeSeed, 0x5ca1_60);
  return (t & 0xff) < 0x4d;
}
