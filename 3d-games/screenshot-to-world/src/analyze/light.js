import { luma, sat, clamp, smooth2d } from './util.js';

/**
 * Infer the lighting rig from the photo.
 *
 * The brightest smooth blob in the sky is almost always the sun (or the
 * dominant light source when there is no sky). Its image position maps to a
 * direction through the same pinhole model used everywhere else, so the world's
 * sun ends up where the photo's sun was -- shadows and highlights on props then
 * agree with the baked-in lighting of the photo itself instead of fighting it.
 */
export function inferLight(img, camera, horizonPx, skyInfo) {
  const { width: w, height: h } = img;
  const d = img.data;

  const lum = new Float32Array(w * h);
  for (let i = 0; i < w * h; i++) {
    const c = i * 4;
    lum[i] = luma(d[c], d[c + 1], d[c + 2]);
  }
  // Blur hard so a single blown-out pixel does not become the sun.
  const blurred = smooth2d(lum, w, h, Math.max(2, Math.round(w * 0.02)), 2);

  // Prefer the sky band; fall back to the whole frame for interiors.
  const searchBottom = skyInfo.hasSky && horizonPx > 8 ? horizonPx : h;
  let bestI = -1;
  let bestV = -Infinity;
  for (let y = 0; y < searchBottom; y++) {
    for (let x = 0; x < w; x++) {
      const i = y * w + x;
      if (blurred[i] > bestV) {
        bestV = blurred[i];
        bestI = i;
      }
    }
  }

  const sunCol = bestI >= 0 ? bestI % w : w * 0.5;
  const sunRow = bestI >= 0 ? Math.floor(bestI / w) : Math.max(0, horizonPx - h * 0.3);

  // Direction through the pinhole, in analysis space (+z = into the photo).
  const dx = (sunCol - camera.cx) / camera.f;
  const dy = (horizonPx - sunRow) / camera.f; // up is positive
  const len = Math.hypot(dx, dy, 1);
  const dir = { x: dx / len, y: Math.max(0.08, dy / len), z: 1 / len };

  // Overall exposure and warmth drive ambient level and colour.
  let lAcc = 0;
  let sAcc = 0;
  let rAcc = 0;
  let gAcc = 0;
  let bAcc = 0;
  let n = 0;
  const step = Math.max(1, Math.floor(w / 64));
  for (let y = 0; y < h; y += step) {
    for (let x = 0; x < w; x += step) {
      const c = (y * w + x) * 4;
      lAcc += luma(d[c], d[c + 1], d[c + 2]);
      sAcc += sat(d[c], d[c + 1], d[c + 2]);
      rAcc += d[c];
      gAcc += d[c + 1];
      bAcc += d[c + 2];
      n++;
    }
  }
  const meanLuma = lAcc / Math.max(1, n);
  const meanSat = sAcc / Math.max(1, n);
  const meanRgb = [rAcc / n, gAcc / n, bAcc / n];

  // Sun colour: warm-shift the sky's brightest tone slightly.
  const sunColor = normalizeTint([
    clamp(meanRgb[0] * 1.12 + 24, 0, 255),
    clamp(meanRgb[1] * 1.04 + 16, 0, 255),
    clamp(meanRgb[2] * 0.96 + 10, 0, 255),
  ]);

  // Night / low-key photos get a dimmer key and a cooler, lifted ambient so the
  // world stays readable instead of going pitch black.
  const night = meanLuma < 0.22;

  return {
    direction: dir,
    sunColor,
    sunIntensity: night ? 0.55 : clamp(0.75 + meanLuma * 0.9, 0.6, 1.85),
    ambientColor: normalizeTint(
      night ? [90, 105, 140] : [meanRgb[0] * 0.7 + 40, meanRgb[1] * 0.7 + 44, meanRgb[2] * 0.7 + 54],
    ),
    ambientIntensity: night ? 0.75 : clamp(0.45 + (1 - meanSat) * 0.35, 0.4, 0.95),
    meanLuma,
    meanSat,
    night,
    sunImage: { col: sunCol / w, row: sunRow / h },
  };
}

function normalizeTint([r, g, b]) {
  return [clamp(Math.round(r), 0, 255), clamp(Math.round(g), 0, 255), clamp(Math.round(b), 0, 255)];
}
