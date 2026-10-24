import * as THREE from "three";
import { CONFIG } from "./config.js";
import { G } from "./gameState.js";
import { createBlockGeometry, getBlockDef, makeBlockMaterial } from "./blocks.js";
import { getPlanetSurfaceWorld } from "./solar.js";

function addToInteractables(obj, userDataRef) {
  const walk = (o) => {
    if (o.isMesh) {
      o.userData = userDataRef;
      G.interactables.push(o);
    }
    for (const c of o.children || []) walk(c);
  };
  walk(obj);
}

function removeFromInteractables(obj) {
  const walk = (o) => {
    if (o.isMesh) {
      const i = G.interactables.indexOf(o);
      if (i > -1) G.interactables.splice(i, 1);
    }
    for (const c of o.children || []) walk(c);
  };
  walk(obj);
}

export class SEGrid extends THREE.Group {
  constructor(isStatic, gridSize = CONFIG.largeGridSize) {
    super();
    this.isStatic = isStatic;
    this.gridSize = gridSize;
    this.blocks = new Map();
    this.gravityGenerators = [];
    this.velocity = new THREE.Vector3();
    this.angularVelocity = new THREE.Vector3();
    this.dampeners = true;
    this.isOn = true;
    this.isParked = false;
    this.totalCapacity = 0;
    this.currentPower = 0;
    this.powerDraw = 0;
    this.thrustCaps = { up: 0, down: 0, left: 0, right: 0, fwd: 0, back: 0 };
  }

  addBlock(localPos, id, quat) {
    const k = `${Math.round(localPos.x)},${Math.round(localPos.y)},${Math.round(localPos.z)}`;
    if (this.blocks.has(k)) return;
    const def = getBlockDef(id);
    const s = this.gridSize;

    const geoStruct = createBlockGeometry(id, s);
    let mesh;

    if (geoStruct.isGroup) {
      mesh = geoStruct;
    } else {
      mesh = new THREE.Mesh(geoStruct, makeBlockMaterial(def));
    }

    mesh.userData = { isBlock: true, id, grid: this, localPos: localPos.clone() };
    const pivot = new THREE.Object3D();
    pivot.position.copy(localPos.clone().multiplyScalar(s));
    pivot.add(mesh);
    mesh.quaternion.copy(quat);
    mesh.userData.quat = quat.clone();
    this.add(pivot);
    this.blocks.set(k, mesh);

    if (def.type === "gravity") this.gravityGenerators.push(mesh);

    addToInteractables(mesh, mesh.userData);
    this.recalcSystem();
  }

  removeBlock(hitMesh) {
    let target = hitMesh;
    while (target.parent && target.parent !== this && !target.userData.isBlock) {
      target = target.parent;
    }

    let foundKey = null;
    for (const [k, m] of this.blocks.entries()) {
      let hasMesh = false;
      m.traverse((c) => {
        if (c === hitMesh) hasMesh = true;
      });
      if (hasMesh) {
        foundKey = k;
        break;
      }
    }

    if (!foundKey) return;

    const foundMesh = this.blocks.get(foundKey);
    const def = getBlockDef(foundMesh.userData.id);
    if (def.type === "gravity") {
      const idx = this.gravityGenerators.indexOf(foundMesh);
      if (idx > -1) this.gravityGenerators.splice(idx, 1);
    }

    removeFromInteractables(foundMesh);

    this.blocks.delete(foundKey);
    this.remove(foundMesh.parent);
    this.recalcSystem();

    if (this.children.length === 0) {
      const gi = G.grids.indexOf(this);
      if (gi > -1) G.grids.splice(gi, 1);
      G.scene.remove(this);
    } else {
      this.handleSplits();
    }
  }

