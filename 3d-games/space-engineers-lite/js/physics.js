import * as THREE from "three";
import { CONFIG } from "./config.js";
import { G } from "./gameState.js";
import { getPlanetSurfaceWorld, getPlanetSpinAxisWorld } from "./solar.js";

function atmosphereThicknessForPlanet(planet) {
  const r = planet.userData.radius;
  const cap = r > 2800 ? CONFIG.atmosphereThicknessM : Math.min(CONFIG.atmosphereThicknessM, Math.max(450, r * 0.38));
  return cap;
}

/**
 * Frame-rate-independent damping: `base` is the per-frame factor tuned at 60 fps.
 * Math.pow keeps the same feel at 60 fps while behaving identically at 30/120/144 fps.
 */
function dampFactor(base, dt) {
  return Math.pow(base, dt * 60);
}

/** Scratch objects to avoid per-frame allocations in hot loops. */
const _gDir = new THREE.Vector3();
const _gInv = new THREE.Matrix4();
const _gLocalPos = new THREE.Vector3();
const _gGenLocal = new THREE.Vector3();
const _gQuat = new THREE.Quaternion();
const _cInv = new THREE.Matrix4();
const _cLocalP = new THREE.Vector3();
const _cBPos = new THREE.Vector3();

export function getGravityInfo(pos) {
  const totalG = new THREE.Vector3(0, 0, 0);
  let strongestP = null;
  let maxGMag = 0;

  G.celestialBodies.forEach((p) => {
    const dist = pos.distanceTo(p.position);
    if (dist < p.userData.radius * 4) {
      const surfaceG = p.userData.surfaceGravity ?? CONFIG.gravityForce;
      /** Avoid inverse-square blow-up at/near the core (was causing 1e8+ m/s²). */
      const safeDist = Math.max(dist, p.userData.radius * 0.92);
      const ratio = p.userData.radius / safeDist;
      let gMag = surfaceG * (ratio * ratio);
      gMag = Math.min(gMag, surfaceG * 2.5);
      if (gMag > 0.05) {
        if (dist < 1e-4) return;
        _gDir.copy(p.position).sub(pos).normalize();
        totalG.addScaledVector(_gDir, gMag);
        if (gMag > maxGMag) {
          maxGMag = gMag;
          strongestP = p;
        }
      }
    }
  });

  G.grids.forEach((g) => {
    if (g.isOn && g.currentPower > 0 && g.gravityGenerators.length > 0) {
      /** Invert once per grid (was once per generator, with an extra unused getWorldPosition). */
      _gInv.copy(g.matrixWorld).invert();
      const localPos = _gLocalPos.copy(pos).applyMatrix4(_gInv);
      g.gravityGenerators.forEach((gen) => {
        const genLocal = _gGenLocal.copy(gen.userData.localPos).multiplyScalar(g.gridSize);
        const range = 50;
        if (
          Math.abs(localPos.x - genLocal.x) < range &&
          Math.abs(localPos.z - genLocal.z) < range &&
          Math.abs(localPos.y - genLocal.y) < range
        ) {
          const down = new THREE.Vector3(0, -1, 0).applyQuaternion(gen.getWorldQuaternion(_gQuat));
          totalG.add(down.multiplyScalar(9.8));
        }
      });
    }
  });

  return { vec: totalG, planet: strongestP };
}

/**
 * Grid AABB vs player point + radius. Uses smallest-penetration face so vertical walls
 * are not mistaken for floors (old heuristic: localY near top triggered on wall edges).
 */
