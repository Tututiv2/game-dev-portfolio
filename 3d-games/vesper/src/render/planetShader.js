/* ============================================================================
   PLANET SHADER — the orbital representation.

   ONE sphere, ONE draw call. This is the thing that makes a world look like the
   reference images.

   HOW IT IS ORGANISED, and why it is organised that way
   -----------------------------------------------------
   The whole file is three GLSL strings that get assembled into three different
   shaders, so that the LOOK is written down exactly once:

     SURFACE_SRC   surfaceAt(n, eps) -> colour, emissive, normal, AO, height.
                   A pure function of a direction. Knows nothing about the sun,
                   the camera, or the screen. This is "what is at this point on
                   the world", and it is EXPENSIVE — about 20,000 operations,
                   because it evaluates the height field seven times over.

     LIGHT_SRC     shadeSurf(n, surf, shade) -> the final pixel. Sun, terminator,
                   emissive day/night, the white-hot boost, the atmospheric haze.
                   CHEAP, and it is the only part that changes when the star
                   moves or the world turns.

     the two ways of getting a Surf:
       ANALYTIC — call surfaceAt() per pixel, per frame. Correct at any zoom,
                  and it costs what it costs. This is what the whole renderer
                  used to be, and it is still the reference: ?analytic=1.
       BAKED    — surfaceAt() was evaluated ONCE into cubemaps when the world
                  was built (see planetBake.js), so drawing is three texture
                  fetches. This is the shipping path.

   The split is what makes the bake safe: there is no second copy of the art to
   drift out of step, and ?analytic=1 renders the same world through the old
   path so the two can be put side by side.

   THE SEVEN LAWS, and where each one lives in this file:
     1. dark base makes the glow ......... uLo/uMid near-black; uAmbient low;
                                           and NOTHING but emissive may exceed
                                           the bloom threshold (see view.js)
     2. emissive branching networks ...... crackB(), the warped F2-F1 network
     3. huge circular macro-features ..... featureField(), uFeatPos/uFeatParam
     4. fractal boundaries everywhere .... warp2() feeding the continent fBm
     5. hard saturated atmospheric rim ... atmosphere.js (separate shell)
     6. two colour families per world .... uEmis* (hot) vs uAccent (cool),
                                           masked into DIFFERENT regions
     7. space is never plain black ....... nebula.js (separate backdrop)

   EXPOSURE IS THE WHOLE BALL GAME. The first version of this shader looked
   like a pink smear because lit rock sat above the bloom threshold, so the
   entire disc bloomed and ACES pushed the blown reds to magenta. Rule: lit
   surface albedo stays under ~0.9 linear, emissive goes to 2-12 linear, and
   the bloom threshold sits between them.
   ========================================================================= */

import * as T from 'three';
import { NOISE, GLSL_HELPERS } from './glsl/noise.glsl.js';
import { toLocalDir } from './frameUtil.js';
// The sky's palette, so the light it casts on the ground is the colour of the
// thing actually drawn up there. nebula.js imports nothing from here, so this
// is a leaf dependency and not a cycle.
import { NEBULA_SETS } from './nebula.js';

export const NFEAT = 12;                 // placed macro-features per world

/* Declared once and included by every shader here, because GLSL will not let
   the same uniform be declared twice and the three shaders overlap. */
const COMMON_SRC = /* glsl */`
uniform float uRelief, uRadius;
uniform float uEps;          // angular size of ONE SCREEN PIXEL, in radians
uniform float uShadowAmt;
uniform float uIsGas;
`;

/* ---------------------------------------------------------------------------
   The shared height field. Written ONCE, used by:
     - the vertex shader (silhouette displacement)
     - the fragment shader (colour ramps + the analytic normal)
     - the CPU mirror in world/heightField.js, which answers "where is the
       ground" for meshes and collision
   Binding rule 3 says there is exactly one of these. Changing it changes every
   existing world, so it is a world-version event.
   ------------------------------------------------------------------------ */
// Exported so tests/conformance.mjs can render THIS EXACT SOURCE as data and
// compare it against the CPU mirror. A re-typed copy in the test would defeat
// the entire point of the test.
export const HEIGHT_FIELD_SRC = /* glsl */`
uniform float uContFreq, uWarpAmt, uMtnFreq, uMtnSharp, uMtnAmt, uMtnLo;
uniform float uCrackFreq, uCrackDepth, uCrackWidth, uCrackWarp;
uniform float uTerrace, uPlateSmooth;
// A vec3 offset, NOT a scalar multiplier. snoise wraps at +-289, so seeding by
// multiplying a big scalar into high-frequency coordinates would push some
// worlds outside the permutation table and make them visibly tile.
uniform vec3  uSeedOff;
uniform vec3  uVortexAxis;
uniform vec2  uVortexP;               // x = twist strength, y = falloff
uniform vec3  uFeatPos[${NFEAT}];
uniform vec4  uFeatParam[${NFEAT}];   // x=1-cos(radius)  y=kind  z=depth  w=glow

// Placed circular macro-features: calderas, impact basins, luminous rings.
// Noise alone never produces a clean circle, and a world with no circles has
// no sense of scale. Positions are hashed from the planet seed on the CPU.
//   kind 0 = impact basin   1 = caldera   2 = luminous ring   3 = vortex (sky only)
float featureField(vec3 n, out float actBoost, out float glowBoost){
  float h = 0.0; actBoost = 0.0; glowBoost = 0.0;
  for(int i=0;i<${NFEAT};i++){
    vec4 pr = uFeatParam[i];
    if(pr.x <= 0.0) continue;
    float kind = pr.y;
    if(kind > 2.5) continue;                       // vortices are cloud-only
    float c = 1.0 - dot(n, uFeatPos[i]);           // 0 at centre, grows outward
    float t = c / pr.x;                            // 1 at the rim
    if(t > 2.4) continue;
    // Perturb the radius so the boundary is ragged. A mathematically perfect
    // circle with a soft edge reads as a decal stuck on the planet; the calderas
    // in the reference have torn, irregular outlines. Impact basins keep more of
    // their circle (they really are round) than calderas do.
    t *= 1.0 + fbmB(n*7.0 + uSeedOff + vec3(3.0, 61.0, 23.0), 7.0, 3)
               * (kind < 0.5 ? 0.14 : 0.40);

    if(kind < 0.5){                                // impact basin
      h += -pr.z * (1.0 - smoothstep(0.0, 1.05, t))
           + pr.z * 0.62 * exp(-pow((t-1.0)*3.0, 2.0));
      glowBoost += pr.w * 0.10 * (1.0 - smoothstep(0.0, 0.8, t));
    } else if(kind < 1.5){                         // caldera — the money feature
      float floorD = 1.0 - smoothstep(0.50, 1.0, t);
      h += -pr.z * floorD + pr.z * 0.5 * exp(-pow((t-1.0)*4.0, 2.0));
      // A caldera works by making the CRACK NETWORK dense and bright inside it,
      // not by being a glowing disc. The first pass emitted a smooth disc here
      // and bloom turned every caldera into a featureless white ball stuck to
      // the planet. Almost all of the effect goes through actBoost.
      actBoost  = max(actBoost,  pr.w * floorD * 0.90);
      glowBoost = max(glowBoost, pr.w * floorD * 0.08);
    } else {                                       // luminous ring
      // rings stay smooth on purpose — a clean glowing circle is the point
      float ring = exp(-pow((t-1.0)*5.0, 2.0));
      h += pr.z * 0.30 * ring;
      glowBoost += pr.w * ring * 0.85;
    }
  }
  return h;
}

// The height field itself. Returns roughly -1..1; multiply by uRelief for metres.
float heightAt(vec3 n0, out float cont, out float mtn, out float plate){
  // ONE giant spiral: the ice vortex on the glacier reference, the storm on a
  // gas giant. Applied to the sample point, so the terrain itself is sheared
  // into the spiral rather than having a spiral drawn on top of it.
  vec3 n = uVortexP.x > 0.0
    ? spiralWarp(n0, uVortexAxis, uVortexP.x, uVortexP.y)
    : n0;

  vec3 q = n*uContFreq + uSeedOff;

  // Plates first: the crack mask gates how much fine relief the interiors get.
  plate = crackB(n*uCrackFreq + uSeedOff + vec3(11.0, 3.0, 7.0),
                 uCrackFreq, uCrackWidth, uCrackWarp);

  // ONE DOMINANT SHAPE, then quieter detail under it.
  //
  // Two findings, both measured rather than guessed:
  //   - Ablation (tests/ablate.mjs --sweep=uWarpAmt) showed domain warping is
  //     what covered every world in fingerprint contour loops. Warping
  //     compresses the domain locally, so a warped octave carries several times
  //     its nominal frequency and the fine ones shear into closed curves.
  //   - Everything sat at one visual scale, so worlds read as noodles rather
  //     than as continents. The references have ONE huge shape per hemisphere
  //     with everything else clearly subordinate.
  //
  // So: warp hard, but at 45% of the nominal frequency, giving enormous fractal
  // landmasses; then add unwarped medium relief at only 30% weight. The medium
  // term also honours uPlateSmooth, which is what leaves an ice sheet's
  // interior genuinely flat with all of its relief out at the seams.
  vec3 wq = warp2(n*uContFreq*0.45 + uSeedOff, uWarpAmt);
  float big = fbmB(wq, uContFreq*0.45*(1.0 + uWarpAmt*1.5), 2);
  float med = fbmB(q*1.6 + vec3(17.0, 5.0, 29.0), uContFreq*1.6, 2);
  cont = big + med*0.30*mix(uPlateSmooth, 1.0, plate);

  // Ridged mountains, masked onto high ground so basins stay open. sharp>1
  // makes knife edges — these worlds have no rain, so nothing rounds them off.
  // THREE octaves, not four: the fourth sits around 5 px from orbit and a
  // 4-octave ridged multifractal at that scale reads as a maze, not as rock.
  // uPlateSmooth keeps the INSIDE of a plate flat and puts the relief on its
  // boundary: that is what an ice sheet looks like, and it is what makes the
  // plate structure legible instead of buried under noise everywhere.
  float mmask = sat((cont - uMtnLo)*2.6) * mix(uPlateSmooth, 1.0, plate);
  mtn = ridgedB(n*uMtnFreq + uSeedOff + vec3(31.0, 7.0, 19.0), uMtnFreq, uMtnSharp, 3);

  // Ridged noise is strictly positive, so adding it raw lifts the whole world
  // and floods every basin — which is why the first pass had no coastlines at
  // all. Re-centring lets ranges rise AND lets the ground between them fall.
  float h = cont*0.80 + (mtn - 0.34)*mmask*uMtnAmt;
  h -= plate*uCrackDepth;

  float ab, gb;
  h += featureField(n, ab, gb);

  // Stepped/terraced worlds (crystal, shattered) — quantise the elevation.
  // A SOFTENED riser was tried here and reverted; see world/heightField.js for
  // the two measurements that killed it. Keep this line identical to the CPU
  // mirror — conformance.mjs checks the two against each other.
  if(uTerrace > 0.0) h = floor(h/uTerrace)*uTerrace + uTerrace*0.5;
  return h;
}
float heightAt(vec3 n){ float a,b,c; return heightAt(n,a,b,c); }
`;

