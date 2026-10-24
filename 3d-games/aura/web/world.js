// AURA — world.js
// The dusk forest road + clearing (buildWorld) and the night training courtyard (buildDojo).
// Not one asset file: every texture is painted in code, every mesh is built from primitives.
//
// Draw calls, world: sky, ground, conifers, dead trees, grass, rocks, hide rock, props, fireflies
// = 9 colour + 5 shadow casters. Dojo is ~13.
import * as THREE from './vendor/three/three.module.js';

const ARENA = 26;
const V3 = (x, y, z) => new THREE.Vector3(x, y, z);

// ------------------------------------------------------------------ noise (JS side)
function mulberry32(a) {
  return function () {
    a |= 0; a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
function hash2(x, y) {
  let h = (Math.imul(x | 0, 374761393) + Math.imul(y | 0, 668265263)) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
}
function vnoise(x, y) {
  const ix = Math.floor(x), iy = Math.floor(y);
  let fx = x - ix, fy = y - iy;
  fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy);
  const a = hash2(ix, iy), b = hash2(ix + 1, iy), c = hash2(ix, iy + 1), d = hash2(ix + 1, iy + 1);
  return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy;
}
function fbm(x, y, oct = 4) {
  let s = 0, a = 0.5, n = 0;
  for (let i = 0; i < oct; i++) { s += a * vnoise(x, y); n += a; x = x * 2.03 + 17.1; y = y * 2.03 + 9.7; a *= 0.5; }
  return s / n;
}
const clamp01 = (x) => (x < 0 ? 0 : x > 1 ? 1 : x);
const smooth = (a, b, x) => { const t = clamp01((x - a) / (b - a)); return t * t * (3 - 2 * t); };

/** Centre-line of the dirt road (x as a function of z). Mirrored in the ground shader. */
export function roadX(z) { return 2.5 * Math.sin(z * 0.021 + 0.6) + 1.2 * Math.sin(z * 0.057); }

function heightAt(x, z) {
  const r = Math.hypot(x, z);
  if (r < ARENA) return 0;
  const s = smooth(ARENA, ARENA + 16, r);
  const hills = fbm(x * 0.016 + 40, z * 0.016 - 12, 4);
  let h = (0.5 + hills * 9 + Math.max(0, r - 42) * 0.05) * s;
  const d = Math.abs(x - roadX(z));
  h *= 0.22 + 0.78 * smooth(3.5, 16, d);
  return h;
}

// ------------------------------------------------------------------ geometry helpers
/** Deterministic vertex jitter: duplicates at the same spot move together, so no cracks. */
function jitter(g, amt, seed = 1, keepBottom = false) {
  const P = g.attributes.position;
  for (let i = 0; i < P.count; i++) {
    const x = P.getX(i), y = P.getY(i), z = P.getZ(i);
    if (keepBottom && y < -0.49) continue;
    const kx = Math.round(x * 1000), ky = Math.round(y * 1000), kz = Math.round(z * 1000);
    const h1 = hash2(kx + seed * 131, ky * 7 + kz * 13);
    const h2 = hash2(ky + seed * 71, kz * 3 + kx * 11);
    const h3 = hash2(kz + seed * 37, kx * 5 + ky * 17);
    P.setXYZ(i, x + (h1 - 0.5) * amt, y + (h2 - 0.5) * amt, z + (h3 - 0.5) * amt);
  }
  P.needsUpdate = true;
  g.computeVertexNormals();
  return g;
}

const _m4 = new THREE.Matrix4(), _q = new THREE.Quaternion(), _e = new THREE.Euler(), _s = new THREE.Vector3(), _p = new THREE.Vector3();
function mat(x, y, z, rx = 0, ry = 0, rz = 0, sx = 1, sy = sx, sz = sx) {
  return new THREE.Matrix4().compose(_p.set(x, y, z), _q.setFromEuler(_e.set(rx, ry, rz)), _s.set(sx, sy, sz));
}

/**
 * Merge primitive parts into one non-indexed geometry with vertex colours.
 * parts: [{ g, m?: Matrix4, c: hex|Color, shade?: (x,y,z,nx,ny,nz)=>multiplier }]
 */
/** non-indexed geometry -> per-face normals: the faceted toon look (MeshToonMaterial has no flatShading) */
function flatten(g) { g.computeVertexNormals(); return g; }

function bake(parts) {
  let n = 0;
  const gs = parts.map((p) => {
    let g = p.g.index ? p.g.toNonIndexed() : p.g.clone();
    if (!g.attributes.normal) g.computeVertexNormals();
    if (p.m) g.applyMatrix4(p.m);
    n += g.attributes.position.count;
    return g;
  });
  const pos = new Float32Array(n * 3), nor = new Float32Array(n * 3), col = new Float32Array(n * 3), uvs = new Float32Array(n * 2);
  const c = new THREE.Color();
  let o = 0;
  gs.forEach((g, i) => {
    const P = g.attributes.position, N = g.attributes.normal, U = g.attributes.uv, part = parts[i];
    c.set(part.c);
    for (let k = 0; k < P.count; k++, o++) {
      const x = P.getX(k), y = P.getY(k), z = P.getZ(k);
      const nx = N.getX(k), ny = N.getY(k), nz = N.getZ(k);
      pos[o * 3] = x; pos[o * 3 + 1] = y; pos[o * 3 + 2] = z;
      nor[o * 3] = nx; nor[o * 3 + 1] = ny; nor[o * 3 + 2] = nz;
      const f = part.shade ? part.shade(x, y, z, nx, ny, nz) : 1;
      col[o * 3] = c.r * f; col[o * 3 + 1] = c.g * f; col[o * 3 + 2] = c.b * f;
      if (U) { uvs[o * 2] = U.getX(k); uvs[o * 2 + 1] = U.getY(k); }
    }
    g.dispose();
  });
  const out = new THREE.BufferGeometry();
  out.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  out.setAttribute('normal', new THREE.BufferAttribute(nor, 3));
  out.setAttribute('color', new THREE.BufferAttribute(col, 3));
  out.setAttribute('uv', new THREE.BufferAttribute(uvs, 2));
  out.computeBoundingSphere();
  return out;
}

let _grad = null;
function toonGradient() {
  if (_grad) return _grad;
  const d = new Uint8Array([84, 150, 214, 255]);
  _grad = new THREE.DataTexture(d, 4, 1, THREE.RedFormat);
  _grad.minFilter = _grad.magFilter = THREE.NearestFilter;
  _grad.generateMipmaps = false;
  _grad.needsUpdate = true;
  return _grad;
}
function toon(opts = {}) {
  const { flatShading, ...rest } = opts; // eslint-disable-line no-unused-vars
  return new THREE.MeshToonMaterial({ gradientMap: toonGradient(), vertexColors: true, ...rest });
}

// ------------------------------------------------------------------ shared GLSL noise
const GLSL_NOISE = /* glsl */`
float aHash12(vec2 p){ vec3 p3 = fract(vec3(p.xyx) * .1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
float aNoise(vec2 p){ vec2 i = floor(p), f = fract(p); f = f*f*(3.-2.*f);
  float a = aHash12(i), b = aHash12(i+vec2(1.,0.)), c = aHash12(i+vec2(0.,1.)), d = aHash12(i+vec2(1.,1.));
  return mix(mix(a,b,f.x), mix(c,d,f.x), f.y); }
float aFbm(vec2 p){ float s = 0., a = .5; for(int i=0;i<4;i++){ s += a*aNoise(p); p = p*2.03 + 17.1; a *= .5; } return s / .9375; }
`;

// ------------------------------------------------------------------ sky dome
function makeSky(o) {
  const u = {
    uTop: { value: new THREE.Color(o.top) },
    uMid: { value: new THREE.Color(o.mid) },
    uHorizon: { value: new THREE.Color(o.horizon) },
    uRidgeFar: { value: new THREE.Color(o.ridgeFar) },
    uRidgeNear: { value: new THREE.Color(o.ridgeNear) },
    uSunDir: { value: o.sunDir.clone().normalize() },
    uSunCol: { value: new THREE.Color(o.sunCol) },
    uMoonDir: { value: o.moonDir.clone().normalize() },
    uMoonCol: { value: new THREE.Color(o.moonCol) },
    uMoonR: { value: o.moonR },
    uGlow: { value: new THREE.Color(0, 0, 0) },
    uStars: { value: o.stars },
    uRidgeAmt: { value: o.ridge ?? 1 },
    uTime: { value: 0 },
  };
  const m = new THREE.ShaderMaterial({
    uniforms: u,
    side: THREE.BackSide,
    depthTest: false,
    depthWrite: false,
    fog: false,
    vertexShader: /* glsl */`
      varying vec3 vDir;
      void main(){
        vDir = position;
        vec4 p = projectionMatrix * vec4(mat3(viewMatrix) * position, 1.0);
        gl_Position = p.xyww;
      }`,
    fragmentShader: /* glsl */`
      uniform vec3 uTop, uMid, uHorizon, uRidgeFar, uRidgeNear, uSunDir, uSunCol, uMoonDir, uMoonCol, uGlow;
      uniform float uMoonR, uStars, uRidgeAmt, uTime;
      varying vec3 vDir;
      ${GLSL_NOISE}
      vec3 h33(vec3 p){ p = fract(p * vec3(.1031,.1030,.0973)); p += dot(p, p.yxz + 33.33); return fract((p.xxy + p.yxx) * p.zyx); }
      void main(){
        vec3 d = normalize(vDir);
        float e = d.y;
        vec3 col = mix(uHorizon, uMid, smoothstep(0.0, 0.30, e));
        col = mix(col, uTop, smoothstep(0.16, 0.85, e));

        // setting sun: wide warm glow hugging the horizon, a tighter bloom, no hard disc (it is behind the hills)
        float sd = max(dot(d, uSunDir), 0.);
        float band = exp(-max(e, 0.) * 7.);
        col += uSunCol * (pow(sd, 4.) * 0.55 * band + pow(sd, 30.) * 0.55 + pow(sd, 400.) * 1.2);

        // stars
        if (e > 0.02) {
          vec3 p = d * 190.;
          vec3 c = floor(p), f = fract(p);
          vec3 h = h33(c);
          if (h.x > 0.972) {
            vec3 sp = h.yzx * 0.7 + 0.15;
            float s = smoothstep(0.16, 0.0, length(f - sp));
            float tw = 0.6 + 0.4 * sin(uTime * (1.3 + h.y * 3.) + h.z * 40.);
            col += vec3(0.75, 0.8, 1.0) * s * tw * (0.5 + 1.8 * h.y * h.y) * smoothstep(0.06, 0.5, e) * uStars;
          }
        }

        // moon: pale disc, maria, limb darkening and a wide soft halo
        float md = dot(d, uMoonDir);
        if (md > 0.) {
          vec3 mt = normalize(cross(uMoonDir, vec3(0., 1., 0.)));
          vec3 mb = cross(mt, uMoonDir);
          vec2 mq = vec2(dot(d, mt), dot(d, mb)) / uMoonR;
          float mr = length(mq);
          float aa = fwidth(mr) * 1.5;
          float disc = 1. - smoothstep(1. - aa, 1. + aa, mr);
          float mare = smoothstep(0.42, 0.72, aFbm(mq * 1.35 + 5.3));
          float crat = aNoise(mq * 9. + 2.) * 0.5 + aNoise(mq * 23.) * 0.5;
          float limb = 0.72 + 0.28 * sqrt(max(0., 1. - mr * mr));
          vec3 mc = uMoonCol * (1. - 0.30 * mare - 0.10 * crat) * limb;
          float out1 = max(mr - 1., 0.);
          col += uMoonCol * (exp(-out1 * 5.) * 0.22 + exp(-out1 * 0.7) * 0.05) * (1. - disc);
          col = mix(col, mc, disc);
        }

        // two ridge lines of far hills, periodic in azimuth (noise sampled on a circle)
        float az = atan(d.z, d.x);
        vec2 cz = vec2(cos(az), sin(az));
        float rf = 0.020 + 0.060 * aNoise(cz * 2.3 + 7.) + 0.020 * aNoise(cz * 8. + 1.) + 0.006 * aNoise(cz * 30.);
        float rn = 0.004 + 0.034 * aNoise(cz * 3.4 + 19.) + 0.012 * aNoise(cz * 12. + 4.) + 0.005 * aNoise(cz * 44. + 9.);
        float aa2 = fwidth(e) * 1.2 + 0.0004;
        float far = 1. - smoothstep(rf - aa2, rf + aa2, e);
        float near = 1. - smoothstep(rn - aa2, rn + aa2, e);
        vec3 rfc = mix(uRidgeFar, uHorizon, smoothstep(rf, -0.01, e) * 0.55);
        col = mix(col, rfc, far * uRidgeAmt);
        vec3 rnc = mix(uRidgeNear, uHorizon, smoothstep(rn, -0.02, e) * 0.5);
        col = mix(col, rnc, near * uRidgeAmt);

        // awakened: a thin electric haze along the horizon
        col += uGlow * (exp(-abs(e - rf) * 60.) * 0.5 + exp(-max(e, 0.) * 10.) * 0.35);

        if (e < 0.) col = mix(col, uHorizon, smoothstep(0., -0.05, e));
        gl_FragColor = vec4(col, 1.);
      }`,
  });
  const mesh = new THREE.Mesh(new THREE.SphereGeometry(10, 48, 24), m);
  mesh.frustumCulled = false;
  mesh.renderOrder = -1000;
  mesh.name = 'sky';
  return { mesh, u };
}

// ------------------------------------------------------------------ vegetation geometry
function coniferGeometry(seed) {
  const parts = [];
  // trunk
  parts.push({ g: new THREE.CylinderGeometry(0.035, 0.06, 0.34, 6), m: mat(0, 0.12, 0), c: 0x3a2a24 });
  const tiers = 4;
  for (let i = 0; i < tiers; i++) {
    const f = i / (tiers - 1);
    const r = 0.30 - f * 0.18;
    const h = 0.34 - f * 0.06;
    const y = 0.20 + i * 0.19;
    const g = jitter(new THREE.ConeGeometry(r, h, 8, 1), 0.035, seed * 10 + i);
    // droop the skirt: alternate rim vertices drop
    const P = g.attributes.position;
    for (let k = 0; k < P.count; k++) {
      if (P.getY(k) < -h * 0.45) {
        const a = Math.atan2(P.getZ(k), P.getX(k));
        P.setY(k, P.getY(k) - 0.03 * (0.5 + 0.5 * Math.sin(a * 4 + i + seed)));
      }
    }
    P.needsUpdate = true;
    const base = [0x17302b, 0x1b3730, 0x214035, 0x284a3a][i];
    parts.push({
      g, m: mat(0, y + h / 2, 0, 0, i * 0.7 + seed, 0), c: base,
      shade: (x, yy) => 0.78 + 0.45 * clamp01((yy - y) / h), // lighter toward each tier's tip
    });
  }
  return flatten(bake(parts));
}

function deadTreeGeometry() {
  const parts = [];
  const bark = 0x4b403d;
  parts.push({ g: jitter(new THREE.CylinderGeometry(0.018, 0.06, 1.0, 6, 4), 0.012, 3), m: mat(0, 0.5, 0, 0, 0, 0.04), c: bark });
  const br = [
    [0.52, 0.9, 0.0, 0.34], [0.62, -0.8, 1.9, 0.28], [0.72, 0.7, 3.4, 0.22], [0.40, -0.9, 4.6, 0.3], [0.82, 0.5, 5.6, 0.16],
  ];
  for (const [y, tilt, yaw, len] of br) {
    const g = new THREE.CylinderGeometry(0.006, 0.02, len, 5);
    const m = new THREE.Matrix4().makeTranslation(0, len / 2, 0);
    const rot = new THREE.Matrix4().makeRotationFromEuler(new THREE.Euler(0, yaw, tilt, 'YXZ'));
    const t = new THREE.Matrix4().makeTranslation(0, y, 0);
    parts.push({ g, m: t.multiply(rot).multiply(m), c: bark, shade: () => 0.9 });
  }
  return flatten(bake(parts));
}

function grassGeometry() {
  // five bent blades, normals pointing up so a tuft lights like the ground it grows from
  const pos = [], col = [];
  const base = new THREE.Color(0x1f4436), tip = new THREE.Color(0x6a9a64);
  const rnd = mulberry32(7);
  for (let b = 0; b < 6; b++) {
    const a = (b / 6) * Math.PI * 2 + rnd() * 0.6;
    const h = 0.3 + rnd() * 0.3, w = 0.03 + rnd() * 0.02, lean = 0.08 + rnd() * 0.16;
    const ox = Math.cos(a) * 0.05, oz = Math.sin(a) * 0.05;
    const dx = Math.cos(a) * lean, dz = Math.sin(a) * lean;
    const px = -Math.sin(a) * w, pz = Math.cos(a) * w;
    // two segments: base quad-ish then tip
    const mx = ox + dx * 0.35, mz = oz + dz * 0.35, my = h * 0.55;
    const tx = ox + dx, tz = oz + dz;
    const v = [
      [ox - px, 0, oz - pz], [ox + px, 0, oz + pz], [mx + px * 0.7, my, mz + pz * 0.7],
      [ox - px, 0, oz - pz], [mx + px * 0.7, my, mz + pz * 0.7], [mx - px * 0.7, my, mz - pz * 0.7],
      [mx - px * 0.7, my, mz - pz * 0.7], [mx + px * 0.7, my, mz + pz * 0.7], [tx, h, tz],
    ];
    for (const p of v) {
      pos.push(p[0], p[1], p[2]);
      const t = p[1] / h;
      col.push(base.r + (tip.r - base.r) * t, base.g + (tip.g - base.g) * t, base.b + (tip.b - base.b) * t);
    }
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('color', new THREE.Float32BufferAttribute(col, 3));
  const n = new Float32Array(pos.length);
  for (let i = 1; i < n.length; i += 3) n[i] = 1;
  g.setAttribute('normal', new THREE.BufferAttribute(n, 3));
  return g;
}

function rockGeometry(seed, detail = 1) {
  const g = jitter(new THREE.IcosahedronGeometry(1, detail), 0.42, seed);
  const P = g.attributes.position;
  for (let i = 0; i < P.count; i++) { const y = P.getY(i); P.setY(i, y < -0.2 ? -0.2 + (y + 0.2) * 0.3 : y); }
  g.computeVertexNormals();
  return flatten(bake([{ g, c: 0x6d6778 }]));
}

function tintMoss(geo, moss, rockC, thresh = 0.5) {
  const C = geo.attributes.color, N = geo.attributes.normal;
  const a = new THREE.Color(moss), b = new THREE.Color(rockC);
  for (let i = 0; i < C.count; i++) {
    const t = smooth(thresh, thresh + 0.3, N.getY(i));
    const f = 0.85 + 0.3 * hash2(i, 5);
    C.setXYZ(i, (b.r + (a.r - b.r) * t) * f, (b.g + (a.g - b.g) * t) * f, (b.b + (a.b - b.b) * t) * f);
  }
  C.needsUpdate = true;
  return geo;
}

// ------------------------------------------------------------------ fireflies
function makeFireflies(count, place, colorHex) {
  const pos = new Float32Array(count * 3), seed = new Float32Array(count);
  const rnd = mulberry32(99);
  for (let i = 0; i < count; i++) {
    const p = place(rnd);
    pos[i * 3] = p.x; pos[i * 3 + 1] = p.y; pos[i * 3 + 2] = p.z;
    seed[i] = rnd();
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  g.setAttribute('aSeed', new THREE.BufferAttribute(seed, 1));
  const u = { uTime: { value: 0 }, uScale: { value: 600 }, uCol: { value: new THREE.Color(colorHex).multiplyScalar(3.2) }, uSize: { value: 0.09 } };
  const m = new THREE.ShaderMaterial({
    uniforms: u, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, fog: false,
    vertexShader: /* glsl */`
      attribute float aSeed; uniform float uTime, uScale, uSize; varying float vA;
      void main(){
        vec3 p = position;
        float t = uTime * 0.35 + aSeed * 10.;
        p.x += sin(t * 1.3 + aSeed * 7.) * 1.3; p.y += sin(t * 1.7 + aSeed * 3.) * 0.45; p.z += cos(t * 1.1 + aSeed * 5.) * 1.3;
        vec4 mv = modelViewMatrix * vec4(p, 1.);
        float blink = pow(0.5 + 0.5 * sin(uTime * (0.9 + aSeed * 1.4) + aSeed * 40.), 4.);
        vA = (0.08 + 0.92 * blink) * smoothstep(110., 25., -mv.z);
        gl_PointSize = max(uSize * uScale / -mv.z * (0.7 + 0.6 * blink), 1.5);
        gl_Position = projectionMatrix * mv;
      }`,
    fragmentShader: /* glsl */`
      uniform vec3 uCol; varying float vA;
      void main(){
        float d = length(gl_PointCoord - .5) * 2.;
        float a = exp(-d * d * 4.) + exp(-d * d * 40.) * 0.8;
        gl_FragColor = vec4(uCol * a * vA + vec3(a * a * vA), 1.);
      }`,
  });
  const pts = new THREE.Points(g, m);
  pts.frustumCulled = false;
  const vs = new THREE.Vector2();
  pts.onBeforeRender = (r, s, cam) => { r.getDrawingBufferSize(vs); u.uScale.value = cam.projectionMatrix.elements[5] * vs.y * 0.5; };
  return { pts, u };
}

// ------------------------------------------------------------------ canvas painting
function canvasTex(w, h, paint, srgb = true) {
  const c = document.createElement('canvas');
  c.width = w; c.height = h;
  const g = c.getContext('2d');
  paint(g, w, h);
  const t = new THREE.CanvasTexture(c);
  if (srgb) t.colorSpace = THREE.SRGBColorSpace;
  t.anisotropy = 8;
  return t;
}

// =================================================================== WORLD
export function buildWorld(scene, renderer) {
  if (renderer) {
    renderer.shadowMap.enabled = true;
    renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  }

  // ---- palettes (sRGB hex), lerped by awaken
  const DUSK = {
    top: 0x161a42, mid: 0x5a3470, horizon: 0xb4707e, ridgeFar: 0x9c6883, ridgeNear: 0x7a5274,
    sun: 0xff9a60, sunLight: 0xffb485, sunI: 2.7, hemiSky: 0x8a78c0, hemiGround: 0x3c2c34, hemiI: 1.0,
    rimI: 0, fly: 0xd8ff7a, stars: 0.55, moon: 0xf2ecff,
  };
  const AWAKE = {
    top: 0x010209, mid: 0x04112a, horizon: 0x0c2f45, ridgeFar: 0x0a2236, ridgeNear: 0x06111f,
    sun: 0x2a6f9a, sunLight: 0xa8dcff, sunI: 1.25, hemiSky: 0x3a6fa8, hemiGround: 0x05070d, hemiI: 1.0,
    rimI: 3.2, fly: 0x4fdcff, stars: 1.3, moon: 0xd8f6ff,
  };
  const cA = {}, cB = {};
  for (const k of Object.keys(DUSK)) if (typeof DUSK[k] === 'number' && DUSK[k] > 16) { cA[k] = new THREE.Color(DUSK[k]); cB[k] = new THREE.Color(AWAKE[k]); }
  const tmpC = new THREE.Color();
  const lerpC = (k, t, out) => out.copy(cA[k]).lerp(cB[k], t);

  const sunLightDir = V3(-0.52, 0.5, 0.69).normalize();
  const sunSkyDir = V3(-0.6, 0.03, 0.8).normalize();
  const moonDir = V3(0.3, 0.3, 0.9).normalize();

  // ---- sky
  const sky = makeSky({
    top: DUSK.top, mid: DUSK.mid, horizon: DUSK.horizon, ridgeFar: DUSK.ridgeFar, ridgeNear: DUSK.ridgeNear,
    sunDir: sunSkyDir, sunCol: DUSK.sun, moonDir, moonCol: DUSK.moon, moonR: 0.085, stars: DUSK.stars,
  });
  sky.u.uMoonCol.value.multiplyScalar(0.62);
  scene.add(sky.mesh);

  const fog = new THREE.Fog(DUSK.horizon, 24, 165);
  scene.fog = fog;
  scene.background = fog.color;

  // ---- lights
  const hemi = new THREE.HemisphereLight(DUSK.hemiSky, DUSK.hemiGround, DUSK.hemiI);
  scene.add(hemi);
  const sun = new THREE.DirectionalLight(DUSK.sunLight, DUSK.sunI);
  const sunOffset = sunLightDir.clone().multiplyScalar(70);
  sun.position.copy(sunOffset);
  sun.castShadow = true;
  sun.shadow.mapSize.set(2048, 2048);
  const sc = sun.shadow.camera;
  sc.left = -26; sc.right = 26; sc.top = 26; sc.bottom = -26; sc.near = 5; sc.far = 150;
  sun.shadow.bias = -0.0006;
  sun.shadow.normalBias = 0.035;
  sun.shadow.radius = 2;
  sun.name = 'sun';
  scene.add(sun);
  scene.add(sun.target);
  sun.userData.offset = sunOffset;

  // cyan rim light from behind the default view, only on after the Awakening
  const rim = new THREE.DirectionalLight(0x4fdcff, 0);
  rim.position.set(0.35, 0.45, -1).multiplyScalar(50);
  scene.add(rim);
  scene.add(rim.target);

  // ---- ground: one big heightfield, colour computed per pixel from world position
  const G = 480, SEG = 240;
  const gg = new THREE.PlaneGeometry(G, G, SEG, SEG);
  gg.rotateX(-Math.PI / 2);
  {
    const P = gg.attributes.position;
    for (let i = 0; i < P.count; i++) P.setY(i, heightAt(P.getX(i), P.getZ(i)));
    gg.computeVertexNormals();
  }
  const groundU = {
    uGrassA: { value: new THREE.Color(0x1d4a3a) },
    uGrassB: { value: new THREE.Color(0x44703e) },
    uDirt: { value: new THREE.Color(0x75685c) },
    uDirtDark: { value: new THREE.Color(0x3e3632) },
  };
  const groundMat = new THREE.MeshLambertMaterial({ color: 0xffffff });
  groundMat.onBeforeCompile = (sh) => {
    Object.assign(sh.uniforms, groundU);
    sh.vertexShader = sh.vertexShader
      .replace('#include <common>', '#include <common>\nvarying vec3 vGW;')
      .replace('#include <begin_vertex>', '#include <begin_vertex>\nvGW = (modelMatrix * vec4(transformed, 1.0)).xyz;');
    sh.fragmentShader = sh.fragmentShader
      .replace('#include <common>', `#include <common>
        varying vec3 vGW;
        uniform vec3 uGrassA, uGrassB, uDirt, uDirtDark;
        ${GLSL_NOISE}
        float roadX(float z){ return 2.5 * sin(z * 0.021 + 0.6) + 1.2 * sin(z * 0.057); }`)
      .replace('#include <map_fragment>', /* glsl */`
        vec2 w = vGW.xz;
        float big = aFbm(w * 0.045);
        float mid = aFbm(w * 0.33 + 11.);
        float fine = aNoise(w * 3.3) * 0.55 + aNoise(w * 11.7) * 0.45;
        vec3 gcol = mix(uGrassA, uGrassB, smoothstep(0.32, 0.72, big * 0.65 + mid * 0.35));
        gcol *= 0.82 + 0.34 * fine;
        float rr = length(w);
        // trampled clearing: worn patches, more of them toward the middle
        float wornN = aFbm(w * 0.11 + 3.) + (1. - smoothstep(0., 22., rr)) * 0.22;
        float worn = (1. - smoothstep(12., 25., rr)) * smoothstep(0.5, 0.72, wornN);
        gcol = mix(gcol, mix(uDirtDark, uDirt, fine), worn * 0.45);
        // the road
        float dx = abs(w.x - roadX(w.y));
        float hw = 2.5 + (aNoise(vec2(w.y * 0.12, 1.7)) - 0.5) * 1.1;
        float edgeN = (aNoise(w * 1.4) - 0.5) * 0.9 + (aNoise(w * 5.) - 0.5) * 0.3;
        float road = 1. - smoothstep(hw - 0.35, hw + 0.45, dx + edgeN);
        vec3 rc = mix(uDirtDark, uDirt, 0.55 + 0.45 * mid) * (0.86 + 0.28 * fine);
        float ruts = smoothstep(0.32, 0.0, abs(dx - 1.05)) * 0.28;
        float crown = smoothstep(0.6, 0.0, dx) * aNoise(w * vec2(0.8, 0.25)) * 0.5;
        rc *= 1. - ruts;
        rc = mix(rc, gcol * 0.9, crown * 0.6);  // a stripe of grass down the middle
        float peb = smoothstep(0.9, 0.97, aNoise(w * 13.1)) * road;
        rc = mix(rc, vec3(0.30, 0.29, 0.32), peb * 0.45);
        vec3 fcol = mix(gcol, rc, road);
        diffuseColor.rgb *= fcol;
      `);
  };
  const ground = new THREE.Mesh(gg, groundMat);
  ground.receiveShadow = true;
  ground.name = 'ground';
  scene.add(ground);

  // ---- placement helpers
  const rnd = mulberry32(1337);
  const spots = { start: V3(0, 0, -20), sisterHide: V3(-8, 0, 7), ogreEntry: V3(7, 0, 26), ogreHold: V3(3, 0, 10) };
  const offRoad = (x, z, pad) => Math.abs(x - roadX(z)) > pad;

  // ---- conifers (one instanced mesh, 2 geometry variants would cost a call; one reads fine with per-instance scale)
  const conGeo = coniferGeometry(1.3);
  const treeMat = toon({ flatShading: true });
  const NCON = 560;
  const conifers = new THREE.InstancedMesh(conGeo, treeMat, NCON);
  conifers.castShadow = true; conifers.receiveShadow = true;
  const dummy = new THREE.Object3D();
  const cc = new THREE.Color();
  const treePts = [];
  let placed = 0, guard = 0;
  while (placed < NCON && guard++ < 20000) {
    const a = rnd() * Math.PI * 2;
    // density: a wall of trees just outside the clearing, thinning out, then far silhouettes
    const u = rnd();
    const r = u < 0.45 ? 29 + rnd() * 22 : u < 0.85 ? 50 + rnd() * 60 : 110 + rnd() * 90;
    const x = Math.cos(a) * r, z = Math.sin(a) * r;
    if (!offRoad(x, z, 6 + r * 0.02)) continue;
    let ok = true;
    for (let k = Math.max(0, treePts.length - 60); k < treePts.length; k++) {
      const t = treePts[k];
      if ((t[0] - x) ** 2 + (t[1] - z) ** 2 < 5) { ok = false; break; }
    }
    if (!ok) continue;
    treePts.push([x, z]);
    const hgt = 8 + rnd() * 9 + (r > 60 ? 3 : 0);
    const w = hgt * (0.9 + rnd() * 0.35);
    dummy.position.set(x, heightAt(x, z) - 0.25, z);
    dummy.rotation.set((rnd() - 0.5) * 0.06, rnd() * 6.28, (rnd() - 0.5) * 0.06);
    dummy.scale.set(w, hgt, w);
    dummy.updateMatrix();
    conifers.setMatrixAt(placed, dummy.matrix);
    const v = 0.8 + rnd() * 0.35;
    cc.setRGB(v * (0.9 + rnd() * 0.12), v, v * (0.92 + rnd() * 0.16));
    conifers.setColorAt(placed, cc);
    placed++;
  }
  conifers.count = placed;
  conifers.computeBoundingSphere();
  conifers.name = 'conifers';
  scene.add(conifers);

  // ---- dead trees (a few, for melancholy — two stand right by the road)
  const deadGeo = deadTreeGeometry();
  const dead = new THREE.InstancedMesh(deadGeo, toon({ flatShading: true }), 22);
  dead.castShadow = true;
  {
    const fixed = [[roadX(-31) - 5.2, -31, 7.5], [roadX(33) + 6, 33, 8.5], [-27, 12, 9], [24, -16, 7]];
    let n = 0;
    for (const [x, z, h] of fixed) {
      dummy.position.set(x, heightAt(x, z) - 0.1, z); dummy.rotation.set(0, rnd() * 6.28, (rnd() - 0.5) * 0.12);
      dummy.scale.set(h, h, h); dummy.updateMatrix(); dead.setMatrixAt(n++, dummy.matrix);
    }
    while (n < 22) {
      const a = rnd() * Math.PI * 2, r = 30 + rnd() * 55;
      const x = Math.cos(a) * r, z = Math.sin(a) * r;
      if (!offRoad(x, z, 6)) continue;
      const h = 6 + rnd() * 6;
      dummy.position.set(x, heightAt(x, z) - 0.1, z); dummy.rotation.set((rnd() - 0.5) * 0.15, rnd() * 6.28, (rnd() - 0.5) * 0.15);
      dummy.scale.set(h, h, h); dummy.updateMatrix(); dead.setMatrixAt(n++, dummy.matrix);
    }
    dead.computeBoundingSphere();
  }
  dead.name = 'deadTrees';
  scene.add(dead);

  // ---- grass tufts (wind in the vertex shader)
  const grassU = { uTime: { value: 0 }, uWind: { value: 1 } };
  const grassMat = new THREE.MeshLambertMaterial({ vertexColors: true, side: THREE.DoubleSide });
  grassMat.onBeforeCompile = (sh) => {
    Object.assign(sh.uniforms, grassU);
    sh.vertexShader = sh.vertexShader
      .replace('#include <common>', '#include <common>\nuniform float uTime, uWind;')
      .replace('#include <begin_vertex>', /* glsl */`#include <begin_vertex>
        #ifdef USE_INSTANCING
          vec3 ip = instanceMatrix[3].xyz;
        #else
          vec3 ip = vec3(0.);
        #endif
        float gust = 0.6 + 0.4 * sin(uTime * 0.7 + ip.x * 0.05 + ip.z * 0.03);
        float sway = sin(uTime * 1.9 + ip.x * 0.37 + ip.z * 0.29) * 0.09 * gust * uWind;
        transformed.x += sway * transformed.y * transformed.y * 2.;
        transformed.z += sway * 0.6 * transformed.y * transformed.y * 2.;`);
  };
  const grassGeo = grassGeometry();
  const NGR = 12000;
  const grass = new THREE.InstancedMesh(grassGeo, grassMat, NGR);
  grass.receiveShadow = true;
  {
    let n = 0, tries = 0;
    while (n < NGR && tries++ < 60000) {
      const a = rnd() * Math.PI * 2;
      const r = Math.sqrt(rnd()) * 70;
      const x = Math.cos(a) * r, z = Math.sin(a) * r;
      const dRoad = Math.abs(x - roadX(z));
      if (dRoad < 2.6 + rnd() * 0.8) continue;
      // clearing is trampled: sparse in the middle, thick at the edge
      const keep = r < 22 ? 0.4 + 0.4 * (r / 22) : 1;
      if (rnd() > keep) continue;
      if (Math.hypot(x - spots.sisterHide.x, z - spots.sisterHide.z) < 2.2) continue;
      const s = (0.6 + rnd() * 0.6) * (r > 26 ? 1.3 : 1);
      dummy.position.set(x, heightAt(x, z), z);
      dummy.rotation.set(0, rnd() * 6.28, 0);
      dummy.scale.set(s, s * (0.8 + rnd() * 0.5), s);
      dummy.updateMatrix();
      grass.setMatrixAt(n, dummy.matrix);
      const v = 0.75 + rnd() * 0.45;
      cc.setRGB(v * (0.95 + rnd() * 0.15), v, v * 0.9);
      grass.setColorAt(n, cc);
      n++;
    }
    grass.count = n;
    grass.computeBoundingSphere();
  }
  grass.name = 'grass';
  scene.add(grass);

  // ---- rocks (instanced) + the big hiding rock
  const rockGeo = tintMoss(rockGeometry(4, 1), 0x3f5236, 0x6a6474, 0.55);
  const rockMat = toon({ flatShading: true });
  const NR = 70;
  const rocks = new THREE.InstancedMesh(rockGeo, rockMat, NR);
  rocks.castShadow = true; rocks.receiveShadow = true;
  {
    let n = 0;
    // pebbles in the clearing (visual only, ankle height)
    while (n < 18) {
      const a = rnd() * 6.28, r = 4 + rnd() * 20;
      const x = Math.cos(a) * r, z = Math.sin(a) * r;
      if (!offRoad(x, z, 3.2)) continue;
      const s = 0.1 + rnd() * 0.16;
      dummy.position.set(x, 0, z); dummy.rotation.set(rnd(), rnd() * 6, rnd()); dummy.scale.set(s * 1.3, s, s);
      dummy.updateMatrix(); rocks.setMatrixAt(n++, dummy.matrix);
    }
    // boulders ringing the clearing and scattered in the woods
    while (n < NR) {
      const a = rnd() * 6.28, r = 27 + rnd() * 50;
      const x = Math.cos(a) * r, z = Math.sin(a) * r;
      if (!offRoad(x, z, 4.5)) continue;
      const s = 0.4 + rnd() * rnd() * 2.2;
      dummy.position.set(x, heightAt(x, z) + s * 0.1, z); dummy.rotation.set(rnd() * 0.4, rnd() * 6, rnd() * 0.4);
      dummy.scale.set(s * (1 + rnd() * 0.6), s * (0.6 + rnd() * 0.5), s * (1 + rnd() * 0.4));
      dummy.updateMatrix(); rocks.setMatrixAt(n++, dummy.matrix);
    }
    rocks.computeBoundingSphere();
  }
  rocks.name = 'rocks';
  scene.add(rocks);

  const hideGeo = (() => {
    const a = jitter(new THREE.IcosahedronGeometry(1, 2), 0.28, 21);
    const b = jitter(new THREE.IcosahedronGeometry(1, 1), 0.3, 22);
    const c = jitter(new THREE.IcosahedronGeometry(1, 1), 0.3, 23);
    const geo = flatten(bake([
      { g: a, m: mat(0, 1.0, 0, 0.1, 0.4, 0.05, 2.0, 1.25, 1.45), c: 0x6a6474 },
      { g: b, m: mat(1.6, 0.45, 0.5, 0.3, 1.2, 0.2, 0.9, 0.7, 0.9), c: 0x6a6474 },
      { g: c, m: mat(-1.5, 0.3, -0.6, 0.5, 2.2, 0.1, 0.7, 0.55, 0.6), c: 0x6a6474 },
    ]));
    return tintMoss(geo, 0x3f5638, 0x6c6676, 0.45);
  })();
  const hideRock = new THREE.Mesh(hideGeo, rockMat);
  hideRock.position.copy(spots.sisterHide);
  hideRock.rotation.y = 0.6;
  hideRock.castShadow = true; hideRock.receiveShadow = true;
  hideRock.name = 'hideRock';
  scene.add(hideRock);

  // ---- props: broken roadside fence, a signpost, a small stone jizo with a red bib, a fallen log
  const props = (() => {
    const parts = [];
    const wood = 0x5a4434, woodDark = 0x3b2d25;
    const r2 = mulberry32(5);
    let prev = null;
    for (let z = -29; z > -60; z -= 2.5) {
      const x = roadX(z) + 4.3 + (r2() - 0.5) * 0.3;
      if (r2() < 0.18) { prev = null; continue; }            // a missing post
      const tilt = (r2() - 0.5) * 0.25, h = 1.0 + r2() * 0.25, y0 = heightAt(x, z);
      parts.push({ g: new THREE.BoxGeometry(0.12, h, 0.12), m: mat(x, y0 + h / 2 - 0.1, z, tilt, r2(), tilt * 0.5), c: woodDark });
      if (prev && r2() < 0.75) {
        const mx = (x + prev[0]) / 2, mz = (z + prev[1]) / 2, len = Math.hypot(x - prev[0], z - prev[1]);
        const yaw = Math.atan2(x - prev[0], z - prev[1]);
        const sag = r2() < 0.25 ? 0.35 : 0;                  // one rail has come down at one end
        parts.push({ g: new THREE.BoxGeometry(0.06, 0.1, len), m: mat(mx, y0 + 0.75 - sag * 0.5, mz, sag * 0.4, yaw, 0), c: wood });
        if (r2() < 0.6) parts.push({ g: new THREE.BoxGeometry(0.06, 0.09, len), m: mat(mx, y0 + 0.38, mz, 0, yaw, 0), c: wood });
      }
      prev = [x, z];
    }
    // signpost
    const sx = roadX(-26) - 3.6, sz = -26;
    parts.push({ g: new THREE.CylinderGeometry(0.06, 0.07, 2.2, 6), m: mat(sx, 1.1, sz, 0, 0, 0.04), c: woodDark });
    parts.push({ g: new THREE.BoxGeometry(0.9, 0.2, 0.04), m: mat(sx + 0.3, 1.85, sz, 0, 0.3, 0.05), c: wood });
    parts.push({ g: new THREE.BoxGeometry(0.75, 0.18, 0.04), m: mat(sx - 0.25, 1.55, sz, 0, -0.5, -0.12), c: wood });
    // jizo: stone body, head, red bib, little cap
    const jx = roadX(-38) - 4.0, jz = -38, jy = heightAt(jx, jz);
    parts.push({ g: new THREE.BoxGeometry(0.7, 0.18, 0.6), m: mat(jx, jy + 0.09, jz, 0, 0.2, 0), c: 0x575260 });
    parts.push({ g: new THREE.CylinderGeometry(0.17, 0.22, 0.5, 8), m: mat(jx, jy + 0.43, jz), c: 0x7b7682 });
    parts.push({ g: new THREE.SphereGeometry(0.15, 10, 8), m: mat(jx, jy + 0.8, jz), c: 0x837e8a });
    parts.push({ g: new THREE.ConeGeometry(0.24, 0.26, 8, 1, true), m: mat(jx, jy + 0.55, jz, 0, 0, Math.PI), c: 0xa3232a });
    parts.push({ g: new THREE.CylinderGeometry(0.16, 0.16, 0.05, 10), m: mat(jx, jy + 0.95, jz), c: 0x8e2027 });
    // fallen log at the clearing's edge, with a sawn light end
    const lx = -25.5, lz = -16, ly = heightAt(lx, lz);
    parts.push({ g: jitter(new THREE.CylinderGeometry(0.42, 0.5, 6.5, 9, 3), 0.05, 44), m: mat(lx, ly + 0.35, lz, 0, 0.9, Math.PI / 2 - 0.03), c: 0x4a3a30 });
    parts.push({ g: new THREE.CylinderGeometry(0.4, 0.4, 0.02, 9), m: mat(lx + Math.cos(0.9) * 3.26, ly + 0.34, lz - Math.sin(0.9) * 3.26, 0, 0.9, Math.PI / 2), c: 0x9c7a5a });
    return bake(parts);
  })();
  const propMesh = new THREE.Mesh(props, toon({}));
  propMesh.castShadow = true; propMesh.receiveShadow = true;
  propMesh.name = 'props';
  scene.add(propMesh);

  // ---- fireflies
  const flies = makeFireflies(170, (r) => {
    const a = r() * 6.28, rad = 12 + r() * 36;
    const x = Math.cos(a) * rad, z = Math.sin(a) * rad;
    return V3(x, heightAt(x, z) + 0.5 + r() * 2.8, z);
  }, DUSK.fly);
  scene.add(flies.pts);

  // ------------------------------------------------------------------ update
  let lastA = -1;
  const flyA = new THREE.Color(DUSK.fly), flyB = new THREE.Color(AWAKE.fly);
  function update(dt, time, awaken = 0) {
    const a = clamp01(awaken);
    sky.u.uTime.value = time;
    grassU.uTime.value = time;
    flies.u.uTime.value = time;
    // the game recentres sun.target on the player; the light follows so the direction never changes
    sun.position.copy(sun.target.position).add(sunOffset);
    rim.target.position.copy(sun.target.position);
    rim.position.copy(sun.target.position).addScaledVector(rim.userData.dir || (rim.userData.dir = V3(0.35, 0.45, -1).normalize()), 50);
    if (a === lastA) return;
    lastA = a;
    const e = a * a * (3 - 2 * a);
    lerpC('top', e, sky.u.uTop.value);
    lerpC('mid', e, sky.u.uMid.value);
    lerpC('horizon', e, sky.u.uHorizon.value);
    lerpC('ridgeFar', e, sky.u.uRidgeFar.value);
    lerpC('ridgeNear', e, sky.u.uRidgeNear.value);
    lerpC('sun', e, sky.u.uSunCol.value);
    lerpC('moon', e, sky.u.uMoonCol.value).multiplyScalar(0.62);
    sky.u.uGlow.value.setRGB(0.12, 0.55, 0.9).multiplyScalar(e * 0.7);
    sky.u.uStars.value = DUSK.stars + (AWAKE.stars - DUSK.stars) * e;
    fog.color.copy(sky.u.uHorizon.value);
    fog.near = 24 - 6 * e; fog.far = 165 - 55 * e;
    lerpC('hemiSky', e, hemi.color);
    lerpC('hemiGround', e, hemi.groundColor);
    hemi.intensity = DUSK.hemiI + (AWAKE.hemiI - DUSK.hemiI) * e;
    lerpC('sunLight', e, sun.color);
    sun.intensity = DUSK.sunI + (AWAKE.sunI - DUSK.sunI) * e;
    rim.intensity = AWAKE.rimI * e;
    flies.u.uCol.value.copy(flyA).lerp(flyB, e).multiplyScalar(3.2);
    grassU.uWind.value = 1 + e * 1.4;
  }
  update(0, 0, 0);

  return {
    update,
    heightAt,
    arenaRadius: ARENA,
    spots,
    sun,
    // extras (not in the contract, handy for the game/debug)
    rim, hemi, fog, sky: sky.mesh, roadX,
  };
}

// =================================================================== DOJO
export function buildDojo() {
  const scene = new THREE.Scene();
  scene.name = 'dojo';
  const W = 18, D = 22; // platform footprint, x in [-9,9], z in [-11,11]

  const sky = makeSky({
    top: 0x020309, mid: 0x0a0f28, horizon: 0x1c1d3a, ridgeFar: 0x121433, ridgeNear: 0x0a0b1e,
    sunDir: V3(-0.7, -0.1, 0.7), sunCol: 0x000000, moonDir: V3(-0.45, 0.55, 0.7), moonCol: 0xdfe6ff, moonR: 0.06, stars: 1.2,
  });
  scene.add(sky.mesh);
  scene.fog = new THREE.Fog(0x1c1d3a, 20, 75);
  scene.background = scene.fog.color;

  // lights: cold moon (the one shadow caster), low hemi, three warm lanterns
  scene.add(new THREE.HemisphereLight(0x3a4a8a, 0x120c10, 0.9));
  const moon = new THREE.DirectionalLight(0x9fb4ff, 1.1);
  moon.position.set(-14, 22, 16);
  moon.castShadow = true;
  moon.shadow.mapSize.set(2048, 2048);
  Object.assign(moon.shadow.camera, { left: -17, right: 17, top: 17, bottom: -17, near: 1, far: 70 });
  moon.shadow.bias = -0.0005; moon.shadow.normalBias = 0.03;
  scene.add(moon); scene.add(moon.target);

  // ---- floor planks (painted)
  const plankTex = canvasTex(1024, 1024, (g, w, h) => {
    const rnd = mulberry32(12);
    const n = 16, pw = w / n;
    for (let i = 0; i < n; i++) {
      // each plank in 2-3 boards with staggered butt joints
      let y = -rnd() * h;
      while (y < h) {
        const len = h * (0.45 + rnd() * 0.5);
        const v = 0.75 + rnd() * 0.3;
        g.fillStyle = `rgb(${(122 * v) | 0},${(84 * v) | 0},${(56 * v) | 0})`;
        g.fillRect(i * pw, y, pw, len);
        // grain
        for (let k = 0; k < 14; k++) {
          const gx = i * pw + rnd() * pw;
          g.strokeStyle = `rgba(${40 + rnd() * 30 | 0},${25 + rnd() * 15 | 0},15,${0.15 + rnd() * 0.2})`;
          g.lineWidth = 0.6 + rnd() * 1.4;
          g.beginPath(); g.moveTo(gx, y);
          for (let t = 0; t <= 1; t += 0.1) g.lineTo(gx + Math.sin(t * 6 + k) * 2.5 * rnd(), y + len * t);
          g.stroke();
        }
        if (rnd() < 0.3) { // a knot
          const kx = i * pw + pw * (0.3 + rnd() * 0.4), ky = y + rnd() * len;
          g.fillStyle = 'rgba(45,26,15,0.6)'; g.beginPath(); g.ellipse(kx, ky, 3 + rnd() * 3, 7 + rnd() * 5, 0, 0, 6.3); g.fill();
        }
        g.fillStyle = 'rgba(15,8,5,0.9)'; g.fillRect(i * pw, y, pw, 2);
        y += len;
      }
      g.fillStyle = 'rgba(12,7,4,0.95)'; g.fillRect(i * pw, 0, 2, h);
    }
  });
  plankTex.wrapS = plankTex.wrapT = THREE.RepeatWrapping;
  plankTex.repeat.set(W / 4, D / 4);
  const floorMat = new THREE.MeshStandardMaterial({ map: plankTex, roughness: 0.42, metalness: 0.0 });
  const floor = new THREE.Mesh(new THREE.BoxGeometry(W, 0.4, D), floorMat);
  floor.position.y = -0.2;
  floor.receiveShadow = true;
  scene.add(floor);

  // ---- gravel yard around it
  const gravelTex = canvasTex(256, 256, (g, w, h) => {
    const rnd = mulberry32(3);
    g.fillStyle = '#34323a'; g.fillRect(0, 0, w, h);
    for (let i = 0; i < 2600; i++) {
      const v = 30 + rnd() * 50 | 0;
      g.fillStyle = `rgb(${v},${v - 2},${v + 6})`;
      g.beginPath(); g.arc(rnd() * w, rnd() * h, 0.6 + rnd() * 1.8, 0, 6.3); g.fill();
    }
    // raked lines
    g.strokeStyle = 'rgba(0,0,0,0.25)'; g.lineWidth = 2;
    for (let y = 0; y < h; y += 16) { g.beginPath(); g.moveTo(0, y); g.lineTo(w, y); g.stroke(); }
  });
  gravelTex.wrapS = gravelTex.wrapT = THREE.RepeatWrapping;
  gravelTex.repeat.set(40, 40);
  const yard = new THREE.Mesh(new THREE.PlaneGeometry(140, 140), new THREE.MeshLambertMaterial({ map: gravelTex }));
  yard.rotation.x = -Math.PI / 2; yard.position.y = -0.4;
  yard.receiveShadow = true;
  scene.add(yard);

  // ---- stone border (instanced blocks)
  const rnd = mulberry32(77);
  const stoneGeo = tintMoss(flatten(bake([{ g: jitter(new THREE.BoxGeometry(1, 1, 1, 2, 2, 2), 0.08, 9), c: 0x6f6b76 }])), 0x4a5a44, 0x6f6b76, 0.8);
  const stones = new THREE.InstancedMesh(stoneGeo, toon({ flatShading: true }), 120);
  stones.castShadow = true; stones.receiveShadow = true;
  const dm = new THREE.Object3D();
  let ns = 0;
  const addStone = (x, z, yaw) => {
    dm.position.set(x, -0.28, z); dm.rotation.set(0, yaw + (rnd() - 0.5) * 0.08, 0);
    dm.scale.set(0.9 + rnd() * 0.25, 0.3 + rnd() * 0.12, 0.7); dm.updateMatrix();
    stones.setMatrixAt(ns, dm.matrix);
    const v = 0.8 + rnd() * 0.35; stones.setColorAt(ns, new THREE.Color(v, v, v * 1.03)); ns++;
  };
  for (let x = -W / 2 - 0.4; x <= W / 2 + 0.4; x += 1.02) { addStone(x, -D / 2 - 0.4, 0); addStone(x, D / 2 + 0.4, 0); }
  for (let z = -D / 2 + 0.6; z <= D / 2 - 0.4; z += 1.02) { addStone(-W / 2 - 0.4, z, Math.PI / 2); addStone(W / 2 + 0.4, z, Math.PI / 2); }
  stones.count = ns;
  scene.add(stones);

  // ---- wood: pillars, beams, back hall frame + roof, makiwara, weapon rack, stone lanterns
  const woodParts = [];
  const wood = 0x4a3326, woodL = 0x7a5a3e, roofC = 0x1d1f2a, stoneC = 0x6c6874;
  const posts = [[-8.4, -10.4], [8.4, -10.4], [-8.4, 0], [8.4, 0], [-8.4, 10.4], [8.4, 10.4]];
  for (const [x, z] of posts) {
    woodParts.push({ g: new THREE.BoxGeometry(0.34, 4.2, 0.34), m: mat(x, 2.1, z), c: wood });
    woodParts.push({ g: new THREE.BoxGeometry(0.5, 0.25, 0.5), m: mat(x, 0.12, z), c: stoneC });
  }
  // side beams connecting the posts at the top
  for (const x of [-8.4, 8.4]) woodParts.push({ g: new THREE.BoxGeometry(0.28, 0.32, 21.2), m: mat(x, 4.1, 0), c: wood });
  // lantern arms
  const lanternPos = [[-7.6, 3.3, -10.4], [7.6, 3.3, -10.4], [-7.6, 3.3, 10.4], [7.6, 3.3, 10.4], [-7.6, 3.3, 0], [7.6, 3.3, 0]];
  for (const [x, y, z] of lanternPos) woodParts.push({ g: new THREE.BoxGeometry(1.0, 0.1, 0.1), m: mat(x + (x < 0 ? -0.35 : 0.35), 3.9, z), c: wood });
  // back hall at z=+14: frame, raised veranda, roof
  const hz = 14.2;
  woodParts.push({ g: new THREE.BoxGeometry(24, 0.5, 3.4), m: mat(0, 0.05, hz), c: 0x3a281d });
  for (let x = -11.5; x <= 11.6; x += 2.875) woodParts.push({ g: new THREE.BoxGeometry(0.3, 3.6, 0.3), m: mat(x, 2.1, hz - 1.3), c: wood });
  woodParts.push({ g: new THREE.BoxGeometry(24, 0.3, 0.35), m: mat(0, 3.8, hz - 1.3), c: wood });
  woodParts.push({ g: new THREE.BoxGeometry(24, 3.6, 0.2), m: mat(0, 2.1, hz + 0.9), c: 0x2a1d16 });
  woodParts.push({ g: new THREE.BoxGeometry(26.5, 0.35, 6.4), m: mat(0, 4.65, hz - 0.2, -0.32, 0, 0), c: roofC });
  woodParts.push({ g: new THREE.BoxGeometry(26.5, 0.35, 3.2), m: mat(0, 5.6, hz + 2.8, 0.55, 0, 0), c: roofC });
  woodParts.push({ g: new THREE.BoxGeometry(27, 0.2, 0.3), m: mat(0, 5.0, hz - 3.3), c: 0x2a2c38 });
  // makiwara posts at the sides (straw bound near the top)
  for (const [x, z] of [[-6.3, 6], [6.3, 6], [-6.3, -3], [6.3, -3]]) {
    woodParts.push({ g: new THREE.CylinderGeometry(0.1, 0.12, 1.6, 8), m: mat(x, 0.8, z), c: woodL });
    woodParts.push({ g: new THREE.CylinderGeometry(0.15, 0.15, 0.45, 8), m: mat(x, 1.25, z), c: 0xb09a62 });
  }
  // weapon rack with three bokken
  woodParts.push({ g: new THREE.BoxGeometry(2.4, 0.12, 0.35), m: mat(-3.5, 0.5, 10.8), c: wood });
  woodParts.push({ g: new THREE.BoxGeometry(2.4, 0.12, 0.35), m: mat(-3.5, 1.3, 10.8), c: wood });
  for (const x of [-4.6, -2.4]) woodParts.push({ g: new THREE.BoxGeometry(0.12, 1.5, 0.3), m: mat(x, 0.75, 10.8), c: wood });
  for (let i = 0; i < 3; i++) woodParts.push({ g: new THREE.BoxGeometry(0.05, 1.05, 0.04), m: mat(-4.1 + i * 0.55, 0.95, 10.7, 0, 0, 0.12), c: 0xb88f5a });
  // stone toro lanterns just outside the platform
  for (const [x, z] of [[-11.6, -8], [11.6, -8], [-11.6, 8], [11.6, 8]]) {
    woodParts.push({ g: new THREE.BoxGeometry(0.8, 0.25, 0.8), m: mat(x, -0.25, z), c: stoneC });
    woodParts.push({ g: new THREE.CylinderGeometry(0.16, 0.2, 1.2, 8), m: mat(x, 0.45, z), c: stoneC });
    woodParts.push({ g: new THREE.BoxGeometry(0.7, 0.55, 0.7), m: mat(x, 1.3, z), c: 0x5e5a66 });
    woodParts.push({ g: new THREE.ConeGeometry(0.72, 0.5, 4), m: mat(x, 1.83, z, 0, Math.PI / 4, 0), c: stoneC });
  }
  const woodMesh = new THREE.Mesh(bake(woodParts), toon({}));
  woodMesh.castShadow = true; woodMesh.receiveShadow = true;
  scene.add(woodMesh);

  // ---- glowing panels: shoji of the hall + the fire windows of the toro (one emissive mesh)
  const shojiTex = canvasTex(256, 256, (g, w, h) => {
    const grd = g.createRadialGradient(w / 2, h / 2, 10, w / 2, h / 2, w * 0.75);
    grd.addColorStop(0, '#ffe2b0'); grd.addColorStop(1, '#d99a5c');
    g.fillStyle = grd; g.fillRect(0, 0, w, h);
    g.strokeStyle = '#2a1a10'; g.lineWidth = 5;
    for (let i = 0; i <= 4; i++) { g.beginPath(); g.moveTo((i * w) / 4, 0); g.lineTo((i * w) / 4, h); g.stroke(); }
    for (let i = 0; i <= 6; i++) { g.beginPath(); g.moveTo(0, (i * h) / 6); g.lineTo(w, (i * h) / 6); g.stroke(); }
    g.lineWidth = 12; g.strokeRect(0, 0, w, h);
  });
  const panelParts = [];
  for (let i = 0; i < 8; i++) {
    const x = -10.06 + i * 2.875;
    if (i === 3) continue; // one panel slid open (dark)
    panelParts.push({ g: new THREE.PlaneGeometry(2.5, 3.3), m: mat(x, 2.1, hz + 0.78, 0, Math.PI, 0), c: 0xffffff });
  }
  const panelGeo = bake(panelParts);
  const panels = new THREE.Mesh(panelGeo, new THREE.MeshBasicMaterial({ map: shojiTex, color: new THREE.Color(1.9, 1.5, 1.1), fog: true, side: THREE.DoubleSide }));
  scene.add(panels);
  const toroGlow = new THREE.Mesh(
    bake([[-11.6, -8], [11.6, -8], [-11.6, 8], [11.6, 8]].map(([x, z]) => ({ g: new THREE.BoxGeometry(0.72, 0.3, 0.72), m: mat(x, 1.3, z), c: 0xffffff }))),
    new THREE.MeshBasicMaterial({ color: new THREE.Color(3.0, 1.7, 0.7) }),
  );
  scene.add(toroGlow);

  // ---- paper lanterns (instanced, they sway) + caps
  const lanternTex = canvasTex(256, 256, (g, w, h) => {
    const grd = g.createLinearGradient(0, 0, 0, h);
    grd.addColorStop(0, '#c9562c'); grd.addColorStop(0.5, '#ffb36a'); grd.addColorStop(1, '#c9562c');
    g.fillStyle = grd; g.fillRect(0, 0, w, h);
    g.strokeStyle = 'rgba(90,30,10,0.45)'; g.lineWidth = 2;
    for (let y = 8; y < h; y += 14) { g.beginPath(); g.moveTo(0, y); g.lineTo(w, y); g.stroke(); }
    // a brush mark on two sides
    g.strokeStyle = 'rgba(40,10,5,0.85)'; g.lineCap = 'round';
    for (const cx of [w * 0.25, w * 0.75]) {
      g.lineWidth = 9;
      g.beginPath(); g.moveTo(cx - 18, 90); g.lineTo(cx + 18, 90); g.stroke();
      g.beginPath(); g.moveTo(cx, 72); g.lineTo(cx, 180); g.stroke();
      g.lineWidth = 7;
      g.beginPath(); g.moveTo(cx - 20, 130); g.quadraticCurveTo(cx, 120, cx + 22, 140); g.stroke();
      g.beginPath(); g.moveTo(cx - 14, 165); g.lineTo(cx - 24, 185); g.stroke();
      g.beginPath(); g.moveTo(cx + 12, 160); g.lineTo(cx + 24, 182); g.stroke();
    }
  });
  const lanternGeo = new THREE.SphereGeometry(0.34, 16, 12);
  lanternGeo.scale(1, 1.3, 1);
  lanternGeo.translate(0, -0.45, 0); // hangs from its pivot
  const lanternMat = new THREE.MeshBasicMaterial({ map: lanternTex, color: new THREE.Color(2.6, 2.1, 1.7) });
  const lanterns = new THREE.InstancedMesh(lanternGeo, lanternMat, lanternPos.length);
  const capGeo = bake([
    { g: new THREE.CylinderGeometry(0.2, 0.2, 0.08, 12), m: mat(0, -0.02, 0), c: 0x1a1414 },
    { g: new THREE.CylinderGeometry(0.2, 0.2, 0.08, 12), m: mat(0, -0.88, 0), c: 0x1a1414 },
    { g: new THREE.CylinderGeometry(0.01, 0.01, 0.5, 4), m: mat(0, 0.25, 0), c: 0x1a1414 },
  ]);
  const caps = new THREE.InstancedMesh(capGeo, toon({}), lanternPos.length);
  scene.add(lanterns); scene.add(caps);
  const lanternPivots = lanternPos.map(([x, y, z]) => V3(x + (x < 0 ? -0.8 : 0.8), 3.85, z));
  const lights = [];
  for (const i of [0, 3, 4]) {
    const l = new THREE.PointLight(0xffa860, 22, 16, 1.8);
    l.position.copy(lanternPivots[i]).add(V3(0, -0.5, 0));
    scene.add(l);
    lights.push({ l, i, base: 22 });
  }

  // ---- pines around the yard (dark silhouettes)
  const pineGeo = coniferGeometry(2.1);
  const pines = new THREE.InstancedMesh(pineGeo, toon({ flatShading: true }), 40);
  pines.castShadow = true;
  let np = 0;
  while (np < 40) {
    const a = rnd() * 6.28, r = 16 + rnd() * 30;
    const x = Math.cos(a) * r, z = Math.sin(a) * r * 1.1;
    if (Math.abs(x) < 13 && Math.abs(z) < 17) continue;
    const h = 7 + rnd() * 8, w = h * 0.9;
    dm.position.set(x, -0.6, z); dm.rotation.set(0, rnd() * 6.28, 0); dm.scale.set(w, h, w); dm.updateMatrix();
    pines.setMatrixAt(np, dm.matrix);
    pines.setColorAt(np, new THREE.Color(0.7, 0.75, 0.9));
    np++;
  }
  scene.add(pines);

  // ---- motes drifting in lantern light
  const motes = makeFireflies(90, (r) => V3((r() - 0.5) * 18, 0.4 + r() * 3.4, (r() - 0.5) * 22), 0xffc890);
  motes.u.uSize.value = 0.04;
  motes.u.uCol.value.multiplyScalar(0.45);
  scene.add(motes.pts);

  const spots = { player: V3(0, 0, -6), target: V3(0, 0, 4) };

  const tmpM = new THREE.Matrix4(), tq = new THREE.Quaternion(), te = new THREE.Euler(), one = V3(1, 1, 1);
  function update(dt, time) {
    sky.u.uTime.value = time;
    motes.u.uTime.value = time;
    for (let i = 0; i < lanternPivots.length; i++) {
      const p = lanternPivots[i];
      te.set(Math.sin(time * 0.9 + i * 1.7) * 0.05, time * 0.1 + i, Math.sin(time * 0.7 + i) * 0.05);
      tq.setFromEuler(te);
      tmpM.compose(p, tq, one);
      lanterns.setMatrixAt(i, tmpM);
      caps.setMatrixAt(i, tmpM);
    }
    lanterns.instanceMatrix.needsUpdate = true;
    caps.instanceMatrix.needsUpdate = true;
    for (const L of lights) {
      const f = 0.86 + 0.08 * Math.sin(time * 7.3 + L.i * 3) + 0.06 * Math.sin(time * 13.1 + L.i);
      L.l.intensity = L.base * f;
    }
  }
  update(0, 0);

  return { scene, update, spots, moon };
}
