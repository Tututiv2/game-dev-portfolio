/* ============================================================================
   THE GROUND'S MATERIAL — which is the ORBITAL PLANET'S MATERIAL.

   This file is almost empty on purpose. The terrain does not get its own
   shading; it reuses FRAG_BAKED verbatim, the exact fragment shader the orbital
   sphere is drawn with. Only the vertex stage differs, because terrain vertices
   arrive as real displaced geometry in a chunk-local frame rather than as
   points on a sphere.

   Why that matters more than the saved code: the transition from "the planet
   is a sphere in front of you" to "the planet is the ground under you" is the
   single most visible cut in the whole renderer, and if the two sides of it are
   shaded by two pieces of source they WILL drift apart — different palettes,
   different glow, a visible line across the world. Sharing the fragment shader
   makes that impossible rather than unlikely.

   It also means every uniform the orbital planet has is already correct here,
   because both materials share ONE uniforms object. (Share it at construction.
   Never reassign material.uniforms afterwards — see DECISIONS, the bake spent
   an evening drawing the wrong planet that way.)
   ========================================================================= */

import * as T from 'three';
import { fragBaked } from './planetShader.js';

/* The direction is an ATTRIBUTE, not something reconstructed from the vertex
   position. Position is chunk-relative on purpose — a planet is up to 850 km
   across and float32 gives out around 0.1 m at that magnitude — so adding the
   chunk origin back on and normalising would hand the texture lookup a number
   that has already lost the precision the whole scheme exists to protect. */
export const TERRAIN_VERT = /* glsl */`
precision highp float;
in vec3 aDir;
in vec3 aNrm;
in float aSeam;
out vec3 vN;
out vec3 vNrm;
out float vSeam;
/* Distance from the eye, in metres. The ground needs it and the orbital sphere
   does not: it is what lets the near-field crack network band-limit itself to
   the pixel footprint, so the network dissolves into the baked cubemap with
   distance instead of aliasing against it. Taken from the model-view position,
   which is already chunk-relative and therefore still has its precision. */
out float vDist;
/* The chunk-relative position, in METRES, for the ground's bump normal. The
   fragment stage takes screen-space derivatives of it to turn the material
   field into a gradient in world units — see fragBaked. Chunk-relative is
   exactly what that needs: derivatives are differences, so the chunk origin
   cancels, and this keeps the precision the whole scheme exists to protect. */
out vec3 vPos;

void main(){
  vN = normalize(aDir);
  vPos = position;
  /* The shading normal, computed on the CPU from the surrounding vertices and
     carried per vertex. It is NOT normalised here: interpolating unit vectors
     across a triangle does not give a unit vector, so it is normalised in the
     fragment stage where the interpolation has already happened. */
  vNrm = aNrm;
  vSeam = aSeam;
  vec4 mv = modelViewMatrix * vec4(position, 1.0);
  vDist = length(mv.xyz);
  gl_Position = projectionMatrix * mv;
}
`;

/**
 * @param uniforms the world's uniform set — THE SAME OBJECT the orbital planet
 *                 uses, so the sun, the camera and the bake textures update in
 *                 one place for both.
 */
export function makeTerrainMaterial(def, uniforms) {
  const mat = new T.ShaderMaterial({
    vertexShader: TERRAIN_VERT,
    fragmentShader: fragBaked(true),      // true = shade from the MESH normal
    glslVersion: T.GLSL3,
    uniforms,
  });
  mat.toneMapped = false;
  return mat;
}
