// THE BLACK HOLE — the render side.
//
// It is the one thing in the sky that is DARKER than space, so it cannot be
// drawn the way a star is. Four pieces, and each does a different job:
//
//   disc    an unlit black sphere. Opaque, so it puts out the stars behind it.
//   ring    a thin bright torus billboarded at the camera: the lensed edge.
//   glow    a radial-gradient sprite, additive. A constant-opacity additive
//           sphere renders as a flat disc -- this portfolio has paid for that
//           one already -- so the falloff is baked into a texture.
//   veil    the same sprite inverted -- opaque black fading to nothing -- which
//           is what makes the sky visibly wrong: it puts out the stars around
//           it, so the hole is darker than space instead of brighter than it.
//
// Plus an accretion swirl of motes, a tidal bulge dragged across the sea, and
// the scars, which are the only part that outlives the event.
//
// This module reads the world and changes nothing. Every number it draws comes
// from the sim's own trackDir / strength / discRadius, so what hangs overhead
// and what is being torn apart can never drift apart.

import { BH, trackDir, strength, discRadius } from '../../sim/events/blackhole.js';

export const id = 'blackhole';

const MOTES = 420;
const CAP_RINGS = 9;         // tide mesh: rings x segments
const CAP_SEGS = 28;
const CAP_ANGLE = 0.30;      // radians of sphere the tidal bulge covers
const SCAR_CAP = 260;

/**
 * A radial gradient sprite. `veil` gives the opposite: opaque black at the
 * centre fading to nothing.
 *
 * The veil is ALPHA, not multiply blending, and that is the whole reason it
 * works. MultiplyBlending in three is blendSrc ZERO / blendDst SRC_COLOR, so
 * the result is dst * src.rgb and material.opacity never enters it -- the veil
 * would have been at full darkness from the first frame and could not fade in.
 * Compositing black over the stars puts them out just as dead, and it fades.
 */
function gradientTexture(THREE, veil) {
  const n = 128;
  const cv = document.createElement('canvas');
  cv.width = cv.height = n;
  const g = cv.getContext('2d');
  const grd = g.createRadialGradient(n / 2, n / 2, 0, n / 2, n / 2, n / 2);
  if (veil) {
    grd.addColorStop(0.00, 'rgba(0,0,0,1)');
    grd.addColorStop(0.42, 'rgba(2,0,6,0.85)');
    grd.addColorStop(0.74, 'rgba(6,2,12,0.34)');
    grd.addColorStop(1.00, 'rgba(6,2,12,0)');
    g.clearRect(0, 0, n, n);
  } else {
    grd.addColorStop(0.00, '#ffffff');
    grd.addColorStop(0.18, '#ffd9a0');
    grd.addColorStop(0.45, '#a2582b');
    grd.addColorStop(1.00, '#000000');
  }
  g.fillStyle = grd;
  g.fillRect(0, 0, n, n);
  const t = new THREE.CanvasTexture(cv);
  t.needsUpdate = true;
  return t;
}

