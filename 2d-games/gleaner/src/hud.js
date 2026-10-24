import { CFG, COL } from './config.js';
import { TOWERS } from './towers.js';
import { ETYPE } from './swarm.js';
import { clamp, lerp, fmt, TAU } from './util.js';

const F = (s, w) => `${w || 600} ${s}px "Segoe UI", Inter, system-ui, sans-serif`;

function box(g, x, y, w, h, r, fill, stroke) {
  g.fillStyle = fill;
  g.beginPath(); g.roundRect(x, y, w, h, r); g.fill();
  if (stroke) { g.strokeStyle = stroke; g.lineWidth = 1.5; g.stroke(); }
}

export function drawHUD(g, w, R) {
  const vw = R.vw, vh = R.vh;
  g.textBaseline = 'middle';

  drawThrottle(g, w, 26, vh / 2 - 150, 300);
  drawTopLeft(g, w);
  drawTopCentre(g, w, vw);
  drawHotbar(g, w, vw, vh);
  drawTip(g, w, vw, vh);

  if (w.swarm.swellWarning > 0) {
    const a = 0.5 + Math.sin(w.time * 14) * 0.5;
    g.fillStyle = `rgba(224,83,58,${0.55 + a * 0.4})`;
    g.font = F(30, 800);
    g.textAlign = 'center';
    g.fillText('THE CHORUS ANSWERS', vw / 2, 168);
    g.font = F(13, 600);
    g.fillStyle = 'rgba(255,220,190,0.7)';
    g.fillText('SOMETHING OUT THERE HAS LEARNED YOUR SONG', vw / 2, 192);
  }

  if (w.hv.hull / CFG.hv.hullMax < 0.28) {
    const a = 0.20 + Math.sin(w.time * 7) * 0.14;
    g.fillStyle = `rgba(190,30,10,${a})`;
    g.fillRect(0, 0, vw, vh);
  }
}

/* ------------------------------ the dial ------------------------------ */

function drawThrottle(g, w, x, y, h) {
  const hv = w.hv;
  const bw = 44;

  box(g, x - 8, y - 46, bw + 108, h + 92, 12, 'rgba(18,12,6,0.62)', 'rgba(210,180,130,0.18)');

  g.textAlign = 'left';
  g.font = F(11, 800);
  g.fillStyle = COL.uiDim;
  g.fillText('CHOIR', x - 1, y - 30);

  // track
  box(g, x, y, bw, h, 8, 'rgba(0,0,0,0.5)', 'rgba(210,180,130,0.25)');

  // fill — green at idle through to red wide open, because wide open is the danger
  const fh = h * hv.throttle;
  const grd = g.createLinearGradient(0, y + h, 0, y);
  grd.addColorStop(0, '#5fa855');
  grd.addColorStop(0.45, '#d8bc42');
  grd.addColorStop(0.78, '#e08a2c');
  grd.addColorStop(1, '#e0442c');
  g.fillStyle = grd;
  g.beginPath(); g.roundRect(x + 3, y + h - fh + 3, bw - 6, Math.max(0, fh - 6), 5); g.fill();

  // ticks
  g.strokeStyle = 'rgba(255,235,200,0.22)';
  g.lineWidth = 1;
  for (let i = 0; i <= 10; i++) {
    const ty = y + h - (h * i) / 10;
    g.beginPath(); g.moveTo(x + bw, ty); g.lineTo(x + bw + (i % 5 === 0 ? 10 : 5), ty); g.stroke();
  }

  // needle
  const ny = y + h - fh;
  g.strokeStyle = COL.ui; g.lineWidth = 2.5;
  g.beginPath(); g.moveTo(x - 6, ny); g.lineTo(x + bw + 12, ny); g.stroke();

  g.font = F(22, 800);
  g.fillStyle = COL.ui;
  g.fillText(Math.round(hv.throttle * 100) + '%', x + bw + 18, ny);

  // ---- what the dial buys you, and what it costs ----
  const gy = y + h + 22;
  g.font = F(10, 700);
  g.fillStyle = COL.uiDim;
  g.fillText('YIELD', x, gy);
  g.fillText('SONG', x, gy + 30);

  const grainRate = w.grainRate;
  const noiseF = hv.noise / CFG.noise.full;

  bar(g, x + 46, gy - 5, 92, 10, clamp(grainRate / 16, 0, 1), '#d9a83f');
  bar(g, x + 46, gy + 25, 92, 10, noiseF, '#e0533a');

  g.font = F(11, 700);
  g.fillStyle = COL.ui;
  g.textAlign = 'right';
  g.fillText(grainRate.toFixed(1) + '/s', x + 138, gy + 22);
  g.fillText(Math.round(hv.noise) + 'm', x + 138, gy + 52);
  g.textAlign = 'left';
}

function bar(g, x, y, w, h, t, c) {
  g.fillStyle = 'rgba(0,0,0,0.45)';
  g.beginPath(); g.roundRect(x, y, w, h, h / 2); g.fill();
  g.fillStyle = c;
  g.beginPath(); g.roundRect(x, y, Math.max(h, w * clamp(t, 0, 1)), h, h / 2); g.fill();
}

