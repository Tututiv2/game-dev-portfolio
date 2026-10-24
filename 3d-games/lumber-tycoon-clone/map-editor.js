/**
 * Map editor: full shop + Bob (matches game visuals), trees/props, movable sell zone.
 * Shift+click places — does not auto-select. Click object to show gizmo.
 */
import * as THREE from 'three';
import { OrbitControls } from 'https://unpkg.com/three@0.160.0/examples/jsm/controls/OrbitControls.js';
import { TransformControls } from 'https://unpkg.com/three@0.160.0/examples/jsm/controls/TransformControls.js';
import {
  tryGetPrefabDataForKind,
  deserializePrefabGroup,
} from './prefab-serializer.js';
import {
  applyProceduralTreeBranches,
  normalizeTreeBranchSettings,
  hashTreeBranchSeed,
} from './tree-procedural-branches.js';
import {
  normalizeMapDocument,
  getWorldSliceFromDocument,
  mapSliceHasAuthoring,
  MAP_DOCUMENT_VERSION,
  normalizeUniversePose,
} from './map-world-format.js';

/** Match game.js STORE + dock depth */
const EDITOR_STORE = { x: -22, z: -10, W: 14, D: 10, bobX: -19.2, bobZ: -11.8, bobY: 1.05 };
const EDITOR_DOCK_DEPTH = 4;

function defaultSellXZ() {
  return {
    x: EDITOR_STORE.x,
    z: EDITOR_STORE.z + EDITOR_STORE.D / 2 + EDITOR_DOCK_DEPTH / 2,
  };
}

/** Same key as game.js — map survives refreshes & game loads it automatically. */
const MAP_EDITOR_STORAGE_KEY = 'LT2_MapEditor';

/** Full multi-world document in memory (P0.2); merged on each export. */
let mapEditorDocument = null;

function slugPlanetId(raw) {
  const s = String(raw || '')
    .trim()
    .toLowerCase()
    .replace(/\s+/g, '_')
    .replace(/[^a-z0-9_-]/g, '')
    .slice(0, 48);
  return s || `planet_${Date.now()}`;
}

function syncPlanetSelectUi() {
  const sel = document.getElementById('me-planet-select');
  if (!sel || !mapEditorDocument?.worlds?.length) return;
  let cur = mapEditorDocument.defaultPlanetId || mapEditorDocument.worlds[0].planetId;
  if (!mapEditorDocument.worlds.some((w) => w && w.planetId === cur)) {
    cur = mapEditorDocument.worlds[0].planetId;
    mapEditorDocument.defaultPlanetId = cur;
  }
  sel.innerHTML = '';
  for (const w of mapEditorDocument.worlds) {
    if (!w?.planetId) continue;
    const opt = document.createElement('option');
    opt.value = w.planetId;
    opt.textContent = `${w.label || w.planetId} (${w.planetId})`;
    sel.appendChild(opt);
  }
  sel.value = cur;
}

function onMePlanetSelectChange() {
  if (!mapEditorDocument?.worlds) return;
  const sel = document.getElementById('me-planet-select');
  const newId = sel?.value;
  if (!newId || newId === mapEditorDocument.defaultPlanetId) return;
  pushUndoSnapshot();
  refreshExport();
  mapEditorDocument.defaultPlanetId = newId;
  const slice = getWorldSliceFromDocument(mapEditorDocument, newId);
  if (!slice) return;
  buildMapFromData(slice);
  refreshExport();
  syncList();
  syncPlanetSelectUi();
  scheduleAutosave();
}

function onMePlanetAddClick() {
  const raw = prompt('New planet id (letters, numbers, underscore):', 'expedition');
  if (raw == null) return;
  let id = slugPlanetId(raw);
  if (!id) id = `p_${Date.now()}`;
  if (!mapEditorDocument) mapEditorDocument = normalizeMapDocument({});
  while (mapEditorDocument.worlds.some((w) => w.planetId === id)) {
    id = `${id.slice(0, 36)}_${Math.floor(Math.random() * 999)}`;
  }
  const labelIn = prompt('Display label:', id);
  if (labelIn == null) return;
  const label = String(labelIn).trim() || id;
  pushUndoSnapshot();
  refreshExport();
  const spread = mapEditorDocument.worlds.length * 320;
  mapEditorDocument.worlds.push({
    planetId: id,
    label,
    sphereRadius: 2500,
    useSpherePhysics: false,
    trees: [],
    props: [],
    plotSites: [],
    sellSpot: {},
    terrain: null,
    universePose: normalizeUniversePose({ x: spread, y: 0, z: 0, rx: 0, ry: 0, rz: 0, sx: 1, sy: 1, sz: 1 }),
  });
  mapEditorDocument.defaultPlanetId = id;
  buildMapFromData(getWorldSliceFromDocument(mapEditorDocument, id));
  refreshExport();
  syncList();
  syncPlanetSelectUi();
  scheduleAutosave();
}

/** @param {unknown} raw @returns {'wood'|'dark'|'void'} */
function normalizeMapTreeLogSkin(raw) {
  if (raw === 'dark') return 'dark';
  if (raw === 'void') return 'void';
  return 'wood';
}

/** World grid step (m) — placement + gizmo translate snap. */
const MAP_GRID = 1;
function snapMapGrid(v) {
  return Math.round(v / MAP_GRID) * MAP_GRID;
}

/**
 * ============================================================================
 *  Planet-mode editor ground
 * ============================================================================
 *
 * The map editor stores item positions as **tangent-space `(x, z)` pairs** —
 * think of `(x, z)` as a flat cartesian coord at the north pole of a sphere.
 * The *game* takes these tangent pairs and projects them onto the planet
 * surface via `sphereTangentXZToInnerSurfaceWorld` in `game.js`:
 *
 *   surface = normalize(x, R, z) * R + planetCenter
 *
 * Historically the editor rendered on a flat `PlaneGeometry` ground — meaning
 * items far from the origin visually floated far from where they'd land on
 * the sphere in-game. This block replaces that flat ground with an actual
 * sphere sized to the currently-selected planet's `sphereRadius`, centered
 * so the north pole sits at the editor origin `(0, 0, 0)`. Item positions on
 * screen now match exactly what the player sees in-game.
 *
 * Item storage format is UNCHANGED — we still save `(x, z)` tangent coords
 * + a `ry` yaw — so old saves and world files load without migration. The
 * trick is we stash the tangent coords in `g.userData.mapX / mapZ / yaw` and
 * compute the on-sphere visual position + orientation from them, so the
 * Object3D's `position/quaternion` is free to be the projected world-space
 * transform without touching serialization.
 */
let planetRadius = 2500;
/** Center of the editor planet. Chosen so the north pole `(0, 0, 0)` stays at
 *  the editor origin — the store + Bob live near the origin, so this keeps
 *  them visually "on the planet" without moving anything. */
const planetCenter = new THREE.Vector3(0, -2500, 0);
/** Reference vector pointing from planet center toward the north pole. Used as
 *  the "flat tangent" axis — a tangent sample at `(x, z) = (0, 0)` lies along
 *  this direction, scaled to `planetRadius`. */
const PLANET_NORTH = new THREE.Vector3(0, 1, 0);
/** @type {THREE.Mesh | null} */
let planetMesh = null;
/** @type {THREE.GridHelper | null} */
let planetHelperGrid = null;

/**
 * Build (or rebuild) the editor planet sphere at the current `planetRadius`.
 * `surfaceColor` is optional — defaults to the same greenish tone the old
 * flat ground used so existing screenshots stay recognizable. Side is front-
 * only because the camera is always outside the sphere.
 */
function rebuildEditorPlanetMesh(surfaceColor) {
  if (planetMesh) {
    scene?.remove(planetMesh);
    planetMesh.geometry.dispose();
    planetMesh.material.dispose();
    planetMesh = null;
  }
  if (!scene) return;
  const segs = planetRadius >= 4000 ? 96 : 64;
  const geo = new THREE.SphereGeometry(planetRadius, segs, Math.floor(segs * 0.75));
  const mat = new THREE.MeshStandardMaterial({
    color: surfaceColor ?? 0x3d6b3d,
    roughness: 1,
    side: THREE.FrontSide,
  });
  planetMesh = new THREE.Mesh(geo, mat);
  planetMesh.name = 'PlanetGround';
  planetMesh.position.copy(planetCenter);
  planetMesh.receiveShadow = true;
  scene.add(planetMesh);
}

/**
 * Swap in a new planet radius + color. Called when the selected planet
 * changes in the dropdown or when a loaded planet's radius is different
 * from what we have rendered. Re-projects every already-placed item so the
 * scene stays consistent when the planet grows or shrinks.
 */
function setEditorPlanetFromSlice(slice) {
  const r = Number(slice?.sphereRadius);
  if (Number.isFinite(r) && r > 0) planetRadius = Math.max(50, Math.min(200000, r));
  planetCenter.set(0, -planetRadius, 0);
  /** Pick a surface color — planetary surface colors live in the world slice
   *  under `slice.surfaceColor` (authored in universe editor). Fall back to
   *  the classic editor green so tests that don't set one still look fine. */
  let col = 0x3d6b3d;
  const raw = slice?.surfaceColor;
  if (typeof raw === 'string' && /^#?[0-9a-fA-F]{6}$/.test(raw)) {
    col = parseInt(raw.replace('#', ''), 16);
  } else if (typeof raw === 'number') {
    col = raw | 0;
  }
  rebuildEditorPlanetMesh(col);
  for (const g of placed) {
    if (g?.userData?.mapX == null) continue;
    placeItemOnPlanet(g, g.userData.mapX, g.userData.mapZ, g.userData.yaw ?? 0);
  }
}

/**
 * Core projection: given tangent `(x, z)` coords, fill `outPos` with the
 * corresponding point on the editor planet surface and `outUp` with the unit
 * surface normal (direction from planet center to that surface point).
 *
 * This matches the game's projection formula exactly, so what you place in
 * the editor is where the player walks in-game.
 */
function projectTangentToSurface(x, z, outPos, outUp) {
  const R = planetRadius;
  const l = Math.hypot(x, R, z) || 1;
  const s = R / l;
  outPos.set(
    x * s + planetCenter.x,
    R * s + planetCenter.y,
    z * s + planetCenter.z,
  );
  outUp.set(x / l, R / l, z / l);
}

const _tmpPos = new THREE.Vector3();
const _tmpUp = new THREE.Vector3();
const _tmpAlign = new THREE.Quaternion();
const _tmpYaw = new THREE.Quaternion();
const _tmpLocalUp = new THREE.Vector3(0, 1, 0);

/**
 * Place `g` visually at tangent `(x, z)` on the editor planet, oriented so
 * its local +Y axis points along the surface normal, then rotated by `yaw`
 * around that normal. Stash the tangent coords + yaw on `g.userData` so
 * export / picking / undo all keep round-tripping correctly.
 *
 * NOTE: this overwrites `g.position` and `g.quaternion`. Any yaw that was
 * previously set via `g.rotation.y = ry` must be passed in as the `yaw`
 * arg — don't rely on existing `rotation.y` surviving.
 */
function placeItemOnPlanet(g, x, z, yaw) {
  const yawSafe = Number.isFinite(yaw) ? yaw : 0;
  projectTangentToSurface(x, z, _tmpPos, _tmpUp);
  g.position.copy(_tmpPos);
  _tmpAlign.setFromUnitVectors(_tmpLocalUp, _tmpUp);
  _tmpYaw.setFromAxisAngle(_tmpUp, yawSafe);
  g.quaternion.copy(_tmpYaw).multiply(_tmpAlign);
  g.userData.mapX = x;
  g.userData.mapZ = z;
  g.userData.yaw = yawSafe;
}

