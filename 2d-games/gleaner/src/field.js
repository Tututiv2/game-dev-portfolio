// The wheat field: infinite, chunked, and cut permanently.
//
// Two separate stores, on purpose. `cut` is a Uint8Array per chunk and is kept
// FOREVER (1KB each — a long run is a few hundred KB). `canv` is the rendered
// canvas and is dropped once you drive away from it. Come back and the chunk is
// rebuilt from its cut mask, so the swath you carved an hour ago is still there.
import { CFG, COL } from './config.js';
import { hash2, mulberry32, TAU } from './util.js';

const N = CFG.chunkCells;
const CS = CFG.cell;
const PX = N * CS;

export class Field {
  constructor() {
    this.cut = new Map();   // "cx,cy" -> Uint8Array(N*N)
    this.canv = new Map();  // "cx,cy" -> canvas
    this.budget = 0;
  }

  key(cx, cy) { return cx + ',' + cy; }

  cutMask(cx, cy) {
    const k = this.key(cx, cy);
    let m = this.cut.get(k);
    if (m === undefined) { m = new Uint8Array(N * N); this.cut.set(k, m); }
    return m;
  }

  /** Marks one world-space cell as cut. Returns 1 if it was standing wheat. */
  markCell(wx, wy) {
    const gx = Math.floor(wx / CS), gy = Math.floor(wy / CS);
    const cx = Math.floor(gx / N), cy = Math.floor(gy / N);
    const lx = gx - cx * N, ly = gy - cy * N;
    const mask = this.cutMask(cx, cy);
    const i = ly * N + lx;
    if (mask[i]) return 0;
    mask[i] = 1;

    const c = this.canv.get(this.key(cx, cy));
    if (c) this.paintStubble(c.getContext('2d'), lx, ly, gx, gy);
    return 1;
  }

  /** Reaped ground: the light has been taken out of it. */
  paintStubble(g, lx, ly, gx, gy) {
    const x = lx * CS, y = ly * CS;
    const r = hash2(gx, gy);
    g.fillStyle = r > 0.5 ? COL.spent : COL.spentLo;
    g.fillRect(x, y, CS, CS);

    // broken stumps of crystal, dark now
    g.fillStyle = 'rgba(18,14,26,0.6)';
    for (let i = 0; i < 3; i++) {
      const h = hash2(gx * 7 + i, gy * 13 - i);
      g.fillRect(x + h * (CS - 2), y + hash2(gy + i, gx - i) * (CS - 2), 1.6, 2.4);
    }
    // a last ember at the cut face, cooling
    g.fillStyle = `rgba(255,154,60,${0.05 + r * 0.09})`;
    g.fillRect(x, y, CS, 1.6);
  }

  /** Wheat, decoration, then every cell that has already been cut. */
  buildChunk(cx, cy) {
    const c = document.createElement('canvas');
    c.width = PX; c.height = PX;
    const g = c.getContext('2d');
    const rng = mulberry32(((cx & 0xffff) << 16) ^ (cy & 0xffff) ^ 0x9e37);

    g.fillStyle = COL.field;
    g.fillRect(0, 0, PX, PX);

    // Low-frequency ripeness patches. Sampled from the 3x3 chunk neighbourhood
    // so a blob straddling a chunk edge is drawn identically on both sides —
    // otherwise every chunk boundary reads as a visible seam.
    for (let oy = -1; oy <= 1; oy++) {
      for (let ox = -1; ox <= 1; ox++) {
        const nr = mulberry32((((cx + ox) & 0xffff) << 16) ^ ((cy + oy) & 0xffff) ^ 0x51ed);
        for (let i = 0; i < 18; i++) {
          const x = nr() * PX + ox * PX, y = nr() * PX + oy * PX, r = 70 + nr() * 150;
          const up = nr() > 0.5;
          if (x + r < 0 || x - r > PX || y + r < 0 || y - r > PX) continue;
          const gr = g.createRadialGradient(x, y, 0, x, y, r);
          gr.addColorStop(0, up ? 'rgba(240,206,116,0.30)' : 'rgba(166,122,38,0.22)');
          gr.addColorStop(1, 'rgba(0,0,0,0)');
          g.fillStyle = gr;
          g.beginPath(); g.arc(x, y, r, 0, TAU); g.fill();
        }
      }
    }

    // Growth banding, in world space so it runs unbroken across chunk edges.
    // The Goldsea grew in slow tidal lines, like rock strata seen from above.
    g.strokeStyle = 'rgba(120,86,26,0.10)';
    g.lineWidth = 2;
    const originY = cy * PX;
    for (let y = Math.ceil(originY / 34) * 34 - originY; y < PX; y += 34) {
      g.beginPath(); g.moveTo(0, y); g.lineTo(PX, y); g.stroke();
    }

    // Shards. Angular slivers with a lit tip rather than soft stalks — this is
    // crystal that grew, not a crop that was planted.
    const shards = 1500;
    for (let i = 0; i < shards; i++) {
      const x = rng() * PX, y = rng() * PX;
      const t = rng();
      const h = 4 + rng() * 5.5;
      const lean = (rng() - 0.5) * 3.4;
      g.fillStyle = t > 0.72 ? COL.fieldHi : t > 0.26 ? COL.field : COL.fieldLo;
      g.beginPath();
      g.moveTo(x - 1.5, y + 3);
      g.lineTo(x + lean, y - h);
      g.lineTo(x + 1.6, y + 3);
      g.closePath(); g.fill();
      if (t > 0.90) {   // a facet catching the light — rare, or the field fizzes
        g.fillStyle = 'rgba(255,246,214,0.6)';
        g.beginPath();
        g.moveTo(x + lean, y - h);
        g.lineTo(x + lean - 0.9, y - h + 2.6);
        g.lineTo(x + lean + 0.9, y - h + 2.4);
        g.closePath(); g.fill();
      }
    }

    // Rare blooms — a knot of shards that grew tall and holds more light.
    for (let i = 0; i < 5; i++) {
      if (rng() > 0.45) continue;
      const x = rng() * PX, y = rng() * PX;
      const gl = g.createRadialGradient(x, y, 0, x, y, 26);
      gl.addColorStop(0, 'rgba(255,240,190,0.5)');
      gl.addColorStop(0.5, 'rgba(255,209,120,0.22)');
      gl.addColorStop(1, 'rgba(255,209,120,0)');
      g.fillStyle = gl;
      g.beginPath(); g.arc(x, y, 26, 0, TAU); g.fill();
      for (let k = 0; k < 9; k++) {
        const a = rng() * TAU, rr = rng() * 11;
        const bx = x + Math.cos(a) * rr, by = y + Math.sin(a) * rr;
        g.fillStyle = COL.fieldHi;
        g.beginPath();
        g.moveTo(bx - 1.8, by + 4); g.lineTo(bx + (rng() - 0.5) * 3, by - 11 - rng() * 5);
        g.lineTo(bx + 1.9, by + 4);
        g.closePath(); g.fill();
      }
    }

    this.decorate(g, cx, cy, rng);

    const mask = this.cut.get(this.key(cx, cy));
    if (mask) {
      for (let ly = 0; ly < N; ly++)
        for (let lx = 0; lx < N; lx++)
          if (mask[ly * N + lx]) this.paintStubble(g, lx, ly, cx * N + lx, cy * N + ly);
    }
    return c;
  }

