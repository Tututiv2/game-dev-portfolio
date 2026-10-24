// Pollution — the Factorio model, on a sphere.
//
// This replaces the 2D game's heat BAR with a heat MAP, and that is the whole
// point. A bar is a number you watch; a cloud is a thing you can see creeping
// across the ground toward something you would rather it did not reach. Same
// design law either way: the thing that makes money is the thing that gets you
// killed. But now it has a shape, and you can watch it happen.
//
// Drills emit into the cell they stand on. Every tick the field diffuses to
// neighbouring cells, decays a little, and is absorbed by whatever flora is
// growing there. Nests sitting inside the cloud eat it and send back what they
// are owed.
//
// The grid is the same cube-sphere as everything else, one level coarser than
// the build grid.

import { unitToUV, uvToUnit, FACES } from './cubesphere.js';
import { WORLD, scatterDensity, groveDensity } from './terrain.js';

// Coarser than it looks like it should be, for a measured reason. Spread
// distance goes as cell * sqrt(DIFFUSE * t / TICK), so the cell size sets the
// reach far more than the diffusion rate does — the rate is capped at 1 and the
// cell size is not. At 18.4 m the cloud plateaued at 146 m and could not touch a
// nest 170 m away, so the entire mechanic never fired. 36.8 m doubles the reach
// for less work, and pollution has no business being sharper than that anyway.
export const POLLUTION_LEVEL = 7;                 // ~36.8 m cells
const N = 1 << POLLUTION_LEVEL;
const CELL_ARC = (Math.PI / 2) * WORLD.radius / N;

export const TICK = 0.2;          // seconds between simulation steps
const DIFFUSE = 0.34;             // fraction handed to neighbours per tick
// Decay is deliberately small next to diffusion — that ratio is what lets a
// cloud travel instead of evaporating where it is made. Flora is the real sink.
const DECAY = 0.004;              // fraction lost per tick regardless
const ABSORB_FLORA = 0.030;       // extra loss per tick in dense forest
const EPSILON = 0.02;             // below this a cell is dropped entirely

// Cells pack into one integer: face (3 bits) | row (8) | col (8). Integer keys
// keep the Map fast; string keys showed up in the profile immediately.
function pack(face, col, row) { return (face << 16) | (row << 8) | col; }
function unpackFace(k) { return k >>> 16; }
function unpackRow(k) { return (k >>> 8) & 0xff; }
function unpackCol(k) { return k & 0xff; }

const _uv = {};
const _d = { x: 0, y: 0, z: 0 };
const _a = { x: 0, y: 0, z: 0 };
const _b = { x: 0, y: 0, z: 0 };

function cellOf(x, y, z) {
  unitToUV(x, y, z, _uv);
  const col = Math.min(N - 1, Math.max(0, (_uv.u * N) | 0));
  const row = Math.min(N - 1, Math.max(0, (_uv.v * N) | 0));
  return pack(_uv.face, col, row);
}

function cellDir(key, out) {
  const face = FACES[unpackFace(key)];
  uvToUnit(face, (unpackCol(key) + 0.5) / N, (unpackRow(key) + 0.5) / N, out);
  return out;
}

export class Pollution {
  constructor() {
    this.cells = new Map();       // packed key -> amount
    this.meta = new Map();        // packed key -> { dir, absorb, nbr }
    this.acc = 0;
    this.total = 0;
    this.peak = 0;
    // Counts simulation steps, so the renderer can tell whether the field
    // actually changed and skip rebuilding the GPU window if it did not.
    this.stepCount = 0;
    this.cellArc = CELL_ARC;
  }

  // Cached per-cell facts. Worth caching because the same cells stay active for
  // as long as you are parked, and groveDensity is a 6-octave noise.
  metaOf(key) {
    let m = this.meta.get(key);
    if (m) return m;
    const d = cellDir(key, { x: 0, y: 0, z: 0 });
    // Forest scrubs the air. Parking in a grove buys you quiet; the trade is
    // that groves are where the flora glows, so it is not free cover.
    const absorb = ABSORB_FLORA * groveDensity(d.x, d.y, d.z);
    m = { dir: d, absorb, nbr: null };
    this.meta.set(key, m);
    return m;
  }

