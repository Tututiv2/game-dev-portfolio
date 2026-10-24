/**
 * Axis-aligned “log becomes plank” region in sawmill local space (+X = front feed, −X = back).
 * Shared by game.js, prefab-serializer.js, and prefab-editor.js.
 */

/** Same numbers as buildDefaultSawmill / SAWMILL when prefab has no custom zone. */
export const EDITOR_DEFAULT_SAWMILL_DIMS = {
  tunnelW: 1.05,
  outerH: 1.02,
  tunnelD: 2.1,
  tunnelBackExtra: 0.95,
  /**
   * Extra half-length along sawmill local ±X for the convert AABB (beyond tunnelD/2).
   * Larger values allow long box logs (length on mesh +Y) to fully register when fed along +X.
   */
  convertZoneXPad: 1.3,
};

/** Default convert box: generous along X so tall/long logs can be fully “inside” for one frame. */
export function defaultSawmillConvertZoneDims(dims) {
  const tw = dims.tunnelW;
  const oh = dims.outerH;
  const td = dims.tunnelD;
  const extra = dims.tunnelBackExtra;
  const pad =
    dims.convertZoneXPad != null && Number.isFinite(Number(dims.convertZoneXPad))
      ? Math.max(0.15, Number(dims.convertZoneXPad))
      : 1.3;
  return {
    xMax: td / 2 + pad,
    xMin: -(td / 2 + pad + extra),
    halfZ: tw / 2 + 0.16,
    centerY: oh * 0.38,
    halfY: oh * 0.52 + 0.08,
  };
}

/**
 * @param {unknown} z
 * @returns {{ xMin: number; xMax: number; halfZ: number; centerY: number; halfY: number } | null}
 */
export function normalizeSawmillConvertZone(z) {
  if (!z || typeof z !== 'object') return null;
  const xMin = Number(z.xMin);
  const xMax = Number(z.xMax);
  const halfZ = Number(z.halfZ);
  const centerY = Number(z.centerY);
  const halfY = Number(z.halfY);
  if (![xMin, xMax, halfZ, centerY, halfY].every(Number.isFinite)) return null;
  if (xMax <= xMin + 0.02) return null;
  return {
    xMin,
    xMax,
    halfZ: Math.max(0.05, halfZ),
    centerY,
    halfY: Math.max(0.05, halfY),
  };
}
