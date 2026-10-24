// Every sprite in the game is drawn here, in code, at boot.
// No asset files, no loading, and a palette swap is a one-line edit.
import { TAU } from './util.js';
import { COL } from './config.js';

function mk(w, h) {
  const c = document.createElement('canvas');
  c.width = Math.ceil(w);
  c.height = Math.ceil(h);
  return c;
}

/* ------------------------------------------------------------------ *
 *  THE CHORUS — drawn facing +X, centred on the origin.
 *
 *  Black bodies with a COLD light in them. The Goldsea is warm and the
 *  things that answer the song are not, which is what makes a 12px sprite
 *  legible on a glowing amber field and on a dark reaped scar alike.
 * ------------------------------------------------------------------ */

function husk(g, f) {
  const w = f ? 1 : -1;
  g.lineCap = 'round';

  // legs — thin, jointed, crystalline
  g.strokeStyle = '#0a0810';
  g.lineWidth = 1.5;
  for (let i = -1; i <= 1; i++) {
    const bx = i * 2.6;
    g.beginPath();
    g.moveTo(bx, -1.6); g.lineTo(bx - 1.6, -3.6); g.lineTo(bx - 0.6, -5.6 + w * 0.9);
    g.moveTo(bx,  1.6); g.lineTo(bx - 1.6,  3.6); g.lineTo(bx - 0.6,  5.6 - w * 0.9);
    g.stroke();
  }
  g.lineWidth = 0.9;
  g.beginPath();
  g.moveTo(3.8, -1.1); g.lineTo(7.4, -3.2 + w * 0.5);
  g.moveTo(3.8,  1.1); g.lineTo(7.4,  3.2 - w * 0.5);
  g.stroke();

  // angular shell, not an oval — everything out here grew as crystal
  g.fillStyle = '#15121f';
  g.beginPath();
  g.moveTo(-6.6, 0); g.lineTo(-3.4, -3.4); g.lineTo(2.2, -3.1);
  g.lineTo(4.4, 0); g.lineTo(2.2, 3.1); g.lineTo(-3.4, 3.4);
  g.closePath(); g.fill();

  g.fillStyle = '#221d33';
  g.beginPath();
  g.moveTo(3.4, 0); g.lineTo(5.2, -2.2); g.lineTo(7.0, 0); g.lineTo(5.2, 2.2);
  g.closePath(); g.fill();

  // the seam of cold light down the back
  g.strokeStyle = 'rgba(127,232,220,0.85)';
  g.lineWidth = 1.1;
  g.beginPath(); g.moveTo(-5.4, 0); g.lineTo(2.6, 0); g.stroke();
  g.fillStyle = 'rgba(180,255,246,0.9)';
  g.beginPath(); g.arc(5.4, 0, 0.9, 0, TAU); g.fill();

  // cold rim so it also reads against dark reaped ground
  g.strokeStyle = 'rgba(127,232,220,0.32)';
  g.lineWidth = 0.8;
  g.beginPath();
  g.moveTo(-6.6, 0); g.lineTo(-3.4, -3.4); g.lineTo(2.2, -3.1); g.lineTo(4.4, 0);
  g.stroke();
}

function reaver(g, f) {
  const w = f ? 1 : -1;
  g.lineCap = 'round';

  g.strokeStyle = '#080610';
  g.lineWidth = 2.5;
  for (let i = -1; i <= 1; i++) {
    const bx = i * 3.6 - 1;
    g.beginPath();
    g.moveTo(bx, -3); g.lineTo(bx - 3, -6.2); g.lineTo(bx - 1.4, -9.2 + w * 1.2);
    g.moveTo(bx,  3); g.lineTo(bx - 3,  6.2); g.lineTo(bx - 1.4,  9.2 - w * 1.2);
    g.stroke();
  }
  // glowing joints
  g.fillStyle = 'rgba(127,232,220,0.5)';
  for (let i = -1; i <= 1; i++) {
    const bx = i * 3.6 - 1;
    g.beginPath(); g.arc(bx - 3, -6.2, 1.1, 0, TAU); g.fill();
    g.beginPath(); g.arc(bx - 3,  6.2, 1.1, 0, TAU); g.fill();
  }

  // slabbed carapace
  g.fillStyle = '#100d1a';
  g.beginPath();
  g.moveTo(-10, 0); g.lineTo(-6.4, -5.4); g.lineTo(1.6, -6.2);
  g.lineTo(6.2, -3.2); g.lineTo(6.2, 3.2); g.lineTo(1.6, 6.2); g.lineTo(-6.4, 5.4);
  g.closePath(); g.fill();

  g.strokeStyle = 'rgba(127,232,220,0.22)';
  g.lineWidth = 1.1;
  for (let i = 0; i < 3; i++) {
    const px = -7 + i * 3.8;
    g.beginPath(); g.moveTo(px, -5.2 + i * 0.5); g.lineTo(px + 1.6, 5.2 - i * 0.5); g.stroke();
  }

  // bladed head
  g.fillStyle = '#241d38';
  g.beginPath();
  g.moveTo(5.4, -4); g.lineTo(11.6, -1.6); g.lineTo(11.6, 1.6); g.lineTo(5.4, 4);
  g.closePath(); g.fill();
  g.fillStyle = 'rgba(190,255,248,0.9)';
  g.beginPath(); g.arc(9.2, -1.6, 1.1, 0, TAU); g.fill();
  g.beginPath(); g.arc(9.2,  1.6, 1.1, 0, TAU); g.fill();

  g.strokeStyle = 'rgba(127,232,220,0.4)';
  g.lineWidth = 1.2;
  g.beginPath();
  g.moveTo(-10, 0); g.lineTo(-6.4, -5.4); g.lineTo(1.6, -6.2); g.lineTo(6.2, -3.2);
  g.stroke();
}