  // The four neighbours of a cell.
  //
  // Inside a face that is just col/row +/- 1. Across a face edge it is not:
  // the neighbouring face has its own orientation, and hand-tabulating the 24
  // edge adjacencies is exactly the kind of typed-in table that goes wrong
  // silently. So at an edge we step along the surface by one cell of arc and
  // ask which cell we landed in. Slower, correct, and it can't drift.
  neighbours(key) {
    const m = this.metaOf(key);
    if (m.nbr) return m.nbr;

    const face = unpackFace(key), col = unpackCol(key), row = unpackRow(key);
    const interior = col > 0 && col < N - 1 && row > 0 && row < N - 1;
    if (interior) {
      m.nbr = [pack(face, col - 1, row), pack(face, col + 1, row),
               pack(face, col, row - 1), pack(face, col, row + 1)];
      return m.nbr;
    }

    const f = FACES[face];
    const du = 1 / N;
    const u = (col + 0.5) / N, v = (row + 0.5) / N;
    const out = [];
    for (const [ou, ov] of [[-du, 0], [du, 0], [0, -du], [0, du]]) {
      uvToUnit(f, Math.min(1, Math.max(0, u + ou)), Math.min(1, Math.max(0, v + ov)), _d);
      // Clamping the uv keeps us on this face; step the resulting direction a
      // little further to actually cross onto the neighbour.
      cellDir(key, _a);
      const dx = _d.x - _a.x, dy = _d.y - _a.y, dz = _d.z - _a.z;
      const len = Math.hypot(dx, dy, dz) || 1;
      const s = (CELL_ARC / WORLD.radius) / len;
      _b.x = _a.x + dx * s; _b.y = _a.y + dy * s; _b.z = _a.z + dz * s;
      const bl = Math.hypot(_b.x, _b.y, _b.z) || 1;
      out.push(cellOf(_b.x / bl, _b.y / bl, _b.z / bl));
    }
    m.nbr = out;
    return out;
  }

  // Emit at a direction on the sphere.
  emit(dir, amount) {
    if (amount <= 0) return;
    const key = cellOf(dir.x, dir.y, dir.z);
    this.cells.set(key, (this.cells.get(key) || 0) + amount);
  }

  amountAt(dir) {
    return this.cells.get(cellOf(dir.x, dir.y, dir.z)) || 0;
  }

  amountAtKey(key) { return this.cells.get(key) || 0; }

  // Fixed-step so the spread rate does not depend on frame rate.
  update(dt) {
    this.acc += dt;
    let steps = 0;
    while (this.acc >= TICK && steps < 4) {
      this.acc -= TICK;
      this.step();
      steps++;
    }
    if (this.acc > TICK * 4) this.acc = 0;         // tab-switch clamp
  }

  step() {
    this.stepCount++;
    const src = this.cells;
    if (src.size === 0) { this.total = 0; this.peak = 0; return; }

    const next = new Map();
    const share = DIFFUSE / 4;
    for (const [key, amt] of src) {
      const keep = amt * (1 - DIFFUSE);
      next.set(key, (next.get(key) || 0) + keep);
      const give = amt * share;
      if (give > EPSILON * 0.1) {
        const nb = this.neighbours(key);
        for (let i = 0; i < 4; i++) {
          next.set(nb[i], (next.get(nb[i]) || 0) + give);
        }
      } else {
        // Too little to bother spreading; keep it where it is so it can decay.
        next.set(key, (next.get(key) || 0) + give * 4);
      }
    }

    let total = 0, peak = 0;
    for (const [key, amt] of next) {
      const m = this.metaOf(key);
      const left = amt * (1 - DECAY - m.absorb);
      if (left < EPSILON) { next.delete(key); continue; }
      next.set(key, left);
      total += left;
      if (left > peak) peak = left;
    }

    this.cells = next;
    this.total = total;
    this.peak = peak;

    // Cells go quiet but their cached facts stay valid; only prune when the
    // cache has grown far past what is live.
    if (this.meta.size > this.cells.size * 8 + 4096) {
      for (const k of this.meta.keys()) if (!this.cells.has(k)) this.meta.delete(k);
    }
  }

  clear() {
    this.cells.clear();
    this.total = 0;
    this.peak = 0;
  }

  // How far the cloud reaches from a point, in metres. Used by the HUD, and by
  // anything that wants to know whether it is about to touch something.
  radiusFrom(dir, threshold = 0.3) {
    let maxArc = 0;
    for (const [key, amt] of this.cells) {
      if (amt < threshold) continue;
      const d = this.metaOf(key).dir;
      const dot = Math.min(1, Math.max(-1, d.x * dir.x + d.y * dir.y + d.z * dir.z));
      const arc = Math.acos(dot) * WORLD.radius;
      if (arc > maxArc) maxArc = arc;
    }
    return maxArc;
  }
}
