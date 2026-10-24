/**
 * Full-screen overlay editor for the active {@link LT2_TreeConfig} preset.
 *
 * Left column:
 *  - Presets row (dropdown, New, Delete, Reroll seed, Auto-rotate toggle).
 *  - Sliders grouped by Trunk / Main branches / Sub-branches / Leaves / Physics.
 *
 * Right column: a tiny three.js scene. Hold LMB on the canvas and drag to orbit,
 * wheel to zoom. Auto-rotate adds yaw when idle.
 *
 * Mount point: `#lt2-config-root` (created in index.html).
 */
import * as THREE from 'three';
import {
  LT2_TREE_DEFAULTS,
  LT2_INTERIOR_TEXTURES,
  LT2_TRUNK_SIDE_TEXTURES,
  LT2_LEAF_TEXTURES,
  loadLt2Config,
  saveLt2Config,
  resetLt2Config,
  normalizeLt2Config,
  listLt2Presets,
  getActiveLt2PresetId,
  setActiveLt2PresetId,
  createLt2Preset,
  deleteLt2Preset,
} from './lt2-tree-config.js';
import { applyLt2StyleTreeBranches } from './lt2-tree-branches.js';
import { buildInteriorCapMaterial, disposeInteriorCapMaterial } from './lt2-tree-interior.js';
import { buildTrunkSideMaterial } from './lt2-tree-textures.js';

const INTERIOR_TEXTURE_LABELS = {
  solid: 'Solid',
  rings: 'Tree rings',
  grain: 'Wood grain',
  checker: 'Checker',
  planks: 'Planks',
};

const TRUNK_TEXTURE_LABELS = {
  stock: 'Stock bark (PNG)',
  smooth: 'Smooth',
  grain: 'Vertical grain',
  bark: 'Rough bark',
  rings: 'Horizontal rings',
};

const LEAF_TEXTURE_LABELS = {
  solid: 'Solid color',
  grass: 'Grass noise',
  leafy: 'Leafy clumps',
  dense: 'Dense canopy',
  speckle: 'Fine speckle',
};

const AUTO_ROTATE_KEY = 'LT2_TreePreviewAutoRotate';

let host = null;
let overlayEl = null;
let rebuildPreview = null;
let previewDisposer = null;
let workingCfg = null;
let previewSeed = 0xC0FFEE;
let autoRotateEnabled = true;
const sliderInputs = new Map();

