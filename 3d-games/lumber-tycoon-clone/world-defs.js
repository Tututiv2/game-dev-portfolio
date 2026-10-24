/**
 * Per-planet shop / NPC layout overrides (P2.1). Map `worlds[]` entries may include:
 *
 * ```json
 * "worldDef": {
 *   "store": { "x": -22, "z": -10, "W": 14, "D": 10, "bobX": -19.2, "bobZ": -11.8, "bobY": 1.05 },
 *   "clerk": { "x": -19.35, "z": -8.2, "y": 1.05, "interactR": 3.6 }
 * }
 * ```
 *
 * Omitted keys keep the built-in `home` defaults (same numbers as legacy `game.js`).
 */

/** @type {Readonly<{ x: number; z: number; W: number; D: number; bobX: number; bobZ: number; bobY: number }>} */
export const STORE_DEFAULTS = Object.freeze({
  x: -22,
  z: -10,
  W: 14,
  D: 10,
  bobX: -19.2,
  bobZ: -11.8,
  bobY: 1.05,
});

/** @type {Readonly<{ x: number; z: number; y: number; interactR: number }>} */
export const CLERK_DEFAULTS = Object.freeze({
  x: -19.35,
  z: -8.2,
  y: 1.05,
  interactR: 3.6,
});

const STORE_KEYS = ['x', 'z', 'W', 'D', 'bobX', 'bobY', 'bobZ'];
const CLERK_KEYS = ['x', 'z', 'y', 'interactR'];

/**
 * @param {Record<string, number>} target
 * @param {unknown} source
 * @param {string[]} keys
 */
function mergeNumericSubset(target, source, keys) {
  if (!source || typeof source !== 'object' || Array.isArray(source)) return;
  for (const k of keys) {
    const v = source[k];
    if (typeof v === 'number' && Number.isFinite(v)) target[k] = v;
  }
}

/**
 * Reset and apply `slice.worldDef` into live mutable `store` / `clerk` objects (same shape as game STORE / SHOP_CLERK).
 * @param {{ x: number; z: number; W: number; D: number; bobX: number; bobZ: number; bobY: number }} storeOut
 * @param {{ x: number; z: number; y: number; interactR: number }} clerkOut
 * @param {{ worldDef?: unknown } | null | undefined} slice
 */
export function mergeWorldDefIntoRuntime(storeOut, clerkOut, slice) {
  Object.assign(storeOut, STORE_DEFAULTS);
  Object.assign(clerkOut, CLERK_DEFAULTS);
  const wd = slice?.worldDef;
  if (wd && typeof wd === 'object' && !Array.isArray(wd)) {
    mergeNumericSubset(storeOut, wd.store, STORE_KEYS);
    mergeNumericSubset(clerkOut, wd.clerk, CLERK_KEYS);
  }
}

/**
 * @param {{ worldDef?: unknown } | null | undefined} slice
 * @returns {{ store: Record<string, number>; clerk: Record<string, number> }}
 */
export function getActiveWorldDefFromSlice(slice) {
  const store = {};
  const clerk = {};
  mergeWorldDefIntoRuntime(store, clerk, slice);
  return { store, clerk };
}
