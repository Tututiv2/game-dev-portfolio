/**
 * Live-tunable config for the LT2-style tree generator, stored as named presets.
 *
 * Storage shape in `localStorage['LT2_TreePresets']`:
 * ```
 * { activeId: string, order: string[], items: { [id]: { name, cfg } } }
 * ```
 * Back-compat: legacy single-object `localStorage['LT2_TreeConfig']` is migrated
 * into `items.default` on first read.
 *
 * Defaults match the previous hardcoded LT2 branch constants so the visible
 * output is unchanged until the user moves a slider.
 */

const LT2_PRESETS_KEY = 'LT2_TreePresets';
const LT2_LEGACY_KEY = 'LT2_TreeConfig';
/** ~Roblox stud to meters; LT2 trunk in the script is 1×8×1 studs. */
export const LT2_STUD = 0.28;

/** Allowed values for `interiorTexture` — each maps to a canvas-generated pattern. */
export const LT2_INTERIOR_TEXTURES = Object.freeze([
  'solid',
  'rings',
  'grain',
  'checker',
  'planks',
]);

/** Canvas-generated bark side patterns (`stock` = keep the shared PNG). */
export const LT2_TRUNK_SIDE_TEXTURES = Object.freeze([
  'stock',
  'smooth',
  'grain',
  'bark',
  'rings',
]);

/** Canvas-generated leaf-pad patterns (`solid` = plain flat color, no map). */
export const LT2_LEAF_TEXTURES = Object.freeze([
  'solid',
  'grass',
  'leafy',
  'dense',
  'speckle',
]);

export const LT2_TREE_DEFAULTS = Object.freeze({
  trunkHeight: 8 * LT2_STUD,
  trunkThickness: 0.5 * LT2_STUD,
  /** White = no tint (use the stock bark texture as-is). Any other hex multiplies over the map. */
  trunkColor: '#ffffff',
  trunkTexture: 'stock',
  interiorColor: '#9a6a3d',
  interiorNeon: false,
  interiorBorder: false,
  interiorBorderThickness: 0.06,
  interiorBorderColor: '#3a2516',
  interiorTexture: 'solid',

  mainCount: 3,
  mainThickness: 1,
  mainLength: 1,
  mainAngleJitterDeg: 40,
  mainPitchDeg: 25,
  mainPitchJitterDeg: 12,

  subMin: 2,
  subMax: 3,
  subThickness: 1,
  subLength: 1,
  subAngleJitterDeg: 35,
  subPitchDeg: 35,
  subPitchJitterDeg: 14,

  leavesPerSub: 2,
  leafPadSize: 1,
  leafColor: '#73d178',
  leafTexture: 'solid',

  woodWeight: 1,
});

function clampNum(v, lo, hi, fallback) {
  const n = Number(v);
  if (!Number.isFinite(n)) return fallback;
  return Math.min(hi, Math.max(lo, n));
}

function clampInt(v, lo, hi, fallback) {
  return Math.round(clampNum(v, lo, hi, fallback));
}