export function build(ctx) {
  const THREE = ctx.THREE;
  const sky = ctx.sky || ctx.scene;
  const root = ctx.root || ctx.scene;
  const P = ctx.P, C = ctx.C;

  /* ------------------------------------------------------------ the sky -- */
  const hole = new THREE.Group();
  hole.frustumCulled = false;
  sky.add(hole);

  const disc = new THREE.Mesh(
    new THREE.SphereGeometry(1, 24, 16),
    new THREE.MeshBasicMaterial({ color: 0x000000, fog: false }));
  disc.frustumCulled = false;
  hole.add(disc);

  const ringMat = new THREE.MeshBasicMaterial({
    color: 0xffe6b4, fog: false, transparent: true, opacity: 0.9,
    blending: THREE.AdditiveBlending, depthWrite: false,
  });
  const ring = new THREE.Mesh(new THREE.TorusGeometry(1, 0.035, 6, 72), ringMat);
  ring.frustumCulled = false;
  ring.renderOrder = 3;
  hole.add(ring);

  const glowTex = ctx.glowTexture ? ctx.glowTexture() : gradientTexture(THREE, false);
  const glowMat = new THREE.MeshBasicMaterial({
    map: glowTex, fog: false, transparent: true, opacity: 0.0,
    blending: THREE.AdditiveBlending, depthWrite: false,
  });
  const glow = new THREE.Mesh(new THREE.PlaneGeometry(2, 2), glowMat);
  glow.frustumCulled = false;
  glow.renderOrder = 2;
  hole.add(glow);

  const veilTex = gradientTexture(THREE, true);
  const veilMat = new THREE.MeshBasicMaterial({
    map: veilTex, fog: false, transparent: true, opacity: 0.0, depthWrite: false,
  });
  const veil = new THREE.Mesh(new THREE.PlaneGeometry(2, 2), veilMat);
  veil.frustumCulled = false;
  veil.renderOrder = 1;               // take the light out before adding any
  hole.add(veil);

  // accretion: a tilted disc of motes, so it reads as a disc and not a halo
  const swirl = new THREE.Group();
  swirl.rotation.set(0.62, 0.0, 0.35);
  hole.add(swirl);
  const mPos = new Float32Array(MOTES * 3);
  const mR = new Float32Array(MOTES);
  const mA = new Float32Array(MOTES);
  const mS = new Float32Array(MOTES);
  {
    let s = 1337;
    const rnd = () => { s = (Math.imul(s, 1103515245) + 12345) & 0x7fffffff; return s / 0x7fffffff; };
    for (let i = 0; i < MOTES; i++) {
      mR[i] = 1.25 + Math.pow(rnd(), 0.6) * 2.4;
      mA[i] = rnd() * Math.PI * 2;
      mS[i] = (0.9 + rnd() * 1.8) / mR[i];        // inner motes go round faster
      mPos[i * 3 + 2] = (rnd() - 0.5) * 0.20;
    }
  }
  const mGeo = new THREE.BufferGeometry();
  mGeo.setAttribute('position', new THREE.BufferAttribute(mPos, 3));
  const mMat = new THREE.PointsMaterial({
    color: 0xffc887, size: 2.4, sizeAttenuation: false, fog: false,
    transparent: true, opacity: 0.0, blending: THREE.AdditiveBlending, depthWrite: false,
  });
  const motes = new THREE.Points(mGeo, mMat);
  motes.frustumCulled = false;
  swirl.add(motes);

  /* ----------------------------------------------------------- the sea -- */
  // A spherical cap of water, lifted toward whatever is overhead. Its own local
  // +Y is aimed at the ground track, so only the RADII change per frame.
  const capDirs = [];
  const capIdx = [];
  for (let r = 0; r <= CAP_RINGS; r++) {
    const a = (r / CAP_RINGS) * CAP_ANGLE;
    for (let sgi = 0; sgi < CAP_SEGS; sgi++) {
      const th = (sgi / CAP_SEGS) * Math.PI * 2;
      capDirs.push(Math.sin(a) * Math.cos(th), Math.cos(a), Math.sin(a) * Math.sin(th));
    }
  }
  for (let r = 0; r < CAP_RINGS; r++) {
    for (let sgi = 0; sgi < CAP_SEGS; sgi++) {
      const a0 = r * CAP_SEGS + sgi;
      const a1 = r * CAP_SEGS + ((sgi + 1) % CAP_SEGS);
      const b0 = a0 + CAP_SEGS, b1 = a1 + CAP_SEGS;
      capIdx.push(a0, b0, b1, a0, b1, a1);
    }
  }
  const capUnit = new Float32Array(capDirs);
  const capPos = new Float32Array(capDirs.length);
  const tideGeo = new THREE.BufferGeometry();
  tideGeo.setAttribute('position', new THREE.BufferAttribute(capPos, 3));
  tideGeo.setIndex(capIdx);
  const tideMat = new THREE.MeshBasicMaterial({
    color: 0x7fd2f5, transparent: true, opacity: 0.0,
    depthWrite: false, side: THREE.DoubleSide,
  });
  const tide = new THREE.Mesh(tideGeo, tideMat);
  tide.frustumCulled = false;
  tide.visible = false;
  root.add(tide);

  /* --------------------------------------------------------- the scars -- */
  // The only part that is still there tomorrow -- so it survives dispose(), and
  // a SECOND black hole must therefore find the meshes the first one left
  // rather than stack another pair on top of them.
  let scars = root.getObjectByName('bh-scars');
  let rims = root.getObjectByName('bh-scar-rims');
  if (!scars) {
    scars = new THREE.InstancedMesh(new THREE.CircleGeometry(1, 14),
      new THREE.MeshBasicMaterial({
        color: 0x0b0710, transparent: true, opacity: 0.92, depthWrite: false,
        polygonOffset: true, polygonOffsetFactor: -2, polygonOffsetUnits: -2,
      }), SCAR_CAP);
    scars.name = 'bh-scars';
    scars.frustumCulled = false;
    scars.count = 0;
    root.add(scars);
  }
  if (!rims) {
    rims = new THREE.InstancedMesh(new THREE.RingGeometry(0.86, 1.0, 16),
      new THREE.MeshBasicMaterial({
        color: 0x8a4cff, transparent: true, opacity: 0.55,
        blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide,
      }), SCAR_CAP);
    rims.name = 'bh-scar-rims';
    rims.frustumCulled = false;
    rims.count = 0;
    root.add(rims);
  }

  return {
    THREE, P, C, sky, root,
    hole, disc, ring, ringMat, glow, glowMat, veil, veilMat,
    glowTex, veilTex, ownGlow: !ctx.glowTexture,
    swirl, motes, mGeo, mMat, mPos, mR, mA, mS,
    tide, tideGeo, tideMat, capUnit, capPos,
    scars, rims,
    scarKey: -1,
    // scratch, so nothing allocates in a frame
    _v: new THREE.Vector3(), _v2: new THREE.Vector3(), _q: new THREE.Quaternion(),
    _m: new THREE.Matrix4(), _s: new THREE.Vector3(1, 1, 1),
    _up: new THREE.Vector3(0, 1, 0), _fwd: new THREE.Vector3(0, 0, 1),
  };
}

