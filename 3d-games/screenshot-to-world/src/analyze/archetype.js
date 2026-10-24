import { textureEnergy, luma, sat, clamp } from './util.js';

/**
 * Photo archetypes.
 *
 * The build strategy has to change with what kind of photograph it is. A
 * landscape has a ground plane you can stand on; a portrait does not, and
 * pretending otherwise is exactly how these things end up as lumpy skin-toned
 * terrain. Detecting the difference lets the bad cases fail on purpose into
 * something deliberate.
 *
 *   open     - horizon + open sky. The full projection pipeline.
 *   interior - depth and structure but no sky. Enclosed, shorter fog, no dome.
 *   closeup  - a dominant near subject, no usable ground plane. Gallery void.
 *   abstract - no reliable geometry at all. Gallery void, looser styling.
 */
export function classify(img, horizon, skyInfo) {
  const { width: w, height: h } = img;
  const tex = textureEnergy(img);
  const d = img.data;

  // Centre-vs-edge contrast: a close subject dominates the middle of the frame
  // and the edges fall away (bokeh, vignette, plain backdrop).
  let cTex = 0;
  let cN = 0;
  let eTex = 0;
  let eN = 0;
  let cSat = 0;
  let eSat = 0;
  const x0 = Math.floor(w * 0.3);
  const x1 = Math.ceil(w * 0.7);
  const y0 = Math.floor(h * 0.25);
  const y1 = Math.ceil(h * 0.85);
  for (let y = 0; y < h; y += 2) {
    for (let x = 0; x < w; x += 2) {
      const i = y * w + x;
      const c = i * 4;
      const s = sat(d[c], d[c + 1], d[c + 2]);
      const inside = x >= x0 && x < x1 && y >= y0 && y < y1;
      if (inside) {
        cTex += tex[i];
        cSat += s;
        cN++;
      } else {
        eTex += tex[i];
        eSat += s;
        eN++;
      }
    }
  }
  const centerTex = cTex / Math.max(1, cN);
  const edgeTex = eTex / Math.max(1, eN);
  const centerSat = cSat / Math.max(1, cN);
  const edgeSat = eSat / Math.max(1, eN);

  const subjectDominance = clamp(
    (centerTex - edgeTex) / Math.max(0.02, centerTex + edgeTex) +
      (centerSat - edgeSat) * 0.6,
    -1,
    1,
  );

  // How much vertical structure survives above the horizon.
  const horizonStrong = horizon.confidence > 0.42;
  const horizonUsable = horizon.confidence > 0.24;

  let mode;
  const reasons = [];

  if (horizonStrong && skyInfo.hasSky) {
    mode = 'open';
    reasons.push(`confident horizon (${horizon.confidence.toFixed(2)}) with open sky`);
  } else if (horizonUsable && !skyInfo.hasSky && subjectDominance < 0.22) {
    mode = 'interior';
    reasons.push(`ground plane without sky (openness ${skyInfo.openness.toFixed(2)})`);
  } else if (subjectDominance > 0.18 || horizon.confidence < 0.16) {
    mode = 'closeup';
    reasons.push(
      `dominant near subject (${subjectDominance.toFixed(2)}), horizon confidence ${horizon.confidence.toFixed(2)}`,
    );
  } else {
    mode = 'abstract';
    reasons.push('no reliable ground plane or subject structure');
  }

  // A photo with a usable horizon but a weak sky still walks better as an open
  // world than as a void; only fall through to closeup when the subject really
  // dominates.
  if (mode === 'closeup' && horizonStrong && subjectDominance < 0.3) {
    mode = 'open';
    reasons.push('overridden to open: horizon is strong enough to stand on');
  }

  return {
    mode,
    reasons,
    metrics: {
      subjectDominance,
      centerTex,
      edgeTex,
      horizonConfidence: horizon.confidence,
      skyOpenness: skyInfo.openness ?? 0,
    },
    walkable: mode === 'open' || mode === 'interior',
  };
}
