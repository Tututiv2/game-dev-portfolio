// THE MOON — the render half.
//
// It reads the sun rather than inventing one. render.js owns the day: it swings
// a DirectionalLight round the planet in a fixed plane. This module finds that
// light, takes its direction, and hangs the moon at an angle FROM it -- so the
// disc is lit by the same light as the ground beneath it, the phase is correct
// for free, and the moment the moon reaches the sun's place in the sky is the
// same moment the sim says an eclipse is happening. Nothing is duplicated and
// nothing can drift.
//
// Two things it draws that are not the moon: a corona, which only exists while
// the sun is behind the disc, and a darkening dome around the camera, which is
// how an event darkens the world without owning the lights.

import * as M from '../../sim/events/moon.js';
import * as P from '../../sim/planet.js';
import { age } from '../../sim/events/api.js';

export const id = 'moon';

const clamp01 = (x) => (x < 0 ? 0 : x > 1 ? 1 : x);
const smooth = (x) => { const t = clamp01(x); return t * t * (3 - 2 * t); };

// A glow needs a falloff or it renders as a flat disc. Built once, in code:
// this game has no asset files.
//
// `hole` is what makes a corona a corona. An additive sprite centred on the
// moon is brightest exactly where the moon is, which fills the silhouette in
// and turns the one image an eclipse exists for into a bright blob. So the
// alpha is zero out to the edge of the disc and only then climbs.
function glowTexture(THREE, hole = 0) {
  const n = 64;
  const data = new Uint8Array(n * n * 4);
  const peak = hole + 0.07;
  for (let y = 0; y < n; y++) {
    for (let x = 0; x < n; x++) {
      const dx = ((x + 0.5) / n) * 2 - 1;
      const dy = ((y + 0.5) / n) * 2 - 1;
      const r = Math.hypot(dx, dy);
      let a;
      if (hole <= 0) a = Math.pow(Math.max(0, 1 - r), 2.4);
      else if (r < hole) a = 0;
      else if (r < peak) a = (r - hole) / (peak - hole);
      else a = Math.pow(Math.max(0, (1 - r) / (1 - peak)), 2.2);
      const i = (y * n + x) * 4;
      data[i] = 255; data[i + 1] = 244; data[i + 2] = 222;
      data[i + 3] = Math.round(255 * Math.max(0, Math.min(1, a)));
    }
  }
  const t = new THREE.DataTexture(data, n, n, THREE.RGBAFormat);
  t.minFilter = THREE.LinearFilter;
  t.magFilter = THREE.LinearFilter;
  t.needsUpdate = true;
  return t;
}

// Maria and highlands off the planet's own noise, sampled on a swapped axis so
// the moon is not a copy of the ground you are standing on.
function surfaceMoon(THREE, C) {
  const geo = new THREE.IcosahedronGeometry(M.MOON.radius, 4);
  const pos = geo.attributes.position;
  const col = new Float32Array(pos.count * 3);
  for (let i = 0; i < pos.count; i++) {
    const d = P.norm({ x: pos.getX(i), y: pos.getY(i), z: pos.getZ(i) });
    const n = P.heightAt(P.v(d.z, d.x, d.y)) / C.planet.relief;   // about -1..1
    const r = M.MOON.radius * (1 + n * 0.035);
    pos.setXYZ(i, d.x * r, d.y * r, d.z * r);
    // dark seas in the basins, pale dust on the highs
    const g = n < -0.22 ? 0.30 + n * 0.05 : 0.56 + n * 0.20;
    col[i * 3] = g * 1.04; col[i * 3 + 1] = g; col[i * 3 + 2] = g * 0.94;
  }
  geo.setAttribute('color', new THREE.BufferAttribute(col, 3));
  geo.computeVertexNormals();
  return geo;
}

function findSun(scene) {
  let found = null;
  scene.traverse((o) => { if (!found && o.isDirectionalLight) found = o; });
  return found;
}

function inScene(obj, scene) {
  for (let p = obj; p; p = p.parent) if (p === scene) return true;
  return false;
}

