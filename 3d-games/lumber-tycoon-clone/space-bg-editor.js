/**
 * ============================================================================
 *  Space Background live editor
 * ============================================================================
 *
 * Gives the player a floating panel (bottom-left "Space BG" button) that
 * exposes every knob of the procedural sky built by `buildSpaceBackground`
 * in `game.js` — sliders for density/size, color pickers for every palette
 * slot, direction vectors for where the galaxy hangs, and a preset library
 * for one-click looks.
 *
 * Design:
 *   - Config lives in a single flat-ish object accessed via {@link getSpaceBgConfig}.
 *   - `game.js` reads the config inside `buildSpaceBackground()` so any time
 *     we dispose + rebuild the background, it picks up the latest values.
 *   - UI mutates the config + calls subscribed listeners; `game.js` registers
 *     one listener that disposes + rebuilds the background. Rebuild is
 *     debounced (120 ms) so dragging a slider doesn't allocate megabytes of
 *     buffer geometry per frame.
 *   - Everything persists to `localStorage` so your tuned sky survives across
 *     sessions.
 *
 * This module renders NO THREE objects — it only owns configuration + DOM.
 * All rendering lives in `game.js`'s `buildSpaceBackground()`.
 */

const LS_KEY = 'LT2_SpaceBgConfig_v1';

/**
 * Default config — the "original spiral galaxy" look. Shipping identical
 * numbers to what `buildSpaceBackground()` used to hard-code, so the user
 * sees no visual change on first load; all knobs show current values.
 */
export function defaultSpaceBgConfig() {
  return {
    /** Universe-wide deterministic parameters. `seed` drives the star
     *  catalog generator in `star-catalog.js` — same seed = identical sky,
     *  every star at the same position with the same spectral class and
     *  `systemSeed`. Phase 2+ will use this to procedurally build planet
     *  systems on warp without storing any mesh data. */
    universe: {
      seed: 424242,
    },
    sky: {
      bg: '#01020a',
    },
    stars: {
      density: 1.0,
      size: 1.0,
      tintDust: '#e8ebff',
      tintMid: '#fff1d9',
      tintBright: '#ffdec4',
    },
    nebula: {
      color1: '#3a2a7a',
      color2: '#7a2a5a',
      intensity: 1.0,
    },
    /** Featured "nebula cloud" — a big patchy emission nebula hanging in a
     *  fixed patch of the sky (opposite the galaxy by default). Rendered as
     *  ~1200 soft colored point sprites in a 3-D gaussian cluster, with a
     *  radial color gradient from `colorCore` at the center through
     *  `colorMid` out to `colorEdge`. Gives the sky a second major feature
     *  so the galaxy isn't the only thing to look at. */
    nebulaCloud: {
      enabled: true,
      dirX: -0.72,
      dirY: 0.28,
      dirZ: 0.62,
      size: 2200,
      density: 1400,
      colorCore: '#ff77d9',
      colorMid: '#a66bff',
      colorEdge: '#3a62ff',
      intensity: 1.0,
    },
    galaxy: {
      enabled: true,
      outerR: 3500,
      bulgeR: 820,
      blackHoleR: 170,
      armCount: 4,
      spiralTightness: 0.78,
      armStars: 14000,
      bulgeStars: 4500,
      haloColor: '#2e2418',
      haloIntensity: 1.0,
      armColorInner: '#ffeaba',
      armColorOuter: '#b3a6ff',
      armAccent1: '#ff597f',
      armAccent2: '#f28e59',
      accretionInner: '#ffd480',
      accretionOuter: '#661a00',
      photonColor: '#fff2e0',
      dirX: 0.62,
      dirY: 0.48,
      dirZ: -0.62,
      normX: 0.28,
      normY: 0.88,
      normZ: 0.38,
    },
  };
}

/**
 * Preset library — "packaged" looks the player can switch between. Each
 * preset overrides a subset of the default config; applying a preset deep-
 * merges over the defaults so unspecified knobs reset to sensible values.
 */
