/**
 * Freeform prefab editor: select mesh parts, move with gizmo, save JSON.
 */
import './debug-log-panel.js';
import * as THREE from 'three';
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { TransformControls } from 'three/addons/controls/TransformControls.js';
import {
  serializePrefabGroup,
  deserializePrefabGroup,
  loadAllPrefabsFromStorage,
  saveAllPrefabsToStorage,
  removePrefabKindFromStorage,
} from './prefab-serializer.js';
import { loadAxeObjAsGroup, disposeAxeObjDeep } from './axe-obj-load.js';
import {
  loadNeonSettings,
  saveNeonSettings,
  defaultNeonSettings,
  createNeonCapCanvasTexture,
  neonEmissiveIntensityFromSlider,
  applyNeonBloomToPass,
  neonAlbedoColorFromPicker,
} from './neon-tree-settings.js';
import {
  createNeonBloomPipeline,
  enableNeonBloomOnMesh,
  enableNeonBloomOnCamera,
} from './neon-bloom-pipeline.js';
import {
  defaultSawmillConvertZoneDims,
  normalizeSawmillConvertZone,
  EDITOR_DEFAULT_SAWMILL_DIMS,
} from './sawmill-convert-zone.js';
import { createPresetCanvas, TEXTURE_PRESET_OPTIONS } from './prefab-texture-presets.js';
import {
  applyProceduralTreeBranches,
  defaultTreeBranchSettings,
  normalizeTreeBranchSettings,
  hashTreeBranchSeed,
} from './tree-procedural-branches.js';

let scene, camera, renderer, controls, transformControls;
/** @type {ReturnType<typeof createNeonBloomPipeline> | null} */
let neonBloomPipeline = null;
let prefabRoot = null;
let selected = null;
let raycaster, pointer;
let currentKind = 'tree';

const BUILTIN_KINDS = [
  'tree',
  'treeSpook',
  'treeVoid',
  'plankWood',
  'plankNeon',
  'conveyor',
  'shelf',
  'ramp',
  'sawmill',
  'axe',
];
const BUILTIN_LABELS = {
  tree: 'Tree (default wood)',
  treeSpook: 'Tree (Spook wood)',
  treeVoid: 'Tree (void / purple neon)',
  plankWood: 'Plank (classic wood)',
  plankNeon: 'Plank (Spook / neon)',
  conveyor: 'Conveyor',
  shelf: 'Shop shelf (wall)',
  ramp: 'Ramp (map)',
  sawmill: 'Simple sawmill',
  axe: 'Starter axe (first-person)',
};
const RESERVED_KIND_IDS = new Set([...BUILTIN_KINDS, '__new__']);
/** Cancels stale <code>axe.obj</code> fetches when switching prefab kind. */
let axeObjEditorLoadSerial = 0;
const MAX_UNDO = 60;
const undoStack = [];
let undoing = false;
let copyBuffer = null;
let copySerial = 1;
let globalScale = 1;
let lastBakedScale = 1;

function isTypingInUi(e) {
  const tag = e.target?.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || e.target?.isContentEditable;
}

function pushUndoSnapshot() {
  if (undoing) return;
  if (!prefabRoot) return;
  bakeGlobalScaleIntoParts();
  const snap = serializePrefabGroup(prefabRoot, currentKind);
  undoStack.push(snap);
  if (undoStack.length > MAX_UNDO) undoStack.shift();
}

function restoreFromSnapshot(snap) {
  if (!snap) return;
  undoing = true;
  try {
    if (prefabRoot) {
      scene.remove(prefabRoot);
      disposeGroupDeep(prefabRoot);
      prefabRoot = null;
    }
    transformControls.detach();
    selected = null;
    prefabRoot = deserializePrefabGroup(snap);
    prefabRoot.position.set(0, 0, 0);
    scene.add(prefabRoot);
    syncPartList();
    globalScale = 1;
    lastBakedScale = 1;
    syncGlobalScaleUi();
    syncConveyorBeltUi();
    if (currentKind === 'sawmill' && prefabRoot) {
      if (!normalizeSawmillConvertZone(prefabRoot.userData?.sawmillConvertZone)) {
        prefabRoot.userData = prefabRoot.userData || {};
        prefabRoot.userData.sawmillConvertZone = defaultSawmillConvertZoneDims(EDITOR_DEFAULT_SAWMILL_DIMS);
      }
      ensureSawmillConvertZoneVisual(prefabRoot);
    }
    syncSawmillConvertZoneUi();
    syncTreeBranchUi();
    refreshProceduralBranchesInEditor();
    syncMaterialUiFromSelection();
  } finally {
    undoing = false;
  }
}

function popUndo() {
  if (undoStack.length === 0) return;
  const snap = undoStack.pop();
  restoreFromSnapshot(snap);
  setStatus('Undo.');
}

function genUniquePartName(base) {
  const taken = new Set();
  prefabRoot?.traverse((o) => {
    if (o.isMesh) taken.add(o.name);
  });
  let name = base;
  let i = 1;
  while (taken.has(name)) name = `${base}_${i++}`;
  return name;
}

function clampGlobalScale(x) {
  const v = Number(x);
  if (!Number.isFinite(v)) return 1;
  return Math.min(3, Math.max(0.25, v));
}

const BELT_PUSH_MUL_MIN = 0.25;
const BELT_PUSH_MUL_MAX = 4;
const DEFAULT_CONVEYOR_BELT_MUL = 2;

function clampBeltPushMulEditor(x) {
  const v = Number(x);
  if (!Number.isFinite(v)) return 1;
  return Math.min(BELT_PUSH_MUL_MAX, Math.max(BELT_PUSH_MUL_MIN, v));
}

function syncConveyorBeltUi() {
  const sec = document.getElementById('pe-conveyor-belt-section');
  const r = document.getElementById('pe-conveyor-belt-mul');
  const v = document.getElementById('pe-conveyor-belt-mul-val');
  if (!sec || !r || !v) return;
  const show = currentKind === 'conveyor';
  sec.style.display = show ? 'block' : 'none';
  if (!show || !prefabRoot) return;
  const mul = clampBeltPushMulEditor(prefabRoot.userData?.beltPushMul ?? 1);
  prefabRoot.userData.beltPushMul = mul;
  r.value = String(mul);
  v.textContent = mul.toFixed(2);
}

function wireConveyorBeltControls() {
  document.getElementById('pe-conveyor-belt-mul')?.addEventListener('input', (e) => {
    if (currentKind !== 'conveyor' || !prefabRoot) return;
    const mul = clampBeltPushMulEditor(e.target.value);
    prefabRoot.userData = prefabRoot.userData || {};
    prefabRoot.userData.beltPushMul = mul;
    const v = document.getElementById('pe-conveyor-belt-mul-val');
    if (v) v.textContent = mul.toFixed(2);
  });
}

function isTreePrefabKind() {
  return currentKind === 'tree' || currentKind === 'treeSpook' || currentKind === 'treeVoid';
}

function findTrunkMeshForBranches(root) {
  if (!root) return null;
  const byName = root.getObjectByName('trunk');
  if (byName && byName.isMesh) return byName;
  let best = null;
  let bestH = -1;
  root.traverse((o) => {
    if (!o.isMesh || o.userData.excludeFromPrefabSerialize) return;
    const n = String(o.name || '').toLowerCase();
    if (n.includes('leaf')) return;
    const p = o.geometry?.parameters;
    let h = 0;
    if (o.geometry?.type === 'BoxGeometry' && p?.height) h = p.height;
    else if (o.geometry?.type === 'CylinderGeometry' && p?.height) h = p.height;
    if (h > bestH) {
      bestH = h;
      best = o;
    }
  });
  return best;
}

function editorTreeBranchPreviewSeed() {
  const s = normalizeTreeBranchSettings(prefabRoot?.userData?.treeBranches);
  if (s.previewSeed != null) return s.previewSeed;
  const tag = currentKind === 'treeSpook' ? 2 : currentKind === 'treeVoid' ? 3 : 1;
  return hashTreeBranchSeed(90210, tag, 0);
}

function readTreeBranchesFromUiToUserData() {
  if (!prefabRoot || !isTreePrefabKind()) return;
  const en = document.getElementById('pe-tree-branch-enabled');
  const minEl = document.getElementById('pe-tree-branch-min');
  const maxEl = document.getElementById('pe-tree-branch-max');
  const segEl = document.getElementById('pe-tree-branch-segments');
  const prev = normalizeTreeBranchSettings(prefabRoot.userData?.treeBranches);
  prefabRoot.userData = prefabRoot.userData || {};
  prefabRoot.userData.treeBranches = normalizeTreeBranchSettings({
    enabled: en ? !!en.checked : true,
    minBranches: minEl ? Number(minEl.value) : prev.minBranches,
    maxBranches: maxEl ? Number(maxEl.value) : prev.maxBranches,
    segmentCount: segEl ? Number(segEl.value) : prev.segmentCount,
    previewSeed: prev.previewSeed,
  });
}

function syncTreeBranchUi() {
  const sec = document.getElementById('pe-tree-branch-section');
  if (!sec) return;
  const show = isTreePrefabKind();
  sec.style.display = show ? 'block' : 'none';
  if (!show || !prefabRoot) return;
  const s = normalizeTreeBranchSettings(prefabRoot.userData?.treeBranches);
  prefabRoot.userData = prefabRoot.userData || {};
  prefabRoot.userData.treeBranches = s;
  const en = document.getElementById('pe-tree-branch-enabled');
  if (en) en.checked = !!s.enabled;
  const minEl = document.getElementById('pe-tree-branch-min');
  const maxEl = document.getElementById('pe-tree-branch-max');
  const segEl = document.getElementById('pe-tree-branch-segments');
  const segLab = document.getElementById('pe-tree-branch-segments-val');
  if (minEl) minEl.value = String(s.minBranches);
  if (maxEl) maxEl.value = String(s.maxBranches);
  if (segEl) segEl.value = String(s.segmentCount);
  if (segLab) segLab.textContent = String(s.segmentCount);
}

function refreshProceduralBranchesInEditor() {
  if (!prefabRoot || !isTreePrefabKind()) return;
  readTreeBranchesFromUiToUserData();
  const trunk = findTrunkMeshForBranches(prefabRoot);
  if (!trunk) return;
  const seed = editorTreeBranchPreviewSeed();
  applyProceduralTreeBranches(trunk, prefabRoot.userData.treeBranches, seed);
}

