import * as THREE from 'three';
import { makeCamera } from '../analyze/camera.js';
import { clamp } from '../analyze/util.js';

/**
 * WorldSpec -> three.js scene.
 *
 * COORDINATES: analysis space measures depth as +z INTO the photo; three.js
 * cameras look down -z. Everything crossing that boundary negates z, and every
 * such site is marked. Getting this wrong mirrors the world, which is subtle
 * enough to survive a screenshot and obvious the moment you walk.
 *
 * THE DIVE: every vertex and prop carries two poses. The world pose is where it
 * belongs in 3D. The flat pose is where it sits in the original photograph,
 * expressed in normalized image coordinates and resolved to a plane in front of
 * the spawn point by the shader. At uDive = 0 the geometry reassembles into the
 * photo; at 1 it is the world. Because the terrain was built by inverse-
 * projecting the photo, projecting it back is exact -- the ground folds down
 * out of the picture into the floor under your feet with no seam.
 */

export const DIVE_PLANE_DISTANCE = 30;

export function buildScene(spec, viewport) {
  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(
    spec.camera.fovDeg,
    viewport.width / viewport.height,
    0.1,
    2000,
  );

  const fogColor = new THREE.Color(spec.fog.color);
  scene.fog = new THREE.Fog(fogColor, spec.fog.near, spec.fog.far);
  scene.background = fogColor;

  const dive = { value: 0 };
  const flatUniforms = {
    uDive: dive,
    uVisW: { value: 1 },
    uVisH: { value: 1 },
    uPlaneZ: { value: -DIVE_PLANE_DISTANCE },
    uEyeY: { value: spec.camera.eyeHeight },
  };

  addLights(scene, spec);

  const disposables = [];
  const parts = { terrain: null, props: [], sky: null, monolith: null };

  if (spec.ground) {
    parts.terrain = buildTerrain(spec, flatUniforms, disposables);
    scene.add(parts.terrain.mesh);
  }
  if (spec.sky?.enabled) {
    parts.sky = buildSky(spec, disposables);
    scene.add(parts.sky);
  } else if (spec.sky?.enclosedColor) {
    parts.sky = buildEnclosure(spec, disposables);
    scene.add(parts.sky);
  }
  if (spec.props?.length) {
    parts.props = buildProps(spec, disposables);
    for (const p of parts.props) scene.add(p.mesh);
  }
  if (spec.gallery) {
    parts.monolith = buildMonolith(spec, disposables);
    scene.add(parts.monolith);
  }

  // The photo itself, held in front of the camera at t=0 and faded out as the
  // geometry takes over. Covers any sub-pixel disagreement between the
  // reassembled geometry and the source image during the first moments.
  const backdrop = buildBackdrop(spec, disposables);
  camera.add(backdrop.mesh);
  scene.add(camera);

  function setViewportSize(w, h) {
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
    // Size the flat plane to COVER the viewport, so the photo fills the screen
    // regardless of how its aspect compares to the window's.
    const visH = 2 * DIVE_PLANE_DISTANCE * Math.tan((spec.camera.fovDeg * Math.PI) / 360);
    const cover = Math.max(1, w / h / spec.source.aspect);
    flatUniforms.uVisH.value = visH * cover;
    flatUniforms.uVisW.value = visH * cover * spec.source.aspect;
    backdrop.resize(w / h);
  }
  setViewportSize(viewport.width, viewport.height);

  function setDive(t) {
    const k = clamp(t, 0, 1);
    dive.value = k;

    // Props are few, so they lerp on the CPU.
    for (const p of parts.props) p.applyDive(k, flatUniforms);

    // The photo backdrop clears early; by then the geometry already matches it.
    backdrop.mesh.visible = k < 0.35;
    backdrop.material.opacity = clamp(1 - k / 0.35, 0, 1);

    if (parts.sky) {
      parts.sky.visible = k > 0.02;
      if (parts.sky.material) parts.sky.material.opacity = clamp((k - 0.02) / 0.4, 0, 1);
    }
  }
  setDive(0);

  function heightAt(x, z) {
    return parts.terrain ? parts.terrain.heightAt(x, z) : 0;
  }

  function faceProps(cameraPos) {
    for (const p of parts.props) p.face(cameraPos, dive.value);
  }

  function dispose() {
    for (const d of disposables) d.dispose?.();
  }

  return { scene, camera, setDive, setViewportSize, heightAt, faceProps, dispose, parts };
}

