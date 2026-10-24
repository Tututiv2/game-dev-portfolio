import * as THREE from "three";
import { SimplexNoise } from "three/addons/math/SimplexNoise.js";

/** Deterministic RNG for SimplexNoise permutation (one instance per planet). */
function makeSeededRng(seed) {
  let s = (seed >>> 0) || 1;
  return {
    random() {
      s = Math.imul(s, 1664525) + 1013904223;
      return ((s >>> 0) & 0xffffff) / 0x1000000;
    },
  };
}

function fbm3(noise, x, y, z, octaves, lacunarity, gain) {
  let sum = 0;
  let amp = 1;
  let freq = 1;
  let norm = 0;
  for (let i = 0; i < octaves; i++) {
    sum += amp * noise.noise3d(x * freq, y * freq, z * freq);
    norm += amp;
    amp *= gain;
    freq *= lacunarity;
  }
  return sum / norm;
}

/**
 * @typedef {Object} TerrainProfile
 * @property {string} kind — "terra" | "rocky" | "barren" | "gas" | "ice" | "moon"
 * @property {number} baseScale — noise input scale vs radius (typical 0.00012–0.00035)
 * @property {number} continentAmp — soft undulation (radial multiplier amplitude)
 * @property {number} detailAmp — tiny high-freq roughness (mostly non-plains)
 * @property {number} ridgedAmp — unused for height (kept for preset compatibility)
 * @property {number} warpAmp — domain warp strength (0 = off)
 * @property {number} [maxRelief] — clamp |h| for radial multiplier (default 0.14)
 * @property {number} [seaLevelH] — below ≈ ocean (h = |v|/r − 1)
 * @property {number} [coastTopH] — top of beach / start of solid green
 * @property {number} [landHiH] — hills before snow blend
 * @property {number} [snowLineH] — white peaks begin
 * @property {number} [snowCapH] — mostly white
 * @property {number} [macroScale] — extra-low frequency (oceans / plateaus / basins)
 * @property {number} [macroAmp] — strength of macro layer
 * @property {number} [macroOctaves] — octaves for macro FBM (default 3)
 * @property {number} [contOctaves] — octaves for meso continent FBM (default 4)
 * @property {number} [plainsRollingAmp] — subtle hills on flat regions
 * @property {number} [mountainUpliftAmp] — smooth massifs (positive massBlend)
 * @property {number} [mountainSinkAmp] — smooth valleys (negative massBlend)
 * @property {number} [mountainMesoScale] — spatial scale of mountain FBM (lower = wider mountains)
 * @property {number} [rangeMaskLow] — rare gate: below → no range uplift (see rangeMaskHigh)
 * @property {number} [rangeMaskHigh] — gate fully on; ~fraction between ≈ mountain-range coverage
 * @property {number} [hillbeltAmp] — rolling hills outside rare ranges (not flat)
 * @property {number} [peakRoundPower] — >1 rounds summits (less needle peaks)
 */

const DEFAULT_PROFILE = {
  kind: "rocky",
  baseScale: 0.0002,
  macroScale: 0.26,
  macroOctaves: 3,
  macroAmp: 0.072,
  contOctaves: 4,
  continentAmp: 0.028,
  plainsRollingAmp: 0.008,
  hillbeltAmp: 0.012,
  mountainMesoScale: 0.36,
  mountainUpliftAmp: 0.1,
  mountainSinkAmp: 0.05,
  rangeMaskLow: 0.7,
  rangeMaskHigh: 0.9,
  peakRoundPower: 1.38,
  detailAmp: 0.0028,
  ridgedAmp: 0,
  warpAmp: 0.00008,
};

/**
 * Smooth FBM only. Mountain **ranges** are rare (separate low-freq gate, ~few % of sphere).
 * Peaks are rounded (power curve), not cones. Rest of world keeps meso + hillbelt so it is not flat.
 */
