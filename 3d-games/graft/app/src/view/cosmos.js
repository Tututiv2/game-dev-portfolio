// THE VIEW ABOVE THE GROUND.
//
// Four scenes for the four tiers you cannot walk on: the planet seen from
// orbit, the system, the galaxy, and a field of galaxies. It is ONE
// THREE.Group with one child visible at a time -- the lead adds it to the
// scene and calls setTier(). It decides nothing about the game; it reads
// `sc` (from sim/scale.js) and draws it.
//
// RULES, all paid for elsewhere in this portfolio:
//  - NOTHING is allocated in tick(). Every mesh, material, matrix and vector
//    is made once, in build. The suite greps tick() for `new` and `.clone(`.
//  - A glow needs a FALLOFF. An additive sphere at constant opacity renders as
//    a flat disc, however low the opacity. Everything that glows here is a
//    radial-gradient sprite painted on a canvas at boot.
//  - Everything here is far away, so every material is `fog: false`. Fog
//    erases landmarks, and at these ranges it erases the whole tier.
//  - NOT ONE ASSET. Every texture is painted in code. If this file cannot get
//    a 2D canvas it falls back to plain colours and the game still runs, which
//    is the rule the whole project is built on.
//  - A tier is built the first time you look at it. Never looking at the
//    galaxy costs nothing.

import * as THREE from '../../vendor/three/three.module.js';
import { C } from '../sim/const.js';
import * as P from '../sim/planet.js';

// The tiers this file draws. 'surface' is render.js's job and is not here.
export const COSMOS_TIERS = ['orbit', 'system', 'galaxy', 'galaxies'];

// Where to put the camera for each, so the lead does not have to guess. `dist`
// is how far back it wants to sit; near/far are the depth range it needs.
export const CAMERA = {
  orbit: { dist: 430, near: 1, far: 40000, fov: 48 },
  system: { dist: 2600, near: 2, far: 60000, fov: 52 },
  galaxy: { dist: 7200, near: 4, far: 120000, fov: 55 },
  galaxies: { dist: 12000, near: 8, far: 260000, fov: 58 },
};

export function cameraFor(tier) { return CAMERA[tier] || CAMERA.orbit; }

// One axis, shared by every rotation in tick(). Module scope so that the frame
// never makes one.
const cosmosUp = new THREE.Vector3(0, 1, 0);

// Deterministic, and never Math.random: two runs of the game must draw the
// same galaxy or the sky changes every time you look away.
function lcg(seed) {
  let s = seed | 0 || 1;
  return () => { s = (Math.imul(s, 1103515245) + 12345) & 0x7fffffff; return s / 0x7fffffff; };
}

/**
 * A radial falloff on a canvas. `stops` is [offset, 'rgba(...)'] pairs.
 * Returns null if there is no canvas to paint on -- every caller copes, and
 * the game still runs with no textures at all.
 */
function paint(size, draw) {
  try {
    if (typeof document === 'undefined') return null;
    const cv = document.createElement('canvas');
    cv.width = cv.height = size;
    const g = cv.getContext('2d');
    if (!g) return null;
    draw(g, size);
    const t = new THREE.CanvasTexture(cv);
    t.needsUpdate = true;
    return t;
  } catch (e) {
    return null;                      // a missing texture is never fatal
  }
}

function glowTexture() {
  return paint(128, (g, s) => {
    const r = s / 2;
    const grad = g.createRadialGradient(r, r, 0, r, r, r);
    grad.addColorStop(0.00, 'rgba(255,255,255,1)');
    grad.addColorStop(0.18, 'rgba(255,246,214,0.72)');
    grad.addColorStop(0.45, 'rgba(255,190,120,0.20)');
    grad.addColorStop(1.00, 'rgba(255,150,80,0)');
    g.fillStyle = grad;
    g.fillRect(0, 0, s, s);
  });
}

