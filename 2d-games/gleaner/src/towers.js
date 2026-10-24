// Weapons bolted to the deck. They ride the machine, so their world position
// and facing are derived from the hull every frame — nothing is placed in the
// world, ever.
import { CFG, HARDPOINTS, COL } from './config.js';
import { ETYPE } from './swarm.js';
import { angDiff, clamp, rand, TAU } from './util.js';
import { TOWER_ART } from './art.js';

export const TOWERS = [
  {
    key: 'thresher', name: 'THRESHER', art: 'thresher', cost: 30,
    range: 96, period: 0.55, dmg: 7, turn: 0,
    blurb: 'A ring of glass blades. No aiming, no gaps, no reach.',
  },
  {
    key: 'glasshot', name: 'GLASSHOT', art: 'glasshot', cost: 55,
    range: 190, period: 1.15, dmg: 11, pellets: 7, spread: 0.44, turn: 4.5,
    blurb: 'Throws a cone of shattered crystal. Eats packed ranks.',
  },
  {
    key: 'sunlance', name: 'SUNLANCE', art: 'sunlance', cost: 90,
    range: 420, period: 1.35, dmg: 145, turn: 2.4,
    blurb: 'Stored daylight, spent all at once. Picks the largest thing it can see.',
  },
  {
    key: 'censer', name: 'CENSER', art: 'censer', cost: 130,
    range: 150, arc: 0.5, dps: 34, burn: 26, turn: 3.2,
    blurb: 'Swings burning resin. Sets the Chorus alight and keeps it burning.',
  },
];

const _w = { x: 0, y: 0 };

export class TowerSet {
  constructor() {
    this.mounted = new Array(HARDPOINTS.length).fill(null);
    this.built = TOWERS.map(() => 0);
    this._nbr = new Int32Array(512);
  }

  costOf(defIndex) {
    return Math.round(TOWERS[defIndex].cost * Math.pow(CFG.costGrowth, this.built[defIndex]));
  }

  place(slot, defIndex) {
    this.mounted[slot] = {
      def: defIndex, cd: 0, ang: 0, heat: 0, spin: rand(0, TAU), paid: this.costOf(defIndex),
    };
    this.built[defIndex]++;
  }

  remove(slot) {
    const t = this.mounted[slot];
    if (!t) return 0;
    this.mounted[slot] = null;
    this.built[t.def]--;
    return Math.round(t.paid * CFG.sellRefund);
  }

  /** World position of a hardpoint, following the hull. */
  worldOf(hv, slot, out) {
    const hp = HARDPOINTS[slot];
    return hv.toWorld(hp.x, hp.y, out);
  }

