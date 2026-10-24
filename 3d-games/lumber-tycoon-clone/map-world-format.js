/**
 * Map storage document: `worlds[]` + `planetId` per world (P0.2).
 * Legacy flat `{ trees, props, plotSites, sellSpot, terrain }` normalizes to one world `home`.
 *
 * Bumped to v3 in this revision to add per-planet **planet properties**:
 * - `gravityStrength` (m/s² toward center; null = engine default)
 * - `atmosphere` (color, inner/outer radii, fog density)
 * - `biomes[]` (lat-band biomes with LT2 preset palette)
 * - `treeSpawn` (procedural-spawn settings)
 *
 * Older v2 documents normalize forward without losing data.
 */

export const MAP_DOCUMENT_VERSION = 3;

const HEX_RE = /^#[0-9a-fA-F]{6}$/;

/** @param {unknown} v @param {string} fallback */
function normalizeHex(v, fallback) {
  if (typeof v === 'string') {
    const s = v.trim();
    if (HEX_RE.test(s)) return s.toLowerCase();
  }
  return fallback;
}

/** @param {unknown} v @param {number} min @param {number} max @param {number} fb */
function clampNum(v, min, max, fb) {
  const n = Number(v);
  if (!Number.isFinite(n)) return fb;
  return Math.min(max, Math.max(min, n));
}

/**
 * Default biomes, ordered pole → equator. Lat is in degrees from –90 (south pole) to +90 (north pole).
 * Each biome can list LT2 preset ids — the in-game spawner picks one per tree slot weighted by
 * biome density. An empty list means no procedural trees spawn there.
 */
export const DEFAULT_BIOMES = Object.freeze([
  Object.freeze({ id: 'snow_n', label: 'Snow (north)', latMin: 65, latMax: 90, color: '#dfe7ee', presetIds: ['pine'], density: 0.6 }),
  Object.freeze({ id: 'forest', label: 'Forest', latMin: -65, latMax: 65, color: '#3d6b4a', presetIds: ['default', 'oak', 'cherry'], density: 1 }),
  Object.freeze({ id: 'beach_n', label: 'Beach (north)', latMin: 6, latMax: 18, color: '#e7d6a3', presetIds: ['palm'], density: 0.45 }),
  Object.freeze({ id: 'beach_s', label: 'Beach (south)', latMin: -18, latMax: -6, color: '#e7d6a3', presetIds: ['palm'], density: 0.45 }),
  Object.freeze({ id: 'snow_s', label: 'Snow (south)', latMin: -90, latMax: -65, color: '#dfe7ee', presetIds: ['pine'], density: 0.6 }),
]);

/**
 * @param {unknown} b
 * @param {number} idx
 * @returns {{ id: string; label: string; latMin: number; latMax: number; color: string; presetIds: string[]; density: number }}
 */
function ensureBiomeEntry(b, idx) {
  const src = b && typeof b === 'object' ? b : {};
  const id = typeof src.id === 'string' && src.id ? src.id : `biome_${idx}`;
  const label = typeof src.label === 'string' && src.label ? src.label : id;
  let latMin = clampNum(src.latMin, -90, 90, -90);
  let latMax = clampNum(src.latMax, -90, 90, 90);
  if (latMin > latMax) {
    const t = latMin;
    latMin = latMax;
    latMax = t;
  }
  const presetIds = Array.isArray(src.presetIds)
    ? src.presetIds.filter((x) => typeof x === 'string' && x).slice(0, 16)
    : [];
  return {
    id,
    label,
    latMin,
    latMax,
    color: normalizeHex(src.color, '#3d6b4a'),
    presetIds,
    density: clampNum(src.density, 0, 5, 1),
  };
}

/**
 * @param {unknown} a
 * @returns {{ enabled: boolean; color: string; thickness: number; fogDensity: number }}
 *   - `thickness` is meters above sphere surface where the atmosphere ends (outer shell).
 *     The fog-in zone is from `sphereRadius` (ground) to `sphereRadius + thickness` (space).
 *   - `fogDensity` is the exponential fog density used at sea level (gameplay).
 */