// A galaxy seen from far enough away is a smudge with a bright middle and two
// arms. Painted once; six hundred of them share it.
function galaxyTexture() {
  return paint(128, (g, s) => {
    const r = s / 2;
    const halo = g.createRadialGradient(r, r, 0, r, r, r);
    halo.addColorStop(0.00, 'rgba(255,252,236,0.95)');
    halo.addColorStop(0.12, 'rgba(214,226,255,0.42)');
    halo.addColorStop(0.42, 'rgba(150,175,255,0.12)');
    halo.addColorStop(1.00, 'rgba(90,120,220,0)');
    g.fillStyle = halo;
    g.fillRect(0, 0, s, s);
    g.globalCompositeOperation = 'lighter';
    for (let arm = 0; arm < 2; arm++) {
      g.beginPath();
      for (let i = 0; i <= 60; i++) {
        const t = i / 60;
        const a = arm * Math.PI + t * 2.6;
        const rad = 4 + t * (r - 8);
        const x = r + Math.cos(a) * rad, y = r + Math.sin(a) * rad * 0.42;
        if (i === 0) g.moveTo(x, y); else g.lineTo(x, y);
      }
      g.strokeStyle = 'rgba(196,214,255,0.30)';
      g.lineWidth = 5;
      g.stroke();
    }
  });
}