export function checkCollisions(nextPos) {
  const pRad = 0.5;
  for (const grid of G.grids) {
    const distToGrid = nextPos.distanceTo(grid.position);
    if (distToGrid > 100) continue;
    const inv = _cInv.copy(grid.matrixWorld).invert();
    const localP = _cLocalP.copy(nextPos).applyMatrix4(inv);
    const bHalf = grid.gridSize / 2;

    for (const [, mesh] of grid.blocks) {
      const bPos = _cBPos.copy(mesh.userData.localPos).multiplyScalar(grid.gridSize);
      const h = bHalf + pRad;
      if (
        localP.x < bPos.x - h ||
        localP.x > bPos.x + h ||
        localP.y < bPos.y - h ||
        localP.y > bPos.y + h ||
        localP.z < bPos.z - h ||
        localP.z > bPos.z + h
      ) {
        continue;
      }

      const dx = localP.x - bPos.x;
      const dy = localP.y - bPos.y;
      const dz = localP.z - bPos.z;
      const penX = h - Math.abs(dx);
      const penY = h - Math.abs(dy);
      const penZ = h - Math.abs(dz);
      if (penX <= 0 || penY <= 0 || penZ <= 0) continue;

      let nx = 0;
      let ny = 0;
      let nz = 0;
      let sepAlongFace = penX;
      if (penX <= penY && penX <= penZ) {
        nx = Math.sign(dx);
        sepAlongFace = penX;
      } else if (penY <= penZ) {
        ny = Math.sign(dy);
        sepAlongFace = penY;
      } else {
        nz = Math.sign(dz);
        sepAlongFace = penZ;
      }

      const localNormal = new THREE.Vector3(nx, ny, nz);
      const worldNormal = localNormal.clone().transformDirection(grid.matrixWorld).normalize();
      const gridWorldUp = new THREE.Vector3(0, 1, 0).applyQuaternion(grid.quaternion).normalize();
      /** Walkable top: separation normal matches grid “up” (not a vertical wall). */
      const walkableTop = worldNormal.dot(gridWorldUp) > 0.62;

      if (walkableTop) {
        /** Use penetration on the resolved face only — min(X,Y,Z) was too small at wall+floor corners. */
        return {
          type: "floor",
          grid,
          worldUp: gridWorldUp,
          contactNormal: worldNormal.clone(),
          separation: sepAlongFace + 0.05,
        };
      }

      const push = worldNormal.clone().multiplyScalar(sepAlongFace + 0.06);
      return { type: "push", vec: push, grid };
    }
  }
  return null;
}

