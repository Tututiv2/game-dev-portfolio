import * as THREE from 'three';
import { clamp } from '../analyze/util.js';

/**
 * First-person controller: pointer-lock look, WASD, gravity, terrain collision.
 *
 * Deliberately walks rather than flies. Being pinned to the ground plane is
 * what makes the photo read as a place you are standing in -- free flight turns
 * the same geometry back into a diorama you are inspecting from outside.
 */

const WALK = 5.4;
const SPRINT = 11;
const ACCEL = 42;
const FRICTION = 11;
const GRAVITY = 22;
const JUMP = 7.2;
const LOOK = 0.0022;
const MAX_PITCH = Math.PI / 2 - 0.05;

export function createPlayer(camera, world, opts = {}) {
  const eyeHeight = opts.eyeHeight ?? 1.7;
  const state = {
    pos: new THREE.Vector3(0, eyeHeight, 0),
    vel: new THREE.Vector3(),
    yaw: 0,
    pitch: 0,
    grounded: true,
    bob: 0,
    enabled: false,
  };

  const keys = new Set();
  const el = opts.domElement ?? document.body;

  function onKeyDown(e) {
    if (e.repeat) return;
    keys.add(e.code);
    // Space scrolls the page if the pointer is not locked; it is a jump here.
    if (e.code === 'Space') e.preventDefault();
  }
  function onKeyUp(e) {
    keys.delete(e.code);
  }
  function onMouseMove(e) {
    if (!state.enabled) return;
    state.yaw -= e.movementX * LOOK;
    state.pitch = clamp(state.pitch - e.movementY * LOOK, -MAX_PITCH, MAX_PITCH);
  }
  function onPointerLockChange() {
    state.enabled = document.pointerLockElement === el;
    if (!state.enabled) keys.clear();
  }

  window.addEventListener('keydown', onKeyDown);
  window.addEventListener('keyup', onKeyUp);
  document.addEventListener('mousemove', onMouseMove);
  document.addEventListener('pointerlockchange', onPointerLockChange);

  function lock() {
    el.requestPointerLock?.();
  }

  function update(dt) {
    const step = Math.min(dt, 0.05); // never integrate a stalled frame

    // Desired direction in the horizontal plane.
    let fwd = 0;
    let strafe = 0;
    if (keys.has('KeyW') || keys.has('ArrowUp')) fwd += 1;
    if (keys.has('KeyS') || keys.has('ArrowDown')) fwd -= 1;
    if (keys.has('KeyD') || keys.has('ArrowRight')) strafe += 1;
    if (keys.has('KeyA') || keys.has('ArrowLeft')) strafe -= 1;

    const sprinting = keys.has('ShiftLeft') || keys.has('ShiftRight');
    const target = sprinting ? SPRINT : WALK;

    const sin = Math.sin(state.yaw);
    const cos = Math.cos(state.yaw);
    // Forward is -z in three space.
    const dirX = -sin * fwd + cos * strafe;
    const dirZ = -cos * fwd - sin * strafe;
    const len = Math.hypot(dirX, dirZ);

    if (len > 0 && state.enabled) {
      const nx = dirX / len;
      const nz = dirZ / len;
      state.vel.x += nx * ACCEL * step;
      state.vel.z += nz * ACCEL * step;
      const speed = Math.hypot(state.vel.x, state.vel.z);
      if (speed > target) {
        state.vel.x = (state.vel.x / speed) * target;
        state.vel.z = (state.vel.z / speed) * target;
      }
    } else {
      const damp = Math.exp(-FRICTION * step);
      state.vel.x *= damp;
      state.vel.z *= damp;
    }

    if (keys.has('Space') && state.grounded && state.enabled) {
      state.vel.y = JUMP;
      state.grounded = false;
    }

    state.vel.y -= GRAVITY * step;

    state.pos.x += state.vel.x * step;
    state.pos.z += state.vel.z * step;
    state.pos.y += state.vel.y * step;

    const ground = world.heightAt(state.pos.x, state.pos.z) + eyeHeight;
    if (state.pos.y <= ground) {
      state.pos.y = ground;
      state.vel.y = 0;
      state.grounded = true;
    } else {
      state.grounded = false;
    }

    // Head bob, scaled by actual ground speed so it stops when you do.
    const speed = Math.hypot(state.vel.x, state.vel.z);
    state.bob += step * speed * 1.6;
    const bobY = state.grounded ? Math.sin(state.bob) * Math.min(speed / SPRINT, 1) * 0.055 : 0;

    camera.position.set(state.pos.x, state.pos.y + bobY, state.pos.z);
    camera.rotation.set(state.pitch, state.yaw, 0, 'YXZ');
  }

  function dispose() {
    window.removeEventListener('keydown', onKeyDown);
    window.removeEventListener('keyup', onKeyUp);
    document.removeEventListener('mousemove', onMouseMove);
    document.removeEventListener('pointerlockchange', onPointerLockChange);
  }

  return { state, update, lock, dispose };
}
