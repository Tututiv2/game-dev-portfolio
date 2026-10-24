// AURA — post.js
// scene -> HalfFloat MSAA render target -> ONE fullscreen composite pass to the screen.
// The composite does, in order: radial chromatic aberration, a cheap mip-chain bloom,
// exposure + ACES (three's fit) + sRGB, tint/saturation/contrast, high-contrast B&W + grain,
// anime speed lines, impact-frame invert, knocked-down tunnel, vignette, white-out, dither.
import * as THREE from './vendor/three/three.module.js';

const VS = /* glsl */`
varying vec2 vUv;
void main(){ vUv = uv; gl_Position = vec4(position.xy, 0., 1.); }`;

const FS = /* glsl */`
uniform sampler2D tScene;
uniform vec2 uRes;
uniform float uExposure, uSat, uContrast, uGray, uInvert, uVignette, uAb, uSpeed, uFlash, uDown, uTime;
uniform float uBloom, uThresh, uMips;
uniform vec3 uTint;
varying vec2 vUv;

float h12(vec2 p){ vec3 p3 = fract(vec3(p.xyx) * .1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }

// three.js ACESFilmicToneMapping (Stephen Hill fit)
vec3 RRTAndODTFit(vec3 v){ vec3 a = v * (v + 0.0245786) - 0.000090537; vec3 b = v * (0.983729 * v + 0.4329510) + 0.238081; return a / b; }
vec3 aces(vec3 color){
  const mat3 IN = mat3(vec3(0.59719, 0.07600, 0.02840), vec3(0.35458, 0.90834, 0.13383), vec3(0.04823, 0.01566, 0.83777));
  const mat3 OUT = mat3(vec3(1.60475, -0.10208, -0.00327), vec3(-0.53108, 1.10813, -0.07276), vec3(-0.07367, -0.00605, 1.07602));
  color *= 1. / 0.6;
  color = IN * color;
  color = RRTAndODTFit(color);
  color = OUT * color;
  return clamp(color, 0., 1.);
}
vec3 toSRGB(vec3 c){ return mix(c * 12.92, 1.055 * pow(c, vec3(1. / 2.4)) - 0.055, step(0.0031308, c)); }

vec3 bloomTap(float lod){
  vec2 px = exp2(lod) / uRes;
  vec3 s = textureLod(tScene, vUv + vec2( px.x, px.y) * 0.7, lod).rgb
         + textureLod(tScene, vUv + vec2(-px.x, px.y) * 0.7, lod).rgb
         + textureLod(tScene, vUv + vec2( px.x,-px.y) * 0.7, lod).rgb
         + textureLod(tScene, vUv + vec2(-px.x,-px.y) * 0.7, lod).rgb;
  s *= 0.25;
  return max(s - uThresh, 0.);
}

void main(){
  vec2 uv = vUv;
  vec2 c = uv - .5;
  float aspect = uRes.x / uRes.y;
  vec2 p = c * vec2(aspect, 1.);
  float r = length(p);

  // radial chromatic aberration
  vec3 col;
  if (uAb > 0.0001) {
    vec2 off = c * uAb * 0.045 * (0.25 + r * 1.4);
    col.r = texture2D(tScene, uv + off).r;
    col.g = texture2D(tScene, uv).g;
    col.b = texture2D(tScene, uv - off).b;
  } else {
    col = texture2D(tScene, uv).rgb;
  }

  // bloom from the mip chain (one pass: the target builds its own mips)
  if (uBloom > 0.) {
    vec3 b = bloomTap(1.5) * 0.30 + bloomTap(3.) * 0.35 + bloomTap(4.5) * 0.45 + bloomTap(min(6., uMips)) * 0.6;
    col += b * uBloom;
  }

  col *= uExposure;
  col = aces(col);
  col = toSRGB(col);

  col *= uTint;
  float l = dot(col, vec3(.2126, .7152, .0722));
  col = max(mix(vec3(l), col, uSat), 0.);
  col = clamp((col - .5) * uContrast + .5, 0., 1.);

  // high-contrast black and white: crushed blacks, hot whites, grain
  float gA = clamp(max(uGray, uInvert), 0., 1.);
  if (gA > 0.) {
    float gl = dot(col, vec3(.2126, .7152, .0722));
    float bw = smoothstep(0.035, 0.38, gl);
    bw = bw * bw * (3. - 2. * bw);
    bw = pow(bw, 0.9);
    float fr = floor(uTime * 24.);
    float grain = h12(gl_FragCoord.xy * 0.73 + fr * vec2(37.1, 11.7)) - .5;
    bw = clamp(bw + grain * 0.12, 0., 1.);
    col = mix(col, vec3(bw), gA);
  }

  // anime speed lines rushing in from the edges, re-rolled every 1/24 s
  if (uSpeed > 0.001) {
    float fr = floor(uTime * 24.);
    float ang = atan(p.y, p.x) / 6.2831853 + .5;
    float N = 150.;
    float id = floor(ang * N);
    float h = h12(vec2(id, fr));
    float h2 = h12(vec2(id * 1.7 + 3.1, fr + 11.));
    float fa = fract(ang * N) - .5;
    float w = 0.10 + 0.38 * h2 * h2;
    float line = 1. - smoothstep(w * 0.4, w, abs(fa));
    float r0 = mix(0.62, 0.30, uSpeed) + 0.42 * h;
    float len = smoothstep(r0, r0 + 0.22, r);
    float on = step(1. - (0.35 + 0.45 * uSpeed), h2);
    float s = line * len * on * clamp(uSpeed, 0., 1.);
    col = mix(col, vec3(1.), s * 0.9);
    // a faint white pressure at the rim
    col += vec3(0.9, 0.97, 1.) * smoothstep(0.55, 1.1, r) * 0.18 * uSpeed;
  }

  // impact frame: the B&W image inverted
  col = mix(col, 1. - col, clamp(uInvert, 0., 1.));

  // knocked down: dark tunnel, slow red heartbeat pulse at the edges, a little desaturation
  if (uDown > 0.) {
    float e = smoothstep(0.12, 0.85, r);
    float t = fract(uTime * 0.75);
    float beat = exp(-t * 14.) + 0.7 * exp(-max(t - 0.18, 0.) * 14.) * step(0.18, t);
    float ll = dot(col, vec3(.2126, .7152, .0722));
    col = mix(col, vec3(ll), uDown * 0.45);
    col *= 1. - uDown * (0.18 + 0.78 * e);
    col += vec3(0.62, 0.02, 0.04) * e * uDown * (0.18 + 0.5 * beat);
  }

  col *= 1. - uVignette * smoothstep(0.35, 1.05, r);
  col = mix(col, vec3(1.), clamp(uFlash, 0., 1.));
  col += (h12(gl_FragCoord.xy + fract(uTime) * 91.) - .5) / 255.;
  gl_FragColor = vec4(col, 1.);
}`;

