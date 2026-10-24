import * as THREE from "three";
import { CONFIG } from "./config.js";
import { G } from "./gameState.js";
import { defaultUniverse } from "./universeSchema.js";
import {
  buildPlanetGeometry,
  createTerrainContext,
  getRadialDisplacement,
  terrainProfileForStyle,
} from "./planetTerrain.js";

/**
 * Surface point from planet center in **world space** (matches rotated mesh).
 * Noise lives in mesh-local space; result is rotated back so walking + treadmill align.
 */
const _surfInvQ = new THREE.Quaternion();
const _surfLocalDir = new THREE.Vector3();

export function getPlanetSurfaceWorld(planet, worldDirectionUnitFromCenter) {
  const radius = planet.userData.radius;
  const inv = _surfInvQ.copy(planet.quaternion).invert();
  const localDir = _surfLocalDir.copy(worldDirectionUnitFromCenter);
  /** Guard zero-length direction (normalize would yield NaN and poison positions). */
  if (localDir.lengthSq() < 1e-12) localDir.set(0, 1, 0);
  localDir.normalize().applyQuaternion(inv);
  let dist;
  const { terrainCtx, terrainProfile } = planet.userData;
  if (terrainCtx && terrainProfile) {
    const mul = getRadialDisplacement(terrainCtx, localDir, radius, terrainProfile);
    dist = radius * mul;
  } else {
    const x = localDir.x * radius;
    const y = localDir.y * radius;
    const z = localDir.z * radius;
    const n1 = Math.sin(x * 0.002) * Math.cos(y * 0.002) * Math.sin(z * 0.002);
    const n2 = Math.sin(x * 0.01 + 100) * Math.cos(y * 0.01 + 100);
    dist = radius * (1 + (n1 * 5 + n2) * 0.02);
  }
  /** Returned vectors stay freshly allocated — callers hold onto them across calls. */
  const worldOffset = localDir.clone().multiplyScalar(dist).applyQuaternion(planet.quaternion);
  const len = worldOffset.length();
  const outwardWorld =
    len > 1e-8 ? worldOffset.clone().multiplyScalar(1 / len) : new THREE.Vector3(0, 1, 0);
  return { worldOffset, outwardWorld, distance: len };
}

/** Scalar radius only — prefer `getPlanetSurfaceWorld` for collisions. */
export function getPlanetTerrainHeight(planet, worldDirectionFromCenter) {
  return getPlanetSurfaceWorld(planet, worldDirectionFromCenter).distance;
}

/** World axis the planet spins around this frame (local +Y). */
export function getPlanetSpinAxisWorld(planet) {
  return new THREE.Vector3(0, 1, 0).applyQuaternion(planet.quaternion).normalize();
}

function makeGlowSprite(scene, radius) {
  const canvas = document.createElement("canvas");
  canvas.width = 128;
  canvas.height = 128;
  const ctx = canvas.getContext("2d");
  const gradient = ctx.createRadialGradient(64, 64, 0, 64, 64, 64);
  gradient.addColorStop(0, "rgba(255, 200, 100, 1)");
  gradient.addColorStop(0.2, "rgba(255, 150, 50, 0.8)");
  gradient.addColorStop(0.5, "rgba(255, 100, 0, 0.2)");
  gradient.addColorStop(1, "rgba(0, 0, 0, 0)");
  ctx.fillStyle = gradient;
  ctx.fillRect(0, 0, 128, 128);
  const tex = new THREE.CanvasTexture(canvas);
  const sprite = new THREE.Sprite(
    new THREE.SpriteMaterial({ map: tex, blending: THREE.AdditiveBlending, depthWrite: false })
  );
  sprite.scale.set(radius * 6, radius * 6, 1);
  scene.add(sprite);
  return sprite;
}

function getBodyStyle(body) {
  if (body.terrainStyle) return body.terrainStyle;
  if (body.type === "sun") return "sun";
  if (body.type === "moon") return "moon";
  return "rocky";
}

function getTerrainSegments(radius, terrainStyle) {
  const wSeg =
    terrainStyle === "gas"
      ? radius > 55000
        ? 144
        : radius > 32000
          ? 128
          : 112
      : radius > 26000
        ? 216
        : 200;
  const hSeg =
    terrainStyle === "gas"
      ? radius > 55000
        ? 108
        : radius > 32000
          ? 96
          : 84
      : radius > 26000
        ? 144
        : 132;
  return { wSeg, hSeg };
}