  handleSplits() {
    const allBlocks = Array.from(this.blocks.values());
    const visited = new Set();
    const islands = [];

    for (const block of allBlocks) {
      if (visited.has(block)) continue;
      const island = [];
      const queue = [block];
      visited.add(block);

      while (queue.length > 0) {
        const curr = queue.shift();
        island.push(curr);
        const pos = curr.userData.localPos;
        const neighbors = [
          new THREE.Vector3(1, 0, 0),
          new THREE.Vector3(-1, 0, 0),
          new THREE.Vector3(0, 1, 0),
          new THREE.Vector3(0, -1, 0),
          new THREE.Vector3(0, 0, 1),
          new THREE.Vector3(0, 0, -1),
        ];
        for (const n of neighbors) {
          const nPos = pos.clone().add(n);
          const k = `${Math.round(nPos.x)},${Math.round(nPos.y)},${Math.round(nPos.z)}`;
          if (this.blocks.has(k)) {
            const nb = this.blocks.get(k);
            if (!visited.has(nb)) {
              visited.add(nb);
              queue.push(nb);
            }
          }
        }
      }
      islands.push(island);
    }

    if (islands.length > 1) {
      islands.sort((a, b) => b.length - a.length);
      for (let i = 1; i < islands.length; i++) this.detachIsland(islands[i]);
    }
    this.checkAnchors();
  }

  detachIsland(islandBlocks) {
    const newGrid = new SEGrid(false, this.gridSize);
    newGrid.position.copy(this.position);
    newGrid.quaternion.copy(this.quaternion);
    G.scene.add(newGrid);
    G.grids.push(newGrid);

    for (const mesh of islandBlocks) {
      const pivot = mesh.parent;
      const pos = mesh.userData.localPos;
      const k = `${Math.round(pos.x)},${Math.round(pos.y)},${Math.round(pos.z)}`;

      removeFromInteractables(mesh);
      this.blocks.delete(k);
      this.remove(pivot);

      const def = getBlockDef(mesh.userData.id);
      if (def.type === "gravity") {
        const idx = this.gravityGenerators.indexOf(mesh);
        if (idx > -1) this.gravityGenerators.splice(idx, 1);
      }

      newGrid.addBlock(pos, mesh.userData.id, mesh.userData.quat);
    }
    this.recalcSystem();
    newGrid.checkAnchors();
  }

  checkAnchors() {
    if (!this.isStatic) return;
    let anchored = false;
    let bestP = null;
    G.celestialBodies.forEach((p) => {
      if (this.position.distanceTo(p.position) < p.userData.radius * 2) bestP = p;
    });
    if (bestP) {
      const temp = new THREE.Vector3();
      const dir = new THREE.Vector3();
      for (const c of this.children) {
        c.children[0].getWorldPosition(temp);
        /** Compare against actual terrain height (mountains rise far above base radius). */
        dir.copy(temp).sub(bestP.position);
        if (dir.lengthSq() < 1e-8) dir.set(0, 1, 0);
        const surf = getPlanetSurfaceWorld(bestP, dir.normalize());
        if (temp.distanceTo(bestP.position) < surf.distance + this.gridSize) {
          anchored = true;
          break;
        }
      }
    }
    if (!anchored) this.isStatic = false;
  }

  recalcSystem() {
    this.thrustCaps = { up: 0, down: 0, left: 0, right: 0, fwd: 0, back: 0 };
    this.totalCapacity = 0;

    this.blocks.forEach((m) => {
      const id = m.userData.id;
      if (id === 2) {
        const dir = new THREE.Vector3(0, 0, 1).applyQuaternion(m.userData.quat);
        dir.x = Math.round(dir.x);
        dir.y = Math.round(dir.y);
        dir.z = Math.round(dir.z);
        const p = CONFIG.thrustPower;
        if (dir.z === 1) this.thrustCaps.fwd += p;
        if (dir.z === -1) this.thrustCaps.back += p;
        if (dir.x === 1) this.thrustCaps.left += p;
        if (dir.x === -1) this.thrustCaps.right += p;
        if (dir.y === 1) this.thrustCaps.down += p;
        if (dir.y === -1) this.thrustCaps.up += p;
      }
      if (id === 3) this.totalCapacity += CONFIG.batteryCapacity;
    });

    if (this.currentPower > this.totalCapacity) this.currentPower = this.totalCapacity;
    if (this.currentPower <= 0 && this.totalCapacity > 0) this.currentPower = this.totalCapacity;

    this.updateGearVisuals();
  }

  updateGearVisuals() {
    this.blocks.forEach((m) => {
      if (m.userData.id === 10) {
        m.traverse((c) => {
          if (c.name === "statusLight" && c.material?.color) {
            c.material.color.setHex(this.isParked ? 0x00ff00 : 0xff0000);
          }
        });
      }
    });
  }
}
