import { clamp, createCanvas, smooth2d, luma, sat } from './util.js';

/**
 * Ground construction.
 *
 * The photo's ground region is inverse-projected into a top-down orthophoto of
 * the world square. Working in world space rather than image space matters: it
 * removes the perspective stretch, so a texture-energy reading near the horizon
 * means the same thing as one at your feet, and the heightfield derived from it
 * is coherent instead of smeared.
 *
 * The photo only covers a wedge in front of you. Everything outside that wedge
 * is extended procedurally from the photo's own ground palette, then hidden
 * under distance fog, so the world has no visible edge to fall off.
 */

export const WORLD_SIZE = 220; // metres across
export const TEX_RES = 1024;
export const GRID_RES = 160;

/** Player stands at (0,0); the photo's wedge opens toward +z. */
export const Z_MIN = -WORLD_SIZE * 0.25;

export function buildGround(fullImg, camera, horizonPx, opts = {}) {
  const size = opts.size ?? WORLD_SIZE;
  const texRes = opts.texRes ?? TEX_RES;
  const gridRes = opts.gridRes ?? GRID_RES;
  const zMin = opts.zMin ?? Z_MIN;

  const scale = fullImg.width / camera.width; // analysis-res camera -> full-res pixels
  const seed = hashImage(fullImg);
  const noise = makeValueNoise(seed);

  const groundStats = sampleGroundPalette(fullImg, camera, horizonPx, scale);

  const canvas = createCanvas(texRes, texRes);
  const ctx = canvas.getContext('2d');
  const out = ctx.createImageData(texRes, texRes);
  const covered = new Float32Array(texRes * texRes);
  const lumaField = new Float32Array(texRes * texRes);
  const texField = new Float32Array(texRes * texRes);

  const yhFull = horizonPx * scale;

  for (let ty = 0; ty < texRes; ty++) {
    // v runs +z away from the player
    const z = zMin + ((ty + 0.5) / texRes) * size;
    for (let tx = 0; tx < texRes; tx++) {
      const x = ((tx + 0.5) / texRes - 0.5) * size;
      const i = ty * texRes + tx;
      const di = i * 4;

      let r;
      let g;
      let b;
      let cov = 0;

      if (z > 0.6) {
        const col = camera.colAtX(x, z) * scale;
        const row = camera.rowAtDepth(z) * scale;
        if (col >= 0 && col < fullImg.width - 1 && row > yhFull + 1 && row < fullImg.height - 1) {
          [r, g, b] = bilinear(fullImg, col, row);
          cov = 1;
          // Fade coverage out near the photo's left/right edges so the wedge
          // blends into the procedural surround instead of ending on a seam.
          const edge = Math.min(col, fullImg.width - 1 - col) / (fullImg.width * 0.12);
          const bottomEdge = (fullImg.height - 1 - row) / (fullImg.height * 0.06);
          cov = clamp(Math.min(edge, 1), 0, 1) * clamp(Math.min(bottomEdge, 1), 0, 1);
        }
      }

      if (cov < 1) {
        // Procedural surround from the photo's ground palette.
        const n = noise(x * 0.035, z * 0.035) * 0.5 + noise(x * 0.14, z * 0.14) * 0.5;
        const tint = 0.82 + n * 0.36;
        const pr = groundStats.mean[0] * tint;
        const pg = groundStats.mean[1] * tint;
        const pb = groundStats.mean[2] * tint;
        if (cov <= 0) {
          r = pr;
          g = pg;
          b = pb;
        } else {
          r = r * cov + pr * (1 - cov);
          g = g * cov + pg * (1 - cov);
          b = b * cov + pb * (1 - cov);
        }
      }

      out.data[di] = r;
      out.data[di + 1] = g;
      out.data[di + 2] = b;
      out.data[di + 3] = 255;
      covered[i] = cov;
      lumaField[i] = luma(r, g, b);
    }
  }

  // World-space texture energy: bumpy ground (grass, rubble, crowd) rises
  // slightly, smooth ground (road, water, sand) stays flat.
  for (let ty = 1; ty < texRes - 1; ty++) {
    for (let tx = 1; tx < texRes - 1; tx++) {
      const i = ty * texRes + tx;
      const c = lumaField[i];
      texField[i] =
        (Math.abs(c - lumaField[i - 1]) +
          Math.abs(c - lumaField[i + 1]) +
          Math.abs(c - lumaField[i - texRes]) +
          Math.abs(c - lumaField[i + texRes])) /
        4;
    }
  }

  ctx.putImageData(out, 0, 0);

  const heights = buildHeights({
    texRes,
    gridRes,
    size,
    zMin,
    texField,
    covered,
    noise,
    amplitude: opts.amplitude ?? 0.9,
  });

  return {
    size,
    gridRes,
    zMin,
    heights,
    texture: canvas.toDataURL('image/jpeg', 0.9),
    palette: groundStats,
  };
}

