import { px, rgbDist2, clamp, textureEnergy, luma } from './util.js';

/**
 * Sky colour model: one colour PER ROW, tracked down the gradient.
 *
 * A single global colour model cannot represent a sky, because a sky is a
 * gradient. Train on the top of the image and the pale haze near the horizon
 * falls outside tolerance and is classified as a solid object -- which turns
 * the entire lower half of the sky into one enormous prop. Train on the whole
 * band instead and buildings get learned as sky, so nothing breaks the skyline
 * at all. Both failures were observed; neither is fixable by tuning tolerance.
 *
 * So the model walks down the image one row at a time, re-estimating the sky
 * colour from those pixels in each row that are close to the row above. The
 * estimate drifts smoothly with the gradient, while a building entering the
 * frame is a per-row outlier that never captures the estimate. Where a row is
 * mostly occluded (a ridge spanning the frame), the previous row's colour is
 * held rather than re-fitted.
 */
export function buildSkyModel(img, horizonPx) {
  const { width: w } = img;
  const bottom = Math.max(2, horizonPx);

  // Seed from the median of the first few rows.
  const seedRows = Math.min(bottom, Math.max(2, Math.round(img.height * 0.02)));
  let cur = medianColorOfRows(img, 0, seedRows);

  const rowColor = new Float32Array(bottom * 3);
  const trackTol2 = 34 * 34; // row-to-row sky change is small; a building is not
  const residuals = [];

  // Every second column is plenty to estimate a row's dominant colour, and
  // halves the cost of the whole pass.
  const stepX = Math.max(1, Math.floor(w / 256));
  const sampled = Math.ceil(w / stepX);

  for (let y = 0; y < bottom; y++) {
    const matched = [];
    for (let x = 0; x < w; x += stepX) {
      const c = px(img, x, y);
      if (rgbDist2(c, cur) < trackTol2) matched.push(c);
    }
    if (matched.length > sampled * 0.15) {
      const m = medianColor(matched);
      // Ease toward the new estimate so a single noisy row cannot yank it.
      cur = [cur[0] * 0.4 + m[0] * 0.6, cur[1] * 0.4 + m[1] * 0.6, cur[2] * 0.4 + m[2] * 0.6];
      for (let i = 0; i < matched.length; i += 8) {
        residuals.push(Math.sqrt(rgbDist2(matched[i], cur)));
      }
    }
    rowColor[y * 3] = cur[0];
    rowColor[y * 3 + 1] = cur[1];
    rowColor[y * 3 + 2] = cur[2];
  }

  residuals.sort((a, b) => a - b);
  const spread = residuals.length ? residuals[Math.floor(residuals.length * 0.5)] : 6;
  const tol = Math.max(20, Math.min(72, spread * 2.6 + 16));
  const tol2 = tol * tol;

  return {
    valid: true,
    rowColor,
    bottom,
    spread,
    tol,
    colorAtRow(y) {
      const yy = Math.max(0, Math.min(bottom - 1, y | 0));
      return [rowColor[yy * 3], rowColor[yy * 3 + 1], rowColor[yy * 3 + 2]];
    },
    isSkyAt(rgb, y) {
      if (y >= bottom) return false;
      return rgbDist2(rgb, this.colorAtRow(y)) < tol2;
    },
  };
}

function medianColor(list) {
  const out = [0, 0, 0];
  for (let o = 0; o < 3; o++) {
    const vals = list.map((c) => c[o]).sort((a, b) => a - b);
    out[o] = vals[Math.floor(vals.length / 2)];
  }
  return out;
}

function medianColorOfRows(img, y0, y1) {
  const { width: w } = img;
  const list = [];
  const step = Math.max(1, Math.floor(w / 128));
  for (let y = y0; y < y1; y++) for (let x = 0; x < w; x += step) list.push(px(img, x, y));
  return list.length ? medianColor(list) : [140, 170, 210];
}

