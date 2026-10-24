/* ============================================================================
   THE BAKE — evaluate the world ONCE, into textures.

   WHY THIS EXISTS
   ---------------
   Colouring one pixel of the orbital planet evaluates the height field seven
   times (three for the normal, four for the self-shadow), and each evaluation
   is a couple of thousand operations of simplex, ridged and Worley noise. That
   is fine for judging twelve worlds and hopeless for a game: measured on the
   owner's RTX 4070 SUPER at 1920x1080 with the planet at 1.9 radii, the surface
   alone cost 16.5 ms of a 22.4 ms frame — three quarters of everything, with
   nothing in the scene but one planet. (tests/attrib.mjs, 2026-08-01.)

   Nothing in surfaceAt() depends on where the sun is or where the camera is. It
   is a pure function of a direction. So it does not have to be recomputed
   thirty million times a second — it can be computed once per direction, when
   you arrive, and looked up after that. This is what No Man's Sky does, and it
   is why approaching a planet there gets CHEAPER instead of collapsing.

   WHAT GETS BAKED, AND WHAT DELIBERATELY DOES NOT
   -----------------------------------------------
   Baked (three RGBA16F cubemaps):
     col   rgb = albedo            a = height, normalised
     emis  rgb = emissive          a = spare
     nrm   rgb = normal, object    a = ambient occlusion
   NOT baked, because it moves: the sun, the terminator, the day/night weighting
   of the emissive, the self-shadow, the atmospheric haze. All of that is
   shadeSurf() and it runs per pixel, per frame, and costs almost nothing.

   NOT baked, because it is animated: GAS GIANTS. Their bands drift with uTime,
   and their shader has no height field to evaluate, so it is roughly thirty
   times cheaper than a terrain world already. They stay on the analytic path.

   THINGS THAT WILL BITE
   ---------------------
   1. HALF float, not full. A DataTexture of FloatType with LinearFilter needs
      OES_texture_float_linear and silently returns ZERO without it — that cost
      two runs on the conformance harness (DECISIONS, 2026-08-01). RGBA16F is
      filterable in core WebGL2, which is the only reason linear filtering and
      mipmapping are safe here.
   2. Mipmaps are not optional. From 3.15 radii the planet is ~430 px across a
      map whose equator is 4 x uBakeSize texels, so without mips it aliases into
      the same fizz that band-limiting exists to prevent. And a cube texture
      with a mipmap minFilter and NO mip chain is INCOMPLETE, which samples as
      pure black — a very loud failure, at least.
   3. The mip chain is generated once, after the sixth face. Asking for it after
      every face regenerates an incomplete cube five times for nothing.
   4. Sea level is baked in. DESIGN.md wants tides and tsunamis, which means
      uSeaLevel has to be able to move — so a moving sea means a RE-bake, and at
      some point a separate water surface drawn over the baked land. Do not
      build the ocean on the assumption that this texture can be edited.
   ========================================================================= */

import * as T from 'three';
import { bakeFrag, VERT_BAKE } from './planetShader.js';

/* 1024 per face = 4096 texels around the equator = about 1.2 km per texel on a
   600 km world, and 3 x 6 x 1024^2 x 8 bytes = 151 MB plus a third for mips.
   That is the orbital representation only; the ground you walk on comes from
   the chunked terrain, which reads the analytic field at whatever scale it
   needs. Raise it and you pay in VRAM, quadratically. */
export const BAKE_SIZE = 1024;
export const BAKE_SIZE_LOW = 512;

function cubeTarget(size) {
  const rt = new T.WebGLCubeRenderTarget(size, {
    type: T.HalfFloatType,
    format: T.RGBAFormat,
    magFilter: T.LinearFilter,
    minFilter: T.LinearMipmapLinearFilter,
    depthBuffer: false,
    stencilBuffer: false,
    colorSpace: T.LinearSRGBColorSpace,   // raw linear HDR, no conversion
  });
  // Off during the six face renders, flipped on for the last one so the chain
  // is built exactly once, over a complete cube.
  rt.texture.generateMipmaps = false;
  return rt;
}

/* ---------------------------------------------------------------------------
   ONE OF THESE, REUSED FOR EVERY WORLD. Not one per planet.

   Measured, because it was not obvious: baking is ~25 ms of GPU work, but
   allocating three fresh half-float cubemaps and freeing the previous three
   costs about 1.3 SECONDS on D3D11 — fifty times the actual rendering. A
   viewer that built a new PlanetBake per world therefore spent all of its time
   in the memory allocator and none of it drawing, and it looked exactly like a
   slow bake. (tests/bakecost.mjs.)

   So the render targets are owned here, allocated once, and every new world
   simply overwrites them. That also halves peak VRAM, since the old cubemaps
   no longer have to coexist with the new ones during a switch.
   ------------------------------------------------------------------------ */
