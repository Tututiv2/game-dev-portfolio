// THE WORMHOLE — the render side.
//
// A ring standing on its edge on the ground with a churning disc inside it,
// throwing light onto the terrain around it. It reads from a long way off,
// because the player may be on the other side of the planet when it opens:
// a shaft of light rises out of the ring and clears the horizon of a world
// only 118 units across, and an additive sprite carries the glow past the
// distance where the ring itself is two pixels wide.
//
// It owns a Group and nothing else. It never changes the world. Everything that
// matters is in ev.data, which the sim owns and saves.

import { CONST } from '../../sim/events/wormhole.js';

export const id = 'wormhole';

const SEG = 30;            // blocks the ring is assembled from, one at a time
const R = CONST.ring;

/* ---------------------------------------------------------------- assets */

// A radial falloff, built in code. An additive sphere at constant opacity
// renders as a flat disc; a glow needs the falloff or it is a sticker.
function makeGlow(THREE) {
  const n = 64, data = new Uint8Array(n * n * 4);
  for (let y = 0; y < n; y++) {
    for (let x = 0; x < n; x++) {
      const dx = (x + 0.5) / n * 2 - 1, dy = (y + 0.5) / n * 2 - 1;
      const d = Math.min(1, Math.sqrt(dx * dx + dy * dy));
      const a = Math.pow(1 - d, 2.6);
      const i = (y * n + x) * 4;
      data[i] = 255; data[i + 1] = 255; data[i + 2] = 255;
      data[i + 3] = Math.round(a * 255);
    }
  }
  const t = new THREE.DataTexture(data, n, n, THREE.RGBAFormat);
  t.needsUpdate = true;
  return t;
}

// The churn. A flat disc of one colour does not churn however fast you spin it,
// so the turbulence is baked into vertex colours and four of these counter-
// rotate at different rates and radii.
function churnGeo(THREE, inner, outer, k) {
  const g = new THREE.RingGeometry(inner, outer, 44, 5);
  const pos = g.attributes.position;
  const col = new Float32Array(pos.count * 3);
  for (let i = 0; i < pos.count; i++) {
    const x = pos.getX(i), y = pos.getY(i);
    const a = Math.atan2(y, x), r = Math.sqrt(x * x + y * y);
    const v = 0.5 + 0.5 * Math.sin(a * (3 + k) + r * (1.7 + k * 0.6) + k * 2.1);
    const w = 0.35 + 0.65 * v * v;
    col[i * 3] = 0.45 * w + 0.25;
    col[i * 3 + 1] = 0.22 * w + 0.08;
    col[i * 3 + 2] = 0.95 * w + 0.05;
  }
  g.setAttribute('color', new THREE.BufferAttribute(col, 3));
  return g;
}

// Light pooled on the ground under the ring. Additive with a colour that falls
// to black at the rim, so it fades into the terrain instead of stamping a disc.
function poolGeo(THREE, r) {
  const g = new THREE.CircleGeometry(r, 40);
  const pos = g.attributes.position;
  const col = new Float32Array(pos.count * 3);
  for (let i = 0; i < pos.count; i++) {
    const d = Math.min(1, Math.hypot(pos.getX(i), pos.getY(i)) / r);
    const f = Math.pow(1 - d, 2.2);
    col[i * 3] = 0.55 * f; col[i * 3 + 1] = 0.22 * f; col[i * 3 + 2] = 1.0 * f;
  }
  g.setAttribute('color', new THREE.BufferAttribute(col, 3));
  return g;
}

/* ----------------------------------------------------------------- build */