function computeLocalOrbitOffset(userData) {
  const radius = userData.orbitDist || 0;
  const inc = userData.orbitInclination || 0;
  const c = Math.cos(userData.angle || 0);
  const s = Math.sin(userData.angle || 0);
  const y = s * radius * Math.sin(inc);
  const z = s * radius * Math.cos(inc);
  return new THREE.Vector3(c * radius, y, z);
}

function clearExistingCelestial() {
  if (!Array.isArray(G.celestialObjects)) return;
  G.celestialObjects.forEach((obj) => G.scene.remove(obj));
  G.celestialObjects = [];
}

export function setupSolarSystem(universeInput = null) {
  const { scene } = G;
  const universe = universeInput || defaultUniverse();
  const oldBodies = new Set(G.celestialBodies || []);
  clearExistingCelestial();
  G.interactables = (G.interactables || []).filter((obj) => !oldBodies.has(obj));
  G.celestialBodies = [];
  G.bodyById = new Map();
  G.activeUniverse = universe;
  if (!G.celestialObjects) G.celestialObjects = [];

  const ambient = new THREE.AmbientLight(0x223344, 0.4);
  scene.add(ambient);
  G.celestialObjects.push(ambient);

  let planetBuildIndex = 0;
  const saltFromMeta = universe.meta && Number.isFinite(universe.meta.seed) ? Number(universe.meta.seed) : null;
  const sessionTerrainSalt = saltFromMeta != null ? saltFromMeta >>> 0 : (Math.random() * 2 ** 31) >>> 0;
  const createBody = (body) => {
    const radius = Math.max(100, Number(body.radius) || 1000);
    const color = Number(body.color) || 0xffffff;
    const terrainStyle = getBodyStyle(body);
    const name = body.name || body.id || "Unnamed";
    const gravity = Number(body.surfaceGravity) || 0;
    const isSun = body.type === "sun";
    const seed =
      (planetBuildIndex++ * 7919 + name.length * 97 + Math.floor(radius) + sessionTerrainSalt) >>> 0;
    const terrainCtx = createTerrainContext(seed);
    const terrainProfile = terrainProfileForStyle(isSun ? "gas" : terrainStyle);
    const { wSeg, hSeg } = getTerrainSegments(radius, terrainStyle);
    const geo = isSun
      ? new THREE.SphereGeometry(radius, 48, 48)
      : buildPlanetGeometry(radius, wSeg, hSeg, terrainCtx, terrainProfile, color);
    const mat = isSun
      ? new THREE.MeshBasicMaterial({ color })
      : new THREE.MeshStandardMaterial({
          color: 0xffffff,
          vertexColors: true,
          roughness:
            terrainStyle === "gas"
              ? 0.52
              : terrainStyle === "ice"
                ? 0.68
                : terrainStyle === "moon"
                  ? 0.9
                  : 0.84,
          metalness: terrainStyle === "gas" ? 0.05 : terrainStyle === "ice" ? 0.04 : 0.06,
        });
    const mesh = new THREE.Mesh(geo, mat);
    mesh.receiveShadow = true;
    mesh.castShadow = isSun;
    const orbit = body.orbit || {};
    const rotation = body.rotation || {};
    mesh.userData = {
      isPlanet: true,
      id: body.id || `body-${planetBuildIndex}`,
      type: body.type || "planet",
      radius,
      orbitDist: Math.max(0, Number(orbit.semiMajor) || 0),
      orbitSpeed: Number(orbit.orbitSpeed) || 0,
      orbitInclination: Number(orbit.inclination) || 0,
      angle: Number(orbit.phase) || Math.random() * Math.PI * 2,
      name,
      surfaceGravity: gravity,
      frameRot: 0,
      angularVelocity: new THREE.Vector3(),
      previousPosition: new THREE.Vector3(),
      terrainCtx,
      terrainProfile,
      terrainStyle,
      parentId: body.parentId || null,
      parentBody: null,
      spinSpeed: Number(rotation.spinSpeed),
      axialTilt: Number(rotation.axialTilt) || 0,
      orbitEccentricity: Number(orbit.eccentricity) || 0,
    };
    mesh.rotateZ(mesh.userData.axialTilt);

    if (!isSun) {
      const atmIn = new THREE.Mesh(
        new THREE.SphereGeometry(radius * 1.04, 48, 48),
        new THREE.MeshBasicMaterial({
          color: 0x5ab8ec,
          transparent: true,
          opacity: 0.16,
          side: THREE.BackSide,
          depthWrite: false,
        })
      );
      mesh.add(atmIn);
      const atmOut = new THREE.Mesh(
        new THREE.SphereGeometry(radius * 1.09, 40, 40),
        new THREE.MeshBasicMaterial({
          color: 0x9bdcff,
          transparent: true,
          opacity: 0.07,
          side: THREE.BackSide,
          depthWrite: false,
        })
      );
      mesh.add(atmOut);
      atmIn.raycast = () => {};
      atmOut.raycast = () => {};
    } else {
      const glow = makeGlowSprite(scene, radius);
      glow.userData.followBody = mesh;
      G.celestialObjects.push(glow);
      const sunLight = new THREE.PointLight(0xffffff, Number(body.lightIntensity) || 2, 0, 0);
      sunLight.castShadow = true;
      sunLight.shadow.mapSize.set(4096, 4096);
      sunLight.shadow.camera.far = 400000;
      sunLight.userData.followBody = mesh;
      scene.add(sunLight);
      G.celestialObjects.push(sunLight);
    }

    scene.add(mesh);
    G.interactables.push(mesh);
    G.celestialBodies.push(mesh);
    G.celestialObjects.push(mesh);
    G.bodyById.set(mesh.userData.id, mesh);
    return mesh;
  };

  universe.bodies.forEach((body) => {
    if (!body || !body.id) return;
    createBody(body);
  });

  G.celestialBodies.forEach((mesh) => {
    if (mesh.userData.parentId) mesh.userData.parentBody = G.bodyById.get(mesh.userData.parentId) || null;
    mesh.userData.previousPosition.copy(mesh.position);
  });
  updateOrbits(0);

  const sGeo = new THREE.BufferGeometry();
  const sPos = [];
  for (let i = 0; i < 20000; i++) {
    const v = new THREE.Vector3().randomDirection().multiplyScalar(400000);
    sPos.push(v.x, v.y, v.z);
  }
  sGeo.setAttribute("position", new THREE.Float32BufferAttribute(sPos, 3));
  const stars = new THREE.Points(
    sGeo,
    new THREE.PointsMaterial({
      color: 0xffffff,
      size: 18,
      sizeAttenuation: true,
      transparent: true,
      opacity: 0.9,
      depthWrite: false,
    })
  );
  scene.add(stars);
  G.celestialObjects.push(stars);
}