  /**
   * Ruins. The Goldsea grew up through a civilisation and kept its shape, so
   * what is scattered across the field is architecture, not geology — that one
   * choice is most of what sells "three thousand years later".
   * Deterministic: a rebuilt chunk is identical to the one it replaced.
   */
  decorate(g, cx, cy, rng) {
    const h = hash2(cx, cy);
    const shadow = (x, y, rx, ry) => {
      g.fillStyle = 'rgba(14,8,24,0.34)';
      g.beginPath(); g.ellipse(x + 5, y + 6, rx, ry, 0, 0, TAU); g.fill();
    };

    if (h > 0.78) {   // toppled column, half swallowed
      const x = 70 + rng() * (PX - 140), y = 70 + rng() * (PX - 140);
      const a = rng() * TAU, L = 34 + rng() * 40;
      g.save(); g.translate(x, y); g.rotate(a);
      shadow(0, 0, L * 0.55, 12);
      g.fillStyle = '#b9b099';
      g.beginPath(); g.roundRect(-L / 2, -9, L, 18, 4); g.fill();
      g.fillStyle = 'rgba(255,246,214,0.28)';
      g.beginPath(); g.roundRect(-L / 2, -9, L, 6, 3); g.fill();
      // fluting
      g.strokeStyle = 'rgba(70,64,54,0.45)'; g.lineWidth = 1.2;
      for (let i = 1; i < 5; i++) {
        const px = -L / 2 + (L * i) / 5;
        g.beginPath(); g.moveTo(px, -8); g.lineTo(px, 8); g.stroke();
      }
      // a broken end, and crystal growing out of the break
      g.fillStyle = '#8d8574';
      g.beginPath(); g.ellipse(L / 2, 0, 3.5, 9, 0, 0, TAU); g.fill();
      g.fillStyle = COL.fieldHi;
      for (let i = 0; i < 4; i++) {
        const oy = -6 + i * 4;
        g.beginPath();
        g.moveTo(L / 2, oy); g.lineTo(L / 2 + 6 + rng() * 5, oy + 1.5); g.lineTo(L / 2, oy + 3);
        g.closePath(); g.fill();
      }
      g.restore();
    }

    if (h < 0.14) {   // standing arch, still holding
      const x = 80 + rng() * (PX - 160), y = 80 + rng() * (PX - 160);
      shadow(x, y, 34, 22);
      g.strokeStyle = '#a89f88'; g.lineWidth = 9; g.lineCap = 'butt';
      g.beginPath(); g.arc(x, y + 10, 26, Math.PI * 1.08, Math.PI * 1.92); g.stroke();
      g.strokeStyle = 'rgba(255,246,214,0.22)'; g.lineWidth = 3;
      g.beginPath(); g.arc(x, y + 8, 26, Math.PI * 1.12, Math.PI * 1.88); g.stroke();
      g.fillStyle = '#8d8574';
      g.fillRect(x - 30, y + 4, 10, 14);
      g.fillRect(x + 20, y + 4, 10, 14);
      // keystone, still lit after all this time
      g.fillStyle = COL.glowWarm;
      g.beginPath(); g.arc(x, y - 16, 3.4, 0, TAU); g.fill();
    }

    if (h > 0.42 && h < 0.50) {   // a buried wall line running off the chunk
      const y = 40 + rng() * (PX - 80);
      const drift = (rng() - 0.5) * 22;
      g.strokeStyle = 'rgba(150,142,122,0.55)'; g.lineWidth = 7;
      g.beginPath(); g.moveTo(0, y); g.lineTo(PX, y + drift); g.stroke();
      g.strokeStyle = 'rgba(30,24,40,0.4)'; g.lineWidth = 1.4;
      g.beginPath(); g.moveTo(0, y + 4); g.lineTo(PX, y + drift + 4); g.stroke();
      // course lines, so it reads as masonry not a stripe
      for (let x = 6; x < PX; x += 22) {
        const yy = y + (drift * x) / PX;
        g.strokeStyle = 'rgba(60,54,44,0.35)'; g.lineWidth = 1.2;
        g.beginPath(); g.moveTo(x, yy - 3.5); g.lineTo(x, yy + 3.5); g.stroke();
      }
    }

    if (h > 0.30 && h < 0.345) {  // a face, looking up out of the field
      const x = 90 + rng() * (PX - 180), y = 90 + rng() * (PX - 180);
      shadow(x, y, 24, 17);
      g.fillStyle = '#bdb49d';
      g.beginPath(); g.ellipse(x, y, 20, 15, rng() * 0.6 - 0.3, 0, TAU); g.fill();
      g.fillStyle = 'rgba(255,246,214,0.25)';
      g.beginPath(); g.ellipse(x - 5, y - 4, 11, 7, 0, 0, TAU); g.fill();
      g.fillStyle = 'rgba(46,38,58,0.75)';
      g.beginPath(); g.ellipse(x - 6, y - 1, 3.4, 2, 0, 0, TAU); g.fill();
      g.beginPath(); g.ellipse(x + 6, y - 1, 3.4, 2, 0, 0, TAU); g.fill();
      g.strokeStyle = 'rgba(46,38,58,0.6)'; g.lineWidth = 1.6;
      g.beginPath(); g.moveTo(x - 5, y + 8); g.lineTo(x + 5, y + 7); g.stroke();
    }
  }

