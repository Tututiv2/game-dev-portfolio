/**
 * Space Engineers Lite — built on the Space Game / cracker.html foundation (modularized + cleaned).
 */
import * as THREE from "three";
import { PointerLockControls } from "three/addons/controls/PointerLockControls.js";
import { CONFIG } from "./config.js";
import { G } from "./gameState.js";
import { setupSolarSystem, getPlanetSurfaceWorld, updateOrbits } from "./solar.js";
import { defaultUniverse } from "./universeSchema.js";
import { loadUniverseFromSession } from "./universeIO.js";
import { getGravityInfo, updatePhysics } from "./physics.js";
import { setupBuildSystem, updateBuildTool } from "./build.js";
import { updateCamera } from "./camera.js";
import { SEGrid } from "./grid.js";
import {
  setupUI,
  renderHotbar,
  toggleLibrary,
  selectSlot,
  toggleGridMode,
  updateHUD,
  showToast,
} from "./ui.js";

/**
 * Helmet spotlight — lives on the **scene** (not camera child) so WebGL actually
 * accumulates it with MeshStandardMaterials. Position is synced each frame after the camera moves.
 */
function setupHeadGearLamp() {
  const spot = new THREE.SpotLight(0xf8fcff, 0, 0, 0.62, 0.5, 1.05);
  spot.intensity = 0;
  spot.visible = false;
  spot.castShadow = false;
  spot.name = "HeadGearSpot";
  const tgt = new THREE.Object3D();
  tgt.name = "HeadGearSpotTarget";
  G.scene.add(spot);
  G.scene.add(tgt);
  spot.target = tgt;
  G.headLamp = spot;
  G.headLampOn = false;
}

const _lampPos = new THREE.Vector3();
const _lampDir = new THREE.Vector3();

function updateHeadGearLampFromCamera() {
  const spot = G.headLamp;
  const cam = G.camera;
  if (!spot || !cam) return;
  const wpos = _lampPos;
  const dir = _lampDir;
  cam.getWorldPosition(wpos);
  cam.getWorldDirection(dir);
  spot.position.copy(wpos).addScaledVector(dir, 0.45);
  /** Aim far enough that the cone covers ground on a large planet (log-depth safe). */
  spot.target.position.copy(wpos).addScaledVector(dir, 3200);
  spot.target.updateMatrixWorld(true);
}

function init() {
  G.scene = new THREE.Scene();
  G.scene.background = new THREE.Color(0x020308);
  G.scene.fog = new THREE.FogExp2(0x03050a, 0.00000085);

  G.camera = new THREE.PerspectiveCamera(
    60,
    window.innerWidth / window.innerHeight,
    0.1,
    CONFIG.viewDistance
  );
  G.scene.add(G.camera);

  G.scene.add(G.Player.wrapper);

  G.renderer = new THREE.WebGLRenderer({ antialias: true, logarithmicDepthBuffer: true });
  G.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
  G.renderer.setSize(window.innerWidth, window.innerHeight);
  G.renderer.shadowMap.enabled = true;
  G.renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  G.renderer.toneMapping = THREE.ACESFilmicToneMapping;
  G.renderer.toneMappingExposure = 1.05;
  document.body.appendChild(G.renderer.domElement);

  const selectedUniverse = loadUniverseFromSession() || defaultUniverse();
  setupSolarSystem(selectedUniverse);

  const home =
    G.celestialBodies.find((b) => b.userData.name === "Terra") ||
    G.celestialBodies.find((b) => b.userData.type !== "sun");
  if (home) {
    const spawnDir = new THREE.Vector3(0, 0, 1);
    const surf = getPlanetSurfaceWorld(home, spawnDir);
    G.Player.wrapper.position
      .copy(home.position)
      .add(surf.outwardWorld.clone().multiplyScalar(surf.distance + 2.5));
  } else {
    G.Player.wrapper.position.set(0, CONFIG.planetRadius + 50, 0);
  }
  G.Player.velocity.set(0, 0, 0);

  setupBuildSystem();
  setupUI();

  G.controls = new PointerLockControls(G.camera, document.body);

  setupHeadGearLamp();

  document.addEventListener("keydown", onKeyDown);
  document.addEventListener("keyup", onKeyUp);
  document.addEventListener("mousedown", onMouseDown);
  document.addEventListener("mousemove", onMouseMove);
  document.addEventListener("wheel", onWheel, { passive: false });
  window.addEventListener("resize", onResize);

  document.addEventListener("click", (e) => {
    if (G.isLibraryOpen) return;
    if (!G.controls.isLocked) {
      const t = e.target;
      if (t.closest("#dev-menu")) return;
      if (t.closest("#library-modal")) return;
      if (t.closest("#hotbar-container")) return;
      G.controls.lock();
    }
  });

  G.lastTime = performance.now();
  updateHUD();
  requestAnimationFrame(animate);
}