function normalizeHexColor(v, fallback) {
  if (typeof v !== 'string') return fallback;
  const s = v.trim();
  if (!/^#[0-9a-fA-F]{6}$/.test(s)) return fallback;
  return s.toLowerCase();
}

/** Clamp every field; ignore unknown keys. Always returns a full config. */
export function normalizeLt2Config(raw) {
  const d = LT2_TREE_DEFAULTS;
  const o = raw && typeof raw === 'object' ? raw : {};
  let subMin = clampInt(o.subMin, 1, 5, d.subMin);
  let subMax = clampInt(o.subMax, 1, 5, d.subMax);
  if (subMax < subMin) [subMin, subMax] = [subMax, subMin];
  return {
    trunkHeight: clampNum(o.trunkHeight, 0.5, 12, d.trunkHeight),
    trunkThickness: clampNum(o.trunkThickness, 0.04, 0.9, d.trunkThickness),
    trunkColor: normalizeHexColor(o.trunkColor, d.trunkColor),
    trunkTexture: LT2_TRUNK_SIDE_TEXTURES.includes(o.trunkTexture) ? o.trunkTexture : d.trunkTexture,
    interiorColor: normalizeHexColor(o.interiorColor, d.interiorColor),
    interiorNeon: !!o.interiorNeon,
    interiorBorder: !!o.interiorBorder,
    interiorBorderThickness: clampNum(o.interiorBorderThickness, 0, 0.45, d.interiorBorderThickness),
    interiorBorderColor: normalizeHexColor(o.interiorBorderColor, d.interiorBorderColor),
    interiorTexture: LT2_INTERIOR_TEXTURES.includes(o.interiorTexture) ? o.interiorTexture : d.interiorTexture,
    mainCount: clampInt(o.mainCount, 2, 6, d.mainCount),
    mainThickness: clampNum(o.mainThickness, 0.25, 3, d.mainThickness),
    mainLength: clampNum(o.mainLength, 0.25, 3, d.mainLength),
    mainAngleJitterDeg: clampNum(o.mainAngleJitterDeg, 0, 120, d.mainAngleJitterDeg),
    mainPitchDeg: clampNum(o.mainPitchDeg, -20, 85, d.mainPitchDeg),
    mainPitchJitterDeg: clampNum(o.mainPitchJitterDeg, 0, 60, d.mainPitchJitterDeg),
    subMin,
    subMax,
    subThickness: clampNum(o.subThickness, 0.25, 3, d.subThickness),
    subLength: clampNum(o.subLength, 0.25, 3, d.subLength),
    subAngleJitterDeg: clampNum(o.subAngleJitterDeg, 0, 120, d.subAngleJitterDeg),
    subPitchDeg: clampNum(o.subPitchDeg, -20, 85, d.subPitchDeg),
    subPitchJitterDeg: clampNum(o.subPitchJitterDeg, 0, 60, d.subPitchJitterDeg),
    leavesPerSub: clampInt(o.leavesPerSub, 0, 4, d.leavesPerSub),
    leafPadSize: clampNum(o.leafPadSize, 0.25, 3, d.leafPadSize),
    leafColor: normalizeHexColor(o.leafColor, d.leafColor),
    leafTexture: LT2_LEAF_TEXTURES.includes(o.leafTexture) ? o.leafTexture : d.leafTexture,
    woodWeight: clampNum(o.woodWeight, 0.25, 3, d.woodWeight),
  };
}

/**
 * Bump whenever {@link LT2_BUILTIN_PRESETS} changes so existing stored bundles
 * pick up the new values (see reseed logic in {@link normalizePresets}).
 */
const BUILTINS_VERSION = 4;

/**
 * Library of built-in trees. Most presets stay close to {@link LT2_TREE_DEFAULTS}
 * so they look natural; per-tree personality is achieved mostly via colors,
 * thickness bumps, and small pitch/height variations. Palm is intentionally the
 * outlier (very tall, very thin trunk, big fronds).
 *
 * Partial cfgs merge over defaults then normalize, so adding new fields later
 * doesn't invalidate older presets.
 */
const LT2_BUILTIN_PRESETS = [
  {
    id: 'default',
    name: 'Default',
    cfg: {
      trunkTexture: 'grain',
      leafTexture: 'grass',
    },
  },
  {
    id: 'palm',
    name: 'Palm',
    cfg: {
      trunkHeight: 10,
      trunkThickness: 0.26,
      trunkColor: '#c2a070',
      trunkTexture: 'rings',
      leafTexture: 'leafy',
      interiorColor: '#caa069',
      interiorTexture: 'rings',
      mainCount: 6,
      mainThickness: 0.4,
      mainLength: 1.6,
      mainAngleJitterDeg: 25,
      mainPitchDeg: 40,
      mainPitchJitterDeg: 22,
      subMin: 1,
      subMax: 1,
      subThickness: 0.3,
      subLength: 0.9,
      subAngleJitterDeg: 20,
      subPitchDeg: -5,
      subPitchJitterDeg: 18,
      leavesPerSub: 3,
      leafPadSize: 2.3,
      leafColor: '#6fb14a',
      woodWeight: 0.75,
    },
  },
  {
    id: 'pine',
    name: 'Pine',
    cfg: {
      trunkHeight: 2.8,
      trunkThickness: 0.18,
      trunkColor: '#7a5a3a',
      trunkTexture: 'bark',
      leafTexture: 'dense',
      interiorColor: '#a0744a',
      interiorTexture: 'rings',
      mainCount: 4,
      mainThickness: 1.2,
      mainLength: 0.95,
      mainPitchDeg: 18,
      subThickness: 1.1,
      subPitchDeg: 22,
      leafColor: '#2d5c2d',
    },
  },
  {
    id: 'oak',
    name: 'Oak',
    cfg: {
      trunkHeight: 2.4,
      trunkThickness: 0.2,
      trunkColor: '#6b4a2a',
      trunkTexture: 'grain',
      leafTexture: 'grass',
      interiorColor: '#a9794a',
      interiorTexture: 'rings',
      mainThickness: 1.3,
      mainLength: 1.05,
      mainPitchDeg: 30,
      subThickness: 1.2,
      subPitchDeg: 32,
      leafColor: '#4a8a3a',
      leafPadSize: 1.15,
      woodWeight: 1.2,
    },
  },
  {
    id: 'cherry',
    name: 'Cherry blossom',
    cfg: {
      trunkHeight: 2.1,
      trunkThickness: 0.14,
      trunkColor: '#5a3a24',
      trunkTexture: 'bark',
      leafTexture: 'leafy',
      interiorColor: '#8b4a3a',
      interiorTexture: 'grain',
      mainThickness: 1.1,
      mainPitchDeg: 40,
      subPitchDeg: 45,
      leafColor: '#f3b7d1',
      leafPadSize: 1.1,
      woodWeight: 0.9,
    },
  },
];

function buildBuiltinItem(spec) {
  return {
    name: spec.name,
    cfg: normalizeLt2Config({ ...LT2_TREE_DEFAULTS, ...spec.cfg }),
  };
}

function defaultPresetBundle() {
  const items = {};
  const order = [];
  for (const spec of LT2_BUILTIN_PRESETS) {
    items[spec.id] = buildBuiltinItem(spec);
    order.push(spec.id);
  }
  return {
    activeId: order[0],
    order,
    items,
    _builtinsSeeded: true,
    _builtinsVersion: BUILTINS_VERSION,
  };
}

function slugify(name, taken) {
  const base = String(name || '')
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 28) || 'preset';
  let id = base;
  let i = 2;
  while (taken.has(id)) id = `${base}_${i++}`;
  return id;
}

