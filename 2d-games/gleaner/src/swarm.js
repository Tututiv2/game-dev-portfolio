// The tide. Structure-of-arrays because at 1500 units the object churn of one
// class instance each is what kills the frame, not the maths.
import { CFG } from './config.js';
import { Grid, TAU, clamp, rand, randInt, rnd } from './util.js';

// THE CHORUS — what lives in the Goldsea and comes when the Gleaner sings.
//
// Husks are FASTER than the machine on purpose, and by a wide enough margin to
// actually close a gap — at +10% they technically pursued but took 20 seconds
// to arrive, which read as "you escaped". If you could outrun the Chorus the
// choir would buy safety as well as lumen and the design collapses into
// "hold W": you cannot escape, you can only be quieter. Reavers and Cantors are
// slower than you — they are threats you DRIVE INTO, not threats that chase.
export const ETYPE = [
  { name: 'Husk',   hp: 9,   spd: 296, dps: 5,  r: 5,  worth: 0.5, unlock: 0    },
  { name: 'Reaver', hp: 46,  spd: 178, dps: 15, r: 9,  worth: 2.0, unlock: 300  },
  { name: 'Cantor', hp: 340, spd: 88,  dps: 42, r: 17, worth: 15,  unlock: 900  },
];

const S = CFG.swarm;
const _l = { x: 0, y: 0 };

export class Swarm {
  constructor() {
    const m = S.max;
    this.x = new Float32Array(m);
    this.y = new Float32Array(m);
    this.vx = new Float32Array(m);
    this.vy = new Float32Array(m);
    this.hp = new Float32Array(m);
    this.burn = new Float32Array(m);
    this.type = new Uint8Array(m);
    this.alive = new Uint8Array(m);
    this.brood = new Float32Array(m);   // brood spawn timer
    this.n = 0;                          // high-water mark
    this.live = 0;
    this.free = [];
    this.counts = [0, 0, 0];

    // Cell size is tuned to the SEPARATION query radius (~13px for mites), not
    // to the tower ranges. At 46 a query pulled ~90 candidates out of a packed
    // blob; at 24 it pulls ~16, and separation is the hottest loop in the game.
    this.grid = new Grid(24);
    this._nbr = new Int32Array(256);

    this.spawnAcc = 0;
    this.swellT = S.swellEvery;
    this.swellWarning = 0;
  }

  slot() {
    if (this.free.length) return this.free.pop();
    if (this.n < S.max) return this.n++;
    return -1;
  }

  spawn(x, y, t) {
    const i = this.slot();
    if (i < 0) return -1;
    const d = ETYPE[t];
    this.x[i] = x; this.y[i] = y;
    this.vx[i] = 0; this.vy[i] = 0;
    this.hp[i] = d.hp;
    this.burn[i] = 0;
    this.type[i] = t;
    this.alive[i] = 1;
    this.brood[i] = rand(3, 6);
    this.live++;
    this.counts[t]++;
    return i;
  }

  kill(i, w) {
    if (!this.alive[i]) return;
    this.alive[i] = 0;
    this.live--;
    const t = this.type[i];
    this.counts[t]--;
    this.free.push(i);
    w.grain += ETYPE[t].worth;
    w.killed++;
    w.fx.burst(this.x[i], this.y[i], t === 2 ? 16 : t === 1 ? 7 : 4, t === 2 ? '#a8c060' : '#3a2a14');
  }

  /** Which types are legal at this distance. */
  maxType(dist) {
    let m = 0;
    for (let t = 1; t < ETYPE.length; t++) if (dist >= ETYPE[t].unlock) m = t;
    return m;
  }

  rollType(dist) {
    const m = this.maxType(dist);
    const r = rnd();
    if (m >= 2 && r > 0.965) return 2;
    if (m >= 1 && r > 0.80) return 1;
    return 0;
  }

  /** One nest's worth, dropped just outside what you can see. */
  cluster(w, radius, ahead) {
    const hv = w.hv;
    let a;
    if (ahead) a = hv.ang + rand(-0.8, 0.8);
    else if (rnd() < S.forwardBias) a = hv.ang + rand(-1.5, 1.5);
    else a = rand(0, TAU);

    const cx = hv.x + Math.cos(a) * radius;
    const cy = hv.y + Math.sin(a) * radius;
    const n = randInt(S.clusterMin, S.clusterMax);
    for (let k = 0; k < n; k++) {
      const ang = rand(0, TAU), rr = Math.sqrt(rnd()) * S.clusterSpread;
      this.spawn(cx + Math.cos(ang) * rr, cy + Math.sin(ang) * rr, this.rollType(hv.dist));
    }
  }

  /** Frees the n bodies furthest from the machine — they are off-screen and
   *  losing ground, so nobody sees them go. */
  recycleFurthest(w, n) {
    const hv = w.hv;
    let freed = 0;
    // One coarse pass: anything past 60% of the cull distance is fair game.
    const lim = (w.viewRadius + 260) * 0.6;
    const lim2 = lim * lim;
    for (let i = 0; i < this.n && freed < n; i++) {
      if (!this.alive[i]) continue;
      const dx = this.x[i] - hv.x, dy = this.y[i] - hv.y;
      if (dx * dx + dy * dy < lim2) continue;
      this.alive[i] = 0; this.live--; this.counts[this.type[i]]--; this.free.push(i);
      freed++;
    }
  }

