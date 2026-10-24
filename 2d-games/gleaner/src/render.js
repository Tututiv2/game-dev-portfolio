import { CFG, COL, HARDPOINTS } from './config.js';
import { HV_ART, drawReel, angleIndex } from './art.js';
import { TAU, lerp, clamp } from './util.js';

export class Renderer {
  constructor(canvas) {
    this.canvas = canvas;
    this.g = canvas.getContext('2d', { alpha: false });
    this.dpr = 1;
    this.vw = 0; this.vh = 0;
    this.vignette = null;
    this.resize();
    addEventListener('resize', () => this.resize());
  }

  resize() {
    const dpr = Math.min(devicePixelRatio || 1, CFG.maxDpr);
    this.dpr = dpr;
    this.vw = innerWidth;
    this.vh = innerHeight;
    this.canvas.width = Math.floor(this.vw * dpr);
    this.canvas.height = Math.floor(this.vh * dpr);
    this.canvas.style.width = this.vw + 'px';
    this.canvas.style.height = this.vh + 'px';

    const g = this.g;
    const r = Math.hypot(this.vw, this.vh) / 2;
    const v = g.createRadialGradient(this.vw / 2, this.vh / 2, r * 0.52, this.vw / 2, this.vh / 2, r);
    v.addColorStop(0, 'rgba(0,0,0,0)');
    v.addColorStop(1, 'rgba(24,12,0,0.5)');
    this.vignette = v;
  }

  get viewRadius() { return Math.hypot(this.vw, this.vh) / 2; }

  updateCamera(cam, hv, dt) {
    const tx = hv.x + Math.cos(hv.ang) * CFG.camLead * (0.35 + hv.throttle * 0.65);
    const ty = hv.y + Math.sin(hv.ang) * CFG.camLead * (0.35 + hv.throttle * 0.65);
    const k = Math.min(1, dt * CFG.camLerp);
    cam.x = lerp(cam.x, tx, k);
    cam.y = lerp(cam.y, ty, k);
  }

  /** Screen point -> world point. */
  toWorld(cam, sx, sy, out) {
    out.x = cam.x + (sx - this.vw / 2);
    out.y = cam.y + (sy - this.vh / 2);
    return out;
  }

  draw(w) {
    const g = this.g;
    g.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
    g.fillStyle = COL.field;
    g.fillRect(0, 0, this.vw, this.vh);

    const shakeX = (Math.random() - 0.5) * w.shake;
    const shakeY = (Math.random() - 0.5) * w.shake;

    g.save();
    g.translate(this.vw / 2 + shakeX, this.vh / 2 + shakeY);
    g.translate(-w.cam.x, -w.cam.y);

    w.field.draw(g, w.cam, this.vw + 40, this.vh + 40);
    this.drawNoiseRing(g, w);
    this.drawSwarm(g, w);
    this.drawHarvester(g, w);
    w.fx.draw(g);
    this.drawPlacement(g, w);

    g.restore();

    g.fillStyle = this.vignette;
    g.fillRect(0, 0, this.vw, this.vh);
  }

  /**
   * The song, made visible. Everything inside this can hear you.
   * Three staggered waves rather than one — a choir, not a siren.
   */
  drawNoiseRing(g, w) {
    const hv = w.hv, R = hv.noise;
    const a = 0.10 + hv.throttle * 0.16;

    const grd = g.createRadialGradient(hv.x, hv.y, R * 0.70, hv.x, hv.y, R);
    grd.addColorStop(0, 'rgba(127,232,220,0)');
    grd.addColorStop(1, `rgba(127,232,220,${a * 0.5})`);
    g.fillStyle = grd;
    g.beginPath(); g.arc(hv.x, hv.y, R, 0, TAU); g.fill();

    g.strokeStyle = `rgba(168,244,234,${a + 0.14})`;
    g.lineWidth = 2;
    g.setLineDash([16, 13]);
    g.lineDashOffset = -w.time * 40;
    g.beginPath(); g.arc(hv.x, hv.y, R, 0, TAU); g.stroke();
    g.setLineDash([]);

    // the waves themselves, faster and brighter the harder the choir is driven
    for (let i = 0; i < 3; i++) {
      const p = ((w.time * CFG.noise.ringPulse + i / 3) % 1);
      const pr = lerp(R * 0.16, R, p);
      g.strokeStyle = `rgba(200,250,244,${(1 - p) * (0.07 + hv.throttle * 0.26)})`;
      g.lineWidth = 2.4;
      g.beginPath(); g.arc(hv.x, hv.y, pr, 0, TAU); g.stroke();
    }
  }

