import * as THREE from "three";
import { G } from "./gameState.js";

export function updateCamera() {
  const { Player, camera, CamState } = G;

  if (Player.inShip && Player.controlledGrid && Player.activeCockpit) {
    const ship = Player.controlledGrid;
    const cockpitPos = new THREE.Vector3();
    Player.activeCockpit.getWorldPosition(cockpitPos);

    const camRot = ship.quaternion.clone();
    const orbitQ = new THREE.Quaternion().setFromEuler(
      new THREE.Euler(CamState.orbitY, CamState.orbitX, 0, "YXZ")
    );
    camRot.multiply(orbitQ);

    const offset = new THREE.Vector3(0, 0, CamState.distance + 0.1);
    offset.applyQuaternion(camRot);

    camera.position.copy(cockpitPos).add(offset);
    camera.quaternion.copy(camRot);
  } else {
    const offset = new THREE.Vector3(0, Player.height, 0);
    offset.applyQuaternion(Player.wrapper.quaternion);
    const pos = Player.wrapper.position.clone().add(offset);
    camera.position.copy(pos);
    const lookQ = new THREE.Quaternion().setFromEuler(
      new THREE.Euler(Player.rotationX, 0, 0, "YXZ")
    );
    const finalQ = Player.wrapper.quaternion.clone().multiply(lookQ);
    camera.quaternion.copy(finalQ);
  }
}