function ensureAtmosphere(a) {
  const src = a && typeof a === 'object' ? a : {};
  return {
    enabled: src.enabled !== false,
    color: normalizeHex(src.color, '#9ec7ff'),
    thickness: clampNum(src.thickness, 0, 10000, 350),
    fogDensity: clampNum(src.fogDensity, 0, 0.05, 0.0035),
  };
}

/**
 * @param {unknown} t
 * @returns {{ enabled: boolean; seed: number; targetCount: number }}
 *   - `targetCount` is how many procedural trees the planet **tries** to seed at load time.
 *     Per-biome density scales each biome's share; authored trees from `slice.trees` are
 *     spawned independently and not counted here.
 */
function ensureTreeSpawn(t) {
  const src = t && typeof t === 'object' ? t : {};
  const seed = Number.isFinite(src.seed) ? (src.seed | 0) : Math.floor(Math.random() * 1e9);
  /**
   * Default 1000 gives a "real forest" density. Perf budget is now:
   *   • physics: trunks only (no branch colliders for procedural trees → ~1 body each)
   *   • render:  procedural trees use merged bark + leaf geometry → ~2 draw calls each
   * So 1000 trees ≈ ~1000 static bodies + ~2000 draw calls for the crown + ~1000 trunk multi-
   * material meshes. Totally manageable on modern GPUs; users who want even denser can push the
   * slider up to 5000 via the universe editor's "Target tree count" field.
   */
  /**
   * Forward-migrate legacy defaults: earlier builds shipped defaults of exactly 120 or 350
   * which were set by previous versions of this loader on every load for worlds that never
   * had the editor's `treeSpawn.targetCount` explicitly touched. Bump only those two exact
   * legacy defaults up to the new default so existing worlds immediately get the denser
   * forest without having to nuke saves — and so legitimate user-chosen sparse values (like
   * 50) are NOT stomped on load.
   */
  const LEGACY_DEFAULTS = new Set([120, 350]);
  const NEW_DEFAULT = 2000;
  const MAX_COUNT = 50000;
  let rawCount = Number(src.targetCount);
  if (!Number.isFinite(rawCount)) rawCount = NEW_DEFAULT;
  if (src.__targetCountMigratedV1 !== true && LEGACY_DEFAULTS.has(rawCount)) {
    rawCount = NEW_DEFAULT;
  }
  /**
   * `clustering` — biases the Fibonacci-even distribution toward `K` pre-computed cluster
   * centers. 0 = evenly spread ("golf course"), 1 = heavily clustered ("dense forest with
   * clearings"). Applied BEFORE per-biome density rolls, so a clustering=0.7 forest band still
   * respects a beach's 0.4 density — clusters just happen more often inside forest spots.
   */
  const clusteringRaw = Number(src.clustering);
  const clustering = Number.isFinite(clusteringRaw) ? Math.max(0, Math.min(1, clusteringRaw)) : 0.25;
  const out = {
    enabled: src.enabled !== false,
    seed,
    targetCount: clampNum(rawCount, 0, MAX_COUNT, NEW_DEFAULT),
    clustering,
    __targetCountMigratedV1: true,
  };
  return out;
}

/**
 * Planet hub transform in **universe space** (editor + runtime offset for map content under `Planet_<id>`).
 * @param {unknown} p
 * @returns {{ x: number; y: number; z: number; rx: number; ry: number; rz: number; sx: number; sy: number; sz: number }}
 */