function starPoints(n, radius, seed, size, colour) {
  const rnd = lcg(seed);
  const pos = new Float32Array(n * 3);
  for (let i = 0; i < n; i++) {
    const u = rnd() * 2 - 1, th = rnd() * Math.PI * 2, p = Math.sqrt(1 - u * u);
    pos[i * 3] = radius * p * Math.cos(th);
    pos[i * 3 + 1] = radius * u;
    pos[i * 3 + 2] = radius * p * Math.sin(th);
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  return new THREE.Points(g, new THREE.PointsMaterial({
    color: colour, size, sizeAttenuation: false, fog: false, depthWrite: false,
  }));
}

/* ------------------------------------------------------------------ orbit */

// The same height function the player walks on, so the coastline you know is
// the coastline you see from up here. render.js owns the ground-level palette
// and exports nothing, so this is a coarse copy on purpose -- it is a
// different job at a different range and it must never be a second HEIGHT
// function, which is the rule that matters.
function orbitColour(h, out) {
  const sea = C.planet.sea;
  if (h < sea) out.setRGB(0.06, 0.24, 0.38);
  else if (h < sea + C.planet.shore) out.setRGB(0.80, 0.72, 0.52);
  else if (h < C.planet.alpine) out.setRGB(0.22, 0.44, 0.24);
  else if (h < C.planet.snow) out.setRGB(0.42, 0.40, 0.42);
  else out.setRGB(0.88, 0.90, 0.94);
}

function buildOrbit(glow) {
  const root = new THREE.Group();
  const R = C.planet.radius;

  const geo = new THREE.IcosahedronGeometry(R, 26);   // enough to read a coast
  const pos = geo.attributes.position;
  const col = new THREE.Color();
  const colours = new Float32Array(pos.count * 3);
  const d = { x: 0, y: 0, z: 0 };
  for (let i = 0; i < pos.count; i++) {
    d.x = pos.getX(i); d.y = pos.getY(i); d.z = pos.getZ(i);
    const dir = P.norm(d);
    const h = P.heightAt(dir);
    const r = R + Math.max(h, C.planet.sea) * 0.9;    // the sea fills its basins
    pos.setXYZ(i, dir.x * r, dir.y * r, dir.z * r);
    orbitColour(h, col);
    colours[i * 3] = col.r; colours[i * 3 + 1] = col.g; colours[i * 3 + 2] = col.b;
  }
  geo.computeVertexNormals();
  geo.setAttribute('color', new THREE.BufferAttribute(colours, 3));
  const planet = new THREE.Mesh(geo, new THREE.MeshStandardMaterial({
    vertexColors: true, roughness: 0.95, metalness: 0.02, flatShading: true, fog: false,
  }));
  root.add(planet);

  // Air, seen edge-on. A back-faced shell is the cheap way to get a limb.
  const air = new THREE.Mesh(
    new THREE.SphereGeometry(R * 1.055, 40, 24),
    new THREE.MeshBasicMaterial({
      color: 0x6fb0ff, transparent: true, opacity: 0.13, side: THREE.BackSide,
      depthWrite: false, blending: THREE.AdditiveBlending, fog: false,
    }));
  root.add(air);

  // What you have built, in orbit around it. One InstancedMesh, count set from
  // the ladder each frame, so it costs one draw call and never rebuilds.
  const ring = new THREE.InstancedMesh(
    new THREE.BoxGeometry(2.6, 0.7, 0.7),
    new THREE.MeshStandardMaterial({
      color: 0xc8d4e4, emissive: 0x3d5a80, emissiveIntensity: 0.9,
      roughness: 0.4, metalness: 0.5, fog: false,
    }), 64);
  ring.frustumCulled = false;
  ring.count = 0;
  root.add(ring);

  const sun = new THREE.Sprite(new THREE.SpriteMaterial({
    map: glow, color: 0xfff0cc, transparent: true, depthWrite: false,
    blending: THREE.AdditiveBlending, fog: false,
  }));
  sun.scale.setScalar(520);
  sun.position.set(2400, 900, -1600);
  root.add(sun);

  root.add(starPoints(1400, 9000, 99, 1.5, 0xbcd2e8));
  return { root, spin: planet, ring, kind: 'orbit' };
}

/* ----------------------------------------------------------------- system */

function buildSystem(glow) {
  const root = new THREE.Group();

  const star = new THREE.Mesh(
    new THREE.SphereGeometry(60, 32, 20),
    new THREE.MeshBasicMaterial({ color: 0xffe9b0, fog: false }));
  root.add(star);
  const halo = new THREE.Sprite(new THREE.SpriteMaterial({
    map: glow, color: 0xffd489, transparent: true, depthWrite: false,
    blending: THREE.AdditiveBlending, fog: false,
  }));
  halo.scale.setScalar(620);
  root.add(halo);

  // Eight worlds. The larder in tierSpec('system').stock is eight, and this is
  // that number drawn: eat one and one goes out.
  const N = 8;
  const orbits = new Float32Array(N), speed = new Float32Array(N), phase = new Float32Array(N);
  const rnd = lcg(4242);
  for (let i = 0; i < N; i++) {
    orbits[i] = 240 + i * 235 + rnd() * 60;
    speed[i] = 0.28 / Math.pow(orbits[i] / 240, 1.5);    // slower further out
    phase[i] = rnd() * Math.PI * 2;
  }

  const worlds = new THREE.InstancedMesh(
    new THREE.IcosahedronGeometry(1, 2),
    new THREE.MeshStandardMaterial({
      color: 0x9fb4c8, roughness: 0.85, flatShading: true, fog: false,
    }), N);
  worlds.frustumCulled = false;
  worlds.count = N;
  const tint = new THREE.Color();
  for (let i = 0; i < N; i++) {
    tint.setHSL(0.08 + i * 0.09, 0.42, 0.55);
    worlds.setColorAt(i, tint);
  }
  if (worlds.instanceColor) worlds.instanceColor.needsUpdate = true;
  root.add(worlds);

  // A lane you can see is a distance you can judge.
  const lanes = new THREE.Group();
  for (let i = 0; i < N; i++) {
    const pts = new Float32Array(129 * 3);
    for (let k = 0; k <= 128; k++) {
      const a = (k / 128) * Math.PI * 2;
      pts[k * 3] = Math.cos(a) * orbits[i];
      pts[k * 3 + 1] = 0;
      pts[k * 3 + 2] = Math.sin(a) * orbits[i];
    }
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.BufferAttribute(pts, 3));
    lanes.add(new THREE.Line(g, new THREE.LineBasicMaterial({
      color: 0x2c4a68, transparent: true, opacity: 0.55, fog: false,
    })));
  }
  root.add(lanes);

  root.add(starPoints(1800, 26000, 7, 1.3, 0xa8bcd8));
  return { root, worlds, orbits, speed, phase, sizes: 26, kind: 'system' };
}

/* ----------------------------------------------------------------- galaxy */