/* ------------------------------ resources ------------------------------ */

function drawTopLeft(g, w) {
  box(g, 20, 18, 232, 62, 10, 'rgba(18,12,6,0.62)', 'rgba(210,180,130,0.18)');

  // a cut shard, holding light
  g.save();
  g.translate(46, 49);
  const gl = g.createRadialGradient(0, 0, 0, 0, 0, 14);
  gl.addColorStop(0, 'rgba(255,240,190,0.55)');
  gl.addColorStop(1, 'rgba(255,209,120,0)');
  g.fillStyle = gl;
  g.beginPath(); g.arc(0, 0, 14, 0, TAU); g.fill();
  g.fillStyle = '#f2d178';
  g.beginPath();
  g.moveTo(0, -11); g.lineTo(6, -1); g.lineTo(0, 11); g.lineTo(-6, -1);
  g.closePath(); g.fill();
  g.fillStyle = 'rgba(255,250,225,0.85)';
  g.beginPath();
  g.moveTo(0, -11); g.lineTo(3, -1); g.lineTo(0, 4); g.lineTo(-3, -1);
  g.closePath(); g.fill();
  g.restore();

  g.textAlign = 'left';
  g.font = F(30, 800);
  g.fillStyle = COL.ui;
  g.fillText(fmt(w.grain), 70, 44);
  g.font = F(10, 700);
  g.fillStyle = COL.uiDim;
  g.fillText('LUMEN', 70, 66);

  g.textAlign = 'right';
  g.font = F(18, 800);
  g.fillStyle = COL.ui;
  g.fillText(fmt(w.hv.dist) + ' m', 238, 42);
  g.font = F(10, 700);
  g.fillStyle = COL.uiDim;
  g.fillText('CROSSED', 238, 64);
  g.textAlign = 'left';
}

/* ------------------------------ hull + tally ------------------------------ */

function drawTopCentre(g, w, vw) {
  const bw = 460, x = vw / 2 - bw / 2;
  const hv = w.hv;
  const frac = hv.hull / CFG.hv.hullMax;

  g.textAlign = 'center';
  g.font = F(11, 800);
  g.fillStyle = COL.uiDim;
  g.fillText('FRAME', vw / 2, 30);

  box(g, x, 40, bw, 22, 6, 'rgba(0,0,0,0.55)', 'rgba(210,180,130,0.25)');
  const c = frac > 0.55 ? '#5fa855' : frac > 0.28 ? '#d8a02c' : '#e0442c';
  g.fillStyle = c;
  g.beginPath(); g.roundRect(x + 3, 43, Math.max(0, (bw - 6) * frac), 16, 4); g.fill();

  g.font = F(13, 800);
  g.fillStyle = frac > 0.55 ? 'rgba(255,255,255,0.85)' : '#fff';
  g.fillText(Math.ceil(hv.hull) + ' / ' + CFG.hv.hullMax, vw / 2, 52);

  // enemy tally, one entry per type you have actually met
  const maxT = w.swarm.maxType(hv.dist);
  const n = maxT + 1;
  const step = 118;
  const sx = vw / 2 - ((n - 1) * step) / 2;
  for (let t = 0; t <= maxT; t++) {
    const px = sx + t * step;
    const sp = w.sprites[t];
    const scale = t === 2 ? 0.46 : t === 1 ? 0.66 : 0.9;
    const sz = sp.size * scale;
    g.drawImage(sp.canvas, 0, 0, sp.size, sp.size, px - 34 - sz / 2, 82 - sz / 2, sz, sz);
    g.textAlign = 'left';
    g.font = F(19, 800);
    g.fillStyle = COL.ui;
    g.fillText(fmt(w.swarm.counts[t]), px - 18, 78);
    g.font = F(9, 700);
    g.fillStyle = COL.uiDim;
    g.fillText(ETYPE[t].name.toUpperCase(), px - 18, 94);
  }
  g.textAlign = 'center';
}

/* ------------------------------ hotbar ------------------------------ */