const STYLE_ID = 'lt2-config-style';
const CSS = `
#lt2-config-overlay {
  position: fixed;
  inset: 0;
  z-index: 40000;
  display: none;
  flex-direction: column;
  background: linear-gradient(160deg, #0f1a14 0%, #0a0f16 55%, #13241d 100%);
  color: #e8f0e8;
  font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
}
#lt2-config-overlay.lt2-cfg-open { display: flex; }
#lt2-config-overlay .lt2-cfg-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 18px 26px;
  border-bottom: 1px solid rgba(255,255,255,0.08);
  background: rgba(0,0,0,0.35);
}
#lt2-config-overlay .lt2-cfg-head h2 { margin: 0; font-size: 1.35rem; font-weight: 700; letter-spacing: 0.02em; }
#lt2-config-overlay .lt2-cfg-head .lt2-cfg-sub { font-size: 12px; opacity: 0.72; margin-top: 2px; }
#lt2-config-overlay .lt2-cfg-actions { display: flex; gap: 10px; }
#lt2-config-overlay .lt2-cfg-btn {
  border: 1px solid rgba(255,255,255,0.14);
  background: rgba(0,0,0,0.3);
  color: #e8f0e8;
  font-size: 13px; font-weight: 600; font-family: inherit;
  padding: 8px 14px; border-radius: 8px; cursor: pointer;
}
#lt2-config-overlay .lt2-cfg-btn:hover { filter: brightness(1.1); }
#lt2-config-overlay .lt2-cfg-btn.primary { background: linear-gradient(180deg,#3d9a6a,#2d6a4f); border-color: rgba(120,200,160,0.4); }
#lt2-config-overlay .lt2-cfg-btn.danger { background: #5a3535; border-color: rgba(255,160,160,0.25); }
#lt2-config-overlay .lt2-cfg-btn:disabled { opacity: 0.45; cursor: not-allowed; }
#lt2-config-overlay .lt2-cfg-body {
  flex: 1;
  display: grid;
  grid-template-columns: minmax(340px, 460px) 1fr;
  min-height: 0;
}
#lt2-config-overlay .lt2-cfg-sliders {
  overflow-y: auto;
  padding: 18px 24px 32px;
  border-right: 1px solid rgba(255,255,255,0.08);
  background: rgba(0,0,0,0.2);
}
#lt2-config-overlay .lt2-cfg-group {
  margin-bottom: 18px;
  padding: 14px 16px;
  border-radius: 10px;
  background: rgba(255,255,255,0.035);
  border: 1px solid rgba(255,255,255,0.06);
}
#lt2-config-overlay .lt2-cfg-group h3 {
  margin: 0 0 10px;
  font-size: 11px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: #9adab5;
  font-weight: 700;
}
#lt2-config-overlay .lt2-cfg-field {
  display: grid;
  grid-template-columns: 1fr 64px;
  column-gap: 10px;
  row-gap: 3px;
  align-items: center;
  margin-bottom: 10px;
}
#lt2-config-overlay .lt2-cfg-field:last-child { margin-bottom: 0; }
#lt2-config-overlay .lt2-cfg-field label {
  grid-column: 1 / 2;
  font-size: 13px;
}
#lt2-config-overlay .lt2-cfg-field .lt2-cfg-val {
  grid-column: 2 / 3;
  text-align: right;
  font-variant-numeric: tabular-nums;
  font-size: 12px;
  opacity: 0.85;
}
#lt2-config-overlay .lt2-cfg-field input[type="range"] {
  grid-column: 1 / 3;
  width: 100%;
  accent-color: #4caf7a;
}
#lt2-config-overlay .lt2-cfg-field input[type="color"] {
  grid-column: 1 / 3;
  width: 100%;
  height: 30px;
  background: transparent;
  border: 1px solid rgba(255,255,255,0.16);
  border-radius: 6px;
  cursor: pointer;
}
#lt2-config-overlay .lt2-cfg-field select {
  grid-column: 1 / 3;
  width: 100%;
  padding: 6px 8px;
  background: #1a2028;
  color: #e8f0e8;
  border: 1px solid rgba(255,255,255,0.16);
  border-radius: 6px;
  font-family: inherit;
  font-size: 13px;
}
#lt2-config-overlay .lt2-cfg-field.lt2-cfg-check {
  grid-template-columns: 1fr;
}
#lt2-config-overlay .lt2-cfg-field.lt2-cfg-check label {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
}
#lt2-config-overlay .lt2-cfg-field.lt2-cfg-check input[type="checkbox"] {
  width: 16px; height: 16px; accent-color: #4caf7a; cursor: pointer;
}
#lt2-config-overlay .lt2-cfg-presets-row {
  display: flex; flex-wrap: wrap; gap: 8px; align-items: center;
  margin-bottom: 10px;
}
#lt2-config-overlay .lt2-cfg-presets-row select {
  flex: 1 1 140px;
  padding: 7px 10px;
  border-radius: 6px;
  background: #1a2028;
  color: #e8f0e8;
  border: 1px solid rgba(255,255,255,0.16);
  font-family: inherit;
  font-size: 13px;
}
#lt2-config-overlay .lt2-cfg-presets-row .lt2-cfg-btn-mini {
  padding: 7px 12px;
  font-size: 12px;
}
#lt2-config-overlay .lt2-cfg-presets-second {
  display: flex; flex-wrap: wrap; gap: 10px 14px; align-items: center;
  font-size: 13px;
}
#lt2-config-overlay .lt2-cfg-presets-second label {
  display: flex; align-items: center; gap: 6px; cursor: pointer;
}
#lt2-config-overlay .lt2-cfg-presets-second input[type="checkbox"] {
  width: 15px; height: 15px; accent-color: #4caf7a; cursor: pointer;
}
#lt2-config-overlay .lt2-cfg-preview {
  position: relative;
  background: radial-gradient(ellipse at center, #1e2c25 0%, #0b1219 80%);
  overflow: hidden;
}
#lt2-config-overlay .lt2-cfg-preview canvas {
  width: 100%;
  height: 100%;
  display: block;
  cursor: grab;
}
#lt2-config-overlay .lt2-cfg-preview canvas.lt2-cfg-dragging { cursor: grabbing; }
#lt2-config-overlay .lt2-cfg-preview .lt2-cfg-hint {
  position: absolute;
  left: 14px; bottom: 12px;
  font-size: 11px; opacity: 0.55;
  pointer-events: none;
  text-shadow: 0 1px 2px rgba(0,0,0,0.6);
}
@media (max-width: 820px) {
  #lt2-config-overlay .lt2-cfg-body { grid-template-columns: 1fr; }
  #lt2-config-overlay .lt2-cfg-preview { min-height: 260px; }
}
`;

