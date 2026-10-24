/* ============================================================================
   THE MATERIAL FOR THINGS THAT STAND ON THE GROUND.

   It shares the WORLD'S UNIFORM OBJECT — the same one the orbital sphere and
   the terrain use. That is the same decision terrainShader.js made and for the
   same reason: a spire lit by its own private sun direction would drift out of
   step with the ground it stands on the moment anything about the lighting
   changed, and the error would look like a bug in the geometry. Sharing makes
   that impossible rather than unlikely.

   (Share it at CONSTRUCTION. Never reassign material.uniforms afterwards —
   DECISIONS records two separate days lost to that trap.)

   The lighting is deliberately the same three terms the ground has, in the same
   order: sun with a soft wrap, hemispheric sky, and emissive. An object that is
   lit by a different formula reads as a sticker.
   ========================================================================= */

import * as T from 'three';
import { growFromCode, isGenome } from './treeGeom.js';

const VERT = /* glsl */`
precision highp float;
in vec3 aCol;          // per instance, from the world's own palette
/* THE SECOND COLOUR, and it is the difference between a tree and a green cone.
   Every object was ONE flat tone shaded by angle: a trunk was the same colour
   as its canopy, a boulder the same top and bottom. Nothing in a stylised
   look survives that — the whole read of a No Man's Sky prop is that its
   parts are different colours. Both come from the world's own palette, so
   this cannot drift from the ground it stands on. */
in vec3 aCol2;
in float aShade;       // per instance brightness, so a stand is not one flat tone
in vec3 aUp;           // this object's local UP (its own radial direction)
/* THE ONE NEW PER-VERTEX ATTRIBUTE, and it is per FAMILY rather than per
   instance, so it costs nothing to have. .x is the material (0 bark, 1
   foliage), .y is baked exposure (0 buried in the canopy, 1 out in the open).
   It replaces the smoothstep(0.16, 0.52, vY) guess below, which could only ever
   say "anything above a sixth of the height is canopy" — true of a cone and
   false of a tree with a low branch. geometryFor() synthesises it from that
   same old guess for any family that has no opinion, so nothing regresses. */
in vec2 aPart;
in float aEmis;        // per instance: a world now has TWO rosters (see biome.js)
                       // and they glow by different amounts, so this cannot be
                       // a per-world uniform any more
out vec3 vN;
out vec3 vCol;
out vec3 vCol2;
/* VIEW-space normal. vN is in the planet's frame, so the existing emissive rim
   (which tests abs(n.z)) is really a fixed directional bias rather than a rim —
   it is left alone because it is tuned art, but a silhouette light needs to
   follow the EYE, and that needs this. */
out vec3 vNv;
out float vShade;
out vec3 vUp;
out float vEmis;
/* Height up this object, 0 at the base and 1 at the tip. Every geometry in
   GEOM below is authored normalised that way on purpose (see the note there),
   so this needs no uniform and no per-family special case. */
out float vY;
out float vMat;
out float vAo;

void main(){
  /* The instance matrix carries position, lean and size. Normals must go
     through its inverse transpose or a leaning object is lit as though it stood
     upright — but these are cones and cylinders scaled non-uniformly, and for
     that the transpose of the upper 3x3 is close enough and far cheaper than
     inverting per vertex. */
  mat3 im = mat3(instanceMatrix);
  vN = normalize(im * normal);
  /* The local UP at this object: an instance sits on a sphere, so "up" is its
     own radial direction, not world +Y. It arrives as an ATTRIBUTE rather than
     being derived from the instance matrix, because instance positions are
     stored relative to their chunk's origin — that is the whole precision
     scheme (a planet is 850 km across and float32 gives out at 0.1 m there) —
     so the matrix translation is a small local offset and normalising it would
     give the direction to the chunk corner, not to the planet's centre. */
  vUp = normalize(aUp);
  vCol = aCol;
  vCol2 = aCol2;
  vNv = mat3(modelViewMatrix) * vN;
  vShade = aShade;
  vEmis = aEmis;
  vY = position.y;
  vMat = aPart.x;
  vAo = aPart.y;
  gl_Position = projectionMatrix * modelViewMatrix * instanceMatrix * vec4(position, 1.0);
}
`;