export class PlanetBake {
  /**
   * @param renderer  the live WebGLRenderer — baking borrows it for 18 draws
   * @param size      texels per cube face
   */
  constructor(renderer, size = BAKE_SIZE) {
    this.renderer = renderer;
    this.size = size;
    this.rtCol = cubeTarget(size);
    this.rtEmis = cubeTarget(size);
    this.rtNrm = cubeTarget(size);

    this.mat = null;
    this.scene = new T.Scene();
    this.quad = new T.Mesh(new T.PlaneGeometry(2, 2), null);
    this.scene.add(this.quad);
    this.camera = new T.Camera();
    this.setWorld({});
    this.ms = 0;
    this.compileMs = 0;
  }

  /* ---------------------------------------------------------------------
     Point the bake at a world.

     A WHOLE NEW MATERIAL, every time. The obvious thing — keep one material
     and reassign material.uniforms — DOES NOT WORK, and it fails silently in
     the worst possible way: three caches the uniform list against the program
     when the material is first drawn, so the shader keeps reading whatever it
     was bound to first. The bake then rendered a perfectly plausible planet
     that was not the one anyone had asked for, identical for every seed, and
     no error anywhere. It took a probe that reads a uniform straight back out
     of the shader to see it (uPass 5-8).

     Building a new ShaderMaterial does not recompile anything: three caches
     programs by source, so this is a hash lookup and a refcount.
     --------------------------------------------------------------------- */
  /**
   * @param probes compile the diagnostic passes in (uPass 3-8). OFF for the
   *   game: one of them evaluates the height field on its own, which inlines
   *   the whole noise library again and costs about 13 seconds of first-launch
   *   shader compile. Tests turn it on and pay for it.
   */
  setWorld(uniforms, probes = false) {
    const old = this.mat;
    this.probes = probes;
    this.mat = new T.ShaderMaterial({
      vertexShader: VERT_BAKE,
      fragmentShader: bakeFrag(probes),
      uniforms: Object.assign({}, uniforms, {
        uFaceIx: { value: 0 }, uPass: { value: 0 },
        uBakeSize: { value: this.size },
      }),
      depthTest: false, depthWrite: false,
    });
    this.mat.toneMapped = false;
    this.quad.material = this.mat;
    if (old) old.dispose();
  }

  /** the three cubemaps plus the texel angle, ready to hand to a material */
  get textures() {
    return {
      col: this.rtCol.texture,
      emis: this.rtEmis.texture,
      nrm: this.rtNrm.texture,
      // angular size of one texel at a face CENTRE. The draw shader only uses
      // it as a floor on the self-shadow step, so the coarsest value is right.
      texEps: 2 / this.size,
    };
  }

  /**
   * Render all three targets for one world. Blocking: 18 fullscreen draws.
   * @param uniforms the world's uniform set (makeSurfaceUniforms), the SAME
   *                 object the drawing material will use, so the bake and the
   *                 draw cannot end up describing two different planets.
   */
  run(uniforms) {
    const r = this.renderer;
    this.setWorld(uniforms);

    // Build the program FIRST and time it on its own. The bake shader carries
    // the whole noise library, and ANGLE compiles that through HLSL, which can
    // take many seconds on its own — measure them together and you will "fix"
    // a bake that was never the slow part. It is paid once per session (three
    // caches programs by source), not once per world.
    const t0 = performance.now();
    r.compile(this.scene, this.camera);
    const tC = performance.now();
    const prevTarget = r.getRenderTarget();
    const prevFace = r.getActiveCubeFace();
    const prevAutoClear = r.autoClear;
    r.autoClear = true;

    const targets = [this.rtCol, this.rtEmis, this.rtNrm];
    for (let pass = 0; pass < 3; pass++) {
      const rt = targets[pass];
      this.mat.uniforms.uPass.value = pass;
      for (let f = 0; f < 6; f++) {
        this.mat.uniforms.uFaceIx.value = f;
        // build the mip chain once, on the last face, over a complete cube
        rt.texture.generateMipmaps = (f === 5);
        r.setRenderTarget(rt, f);
        r.render(this.scene, this.camera);
      }
    }

    // Make the number honest. Without this the driver returns from the last
    // draw immediately and the stall simply reappears in the first frame,
    // which is a great way to report a bake that is four times faster than it
    // is and then wonder why the page still hitches.
    r.getContext().finish();

    r.setRenderTarget(prevTarget, prevFace);
    r.autoClear = prevAutoClear;
    this.compileMs = tC - t0;
    this.ms = performance.now() - tC;
    return this.ms;
  }

