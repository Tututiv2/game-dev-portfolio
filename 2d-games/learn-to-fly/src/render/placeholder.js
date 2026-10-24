// PROCEDURAL VECTOR ART. This is not a stopgap.
//
// §8.1 settled it: generated animation costs 25 minutes to 4 hours a clip and needs 96+
// frames to yield the 8-16 a sprite loop uses. This game's whole look is flat shapes with
// thick black outlines, so drawing it in code is CLOSER to the 2009 original than a
// diffusion model's output, it animates perfectly because every frame is under control, and
// it costs seconds. The owner confirmed the choice; §8.1 says not to spend a day of GPU
// rediscovering it.
//
// It also means the game is 100% playable and shippable with assets/sheets/ empty, which is
// what stops "waiting on art" from ever blocking "tuning the game" — and a broken sprite
// export degrades to a shape instead of to a crash (§8.4).
//
// EVERY function draws in UNIT SPACE: the entity is 1.0 across, centred on its pivot, +y UP.
// The caller sets up translate/rotate/scale. sprites.js swaps a real sheet in at the same
// pivot and the drawing code never learns about it.

export const PALETTE = {
  ink: '#0d1117',
  penguinBody: '#232f3e',
  penguinBody2: '#16202b',
  penguinBelly: '#f7fbff',
  beak: '#ff9c2a',
  beakDark: '#e07b12',
  ice: '#eaf4ff',
  iceShade: '#c3dcf4',
  iceDeep: '#9dbfe0',
  snow: '#f4fbff',
  rock: '#6c7787',
  rockDark: '#4a5462',
  water: '#2f86cc',
  waterDeep: '#1b5c95',
  foam: '#ffffff',
  wood: '#a9743f',
  woodDark: '#7a5029',
  metal: '#b9c4d0',
  metalDark: '#7d8896',
  flame: '#ffd23f',
  flameHot: '#fff6d5',
  flameEdge: '#ff6b1f',
  cyan: '#3fe0ff',
  red: '#e0483d',
  gold: '#ffc93c',
  shadow: 'rgba(13,17,23,0.18)',
};

const TAU = Math.PI * 2;

// ── primitives ─────────────────────────────────────────────────────────────────────────

/** Thick black outline is the single most load-bearing thing about this style. */
function ink(ctx, w = 0.045) {
  ctx.lineWidth = w;
  ctx.strokeStyle = PALETTE.ink;
  ctx.lineJoin = 'round';
  ctx.lineCap = 'round';
  ctx.stroke();
}

function shape(ctx, fill, path, lw = 0.045) {
  ctx.beginPath();
  path(ctx);
  ctx.fillStyle = fill;
  ctx.fill();
  if (lw > 0) ink(ctx, lw);
}

function ellipse(ctx, x, y, rx, ry, rot = 0) {
  ctx.ellipse(x, y, Math.abs(rx), Math.abs(ry), rot, 0, TAU);
}

function poly(ctx, pts, close = true) {
  pts.forEach((p, i) => (i === 0 ? ctx.moveTo(p[0], p[1]) : ctx.lineTo(p[0], p[1])));
  if (close) ctx.closePath();
}