const FRAG = /* glsl */`
precision highp float;
in vec3 vN;
in vec3 vCol;
in vec3 vCol2;
in vec3 vNv;
in float vShade;
in vec3 vUp;
in float vEmis;
in float vY;
in float vMat;
in float vAo;
layout(location = 0) out vec4 outColor;

uniform vec3  uSun, uSunCol, uSkyCol;
uniform float uAmbient, uSkyAmt;

float sat(float x){ return clamp(x, 0.0, 1.0); }

void main(){
  vec3 n = normalize(vN);
  /* The bred families include one-layer membranes and ribbons, drawn double
     sided. Light a back face as the surface it is, not as its own shadow. */
  if (!gl_FrontFacing) n = -n;
  vec3 up = normalize(vUp);

  float ndl  = dot(n, uSun);
  float lit  = sat(ndl);
  float wrap = sat((ndl + 0.20)/1.20);
  float skyF = 0.5 + 0.5*dot(n, up);

  /* THE SKY TERM IS SCALED DOWN FOR OBJECTS, AND IT IS AN EXPOSURE FIX.
     archetypes.js rule 1 sizes every albedo against a lit multiplier that peaks
     around 1.22, above which the bloom threshold is crossed and ACES rolls the
     colour off toward white. Objects were quietly exempt from that budget: they
     take the sky term at full strength, and an upward-facing cap gets skyF = 1,
     which put their total near 1.6. Desert's hoodoos paint from "peak"
     (#c1a171, about 0.53 linear); times the per-object shade times 1.6 lands
     over the line, and they rendered as flat white parasols with no modelling
     in them at all.

     It was NOT a wrong colour and no palette edit would have fixed it. Proved
     by ablation — tests/standshot.mjs --sweep=uSkyAmt:0,0.5,1.5 — where the
     same hoodoos come back the authored tan with the sky term off.

     0.55 brings the object budget to about 1.38, under the line, and it is also
     the more physical number: flat ground sees the whole hemisphere, while a
     convex object standing on that ground shades itself and its neighbours
     shade it. Emissive is added after this and is still allowed to bloom — on
     the worlds that glow, that is the art. */
  /* TWO-TONE UP THE OBJECT. vY is normalised height, so this is a trunk/canopy
     split on a tree, a darker footing under a rock, a stem under a cap. The
     band is deliberately narrow-ish and low: foliage should start near the
     bottom third, not fade in over the whole height, or it reads as a gradient
     rather than as two materials. */
  /* THE MATERIAL SPLIT NOW COMES FROM THE GEOMETRY. vMat says which vertices
     are foliage instead of the shader guessing from height, and vAo is baked
     occlusion — a leaf deep inside a canopy is darker than one on the outside,
     which is most of what makes a mass of lumps read as a mass rather than as
     a lump. */
  vec3 base = mix(vCol, vCol2, vMat);
  vec3 col = base * vShade * mix(0.34, 1.0, vAo);

  /* SILHOUETTE LIGHT — the cheapest thing that makes a prop read as a prop
     rather than as a patch of the background. Objects are painted from the same
     palette as the ground they stand on (deliberately — see objectKinds.js),
     which is exactly why they vanish into it: verdant's trees are the world's
     green standing on the world's green. A view-space rim puts the sky colour
     around the edge facing away from the eye, so the shape separates without
     changing its material. Coloured from uSkyCol so it cannot drift from the
     sky actually drawn. */
  float rimV = pow(1.0 - sat(abs(normalize(vNv).z)), 3.0);

  /* ALL REFLECTED LIGHT IS GATHERED FIRST, THEN CAPPED. EMISSIVE IS ADDED
     AFTER, AND ONLY EMISSIVE MAY BLOOM.

     That is exactly what archetypes.js rule 1 says, and objects had never
     obeyed it. Capping the ALBEDO was tried first and did not work, because the
     albedo was not what crossed the line: sun 1.06 + ambient + sky + rim came
     to about 1.9, so even a legal 0.72 albedo landed far over the 0.95 bloom
     threshold and ACES rolled it to white. Tundra's snow-capped conifers came
     back as glowing white cones and did not move at all when the albedo was
     clamped — which is what proved the fault was downstream of it.

     Capping the SUM fixes every route at once: the sky term, the tip colour,
     the rim, a bright per-object shade, and whatever the next roster invents.
     Scaled UNIFORMLY rather than clamped per channel, or a saturated colour
     would be pulled toward grey — the same failure wearing different clothes.
     0.92 sits just under the threshold, so lit rock never blooms and a spire
     that is MEANT to glow still can. */
  vec3 outc = col * (lit*0.92 + wrap*0.14) * uSunCol
            + col * uAmbient
            + col * uSkyCol * (uSkyAmt * skyF * 0.55)
            + base * mix(0.34, 1.0, vAo) * uSkyCol * (rimV * 0.42);
  float lmx = max(max(outc.r, outc.g), outc.b);
  outc *= min(1.0, 0.92/max(lmx, 1e-4));

  /* EMISSIVE IS THE POINT on the worlds that have it — the reference's spires
     are "translucent, internally lit". A rim weight makes the light come from
     INSIDE: facing away from the eye is thin material, so it glows more, which
     is the cheapest approximation of translucency there is and it costs one
     dot product. Added AFTER the cap, on purpose: this is the one thing allowed
     over the line. */
  float rim = pow(1.0 - sat(abs(n.z)), 2.0);
  outc += col * vEmis * (0.55 + 1.45*rim);

  /* CONTACT OCCLUSION — the difference between a thing standing IN the ground
     and a decal lying on top of it.

     Every object here was uniformly lit from base to tip, so it met the terrain
     at a hard edge with no darkening at all, and the eye reads that as a
     cut-out. It is most obvious where the roster paints from a pale field:
     desert's hoodoos came back as flat white parasols with no modelling in them
     anywhere. Reducing their brightness alone does not fix that — a dimmer flat
     shape is still a flat shape. What was missing is the shadow an object casts
     on ITSELF and on the ground it is planted in.

     vY is normalised height up the object, so this costs one smoothstep and
     needs nothing to know about the terrain. Applied AFTER the emissive on
     purpose: a glowing stalk should be occluded at its foot too, or the
     emissive worlds keep the sticker look that the lit ones just lost. */
  outc *= mix(0.38, 1.0, smoothstep(0.0, 0.20, vY));

  outColor = vec4(outc, 1.0);
}
`;