function buildGalaxy(glow) {
  const root = new THREE.Group();
  const N = 24000, ARMS = 4;
  const rnd = lcg(1337);
  const pos = new Float32Array(N * 3);
  const col = new Float32Array(N * 3);
  const c = new THREE.Color();
  for (let i = 0; i < N; i++) {
    const t = Math.pow(rnd(), 0.6);              // crowded at the middle
    const arm = Math.floor(rnd() * ARMS);
    const a = (arm / ARMS) * Math.PI * 2 + t * 5.2 + (rnd() - 0.5) * 0.45;
    const r = 120 + t * 4600;
    const puff = (1 - t) * 320 + 60;
    pos[i * 3] = Math.cos(a) * r + (rnd() - 0.5) * puff;
    pos[i * 3 + 1] = (rnd() - 0.5) * (puff * 0.30 + (1 - t) * 260);
    pos[i * 3 + 2] = Math.sin(a) * r + (rnd() - 0.5) * puff;
    c.setHSL(0.60 - t * 0.44, 0.55, 0.52 + rnd() * 0.3);
    col[i * 3] = c.r; col[i * 3 + 1] = c.g; col[i * 3 + 2] = c.b;
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  g.setAttribute('color', new THREE.BufferAttribute(col, 3));
  const stars = new THREE.Points(g, new THREE.PointsMaterial({
    size: 2.4, sizeAttenuation: false, vertexColors: true, transparent: true,
    opacity: 1, depthWrite: false, blending: THREE.AdditiveBlending, fog: false,
  }));
  root.add(stars);

  const core = new THREE.Sprite(new THREE.SpriteMaterial({
    map: glow, color: 0xfff2cf, transparent: true, depthWrite: false,
    blending: THREE.AdditiveBlending, fog: false,
  }));
  core.scale.setScalar(2400);
  root.add(core);

  return { root, spin: stars, stars, kind: 'galaxy' };
}

/* --------------------------------------------------------------- galaxies */

function buildGalaxies(gtex) {
  const root = new THREE.Group();
  const N = 900;
  const rnd = lcg(90210);
  const pos = new Float32Array(N * 3);
  const col = new Float32Array(N * 3);
  const c = new THREE.Color();
  for (let i = 0; i < N; i++) {
    // filaments, not soup: the real thing is a foam with walls and voids
    const th = rnd() * Math.PI * 2, u = rnd() * 2 - 1;
    const p = Math.sqrt(1 - u * u);
    const r = 3000 + Math.pow(rnd(), 0.4) * 26000;
    const clump = 0.55 + rnd() * 0.9;
    pos[i * 3] = Math.cos(th) * p * r * clump;
    pos[i * 3 + 1] = u * r * 0.55 * clump;
    pos[i * 3 + 2] = Math.sin(th) * p * r * clump;
    c.setHSL(0.58 + rnd() * 0.12, 0.35, 0.55 + rnd() * 0.3);
    col[i * 3] = c.r; col[i * 3 + 1] = c.g; col[i * 3 + 2] = c.b;
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  g.setAttribute('color', new THREE.BufferAttribute(col, 3));
  const mat = new THREE.PointsMaterial({
    size: 260, sizeAttenuation: true, vertexColors: true, transparent: true,
    depthWrite: false, blending: THREE.AdditiveBlending, fog: false,
  });
  if (gtex) mat.map = gtex;                 // no texture is a dot, and a dot is fine
  const field = new THREE.Points(g, mat);
  root.add(field);
  return { root, spin: field, field, count: N, kind: 'galaxies' };
}

/* ------------------------------------------------------------------- view */

export function createCosmos() {
  const group = new THREE.Group();
  group.name = 'cosmos';
  group.visible = false;

  // Lights of its own, so the lead can hide the surface scene's sun without
  // the planet from orbit going black.
  const key = new THREE.DirectionalLight(0xfff2d8, 2.4);
  key.position.set(3, 1.2, -2);
  group.add(key);
  group.add(new THREE.AmbientLight(0x27324a, 0.55));

  const glow = glowTexture();
  const gtex = galaxyTexture();
  const views = {};
  let cur = null;
  let time = 0;

  // Every temporary the frame needs, made once.
  const _m = new THREE.Matrix4();
  const _p = new THREE.Vector3();
  const _q = new THREE.Quaternion();
  const _s = new THREE.Vector3(1, 1, 1);

  function ensure(tier) {
    if (views[tier]) return views[tier];
    let v = null;
    if (tier === 'orbit') v = buildOrbit(glow);
    else if (tier === 'system') v = buildSystem(glow);
    else if (tier === 'galaxy') v = buildGalaxy(glow);
    else if (tier === 'galaxies') v = buildGalaxies(gtex);
    if (!v) return null;
    v.root.visible = false;
    group.add(v.root);
    views[tier] = v;
    return v;
  }

  /** Show one tier, or null for none. Builds it the first time it is asked for. */
  function setTier(tier) {
    const v = tier ? ensure(tier) : null;
    if (cur && cur !== v) cur.root.visible = false;
    cur = v;
    if (cur) cur.root.visible = true;
    group.visible = !!cur;
    return !!cur;
  }

  function currentTier() { return cur ? cur.kind : null; }

  /**
   * PER FRAME. Allocates nothing: every vector, quaternion and matrix above is
   * reused, and the only writes are into buffers that already exist.
   */
  function tick(sc, dt) {
    if (!cur) return;
    time += dt;
    const k = cur.kind;

    if (k === 'orbit') {
      cur.spin.rotation.y = time * 0.035;
      let n = 0;
      if (sc && sc.built) for (const id in sc.built) if (sc.built[id]) n++;
      const ring = cur.ring;
      const want = Math.min(n * 3, 64);
      for (let i = 0; i < want; i++) {
        const a = time * 0.10 + (i / want) * Math.PI * 2;
        const tilt = (i % 3) * 0.28 - 0.28;
        const r = C.planet.radius * 1.24 + (i % 3) * 7;
        _p.set(Math.cos(a) * r, Math.sin(a) * r * Math.sin(tilt), Math.sin(a) * r * Math.cos(tilt));
        _q.setFromAxisAngle(cosmosUp, a);
        _m.compose(_p, _q, _s);
        ring.setMatrixAt(i, _m);
      }
      ring.count = want;
      ring.instanceMatrix.needsUpdate = true;
      return;
    }

    if (k === 'system') {
      // Worlds you have eaten are gone from the sky. That is the whole point of
      // the tier and it must be visible without a number.
      const eaten = sc && sc.eaten ? Math.floor(sc.eaten.world || 0) : 0;
      const left = Math.max(0, 8 - Math.min(8, eaten));
      const w = cur.worlds;
      for (let i = 0; i < left; i++) {
        const a = cur.phase[i] + time * cur.speed[i] * 0.12;
        const r = cur.orbits[i];
        _p.set(Math.cos(a) * r, Math.sin(a * 0.5) * 12, Math.sin(a) * r);
        _q.setFromAxisAngle(cosmosUp, a * 3);
        _s.setScalar(cur.sizes * (i === 0 ? 1.15 : 0.72 + (i % 3) * 0.18));
        _m.compose(_p, _q, _s);
        w.setMatrixAt(i, _m);
      }
      _s.setScalar(1);
      w.count = left;
      w.instanceMatrix.needsUpdate = true;
      return;
    }

    if (k === 'galaxy') {
      cur.spin.rotation.y = time * 0.012;
      // The Spindle takes the arms from the outside in. The sky it leaves is
      // dimmer, and this is the only readout that says so without a number.
      const eaten = sc && sc.eaten ? (sc.eaten.star || 0) : 0;
      const frac = Math.min(1, eaten / 2.4e11);
      cur.stars.material.opacity = 1 - frac * 0.82;
      return;
    }

    if (k === 'galaxies') {
      cur.spin.rotation.y = time * 0.004;
      const eaten = sc && sc.eaten ? (sc.eaten.galaxy || 0) : 0;
      const gone = Math.min(cur.count - 1, Math.floor(eaten));
      cur.field.geometry.setDrawRange(gone, cur.count - gone);
    }
  }

  function dispose() {
    for (const k in views) {
      const v = views[k];
      group.remove(v.root);
      v.root.traverse((o) => {
        if (o.geometry) o.geometry.dispose();
        if (o.material) {
          const m = o.material;
          if (m.map && m.map !== glow && m.map !== gtex) m.map.dispose();
          m.dispose();
        }
      });
      delete views[k];
    }
    if (glow) glow.dispose();
    if (gtex) gtex.dispose();
    cur = null;
    group.visible = false;
  }

  return { group, setTier, currentTier, tick, dispose, cameraFor, CAMERA, views };
}
