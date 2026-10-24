/* ============================================================================
   CLOUDS — its own slowly-rotating sphere.

   Two jobs, both from the reference set:
     - latitude BANDS, warped so they swirl instead of striping
     - SPIRAL CYCLONES at hashed positions: the ice vortex on the glacier world,
       the three storm eyes on the ocean world. Done with a Rodrigues rotation
       whose angle decays with angular distance from the eye — a real spiral,
       not a texture of one.

   `warm` turns the layer into ASH: tinted by the planet's glow colour and
   concentrated around calderas rather than spread over the globe, which is what
   the lava reference shows rising off its two big basins.
   ========================================================================= */

import * as T from 'three';
import { NOISE } from './glsl/noise.glsl.js';
import { NFEAT } from './planetShader.js';
import { toLocalDir } from './frameUtil.js';

const VERT = /* glsl */`
varying vec3 vN;
void main(){
  vN = normalize(position);
  gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
}
`;

const FRAG = /* glsl */`
precision highp float;
varying vec3 vN;
${NOISE}

uniform vec3  uCol, uSun, uCamObj, uSeedOff, uWarmCol;
uniform float uRs, uAmount, uBand, uTime, uWarm;
uniform vec3  uVortex[4];
uniform vec2  uVortexP[4];      // x = strength, y = falloff (radians)
uniform vec3  uFeatPos[${NFEAT}];
uniform vec4  uFeatParam[${NFEAT}];

void main(){
  vec3 n = normalize(vN);

  // --- cyclones: rotate the sample point around each eye ------------------
  vec3 q = n;
  for(int i=0;i<4;i++){
    if(uVortexP[i].x == 0.0) continue;
    q = spiralWarp(q, uVortex[i], uVortexP[i].x, uVortexP[i].y);
  }

  vec3 p = q*2.0 + uSeedOff + vec3(uTime*0.02, 0.0, 0.0);
  float turb = fbm4(p);
  // BANDS dominate, turbulence only distorts them. Weighted the other way round
  // the layer came out as cotton-wool blobs: real weather on a spinning world
  // is drawn out into latitude streaks, and the streaks are what sell rotation.
  float bands = sin(q.y*uBand + turb*2.8)*0.5 + 0.5;
  float d = 0.34*fbm4(p*2.4 + 5.0) + 0.66*(bands*2.0 - 1.0);

  // A steep curve so cloud reads as banded WISPS with gaps, not a deck.
  // The gaps are the point: you have to be able to see the world through them.
  float cover = 1.0 - uAmount*0.85;
  float a = smoothstep(cover, cover + 0.26, d*0.5 + 0.5);

  vec3 col = uCol;

  // --- ash mode: keep it near the calderas, and tint it hot ---------------
  if(uWarm > 0.0){
    float near = 0.0;
    for(int i=0;i<${NFEAT};i++){
      vec4 pr = uFeatParam[i];
      if(pr.x <= 0.0 || pr.y < 0.5 || pr.y > 1.5) continue;   // calderas only
      float c = 1.0 - dot(n, uFeatPos[i]);
      near = max(near, 1.0 - smoothstep(0.0, pr.x*2.6, c));
    }
    // plumes drift, so bias the mask upward along the noise as well
    a *= mix(0.10, 1.0, near);
    col = mix(uCol, uWarmCol, uWarm*0.55*near);
  }

  vec3 vd = normalize(uCamObj - n*uRs);
  // ONE light term. The first pass multiplied by both shade and (1+lit*0.5),
  // which peaked at 1.66x the cloud albedo, pushed a bright white deck over the
  // bloom threshold, and turned every ocean world into a featureless white ball.
  // Clouds on the night side must still go properly dark or the planet stops
  // reading as a lit sphere.
  float shade = 0.05 + smoothstep(-0.06, 0.50, dot(n, uSun))*0.95;
  // fade at the limb so the layer does not draw a hard second circle
  float rim = smoothstep(0.0, 0.30, abs(dot(n, vd)));

  gl_FragColor = vec4(col*shade, clamp(a, 0.0, 1.0)*rim*0.92);
}
`;

export function makeClouds(def) {
  if (!def.clouds) return null;
  const Rs = def.radius * 1.014;
  const g = new T.SphereGeometry(Rs, 96, 64);

  const vpos = [], vpar = [];
  for (let i = 0; i < 4; i++) {
    const v = def.vortices[i];
    if (v) {
      vpos.push(new T.Vector3(v.x, v.y, v.z));
      vpar.push(new T.Vector2(2.4 + v.glow * 2.0, Math.max(v.r * 2.4, 0.22)));
    } else { vpos.push(new T.Vector3(0, 1, 0)); vpar.push(new T.Vector2(0, 1)); }
  }
  const featPos = [], featParam = [];
  for (let i = 0; i < NFEAT; i++) {
    const f = def.features[i];
    if (f) {
      featPos.push(new T.Vector3(f.x, f.y, f.z));
      featParam.push(new T.Vector4(1 - Math.cos(f.r), f.kind, f.depth, f.glow));
    } else {
      featPos.push(new T.Vector3(0, 1, 0));
      featParam.push(new T.Vector4(0, 0, 0, 0));
    }
  }

  const m = new T.ShaderMaterial({
    vertexShader: VERT, fragmentShader: FRAG,
    transparent: true, depthWrite: false, side: T.FrontSide,
    uniforms: {
      uCol: { value: def.clouds.col.clone() },
      uWarmCol: { value: def.art.emisB.clone() },
      uSun: { value: new T.Vector3(1, 0, 0) },
      uCamObj: { value: new T.Vector3() },
      uSeedOff: { value: new T.Vector3(...def.seedOff) },
      uRs: { value: Rs },
      uAmount: { value: def.clouds.amount },
      uBand: { value: def.clouds.band },
      uWarm: { value: def.clouds.warm },
      uTime: { value: 0 },
      uVortex: { value: vpos },
      uVortexP: { value: vpar },
      uFeatPos: { value: featPos },
      uFeatParam: { value: featParam },
    },
  });
  m.toneMapped = false;
  const mesh = new T.Mesh(g, m);
  mesh.renderOrder = 3;
  return mesh;
}

const _v = new T.Vector3();
export function updateClouds(mesh, camera, sunDirWorld, time) {
  if (!mesh) return;
  const u = mesh.material.uniforms;
  mesh.worldToLocal(_v.copy(camera.position));
  u.uCamObj.value.copy(_v);
  toLocalDir(mesh, sunDirWorld, u.uSun.value);
  u.uTime.value = time;
}
