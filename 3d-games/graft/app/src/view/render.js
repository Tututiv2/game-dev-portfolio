// Camera, meshes, light, water, sky. Decides NOTHING about the game.
// Every position drawn comes from the same height function the player walks on,
// so nothing ever floats or sinks.

import * as THREE from '../../vendor/three/three.module.js';
import { C } from '../sim/const.js';
import * as P from '../sim/planet.js';
import { KIND, spec } from '../sim/catalog.js';
import { fxFor } from './fx/index.js';
import { modelParts, playerModel } from './assets.js';

const V3 = (p) => new THREE.Vector3(p.x, p.y, p.z);

// Terrain palette. Height decides the band, slope darkens it into rock.
function groundColour(h, slope, out) {
  const sea = C.planet.sea;
  let r, g, b;
  if (h < sea + C.planet.shore) {            // sand
    r = 0.80; g = 0.72; b = 0.52;
  } else if (h < C.planet.alpine) {          // grass, greener in the lowlands
    const t = (h - sea - C.planet.shore) / (C.planet.alpine - sea - C.planet.shore);
    r = 0.24 + 0.20 * t; g = 0.46 - 0.10 * t; b = 0.24 + 0.06 * t;
  } else if (h < C.planet.snow) {            // bare rock
    r = 0.42; g = 0.40; b = 0.42;
  } else {                                   // snow
    r = 0.88; g = 0.90; b = 0.94;
  }
  const k = 1 - slope * 0.45;                // steep ground is stone
  out.setRGB(r * k + 0.10 * slope, g * k + 0.09 * slope, b * k + 0.10 * slope);
}

