// One camera, two jobs.
//
// MOBILE: locked behind the crawler like a driving game — you feel the speed.
// DEPLOYED: lifts up and back into an RTS orbit — you see your base and what is
// coming for it.
//
// The swap is not a cut. Both target transforms are computed every frame and
// blended by the same eased deploy value that drives the legs, so the camera
// itself tells you which mode you are in before you read a word of UI.

import * as THREE from 'three';

const CHASE = { dist: 23, height: 9.5, look: 3.6, fov: 62, fovBoost: 74 };
const ORBIT = { pitch: 0.86, fov: 52, minDist: 34, maxDist: 240, startDist: 92 };

const _up = new THREE.Vector3();
const _fwd = new THREE.Vector3();
const _right = new THREE.Vector3();
const _chasePos = new THREE.Vector3();
const _chaseAim = new THREE.Vector3();
const _orbitPos = new THREE.Vector3();
const _orbitAim = new THREE.Vector3();
const _desired = new THREE.Vector3();
const _aim = new THREE.Vector3();
const _camDir = new THREE.Vector3();
const _q = new THREE.Quaternion();

const MIN_CLEARANCE = 3.5;   // metres the camera keeps above the ground

export class CameraRig {
  constructor(camera, planet) {
    this.camera = camera;
    this.planet = planet;
    this.orbitYaw = 0;
    this.orbitPitch = ORBIT.pitch;
    this.orbitDist = ORBIT.startDist;
    this.pos = new THREE.Vector3();
    this.aim = new THREE.Vector3();
    this.initialised = false;
  }

  onDrag(dx, dy) {
    this.orbitYaw -= dx * 0.005;
    this.orbitPitch = Math.max(0.18, Math.min(1.45, this.orbitPitch - dy * 0.004));
  }

  onWheel(delta) {
    this.orbitDist = Math.max(ORBIT.minDist, Math.min(ORBIT.maxDist, this.orbitDist * (1 + delta * 0.0012)));
  }

  update(crawler, blend, dt) {
    _up.copy(crawler.pos);
    _fwd.copy(crawler.fwd).addScaledVector(_up, -crawler.fwd.dot(_up)).normalize();

    // --- chase ---------------------------------------------------------------
    const speedT = Math.min(1, Math.abs(crawler.speed) / 76);
    const back = CHASE.dist + speedT * 5.5;
    _chasePos.copy(crawler.worldPos)
      .addScaledVector(_up, CHASE.height + speedT * 1.5)
      .addScaledVector(_fwd, -back);
    _chaseAim.copy(crawler.worldPos).addScaledVector(_up, CHASE.look).addScaledVector(_fwd, 6);

    // --- orbit ---------------------------------------------------------------
    _q.setFromAxisAngle(_up, this.orbitYaw);
    _right.copy(_fwd).applyQuaternion(_q);
    const horiz = Math.cos(this.orbitPitch) * this.orbitDist;
    const vert = Math.sin(this.orbitPitch) * this.orbitDist;
    _orbitPos.copy(crawler.worldPos).addScaledVector(_up, vert).addScaledVector(_right, -horiz);
    _orbitAim.copy(crawler.worldPos).addScaledVector(_up, 2.0);

    _desired.copy(_chasePos).lerp(_orbitPos, blend);
    _aim.copy(_chaseAim).lerp(_orbitAim, blend);

    if (!this.initialised) {
      this.pos.copy(_desired);
      this.aim.copy(_aim);
      this.initialised = true;
    } else {
      // Frame-rate independent smoothing. The chase cam lags a little (that lag
      // IS the sense of speed); the orbit cam is tighter so dragging feels
      // direct.
      const kPos = 1 - Math.pow(0.0009 + blend * 0.00002, dt);
      const kAim = 1 - Math.pow(0.00002, dt);
      this.pos.lerp(_desired, kPos);
      this.aim.lerp(_aim, kAim);
    }

    // Keep the camera out of the ground. Backing 23 m down a slope puts it
    // inside the hill behind you every time, and the first thing you see is the
    // inside of a mountain.
    if (this.planet) {
      _camDir.copy(this.pos).normalize();
      const floor = this.planet.surfaceRadiusAt(_camDir) + MIN_CLEARANCE;
      const r = this.pos.length();
      if (r < floor) this.pos.multiplyScalar(floor / r);
    }

    this.camera.position.copy(this.pos);
    this.camera.up.copy(_up);
    this.camera.lookAt(this.aim);

    const fov = THREE.MathUtils.lerp(
      THREE.MathUtils.lerp(CHASE.fov, CHASE.fovBoost, speedT),
      ORBIT.fov,
      blend
    );
    if (Math.abs(this.camera.fov - fov) > 0.01) {
      this.camera.fov = fov;
      this.camera.updateProjectionMatrix();
    }
  }
}
