// The planet: a cube-sphere quadtree that subdivides toward the camera.
//
// Six root chunks, each splitting into four while you are close enough, so the
// ground under the crawler is sub-metre while the mountains on the far side of
// the world cost almost nothing.
//
// One rule holds the whole thing together: the height function is IDENTICAL at
// every level of detail. It is tempting to drop octaves on distant chunks to
// save time — do not. Two neighbouring chunks at different depths share an edge,
// and if they disagree about the height there, the seam is a hole you can see
// the stars through.

import * as THREE from 'three';
import { FACES, FACE_FLIP, uvToUnit, unitToUV } from './cubesphere.js';
import { WORLD, elevation, oreAt } from './terrain.js';
import { surfaceUniforms, terrainMaterial, rockMaterial, treeMaterial } from './materials.js';
import { LAYERS, rockGeometries, treeGeometries, buildLayer } from './scatter.js';

const RES = 24;            // quads per chunk edge
const OUT = RES + 3;       // emitted verts per side: core grid + a skirt ring
const PAD = RES + 3;       // sampled verts per side: core grid + normal padding
const MAX_DEPTH = 8;
const SPLIT = 2.4;         // split when the camera is within 2.4 chunk-widths
const BUILD_BUDGET = 3;    // chunks meshed per frame — the cap that stops a fast
                           // drive from turning into a stutter. A chunk costs
                           // ~1.1 ms, and at boost the queue still grows; that
                           // is fine, it drains the moment you slow down.

const FACE_ARC = (Math.PI / 2) * WORLD.radius;

// Shared scratch — chunk building runs hundreds of times a second and must not
// allocate.
const _d = { x: 0, y: 0, z: 0 };
const _uv = {};

// Precomputed triangle indices. Every chunk has the same topology, so the index
// buffer is built once and shared by all of them.
const INDICES = (() => {
  const both = [null, null];
  for (let flip = 0; flip < 2; flip++) {
    const idx = new Uint16Array(RES + 2 > 0 ? (OUT - 1) * (OUT - 1) * 6 : 0);
    let n = 0;
    for (let j = 0; j < OUT - 1; j++) {
      for (let i = 0; i < OUT - 1; i++) {
        const a = j * OUT + i;
        const b = a + 1;
        const c = a + OUT;
        const d = c + 1;
        if (flip) { idx[n++] = a; idx[n++] = c; idx[n++] = b; idx[n++] = b; idx[n++] = c; idx[n++] = d; }
        else      { idx[n++] = a; idx[n++] = b; idx[n++] = c; idx[n++] = b; idx[n++] = d; idx[n++] = c; }
      }
    }
    both[flip] = new THREE.BufferAttribute(idx, 1);
  }
  return both;
})();

class Chunk {
  constructor(planet, faceIndex, u0, v0, size, depth) {
    this.planet = planet;
    this.faceIndex = faceIndex;
    this.u0 = u0; this.v0 = v0; this.size = size; this.depth = depth;
    this.children = null;
    this.mesh = null;
    this.scatter = null;
    this.queued = false;
    this.dead = false;

    this.edgeLen = FACE_ARC * size;

    const dir = uvToUnit(FACES[faceIndex], u0 + size * 0.5, v0 + size * 0.5, {});
    const r = WORLD.radius + elevation(dir.x, dir.y, dir.z);
    this.center = new THREE.Vector3(dir.x * r, dir.y * r, dir.z * r);
    // Two different radii, and conflating them cost an afternoon. The cull
    // bound has to include the full relief amplitude or peaks pop out at the
    // screen edge. The LOD bound must NOT: at depth 6+ a chunk is smaller than
    // the amplitude, so adding it drives `dist` to zero for everything within
    // ~300 m and every one of those chunks subdivides to max depth at once.
    this.cullRadius = this.edgeLen * 0.75 + WORLD.amplitude;
    this.lodRadius = this.edgeLen * 0.75;
  }

