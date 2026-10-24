// The height field. One function, one seed, one answer — the mesh builder, the
// crawler's wheels and (later) tower placement all read the planet through here,
// so there is no way for what you see and what you collide with to disagree.

import { makeNoise3D, fbm, ridged } from './noise.js';

export const WORLD = {
  seed: 20260806,
  radius: 3000,        // metres. Horizon from the chase cam sits ~400 m out,
                       // which is what makes the curve read as a curve.
  amplitude: 265,      // metres of relief from the lowest basin to the peaks
  flatLevel: -55,      // below this the terrain solidifies into "the Flats"
};

const TERRACE_STEP = 24;   // metres per rock bed

const n = makeNoise3D(WORLD.seed);
const nWarp = makeNoise3D(WORLD.seed ^ 0x9e3779b9);
const nOre = makeNoise3D(WORLD.seed ^ 0x51ed270b);
const nGrove = makeNoise3D(WORLD.seed ^ 0x1b873593);

// Scratch, so sampling per-vertex allocates nothing.
const q = { x: 0, y: 0, z: 0 };

// p must be a UNIT vector (a direction from the planet centre).
// Returns metres of elevation relative to the nominal radius.
export function elevation(px, py, pz) {
  // Domain warp first: without it, fbm ridges look like noise; with it they
  // bend, and bent ridges read as geology.
  const wx = fbm(nWarp, px * 1.7, py * 1.7, pz * 1.7, 4);
  const wy = fbm(nWarp, px * 1.7 + 31.4, py * 1.7 + 17.2, pz * 1.7 - 9.6, 4);
  const wz = fbm(nWarp, px * 1.7 - 5.1, py * 1.7 + 63.8, pz * 1.7 + 22.7, 4);
  q.x = px + wx * 0.11;
  q.y = py + wy * 0.11;
  q.z = pz + wz * 0.11;

  const cont = fbm(n, q.x * 1.25, q.y * 1.25, q.z * 1.25, 6);
  const mask = smoothstep(-0.06, 0.42, cont);
  const mnt = ridged(n, q.x * 3.4, q.y * 3.4, q.z * 3.4, 6);
  const det = fbm(n, q.x * 11.0, q.y * 11.0, q.z * 11.0, 4);

  let h = cont * 0.42 + mnt * mask * 0.74 + det * 0.055;
  let e = h * WORLD.amplitude;

  // Terracing. Without this the planet is one continuous noise field: pleasant
  // from orbit, featureless at the 10-200 m scale where a base actually sits.
  // Bedding the rock into steps gives hard silhouette edges, a readable sense
  // of scale — and flat treads, which is somewhere worth anchoring.
  const bedMask = smoothstep(0.02, 0.40, fbm(nWarp, q.x * 2.2 + 51.3, q.y * 2.2 - 12.7, q.z * 2.2 + 8.9, 3));
  if (bedMask > 0.001) {
    const t = e / TERRACE_STEP;
    const base = Math.floor(t);
    const f = t - base;
    // Long flat tread, then a fast riser.
    const stepped = (base + Math.pow(f, 3.4)) * TERRACE_STEP;
    e = e + (stepped - e) * bedMask * 0.85;
  }

  // The Flats: basins fill with solidified coolant, so anything below the flat
  // level collapses to a near-plane instead of a hole. Drivable, and it gives
  // the horizon a hard edge you can read from 2 km away.
  if (e < WORLD.flatLevel) e = WORLD.flatLevel - (WORLD.flatLevel - e) * 0.06;

  return e;
}

// Distance from the planet centre to the surface, in metres.
export function surfaceRadius(px, py, pz) {
  return WORLD.radius + elevation(px, py, pz);
}

// Which ore a point sits on. The shader draws these as glowing veins, so the
// pretty thing on the ground IS the resource — when extractors land, they go
// exactly where the planet already looks interesting.
//
// Two scales on purpose. `field` is broad (~1 km patches) and safe to read at
// any level of detail, so distant ground still glows. `vein` is fine (~14 m)
// and only sampled up close, so the glow resolves into actual veins as you
// drive in. All four values are 0..1.
const ore = { massVein: 0, techVein: 0, massField: 0, techField: 0 };
export function oreAt(px, py, pz) {
  // Deliberately rare. Ore has to be something you go looking for — if most of
  // the planet glows, none of it is worth driving to, and the whole risk of
  // stopping somewhere exposed evaporates.
  const region = fbm(nOre, px * 2.6, py * 2.6, pz * 2.6, 3);
  ore.massField = smoothstep(0.20, 0.44, region);
  ore.techField = smoothstep(-0.24, -0.48, region);
  if (ore.massField <= 0 && ore.techField <= 0) {
    ore.massVein = 0; ore.techVein = 0;
    return ore;
  }
  const vein = ridged(nOre, px * 85, py * 85, pz * 85, 4);
  const strength = smoothstep(0.74, 0.93, vein);
  ore.massVein = strength * ore.massField;
  ore.techVein = strength * ore.techField;
  return ore;
}

// How thickly boulders lie at a point, 0..1. Patchy on purpose: a planet with
// an even sprinkle of rocks everywhere reads as texture, not as terrain.
export function scatterDensity(px, py, pz) {
  const f = fbm(nWarp, px * 6.5 + 91.2, py * 6.5 - 44.6, pz * 6.5 + 17.8, 3);
  return smoothstep(-0.18, 0.30, f);
}

// Where the flora grows, 0..1. Lower frequency than the rock fields, so groves
// are big enough to be a landmark you steer toward rather than a texture.
export function groveDensity(px, py, pz) {
  const f = fbm(nGrove, px * 3.1 + 7.7, py * 3.1 + 88.4, pz * 3.1 - 23.1, 4);
  const patch = smoothstep(0.06, 0.42, f);
  // Thins toward the edges of a grove instead of stopping at a hard line.
  const edge = fbm(nGrove, px * 14.0, py * 14.0, pz * 14.0, 2);
  return patch * smoothstep(-0.45, 0.15, edge);
}

export function smoothstep(a, b, x) {
  const t = Math.min(1, Math.max(0, (x - a) / (b - a)));
  return t * t * (3 - 2 * t);
}