function wireTreeBranchControls() {
  const onChange = () => {
    if (!isTreePrefabKind() || !prefabRoot) return;
    readTreeBranchesFromUiToUserData();
    refreshProceduralBranchesInEditor();
  };
  document.getElementById('pe-tree-branch-enabled')?.addEventListener('change', onChange);
  for (const id of ['pe-tree-branch-min', 'pe-tree-branch-max']) {
    document.getElementById(id)?.addEventListener('input', onChange);
    document.getElementById(id)?.addEventListener('change', onChange);
  }
  document.getElementById('pe-tree-branch-segments')?.addEventListener('input', (e) => {
    const lab = document.getElementById('pe-tree-branch-segments-val');
    if (lab) lab.textContent = String(e.target.value);
    onChange();
  });
  document.getElementById('pe-tree-branch-random')?.addEventListener('click', () => {
    if (!prefabRoot || !isTreePrefabKind()) return;
    readTreeBranchesFromUiToUserData();
    const s = normalizeTreeBranchSettings(prefabRoot.userData.treeBranches);
    s.previewSeed = (Math.random() * 2 ** 32) >>> 0;
    prefabRoot.userData.treeBranches = s;
    syncTreeBranchUi();
    refreshProceduralBranchesInEditor();
    setStatus('New random crown layout (saved with <strong>Save</strong>).');
  });
}

const PE_SAWMILL_CONVERT_ZONE_NAME = '__pe_sawmill_convert_zone';

function noopRaycast() {}

function ensureSawmillConvertZoneVisual(root) {
  if (!root) return;
  let grp = root.getObjectByName(PE_SAWMILL_CONVERT_ZONE_NAME);
  if (!grp) {
    grp = new THREE.Group();
    grp.name = PE_SAWMILL_CONVERT_ZONE_NAME;
    grp.userData.excludeFromPrefabSerialize = true;
    const geoFill = new THREE.BoxGeometry(1, 1, 1);
    const geoEdges = new THREE.BoxGeometry(1, 1, 1);
    const fill = new THREE.Mesh(
      geoFill,
      new THREE.MeshBasicMaterial({
        color: 0x33ccee,
        transparent: true,
        opacity: 0.16,
        depthWrite: false,
        side: THREE.DoubleSide,
      })
    );
    fill.name = '__pe_saw_zone_fill';
    fill.userData.excludeFromPrefabSerialize = true;
    const edges = new THREE.LineSegments(
      new THREE.EdgesGeometry(geoEdges),
      new THREE.LineBasicMaterial({
        color: 0xaaeeff,
        transparent: true,
        opacity: 0.95,
        depthWrite: false,
      })
    );
    edges.name = '__pe_saw_zone_edges';
    edges.userData.excludeFromPrefabSerialize = true;
    grp.add(fill, edges);
    grp.traverse((o) => {
      o.raycast = noopRaycast;
    });
    root.add(grp);
  }
  updateSawmillConvertZoneVisual(root);
}

function updateSawmillConvertZoneVisual(root) {
  const grp = root?.getObjectByName(PE_SAWMILL_CONVERT_ZONE_NAME);
  if (!grp) return;
  const z =
    normalizeSawmillConvertZone(root.userData?.sawmillConvertZone) ||
    defaultSawmillConvertZoneDims(EDITOR_DEFAULT_SAWMILL_DIMS);
  const sx = Math.max(0.05, z.xMax - z.xMin);
  const sy = Math.max(0.05, 2 * z.halfY);
  const sz = Math.max(0.05, 2 * z.halfZ);
  grp.position.set((z.xMin + z.xMax) / 2, z.centerY, 0);
  grp.scale.set(sx, sy, sz);
}

function readSawmillConvertZoneFromSliders() {
  const xmin = Number(document.getElementById('pe-saw-zone-xmin')?.value);
  const xmax = Number(document.getElementById('pe-saw-zone-xmax')?.value);
  const halfZ = Number(document.getElementById('pe-saw-zone-halfz')?.value);
  const centerY = Number(document.getElementById('pe-saw-zone-centery')?.value);
  const halfY = Number(document.getElementById('pe-saw-zone-halfy')?.value);
  if (![xmin, xmax, halfZ, centerY, halfY].every(Number.isFinite)) return null;
  let lo = xmin;
  let hi = xmax;
  if (lo > hi) [lo, hi] = [hi, lo];
  return normalizeSawmillConvertZone({
    xMin: lo,
    xMax: hi,
    halfZ,
    centerY,
    halfY,
  });
}

function syncSawmillConvertZoneUi() {
  const sec = document.getElementById('pe-sawmill-zone-section');
  if (sec) sec.style.display = currentKind === 'sawmill' ? 'block' : 'none';
  if (currentKind !== 'sawmill' || !prefabRoot) return;
  let z = normalizeSawmillConvertZone(prefabRoot.userData?.sawmillConvertZone);
  if (!z) {
    z = defaultSawmillConvertZoneDims(EDITOR_DEFAULT_SAWMILL_DIMS);
    prefabRoot.userData = prefabRoot.userData || {};
    prefabRoot.userData.sawmillConvertZone = z;
  }
  const setR = (id, val, dec = 2) => {
    const el = document.getElementById(id);
    if (el) el.value = String(val);
    const lab = document.getElementById(`${id}-val`);
    if (lab) lab.textContent = Number(val).toFixed(dec);
  };
  setR('pe-saw-zone-xmin', z.xMin);
  setR('pe-saw-zone-xmax', z.xMax);
  setR('pe-saw-zone-halfz', z.halfZ);
  setR('pe-saw-zone-centery', z.centerY);
  setR('pe-saw-zone-halfy', z.halfY);
  ensureSawmillConvertZoneVisual(prefabRoot);
}

function applySawmillConvertZoneFromSliders() {
  if (currentKind !== 'sawmill' || !prefabRoot) return;
  const z = readSawmillConvertZoneFromSliders();
  if (!z) return;
  prefabRoot.userData = prefabRoot.userData || {};
  prefabRoot.userData.sawmillConvertZone = z;
  const z2 = z;
  const lab = (id, val, dec = 2) => {
    const el = document.getElementById(`${id}-val`);
    if (el) el.textContent = Number(val).toFixed(dec);
  };
  lab('pe-saw-zone-xmin', z2.xMin);
  lab('pe-saw-zone-xmax', z2.xMax);
  lab('pe-saw-zone-halfz', z2.halfZ);
  lab('pe-saw-zone-centery', z2.centerY);
  lab('pe-saw-zone-halfy', z2.halfY);
  updateSawmillConvertZoneVisual(prefabRoot);
}

function wireSawmillConvertZoneControls() {
  const ids = [
    'pe-saw-zone-xmin',
    'pe-saw-zone-xmax',
    'pe-saw-zone-halfz',
    'pe-saw-zone-centery',
    'pe-saw-zone-halfy',
  ];
  for (const id of ids) {
    document.getElementById(id)?.addEventListener('input', () => applySawmillConvertZoneFromSliders());
  }
  document.getElementById('pe-saw-zone-reset')?.addEventListener('click', () => {
    if (currentKind !== 'sawmill' || !prefabRoot) return;
    prefabRoot.userData.sawmillConvertZone = defaultSawmillConvertZoneDims(EDITOR_DEFAULT_SAWMILL_DIMS);
    syncSawmillConvertZoneUi();
  });
}

function syncNeonTreeUi() {
  const s = loadNeonSettings();
  const c = document.getElementById('pe-neon-color');
  const r = document.getElementById('pe-neon-intensity');
  const v = document.getElementById('pe-neon-intensity-val');
  if (c) c.value = s.color;
  if (r) r.value = String(s.intensity);
  if (v) v.textContent = Number(s.intensity).toFixed(1);
}