export function updateOrbits(dt) {
  const resolved = new Set();
  const tempStack = new Set();
  const resolveBody = (body) => {
    if (resolved.has(body)) return;
    if (tempStack.has(body)) return;
    tempStack.add(body);
    const parent = body.userData.parentBody;
    if (parent) resolveBody(parent);

    body.userData.previousPosition.copy(body.position);
    if (body.userData.orbitSpeed && dt > 0) {
      body.userData.angle +=
        body.userData.orbitSpeed * CONFIG.orbitAroundSunMul * CONFIG.timeScale * 60 * dt;
    }
    const localOffset = computeLocalOrbitOffset(body.userData);
    if (parent) {
      body.position.copy(parent.position).add(localOffset);
    } else {
      body.position.copy(localOffset);
    }

    const baseSpin = Number.isFinite(body.userData.spinSpeed) ? body.userData.spinSpeed : CONFIG.planetSpinSpeed;
    const rotAmt = baseSpin * CONFIG.planetSpinMul * dt;
    body.rotation.y += rotAmt;
    body.userData.frameRot = rotAmt;
    body.userData.angularVelocity.set(0, baseSpin * CONFIG.planetSpinMul, 0);
    resolved.add(body);
    tempStack.delete(body);
  };

  G.celestialBodies.forEach((body) => resolveBody(body));
  if (!Array.isArray(G.celestialObjects)) return;
  G.celestialObjects.forEach((obj) => {
    if (!obj.userData || !obj.userData.followBody) return;
    obj.position.copy(obj.userData.followBody.position);
  });
}
