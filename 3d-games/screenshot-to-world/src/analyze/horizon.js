import { luma, blueness, smooth1d, textureEnergy, clamp } from './util.js';

/**
 * Horizon detection, in two passes.
 *
 * PASS 1 reduces the image to a per-row "skyness" signal and splits it with
 * Otsu's between-class variance. Skyness combines brightness, smoothness and
 * blueness -- three cues that agree on sky and disagree on nearly everything
 * else. This alone nails open horizons (fields, sea, desert).
 *
 * PASS 2 fixes the case pass 1 always gets wrong. Against a city skyline, the
 * strongest brightness/smoothness step is at the ROOFTOPS, not the street, so
 * Otsu confidently reports a horizon halfway up the buildings. But buildings
 * and trees are full of vertical edges and roads are not, so scanning down from
 * the pass-1 split for the row where vertical-edge density collapses finds
 * where the standing structure actually meets the ground.
 *
 * The refinement can only ever push the horizon DOWN, and only fires when there
 * is real structure to explain, so open-horizon photos pass through untouched.
 */
export function detectHorizon(img) {
  const { width: w, height: h } = img;
  const tex = textureEnergy(img);
  const d = img.data;

  const bright = new Array(h).fill(0);
  const rough = new Array(h).fill(0);
  const blue = new Array(h).fill(0);

  for (let y = 0; y < h; y++) {
    let bAcc = 0;
    let tAcc = 0;
    let blAcc = 0;
    for (let x = 0; x < w; x++) {
      const i = y * w + x;
      const c = i * 4;
      bAcc += luma(d[c], d[c + 1], d[c + 2]);
      tAcc += tex[i];
      blAcc += blueness(d[c], d[c + 1], d[c + 2]);
    }
    bright[y] = bAcc / w;
    rough[y] = tAcc / w;
    blue[y] = blAcc / w;
  }

  const r1 = Math.max(1, Math.round(h * 0.01));
  const sB = smooth1d(bright, r1, 2);
  const sT = smooth1d(rough, r1, 2);
  const sBl = smooth1d(blue, r1, 2);

  const texMax = Math.max(1e-6, ...sT);
  const skyness = new Array(h);
  for (let y = 0; y < h; y++) {
    const smoothness = 1 - clamp(sT[y] / texMax, 0, 1);
    const blueBoost = clamp(sBl[y] * 3, 0, 1);
    skyness[y] = 0.42 * sB[y] + 0.4 * smoothness + 0.18 * blueBoost;
  }

  const coarse = otsuSplit(skyness, h);
  const vEdge = verticalEdgeDensity(img);
  const refined = refineToStructureBase(vEdge, coarse.y, h);

  const y = refined.y;

  // Confidence is measured AT the final horizon, not at the coarse split, so a
  // skyline that needed refining is not penalised for the split it started at.
  const band = Math.max(2, Math.round(h * 0.03));
  const above = avgRange(skyness, y - band, y);
  const below = avgRange(skyness, y + 1, y + 1 + band);
  const step = clamp((above - below) * 2.5, 0, 1);

  // A brightness step is the right evidence for a sky/ground horizon, but the
  // wrong evidence for a building/road one -- at night both sides are dark and
  // the step reads zero even when the line is exactly right. Structure density
  // collapsing is the evidence in that case, so take whichever cue fired.
  const edgeAbove = avgRange(vEdge, y - band, y);
  const edgeBelow = avgRange(vEdge, y + 1, y + 1 + band);
  const edgeStep = clamp((edgeAbove - edgeBelow) * 6, 0, 1);

  const confidence = clamp(0.35 * coarse.separability + 0.65 * Math.max(step, edgeStep), 0, 1);

  return {
    y: y / h,
    yPx: y,
    confidence,
    separability: coarse.separability,
    step,
    edgeStep,
    refined: refined.applied,
    coarseY: coarse.y / h,
    profile: skyness,
    edgeProfile: vEdge,
  };
}

/** Otsu split over the skyness profile; sky must be the upper band. */
function otsuSplit(skyness, h) {
  const lo = Math.floor(h * 0.06);
  const hi = Math.ceil(h * 0.94);
  const total = skyness.reduce((a, b) => a + b, 0);

  let best = { y: Math.round(h * 0.45), score: -Infinity };
  let acc = 0;
  for (let k = 0; k < lo; k++) acc += skyness[k];

  for (let k = lo; k < hi; k++) {
    acc += skyness[k];
    const n0 = k + 1;
    const n1 = h - n0;
    if (n1 <= 0) break;
    const mu0 = acc / n0;
    const mu1 = (total - acc) / n1;
    if (mu0 <= mu1) continue;
    const score = (n0 / h) * (n1 / h) * (mu0 - mu1) * (mu0 - mu1);
    if (score > best.score) best = { y: k, score };
  }

  const mean = total / h;
  let varTotal = 0;
  for (let y = 0; y < h; y++) {
    const dv = skyness[y] - mean;
    varTotal += dv * dv;
  }
  varTotal /= h;

  return {
    y: best.y,
    separability: varTotal > 1e-9 ? clamp(best.score / varTotal, 0, 1) : 0,
  };
}

/**
 * Per-row density of vertical edges. Buildings, trees, poles and people are
 * full of them; roads, grass, sand and water are not. Vertical edges show up as
 * large HORIZONTAL luminance gradients, hence the x-difference.
 */
function verticalEdgeDensity(img) {
  const { width: w, height: h, data: d } = img;
  const thr = 0.09;
  const out = new Array(h).fill(0);
  for (let y = 0; y < h; y++) {
    let n = 0;
    for (let x = 1; x < w - 1; x++) {
      const a = (y * w + x - 1) * 4;
      const b = (y * w + x + 1) * 4;
      const g = Math.abs(luma(d[a], d[a + 1], d[a + 2]) - luma(d[b], d[b + 1], d[b + 2]));
      if (g > thr) n++;
    }
    out[y] = n / Math.max(1, w - 2);
  }
  return smooth1d(out, Math.max(1, Math.round(h * 0.012)), 2);
}

/**
 * Push the horizon down to the base of whatever is standing above it.
 *
 * Only fires when the band below the coarse split really is full of vertical
 * structure, and stops at the first sustained collapse in edge density -- the
 * ground line. Bounded so a pathological image cannot drag the horizon to the
 * bottom of the frame.
 */
function refineToStructureBase(vEdge, coarseY, h) {
  const maxPush = Math.round(h * 0.42);
  const end = Math.min(h - 2, coarseY + maxPush);
  if (end <= coarseY + 2) return { y: coarseY, applied: false };

  let peak = 0;
  for (let y = coarseY; y <= end; y++) peak = Math.max(peak, vEdge[y]);

  // No meaningful structure below the split: this is already an open horizon.
  if (peak < 0.055) return { y: coarseY, applied: false };

  const floorLevel = Math.max(0.02, peak * 0.3);
  const need = Math.max(3, Math.round(h * 0.025)); // sustained, not a gap between buildings

  let run = 0;
  for (let y = coarseY; y <= end; y++) {
    if (vEdge[y] < floorLevel) {
      run++;
      if (run >= need) return { y: y - run + 1, applied: y - run + 1 > coarseY + 1 };
    } else {
      run = 0;
    }
  }
  return { y: coarseY, applied: false };
}

function avgRange(arr, a, b) {
  const lo = Math.max(0, Math.min(a, arr.length));
  const hi = Math.max(0, Math.min(b, arr.length));
  if (hi <= lo) return 0;
  let s = 0;
  for (let i = lo; i < hi; i++) s += arr[i];
  return s / (hi - lo);
}