function wireNeonTreeControls() {
  const c = document.getElementById('pe-neon-color');
  const r = document.getElementById('pe-neon-intensity');
  if (!c || !r) return;
  const apply = () => {
    const base = defaultNeonSettings();
    let color = String(c.value || base.color);
    if (!/^#[0-9a-fA-F]{6}$/.test(color)) color = base.color;
    let intensity = Number(r.value);
    if (!Number.isFinite(intensity)) intensity = base.intensity;
    intensity = Math.min(12, Math.max(0.5, intensity));
    saveNeonSettings({ color, intensity });
    const v = document.getElementById('pe-neon-intensity-val');
    if (v) v.textContent = intensity.toFixed(1);
    applyNeonBloomToPass(neonBloomPipeline?.bloomPass ?? null, intensity);
    refreshPlankNeonMaterialOnRoot();
  };
  c.addEventListener('input', apply);
  r.addEventListener('input', apply);
}

function syncGlobalScaleUi() {
  const r = document.getElementById('pe-global-scale');
  const v = document.getElementById('pe-global-scale-val');
  if (r) r.value = String(globalScale.toFixed(2));
  if (v) v.textContent = globalScale.toFixed(2);
}

function bakeGlobalScaleIntoParts() {
  if (!prefabRoot) return;
  if (Math.abs(globalScale - lastBakedScale) < 1e-6) return;
  const factor = globalScale / Math.max(1e-6, lastBakedScale);
  prefabRoot.traverse((o) => {
    if (!o.isMesh) return;
    o.position.multiplyScalar(factor);
    o.scale.multiplyScalar(factor);
  });
  lastBakedScale = globalScale;
}

function primaryMeshMaterial(mesh) {
  const m = mesh?.material;
  if (!m) return null;
  return Array.isArray(m) ? m[0] : m;
}

function applyColorToMesh(mesh, hexString) {
  const m = primaryMeshMaterial(mesh);
  if (!m) return;
  const c = new THREE.Color(hexString);
  if (m.color) m.color.set(c);
}

function syncMaterialUiFromSelection() {
  const color = document.getElementById('pe-mat-color');
  const hint = document.getElementById('pe-tex-hint');
  if (!selected || !selected.isMesh) {
    if (color) color.value = '#888888';
    if (hint) hint.textContent = 'No selection.';
    return;
  }
  const m = primaryMeshMaterial(selected);
  if (color && m?.color) color.value = `#${m.color.getHexString()}`;
  if (hint) {
    hint.textContent = m?.map
      ? 'Selection has a color map (included when you save the prefab).'
      : 'No color map on selection.';
  }
}

function applyMaterialToSelected() {
  if (!selected || !selected.isMesh) return;
  const color = document.getElementById('pe-mat-color');
  if (!color) return;
  pushUndoSnapshot();
  applyColorToMesh(selected, color.value);
  setStatus(`Updated color on <strong>${selected.name || 'part'}</strong>.`);
}

function applyMaterialToAll() {
  if (!prefabRoot) return;
  const color = document.getElementById('pe-mat-color');
  if (!color) return;
  pushUndoSnapshot();
  prefabRoot.traverse((o) => {
    if (o.isMesh) applyColorToMesh(o, color.value);
  });
  setStatus('Updated color on <strong>all parts</strong>.');
}

function applyTextureMapFromFileInput() {
  if (!selected?.isMesh) {
    setStatus('Select a mesh part first.');
    return;
  }
  const inp = document.getElementById('pe-tex-file');
  const f = inp?.files?.[0];
  if (!f) {
    setStatus('Choose an image file, then click <strong>Apply image as map</strong>.');
    return;
  }
  pushUndoSnapshot();
  const reader = new FileReader();
  reader.onload = () => {
    const dataUrl = reader.result;
    if (typeof dataUrl !== 'string') return;
    const m = primaryMeshMaterial(selected);
    if (!m) return;
    const tex = new THREE.TextureLoader().load(dataUrl);
    tex.colorSpace = THREE.SRGBColorSpace;
    if (m.map) m.map.dispose();
    m.map = tex;
    m.needsUpdate = true;
    syncMaterialUiFromSelection();
    setStatus(`Applied color map on <strong>${selected.name}</strong>.`);
  };
  reader.readAsDataURL(f);
}

function clearTextureMapOnSelected() {
  if (!selected?.isMesh) {
    setStatus('Select a mesh part first.');
    return;
  }
  pushUndoSnapshot();
  const m = primaryMeshMaterial(selected);
  if (!m) return;
  if (m.map) {
    m.map.dispose();
    m.map = null;
    m.needsUpdate = true;
  }
  syncMaterialUiFromSelection();
  setStatus(`Cleared color map on <strong>${selected.name}</strong>.`);
}

function threeCanvasPresetTexture(result) {
  if (!result?.canvas) return null;
  const tex = new THREE.CanvasTexture(result.canvas);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.wrapS = THREE.RepeatWrapping;
  tex.wrapT = THREE.RepeatWrapping;
  tex.repeat.set(result.repeat[0], result.repeat[1]);
  return tex;
}

function applyTexturePresetToSelected() {
  const sel = document.getElementById('pe-tex-preset');
  const id = String(sel?.value ?? '');
  if (!selected?.isMesh) {
    setStatus('Select a part first.');
    return;
  }
  if (!id) {
    setStatus('Choose a preset in the list, then click <strong>Apply preset</strong>.');
    return;
  }
  const res = createPresetCanvas(id);
  if (!res) {
    setStatus('Unknown preset.');
    return;
  }
  pushUndoSnapshot();
  const m = primaryMeshMaterial(selected);
  if (!m) return;
  const tex = threeCanvasPresetTexture(res);
  if (!tex) return;
  if (m.map) m.map.dispose();
  m.map = tex;
  m.needsUpdate = true;
  syncMaterialUiFromSelection();
  const label = sel.options[sel.selectedIndex]?.text ?? id;
  setStatus(`Applied preset <strong>${label}</strong> on <strong>${selected.name}</strong>.`);
}

/** Default sizes for editor primitives (meters-ish). */
function makeDefaultGeometry(type) {
  switch (type) {
    case 'box':
      return new THREE.BoxGeometry(0.25, 0.25, 0.25);
    case 'cylinder':
      return new THREE.CylinderGeometry(0.1, 0.1, 0.25, 16, 1);
    case 'sphere':
      return new THREE.SphereGeometry(0.14, 16, 12);
    case 'cone':
      return new THREE.ConeGeometry(0.13, 0.26, 16, 1);
    case 'torus':
      return new THREE.TorusGeometry(0.1, 0.032, 14, 28);
    case 'ring':
      return new THREE.RingGeometry(0.06, 0.1, 32, 1);
    case 'circle':
      return new THREE.CircleGeometry(0.12, 36);
    case 'plane':
      return new THREE.PlaneGeometry(0.35, 0.35, 1, 1);
    case 'capsule':
      return new THREE.CapsuleGeometry(0.08, 0.2, 4, 12);
    case 'tetrahedron':
      return new THREE.TetrahedronGeometry(0.16, 0);
    case 'octahedron':
      return new THREE.OctahedronGeometry(0.16, 0);
    case 'dodecahedron':
      return new THREE.DodecahedronGeometry(0.14, 0);
    case 'icosahedron':
      return new THREE.IcosahedronGeometry(0.15, 0);
    case 'torus_knot':
      return new THREE.TorusKnotGeometry(0.09, 0.026, 56, 10, 2, 3);
    case 'rounded_box': {
      const w = 0.2;
      const h = 0.16;
      const d = 0.12;
      const segments = 4;
      const radius = Math.min(0.028, w / 2 - 0.001, h / 2 - 0.001, d / 2 - 0.001);
      return new RoundedBoxGeometry(w, h, d, segments, radius);
    }
    default:
      return new THREE.BoxGeometry(0.25, 0.25, 0.25);
  }
}

/** Metadata for save/load (RoundedBoxGeometry extends BoxGeometry; parameters alone are not enough). */
function prefabGeomMetaForPrimitive(type) {
  if (type === 'rounded_box') {
    const w = 0.2;
    const h = 0.16;
    const d = 0.12;
    const segments = 4;
    const radius = Math.min(0.028, w / 2 - 0.001, h / 2 - 0.001, d / 2 - 0.001);
    return { type: 'rounded_box', width: w, height: h, depth: d, segments, radius };
  }
  return null;
}

function quickAddShape(type) {
  const sel = document.getElementById('pe-new-type');
  if (sel) sel.value = type;
  addNewPart();
}

function addNewPart() {
  if (!prefabRoot) return;
  const nameEl = document.getElementById('pe-new-name');
  const typeEl = document.getElementById('pe-new-type');
  const colorEl = document.getElementById('pe-mat-color');
  const rawName = String(nameEl?.value ?? '').trim() || 'part';
  const name = genUniquePartName(rawName);
  const t = String(typeEl?.value ?? 'box');
  const geo = makeDefaultGeometry(t);
  const mat = new THREE.MeshStandardMaterial({
    color: colorEl?.value ?? '#888888',
    roughness: 0.85,
    metalness: 0.05,
  });
  const mesh = new THREE.Mesh(geo, mat);
  mesh.name = name;
  mesh.castShadow = true;
  mesh.receiveShadow = true;
  mesh.position.set(0, 0.25, 0);
  const pg = prefabGeomMetaForPrimitive(t);
  if (pg) mesh.userData.prefabGeom = pg;
  pushUndoSnapshot();
  prefabRoot.add(mesh);
  if (currentKind === 'plankNeon') enableNeonBloomOnMesh(mesh);
  syncPartList();
  selected = mesh;
  transformControls.attach(mesh);
  const parts = document.getElementById('pe-parts');
  if (parts) parts.value = mesh.name;
  if (nameEl) nameEl.value = mesh.name;
  setStatus(`Added <strong>${mesh.name}</strong>.`);
}

function deleteSelected() {
  if (!prefabRoot || !selected || selected === prefabRoot) return;
  pushUndoSnapshot();
  transformControls.detach();
  const disposeMesh = (o) => {
    if (!o.isMesh) return;
    o.geometry?.dispose();
    const m = o.material;
    if (Array.isArray(m)) m.forEach((x) => x.dispose?.());
    else m?.dispose?.();
  };
  const par = selected.parent;
  if (!par) return;
  if (selected.isMesh) {
    disposeMesh(selected);
    par.remove(selected);
  } else if (selected.isGroup) {
    selected.traverse(disposeMesh);
    par.remove(selected);
  } else {
    return;
  }
  selected = null;
  syncPartList();
  setStatus('Deleted selection.');
}

/**
 * Replace one cylinder with several shorter cylinders along a planar spline (baked “bend”).
 * Works in the mesh’s local space (before parent transform); result is a Group under the same parent.
 */
function bakeCylinderPlaneBend(mesh, totalBendDeg, segments) {
  if (!mesh?.isMesh || mesh.geometry?.type !== 'CylinderGeometry' || !mesh.parent) return false;
  const gp = mesh.geometry.parameters;
  const parent = mesh.parent;
  const baseName = String(mesh.name || 'cylinder').replace(/_bend_\d+$/, '');
  pushUndoSnapshot();

  const H = gp.height;
  const rTop = gp.radiusTop;
  const rBottom = gp.radiusBottom;
  const radSeg = Math.max(5, Math.min(32, gp.radialSegments ?? 10));
  const mat0 = mesh.material;
  const matTpl = Array.isArray(mat0) ? mat0[0] : mat0;
  const matBase = matTpl?.clone?.() ?? matTpl;

  const N = Math.max(3, Math.min(24, Math.floor(segments)));
  const beta = THREE.MathUtils.degToRad(Math.max(0, Math.min(175, totalBendDeg)));
  const bulge = (H * 0.5) * Math.sin(beta * 0.5) * 0.85;

  const p0 = new THREE.Vector3(0, -H / 2, 0);
  const p1 = new THREE.Vector3(bulge * 0.42, -H / 4, 0);
  const p2 = new THREE.Vector3(bulge * 0.42, H / 4, 0);
  const p3 = new THREE.Vector3(0, H / 2, 0);
  const curve = new THREE.CatmullRomCurve3([p0, p1, p2, p3], false, 'catmullrom', 0.45);
  const pts = curve.getPoints(N);

  const grp = new THREE.Group();
  grp.name = `${baseName}_bent`;
  grp.position.copy(mesh.position);
  grp.quaternion.copy(mesh.quaternion);
  grp.scale.copy(mesh.scale);

  const up = new THREE.Vector3(0, 1, 0);
  const mid = new THREE.Vector3();
  const dir = new THREE.Vector3();
  const q = new THREE.Quaternion();

  for (let i = 0; i < N; i++) {
    const a = pts[i];
    const b = pts[i + 1];
    mid.addVectors(a, b).multiplyScalar(0.5);
    dir.copy(b).sub(a);
    const len = dir.length();
    if (len < 1e-5) continue;
    dir.normalize();
    const cylGeom = new THREE.CylinderGeometry(rTop, rBottom, len, radSeg, 1);
    const piece = new THREE.Mesh(cylGeom, matBase.clone ? matBase.clone() : matBase);
    piece.name = `${baseName}_bend_${i}`;
    piece.position.copy(mid);
    q.setFromUnitVectors(up, dir);
    piece.setRotationFromQuaternion(q);
    piece.castShadow = true;
    piece.receiveShadow = true;
    grp.add(piece);
  }

  parent.remove(mesh);
  mesh.geometry?.dispose();
  parent.add(grp);

  syncPartList();
  const first = grp.children.find((c) => c.isMesh);
  if (first) {
    selected = first;
    const sel = document.getElementById('pe-parts');
    if (sel) sel.value = first.name;
    transformControls.attach(first);
  } else {
    selected = null;
    transformControls.detach();
  }
  return true;
}

function wireCylinderBendTools() {
  const deg = document.getElementById('pe-bend-deg');
  const degV = document.getElementById('pe-bend-deg-val');
  const seg = document.getElementById('pe-bend-seg');
  const segV = document.getElementById('pe-bend-seg-val');
  deg?.addEventListener('input', () => {
    if (degV) degV.textContent = String(deg.value);
  });
  seg?.addEventListener('input', () => {
    if (segV) segV.textContent = String(seg.value);
  });
  document.getElementById('pe-bend-apply')?.addEventListener('click', () => {
    if (!selected?.isMesh || selected.geometry?.type !== 'CylinderGeometry') {
      setStatus('Select a <strong>cylinder</strong> part first (e.g. <code>axe_handle</code>).');
      return;
    }
    const d = Number(deg?.value ?? 32);
    const s = Number(seg?.value ?? 10);
    if (bakeCylinderPlaneBend(selected, d, s)) {
      setStatus('Baked bend into segments. <strong>Save</strong> when it looks right.');
    }
  });
}

function copySelection() {
  if (!selected || !selected.isMesh) return;
  copyBuffer = {
    kind: 'meshClone',
    fromName: selected.name || 'part',
  };
  setStatus(`Copied <strong>${selected.name || 'part'}</strong>.`);
}

function pasteCopy() {
  if (!prefabRoot || !copyBuffer || copyBuffer.kind !== 'meshClone') return;
  if (!selected || !selected.isMesh) return;

  const src = selected;
  const clone = new THREE.Mesh(src.geometry, src.material);
  clone.userData = src.userData && typeof src.userData === 'object' ? { ...src.userData } : {};
  clone.castShadow = true;
  clone.receiveShadow = true;
  clone.position.copy(src.position);
  clone.rotation.copy(src.rotation);
  clone.scale.copy(src.scale);

  const baseName = `${src.name || 'part'}_copy${copySerial++}`;
  clone.name = genUniquePartName(baseName);
  clone.position.add(new THREE.Vector3(0.15, 0, 0.15));
  prefabRoot.add(clone);
  if (currentKind === 'plankNeon') enableNeonBloomOnMesh(clone);
  syncPartList();

  selected = clone;
  transformControls.attach(clone);
  const sel = document.getElementById('pe-parts');
  if (sel) sel.value = clone.name;
  setStatus(`Pasted <strong>${clone.name}</strong>.`);
  pushUndoSnapshot();
}

function onKeyDown(e) {
  if (isTypingInUi(e)) return;

  if ((e.ctrlKey || e.metaKey) && e.code === 'KeyZ' && !e.shiftKey) {
    e.preventDefault();
    popUndo();
    return;
  }
  if ((e.ctrlKey || e.metaKey) && e.code === 'KeyC') {
    e.preventDefault();
    copySelection();
    return;
  }
  if ((e.ctrlKey || e.metaKey) && e.code === 'KeyV') {
    e.preventDefault();
    pasteCopy();
    return;
  }
}

/** Matches in-game box trunks (vertical length on Y), not cylinders. */
function buildDefaultTree() {
  const g = new THREE.Group();
  const tw = 0.22;
  const th = 2.05;
  const trunk = new THREE.Mesh(
    new THREE.BoxGeometry(tw, th, tw),
    new THREE.MeshStandardMaterial({ color: 0x5c4030, roughness: 0.88, metalness: 0.04 })
  );
  trunk.name = 'trunk';
  trunk.position.y = th / 2 + 0.02;
  trunk.castShadow = true;
  trunk.receiveShadow = true;
  g.add(trunk);
  g.userData = g.userData || {};
  g.userData.treeBranches = defaultTreeBranchSettings();
  return g;
}

function buildDefaultTreeSpook() {
  const g = new THREE.Group();
  const tw = 0.22;
  const th = 2.05;
  const trunk = new THREE.Mesh(
    new THREE.BoxGeometry(tw, th, tw),
    new THREE.MeshStandardMaterial({ color: 0x0a0a0a, roughness: 1, metalness: 0 })
  );
  trunk.name = 'trunk';
  trunk.position.y = th / 2 + 0.02;
  trunk.castShadow = true;
  trunk.receiveShadow = true;
  g.add(trunk);
  g.userData = g.userData || {};
  g.userData.treeBranches = defaultTreeBranchSettings();
  return g;
}

/** Same mesh layout as default wood — game applies purple neon on cut faces for `logSkin: void`. */
function buildDefaultTreeVoid() {
  return buildDefaultTree();
}

function buildDefaultConveyor() {
  const g = new THREE.Group();
  const W = 0.9;
  const H = 0.16;
  const L = 4;
  const rollerR = Math.min(0.095, W * 0.12);
  const gap = Math.max(0.04, Math.min(0.12, L * 0.03));
  const pitch = rollerR * 2 + gap;
  const n = Math.max(3, Math.min(80, Math.floor(L / pitch)));
  const rollerLen = W * 0.94;
  const matRoller = new THREE.MeshStandardMaterial({
    color: 0x54545e,
    roughness: 0.7,
    metalness: 0.3,
  });
  const matRail = new THREE.MeshStandardMaterial({
    color: 0x2c303a,
    roughness: 0.88,
    metalness: 0.08,
  });
  let ri = 0;
  const zStart = -L / 2 + rollerR + 0.02;
  for (let i = 0; i < n; i++) {
    const zp = zStart + i * pitch;
    if (zp > L / 2 - rollerR - 0.03) break;
    const cyl = new THREE.Mesh(
      new THREE.CylinderGeometry(rollerR, rollerR, rollerLen, 12),
      matRoller
    );
    cyl.rotation.z = Math.PI / 2;
    cyl.position.set(0, rollerR + 0.015, zp);
    cyl.name = `roller_${ri++}`;
    cyl.castShadow = true;
    cyl.receiveShadow = true;
    g.add(cyl);
  }
  const railH = Math.max(0.05, Math.min(H * 0.55, 0.14));
  const railT = Math.max(0.03, W * 0.048);
  const railGeo = new THREE.BoxGeometry(railT, railH, L);
  const railL = new THREE.Mesh(railGeo, matRail);
  railL.name = 'rail_left';
  railL.position.set(-W / 2 - railT / 2, railH / 2 + rollerR * 0.45, 0);
  railL.castShadow = true;
  const railR = new THREE.Mesh(railGeo, matRail);
  railR.name = 'rail_right';
  railR.position.set(W / 2 + railT / 2, railH / 2 + rollerR * 0.45, 0);
  g.add(railL, railR);
  g.userData = g.userData || {};
  g.userData.beltPushMul = DEFAULT_CONVEYOR_BELT_MUL;
  return g;
}

function buildDefaultShelf() {
  const g = new THREE.Group();
  const wood = new THREE.MeshStandardMaterial({
    color: 0x4a3528,
    roughness: 0.9,
    metalness: 0.05,
  });
  const boardW = 3.15;
  const boardD = 0.56;
  const boardThick = 0.14;
  const lowY = 1.28;
  const highY = 1.88;
  const backThick = 0.12;
  const backD = 0.58;
  const backH = 1.78;
  const shelfLow = new THREE.Mesh(
    new THREE.BoxGeometry(boardW, boardThick, boardD),
    wood
  );
  shelfLow.name = 'shelf_lower';
  shelfLow.position.set(0, lowY, 0);
  shelfLow.castShadow = true;
  shelfLow.receiveShadow = true;
  const shelfHigh = new THREE.Mesh(
    new THREE.BoxGeometry(boardW, boardThick, boardD),
    wood
  );
  shelfHigh.name = 'shelf_upper';
  shelfHigh.position.set(0, highY, 0);
  shelfHigh.castShadow = true;
  shelfHigh.receiveShadow = true;
  const shelfBack = new THREE.Mesh(
    new THREE.BoxGeometry(backThick, backH, backD),
    wood
  );
  shelfBack.name = 'shelf_back';
  shelfBack.position.set(-(boardW / 2 + backThick / 2), backH / 2, 0);
  shelfBack.castShadow = true;
  g.add(shelfLow, shelfHigh, shelfBack);
  return g;
}

function applyRampTiltDefault(mesh, def) {
  const rise = def.rise != null ? def.rise : 0.35;
  mesh.rotation.x = -Math.atan2(rise, def.baseD);
  mesh.position.y = def.baseH / 2;
  mesh.updateMatrixWorld(true);
  const box = new THREE.Box3().setFromObject(mesh);
  mesh.position.y -= box.min.y;
}

function buildDefaultRamp() {
  const def = { baseW: 2, baseH: 0.15, baseD: 3, rise: 0.35, color: 0x8b7355 };
  const g = new THREE.Group();
  const mesh = new THREE.Mesh(
    new THREE.BoxGeometry(def.baseW, def.baseH, def.baseD),
    new THREE.MeshStandardMaterial({
      color: def.color,
      roughness: 0.88,
      metalness: 0.06,
    })
  );
  mesh.name = 'ramp_run';
  mesh.castShadow = true;
  mesh.receiveShadow = true;
  applyRampTiltDefault(mesh, def);
  g.add(mesh);
  return g;
}

/** Same grain recipe as game.js getSawmillPlankBoardTexture — editor preview only. */
function createClassicPlankBoardTexture() {
  const W = 256;
  const H = 256;
  const canvas = document.createElement('canvas');
  canvas.width = W;
  canvas.height = H;
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = '#8f6a4e';
  ctx.fillRect(0, 0, W, H);
  for (let x = 0; x < W; x += 2) {
    const t = 0.72 + Math.random() * 0.34;
    ctx.fillStyle = `rgba(${Math.floor(118 * t)}, ${Math.floor(86 * t)}, ${Math.floor(58 * t)}, 0.28)`;
    ctx.fillRect(x, 0, 1 + (Math.random() * 2.5) | 0, H);
  }
  for (let i = 0; i < 1800; i++) {
    ctx.fillStyle = `rgba(32,20,10,${0.025 + Math.random() * 0.07})`;
    ctx.fillRect((Math.random() * W) | 0, (Math.random() * H) | 0, 1, 2 + ((Math.random() * 5) | 0));
  }
  for (let i = 0; i < 900; i++) {
    ctx.fillStyle = `rgba(255,230,200,${0.02 + Math.random() * 0.06})`;
    ctx.fillRect((Math.random() * W) | 0, (Math.random() * H) | 0, 1, 1);
  }
  const tex = new THREE.CanvasTexture(canvas);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.wrapS = THREE.RepeatWrapping;
  tex.wrapT = THREE.RepeatWrapping;
  tex.needsUpdate = true;
  return tex;
}

/** Box (L, W, T) on X/Y/Z like game sawmill output; thin edge on Z, length on X. */
function buildDefaultPlankWood() {
  const g = new THREE.Group();
  const plankL = 1.35;
  const plankW = 0.14;
  const plankT = 0.055;
  const tex = createClassicPlankBoardTexture();
  const mat = new THREE.MeshStandardMaterial({
    color: 0xffffff,
    map: tex,
    roughness: 0.86,
    metalness: 0.05,
  });
  const mesh = new THREE.Mesh(new THREE.BoxGeometry(plankL, plankW, plankT), mat);
  mesh.name = 'plank';
  mesh.position.set(0, plankW / 2, 0);
  mesh.castShadow = true;
  mesh.receiveShadow = true;
  g.add(mesh);
  return g;
}

function makeNeonPlankMaterial() {
  const s = loadNeonSettings();
  const capMap = createNeonCapCanvasTexture(THREE, s.color);
  return new THREE.MeshStandardMaterial({
    color: neonAlbedoColorFromPicker(s.color),
    map: capMap,
    emissive: new THREE.Color(s.color),
    emissiveMap: capMap,
    emissiveIntensity: neonEmissiveIntensityFromSlider(s.intensity),
    roughness: 0.28,
    metalness: 0,
    toneMapped: true,
  });
}

function buildDefaultPlankNeon() {
  const g = new THREE.Group();
  const plankL = 1.35;
  const plankW = 0.14;
  const plankT = 0.055;
  const mesh = new THREE.Mesh(new THREE.BoxGeometry(plankL, plankW, plankT), makeNeonPlankMaterial());
  mesh.name = 'plank';
  mesh.position.set(0, plankW / 2, 0);
  mesh.castShadow = true;
  mesh.receiveShadow = true;
  enableNeonBloomOnMesh(mesh);
  g.add(mesh);
  return g;
}

/** When neon color/intensity changes, refresh the Spook plank preview mesh. */
function refreshPlankNeonMaterialOnRoot() {
  if (!prefabRoot || currentKind !== 'plankNeon') return;
  prefabRoot.traverse((o) => {
    if (!o.isMesh || o.name !== 'plank') return;
    const old = o.material;
    o.material = makeNeonPlankMaterial();
    if (old && !Array.isArray(old)) {
      old.map?.dispose?.();
      old.emissiveMap?.dispose?.();
      old.dispose?.();
    }
  });
}

/** FRONT on +X, BACK on deep −X — matches game.js extended tunnel. */
function addSawmillFrontBackEndLabelsPrefab(g, oh, td, backExtra) {
  const makeTex = (label) => {
    const pad = 12;
    const fontPx = 22;
    const canvas = document.createElement('canvas');
    const ctx = canvas.getContext('2d');
    ctx.font = `800 ${fontPx}px system-ui, "Segoe UI", sans-serif`;
    const tw = ctx.measureText(label).width;
    const w = Math.ceil(Math.min(512, Math.max(64, tw + pad * 2)));
    const h = Math.ceil(fontPx + pad * 2);
    canvas.width = w;
    canvas.height = h;
    ctx.fillStyle = '#1c1612';
    ctx.fillRect(0, 0, w, h);
    ctx.strokeStyle = 'rgba(0,0,0,0.2)';
    ctx.lineWidth = 2;
    ctx.strokeRect(2, 2, w - 4, h - 4);
    ctx.fillStyle = '#f5ebe0';
    ctx.font = `800 ${fontPx}px system-ui, "Segoe UI", sans-serif`;
    ctx.textBaseline = 'middle';
    ctx.fillText(label, pad, h / 2);
    const tex = new THREE.CanvasTexture(canvas);
    tex.colorSpace = THREE.SRGBColorSpace;
    tex.needsUpdate = true;
    return { tex, aspect: w / h };
  };
  const addOne = (text, x, xSign) => {
    const { tex, aspect } = makeTex(text);
    const mat = new THREE.MeshBasicMaterial({
      map: tex,
      transparent: true,
      polygonOffset: true,
      polygonOffsetFactor: -2,
      polygonOffsetUnits: -2,
    });
    const planeW = 0.95;
    const planeH = planeW / aspect;
    const mesh = new THREE.Mesh(new THREE.PlaneGeometry(planeW, planeH), mat);
    mesh.name = text === 'FRONT' ? 'saw_label_front' : 'saw_label_back';
    const y = oh * 0.52;
    mesh.position.set(x, y, 0);
    mesh.lookAt(x + xSign * 2.5, y, 0);
    g.add(mesh);
  };
  addOne('FRONT', td / 2 + 0.055, 1);
  addOne('BACK', -(td / 2 + backExtra + 0.055), -1);
}

/** Matches in-game procedural sawmill (game.js) — editable in editor, used when prefab saved. */
function buildDefaultSawmill() {
  const wall = new THREE.MeshStandardMaterial({
    color: 0x5c5348,
    roughness: 0.82,
    metalness: 0.06,
  });
  const roof = new THREE.MeshStandardMaterial({
    color: 0x4a4540,
    roughness: 0.88,
    metalness: 0.04,
  });
  const g = new THREE.Group();

  // Hollow tunnel frame (matches game SAWMILL dims).
  const ow = 2.4;
  const oh = 1.02;
  const od = 1.8;
  const tw = 1.05;
  const th = 1.05;
  const td = 2.1;
  const tExtra = 0.95;
  const tunnelLen = td + tExtra;
  const tCx = -tExtra / 2;
  const sideT = Math.max(0.2, (od - tw) / 2);
  const topT = Math.max(0.2, (oh - th) / 2);

  // Tunnel axis is local X; extra length toward −X (BACK), matches game.js.
  const sideGeo = new THREE.BoxGeometry(tunnelLen, oh, sideT);
  const front = new THREE.Mesh(sideGeo, wall);
  front.name = 'saw_side_F';
  front.position.set(tCx, oh / 2, tw / 2 + sideT / 2);
  front.castShadow = true;
  front.receiveShadow = true;
  const back = front.clone();
  back.name = 'saw_side_B';
  back.position.z *= -1;
  const topRail = new THREE.Mesh(new THREE.BoxGeometry(tunnelLen, topT, tw), wall);
  topRail.name = 'saw_top';
  topRail.position.set(tCx, th + topT / 2, 0);
  topRail.castShadow = true;
  topRail.receiveShadow = true;
  const botRail = new THREE.Mesh(new THREE.BoxGeometry(tunnelLen, topT, tw), wall);
  botRail.name = 'saw_bottom';
  botRail.position.set(tCx, topT / 2, 0);
  botRail.castShadow = true;
  botRail.receiveShadow = true;

  const roofCap = new THREE.Mesh(new THREE.BoxGeometry(ow + 0.12, 0.1, od + 0.12), roof);
  roofCap.name = 'saw_roof';
  roofCap.position.y = oh + 0.05;
  roofCap.castShadow = true;
  roofCap.receiveShadow = true;

  // External belt + funnel on −local X (BACK), matches game.js SAWMILL.beltBackStickOut
  const beltMat = new THREE.MeshStandardMaterial({ color: 0x6a6f78, roughness: 0.82, metalness: 0.18 });
  const intakeLen = 0.9;
  const beltStick = 0.82;
  const beltCenterX = -(td / 2 + intakeLen / 2 - 0.11) - beltStick;
  const belt = new THREE.Mesh(new THREE.BoxGeometry(intakeLen, 0.16, tw + 0.45), beltMat);
  belt.name = 'saw_intake_belt';
  belt.position.set(beltCenterX, 0.12, 0);
  belt.castShadow = true;
  belt.receiveShadow = true;
  const ribMat = new THREE.MeshStandardMaterial({ color: 0x545a66, roughness: 0.7, metalness: 0.25 });
  for (let i = 0; i < 9; i++) {
    const rib = new THREE.Mesh(new THREE.BoxGeometry(0.03, 0.02, tw + 0.28), ribMat);
    rib.name = `saw_belt_rib_${i}`;
    rib.position.set(belt.position.x - intakeLen / 2 + 0.08 + i * (intakeLen / 8), 0.18, 0);
    rib.castShadow = true;
    rib.receiveShadow = true;
    g.add(rib);
  }
  const funnelLen = 0.72;
  const r0 = (tw + 0.45) * 0.55;
  const r1 = tw * 0.52;
  const funnel = new THREE.Mesh(new THREE.CylinderGeometry(r0, r1, funnelLen, 18, 1, true), wall);
  funnel.name = 'saw_funnel';
  funnel.rotation.z = -Math.PI / 2;
  funnel.position.set(
    -td / 2 - tExtra + funnelLen / 2 + 0.03 - beltStick * 0.42,
    th * 0.52 + 0.06,
    0
  );
  funnel.castShadow = true;
  funnel.receiveShadow = true;

  // Internal belt visual (matches game).
  const inside = new THREE.Mesh(new THREE.BoxGeometry(tunnelLen * 0.72, 0.12, tw * 0.95), beltMat);
  inside.name = 'saw_inside_belt';
  inside.position.set(tCx + 0.05, 0.12, 0);
  inside.castShadow = true;
  inside.receiveShadow = true;

  // Side control panel geometry (plate + buttons + value areas + labels).
  const panel = new THREE.Group();
  panel.name = 'saw_panel';
  const plateMat = new THREE.MeshStandardMaterial({ color: 0xd9b07d, roughness: 0.9, metalness: 0.02 });
  const plate = new THREE.Mesh(new THREE.BoxGeometry(0.78, 0.42, 0.03), plateMat);
  plate.name = 'saw_panel_plate';
  plate.position.set(0, oh * 0.62, od / 2 + 0.02);
  plate.castShadow = true;
  plate.receiveShadow = true;
  panel.add(plate);

  function makeTextTex(text, opts = {}) {
    const pad = opts.pad ?? 10;
    const fontPx = opts.fontPx ?? 22;
    const fg = opts.fg ?? '#111827';
    const canvas = document.createElement('canvas');
    const ctx = canvas.getContext('2d');
    ctx.font = `900 ${fontPx}px system-ui, "Segoe UI", sans-serif`;
    const t = String(text ?? '');
    const w = Math.ceil(Math.min(512, Math.max(48, ctx.measureText(t).width + pad * 2)));
    const h = Math.ceil(fontPx + pad * 2);
    canvas.width = w;
    canvas.height = h;
    ctx.clearRect(0, 0, w, h);
    ctx.fillStyle = fg;
    ctx.font = `900 ${fontPx}px system-ui, "Segoe UI", sans-serif`;
    ctx.textBaseline = 'middle';
    ctx.fillText(t, pad, h / 2);
    const tex = new THREE.CanvasTexture(canvas);
    tex.colorSpace = THREE.SRGBColorSpace;
    tex.needsUpdate = true;
    return { tex, aspect: w / h };
  }

  const decalMat = (tex) =>
    new THREE.MeshBasicMaterial({
      map: tex,
      transparent: true,
      opacity: 0.98,
      depthWrite: false,
    });

  // Title label: separate background + printed text (so you can resize the background).
  const titleBg = new THREE.Mesh(
    new THREE.PlaneGeometry(0.56, 0.09),
    new THREE.MeshStandardMaterial({ color: 0xe5e7eb, roughness: 0.95, metalness: 0 })
  );
  titleBg.name = 'saw_title_bg';
  titleBg.position.set(plate.position.x, plate.position.y + 0.16, plate.position.z + 0.016);
  panel.add(titleBg);
  const titleTex = makeTextTex('Plank Cross Section', { fontPx: 18, pad: 10, fg: '#111827' });
  const titleTxt = new THREE.Mesh(new THREE.PlaneGeometry(0.54, 0.085), decalMat(titleTex.tex));
  titleTxt.name = 'saw_title_txt';
  titleTxt.position.set(titleBg.position.x, titleBg.position.y, titleBg.position.z + 0.001);
  panel.add(titleTxt);

  const buttonMat = new THREE.MeshStandardMaterial({ color: 0x111827, roughness: 0.6, metalness: 0.15 });
  const mkBtn = (name, x, y) => {
    const b = new THREE.Mesh(new THREE.CylinderGeometry(0.048, 0.048, 0.018, 20), buttonMat);
    b.name = name;
    b.rotation.x = Math.PI / 2;
    b.position.set(x, y, plate.position.z + 0.02);
    b.castShadow = true;
    b.receiveShadow = true;
    return b;
  };
  const btnXDec = mkBtn('saw_btn_x_dec', -0.18, plate.position.y + 0.04);
  const btnXInc = mkBtn('saw_btn_x_inc', 0.18, plate.position.y + 0.04);
  const btnYDec = mkBtn('saw_btn_y_dec', -0.18, plate.position.y - 0.08);
  const btnYInc = mkBtn('saw_btn_y_inc', 0.18, plate.position.y - 0.08);
  panel.add(btnXDec, btnXInc, btnYDec, btnYInc);

  // Printed icons on buttons: separate background + text so you can resize to fit the circles.
  const iconBgMat = new THREE.MeshStandardMaterial({ color: 0x111827, roughness: 0.8, metalness: 0.05 });
  const addBtnDecal = (btn, baseName, glyph) => {
    const bg = new THREE.Mesh(new THREE.PlaneGeometry(0.09, 0.09), iconBgMat);
    bg.name = `${baseName}_bg`;
    bg.position.set(btn.position.x, btn.position.y, btn.position.z + 0.02);
    panel.add(bg);
    const tx = makeTextTex(glyph, { fontPx: 26, pad: 8, fg: '#ffb366' });
    const t = new THREE.Mesh(new THREE.PlaneGeometry(0.06, 0.06), decalMat(tx.tex));
    t.name = `${baseName}_txt`;
    t.position.set(bg.position.x, bg.position.y, bg.position.z + 0.001);
    panel.add(t);
  };
  addBtnDecal(btnXDec, 'saw_btn_x_dec_icon', '◀');
  addBtnDecal(btnXInc, 'saw_btn_x_inc_icon', '▶');
  addBtnDecal(btnYDec, 'saw_btn_y_dec_icon', '◀');
  addBtnDecal(btnYInc, 'saw_btn_y_inc_icon', '▶');

  // X/Y labels (separate background + text).
  const labBgMat = new THREE.MeshStandardMaterial({ color: 0xe5e7eb, roughness: 0.95, metalness: 0 });
  const addAxisLabel = (axis, y) => {
    const bg = new THREE.Mesh(new THREE.PlaneGeometry(0.09, 0.06), labBgMat);
    bg.name = `saw_lab_${axis}_bg`;
    bg.position.set(plate.position.x - 0.31, y, plate.position.z + 0.016);
    panel.add(bg);
    const tx = makeTextTex(`${axis.toUpperCase()}:`, { fontPx: 18, pad: 8, fg: '#111827' });
    const t = new THREE.Mesh(new THREE.PlaneGeometry(0.08, 0.05), decalMat(tx.tex));
    t.name = `saw_lab_${axis}_txt`;
    t.position.set(bg.position.x, bg.position.y, bg.position.z + 0.001);
    panel.add(t);
  };
  addAxisLabel('x', plate.position.y + 0.04);
  addAxisLabel('y', plate.position.y - 0.08);

  const valueMat = new THREE.MeshStandardMaterial({ color: 0xe5e7eb, roughness: 0.95, metalness: 0 });
  const xVal = new THREE.Mesh(new THREE.PlaneGeometry(0.16, 0.085), valueMat);
  xVal.name = 'saw_val_x';
  xVal.position.set(0, plate.position.y + 0.04, plate.position.z + 0.016);
  panel.add(xVal);
  const yVal = new THREE.Mesh(new THREE.PlaneGeometry(0.16, 0.085), valueMat);
  yVal.name = 'saw_val_y';
  yVal.position.set(0, plate.position.y - 0.08, plate.position.z + 0.016);
  panel.add(yVal);

  g.add(panel);

  g.userData.sawmillConvertZone = defaultSawmillConvertZoneDims({
    tunnelW: tw,
    outerH: oh,
    tunnelD: td,
    tunnelBackExtra: tExtra,
  });
  addSawmillFrontBackEndLabelsPrefab(g, oh, td, tExtra);
  g.add(front, back, topRail, botRail, roofCap, belt, funnel, inside);
  return g;
}

/** Wood grain on the starter axe hilt (same as preset <code>wood_fine</code>, tuned repeat). */
function createAxeHandleWoodTexture() {
  const res = createPresetCanvas('wood_fine');
  if (!res) return null;
  const tex = threeCanvasPresetTexture(res);
  if (tex) tex.repeat.set(2.2, 1);
  return tex;
}

/** Held axe for the game (kind <code>axe</code>) — wood handle + steel blade + poll (Y-up, standing on grid). */
function buildDefaultAxe() {
  const g = new THREE.Group();
  g.name = 'StarterAxePrefab';
  const woodTex = createAxeHandleWoodTexture();
  const wood = new THREE.MeshStandardMaterial({
    color: 0x4a3324,
    roughness: 0.91,
    metalness: 0.04,
    ...(woodTex ? { map: woodTex } : {}),
  });
  const steel = new THREE.MeshStandardMaterial({
    color: 0x7d8896,
    roughness: 0.34,
    metalness: 0.58,
  });
  const steelDark = new THREE.MeshStandardMaterial({
    color: 0x3c4249,
    roughness: 0.52,
    metalness: 0.48,
  });

  /** Rounded rectangular grip: wider along blade (X), thinner on sides (Z), vertical (Y). */
  const hw = 0.052;
  const hh = 0.56;
  const hd = 0.032;
  const hSeg = 5;
  const hRad = Math.min(0.011, hw / 2 - 0.002, hd / 2 - 0.002, hh / 2 - 0.002);
  const handleGeo = new RoundedBoxGeometry(hw, hh, hd, hSeg, hRad);
  const handle = new THREE.Mesh(handleGeo, wood);
  handle.name = 'axe_handle';
  handle.userData.prefabGeom = {
    type: 'rounded_box',
    width: hw,
    height: hh,
    depth: hd,
    segments: hSeg,
    radius: hRad,
  };
  handle.position.set(0, 0.28, 0);
  handle.rotation.x = 0.055;
  handle.rotation.z = 0.045;
  handle.castShadow = true;
  handle.receiveShadow = true;

  const cw = 0.046;
  const ch = 0.048;
  const cd = 0.046;
  const cSeg = 3;
  const cRad = Math.min(0.008, cw / 2 - 0.001, cd / 2 - 0.001, ch / 2 - 0.001);
  const collarGeo = new RoundedBoxGeometry(cw, ch, cd, cSeg, cRad);
  const collar = new THREE.Mesh(collarGeo, steelDark);
  collar.name = 'axe_collar';
  collar.userData.prefabGeom = {
    type: 'rounded_box',
    width: cw,
    height: ch,
    depth: cd,
    segments: cSeg,
    radius: cRad,
  };
  collar.position.set(0, 0.556, 0);
  collar.rotation.x = handle.rotation.x;
  collar.rotation.z = handle.rotation.z;
  collar.castShadow = true;
  collar.receiveShadow = true;

  const blade = new THREE.Mesh(new THREE.BoxGeometry(0.24, 0.042, 0.11), steel);
  blade.name = 'axe_blade';
  blade.position.set(-0.118, 0.588, 0);
  blade.rotation.set(0, 0, -0.13);
  blade.castShadow = true;
  blade.receiveShadow = true;

  const edge = new THREE.Mesh(new THREE.BoxGeometry(0.2, 0.028, 0.045), steelDark);
  edge.name = 'axe_edge';
  edge.position.set(-0.228, 0.582, 0);
  edge.rotation.set(0, 0, -0.13);
  edge.castShadow = true;
  edge.receiveShadow = true;

  const poll = new THREE.Mesh(new THREE.BoxGeometry(0.065, 0.058, 0.075), steel);
  poll.name = 'axe_poll';
  poll.position.set(0.082, 0.584, 0);
  poll.castShadow = true;
  poll.receiveShadow = true;

  g.add(handle, collar, blade, edge, poll);
  return g;
}

/** Starting point for a user-defined prefab kind (＋ New prefab…). */
function buildBlankPrefab() {
  const g = new THREE.Group();
  const mesh = new THREE.Mesh(
    new THREE.BoxGeometry(0.35, 0.35, 0.35),
    new THREE.MeshStandardMaterial({ color: 0x8899aa, roughness: 0.85, metalness: 0.05 })
  );
  mesh.name = 'part';
  mesh.position.set(0, 0.175, 0);
  mesh.castShadow = true;
  mesh.receiveShadow = true;
  g.add(mesh);
  return g;
}

function refreshKindDropdown() {
  const sel = document.getElementById('pe-kind');
  if (!sel) return;
  const all = loadAllPrefabsFromStorage();
  const customKeys = Object.keys(all).filter(
    (k) => !BUILTIN_KINDS.includes(k) && all[k] != null && typeof all[k] === 'object'
  );
  const custom = new Set(customKeys);
  if (!BUILTIN_KINDS.includes(currentKind) && currentKind !== '__new__') {
    custom.add(currentKind);
  }
  const sortedCustom = [...custom].sort();
  const parts = [];
  for (const k of BUILTIN_KINDS) {
    parts.push(`<option value="${k}">${BUILTIN_LABELS[k] || k}</option>`);
  }
  for (const k of sortedCustom) {
    parts.push(`<option value="${k}">${k} (custom)</option>`);
  }
  parts.push('<option value="__new__">＋ New prefab…</option>');
  sel.innerHTML = parts.join('');
  if ([...sel.options].some((o) => o.value === currentKind)) {
    sel.value = currentKind;
  } else {
    currentKind = 'tree';
    sel.value = 'tree';
  }
}

function revertKindSelect() {
  const sel = document.getElementById('pe-kind');
  if (sel) sel.value = currentKind;
}

function promptNewPrefabKind() {
  const raw = window.prompt(
    'New prefab id (lowercase letters, numbers, underscores — saved as a key in LT2_PrefabEdits):',
    'my_prefab'
  );
  if (raw == null || !String(raw).trim()) {
    revertKindSelect();
    return;
  }
  let id = String(raw)
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9_]/g, '_')
    .replace(/^_+/, '');
  if (!id) id = 'my_prefab';
  if (RESERVED_KIND_IDS.has(id)) {
    alert('That id is reserved or already a built-in prefab. Pick another name.');
    revertKindSelect();
    return;
  }
  currentKind = id;
  setPrefabKind(id);
  refreshKindDropdown();
  const sel = document.getElementById('pe-kind');
  if (sel) sel.value = id;
  setStatus(`New prefab <strong>${id}</strong> — add parts, then <strong>Save</strong>.`);
}