/* ---------------------------------------------------------------------------
   THE SURFACE — everything about a point on the world that does not depend on
   where the sun or the camera is. Split out of main() so it can be evaluated
   per pixel (analytic) or once into a texture (baked) from ONE piece of source.

   `eps` is the angular footprint of whatever is sampling it: one screen pixel
   when drawing directly, one texel when baking. It does two jobs — it is the
   finite-difference step for the normal, and it band-limits every noise octave
   (see noise.glsl.js) — so those two can never disagree.
   ------------------------------------------------------------------------ */
export const SURF_STRUCT_SRC = /* glsl */`
struct Surf {
  vec3  col;    // albedo, linear, authored under ~0.9 or the whole disc blooms
  vec3  emis;   // linear, 2-12 for the hot stuff. NOT yet day/night weighted.
  vec3  nrm;    // object space, unit
  float ao;     // 0.66 .. 1.08
  float h;      // the height field, normalised. Multiply by uRelief for metres.
};
`;

export const SURFACE_SRC = /* glsl */`
// --- palette ---------------------------------------------------------------
uniform vec3  uLo, uMid, uHi, uPeak;
uniform vec3  uSeaDeep, uSeaMid, uShelf, uSeaGlowCol;
uniform float uHasSea, uSeaLevel, uSeaDepthK, uShelfW, uSeaGlow, uWaterCrack;
uniform vec3  uEmisA, uEmisB, uEmisC;      // crack gradient: rim -> mid -> core
uniform vec3  uAccent;                     // the SECOND colour family
uniform float uAccentAmt, uAccentFreq;
uniform vec3  uCapCol;
uniform float uCapAmt, uCapLat;
uniform float uReliefBoost, uAOAmt;

// --- pattern ---------------------------------------------------------------
uniform float uCrackFreq2, uCrackWidth2, uEmisPow, uEmisAmt, uCrackHi;
uniform float uPlateGlowLo, uPlateRock, uFineGlow;
uniform float uActFreq, uActLo, uActHi;
uniform float uGrainFreq, uGrainAmt;
uniform float uBandFreq, uBandWarp;
uniform float uTime;

/* ---- gas giants take a completely different path: bands, no terrain ------ */
vec3 gasSurface(vec3 n, out vec3 emis){
  vec3 w = warp1(n*uBandFreq*0.35 + uSeedOff, uBandWarp);
  float band = sin(n.y*uBandFreq + fbmB(w, uBandFreq, 4)*2.4);
  float turb = fbmB(n*uBandFreq*0.9 + vec3(0.0, uTime*0.004, 0.0) + uSeedOff + 3.0,
                    uBandFreq, 5);
  band = band*0.72 + turb*0.5;
  float t = sat(band*0.5 + 0.5);
  vec3 c = mix(uLo, uMid, smoothstep(0.15, 0.55, t));
  c = mix(c, uHi,  smoothstep(0.5, 0.82, t));
  c = mix(c, uPeak, smoothstep(0.84, 1.0, t));
  // Great spots are FILLED ovals, computed here rather than borrowed from
  // featureField's glowBoost — that returns a thin ring, and a gas giant with
  // a magenta outline drawn on it looks like someone circled it in marker.
  float storm = 0.0;
  for(int i=0;i<${NFEAT};i++){
    vec4 pr = uFeatParam[i];
    if(pr.x <= 0.0) continue;
    float cc = 1.0 - dot(n, uFeatPos[i]);
    float t = cc/pr.x;
    if(t > 1.4) continue;
    // Stretched along latitude, because a storm in a banded flow gets sheared.
    // pr.x is 1-cos(radius), so the ANGULAR radius is sqrt(2*pr.x); comparing a
    // linear y-difference against pr.x directly mixed units and collapsed the
    // whole spot into a one-pixel line.
    float ar = sqrt(2.0*pr.x);
    float sh = 1.0 - abs(n.y - uFeatPos[i].y)/max(ar*1.1, 0.02);
    storm = max(storm, (1.0 - smoothstep(0.45, 1.20, t))*pr.w*sat(sh));
  }
  c = mix(c, uAccent, sat(storm*1.25)*0.85);
  emis = vec3(0.0);
  return c;
}

void surfaceAt(vec3 n, float eps, out Surf s){
  // Band-limit every octave to the sampler's footprint. This is the one line
  // that lets the same source be a per-pixel shader and a texture bake.
  gEps = eps;

  s.nrm = n; s.ao = 1.0; s.h = 0.0; s.emis = vec3(0.0);

  if(uIsGas > 0.5){ s.col = gasSurface(n, s.emis); return; }

  vec3 col; vec3 emis = vec3(0.0);

  /* ---- height + analytic normal (3 taps) -------------------------------- */
  vec3 up = abs(n.y) < 0.985 ? vec3(0.0,1.0,0.0) : vec3(1.0,0.0,0.0);
  vec3 tx = normalize(cross(up, n));
  vec3 ty = cross(n, tx);
  float cont, mtn, plate;
  float h0 = heightAt(n, cont, mtn, plate);
  float hx = heightAt(normalize(n + tx*eps));
  float hy = heightAt(normalize(n + ty*eps));

  // Height units -> radians of slope, at THIS sample footprint. Used to be a
  // uniform (uSlopeK); it is derived here instead so that it cannot fall out of
  // step with the eps the finite difference above actually used.
  float slopeK = uRelief/(uRadius*eps);

  // uReliefBoost exaggerates the slope beyond what the elevation implies.
  // Art direction beats plausibility: at true scale a 10 km peak on a 600 km
  // world tilts the normal by about 12 degrees and reads as a gentle bump.
  vec3 nrm = normalize(n - (tx*(hx-h0) + ty*(hy-h0))*slopeK*uReliefBoost);
  float slope = sat((1.0 - dot(nrm, n))*3.0);

  // A two-tap Laplacian, free from the samples we already have. Positive is
  // concave (valley floors, crack bottoms) and negative is convex (ridges).
  // This costs nothing and does most of the work of an occlusion pass.
  // Ceiling of 1.08, not 1.4: this multiplies straight into the lit colour,
  // and anything that pushes an ordinary surface past the bloom threshold
  // makes the whole disc glow.
  //
  // The FLOOR matters just as much. A Laplacian is a high-pass filter, so its
  // zero crossings trace the iso-contours of the height field — left with a
  // wide range it draws every one of them as a dark line and the planet ends
  // up looking like a fingerprint. Clamped tight it only deepens real hollows.
  float lap = (hx + hy - 2.0*h0)*slopeK;
  float ao = clamp(1.0 - lap*uAOAmt, 0.66, 1.08);

  /* ---- activity: WHERE the world is alive ------------------------------- */
  // Uniform cracks over a whole globe read as texture. Cracks concentrated
  // into blazing regions with dead-black rock between them read as fire.
  // molten texture: keeps caldera floors from reading as flat glowing discs
  float mol = fbmB(n*uCrackFreq2*0.6 + uSeedOff + vec3(91.0, 13.0, 5.0),
                   uCrackFreq2*0.6, 3)*0.5 + 0.5;

  float ab, gb; featureField(n, ab, gb);
  float act = sat(smoothstep(uActLo, uActHi,
                  fbmB(n*uActFreq + uSeedOff + vec3(23.0, 41.0, 5.0), uActFreq, 3)
                  *0.5 + 0.5));
  // A caldera's activity boost is textured, not flat, or the whole floor
  // lights to the same value and reads as a pink disc rather than a lava lake.
  act = max(act, ab*(0.55 + 0.55*mol));

  /* ---- the crack network (LAW 2 — the signature element) ---------------- */
  float c2 = crackB(n*uCrackFreq2 + uSeedOff + vec3(3.0, 29.0, 13.0),
                    uCrackFreq2, uCrackWidth2, uCrackWarp*0.7);
  // The wide plate mask carves the CANYON; only its deepest part GLOWS.
  // Using the full mask for both lit the whole canyon floor and lost the
  // reference look entirely — a dark rock canyon with a thin bright seam.
  // uFineGlow decides whether the FINE network is a co-star or a texture.
  // On a lava world it is the signature element and runs at full strength; on
  // an ice world it lit every square kilometre and buried the plate structure
  // the archetype is actually about.
  float plateGlow = smoothstep(uPlateGlowLo, 1.0, plate);
  float cr = max(plateGlow*0.95, c2*uFineGlow) * act;

  /* ---- base colour ------------------------------------------------------ */
  float e = sat((h0 - uSeaLevel)/max(1.0 - uSeaLevel, 0.15));
  bool water = (uHasSea > 0.5) && (h0 < uSeaLevel);
  if(water){
    float depth = sat((uSeaLevel - h0)*uSeaDepthK);
    col = mix(uSeaMid, uSeaDeep, smoothstep(0.0, 1.0, pow(depth, 0.5)));
    // The hard bright shelf ring around every landmass. This is what makes an
    // archipelago read as an archipelago instead of green blobs on blue.
    col = mix(col, uShelf, (1.0 - smoothstep(0.0, uShelfW, depth))*0.95);
    nrm = normalize(mix(n, nrm, 0.22));      // water is flat
    ao = mix(1.0, ao, 0.25);
    // uWaterCrack: an ocean drowns its cracks, a frozen sea does not.
    cr *= mix(uWaterCrack, 1.0, 1.0 - smoothstep(0.0, 0.30, depth));
    emis += uSeaGlowCol * uSeaGlow *
            (0.25 + 0.75*smoothstep(0.15, 0.85, depth)) *
            (0.55 + 0.45*fbmB(n*11.0 + uSeedOff + vec3(71.0,5.0,23.0), 11.0, 3));
  } else {
    col = mix(uLo, uMid, smoothstep(0.02, 0.42, e));
    col = mix(col, uHi,  smoothstep(0.34, 0.74, e));
    col = mix(col, uPeak, smoothstep(0.70, 0.96, e));
    // Steep ground shows bare rock — this is what draws the mountain ranges.
    // Thresholded, not linear: a linear slope term darkens EVERY gradient in
    // the height field, which traces a dark line along every ridge and gully
    // and contributes to the fingerprint look. Only genuinely steep faces.
    col = mix(col, uHi*0.65, smoothstep(0.30, 0.85, slope)*0.55);
    // Bare rock along the plate SEAMS, painted from the crack mask rather
    // than from elevation. The glacier reference is pale ice plates bounded
    // by dark broken rock, and elevation alone never produces that because
    // the seam is the LOW ground, not the high ground.
    col = mix(col, uHi, plate*uPlateRock);
    float lat = abs(n.y);
    float cap = smoothstep(uCapLat - 0.16, uCapLat + 0.10, lat + e*0.13
                           + fbmB(n*7.0 + uSeedOff + 47.0, 7.0, 3)*0.10);
    col = mix(col, uCapCol, cap*uCapAmt);
    // Cracks are in the CRUST. Keeping them off the peaks is what leaves the
    // black spires clean against the fire, exactly as the lava reference does.
    cr *= mix(1.0, 1.0 - sat((e - 0.45)*2.2), uCrackHi);
    // A CALDERA IS A LAVA LAKE, not a bright patch.
    // Making it a region of denser cracks still averaged out to a pale disc.
    // What the reference actually shows is the inverse of the surrounding
    // terrain: mostly molten, with dark solidified crust plates floating in
    // it. So inside a caldera the figure and ground swap over — the rock
    // becomes the islands and the fire becomes the sea.
    if(ab > 0.01){
      // Crust RAFTS with molten seams running between them, not a blobby
      // mask: the fine crack network carves the rafts apart, which is what
      // gives a lava lake its jigsaw look instead of polka dots.
      float crust = smoothstep(0.30, 0.66, mol) * (1.0 - c2*0.85);
      col = mix(col*0.20, col*0.95, crust);
      // Orange is the body of the lava; the pale gold is only the hottest
      // seams. Weighted the other way it came out cream and read as ash.
      emis += mix(uEmisB, uEmisC, sat((1.0 - crust)*1.5 - 0.45))
              * (1.0 - crust) * uEmisAmt * ab * 0.50;
      cr *= 0.30 + crust*0.70;
    }
  }

  /* ---- LAW 6: the second colour family, in its OWN regions --------------- */
  // Masked by a different, lower-frequency field than the glow, so the two
  // hues occupy different parts of the globe instead of muddying together.
  float acc = smoothstep(0.30, 0.72,
                fbmB(n*uAccentFreq + uSeedOff + vec3(53.0,17.0,37.0), uAccentFreq, 3)
                *0.5 + 0.5);
  emis += uAccent * acc * cr * uAccentAmt;

  /* ---- LAW 2 continued: the glow itself ---------------------------------- */
  vec3 glow = mix(uEmisA, uEmisB, sat(cr*1.7));
  glow = mix(glow, uEmisC, sat((cr - 0.62)*3.2));
  emis += glow * pow(cr, uEmisPow) * uEmisAmt * (1.0 - acc*0.55);

  /* ---- feature floors glow (caldera lakes, luminous rings) --------------- */
  emis += mix(uEmisB, uEmisC, 0.45) * gb * uEmisAmt * (0.22 + 0.80*mol);

  /* ---- grain: breaks up any remaining flatness --------------------------- */
  col *= 1.0 + fbmB(n*uGrainFreq + uSeedOff + vec3(61.0,43.0,29.0), uGrainFreq, 3)
               *uGrainAmt;

  s.col = col; s.emis = emis; s.nrm = nrm; s.ao = ao; s.h = h0;
}
`;

