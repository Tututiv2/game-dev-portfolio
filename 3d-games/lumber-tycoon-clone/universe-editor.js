/**
 * Universe editor: 3D overview of all `worlds[]` planets, hierarchy, TransformControls for `universePose`.
 */
import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { TransformControls } from 'three/addons/controls/TransformControls.js';
import {
  normalizeMapDocument,
  normalizeUniversePose,
  MAP_DOCUMENT_VERSION,
  DEFAULT_BIOMES,
} from './map-world-format.js';
import { listLt2Presets } from './lt2-tree-config.js';

const STORAGE_KEY = 'LT2_MapEditor';

/** @type {ReturnType<typeof normalizeMapDocument>} */
let doc = null;
const planetRoots = new Map();
let selectedPlanetId = null;

/**
 * Per-planet visual proxy parts so we can mutate radius / atmosphere / surface tint live
 * without rebuilding the whole proxy tree. Keyed by `planetId`.
 * @type {Map<string, { surface: THREE.Mesh, atmosphere: THREE.Mesh | null, biomeBands: THREE.Mesh | null, gravityWell: THREE.Group | null }>}
 */
const planetProxyParts = new Map();

/**
 * Per-planet gravity-well visibility state. User toggles these via checkboxes in the left panel.
 * Key: planetId → boolean (default: false, hidden).
 * @type {Map<string, boolean>}
 */
const gravityWellVisibility = new Map();

let scene, camera, renderer, orbit, transform;
const tmpBox = new THREE.Box3();
const tmpVec = new THREE.Vector3();

function setStatus(t) {
  const el = document.getElementById('ue-status');
  if (el) el.textContent = t || '';
}

function loadDoc() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) doc = normalizeMapDocument({});
    else doc = normalizeMapDocument(JSON.parse(raw));
  } catch {
    doc = normalizeMapDocument({});
  }
}

function persist() {
  for (const [id, root] of planetRoots) {
    const w = doc.worlds.find((x) => x && x.planetId === id);
    if (!w) continue;
    w.universePose = {
      x: root.position.x,
      y: root.position.y,
      z: root.position.z,
      rx: root.rotation.x,
      ry: root.rotation.y,
      rz: root.rotation.z,
      sx: root.scale.x,
      sy: root.scale.y,
      sz: root.scale.z,
    };
  }
  doc.mapFormatVersion = MAP_DOCUMENT_VERSION;
  localStorage.setItem(STORAGE_KEY, JSON.stringify(doc));
  setStatus('Saved to ' + STORAGE_KEY);
}

function makeLabelSprite(text) {
  const c = document.createElement('canvas');
  const ctx = c.getContext('2d');
  c.width = 512;
  c.height = 128;
  ctx.fillStyle = 'rgba(0,0,0,0.55)';
  ctx.fillRect(0, 0, c.width, c.height);
  ctx.strokeStyle = 'rgba(255,255,255,0.35)';
  ctx.strokeRect(2, 2, c.width - 4, c.height - 4);
  ctx.fillStyle = '#e8eaed';
  ctx.font = 'bold 36px system-ui,sans-serif';
  ctx.fillText(text.slice(0, 28), 16, 52);
  ctx.font = '22px system-ui,sans-serif';
  ctx.fillStyle = '#9aa0a6';
  const sub = text.length > 40 ? `${text.slice(0, 40)}…` : text;
  ctx.fillText(sub, 16, 92);
  const tex = new THREE.CanvasTexture(c);
  tex.colorSpace = THREE.SRGBColorSpace;
  const mat = new THREE.SpriteMaterial({
    map: tex,
    transparent: true,
    depthTest: false,
    depthWrite: false,
    sizeAttenuation: true,
  });
  const sp = new THREE.Sprite(mat);
  sp.scale.set(56, 14, 1);
  sp.center.set(0.5, 0);
  sp.position.set(0, 28, 0);
  return sp;
}

/**
 * Map a real planet radius (m) into the universe view's units. We now render at **1:1 scale**
 * with positions so the user can visually confirm that two planets don't share a gravity well —
 * if the spheres overlap here, they overlap in-game. Legacy behavior scaled radii down by ~0.018
 * which made planets *look* separated even when they were physically inside each other; that was
 * the root cause of "xenon sucks me off Home even though they looked far apart in the editor".
 *
 * Tiny planets (< 50 m) still get a floor of 50 so they stay clickable in the editor.
 */
function previewRadiusForSphere(realRadius) {
  return Math.max(50, realRadius || 2500);
}

