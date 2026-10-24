// Hex grid using axial coordinates (q, r). The playfield is a hexagon of radius
// GRID_RADIUS (radius 3 = 37 cells, spans 7 across the middle row -> "7x7" feel).
// Pointy-top hexes. Six-neighbor adjacency.

export const GRID_RADIUS = 5; // radius 5 = 91 cells, spans 11 across (Stage 2 expansion)
export const HEX_SIZE = 38; // pixel radius of a hex (center to corner), base zoom

export const HEX_DIRS = [
  { q: 1, r: 0 },
  { q: 1, r: -1 },
  { q: 0, r: -1 },
  { q: -1, r: 0 },
  { q: -1, r: 1 },
  { q: 0, r: 1 },
];

export function key(q, r) {
  return q + "," + r;
}

// Is (q,r) inside the hexagon playfield?
export function inBounds(q, r) {
  const s = -q - r;
  return Math.max(Math.abs(q), Math.abs(r), Math.abs(s)) <= GRID_RADIUS;
}

// All valid cells, ordered for stable iteration.
export function allCells() {
  const cells = [];
  for (let q = -GRID_RADIUS; q <= GRID_RADIUS; q++) {
    for (let r = -GRID_RADIUS; r <= GRID_RADIUS; r++) {
      if (inBounds(q, r)) cells.push({ q, r });
    }
  }
  return cells;
}

// Hex (axial) distance between two cells.
export function hexDistance(aq, ar, bq, br) {
  return (Math.abs(aq - bq) + Math.abs(ar - br) + Math.abs(aq + ar - bq - br)) / 2;
}

export function neighbors(q, r) {
  const out = [];
  for (const d of HEX_DIRS) {
    const nq = q + d.q;
    const nr = r + d.r;
    if (inBounds(nq, nr)) out.push({ q: nq, r: nr });
  }
  return out;
}

// Axial -> pixel (pointy-top). Returns offset from grid origin (0,0 axial).
export function axialToPixel(q, r, size = HEX_SIZE) {
  const x = size * Math.sqrt(3) * (q + r / 2);
  const y = size * 1.5 * r;
  return { x, y };
}

// Pixel -> nearest axial cell (pointy-top), with cube rounding.
export function pixelToAxial(x, y, size = HEX_SIZE) {
  const q = ((Math.sqrt(3) / 3) * x - (1 / 3) * y) / size;
  const r = ((2 / 3) * y) / size;
  return cubeRound(q, r);
}

function cubeRound(q, r) {
  let x = q;
  let z = r;
  let y = -x - z;
  let rx = Math.round(x);
  let ry = Math.round(y);
  let rz = Math.round(z);
  const dx = Math.abs(rx - x);
  const dy = Math.abs(ry - y);
  const dz = Math.abs(rz - z);
  if (dx > dy && dx > dz) rx = -ry - rz;
  else if (dy > dz) ry = -rx - rz;
  else rz = -rx - ry;
  return { q: rx, r: rz };
}

// Corner points of a pointy-top hex centered at (cx, cy).
export function hexCorners(cx, cy, size = HEX_SIZE) {
  const pts = [];
  for (let i = 0; i < 6; i++) {
    const ang = (Math.PI / 180) * (60 * i - 30);
    pts.push({ x: cx + size * Math.cos(ang), y: cy + size * Math.sin(ang) });
  }
  return pts;
}