function smoothLandmassHeight(n, x, y, z, p) {
  const elK = p.macroScale ?? 0.26;
  const elephant = fbm3(n, x * elK, y * elK, z * elK, p.macroOctaves ?? 3, 2.0, 0.52);
  const gateA = fbm3(n, x * 0.11 + 41.7, y * 0.11 + 11.2, z * 0.11 + 73.9, 2, 2.0, 0.52);
  const gateB = fbm3(n, x * 0.088 + 120.0, y * 0.088 + 66.0, z * 0.088 + 3.0, 2, 2.0, 0.5);
  const gate = gateA * 0.52 + gateB * 0.48;
  const lo = p.rangeMaskLow ?? 0.7;
  const hi = p.rangeMaskHigh ?? 0.9;
  const mountWA = THREE.MathUtils.smoothstep(gate, lo, hi);

  const macroLift = elephant * (p.macroAmp ?? 0.078);
  const mesoRoll =
    fbm3(n, x * 0.74, y * 0.74, z * 0.74, p.contOctaves ?? 4, 2.05, 0.48) * (p.continentAmp ?? 0.034);

  const roll =
    fbm3(n, x * 1.18, y * 1.18, z * 1.18, 3, 2.0, 0.5) *
    (p.plainsRollingAmp ?? 0.01) *
    (0.5 + 0.5 * (1 - mountWA * 0.75));

  const hillbelt =
    fbm3(n, x * 1.48, y * 1.48, z * 1.48, 3, 2.0, 0.48) * (p.hillbeltAmp ?? 0.015) * (1 - mountWA * 0.92);

  const ms = p.mountainMesoScale ?? 0.36;
  const mass1 = fbm3(n, x * ms * 0.86, y * ms * 0.86, z * ms * 0.86, 4, 1.9, 0.47);
  const mass2 = fbm3(n, x * ms * 1.32, y * ms * 1.32, z * ms * 1.32, 4, 2.0, 0.48);
  const massS = fbm3(n, x * ms * 0.5, y * ms * 0.5, z * ms * 0.5, 3, 1.95, 0.5);
  const massBlend = mass1 * 0.38 + mass2 * 0.34 + massS * 0.28;

  const pr = p.peakRoundPower ?? 1.38;
  const pos = Math.max(0, massBlend);
  const roundedUp = 1 - Math.pow(1 - Math.min(1, pos * 0.9 + 0.1), pr);

  const neg = Math.max(0, -massBlend);
  const roundedDn = -Math.pow(neg, 0.8);

  const uplift = roundedUp * (p.mountainUpliftAmp ?? 0.11) * mountWA;
  const sink = roundedDn * (p.mountainSinkAmp ?? 0.052) * mountWA;

  const summitDamp = 1 - 0.6 * roundedUp * mountWA;
  const micro =
    fbm3(n, x * 1.55, y * 1.55, z * 1.55, 2, 2.0, 0.5) *
    (p.detailAmp ?? 0.0028) *
    (0.05 + 0.95 * mountWA) *
    summitDamp;

  return macroLift + mesoRoll + roll + hillbelt + uplift + sink + micro;
}