export function createView(canvas) {
  const renderer = new THREE.WebGLRenderer({
    canvas, antialias: true, powerPreference: 'high-performance',
  });
  renderer.setClearColor(0x060912, 1);

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(62, 1, 0.15, 3000);

  const sun = new THREE.DirectionalLight(0xfff0d8, 2.2);
  scene.add(sun);
  const hemi = new THREE.HemisphereLight(0x9dc0ea, 0x2b2519, 0.75);
  scene.add(hemi);
  const amb = new THREE.AmbientLight(0x2a3550, 0.45);
  scene.add(amb);

  // Cosmic events hang their work in here. It is never fogged and never scaled
  // with the terrain, because a thing in the sky is not at ground distance.
  const sky = new THREE.Group();
  scene.add(sky);
  const fxRoot = new THREE.Group();
  scene.add(fxRoot);

  // One radial-gradient sprite texture, shared. A glow needs a FALLOFF: an
  // additive sphere at constant opacity renders as a flat disc, which this
  // portfolio has paid for twice.
  let _glowTex = null;
  function glowTexture() {
    if (_glowTex) return _glowTex;
    const c = document.createElement('canvas');
    c.width = c.height = 128;
    const g = c.getContext('2d');
    const rad = g.createRadialGradient(64, 64, 0, 64, 64, 64);
    rad.addColorStop(0.00, 'rgba(255,255,255,0.85)');
    rad.addColorStop(0.25, 'rgba(255,255,255,0.28)');
    rad.addColorStop(0.60, 'rgba(255,255,255,0.06)');
    rad.addColorStop(1.00, 'rgba(255,255,255,0)');
    g.fillStyle = rad;
    g.fillRect(0, 0, 128, 128);
    _glowTex = new THREE.CanvasTexture(c);
    return _glowTex;
  }

  /* -------------------------------------------------------------- stars -- */
  {
    const n = 1200, pos = new Float32Array(n * 3);
    let s = 99;
    const rnd = () => { s = (Math.imul(s, 1103515245) + 12345) & 0x7fffffff; return s / 0x7fffffff; };
    for (let i = 0; i < n; i++) {
      const u = rnd() * 2 - 1, th = rnd() * Math.PI * 2, p = Math.sqrt(1 - u * u);
      const r = 1600;
      pos[i * 3] = r * p * Math.cos(th); pos[i * 3 + 1] = r * u; pos[i * 3 + 2] = r * p * Math.sin(th);
    }
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    scene.add(new THREE.Points(g, new THREE.PointsMaterial({ color: 0xbcd2e8, size: 1.6, sizeAttenuation: false })));
  }

  /* ------------------------------------------------------------- planet -- */
  const planetGeo = new THREE.IcosahedronGeometry(C.planet.radius, C.planet.detail);
  {
    const pos = planetGeo.attributes.position;
    const heights = new Float32Array(pos.count);
    const d = { x: 0, y: 0, z: 0 };
    for (let i = 0; i < pos.count; i++) {
      d.x = pos.getX(i); d.y = pos.getY(i); d.z = pos.getZ(i);
      const dir = P.norm(d);
      const h = P.heightAt(dir);
      heights[i] = h;
      const r = C.planet.radius + h;
      pos.setXYZ(i, dir.x * r, dir.y * r, dir.z * r);
    }
    planetGeo.computeVertexNormals();

    // Slope comes off the normal we just computed. Calling slopeAt per vertex
    // would be five more noise evaluations each -- about a million of them --
    // for a number the geometry already knows.
    const nrm = planetGeo.attributes.normal;
    const colours = new Float32Array(pos.count * 3);
    const col = new THREE.Color();
    for (let i = 0; i < pos.count; i++) {
      const x = pos.getX(i), y = pos.getY(i), z = pos.getZ(i);
      const l = Math.hypot(x, y, z) || 1;
      const up = (nrm.getX(i) * x + nrm.getY(i) * y + nrm.getZ(i) * z) / l;
      groundColour(heights[i], Math.min(1, Math.max(0, 1 - up) * 3.2), col);
      colours[i * 3] = col.r; colours[i * 3 + 1] = col.g; colours[i * 3 + 2] = col.b;
    }
    planetGeo.setAttribute('color', new THREE.BufferAttribute(colours, 3));
  }
  const planet = new THREE.Mesh(planetGeo, new THREE.MeshStandardMaterial({
    vertexColors: true, roughness: 0.96, metalness: 0.02, flatShading: true,
  }));
  scene.add(planet);

  /* -------------------------------------------------------------- ocean -- */
  const oceanMat = new THREE.MeshStandardMaterial({
    color: 0x1d6f92, roughness: 0.16, metalness: 0.25,
    transparent: true, opacity: 0.86,
  });
  const ocean = new THREE.Mesh(
    new THREE.IcosahedronGeometry(C.planet.radius + C.planet.sea, 5), oceanMat);
  scene.add(ocean);

  /* ------------------------------------------------------- the catalogue -- */
  // One InstancedMesh per (kind, part). Everything the player can make is drawn
  // by this table and nothing else, which is the render-side half of the rule:
  // if it is on screen, it came from the catalog.
  const PARTS = {
    [KIND.BLOOM]: [
      { geo: () => new THREE.ConeGeometry(0.30, 0.9, 5), mat: { color: 0x2f7a4a, roughness: .8 }, y: 0.45, s: 1 },
      { geo: () => new THREE.IcosahedronGeometry(0.26, 0), mat: { color: 0x8ff0c0, emissive: 0x38c98a, emissiveIntensity: 1.5 }, y: 0.95, s: 1 },
    ],
    [KIND.TREE]: [
      { geo: () => new THREE.CylinderGeometry(0.10, 0.17, 1, 5), mat: { color: 0x5b4432, roughness: .95 }, y: 0.5, s: 1, stretch: true },
      { geo: () => new THREE.IcosahedronGeometry(0.42, 0), mat: { color: 0x39804a, roughness: .85, flatShading: true }, y: 1.05, s: 1 },
      { geo: () => new THREE.IcosahedronGeometry(0.32, 0), mat: { color: 0x2f6b3f, roughness: .85, flatShading: true }, y: 1.35, s: 1 },
    ],
    [KIND.CRYSTAL]: [
      { geo: () => new THREE.OctahedronGeometry(0.34, 0), mat: { color: 0x9fd8f2, emissive: 0x2e7fa8, emissiveIntensity: .8, roughness: .25, metalness: .3, flatShading: true }, y: 0.34, s: 1, stretch: true },
    ],
    [KIND.KELP]: [
      { geo: () => new THREE.CylinderGeometry(0.05, 0.14, 1, 4), mat: { color: 0x2f8f7a, emissive: 0x1d6b58, emissiveIntensity: .6, transparent: true, opacity: .9 }, y: 0.5, s: 1, stretch: true },
    ],
    [KIND.HAB]: [
      { geo: () => new THREE.SphereGeometry(0.62, 10, 6, 0, Math.PI * 2, 0, Math.PI / 2), mat: { color: 0xb9c3cf, roughness: .6, metalness: .2, flatShading: true }, y: 0, s: 1 },
      { geo: () => new THREE.BoxGeometry(0.26, 0.34, 0.1), mat: { color: 0xffd9a0, emissive: 0xffb44d, emissiveIntensity: 1.1 }, y: 0.17, s: 1, front: 0.60 },
    ],
    [KIND.MAST]: [
      { geo: () => new THREE.CylinderGeometry(0.07, 0.13, 1, 5), mat: { color: 0x8b95a3, roughness: .6, metalness: .4 }, y: 0.5, s: 1, stretch: true },
      { geo: () => new THREE.SphereGeometry(0.16, 8, 6), mat: { color: 0xff6b5a, emissive: 0xff4a33, emissiveIntensity: 2.0 }, y: 1.02, s: 1 },
    ],
    [KIND.KILN]: [
      { geo: () => new THREE.BoxGeometry(0.9, 0.7, 0.9), mat: { color: 0x6d5c50, roughness: .9, flatShading: true }, y: 0.35, s: 1 },
      { geo: () => new THREE.CylinderGeometry(0.14, 0.18, 0.7, 6), mat: { color: 0x4c4038, roughness: .9 }, y: 0.95, s: 1 },
      { geo: () => new THREE.SphereGeometry(0.2, 8, 6), mat: { color: 0xff8a3c, emissive: 0xff5a1a, emissiveIntensity: 1.8 }, y: 0.34, s: 1, front: 0.46 },
    ],
    [KIND.BEACON]: [
      { geo: () => new THREE.CylinderGeometry(0.08, 0.12, 1, 5), mat: { color: 0x7c8695, roughness: .6, metalness: .4 }, y: 0.5, s: 1, stretch: true },
      { geo: () => new THREE.TorusGeometry(0.44, 0.07, 5, 14), mat: { color: 0xff4fd8, emissive: 0xff2fc8, emissiveIntensity: 2.6 }, y: 0.86, s: 1, flat: true },
      { geo: () => new THREE.TorusGeometry(0.30, 0.05, 5, 12), mat: { color: 0x4fe8ff, emissive: 0x2fd8ff, emissiveIntensity: 2.6 }, y: 1.02, s: 1, flat: true },
    ],
    // ---- the buildings that CAUSE cosmic events ------------------------
    // Each one is somebody's late-game answer to a thing that happens to the
    // planet. They are deliberately strange next to a hab: you should be able
    // to tell from the far side of a valley that somebody has been ambitious.
    [KIND.LATTICE]: [
      { geo: () => new THREE.TorusGeometry(0.40, 0.045, 6, 18), mat: { color: 0x2a2f3d, roughness: .4, metalness: .8, emissive: 0x140b22, emissiveIntensity: 1 }, y: 0.55, s: 1, flat: true },
      { geo: () => new THREE.TorusGeometry(0.28, 0.035, 6, 16), mat: { color: 0x7a4fd8, emissive: 0x6a2fd8, emissiveIntensity: 2.4 }, y: 0.78, s: 1, flat: true },
      { geo: () => new THREE.IcosahedronGeometry(0.14, 0), mat: { color: 0x05050a, emissive: 0x1a0a2e, emissiveIntensity: .6, roughness: .1, metalness: .9 }, y: 0.66, s: 1 },
      { geo: () => new THREE.CylinderGeometry(0.06, 0.13, 1, 6), mat: { color: 0x39404f, roughness: .5, metalness: .6 }, y: 0.5, s: 1, stretch: true },
    ],
    [KIND.WORMGATE]: [
      { geo: () => new THREE.TorusGeometry(0.42, 0.075, 6, 20), mat: { color: 0x8e97a6, roughness: .4, metalness: .7, emissive: 0x1d5f6e, emissiveIntensity: .8 }, y: 0.55, s: 1 },
      { geo: () => new THREE.CircleGeometry(0.36, 18), mat: { color: 0x63f0e0, emissive: 0x3fd8c8, emissiveIntensity: 2.2, transparent: true, opacity: .55, side: 2 }, y: 0.55, s: 1 },
      { geo: () => new THREE.BoxGeometry(0.5, 0.1, 0.22), mat: { color: 0x6b7382, roughness: .8 }, y: 0.05, s: 1 },
    ],
    [KIND.LANTERN]: [
      { geo: () => new THREE.CylinderGeometry(0.05, 0.09, 1, 5), mat: { color: 0x4a4033, roughness: .9 }, y: 0.5, s: 1, stretch: true },
      { geo: () => new THREE.IcosahedronGeometry(0.24, 0), mat: { color: 0xffe6a0, emissive: 0xffc060, emissiveIntensity: 2.4 }, y: 1.0, s: 1 },
    ],
    [KIND.LUREMAST]: [
      { geo: () => new THREE.CylinderGeometry(0.05, 0.12, 1, 5), mat: { color: 0x7d8695, roughness: .6, metalness: .5 }, y: 0.5, s: 1, stretch: true },
      { geo: () => new THREE.ConeGeometry(0.26, 0.4, 6), mat: { color: 0xff8a3c, emissive: 0xff5a1a, emissiveIntensity: 2.2 }, y: 1.02, s: 1 },
      { geo: () => new THREE.TorusGeometry(0.22, 0.03, 4, 12), mat: { color: 0xff8a3c, emissive: 0xff5a1a, emissiveIntensity: 1.6 }, y: 0.74, s: 1, flat: true },
    ],
    [KIND.CRATER]: [
      { geo: () => new THREE.TorusGeometry(0.9, 0.16, 5, 16), mat: { color: 0x4a423c, roughness: .98, flatShading: true }, y: 0.06, s: 1, flat: true },
      { geo: () => new THREE.IcosahedronGeometry(0.24, 0), mat: { color: 0xd8934a, emissive: 0x7a3a10, emissiveIntensity: 1.1, flatShading: true }, y: 0.12, s: 1 },
    ],
    [KIND.ANCHOR]: [
      { geo: () => new THREE.CylinderGeometry(0.09, 0.16, 1, 6), mat: { color: 0x9aa4b3, roughness: .45, metalness: .7 }, y: 0.5, s: 1, stretch: true },
      { geo: () => new THREE.TorusGeometry(0.34, 0.05, 5, 16), mat: { color: 0xbfe9ff, emissive: 0x4fa8ff, emissiveIntensity: 2.0 }, y: 0.9, s: 1, flat: true },
      { geo: () => new THREE.SphereGeometry(0.15, 10, 8), mat: { color: 0xdfe9f5, emissive: 0x8fd0ff, emissiveIntensity: 1.4 }, y: 1.03, s: 1 },
    ],
    [KIND.ANOMALY]: [
      { geo: () => new THREE.OctahedronGeometry(0.30, 0), mat: { color: 0xc07fe8, emissive: 0x8a2fd8, emissiveIntensity: 1.8, flatShading: true, roughness: .2 }, y: 0.5, s: 1, stretch: true },
    ],
    [KIND.RIFTENGINE]: [
      { geo: () => new THREE.BoxGeometry(0.7, 0.5, 0.5), mat: { color: 0x5b5468, roughness: .7, metalness: .3, flatShading: true }, y: 0.28, s: 1 },
      { geo: () => new THREE.BoxGeometry(0.08, 1.0, 0.08), mat: { color: 0xd86fff, emissive: 0xc02fff, emissiveIntensity: 2.6 }, y: 0.62, s: 1 },
    ],
    [KIND.STELLARTAP]: [
      { geo: () => new THREE.CylinderGeometry(0.04, 0.14, 1, 6), mat: { color: 0x8b95a3, roughness: .5, metalness: .6 }, y: 0.5, s: 1, stretch: true },
      { geo: () => new THREE.SphereGeometry(0.22, 10, 8), mat: { color: 0xfff0c0, emissive: 0xffc040, emissiveIntensity: 2.8 }, y: 1.0, s: 1 },
      { geo: () => new THREE.TorusGeometry(0.34, 0.028, 4, 14), mat: { color: 0xffd070, emissive: 0xffa020, emissiveIntensity: 1.8 }, y: 0.84, s: 1, flat: true },
    ],
    [KIND.TOWER]: [
      { geo: () => new THREE.BoxGeometry(0.62, 1, 0.62), mat: { color: 0x8e97a6, roughness: .7, metalness: .2, flatShading: true }, y: 0.5, s: 1, stretch: true },
      { geo: () => new THREE.BoxGeometry(0.68, 0.05, 0.68), mat: { color: 0xff4fd8, emissive: 0xff2fc8, emissiveIntensity: 2.2 }, y: 0.62, s: 1 },
      { geo: () => new THREE.BoxGeometry(0.68, 0.05, 0.68), mat: { color: 0x4fe8ff, emissive: 0x2fd8ff, emissiveIntensity: 2.2 }, y: 0.86, s: 1 },
    ],
  };

  const CAP = 1400;                       // instances per part
  const banks = [];                       // {kind, part, mesh}

  // A model pack REPLACES the code-drawn parts for a kind, and the fallback is
  // the whole point: with no pack, `modelParts` returns null and the game looks
  // and runs exactly as it did before there were any assets at all.
  let modelPack = null;
  let builtRev = -1;      // declared HERE because buildBanks() below resets it

  function partsFor(kind) {
    return (modelPack && modelParts(modelPack, kind)) || PARTS[kind];
  }

  function buildBanks() {
    for (const b of banks) {
      scene.remove(b.mesh);
      b.mesh.geometry.dispose();
      if (b.mesh.material.dispose) b.mesh.material.dispose();
    }
    banks.length = 0;
    for (const kind of Object.keys(PARTS)) {
      for (const part of partsFor(kind)) {
        const geo = part.geo();
        const mat = part.material ? part.material : new THREE.MeshStandardMaterial(part.mat);
        const mesh = new THREE.InstancedMesh(geo, mat, CAP);
        mesh.frustumCulled = false;
        mesh.count = 0;
        scene.add(mesh);
        banks.push({ kind, part, mesh });
      }
    }
    builtRev = -1;                        // force a rebuild of the instances
  }
  buildBanks();

  /** Swap in real geometry once a pack has loaded. Safe to never call. */
  function useModelPack(p) {
    modelPack = p || null;
    buildBanks();
    if (modelPack) {
      const body = playerModel(modelPack, C.player.height);
      if (body) {
        you.clear();
        you.add(body);
        limbs.length = 0;                 // the model brings its own limbs
      }
    }
  }

  const _m = new THREE.Matrix4();
  const _q = new THREE.Quaternion();
  const _pv = new THREE.Vector3();
  const _sv = new THREE.Vector3();
  const _up = new THREE.Vector3(0, 1, 0);
  const _n = new THREE.Vector3();


  function rebuild(w) {
    for (const b of banks) b.mesh.count = 0;
    for (const o of w.objects) {
      const s = spec(o.kind);
      const h = s.stages[o.stage].h;
      const dir = o.dir;
      const gr = P.groundR(dir);
      _n.set(dir.x, dir.y, dir.z);
      _q.setFromUnitVectors(_up, _n);
      for (const b of banks) {
        if (b.kind !== o.kind) continue;
        if (b.mesh.count >= CAP) continue;
        const part = b.part;
        const lift = part.y * h;
        const along = gr + lift;
        _pv.set(dir.x * along, dir.y * along, dir.z * along);
        if (part.front) {
          // push a door or a vent out to the face of its own building
          const bs = P.basis(dir);
          const off = part.front * h;
          _pv.x += bs.north.x * off; _pv.y += bs.north.y * off; _pv.z += bs.north.z * off;
        }
        const sc = h * part.s;
        _sv.set(sc, part.stretch ? h : sc, sc);
        if (part.flat) {
          const flatQ = new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(1, 0, 0), Math.PI / 2);
          _m.compose(_pv, _q.clone().multiply(flatQ), _sv);
        } else {
          _m.compose(_pv, _q, _sv);
        }
        b.mesh.setMatrixAt(b.mesh.count++, _m);
      }
    }
    for (const b of banks) b.mesh.instanceMatrix.needsUpdate = true;
  }

  /* ---------------------------------------------------------- astronaut -- */
  const you = new THREE.Group();
  const suit = new THREE.MeshStandardMaterial({ color: 0xe9edf2, roughness: .55, metalness: .1, flatShading: true, emissive: 0x2a3242, emissiveIntensity: 1 });
  const dark = new THREE.MeshStandardMaterial({ color: 0x59647a, roughness: .7, metalness: .2, emissive: 0x1a2130, emissiveIntensity: 1 });
  const visor = new THREE.MeshStandardMaterial({ color: 0x1b2b3a, emissive: 0x3fa8d8, emissiveIntensity: .55, roughness: .18, metalness: .6 });
  const torso = new THREE.Mesh(new THREE.CapsuleGeometry(0.30, 0.52, 3, 8), suit);
  torso.position.y = 1.06;
  you.add(torso);
  const helm = new THREE.Mesh(new THREE.SphereGeometry(0.28, 12, 10), suit);
  helm.position.y = 1.60;
  you.add(helm);
  const glass = new THREE.Mesh(new THREE.SphereGeometry(0.235, 12, 10), visor);
  glass.position.set(0, 1.60, 0.13);
  you.add(glass);
  const pack = new THREE.Mesh(new THREE.BoxGeometry(0.44, 0.5, 0.24), dark);
  pack.position.set(0, 1.12, -0.32);
  you.add(pack);
  const lamp = new THREE.Mesh(new THREE.SphereGeometry(0.09, 8, 6),
    new THREE.MeshBasicMaterial({ color: 0xfff0c0 }));
  lamp.position.set(0.24, 1.62, 0.10);
  you.add(lamp);
  const limbs = [];
  for (const [x, z, isLeg] of [[-0.34, 0, false], [0.34, 0, false], [-0.15, 0, true], [0.15, 0, true]]) {
    const l = new THREE.Mesh(new THREE.CapsuleGeometry(0.10, isLeg ? 0.55 : 0.44, 3, 6), isLeg ? dark : suit);
    l.position.set(x, isLeg ? 0.40 : 1.06, z);
    you.add(l);
    limbs.push({ mesh: l, isLeg, side: Math.sign(x) });
  }
  scene.add(you);

  const headLight = new THREE.PointLight(0xffe8bf, 0, 26, 2);
  scene.add(headLight);

  /* ---------------------------------------------------------- placement -- */
  const ghostMat = new THREE.MeshBasicMaterial({ color: 0x7fe8b0, transparent: true, opacity: .5, wireframe: true });
  const ghost = new THREE.Mesh(new THREE.CylinderGeometry(0.6, 0.6, 1, 8), ghostMat);
  ghost.visible = false;
  scene.add(ghost);

  /* ------------------------------------------------------------- camera -- */
  const rig = { yaw: 0, pitch: 0.10, dist: 11.5 };   // pitch is an aim: 0 is level

  function resize() {
    const w = canvas.clientWidth || document.documentElement.clientWidth || window.innerWidth;
    const h = canvas.clientHeight || document.documentElement.clientHeight || window.innerHeight;
    if (w < 2 || h < 2) return false;
    if (canvas.width === Math.floor(w * Math.min(devicePixelRatio || 1, 2))) return true;
    renderer.setPixelRatio(Math.min(devicePixelRatio || 1, 2));
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
    return true;
  }
  if (typeof ResizeObserver !== 'undefined') {
    new ResizeObserver(() => resize()).observe(document.documentElement);
  }

  const tmpA = new THREE.Vector3();
  const tmpB = new THREE.Vector3();

  const fxLive = new Map();
  let sunOffset = null;

  function render(w, dt, ui) {
    if (!resize()) return;

    if (w.rev !== builtRev) { builtRev = w.rev; rebuild(w); }

    // --- sun: a real day, so neon has something to be brighter than.
    // It starts over wherever you landed: arriving in the dark on a planet you
    // have never seen is not an atmosphere, it is a black screen.
    if (sunOffset === null) {
      // Aligning only the azimuth left the sun below the horizon, because the
      // landing point is not on the equator. Build the day IN the plane of
      // where you actually are: overhead at t=0, then it sets.
      const p0 = w.player.dir;
      const b0 = P.basis(p0);
      sunOffset = { up: p0, east: b0.east, t0: w.t };
    }
    const ang = ((w.t - sunOffset.t0) / C.day) * Math.PI * 2;
    const ca = Math.cos(ang), sa = Math.sin(ang);
    const sd = new THREE.Vector3(
      sunOffset.up.x * ca + sunOffset.east.x * sa,
      sunOffset.up.y * ca + sunOffset.east.y * sa,
      sunOffset.up.z * ca + sunOffset.east.z * sa,
    ).normalize();
    sun.position.copy(sd).multiplyScalar(900);
    api.sunDir = sd;          // events site their work where the sun actually is
    const p = w.player.dir;
    const daylight = Math.max(0, sd.x * p.x + sd.y * p.y + sd.z * p.z);
    sun.intensity = 0.15 + 2.2 * daylight;
    hemi.intensity = 0.14 + 0.72 * daylight;
    amb.intensity = 0.30 + 0.20 * daylight;
    const night = 1 - Math.min(1, daylight * 2.4);
    headLight.intensity = 9 * night;
    renderer.setClearColor(new THREE.Color(0x060912).lerp(new THREE.Color(0x2c5f86), daylight), 1);
    oceanMat.color.setHex(0x0b3550).lerp(new THREE.Color(0x2a86ad), daylight);

    // --- the astronaut stands on the ground, not near it
    const gr = P.groundR(p);
    const up = new THREE.Vector3(p.x, p.y, p.z);
    you.position.copy(up).multiplyScalar(gr);
    const f = P.facing(p, w.player.yaw);
    tmpA.set(f.x, f.y, f.z);
    const m = new THREE.Matrix4().lookAt(new THREE.Vector3(0, 0, 0), tmpA.clone().negate(), up);
    you.quaternion.setFromRotationMatrix(m);

    // walk cycle
    const moving = ui && ui.moving;
    const phase = w.t * (ui && ui.running ? 11 : 7);
    for (const l of limbs) {
      const swing = moving ? Math.sin(phase + (l.isLeg ? 0 : Math.PI)) * (l.isLeg ? 0.55 : 0.42) * l.side : 0;
      l.mesh.rotation.x = swing;
    }
    headLight.position.copy(you.position).addScaledVector(up, 1.7);

    // --- camera behind the shoulder.
    //
    // Pitch tilts the VIEW DIRECTION, not just the camera's height. The first
    // two attempts moved the eye and kept it staring at the player's feet, so
    // the sky always fell off the top of the frame and a black hole hanging
    // overhead was unseeable however far you dragged. A third-person camera
    // that cannot look up cannot show you the thing it wants you to dread.
    const b = P.basis(p);
    const camYaw = w.player.yaw + rig.yaw;
    const fwd = P.add(P.mul(b.north, Math.cos(camYaw)), P.mul(b.east, Math.sin(camYaw)));
    const back = P.mul(fwd, -1);

    const head = new THREE.Vector3(
      you.position.x + up.x * 1.5, you.position.y + up.y * 1.5, you.position.z + up.z * 1.5);

    // The eye sits behind and a little above, always. It does not dive.
    const EYE_LIFT = 0.38;
    const eye = new THREE.Vector3(
      head.x + back.x * rig.dist + up.x * EYE_LIFT * rig.dist,
      head.y + back.y * rig.dist + up.y * EYE_LIFT * rig.dist,
      head.z + back.z * rig.dist + up.z * EYE_LIFT * rig.dist,
    );

    // never inside a hill...
    const eyeDir = P.norm({ x: eye.x, y: eye.y, z: eye.z });
    const minR = P.groundR(eyeDir) + 1.1;
    if (eye.length() < minR) eye.setLength(minR);

    // ...and never inside a tree. A camera parked in a trunk shows a wall and
    // reads as a crash. A tree's blocking radius is its TRUNK, not its height.
    const cray = eye.clone().sub(head);
    const full = cray.length();
    if (full > 0.01) {
      cray.multiplyScalar(1 / full);
      let allowed = full;
      for (const o of w.objects) {
        if (P.surfDist(o.dir, p) > full + 6) continue;
        const s2 = spec(o.kind);
        if (!s2) continue;
        const hh = s2.stages[o.stage].h;
        const rad = hh * 0.20;
        if (rad < 0.5) continue;
        const gr2 = P.groundR(o.dir);
        const c = new THREE.Vector3(o.dir.x * gr2, o.dir.y * gr2, o.dir.z * gr2)
          .addScaledVector(new THREE.Vector3(o.dir.x, o.dir.y, o.dir.z), hh * 0.5);
        const rel = c.sub(head);
        const along = rel.dot(cray);
        if (along <= 0 || along > allowed) continue;
        const perp = Math.sqrt(Math.max(0, rel.lengthSq() - along * along));
        if (perp < rad) allowed = Math.max(5.0, along - rad * 0.9);
      }
      // Pulling in is not enough on its own: a trunk right behind you leaves the
      // eye at the minimum distance and still inside the bark. So when the way
      // is blocked, RISE as well -- look over the thing rather than through it.
      if (allowed < full) {
        const lift = (1 - allowed / full) * rig.dist * 0.85;
        eye.copy(head).addScaledVector(cray, allowed).addScaledVector(up, lift);
        const d2 = P.norm({ x: eye.x, y: eye.y, z: eye.z });
        const floor2 = P.groundR(d2) + 1.1;
        if (eye.length() < floor2) eye.setLength(floor2);
      }
    }

    camera.position.copy(eye);
    camera.up.copy(up);

    // rig.pitch is an AIM measured from LOOKING AT YOU, not from the horizon.
    // Measuring it from horizontal put the eye above the head and then tilted
    // up from there, which shoved the astronaut off the bottom of the frame.
    // 0 is the classic over-shoulder shot; 1.25 is nearly straight up.
    const aim = Math.max(-0.25, Math.min(1.25, rig.pitch));
    const base = new THREE.Vector3().subVectors(head, eye).normalize();
    const side = new THREE.Vector3().crossVectors(base, up).normalize();
    const look = base.clone().applyAxisAngle(side, -aim);
    tmpB.copy(eye).addScaledVector(look, 140);
    camera.lookAt(tmpB);

    // --- the ghost of what you are about to plant
    if (ui && ui.ghost) {
      const gd = ui.ghost.dir;
      const ggr = P.groundR(gd);
      ghost.position.set(gd.x * ggr, gd.y * ggr, gd.z * ggr);
      ghost.quaternion.setFromUnitVectors(_up, new THREE.Vector3(gd.x, gd.y, gd.z));
      const hh = spec(ui.ghost.kind).stages[0].h * 1.4;
      ghost.scale.set(1, hh, 1);
      ghost.position.addScaledVector(new THREE.Vector3(gd.x, gd.y, gd.z), hh / 2);
      ghostMat.color.setHex(ui.ghost.ok ? 0x7fe8b0 : 0xe8705f);
      ghost.visible = true;
    } else {
      ghost.visible = false;
    }

    // ---- cosmic events -------------------------------------------------
    // Built on first sight, disposed when the event ends. An event that never
    // happens costs nothing.
    for (const ev of (w.events || [])) {
      let live = fxLive.get(ev.id);
      if (!live) {
        const mod = fxFor(ev.id);
        if (!mod || !mod.build) { fxLive.set(ev.id, { mod: null }); continue; }
        const root = new THREE.Group();
        fxRoot.add(root);
        const ctx = { THREE, scene, sky, root, P, C, camera, glowTexture, sun, renderer };
        let self = null;
        try { self = mod.build(ctx); }
        catch (e) { console.warn('fx build failed for ' + ev.id, e); }
        live = { mod, self, ctx, root };
        fxLive.set(ev.id, live);
      }
      if (live.mod && live.mod.update) {
        try { live.mod.update(live.self, ev, w, dt, live.ctx); }
        catch (e) { /* one bad effect must not take the frame down */ }
      }
    }
    for (const [id, live] of fxLive) {
      if ((w.events || []).some(e => e.id === id)) continue;
      if (live.mod && live.mod.dispose) {
        try { live.mod.dispose(live.self, live.ctx); } catch (e) {}
      }
      if (live.root) fxRoot.remove(live.root);
      fxLive.delete(id);
    }

    renderer.render(scene, camera);
  }

  const api = { render, renderer, scene, camera, rig, you, rebuild, sky, fxRoot, glowTexture, useModelPack, sunDir: null };
  return api;
}
