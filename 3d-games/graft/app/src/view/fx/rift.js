// THE RIFT — the render side.
//
// Owns one Group and nothing else. It reads the world; it never changes it.
// Everything it needs to know about the tear is in `ev.data.r` (how wide it is)
// and the phase, both of which the sim owns and saves.
//
// The look is one idea: inside the boundary NOTHING obeys the planet. The
// ground glows violet and acid lime, which are the two colours that appear
// nowhere else in the game; the things standing in it lean at angles the
// terrain never produces; and each of them flickers between two different
// shapes on its own clock, so the interior never resolves into one picture.
//
// No three.js import: THREE arrives on ctx, which is what lets the test import
// this file under plain node with a stub and drive every phase of it.

import { RIFT } from '../../sim/events/rift.js';

export const id = 'rift';

const RINGS = 7;                  // the ground patch is a fan, conformed to the
const SPOKES = 30;                // terrain by the SAME height function you walk on
const SHARDS = 44;
const TEETH = 34;
const CRACK = 26;
const LIFT = 0.07;                // off the ground, or it z-fights the planet

const VIOLET = 0x7a2bff;          // deliberately outside the planet's palette:
const LIME = 0xc8ff2b;            // sand, grass, rock, snow, and the neon pink
const BONE = 0xe8e0ff;            // and cyan the beacons already use

/** Deterministic scatter, so the same rift looks the same twice. */
function lcg(seed) {
  let s = seed | 0;
  return () => {
    s = (Math.imul(s, 1103515245) + 12345) & 0x7fffffff;
    return s / 0x7fffffff;
  };
}

