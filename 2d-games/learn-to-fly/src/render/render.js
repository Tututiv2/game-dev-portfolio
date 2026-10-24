// ONE DRAW ORDER, DECLARED IN ONE ARRAY (§3).
//
// Adding an effect means adding a line to LAYERS, not hunting through the file for the right
// place to slip in a ctx.drawImage. Top of the list is furthest back.

import * as PH from './placeholder.js';
import { STATE } from '../sim/physics.js';
import { SURFACE, waterSpansOf } from '../sim/world.js';
import { damageStage } from '../sim/obstacles.js';

const POSE_FOR = (p) => {
  if (p.state === STATE.RAMP) return 'ramp';
  if (p.state === STATE.TUMBLE) return 'tumble';
  if (p.state === STATE.SUBMERGED) return 'splash';
  if (p.state === STATE.WATER || p.state === STATE.GROUND) return p.speed < 2 ? 'idle' : 'splash';
  if (p.alpha > 0.42) return 'pitchup';
  if (p.alpha < -0.25) return 'pitchdown';
  return 'glide';
};

export function createRenderer(T, { camera, sprites, fx, parallax, hud }) {
  let clock = 0;

  // ── individual layers ────────────────────────────────────────────────────────────────

  function sky(ctx, c) {
    const biome = c.world.segmentAt(c.p.x).biome;
    parallax.sky(ctx, c.w, c.h, biome, c.dayNight);
  }

  function far(ctx, c) { parallax.far(ctx, camera, c.w, c.h, clock); }

  function markers(ctx, c) {
    const b = camera.bounds(c.w, c.h);
    const span = (b.right - b.left) * T.FT_PER_M;
    const step = span > 9000 ? 2000 : span > 3000 ? 1000 : span > 900 ? 250 : 100;
    parallax.markers(ctx, camera, c.w, c.h, step);
  }

  /** Terrain, segment by segment. Nothing here knows which GAME it is drawing (§14 seam 2). */
  function terrain(ctx, c) {
    const b = camera.bounds(c.w, c.h);
    ctx.save();
    applyWorldTransform(ctx, c);
    const w = c.world.segmentWidth;
    for (let x = Math.floor(b.left / w) * w; x < b.right + w; x += w) {
      const seg = c.world.segmentAt(x + w * 0.5);
      for (const [sx, ex] of seg.solidSpans || []) {
        if (ex <= sx) continue;
        if (seg.biome === 'snowfield') PH.snowField(ctx, sx, ex, T.SEA_LEVEL_Y);
        else PH.iceShelf(ctx, sx, ex, T.SEA_LEVEL_Y);
      }
    }
    ctx.restore();
  }

  function obstacles(ctx, c) {
    if (!c.obstacles || !c.obstacles.length) return;
    const b = camera.bounds(c.w, c.h);
    ctx.save();
    applyWorldTransform(ctx, c);
    for (const o of c.obstacles) {
      if (o.destroyed || o.x1 < b.left - 20 || o.x0 > b.right + 20) continue;
      // Landmine 13 — anything this tall must be framed or the player cannot judge it.
      if (o.y1 > b.top - 4 && o.x0 < b.right + 60 && o.x1 > b.left - 60) camera.require(o.y1);
      ctx.save();
      ctx.translate(o.centreX, o.y0);
      PH.obstacleShape(ctx, o.kind, o.widthM, o.heightM, damageStage(o, T));
      ctx.restore();
    }
    ctx.restore();
    // health bars, in screen space so they stay legible when zoomed out
    for (const o of c.obstacles) {
      if (o.destroyed || o.hp >= o.maxHp) continue;
      if (o.x1 < b.left - 20 || o.x0 > b.right + 20) continue;
      const s = camera.project(o.centreX, o.y1 + 2, c.w, c.h);
      const bw = 74;
      ctx.fillStyle = 'rgba(13,17,23,0.65)';
      ctx.fillRect(s.x - bw / 2, s.y - 6, bw, 8);
      ctx.fillStyle = o.hp / o.maxHp > 0.5 ? '#7fe38a' : o.hp / o.maxHp > 0.2 ? PH.PALETTE.gold : '#ff5b4a';
      ctx.fillRect(s.x - bw / 2, s.y - 6, bw * (o.hp / o.maxHp), 8);
      ctx.strokeStyle = 'rgba(255,255,255,0.3)'; ctx.lineWidth = 1;
      ctx.strokeRect(s.x - bw / 2, s.y - 6, bw, 8);
    }
  }

  function ramp(ctx, c) {
    const b = camera.bounds(c.w, c.h);
    const topY = c.loadout.rampHeightM;
    // The structure runs from the ice up to the launch lip at exactly RAMP_ANGLE, so the
    // slope you see is the slope §4.7 launches you at. The slide path is a short section
    // near the top of it (see rampScaffold).
    const run = (topY - T.SEA_LEVEL_Y) / Math.tan(T.RAMP_ANGLE);
    if (b.left > 4) return;
    ctx.save();
    applyWorldTransform(ctx, c);
    PH.rampScaffold(ctx, {
      topX: 0, topY, baseX: -run, baseY: T.SEA_LEVEL_Y, tier: c.rampTier || 0,
    });
    ctx.restore();
  }

  function penguin(ctx, c) {
    const p = c.p;
    const ppm = camera.pixelsPerMetre();
    const s = camera.project(p.x, p.y, c.w, c.h);
    const size = Math.max(T.PENGUIN_LEN_M * ppm, T.PENGUIN_MIN_PX);
    if (s.x < -200 || s.x > c.w + 200 || s.y < -200 || s.y > c.h + 200) return;

    ctx.save();
    ctx.translate(s.x, s.y);
    // scale(size, -SIZE) then rotate(+theta). placeholder.js is authored y-UP, the same
    // convention as the world transform and the sim; flipping here is what makes +theta read
    // as nose-up and keeps the hang glider ABOVE the bird instead of hanging under it.
    ctx.scale(size, -size);
    ctx.rotate(p.theta);

    const pose = POSE_FOR(p);
    const opts = { pose, t: clock, stressed: p.stressed, dummy: c.dummy, hp: p.gliderHp };

    if (p.thrusting) {
      if (!sprites.draw(ctx, 'rocket_flame', clock, [opts])) PH.rocketFlame(ctx, opts);
    }
    if (c.gliderTier > 0 && p.gliderHp > 0 && p.state !== STATE.SUBMERGED) {
      if (!sprites.draw(ctx, `glider_${c.gliderTier}`, clock, [c.gliderTier, opts])) {
        PH.gliderChevron(ctx, c.gliderTier, opts);
      }
    }
    if (c.payloadBomb) {
      ctx.save(); ctx.translate(-0.1, -0.30); ctx.scale(0.55, 0.55);
      PH.bombPayload(ctx, opts);
      ctx.restore();
    }
    if (!sprites.draw(ctx, `penguin_${pose}`, clock, [opts])) PH.penguinCapsule(ctx, opts);
    if (c.showPivot) sprites.drawPivot(ctx, 0.2);
    ctx.restore();
  }

  /** Drawn AFTER the penguin, semi-opaque, which is what makes a dive read as a dive. */
  function water(ctx, c) {
    const b = camera.bounds(c.w, c.h);
    const seaScreen = camera.project(0, T.SEA_LEVEL_Y, c.w, c.h).y;
    if (seaScreen > c.h + 40) return;
    ctx.save();
    applyWorldTransform(ctx, c);
    ctx.globalAlpha = 0.82;
    // Water goes in the gaps between the solid spans, NOT wherever the segment's midpoint
    // happens to be wet — a segment is 200 m and the shelf is 60, so the coarse test paints
    // the ocean straight over the ice the ramp is standing on.
    const step = c.world.segmentWidth;
    for (let x = Math.floor(b.left / step) * step; x < b.right + step; x += step) {
      const seg = c.world.segmentAt(x + step * 0.5);
      for (const [sx, ex] of waterSpansOf(seg)) {
        const a = Math.max(sx, b.left - 2), z = Math.min(ex, b.right + 2);
        if (z > a) PH.waterBand(ctx, a, z, T.SEA_LEVEL_Y, clock, T.MAX_DIVE_DEPTH_M);
      }
    }
    ctx.globalAlpha = 1;
    ctx.restore();
  }

  function particles(ctx, c) { fx.draw(ctx, camera, c.w, c.h); }
  function floaters(ctx, c) { fx.drawTexts(ctx, camera, c.w, c.h); }

  function hudLayer(ctx, c) {
    if (c.hideHud) return;
    hud.draw(ctx, c.w, c.h, c, c.dt);
    if (c.showDebug) hud.debug(ctx, c.w, c.h, c, c.loopStats);
  }

  // The whole draw order, in one place, top of the list furthest back.
  const LAYERS = [
    { name: 'sky', fn: sky },
    { name: 'parallax', fn: far },
    { name: 'markers', fn: markers },
    { name: 'terrain', fn: terrain },
    { name: 'obstacles', fn: obstacles },
    { name: 'ramp', fn: ramp },
    { name: 'penguin', fn: penguin },
    { name: 'water', fn: water },
    { name: 'particles', fn: particles },
    { name: 'floaters', fn: floaters },
    { name: 'hud', fn: hudLayer },
  ];

  /**
   * World metres -> canvas pixels, as a canvas transform, so the placeholder art can be
   * written in metres. y is negated here because the sim is y-up and canvas is y-down.
   */
  function applyWorldTransform(ctx, c) {
    const ppm = camera.pixelsPerMetre();
    const o = camera.project(0, 0, c.w, c.h);
    ctx.translate(o.x, o.y);
    ctx.scale(ppm, -ppm);
  }

  return {
    LAYERS,
    get clock() { return clock; },
    tick(dt) { clock += dt; },
    draw(ctx, ctxInfo) {
      camera.setViewport(ctxInfo.w, ctxInfo.h);
      ctx.save();
      for (const layer of LAYERS) layer.fn(ctx, ctxInfo);
      ctx.restore();
    },
    applyWorldTransform,
  };
}
