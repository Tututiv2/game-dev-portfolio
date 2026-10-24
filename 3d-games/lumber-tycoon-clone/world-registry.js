/**
 * Multi-planet registry (P0). Owns active `planetId` and per-planet Three.js roots + optional unload hooks.
 * @typedef {{ id: string, threeRoot: import('three').Group, unload?: () => void }} PlanetEntry
 */

export function createWorldRegistry() {
  /** @type {Map<string, PlanetEntry>} */
  const planets = new Map();
  /** @type {string} */
  let activePlanetId = '';

  /**
   * @param {{ id: string, threeRoot: import('three').Group, unload?: () => void }} entry
   */
  function registerPlanet(entry) {
    const id = entry?.id;
    if (typeof id !== 'string' || !id) throw new Error('world-registry: registerPlanet requires string id');
    if (!entry?.threeRoot) throw new Error('world-registry: registerPlanet requires threeRoot');
    planets.set(id, { id, threeRoot: entry.threeRoot, unload: entry.unload });
    if (!activePlanetId) activePlanetId = id;
  }

  function getActivePlanetId() {
    return activePlanetId;
  }

  /**
   * @param {string} id
   * @returns {boolean}
   */
  function setActivePlanetId(id) {
    if (typeof id !== 'string' || !id) return false;
    if (!planets.has(id)) return false;
    activePlanetId = id;
    return true;
  }

  /**
   * @param {string} planetId
   * @returns {import('three').Group | null}
   */
  function getPlanetRoot(planetId) {
    return planets.get(planetId)?.threeRoot ?? null;
  }

  /**
   * @param {string} planetId
   */
  function unloadPlanet(planetId) {
    const p = planets.get(planetId);
    if (!p) return;
    try {
      if (typeof p.unload === 'function') p.unload();
    } catch (_) {}
  }

  return {
    registerPlanet,
    getActivePlanetId,
    setActivePlanetId,
    getPlanetRoot,
    unloadPlanet,
  };
}