function cantor(g, f) {
  const w = f ? 1 : -1;
  g.lineCap = 'round';

  g.strokeStyle = '#070510';
  g.lineWidth = 3.4;
  for (let i = -1; i <= 1; i++) {
    const bx = i * 5.5 - 2;
    g.beginPath();
    g.moveTo(bx, -6); g.lineTo(bx - 5, -14 + w * 2.2); g.lineTo(bx - 2, -20 + w * 1.4);
    g.moveTo(bx,  6); g.lineTo(bx - 5,  14 - w * 2.2); g.lineTo(bx - 2,  20 - w * 1.4);
    g.stroke();
  }

  // The resonating bladder. It is the brightest thing in the game on purpose —
  // a Cantor is a landmark you can see coming across the field.
  const sac = g.createRadialGradient(-8, 0, 1, -8, 0, 16);
  sac.addColorStop(0, '#e8fffb');
  sac.addColorStop(0.4, '#7fe8dc');
  sac.addColorStop(0.78, '#2c7a75');
  sac.addColorStop(1, '#10222a');
  g.fillStyle = sac;
  g.beginPath(); g.ellipse(-8, 0, 15, 12, 0, 0, TAU); g.fill();

  // ribbing across it, like a throat
  g.strokeStyle = 'rgba(8,20,24,0.45)';
  g.lineWidth = 1.4;
  for (let i = 0; i < 4; i++) {
    g.beginPath(); g.ellipse(-16 + i * 5, 0, 1.6, 10.5 - i * 1.4, 0, -1.2, 1.2); g.stroke();
  }

  g.fillStyle = '#0e0b18';
  g.beginPath(); g.ellipse(8, 0, 10, 8.5, 0, 0, TAU); g.fill();
  g.fillStyle = '#1d1830';
  g.beginPath();
  g.moveTo(11, -5.4); g.lineTo(19.5, -2.2); g.lineTo(19.5, 2.2); g.lineTo(11, 5.4);
  g.closePath(); g.fill();

  // the open mouth it sings out of
  g.fillStyle = 'rgba(200,255,250,0.9)';
  g.beginPath(); g.ellipse(17.5, 0, 1.8, 2.6, 0, 0, TAU); g.fill();

  g.strokeStyle = 'rgba(127,232,220,0.45)';
  g.lineWidth = 1.6;
  g.beginPath(); g.ellipse(8, -1.2, 9.4, 7.6, 0, Math.PI * 1.05, Math.PI * 1.95); g.stroke();
}

const ENEMY_ART = [
  { draw: husk,   size: 24 },
  { draw: reaver, size: 34 },
  { draw: cantor, size: 58 },
];

const ANGLES = 16, FRAMES = 2;

/**
 * Bakes each enemy into an ANGLES x FRAMES sheet so the hot loop can draw a
 * thousand of them with a plain drawImage — no per-unit save/rotate/restore,
 * which is the difference between 60fps and 25fps at this count.
 */
export function bakeEnemies() {
  return ENEMY_ART.map((d) => {
    const c = mk(d.size * ANGLES, d.size * FRAMES);
    const g = c.getContext('2d');
    for (let f = 0; f < FRAMES; f++) {
      for (let a = 0; a < ANGLES; a++) {
        g.save();
        g.translate(a * d.size + d.size / 2, f * d.size + d.size / 2);
        g.rotate((a / ANGLES) * TAU);
        d.draw(g, f);
        g.restore();
      }
    }
    return { canvas: c, size: d.size, angles: ANGLES, frames: FRAMES };
  });
}

