import * as THREE from "three";
import type { DevSettings } from "./devSettings";

const SKY_VS = /* glsl */ `
varying vec3 vWorld;
void main() {
  vec4 w = modelMatrix * vec4(position, 1.0);
  vWorld = w.xyz;
  gl_Position = projectionMatrix * viewMatrix * w;
}
`;

const SKY_FS = /* glsl */ `
precision highp float;
varying vec3 vWorld;
uniform vec3 uCamera;
uniform float uTime;
uniform vec3 uSunDir;
uniform float uGalaxyU;
uniform float uCamOrbitDist;
uniform float uOrbitNear;
uniform float uOrbitMid;
uniform float uOrbitFar;
uniform float uStarCell;
uniform float uStarThresh;
uniform float uTwinkle;
uniform float uColorWarm;
uniform float uMilky;
uniform float uParallax;

float hash3(vec3 p) {
  p = fract(p * 0.1031);
  p += dot(p, p.yzx + 33.33);
  return fract((p.x + p.y) * p.z);
}

float fbm2(vec2 x) {
  float v = 0.0, a = 0.5;
  mat2 m = mat2(1.6,1.2,-1.2,1.6);
  for (int i = 0; i < 4; i++) {
    v += a * sin(x.x) * sin(x.y);
    x = m * x;
    a *= 0.5;
  }
  return v;
}

void main() {
  vec3 p = vWorld;
  vec3 d = normalize(p - uCamera);
  if (length(d) < 1e-4) d = vec3(0.0, 1.0, 0.0);
  float up = max(0.0, d.y);
  float haze = 1.0 - pow(1.0 - up, 2.0);
  float wFull = 1.0 - smoothstep(uOrbitNear, uOrbitMid, uCamOrbitDist);
  float wDot = smoothstep(uOrbitMid, uOrbitFar, uCamOrbitDist);
  float wMid = max(0.0, 1.0 - wFull - wDot);

  vec3 night = mix(vec3(0.01,0.02,0.05), vec3(0.02,0.05,0.12), up);
  vec3 atmo = vec3(0.12,0.2,0.45) * haze * 0.35;
  float az = atan(d.z, d.x) / 6.28318 + 0.5;
  float el = d.y * 0.5 + 0.5;
  vec2 gal = vec2(az + uGalaxyU + uParallax, el) * 12.0;
  vec3 gaxis = normalize(vec3(0.2,0.3,0.9));
  float gband = 1.0 - abs(dot(d, gaxis));
  float milky = smoothstep(0.55, 0.2, gband) * (0.5 + 0.5 * fbm2(gal * 0.3 + uTime * 0.01));
  float neb = max(0.0, fbm2(gal) * 0.15) * (0.2 + 0.8 * milky);
  float mF = uMilky;
  vec3 mcol = mix(vec3(0.15,0.1,0.2), vec3(0.25,0.2,0.4), 0.5) * (milky * 0.6 + neb) * mF;
  mcol += vec3(0.1,0.12,0.2) * milky * 0.4 * mF * (0.5 + 0.5 * sin(uTime * 0.2 + d.x * 2.0));
  vec3 col = night + atmo * 0.4 + mcol;
  float cellScale = mix(95.0, uStarCell, wFull + 0.4 * wMid) + 35.0 * wDot;
  float thA = mix(0.9991, uStarThresh, wFull + 0.35 * wMid) * (1.0 - 0.002 * wDot);
  float twMul = (0.6 + 0.4 * wFull) * uTwinkle;
  float boostA = (20.0 + 55.0 * wFull + 20.0 * wMid) * (0.35 + 0.65 * haze);
  vec3 coolC = vec3(0.45,0.55,0.9);
  vec3 warmC = vec3(1.0,0.92,0.75);
  vec3 starTint = mix(coolC, warmC, uColorWarm);
  vec3 qd = d * cellScale;
  for (int k = 0; k < 4; k++) {
    vec3 hcell = floor(qd);
    qd = qd * 1.4 + 0.7;
    float h1 = hash3(hcell);
    if (h1 > thA) {
      float tw = 0.5 + 0.5 * sin(uTime * uTwinkle * (1.0 + h1 * 2.0) * twMul + hash3(hcell + 1.0) * 6.28318);
      float br = (h1 - thA) / max(1.0 - thA, 0.0001);
      col += tw * 0.45 * br * boostA * starTint;
    }
  }
  if (wDot > 0.1) {
    float cellB = 55.0;
    float thB = 0.9993;
    vec3 q2 = d * cellB;
    float h2 = hash3(floor(q2));
    if (h2 > thB) {
      float w = (h2 - thB) * 200.0 * wDot;
      col += w * starTint * 0.25;
    }
  }
  if (wMid + wFull > 0.05) {
    float thC = 0.988;
    float h3 = hash3(floor(d * 210.0));
    if (h3 > thC) {
      float tw2 = 0.5 + 0.5 * sin(uTime * 2.0 * uTwinkle + d.x * 5.0);
      col += tw2 * 0.15 * (h3 - thC) * 30.0 * wMid * starTint;
    }
  }

  float sun = smoothstep(0.999, 0.9998, dot(d, uSunDir));
  col += vec3(1.0,0.98,0.9) * sun * 3.0 * (0.4 + 0.6 * haze);
  col += pow(max(0.0, dot(d, uSunDir)) * 0.5, 8.0) * vec3(0.2,0.3,0.5);
  gl_FragColor = vec4(col, 1.0);
}
`;