function onKeyDown(e) {
  if (G.isLibraryOpen) {
    if (e.code === "KeyG" || e.code === "Escape") toggleLibrary();
    return;
  }
  const I = G.Input;
  if (e.code === "KeyW") I.w = 1;
  if (e.code === "KeyS") I.s = 1;
  if (e.code === "KeyA") I.a = 1;
  if (e.code === "KeyD") I.d = 1;
  if (e.code === "Space") I.sp = 1;
  if (e.code === "KeyC") I.c = 1;
  if (e.code === "KeyQ") I.q = 1;
  if (e.code === "KeyE") I.e = 1;
  if (e.code === "AltLeft") I.alt = true;

    if (e.code === "KeyL" && !e.repeat) {
      G.headLampOn = !G.headLampOn;
      if (G.headLamp) {
        G.headLamp.visible = G.headLampOn;
        /** ACES tone mapping needs much higher intensity than “3” to read on terrain. */
        G.headLamp.intensity = G.headLampOn ? 1400 : 0;
      }
      updateHUD();
    }
    if (e.code === "KeyM") {
    const m = document.getElementById("dev-menu");
    if (m) {
      m.style.display = m.style.display === "none" ? "block" : "none";
      if (m.style.display === "block") G.controls.unlock();
    }
  }

  if (e.key >= "1" && e.key <= "9") selectSlot(parseInt(e.key, 10) - 1);
  if (e.key === "0") selectSlot(9);

  if (!e.repeat) {
    if (e.code === "KeyX") {
      G.Player.jetpack = !G.Player.jetpack;
      updateHUD();
    }
    if (e.code === "KeyZ") {
      if (G.Player.inShip) G.Player.controlledGrid.dampeners = !G.Player.controlledGrid.dampeners;
      else G.Player.suitDampeners = !G.Player.suitDampeners;
      updateHUD();
    }
    if (e.code === "KeyG") toggleLibrary();
    if (e.code === "KeyF") handleInteract();
    if (e.code === "KeyB") toggleGridMode();

    if (e.code === "KeyR") {
      G.Build.rotation.multiply(new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0, 1, 0), Math.PI / 2));
    }
    if (e.code === "KeyT") {
      G.Build.rotation.multiply(new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(1, 0, 0), Math.PI / 2));
    }
    if (e.code === "KeyY" && !G.Player.inShip) {
      G.Build.rotation.multiply(new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0, 0, 1), Math.PI / 2));
    }

    if (G.Player.inShip && G.Player.controlledGrid) {
      const g = G.Player.controlledGrid;
      if (e.code === "KeyP") {
        g.isParked = !g.isParked;
        g.updateGearVisuals();
      }
      if (e.code === "KeyY") g.isOn = !g.isOn;
    }
  }
}

function onKeyUp(e) {
  const I = G.Input;
  if (e.code === "KeyW") I.w = 0;
  if (e.code === "KeyS") I.s = 0;
  if (e.code === "KeyA") I.a = 0;
  if (e.code === "KeyD") I.d = 0;
  if (e.code === "Space") I.sp = 0;
  if (e.code === "KeyC") I.c = 0;
  if (e.code === "KeyQ") I.q = 0;
  if (e.code === "KeyE") I.e = 0;
  if (e.code === "AltLeft") I.alt = false;
}

function onMouseDown(e) {
  if (G.isLibraryOpen || !G.controls.isLocked) return;
  const { Build, camera, raycaster, interactables, Player, scene, activePlanet } = G;

  if (e.button === 0 && Build.isActive && Build.ghost.visible) {
    if (Build.targetGrid) {
      Build.targetGrid.addBlock(Build.localPos, Build.selectedBlockId, Build.rotation);
    } else {
      let isStatic = false;
      if (activePlanet) {
        /** Compare against actual terrain height, not base radius — mountains can rise thousands of meters above it. */
        const toCenter = Build.ghost.position.clone().sub(activePlanet.position);
        if (toCenter.lengthSq() < 1e-8) toCenter.set(0, 1, 0);
        const surf = getPlanetSurfaceWorld(activePlanet, toCenter.normalize());
        if (Build.ghost.position.distanceTo(activePlanet.position) < surf.distance + 20) {
          isStatic = true;
        }
      }
      const size = Build.mode === "large" ? CONFIG.largeGridSize : CONFIG.smallGridSize;
      const g = new SEGrid(isStatic, size);
      if (isStatic && activePlanet) {
        const up = Build.ghost.position.clone().sub(activePlanet.position).normalize();
        g.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), up);
      } else {
        g.quaternion.copy(Player.wrapper.quaternion);
      }
      g.position.copy(Build.ghost.position);
      g.addBlock(new THREE.Vector3(0, 0, 0), Build.selectedBlockId, Build.rotation);
      scene.add(g);
      G.grids.push(g);
    }
  }
  if (e.button === 2) {
    raycaster.setFromCamera(new THREE.Vector2(0, 0), camera);
    const hits = raycaster.intersectObjects(interactables, true);
    if (hits.length > 0 && hits[0].distance < 30) {
      let obj = hits[0].object;
      while (obj.parent && !obj.userData.isBlock) obj = obj.parent;
      if (obj.userData.grid) obj.userData.grid.removeBlock(obj);
    }
  }
}