export function update(self, ev, w, dt, ctx) {
  const P = self.P, C = self.C;
  const cam = ctx.camera;

  const track = trackDir(w, ev);
  const s = strength(w, ev);
  const R = discRadius(w, ev);

  /* ------------------------------------------------------------ the sky -- */
  self.hole.position.set(track.x * BH.skyDist, track.y * BH.skyDist, track.z * BH.skyDist);
  self.disc.scale.setScalar(R);

  // The lensed edge is a hair outside the shadow, and it sharpens as it swells.
  self.ring.scale.setScalar(R * 1.07);
  self.ringMat.opacity = 0.35 + 0.55 * s;
  self.glow.scale.setScalar(R * 6.5);
  self.glowMat.opacity = Math.min(0.85, 0.10 + s * 0.8);
  self.veil.scale.setScalar(R * 13);
  self.veilMat.opacity = Math.min(0.92, s * 1.05);

  if (cam) {
    self.ring.lookAt(cam.position);
    self.glow.lookAt(cam.position);
    self.veil.lookAt(cam.position);
  }

  // accretion. Written in place: a Points cloud that allocated per frame would
  // cost more than everything else here put together.
  const spin = dt * (0.35 + s * 2.6);
  const rs = R * 1.15;
  for (let i = 0; i < MOTES; i++) {
    const a = (self.mA[i] += self.mS[i] * spin);
    const r = self.mR[i] * rs;
    self.mPos[i * 3] = Math.cos(a) * r;
    self.mPos[i * 3 + 1] = Math.sin(a) * r;
    // z is the disc's thickness and was set once, at build
  }
  self.mGeo.attributes.position.needsUpdate = true;
  self.mMat.opacity = Math.max(0, s - 0.12) * 0.9;
  self.mMat.size = 1.4 + s * 2.6;

  /* ----------------------------------------------------------- the sea -- */
  // Only over water. On land the bulge would be inside the hill anyway, but
  // showing it there would read as the ground rising, which is the next event's
  // job and not this one's.
  const seaR = C.planet.radius + C.planet.sea;
  const overWater = P.heightAt(track) < C.planet.sea + 1.5;
  self.tide.visible = overWater && s > 0.22;
  if (self.tide.visible) {
    self._v.set(track.x, track.y, track.z);
    self._q.setFromUnitVectors(self._up, self._v);
    self.tide.quaternion.copy(self._q);
    const lift = 9.5 * Math.max(0, s - 0.2);
    const n = self.capUnit.length / 3;
    for (let i = 0; i < n; i++) {
      const uy = self.capUnit[i * 3 + 1];              // cos of the angle from the peak
      const f = Math.max(0, (uy - Math.cos(CAP_ANGLE)) / (1 - Math.cos(CAP_ANGLE)));
      const rr = seaR + 0.06 + lift * f * f;
      self.capPos[i * 3] = self.capUnit[i * 3] * rr;
      self.capPos[i * 3 + 1] = uy * rr;
      self.capPos[i * 3 + 2] = self.capUnit[i * 3 + 2] * rr;
    }
    self.tideGeo.attributes.position.needsUpdate = true;
    self.tideMat.opacity = Math.min(0.45, 0.10 + s * 0.4);
  }

  /* --------------------------------------------------------- the scars -- */
  refreshScars(self, w);
}

