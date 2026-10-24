/**
 * ============================================================================
 *  Deterministic star catalog
 * ============================================================================
 *
 * Pure-logic module — no DOM, no THREE dependency. Given a 32-bit universe
 * seed + an optional list of hand-authored planet bookmarks, produces a
 * repeatable list of catalog stars that `game.js` renders as the night sky.
 *
 * Architectural contract (Phase 1 of the NMS-style universe work):
 *   - Every star has a stable `id` of the form `s-<layer>-<index>` so IDs
 *     survive across sessions even if the seed changes (the *content* of
 *     star `s-1-37` changes when reseeded; the *slot* does not).
 *   - Star positions are generated with a seeded `mulberry32` PRNG so the
 *     same seed always produces the same sky. No `Math.random()` anywhere
 *     in this file — that's the whole point.
 *   - Each star carries a `systemSeed` derived deterministically from
 *     `(universeSeed, layer, index)` via `hashCombine`. Phase 2 can use
 *     this to procedurally generate planets, colors, biomes per system
 *     without storing anything.
 *   - Bookmarks are hand-authored planets re-projected onto the celestial
 *     shell as unit directions from the world origin, so the player can
 *     look up and find their home planet's sun as a visible marker.
 *
 * Visual / gameplay intent:
 *   - Layer counts match the existing 3-layer starfield exactly (dust 6000,
 *     medium 2200, bright 350 = 8550 total) so visual density at default
 *     seed + density 1.0 is indistinguishable from the pre-refactor sky.
 *   - Spectral classes (O B A F G K M) use IRL-ish main-sequence rarity
 *     weights so most stars are dim red dwarfs + yellow suns, with the
 *     rare blue giant punching out — gives the sky visible color variety
 *     even before the user touches tint sliders.
 */

import { mulberry32 } from './tree-procedural-branches.js';

/** Layer sizes identical to the original `buildSpaceBackground` hard-coded
 *  counts. Preserved as a function so callers don't reach into a module-
 *  private constant — also cheap to tweak in one place if we ever want
 *  larger catalogs for Phase 2+. */
export function getLayerCounts() {
  return { dust: 6000, medium: 2200, bright: 350 };
}

/**
 * Classic 32-bit xorshift-style mixer. Combines two 32-bit inputs into a
 * new 32-bit value with good avalanche (single bit flip in either input
 * touches ~half the output bits). Used to derive `systemSeed` from
 * `(universeSeed, layer, index)` so reseeding the universe reshuffles
 * *every* star's contents atomically.
 */
export function hashCombine(a, b) {
  let x = (a ^ 0x9e3779b9) >>> 0;
  let y = (b ^ 0x85ebca6b) >>> 0;
  x = Math.imul(x ^ (x >>> 16), 0x7feb352d) >>> 0;
  x = (x + y) >>> 0;
  x = Math.imul(x ^ (x >>> 15), 0x846ca68b) >>> 0;
  x = (x ^ (x >>> 16)) >>> 0;
  return x;
}

/**
 * Main-sequence spectral class distribution (approximate IRL frequencies
 * clamped to a playable palette):
 *   M red dwarfs  76%  — dim red
 *   K orange      12%  — warm amber
 *   G yellow sun   8%  — our sun's class
 *   F yellow-white 3%
 *   A white        0.6%
 *   B blue         0.13%
 *   O blue giant   0.00003% but bumped to ~0.27% here so the sky gets a
 *                  visible sprinkle of hot blue stars (pure realism = 1
 *                  O-type in 3 million = zero on our 350-star bright layer)
 *
 * The pick is driven by a 0..1 uniform from the seeded RNG, so swapping
 * seeds gives a completely different (but still realistic-feeling) palette.
 */
export function spectralClassPick(rng01) {
  const r = rng01;
  if (r < 0.0027) return 'O';
  if (r < 0.0130) return 'B';
  if (r < 0.0190) return 'A';
  if (r < 0.0520) return 'F';
  if (r < 0.1320) return 'G';
  if (r < 0.2520) return 'K';
  return 'M';
}

/**
 * RGB tint (0..1 each) per spectral class, roughly matching real stellar
 * blackbody colors but biased slightly toward saturation so they read
 * clearly against the dark sky under additive blending.
 *
 * Returned as a 3-tuple so the caller can push straight into a Float32
 * color buffer without allocating THREE.Color objects per vertex.
 */
export function colorForSpectralClass(cls) {
  switch (cls) {
    case 'O': return [0.60, 0.72, 1.00];
    case 'B': return [0.72, 0.82, 1.00];
    case 'A': return [0.92, 0.94, 1.00];
    case 'F': return [1.00, 0.97, 0.90];
    case 'G': return [1.00, 0.93, 0.78];
    case 'K': return [1.00, 0.82, 0.64];
    case 'M': return [1.00, 0.65, 0.50];
    default:  return [1.00, 1.00, 1.00];
  }
}