export function normalizeUniversePose(p) {
  const d = p && typeof p === 'object' ? p : {};
  const clampS = (s) => {
    const x = Number(s);
    if (!Number.isFinite(x)) return 1;
    return Math.min(80, Math.max(0.02, x));
  };
  return {
    x: Number(d.x) || 0,
    y: Number(d.y) || 0,
    z: Number(d.z) || 0,
    rx: Number(d.rx) || 0,
    ry: Number(d.ry) || 0,
    rz: Number(d.rz) || 0,
    sx: clampS(d.sx),
    sy: clampS(d.sy ?? d.sx),
    sz: clampS(d.sz ?? d.sx),
  };
}

/**
 * @param {unknown} w
 * @returns {{ planetId: string; label: string; sphereRadius: number; trees: unknown[]; props: unknown[]; plotSites: unknown[]; sellSpot: object; terrain: unknown }}
 */
function ensureWorldEntry(w) {
  const pid = typeof w?.planetId === 'string' && w.planetId ? w.planetId : 'home';
  /** @type {unknown[] | undefined} */
  let trees;
  if (w && 'trees' in w) {
    trees = Array.isArray(w.trees) ? w.trees : [];
  } else {
    trees = undefined;
  }
  const rawWd = w?.worldDef;
  const worldDef =
    rawWd && typeof rawWd === 'object' && !Array.isArray(rawWd) ? rawWd : {};

  /** Migrate / fill the new planet-property fields. `null` gravityStrength = engine default. */
  const gravityStrengthRaw = w?.gravityStrength;
  const gravityStrength =
    gravityStrengthRaw === null || gravityStrengthRaw === undefined
      ? null
      : clampNum(gravityStrengthRaw, 0, 200, 24);

  const biomesSrc = Array.isArray(w?.biomes) && w.biomes.length > 0
    ? w.biomes
    : DEFAULT_BIOMES;
  const biomes = biomesSrc.map((b, i) => ensureBiomeEntry(b, i));

  const surfaceColor = normalizeHex(w?.surfaceColor, '#3d6b4a');

  return {
    planetId: pid,
    label: typeof w?.label === 'string' ? w.label : 'Home',
    sphereRadius: typeof w?.sphereRadius === 'number' && w.sphereRadius > 0 ? w.sphereRadius : 2500,
    /** Default **true**: the game is designed as planet-first. Only opt-out by explicitly setting `useSpherePhysics: false`. */
    useSpherePhysics: !(w && w.useSpherePhysics === false),
    trees,
    props: Array.isArray(w?.props) ? w.props : [],
    plotSites: Array.isArray(w?.plotSites) ? w.plotSites : [],
    sellSpot: w?.sellSpot && typeof w.sellSpot === 'object' ? w.sellSpot : {},
    terrain: w?.terrain !== undefined ? w.terrain : null,
    worldDef,
    universePose: normalizeUniversePose(w?.universePose),
    /** New (v3) planet properties: */
    gravityStrength,
    surfaceColor,
    atmosphere: ensureAtmosphere(w?.atmosphere),
    biomes,
    treeSpawn: ensureTreeSpawn(w?.treeSpawn),
  };
}

/**
 * @param {unknown} input
 * @returns {{ mapFormatVersion: number; defaultPlanetId: string; worlds: ReturnType<typeof ensureWorldEntry>[] }}
 */
