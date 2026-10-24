/* ============================================================================
   WHAT GROWS WHERE — a recipe per world.

   Repainting one set of shapes in thirteen palettes is still one theme wearing
   thirteen coats, and the owner spotted that immediately: "each planet should
   be different trees". A planet does not merely colour its trees differently,
   it grows different ones.

   So each world names which trunk / branch / foliage / symmetry it draws from,
   and the band each continuous gene is sampled inside. Generation zero is then
   eleven genomes rolled from that recipe — related to each other, and
   unmistakably not the set next door. It is all still ONE grammar and ONE
   builder, so a Frozen conifer can still be crossed with an Arid hoodoo.

   This file is ART DIRECTION, in the same sense objectKinds.js is: it should be
   editable without reading a line of grow.js. Nothing here is a colour — the
   palette comes from worlds.js, which is generated from the game.

   A gene a recipe does not mention is sampled from the MIDDLE 60% of its range
   rather than the whole of it. Sampling the full range drowns the recipe in
   noise, which is the failure mode of every "just randomise it" generator.
   ========================================================================= */
var RECIPE = {
  /* black basalt over a blazing mantle — charred, angular, upswept, sparse */
  volcanic: { trunk: ['single', 'single', 'multi'], branch: ['none', 'whorl'],
    fol: ['blade', 'blade', 'none'], sym: ['radial', 'scattered'],
    trunkH: [0.50, 1.00], taper: [0.55, 0.90], sides: [4, 6], flute: [0, 0.16],
    folSize: [0.30, 0.70], folFlat: [0.15, 0.45], folDroop: [-0.30, 0.00],
    folN: [5, 11], folTilt: [0.10, 0.60], emis: [0.15, 0.50] },

  /* translucent needles, lit from inside, leaning in clusters */
  glacier: { trunk: ['single', 'single', 'cage'], branch: ['none'],
    fol: ['blade', 'blade', 'membrane'], sym: ['radial'],
    trunkH: [0.35, 0.80], taper: [0.70, 0.92], sides: [3, 5],
    folSize: [0.55, 1.00], folFlat: [0.10, 0.35], folDroop: [-0.34, -0.05],
    folN: [5, 12], folTilt: [0.20, 0.75], matSplit: [0.60, 1.00], emis: [0.50, 0.90] },

  /* kelp: thin stems, long ribbons, everything hanging */
  oceanic: { trunk: ['single', 'multi'], branch: ['none'],
    fol: ['ribbon'], sym: ['radial', 'scattered'],
    trunkH: [0.25, 0.60], taper: [0.30, 0.70], trunkR: [0.25, 0.60],
    folSize: [0.70, 1.00], folFlat: [0.60, 1.00], folDroop: [0.15, 0.40],
    folN: [7, 12], emis: [0.05, 0.35] },

  /* the one true forest: forking limbs and a clustered broadleaf mass */
  verdant: { trunk: ['single', 'single', 'multi'], branch: ['fork', 'fork', 'whorl'],
    fol: ['blob', 'blob', 'disc'], sym: ['scattered'],
    trunkH: [0.35, 0.75], taper: [0.40, 0.70], sides: [5, 7],
    /* MANY SMALL LUMPS, NOT FEW BIG ONES. At 0.35-0.80 the blobs on adjacent
       branch tips overlapped into one flat plate and every tree came back as a
       dark trunk under a green slab. Size is what separates a canopy from a
       lid; the jag is what stops the lumps reading as spheres. */
    folSize: [0.17, 0.40], folFlat: [0.30, 0.70], folDroop: [-0.05, 0.25],
    folN: [6, 12], folJag: [0.26, 0.42],
    branchLen: [0.45, 0.90], branchAngle: [0.45, 1.05],
    matSplit: [0.00, 0.50], emis: [0.05, 0.25] },

  /* prisms: a burst of facets from one root, not a curve on them */
  crystal: { trunk: ['single', 'single', 'cage'], branch: ['none'],
    fol: ['blade'], sym: ['radial', 'scattered'],
    trunkH: [0.20, 0.55], taper: [0.60, 0.92], sides: [3, 4],
    folSize: [0.70, 1.00], folFlat: [0.20, 0.50], folTilt: [0.30, 0.75],
    folN: [8, 12], emis: [0.55, 0.90] },

  /* precipitated chimneys: squat, fat, layered shelves */
  toxic: { trunk: ['single', 'multi'], branch: ['none', 'whorl'],
    fol: ['disc', 'membrane', 'disc'], sym: ['scattered'],
    trunkH: [0.50, 0.95], taper: [0.15, 0.50], trunkR: [0.60, 1.00],
    folSize: [0.40, 0.80], folFlat: [0.10, 0.30], folRise: [0.60, 1.00],
    folN: [3, 8], emis: [0.15, 0.40] },

  /* a dead forest: bare wandering trunks with a few wisps left on them */
  ashen: { trunk: ['single'], branch: ['whorl', 'fork'],
    fol: ['none', 'none', 'blob'], sym: ['scattered'],
    trunkH: [0.70, 1.00], wander: [0.15, 0.34], taper: [0.60, 0.92],
    sides: [4, 6], flute: [0.10, 0.30], branchN: [2, 3], branchLevels: [2, 4],
    branchLen: [0.30, 0.80], branchDroop: [-0.20, 0.15],
    folSize: [0.10, 0.30], folN: [1, 4], emis: [0.02, 0.12] },

  /* hoodoos: a fat, barely-tapering column under a wide flat caprock */
  desert: { trunk: ['single'], branch: ['none'],
    fol: ['disc', 'disc', 'membrane'], sym: ['scattered'],
    trunkH: [0.55, 0.90], taper: [0.15, 0.45], trunkR: [0.70, 1.00], sides: [5, 7],
    folSize: [0.60, 1.00], folFlat: [0.08, 0.25], folDroop: [0.05, 0.30],
    folN: [1, 4], folRise: [0.00, 0.40], matSplit: [0.75, 1.00], emis: [0.02, 0.06] },

  /* ejecta: not much survives here, and it should look like it */
  barren: { trunk: ['single', 'multi'], branch: ['none', 'whorl'],
    fol: ['none', 'blob', 'blob'], sym: ['scattered'],
    trunkH: [0.25, 0.60], taper: [0.40, 0.80], sides: [3, 5],
    folSize: [0.15, 0.40], folN: [2, 5], folFlat: [0.30, 0.80],
    branchLen: [0.20, 0.45], matSplit: [0.55, 0.95], emis: [0.02, 0.05] },

  /* taiga: a full-height stem with tight drooping whorls all the way up */
  tundra: { trunk: ['single'], branch: ['whorl'],
    fol: ['blob', 'blob', 'disc'], sym: ['radial'],
    trunkH: [0.85, 1.00], taper: [0.70, 0.92], sides: [5, 7],
    branchLevels: [3, 4], branchN: [4, 5], branchLen: [0.35, 0.70],
    branchAngle: [0.90, 1.40], branchDroop: [0.15, 0.40],
    folSize: [0.12, 0.30], folFlat: [0.30, 0.70], folN: [7, 12], folJag: [0.22, 0.40],
    /* snow on top: tundra is one of the worlds whose roster really does paint
       from `peak`, so its trees are allowed to take the pale tip */
    matSplit: [0.75, 1.00], emis: [0.02, 0.06] },

  /* torn ridges of chilled skin, still hot: blades thrown sharply upward */
  inferno: { trunk: ['single', 'multi'], branch: ['whorl', 'none'],
    fol: ['blade', 'blade', 'membrane'], sym: ['scattered', 'radial'],
    trunkH: [0.40, 0.85], taper: [0.50, 0.85], wander: [0.05, 0.25],
    folSize: [0.50, 0.95], folFlat: [0.15, 0.45], folDroop: [-0.34, -0.10],
    folTilt: [0.35, 0.75], folN: [5, 10], emis: [0.35, 0.75] },

  /* spore stalks: a thin stem carrying one heavy glowing cap */
  fungal: { trunk: ['single', 'single', 'multi'], branch: ['none'],
    fol: ['disc', 'disc', 'blob'], sym: ['scattered'],
    trunkH: [0.65, 1.00], taper: [0.55, 0.90], trunkR: [0.22, 0.50],
    folSize: [0.60, 1.00], folFlat: [0.15, 0.45], folDroop: [0.15, 0.40],
    folN: [1, 4], folRise: [0.00, 0.35], emis: [0.45, 0.85] },

  /* crust fragments driven into the ground along the chasm rims */
  shattered: { trunk: ['single'], branch: ['none', 'whorl'],
    fol: ['blade', 'membrane', 'blade'], sym: ['bilateral', 'scattered'],
    trunkH: [0.30, 0.70], taper: [0.60, 0.90], sides: [3, 4],
    folSize: [0.60, 1.00], folFlat: [0.20, 0.60], folTilt: [-0.20, 0.50],
    folN: [4, 9], emis: [0.15, 0.40] },
};

