// The mobile core — the thing the whole game is about.
//
// Movement on a sphere is done with two unit vectors and no angles: `pos` is
// where you are, `fwd` is a tangent pointing where you face. Driving forward is
// a rotation of BOTH of them around pos x fwd. There is no pole, no gimbal, no
// wrap-around seam — you can drive over the north pole and nothing special
// happens, because nothing about the maths knows there is one.

import * as THREE from 'three';
import { WORLD } from './terrain.js';

const MAX_SPEED = 46;      // m/s
const BOOST_SPEED = 76;
const ACCEL = 26;
const BRAKE = 42;
const DRAG = 0.5;          // fraction of speed kept per second when coasting
const TURN_RATE = 1.25;    // rad/s at a standstill
const RIDE_HEIGHT = 2.15;

const _axis = new THREE.Vector3();
const _right = new THREE.Vector3();
const _up = new THREE.Vector3();
const _back = new THREE.Vector3();
const _m = new THREE.Matrix4();
const _q = new THREE.Quaternion();

// Metalness is deliberately low. MeshStandardMaterial with high metalness and
// NO environment map has almost no diffuse response — it reflects a scene that
// is not there and comes out near black. That is why the hull was reading as a
// silhouette in daylight; it was not a lighting or colour problem.
function metalMat(color, rough = 0.55, metal = 0.32) {
  return new THREE.MeshStandardMaterial({ color, roughness: rough, metalness: metal, flatShading: true });
}
function glowMat(color, intensity = 2.2) {
  return new THREE.MeshStandardMaterial({
    color: 0x000000,
    emissive: new THREE.Color(color),
    emissiveIntensity: intensity,
    roughness: 0.4,
    metalness: 0.0,
  });
}

export class Crawler {
  constructor(planet) {
    this.planet = planet;
    this.pos = new THREE.Vector3(0, 0, 1).normalize();   // unit direction
    this.fwd = new THREE.Vector3(0, 1, 0);               // unit tangent
    this.orthonormalise();

    this.speed = 0;
    this.deployed = false;
    this.deployT = 0;          // 0 mobile, 1 fully anchored
    this.worldPos = new THREE.Vector3();
    this.surfaceNormal = new THREE.Vector3(0, 0, 1);
    this.smoothNormal = new THREE.Vector3(0, 0, 1);
    this.bob = 0;

    this.group = new THREE.Group();
    this.build();
    this.snapToGround();
  }

