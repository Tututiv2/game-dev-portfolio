/* ============================================================================
   VESPER — TREE BAKE-OFF.

   Eleven candidate trees, side by side, rotating, so a human can pick one.

   Everything here obeys the SHIPPING CONTRACT, because whichever one wins has
   to drop into surfaceObjects.js without a rewrite:

     one BufferGeometry per family   — no groups, no second material
     base y=0, tip y=1, widest r=1   — the instance matrix scales (r, h, r)
     <= 400 triangles                — 20,000+ instances in view
     no textures, no UVs
     a pure function of a seed       — no Math.random() anywhere below

   THE ONE ADDITION: a vec2 vertex attribute `aPart`.
     .x  material  0 = bark/stem/rock, 1 = foliage/blade/membrane
     .y  exposure  0 = buried in the canopy, 1 = out in the open (baked AO)
   It lives on the shared per-family geometry, so it costs nothing per instance,
   and it replaces objectShader's smoothstep(0.16, 0.52, vY) guess about which
   vertices are canopy. Press G to switch back to that guess and watch what it
   costs. Everything else in the fragment shader is the game's objectShader.js.
   ========================================================================= */
(function () {
'use strict';
var T = THREE;

/* A white page and a crash look identical from outside — VESPER paid for that
   lesson once already (CLAUDE.md item 5). Say which. */
function die(e) {
  var d = document.getElementById('err');
  if (d) { d.style.display = 'grid'; d.textContent = 'TREES.html failed to start\n\n' + (e && e.stack || e); }
  window.__T = window.__T || {};
  window.__T.fatal = String(e && e.message || e);
  throw e;
}

/* ==========================================================================
   1.  THE ELEVEN, HAND BUILT
   The toolkit these use (rnd / MB / tri / quad / tube / blob / finish) lives in
   geom.js, because the BREEDER needs exactly the same six functions and there
   is no module system that survives file://.
   ========================================================================= */


/* --- 0 · CONTROL -----------------------------------------------------------
   Exactly what VESPER ships today: objectShader.js's `tree` profile spun about
   the vertical with 10 radial segments. aPart reproduces the shader's own
   smoothstep(0.16, 0.52, y) and sets exposure to 1 everywhere, so this renders
   the way it does in game — a fair control, not a straw man. */
function tLathe() {
  var P = [[0.34, 0.00], [0.13, 0.10], [0.11, 0.26], [1.00, 0.30], [0.42, 0.52],
           [0.78, 0.55], [0.34, 0.74], [0.55, 0.77], [0.18, 0.92], [0.00, 1.00]];
  var g = new T.LatheGeometry(P.map(function (v) { return new T.Vector2(v[0], v[1]); }), 10);
  g.deleteAttribute('uv');
  var pos = g.attributes.position, n = pos.count, arr = new Float32Array(n * 2);
  for (var i = 0; i < n; i++) {
    var t = cl01((pos.getY(i) - 0.16) / 0.36);
    arr[i * 2] = t * t * (3 - 2 * t);   // the shader's guess, made explicit
    arr[i * 2 + 1] = 1;                 // it has no idea about occlusion
  }
  g.setAttribute('aPart', new T.Float32BufferAttribute(arr, 2));
  return g;
}

/* --- 1 · BOUGHWORK ---------------------------------------------------------
   Real recursion. A trunk that forks, and forks again, each limb a tapered
   4-sided prism; leaves hang on the outer tips only. The silhouette differs
   from every azimuth because the fork directions do, and no two limbs are
   parallel. */
function tBough() {
  var b = MB(), R = rnd(70113), tips = [];
  function limb(from, dir, len, rad, depth) {
    var end = add(from, mul(dir, len));
    var pts, rads;
    if (depth === 2) {
      var mid = add(from, mul(dir, len * 0.55));
      mid[0] += 0.05; mid[2] -= 0.035;
      pts = [from, mid, end]; rads = [rad, rad * 0.74, rad * 0.56];
    } else {
      pts = [from, end]; rads = [rad, rad * 0.50];
    }
    var ao = 0.30 + 0.24 * (2 - depth);
    tube(b, pts, rads, 4, function () { return BARK(ao); }, { flat: true });
    if (depth === 0) { tips.push(end); return; }
    var n = depth === 2 ? 3 : 2;
    for (var i = 0; i < n; i++) {
      var az = R() * 6.2832, sp = 0.58 + R() * 0.55;
      var d2 = nrm([dir[0] + Math.cos(az) * sp, dir[1] * (0.74 + R() * 0.28),
                    dir[2] + Math.sin(az) * sp]);
      limb(end, d2, len * (0.60 + R() * 0.14), rad * 0.56, depth - 1);
    }
  }
  limb([0, 0, 0], [0, 1, 0], 0.50, 0.115, 2);

  /* SMALLER LUMPS, MORE OF THEM. The first pass hung six lumps at 0.42 radius
     on the tips and they merged into two enormous flat plates that hid every
     limb underneath them — a tree whose whole argument is that it branches,
     rendering as a canopy with no visible structure. */
  var crown = [0, 0, 0], i;
  for (i = 0; i < tips.length; i++) crown = add(crown, mul(tips[i], 1 / tips.length));
  var lumps = tips.concat([lerp3(crown, tips[0], 0.34), lerp3(crown, tips[2], 0.30),
                           lerp3(crown, tips[4], 0.26), [crown[0], crown[1] + 0.10, crown[2]]]);
  for (i = 0; i < lumps.length; i++) {
    var c = lumps[i], s = 0.135 + rnd(500 + i)() * 0.075;
    blob(b, OCT, [c[0], c[1] + s * 0.28, c[2]], [s * 1.32, s * 0.82, s * 1.32], 31 + i, 0.36,
      function (v) { return LEAF(0.34 + 0.66 * cl01((v[1] - 0.45) / 0.55)); });
  }
  return finish(b);
}

/* --- 2 · PARASOL -----------------------------------------------------------
   One bold shape instead of many small ones. A slim trunk and a single wide
   drooping canopy whose rim is a scalloped 15-gon rather than a circle, so it
   is a different outline from every angle even though it is one piece. At 30 m
   it reads as a dark umbrella against the sky, which is the cheapest
   silhouette there is. */
function tParasol() {
  var b = MB();
  tube(b, [[0, 0, 0], [0.02, 0.30, 0.01], [0.05, 0.58, 0.02], [0.07, 0.78, 0.02]],
    [0.095, 0.062, 0.048, 0.040], 5, function () { return BARK(0.30); });

  function canopy(cx, cy, cz, rad, top, dip, sides, seed, ao) {
    var Q = rnd(seed), rim = [], i;
    for (i = 0; i < sides; i++) {
      var a = (i / sides) * 6.2832 + (Q() - 0.5) * 0.13;
      var rr = rad * (0.78 + Q() * 0.22);
      rim.push([cx + Math.cos(a) * rr, cy - dip * (0.70 + Q() * 0.46), cz + Math.sin(a) * rr]);
    }
    var apex = [cx, cy + top, cz], under = [cx, cy + top * 0.28, cz];
    for (i = 0; i < sides; i++) {
      var j = (i + 1) % sides;
      tri(b, apex, rim[i], rim[j], LEAF(ao));               // upper skin
      tri(b, under, rim[j], rim[i], LEAF(ao * 0.40));       // under skin, darker
    }
  }
  canopy(0.07, 0.86, 0.02, 1.00, 0.14, 0.23, 15, 771, 1.00);
  canopy(-0.17, 0.60, -0.11, 0.44, 0.07, 0.12, 9, 913, 0.55);
  return finish(b);
}

/* --- 3 · PAGODA ------------------------------------------------------------
   Stacked, separated discs threaded on a stem, each offset sideways and
   rotated, sizes falling off upward. Nothing about it is a cone; the offsets
   spiral, so turning it changes which discs overlap. */
function tPagoda() {
  var b = MB();
  tube(b, [[0, 0, 0], [0.01, 0.22, 0.03], [0.03, 0.46, 0.05], [0.05, 0.70, 0.04], [0.04, 0.94, 0.02]],
    [0.100, 0.072, 0.058, 0.046, 0.028], 4, function () { return BARK(0.34); }, { flat: true });

  /* Radii fall off FAST and the offsets spiral wide. With a gentle falloff the
     five discs sat almost inside one another's outline and, seen from above,
     merged into a single lumpy mass — the one thing this shape must not do. */
  var H = [0.20, 0.395, 0.575, 0.745, 0.905], RD = [1.00, 0.79, 0.585, 0.395, 0.215];
  for (var d = 0; d < 5; d++) {
    var t = d / 4, Q = rnd(600 + d * 37), i;
    var ox = Math.cos(t * 4.9 + 0.6) * 0.23 * (1 - t * 0.25);
    var oz = Math.sin(t * 4.9 + 0.6) * 0.23 * (1 - t * 0.25);
    var sides = 9, ring = [];
    for (i = 0; i < sides; i++) {
      var a = (i / sides) * 6.2832 + t * 1.1;
      var rr = RD[d] * (0.78 + Q() * 0.22);
      ring.push([ox + Math.cos(a) * rr, H[d] + (Q() - 0.5) * 0.024, oz + Math.sin(a) * rr]);
    }
    var up = [ox, H[d] + 0.030 + RD[d] * 0.050, oz];
    var dn = [ox, H[d] - 0.028, oz];
    for (i = 0; i < sides; i++) {
      var j = (i + 1) % sides;
      tri(b, up, ring[i], ring[j], LEAF(1.0));
      tri(b, dn, ring[j], ring[i], LEAF(0.28));
    }
  }
  return finish(b);
}

/* --- 4 · PRISM -------------------------------------------------------------
   No trunk at all: a burst of faceted 3-sided blades from one root, every one
   a different length and angle, flat-shaded so each facet takes the light on
   its own. The material ramps to foliage at the points, so on a glowing world
   the tips light and the root stays dead. */
function tPrism() {
  var b = MB(), i;
  var A = [[0.00, 1.00, 0.02, 0.150], [0.34, 0.86, -0.20, 0.128],
           [-0.42, 0.74, 0.16, 0.120], [0.10, 0.66, 0.46, 0.114],
           [-0.20, 0.55, -0.44, 0.106], [0.55, 0.50, 0.22, 0.098],
           [-0.58, 0.42, -0.16, 0.092], [0.26, 0.36, -0.56, 0.086],
           [-0.30, 0.30, 0.58, 0.080], [0.68, 0.26, -0.34, 0.072],
           [-0.70, 0.22, 0.30, 0.068], [0.06, 0.20, 0.74, 0.062],
           [-0.10, 0.17, -0.76, 0.058]];
  for (i = 0; i < A.length; i++) {
    var a = A[i];
    var base = [a[0] * 0.20, 0.0, a[2] * 0.20];
    var tip = [a[0], a[1], a[2]];
    /* The material ramp stays DARK for most of each blade and only turns pale
       near the point. Ramping 0 -> 0.45 -> 1 painted three quarters of every
       blade from `peak`, and the whole tree came back as a silver thistle with
       no trunk tone in it anywhere. */
    tube(b, [base, lerp3(base, tip, 0.45), tip], [a[3], a[3] * 0.55, 0.012], 3,
      function (ri) { return [ri === 0 ? 0 : ri === 1 ? 0.20 : 0.88, 0.26 + 0.74 * (ri / 2)]; },
      { flat: true, phase: i * 0.7 });
  }
  blob(b, OCT, [0, 0.055, 0], [0.30, 0.13, 0.30], 5, 0.30, function () { return BARK(0.22); });
  return finish(b);
}

/* --- 5 · FROND -------------------------------------------------------------
   A crown of arcing ribbons. Each frond is a shallow V-section swept along a
   Bezier that rises, turns over and droops, tapering to a point — so the
   outline is all curve and no cone, and the fronds cross each other
   differently from every direction. */
function tFrond() {
  var b = MB();
  tube(b, [[0, 0, 0], [0.01, 0.14, 0.01], [0.02, 0.28, 0.01], [0.02, 0.40, 0]],
    [0.155, 0.115, 0.098, 0.085], 5, function () { return BARK(0.30); });

  var NF = 9;
  for (var f = 0; f < NF; f++) {
    var az = (f / NF) * 6.2832 + 0.31, Q = rnd(880 + f * 13);
    var out = [Math.cos(az), 0, Math.sin(az)];
    var side = [-Math.sin(az), 0, Math.cos(az)];
    var reach = 0.86 + Q() * 0.26, rise = 1.40 + Q() * 0.24, drop = 0.48 + Q() * 0.18;
    var wob = 0.85 + Q() * 0.30;
    var P0 = [0, 0.40, 0];
    var P1 = add(mul(out, reach * 0.10), [0, rise, 0]);
    var P2 = add(mul(out, reach), [0, drop, 0]);
    var ST = 5, prev = null;
    for (var i = 0; i < ST; i++) {
      var t = i / (ST - 1), u = 1 - t;
      var C = [u * u * P0[0] + 2 * u * t * P1[0] + t * t * P2[0],
               u * u * P0[1] + 2 * u * t * P1[1] + t * t * P2[1],
               u * u * P0[2] + 2 * u * t * P1[2] + t * t * P2[2]];
      var w = 0.130 * Math.sin(Math.PI * Math.pow(t, 0.62)) * wob;
      var cur = {
        L: add(C, mul(side, -w)),
        M: [C[0], C[1] + w * 0.55, C[2]],
        R: add(C, mul(side, w)),
        ao: 0.28 + 0.72 * t,
      };
      if (prev) {
        quad(b, prev.L, cur.L, cur.M, prev.M,
          [LEAF(prev.ao * 0.78), LEAF(cur.ao * 0.78), LEAF(cur.ao), LEAF(prev.ao)]);
        quad(b, prev.M, cur.M, cur.R, prev.R,
          [LEAF(prev.ao), LEAF(cur.ao), LEAF(cur.ao * 0.78), LEAF(prev.ao * 0.78)]);
      }
      prev = cur;
    }
  }
  return finish(b);
}

/* --- 6 · BRAID -------------------------------------------------------------
   Several thin trunks from one root, splayed at the ground and CONVERGING
   under a single shared crown. One instance reads as a thicket, which is worth
   a great deal when the density budget is per square kilometre. */
function tBraid() {
  var b = MB(), NT = 5;
  for (var k = 0; k < NT; k++) {
    var az = (k / NT) * 6.2832 + 0.4, Q = rnd(300 + k * 29);
    var spread = 0.30 + Q() * 0.17, twist = (Q() - 0.5) * 1.6;
    var top = 0.70 + Q() * 0.12, girth = 0.82 + Q() * 0.36;
    var shade = 0.26 + 0.26 * Q();
    var pts = [], rad = [], ST = 5;
    for (var i = 0; i < ST; i++) {
      var t = i / (ST - 1);
      var s = spread * (1 - t) * (1 - t) + 0.055 * t;
      var a = az + twist * t;
      pts.push([Math.cos(a) * s, t * top, Math.sin(a) * s]);
      rad.push(0.105 * (1 - t * 0.62) * girth);
    }
    tube(b, pts, rad, 3, function () { return BARK(shade); }, { flat: true });
  }
  // The crown is a LID on the trunks, not a ball around them: at 0.40 vertical
  // radius it reached down to y=0.28 and ate the five trunks that are the
  // entire idea.
  var ao = function (v) { return LEAF(0.28 + 0.72 * cl01((v[1] - 0.66) / 0.34)); };
  blob(b, ICO, [0.02, 0.86, -0.03], [0.84, 0.22, 0.80], 41, 0.30, ao);
  blob(b, ICO, [-0.26, 0.93, 0.22], [0.50, 0.17, 0.54], 43, 0.32, ao);
  blob(b, OCT, [0.36, 0.88, 0.28], [0.36, 0.15, 0.34], 47, 0.34, ao);
  return finish(b);
}

/* --- 7 · GNARL -------------------------------------------------------------
   The trunk IS the tree. One tube swept along a wandering, kinked spine, with
   a lobed cross-section that rotates as it climbs — fluted, twisted, thicker
   in some places than others, and never the same outline twice. */
function tGnarl() {
  var b = MB();
  var SP = [[0.00, 0.00, 0.00], [0.06, 0.11, -0.04], [0.16, 0.23, -0.02],
            [0.13, 0.35, 0.09], [0.02, 0.47, 0.16], [-0.09, 0.59, 0.11],
            [-0.13, 0.71, -0.02], [-0.06, 0.82, -0.13], [0.06, 0.91, -0.16],
            [0.14, 0.99, -0.12]];
  var RD = [0.190, 0.155, 0.124, 0.140, 0.095, 0.080, 0.092, 0.061, 0.040, 0.017];
  tube(b, SP, RD, 6, function (ri) { return BARK(0.16 + 0.84 * (ri / 9)); },
    { lobe: function (i, s, a) { return 1 + 0.24 * Math.cos(3 * (a + i * 0.42)); } });

  /* THE LIMBS HAVE TO REACH, and the first version's did not.
     finish() scales the geometry so its widest point is exactly r = 1.0. On
     every other candidate that widest point is a canopy — but here it was the
     TRUNK, at 0.30 against stubs that only got to 0.32, so normalising blew the
     whole tree up 3x and it came back as a boulder with three leaves on it.
     A shape whose widest feature is its own trunk cannot be authored to this
     contract; the limbs have to be what reaches 1.0. */
  var LIMB = [[3, 1.15, 0.80, 0.062, 0.34], [5, -0.62, 0.92, 0.055, 0.20],
              [7, 2.55, 0.72, 0.046, 0.40], [2, 4.10, 0.58, 0.050, 0.10],
              [8, 3.35, 0.44, 0.034, 0.55]];
  for (var i = 0; i < LIMB.length; i++) {
    var L = LIMB[i], o = SP[L[0]];
    var d = nrm([Math.cos(L[1]), L[4], Math.sin(L[1])]);
    var end = add(o, mul(d, L[2]));
    var mid = add(o, mul(d, L[2] * 0.55));
    mid[1] += L[2] * 0.16;                       // limbs sag, then lift at the tip
    tube(b, [o, mid, end], [L[3], L[3] * 0.62, L[3] * 0.28], 4,
      function () { return BARK(0.40 + 0.10 * i); }, { flat: true });
    var w = 0.15 + 0.05 * (i % 3);
    blob(b, OCT, [end[0], end[1] + w * 0.4, end[2]], [w * 1.5, w * 0.85, w * 1.5],
      77 + i * 7, 0.38, function () { return LEAF(0.55 + 0.45 * (1 - i / 5)); });
  }
  return finish(b);
}

/* --- 8 · LANTERN -----------------------------------------------------------
   The one that leans into the glow. A dark cage of ribs bulging at waist
   height with a bright pod suspended inside it. At night the ribs are the
   silhouette and the pod is the light; by day it is a sculptural cage. The pod
   is the only foliage-material part, so it takes the glow colour and the ribs
   stay dead. */
function tLantern() {
  var b = MB(), NR = 5;
  for (var k = 0; k < NR; k++) {
    var az = (k / NR) * 6.2832 + 0.2, Q = rnd(150 + k * 31);
    var fat = 0.55 + Q() * 0.20, shade = 0.30 + 0.44 * Q();
    var pts = [], rad = [], ST = 5;
    for (var i = 0; i < ST; i++) {
      var t = i / (ST - 1);
      var s = 0.13 + Math.sin(Math.PI * Math.pow(t, 0.85)) * fat;
      var a = az + t * 0.58;
      pts.push([Math.cos(a) * s, t * 0.92, Math.sin(a) * s]);
      rad.push(0.042 * (1 - t * 0.35));
    }
    tube(b, pts, rad, 3, function () { return BARK(shade); }, { flat: true });
  }
  tube(b, [[0.04, 0.89, 0.02], [0.02, 1.00, 0.01]], [0.055, 0.010], 3,
    function () { return BARK(0.90); }, { flat: true });
  blob(b, OCT, [0.01, 0.44, 0.00], [0.31, 0.37, 0.31], 111, 0.26, function () { return LEAF(1.0); });
  blob(b, OCT, [-0.10, 0.68, 0.08], [0.15, 0.18, 0.15], 113, 0.26, function () { return LEAF(0.9); });
  return finish(b);
}

/* --- 9 · CROWN -------------------------------------------------------------
   The familiar one, done properly: a broadleaf canopy assembled from seven
   overlapping lumps in THREE different sizes, on a short thick trunk whose
   limb stubs push into the mass. The size hierarchy is what stops it reading
   as a die — one lump is a die, seven overlapping ones are a canopy. */
function tCrown() {
  var b = MB();
  tube(b, [[0, 0, 0], [0.02, 0.17, 0.01], [0.04, 0.33, 0.02], [0.05, 0.48, 0.02]],
    [0.175, 0.128, 0.104, 0.090], 5, function (ri) { return BARK(0.24 + 0.18 * ri); });
  var ST = [[0.9, 0.36], [3.1, 0.32], [5.3, 0.28]];
  for (var i = 0; i < ST.length; i++) {
    var o = [0.05, 0.44 + i * 0.045, 0.02];
    var d = nrm([Math.cos(ST[i][0]), 0.85, Math.sin(ST[i][0])]);
    tube(b, [o, add(o, mul(d, ST[i][1]))], [0.070, 0.036], 4,
      function () { return BARK(0.34); }, { flat: true });
  }
  /* The canopy sits ON the trunk rather than around it. At 0.44 vertical
     radius the main lump reached down to y = 0.28 and the whole tree came back
     as one boulder with no trunk visible at all — the exact failure the
     `boulder` roster already had, arrived at from the other direction. */
  var ao = function (v) { return LEAF(0.26 + 0.74 * cl01((v[1] - 0.56) / 0.44)); };
  blob(b, ICO, [0.04, 0.79, 0.02], [0.86, 0.27, 0.80], 201, 0.28, ao);
  blob(b, ICO, [-0.34, 0.88, 0.22], [0.50, 0.21, 0.48], 203, 0.30, ao);
  blob(b, ICO, [0.30, 0.85, -0.28], [0.54, 0.20, 0.52], 205, 0.30, ao);
  blob(b, OCT, [0.10, 0.97, 0.16], [0.34, 0.15, 0.32], 207, 0.32, ao);
  blob(b, OCT, [-0.16, 0.72, -0.42], [0.32, 0.14, 0.30], 209, 0.32, ao);
  blob(b, OCT, [0.46, 0.72, 0.22], [0.28, 0.13, 0.28], 211, 0.32, ao);
  blob(b, OCT, [-0.46, 0.77, -0.10], [0.26, 0.13, 0.26], 213, 0.32, ao);
  return finish(b);
}

/* --- 10 · SAIL -------------------------------------------------------------
   BILATERAL, NOT RADIAL — the direct answer to "identical from every angle".
   A spine carrying a broad membrane that only exists in one plane, plus a
   second smaller blade crossed at 55 degrees. Edge-on it is a line, face-on it
   is a wall, so one geometry plus a random yaw generates a forest that never
   repeats. The membrane is one layer thick on purpose (needs DoubleSide, or
   the faces emitted twice — see the handback). */
function tSail() {
  var b = MB(), i, p;
  var yaw = 0.0, ca = Math.cos(yaw), sa = Math.sin(yaw);
  var SIDE = [-sa, 0, ca];        // along the sail plane, horizontal
  var OUT = [ca, 0, sa];          // out of the plane — only the fold uses it

  var SP = [[0, 0, 0], [0.012, 0.22, 0.008], [0.028, 0.44, 0.014],
            [0.040, 0.66, 0.016], [0.044, 0.85, 0.012], [0.040, 1.00, 0.006]];
  tube(b, SP, [0.098, 0.078, 0.062, 0.048, 0.034, 0.014], 4,
    function (ri) { return BARK(0.28 + 0.14 * ri); }, { flat: true });

  /* FOUR PAIRS OF BLADES IN ONE PLANE, not one continuous membrane.
     The membrane version came back as a single enormous rhombus — a leaf the
     size of a house, not a tree. Cutting it into blades keeps the bilateral
     property exactly (they are all still in the one plane, so it is a wall
     face-on and a line edge-on) and gives it a structure the eye can read. */
  var PAIR = [[0.30, 1.00, 0.13], [0.50, 0.86, 0.115], [0.68, 0.68, 0.098], [0.845, 0.47, 0.078]];
  for (var k = 0; k < PAIR.length; k++) {
    var y0 = PAIR[k][0], reach = PAIR[k][1], hh = PAIR[k][2];
    var root = [0.026 + 0.02 * k, y0, 0.012];
    for (var s = -1; s <= 1; s += 2) {
      var Q = rnd(3300 + k * 17 + (s + 1) * 61);
      var fold = (0.030 + Q() * 0.020) * (k % 2 ? 1 : -1);
      var ST = 4, prev = null;
      for (i = 0; i < ST; i++) {
        var t = i / (ST - 1);
        var C = add(root, mul(SIDE, s * reach * t));
        C[1] += 0.10 * Math.sin(Math.PI * t) - 0.26 * t * t;      // rise, then droop
        var h = hh * Math.sin(Math.PI * Math.pow(t, 0.50)) * (0.85 + Q() * 0.28);
        var cur = {
          U: [C[0], C[1] + h, C[2]],
          M: add(C, mul(OUT, fold * (1 - Math.abs(0.5 - t) * 1.4))),
          D: [C[0], C[1] - h, C[2]],
          ao: 0.30 + 0.70 * t,
        };
        if (prev) {
          quad(b, prev.U, cur.U, cur.M, prev.M,
            [LEAF(prev.ao), LEAF(cur.ao), LEAF(cur.ao * 0.8), LEAF(prev.ao * 0.8)]);
          quad(b, prev.M, cur.M, cur.D, prev.D,
            [LEAF(prev.ao * 0.8), LEAF(cur.ao * 0.8), LEAF(prev.ao * 0.55), LEAF(cur.ao * 0.55)]);
        }
        prev = cur;
      }
    }
  }
  return finish(b);
}

/* --- the roster ----------------------------------------------------------- */
var CAND = [
  { nm: 'TIERED LATHE', ctl: true, build: tLathe, emis: 0.15, tip: 'mid',
    idea: 'one profile spun about the vertical — today’s tree' },
  { nm: 'BOUGHWORK', build: tBough, emis: 0.15, tip: 'mid',
    idea: 'real branching: limbs that fork twice, then leaves' },
  { nm: 'PARASOL', build: tParasol, emis: 0.15, tip: 'mid',
    idea: 'one bold drooping canopy, scalloped at the rim' },
  { nm: 'PAGODA', build: tPagoda, emis: 0.22, tip: 'mid',
    idea: 'separated discs threaded on a stem, each turned' },
  { nm: 'PRISM', build: tPrism, emis: 0.14, tip: 'peak',
    idea: 'faceted blades from one root; not a curve on it' },
  { nm: 'FROND', build: tFrond, emis: 0.15, tip: 'mid',
    idea: 'arcing V-section ribbons that rise and droop' },
  { nm: 'BRAID', build: tBraid, emis: 0.15, tip: 'mid',
    idea: 'five trunks, one root, one shared crown' },
  { nm: 'GNARL', build: tGnarl, emis: 0.18, tip: 'mid',
    idea: 'the trunk IS the tree: fluted, wandering, twisted' },
  { nm: 'LANTERN', build: tLantern, emis: 0.90, tip: 'emisB',
    idea: 'a dark cage of ribs around a glowing pod' },
  { nm: 'CROWN', build: tCrown, emis: 0.15, tip: 'mid',
    idea: 'a broadleaf mass from lumps in three sizes' },
  { nm: 'SAIL', build: tSail, emis: 0.18, tip: 'mid',
    idea: 'bilateral: a wall face-on, a line edge-on' },
];

/* ==========================================================================
   2.  GENERATION ZERO FOR THE BREEDER.

   Eleven points in the genome grammar, chosen to be as far apart as the space
   allows — all three trunk modes, all three branch modes, all five foliage
   kinds, all three symmetries. They echo the hand-built eleven and are NOT
   identical to them: these are what one shared grammar can say, and the point
   of starting here rather than from noise is that a random genome is almost
   always ugly, so a breeder seeded with noise dies in generation one.
   ========================================================================= */
var SEEDS = [
  ['CONIFER', { trunkMode: 'single', trunkH: 0.98, trunkR: 0.34, taper: 0.80, sides: 6,
    branchMode: 'whorl', branchLevels: 4, branchN: 5, branchLen: 0.62, branchAngle: 1.05,
    branchDroop: 0.20, branchR: 0.040, foliage: 'blob', folSize: 0.30, folFlat: 0.52,
    folJag: 0.30, symmetry: 'radial', emis: 0.15, aoDepth: 0.30, seed: 11 }],
  ['ACACIA', { trunkMode: 'single', trunkH: 0.46, trunkR: 0.62, taper: 0.55, wander: 0.10,
    lean: 0.10, sides: 5, branchMode: 'fork', branchN: 3, branchLen: 0.80, branchAngle: 0.85,
    branchDroop: -0.10, branchR: 0.085, foliage: 'blob', folSize: 0.34, folFlat: 0.30,
    folJag: 0.34, symmetry: 'scattered', emis: 0.15, aoDepth: 0.32, seed: 29 }],
  ['PARASOL', { trunkMode: 'single', trunkH: 0.80, trunkR: 0.26, taper: 0.60, sides: 5,
    branchMode: 'none', foliage: 'disc', folN: 1, folSize: 1.00, folFlat: 0.30,
    folSpread: 0.14, folRise: 0.0, folDroop: 0.30, symmetry: 'radial',
    emis: 0.15, aoDepth: 0.30, seed: 43 }],
  ['PAGODA', { trunkMode: 'single', trunkH: 1.00, trunkR: 0.28, taper: 0.72, sides: 4,
    branchMode: 'none', foliage: 'disc', folN: 5, folSize: 0.72, folFlat: 0.18,
    folSpread: 0.22, folRise: 1.0, folDroop: 0.12, symmetry: 'scattered',
    emis: 0.22, aoDepth: 0.28, seed: 57 }],
  ['PRISM', { trunkMode: 'single', trunkH: 0.26, trunkR: 0.30, taper: 0.85, sides: 4,
    branchMode: 'none', foliage: 'blade', folN: 12, folSize: 0.90, folFlat: 0.55,
    folSpread: 0.20, folRise: 0.45, folTilt: 0.55, symmetry: 'scattered',
    matSplit: 0.88, emis: 0.14, aoDepth: 0.26, seed: 71 }],
  ['FROND', { trunkMode: 'single', trunkH: 0.40, trunkR: 0.85, taper: 0.42, sides: 5,
    branchMode: 'none', foliage: 'ribbon', folN: 9, folSize: 0.95, folFlat: 0.85,
    folSpread: 0.16, folRise: 0.0, folDroop: 0.24, symmetry: 'radial',
    emis: 0.15, aoDepth: 0.28, seed: 83 }],
  ['BRAID', { trunkMode: 'multi', stems: 5, splay: 0.36, converge: 0.14, trunkH: 0.74,
    trunkR: 0.44, taper: 0.60, twist: 0.70, sides: 3, branchMode: 'none',
    foliage: 'blob', folN: 4, folSize: 0.60, folFlat: 0.32, folSpread: 0.42,
    folRise: 0.10, folJag: 0.30, symmetry: 'radial', emis: 0.15, aoDepth: 0.28, seed: 97 }],
  ['GNARL', { trunkMode: 'single', trunkH: 0.85, trunkR: 0.40, taper: 0.86, wander: 0.30,
    flute: 0.26, twist: 0.85, sides: 6, branchMode: 'whorl', branchLevels: 3, branchN: 2,
    branchLen: 0.80, branchAngle: 1.28, branchDroop: 0.08, branchR: 0.055,
    foliage: 'blob', folSize: 0.26, folFlat: 0.26, folJag: 0.38, symmetry: 'scattered',
    emis: 0.18, aoDepth: 0.30, seed: 113 }],
  ['LANTERN', { trunkMode: 'cage', stems: 5, twist: 0.60, trunkR: 0.55, foliage: 'blob',
    folN: 2, folSize: 0.55, folFlat: 0.95, folSpread: 0.14, folJag: 0.26,
    branchMode: 'none', symmetry: 'radial', emis: 0.88, matSplit: 1.0,
    aoDepth: 0.45, seed: 127 }],
  ['CROWN', { trunkMode: 'single', trunkH: 0.48, trunkR: 0.80, taper: 0.50, sides: 5,
    branchMode: 'fork', branchN: 3, branchLen: 0.68, branchAngle: 0.70, branchDroop: -0.16,
    branchR: 0.075, foliage: 'blob', folSize: 0.52, folFlat: 0.46, folJag: 0.30,
    symmetry: 'scattered', emis: 0.15, aoDepth: 0.26, seed: 149 }],
  ['SAIL', { trunkMode: 'single', trunkH: 1.00, trunkR: 0.36, taper: 0.82, sides: 4,
    branchMode: 'none', foliage: 'membrane', folN: 8, folSize: 0.82, folFlat: 0.60,
    folSpread: 0.14, folRise: 0.85, folDroop: 0.18, symmetry: 'bilateral',
    emis: 0.18, aoDepth: 0.30, seed: 163 }],
];

/* ==========================================================================
   3.  THE MATERIAL — objectShader.js, plus aPart.
   Every term below is the game's, in the game's order. Only the lines marked
   NEW differ. No backticks inside a shader string (CLAUDE.md item 1).
   ========================================================================= */
var VERT = [
  'precision highp float;',
  'in vec3 aCol; in vec3 aCol2; in float aShade; in vec3 aUp; in float aEmis;',
  'in vec2 aPart;',                                             // NEW
  'out vec3 vN; out vec3 vCol; out vec3 vCol2; out vec3 vNv;',
  'out float vShade; out vec3 vUp; out float vEmis; out float vY;',
  'out float vMat; out float vAo;',                             // NEW
  'void main(){',
  '  mat3 im = mat3(instanceMatrix);',
  '  vN = normalize(im * normal);',
  '  vUp = normalize(aUp); vCol = aCol; vCol2 = aCol2;',
  '  vNv = mat3(modelViewMatrix) * vN;',
  '  vShade = aShade; vEmis = aEmis; vY = position.y;',
  '  vMat = aPart.x; vAo = aPart.y;',                           // NEW
  '  gl_Position = projectionMatrix * modelViewMatrix * instanceMatrix * vec4(position,1.0);',
  '}'
].join('\n');

var FRAG = [
  'precision highp float;',
  'in vec3 vN; in vec3 vCol; in vec3 vCol2; in vec3 vNv;',
  'in float vShade; in vec3 vUp; in float vEmis; in float vY;',
  'in float vMat; in float vAo;',
  'layout(location = 0) out vec4 outColor;',
  'uniform vec3 uSun, uSunCol, uSkyCol;',
  'uniform float uAmbient, uSkyAmt, uAoFloor, uOld;',
  'float sat(float x){ return clamp(x,0.0,1.0); }',
  'void main(){',
  '  vec3 n = normalize(vN);',
  // NEW: one-layer membranes and open tubes. Light a back face as the surface
  // it is, rather than as its own shadow.
  '  if(!gl_FrontFacing) n = -n;',
  '  vec3 up = normalize(vUp);',
  '  float ndl = dot(n, uSun);',
  '  float lit = sat(ndl);',
  '  float wrap = sat((ndl + 0.20)/1.20);',
  '  float skyF = 0.5 + 0.5*dot(n, up);',
  // NEW: the material split comes from the geometry instead of from a guess
  // about height. uOld = 1 restores today's guess, for a one-key comparison.
  '  float m  = mix(vMat, smoothstep(0.16,0.52,vY), uOld);',
  '  float ao = mix(mix(uAoFloor, 1.0, vAo), 1.0, uOld);',
  '  vec3 base = mix(vCol, vCol2, m);',
  '  vec3 col = base * vShade * ao;',
  '  float rimV = pow(1.0 - sat(abs(normalize(vNv).z)), 3.0);',
  '  vec3 outc = col * (lit*0.92 + wrap*0.14) * uSunCol',
  '            + col * uAmbient',
  '            + col * uSkyCol * (uSkyAmt * skyF * 0.55)',
  '            + base * ao * uSkyCol * (rimV * 0.42);',
  '  float lmx = max(max(outc.r, outc.g), outc.b);',
  '  outc *= min(1.0, 0.92/max(lmx, 1e-4));',
  '  float rim = pow(1.0 - sat(abs(n.z)), 2.0);',
  '  outc += col * vEmis * (0.55 + 1.45*rim);',
  '  outc *= mix(0.38, 1.0, smoothstep(0.0, 0.20, vY));',
  '  outColor = vec4(outc, 1.0);',
  '}'
].join('\n');

/* ==========================================================================
   5.  SCENE
   ========================================================================= */
/* THE PALETTE IS THE WORLD, AND THE WORLD IS A DIAL.
   Every tree used to be painted from verdant's two colours, so eighteen wildly
   different SHAPES all came back looking like one forest — which is exactly
   what the owner said when he saw his own library: "these are all 1 theme".
   The theme was never the trees. WORLDS is generated from the game's own
   archetypes.js and objectKinds.js by tools/gen_worlds.mjs, so a tree bred
   here is painted in the colours it would really be painted in on that planet. */
function WRLD() { return WORLD_BY[st.world] || WORLDS[0]; }
/* The eleven hand-built candidates name their tip as a palette FIELD; the
   worlds table names colours. One mapper, so CAND never has to know. */
var TIPFIELD = { mid: 'tip', peak: 'peak', emisB: 'glow' };
function tipCol(field) { var w = WRLD(); return w[TIPFIELD[field] || 'tip']; }
/* A world's own emissive character scales whatever the genome asked for.
   verdant (0.15) is the unit, so desert and tundra ship 0 and nothing on them
   glows at all — which is true of those worlds and is the point. */
function emisFor(g) {
  /* verdant's 0.15 is the unit, so a tree bred there is unchanged, and 0.55 is
     a hard ceiling on the PRODUCT. Without it crystal (roster emissive 0.9) put
     broad canopies at 1.1, and 1.1 times the shader's rim term is 2.2 on an
     albedo whose red channel is already 1.0 — the tree stopped being a glowing
     shape and became a white light panel. In game that never shows because
     crystal's spire is a needle at rBase 0.11; a canopy is not. */
  return Math.min(0.55, g * Math.min(2.0, WRLD().emis / 0.15));
}
var SIZES = [3, 12, 30];
/* WIDTH — and it is not a cosmetic control.
   Every geometry here is authored to max radius 1.0, but the game does NOT
   draw it that way: surfaceObjects.js scales each instance by
   (height * rBase, height, height * rBase), and verdant's canopy tree ships
   rBase = 0.10. So the thing that actually stands on the planet is a tenth as
   wide as what "widest radius 1.0" draws. A tree judged only at 1:1 is a tree
   judged at a proportion it will never have. 1.0 is the raw geometry, 0.45 is
   a broadleaf, 0.16 is roughly what verdant ships today. */
var WIDES = [1.00, 0.45, 0.16];
var st = { size: 1, wide: 0, night: true, spin: true, bloom: true, ao: true, old: false,
           zoom: -1, mode: 'breed', gen: 0, sel: [], mut: 1, libPage: 0, bg: 2, world: 'verdant', libAll: false };
var api = { frames: 0, trees: [], state: st, fatal: null };
window.__T = api;

var cv = document.getElementById('cv');
var renderer, scene, cam, uni, mat, W = 1, H = 1;
try {
  renderer = new T.WebGLRenderer({ canvas: cv, antialias: true, powerPreference: 'high-performance' });
} catch (e) { die(e); }
renderer.setPixelRatio(Math.min(2, window.devicePixelRatio || 1));
renderer.setClearColor(0x04070a, 1);

var HDR = renderer.extensions.has('EXT_color_buffer_half_float') ||
          renderer.extensions.has('EXT_color_buffer_float');
var RTT = HDR ? T.HalfFloatType : T.UnsignedByteType;

scene = new T.Scene();
cam = new T.PerspectiveCamera(38, 1, 0.1, 4000);

uni = {
  uSun: { value: new T.Vector3(0.42, 0.42, 0.80).normalize() },
  uSunCol: { value: new T.Color(1, 1, 1) },
  uSkyCol: { value: new T.Color(0.10, 0.28, 0.32) },
  uAmbient: { value: 0.055 },
  uSkyAmt: { value: 0.95 },
  uAoFloor: { value: 0.34 },
  uOld: { value: 0 },
};
mat = new T.ShaderMaterial({
  vertexShader: VERT, fragmentShader: FRAG, glslVersion: T.GLSL3,
  uniforms: uni, side: T.DoubleSide,
});
mat.toneMapped = false;

/* THE SKY, as a dome rather than a clear colour: a silhouette needs something
   behind it, and the night side of a VESPER world is lit by the nebula. */
var skyU = {
  uHor: { value: new T.Color(0x0d2b33) },
  uZen: { value: new T.Color(0x04070c) },
  uGlow: { value: new T.Color(0x0d5a4a) },
  uStars: { value: 1 },
};
var sky = new T.Mesh(new T.SphereGeometry(2400, 24, 16), new T.ShaderMaterial({
  uniforms: skyU, side: T.BackSide, depthWrite: false, depthTest: false,
  vertexShader: 'varying vec3 vP; void main(){ vP = position; ' +
    'gl_Position = projectionMatrix * modelViewMatrix * vec4(position,1.0); }',
  /* STARS ARE DRAWN BY THE DOME, not by a THREE.Points cloud.
     The Points version rendered nothing at all on this driver and there was no
     error to read — the night sky simply stayed empty. A hash-grid in the
     fragment shader cannot silently fail to draw, costs nothing, and is
     deterministic by construction. */
  fragmentShader: [
    'uniform vec3 uHor, uZen, uGlow; uniform float uStars; varying vec3 vP;',
    'float h21(vec2 p){ p = fract(p*vec2(123.34,456.21)); p += dot(p,p+45.32);',
    '  return fract(p.x*p.y); }',
    'void main(){',
    '  vec3 d = normalize(vP);',
    '  float h = clamp(d.y*0.5+0.5, 0.0, 1.0);',
    '  vec3 c = mix(uHor, uZen, pow(h, 0.55));',
    '  c += uGlow * exp(-pow((h-0.50)*7.0, 2.0)) * 0.55;',
    '  if (uStars > 0.5 && d.y > -0.02) {',
    '    vec2 uv = vec2(atan(d.z, d.x)*2.2, asin(clamp(d.y,-1.0,1.0))*2.2) * 30.0;',
    '    vec2 cell = floor(uv), f = fract(uv);',
    '    float g = h21(cell);',
    '    if (g > 0.905) {',
    '      vec2 at = vec2(h21(cell+3.7), h21(cell+9.1));',
    '      float s = smoothstep(0.20, 0.0, length(f-at)) * (0.25 + 0.75*h21(cell+17.3));',
    '      c += vec3(0.36,0.48,0.62) * s * smoothstep(-0.02, 0.10, d.y);',
    '    }',
    '  }',
    '  gl_FragColor = vec4(c, 1.0);',
    '}'
  ].join('\n'),
}));
sky.frustumCulled = false;
sky.renderOrder = -2;
scene.add(sky);

/* THE GROUND. Not eleven discs floating in a void: a landscape. Trees in the
   game are seen against ground as often as against sky, so judging them over
   nothing would be judging the easy case. It fades to the sky's own horizon
   colour with distance, which is the cheapest aerial perspective there is and
   is what stops the far edge reading as a hard cut. */
var gndU = {
  uNear: { value: new T.Color(0x0a1e1f) },
  uFar: { value: skyU.uHor.value },
  uK: { value: 1 / 300 },
};
var ground = new T.Mesh(new T.CircleGeometry(1, 72).rotateX(-Math.PI / 2),
  new T.ShaderMaterial({
    uniforms: gndU,
    vertexShader: [
      'varying float vD;',
      'void main(){ vec4 wp = modelMatrix * vec4(position,1.0);',
      '  vD = length(wp.xyz - cameraPosition);',
      '  gl_Position = projectionMatrix * viewMatrix * wp; }'
    ].join('\n'),
    fragmentShader: [
      'uniform vec3 uNear, uFar; uniform float uK; varying float vD;',
      'void main(){ gl_FragColor = vec4(mix(uNear, uFar, 1.0 - exp(-vD*uK)), 1.0); }'
    ].join('\n'),
  }));
ground.position.y = -0.006;
scene.add(ground);

/* ------------------------------------------------------- shared geometries */
function rawGeo(b) {
  var g = new T.BufferGeometry();
  g.setAttribute('position', new T.Float32BufferAttribute(b.p, 3));
  g.setAttribute('normal', new T.Float32BufferAttribute(b.n, 3));
  g.setAttribute('aPart', new T.Float32BufferAttribute(b.m, 2));
  return g;
}
function humanGeo() {                       // 1.0 tall, scaled to 1.8 m below
  var b = MB();
  function box(cx, cy, cz, hx, hy, hz) {
    var X = [cx - hx, cx + hx], Y = [cy - hy, cy + hy], Z = [cz - hz, cz + hz];
    var v = [[X[0], Y[0], Z[0]], [X[1], Y[0], Z[0]], [X[1], Y[1], Z[0]], [X[0], Y[1], Z[0]],
             [X[0], Y[0], Z[1]], [X[1], Y[0], Z[1]], [X[1], Y[1], Z[1]], [X[0], Y[1], Z[1]]];
    var F = [[4, 5, 6, 7], [1, 0, 3, 2], [3, 7, 6, 2], [0, 1, 5, 4], [5, 1, 2, 6], [0, 4, 7, 3]];
    for (var i = 0; i < 6; i++) quad(b, v[F[i][0]], v[F[i][1]], v[F[i][2]], v[F[i][3]], LEAF(1));
  }
  box(0, 0.930, 0, 0.054, 0.065, 0.050);   // head
  box(0, 0.730, 0, 0.092, 0.146, 0.057);   // torso
  box(0, 0.545, 0, 0.073, 0.058, 0.050);   // hips
  box(-0.044, 0.266, 0, 0.031, 0.266, 0.038);
  box(0.044, 0.266, 0, 0.031, 0.266, 0.038);
  box(-0.117, 0.720, 0, 0.025, 0.157, 0.029);
  box(0.117, 0.720, 0, 0.025, 0.157, 0.029);
  return rawGeo(b);
}
function discGeo(sides) {
  var b = MB();
  for (var i = 0; i < sides; i++) {
    var a0 = (i / sides) * 6.2832, a1 = ((i + 1) / sides) * 6.2832;
    tri(b, [0, 0, 0], [Math.cos(a1), 0, Math.sin(a1)], [Math.cos(a0), 0, Math.sin(a0)], BARK(1));
  }
  return rawGeo(b);
}
function instAttrs(geo, col, col2, shade, emis) {
  var c = new T.Color(col), c2 = new T.Color(col2);
  geo.setAttribute('aCol', new T.InstancedBufferAttribute(new Float32Array([c.r, c.g, c.b]), 3));
  geo.setAttribute('aCol2', new T.InstancedBufferAttribute(new Float32Array([c2.r, c2.g, c2.b]), 3));
  geo.setAttribute('aUp', new T.InstancedBufferAttribute(new Float32Array([0, 1, 0]), 3));
  geo.setAttribute('aShade', new T.InstancedBufferAttribute(new Float32Array([shade]), 1));
  geo.setAttribute('aEmis', new T.InstancedBufferAttribute(new Float32Array([emis]), 1));
  return geo;
}
var HUMAN = instAttrs(humanGeo(), '#8d7f6d', '#d9cdb8', 1.0, 0.05);

/* ------------------------------------------------------------- build pods --
   InstancedMesh with one instance and the real per-instance attributes, so
   every geometry here is exercised through the exact path it will ship on. */
function measure(geo) {
  var pa = geo.attributes.position, minY = 1e9, maxY = -1e9, maxR = 0;
  for (var vi = 0; vi < pa.count; vi++) {
    var yy = pa.getY(vi);
    if (yy < minY) minY = yy;
    if (yy > maxY) maxY = yy;
    var rr2 = Math.hypot(pa.getX(vi), pa.getZ(vi));
    if (rr2 > maxR) maxR = rr2;
  }
  return { tris: geo.index ? geo.index.count / 3 : pa.count / 3, verts: pa.count,
           minY: minY, maxY: maxY, maxR: maxR };
}

var pods = [];
for (var ci = 0; ci < CAND.length; ci++) {
  var cand = CAND[ci], geo;
  try { geo = cand.build(); }
  catch (e) { die(new Error('tree ' + ci + ' (' + cand.nm + '): ' + e.message)); }

  var mm = measure(geo);
  api.trees.push({ n: ci, name: cand.nm, tris: mm.tris, verts: mm.verts,
                   minY: mm.minY, maxY: mm.maxY, maxR: mm.maxR });

  instAttrs(geo, WRLD().trunk, tipCol(cand.tip), 1.0, emisFor(cand.emis));
  var im = new T.InstancedMesh(geo, mat, 1); im.frustumCulled = false;
  var hum = new T.InstancedMesh(HUMAN, mat, 1); hum.frustumCulled = false;
  /* THE PATCH OF GROUND, on a FLAT material rather than the object shader.
     Through the object shader it came back pure black and the eleven pods read
     as eleven holes: a disc lies at vY = 0, so objectShader's contact-occlusion
     term multiplies it by 0.38 — correct for the foot of a tree, catastrophic
     for a floor. */
  var disc = new T.Mesh(discGeo(34), new T.MeshBasicMaterial({ color: 0x123430 }));
  disc.material.toneMapped = false;

  var rp = [];
  for (var q = 0; q <= 64; q++) rp.push(new T.Vector3(Math.cos(q / 64 * 6.2832), 0, Math.sin(q / 64 * 6.2832)));
  var ring = new T.LineLoop(new T.BufferGeometry().setFromPoints(rp),
    new T.LineBasicMaterial({ color: 0x1d6a58, transparent: true, opacity: 0.5 }));

  var grp = new T.Group();
  grp.add(im); grp.add(hum); grp.add(disc); grp.add(ring);
  scene.add(grp);
  pods.push({ grp: grp, im: im, hum: hum, disc: disc, ring: ring, tris: mm.tris });
}

/* ==========================================================================
   THE BREEDER — selective breeding, with your eye as the fitness function.

   Select the ones you like, press BREED, and the next eleven are their
   children. Nothing reloads: a genome is 34 bytes, growTree() runs in well
   under a millisecond, and swapping an InstancedMesh's geometry is a pointer
   assignment. The whole generation is rebuilt between two frames.

   Three things make it converge instead of wander:
     ELITISM — the ones you picked come back UNCHANGED in the front slots, so
       a good tree can never be lost by an unlucky mutation.
     CROSSOVER blends the continuous genes and inherits the categorical ones
       whole (see grow.js), so a child of a disc tree and a ribbon tree is one
       or the other, at a shape between them.
     THE MUTATION RATE IS YOURS. Subtle to refine something that nearly works,
       wild to get out of a corner you have bred yourself into.
   ========================================================================= */
var pop = null, popMeta = [], history = [], MUT = [0.20, 0.48, 0.95];

/* A genome carries a `label` on gen 0 (the eleven ideas, by name). Array.slice
   drops custom properties, so cloning has to be explicit — without this a kept
   PARASOL comes back through breed() renamed to a random syllable pair, and it
   looks like the app swapped your tree for a different one. */
function cloneGenome(g) {
  var c = g.slice();
  if (g.label) c.label = g.label;
  return c;
}

/* ------------------------------------------------------------ THE LIBRARY --
   Anything you keep is remembered. It persists in localStorage, which usually
   works from a double-clicked file but is not guaranteed to — Chrome can treat
   a file:// page as an opaque origin and throw. So every access is wrapped, the
   strip says so when it has fallen back to memory only, and COPY ALL CODES is
   the backstop: 68 characters per tree, paste them anywhere. */
var LIBKEY = 'vesper.trees.library.v3';
var OLDKEY = 'vesper.trees.library.v2';

/* THE 18 THE OWNER KEPT, so a fresh install opens on his own set rather than
   on mine. Seeded only when there is nothing stored — it must never resurrect
   something he deleted. */
var SEED_LIB = [
  ['GNARL', '4ECD3BECE18099DDC58080808B00AACCDD984B2A802D34808080E3CD80802E806071'],
  ['PRISM', '4E071AE9808033808080808032808080808080C1FFE38412738080CD80D823E04647'],
  ['FROND', '4E36CE648080668080808080328080808080808FB9F1D60600C88032808026805353'],
  ['LANTERN', 'ED806C80808080808BBF8080328080808080802A1780F10080808E328080F9FFBF7F'],
  ['ACACIA', '4E4A838C4B62668080808080D95580CC8861A52A80443F808080C6CD808026806C1D'],
  ['DRANYX', '4E36CE795D80668F8080698077788080AC80808FB7F1D60600C880328080268053AE'],
  ['DRAITH', '4E4AA271808054A78A9A8080968060804E80808F00C8DD0640B68332808066F86621'],
  ['KALITH', '4EA675FF877F6EB2B180808027379EB7A8624A2AA62E35808080D7CD6D7B27806673'],
  ['THUDUN', '4ECD3BFFE16999FFD75792808B00E2FFDB984B20802D34AB80AAE3CD805F2E806036'],
  ['KATAK', 'D4A800C3A1C6014E79804D3A7CFE0BFE49E59F6421F15367ED91F1345BB98EB2847A'],
  ['ISHMEL', 'CE09539B4E2CADD7546A6D41CDD174C7B965C72C0D412E06133E289BADFFC9593473'],
  ['DRASHA', '4E4AA27180545CA78A608062962D45804E80808F00A5DD0040B6831093DC66F86698'],
  ['VELITH', 'B6E471B0A43460B06307563E909EE4B57F89C9D7A083382926D7D55336AE25D02158'],
  ['RYNNYX', '64EB0DC4EF7694B3328B0DFC82B9351CEF681CD1AA9F6C87CEE77837E8CD56DF9F4C'],
  ['RYNDUN', '64F00DF7FF39E3AF00716FFF279F351CCF651CD19D9FA281CEFF7837E8BA5FDF9F64'],
  ['RYNMEL', '64B50D3CFF769475328B0DDE82B9353AFFB91CD1AA9F6CFFCEFF7837E8BFB3FFFDA6'],
  ['DRATAK', 'EEA70DD3F497C5B02F6847FF82B23529D34C3FD3AA9F9484CEEB7837FE826EDFA20B'],
  ['RYNMEL II', '64FF0DBBFF398EAF00826FFF279F352BCF651CD18A9FA281CEFF7837FF83ADDF9FA1'],
];

/* ------------------------------------------------------------ THE LIBRARY --
   ONE COLLECTION PER WORLD. The owner kept eighteen trees and every one of them
   was verdant, because verdant was the only palette there was; a library that
   is one flat list would go on hiding that. Keyed by world, each theme keeps
   its own shelf, and the shelf you are looking at is always the planet you are
   standing on.

   It persists in localStorage, which usually works from a double-clicked file
   but is not guaranteed to — Chrome can treat a file:// page as an opaque
   origin and throw. So every access is wrapped, the strip says so when it has
   fallen back to memory only, and COPY ALL CODES is the backstop. */
var libs = {}, libPersist = true;
function lib_() { return (libs[st.world] = libs[st.world] || []); }
function libTotal() { var n = 0; for (var k in libs) n += libs[k].length; return n; }
function entryFor(g, name) {
  var geo = growTree(g), m = measure(geo); geo.dispose();
  return { code: encGenome(g), name: name || g.label || nameGenome(g),
           desc: describeGenome(g), tris: m.tris };
}
function libLoad() {
  var raw = null;
  try { raw = window.localStorage.getItem(LIBKEY); } catch (e) { libPersist = false; }
  if (raw) {
    try { libs = JSON.parse(raw) || {}; } catch (e) { libs = {}; }
  } else {
    /* migrate the old flat list: everything in it was bred on verdant, because
       that was the only palette that existed when it was written */
    var old = null;
    try { old = window.localStorage.getItem(OLDKEY); } catch (e) { /* ignore */ }
    if (old) { try { libs = { verdant: JSON.parse(old) || [] }; } catch (e) { libs = {}; } }
  }
  if (typeof libs !== 'object' || libs === null || libs instanceof Array) libs = {};
  if (!libTotal()) {
    libs.verdant = [];
    for (var i = 0; i < SEED_LIB.length; i++) {
      var g = decGenome(SEED_LIB[i][1]);
      if (g) libs.verdant.push(entryFor(g, SEED_LIB[i][0]));
    }
    libStore();
  }
}
function libStore() {
  // an empty collection is an artefact of having glanced at a world, not a
  // thing the owner made — storing them would grow the file for ever
  var out = {};
  for (var k in libs) if (libs[k] && libs[k].length) out[k] = libs[k];
  try { window.localStorage.setItem(LIBKEY, JSON.stringify(out)); libPersist = true; }
  catch (e) { libPersist = false; }
}
function libAdd(g) {
  var L = lib_(), code = encGenome(g);
  for (var i = 0; i < L.length; i++) if (L[i].code === code) return false;
  L.push(entryFor(g));
  libStore();
  return true;
}
/* THIS WORLD, OR ALL OF THEM.
   Collections are per world so each theme keeps its own shelf — but a genome is
   geometry and the world is only paint, so every shape you have ever kept is
   worth looking at on the planet you are standing on now. ALL concatenates the
   collections and paints them all in the CURRENT world, which is the fastest
   way to find out whether the eighteen verdant trees also work as crystal. */
function libView() {
  if (!st.libAll) return lib_();
  var out = [], seen = {}, k, i;
  for (k in libs) {
    for (i = 0; i < libs[k].length; i++) {
      var e = libs[k][i];
      if (seen[e.code]) continue;
      seen[e.code] = 1;
      out.push({ code: e.code, name: e.name, desc: e.desc, tris: e.tris, world: k });
    }
  }
  return out;
}
function libWorldOf(code) {
  for (var k in libs) for (var i = 0; i < libs[k].length; i++)
    if (libs[k][i].code === code) return k;
  return st.world;
}
function libRemove(codes) {
  for (var k in libs) {
    libs[k] = libs[k].filter(function (e) { return codes.indexOf(e.code) < 0; });
  }
  libStore();
}
libLoad();

function genomeTip(G) {
  /* 0.84, not 0.72. `peak` is the world's PALE field — snow on a tundra
     conifer, caprock on a hoodoo — and at one in four it was turning up as
     white canopies scattered through a green forest, where verdant's roster
     never paints from peak at all. The recipes now hand a high matSplit only
     to the worlds whose rosters really do use it. */
  var w = WRLD();
  return G.emis > 0.45 ? w.glow : (G.matSplit > 0.84 ? w.peak : w.tip);
}
function setPodGeom(i, geo, tip, emis) {
  var p = pods[i], old = p.im.geometry;
  instAttrs(geo, WRLD().trunk, tip || WRLD().tip, 1.0, emis);
  p.im.geometry = geo;
  if (old && old !== geo) old.dispose();
  var mm2 = measure(geo);
  p.tris = mm2.tris;
  return mm2;
}
function growInto(i, g) {
  var G, geo;
  try { G = decode(g); geo = growTree(g); }
  catch (e) { g = makeGenome({}); G = decode(g); geo = growTree(g); }
  var mm2 = setPodGeom(i, geo, genomeTip(G), emisFor(G.emis));
  pods[i].active = true;
  popMeta[i] = { name: g.label || nameGenome(g), desc: describeGenome(g), code: encGenome(g),
                 emis: G.emis, tip: genomeTip(G), elite: !!g.elite };
  api.trees.push({ n: i, name: popMeta[i].name, tris: mm2.tris, verts: mm2.verts,
                   minY: mm2.minY, maxY: mm2.maxY, maxR: mm2.maxR, code: popMeta[i].code });
  return g;
}
function applyPop() {
  api.trees.length = 0;
  for (var i = 0; i < pods.length; i++) pop[i] = growInto(i, pop[i]);
}
function applyGallery() {
  api.trees.length = 0;
  for (var i = 0; i < CAND.length; i++) {
    var c2 = CAND[i], geo2 = c2.build();
    var mm3 = setPodGeom(i, geo2, tipCol(c2.tip), emisFor(c2.emis));
    pods[i].active = true;
    popMeta[i] = { name: c2.nm, desc: c2.idea, code: '', emis: c2.emis, tip: c2.tip, elite: false };
    api.trees.push({ n: i, name: c2.nm, tris: mm3.tris, verts: mm3.verts,
                     minY: mm3.minY, maxY: mm3.maxY, maxR: mm3.maxR });
  }
}
var libSlots = [], podWorld = [];   // which library entry each pod shows, and whose
function applyLibrary() {
  api.trees.length = 0; libSlots.length = 0;
  var L0 = libView();
  var pages = Math.max(1, Math.ceil(L0.length / pods.length));
  st.libPage = Math.max(0, Math.min(pages - 1, st.libPage));
  var base = st.libPage * pods.length;
  for (var i = 0; i < pods.length; i++) {
    var e = L0[base + i];
    if (!e) { pods[i].active = false; popMeta[i] = { name: '', desc: '', code: '' }; continue; }
    var g = decGenome(e.code);
    if (!g) { pods[i].active = false; popMeta[i] = { name: '', desc: '', code: '' }; continue; }
    g.label = e.name;
    libSlots[i] = e.code;
    podWorld[i] = e.world || st.world;
    growInto(i, g);
  }
}
/* GENERATION ZERO IS THE PLANET'S OWN. Eleven genomes rolled from this world's
   recipe (recipes.js), so Frozen opens on drooping whorled conifers and Arid on
   fat columns under flat caprock — different TREES, not the same trees in a
   different colour. Deterministic in (world, index): the same planet always
   opens on the same eleven. */
function seedPop() {
  pop = seedSetFor(st.world, pods.length);
  history.length = 0;
  st.gen = 0; st.sel = [];
  applyPop();
}
/* NEW TREES — the other half of the loop, and it was missing.
   Breeding can only ever recombine what is already in the pool, so a
   population that has converged on one shape has no way out of it: every child
   is a variation on the same thing. This rerolls every slot you have NOT kept,
   from the whole space, leaving the kept ones exactly where they are. */
/* A MONOTONIC ROLL COUNTER, AND IT IS NOT DECORATION.
   The first version seeded each reroll from `pop[0][0]` — one byte of the
   population it was about to replace. A byte has 256 states and the seed
   produced that byte, so the whole thing was a deterministic map from a
   256-element set onto itself, which must cycle. Measured: it came back around
   after 5 to 21 presses depending on the generation, so hammering NEW TREES
   walked a tiny loop of populations and kept handing back trees already seen
   and already saved.
   A counter that only ever goes up cannot do that. Still fully deterministic
   for a given sequence of clicks, so the harness can still reproduce a run.

   AND IT OUTLIVES THE PAGE. Kept in storage next to the library, because a
   counter that restarts at zero every reload means the FIRST press of a new
   session repeats the first press of the last one. Persisting it beats salting
   with a clock: it stays completely deterministic, it just never goes back. */
var ROLLKEY = 'vesper.trees.rolls.v1';
var rolls = 0;
try { rolls = parseInt(window.localStorage.getItem(ROLLKEY), 10) || 0; } catch (e) { rolls = 0; }
function rollSeed(tag) {
  rolls++;
  try { window.localStorage.setItem(ROLLKEY, String(rolls)); } catch (e) { /* memory only */ }
  return (Math.imul(rolls, 0x9E3779B1) ^ Math.imul(st.gen + 1, 0x85EBCA6B) ^
          Math.imul(libTotal() + 1, 0xC2B2AE35) ^ tag) >>> 0;
}
function freshPop() {
  if (!pop) { seedPop(); return; }
  history.push(pop.map(cloneGenome));
  if (history.length > 24) history.shift();
  var R = rnd(rollSeed(0x5EED));
  var keep = st.sel.slice().sort(function (a, b) { return a - b; });
  var next = [], i, k;
  for (i = 0; i < pods.length; i++) next.push(null);
  for (k = 0; k < keep.length; k++) {
    var c = cloneGenome(pop[keep[k]]); c.elite = true; next[keep[k]] = c;
  }
  /* "completely new" is taken literally: a fresh roll that happens to be a
     tree already in the library is rerolled. In a space of 256^34 that will
     essentially never fire, but it makes the promise the button makes true by
     construction rather than by probability. */
  var have = {};
  for (i = 0; i < lib_().length; i++) have[lib_()[i].code] = 1;
  for (i = 0; i < next.length; i++) if (next[i]) have[encGenome(next[i])] = 1;
  for (var j = 0; j < next.length; j++) {
    if (next[j]) continue;
    var g, code, tries = 0;
    do { g = randGenome(R); code = encGenome(g); } while (have[code] && ++tries < 8);
    have[code] = 1;
    next[j] = g;
  }
  pop = next; st.sel = keep;
  applyPop();
}
function breed() {
  if (!st.sel.length) return false;
  history.push(pop.map(cloneGenome));
  if (history.length > 24) history.shift();

  var parents = st.sel.map(function (i) { return pop[i]; });
  /* Through the same monotonic counter as the reroll. Breeding did not have
     the cycling problem — st.gen climbs every time, so its seed always moved —
     but it was one edit away from it, and there is no reason for two different
     rules about where randomness comes from. */
  var R = rnd(rollSeed(parents[0][0] * 313 + parents[0][7] * 17 + parents[0][19] * 3));
  var amt = MUT[st.mut];
  var next = parents.map(function (g) { var c = cloneGenome(g); c.elite = true; return c; });
  var guard = 0;
  while (next.length < pods.length && guard++ < 400) {
    var a = parents[Math.floor(R() * parents.length) % parents.length];
    var child;
    if (parents.length > 1 && R() < 0.62) {
      var b2 = parents[Math.floor(R() * parents.length) % parents.length];
      child = mutateGenome(crossGenome(a, b2, R), R, amt * 0.75);
    } else {
      child = mutateGenome(a, R, amt);
    }
    child[GI.seed] = Math.floor(R() * 256);
    next.push(child);
  }
  pop = next;
  st.gen++;
  // the parents STAY kept, in the front slots. "Kept" has to mean kept, or
  // pressing BREED twice quietly throws away the thing you were building on.
  st.sel = parents.map(function (_, i) { return i; });
  applyPop();
  return true;
}
function stepBack() {
  if (!history.length) return false;
  pop = history.pop();
  st.gen = Math.max(0, st.gen - 1); st.sel = [];
  applyPop();
  return true;
}
function loadGenome(code, slot) {
  var g = decGenome(code);
  if (!g) return false;
  pop[slot === undefined ? 0 : slot] = g;
  applyPop();
  return true;
}

/* ==========================================================================
   6.  POST — bloom, then ACES, then sRGB: the chain src/render/view.js uses.
   (UnrealBloomPass lives in three/examples and cannot be inlined without its
   whole dependency tree, so this is a hand-rolled equivalent at the same
   threshold. This is a viewer, not the game's renderer.)
   ========================================================================= */
var POSTV = 'varying vec2 vUv; void main(){ vUv = uv; gl_Position = vec4(position.xy,0.0,1.0); }';
var rtScene = null, rtA = null, rtB = null;
var quadCam = new T.OrthographicCamera(-1, 1, 1, -1, 0, 1);
var quadMesh = new T.Mesh(new T.PlaneGeometry(2, 2), null);
quadMesh.frustumCulled = false;
var quadScene = new T.Scene(); quadScene.add(quadMesh);

var mBright = new T.ShaderMaterial({
  uniforms: { tD: { value: null }, uThr: { value: 0.95 } },
  vertexShader: POSTV, depthTest: false, depthWrite: false,
  fragmentShader: [
    'uniform sampler2D tD; uniform float uThr; varying vec2 vUv;',
    'void main(){ vec3 c = texture2D(tD, vUv).rgb;',
    '  float l = max(max(c.r,c.g),c.b);',
    '  gl_FragColor = vec4(c * smoothstep(uThr, uThr+0.35, l), 1.0); }'
  ].join('\n'),
});
function blurMat(dir) {
  return new T.ShaderMaterial({
    uniforms: { tD: { value: null }, uTex: { value: new T.Vector2(1, 1) } },
    vertexShader: POSTV, depthTest: false, depthWrite: false,
    fragmentShader: [
      'uniform sampler2D tD; uniform vec2 uTex; varying vec2 vUv;',
      'void main(){',
      '  vec2 d = uTex * vec2(' + dir + ');',
      '  vec3 c = texture2D(tD, vUv).rgb * 0.227;',
      '  c += (texture2D(tD, vUv + d*1.385).rgb + texture2D(tD, vUv - d*1.385).rgb) * 0.316;',
      '  c += (texture2D(tD, vUv + d*3.231).rgb + texture2D(tD, vUv - d*3.231).rgb) * 0.070;',
      '  gl_FragColor = vec4(c, 1.0); }'
    ].join('\n'),
  });
}
var mBlurH = blurMat('1.0,0.0'), mBlurV = blurMat('0.0,1.0');
var mComp = new T.ShaderMaterial({
  uniforms: { tD: { value: null }, tB: { value: null }, uStr: { value: 0.9 } },
  vertexShader: POSTV, depthTest: false, depthWrite: false,
  fragmentShader: [
    'uniform sampler2D tD; uniform sampler2D tB; uniform float uStr; varying vec2 vUv;',
    'vec3 rrt(vec3 v){ vec3 a = v*(v+0.0245786)-0.000090537;',
    '  vec3 b = v*(0.983729*v+0.4329510)+0.238081; return a/b; }',
    'vec3 aces(vec3 c){',
    '  mat3 I = mat3(0.59719,0.07600,0.02840, 0.35458,0.90834,0.13383, 0.04823,0.01566,0.83777);',
    '  mat3 O = mat3(1.60475,-0.10208,-0.00327, -0.53108,1.10813,-0.07276, -0.07367,-0.00605,1.07602);',
    '  c *= 1.0/0.6; c = I*c; c = rrt(c); c = O*c; return clamp(c,0.0,1.0); }',
    'vec3 srgb(vec3 c){ return mix(pow(c,vec3(0.41666))*1.055-0.055, c*12.92,',
    '  vec3(lessThanEqual(c, vec3(0.0031308)))); }',
    'void main(){',
    '  vec3 c = texture2D(tD, vUv).rgb + texture2D(tB, vUv).rgb * uStr;',
    '  gl_FragColor = vec4(srgb(aces(c)), 1.0); }'
  ].join('\n'),
});

function allocRT() {
  var pr = renderer.getPixelRatio();
  var w = Math.max(2, Math.floor(W * pr)), h = Math.max(2, Math.floor(H * pr));
  if (rtScene) { rtScene.dispose(); rtA.dispose(); rtB.dispose(); }
  rtScene = new T.WebGLRenderTarget(w, h, {
    type: RTT, minFilter: T.LinearFilter, magFilter: T.LinearFilter, depthBuffer: true,
  });
  var w4 = Math.max(2, w >> 2), h4 = Math.max(2, h >> 2);
  var o = { type: RTT, minFilter: T.LinearFilter, magFilter: T.LinearFilter, depthBuffer: false };
  rtA = new T.WebGLRenderTarget(w4, h4, o);
  rtB = new T.WebGLRenderTarget(w4, h4, o);
  mBlurH.uniforms.uTex.value.set(1 / w4, 1 / h4);
  mBlurV.uniforms.uTex.value.set(1 / w4, 1 / h4);
}
function pass(m, target) {
  quadMesh.material = m;
  renderer.setRenderTarget(target || null);
  renderer.render(quadScene, quadCam);
}

/* ==========================================================================
   7.  LAYOUT / CAMERA
   ========================================================================= */
/* Four across, three deep, and the camera looks down 30 degrees at it. The
   tilt is not a taste call: at a shallow angle the three rows INTERLEAVE on
   screen — the middle row's canopy lands above the back row's base — so every
   label collides with the row behind it and no tree has a clear outline.
   Thirty degrees separates the rows completely. The low hero angle, against
   the sky, is what the zoom view is for. */
var COLS = 4, ROWS = 3, CSP = 2.12, RSP = 3.90, DISC = 0.80;
var TILT = 26 * Math.PI / 180;
var camPos = new T.Vector3(), camTgt = new T.Vector3();
var wantPos = new T.Vector3(), wantTgt = new T.Vector3();
var first = true;

function S() { return SIZES[st.size]; }

/* THE GRID FITS WHATEVER IT IS SHOWING.
   Four across and three deep is right for eleven and wrong for two: the library
   shelf with a pair of saved trees left them stranded in the back corner of an
   empty frame, because the layout AND the camera were both hard-wired to
   eleven. Both read the live count now. */
var GRID = { cols: COLS, rows: ROWS, n: 11 };
function activeN() {
  if (inLib()) return Math.max(1, Math.min(pods.length, libView().length - st.libPage * pods.length));
  return pods.length;
}
function regrid() {
  var n = activeN();
  var cols = n <= 3 ? n : (n <= 8 ? Math.ceil(n / 2) : COLS);
  GRID = { cols: Math.max(1, Math.min(COLS, cols)), rows: 0, n: n };
  GRID.rows = Math.ceil(n / GRID.cols);
}
function podXZ(i) {
  var s = S(), col = i % GRID.cols, row = Math.floor(i / GRID.cols);
  var last = GRID.n - GRID.cols * (GRID.rows - 1);
  var inRow = (row === GRID.rows - 1 && last > 0) ? last : GRID.cols;
  return [(col - (inRow - 1) / 2) * CSP * s, (row - (GRID.rows - 1) / 2) * RSP * s];
}
function layout() {
  regrid();
  var s = S();
  for (var i = 0; i < pods.length; i++) {
    var p = pods[i], xz = podXZ(i);
    p.grp.position.set(xz[0], 0, xz[1]);
    p.disc.scale.set(s * DISC, 1, s * DISC);
    p.ring.scale.set(s * DISC, 1, s * DISC);
    p.ring.position.y = 0.005 * s;
    p.hum.position.set(-s * 0.66, 0, s * 0.54);
    p.hum.scale.setScalar(1.8);
  }
  ground.scale.set(s * 420, 1, s * 420);
  gndU.uK.value = 1 / (26 * s);
}
function frame() {
  var s = S(), asp = Math.max(0.55, W / H);
  var vt = Math.tan(cam.fov * Math.PI / 360), ht = vt * asp;
  var nz = Math.max(0.08, s * 0.04);
  if (cam.near !== nz) { cam.near = nz; cam.updateProjectionMatrix(); }
  if (st.zoom >= 0) {
    /* A HERO FRAME: low, slightly below the canopy, looking a touch UP, so the
       silhouette is against the sky. The other pods are hidden in tick() —
       without that, the neighbours crowd into the edges of the frame and it is
       impossible to tell which shape belongs to which tree. */
    /* Framed on the NEAR edge, not on the centre. Every geometry here is 2.0
       wide and 1.0 tall, so the front of a tree is a whole radius closer than
       its middle: at a distance picked from the centre, the near ribs of the
       cage subtended 32 degrees against a 30-degree half-frame and ran off
       both sides of the picture. */
    var xz = podXZ(st.zoom);
    var w = WIDES[st.wide];
    var d = Math.max(0.95 * s / vt, s * (1.0 + 1.05 * w) / ht + s * w);
    wantTgt.set(xz[0], s * 0.55, xz[1]);
    wantPos.set(xz[0] + s * 0.42 * w, s * 0.40, xz[1] + d);
  } else {
    var ct = Math.cos(TILT), stt = Math.sin(TILT);
    var halfW = (GRID.cols - 1) / 2 * CSP * s + (DISC + 0.16) * s;
    var halfZ = (GRID.rows - 1) / 2 * RSP * s + (DISC + 0.16) * s;
    var vE = 2 * halfZ * stt + 1.06 * s * ct;      // vertical extent on screen
    var Dw = halfW / ht + halfZ * ct;              // wide enough
    var Dh = vE * 0.5 / vt + halfZ * ct;           // tall enough
    var D = Math.max(Dw, Dh) * 1.03;
    wantTgt.set(0, s * 0.30, 0);
    wantPos.set(0, s * 0.30 + D * stt, D * ct);
  }
  if (first) { camPos.copy(wantPos); camTgt.copy(wantTgt); first = false; }
}
/* THE BACKDROP IS A SEPARATE DIAL FROM THE LIGHT, and it has to be.
   On this palette bark paints from `hi` (#33254f) — verdant's darkest field,
   near black. Against a night sky that is also near black, a tree that is
   mostly trunk is black on black and there is nothing to judge. Raising the
   EXPOSURE would not fix it either: it lifts the tree and the background
   together and the contrast stays exactly where it was.
   So this lifts only the sky dome, the ground and the pod, toward a neutral,
   leaving the light on the tree alone — which is what turns an invisible trunk
   into a readable silhouette. A little fill comes with it so the shapes still
   have modelling in them rather than being flat cut-outs. */
var BG_T = [0.0, 0.42, 0.80];
var _k1 = new T.Color(), _k2 = new T.Color(), _k3 = new T.Color();
/* base colour, optionally pulled toward another and scaled */
function tone(dst, hex, k, toward, t) {
  dst.set(hex);
  if (toward) dst.lerp(_k3.set(toward), t);
  if (k !== undefined) dst.multiplyScalar(k);
  return dst;
}
/* LIFT THE WORLD'S OWN COLOUR — never lerp toward a fixed one.
   The first version pulled every backdrop element toward the same slate teal,
   and at the LIGHT setting that lift is 80%: so Arid, whose ground is rust
   #8a4c22, came back with a teal floor, and every world's sky looked the same.
   Brightening what is already there keeps the hue and still fixes the
   black-on-black problem; the small neutral is only there so a base that is
   very nearly black has something to rise from. */
function bd(dst, gain, neutralT) {
  var t = BG_T[st.bg];
  dst.multiplyScalar(1 + (gain - 1) * t);
  return dst.lerp(_k2.setRGB(0.030, 0.038, 0.045),
                  t * (neutralT === undefined ? 0.30 : neutralT));
}
function light() {
  var t = BG_T[st.bg], w = WRLD();
  if (st.night) {
    /* NIGHT IS DARK, and it is the world's own dark. The sky, the fill and the
       ground all come from this archetype's atmosphere and low-ground colours,
       so switching world changes the planet you are standing on and not just
       the paint on the trees. Sun near zero; on a glowing world the picture is
       carried by emissive and by the rim, which is what the game does. */
    uni.uSun.value.set(-0.46, 0.18, 0.56).normalize();
    tone(uni.uSunCol.value, w.atmo, (0.20 + 0.34 * t), '#8fb6ff', 0.45);
    tone(uni.uSkyCol.value, w.atmo, (0.20 + 0.24 * t), '#20404e', 0.35);
    uni.uSkyAmt.value = 0.80;
    uni.uAmbient.value = 0.030 + 0.050 * t;
    bd(tone(skyU.uHor.value, w.atmo, 0.13, '#0a2026', 0.55), 3.4);
    bd(tone(skyU.uZen.value, w.atmo, 0.05, '#03050a', 0.80), 3.0, 0.45);
    tone(skyU.uGlow.value, w.glow, 0.16);
    bd(tone(gndU.uNear.value, w.lo, 0.30, '#08191a', 0.30), 2.7);
    for (var q1 = 0; q1 < pods.length; q1++) {
      bd(tone(pods[q1].disc.material.color, w.lo, 0.42, '#0d2422', 0.30), 2.4);
    }
    mComp.uniforms.uStr.value = 1.05;
    skyU.uStars.value = 1;
  } else {
    uni.uSun.value.set(0.42, 0.50, 0.76).normalize();
    uni.uSunCol.value.setRGB(1.00, 0.965, 0.90).multiplyScalar(1.05);
    tone(uni.uSkyCol.value, w.atmo, 0.80, '#ffffff', 0.20);
    // 0.62, not 0.75. The sky term is near-uniform over every upward face, so
    // too much of it flattens the modelling out of the shapes — which is the
    // one thing this page exists to show.
    uni.uSkyAmt.value = 0.62 + 0.16 * t;
    uni.uAmbient.value = 0.055 + 0.035 * t;
    bd(tone(skyU.uHor.value, w.atmo, 1.0, '#ffffff', 0.42), 1.35, 0.10);
    bd(tone(skyU.uZen.value, w.atmo, 0.42, '#1b3f6c', 0.35), 1.55, 0.10);
    tone(skyU.uGlow.value, w.atmo, 0.30);
    bd(tone(gndU.uNear.value, w.lo, 0.62), 1.55, 0.14);
    for (var q2 = 0; q2 < pods.length; q2++) {
      bd(tone(pods[q2].disc.material.color, w.lo, 0.78), 1.45, 0.14);
    }
    mComp.uniforms.uStr.value = 0.40;
    skyU.uStars.value = 0;
  }
  uni.uAoFloor.value = st.ao ? 0.34 : 1.0;
  uni.uOld.value = st.old ? 1 : 0;
}

/* ==========================================================================
   8.  CARDS + CONTROLS
   ========================================================================= */
var cardsEl = document.getElementById('cards');
var bigEl = document.getElementById('big');
var cards = [];
function pad2(k) { return (k < 10 ? '0' : '') + k; }
var breedBar = document.getElementById('breedbar');
var bigCode = document.getElementById('bigCode');

var libBar = document.getElementById('libbar');
var stripEl = document.getElementById('strip');
var stripList = document.getElementById('stripList');

function breeding() { return st.mode === 'breed'; }
function inLib() { return st.mode === 'library'; }
function selectable() { return breeding() || inLib(); }
function label(k) { return popMeta[k] ? popMeta[k].name : ''; }
function blurb(k) { return popMeta[k] ? popMeta[k].desc : ''; }

for (var ki = 0; ki < CAND.length; ki++) {
  var el = document.createElement('div');
  el.className = 'card';
  el.innerHTML = '<div class="top"><span class="num"></span><span class="nm"></span>' +
    '<span class="tag">KEPT</span></div><div class="tri"></div><div class="idea"></div>';
  el.addEventListener('click', (function (k) {
    return function (e) { e.stopPropagation(); hit(k); };
  })(ki));
  el.addEventListener('dblclick', (function (k) {
    return function (e) {
      e.stopPropagation();
      if (selectable()) { zoomTo(st.zoom === k ? -1 : k); sync(); }
    };
  })(ki));
  cardsEl.appendChild(el);
  cards.push(el);
}

/* ONE CLICK MEANS DIFFERENT THINGS IN THE THREE VIEWS, and it has to: on the
   originals shelf the only verb is "look closer", while in the breeder the verb
   you use forty times a session is "keep this one". Enlarging is the
   double-click wherever keeping is the single click. */
function hit(k) {
  if (!selectable()) { zoomTo(st.zoom === k ? -1 : k); return; }
  if (!pods[k].active) return;
  var at = st.sel.indexOf(k);
  if (at >= 0) st.sel.splice(at, 1);
  else {
    st.sel.push(k);
    // KEEPING IS REMEMBERING. There is no separate save button, because a
    // second deliberate action to preserve something you have already said you
    // like is friction with nothing on the other side of it.
    if (breeding()) libAdd(pop[k]);
  }
  refreshCards();
}

function refreshCards() {
  var i;
  for (i = 0; i < cards.length; i++) {
    var c = cards[i], t = api.trees[i] || { tris: 0 };
    var live = pods[i].active !== false;
    var isCtl = st.mode === 'originals' && CAND[i].ctl;
    var kept = selectable() && st.sel.indexOf(i) >= 0;
    c.className = 'card' + (isCtl ? ' ctl' : '') + (kept ? ' sel' : '') +
      (breeding() && popMeta[i] && popMeta[i].elite ? ' elite' : '');
    c.querySelector('.num').textContent = pad2(i);
    c.querySelector('.nm').textContent = label(i);
    c.querySelector('.tri').textContent = live
      ? (t.tris + ' TRIS' + (isCtl ? ' · CONTROL' : '')
         + (inLib() && st.libAll && podWorld[i] ? ' · ' + podWorld[i].toUpperCase() : ''))
      : '';
    c.querySelector('.idea').textContent = blurb(i);
    c.style.visibility = live ? 'visible' : 'hidden';
    // the ring under the tree turns too, so keeping reads in the 3D as well
    pods[i].ring.material.color.setHex(kept ? 0x33ff77 : 0x1d6a58);
    pods[i].ring.material.opacity = kept ? 0.95 : 0.5;
  }
  var n = st.sel.length;
  var bb = document.getElementById('bBreed');
  bb.disabled = !n;
  bb.textContent = n ? ('Breed from ' + n) : 'Keep some first';
  document.getElementById('bBack').disabled = !history.length;
  document.getElementById('genLbl').textContent = 'GEN ' + st.gen;

  var LB = libView();
  var pages = Math.max(1, Math.ceil(LB.length / pods.length));
  document.getElementById('libLbl').textContent =
    (st.libAll ? 'ALL WORLDS' : WRLD().name.toUpperCase()) + '  ' + LB.length + ' KEPT' + (pages > 1 ? '  ' + (st.libPage + 1) + '/' + pages : '');
  document.getElementById('bSend').disabled = !(inLib() && n);
  document.getElementById('bDrop').disabled = !(inLib() && n);
  document.getElementById('bPrev').disabled = !(inLib() && st.libPage > 0);
  document.getElementById('bNext').disabled = !(inLib() && st.libPage < pages - 1);
  document.getElementById('bCopyAll').disabled = !LB.length;
  refreshStrip();
}

/* THE RUNNING STRIP — the library, without leaving the population.
   It is text only on purpose: it is a tally you glance at while you work, not
   the place you judge from. Judging happens on the shelf, where the trees are
   rotating under the same light as everything else. */
function refreshStrip() {
  var SL = inLib() ? libView() : lib_();
  document.getElementById('stripN').textContent =
    (inLib() && st.libAll ? 'ALL' : WRLD().name.toUpperCase()) + ' · ' + SL.length;
  document.getElementById('stripWarn').textContent = libPersist ? '' : 'THIS SESSION ONLY';
  var sig = st.world + '|' + (inLib() && st.libAll) + '|' +
            SL.map(function (e) { return e.code.slice(0, 4); }).join('');
  if (stripList.dataset.sig === sig) return;
  stripList.dataset.sig = sig;
  stripList.innerHTML = '';
  if (!SL.length) {
    var d = document.createElement('div');
    d.className = 'empty';
    d.textContent = 'nothing kept yet — click a tree you like';
    stripList.appendChild(d);
    return;
  }
  for (var i = SL.length - 1; i >= 0; i--) {
    (function (e) {
      var r = document.createElement('div');
      r.className = 'row2';
      r.innerHTML = '<span class="n"></span><span class="t"></span><span class="x">&times;</span>';
      r.querySelector('.n').textContent = e.name;
      r.querySelector('.t').textContent = e.tris;
      r.addEventListener('click', function (ev) {
        if (ev.target.className === 'x') { libRemove([e.code]); refreshCards(); return; }
        recall(e.code);
      });
      stripList.appendChild(r);
    })(SL[i]);
  }
}
/* Put a saved tree back into the pool, in the first slot you have NOT kept, and
   keep it there — so crossing two old favourites is: recall, recall, breed. */
function recall(code) {
  if (!pop) seedPop();
  if (!breeding()) setView('breed');
  var g = decGenome(code);
  if (!g) return;
  var e = null, i;
  for (i = 0; i < lib_().length; i++) if (lib_()[i].code === code) e = lib_()[i];
  if (e) g.label = e.name;
  var slot = -1;
  for (i = 0; i < pop.length; i++) if (st.sel.indexOf(i) < 0) { slot = i; break; }
  if (slot < 0) slot = pop.length - 1;
  pop[slot] = g;
  if (st.sel.indexOf(slot) < 0) st.sel.push(slot);
  applyPop();
  sync();
}

function zoomTo(k) {
  st.zoom = k;
  if (k >= 0) {
    var t = api.trees[k];
    bigEl.querySelector('.num').textContent = pad2(k);
    bigEl.querySelector('.nm').textContent =
      label(k) + (st.mode === 'originals' && CAND[k].ctl ? ' · CONTROL' : '');
    bigEl.querySelector('.tri').textContent = t.tris + ' TRIS';
    bigEl.querySelector('.idea').textContent = blurb(k);
    bigEl.querySelector('.meta').textContent =
      'y ' + t.minY.toFixed(2) + '-' + t.maxY.toFixed(2) + '   max r ' + t.maxR.toFixed(2) +
      '   rBase ' + WIDES[st.wide].toFixed(2) +
      '   emis ' + (popMeta[k].emis || 0).toFixed(2) + '   tip ' + (popMeta[k].tip || 'mid');
    /* THE GENOME, ON SCREEN AND COPYABLE. It is the whole point of the integer
       genes: this string grows back the exact tree, so a favourite can be
       pasted into a message or into objectKinds.js instead of described. */
    if (popMeta[k] && popMeta[k].code) {
      bigCode.textContent = popMeta[k].code; bigCode.style.display = 'block';
    } else bigCode.style.display = 'none';
    bigEl.style.display = 'block';
  } else bigEl.style.display = 'none';
}
bigCode.addEventListener('click', function () {
  var ta = document.createElement('textarea');
  ta.value = bigCode.textContent;
  ta.style.position = 'fixed'; ta.style.opacity = '0';
  document.body.appendChild(ta); ta.select();
  var ok = false;
  try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
  document.body.removeChild(ta);
  var was = bigCode.textContent;
  bigCode.textContent = ok ? 'copied to clipboard' : was;
  if (ok) setTimeout(function () { bigCode.textContent = was; }, 900);
});

/* ONE DISPATCHER FOR WHAT IS ON THE PODS, used by boot and by the buttons.
   Boot used to call applyGallery() unconditionally while the state said BREED,
   so the page opened claiming to show "this world's own trees" and was in fact
   showing the eleven hand-built ones — switching planet then only changed the
   paint, which is exactly what the owner reported. A label that disagrees with
   what is drawn is worse than no label. */
function applyView() {
  for (var i = 0; i < pods.length; i++) pods[i].active = true;
  if (st.mode === 'breed') { if (!pop) seedPop(); else applyPop(); }
  else if (st.mode === 'library') applyLibrary();
  else applyGallery();
}
function setView(m) {
  if (st.mode === m) return;
  st.mode = m; st.zoom = -1; st.sel = [];
  bigEl.style.display = 'none';
  applyView();
  sync();
}
var setMode = setView;   // the old name, still used by the harness

var SUBHEAD = {
  originals: '— the eleven I hand-built. reference only — these cannot be bred',
  breed: '',
  library: '— everything you have kept',
};
var LEDE = {
  originals: 'The eleven shapes I wrote by hand, one algorithm each — the SAME eleven ' +
             'whatever world you pick, which is why only their colour changes here. ' +
             'Every planet’s own trees are in BREED.',
  breed: 'This planet’s own eleven trees. Click the ones you like to KEEP them; ' +
         'BREED makes children from those, NEW TREES rerolls the rest. ' +
         'Switch world to see what grows somewhere else.',
  library: '',
};

function setOn(id, on) { document.getElementById(id).classList.toggle('on', !!on); }
function sync() {
  setOn('bGal', st.mode === 'originals'); setOn('bBrd', breeding()); setOn('bLib', inLib());
  setOn('bNight', st.night); setOn('bDay', !st.night);
  setOn('bB0', st.bg === 0); setOn('bB1', st.bg === 1); setOn('bB2', st.bg === 2);
  setOn('bS0', st.size === 0); setOn('bS1', st.size === 1); setOn('bS2', st.size === 2);
  setOn('bW0', st.wide === 0); setOn('bW1', st.wide === 1); setOn('bW2', st.wide === 2);
  setOn('bSpin', st.spin); setOn('bGlow', st.bloom); setOn('bAo', st.ao); setOn('bFair', st.old);
  setOn('bAll', st.libAll);
  setOn('bM0', st.mut === 0); setOn('bM1', st.mut === 1); setOn('bM2', st.mut === 2);
  breedBar.classList.toggle('on', breeding() && st.zoom < 0);
  libBar.classList.toggle('on', inLib() && st.zoom < 0);
  stripEl.classList.toggle('on', st.mode !== 'originals' && st.zoom < 0);
  /* WHICH SET AM I LOOKING AT. The per-world trees were built and then hidden
     behind a view the page did not open on, so switching planet appeared to do
     nothing but change the paint. Both facts are now said out loud. */
  document.getElementById('subhead').textContent = breeding()
    ? '— ' + WRLD().name.toLowerCase() + ' · its own trees · generation ' + st.gen
    : st.mode === 'library' ? '— ' + WRLD().name.toLowerCase() + ' · kept'
    : '— hand-built reference · the same eleven on every world · cannot be bred';
  document.getElementById('sub').textContent = inLib()
    ? (libView().length
        ? 'Kept on ' + (st.libAll ? 'every world' : WRLD().name.toLowerCase()) +
          ', painted as ' + WRLD().name.toLowerCase() + '. Click one, then send it back into the pool.'
        : 'Nothing kept on ' + WRLD().name.toLowerCase() + ' yet. Breed some here, or press ALL WORLDS ' +
          'to see every shape you have kept, repainted in this palette.')
    : LEDE[st.mode];
  document.getElementById('hintG').style.display = selectable() ? 'none' : 'inline';
  document.getElementById('hintB').style.display = selectable() ? 'inline' : 'none';
  layout();
  refreshCards();
  if (st.zoom >= 0) zoomTo(st.zoom);
  light();
}
/* SWITCHING WORLD REPAINTS EVERYTHING AND KEEPS THE SHAPES.
   A genome is geometry; the world is paint. So the same eighteen trees can be
   looked at on all thirteen planets without breeding anything again — and the
   library follows, because each world keeps its own shelf. */
var wSel = document.getElementById('wSel');
for (var wi = 0; wi < WORLDS.length; wi++) {
  var o = document.createElement('option');
  o.value = WORLDS[wi].key;
  o.textContent = WORLDS[wi].name + ' — ' + WORLDS[wi].label;
  wSel.appendChild(o);
}
wSel.value = st.world;
wSel.addEventListener('change', function () { setWorld(wSel.value); });
function setWorld(k) {
  if (!WORLD_BY[k] || k === st.world) return;
  st.world = k; st.libPage = 0;
  wSel.value = k;
  if (inLib()) { st.sel = []; applyLibrary(); }
  else if (breeding()) {
    /* Reseed to the new planet's own eleven only if there is nothing to lose.
       Mid-run, switching world must REPAINT and not throw away the generations
       you have bred — so an untouched generation zero reseeds, and anything
       else keeps its shapes and changes palette. */
    if (st.gen === 0 && !st.sel.length && !history.length) seedPop();
    else applyPop();
  } else { st.sel = []; applyGallery(); }
  sync();
}

function btn(id, fn) { document.getElementById(id).addEventListener('click', fn); }
btn('bNight', function () { st.night = true; sync(); });
btn('bDay', function () { st.night = false; sync(); });
btn('bB0', function () { st.bg = 0; sync(); });
btn('bB1', function () { st.bg = 1; sync(); });
btn('bB2', function () { st.bg = 2; sync(); });
btn('bS0', function () { st.size = 0; layout(); sync(); });
btn('bS1', function () { st.size = 1; layout(); sync(); });
btn('bS2', function () { st.size = 2; layout(); sync(); });
btn('bW0', function () { st.wide = 0; sync(); });
btn('bW1', function () { st.wide = 1; sync(); });
btn('bW2', function () { st.wide = 2; sync(); });
btn('bSpin', function () { st.spin = !st.spin; sync(); });
btn('bGlow', function () { st.bloom = !st.bloom; sync(); });
btn('bAo', function () { st.ao = !st.ao; sync(); });
btn('bFair', function () { st.old = !st.old; sync(); });
btn('bGal', function () { setView('originals'); });
btn('bBrd', function () { setView('breed'); });
btn('bLib', function () { setView('library'); });
btn('bBreed', function () { if (breed()) sync(); });
btn('bNew', function () { freshPop(); sync(); });
btn('bBack', function () { if (stepBack()) sync(); });
btn('bReset', function () { seedPop(); sync(); });
btn('bSend', function () {
  var codes = st.sel.map(function (i) { return libSlots[i]; }).filter(Boolean);
  if (!codes.length) return;
  if (!pop) seedPop();
  setView('breed');
  st.sel = [];
  for (var i = 0; i < codes.length && i < pop.length; i++) {
    var g = decGenome(codes[i]);
    for (var j = 0; j < lib_().length; j++) if (lib_()[j].code === codes[i]) g.label = lib_()[j].name;
    pop[i] = g; st.sel.push(i);
  }
  applyPop(); sync();
});
btn('bDrop', function () {
  libRemove(st.sel.map(function (i) { return libSlots[i]; }).filter(Boolean));
  st.sel = []; applyLibrary(); sync();
});
btn('bAll', function () { st.libAll = !st.libAll; st.libPage = 0; st.sel = []; applyLibrary(); sync(); });
btn('bPrev', function () { st.libPage--; st.sel = []; applyLibrary(); sync(); });
btn('bNext', function () { st.libPage++; st.sel = []; applyLibrary(); sync(); });
btn('bCopyAll', function () {
  var txt = lib_().map(function (e) { return e.name + '  ' + e.code; }).join(String.fromCharCode(10));
  var ta = document.createElement('textarea');
  ta.value = txt; ta.style.position = 'fixed'; ta.style.opacity = '0';
  document.body.appendChild(ta); ta.select();
  var ok = false; try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
  document.body.removeChild(ta);
  var b = document.getElementById('bCopyAll'), was = b.textContent;
  b.textContent = ok ? 'copied' : 'copy failed';
  setTimeout(function () { b.textContent = was; }, 900);
});
btn('bM0', function () { st.mut = 0; sync(); });
btn('bM1', function () { st.mut = 1; sync(); });
btn('bM2', function () { st.mut = 2; sync(); });

window.addEventListener('keydown', function (e) {
  var k = e.key.toLowerCase();
  if (k === 'n') st.night = !st.night;
  else if (k === '1') { st.size = 0; layout(); }
  else if (k === '2') { st.size = 1; layout(); }
  else if (k === '3') { st.size = 2; layout(); }
  else if (k === '4') st.wide = 0;
  else if (k === '5') st.wide = 1;
  else if (k === '6') st.wide = 2;
  else if (k === '7') st.bg = 0;
  else if (k === '8') st.bg = 1;
  else if (k === '9') st.bg = 2;
  else if (k === ' ') { st.spin = !st.spin; e.preventDefault(); }
  else if (k === 'b') st.bloom = !st.bloom;
  else if (k === 'o') st.ao = !st.ao;
  else if (k === 'g') st.old = !st.old;
  else if (k === '[' || k === ']') {
    var at = 0;
    for (var wj = 0; wj < WORLDS.length; wj++) if (WORLDS[wj].key === st.world) at = wj;
    setWorld(WORLDS[(at + (k === ']' ? 1 : WORLDS.length - 1)) % WORLDS.length].key);
    return;
  }
  else if (k === 'escape') zoomTo(-1);
  else if (k === 'm') {
    setView(st.mode === 'originals' ? 'breed' : st.mode === 'breed' ? 'library' : 'originals');
    return;
  }
  else if (k === 'enter' && breeding()) { if (!breed()) return; }
  else if (k === 'r' && breeding()) freshPop();
  else if (k === 'backspace' && breeding()) { if (!stepBack()) return; }
  else return;
  sync();
});

/* PICK IN SCREEN SPACE, not with a ray against a box.
   The box version selected the wrong tree: the pods are only ~2 radii apart
   and the camera looks down at them, so a ray aimed at the TOP of a middle-row
   tree passes clean through the front row's bounding box first and picks that
   one instead. Nearest projected centre is what a person means by "that one",
   and the accept radius is derived from the pod's own on-screen size so it
   works at 3 m and at 30 m without a magic number. */
var _sv = new T.Vector3();
function toScreen(x, y, z) {
  _sv.set(x, y, z).project(cam);
  return [(_sv.x * 0.5 + 0.5) * W, (-_sv.y * 0.5 + 0.5) * H, _sv.z];
}
function podScreen(i) { var s = S(), xz = podXZ(i); return toScreen(xz[0], s * 0.42, xz[1]); }
function podPick(px, py) {
  var s = S(), best = -1, bd = 1e18;
  for (var i = 0; i < pods.length; i++) {
    var a = podScreen(i);
    if (a[2] > 1) continue;
    var d = (a[0] - px) * (a[0] - px) + (a[1] - py) * (a[1] - py);
    if (d < bd) { bd = d; best = i; }
  }
  if (best < 0) return -1;
  var c = podScreen(best), xz = podXZ(best);
  var e = toScreen(xz[0] + s * 1.0, s * 0.42, xz[1]);
  var rad = Math.max(28, Math.abs(e[0] - c[0]) * 1.15);
  return Math.sqrt(bd) <= rad ? best : -1;
}
cv.addEventListener('click', function (e) {
  var k = podPick(e.clientX, e.clientY);
  if (k < 0) { if (st.zoom >= 0) { zoomTo(-1); sync(); } return; }
  hit(k);
  sync();
});
cv.addEventListener('dblclick', function (e) {
  // the originals shelf already zooms on a single click; acting here too would
  // toggle it straight back off again
  if (!selectable()) return;
  var k = podPick(e.clientX, e.clientY);
  if (k >= 0) { zoomTo(st.zoom === k ? -1 : k); sync(); }
});

/* ==========================================================================
   9.  LOOP
   ========================================================================= */
function resize() {
  W = window.innerWidth || 1280; H = window.innerHeight || 720;
  renderer.setSize(W, H, false);
  cam.aspect = W / H; cam.updateProjectionMatrix();
  allocRT();
}
window.addEventListener('resize', resize);

var _m = new T.Matrix4(), _q = new T.Quaternion(), _p = new T.Vector3(), _s = new T.Vector3();
var _pv = new T.Vector3(), _YAX = new T.Vector3(0, 1, 0), _I = new T.Matrix4();
var spinT = 0, last = performance.now();

function tick(now) {
  var dt = Math.min(0.05, (now - last) / 1000); last = now;
  if (st.spin) spinT += dt * 0.26;

  frame();
  var k = 1 - Math.pow(0.0018, dt);
  camPos.lerp(wantPos, k); camTgt.lerp(wantTgt, k);
  cam.position.copy(camPos); cam.up.set(0, 1, 0); cam.lookAt(camTgt);
  sky.position.copy(camPos);

  var s = S(), i, w = WIDES[st.wide];
  _q.setFromAxisAngle(_YAX, spinT);
  // exactly the instance matrix surfaceObjects.js builds: (r, h, r)
  _p.set(0, 0, 0); _s.set(s * w, s, s * w);
  _m.compose(_p, _q, _s);
  for (i = 0; i < pods.length; i++) {
    var p = pods[i];
    p.grp.visible = p.active !== false && (st.zoom < 0 || st.zoom === i);
    if (!p.grp.visible) continue;
    p.im.setMatrixAt(0, _m); p.im.instanceMatrix.needsUpdate = true;
    p.hum.setMatrixAt(0, _I); p.hum.instanceMatrix.needsUpdate = true;
  }

  renderer.setRenderTarget(rtScene);
  renderer.clear();
  renderer.render(scene, cam);
  if (st.bloom) {
    mBright.uniforms.tD.value = rtScene.texture; pass(mBright, rtA);
    mBlurH.uniforms.tD.value = rtA.texture; pass(mBlurH, rtB);
    mBlurV.uniforms.tD.value = rtB.texture; pass(mBlurV, rtA);
    mComp.uniforms.tB.value = rtA.texture;
    mComp.uniforms.tD.value = rtScene.texture;
    pass(mComp, null);
  } else {
    mComp.uniforms.tD.value = rtScene.texture;
    mComp.uniforms.tB.value = rtScene.texture;
    var keep = mComp.uniforms.uStr.value;
    mComp.uniforms.uStr.value = 0;
    pass(mComp, null);
    mComp.uniforms.uStr.value = keep;
  }

  for (i = 0; i < pods.length; i++) {
    var e2 = cards[i];
    if (st.zoom >= 0) { e2.style.display = 'none'; continue; }
    var xz2 = podXZ(i), sc = toScreen(xz2[0], 0, xz2[1] + s * (DISC + 0.05));
    e2.style.display = (sc[2] < 1 && sc[0] > -150 && sc[0] < W + 150) ? 'block' : 'none';
    e2.style.left = sc[0].toFixed(1) + 'px';
    e2.style.top = Math.min(H - 62, sc[1] + 3).toFixed(1) + 'px';
  }

  api.frames++;
  requestAnimationFrame(tick);
}

try {
  /* popMeta is what every label reads from, and the pod-build loop above does
     not fill it (its `var` initialiser runs later and would wipe it anyway).
     Without this the first screen comes up with eleven blank cards. */
  applyView();
  resize(); layout(); sync(); frame();
  requestAnimationFrame(tick);
} catch (e) { die(e); }

api.zoomTo = zoomTo;
api.set = function (o) { for (var k in o) st[k] = o[k]; layout(); sync(); frame(); };
api.snap = function () { camPos.copy(wantPos); camTgt.copy(wantTgt); };
api.spinTo = function (a) { spinT = a; };
api.podScreen = podScreen;      // so the verifier clicks where a pod really is
api.world = function (k) { if (k) setWorld(k); return st.world; };
api.libAll = function (v) { if (v !== undefined) { st.libAll = !!v; st.libPage = 0; st.sel = []; if (inLib()) applyLibrary(); sync(); } return st.libAll; };
api.worlds = function () { return WORLDS.map(function (w) { return w.key; }); };
api.mode = setView;
api.newTrees = function () { freshPop(); sync(); return true; };
api.libs = function () {
  var o = {};
  for (var k in libs) if (libs[k] && libs[k].length) o[k] = libs[k].slice();
  return o;
};
api.lib = function () { return lib_().map(function (e) { return { code: e.code, name: e.name, tris: e.tris }; }); };
api.libClear = function () { libs = {}; libStore(); if (inLib()) applyLibrary(); sync(); };
api.libPersists = function () { return libPersist; };
api.rolls = function () { return rolls; };
api.recall = recall;
/* THE HARNESS TAKES THE SAME DOOR THE PERSON DOES.
   This used to assign st.sel directly, which skipped hit() — and hit() is where
   keeping a tree also remembers it. So a test could "keep" three trees and the
   library would stay empty, and the check that was supposed to prove keeping
   works was quietly exercising a path no human can reach. VESPER has been
   bitten by a private test door standing in for the real one before. */
api.select = function (list) {
  st.sel = [];
  for (var i = 0; i < list.length; i++) hit(list[i]);
  refreshCards();
};
api.breed = function () { var ok = breed(); sync(); return ok; };
api.back = function () { var ok = stepBack(); sync(); return ok; };
api.reset = function () { seedPop(); sync(); };
api.pop = function () { return pop ? pop.map(function (g) { return g.slice(); }) : null; };
api.codes = function () { return popMeta.map(function (m) { return m.code; }); };
api.load = function (code, slot) { var ok = loadGenome(code, slot); sync(); return ok; };
api.grow = function (code) {                    // build a genome outside the page state
  var g = decGenome(code); if (!g) return null;
  var geo = growTree(g), m = measure(geo), pa = geo.attributes.position;
  var sum = 0; for (var i = 0; i < pa.count; i++) sum += pa.getX(i) * 3.7 + pa.getY(i) * 11.3 + pa.getZ(i) * 5.1;
  geo.dispose();
  return { tris: m.tris, minY: m.minY, maxY: m.maxY, maxR: m.maxR, hash: sum.toFixed(6) };
};
api.randomCode = function (seed) {
  var R = rnd(seed >>> 0); return encGenome(randGenome(R));
};
})();