export function build(ctx) {
  const THREE = ctx.THREE;
  const parent = ctx.root || ctx.scene;

  const group = new THREE.Group();
  group.frustumCulled = false;
  parent.add(group);

  const geos = [], mats = [], texs = [];
  const keep = (o, list) => { list.push(o); return o; };

  // --- the ring, as blocks, so it can be seen going up ---------------------
  const segGeo = keep(new THREE.BoxGeometry(0.95, 0.62, 1.15), geos);
  const segMat = keep(new THREE.MeshStandardMaterial({
    color: 0x3a4468, roughness: 0.45, metalness: 0.65,
    emissive: 0x160f36, emissiveIntensity: 1,
  }), mats);
  const segs = new THREE.InstancedMesh(segGeo, segMat, SEG);
  segs.frustumCulled = false;
  {
    const m = new THREE.Matrix4(), q = new THREE.Quaternion();
    const p = new THREE.Vector3(), s = new THREE.Vector3(1, 1, 1);
    const axis = new THREE.Vector3(0, 0, 1);
    for (let i = 0; i < SEG; i++) {
      const a = (i / SEG) * Math.PI * 2;
      p.set(Math.cos(a) * R, Math.sin(a) * R, 0);
      q.setFromAxisAngle(axis, a);
      m.compose(p, q, s);
      segs.setMatrixAt(i, m);
    }
    segs.instanceMatrix.needsUpdate = true;
  }
  group.add(segs);

  // --- the ring proper, once it closes -------------------------------------
  const torusMat = keep(new THREE.MeshStandardMaterial({
    color: 0x6a48d8, emissive: 0x7b3cff, emissiveIntensity: 0.5,
    roughness: 0.3, metalness: 0.5,
  }), mats);
  const torus = new THREE.Mesh(keep(new THREE.TorusGeometry(R, 0.44, 8, 54), geos), torusMat);
  torus.frustumCulled = false;
  torus.visible = false;
  group.add(torus);

  // --- the churn -----------------------------------------------------------
  const discs = [];
  for (let k = 0; k < 4; k++) {
    const inner = R * (0.02 + k * 0.16);
    const outer = R * (0.97 - k * 0.13);
    const mat = keep(new THREE.MeshBasicMaterial({
      vertexColors: true, transparent: true, opacity: 0,
      blending: THREE.AdditiveBlending, depthWrite: false,
      side: THREE.DoubleSide, fog: false,
    }), mats);
    const mesh = new THREE.Mesh(keep(churnGeo(THREE, inner, outer, k), geos), mat);
    mesh.frustumCulled = false;
    mesh.position.z = (k - 1.5) * 0.06;      // never coplanar, or they z-fight
    group.add(mesh);
    discs.push({ mesh, mat, spin: (k % 2 ? -1 : 1) * (0.55 + k * 0.42) });
  }

  // --- light on the ground, and light in the air ---------------------------
  const glow = keep(makeGlow(THREE), texs);

  const haloMat = keep(new THREE.SpriteMaterial({
    map: glow, color: 0x9b5cff, transparent: true, opacity: 0,
    blending: THREE.AdditiveBlending, depthWrite: false, fog: false,
  }), mats);
  const halo = new THREE.Sprite(haloMat);
  halo.scale.set(R * 5, R * 5, 1);
  group.add(halo);

  const coreMat = keep(new THREE.SpriteMaterial({
    map: glow, color: 0xe8dcff, transparent: true, opacity: 0,
    blending: THREE.AdditiveBlending, depthWrite: false, fog: false,
  }), mats);
  const core = new THREE.Sprite(coreMat);
  core.scale.set(R * 1.5, R * 1.5, 1);
  group.add(core);

  const poolMat = keep(new THREE.MeshBasicMaterial({
    vertexColors: true, transparent: true, opacity: 0,
    blending: THREE.AdditiveBlending, depthWrite: false,
    side: THREE.DoubleSide, fog: false,
  }), mats);
  const pool = new THREE.Mesh(keep(poolGeo(THREE, R * 1.5), geos), poolMat);
  pool.frustumCulled = false;
  pool.rotation.x = -Math.PI / 2;              // lie flat on the tangent plane
  pool.position.y = -(R + 0.35) + 0.35;
  group.add(pool);

  // The horizon on a planet this small is close. Without something tall, an
  // event you are meant to notice from anywhere is invisible from anywhere.
  const shaftMat = keep(new THREE.MeshBasicMaterial({
    color: 0x8f57ff, transparent: true, opacity: 0,
    blending: THREE.AdditiveBlending, depthWrite: false,
    side: THREE.DoubleSide, fog: false,
  }), mats);
  const shaft = new THREE.Mesh(
    keep(new THREE.CylinderGeometry(R * 0.30, R * 0.62, 90, 12, 1, true), geos), shaftMat);
  shaft.frustumCulled = false;
  shaft.position.y = 45;
  group.add(shaft);

  const light = new THREE.PointLight(0x9a5cff, 0, 120, 2);
  group.add(light);

  return {
    group, segs, segMat, torus, torusMat, discs, halo, haloMat, core, coreMat,
    pool, poolMat, shaft, shaftMat, light,
    geos, mats, texs,
    at: null,                                   // the site currently drawn
    m: new THREE.Matrix4(),
    ax: new THREE.Vector3(), ay: new THREE.Vector3(), az: new THREE.Vector3(),
    spin: 0,
  };
}

/* ---------------------------------------------------------------- update */