/* ---------------------------------------------------------------------------
   THE LIGHTING — everything that changes when the star moves or the world
   turns, and therefore everything that can never be baked. It is also, on
   purpose, cheap: this is what runs per pixel per frame in the shipping path.
   ------------------------------------------------------------------------ */
const LIGHT_SRC = /* glsl */`
uniform vec3  uSun;          // direction TO the star, OBJECT space
uniform vec3  uSunCol;
uniform vec3  uCamObj;       // camera position in OBJECT space
uniform vec3  uAtmo;
uniform float uAtmoAmt, uAmbient, uEmisDay;
uniform vec3  uSkyCol;       // what the sky actually is, over this world
uniform float uSkyAmt;
uniform float uSkyNight;    // sky weight ADDED on the night side, ON THE GROUND
uniform float uSkyNightOrbit; // ...and from orbit, where the approved gallery lives
uniform float uEmisNear;     // emissive multiplier when the camera is ON the ground
uniform float uCamAlt;       // camera height above the ground, METRES (CPU-supplied)

vec3 shadeSurf(vec3 n, Surf s, float shade){
  vec3 viewDir = normalize(uCamObj - n*uRadius);   // both in OBJECT space

  /* ---- lighting. Ambient stays LOW: the sun is what models the terrain. -- */
  float ndl  = dot(s.nrm, uSun);
  float lit  = sat(ndl);
  float wrap = sat((ndl + 0.20)/1.20);        // soft terminator, thin air
  vec3 outc = s.col * (lit*0.92*shade + wrap*0.14) * s.ao * uSunCol
            + s.col * uAmbient;

  float geoNdl = dot(n, uSun);
  float night = 1.0 - sat(geoNdl*2.4);

  /* ---- SKY LIGHT — the second of the reference's three light sources -----
     refs/SURFACE.md, first section: on the night side everything you can see is
     lit by the ground's own glow, THE NEBULA, and a scatter along the horizon —
     "and none of them is a star". The renderer had only the first. So a world
     whose rock does not glow had nothing lighting it at all, and stood on at
     night it came back as a black rectangle: ashen measured 56 distinct colours
     in a 1280x720 frame, with terrain underneath that was perfectly correct and
     completely invisible.

     Hemispheric, not a constant. dot(s.nrm, n) is the surface normal against
     the local UP, so ground facing the sky catches it and ground facing a cliff
     does not — which means this term MODELS THE TERRAIN at night, where before
     the flat uAmbient could only raise it uniformly and wash it out. That is
     the whole difference between "visible" and "shaped".

     Weighted toward night. Physically the sky does not switch off in daylight,
     but the sun is three orders of magnitude brighter when it is up, and the
     orbital gallery is an approved artefact — so the day side keeps a third of
     it and nothing the owner has already signed off moves much.

     uSkyCol is the nebula's own core colour for this world, taken from the same
     table nebula.js draws with, so the light on the ground is the colour of the
     thing in the sky rather than a second art decision that can drift from it. */
  float skyF = 0.5 + 0.5*dot(s.nrm, n);
  /* THE NIGHT WEIGHT IS A UNIFORM BECAUSE IT WAS A LITERAL, AND A LITERAL
     CANNOT BE SWEPT. Measured across all thirteen worlds (tests/daynight.mjs),
     the star was worth 3.0-9.1x on the ground with this term OFF and 0.53-1.23x
     with it ON — i.e. the sun was not weak, it was DROWNED, and five worlds were
     BRIGHTER AT NIGHT than in daylight. The comment above states the opposite
     intent in as many words. The arithmetic is not subtle: the star's entire
     contribution is capped at s.col*0.92, while this term ran s.col*3.40 at
     night and s.col*1.19 in daylight.

     Splitting day from night matters more than the value does. The DAY weight
     stays exactly 0.35*uSkyAmt, so nothing on the lit side moves at all.

     AND IT IS RAMPED BY ALTITUDE, for the same reason uEmisNear below is.
     "The day side is untouched, so the gallery is safe" is NOT good enough on
     its own: the night ramp is 1.0 - sat(geoNdl*2.4), which stays non-zero out
     to a sun elevation of 24.6 degrees, and on a sphere seen from outside that
     band is most of the limb. The owner's "they look... AMAZING HOLY COW" is
     partly about those pixels, so they are not mine to change on the way past.

     So orbit keeps the value that shipped and the ground gets the swept one.
     Same shape of fix, in the same function, as the emissive immediately below:
     a quantity authored for one viewing distance being asked to work at another.
     Not a discontinuity — the ramp is the same uCamAlt/(uRadius*0.03), 0 at your
     feet and 1 long before any gallery distance, so it crossfades across the
     descent. Verified by tests/uniform_ab.mjs: sweeping uSkyNight 0.65 -> 0.22
     from orbit moves ONE pixel in 490,000, by 1/255, while on the ground the
     same move takes glacier's day/night from 1.13 to 1.59.

     DO NOT MEASURE THIS WITH ablate.mjs. It rebuilds and re-bakes the world
     between variants and captures after three frames, so consecutive renders of
     the SAME value differ in 92% of pixels — an instrument noisier than the
     signal, which reported a confident 67% for a change that moves one pixel. */
  float camNear = sat(uCamAlt/(uRadius*0.03));
  outc += s.col * uSkyCol *
    (uSkyAmt * (0.35 + mix(uSkyNight, uSkyNightOrbit, camNear)*night) * skyF * s.ao);

  /* EMISSIVE IS AUTHORED FOR ORBIT AND HAS TO BE PULLED DOWN ON THE GROUND.
     From space a caldera is a few pixels against a dark disc, so emisAmt runs
     2-12 and the bloom does the rest. Stand inside one and those few pixels are
     the whole frame: measured at true night on VESPER-1 volcanic, the ground
     came back as flat blown cream with the terrain invisible underneath it.

     Same shape of fix as the haze term below, and for the same reason — a
     quantity authored for one viewing distance being asked to work at another.
     The distance ramp is 0 at your feet and 1 well before the gallery distances,
     so the approved orbital look is untouched.

     NB the *night* multiplier stays: the point is not to make the ground dark,
     it is to keep the crack network under the tone mapper so its SHAPE reads.
     Judge this at night with tests/groundshot.mjs, which reports the sun's
     elevation above the local horizon — and read DECISIONS first, because the
     harness spent a whole session calling daylit frames "night".

     THE GATE IS THE CAMERA'S ALTITUDE, NOT A DISTANCE TO THE DATUM.
     The first version used length(uCamObj - n*uRadius), which is the distance to
     the point on the DATUM sphere in that direction. Standing in a caldera the
     floor is kilometres BELOW the datum, so that length exceeded the fade range
     at every pixel in frame, the term saturated off, and sweeping uEmisNear from
     1.0 to 0.06 changed the image by NOTHING — six identical readings. Altitude
     is what "am I standing on it" actually means, the CPU already computes it
     for the terrain LOD, so it is passed in rather than guessed at. */
  // camNear is declared above, at the sky term, which now needs it too.
  vec3 emis = s.emis * (uEmisDay + night*1.05) * mix(uEmisNear, 1.0, camNear);

  // HOT THINGS GO WHITE. A pure saturated red at 3x intensity has nowhere to go
  // in a tone mapper: ACES desaturates it sideways and the result is PINK, which
  // is why every lava world in this project came out looking like bubblegum.
  // Physically, something that bright is also radiating across the spectrum, so
  // adding white as intensity rises is both correct and the fix.
  float eLum = dot(emis, vec3(0.2126, 0.7152, 0.0722));
  emis += vec3(0.62, 0.66, 0.74) * sat(eLum - 1.15) * 0.55;

  outc += emis;

  /* ---- atmospheric inscatter -------------------------------------------- */
  // Uses the GEOMETRIC normal, not the perturbed one: haze belongs to the limb,
  // not to every mountain face. Kept weak — the rim shell does the loud part,
  // and an over-strong term here lays a film over the entire planet.
  float fres = 1.0 - sat(dot(n, viewDir));
  float hazeLit = 0.18 + sat(geoNdl)*0.95;

  /* AND IT HAS TO KNOW HOW FAR AWAY THE GROUND IS.
     fres is a fresnel against the surface normal, which is the right shape seen
     from SPACE — it peaks at the limb, where the sight line really does cross
     the most air. Stand on the planet and it is wrong in the worst way: every
     sight line is now nearly tangential, so fres is about 1 at EVERY pixel and
     the term stops being a limb and becomes a flat film over the whole frame.

     Measured, standing on VESPER-1 glacier: the surface's own albedo is ~0.012
     linear (the near-black rock the whole art direction rests on), while this
     term was adding a constant ~0.12 — ten times the picture, all of it the
     same colour. A 1280x720 ground shot came back with FOUR distinct colours in
     it, over a height field carrying five decades of measured detail and a
     correct per-vertex normal.

     So scale it by distance, which is what inscatter actually depends on:
     nothing at your feet, full strength by the horizon. At orbital range the
     distance is many times the radius, this saturates at 1, and the approved
     view is bit-for-bit what it was. */
  float dist = length(uCamObj - n*uRadius);
  outc = mix(outc, uAtmo*hazeLit,
             pow(fres, 4.5)*uAtmoAmt*0.50*sat(dist/(uRadius*0.03)));

  return max(outc, 0.0);
}
`;