/**
 * Base luminosity multiplier per spectral class, used so hot O/B stars
 * punch out as genuinely bright regardless of which render layer they
 * landed in. Layer still controls point SIZE; this influences COLOR
 * intensity (color[i] *= luminosity * brightness).
 */
export function luminosityForSpectralClass(cls) {
  switch (cls) {
    case 'O': return 1.0;
    case 'B': return 0.92;
    case 'A': return 0.85;
    case 'F': return 0.78;
    case 'G': return 0.72;
    case 'K': return 0.64;
    case 'M': return 0.55;
    default:  return 0.7;
  }
}

/**
 * Procedural star name: `<Prefix>-<n1>-<n2>`. Prefixes are short,
 * vaguely-astronomical-catalog-like three-letter codes so different seeds
 * feel like they belong to the same universe. Purely flavor text; not
 * used for identity (IDs are the layer-index strings).
 */
const NAME_PREFIXES = [
  'KEP', 'GLI', 'HIP', 'TRA', 'WOL', 'NOV', 'SIR', 'VEG',
  'POL', 'ALT', 'PRX', 'LYR', 'ARC', 'BET', 'CYG', 'ORI',
  'TAU', 'AUR', 'LUP', 'PYX', 'CAR', 'DRA', 'SER', 'VUL',
];
export function proceduralStarName(seed) {
  const rng = mulberry32(seed >>> 0);
  const pi = Math.floor(rng() * NAME_PREFIXES.length) % NAME_PREFIXES.length;
  const n1 = Math.floor(rng() * 9999).toString().padStart(4, '0');
  const n2 = Math.floor(rng() * 99).toString().padStart(2, '0');
  return `${NAME_PREFIXES[pi]}-${n1}-${n2}`;
}

/**
 * Produce a unit-vector direction uniformly distributed on the sphere.
 * Uses the same "Marsaglia acos / cos-theta" trick the old code used, but
 * sources its two uniforms from the *seeded* RNG so positions are
 * repeatable.
 */
function uniformDirection(rng) {
  const u = rng() * 2 - 1;
  const theta = rng() * Math.PI * 2;
  const s = Math.sqrt(Math.max(0, 1 - u * u));
  return [s * Math.cos(theta), u, s * Math.sin(theta)];
}

/**
 * Build one star catalog entry. Exposed so Phase 2 can re-derive a single
 * system on demand (from just `universeSeed + layer + index`) without
 * having to keep the whole catalog in memory.
 */
export function buildCatalogStar(universeSeed, layer, index, layerName) {
  /** Per-star RNG is seeded with the hash of (universeSeed, layer, index)
   *  so each slot is independent; advancing through the catalog linearly
   *  doesn't create visual patterns from PRNG state drift. */
  const slotSeed = hashCombine(
    hashCombine(universeSeed >>> 0, ((layer + 1) * 73856093) >>> 0),
    ((index + 1) * 19349663) >>> 0,
  );
  const rng = mulberry32(slotSeed);
  const dir = uniformDirection(rng);
  const cls = spectralClassPick(rng());
  const color = colorForSpectralClass(cls);
  const luminosity = luminosityForSpectralClass(cls);
  /** Magnitude is a blend of class luminosity and a per-star roll so
   *  layers have natural brightness variance. Values stay in 0..1. */
  const magnitude = Math.min(1, luminosity * (0.6 + rng() * 0.55));
  /** Size multiplier 0.7..1.4 — *renderer* picks the layer point size;
   *  this adds fine-grained per-star variation when we do per-point
   *  sizes later (Phase 2+). For Phase 1 the renderer just uses it as
   *  a color-brightness scaler for extra sparkle. */
  const size = 0.7 + rng() * 0.7;
  /** Phase 2 uses this to generate a procedural system from each star.
   *  Stays stable across sessions because it's a pure hash. */
  const systemSeed = hashCombine(slotSeed, 0xC0FFEE);
  /** 1..5 planets per system, heavily weighted toward small numbers —
   *  makes most star systems quick to explore, rare big ones exciting. */
  const hintRoll = rng();
  const planetCountHint =
    hintRoll < 0.45 ? 1 :
    hintRoll < 0.80 ? 2 :
    hintRoll < 0.93 ? 3 :
    hintRoll < 0.985 ? 4 : 5;
  const name = proceduralStarName(systemSeed);
  return {
    id: `s-${layer}-${index}`,
    kind: 'procedural',
    layer,
    layerName,
    index,
    dir,
    spectralClass: cls,
    color,
    magnitude,
    size,
    systemSeed,
    planetCountHint,
    name,
  };
}

/**
 * Normalize + validate the raw bookmark list coming from the map editor.
 * Each bookmark needs at minimum `planetId` and a direction we can unit-
 * normalize. Drops entries we can't use rather than throwing, so a
 * corrupt map save can't brick the star catalog build.
 */
