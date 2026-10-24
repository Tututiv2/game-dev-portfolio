/* ============================================================================
   SIMPLEX NOISE — a FAITHFUL JavaScript port of the GLSL in glsl/noise.glsl.js.

   WHY THIS FILE HAS TO EXIST, AND WHY IT HAS TO MATCH EXACTLY:

   The terrain you SEE is drawn by the GPU. The terrain you STAND ON is computed
   by the CPU. Binding rule 3 says there is exactly one height function — if
   these two disagree by even a little, you sink through the ground, or hover
   above it, or a mountain is solid air. "Physics == visuals" is not a slogan
   here, it is a numerical claim, and `tests/conformance.mjs` checks it against
   the real GPU every run.

   So this is not "a simplex noise". It is Ashima's exact algorithm, operation
   for operation, because a *different* good noise function would produce a
   different world. Do not tidy it. Do not swap it for something faster.

   On precision: GLSL runs this in 32-bit floats and JavaScript in 64-bit. The
   permutation arithmetic stays under 2^24 so it is exact in both, and the
   gradient maths differs only in the last bits — parts per million on a height
   of kilometres, i.e. millimetres. The conformance test asserts the bound.
   ========================================================================= */

const mod289 = (x) => x - Math.floor(x * (1.0 / 289.0)) * 289.0;
const permute = (x) => mod289(((x * 34.0) + 1.0) * x);
const taylorInvSqrt = (r) => 1.79284291400159 - 0.85373472095314 * r;
const step = (edge, x) => (x < edge ? 0 : 1);