  build() {
    const g = this.group;

    const dark = metalMat(0x424b66, 0.52, 0.34);
    const darker = metalMat(0x272e42, 0.66, 0.30);
    const trim = metalMat(0x6c7b99, 0.40, 0.46);

    // Chassis
    const chassis = new THREE.Mesh(new THREE.BoxGeometry(5.4, 1.4, 8.6), darker);
    chassis.position.y = 1.5;
    g.add(chassis);

    // Hexagonal upper hull — the silhouette from the 2D game's core tile.
    const hull = new THREE.Mesh(new THREE.CylinderGeometry(3.05, 3.75, 2.4, 6), dark);
    hull.position.y = 3.2;
    hull.rotation.y = Math.PI / 6;
    g.add(hull);

    // Armoured prow
    const prow = new THREE.Mesh(new THREE.CylinderGeometry(0.05, 2.2, 3.4, 3), trim);
    prow.rotation.x = Math.PI / 2;
    prow.rotation.y = Math.PI / 2;
    prow.position.set(0, 2.1, -5.0);
    g.add(prow);

    // Neon ring around the hull — the single strongest read at any distance.
    const ring = new THREE.Mesh(new THREE.TorusGeometry(3.45, 0.10, 8, 6), glowMat(0x00ccff, 1.9));
    ring.rotation.x = Math.PI / 2;
    ring.rotation.z = Math.PI / 6;
    ring.position.y = 3.9;
    g.add(ring);
    this.ring = ring;

    // The core itself. White while mobile, violet when anchored — same colour
    // language as the 2D game, where the core tile changed colour on deploy.
    const coreGlow = glowMat(0xffffff, 2.1);
    const core = new THREE.Mesh(new THREE.OctahedronGeometry(1.25, 0), coreGlow);
    core.position.y = 6.2;
    g.add(core);
    this.core = core;
    this.coreMat = coreGlow;

    const spine = new THREE.Mesh(new THREE.CylinderGeometry(0.42, 0.62, 2.4, 6), trim);
    spine.position.y = 4.9;
    g.add(spine);

    // Wheels: three a side, so it reads as a crawler rather than a car.
    this.wheels = [];
    const wheelGeo = new THREE.CylinderGeometry(1.35, 1.35, 0.85, 14);
    const hubGeo = new THREE.CylinderGeometry(0.45, 0.45, 0.95, 8);
    const rubber = metalMat(0x161a24, 0.95, 0.06);
    const hubMat = glowMat(0x00ccff, 1.4);
    for (let side = -1; side <= 1; side += 2) {
      for (let i = -1; i <= 1; i++) {
        const w = new THREE.Group();
        const tyre = new THREE.Mesh(wheelGeo, rubber);
        tyre.rotation.z = Math.PI / 2;
        w.add(tyre);
        const hub = new THREE.Mesh(hubGeo, hubMat);
        hub.rotation.z = Math.PI / 2;
        w.add(hub);
        w.position.set(side * 3.05, 1.35, i * 3.1);
        g.add(w);
        this.wheels.push(w);
      }
    }

    // Anchor legs — folded flat while mobile, driven into the ground on deploy.
    this.legs = [];
    const legGeo = new THREE.BoxGeometry(0.55, 3.2, 0.55);
    const footGeo = new THREE.CylinderGeometry(0.95, 1.25, 0.5, 6);
    for (let i = 0; i < 4; i++) {
      const a = (i / 4) * Math.PI * 2 + Math.PI / 4;
      const pivot = new THREE.Group();
      pivot.position.set(Math.cos(a) * 2.9, 2.2, Math.sin(a) * 4.0);
      const leg = new THREE.Mesh(legGeo, trim);
      leg.position.y = -1.6;
      pivot.add(leg);
      const foot = new THREE.Mesh(footGeo, dark);
      foot.position.y = -3.2;
      pivot.add(foot);
      pivot.rotation.z = Math.cos(a) * 1.15;
      pivot.rotation.x = -Math.sin(a) * 1.15;
      pivot.userData.rest = { x: pivot.rotation.x, z: pivot.rotation.z };
      pivot.scale.y = 0.25;
      g.add(pivot);
      this.legs.push(pivot);
    }

    // Headlamps
    const lampMat = glowMat(0xbfe9ff, 2.6);
    for (const sx of [-1.5, 1.5]) {
      const lamp = new THREE.Mesh(new THREE.BoxGeometry(0.9, 0.35, 0.2), lampMat);
      lamp.position.set(sx, 2.6, -4.3);
      g.add(lamp);
    }
  }

  orthonormalise() {
    this.pos.normalize();
    this.fwd.addScaledVector(this.pos, -this.fwd.dot(this.pos)).normalize();
  }

  // Arc-length movement: rotate position and facing together about the axis
  // perpendicular to both.
  advance(metres) {
    if (Math.abs(metres) < 1e-6) return;
    _axis.crossVectors(this.pos, this.fwd).normalize();
    const angle = metres / WORLD.radius;
    _q.setFromAxisAngle(_axis, angle);
    this.pos.applyQuaternion(_q);
    this.fwd.applyQuaternion(_q);
    this.orthonormalise();
  }

  turn(radians) {
    _q.setFromAxisAngle(this.pos, radians);
    this.fwd.applyQuaternion(_q);
    this.orthonormalise();
  }