  updateSpawning(dt, w) {
    const hv = w.hv;
    const radius = w.viewRadius + S.spawnPad;

    // Pressure is a pure function of the dial and how far you have come.
    const ramp = 1 + hv.dist * S.rampPerMetre;
    const noiseF = Math.pow(hv.noise / S.refNoise, S.pressureCurve);
    const rate = S.basePressure * noiseF * ramp;

    this.spawnAcc += rate * dt;
    while (this.spawnAcc >= 1) {
      this.spawnAcc -= 1;
      // At the cap we recycle the furthest body rather than refusing to spawn.
      // Refusing turned the cap into a SHIELD: outrun the swarm, fill the pool
      // with stragglers, and the spawner went quiet exactly when you were
      // pushing hardest. Pressure must always arrive from where you are now.
      if (this.live >= S.max - 40) this.recycleFurthest(w, 30);
      this.cluster(w, radius, false);
    }

    // Swells give the run a heartbeat instead of a flat grind.
    this.swellT -= dt;
    this.swellWarning = this.swellT < S.swellWarn ? this.swellT : 0;
    if (this.swellT <= 0) {
      this.swellT = S.swellEvery;
      w.audio.horn();
      w.shake = Math.max(w.shake, 9);
      for (let k = 0; k < S.swellClusters; k++) this.cluster(w, radius * 1.06, false);
    }
  }

  update(dt, w) {
    this.updateSpawning(dt, w);

    const grid = this.grid;
    grid.clear();
    for (let i = 0; i < this.n; i++) if (this.alive[i]) grid.insert(i, this.x[i], this.y[i]);

    const hv = w.hv;
    const nbr = this._nbr;
    const sep = S.separation;
    const cullR = w.viewRadius + 260;
    const cullR2 = cullR * cullR;
    const headerDPS = CFG.hv.headerDPS * (0.25 + hv.throttle * 0.75);
    const headerBite = CFG.hv.headerBite +
      (CFG.hv.headerBiteAtSpeed - CFG.hv.headerBite) * hv.throttle * hv.throttle;

    for (let i = 0; i < this.n; i++) {
      if (!this.alive[i]) continue;
      const t = this.type[i], d = ETYPE[t];

      if (this.burn[i] > 0) {
        this.hp[i] -= this.burn[i] * dt;
        this.burn[i] *= Math.max(0, 1 - S.burnDecay * dt);
        if (this.burn[i] < 0.4) this.burn[i] = 0;
        if (this.hp[i] <= 0) { this.kill(i, w); continue; }
      }

      let dx = hv.x - this.x[i], dy = hv.y - this.y[i];
      const d2 = dx * dx + dy * dy;

      // Anything this far behind is never catching up — recycle the slot.
      if (d2 > cullR2) {
        this.alive[i] = 0; this.live--; this.counts[t]--; this.free.push(i);
        continue;
      }

      const dl = Math.sqrt(d2) || 1;
      dx /= dl; dy /= dl;

      // ---- contact ----
      let touching = 0;
      if (dl < 240) {
        hv.toLocal(this.x[i], this.y[i], _l);
        touching = hv.overlap(_l.x, _l.y, d.r);
        if (touching === 2) {
          // Inside the cutter bar: it mills them, they chew the header.
          this.hp[i] -= headerDPS * dt;
          hv.damage(d.dps * headerBite * dt);
          if (rnd() < dt * 6) w.fx.spark(this.x[i], this.y[i], '#d8c48a');
          if (this.hp[i] <= 0) { this.kill(i, w); continue; }
        } else if (touching === 1) {
          hv.damage(d.dps * dt);
        }
      }

      // ---- steer ----
      const wob = Math.sin(w.time * 2.3 + i * 0.61) * 0.22;
      let ax = dx - dy * wob, ay = dy + dx * wob;

      const rr = d.r * 2.6;
      const cnt = grid.query(this.x[i], this.y[i], rr, nbr);
      let sx = 0, sy = 0;
      for (let k = 0; k < cnt; k++) {
        const j = nbr[k];
        if (j === i || !this.alive[j]) continue;
        const ox = this.x[i] - this.x[j], oy = this.y[i] - this.y[j];
        const od2 = ox * ox + oy * oy;
        const want = d.r + ETYPE[this.type[j]].r;
        if (od2 > 0.0001 && od2 < want * want) {
          const od = Math.sqrt(od2);
          const f = (want - od) / want;
          sx += (ox / od) * f; sy += (oy / od) * f;
        }
      }
      ax += sx * sep; ay += sy * sep;

      const al = Math.hypot(ax, ay) || 1;
      // Pressed against the hull they stop pushing and start biting.
      const spd = touching === 1 ? 0 : d.spd;
      const tvx = (ax / al) * spd, tvy = (ay / al) * spd;

      this.vx[i] += (tvx - this.vx[i]) * Math.min(1, dt * 9);
      this.vy[i] += (tvy - this.vy[i]) * Math.min(1, dt * 9);
      this.x[i] += this.vx[i] * dt;
      this.y[i] += this.vy[i] * dt;

      // ---- broodmothers keep the tide topped up ----
      if (t === 2) {
        this.brood[i] -= dt;
        if (this.brood[i] <= 0) {
          this.brood[i] = rand(2.6, 4.4);
          const n = randInt(2, 4);
          for (let k = 0; k < n; k++) {
            const a = rand(0, TAU);
            this.spawn(this.x[i] + Math.cos(a) * 22, this.y[i] + Math.sin(a) * 22, 0);
          }
          w.fx.burst(this.x[i], this.y[i], 5, '#c7d98a');
        }
      }
    }
  }

  /** Damage helper used by every weapon. */
  hit(i, dmg, w) {
    if (!this.alive[i]) return false;
    this.hp[i] -= dmg;
    if (this.hp[i] <= 0) { this.kill(i, w); return true; }
    return false;
  }
}