/** Scars only change when the ground is torn, so this rebuilds on a key rather
    than every frame. The last scar WIDENS in place, so its radius is in the key. */
function refreshScars(self, w) {
  const list = w.scars || [];
  const last = list[list.length - 1];
  const key = list.length * 1000 + (last ? last[3] : 0);
  if (key === self.scarKey) return;
  self.scarKey = key;

  const P = self.P;
  const n = Math.min(list.length, SCAR_CAP);
  for (let i = 0; i < n; i++) {
    const sc = list[i];
    const dir = P.norm(P.v(sc[0], sc[1], sc[2]));
    const gr = P.groundR(dir) + 0.06;
    self._v.set(dir.x * gr, dir.y * gr, dir.z * gr);
    self._v2.set(dir.x, dir.y, dir.z);
    self._q.setFromUnitVectors(self._fwd, self._v2);   // a circle's normal is +Z
    self._s.setScalar(sc[3]);
    self._m.compose(self._v, self._q, self._s);
    self.scars.setMatrixAt(i, self._m);
    self.rims.setMatrixAt(i, self._m);
  }
  self.scars.count = n;
  self.rims.count = n;
  self.scars.instanceMatrix.needsUpdate = true;
  self.rims.instanceMatrix.needsUpdate = true;
}

export function dispose(self, ctx) {
  const kill = (o) => { if (o && o.parent) o.parent.remove(o); };
  kill(self.hole);
  kill(self.tide);
  // The scars stay, meshes and all. They are the whole point of a scar.
  for (const g of [self.mGeo, self.tideGeo]) if (g) g.dispose();
  for (const m of [self.ringMat, self.glowMat, self.veilMat, self.mMat, self.tideMat]) {
    if (m) m.dispose();
  }
  if (self.disc) { self.disc.geometry.dispose(); self.disc.material.dispose(); }
  if (self.ring) self.ring.geometry.dispose();
  if (self.glow) self.glow.geometry.dispose();
  if (self.veil) self.veil.geometry.dispose();
  if (self.veilTex) self.veilTex.dispose();
  if (self.ownGlow && self.glowTex) self.glowTex.dispose();
}

/* ------------------------------------------------- SCARS, FOR THE INTEGRATOR
   dispose() deliberately leaves the scar meshes in the scene: they are ground,
   not sky, and they must still be there after the event has gone. They are
   rebuilt from `w.scars`, so if world.js ever clears that array they go too. */
