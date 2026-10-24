// AURA — characters. Anime cel look: MeshToonMaterial (3-step gradient) + inverted-hull
// outlines, every mesh built from primitives, every animation procedural.
//
// DIR CONVENTION: enemy s.dir (ogre windup/slam/sweep, goblin windup/attack) is from the VIEWER's
// side (player facing the enemy): 'left' = blade comes from the player's screen-left = the enemy's
// OWN RIGHT; player/viewmodel attack+parry dir is the player's own ('left' = sweeps from his left).
//
// Conventions (see CONTRACT.md §2): metres, Y up, feet at the local origin, facing +Z.
// The character's own LEFT is +X, own RIGHT is -X.  Internally frames are authored per OWN side
// ('R' / 'L' / 'over') and mapped from s.dir by each actor's dirOf().
//
// Rigs are nested Groups (hips > spine > chest > neck > head, shoulders > elbows > wrists,
// hips > knees > ankles).  Arms are driven by two-bone IK: a pose names where the WRIST goes
// (root space), which way the held weapon/grip axis points, and where the elbow points.
// Everything else is Euler rotations.  Poses are keyframed channel arrays, so nothing
// allocates per frame.

import * as THREE from './vendor/three/three.module.js';

const V3 = THREE.Vector3;
const PI = Math.PI, TAU = Math.PI * 2;
const clamp = (x, a, b) => (x < a ? a : x > b ? b : x);
const lerp = (a, b, t) => a + (b - a) * t;
const sstep = (a, b, x) => { const u = clamp((x - a) / (b - a), 0, 1); return u * u * (3 - 2 * u); };

export const AURA_CYAN = 0x4fdcff;
const CYAN_LIN = new THREE.Color(AURA_CYAN);            // linear
const CYAN_HOT = CYAN_LIN.clone().multiplyScalar(3.0);   // HDR glow
const WHITE_HOT = new THREE.Color(3, 3, 3);

const EASE = {
  lin: (u) => u,
  io: (u) => u * u * (3 - 2 * u),
  in: (u) => u * u,
  out: (u) => 1 - (1 - u) * (1 - u),
  snap: (u) => 1 - Math.pow(1 - u, 5),
  back: (u) => { const c1 = 1.9, c3 = c1 + 1, v = u - 1; return 1 + c3 * v * v * v + c1 * v * v; },
};

// ------------------------------------------------------------------ shared GPU resources
function makeGradient() {
  const d = new Uint8Array([70, 155, 255]);
  const t = new THREE.DataTexture(d, 3, 1, THREE.RedFormat, THREE.UnsignedByteType);
  t.minFilter = THREE.NearestFilter; t.magFilter = THREE.NearestFilter;
  t.generateMipmaps = false; t.unpackAlignment = 1; t.needsUpdate = true;
  return t;
}
const GRAD = makeGradient();

const OUTLINE_VS = /* glsl */`
attribute vec3 onormal;
attribute float ow;
uniform float uMin;
uniform float uMax;
uniform float uPx;
#include <fog_pars_vertex>
void main() {
  vec4 mvPosition = modelViewMatrix * vec4(position, 1.0);
  vec3 n = normalize(normalMatrix * onormal);
  float d = max(0.05, -mvPosition.z);
  float w = ow * clamp(uPx * d, uMin, uMax);
  mvPosition.xyz += n * w;
  gl_Position = projectionMatrix * mvPosition;
  #include <fog_vertex>
}`;
const OUTLINE_FS = /* glsl */`
uniform vec3 uColor;
#include <fog_pars_fragment>
void main() {
  gl_FragColor = vec4(uColor, 1.0);
  #include <fog_fragment>
}`;
const _outlines = new Map();
// World-space thickness that tracks ~uPx per metre of distance (so it stays a few pixels wide),
// clamped per character: the ogre may go thick, the viewmodel stays hairline.
function outlineMat(minW, maxW, px = 0.0024) {
  const key = minW + '|' + maxW + '|' + px;
  let m = _outlines.get(key);
  if (m) return m;
  m = new THREE.ShaderMaterial({
    uniforms: THREE.UniformsUtils.merge([THREE.UniformsLib.fog, {
      uMin: { value: minW }, uMax: { value: maxW }, uPx: { value: px }, uColor: { value: new THREE.Color(0x040308) },
    }]),
    vertexShader: OUTLINE_VS, fragmentShader: OUTLINE_FS, side: THREE.BackSide, fog: true,
  });
  _outlines.set(key, m);
  return m;
}

const toonMat = (side = THREE.FrontSide) =>
  new THREE.MeshToonMaterial({ vertexColors: true, gradientMap: GRAD, side });
const SHARED = {
  unlit: new THREE.MeshBasicMaterial({ vertexColors: true }),
  goblinEye: new THREE.MeshBasicMaterial({ color: new THREE.Color(3.2, 2.4, 0.35), fog: false }),
  ogreEye: new THREE.MeshBasicMaterial({ color: new THREE.Color(4.0, 0.35, 0.18), fog: false }),
};

const VEIN_VS = /* glsl */`
varying vec2 vUv;
varying vec3 vN;
varying vec3 vV;
void main() {
  vUv = uv;
  vec4 mv = modelViewMatrix * vec4(position, 1.0);
  vN = normalize(normalMatrix * normal);
  vV = normalize(-mv.xyz);
  gl_Position = projectionMatrix * mv;
}`;
const VEIN_FS = /* glsl */`
uniform float uLevel;
uniform float uTime;
uniform float uShell;
uniform vec3 uColor;
varying vec2 vUv;
varying vec3 vN;
varying vec3 vV;
void main() {
  float s = vUv.x;
  float wave = 0.5 + 0.5 * sin(s * 15.0 - uTime * 9.0);
  float surge = 0.35 + 1.5 * pow(wave, 4.0);
  float fall = smoothstep(0.0, 0.1, s) * (1.0 - smoothstep(0.82, 1.0, s));
  float lvl = uLevel * fall;
  vec3 c;
  if (uShell > 0.5) {
    float f = pow(clamp(dot(normalize(vN), normalize(vV)), 0.0, 1.0), 1.3);
    c = uColor * (0.45 + surge) * f * lvl * 1.3;
  } else {
    c = mix(uColor * 1.6, vec3(3.0), 0.25 + 0.55 * pow(wave, 3.0)) * (0.55 + 0.6 * surge) * lvl;
  }
  gl_FragColor = vec4(c, 1.0);
}`;
function veinMats() {
  const u = { uLevel: { value: 0 }, uTime: { value: 0 } };
  const mk = (shell) => new THREE.ShaderMaterial({
    uniforms: { uLevel: u.uLevel, uTime: u.uTime, uShell: { value: shell }, uColor: { value: CYAN_LIN.clone().multiplyScalar(1.4) } },
    vertexShader: VEIN_VS, fragmentShader: VEIN_FS,
    transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, fog: false,
  });
  return { u, core: mk(0), shell: mk(1) };
}

// ------------------------------------------------------------------ geometry builder
// Merges primitives into ONE BufferGeometry per rigid bone, with per-vertex colour (so one
// material per character), plus `onormal` (normals averaged per position, so the outline hull
// does not crack at hard edges) and `ow` (per-part outline weight: 0 for eyes, <1 for blades).
const _e = new THREE.Euler(), _qa = new THREE.Quaternion(), _va = new V3(), _vb = new V3();
function mat(p = [0, 0, 0], r = [0, 0, 0], s = [1, 1, 1]) {
  return new THREE.Matrix4().compose(_va.set(p[0], p[1], p[2]), _qa.setFromEuler(_e.set(r[0], r[1], r[2])), _vb.set(s[0], s[1], s[2]));
}
const UPV = new V3(0, 1, 0);
function aimMat(a, b) {
  const A = new V3(a[0], a[1], a[2]), d = new V3(b[0] - a[0], b[1] - a[1], b[2] - a[2]);
  const len = d.length() || 1e-4; d.divideScalar(len);
  const q = new THREE.Quaternion().setFromUnitVectors(UPV, d);
  return { m: new THREE.Matrix4().compose(A, q, new V3(1, 1, 1)), len };
}
function capsuleGeo(len, r0, r1, seg = 10, cap = 4) {
  const pts = [];
  for (let i = 0; i <= cap; i++) { const a = -PI / 2 + (i / cap) * PI / 2; pts.push(new THREE.Vector2(Math.cos(a) * r0, Math.sin(a) * r0)); }
  for (let i = 0; i <= cap; i++) { const a = (i / cap) * PI / 2; pts.push(new THREE.Vector2(Math.cos(a) * r1, len + Math.sin(a) * r1)); }
  return new THREE.LatheGeometry(pts, seg);
}

class GB {
  constructor() { this.P = []; this.N = []; this.O = []; this.C = []; this.W = []; this.I = []; this.n = 0; }
  geo(g, color, m = new THREE.Matrix4(), ow = 1) {
    if (!g.attributes.normal) g.computeVertexNormals();
    const pa = g.attributes.position, na = g.attributes.normal, cnt = pa.count;
    const nm = new THREE.Matrix3().getNormalMatrix(m);
    const col = new THREE.Color(color);
    const base = this.n, flip = m.determinant() < 0;
    const v = new V3(), nv = new V3(), acc = new Map(), ks = new Array(cnt), nrm = [];
    for (let i = 0; i < cnt; i++) {
      v.fromBufferAttribute(pa, i).applyMatrix4(m);
      nv.fromBufferAttribute(na, i).applyMatrix3(nm).normalize();
      this.P.push(v.x, v.y, v.z); this.N.push(nv.x, nv.y, nv.z); nrm.push(nv.clone());
      this.C.push(col.r, col.g, col.b); this.W.push(ow);
      const k = Math.round(v.x * 2e4) + ',' + Math.round(v.y * 2e4) + ',' + Math.round(v.z * 2e4);
      let a = acc.get(k); if (!a) { a = new V3(); acc.set(k, a); }
      a.add(nv); ks[i] = a;
    }
    for (let i = 0; i < cnt; i++) {
      const a = ks[i], l = a.length();
      if (l < 1e-4) this.O.push(nrm[i].x, nrm[i].y, nrm[i].z); else this.O.push(a.x / l, a.y / l, a.z / l);
    }
    const idx = g.index, tri = idx ? idx.count : cnt;
    for (let i = 0; i < tri; i += 3) {
      const a = idx ? idx.getX(i) : i, b = idx ? idx.getX(i + 1) : i + 1, c = idx ? idx.getX(i + 2) : i + 2;
      if (flip) this.I.push(base + a, base + c, base + b); else this.I.push(base + a, base + b, base + c);
    }
    this.n += cnt; g.dispose();
    return this;
  }
  limb(a, b, r0, r1, color, ow = 1, seg = 10) { const { m, len } = aimMat(a, b); return this.geo(capsuleGeo(len, r0, r1, seg), color, m, ow); }
  ell(p, r, color, rot = [0, 0, 0], ow = 1, seg = 14) {
    return this.geo(new THREE.SphereGeometry(1, seg, Math.max(6, (seg * 0.7) | 0)), color, mat(p, rot, r), ow);
  }
  box(p, s, color, rot = [0, 0, 0], ow = 1) { return this.geo(new THREE.BoxGeometry(s[0], s[1], s[2]), color, mat(p, rot), ow); }
  cyl(p, rt, rb, h, color, rot = [0, 0, 0], ow = 1, seg = 12) {
    return this.geo(new THREE.CylinderGeometry(rt, rb, h, seg, 1), color, mat(p, rot), ow);
  }
  cone(a, b, r, color, ow = 1, seg = 8) { // base at a (radius r), tip at b
    const { m, len } = aimMat(a, b);
    const g = new THREE.CylinderGeometry(0, r, len, seg, 1); g.translate(0, len / 2, 0);
    return this.geo(g, color, m, ow);
  }
  frustum(a, b, r0, r1, color, ow = 1, seg = 8) {
    const { m, len } = aimMat(a, b);
    const g = new THREE.CylinderGeometry(r1, r0, len, seg, 1); g.translate(0, len / 2, 0);
    return this.geo(g, color, m, ow);
  }
  torus(p, R, r, color, rot = [0, 0, 0], scl = [1, 1, 1], ow = 1, arc = TAU) {
    return this.geo(new THREE.TorusGeometry(R, r, 6, 24, arc), color, mat(p, rot, scl), ow);
  }
  // A chain of capsules through points with per-point radii (horns, tails, locks of hair).
  chain(pts, radii, color, ow = 1) {
    for (let i = 0; i < pts.length - 1; i++) this.limb(pts[i], pts[i + 1], radii[i], radii[i + 1], color, ow, 8);
    return this;
  }
  build() {
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute(this.P, 3));
    g.setAttribute('normal', new THREE.Float32BufferAttribute(this.N, 3));
    g.setAttribute('color', new THREE.Float32BufferAttribute(this.C, 3));
    g.setAttribute('onormal', new THREE.Float32BufferAttribute(this.O, 3));
    g.setAttribute('ow', new THREE.Float32BufferAttribute(this.W, 1));
    g.setIndex(this.I);
    g.computeBoundingSphere();
    return g;
  }
}

// Simple merge that keeps uv (vein tubes need the along-length coordinate).
function mergeUV(geos) {
  const P = [], N = [], U = [], I = []; let n = 0;
  for (const g of geos) {
    const p = g.attributes.position, nn = g.attributes.normal, uv = g.attributes.uv;
    for (let i = 0; i < p.count; i++) { P.push(p.getX(i), p.getY(i), p.getZ(i)); N.push(nn.getX(i), nn.getY(i), nn.getZ(i)); U.push(uv.getX(i), uv.getY(i)); }
    const idx = g.index;
    for (let i = 0; i < idx.count; i++) I.push(n + idx.getX(i));
    n += p.count; g.dispose();
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(P, 3));
  g.setAttribute('normal', new THREE.Float32BufferAttribute(N, 3));
  g.setAttribute('uv', new THREE.Float32BufferAttribute(U, 2));
  g.setIndex(I); g.computeBoundingSphere();
  return g;
}