export function updatePhysics(dt) {
  const steps = 1;
  const subDt = dt / steps;
  const { Player, camera, Input } = G;

  for (let s = 0; s < steps; s++) {
    if (!Player.inShip) {
      const gInfo = getGravityInfo(Player.wrapper.position);
      G.activePlanet = gInfo.planet;

      if (G.activePlanet) {
        const distToPlanet = Player.wrapper.position.distanceTo(G.activePlanet.position);
        if (distToPlanet < G.activePlanet.userData.radius * 2.0) {
          const planetDelta = G.activePlanet.position.clone().sub(G.activePlanet.userData.previousPosition);
          if (planetDelta.length() > 0) Player.wrapper.position.add(planetDelta);

          const toP = Player.wrapper.position.clone().sub(G.activePlanet.position);
          const dtp = toP.length();
          const dirP = dtp > 1e-6 ? toP.multiplyScalar(1 / dtp) : new THREE.Vector3(0, 1, 0);
          const surfHere = getPlanetSurfaceWorld(G.activePlanet, dirP);
          const altAboveTerrain = dtp - surfHere.distance;
          const atmH = atmosphereThicknessForPlanet(G.activePlanet);
          /**
           * Corotate in atmosphere: default ON. Only skip if jetpack ON **and** dampeners OFF
           * (free inertial flight). Jetpack + dampeners = SE-style damped coupling to rotating air.
           */
          const skipCoupling = Player.jetpack && !Player.suitDampeners;
          const atmosphereCoupled =
            !skipCoupling && altAboveTerrain > -150 && altAboveTerrain < atmH;

          if (atmosphereCoupled) {
            const rot = G.activePlanet.userData.frameRot;
            const spinAxis = getPlanetSpinAxisWorld(G.activePlanet);
            const relPos = Player.wrapper.position.clone().sub(G.activePlanet.position);
            relPos.applyAxisAngle(spinAxis, rot);
            Player.wrapper.position.copy(G.activePlanet.position).add(relPos);
            Player.wrapper.rotateOnWorldAxis(spinAxis, rot);
          }
        }
      }

      /** Feet-down smoothing: on foot, or jetpack + dampeners near a planet (damped EVA). */
      const alignFeetDown =
        gInfo.vec.length() > 0.1 &&
        (!Player.jetpack || (Player.jetpack && Player.suitDampeners && G.activePlanet));
      if (alignFeetDown) {
        const targetUp = gInfo.vec.clone().negate().normalize();
        const currentUp = new THREE.Vector3(0, 1, 0).applyQuaternion(Player.wrapper.quaternion);
        const q = new THREE.Quaternion().setFromUnitVectors(currentUp, targetUp);
        const nearPlanet =
          G.activePlanet &&
          Player.wrapper.position.distanceTo(G.activePlanet.position) <
            G.activePlanet.userData.radius * 2.05;
        /** Jetpack + dampeners: track rotating planet frame tightly (SE-style inertial dampers). */
        const dampEva = Player.jetpack && Player.suitDampeners && nearPlanet;
        const blend = dampEva ? 0.88 : Player.jetpack && Player.suitDampeners ? 0.2 : 0.1;
        const smoothQ = new THREE.Quaternion()
          .copy(Player.wrapper.quaternion)
          .slerp(Player.wrapper.quaternion.clone().premultiply(q), blend);
        Player.wrapper.quaternion.copy(smoothQ);
      }

      const speed = Player.jetpack ? CONFIG.jetpackSpeed : CONFIG.playerSpeed;
      const cFwd = new THREE.Vector3();
      camera.getWorldDirection(cFwd);
      const wUp = new THREE.Vector3(0, 1, 0).applyQuaternion(Player.wrapper.quaternion);
      const cRight = new THREE.Vector3().crossVectors(cFwd, wUp).normalize();
      if (!Player.jetpack) {
        cFwd.projectOnPlane(wUp).normalize();
        cRight.projectOnPlane(wUp).normalize();
      }

      const inputDir = new THREE.Vector3();
      if (Input.w) inputDir.add(cFwd);
      if (Input.s) inputDir.sub(cFwd);
      if (Input.d) inputDir.add(cRight);
      if (Input.a) inputDir.sub(cRight);

      if (inputDir.length() > 0) {
        inputDir.normalize().multiplyScalar(speed * subDt * 5);
        Player.velocity.add(inputDir);
      } else if (Player.onGround) {
        Player.velocity.multiplyScalar(dampFactor(0.9, subDt));
      }

      if (Player.jetpack) {
        if (Input.sp) Player.velocity.add(wUp.clone().multiplyScalar(speed * subDt * 2));
        if (Input.c) Player.velocity.add(wUp.clone().multiplyScalar(-speed * subDt * 2));
        if (Player.suitDampeners && inputDir.length() === 0 && !Input.sp && !Input.c) {
          Player.velocity.multiplyScalar(dampFactor(0.9, subDt));
        }
        if (Input.q) Player.wrapper.rotateZ(2 * subDt);
        if (Input.e) Player.wrapper.rotateZ(-2 * subDt);
      } else if (Input.sp && Player.onGround) {
        Player.velocity.add(wUp.clone().multiplyScalar(8));
        Player.onGround = false;
      }

      if (!Player.jetpack) Player.velocity.add(gInfo.vec.clone().multiplyScalar(subDt));

      let nextPos = Player.wrapper.position.clone().add(Player.velocity.clone().multiplyScalar(subDt));

      if (G.activePlanet) {
        const radial = nextPos.clone().sub(G.activePlanet.position);
        let distToCenter = radial.length();
        if (distToCenter < 1e-6) {
          radial.set(0, 1, 0);
          distToCenter = 1e-6;
        }
        const dirUnit = radial.multiplyScalar(1 / distToCenter);
        const surf = getPlanetSurfaceWorld(G.activePlanet, dirUnit);
        const floorDist = surf.distance + Player.height;
        const clearance = distToCenter - surf.distance;
        const outwardSpeed = Player.velocity.dot(surf.outwardWorld);

        /** Snap only when penetrating rock; allow easy jump-out (reduces “planet rams you”). */
        const penetrating = clearance < Player.height + 0.06;
        const jumpingAway = outwardSpeed > 0.8 && clearance > Player.height * 0.72;

        if (penetrating && !jumpingAway) {
          nextPos.copy(G.activePlanet.position).add(surf.outwardWorld.clone().multiplyScalar(floorDist));
          const normal = surf.outwardWorld.clone();
          const velIntoPlane = Player.velocity.dot(normal);
          if (velIntoPlane < 0) Player.velocity.sub(normal.multiplyScalar(velIntoPlane));
          Player.onGround = true;
        } else {
          Player.onGround = clearance < Player.height + 0.35;
        }
      }

      const col = checkCollisions(nextPos);
      if (col) {
        if (col.type === "floor") {
          const up = col.worldUp
            ? col.worldUp.clone().normalize()
            : Player.wrapper.position
                .clone()
                .sub(G.activePlanet ? G.activePlanet.position : new THREE.Vector3())
                .normalize();
          if (Player.velocity.dot(up) < 0) Player.velocity.projectOnPlane(up);
          Player.onGround = true;
          if (col.contactNormal && col.separation != null) {
            nextPos.add(col.contactNormal.clone().multiplyScalar(col.separation));
          } else {
            nextPos.add(up.multiplyScalar(0.1));
          }
          if (!col.grid.isStatic) {
            Player.velocity.add(col.grid.velocity.clone().multiplyScalar(0.1));
          }
        } else if (col.type === "push") {
          nextPos.add(col.vec);
          const n = col.vec.clone().normalize();
          const into = Player.velocity.dot(n);
          if (into < 0) Player.velocity.addScaledVector(n, -into);
          if (col.vec.lengthSq() < 0.15) {
            Player.velocity.multiplyScalar(0.35);
          }
        }
      }

      Player.wrapper.position.copy(nextPos);
    }

    G.grids.forEach((g) => {
      const gInfo = getGravityInfo(g.position);

      if (gInfo.planet) {
        const dist = g.position.distanceTo(gInfo.planet.position);
        if (dist < gInfo.planet.userData.radius * 2.0) {
          const pDelta = gInfo.planet.position.clone().sub(gInfo.planet.userData.previousPosition);
          if (pDelta.length() > 0) g.position.add(pDelta);
        }
      }

      if (g.isStatic && gInfo.planet) {
        const rotAmt = gInfo.planet.userData.frameRot;
        if (rotAmt) {
          const ax = getPlanetSpinAxisWorld(gInfo.planet);
          const rel = g.position.clone().sub(gInfo.planet.position);
          rel.applyAxisAngle(ax, rotAmt);
          g.position.copy(gInfo.planet.position).add(rel);
          g.rotateOnWorldAxis(ax, rotAmt);
          g.updateMatrixWorld();
          return;
        }
      }

      if (g.isStatic) return;

      g.powerDraw = 0;
      let inputActive = false;
      if (Player.inShip && Player.controlledGrid === g) {
        if (Input.w || Input.s || Input.a || Input.d || Input.sp || Input.c || Input.q || Input.e) {
          inputActive = true;
        }
      }

      if (g.isOn && g.currentPower > 0 && !g.isParked) {
        if (Player.inShip && Player.controlledGrid === g) {
          const rotS = CONFIG.shipRotSpeed * subDt;
          if (Input.q) g.angularVelocity.z += rotS;
          if (Input.e) g.angularVelocity.z -= rotS;
        }

        g.angularVelocity.multiplyScalar(dampFactor(0.9, subDt));

        const fwd = new THREE.Vector3(0, 0, -1).applyQuaternion(g.quaternion);
        const right = new THREE.Vector3(1, 0, 0).applyQuaternion(g.quaternion);
        const up = new THREE.Vector3(0, 1, 0).applyQuaternion(g.quaternion);

        const apply = (active, cap, vec) => {
          if (active && cap > 0) {
            g.velocity.add(vec.clone().multiplyScalar(cap * subDt * 0.005));
            g.powerDraw += CONFIG.thrusterDrain;
          }
        };

        if (Player.inShip && Player.controlledGrid === g) {
          apply(Input.w, g.thrustCaps.fwd, fwd);
          apply(Input.s, g.thrustCaps.back, fwd.clone().negate());
          apply(Input.a, g.thrustCaps.left, right.clone().negate());
          apply(Input.d, g.thrustCaps.right, right);
          apply(Input.sp, g.thrustCaps.up, up);
          apply(Input.c, g.thrustCaps.down, up.clone().negate());
        }

        if (g.dampeners && !g.isParked) {
          if (!inputActive) g.velocity.multiplyScalar(dampFactor(0.99, subDt));
          const descendCmd = Player.inShip && Player.controlledGrid === g && Input.c;
          if (!descendCmd) {
            const localG = gInfo.vec.clone().applyQuaternion(g.quaternion.clone().invert());
            if (localG.y < 0 && g.thrustCaps.up > 0) {
              const antiG = gInfo.vec.clone().negate().multiplyScalar(subDt);
              g.velocity.add(antiG);
              g.powerDraw += CONFIG.thrusterDrain;
            }
          }
        }
        g.currentPower -= g.powerDraw * subDt;
        if (g.currentPower < 0) g.currentPower = 0;
      } else {
        if (g.isParked) {
          g.velocity.set(0, 0, 0);
          g.angularVelocity.set(0, 0, 0);
          if (gInfo.planet) {
            const rotAmt = gInfo.planet.userData.frameRot;
            if (rotAmt) {
              const ax = getPlanetSpinAxisWorld(gInfo.planet);
              const rel = g.position.clone().sub(gInfo.planet.position);
              rel.applyAxisAngle(ax, rotAmt);
              g.position.copy(gInfo.planet.position).add(rel);
              g.rotateOnWorldAxis(ax, rotAmt);
            }
          }
        }
        if (!g.isOn) {
          g.currentPower += CONFIG.rechargeRate * subDt;
          if (g.currentPower > g.totalCapacity) g.currentPower = g.totalCapacity;
        }
      }

      if (!g.isParked) g.velocity.add(gInfo.vec.clone().multiplyScalar(subDt));
      if (!g.isParked) g.position.add(g.velocity.clone().multiplyScalar(subDt));

      if (gInfo.planet) {
        const dir = g.position.clone().sub(gInfo.planet.position);
        /** Guard zero-length normalize (grid exactly at planet center → NaN cascade). */
        if (dir.lengthSq() < 1e-8) dir.set(0, 1, 0);
        else dir.normalize();
        const surf = getPlanetSurfaceWorld(gInfo.planet, dir);
        if (g.position.distanceTo(gInfo.planet.position) < surf.distance + g.gridSize) {
          g.position
            .copy(gInfo.planet.position)
            .add(surf.outwardWorld.clone().multiplyScalar(surf.distance + g.gridSize));
          g.velocity.multiplyScalar(0.5);
        }
      }

      const q = new THREE.Quaternion().setFromEuler(
        new THREE.Euler(
          g.angularVelocity.x * subDt,
          g.angularVelocity.y * subDt,
          g.angularVelocity.z * subDt
        )
      );
      g.quaternion.multiply(q);

      if (Player.inShip && Player.controlledGrid === g) {
        Player.wrapper.position.copy(g.position);
        Player.wrapper.quaternion.copy(g.quaternion);
      }
    });
  }
}
