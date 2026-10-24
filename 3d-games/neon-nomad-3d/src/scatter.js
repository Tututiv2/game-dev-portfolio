// Everything standing on the ground: boulders, shards, and the flora.
//
// This exists to solve one specific problem: with nothing between the 0.8 m
// vertex grid and a 200 m mesa, your eye has no size reference, so the whole
// planet reads as a smooth toy no matter how good the height field is. Rocks
// cover 0.3-4 m. Trees cover 4-16 m, which is the band that actually tells you
// how far away a hill is.
//
// Placement is LOD-INDEPENDENT by construction. Everything lives on a fixed
// global cell grid derived from the same cube-sphere as the terrain, and an
// object's existence, size and rotation come from a hash of its cell index —
// not from the chunk that happens to be drawing it. Drive away, come back, and
// it is all exactly where you left it. (Verified by test, not by hope.)

import * as THREE from 'three';
import { FACES, uvToUnit } from './cubesphere.js';
import { WORLD, elevation, scatterDensity, groveDensity } from './terrain.js';

const _dir = { x: 0, y: 0, z: 0 };
const _pos = new THREE.Vector3();
const _up = new THREE.Vector3();
const _q = new THREE.Quaternion();
const _qSpin = new THREE.Quaternion();
const _scale = new THREE.Vector3();
const _mat = new THREE.Matrix4();
const _yAxis = new THREE.Vector3(0, 1, 0);
const _leanAxis = new THREE.Vector3();

// Instance transforms are accumulated straight into these, never into cloned
// Matrix4 objects. A dense chunk places ~800 objects, three chunks build per
// frame, and at that rate the clones alone produced a 224 ms GC pause.
let _buf = [new Float32Array(2048 * 16), new Float32Array(2048 * 16)];
const _count = [0, 0];

function emit(bucket) {
  const need = (_count[bucket] + 1) * 16;
  if (need > _buf[bucket].length) {
    const bigger = new Float32Array(Math.max(need, _buf[bucket].length * 2));
    bigger.set(_buf[bucket]);
    _buf[bucket] = bigger;
  }
  _mat.toArray(_buf[bucket], _count[bucket] * 16);
  _count[bucket]++;
}

// Deterministic hash of a cell. `salt` gives independent streams for the
// different decisions about one object.
function cellHash(face, cx, cy, salt) {
  let h = Math.imul(face + 1, 0x27d4eb2d) ^ Math.imul(cx, 0x9e3779b1) ^
          Math.imul(cy, 0x85ebca6b) ^ Math.imul(salt, 0xc2b2ae35);
  h = Math.imul(h ^ (h >>> 15), 0x2c1b3c6d);
  h = Math.imul(h ^ (h >>> 12), 0x297a2d39);
  return ((h ^ (h >>> 15)) >>> 0) / 4294967296;
}

// --- geometry ----------------------------------------------------------------

// Concatenate parts into one geometry carrying an `aPart` flag, so a whole tree
// is a single InstancedMesh instead of one per material. Halves the draw calls
// and keeps trunk and canopy welded together.
function mergeParts(parts) {
  const prepped = [];
  let total = 0;
  for (const p of parts) {
    const g = p.geo.index ? p.geo.toNonIndexed() : p.geo.clone();
    g.applyMatrix4(p.matrix);
    total += g.getAttribute('position').count;
    prepped.push({ g, part: p.part });
  }
  const pos = new Float32Array(total * 3);
  const nrm = new Float32Array(total * 3);
  const prt = new Float32Array(total);
  let o = 0;
  for (const { g, part } of prepped) {
    const P = g.getAttribute('position');
    const N = g.getAttribute('normal');
    pos.set(P.array.subarray(0, P.count * 3), o * 3);
    nrm.set(N.array.subarray(0, N.count * 3), o * 3);
    prt.fill(part, o, o + P.count);
    o += P.count;
    g.dispose();
  }
  const out = new THREE.BufferGeometry();
  out.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  out.setAttribute('normal', new THREE.BufferAttribute(nrm, 3));
  out.setAttribute('aPart', new THREE.BufferAttribute(prt, 1));
  return out;
}

