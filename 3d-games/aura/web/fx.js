// AURA — fx.js
// Pooled particles, streaks, anime slash arcs, ground shockwaves, afterimage ghosts, body auras.
// Everything is allocated in the constructor or on the first call for a given actor (warm-up);
// after that nothing allocates per frame.
import * as THREE from './vendor/three/three.module.js';

const CYAN = new THREE.Color(0x4fdcff);
const TAU = Math.PI * 2;
const rand = (a, b) => a + Math.random() * (b - a);

// scratch
const _v = new THREE.Vector3(), _v2 = new THREE.Vector3(), _d = new THREE.Vector3();
const _c = new THREE.Color(), _c2 = new THREE.Color();
const _m3 = new THREE.Matrix3();
const _vs = new THREE.Vector2();

function setColor(out, color, fallback = CYAN) {
  if (color === undefined || color === null) return out.copy(fallback);
  if (Array.isArray(color)) return out.setRGB(color[0], color[1], color[2]);
  return out.set(color);
}
function randUnit(out) {
  const u = Math.random() * 2 - 1, a = Math.random() * TAU, s = Math.sqrt(1 - u * u);
  return out.set(s * Math.cos(a), u, s * Math.sin(a));
}
/** unit vector biased toward dir (dir may be null): spread 0 = along dir, 1 = anywhere */
function coneDir(out, dir, spread) {
  randUnit(out);
  if (!dir) return out;
  _d.copy(dir);
  const L = _d.length();
  if (L < 1e-5) return out;
  _d.multiplyScalar(1 / L);
  return out.multiplyScalar(spread).addScaledVector(_d, 1).normalize();
}

// =========================================================== soft points
const POINT_VS = /* glsl */`
attribute vec3 aCol; attribute float aSize; attribute float aAlpha;
uniform float uScale;
varying vec3 vCol; varying float vA;
void main(){
  vCol = aCol; vA = aAlpha;
  vec4 mv = modelViewMatrix * vec4(position, 1.);
  if (aAlpha <= 0.001 || -mv.z < 0.05) { gl_Position = vec4(2., 2., 2., 1.); gl_PointSize = 0.; return; }
  gl_PointSize = clamp(aSize * uScale / -mv.z, 1.0, 320.);
  gl_Position = projectionMatrix * mv;
}`;
const POINT_FS = /* glsl */`
uniform float uCore; uniform float uHard;
varying vec3 vCol; varying float vA;
void main(){
  float d = length(gl_PointCoord - .5) * 2.;
  if (d > 1.) discard;
  float soft = pow(1. - d, 1.6);
  float hard = 1. - smoothstep(0.72, 1., d);
  float a = mix(soft, hard, uHard) * vA;
  float core = pow(max(1. - d * 1.6, 0.), 2.) * uCore;
  vec3 col = vCol * mix(1., 0.75 + 0.25 * (1. - d), uHard) + vec3(core);
  gl_FragColor = vec4(col, a);
}`;

class PointPool {
  constructor(n, { additive, core, hard }) {
    this.n = n; this.cursor = 0;
    this.pos = new Float32Array(n * 3);
    this.col = new Float32Array(n * 3);
    this.size = new Float32Array(n);
    this.alpha = new Float32Array(n);
    this.vel = new Float32Array(n * 3);
    this.life = new Float32Array(n);
    this.max = new Float32Array(n);
    this.s0 = new Float32Array(n); this.s1 = new Float32Array(n);
    this.a0 = new Float32Array(n);
    this.drag = new Float32Array(n); this.grav = new Float32Array(n);
    this.swirl = new Float32Array(n); this.flick = new Float32Array(n);
    this.cx = new Float32Array(n); this.cz = new Float32Array(n);
    const g = new THREE.BufferGeometry();
    const mk = (arr, k) => { const a = new THREE.BufferAttribute(arr, k); a.setUsage(THREE.DynamicDrawUsage); return a; };
    g.setAttribute('position', mk(this.pos, 3));
    g.setAttribute('aCol', mk(this.col, 3));
    g.setAttribute('aSize', mk(this.size, 1));
    g.setAttribute('aAlpha', mk(this.alpha, 1));
    this.u = { uScale: { value: 800 }, uCore: { value: core }, uHard: { value: hard } };
    const m = new THREE.ShaderMaterial({
      uniforms: this.u, vertexShader: POINT_VS, fragmentShader: POINT_FS,
      transparent: true, depthWrite: false,
      blending: additive ? THREE.AdditiveBlending : THREE.NormalBlending,
    });
    this.points = new THREE.Points(g, m);
    this.points.frustumCulled = false;
    this.points.renderOrder = additive ? 10 : 9;
    this.points.onBeforeRender = (r, s, cam) => {
      r.getDrawingBufferSize(_vs);
      this.u.uScale.value = cam.projectionMatrix.elements[5] * _vs.y * 0.5;
    };
    this.live = 0;
  }
  spawn(x, y, z, vx, vy, vz, life, s0, s1, col, a0, drag = 0, grav = 0, swirl = 0, flick = 0) {
    const i = this.cursor; this.cursor = (i + 1) % this.n;
    const i3 = i * 3;
    this.pos[i3] = x; this.pos[i3 + 1] = y; this.pos[i3 + 2] = z;
    this.vel[i3] = vx; this.vel[i3 + 1] = vy; this.vel[i3 + 2] = vz;
    this.col[i3] = col.r; this.col[i3 + 1] = col.g; this.col[i3 + 2] = col.b;
    this.life[i] = life; this.max[i] = life;
    this.s0[i] = s0; this.s1[i] = s1; this.size[i] = s0;
    this.a0[i] = a0; this.alpha[i] = a0;
    this.drag[i] = drag; this.grav[i] = grav; this.swirl[i] = swirl; this.flick[i] = flick;
    this.cx[i] = x; this.cz[i] = z;
    this.live = Math.max(this.live, 1);
  }
  update(dt, time) {
    let any = 0;
    const n = this.n;
    for (let i = 0; i < n; i++) {
      if (this.life[i] <= 0) { if (this.alpha[i] !== 0) { this.alpha[i] = 0; any++; } continue; }
      any++;
      const L = (this.life[i] -= dt);
      if (L <= 0) { this.alpha[i] = 0; continue; }
      const t = 1 - L / this.max[i];
      const i3 = i * 3;
      const dr = Math.exp(-this.drag[i] * dt);
      let vx = this.vel[i3] * dr, vy = this.vel[i3 + 1] * dr - this.grav[i] * dt, vz = this.vel[i3 + 2] * dr;
      const sw = this.swirl[i];
      if (sw !== 0) { // orbit the spawn column (flow motes)
        const rx = this.pos[i3] - this.cx[i], rz = this.pos[i3 + 2] - this.cz[i];
        vx += -rz * sw * dt * 6; vz += rx * sw * dt * 6;
      }
      this.vel[i3] = vx; this.vel[i3 + 1] = vy; this.vel[i3 + 2] = vz;
      this.pos[i3] += vx * dt; this.pos[i3 + 1] += vy * dt; this.pos[i3 + 2] += vz * dt;
      this.size[i] = this.s0[i] + (this.s1[i] - this.s0[i]) * t;
      let a = this.a0[i] * (1 - t) * (1 - t * 0.35) * Math.min(1, t * 25 + 0.25);
      if (this.flick[i] > 0) a *= 0.55 + 0.45 * Math.sin(time * 60 + i * 1.7);
      this.alpha[i] = a;
    }
    if (any || this.live) {
      const at = this.points.geometry.attributes;
      at.position.needsUpdate = true; at.aCol.needsUpdate = true; at.aSize.needsUpdate = true; at.aAlpha.needsUpdate = true;
      this.live = any;
    }
  }
}