/**
 * Inverse of {@link projectTangentToSurface}. Given a world point on the
 * planet surface, return the tangent `(x, z)` coords that would project to
 * it. Returns `null` if the point is behind the planet (not reachable by a
 * tangent-space sample from the north pole).
 */
function surfaceWorldToTangent(worldPt, out) {
  const cx = worldPt.x - planetCenter.x;
  const cy = worldPt.y - planetCenter.y;
  const cz = worldPt.z - planetCenter.z;
  /** Tangent sample direction must have positive `y` component (above the
   *  equator of the north-cap) — anything below wraps to the dark side of
   *  the planet which we don't expose as an editable region. */
  if (cy < 1e-3) return null;
  const R = planetRadius;
  out.x = (cx * R) / cy;
  out.z = (cz * R) / cy;
  return out;
}

const _tmpRayToCenter = new THREE.Vector3();

/**
 * Ray-sphere intersection against the editor planet. Returns the nearest
 * hit's tangent `(x, z)` or `null` on miss. Used for shift-click placement
 * + hover selection so the player picks points on the curved surface, not
 * on the vanished flat plane.
 */
function raycastEditorPlanet(ray, out) {
  _tmpRayToCenter.subVectors(planetCenter, ray.origin);
  const tCa = _tmpRayToCenter.dot(ray.direction);
  if (tCa < 0) return null;
  const d2 = _tmpRayToCenter.lengthSq() - tCa * tCa;
  const r2 = planetRadius * planetRadius;
  if (d2 > r2) return null;
  const tHc = Math.sqrt(r2 - d2);
  const t = tCa - tHc;
  if (t < 0) return null;
  _tmpPos.copy(ray.origin).addScaledVector(ray.direction, t);
  return surfaceWorldToTangent(_tmpPos, out);
}

/**
 * Decompose an Object3D's world quaternion into yaw-around-local-up. Used
 * after a rotate gizmo drag to recover the `ry` value for storage/export.
 * Returns a `Number` in radians.
 *
 * Theory: the object's final orientation is `qYaw * qAlign` where `qAlign`
 * rotates world up onto the surface normal at `(mapX, mapZ)`. Inverting
 * `qAlign` leaves a pure rotation around the surface up, from which we
 * extract the signed angle.
 */
function extractYawFromQuaternion(q, up) {
  _tmpAlign.setFromUnitVectors(_tmpLocalUp, up);
  /** `qRel = q * qAlign^-1` = residual rotation around `up`. */
  const qInv = _tmpAlign.clone().invert();
  const qRel = q.clone().multiply(qInv);
  const sinHalf = Math.hypot(qRel.x, qRel.y, qRel.z);
  if (sinHalf < 1e-6) return 0;
  const axisX = qRel.x / sinHalf;
  const axisY = qRel.y / sinHalf;
  const axisZ = qRel.z / sinHalf;
  const sign = axisX * up.x + axisY * up.y + axisZ * up.z >= 0 ? 1 : -1;
  return 2 * Math.atan2(sinHalf, qRel.w) * sign;
}

const MAX_UNDO = 60;
const undoStack = [];
let undoing = false;
/** @type {object | null} */
let copyBuffer = null;

let autosaveTimer = null;

function disposeObjectDeep(obj) {
  obj.traverse((o) => {
    if (o.geometry) o.geometry.dispose();
    if (o.material) {
      const m = o.material;
      if (Array.isArray(m)) m.forEach((x) => x.dispose?.());
      else m.dispose?.();
    }
  });
}

let editorTerrainMesh = null;
let editorTerrainData = null;

function disposeEditorTerrainMesh() {
  if (!editorTerrainMesh) return;
  scene?.remove(editorTerrainMesh);
  editorTerrainMesh.geometry?.dispose?.();
  editorTerrainMesh.material?.dispose?.();
  editorTerrainMesh = null;
  editorTerrainData = null;
}

function fract(n) {
  return n - Math.floor(n);
}

function hash21(x, z, seed) {
  return fract(Math.sin((x * 127.1 + z * 311.7 + seed) * 43758.5453123));
}

function smoothstep2(t) {
  const x = Math.max(0, Math.min(1, t));
  return x * x * (3 - 2 * x);
}

function noise2Smooth(x, z, seed) {
  const x0 = Math.floor(x);
  const z0 = Math.floor(z);
  const tx = x - x0;
  const tz = z - z0;
  const ux = smoothstep2(tx);
  const uz = smoothstep2(tz);
  const a = hash21(x0, z0, seed);
  const b = hash21(x0 + 1, z0, seed);
  const c = hash21(x0, z0 + 1, seed);
  const d = hash21(x0 + 1, z0 + 1, seed);
  const i1 = a + (b - a) * ux;
  const i2 = c + (d - c) * ux;
  return i1 + (i2 - i1) * uz;
}

/**
 * @returns {{ version: number; size: number; segments: number; color: number; heights: number[] } | null}
 */
function generateEditorTerrain(opts = {}) {
  if (!scene) return null;
  const size = Math.max(40, Math.min(400, Number(opts.size) || 200));
  const segments = Math.max(8, Math.min(128, Math.floor(Number(opts.segments) || 64)));
  const amplitude = Math.max(0.1, Math.min(40, Number(opts.amplitude) ?? 2.5));
  const scale = Math.max(0.5, Math.min(20, Number(opts.scale) ?? 2.4));
  const seed = Number.isFinite(Number(opts.seed)) ? Number(opts.seed) : Math.floor(Math.random() * 1e9);

  disposeEditorTerrainMesh();
  const geo = new THREE.PlaneGeometry(size, size, segments, segments);
  const pos = geo.attributes.position;
  let minH = Infinity;
  for (let i = 0; i < pos.count; i++) {
    const lx = pos.getX(i);
    const ly = pos.getY(i);
    const u = (lx / size) * scale;
    const v = (ly / size) * scale;
    const h = noise2Smooth(u, v, seed) * amplitude;
    pos.setZ(i, h);
    minH = Math.min(minH, h);
  }
  for (let i = 0; i < pos.count; i++) {
    pos.setZ(i, pos.getZ(i) - minH);
  }
  pos.needsUpdate = true;
  geo.computeVertexNormals();
  const heights = [];
  for (let i = 0; i < pos.count; i++) heights.push(Number(pos.getZ(i).toFixed(5)));
  const mat = new THREE.MeshStandardMaterial({
    color: 0x3d6b3d,
    roughness: 1,
    metalness: 0,
  });
  editorTerrainMesh = new THREE.Mesh(geo, mat);
  editorTerrainMesh.name = 'EditorTerrain';
  editorTerrainMesh.rotation.x = -Math.PI / 2;
  editorTerrainMesh.receiveShadow = true;
  scene.add(editorTerrainMesh);
  /** Flat-terrain path hides the planet sphere so the two grounds don't
   *  z-fight. Terrain on a sphere is not yet modeled — this keeps the legacy
   *  flat-terrain workflow functioning while planet mode becomes the default. */
  if (planetMesh) planetMesh.visible = false;
  if (planetHelperGrid) planetHelperGrid.visible = false;

  editorTerrainData = {
    version: 1,
    size,
    segments,
    color: 0x3d6b3d,
    seed,
    heights,
  };
  return editorTerrainData;
}

function applyTerrainFromDiskData(t) {
  if (!scene || !t || !Array.isArray(t.heights)) {
    disposeEditorTerrainMesh();
    if (planetMesh) planetMesh.visible = true;
    if (planetHelperGrid) planetHelperGrid.visible = true;
    editorTerrainData = null;
    return;
  }
  const size = Number(t.size) || 200;
  const seg = Math.max(4, Math.min(256, Math.floor(Number(t.segments) || 64)));
  if (!Array.isArray(t.heights) || t.heights.length !== (seg + 1) * (seg + 1)) {
    disposeEditorTerrainMesh();
    return;
  }
  disposeEditorTerrainMesh();
  const geo = new THREE.PlaneGeometry(size, size, seg, seg);
  const pos = geo.attributes.position;
  for (let i = 0; i < pos.count; i++) pos.setZ(i, Number(t.heights[i]) || 0);
  pos.needsUpdate = true;
  geo.computeVertexNormals();
  const mat = new THREE.MeshStandardMaterial({
    color: typeof t.color === 'number' ? t.color : 0x3d6b3d,
    roughness: 1,
    metalness: 0,
  });
  editorTerrainMesh = new THREE.Mesh(geo, mat);
  editorTerrainMesh.name = 'EditorTerrain';
  editorTerrainMesh.rotation.x = -Math.PI / 2;
  editorTerrainMesh.receiveShadow = true;
  scene.add(editorTerrainMesh);
  if (planetMesh) planetMesh.visible = false;
  if (planetHelperGrid) planetHelperGrid.visible = false;
  editorTerrainData = {
    version: 1,
    size,
    /** BUGFIX: was bare `segments` shorthand but the local is `seg` — threw a ReferenceError
     *  and aborted loading any saved map that contains terrain. */
    segments: seg,
    color: typeof t.color === 'number' ? t.color : 0x3d6b3d,
    seed: t.seed ?? 0,
    heights: t.heights.map((h) => Number(h) || 0),
  };
}

function clearPlacedContent() {
  deselect();
  disposeEditorTerrainMesh();
  if (planetMesh) planetMesh.visible = true;
  if (planetHelperGrid) planetHelperGrid.visible = true;
  for (const g of [...placed]) {
    disposeObjectDeep(g);
    itemsRoot.remove(g);
  }
  placed.length = 0;
  sellSpotRef = null;
}

/**
 * Rollers (thin cylinders along X) + side rails; length along local Z. Matches game.js.
 */
function buildConveyorGroup(worldW, worldH, worldD, x, y, z) {
  const g = new THREE.Group();
  const L = Math.max(0.45, worldD);
  const W = Math.max(0.28, worldW);
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
    cyl.castShadow = true;
    cyl.receiveShadow = true;
    g.add(cyl);
  }
  const railH = Math.max(0.05, Math.min(worldH * 0.55, 0.14));
  const railT = Math.max(0.03, W * 0.048);
  const railGeo = new THREE.BoxGeometry(railT, railH, L);
  const railL = new THREE.Mesh(railGeo, matRail);
  railL.position.set(-W / 2 - railT / 2, railH / 2 + rollerR * 0.45, 0);
  railL.castShadow = true;
  const railR = railL.clone();
  railR.position.x = W / 2 + railT / 2;
  g.add(railL, railR);
  g.position.set(x, y, z);
  g.userData.placedItem = true;
  g.userData.itemKind = 'conveyor';
  g.userData.base = { w: worldW, h: worldH, d: worldD };
  ensureConveyorArrow(g);
  return g;
}

/** Belt travel direction in editor: local −Z (along roller length). */
function ensureConveyorArrow(g) {
  if (!g || g.userData.itemKind !== 'conveyor') return;
  if (g.userData.conveyorArrow) {
    g.remove(g.userData.conveyorArrow);
    g.userData.conveyorArrow.dispose?.();
    g.userData.conveyorArrow = null;
  }
  const b = g.userData.base || { w: 0.9, h: 0.16, d: 4 };
  const len = Math.max(0.4, Math.min(2.4, b.d * 0.38));
  const head = Math.min(0.32, len * 0.22);
  const origin = new THREE.Vector3(0, Math.max(0.1, b.h * 0.45), 0);
  const arrow = new THREE.ArrowHelper(
    new THREE.Vector3(0, 0, -1),
    origin,
    len,
    0xffb020,
    head,
    head * 0.72
  );
  arrow.userData.isConveyorArrow = true;
  g.add(arrow);
  g.userData.conveyorArrow = arrow;
}