export function angleIndex(ang, angles) {
  let i = Math.round((ang / TAU) * angles) % angles;
  if (i < 0) i += angles;
  return i;
}

/* ------------------------------------------------------------------ *
 *  THE GLEANER — half cathedral, half thresher.
 * ------------------------------------------------------------------ */

export const HV_ART = { w: 280, h: 240, ox: 140, oy: 120 };

function panel(g, x0, y0, x1, y1, fill, edge) {
  g.fillStyle = fill;
  g.fillRect(x0, y0, x1 - x0, y1 - y0);
  g.strokeStyle = edge;
  g.lineWidth = 1.6;
  g.strokeRect(x0 + 0.5, y0 + 0.5, x1 - x0 - 1, y1 - y0 - 1);
}

/** Bronze studs along a plate edge. */
function studs(g, x0, y0, x1, y1, step) {
  g.fillStyle = COL.bronze;
  for (let x = x0 + step / 2; x < x1; x += step) {
    g.beginPath(); g.arc(x, y0 + 4.5, 1.5, 0, TAU); g.fill();
    g.beginPath(); g.arc(x, y1 - 4.5, 1.5, 0, TAU); g.fill();
  }
}

/** Static parts of the Gleaner, baked once. Forward is +X. */
export function bakeHarvester() {
  const c = mk(HV_ART.w, HV_ART.h);
  const g = c.getContext('2d');
  g.translate(HV_ART.ox, HV_ART.oy);

  // ---- ground shadow ----
  g.fillStyle = 'rgba(10,6,20,0.34)';
  g.beginPath(); g.ellipse(-4, 8, 100, 64, 0, 0, TAU); g.fill();

  // ---- rear axle + wheels ----
  g.fillStyle = COL.ironDark;
  g.fillRect(-72, -46, 22, 92);
  for (const sy of [-1, 1]) {
    g.save();
    g.translate(-61, sy * 46);
    g.fillStyle = '#191622';
    g.beginPath(); g.roundRect(-17, -11, 34, 22, 5); g.fill();
    g.strokeStyle = '#332d42'; g.lineWidth = 2;
    for (let i = -2; i <= 2; i++) {
      g.beginPath(); g.moveTo(i * 6, -11); g.lineTo(i * 6 + 3, 11); g.stroke();
    }
    g.restore();
  }

  // ---- front drive wheels ----
  for (const sy of [-1, 1]) {
    g.save();
    g.translate(40, sy * 54);
    g.fillStyle = '#151220';
    g.beginPath(); g.roundRect(-26, -15, 52, 30, 7); g.fill();
    g.strokeStyle = '#3a3350'; g.lineWidth = 2.4;
    for (let i = -3; i <= 3; i++) {
      g.beginPath(); g.moveTo(i * 7, -15); g.lineTo(i * 7 + 5, 15); g.stroke();
    }
    g.fillStyle = COL.bronze;
    g.beginPath(); g.ellipse(0, 0, 7, 7, 0, 0, TAU); g.fill();
    g.fillStyle = 'rgba(255,217,138,0.5)';
    g.beginPath(); g.ellipse(0, 0, 3, 3, 0, 0, TAU); g.fill();
    g.restore();
  }

  // ---- hull: verdigris plate over an iron frame ----
  panel(g, -84, -48, 72, 48, COL.hull, COL.hullDark);

  // Vessel recess — reaped light is stored here and filled in at draw time.
  // Kept deliberately smaller than the deck: at full it used to cover the whole
  // hull and the machine read as one blank slab.
  panel(g, -70, -31, -6, 31, '#16262e', '#0b161b');
  g.strokeStyle = COL.bronze; g.lineWidth = 1.6;
  g.strokeRect(-70.5, -31.5, 64, 62);

  studs(g, -84, -48, 72, 48, 17);

  // graven lines — this thing was made by people who decorated their machines
  g.strokeStyle = 'rgba(255,217,138,0.18)';
  g.lineWidth = 1;
  for (let i = 0; i < 3; i++) {
    const y = -30 + i * 30;
    g.beginPath(); g.moveTo(10, y); g.lineTo(66, y); g.stroke();
  }

  // shoulder light — sells the top-down curve of the hull
  const sh = g.createLinearGradient(0, -48, 0, 48);
  sh.addColorStop(0, 'rgba(210,255,240,0.20)');
  sh.addColorStop(0.32, 'rgba(210,255,240,0)');
  sh.addColorStop(0.72, 'rgba(0,0,0,0)');
  sh.addColorStop(1, 'rgba(0,0,0,0.32)');
  g.fillStyle = sh;
  g.fillRect(-84, -48, 156, 96);

  // ---- the shrine: a lantern of white stone where a cab would be ----
  panel(g, 16, -30, 62, 30, COL.stone, '#8a8272');
  g.fillStyle = '#6b6455';
  g.fillRect(16, -30, 46, 3.5);
  g.fillRect(16, 26.5, 46, 3.5);

  const lamp = g.createRadialGradient(40, 0, 2, 40, 0, 22);
  lamp.addColorStop(0, '#fff6dd');
  lamp.addColorStop(0.45, COL.glass);
  lamp.addColorStop(1, 'rgba(255,180,80,0.15)');
  g.fillStyle = lamp;
  g.beginPath(); g.roundRect(24, -22, 32, 44, 5); g.fill();
  // leading
  g.strokeStyle = '#7a6a4a'; g.lineWidth = 1.6;
  g.beginPath(); g.roundRect(24, -22, 32, 44, 5); g.stroke();
  g.beginPath(); g.moveTo(40, -22); g.lineTo(40, 22);
  g.moveTo(24, 0); g.lineTo(56, 0); g.stroke();

  // ---- resonator horn: this is what sings ----
  g.fillStyle = COL.bronze;
  g.beginPath(); g.ellipse(2, -39, 9.5, 9.5, 0, 0, TAU); g.fill();
  g.fillStyle = '#0d0a14';
  g.beginPath(); g.ellipse(2, -39, 5.6, 5.6, 0, 0, TAU); g.fill();
  g.strokeStyle = 'rgba(255,217,138,0.55)'; g.lineWidth = 1.2;
  g.beginPath(); g.arc(2, -39, 7.4, 0, TAU); g.stroke();

  // ---- throat feeding the reaping arc ----
  g.fillStyle = COL.hullDark;
  g.beginPath();
  g.moveTo(72, -34); g.lineTo(92, -20); g.lineTo(92, 20); g.lineTo(72, 34);
  g.closePath(); g.fill();

  // ---- the reaping arc ----
  panel(g, 92, -96, 110, 96, COL.hull, COL.hullDark);
  g.fillStyle = COL.ironDark;
  g.fillRect(74, -96, 20, 192);

  // glass sickles along the leading edge
  for (let y = -94; y < 94; y += 7) {
    g.fillStyle = y % 14 === 0 ? '#cfe9e4' : '#9dc6c0';
    g.beginPath();
    g.moveTo(110, y); g.lineTo(120, y + 3.5); g.lineTo(110, y + 7);
    g.closePath(); g.fill();
  }
  g.strokeStyle = 'rgba(255,217,138,0.4)'; g.lineWidth = 1.4;
  g.beginPath(); g.moveTo(96, -96); g.lineTo(96, 96); g.stroke();

  // arc ends flaring outward
  for (const sy of [-1, 1]) {
    g.fillStyle = COL.hullDark;
    g.beginPath();
    g.moveTo(110, sy * 96); g.lineTo(130, sy * 86); g.lineTo(112, sy * 80);
    g.closePath(); g.fill();
    g.fillStyle = COL.bronze;
    g.beginPath(); g.arc(114, sy * 88, 3.2, 0, TAU); g.fill();
  }

  return c;
}