// =========================================================== streaks (sparks, shards, beams)
const STREAK_VS = /* glsl */`
attribute vec3 aA; attribute vec3 aB; attribute vec2 aSide; attribute vec3 aCol; attribute float aW; attribute float aAlpha;
varying vec3 vCol; varying float vA; varying vec2 vSide;
void main(){
  vCol = aCol; vA = aAlpha; vSide = aSide;
  if (aAlpha <= 0.001) { gl_Position = vec4(2., 2., 2., 1.); return; }
  mat4 vp = projectionMatrix * viewMatrix;
  vec4 ca = vp * vec4(aA, 1.), cb = vp * vec4(aB, 1.);
  if (ca.w < 0.05 || cb.w < 0.05) { gl_Position = vec4(2., 2., 2., 1.); return; }
  float P00 = projectionMatrix[0][0], P11 = projectionMatrix[1][1];
  float aspect = P11 / P00;
  vec2 d = (cb.xy / cb.w - ca.xy / ca.w) * vec2(aspect, 1.);
  float L = length(d);
  d = L > 1e-6 ? d / L : vec2(1., 0.);
  vec2 perp = vec2(-d.y, d.x);
  vec4 c = aSide.x > .5 ? cb : ca;
  float endCap = aSide.x > .5 ? 1. : -1.;
  c.xy += (vec2(perp.x * P00, perp.y * P11) * aSide.y + vec2(d.x * P00, d.y * P11) * endCap * 0.5) * aW;
  gl_Position = c;
}`;
const STREAK_FS = /* glsl */`
varying vec3 vCol; varying float vA; varying vec2 vSide;
void main(){
  float s = 1. - abs(vSide.y);
  float prof = s * s * (3. - 2. * s);
  float core = pow(s, 5.);
  float along = mix(0.2, 1., vSide.x);
  vec3 col = vCol * prof + vec3(1.2) * core * along;
  gl_FragColor = vec4(col, vA * prof * along);
}`;

