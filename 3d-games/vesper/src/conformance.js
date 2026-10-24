/* ============================================================================
   HEIGHT CONFORMANCE HARNESS.

   Renders the GPU's heightAt() into a float render target, one texel per sample
   direction, and hands the values back to the test so it can compare them
   against the CPU mirror in world/heightField.js.

   This is the guard on binding rule 3 — one height function for the mesh and
   for collision. Without it, the two implementations drift the moment anyone
   edits either, and the symptom is the player sinking through a hill, which is
   miserable to debug from the outside.

   uEps is forced to ~0 so the shader's band-limiting is fully open and every
   octave contributes, matching the CPU (which never band-limits, because what
   you stand on cannot depend on where the camera is looking).
   ========================================================================= */

import * as T from 'three';
import { NOISE } from './render/glsl/noise.glsl.js';
import { makeSurfaceUniforms, NFEAT } from './render/planetShader.js';
import { makePlanetDef } from './world/planetDef.js';
import { heightNorm } from './world/heightField.js';

const canvas = document.getElementById('cv');
const renderer = new T.WebGLRenderer({ canvas, antialias: false });
const scene = new T.Scene();
const camera = new T.OrthographicCamera(-1, 1, 1, -1, 0, 1);

/* The height shader: the SAME height field, rendered as data rather than as a
   picture. It reuses the exact source string from planetShader.js — a
   re-typed copy here would defeat the whole point of the test. */
import { HEIGHT_FIELD_SRC } from './render/planetShader.js';

const SIZE = 64;                       // 4096 sample directions per world
const dirTex = new T.DataTexture(new Float32Array(SIZE * SIZE * 4), SIZE, SIZE,
  T.RGBAFormat, T.FloatType);
// NEAREST is mandatory, not an optimisation. A float texture sampled with the
// default LinearFilter needs OES_texture_float_linear; without it every fetch
// silently returns zero, the probe reads back all zeros, and it looks exactly
// like the shader computing the wrong thing. This harness did that for two runs.
dirTex.minFilter = T.NearestFilter;
dirTex.magFilter = T.NearestFilter;
dirTex.generateMipmaps = false;
dirTex.needsUpdate = true;

const VERT_SRC = `varying vec2 vUv;
    void main(){ vUv = uv; gl_Position = vec4(position.xy, 0.0, 1.0); }`;

const FRAG_SRC = `
    precision highp float;
    varying vec2 vUv;
    uniform sampler2D uDirs;
    uniform int uMode;
    ${NOISE}
    ${HEIGHT_FIELD_SRC}

    /* A copy of snoise's setup that EXPOSES its intermediates, so a CPU/GPU
       disagreement can be traced to the exact step that diverges instead of
       being re-read in two languages until someone spots it. */
    vec4 snoiseProbe(vec3 v, int what){
      const vec2 C = vec2(1.0/6.0, 1.0/3.0);
      vec3 i  = floor(v + dot(v, C.yyy));
      vec3 x0 = v - i + dot(i, C.xxx);
      vec3 g = step(x0.yzx, x0.xyz);
      vec3 l = 1.0 - g;
      vec3 i1 = min(g.xyz, l.zxy);
      vec3 i2 = max(g.xyz, l.zxy);
      vec3 im = mod289(i);
      vec4 p = permute( permute( permute(
                 im.z + vec4(0.0, i1.z, i2.z, 1.0))
               + im.y + vec4(0.0, i1.y, i2.y, 1.0))
               + im.x + vec4(0.0, i1.x, i2.x, 1.0));
      if(what == 0) return vec4(i, 0.0);
      if(what == 1) return vec4(i1, 0.0);
      if(what == 2) return vec4(i2, 0.0);
      if(what == 3) return p;
      return vec4(x0, 0.0);
    }
    void main(){
      // Fully open the band-limit. gEps used to be a uniform; it is a global
      // now, because the cube bake needs a different value at every texel.
      // Physics never band-limits — what you stand on cannot depend on where
      // the camera is — so this harness sets it to nothing at all.
      gEps = 1e-7;
      vec3 d = normalize(texture2D(uDirs, vUv).xyz);
      float v = 0.0;
      // Probe modes exist so a disagreement can be BISECTED to one primitive
      // instead of stared at. Every one of these has a byte-identical CPU
      // counterpart in tests/conformance.mjs.
      if(uMode == 1)      v = snoise(d*7.3);
      else if(uMode == 2) v = fbmB(d*3.1, 3.1, 2);
      else if(uMode == 3) v = crackMask(d*9.0, 0.1);
      else if(uMode == 4) v = warp2(d*2.0, 0.7).x;
      else if(uMode == 5) v = ridgedB(d*5.0, 5.0, 2.4, 3);
      else if(uMode == 6) v = warp1(d*2.0, 0.7).x;
      // mode 7 checks the HARNESS itself: does texel i on the GPU carry the
      // same direction the CPU thinks index i has? If this differs, nothing
      // downstream means anything.
      // mode 9 asks the shader what gEps ACTUALLY IS at the point of use —
      // i.e. did the assignment three lines above this survive the compiler?
      else if(uMode == 9) v = gEps;
      // 20+ read a WORLD UNIFORM straight back out. Every primitive can match
      // perfectly and heightAt still be wrong, because bisect_noise probes use
      // hardcoded frequencies and never touch a uniform at all.
      else if(uMode == 20) v = uContFreq;
      else if(uMode == 21) v = uSeedOff.x;
      else if(uMode == 22) v = uCrackFreq;
      else if(uMode == 23) v = uMtnFreq;
      else if(uMode == 24) v = uFeatParam[0].x;
      else if(uMode == 7) v = d.x;
      else if(uMode == 8) v = d.y;
      else if(uMode == 10) v = snoiseProbe(d*7.3, 0).x;   // lattice cell i.x
      else if(uMode == 11) v = snoiseProbe(d*7.3, 1).x;   // corner offset i1.x
      else if(uMode == 12) v = snoiseProbe(d*7.3, 2).x;   // corner offset i2.x
      else if(uMode == 13) v = snoiseProbe(d*7.3, 3).x;   // permutation p.x
      else if(uMode == 14) v = snoiseProbe(d*7.3, 3).y;   // permutation p.y
      else if(uMode == 15) v = snoiseProbe(d*7.3, 4).x;   // x0.x
      else                v = heightAt(d);
      gl_FragColor = vec4(v, 0.0, 0.0, 1.0);
    }`;