/* ONE GEOMETRY PER FAMILY, BUILT FROM A SILHOUETTE.
   The first version was a single cone per family and the owner's verdict was
   exact: "there's only like stumps in the ground". A cone IS a stump — it has
   no trunk, no waist, no tiers, so at any distance it reads as a lump of ground
   rather than as a thing growing out of it.

   These are LatheGeometry profiles: a 2D outline (x = radius, y = height, both
   normalised 0..1) spun about the vertical. That buys a real silhouette for the
   same vertex budget as a cone and, importantly, for the same INSTANCED draw —
   the shape is what changes, not the cost. Merging separate trunk and canopy
   meshes would have needed BufferGeometryUtils and two draw calls per family.

   Height is normalised so scaling by (radius, height, radius) in the instance
   matrix stands the object ON the ground with its tip at `height`. */
const GEOM = {};

const V2 = (x, y) => new T.Vector2(x, y);

/* Profiles run BOTTOM to TOP. A radius of exactly 0 at the tip closes the
   shape; a non-zero radius at y=0 leaves the base open, which is invisible
   because it is buried in the ground. */
const PROFILES = {
  // tiered conifer: flared root, bare trunk, three skirts of foliage
  tree:    [V2(0.34, 0.00), V2(0.13, 0.10), V2(0.11, 0.26), V2(1.00, 0.30),
            V2(0.42, 0.52), V2(0.78, 0.55), V2(0.34, 0.74), V2(0.55, 0.77),
            V2(0.18, 0.92), V2(0.00, 1.00)],
  // dead: bare tapering trunk, snapped off, a couple of stubs
  snag:    [V2(0.42, 0.00), V2(0.26, 0.14), V2(0.30, 0.18), V2(0.18, 0.46),
            V2(0.24, 0.50), V2(0.13, 0.78), V2(0.09, 0.95), V2(0.00, 1.00)],
  // soft fungal stalk: thin stem swelling into a cap
  stalk:   [V2(0.16, 0.00), V2(0.10, 0.30), V2(0.09, 0.62), V2(0.20, 0.72),
            V2(0.62, 0.80), V2(0.90, 0.88), V2(0.44, 0.97), V2(0.00, 1.00)],
  // grown crystal: a needle with a waist and a shoulder
  spire:   [V2(0.62, 0.00), V2(0.90, 0.06), V2(0.44, 0.34), V2(0.30, 0.62),
            V2(0.14, 0.86), V2(0.00, 1.00)],
  // chilled lava: a blade, wide at the root, torn at the top
  spine:   [V2(0.90, 0.00), V2(0.70, 0.22), V2(0.44, 0.44), V2(0.34, 0.70),
            V2(0.12, 0.92), V2(0.00, 1.00)],
  // wind-carved: a mushroom of caprock on a narrow neck
  hoodoo:  [V2(0.78, 0.00), V2(0.52, 0.22), V2(0.34, 0.52), V2(0.30, 0.68),
            V2(0.86, 0.76), V2(0.92, 0.86), V2(0.40, 0.94), V2(0.00, 1.00)],
  // precipitated chimney: bulbous, layered, vent at the top
  chimney: [V2(0.86, 0.00), V2(0.96, 0.14), V2(0.62, 0.34), V2(0.74, 0.46),
            V2(0.50, 0.70), V2(0.58, 0.84), V2(0.34, 0.96), V2(0.30, 1.00)],
  // wave-cut sea stack: near-vertical with an undercut notch
  stack:   [V2(0.94, 0.00), V2(0.70, 0.10), V2(0.88, 0.20), V2(0.84, 0.60),
            V2(0.90, 0.78), V2(0.66, 0.94), V2(0.40, 1.00)],
};