  build() {
    if (this.dead || this.mesh) return;

    const R = WORLD.radius;
    const face = FACES[this.faceIndex];
    const step = this.size / RES;

    const px = new Float64Array(PAD * PAD);
    const py = new Float64Array(PAD * PAD);
    const pz = new Float64Array(PAD * PAD);
    const dx = new Float64Array(PAD * PAD);
    const dy = new Float64Array(PAD * PAD);
    const dz = new Float64Array(PAD * PAD);

    // Sample one vertex wider than the chunk on every side, so central
    // differences have real neighbours at the border instead of a guessed
    // normal. Sample index si maps to grid coordinate si - 1.
    for (let sj = 0; sj < PAD; sj++) {
      const v = this.v0 + (sj - 1) * step;
      for (let si = 0; si < PAD; si++) {
        const u = this.u0 + (si - 1) * step;
        uvToUnit(face, u, v, _d);
        const r = R + elevation(_d.x, _d.y, _d.z);
        const k = sj * PAD + si;
        dx[k] = _d.x; dy[k] = _d.y; dz[k] = _d.z;
        px[k] = _d.x * r; py[k] = _d.y * r; pz[k] = _d.z * r;
      }
    }

    const count = OUT * OUT;
    const pos = new Float32Array(count * 3);
    const nrm = new Float32Array(count * 3);
    const ore = new Float32Array(count * 4);

    // Skirts: the border ring is DUPLICATED and dropped straight down, hiding
    // the crack where this chunk meets a neighbour at a coarser depth. Cheaper
    // and far less fragile than stitching the two edges together.
    //
    // The duplicate matters. Displacing a ring that sits one quad outside the
    // chunk instead makes every chunk overlap its neighbours, and the sloped
    // skirt then draws across the neighbour's surface as a dark seam.
    //
    // Depth: a neighbour is at most one level coarser, so the worst height
    // disagreement is roughly what the terrain does across two of our quads.
    const skirt = (this.edgeLen / RES) * 3.5;
    // Veins are ~14 m across; sampling them on a chunk whose vertices are 100 m
    // apart just produces crawling speckle. Below this depth we keep only the
    // broad ore field, and the veins resolve as you approach.
    const veinsVisible = this.depth >= 5;

    let o = 0;
    for (let j = 0; j < OUT; j++) {
      for (let i = 0; i < OUT; i++) {
        // Output row/col 0 and OUT-1 are the skirt: same vertex as the border,
        // dropped. Everything between walks the core grid.
        const gi = Math.min(Math.max(i - 1, 0), RES);
        const gj = Math.min(Math.max(j - 1, 0), RES);
        const si = gi + 1, sj = gj + 1;
        const k = sj * PAD + si;

        // Central differences on the sampled grid give a normal that matches
        // the surface exactly, with no extra height evaluations.
        const ax = px[k + 1] - px[k - 1], ay = py[k + 1] - py[k - 1], az = pz[k + 1] - pz[k - 1];
        const bx = px[k + PAD] - px[k - PAD], by = py[k + PAD] - py[k - PAD], bz = pz[k + PAD] - pz[k - PAD];
        let nx = ay * bz - az * by;
        let ny = az * bx - ax * bz;
        let nz = ax * by - ay * bx;
        const nl = Math.hypot(nx, ny, nz) || 1;
        nx /= nl; ny /= nl; nz /= nl;
        // Half the cube faces are mirrored, so the cross product points inward
        // on those. Flip against the outward direction rather than tabulating.
        if (nx * dx[k] + ny * dy[k] + nz * dz[k] < 0) { nx = -nx; ny = -ny; nz = -nz; }

        const edge = (i === 0 || j === 0 || i === OUT - 1 || j === OUT - 1);
        const drop = edge ? skirt : 0;

        pos[o * 3] = px[k] - dx[k] * drop;
        pos[o * 3 + 1] = py[k] - dy[k] * drop;
        pos[o * 3 + 2] = pz[k] - dz[k] * drop;
        nrm[o * 3] = nx; nrm[o * 3 + 1] = ny; nrm[o * 3 + 2] = nz;

        const s = oreAt(dx[k], dy[k], dz[k]);
        ore[o * 4] = veinsVisible ? s.massVein : 0;
        ore[o * 4 + 1] = veinsVisible ? s.techVein : 0;
        ore[o * 4 + 2] = s.massField;
        ore[o * 4 + 3] = s.techField;
        o++;
      }
    }

    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    geo.setAttribute('normal', new THREE.BufferAttribute(nrm, 3));
    geo.setAttribute('aOre', new THREE.BufferAttribute(ore, 4));
    geo.setIndex(INDICES[FACE_FLIP[this.faceIndex] ? 1 : 0]);
    geo.boundingSphere = new THREE.Sphere(this.center.clone(), this.cullRadius);

    this.mesh = new THREE.Mesh(geo, this.planet.material);
    this.mesh.frustumCulled = true;
    this.mesh.visible = false;
    this.mesh.matrixAutoUpdate = false;
    this.planet.group.add(this.mesh);
    this.planet.stats.chunks++;

    // Each scatter layer belongs to exactly one depth, and stays visible even
    // when this chunk's own terrain is hidden because its children took over
    // drawing it. Placement comes off a global cell grid, so which chunk builds
    // a rock or a tree makes no difference to where it lands.
    for (const layer of LAYERS) {
      if (this.depth !== layer.depth) continue;
      const res = this.planet.layerResources[layer.name];
      const meshes = buildLayer(
        layer, this.faceIndex, this.u0, this.v0, this.size,
        res.geometries, res.material, this.center, this.cullRadius
      );
      if (!meshes.length) continue;
      if (!this.scatter) this.scatter = [];
      for (const m of meshes) {
        this.scatter.push({ mesh: m, stat: layer.stat });
        this.planet.group.add(m);
        this.planet.stats[layer.stat] += m.count;
      }
    }
  }

