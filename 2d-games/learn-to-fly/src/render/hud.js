// §6.4. THE ONLY PLACE IN THE PROJECT THAT CONVERTS UNITS.
//
// The sim is metres, seconds, radians everywhere (§4.2). Feet and mph are born here, at the
// moment of drawing, and die here. Landmine 5: let them leak into sim/ and you can no longer
// tell whether a wrong number is a physics bug or a conversion bug, and tuning becomes
// unfalsifiable.

import { PALETTE } from './placeholder.js';

const F = (n, d = 0) => n.toLocaleString(undefined, { minimumFractionDigits: d, maximumFractionDigits: d });

function panel(ctx, x, y, w, h, r = 10) {
  ctx.beginPath();
  ctx.roundRect(x, y, w, h, r);
  ctx.fillStyle = 'rgba(13,17,23,0.55)';
  ctx.fill();
  ctx.strokeStyle = 'rgba(255,255,255,0.16)';
  ctx.lineWidth = 1.5;
  ctx.stroke();
}

export function createHud(T) {
  let shownDist = 0;

  return {
    reset() { shownDist = 0; },

    /**
     * @param {object} v  { p, loadout, day, cash, bestDistFt, mode, goalFt, obstacles }
     */
    draw(ctx, viewW, viewH, v, dt) {
      const p = v.p;
      const mph = p.speed * T.MPH_PER_MS;
      const altFt = Math.max(0, p.y - T.SEA_LEVEL_Y) * T.FT_PER_M;
      const distFt = Math.max(0, p.dist) * T.FT_PER_M;
      const ceilingMph = v.loadout.glider.vMaxMph;

      shownDist += (distFt - shownDist) * Math.min(1, dt * 12);

      ctx.save();
      ctx.textBaseline = 'middle';

      // ── top-left: day and cash ──
      panel(ctx, 14, 14, 190, 62);
      ctx.font = '700 20px system-ui, sans-serif';
      ctx.textAlign = 'left';
      ctx.fillStyle = '#fff';
      ctx.fillText(`Day ${v.day}`, 28, 34);
      ctx.font = '700 22px system-ui, sans-serif';
      ctx.fillStyle = PALETTE.gold;
      ctx.fillText(`$${F(v.cash)}`, 28, 60);

      // ── top-right: best distance, with a flag ──
      if (v.bestDistFt > 0) {
        const w = 210;
        panel(ctx, viewW - w - 14, 14, w, 44);
        ctx.font = '600 13px system-ui, sans-serif';
        ctx.textAlign = 'right';
        ctx.fillStyle = 'rgba(255,255,255,0.65)';
        ctx.fillText('BEST', viewW - 28, 28);
        ctx.font = '700 20px system-ui, sans-serif';
        ctx.fillStyle = '#fff';
        ctx.fillText(`${F(v.bestDistFt)} ft`, viewW - 28, 48);
      }

      // ── goal bar (LTF1) or obstacle strip (LTF2 story) ──
      if (v.goalFt) {
        const bw = Math.min(360, viewW * 0.34);
        const bx = (viewW - bw) * 0.5, by = 22;
        ctx.fillStyle = 'rgba(13,17,23,0.45)';
        ctx.beginPath(); ctx.roundRect(bx, by, bw, 12, 6); ctx.fill();
        ctx.fillStyle = PALETTE.cyan;
        ctx.beginPath(); ctx.roundRect(bx, by, bw * Math.min(1, distFt / v.goalFt), 12, 6); ctx.fill();
        if (v.bestDistFt > 0) {
          const mx = bx + bw * Math.min(1, v.bestDistFt / v.goalFt);
          ctx.strokeStyle = '#fff'; ctx.lineWidth = 2;
          ctx.beginPath(); ctx.moveTo(mx, by - 4); ctx.lineTo(mx, by + 16); ctx.stroke();
        }
        ctx.font = '600 12px system-ui, sans-serif';
        ctx.textAlign = 'center';
        ctx.fillStyle = 'rgba(255,255,255,0.8)';
        ctx.fillText(`${F(v.goalFt)} ft`, bx + bw + 34, by + 6);
      }

      // ── centre-bottom: distance so far, large and semi-transparent ──
      if (distFt > 1) {
        ctx.font = '800 62px system-ui, sans-serif';
        ctx.textAlign = 'center';
        ctx.globalAlpha = 0.30;
        ctx.fillStyle = '#fff';
        ctx.strokeStyle = PALETTE.ink; ctx.lineWidth = 6;
        ctx.strokeText(`${F(shownDist)} ft`, viewW * 0.5, viewH - 116);
        ctx.fillText(`${F(shownDist)} ft`, viewW * 0.5, viewH - 116);
        ctx.globalAlpha = 1;
      }

      // ── bottom-left: the speed dial ──
      // The arc's maximum IS the current glider's ceiling, so the dial RESCALES as you
      // upgrade. That is why the community's advice is "keep it about halfway up your dial"
      // rather than naming an mph — halfway stays meaningful at every tier (§4.5).
      const cx = 84, cy = viewH - 104, R = 56;
      const a0 = Math.PI * 0.78, a1 = Math.PI * 2.22;
      ctx.beginPath(); ctx.arc(cx, cy, R + 8, a0, a1);
      ctx.lineTo(cx, cy); ctx.closePath();
      ctx.fillStyle = 'rgba(13,17,23,0.55)'; ctx.fill();

      const overFrac = 1;                                   // redline starts at the ceiling
      ctx.beginPath(); ctx.arc(cx, cy, R, a0, a1);
      ctx.strokeStyle = 'rgba(255,255,255,0.25)'; ctx.lineWidth = 8; ctx.stroke();
      ctx.beginPath(); ctx.arc(cx, cy, R, a0 + (a1 - a0) * 0.86 * overFrac, a1);
      ctx.strokeStyle = p.stressed ? '#ff5b4a' : 'rgba(255,91,74,0.55)'; ctx.lineWidth = 8; ctx.stroke();

      for (let i = 0; i <= 8; i++) {
        const a = a0 + (a1 - a0) * (i / 8);
        ctx.beginPath();
        ctx.moveTo(cx + Math.cos(a) * (R - 9), cy + Math.sin(a) * (R - 9));
        ctx.lineTo(cx + Math.cos(a) * (R - 2), cy + Math.sin(a) * (R - 2));
        ctx.strokeStyle = 'rgba(255,255,255,0.5)'; ctx.lineWidth = 2; ctx.stroke();
      }
      const needleA = a0 + (a1 - a0) * Math.min(1.06, mph / (ceilingMph * 1.16));
      ctx.beginPath();
      ctx.moveTo(cx - Math.cos(needleA) * 8, cy - Math.sin(needleA) * 8);
      ctx.lineTo(cx + Math.cos(needleA) * (R - 6), cy + Math.sin(needleA) * (R - 6));
      ctx.strokeStyle = p.stressed ? '#ff5b4a' : '#fff';
      ctx.lineWidth = 3.5; ctx.lineCap = 'round'; ctx.stroke();
      ctx.beginPath(); ctx.arc(cx, cy, 5, 0, 6.283);
      ctx.fillStyle = '#fff'; ctx.fill();

      ctx.font = '800 22px system-ui, sans-serif';
      ctx.textAlign = 'center';
      ctx.fillStyle = '#fff';
      ctx.fillText(`${F(mph)}`, cx, cy + 26);
      ctx.font = '600 11px system-ui, sans-serif';
      ctx.fillStyle = 'rgba(255,255,255,0.6)';
      ctx.fillText('MPH', cx, cy + 42);

      // wing integrity, only once it starts to matter
      if (p.gliderHp < 0.999 && v.loadout.glider.lift > 0) {
        const bw = 96;
        ctx.fillStyle = 'rgba(13,17,23,0.6)';
        ctx.fillRect(cx - bw / 2, cy + 52, bw, 7);
        ctx.fillStyle = p.gliderHp > 0.4 ? PALETTE.gold : '#ff5b4a';
        ctx.fillRect(cx - bw / 2, cy + 52, bw * p.gliderHp, 7);
      }

      // ── bottom-centre: the fuel bar, one segment per second, draining right to left ──
      if (v.loadout.fuelSeconds > 0) {
        const segs = Math.max(1, Math.round(v.loadout.fuelSeconds * 2));
        const sw = 15, gap = 4;
        const tw = segs * sw + (segs - 1) * gap;
        const fx = (viewW - tw) * 0.5, fy = viewH - 42;
        const filled = (p.fuel / v.loadout.fuelSeconds) * segs;
        for (let i = 0; i < segs; i++) {
          const on = segs - i <= filled;
          ctx.fillStyle = on ? (p.thrusting ? PALETTE.flameHot : PALETTE.flame) : 'rgba(13,17,23,0.5)';
          ctx.beginPath();
          ctx.roundRect(fx + i * (sw + gap), fy, sw, 16, 3);
          ctx.fill();
          ctx.strokeStyle = 'rgba(255,255,255,0.22)'; ctx.lineWidth = 1; ctx.stroke();
        }
        ctx.font = '600 11px system-ui, sans-serif';
        ctx.textAlign = 'center';
        ctx.fillStyle = 'rgba(255,255,255,0.7)';
        ctx.fillText('FUEL', viewW * 0.5, fy - 10);
      }

      // ── bottom-right: altitude, and the resistance gauge directly under it ──
      // They live together because the player reads them together (§6.4).
      const rx = viewW - 128, ry = viewH - 150;
      panel(ctx, rx - 14, ry - 12, 128, 138);
      const barH = 76;
      ctx.fillStyle = 'rgba(255,255,255,0.18)';
      ctx.fillRect(rx, ry, 14, barH);
      const altFrac = Math.min(1, altFt / 1200);
      ctx.fillStyle = PALETTE.cyan;
      ctx.fillRect(rx, ry + barH * (1 - altFrac), 14, barH * altFrac);
      ctx.strokeStyle = 'rgba(255,255,255,0.35)'; ctx.lineWidth = 1;
      ctx.strokeRect(rx, ry, 14, barH);

      ctx.textAlign = 'left';
      ctx.font = '800 20px system-ui, sans-serif';
      ctx.fillStyle = '#fff';
      ctx.fillText(`${F(altFt)}`, rx + 24, ry + 14);
      ctx.font = '600 11px system-ui, sans-serif';
      ctx.fillStyle = 'rgba(255,255,255,0.6)';
      ctx.fillText('FT ALT', rx + 24, ry + 30);

      // THE resistance gauge. §4.5: whatever this reads, optimal glide must read 3-4%, and
      // check 8 asserts exactly that in Node. It reads ~0 in a clean glide and climbs fast
      // when you over-pitch, which is what makes it an instrument rather than a second
      // speedometer — and it is the units the community's own strategy advice is phrased in.
      const pct = p.resistance * 100;
      const good = pct >= 2 && pct <= 5;
      ctx.font = '800 20px system-ui, sans-serif';
      ctx.fillStyle = good ? '#7fe38a' : pct > 9 ? '#ff5b4a' : '#fff';
      ctx.fillText(`${pct.toFixed(1)}%`, rx, ry + barH + 22);
      ctx.font = '600 11px system-ui, sans-serif';
      ctx.fillStyle = 'rgba(255,255,255,0.6)';
      ctx.fillText('RESISTANCE', rx, ry + barH + 38);
      const gw = 100;
      ctx.fillStyle = 'rgba(255,255,255,0.18)';
      ctx.fillRect(rx, ry + barH + 46, gw, 6);
      ctx.fillStyle = good ? '#7fe38a' : pct > 9 ? '#ff5b4a' : PALETTE.gold;
      ctx.fillRect(rx, ry + barH + 46, gw * Math.min(1, pct / 15), 6);
      ctx.fillStyle = 'rgba(255,255,255,0.55)';           // the 3-4% target window
      ctx.fillRect(rx + gw * (3 / 15), ry + barH + 44, 1, 10);
      ctx.fillRect(rx + gw * (4 / 15), ry + barH + 44, 1, 10);

      ctx.restore();
    },

    /** F3 — the debug overlay (§15). Reads the SAME fields the flight model computes. */
    debug(ctx, viewW, viewH, v, loopStats) {
      const p = v.p;
      const rows = [
        ['state', p.state],
        ['x / y (m)', `${p.x.toFixed(1)} / ${p.y.toFixed(1)}`],
        ['speed', `${p.speed.toFixed(2)} m/s   ${(p.speed * T.MPH_PER_MS).toFixed(0)} mph`],
        ['alpha', `${(p.alpha * 180 / Math.PI).toFixed(1)} deg`],
        ['theta', `${(p.theta * 180 / Math.PI).toFixed(1)} deg`],
        ['Cd / Cl', `${p.cd.toFixed(5)} / ${p.cl.toFixed(3)}`],
        ['resistance', `${(p.resistance * 100).toFixed(2)} %`],
        ['glide L/D', v.glideRatio.toFixed(2)],
        ['gliderHp', p.gliderHp.toFixed(3)],
        ['fuel', `${p.fuel.toFixed(2)} s`],
        ['airTime', `${p.airTime.toFixed(2)} s`],
        ['bounces', String(p.bounces)],
        ['fps / steps', `${loopStats.fps.toFixed(0)} / ${loopStats.steps}`],
        ['frame ms', loopStats.frameMs.toFixed(2)],
      ];
      const w = 250, h = rows.length * 17 + 16;
      ctx.save();
      ctx.fillStyle = 'rgba(0,0,0,0.72)';
      ctx.fillRect(viewW - w - 10, 70, w, h);
      ctx.font = '12px ui-monospace, Consolas, monospace';
      ctx.textBaseline = 'middle';
      rows.forEach(([k, val], i) => {
        ctx.fillStyle = 'rgba(255,255,255,0.55)';
        ctx.textAlign = 'left';
        ctx.fillText(k, viewW - w, 86 + i * 17);
        ctx.fillStyle = '#8fe3ff';
        ctx.textAlign = 'right';
        ctx.fillText(String(val), viewW - 18, 86 + i * 17);
      });
      ctx.restore();
    },
  };
}