// --- lights ----------------------------------------------------------------

function addLights(scene, spec) {
  const l = spec.light;
  const sun = new THREE.DirectionalLight(new THREE.Color(l.sunColor), l.sunIntensity);
  // analysis +z is into the photo -> three -z
  sun.position.set(l.direction.x * 100, l.direction.y * 100, -l.direction.z * 100);
  scene.add(sun);

  scene.add(new THREE.AmbientLight(new THREE.Color(l.ambientColor), l.ambientIntensity));

  // A dim upward bounce keeps undersides of props from going pure black.
  const hemi = new THREE.HemisphereLight(
    new THREE.Color(spec.sky?.top || l.ambientColor),
    new THREE.Color(l.ambientColor),
    0.35,
  );
  scene.add(hemi);
}

// --- terrain ---------------------------------------------------------------

function buildTerrain(spec, flatUniforms, disposables) {
  const g = spec.ground;
  const n = g.gridRes;
  const geo = new THREE.PlaneGeometry(g.size, g.size, n - 1, n - 1);
  geo.rotateX(-Math.PI / 2); // lie flat; +y up

  const pos = geo.attributes.position;
  const flat = new Float32Array(pos.count * 3);

  // The camera that took the photo, in analysis pixel units, so world points
  // can be projected back to the image they came from.
  const cam = makeCamera(1, 1, 0, { fovDeg: spec.camera.fovDeg, eyeHeight: spec.camera.eyeHeight });
  const f = 0.5 / Math.tan((spec.camera.fovDeg * Math.PI) / 360); // focal, in image heights
  const yh = spec.camera.horizonY;

  const heights = g.heights;

  for (let i = 0; i < pos.count; i++) {
    const x = pos.getX(i);
    // PlaneGeometry spans -size/2..size/2; the world's z range starts at zMin.
    const zPlane = pos.getZ(i);
    const zAnalysis = zPlane + g.size / 2 + g.zMin;

    const u = (x / g.size + 0.5) * (n - 1);
    const v = ((zAnalysis - g.zMin) / g.size) * (n - 1);
    const h = sampleGrid(heights, n, u, v);
    pos.setY(i, h);
    pos.setZ(i, -zAnalysis); // analysis +z (forward) -> three -z

    // Flat pose: project this ground point back into the photograph.
    let fu = 0.5;
    let fv = 1.2; // default: below the frame, i.e. invisible at t=0
    if (zAnalysis > 0.6) {
      fv = yh + (f * spec.camera.eyeHeight) / zAnalysis;
      fu = 0.5 + (x * f) / zAnalysis / spec.source.aspect;
    }
    flat[i * 3] = fu;
    flat[i * 3 + 1] = fv;
    flat[i * 3 + 2] = 1;
  }
  pos.needsUpdate = true;
  geo.setAttribute('aFlat', new THREE.BufferAttribute(flat, 3));
  geo.computeVertexNormals();

  const tex = new THREE.TextureLoader().load(g.texture);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.wrapS = tex.wrapT = THREE.ClampToEdgeWrapping;
  tex.anisotropy = 8;
  disposables.push(tex);

  const mat = new THREE.MeshStandardMaterial({ map: tex, roughness: 0.95, metalness: 0 });
  injectDive(mat, flatUniforms);
  disposables.push(mat, geo);

  const mesh = new THREE.Mesh(geo, mat);
  mesh.frustumCulled = false; // the dive moves vertices far outside their bounds

  return {
    mesh,
    heightAt(x, z) {
      // z here is three-space; convert back to analysis depth.
      const zAnalysis = -z;
      const u = ((x + g.size / 2) / g.size) * (n - 1);
      const v = ((zAnalysis - g.zMin) / g.size) * (n - 1);
      if (u < 0 || v < 0 || u > n - 1 || v > n - 1) return 0;
      return sampleGrid(heights, n, u, v);
    },
  };
}