/**
 * Uses saved conveyor prefab (if any) scaled to map dimensions; otherwise procedural rollers.
 */
function buildConveyorFromStorageOrDefault(worldW, worldH, worldD, x, y, z) {
  const pdata = tryGetPrefabDataForKind('conveyor');
  if (pdata && Array.isArray(pdata.parts) && pdata.parts.length > 0) {
    const g = deserializePrefabGroup(pdata);
    const pb = pdata.base || { w: 1, h: 1, d: 1 };
    g.scale.set(
      worldW / Math.max(0.01, pb.w),
      worldH / Math.max(0.01, pb.h),
      worldD / Math.max(0.01, pb.d)
    );
    g.position.set(x, y, z);
    g.userData.placedItem = true;
    g.userData.itemKind = 'conveyor';
    g.userData.base = { w: worldW, h: worldH, d: worldD };
    g.userData.fromPrefab = true;
    ensureConveyorArrow(g);
    return g;
  }
  return buildConveyorGroup(worldW, worldH, worldD, x, y, z);
}

/**
 * World size (sx,sy,sz) for map JSON.
 * Procedural: base * group scale.
 * Prefab: prefab AABB (prefabBase) * group scale — do NOT multiply userData.base * scale
 * (that double-counts: base is already the target map size and scale is W/pb.w).
 */
function getConveyorWorldSizes(g) {
  const b = g.userData.base;
  if (!b) return null;
  const pb = g.userData.prefabBase;
  if (pb) {
    return {
      sx: pb.w * g.scale.x,
      sy: pb.h * g.scale.y,
      sz: pb.d * g.scale.z,
    };
  }
  return {
    sx: b.w * g.scale.x,
    sy: b.h * g.scale.y,
    sz: b.d * g.scale.z,
  };
}

/** Tilt ramp mesh so bottom sits on y=0 in group space (before group rotation). */
function applyRampMeshTiltScaled(mesh, def, sx, sy, sz) {
  const rise = def.rise != null ? def.rise : 0.35;
  mesh.rotation.x = -Math.atan2(rise * sy, def.baseD * sz);
  mesh.position.y = (def.baseH * sy) / 2;
  mesh.updateMatrixWorld(true);
  const box = new THREE.Box3().setFromObject(mesh);
  mesh.position.y -= box.min.y;
}

function createPropFromExport(p) {
  const kind = p.kind;
  const tx = Number(p.x) || 0;
  const tz = Number(p.z) || 0;
  const yaw = Number(p.ry) || 0;
  if (kind === 'conveyor') {
    const def = TOOL_DEFS.conveyor;
    const bw = def.baseW;
    const bh = def.baseH;
    const bd = def.baseD;
    const w = p.sx != null && Number(p.sx) > 0 ? Number(p.sx) : bw;
    const h = p.sy != null && Number(p.sy) > 0 ? Number(p.sy) : bh;
    const d = p.sz != null && Number(p.sz) > 0 ? Number(p.sz) : bd;
    const grp = buildConveyorFromStorageOrDefault(w, h, d, tx, Number(p.y) || 0, tz);
    /** Conveyors carry `rx/rz` in their serialized form (users can tilt them
     *  on slopes). Sphere-project them around the tangent position; the
     *  surface-align quaternion replaces the stored `rx/rz` because those
     *  only made sense on a flat plane. `ry` survives as yaw. */
    placeItemOnPlanet(grp, tx, tz, yaw);
    return grp;
  }
  const def = TOOL_DEFS[kind];
  if (!def || !def.baseW) return null;
  const geo = new THREE.BoxGeometry(def.baseW, def.baseH, def.baseD);
  const mesh = new THREE.Mesh(
    geo,
    new THREE.MeshStandardMaterial({
      color: def.color,
      roughness: 0.88,
      metalness: 0.06,
    })
  );
  mesh.castShadow = true;
  mesh.receiveShadow = true;
  if (kind !== 'ramp') mesh.position.y = def.baseH / 2;
  mesh.userData.base = { w: def.baseW, h: def.baseH, d: def.baseD };
  mesh.userData.itemKind = kind;
  const g = new THREE.Group();
  g.add(mesh);
  const bw = def.baseW;
  const bh = def.baseH;
  const bd = def.baseD;
  const sx = (Number(p.sx) || bw) / bw;
  const sy = (Number(p.sy) || bh) / bh;
  const sz = (Number(p.sz) || bd) / bd;
  mesh.scale.set(sx, sy, sz);
  if (kind === 'ramp') applyRampMeshTiltScaled(mesh, def, sx, sy, sz);
  g.scale.set(1, 1, 1);
  g.userData.placedItem = true;
  g.userData.itemKind = kind;
  placeItemOnPlanet(g, tx, tz, yaw);
  return g;
}

function buildMapFromData(data) {
  if (!data || typeof data !== 'object') return;
  clearPlacedContent();
  /** Resize the planet ground to whatever radius this slice declares. Falls
   *  back to the previous radius if the slice doesn't specify — keeps tiny
   *  test documents from shrinking the editor to a pebble. */
  setEditorPlanetFromSlice(data);

  for (const row of data.trees || []) {
    let tg = null;
    if (Array.isArray(row) && row.length >= 2) {
      tg = createTreeGroup(row[0], row[1]);
    } else if (
      row &&
      typeof row === 'object' &&
      !Array.isArray(row) &&
      typeof row.x === 'number' &&
      typeof row.z === 'number'
    ) {
      tg = createTreeFromExport(row);
    }
    if (tg) {
      itemsRoot.add(tg);
      placed.push(tg);
    }
  }

  for (const pr of data.props || []) {
    if (!pr || !pr.kind) continue;
    let g = null;
    if (pr.kind === 'label') {
      g = createLabelFromExport(pr);
    } else {
      g = createPropFromExport(pr);
    }
    if (g) {
      itemsRoot.add(g);
      placed.push(g);
    }
  }

  for (const ps of data.plotSites || []) {
    const g = createLandPlotFromExport(ps);
    if (g) {
      itemsRoot.add(g);
      placed.push(g);
    }
  }

  const ss = data.sellSpot || {};
  const d0 = defaultSellXZ();
  const sx = typeof ss.x === 'number' ? ss.x : d0.x;
  const sz = typeof ss.z === 'number' ? ss.z : d0.z;
  const r = typeof ss.r === 'number' && ss.r > 0 ? Math.min(40, Math.max(0.5, ss.r)) : 3.5;
  sellSpotRef = createSellSpotMarker(sx, sz, 3.5);
  const sc = r / 3.5;
  sellSpotRef.scale.set(sc, 1, sc);
  itemsRoot.add(sellSpotRef);
  placed.push(sellSpotRef);
  syncSellSpotScaleToRadius();

  applyTerrainFromDiskData(data.terrain ?? null);
}

function createTreeFromExport(p) {
  const logSkin = normalizeMapTreeLogSkin(p.logSkin);
  const x = Number(p.x) || 0;
  const z = Number(p.z) || 0;
  const g = createTreeGroup(x, z, logSkin);
  /** Replace the yaw stashed by `createTreeGroup` (which defaults to 0) with
   *  the serialized value, and re-project so the tree stays flush on the
   *  surface with the correct yaw around its local up. */
  placeItemOnPlanet(g, x, z, Number(p.ry) || 0);
  if (g.userData.fromPrefab && g.userData.base) {
    const b = g.userData.base;
    const sx = (p.sx != null ? Number(p.sx) : b.w) / b.w;
    const sy = (p.sy != null ? Number(p.sy) : b.h) / b.h;
    const sz = (p.sz != null ? Number(p.sz) : b.d) / b.d;
    g.scale.set(sx, sy, sz);
    return g;
  }
  const cyl = g.children.find((c) => c.isMesh);
  if (!cyl || !cyl.userData.base) return g;
  const b = cyl.userData.base;
  const sx = (p.sx != null ? Number(p.sx) : b.w) / b.w;
  const sy = (p.sy != null ? Number(p.sy) : b.h) / b.h;
  const sz = (p.sz != null ? Number(p.sz) : b.d) / b.d;
  cyl.scale.set(sx, sy, sz);
  g.scale.set(1, 1, 1);
  return g;
}

function tryLoadMapFromStorageOrDefault() {
  try {
    const raw = localStorage.getItem(MAP_EDITOR_STORAGE_KEY);
    if (raw) {
      const data = JSON.parse(raw);
      if (data && typeof data === 'object') {
        mapEditorDocument = normalizeMapDocument(data);
        const slice = getWorldSliceFromDocument(
          mapEditorDocument,
          mapEditorDocument.defaultPlanetId
        );
        if (mapSliceHasAuthoring(slice)) {
          buildMapFromData(slice);
          return;
        }
      }
    }
  } catch (_) {
    mapEditorDocument = null;
  }

  const sell = defaultSellXZ();
  sellSpotRef = createSellSpotMarker(sell.x, sell.z, 3.5);
  itemsRoot.add(sellSpotRef);
  placed.push(sellSpotRef);
}

function persistMapToStorageNow() {
  const out = document.getElementById('me-output');
  const status = document.getElementById('me-save-status');
  try {
    if (out?.value) localStorage.setItem(MAP_EDITOR_STORAGE_KEY, out.value);
    if (status) {
      status.textContent = 'Saved — stored in this browser; game reads it on load.';
      status.style.color = '#8cf';
    }
  } catch {
    if (status) {
      status.textContent = 'Could not save (private mode or storage full). Use Download file.';
      status.style.color = '#f88';
    }
  }
}

function scheduleAutosave() {
  const status = document.getElementById('me-save-status');
  if (status) {
    status.textContent = 'Saving…';
    status.style.color = '#9aa0a6';
  }
  clearTimeout(autosaveTimer);
  autosaveTimer = setTimeout(persistMapToStorageNow, 400);
}

function pushUndoSnapshot() {
  if (undoing) return;
  refreshExport();
  const v = document.getElementById('me-output')?.value;
  if (v) {
    undoStack.push(v);
    if (undoStack.length > MAX_UNDO) undoStack.shift();
  }
}

function popUndo() {
  if (undoStack.length === 0) return;
  undoing = true;
  const prev = undoStack.pop();
  try {
    const doc = normalizeMapDocument(JSON.parse(prev));
    mapEditorDocument = doc;
    buildMapFromData(getWorldSliceFromDocument(doc, doc.defaultPlanetId));
    refreshExport();
    syncList();
    syncPlanetSelectUi();
    persistMapToStorageNow();
  } catch (_) {}
  undoing = false;
}

