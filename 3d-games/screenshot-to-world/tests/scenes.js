/**
 * Synthetic test photographs.
 *
 * Each scene is drawn with a KNOWN horizon row, so the analysis passes can be
 * checked against ground truth instead of eyeballed. They also cover the cases
 * that break naive implementations: a night shot (dark everywhere), a photo
 * with no sky at all, and a close-up with no ground plane.
 */

export const SCENES = [
  { id: 'landscape', label: 'Landscape + trees', truthHorizon: 0.52, draw: drawLandscape },
  { id: 'city', label: 'City skyline', truthHorizon: 0.62, draw: drawCity },
  { id: 'sunset', label: 'Sunset beach', truthHorizon: 0.46, draw: drawSunset },
  { id: 'night', label: 'Night street', truthHorizon: 0.58, draw: drawNight },
  { id: 'interior', label: 'Interior room (no sky)', truthHorizon: 0.66, draw: drawInterior },
  { id: 'closeup', label: 'Close-up subject (no ground)', truthHorizon: null, draw: drawCloseup },
];

const W = 960;
const H = 640;

export function renderScene(scene, w = W, h = H) {
  const c = document.createElement('canvas');
  c.width = w;
  c.height = h;
  scene.draw(c.getContext('2d'), w, h);
  return c;
}

function skyGradient(ctx, w, hy, top, bottom) {
  const g = ctx.createLinearGradient(0, 0, 0, hy);
  g.addColorStop(0, top);
  g.addColorStop(1, bottom);
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, w, hy);
}