class StreakPool {
  constructor(n) {
    this.n = n; this.cursor = 0;
    const V = n * 4;
    this.A = new Float32Array(V * 3); this.B = new Float32Array(V * 3);
    this.C = new Float32Array(V * 3); this.W = new Float32Array(V); this.AL = new Float32Array(V);
    const side = new Float32Array(V * 2);
    const idx = new Uint32Array(n * 6);
    for (let i = 0; i < n; i++) {
      const v = i * 4;
      side.set([0, -1, 0, 1, 1, -1, 1, 1], v * 2);
      idx.set([v, v + 1, v + 2, v + 2, v + 1, v + 3], i * 6);
    }
    // sim state per streak
    this.pos = new Float32Array(n * 3); this.vel = new Float32Array(n * 3);
    this.from = new Float32Array(n * 3);
    this.life = new Float32Array(n); this.max = new Float32Array(n);
    this.w0 = new Float32Array(n); this.len = new Float32Array(n);
    this.drag = new Float32Array(n); this.grav = new Float32Array(n);
    this.a0 = new Float32Array(n); this.mode = new Uint8Array(n); this.flick = new Float32Array(n);
    this.col = new Float32Array(n * 3);
    const g = new THREE.BufferGeometry();
    const mk = (arr, k) => { const a = new THREE.BufferAttribute(arr, k); a.setUsage(THREE.DynamicDrawUsage); return a; };
    g.setAttribute('position', new THREE.BufferAttribute(new Float32Array(V * 3), 3)); // unused, required
    g.setAttribute('aA', mk(this.A, 3)); g.setAttribute('aB', mk(this.B, 3));
    g.setAttribute('aSide', new THREE.BufferAttribute(side, 2));
    g.setAttribute('aCol', mk(this.C, 3)); g.setAttribute('aW', mk(this.W, 1)); g.setAttribute('aAlpha', mk(this.AL, 1));
    g.setIndex(new THREE.BufferAttribute(idx, 1));
    const m = new THREE.ShaderMaterial({
      vertexShader: STREAK_VS, fragmentShader: STREAK_FS,
      transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, side: THREE.DoubleSide,
    });
    this.mesh = new THREE.Mesh(g, m);
    this.mesh.frustumCulled = false;
    this.mesh.renderOrder = 11;
    this.dirty = false;
  }
  /** particle streak: tail trails behind by vel*len seconds */
  spawn(x, y, z, vx, vy, vz, life, w, len, col, a0, drag = 0, grav = 0, flick = 0) {
    const i = this.cursor; this.cursor = (i + 1) % this.n;
    const i3 = i * 3;
    this.pos[i3] = x; this.pos[i3 + 1] = y; this.pos[i3 + 2] = z;
    this.vel[i3] = vx; this.vel[i3 + 1] = vy; this.vel[i3 + 2] = vz;
    this.life[i] = life; this.max[i] = life; this.w0[i] = w; this.len[i] = len;
    this.drag[i] = drag; this.grav[i] = grav; this.a0[i] = a0; this.mode[i] = 0; this.flick[i] = flick;
    this.col[i3] = col.r; this.col[i3 + 1] = col.g; this.col[i3 + 2] = col.b;
    this.dirty = true;
  }
  /** fixed segment (beam): from -> to, thins and fades */
  beam(from, to, life, w, col, a0) {
    const i = this.cursor; this.cursor = (i + 1) % this.n;
    const i3 = i * 3;
    this.from[i3] = from.x; this.from[i3 + 1] = from.y; this.from[i3 + 2] = from.z;
    this.pos[i3] = to.x; this.pos[i3 + 1] = to.y; this.pos[i3 + 2] = to.z;
    this.life[i] = life; this.max[i] = life; this.w0[i] = w; this.a0[i] = a0; this.mode[i] = 1; this.flick[i] = 0;
    this.col[i3] = col.r; this.col[i3 + 1] = col.g; this.col[i3 + 2] = col.b;
    this.dirty = true;
  }
  update(dt, time) {
    if (!this.dirty) return;
    let any = false;
    for (let i = 0; i < this.n; i++) {
      const v = i * 4;
      if (this.life[i] <= 0) {
        if (this.AL[v] !== 0) { this.AL[v] = this.AL[v + 1] = this.AL[v + 2] = this.AL[v + 3] = 0; any = true; }
        continue;
      }
      any = true;
      const L = (this.life[i] -= dt);
      const i3 = i * 3;
      if (L <= 0) { this.AL[v] = this.AL[v + 1] = this.AL[v + 2] = this.AL[v + 3] = 0; continue; }
      const t = 1 - L / this.max[i];
      let ax, ay, az, bx, by, bz, w, a;
      if (this.mode[i] === 0) {
        const dr = Math.exp(-this.drag[i] * dt);
        this.vel[i3] *= dr; this.vel[i3 + 1] = this.vel[i3 + 1] * dr - this.grav[i] * dt; this.vel[i3 + 2] *= dr;
        this.pos[i3] += this.vel[i3] * dt; this.pos[i3 + 1] += this.vel[i3 + 1] * dt; this.pos[i3 + 2] += this.vel[i3 + 2] * dt;
        bx = this.pos[i3]; by = this.pos[i3 + 1]; bz = this.pos[i3 + 2];
        const k = this.len[i];
        ax = bx - this.vel[i3] * k; ay = by - this.vel[i3 + 1] * k; az = bz - this.vel[i3 + 2] * k;
        w = this.w0[i] * (1 - t * 0.6);
        a = this.a0[i] * (1 - t) * (1 - t);
        if (this.flick[i] > 0) a *= 0.35 + 0.65 * (0.5 + 0.5 * Math.sin(time * 70 + i * 2.3));
      } else {
        ax = this.from[i3]; ay = this.from[i3 + 1]; az = this.from[i3 + 2];
        bx = this.pos[i3]; by = this.pos[i3 + 1]; bz = this.pos[i3 + 2];
        // the tail catches up with the head as it dies, so a beam reads as a streak that zips away
        const tt = t * t;
        ax += (bx - ax) * tt; ay += (by - ay) * tt; az += (bz - az) * tt;
        w = this.w0[i] * (1 - t * 0.7);
        a = this.a0[i] * (1 - t);
      }
      const cr = this.col[i3], cg = this.col[i3 + 1], cb = this.col[i3 + 2];
      for (let k = 0; k < 4; k++) {
        const o = (v + k) * 3;
        this.A[o] = ax; this.A[o + 1] = ay; this.A[o + 2] = az;
        this.B[o] = bx; this.B[o + 1] = by; this.B[o + 2] = bz;
        this.C[o] = cr; this.C[o + 1] = cg; this.C[o + 2] = cb;
        this.W[v + k] = w; this.AL[v + k] = a;
      }
    }
    const at = this.mesh.geometry.attributes;
    at.aA.needsUpdate = at.aB.needsUpdate = at.aCol.needsUpdate = at.aW.needsUpdate = at.aAlpha.needsUpdate = true;
    this.dirty = any;
  }
}