/* ---------------------------------------------------------------------------
   THE GLOW MOVES.

   The surface is baked into a texture, so the emissive pattern is frozen — and
   a world whose fire never stirs reads as a photograph of a planet rather than
   a planet. The way out is that you do not have to animate the TEXTURE, you
   animate what the texture is worth.

   Everything here is a multiplier on the baked emissive, which is what makes it
   work: dead rock has emis ~= 0, so no amount of modulation lights it. The
   motion is therefore confined to the crack network by construction, and reads
   as light travelling THROUGH the veins rather than as an effect laid over the
   picture. It costs no noise, no extra texture and no re-bake.

   Three layers, deliberately at different rates so they never visibly repeat:

     1. SURGE — rings racing outward from every caldera. This is the one that
        sells it: the volcanoes are the pumps, and you can see the pressure
        leaving them. Scaled by each feature's own glow, so a lava world throbs
        and an ice world barely stirs, straight out of existing per-world data.
     2. BREATH — two slow plane waves across the whole globe at different axes
        and speeds, so the network brightens unevenly instead of in step.
     3. FLICKER — fast, small, and only where it is already bright.

   Kept centred on 1.0 and biased slightly to DIMMING, because the bloom
   threshold is load-bearing: a pulse that pushes ordinary lit rock over it
   makes the whole disc glow and ACES turns the blown reds magenta. Surging past
   1.0 is the point, but only for things already emissive.
   ------------------------------------------------------------------------ */
const PULSE_SRC = (declareFeat) => /* glsl */`
uniform float uPulse;        // 0 turns the whole thing off (?pulse=0)
uniform vec3  uFlowAxis;
// The baked path includes none of the surface source, so it has to declare
// these itself. The analytic path already has all three and GLSL will not
// tolerate a second declaration.
${declareFeat ? `uniform float uTime;
uniform vec3 uFeatPos[${NFEAT}];
uniform vec4 uFeatParam[${NFEAT}];` : ''}

float glowPulse(vec3 n){
  if(uPulse <= 0.0) return 1.0;
  float t = uTime;

  // 2 — BREATH. Two plane waves. The second uses a swizzled axis so the two
  // are not parallel; parallel waves just beat against each other in stripes.
  float p = 0.30*sin(dot(n, uFlowAxis)*2.7 - t*0.13)
          + 0.20*sin(dot(n, uFlowAxis.zxy)*4.6 + t*0.21);

  // 1 — SURGE. Rings out of the calderas.
  for(int i=0;i<${NFEAT};i++){
    vec4 pr = uFeatParam[i];
    if(pr.x <= 0.0) continue;
    if(pr.y < 0.5 || pr.y > 1.5) continue;          // calderas only
    // angular distance, cheaply: 1-cos(d) -> d for small d, and it degrades
    // gracefully into a global term on the far side instead of going wrong.
    float d = sqrt(max(1.0 - dot(n, uFeatPos[i]), 0.0)*2.0);
    // travels OUTWARD (minus t), decays with distance, and is strongest for
    // the features that are already the most active
    p += 0.85*pr.w*sin(d*18.0 - t*0.85)*exp(-d*2.6);
  }

  // 3 — FLICKER
  p += 0.10*sin(dot(n, uFlowAxis.yzx)*23.0 - t*2.3);

  // asymmetric: dips further than it spikes, so peaks stay under the bloom knee
  p = p < 0.0 ? p*1.35 : p;
  return 1.0 + clamp(p, -0.65, 0.55)*uPulse;
}
`;

/* ---------------------------------------------------------------------------
   SELF-SHADOWING. Four extra height samples marched toward the star along the
   local tangent plane; if anything along that line rises above the sun's
   elevation, this pixel is in shadow.

   This is the single biggest thing separating a shaded ball from a landscape.
   Diffuse lighting alone gives a mountain a bright face and a dark face, but a
   mountain that throws a shadow ACROSS the ground next to it is what makes the
   black spires in the lava reference read as objects standing on a world.

   Steps scale with eps, so the shadow is the same length on screen whether you
   are in orbit or halfway down. Skipped entirely on the night side, where the
   terminator already does the job.

   The body is shared: only HOW a height is fetched differs between the analytic
   path (evaluate the field — four more full evaluations, and the reason a
   fullscreen planet used to cost what it did) and the baked path (four texture
   fetches, which is the same picture for about a thousandth of the work).
   ------------------------------------------------------------------------ */
const SHADOW_SRC = (fetchH) => /* glsl */`
float sunShadow(vec3 n, float h0, float eps){
  float sunEl = dot(n, uSun);
  if(sunEl <= 0.02) return 1.0;
  vec3 sd = uSun - n*sunEl;
  float sl = length(sd);
  if(sl < 1e-4) return 1.0;
  sd /= sl;

  float k = uRelief/uRadius;                 // height units -> radians of slope
  float tanSun = sunEl/max(sl, 0.05);        // tangent of the sun's elevation
  float worst = 0.0;
  for(int i=0;i<4;i++){
    float d = eps*(3.0 + float(i)*float(i)*7.0 + float(i)*6.0);
    float hs = ${fetchH};
    worst = max(worst, (hs - h0)*k/d);
  }
  return 1.0 - smoothstep(0.0, 0.65, worst/tanSun - 1.0)*uShadowAmt;
}
`;

/* ---------------------------------------------------------------------------
   TWO VERTEX SHADERS, and the difference is not about speed.

   Both do the same job: displace the sphere a little so the limb has real
   relief and the terminator is jagged. The analytic one evaluates the height
   field; the baked one reads it out of the cubemap.

   The reason it matters is COMPILE TIME. Evaluating the height field means
   inlining the whole noise library, and ANGLE compiles that through HLSL — the
   first launch on a machine spends about half a minute in the shader compiler
   (tests/boottime.mjs), which is the single worst thing the owner experiences.
   Every copy of heightAt in a program costs. Reading the cubemap instead takes
   the SHIPPING draw program down to no noise at all, leaving only the bake
   program heavy, and the bake program is built once.

   It costs nothing in quality: at 320 segments a vertex is about 6 km apart on
   a 600 km world and a baked texel is about 1.2 km, so the map is already finer
   than the mesh it is displacing.
   ------------------------------------------------------------------------ */
const VERT_ANALYTIC = /* glsl */`
precision highp float;
uniform float uDisplace;
varying vec3  vN;

${NOISE}
${COMMON_SRC}
${HEIGHT_FIELD_SRC}

void main(){
  gEps = uEps;
  vN = normalize(position);
  // Modest real displacement: gives the limb genuine relief and a jagged
  // terminator. The drama is carried by the fragment shader; this is silhouette.
  float h = uDisplace > 0.0 ? heightAt(vN) : 0.0;
  vec3 p = vN * (uRadius + h*uRelief*uDisplace);
  gl_Position = projectionMatrix * modelViewMatrix * vec4(p, 1.0);
}
`;

/* GLSL3, and only because of one line. Sampling a texture in a VERTEX shader
   needs an explicit mip level (there are no derivatives up there). GLSL ES 1.00
   spells that `textureCubeLod`, which ANGLE does not expose in a WebGL2 ESSL1
   vertex shader — "no matching overloaded function found". GLSL ES 3.00 has
   `textureLod` in core for every stage, so the baked pair is compiled as 300 es
   and the analytic pair stays on 100. They are separate programs; nothing is
   shared between them but uniform declarations, which are version-agnostic.

   Note three does NOT alias gl_FragColor for a raw ShaderMaterial under GLSL3 —
   the output has to be declared. */
const VERT_BAKED = /* glsl */`
precision highp float;
uniform float uDisplace, uRelief, uRadius;
uniform samplerCube uTexCol;      // rgb = albedo, a = height
out vec3 vN;

void main(){
  vN = normalize(position);
  float h = uDisplace > 0.0 ? textureLod(uTexCol, vN, 0.0).a : 0.0;
  vec3 p = vN * (uRadius + h*uRelief*uDisplace);
  gl_Position = projectionMatrix * modelViewMatrix * vec4(p, 1.0);
}
`;

/* ---- path A: evaluate the surface per pixel, per frame -------------------- */
const FRAG_ANALYTIC = /* glsl */`
precision highp float;
varying vec3 vN;

${NOISE}
${COMMON_SRC}
${HEIGHT_FIELD_SRC}
${SURF_STRUCT_SRC}
${SURFACE_SRC}
${LIGHT_SRC}
${PULSE_SRC(false)}
${SHADOW_SRC('heightAt(normalize(n + sd*d))')}

void main(){
  vec3 n = normalize(vN);
  Surf s;
  surfaceAt(n, uEps, s);
  s.emis *= glowPulse(n);
  float shade = (uIsGas > 0.5 || uShadowAmt <= 0.0) ? 1.0 : sunShadow(n, s.h, uEps);
  gl_FragColor = vec4(shadeSurf(n, s, shade), 1.0);
}
`;

/* ---- path B: read the surface out of the bake — THE SHIPPING SHADER -------
   GLSL 300 es, to match VERT_BAKED. Note what is NOT in here: no simplex, no
   Worley, no height field, no domain warp. Three texture fetches, four more
   for the shadow, and the lighting. That is the whole point of the bake, and
   it is also why this program compiles in a blink where the analytic one takes
   the best part of half a minute.

   TWO VARIANTS, FROM ONE PIECE OF SOURCE — and the reason is the same reason
   terrainShader.js shares this file at all: the moment the sphere and the
   ground are shaded by two separate texts they drift apart, and the seam runs
   across the whole world.

   `meshNormal` is the only difference. The orbital sphere has no geometry to
   speak of, so its normal has to come out of the baked cubemap. The terrain
   IS geometry, and at ground level the baked normal is a constant — one texel
   is 1.2 km, so a 1 km view samples one of them and every pixel comes back
   identical (measured: a ground shot with exactly 1 distinct colour in it).
   The terrain variant therefore takes its normal from the mesh, which costs
   nothing, adds no noise to the program, and is the normal of the triangles
   the player is actually standing on. */