function readRawPresets() {
  try {
    const raw = localStorage.getItem(LT2_PRESETS_KEY);
    if (raw) {
      const parsed = JSON.parse(raw);
      if (parsed && typeof parsed === 'object' && parsed.items && typeof parsed.items === 'object') {
        return parsed;
      }
    }
  } catch (_) {}

  try {
    const legacy = localStorage.getItem(LT2_LEGACY_KEY);
    if (legacy) {
      const cfg = normalizeLt2Config(JSON.parse(legacy));
      const bundle = {
        activeId: 'default',
        order: ['default'],
        items: { default: { name: 'Default', cfg } },
      };
      try {
        localStorage.setItem(LT2_PRESETS_KEY, JSON.stringify(bundle));
        localStorage.removeItem(LT2_LEGACY_KEY);
      } catch (_) {}
      return bundle;
    }
  } catch (_) {}

  return null;
}

/** Always returns a well-formed bundle, never null. */
function normalizePresets(bundle) {
  if (!bundle || typeof bundle !== 'object') return defaultPresetBundle();
  const items = bundle.items && typeof bundle.items === 'object' ? bundle.items : {};
  let order = Array.isArray(bundle.order) ? bundle.order.filter((id) => items[id]) : [];
  const cleanItems = {};
  for (const id of Object.keys(items)) {
    const it = items[id];
    if (!it || typeof it !== 'object') continue;
    cleanItems[id] = {
      name: typeof it.name === 'string' && it.name.trim() ? it.name.trim().slice(0, 48) : id,
      cfg: normalizeLt2Config(it.cfg),
    };
  }
  for (const id of Object.keys(cleanItems)) {
    if (!order.includes(id)) order.push(id);
  }

  /**
   * Seed (or re-seed) built-in presets when the stored bundle is older than
   * {@link BUILTINS_VERSION}. Re-seeding overwrites the built-in slots (any
   * local tweaks to a builtin are lost) but leaves user-created presets alone.
   * After first seed, only a version bump triggers another refresh.
   */
  const seededVersion = Number(bundle._builtinsVersion) || 0;
  if (seededVersion < BUILTINS_VERSION) {
    for (const spec of LT2_BUILTIN_PRESETS) {
      cleanItems[spec.id] = buildBuiltinItem(spec);
      if (!order.includes(spec.id)) order.push(spec.id);
    }
  }

  if (!order.length) {
    cleanItems.default = { name: 'Default', cfg: normalizeLt2Config(null) };
    order = ['default'];
  }
  let activeId = typeof bundle.activeId === 'string' && cleanItems[bundle.activeId] ? bundle.activeId : order[0];
  return {
    activeId,
    order,
    items: cleanItems,
    _builtinsSeeded: true,
    _builtinsVersion: BUILTINS_VERSION,
  };
}