const STYLE_PRESETS = {
  terra: {
    kind: "terra",
    baseScale: 0.000118,
    macroScale: 0.23,
    macroOctaves: 3,
    macroAmp: 0.086,
    contOctaves: 4,
    continentAmp: 0.03,
    plainsRollingAmp: 0.0088,
    hillbeltAmp: 0.013,
    mountainMesoScale: 0.33,
    mountainUpliftAmp: 0.112,
    mountainSinkAmp: 0.054,
    rangeMaskLow: 0.72,
    rangeMaskHigh: 0.91,
    peakRoundPower: 1.45,
    detailAmp: 0.003,
    ridgedAmp: 0,
    warpAmp: 0.0001,
    maxRelief: 0.24,
    seaLevelH: -0.032,
    coastTopH: -0.01,
    landHiH: 0.04,
    snowLineH: 0.1,
    snowCapH: 0.16,
  },
  rocky: {
    kind: "rocky",
    baseScale: 0.00016,
    macroScale: 0.26,
    macroOctaves: 3,
    macroAmp: 0.078,
    contOctaves: 4,
    continentAmp: 0.028,
    plainsRollingAmp: 0.0075,
    hillbeltAmp: 0.011,
    mountainMesoScale: 0.35,
    mountainUpliftAmp: 0.098,
    mountainSinkAmp: 0.05,
    rangeMaskLow: 0.7,
    rangeMaskHigh: 0.9,
    peakRoundPower: 1.38,
    detailAmp: 0.0028,
    ridgedAmp: 0,
    warpAmp: 0.00009,
    maxRelief: 0.22,
  },
  barren: {
    kind: "barren",
    baseScale: 0.000175,
    macroScale: 0.28,
    macroOctaves: 3,
    macroAmp: 0.082,
    contOctaves: 4,
    continentAmp: 0.03,
    plainsRollingAmp: 0.007,
    hillbeltAmp: 0.012,
    mountainMesoScale: 0.38,
    mountainUpliftAmp: 0.1,
    mountainSinkAmp: 0.054,
    rangeMaskLow: 0.68,
    rangeMaskHigh: 0.89,
    peakRoundPower: 1.35,
    detailAmp: 0.003,
    ridgedAmp: 0,
    warpAmp: 0.00007,
    maxRelief: 0.23,
  },
  ice: {
    kind: "ice",
    baseScale: 0.000112,
    macroScale: 0.29,
    macroOctaves: 3,
    macroAmp: 0.068,
    contOctaves: 4,
    continentAmp: 0.026,
    plainsRollingAmp: 0.006,
    hillbeltAmp: 0.009,
    mountainMesoScale: 0.4,
    mountainUpliftAmp: 0.074,
    mountainSinkAmp: 0.042,
    rangeMaskLow: 0.62,
    rangeMaskHigh: 0.86,
    peakRoundPower: 1.4,
    detailAmp: 0.0024,
    ridgedAmp: 0,
    warpAmp: 0.00006,
    maxRelief: 0.18,
  },
  gas: {
    kind: "gas",
    baseScale: 0.000055,
    macroScale: 0.28,
    macroOctaves: 2,
    macroAmp: 0.0085,
    contOctaves: 3,
    continentAmp: 0.0072,
    detailAmp: 0.0016,
    ridgedAmp: 0,
    warpAmp: 0,
    maxRelief: 0.024,
  },
  moon: {
    kind: "moon",
    baseScale: 0.00019,
    macroScale: 0.3,
    macroOctaves: 3,
    macroAmp: 0.062,
    contOctaves: 4,
    continentAmp: 0.026,
    plainsRollingAmp: 0.0055,
    hillbeltAmp: 0.008,
    mountainMesoScale: 0.42,
    mountainUpliftAmp: 0.082,
    mountainSinkAmp: 0.044,
    rangeMaskLow: 0.66,
    rangeMaskHigh: 0.88,
    peakRoundPower: 1.32,
    detailAmp: 0.0028,
    ridgedAmp: 0,
    warpAmp: 0.000045,
    maxRelief: 0.2,
  },
};

export function terrainProfileForStyle(style) {
  return { ...DEFAULT_PROFILE, ...(STYLE_PRESETS[style] || STYLE_PRESETS.rocky) };
}

export function createTerrainContext(seed) {
  const noise = new SimplexNoise(makeSeededRng(seed));
  return { noise, seed };
}

/**
 * Radial multiplier (≈1.0) for a direction on the unit sphere in **planet local** space.
 * Must match mesh displacement.
 */
export function getRadialDisplacement(terrainCtx, localDirUnit, radius, profile) {
  /** All call sites pass profiles pre-merged with DEFAULT_PROFILE; only merge for partial ones (perf: this runs per vertex + per frame). */
  const p = profile && profile.baseScale != null ? profile : { ...DEFAULT_PROFILE, ...profile };
  const s = radius * p.baseScale;
  let x = localDirUnit.x * s;
  let y = localDirUnit.y * s;
  let z = localDirUnit.z * s;

  if (p.warpAmp > 0) {
    const w = radius * p.warpAmp;
    x += terrainCtx.noise.noise3d(y * 2.1, z * 2.1, localDirUnit.x * s * 0.5) * w;
    y += terrainCtx.noise.noise3d(z * 2.1, x * 2.1, localDirUnit.y * s * 0.5) * w;
    z += terrainCtx.noise.noise3d(x * 2.1, y * 2.1, localDirUnit.z * s * 0.5) * w;
  }

  const n = terrainCtx.noise;
  let h = 0;

  if (p.kind === "gas") {
    const mk = p.macroScale ?? 0.3;
    const macro = fbm3(n, x * mk, y * mk, z * mk, p.macroOctaves ?? 2, 2.0, 0.5) * (p.macroAmp ?? 0.006);
    const fine = fbm3(n, x * 0.58, y * 0.58, z * 0.58, p.contOctaves ?? 3, 2.0, 0.5) * p.continentAmp;
    h = macro + fine + fbm3(n, x * 1.2, y * 1.2, z * 1.2, 2, 2.0, 0.5) * (p.detailAmp ?? 0);
  } else {
    h = smoothLandmassHeight(n, x, y, z, p);
  }

  const maxR =
    p.maxRelief != null ? p.maxRelief : p.kind === "gas" ? 0.018 : 0.14;
  return 1 + THREE.MathUtils.clamp(h, -maxR, maxR);
}

