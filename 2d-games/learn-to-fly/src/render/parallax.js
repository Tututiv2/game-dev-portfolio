// Sky, distant bergs, clouds, far mountains.
//
// Landmine 9: layer offsets are ROUNDED TO INTEGER DEVICE PIXELS. Sub-pixel parallax shimmers,
// and against flat snow at this game's speeds that shimmer is very visible.

import { PALETTE, cloudPuff } from './placeholder.js';

const SKY = {
  ocean: [['#7ec8f5', 0], ['#bfe6ff', 0.55], ['#eaf7ff', 1]],
  snowfield: [['#8fd0f2', 0], ['#cfeaff', 0.6], ['#f2fbff', 1]],
  night: [['#0d1b33', 0], ['#1d3557', 0.6], ['#3a6ea5', 1]],
};

export function createParallax(T, rng) {
  const rand = rng || Math.random;
  const clouds = [];
  const bergs = [];
  for (let i = 0; i < 26; i++) {
    clouds.push({
      x: rand() * 4000 - 200, y: 40 + rand() * 260,
      s: 12 + rand() * 34, depth: 0.12 + rand() * 0.28, seed: (i * 7919) | 0,
      drift: 0.4 + rand() * 1.1,
    });
  }
  for (let i = 0; i < 40; i++) {
    bergs.push({ x: rand() * 6000 - 300, w: 18 + rand() * 70, h: 6 + rand() * 34, depth: 0.30 + rand() * 0.22 });
  }

  return {
    /** Layer 0 — the sky gradient, drawn in screen space, no parallax. */
    sky(ctx, viewW, viewH, biome, dayNight) {
      const stops = SKY[dayNight === 'night' ? 'night' : (biome === 'snowfield' ? 'snowfield' : 'ocean')];
      const g = ctx.createLinearGradient(0, 0, 0, viewH);
      for (const [c, p] of stops) g.addColorStop(p, c);
      ctx.fillStyle = g;
      ctx.fillRect(0, 0, viewW, viewH);
    },

    /** Layers 1-2 — far bergs then clouds. `depth` 0 = infinitely far, 1 = at the penguin. */
    far(ctx, camera, viewW, viewH, t) {
      const b = camera.bounds(viewW, viewH);
      const seaScreen = camera.project(0, T.SEA_LEVEL_Y, viewW, viewH).y;

      ctx.save();
      for (const berg of bergs) {
        const px = Math.round(viewW * 0.5 + (berg.x - camera.cam.x * berg.depth) * b.scale * berg.depth);
        if (px < -300 || px > viewW + 300) continue;
        const h = berg.h * b.scale * berg.depth;
        const w = berg.w * b.scale * berg.depth;
        const py = Math.round(seaScreen - (camera.cam.y * (1 - berg.depth)) * 0);
        ctx.globalAlpha = 0.35 + berg.depth * 0.5;
        ctx.fillStyle = berg.depth < 0.4 ? '#b7d9f2' : '#9cc7e8';
        ctx.beginPath();
        ctx.moveTo(px - w * 0.5, py);
        ctx.lineTo(px - w * 0.2, py - h);
        ctx.lineTo(px + w * 0.1, py - h * 0.6);
        ctx.lineTo(px + w * 0.5, py);
        ctx.closePath();
        ctx.fill();
      }
      ctx.globalAlpha = 1;

      for (const c of clouds) {
        const px = Math.round(viewW * 0.5 + (c.x + t * c.drift - camera.cam.x * c.depth) * b.scale * c.depth);
        const py = Math.round(viewH * 0.5 - (c.y - camera.cam.y * c.depth) * b.scale * c.depth);
        const sz = c.s * b.scale * c.depth;
        if (px < -sz * 2 || px > viewW + sz * 2 || py < -sz * 2 || py > viewH + sz * 2) continue;
        ctx.save();
        ctx.translate(px, py);
        ctx.scale(sz, -sz);
        ctx.globalAlpha = 0.55 + c.depth * 0.45;
        cloudPuff(ctx, c.seed);
        ctx.restore();
      }
      ctx.globalAlpha = 1;
      ctx.restore();
    },

    /** A distance ruler along the waterline. It is how you read progress at 200 mph. */
    markers(ctx, camera, viewW, viewH, stepFt) {
      const b = camera.bounds(viewW, viewH);
      const stepM = stepFt / T.FT_PER_M;
      const first = Math.floor(b.left / stepM) * stepM;
      ctx.font = '600 12px system-ui, sans-serif';
      ctx.textAlign = 'center';
      for (let x = first; x < b.right; x += stepM) {
        if (x < stepM * 0.5) continue;
        const s = camera.project(x, T.SEA_LEVEL_Y, viewW, viewH);
        if (s.y < -20 || s.y > viewH + 20) continue;
        ctx.globalAlpha = 0.30;
        ctx.strokeStyle = PALETTE.ink;
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.moveTo(Math.round(s.x), Math.round(s.y));
        ctx.lineTo(Math.round(s.x), Math.round(s.y) - 14);
        ctx.stroke();
        ctx.globalAlpha = 0.55;
        ctx.fillStyle = PALETTE.ink;
        ctx.fillText(`${Math.round(x * T.FT_PER_M)}`, Math.round(s.x), Math.round(s.y) - 20);
      }
      ctx.globalAlpha = 1;
    },
  };
}