export const SPACE_BG_PRESETS = {
  default: {
    label: 'Default spiral',
    patch: () => defaultSpaceBgConfig(),
  },
  aurora: {
    label: 'Aurora green',
    patch: () => ({
      sky: { bg: '#02060a' },
      stars: {
        density: 1.1,
        size: 1.0,
        tintDust: '#c6f5d9',
        tintMid: '#d4ffe1',
        tintBright: '#a8ffbe',
      },
      nebula: {
        color1: '#15634a',
        color2: '#1f8873',
        intensity: 1.6,
      },
      nebulaCloud: {
        colorCore: '#a0ffdc',
        colorMid: '#3aff9a',
        colorEdge: '#005c6a',
        intensity: 1.2,
      },
      galaxy: {
        enabled: true,
        outerR: 3600,
        bulgeR: 860,
        blackHoleR: 180,
        armCount: 4,
        spiralTightness: 0.72,
        armStars: 14000,
        bulgeStars: 4500,
        haloColor: '#0d2a24',
        haloIntensity: 1.2,
        armColorInner: '#bcffdd',
        armColorOuter: '#4fe3a1',
        armAccent1: '#88ffe0',
        armAccent2: '#00a36a',
        accretionInner: '#7dffc5',
        accretionOuter: '#0a3d30',
        photonColor: '#e1fff2',
        dirX: 0.62, dirY: 0.48, dirZ: -0.62,
        normX: 0.28, normY: 0.88, normZ: 0.38,
      },
    }),
  },
  sunsetVoid: {
    label: 'Sunset void',
    patch: () => ({
      sky: { bg: '#080306' },
      stars: {
        density: 0.85,
        size: 1.1,
        tintDust: '#ffd9c8',
        tintMid: '#ffb785',
        tintBright: '#ffa066',
      },
      nebula: {
        color1: '#6a1a4a',
        color2: '#c94b1a',
        intensity: 1.4,
      },
      nebulaCloud: {
        colorCore: '#ffc97a',
        colorMid: '#ff5a2a',
        colorEdge: '#4a0a28',
        intensity: 1.3,
      },
      galaxy: {
        enabled: true,
        outerR: 3400,
        bulgeR: 820,
        blackHoleR: 200,
        armCount: 3,
        spiralTightness: 0.9,
        armStars: 13000,
        bulgeStars: 5000,
        haloColor: '#3a1005',
        haloIntensity: 1.3,
        armColorInner: '#ffd28a',
        armColorOuter: '#ff4fa6',
        armAccent1: '#ff6b2c',
        armAccent2: '#ffe88a',
        accretionInner: '#ffc15e',
        accretionOuter: '#4a0820',
        photonColor: '#fff0dc',
        dirX: 0.62, dirY: 0.48, dirZ: -0.62,
        normX: 0.28, normY: 0.88, normZ: 0.38,
      },
    }),
  },
  crimsonDust: {
    label: 'Crimson dust',
    patch: () => ({
      sky: { bg: '#050103' },
      stars: {
        density: 1.0,
        size: 1.0,
        tintDust: '#ffd7d7',
        tintMid: '#ffb5b5',
        tintBright: '#ff8787',
      },
      nebula: {
        color1: '#5a0a1a',
        color2: '#8a1030',
        intensity: 1.8,
      },
      nebulaCloud: {
        colorCore: '#ffd0d0',
        colorMid: '#ff2a5a',
        colorEdge: '#2a0008',
        intensity: 1.4,
      },
      galaxy: {
        enabled: true,
        outerR: 3500,
        bulgeR: 820,
        blackHoleR: 190,
        armCount: 5,
        spiralTightness: 0.6,
        armStars: 16000,
        bulgeStars: 5500,
        haloColor: '#3a0a14',
        haloIntensity: 1.4,
        armColorInner: '#ffd0d0',
        armColorOuter: '#ff3366',
        armAccent1: '#ff1040',
        armAccent2: '#ffe8b8',
        accretionInner: '#ffd080',
        accretionOuter: '#680810',
        photonColor: '#ffefec',
        dirX: 0.62, dirY: 0.48, dirZ: -0.62,
        normX: 0.28, normY: 0.88, normZ: 0.38,
      },
    }),
  },
  blueDawn: {
    label: 'Blue dawn',
    patch: () => ({
      sky: { bg: '#020414' },
      stars: {
        density: 1.3,
        size: 0.9,
        tintDust: '#dce6ff',
        tintMid: '#c3d6ff',
        tintBright: '#9bbcff',
      },
      nebula: {
        color1: '#1a3a8a',
        color2: '#0a5a9a',
        intensity: 1.2,
      },
      nebulaCloud: {
        colorCore: '#dfe9ff',
        colorMid: '#5a88ff',
        colorEdge: '#0a1240',
        intensity: 1.1,
      },
      galaxy: {
        enabled: true,
        outerR: 3800,
        bulgeR: 900,
        blackHoleR: 160,
        armCount: 4,
        spiralTightness: 0.85,
        armStars: 17000,
        bulgeStars: 5000,
        haloColor: '#0e1a40',
        haloIntensity: 1.1,
        armColorInner: '#d8e4ff',
        armColorOuter: '#6c8dff',
        armAccent1: '#3aa9ff',
        armAccent2: '#aec7ff',
        accretionInner: '#eaf2ff',
        accretionOuter: '#0a1640',
        photonColor: '#ffffff',
        dirX: 0.62, dirY: 0.48, dirZ: -0.62,
        normX: 0.28, normY: 0.88, normZ: 0.38,
      },
    }),
  },
  emeraldCluster: {
    label: 'Emerald cluster',
    patch: () => ({
      sky: { bg: '#010a06' },
      stars: {
        density: 1.2,
        size: 1.0,
        tintDust: '#d6ffe6',
        tintMid: '#fff3c7',
        tintBright: '#ffd99c',
      },
      nebula: {
        color1: '#083a2a',
        color2: '#0a6a4a',
        intensity: 1.5,
      },
      nebulaCloud: {
        colorCore: '#f9ffd2',
        colorMid: '#58ffae',
        colorEdge: '#0a2820',
        intensity: 1.2,
      },
      galaxy: {
        enabled: true,
        outerR: 3300,
        bulgeR: 820,
        blackHoleR: 170,
        armCount: 4,
        spiralTightness: 0.78,
        armStars: 13500,
        bulgeStars: 4700,
        haloColor: '#0f2a1f',
        haloIntensity: 1.0,
        armColorInner: '#f9ffd2',
        armColorOuter: '#58ffae',
        armAccent1: '#ffc957',
        armAccent2: '#00ffb8',
        accretionInner: '#ffd480',
        accretionOuter: '#0a2820',
        photonColor: '#e7ffda',
        dirX: 0.62, dirY: 0.48, dirZ: -0.62,
        normX: 0.28, normY: 0.88, normZ: 0.38,
      },
    }),
  },
  violetDream: {
    label: 'Violet dream',
    patch: () => ({
      sky: { bg: '#050014' },
      stars: {
        density: 1.1,
        size: 1.0,
        tintDust: '#e4d6ff',
        tintMid: '#ffd6f0',
        tintBright: '#f9a8ff',
      },
      nebula: {
        color1: '#5a1a8a',
        color2: '#b030ff',
        intensity: 1.7,
      },
      nebulaCloud: {
        colorCore: '#ffccff',
        colorMid: '#b45aff',
        colorEdge: '#200a50',
        intensity: 1.5,
      },
      galaxy: {
        enabled: true,
        outerR: 3500,
        bulgeR: 820,
        blackHoleR: 180,
        armCount: 4,
        spiralTightness: 0.8,
        armStars: 14500,
        bulgeStars: 4800,
        haloColor: '#281050',
        haloIntensity: 1.3,
        armColorInner: '#f9d6ff',
        armColorOuter: '#8a3aff',
        armAccent1: '#ff77ff',
        armAccent2: '#e8b0ff',
        accretionInner: '#ffc8ff',
        accretionOuter: '#200855',
        photonColor: '#f2e0ff',
        dirX: 0.62, dirY: 0.48, dirZ: -0.62,
        normX: 0.28, normY: 0.88, normZ: 0.38,
      },
    }),
  },
};

