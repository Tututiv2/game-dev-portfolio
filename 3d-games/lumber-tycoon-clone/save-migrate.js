/**
 * Save file versioning (P3.1 / P3.2). Legacy flat saves migrate to nested `worlds[planetId]`
 * plus `poses[planetId]` and global economy fields.
 *
 * **Pose space (P3 design note):** `poses[planetId]` stores **world-space Cartesian** position and
 * yaw/pitch only. Spherical play uses the same numeric space (meters from origin); no separate
 * lat/lon save field in v2. A future migration can add optional `{ lat, lon, height }` if needed.
 */

export const SAVE_FORMAT_VERSION = 2;

/**
 * @param {unknown} slice
 * @returns {{ trees: unknown[]; logs: unknown[]; sawmills: unknown[]; plotBlueprints: unknown[] }}
 */
function normalizeWorldDynamicsSlice(slice) {
  if (!slice || typeof slice !== 'object') {
    return { trees: [], logs: [], sawmills: [], plotBlueprints: [] };
  }
  return {
    trees: Array.isArray(slice.trees) ? slice.trees : [],
    logs: Array.isArray(slice.logs) ? slice.logs : [],
    sawmills: Array.isArray(slice.sawmills) ? slice.sawmills : [],
    plotBlueprints: Array.isArray(slice.plotBlueprints) ? slice.plotBlueprints : [],
  };
}

/**
 * @param {Record<string, unknown>} data
 */
function normalizeV2Document(data) {
  /** @type {Record<string, ReturnType<typeof normalizeWorldDynamicsSlice>>} */
  const worlds = {};
  const src = data.worlds && typeof data.worlds === 'object' && !Array.isArray(data.worlds) ? data.worlds : null;
  if (src) {
    for (const k of Object.keys(src)) {
      if (!k) continue;
      worlds[k] = normalizeWorldDynamicsSlice(/** @type {object} */ (src[k]));
    }
  }
  if (Object.keys(worlds).length === 0) {
    worlds.home = normalizeWorldDynamicsSlice({
      trees: data.trees,
      logs: data.logs,
      sawmills: data.sawmills,
      plotBlueprints: data.plotBlueprints,
    });
  }

  /** @type {Record<string, { x?: number; y?: number; z?: number; yaw?: number; pitch?: number }>} */
  const poses = {};
  const ps = data.poses && typeof data.poses === 'object' && !Array.isArray(data.poses) ? data.poses : null;
  if (ps) {
    for (const k of Object.keys(ps)) {
      const p = ps[k];
      if (p && typeof p === 'object') poses[k] = { ...p };
    }
  }
  if (Object.keys(poses).length === 0 && data.player && typeof data.player === 'object') {
    const pl = data.player;
    poses.home = {
      x: pl.x,
      y: pl.y,
      z: pl.z,
      yaw: pl.yaw,
      pitch: pl.pitch,
    };
  }

  return {
    saveFormatVersion: SAVE_FORMAT_VERSION,
    money: data.money ?? 0,
    landPlots: data.landPlots ?? 0,
    landPlotId: data.landPlotId,
    landPlotSlotId: data.landPlotSlotId,
    nextNetId: data.nextNetId ?? 1,
    nextTreeId: data.nextTreeId ?? 1,
    hotbar: data.hotbar,
    hotbarSel: data.hotbarSel,
    currentPlanetId:
      typeof data.currentPlanetId === 'string' && data.currentPlanetId ? data.currentPlanetId : 'home',
    worlds,
    poses,
  };
}

/**
 * Normalize legacy flat save or repair partial v2 into the v2 shape used by `game.js`.
 * @param {unknown} data
 * @returns {ReturnType<typeof normalizeV2Document> | null}
 */
export function migrateSaveToV2(data) {
  if (!data || typeof data !== 'object') return null;
  const v = Number(/** @type {Record<string, unknown>} */ (data).saveFormatVersion);
  if (v >= 2) {
    return normalizeV2Document(/** @type {Record<string, unknown>} */ (data));
  }
  /** @type {Record<string, unknown>} */
  const flat = { .../** @type {Record<string, unknown>} */ (data) };
  flat.saveFormatVersion = SAVE_FORMAT_VERSION;
  flat.currentPlanetId = 'home';
  flat.worlds = {
    home: normalizeWorldDynamicsSlice({
      trees: flat.trees,
      logs: flat.logs,
      sawmills: flat.sawmills,
      plotBlueprints: flat.plotBlueprints,
    }),
  };
  if (flat.player) {
    flat.poses = { home: { .../** @type {object} */ (flat.player) } };
  } else {
    flat.poses = {};
  }
  delete flat.trees;
  delete flat.logs;
  delete flat.sawmills;
  delete flat.plotBlueprints;
  delete flat.player;
  return normalizeV2Document(flat);
}

