// Every screen. Canvas-drawn, so the menus look like the game rather than like a web page.
//
// Immediate-mode: each draw call pushes clickable regions into `hits`, and main.js tests a
// click against them. No DOM elements, no CSS, no layout engine — this whole file is
// rectangles and text, which is exactly what a 2009 Flash game's UI was.

import { PALETTE, penguinCapsule, kiwipediaBook, merchantPenguin, cloudPuff, obstacleShape } from './placeholder.js';

const F = (n, d = 0) => n.toLocaleString(undefined, { minimumFractionDigits: d, maximumFractionDigits: d });

// §6.1 — flavour, picked BY DAY NUMBER so it is stable across reloads.
export const DAY_FLAVOUR = [
  'The neighbours have stopped asking.',
  'Aerodynamics is just spite with a diagram.',
  'The book is still wrong.',
  'Today feels like the day. Today is not the day.',
  'Someone left a pamphlet about swimming. It has been eaten.',
  'The ramp creaks in a new place.',
  'A seagull watched the whole thing. No comment.',
  'Confidence: unchanged. Evidence: mounting.',
  'The fish are getting used to this.',
  'You have begun dreaming in trajectories.',
  'A second penguin came to watch. It left.',
  'The wind is helping today. The wind is lying.',
  'You have started saying "we" about the ramp.',
  'Kiwipedia has not issued a correction.',
  'The ocean remains undefeated but nervous.',
  'You measured the horizon. It is further than it looks.',
  'Somewhere out there: an iceberg. Unrelated.',
  'Tools have been acquired. Questions were not asked.',
  'The seagull is back. It brought a friend.',
  'Nobody is calling it a hobby any more.',
];

export function flavourFor(day) { return DAY_FLAVOUR[(day - 1) % DAY_FLAVOUR.length]; }

// ── little drawing helpers ─────────────────────────────────────────────────────────────

function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.roundRect(x, y, w, h, r);
}

function text(ctx, s, x, y, { size = 18, weight = 700, align = 'left', fill = '#fff', stroke = null, font = 'system-ui, sans-serif' } = {}) {
  ctx.font = `${weight} ${size}px ${font}`;
  ctx.textAlign = align;
  ctx.textBaseline = 'middle';
  if (stroke) { ctx.lineWidth = size * 0.22; ctx.strokeStyle = stroke; ctx.lineJoin = 'round'; ctx.strokeText(s, x, y); }
  ctx.fillStyle = fill;
  ctx.fillText(s, x, y);
}

/** An ice slab — the launcher's big buttons, and the shop's chunky rows. */
function slab(ctx, x, y, w, h, { hot = false, disabled = false, tint = null } = {}) {
  roundRect(ctx, x, y, w, h, 12);
  const g = ctx.createLinearGradient(0, y, 0, y + h);
  if (disabled) { g.addColorStop(0, '#5c6470'); g.addColorStop(1, '#464d57'); }
  else if (tint) { g.addColorStop(0, tint[0]); g.addColorStop(1, tint[1]); }
  else if (hot) { g.addColorStop(0, '#ffffff'); g.addColorStop(1, '#bfe0fa'); }
  else { g.addColorStop(0, '#e8f4ff'); g.addColorStop(1, '#a8cbe8'); }
  ctx.fillStyle = g;
  ctx.fill();
  ctx.strokeStyle = PALETTE.ink;
  ctx.lineWidth = 3;
  ctx.stroke();
  if (hot && !disabled) {
    roundRect(ctx, x + 3, y + 3, w - 6, h - 6, 9);
    ctx.strokeStyle = 'rgba(255,255,255,0.85)'; ctx.lineWidth = 2; ctx.stroke();
  }
}