export function build(ctx) {
  const T = ctx.THREE;
  const group = new T.Group();
  ctx.root.add(group);

  const add = (m) => { m.frustumCulled = false; group.add(m); return m; };
  const glow = (color, extra) => new T.MeshBasicMaterial(Object.assign({
    color, transparent: true, opacity: 0, depthWrite: false,
    blending: T.AdditiveBlending,
  }, extra || {}));

  /* ------------------------------------------------------------- the patch */
  // A fan of RINGS x SPOKES vertices sitting on the real ground. Vertex colours
  // fall from violet at the centre to black at the rim, which under additive
  // blending IS the falloff -- a flat disc at constant opacity reads as a
  // sticker, and this project has already paid for that once.
  const verts = 1 + RINGS * SPOKES;
  const pos = new Float32Array(verts * 3);
  const col = new Float32Array(verts * 3);
  const idx = [];
  for (let j = 0; j < SPOKES; j++) {
    idx.push(0, 1 + j, 1 + ((j + 1) % SPOKES));
  }
  for (let i = 0; i < RINGS - 1; i++) {
    for (let j = 0; j < SPOKES; j++) {
      const a = 1 + i * SPOKES + j;
      const b = 1 + i * SPOKES + ((j + 1) % SPOKES);
      const c = 1 + (i + 1) * SPOKES + j;
      const d = 1 + (i + 1) * SPOKES + ((j + 1) % SPOKES);
      idx.push(a, c, b, b, c, d);
    }
  }
  const patchGeo = new T.BufferGeometry();
  patchGeo.setAttribute('position', new T.BufferAttribute(pos, 3));
  patchGeo.setAttribute('color', new T.BufferAttribute(col, 3));
  patchGeo.setIndex(new T.BufferAttribute(new Uint16Array(idx), 1));
  const patchMat = new T.MeshBasicMaterial({
    vertexColors: true, transparent: true, opacity: 0, depthWrite: false,
    blending: T.AdditiveBlending, side: T.DoubleSide,
  });
  const patch = add(new T.Mesh(patchGeo, patchMat));

  /* ------------------------------------------------------------- the crack */
  // The hairline. It is there from the first second and it is the last thing
  // to go out, because it is the only part of this that was ever subtle.
  const crackGeo = new T.BoxGeometry(1, 0.06, 0.14);
  const crackMat = glow(LIME);
  const crack = add(new T.InstancedMesh(crackGeo, crackMat, CRACK));

  /* ------------------------------------------------------- what stands in it */
  // Two shapes, one per instance slot. Every shard is in both meshes and shows
  // in exactly one of them at a time, so a thing in the rift is never settled
  // on being any particular kind of thing.
  const shardMatA = glow(VIOLET);
  const shardMatB = glow(LIME);
  const shardA = add(new T.InstancedMesh(new T.OctahedronGeometry(0.5, 0), shardMatA, SHARDS));
  const shardB = add(new T.InstancedMesh(new T.BoxGeometry(0.7, 1.6, 0.7), shardMatB, SHARDS));

  /* -------------------------------------------------------------- the edge */
  const teethMat = glow(BONE);
  const teeth = add(new T.InstancedMesh(new T.ConeGeometry(0.22, 1.5, 4), teethMat, TEETH));

  /* --------------------------------------------------------- fixed scatter */
  const r = lcg(0x51f7);
  const shard = [];
  for (let i = 0; i < SHARDS; i++) {
    shard.push({
      a: r() * Math.PI * 2,
      d: Math.sqrt(r()) * 0.92,
      tiltAxis: r() * Math.PI * 2,
      tilt: 0.34 + r() * 0.62,        // no slope on this planet leans this far
      size: 0.7 + r() * 2.3,
      ph: r(),
      sp: 0.35 + r() * 1.1,
    });
  }
  const tooth = [];
  for (let i = 0; i < TEETH; i++) {
    tooth.push({ a: (i / TEETH) * Math.PI * 2 + r() * 0.06, size: 0.8 + r() * 0.9 });
  }
  const crackSeg = [];
  for (let i = 0; i < CRACK; i++) {
    crackSeg.push({ off: (i / (CRACK - 1)) * 2 - 1, wob: (r() - 0.5) * 0.16, len: 0.55 + r() * 0.6 });
  }

  return {
    group, patch, patchGeo, patchMat, pos, col,
    crack, crackGeo, crackMat, crackSeg,
    shardA, shardB, shardMatA, shardMatB, shard,
    teeth, teethMat, tooth,
    // per-frame scratch: nothing in update() may allocate
    P: new Float32Array(SHARDS * 3), Q: new Float32Array(SHARDS * 4),
    TP: new Float32Array(TEETH * 3), TQ: new Float32Array(TEETH * 4),
    CP: new Float32Array(CRACK * 3), CQ: new Float32Array(CRACK * 4),
    CS: new Float32Array(CRACK),
    _m: new T.Matrix4(), _q: new T.Quaternion(), _q2: new T.Quaternion(),
    _p: new T.Vector3(), _s: new T.Vector3(), _n: new T.Vector3(),
    _up: new T.Vector3(0, 1, 0), _ax: new T.Vector3(0, 1, 0),
    lastR: -1, lastSite: null, t: 0,
  };
}

/** Where on the sphere a point `de` east and `dn` north of the site lands. */
function offsetPoint(P, C, site, basis, de, dn) {
  const e = basis.east, n = basis.north;
  return P.norm(P.v(
    site.x * C.planet.radius + e.x * de + n.x * dn,
    site.y * C.planet.radius + e.y * de + n.y * dn,
    site.z * C.planet.radius + e.z * de + n.z * dn,
  ));
}
const pointAt = (P, C, site, b, ang, dist) =>
  offsetPoint(P, C, site, b, Math.cos(ang) * dist, Math.sin(ang) * dist);

const CRACK_BEARING = 0.9;        // the crack runs on one fixed bearing, so it
                                  // reads as a tear and not as a decorative ring

/**
 * Recompute everything that depends on the terrain. Throttled on the radius,
 * because heightAt is seven noise evaluations and this touches ~300 points.
 */