function createPlanetProxy(w) {
  const id = w.planetId;
  const group = new THREE.Group();
  group.name = `UniversePlanet_${id}`;
  group.userData.planetId = id;

  const pose = normalizeUniversePose(w.universePose);
  group.position.set(pose.x, pose.y, pose.z);
  group.rotation.set(pose.rx, pose.ry, pose.rz);
  group.scale.set(pose.sx, pose.sy, pose.sz);

  /** @type {THREE.Mesh} */
  let surface;
  /** @type {THREE.Mesh | null} */
  let atmosphere = null;
  /** @type {THREE.Mesh | null} */
  let biomeBands = null;
  /** @type {THREE.Group | null} */
  let gravityWell = null;

  if (w.useSpherePhysics) {
    const R = previewRadiusForSphere(w.sphereRadius);
    const geo = new THREE.SphereGeometry(R, 32, 24);
    const mat = new THREE.MeshStandardMaterial({
      color: new THREE.Color(w.surfaceColor || '#3d6b4a'),
      roughness: 0.78,
      metalness: 0.04,
    });
    surface = new THREE.Mesh(geo, mat);
    surface.castShadow = true;
    surface.receiveShadow = true;

    /** Translucent atmosphere shell (additive-ish) painted just above the surface. */
    if (w.atmosphere?.enabled) {
      atmosphere = createAtmosphereShell(R, w.atmosphere);
      group.add(atmosphere);
    }

    /** Biome lat-band overlay: a slightly inflated sphere with vertex colors per latitude band. */
    biomeBands = createBiomeBandsOverlay(R, w.biomes);
    if (biomeBands) group.add(biomeBands);

    /** Gravity well visualization (hidden by default; user toggles via left-panel checkbox). */
    gravityWell = createGravityWellVisual(R, w);
    gravityWell.visible = gravityWellVisibility.get(id) === true;
    group.add(gravityWell);
  } else {
    const geo = new THREE.CylinderGeometry(42, 42, 1.2, 48, 1, false);
    const mat = new THREE.MeshStandardMaterial({
      color: new THREE.Color(w.surfaceColor || '#3d6b4a'),
      roughness: 0.85,
      metalness: 0.04,
    });
    surface = new THREE.Mesh(geo, mat);
    surface.receiveShadow = true;
  }
  surface.name = 'PlanetProxy';
  group.add(surface);

  const label = makeLabelSprite(`${w.label || id}\n${id}`);
  /** Lift the label above the planet by a fraction of its radius so it doesn't clip into the surface. */
  const labelLift = (w.useSpherePhysics ? previewRadiusForSphere(w.sphereRadius) : 42) * 1.2;
  label.position.set(0, labelLift, 0);
  label.scale.set(labelLift * 1.1, labelLift * 0.28, 1);
  group.add(label);

  /** Axis helper scaled to the planet so it's actually visible next to a 2.5 km sphere. */
  const axisLen = (w.useSpherePhysics ? previewRadiusForSphere(w.sphereRadius) : 42) * 0.3;
  const axis = new THREE.AxesHelper(axisLen);
  group.add(axis);

  planetProxyParts.set(id, { surface, atmosphere, biomeBands, gravityWell });
  return group;
}

/**
 * Build a multi-shell visualization of a planet's gravity influence:
 *   1. Wireframe sphere at the physical surface radius — "inside this = you collide with planet".
 *   2. Translucent outer shell at ~2× surface radius — "within this, this planet usually wins
 *      the closest-surface gravity picker, since another planet would need to have its surface
 *      even nearer to override".
 *   3. Axis cone pointing toward center showing "pull direction" anywhere inside the shell.
 *
 * The shell is tinted by gravity strength: stronger gravity → hotter color. Returned as a
 * single `THREE.Group` so the caller can toggle visibility with one `.visible` assignment.
 */
function createGravityWellVisual(surfaceR, w) {
  const group = new THREE.Group();
  group.name = 'PlanetGravityWell';
  group.userData.__lt2GravityWell = true;

  /** Color scale: map gravity 0..40 m/s² → green → yellow → red. */
  const gStrength = typeof w.gravityStrength === 'number' && w.gravityStrength > 0 ? w.gravityStrength : 24;
  const gNorm = Math.max(0, Math.min(1, gStrength / 40));
  const col = new THREE.Color();
  col.setHSL(0.33 * (1 - gNorm), 0.9, 0.55);

  /** Inner wireframe sphere at the exact physics collider boundary. */
  const inner = new THREE.Mesh(
    new THREE.SphereGeometry(surfaceR * 1.004, 24, 16),
    new THREE.MeshBasicMaterial({
      color: col,
      wireframe: true,
      transparent: true,
      opacity: 0.65,
      depthWrite: false,
    })
  );
  inner.renderOrder = 5;
  group.add(inner);

  /** Outer influence shell at 2× radius: soft translucent volume where this planet's gravity
   *  typically dominates (unless another planet's surface is even closer). */
  const outer = new THREE.Mesh(
    new THREE.SphereGeometry(surfaceR * 2.0, 32, 24),
    new THREE.MeshBasicMaterial({
      color: col,
      transparent: true,
      opacity: 0.09,
      side: THREE.BackSide,
      depthWrite: false,
      blending: THREE.AdditiveBlending,
    })
  );
  outer.renderOrder = 4;
  group.add(outer);

  /** Pull-direction arrows: 6 cone markers placed evenly on the outer shell pointing inward. */
  const arrowDirs = [
    new THREE.Vector3(1, 0, 0), new THREE.Vector3(-1, 0, 0),
    new THREE.Vector3(0, 1, 0), new THREE.Vector3(0, -1, 0),
    new THREE.Vector3(0, 0, 1), new THREE.Vector3(0, 0, -1),
  ];
  for (const dir of arrowDirs) {
    const cone = new THREE.Mesh(
      new THREE.ConeGeometry(surfaceR * 0.08, surfaceR * 0.22, 10),
      new THREE.MeshBasicMaterial({ color: col, transparent: true, opacity: 0.75, depthWrite: false })
    );
    /** Place at 1.4× surface radius along dir, point toward center (cone tip is at +Y by default). */
    cone.position.copy(dir).multiplyScalar(surfaceR * 1.4);
    const up = new THREE.Vector3(0, 1, 0);
    const target = dir.clone().multiplyScalar(-1);
    cone.quaternion.setFromUnitVectors(up, target);
    cone.renderOrder = 6;
    group.add(cone);
  }

  return group;
}