function buildHeights({ texRes, gridRes, size, zMin, texField, covered, noise, amplitude }) {
  const smoothTex = smooth2d(texField, texRes, texRes, 6, 2);

  let maxTex = 1e-6;
  for (let i = 0; i < smoothTex.length; i++) if (smoothTex[i] > maxTex) maxTex = smoothTex[i];

  const heights = new Float32Array(gridRes * gridRes);
  for (let gy = 0; gy < gridRes; gy++) {
    const v = gy / (gridRes - 1);
    const z = zMin + v * size;
    for (let gx = 0; gx < gridRes; gx++) {
      const u = gx / (gridRes - 1);
      const x = (u - 0.5) * size;

      const tx = clamp(Math.round(u * (texRes - 1)), 0, texRes - 1);
      const ty = clamp(Math.round(v * (texRes - 1)), 0, texRes - 1);
      const ti = ty * texRes + tx;

      const rough = clamp(smoothTex[ti] / maxTex, 0, 1);
      const cov = covered[ti];

      // Inside the photo wedge: gentle relief tracking ground roughness.
      const photoRelief = rough * amplitude;

      // Outside it: rolling hills so the surround is not a dead plane.
      const hills =
        (noise(x * 0.012, z * 0.012) * 3.2 + noise(x * 0.045, z * 0.045) * 0.9) *
        clamp(1 - cov, 0, 1);

      // Flatten a disc around spawn so you never start inside a hillside.
      const dSpawn = Math.hypot(x, z);
      const spawnFlat = clamp((dSpawn - 4) / 10, 0, 1);

      heights[gy * gridRes + gx] = (photoRelief * cov + hills) * spawnFlat;
    }
  }

  // One final smoothing pass so collision never produces a stair-step.
  return smooth2d(heights, gridRes, gridRes, 1, 1);
}

function sampleGroundPalette(fullImg, camera, horizonPx, scale) {
  const yh = horizonPx * scale;
  const y0 = Math.min(fullImg.height - 1, Math.round(yh + (fullImg.height - yh) * 0.25));
  const list = [];
  const stepX = Math.max(1, Math.floor(fullImg.width / 60));
  const stepY = Math.max(1, Math.floor((fullImg.height - y0) / 40));
  let satAcc = 0;
  for (let y = y0; y < fullImg.height; y += stepY) {
    for (let x = 0; x < fullImg.width; x += stepX) {
      const i = (y * fullImg.width + x) * 4;
      const c = [fullImg.data[i], fullImg.data[i + 1], fullImg.data[i + 2]];
      list.push(c);
      satAcc += sat(c[0], c[1], c[2]);
    }
  }
  if (!list.length) return { mean: [90, 90, 90], saturation: 0 };
  let r = 0;
  let g = 0;
  let b = 0;
  for (const c of list) {
    r += c[0];
    g += c[1];
    b += c[2];
  }
  const n = list.length;
  return { mean: [r / n, g / n, b / n], saturation: satAcc / n };
}

function bilinear(img, fx, fy) {
  const x0 = Math.floor(fx);
  const y0 = Math.floor(fy);
  const x1 = Math.min(img.width - 1, x0 + 1);
  const y1 = Math.min(img.height - 1, y0 + 1);
  const ax = fx - x0;
  const ay = fy - y0;
  const g = (x, y, o) => img.data[(y * img.width + x) * 4 + o];
  const out = [0, 0, 0];
  for (let o = 0; o < 3; o++) {
    const top = g(x0, y0, o) * (1 - ax) + g(x1, y0, o) * ax;
    const bot = g(x0, y1, o) * (1 - ax) + g(x1, y1, o) * ax;
    out[o] = top * (1 - ay) + bot * ay;
  }
  return out;
}

/** Deterministic seed so the same photo always yields the same world. */
function hashImage(img) {
  let hash = 2166136261;
  const step = Math.max(4, Math.floor(img.data.length / 4096)) * 4;
  for (let i = 0; i < img.data.length; i += step) {
    hash ^= img.data[i];
    hash = Math.imul(hash, 16777619);
  }
  hash ^= img.width * 73856093;
  hash ^= img.height * 19349663;
  return hash >>> 0;
}

function makeValueNoise(seed) {
  const hash2 = (ix, iy) => {
    let h = seed ^ Math.imul(ix, 374761393) ^ Math.imul(iy, 668265263);
    h = Math.imul(h ^ (h >>> 13), 1274126177);
    return ((h ^ (h >>> 16)) >>> 0) / 4294967295;
  };
  return (x, y) => {
    const ix = Math.floor(x);
    const iy = Math.floor(y);
    const fx = x - ix;
    const fy = y - iy;
    const sx = fx * fx * (3 - 2 * fx);
    const sy = fy * fy * (3 - 2 * fy);
    const a = hash2(ix, iy);
    const b = hash2(ix + 1, iy);
    const c = hash2(ix, iy + 1);
    const d = hash2(ix + 1, iy + 1);
    return (a * (1 - sx) + b * sx) * (1 - sy) + (c * (1 - sx) + d * sx) * sy;
  };
}