function drawHotbar(g, w, vw, vh) {
  const n = TOWERS.length;
  const sw = 92, gap = 8;
  const total = n * sw + (n - 1) * gap;
  const x0 = vw / 2 - total / 2;
  const y = vh - 108;

  for (let i = 0; i < n; i++) {
    const x = x0 + i * (sw + gap);
    const def = TOWERS[i];
    const unlocked = w.hv.dist >= CFG.unlockAt[i];
    const cost = w.towers.costOf(i);
    const afford = w.grain >= cost;
    const sel = w.selected === i;

    box(g, x, y, sw, 88, 9,
      sel ? 'rgba(60,44,18,0.92)' : 'rgba(18,12,6,0.72)',
      sel ? '#f0d79a' : 'rgba(210,180,130,0.2)');

    if (!unlocked) {
      g.fillStyle = 'rgba(0,0,0,0.55)';
      g.beginPath(); g.roundRect(x, y, sw, 88, 9); g.fill();
      g.textAlign = 'center';
      g.font = F(11, 800);
      g.fillStyle = COL.uiDim;
      g.fillText('LOCKED', x + sw / 2, y + 36);
      g.font = F(10, 700);
      g.fillText(CFG.unlockAt[i] + ' m', x + sw / 2, y + 54);
      continue;
    }

    g.save();
    g.translate(x + sw / 2, y + 34);
    g.globalAlpha = afford ? 1 : 0.4;
    g.rotate(-Math.PI / 2);
    g.scale(1.15, 1.15);
    w.towerArt[def.art](g, w.time, 0);
    g.restore();
    g.globalAlpha = 1;

    g.textAlign = 'center';
    g.font = F(10, 800);
    g.fillStyle = afford ? COL.ui : 'rgba(200,170,120,0.45)';
    g.fillText(def.name, x + sw / 2, y + 62);
    g.font = F(13, 800);
    g.fillStyle = afford ? '#e8c869' : '#8a6a3a';
    g.fillText(fmt(cost), x + sw / 2, y + 78);

    g.textAlign = 'left';
    g.font = F(11, 800);
    g.fillStyle = sel ? '#f0d79a' : COL.uiDim;
    g.fillText(String(i + 1), x + 7, y + 12);
  }

  g.textAlign = 'center';
  if (w.selected >= 0) {
    g.font = F(12, 600);
    g.fillStyle = 'rgba(240,225,190,0.8)';
    g.fillText(TOWERS[w.selected].blurb, vw / 2, y - 14);
  }
}

function drawTip(g, w, vw, vh) {
  g.textAlign = 'right';
  g.font = F(11, 600);
  g.fillStyle = 'rgba(240,225,190,0.42)';
  const rows = [
    'W / S  choir       A / D  steer       SPACE  silence it',
    '1-4  pick weapon    CLICK  mount    RIGHT-CLICK  strip',
    'R  mend frame (' + CFG.repairGrainPerSec + ' lumen/s)     M  mute',
  ];
  for (let i = 0; i < rows.length; i++) g.fillText(rows[i], vw - 24, vh - 62 + i * 17);
  g.textAlign = 'left';
}

/* ------------------------------ screens ------------------------------ */

export function drawTitle(g, R, time) {
  const vw = R.vw, vh = R.vh;
  g.fillStyle = 'rgba(14,9,20,0.76)';
  g.fillRect(0, 0, vw, vh);

  g.textAlign = 'center';
  g.font = F(84, 900);
  g.fillStyle = '#f2d178';
  g.fillText('GLEANER', vw / 2, vh / 2 - 112);

  g.font = F(13, 700);
  g.fillStyle = 'rgba(127,232,220,0.8)';
  g.fillText('T H E   G O L D S E A', vw / 2, vh / 2 - 78);

  g.font = F(17, 600);
  g.fillStyle = 'rgba(240,225,190,0.82)';
  g.fillText('Three thousand years on, the crystal has drowned everything and it is full of light.',
             vw / 2, vh / 2 - 40);
  g.fillText('You drive one of the last reaping engines. It does not roar. It sings.',
             vw / 2, vh / 2 - 14);

  g.font = F(14, 700);
  g.fillStyle = 'rgba(255,154,60,0.95)';
  g.fillText('ONE LEVER SETS YOUR INCOME AND YOUR DIFFICULTY. IT IS THE SAME LEVER.',
             vw / 2, vh / 2 + 28);

  const a = 0.55 + Math.sin(time * 3.4) * 0.4;
  g.font = F(20, 800);
  g.fillStyle = `rgba(255,255,255,${a})`;
  g.fillText('CLICK TO START', vw / 2, vh / 2 + 96);

  g.font = F(12, 600);
  g.fillStyle = 'rgba(240,225,190,0.45)';
  g.fillText('W / S choir     A / D steer     1-4 select a weapon     CLICK to mount it on the deck',
             vw / 2, vh / 2 + 136);
}

export function drawDead(g, R, w) {
  const vw = R.vw, vh = R.vh;
  g.fillStyle = 'rgba(26,6,22,0.80)';
  g.fillRect(0, 0, vw, vh);

  g.textAlign = 'center';
  g.font = F(66, 900);
  g.fillStyle = '#e0533a';
  g.fillText('THE GLEANER HAS FALLEN', vw / 2, vh / 2 - 78);

  g.font = F(15, 600);
  g.fillStyle = 'rgba(127,232,220,0.7)';
  g.fillText('the song stops, and the Goldsea closes over it', vw / 2, vh / 2 - 44);

  g.font = F(30, 800);
  g.fillStyle = COL.ui;
  g.fillText(fmt(w.hv.dist) + ' metres crossed', vw / 2, vh / 2 + 2);

  g.font = F(16, 600);
  g.fillStyle = 'rgba(240,225,190,0.8)';
  g.fillText(fmt(w.harvested) + ' lumen reaped        ' + fmt(w.killed) + ' silenced',
             vw / 2, vh / 2 + 40);

  const a = 0.55 + Math.sin(w.time * 3.4) * 0.4;
  g.font = F(18, 800);
  g.fillStyle = `rgba(255,255,255,${a})`;
  g.fillText('CLICK TO RUN AGAIN', vw / 2, vh / 2 + 100);
}