/** Retained for reference: the global k-means model this replaced. */
export function buildGlobalSkyModel(img, horizonPx) {
  const { width: w, height: h } = img;
  // Train on the TOP band, not on a fraction of the distance to the horizon.
  // Once the horizon is refined down to the base of a skyline, "70% of the way
  // to the horizon" reaches deep into the buildings, and the model cheerfully
  // learns brickwork as sky -- after which nothing breaks the skyline and no
  // props are ever extracted.
  const trainBottom = Math.max(2, Math.min(horizonPx, Math.round(h * 0.15)));

  const samples = [];
  const stepX = Math.max(1, Math.floor(w / 96));
  const stepY = Math.max(1, Math.floor(trainBottom / 48));
  for (let y = 0; y < trainBottom; y += stepY) {
    for (let x = 0; x < w; x += stepX) samples.push(px(img, x, y));
  }
  if (samples.length < 8) {
    return { centers: [], spread: 0, valid: false, isSky: () => false };
  }

  // Seed centres from the top, middle and bottom of the training slice so the
  // vertical gradient is represented from the start.
  const bandOf = (frac) => {
    const yTarget = Math.floor(trainBottom * frac);
    const row = [];
    for (let x = 0; x < w; x += stepX) row.push(px(img, x, clamp(yTarget, 0, trainBottom - 1)));
    return meanRgb(row);
  };
  let centers = [bandOf(0.05), bandOf(0.5), bandOf(0.95)];

  for (let iter = 0; iter < 8; iter++) {
    const buckets = centers.map(() => []);
    for (const s of samples) {
      let bi = 0;
      let bd = Infinity;
      for (let i = 0; i < centers.length; i++) {
        const dd = rgbDist2(s, centers[i]);
        if (dd < bd) {
          bd = dd;
          bi = i;
        }
      }
      buckets[bi].push(s);
    }
    const next = centers.map((c, i) => (buckets[i].length ? meanRgb(buckets[i]) : c));
    let moved = 0;
    for (let i = 0; i < centers.length; i++) moved += rgbDist2(next[i], centers[i]);
    centers = next;
    if (moved < 1) break;
  }

  // Drop an outlier centre: if one cluster is both small and far from the
  // others it is an object intruding into the top band (an overhead branch, a
  // tower, a bird), not a shade of sky. Keeping it would make that object
  // transparent to the skyline trace.
  {
    const counts = centers.map(() => 0);
    for (const s of samples) counts[nearestIndex(s, centers)]++;
    const keep = centers.filter((c, i) => {
      if (centers.length < 3) return true;
      const share = counts[i] / samples.length;
      if (share > 0.12) return true;
      const others = centers.filter((_, j) => j !== i);
      const nearest = Math.sqrt(Math.min(...others.map((o) => rgbDist2(c, o))));
      return nearest < 70;
    });
    if (keep.length) centers = keep;
  }

  // Spread sets the classification tolerance, so a hazy/noisy sky gets a looser
  // threshold than a clean gradient one. Uses the MEDIAN distance rather than
  // RMS: stars, birds and sensor noise are outliers, and an RMS spread lets a
  // few of them inflate the tolerance until buildings start counting as sky.
  const dists = samples.map((s) => Math.sqrt(nearestDist2(s, centers))).sort((a, b) => a - b);
  const spread = dists[Math.floor(dists.length / 2)] || 0;

  const tol = Math.max(28, Math.min(78, spread * 2.6 + 14));
  const tol2 = tol * tol;

  return {
    centers,
    spread,
    tol,
    valid: true,
    isSky: (rgb) => nearestDist2(rgb, centers) < tol2,
  };
}

/**
 * Decide whether the region above the horizon is genuinely open sky, as opposed
 * to a ceiling, a wall, or a canopy. Drives archetype selection.
 *
 * This deliberately does NOT ask "does the top of the image match the sky model
 * trained on the top of the image" -- that question is circular and always
 * answers yes, which is how a beige ceiling gets classified as open sky. The
 * test here is absolute: sky has to actually look like sky.
 *
 * Judged on the TOP BAND rather than everything above the horizon, because with
 * a city skyline the above-horizon region is mostly buildings, and buildings
 * should not count as evidence against the sky behind them.
 */
