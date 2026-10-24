// Short-lived visual effects, all in fixed-size ring buffers so nothing
// allocates during a firefight.
import { TAU, rand } from './util.js';

const MAX_T = 320, MAX_P = 900, MAX_R = 40, MAX_F = 24;

export class FX {
  constructor() {
    this.t = []; this.p = []; this.r = []; this.f = [];
    for (let i = 0; i < MAX_T; i++) this.t.push({ life: 0, ax: 0, ay: 0, bx: 0, by: 0, c: '', w: 1 });
    for (let i = 0; i < MAX_P; i++) this.p.push({ life: 0, max: 1, x: 0, y: 0, vx: 0, vy: 0, c: '', s: 1 });
    for (let i = 0; i < MAX_R; i++) this.r.push({ life: 0, x: 0, y: 0, rad: 0, c: '' });
    for (let i = 0; i < MAX_F; i++) this.f.push({ life: 0, x: 0, y: 0, a: 0, len: 0, arc: 0 });
    this.ti = 0; this.pi = 0; this.ri = 0; this.fi = 0;
  }

  tracer(ax, ay, bx, by, c, w) {
    const o = this.t[this.ti = (this.ti + 1) % MAX_T];
    o.life = 0.07; o.ax = ax; o.ay = ay; o.bx = bx; o.by = by; o.c = c; o.w = w;
  }

  spark(x, y, c) {
    for (let i = 0; i < 3; i++) {
      const o = this.p[this.pi = (this.pi + 1) % MAX_P];
      const a = rand(0, TAU), s = rand(40, 150);
      o.life = o.max = rand(0.12, 0.3);
      o.x = x; o.y = y; o.vx = Math.cos(a) * s; o.vy = Math.sin(a) * s; o.c = c; o.s = rand(1.2, 2.4);
    }
  }

  burst(x, y, n, c) {
    for (let i = 0; i < n; i++) {
      const o = this.p[this.pi = (this.pi + 1) % MAX_P];
      const a = rand(0, TAU), s = rand(20, 110);
      o.life = o.max = rand(0.2, 0.55);
      o.x = x; o.y = y; o.vx = Math.cos(a) * s; o.vy = Math.sin(a) * s; o.c = c; o.s = rand(1.4, 3.2);
    }
  }

  smoke(x, y, c, s, up) {
    const o = this.p[this.pi = (this.pi + 1) % MAX_P];
    o.life = o.max = rand(0.7, 1.5);
    o.x = x; o.y = y;
    o.vx = rand(-14, 14) + up.x; o.vy = rand(-14, 14) + up.y;
    o.c = c; o.s = s;
  }

  ring(x, y, rad, c) {
    const o = this.r[this.ri = (this.ri + 1) % MAX_R];
    o.life = 0.28; o.x = x; o.y = y; o.rad = rad; o.c = c;
  }

  flame(x, y, a, len, arc) {
    const o = this.f[this.fi = (this.fi + 1) % MAX_F];
    o.life = 0.09; o.x = x; o.y = y; o.a = a; o.len = len; o.arc = arc;
  }

  update(dt) {
    for (const o of this.t) if (o.life > 0) o.life -= dt;
    for (const o of this.r) if (o.life > 0) o.life -= dt;
    for (const o of this.f) if (o.life > 0) o.life -= dt;
    for (const o of this.p) {
      if (o.life <= 0) continue;
      o.life -= dt;
      o.x += o.vx * dt; o.y += o.vy * dt;
      o.vx *= 1 - Math.min(1, dt * 3.4);
      o.vy *= 1 - Math.min(1, dt * 3.4);
    }
  }

  draw(g) {
    // flame cones first, underneath everything
    for (const o of this.f) {
      if (o.life <= 0) continue;
      const grd = g.createRadialGradient(o.x, o.y, 4, o.x, o.y, o.len);
      grd.addColorStop(0, 'rgba(255,246,190,0.75)');
      grd.addColorStop(0.35, 'rgba(255,150,44,0.45)');
      grd.addColorStop(1, 'rgba(150,40,10,0)');
      g.fillStyle = grd;
      g.beginPath();
      g.moveTo(o.x, o.y);
      g.arc(o.x, o.y, o.len, o.a - o.arc, o.a + o.arc);
      g.closePath();
      g.fill();
    }

    for (const o of this.p) {
      if (o.life <= 0) continue;
      g.globalAlpha = Math.max(0, o.life / o.max);
      g.fillStyle = o.c;
      g.fillRect(o.x - o.s / 2, o.y - o.s / 2, o.s, o.s);
    }
    g.globalAlpha = 1;

    g.lineCap = 'round';
    for (const o of this.t) {
      if (o.life <= 0) continue;
      g.globalAlpha = Math.min(1, o.life / 0.07);
      g.strokeStyle = o.c; g.lineWidth = o.w;
      g.beginPath(); g.moveTo(o.ax, o.ay); g.lineTo(o.bx, o.by); g.stroke();
    }

    for (const o of this.r) {
      if (o.life <= 0) continue;
      const k = 1 - o.life / 0.28;
      g.globalAlpha = o.life / 0.28;
      g.strokeStyle = o.c; g.lineWidth = 2.5;
      g.beginPath(); g.arc(o.x, o.y, o.rad * (0.55 + k * 0.5), 0, TAU); g.stroke();
    }
    g.globalAlpha = 1;
  }
}