export const fragBaked = (meshNormal) => /* glsl */`
precision highp float;
in vec3 vN;
${meshNormal ? 'in vec3 vNrm;\nin float vSeam;' : ''}
${meshNormal ? 'in float vDist;\nin vec3 vPos;' : ''}
layout(location = 0) out vec4 outColor;

/* NOISE GOES IN THE TERRAIN PROGRAM AND NOWHERE ELSE, and that split is the
   whole cost control. The orbital sphere stays exactly as empty as it was when
   its frame rate and its 24 s first-compile were measured and approved — three
   cubemap reads and no noise at all. The GROUND needs the crack network live,
   because a baked texel is 1.2 km across and a ground view is 2 km, so down
   here the bake is a CONSTANT. Two programs, one piece of art.

   It sits HERE, in the helpers slot, and not lower down: NOISE already opens
   with GLSL_HELPERS (so including both is "'sat' : function already has a
   body"), and LIGHT_SRC below calls sat(), so a declaration placed after it is
   "no matching overloaded function". Both were link errors, and walk.mjs caught
   both because it renders the ground on a real driver. */
${meshNormal ? NOISE : GLSL_HELPERS}
${COMMON_SRC}
${SURF_STRUCT_SRC}
${LIGHT_SRC}

uniform samplerCube uTexCol;    // rgb = albedo,   a = height
uniform samplerCube uTexEmis;   // rgb = emissive, a = spare
uniform samplerCube uTexNrm;    // rgb = normal,   a = ambient occlusion
uniform float uTexEps;          // angular size of one BAKED TEXEL, radians
${meshNormal ? `
// Inputs to the ground-scale network. All but the last three already exist in
// the world's uniform set, and the terrain SHARES that object with the orbital
// sphere, so there is nothing here that can fall out of step.
uniform vec3  uSeedOff;
uniform float uCrackWidth2, uCrackWarp, uFineGlow;
uniform float uDetailFreq;      // cracks per radian at GROUND scale
uniform float uEmisDetail;      // 0 = exactly the old behaviour
uniform float uPixA;            // radians per screen pixel
uniform float uGndAmt;          // metre-scale albedo contrast; 0 = flat ground
uniform float uGndFreq;         // mottle cycles per radian (radius / gndScale)
uniform float uGndDust;         // how much the FLATS wash out toward fines
uniform float uGndBump;         // metre-scale relief in the SHADING NORMAL

/* EXACT RANGE REDUCTION, and the ground material does not work without it.
   snoise is periodic with period 867 per axis — detail.js verifies that on
   20,000 points, and records that the 289 an older comment claimed is not a
   period at all. So folding a coordinate back to +-433 changes nothing about
   the value and everything about the precision.

   It matters HERE and not for the crack network above because of magnitude. A
   12 m mottle on a 310 km world is about 26,000 cycles per radian, and by the
   third octave the coordinate is past 100,000, where a float32 ulp is a
   noticeable fraction of a noise cell — exactly wall 2 in detail.js, which
   arrives as visible stepping rather than as noise. The crack network runs two
   decades coarser and never gets near it. */