/* Every family needs aPart, or the attribute reads as (0,0) on the ones that
   do not define it and every rock in the game turns bark-coloured and dark.
   The fallback IS the old guess, so a family that says nothing renders exactly
   as it did before this existed. */
function ensurePart(g) {
  if (g.attributes.aPart) return g;
  const pos = g.attributes.position, n = pos.count, a = new Float32Array(n * 2);
  for (let i = 0; i < n; i++) {
    const t = Math.min(1, Math.max(0, (pos.getY(i) - 0.16) / 0.36));
    a[i * 2] = t * t * (3 - 2 * t);
    a[i * 2 + 1] = 1;
  }
  g.setAttribute('aPart', new T.Float32BufferAttribute(a, 2));
  return g;
}

export function geometryFor(kind) {
  if (GEOM[kind]) return GEOM[kind];
  /* A ROSTER ENTRY MAY NAME A GENOME instead of a profile. 68 hex characters
     grown by src/render/treeGeom.js — the same grammar TREES.html breeds on, so
     the tree the owner picked is the tree that stands on the planet. Returned
     BEFORE computeVertexNormals(), which would flatten the authored normals. */
  if (isGenome(kind)) {
    const gg = growFromCode(kind);
    if (gg) return (GEOM[kind] = ensurePart(gg));
  }
  let g;
  if (PROFILES[kind]) {
    // 10, not 7. These are INSTANCED, so the segment count is paid once per
    // family for the whole world — and at 7 the silhouette of a near tree is
    // visibly a heptagon, which is the wrong kind of low-poly: stylised wants
    // clean shapes, not crude ones.
    g = new T.LatheGeometry(PROFILES[kind], 10);
  } else if (kind === 'boulder') {
    /* NOT A BARE ICOSAHEDRON. A 20-sided regular solid is instantly readable as
       a DIE, and once there are a few hundred of them on a hillside that is the
       only thing anyone sees — the owner's word for the first version was
       "disgusting", and he was looking at a field of red d20s.

       One subdivision plus a deterministic per-vertex displacement gives an
       irregular, squat, broken shape for 80 triangles. The displacement is
       hashed from the vertex POSITION rather than its index, which is what
       keeps the mesh watertight: PolyhedronGeometry is non-indexed, so a shared
       corner appears three times, and hashing the index would pull those copies
       apart and open cracks in every rock. */
    g = new T.IcosahedronGeometry(0.5, 1);
    const p = g.attributes.position;
    let minY = Infinity;
    for (let i = 0; i < p.count; i++) {
      const x = p.getX(i), y = p.getY(i), z = p.getZ(i);
      const h = Math.sin(x * 127.1 + y * 311.7 + z * 74.7) * 43758.5453;
      const s = 0.62 + 0.66 * (h - Math.floor(h));
      // squashed on Y: a rock that has come to rest is wider than it is tall
      const ny = y * s * 0.72;
      p.setXYZ(i, x * s, ny, z * s);
      if (ny < minY) minY = ny;
    }
    // sit it exactly ON the ground — the displacement moved the lowest point,
    // so a fixed translate would bury some rocks and float others.
    g.translate(0, -minY, 0);
  } else if (kind === 'column') {
    // basalt: hexagonal, near-parallel sides, flat top
    g = new T.CylinderGeometry(0.88, 1.0, 1, 6, 1);
    g.translate(0, 0.5, 0);
  } else if (kind === 'shard') {
    // driven-in crust fragment: four-sided, sharp, no taper at the base
    g = new T.CylinderGeometry(0.06, 0.95, 1, 4, 1);
    g.translate(0, 0.5, 0);
  } else {
    g = new T.CylinderGeometry(0.05, 1.0, 1, 6, 1);
    g.translate(0, 0.5, 0);
  }
  g.computeVertexNormals();
  GEOM[kind] = ensurePart(g);
  return GEOM[kind];
}

/**
 * @param uniforms THE WORLD'S uniform object — passed, never copied.
 */
export function makeObjectMaterial(uniforms) {
  const u = {
    uSun: uniforms.uSun, uSunCol: uniforms.uSunCol,
    uAmbient: uniforms.uAmbient, uSkyCol: uniforms.uSkyCol,
    uSkyAmt: uniforms.uSkyAmt,
  };
  const m = new T.ShaderMaterial({
    vertexShader: VERT, fragmentShader: FRAG,
    glslVersion: T.GLSL3, uniforms: u,
    /* DoubleSide because the bred foliage includes single-layer ribbons and
       membranes; the fragment shader flips the normal on a back face so they
       are still lit correctly. Closed families never show a back face, so this
       costs them nothing. */
    side: T.DoubleSide,
  });
  m.toneMapped = false;
  return m;
}