function disposeGroupDeep(g) {
  if (!g) return;
  g.traverse((o) => {
    if (o.geometry) o.geometry.dispose();
    if (o.material) {
      const mats = Array.isArray(o.material) ? o.material : [o.material];
      for (const mat of mats) {
        if (!mat) continue;
        for (const key of ['map', 'normalMap', 'roughnessMap', 'metalnessMap', 'aoMap', 'emissiveMap']) {
          const t = mat[key];
          if (t && typeof t.dispose === 'function') t.dispose();
        }
        mat.dispose?.();
      }
    }
  });
}

/** Old editor defaults used a cylinder trunk; game uses box logs — replace so “Reset” isn’t required. */
function treePrefabHasCylinderTrunk(grp) {
  if (!grp) return false;
  let found = false;
  grp.traverse((o) => {
    if (!o.isMesh || o.geometry?.type !== 'CylinderGeometry') return;
    const n = String(o.name || '').toLowerCase();
    if (n === 'trunk' || n.includes('trunk')) found = true;
  });
  return found;
}

function syncUiAfterPrefabSceneChange(kind) {
  syncPartList();
  syncGlobalScaleUi();
  syncConveyorBeltUi();
  if (kind === 'sawmill' && prefabRoot) {
    if (!normalizeSawmillConvertZone(prefabRoot.userData?.sawmillConvertZone)) {
      prefabRoot.userData = prefabRoot.userData || {};
      prefabRoot.userData.sawmillConvertZone = defaultSawmillConvertZoneDims(EDITOR_DEFAULT_SAWMILL_DIMS);
    }
    ensureSawmillConvertZoneVisual(prefabRoot);
  }
  syncSawmillConvertZoneUi();
  syncTreeBranchUi();
  refreshProceduralBranchesInEditor();
  pushUndoSnapshot();
  applyEditorMode();
  syncMaterialUiFromSelection();
}