/** Bilinear sample of the heightfield. */
function sampleGrid(grid, n, u, v) {
  const x0 = clamp(Math.floor(u), 0, n - 1);
  const y0 = clamp(Math.floor(v), 0, n - 1);
  const x1 = Math.min(n - 1, x0 + 1);
  const y1 = Math.min(n - 1, y0 + 1);
  const ax = u - x0;
  const ay = v - y0;
  const a = grid[y0 * n + x0];
  const b = grid[y0 * n + x1];
  const c = grid[y1 * n + x0];
  const d = grid[y1 * n + x1];
  return (a * (1 - ax) + b * ax) * (1 - ay) + (c * (1 - ax) + d * ax) * ay;
}

/**
 * Teach a standard material to blend between its world pose and its flat pose.
 * The flat attribute stores normalized image coordinates, so the resolved
 * position adapts to the viewport without rebuilding any geometry.
 */
function injectDive(material, uniforms) {
  material.onBeforeCompile = (shader) => {
    Object.assign(shader.uniforms, uniforms);
    shader.vertexShader = shader.vertexShader
      .replace(
        '#include <common>',
        `#include <common>
         attribute vec3 aFlat;
         uniform float uDive;
         uniform float uVisW;
         uniform float uVisH;
         uniform float uPlaneZ;
         uniform float uEyeY;`,
      )
      .replace(
        '#include <begin_vertex>',
        `vec3 flatPos = vec3(
           (aFlat.x - 0.5) * uVisW,
           uEyeY + (0.5 - aFlat.y) * uVisH,
           uPlaneZ);
         vec3 transformed = mix(flatPos, position, smoothstep(0.0, 1.0, uDive));`,
      );
  };
}

// --- sky -------------------------------------------------------------------

function buildSky(spec, disposables) {
  const geo = new THREE.SphereGeometry(900, 48, 24);
  const tex = new THREE.TextureLoader().load(spec.sky.texture);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.wrapS = THREE.RepeatWrapping;
  disposables.push(tex, geo);

  const mat = new THREE.MeshBasicMaterial({
    map: tex,
    side: THREE.BackSide,
    fog: false,
    transparent: true,
    depthWrite: false,
  });
  disposables.push(mat);
  const mesh = new THREE.Mesh(geo, mat);
  mesh.renderOrder = -1;
  return mesh;
}

function buildEnclosure(spec, disposables) {
  const geo = new THREE.SphereGeometry(spec.ground ? spec.ground.size * 0.6 : 60, 24, 16);
  const mat = new THREE.MeshBasicMaterial({
    color: new THREE.Color(spec.sky.enclosedColor),
    side: THREE.BackSide,
    transparent: true,
    depthWrite: false,
  });
  disposables.push(geo, mat);
  const mesh = new THREE.Mesh(geo, mat);
  mesh.renderOrder = -1;
  return mesh;
}

// --- props -----------------------------------------------------------------