/** The blade ring is drawn live — its speed is the clearest read on the choir. */
export function drawReel(g, phase, throttle) {
  const blades = 6, cx = 101, r = 13;
  for (let i = 0; i < blades; i++) {
    const a = phase + (i / blades) * TAU;
    const x = cx + Math.cos(a) * r;
    const depth = (Math.sin(a) + 1) * 0.5;    // 0 = far side, 1 = near
    g.strokeStyle = `rgba(${140 + depth * 90},${200 + depth * 45},${196 + depth * 50},${0.28 + depth * 0.55})`;
    g.lineWidth = 2 + depth * 2.6;
    g.beginPath();
    g.moveTo(x, -92);
    g.lineTo(x, 92);
    g.stroke();
    if (depth > 0.72 && throttle > 0.2) {
      g.strokeStyle = `rgba(255,246,220,${(depth - 0.72) * 0.9 * throttle})`;
      g.lineWidth = 1;
      g.beginPath(); g.moveTo(x, -92); g.lineTo(x, 92); g.stroke();
    }
  }
}

/* ------------------------------------------------------------------ *
 *  WEAPONS — few enough to draw live with a transform.
 * ------------------------------------------------------------------ */

export const TOWER_ART = {
  // THRESHER — a spinning ring of glass blades. No aiming, no gaps.
  thresher(g, t) {
    g.fillStyle = COL.ironDark;
    g.beginPath(); g.arc(0, 0, 8, 0, TAU); g.fill();
    g.strokeStyle = COL.bronze; g.lineWidth = 2;
    g.beginPath(); g.arc(0, 0, 8, 0, TAU); g.stroke();
    for (let i = 0; i < 3; i++) {
      const a = t * 9 + (i / 3) * TAU;
      const x = Math.cos(a) * 15, y = Math.sin(a) * 15;
      g.strokeStyle = '#9dc6c0'; g.lineWidth = 2.4;
      g.beginPath();
      g.moveTo(0, 0);
      g.quadraticCurveTo(Math.cos(a - 0.3) * 11, Math.sin(a - 0.3) * 11, x, y);
      g.stroke();
      g.fillStyle = '#cfe9e4';
      g.beginPath(); g.arc(x, y, 3, 0, TAU); g.fill();
    }
    g.fillStyle = COL.glowWarm;
    g.beginPath(); g.arc(0, 0, 3.6, 0, TAU); g.fill();
  },

  // GLASSHOT — throws a cone of shattered crystal.
  glasshot(g, t, heat) {
    g.fillStyle = COL.ironDark;
    g.beginPath(); g.roundRect(-8, -8, 16, 16, 3); g.fill();
    g.fillStyle = COL.bronze;
    g.beginPath(); g.roundRect(-2, -6, 18, 4.5, 2); g.fill();
    g.beginPath(); g.roundRect(-2, 1.5, 18, 4.5, 2); g.fill();
    g.fillStyle = '#9dc6c0';
    g.fillRect(-6, -4.5, 5, 9);
    if (heat > 0) {
      g.fillStyle = `rgba(190,255,248,${Math.min(1, heat) * 0.9})`;
      g.beginPath(); g.arc(17, -3.8, 3.2 * heat, 0, TAU); g.fill();
      g.beginPath(); g.arc(17,  3.8, 3.2 * heat, 0, TAU); g.fill();
    }
    g.fillStyle = COL.glowWarm;
    g.beginPath(); g.arc(0, 0, 3, 0, TAU); g.fill();
  },

  // SUNLANCE — a held beam of stored daylight. Longest reach in the game.
  sunlance(g, t, heat) {
    g.fillStyle = COL.ironDark;
    g.beginPath(); g.roundRect(-9, -6.5, 17, 13, 3); g.fill();
    g.fillStyle = COL.bronze;
    g.fillRect(-1, -2.1, 27, 4.2);
    // focusing rings down the lance
    g.fillStyle = COL.stone;
    for (let i = 0; i < 3; i++) g.fillRect(6 + i * 6, -4.2, 2.2, 8.4);
    if (heat > 0) {
      const gl = g.createRadialGradient(28, 0, 1, 28, 0, 9 * heat);
      gl.addColorStop(0, `rgba(255,250,220,${Math.min(1, heat)})`);
      gl.addColorStop(1, 'rgba(255,190,80,0)');
      g.fillStyle = gl;
      g.beginPath(); g.arc(28, 0, 9 * heat, 0, TAU); g.fill();
    }
    g.fillStyle = COL.glowWarm;
    g.beginPath(); g.arc(-2, 0, 3.2, 0, TAU); g.fill();
  },

  // CENSER — swings burning resin. Sets the tide alight and keeps it burning.
  censer(g, t, heat) {
    g.fillStyle = COL.ironDark;
    g.beginPath(); g.roundRect(-11, -7, 13, 14, 4); g.fill();
    // the hanging bowl
    g.fillStyle = COL.bronze;
    g.beginPath(); g.arc(-4.5, 0, 6.2, 0, TAU); g.fill();
    g.fillStyle = '#ff8a2a';
    g.beginPath(); g.arc(-4.5, 0, 3.4, 0, TAU); g.fill();
    g.fillStyle = COL.iron;
    g.fillRect(1, -2.6, 16, 5.2);
    g.fillStyle = COL.bronze;
    g.beginPath(); g.moveTo(15, -4.6); g.lineTo(23, -2.6); g.lineTo(23, 2.6); g.lineTo(15, 4.6);
    g.closePath(); g.fill();
    if (heat > 0) {
      const gl = g.createRadialGradient(24, 0, 1, 24, 0, 7 * heat);
      gl.addColorStop(0, `rgba(255,240,190,${Math.min(1, heat)})`);
      gl.addColorStop(1, 'rgba(255,120,30,0)');
      g.fillStyle = gl;
      g.beginPath(); g.arc(24, 0, 7 * heat, 0, TAU); g.fill();
    }
  },
};