/** Sphere shell with inward-facing back-side rendering for a soft halo look. */
function createAtmosphereShell(surfaceR, atm) {
  const thicknessFrac = Math.min(0.4, Math.max(0.02, (atm.thickness || 350) / Math.max(50, surfaceR * 50)));
  const R = surfaceR * (1 + thicknessFrac);
  const geo = new THREE.SphereGeometry(R, 32, 24);
  const mat = new THREE.MeshBasicMaterial({
    color: new THREE.Color(atm.color || '#9ec7ff'),
    transparent: true,
    opacity: 0.18,
    side: THREE.BackSide,
    depthWrite: false,
  });
  const mesh = new THREE.Mesh(geo, mat);
  mesh.name = 'PlanetAtmosphere';
  mesh.userData.__lt2AtmShell = true;
  return mesh;
}

/**
 * Slightly inflated sphere whose vertex colors come from the biome the vertex's latitude
 * belongs to. Lets the universe view show "where the snow / forest / beach is" at a glance.
 */
function createBiomeBandsOverlay(surfaceR, biomes) {
  if (!Array.isArray(biomes) || biomes.length === 0) return null;
  const R = surfaceR * 1.002;
  const geo = new THREE.SphereGeometry(R, 48, 32);
  const pos = geo.attributes.position;
  const colors = new Float32Array(pos.count * 3);
  const c = new THREE.Color();
  const fallback = new THREE.Color('#3d6b4a');
  for (let i = 0; i < pos.count; i++) {
    const y = pos.getY(i);
    const lenInv = 1 / Math.max(1e-6, Math.hypot(pos.getX(i), y, pos.getZ(i)));
    const sinLat = y * lenInv;
    const latDeg = Math.asin(Math.max(-1, Math.min(1, sinLat))) * (180 / Math.PI);
    let chosen = null;
    for (const b of biomes) {
      if (latDeg >= b.latMin && latDeg <= b.latMax) {
        chosen = b;
        break;
      }
    }
    if (chosen) c.set(chosen.color);
    else c.copy(fallback);
    colors[i * 3] = c.r;
    colors[i * 3 + 1] = c.g;
    colors[i * 3 + 2] = c.b;
  }
  geo.setAttribute('color', new THREE.BufferAttribute(colors, 3));
  const mat = new THREE.MeshBasicMaterial({
    vertexColors: true,
    transparent: true,
    opacity: 0.55,
    depthWrite: false,
  });
  const mesh = new THREE.Mesh(geo, mat);
  mesh.name = 'PlanetBiomeBands';
  mesh.userData.__lt2BiomeBands = true;
  return mesh;
}

function rebuildPlanets() {
  for (const g of planetRoots.values()) {
    transform.detach();
    scene.remove(g);
    disposePlanetProxy(g);
  }
  planetRoots.clear();
  planetProxyParts.clear();

  for (const w of doc.worlds || []) {
    if (!w?.planetId) continue;
    const g = createPlanetProxy(w);
    scene.add(g);
    planetRoots.set(w.planetId, g);
  }
  buildHierarchy();
  renderGravityWellCheckboxes();
  refreshOverlapWarnings();
  frameAll();
}

/**
 * Detect sphere-physics planets whose colliders overlap and either tint their gravity wells red
 * or show a banner warning. Overlapping planets are the #1 cause of "I'm on Home but gravity
 * yanks me into the sky" — the physics engine treats them as two nested spheres which the solver
 * can't cleanly resolve.
 */
function refreshOverlapWarnings() {
  const banner = document.getElementById('ue-overlap-banner');
  const spheres = (doc.worlds || []).filter((w) => w?.planetId && w.useSpherePhysics);
  /** @type {Set<string>} */
  const overlapping = new Set();
  /** @type {Array<[string,string,number]>} */
  const pairs = [];
  for (let i = 0; i < spheres.length; i++) {
    for (let j = i + 1; j < spheres.length; j++) {
      const a = spheres[i];
      const b = spheres[j];
      const poseA = a.universePose || { x: 0, y: 0, z: 0 };
      const poseB = b.universePose || { x: 0, y: 0, z: 0 };
      const dx = poseA.x - poseB.x;
      const dy = poseA.y - poseB.y;
      const dz = poseA.z - poseB.z;
      const dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
      const sumR = (a.sphereRadius || 2500) + (b.sphereRadius || 2500);
      if (dist < sumR) {
        overlapping.add(a.planetId);
        overlapping.add(b.planetId);
        pairs.push([a.label || a.planetId, b.label || b.planetId, sumR - dist]);
      }
    }
  }
  /** Tint each gravity well based on overlap state (only if already built). */
  for (const [id, parts] of planetProxyParts.entries()) {
    if (!parts.gravityWell) continue;
    const bad = overlapping.has(id);
    parts.gravityWell.traverse((o) => {
      if ((o.isMesh || o.isPoints) && o.material?.color) {
        if (bad) {
          /** Overlapping → flash red so it's impossible to miss. */
          if (!o.userData.__lt2OrigColor) o.userData.__lt2OrigColor = o.material.color.clone();
          o.material.color.setHex(0xff3030);
        } else if (o.userData.__lt2OrigColor) {
          o.material.color.copy(o.userData.__lt2OrigColor);
          o.userData.__lt2OrigColor = null;
        }
      }
    });
  }
  if (banner) {
    if (pairs.length) {
      const items = pairs
        .map(([a, b, overlap]) => `<li><strong>${escapeHtml(a)}</strong> ↔ <strong>${escapeHtml(b)}</strong> (overlap: ${overlap.toFixed(0)} m)</li>`)
        .join('');
      banner.innerHTML = `<strong>⚠ Planet colliders overlap</strong> — move these planets farther apart or shrink their radius, otherwise the in-game gravity picker will flip-flop and yank the player off one planet toward the other.<ul style="margin:4px 0 0 16px;">${items}</ul>`;
      banner.style.display = 'block';
    } else {
      banner.innerHTML = '';
      banner.style.display = 'none';
    }
  }
}