const quad = new T.Mesh(new T.PlaneGeometry(2, 2), null);
scene.add(quad);

const rt = new T.WebGLRenderTarget(SIZE, SIZE, {
  type: T.FloatType, format: T.RGBAFormat,
  minFilter: T.NearestFilter, magFilter: T.NearestFilter,
});

/** evaluate the GPU height field at `dirs` (Float32Array of xyz triples)
 *
 *  A WHOLE NEW MATERIAL, every call, with the world's uniforms present at
 *  CONSTRUCTION. This used to keep one material and bolt the world's uniforms
 *  onto `mat.uniforms` afterwards, which is the exact trap that made the bake
 *  render the wrong planet (DECISIONS, 2026-08-01): three binds the uniform
 *  list to the program the first time the material is drawn, and `needsUpdate`
 *  did not save it. Every world uniform arrived at the shader as ZERO.
 *
 *  What made that so hard to see is that it does not look like a broken
 *  harness — it looks like the height field itself disagreeing by 12-21% of
 *  relief, and `bisect_noise.mjs` reports every primitive matching perfectly,
 *  because those probes use hardcoded frequencies and never read a uniform.
 *  Building the material fresh costs nothing: three caches programs by source.
 */
function gpuHeights(def, dirs, mode = 0) {
  const old = quad.material;
  quad.material = new T.ShaderMaterial({
    vertexShader: VERT_SRC,
    fragmentShader: FRAG_SRC,
    uniforms: Object.assign({}, makeSurfaceUniforms(def), {
      uDirs: { value: dirTex }, uMode: { value: mode },
    }),
  });
  if (old) old.dispose();
  const mat = quad.material;

  const d = dirTex.image.data;
  for (let i = 0; i < SIZE * SIZE; i++) {
    d[i * 4] = dirs[i * 3]; d[i * 4 + 1] = dirs[i * 3 + 1];
    d[i * 4 + 2] = dirs[i * 3 + 2]; d[i * 4 + 3] = 1;
  }
  dirTex.needsUpdate = true;

  renderer.setRenderTarget(rt);
  renderer.render(scene, camera);
  const out = new Float32Array(SIZE * SIZE * 4);
  renderer.readRenderTargetPixels(rt, 0, 0, SIZE, SIZE, out);
  renderer.setRenderTarget(null);

  const h = new Float32Array(SIZE * SIZE);
  for (let i = 0; i < SIZE * SIZE; i++) h[i] = out[i * 4];
  return h;
}