function at(y, sy = 1) {
  return new THREE.Matrix4().makeTranslation(0, y, 0).scale(new THREE.Vector3(1, sy, 1));
}

export function rockGeometries() {
  const boulder = new THREE.IcosahedronGeometry(1, 0);
  const shard = new THREE.ConeGeometry(1, 2.0, 5, 1);
  return [boulder, shard];
}

// Both species are modelled at unit height standing on y = 0, so the instance
// scale is simply how tall the tree is in metres.
export function treeGeometries() {
  // Spire: a tall thin thing with a stacked crown. Its job is vertical scale.
  const spire = mergeParts([
    { geo: new THREE.CylinderGeometry(0.028, 0.075, 0.70, 5), matrix: at(0.35), part: 0 },
    { geo: new THREE.ConeGeometry(0.20, 0.40, 6), matrix: at(0.66), part: 1 },
    { geo: new THREE.ConeGeometry(0.13, 0.28, 6), matrix: at(0.90), part: 1 },
  ]);
  // Canopy: short and wide, to break up a horizon full of spires.
  const canopy = mergeParts([
    { geo: new THREE.CylinderGeometry(0.038, 0.085, 0.55, 5), matrix: at(0.275), part: 0 },
    { geo: new THREE.IcosahedronGeometry(0.36, 0), matrix: at(0.70, 0.55), part: 1 },
  ]);
  return [spire, canopy];
}

// --- layers ------------------------------------------------------------------
//
// `depth` is the chunk depth that owns the layer, which sets how far away it
// stays loaded: depth 5 (~147 m chunks) reaches ~460 m, depth 4 (~294 m) reaches
// ~900 m. Trees live a level shallower than rocks precisely so you can see a
// forest from across a valley and decide to drive to it.
// Each layer is tested in three widening stages, and the ORDER is the point.
// `salt`/`maxProb` is a pure hash and rejects most cells for nothing; `density`
// is a 3-octave noise; `place` only runs for survivors and is the only stage
// that pays for elevation(), which costs ~28 noise evaluations. Testing
// elevation first would make a chunk cost 4.5 ms instead of 1.
export const LAYERS = [
  { name: 'rocks', stat: 'rocks', depth: 5, level: 11, salt: 1,  maxProb: 0.20,
    density: scatterDensity, place: placeRock },
  { name: 'trees', stat: 'trees', depth: 4, level: 9,  salt: 21, maxProb: 0.42,
    density: groveDensity,   place: placeTree },
];

// Writes its transform into `_mat` and returns the bucket index + 1, or 0 to
// skip this cell.
function placeRock(faceIndex, gx, gy, dir, e) {
  const type = cellHash(faceIndex, gx, gy, 4) < 0.78 ? 0 : 1;
  // Heavily skewed small: a field of same-sized rocks reads as polka dots. The
  // occasional big one is what carries the size hierarchy.
  const s = 0.30 + Math.pow(cellHash(faceIndex, gx, gy, 5), 3.6) * 3.9;

  // Sink it, so a rock on a slope beds into the ground instead of balancing on
  // a single point.
  const r = WORLD.radius + e - s * 0.34;
  _pos.set(dir.x * r, dir.y * r, dir.z * r);
  _up.set(dir.x, dir.y, dir.z);

  _q.setFromUnitVectors(_yAxis, _up);
  _qSpin.setFromAxisAngle(_yAxis, cellHash(faceIndex, gx, gy, 6) * Math.PI * 2);
  _q.multiply(_qSpin);
  _leanAxis.set(1, 0, 0);
  _qSpin.setFromAxisAngle(_leanAxis, (cellHash(faceIndex, gx, gy, 7) - 0.5) * 0.5);
  _q.multiply(_qSpin);

  _scale.set(
    s * (0.65 + cellHash(faceIndex, gx, gy, 8) * 0.7),
    s * (type === 1 ? 0.8 + cellHash(faceIndex, gx, gy, 9) * 1.1
                    : 0.55 + cellHash(faceIndex, gx, gy, 9) * 0.6),
    s * (0.65 + cellHash(faceIndex, gx, gy, 10) * 0.7)
  );
  _mat.compose(_pos, _q, _scale);
  return type + 1;
}