/**
 * (Re)render the gravity-well checkbox list in the left panel. One checkbox per planet that
 * has sphere physics; clicking it toggles the corresponding visual group's `.visible` flag.
 * Flat (non-sphere) planets are listed as disabled entries so the user sees why they have no well.
 */
function renderGravityWellCheckboxes() {
  const host = document.getElementById('ue-gravity-wells');
  if (!host) return;
  const parts = [];
  for (const w of doc.worlds || []) {
    if (!w?.planetId) continue;
    const id = w.planetId;
    const visible = gravityWellVisibility.get(id) === true;
    const hasSphere = !!w.useSpherePhysics;
    const g = typeof w.gravityStrength === 'number' ? w.gravityStrength : 24;
    if (hasSphere) {
      parts.push(`<label style="display:flex;align-items:center;gap:6px;cursor:pointer;"><input type="checkbox" data-well-id="${escapeAttr(id)}" ${visible ? 'checked' : ''}/> <span><strong>${escapeHtml(w.label || id)}</strong> <span style="opacity:0.6">(g=${g})</span></span></label>`);
    } else {
      parts.push(`<label style="display:flex;align-items:center;gap:6px;opacity:0.4;" title="Flat worlds have no gravity well"><input type="checkbox" disabled/> <span>${escapeHtml(w.label || id)} <span style="opacity:0.6">(flat)</span></span></label>`);
    }
  }
  host.innerHTML = parts.join('');
  host.querySelectorAll('input[type=checkbox][data-well-id]').forEach((input) => {
    input.addEventListener('change', () => {
      const id = input.getAttribute('data-well-id');
      if (!id) return;
      const on = !!input.checked;
      gravityWellVisibility.set(id, on);
      const parts2 = planetProxyParts.get(id);
      if (parts2?.gravityWell) parts2.gravityWell.visible = on;
    });
  });
}

function buildHierarchy() {
  const el = document.getElementById('ue-tree');
  if (!el) return;
  const parts = [];
  parts.push(`<div class="node${selectedPlanetId == null ? ' sel' : ''}" data-id="">Universe</div>`);
  for (const w of doc.worlds || []) {
    if (!w?.planetId) continue;
    const id = w.planetId;
    const sel = id === selectedPlanetId ? ' sel' : '';
    const trees = Array.isArray(w.trees) ? w.trees.length : w.trees === undefined ? '…' : 0;
    const props = Array.isArray(w.props) ? w.props.length : 0;
    const plots = Array.isArray(w.plotSites) ? w.plotSites.length : 0;
    parts.push(`<div class="node${sel}" data-id="${escapeAttr(id)}"><strong>${escapeHtml(id)}</strong> <span style="opacity:0.75">${escapeHtml(w.label || '')}</span></div>`);
    parts.push(`<div class="ch">Terrain: ${w.terrain ? 'yes' : 'no'}</div>`);
    parts.push(`<div class="ch">Trees: ${trees} · Props: ${props} · Plots: ${plots}</div>`);
    parts.push(`<div class="ch">Sphere: ${w.useSpherePhysics ? 'yes' : 'flat'}</div>`);
  }
  el.innerHTML = parts.join('');
  el.querySelectorAll('.node').forEach((n) => {
    n.addEventListener('click', (e) => {
      e.stopPropagation();
      const id = n.getAttribute('data-id') || '';
      selectPlanet(id || null);
    });
  });
}

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/"/g, '&quot;');
}