export function build(ctx) {
  const { THREE, C } = ctx;
  const sky = ctx.sky || ctx.scene;
  const near = ctx.root || ctx.scene;

  const group = new THREE.Group();
  sky.add(group);

  const mat = new THREE.MeshStandardMaterial({
    vertexColors: true, roughness: 1.0, metalness: 0.0, flatShading: true,
    emissive: 0x0a0f1a, emissiveIntensity: 1, fog: false,
  });
  const mesh = new THREE.Mesh(surfaceMoon(THREE, C), mat);
  mesh.frustumCulled = false;
  group.add(mesh);

  // The corona. Drawn after the darkening dome on purpose: the one thing an
  // eclipse must NOT dim is the ring of light that is the whole point of it.
  // Its size is FIXED -- the hole in the texture has to line up with the disc,
  // so only the brightness may move.
  const tex = glowTexture(THREE, 0);
  const coronaTex = glowTexture(THREE, 0.29);
  const coronaMat = new THREE.SpriteMaterial({
    map: coronaTex, color: 0xfff2d8, transparent: true, opacity: 0,
    blending: THREE.AdditiveBlending, depthWrite: false, depthTest: false, fog: false,
  });
  const corona = new THREE.Sprite(coronaMat);
  corona.renderOrder = 10001;
  corona.frustumCulled = false;
  group.add(corona);

  // A soft halo so a full moon is not a flat pebble against the stars.
  const haloMat = new THREE.SpriteMaterial({
    map: tex, color: 0xbfd4ee, transparent: true, opacity: 0,
    blending: THREE.AdditiveBlending, depthWrite: false, fog: false,
  });
  const halo = new THREE.Sprite(haloMat);
  halo.scale.setScalar(M.MOON.radius * 4.4);
  halo.frustumCulled = false;
  group.add(halo);

  // How an event takes the light away without touching render.js's lights: a
  // small inside-out sphere parked on the camera, drawn last, depth off.
  const domeMat = new THREE.MeshBasicMaterial({
    color: 0x05070e, transparent: true, opacity: 0,
    side: THREE.BackSide, depthWrite: false, depthTest: false, fog: false,
  });
  const dome = new THREE.Mesh(new THREE.SphereGeometry(3, 12, 8), domeMat);
  dome.renderOrder = 10000;
  dome.frustumCulled = false;
  dome.visible = false;
  near.add(dome);

  return {
    group, mesh, mat, corona, coronaMat, halo, haloMat, dome, domeMat, tex, coronaTex,
    sunLight: findSun(ctx.scene),
    ownLight: null,
    s0: null,          // first sun direction seen
    planeN: null,      // the sun's orbital plane, latched once
    checkedLit: false,
    // scratch: nothing in this module allocates once the frames start
    up: new THREE.Vector3(0, 1, 0),
    sd: new THREE.Vector3(0, 1, 0),
    md: new THREE.Vector3(0, 1, 0),
    tmp: new THREE.Vector3(),
  };
}

