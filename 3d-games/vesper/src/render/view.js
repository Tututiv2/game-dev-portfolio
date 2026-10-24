/* ============================================================================
   THE RENDER PIPELINE.

   HDR scene -> bloom -> ACES tone map -> sRGB.

   BLOOM IS NOT OPTIONAL. Emissive without a bloom pass is just a bright pixel;
   it does not glow, and "glowing cracks" is the single most important element
   in the whole art direction. Everything upstream therefore renders LINEAR HDR
   into a half-float target with `toneMapped = false` on every material, and the
   OutputPass does tone mapping and colour space once, at the end.
   ========================================================================= */

import * as T from 'three';
import { EffectComposer } from 'three/examples/jsm/postprocessing/EffectComposer.js';
import { RenderPass } from 'three/examples/jsm/postprocessing/RenderPass.js';
import { UnrealBloomPass } from 'three/examples/jsm/postprocessing/UnrealBloomPass.js';
import { OutputPass } from 'three/examples/jsm/postprocessing/OutputPass.js';

export function makeView(canvas, opts = {}) {
  const renderer = new T.WebGLRenderer({
    canvas, antialias: false, powerPreference: 'high-performance',
    preserveDrawingBuffer: !!opts.preserveDrawingBuffer,
  });
  renderer.setPixelRatio(Math.min(devicePixelRatio, opts.maxPR ?? 1.5));
  renderer.setSize(innerWidth, innerHeight);
  renderer.outputColorSpace = T.SRGBColorSpace;
  renderer.toneMapping = T.ACESFilmicToneMapping;
  renderer.toneMappingExposure = opts.exposure ?? 1.0;

  const scene = new T.Scene();
  const camera = new T.PerspectiveCamera(opts.fov ?? 45, innerWidth / innerHeight, 1, 1e9);

  /* ---- THE DEPTH SLICES — how a cockpit and a planet share one frame -------
     You cannot draw something 1 m from your eye and a 310 km world behind it
     with one camera, and orbit.js already knows: it clamps the near plane to
     `min(max(alt*0.08, 1.0), radius*0.05)`, which is 1 m standing on the ground
     and 15.5 km in orbit. That clamp is not timidity, it is arithmetic — the far
     plane is radius*22, so a 1 m near plane in orbit would be a 6,800,000:1
     depth ratio and the planet would z-fight itself to pieces.

     So the frame is rendered in SLICES, far to near, clearing DEPTH (never
     colour) between them. Everything in a nearer slice is by construction in
     front of everything in a farther one, so painting them in that order is
     correct with no depth comparison between slices at all.

     THE SPLIT IS THE GEOMETRIC MEAN, and that is the whole trick. Depth
     precision depends on the RATIO far/near, not the difference, so splitting
     [0.02, camera.near] at sqrt(0.02 * camera.near) gives both slices the same
     ratio at every altitude, with no table and nothing to tune: in orbit that is
     0.02 -> 17.6 m -> 15,500 m, two slices of 880:1 each instead of one of
     775,000:1. Standing on the ground it becomes 0.02 -> 0.14 -> 1.0, and the
     ratios fall to 7:1 on their own.

     THE FAR SLICE IS THE EXISTING CAMERA, UNTOUCHED. The approved orbital
     gallery is rendered by exactly the code that rendered it before, and the two
     extra passes are DISABLED whenever nearScene is empty — so with no ships in
     the world this costs nothing and cannot change a pixel.

     Ship-scale geometry lives in `nearScene` and is rendered by BOTH near
     slices; each camera's frustum clips it, and anything straddling the boundary
     is drawn twice with the nearer pass winning. That is why this takes no
     shader change at all, which matters more here than the duplicated draw:
     planetShader.js is hand-written GLSL with its own trap list, and a
     logarithmic depth buffer would mean injecting three's logdepth chunks into
     it by hand. */
  const nearScene = new T.Scene();
  const camMid = new T.PerspectiveCamera(opts.fov ?? 45, innerWidth / innerHeight, 1, 10);
  const camNear = new T.PerspectiveCamera(opts.fov ?? 45, innerWidth / innerHeight, 0.02, 1);
  // The near cameras are driven entirely from `camera` and are never parented,
  // so nothing can move them out from under the frame they belong to.
  camMid.matrixAutoUpdate = false;
  camNear.matrixAutoUpdate = false;

  // MSAA on the HDR target — the planet's limb is a hard curve against black
  // and it aliases badly without it. FXAA after bloom smears the glow instead.
  const size = renderer.getDrawingBufferSize(new T.Vector2());
  const rt = new T.WebGLRenderTarget(size.x, size.y, {
    type: T.HalfFloatType,
    samples: opts.samples ?? 4,
    colorSpace: T.LinearSRGBColorSpace,
  });

  const composer = new EffectComposer(renderer, rt);
  composer.addPass(new RenderPass(scene, camera));

  /* Far to near, and colour is never cleared — only depth. `clear = false` is
     the load-bearing half: clearing colour here would throw away the planet. */
  const midPass = new RenderPass(nearScene, camMid);
  const nearPass = new RenderPass(nearScene, camNear);
  for (const p of [midPass, nearPass]) {
    p.clear = false;
    p.clearDepth = true;
    p.enabled = false;          // until something is actually in nearScene
    composer.addPass(p);
  }
  // THRESHOLD IS THE CRITICAL NUMBER. It must sit ABOVE the brightest ordinary
  // lit surface and BELOW the emissive. Set it too low (0.6, as the first pass
  // did) and the whole lit disc blooms: the planet becomes a smear and ACES
  // pushes the blown saturated reds to magenta. Base albedos are authored to
  // stay under ~0.85 linear, so 0.95 leaves only snow peaks and glow above it.
  const bloom = new UnrealBloomPass(
    new T.Vector2(size.x, size.y),
    opts.bloomStrength ?? 0.75,   // how much light spills
    opts.bloomRadius ?? 0.50,     // how far it spills
    opts.bloomThreshold ?? 0.95,  // above this is "light", below is "surface"
  );
  composer.addPass(bloom);
  composer.addPass(new OutputPass());

  /* CALL THIS EVERY FRAME, AFTER the far camera has been placed and AFTER
     orbit.js has moved its near plane, and BEFORE composer.render().

     It returns the slice boundaries so a test can assert them without a GPU —
     the whole point of this function is a number (the depth ratio) that is
     invisible in a screenshot until it is catastrophic. */
  const NEAREST = 0.02;                      // 2 cm: inside a cockpit canopy
  function syncNear() {
    const on = nearScene.children.length > 0;
    midPass.enabled = nearPass.enabled = on;
    if (!on) return null;

    // The far camera owns the world transform; the slices only re-cut its depth.
    camera.updateMatrixWorld();
    for (const c of [camMid, camNear]) {
      c.matrixWorld.copy(camera.matrixWorld);
      c.matrixWorldInverse.copy(camera.matrixWorldInverse);
      c.fov = camera.fov; c.aspect = camera.aspect;
    }
    const outer = camera.near;               // where the far slice begins
    const split = Math.sqrt(NEAREST * outer); // equal ratio either side
    camMid.near = split;  camMid.far = outer;
    camNear.near = NEAREST; camNear.far = split;
    camMid.updateProjectionMatrix();
    camNear.updateProjectionMatrix();
    return { nearest: NEAREST, split, outer,
             ratio: Math.max(split / NEAREST, outer / split) };
  }

  function resize() {
    const w = innerWidth, h = innerHeight;
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
    renderer.setSize(w, h);
    composer.setSize(w, h);
    const s = renderer.getDrawingBufferSize(new T.Vector2());
    bloom.setSize(s.x, s.y);
  }
  addEventListener('resize', resize);

  return { renderer, scene, camera, composer, bloom, resize,
           nearScene, camMid, camNear, syncNear };
}