/** deterministic spread of directions over the sphere (Fibonacci lattice) */
function makeDirs(N) {
  const dirs = new Float32Array(N * 3);
  const ga = Math.PI * (3 - Math.sqrt(5));
  for (let i = 0; i < N; i++) {
    const y = 1 - (i / (N - 1)) * 2;
    const r = Math.sqrt(Math.max(1 - y * y, 0));
    const a = ga * i;
    dirs[i * 3] = Math.cos(a) * r; dirs[i * 3 + 1] = y; dirs[i * 3 + 2] = Math.sin(a) * r;
  }
  return dirs;
}

window.__C = {
  SIZE, N: SIZE * SIZE,
  ready: true,

  /* THE HARNESS MUST BE ABLE TO SAY IT IS ALIVE. A lost WebGL context reads
     back as all-zeros, which does not look like a broken instrument — it looks
     exactly like the height field disagreeing by 12-21% of relief, and it cost
     a whole session under that misreading (DECISIONS, 2026-08-05). Neither of
     these is diagnostic scaffolding; conformance.mjs asserts on both. */
  contextLost: () => renderer.getContext().isContextLost(),
  rendererName: (() => {
    const gl = renderer.getContext();
    const d = gl.getExtension('WEBGL_debug_renderer_info');
    return d ? gl.getParameter(d.UNMASKED_RENDERER_WEBGL) : 'unknown';
  })(),
  gl: renderer.getContext(),
  floatRT: !!renderer.getContext().getExtension('EXT_color_buffer_float'),

  /** raw GPU values for a probe mode — the test computes the CPU side itself */
  probe(mode) {
    const def = makePlanetDef('PROBE', 'volcanic');
    const dirs = makeDirs(SIZE * SIZE);
    return { dirs: Array.from(dirs), gpu: Array.from(gpuHeights(def, dirs, mode)) };
  },

  /** run the comparison for one world; returns summary stats */
  compare(seed, arch) {
    const def = makePlanetDef(seed, arch);
    if (def.art.isGas) return { skipped: 'gas giant has no terrain' };

    // deterministic spread of directions over the sphere (Fibonacci lattice)
    const N = SIZE * SIZE;
    const dirs = new Float32Array(N * 3);
    const ga = Math.PI * (3 - Math.sqrt(5));
    for (let i = 0; i < N; i++) {
      const y = 1 - (i / (N - 1)) * 2;
      const r = Math.sqrt(Math.max(1 - y * y, 0));
      const a = ga * i;
      dirs[i * 3] = Math.cos(a) * r; dirs[i * 3 + 1] = y; dirs[i * 3 + 2] = Math.sin(a) * r;
    }

    const g = gpuHeights(def, dirs);
    const errs = new Float64Array(N);
    let sumAbs = 0, cpuRange = [1e9, -1e9];
    // The GPU's own spread. The test already refuses a CONSTANT CPU mirror
    // (which would "agree" with anything); this is the same guard on the other
    // side, and it is the one that was missing when a dead context returned
    // 4096 zeros and the failure was read as a real divergence.
    let gpuRange = [1e9, -1e9];
    for (let i = 0; i < N; i++) {
      gpuRange[0] = Math.min(gpuRange[0], g[i]);
      gpuRange[1] = Math.max(gpuRange[1], g[i]);
    }
    const d3 = [0, 0, 0];
    for (let i = 0; i < N; i++) {
      d3[0] = dirs[i * 3]; d3[1] = dirs[i * 3 + 1]; d3[2] = dirs[i * 3 + 2];
      const c = heightNorm(def, d3);
      cpuRange[0] = Math.min(cpuRange[0], c);
      cpuRange[1] = Math.max(cpuRange[1], c);
      errs[i] = Math.abs(c - g[i]);
      sumAbs += errs[i];
    }
    // Percentiles, not just the max. The residual disagreement is concentrated
    // entirely at crack boundaries, where a cell-jitter difference of one part
    // in ten thousand moves a canyon WALL by its full depth. That is one pixel
    // of edge, not a broken height field, and a max-only assertion cannot tell
    // the two apart.
    const sorted = Array.from(errs).sort((a, b) => a - b);
    const pct = (p) => sorted[Math.min(N - 1, Math.floor(N * p))];
    const spread = cpuRange[1] - cpuRange[0];
    return {
      key: def.key, seed: String(seed), n: N,
      meanErr: sumAbs / N, p50: pct(0.50), p95: pct(0.95), p99: pct(0.99),
      maxErr: sorted[N - 1],
      reliefM: def.relief, spread, gpuSpread: gpuRange[1] - gpuRange[0],
      // fraction of the surface where the two disagree by more than 1% of relief
      fracOff: sorted.filter(e => e > spread * 0.01).length / N,
    };
  },
};

document.getElementById('out').textContent = 'ready';