  update(dt, w) {
    const hv = w.hv, swarm = w.swarm, nbr = this._nbr;

    for (let s = 0; s < this.mounted.length; s++) {
      const t = this.mounted[s];
      if (!t) continue;
      const def = TOWERS[t.def];
      this.worldOf(hv, s, _w);
      const tx = _w.x, ty = _w.y;

      t.heat = Math.max(0, t.heat - dt * 5);
      t.spin += dt * (def.key === 'thresher' ? 9 : 1);

      const cnt = swarm.grid.query(tx, ty, def.range, nbr);

      if (def.key === 'thresher') {
        t.cd -= dt;
        if (t.cd <= 0 && cnt > 0) {
          t.cd = def.period;
          let hitAny = false;
          for (let k = 0; k < cnt; k++) {
            const j = nbr[k];
            if (!swarm.alive[j]) continue;
            const dx = swarm.x[j] - tx, dy = swarm.y[j] - ty;
            if (dx * dx + dy * dy > def.range * def.range) continue;
            swarm.hit(j, def.dmg, w);
            hitAny = true;
          }
          if (hitAny) {
            w.fx.ring(tx, ty, def.range, 'rgba(210,210,225,0.5)');
            w.audio.thud();
          }
        }
        continue;
      }

      // ---- everything else needs a target ----
      let best = -1, bestScore = -Infinity;
      for (let k = 0; k < cnt; k++) {
        const j = nbr[k];
        if (!swarm.alive[j]) continue;
        const dx = swarm.x[j] - tx, dy = swarm.y[j] - ty;
        const d2 = dx * dx + dy * dy;
        if (d2 > def.range * def.range) continue;
        let score;
        if (def.key === 'sunlance') score = ETYPE[swarm.type[j]].hp * 1000 - d2 * 0.001;
        else score = -d2;
        if (score > bestScore) { bestScore = score; best = j; }
      }

      if (best < 0) {
        // idle: point the way the machine is going
        t.ang += angDiff(t.ang, hv.ang) * Math.min(1, dt * 2);
        continue;
      }

      const aim = Math.atan2(swarm.y[best] - ty, swarm.x[best] - tx);
      t.ang += angDiff(t.ang, aim) * Math.min(1, dt * def.turn);
      const onTarget = Math.abs(angDiff(t.ang, aim)) < 0.28;

      if (def.key === 'censer') {
        if (!onTarget) continue;
        t.heat = 1;
        const half = def.arc;
        for (let k = 0; k < cnt; k++) {
          const j = nbr[k];
          if (!swarm.alive[j]) continue;
          const dx = swarm.x[j] - tx, dy = swarm.y[j] - ty;
          if (dx * dx + dy * dy > def.range * def.range) continue;
          if (Math.abs(angDiff(t.ang, Math.atan2(dy, dx))) > half) continue;
          swarm.hit(j, def.dps * dt, w);
          if (swarm.alive[j]) swarm.burn[j] = Math.min(def.burn, swarm.burn[j] + def.burn * dt * 2.4);
        }
        w.fx.flame(tx, ty, t.ang, def.range, def.arc);
        continue;
      }

      t.cd -= dt;
      if (t.cd > 0 || !onTarget) continue;
      t.cd = def.period;
      t.heat = 1;

      if (def.key === 'glasshot') {
        w.audio.shot(0.55, 190);
        for (let p = 0; p < def.pellets; p++) {
          const a = t.ang + rand(-def.spread, def.spread);
          const reach = def.range * rand(0.7, 1);
          let hitJ = -1, hitD = Infinity;
          for (let k = 0; k < cnt; k++) {
            const j = nbr[k];
            if (!swarm.alive[j]) continue;
            const dx = swarm.x[j] - tx, dy = swarm.y[j] - ty;
            const dd = Math.hypot(dx, dy);
            if (dd > reach) continue;
            if (Math.abs(angDiff(a, Math.atan2(dy, dx))) > 0.075) continue;
            if (dd < hitD) { hitD = dd; hitJ = j; }
          }
          const len = hitJ >= 0 ? hitD : reach;
          w.fx.tracer(tx, ty, tx + Math.cos(a) * len, ty + Math.sin(a) * len, 'rgba(255,224,168,0.7)', 1.4);
          if (hitJ >= 0) { swarm.hit(hitJ, def.dmg, w); w.fx.spark(swarm.x[hitJ], swarm.y[hitJ], '#ffd08a'); }
        }
        w.shake = Math.max(w.shake, 1.6);
      } else if (def.key === 'sunlance') {
        w.audio.shot(0.9, 90);
        const bx = swarm.x[best], by = swarm.y[best];
        w.fx.tracer(tx, ty, bx, by, 'rgba(255,246,214,0.95)', 2.6);
        swarm.hit(best, def.dmg, w);
        w.fx.spark(bx, by, '#fff0c0');
        w.shake = Math.max(w.shake, 3.2);
      }
    }
  }

  draw(g, hv, time) {
    for (let s = 0; s < this.mounted.length; s++) {
      const t = this.mounted[s];
      if (!t) continue;
      const hp = HARDPOINTS[s];
      const def = TOWERS[t.def];

      g.save();
      g.translate(hp.x, hp.y);

      // mounting plate stays square to the deck
      g.fillStyle = COL.ironDark;
      g.beginPath(); g.roundRect(-11, -11, 22, 22, 4); g.fill();
      g.strokeStyle = 'rgba(0,0,0,0.45)'; g.lineWidth = 1.4;
      g.beginPath(); g.roundRect(-11, -11, 22, 22, 4); g.stroke();

      // the weapon itself aims in world space, so undo the hull's rotation
      g.rotate(t.ang - hv.ang);
      TOWER_ART[def.art](g, t.spin, t.heat);
      g.restore();
    }
  }
}