export function createUi(T) {
  const hits = [];
  let hover = null;
  let lastHover = null;

  function push(id, x, y, w, h, data) { hits.push({ id, x, y, w, h, data }); }

  const ui = {
    hits,
    get hoverChanged() { const c = hover !== lastHover; lastHover = hover; return c && hover !== null; },
    begin(mouse) {
      hits.length = 0;
      ui.mouse = mouse;
    },
    resolveHover() {
      hover = null;
      if (!ui.mouse) return;
      for (const h of hits) {
        if (ui.mouse.x >= h.x && ui.mouse.x <= h.x + h.w && ui.mouse.y >= h.y && ui.mouse.y <= h.y + h.h) hover = h.id;
      }
      return hover;
    },
    hitAt(x, y) {
      for (let i = hits.length - 1; i >= 0; i--) {
        const h = hits[i];
        if (x >= h.x && x <= h.x + h.w && y >= h.y && y <= h.y + h.h) return h;
      }
      return null;
    },
    isHot(id) { return hover === id; },

    button(ctx, id, x, y, w, h, label, opts = {}) {
      push(id, x, y, w, h, opts.data);
      slab(ctx, x, y, w, h, { hot: hover === id, disabled: opts.disabled, tint: opts.tint });
      text(ctx, label, x + w / 2, y + h / 2, {
        size: opts.size || 20, align: 'center',
        fill: opts.disabled ? '#9aa3ae' : PALETTE.ink,
      });
      return hover === id;
    },

    // ── LAUNCHER ─────────────────────────────────────────────────────────────────────
    // Not a placeholder. It is the first thing on screen in the video, so it gets real art
    // direction: falling snow, the penguin idling on an ice shelf, the two logos as carved
    // ice slabs (§1).
    launcher(ctx, w, h, s) {
      const g = ctx.createLinearGradient(0, 0, 0, h);
      g.addColorStop(0, '#0f2b4a'); g.addColorStop(0.55, '#2d6ea8'); g.addColorStop(1, '#9fd0f0');
      ctx.fillStyle = g; ctx.fillRect(0, 0, w, h);

      for (const c of s.clouds) {
        ctx.save();
        ctx.translate((c.x + s.t * c.drift) % (w + 400) - 200, c.y);
        ctx.scale(c.s, -c.s);
        ctx.globalAlpha = 0.30;
        cloudPuff(ctx, c.seed);
        ctx.restore();
      }
      ctx.globalAlpha = 1;

      // ice shelf
      const shelfY = h - 96;
      ctx.fillStyle = '#dceefc';
      ctx.beginPath();
      ctx.moveTo(0, shelfY + 18);
      for (let x = 0; x <= w; x += 40) ctx.lineTo(x, shelfY + 18 + Math.sin(x * 0.02) * 5);
      ctx.lineTo(w, h); ctx.lineTo(0, h); ctx.closePath(); ctx.fill();
      ctx.strokeStyle = PALETTE.ink; ctx.lineWidth = 4; ctx.stroke();

      // the penguin, idling
      ctx.save();
      ctx.translate(w * 0.5, shelfY - 42 + Math.sin(s.t * 1.6) * 2);
      // placeholder.js is authored y-UP, so every UI call site flips y as well.
      ctx.scale(96, -96);
      // The art is canonically nose-along-+x because that is the flight pose, and flight is
      // 99% of this game. A bird standing on ice is the rotated case, not the other way round.
      ctx.rotate(Math.PI / 2);
      penguinCapsule(ctx, { pose: 'idle', t: s.t });
      ctx.restore();

      text(ctx, 'LEARN TO FLY', w / 2, 78, { size: 58, align: 'center', fill: '#fff', stroke: PALETTE.ink });
      text(ctx, 'a penguin, a ramp, and a grudge', w / 2, 118, { size: 16, weight: 500, align: 'center', fill: 'rgba(255,255,255,0.75)' });

      const bw = 300, bh = 62, bx = w / 2 - bw / 2;
      let by = 168;
      ui.button(ctx, 'ltf1', bx, by, bw, bh, 'PLAY LEARN TO FLY');
      text(ctx, '2009 — the original', bx + bw + 18, by + bh / 2, { size: 13, weight: 500, fill: 'rgba(255,255,255,0.6)' });
      by += bh + 14;
      const l2 = s.ltf2Unlocked;
      ui.button(ctx, 'ltf2', bx, by, bw, bh, 'PLAY LEARN TO FLY 2', { disabled: !l2 });
      text(ctx, l2 ? '2011 — story / classic / arcade' : 'finish the first one', bx + bw + 18, by + bh / 2,
        { size: 13, weight: 500, fill: 'rgba(255,255,255,0.6)' });
      by += bh + 14;
      ui.button(ctx, 'sidebyside', bx, by, bw / 2 - 6, 46, 'SIDE-BY-SIDE', { size: 15 });
      ui.button(ctx, 'sandbox', bx + bw / 2 + 6, by, bw / 2 - 6, 46, 'SANDBOX', { size: 15 });
      by += 60;
      ui.button(ctx, 'medals', bx, by, bw / 2 - 6, 40, `MEDALS ${s.medalCount}/48`, { size: 14 });
      ui.button(ctx, 'reset', bx + bw / 2 + 6, by, bw / 2 - 6, 40, 'RESET SAVE', { size: 14 });

      // §7.7 joke medal 20 — click the penguin's beak on the title screen
      push('beak', w * 0.5 - 16, shelfY - 92, 34, 30);

      if (s.audioSuspended) {
        text(ctx, 'click anywhere to enable sound', w / 2, h - 26, { size: 13, weight: 500, align: 'center', fill: 'rgba(255,255,255,0.6)' });
      }
      if (s.warning) text(ctx, s.warning, w / 2, h - 48, { size: 13, weight: 600, align: 'center', fill: '#ffd166' });
    },

    // ── INTRO ────────────────────────────────────────────────────────────────────────
    intro(ctx, w, h, frames, idx, tIn) {
      ctx.fillStyle = '#0d1117'; ctx.fillRect(0, 0, w, h);
      const f = frames[Math.min(idx, frames.length - 1)];
      const a = Math.min(1, tIn / 0.4);
      ctx.globalAlpha = a;
      ctx.save();
      ctx.translate(w / 2, h / 2 - 40);
      ctx.scale(160, -160);
      f.draw(ctx, tIn);
      ctx.restore();
      ctx.globalAlpha = 1;

      // typed caption
      const shown = f.caption.slice(0, Math.floor(tIn * 34));
      text(ctx, shown, w / 2, h - 120, { size: 26, align: 'center', fill: '#fff' });
      text(ctx, 'any key to skip', w / 2, h - 40, { size: 13, weight: 500, align: 'center', fill: 'rgba(255,255,255,0.45)' });
      const pipW = 40;
      frames.forEach((_, i) => {
        ctx.fillStyle = i <= idx ? '#fff' : 'rgba(255,255,255,0.25)';
        ctx.fillRect(w / 2 - (frames.length * pipW) / 2 + i * pipW, h - 74, pipW - 8, 3);
      });
    },

    // ── DAY CARD ─────────────────────────────────────────────────────────────────────
    dayCard(ctx, w, h, day, t, dur) {
      const a = Math.min(1, Math.min(t, dur - t) * 4);
      ctx.globalAlpha = Math.max(0, a) * 0.86;
      ctx.fillStyle = '#0d1117'; ctx.fillRect(0, 0, w, h);
      ctx.globalAlpha = Math.max(0, a);
      text(ctx, `Day ${day}`, w / 2, h / 2 - 18, { size: 64, align: 'center', fill: '#fff' });
      text(ctx, flavourFor(day), w / 2, h / 2 + 34, { size: 18, weight: 500, align: 'center', fill: 'rgba(255,255,255,0.7)' });
      ctx.globalAlpha = 1;
    },

    // ── PRE-LAUNCH ───────────────────────────────────────────────────────────────────
    // Not a formality (§5). It is where the player reads their new kit on the penguin and
    // gets the beat of anticipation that makes the launch land.
    preLaunch(ctx, w, h, s) {
      const pulse = 0.6 + 0.4 * Math.sin(s.t * 3.4);
      text(ctx, 'press SPACE to launch', w / 2, h - 76, {
        size: 26, align: 'center', fill: `rgba(255,255,255,${pulse})`, stroke: PALETTE.ink,
      });
      text(ctx, 'LEFT / RIGHT to pitch   ·   SPACE for rockets', w / 2, h - 42, {
        size: 14, weight: 500, align: 'center', fill: 'rgba(255,255,255,0.6)',
      });
      if (s.newGear && s.newGear.length) {
        const y = 96;
        text(ctx, 'NEW THIS MORNING', w / 2, y, { size: 13, align: 'center', fill: PALETTE.gold });
        s.newGear.forEach((g, i) => text(ctx, g, w / 2, y + 24 + i * 22, { size: 17, align: 'center', fill: '#fff', stroke: PALETTE.ink }));
      }
    },

    // ── RESULTS ──────────────────────────────────────────────────────────────────────
    // §2.8 — THIS SCREEN IS THE DOPAMINE. Numbers count up, in order, each with a tick.
    results(ctx, w, h, r) {
      const pw = 460, ph = 372;
      const px = (w - pw) / 2;
      const py = (h - ph) / 2 + (1 - r.slide) * h * 0.7;

      ctx.globalAlpha = r.slide * 0.55;
      ctx.fillStyle = '#0d1117'; ctx.fillRect(0, 0, w, h);
      ctx.globalAlpha = 1;

      roundRect(ctx, px, py, pw, ph, 16);
      const g = ctx.createLinearGradient(0, py, 0, py + ph);
      g.addColorStop(0, '#f3faff'); g.addColorStop(1, '#cbe3f6');
      ctx.fillStyle = g; ctx.fill();
      ctx.strokeStyle = PALETTE.ink; ctx.lineWidth = 4; ctx.stroke();

      text(ctx, `DAY ${r.day}`, px + pw / 2, py + 34, { size: 24, align: 'center', fill: PALETTE.ink });

      const rows = r.rows;
      rows.forEach((row, i) => {
        const y = py + 78 + i * 38;
        ctx.globalAlpha = row.revealed ? 1 : 0.18;
        text(ctx, row.label, px + 34, y, { size: 16, weight: 600, fill: '#3d4a5a' });
        text(ctx, row.render(row.shown), px + pw - 34, y, { size: 22, align: 'right', fill: PALETTE.ink });
        ctx.globalAlpha = 1;
      });

      const cy = py + ph - 76;
      ctx.beginPath();
      ctx.moveTo(px + 30, cy - 26); ctx.lineTo(px + pw - 30, cy - 26);
      ctx.strokeStyle = 'rgba(13,17,23,0.2)'; ctx.lineWidth = 2; ctx.stroke();
      text(ctx, 'EARNED', px + 34, cy, { size: 16, weight: 600, fill: '#3d4a5a' });
      text(ctx, `$${F(r.cashShown)}`, px + pw - 34, cy, { size: 34, align: 'right', fill: '#1f8a3f' });

      if (r.done) {
        ui.button(ctx, 'toShop', px + pw / 2 - 100, py + ph - 44, 200, 34, 'TO THE SHOP', { size: 16 });
      }
    },

    // ── SHOP ─────────────────────────────────────────────────────────────────────────
    // §2.9 — a wall of chunky buttons with the NEXT TIER'S STATS VISIBLE. One screen. No
    // submenus, no tooltips-only. You can always see what the next thing does and costs.
    shop(ctx, w, h, s) {
      const g = ctx.createLinearGradient(0, 0, 0, h);
      g.addColorStop(0, s.hangar ? '#2b3038' : '#3b3226');
      g.addColorStop(1, s.hangar ? '#171a1f' : '#221c14');
      ctx.fillStyle = g; ctx.fillRect(0, 0, w, h);

      // plank / panel wall texture, so it reads as a shed (LTF1) or a hangar (LTF2)
      ctx.globalAlpha = 0.10;
      ctx.strokeStyle = '#fff'; ctx.lineWidth = 2;
      for (let y = 0; y < h; y += s.hangar ? 26 : 44) {
        ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(w, y); ctx.stroke();
      }
      ctx.globalAlpha = 1;

      text(ctx, s.hangar ? 'THE HANGAR' : 'THE SHED', 32, 40, { size: 26, fill: '#fff' });
      text(ctx, `Day ${s.day}`, 32, 70, { size: 15, weight: 500, fill: 'rgba(255,255,255,0.6)' });

      // clickable money — medals 14 and 15 (§7.7). Twenty minutes, and exactly the kind of
      // detail that makes a remake read as loving rather than mechanical.
      const cashStr = `$${F(s.cash)}`;
      ctx.font = '800 34px system-ui, sans-serif';
      const cashW = ctx.measureText(cashStr).width;
      push('cash', w - 32 - cashW, 22, cashW, 40);
      text(ctx, cashStr, w - 32, 42, { size: 34, align: 'right', fill: ui.isHot('cash') ? '#ffe066' : PALETTE.gold });
      if (s.bp !== undefined) {
        text(ctx, `${F(s.bp)} BP`, w - 32, 74, { size: 18, align: 'right', fill: PALETTE.cyan });
      }

      // Columns follow the window rather than being fixed at two: on an ultrawide, two
      // columns give you 1,400-pixel-wide buttons and the whole screen reads as empty.
      const cols = Math.max(2, Math.min(4, Math.round(w / 520)));
      const cw = (w - 64 - 20 * (cols - 1)) / cols;
      const chh = 106;
      s.items.forEach((item, i) => {
        const cx = 32 + (i % cols) * (cw + 20);
        const cy = 104 + Math.floor(i / cols) * (chh + 14);
        const maxed = item.next === null;
        const afford = !maxed && item.next.price <= s.cash;
        push(`buy:${item.key}`, cx, cy, cw, chh, item);
        slab(ctx, cx, cy, cw, chh, {
          hot: ui.isHot(`buy:${item.key}`) && afford,
          disabled: maxed,
          tint: !maxed && !afford ? ['#d8dee6', '#aab3bf'] : null,
        });

        text(ctx, item.name, cx + 16, cy + 24, { size: 18, fill: PALETTE.ink });
        text(ctx, item.blurb, cx + 16, cy + 45, { size: 12, weight: 500, fill: '#4a5765' });

        // tier pips
        for (let k = 0; k < item.tiers; k++) {
          const on = k <= item.level;
          ctx.fillStyle = on ? (k === item.level ? '#1f8a3f' : '#2e3f52') : 'rgba(13,17,23,0.18)';
          roundRect(ctx, cx + 16 + k * 20, cy + 60, 15, 9, 3);
          ctx.fill();
        }

        text(ctx, `now: ${item.current}`, cx + 16, cy + 88, { size: 13, weight: 600, fill: '#3d4a5a' });
        if (maxed) {
          text(ctx, 'MAX', cx + cw - 16, cy + 76, { size: 20, align: 'right', fill: '#1f8a3f' });
        } else {
          // THE point of this screen: the next tier's actual value, always visible.
          text(ctx, `next: ${item.next.label}`, cx + cw - 16, cy + 62, { size: 14, align: 'right', fill: PALETTE.ink });
          text(ctx, `$${F(item.next.price)}`, cx + cw - 16, cy + 88, {
            size: 22, align: 'right', fill: afford ? '#1f8a3f' : '#b4483c',
          });
        }
      });

      const by = 104 + Math.ceil(s.items.length / cols) * (chh + 14) + 8;
      ui.button(ctx, 'nextDay', w / 2 - 130, Math.min(by, h - 62), 260, 48, 'NEXT DAY  →', { tint: ['#ffe9a8', '#f0c14b'] });
      if (s.goalFt) {
        text(ctx, `best ${F(s.bestFt)} ft  of  ${F(s.goalFt)} ft`, w / 2, Math.min(by, h - 62) + 66,
          { size: 14, weight: 600, align: 'center', fill: 'rgba(255,255,255,0.7)' });
      }
      ui.button(ctx, 'quit', 32, h - 46, 110, 32, 'MENU', { size: 14 });
      if (s.secretShop) ui.button(ctx, 'secret', w - 174, h - 46, 142, 32, 'psst...', { size: 14, tint: ['#5b3f7a', '#3a2750'] });
    },

    // ── SECRET SHOP (§7.7) ───────────────────────────────────────────────────────────
    secretShop(ctx, w, h, s) {
      ctx.fillStyle = 'rgba(13,10,20,0.93)'; ctx.fillRect(0, 0, w, h);
      ctx.save();
      ctx.translate(w * 0.18, h * 0.6);
      ctx.scale(150, -150);
      merchantPenguin(ctx, { t: s.t });
      ctx.restore();
      text(ctx, 'not a shop', w * 0.5, 70, { size: 30, align: 'center', fill: '#c9a7ff' });
      text(ctx, 'never was a shop', w * 0.5, 100, { size: 14, weight: 500, align: 'center', fill: 'rgba(255,255,255,0.45)' });

      s.items.forEach((item, i) => {
        const x = w * 0.38, y = 150 + i * 96;
        const owned = item.owned;
        const afford = s.bp >= item.price;
        push(`secret:${item.id}`, x, y, w * 0.5, 82);
        slab(ctx, x, y, w * 0.5, 82, { hot: ui.isHot(`secret:${item.id}`) && afford && !owned, disabled: owned, tint: ['#6b4f8f', '#3d2b57'] });
        text(ctx, item.name, x + 18, y + 26, { size: 20, fill: '#fff' });
        text(ctx, item.desc, x + 18, y + 50, { size: 12, weight: 500, fill: 'rgba(255,255,255,0.66)' });
        text(ctx, owned ? 'OWNED' : `${item.price} BP`, x + w * 0.5 - 18, y + 40,
          { size: 20, align: 'right', fill: owned ? '#9be5a8' : afford ? PALETTE.cyan : '#e08b8b' });
      });
      ui.button(ctx, 'closeSecret', w / 2 - 80, h - 62, 160, 40, 'BACK', { size: 16 });
    },

    // ── VICTORY / ENDING ─────────────────────────────────────────────────────────────
    // §6.5 — do not soften it. The iceberg is the correct ending, it is a verified fact
    // about the original, and it is the entire setup for the sequel and for §14.
    ending(ctx, w, h, s) {
      ctx.fillStyle = '#0d1117'; ctx.fillRect(0, 0, w, h);
      if (s.phase === 'cheer') {
        ctx.save();
        ctx.translate(w / 2, h / 2);
        ctx.scale(150, -150);
        penguinCapsule(ctx, { pose: 'cheer', t: s.t });
        ctx.restore();
        text(ctx, '6,000 FEET', w / 2, h / 2 - 150, { size: 52, align: 'center', fill: '#fff', stroke: PALETTE.ink });
      } else if (s.phase === 'berg') {
        // the pan, then the iceberg, then nothing
        const px = w * 0.5 - s.t * 160;
        ctx.save();
        ctx.translate(w * 0.9, h * 0.5);
        ctx.scale(2.4, -2.4);
        obstacleShape(ctx, 'iceberg', 120, 190, 0);
        ctx.restore();
        ctx.save();
        ctx.translate(px, h * 0.42);
        ctx.scale(90, -90);
        penguinCapsule(ctx, { pose: 'glide', t: s.t });
        ctx.restore();
      } else if (s.phase === 'black') {
        // nothing. Hold it.
      } else {
        text(ctx, "Penguins can't fly.", w / 2, h / 2, { size: 46, align: 'center', fill: '#fff' });
        if (s.t > 2.2) {
          text(ctx, `${F(s.days)} days   ·   ${F(s.bestFt)} ft   ·   $${F(s.earned)} earned`,
            w / 2, h / 2 + 56, { size: 17, weight: 500, align: 'center', fill: 'rgba(255,255,255,0.6)' });
          ui.button(ctx, 'post', w / 2 - 120, h / 2 + 110, 240, 44, 'CONTINUE', { size: 17 });
        }
      }
    },

    // ── MEDALS ───────────────────────────────────────────────────────────────────────
    medals(ctx, w, h, s) {
      ctx.fillStyle = '#141c26'; ctx.fillRect(0, 0, w, h);
      text(ctx, 'MEDALS', 32, 40, { size: 28, fill: '#fff' });
      text(ctx, `${s.unlocked} / ${s.all.length}   ·   ${F(s.bp)} BP earned`, 32, 70,
        { size: 14, weight: 500, fill: 'rgba(255,255,255,0.6)' });

      const cols = 3, cw = (w - 64 - 24) / cols, ch = 52;
      const perPage = Math.floor((h - 160) / (ch + 8)) * cols;
      const page = s.page || 0;
      const slice = s.all.slice(page * perPage, (page + 1) * perPage);
      slice.forEach((m, i) => {
        const x = 32 + (i % cols) * (cw + 12);
        const y = 100 + Math.floor(i / cols) * (ch + 8);
        const got = !!s.unlockedIds[m.id];
        roundRect(ctx, x, y, cw, ch, 8);
        ctx.fillStyle = got ? 'rgba(255,201,60,0.16)' : 'rgba(255,255,255,0.05)';
        ctx.fill();
        ctx.strokeStyle = got ? PALETTE.gold : 'rgba(255,255,255,0.14)';
        ctx.lineWidth = 2; ctx.stroke();
        ctx.beginPath(); ctx.arc(x + 24, y + ch / 2, 12, 0, 6.283);
        ctx.fillStyle = got ? PALETTE.gold : 'rgba(255,255,255,0.16)'; ctx.fill();
        text(ctx, m.name, x + 46, y + 19, { size: 14, fill: got ? '#fff' : 'rgba(255,255,255,0.55)' });
        text(ctx, m.condition, x + 46, y + 36, { size: 11, weight: 500, fill: 'rgba(255,255,255,0.42)' });
        if (got) text(ctx, `+${m.bp}`, x + cw - 12, y + ch / 2, { size: 13, align: 'right', fill: PALETTE.gold });
      });
      const pages = Math.ceil(s.all.length / perPage);
      if (pages > 1) {
        ui.button(ctx, 'medalPrev', w / 2 - 130, h - 52, 60, 34, '<', { size: 16 });
        text(ctx, `${page + 1} / ${pages}`, w / 2, h - 35, { size: 14, align: 'center', fill: '#fff' });
        ui.button(ctx, 'medalNext', w / 2 + 70, h - 52, 60, 34, '>', { size: 16 });
      }
      ui.button(ctx, 'closeMedals', 32, h - 52, 120, 34, 'BACK', { size: 15 });
    },

    /** A medal that just unlocked, sliding in from the right. */
    medalToast(ctx, w, h, toast) {
      const a = Math.min(1, toast.life * 3, toast.maxLife - toast.life > 0.2 ? 1 : 0);
      const x = w - 300 - 20 + (1 - Math.min(1, toast.life * 4)) * 320;
      ctx.globalAlpha = Math.max(0, Math.min(1, toast.life));
      roundRect(ctx, x, 90, 300, 62, 10);
      ctx.fillStyle = 'rgba(13,17,23,0.92)'; ctx.fill();
      ctx.strokeStyle = PALETTE.gold; ctx.lineWidth = 2; ctx.stroke();
      ctx.beginPath(); ctx.arc(x + 32, 121, 16, 0, 6.283);
      ctx.fillStyle = PALETTE.gold; ctx.fill();
      text(ctx, 'MEDAL', x + 60, 108, { size: 11, fill: PALETTE.gold });
      text(ctx, toast.name, x + 60, 128, { size: 17, fill: '#fff' });
      text(ctx, `+${toast.bp} BP`, x + 284, 121, { size: 15, align: 'right', fill: PALETTE.cyan });
      ctx.globalAlpha = 1;
      return a;
    },

    /** SIDE-BY-SIDE (§11) — the reference pane, plus how to fill it. */
    reference(ctx, w, h, s) {
      ctx.fillStyle = '#11161d'; ctx.fillRect(0, 0, w, h);
      text(ctx, 'THE ORIGINAL', w / 2, 40, { size: 20, align: 'center', fill: 'rgba(255,255,255,0.75)' });
      const lines = s.ready ? ['Ruffle is loaded. Fly both, compare, log it in CALIBRATION.md.'] : [
        'This pane runs the real SWF through Ruffle, at the same',
        'canvas size and scale as the remake, so they can be flown',
        'side by side (§11).',
        '',
        'It needs two files this project does not ship:',
        '',
        '   reference/ruffle/     the self-hosted Ruffle package',
        '   reference/ltf1.swf    sourced by you',
        '',
        'reference/README.md has the procedure and the six',
        'calibration points. Without the SWF, frame-step a longplay',
        'video instead — lower precision, same method.',
      ];
      lines.forEach((l, i) => text(ctx, l, 40, 96 + i * 24, { size: 14, weight: 500, fill: 'rgba(255,255,255,0.6)', font: 'ui-monospace, Consolas, monospace' }));
      ui.button(ctx, 'closeRef', 40, h - 52, 120, 34, 'BACK', { size: 15 });
    },

    /** Small persistent hint strip for capture mode and the debug keys (§15). */
    keyHints(ctx, w, h) {
      const s = 'F3 debug   F8 replay   F9 hide HUD   F10 side-by-side   [ ] slow-mo   C freecam   P pivots';
      text(ctx, s, w / 2, h - 12, { size: 11, weight: 500, align: 'center', fill: 'rgba(255,255,255,0.28)' });
    },
  };

  return ui;
}