function normalizeBookmarks(rawBookmarks) {
  const out = [];
  if (!Array.isArray(rawBookmarks)) return out;
  for (const b of rawBookmarks) {
    if (!b || typeof b !== 'object') continue;
    const planetId = typeof b.planetId === 'string' ? b.planetId : null;
    if (!planetId) continue;
    const dx = Number(b.dirX);
    const dy = Number(b.dirY);
    const dz = Number(b.dirZ);
    if (!Number.isFinite(dx) || !Number.isFinite(dy) || !Number.isFinite(dz)) continue;
    const len = Math.sqrt(dx * dx + dy * dy + dz * dz);
    if (len < 1e-6) continue;
    const dir = [dx / len, dy / len, dz / len];
    const label = typeof b.label === 'string' && b.label.trim() ? b.label.trim() : planetId;
    /** Bookmarks use a distinct color so the player can spot them at a
     *  glance — we reserve a saturated cyan that doesn't appear naturally
     *  in the M-dominated procedural palette. Phase 2 will let the player
     *  override per-planet bookmark colors. */
    const color = Array.isArray(b.color) && b.color.length === 3
      ? [Number(b.color[0]) || 0, Number(b.color[1]) || 0, Number(b.color[2]) || 0]
      : [0.65, 0.95, 1.0];
    out.push({
      id: `bm-${planetId}`,
      kind: 'bookmark',
      planetId,
      label,
      dir,
      color,
      magnitude: 1.0,
      size: 1.5,
    });
  }
  return out;
}

/**
 * Build the full catalog for the current session.
 *
 * @param {number} universeSeed - 32-bit integer seed.
 * @param {Array<{planetId, label?, dirX, dirY, dirZ, color?}>} [rawBookmarks]
 *        Optional list of hand-authored planet bookmarks to mix into the
 *        catalog. Directions should be unit vectors; we re-normalize
 *        defensively.
 *
 * @returns {{
 *   stars: Array, bookmarks: Array, byId: Map<string, object>,
 *   metadata: { universeSeed, counts, generatedAt }
 * }}
 */
export function generateStarCatalog(universeSeed, rawBookmarks) {
  const seed = (Number.isFinite(universeSeed) ? Math.floor(universeSeed) : 0) >>> 0;
  const counts = getLayerCounts();
  const layerNames = ['dust', 'medium', 'bright'];
  const layerSizes = [counts.dust, counts.medium, counts.bright];
  const stars = [];
  for (let layer = 0; layer < layerSizes.length; layer++) {
    const n = layerSizes[layer];
    for (let i = 0; i < n; i++) {
      stars.push(buildCatalogStar(seed, layer, i, layerNames[layer]));
    }
  }
  const bookmarks = normalizeBookmarks(rawBookmarks);
  /** Lookup map combines both kinds under one `byId`. Collisions shouldn't
   *  happen (different prefixes) but we guard with `has()` anyway. */
  const byId = new Map();
  for (const s of stars) if (!byId.has(s.id)) byId.set(s.id, s);
  for (const b of bookmarks) if (!byId.has(b.id)) byId.set(b.id, b);
  return {
    stars,
    bookmarks,
    byId,
    metadata: {
      universeSeed: seed,
      counts,
      generatedAt: Date.now(),
    },
  };
}

/**
 * Find the catalog entry (star or bookmark) whose direction is closest to
 * `dir`. Uses cosine of the angle between unit vectors (dot product) so
 * "closest" means "most aligned", not "smallest Euclidean distance".
 *
 * `cosThresh` defaults to ~0.99995 (angular threshold of ~0.6°, roughly
 * crosshair width at 60° FOV). A lower cosThresh = wider cone = more
 * permissive targeting. Returns null if nothing is close enough, which
 * the caller can surface as "no star in aim" in the Phase 2 reticle UI.
 *
 * @param {ReturnType<typeof generateStarCatalog>} catalog
 * @param {{x:number,y:number,z:number} | [number,number,number]} dir
 *        Unit vector direction (camera forward). We do NOT re-normalize;
 *        callers must pass a unit vector.
 * @param {number} [cosThresh=0.99995]
 */
export function findNearestStarInDirection(catalog, dir, cosThresh = 0.99995) {
  if (!catalog) return null;
  const dx = Array.isArray(dir) ? dir[0] : dir?.x;
  const dy = Array.isArray(dir) ? dir[1] : dir?.y;
  const dz = Array.isArray(dir) ? dir[2] : dir?.z;
  if (!Number.isFinite(dx) || !Number.isFinite(dy) || !Number.isFinite(dz)) return null;
  let bestDot = -Infinity;
  let bestEntry = null;
  const scan = (arr) => {
    for (let i = 0; i < arr.length; i++) {
      const s = arr[i];
      const d = s.dir;
      const dot = d[0] * dx + d[1] * dy + d[2] * dz;
      if (dot > bestDot) {
        bestDot = dot;
        bestEntry = s;
      }
    }
  };
  /** Scan bookmarks first so a tie between a bookmark + a procedural star
   *  at the same direction breaks toward the bookmark. */
  scan(catalog.bookmarks);
  scan(catalog.stars);
  if (bestDot < cosThresh) return null;
  return { entry: bestEntry, dot: bestDot, angleRad: Math.acos(Math.max(-1, Math.min(1, bestDot))) };
}
