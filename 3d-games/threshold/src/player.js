// First-person controller: movement, grid collision, and interaction with openings.
//
// The interaction design matters more than it looks. A door is a tap, because a door is a decision.
// A window is ANALOG — hold E, or roll the wheel — because "opening the window makes a bit more
// noise" needs a dial and not a switch, and because the first ten percent of opening is where
// almost all of the loudness lives (BUILD_PROMPT §3.2). A window you can only slam open would
// throw away the best thing in the model.

import { isSolidMat } from './world/materials.js';
import { makeListener } from './audio/model.js';

const EYE = 1.62;
const RADIUS = 0.30;
const HEIGHT = 1.78;
const WALK = 3.1;
const SPRINT = 6.4;
const GRAVITY = 22;
const REACH = 2.8;

// Below this, a door leaf or window sash is physically in the way. It is also what keeps the
// listener out of the blend band of a shut opening — see blendRadius() in audio/model.js.
const PASSABLE = 0.35;

export class Player {
  constructor(grid, spawn) {
    this.grid = grid;
    this.pos = [spawn.x, spawn.y, spawn.z];
    this.vel = [0, 0, 0];
    this.yaw = spawn.yaw;
    this.pitch = spawn.pitch;
    this.onGround = false;
    this.keys = new Set();
    this.stepDistance = 0;
    this.lastStepMaterial = 0;
    this.interactDir = 1;   // hold-E alternates between opening and closing
    this.interactHeld = false;
    this.target = null;     // the opening you are looking at
    this.speed = 0;
  }

  /** Solid to a body — openings block until they are actually open enough to walk through. */
  _blocked(x, y, z) {
    const op = this.grid.openingAt(x, y, z);
    if (op) return op.openness < PASSABLE;
    return isSolidMat(this.grid.get(x, y, z));
  }

  _hits(px, py, pz) {
    const x0 = Math.floor(px - RADIUS), x1 = Math.floor(px + RADIUS);
    const z0 = Math.floor(pz - RADIUS), z1 = Math.floor(pz + RADIUS);
    const y0 = Math.floor(py), y1 = Math.floor(py + HEIGHT - 0.01);
    for (let x = x0; x <= x1; x++)
      for (let y = y0; y <= y1; y++)
        for (let z = z0; z <= z1; z++)
          if (this._blocked(x, y, z)) return true;
    return false;
  }

  look(dx, dy) {
    this.yaw -= dx * 0.0024;
    this.pitch -= dy * 0.0024;
    const lim = Math.PI / 2 - 0.02;
    this.pitch = Math.max(-lim, Math.min(lim, this.pitch));
  }

  update(dt, audio) {
    const k = this.keys;
    const sprint = k.has('ShiftLeft') || k.has('ShiftRight');
    const speed = sprint ? SPRINT : WALK;

    // Movement axes come STRAIGHT OUT OF makeListener rather than being written out again here,
    // and that is the fix for a bug, not a tidy-up. The two used to be separate transcriptions of
    // the same intent: the model built its right vector as up × forward and the controller inlined
    // the matching (cos, -sin). They agreed with each other and both disagreed with the camera, so
    // every test passed and A and D were swapped on screen. One definition, no drift. Pitch is
    // dropped so that looking up does not slow you down.
    const axes = makeListener(this.pos, this.yaw, 0);
    const f = axes.forward, r = axes.right;
    let fx = 0, fz = 0;
    if (k.has('KeyW')) { fx += f[0]; fz += f[2]; }
    if (k.has('KeyS')) { fx -= f[0]; fz -= f[2]; }
    if (k.has('KeyD')) { fx += r[0]; fz += r[2]; }
    if (k.has('KeyA')) { fx -= r[0]; fz -= r[2]; }
    const m = Math.hypot(fx, fz);
    if (m > 0) { fx = fx / m * speed; fz = fz / m * speed; }
    this.speed = m > 0 ? speed / SPRINT : 0;

    this.vel[1] -= GRAVITY * dt;
    if (k.has('Space') && this.onGround) { this.vel[1] = 6.2; this.onGround = false; }

    // Axis-separated resolution, so sliding along a wall works and a corner never traps you.
    const step = (axis, amount) => {
      if (amount === 0) return;
      const next = this.pos.slice();
      next[axis] += amount;
      if (!this._hits(next[0], next[1], next[2])) { this.pos[axis] = next[axis]; return true; }
      if (axis === 1) {
        if (amount < 0) { this.onGround = true; }
        this.vel[1] = 0;
      }
      return false;
    };

    step(0, fx * dt);
    step(2, fz * dt);
    const wasGround = this.onGround;
    this.onGround = false;
    step(1, this.vel[1] * dt);
    if (this.vel[1] < 0 && !this.onGround && wasGround) this.onGround = false;

    // Footsteps, spaced by distance rather than time so sprinting sounds like sprinting.
    if (this.onGround && m > 0) {
      this.stepDistance += speed * dt;
      const stride = sprint ? 0.92 : 0.72;
      if (this.stepDistance > stride) {
        this.stepDistance = 0;
        const under = this.grid.get(
          Math.floor(this.pos[0]), Math.floor(this.pos[1] - 0.2), Math.floor(this.pos[2]));
        this.lastStepMaterial = under;
        if (audio) audio.footstep(under, sprint ? 1 : 0.7);
      }
    }
  }

  /** Nearest opening you are actually looking at, or null. */
  findTarget() {
    const f = [Math.sin(this.yaw) * Math.cos(this.pitch), Math.sin(this.pitch),
      Math.cos(this.yaw) * Math.cos(this.pitch)];
    const eye = [this.pos[0], this.pos[1] + EYE, this.pos[2]];
    let best = null, bestScore = 0.55;
    for (const op of this.grid.openingList) {
      const d = [op.center[0] - eye[0], op.center[1] - eye[1], op.center[2] - eye[2]];
      const dist = Math.hypot(d[0], d[1], d[2]);
      if (dist > REACH) continue;
      const dot = (d[0] * f[0] + d[1] * f[1] + d[2] * f[2]) / dist;
      if (dot > bestScore) { bestScore = dot; best = op; }
    }
    this.target = best;
    return best;
  }

  /** Tap E. Doors swing; windows begin travelling in whichever direction is next. */
  interactPress() {
    const op = this.findTarget();
    if (!op) return null;
    if (op.type === 'door') { op.toggle(); return op; }
    this.interactHeld = true;
    this.interactDir = op.openness > 0.5 ? -1 : 1;
    return op;
  }

  interactRelease() {
    this.interactHeld = false;
  }

  /** Hold E on a window: open it by degrees, and stop where you let go. */
  interactHold(dt) {
    if (!this.interactHeld) return;
    const op = this.target;
    if (!op || op.type !== 'window') return;
    op.nudge(this.interactDir * dt * 0.42);
  }

  /** Wheel over a window — the same dial, for people who would rather aim than hold. */
  wheel(delta) {
    const op = this.findTarget();
    if (!op || op.type !== 'window') return;
    op.nudge(-Math.sign(delta) * 0.07);
  }

  get eye() {
    return [this.pos[0], this.pos[1] + EYE, this.pos[2]];
  }

  /** Pose in the form the acoustics want. */
  pose() {
    return makeListener(this.eye, this.yaw, this.pitch);
  }
}