function frameCameraToObject(obj) {
  if (!obj || !camera || !controls) return;
  const box = new THREE.Box3().setFromObject(obj);
  if (box.isEmpty()) return;
  const center = box.getCenter(new THREE.Vector3());
  const size = box.getSize(new THREE.Vector3());
  const radius = Math.max(size.x, size.y, size.z) * 0.75;
  const dist = Math.max(2.1, radius * 3.2);
  controls.target.copy(center);
  camera.position.set(center.x + dist, center.y + dist * 0.72, center.z + dist);
  camera.lookAt(center);
  camera.updateProjectionMatrix();
  globalThis.LT_appendDebugLog?.(
    `[prefab camera] framed center=${center.x.toFixed(2)},${center.y.toFixed(2)},${center.z.toFixed(2)} dist=${dist.toFixed(2)}`
  );
}

function setPrefabKind(kind) {
  undoStack.length = 0;
  globalScale = 1;
  lastBakedScale = 1;
  if (prefabRoot) {
    scene.remove(prefabRoot);
    disposeGroupDeep(prefabRoot);
    prefabRoot = null;
  }
  transformControls.detach();
  selected = null;
  setStatus('');

  const all = loadAllPrefabsFromStorage();
  let axeUsesObjLoader = false;
  if (kind === 'axe') {
    axeUsesObjLoader = true;
    // Guaranteed visible fallback while OBJ loads.
    prefabRoot = buildDefaultAxe();
  } else if (all[kind] && Array.isArray(all[kind].parts) && all[kind].parts.length > 0) {
    prefabRoot = deserializePrefabGroup(all[kind]);
    if ((kind === 'tree' || kind === 'treeSpook' || kind === 'treeVoid') && treePrefabHasCylinderTrunk(prefabRoot)) {
      disposeGroupDeep(prefabRoot);
      prefabRoot =
        kind === 'treeSpook' ? buildDefaultTreeSpook() : kind === 'treeVoid' ? buildDefaultTreeVoid() : buildDefaultTree();
      setStatus(
        '<strong>Tree prefab upgraded:</strong> replaced old cylinder trunk with box trunk (matches game). Click <strong>Save</strong> to store.'
      );
    }
  } else {
    if (kind === 'tree') prefabRoot = buildDefaultTree();
    else if (kind === 'treeSpook') prefabRoot = buildDefaultTreeSpook();
    else if (kind === 'treeVoid') prefabRoot = buildDefaultTreeVoid();
    else if (kind === 'plankWood') prefabRoot = buildDefaultPlankWood();
    else if (kind === 'plankNeon') prefabRoot = buildDefaultPlankNeon();
    else if (kind === 'conveyor') prefabRoot = buildDefaultConveyor();
    else if (kind === 'ramp') prefabRoot = buildDefaultRamp();
    else if (kind === 'sawmill') prefabRoot = buildDefaultSawmill();
    else if (kind === 'shelf') prefabRoot = buildDefaultShelf();
    else prefabRoot = buildBlankPrefab();
  }
  prefabRoot.position.set(0, 0, 0);
  scene.add(prefabRoot);
  if (kind === 'plankNeon') {
    prefabRoot.traverse((o) => {
      if (o.isMesh) enableNeonBloomOnMesh(o);
    });
  }
  syncUiAfterPrefabSceneChange(kind);
  if (axeUsesObjLoader) frameCameraToObject(prefabRoot);
  if (axeUsesObjLoader) {
    const token = ++axeObjEditorLoadSerial;
    setStatus('Loading <code>models/files/axe.obj</code>…');
    void loadAxeObjAsGroup({
      targetMaxDim: 1.75,
      castShadow: true,
      receiveShadow: true,
      tryTexture: false,
      skipBloomMaterialSwap: true,
    })
      .then((obj) => {
        if (currentKind !== 'axe' || token !== axeObjEditorLoadSerial || !prefabRoot) {
          disposeAxeObjDeep(obj);
          return;
        }
        scene.remove(prefabRoot);
        disposeGroupDeep(prefabRoot);
        prefabRoot = obj;
        prefabRoot.position.set(0, 0, 0);
        scene.add(prefabRoot);
        syncUiAfterPrefabSceneChange('axe');
        frameCameraToObject(prefabRoot);
        setStatus('');
      })
      .catch((err) => {
        if (currentKind !== 'axe' || token !== axeObjEditorLoadSerial || !prefabRoot) return;
        setStatus(
          `Could not load <code>axe.obj</code> — procedural fallback. (${String(err?.message || err)})`
        );
      });
  }
}