/**
 * Deep-merge `src` onto `dst`. Only touches plain objects; primitives get
 * overwritten. Used so partial presets inherit unspecified keys from the
 * baseline defaults.
 */
function deepMerge(dst, src) {
  for (const k of Object.keys(src || {})) {
    const v = src[k];
    if (v && typeof v === 'object' && !Array.isArray(v)) {
      if (!dst[k] || typeof dst[k] !== 'object') dst[k] = {};
      deepMerge(dst[k], v);
    } else {
      dst[k] = v;
    }
  }
  return dst;
}

/**
 * Sanitize a config object in-place: every numeric field is replaced with
 * the default if it's NaN / Infinity / missing, every color hex is replaced
 * if malformed. Keeps the game bulletproof against corrupt localStorage,
 * weird manual console edits, or future schema changes that drop fields.
 */
function sanitizeConfig(cfg) {
  const def = defaultSpaceBgConfig();
  const fixNum = (obj, defObj, key) => {
    const v = Number(obj[key]);
    if (!Number.isFinite(v)) obj[key] = defObj[key];
    else obj[key] = v;
  };
  const fixHex = (obj, defObj, key) => {
    const v = obj[key];
    if (typeof v !== 'string' || !/^#[0-9a-fA-F]{6}$/.test(v.trim())) obj[key] = defObj[key];
  };
  const fixBool = (obj, defObj, key) => {
    obj[key] = obj[key] == null ? defObj[key] : !!obj[key];
  };
  /** Clamp `universe.seed` to an integer in 32-bit unsigned range. Any
   *  non-finite / non-integer value falls back to the default — preserves
   *  the sky at a stable seed even if localStorage gets corrupted. */
  const fixInt = (obj, defObj, key) => {
    const v = Number(obj[key]);
    if (!Number.isFinite(v)) obj[key] = defObj[key];
    else obj[key] = (Math.floor(v) >>> 0);
  };
  if (!cfg.universe || typeof cfg.universe !== 'object') cfg.universe = { ...def.universe };
  fixInt(cfg.universe, def.universe, 'seed');
  if (!cfg.sky || typeof cfg.sky !== 'object') cfg.sky = { ...def.sky };
  fixHex(cfg.sky, def.sky, 'bg');
  if (!cfg.stars || typeof cfg.stars !== 'object') cfg.stars = { ...def.stars };
  for (const k of ['density', 'size']) fixNum(cfg.stars, def.stars, k);
  for (const k of ['tintDust', 'tintMid', 'tintBright']) fixHex(cfg.stars, def.stars, k);
  if (!cfg.nebula || typeof cfg.nebula !== 'object') cfg.nebula = { ...def.nebula };
  fixNum(cfg.nebula, def.nebula, 'intensity');
  for (const k of ['color1', 'color2']) fixHex(cfg.nebula, def.nebula, k);
  if (!cfg.nebulaCloud || typeof cfg.nebulaCloud !== 'object') cfg.nebulaCloud = { ...def.nebulaCloud };
  fixBool(cfg.nebulaCloud, def.nebulaCloud, 'enabled');
  for (const k of ['dirX', 'dirY', 'dirZ', 'size', 'density', 'intensity']) fixNum(cfg.nebulaCloud, def.nebulaCloud, k);
  for (const k of ['colorCore', 'colorMid', 'colorEdge']) fixHex(cfg.nebulaCloud, def.nebulaCloud, k);
  if (!cfg.galaxy || typeof cfg.galaxy !== 'object') cfg.galaxy = { ...def.galaxy };
  fixBool(cfg.galaxy, def.galaxy, 'enabled');
  for (const k of [
    'outerR', 'bulgeR', 'blackHoleR', 'armCount', 'spiralTightness',
    'armStars', 'bulgeStars', 'haloIntensity',
    'dirX', 'dirY', 'dirZ', 'normX', 'normY', 'normZ',
  ]) fixNum(cfg.galaxy, def.galaxy, k);
  for (const k of [
    'haloColor', 'armColorInner', 'armColorOuter', 'armAccent1', 'armAccent2',
    'accretionInner', 'accretionOuter', 'photonColor',
  ]) fixHex(cfg.galaxy, def.galaxy, k);
  return cfg;
}

/** Current live config. Initialized from localStorage or defaults. Any
 *  corrupt value is replaced with its default — a single bad field must
 *  never brick the game, and we've been bitten by this before (user set
 *  something weird, page reload read it back, `buildSpaceBackground` threw,
 *  main menu stayed hidden by the `lt-game-running` CSS rule). */
let _config = (() => {
  const base = defaultSpaceBgConfig();
  try {
    const raw = localStorage.getItem(LS_KEY);
    if (!raw) return base;
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object') return base;
    deepMerge(base, parsed);
    sanitizeConfig(base);
    return base;
  } catch (_) {
    return base;
  }
})();

/** @type {Set<() => void>} */
const _listeners = new Set();

/** Read-only-ish accessor. Callers must not mutate the returned object
 *  directly — go through {@link setSpaceBgConfig} or {@link patchSpaceBgConfig}
 *  so listeners + persistence fire. */
export function getSpaceBgConfig() {
  return _config;
}

/** Replace the full config atomically (used by preset application + Reset). */
export function setSpaceBgConfig(next) {
  _config = next || defaultSpaceBgConfig();
  sanitizeConfig(_config);
  persist();
  emit();
}

/** Patch specific fields. `patch` is deep-merged over the current config. */
export function patchSpaceBgConfig(patch) {
  deepMerge(_config, patch);
  sanitizeConfig(_config);
  persist();
  emit();
}

/** Register a callback fired any time the config changes. Returns an
 *  unsubscribe function. */
export function onSpaceBgChange(cb) {
  _listeners.add(cb);
  return () => _listeners.delete(cb);
}

function emit() {
  for (const cb of _listeners) {
    try { cb(); } catch (e) { console.warn('[space-bg] listener threw', e); }
  }
}

function persist() {
  try {
    localStorage.setItem(LS_KEY, JSON.stringify(_config));
  } catch (_) {
    /* quota or SecurityError — ignore */
  }
}

/** Apply a preset by id (see {@link SPACE_BG_PRESETS}). Unknown id → no-op. */
export function applySpaceBgPreset(id) {
  const p = SPACE_BG_PRESETS[id];
  if (!p) return;
  const fresh = defaultSpaceBgConfig();
  deepMerge(fresh, p.patch());
  setSpaceBgConfig(fresh);
}

/* ────────────────────────────────────────────────────────────────────────── *
 *  UI
 * ────────────────────────────────────────────────────────────────────────── */

let _uiInstalled = false;
let _panel = null;
let _toggleBtn = null;

/** Install the floating "Space BG" toggle button + the editor panel. Safe to
 *  call multiple times — subsequent calls are no-ops. Call once from
 *  `game.js` after `startGame()` runs. */
export function installSpaceBgEditorUi() {
  if (_uiInstalled || typeof document === 'undefined') return;
  _uiInstalled = true;

  const btn = document.createElement('button');
  btn.type = 'button';
  btn.id = 'lt-space-bg-toggle';
  btn.textContent = 'Space BG';
  btn.title = 'Edit the starfield + galaxy (B)';
  Object.assign(btn.style, {
    position: 'fixed',
    bottom: '12px',
    left: '120px',
    zIndex: '10000',
    padding: '8px 14px',
    borderRadius: '8px',
    border: '1px solid rgba(140,180,255,0.35)',
    background: 'rgba(22,24,48,0.94)',
    color: '#d4e4ff',
    fontSize: '13px',
    fontFamily: 'system-ui, sans-serif',
    cursor: 'pointer',
    boxShadow: '0 4px 18px rgba(0,0,0,0.5)',
  });
  btn.addEventListener('click', () => togglePanel());
  /** Stop pointer events on the toggle button + panel from bubbling to the
   *  game's document-level "click to re-lock the cursor" handler. Without
   *  this, every slider drag / preset click sucks the mouse back into the
   *  game and it's impossible to actually use the editor. */
  const swallow = (e) => e.stopPropagation();
  ['mousedown', 'click', 'pointerdown', 'pointerup', 'mouseup'].forEach((ev) => {
    btn.addEventListener(ev, swallow);
  });
  document.body.appendChild(btn);
  _toggleBtn = btn;

  _panel = buildPanel();
  ['mousedown', 'click', 'pointerdown', 'pointerup', 'mouseup'].forEach((ev) => {
    _panel.addEventListener(ev, swallow);
  });
  document.body.appendChild(_panel);
  _panel.style.display = 'none';

  document.addEventListener('keydown', (e) => {
    const tag = e.target?.tagName;
    const inField = tag === 'INPUT' || tag === 'TEXTAREA' || e.target?.isContentEditable;
    if (inField) return;
    if (e.code === 'KeyB' && !e.ctrlKey && !e.metaKey && !e.altKey) {
      togglePanel();
    }
  });
}

function togglePanel() {
  if (!_panel) return;
  const showing = _panel.style.display !== 'none';
  _panel.style.display = showing ? 'none' : 'flex';
  if (!showing) refreshInputsFromConfig();
}

function buildPanel() {
  const p = document.createElement('div');
  p.id = 'lt-space-bg-panel';
  Object.assign(p.style, {
    position: 'fixed',
    bottom: '60px',
    left: '12px',
    width: 'min(380px, 94vw)',
    maxHeight: 'min(75vh, 800px)',
    zIndex: '9999',
    background: 'rgba(10,12,22,0.96)',
    border: '1px solid rgba(120,180,255,0.3)',
    borderRadius: '12px',
    color: '#e4eaff',
    fontFamily: 'system-ui, sans-serif',
    fontSize: '12px',
    display: 'flex',
    flexDirection: 'column',
    boxShadow: '0 12px 36px rgba(0,0,0,0.6)',
    overflow: 'hidden',
  });

  const header = document.createElement('div');
  header.textContent = 'Space background';
  Object.assign(header.style, {
    padding: '10px 14px',
    fontSize: '14px',
    fontWeight: '600',
    background: 'linear-gradient(180deg, rgba(70,90,180,0.25), rgba(30,40,80,0.1))',
    borderBottom: '1px solid rgba(120,180,255,0.2)',
    letterSpacing: '0.4px',
  });
  p.appendChild(header);

  const body = document.createElement('div');
  Object.assign(body.style, {
    overflowY: 'auto',
    padding: '12px 14px 14px',
    display: 'flex',
    flexDirection: 'column',
    gap: '14px',
  });
  p.appendChild(body);

  /* Presets row */
  body.appendChild(buildPresetsRow());

  /* Sections */
  body.appendChild(buildSection('Sky', [
    colorRow('sky.bg', 'Background'),
  ]));

  /** Universe section — seed + Reseed. The seed is a 32-bit integer; we
   *  render it with a number input and a button that picks a random new
   *  seed, mirroring the workflow of "reroll this sky". Changing the seed
   *  triggers a background rebuild via `scheduleEmit` so the new star
   *  layout shows up live. */
  body.appendChild(buildSection('Universe', [
    seedRow('universe.seed', 'Seed'),
  ]));

  body.appendChild(buildSection('Stars', [
    sliderRow('stars.density', 'Density', 0.1, 2.5, 0.05),
    sliderRow('stars.size', 'Size', 0.3, 3, 0.05),
    colorRow('stars.tintDust', 'Dust tint'),
    colorRow('stars.tintMid', 'Medium tint'),
    colorRow('stars.tintBright', 'Bright tint'),
  ]));

  body.appendChild(buildSection('Nebula haze (full-sky)', [
    colorRow('nebula.color1', 'Color A'),
    colorRow('nebula.color2', 'Color B'),
    sliderRow('nebula.intensity', 'Intensity', 0, 3, 0.05),
  ]));

  body.appendChild(buildSection('Nebula cloud (featured)', [
    checkboxRow('nebulaCloud.enabled', 'Enabled'),
    colorRow('nebulaCloud.colorCore', 'Core color'),
    colorRow('nebulaCloud.colorMid', 'Mid color'),
    colorRow('nebulaCloud.colorEdge', 'Edge color'),
    sliderRow('nebulaCloud.intensity', 'Intensity', 0, 3, 0.05),
    sliderRow('nebulaCloud.size', 'Size', 600, 5000, 50),
    sliderRow('nebulaCloud.density', 'Density', 100, 4000, 50),
    sliderRow('nebulaCloud.dirX', 'Direction X', -1, 1, 0.02),
    sliderRow('nebulaCloud.dirY', 'Direction Y', -1, 1, 0.02),
    sliderRow('nebulaCloud.dirZ', 'Direction Z', -1, 1, 0.02),
  ]));

  body.appendChild(buildSection('Galaxy — shape', [
    checkboxRow('galaxy.enabled', 'Enabled'),
    sliderRow('galaxy.armCount', 'Arm count', 2, 7, 1),
    sliderRow('galaxy.spiralTightness', 'Spiral tightness', 0.3, 1.6, 0.02),
    sliderRow('galaxy.outerR', 'Outer radius', 1200, 8000, 50),
    sliderRow('galaxy.bulgeR', 'Bulge radius', 200, 2400, 20),
    sliderRow('galaxy.blackHoleR', 'Black hole radius', 40, 500, 5),
    sliderRow('galaxy.armStars', 'Arm stars', 2000, 30000, 500),
    sliderRow('galaxy.bulgeStars', 'Bulge stars', 500, 12000, 100),
  ]));

  body.appendChild(buildSection('Galaxy — colors', [
    colorRow('galaxy.haloColor', 'Halo glow'),
    sliderRow('galaxy.haloIntensity', 'Halo intensity', 0, 3, 0.05),
    colorRow('galaxy.armColorInner', 'Arm color (inner)'),
    colorRow('galaxy.armColorOuter', 'Arm color (outer)'),
    colorRow('galaxy.armAccent1', 'Arm accent 1'),
    colorRow('galaxy.armAccent2', 'Arm accent 2'),
    colorRow('galaxy.accretionInner', 'Accretion (inner)'),
    colorRow('galaxy.accretionOuter', 'Accretion (outer)'),
    colorRow('galaxy.photonColor', 'Photon ring'),
  ]));

  body.appendChild(buildSection('Galaxy — placement', [
    sliderRow('galaxy.dirX', 'Direction X', -1, 1, 0.02),
    sliderRow('galaxy.dirY', 'Direction Y', -1, 1, 0.02),
    sliderRow('galaxy.dirZ', 'Direction Z', -1, 1, 0.02),
    sliderRow('galaxy.normX', 'Disc normal X', -1, 1, 0.02),
    sliderRow('galaxy.normY', 'Disc normal Y', -1, 1, 0.02),
    sliderRow('galaxy.normZ', 'Disc normal Z', -1, 1, 0.02),
  ]));

  /* Footer */
  const footer = document.createElement('div');
  Object.assign(footer.style, {
    display: 'flex',
    gap: '8px',
    padding: '10px 14px',
    background: 'rgba(0,0,0,0.25)',
    borderTop: '1px solid rgba(120,180,255,0.2)',
  });
  const reset = buttonEl('Reset to default', () => applySpaceBgPreset('default'));
  const randomize = buttonEl('Randomize colors', randomizeColors);
  const close = buttonEl('Close', () => togglePanel());
  Object.assign(close.style, { marginLeft: 'auto' });
  footer.appendChild(reset);
  footer.appendChild(randomize);
  footer.appendChild(close);
  p.appendChild(footer);

  return p;
}

function buildSection(title, rows) {
  const s = document.createElement('div');
  Object.assign(s.style, {
    display: 'flex',
    flexDirection: 'column',
    gap: '6px',
    borderTop: '1px solid rgba(255,255,255,0.08)',
    paddingTop: '10px',
  });
  const h = document.createElement('div');
  h.textContent = title;
  Object.assign(h.style, {
    fontSize: '11px',
    fontWeight: '700',
    letterSpacing: '0.8px',
    textTransform: 'uppercase',
    color: '#a8c3ff',
    marginBottom: '4px',
  });
  s.appendChild(h);
  for (const r of rows) s.appendChild(r);
  return s;
}

function buildPresetsRow() {
  const row = document.createElement('div');
  Object.assign(row.style, {
    display: 'flex',
    flexWrap: 'wrap',
    gap: '6px',
  });
  const label = document.createElement('div');
  label.textContent = 'Preset:';
  Object.assign(label.style, {
    fontSize: '11px',
    color: '#a8c3ff',
    alignSelf: 'center',
    marginRight: '4px',
    fontWeight: '600',
  });
  row.appendChild(label);
  for (const id of Object.keys(SPACE_BG_PRESETS)) {
    const b = buttonEl(SPACE_BG_PRESETS[id].label, () => {
      applySpaceBgPreset(id);
      refreshInputsFromConfig();
    });
    b.style.padding = '5px 9px';
    b.style.fontSize = '11px';
    row.appendChild(b);
  }
  return row;
}

function buttonEl(label, onClick) {
  const b = document.createElement('button');
  b.type = 'button';
  b.textContent = label;
  Object.assign(b.style, {
    padding: '6px 10px',
    borderRadius: '6px',
    border: '1px solid rgba(140,180,255,0.35)',
    background: 'rgba(36,44,80,0.9)',
    color: '#e4eaff',
    fontSize: '12px',
    fontFamily: 'inherit',
    cursor: 'pointer',
  });
  b.addEventListener('click', onClick);
  return b;
}

/* Input row factories — each returns a DOM element and registers its
 * refresh handler in `_inputRefreshers` so a preset change updates every
 * control on the panel without rebuilding the DOM. */

/** @type {Array<() => void>} */
const _inputRefreshers = [];

function resolvePath(path) {
  const parts = path.split('.');
  let o = _config;
  for (let i = 0; i < parts.length - 1; i++) {
    if (!o[parts[i]]) o[parts[i]] = {};
    o = o[parts[i]];
  }
  return { obj: o, key: parts[parts.length - 1] };
}

function sliderRow(path, label, min, max, step) {
  const row = document.createElement('label');
  Object.assign(row.style, {
    display: 'grid',
    gridTemplateColumns: '110px 1fr 54px',
    alignItems: 'center',
    gap: '8px',
  });
  const l = document.createElement('span');
  l.textContent = label;
  l.style.fontSize = '11px';
  l.style.color = '#c0cfee';

  const slider = document.createElement('input');
  slider.type = 'range';
  slider.min = String(min);
  slider.max = String(max);
  slider.step = String(step);
  slider.style.width = '100%';

  const val = document.createElement('span');
  Object.assign(val.style, {
    fontSize: '11px',
    textAlign: 'right',
    fontVariantNumeric: 'tabular-nums',
    color: '#a8c3ff',
  });

  const { obj, key } = resolvePath(path);
  const refresh = () => {
    const { obj: o2, key: k2 } = resolvePath(path);
    const cur = Number(o2[k2]);
    slider.value = String(cur);
    val.textContent = formatSliderVal(cur, step);
  };
  refresh();
  _inputRefreshers.push(refresh);

  slider.addEventListener('input', () => {
    const v = Number(slider.value);
    const { obj: o2, key: k2 } = resolvePath(path);
    o2[k2] = v;
    val.textContent = formatSliderVal(v, step);
    persist();
    scheduleEmit();
  });

  row.appendChild(l);
  row.appendChild(slider);
  row.appendChild(val);
  return row;
}

function formatSliderVal(v, step) {
  if (step >= 1) return String(Math.round(v));
  if (step >= 0.1) return v.toFixed(1);
  return v.toFixed(2);
}

function colorRow(path, label) {
  const row = document.createElement('label');
  Object.assign(row.style, {
    display: 'grid',
    gridTemplateColumns: '110px 1fr 86px',
    alignItems: 'center',
    gap: '8px',
  });
  const l = document.createElement('span');
  l.textContent = label;
  l.style.fontSize = '11px';
  l.style.color = '#c0cfee';

  const picker = document.createElement('input');
  picker.type = 'color';
  Object.assign(picker.style, {
    width: '100%',
    height: '22px',
    padding: '0',
    border: '1px solid rgba(255,255,255,0.18)',
    borderRadius: '4px',
    background: 'transparent',
    cursor: 'pointer',
  });

  const hexBox = document.createElement('input');
  hexBox.type = 'text';
  hexBox.maxLength = 7;
  hexBox.spellcheck = false;
  Object.assign(hexBox.style, {
    fontFamily: 'ui-monospace, Consolas, monospace',
    fontSize: '11px',
    background: 'rgba(0,0,0,0.3)',
    color: '#d9e4ff',
    border: '1px solid rgba(255,255,255,0.12)',
    borderRadius: '4px',
    padding: '2px 4px',
    width: '100%',
  });

  const refresh = () => {
    const { obj: o2, key: k2 } = resolvePath(path);
    const hex = normalizeHex(o2[k2]);
    picker.value = hex;
    hexBox.value = hex;
  };
  refresh();
  _inputRefreshers.push(refresh);

  const apply = (newHex) => {
    const hex = normalizeHex(newHex);
    const { obj: o2, key: k2 } = resolvePath(path);
    o2[k2] = hex;
    picker.value = hex;
    hexBox.value = hex;
    persist();
    scheduleEmit();
  };
  picker.addEventListener('input', () => apply(picker.value));
  hexBox.addEventListener('change', () => apply(hexBox.value));

  row.appendChild(l);
  row.appendChild(picker);
  row.appendChild(hexBox);
  return row;
}

/**
 * Integer seed input + "Reseed" button pair. Wired to a config path like
 * `universe.seed`. Writes immediately on change and triggers a debounced
 * rebuild so the star catalog regenerates. Reseed button rolls a random
 * 32-bit int so the player can spam it until they find a sky they like.
 */
function seedRow(path, label) {
  const row = document.createElement('label');
  Object.assign(row.style, {
    display: 'grid',
    gridTemplateColumns: '110px 1fr 78px',
    alignItems: 'center',
    gap: '8px',
  });
  const l = document.createElement('span');
  l.textContent = label;
  l.style.fontSize = '11px';
  l.style.color = '#c0cfee';

  const input = document.createElement('input');
  input.type = 'number';
  input.min = '0';
  input.step = '1';
  input.spellcheck = false;
  Object.assign(input.style, {
    fontFamily: 'ui-monospace, Consolas, monospace',
    fontSize: '11px',
    background: 'rgba(0,0,0,0.3)',
    color: '#d9e4ff',
    border: '1px solid rgba(255,255,255,0.12)',
    borderRadius: '4px',
    padding: '3px 6px',
    width: '100%',
  });

  const refresh = () => {
    const { obj: o2, key: k2 } = resolvePath(path);
    const v = Number(o2[k2]);
    input.value = String(Number.isFinite(v) ? Math.floor(v) >>> 0 : 0);
  };
  refresh();
  _inputRefreshers.push(refresh);

  input.addEventListener('change', () => {
    const v = Number(input.value);
    const { obj: o2, key: k2 } = resolvePath(path);
    o2[k2] = (Number.isFinite(v) ? Math.floor(v) >>> 0 : o2[k2]);
    input.value = String(o2[k2]);
    persist();
    scheduleEmit();
  });

  const reseed = buttonEl('Reseed', () => {
    /** Use a full 32-bit range by combining two Math.random draws — a
     *  single draw only gives us ~52 bits of mantissa but the top 11 are
     *  almost always zero when we downcast, biasing seeds toward small
     *  values. Shifting gets us a uniform spread. */
    const a = Math.floor(Math.random() * 0x10000) & 0xffff;
    const b = Math.floor(Math.random() * 0x10000) & 0xffff;
    const seed = ((a << 16) | b) >>> 0;
    const { obj: o2, key: k2 } = resolvePath(path);
    o2[k2] = seed;
    input.value = String(seed);
    persist();
    scheduleEmit();
  });
  reseed.style.padding = '4px 8px';
  reseed.style.fontSize = '11px';

  row.appendChild(l);
  row.appendChild(input);
  row.appendChild(reseed);
  return row;
}

function checkboxRow(path, label) {
  const row = document.createElement('label');
  Object.assign(row.style, {
    display: 'flex',
    alignItems: 'center',
    gap: '10px',
    fontSize: '11px',
    color: '#c0cfee',
  });
  const cb = document.createElement('input');
  cb.type = 'checkbox';
  const refresh = () => {
    const { obj: o2, key: k2 } = resolvePath(path);
    cb.checked = !!o2[k2];
  };
  refresh();
  _inputRefreshers.push(refresh);
  cb.addEventListener('change', () => {
    const { obj: o2, key: k2 } = resolvePath(path);
    o2[k2] = cb.checked;
    persist();
    scheduleEmit();
  });
  const l = document.createElement('span');
  l.textContent = label;
  row.appendChild(cb);
  row.appendChild(l);
  return row;
}

function refreshInputsFromConfig() {
  for (const fn of _inputRefreshers) {
    try { fn(); } catch (_) {}
  }
}

function normalizeHex(raw) {
  if (typeof raw !== 'string') return '#ffffff';
  let s = raw.trim();
  if (!s.startsWith('#')) s = `#${s}`;
  if (!/^#[0-9a-fA-F]{6}$/.test(s)) return '#ffffff';
  return s.toLowerCase();
}

/** Pick a random color in a roughly nebula-friendly palette (bright-ish, not
 *  muddy). Used by the Randomize button to give quick inspiration. */
function randomNiceColor() {
  const h = Math.random();
  const s = 0.55 + Math.random() * 0.35;
  const l = 0.45 + Math.random() * 0.25;
  return hslToHex(h, s, l);
}

function hslToHex(h, s, l) {
  const a = s * Math.min(l, 1 - l);
  const f = (n) => {
    const k = (n + h * 12) % 12;
    const c = l - a * Math.max(-1, Math.min(Math.min(k - 3, 9 - k), 1));
    return Math.round(255 * c);
  };
  const toHex = (v) => v.toString(16).padStart(2, '0');
  return `#${toHex(f(0))}${toHex(f(8))}${toHex(f(4))}`;
}

function randomizeColors() {
  const c = _config;
  c.nebula.color1 = randomNiceColor();
  c.nebula.color2 = randomNiceColor();
  c.galaxy.haloColor = randomNiceColor();
  c.galaxy.armColorInner = randomNiceColor();
  c.galaxy.armColorOuter = randomNiceColor();
  c.galaxy.armAccent1 = randomNiceColor();
  c.galaxy.armAccent2 = randomNiceColor();
  c.galaxy.accretionInner = randomNiceColor();
  c.galaxy.accretionOuter = randomNiceColor();
  c.galaxy.photonColor = randomNiceColor();
  if (c.nebulaCloud) {
    c.nebulaCloud.colorCore = randomNiceColor();
    c.nebulaCloud.colorMid = randomNiceColor();
    c.nebulaCloud.colorEdge = randomNiceColor();
  }
  persist();
  refreshInputsFromConfig();
  emit();
}

/** Debounced emit — many slider events per second become one rebuild. */
let _emitTimer = null;
function scheduleEmit() {
  clearTimeout(_emitTimer);
  _emitTimer = setTimeout(() => { emit(); }, 120);
}

/**
 * Small helper for the renderer: parse a config hex string into a THREE
 * `Color`-compatible `{r, g, b}` triple in 0..1. Defensive against bad values
 * so a hand-typed hex can't crash the rebuild.
 */
export function hexToRgbFloat(hex) {
  const s = normalizeHex(hex).slice(1);
  return {
    r: parseInt(s.slice(0, 2), 16) / 255,
    g: parseInt(s.slice(2, 4), 16) / 255,
    b: parseInt(s.slice(4, 6), 16) / 255,
  };
}