function reshape(self, ev, ctx, rad) {
  const T = ctx.THREE, P = ctx.P, C = ctx.C;
  const site = ev.dir;
  const b = P.basis(site);
  const up = self._up, q = self._q, q2 = self._q2, n = self._n;

  // the ground patch
  const centre = P.groundAt(site);
  const cr = 1 + LIFT / P.len(centre);
  self.pos[0] = centre.x * cr; self.pos[1] = centre.y * cr; self.pos[2] = centre.z * cr;
  self.col[0] = 0.55; self.col[1] = 0.12; self.col[2] = 0.95;
  for (let i = 0; i < RINGS; i++) {
    const f = (i + 1) / RINGS;
    for (let j = 0; j < SPOKES; j++) {
      const k = 1 + i * SPOKES + j;
      const d = pointAt(P, C, site, b, (j / SPOKES) * Math.PI * 2, f * rad);
      const gr = P.groundR(d) + LIFT;
      self.pos[k * 3] = d.x * gr; self.pos[k * 3 + 1] = d.y * gr; self.pos[k * 3 + 2] = d.z * gr;
      // violet in the middle, a lime bruise two thirds out, black at the rim
      const fade = (1 - f) * (1 - f);
      const bruise = Math.max(0, 1 - Math.abs(f - 0.66) * 4.5) * 0.5;
      self.col[k * 3] = 0.55 * fade + 0.70 * bruise;
      self.col[k * 3 + 1] = 0.12 * fade + 0.95 * bruise;
      self.col[k * 3 + 2] = 0.95 * fade + 0.18 * bruise;
    }
  }
  self.patchGeo.attributes.position.needsUpdate = true;
  self.patchGeo.attributes.color.needsUpdate = true;

  // the shards, leaning
  for (let i = 0; i < SHARDS; i++) {
    const sh = self.shard[i];
    const d = pointAt(P, C, site, b, sh.a, sh.d * rad);
    const gr = P.groundR(d) + sh.size * 0.35;
    self.P[i * 3] = d.x * gr; self.P[i * 3 + 1] = d.y * gr; self.P[i * 3 + 2] = d.z * gr;
    n.set(d.x, d.y, d.z);
    q.setFromUnitVectors(up, n);
    self._ax.set(Math.cos(sh.tiltAxis), 0, Math.sin(sh.tiltAxis));
    q2.setFromAxisAngle(self._ax, sh.tilt);
    q.multiply(q2);
    self.Q[i * 4] = q.x; self.Q[i * 4 + 1] = q.y; self.Q[i * 4 + 2] = q.z; self.Q[i * 4 + 3] = q.w;
  }

  // the edge: something to be afraid of crossing
  for (let i = 0; i < TEETH; i++) {
    const th = self.tooth[i];
    const d = pointAt(P, C, site, b, th.a, rad);
    const gr = P.groundR(d) + th.size * 0.75;
    self.TP[i * 3] = d.x * gr; self.TP[i * 3 + 1] = d.y * gr; self.TP[i * 3 + 2] = d.z * gr;
    n.set(d.x, d.y, d.z);
    q.setFromUnitVectors(up, n);
    self.TQ[i * 4] = q.x; self.TQ[i * 4 + 1] = q.y; self.TQ[i * 4 + 2] = q.z; self.TQ[i * 4 + 3] = q.w;
  }

  // the crack itself: a jagged chord, always a little longer than the tear
  const ce = Math.cos(CRACK_BEARING), sn = Math.sin(CRACK_BEARING);
  for (let i = 0; i < CRACK; i++) {
    const cs = self.crackSeg[i];
    const along = cs.off * rad * 1.05;
    const lat = cs.wob * rad;
    const d = offsetPoint(P, C, site, b, ce * along - sn * lat, sn * along + ce * lat);
    const gr = P.groundR(d) + LIFT * 2;
    self.CP[i * 3] = d.x * gr; self.CP[i * 3 + 1] = d.y * gr; self.CP[i * 3 + 2] = d.z * gr;
    n.set(d.x, d.y, d.z);
    q.setFromUnitVectors(up, n);
    self._ax.set(0, 1, 0);
    q2.setFromAxisAngle(self._ax, -CRACK_BEARING);
    q.multiply(q2);
    self.CQ[i * 4] = q.x; self.CQ[i * 4 + 1] = q.y; self.CQ[i * 4 + 2] = q.z; self.CQ[i * 4 + 3] = q.w;
    self.CS[i] = cs.len * Math.max(0.4, rad * 0.09);
  }

  self.lastR = rad;
  self.lastSite = site;
}

