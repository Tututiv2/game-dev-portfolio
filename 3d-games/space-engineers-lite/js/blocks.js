import * as THREE from "three";

/** Block dictionary — from Space Game / cracker.html */
export const BLOCKS = {
  1: { name: "Armor", color: 0x555555, type: "cube", cat: "structure", mass: 20 },
  2: { name: "Thruster", color: 0xffaa00, type: "cone", cat: "propulsion", mass: 100 },
  3: { name: "Battery", color: 0x228822, type: "cube", emissive: 0x003300, cat: "systems", mass: 80 },
  4: { name: "Cockpit", color: 0xdddddd, type: "cockpit", cat: "tools", mass: 50 },
  5: { name: "Glass", color: 0x88ccff, type: "cube", opacity: 0.3, cat: "structure", mass: 5 },
  6: { name: "Drill", color: 0xcc4444, type: "drill", cat: "tools", mass: 150 },
  7: { name: "Light", color: 0xffffaa, type: "cube", emissive: 0x444422, cat: "systems", mass: 5 },
  8: { name: "Wheel", color: 0x222222, type: "cylinder", cat: "propulsion", mass: 40 },
  9: { name: "Beam", color: 0x444444, type: "cube", cat: "structure", mass: 10 },
  10: { name: "Land Gear", color: 0x444444, type: "pad", cat: "structure", mass: 30 },
  11: { name: "Grav Gen", color: 0x0000aa, type: "gravity", emissive: 0x000044, cat: "systems", mass: 200 },
};

export function getBlockDef(id) {
  return BLOCKS[id] || BLOCKS[1];
}

/**
 * Returns THREE.Group for composite shapes, or THREE.BufferGeometry for simple meshes.
 * Uses mesh rotations (BufferGeometry no longer has .rotateX in modern Three).
 */
export function createBlockGeometry(id, gridSize) {
  const def = BLOCKS[id];
  const s = gridSize;

  if (def.type === "cone") {
    const g = new THREE.Group();
    const matT = new THREE.MeshStandardMaterial({ color: def.color, roughness: 0.5, metalness: 0.3 });
    const body = new THREE.Mesh(new THREE.ConeGeometry(s * 0.4, s, 16), matT);
    body.rotation.x = Math.PI / 2;
    body.position.z = s * 0.25;
    const nozzle = new THREE.Mesh(
      new THREE.CylinderGeometry(s * 0.2, s * 0.3, s * 0.1, 16),
      new THREE.MeshStandardMaterial({ color: 0x333333, roughness: 0.8 })
    );
    nozzle.rotation.x = Math.PI / 2;
    nozzle.position.z = -s * 0.25;
    g.add(body, nozzle);
    return g;
  }
  if (def.type === "cockpit") return new THREE.BoxGeometry(s * 0.8, s * 0.8, s * 0.8);
  if (def.type === "cylinder")
    return new THREE.CylinderGeometry(s * 0.4, s * 0.4, s * 0.4, 24);
  if (def.type === "drill") {
    const g = new THREE.Group();
    const shaft = new THREE.Mesh(
      new THREE.CylinderGeometry(s * 0.15, s * 0.25, s * 0.5, 12),
      new THREE.MeshStandardMaterial({ color: 0x666666, metalness: 0.6, roughness: 0.4 })
    );
    shaft.rotation.z = Math.PI / 2;
    const tip = new THREE.Mesh(
      new THREE.ConeGeometry(s * 0.25, s * 0.2, 8),
      new THREE.MeshStandardMaterial({ color: def.color, metalness: 0.4, roughness: 0.5 })
    );
    tip.rotation.z = -Math.PI / 2;
    tip.position.x = s * 0.35;
    g.add(shaft, tip);
    return g;
  }
  if (def.type === "gravity") {
    const g = new THREE.Group();
    const base = new THREE.Mesh(
      new THREE.BoxGeometry(s, s * 0.2, s),
      new THREE.MeshStandardMaterial({ color: 0x222222 })
    );
    base.position.y = -s * 0.4;
    const core = new THREE.Mesh(
      new THREE.SphereGeometry(s * 0.28, 16, 16),
      new THREE.MeshStandardMaterial({
        color: 0x2244ff,
        emissive: def.emissive ?? 0x000044,
        emissiveIntensity: 0.6,
        metalness: 0.2,
        roughness: 0.35,
      })
    );
    g.add(base, core);
    return g;
  }
  if (def.type === "pad") {
    const g = new THREE.Group();
    const foot = new THREE.Mesh(
      new THREE.BoxGeometry(s * 0.8, s * 0.2, s * 0.8),
      new THREE.MeshStandardMaterial({ color: 0x222222 })
    );
    foot.position.y = -s * 0.4;
    const strut = new THREE.Mesh(
      new THREE.CylinderGeometry(s * 0.3, s * 0.3, s * 0.6, 16),
      new THREE.MeshStandardMaterial({ color: 0x555555, metalness: 0.4, roughness: 0.55 })
    );
    const light = new THREE.Mesh(
      new THREE.SphereGeometry(s * 0.1, 12, 12),
      new THREE.MeshBasicMaterial({ color: 0xff0000 })
    );
    light.name = "statusLight";
    light.position.set(0, 0, s * 0.3);
    g.add(foot, strut, light);
    return g;
  }
  return new THREE.BoxGeometry(s, s, s);
}

export function makeBlockMaterial(def) {
  const mat = new THREE.MeshStandardMaterial({
    color: def.color,
    roughness: 0.6,
    metalness: 0.2,
    emissive: def.emissive ?? 0x000000,
    emissiveIntensity: def.emissive ? 0.35 : 0,
    transparent: def.opacity != null,
    opacity: def.opacity ?? 1,
  });
  return mat;
}