vec3 fold867(vec3 p){ return p - 867.0*floor(p*(1.0/867.0) + 0.5); }
` : ''}
${meshNormal ? 'uniform float uSeamGlow;\nuniform float uTerrDbg;\nuniform float uGndLift;' : ''}

${PULSE_SRC(true)}
${SHADOW_SRC('texture(uTexCol, normalize(n + sd*d)).a')}

void main(){
  vec3 n = normalize(vN);
  vec4 c = texture(uTexCol,  n);
  vec4 e = texture(uTexEmis, n);
  vec4 q = texture(uTexNrm,  n);

  Surf s;
  s.col = c.rgb; s.h = c.a;
  // The emissive is baked and therefore frozen. This is what unfreezes it, and
  // it is the ONLY place the shipping shader does anything time-varying.
  s.emis = e.rgb * glowPulse(n);
${meshNormal ? `
  /* THE LIGHT COMES OUT OF THE CRACKS, and only the mesh knows where they are.
     The baked emissive is one 1.2 km texel across a 2 km ground view, so at this
     range it is a CONSTANT — which is why a caldera read as flat gold at every
     exposure that was tried. uEmisNear fixed the blow-out and could not fix
     this, because the missing thing was never brightness, it was STRUCTURE.

     vSeam is the height field's concavity at this vertex, computed on the CPU
     from samples buildChunk already had (see terrain.js). Positive in hollows,
     crack bottoms and at the foot of a riser; zero on a plate top. Multiplying
     the baked emissive by it puts the glow in the seams and takes it off the
     pans — refs/SURFACE.md item 2, "the brightest light is at the base of each
     riser" — and it costs the shipping shader one multiply and no noise.

     uSeamGlow is how much of the emissive is moved into the seams rather than
     left spread flat; at 0 this is exactly the old behaviour. */
  s.emis *= mix(1.0, mix(0.14, 2.30, vSeam), uSeamGlow);

  /* THE CRACK NETWORK, CONTINUED DOWN TO GROUND SCALE.
     vSeam above says WHERE THE GROUND IS CONCAVE, which is a property of the
     shape. It cannot say where the world's cracks are, because that is a
     property of the MATERIAL and it lives in the baked emissive — which at this
     range is one texel, i.e. a constant. So the ground came out as one flat
     colour with the terraces picked out in it, and no exposure could fix that,
     because the missing thing was never brightness.

     This evaluates the SAME network the bake was drawn from (same seed offset,
     same width, same warp — see the c2 term in SURFACE_SRC) at a frequency
     chosen for metres rather than kilometres, and multiplies the baked emissive
     by it. MULTIPLY, never add: dead rock has emis ~= 0, so nothing here can
     light ground the art says is unlit, and the network can only appear where
     the bake already put glow. That is the same guarantee glowPulse relies on.

     THE DISTANCE FADE IS THE BAND-LIMIT, not a separate constant. gEps is set
     from this fragment's own pixel footprint, so bandw() takes the network to
     zero exactly when it stops being resolvable — and w is then reused as the
     blend weight back to the bake. One number does both jobs, and it cannot
     drift out of step with itself the way a hand-tuned near/far pair would. */
  /* ONE pixel footprint for every ground-scale term below it. This used to be
     assigned inside the emissive block; the albedo network needs the identical
     number, and two copies of a band-limit is precisely the kind of pair that
     drifts apart and then aliases in only one of them. */
  gEps = max(vDist * uPixA / uRadius, 1e-7);
  vec3 mn = normalize(vNrm);

  if(uEmisDetail > 0.0){
    float w = bandw(uDetailFreq*1.4);
    if(w > 0.001){
      vec3 dp = n*uDetailFreq + uSeedOff + vec3(3.0, 29.0, 13.0);
      float f = crackMask(uCrackWarp > 0.001 ? warp1(dp, uCrackWarp*0.7) : dp,
                          uCrackWidth2);
      /* THE CONTRAST IS SCALED BY uFineGlow, THE WORLD'S OWN ANSWER to "is the
         fine network a co-star here, or a texture". A flat mix(0.30, 2.40)
         looked superb on volcanic and BLACKED CRYSTAL OUT — 4098 distinct
         colours down to 1793, sd 17.3 down to 4.1 — because a world whose glow
         is broad rather than filamentary has f = 0 nearly everywhere, and
         multiplying by 0.30 there does not carve a network into the light, it
         removes the light. Ice worlds run fineGlow 0.30, lava 1.0, so this
         reads their existing art direction instead of adding a second knob
         that could disagree with it.
         The floor also stays near 1.0: this may only redistribute the bake's
         glow, never subtract it, or the near field goes dark and the far field
         does not, and you get a black disc following you around. */
      // MONOTONE ON PURPOSE: the floor is exactly 1.0, so this can only ADD
      // light to a crack and never take it off the rock between them. A version
      // with a floor of 0.30 looked superb on volcanic and blacked crystal out,
      // and the general danger is worse than one world: anything below 1.0 dims
      // the NEAR field while the far field keeps the bake, which is a dark disc
      // that follows the camera. Contrast still scales with uFineGlow — the
      // world's own answer to "is the fine network a co-star or a texture" —
      // so ice (0.30) barely moves and lava (1.0) gets the full network.
      s.emis *= mix(1.0, 1.0 + 2.6*uFineGlow*f, w*uEmisDetail);
    }
  }

  /* ---- THE GROUND'S OWN MATERIAL, AT METRE SCALE ------------------------
     Everything above this line gives the ground its LIGHT. Nothing gave it a
     SURFACE, and that is the whole of "it reads as scattered props on
     flat-coloured ground".

     s.col is one texel of the baked albedo cubemap — 1.2 km across — so from a
     standing eye the entire visible world is a SINGLE colour, shaded only by
     the angle it happens to face. The crack network above cannot help, because
     it may only redistribute EMISSIVE, and a world whose rock does not glow
     has none to redistribute. Measured before this term existed: verdant's
     ground was one flat teal, crystal's one flat blue. That is not a tuning
     failure that a better exposure could reach — the term was missing.

     Three signals, and the slope one is why this reads as ground rather than
     as noise laid over it:

       mottle  broad patches at gndScale metres — different rock, different
               weathering, the thing you actually see across a hillside
       grain   one finer octave for the near field, band-limited SEPARATELY so
               it dissolves first and never sizzles at distance
       slope   n IS the local radial here (vN is the vertex direction), so
               dot(mesh normal, n) is the true ground angle for free. Fines
               settle on the flats, bare rock stands on the steeps. This is the
               one cue that ties the material to the SHAPE the height field
               already built, and without it the other two are just fog.

     MULTIPLICATIVE, AND DELIBERATELY NOT SYMMETRIC — 0.45x down against 1.28x
     up. Two independent reasons. Exposure: archetypes.js rule 1 caps albedo
     near 0.75 linear or the lit surface crosses the bloom threshold and ACES
     turns blown saturated colour magenta, so a symmetric lift would put the
     pale worlds over the line. And material: real ground varies by shadowing,
     damp and dust far more than it varies by being brighter than its own rock.

     TERRAIN PROGRAM ONLY, which is what makes it safe to be bold. The orbital
     sphere is fragBaked(false) and cannot reach this code at all, so the
     12-world look approved from orbit is untouchable by any value in here. */
  /* THERE IS NO "wg > 0.001" EARLY-OUT HERE, AND THERE MUST NOT BE.
     It was here, and it silently killed the bump normal.

     "wg" is derived from gEps, which is derived from vDist — so it varies from
     pixel to pixel, and a branch on it is NON-UNIFORM control flow. dFdx/dFdy
     inside non-uniform flow are UNDEFINED in GLSL: the hardware computes
     derivatives across a 2x2 quad, and if some pixels of that quad took the
     other branch there is nothing to difference against. The driver returned
     zero, so the gradient was zero, so the normal was never bent — and it
     failed SILENTLY, with no error and a perfectly plausible picture. Sweeping
     uGndBump from 0 to 4.0 moved the frame from 4451 to 4725 distinct colours,
     i.e. not at all, which is the only reason it was caught.

     The band weights already take every term to zero at distance by
     multiplication, so the branch only ever bought speed, never correctness.
     uGndAmt is a genuine uniform, so branching on THAT is fine. */
  if(uGndAmt > 0.0){
    float wg = bandw(uGndFreq);
    {
      /* THE COARSE BAND IS WHY THE MIDDLE DISTANCE IS NOT CARDBOARD.
         Every other band here is band-limited away by about 5 km, and the
         baked cubemap it hands back to has 1.2 km texels — so between roughly
         1 and 5 km the ground had structure at NO scale at all. A hill a
         kilometre off came back as a flat cut-out of colour while the ground
         at your feet was fully textured, and the step between them read as a
         ring around the camera. At about 500 m this one survives to tens of
         km and closes that gap.

         It is computed from the UNFOLDED direction on purpose: its coordinate
         is only in the hundreds, where float32 is exact and range reduction
         buys nothing — and folding it would actively break it, for the reason
         written against the multipliers below. */
      vec3 gc = n*(uGndFreq*0.025) + uSeedOff*3.0 + vec3(61.0, 7.0, 43.0);
      float bC = snoise(gc)*bandw(uGndFreq*0.025);

      vec3 gp = fold867(n*uGndFreq + uSeedOff*7.0 + vec3(17.0, 53.0, 29.0));

      /* EXPLICIT BANDS, NOT AN fbm, and the reason is what the first version
         looked like. fbmB runs at gain 0.40 (see noise.glsl.js — the gain is
         deliberately below 0.5 so fine octaves cannot pile up), which means it
         is dominated by its FIRST octave, and its first octave is smooth
         simplex. Smooth simplex at 12 m, viewed from a standing eye where the
         bottom of the frame is three metres away, is one soft blotch across
         the whole near field: it read as a vignette, not as ground.

         Bands, each band-limited on its own, let the near field carry
         sub-metre content while the far field still dissolves cleanly. b3 is
         CREASED — 1-|noise| makes lines instead of blobs — because bands of
         smooth noise summed is still cloud, and ground has edges in it.

         THE MULTIPLIERS ARE WHOLE NUMBERS, AND THAT IS NOT A ROUNDING CHOICE.
         fold867 works because snoise has period 867, so two points either side
         of a fold boundary sit 867 apart and evaluate identically. Scale that
         folded coordinate by a NON-integer and the property is destroyed: at
         x3.7 an 867 jump becomes 3208.9, which is not a whole number of
         periods, so the band is discontinuous across every fold — a hard line
         across the ground roughly every 15 km on a 600 km world. The first
         version used 3.7, 13.0 and 7.3 and had two of them. Offsets are added
         AFTER the scaling, which is safe (a constant shifts both sides alike)
         and decorrelates bands that would otherwise share a lattice. */
      float r0 = snoise(gp);
      float r1 = snoise(gp* 4.0 + vec3(19.0,  3.0,  7.0));
      float r2 = snoise(gp*13.0 + vec3( 5.0, 31.0, 11.0));
      float r3 = 1.0 - abs(snoise(gp*7.0 + vec3(23.0, 13.0, 2.0)));

      float b0 = r0*bandw(uGndFreq);
      float b1 = r1*bandw(uGndFreq* 4.0);
      float b2 = r2*bandw(uGndFreq*13.0);
      float b3 = r3*bandw(uGndFreq* 7.0);
      float m  = sat(0.5 + 0.26*bC + 0.34*b0 + 0.26*b1 + 0.22*b2 + 0.22*(b3 - 0.5));

      /* THE BUMP FIELD IS ITS OWN FIELD, AND IT MUST BE SMOOTHER THAN THE
         COLOUR — NOT SHARPER. This got written the wrong way round first and
         the result was unmistakable: a fizzing salt-and-pepper carpet over the
         whole near field.

         The reasoning that produced it was that rock is sharp, so the bump
         should be weighted toward the FINEST bands. That is right about rock
         and wrong about arithmetic. DIFFERENTIATION AMPLIFIES HIGH FREQUENCY:
         a band sitting near the pixel limit has a per-pixel difference that is
         essentially random, so dFdx of it returns noise, and weighting the
         bump toward exactly those bands returns nothing but noise. The
         band-limit does not save it either — bandw attenuates amplitude near
         Nyquist but does not remove it, and the derivative puts the gain
         straight back.

         This is trap 16 in CLAUDE.md, "smooth before you differentiate", which
         was recorded for the Laplacian in terrain.js and applies verbatim here.
         Lowering the STRENGTH cannot fix it, for the reason given there: gain
         scales signal and noise alike, and only a low-pass separates them.

         So the bump reuses the same noise (no extra cost — that is why the raw
         values are kept above) with a band limit SB times more conservative,
         and drops r2, the finest band, entirely. */
      const float SB = 3.2;

      // 4.5 so the term saturates by about 13 degrees: on a terraced world the
      // riser has to read as a different material from the plate, and the plate
      // is not perfectly level.
      float slope = sat((1.0 - dot(mn, n))*4.5);

      /* MEAN-PRESERVING BY CONSTRUCTION. The first version was mix(0.45, 1.28)
         read straight, whose midpoint is 0.86 — so switching the term on made
         every world 14% DARKER as a side effect of giving it texture, measured
         on volcanic as mean 17.4 dropping to 14.5. Dividing by the value at
         m = 0.5 means contrast can be retuned freely without ever moving the
         exposure the orbital art was approved at. */
      float lo = 0.46, hi = 1.34;
      float v = mix(lo, hi, m)/mix(lo, hi, 0.5);

      /* AND IT MAY ONLY DARKEN GROUND THAT IS ALREADY BRIGHT.
         archetypes.js rule 1: albedo above ~0.75 linear crosses the bloom
         threshold once lit, and ACES turns blown saturated colour magenta. A
         1.48x lift on a pale world would do exactly that. "head" is the
         remaining headroom, so a dark world gets the full swing and a bright
         one gets the darkening half only — the rule enforced in code rather
         than trusted to every future archetype edit. */
      float head = sat((0.72 - max(max(s.col.r, s.col.g), s.col.b))*3.0);
      v = mix(min(v, 1.0), v, head);

      /* ---- SURFACE RELIEF IN THE LIGHTING, NOT ONLY IN THE COLOUR --------
         This is the difference between "the ground has a pattern painted on
         it" and "the ground is made of rock", and it is the thing the owner
         was pointing at when he compared this to No Man's Sky.

         Up to here the material only tinted albedo. The shading normal was
         still the mesh normal, which at 1.86 m vertex spacing is SMOOTH — so
         every light in the scene fell on a polished surface and the mottle
         read as a stain on plastic. No amount of albedo contrast fixes that,
         because the eye reads material from how light MOVES across a surface,
         not from its colour.

         The gradient comes from screen-space derivatives of the material field
         itself (Mikkelsen's tangent-free form), which costs two derivatives
         and no extra noise. That it is screen-space is a FEATURE here rather
         than the usual compromise: dFdx of m is its change across exactly one
         pixel, which is the same band-limit every other term in this shader
         uses, so the bump cannot alias — it fades out at precisely the rate
         the field it is derived from does. Scaled by "a", so it dies with
         distance alongside the colour it belongs to.

         det can be zero on a degenerate triangle (a skirt seen edge-on), and
         dividing there would spray NaNs across the frame. */
      /* THE GRADIENT IS TAKEN OVER A FIXED WORLD DISTANCE, NOT PER PIXEL.
         The first version used dFdx/dFdy of the material field, which is the
         standard tangent-free bump and is WRONG AT PLANETARY SCALE.

         The noise coordinate has magnitude up to 433 after range reduction, so
         one float32 ulp of it is about 3e-5. Standing two metres from the
         ground, one screen pixel spans under a millimetre, which is only about
         three ulps of that coordinate — so the per-pixel difference is not the
         gradient, it is a quantisation staircase that jumps between zero and
         three ulps. Differentiating it returned salt-and-pepper noise across
         the entire near field, on top of the real relief, and no band limit
         could remove it because the fault is in the COORDINATE and not in the
         field.

         Sampling at a fixed 0.55 m instead makes the step about a thousand
         ulps, which is far above the noise floor and — the part that matters —
         does not change with how close the camera is. Two extra noise calls
         (the centre sample is r1, already computed).

         Trap 16 said "smooth before you differentiate". This is its other half:
         at planetary scale, choose WHAT DISTANCE you differentiate over, and
         never let the screen choose it for you. */
      float a = uGndAmt*wg;
      if(uGndBump > 0.0){
        // branchless orthonormal basis (Duff et al.) — a fixed reference vector
        // would go singular at the poles and swirl the relief there.
        float sg = mn.z >= 0.0 ? 1.0 : -1.0;
        float ba = -1.0/(sg + mn.z);
        float bb = mn.x*mn.y*ba;
        vec3 tA = vec3(1.0 + sg*mn.x*mn.x*ba, sg*bb, -sg*mn.x);
        vec3 tB = cross(mn, tA);

        float eb = uGndFreq*0.55/uRadius;          // 0.55 m, in noise units
        vec3 off = vec3(19.0, 3.0, 7.0);
        float hA = snoise((gp + tA*eb)*4.0 + off);
        float hB = snoise((gp + tB*eb)*4.0 + off);
        // r1 is the centre sample of this same band, so the difference is a
        // real forward gradient and costs two calls rather than three.
        vec3 grad = (tA*(hA - r1) + tB*(hB - r1))*bandw(uGndFreq*4.0*SB);
        mn = normalize(mn - uGndBump*a*grad*2.4);
      }

      s.col *= mix(1.0, v*mix(1.0, 0.82, slope), a);

      /* Fines are DUST, and dust is grey. Desaturating the flats is what makes
         a pan look like it collected something rather than like the same paint
         seen face-on. Weighted by (1 - slope) so it never touches a wall, and
         by m so it pools where the mottle is already pale. */
      float lum = dot(s.col, vec3(0.2126, 0.7152, 0.0722));
      s.col = mix(s.col, vec3(lum), uGndDust*a*(1.0 - slope)*m);
    }
  }` : ''}
${meshNormal ? `
  /* ---- THE ALBEDO IS AUTHORED FOR ORBIT TOO ------------------------------
     Same fault as uEmisNear below it, in the opposite direction, and it took
     the whole terrain probe to prove because every part of the renderer is
     behaving exactly as written.

     Measured standing on barren VESPER-1 with the star 35 degrees up: the
     lambert term reads a healthy mean 116/255 varying by sd 84, the sun shadow
     reads a flat 250 (nothing is shadowed) — and the frame is mean 4.6 with 66
     distinct colours, i.e. black with two pebbles in it. Probe mode 6 says why:
     s.col comes back at mean 10.9, which IS barren's own palette. Its base
     colour is 0x37373d, a mid grey at 55/255 in sRGB, and 55/255 is 0.040 in
     LINEAR light. Times a lambert of 0.46 that is 0.018, and 0.018 through ACES
     is black. Nothing is broken. A mid grey rock lit by a star is simply not
     visible at this exposure.

     It works from orbit because from orbit the disc is a bright object against
     empty space with bloom around it, and it is judged as a shape. On the ground
     the same albedo is the whole frame and there is nothing to read it against.
     Four of thirteen worlds are affected — volcanic 102 colours at mean 10.6,
     barren 66 at 4.6, ashen 657 at 4.2, shattered 177 at 2.9 — and they are
     exactly the four whose palettes are darkest, which is what archetypes.js
     rule 1 asks for on purpose ("dark base makes the glow").

     So this lifts LUMINANCE ONLY, on a curve, on the ground only:

       - luminance only, applied as a SCALE on the colour, so hue and saturation
         come through untouched. Running pow() per channel also works and quietly
         desaturates every dark colour toward grey, which on worlds selected for
         being dark is the entire palette.
       - a curve, not a multiplier. pow(L, 0.62) lifts 0.040 by 3.6x and 0.60 by
         only 1.2x, so the dark worlds move and glacier and desert do not — one
         number, no per-archetype table to keep in sync.
       - ramped by camNear, so the approved orbital gallery cannot see it at all.

     It is deliberately NOT applied to s.emis. Rule 1 trades a dark base against
     a bright glow, and lifting both would cancel the trade and flatten exactly
     the worlds this is for. */
  {
    float L = dot(s.col, vec3(0.2126, 0.7152, 0.0722));
    float g = mix(uGndLift, 1.0, sat(uCamAlt/(uRadius*0.03)));
    if (L > 1e-5) s.col *= pow(L, g) / L;
  }` : ''}
  // mn, not normalize(vNrm): the ground material above bends it, so reading the
  // raw mesh normal here would compute the bump and then throw it away.
  s.nrm = ${meshNormal ? 'mn' : 'normalize(q.xyz)'};
  s.ao = q.w;
${meshNormal ? `
  /* THE PROBE — every INPUT to the lighting, written straight out.
     DECISIONS trap 8: if a value is not doing anything, READ IT BACK. Both of
     the bake's silent bugs were found this way and nothing else found either.
     Reasoning about which term flattened the ground cost several wrong guesses
     (the haze, the normal, the albedo); one run of this settles it, because a
     term that is constant across the frame shows up as a constant colour and a
     term that varies shows up as a picture.
       1 mesh normal   2 baked normal   3 their difference
       4 lit = sat(N.L)   5 sun shadow   6 baked albedo   7 baked AO
     Costs one uniform compare in a shader that contains no noise. */
  if(uTerrDbg > 0.5){
    vec3 shown;
    if(uTerrDbg < 1.5)      shown = s.nrm*0.5 + 0.5;
    else if(uTerrDbg < 2.5) shown = normalize(q.xyz)*0.5 + 0.5;
    else if(uTerrDbg < 3.5) shown = normalize(s.nrm - normalize(q.xyz))*0.5 + 0.5;
    else if(uTerrDbg < 4.5) shown = vec3(sat(dot(s.nrm, uSun)));
    else if(uTerrDbg < 5.5) shown = vec3(uShadowAmt <= 0.0 ? 1.0
                                    : sunShadow(n, s.h, max(uEps, uTexEps)));
    else if(uTerrDbg < 6.5) shown = s.col;
    else                    shown = vec3(s.ao - 0.5);
    outColor = vec4(shown, 1.0);
    return;
  }` : ''}

  // Never march the shadow in steps finer than the map it is reading, or all
  // four taps land in one texel, every difference is zero and the shadows
  // simply vanish as you get close.
  float eps = max(uEps, uTexEps);
  float shade = uShadowAmt <= 0.0 ? 1.0 : sunShadow(n, s.h, eps);
  outColor = vec4(shadeSurf(n, s, shade), 1.0);
}
`;