// =========================================================== slash crescent
function crescentGeometry(segs = 56, rings = 6, span = Math.PI * 1.15, width = 0.55) {
  const pos = [], uv = [], idx = [];
  for (let i = 0; i <= segs; i++) {
    const u = i / segs;
    const th = (u - 0.5) * span;
    const thick = width * Math.pow(Math.sin(Math.PI * u), 0.75);
    for (let j = 0; j <= rings; j++) {
      const v = j / rings;
      const r = 1 - thick * (1 - v);
      // a slight helical lift so the ribbon is not dead flat edge-on
      pos.push(Math.sin(th) * r, (u - 0.5) * 0.12, Math.cos(th) * r);
      uv.push(u, v);
    }
  }
  const R = rings + 1;
  for (let i = 0; i < segs; i++) for (let j = 0; j < rings; j++) {
    const a = i * R + j, b = a + R;
    idx.push(a, b, a + 1, a + 1, b, b + 1);
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
  g.setIndex(idx);
  return g;
}
const SLASH_VS = /* glsl */`
varying vec2 vUv;
void main(){ vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.); }`;
const SLASH_FS = /* glsl */`
uniform float uHead, uLen, uThin, uFade, uFlip, uSeed;
uniform vec3 uColor;
varying vec2 vUv;
float h11(float p){ p = fract(p * .1031); p *= p + 33.33; p *= p + p; return fract(p); }
void main(){
  float u = uFlip > .5 ? 1. - vUv.x : vUv.x;
  float v = vUv.y;
  if (u > uHead) discard;
  float tail = smoothstep(uHead - uLen, uHead - uLen * 0.35, u);
  float vis = smoothstep(uThin, uThin + 0.18, v);
  // streaks running along the arc, like a brush dragged fast
  float lane = floor(v * 9.);
  float streak = 0.72 + 0.28 * step(0.35, h11(lane + uSeed * 13.));
  float core = smoothstep(0.42, 0.86, v) * (1. - smoothstep(0.93, 1., v));
  float lead = smoothstep(uHead - 0.12, uHead, u);
  vec3 col = mix(uColor * 2.4, vec3(5.5), core);
  col += vec3(4.) * lead * core;
  float edge = smoothstep(0., 0.2, v) * (1. - smoothstep(0.97, 1., v) * 0.5);
  float a = vis * tail * edge * uFade * streak;
  gl_FragColor = vec4(col, a);
}`;

// =========================================================== shockwave ring
const RING_VS = SLASH_VS;
const RING_FS = /* glsl */`
uniform float uP, uFade; uniform vec3 uColor;
varying vec2 vUv;
void main(){
  float d = length(vUv * 2. - 1.);
  float w = 0.035 + 0.09 * (1. - uP);
  float ring = exp(-pow((d - uP) / w, 2.));
  float inner = smoothstep(uP, 0., d) * 0.22 * (1. - uP);
  float core = exp(-pow((d - uP) / (w * 0.3), 2.));
  vec3 col = uColor * 2.2 * (ring + inner) + vec3(3.) * core;
  float a = (ring + inner + core * 0.5) * uFade * step(d, 1.);
  gl_FragColor = vec4(col, a);
}`;

// =========================================================== afterimage ghost
const GHOST_VS = /* glsl */`
varying vec3 vN; varying vec3 vW;
void main(){ vN = normal; vW = position; gl_Position = projectionMatrix * viewMatrix * vec4(position, 1.); }`;
const GHOST_FS = /* glsl */`
uniform vec3 uColor; uniform float uAlpha;
varying vec3 vN; varying vec3 vW;
void main(){
  vec3 n = normalize(vN);
  vec3 v = normalize(cameraPosition - vW);
  float f = 1. - abs(dot(n, v));
  float rim = pow(f, 2.2);
  vec3 col = mix(uColor * 1.6, vec3(3.), pow(f, 6.));
  gl_FragColor = vec4(col, uAlpha * (0.07 + 0.93 * rim));
}`;

// =========================================================== aura flames
const AURA_VS = /* glsl */`
attribute vec2 aCorner; attribute float aSeed; attribute float aAng; attribute float aRad; attribute float aH; attribute float aKind;
uniform float uTime, uR, uHgt, uLevel;
varying vec2 vC; varying float vSeed; varying float vKind;
void main(){
  vC = aCorner; vSeed = aSeed; vKind = aKind;
  vec3 wp;
  if (aKind > .5) {
    // flat glow disc on the ground
    vec3 o = (modelMatrix * vec4(0., 0.03, 0., 1.)).xyz;
    wp = o + vec3(aCorner.x, 0., aCorner.y * 2. - 1.) * uR * 2.4;
  } else {
    float ang = aAng + uTime * (0.35 + aSeed * 0.3);
    vec3 c = vec3(cos(ang) * aRad * uR, uHgt * (0.02 + 0.06 * aSeed), sin(ang) * aRad * uR);
    vec3 wc = (modelMatrix * vec4(c, 1.)).xyz;
    vec3 toCam = cameraPosition - wc; toCam.y = 0.;
    vec3 right = normalize(cross(vec3(0., 1., 0.), normalize(toCam + vec3(1e-4, 0., 0.))));
    float pulse = 0.72 + 0.28 * sin(uTime * (5. + aSeed * 4.) + aSeed * 30.);
    float h = uHgt * aH * pulse * (0.55 + 0.45 * uLevel);
    float w = uR * (0.42 + 0.25 * aSeed);
    wp = wc + right * aCorner.x * w + vec3(0., aCorner.y * h, 0.);
  }
  gl_Position = projectionMatrix * viewMatrix * vec4(wp, 1.);
}`;
const AURA_FS = /* glsl */`
uniform float uTime, uLevel; uniform vec3 uColor;
varying vec2 vC; varying float vSeed; varying float vKind;
float h12(vec2 p){ vec3 p3 = fract(vec3(p.xyx) * .1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
float n2(vec2 p){ vec2 i = floor(p), f = fract(p); f = f*f*(3.-2.*f);
  return mix(mix(h12(i), h12(i + vec2(1,0)), f.x), mix(h12(i + vec2(0,1)), h12(i + vec2(1,1)), f.x), f.y); }
void main(){
  if (vKind > .5) {
    float d = length(vec2(vC.x, vC.y * 2. - 1.));
    float g = exp(-d * d * 5.) * (0.8 + 0.2 * sin(uTime * 9.));
    gl_FragColor = vec4(uColor * 1.6 + vec3(0.6) * g, g * 0.55 * uLevel);
    return;
  }
  vec2 p = vC;            // x -1..1, y 0..1
  float t = uTime * 2.2 + vSeed * 17.;
  float n = n2(vec2(p.x * 1.6 + vSeed * 9., p.y * 2.6 - t));
  float n2v = n2(vec2(p.x * 4.2 + vSeed * 3., p.y * 5.5 - t * 1.7));
  float x = p.x + (n - .5) * 1.1 * p.y + (n2v - .5) * 0.35 * p.y;
  float w = pow(1. - p.y, 0.9) * (0.28 + 0.34 * n);
  float body = (1. - smoothstep(w * 0.25, w, abs(x))) * smoothstep(0., 0.15, p.y);
  float core = (1. - smoothstep(0., w * 0.45, abs(x))) * pow(1. - p.y, 2.2);
  // licks break off near the top; never touch the quad's side edges
  body *= smoothstep(0.3, 0.6, n2v + (1. - p.y) * 0.55) * (1. - smoothstep(0.75, 0.98, abs(p.x)));
  vec3 col = uColor * 1.5 * body + vec3(1.9) * core;
  gl_FragColor = vec4(col, (body * 0.6 + core * 0.8) * uLevel);
}`;

function auraGeometry(N) {
  const Q = N + 1; // N tongues + 1 ground disc
  const corner = new Float32Array(Q * 4 * 2), seed = new Float32Array(Q * 4), ang = new Float32Array(Q * 4);
  const rad = new Float32Array(Q * 4), hh = new Float32Array(Q * 4), kind = new Float32Array(Q * 4);
  const idx = [];
  const C = [-1, 0, 1, 0, -1, 1, 1, 1];
  for (let q = 0; q < Q; q++) {
    const s = Math.random(), a = (q / N) * TAU + Math.random() * 0.5;
    const r = 0.45 + Math.random() * 0.55, h = 0.7 + Math.random() * 0.55;
    for (let k = 0; k < 4; k++) {
      const v = q * 4 + k;
      if (q === N) { corner[v * 2] = C[k * 2]; corner[v * 2 + 1] = C[k * 2 + 1]; kind[v] = 1; }
      else { corner[v * 2] = C[k * 2]; corner[v * 2 + 1] = C[k * 2 + 1]; kind[v] = 0; }
      seed[v] = s; ang[v] = a; rad[v] = r; hh[v] = h;
    }
    const v = q * 4;
    idx.push(v, v + 1, v + 2, v + 2, v + 1, v + 3);
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.BufferAttribute(new Float32Array(Q * 4 * 3), 3));
  g.setAttribute('aCorner', new THREE.BufferAttribute(corner, 2));
  g.setAttribute('aSeed', new THREE.BufferAttribute(seed, 1));
  g.setAttribute('aAng', new THREE.BufferAttribute(ang, 1));
  g.setAttribute('aRad', new THREE.BufferAttribute(rad, 1));
  g.setAttribute('aH', new THREE.BufferAttribute(hh, 1));
  g.setAttribute('aKind', new THREE.BufferAttribute(kind, 1));
  g.setIndex(idx);
  return g;
}

// =========================================================== FX
export class FX {
  constructor(scene) {
    this.scene = scene;
    this.time = 0;
    this.dt = 1 / 60;
    this.frame = 0;

    this.glow = new PointPool(3000, { additive: true, core: 0.9, hard: 0 });
    this.ink = new PointPool(900, { additive: false, core: 0, hard: 1 });
    this.streaks = new StreakPool(1000);
    scene.add(this.glow.points, this.ink.points, this.streaks.mesh);

    // slashes
    const cg = crescentGeometry();
    this.slashes = [];
    for (let i = 0; i < 10; i++) {
      const u = {
        uHead: { value: 0 }, uLen: { value: 0.95 }, uThin: { value: 0 }, uFade: { value: 0 },
        uFlip: { value: 0 }, uSeed: { value: i }, uColor: { value: new THREE.Color() },
      };
      const m = new THREE.ShaderMaterial({
        uniforms: u, vertexShader: SLASH_VS, fragmentShader: SLASH_FS,
        transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, side: THREE.DoubleSide,
      });
      const mesh = new THREE.Mesh(cg, m);
      mesh.visible = false; mesh.frustumCulled = false; mesh.renderOrder = 12;
      scene.add(mesh);
      this.slashes.push({ mesh, u, t: -1 });
    }
    this.slashCursor = 0;

    // shockwaves
    const rg = new THREE.PlaneGeometry(2, 2);
    rg.rotateX(-Math.PI / 2);
    this.rings = [];
    for (let i = 0; i < 8; i++) {
      const u = { uP: { value: 0 }, uFade: { value: 0 }, uColor: { value: new THREE.Color() } };
      const m = new THREE.ShaderMaterial({
        uniforms: u, vertexShader: RING_VS, fragmentShader: RING_FS,
        transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, side: THREE.DoubleSide,
        polygonOffset: true, polygonOffsetFactor: -2, polygonOffsetUnits: -2,
      });
      const mesh = new THREE.Mesh(rg, m);
      mesh.visible = false; mesh.frustumCulled = false; mesh.renderOrder = 8;
      scene.add(mesh);
      this.rings.push({ mesh, u, t: -1, R: 1, dur: 0.45 });
    }
    this.ringCursor = 0;

    this.ghostSets = new Map(); // obj3d -> { meshes, ghosts[], cursor }
    this.ghostList = [];
    this.auras = new Map();     // obj3d -> rig
    this.auraList = [];
  }

  // ------------------------------------------------------------ bursts
  burst(kind, pos, dir = null, count = null) {
    const G = this.glow, I = this.ink, S = this.streaks;
    const px = pos.x, py = pos.y, pz = pos.z;
    const n = (def) => (count == null ? def : count);
    switch (kind) {
      case 'spark': {
        for (let i = 0, N = n(24); i < N; i++) {
          coneDir(_v, dir, 0.9); const sp = rand(6, 17);
          _c2.setRGB(3, 1.35, 0.45).lerp(_c.setRGB(3.2, 2.8, 2.2), Math.random() * 0.7);
          S.spawn(px, py, pz, _v.x * sp, _v.y * sp + 2, _v.z * sp, rand(0.16, 0.42), rand(0.018, 0.035), 0.028, _c2, 1, 2.5, 16);
        }
        G.spawn(px, py, pz, 0, 0, 0, 0.08, 1.1, 1.6, _c2.setRGB(3, 2.2, 1.4), 1);
        for (let i = 0; i < 6; i++) { randUnit(_v); G.spawn(px, py, pz, _v.x * 3, _v.y * 3, _v.z * 3, rand(0.15, 0.3), 0.14, 0.02, _c2.setRGB(3, 1.6, 0.6), 1, 3, 4); }
        break;
      }
      case 'ink': {
        for (let i = 0, N = n(22); i < N; i++) {
          coneDir(_v, dir, 0.75); const sp = rand(2.5, 8);
          const v = 0.7 + Math.random() * 0.5;
          _c2.setRGB(0.20 * v, 0.006 * v, 0.02 * v);
          I.spawn(px, py, pz, _v.x * sp, _v.y * sp + 1.5, _v.z * sp, rand(0.45, 0.9), rand(0.1, 0.26), rand(0.02, 0.08), _c2, 1, 1.6, 13);
        }
        for (let i = 0; i < 10; i++) {
          coneDir(_v, dir, 0.5); const sp = rand(8, 14);
          I.spawn(px, py, pz, _v.x * sp, _v.y * sp + 2, _v.z * sp, rand(0.3, 0.55), 0.05, 0.02, _c2.setRGB(0.26, 0.01, 0.025), 1, 1, 16);
        }
        break;
      }
      case 'aura': {
        for (let i = 0, N = n(30); i < N; i++) {
          randUnit(_v); const r = Math.random() * 0.5;
          const sp = rand(1.5, 3.8);
          _c2.copy(CYAN).multiplyScalar(rand(1.6, 3.2));
          G.spawn(px + _v.x * r, py + _v.y * r * 0.6, pz + _v.z * r, _v.x * 0.8, sp, _v.z * 0.8, rand(0.5, 1.0), rand(0.1, 0.24), 0.01, _c2, 1, 1.2, -1.5);
        }
        break;
      }
      case 'dust': {
        for (let i = 0, N = n(14); i < N; i++) {
          const a = Math.random() * TAU, sp = rand(0.8, 3.2);
          let vx = Math.cos(a) * sp, vz = Math.sin(a) * sp;
          if (dir) { vx += dir.x * 2; vz += dir.z * 2; }
          const v = 0.8 + Math.random() * 0.3;
          _c2.setRGB(0.19 * v, 0.16 * v, 0.17 * v);
          I.spawn(px + Math.cos(a) * 0.3, py + 0.15, pz + Math.sin(a) * 0.3, vx, rand(0.3, 1.1), vz, rand(0.8, 1.4), rand(0.35, 0.6), rand(1.2, 2.0), _c2, 0.4, 2.8, -0.3);
        }
        break;
      }
      case 'flow': {
        for (let i = 0, N = n(12); i < N; i++) {
          const a = Math.random() * TAU, r = rand(0.3, 0.7);
          _c2.copy(CYAN).lerp(_c.setRGB(1, 1, 1), Math.random() * 0.5).multiplyScalar(2.6);
          G.spawn(px + Math.cos(a) * r, py + rand(-0.3, 0.4), pz + Math.sin(a) * r, 0, rand(1.2, 2.6), 0, rand(0.45, 0.7), 0.08, 0.02, _c2, 1, 0.5, 0, 1.4);
        }
        break;
      }
      case 'reset': {
        const N = n(36);
        for (let i = 0; i < N; i++) {
          const a = (i / N) * TAU;
          _c2.copy(CYAN).lerp(_c.setRGB(1, 1, 1), 0.35).multiplyScalar(3);
          G.spawn(px, py, pz, Math.cos(a) * 7, rand(-0.3, 0.3), Math.sin(a) * 7, 0.38, 0.13, 0.03, _c2, 1, 4);
        }
        for (let i = 0; i < 10; i++) {
          coneDir(_v, _v2.set(0, 1, 0), 0.5); const sp = rand(5, 10);
          S.spawn(px, py, pz, _v.x * sp, _v.y * sp, _v.z * sp, rand(0.2, 0.35), 0.03, 0.035, _c2.copy(CYAN).multiplyScalar(3), 1, 3, 4);
        }
        G.spawn(px, py, pz, 0, 0, 0, 0.12, 0.8, 2.2, _c2.setRGB(1.6, 3.2, 4), 1);
        break;
      }
      case 'deflect': {
        for (let i = 0, N = n(20); i < N; i++) {
          coneDir(_v, dir, 0.55); const sp = rand(11, 24);
          _c2.copy(CYAN).lerp(_c.setRGB(1, 1, 1), 0.3 + Math.random() * 0.7).multiplyScalar(3.5);
          S.spawn(px, py, pz, _v.x * sp, _v.y * sp, _v.z * sp, rand(0.12, 0.28), rand(0.02, 0.04), 0.022, _c2, 1, 4, 6);
        }
        G.spawn(px, py, pz, 0, 0, 0, 0.09, 1.3, 2.0, _c2.setRGB(3, 3.6, 4), 1);
        for (let i = 0; i < 6; i++) { randUnit(_v); G.spawn(px, py, pz, _v.x * 2, _v.y * 2, _v.z * 2, 0.25, 0.1, 0.02, _c2.copy(CYAN).multiplyScalar(3), 1, 3); }
        break;
      }
      case 'glass': {
        for (let i = 0, N = n(30); i < N; i++) {
          coneDir(_v, dir, 1.0); const sp = rand(3, 12);
          _c2.setRGB(0.7, 0.95, 1).lerp(CYAN, Math.random() * 0.5).multiplyScalar(rand(1.8, 3.4));
          S.spawn(px, py, pz, _v.x * sp, _v.y * sp + 2.5, _v.z * sp, rand(0.45, 0.95), rand(0.025, 0.05), 0.016, _c2, 1, 1.2, 18, 1);
        }
        for (let i = 0; i < 14; i++) {
          randUnit(_v); const sp = rand(1, 6);
          G.spawn(px, py, pz, _v.x * sp, _v.y * sp + 1, _v.z * sp, rand(0.3, 0.7), 0.06, 0.02, _c2.setRGB(2.4, 3, 3.4), 1, 1.5, 9, 0, 1);
        }
        break;
      }
      default:
        console.warn('[fx] unknown burst kind', kind);
    }
  }

  // ------------------------------------------------------------ slash
  /**
   * Anime slash arc centred on pos (use the actor's chest), facing yaw.
   * dir: 'left' sweeps right->left of the actor, 'right' the reverse, 'over' top->down.
   * pitch: positive tilts the arc upward (radians).
   */
  slash(pos, yaw, pitch = 0, dir = 'right', color, scale = 1) {
    const s = this.slashes[this.slashCursor];
    this.slashCursor = (this.slashCursor + 1) % this.slashes.length;
    setColor(s.u.uColor.value, color);
    s.mesh.position.copy(pos);
    let roll = 0, flip = 0, lift = 0, yawOff = 0;
    // overhead: a vertical plane through the actor is edge-on to a chase camera, so cant it
    // into a steep diagonal and turn it a little off the facing so the crescent shows its face
    if (dir === 'over') { roll = Math.PI / 2 - 0.4 + (Math.random() - 0.5) * 0.15; flip = 1; yawOff = 0.55; }
    // yaw 0 faces +Z, +X is the actor's LEFT: 'left' means the blade travels -X -> +X.
    // Horizontal arcs are rolled into a diagonal and their far edge lifted so the crescent
    // faces a camera behind/above the actor instead of collapsing edge-on into a line.
    else if (dir === 'left') { roll = -0.5 + (Math.random() - 0.5) * 0.2; flip = 0; lift = 0.5; }
    else { roll = 0.5 + (Math.random() - 0.5) * 0.2; flip = 1; lift = 0.5; }
    s.mesh.rotation.set(-pitch - lift, yaw + yawOff, roll, 'YXZ');
    s.mesh.scale.set(1.55 * scale, 1.55 * scale, 1.55 * scale);
    s.u.uFlip.value = flip;
    s.u.uSeed.value = Math.random() * 10;
    s.t = 0;
    s.mesh.visible = true;
    this._updSlash(s);
  }
  _updSlash(s) {
    const t = s.t;
    s.u.uHead.value = Math.min(1, t / 0.075) * 1.02;
    s.u.uThin.value = Math.max(0, (t - 0.07) / 0.13);
    s.u.uFade.value = 1 - Math.max(0, Math.min(1, (t - 0.1) / 0.1));
    if (t > 0.2) { s.mesh.visible = false; s.t = -1; }
  }

  // ------------------------------------------------------------ shockwave
  shockwave(pos, radius = 4, color) {
    const r = this.rings[this.ringCursor];
    this.ringCursor = (this.ringCursor + 1) % this.rings.length;
    setColor(r.u.uColor.value, color);
    r.mesh.position.set(pos.x, pos.y + 0.05, pos.z);
    r.R = radius; r.t = 0; r.dur = 0.3 + Math.min(0.4, radius * 0.04);
    r.mesh.scale.setScalar(radius);
    r.mesh.visible = true;
    r.u.uP.value = 0; r.u.uFade.value = 1;
  }

  // ------------------------------------------------------------ afterimage
  _ghostSet(obj) {
    let set = this.ghostSets.get(obj);
    if (set) return set;
    obj.updateMatrixWorld(true);
    const meshes = [];
    let V = 0, I = 0;
    obj.traverse((m) => {
      if (!m.isMesh || m.isInstancedMesh || m.isSkinnedMesh) return;
      const mat = Array.isArray(m.material) ? m.material[0] : m.material;
      if (mat && mat.side === THREE.BackSide) return;           // inverted-hull outline
      if (mat && mat.blending === THREE.AdditiveBlending) return; // glow cards etc.
      const g = m.geometry;
      if (!g || !g.attributes.position) return;
      const cnt = g.attributes.position.count;
      const ic = g.index ? g.index.count : cnt;
      meshes.push({ m, g, v0: V, cnt });
      V += cnt; I += ic;
    });
    const index = V > 65535 ? new Uint32Array(I) : new Uint16Array(I);
    let o = 0;
    for (const e of meshes) {
      if (e.g.index) { const src = e.g.index.array; for (let k = 0; k < src.length; k++) index[o++] = src[k] + e.v0; }
      else for (let k = 0; k < e.cnt; k++) index[o++] = e.v0 + k;
    }
    const idxAttr = new THREE.BufferAttribute(index, 1);
    const ghosts = [];
    for (let k = 0; k < 6; k++) {
      const g = new THREE.BufferGeometry();
      const pa = new THREE.BufferAttribute(new Float32Array(V * 3), 3); pa.setUsage(THREE.DynamicDrawUsage);
      const na = new THREE.BufferAttribute(new Float32Array(V * 3), 3); na.setUsage(THREE.DynamicDrawUsage);
      g.setAttribute('position', pa); g.setAttribute('normal', na);
      g.setIndex(idxAttr);
      const u = { uColor: { value: new THREE.Color() }, uAlpha: { value: 0 } };
      const mat = new THREE.ShaderMaterial({
        uniforms: u, vertexShader: GHOST_VS, fragmentShader: GHOST_FS,
        transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
      });
      const mesh = new THREE.Mesh(g, mat);
      mesh.frustumCulled = false; mesh.visible = false; mesh.renderOrder = 7;
      mesh.matrixAutoUpdate = false;
      this.scene.add(mesh);
      ghosts.push({ mesh, u, t: -1, dur: 0.35 });
    }
    set = { obj, meshes, ghosts, cursor: 0 };
    this.ghostSets.set(obj, set);
    this.ghostList.push(set);
    return set;
  }
  afterimage(obj3d, color) {
    if (!obj3d) return;
    const set = this._ghostSet(obj3d);
    if (!set.meshes.length) return;
    const gh = set.ghosts[set.cursor];
    set.cursor = (set.cursor + 1) % set.ghosts.length;
    obj3d.updateMatrixWorld(true);
    const P = gh.mesh.geometry.attributes.position.array, N = gh.mesh.geometry.attributes.normal.array;
    for (const e of set.meshes) {
      const m = e.m;
      // still attached and visible?
      let live = true, p = m;
      while (p && p !== obj3d) { if (!p.visible) { live = false; break; } p = p.parent; }
      if (p !== obj3d) live = false;
      const src = e.g.attributes.position, sn = e.g.attributes.normal;
      if (!live) { for (let k = 0; k < e.cnt; k++) { const o = (e.v0 + k) * 3; P[o] = P[o + 1] = P[o + 2] = 0; } continue; }
      const me = m.matrixWorld.elements;
      _m3.getNormalMatrix(m.matrixWorld);
      const ne = _m3.elements;
      for (let k = 0; k < e.cnt; k++) {
        const x = src.getX(k), y = src.getY(k), z = src.getZ(k);
        const o = (e.v0 + k) * 3;
        P[o] = me[0] * x + me[4] * y + me[8] * z + me[12];
        P[o + 1] = me[1] * x + me[5] * y + me[9] * z + me[13];
        P[o + 2] = me[2] * x + me[6] * y + me[10] * z + me[14];
        if (sn) {
          const nx = sn.getX(k), ny = sn.getY(k), nz = sn.getZ(k);
          N[o] = ne[0] * nx + ne[3] * ny + ne[6] * nz;
          N[o + 1] = ne[1] * nx + ne[4] * ny + ne[7] * nz;
          N[o + 2] = ne[2] * nx + ne[5] * ny + ne[8] * nz;
        } else { N[o] = 0; N[o + 1] = 1; N[o + 2] = 0; }
      }
    }
    gh.mesh.geometry.attributes.position.needsUpdate = true;
    gh.mesh.geometry.attributes.normal.needsUpdate = true;
    setColor(gh.u.uColor.value, color);
    gh.u.uAlpha.value = 1;
    gh.t = 0;
    gh.mesh.visible = true;
  }

  // ------------------------------------------------------------ aura
  _auraRig(obj) {
    let rig = this.auras.get(obj);
    if (rig) return rig;
    const box = new THREE.Box3().setFromObject(obj);
    obj.updateMatrixWorld(true);
    const wp = new THREE.Vector3().setFromMatrixPosition(obj.matrixWorld);
    const h = obj.userData.height || Math.max(0.5, box.max.y - wp.y);
    const r = obj.userData.radius || Math.max(0.3, Math.min(1.8, Math.max(box.max.x - box.min.x, box.max.z - box.min.z) * 0.5));
    const u = {
      uTime: { value: 0 }, uR: { value: r }, uHgt: { value: h * 1.1 }, uLevel: { value: 0 },
      uColor: { value: CYAN.clone() },
    };
    const m = new THREE.ShaderMaterial({
      uniforms: u, vertexShader: AURA_VS, fragmentShader: AURA_FS,
      transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, side: THREE.DoubleSide,
    });
    const mesh = new THREE.Mesh(auraGeometry(24), m);
    mesh.frustumCulled = false; mesh.visible = false; mesh.renderOrder = 6;
    this.scene.add(mesh);
    rig = { obj, mesh, u, h, r, target: 0, shown: 0, last: -10, emit: 0 };
    this.auras.set(obj, rig);
    this.auraList.push(rig);
    return rig;
  }
  auraBody(obj3d, level = 1) {
    if (!obj3d) return;
    const rig = this._auraRig(obj3d);
    rig.target = Math.max(0, Math.min(1, level));
    rig.last = this.frame;
    obj3d.getWorldPosition(rig.mesh.position);
    // rising motes from the body
    rig.emit += this.dt * 38 * rig.target;
    while (rig.emit >= 1) {
      rig.emit -= 1;
      const a = Math.random() * TAU, rr = rig.r * rand(0.3, 0.9);
      _c2.copy(CYAN).lerp(_c.setRGB(1, 1, 1), Math.random() * 0.4).multiplyScalar(rand(1.8, 3));
      const p = rig.mesh.position;
      this.glow.spawn(p.x + Math.cos(a) * rr, p.y + Math.random() * rig.h, p.z + Math.sin(a) * rr,
        Math.cos(a) * 0.3, rand(1.2, 2.8), Math.sin(a) * 0.3, rand(0.4, 0.8), rand(0.05, 0.11), 0.01, _c2, 1, 0.8, -1);
    }
  }

  // ------------------------------------------------------------ beam
  beam(from, to, color, width = 0.12) {
    setColor(_c2, color).multiplyScalar(3);
    this.streaks.beam(from, to, 0.26, width, _c2, 1);
  }

  // ------------------------------------------------------------ update
  update(dt) {
    dt = Math.max(0, Math.min(dt, 0.1));  // a negative dt parks a ghost at t<0: visible, never faded
    this.dt = dt || this.dt;
    this.time += dt;
    this.frame++;
    const time = this.time;
    this.glow.update(dt, time);
    this.ink.update(dt, time);
    this.streaks.update(dt, time);

    for (const s of this.slashes) if (s.t >= 0) { s.t += dt; this._updSlash(s); }

    for (const r of this.rings) {
      if (r.t < 0) continue;
      r.t += dt;
      const k = r.t / r.dur;
      if (k >= 1) { r.t = -1; r.mesh.visible = false; continue; }
      r.u.uP.value = 1 - Math.pow(1 - k, 3) * 0.92 - 0.08 * (1 - k);
      r.u.uFade.value = 1 - k * k;
    }

    for (const set of this.ghostList) {
      for (const g of set.ghosts) {
        if (g.t < 0) continue;
        g.t += dt;
        const k = g.t / g.dur;
        if (k >= 1) { g.t = -1; g.mesh.visible = false; continue; }
        g.u.uAlpha.value = (1 - k) * (1 - k) * 0.75;
      }
    }

    for (const rig of this.auraList) {
      // auraBody is called every frame; if it stopped, the aura dies away
      if (rig.last < this.frame - 1) rig.target = 0;
      rig.shown += (rig.target - rig.shown) * Math.min(1, dt * 10);
      if (rig.shown < 0.004 && rig.target === 0) { rig.shown = 0; rig.mesh.visible = false; continue; }
      rig.mesh.visible = true;
      rig.u.uLevel.value = rig.shown;
      rig.u.uTime.value = time;
    }
  }
}