export function normalizeMapDocument(input) {
  if (!input || typeof input !== 'object') {
    return {
      mapFormatVersion: MAP_DOCUMENT_VERSION,
      defaultPlanetId: 'home',
      worlds: [ensureWorldEntry({ planetId: 'home', trees: [] })],
    };
  }
  if (Array.isArray(input.worlds) && input.worlds.length > 0) {
    const mapFormatVersion = Number(input.mapFormatVersion) || MAP_DOCUMENT_VERSION;
    const defaultPlanetId =
      typeof input.defaultPlanetId === 'string' && input.defaultPlanetId
        ? input.defaultPlanetId
        : ensureWorldEntry(input.worlds[0]).planetId;
    return {
      mapFormatVersion,
      defaultPlanetId,
      worlds: input.worlds.map((x) => ensureWorldEntry(x)),
    };
  }
  const props = Array.isArray(input.props) ? input.props : [];
  const plotSites = Array.isArray(input.plotSites) ? input.plotSites : [];
  const sellSpot = input.sellSpot && typeof input.sellSpot === 'object' ? input.sellSpot : {};
  const terrain = input.terrain !== undefined ? input.terrain : null;
  /** @type {Record<string, unknown>} */
  const legacyWorld = {
    planetId: 'home',
    label: 'Home',
    sphereRadius: typeof input.sphereRadius === 'number' && input.sphereRadius > 0 ? input.sphereRadius : 2500,
    props,
    plotSites,
    sellSpot,
    terrain,
  };
  if ('trees' in input) {
    legacyWorld.trees = Array.isArray(input.trees) ? input.trees : [];
  }
  if ('useSpherePhysics' in input) {
    legacyWorld.useSpherePhysics = !!input.useSpherePhysics;
  }
  return {
    mapFormatVersion: MAP_DOCUMENT_VERSION,
    defaultPlanetId: 'home',
    worlds: [ensureWorldEntry(legacyWorld)],
  };
}

/**
 * @param {{ mapFormatVersion?: number; defaultPlanetId?: string; worlds?: unknown[] } | null} doc
 * @param {string} [planetId]
 * @returns {{ trees: unknown[]; props: unknown[]; plotSites: unknown[]; sellSpot: object; terrain: unknown } | null}
 */
export function getWorldSliceFromDocument(doc, planetId) {
  if (!doc || !Array.isArray(doc.worlds) || doc.worlds.length === 0) return null;
  const pid = (typeof planetId === 'string' && planetId ? planetId : null) || doc.defaultPlanetId || 'home';
  const w = doc.worlds.find((x) => x && x.planetId === pid) || doc.worlds[0];
  if (!w) return null;
  const e = ensureWorldEntry(w);
  return {
    trees: e.trees,
    props: e.props,
    plotSites: e.plotSites,
    sellSpot: e.sellSpot,
    terrain: e.terrain,
    planetId: e.planetId,
    label: e.label,
    sphereRadius: e.sphereRadius,
    useSpherePhysics: e.useSpherePhysics,
    worldDef: e.worldDef,
    universePose: e.universePose,
    gravityStrength: e.gravityStrength,
    surfaceColor: e.surfaceColor,
    atmosphere: e.atmosphere,
    biomes: e.biomes,
    treeSpawn: e.treeSpawn,
  };
}

/**
 * Pure helper for placement code: pick a biome by latitude (degrees, –90..+90).
 * Returns the first biome whose `[latMin, latMax]` contains `latDeg`. If none match,
 * returns `null` (caller treats as "no procedural trees here").
 *
 * @param {number} latDeg
 * @param {Array<{ latMin: number; latMax: number }>} biomes
 * @returns {object | null}
 */
export function biomeForLatitude(latDeg, biomes) {
  if (!Number.isFinite(latDeg) || !Array.isArray(biomes)) return null;
  for (const b of biomes) {
    if (b && latDeg >= b.latMin && latDeg <= b.latMax) return b;
  }
  return null;
}

/**
 * @param {{ trees?: unknown[]; props?: unknown[]; plotSites?: unknown[]; sellSpot?: unknown; terrain?: unknown } | null} slice
 * @returns {boolean}
 */
export function mapSliceHasAuthoring(slice) {
  if (!slice || typeof slice !== 'object') return false;
  const treesOk = Array.isArray(slice.trees) && slice.trees.length > 0;
  return !!(
    treesOk ||
    (Array.isArray(slice.props) && slice.props.length) ||
    (Array.isArray(slice.plotSites) && slice.plotSites.length) ||
    (slice.sellSpot &&
      typeof slice.sellSpot === 'object' &&
      (typeof slice.sellSpot.x === 'number' || typeof slice.sellSpot.z === 'number')) ||
    slice.terrain
  );
}