function ensureStyle() {
  if (document.getElementById(STYLE_ID)) return;
  const s = document.createElement('style');
  s.id = STYLE_ID;
  s.textContent = CSS;
  document.head.appendChild(s);
}

const SLIDER_DEFS = [
  {
    title: 'Trunk',
    fields: [
      { key: 'trunkHeight', label: 'Trunk height (m)', min: 0.5, max: 12, step: 0.05 },
      { key: 'trunkThickness', label: 'Trunk halfW (m)', min: 0.04, max: 0.9, step: 0.01 },
      {
        key: 'trunkTexture',
        label: 'Outside texture (bark)',
        kind: 'select',
        options: LT2_TRUNK_SIDE_TEXTURES.map((v) => ({ value: v, label: TRUNK_TEXTURE_LABELS[v] || v })),
      },
      { key: 'trunkColor', label: 'Outside color (bark)', kind: 'color' },
      { key: 'interiorColor', label: 'Interior color (inside trunk)', kind: 'color' },
      { key: 'interiorNeon', label: 'Neon glow inside trunk', kind: 'checkbox' },
      { key: 'interiorBorder', label: 'Border inside trunk', kind: 'checkbox' },
      { key: 'interiorBorderThickness', label: 'Border thickness (inset)', min: 0, max: 0.45, step: 0.01 },
      { key: 'interiorBorderColor', label: 'Border color', kind: 'color' },
      {
        key: 'interiorTexture',
        label: 'Interior texture',
        kind: 'select',
        options: LT2_INTERIOR_TEXTURES.map((v) => ({ value: v, label: INTERIOR_TEXTURE_LABELS[v] || v })),
      },
    ],
  },
  {
    title: 'Main branches',
    fields: [
      { key: 'mainCount', label: 'Count', min: 2, max: 6, step: 1 },
      { key: 'mainThickness', label: 'Thickness ×', min: 0.25, max: 3, step: 0.05 },
      { key: 'mainLength', label: 'Length ×', min: 0.25, max: 3, step: 0.05 },
      { key: 'mainAngleJitterDeg', label: 'Horizontal jitter (°)', min: 0, max: 120, step: 1 },
      { key: 'mainPitchDeg', label: 'Vertical pitch (°)', min: -20, max: 85, step: 1 },
      { key: 'mainPitchJitterDeg', label: 'Vertical jitter (°)', min: 0, max: 60, step: 1 },
    ],
  },
  {
    title: 'Sub-branches',
    fields: [
      { key: 'subMin', label: 'Min per branch', min: 1, max: 5, step: 1 },
      { key: 'subMax', label: 'Max per branch', min: 1, max: 5, step: 1 },
      { key: 'subThickness', label: 'Thickness ×', min: 0.25, max: 3, step: 0.05 },
      { key: 'subLength', label: 'Length ×', min: 0.25, max: 3, step: 0.05 },
      { key: 'subAngleJitterDeg', label: 'Horizontal jitter (°)', min: 0, max: 120, step: 1 },
      { key: 'subPitchDeg', label: 'Vertical pitch (°)', min: -20, max: 85, step: 1 },
      { key: 'subPitchJitterDeg', label: 'Vertical jitter (°)', min: 0, max: 60, step: 1 },
    ],
  },
  {
    title: 'Leaves',
    fields: [
      { key: 'leavesPerSub', label: 'Leaves per sub', min: 0, max: 4, step: 1 },
      { key: 'leafPadSize', label: 'Leaf pad size ×', min: 0.25, max: 3, step: 0.05 },
      {
        key: 'leafTexture',
        label: 'Leaf texture',
        kind: 'select',
        options: LT2_LEAF_TEXTURES.map((v) => ({ value: v, label: LEAF_TEXTURE_LABELS[v] || v })),
      },
      { key: 'leafColor', label: 'Leaf color', kind: 'color' },
    ],
  },
  {
    title: 'Physics & economy',
    fields: [
      {
        key: 'woodWeight',
        label: 'Wood weight × (mass + sell)',
        min: 0.25,
        max: 3,
        step: 0.05,
      },
    ],
  },
];