  drawSwarm(g, w) {
    const s = w.swarm, sprites = w.sprites;
    const lx = w.cam.x - this.vw / 2 - 60, rx = w.cam.x + this.vw / 2 + 60;
    const ty = w.cam.y - this.vh / 2 - 60, by = w.cam.y + this.vh / 2 + 60;
    const t2 = (w.time * 11) | 0;

    for (let i = 0; i < s.n; i++) {
      if (!s.alive[i]) continue;
      const x = s.x[i], y = s.y[i];
      if (x < lx || x > rx || y < ty || y > by) continue;
      const sp = sprites[s.type[i]];
      const ai = angleIndex(Math.atan2(s.vy[i], s.vx[i]), sp.angles);
      const fi = (t2 + i) & 1;
      const half = sp.size / 2;
      g.drawImage(sp.canvas, ai * sp.size, fi * sp.size, sp.size, sp.size,
                  x - half, y - half, sp.size, sp.size);

      if (s.burn[i] > 0) {
        g.globalAlpha = Math.min(0.85, s.burn[i] / 22);
        g.fillStyle = '#ff8a2a';
        g.beginPath(); g.arc(x, y - 2, 4 + Math.random() * 3, 0, TAU); g.fill();
        g.globalAlpha = 1;
      }
    }
  }

  drawHarvester(g, w) {
    const hv = w.hv;
    g.save();
    g.translate(hv.x, hv.y);
    g.rotate(hv.ang);

    // dust and chaff thrown out behind the header while cutting
    g.drawImage(w.hvArt, -HV_ART.ox, -HV_ART.oy);

    // the vessel: reaped light, pooling. It glows brighter as it fills, so how
    // rich you are is legible from the machine itself.
    const fill = clamp(w.grain / CFG.hv.tankVisual, 0, 1);
    if (fill > 0) {
      const h = 62 * fill, top = 31 - h;
      const lv = g.createLinearGradient(0, top, 0, 31);
      lv.addColorStop(0, `rgba(255,244,206,${0.42 + fill * 0.34})`);
      lv.addColorStop(1, `rgba(255,176,72,${0.34 + fill * 0.3})`);
      g.fillStyle = lv;
      g.fillRect(-70, top, 64, h);
      g.fillStyle = 'rgba(255,255,240,0.8)';
      g.fillRect(-70, top, 64, 1.6);          // the surface, catching light

      // ribs over the fill so it reads as a vessel and not a painted panel
      g.strokeStyle = 'rgba(20,40,46,0.5)'; g.lineWidth = 1.6;
      for (let i = 1; i < 4; i++) {
        const x = -70 + (64 * i) / 4;
        g.beginPath(); g.moveTo(x, -31); g.lineTo(x, 31); g.stroke();
      }
      g.strokeStyle = 'rgba(160,124,60,0.9)'; g.lineWidth = 1.6;
      g.strokeRect(-70.5, -31.5, 64, 62);
    }

    drawReel(g, hv.reel, hv.throttle);
    w.towers.draw(g, hv, w.time);
    g.restore();

    // The resonator throws off motes as it drives the choir — the clearest
    // read on the lever short of the gauge itself.
    if (Math.random() < 0.2 + hv.throttle * 0.8) {
      const ex = hv.x + Math.cos(hv.ang) * 2 - Math.sin(hv.ang) * -39;
      const ey = hv.y + Math.sin(hv.ang) * 2 + Math.cos(hv.ang) * -39;
      const back = { x: -Math.cos(hv.ang) * hv.speed * 0.3, y: -Math.sin(hv.ang) * hv.speed * 0.3 };
      w.fx.smoke(ex, ey, hv.throttle > 0.6 ? 'rgba(255,232,180,0.85)' : 'rgba(200,224,220,0.5)',
                 2 + hv.throttle * 4, back);
    }
  }

  /** Hardpoint picker: only shown while a tower is selected. */
  drawPlacement(g, w) {
    if (w.selected < 0) return;
    const hv = w.hv, ts = w.towers;
    const p = { x: 0, y: 0 };
    const def = w.towerDefs[w.selected];
    const afford = w.grain >= ts.costOf(w.selected);

    for (let s = 0; s < HARDPOINTS.length; s++) {
      ts.worldOf(hv, s, p);
      const occupied = !!ts.mounted[s];
      const hot = s === w.hoverSlot;

      if (hot && !occupied && afford) {
        g.fillStyle = 'rgba(120,220,160,0.10)';
        g.beginPath(); g.arc(p.x, p.y, def.range, 0, TAU); g.fill();
        g.strokeStyle = 'rgba(150,240,190,0.6)';
        g.lineWidth = 2;
        g.setLineDash([9, 7]);
        g.beginPath(); g.arc(p.x, p.y, def.range, 0, TAU); g.stroke();
        g.setLineDash([]);
      }

      g.lineWidth = hot ? 3 : 2;
      g.strokeStyle = occupied ? 'rgba(255,120,90,0.75)'
                    : !afford ? 'rgba(230,200,150,0.35)'
                    : hot ? 'rgba(150,255,190,0.95)' : 'rgba(240,225,190,0.6)';
      g.beginPath(); g.arc(p.x, p.y, hot ? 15 : 12, 0, TAU); g.stroke();
      if (!occupied && afford) {
        g.beginPath();
        g.moveTo(p.x - 5, p.y); g.lineTo(p.x + 5, p.y);
        g.moveTo(p.x, p.y - 5); g.lineTo(p.x, p.y + 5);
        g.stroke();
      }
    }
  }
}
