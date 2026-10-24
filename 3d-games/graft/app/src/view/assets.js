// The model pack: real Blender geometry, if any is on disk.
//
// This is an ENHANCEMENT LAYER and it is written to be deleted. `app/assets/`
// may be absent, empty, half-written or unreachable, and every path through
// here answers `null` -- which the renderer reads as "draw the procedural shape
// you have always drawn". Nothing in the game may depend on a model existing.
// The suite tests the absent case, not just the present one.
//
// The parts it hands back are shaped EXACTLY like an entry in render.js's PARTS
// table -- `{ geo, mat, y, s }` -- so swapping a procedural bank for a loaded
// one is choosing between two arrays, not a new code path. That is deliberate:
// every object is still drawn by one InstancedMesh per material, which is why
// the frame holds at 4.1 ms with a thousand things on the planet.

import * as THREE from '../../vendor/three/three.module.js';
import { parseGLB, fitToUnit, bounds } from '../../vendor/GLTFLoader.js';

// Resolved against the PAGE, not against this module. `import.meta.url` would
// be the obvious way to write it and it is a trap: tools/bundle.py rewrites
// every module into a function body for the single-file build, and `import.meta`
// outside module code is a SYNTAX error -- so the bundle would not parse at all,
// which is a blank page rather than a missing model.
const PACK_PATH = 'assets/models/pack.json';

function defaultUrl() {
  try {
    if (typeof document !== 'undefined' && document.baseURI) {
      return new URL(PACK_PATH, document.baseURI).href;
    }
  } catch (e) { /* fall through to the plain relative path */ }
  return PACK_PATH;
}

// A phone draws these a thousand at a time. A model that blows the budget is
// worse than no model, so it is refused and the procedural shape stands.
const MAX_TRIS = 4000;
const TIMEOUT = 8000;

function noop() {}

async function grab(fetcher, url, as, timeout) {
  let signal, timer = null;
  if (timeout > 0 && typeof AbortController !== 'undefined') {
    const ac = new AbortController();
    signal = ac.signal;
    timer = setTimeout(() => ac.abort(), timeout);
  }
  try {
    const res = await fetcher(url, signal ? { signal } : undefined);
    if (!res || !res.ok) return null;
    return as === 'json' ? await res.json() : await res.arrayBuffer();
  } catch (e) {
    return null;                    // a missing pack is a normal state, not an error
  } finally {
    if (timer) clearTimeout(timer);
  }
}

/**
 * Load whatever models are on disk.
 *
 * @param {object} [opts]
 * @param {string} [opts.url]      where pack.json lives
 * @param {Function} [opts.fetch]  injected for the suite; defaults to global fetch
 * @param {number} [opts.maxTris]  per-model budget
 * @param {Function} [opts.warn]   told about each model that was refused
 * @returns {Promise<object|null>} a pack, or null if there is nothing to load
 */
export async function loadModelPack(opts = {}) {
  const fetcher = opts.fetch || (typeof fetch === 'function' ? fetch : null);
  if (!fetcher) return null;                       // no fetch at all: nothing to do
  const url = opts.url || defaultUrl();
  const maxTris = opts.maxTris === undefined ? MAX_TRIS : opts.maxTris;
  const timeout = opts.timeout === undefined ? TIMEOUT : opts.timeout;
  const warn = opts.warn || noop;

  const manifest = await grab(fetcher, url, 'json', timeout);
  if (!manifest || typeof manifest !== 'object') return null;
  const listed = manifest.models;
  if (!listed || typeof listed !== 'object') return null;

  const base = url.replace(/[^/]*$/, '');
  const kinds = Object.create(null);
  let loaded = 0, tris = 0, bytes = 0;

  for (const kind of Object.keys(listed)) {
    const entry = listed[kind];
    if (!entry || typeof entry.file !== 'string') { warn(kind, 'no file named'); continue; }
    const buf = await grab(fetcher, base + entry.file, 'buffer', timeout);
    if (!buf) { warn(kind, 'not on disk'); continue; }
    try {
      const parsed = parseGLB(buf);
      if (parsed.tris > maxTris) {
        warn(kind, `${parsed.tris} triangles is over the ${maxTris} budget`);
        continue;
      }
      const fit = clampFit(entry.fit);
      // Always re-fit at runtime. The exporter does this too, but the owner can
      // drop any .glb in here and a model authored in centimetres would plant a
      // 180-unit tree.
      fitToUnit(parsed.parts, fit);
      kinds[kind] = parsed.parts.map(toPart);
      loaded++;
      tris += parsed.tris;
      bytes += buf.byteLength;
    } catch (e) {
      warn(kind, (e && e.message) || 'unreadable');
    }
  }

  if (!loaded) return null;
  return { kinds, count: loaded, tris, bytes, url, source: manifest.generated || 'unknown' };
}