export function snoise(vx, vy, vz) {
  const C_x = 1.0 / 6.0, C_y = 1.0 / 3.0;

  // First corner
  const s = (vx + vy + vz) * C_y;
  let ix = Math.floor(vx + s), iy = Math.floor(vy + s), iz = Math.floor(vz + s);
  const t0 = (ix + iy + iz) * C_x;
  const x0x = vx - ix + t0, x0y = vy - iy + t0, x0z = vz - iz + t0;

  // Other corners
  const gx = step(x0y, x0x), gy = step(x0z, x0y), gz = step(x0x, x0z);
  const lx = 1.0 - gx, ly = 1.0 - gy, lz = 1.0 - gz;
  const i1x = Math.min(gx, lz), i1y = Math.min(gy, lx), i1z = Math.min(gz, ly);
  const i2x = Math.max(gx, lz), i2y = Math.max(gy, lx), i2z = Math.max(gz, ly);

  const x1x = x0x - i1x + C_x, x1y = x0y - i1y + C_x, x1z = x0z - i1z + C_x;
  const x2x = x0x - i2x + C_y, x2y = x0y - i2y + C_y, x2z = x0z - i2z + C_y;
  const x3x = x0x - 0.5, x3y = x0y - 0.5, x3z = x0z - 0.5;

  // Permutations
  ix = mod289(ix); iy = mod289(iy); iz = mod289(iz);
  const pz0 = permute(iz + 0.0), pz1 = permute(iz + i1z);
  const pz2 = permute(iz + i2z), pz3 = permute(iz + 1.0);
  const py0 = permute(pz0 + iy + 0.0), py1 = permute(pz1 + iy + i1y);
  const py2 = permute(pz2 + iy + i2y), py3 = permute(pz3 + iy + 1.0);
  const p0 = permute(py0 + ix + 0.0), p1 = permute(py1 + ix + i1x);
  const p2 = permute(py2 + ix + i2x), p3 = permute(py3 + ix + 1.0);

  // Gradients: 7x7 points over a square, mapped onto an octahedron.
  // ns = n_ * D.wyz - D.xzx  with D = (0.0, 0.5, 1.0, 2.0), so
  //   D.wyz = (2.0, 0.5, 1.0) and D.xzx = (0.0, 1.0, 0.0).
  // Getting ns.x wrong (subtracting 1.0 here) still produces plausible-looking
  // noise, just a DIFFERENT one — which would have meant the ground you stand
  // on quietly disagreeing with the ground you see.
  const n_ = 0.142857142857;                       // 1/7
  const ns_x = n_ * 2.0 - 0.0;
  const ns_y = n_ * 0.5 - 1.0;
  const ns_z = n_ * 1.0 - 0.0;

  const grad = (p, xx, yy, zz) => {
    // INTEGER math on purpose. The GLSL writes this as
    //   j = p - 49*floor(p*ns.z*ns.z);  x_ = floor(j*ns.z);  y_ = floor(j-7*x_)
    // which relies on 1/7 being stored as a 32-bit float — where it rounds UP,
    // so floor(7 * ns.z) == 1. JavaScript stores it as a 64-bit float, where the
    // same decimal rounds DOWN, so floor(7 * ns.z) == 0 and every seventh
    // gradient index fell through to y_ = 7, off the end of the 7x7 table.
    // The result was gradients up to 4.9x too long, noise ranging +-4.2 instead
    // of +-1, and ridged terrain computing pow(negative, 2.46) = NaN — holes in
    // the world you would fall straight through.
    // p is an exact integer in both languages (permute stays under 2^24), so
    // doing this exactly sidesteps the whole precision question.
    const j = p % 49;
    const x_ = Math.floor(j / 7);
    const y_ = j - 7 * x_;
    let x = x_ * ns_x + ns_y;
    let y = y_ * ns_x + ns_y;
    const h = 1.0 - Math.abs(x) - Math.abs(y);
    const s0x = Math.floor(x) * 2.0 + 1.0, s0y = Math.floor(y) * 2.0 + 1.0;
    // The threshold is -1e-4, NOT 0, and that is the whole ball game.
    // Seven of the forty-nine (x_, y_) pairs give |x| + |y| == 1 exactly
    // (0.5+0.5, 0.786+0.214, 0.929+0.071, 0.643+0.357), so h lands on zero and
    // the fold branch is decided purely by which way the rounding error fell —
    // one way in the GPU's 32-bit floats, the other in JavaScript's 64-bit.
    // That put 32% of samples on different gradients. Nudging the threshold
    // below any achievable rounding error, but far above zero, makes the branch
    // identical everywhere. The smallest genuinely-nonzero |h| is about 0.07,
    // so 1e-4 has four orders of magnitude of headroom in both directions.
    const shv = (h <= -1e-4) ? -1.0 : 0.0;           // -step(h, -1e-4)
    let gxx = x + s0x * shv;
    let gyy = y + s0y * shv;
    let gzz = h;
    const norm = taylorInvSqrt(gxx * gxx + gyy * gyy + gzz * gzz);
    gxx *= norm; gyy *= norm; gzz *= norm;
    return gxx * xx + gyy * yy + gzz * zz;
  };

  const m0 = Math.max(0.6 - (x0x * x0x + x0y * x0y + x0z * x0z), 0.0);
  const m1 = Math.max(0.6 - (x1x * x1x + x1y * x1y + x1z * x1z), 0.0);
  const m2 = Math.max(0.6 - (x2x * x2x + x2y * x2y + x2z * x2z), 0.0);
  const m3 = Math.max(0.6 - (x3x * x3x + x3y * x3y + x3z * x3z), 0.0);
  const q0 = m0 * m0 * m0 * m0, q1 = m1 * m1 * m1 * m1;
  const q2 = m2 * m2 * m2 * m2, q3 = m3 * m3 * m3 * m3;

  return 42.0 * (
    q0 * grad(p0, x0x, x0y, x0z) +
    q1 * grad(p1, x1x, x1y, x1z) +
    q2 * grad(p2, x2x, x2y, x2z) +
    q3 * grad(p3, x3x, x3y, x3z)
  );
}

/* ---- fBm / ridged, matching fbmB / ridgedB with the band-limit OFF --------
   The GPU band-limits octaves by on-screen pixel size (uEps). Physics must not:
   what you stand on cannot depend on where the camera is. So the CPU always
   evaluates every octave, and the GPU converges to this as uEps -> 0, which is
   exactly the condition the conformance test uses.
   Gain 0.40 — see DECISIONS.md; it is not the textbook 0.50 and that matters. */
export function fbm(px, py, pz, oct) {
  let s = 0, a = 1, f = 1, m = 0;
  for (let i = 0; i < oct; i++) {
    s += a * snoise(px * f, py * f, pz * f);
    m += a; a *= 0.40; f *= 2.03;
  }
  return s / Math.max(m, 1e-4);
}