  ready() { return this.mesh !== null; }

  setVisible(v) { if (this.mesh) this.mesh.visible = v; }

  lod(camPos) {
    if (this.dead) return;
    const d = Math.max(0, camPos.distanceTo(this.center) - this.lodRadius);
    const wantSplit = this.depth < MAX_DEPTH && d < this.edgeLen * SPLIT;

    if (wantSplit) {
      if (!this.children) {
        const h = this.size * 0.5;
        this.children = [
          new Chunk(this.planet, this.faceIndex, this.u0, this.v0, h, this.depth + 1),
          new Chunk(this.planet, this.faceIndex, this.u0 + h, this.v0, h, this.depth + 1),
          new Chunk(this.planet, this.faceIndex, this.u0, this.v0 + h, h, this.depth + 1),
          new Chunk(this.planet, this.faceIndex, this.u0 + h, this.v0 + h, h, this.depth + 1),
        ];
        for (const c of this.children) this.planet.enqueue(c);
      }
      let allReady = true;
      for (const c of this.children) {
        // Only descend into a child that actually exists yet. Recursing into
        // unbuilt children lets the tree reach max depth in a single frame and
        // queue thousands of chunks nobody asked for; gating on `ready` makes
        // detail grow one level per build instead.
        if (c.ready()) c.lod(camPos);
        else allReady = false;
      }
      // Keep drawing ourselves until every child can cover our footprint —
      // otherwise splitting punches a hole in the world for a frame or two.
      this.setVisible(!allReady);
    } else {
      if (this.children) {
        for (const c of this.children) c.dispose();
        this.children = null;
      }
      this.setVisible(true);
    }
  }

  dispose() {
    this.dead = true;
    if (this.children) { for (const c of this.children) c.dispose(); this.children = null; }
    if (this.mesh) {
      this.planet.group.remove(this.mesh);
      this.mesh.geometry.dispose();
      this.mesh = null;
      this.planet.stats.chunks--;
    }
    if (this.scatter) {
      for (const s of this.scatter) {
        this.planet.group.remove(s.mesh);
        this.planet.stats[s.stat] -= s.mesh.count;
        s.mesh.dispose();       // frees the instance buffers, not the shared geometry
      }
      this.scatter = null;
    }
  }
}