function readPresets() {
  return normalizePresets(readRawPresets());
}

function writePresets(bundle) {
  try {
    localStorage.setItem(LT2_PRESETS_KEY, JSON.stringify(normalizePresets(bundle)));
  } catch (_) {}
}

/** Active-preset config (what game.js reads at spawn time). */
export function loadLt2Config() {
  const bundle = readPresets();
  return normalizeLt2Config(bundle.items[bundle.activeId]?.cfg);
}

/**
 * Return the normalized config for a **specific preset id** (e.g. `'pine'`, `'palm'`, `'oak'`).
 * Used by the biome-aware procedural spawner so different biomes on the same planet can spawn
 * trees with different shapes/colors side-by-side without swapping the active preset.
 * Falls back to the active preset's config if the id doesn't exist — that way a biome that
 * references a deleted preset still places *something* visible instead of nothing.
 */
export function loadLt2ConfigForPreset(id) {
  const bundle = readPresets();
  const pick = (typeof id === 'string' && id && bundle.items[id]) ? bundle.items[id].cfg : null;
  if (pick) return normalizeLt2Config(pick);
  return normalizeLt2Config(bundle.items[bundle.activeId]?.cfg);
}

/** Overwrites the active preset's config and persists. */
export function saveLt2Config(cfg) {
  const bundle = readPresets();
  const active = bundle.items[bundle.activeId];
  if (!active) return;
  active.cfg = normalizeLt2Config(cfg);
  writePresets(bundle);
}

/** Replaces the active preset's config with defaults (kept for UI reset button). */
export function resetLt2Config() {
  const fresh = normalizeLt2Config(null);
  saveLt2Config(fresh);
  return fresh;
}

export function listLt2Presets() {
  const bundle = readPresets();
  return bundle.order.map((id) => ({ id, name: bundle.items[id].name }));
}

export function getActiveLt2PresetId() {
  return readPresets().activeId;
}

export function setActiveLt2PresetId(id) {
  const bundle = readPresets();
  if (!bundle.items[id]) return false;
  bundle.activeId = id;
  writePresets(bundle);
  return true;
}

/**
 * Create a new preset. New preset clones the active preset's config, so users
 * can branch from a tuned starting point.
 */
export function createLt2Preset(name) {
  const bundle = readPresets();
  const id = slugify(name || `Preset ${bundle.order.length + 1}`, new Set(bundle.order));
  const sourceCfg = bundle.items[bundle.activeId]?.cfg;
  bundle.items[id] = {
    name: (name || id).trim().slice(0, 48) || id,
    cfg: normalizeLt2Config(sourceCfg),
  };
  bundle.order.push(id);
  bundle.activeId = id;
  writePresets(bundle);
  return id;
}

export function deleteLt2Preset(id) {
  const bundle = readPresets();
  if (!bundle.items[id]) return false;
  if (bundle.order.length <= 1) return false;
  delete bundle.items[id];
  bundle.order = bundle.order.filter((x) => x !== id);
  if (bundle.activeId === id) bundle.activeId = bundle.order[0];
  writePresets(bundle);
  return true;
}

export function renameLt2Preset(id, name) {
  const bundle = readPresets();
  if (!bundle.items[id]) return false;
  const clean = (name || '').trim().slice(0, 48);
  if (!clean) return false;
  bundle.items[id].name = clean;
  writePresets(bundle);
  return true;
}