/** Deterministic PRNG so scenes are identical run to run. */
function rng(seed) {
  let s = seed >>> 0;
  return () => {
    s = (Math.imul(s, 1664525) + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

function noiseFill(ctx, x, y, w, h, base, amount, seed) {
  const r = rng(seed);
  const img = ctx.getImageData(x, y, w, h);
  for (let i = 0; i < img.data.length; i += 4) {
    const n = (r() - 0.5) * amount;
    img.data[i] = clamp255(base[0] + n);
    img.data[i + 1] = clamp255(base[1] + n);
    img.data[i + 2] = clamp255(base[2] + n);
    img.data[i + 3] = 255;
  }
  ctx.putImageData(img, x, y);
}

function clamp255(v) {
  return v < 0 ? 0 : v > 255 ? 255 : v;
}

function drawLandscape(ctx, w, h) {
  const hy = Math.round(h * 0.52);
  skyGradient(ctx, w, hy, '#4a86c8', '#b9d4ea');

  // Ground: textured grass, gets smoother toward the horizon.
  noiseFill(ctx, 0, hy, w, h - hy, [86, 112, 58], 46, 7);
  const g = ctx.createLinearGradient(0, hy, 0, h);
  g.addColorStop(0, 'rgba(150,170,140,0.55)');
  g.addColorStop(1, 'rgba(40,60,30,0.15)');
  ctx.fillStyle = g;
  ctx.fillRect(0, hy, w, h - hy);

  // Trees breaking the skyline at varied heights.
  const r = rng(11);
  const trees = [
    [140, 95], [230, 60], [420, 130], [520, 78], [700, 110], [830, 55],
  ];
  for (const [x, tall] of trees) {
    ctx.fillStyle = '#3c2b1c';
    ctx.fillRect(x - 5, hy - tall * 0.35, 10, tall * 0.35);
    ctx.fillStyle = '#2f5228';
    ctx.beginPath();
    ctx.ellipse(x, hy - tall * 0.62, 26 + r() * 12, tall * 0.4, 0, 0, Math.PI * 2);
    ctx.fill();
  }
  // Distant hill ridge
  ctx.fillStyle = '#6d84a0';
  ctx.beginPath();
  ctx.moveTo(0, hy);
  ctx.lineTo(0, hy - 26);
  ctx.quadraticCurveTo(w * 0.3, hy - 58, w * 0.55, hy - 20);
  ctx.quadraticCurveTo(w * 0.8, hy - 42, w, hy - 30);
  ctx.lineTo(w, hy);
  ctx.closePath();
  ctx.fill();
}

function drawCity(ctx, w, h) {
  const hy = Math.round(h * 0.62);
  skyGradient(ctx, w, hy, '#7fa6cc', '#d8e2ea');

  const r = rng(23);
  let x = 0;
  while (x < w) {
    const bw = 40 + r() * 70;
    const bh = 60 + r() * 200;
    const shade = 60 + Math.floor(r() * 50);
    ctx.fillStyle = `rgb(${shade},${shade + 6},${shade + 14})`;
    ctx.fillRect(x, hy - bh, bw, bh);
    // windows
    ctx.fillStyle = 'rgba(255,235,180,0.55)';
    for (let wy = hy - bh + 8; wy < hy - 8; wy += 16) {
      for (let wx = x + 6; wx < x + bw - 8; wx += 14) {
        if (r() > 0.45) ctx.fillRect(wx, wy, 6, 8);
      }
    }
    x += bw + 4 + r() * 10;
  }
  noiseFill(ctx, 0, hy, w, h - hy, [72, 72, 78], 30, 5);
}

function drawSunset(ctx, w, h) {
  const hy = Math.round(h * 0.46);
  const g = ctx.createLinearGradient(0, 0, 0, hy);
  g.addColorStop(0, '#2a3a72');
  g.addColorStop(0.55, '#d2643c');
  g.addColorStop(1, '#f6c27a');
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, w, hy);

  ctx.fillStyle = 'rgba(255,238,190,0.95)';
  ctx.beginPath();
  ctx.arc(w * 0.66, hy - 34, 30, 0, Math.PI * 2);
  ctx.fill();

  // Sea, then wet sand
  const s = ctx.createLinearGradient(0, hy, 0, h);
  s.addColorStop(0, '#8a5a52');
  s.addColorStop(0.45, '#54423f');
  s.addColorStop(1, '#c2a184');
  ctx.fillStyle = s;
  ctx.fillRect(0, hy, w, h - hy);
}

function drawNight(ctx, w, h) {
  const hy = Math.round(h * 0.58);
  skyGradient(ctx, w, hy, '#05070f', '#161d33');

  const r = rng(41);
  for (let i = 0; i < 120; i++) {
    ctx.fillStyle = `rgba(255,255,255,${0.3 + r() * 0.6})`;
    ctx.fillRect(r() * w, r() * hy * 0.85, 1.5, 1.5);
  }
  // Buildings silhouetted
  let x = 0;
  while (x < w) {
    const bw = 50 + r() * 80;
    const bh = 40 + r() * 150;
    ctx.fillStyle = '#0a0c14';
    ctx.fillRect(x, hy - bh, bw, bh);
    ctx.fillStyle = 'rgba(255,196,92,0.8)';
    for (let wy = hy - bh + 10; wy < hy - 10; wy += 18) {
      for (let wx = x + 8; wx < x + bw - 10; wx += 16) {
        if (r() > 0.6) ctx.fillRect(wx, wy, 5, 7);
      }
    }
    x += bw + 6;
  }
  noiseFill(ctx, 0, hy, w, h - hy, [26, 26, 32], 22, 3);
  // Street lamp pools
  for (let i = 0; i < 4; i++) {
    const lx = (i + 0.5) * (w / 4);
    const rg = ctx.createRadialGradient(lx, h * 0.85, 4, lx, h * 0.85, 110);
    rg.addColorStop(0, 'rgba(255,200,110,0.5)');
    rg.addColorStop(1, 'rgba(255,200,110,0)');
    ctx.fillStyle = rg;
    ctx.fillRect(lx - 120, h * 0.6, 240, h * 0.4);
  }
}

function drawInterior(ctx, w, h) {
  const hy = Math.round(h * 0.66);
  // Ceiling + back wall: no sky anywhere.
  ctx.fillStyle = '#c9bfae';
  ctx.fillRect(0, 0, w, hy);
  ctx.fillStyle = '#b3a795';
  ctx.fillRect(0, 0, w, Math.round(h * 0.14));

  // Perspective floorboards converging on a vanishing point.
  noiseFill(ctx, 0, hy, w, h - hy, [128, 96, 62], 26, 9);
  ctx.strokeStyle = 'rgba(60,40,24,0.5)';
  ctx.lineWidth = 2;
  const vpx = w * 0.5;
  for (let i = -8; i <= 8; i++) {
    ctx.beginPath();
    ctx.moveTo(vpx + i * 22, hy);
    ctx.lineTo(vpx + i * 150, h);
    ctx.stroke();
  }
  // Window with bright daylight (a classic false-sky trap)
  ctx.fillStyle = '#e8eef2';
  ctx.fillRect(w * 0.66, h * 0.2, w * 0.2, h * 0.28);
  ctx.strokeStyle = '#6a5c48';
  ctx.lineWidth = 6;
  ctx.strokeRect(w * 0.66, h * 0.2, w * 0.2, h * 0.28);
}

function drawCloseup(ctx, w, h) {
  // Soft out-of-focus backdrop
  const g = ctx.createLinearGradient(0, 0, w, h);
  g.addColorStop(0, '#5c6b7a');
  g.addColorStop(1, '#39424d');
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, w, h);

  // A dominant, detailed subject filling the centre; no ground, no horizon.
  const cx = w * 0.5;
  const cy = h * 0.54;
  ctx.fillStyle = '#d9a98c';
  ctx.beginPath();
  ctx.ellipse(cx, cy, w * 0.19, h * 0.31, 0, 0, Math.PI * 2);
  ctx.fill();

  const r = rng(77);
  // High-frequency detail so centre texture energy clearly beats the edges.
  for (let i = 0; i < 2600; i++) {
    const a = r() * Math.PI * 2;
    const rad = Math.sqrt(r()) * 0.95;
    const x = cx + Math.cos(a) * rad * w * 0.19;
    const y = cy + Math.sin(a) * rad * h * 0.31;
    ctx.fillStyle = `rgba(${120 + r() * 110},${70 + r() * 70},${60 + r() * 60},0.5)`;
    ctx.fillRect(x, y, 2, 2);
  }
  ctx.fillStyle = '#2b2118';
  ctx.beginPath();
  ctx.ellipse(cx - w * 0.06, cy - h * 0.07, 12, 7, 0, 0, Math.PI * 2);
  ctx.ellipse(cx + w * 0.06, cy - h * 0.07, 12, 7, 0, 0, Math.PI * 2);
  ctx.fill();
}