function syncPartList() {
  const el = document.getElementById('pe-parts');
  if (!el || !prefabRoot) return;
  const names = [];
  prefabRoot.traverse((o) => {
    if (o.isMesh && !o.userData.excludeFromPrefabSerialize) names.push(o.name || '(unnamed)');
  });
  el.innerHTML = names.map((n) => `<option value="${n}">${n}</option>`).join('');
}

function selectMeshByName(name) {
  if (!prefabRoot) return;
  prefabRoot.traverse((o) => {
    if (o.isMesh && o.name === name) {
      selected = o;
      if (getGizmoMode() !== 'select') transformControls.attach(o);
      setStatus(`Selected: <strong>${selected.name}</strong>`);
      syncMaterialUiFromSelection();
    }
  });
}

function onPointerDown(e) {
  if (e.button !== 0) return;
  if (getGizmoMode() !== 'select') return;
  const rect = renderer.domElement.getBoundingClientRect();
  pointer.x = ((e.clientX - rect.left) / rect.width) * 2 - 1;
  pointer.y = -((e.clientY - rect.top) / rect.height) * 2 + 1;
  raycaster.setFromCamera(pointer, camera);
  const meshes = [];
  prefabRoot?.traverse((o) => {
    if (o.isMesh) meshes.push(o);
  });
  const hits = raycaster.intersectObjects(meshes, false);
  if (hits.length && hits[0].object) {
    selected = hits[0].object;
    transformControls.attach(selected);
    const sel = document.getElementById('pe-parts');
    if (sel) sel.value = selected.name;
    setStatus(`Selected: <strong>${selected.name}</strong>`);
    syncMaterialUiFromSelection();
  }
}

