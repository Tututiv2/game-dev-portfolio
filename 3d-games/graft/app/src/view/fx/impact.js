// THE IMPACT — the render side. Reads ev.data and changes nothing.
//
// Two things to draw. The rocks still in the sky, which are the whole point:
// every strike is visible for IMPACT.fall seconds before it arrives, so the
// player gets to watch it coming and decide whether to be somewhere else. And
// the strikes themselves — a shockwave that expands once, and a scorch that
// cools over the next minute.
//
// The LASTING crater is not drawn here. It is a catalog object, so the main
// instanced bank draws it and it is still there after this module is disposed.

import * as PM from '../../sim/planet.js';
import { C as CM } from '../../sim/const.js';
import { IMPACT } from '../../sim/events/impact.js';

export const id = 'impact';

const MET_CAP = IMPACT.maxLive;
const CRA_CAP = 64;
const SHOCK = 1.4;          // seconds the shockwave takes to run out
const COOL = 75;            // seconds the scorch stays visibly hot

/** A radial falloff, in case the host does not hand us one. An additive sphere
    at constant opacity renders as a flat disc; a glow needs a gradient. */
function radialTexture(THREE) {
  const n = 64, data = new Uint8Array(n * n * 4);
  for (let y = 0; y < n; y++) {
    for (let x = 0; x < n; x++) {
      const dx = (x + 0.5) / n * 2 - 1, dy = (y + 0.5) / n * 2 - 1;
      const d = Math.min(1, Math.hypot(dx, dy));
      const a = Math.pow(1 - d, 2.6);
      const i = (y * n + x) * 4;
      data[i] = 255; data[i + 1] = 255; data[i + 2] = 255;
      data[i + 3] = Math.round(a * 255);
    }
  }
  const tex = new THREE.DataTexture(data, n, n);
  tex.needsUpdate = true;
  return tex;
}

export function build(ctx) {
  const THREE = ctx.THREE;
  const P = ctx.P || PM;
  const C = ctx.C || CM;
  const group = new THREE.Group();
  ctx.root.add(group);

  const glowTex = ctx.glowTexture ? ctx.glowTexture() : radialTexture(THREE);

  const mk = (geo, mat, cap) => {
    const m = new THREE.InstancedMesh(geo, mat, cap);
    m.frustumCulled = false;
    m.count = 0;
    group.add(m);
    return m;
  };

  // --- the rocks
  const headGeo = new THREE.IcosahedronGeometry(1, 0);
  const headMat = new THREE.MeshBasicMaterial({ color: 0xffe8bc, fog: false });
  const head = mk(headGeo, headMat, MET_CAP);

  // the tail's tip trails BEHIND the head, so the cone is turned to point back
  // along the direction of travel rather than along it
  const tailGeo = new THREE.ConeGeometry(0.5, 1, 7, 1, true);
  const tailMat = new THREE.MeshBasicMaterial({
    color: 0xff8a3c, transparent: true, opacity: 0.5, depthWrite: false,
    blending: THREE.AdditiveBlending, side: THREE.DoubleSide, fog: false,
  });
  const tail = mk(tailGeo, tailMat, MET_CAP);

  const glowGeo = new THREE.PlaneGeometry(1, 1);
  const glowMat = new THREE.MeshBasicMaterial({
    map: glowTex, color: 0xffc890, transparent: true, depthWrite: false,
    blending: THREE.AdditiveBlending, fog: false,
  });
  const glow = mk(glowGeo, glowMat, MET_CAP);

  // --- the ground
  // The scorch is built at its real size with the planet's curvature baked into
  // it, so it lies IN the bowl instead of hovering over it as a flat decal.
  const scorchGeo = new THREE.RingGeometry(IMPACT.crater * 0.18, IMPACT.crater, 30, 2);
  {
    const pos = scorchGeo.attributes.position;
    for (let i = 0; i < pos.count; i++) {
      const x = pos.getX(i), y = pos.getY(i);
      pos.setZ(i, -(x * x + y * y) / (2 * C.planet.radius));
    }
    scorchGeo.computeVertexNormals();
  }
  const scorchMat = new THREE.MeshBasicMaterial({
    color: 0xff5a1a, transparent: true, opacity: 0.85, depthWrite: false,
    blending: THREE.AdditiveBlending, side: THREE.DoubleSide, fog: false,
  });
  const scorch = mk(scorchGeo, scorchMat, CRA_CAP);

  const shockGeo = new THREE.RingGeometry(0.86, 1, 40, 1);
  const shockMat = new THREE.MeshBasicMaterial({
    color: 0xffd8a0, transparent: true, opacity: 0.9, depthWrite: false,
    blending: THREE.AdditiveBlending, side: THREE.DoubleSide, fog: false,
  });
  const shock = mk(shockGeo, shockMat, CRA_CAP);

  // One light, moved to whichever strike is newest. A light per crater would be
  // a dozen shadowless point lights for an effect that lasts a second.
  const flash = new THREE.PointLight(0xffa050, 0, 90, 2);
  group.add(flash);

  return {
    P, C, group, head, tail, glow, scorch, shock, flash,
    mats: [headMat, tailMat, glowMat, scorchMat, shockMat],
    geos: [headGeo, tailGeo, glowGeo, scorchGeo, shockGeo],
    ownTex: ctx.glowTexture ? null : glowTex,
    // nothing below is allocated again
    m: new THREE.Matrix4(),
    q: new THREE.Quaternion(),
    q2: new THREE.Quaternion(),
    a: new THREE.Vector3(),
    b: new THREE.Vector3(),
    c: new THREE.Vector3(),
    s: new THREE.Vector3(),
    n2: new THREE.Vector3(),
    col: new THREE.Color(),
    ed: { x: 0, y: 0, z: 0 },      // plain scratch dirs for the pure planet maths
    td: { x: 0, y: 0, z: 0 },
    UP: new THREE.Vector3(0, 1, 0),
    FWD: new THREE.Vector3(0, 0, 1),
  };
}