// Stand the ring on the ground, on its edge, once. Only redone if the sim moves
// the site -- which it does exactly once, in start().
function site(self, ev, ctx) {
  const d = ev.dir;
  if (self.at && self.at.x === d.x && self.at.y === d.y && self.at.z === d.z) return;
  self.at = { x: d.x, y: d.y, z: d.z };
  const P = ctx.P;
  const b = P.basis(d);
  // local X north, local Y up, local Z east: the ring's plane then contains the
  // vertical, which is what makes it stand rather than lie down.
  self.ax.set(b.north.x, b.north.y, b.north.z);
  self.ay.set(b.up.x, b.up.y, b.up.z);
  self.az.set(b.east.x, b.east.y, b.east.z);
  self.m.makeBasis(self.ax, self.ay, self.az);
  self.group.quaternion.setFromRotationMatrix(self.m);
  const gr = P.groundR(d) + R + 0.35;
  self.group.position.set(d.x * gr, d.y * gr, d.z * gr);
}

function phaseOf(ev) {
  const p = ev.def && ev.def.phases[ev.phase];
  return p ? p.id : 'ring';
}

function progressOf(ev, w) {
  const ps = ev.def.phases;
  const cur = ps[ev.phase], next = ps[ev.phase + 1];
  const dur = next ? next.at : (cur && cur.lasts) || 1;
  if (!dur) return 1;
  return Math.min(1, Math.max(0, (w.t - ev.phaseAt) / dur));
}

export function update(self, ev, w, dt, ctx) {
  if (!ev || !ev.dir) { self.group.visible = false; return; }
  self.group.visible = true;
  site(self, ev, ctx);

  const phase = phaseOf(ev);
  const t = progressOf(ev, w);

  // how hard the hole is working, 0..1 -- one number drives everything below
  let open = 0, built = 1, dying = 0;
  if (phase === 'ring') { built = t; open = 0; }
  else if (phase === 'charge') { open = t * 0.55; }
  else if (phase === 'open') { open = 1; }
  else { open = 1 - t; dying = t; }

  // arrivals punch the light up for a moment; a value that carries information
  // is never left to a transition to interpolate.
  const since = ev.data && ev.data.flash ? w.t - ev.data.flash : 999;
  const flash = since < 1.8 ? Math.pow(1 - since / 1.8, 2) : 0;

  const pulse = 0.85 + 0.15 * Math.sin(w.t * 2.3) + 0.10 * Math.sin(w.t * 7.1);
  const heat = open * pulse + flash * 0.8;

  self.segs.count = phase === 'ring' ? Math.ceil(built * SEG) : SEG;
  self.segMat.emissiveIntensity = 0.6 + heat * 2.6;

  self.torus.visible = phase !== 'ring';
  self.torusMat.emissiveIntensity = 0.4 + heat * 3.4;

  self.spin += dt * (0.25 + open * 2.4 + flash);
  for (let i = 0; i < self.discs.length; i++) {
    const d = self.discs[i];
    d.mesh.rotation.z = self.spin * d.spin;
    const s = Math.max(0.001, open * (1 - dying * 0.92));
    d.mesh.scale.set(s, s, 1);
    d.mat.opacity = Math.max(0, Math.min(1, (0.28 + i * 0.10) * heat * (1 - dying)));
  }

  self.haloMat.opacity = Math.max(0, Math.min(1, 0.22 + heat * 0.55));
  const hs = R * (3.2 + heat * 2.4);
  self.halo.scale.set(hs, hs, 1);

  self.coreMat.opacity = Math.max(0, Math.min(1, heat * 0.9 * (1 - dying)));
  const cs = R * (0.6 + heat * 1.1) * (1 - dying * 0.95);
  self.core.scale.set(Math.max(0.001, cs), Math.max(0.001, cs), 1);

  self.poolMat.opacity = Math.max(0, Math.min(1, 0.10 + heat * 0.65));
  self.shaftMat.opacity = Math.max(0, Math.min(1, (open * 0.30 + flash * 0.35) * (1 - dying * 0.8)));

  // The collapse is the loudest moment, not the quietest: a hole that shuts
  // quietly reads as a bug.
  const spike = dying > 0 ? Math.pow(1 - dying, 0.5) * 1.6 : 0;
  self.light.intensity = 6 * built + heat * 60 + spike * 40;
  self.light.distance = 60 + heat * 90;
}

/* --------------------------------------------------------------- dispose */

export function dispose(self, ctx) {
  if (self.group.parent) self.group.parent.remove(self.group);
  for (const g of self.geos) if (g.dispose) g.dispose();
  for (const m of self.mats) if (m.dispose) m.dispose();
  for (const t of self.texs) if (t.dispose) t.dispose();
  self.geos.length = 0; self.mats.length = 0; self.texs.length = 0;
}
