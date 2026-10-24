import * as THREE from "three";
import type { DevSettings } from "./devSettings";
import {
  fillSeededSpherePoints,
  SKY_DENSE_STARS,
  SKY_GALACTIC_POINTS,
  SKY_MAIN_STARS,
} from "../planet/skydomeSeed";
import { mix4 } from "../sim/universe/hash";

function u01l(seed: number, i: number, salt: number): number {
  return (mix4(seed, i >>> 0, salt, 0x4b1d) & 0xfffffff) / 0x1000_0000;
}

/**
 * Skydome + **deterministic** starfield (seeded) + galactic band.
 */
export function createSimpleSurface(sunDir: THREE.Vector3, skyRadius: number, skydomeSeed: number): {
  sky: THREE.Mesh;
  stars: THREE.Points;
  denseStars: THREE.Points;
  galactic: THREE.Points;
  skydomeSeed: number;
  updateSunLight: (dir: THREE.Vector3) => void;
  applyStarDev: (s: DevSettings["sky"]) => void;
} {
  const skyMat = new THREE.MeshBasicMaterial({
    color: 0x030508,
    side: THREE.BackSide,
    depthWrite: false,
    depthTest: false,
    transparent: true,
    opacity: 0.22,
    fog: false,
  });
  const sky = new THREE.Mesh(new THREE.SphereGeometry(skyRadius, 32, 24), skyMat);
  sky.renderOrder = -2;
  sky.frustumCulled = false;
  sky.name = "SimpleSky";

  const positions = new Float32Array(SKY_MAIN_STARS * 3);
  fillSeededSpherePoints(positions, skyRadius * 0.985, skydomeSeed, 0, SKY_MAIN_STARS, 0x11a0);
  const starGeo = new THREE.BufferGeometry();
  starGeo.setAttribute("position", new THREE.BufferAttribute(positions, 3));
  const starMat = new THREE.PointsMaterial({
    color: 0xf2f4ff,
    size: 2.2,
    sizeAttenuation: false,
    transparent: true,
    opacity: 1.0,
    depthWrite: false,
    depthTest: false,
    fog: false,
  });
  const stars = new THREE.Points(starGeo, starMat);
  stars.renderOrder = 0;
  stars.frustumCulled = false;
  stars.name = "SimpleStars";

  const dPos = new Float32Array(SKY_DENSE_STARS * 3);
  fillSeededSpherePoints(dPos, skyRadius * 0.988, skydomeSeed, SKY_MAIN_STARS, SKY_DENSE_STARS, 0x22b0);
  const denseGeo = new THREE.BufferGeometry();
  denseGeo.setAttribute("position", new THREE.BufferAttribute(dPos, 3));
  const denseMat = new THREE.PointsMaterial({
    color: 0xc8d0ff,
    size: 1.0,
    sizeAttenuation: false,
    transparent: true,
    opacity: 0,
    depthWrite: false,
    depthTest: false,
    fog: false,
  });
  const denseStars = new THREE.Points(denseGeo, denseMat);
  denseStars.renderOrder = 0;
  denseStars.frustumCulled = false;
  denseStars.name = "DenseStars";

  const v = new THREE.Vector3();
  const c0 = new THREE.Color();
  const gPos = new Float32Array(SKY_GALACTIC_POINTS * 3);
  const gCol = new Float32Array(SKY_GALACTIC_POINTS * 3);
  for (let i = 0; i < SKY_GALACTIC_POINTS; i++) {
    const u = u01l(skydomeSeed, i, 0x41) * Math.PI * 2;
    const c = u01l(skydomeSeed, i, 0x42) * 2 - 1;
    const p = new THREE.Spherical(skyRadius * 0.99, Math.acos(c), u);
    v.setFromSpherical(p);
    const j = i * 3;
    gPos[j] = v.x;
    gPos[j + 1] = v.y;
    gPos[j + 2] = v.z;
    c0.setHSL(
      0.7 + u01l(skydomeSeed, i, 0x43) * 0.12,
      0.35 + u01l(skydomeSeed, i, 0x44) * 0.45,
      0.45 + u01l(skydomeSeed, i, 0x45) * 0.3
    );
    gCol[j] = c0.r;
    gCol[j + 1] = c0.g;
    gCol[j + 2] = c0.b;
  }
  const galGeo = new THREE.BufferGeometry();
  galGeo.setAttribute("position", new THREE.BufferAttribute(gPos, 3));
  galGeo.setAttribute("color", new THREE.BufferAttribute(gCol, 3));
  const galMat = new THREE.PointsMaterial({
    size: 3.0,
    sizeAttenuation: false,
    vertexColors: true,
    transparent: true,
    opacity: 0.5,
    depthWrite: false,
    depthTest: false,
    blending: THREE.AdditiveBlending,
    fog: false,
  });
  const galactic = new THREE.Points(galGeo, galMat);
  galactic.renderOrder = -1;
  galactic.frustumCulled = false;
  galactic.name = "GalacticBand";

  const applySkyTint = (dir: THREE.Vector3) => {
    const t = 0.12 + 0.08 * (dir.y * 0.5 + 0.5);
    skyMat.color.setHSL(0.6, 0.45, 0.045 + t * 0.04);
    skyMat.opacity = 0.14 + t * 0.08;
  };
  applySkyTint(sunDir);

  return {
    sky,
    stars,
    denseStars,
    galactic,
    skydomeSeed,
    updateSunLight: applySkyTint,
    applyStarDev: (s: DevSettings["sky"]) => {
      const density = 320 / Math.max(80, s.starSpacing);
      starMat.size = THREE.MathUtils.clamp(0.55 + density * 0.28, 0.55, 1.5);
      starMat.opacity = THREE.MathUtils.clamp(0.88 + (s.starThreshold - 0.97) * 2.2, 0.75, 1);
    },
  };
}
