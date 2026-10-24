// The skill ceiling. Pure.
//
// A beat-parry alone is one input. Rocket League is hard because inputs STACK --
// boost while air-rolling while timing the touch. These are the stacks:
//
//   Flow         every action (attack, dash, jump, parry) graded against the beat.
//                On it: the multiplier climbs. Off it: gone. Movement never counts.
//   Directional  a parry must flick the mouse toward the side the blow comes from.
//   Aura Reset   the air dash is ONE charge; it comes back only by landing a hit or
//                a perfect parry in mid-air. Chain them and you never touch down.
//   Deflect-aim  a perfect parry on a thrown spear sends it down your crosshair.

export const FLOW = {
  on: 0.065,        // within 65 ms of a beat = on beat
  off: 0.09,        // further than 90 ms = off beat, flow breaks. A thin band between is forgiven.
                    // (A wide forgiven band let a random masher reach flow 6 -- the test caught it.)
  idleBars: 2,      // doing nothing on-beat for 2 bars lets the flow lapse
  step: 0.2,        // multiplier per flow level
  cap: 20,          // level cap -> x5
};

export class Flow {
  constructor(cfg = FLOW) {
    this.cfg = cfg;
    this.level = 0;
    this.best = 0;
    this.lastUp = -Infinity;
    this.lastBeat = null;
  }
  get mult() { return 1 + Math.min(this.level, this.cfg.cap) * this.cfg.step; }
  // t is already latency-corrected by the caller. grid may be null (no music): never counts.
  act(t, grid) {
    if (!grid) return { result: 'none', level: this.level, off: null };
    const off = grid.offset(t);
    const a = Math.abs(off);
    if (this.level > 0 && t - this.lastUp > this.cfg.idleBars * grid.bar) this.level = 0;
    if (a <= this.cfg.on) {
      // One climb per beat: three swings inside the same window are one on-beat action.
      const k = Math.round(grid.beatAt(t));
      if (k === this.lastBeat) return { result: 'none', level: this.level, off };
      this.lastBeat = k;
      this.level++;
      this.lastUp = t;
      if (this.level > this.best) this.best = this.level;
      return { result: 'up', level: this.level, off };
    }
    if (a > this.cfg.off) {
      const had = this.level;
      this.level = 0;
      return { result: had > 0 ? 'break' : 'none', level: 0, off };
    }
    return { result: 'none', level: this.level, off };
  }
  reset() { this.level = 0; this.lastUp = -Infinity; this.lastBeat = null; }
}

// Mouse movement samples [{t, dx, dy}] (pointer-lock movementX/Y, t on the same
// clock as the press). Sum what moved within `win` of the press and decide which
// way the flick went. Screen y grows downward, so a flick UP is dy < 0.
export const FLICK = { win: 0.1, min: 14 };

export function readFlick(samples, pressT, cfg = FLICK) {
  let sx = 0, sy = 0;
  for (const s of samples) {
    if (s.t >= pressT - cfg.win && s.t <= pressT + cfg.win) { sx += s.dx; sy += s.dy; }
  }
  const mag = Math.hypot(sx, sy);
  if (mag < cfg.min) return null;
  if (-sy > Math.abs(sx)) return 'over';
  if (Math.abs(sx) >= Math.abs(sy)) return sx < 0 ? 'left' : 'right';
  return null; // a flick DOWN answers nothing
}

// A timing grade + (optionally) a direction check -> the final parry result.
// Right timing, wrong direction is a BLOCK, not a perfect: you stopped it, you did not own it.
export function judgeParry(timing, flick, attackDir, directional) {
  if (timing.grade === 'miss') return 'miss';
  // 'flawless' and 'perfect' both need the right direction; wrong way is a block either way
  if (!directional || !attackDir) return timing.grade;
  if (flick === attackDir) return timing.grade;
  return 'good';
}

export const AIR = {
  gravity: 26,
  jump: 9.4,
  dashSpeed: 18,
  dashTime: 0.19,
  charges: 1,
  popOnReset: 5.5,     // a reset also bumps you up a little, like a flip-reset touch
  hangAfterHit: 0.12,  // brief float after an air hit so a chain is humanly possible
};

export class AirState {
  constructor() { this.charges = AIR.charges; this.grounded = true; this.resets = 0; this.chain = 0; this.bestChain = 0; }
  land() { this.grounded = true; this.charges = AIR.charges; this.chain = 0; }
  leave() { this.grounded = false; }
  canDash() { return this.grounded || this.charges > 0; }
  useDash() { if (!this.grounded) this.charges--; }
  // A hit or perfect parry while airborne. Returns true if it was a reset.
  touch() {
    if (this.grounded) return false;
    const wasEmpty = this.charges < AIR.charges;
    this.charges = AIR.charges;
    this.chain++;
    if (this.chain > this.bestChain) this.bestChain = this.chain;
    if (wasEmpty) this.resets++;
    return wasEmpty;
  }
}

// Aura points. Everything is multiplied by Flow; air doubles it.
export const POINTS = { hit: 10, kill: 40, perfect: 100, block: 10, reset: 150, deflect: 120, deflectKill: 250, release: 400 };
export function score(kind, flowMult = 1, airborne = false) {
  return Math.round((POINTS[kind] || 0) * flowMult * (airborne ? 2 : 1));
}

// Ray (origin, unit dir) vs sphere -> distance or -1. For the deflected spear.
export function raySphere(o, d, c, r) {
  const ox = o.x - c.x, oy = o.y - c.y, oz = o.z - c.z;
  const b = ox * d.x + oy * d.y + oz * d.z;
  const cc = ox * ox + oy * oy + oz * oz - r * r;
  const h = b * b - cc;
  if (h < 0) return -1;
  const t = -b - Math.sqrt(h);
  return t >= 0 ? t : (-b + Math.sqrt(h) >= 0 ? 0 : -1);
}