export function update(self, ev, w, dt, ctx) {
  if (!ev || !ev.dir) return;
  self.t += dt;

  const rad = Math.max(0.4, (ev.data && ev.data.r) || 0.4);
  if (self.lastSite !== ev.dir || Math.abs(rad - self.lastR) > 0.4) reshape(self, ev, ctx, rad);

  const phase = ev.def.phases[ev.phase].id;
  // 0 at a hairline, 1 when the interior is fully other. Everything the eye
  // reads scales off this one number, so the tear never looks half-drawn.
  const open = Math.max(0, Math.min(1,
    (rad - RIFT.hairline) / (RIFT.radius - RIFT.hairline)));
  const closing = phase === 'closes';

  const m = self._m, q = self._q, p = self._p, s = self._s;

  self.patchMat.opacity = 0.10 + open * 0.55;
  self.crackMat.opacity = 0.55 + Math.sin(self.t * 3.1) * 0.12 + open * 0.3;
  self.shardMatA.opacity = 0.35 + open * 0.5;
  self.shardMatB.opacity = 0.35 + open * 0.5;
  self.teethMat.opacity = open * (closing ? 0.35 : 0.7);

  // the crack
  self.crack.count = CRACK;
  for (let i = 0; i < CRACK; i++) {
    p.set(self.CP[i * 3], self.CP[i * 3 + 1], self.CP[i * 3 + 2]);
    q.set(self.CQ[i * 4], self.CQ[i * 4 + 1], self.CQ[i * 4 + 2], self.CQ[i * 4 + 3]);
    s.set(self.CS[i], 1, 0.5 + open * 3.5);
    m.compose(p, q, s);
    self.crack.setMatrixAt(i, m);
  }
  self.crack.instanceMatrix.needsUpdate = true;

  // the shards, flickering between two kinds of thing
  const n = Math.floor(SHARDS * open);
  self.shardA.count = n;
  self.shardB.count = n;
  const e = 0.08;
  for (let i = 0; i < n; i++) {
    const sh = self.shard[i];
    let ph = (self.t * sh.sp + sh.ph) % 1;
    if (ph < 0) ph += 1;
    let a;
    if (ph < 0.5 - e) a = 1;
    else if (ph < 0.5 + e) a = (0.5 + e - ph) / (2 * e);
    else if (ph < 1 - e) a = 0;
    else a = (ph - 1 + e) / (2 * e);

    p.set(self.P[i * 3], self.P[i * 3 + 1], self.P[i * 3 + 2]);
    q.set(self.Q[i * 4], self.Q[i * 4 + 1], self.Q[i * 4 + 2], self.Q[i * 4 + 3]);
    const k = sh.size * (closing ? 0.6 : 1);
    s.set(k * a, k * a, k * a);
    m.compose(p, q, s);
    self.shardA.setMatrixAt(i, m);
    const bk = k * (1 - a);
    s.set(bk * 0.6, bk, bk * 0.6);
    m.compose(p, q, s);
    self.shardB.setMatrixAt(i, m);
  }
  self.shardA.instanceMatrix.needsUpdate = true;
  self.shardB.instanceMatrix.needsUpdate = true;

  // the edge
  const tn = Math.floor(TEETH * open);
  self.teeth.count = tn;
  for (let i = 0; i < tn; i++) {
    const th = self.tooth[i];
    p.set(self.TP[i * 3], self.TP[i * 3 + 1], self.TP[i * 3 + 2]);
    q.set(self.TQ[i * 4], self.TQ[i * 4 + 1], self.TQ[i * 4 + 2], self.TQ[i * 4 + 3]);
    const k = th.size * (0.5 + open * 0.5);
    s.set(k, k, k);
    m.compose(p, q, s);
    self.teeth.setMatrixAt(i, m);
  }
  self.teeth.instanceMatrix.needsUpdate = true;
}

export function dispose(self, ctx) {
  if (!self) return;
  ctx.root.remove(self.group);
  for (const mesh of [self.patch, self.crack, self.shardA, self.shardB, self.teeth]) {
    self.group.remove(mesh);
    mesh.geometry.dispose();
    mesh.material.dispose();
  }
}