/**
 * The counting-up results panel. Rows reveal in order — distance, altitude, speed, air time,
 * multiplier — and only then the cash total (§2.8). It emits ticks so audio can follow.
 */
export function createResults(pay, run, day, T) {
  const rows = [
    { label: 'Distance', target: pay.distFt, render: (v) => `${F(v)} ft` },
    { label: 'Max altitude', target: pay.altFt, render: (v) => `${F(v)} ft` },
    { label: 'Max speed', target: run.maxSpeed * T.MPH_PER_MS, render: (v) => `${F(v)} mph` },
    { label: 'Air time', target: run.airTime, render: (v) => `${v.toFixed(1)} s` },
    { label: 'Time multiplier', target: pay.mult, render: (v) => `x${v.toFixed(2)}` },
  ];
  for (const r of rows) { r.shown = 0; r.revealed = false; r.done = false; }

  let t = 0, i = 0, cashShown = 0, cashDone = false;
  const HOLD = 0.16, FILL = 0.5;

  return {
    day, rows,
    get cashShown() { return cashShown; },
    get slide() { return Math.min(1, t / 0.35); },
    get done() { return cashDone; },
    /** @returns {number} number of ticks to play this frame */
    update(dt) {
      t += dt;
      let ticks = 0;
      if (t < 0.35) return 0;
      if (i < rows.length) {
        const r = rows[i];
        r.revealed = true;
        const prev = r.shown;
        r.shown = Math.min(r.target, r.shown + (r.target / FILL) * dt);
        if (Math.floor(r.shown * 3) !== Math.floor(prev * 3)) ticks++;
        if (r.shown >= r.target - 1e-9) {
          r.shown = r.target; r.done = true;
          if (t > 0) { i++; t += HOLD; }
        }
      } else if (!cashDone) {
        cashShown = Math.min(pay.cash, cashShown + (pay.cash / 0.7) * dt);
        if (cashShown >= pay.cash - 1e-9) { cashShown = pay.cash; cashDone = true; }
        ticks++;
      }
      return ticks;
    },
    skip() {
      for (const r of rows) { r.shown = r.target; r.revealed = true; r.done = true; }
      i = rows.length; cashShown = pay.cash; cashDone = true; t = Math.max(t, 1);
    },
  };
}