function escapeAttr(s) {
  return escapeHtml(s).replace(/'/g, '&#39;');
}

function disposePlanetProxy(group) {
  group.traverse((o) => {
    if (o.isMesh) {
      o.geometry?.dispose?.();
      const m = o.material;
      if (Array.isArray(m)) m.forEach((x) => x?.dispose?.());
      else m?.dispose?.();
    }
    if (o.isSprite) {
      o.material?.map?.dispose?.();
      o.material?.dispose?.();
    }
  });
}

/**
 * Live-update the in-scene proxy for a planet without rebuilding the whole tree.
 * Used by the right-side properties panel so radius / color / atmosphere edits feel instant.
 */
function refreshPlanetVisualsForId(planetId) {
  const w = doc.worlds.find((x) => x && x.planetId === planetId);
  const root = planetRoots.get(planetId);
  if (!w || !root) return;
  const parts = planetProxyParts.get(planetId);
  if (!parts) return;

  if (w.useSpherePhysics) {
    const R = previewRadiusForSphere(w.sphereRadius);

    if (parts.surface && parts.surface.geometry?.type === 'SphereGeometry') {
      const oldR = parts.surface.geometry.parameters.radius;
      if (Math.abs(oldR - R) > 0.001) {
        parts.surface.geometry.dispose();
        parts.surface.geometry = new THREE.SphereGeometry(R, 32, 24);
      }
    }
    if (parts.surface?.material) {
      parts.surface.material.color.set(w.surfaceColor || '#3d6b4a');
      parts.surface.material.needsUpdate = true;
    }

    /** Atmosphere shell: rebuild from scratch if enabled-state or geometry-determining params changed. */
    if (parts.atmosphere) {
      root.remove(parts.atmosphere);
      parts.atmosphere.geometry?.dispose?.();
      parts.atmosphere.material?.dispose?.();
      parts.atmosphere = null;
    }
    if (w.atmosphere?.enabled) {
      parts.atmosphere = createAtmosphereShell(R, w.atmosphere);
      root.add(parts.atmosphere);
    }

    if (parts.biomeBands) {
      root.remove(parts.biomeBands);
      parts.biomeBands.geometry?.dispose?.();
      parts.biomeBands.material?.dispose?.();
      parts.biomeBands = null;
    }
    parts.biomeBands = createBiomeBandsOverlay(R, w.biomes);
    if (parts.biomeBands) root.add(parts.biomeBands);

    /** Gravity well: rebuild so radius / gravity-strength tint stay in sync with the panel. */
    if (parts.gravityWell) {
      root.remove(parts.gravityWell);
      parts.gravityWell.traverse((o) => {
        if (o.isMesh) {
          o.geometry?.dispose?.();
          o.material?.dispose?.();
        }
      });
      parts.gravityWell = null;
    }
    parts.gravityWell = createGravityWellVisual(R, w);
    parts.gravityWell.visible = gravityWellVisibility.get(planetId) === true;
    root.add(parts.gravityWell);
    /** Keep the checkbox list label (e.g. g value) in sync. */
    renderGravityWellCheckboxes();
  } else {
    if (parts.surface?.material) {
      parts.surface.material.color.set(w.surfaceColor || '#3d6b4a');
    }
    /** A flat-world toggle might have removed the well; refresh the list anyway. */
    renderGravityWellCheckboxes();
  }
}

function selectPlanet(id) {
  selectedPlanetId = id;
  transform.detach();
  if (id && planetRoots.has(id)) {
    transform.attach(planetRoots.get(id));
  }
  buildHierarchy();
  renderPlanetPanel();
}

function frameAll() {
  tmpBox.makeEmpty();
  for (const g of planetRoots.values()) {
    g.updateMatrixWorld(true);
    tmpBox.expandByObject(g);
  }
  if (tmpBox.isEmpty()) {
    camera.position.set(180, 120, 220);
    orbit.target.set(0, 0, 0);
    orbit.update();
    return;
  }
  tmpBox.getCenter(orbit.target);
  const size = tmpBox.getSize(tmpVec).length();
  const dist = Math.max(120, size * 1.8);
  camera.position.copy(orbit.target).add(new THREE.Vector3(dist * 0.65, dist * 0.45, dist * 0.7));
  orbit.update();
}

function initThree() {
  scene = new THREE.Scene();
  scene.background = new THREE.Color(0x06080e);

  camera = new THREE.PerspectiveCamera(55, window.innerWidth / window.innerHeight, 0.5, 500000);
  camera.position.set(220, 140, 280);

  renderer = new THREE.WebGLRenderer({ antialias: true });
  renderer.setSize(window.innerWidth, window.innerHeight);
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
  renderer.shadowMap.enabled = true;
  document.body.appendChild(renderer.domElement);

  orbit = new OrbitControls(camera, renderer.domElement);
  orbit.enableDamping = true;
  orbit.dampingFactor = 0.06;

  transform = new TransformControls(camera, renderer.domElement);
  transform.addEventListener('dragging-changed', (ev) => {
    orbit.enabled = !ev.value;
  });
  transform.addEventListener('change', () => {
    /** Re-run overlap checks live while dragging so the banner / red-tinted wells react
     *  to mid-drag positions. Cheap: just a few vector ops per planet pair. */
    if (transform.object) {
      /** Mirror the dragged group's current transform back into `doc.worlds[...]` so overlap math
       *  sees fresh positions without waiting for `persist()`. */
      const id = transform.object.userData?.planetId;
      if (id) {
        const w = doc.worlds.find((x) => x && x.planetId === id);
        if (w) {
          w.universePose = {
            x: transform.object.position.x,
            y: transform.object.position.y,
            z: transform.object.position.z,
            rx: transform.object.rotation.x,
            ry: transform.object.rotation.y,
            rz: transform.object.rotation.z,
            sx: transform.object.scale.x,
            sy: transform.object.scale.y,
            sz: transform.object.scale.z,
          };
        }
      }
      refreshOverlapWarnings();
    }
  });
  scene.add(transform);

  scene.add(new THREE.AmbientLight(0x8a9ab0, 0.55));
  const sun = new THREE.DirectionalLight(0xffffff, 0.85);
  sun.position.set(120, 200, 80);
  sun.castShadow = true;
  scene.add(sun);

  /** Bigger universe grid so it spans the now-1:1-scale planets (a 2.5 km radius planet used to
   *  fit comfortably in a 4 km grid when radii were scaled; at real scale we need kilometers). */
  const grid = new THREE.GridHelper(100000, 100, 0x334155, 0x1e293b);
  scene.add(grid);

  window.addEventListener('resize', () => {
    camera.aspect = window.innerWidth / window.innerHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(window.innerWidth, window.innerHeight);
  });
}

function pickPlanetFromPointer(ev) {
  const rect = renderer.domElement.getBoundingClientRect();
  const x = ((ev.clientX - rect.left) / rect.width) * 2 - 1;
  const y = -((ev.clientY - rect.top) / rect.height) * 2 + 1;
  const raycaster = new THREE.Raycaster();
  raycaster.setFromCamera(new THREE.Vector2(x, y), camera);
  const hits = raycaster.intersectObjects([...planetRoots.values()], true);
  if (!hits.length) return null;
  let o = hits[0].object;
  while (o && !o.userData.planetId) o = o.parent;
  return o?.userData?.planetId ?? null;
}

function animate() {
  requestAnimationFrame(animate);
  orbit.update();
  renderer.render(scene, camera);
}

/**
 * Right-side "Planet properties" panel: shows the selected planet's gravity, atmosphere,
 * biomes, and tree-spawn settings. Inputs write back to `doc.worlds[i]`, autosave to
 * localStorage, and call {@link refreshPlanetVisualsForId} so the 3D proxy updates live.
 */
function renderPlanetPanel() {
  const panel = document.getElementById('ue-planet-panel');
  const empty = document.getElementById('ue-pp-empty');
  const content = document.getElementById('ue-pp-content');
  const title = document.getElementById('ue-pp-title');
  if (!panel || !empty || !content || !title) return;

  if (!selectedPlanetId) {
    panel.classList.remove('open');
    return;
  }
  const w = doc.worlds.find((x) => x && x.planetId === selectedPlanetId);
  if (!w) {
    panel.classList.remove('open');
    return;
  }

  panel.classList.add('open');
  empty.style.display = 'none';
  content.style.display = 'flex';
  title.textContent = `Planet: ${w.label || w.planetId}`;

  /** Bind a single input in the panel: write `parser(input.value)` back to `w[key]` and refresh. */
  const bind = (id, key, parser) => {
    const el = /** @type {HTMLInputElement} */ (document.getElementById(id));
    if (!el) return;
    const cur = w[key];
    if (el.type === 'checkbox') el.checked = !!cur;
    else if (el.type === 'color') el.value = cur || '#000000';
    else el.value = cur ?? '';
    el.oninput = () => {
      const raw = el.type === 'checkbox' ? el.checked : el.value;
      const parsed = parser ? parser(raw) : raw;
      w[key] = parsed;
      autosaveAndRefresh();
    };
  };

  /** Bind a nested-object field (e.g. `atmosphere.color`). */
  const bindNested = (id, parentKey, key, parser) => {
    const el = /** @type {HTMLInputElement} */ (document.getElementById(id));
    if (!el || !w[parentKey]) return;
    const cur = w[parentKey][key];
    if (el.type === 'checkbox') el.checked = !!cur;
    else if (el.type === 'color') el.value = cur || '#000000';
    else el.value = cur ?? '';
    el.oninput = () => {
      const raw = el.type === 'checkbox' ? el.checked : el.value;
      const parsed = parser ? parser(raw) : raw;
      w[parentKey] = { ...w[parentKey], [key]: parsed };
      autosaveAndRefresh();
    };
  };

  const numFn = (min, max) => (v) => {
    const n = Number(v);
    if (!Number.isFinite(n)) return null;
    return Math.min(max, Math.max(min, n));
  };

  bind('pp-label', 'label');
  /**
   * Toggling sphere physics swaps the proxy geometry kind (sphere ↔ cylinder), so we do a
   * full rebuild instead of trying to mutate in-place. Keep the gizmo on the same planet.
   */
  const sphereInput = /** @type {HTMLInputElement} */ (document.getElementById('pp-sphere'));
  if (sphereInput) {
    sphereInput.checked = !!w.useSpherePhysics;
    sphereInput.oninput = () => {
      w.useSpherePhysics = !!sphereInput.checked;
      const keepId = selectedPlanetId;
      persist();
      rebuildPlanets();
      if (keepId) selectPlanet(keepId);
    };
  }
  bind('pp-radius', 'sphereRadius', numFn(50, 20000000));
  bind('pp-gravity', 'gravityStrength', (v) => {
    if (v === '' || v == null) return null;
    const n = Number(v);
    if (!Number.isFinite(n)) return null;
    return Math.min(60, Math.max(0, n));
  });
  bind('pp-surface', 'surfaceColor');

  bindNested('pp-atm-on', 'atmosphere', 'enabled');
  bindNested('pp-atm-color', 'atmosphere', 'color');
  bindNested('pp-atm-thick', 'atmosphere', 'thickness', numFn(0, 5000));
  bindNested('pp-atm-fog', 'atmosphere', 'fogDensity', numFn(0, 0.05));

  /** Make sure the new `clustering` field has a sensible default before we bind the slider —
   *  older saved worlds predate it and would otherwise show up as "" → HTML range default of 0.5
   *  which is slightly too clumpy for a fresh world. */
  if (w.treeSpawn && typeof w.treeSpawn === 'object' && w.treeSpawn.clustering == null) {
    w.treeSpawn = { ...w.treeSpawn, clustering: 0.25 };
  }
  bindNested('pp-ts-on', 'treeSpawn', 'enabled');
  bindNested('pp-ts-count', 'treeSpawn', 'targetCount', numFn(0, 50000));
  bindNested('pp-ts-seed', 'treeSpawn', 'seed', (v) => {
    const n = Number(v);
    return Number.isFinite(n) ? Math.max(0, n | 0) : 0;
  });
  bindNested('pp-ts-cluster', 'treeSpawn', 'clustering', numFn(0, 1));
  /** Keep the "0.25" readout next to the clustering slider in sync while the user drags. Fires
   *  on `input` so the label updates live, separate from the debounced autosave `bindNested`
   *  triggers on `change`. */
  const clusterSlider = document.getElementById('pp-ts-cluster');
  const clusterVal = document.getElementById('pp-ts-cluster-val');
  if (clusterSlider && clusterVal) {
    const syncLabel = () => {
      const n = Number(clusterSlider.value);
      clusterVal.textContent = Number.isFinite(n) ? n.toFixed(2) : '0.00';
    };
    clusterSlider.addEventListener('input', syncLabel);
    syncLabel();
  }

  renderBiomesList(w);

  const addBtn = document.getElementById('ue-biome-add');
  if (addBtn) {
    addBtn.onclick = () => {
      /** Default to `['default']` preset so a freshly added biome immediately spawns something
       *  in-game — an empty list silently skips all slots which is a confusing first-use UX. */
      const next = {
        id: `biome_${(w.biomes?.length || 0) + 1}`,
        label: 'New biome',
        latMin: -10,
        latMax: 10,
        color: '#5fa86b',
        presetIds: ['default'],
        density: 1,
      };
      w.biomes = [...(w.biomes || []), next];
      autosaveAndRefresh();
      renderBiomesList(w);
    };
  }
}

let lt2PresetsCache = null;
function getLt2Presets() {
  if (!lt2PresetsCache) {
    try {
      lt2PresetsCache = listLt2Presets();
    } catch {
      lt2PresetsCache = [];
    }
  }
  return lt2PresetsCache;
}

function renderBiomesList(w) {
  const list = document.getElementById('ue-biomes-list');
  if (!list) return;
  list.innerHTML = '';
  const presets = getLt2Presets();

  (w.biomes || []).forEach((b, idx) => {
    const row = document.createElement('div');
    row.className = 'pp-biome';
    row.innerHTML = `
      <div class="pp-biome-head">
        <input type="text" data-k="label" value="${escapeAttr(b.label || '')}" />
        <input type="color" data-k="color" value="${b.color || '#3d6b4a'}" />
        <button type="button" class="pp-del" data-act="del">×</button>
      </div>
      <div class="pp-row">
        <label>Lat min</label><input type="number" data-k="latMin" min="-90" max="90" step="1" value="${b.latMin}" />
        <label>Lat max</label><input type="number" data-k="latMax" min="-90" max="90" step="1" value="${b.latMax}" />
      </div>
      <div class="pp-row">
        <label>Density × (0–5)</label>
        <input type="number" data-k="density" min="0" max="5" step="0.05" value="${b.density}" />
      </div>
      <div class="pp-presets" data-presets></div>
    `;

    /** Tree-preset palette: chips toggling presence in `presetIds`. */
    const chips = row.querySelector('[data-presets]');
    if (chips) {
      for (const p of presets) {
        const wrap = document.createElement('label');
        const has = b.presetIds.includes(p.id);
        if (has) wrap.classList.add('on');
        wrap.innerHTML = `<input type="checkbox" data-pid="${escapeAttr(p.id)}" ${has ? 'checked' : ''} /> ${escapeHtml(p.name)}`;
        const cb = wrap.querySelector('input');
        cb.addEventListener('change', () => {
          const pid = p.id;
          const set = new Set(b.presetIds);
          if (cb.checked) set.add(pid);
          else set.delete(pid);
          b.presetIds = Array.from(set);
          wrap.classList.toggle('on', cb.checked);
          autosaveAndRefresh();
        });
        chips.appendChild(wrap);
      }
    }

    row.querySelectorAll('input[data-k]').forEach((input) => {
      const k = input.getAttribute('data-k');
      input.addEventListener('input', () => {
        let v = input.value;
        if (input.type === 'number') {
          const n = Number(v);
          if (!Number.isFinite(n)) return;
          v = n;
          if (k === 'latMin' || k === 'latMax') v = Math.min(90, Math.max(-90, n));
          if (k === 'density') v = Math.min(5, Math.max(0, n));
        }
        b[k] = v;
        autosaveAndRefresh();
      });
    });

    row.querySelector('[data-act="del"]').addEventListener('click', () => {
      w.biomes = w.biomes.filter((_, i) => i !== idx);
      autosaveAndRefresh();
      renderBiomesList(w);
    });

    list.appendChild(row);
  });
}

function autosaveAndRefresh() {
  persist();
  if (selectedPlanetId) refreshPlanetVisualsForId(selectedPlanetId);
  /** Radius / position / sphere-toggle edits can create or fix overlaps; rerun detector live. */
  refreshOverlapWarnings();
}

loadDoc();
initThree();
rebuildPlanets();
renderPlanetPanel();

renderer.domElement.addEventListener('pointerdown', (ev) => {
  if (ev.button !== 0) return;
  if (transform.axis) return;
  const id = pickPlanetFromPointer(ev);
  if (id) selectPlanet(id);
});

document.getElementById('ue-mode-t')?.addEventListener('click', () => {
  transform.setMode('translate');
});
document.getElementById('ue-mode-r')?.addEventListener('click', () => {
  transform.setMode('rotate');
});
document.getElementById('ue-mode-s')?.addEventListener('click', () => {
  transform.setMode('scale');
});
document.getElementById('ue-focus')?.addEventListener('click', frameAll);

/** Bulk gravity-well toggles: flip every checkbox + every visual in one click. */
document.getElementById('ue-wells-all')?.addEventListener('click', () => {
  for (const w of doc.worlds || []) {
    if (!w?.planetId || !w.useSpherePhysics) continue;
    gravityWellVisibility.set(w.planetId, true);
    const parts = planetProxyParts.get(w.planetId);
    if (parts?.gravityWell) parts.gravityWell.visible = true;
  }
  renderGravityWellCheckboxes();
});
document.getElementById('ue-wells-none')?.addEventListener('click', () => {
  for (const w of doc.worlds || []) {
    if (!w?.planetId) continue;
    gravityWellVisibility.set(w.planetId, false);
    const parts = planetProxyParts.get(w.planetId);
    if (parts?.gravityWell) parts.gravityWell.visible = false;
  }
  renderGravityWellCheckboxes();
});

document.getElementById('ue-save')?.addEventListener('click', () => {
  persist();
});

document.getElementById('ue-download')?.addEventListener('click', () => {
  persist();
  const blob = new Blob([JSON.stringify(doc, null, 2)], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'lumber-map-universe.json';
  a.click();
  URL.revokeObjectURL(a.href);
});

/**
 * Generate a fresh, unique planet id based on a user-chosen label. Normalizes to
 * [a-z0-9_] and appends an integer if the id already exists in the doc.
 */
function generateUniquePlanetId(desiredLabel) {
  const base = String(desiredLabel || 'planet')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 32) || 'planet';
  const existing = new Set((doc.worlds || []).map((w) => w?.planetId).filter(Boolean));
  if (!existing.has(base)) return base;
  for (let i = 2; i < 1000; i++) {
    const candidate = `${base}_${i}`;
    if (!existing.has(candidate)) return candidate;
  }
  return `${base}_${Date.now()}`;
}

document.getElementById('ue-new-planet')?.addEventListener('click', () => {
  /** Save any live transform changes from the gizmo before rebuilding. */
  persist();
  const label = (prompt('Name of the new planet?', 'New Planet') || '').trim();
  if (!label) return;
  const pid = generateUniquePlanetId(label);
  /**
   * Offset the new planet so its collider doesn't overlap the existing ones. Each planet is
   * 2500 m radius by default, so we put new planets at y = 8000 m + spread — enough that the
   * two colliders stay clear of each other AND the player can see the neighbor as a visible
   * globe floating in the sky above the current planet.
   */
  const n = (doc.worlds || []).length;
  const offset = { x: (n % 3) * 2000 - 2000, y: 8000 + Math.floor(n / 3) * 2000, z: (n % 2) * 2000 };
  doc.worlds = doc.worlds || [];
  doc.worlds.push({
    planetId: pid,
    label,
    sphereRadius: 2500,
    useSpherePhysics: true,
    universePose: { x: offset.x, y: offset.y, z: offset.z, rx: 0, ry: 0, rz: 0, sx: 1, sy: 1, sz: 1 },
  });
  /** Re-normalize so defaults (atmosphere, biomes, tree spawn, etc.) get filled in. */
  doc = normalizeMapDocument(doc);
  persist();
  rebuildPlanets();
  selectPlanet(pid);
  setStatus(`Created planet "${label}" (${pid})`);
});

document.getElementById('ue-rename-planet')?.addEventListener('click', () => {
  if (!selectedPlanetId) {
    setStatus('Select a planet first.');
    return;
  }
  const w = doc.worlds.find((x) => x && x.planetId === selectedPlanetId);
  if (!w) return;
  const next = (prompt(`Rename "${w.label || w.planetId}" to:`, w.label || w.planetId) || '').trim();
  if (!next || next === w.label) return;
  w.label = next;
  persist();
  buildHierarchy();
  /** Update the label sprite on the 3D proxy without a full rebuild. */
  const root = planetRoots.get(selectedPlanetId);
  if (root) {
    const oldSprite = root.children.find((c) => c.isSprite);
    if (oldSprite) {
      root.remove(oldSprite);
      oldSprite.material?.map?.dispose?.();
      oldSprite.material?.dispose?.();
    }
    root.add(makeLabelSprite(`${next} (${selectedPlanetId})`));
  }
  renderPlanetPanel();
  setStatus(`Renamed to "${next}"`);
});

document.getElementById('ue-delete-planet')?.addEventListener('click', () => {
  if (!selectedPlanetId) {
    setStatus('Select a planet first.');
    return;
  }
  if ((doc.worlds || []).length <= 1) {
    setStatus('Cannot delete the last planet — at least one world must exist.');
    return;
  }
  const w = doc.worlds.find((x) => x && x.planetId === selectedPlanetId);
  if (!w) return;
  if (!confirm(`Delete planet "${w.label || selectedPlanetId}" (${selectedPlanetId})? This removes all trees, props and plots stored on that planet.`)) {
    return;
  }
  const deletedId = selectedPlanetId;
  doc.worlds = doc.worlds.filter((x) => x && x.planetId !== deletedId);
  if (doc.defaultPlanetId === deletedId) {
    doc.defaultPlanetId = doc.worlds[0]?.planetId || 'home';
  }
  persist();
  rebuildPlanets();
  selectPlanet(doc.worlds[0]?.planetId || null);
  setStatus(`Deleted planet "${w.label || deletedId}"`);
});

animate();