  /**
   * Read the baked height back at N directions. This is how a flipped cube face
   * gets caught: the six face orientations are pure convention, one wrong sign
   * tears the world along a seam, and "it looks a bit odd" is not a bug report.
   * tests/bake_conformance.mjs compares these against the CPU height mirror.
   * @param dirs Float32Array of xyz triples
   * @param mode 0 = baked height. 1/2/3 = give back the DIRECTION's x/y/z, which
   *        checks the harness itself: does texel i on the GPU carry the
   *        direction the caller thinks index i has? If that is wrong then
   *        nothing downstream means anything, and it will look exactly like a
   *        broken bake. conformance.js has the same self-check for the same
   *        reason, and it is the only way this class of bug gets found.
   */
  probeHeights(dirs, mode = 0) {
    const n = dirs.length / 3;
    const w = Math.min(n, 256), h = Math.ceil(n / w);
    const dirTex = new T.DataTexture(new Float32Array(w * h * 4), w, h,
      T.RGBAFormat, T.FloatType);
    // NearestFilter is mandatory on a FloatType texture — see DECISIONS.
    dirTex.minFilter = T.NearestFilter;
    dirTex.magFilter = T.NearestFilter;
    dirTex.generateMipmaps = false;
    for (let i = 0; i < n; i++) {
      dirTex.image.data[i * 4] = dirs[i * 3];
      dirTex.image.data[i * 4 + 1] = dirs[i * 3 + 1];
      dirTex.image.data[i * 4 + 2] = dirs[i * 3 + 2];
      dirTex.image.data[i * 4 + 3] = 1;
    }
    dirTex.needsUpdate = true;

    // GLSL3 for ONE REASON: textureLod. Neighbouring pixels of this probe are
    // unrelated directions all over the sphere, so the implicit derivative is
    // enormous and the hardware picks the COARSEST mip — the probe then reads
    // a 1x1 average of the whole world and reports that the bake is garbage,
    // which is exactly what it did. Explicit level 0 or the instrument lies.
    const mat = new T.ShaderMaterial({
      glslVersion: T.GLSL3,
      uniforms: { uDirs: { value: dirTex }, uCube: { value: this.rtCol.texture },
                  uMode: { value: mode } },
      vertexShader: 'out vec2 vUv; void main(){ vUv = uv; gl_Position = vec4(position.xy, 0.0, 1.0); }',
      // GLSL3 does not give you gl_FragColor — declare the output. (three
      // aliases it for its own materials, not for a raw one like this.)
      fragmentShader: `
        precision highp float;
        in vec2 vUv;
        uniform sampler2D uDirs;
        uniform samplerCube uCube;
        uniform int uMode;
        layout(location = 0) out vec4 outColor;
        void main(){
          vec3 d = normalize(textureLod(uDirs, vUv, 0.0).xyz);
          float v = textureLod(uCube, d, 0.0).a;
          if(uMode == 1) v = d.x;
          else if(uMode == 2) v = d.y;
          else if(uMode == 3) v = d.z;
          outColor = vec4(v, 0.0, 0.0, 1.0);
        }`,
      depthTest: false, depthWrite: false,
    });
    const scene = new T.Scene();
    scene.add(new T.Mesh(new T.PlaneGeometry(2, 2), mat));
    const rt = new T.WebGLRenderTarget(w, h, {
      type: T.FloatType, format: T.RGBAFormat,
      minFilter: T.NearestFilter, magFilter: T.NearestFilter,
    });

    const r = this.renderer;
    const prev = r.getRenderTarget();
    r.setRenderTarget(rt);
    r.render(scene, this.camera);
    const buf = new Float32Array(w * h * 4);
    r.readRenderTargetPixels(rt, 0, 0, w, h, buf);
    r.setRenderTarget(prev);

    const out = new Float32Array(n);
    for (let i = 0; i < n; i++) out[i] = buf[i * 4];
    rt.dispose(); mat.dispose(); dirTex.dispose();
    return out;
  }

  /**
   * Bake a function whose value is known at every direction (n.y) and read it
   * back. Separates "is the cube the right way round" from "is the height
   * field right" — two failures that are indistinguishable in a picture and
   * indistinguishable in a correlation against the CPU mirror.
   * Leaves rtCol holding the probe, so re-run run() afterwards.
   */
  checkOrientation(uniforms, dirs, probePass = 3) {
    this.setWorld(uniforms, true);      // compiles the probe variant, once
    const r = this.renderer;
    const prev = r.getRenderTarget(), prevFace = r.getActiveCubeFace();
    this.mat.uniforms.uPass.value = probePass;
    for (let f = 0; f < 6; f++) {
      this.mat.uniforms.uFaceIx.value = f;
      this.rtCol.texture.generateMipmaps = (f === 5);
      r.setRenderTarget(this.rtCol, f);
      r.render(this.scene, this.camera);
    }
    r.setRenderTarget(prev, prevFace);
    return this.probeHeights(dirs, 0);
  }

  dispose() {
    this.rtCol.dispose(); this.rtEmis.dispose(); this.rtNrm.dispose();
    this.scene.traverse(o => { if (o.geometry) o.geometry.dispose(); });
    this.mat.dispose();
  }
}