  /** Returns the chunk canvas, building it if there is budget left this frame. */
  chunkCanvas(cx, cy) {
    const k = this.key(cx, cy);
    let c = this.canv.get(k);
    if (c === undefined) {
      if (this.budget <= 0) return null;
      this.budget--;
      c = this.buildChunk(cx, cy);
      this.canv.set(k, c);
    }
    return c;
  }

  /** Drops canvases we have driven away from. Cut masks stay. */
  prune(camX, camY) {
    const ccx = camX / PX, ccy = camY / PX;
    const R = CFG.chunkKeepRadius;
    for (const k of this.canv.keys()) {
      const p = k.indexOf(',');
      const cx = +k.slice(0, p), cy = +k.slice(p + 1);
      if (Math.abs(cx + 0.5 - ccx) > R || Math.abs(cy + 0.5 - ccy) > R) this.canv.delete(k);
    }
  }

  /**
   * Sweeps the header from its previous pose to its current one and cuts
   * everything under it. Sub-stepped so a frame hitch cannot leave a gap.
   */
  harvest(px, py, pang, x, y, ang, halfW, forwardOff) {
    const dx = x - px, dy = y - py;
    const steps = Math.max(1, Math.ceil(Math.hypot(dx, dy) / (CS * 0.7)));
    let cells = 0;
    for (let s = 1; s <= steps; s++) {
      const t = s / steps;
      const a = pang + (ang - pang) * t;
      const cxp = px + dx * t + Math.cos(a) * forwardOff;
      const cyp = py + dy * t + Math.sin(a) * forwardOff;
      const nx = -Math.sin(a), ny = Math.cos(a); // header runs across the heading
      for (let o = -halfW; o <= halfW; o += CS * 0.7) {
        cells += this.markCell(cxp + nx * o, cyp + ny * o);
      }
    }
    return cells;
  }

  draw(g, cam, vw, vh) {
    this.budget = CFG.chunkBudget;
    const x0 = Math.floor((cam.x - vw / 2) / PX), x1 = Math.floor((cam.x + vw / 2) / PX);
    const y0 = Math.floor((cam.y - vh / 2) / PX), y1 = Math.floor((cam.y + vh / 2) / PX);
    for (let cy = y0; cy <= y1; cy++) {
      for (let cx = x0; cx <= x1; cx++) {
        const c = this.chunkCanvas(cx, cy);
        if (c) g.drawImage(c, cx * PX, cy * PX);
        else { g.fillStyle = COL.field; g.fillRect(cx * PX, cy * PX, PX, PX); }
      }
    }
  }
}