function pickRandom(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

function hexToRgb(hex) {
  const h = hex.replace('#', '');
  return {
    r: parseInt(h.slice(0, 2), 16),
    g: parseInt(h.slice(2, 4), 16),
    b: parseInt(h.slice(4, 6), 16),
  };
}

function buildLabelCanvasTexture(text, bgHex, fgHex) {
  const pad = 20;
  const lineGap = 8;
  const fontPx = 28;
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d');
  ctx.font = `600 ${fontPx}px system-ui, "Segoe UI", sans-serif`;
  const lines = String(text || ' ').split('\n');
  let maxW = 120;
  for (const line of lines) {
    maxW = Math.max(maxW, ctx.measureText(line).width);
  }
  const lineH = fontPx + lineGap;
  const W = Math.ceil(Math.min(1024, maxW + pad * 2));
  const H = Math.ceil(Math.min(512, lines.length * lineH + pad * 2));
  canvas.width = W;
  canvas.height = H;
  const g0 = hexToRgb(bgHex);
  const g1 = hexToRgb(fgHex);
  const grd = ctx.createLinearGradient(0, 0, W, H);
  grd.addColorStop(0, `rgb(${g0.r},${g0.g},${g0.b})`);
  grd.addColorStop(
    1,
    `rgb(${Math.min(255, g0.r + 40)},${Math.min(255, g0.g + 35)},${Math.min(255, g0.b + 50)})`
  );
  ctx.fillStyle = grd;
  ctx.fillRect(0, 0, W, H);
  ctx.strokeStyle = 'rgba(255,255,255,0.4)';
  ctx.lineWidth = 3;
  ctx.strokeRect(2, 2, W - 4, H - 4);
  const fg = hexToRgb(fgHex);
  ctx.fillStyle = `rgb(${fg.r},${fg.g},${fg.b})`;
  ctx.font = `600 ${fontPx}px system-ui, "Segoe UI", sans-serif`;
  ctx.shadowColor = 'rgba(0,0,0,0.45)';
  ctx.shadowBlur = 4;
  ctx.shadowOffsetX = 1;
  ctx.shadowOffsetY = 1;
  let y = pad + fontPx;
  for (const line of lines) {
    ctx.fillText(line, pad, y);
    y += lineH;
  }
  const tex = new THREE.CanvasTexture(canvas);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.needsUpdate = true;
  return { tex, w: W, h: H };
}

function createLabelGroup(x, z, text, bgHex, fgHex) {
  const pal = pickRandom(LABEL_PALETTES);
  const bg = bgHex ?? pal.bg;
  const fg = fgHex ?? pal.fg;
  const { tex } = buildLabelCanvasTexture(text, bg, fg);
  const mat = new THREE.SpriteMaterial({
    map: tex,
    transparent: true,
    depthWrite: false,
    opacity: 0.98,
  });
  const sprite = new THREE.Sprite(mat);
  const aspect = tex.image.width / tex.image.height;
  const baseH = 1.35;
  sprite.scale.set(baseH * aspect, baseH, 1);
  sprite.position.set(0, 1.25, 0);
  sprite.castShadow = false;

  const g = new THREE.Group();
  g.add(sprite);
  g.userData.placedItem = true;
  g.userData.itemKind = 'label';
  g.userData.labelText = text;
  g.userData.labelBg = bg;
  g.userData.labelFg = fg;
  g.userData.labelSprite = sprite;
  placeItemOnPlanet(g, x, z, 0);
  return g;
}

function updateLabelGroupText(group, newText) {
  const sprite = group.userData.labelSprite;
  if (!sprite?.material) return;
  const oldMap = sprite.material.map;
  const { tex } = buildLabelCanvasTexture(
    newText,
    group.userData.labelBg,
    group.userData.labelFg
  );
  sprite.material.map = tex;
  sprite.material.needsUpdate = true;
  if (oldMap) oldMap.dispose();
  const aspect = tex.image.width / tex.image.height;
  const baseH = 1.35;
  sprite.scale.set(baseH * aspect, baseH, 1);
  group.userData.labelText = newText;
}

function createLabelFromExport(p) {
  const tx = Number(p.x) || 0;
  const tz = Number(p.z) || 0;
  const yaw = Number(p.ry) || 0;
  const g = createLabelGroup(tx, tz, String(p.text ?? 'Hi'), p.bg, p.fg);
  /** `createLabelGroup` already called `placeItemOnPlanet(g, tx, tz, 0)`.
   *  Re-place with the serialized yaw so the label faces the direction the
   *  author set. Sprite's billboard behavior keeps it readable even when
   *  the label is rotated, so yaw only affects the anchor position. */
  placeItemOnPlanet(g, tx, tz, yaw);
  const sx = p.sx != null ? Number(p.sx) : 1;
  const sy = p.sy != null ? Number(p.sy) : 1;
  g.scale.set(Math.max(0.15, sx), Math.max(0.15, sy), 1);
  return g;
}

/** @type {THREE.Scene} */
let scene;
/** @type {THREE.PerspectiveCamera} */
let camera;
/** @type {THREE.WebGLRenderer} */
let renderer;
/** @type {OrbitControls} */
let controls;
/** @type {TransformControls} */
let transformControls;
/** @type {THREE.Raycaster} */
let raycaster;
/** @type {THREE.Vector2} */
let pointer;

const itemsRoot = new THREE.Group();
itemsRoot.name = 'PlacedItems';

/** @type {THREE.Group[]} */
const placed = [];

/** @type {THREE.Group | null} */
let sellSpotRef = null;

let activeTool = 'tree';
/** 'wood' | 'dark' | 'void' — matches game logSkin on map trees. */
let activeTreeSkin = 'wood';
/** @type {'translate'|'rotate'|'scale'} */
let gizmoMode = 'translate';

let selected = null;
/** Ctrl+click conveyors for batch rotate (belt direction). */
const selectedConveyors = new Set();
let skipNextClickSelect = false;
let ptrDown = { x: 0, y: 0 };

const TOOL_DEFS = {
  tree: { label: 'Tree' },
  slab: { label: 'Grey slab', baseW: 2, baseH: 0.12, baseD: 2, color: 0x8a8e94 },
  block: { label: 'Block', baseW: 1, baseH: 1, baseD: 1, color: 0x5c636a },
  strip: { label: 'Strip', baseW: 5, baseH: 0.22, baseD: 0.4, color: 0x7a8090 },
  conveyor: { label: 'Conveyor', baseW: 0.9, baseH: 0.16, baseD: 4, color: 0x4a4d55 },
  /** Inclined box: rise (m) over baseD run; physics matches tilt in game. */
  ramp: { label: 'Ramp', baseW: 2, baseH: 0.15, baseD: 3, rise: 0.35, color: 0x8b7355 },
  label: { label: 'Chat / text' },
  landPlot: { label: 'Land plot (Bob)' },
};

const LABEL_PALETTES = [
  { bg: '#0d9488', fg: '#ecfdf5' },
  { bg: '#7c3aed', fg: '#faf5ff' },
  { bg: '#db2777', fg: '#fdf2f8' },
  { bg: '#ea580c', fg: '#fff7ed' },
  { bg: '#2563eb', fg: '#eff6ff' },
  { bg: '#16a34a', fg: '#f0fdf4' },
  { bg: '#4f46e5', fg: '#eef2ff' },
];

const CHAT_LINES = [
  'Nice chop! 🪓',
  'Wood gang',
  'Sell here!',
  'Bob is cool',
  'Big logs only',
  'No griefing pls',
  'GG',
  'Rate 5 stars',
];

function buildEditorStore() {
  const g = new THREE.Group();
  g.name = 'EditorStore';
  const wallMat = new THREE.MeshStandardMaterial({ color: 0x6a6e73, roughness: 0.92, metalness: 0.05 });
  const glassMat = new THREE.MeshStandardMaterial({
    color: 0x88aacc,
    roughness: 0.08,
    metalness: 0.35,
    transparent: true,
    opacity: 0.35,
  });
  const floorMat = new THREE.MeshStandardMaterial({ color: 0x4a4d52, roughness: 0.95 });
  const sx = EDITOR_STORE.x;
  const sz = EDITOR_STORE.z;
  const W = EDITOR_STORE.W;
  const D = EDITOR_STORE.D;
  const H = 4;
  const t = 0.2;

  const floor = new THREE.Mesh(new THREE.BoxGeometry(W, 0.15, D), floorMat);
  floor.position.set(sx, 0.075, sz);
  floor.receiveShadow = true;
  floor.castShadow = true;
  g.add(floor);

  const dockZ = sz + D / 2 + EDITOR_DOCK_DEPTH / 2;
  const loadingDock = new THREE.Mesh(new THREE.BoxGeometry(W, 0.14, EDITOR_DOCK_DEPTH), floorMat);
  loadingDock.position.set(sx, 0.07, dockZ);
  loadingDock.receiveShadow = true;
  loadingDock.castShadow = true;
  g.add(loadingDock);

  const roof = new THREE.Mesh(new THREE.BoxGeometry(W + 0.4, 0.25, D + 0.4), wallMat);
  roof.position.set(sx, H + 0.125, sz);
  roof.castShadow = true;
  roof.receiveShadow = true;
  g.add(roof);

  const backWall = new THREE.Mesh(new THREE.BoxGeometry(W, H, t), wallMat);
  backWall.position.set(sx, H / 2, sz + D / 2);
  backWall.castShadow = true;
  backWall.receiveShadow = true;
  g.add(backWall);

  const sideL = new THREE.Mesh(new THREE.BoxGeometry(t, H, D - 0.2), wallMat);
  sideL.position.set(sx - W / 2 + t / 2, H / 2, sz);
  sideL.castShadow = true;
  sideL.receiveShadow = true;
  g.add(sideL);

  const sideR = new THREE.Mesh(new THREE.BoxGeometry(t, H, D - 0.2), wallMat);
  sideR.position.set(sx + W / 2 - t / 2, H / 2, sz);
  sideR.castShadow = true;
  sideR.receiveShadow = true;
  g.add(sideR);

  const doorW = 3.2;
  const frontZ = sz - D / 2;
  const sideW = (W - doorW) / 2;
  const fLeft = new THREE.Mesh(new THREE.BoxGeometry(sideW, H, t), wallMat);
  fLeft.position.set(sx - doorW / 2 - sideW / 2, H / 2, frontZ);
  fLeft.castShadow = true;
  fLeft.receiveShadow = true;
  g.add(fLeft);
  const fRight = new THREE.Mesh(new THREE.BoxGeometry(sideW, H, t), wallMat);
  fRight.position.set(sx + doorW / 2 + sideW / 2, H / 2, frontZ);
  fRight.castShadow = true;
  fRight.receiveShadow = true;
  g.add(fRight);

  const winGeo = new THREE.PlaneGeometry(3.5, 2.2);
  const winL = new THREE.Mesh(winGeo, glassMat);
  winL.position.set(sx - 3.8, 2.1, frontZ + 0.11);
  g.add(winL);
  const winR = new THREE.Mesh(winGeo, glassMat);
  winR.position.set(sx + 3.8, 2.1, frontZ + 0.11);
  g.add(winR);

  const bobGroup = new THREE.Group();
  bobGroup.position.set(EDITOR_STORE.bobX, 0, EDITOR_STORE.bobZ);
  const body = new THREE.Mesh(
    new THREE.CylinderGeometry(0.38, 0.42, 1.1, 10),
    new THREE.MeshStandardMaterial({ color: 0x3355aa, roughness: 0.75 })
  );
  body.position.y = 0.55;
  body.castShadow = true;
  const head = new THREE.Mesh(
    new THREE.SphereGeometry(0.32, 12, 10),
    new THREE.MeshStandardMaterial({ color: 0xe8c8a8, roughness: 0.65 })
  );
  head.position.y = 1.22;
  head.castShadow = true;
  bobGroup.add(body, head);
  g.add(bobGroup);

  return g;
}

function createSellSpotMarker(x, z, baseR = 3.5) {
  const g = new THREE.Group();
  const ring = new THREE.Mesh(
    new THREE.RingGeometry(baseR * 0.88, baseR, 80),
    new THREE.MeshBasicMaterial({
      color: 0x4ade80,
      transparent: true,
      opacity: 0.92,
      side: THREE.DoubleSide,
    })
  );
  ring.rotation.x = -Math.PI / 2;
  ring.position.y = 0.04;
  const inner = new THREE.Mesh(
    new THREE.CircleGeometry(baseR * 0.86, 64),
    new THREE.MeshBasicMaterial({
      color: 0x22c55e,
      transparent: true,
      opacity: 0.22,
      side: THREE.DoubleSide,
    })
  );
  inner.rotation.x = -Math.PI / 2;
  inner.position.y = 0.035;
  const rim = new THREE.Mesh(
    new THREE.TorusGeometry(baseR * 0.98, 0.06, 8, 64),
    new THREE.MeshStandardMaterial({ color: 0x86efac, roughness: 0.4, metalness: 0.1 })
  );
  rim.rotation.x = Math.PI / 2;
  rim.position.y = 0.055;
  g.add(inner, ring, rim);
  g.userData.placedItem = true;
  g.userData.itemKind = 'sellSpot';
  g.userData.isSellSpot = true;
  g.userData.sellRadius = baseR;
  placeItemOnPlanet(g, x, z, 0);
  return g;
}

function init() {
  scene = new THREE.Scene();
  scene.background = new THREE.Color(0x87b8e8);

  camera = new THREE.PerspectiveCamera(50, window.innerWidth / window.innerHeight, 0.1, 500);
  camera.position.set(-8, 22, 32);

  renderer = new THREE.WebGLRenderer({ antialias: true });
  renderer.setSize(window.innerWidth, window.innerHeight);
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  document.body.appendChild(renderer.domElement);

  const hemi = new THREE.HemisphereLight(0xcfe8ff, 0x3a4a30, 0.55);
  scene.add(hemi);
  const sun = new THREE.DirectionalLight(0xffffff, 0.88);
  sun.position.set(24, 48, 18);
  sun.castShadow = true;
  sun.shadow.mapSize.set(2048, 2048);
  sun.shadow.camera.near = 1;
  sun.shadow.camera.far = 120;
  sun.shadow.camera.left = -50;
  sun.shadow.camera.right = 50;
  sun.shadow.camera.top = 50;
  sun.shadow.camera.bottom = -50;
  scene.add(sun);

  /** Planet sphere ground — replaces the old flat 200×200 PlaneGeometry so the
   *  editor mirrors the in-game curvature. Sized from the currently selected
   *  planet's `sphereRadius` via {@link setEditorPlanetFromSlice} once the
   *  document is loaded; built here at the default 2500m radius so the scene
   *  has something to render on first frame. */
  rebuildEditorPlanetMesh(0x3d6b3d);

  /** Helper grid: small 60×60 grid sitting at the north-pole tangent plane.
   *  Stays flat (planetarium-style reference) so snapping still feels intuitive.
   *  `y = 0.02` keeps it from z-fighting with the sphere surface right at the
   *  origin. Raised from the old 0.01 because the sphere peaks right at 0. */
  planetHelperGrid = new THREE.GridHelper(60, 60, 0x224422, 0x335533);
  planetHelperGrid.position.y = 0.02;
  planetHelperGrid.name = 'PlanetTangentGrid';
  scene.add(planetHelperGrid);

  scene.add(buildEditorStore());

  scene.add(itemsRoot);

  controls = new OrbitControls(camera, renderer.domElement);
  controls.target.set(EDITOR_STORE.x, 0, EDITOR_STORE.z);
  controls.enableDamping = true;
  controls.dampingFactor = 0.08;

  transformControls = new TransformControls(camera, renderer.domElement);
  transformControls.setSize(0.85);
  transformControls.addEventListener('dragging-changed', (ev) => {
    controls.enabled = !ev.value;
    if (ev.value) pushUndoSnapshot();
    if (!ev.value) {
      skipNextClickSelect = true;
      if (selected) {
        /** On sphere: after a gizmo drag, `selected.position` is in world
         *  space (potentially off the surface for translate, unchanged for
         *  rotate/scale). We inverse-project back to tangent `(x, z)` for
         *  translate, then re-apply the surface-align quaternion + yaw via
         *  {@link placeItemOnPlanet}. Rotate drags end with the item still
         *  on its original tangent position; we just extract the new yaw
         *  around the surface normal and re-place to keep alignment crisp. */
        if (gizmoMode === 'translate') {
          const outXZ = { x: 0, z: 0 };
          const ok = surfaceWorldToTangent(selected.position, outXZ);
          if (ok) {
            const snappedX = snapMapGrid(outXZ.x);
            const snappedZ = snapMapGrid(outXZ.z);
            placeItemOnPlanet(selected, snappedX, snappedZ, selected.userData.yaw ?? 0);
          }
        } else if (gizmoMode === 'rotate') {
          const mx = selected.userData.mapX ?? 0;
          const mz = selected.userData.mapZ ?? 0;
          projectTangentToSurface(mx, mz, _tmpPos, _tmpUp);
          const newYaw = extractYawFromQuaternion(selected.quaternion, _tmpUp);
          placeItemOnPlanet(selected, mx, mz, newYaw);
        }
        refreshExport();
      }
    }
  });
  transformControls.addEventListener('objectChange', () => {
    clampSelectedScale();
    syncSellSpotScaleToRadius();
    if (selected?.userData?.itemKind === 'conveyor') {
      ensureConveyorArrow(selected);
    }
    refreshExport();
  });
  scene.add(transformControls);

  tryLoadMapFromStorageOrDefault();
  refreshExport();
  syncList();
  syncPlanetSelectUi();

  document.getElementById('me-planet-select')?.addEventListener('change', onMePlanetSelectChange);
  document.getElementById('me-planet-add')?.addEventListener('click', onMePlanetAddClick);

  raycaster = new THREE.Raycaster();
  pointer = new THREE.Vector2();

  window.addEventListener('resize', onResize);
  renderer.domElement.addEventListener('pointerdown', onPointerDown);
  renderer.domElement.addEventListener('pointerup', onPointerUp);
  window.addEventListener('keydown', onKeyDown);

  wireUi();
  setGizmoMode(gizmoMode);
  syncToolButtons();
  refreshExport();
  syncList();
  syncConveyorBatchUi();
  animate();
}

function wireUi() {
  for (const id of ['tree', 'slab', 'block', 'strip', 'ramp', 'conveyor', 'label', 'landPlot']) {
    document.getElementById(`me-tool-${id}`)?.addEventListener('click', () => {
      activeTool = id;
      syncToolButtons();
      syncTreeTextureSubUi();
    });
  }
  document.getElementById('me-tree-skin-wood')?.addEventListener('click', () => {
    activeTreeSkin = 'wood';
    syncTreeTextureSubUi();
  });
  document.getElementById('me-tree-skin-dark')?.addEventListener('click', () => {
    activeTreeSkin = 'dark';
    syncTreeTextureSubUi();
  });
  document.getElementById('me-tree-skin-void')?.addEventListener('click', () => {
    activeTreeSkin = 'void';
    syncTreeTextureSubUi();
  });
  document.getElementById('me-mode-translate')?.addEventListener('change', (e) => {
    if (e.target.checked) setGizmoMode('translate');
  });
  document.getElementById('me-mode-rotate')?.addEventListener('change', (e) => {
    if (e.target.checked) setGizmoMode('rotate');
  });
  document.getElementById('me-mode-scale')?.addEventListener('change', (e) => {
    if (e.target.checked) setGizmoMode('scale');
  });
  document.getElementById('me-delete')?.addEventListener('click', deleteSelected);
  document.getElementById('me-conveyor-rot-90')?.addEventListener('click', () => {
    rotateSelectedConveyorsY(Math.PI / 2);
  });
  document.getElementById('me-conveyor-rot-m90')?.addEventListener('click', () => {
    rotateSelectedConveyorsY(-Math.PI / 2);
  });
  document.getElementById('me-conveyor-rot-180')?.addEventListener('click', () => {
    rotateSelectedConveyorsY(Math.PI);
  });
  document.getElementById('me-clear')?.addEventListener('click', clearAll);
  document.getElementById('me-export')?.addEventListener('click', refreshExport);
  document.getElementById('me-copy')?.addEventListener('click', async () => {
    const out = document.getElementById('me-output');
    try {
      await navigator.clipboard.writeText(out?.value ?? '');
    } catch (_) {}
  });
  document.getElementById('me-apply-game')?.addEventListener('click', () => {
    refreshExport();
    persistMapToStorageNow();
  });
  document.getElementById('me-download')?.addEventListener('click', () => {
    refreshExport();
    const out = document.getElementById('me-output');
    const blob = new Blob([out?.value ?? '{}'], { type: 'application/json;charset=utf-8' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'lumber-map.json';
    a.click();
    URL.revokeObjectURL(a.href);
  });
  document.getElementById('me-import-file')?.addEventListener('change', (e) => {
    const input = e.target;
    const f = input.files?.[0];
    if (!f) return;
    const reader = new FileReader();
    reader.onload = () => {
      try {
        const data = JSON.parse(String(reader.result));
        pushUndoSnapshot();
        mapEditorDocument = normalizeMapDocument(data);
        buildMapFromData(
          getWorldSliceFromDocument(mapEditorDocument, mapEditorDocument.defaultPlanetId)
        );
        refreshExport();
        syncPlanetSelectUi();
        persistMapToStorageNow();
      } catch (err) {
        alert('Invalid JSON file.');
      }
      input.value = '';
    };
    reader.readAsText(f);
  });
  document.getElementById('me-gen-terrain')?.addEventListener('click', () => {
    pushUndoSnapshot();
    const raw = prompt('Terrain seed (integer, empty = random)', String(Math.floor(Math.random() * 1e6)));
    let seed;
    if (raw != null && String(raw).trim() !== '') {
      const n = Number(raw);
      if (Number.isFinite(n)) seed = Math.floor(n);
    }
    generateEditorTerrain({ seed });
    refreshExport();
    syncList();
    scheduleAutosave();
  });
  document.getElementById('me-clear-terrain')?.addEventListener('click', () => {
    pushUndoSnapshot();
    disposeEditorTerrainMesh();
    if (planetMesh) planetMesh.visible = true;
    if (planetHelperGrid) planetHelperGrid.visible = true;
    refreshExport();
    syncList();
    scheduleAutosave();
  });
}

function syncToolButtons() {
  for (const id of ['tree', 'slab', 'block', 'strip', 'ramp', 'conveyor', 'label', 'landPlot']) {
    const el = document.getElementById(`me-tool-${id}`);
    if (el) el.classList.toggle('me-active', activeTool === id);
  }
  syncTreeTextureSubUi();
}

function syncTreeTextureSubUi() {
  const wrap = document.getElementById('me-tree-texture-sub');
  if (wrap) wrap.style.display = activeTool === 'tree' ? 'flex' : 'none';
  document.getElementById('me-tree-skin-wood')?.classList.toggle('me-skin-active', activeTreeSkin === 'wood');
  document.getElementById('me-tree-skin-dark')?.classList.toggle('me-skin-active', activeTreeSkin === 'dark');
  document.getElementById('me-tree-skin-void')?.classList.toggle('me-skin-active', activeTreeSkin === 'void');
}

function setGizmoMode(mode) {
  gizmoMode = mode;
  transformControls.setMode(mode);
  transformControls.setTranslationSnap(mode === 'translate' ? MAP_GRID : null);
  const t = document.getElementById('me-mode-translate');
  const r = document.getElementById('me-mode-rotate');
  const s = document.getElementById('me-mode-scale');
  if (t) t.checked = mode === 'translate';
  if (r) r.checked = mode === 'rotate';
  if (s) s.checked = mode === 'scale';
}

function findPlacedGroup(obj) {
  let o = obj;
  while (o) {
    if (o.userData && o.userData.placedItem) return o;
    o = o.parent;
  }
  return null;
}

/** When mesh raycasts miss (thin rails, edge-on slabs), pick by proximity to the
 *  planet-surface hit point. */
const _pickPlanetHitXZ = { x: 0, z: 0 };
const _pickPlanetHitWorld = new THREE.Vector3();
const _pickPlanetHitUp = new THREE.Vector3();
const _pickBox = new THREE.Box3();
const _pickCenter = new THREE.Vector3();
const MAP_PICK_MAX_BOX_DIST = 4.5;
const MAP_PICK_MAX_RAY_CENTER_DIST = 10;

function pickNearestPlacedGroup(ray) {
  let best = null;
  let bestD = Infinity;

  /** First pass: find the nearest placed item whose AABB is close to the
   *  planet surface hit of this ray. On a sphere this replaces the old
   *  flat-plane distance check; the surface hit is a proper world point so
   *  AABB distance works the same way. */
  if (raycastEditorPlanet(ray, _pickPlanetHitXZ)) {
    projectTangentToSurface(_pickPlanetHitXZ.x, _pickPlanetHitXZ.z, _pickPlanetHitWorld, _pickPlanetHitUp);
    for (const g of placed) {
      _pickBox.setFromObject(g);
      const d = _pickBox.distanceToPoint(_pickPlanetHitWorld);
      if (d < bestD) {
        bestD = d;
        best = g;
      }
    }
    if (best && bestD <= MAP_PICK_MAX_BOX_DIST) return best;
  }

  best = null;
  bestD = Infinity;
  for (const g of placed) {
    _pickBox.setFromObject(g);
    _pickBox.getCenter(_pickCenter);
    const d = ray.distanceToPoint(_pickCenter);
    if (d < bestD) {
      bestD = d;
      best = g;
    }
  }
  if (best && bestD <= MAP_PICK_MAX_RAY_CENTER_DIST) return best;
  return null;
}

function isDescendantOf(child, ancestor) {
  let o = child;
  while (o) {
    if (o === ancestor) return true;
    o = o.parent;
  }
  return false;
}

function syncConveyorBatchUi() {
  const wrap = document.getElementById('me-conveyor-batch');
  const countEl = document.getElementById('me-conveyor-batch-count');
  if (countEl) countEl.textContent = String(selectedConveyors.size);
  if (wrap) wrap.style.display = selectedConveyors.size >= 1 ? 'block' : 'none';
}

function toggleConveyorSelection(g) {
  if (selectedConveyors.has(g)) {
    selectedConveyors.delete(g);
  } else {
    selectedConveyors.add(g);
  }
  if (selectedConveyors.size === 0) {
    deselect();
  } else if (selectedConveyors.size === 1) {
    const only = [...selectedConveyors][0];
    selectObject(only);
  } else {
    selected = null;
    transformControls.detach();
    syncConveyorBatchUi();
  }
}

function rotateSelectedConveyorsY(deltaY) {
  if (selectedConveyors.size === 0) return;
  pushUndoSnapshot();
  for (const g of selectedConveyors) {
    /** Yaw is now "rotation around surface normal at (mapX, mapZ)", not a
     *  raw Euler Y angle — bump the stashed `userData.yaw` and re-project
     *  via {@link placeItemOnPlanet} so the composed `qYaw * qAlign`
     *  quaternion stays well-formed. */
    const mx = g.userData?.mapX ?? 0;
    const mz = g.userData?.mapZ ?? 0;
    const yaw = (g.userData?.yaw ?? 0) + deltaY;
    placeItemOnPlanet(g, mx, mz, yaw);
  }
  refreshExport();
  syncList();
}

function selectObject(group) {
  selectedConveyors.clear();
  if (group.userData.itemKind === 'conveyor') {
    selectedConveyors.add(group);
  }
  if (selected === group) {
    syncConveyorBatchUi();
    return;
  }
  transformControls?.detach();
  selected = group;
  transformControls.attach(group);
  syncConveyorBatchUi();
}

function deselect() {
  selected = null;
  transformControls?.detach();
  selectedConveyors.clear();
  syncConveyorBatchUi();
}

function clampSelectedScale() {
  if (!selected) return;
  if (selected.userData.isSellSpot) {
    const sx = Math.max(0.25, Math.min(3, selected.scale.x));
    const sz = Math.max(0.25, Math.min(3, selected.scale.z));
    selected.scale.set(sx, 1, sz);
    return;
  }
  if (selected.userData.itemKind === 'label') {
    const sx = Math.max(0.15, Math.min(14, selected.scale.x));
    const sy = Math.max(0.15, Math.min(14, selected.scale.y));
    selected.scale.set(sx, sy, selected.scale.z);
    return;
  }
  if (selected.userData.itemKind === 'landPlot') {
    const sx = Math.max(0.35, Math.min(3.5, selected.scale.x));
    const sz = Math.max(0.35, Math.min(3.5, selected.scale.z));
    selected.scale.set(sx, 1, sz);
    return;
  }
  if (selected.userData.itemKind === 'conveyor') {
    const sx = Math.max(0.2, Math.min(6, selected.scale.x));
    const sy = Math.max(0.2, Math.min(3, selected.scale.y));
    const sz = Math.max(0.3, Math.min(28, selected.scale.z));
    selected.scale.set(sx, sy, sz);
    return;
  }
  selected.traverse((ch) => {
    if (ch.isMesh && ch.userData.base) {
      ch.scale.x = Math.max(0.04, ch.scale.x);
      ch.scale.y = Math.max(0.04, ch.scale.y);
      ch.scale.z = Math.max(0.04, ch.scale.z);
    }
  });
}

function syncSellSpotScaleToRadius() {
  if (!sellSpotRef) return;
  const g = sellSpotRef;
  const base = g.userData.sellRadius || 3.5;
  const effR = base * Math.max(g.scale.x, g.scale.z);
  g.userData.effectiveRadius = effR;
}

function newLandPlotId() {
  return `lp_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 9)}`;
}

function createLandPlotGroup(x, z, labelText, existingId) {
  const bw = 10;
  const bh = 0.07;
  const bd = 10;
  const soil = new THREE.Mesh(
    new THREE.BoxGeometry(bw, bh, bd),
    new THREE.MeshStandardMaterial({
      color: 0x6b4423,
      roughness: 0.92,
      metalness: 0.02,
    })
  );
  soil.castShadow = true;
  soil.receiveShadow = true;
  soil.position.y = bh / 2;
  soil.userData.base = { w: bw, h: bh, d: bd };
  soil.userData.isLandPlotSoil = true;
  const rm = Math.max(bw, bd) * 0.5;
  const ring = new THREE.Mesh(
    new THREE.RingGeometry(rm * 0.96, rm * 1.04, 48),
    new THREE.MeshBasicMaterial({
      color: 0x3d2918,
      transparent: true,
      opacity: 0.85,
      depthWrite: false,
    })
  );
  ring.rotation.x = -Math.PI / 2;
  ring.position.y = 0.09;
  ring.renderOrder = 1;
  const g = new THREE.Group();
  g.add(soil, ring);
  g.userData.placedItem = true;
  g.userData.itemKind = 'landPlot';
  g.userData.plotLabel = labelText || 'Land lot';
  g.userData.plotId = existingId || newLandPlotId();
  placeItemOnPlanet(g, x, z, 0);
  return g;
}

function createLandPlotFromExport(p) {
  if (!p || typeof p !== 'object') return null;
  const w = Math.max(2, Math.min(48, Number(p.w) || 10));
  const d = Math.max(2, Math.min(48, Number(p.d) || 10));
  const bh = 0.07;
  const soil = new THREE.Mesh(
    new THREE.BoxGeometry(w, bh, d),
    new THREE.MeshStandardMaterial({
      color: 0x6b4423,
      roughness: 0.92,
      metalness: 0.02,
    })
  );
  soil.castShadow = true;
  soil.receiveShadow = true;
  soil.position.y = bh / 2;
  soil.userData.base = { w, h: bh, d };
  soil.userData.isLandPlotSoil = true;
  const rm = Math.max(w, d) * 0.5;
  const ring = new THREE.Mesh(
    new THREE.RingGeometry(rm * 0.96, rm * 1.04, 48),
    new THREE.MeshBasicMaterial({
      color: 0x3d2918,
      transparent: true,
      opacity: 0.85,
      depthWrite: false,
    })
  );
  ring.rotation.x = -Math.PI / 2;
  ring.position.y = 0.09;
  ring.renderOrder = 1;
  const g = new THREE.Group();
  g.add(soil, ring);
  g.userData.placedItem = true;
  g.userData.itemKind = 'landPlot';
  g.userData.plotLabel = String(p.label || 'Land lot');
  g.userData.plotId = String(p.id || newLandPlotId());
  g.scale.set(1, 1, 1);
  placeItemOnPlanet(g, Number(p.x) || 0, Number(p.z) || 0, Number(p.ry) || 0);
  return g;
}

function applyTreeBranchesToMapEditorTree(grp, pdata, x, z, skin) {
  const trunk = grp.getObjectByName('trunk');
  if (!trunk || !trunk.isMesh) return;
  const settings = normalizeTreeBranchSettings(pdata?.treeBranches ?? grp.userData?.treeBranches);
  const seedTag = skin === 'dark' ? 1 : skin === 'void' ? 2 : 0;
  const seed = hashTreeBranchSeed(seedTag, x, z);
  applyProceduralTreeBranches(trunk, settings, seed);
}

function createTreeGroup(x, z, logSkin = 'wood') {
  const skin = normalizeMapTreeLogSkin(logSkin);
  const prefKey = skin === 'dark' ? 'treeSpook' : skin === 'void' ? 'treeVoid' : 'tree';
  let pdata = tryGetPrefabDataForKind(prefKey);
  if (!pdata || !Array.isArray(pdata.parts) || pdata.parts.length === 0) {
    pdata = tryGetPrefabDataForKind('tree');
  }
  if (pdata && Array.isArray(pdata.parts) && pdata.parts.length > 0) {
    const grp = deserializePrefabGroup(pdata);
    grp.userData.placedItem = true;
    grp.userData.itemKind = 'tree';
    grp.userData.treeLogSkin = skin;
    grp.userData.fromPrefab = true;
    const pb = pdata.base || { w: 1, h: 1, d: 1 };
    grp.userData.base = { w: pb.w, h: pb.h, d: pb.d };
    applyTreeBranchesToMapEditorTree(grp, pdata, x, z, skin);
    placeItemOnPlanet(grp, x, z, 0);
    return grp;
  }

  const isDark = skin === 'dark';
  const g = new THREE.Group();
  const tw = 0.22;
  const th = 2.05;
  const trunk = new THREE.Mesh(
    new THREE.BoxGeometry(tw, th, tw),
    new THREE.MeshStandardMaterial({
      color: isDark ? 0x0a0a0a : 0x5c4030,
      roughness: isDark ? 1 : 0.88,
      metalness: isDark ? 0 : 0.04,
    })
  );
  trunk.name = 'trunk';
  trunk.position.y = th / 2 + 0.02;
  trunk.castShadow = true;
  trunk.receiveShadow = true;
  g.add(trunk);
  g.userData.placedItem = true;
  g.userData.itemKind = 'tree';
  g.userData.treeLogSkin = skin;
  trunk.userData.base = { w: tw, h: th, d: tw };
  applyTreeBranchesToMapEditorTree(g, null, x, z, skin);
  placeItemOnPlanet(g, x, z, 0);
  return g;
}

function createPropGroup(kind, x, z) {
  if (kind === 'conveyor') {
    const def = TOOL_DEFS.conveyor;
    /** Conveyor builder takes a flat `(x, 0, z)` in its internal coords; we
     *  pass `(x, 0, z)` so the constructor's local frame is trivial, then
     *  project the finished group onto the planet surface ourselves. */
    const grp = buildConveyorFromStorageOrDefault(def.baseW, def.baseH, def.baseD, x, 0, z);
    if (grp) placeItemOnPlanet(grp, x, z, 0);
    return grp;
  }
  if (kind === 'ramp') {
    const def = TOOL_DEFS.ramp;
    const mesh = new THREE.Mesh(
      new THREE.BoxGeometry(def.baseW, def.baseH, def.baseD),
      new THREE.MeshStandardMaterial({
        color: def.color,
        roughness: 0.88,
        metalness: 0.06,
      })
    );
    mesh.castShadow = true;
    mesh.receiveShadow = true;
    mesh.userData.base = { w: def.baseW, h: def.baseH, d: def.baseD };
    mesh.userData.itemKind = 'ramp';
    applyRampMeshTiltScaled(mesh, def, 1, 1, 1);
    const g = new THREE.Group();
    g.add(mesh);
    g.userData.placedItem = true;
    g.userData.itemKind = 'ramp';
    placeItemOnPlanet(g, x, z, 0);
    return g;
  }
  const def = TOOL_DEFS[kind];
  if (!def.baseW) return null;
  const geo = new THREE.BoxGeometry(def.baseW, def.baseH, def.baseD);
  const mesh = new THREE.Mesh(
    geo,
    new THREE.MeshStandardMaterial({
      color: def.color,
      roughness: 0.88,
      metalness: 0.06,
    })
  );
  mesh.castShadow = true;
  mesh.receiveShadow = true;
  mesh.position.y = def.baseH / 2;
  mesh.userData.base = { w: def.baseW, h: def.baseH, d: def.baseD };
  mesh.userData.itemKind = kind;

  const g = new THREE.Group();
  g.add(mesh);
  g.userData.placedItem = true;
  g.userData.itemKind = kind;
  placeItemOnPlanet(g, x, z, 0);
  return g;
}

function placeAt(x, z) {
  let group = null;
  if (activeTool === 'tree') {
    group = createTreeGroup(x, z, activeTreeSkin);
  } else if (activeTool === 'label') {
    let text = window.prompt('Chat text (leave empty for a random line):', '');
    if (text === null) return;
    text = String(text).trim();
    if (!text) text = pickRandom(CHAT_LINES);
    const pal = pickRandom(LABEL_PALETTES);
    group = createLabelGroup(x, z, text, pal.bg, pal.fg);
  } else if (activeTool === 'landPlot') {
    let lab = window.prompt('Lot name (shown when buying from Bob):', 'Land lot');
    if (lab === null) return;
    lab = String(lab).trim() || 'Land lot';
    group = createLandPlotGroup(x, z, lab);
  } else if (TOOL_DEFS[activeTool]) {
    group = createPropGroup(activeTool, x, z);
  }
  if (!group) return;
  pushUndoSnapshot();
  itemsRoot.add(group);
  placed.push(group);
  deselect();
  refreshExport();
  syncList();
}

function onPointerDown(e) {
  if (e.button !== 0) return;

  if (!e.shiftKey) {
    ptrDown.x = e.clientX;
    ptrDown.y = e.clientY;
  }

  const rect = renderer.domElement.getBoundingClientRect();
  pointer.x = ((e.clientX - rect.left) / rect.width) * 2 - 1;
  pointer.y = -((e.clientY - rect.top) / rect.height) * 2 + 1;
  raycaster.setFromCamera(pointer, camera);

  if (e.shiftKey) {
    e.preventDefault();
    /** Raycast the planet sphere instead of a flat plane — the hit's tangent
     *  `(x, z)` is what gets stored, matching the game's projection formula so
     *  the placement is visually identical between editor + runtime. */
    const hitXZ = { x: 0, z: 0 };
    if (!raycastEditorPlanet(raycaster.ray, hitXZ)) return;
    placeAt(snapMapGrid(hitXZ.x), snapMapGrid(hitXZ.z));
  }
}

function onPointerUp(e) {
  if (e.button !== 0) return;
  if (e.shiftKey) return;
  if (skipNextClickSelect) {
    skipNextClickSelect = false;
    return;
  }

  const dx = e.clientX - ptrDown.x;
  const dy = e.clientY - ptrDown.y;
  if (Math.hypot(dx, dy) > 10) return;

  const rect = renderer.domElement.getBoundingClientRect();
  pointer.x = ((e.clientX - rect.left) / rect.width) * 2 - 1;
  pointer.y = -((e.clientY - rect.top) / rect.height) * 2 + 1;
  raycaster.setFromCamera(pointer, camera);

  const ctrl = e.ctrlKey || e.metaKey;
  const pickList = [transformControls, ...itemsRoot.children];
  const hits = raycaster.intersectObjects(pickList, true);
  if (hits.length > 0) {
    if (isDescendantOf(hits[0].object, transformControls)) return;
    const grp = findPlacedGroup(hits[0].object);
    if (grp) {
      if (grp.userData.itemKind === 'conveyor' && ctrl) {
        toggleConveyorSelection(grp);
        return;
      }
      selectObject(grp);
      return;
    }
  }

  const near = pickNearestPlacedGroup(raycaster.ray);
  if (near) {
    if (near.userData.itemKind === 'conveyor' && ctrl) {
      toggleConveyorSelection(near);
      return;
    }
    selectObject(near);
  } else deselect();
}

function copySelectionToBuffer() {
  if (!selected || selected.userData.isSellSpot) return;
  const g = selected;
  const k = g.userData.itemKind;
  /** All copy-buffer entries use tangent-space coords so the pasted item
   *  projects to the sphere surface naturally via {@link placeItemOnPlanet}.
   *  `rx`/`rz` are always 0 because the surface-align quaternion — not raw
   *  world Euler angles — provides the tilt-to-planet part. */
  const { x: tx, z: tz, ry: tyaw } = tangentXZYaw(g);
  const base = { x: tx, y: 0, z: tz, ry: tyaw };
  if (k === 'tree') {
    const logSkin = normalizeMapTreeLogSkin(g.userData.treeLogSkin);
    if (g.userData.fromPrefab && g.userData.base) {
      const b = g.userData.base;
      copyBuffer = {
        kind: 'tree',
        logSkin,
        ...base,
        sx: b.w * g.scale.x,
        sy: b.h * g.scale.y,
        sz: b.d * g.scale.z,
      };
      return;
    }
    const mesh = g.children.find((c) => c.isMesh);
    const b = mesh?.userData?.base;
    if (!mesh || !b) return;
    copyBuffer = {
      kind: 'tree',
      logSkin,
      ...base,
      sx: b.w * mesh.scale.x * g.scale.x,
      sy: b.h * mesh.scale.y * g.scale.y,
      sz: b.d * mesh.scale.z * g.scale.z,
    };
    return;
  }
  if (k === 'label') {
    copyBuffer = {
      kind: 'label',
      ...base,
      text: g.userData.labelText,
      bg: g.userData.labelBg,
      fg: g.userData.labelFg,
      sx: g.scale.x,
      sy: g.scale.y,
    };
    return;
  }
  if (k === 'landPlot') {
    const mesh = g.children.find((c) => c.isMesh && c.userData.isLandPlotSoil);
    const b = mesh?.userData?.base;
    if (!mesh || !b) return;
    copyBuffer = {
      kind: 'landPlot',
      id: g.userData.plotId,
      label: g.userData.plotLabel,
      ...base,
      w: b.w * mesh.scale.x * g.scale.x,
      d: b.d * mesh.scale.z * g.scale.z,
    };
    return;
  }
  if (k === 'conveyor') {
    const sizes = getConveyorWorldSizes(g);
    if (!sizes) return;
    copyBuffer = {
      kind: 'conveyor',
      ...base,
      rx: 0,
      rz: 0,
      sx: sizes.sx,
      sy: sizes.sy,
      sz: sizes.sz,
    };
    return;
  }
  const mesh = g.children.find((c) => c.isMesh);
  const b = mesh?.userData?.base;
  if (!mesh || !b) return;
  copyBuffer = {
    kind: k,
    ...base,
    rx: 0,
    rz: 0,
    sx: b.w * mesh.scale.x * g.scale.x,
    sy: b.h * mesh.scale.y * g.scale.y,
    sz: b.d * mesh.scale.z * g.scale.z,
  };
}

function pasteFromBuffer() {
  if (!copyBuffer) return;
  let o = { ...copyBuffer };
  o.x = (o.x ?? 0) + 2;
  o.z = (o.z ?? 0) + 2;
  let g = null;
  if (o.kind === 'tree') g = createTreeFromExport(o);
  else if (o.kind === 'label') g = createLabelFromExport(o);
  else if (o.kind === 'landPlot') {
    o = { ...o, id: newLandPlotId() };
    g = createLandPlotFromExport(o);
  } else g = createPropFromExport(o);
  if (!g) return;
  pushUndoSnapshot();
  itemsRoot.add(g);
  placed.push(g);
  selectObject(g);
  refreshExport();
  syncList();
}

function onKeyDown(e) {
  const tag = e.target?.tagName;
  const inField = tag === 'INPUT' || tag === 'TEXTAREA' || e.target?.isContentEditable;

  if ((e.ctrlKey || e.metaKey) && e.code === 'KeyZ' && !e.shiftKey) {
    if (inField) return;
    e.preventDefault();
    popUndo();
    return;
  }
  if ((e.ctrlKey || e.metaKey) && e.code === 'KeyC') {
    if (inField) return;
    e.preventDefault();
    copySelectionToBuffer();
    return;
  }
  if ((e.ctrlKey || e.metaKey) && e.code === 'KeyV') {
    if (inField) return;
    e.preventDefault();
    pasteFromBuffer();
    return;
  }

  if (e.code === 'F2' && !inField) {
    if (selected?.userData?.itemKind === 'label') {
      e.preventDefault();
      const cur = String(selected.userData.labelText ?? '');
      const next = window.prompt('Edit chat text:', cur);
      if (next === null) return;
      pushUndoSnapshot();
      updateLabelGroupText(selected, next);
      refreshExport();
      syncList();
    } else if (selected?.userData?.itemKind === 'landPlot') {
      e.preventDefault();
      const cur = String(selected.userData.plotLabel ?? '');
      const next = window.prompt('Edit lot name:', cur);
      if (next === null) return;
      pushUndoSnapshot();
      selected.userData.plotLabel = String(next).trim() || 'Land lot';
      refreshExport();
      syncList();
    }
    return;
  }

  if (e.code === 'Delete' || e.code === 'Backspace') {
    if (inField) return;
    e.preventDefault();
    deleteSelected();
  }
}

function deleteSelected() {
  if (selectedConveyors.size > 1) {
    pushUndoSnapshot();
    for (const g of [...selectedConveyors]) {
      if (g.userData.isSellSpot) continue;
      const i = placed.indexOf(g);
      if (i !== -1) placed.splice(i, 1);
      itemsRoot.remove(g);
      disposeObjectDeep(g);
    }
    deselect();
    refreshExport();
    syncList();
    return;
  }
  if (!selected || selected.userData.isSellSpot) return;
  pushUndoSnapshot();
  const i = placed.indexOf(selected);
  if (i !== -1) placed.splice(i, 1);
  itemsRoot.remove(selected);
  disposeObjectDeep(selected);
  transformControls.detach();
  selected = null;
  selectedConveyors.clear();
  syncConveyorBatchUi();
  refreshExport();
  syncList();
}

function clearAll() {
  pushUndoSnapshot();
  deselect();
  disposeEditorTerrainMesh();
  if (planetMesh) planetMesh.visible = true;
  if (planetHelperGrid) planetHelperGrid.visible = true;
  for (let i = placed.length - 1; i >= 0; i--) {
    const g = placed[i];
    if (g.userData.isSellSpot) continue;
    itemsRoot.remove(g);
    placed.splice(i, 1);
  }
  if (sellSpotRef) {
    const dSell = defaultSellXZ();
    /** Sell-spot reset snaps back to its default tangent position; the
     *  surface-align quaternion is applied inside `placeItemOnPlanet`, so
     *  we don't touch `rotation` manually here (doing so would overwrite the
     *  alignment and leave the marker tipped over on the curve). */
    placeItemOnPlanet(sellSpotRef, dSell.x, dSell.z, 0);
    sellSpotRef.scale.set(1, 1, 1);
  }
  refreshExport();
  syncList();
}

/** Tangent-space `(x, z)` + yaw for a placed item. Prefers the stashed
 *  `userData.mapX/mapZ/yaw` so sphere-projected visual transforms don't
 *  leak into the serialized map (which uses flat tangent coords matching
 *  {@link sphereTangentXZToInnerSurfaceWorld} in `game.js`). Falls back to
 *  raw `position.x/z` + `rotation.y` for rare items that were created
 *  before the planet helpers ran. */
function tangentXZYaw(g) {
  const mx = g.userData?.mapX;
  const mz = g.userData?.mapZ;
  const my = g.userData?.yaw;
  return {
    x: Number.isFinite(mx) ? mx : g.position.x,
    z: Number.isFinite(mz) ? mz : g.position.z,
    ry: Number.isFinite(my) ? my : g.rotation.y,
  };
}

function exportTrees() {
  return placed
    .filter((g) => g.userData.itemKind === 'tree')
    .map((g) => {
      const logSkin = normalizeMapTreeLogSkin(g.userData.treeLogSkin);
      const { x, z, ry } = tangentXZYaw(g);
      const coord = {
        x: Number(x.toFixed(3)),
        /** `y` is always 0 in tangent space — the game treats it as a radial
         *  tweak and defaults to 0. We keep the field for backward compat. */
        y: 0,
        z: Number(z.toFixed(3)),
        ry: Number(ry.toFixed(4)),
      };
      if (g.userData.fromPrefab && g.userData.base) {
        const b = g.userData.base;
        const sx = b.w * g.scale.x;
        const sy = b.h * g.scale.y;
        const sz = b.d * g.scale.z;
        return {
          logSkin,
          ...coord,
          sx: Number(sx.toFixed(3)),
          sy: Number(sy.toFixed(3)),
          sz: Number(sz.toFixed(3)),
        };
      }
      const mesh = g.children.find((c) => c.isMesh);
      const b = mesh?.userData?.base;
      if (!mesh || !b) {
        return {
          logSkin,
          ...coord,
          sx: 0.8,
          sy: 1.2,
          sz: 0.8,
        };
      }
      const sx = b.w * mesh.scale.x * g.scale.x;
      const sy = b.h * mesh.scale.y * g.scale.y;
      const sz = b.d * mesh.scale.z * g.scale.z;
      return {
        logSkin,
        ...coord,
        sx: Number(sx.toFixed(3)),
        sy: Number(sy.toFixed(3)),
        sz: Number(sz.toFixed(3)),
      };
    });
}

function exportPlotSites() {
  return placed
    .filter((g) => g.userData.itemKind === 'landPlot')
    .map((g) => {
      const mesh = g.children.find((c) => c.isMesh && c.userData.isLandPlotSoil);
      if (!mesh?.userData?.base) return null;
      const b = mesh.userData.base;
      const w = b.w * mesh.scale.x * g.scale.x;
      const d = b.d * mesh.scale.z * g.scale.z;
      const { x, z, ry } = tangentXZYaw(g);
      return {
        id: String(g.userData.plotId || newLandPlotId()),
        label: String(g.userData.plotLabel || 'Land lot'),
        x: Number(x.toFixed(3)),
        y: 0,
        z: Number(z.toFixed(3)),
        ry: Number(ry.toFixed(4)),
        w: Number(w.toFixed(3)),
        d: Number(d.toFixed(3)),
      };
    })
    .filter(Boolean);
}

function exportProps() {
  const out = [];
  for (const g of placed) {
    const k = g.userData.itemKind;
    if (k === 'tree' || k === 'sellSpot' || k === 'landPlot') continue;
    const { x, z, ry } = tangentXZYaw(g);
    if (k === 'label') {
      out.push({
        kind: 'label',
        x: Number(x.toFixed(3)),
        y: 0,
        z: Number(z.toFixed(3)),
        ry: Number(ry.toFixed(4)),
        text: String(g.userData.labelText ?? ''),
        bg: g.userData.labelBg,
        fg: g.userData.labelFg,
        sx: Number(g.scale.x.toFixed(4)),
        sy: Number(g.scale.y.toFixed(4)),
      });
      continue;
    }
    if (k === 'conveyor') {
      const sizes = getConveyorWorldSizes(g);
      if (!sizes) continue;
      /** Conveyors only persist `ry` (tangent-space yaw). `rx`/`rz` are
       *  written as 0 because the surface-align quaternion — which the
       *  game re-derives at load — handles tilt-onto-surface automatically.
       *  Keeping the fields in the schema so world files stay identical
       *  structurally, just zeroed. */
      out.push({
        kind: 'conveyor',
        x: Number(x.toFixed(3)),
        y: 0,
        z: Number(z.toFixed(3)),
        rx: 0,
        ry: Number(ry.toFixed(4)),
        rz: 0,
        sx: Number(sizes.sx.toFixed(3)),
        sy: Number(sizes.sy.toFixed(3)),
        sz: Number(sizes.sz.toFixed(3)),
      });
      continue;
    }
    const mesh = g.children.find((c) => c.isMesh);
    if (!mesh || !mesh.userData.base) continue;
    const b = mesh.userData.base;
    const s = mesh.scale;
    out.push({
      kind: k,
      x: Number(x.toFixed(3)),
      y: 0,
      z: Number(z.toFixed(3)),
      rx: 0,
      ry: Number(ry.toFixed(4)),
      rz: 0,
      sx: Number((b.w * s.x * g.scale.x).toFixed(3)),
      sy: Number((b.h * s.y * g.scale.y).toFixed(3)),
      sz: Number((b.d * s.z * g.scale.z).toFixed(3)),
    });
  }
  return out;
}

function exportSellSpot() {
  if (!sellSpotRef) {
    const d = defaultSellXZ();
    return { x: d.x, z: d.z, r: 3.5 };
  }
  const base = sellSpotRef.userData.sellRadius || 3.5;
  const r = base * Math.max(sellSpotRef.scale.x, sellSpotRef.scale.z);
  const { x, z } = tangentXZYaw(sellSpotRef);
  return {
    x: Number(x.toFixed(3)),
    z: Number(z.toFixed(3)),
    r: Number(Math.max(0.6, Math.min(40, r)).toFixed(2)),
  };
}

function exportTerrain() {
  if (!editorTerrainData?.heights?.length) return null;
  return {
    version: editorTerrainData.version ?? 1,
    size: editorTerrainData.size,
    segments: editorTerrainData.segments,
    color: editorTerrainData.color ?? 0x3d6b3d,
    seed: editorTerrainData.seed ?? 0,
    heights: editorTerrainData.heights,
  };
}

function refreshExport() {
  syncSellSpotScaleToRadius();
  const trees = exportTrees();
  const props = exportProps();
  const plotSites = exportPlotSites();
  const sellSpot = exportSellSpot();
  const terrain = exportTerrain();
  const slice = { trees, props, plotSites, sellSpot, terrain };

  const base = mapEditorDocument;
  const planetId = base?.defaultPlanetId || 'home';
  let doc;
  if (base && Array.isArray(base.worlds) && base.worlds.length > 0) {
    const worlds = base.worlds.map((w) => {
      if (!w || w.planetId !== planetId) return { ...w };
      return {
        planetId,
        label: typeof w.label === 'string' ? w.label : 'Home',
        sphereRadius: typeof w.sphereRadius === 'number' && w.sphereRadius > 0 ? w.sphereRadius : 2500,
        ...slice,
        useSpherePhysics: !!(w && w.useSpherePhysics),
        worldDef: w && typeof w.worldDef === 'object' && w.worldDef ? w.worldDef : {},
        universePose: normalizeUniversePose(w?.universePose),
      };
    });
    if (!worlds.some((w) => w && w.planetId === planetId)) {
      worlds.push({
        planetId,
        label: 'Home',
        sphereRadius: 2500,
        ...slice,
        useSpherePhysics: false,
        worldDef: {},
        universePose: normalizeUniversePose(null),
      });
    }
    doc = { mapFormatVersion: MAP_DOCUMENT_VERSION, defaultPlanetId: planetId, worlds };
  } else {
    doc = {
      mapFormatVersion: MAP_DOCUMENT_VERSION,
      defaultPlanetId: 'home',
      worlds: [{ planetId: 'home', label: 'Home', sphereRadius: 2500, useSpherePhysics: false, ...slice }],
    };
  }
  mapEditorDocument = doc;
  const text = JSON.stringify(doc, null, 2);
  const outEl = document.getElementById('me-output');
  if (outEl) outEl.value = text;
  scheduleAutosave();
}

function syncList() {
  const listEl = document.getElementById('me-list');
  if (!listEl) return;
  listEl.innerHTML = `<ul style="margin:0;padding-left:18px;">${placed
    .map((g, i) => {
      const k = g.userData.itemKind;
      let extra = '';
      if (k === 'sellSpot') extra = ' <span style="color:#6c8">(sell zone)</span>';
      else if (k === 'label') {
        const t = String(g.userData.labelText ?? '')
          .replace(/&/g, '&amp;')
          .replace(/</g, '&lt;')
          .replace(/"/g, '&quot;');
        const short = t.length > 28 ? `${t.slice(0, 28)}…` : t;
        extra = ` <span style="opacity:0.85">“${short}”</span>`;
      }       else if (k === 'landPlot') {
        const t = String(g.userData.plotLabel ?? '').replace(/</g, '&lt;');
        extra = ` <span style="color:#c9a227">(${t})</span>`;
      } else if (k === 'tree') {
        const sk =
          g.userData.treeLogSkin === 'dark'
            ? 'neon orange'
            : g.userData.treeLogSkin === 'void'
              ? 'purple void'
              : 'classic';
        extra = ` <span style="color:#9aa0a6">${sk}</span>`;
      } else if (k === 'conveyor') {
        const sz = getConveyorWorldSizes(g);
        extra = sz
          ? ` <span style="color:#9aa0a6">${sz.sx.toFixed(1)}×${sz.sz.toFixed(1)}m</span>`
          : '';
      }
      /** Display the tangent-space `(x, z)` — matches what gets saved and
       *  what the game projects, rather than the sphere-world position which
       *  would read as e.g. `(3.2, 0.02)` at the equator because of the
       *  curvature. */
      const t = tangentXZYaw(g);
      return `<li>#${i + 1} <strong>${k}</strong>${extra} @ (${t.x.toFixed(1)}, ${t.z.toFixed(1)})</li>`;
    })
    .join('')}</ul>`;
}

function onResize() {
  camera.aspect = window.innerWidth / window.innerHeight;
  camera.updateProjectionMatrix();
  renderer.setSize(window.innerWidth, window.innerHeight);
}

let beltSpinT = 0;
function animate() {
  requestAnimationFrame(animate);
  beltSpinT += 0.05;
  controls.update();
  for (const g of placed) {
    const ah = g.userData.conveyorArrow;
    if (!ah?.cone) continue;
    ah.cone.rotation.x = beltSpinT * 0.12;
  }
  renderer.render(scene, camera);
}

init();
