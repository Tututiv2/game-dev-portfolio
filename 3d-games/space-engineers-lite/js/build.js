import * as THREE from "three";
import { CONFIG } from "./config.js";
import { G } from "./gameState.js";
import { createBlockGeometry } from "./blocks.js";
import { SEGrid } from "./grid.js";

export function setupBuildSystem() {
  G.raycaster = new THREE.Raycaster();
  G.Build.ghost = new THREE.Group();
  G.scene.add(G.Build.ghost);
  updateGhostGeometry(G.Build.selectedBlockId);
}

export function updateGhostGeometry(id) {
  const ghost = G.Build.ghost;
  while (ghost.children.length > 0) {
    const c = ghost.children[0];
    ghost.remove(c);
    /** Ghost meshes own their geometry + cloned material — dispose to avoid GPU leaks on every slot/grid-mode change. */
    if (c.geometry) c.geometry.dispose();
    if (c.material) c.material.dispose();
  }

  const size = G.Build.mode === "large" ? CONFIG.largeGridSize : CONFIG.smallGridSize;
  const geoStruct = createBlockGeometry(id, size);
  const ghostMat = new THREE.MeshBasicMaterial({
    color: 0x00ff00,
    wireframe: true,
    opacity: 0.45,
    transparent: true,
    depthTest: true,
  });

  if (geoStruct.isGroup) {
    geoStruct.children.forEach((c) => {
      const m = new THREE.Mesh(c.geometry, ghostMat.clone());
      m.position.copy(c.position);
      m.rotation.copy(c.rotation);
      m.scale.copy(c.scale);
      ghost.add(m);
    });
  } else {
    ghost.add(new THREE.Mesh(geoStruct, ghostMat));
  }
}

export function updateBuildTool() {
  const { Player, Build, camera, raycaster, interactables } = G;
  if (Player.inShip || !Build.isActive || G.isLibraryOpen) {
    Build.ghost.visible = false;
    return;
  }
  Build.ghost.visible = true;
  raycaster.setFromCamera(new THREE.Vector2(0, 0), camera);
  const hits = raycaster.intersectObjects(interactables, true);

  const fwd = new THREE.Vector3();
  camera.getWorldDirection(fwd);
  const floatPos = camera.position.clone().add(fwd.multiplyScalar(5));
  Build.ghost.position.copy(floatPos);
  Build.ghost.quaternion.copy(Player.wrapper.quaternion);
  Build.targetGrid = null;

  if (hits.length > 0 && hits[0].distance < 30) {
    const h = hits[0];
    let obj = h.object;
    while (obj.parent && !obj.userData.isBlock && !obj.userData.isPlanet) obj = obj.parent;

    if (obj.userData.isPlanet) {
      Build.targetGrid = null;
      Build.ghost.position.copy(h.point);
      const up = h.point.clone().sub(obj.position).normalize();
      Build.ghost.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), up);
    } else if (obj.userData.grid) {
      const g = obj.userData.grid;
      const currentGridSize = Build.mode === "large" ? CONFIG.largeGridSize : CONFIG.smallGridSize;
      if (g.gridSize !== currentGridSize) {
        Build.targetGrid = null;
        Build.ghost.position.copy(h.point);
        Build.ghost.quaternion.copy(g.quaternion);
      } else {
        Build.targetGrid = g;
        const inv = g.matrixWorld.clone().invert();
        const localHit = h.point.clone().applyMatrix4(inv);
        const blockCenter = obj.userData.localPos.clone();
        const diff = localHit.sub(blockCenter);
        const localNorm = new THREE.Vector3();
        if (Math.abs(diff.x) > Math.abs(diff.y) && Math.abs(diff.x) > Math.abs(diff.z)) {
          localNorm.x = Math.sign(diff.x);
        } else if (Math.abs(diff.y) > Math.abs(diff.z)) {
          localNorm.y = Math.sign(diff.y);
        } else {
          localNorm.z = Math.sign(diff.z);
        }
        const newL = obj.userData.localPos.clone().add(localNorm).round();
        Build.ghost.position.copy(newL.clone().multiplyScalar(g.gridSize).applyMatrix4(g.matrixWorld));
        Build.ghost.quaternion.copy(g.quaternion);
        Build.localPos.copy(newL);
      }
    }
  }
  Build.ghost.quaternion.multiply(Build.rotation);
}