function onMouseMove(e) {
  if (!G.controls.isLocked) return;
  const sens = 0.002;
  const { Player, Input, CamState } = G;
  if (Player.inShip) {
    if (Input.alt) {
      CamState.orbitX -= e.movementX * sens;
      CamState.orbitY -= e.movementY * sens;
      CamState.orbitY = Math.max(-1.5, Math.min(1.5, CamState.orbitY));
    } else {
      const g = Player.controlledGrid;
      if (g) {
        g.angularVelocity.y -= e.movementX * sens * 2;
        g.angularVelocity.x -= e.movementY * sens * 2;
      }
    }
  } else {
    Player.wrapper.rotateY(-e.movementX * sens);
    Player.rotationX -= e.movementY * sens;
    Player.rotationX = Math.max(-1.5, Math.min(1.5, Player.rotationX));
  }
}

function onWheel(e) {
  if (!G.Player.inShip) return;
  G.CamState.distance += e.deltaY * 0.01;
  G.CamState.distance = Math.max(0, Math.min(50, G.CamState.distance));
}

function onResize() {
  G.camera.aspect = window.innerWidth / window.innerHeight;
  G.camera.updateProjectionMatrix();
  G.renderer.setSize(window.innerWidth, window.innerHeight);
}

function handleInteract() {
  const { Player, camera, raycaster, interactables } = G;
  if (Player.inShip) {
    Player.inShip = false;
    Player.controlledGrid = null;
    Player.activeCockpit = null;
    G.CamState.distance = 0;
    Player.wrapper.position.add(new THREE.Vector3(0, 3, 0).applyQuaternion(Player.wrapper.quaternion));
    const shipHud = document.getElementById("hud-ship");
    if (shipHud) shipHud.style.display = "none";
  } else {
    raycaster.setFromCamera(new THREE.Vector2(0, 0), camera);
    const hits = raycaster.intersectObjects(interactables, true);
    if (hits.length > 0 && hits[0].distance < 8) {
      let obj = hits[0].object;
      while (obj.parent && !obj.userData.isBlock) obj = obj.parent;
      if (obj.userData.id === 4) {
        const g = obj.userData.grid;
        if (g.isStatic) {
          g.isStatic = false;
          showToast("Station converted to ship — grid is now dynamic.");
        }
        Player.inShip = true;
        Player.controlledGrid = g;
        Player.activeCockpit = obj;
        const shipHud = document.getElementById("hud-ship");
        if (shipHud) shipHud.style.display = "block";
      }
    }
  }
  updateHUD();
}

/** Cached HUD elements + last written strings (skip DOM writes when unchanged). */
const _hud = { spd: null, alt: null, g: null, lastSpd: "", lastAlt: "", lastG: "" };

function animate() {
  requestAnimationFrame(animate);
  const time = performance.now();
  const dt = Math.min((time - G.lastTime) / 1000, 0.1);
  G.lastTime = time;

  if (G.controls.isLocked) {
    updateOrbits(dt);
    updatePhysics(dt);
    updateBuildTool();
    const speed =
      G.Player.inShip && G.Player.controlledGrid
        ? G.Player.controlledGrid.velocity.length()
        : G.Player.velocity.length();
    if (!_hud.spd) {
      _hud.spd = document.getElementById("hud-speed");
      _hud.alt = document.getElementById("hud-alt");
      _hud.g = document.getElementById("hud-g");
    }
    const spdTxt = `${speed.toFixed(1)} m/s`;
    if (_hud.spd && spdTxt !== _hud.lastSpd) {
      _hud.spd.textContent = spdTxt;
      _hud.lastSpd = spdTxt;
    }
    let altTxt = "—";
    if (G.activePlanet) {
      const alt =
        G.Player.wrapper.position.distanceTo(G.activePlanet.position) - G.activePlanet.userData.radius;
      altTxt = `${alt.toFixed(0)} m`;
    }
    if (_hud.alt && altTxt !== _hud.lastAlt) {
      _hud.alt.textContent = altTxt;
      _hud.lastAlt = altTxt;
    }
    let gTxt = "0.00 G";
    if (G.activePlanet) {
      const gi = getGravityInfo(G.Player.wrapper.position);
      gTxt = `${(gi.vec.length() / 9.81).toFixed(2)} G`;
    }
    if (_hud.g && gTxt !== _hud.lastG) {
      _hud.g.textContent = gTxt;
      _hud.lastG = gTxt;
    }
    updateHUD();
  }

  updateCamera();
  updateHeadGearLampFromCamera();
  G.renderer.render(G.scene, G.camera);
}

init();