function buildProps(spec, disposables) {
  const loader = new THREE.TextureLoader();
  return spec.props.map((p) => {
    const tex = loader.load(p.texture);
    tex.colorSpace = THREE.SRGBColorSpace;
    disposables.push(tex);

    const mat = new THREE.MeshBasicMaterial({
      map: tex,
      transparent: true,
      alphaTest: 0.35,
      side: THREE.DoubleSide,
    });
    disposables.push(mat);

    const geo = new THREE.PlaneGeometry(1, 1);
    disposables.push(geo);
    const mesh = new THREE.Mesh(geo, mat);
    mesh.frustumCulled = false;

    const world = new THREE.Vector3(p.world.x, p.world.height / 2, -p.world.z);

    return {
      mesh,
      spec: p,
      applyDive(t, u) {
        const k = t * t * (3 - 2 * t);
        const flatW = p.flat.w * u.uVisW.value;
        const flatH = p.flat.h * u.uVisH.value;
        const flatX = (p.flat.x + p.flat.w / 2 - 0.5) * u.uVisW.value;
        const flatY = u.uEyeY.value + (0.5 - (p.flat.y + p.flat.h / 2)) * u.uVisH.value;

        mesh.scale.set(
          flatW + (p.world.width - flatW) * k,
          flatH + (p.world.height - flatH) * k,
          1,
        );
        mesh.position.set(
          flatX + (world.x - flatX) * k,
          flatY + (world.y - flatY) * k,
          u.uPlaneZ.value + (world.z - u.uPlaneZ.value) * k,
        );
      },
      /** Billboard about Y only, and only once standing in the world. */
      face(cameraPos, t) {
        if (t < 0.5) {
          mesh.rotation.set(0, 0, 0);
          return;
        }
        const dx = cameraPos.x - mesh.position.x;
        const dz = cameraPos.z - mesh.position.z;
        mesh.rotation.y = Math.atan2(dx, dz) * (t - 0.5) * 2;
      },
    };
  });
}

// --- gallery (closeup / abstract) ------------------------------------------

function buildMonolith(spec, disposables) {
  const group = new THREE.Group();
  const h = spec.gallery.monolith.height;
  const w = h * spec.source.aspect;

  const tex = new THREE.TextureLoader().load(spec.source.photo);
  tex.colorSpace = THREE.SRGBColorSpace;
  const mat = new THREE.MeshBasicMaterial({ map: tex, side: THREE.DoubleSide });
  const geo = new THREE.PlaneGeometry(w, h);
  disposables.push(tex, mat, geo);

  const slab = new THREE.Mesh(geo, mat);
  slab.position.set(0, h / 2 + 0.4, -spec.gallery.monolith.distance);
  group.add(slab);

  // A floor to stand on, so the void still has a ground plane.
  const floorGeo = new THREE.CircleGeometry(70, 48);
  floorGeo.rotateX(-Math.PI / 2);
  const floorMat = new THREE.MeshStandardMaterial({
    color: new THREE.Color(
      `rgb(${spec.gallery.voidColor.map((v) => Math.round(v)).join(',')})`,
    ),
    roughness: 0.7,
    metalness: 0.15,
  });
  disposables.push(floorGeo, floorMat);
  group.add(new THREE.Mesh(floorGeo, floorMat));

  return group;
}

// --- backdrop --------------------------------------------------------------

function buildBackdrop(spec, disposables) {
  const tex = new THREE.TextureLoader().load(spec.source.photo);
  tex.colorSpace = THREE.SRGBColorSpace;
  const material = new THREE.MeshBasicMaterial({
    map: tex,
    transparent: true,
    depthTest: false,
    depthWrite: false,
    fog: false,
  });
  const geo = new THREE.PlaneGeometry(1, 1);
  disposables.push(tex, material, geo);

  const mesh = new THREE.Mesh(geo, material);
  mesh.position.set(0, 0, -DIVE_PLANE_DISTANCE);
  mesh.renderOrder = 10;

  function resize(screenAspect) {
    const visH = 2 * DIVE_PLANE_DISTANCE * Math.tan((spec.camera.fovDeg * Math.PI) / 360);
    const cover = Math.max(1, screenAspect / spec.source.aspect);
    mesh.scale.set(visH * cover * spec.source.aspect, visH * cover, 1);
  }

  return { mesh, material, resize };
}