function hashStr(s) {
  var h = 2166136261;
  for (var i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
  return h >>> 0;
}
function pickFrom(a, R) { return a[Math.min(a.length - 1, Math.floor(R() * a.length))]; }

/* One genome from a world's recipe. Deterministic in (world, index), so the
   same planet always opens on the same eleven trees. */
function seedGenome(worldKey, i) {
  var rc = RECIPE[worldKey] || RECIPE.verdant;
  var R = rnd(hashStr(worldKey) + i * 7919 + 13);
  var o = {}, nm, r;
  o.trunkMode = pickFrom(rc.trunk || ['single'], R);
  o.branchMode = pickFrom(rc.branch || ['none'], R);
  o.foliage = pickFrom(rc.fol || ['blob'], R);
  o.symmetry = pickFrom(rc.sym || ['scattered'], R);
  for (nm in RANGE) {
    r = rc[nm];
    if (r) { o[nm] = r[0] + (r[1] - r[0]) * R(); }
    else {
      var f = RANGE[nm];
      o[nm] = f[0] + (f[1] - f[0]) * (0.20 + 0.60 * R());
    }
  }
  var g = makeGenome(o);
  g[GI.seed] = Math.floor(R() * 256);
  return g;
}
function seedSetFor(worldKey, n) {
  var out = [];
  for (var i = 0; i < n; i++) out.push(seedGenome(worldKey, i));
  return out;
}