export function ridged(px, py, pz, sharp, oct) {
  let s = 0, a = 1, f = 1, m = 0;
  for (let i = 0; i < oct; i++) {
    // max(..., 0) is NOT decorative. Simplex slightly overshoots +-1, so
    // 1 - |noise| goes a little negative, and pow(negative, 2.46) is NaN in JS
    // and UNDEFINED in GLSL. Either way it is a hole in the terrain.
    const v = Math.max(1.0 - Math.abs(snoise(px * f, py * f, pz * f)), 0.0);
    s += a * Math.pow(v, sharp);
    m += a; a *= 0.40; f *= 2.11;
  }
  return s / Math.max(m, 1e-4);
}

/* ---- Worley / cellular, matching worley() + crackMask() ------------------
   The cell hash is built from permute(), NOT from a multiply/fract hash. A
   fract hash extracts entropy from the low mantissa bits, and there are 24 of
   those on the GPU against 53 here, so the two disagree by construction — which
   moved every crack boundary and left the ground out of step with its picture.
   Everything below stays under 2^24, where both float sizes are exact. */
function hash33(ix, iy, iz, out) {
  const a = permute(permute(permute(mod289(ix)) + mod289(iy)) + mod289(iz));
  const b = permute(permute(permute(mod289(ix + 37)) + mod289(iy + 91)) + mod289(iz + 13));
  const c = permute(a + b);
  out[0] = a / 289.0; out[1] = b / 289.0; out[2] = c / 289.0;
  return out;
}

const _h = [0, 0, 0];
export function crackMask(px, py, pz, width) {
  const ipx = Math.floor(px), ipy = Math.floor(py), ipz = Math.floor(pz);
  const fpx = px - ipx, fpy = py - ipy, fpz = pz - ipz;
  let f1 = 9.0, f2 = 9.0;
  for (let z = -1; z <= 1; z++)
    for (let y = -1; y <= 1; y++)
      for (let x = -1; x <= 1; x++) {
        hash33(ipx + x, ipy + y, ipz + z, _h);
        const dx = x + _h[0] - fpx, dy = y + _h[1] - fpy, dz = z + _h[2] - fpz;
        const dd = dx * dx + dy * dy + dz * dz;
        if (dd < f1) { f2 = f1; f1 = dd; }
        else if (dd < f2) { f2 = dd; }
      }
  const e = Math.sqrt(f2) - Math.sqrt(f1);
  const t = Math.min(Math.max(e / Math.max(width, 1e-6), 0), 1);
  return 1.0 - t * t * (3 - 2 * t);          // 1 - smoothstep(0, width, e)
}

/* ---- domain warping, matching warp1 / warp2 ------------------------------
   NOTE the weights: warp1/warp2 in GLSL call the hand-unrolled `fbm2`, which
   still uses the ORIGINAL 0.6667/0.3333 split, not the 0.40 gain that fbmB
   switched to. Mirroring the 0.40 version here would have produced continents
   in a subtly different place from the ones being drawn. */
function fbm2(px, py, pz) {
  return snoise(px, py, pz) * 0.6667
       + snoise(px * 2.03, py * 2.03, pz * 2.03) * 0.3333;
}

export function warp1(p, amp, out) {
  const wx = fbm2(p[0], p[1], p[2]);
  const wy = fbm2(p[0] + 5.2, p[1] + 1.3, p[2] + 2.8);
  const wz = fbm2(p[0] + 1.7, p[1] + 9.2, p[2] + 3.4);
  out[0] = p[0] + amp * wx; out[1] = p[1] + amp * wy; out[2] = p[2] + amp * wz;
  return out;
}

const _w1 = [0, 0, 0];
export function warp2(p, amp, out) {
  warp1(p, amp, _w1);
  const wx = fbm2(_w1[0] + 8.3, _w1[1] + 2.8, _w1[2] + 4.4);
  const wy = fbm2(_w1[0] + 3.1, _w1[1] + 6.6, _w1[2] + 7.7);
  const wz = fbm2(_w1[0] + 9.8, _w1[1] + 4.1, _w1[2] + 1.2);
  out[0] = p[0] + amp * wx; out[1] = p[1] + amp * wy; out[2] = p[2] + amp * wz;
  return out;
}

export const smoothstep = (a, b, x) => {
  const t = Math.min(Math.max((x - a) / (b - a), 0), 1);
  return t * t * (3 - 2 * t);
};
export const sat = (x) => (x < 0 ? 0 : x > 1 ? 1 : x);