function formatVal(key, v) {
  if (key === 'leafColor' || key === 'interiorColor' || key === 'trunkTexture' || key === 'leafTexture' || key === 'interiorBorderColor' || key === 'trunkColor' || key === 'interiorTexture') return String(v);
  const n = Number(v);
  if (!Number.isFinite(n)) return '—';
  const def = LT2_TREE_DEFAULTS[key];
  const isInt = Number.isInteger(def);
  if (isInt) return String(Math.round(n));
  return n.toFixed(2).replace(/\.00$/, '').replace(/0$/, '') || n.toFixed(2);
}

function readAutoRotatePref() {
  try {
    const v = localStorage.getItem(AUTO_ROTATE_KEY);
    if (v === 'off') return false;
    if (v === 'on') return true;
  } catch (_) {}
  return true;
}
function writeAutoRotatePref(on) {
  try {
    localStorage.setItem(AUTO_ROTATE_KEY, on ? 'on' : 'off');
  } catch (_) {}
}

function buildPresetsGroup(rootEl) {
  const g = document.createElement('div');
  g.className = 'lt2-cfg-group';
  const h = document.createElement('h3');
  h.textContent = 'Presets';
  g.appendChild(h);

  const row = document.createElement('div');
  row.className = 'lt2-cfg-presets-row';
  const sel = document.createElement('select');
  sel.id = 'lt2-cfg-preset-select';
  const newBtn = document.createElement('button');
  newBtn.type = 'button';
  newBtn.className = 'lt2-cfg-btn lt2-cfg-btn-mini';
  newBtn.textContent = 'New';
  const delBtn = document.createElement('button');
  delBtn.type = 'button';
  delBtn.className = 'lt2-cfg-btn lt2-cfg-btn-mini danger';
  delBtn.textContent = 'Delete';
  row.appendChild(sel);
  row.appendChild(newBtn);
  row.appendChild(delBtn);
  g.appendChild(row);

  const row2 = document.createElement('div');
  row2.className = 'lt2-cfg-presets-second';
  const rerollBtn = document.createElement('button');
  rerollBtn.type = 'button';
  rerollBtn.className = 'lt2-cfg-btn lt2-cfg-btn-mini';
  rerollBtn.textContent = 'Reroll seed';
  const rotLabel = document.createElement('label');
  const rotInput = document.createElement('input');
  rotInput.type = 'checkbox';
  rotInput.checked = autoRotateEnabled;
  const rotSpan = document.createElement('span');
  rotSpan.textContent = 'Auto-rotate preview';
  rotLabel.appendChild(rotInput);
  rotLabel.appendChild(rotSpan);
  row2.appendChild(rerollBtn);
  row2.appendChild(rotLabel);
  g.appendChild(row2);

  rootEl.appendChild(g);

  function refreshOptions() {
    const active = getActiveLt2PresetId();
    const presets = listLt2Presets();
    sel.innerHTML = '';
    for (const p of presets) {
      const opt = document.createElement('option');
      opt.value = p.id;
      opt.textContent = p.name;
      if (p.id === active) opt.selected = true;
      sel.appendChild(opt);
    }
    delBtn.disabled = presets.length <= 1;
  }
  refreshOptions();

  sel.addEventListener('change', () => {
    if (setActiveLt2PresetId(sel.value)) {
      workingCfg = loadLt2Config();
      syncInputsFromCfg();
      rebuildPreview?.();
    }
  });
  newBtn.addEventListener('click', () => {
    const name = (window.prompt('Preset name:', `Preset ${listLt2Presets().length + 1}`) || '').trim();
    if (!name) return;
    saveLt2Config(workingCfg);
    const id = createLt2Preset(name);
    if (!id) return;
    refreshOptions();
    workingCfg = loadLt2Config();
    syncInputsFromCfg();
    rebuildPreview?.();
  });
  delBtn.addEventListener('click', () => {
    const active = getActiveLt2PresetId();
    const presets = listLt2Presets();
    if (presets.length <= 1) return;
    const current = presets.find((p) => p.id === active);
    if (!confirm(`Delete preset "${current?.name || active}"?`)) return;
    if (!deleteLt2Preset(active)) return;
    refreshOptions();
    workingCfg = loadLt2Config();
    syncInputsFromCfg();
    rebuildPreview?.();
  });
  rerollBtn.addEventListener('click', () => {
    previewSeed = (Math.random() * 0xffffffff) >>> 0;
    rebuildPreview?.();
  });
  rotInput.addEventListener('change', () => {
    autoRotateEnabled = !!rotInput.checked;
    writeAutoRotatePref(autoRotateEnabled);
  });

  return { refreshOptions };
}

