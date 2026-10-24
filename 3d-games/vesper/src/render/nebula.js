/* ============================================================================
   THE BACKDROP — LAW 7: space is never plain black.

   Every one of the four reference images has coloured nebula behind the planet,
   and it is not decoration: it is what gives the rim glow something to read
   against and what stops the frame reading as a render on a black background.

   Two pieces:
     - a nebula dome (fBm clouds in two complementary hues + bright filaments)
     - a star field (real Points, so they twinkle in size and colour rather than
       being painted into the dome)

   This is a PLACEHOLDER for the real thing. In the assembled game the sky is
   the tiered cosmic streamer lifted from vesper.html, where every point is an
   actual galaxy or star at an actual distance that you can fly to. This module
   exists so the planet stage can be judged on its own; when the streamer lands,
   the nebula dome stays and the star Points get replaced.
   ========================================================================= */

import * as T from 'three';
import { NOISE } from './glsl/noise.glsl.js';
import { stream } from '../core/rng.js';

const VERT = /* glsl */`
varying vec3 vD;
void main(){
  vD = normalize(position);
  gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
}
`;

const FRAG = /* glsl */`
precision highp float;
varying vec3 vD;
${NOISE}
uniform vec3  uA, uB, uC, uOff;
uniform float uAmt, uScale, uContrast;

void main(){
  vec3 d = normalize(vD);
  vec3 p = d*uScale + uOff;

  // warped fBm gives billowing clouds instead of a smooth gradient
  vec3 w = warp1(p, 1.5);
  float m  = fbm5(w)*0.5 + 0.5;
  float m2 = fbm4(p*2.7 + 11.0)*0.5 + 0.5;

  float dens = pow(sat(m), uContrast);
  vec3 col = mix(uA, uB, sat(m2*1.25 - 0.1));
  col *= dens;

  /* THE FILAMENTS ARE THE PART THAT READS — the owner's words on seeing the
     volcanic sky were "the sky has red strokes, looks beautiful, but need more
     of it", and the strokes are this term. So there are now TWO of them, at
     different thresholds and powers: a broad one for the body of each thread
     and a tighter, brighter one for its core. Both are built from the SAME two
     noise fields that were already fetched (m and m2) — no extra octaves, no
     new primitives. That restraint is deliberate: a heavier backdrop program is
     what tore the gallery into rectangular tiles on the previous attempt
     (DECISIONS, "THE SKY: attempted, REVERTED"), and until that mechanism is
     understood the sky gets stronger by USING what it already computes rather
     than by computing more. */
  float fil = pow(sat(m*m2*2.1 - 0.55), 3.0);
  col += uC * fil * 1.7;
  float core = pow(sat(m*m2*2.6 - 0.80), 6.0);
  col += mix(uC, vec3(1.0), 0.45) * core * 2.6;

  gl_FragColor = vec4(col*uAmt, 1.0);
}
`;