/** Deterministic jitter — a jagged edge that is the SAME jagged edge every frame. */
function wobble(seed) {
  let s = seed >>> 0;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

// ── the penguin ────────────────────────────────────────────────────────────────────────

const POSES = {
  idle:      { flipper: -0.15, tuck: 0.0, squash: 1.00, feet: 0.0 },
  ramp:      { flipper: -0.55, tuck: 0.35, squash: 0.92, feet: 0.4 },
  glide:     { flipper: 1.45, tuck: 0.1, squash: 1.06, feet: 0.7 },
  pitchup:   { flipper: 1.05, tuck: 0.0, squash: 1.02, feet: 0.5 },
  pitchdown: { flipper: 1.75, tuck: 0.4, squash: 1.10, feet: 0.9 },
  tumble:    { flipper: -1.1, tuck: 0.0, squash: 0.98, feet: -0.5 },
  splash:    { flipper: -0.7, tuck: 0.2, squash: 0.95, feet: 0.2 },
  cheer:     { flipper: -2.3, tuck: 0.0, squash: 1.00, feet: 0.0 },
  dead:      { flipper: -0.2, tuck: 0.0, squash: 0.72, feet: 0.0 },
};

/**
 * @param {object} o  pose, t (seconds, for blink/flutter), thrusting, dummy
 * The penguin is 1.0 long nose-to-tail, centred on its BODY CENTRE — which is what it
 * rotates about in flight, and which is why manifest.json overrides Studio's ground-line
 * pivot for this one entity (§8.3).
 */
export function penguinCapsule(ctx, o = {}) {
  const pose = POSES[o.pose] || POSES.glide;
  const t = o.t || 0;
  const flutter = o.stressed ? Math.sin(t * 42) * 0.10 : 0;
  const sq = pose.squash;
  const body = o.dummy ? '#d8b25a' : PALETTE.penguinBody;
  const body2 = o.dummy ? '#b08c3a' : PALETTE.penguinBody2;

  // rear flipper (behind the body)
  ctx.save();
  ctx.translate(-0.02, 0.02);
  ctx.rotate(pose.flipper + flutter * 0.6);
  shape(ctx, body2, (c) => ellipse(c, 0.20, 0, 0.26, 0.075), 0.035);
  ctx.restore();

  // tail
  shape(ctx, body2, (c) => poly(c, [[-0.44, 0.02], [-0.60, -0.06], [-0.44, -0.10]]), 0.035);

  // body
  shape(ctx, body, (c) => ellipse(c, 0, 0, 0.46, 0.30 * sq));

  // belly — offset forward and down, which is what makes it read as a penguin at 12 px
  shape(ctx, PALETTE.penguinBelly, (c) => ellipse(c, 0.10, -0.03, 0.31, 0.22 * sq), 0.03);

  // feet
  ctx.save();
  ctx.translate(-0.06, -0.24 * sq);
  ctx.rotate(pose.feet);
  shape(ctx, PALETTE.beak, (c) => poly(c, [[0, 0], [0.20, 0.03], [0.20, -0.05], [0, -0.03]]), 0.03);
  ctx.restore();

  // beak
  ctx.save();
  ctx.translate(0.42, 0.02);
  shape(ctx, PALETTE.beak, (c) => poly(c, [[0, 0.05], [0.19, 0.0], [0, -0.06]]), 0.032);
  shape(ctx, PALETTE.beakDark, (c) => poly(c, [[0, 0.0], [0.19, 0.0], [0, -0.06]]), 0);
  ctx.restore();

  // eye — blinks on a stable schedule, and goes spiral when dead
  const blink = (t * 1.7) % 4 < 0.13;
  if (o.pose === 'dead') {
    ctx.save();
    ctx.translate(0.27, 0.10);
    ctx.beginPath();
    for (let a = 0; a < TAU * 1.9; a += 0.25) {
      const r = 0.012 + a * 0.012;
      const px = Math.cos(a) * r, py = Math.sin(a) * r;
      a === 0 ? ctx.moveTo(px, py) : ctx.lineTo(px, py);
    }
    ink(ctx, 0.022);
    ctx.restore();
  } else if (blink) {
    ctx.beginPath();
    ctx.moveTo(0.23, 0.10); ctx.lineTo(0.32, 0.10);
    ink(ctx, 0.028);
  } else {
    shape(ctx, PALETTE.penguinBelly, (c) => ellipse(c, 0.27, 0.10, 0.058, 0.062), 0.025);
    shape(ctx, PALETTE.ink, (c) => ellipse(c, 0.29, 0.10, 0.026, 0.030), 0);
  }

  // near flipper (in front)
  ctx.save();
  ctx.translate(0.02, -0.01);
  ctx.rotate(pose.flipper + flutter);
  shape(ctx, body, (c) => ellipse(c, 0.22, 0, 0.29, 0.085), 0.035);
  ctx.restore();

  if (o.dummy) {
    // the crash-test stripe, so the LTF2 joke reads instantly (§7.1)
    ctx.save();
    ctx.beginPath();
    ellipse(ctx, 0, 0, 0.46, 0.30 * sq);
    ctx.clip();
    ctx.fillStyle = '#1c1c22';
    for (let i = -2; i <= 2; i++) ctx.fillRect(-0.06 + i * 0.17, -0.4, 0.06, 0.8);
    ctx.restore();
  }
}

// ── gliders ────────────────────────────────────────────────────────────────────────────

const GLIDER_STYLE = [
  null,
  { fill: PALETTE.wood, edge: PALETTE.woodDark, span: 1.5, label: 'cardboard' },
  { fill: PALETTE.red, edge: '#f5f5f5', span: 1.9, label: 'hang' },
  { fill: '#eef4fa', edge: PALETTE.metalDark, span: 2.3, label: 'delta' },
  { fill: '#1a1a20', edge: PALETTE.cyan, span: 2.7, label: 'proto' },
];

/** Drawn in the PENGUIN's unit space, so it rotates with the bird. tier 0 draws nothing. */
export function gliderChevron(ctx, tier, o = {}) {
  const s = GLIDER_STYLE[tier];
  if (!s) return;
  const hp = o.hp === undefined ? 1 : o.hp;
  const flap = o.stressed ? Math.sin((o.t || 0) * 38) * 0.09 * (1 - hp) : 0;
  const half = s.span * 0.5;

  ctx.save();
  ctx.translate(-0.02, 0.20);
  ctx.rotate(flap);
  // wing as a swept chevron seen edge-on-ish
  shape(ctx, s.fill, (c) => poly(c, [
    [-half, -0.06], [-half * 0.55, 0.10], [half * 0.55, 0.10], [half, -0.06],
    [half * 0.5, -0.02], [-half * 0.5, -0.02],
  ]), 0.04);
  ctx.beginPath();
  ctx.moveTo(-half * 0.55, 0.10); ctx.lineTo(half * 0.55, 0.10);
  ctx.strokeStyle = s.edge; ctx.lineWidth = 0.045; ctx.stroke();
  // mast
  ctx.beginPath();
  ctx.moveTo(0, 0.10); ctx.lineTo(0, -0.16);
  ctx.strokeStyle = PALETTE.ink; ctx.lineWidth = 0.035; ctx.stroke();

  if (tier === 4) {                    // prototype gets a glow strip
    ctx.beginPath();
    ctx.moveTo(-half * 0.9, 0.02); ctx.lineTo(half * 0.9, 0.02);
    ctx.strokeStyle = PALETTE.cyan; ctx.lineWidth = 0.03;
    ctx.globalAlpha = 0.55 + 0.45 * Math.sin((o.t || 0) * 4);
    ctx.stroke();
    ctx.globalAlpha = 1;
  }
  if (hp < 0.999) {                    // cracks that deepen as the wing gives up
    const r = wobble(7);
    ctx.beginPath();
    const n = Math.ceil((1 - hp) * 6);
    for (let i = 0; i < n; i++) {
      const x = (r() * 2 - 1) * half * 0.8;
      ctx.moveTo(x, 0.10);
      ctx.lineTo(x + (r() - 0.5) * 0.25, -0.05);
    }
    ctx.strokeStyle = PALETTE.ink; ctx.lineWidth = 0.02; ctx.stroke();
  }
  ctx.restore();
}

/** The brick — a "glider" with no lift and enormous mass. §7.7's best joke. */
export function brick(ctx) {
  shape(ctx, '#a8442e', (c) => c.rect(-0.5, -0.25, 1.0, 0.5));
  ctx.beginPath();
  ctx.moveTo(-0.5, 0); ctx.lineTo(0.5, 0);
  ctx.moveTo(0, 0); ctx.lineTo(0, 0.25);
  ctx.moveTo(-0.25, -0.25); ctx.lineTo(-0.25, 0);
  ctx.moveTo(0.25, -0.25); ctx.lineTo(0.25, 0);
  ctx.strokeStyle = '#7d3222'; ctx.lineWidth = 0.03; ctx.stroke();
}

// ── rocket flame ───────────────────────────────────────────────────────────────────────

export function rocketFlame(ctx, o = {}) {
  const t = o.t || 0;
  const len = 0.55 + Math.sin(t * 47) * 0.13 + Math.sin(t * 23.3) * 0.07;
  ctx.save();
  ctx.translate(-0.48, 0.0);
  shape(ctx, PALETTE.flameEdge, (c) => poly(c, [[0, 0.13], [-len, 0], [0, -0.13]]), 0);
  shape(ctx, PALETTE.flame, (c) => poly(c, [[0, 0.09], [-len * 0.72, 0], [0, -0.09]]), 0);
  shape(ctx, PALETTE.flameHot, (c) => poly(c, [[0, 0.05], [-len * 0.4, 0], [0, -0.05]]), 0);
  ctx.restore();
}

// ── the ramp ───────────────────────────────────────────────────────────────────────────

/**
 * Drawn in WORLD metres (not unit space) because the ramp's geometry IS the tuning: its top
 * is at rampHeightM and its slope is RAMP_ANGLE, and drawing anything else is a lie about
 * where the penguin launches from.
 *
 * The STRUCTURE is not the slide path. §4.7's slide is a fixed 0.6 s, so at tier 3 it covers
 * about 7 m of a 41 m ramp — drawing the ramp as the slide path alone stands it up at 63
 * degrees on four legs in mid-air. The penguin simply starts part way up, which reads as a
 * run-up and is what it is.
 */
export function rampScaffold(ctx, { topX, topY, baseX, baseY, tier }) {
  const deck = 0.55;
  const bays = Math.max(3, Math.round((topX - baseX) / 6));
  const ux = (topX - baseX), uy = (topY - baseY);
  const len = Math.hypot(ux, uy) || 1;
  // normal to the deck, so the plank has thickness along the slope rather than vertically
  const nx = (uy / len) * deck, ny = (-ux / len) * deck;

  // legs and cross-braces first, so the deck sits on top of them
  ctx.beginPath();
  for (let i = 0; i <= bays; i++) {
    const f = i / bays;
    const x = baseX + ux * f;
    const y = baseY + uy * f;
    ctx.moveTo(x, y); ctx.lineTo(x, baseY);
    if (i > 0) {
      const pf = (i - 1) / bays;
      ctx.moveTo(baseX + ux * pf, baseY);
      ctx.lineTo(x, y);
      ctx.moveTo(baseX + ux * pf, baseY + uy * pf);
      ctx.lineTo(x, baseY);
    }
  }
  ctx.strokeStyle = PALETTE.woodDark;
  ctx.lineWidth = 0.22; ctx.lineCap = 'round'; ctx.stroke();

  shape(ctx, PALETTE.wood, (c) => {
    c.moveTo(baseX, baseY); c.lineTo(topX, topY);
    c.lineTo(topX + nx, topY + ny); c.lineTo(baseX + nx, baseY + ny);
  }, 0.16);

  // the lip you leave from
  shape(ctx, PALETTE.woodDark, (c) => c.rect(topX - 0.6, topY, 1.2, 0.34), 0.12);
  // tier flourish: a taller ramp gets a rail, so the upgrade is legible from across the screen
  if (tier >= 2) {
    ctx.beginPath();
    ctx.moveTo(baseX, baseY + 1.2); ctx.lineTo(topX, topY + 1.2);
    for (let i = 0; i <= bays; i++) {
      const f = i / bays;
      ctx.moveTo(baseX + ux * f, baseY + uy * f);
      ctx.lineTo(baseX + ux * f, baseY + uy * f + 1.2);
    }
    ctx.strokeStyle = PALETTE.woodDark; ctx.lineWidth = 0.14; ctx.stroke();
  }
}

// ── world surfaces ─────────────────────────────────────────────────────────────────────

export function iceShelf(ctx, x0, x1, y) {
  shape(ctx, PALETTE.ice, (c) => {
    c.moveTo(x0, y); c.lineTo(x1, y);
    c.lineTo(x1, y - 40); c.lineTo(x0, y - 40);
  }, 0);
  ctx.beginPath();
  ctx.moveTo(x0, y); ctx.lineTo(x1, y);
  ctx.strokeStyle = PALETTE.ink; ctx.lineWidth = 0.22; ctx.stroke();
  shape(ctx, PALETTE.iceShade, (c) => c.rect(x0, y - 40, x1 - x0, 38.5), 0);
  // a couple of crevasse lines so it is not a flat slab
  const r = wobble(21);
  ctx.beginPath();
  for (let i = 0; i < 6; i++) {
    const x = x0 + (x1 - x0) * r();
    ctx.moveTo(x, y - 0.4); ctx.lineTo(x + (r() - 0.5) * 2, y - 3 - r() * 5);
  }
  ctx.strokeStyle = PALETTE.iceDeep; ctx.lineWidth = 0.14; ctx.stroke();
}

export function snowField(ctx, x0, x1, y) {
  shape(ctx, PALETTE.snow, (c) => c.rect(x0, y - 60, x1 - x0, 60), 0);
  ctx.beginPath();
  ctx.moveTo(x0, y); ctx.lineTo(x1, y);
  ctx.strokeStyle = PALETTE.ink; ctx.lineWidth = 0.22; ctx.stroke();
  const r = wobble(99);
  ctx.beginPath();
  for (let i = 0; i < 30; i++) {
    const x = x0 + (x1 - x0) * r();
    ctx.moveTo(x, y - 0.5 - r() * 2);
    ctx.lineTo(x + 1.5 + r() * 2, y - 0.5 - r() * 2);
  }
  ctx.strokeStyle = PALETTE.iceShade; ctx.lineWidth = 0.12; ctx.stroke();
}

/** Side-on band of ocean. `t` animates the crests; `x0..x1` is the visible span. */
export function waterBand(ctx, x0, x1, y, t, depth) {
  ctx.beginPath();
  ctx.moveTo(x0, y);
  const step = Math.max((x1 - x0) / 160, 0.35);
  for (let x = x0; x <= x1; x += step) {
    ctx.lineTo(x, y + Math.sin(x * 0.42 + t * 1.7) * 0.16 + Math.sin(x * 1.13 - t * 2.6) * 0.07);
  }
  ctx.lineTo(x1, y - depth); ctx.lineTo(x0, y - depth);
  ctx.closePath();
  const g = ctx.createLinearGradient(0, y, 0, y - depth);
  g.addColorStop(0, PALETTE.water);
  g.addColorStop(1, PALETTE.waterDeep);
  ctx.fillStyle = g; ctx.fill();

  ctx.beginPath();
  ctx.moveTo(x0, y);
  for (let x = x0; x <= x1; x += step) {
    ctx.lineTo(x, y + Math.sin(x * 0.42 + t * 1.7) * 0.16 + Math.sin(x * 1.13 - t * 2.6) * 0.07);
  }
  ctx.strokeStyle = PALETTE.ink; ctx.lineWidth = 0.18; ctx.stroke();
}

export function cloudPuff(ctx, seed = 1) {
  const r = wobble(seed);
  ctx.beginPath();
  const lobes = 4 + Math.floor(r() * 3);
  for (let i = 0; i < lobes; i++) {
    const f = i / (lobes - 1);
    const x = -0.5 + f;
    const y = Math.sin(f * Math.PI) * 0.10 - 0.05;
    ellipse(ctx, x, y, 0.16 + r() * 0.12, 0.13 + r() * 0.10);
  }
  ctx.fillStyle = '#ffffff';
  ctx.fill();
}

// ── LTF2 obstacles, with three damage stages (§8.5) ────────────────────────────────────

function crackOverlay(ctx, w, h, stage, seed) {
  if (stage === 0) return;
  const r = wobble(seed);
  ctx.beginPath();
  const n = stage === 1 ? 5 : 12;
  for (let i = 0; i < n; i++) {
    let x = (r() - 0.5) * w * 0.8;
    let y = r() * h * 0.9;
    ctx.moveTo(x, y);
    for (let k = 0; k < 3; k++) {
      x += (r() - 0.5) * w * 0.3;
      y -= r() * h * 0.22;
      ctx.lineTo(x, y);
    }
  }
  ctx.strokeStyle = PALETTE.ink;
  ctx.lineWidth = Math.max(w, h) * 0.012;
  ctx.globalAlpha = stage === 1 ? 0.55 : 0.9;
  ctx.stroke();
  ctx.globalAlpha = 1;
}

/** Drawn in WORLD metres with the base at y=0, because obstacles stand on the ground. */
export function obstacleShape(ctx, kind, w, h, stage) {
  const lw = Math.max(w, h) * 0.02;
  const chunk = stage === 2 ? 0.72 : stage === 1 ? 0.9 : 1;
  const hh = h * chunk;

  if (kind === 'snowman') {
    shape(ctx, PALETTE.snow, (c) => ellipse(c, 0, hh * 0.20, w * 0.46, hh * 0.22), lw);
    shape(ctx, PALETTE.snow, (c) => ellipse(c, 0, hh * 0.55, w * 0.35, hh * 0.18), lw);
    if (stage < 2) {
      shape(ctx, PALETTE.snow, (c) => ellipse(c, 0, hh * 0.82, w * 0.26, hh * 0.15), lw);
      shape(ctx, PALETTE.beak, (c) => poly(c, [[w * 0.2, hh * 0.84], [w * 0.62, hh * 0.80], [w * 0.2, hh * 0.78]]), lw);
      shape(ctx, PALETTE.ink, (c) => { ellipse(c, -w * 0.06, hh * 0.87, w * 0.035, hh * 0.022); }, 0);
      shape(ctx, PALETTE.ink, (c) => { ellipse(c, w * 0.08, hh * 0.87, w * 0.035, hh * 0.022); }, 0);
    }
    ctx.beginPath();
    ctx.moveTo(-w * 0.3, hh * 0.58); ctx.lineTo(-w * 0.75, hh * 0.75);
    ctx.moveTo(w * 0.3, hh * 0.58); ctx.lineTo(w * 0.72, hh * 0.70);
    ctx.strokeStyle = PALETTE.woodDark; ctx.lineWidth = lw * 1.6; ctx.stroke();
  } else if (kind === 'mound') {
    shape(ctx, PALETTE.snow, (c) => {
      c.moveTo(-w * 0.5, 0);
      c.bezierCurveTo(-w * 0.34, hh * 1.05, w * 0.30, hh * 1.05, w * 0.5, 0);
    }, lw);
    shape(ctx, PALETTE.iceShade, (c) => {
      c.moveTo(w * 0.06, hh * 0.86); c.bezierCurveTo(w * 0.3, hh * 0.7, w * 0.42, hh * 0.35, w * 0.5, 0);
      c.lineTo(w * 0.14, 0);
    }, 0);
  } else if (kind === 'hill') {
    shape(ctx, PALETTE.rock, (c) => poly(c, [
      [-w * 0.5, 0], [-w * 0.28, hh * 0.55], [-w * 0.08, hh * 0.42],
      [w * 0.05, hh * 1.0], [w * 0.26, hh * 0.5], [w * 0.5, 0],
    ]), lw);
    shape(ctx, PALETTE.snow, (c) => poly(c, [
      [-w * 0.16, hh * 0.62], [w * 0.05, hh * 1.0], [w * 0.20, hh * 0.66],
      [w * 0.08, hh * 0.74], [-w * 0.03, hh * 0.66],
    ]), 0);
    shape(ctx, PALETTE.rockDark, (c) => poly(c, [[w * 0.05, hh], [w * 0.26, hh * 0.5], [w * 0.5, 0], [w * 0.18, 0]]), 0);
  } else if (kind === 'iceberg') {
    shape(ctx, PALETTE.ice, (c) => poly(c, [
      [-w * 0.5, 0], [-w * 0.38, hh * 0.45], [-w * 0.15, hh * 0.34],
      [-w * 0.02, hh * 1.0], [w * 0.18, hh * 0.55], [w * 0.34, hh * 0.66], [w * 0.5, 0],
    ]), lw);
    shape(ctx, PALETTE.iceShade, (c) => poly(c, [
      [-w * 0.02, hh], [w * 0.18, hh * 0.55], [w * 0.34, hh * 0.66], [w * 0.5, 0], [w * 0.05, 0],
    ]), 0);
    shape(ctx, PALETTE.iceDeep, (c) => poly(c, [[-w * 0.5, 0], [-w * 0.38, hh * 0.45], [-w * 0.2, 0]]), 0);
  } else {                                       // the wall
    shape(ctx, PALETTE.ice, (c) => c.rect(-w * 0.5, 0, w, hh), lw);
    shape(ctx, PALETTE.iceShade, (c) => c.rect(w * 0.12, 0, w * 0.38, hh), 0);
    ctx.beginPath();
    for (let i = 1; i < 14; i++) {
      const y = hh * (i / 14);
      ctx.moveTo(-w * 0.5, y); ctx.lineTo(w * 0.5, y);
    }
    ctx.strokeStyle = PALETTE.iceDeep; ctx.lineWidth = lw * 0.8; ctx.stroke();
    shape(ctx, PALETTE.iceDeep, (c) => poly(c, [
      [-w * 0.5, hh], [-w * 0.3, hh * 1.03], [-w * 0.1, hh * 0.99],
      [w * 0.1, hh * 1.04], [w * 0.32, hh * 0.98], [w * 0.5, hh],
    ]), lw);
  }
  crackOverlay(ctx, w, hh, stage, kind.length * 977 + 13);
}

// ── props ──────────────────────────────────────────────────────────────────────────────

export function kiwipediaBook(ctx) {
  shape(ctx, '#f3efe2', (c) => poly(c, [[-0.5, -0.22], [-0.03, -0.30], [-0.03, 0.28], [-0.5, 0.20]]), 0.03);
  shape(ctx, '#f3efe2', (c) => poly(c, [[0.5, -0.22], [0.03, -0.30], [0.03, 0.28], [0.5, 0.20]]), 0.03);
  ctx.beginPath();
  for (let i = 0; i < 5; i++) {
    ctx.moveTo(0.08, 0.16 - i * 0.08); ctx.lineTo(0.42, 0.10 - i * 0.08);
    ctx.moveTo(-0.42, 0.10 - i * 0.08); ctx.lineTo(-0.08, 0.16 - i * 0.08);
  }
  ctx.strokeStyle = '#9aa4b0'; ctx.lineWidth = 0.018; ctx.stroke();
  shape(ctx, '#8b6f3f', (c) => ellipse(c, -0.26, -0.02, 0.10, 0.12), 0.024);
  shape(ctx, PALETTE.beak, (c) => poly(c, [[-0.19, -0.02], [-0.02, -0.05], [-0.19, -0.07]]), 0.02);
}

export function merchantPenguin(ctx, o = {}) {
  const t = o.t || 0;
  ctx.save();
  ctx.rotate(Math.sin(t * 1.4) * 0.06);
  shape(ctx, '#3c3320', (c) => poly(c, [[-0.3, -0.5], [0.3, -0.5], [0.24, 0.24], [-0.24, 0.24]]), 0.04);
  shape(ctx, PALETTE.penguinBody, (c) => ellipse(c, 0, 0.3, 0.24, 0.22), 0.04);
  shape(ctx, PALETTE.beak, (c) => poly(c, [[0.16, 0.3], [0.40, 0.27], [0.16, 0.24]]), 0.03);
  shape(ctx, PALETTE.penguinBelly, (c) => ellipse(c, 0.08, 0.36, 0.05, 0.055), 0.022);
  shape(ctx, PALETTE.ink, (c) => ellipse(c, 0.10, 0.36, 0.022, 0.028), 0);
  shape(ctx, '#2a2418', (c) => c.rect(-0.34, 0.42, 0.68, 0.10), 0.035);
  ctx.restore();
}

export function phoenixFeather(ctx, o = {}) {
  const t = o.t || 0;
  ctx.save();
  ctx.rotate(Math.sin(t * 2) * 0.15);
  shape(ctx, '#ff8a1f', (c) => {
    c.moveTo(0, 0.5);
    c.bezierCurveTo(0.22, 0.1, 0.16, -0.3, 0, -0.5);
    c.bezierCurveTo(-0.16, -0.3, -0.22, 0.1, 0, 0.5);
  }, 0.03);
  shape(ctx, PALETTE.gold, (c) => {
    c.moveTo(0, 0.42); c.bezierCurveTo(0.10, 0.1, 0.08, -0.2, 0, -0.4);
    c.bezierCurveTo(-0.08, -0.2, -0.10, 0.1, 0, 0.42);
  }, 0);
  ctx.restore();
}

export function rob64(ctx, o = {}) {
  const t = o.t || 0;
  ctx.save();
  ctx.translate(0, Math.sin(t * 3) * 0.06);
  shape(ctx, PALETTE.metal, (c) => c.rect(-0.32, -0.22, 0.64, 0.5), 0.04);
  shape(ctx, '#1a2430', (c) => c.rect(-0.2, 0.0, 0.4, 0.18), 0.03);
  shape(ctx, PALETTE.cyan, (c) => ellipse(c, -0.09, 0.09, 0.05, 0.05), 0);
  shape(ctx, PALETTE.cyan, (c) => ellipse(c, 0.09, 0.09, 0.05, 0.05), 0);
  shape(ctx, PALETTE.metalDark, (c) => c.rect(-0.14, -0.36, 0.28, 0.16), 0.03);
  // flicker off t, not Math.random — a replay (§15) has to look the same twice
  shape(ctx, PALETTE.flame, (c) => poly(c, [[-0.1, -0.36], [0, -0.36 - 0.18 - (0.5 + 0.5 * Math.sin(t * 31)) * 0.06], [0.1, -0.36]]), 0);
  ctx.restore();
}

export function bombPayload(ctx, o = {}) {
  const t = o.t || 0;
  shape(ctx, '#1b1b22', (c) => ellipse(c, 0, 0, 0.36, 0.36), 0.045);
  shape(ctx, PALETTE.metalDark, (c) => c.rect(-0.09, 0.30, 0.18, 0.12), 0.035);
  ctx.beginPath();
  ctx.moveTo(0, 0.42);
  ctx.quadraticCurveTo(0.18, 0.56, 0.10, 0.70);
  ctx.strokeStyle = '#c8b48a'; ctx.lineWidth = 0.045; ctx.stroke();
  shape(ctx, PALETTE.flameHot, (c) => ellipse(c, 0.10, 0.70, 0.06 + Math.sin(t * 30) * 0.02, 0.07), 0);
}

/** Every placeholder the manifest may name. sprites.js resolves against this. */
export const PLACEHOLDERS = {
  penguinCapsule, gliderChevron, brick, rocketFlame, rampScaffold, iceShelf, snowField,
  waterBand, cloudPuff, obstacleShape, kiwipediaBook, merchantPenguin, phoenixFeather,
  rob64, bombPayload,
};