function buildSliders(rootEl) {
  sliderInputs.clear();
  for (const group of SLIDER_DEFS) {
    const g = document.createElement('div');
    g.className = 'lt2-cfg-group';
    const h = document.createElement('h3');
    h.textContent = group.title;
    g.appendChild(h);
    for (const f of group.fields) {
      const row = document.createElement('div');
      row.className = 'lt2-cfg-field';
      if (f.kind === 'checkbox') {
        row.classList.add('lt2-cfg-check');
        const lab = document.createElement('label');
        const input = document.createElement('input');
        input.type = 'checkbox';
        input.checked = !!workingCfg[f.key];
        const txt = document.createElement('span');
        txt.textContent = f.label;
        lab.appendChild(input);
        lab.appendChild(txt);
        row.appendChild(lab);
        input.addEventListener('change', () => {
          workingCfg = normalizeLt2Config({ ...workingCfg, [f.key]: !!input.checked });
          saveLt2Config(workingCfg);
          rebuildPreview?.();
        });
        sliderInputs.set(f.key, { input, valEl: null });
      } else if (f.kind === 'color') {
        const lab = document.createElement('label');
        lab.textContent = f.label;
        row.appendChild(lab);
        const input = document.createElement('input');
        input.type = 'color';
        input.value = workingCfg[f.key] || LT2_TREE_DEFAULTS[f.key];
        input.addEventListener('input', () => {
          workingCfg = normalizeLt2Config({ ...workingCfg, [f.key]: input.value });
          saveLt2Config(workingCfg);
          rebuildPreview?.();
        });
        row.appendChild(input);
        sliderInputs.set(f.key, { input, valEl: null });
      } else if (f.kind === 'select') {
        const lab = document.createElement('label');
        lab.textContent = f.label;
        row.appendChild(lab);
        const input = document.createElement('select');
        for (const opt of f.options || []) {
          const o = document.createElement('option');
          o.value = opt.value;
          o.textContent = opt.label;
          if (workingCfg[f.key] === opt.value) o.selected = true;
          input.appendChild(o);
        }
        input.addEventListener('change', () => {
          workingCfg = normalizeLt2Config({ ...workingCfg, [f.key]: input.value });
          saveLt2Config(workingCfg);
          rebuildPreview?.();
        });
        row.appendChild(input);
        sliderInputs.set(f.key, { input, valEl: null });
      } else {
        const lab = document.createElement('label');
        lab.textContent = f.label;
        row.appendChild(lab);
        const val = document.createElement('span');
        val.className = 'lt2-cfg-val';
        row.appendChild(val);
        const input = document.createElement('input');
        input.type = 'range';
        input.min = String(f.min);
        input.max = String(f.max);
        input.step = String(f.step);
        input.value = String(workingCfg[f.key]);
        val.textContent = formatVal(f.key, workingCfg[f.key]);
        input.addEventListener('input', () => {
          const n = Number(input.value);
          workingCfg = normalizeLt2Config({ ...workingCfg, [f.key]: n });
          saveLt2Config(workingCfg);
          syncInputsFromCfg();
          val.textContent = formatVal(f.key, workingCfg[f.key]);
          rebuildPreview?.();
        });
        row.appendChild(input);
        sliderInputs.set(f.key, { input, valEl: val });
      }
      g.appendChild(row);
    }
    rootEl.appendChild(g);
  }
}

