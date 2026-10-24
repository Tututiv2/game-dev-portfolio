/**
 * Small pixel/statistics helpers shared by the analysis passes.
 * Everything here is plain arrays + ImageData so it runs identically in the
 * Electron renderer and in a headless browser under puppeteer.
 */

/** Draw an image (or canvas) into an offscreen canvas and pull its pixels. */
export function toImageData(source, maxDim = 0) {
  const sw = source.naturalWidth || source.width;
  const sh = source.naturalHeight || source.height;
  let w = sw;
  let h = sh;
  if (maxDim && Math.max(sw, sh) > maxDim) {
    const k = maxDim / Math.max(sw, sh);
    w = Math.max(1, Math.round(sw * k));
    h = Math.max(1, Math.round(sh * k));
  }
  const c = document.createElement('canvas');
  c.width = w;
  c.height = h;
  const ctx = c.getContext('2d', { willReadFrequently: true });
  ctx.drawImage(source, 0, 0, w, h);
  return ctx.getImageData(0, 0, w, h);
}

export function createCanvas(w, h) {
  const c = document.createElement('canvas');
  c.width = w;
  c.height = h;
  return c;
}

/** sRGB luminance, 0..1. */
export function luma(r, g, b) {
  return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
}

/** HSV-ish saturation, 0..1. Cheap (no full conversion). */
export function sat(r, g, b) {
  const mx = Math.max(r, g, b);
  const mn = Math.min(r, g, b);
  return mx === 0 ? 0 : (mx - mn) / mx;
}

/**
 * "Blueness" in the sense that matters for sky detection: blue clearly ahead of
 * red, which is true for daylight sky and false for most ground cover.
 * Returns roughly -1..1.
 */
export function blueness(r, g, b) {
  return (b - r) / 255;
}

export function px(img, x, y) {
  const i = (y * img.width + x) * 4;
  const d = img.data;
  return [d[i], d[i + 1], d[i + 2]];
}

/** Mean + variance of an array. */
export function stats(arr) {
  const n = arr.length;
  if (!n) return { mean: 0, variance: 0, sd: 0 };
  let s = 0;
  for (let i = 0; i < n; i++) s += arr[i];
  const mean = s / n;
  let v = 0;
  for (let i = 0; i < n; i++) {
    const d = arr[i] - mean;
    v += d * d;
  }
  const variance = v / n;
  return { mean, variance, sd: Math.sqrt(variance) };
}

/** In-place-safe 1D box blur, repeated to approximate a gaussian. */
export function smooth1d(arr, radius = 2, passes = 2) {
  let cur = Array.from(arr);
  const n = cur.length;
  if (radius < 1 || n === 0) return cur;
  for (let p = 0; p < passes; p++) {
    const out = new Array(n);
    let acc = 0;
    for (let i = -radius; i <= radius; i++) acc += cur[clamp(i, 0, n - 1)];
    const win = radius * 2 + 1;
    for (let i = 0; i < n; i++) {
      out[i] = acc / win;
      acc -= cur[clamp(i - radius, 0, n - 1)];
      acc += cur[clamp(i + radius + 1, 0, n - 1)];
    }
    cur = out;
  }
  return cur;
}

/** Separable box blur over a 2D Float array (w*h), returns a new array. */
export function smooth2d(arr, w, h, radius = 1, passes = 1) {
  let cur = Float32Array.from(arr);
  if (radius < 1) return cur;
  const win = radius * 2 + 1;
  for (let p = 0; p < passes; p++) {
    const tmp = new Float32Array(w * h);
    // horizontal
    for (let y = 0; y < h; y++) {
      const row = y * w;
      let acc = 0;
      for (let i = -radius; i <= radius; i++) acc += cur[row + clamp(i, 0, w - 1)];
      for (let x = 0; x < w; x++) {
        tmp[row + x] = acc / win;
        acc -= cur[row + clamp(x - radius, 0, w - 1)];
        acc += cur[row + clamp(x + radius + 1, 0, w - 1)];
      }
    }
    const out = new Float32Array(w * h);
    // vertical
    for (let x = 0; x < w; x++) {
      let acc = 0;
      for (let i = -radius; i <= radius; i++) acc += tmp[clamp(i, 0, h - 1) * w + x];
      for (let y = 0; y < h; y++) {
        out[y * w + x] = acc / win;
        acc -= tmp[clamp(y - radius, 0, h - 1) * w + x];
        acc += tmp[clamp(y + radius + 1, 0, h - 1) * w + x];
      }
    }
    cur = out;
  }
  return cur;
}

export function clamp(v, lo, hi) {
  return v < lo ? lo : v > hi ? hi : v;
}

export function lerp(a, b, t) {
  return a + (b - a) * t;
}

export function smoothstep(t) {
  const x = clamp(t, 0, 1);
  return x * x * (3 - 2 * x);
}

/** Squared distance in RGB. Cheap stand-in for perceptual distance. */
export function rgbDist2(a, b) {
  const dr = a[0] - b[0];
  const dg = a[1] - b[1];
  const db = a[2] - b[2];
  return dr * dr + dg * dg + db * db;
}

export function rgbToHex([r, g, b]) {
  return (
    '#' +
    [r, g, b].map((v) => clamp(Math.round(v), 0, 255).toString(16).padStart(2, '0')).join('')
  );
}

/**
 * Per-pixel local texture energy: mean abs difference from the 4-neighbourhood.
 * High for foliage/gravel/crowds, low for sky, water, walls, smooth roads.
 * Returns Float32Array(w*h) roughly 0..1.
 */
export function textureEnergy(img) {
  const { width: w, height: h, data: d } = img;
  const out = new Float32Array(w * h);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const i = y * w + x;
      const c = i * 4;
      const l = luma(d[c], d[c + 1], d[c + 2]);
      let acc = 0;
      let n = 0;
      if (x > 0) {
        const o = (i - 1) * 4;
        acc += Math.abs(l - luma(d[o], d[o + 1], d[o + 2]));
        n++;
      }
      if (x < w - 1) {
        const o = (i + 1) * 4;
        acc += Math.abs(l - luma(d[o], d[o + 1], d[o + 2]));
        n++;
      }
      if (y > 0) {
        const o = (i - w) * 4;
        acc += Math.abs(l - luma(d[o], d[o + 1], d[o + 2]));
        n++;
      }
      if (y < h - 1) {
        const o = (i + w) * 4;
        acc += Math.abs(l - luma(d[o], d[o + 1], d[o + 2]));
        n++;
      }
      out[i] = n ? acc / n : 0;
    }
  }
  return out;
}
