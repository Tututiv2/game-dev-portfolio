/* ============================================================================
   PLANETARY RINGS — real geometry in the planet's equatorial plane.

   Real geometry, not a billboard, for one reason: the near half must draw in
   FRONT of the planet and the far half must be hidden BEHIND it. That is the
   single detail that makes a ringed world read as three-dimensional, and the
   depth buffer does it for free.

   The planet's shadow is cast on the ring analytically: a ring point is in
   shadow when the ray from it toward the star passes within the planet radius
   of the centre. Cheap, and it is the other detail people notice.
   ========================================================================= */

import * as T from 'three';
import { NOISE } from './glsl/noise.glsl.js';
import { toLocalDir } from './frameUtil.js';

const VERT = /* glsl */`
varying vec3 vP;
void main(){
  vP = position;
  gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
}
`;

const FRAG = /* glsl */`
precision highp float;
varying vec3 vP;
${NOISE}
uniform vec3  uCol, uSun, uCamObj;
uniform float uInner, uOuter, uRp, uOpacity, uBands, uSeed;

void main(){
  float rad = length(vP);
  float t = (rad - uInner) / (uOuter - uInner);
  if(t < 0.0 || t > 1.0) discard;

  // banding: several octaves of 1D noise across the radius, plus hard gaps
  float b  = fbm5(vec3(t*uBands*3.0 + uSeed, uSeed*0.7, 0.0));
  float b2 = fbm3(vec3(t*uBands*11.0 + uSeed*2.0, 0.0, uSeed));
  float dens = sat(0.55 + b*0.75 + b2*0.25);
  // a few clean gaps — a ring with no gaps looks like a disc of fog
  dens *= smoothstep(0.0, 0.06, t) * (1.0 - smoothstep(0.90, 1.0, t));
  // a few clean division gaps
  float ph  = fract(t*uBands*0.5 + 0.31);
  float gap = smoothstep(0.44, 0.50, ph) * (1.0 - smoothstep(0.50, 0.56, ph));
  dens *= 1.0 - 0.90*gap;

  // --- shadow of the planet -----------------------------------------------
  float tc = dot(-vP, uSun);
  float shade = 1.0;
  if(tc > 0.0){
    float d2 = max(dot(vP, vP) - tc*tc, 0.0);
    shade = smoothstep(uRp*0.94, uRp*1.06, sqrt(d2));
  }
  shade = 0.10 + 0.90*shade;

  // Grazing angles pile up more material along the line of sight, but only a
  // LITTLE: an unbounded graze term made a nearly edge-on ring render as a
  // solid white highway across the frame. Rings are meant to be translucent —
  // in every reference the planet is clearly visible through them.
  vec3 vd = normalize(uCamObj - vP);
  float graze = 1.0 - abs(vd.y);
  float a = dens * uOpacity * (0.45 + graze*0.35);

  gl_FragColor = vec4(uCol*shade*(0.75 + graze*0.30), clamp(a, 0.0, 0.86));
}
`;

export function makeRings(def) {
  if (!def.ring) return null;
  const R = def.radius;
  const inner = R * def.ring.inner, outer = R * def.ring.outer;
  const g = new T.RingGeometry(inner, outer, 256, 4);
  // RingGeometry lies in XY; rotate it into the equatorial (XZ) plane
  g.rotateX(-Math.PI / 2);

  const m = new T.ShaderMaterial({
    vertexShader: VERT, fragmentShader: FRAG,
    transparent: true, depthWrite: false, side: T.DoubleSide,
    uniforms: {
      uCol: { value: def.ring.col.clone() },
      uSun: { value: new T.Vector3(1, 0, 0) },
      uCamObj: { value: new T.Vector3() },
      uInner: { value: inner }, uOuter: { value: outer },
      uRp: { value: R },
      uOpacity: { value: def.ring.opacity },
      uBands: { value: def.ring.bands },
      uSeed: { value: (def.seed % 977) * 0.031 },
    },
  });
  m.toneMapped = false;
  const mesh = new T.Mesh(g, m);
  mesh.rotation.z = def.ring.tilt;
  mesh.renderOrder = 4;
  return mesh;
}

const _v = new T.Vector3();
export function updateRings(mesh, camera, sunDirWorld) {
  if (!mesh) return;
  const u = mesh.material.uniforms;
  mesh.worldToLocal(_v.copy(camera.position));
  u.uCamObj.value.copy(_v);
  toLocalDir(mesh, sunDirWorld, u.uSun.value);
}
