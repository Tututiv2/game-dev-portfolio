/* ============================================================================
   TREE GEOMETRY TOOLKIT — shared by the eleven hand-built candidates and by the
   breeder. Deliberately global (this is a classic script, concatenated), so
   both can use it without a module system that would not survive file://.

   Everything here obeys the shipping contract: one BufferGeometry, base y=0,
   tip y=1, widest radius exactly 1.0, no UVs, no textures, and not one call to
   Math.random().
   ========================================================================= */

/* ------------------------------------------------------------ determinism */
function rnd(seed) {
  var a = seed >>> 0;
  return function () {
    a = (a + 0x6D2B79F5) | 0;
    var t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/* --------------------------------------------------------- vector helpers */
function sub(a, b) { return [a[0] - b[0], a[1] - b[1], a[2] - b[2]]; }
function add(a, b) { return [a[0] + b[0], a[1] + b[1], a[2] + b[2]]; }
function mul(a, s) { return [a[0] * s, a[1] * s, a[2] * s]; }
function crs(a, b) {
  return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
}
function nrm(a) {
  var l = Math.hypot(a[0], a[1], a[2]) || 1;
  return [a[0] / l, a[1] / l, a[2] / l];
}
function lerp3(a, b, t) {
  return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];
}
function cl01(x) { return x < 0 ? 0 : x > 1 ? 1 : x; }
function lerp(a, b, t) { return a + (b - a) * t; }

/* ==========================================================================
   MESH BUILDER
   Non-indexed triangles with an explicit normal per vertex, so each surface
   picks flat or smooth for itself. At <= 400 triangles the duplication is
   1,200 vertices for an entire family — nothing.
   ========================================================================= */
function MB() { return { p: [], n: [], m: [] }; }
function tris(b) { return b.p.length / 9; }

// aPart may be one [mat, ao] pair for a whole face, or one pair per vertex.
function isPair(x) { return typeof x[0] === 'number'; }
function atP(X, i) { return isPair(X) ? X : X[i]; }
var BARK = function (ao) { return [0, ao]; };
var LEAF = function (ao) { return [1, ao]; };

function tri(b, A, B, C, M, N) {
  var nA, nB, nC;
  if (N) { nA = N[0]; nB = N[1]; nC = N[2]; }
  else { nA = nB = nC = nrm(crs(sub(B, A), sub(C, A))); }
  var V = [A, B, C], NN = [nA, nB, nC];
  for (var i = 0; i < 3; i++) {
    b.p.push(V[i][0], V[i][1], V[i][2]);
    b.n.push(NN[i][0], NN[i][1], NN[i][2]);
    var m = atP(M, i);
    b.m.push(m[0], m[1]);
  }
}
function quad(b, A, B, C, D, M, N) {
  var m4 = isPair(M) ? [M, M, M, M] : M;
  tri(b, A, B, C, [m4[0], m4[1], m4[2]], N ? [N[0], N[1], N[2]] : null);
  tri(b, A, C, D, [m4[0], m4[2], m4[3]], N ? [N[0], N[2], N[3]] : null);
}

/* SWEPT TUBE, parallel-transport frames — no twisting, and it works on a spine
   that wanders in all three axes. opt.lobe(i, s, ang) multiplies the radius per
   side, which is what turns a round trunk into a fluted one.
   signature: tube(b, pts, rad, sides, matFn, opt) */
function tube(b, pts, rad, sides, matFn, opt) {
  opt = opt || {};
  var N = pts.length, i, s;
  var tan = [];
  for (i = 0; i < N; i++) {
    tan.push(nrm(sub(pts[Math.min(N - 1, i + 1)], pts[Math.max(0, i - 1)])));
  }
  var ref = Math.abs(tan[0][1]) > 0.9 ? [1, 0, 0] : [0, 1, 0];
  var up = nrm(crs(tan[0], ref));
  var rings = [], rn = [];
  for (i = 0; i < N; i++) {
    var t = tan[i];
    var d = up[0] * t[0] + up[1] * t[1] + up[2] * t[2];
    up = nrm([up[0] - t[0] * d, up[1] - t[1] * d, up[2] - t[2] * d]);
    var bi = nrm(crs(t, up));
    var rp = [], rr = [];
    for (s = 0; s < sides; s++) {
      var ang = (s / sides) * Math.PI * 2 + (opt.phase || 0);
      var ca = Math.cos(ang), sa = Math.sin(ang);
      var dir = nrm([up[0] * ca + bi[0] * sa, up[1] * ca + bi[1] * sa, up[2] * ca + bi[2] * sa]);
      var r = rad[i] * (opt.lobe ? opt.lobe(i, s, ang) : 1);
      rp.push([pts[i][0] + dir[0] * r, pts[i][1] + dir[1] * r, pts[i][2] + dir[2] * r]);
      rr.push(dir);
    }
    rings.push(rp); rn.push(rr);
  }
  for (i = 0; i < N - 1; i++) {
    for (s = 0; s < sides; s++) {
      var s2 = (s + 1) % sides;
      quad(b, rings[i][s], rings[i + 1][s], rings[i + 1][s2], rings[i][s2],
        [matFn(i, s), matFn(i + 1, s), matFn(i + 1, s2), matFn(i, s2)],
        opt.flat ? null : [rn[i][s], rn[i + 1][s], rn[i + 1][s2], rn[i][s2]]);
    }
  }
  return rings;
}

/* JITTERED POLYHEDRON. The displacement is hashed from the vertex POSITION,
   never its index — PolyhedronGeometry is non-indexed, so a shared corner
   appears three times and hashing the index tears the solid open. Straight out
   of objectShader.js's boulder, which paid for that lesson already. */
var ICO = new THREE.IcosahedronGeometry(1, 0);   // 20 tris
var OCT = new THREE.OctahedronGeometry(1, 0);    //  8 tris
function blob(b, src, c, s, seed, jit, matFn) {
  var p = src.attributes.position.array;
  for (var i = 0; i < p.length; i += 9) {
    var P = [], M = [];
    for (var k = 0; k < 3; k++) {
      var x = p[i + k * 3], y = p[i + k * 3 + 1], z = p[i + k * 3 + 2];
      var h = Math.sin(x * 127.1 + y * 311.7 + z * 74.7 + seed * 7.13) * 43758.5453;
      var j = 1 - jit + 2 * jit * (h - Math.floor(h));
      var v = [c[0] + x * s[0] * j, c[1] + y * s[1] * j, c[2] + z * s[2] * j];
      P.push(v); M.push(matFn(v));
    }
    tri(b, P[0], P[1], P[2], M);
  }
}

/* NORMALISE TO THE SHIPPING CONTRACT, then hand back a BufferGeometry.
   Base to y=0, tip to y=1, widest radius to exactly 1.0 — enforced in code
   rather than trusted, because "about 1.0 by eye" is how a family ends up
   buried or floating once the instance matrix scales it by (r, h, r). Normals
   are carried through the non-uniform scale by its inverse, or every one of
   these gets lit as though it were a differently-shaped tree. */
function finish(b) {
  var i, minY = 1e9, maxY = -1e9, maxR = 0;
  for (i = 0; i < b.p.length; i += 3) {
    var y = b.p[i + 1];
    if (y < minY) minY = y;
    if (y > maxY) maxY = y;
    var r = Math.hypot(b.p[i], b.p[i + 2]);
    if (r > maxR) maxR = r;
  }
  var sy = 1 / Math.max(1e-6, maxY - minY);
  var sxz = 1 / Math.max(1e-6, maxR);
  for (i = 0; i < b.p.length; i += 3) {
    b.p[i] *= sxz; b.p[i + 1] = (b.p[i + 1] - minY) * sy; b.p[i + 2] *= sxz;
    var nx = b.n[i] / sxz, ny = b.n[i + 1] / sy, nz = b.n[i + 2] / sxz;
    var l = Math.hypot(nx, ny, nz) || 1;
    b.n[i] = nx / l; b.n[i + 1] = ny / l; b.n[i + 2] = nz / l;
  }
  var g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(b.p, 3));
  g.setAttribute('normal', new THREE.Float32BufferAttribute(b.n, 3));
  g.setAttribute('aPart', new THREE.Float32BufferAttribute(b.m, 2));
  return g;
}
