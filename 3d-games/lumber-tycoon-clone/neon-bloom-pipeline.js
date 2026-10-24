/**
 * Selective neon bloom (three.js selective UnrealBloom pattern).
 * Only meshes on {@link NEON_BLOOM_LAYER} appear in the bloom buffer; everything else is drawn black
 * for that pass. Scene background is forced to black during the bloom sub-pass so the sky does not bloom.
 */
import * as THREE from 'three';
import { EffectComposer } from 'three/addons/postprocessing/EffectComposer.js';
import { RenderPass } from 'three/addons/postprocessing/RenderPass.js';
import { ShaderPass } from 'three/addons/postprocessing/ShaderPass.js';
import { UnrealBloomPass } from 'three/addons/postprocessing/UnrealBloomPass.js';
import { OutputPass } from 'three/addons/postprocessing/OutputPass.js';

/** Layer bit — enable on dark logs/planks; camera must also enable this layer. */
export const NEON_BLOOM_LAYER = 2;

const neonBloomLayerMask = new THREE.Layers();
neonBloomLayerMask.set(NEON_BLOOM_LAYER);

const BLOOM_MIX_VS = `
varying vec2 vUv;
void main() {
  vUv = uv;
  gl_Position = projectionMatrix * modelViewMatrix * vec4( position, 1.0 );
}
`;

const BLOOM_MIX_FS = `
uniform sampler2D baseTexture;
uniform sampler2D bloomTexture;
varying vec2 vUv;
void main() {
  gl_FragColor = texture2D( baseTexture, vUv ) + texture2D( bloomTexture, vUv );
}
`;

export function enableNeonBloomOnMesh(mesh) {
  if (mesh?.layers) mesh.layers.enable(NEON_BLOOM_LAYER);
}

export function enableNeonBloomOnCamera(cam) {
  if (cam?.layers) cam.layers.enable(NEON_BLOOM_LAYER);
}

/**
 * @param {THREE.WebGLRenderer} renderer
 * @param {THREE.Scene} scene
 * @param {THREE.Camera} camera
 */
export function createNeonBloomPipeline(renderer, scene, camera) {
  const darkMaterial = new THREE.MeshBasicMaterial({ color: 0x000000 });
  const materialsStash = {};
  const blackBg = new THREE.Color(0x000000);

  const renderScene = new RenderPass(scene, camera);

  const bloomPass = new UnrealBloomPass(
    new THREE.Vector2(window.innerWidth, window.innerHeight),
    0.45,
    0.31,
    0.18
  );

  const bloomComposer = new EffectComposer(renderer);
  bloomComposer.renderToScreen = false;
  bloomComposer.addPass(renderScene);
  bloomComposer.addPass(bloomPass);

  const mixMaterial = new THREE.ShaderMaterial({
    uniforms: {
      baseTexture: { value: null },
      bloomTexture: { value: bloomComposer.renderTarget2.texture },
    },
    vertexShader: BLOOM_MIX_VS,
    fragmentShader: BLOOM_MIX_FS,
  });

  const mixPass = new ShaderPass(mixMaterial, 'baseTexture');
  mixPass.needsSwap = true;

  const outputPass = new OutputPass();

  const finalComposer = new EffectComposer(renderer);
  finalComposer.addPass(new RenderPass(scene, camera));
  finalComposer.addPass(mixPass);
  finalComposer.addPass(outputPass);

  function darkenNonBloomed(obj) {
    if (!obj.isMesh) return;
    /** HUD-style overlays must keep their real material (avoid stash/restore edge cases). */
    if (obj.userData?.skipBloomMaterialSwap) return;
    if (neonBloomLayerMask.test(obj.layers)) return;
    materialsStash[obj.uuid] = obj.material;
    if (Array.isArray(obj.material)) {
      obj.material = obj.material.map(() => darkMaterial);
    } else {
      obj.material = darkMaterial;
    }
  }

  function restoreMaterial(obj) {
    if (!obj.isMesh) return;
    if (materialsStash[obj.uuid] !== undefined) {
      obj.material = materialsStash[obj.uuid];
      delete materialsStash[obj.uuid];
    }
  }

  let savedBackground = null;

  return {
    bloomPass,
    /** Full selective bloom + final composite. */
    render() {
      savedBackground = scene.background;
      scene.background = blackBg;

      scene.traverse(darkenNonBloomed);
      bloomComposer.render();
      scene.traverse(restoreMaterial);

      scene.background = savedBackground;
      savedBackground = null;

      mixMaterial.uniforms.bloomTexture.value = bloomComposer.renderTarget2.texture;
      finalComposer.render();
    },
    setSize(width, height) {
      bloomComposer.setSize(width, height);
      finalComposer.setSize(width, height);
    },
    dispose() {
      darkMaterial.dispose();
      mixMaterial.dispose();
      bloomComposer.dispose();
      finalComposer.dispose();
    },
  };
}