/**
 * Inverted skydome: procedural milky + 3-weight star LOD (orbit distance) + dev uniforms.
 */
export class CelestialSky {
  readonly mesh: THREE.Mesh;
  private readonly uTime = { value: 0 };
  private readonly uCamera = { value: new THREE.Vector3() };
  private readonly uSun = { value: new THREE.Vector3(0.4, 0.85, 0.2).normalize() };
  private readonly uGalaxyU = { value: 0 };
  private readonly uCamOrbitDist = { value: 25 };
  private readonly uOrbitNear = { value: 22 };
  private readonly uOrbitMid = { value: 45 };
  private readonly uOrbitFar = { value: 90 };
  private readonly uStarCell = { value: 320 };
  private readonly uStarThresh = { value: 0.985 };
  private readonly uTwinkle = { value: 1 };
  private readonly uColorWarm = { value: 0.45 };
  private readonly uMilky = { value: 1 };
  private readonly uParallax = { value: 0 };

  constructor(radius: number) {
    const geo = new THREE.SphereGeometry(radius, 64, 48);
    const mat = new THREE.ShaderMaterial({
      vertexShader: SKY_VS,
      fragmentShader: SKY_FS,
      side: THREE.BackSide,
      depthWrite: false,
      depthTest: true,
      /** Required: scene FogExp2 + custom fragment shader (no #include chunks) = broken output / black in WebGL2. */
      fog: false,
      /** Sky color is already authored in shader; skip ACES crushing dark night colors to black. */
      toneMapped: false,
      uniforms: {
        uTime: this.uTime,
        uCamera: this.uCamera,
        uSunDir: this.uSun,
        uGalaxyU: this.uGalaxyU,
        uCamOrbitDist: this.uCamOrbitDist,
        uOrbitNear: this.uOrbitNear,
        uOrbitMid: this.uOrbitMid,
        uOrbitFar: this.uOrbitFar,
        uStarCell: this.uStarCell,
        uStarThresh: this.uStarThresh,
        uTwinkle: this.uTwinkle,
        uColorWarm: this.uColorWarm,
        uMilky: this.uMilky,
        uParallax: this.uParallax,
      },
    });
    this.mesh = new THREE.Mesh(geo, mat);
    this.mesh.frustumCulled = false;
    this.mesh.name = "CelestialSky";
  }

  setSunDirection(dir: THREE.Vector3): void {
    this.uSun.value.copy(dir).normalize();
  }

  setGalaxyOffset(u: number): void {
    this.uGalaxyU.value = u;
  }

  setCameraOrbitLength(len: number): void {
    this.uCamOrbitDist.value = len;
  }

  applyDev(s: DevSettings["sky"]): void {
    this.uOrbitNear.value = s.lodOrbitNear;
    this.uOrbitMid.value = s.lodOrbitMid;
    this.uOrbitFar.value = s.lodOrbitFar;
    this.uStarCell.value = s.starSpacing;
    this.uStarThresh.value = s.starThreshold;
    this.uTwinkle.value = s.twinkle;
    this.uColorWarm.value = s.starColorWarmth;
    this.uMilky.value = s.milky;
  }

  setParallaxExtra(v: number): void {
    this.uParallax.value = v;
  }

  beforeRender(camera: THREE.PerspectiveCamera, _delta: number): void {
    this.uCamera.value.copy(camera.position);
    this.setCameraOrbitLength(camera.position.length());
    this.uTime.value += _delta;
  }

  updateParallaxFromCamera(_cam: THREE.PerspectiveCamera): void {
    this.uGalaxyU.value = _cam.position.x * 0.0002 + _cam.position.z * 0.00015;
  }
}