/** Where a rock is, 0..1 through its fall. `out` is written, not returned. */
function flightPoint(self, m, u, out) {
  const P = self.P, C = self.C;
  const from = self.a, to = self.b;
  const entry = self.ed, target = self.td;
  entry.x = m.ex; entry.y = m.ey; entry.z = m.ez;
  target.x = m.tx; target.y = m.ty; target.z = m.tz;
  const rTop = C.planet.radius + P.heightAt(entry) + IMPACT.high;
  from.set(entry.x * rTop, entry.y * rTop, entry.z * rTop);
  // a passer never touches down: it burns out still well above the ground
  const rEnd = m.s ? P.groundR(target) : C.planet.radius + IMPACT.burn;
  to.set(target.x * rEnd, target.y * rEnd, target.z * rEnd);
  out.copy(from).lerp(to, u);
}

export function update(self, ev, w, dt, ctx) {
  const d = ev && ev.data;
  const camQ = ctx.camera ? ctx.camera.quaternion : self.q2;
  const P = self.P;

  let nMet = 0;
  if (d && d.m) {
    for (const m of d.m) {
      if (nMet >= MET_CAP) break;
      const span = (m.tl - m.t0) || 1;
      const u = Math.max(0, Math.min(1, (w.t - m.t0) / span));

      flightPoint(self, m, u, self.c);
      const pos = self.c;
      // direction of travel, from the ends of the flight (self.a/self.b are set)
      self.s.copy(self.b).sub(self.a);
      const flen = self.s.length() || 1;
      self.s.multiplyScalar(1 / flen);

      // it brightens as it comes down through thicker air
      const hot = 0.35 + 0.65 * u;
      const size = 1.6 + 1.4 * u;

      self.q.setFromUnitVectors(self.UP, self.n2.copy(self.s).negate());
      const tailLen = 9 + 13 * u;
      self.m.compose(
        self.b.copy(pos).addScaledVector(self.s, -tailLen * 0.5),
        self.q,
        self.a.set(size * 0.9, tailLen, size * 0.9),
      );
      self.tail.setMatrixAt(nMet, self.m);
      self.tail.setColorAt(nMet, self.col.setRGB(hot, hot * 0.55, hot * 0.22));

      self.m.compose(pos, camQ, self.a.set(size * 9, size * 9, 1));
      self.glow.setMatrixAt(nMet, self.m);
      self.glow.setColorAt(nMet, self.col.setRGB(hot, hot * 0.7, hot * 0.45));

      self.m.compose(pos, camQ, self.a.set(size, size, size));
      self.head.setMatrixAt(nMet, self.m);
      self.head.setColorAt(nMet, self.col.setRGB(1, 0.9 * hot + 0.1, 0.6 * hot));
      nMet++;
    }
  }

  let nSco = 0, nSho = 0, lit = 0;
  if (d && d.c) {
    for (const cr of d.c) {
      const age = w.t - cr.t;
      if (age < 0) continue;
      self.s.set(cr.x, cr.y, cr.z);
      self.td.x = cr.x; self.td.y = cr.y; self.td.z = cr.z;
      const gr = P.groundR(self.td);
      self.a.copy(self.s).multiplyScalar(gr + 0.35);
      self.q.setFromUnitVectors(self.FWD, self.s);

      if (!cr.sea && age < COOL && nSco < CRA_CAP) {
        const heat = Math.pow(1 - age / COOL, 1.7);
        self.m.compose(self.a, self.q, self.b.set(1, 1, 1));
        self.scorch.setMatrixAt(nSco, self.m);
        self.scorch.setColorAt(nSco, self.col.setRGB(heat, heat * 0.30, heat * 0.10));
        nSco++;
      }
      if (age < SHOCK && nSho < CRA_CAP) {
        const k = age / SHOCK;
        const r = IMPACT.crater * (0.3 + 2.1 * k);
        self.m.compose(self.a, self.q, self.b.set(r, r, 1));
        self.shock.setMatrixAt(nSho, self.m);
        const f = 1 - k;
        self.shock.setColorAt(nSho, self.col.setRGB(f, f * 0.85, f * 0.6));
        nSho++;
        // the light belongs to the FRESHEST strike, not the last one in the list
        const glare = age < 0.9 ? 1 - age / 0.9 : 0;
        if (glare > lit) { lit = glare; self.flash.position.copy(self.a); }
      }
    }
  }

  self.head.count = nMet;
  self.tail.count = nMet;
  self.glow.count = nMet;
  self.scorch.count = nSco;
  self.shock.count = nSho;
  for (const mesh of [self.head, self.tail, self.glow, self.scorch, self.shock]) {
    mesh.instanceMatrix.needsUpdate = true;
    if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
  }
  self.flash.intensity = 260 * lit * lit;
}

export function dispose(self, ctx) {
  if (self.group.parent) self.group.parent.remove(self.group);
  for (const g of self.geos) g.dispose();
  for (const m of self.mats) m.dispose();
  if (self.ownTex) self.ownTex.dispose();
}