function clampFit(v) {
  const f = Number(v);
  if (!isFinite(f) || f <= 0) return 1;
  return Math.min(4, Math.max(0.2, f));
}

// One part becomes one InstancedMesh. `y: 0, s: 1` is what makes the existing
// rebuild() maths correct with no change: the model's base is already at its
// origin, so the lift is zero and the scale is just the stage height.
function toPart(p) {
  const geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.BufferAttribute(p.positions, 3));
  if (p.normals) geo.setAttribute('normal', new THREE.BufferAttribute(p.normals, 3));
  geo.setIndex(new THREE.BufferAttribute(p.indices, 1));
  if (!p.normals) geo.computeVertexNormals();
  geo.computeBoundingSphere();

  const m = p.material;
  // glTF factors are LINEAR, which is not the colour space three works in by
  // default -- read them as linear and the model matches the Blender viewport.
  const mat = {
    color: new THREE.Color().setRGB(m.baseColor[0], m.baseColor[1], m.baseColor[2], THREE.LinearSRGBColorSpace),
    roughness: m.roughness,
    metalness: m.metallic,
    flatShading: false,
    side: m.doubleSided ? THREE.DoubleSide : THREE.FrontSide,
  };
  const lit = m.emissive[0] + m.emissive[1] + m.emissive[2];
  if (lit > 0) {
    mat.emissive = new THREE.Color().setRGB(m.emissive[0], m.emissive[1], m.emissive[2], THREE.LinearSRGBColorSpace);
    mat.emissiveIntensity = m.emissiveStrength;
  }
  if (m.alphaMode === 'BLEND' || m.opacity < 1) {
    mat.transparent = true;
    mat.opacity = m.opacity;
  }
  return { geo: () => geo, mat, y: 0, s: 1, model: true, name: p.name };
}

/**
 * The parts for one kind, in render.js's PARTS shape, or null.
 * `null` is the whole fallback: `modelParts(pack, kind) || PARTS[kind]`.
 */
export function modelParts(pack, kind) {
  if (!pack || !pack.kinds) return null;
  const parts = pack.kinds[kind];
  return parts && parts.length ? parts : null;
}

export function hasModel(pack, kind) {
  return !!modelParts(pack, kind);
}

/** Every kind the pack can draw, for a report line. */
export function packKinds(pack) {
  return pack && pack.kinds ? Object.keys(pack.kinds).sort() : [];
}

/**
 * A THREE.Group of the astronaut model, base at the feet, `height` tall --
 * for the one thing in the game that is not instanced.
 */
export function playerModel(pack, height = 1) {
  const parts = modelParts(pack, 'astronaut');
  if (!parts) return null;
  const g = new THREE.Group();
  for (const p of parts) {
    g.add(new THREE.Mesh(p.geo(), new THREE.MeshStandardMaterial(p.mat)));
  }
  g.scale.setScalar(height);
  return g;
}

/** Free the GPU side of a pack. */
export function disposePack(pack) {
  if (!pack || !pack.kinds) return;
  for (const kind of Object.keys(pack.kinds)) {
    for (const p of pack.kinds[kind]) {
      const g = p.geo();
      if (g && g.dispose) g.dispose();
    }
  }
}

export { bounds };