function rng(seed) {
  let a = (seed | 0) + 0x6d2b79f5;
  return () => { a = (a + 0x6d2b79f5) | 0; let t = Math.imul(a ^ (a >>> 15), 1 | a); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
}

// Veins on a forearm that runs from the elbow (y=0) down to y=-len, radius r0 -> r1.
function veinGeo(len, r0, r1, seed, coreR = 0.0017, shellR = 0.0048) {
  const R = rng(seed), curves = [];
  const surf = (s, th) => { const r = lerp(r0, r1, s) + 0.0012; return new V3(Math.cos(th) * r, -0.03 - s * (len - 0.06), Math.sin(th) * r); };
  const starts = [-2.6, -1.7, -0.9, 0.2, 1.1, 2.1, 2.9];
  for (const th0 of starts) {
    const drift = (R() - 0.5) * 1.4, wob = 0.15 + R() * 0.25, ph = R() * 6;
    const pts = [];
    for (let i = 0; i <= 8; i++) { const s = i / 8; pts.push(surf(s, th0 + drift * s + wob * Math.sin(s * 5 + ph))); }
    curves.push(new THREE.CatmullRomCurve3(pts));
    // a branch peeling off the main vein
    const s0 = 0.25 + R() * 0.35, dir = R() < 0.5 ? -1 : 1, bl = [];
    for (let i = 0; i <= 5; i++) {
      const s = s0 + i * 0.07; if (s > 1) break;
      bl.push(surf(s, th0 + drift * s + wob * Math.sin(s * 5 + ph) + dir * i * 0.16));
    }
    if (bl.length > 2) curves.push(new THREE.CatmullRomCurve3(bl));
  }
  const core = mergeUV(curves.map((c) => new THREE.TubeGeometry(c, 28, coreR, 5, false)));
  const shell = mergeUV(curves.map((c) => new THREE.TubeGeometry(c, 28, shellR, 6, false)));
  return { core, shell };
}

// ------------------------------------------------------------------ scene-graph helpers
function jg(parent, name, x = 0, y = 0, z = 0) {
  const g = new THREE.Group(); g.name = name; g.position.set(x, y, z); parent.add(g); return g;
}
function sock(parent, name, x = 0, y = 0, z = 0) {
  const o = new THREE.Object3D(); o.name = 'socket:' + name; o.position.set(x, y, z); parent.add(o); return o;
}
const noRay = () => {};
function mk(parent, geo, material, outline, { shadow = true } = {}) {
  const m = new THREE.Mesh(geo, material);
  m.castShadow = shadow; m.receiveShadow = true;
  if (outline) { const o = new THREE.Mesh(geo, outline); o.castShadow = false; o.receiveShadow = false; o.raycast = noRay; m.add(o); }
  parent.add(m);
  return m;
}

// ------------------------------------------------------------------ pose channels
const JN = ['hips', 'spine', 'chest', 'neck', 'head', 'upL', 'knL', 'anL', 'upR', 'knR', 'anR', 'jaw'];
const CH = {}; let NC = 0;
for (const j of JN) { CH[j] = NC; NC += 3; }
for (const k of ['hp', 'hR', 'bR', 'pR', 'hL', 'bL', 'pL']) { CH[k] = NC; NC += 3; }
CH.gL = NC++; CH.gR = NC++;

// Compile a readable pose object {joint:[x,y,z], hR:[...], gL: n} into channel arrays.
function P(o) {
  const v = new Float32Array(NC), m = new Uint8Array(NC);
  for (const k in o) {
    const c = CH[k]; if (c === undefined) throw new Error('actors: unknown channel ' + k);
    const a = o[k];
    if (typeof a === 'number') { v[c] = a; m[c] = 1; } else for (let i = 0; i < a.length; i++) { v[c + i] = a[i]; m[c + i] = 1; }
  }
  return { v, m };
}
const K = (t, o, e = 'io') => ({ t, p: P(o), e: EASE[e] });

function sample(frames, t, base, out) {
  const n = frames.length;
  let i = 0; while (i < n - 2 && t >= frames[i + 1].t) i++;
  const A = frames[i], B = frames[Math.min(i + 1, n - 1)];
  let u = B.t > A.t ? clamp((t - A.t) / (B.t - A.t), 0, 1) : 1;
  u = B.e(u);
  const av = A.p.v, am = A.p.m, bv = B.p.v, bm = B.p.m;
  for (let c = 0; c < NC; c++) {
    const a = am[c] ? av[c] : base[c], b = bm[c] ? bv[c] : base[c];
    out[c] = a + (b - a) * u;
  }
}
function blendInto(out, pose, w) {
  if (w <= 0) return;
  const v = pose.v, m = pose.m;
  for (let c = 0; c < NC; c++) if (m[c]) out[c] += (v[c] - out[c]) * w;
}

// ------------------------------------------------------------------ two-bone IK
const _S = new V3(), _D = new V3(), _P = new V3(), _U = new V3(), _E = new V3(), _F = new V3();
const _X = new V3(), _Y = new V3(), _Z = new V3(), _Bn = new V3(), _H = new V3(), _B = new V3(), _PL = new V3();
const _mB = new THREE.Matrix4(), _qU = new THREE.Quaternion(), _qF = new THREE.Quaternion(), _qH = new THREE.Quaternion();
const _qInv = new THREE.Quaternion(), _qEl = new THREE.Quaternion();
const AXX = new V3(1, 0, 0);

// Solves in ROOT space. S = shoulder position, qParent = shoulder's parent orientation, H = wrist
// target, B = grip axis (hand-local +Z: blade direction / thumb direction), P = elbow pole.
// Upper arm and forearm hang along local -Y; the elbow bends toward local +Z.
function solveArm(arm, S, qParent, H, B, Pv, shoulderOnly) {
  const a = arm.a, b = arm.b;
  _D.subVectors(H, S); let d = _D.length();
  if (d < 1e-5) { _D.set(0, -1, 0); d = 1e-5; } else _D.multiplyScalar(1 / d);
  d = clamp(d, Math.abs(a - b) + 1e-3, (a + b) * 0.9995);
  const cosA = clamp((a * a + d * d - b * b) / (2 * a * d), -1, 1), sinA = Math.sqrt(1 - cosA * cosA);
  _P.copy(Pv).addScaledVector(_D, -Pv.dot(_D));
  if (_P.lengthSq() < 1e-8) { _P.set(Math.abs(_D.x) < 0.9 ? 1 : 0, Math.abs(_D.x) < 0.9 ? 0 : 1, 0); _P.addScaledVector(_D, -_P.dot(_D)); }
  _P.normalize();
  _U.copy(_D).multiplyScalar(cosA).addScaledVector(_P, sinA);
  _E.copy(S).addScaledVector(_U, a);
  _F.copy(S).addScaledVector(_D, d).sub(_E).normalize();
  _Y.copy(_U).negate();
  _Z.copy(_F).addScaledVector(_U, -_F.dot(_U));
  if (_Z.lengthSq() < 1e-8) _Z.copy(_P).negate();
  _Z.normalize();
  _X.crossVectors(_Y, _Z);
  _mB.makeBasis(_X, _Y, _Z); _qU.setFromRotationMatrix(_mB);
  _qInv.copy(qParent).invert();
  arm.sh.quaternion.multiplyQuaternions(_qInv, _qU);
  if (shoulderOnly) return;
  const cosB = clamp((a * a + b * b - d * d) / (2 * a * b), -1, 1);
  _qEl.setFromAxisAngle(AXX, -(PI - Math.acos(cosB)));
  arm.el.quaternion.copy(_qEl);
  _qF.multiplyQuaternions(_qU, _qEl);
  _Bn.copy(B); if (_Bn.lengthSq() < 1e-8) _Bn.set(0, 0, 1); _Bn.normalize();
  _Y.copy(_F).addScaledVector(_Bn, -_F.dot(_Bn));
  if (_Y.lengthSq() < 1e-5) { _Y.copy(_P).addScaledVector(_Bn, -_P.dot(_Bn)); if (_Y.lengthSq() < 1e-8) _Y.set(0, 1, 0); }
  _Y.normalize().negate();
  _X.crossVectors(_Y, _Bn);
  _mB.makeBasis(_X, _Y, _Bn); _qH.setFromRotationMatrix(_mB);
  arm.wr.quaternion.copy(_qF).invert().multiply(_qH);
}

// ------------------------------------------------------------------ rig
const _mC = new THREE.Matrix4(), _qC = new THREE.Quaternion(), _t1 = new V3(), _t2 = new V3();
function chainPos(out, objs) { // position of the last object's origin in the space of objs[0].parent
  out.set(0, 0, 0);
  for (let i = objs.length - 1; i >= 0; i--) { objs[i].updateMatrix(); out.applyMatrix4(objs[i].matrix); }
  return out;
}

class Rig {
  constructor(root, J, arms) {
    this.root = root; this.J = J; this.arms = arms;
    this.hipsRest = J.hips.position.clone();
    this.base = new Float32Array(NC); this.tgt = new Float32Array(NC); this.cur = new Float32Array(NC);
    this.fresh = true;
    // rest ankle height = where the feet are with straight legs
    this.ankleH = J.anL ? chainPos(new V3(), [J.hips, J.upL, J.knL, J.anL]).y : 0;
    this.skipArm = { R: false, L: false }; // 'shoulder' = shoulder-only (ogre after sever)
  }
  step(dt, rate) {
    const k = this.fresh ? 1 : 1 - Math.exp(-rate * Math.max(0, dt));
    this.fresh = false;
    const c = this.cur, t = this.tgt;
    for (let i = 0; i < NC; i++) c[i] += (t[i] - c[i]) * k;
  }
  // ground: 0..1 weight of snapping the lowest ankle to the floor. pin: V3 the neck must sit at.
  apply(ground = 1, footComp = true, pin = null) {
    const c = this.cur, J = this.J;
    for (let j = 0; j < JN.length; j++) { const g = J[JN[j]]; if (g) { const o = j * 3; g.rotation.set(c[o], c[o + 1], c[o + 2]); } }
    J.hips.position.set(this.hipsRest.x + c[CH.hp], this.hipsRest.y + c[CH.hp + 1], this.hipsRest.z + c[CH.hp + 2]);
    if (J.anL && footComp) {
      const hx = c[CH.hips], hz = c[CH.hips + 2];
      J.anL.rotation.x -= (hx + c[CH.upL] + c[CH.knL]) * 0.9; J.anL.rotation.z -= (hz + c[CH.upL + 2]) * 0.8;
      J.anR.rotation.x -= (hx + c[CH.upR] + c[CH.knR]) * 0.9; J.anR.rotation.z -= (hz + c[CH.upR + 2]) * 0.8;
    }
    if (ground > 0 && J.anL) {
      const yl = chainPos(_t1, [J.hips, J.upL, J.knL, J.anL]).y, yr = chainPos(_t2, [J.hips, J.upR, J.knR, J.anR]).y;
      J.hips.position.y += (this.ankleH - Math.min(yl, yr)) * ground;
    }
    if (pin) {
      chainPos(_t1, [J.hips, J.spine, J.chest, J.neck]);
      J.hips.position.x += pin.x - _t1.x; J.hips.position.y += pin.y - _t1.y; J.hips.position.z += pin.z - _t1.z;
    }
    J.hips.updateMatrix(); J.spine.updateMatrix(); J.chest.updateMatrix();
    _mC.multiplyMatrices(J.hips.matrix, J.spine.matrix).multiply(J.chest.matrix);
    _qC.copy(J.hips.quaternion).multiply(J.spine.quaternion).multiply(J.chest.quaternion);
    this.solveArms(_mC, _qC);
  }
  solveArms(mParent, qParent) {
    const c = this.cur;
    for (const side of ['R', 'L']) {
      const arm = this.arms[side]; if (!arm || this.skipArm[side] === true) continue;
      const h = side === 'R' ? CH.hR : CH.hL, b = side === 'R' ? CH.bR : CH.bL, p = side === 'R' ? CH.pR : CH.pL;
      _S.copy(arm.sh.position); if (mParent) _S.applyMatrix4(mParent);
      _H.set(c[h], c[h + 1], c[h + 2]); _B.set(c[b], c[b + 1], c[b + 2]); _PL.set(c[p], c[p + 1], c[p + 2]);
      solveArm(arm, _S, qParent, _H, _B, _PL, this.skipArm[side] === 'shoulder');
    }
  }
}

// Locomotion + breathing into `out`. G: gait parameters (G.idle is a full pose).
function gait(out, st, s, dt, G) {
  out.set(G.idle.v);
  const mv = clamp(s.move || 0, 0, 1);
  if (mv > 0.01) st.ph += dt * (G.f0 + (G.f1 - G.f0) * mv) * TAU;
  const on = clamp(mv * 3, 0, 1), run = clamp((mv - 0.4) / 0.4, 0, 1);
  // stance offsets fade out while moving
  for (const j of ['upL', 'knL', 'upR', 'knR']) { const c = CH[j]; out[c] *= 1 - on * 0.85; out[c + 2] *= 1 - on * 0.6; }
  out[CH.hips + 1] *= 1 - on;
  const A = G.stride * on * (0.75 + 0.5 * run);
  const sp = Math.sin(st.ph), cp = Math.cos(st.ph);
  out[CH.upL] += -A * sp; out[CH.upR] += A * sp;
  const Kn = G.knee * on * (0.55 + 0.9 * run);
  out[CH.knL] += Kn * Math.max(0, cp) + 0.15 * run * on; out[CH.knR] += Kn * Math.max(0, -cp) + 0.15 * run * on;
  out[CH.spine] += G.lean * run + 0.05 * on;
  out[CH.hips + 1] += -0.18 * A * sp; out[CH.chest + 1] += 0.3 * A * sp;
  out[CH.hips + 2] += (G.sway || 0) * on * sp;
  if (G.runArms) blendInto(out, G.runArms, run);
  const sw = G.swing * on * (1 + run);
  if (G.freeR) { out[CH.hR + 2] += sw * sp; out[CH.hR + 1] += Math.abs(sp) * sw * 0.3 * run; }
  if (G.freeL) { out[CH.hL + 2] -= sw * sp; out[CH.hL + 1] += Math.abs(sp) * sw * 0.3 * run; }
  const br = Math.sin(st.time * G.breathF * TAU);
  out[CH.chest] -= G.breath * br; out[CH.head] += G.breath * 0.6 * br;
  if (!G.freeR) out[CH.hR + 1] += G.breath * 0.4 * br * G.scale;
  out[CH.hL + 1] += G.breath * 0.4 * br * G.scale;
  if (s.air && G.air) blendInto(out, G.air, 1);
}

// Shared per-frame driver. A = { rig, st, acts, G, after }.
function drive(A, dt, s) {
  const st = A.st, rig = A.rig;
  dt = Math.max(0, Math.min(dt || 0, 0.1));
  st.time += dt;
  const name = s.action || 'idle';
  const def = A.acts[name] || A.acts.idle;
  let t = clamp(s.t ?? 0, 0, 1);
  if (def.remap) t = def.remap(t, s);
  gait(rig.base, st, s, dt, A.G);
  let frames = def.f;
  if (frames && !Array.isArray(frames)) frames = frames[A.dirOf ? A.dirOf(s) : (s.dir || 'over')] || frames.over || frames[Object.keys(frames)[0]];
  if (frames) sample(frames, t, rig.base, rig.tgt); else rig.tgt.set(rig.base);
  if (def.post) def.post(rig.tgt, s, t, st);
  if (A.postAll) A.postAll(rig.tgt, s, t, st, name);
  rig.step(dt, def.rate || 16);
  const g = def.g === undefined ? 1 : (typeof def.g === 'function' ? def.g(t) : def.g);
  rig.apply(s.air ? 0 : g, def.foot !== false, def.pin ? A.pin : null);
  if (A.after) A.after(dt, s, t, name);
  st.flash.tick(dt);
}

function flasher(mats) {
  const st = { t: 0, T: 1, c: new THREE.Color(), on: false };
  return {
    flash(color = 0xffffff, secs = 0.12) { st.c.set(color); st.T = st.t = Math.max(0.01, secs); st.on = true; },
    tick(dt) {
      if (!st.on) return;
      st.t = Math.max(0, st.t - dt);
      const k = st.t / st.T, e = k * k * 1.3;
      for (const m of mats) m.emissive.copy(st.c).multiplyScalar(e);
      if (st.t <= 0) st.on = false;
    },
  };
}

function finishActor(o) {
  const { root, mats, extra = [] } = o;
  return {
    root, height: o.height, radius: o.radius, sockets: o.sockets,
    actions: o.actions, dirs: o.dirs || {},
    pose: o.pose, flash: (c, s) => o.st.flash.flash(c, s), setAura: o.setAura || (() => {}),
    dispose() {
      root.removeFromParent();
      for (const m of mats) m.dispose();
      for (const x of extra) x.dispose && x.dispose();
    },
  };
}

// ================================================================== CHARACTERS
// (appended below)
const GEO = {};

// ------------------------------------------------------------------ PLAYER
const PC = {
  skin: 0xf2c9a8, hair: 0x121319, coat: 0x2a3042, lapel: 0x3a4258, lining: 0x5e1822, shirt: 0x3b3f4c,
  pants: 0x1d1f27, boot: 0x131318, sole: 0x07070a, belt: 0x3a2a1c, brass: 0xb08a3a, saya: 0x221014,
  scarf: 0xc8182a, glove: 0x1b1b21, wrap: 0xd8cfbf, white: 0xf4f6fb, mouth: 0x6a3434, steel: 0xc9d1dc,
  hilt: 0x131316, tsuba: 0x3a2f22,
};
const BLADE_L = 0.74, BLADE_Z0 = 0.056;
const bladeV = (u) => 0.022 * (u / BLADE_L) * (u / BLADE_L); // sori: the blade curves toward the spine (+Y)

function fistGeo(m, C) { // m=+1 right (palm faces +X), m=-1 left
  return new GB()
    .ell([0.004 * m, -0.05, 0], [0.034, 0.046, 0.043], C.glove)
    .limb([0.042 * m, -0.07, -0.034], [0.042 * m, -0.07, 0.034], 0.019, 0.019, C.skin, 0.8, 8)
    .ell([0.022 * m, -0.09, 0], [0.028, 0.018, 0.04], C.glove, [0, 0, 0], 0.8)
    .limb([0.0, -0.028, 0.036], [0.03 * m, -0.052, 0.05], 0.013, 0.011, C.skin, 0.8, 8)
    .build();
}
function katanaGeo(C) {
  const b = new GB();
  b.cyl([0, 0, -0.075], 0.0165, 0.0165, 0.24, C.hilt, [PI / 2, 0, 0], 0.8);
  for (let k = 0; k < 6; k++) b.box([0, 0, -0.18 + k * 0.036], [0.035, 0.012, 0.012], C.wrap, [0, 0, PI / 4], 0.3);
  b.cyl([0, 0, -0.2], 0.019, 0.019, 0.014, C.brass, [PI / 2, 0, 0], 0.6);
  b.cyl([0, 0, 0.05], 0.043, 0.043, 0.008, C.tsuba, [PI / 2, 0, 0], 0.6, 16);
  b.box([0, 0.002, 0.066], [0.011, 0.03, 0.02], C.brass, [0, 0, 0], 0.4);
  // blade: a 2D profile (x = along blade, y = spine/edge) extruded thin
  const sh = new THREE.Shape(), n = 14;
  sh.moveTo(0, 0.011);
  for (let i = 1; i <= n; i++) { const u = (i / n) * (BLADE_L - 0.045); sh.lineTo(u, 0.011 - 0.002 * i / n + bladeV(u)); }
  sh.lineTo(BLADE_L, 0.006 + bladeV(BLADE_L));
  sh.quadraticCurveTo(BLADE_L - 0.012, -0.012 + bladeV(BLADE_L), BLADE_L - 0.05, -0.015 + bladeV(BLADE_L - 0.05));
  for (let i = n; i >= 0; i--) { const u = (i / n) * (BLADE_L - 0.05); sh.lineTo(u, -0.018 + 0.002 * i / n + bladeV(u)); }
  const ex = new THREE.ExtrudeGeometry(sh, { depth: 0.004, bevelEnabled: true, bevelThickness: 0.0015, bevelSize: 0.001, bevelSegments: 1, curveSegments: 6 });
  const m = new THREE.Matrix4().makeRotationY(-PI / 2); m.setPosition(0.002, 0, BLADE_Z0);
  b.geo(ex, C.steel, m, 0.35);
  return b.build();
}
function katanaEdgeGeo() { // the cutting edge strip: lit cyan by setAura
  const sh = new THREE.Shape(), n = 14;
  sh.moveTo(0.02, -0.0185 + bladeV(0.02));
  for (let i = 0; i <= n; i++) { const u = 0.02 + (i / n) * (BLADE_L - 0.07); sh.lineTo(u, -0.0185 + bladeV(u)); }
  sh.quadraticCurveTo(BLADE_L - 0.012, -0.0125 + bladeV(BLADE_L), BLADE_L + 0.001, 0.006 + bladeV(BLADE_L));
  sh.lineTo(BLADE_L - 0.008, 0.002 + bladeV(BLADE_L));
  sh.quadraticCurveTo(BLADE_L - 0.025, -0.004 + bladeV(BLADE_L), BLADE_L - 0.05, -0.006 + bladeV(BLADE_L - 0.05));
  for (let i = n; i >= 0; i--) { const u = 0.02 + (i / n) * (BLADE_L - 0.07); sh.lineTo(u, -0.007 + bladeV(u) - 0.003 * (1 - i / n)); }
  const g = new THREE.ExtrudeGeometry(sh, { depth: 0.0078, bevelEnabled: false, curveSegments: 6 });
  const m = new THREE.Matrix4().makeRotationY(-PI / 2); m.setPosition(0.0039, 0, BLADE_Z0);
  g.applyMatrix4(m);
  return g;
}
function spikeInto(b, tips, base, dir, len, r, C) {
  const d = new V3(...dir).normalize();
  const p0 = base, p1 = [base[0] + d.x * len * 0.68, base[1] + d.y * len * 0.68, base[2] + d.z * len * 0.68];
  const p2 = [base[0] + d.x * len, base[1] + d.y * len, base[2] + d.z * len];
  b.frustum(p0, p1, r, r * 0.34, C.hair, 1, 7);
  tips.cone(p1, p2, r * 0.34, C.hair, 0.8, 7);
}
function playerGeo() {
  if (GEO.player) return GEO.player;
  const C = PC, g = {};
  g.pelvis = new GB().ell([0, -0.01, 0], [0.145, 0.1, 0.106], C.pants)
    .cyl([0, 0.05, 0], 0.153, 0.153, 0.046, C.belt).box([0, 0.05, 0.152], [0.045, 0.035, 0.012], C.brass, [0, 0, 0], 0.6)
    .limb([0.155, 0.03, 0.07], [0.21, -0.3, -0.47], 0.022, 0.02, C.saya).build();
  g.thigh = new GB().limb([0, 0.02, 0], [0, -0.44, 0], 0.072, 0.056, C.pants).build();
  g.shin = new GB().limb([0, 0, 0], [0, -0.42, 0], 0.054, 0.043, C.pants).limb([0, -0.19, 0], [0, -0.41, 0], 0.058, 0.05, C.boot).build();
  g.foot = new GB().ell([0, -0.028, 0.045], [0.052, 0.045, 0.118], C.boot).box([0, -0.063, 0.045], [0.1, 0.014, 0.232], C.sole, [0, 0, 0], 0.7).build();
  g.spine = new GB().ell([0, 0.11, 0], [0.15, 0.14, 0.106], C.coat).build();
  g.chest = new GB().ell([0, 0.075, -0.005], [0.178, 0.17, 0.118], C.coat)
    .ell([0, 0.185, -0.01], [0.15, 0.05, 0.085], C.coat)
    .ell([0, 0.1, 0.083], [0.058, 0.12, 0.04], C.shirt, [0, 0, 0], 0.5)
    .box([0.062, 0.1, 0.1], [0.045, 0.2, 0.02], C.lapel, [-0.12, 0, -0.32], 0.6)
    .box([-0.062, 0.1, 0.1], [0.045, 0.2, 0.02], C.lapel, [-0.12, 0, 0.32], 0.6)
    .cyl([0, 0.2, -0.01], 0.082, 0.1, 0.07, C.coat)
    .torus([0, 0.217, 0.004], 0.068, 0.034, C.scarf, [PI / 2, 0, 0])
    .ell([-0.045, 0.19, 0.082], [0.04, 0.036, 0.032], C.scarf).build();
  // head (neck included, so the neck joint needs no mesh)
  const h = new GB().limb([0, -0.075, -0.005], [0, 0.03, 0], 0.043, 0.043, C.skin)
    .ell([0, 0.1, 0], [0.1, 0.115, 0.108], C.skin)
    .ell([0, 0.042, 0.045], [0.06, 0.05, 0.055], C.skin)
    .ell([0.1, 0.09, -0.005], [0.018, 0.03, 0.022], C.skin).ell([-0.1, 0.09, -0.005], [0.018, 0.03, 0.022], C.skin)
    .cone([0, 0.086, 0.102], [0, 0.07, 0.118], 0.011, C.skin, 0.5)
    .box([0, 0.043, 0.093], [0.026, 0.004, 0.006], C.mouth, [0, 0, 0], 0)
    .box([0.043, 0.128, 0.099], [0.042, 0.009, 0.01], C.hair, [0, 0.25, 0.2], 0.4)
    .box([-0.043, 0.128, 0.099], [0.042, 0.009, 0.01], C.hair, [0, -0.25, -0.2], 0.4)
    .ell([0, 0.145, -0.02], [0.114, 0.1, 0.116], C.hair)
    .ell([0.088, 0.1, -0.03], [0.032, 0.07, 0.08], C.hair).ell([-0.088, 0.1, -0.03], [0.032, 0.07, 0.08], C.hair);
  const tips = new GB();
  const hc = [0, 0.14, -0.02];
  const spikes = [
    [[0, 1, -0.3], 0.14], [[0.5, 0.8, -0.4], 0.13], [[-0.5, 0.8, -0.4], 0.13], [[0.85, 0.4, -0.5], 0.12], [[-0.85, 0.4, -0.5], 0.12],
    [[0.3, 0.5, -0.9], 0.14], [[-0.3, 0.5, -0.9], 0.14], [[0, 0.15, -1], 0.13], [[0.6, -0.05, -0.8], 0.11], [[-0.6, -0.05, -0.8], 0.11],
    [[0.95, 0.15, -0.1], 0.1], [[-0.95, 0.15, -0.1], 0.1], [[0.3, 0.95, 0.25], 0.11], [[-0.35, 0.95, 0.18], 0.12], [[0.15, -0.35, -1], 0.1],
  ];
  for (const [d, len] of spikes) {
    const n = new V3(...d).normalize();
    const base = [hc[0] + n.x * 0.085, hc[1] + n.y * 0.075, hc[2] + n.z * 0.085];
    spikeInto(h, tips, base, [n.x, n.y + 0.3, n.z - 0.25], len, 0.042, C);
  }
  for (let i = 0; i < 5; i++) { // bangs, falling over the forehead but not the eyes
    const x = (i - 2) * 0.032;
    spikeInto(h, tips, [x * 0.8, 0.2, 0.055], [x * 1.4, -0.75, 0.55], 0.1, 0.03, C);
  }
  g.head = h.build(); g.hairTips = tips.build();
  g.sclera = new GB().ell([0.041, 0.1, 0.093], [0.024, 0.014, 0.01], C.white, [0, 0.3, 0.1], 0.35)
    .ell([-0.041, 0.1, 0.093], [0.024, 0.014, 0.01], C.white, [0, -0.3, -0.1], 0.35).build();
  g.iris = new THREE.SphereGeometry(1, 12, 8);
  g.iris.applyMatrix4(mat([0.039, 0.099, 0.1], [0, 0.3, 0], [0.012, 0.0135, 0.006]));
  const ir2 = new THREE.SphereGeometry(1, 12, 8); ir2.applyMatrix4(mat([-0.039, 0.099, 0.1], [0, -0.3, 0], [0.012, 0.0135, 0.006]));
  g.iris = mergeUV([g.iris, ir2]);
  g.upperArm = new GB().ell([0, -0.01, 0], [0.07, 0.075, 0.07], C.coat).limb([0, 0, 0], [0, -0.28, 0], 0.058, 0.05, C.coat).build();
  g.forearm = new GB().limb([0, 0.015, 0], [0, -0.065, 0], 0.058, 0.055, C.lining)
    .limb([0, 0, 0], [0, -0.26, 0], 0.042, 0.033, C.skin)
    .cyl([0, -0.228, 0], 0.036, 0.037, 0.048, C.wrap, [0, 0, 0], 0.6).build();
  g.fistR = fistGeo(1, C); g.fistL = fistGeo(-1, C);
  g.katana = katanaGeo(C); g.edge = katanaEdgeGeo();
  g.veinR = veinGeo(0.26, 0.042, 0.033, 7); g.veinL = veinGeo(0.26, 0.042, 0.033, 13);
  // long coat skirt: four lathe sectors (points listed bottom->top so normals face out)
  const prof = (k) => [[0.265, -0.7], [0.24, -0.52], [0.215, -0.3], [0.19, -0.08], [0.176, 0]].map(([r, y]) => new THREE.Vector2(r * k, y));
  const sect = (a0, a1, k, color, ow) => new GB().geo(new THREE.LatheGeometry(prof(k), 6, a0, a1 - a0), color, new THREE.Matrix4(), ow).build();
  const S = { FL: [0.24, PI / 2], BL: [PI / 2, PI], BR: [PI, 1.5 * PI], FR: [1.5 * PI, TAU - 0.24] };
  g.coat = {}; g.lining = {};
  for (const k in S) { g.coat[k] = sect(S[k][0], S[k][1], 1, C.coat, 1); g.lining[k] = sect(S[k][0], S[k][1], 0.965, C.lining, 0); }
  g.scarfSeg = new GB().box([0, -0.075, 0], [0.07, 0.16, 0.012], C.scarf, [0, 0, 0], 0.7).build();
  g.scarfEnd = new GB().box([0, -0.05, 0], [0.07, 0.1, 0.012], C.scarf, [0, 0, 0], 0.7)
    .cone([-0.02, -0.09, 0], [-0.022, -0.15, 0], 0.017, C.scarf, 0.7, 5).cone([0.02, -0.09, 0], [0.024, -0.135, 0], 0.017, C.scarf, 0.7, 5).build();
  return (GEO.player = g);
}

// The katana-hand arm: shared by the player and the first-person viewmodel.
function buildSwordArms(parent, J, G, mats, out, veins, sR, sL) {
  const arms = {};
  for (const side of ['R', 'L']) {
    const pos = side === 'R' ? sR : sL;
    const sh = jg(parent, 'sh' + side, pos[0], pos[1], pos[2]);
    const el = jg(sh, 'el' + side, 0, -0.28, 0);
    const wr = jg(el, 'wr' + side, 0, -0.26, 0);
    mk(sh, G.upperArm, mats.body, out);
    mk(el, G.forearm, mats.body, out);
    const vg = side === 'R' ? G.veinR : G.veinL;
    const vc = new THREE.Mesh(vg.core, veins.core), vs = new THREE.Mesh(vg.shell, veins.shell);
    vc.renderOrder = 2; vs.renderOrder = 3; vc.visible = vs.visible = false; vc.raycast = vs.raycast = noRay;
    el.add(vc, vs); veins.meshes.push(vc, vs);
    mk(wr, side === 'R' ? G.fistR : G.fistL, mats.body, out);
    arms[side] = { sh, el, wr, a: 0.28, b: 0.26 };
  }
  const kat = jg(arms.R.wr, 'katana', 0.018, -0.068, 0);
  mk(kat, G.katana, mats.body, out);
  const edge = new THREE.Mesh(G.edge, mats.edge); edge.raycast = noRay; kat.add(edge);
  const tip = sock(kat, 'swordTip', 0, 0.006 + bladeV(BLADE_L), BLADE_Z0 + BLADE_L);
  return { arms, kat, tip };
}
function swordAura(level, M, veins) {
  const L = clamp(level, 0, 1);
  if (M.iris) M.iris.color.setRGB(0.02, 0.022, 0.035).lerp(_c1.copy(CYAN_HOT).lerp(WHITE_HOT, 0.3), L);
  if (M.tips) M.tips.emissive.copy(CYAN_LIN).multiplyScalar(2.2 * L);
  M.edge.color.setRGB(0.8, 0.86, 0.93).lerp(_c1.copy(CYAN_LIN).multiplyScalar(1.8).lerp(WHITE_HOT, 0.06), L);
  veins.u.uLevel.value = L;
  for (const m of veins.meshes) m.visible = L > 0.002;
}
const _c1 = new THREE.Color();

let PLAYER_ACTS = null;
function playerActs() {
  if (PLAYER_ACTS) return PLAYER_ACTS;
  const lyingBack = { hips: [-1.47, 0.1, 0], hp: [0, -0.79, -0.05], spine: [-0.05, 0, 0], chest: [0, 0, 0], neck: [0.15, 0, 0], head: [0.2, 0.45, 0],
    upL: [-0.12, 0, 0.14], knL: [0.35, 0, 0], upR: [0.02, 0, -0.1], knR: [0.12, 0, 0],
    hR: [-0.55, 0.1, -0.55], bR: [-0.45, -0.06, 0.85], pR: [0, 1, 0], hL: [0.5, 0.09, -0.62], bL: [0, 1, 0], pL: [0, 1, 0] };
  const kneel = { hips: [0.05, 0, 0], hp: [0, -0.42, 0], spine: [0.35, 0, 0], chest: [0.1, 0, 0], head: [0.3, 0, 0],
    upR: [-1.45, 0, -0.08], knR: [1.5, 0, 0], upL: [0.08, 0, 0.06], knL: [1.55, 0, 0],
    hR: [-0.2, 0.72, 0.38], bR: [0, -1, 0.12], pR: [-1, 0, -0.3], hL: [0.04, 0.56, 0.36], bL: [0, 0, 1], pL: [1, 0, -0.5] };
  const atk = {
    R: [K(0, {}),
      K(0.26, { hips: [0, -0.3, 0], spine: [0.02, -0.5, 0], chest: [-0.05, -0.3, 0], head: [0, 0.5, 0],
        hR: [-0.42, 1.32, -0.12], bR: [-0.35, 0.55, -0.75], pR: [-0.3, -1, -0.2], hL: [0.25, 1.2, 0.3], upL: [0.2, 0, 0.08], upR: [-0.3, 0, -0.1], knR: [0.35, 0, 0] }),
      K(0.34, { hips: [0, -0.05, 0], spine: [0.12, 0.05, 0], chest: [0.05, 0, 0], head: [0, 0.1, 0],
        hR: [-0.1, 1.22, 0.5], bR: [-0.05, 0.1, 1], pR: [-0.6, -0.8, 0], hL: [0.3, 1.05, 0.1] }, 'snap'),
      K(0.42, { hips: [0, 0.25, 0], spine: [0.18, 0.55, 0], chest: [0.08, 0.3, 0], head: [0, -0.35, 0],
        hR: [0.33, 1.02, 0.3], bR: [0.85, -0.3, -0.25], pR: [0, -1, 0.3], hL: [0.35, 1.0, -0.25],
        upR: [-0.55, 0, -0.1], knR: [0.55, 0, 0], upL: [0.35, 0, 0.08], knL: [0.3, 0, 0] }, 'out'),
      K(0.8, { hips: [0, 0.22, 0], spine: [0.16, 0.5, 0], chest: [0.08, 0.28, 0], head: [0, -0.3, 0],
        hR: [0.3, 0.98, 0.3], bR: [0.8, -0.35, -0.2], pR: [0, -1, 0.3], hL: [0.35, 1.0, -0.25],
        upR: [-0.5, 0, -0.1], knR: [0.5, 0, 0], upL: [0.33, 0, 0.08], knL: [0.3, 0, 0] }),
      K(1, {})],
    L: [K(0, {}),
      K(0.26, { hips: [0, 0.2, 0], spine: [0.02, 0.55, 0], chest: [-0.02, 0.35, 0], head: [0, -0.5, 0],
        hR: [0.28, 1.3, 0.05], bR: [0.75, 0.45, -0.45], pR: [-0.8, -0.3, 0.2], hL: [0.3, 1.0, -0.15] }),
      K(0.34, { hips: [0, 0, 0], spine: [0.12, 0, 0], hR: [-0.02, 1.2, 0.52], bR: [0.05, 0.1, 1], pR: [-0.8, -0.6, 0] }, 'snap'),
      K(0.42, { hips: [0, -0.3, 0], spine: [0.18, -0.55, 0], chest: [0.06, -0.3, 0], head: [0, 0.35, 0],
        hR: [-0.5, 1.05, 0.25], bR: [-0.9, -0.25, -0.2], pR: [-0.5, -1, 0.2], hL: [0.3, 1.1, 0.25], upR: [-0.45, 0, -0.12], knR: [0.5, 0, 0] }, 'out'),
      K(0.8, { hips: [0, -0.28, 0], spine: [0.16, -0.5, 0], chest: [0.06, -0.28, 0], head: [0, 0.3, 0],
        hR: [-0.48, 1.0, 0.25], bR: [-0.85, -0.3, -0.2], pR: [-0.5, -1, 0.2], hL: [0.3, 1.1, 0.25], upR: [-0.42, 0, -0.12], knR: [0.48, 0, 0] }),
      K(1, {})],
    over: [K(0, {}),
      K(0.26, { spine: [-0.18, 0, 0], chest: [-0.12, 0, 0], head: [-0.15, 0, 0], hR: [-0.1, 1.78, -0.02], bR: [0, 0.35, -1], pR: [-0.7, 0.2, 0], hL: [0.14, 1.66, 0.06] }),
      K(0.34, { spine: [0.15, 0, 0], hR: [-0.08, 1.5, 0.5], bR: [0, 0.75, 0.65], pR: [-0.8, -0.5, 0], hL: [0.14, 1.38, 0.4] }, 'snap'),
      K(0.42, { spine: [0.45, 0, 0], chest: [0.15, 0, 0], head: [0.1, 0, 0], hR: [-0.06, 0.95, 0.52], bR: [0, -0.75, 0.65], pR: [-0.8, -0.5, 0],
        hL: [0.2, 0.9, 0.35], upR: [-0.6, 0, -0.08], knR: [0.6, 0, 0], upL: [0.35, 0, 0.08], knL: [0.35, 0, 0] }, 'out'),
      K(0.8, { spine: [0.42, 0, 0], chest: [0.14, 0, 0], head: [0.08, 0, 0], hR: [-0.06, 0.93, 0.5], bR: [0, -0.72, 0.68], pR: [-0.8, -0.5, 0],
        hL: [0.2, 0.9, 0.35], upR: [-0.56, 0, -0.08], knR: [0.56, 0, 0], upL: [0.33, 0, 0.08], knL: [0.33, 0, 0] }),
      K(1, {})],
  };
  const parry = {
    over: { spine: [-0.05, 0, 0], head: [-0.2, 0, 0], hR: [-0.32, 1.72, 0.3], bR: [1, 0.05, 0.1], pR: [-1, 0, -0.2], hL: [0.2, 1.7, 0.34], bL: [0, 0, 1], pL: [1, 0, -0.2],
      upL: [0, 0, 0.2], upR: [0, 0, -0.2], knL: [0.4, 0, 0], knR: [0.4, 0, 0] },
    R: { spine: [0.05, -0.35, 0], hR: [-0.3, 1.05, 0.32], bR: [-0.05, 1, 0.1], pR: [-1, -0.5, -0.3], hL: [-0.24, 1.35, 0.4], bL: [0, 1, 0], pL: [1, -0.3, -0.3], knL: [0.35, 0, 0], knR: [0.3, 0, 0] },
    L: { spine: [0.05, 0.45, 0], hR: [0.12, 1.05, 0.34], bR: [0.05, 1, 0.1], pR: [-1, -0.8, 0], hL: [0.22, 1.35, 0.42], bL: [0, 1, 0], pL: [1, -0.3, -0.3], knL: [0.3, 0, 0], knR: [0.35, 0, 0] },
  };
  const pf = {}; for (const d in parry) pf[d] = [K(0, {}), K(0.1, parry[d], 'snap'), K(0.78, parry[d]), K(1, {})];
  const dash = { hips: [0.1, 0, 0], spine: [0.45, 0, 0], chest: [0.15, 0, 0], head: [-0.45, 0, 0],
    upL: [-0.9, 0, 0.05], knL: [1.0, 0, 0], upR: [0.65, 0, -0.05], knR: [0.45, 0, 0],
    hR: [-0.4, 0.95, -0.35], bR: [-0.2, -0.15, -1], pR: [-0.5, 0.5, -1], hL: [0.35, 1.0, -0.3], pL: [0.5, 0.5, -1] };
  PLAYER_ACTS = {
    idle: {},
    attack: { f: atk, rate: 42, remap: (t, s) => { const h = clamp(s.hit ?? 0.36, 0.05, 0.95); return t < h ? t / h * 0.36 : 0.36 + (t - h) / (1 - h) * 0.64; } },
    parry: { f: pf, rate: 45 },
    windup: { f: { R: [K(0, {}), atk.R[1]], L: [K(0, {}), atk.L[1]], over: [K(0, {}), atk.over[1]] }, rate: 30 },
    dash: { f: [K(0, {}), K(0.1, dash, 'snap'), K(0.85, dash), K(1, {})], rate: 40 },
    hurt: { f: [K(0, {}), K(0.12, { spine: [-0.3, 0.2, 0], chest: [-0.15, 0, 0], head: [-0.35, 0.2, 0], hR: [-0.38, 1.05, 0.05], bR: [-0.3, 0.6, 0.7], hL: [0.38, 1.12, -0.1] }, 'snap'),
      K(0.45, { spine: [-0.15, 0.1, 0], head: [-0.15, 0.1, 0], hR: [-0.3, 1.0, 0.15], hL: [0.33, 1.0, -0.02] }), K(1, {})], rate: 36 },
    down: { f: [K(0, {}), K(0.1, { hips: [-0.45, 0, 0], hp: [0, -0.3, -0.12], spine: [-0.3, 0, 0], head: [-0.4, 0, 0], hR: [-0.45, 1.0, -0.1], hL: [0.45, 1.05, -0.1] }, 'in'),
      K(0.22, lyingBack, 'in'), K(0.26, { ...lyingBack, hp: [0, -0.75, -0.05] }), K(0.3, lyingBack), K(1, lyingBack)],
      g: (t) => 1 - sstep(0, 0.1, t), rate: 24 },
    getup: { f: [K(0, lyingBack),
      K(0.35, { hips: [-0.9, 0, 0], hp: [0, -0.5, -0.05], spine: [0.5, 0, 0], chest: [0.2, 0, 0], head: [0.2, 0, 0],
        upL: [-0.85, 0, 0.2], knL: [1.6, 0, 0], upR: [-0.3, 0, -0.1], knR: [0.6, 0, 0],
        hL: [0.3, 0.1, -0.3], bL: [0, 0, 1], pL: [0.3, 1, -0.5], hR: [-0.45, 0.35, 0.1], bR: [-0.3, -0.2, 0.9], pR: [-1, 0, -0.3] }),
      K(0.65, kneel),
      K(0.85, { hips: [0.08, 0, 0], hp: [0, -0.14, 0], spine: [0.25, 0, 0], upR: [-0.6, 0, -0.06], knR: [0.8, 0, 0], upL: [0.2, 0, 0.06], knL: [0.5, 0, 0], hR: [-0.25, 0.9, 0.35], bR: [0, -0.2, 1] }),
      K(1, {})], g: (t) => sstep(0.85, 1, t), rate: 14 },
    dead: { f: [K(0, {}), K(0.25, { hp: [0, -0.4, 0], upL: [-0.9, 0, 0.05], knL: [1.6, 0, 0], upR: [-0.8, 0, -0.05], knR: [1.5, 0, 0], spine: [0.4, 0, 0], head: [0.4, 0, 0],
      hR: [-0.3, 0.55, 0.3], hL: [0.3, 0.55, 0.3] }, 'in'),
      K(0.6, { hips: [1.48, 0.15, 0.05], hp: [0, -0.76, 0.12], spine: [0.05, 0, 0], neck: [0, 0, 0], head: [-0.3, 0.9, 0], upL: [-0.05, 0, 0.12], knL: [0.25, 0, 0], upR: [0.1, 0, -0.1], knR: [0.4, 0, 0],
        hR: [-0.55, 0.1, 0.6], bR: [-0.6, 0.05, 0.8], pR: [0, 1, 0], hL: [0.5, 0.1, 0.55], pL: [0, 1, 0] }, 'in'), K(1, { hips: [1.48, 0.15, 0.05], hp: [0, -0.76, 0.12], head: [-0.3, 0.9, 0], hR: [-0.55, 0.1, 0.6], bR: [-0.6, 0.05, 0.8], pR: [0, 1, 0], hL: [0.5, 0.1, 0.55], pL: [0, 1, 0] })],
    g: (t) => 1 - sstep(0.15, 0.3, t), rate: 18 },
    hug: { f: [K(0, {}), K(0.5, { ...kneel, spine: [0.12, 0, 0], chest: [0.05, 0, 0], head: [0.1, 0.4, 0.1],
      hR: [-0.18, 0.95, 0.36], bR: [0, -1, -0.2], pR: [-1, 0, -0.2], hL: [0.18, 0.98, 0.38], bL: [-1, 0, 0], pL: [1, 0, -0.2] }), K(1, { ...kneel, spine: [0.12, 0, 0], chest: [0.05, 0, 0], head: [0.1, 0.4, 0.1],
      hR: [-0.18, 0.95, 0.36], bR: [0, -1, -0.2], pR: [-1, 0, -0.2], hL: [0.18, 0.98, 0.38], bL: [-1, 0, 0], pL: [1, 0, -0.2] })], g: (t) => 1 - sstep(0, 0.4, t), rate: 10 },
  };
  return PLAYER_ACTS;
}
const PLAYER_GAIT = {
  idle: P({ hips: [0, -0.15, 0], spine: [0.06, 0.12, 0], chest: [0.03, 0.05, 0], head: [-0.03, -0.05, 0],
    upL: [0.14, 0, 0.07], knL: [0.22, 0, 0], upR: [-0.2, 0, -0.05], knR: [0.26, 0, 0],
    hR: [-0.2, 1.08, 0.33], bR: [0.12, 0.45, 1], pR: [-0.6, -0.6, -0.4],
    hL: [0.25, 0.9, 0.06], bL: [0, 0, 1], pL: [0.6, -0.3, -0.6] }),
  runArms: P({ hR: [-0.32, 0.95, -0.22], bR: [-0.2, -0.25, -1], pR: [-0.3, -0.4, -1], hL: [0.26, 1.12, 0.22] }),
  air: P({ upL: [-1.0, 0, 0.1], knL: [1.6, 0, 0], upR: [-0.3, 0, -0.1], knR: [0.9, 0, 0], spine: [0.15, 0, 0], hL: [0.42, 1.3, 0.1] }),
  f0: 1.0, f1: 2.2, stride: 0.55, knee: 0.9, lean: 0.35, swing: 0.2, freeL: true, freeR: false,
  breath: 0.02, breathF: 0.25, sway: 0.03, scale: 1,
};
// dir for the PLAYER is his own: 'left' = sweeps from his left.  Default by combo.
const playerDir = (s) => (s.dir === 'left' ? 'L' : s.dir === 'right' ? 'R' : s.dir === 'over' ? 'over' : ['R', 'L', 'over'][(s.combo | 0) % 3]);

export function makePlayer() {
  const G = playerGeo();
  const mats = { body: toonMat(), cloth: toonMat(THREE.DoubleSide) };
  const M = {
    iris: new THREE.MeshBasicMaterial({ color: 0x05060a, fog: false }),
    tips: new THREE.MeshToonMaterial({ color: PC.hair, gradientMap: GRAD }),
    edge: new THREE.MeshBasicMaterial({ color: 0xdfe6ee, fog: false }),
  };
  const out = outlineMat(0.004, 0.02);
  const veins = veinMats(); veins.meshes = [];
  const root = new THREE.Group(); root.name = 'player';
  const J = {};
  J.hips = jg(root, 'hips', 0, 0.96, 0);
  J.spine = jg(J.hips, 'spine', 0, 0.06, 0);
  J.chest = jg(J.spine, 'chest', 0, 0.22, 0);
  J.neck = jg(J.chest, 'neck', 0, 0.235, 0);
  J.head = jg(J.neck, 'head', 0, 0.07, 0.01);
  for (const [sd, x] of [['L', 0.1], ['R', -0.1]]) {
    J['up' + sd] = jg(J.hips, 'up' + sd, x, -0.04, 0);
    J['kn' + sd] = jg(J['up' + sd], 'kn' + sd, 0, -0.44, 0);
    J['an' + sd] = jg(J['kn' + sd], 'an' + sd, 0, -0.42, 0);
    mk(J['up' + sd], G.thigh, mats.body, out); mk(J['kn' + sd], G.shin, mats.body, out); mk(J['an' + sd], G.foot, mats.body, out);
  }
  mk(J.hips, G.pelvis, mats.body, out); mk(J.spine, G.spine, mats.body, out); mk(J.chest, G.chest, mats.body, out);
  mk(J.head, G.head, mats.body, out);
  mk(J.head, G.hairTips, M.tips, out);
  mk(J.head, G.sclera, SHARED.unlit, out, { shadow: false });
  const iris = new THREE.Mesh(G.iris, M.iris); iris.raycast = noRay; J.head.add(iris);
  const coat = {};
  for (const k of ['FL', 'FR', 'BL', 'BR']) {
    coat[k] = jg(J.hips, 'coat' + k, 0, 0.07, 0);
    mk(coat[k], G.coat[k], mats.cloth, out); mk(coat[k], G.lining[k], mats.cloth, null);
  }
  const scarf = jg(J.chest, 'scarf', 0, 0.2, -0.078);
  const tails = [];
  for (const [x, n] of [[-0.022, 3], [0.03, 2]]) {
    const segs = []; let p = jg(scarf, 'tail', x, 0, 0);
    for (let i = 0; i < n; i++) { if (i) p = jg(p, 'tail', 0, -0.15, 0); segs.push(p); mk(p, i === n - 1 ? G.scarfEnd : G.scarfSeg, mats.cloth, out); }
    tails.push(segs);
  }
  const { arms, tip } = buildSwordArms(J.chest, J, G, { body: mats.body, edge: M.edge }, out, veins, [-0.185, 0.19, -0.01], [0.185, 0.19, -0.01]);
  const sockets = {
    head: sock(J.head, 'head', 0, 0.1, 0), eyes: sock(J.head, 'eyes', 0, 0.1, 0.112),
    swordTip: tip, hand: sock(arms.R.wr, 'hand', 0.018, -0.068, 0),
    forearmR: sock(arms.R.el, 'forearmR', 0, -0.13, 0), chest: sock(J.chest, 'chest', 0, 0.08, 0.12),
  };
  const rig = new Rig(root, J, arms);
  const st = { time: 0, ph: 0, flash: flasher([mats.body, mats.cloth]), aura: 0 };
  const A = { rig, st, acts: playerActs(), G: PLAYER_GAIT, dirOf: playerDir };
  A.after = (dt, s, t, name) => {
    const c = rig.cur, mv = clamp(s.move || 0, 0, 1), tm = st.time;
    const blow = mv * 0.6 + (name === 'dash' ? 1.0 : 0) + (s.air ? 0.35 : 0) + st.aura * 0.25;
    const fl = Math.sin(tm * (7 + 7 * mv)) * (0.03 + 0.08 * blow);
    coat.FL.rotation.set(Math.min(0, c[CH.upL]) * 0.9 - 0.02, 0, 0.03 + Math.max(0, c[CH.upL + 2]) * 0.8);
    coat.FR.rotation.set(Math.min(0, c[CH.upR]) * 0.9 - 0.02, 0, -0.03 + Math.min(0, c[CH.upR + 2]) * 0.8);
    coat.BL.rotation.set(Math.max(0, c[CH.upL]) * 0.7 + 0.04 + blow * 0.7 + fl, 0, 0.04);
    coat.BR.rotation.set(Math.max(0, c[CH.upR]) * 0.7 + 0.04 + blow * 0.7 - fl, 0, -0.04);
    const base = 0.2 + blow * 1.1;
    for (let k = 0; k < tails.length; k++) {
      const segs = tails[k];
      for (let i = 0; i < segs.length; i++) {
        const w = Math.sin(tm * (5 + 7 * mv) - i * 1.1 + k * 0.7) * (0.06 + 0.25 * Math.min(1, blow));
        segs[i].rotation.set((i === 0 ? base : base * 0.18) + w, 0, Math.sin(tm * 3.1 + i + k) * 0.08);
      }
    }
    veins.u.uTime.value = tm;
  };
  const pose = (dt, s = {}) => drive(A, dt, s);
  pose(0, {});
  return finishActor({
    root, mats: [mats.body, mats.cloth, M.iris, M.tips, M.edge, veins.core, veins.shell], st, height: 1.8, radius: 0.35, sockets, pose,
    setAura: (L) => { st.aura = clamp(L, 0, 1); swordAura(L, M, veins); },
    actions: ['idle', 'attack', 'parry', 'dash', 'hurt', 'down', 'getup', 'dead', 'hug', 'windup'],
    dirs: { attack: ['left', 'right', 'over'], parry: ['left', 'right', 'over'], windup: ['left', 'right', 'over'] },
  });
}

// ------------------------------------------------------------------ SISTER
const SC = { skin: 0xf6d3bb, dress: 0xf3f2ee, trim: 0xd7dbe6, hair: 0x1f1519, ribbon: 0xd0202c, shoe: 0x3a1f1a, sock: 0xffffff,
  iris: 0x3a2238, white: 0xffffff, mouth: 0xb0505a, blush: 0xf2a7a0 };
function sisterGeo() {
  if (GEO.sister) return GEO.sister;
  const C = SC, g = {};
  const skirt = [[0.2, -0.3], [0.17, -0.2], [0.125, -0.06], [0.1, 0.04], [0.088, 0.09]].map(([r, y]) => new THREE.Vector2(r, y));
  g.hips = new GB().geo(new THREE.LatheGeometry(skirt, 16), C.dress)
    .torus([0, -0.29, 0], 0.198, 0.008, C.trim, [PI / 2, 0, 0], [1, 1, 1], 0.4)
    .torus([0, 0.075, 0], 0.092, 0.012, C.ribbon, [PI / 2, 0, 0], [1, 1, 1], 0.6)
    .ell([0, -0.02, 0], [0.085, 0.07, 0.075], C.white).build();
  g.thigh = new GB().limb([0, 0.02, 0], [0, -0.25, 0], 0.042, 0.035, C.skin).build();
  g.shin = new GB().limb([0, 0, 0], [0, -0.23, 0], 0.034, 0.027, C.skin).limb([0, -0.1, 0], [0, -0.225, 0], 0.031, 0.028, C.sock, 0.6).build();
  g.foot = new GB().ell([0, -0.022, 0.03], [0.034, 0.03, 0.07], C.shoe).build();
  g.spine = new GB().ell([0, 0.07, 0], [0.083, 0.085, 0.068], C.dress).build();
  g.chest = new GB().ell([0, 0.06, 0], [0.093, 0.1, 0.073], C.dress)
    .ell([0.1, 0.12, 0], [0.045, 0.045, 0.045], C.dress).ell([-0.1, 0.12, 0], [0.045, 0.045, 0.045], C.dress)
    .torus([0, 0.145, 0.0], 0.05, 0.012, C.trim, [PI / 2, 0, 0], [1, 1, 0.8], 0.5)
    .ell([0.018, 0.13, 0.06], [0.02, 0.013, 0.01], C.ribbon, [0, 0, -0.4], 0.5).ell([-0.018, 0.13, 0.06], [0.02, 0.013, 0.01], C.ribbon, [0, 0, 0.4], 0.5)
    .build();
  const h = new GB().limb([0, -0.07, 0], [0, 0.03, 0], 0.028, 0.028, C.skin)
    .ell([0, 0.1, 0.004], [0.1, 0.1, 0.098], C.skin)
    .ell([0, 0.05, 0.035], [0.07, 0.05, 0.065], C.skin)
    .ell([0.052, 0.058, 0.082], [0.018, 0.009, 0.006], C.blush, [0, 0.5, 0], 0).ell([-0.052, 0.058, 0.082], [0.018, 0.009, 0.006], C.blush, [0, -0.5, 0], 0)
    .ell([0, 0.04, 0.09], [0.011, 0.005, 0.005], C.mouth, [0, 0, 0], 0)
    // hair: cap, bangs, side locks, long back hair
    .ell([0, 0.125, -0.014], [0.112, 0.106, 0.112], C.hair)
    .ell([0, 0.17, 0.05], [0.1, 0.055, 0.07], C.hair)
    .chain([[0.09, 0.15, 0.03], [0.1, 0.06, 0.04], [0.095, -0.03, 0.03]], [0.03, 0.024, 0.012], C.hair)
    .chain([[-0.09, 0.15, 0.03], [-0.1, 0.06, 0.04], [-0.095, -0.03, 0.03]], [0.03, 0.024, 0.012], C.hair)
    .chain([[0, 0.14, -0.06], [0, 0.0, -0.1], [0, -0.2, -0.09], [0, -0.34, -0.08]], [0.09, 0.08, 0.07, 0.035], C.hair)
    .chain([[0.06, 0.12, -0.06], [0.07, -0.02, -0.08], [0.06, -0.22, -0.07], [0.05, -0.3, -0.06]], [0.05, 0.05, 0.04, 0.02], C.hair)
    .chain([[-0.06, 0.12, -0.06], [-0.07, -0.02, -0.08], [-0.06, -0.22, -0.07], [-0.05, -0.3, -0.06]], [0.05, 0.05, 0.04, 0.02], C.hair)
    // ribbon bow on the left side of her head
    .ell([0.105, 0.185, -0.02], [0.018, 0.018, 0.018], C.ribbon)
    .ell([0.115, 0.22, 0.0], [0.016, 0.035, 0.022], C.ribbon, [0.5, 0, -0.4], 0.7)
    .ell([0.12, 0.2, -0.05], [0.016, 0.035, 0.022], C.ribbon, [-0.9, 0, -0.6], 0.7)
    .limb([0.11, 0.18, -0.03], [0.13, 0.08, -0.05], 0.009, 0.006, C.ribbon, 0.6, 6)
    .limb([0.108, 0.18, -0.02], [0.125, 0.1, 0.0], 0.009, 0.006, C.ribbon, 0.6, 6);
  for (let i = 0; i < 5; i++) { const x = (i - 2) * 0.034; h.cone([x * 0.9, 0.2, 0.07], [x * 1.15, 0.13, 0.1], 0.024, C.hair, 1, 6); }
  g.head = h.build();
  // big anime eyes: unlit
  const e = new GB();
  for (const m of [1, -1]) {
    e.ell([0.038 * m, 0.088, 0.089], [0.026, 0.032, 0.011], C.white, [0, 0.35 * m, 0], 0.5)
      .ell([0.037 * m, 0.085, 0.096], [0.02, 0.027, 0.007], C.iris, [0, 0.35 * m, 0], 0)
      .ell([0.037 * m, 0.08, 0.1], [0.011, 0.015, 0.004], 0x120810, [0, 0.35 * m, 0], 0)
      .ell([0.031 * m, 0.097, 0.104], [0.006, 0.007, 0.003], C.white, [0, 0, 0], 0)
      .box([0.04 * m, 0.126, 0.094], [0.036, 0.006, 0.008], C.hair, [0, 0.35 * m, -0.12 * m], 0);
  }
  g.eyes = e.build();
  g.upperArm = new GB().ell([0, -0.01, 0], [0.042, 0.045, 0.042], C.dress).limb([0, 0, 0], [0, -0.16, 0], 0.025, 0.022, C.skin).build();
  g.forearm = new GB().limb([0, 0, 0], [0, -0.14, 0], 0.021, 0.018, C.skin).build();
  g.hand = new GB().ell([0, -0.03, 0], [0.02, 0.03, 0.026], C.skin).build();
  return (GEO.sister = g);
}
let SISTER_ACTS = null;
function sisterActs() {
  if (SISTER_ACTS) return SISTER_ACTS;
  const held = { spine: [-0.06, 0, 0], chest: [-0.04, 0, 0], neck: [-0.2, 0, 0], head: [-0.35, 0, 0],
    hR: [-0.05, 0.84, 0.085], bR: [0.3, 1, 0.3], pR: [-1, -0.6, -0.2], hL: [0.05, 0.84, 0.085], bL: [-0.3, 1, 0.3], pL: [1, -0.6, -0.2],
    upL: [-0.2, 0, 0.05], knL: [0.5, 0, 0], upR: [-0.1, 0, -0.05], knR: [0.4, 0, 0] };
  const sit = { hips: [-0.3, 0.1, 0], hp: [0, -0.37, -0.02], spine: [0.05, 0, 0], chest: [0.12, 0, 0], head: [0.25, -0.2, 0.1],
    upL: [-1.25, 0, 0.2], knL: [0.65, 0, 0], upR: [-1.05, 0, -0.1], knR: [0.9, 0, 0],
    hR: [-0.2, 0.07, -0.2], bR: [0, 0, 1], pR: [-0.5, 0.5, -1], hL: [0.02, 0.38, 0.12], bL: [-1, 0.3, 0.2], pL: [1, -0.5, -0.3] };
  const hide = { hips: [0.35, 0, 0], hp: [0, -0.33, 0], spine: [0.3, 0, 0], chest: [0.25, 0, 0], neck: [0.1, 0, 0], head: [0.2, 0, 0],
    upL: [-2.1, 0, 0.18], knL: [2.35, 0, 0], upR: [-2.1, 0, -0.18], knR: [2.35, 0, 0],
    hR: [-0.07, 0.3, 0.24], bR: [1, 0, 0.2], pR: [-1, 0, -0.3], hL: [0.07, 0.3, 0.24], bL: [-1, 0, 0.2], pL: [1, 0, -0.3] };
  const hug = { spine: [0.18, 0, 0], chest: [0.05, 0, 0], head: [0.1, 0.6, 0.1], hp: [0, 0, 0.03],
    hR: [-0.1, 0.78, 0.24], bR: [1, 0.3, 0], pR: [-1, -0.3, 0], hL: [0.1, 0.78, 0.24], bL: [-1, 0.3, 0], pL: [1, -0.3, 0] };
  SISTER_ACTS = {
    idle: {},
    held: { f: [K(0, held)], g: 0, pin: true, foot: false, rate: 18,
      post: (T, s, t, st) => {
        const w = st.time * 8.5;
        T[CH.upL] += 0.45 * Math.sin(w); T[CH.knL] += 0.35 * (0.5 + 0.5 * Math.sin(w + 1.2));
        T[CH.upR] += 0.45 * Math.sin(w + 2.6); T[CH.knR] += 0.35 * (0.5 + 0.5 * Math.sin(w + 3.8));
        T[CH.hips + 2] += 0.06 * Math.sin(st.time * 2.2); T[CH.hips] += 0.05 * Math.sin(st.time * 1.7);
        T[CH.hR + 1] += 0.012 * Math.sin(st.time * 13); T[CH.hL + 1] += 0.012 * Math.sin(st.time * 11 + 1);
        T[CH.head + 2] += 0.12 * Math.sin(st.time * 5.3);
      } },
    sit: { f: [K(0, sit)], g: 0, rate: 10, post: (T, s, t, st) => {
      const j = Math.sin(st.time * 37) * 0.012 + Math.sin(st.time * 23) * 0.01;
      T[CH.head] += j * 1.5; T[CH.head + 1] += j; T[CH.hL + 1] += j * 0.4; T[CH.chest] += j * 0.6;
    } },
    hide: { f: [K(0, hide)], g: 0, rate: 8, post: (T, s, t, st) => {
      const c = st.time % 4.2, peek = sstep(2.6, 2.9, c) * (1 - sstep(3.6, 3.9, c));
      T[CH.head] -= 0.7 * peek; T[CH.neck] -= 0.25 * peek; T[CH.chest] -= 0.15 * peek; T[CH.head + 1] += 0.25 * peek;
      T[CH.chest] += Math.sin(st.time * 31) * 0.006;
    } },
    hug: { f: [K(0, {}), K(0.25, hug), K(1, hug)], rate: 10 },
    hurt: { f: [K(0, {}), K(0.12, { spine: [-0.2, 0.2, 0], head: [-0.3, 0.3, 0], hR: [-0.15, 0.8, 0.05], hL: [0.15, 0.8, 0.05] }, 'snap'), K(1, {})], rate: 30 },
  };
  return SISTER_ACTS;
}
const SISTER_GAIT = {
  idle: P({ spine: [0.02, 0, 0], head: [0.08, 0, 0.06], upL: [0, 0, 0.03], upR: [0, 0, -0.03],
    hR: [-0.035, 0.58, 0.11], bR: [1, 0.3, 0.3], pR: [-1, 0, -0.3], hL: [0.035, 0.58, 0.11], bL: [-1, 0.3, 0.3], pL: [1, 0, -0.3] }),
  runArms: P({ hR: [-0.14, 0.62, 0.06], hL: [0.14, 0.62, 0.06], bR: [0, 0, 1], bL: [0, 0, 1] }),
  air: P({ upL: [-0.8, 0, 0.1], knL: [1.3, 0, 0], upR: [-0.3, 0, -0.1], knR: [0.9, 0, 0] }),
  f0: 1.4, f1: 3.0, stride: 0.6, knee: 1.0, lean: 0.25, swing: 0.12, freeL: true, freeR: true,
  breath: 0.02, breathF: 0.33, sway: 0.03, scale: 0.6,
};

export function makeSister() {
  const G = sisterGeo();
  const mats = { body: toonMat(), cloth: toonMat(THREE.DoubleSide) };
  const out = outlineMat(0.0035, 0.016);
  const root = new THREE.Group(); root.name = 'sister';
  const J = {};
  J.hips = jg(root, 'hips', 0, 0.56, 0);
  J.spine = jg(J.hips, 'spine', 0, 0.04, 0);
  J.chest = jg(J.spine, 'chest', 0, 0.13, 0);
  J.neck = jg(J.chest, 'neck', 0, 0.155, 0);
  J.head = jg(J.neck, 'head', 0, 0.04, 0);
  for (const [sd, x] of [['L', 0.058], ['R', -0.058]]) {
    J['up' + sd] = jg(J.hips, 'up' + sd, x, -0.03, 0);
    J['kn' + sd] = jg(J['up' + sd], 'kn' + sd, 0, -0.25, 0);
    J['an' + sd] = jg(J['kn' + sd], 'an' + sd, 0, -0.23, 0);
    mk(J['up' + sd], G.thigh, mats.body, out); mk(J['kn' + sd], G.shin, mats.body, out); mk(J['an' + sd], G.foot, mats.body, out);
  }
  mk(J.hips, G.hips, mats.body, out); mk(J.spine, G.spine, mats.body, out); mk(J.chest, G.chest, mats.body, out);
  mk(J.head, G.head, mats.body, out); mk(J.head, G.eyes, SHARED.unlit, out, { shadow: false });
  const arms = {};
  for (const [sd, x] of [['L', 0.1], ['R', -0.1]]) {
    const sh = jg(J.chest, 'sh' + sd, x, 0.13, 0), el = jg(sh, 'el' + sd, 0, -0.16, 0), wr = jg(el, 'wr' + sd, 0, -0.14, 0);
    mk(sh, G.upperArm, mats.body, out); mk(el, G.forearm, mats.body, out); mk(wr, G.hand, mats.body, out);
    arms[sd] = { sh, el, wr, a: 0.16, b: 0.14 };
  }
  const rig = new Rig(root, J, arms);
  const neckY = chainPos(new V3(), [J.hips, J.spine, J.chest, J.neck]).y;
  const sockets = { neck: sock(J.neck, 'neck'), head: sock(J.head, 'head', 0, 0.1, 0) };
  const st = { time: 0, ph: 0, flash: flasher([mats.body, mats.cloth]) };
  const A = { rig, st, acts: sisterActs(), G: SISTER_GAIT, pin: new V3(0, neckY, 0) };
  const pose = (dt, s = {}) => drive(A, dt, s);
  pose(0, {});
  const api = finishActor({ root, mats: [mats.body, mats.cloth], st, height: 1.15, radius: 0.25, sockets, pose,
    actions: ['idle', 'held', 'sit', 'hide', 'hug', 'hurt'] });
  // While 'held' the rig keeps the neck exactly at (0, neckY, 0) in root space, so:
  // root.position = grabHand world pos - (0, neckY, 0)  (with root.rotation.y = ogre yaw).
  api.neckY = neckY;
  return api;
}

// ------------------------------------------------------------------ GOBLIN
const GC = { skin: 0x62784a, belly: 0x7c8b58, dark: 0x3d4a2e, ear: 0x8a5a52, cloth: 0x5a4430, rope: 0x8a6a40,
  tooth: 0xe8e0c8, claw: 0x2a2620, mouth: 0x2a0d10, wood: 0x5b3b22, stone: 0x6d6a64, shaft: 0x7a5a36, tip: 0x2c2a30 };
// Enemy dir is from the VIEWER: 'left' (player's screen-left) = the enemy's own right.
const enemyDir = (s) => (s.dir === 'left' ? 'R' : s.dir === 'right' ? 'L' : 'over');

function flapGeo(w, h, jag, seed, color) {
  const R = rng(seed), sh = new THREE.Shape();
  sh.moveTo(-w / 2, 0); sh.lineTo(w / 2, 0); sh.lineTo(w * 0.42, -h * 0.8);
  const n = 5;
  for (let i = 0; i <= n; i++) { const x = w * 0.42 - (i / n) * w * 0.84; sh.lineTo(x, -h + (i % 2 ? jag * (0.5 + R()) : 0)); }
  sh.lineTo(-w * 0.42, -h * 0.8); sh.lineTo(-w / 2, 0);
  const g = new THREE.ExtrudeGeometry(sh, { depth: 0.01, bevelEnabled: false });
  g.translate(0, 0, -0.005);
  return new GB().geo(g, color, new THREE.Matrix4(), 0.7).build();
}
function goblinGeo(weapon) {
  const key = 'goblin_' + weapon;
  if (GEO[key]) return GEO[key];
  const C = GC, g = GEO.goblinBase || (GEO.goblinBase = (() => {
    const b = {};
    b.pelvis = new GB().ell([0, 0, 0], [0.1, 0.075, 0.08], C.skin).torus([0, 0.04, 0], 0.1, 0.013, C.rope, [PI / 2, 0, 0], [1, 1, 0.85], 0.6).build();
    b.flapF = flapGeo(0.16, 0.2, 0.05, 3, C.cloth); b.flapB = flapGeo(0.19, 0.22, 0.06, 9, C.cloth);
    b.thigh = new GB().limb([0, 0.01, 0], [0, -0.24, 0], 0.05, 0.038, C.skin).build();
    b.shin = new GB().limb([0, 0, 0], [0, -0.22, 0], 0.04, 0.028, C.skin).build();
    b.foot = new GB().ell([0, -0.02, 0.05], [0.045, 0.028, 0.095], C.skin)
      .cone([0.025, -0.025, 0.13], [0.03, -0.03, 0.17], 0.012, C.claw, 0.6, 5).cone([-0.02, -0.025, 0.13], [-0.025, -0.03, 0.165], 0.012, C.claw, 0.6, 5).build();
    b.spine = new GB().ell([0, 0.075, 0.025], [0.115, 0.11, 0.105], C.belly).build();
    b.chest = new GB().ell([0, 0.06, -0.01], [0.13, 0.1, 0.1], C.skin)
      .cone([0, 0.12, -0.08], [0, 0.17, -0.12], 0.025, C.dark, 0.7, 5).cone([0, 0.05, -0.1], [0, 0.08, -0.15], 0.022, C.dark, 0.7, 5)
      .box([0, 0.05, 0.0], [0.03, 0.24, 0.21], C.rope, [0, 0, 0.7], 0.5).build();
    b.head = new GB().limb([0, -0.08, -0.02], [0, 0.03, 0], 0.04, 0.04, C.skin)
      .ell([0, 0.09, 0.0], [0.115, 0.1, 0.11], C.skin)
      .ell([0, 0.12, 0.07], [0.1, 0.035, 0.05], C.dark)
      .cone([0, 0.085, 0.1], [0, 0.04, 0.2], 0.03, C.skin, 1, 7)
      .ell([0, 0.03, 0.075], [0.08, 0.035, 0.06], C.skin)
      .ell([0, 0.035, 0.1], [0.06, 0.012, 0.03], C.mouth, [0, 0, 0], 0)
      .cone([0.035, 0.022, 0.115], [0.037, 0.06, 0.118], 0.009, C.tooth, 0.4, 5).cone([-0.035, 0.022, 0.115], [-0.037, 0.058, 0.118], 0.009, C.tooth, 0.4, 5)
      .geo(new THREE.ConeGeometry(0.05, 0.24, 7).translate(0, 0.12, 0), C.skin, (() => { const { m } = aimMat([0.09, 0.1, -0.01], [0.3, 0.2, -0.1]); return m.multiply(new THREE.Matrix4().makeScale(1, 1, 0.4)); })())
      .geo(new THREE.ConeGeometry(0.05, 0.24, 7).translate(0, 0.12, 0), C.skin, (() => { const { m } = aimMat([-0.09, 0.1, -0.01], [-0.3, 0.2, -0.1]); return m.multiply(new THREE.Matrix4().makeScale(1, 1, 0.4)); })())
      .build();
    b.eyes = new GB().ell([0.045, 0.1, 0.1], [0.024, 0.014, 0.012], 0xffffff, [0, 0.35, -0.3], 0)
      .ell([-0.045, 0.1, 0.1], [0.024, 0.014, 0.012], 0xffffff, [0, -0.35, 0.3], 0).build();
    b.upperArm = new GB().ell([0, -0.01, 0], [0.045, 0.045, 0.045], C.skin).limb([0, 0, 0], [0, -0.26, 0], 0.036, 0.03, C.skin).build();
    b.forearm = new GB().limb([0, 0, 0], [0, -0.25, 0], 0.032, 0.026, C.skin).torus([0, -0.2, 0], 0.03, 0.008, C.rope, [PI / 2, 0, 0], [1, 1, 1], 0.4).build();
    const claw = new GB().ell([-0.004, -0.045, 0], [0.034, 0.05, 0.042], C.skin);
    for (const z of [-0.025, 0, 0.025]) claw.limb([0, -0.08, z], [-0.02, -0.12, z * 1.2], 0.011, 0.009, C.skin, 0.8, 6).cone([-0.02, -0.12, z * 1.2], [-0.035, -0.15, z * 1.2], 0.009, C.claw, 0.5, 5);
    b.clawL = claw.build();
    b.fistR = new GB().ell([0.004, -0.05, 0], [0.034, 0.048, 0.042], C.skin).limb([0.036, -0.065, -0.03], [0.036, -0.065, 0.03], 0.017, 0.017, C.skin, 0.8, 8).build();
    return b;
  })());
  const w = new GB();
  if (weapon === 'club') {
    w.limb([0, 0, -0.08], [0, 0, 0.6], 0.022, 0.058, C.wood, 1, 9);
    const R = rng(4);
    for (let i = 0; i < 6; i++) { const a = i * 1.1, z = 0.38 + R() * 0.2, r = 0.045 + (z - 0.3) * 0.05;
      w.cone([Math.cos(a) * r * 0.8, Math.sin(a) * r * 0.8, z], [Math.cos(a) * (r + 0.04), Math.sin(a) * (r + 0.04), z + 0.02], 0.014, C.stone, 0.6, 5); }
    w.ell([0, 0, -0.085], [0.028, 0.028, 0.02], C.wood);
  } else {
    w.limb([0, 0, -0.62], [0, 0, 1.0], 0.014, 0.014, C.shaft, 1, 7)
      .cone([0, 0, 0.97], [0, 0, 1.2], 0.03, C.tip, 1, 5).cyl([0, 0, 0.97], 0.02, 0.02, 0.05, C.rope, [PI / 2, 0, 0], 0.5, 6)
      .cone([0, 0, 0.96], [0.03, 0.01, 0.92], 0.012, C.cloth, 0.5, 4);
  }
  const out = Object.assign({}, g, { weapon: w.build() });
  return (GEO[key] = out);
}
const GOB_ACTS = {};
function goblinActs(weapon) {
  if (GOB_ACTS[weapon]) return GOB_ACTS[weapon];
  const hold = (f) => [K(0, {}), K(0.3, f, 'back'), K(1, f)];
  let windup, attack;
  if (weapon === 'club') {
    const wo = { spine: [0.05, 0, 0], chest: [-0.05, 0, 0], neck: [-0.15, 0, 0], head: [-0.3, 0, 0], hR: [-0.12, 1.12, -0.1], bR: [0, 0.9, -0.45], pR: [-0.8, 0, -0.3],
      hL: [0.25, 0.72, 0.35], bL: [0, 0.3, 1], upL: [-0.35, 0, 0.12], knL: [0.5, 0, 0], upR: [0.2, 0, -0.1] };
    const wR = { spine: [0.25, -0.75, 0], chest: [0.1, -0.3, 0], head: [-0.5, 0.7, 0], hR: [-0.52, 0.8, -0.15], bR: [-0.55, 0.6, -0.6], pR: [-0.2, -1, 0], hL: [0.3, 0.55, 0.35] };
    const wL = { spine: [0.3, 0.7, 0], chest: [0.1, 0.35, 0], head: [-0.5, -0.7, 0], hR: [0.28, 0.85, 0.05], bR: [0.6, 0.55, -0.55], pR: [-0.9, -0.3, 0.2], hL: [0.35, 0.5, -0.2] };
    windup = { over: hold(wo), R: hold(wR), L: hold(wL) };
    const hitO = { spine: [0.75, 0, 0], chest: [0.35, 0, 0], head: [-0.6, 0, 0], hR: [-0.08, 0.3, 0.55], bR: [0, -0.8, 0.6], pR: [-0.8, -0.3, 0.3], hL: [0.3, 0.35, 0.1], upR: [-0.6, 0, 0], knR: [0.8, 0, 0], upL: [0.35, 0, 0], knL: [0.4, 0, 0] };
    const hitR = { spine: [0.5, 0.6, 0], chest: [0.2, 0.3, 0], head: [-0.55, -0.4, 0], hR: [0.3, 0.5, 0.35], bR: [0.95, -0.1, 0.25], pR: [-0.3, -1, 0], hL: [0.2, 0.4, -0.15], upR: [-0.4, 0, 0], knR: [0.6, 0, 0] };
    const hitL = { spine: [0.5, -0.6, 0], chest: [0.2, -0.3, 0], head: [-0.55, 0.4, 0], hR: [-0.45, 0.5, 0.3], bR: [-0.95, -0.1, 0.25], pR: [-0.5, -1, 0], hL: [0.3, 0.45, 0.2], upR: [-0.4, 0, 0], knR: [0.6, 0, 0] };
    const strike = (w, h) => [K(0, w), K(0.18, h, 'snap'), K(0.6, h), K(1, {})];
    attack = { over: strike(wo, hitO), R: strike(wR, hitR), L: strike(wL, hitL) };
  } else {
    const wS = { spine: [0.2, -0.35, 0], chest: [0.05, -0.2, 0], head: [-0.45, 0.3, 0], hR: [-0.22, 0.6, -0.28], bR: [0.1, 0.12, 1], pR: [-1, 0, -0.3],
      upL: [-0.45, 0, 0.12], knL: [0.6, 0, 0], upR: [0.35, 0, -0.1], knR: [0.4, 0, 0] };
    const hS = { spine: [0.6, 0, 0], chest: [0.2, 0, 0], head: [-0.7, 0, 0], hR: [-0.1, 0.62, 0.52], bR: [0, 0.02, 1], pR: [-1, -0.3, 0],
      upR: [-0.7, 0, -0.1], knR: [0.7, 0, 0], upL: [0.5, 0, 0.1], knL: [0.3, 0, 0] };
    windup = [K(0, {}), K(0.3, wS, 'back'), K(1, wS)];
    attack = [K(0, wS), K(0.15, hS, 'snap'), K(0.55, hS), K(1, {})];
  }
  GOB_ACTS[weapon] = {
    idle: {},
    windup: { f: windup, rate: 26, post: (T, s, t, st) => { const q = Math.sin(st.time * 47) * 0.012 * sstep(0.3, 0.5, t); T[CH.hR] += q; T[CH.hR + 1] += q; T[CH.head + 1] += q * 2; } },
    attack: { f: attack, rate: 40 },
    throw: { f: [K(0, {}),
      K(0.4, { spine: [-0.05, -0.45, 0], chest: [-0.1, -0.25, 0], head: [-0.4, 0.35, 0], hR: [-0.3, 1.02, -0.28], bR: [0.05, 0.4, 1], pR: [-0.8, -0.4, -0.3],
        hL: [0.3, 0.9, 0.45], bL: [0, 0.5, 1], upL: [-0.4, 0, 0.1], knL: [0.4, 0, 0], upR: [0.3, 0, 0], knR: [0.3, 0, 0] }),
      K(0.5, { spine: [0.55, 0.35, 0], chest: [0.2, 0.2, 0], head: [-0.6, -0.2, 0], hR: [-0.05, 0.8, 0.5], bR: [0, 0.2, 1], pR: [-1, -0.4, 0], hL: [0.3, 0.4, -0.1], upR: [-0.5, 0, 0], knR: [0.6, 0, 0], upL: [0.35, 0, 0] }, 'snap'),
      K(0.75, { spine: [0.6, 0.4, 0], chest: [0.2, 0.25, 0], head: [-0.6, -0.2, 0], hR: [0.15, 0.4, 0.3], bR: [0.3, -0.6, 0.7], pR: [-1, -0.4, 0], hL: [0.3, 0.4, -0.1], upR: [-0.5, 0, 0], knR: [0.6, 0, 0], upL: [0.35, 0, 0] }),
      K(1, {})], rate: 36 },
    hurt: { f: [K(0, {}), K(0.1, { spine: [0.1, 0.25, 0], chest: [-0.2, 0.15, 0], head: [-0.9, 0.3, 0.2], hR: [-0.35, 0.75, 0.0], hL: [0.4, 0.8, 0.0] }, 'snap'),
      K(0.4, { spine: [0.25, 0.1, 0], chest: [0.1, 0.05, 0], head: [-0.5, 0.1, 0.1] }), K(1, {})], rate: 36 },
    stagger: { f: [K(0, {}), K(0.12, { spine: [-0.1, -0.3, 0.15], chest: [-0.3, 0, 0], head: [-0.7, 0.4, 0], hp: [0, 0, -0.12], hR: [-0.5, 0.95, -0.15], hL: [0.5, 1.0, -0.1],
      upR: [0.6, 0, -0.2], knR: [0.3, 0, 0], upL: [-0.3, 0, 0.2] }, 'snap'),
      K(0.45, { spine: [0.8, 0.2, 0], chest: [0.4, 0, 0], head: [-0.2, 0, 0], hR: [-0.3, 0.25, 0.3], hL: [0.3, 0.25, 0.35], upL: [-0.5, 0, 0], knL: [0.9, 0, 0], upR: [0.2, 0, 0], knR: [0.6, 0, 0] }), K(1, {})], rate: 30 },
    dead: { f: [K(0, {}),
      K(0.28, { hp: [0, -0.15, 0], upL: [-1.1, 0, 0.2], knL: [1.9, 0, 0], upR: [-0.9, 0, -0.2], knR: [1.8, 0, 0], spine: [0.7, 0, 0], chest: [0.3, 0, 0], head: [-0.1, 0, 0],
        hR: [-0.3, 0.2, 0.35], hL: [0.3, 0.2, 0.35] }, 'in'),
      K(0.62, { hips: [-1.52, 0.2, 0.25], hp: [0, -0.34, -0.15], spine: [-0.15, 0, 0], chest: [-0.1, 0, 0], neck: [-0.2, 0, 0], head: [-0.3, 0.8, 0],
        upL: [-0.15, 0, 0.25], knL: [0.35, 0, 0], upR: [0.0, 0, -0.3], knR: [0.2, 0, 0],
        hR: [-0.55, 0.06, -0.15], bR: [-0.4, 0, 1], pR: [-1, 0.3, 0], hL: [0.5, 0.06, -0.3], pL: [1, 0.3, 0] }, 'in'),
      K(0.7, { hips: [-1.45, 0.2, 0.25], hp: [0, -0.3, -0.15], spine: [-0.1, 0, 0], head: [-0.2, 0.7, 0], hR: [-0.5, 0.12, -0.25], hL: [0.45, 0.12, -0.35] }),
      K(0.8, { hips: [-1.52, 0.2, 0.25], hp: [0, -0.34, -0.15], spine: [-0.15, 0, 0], head: [-0.3, 0.8, 0], hR: [-0.55, 0.06, -0.15], bR: [-0.4, 0, 1], pR: [-1, 0.3, 0], hL: [0.5, 0.06, -0.3], pL: [1, 0.3, 0] }),
      K(1, { hips: [-1.52, 0.2, 0.25], hp: [0, -0.34, -0.15], spine: [-0.15, 0, 0], head: [-0.3, 0.8, 0], hR: [-0.55, 0.06, -0.15], bR: [-0.4, 0, 1], pR: [-1, 0.3, 0], hL: [0.5, 0.06, -0.3], pL: [1, 0.3, 0] })],
      g: (t) => 1 - sstep(0.2, 0.45, t), rate: 24 },
  };
  return GOB_ACTS[weapon];
}
function goblinGait(weapon) {
  const idle = { spine: [0.35, 0, 0], chest: [0.3, 0, 0], neck: [-0.25, 0, 0], head: [-0.35, 0, 0],
    upL: [-0.3, 0, 0.14], knL: [0.62, 0, 0], upR: [-0.12, 0, -0.14], knR: [0.55, 0, 0],
    hR: [-0.24, 0.48, 0.3], bR: [-0.1, 0.9, 0.35], pR: [-0.8, 0, -0.5], hL: [0.25, 0.44, 0.28], bL: [0, 0, 1], pL: [0.8, 0, -0.5] };
  if (weapon === 'spear') Object.assign(idle, { hR: [-0.2, 0.5, 0.1], bR: [0.04, 0.3, 1] });
  return {
    idle: P(idle), runArms: P({ hR: [-0.3, 0.5, -0.05], hL: [0.3, 0.5, -0.1] }),
    air: P({ upL: [-0.9, 0, 0.1], knL: [1.5, 0, 0], upR: [-0.6, 0, -0.1], knR: [1.3, 0, 0] }),
    f0: 1.4, f1: 3.1, stride: 0.6, knee: 1.0, lean: 0.25, swing: 0.1, freeL: true, freeR: false,
    breath: 0.03, breathF: 0.5, sway: 0.06, scale: 0.8,
  };
}

export function makeGoblin(seed = 1) {
  const R = rng(seed * 7919 + 13);
  const weapon = R() < 0.5 ? 'club' : 'spear';
  const G = goblinGeo(weapon);
  const mats = { body: toonMat(), cloth: toonMat(THREE.DoubleSide) };
  const tint = 0.88 + R() * 0.24;
  mats.body.color.setRGB(tint * (0.95 + R() * 0.1), tint, tint * (0.92 + R() * 0.12));
  const out = outlineMat(0.0035, 0.018);
  const root = new THREE.Group(); root.name = 'goblin';
  const J = {};
  J.hips = jg(root, 'hips', 0, 0.53, 0);
  J.spine = jg(J.hips, 'spine', 0, 0.04, 0);
  J.chest = jg(J.spine, 'chest', 0, 0.16, 0);
  J.neck = jg(J.chest, 'neck', 0, 0.15, 0.03);
  J.head = jg(J.neck, 'head', 0, 0.06, 0.02);
  for (const [sd, x] of [['L', 0.075], ['R', -0.075]]) {
    J['up' + sd] = jg(J.hips, 'up' + sd, x, -0.02, 0);
    J['kn' + sd] = jg(J['up' + sd], 'kn' + sd, 0, -0.24, 0);
    J['an' + sd] = jg(J['kn' + sd], 'an' + sd, 0, -0.22, 0);
    mk(J['up' + sd], G.thigh, mats.body, out); mk(J['kn' + sd], G.shin, mats.body, out); mk(J['an' + sd], G.foot, mats.body, out);
  }
  mk(J.hips, G.pelvis, mats.body, out);
  const flapF = jg(J.hips, 'flapF', 0, 0.03, 0.075), flapB = jg(J.hips, 'flapB', 0, 0.03, -0.075);
  mk(flapF, G.flapF, mats.cloth, out); mk(flapB, G.flapB, mats.cloth, out);
  const belly = mk(J.spine, G.spine, mats.body, out);
  const bs = 0.9 + R() * 0.25; belly.scale.set(bs, 0.95 + R() * 0.1, bs);
  mk(J.chest, G.chest, mats.body, out);
  mk(J.head, G.head, mats.body, out);
  const eyes = new THREE.Mesh(G.eyes, SHARED.goblinEye); eyes.raycast = noRay; J.head.add(eyes);
  const hs = 0.9 + R() * 0.25; J.head.scale.setScalar(hs);
  const arms = {};
  for (const [sd, x] of [['L', 0.15], ['R', -0.15]]) {
    const sh = jg(J.chest, 'sh' + sd, x, 0.12, 0), el = jg(sh, 'el' + sd, 0, -0.26, 0), wr = jg(el, 'wr' + sd, 0, -0.25, 0);
    mk(sh, G.upperArm, mats.body, out); mk(el, G.forearm, mats.body, out); mk(wr, sd === 'R' ? G.fistR : G.clawL, mats.body, out);
    arms[sd] = { sh, el, wr, a: 0.26, b: 0.25 };
  }
  const weap = jg(arms.R.wr, 'weapon', 0.012, -0.064, 0);
  mk(weap, G.weapon, mats.body, out);
  const scale = 0.92 + R() * 0.16; root.scale.setScalar(scale);
  const sockets = { head: sock(J.head, 'head', 0, 0.09, 0.02), hand: sock(arms.R.wr, 'hand', 0.012, -0.064, 0) };
  const rig = new Rig(root, J, arms);
  const st = { time: R() * 10, ph: R() * 6, flash: flasher([mats.body, mats.cloth]) };
  const A = { rig, st, acts: goblinActs(weapon), G: goblinGait(weapon), dirOf: enemyDir };
  A.postAll = (T, s, t, st2, name) => {
    if (weapon === 'spear' && name !== 'throw' && name !== 'dead' && name !== 'hurt' && name !== 'stagger') {
      const bx = T[CH.bR], by = T[CH.bR + 1], bz = T[CH.bR + 2], l = Math.hypot(bx, by, bz) || 1;
      T[CH.hL] = T[CH.hR] + bx / l * 0.36 + 0.02; T[CH.hL + 1] = T[CH.hR + 1] + by / l * 0.36 - 0.02; T[CH.hL + 2] = T[CH.hR + 2] + bz / l * 0.36;
      T[CH.bL] = bx; T[CH.bL + 1] = by; T[CH.bL + 2] = bz;
    }
    T[CH.head + 1] += Math.sin(st2.time * 0.9) * 0.15 * (name === 'idle' ? 1 : 0);
  };
  A.after = (dt, s, t, name) => {
    const c = rig.cur;
    flapF.rotation.x = Math.min(0, Math.min(c[CH.upL], c[CH.upR])) * 0.8 - c[CH.hips] * 0.2;
    flapB.rotation.x = Math.max(0, Math.max(c[CH.upL], c[CH.upR])) * 0.8 + 0.05;
    weap.visible = !(name === 'throw' && t > 0.47);
  };
  const pose = (dt, s = {}) => drive(A, dt, s);
  pose(0, {});
  const api = finishActor({ root, mats: [mats.body, mats.cloth], st, height: 1.1 * scale, radius: 0.45, sockets, pose,
    actions: ['idle', 'windup', 'attack', 'throw', 'hurt', 'stagger', 'dead'],
    dirs: weapon === 'club' ? { windup: ['left', 'right', 'over'], attack: ['left', 'right', 'over'] } : {} });
  api.weapon = weap; api.weaponType = weapon;
  return api;
}

// ------------------------------------------------------------------ OGRE
const OC = { skin: 0x4c5868, belly: 0x5a6676, dark: 0x343c48, horn: 0xd9cfb2, leather: 0x3b2a1e, metal: 0x5d636c, rivet: 0x9a9ea6,
  cloth: 0x3b3226, tooth: 0xe6dcc0, mouth: 0x2a0a0e, blade: 0x676c73, edge: 0xb9c0c9, rust: 0x6e3f24, wood: 0x3a2a1c, flesh: 0x8a1020, bone: 0xe8e0d0 };
const OG = { a1: 0.5, a2: 0.5, b: 0.95, grab: [-0.17, -0.25, 0.075] };
const FINGERS = { z: [0.11, 0.035, -0.04, -0.115], L: [0.13, 0.1, 0.085], base: [-0.02, -0.3] };
const THUMB = { base: [-0.06, -0.1, 0.16], L: [0.1, 0.085], rot: -PI / 2 };

// Solve the full-curl joint angles so the finger centerline wraps a circle of radius R around C
// (the 12 cm neck + finger radius): a tiny grid search at build time, not hand-tuned numbers.
function fitCurl(B, Ls, theta0, C, R, lo, hi) {
  const n = Ls.length, steps = 40, best = { e: Infinity, j: new Array(n).fill(0) }, j = new Array(n).fill(0);
  const evalE = () => {
    let x = B[0], y = B[1], th = theta0, e = 0;
    for (let k = 0; k < n; k++) {
      th += j[k];
      const dx = Math.sin(th), dy = -Math.cos(th);
      const mx = x + dx * Ls[k] * 0.5, my = y + dy * Ls[k] * 0.5; x += dx * Ls[k]; y += dy * Ls[k];
      const e1 = Math.hypot(mx - C[0], my - C[1]) - R, e2 = Math.hypot(x - C[0], y - C[1]) - R;
      e += e1 * e1 + e2 * e2 * (k === n - 1 ? 2 : 1);
    }
    return e;
  };
  const rec = (k) => {
    if (k === n) { const e = evalE(); if (e < best.e) { best.e = e; best.j = j.slice(); } return; }
    for (let i = 0; i <= steps; i++) { j[k] = lo + (hi - lo) * i / steps; rec(k + 1); }
  };
  rec(0);
  return best.j;
}
function ogreGeo() {
  if (GEO.ogre) return GEO.ogre;
  const C = OC, g = {};
  g.pelvis = new GB().ell([0, 0.05, 0.02], [0.62, 0.45, 0.5], C.skin)
    .cyl([0, 0.22, 0.02], 0.64, 0.67, 0.16, C.leather).box([0, 0.22, 0.66], [0.22, 0.17, 0.05], C.metal, [0, 0, 0], 0.6)
    .ell([0.2, 0.22, 0.64], [0.03, 0.03, 0.02], C.rivet, [0, 0, 0], 0.3).ell([-0.2, 0.22, 0.64], [0.03, 0.03, 0.02], C.rivet, [0, 0, 0], 0.3).build();
  g.flapF = new GB().box([0, -0.36, 0], [0.4, 0.74, 0.05], C.cloth, [0, 0, 0], 0.8).build();
  g.flapB = new GB().box([0, -0.34, 0], [0.62, 0.7, 0.05], C.cloth, [0, 0, 0], 0.8).build();
  g.thigh = new GB().limb([0, 0.05, 0], [0, -0.95, 0], 0.3, 0.24, C.skin, 1, 12).build();
  g.shin = new GB().limb([0, 0, 0], [0, -0.85, 0], 0.24, 0.17, C.skin, 1, 12).cyl([0, -0.64, 0], 0.19, 0.2, 0.3, C.leather, [0, 0, 0], 0.7).build();
  g.foot = new GB().ell([0, -0.06, 0.14], [0.21, 0.13, 0.36], C.skin)
    .ell([0.1, -0.08, 0.47], [0.05, 0.04, 0.04], C.horn, [0, 0, 0], 0.5).ell([-0.05, -0.08, 0.49], [0.05, 0.04, 0.04], C.horn, [0, 0, 0], 0.5).build();
  g.spine = new GB().ell([0, 0.3, 0.1], [0.66, 0.56, 0.6], C.belly, [0, 0, 0], 1, 18).build();
  const u = new V3(0.54, 0.84, 0).normalize(), v = new V3(0, 0, 1), w = new V3().crossVectors(u, v);
  const strap = new THREE.Matrix4().makeBasis(u.clone().multiplyScalar(0.95), v.clone().multiplyScalar(0.62), w).setPosition(0, 0.3, -0.02);
  g.chest = new GB().ell([0, 0.3, -0.02], [0.88, 0.58, 0.58], C.skin, [0, 0, 0], 1, 18)
    .ell([0.35, 0.35, 0.33], [0.36, 0.26, 0.2], C.skin).ell([-0.35, 0.35, 0.33], [0.36, 0.26, 0.2], C.skin)
    .ell([0, 0.66, -0.08], [0.6, 0.24, 0.36], C.skin)
    .geo(new THREE.TorusGeometry(1, 0.075, 6, 32), C.leather, strap, 0.8)
    .ell([0.3, 0.72, 0.37], [0.06, 0.06, 0.03], C.metal, [0, 0, 0], 0.5).build();
  const hd = new GB().limb([0, -0.25, -0.08], [0, 0.1, 0], 0.3, 0.28, C.skin, 1, 12)
    .ell([0, 0.26, 0.04], [0.3, 0.3, 0.31], C.skin, [0, 0, 0], 1, 16)
    .ell([0, 0.345, 0.25], [0.29, 0.075, 0.11], C.dark)
    .ell([0, 0.25, 0.32], [0.075, 0.09, 0.07], C.skin)
    .ell([0.17, 0.2, 0.2], [0.1, 0.08, 0.1], C.skin).ell([-0.17, 0.2, 0.2], [0.1, 0.08, 0.1], C.skin)
    .cone([0.28, 0.28, 0], [0.44, 0.36, -0.08], 0.07, C.skin, 1, 6).cone([-0.28, 0.28, 0], [-0.44, 0.36, -0.08], 0.07, C.skin, 1, 6)
    .box([0, 0.13, 0.27], [0.24, 0.04, 0.05], C.tooth, [0, 0, 0], 0.5);
  for (const m of [1, -1]) hd.chain([[0.2 * m, 0.44, 0.06], [0.33 * m, 0.52, 0.1], [0.42 * m, 0.63, 0.17], [0.43 * m, 0.74, 0.28]], [0.095, 0.075, 0.05, 0.012], C.horn, 1);
  g.head = hd.build();
  g.eyes = new GB().ell([0.11, 0.27, 0.315], [0.045, 0.026, 0.025], 0xffffff, [0, 0.3, -0.2], 0)
    .ell([-0.11, 0.27, 0.315], [0.045, 0.026, 0.025], 0xffffff, [0, -0.3, 0.2], 0).build();
  g.jaw = new GB().ell([0, -0.04, 0.1], [0.27, 0.1, 0.2], C.skin).ell([0, 0.02, 0.12], [0.2, 0.05, 0.14], C.mouth, [0, 0, 0], 0)
    .cone([0.15, 0.0, 0.24], [0.17, 0.16, 0.27], 0.035, C.tooth, 0.6, 6).cone([-0.15, 0.0, 0.24], [-0.17, 0.16, 0.27], 0.035, C.tooth, 0.6, 6).build();
  g.deltoid = new GB().ell([0, -0.02, 0], [0.38, 0.36, 0.38], C.skin).build();
  g.pauldron = new GB().ell([-0.06, 0.1, 0], [0.45, 0.3, 0.45], C.metal).ell([-0.3, 0.2, 0.25], [0.04, 0.04, 0.04], C.rivet, [0, 0, 0], 0.4)
    .ell([-0.3, 0.2, -0.25], [0.04, 0.04, 0.04], C.rivet, [0, 0, 0], 0.4).torus([0, -0.12, 0], 0.33, 0.04, C.leather, [PI / 2, 0, 0], [1, 1, 1], 0.6).build();
  g.upperA = new GB().limb([0, 0, 0], [0, -0.5, 0], 0.28, 0.26, C.skin, 1, 12).build();
  g.upperB = new GB().limb([0, 0, 0], [0, -0.5, 0], 0.26, 0.24, C.skin, 1, 12).build();
  g.forearm = new GB().limb([0, 0, 0], [0, -0.95, 0], 0.24, 0.19, C.skin, 1, 12).cyl([0, -0.72, 0], 0.21, 0.22, 0.36, C.leather, [0, 0, 0], 0.7)
    .torus([0, -0.55, 0], 0.215, 0.025, C.metal, [PI / 2, 0, 0], [1, 1, 1], 0.4).build();
  g.fistR = new GB().ell([0, -0.16, 0], [0.11, 0.17, 0.17], C.skin)
    .limb([0.14, -0.24, -0.13], [0.14, -0.24, 0.13], 0.06, 0.06, C.skin, 1, 10)
    .limb([0.05, -0.33, -0.12], [0.05, -0.33, 0.12], 0.065, 0.065, C.skin, 1, 10)
    .limb([0.02, -0.08, 0.15], [0.12, -0.16, 0.18], 0.05, 0.045, C.skin).build();
  const cl = new GB().cyl([0, 0, 0.04], 0.055, 0.055, 0.64, C.wood, [PI / 2, 0, 0], 1, 10).ell([0, 0, -0.29], [0.07, 0.07, 0.05], C.metal)
    .box([0, -0.21, 1.005], [0.05, 0.58, 1.35], C.blade).box([0, -0.49, 1.005], [0.056, 0.05, 1.33], C.edge, [0, 0, 0], 0.5)
    .box([0.027, -0.13, 0.75], [0.004, 0.22, 0.34], C.rust, [0, 0, 0], 0).box([-0.027, -0.28, 1.25], [0.004, 0.16, 0.3], C.rust, [0, 0, 0], 0)
    .cyl([0, -0.03, 1.48], 0.07, 0.07, 0.058, 0x151518, [0, 0, PI / 2], 0.3, 12);
  for (let i = 0; i < 4; i++) cl.ell([0, 0.075, 0.45 + i * 0.3], [0.035, 0.02, 0.03], C.rivet, [0, 0, 0], 0.3);
  g.cleaver = cl.build();
  g.palmL = new GB().ell([-0.0, -0.16, 0], [0.11, 0.17, 0.17], C.skin).build();
  g.phal = FINGERS.L.map((L, i) => new GB().limb([0, 0, 0], [0, -L, 0], 0.048 - i * 0.002, 0.044 - i * 0.002, C.skin, 1, 8).build());
  g.thumb = THUMB.L.map((L) => new GB().limb([0, 0, 0], [0, -L, 0], 0.05, 0.045, C.skin, 1, 8).build());
  g.stumpCap = new GB().cyl([0, 0, 0], 0.255, 0.255, 0.04, C.flesh, [0, 0, 0], 1, 14).cyl([0, 0.021, 0], 0.07, 0.07, 0.01, C.bone, [0, 0, 0], 0, 10).build();
  // curl solutions (full grip around the 12 cm gap)
  const Rn = 0.06 + 0.046;
  g.fingerCurl = fitCurl(FINGERS.base, FINGERS.L, 0, OG.grab, Rn, -2.2, 0.2);
  g.thumbCurl = fitCurl([THUMB.base[0], THUMB.base[1]], THUMB.L, THUMB.rot, OG.grab, Rn, -0.4, 2.2);
  return (GEO.ogre = g);
}
let OGRE_ACTS = null;
const OGRE_HOLD = { chest: [0.1, 0.22, 0], neck: [0.2, 0, 0], head: [-0.15, 0.32, 0], hL: [0.7, 3.3, 2.0], bL: [0, 1, 0], pL: [1, -0.3, -0.5] };
function ogreActs() {
  if (OGRE_ACTS) return OGRE_ACTS;
  const armedHold = { ...OGRE_HOLD, hR: [-1.25, 2.05, 0.8], bR: [-0.05, 0.4, 1], pR: [-1, 0, -0.4] };
  const raised = { ...OGRE_HOLD, spine: [-0.05, 0.1, 0], chest: [-0.05, 0.2, 0], head: [-0.2, 0.3, 0], hR: [-0.6, 5.0, 0.15], bR: [0.1, 1, -0.25], pR: [-1, -0.2, 0.2] };
  const chopped = { ...OGRE_HOLD, spine: [0.35, 0.3, 0], chest: [0.2, 0.1, 0], head: [-0.4, 0.2, 0], hR: [0.15, 2.7, 1.9], bR: [0.3, -0.55, 1], pR: [-1, 0, 0] };
  const wOver = { spine: [-0.22, 0, 0], chest: [-0.12, 0, 0], head: [-0.15, 0, 0], hR: [-0.5, 5.05, -0.2], bR: [0.05, 1, -0.3], pR: [-1, -0.1, 0],
    hL: [1.0, 2.8, 1.0], bL: [0, 0.4, 1], upL: [-0.3, 0, 0.15], knL: [0.4, 0, 0], upR: [0.2, 0, -0.12] };
  const wR = { hips: [0, -0.25, 0], spine: [0.05, -0.65, 0], chest: [0, -0.35, 0], head: [-0.25, 0.7, 0], hR: [-2.1, 3.55, -0.45], bR: [-0.55, 0.8, -0.35], pR: [0, -1, 0],
    hL: [1.3, 2.6, 1.1], bL: [0, 0, 1], upL: [-0.25, 0, 0.2], upR: [0.2, 0, -0.25], knR: [0.4, 0, 0] };
  const wL = { hips: [0, 0.25, 0], spine: [0.05, 0.7, 0], chest: [0, 0.4, 0], head: [-0.25, -0.75, 0], hR: [1.4, 3.65, -0.15], bR: [0.6, 0.8, -0.3], pR: [-0.3, -1, 0.3],
    hL: [1.5, 2.4, -0.6], upR: [-0.25, 0, -0.2], upL: [0.2, 0, 0.25], knL: [0.4, 0, 0] };
  const hold3 = (f) => [K(0, {}), K(0.3, f, 'back'), K(1, f)];
  const slamHit = { spine: [0.55, 0, 0], chest: [0.25, 0, 0], head: [-0.45, 0, 0], hR: [-0.35, 1.35, 2.35], bR: [0, -0.55, 1], pR: [-1, 0.2, 0],
    hL: [0.9, 1.3, 1.4], upR: [-0.55, 0, -0.12], knR: [0.8, 0, 0], upL: [0.35, 0, 0.15], knL: [0.5, 0, 0] };
  const sweepR = [K(0, wR), K(0.12, { hips: [0, -0.05, 0], spine: [0.2, -0.1, 0], chest: [0.1, 0, 0], hR: [-0.8, 2.6, 2.0], bR: [-0.3, 0, 1], pR: [-0.3, -1, 0] }, 'in'),
    K(0.24, { hips: [0, 0.25, 0], spine: [0.25, 0.65, 0], chest: [0.1, 0.3, 0], head: [-0.3, -0.4, 0], hR: [1.8, 2.3, 0.9], bR: [1, -0.05, -0.35], pR: [0.3, -1, 0],
      hL: [0.9, 2.2, -0.8], upL: [0.2, 0, 0.2], upR: [-0.35, 0, -0.25], knR: [0.5, 0, 0] }, 'snap')];
  sweepR.push(K(0.65, sweepR[2].p ? { hips: [0, 0.22, 0], spine: [0.22, 0.6, 0], chest: [0.1, 0.28, 0], head: [-0.3, -0.35, 0], hR: [1.75, 2.25, 0.9], bR: [1, -0.1, -0.35], pR: [0.3, -1, 0],
    hL: [0.9, 2.2, -0.8], upL: [0.2, 0, 0.2], upR: [-0.35, 0, -0.25], knR: [0.5, 0, 0] } : {}), K(1, {}));
  const sweepL = [K(0, wL), K(0.12, { spine: [0.2, 0.1, 0], hR: [0.5, 2.7, 2.1], bR: [0.3, 0, 1], pR: [-0.5, -1, 0] }, 'in'),
    K(0.24, { hips: [0, -0.25, 0], spine: [0.25, -0.65, 0], chest: [0.1, -0.3, 0], head: [-0.3, 0.4, 0], hR: [-1.9, 2.3, 0.6], bR: [-1, -0.05, -0.3], pR: [-0.3, -1, 0.3], hL: [1.4, 2.4, 1.0] }, 'snap'),
    K(0.65, { hips: [0, -0.22, 0], spine: [0.22, -0.6, 0], chest: [0.1, -0.28, 0], head: [-0.3, 0.35, 0], hR: [-1.85, 2.25, 0.6], bR: [-1, -0.1, -0.3], pR: [-0.3, -1, 0.3], hL: [1.4, 2.4, 1.0] }), K(1, {})];
  const lying = { hips: [-1.5, 0.15, 0], hp: [0, -1.42, -0.9], spine: [-0.1, 0, 0], chest: [-0.1, 0, 0], neck: [0, 0, 0], head: [0.2, 0.7, 0], jaw: [0.4, 0, 0],
    upL: [-0.2, 0, 0.3], knL: [0.4, 0, 0], upR: [-0.5, 0, -0.25], knR: [0.9, 0, 0],
    hR: [-2.0, 0.3, -0.6], bR: [-0.3, 0.2, 1], pR: [0, 1, 0], hL: [1.9, 0.3, -1.0], pL: [0, 1, 0] };
  const tremble = (T, s, t, st) => { const q = Math.sin(st.time * 31) * 0.03 * sstep(0.3, 0.45, t); T[CH.hR] += q; T[CH.hR + 1] += q * 0.7; T[CH.head + 2] += q * 0.5; };
  OGRE_ACTS = {
    idle: {},
    hold: { f: [K(0, {}), K(0.25, armedHold), K(1, armedHold)], rate: 8 },
    raise: { f: [K(0, armedHold), K(1, raised, 'io')], rate: 12 },
    chop: { f: [K(0, raised), K(0.22, chopped, 'snap'), K(1, chopped)], rate: 40 },
    roar: { f: [K(0, {}), K(0.2, { spine: [-0.2, 0, 0], chest: [-0.22, 0, 0], neck: [-0.1, 0, 0], head: [-0.55, 0, 0], jaw: [0.75, 0, 0],
      hR: [-1.85, 2.6, 0.55], bR: [0, 0.5, 1], pR: [-0.3, -1, -0.4], hL: [1.85, 2.6, 0.55], bL: [0, 0.5, 1], pL: [0.3, -1, -0.4], gL: 1,
      upL: [0, 0, 0.22], upR: [0, 0, -0.22], knL: [0.3, 0, 0], knR: [0.3, 0, 0] }, 'back'),
    K(0.8, { spine: [-0.18, 0, 0], chest: [-0.2, 0, 0], neck: [-0.1, 0, 0], head: [-0.5, 0, 0], jaw: [0.7, 0, 0],
      hR: [-1.85, 2.55, 0.55], bR: [0, 0.5, 1], pR: [-0.3, -1, -0.4], hL: [1.85, 2.55, 0.55], bL: [0, 0.5, 1], pL: [0.3, -1, -0.4], gL: 1,
      upL: [0, 0, 0.22], upR: [0, 0, -0.22], knL: [0.3, 0, 0], knR: [0.3, 0, 0] }), K(1, {})], rate: 14,
    post: (T, s, t, st) => { const q = Math.sin(st.time * 40) * 0.025 * sstep(0.15, 0.25, t) * (1 - sstep(0.8, 0.95, t)); T[CH.head] += q; T[CH.chest + 2] += q * 0.5; } },
    windup: { f: { over: hold3(wOver), R: hold3(wR), L: hold3(wL) }, rate: 14, post: tremble },
    slam: { f: [K(0, wOver), K(0.18, slamHit, 'snap'), K(0.6, slamHit), K(1, {})], rate: 36 },
    sweep: { f: { R: sweepR, L: sweepL, over: sweepR }, rate: 36 },
    stagger: { f: [K(0, {}), K(0.15, { hp: [0, 0, -0.35], spine: [-0.35, 0.2, 0.1], chest: [-0.2, 0, 0], head: [-0.6, 0.3, 0], hR: [-1.7, 3.0, -0.5], bR: [-0.2, 0.7, -0.5],
      hL: [1.6, 2.9, -0.3], upR: [0.5, 0, -0.2], knR: [0.35, 0, 0], upL: [-0.25, 0, 0.2] }, 'snap'),
    K(0.55, { spine: [0.45, -0.1, 0], chest: [0.25, 0, 0], head: [-0.3, 0, 0], hR: [-1.1, 1.1, 1.0], bR: [0, -0.6, 1], hL: [0.9, 1.2, 0.9], upL: [-0.5, 0, 0.15], knL: [0.9, 0, 0], upR: [0.1, 0, 0], knR: [0.6, 0, 0] }), K(1, {})], rate: 22 },
    hurt: { f: [K(0, {}), K(0.12, { spine: [-0.15, 0.15, 0], chest: [-0.12, 0, 0], head: [-0.4, 0.25, 0], jaw: [0.3, 0, 0], hR: [-1.45, 2.0, 0.2], hL: [1.4, 2.1, 0.1] }, 'snap'), K(1, {})], rate: 30 },
    dead: { f: [K(0, {}),
      K(0.25, { hp: [0, -0.68, 0.1], upL: [-0.1, 0, 0.15], knL: [1.6, 0, 0], upR: [-1.3, 0, -0.15], knR: [1.4, 0, 0], spine: [0.35, 0, 0], chest: [0.2, 0, 0], head: [-0.1, 0, 0], jaw: [0.3, 0, 0],
        hR: [-1.4, 0.9, 0.9], bR: [0, -0.8, 0.5], hL: [1.3, 0.9, 0.9] }, 'in'),
      K(0.62, lying, 'in'), K(0.7, { ...lying, hp: [0, -1.3, -0.95] }), K(0.78, lying), K(1, lying)],
    g: (t) => 1 - sstep(0.0, 0.2, t), rate: 20 },
  };
  return OGRE_ACTS;
}
const OGRE_GAIT = {
  idle: P({ spine: [0.1, 0, 0], chest: [0.12, 0, 0], neck: [0.22, 0, 0], head: [-0.3, 0, 0], jaw: [0.05, 0, 0],
    upL: [-0.1, 0, 0.1], knL: [0.28, 0, 0], upR: [0.06, 0, -0.1], knR: [0.22, 0, 0],
    hR: [-1.25, 1.75, 0.5], bR: [0.1, -0.25, 1], pR: [-1, 0.2, -0.6], hL: [1.2, 1.7, 0.35], bL: [0, 0, 1], pL: [1, 0.2, -0.6], gL: 0.55 }),
  runArms: P({ hR: [-1.3, 2.0, 0.1], hL: [1.3, 2.0, 0.2] }),
  air: P({ upL: [-0.6, 0, 0.1], knL: [1.0, 0, 0], upR: [-0.3, 0, -0.1], knR: [0.8, 0, 0] }),
  f0: 0.5, f1: 1.0, stride: 0.38, knee: 0.7, lean: 0.2, swing: 0.3, freeL: true, freeR: true,
  breath: 0.035, breathF: 0.22, sway: 0.07, scale: 4,
};

export function makeOgre() {
  const G = ogreGeo();
  const mats = { body: toonMat(), cloth: toonMat(THREE.DoubleSide) };
  const out = outlineMat(0.012, 0.05);
  const root = new THREE.Group(); root.name = 'ogre';
  const J = {};
  J.hips = jg(root, 'hips', 0, 2.1, 0);
  J.spine = jg(J.hips, 'spine', 0, 0.12, 0);
  J.chest = jg(J.spine, 'chest', 0, 0.75, 0);
  J.neck = jg(J.chest, 'neck', 0, 0.88, 0.1);
  J.head = jg(J.neck, 'head', 0, 0.12, 0.08);
  J.jaw = jg(J.head, 'jaw', 0, 0.16, 0.1);
  for (const [sd, x] of [['L', 0.42], ['R', -0.42]]) {
    J['up' + sd] = jg(J.hips, 'up' + sd, x, -0.1, 0);
    J['kn' + sd] = jg(J['up' + sd], 'kn' + sd, 0, -0.95, 0);
    J['an' + sd] = jg(J['kn' + sd], 'an' + sd, 0, -0.85, 0);
    mk(J['up' + sd], G.thigh, mats.body, out); mk(J['kn' + sd], G.shin, mats.body, out); mk(J['an' + sd], G.foot, mats.body, out);
  }
  mk(J.hips, G.pelvis, mats.body, out);
  const flapF = jg(J.hips, 'flapF', 0, 0.15, 0.5), flapB = jg(J.hips, 'flapB', 0, 0.15, -0.48);
  mk(flapF, G.flapF, mats.cloth, out); mk(flapB, G.flapB, mats.cloth, out);
  mk(J.spine, G.spine, mats.body, out); mk(J.chest, G.chest, mats.body, out);
  mk(J.head, G.head, mats.body, out); mk(J.jaw, G.jaw, mats.body, out);
  const eyes = new THREE.Mesh(G.eyes, SHARED.ogreEye); eyes.raycast = noRay; J.head.add(eyes);
  // arms: the LEFT upper arm is two segments so sever() can take everything below the middle
  const shR = jg(J.chest, 'shR', -0.92, 0.72, -0.05), elR = jg(shR, 'elR', 0, -(OG.a1 + OG.a2), 0), wrR = jg(elR, 'wrR', 0, -OG.b, 0);
  mk(shR, G.deltoid, mats.body, out); mk(shR, G.pauldron, mats.body, out); mk(shR, G.upperA, mats.body, out);
  const midR = jg(shR, 'midR', 0, -OG.a1, 0); mk(midR, G.upperB, mats.body, out);
  mk(elR, G.forearm, mats.body, out); mk(wrR, G.fistR, mats.body, out);
  const cleaver = jg(wrR, 'cleaver', 0.07, -0.22, 0); mk(cleaver, G.cleaver, mats.body, out);
  const shL = jg(J.chest, 'shL', 0.92, 0.72, -0.05);
  mk(shL, G.deltoid, mats.body, out); mk(shL, G.upperA, mats.body, out);
  const cutL = jg(shL, 'cutL', 0, -OG.a1, 0);
  const elL = jg(cutL, 'elL', 0, -OG.a2, 0), wrL = jg(elL, 'wrL', 0, -OG.b, 0);
  mk(cutL, G.upperB, mats.body, out); mk(elL, G.forearm, mats.body, out); mk(wrL, G.palmL, mats.body, out);
  const fingers = [];
  for (const z of FINGERS.z) {
    const js = []; let p = jg(wrL, 'finger', FINGERS.base[0], FINGERS.base[1], z);
    for (let i = 0; i < 3; i++) { if (i) p = jg(p, 'phal', 0, -FINGERS.L[i - 1], 0); js.push(p); mk(p, G.phal[i], mats.body, out); }
    fingers.push(js);
  }
  const thumbBase = jg(wrL, 'thumbBase', THUMB.base[0], THUMB.base[1], THUMB.base[2]); thumbBase.rotation.z = THUMB.rot;
  const thumb = []; { let p = jg(thumbBase, 'thumb'); for (let i = 0; i < 2; i++) { if (i) p = jg(p, 'thumb', 0, -THUMB.L[0], 0); thumb.push(p); mk(p, G.thumb[i], mats.body, out); } }
  const stumpCap = mk(jg(shL, 'stumpCapPiv', 0, -OG.a1, 0), G.stumpCap, mats.body, out); stumpCap.visible = false;
  const pieceCap = mk(cutL, G.stumpCap, mats.body, out); pieceCap.rotation.x = PI; pieceCap.visible = false;
  const grab = sock(wrL, 'grabHand', OG.grab[0], OG.grab[1], OG.grab[2]); grab.rotation.x = PI / 2;
  const sockets = {
    head: sock(J.head, 'head', 0, 0.28, 0.05), grabHand: grab, cleaverTip: sock(cleaver, 'cleaverTip', 0, -0.3, 1.68),
    stub: sock(shL, 'stub', 0, -OG.a1, 0), chest: sock(J.chest, 'chest', 0, 0.35, 0.55),
  };
  const rig = new Rig(root, J, { R: { sh: shR, el: elR, wr: wrR, a: OG.a1 + OG.a2, b: OG.b }, L: { sh: shL, el: elL, wr: wrL, a: OG.a1 + OG.a2, b: OG.b } });
  const st = { time: 0, ph: 0, flash: flasher([mats.body, mats.cloth]), severed: false };
  const A = { rig, st, acts: ogreActs(), G: OGRE_GAIT, dirOf: enemyDir };
  const HOLD = P(OGRE_HOLD);
  A.postAll = (T, s, t, st2, name) => {
    if (st.severed) { // one-armed: the stub hangs slightly forward and out
      T[CH.hL] = 1.45; T[CH.hL + 1] = 2.4; T[CH.hL + 2] = 0.5; T[CH.pL] = 1; T[CH.pL + 1] = 0; T[CH.pL + 2] = -0.5;
      if (name === 'stagger' || name === 'hurt' || name === 'roar') { T[CH.hL + 1] += 0.5; }
      return;
    }
    if (s.holding || name === 'hold') {
      for (const k of ['hL', 'bL', 'pL']) for (let i = 0; i < 3; i++) T[CH[k] + i] = HOLD.v[CH[k] + i];
      T[CH.gL] = 0.3 + 0.7 * clamp(s.squeeze ?? 1, 0, 1);
      if (name === 'idle') { T[CH.chest + 1] += 0.2; T[CH.head + 1] += 0.3; }
    }
  };
  A.after = (dt, s, t, name) => {
    const c = rig.cur;
    flapF.rotation.x = Math.min(0, Math.min(c[CH.upL], c[CH.upR])) * 0.85;
    flapB.rotation.x = Math.max(0, Math.max(c[CH.upL], c[CH.upR])) * 0.85 + 0.03;
    if (!st.severed) {
      const curl = clamp(c[CH.gL], 0, 1.1);
      for (const js of fingers) for (let i = 0; i < 3; i++) js[i].rotation.z = G.fingerCurl[i] * curl;
      for (let i = 0; i < 2; i++) thumb[i].rotation.z = G.thumbCurl[i] * curl;
    }
  };
  const pose = (dt, s = {}) => drive(A, dt, s);
  pose(0, {});
  const api = finishActor({ root, mats: [mats.body, mats.cloth], st, height: 4.6, radius: 1.5, sockets, pose,
    actions: ['idle', 'hold', 'raise', 'chop', 'roar', 'windup', 'slam', 'sweep', 'stagger', 'hurt', 'dead'],
    dirs: { windup: ['left', 'right', 'over'], sweep: ['left', 'right'] } });
  api.severed = false;
  // Removes the left arm from mid-upper-arm down as ONE Group. Its world transform is baked into
  // its local transform first, so scene.attach(piece) or scene.add(piece) both keep it in place.
  api.sever = () => {
    if (st.severed) return null;
    st.severed = true; api.severed = true;
    root.updateMatrixWorld(true);
    cutL.matrixWorld.decompose(cutL.position, cutL.quaternion, cutL.scale);
    cutL.removeFromParent();
    stumpCap.visible = true; pieceCap.visible = true;
    rig.skipArm.L = 'shoulder';
    api.severedArm = cutL;
    return cutL;
  };
  const baseDispose = api.dispose;
  api.dispose = () => { if (st.severed) cutL.removeFromParent(); baseDispose(); };
  return api;
}

// ------------------------------------------------------------------ VIEWMODEL
// Camera space: camera at the origin looking down -Z, +X = screen right, +Y = up.
let VM_ACTS = null;
function vmActs() {
  if (VM_ACTS) return VM_ACTS;
  const hold = (f) => [K(0, {}), K(0.1, f, 'snap'), K(0.78, f), K(1, {})];
  const low = { hR: [0.27, -0.6, -0.2], bR: [0.1, -0.3, -1], hL: [-0.3, -0.62, -0.2] };
  const sw = (a, m, e) => [K(0, {}), K(0.22, a), K(0.34, m, 'snap'), K(0.42, e, 'out'), K(0.8, e), K(1, {})];
  VM_ACTS = {
    idle: {},
    attack: { rate: 44, remap: PLAYER_ACTS_REMAP, f: {
      R: sw({ hR: [0.32, -0.08, -0.42], bR: [0.35, 0.9, -0.25], pR: [1, -1, 0.5] },
        { hR: [0.05, -0.08, -0.52], bR: [-0.35, 0.45, -0.82], pR: [0.6, -1, 0] },
        { hR: [-0.22, -0.2, -0.48], bR: [-0.95, -0.15, -0.25], pR: [0.3, -1, 0.3], hL: [-0.32, -0.4, -0.34] }),
      L: sw({ hR: [-0.08, -0.06, -0.42], bR: [-0.4, 0.9, -0.2], pR: [1, -0.6, 0.4] },
        { hR: [0.1, -0.09, -0.52], bR: [0.35, 0.45, -0.82], pR: [1, -1, 0] },
        { hR: [0.2, -0.2, -0.5], bR: [0.9, -0.25, -0.4], pR: [1, -1, 0.3] }),
      over: sw({ hR: [0.12, 0.1, -0.36], bR: [-0.02, 0.85, 0.45], pR: [1, -0.3, 0.3], hL: [-0.1, 0.02, -0.36] },
        { hR: [0.07, 0.0, -0.52], bR: [-0.03, 0.5, -0.85], pR: [1, -1, 0], hL: [-0.05, -0.08, -0.48] },
        { hR: [0.05, -0.26, -0.48], bR: [-0.05, -0.75, -0.65], pR: [1, -1, 0], hL: [-0.08, -0.32, -0.44] }),
    } },
    parry: { rate: 46, f: {
      over: hold({ hR: [0.26, 0.0, -0.44], bR: [-1, 0.08, -0.15], pR: [1, -1, 0.3], hL: [-0.2, 0.02, -0.46], bL: [0, 1, 0], pL: [-1, -1, 0.3] }),
      R: hold({ hR: [0.22, -0.22, -0.4], bR: [0.08, 1, -0.25], pR: [1, -1, 0.3], hL: [0.12, -0.06, -0.46], bL: [0, 1, 0] }),
      L: hold({ hR: [-0.12, -0.22, -0.42], bR: [-0.08, 1, -0.25], pR: [1, -0.5, 0.3], hL: [-0.22, -0.06, -0.46], bL: [0, 1, 0] }),
    } },
    dash: { rate: 36, f: hold({ hR: [0.3, -0.42, -0.12], bR: [0.25, -0.35, 1], pR: [1, -0.5, 0.5], hL: [-0.3, -0.4, -0.15] }) },
    hurt: { rate: 30, f: [K(0, {}), K(0.1, { hR: [0.28, -0.4, -0.3], bR: [0.1, 0.4, -1], hL: [-0.3, -0.45, -0.3] }, 'snap'), K(1, {})] },
    down: { rate: 10, f: [K(0, {}), K(0.2, low)] },
    dead: { rate: 10, f: [K(0, {}), K(0.2, low)] },
    getup: { rate: 12, f: [K(0, low), K(0.7, { hR: [0.2, -0.45, -0.35], bR: [0, -1, -0.2], hL: [-0.25, -0.5, -0.35] }), K(1, {})] },
  };
  return VM_ACTS;
}
function PLAYER_ACTS_REMAP(t, s) { const h = clamp(s.hit ?? 0.36, 0.05, 0.95); return t < h ? t / h * 0.36 : 0.36 + (t - h) / (1 - h) * 0.64; }
const VM_GAIT = {
  idle: P({ hR: [0.17, -0.21, -0.4], bR: [-0.32, 0.72, -0.6], pR: [1, -1, 0.4], hL: [-0.26, -0.37, -0.4], bL: [0.3, 0.2, -1], pL: [-1, -1, 0.4] }),
  f0: 1.0, f1: 2.2, stride: 0, knee: 0, lean: 0, swing: 0, breath: 0, breathF: 0.25, scale: 0,
};

export function makeViewmodel() {
  const G = playerGeo();
  const mats = { body: toonMat() };
  const M = { edge: new THREE.MeshBasicMaterial({ color: 0xdfe6ee, fog: false }) };
  const out = outlineMat(0.0009, 0.0035, 0.0028);
  const veins = veinMats(); veins.meshes = [];
  const group = new THREE.Group(); group.name = 'viewmodel';
  const { arms, tip, kat } = buildSwordArms(group, null, G, { body: mats.body, edge: M.edge }, out, veins, [0.27, -0.42, 0.04], [-0.27, -0.42, 0.04]);
  kat.position.z += 0.12; // grip nearer the pommel so the handle does not fill the screen
  group.traverse((o) => { if (o.isMesh) { o.castShadow = false; o.receiveShadow = false; o.frustumCulled = false; } });
  const rig = { base: new Float32Array(NC), tgt: new Float32Array(NC), cur: new Float32Array(NC), fresh: true, arms, skipArm: {} };
  rig.step = Rig.prototype.step; rig.solveArms = Rig.prototype.solveArms;
  const st = { time: 0, ph: 0, flash: flasher([mats.body]), aura: 0 };
  const acts = vmActs(), qI = new THREE.Quaternion();
  const pose = (dt, s = {}) => {
    dt = Math.max(0, Math.min(dt || 0, 0.1)); st.time += dt;
    const name = s.action || 'idle', def = acts[name] || acts.idle;
    let t = clamp(s.t ?? 0, 0, 1); if (def.remap) t = def.remap(t, s);
    const b = rig.base; b.set(VM_GAIT.idle.v);
    const mv = clamp(s.move || 0, 0, 1);
    if (mv > 0.01) st.ph += dt * (1.0 + 1.3 * mv) * TAU;
    const bob = Math.sin(st.ph * 2) * 0.012 * mv, sway = Math.sin(st.ph) * 0.01 * mv, br = Math.sin(st.time * 1.6) * 0.004;
    b[CH.hR + 1] += bob + br; b[CH.hL + 1] += bob + br * 0.8; b[CH.hR] += sway; b[CH.hL] += sway;
    if (s.air) { b[CH.hR + 1] += 0.03; b[CH.hL + 1] += 0.04; }
    let frames = def.f;
    if (frames && !Array.isArray(frames)) frames = frames[playerDir(s)] || frames.over;
    if (frames) sample(frames, t, b, rig.tgt); else rig.tgt.set(b);
    rig.step(dt, def.rate || 16);
    rig.solveArms(null, qI);
    veins.u.uTime.value = st.time;
    st.flash.tick(dt);
  };
  pose(0, {});
  return {
    group, pose, sockets: { swordTip: tip, forearmR: sock(arms.R.el, 'forearmR', 0, -0.13, 0) },
    setAura: (L) => { st.aura = clamp(L, 0, 1); swordAura(L, M, veins); },
    flash: (c, s) => st.flash.flash(c, s),
    actions: ['idle', 'attack', 'parry', 'dash', 'hurt', 'down', 'getup', 'dead'],
    dirs: { attack: ['left', 'right', 'over'], parry: ['left', 'right', 'over'] },
    dispose() { group.removeFromParent(); for (const m of [mats.body, M.edge, veins.core, veins.shell]) m.dispose(); },
  };
}