/* the orbital sphere's variant, kept under its old name so nothing else moves */
export const FRAG_BAKED = fragBaked(false);

/* ---------------------------------------------------------------------------
   THE PROBE PASSES — the instruments that found the wrong-planet bug, kept.

   They are NOT compiled into the shipping shader, and that is not tidiness.
   Pass 4 evaluates heightAt() on its own, which inlines the entire noise
   library a FOURTH time into the bake shader — measured at about 13 extra
   seconds of ANGLE compile on first launch, a third of the whole boot. Leaving
   debug code in a shader is not free the way leaving it in JavaScript is.

   So: `bakeFrag(true)` for tests, `bakeFrag(false)` for the game.
   ------------------------------------------------------------------------ */
const PROBE_SRC = /* glsl */`
  // pass 3 — ORIENTATION. Bake a function whose answer is known at every
  // direction, so "is the cube the right way round" can be told apart from
  // "is the height field right". The function must be SKEW: the first version
  // used n.y, which is invariant under every rotation and mirror about Y, so
  // it passed while the bake was wrong and cost an hour.
  if(uPass == 3)      { gl_FragColor = vec4(0.0, 0.0, 0.0,
                                            dot(n, vec3(0.3, 0.5, 0.8122))); return; }
  // pass 4 — the height field with nothing in the way. No surfaceAt, no struct,
  // no out-parameter. Splits "wrong place" from "wrong value".
  else if(uPass == 4) { gl_FragColor = vec4(0.0, 0.0, 0.0, heightAt(n)); return; }
  // passes 5-8 — a UNIFORM read straight back out. Is the value even arriving?
  // This is the one that found it: the GPU reported uSeedOff.x as 8.0156 where
  // JavaScript held 1.1925, and the same wrong number for two different worlds.
  else if(uPass == 5) { gl_FragColor = vec4(0.0, 0.0, 0.0, uSeedOff.x);      return; }
  else if(uPass == 6) { gl_FragColor = vec4(0.0, 0.0, 0.0, uContFreq);       return; }
  else if(uPass == 7) { gl_FragColor = vec4(0.0, 0.0, 0.0, uFeatParam[0].x); return; }
  else if(uPass == 8) { gl_FragColor = vec4(0.0, 0.0, 0.0, uCrackFreq);      return; }
`;

/* ---- the bake: one texel per direction, three targets --------------------- */
export const bakeFrag = (probes) => /* glsl */`
precision highp float;
varying vec2 vUv;
uniform int   uFaceIx;
uniform float uBakeSize;
uniform int   uPass;

${NOISE}
${COMMON_SRC}
${HEIGHT_FIELD_SRC}
${SURF_STRUCT_SRC}
${SURFACE_SRC}

// Direction for cube face f at face-space coords c in -1..1. These signs are
// the GL cube-map convention exactly (s = -z/|x| for +X, t = -y/|x|, and so
// on); get one of them wrong and the world is visibly torn along a seam.
vec3 cubeDir(int f, vec2 c){
  if(f == 0) return vec3( 1.0, -c.y, -c.x);
  if(f == 1) return vec3(-1.0, -c.y,  c.x);
  if(f == 2) return vec3( c.x,  1.0,  c.y);
  if(f == 3) return vec3( c.x, -1.0, -c.y);
  if(f == 4) return vec3( c.x, -c.y,  1.0);
  return vec3(-c.x, -c.y, -1.0);
}

void main(){
  vec2 c = vUv*2.0 - 1.0;
  vec3 p = cubeDir(uFaceIx, c);
  vec3 n = normalize(p);

  // The angular size of ONE TEXEL here. A cube face is a plane at distance 1,
  // so a texel near a corner is further away and foreshortened, and subtends
  // about 47% of the angle a texel at the face centre does. Using one number
  // per face leaves eight soft patches on the planet where the corners meet.
  //   |dn/du| = sqrt(1+v*v)/(1+u*u+v*v),  and the same with u and v swapped.
  // Take the geometric mean of the two, because the footprint is a rectangle.
  float u2 = c.x*c.x, v2 = c.y*c.y;
  float eps = (2.0/uBakeSize) * pow((1.0+u2)*(1.0+v2), 0.25) / (1.0 + u2 + v2);

  Surf s;
  surfaceAt(n, eps, s);
${probes ? PROBE_SRC : ''}
  if(uPass == 0)      gl_FragColor = vec4(s.col,  s.h);
  else if(uPass == 1) gl_FragColor = vec4(s.emis, 0.0);
  else                gl_FragColor = vec4(s.nrm,  s.ao);
}
`;

export const VERT_BAKE = /* glsl */`
varying vec2 vUv;
void main(){ vUv = uv; gl_Position = vec4(position.xy, 0.0, 1.0); }
`;

/* ---------------------------------------------------------------------------
   The uniform set that DESCRIBES A WORLD. `def` comes from world/planetDef.js
   and is a pure function of the seed, so the same seed always builds the same
   set. Shared by the analytic material and the bake material — and read by
   tests/conformance.mjs, which needs the height-field half of it to render the
   GPU's heightAt() as data.
   ------------------------------------------------------------------------ */
