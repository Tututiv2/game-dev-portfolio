// The machine. It is the map, the economy and the health bar at once.
import { CFG } from './config.js';
import { clamp, lerp, TAU } from './util.js';

const H = CFG.hv;

export class Harvester {
  constructor() {
    this.x = 0; this.y = 0;
    this.ang = -Math.PI / 2;       // pointing "up" the field
    this.speed = 0;
    this.throttle = 0;
    this.hull = H.hullMax;
    this.reel = 0;
    this.dist = 0;                 // metres
    this.grainThisFrame = 0;
    this.px = 0; this.py = 0; this.pang = this.ang;
  }

  /** Rouse radius. The single number the whole game hangs off. */
  get noise() {
    return lerp(CFG.noise.idle, CFG.noise.full, Math.pow(this.throttle, CFG.noise.curve));
  }

  get speedFrac() { return this.speed / H.maxSpeed; }

  /** `ctrl` is a plain {up,down,left,right,cut} — never the Input object, so the
   *  headless sim can drive the machine with a scripted policy. */
  update(dt, ctrl, field) {
    // ---- throttle: a lever that stays where you leave it ----
    if (ctrl.up) this.throttle += H.throttleRate * dt;
    if (ctrl.down) this.throttle -= H.throttleRate * dt;
    if (ctrl.cut) this.throttle -= H.throttleRate * 4 * dt; // panic
    if (ctrl.throttleTarget !== undefined) {
      const d = ctrl.throttleTarget - this.throttle;
      this.throttle += clamp(d, -H.throttleRate * dt, H.throttleRate * dt);
    }
    this.throttle = clamp(this.throttle, 0, 1);

    // ---- speed: heavy, slow to build, slow to shed ----
    const target = H.maxSpeed * this.throttle;
    const rate = target > this.speed ? H.accel : H.brake;
    this.speed += clamp(target - this.speed, -rate * dt, rate * dt);

    // ---- steering: gets lazy the faster you go ----
    let steer = 0;
    if (ctrl.left) steer -= 1;
    if (ctrl.right) steer += 1;
    const authority = lerp(1, H.turnAtSpeed, this.speedFrac);
    this.ang += steer * H.turnRate * authority * dt;
    if (this.ang > Math.PI) this.ang -= TAU;
    if (this.ang < -Math.PI) this.ang += TAU;

    this.px = this.x; this.py = this.y; this.pang = this.ang;
    this.x += Math.cos(this.ang) * this.speed * dt;
    this.y += Math.sin(this.ang) * this.speed * dt;
    this.dist += (this.speed * dt) / CFG.pxPerMetre;

    this.reel += (2 + this.speedFrac * 26) * dt;

    // ---- cut ----
    const halfW = (H.header.y1 - H.header.y0) / 2 - 4;
    const off = (H.header.x0 + H.header.x1) / 2;
    const cells = field.harvest(this.px, this.py, this.pang, this.x, this.y, this.ang, halfW, off);
    this.grainThisFrame = cells * H.grainPerCell;
    return this.grainThisFrame;
  }

  /** World point -> harvester-local. Forward is +X. */
  toLocal(wx, wy, out) {
    const rx = wx - this.x, ry = wy - this.y;
    const c = Math.cos(-this.ang), s = Math.sin(-this.ang);
    out.x = rx * c - ry * s;
    out.y = rx * s + ry * c;
    return out;
  }

  toWorld(lx, ly, out) {
    const c = Math.cos(this.ang), s = Math.sin(this.ang);
    out.x = this.x + lx * c - ly * s;
    out.y = this.y + lx * s + ly * c;
    return out;
  }

  /** 0 = clear, 1 = touching the hull, 2 = inside the cutter bar. */
  overlap(lx, ly, r) {
    if (boxDist(lx, ly, H.header) < r + 2) return 2;
    if (boxDist(lx, ly, H.body) < r + 2) return 1;
    return 0;
  }

  damage(n) {
    this.hull = Math.max(0, this.hull - n);
  }

  repair(n) {
    this.hull = Math.min(H.hullMax, this.hull + n);
  }
}

function boxDist(x, y, b) {
  const qx = clamp(x, b.x0, b.x1), qy = clamp(y, b.y0, b.y1);
  return Math.hypot(x - qx, y - qy);
}