/** returns { dome, stars } — add both to the scene */
export function makeBackdrop(seed, hues, radius = 9e7) {
  const r = stream((seed ^ 0x5eed) >>> 0);

  const g = new T.SphereGeometry(radius, 48, 32);
  const m = new T.ShaderMaterial({
    vertexShader: VERT, fragmentShader: FRAG,
    side: T.BackSide, depthWrite: false, depthTest: false,
    uniforms: {
      uA: { value: new T.Color(hues[0]) },
      uB: { value: new T.Color(hues[1]) },
      uC: { value: new T.Color(hues[2]) },
      uOff: { value: new T.Vector3(r.f() * 40, r.f() * 40, r.f() * 40) },
      // Deliberately dim and high-contrast. The backdrop's job is to stop space
      // being flat black and to give the rim glow something to sit against —
      // the moment it competes with the planet for attention it is too bright.
      // Raised from 0.24-0.50. The old range was set when the backdrop's only
      // job was to stop space being flat black; refs/SURFACE.md item 4 says the
      // sky is HALF THE PICTURE and a place you can fly to, and from the ground
      // it is most of the frame. Still well under the planet's own brightness,
      // which is the line that must not be crossed.
      uAmt: { value: 0.40 + r.f() * 0.34 },
      uScale: { value: 1.5 + r.f() * 1.1 },
      // Lowered from 3.2-5.4: the exponent is what decides how much of the sky
      // has any gas in it at all, and the reference has cloud reaching down to
      // the horizon on both sides rather than a few isolated patches.
      uContrast: { value: 2.5 + r.f() * 1.7 },
    },
  });
  m.toneMapped = false;
  const dome = new T.Mesh(g, m);
  dome.renderOrder = -100;
  dome.frustumCulled = false;

  /* ---- stars ------------------------------------------------------------ */
  /* 3200 -> 6000. This raise is ALSO a controlled experiment: the reverted sky
     rewrite changed the dome shader AND the star count together, and tore the
     gallery. Changing only the count, against the known-good dome, says which
     half was responsible — if it tears now it was the stars, and if it does not
     it was the dome program. Either answer is worth having written down. */
  const N = 6000;
  const pos = new Float32Array(N * 3), col = new Float32Array(N * 3),
        siz = new Float32Array(N);
  const c = new T.Color();
  for (let i = 0; i < N; i++) {
    const s = r.sphere();
    const R = radius * 0.985;
    pos[i * 3] = s[0] * R; pos[i * 3 + 1] = s[1] * R; pos[i * 3 + 2] = s[2] * R;
    // A wide brightness range matters more than count: a sky of identical dots
    // reads as fake even when it is technically full.
    const b = Math.pow(r.f(), 3.2);
    c.setHSL(0.52 + (r.f() - 0.5) * 0.28, 0.20 + r.f() * 0.45, 0.55 + b * 0.45);
    col[i * 3] = c.r; col[i * 3 + 1] = c.g; col[i * 3 + 2] = c.b;
    siz[i] = 1.0 + b * 4.6;
  }
  const sg = new T.BufferGeometry();
  sg.setAttribute('position', new T.BufferAttribute(pos, 3));
  sg.setAttribute('pcol', new T.BufferAttribute(col, 3));
  sg.setAttribute('psiz', new T.BufferAttribute(siz, 1));
  const sm = new T.ShaderMaterial({
    // depthTest MUST be on. A transparent material goes in three's transparent
    // queue, which draws AFTER everything opaque — so with the test off the
    // stars painted straight over the planet and you could see the sky through
    // the ground. renderOrder does not save you: it only sorts within a queue.
    transparent: true, depthWrite: false, depthTest: true,
    blending: T.AdditiveBlending,
    uniforms: { uPR: { value: 1 } },
    vertexShader: `
      attribute vec3 pcol; attribute float psiz; varying vec3 vC;
      uniform float uPR;
      void main(){ vC = pcol;
        gl_PointSize = psiz*uPR;
        gl_Position = projectionMatrix*modelViewMatrix*vec4(position,1.0); }`,
    fragmentShader: `
      precision highp float; varying vec3 vC;
      void main(){
        vec2 q = gl_PointCoord - 0.5;
        float d = length(q);
        if(d > 0.5) discard;
        float a = pow(1.0 - d*2.0, 2.2);
        gl_FragColor = vec4(vC*(0.6 + a*1.9), a);
      }`,
  });
  sm.toneMapped = false;
  const stars = new T.Points(sg, sm);
  stars.renderOrder = -99;
  stars.frustumCulled = false;

  return { dome, stars };
}

/* Palette pairs that always read as "deep space with something going on".
   Picked to be COMPLEMENTARY to the planet, never matching it — a red nebula
   behind a red planet loses the silhouette. */
export const NEBULA_SETS = {
  volcanic:  ['#4a0a12', '#7a1030', '#ff5a3a'],
  inferno:   ['#4a0a12', '#7a1030', '#ff5a3a'],
  shattered: ['#3a0a1a', '#6a1240', '#ff6a4a'],
  glacier:   ['#2a0a4a', '#0a2a5a', '#a05cff'],
  tundra:    ['#1a1a52', '#0e2a52', '#7f9cff'],
  oceanic:   ['#3a0a4a', '#5a1a10', '#c060ff'],
  verdant:   ['#2a0a4a', '#0a3a4a', '#b055ff'],
  fungal:    ['#0a2a3a', '#1a3a10', '#40ffa0'],
  crystal:   ['#0a2a4a', '#1a0a3a', '#40d0ff'],
  toxic:     ['#3a0a3a', '#12002a', '#ff40c0'],
  ashen:     ['#2a1206', '#3a1a2a', '#ff9a5a'],
  desert:    ['#0a1a3a', '#2a0a3a', '#5c9cff'],
  barren:    ['#101a2a', '#1a1030', '#6a80b0'],
  gas:       ['#20103a', '#0a2440', '#8a70ff'],
};