function placeTree(faceIndex, gx, gy, dir, e) {
  // Nothing grows on the Flats — they are solidified coolant, not soil — and
  // nothing grows on the peaks.
  if (e < WORLD.flatLevel + 10 || e > WORLD.amplitude * 0.62) return 0;

  const type = cellHash(faceIndex, gx, gy, 22) < 0.62 ? 0 : 1;
  const h = type === 0
    ? 5.5 + cellHash(faceIndex, gx, gy, 23) * 7.5     // spire  5.5-13 m
    : 3.4 + cellHash(faceIndex, gx, gy, 23) * 4.2;    // canopy 3.4-7.6 m

  // Bed the trunk slightly under the surface so it never floats on a slope.
  const r = WORLD.radius + e - 0.5;
  _pos.set(dir.x * r, dir.y * r, dir.z * r);
  _up.set(dir.x, dir.y, dir.z);

  _q.setFromUnitVectors(_yAxis, _up);
  _qSpin.setFromAxisAngle(_yAxis, cellHash(faceIndex, gx, gy, 24) * Math.PI * 2);
  _q.multiply(_qSpin);
  _leanAxis.set(1, 0, 0);
  _qSpin.setFromAxisAngle(_leanAxis, (cellHash(faceIndex, gx, gy, 25) - 0.5) * 0.22);
  _q.multiply(_qSpin);

  const w = 0.80 + cellHash(faceIndex, gx, gy, 26) * 0.5;
  _scale.set(h * w, h, h * w);
  _mat.compose(_pos, _q, _scale);
  return type + 1;
}

// Builds one layer over one chunk's uv footprint. Returns InstancedMeshes,
// already positioned in world space.
export function buildLayer(layer, faceIndex, u0, v0, size, geometries, material, cullCenter, cullRadius) {
  const face = FACES[faceIndex];
  const cells = 1 << (layer.level - layer.depth);
  const cellU = size / cells;
  // Cell index of this chunk's origin on the GLOBAL grid, so the hash is tied
  // to the world and not to the chunk.
  const baseX = Math.round(u0 / cellU);
  const baseY = Math.round(v0 / cellU);

  _count[0] = 0;
  _count[1] = 0;

  for (let cy = 0; cy < cells; cy++) {
    for (let cx = 0; cx < cells; cx++) {
      const gx = baseX + cx;
      const gy = baseY + cy;

      // Stage 1: pure hash. Rejects most cells before touching any noise.
      const r0 = cellHash(faceIndex, gx, gy, layer.salt);
      if (r0 > layer.maxProb) continue;

      const jx = cellHash(faceIndex, gx, gy, 2);
      const jy = cellHash(faceIndex, gx, gy, 3);
      uvToUnit(face, u0 + (cx + jx) * cellU, v0 + (cy + jy) * cellU, _dir);

      // Stage 2: cheap density noise, so growth clusters into fields and groves
      // instead of dusting the planet evenly.
      if (r0 > layer.density(_dir.x, _dir.y, _dir.z) * layer.maxProb) continue;

      // Stage 3: the expensive one, only for survivors.
      const e = elevation(_dir.x, _dir.y, _dir.z);
      const bucket = layer.place(faceIndex, gx, gy, _dir, e);
      if (bucket) emit(bucket - 1);
    }
  }

  const meshes = [];
  for (let t = 0; t < _count.length; t++) {
    const n = _count[t];
    if (!n) continue;
    const im = new THREE.InstancedMesh(geometries[t], material, n);
    im.instanceMatrix.array.set(_buf[t].subarray(0, n * 16));
    im.instanceMatrix.needsUpdate = true;
    im.frustumCulled = true;
    im.matrixAutoUpdate = false;
    // Instances are already in world space, so the mesh sits at the origin and
    // needs its bounds handed to it explicitly. Set it on the InstancedMesh,
    // NEVER on the geometry — the geometry is shared by every chunk, and
    // writing chunk-local bounds to it breaks culling for all of them.
    im.boundingSphere = new THREE.Sphere(cullCenter.clone(), cullRadius);
    meshes.push(im);
  }
  return meshes;
}
