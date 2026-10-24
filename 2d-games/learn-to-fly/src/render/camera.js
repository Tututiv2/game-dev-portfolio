// §10. Follow with lookahead, critically damped, zooming out with altitude and speed.
//
// Deliberately DOM-free: it takes the viewport as numbers and returns numbers, which is what
// lets check 20 assert that the 600 ft wall actually fits on screen without opening a browser.
// Landmine 13 is that the I.C.B.P. medal asks the player to fly OVER a 600-foot wall, and a
// player who cannot see the top of it cannot judge whether they will clear it.

import { clamp } from '../sim/mathx.js';

export function createCamera(T) {
  const cam = {
    x: 0, y: 0, vx: 0, vy: 0,
    zoom: T.ZOOM_MAX, zoomV: 0,
    shake: 0, shakeX: 0, shakeY: 0,
    free: false,                       // §15 — hold C to detach
    needTopY: null,                    // "this must be visible this frame"
  };

  /**
   * Implicit critically-damped spring. A lerp with a fixed factor is framerate-dependent —
   * soggy at 144 Hz and snappy at 60 — and at this game's speeds you feel it (landmine 8).
   */
  function spring(pos, vel, target, dt, stiffness) {
    const f = 1 + 2 * dt * stiffness;
    const oo = stiffness * stiffness;
    const hoo = dt * oo;
    const hhoo = dt * hoo;
    const inv = 1 / (f + hhoo);
    return {
      pos: (pos * f + vel * dt + target * hhoo) * inv,
      vel: (vel + (target - pos) * hoo) * inv,
    };
  }

  // The viewport, remembered so pixelsPerMetre() can be called without it. Set once a frame.
  let vw = 1, vh = 1;
  const ppm = () => (vh / T.VIEW_HEIGHT_M) * cam.zoom;

  /** Zoom that keeps `metres` of world height inside the viewport. */
  function zoomToFit(metres) {
    return T.VIEW_HEIGHT_M / Math.max(metres, 1);
  }

  return {
    cam,

    /** Ask the camera to keep world-y `topY` on screen this frame (a wall, an iceberg). */
    require(topY) {
      cam.needTopY = cam.needTopY === null ? topY : Math.max(cam.needTopY, topY);
    },

    kick(amount) { cam.shake = Math.min(1, cam.shake + amount); },

    snapTo(p) {
      cam.x = p.x; cam.y = p.y; cam.vx = 0; cam.vy = 0;
      cam.zoom = T.ZOOM_MAX; cam.zoomV = 0;
    },

    setViewport(w, h) { vw = w; vh = h; },

    update(p, dt, viewW, viewH, rng) {
      vw = viewW; vh = viewH;
      // ── zoom ──
      const alt = Math.max(0, p.y - T.SEA_LEVEL_Y);
      const speed = Math.hypot(p.vx, p.vy);
      const byAlt = 1 / (1 + alt / T.CAM_ALT_ZOOM_M);
      const bySpeed = 1 / (1 + speed / T.CAM_SPEED_ZOOM_MS);
      let wantZoom = T.ZOOM_MAX * Math.min(byAlt, bySpeed);

      const needTop = cam.needTopY;
      if (needTop !== null) {
        // Fit from sea level to the top of the tall thing, plus the penguin, plus margin.
        const span = (Math.max(needTop, p.y) - T.SEA_LEVEL_Y) * T.CAM_WALL_MARGIN;
        wantZoom = Math.min(wantZoom, zoomToFit(span));
        cam.needTopY = null;
      }
      wantZoom = clamp(wantZoom, T.ZOOM_MIN, T.ZOOM_MAX);
      const z = spring(cam.zoom, cam.zoomV, wantZoom, dt, T.CAM_STIFFNESS * 0.6);
      cam.zoom = z.pos; cam.zoomV = z.vel;

      // ── follow, with velocity lookahead so at speed you see where you are GOING ──
      const halfW = viewW / ppm() * 0.5;
      const halfH = viewH / ppm() * 0.5;
      let tx = p.x + p.vx * T.LOOKAHEAD_S;
      let ty = p.y + p.vy * T.LOOKAHEAD_S * 0.5;
      // Keep the horizon in frame near the water rather than centring on a penguin at y=0.
      ty = Math.max(ty, T.SEA_LEVEL_Y + halfH * T.CAM_HORIZON_BIAS);

      // Zooming out far enough to FIT the wall is only half of landmine 13: the camera still
      // centres on the penguin, so at 193 m up it frames sky and the base of the wall falls
      // off the bottom. You cannot judge a wall you can only see the top of. Clamp the follow
      // so both ends stay on screen — sea level needs cam.y <= halfH, the wall top needs
      // cam.y >= top - halfH, and the zoom fit above guarantees that interval is non-empty.
      if (needTop !== null) {
        const lo = needTop - halfH;
        const hi = T.SEA_LEVEL_Y + halfH;
        ty = lo <= hi ? clamp(ty, lo, hi) : (lo + hi) * 0.5;
      }

      const sx = spring(cam.x, cam.vx, tx, dt, T.CAM_STIFFNESS);
      const sy = spring(cam.y, cam.vy, ty, dt, T.CAM_STIFFNESS);
      cam.x = sx.pos; cam.vx = sx.vel;
      cam.y = sy.pos; cam.vy = sy.vel;

      // ── shake ──
      cam.shake = Math.max(0, cam.shake - dt / T.SHAKE_DECAY);
      const amp = cam.shake * cam.shake * T.SHAKE_MAX_M;
      const r = rng || Math.random;
      cam.shakeX = (r() * 2 - 1) * amp;
      cam.shakeY = (r() * 2 - 1) * amp;

      return { halfW, halfH };
    },

    /** World metres -> canvas pixels. y is flipped here and NOWHERE else. */
    project(wx, wy, viewW, viewH) {
      const s = ppm();
      return {
        x: viewW * 0.5 + (wx - cam.x - cam.shakeX) * s,
        y: viewH * 0.5 - (wy - cam.y - cam.shakeY) * s,
      };
    },

    unproject(px, py, viewW, viewH) {
      const s = ppm();
      return {
        x: (px - viewW * 0.5) / s + cam.x + cam.shakeX,
        y: (viewH * 0.5 - py) / s + cam.y + cam.shakeY,
      };
    },

    /** The visible world rectangle, for culling and for parallax. */
    bounds(viewW, viewH) {
      const s = ppm();
      const hw = viewW / s * 0.5, hh = viewH / s * 0.5;
      return { left: cam.x - hw, right: cam.x + hw, bottom: cam.y - hh, top: cam.y + hh, hw, hh, scale: s };
    },

    pixelsPerMetre() { return ppm(); },
  };
}
