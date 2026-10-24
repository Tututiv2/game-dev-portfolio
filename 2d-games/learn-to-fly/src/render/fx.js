// Particles, snow, spray, debris, explosions, screen shake and floating text.
//
// One flat pool, one integrator, one draw call per kind. Everything is seeded off a passed-in
// RNG so a replay (§15) produces the same spray twice.

import { PALETTE } from './placeholder.js';

const KIND = { SPRAY: 0, SNOW: 1, DEBRIS: 2, SMOKE: 3, SPARK: 4, ICE: 5 };
export { KIND };

export function createFx(T, rng) {
  const P = [];
  const texts = [];
  const rand = rng || Math.random;
  const MAX = 900;

  function spawn(kind, x, y, vx, vy, life, size, tint) {
    if (P.length >= MAX) P.shift();
    P.push({ kind, x, y, vx, vy, life, maxLife: life, size, tint, rot: rand() * 6.28, spin: (rand() - 0.5) * 8 });
  }

  const api = {
    particles: P,
    texts,

    clear() { P.length = 0; texts.length = 0; },

    /** Water skip — the spray IS the reward, so it scales with the bounce number (§4.8). */
    spray(x, y, speed, n = 1) {
      const count = Math.min(6 + Math.round(speed * 0.5) + n * 2, 44);
      for (let i = 0; i < count; i++) {
        const a = -0.15 + rand() * 1.5;
        const v = speed * (0.16 + rand() * 0.45);
        spawn(KIND.SPRAY, x + (rand() - 0.5), y, Math.cos(a) * v * 0.7 + speed * 0.14, Math.sin(a) * v,
          0.35 + rand() * 0.55, 0.10 + rand() * 0.18);
      }
    },

    splash(x, y, speed) {
      for (let i = 0; i < 34; i++) {
        const a = Math.PI * (0.15 + rand() * 0.7);
        const v = speed * (0.2 + rand() * 0.6);
        spawn(KIND.SPRAY, x + (rand() - 0.5) * 1.2, y, Math.cos(a) * v, Math.abs(Math.sin(a)) * v,
          0.5 + rand() * 0.7, 0.14 + rand() * 0.24);
      }
    },

    /** Rocket exhaust — smoke behind, sparks in it. */
    exhaust(x, y, theta, power) {
      for (let i = 0; i < 2; i++) {
        const a = theta + Math.PI + (rand() - 0.5) * 0.5;
        const v = 6 + rand() * 10;
        spawn(KIND.SMOKE, x, y, Math.cos(a) * v, Math.sin(a) * v, 0.5 + rand() * 0.6, 0.2 + rand() * 0.35);
      }
      if (rand() < 0.6) {
        const a = theta + Math.PI + (rand() - 0.5) * 0.35;
        spawn(KIND.SPARK, x, y, Math.cos(a) * 22, Math.sin(a) * 22, 0.18 + rand() * 0.2, 0.07);
      }
    },

    debris(x, y, count, tint) {
      for (let i = 0; i < count; i++) {
        const a = rand() * 6.283;
        const v = 4 + rand() * 20;
        spawn(KIND.DEBRIS, x, y, Math.cos(a) * v, Math.sin(a) * v, 1.2 + rand() * 1.6, 0.15 + rand() * 0.3, tint);
      }
    },

    iceBurst(x, y, count) {
      for (let i = 0; i < count; i++) {
        const a = rand() * 6.283;
        const v = 6 + rand() * 26;
        spawn(KIND.ICE, x, y, Math.cos(a) * v, Math.sin(a) * v, 1.4 + rand() * 2.0, 0.2 + rand() * 0.5);
      }
    },

    explosion(x, y, scale = 1) {
      for (let i = 0; i < 40 * scale; i++) {
        const a = rand() * 6.283;
        const v = (8 + rand() * 34) * scale;
        spawn(KIND.SMOKE, x, y, Math.cos(a) * v, Math.sin(a) * v, 0.9 + rand() * 1.4, (0.4 + rand() * 0.9) * scale);
      }
      for (let i = 0; i < 24 * scale; i++) {
        const a = rand() * 6.283;
        const v = (14 + rand() * 46) * scale;
        spawn(KIND.SPARK, x, y, Math.cos(a) * v, Math.sin(a) * v, 0.3 + rand() * 0.5, 0.1 * scale);
      }
    },

    /** Ambient snow, seeded across the visible rectangle. */
    snowTick(dt, bounds, density) {
      const want = Math.round(density * (bounds.right - bounds.left) * 0.02);
      let have = 0;
      for (const p of P) if (p.kind === KIND.SNOW) have++;
      for (let i = have; i < want && i < have + 4; i++) {
        spawn(KIND.SNOW,
          bounds.left + rand() * (bounds.right - bounds.left),
          bounds.top + rand() * 4,
          -1 - rand() * 2, -1.5 - rand() * 2.5,
          14, 0.06 + rand() * 0.10);
      }
    },

    float(text, x, y, tint) { texts.push({ text, x, y, life: 1.3, maxLife: 1.3, tint }); },

    update(dt, bounds) {
      for (let i = P.length - 1; i >= 0; i--) {
        const p = P[i];
        p.life -= dt;
        if (p.life <= 0) { P.splice(i, 1); continue; }
        if (p.kind === KIND.SNOW) {
          p.x += p.vx * dt;
          p.y += p.vy * dt;
          if (bounds && (p.y < bounds.bottom - 4 || p.x < bounds.left - 10 || p.x > bounds.right + 10)) {
            P.splice(i, 1);
          }
          continue;
        }
        p.vy -= T.G * (p.kind === KIND.SMOKE ? -0.18 : p.kind === KIND.SPARK ? 0.4 : 1) * dt;
        p.vx *= 1 - (p.kind === KIND.SMOKE ? 1.6 : 0.5) * dt;
        p.x += p.vx * dt;
        p.y += p.vy * dt;
        p.rot += p.spin * dt;
        if (p.kind === KIND.SPRAY && p.y < T.SEA_LEVEL_Y) P.splice(i, 1);
      }
      for (let i = texts.length - 1; i >= 0; i--) {
        const tx = texts[i];
        tx.life -= dt;
        tx.y += dt * 3.2;
        if (tx.life <= 0) texts.splice(i, 1);
      }
    },

    draw(ctx, camera, viewW, viewH) {
      for (const p of P) {
        const s = camera.project(p.x, p.y, viewW, viewH);
        const r = p.size * camera.pixelsPerMetre();
        if (r < 0.35 || s.x < -40 || s.x > viewW + 40 || s.y < -40 || s.y > viewH + 40) continue;
        const a = Math.min(1, p.life / p.maxLife);
        ctx.globalAlpha = p.kind === KIND.SMOKE ? a * 0.5 : a;
        if (p.kind === KIND.DEBRIS || p.kind === KIND.ICE) {
          ctx.save();
          ctx.translate(s.x, s.y);
          ctx.rotate(p.rot);
          ctx.fillStyle = p.tint || (p.kind === KIND.ICE ? PALETTE.ice : PALETTE.woodDark);
          ctx.fillRect(-r, -r * 0.6, r * 2, r * 1.2);
          ctx.strokeStyle = PALETTE.ink;
          ctx.lineWidth = Math.max(0.6, r * 0.22);
          ctx.strokeRect(-r, -r * 0.6, r * 2, r * 1.2);
          ctx.restore();
        } else {
          ctx.beginPath();
          ctx.arc(s.x, s.y, r, 0, 6.283);
          ctx.fillStyle = p.kind === KIND.SPARK ? PALETTE.flame
            : p.kind === KIND.SMOKE ? '#c9d4de'
              : p.kind === KIND.SNOW ? '#ffffff' : PALETTE.foam;
          ctx.fill();
        }
      }
      ctx.globalAlpha = 1;
    },

    drawTexts(ctx, camera, viewW, viewH) {
      for (const tx of texts) {
        const s = camera.project(tx.x, tx.y, viewW, viewH);
        const a = Math.min(1, tx.life / tx.maxLife);
        ctx.globalAlpha = a;
        ctx.font = '700 22px system-ui, sans-serif';
        ctx.textAlign = 'center';
        ctx.lineWidth = 5;
        ctx.strokeStyle = PALETTE.ink;
        ctx.strokeText(tx.text, s.x, s.y);
        ctx.fillStyle = tx.tint || '#fff';
        ctx.fillText(tx.text, s.x, s.y);
      }
      ctx.globalAlpha = 1;
    },
  };
  return api;
}