export function assessSky(img, horizonPx, model) {
  const { width: w, height: h } = img;
  if (horizonPx < 4) {
    return {
      hasSky: false,
      openness: 0,
      score: 0,
      chroma: 0,
      top: [140, 170, 210],
      bottom: [200, 215, 230],
    };
  }

  const bandBottom = Math.max(3, Math.min(horizonPx, Math.round(h * 0.18)));
  const tex = textureEnergy(img);

  let texAcc = 0;
  let rAcc = 0;
  let gAcc = 0;
  let bAcc = 0;
  let lumaAcc = 0;
  let satAcc = 0;
  let n = 0;
  const stepX = Math.max(1, Math.floor(w / 96));
  for (let y = 0; y < bandBottom; y++) {
    for (let x = 0; x < w; x += stepX) {
      const c = px(img, x, y);
      texAcc += tex[y * w + x];
      rAcc += c[0];
      gAcc += c[1];
      bAcc += c[2];
      lumaAcc += luma(c[0], c[1], c[2]);
      const mx = Math.max(c[0], c[1], c[2]);
      const mn = Math.min(c[0], c[1], c[2]);
      satAcc += mx === 0 ? 0 : (mx - mn) / mx;
      n++;
    }
  }
  const meanTex = texAcc / Math.max(1, n);
  const meanR = rAcc / Math.max(1, n);
  const meanG = gAcc / Math.max(1, n);
  const meanB = bAcc / Math.max(1, n);
  const meanLuma = lumaAcc / Math.max(1, n);
  const meanSat = satAcc / Math.max(1, n);

  const smoothness = clamp(1 - meanTex / 0.06, 0, 1);

  // Vertical luminance gradient across the band, which sunsets and hazy skies
  // have strongly and flat paint does not.
  const topL = rowLuma(img, 0, Math.max(1, Math.round(bandBottom * 0.25)));
  const botL = rowLuma(img, Math.round(bandBottom * 0.75), bandBottom);
  const gradient = Math.abs(topL - botL);

  // Four ways to be sky. A photo only has to satisfy one.
  const blueCue = clamp((meanB - meanR) / 40, 0, 1); // daylight
  const overcastCue = clamp((meanLuma - 0.55) / 0.25, 0, 1) * clamp(1 - meanSat / 0.25, 0, 1);
  const sunsetCue = clamp(gradient / 0.25, 0, 1) * clamp(meanSat / 0.35, 0, 1);
  const darkCue = clamp((0.18 - meanLuma) / 0.12, 0, 1) * smoothness; // night
  const chroma = Math.max(blueCue, overcastCue, sunsetCue, darkCue);

  const edges = longEdgeScore(img, 0, bandBottom);

  const score = 0.34 * smoothness + 0.46 * chroma + 0.2 * (1 - edges);

  // Chroma is a gate, not just a term: something that is neither blue, nor
  // bright-and-desaturated, nor a warm gradient, nor near-black is not sky --
  // no matter how smooth and edge-free it is.
  const hasSky = chroma > 0.35 && score > 0.5;

  // Diagnostic only: fraction of the whole above-horizon region the row model
  // calls sky. Low means heavy occlusion (a skyline), not absence of sky.
  let skyCount = 0;
  let mTotal = 0;
  if (model.valid) {
    for (let y = 0; y < horizonPx; y++) {
      for (let x = 0; x < w; x += stepX) {
        if (model.isSkyAt(px(img, x, y), y)) skyCount++;
        mTotal++;
      }
    }
  }

  return {
    hasSky,
    score,
    chroma,
    smoothness,
    edges,
    cues: { blueCue, overcastCue, sunsetCue, darkCue },
    openness: mTotal ? skyCount / mTotal : 0,
    meanTex,
    top: bandMean(img, 0, Math.max(1, Math.round(bandBottom * 0.6))),
    bottom: bandMean(img, Math.round(horizonPx * 0.8), Math.max(2, horizonPx)),
  };
}

/**
 * Detect long straight edges: the signature of built interiors (wall/ceiling
 * joins, window and door frames) and never of open sky.
 */