  toggleDeploy() {
    this.deployed = !this.deployed;
    if (this.deployed) this.speed = 0;
    return this.deployed;
  }

  update(dt, input) {
    if (!this.deployed) {
      const boosting = input.boost;
      const target = boosting ? BOOST_SPEED : MAX_SPEED;
      if (input.throttle > 0) this.speed += ACCEL * input.throttle * dt;
      else if (input.throttle < 0) this.speed += BRAKE * input.throttle * dt;
      else {
        // Coast down: exponential drag plus a flat rolling friction, so it
        // rolls to a stop instead of asymptotically creeping forever.
        this.speed *= Math.pow(DRAG, dt);
        this.speed -= Math.sign(this.speed) * Math.min(Math.abs(this.speed), 8 * dt);
      }

      this.speed = Math.max(-18, Math.min(target, this.speed));
      if (Math.abs(this.speed) < 0.05) this.speed = 0;

      // Steering authority falls off with speed, so it feels like something
      // heavy rather than a mouse cursor.
      if (input.steer !== 0) {
        const auth = 0.42 + 0.58 / (1 + Math.abs(this.speed) * 0.035);
        this.turn(-input.steer * TURN_RATE * auth * dt * (this.speed < 0 ? -1 : 1));
      }

      this.advance(this.speed * dt);
    } else {
      this.speed = 0;
    }

    // Deploy transition
    const dir = this.deployed ? 1 : -1;
    this.deployT = Math.max(0, Math.min(1, this.deployT + dir * dt * 1.6));
    const e = this.deployT * this.deployT * (3 - 2 * this.deployT);

    for (const leg of this.legs) {
      leg.scale.y = 0.25 + e * 0.95;
      leg.rotation.x = leg.userData.rest.x * (1 - e * 0.55);
      leg.rotation.z = leg.userData.rest.z * (1 - e * 0.55);
    }
    this.coreMat.emissive.setRGB(1 - e * 0.36, 1 - e * 1.0, 1 - e * 0.20);
    this.coreMat.emissiveIntensity = 2.1 + e * 1.1;

    this.snapToGround(dt, e);
    return e;
  }

  snapToGround(dt = 0, deployEase = 0) {
    const groundR = WORLD.radius + this.planet.heightAt(this.pos);
    this.planet.normalAt(this.pos, this.surfaceNormal, 2.5);

    // Smooth the normal, or every pebble jerks the whole hull.
    const k = dt > 0 ? Math.min(1, dt * 7) : 1;
    this.smoothNormal.lerp(this.surfaceNormal, k).normalize();

    this.bob += dt * Math.abs(this.speed) * 0.55;
    const suspension = Math.sin(this.bob) * 0.09 * Math.min(1, Math.abs(this.speed) / 20) * (1 - deployEase);
    const ride = RIDE_HEIGHT - deployEase * 0.85 + suspension;

    this.worldPos.copy(this.pos).multiplyScalar(groundR + ride);
    this.group.position.copy(this.worldPos);

    // Sit mostly on the terrain normal, partly on the planet normal. Fully on
    // the terrain normal makes the hull twitch on every ridge; fully on the
    // planet normal makes it float through hillsides.
    _up.copy(this.smoothNormal).multiplyScalar(0.78).addScaledVector(this.pos, 0.22).normalize();
    _back.copy(this.fwd).addScaledVector(_up, -this.fwd.dot(_up)).normalize().negate();
    _right.crossVectors(_up, _back).normalize();
    _m.makeBasis(_right, _up, _back);
    this.group.quaternion.setFromRotationMatrix(_m);

    const spin = dt * this.speed * 0.74;
    for (const w of this.wheels) w.rotation.x -= spin;
  }

  // World-space direction the headlamps point.
  lampDir(out) {
    return out.copy(this.fwd).addScaledVector(this.smoothNormal, -0.28).normalize();
  }
}