export function makeSurfaceUniforms(def) {
  const A = def.art;
  const featPos = [], featParam = [];
  for (let i = 0; i < NFEAT; i++) {
    const f = def.features[i];
    if (f) {
      featPos.push(new T.Vector3(f.x, f.y, f.z));
      featParam.push(new T.Vector4(1 - Math.cos(f.r), f.kind, f.depth, f.glow));
    } else {
      featPos.push(new T.Vector3(0, 1, 0));
      featParam.push(new T.Vector4(0, 0, 0, 0));
    }
  }

  return {
    uSun:      { value: new T.Vector3(1, 0, 0) },
    uSunCol:   { value: new T.Color(1, 0.97, 0.92) },
    uCamObj:   { value: new T.Vector3(0, 0, 3e6) },
    uRadius:   { value: def.radius },
    uRelief:   { value: def.relief },
    uDisplace: { value: A.displace },
    uEps:      { value: 0.002 },
    /* GROUND-SCALE CRACKS (terrain program only; the orbital sphere never reads
       these). uDetailFreq is derived from the world's own fine network rather
       than authored per archetype, so a world's cracks continue at ground level
       as the SAME pattern at a finer scale instead of a second, unrelated one
       laid over the top. x140 puts a cell at roughly 15 m on a 310 km world —
       fine enough to have structure across a 2 km view, coarse enough that
       snoise is nowhere near the float32 trouble the metre-scale octaves in
       detail.js have to range-reduce for. */
    uDetailFreq:  { value: A.crackFreq2 * 140 },
    uEmisDetail:  { value: A.emisDetail !== undefined ? A.emisDetail : 1.0 },
    uPixA:        { value: 0.002 },
    /* GROUND MATERIAL (terrain program only — the orbital sphere never reads
       these). The archetype authors gndScale in METRES and this converts it to
       the shader's unit, cycles per radian, against the world's own radius —
       so "12 m slabs" means 12 m slabs on a 310 km moon and on an 850 km
       giant, instead of silently becoming four times coarser on the big one.
       Declared here, in the world's uniform set, so they exist at material
       construction: trap 6 says a key added to uniforms afterwards arrives as
       ZERO forever, with no error. */
    uGndAmt:   { value: A.gndAmt },
    uGndFreq:  { value: def.radius / Math.max(1, A.gndScale) },
    uGndDust:  { value: A.gndDust },
    uGndBump:  { value: A.gndBump },
    uReliefBoost: { value: A.reliefBoost },
    uAOAmt:    { value: A.aoAmt },
    uTime:     { value: 0 },
    uSeedOff:  { value: new T.Vector3(...def.seedOff) },

    uContFreq:  { value: A.contFreq },
    uWarpAmt:   { value: A.warpAmt },
    uMtnFreq:   { value: A.mtnFreq },
    uMtnSharp:  { value: A.mtnSharp },
    uMtnAmt:    { value: A.mtnAmt },
    uMtnLo:     { value: A.mtnLo },
    uCrackFreq: { value: A.crackFreq },
    uCrackWidth:{ value: A.crackWidth },
    uCrackDepth:{ value: A.crackDepth },
    uCrackWarp: { value: A.crackWarp },
    uCrackFreq2:{ value: A.crackFreq2 },
    uCrackWidth2:{ value: A.crackWidth2 },
    uCrackHi:   { value: A.crackHi },
    uPlateGlowLo:{ value: A.plateGlowLo },
    uEmisDay:   { value: A.emisDay },
    uPlateRock: { value: A.plateRock },
    uShadowAmt: { value: A.shadowAmt },
    uFineGlow: { value: A.fineGlow },
    uTerrace:   { value: A.terrace },
    uVortexAxis:{ value: def.surfaceVortex
                    ? new T.Vector3(def.surfaceVortex.x, def.surfaceVortex.y,
                                    def.surfaceVortex.z)
                    : new T.Vector3(0, 1, 0) },
    uVortexP:   { value: def.surfaceVortex
                    ? new T.Vector2(def.surfaceVortex.twist, def.surfaceVortex.falloff)
                    : new T.Vector2(0, 1) },
    uPlateSmooth:{ value: A.plateSmooth },

    uActFreq: { value: A.actFreq },
    uActLo:   { value: A.actLo },
    uActHi:   { value: A.actHi },

    uLo:    { value: A.lo },
    uMid:   { value: A.mid },
    uHi:    { value: A.hi },
    uPeak:  { value: A.peak },
    uSeaDeep: { value: A.seaDeep },
    uSeaMid:  { value: A.seaMid },
    uShelf:   { value: A.shelf },
    uHasSea:  { value: A.hasSea ? 1 : 0 },
    uSeaLevel:{ value: A.seaLevel },
    uSeaDepthK:{ value: A.seaDepthK },
    uShelfW:  { value: A.shelfW },
    uSeaGlow: { value: A.seaGlow },
    uSeaGlowCol: { value: A.seaGlowCol },
    uWaterCrack: { value: A.waterCrack },
    uCapCol:  { value: A.capCol },
    uCapAmt:  { value: A.capAmt },
    uCapLat:  { value: A.capLat },

    uEmisA:   { value: A.emisA },
    uEmisB:   { value: A.emisB },
    uEmisC:   { value: A.emisC },
    uEmisPow: { value: A.emisPow },
    uEmisAmt: { value: A.emisAmt },
    uAccent:  { value: A.accent },
    uAccentAmt:{ value: A.accentAmt },
    uAccentFreq:{ value: A.accentFreq },

    uAtmo:    { value: A.atmo },
    uAtmoAmt: { value: A.atmoAmt },
    uAmbient: { value: A.ambient },
    /* The sky's colour over this world, and how much of it lands on the ground.
       Read from NEBULA_SETS so it cannot drift from what is actually drawn up
       there; the third entry is the filament/core colour, i.e. the brightest
       thing in that sky. skyLight is per-archetype so a world with its own
       strong glow does not need as much. */
    /* DESATURATED, and that is not a taste call. At full saturation and the
       strength ashen needed to be visible at all, the sky term repainted every
       world in its nebula's hue — verdant, a green world, came back BRIGHT
       MAGENTA because its nebula core is #b055ff. Real skylight is broad-band;
       a nebula tints it, it does not replace the albedo. So the colour is mixed
       most of the way to its own luminance, which keeps the tint and drops the
       hue-shift, and the amount comes down accordingly. */
    uSkyCol: { value: (() => {
      const c = new T.Color((NEBULA_SETS[def.key] || NEBULA_SETS.barren)[2]);
      const l = c.r * 0.2126 + c.g * 0.7152 + c.b * 0.0722;
      return c.lerp(new T.Color(l, l, l), 0.62);
    })() },
    uSkyAmt: { value: A.skyLight !== undefined ? A.skyLight : 3.4 },
    /* 0.22, swept with tests/daynight.mjs across all thirteen worlds and picked
       on two numbers at once — the day/night ratio AND the night colour count,
       because the constraint on lowering this is READABILITY and a value that
       restores day/night by making every night frame black has only moved which
       half of the clock is broken.

       0.65 is what shipped, as a literal. At 0.65 not one world reached a ratio
       of 1.25 and SEVEN of twelve were brighter at night than in daylight.
       At 0.22 the albedo worlds get a real day (glacier 1.13 -> 1.59, oceanic
       1.11 -> 1.55, verdant 1.11 -> 1.57, toxic 0.99 -> 1.51, desert 1.23 ->
       1.76) and pay almost nothing in structure for it: glacier's night colours
       go 1422 -> 1331, oceanic's 6683 -> 4737 against a DAY count of 4194, and
       inferno's actually rise. That is the term behaving as what it is — a
       smooth hemispheric multiplier, which adds brightness and not detail, so
       taking brightness back does not take detail with it.

       The glow worlds deliberately stay near parity (volcanic 0.88, inferno
       0.86, fungal 0.85, crystal 1.09) because on those the night light is the
       EMISSIVE term, not this one, and a lava world genuinely is about as bright
       at night. That is the art law working, not a value that missed. */
    uSkyNight: { value: A.skyNight !== undefined ? A.skyNight : 0.22 },
    /* 0.65 — the value that shipped as a literal, kept EXACTLY for orbit so the
       approved gallery is byte-identical. Do not "tidy" these two into one. */
    uSkyNightOrbit: { value: A.skyNightOrbit !== undefined ? A.skyNightOrbit : 0.65 },
    uGrainFreq:{ value: A.grainFreq },
    uGrainAmt: { value: A.grainAmt },

    // How much the glow moves. One number for every world on purpose — the
    // per-world variation comes out of the caldera term, which is scaled by
    // each feature's own glow, so a lava world throbs and an ice sheet only
    // breathes without anything having to be authored twice.
    /* How far the emissive is pulled down when you are standing on it.
       MEASURED, standing in a caldera on VESPER-1 volcanic at a true -12 degree
       night, sweeping the value and reading the frame back:

         value   mean   blown>250   sd (structure)
          1.00   216.5      3.2%     23.5   <- the blown cream this replaces
          0.50   169.2      0.0%     49.8   <- peak contrast
          0.25   128.6      0.0%     40.5
          0.12    87.8      0.0%     28.4
          0.06    59.3      0.0%     19.6   <- fire becoming a dim stain

       Standard deviation is the useful number, not the mean: it is how much of
       the crack network survives the tone mapper. It peaks at 0.5, and the
       blow-out is gone anywhere below 1.0.

       Lives here, in the world's uniform set, so it exists at material
       construction - trap 6. */
    uEmisNear: { value: 0.5 },
    /* How much of the ground's emissive is concentrated into the seams. 0 is the
       old flat behaviour; 1.0 takes the plate tops down to ~0.45 and lifts a
       crack bottom to ~1.85. Terrain only — the orbital sphere has no mesh to
       ask. */
    uSeamGlow: { value: 1.0 },
    /* GROUND-ONLY ALBEDO LIFT — the exponent applied to luminance. 1.0 is off,
       and off is what the orbital sphere always gets, because the term is ramped
       out by altitude. Swept with tests/standshot.mjs --sweep=uGndLift and judged
       on tests/groundgallery.mjs. Terrain program only, so no value here can
       reach the approved gallery. */
    uGndLift:  { value: A.gndLift !== undefined ? A.gndLift : 1.0 },
    uCamAlt:   { value: 1e9 },   // starts at orbit so nothing fades before the first update
    uPulse:   { value: 1 },
    /* The terrain probe (fragBaked(true) only). It lives HERE, in the world's
       uniform set, rather than being added to the material later — trap 6: three
       binds the uniform list to the program the first time the material draws,
       so a key added afterwards silently arrives as ZERO forever. Declaring it
       up front costs one float and cannot go wrong. */
    uTerrDbg: { value: 0 },
    // An arbitrary but SEEDED axis for the travelling waves, so two worlds do
    // not breathe in the same direction.
    uFlowAxis: { value: new T.Vector3(
      Math.sin(def.seedOff[0]), Math.cos(def.seedOff[1] * 0.7),
      Math.sin(def.seedOff[2] * 1.3)).normalize() },

    uIsGas:   { value: A.isGas ? 1 : 0 },
    uBandFreq:{ value: A.bandFreq },
    uBandWarp:{ value: A.bandWarp },

    uFeatPos:   { value: featPos },
    uFeatParam: { value: featParam },
  };
}

/* ---------------------------------------------------------------------------
   Material factories.

   makeAnalyticMaterial — the reference. Every pixel evaluates the world.
   makeBakedMaterial    — the shipping path. Every pixel reads it.

   Both share ONE uniforms object, so a world is described in exactly one place
   and the bake cannot be baking a different planet from the one being drawn.
   ------------------------------------------------------------------------ */
export function makeAnalyticMaterial(def, uniforms) {
  const mat = new T.ShaderMaterial({
    vertexShader: VERT_ANALYTIC,
    fragmentShader: FRAG_ANALYTIC,
    uniforms: uniforms || makeSurfaceUniforms(def),
  });
  mat.toneMapped = false;    // the composer's OutputPass does tone mapping
  return mat;
}

export function makeBakedMaterial(def, uniforms, bake) {
  const u = uniforms || makeSurfaceUniforms(def);
  u.uTexCol = { value: bake.col };
  u.uTexEmis = { value: bake.emis };
  u.uTexNrm = { value: bake.nrm };
  u.uTexEps = { value: bake.texEps };
  const mat = new T.ShaderMaterial({
    vertexShader: VERT_BAKED,
    fragmentShader: FRAG_BAKED,
    glslVersion: T.GLSL3,       // needed for textureLod in the vertex shader
    uniforms: u,
  });
  mat.toneMapped = false;
  return mat;
}

/* Kept as the old name so nothing outside has to care which path it got. */
export function makePlanetMaterial(def, uniforms) {
  return makeAnalyticMaterial(def, uniforms);
}

/* ---------------------------------------------------------------------------
   Per-frame uniform update.

   uEps is THE most important number here. It is the angular size of one screen
   pixel on the unit sphere, and on the analytic path it does two jobs: it is
   the finite-difference step for the normal, and it band-limits every noise
   octave (see noise.glsl.js). Too large and the world goes smooth; too small
   and it aliases into fizz.

   On the baked path the detail is already fixed, and uEps only sets how far the
   self-shadow marches — which is why it is still computed here.
   ------------------------------------------------------------------------ */
const PIN_EPS = typeof location !== 'undefined'
  ? parseFloat(new URLSearchParams(location.search).get('eps') || '0') : 0;

const _v = new T.Vector3();
export function updatePlanetUniforms(mesh, def, camera, sunDirWorld, sunCol, viewH, time) {
  const u = mesh.material.uniforms;
  mesh.worldToLocal(_v.copy(camera.position));
  u.uCamObj.value.copy(_v);
  toLocalDir(mesh, sunDirWorld, u.uSun.value);
  if (sunCol) u.uSunCol.value.copy(sunCol);
  u.uTime.value = time || 0;

  const pxAngle = 2 * Math.tan((camera.fov * Math.PI / 180) / 2) / viewH;
  /* Radians per screen pixel, for the GROUND's own band-limit. It is set before
     the PIN_EPS early-out on purpose: pinning eps exists to freeze the ORBITAL
     sampling footprint for an A/B, and letting it also freeze the terrain's
     would alias the near network in exactly the shots taken to judge it. */
  if (u.uPixA) u.uPixA.value = pxAngle;

  // PIN_EPS exists to bisect "is this difference just sampling scale?" — the
  // analytic path can be forced to the same footprint the bake used and the
  // two rendered side by side. ?eps=0.0015
  if (PIN_EPS > 0) { u.uEps.value = PIN_EPS; return; }

  const d = Math.max(_v.length(), def.radius * 1.001);
  let eps = pxAngle * d / def.radius * 1.35;
  eps = Math.min(Math.max(eps, 1.5e-4), 0.02);
  u.uEps.value = eps;
}