function longEdgeScore(img, y0, y1) {
  const { width: w, data: d } = img;
  const bandH = y1 - y0;
  if (bandH < 6) return 0;
  const thr = 0.06;

  let hLines = 0;
  for (let y = y0 + 1; y < y1 - 1; y++) {
    let run = 0;
    let best = 0;
    for (let x = 0; x < w; x++) {
      const a = ((y - 1) * w + x) * 4;
      const b = ((y + 1) * w + x) * 4;
      const g = Math.abs(luma(d[a], d[a + 1], d[a + 2]) - luma(d[b], d[b + 1], d[b + 2]));
      if (g > thr) {
        run++;
        if (run > best) best = run;
      } else run = 0;
    }
    if (best > w * 0.5) hLines++;
  }

  let vLines = 0;
  for (let x = 1; x < w - 1; x++) {
    let run = 0;
    let best = 0;
    for (let y = y0; y < y1; y++) {
      const a = (y * w + x - 1) * 4;
      const b = (y * w + x + 1) * 4;
      const g = Math.abs(luma(d[a], d[a + 1], d[a + 2]) - luma(d[b], d[b + 1], d[b + 2]));
      if (g > thr) {
        run++;
        if (run > best) best = run;
      } else run = 0;
    }
    if (best > bandH * 0.5) vLines++;
  }

  return clamp((hLines / bandH) * 8 + (vLines / w) * 4, 0, 1);
}

function rowLuma(img, y0, y1) {
  const { width: w } = img;
  let acc = 0;
  let n = 0;
  const step = Math.max(1, Math.floor(w / 64));
  for (let y = Math.max(0, y0); y < Math.min(img.height, y1); y++) {
    for (let x = 0; x < w; x += step) {
      const c = px(img, x, y);
      acc += luma(c[0], c[1], c[2]);
      n++;
    }
  }
  return n ? acc / n : 0;
}

/**
 * Per-column skyline: the topmost row where sky stops and something solid
 * starts. Requires a run of consecutive non-sky rows so that birds, wires and
 * compression noise do not each become a mountain.
 */
/**
 * Per-column skyline.
 *   top[x]  - topmost row where sky stops and something solid starts
 *   base[x] - lowest solid row above the horizon, or -1 for an all-sky column
 *
 * `base` is what tells a tree from a cloud. Both have sky underneath most of
 * their width -- a canopy only touches the ground along the trunk -- so the
 * "does it reach the ground" test has to be asked of the whole object, not of
 * each column. Asking per column deletes every tree in the picture.
 */
export function traceSkyline(img, model, horizonPx) {
  const { width: w } = img;
  const need = Math.max(2, Math.round(horizonPx * 0.02));
  const top = new Int32Array(w);
  const base = new Int32Array(w);

  for (let x = 0; x < w; x++) {
    let found = horizonPx;
    let run = 0;
    for (let y = 0; y < horizonPx; y++) {
      if (model.isSkyAt(px(img, x, y), y)) {
        run = 0;
      } else {
        run++;
        if (run >= need) {
          found = y - run + 1;
          break;
        }
      }
    }
    top[x] = found;

    let lowest = -1;
    for (let y = horizonPx - 1; y >= found; y--) {
      if (!model.isSkyAt(px(img, x, y), y)) {
        lowest = y;
        break;
      }
    }
    base[x] = lowest;
  }

  return { top, base };
}

function meanRgb(list) {
  let r = 0;
  let g = 0;
  let b = 0;
  for (const c of list) {
    r += c[0];
    g += c[1];
    b += c[2];
  }
  const n = Math.max(1, list.length);
  return [r / n, g / n, b / n];
}

function nearestDist2(rgb, centers) {
  let bd = Infinity;
  for (const c of centers) {
    const dd = rgbDist2(rgb, c);
    if (dd < bd) bd = dd;
  }
  return bd;
}

function nearestIndex(rgb, centers) {
  let bi = 0;
  let bd = Infinity;
  for (let i = 0; i < centers.length; i++) {
    const dd = rgbDist2(rgb, centers[i]);
    if (dd < bd) {
      bd = dd;
      bi = i;
    }
  }
  return bi;
}

function bandMean(img, y0, y1) {
  const { width: w } = img;
  const list = [];
  const stepX = Math.max(1, Math.floor(w / 64));
  for (let y = Math.max(0, y0); y < Math.min(img.height, y1); y++) {
    for (let x = 0; x < w; x += stepX) list.push(px(img, x, y));
  }
  return list.length ? meanRgb(list) : [160, 180, 210];
}

export { meanRgb, luma };