function syncInputsFromCfg() {
  for (const [key, { input, valEl }] of sliderInputs) {
    if (input.tagName === 'SELECT') {
      input.value = String(workingCfg[key]);
    } else if (input.type === 'color') {
      input.value = workingCfg[key] || LT2_TREE_DEFAULTS[key] || '#ffffff';
    } else if (input.type === 'checkbox') {
      input.checked = !!workingCfg[key];
    } else {
      input.value = String(workingCfg[key]);
      if (valEl) valEl.textContent = formatVal(key, workingCfg[key]);
    }
  }
}

/**
 * Build `[side, side, cap, cap, side, side]` matching in-game trunk face order.
 * The cap material uses the shared {@link buildInteriorCapMaterial} so preview
 * matches in-game 1:1 (color, neon glow, inset border, and pattern texture).
 * Caller owns disposal of the cap material (via {@link disposeInteriorCapMaterial}).
 */
function buildTrunkMaterialArray(cfg, sideMat) {
  const cap = buildInteriorCapMaterial(cfg);
  return [sideMat, sideMat, cap, cap, sideMat, sideMat];
}

/** Tiny three.js preview: user-orbit camera, ground plane, trunk rebuilt on change. */
function mountPreview(canvasHolder) {
  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
  const rect = canvasHolder.getBoundingClientRect();
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.setSize(rect.width, rect.height, false);
  renderer.setClearColor(0x0b1219, 1);
  canvasHolder.appendChild(renderer.domElement);

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(45, rect.width / Math.max(1, rect.height), 0.05, 50);
  scene.add(new THREE.AmbientLight(0xffffff, 0.55));
  const sun = new THREE.DirectionalLight(0xffffff, 0.85);
  sun.position.set(3, 5, 2);
  scene.add(sun);

  const ground = new THREE.Mesh(
    new THREE.CircleGeometry(4, 48),
    new THREE.MeshStandardMaterial({ color: 0x2a3a32, roughness: 0.98 })
  );
  ground.rotation.x = -Math.PI / 2;
  scene.add(ground);

  /**
   * Preview bark side material. For `'stock'` texture we use a plain colored
   * material that stands in for the PNG (not shipped into this preview module).
   * For every other texture we build a canvas-backed material via the shared
   * texture module so the preview matches the in-game look.
   */
  const PREVIEW_BARK_DEFAULT = 0x77513a;
  let sideMat = new THREE.MeshStandardMaterial({ color: PREVIEW_BARK_DEFAULT, roughness: 0.92 });
  let sideMatKey = 'stock::#ffffff';
  function disposeSideMat(mat) {
    if (!mat) return;
    const tex = mat.userData?.__lt2TrunkTexture;
    if (tex) tex.dispose?.();
    mat.dispose?.();
  }
  function refreshSideMat(texture, trunkColor) {
    const t = LT2_TRUNK_SIDE_TEXTURES.includes(texture) ? texture : 'stock';
    const color = (typeof trunkColor === 'string' && /^#[0-9a-fA-F]{6}$/.test(trunkColor))
      ? trunkColor.toLowerCase()
      : '#ffffff';
    const key = `${t}::${color}`;
    if (key === sideMatKey) return sideMat;
    disposeSideMat(sideMat);
    if (t === 'stock') {
      sideMat = new THREE.MeshStandardMaterial({
        color: color === '#ffffff' ? PREVIEW_BARK_DEFAULT : color,
        roughness: 0.92,
      });
    } else {
      const tintForCanvas = color === '#ffffff' ? '#77513a' : color;
      sideMat = buildTrunkSideMaterial({ texture: t, tintHex: tintForCanvas });
    }
    sideMatKey = key;
    return sideMat;
  }

  let yaw = 0.65;
  let pitch = 0.1;
  let radius = 4.4;
  let dragging = false;
  let lastMx = 0;
  let lastMy = 0;

  const canvas = renderer.domElement;
  canvas.addEventListener('mousedown', (e) => {
    if (e.button !== 0) return;
    dragging = true;
    lastMx = e.clientX;
    lastMy = e.clientY;
    canvas.classList.add('lt2-cfg-dragging');
    try { canvas.setPointerCapture?.(e.pointerId ?? 1); } catch (_) {}
    e.preventDefault();
  });
  /** Named so {@link previewDisposer} can detach them — otherwise every open/close of the
   *  config overlay stacked another pair of window listeners for the life of the tab. */
  const onWindowMouseMove = (e) => {
    if (!dragging) return;
    const dx = e.clientX - lastMx;
    const dy = e.clientY - lastMy;
    lastMx = e.clientX;
    lastMy = e.clientY;
    yaw += dx * 0.0075;
    pitch = Math.min(1.25, Math.max(-1.25, pitch - dy * 0.0075));
  };
  const onWindowMouseUp = () => {
    if (!dragging) return;
    dragging = false;
    canvas.classList.remove('lt2-cfg-dragging');
  };
  window.addEventListener('mousemove', onWindowMouseMove);
  window.addEventListener('mouseup', onWindowMouseUp);
  canvas.addEventListener('wheel', (e) => {
    e.preventDefault();
    const factor = 1 + e.deltaY * 0.001;
    radius = Math.min(14, Math.max(1.5, radius * factor));
  }, { passive: false });

  let trunkMesh = null;
  let trunkCapMats = [];

  function disposeCaps() {
    for (const m of trunkCapMats) disposeInteriorCapMaterial(m);
    trunkCapMats = [];
  }

  function buildTrunk() {
    if (trunkMesh) {
      scene.remove(trunkMesh);
      trunkMesh.geometry?.dispose?.();
    }
    disposeCaps();
    refreshSideMat(workingCfg.trunkTexture, workingCfg.trunkColor);
    const h = workingCfg.trunkHeight;
    const halfW = workingCfg.trunkThickness;
    const geo = new THREE.BoxGeometry(halfW * 2, h, halfW * 2);
    const matArr = buildTrunkMaterialArray(workingCfg, sideMat);
    trunkCapMats = [matArr[2]];
    trunkMesh = new THREE.Mesh(geo, matArr);
    trunkMesh.position.y = h / 2;
    scene.add(trunkMesh);
    applyLt2StyleTreeBranches(trunkMesh, previewSeed >>> 0, workingCfg);
  }

  function doRebuild() {
    buildTrunk();
  }

  const ro = new ResizeObserver(() => {
    const r = canvasHolder.getBoundingClientRect();
    if (r.width < 4 || r.height < 4) return;
    renderer.setSize(r.width, r.height, false);
    camera.aspect = r.width / r.height;
    camera.updateProjectionMatrix();
  });
  ro.observe(canvasHolder);

  let running = true;
  function tick() {
    if (!running) return;
    if (autoRotateEnabled && !dragging) yaw += 0.005;
    const h = workingCfg.trunkHeight;
    const focusY = h * 0.55;
    const cx = Math.cos(yaw) * Math.cos(pitch) * radius;
    const cz = Math.sin(yaw) * Math.cos(pitch) * radius;
    const cy = focusY + Math.sin(pitch) * radius;
    camera.position.set(cx, Math.max(0.35, cy), cz);
    camera.lookAt(0, focusY, 0);
    renderer.render(scene, camera);
    requestAnimationFrame(tick);
  }
  tick();
  buildTrunk();

  previewDisposer = () => {
    running = false;
    ro.disconnect();
    window.removeEventListener('mousemove', onWindowMouseMove);
    window.removeEventListener('mouseup', onWindowMouseUp);
    if (trunkMesh) {
      scene.remove(trunkMesh);
      trunkMesh.geometry?.dispose?.();
    }
    disposeCaps();
    ground.geometry.dispose();
    ground.material.dispose();
    disposeSideMat(sideMat);
    renderer.dispose();
    renderer.domElement.remove();
  };
  return doRebuild;
}

function buildOverlay() {
  host = document.getElementById('lt2-config-root');
  if (!host) {
    host = document.createElement('div');
    host.id = 'lt2-config-root';
    document.body.appendChild(host);
  }
  ensureStyle();
  host.innerHTML = '';
  overlayEl = document.createElement('div');
  overlayEl.id = 'lt2-config-overlay';
  overlayEl.setAttribute('role', 'dialog');
  overlayEl.setAttribute('aria-modal', 'true');
  overlayEl.setAttribute('aria-label', 'LT2 tree config');
  overlayEl.innerHTML = `
    <div class="lt2-cfg-head">
      <div>
        <h2>LT2 Tree Editor</h2>
        <div class="lt2-cfg-sub">Presets save automatically. Hold LMB on the preview to orbit; wheel to zoom.</div>
      </div>
      <div class="lt2-cfg-actions">
        <button type="button" class="lt2-cfg-btn danger" id="lt2-cfg-reset">Reset active preset</button>
        <button type="button" class="lt2-cfg-btn primary" id="lt2-cfg-close">Close</button>
      </div>
    </div>
    <div class="lt2-cfg-body">
      <div class="lt2-cfg-sliders" id="lt2-cfg-sliders"></div>
      <div class="lt2-cfg-preview" id="lt2-cfg-preview">
        <div class="lt2-cfg-hint">LMB drag to orbit · wheel to zoom · auto-rotate toggles above</div>
      </div>
    </div>
  `;
  host.appendChild(overlayEl);

  const slidersRoot = overlayEl.querySelector('#lt2-cfg-sliders');
  buildPresetsGroup(slidersRoot);
  buildSliders(slidersRoot);

  const previewHolder = overlayEl.querySelector('#lt2-cfg-preview');
  rebuildPreview = mountPreview(previewHolder);

  overlayEl.querySelector('#lt2-cfg-close')?.addEventListener('click', () => closeLt2TreeConfig(true));
  overlayEl.querySelector('#lt2-cfg-reset')?.addEventListener('click', () => {
    workingCfg = resetLt2Config();
    syncInputsFromCfg();
    rebuildPreview?.();
  });
}

export function openLt2TreeConfig() {
  autoRotateEnabled = readAutoRotatePref();
  workingCfg = loadLt2Config();
  previewSeed = (Math.random() * 0xffffffff) >>> 0;
  if (!overlayEl) buildOverlay();
  else {
    syncInputsFromCfg();
    rebuildPreview?.();
  }
  overlayEl.classList.add('lt2-cfg-open');
}

export function closeLt2TreeConfig(save = true) {
  if (!overlayEl) return;
  if (save && workingCfg) saveLt2Config(workingCfg);
  overlayEl.classList.remove('lt2-cfg-open');
  if (previewDisposer) {
    try { previewDisposer(); } catch (_) {}
    previewDisposer = null;
    rebuildPreview = null;
  }
  host?.removeChild(overlayEl);
  overlayEl = null;
  sliderInputs.clear();
}
