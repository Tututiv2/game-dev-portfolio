/**
 * Spherical planet helpers (P1). Used only when sphere physics is enabled for the active map world.
 */
import * as THREE from 'three';
import * as CANNON from 'cannon-es';

/** Default radial acceleration magnitude (m/s²), comparable to flat |g_y| = 24. */
export const SPHERE_DEFAULT_GRAVITY_STRENGTH = 24;

/**
 * @param {{ x: number; y: number; z: number }} center
 * @param {{ x: number; y: number; z: number }} pos
 * @param {{ x: number; y: number; z: number }} out unit “up” from planet center through pos
 */
export function radialUpAt(center, pos, out) {
  out.x = pos.x - center.x;
  out.y = pos.y - center.y;
  out.z = pos.z - center.z;
  const lenSq = out.x * out.x + out.y * out.y + out.z * out.z;
  if (lenSq < 1e-10) {
    out.set(0, 1, 0);
    return;
  }
  const inv = 1 / Math.sqrt(lenSq);
  out.x *= inv;
  out.y *= inv;
  out.z *= inv;
}

/**
 * @param {{ x: number; y: number; z: number }} v
 * @param {{ x: number; y: number; z: number }} up unit
 * @param {{ x: number; y: number; z: number }} out tangent component of v
 */
export function projectOntoTangentPlane(v, up, out) {
  const dot = v.x * up.x + v.y * up.y + v.z * up.z;
  out.x = v.x - up.x * dot;
  out.y = v.y - up.y * dot;
  out.z = v.z - up.z * dot;
}

/**
 * @param {CANNON.World} world
 * @param {CANNON.Vec3} center
 * @param {number} strength radial acceleration toward center (m/s²)
 * @param {number} dt
 */
export function applySphericalGravity(world, center, strength, dt) {
  if (!world || strength <= 0 || dt <= 0) return;
  const cx = center.x;
  const cy = center.y;
  const cz = center.z;
  for (let i = 0; i < world.bodies.length; i++) {
    const body = world.bodies[i];
    if (body.type !== CANNON.Body.DYNAMIC) continue;
    if (body.mass <= 0) continue;
    if (body.userData?.affectedByPlanetGravity === false) continue;
    const dx = body.position.x - cx;
    const dy = body.position.y - cy;
    const dz = body.position.z - cz;
    const lenSq = dx * dx + dy * dy + dz * dz;
    if (lenSq < 1e-10) continue;
    const inv = 1 / Math.sqrt(lenSq);
    const ax = -dx * inv * strength;
    const ay = -dy * inv * strength;
    const az = -dz * inv * strength;
    body.velocity.x += ax * dt;
    body.velocity.y += ay * dt;
    body.velocity.z += az * dt;
  }
}

/**
 * Inner shell trimesh (walkable cavity). Winding flipped so the contact side faces the interior.
 * @param {number} radius
 * @param {number} widthSegments
 * @param {number} heightSegments
 * @param {CANNON.Material | undefined} material
 */
const _eY = new THREE.Vector3(0, 1, 0);
const _scratchUp = new THREE.Vector3();
const _qTw = new THREE.Quaternion();

/**
 * Unit outward direction (geographic lat/lon, radians). lat 0 = equator, +π/2 = +Y pole; lon from +X toward +Z.
 * @param {number} lat
 * @param {number} lon
 * @param {THREE.Vector3} out
 */
export function sphereLatLonDirToVector3(lat, lon, out) {
  const cl = Math.cos(lat);
  out.x = cl * Math.cos(lon);
  out.y = Math.sin(lat);
  out.z = cl * Math.sin(lon);
}

/**
 * Standing trunk: mesh local +Y aligns with `upThree` (world radial “up”), then yaw around that axis by `yawRy`.
 * @param {THREE.Vector3} upThree outward unit (from planet center); need not be pre-normalized
 * @param {number} yawRy
 * @param {THREE.Quaternion} outQuat
 */
export function trunkQuaternionYAlongWorldUp(upThree, yawRy, outQuat) {
  _scratchUp.copy(upThree).normalize();
  outQuat.setFromUnitVectors(_eY, _scratchUp);
  _qTw.setFromAxisAngle(_scratchUp, yawRy);
  outQuat.premultiply(_qTw);
}

/**
 * Solid planet body: player stands on the **outside** of a `CANNON.Sphere`.
 * Use with {@link applySphericalGravity} which pulls bodies toward `radius`-centered origin,
 * i.e. "down" for a body that's at `|r| > radius`.
 *
 * `CANNON.Sphere` is an analytic shape — no tessellation cost regardless of `radius`,
 * and it scales cleanly to planet-sized radii (millions of meters).
 *
 * @param {number} radius
 * @param {CANNON.Material | undefined} material
 */
export function createSolidPlanetBody(radius, material) {
  const body = new CANNON.Body({ mass: 0, material });
  body.addShape(new CANNON.Sphere(radius));
  return body;
}

export function createInnerSphereShellBody(radius, widthSegments, heightSegments, material) {
  const geo = new THREE.SphereGeometry(radius, widthSegments, heightSegments);
  const pos = geo.attributes.position;
  const vertices = [];
  for (let i = 0; i < pos.count; i++) {
    vertices.push(pos.getX(i), pos.getY(i), pos.getZ(i));
  }
  const idx = geo.index;
  const indices = [];
  if (idx) {
    for (let i = 0; i < idx.count; i += 3) {
      indices.push(idx.getX(i + 1), idx.getX(i), idx.getX(i + 2));
    }
  } else {
    for (let i = 0; i < pos.count; i += 3) {
      indices.push(i + 1, i, i + 2);
    }
  }
  geo.dispose();
  const shape = new CANNON.Trimesh(vertices, indices);
  const body = new CANNON.Body({ mass: 0, material });
  body.addShape(shape);
  return body;
}