export function update(self, ev, w, dt, ctx) {
  const { THREE } = ctx;

  // --- the sun, as render.js is actually using it
  if (!self.sunLight) self.sunLight = findSun(ctx.scene);
  if (self.sunLight) self.sd.copy(self.sunLight.position).normalize();
  if (self.sd.lengthSq() < 0.5) self.sd.set(0, 1, 0);

  // The plane the sun swings in is fixed, so two directions far enough apart
  // fix it for good. A few seconds after the moon arrives there is enough
  // travel to take the cross product safely, and it is never taken again --
  // its SIGN flips once the sun passes the far side of where it started.
  if (!self.s0) self.s0 = self.sd.clone();
  if (!self.planeN) {
    self.tmp.crossVectors(self.s0, self.sd);
    if (self.tmp.length() > 0.15) self.planeN = self.tmp.clone().normalize();
  }

  const theta = M.phaseAngle(w, ev);
  const tiltA = M.tilt(w, ev);
  const cov = M.coverage(w, ev);

  if (self.planeN) {
    const d = M.moonUnit(
      { x: self.sd.x, y: self.sd.y, z: self.sd.z },
      { x: self.planeN.x, y: self.planeN.y, z: self.planeN.z },
      theta, tiltA);
    self.md.set(d.x, d.y, d.z);
    self.group.visible = true;
  } else {
    self.group.visible = false;         // it is still on its way in
  }

  // --- arrival: it comes in from the edge of the stars and grows
  const k = smooth(age(w, ev) / M.MOON.settleAt);
  const dist = M.MOON.arriveFrom + (M.MOON.distance - M.MOON.arriveFrom) * k;
  const scale = M.MOON.arriveScale + (1 - M.MOON.arriveScale) * k;
  self.mesh.scale.setScalar(scale);
  self.group.position.copy(self.md).multiplyScalar(dist);

  // Tidally locked: one face has always looked at this planet.
  self.tmp.copy(self.md).negate();
  self.mesh.quaternion.setFromUnitVectors(self.up, self.tmp);

  // --- how bright the moon's own glow should be. A full moon is a lamp; a new
  // moon is a hole, and a hole with a ring of fire round it.
  const lit = clamp01(0.5 - 0.5 * Math.cos(theta) * Math.cos(tiltA));
  self.haloMat.opacity = 0.10 + 0.34 * lit * lit;
  self.halo.scale.setScalar(M.MOON.radius * (3.6 + 1.4 * lit) * scale);
  self.corona.scale.setScalar(M.MOON.radius * 7 * scale);
  self.coronaMat.opacity = cov * cov * 0.95;

  // --- the darkening. Only what the player can actually stand under: at night
  // the moon is in front of a sun that is already down and nothing happens.
  const p = w.player.dir;
  const daylight = Math.max(0, self.sd.x * p.x + self.sd.y * p.y + self.sd.z * p.z);
  const dim = cov * M.MOON.darkest * Math.min(1, daylight * 3);
  self.domeMat.opacity = dim;
  self.dome.visible = dim > 0.002;
  if (self.dome.visible && ctx.camera) self.dome.position.copy(ctx.camera.position);

  // --- if the sky group turns out not to be lit by the scene's own sun, give
  // the moon a light of its own pointed the same way. Same direction, so it is
  // still the same sun; this only exists so the disc is never a black hole.
  if (!self.checkedLit) {
    self.checkedLit = true;
    if (!inScene(self.group, ctx.scene) || !self.sunLight) {
      self.ownLight = new THREE.DirectionalLight(0xfff0d8, 2.0);
      self.group.parent.add(self.ownLight);
      self.ownLight.target = self.mesh;
    }
  }
  if (self.ownLight) self.ownLight.position.copy(self.group.position).addScaledVector(self.sd, 2200);
}

export function dispose(self, ctx) {
  if (self.group.parent) self.group.parent.remove(self.group);
  if (self.dome.parent) self.dome.parent.remove(self.dome);
  if (self.ownLight && self.ownLight.parent) self.ownLight.parent.remove(self.ownLight);
  self.mesh.geometry.dispose();
  self.dome.geometry.dispose();
  self.mat.dispose();
  self.coronaMat.dispose();
  self.haloMat.dispose();
  self.domeMat.dispose();
  self.tex.dispose();
  self.coronaTex.dispose();
}

/**
 * The Anchor's draw entry, in render.js's own PARTS shape.
 *
 * INTEGRATOR: `[KIND.ANCHOR]: anchorParts(THREE),` in render.js. The catalog
 * and the draw table must name exactly the same set or the suite fails, and
 * moon.js in sim/ adds the catalog half.
 */
export function anchorParts(THREE) {
  return [
    { geo: () => new THREE.CylinderGeometry(0.11, 0.26, 1, 6), mat: { color: 0x79839a, roughness: 0.6, metalness: 0.45 }, y: 0.5, s: 1, stretch: true },
    { geo: () => new THREE.TorusGeometry(0.52, 0.06, 6, 20), mat: { color: 0xd8e6ff, emissive: 0x6fa8ff, emissiveIntensity: 2.4 }, y: 0.78, s: 1, flat: true },
    { geo: () => new THREE.TorusGeometry(0.34, 0.05, 6, 16), mat: { color: 0xffe3a8, emissive: 0xffb03a, emissiveIntensity: 2.6 }, y: 0.96, s: 1, flat: true },
    { geo: () => new THREE.OctahedronGeometry(0.20, 0), mat: { color: 0xffffff, emissive: 0xbfd4ee, emissiveIntensity: 3.0, flatShading: true }, y: 1.06, s: 1 },
  ];
}
