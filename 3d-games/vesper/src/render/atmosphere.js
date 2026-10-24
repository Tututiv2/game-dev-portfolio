/* ============================================================================
   ATMOSPHERE — LAW 5: a hard, thin, saturated rim in the planet's own hue.

   Not a fresnel. A fresnel on a back-face shell puts its brightest pixel at the
   OUTSIDE of the shell, so the glow floats away from the planet and reads as a
   halo sticker. The reference images all show the opposite: the brightest line
   sits exactly on the planet's edge and falls off outward, because that is
   where the line of sight through the air is longest.

   So this shades by optical PATH LENGTH through the shell:
       path(b) = 2*sqrt(Rs^2 - b^2)      for impact parameter b in [Rp, Rs]
   which is maximal at b = Rp (the planet's edge) and zero at b = Rs.
   Everything with b < Rp is behind the planet and the depth test removes it.

   TRAP (cost a debugging round in the old engine): this shell is the view from
   OUTSIDE. Standing inside it, an additive back-face sphere lays milky haze
   over the whole frame and flattens the landscape. Hide it below the top of the
   atmosphere — see `setVisibleFromAltitude`.
   ========================================================================= */

import * as T from 'three';
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
uniform vec3  uCol, uSun, uCamObj;
uniform float uRp, uRs, uK, uFall, uBoost;

void main(){
  vec3 n  = normalize(vN);
  vec3 vd = normalize(uCamObj - n*uRs);

  // impact parameter of this line of sight, in metres
  float sinT = length(cross(n, vd));
  float b    = uRs * sinT;

  float outer = sqrt(max(uRs*uRs - b*b, 0.0));
  float maxP  = sqrt(max(uRs*uRs - uRp*uRp, 1.0));
  float t     = clamp(outer / maxP, 0.0, 1.0);   // 1 at the planet's edge

  // air thins with altitude, so the falloff is much sharper than linear
  float dens = pow(t, uFall);

  float lit = max(dot(n, uSun), 0.0);
  // The base term is what keeps the rim reading all the way around the dark
  // limb. Every reference image has a complete ring, not a crescent.
  float amt = dens * uK * (0.34 + lit*1.25);

  vec3 col = uCol * (0.55 + lit*0.85) * uBoost;
  gl_FragColor = vec4(col, clamp(amt, 0.0, 1.0));
}
`;

export function makeAtmosphere(def) {
  const Rp = def.radius;
  // A THIN shell. The references show a hard bright line hugging the planet,
  // not a soft halo floating around it; a thick shell plus a shallow falloff is
  // what produced the fog-ball on the first pass.
  const shellK = 1.020 + Math.min(def.art.atmoAmt, 1.2) * 0.030;
  const Rs = Rp * shellK;
  const g = new T.SphereGeometry(Rs, 96, 64);
  const m = new T.ShaderMaterial({
    vertexShader: VERT, fragmentShader: FRAG,
    transparent: true, depthWrite: false, side: T.BackSide,
    blending: T.AdditiveBlending,
    uniforms: {
      uCol: { value: def.art.atmo.clone() },
      uSun: { value: new T.Vector3(1, 0, 0) },
      uCamObj: { value: new T.Vector3() },
      uRp: { value: Rp }, uRs: { value: Rs },
      uK: { value: Math.min(1.15, def.art.atmoAmt * 1.15) },
      uFall: { value: 3.2 },        // sharp: bright line, fast falloff
      uBoost: { value: 1.5 },       // >1 so the rim blooms
    },
  });
  m.toneMapped = false;
  const mesh = new T.Mesh(g, m);
  mesh.renderOrder = 5;
  mesh.userData.shellR = Rs;
  return mesh;
}

const _v = new T.Vector3();
export function updateAtmosphere(mesh, camera, sunDirWorld) {
  const u = mesh.material.uniforms;
  mesh.worldToLocal(_v.copy(camera.position));
  u.uCamObj.value.copy(_v);
  toLocalDir(mesh, sunDirWorld, u.uSun.value);
}

/** TRAP 5: never draw the outside-view shell while the camera is inside it. */
export function setVisibleFromAltitude(mesh, camDistFromCentre) {
  mesh.visible = camDistFromCentre > mesh.userData.shellR * 1.002;
}