function hexToRgb(hex) {
  const c = new THREE.Color(hex);
  return { r: c.r, g: c.g, b: c.b };
}

function mixRgb(a, b, t) {
  const u = THREE.MathUtils.clamp(t, 0, 1);
  return {
    r: a.r + (b.r - a.r) * u,
    g: a.g + (b.g - a.g) * u,
    b: a.b + (b.b - a.b) * u,
  };
}

/**
 * Builds a displaced sphere with vertex colors; stores nothing on the mesh.
 */
export function buildPlanetGeometry(radius, widthSegs, heightSegs, terrainCtx, profile, baseHex) {
  const geo = new THREE.SphereGeometry(radius, widthSegs, heightSegs);
  const pos = geo.attributes.position;
  const col = new Float32Array(pos.count * 3);
  const base = hexToRgb(baseHex);
  const p = { ...DEFAULT_PROFILE, ...profile };
  const _v = new THREE.Vector3();
  const _n = new THREE.Vector3();
  const _rad = new THREE.Vector3();

  for (let i = 0; i < pos.count; i++) {
    _v.fromBufferAttribute(pos, i);
    _rad.copy(_v).normalize();
    const mul = getRadialDisplacement(terrainCtx, _rad, radius, p);
    pos.setXYZ(i, _v.x * mul, _v.y * mul, _v.z * mul);
  }

  geo.computeVertexNormals();
  const normal = geo.attributes.normal;

  for (let i = 0; i < pos.count; i++) {
    _v.fromBufferAttribute(pos, i);
    _n.fromBufferAttribute(normal, i);
    _rad.copy(_v).normalize();
    const flatness = Math.max(0, _n.dot(_rad));
    const cliff = 1 - flatness;
    const h = _v.length() / radius - 1;

    let rgb = { ...base };
    if (p.kind === "gas") {
      const lat = Math.abs(_rad.y);
      const bands = 0.5 + 0.5 * Math.sin(lat * 28 + fbm3(terrainCtx.noise, _rad.x * 8, _rad.y * 8, _rad.z * 8, 2, 2, 0.5) * 3);
      const deep = { r: base.r * 0.55, g: base.g * 0.5, b: base.b * 0.45 };
      const storm = { r: Math.min(1, base.r * 1.25), g: Math.min(1, base.g * 1.1), b: base.b * 0.95 };
      rgb = mixRgb(deep, storm, bands);
      const pole = mixRgb(rgb, { r: 0.92, g: 0.9, b: 0.85 }, Math.pow(lat, 6) * 0.55);
      rgb = pole;
    } else if (p.kind === "ice") {
      const maxR = p.maxRelief ?? 0.26;
      const hn = h / Math.max(1e-5, maxR);
      const low = { r: 0.35, g: 0.45, b: 0.55 };
      const mid = { r: 0.55, g: 0.62, b: 0.68 };
      const peak = { r: 0.92, g: 0.95, b: 1 };
      const t = THREE.MathUtils.clamp((hn + 0.28) / 0.95, 0, 1);
      rgb = mixRgb(mixRgb(low, mid, t), peak, cliff * 0.65 + t * 0.28);
    } else if (p.kind === "moon") {
      const maxR = p.maxRelief ?? 0.34;
      const hn = h / Math.max(1e-5, maxR);
      const shadow = { r: base.r * 0.28, g: base.g * 0.28, b: base.b * 0.3 };
      const hi = { r: Math.min(1, base.r * 1.15), g: Math.min(1, base.g * 1.15), b: Math.min(1, base.b * 1.15) };
      const t = THREE.MathUtils.clamp((hn + 0.22) / 0.78, 0, 1);
      rgb = mixRgb(shadow, hi, t * (0.45 + flatness * 0.45) + cliff * 0.28);
    } else if (p.kind === "barren") {
      const maxR = p.maxRelief ?? 0.36;
      const hn = h / Math.max(1e-5, maxR);
      const dust = mixRgb({ r: 0.45, g: 0.28, b: 0.18 }, base, 0.55);
      const rock = mixRgb({ r: 0.32, g: 0.3, b: 0.28 }, base, 0.5);
      const cap = mixRgb({ r: 0.55, g: 0.52, b: 0.48 }, base, 0.45);
      const t = THREE.MathUtils.clamp((hn + 0.2) / 0.88, 0, 1);
      rgb = mixRgb(mixRgb(dust, rock, t * 0.72 + cliff * 0.38), cap, t * 0.42);
    } else if (p.kind === "terra") {
      const maxR = p.maxRelief != null ? p.maxRelief : 0.178;
      const sea = p.seaLevelH ?? -0.012;
      const coast = p.coastTopH ?? -0.0025;
      const mid = p.landHiH ?? 0.019;
      const snowL = p.snowLineH ?? 0.036;
      const peak = p.snowCapH ?? 0.058;
      const oceanDeep = { r: 0.02, g: 0.12, b: 0.38 };
      const oceanShallow = { r: 0.06, g: 0.38, b: 0.68 };
      const coastSand = { r: 0.76, g: 0.68, b: 0.45 };
      const grassLo = { r: 0.16, g: 0.44, b: 0.14 };
      const grassHi = { r: 0.28, g: 0.55, b: 0.2 };
      const hill = { r: 0.36, g: 0.48, b: 0.28 };
      const rockGrey = { r: 0.45, g: 0.44, b: 0.4 };
      const snow = { r: 0.92, g: 0.95, b: 1 };

      if (h <= sea) {
        const t = THREE.MathUtils.smoothstep((h + maxR) / Math.max(1e-5, maxR + sea), 0, 1);
        rgb = mixRgb(oceanDeep, oceanShallow, t);
      } else if (h < coast) {
        rgb = mixRgb(oceanShallow, coastSand, (h - sea) / Math.max(1e-5, coast - sea));
      } else if (h < mid) {
        rgb = mixRgb(coastSand, grassLo, (h - coast) / Math.max(1e-5, mid - coast));
      } else if (h < snowL) {
        const t = (h - mid) / Math.max(1e-5, snowL - mid);
        rgb = mixRgb(grassLo, grassHi, t * flatness + cliff * 0.12);
        rgb = mixRgb(rgb, hill, t * 0.35 + cliff * 0.25);
      } else if (h < peak) {
        const t = (h - snowL) / Math.max(1e-5, peak - snowL);
        rgb = mixRgb(grassHi, snow, t + cliff * 0.22);
        rgb = mixRgb(rgb, rockGrey, cliff * 0.35 * (1 - t));
      } else {
        const t = THREE.MathUtils.clamp((h - peak) / Math.max(1e-5, maxR - peak), 0, 1);
        rgb = mixRgb(snow, { r: 1, g: 1, b: 1 }, t * 0.55 + cliff * 0.15);
      }
    } else {
      const deep = { r: 0.12, g: 0.22, b: 0.1 };
      const grass = { r: base.r * 0.85, g: Math.min(1, base.g * 1.05), b: base.b * 0.75 };
      const rock = { r: 0.38, g: 0.34, b: 0.28 };
      const snow = { r: 0.88, g: 0.9, b: 0.92 };
      const t = THREE.MathUtils.clamp((h + 0.015) / 0.05, 0, 1);
      rgb = mixRgb(deep, grass, t * flatness + cliff * 0.15);
      rgb = mixRgb(rgb, rock, cliff * 0.55);
      rgb = mixRgb(rgb, snow, Math.max(0, (h - 0.035) / 0.06) * flatness);
    }

    col[i * 3] = rgb.r;
    col[i * 3 + 1] = rgb.g;
    col[i * 3 + 2] = rgb.b;
  }

  geo.setAttribute("color", new THREE.BufferAttribute(col, 3));
  return geo;
}