export class Post {
  constructor(renderer) {
    this.renderer = renderer;
    renderer.toneMapping = THREE.NoToneMapping;
    renderer.autoClear = true;
    this.u = {
      exposure: 1, sat: 1, contrast: 1, gray: 0, invert: 0, vignette: 0.35, aberration: 0,
      speed: 0, flash: 0, down: 0, tint: [1, 1, 1], time: 0,
      bloom: 0.7, // extra: bloom strength (not in the contract; the game may leave it alone)
    };
    const size = renderer.getDrawingBufferSize(new THREE.Vector2());
    this.rt = new THREE.WebGLRenderTarget(Math.max(1, size.x), Math.max(1, size.y), {
      type: THREE.HalfFloatType,
      samples: 4,
      depthBuffer: true,
      minFilter: THREE.LinearMipmapLinearFilter,
      magFilter: THREE.LinearFilter,
      generateMipmaps: true,
    });
    this.rt.texture.name = 'AURA.scene';
    this.uniforms = {
      tScene: { value: this.rt.texture },
      uRes: { value: new THREE.Vector2(size.x, size.y) },
      uExposure: { value: 1 }, uSat: { value: 1 }, uContrast: { value: 1 }, uGray: { value: 0 },
      uInvert: { value: 0 }, uVignette: { value: 0.35 }, uAb: { value: 0 }, uSpeed: { value: 0 },
      uFlash: { value: 0 }, uDown: { value: 0 }, uTime: { value: 0 }, uTint: { value: new THREE.Vector3(1, 1, 1) },
      uBloom: { value: 0.7 }, uThresh: { value: 0.85 }, uMips: { value: 6 },
    };
    this.material = new THREE.ShaderMaterial({
      uniforms: this.uniforms, vertexShader: VS, fragmentShader: FS,
      depthTest: false, depthWrite: false,
    });
    this.quad = new THREE.Mesh(new THREE.PlaneGeometry(2, 2), this.material);
    this.quad.frustumCulled = false;
    this.scene = new THREE.Scene();
    this.scene.add(this.quad);
    this.camera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0, 1);
    this._sz = new THREE.Vector2();
    this._setMips(size.x, size.y);
  }

  _setMips(w, h) {
    this.uniforms.uRes.value.set(w, h);
    this.uniforms.uMips.value = Math.floor(Math.log2(Math.max(1, Math.min(w, h)))) - 1;
  }

  /** CSS pixels; the target follows the renderer's pixel ratio. */
  setSize(w, h) {
    const pr = this.renderer.getPixelRatio();
    const W = Math.max(1, Math.floor(w * pr)), H = Math.max(1, Math.floor(h * pr));
    this.rt.setSize(W, H);
    this._setMips(W, H);
  }

  render(scene, camera) {
    const r = this.renderer, U = this.uniforms, u = this.u;
    // keep the target glued to the drawing buffer even if the game forgot setSize
    r.getDrawingBufferSize(this._sz);
    if (this._sz.x !== this.rt.width || this._sz.y !== this.rt.height) { this.rt.setSize(this._sz.x, this._sz.y); this._setMips(this._sz.x, this._sz.y); }

    r.setRenderTarget(this.rt);
    r.render(scene, camera);
    r.setRenderTarget(null);

    U.uExposure.value = u.exposure; U.uSat.value = u.sat; U.uContrast.value = u.contrast;
    U.uGray.value = u.gray; U.uInvert.value = u.invert; U.uVignette.value = u.vignette;
    U.uAb.value = u.aberration; U.uSpeed.value = u.speed; U.uFlash.value = u.flash; U.uDown.value = u.down;
    U.uTime.value = u.time; U.uBloom.value = u.bloom ?? 0.7;
    const t = u.tint;
    if (t) U.uTint.value.set(t[0] ?? 1, t[1] ?? 1, t[2] ?? 1);
    r.render(this.scene, this.camera);
  }

  dispose() { this.rt.dispose(); this.material.dispose(); this.quad.geometry.dispose(); }
}