export class Planet {
  constructor() {
    this.group = new THREE.Group();
    // One uniform object for every surface material, so the ground and the
    // rocks standing on it can never disagree about the sun, the fog or the
    // crawler's headlamps.
    this.uniforms = surfaceUniforms();
    this.material = terrainMaterial(this.uniforms);
    this.layerResources = {
      rocks: { geometries: rockGeometries(), material: rockMaterial(this.uniforms) },
      trees: { geometries: treeGeometries(), material: treeMaterial(this.uniforms) },
    };
    this.queue = [];
    this.stats = { chunks: 0, queued: 0, rocks: 0, trees: 0 };
    this.roots = FACES.map((_, i) => new Chunk(this, i, 0, 0, 1, 0));
    for (const r of this.roots) r.build();   // the six roots up front, so the
                                             // first frame is never empty sky
  }

  enqueue(chunk) { chunk.queued = true; this.queue.push(chunk); }

  update(camPos) {
    for (const r of this.roots) r.lod(camPos);

    // Nearest-first: what is under your wheels gets meshed before what is on
    // the horizon.
    if (this.queue.length) {
      this.queue = this.queue.filter((c) => !c.dead && !c.mesh);
      this.queue.sort((a, b) => a.center.distanceToSquared(camPos) - b.center.distanceToSquared(camPos));
      const n = Math.min(BUILD_BUDGET, this.queue.length);
      for (let i = 0; i < n; i++) this.queue[i].build();
      this.queue.splice(0, n);
    }
    this.stats.queued = this.queue.length;
  }

  // --- sampling, for anything that has to sit on the ground ---

  // Metres above the nominal radius at a direction from the planet centre.
  heightAt(dir) {
    return elevation(dir.x, dir.y, dir.z);
  }

  // Distance from the planet centre to the ground at a direction.
  surfaceRadiusAt(dir) {
    return WORLD.radius + elevation(dir.x, dir.y, dir.z);
  }

  // Surface normal at a direction, by sampling a small triangle around it.
  // `eps` is in metres of arc.
  normalAt(dir, out = new THREE.Vector3(), eps = 1.5) {
    const up = _n1.copy(dir).normalize();
    // Any two tangents will do; build them from the least-aligned axis.
    const ref = Math.abs(up.y) < 0.9 ? _n2.set(0, 1, 0) : _n2.set(1, 0, 0);
    const t1 = _n3.crossVectors(ref, up).normalize();
    const t2 = _n4.crossVectors(up, t1).normalize();
    const a = _p1.copy(up).addScaledVector(t1, eps / WORLD.radius).normalize();
    const b = _p2.copy(up).addScaledVector(t2, eps / WORLD.radius).normalize();
    const rc = WORLD.radius + elevation(up.x, up.y, up.z);
    const ra = WORLD.radius + elevation(a.x, a.y, a.z);
    const rb = WORLD.radius + elevation(b.x, b.y, b.z);
    _p4.copy(up).multiplyScalar(rc);
    _p3.copy(a).multiplyScalar(ra).sub(_p4);
    _p5.copy(b).multiplyScalar(rb).sub(_p4);
    out.crossVectors(_p3, _p5).normalize();
    if (out.dot(up) < 0) out.negate();
    return out;
  }

  // Which grid cell a direction falls in, at a given subdivision level.
  // Towers will snap to these — the grid already wraps the whole planet.
  cellAt(dir, level = 7) {
    unitToUV(dir.x, dir.y, dir.z, _uv);
    const n = 1 << level;
    return { face: _uv.face, col: Math.min(n - 1, (_uv.u * n) | 0), row: Math.min(n - 1, (_uv.v * n) | 0), level };
  }
}

const _n1 = new THREE.Vector3(), _n2 = new THREE.Vector3(), _n3 = new THREE.Vector3(), _n4 = new THREE.Vector3();
const _p1 = new THREE.Vector3(), _p2 = new THREE.Vector3(), _p3 = new THREE.Vector3(), _p4 = new THREE.Vector3(), _p5 = new THREE.Vector3();
