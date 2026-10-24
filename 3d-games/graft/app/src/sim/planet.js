// The ground. Pure maths: no DOM, no three.js, no Math.random.
//
// ONE height function, used by the mesh, by the player's feet, by placement and
// by the camera. If the renderer ever computed its own, the player would sink
// through hills at chunk seams -- a lesson this portfolio has already paid for.

import { C } from './const.js';

/* ---------------------------------------------------------------- vectors */
export const v = (x = 0, y = 0, z = 0) => ({ x, y, z });
export const add = (a, b) => v(a.x + b.x, a.y + b.y, a.z + b.z);
export const sub = (a, b) => v(a.x - b.x, a.y - b.y, a.z - b.z);
export const mul = (a, k) => v(a.x * k, a.y * k, a.z * k);
export const dot = (a, b) => a.x * b.x + a.y * b.y + a.z * b.z;
export const cross = (a, b) => v(
  a.y * b.z - a.z * b.y,
  a.z * b.x - a.x * b.z,
  a.x * b.y - a.y * b.x,
);
export const len = (a) => Math.sqrt(dot(a, a));
export function norm(a) {
  const l = len(a) || 1;
  return v(a.x / l, a.y / l, a.z / l);
}
/** Angle between two unit vectors, i.e. distance over the sphere / radius. */
export function arc(a, b) {
  return Math.acos(Math.max(-1, Math.min(1, dot(a, b))));
}
/** Great-circle distance in world units. */
export function surfDist(a, b) { return arc(a, b) * C.planet.radius; }

/* ------------------------------------------------------------------ noise */
// A weak hash biases the whole planet: the first version's mean sat well below
// its own midpoint, which drowned 97% of the world before anyone chose a sea
// level. Math.imul keeps every step in int32 and actually mixes.
function hash(x, y, z) {
  let h = Math.imul(x | 0, 374761393) + Math.imul(y | 0, 668265263)
        + Math.imul(z | 0, 1274126177) + Math.imul(C.seed, 2654435761);
  h = Math.imul(h ^ (h >>> 15), 2246822519);
  h = Math.imul(h ^ (h >>> 13), 3266489917);
  h ^= h >>> 16;
  return (h >>> 0) / 4294967296;
}
const fade = (t) => t * t * (3 - 2 * t);
const lerp = (a, b, t) => a + (b - a) * t;

function value3(x, y, z) {
  const xi = Math.floor(x), yi = Math.floor(y), zi = Math.floor(z);
  const xf = fade(x - xi), yf = fade(y - yi), zf = fade(z - zi);
  const c = (dx, dy, dz) => hash(xi + dx, yi + dy, zi + dz);
  const x00 = lerp(c(0, 0, 0), c(1, 0, 0), xf);
  const x10 = lerp(c(0, 1, 0), c(1, 1, 0), xf);
  const x01 = lerp(c(0, 0, 1), c(1, 0, 1), xf);
  const x11 = lerp(c(0, 1, 1), c(1, 1, 1), xf);
  return lerp(lerp(x00, x10, yf), lerp(x01, x11, yf), zf);
}

function fbm(p, octaves, freq) {
  let a = 1, f = freq, sum = 0, norm_ = 0;
  for (let i = 0; i < octaves; i++) {
    sum += a * value3(p.x * f, p.y * f, p.z * f);
    norm_ += a;
    a *= 0.5;
    f *= 2.03;
  }
  return sum / norm_;
}

/* ----------------------------------------------------------------- ground */
/** Height above the base sphere, for a UNIT direction. */
// Swept against the only thing that matters here: how much dry land you can
// actually put a building on. The first version was 34% buildable with a median
// slope of 0.45 -- above the limit for a hab -- so the game's main verb was
// blocked almost everywhere and it read as craggy rather than as a place.
// `tools/tune_terrain.mjs` regenerates that table.
export function heightAt(dir) {
  const n = fbm(dir, 4, 1.6) * 2 - 1;          // -1..1, rolling
  const ridge = 1 - Math.abs(fbm(dir, 3, 5.1) * 2 - 1);
  return (n * 0.78 + (ridge - 0.5) * 0.22) * C.planet.relief;
}

/** Distance from the planet centre to the ground, for a unit direction. */
export function groundR(dir) { return C.planet.radius + heightAt(dir); }

/** World position of the ground under a unit direction. */
export function groundAt(dir) { return mul(dir, groundR(dir)); }

/** Steepness 0..1, for deciding what will grow where. */
export function slopeAt(dir) {
  const e = 0.012;
  const b = basis(dir);
  const a = heightAt(norm(add(dir, mul(b.east, e))));
  const c = heightAt(norm(add(dir, mul(b.east, -e))));
  const d = heightAt(norm(add(dir, mul(b.north, e))));
  const f = heightAt(norm(add(dir, mul(b.north, -e))));
  const g = Math.hypot(a - c, d - f) / (2 * e * C.planet.radius);
  return Math.min(1, g * 2.2);
}

/** A stable tangent frame at a point on the sphere. */
export function basis(dir) {
  const up = norm(dir);
  // Swapping the reference near the poles is what stops the frame collapsing;
  // nothing else in the game knows the poles exist.
  const ref = Math.abs(up.y) > 0.995 ? v(1, 0, 0) : v(0, 1, 0);
  const east = norm(cross(ref, up));
  const north = cross(up, east);
  return { up, east, north };
}

/** Unit direction you face, from a yaw in the tangent plane. */
export function facing(dir, yaw) {
  const b = basis(dir);
  return norm(add(mul(b.north, Math.cos(yaw)), mul(b.east, Math.sin(yaw))));
}

/** Walk along the surface. Returns the new unit direction. */
export function step(dir, yaw, forward, strafe, dist) {
  if (!forward && !strafe) return dir;
  const b = basis(dir);
  const f = add(mul(b.north, Math.cos(yaw)), mul(b.east, Math.sin(yaw)));
  const r = add(mul(b.north, -Math.sin(yaw)), mul(b.east, Math.cos(yaw)));
  const move = add(mul(f, forward), mul(r, strafe));
  const m = len(move);
  if (m < 1e-6) return dir;
  const t = mul(move, 1 / m);
  return norm(add(dir, mul(t, dist / C.planet.radius)));
}

/** Deterministic scatter of unit directions, for the world's opening state. */
export function scatter(n, seedOffset) {
  const out = [];
  const golden = Math.PI * (3 - Math.sqrt(5));
  for (let i = 0; i < n; i++) {
    const k = i + 0.5 + (seedOffset % 1);
    const y = 1 - (k / n) * 2;
    const r = Math.sqrt(Math.max(0, 1 - y * y));
    const th = golden * (i + seedOffset);
    out.push(v(Math.cos(th) * r, y, Math.sin(th) * r));
  }
  return out;
}