function setStatus(html) {
  const s = document.getElementById('pe-status');
  if (s) s.innerHTML = html;
  if (typeof globalThis.LT_appendDebugLog === 'function' && html) {
    const plain = String(html)
      .replace(/<br\s*\/?>/gi, '\n')
      .replace(/<[^>]+>/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();
    if (plain && !/^Selected:/i.test(plain)) globalThis.LT_appendDebugLog(`[prefab status] ${plain}`);
  }
}

function getGizmoMode() {
  const t = document.getElementById('pe-mode-translate');
  const pick = document.getElementById('pe-mode-select');
  const r = document.getElementById('pe-mode-rotate');
  const s = document.getElementById('pe-mode-scale');
  if (pick?.checked) return 'select';
  if (r?.checked) return 'rotate';
  if (s?.checked) return 'scale';
  return 'translate';
}

function applyEditorMode() {
  const mode = getGizmoMode();
  if (mode === 'select') {
    transformControls.detach();
    transformControls.visible = false;
    return;
  }
  transformControls.visible = true;
  transformControls.setMode(mode);
  if (selected) transformControls.attach(selected);
}

function saveCurrentKind() {
  if (!prefabRoot) return;
  if (currentKind === 'axe' && prefabRoot.userData?.axeExternalObj) {
    setStatus('Starter axe is <code>models/files/axe.obj</code> on disk — not saved as prefab JSON.');
    return;
  }
  readTreeBranchesFromUiToUserData();
  bakeGlobalScaleIntoParts();
  const data = serializePrefabGroup(prefabRoot, currentKind);
  const all = loadAllPrefabsFromStorage();
  all[currentKind] = data;
  saveAllPrefabsToStorage(all);
  refreshKindDropdown();
  const sel = document.getElementById('pe-kind');
  if (sel) sel.value = currentKind;
  setStatus(`Saved <strong>${currentKind}</strong> to browser storage (<code>LT2_PrefabEdits</code>).`);
}

function downloadJson() {
  if (!prefabRoot) return;
  if (currentKind === 'axe' && prefabRoot.userData?.axeExternalObj) {
    setStatus('Download skipped for <code>axe</code> — mesh comes from <code>models/files/axe.obj</code>.');
    return;
  }
  readTreeBranchesFromUiToUserData();
  bakeGlobalScaleIntoParts();
  const data = serializePrefabGroup(prefabRoot, currentKind);
  const all = loadAllPrefabsFromStorage();
  all[currentKind] = data;
  const blob = new Blob([JSON.stringify(all, null, 2)], { type: 'application/json;charset=utf-8' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'lumber-prefabs.json';
  a.click();
  URL.revokeObjectURL(a.href);
}

function onImportFile(e) {
  const f = e.target.files?.[0];
  if (!f) return;
  const reader = new FileReader();
  reader.onload = () => {
    try {
      const imported = JSON.parse(String(reader.result));
      const merged = { ...loadAllPrefabsFromStorage(), ...imported };
      saveAllPrefabsToStorage(merged);
      refreshKindDropdown();
      setPrefabKind(currentKind);
      setStatus('Imported prefabs JSON — reloaded current kind.');
    } catch {
      alert('Invalid JSON');
    }
    e.target.value = '';
  };
  reader.readAsText(f);
}

function init() {
  try {
    const wrap = document.getElementById('pe-canvas-wrap');
    if (!wrap) {
      setStatus('Missing <code>#pe-canvas-wrap</code> in HTML.');
      return;
    }

    scene = new THREE.Scene();
    scene.background = new THREE.Color(0x1a1d24);
    camera = new THREE.PerspectiveCamera(55, window.innerWidth / window.innerHeight, 0.08, 200);
    camera.position.set(4.2, 3.5, 5.2);

    renderer = new THREE.WebGLRenderer({ antialias: true });
    renderer.setSize(window.innerWidth, window.innerHeight);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    renderer.shadowMap.enabled = true;
    renderer.outputColorSpace = THREE.SRGBColorSpace;
    renderer.toneMapping = THREE.ReinhardToneMapping;
    renderer.toneMappingExposure = 1;
    renderer.domElement.style.display = 'block';
    wrap.appendChild(renderer.domElement);
    try {
      neonBloomPipeline = createNeonBloomPipeline(renderer, scene, camera);
      enableNeonBloomOnCamera(camera);
    } catch (err) {
      console.warn('Neon bloom pipeline failed; using direct render.', err);
      neonBloomPipeline = null;
    }

    const hemi = new THREE.HemisphereLight(0xcfe8ff, 0x3a4a30, 0.55);
    scene.add(hemi);
    const sun = new THREE.DirectionalLight(0xffffff, 0.85);
    sun.position.set(8, 18, 10);
    sun.castShadow = true;
    sun.shadow.mapSize.set(1024, 1024);
    scene.add(sun);

    /** 40×40 units, 1 unit per cell — matches game/map 1m grid. */
    const grid = new THREE.GridHelper(40, 40, 0x444a55, 0x2a2e38);
    scene.add(grid);

    controls = new OrbitControls(camera, renderer.domElement);
    controls.enableDamping = true;
    controls.target.set(0, 0.8, 0);

    transformControls = new TransformControls(camera, renderer.domElement);
    transformControls.setSize(0.85);
    transformControls.addEventListener('dragging-changed', (ev) => {
      controls.enabled = !ev.value;
    });
    transformControls.addEventListener('mouseDown', () => {
      if (selected) pushUndoSnapshot();
    });
    scene.add(transformControls);

    raycaster = new THREE.Raycaster();
    pointer = new THREE.Vector2();

    renderer.domElement.addEventListener('pointerdown', onPointerDown);
    window.addEventListener('keydown', onKeyDown);

    document.getElementById('pe-kind')?.addEventListener('change', (e) => {
      const v = e.target.value;
      if (v === '__new__') {
        promptNewPrefabKind();
        return;
      }
      currentKind = v;
      setPrefabKind(currentKind);
    });

    document.getElementById('pe-reset')?.addEventListener('click', () => {
      removePrefabKindFromStorage(currentKind);
      if (!BUILTIN_KINDS.includes(currentKind)) {
        refreshKindDropdown();
      }
      setPrefabKind(currentKind);
      setStatus(`Reset <strong>${currentKind}</strong> to default layout.`);
    });

    document.getElementById('pe-delete-prefab')?.addEventListener('click', () => {
      const label = BUILTIN_LABELS[currentKind] || currentKind;
      if (
        !confirm(
          `Delete saved prefab “${label}” (${currentKind}) from browser storage? The editor will reload the default layout until you save again.`
        )
      ) {
        return;
      }
      removePrefabKindFromStorage(currentKind);
      if (!BUILTIN_KINDS.includes(currentKind)) {
        refreshKindDropdown();
      }
      setPrefabKind(currentKind);
      setStatus(`Deleted saved prefab <strong>${currentKind}</strong> from <code>LT2_PrefabEdits</code>.`);
    });

    document.getElementById('pe-save')?.addEventListener('click', saveCurrentKind);
    document.getElementById('pe-download')?.addEventListener('click', downloadJson);
    document.getElementById('pe-import')?.addEventListener('change', onImportFile);

    document.getElementById('pe-parts')?.addEventListener('change', (e) => {
      selectMeshByName(e.target.value);
    });

    document.getElementById('pe-global-scale')?.addEventListener('input', (e) => {
      globalScale = clampGlobalScale(e.target.value);
      syncGlobalScaleUi();
    });
    syncGlobalScaleUi();
    syncNeonTreeUi();
    wireNeonTreeControls();
    wireConveyorBeltControls();
    wireSawmillConvertZoneControls();
    wireTreeBranchControls();
    wireCylinderBendTools();

    document.getElementById('pe-mat-apply')?.addEventListener('click', applyMaterialToSelected);
    document.getElementById('pe-mat-apply-all')?.addEventListener('click', applyMaterialToAll);
    document.getElementById('pe-tex-apply')?.addEventListener('click', applyTextureMapFromFileInput);
    document.getElementById('pe-tex-clear')?.addEventListener('click', clearTextureMapOnSelected);
    document.getElementById('pe-tex-preset-apply')?.addEventListener('click', applyTexturePresetToSelected);
    document.getElementById('pe-shape-quick-wrap')?.addEventListener('click', (e) => {
      const btn = e.target.closest('[data-pe-shape]');
      if (!btn) return;
      quickAddShape(btn.getAttribute('data-pe-shape'));
    });

    const peTexPreset = document.getElementById('pe-tex-preset');
    if (peTexPreset && peTexPreset.options.length <= 1) {
      for (const { id, label } of TEXTURE_PRESET_OPTIONS) {
        const o = document.createElement('option');
        o.value = id;
        o.textContent = label;
        peTexPreset.appendChild(o);
      }
    }
    document.getElementById('pe-new-add')?.addEventListener('click', addNewPart);
    document.getElementById('pe-delete')?.addEventListener('click', deleteSelected);

    for (const id of ['pe-mode-translate', 'pe-mode-select', 'pe-mode-rotate', 'pe-mode-scale']) {
      document.getElementById(id)?.addEventListener('change', () => {
        applyEditorMode();
      });
    }

    window.addEventListener('resize', () => {
      camera.aspect = window.innerWidth / window.innerHeight;
      camera.updateProjectionMatrix();
      renderer.setSize(window.innerWidth, window.innerHeight);
      neonBloomPipeline?.setSize(window.innerWidth, window.innerHeight);
    });

    currentKind = 'tree';
    refreshKindDropdown();
    setPrefabKind(currentKind);
    applyEditorMode();

    function tick() {
      requestAnimationFrame(tick);
      controls.update();
      if (neonBloomPipeline) {
        applyNeonBloomToPass(neonBloomPipeline.bloomPass, loadNeonSettings().intensity);
        neonBloomPipeline.render();
      } else {
        renderer.render(scene, camera);
      }
    }
    tick();
  } catch (err) {
    console.error(err);
    const msg = err && typeof err === 'object' && 'message' in err ? String(err.message) : String(err);
    setStatus(`Prefab editor failed to start: <code>${msg}</code>`);
  }
}

init();
