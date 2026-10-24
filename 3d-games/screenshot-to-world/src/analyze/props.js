import { px, clamp, createCanvas, smooth1d, luma } from './util.js';

/**
 * Silhouette props.
 *
 * This is the pass that sells the illusion. A flat photo projected onto a
 * ground plane always reads as a painted floor; what makes you believe you are
 * standing somewhere is stuff that stands up around you and slides past at the
 * right rate as you walk. So anything that breaks the skyline -- a tree, a
 * tower, a ridge, a person -- is cut out of the photo with alpha and re-placed
 * in the world at the distance its base implies.
 *
 * Each prop keeps both poses:
 *   flat  - the rect it occupies in the original photo (normalized)
 *   world - where it stands in 3D
 * The dive transition lerps between them, which is why at t=0 the scene
 * reassembles into the untouched photograph.
 */

const MIN_PROP_COLS = 6; // ignore slivers: wires, noise, single-pixel spikes
const GAP_MERGE = 8; // columns of sky allowed inside one object
const MIN_PROMINENCE = 0.02; // fraction of image height above the horizon

export function extractProps(img, fullImg, skyline, horizonPx, camera, opts = {}) {
  const { width: w, height: h } = img;
  const maxProps = opts.maxProps ?? 14;
  const top = skyline.top;
  const base = skyline.base;

  // Prominence: how far each column's skyline rises above the horizon.
  const prom = new Array(w);
  for (let x = 0; x < w; x++) prom[x] = Math.max(0, horizonPx - top[x]);

  const smoothProm = smooth1d(prom, 2, 1);
  const threshold = Math.max(3, h * MIN_PROMINENCE);

  // Group contiguous prominent columns into candidate objects.
  const rawRuns = [];
  let start = -1;
  let gap = 0;
  for (let x = 0; x < w; x++) {
    const on = smoothProm[x] >= threshold;
    if (on) {
      if (start < 0) start = x;
      gap = 0;
    } else if (start >= 0) {
      gap++;
      if (gap > GAP_MERGE) {
        rawRuns.push([start, x - gap]);
        start = -1;
        gap = 0;
      }
    }
  }
  if (start >= 0) rawRuns.push([start, w - 1]);

  // A contiguous run is not one object. A distant ridge line spans the entire
  // frame unbroken, so grouping by contiguity alone yields a single full-width
  // billboard that swallows every tree standing on it -- which is a backdrop,
  // not props, and kills the parallax that makes the world feel inhabited.
  // Split each run recursively at its deepest skyline valley instead.
  const runs = [];
  for (const run of rawRuns) splitAtValleys(run[0], run[1], top, horizonPx, runs, 0);

  const candidates = [];
  const reachBand = h * 0.06;

  for (const [x0, x1] of runs) {
    if (x1 - x0 + 1 < MIN_PROP_COLS) continue;

    let topRow = horizonPx;
    for (let x = x0; x <= x1; x++) topRow = Math.min(topRow, top[x]);

    // Does this object reach the ground anywhere along its width? A tree only
    // touches down at the trunk and a skyline only between the buildings, so
    // one column reaching is enough. A cloud, moon or bird never reaches, and
    // must not be planted in the terrain as a standing prop.
    let lowest = -1;
    for (let x = x0; x <= x1; x++) if (base[x] > lowest) lowest = base[x];
    if (lowest < horizonPx - reachBand) continue;

    // Reject light sources. Anything silhouetted against the sky is backlit and
    // therefore DARKER than the sky behind it; the sun, the moon and a blown-out
    // lamp are brighter. Without this the sun gets planted on the beach as a
    // standing stone, because it happens to sit near the horizon.
    if (opts.skyModel && isLightSource(img, x0, x1, top, base, opts.skyModel)) continue;

    // The base sits slightly below the horizon: an object touching the ground
    // at exactly the horizon is infinitely far away, which is never what a
    // photo actually shows. Push it to a finite, plausible standing distance.
    const baseRow = horizonPx + Math.max(2, h * 0.012);

    const geo = camera.standingObject(baseRow, topRow, x0, x1);
    if (!geo) continue;

    const area = (x1 - x0) * (horizonPx - topRow);
    candidates.push({ x0, x1, topRow, baseRow, geo, area });
  }

  // Keep the visually dominant ones; a skyline of 200 twigs helps nobody.
  candidates.sort((a, b) => b.area - a.area);
  const chosen = candidates.slice(0, maxProps).sort((a, b) => a.x0 - b.x0);

  const scale = fullImg.width / w; // analysis res -> full res for the cutout
  const props = [];

  chosen.forEach((c, i) => {
    const pad = 2;
    const sx0 = clamp(Math.floor((c.x0 - pad) * scale), 0, fullImg.width - 1);
    const sx1 = clamp(Math.ceil((c.x1 + 1 + pad) * scale), 1, fullImg.width);
    const sy0 = clamp(Math.floor((c.topRow - pad) * scale), 0, fullImg.height - 1);
    const sy1 = clamp(Math.ceil(c.baseRow * scale), 1, fullImg.height);
    if (sx1 - sx0 < 2 || sy1 - sy0 < 2) return;

    const texture = cutout(fullImg, sx0, sy0, sx1, sy1, top, scale);
    if (!texture) return;

    props.push({
      id: `prop_${i}`,
      kind: 'silhouette',
      flat: {
        x: sx0 / fullImg.width,
        y: sy0 / fullImg.height,
        w: (sx1 - sx0) / fullImg.width,
        h: (sy1 - sy0) / fullImg.height,
      },
      world: {
        x: c.geo.x,
        z: c.geo.z,
        width: c.geo.width,
        height: c.geo.height,
        baseY: 0,
      },
      texture,
    });
  });

  return props;
}

/**
 * Is this candidate emitting light rather than blocking it? Compares the
 * object's own brightness against the sky the model expects at the same rows.
 * Requires it to be near blown-out as well as brighter, so a pale building
 * against a grey sky is not mistaken for the sun.
 */
function isLightSource(img, x0, x1, top, base, model) {
  let objAcc = 0;
  let skyAcc = 0;
  let n = 0;
  const step = Math.max(1, Math.floor((x1 - x0 + 1) / 24));
  for (let x = x0; x <= x1; x += step) {
    const y0 = top[x];
    const y1 = base[x] < 0 ? y0 : base[x];
    const yStep = Math.max(1, Math.floor((y1 - y0 + 1) / 12));
    for (let y = y0; y <= y1; y += yStep) {
      const c = px(img, x, y);
      objAcc += luma(c[0], c[1], c[2]);
      const s = model.colorAtRow(y);
      skyAcc += luma(s[0], s[1], s[2]);
      n++;
    }
  }
  if (!n) return false;
  const objL = objAcc / n;
  const skyL = skyAcc / n;
  return objL > skyL * 1.08 && objL > 0.78;
}

/**
 * Recursively split a skyline run at its deepest valley.
 *
 * Two objects are separated when the skyline between them descends a good
 * fraction of the way back down to the horizon. A tree standing on a ridge
 * clears that bar; two adjacent towers of a single building do not, and stay
 * one object -- which is the right answer for both.
 */
function splitAtValleys(x0, x1, top, horizonPx, out, depth) {
  const width = x1 - x0 + 1;
  if (width < MIN_PROP_COLS * 2 || depth > 6) {
    if (width >= MIN_PROP_COLS) out.push([x0, x1]);
    return;
  }

  let topRow = horizonPx;
  for (let x = x0; x <= x1; x++) topRow = Math.min(topRow, top[x]);

  // Look for the lowest point of the skyline, ignoring the run's own edges so
  // an object is never split off its own boundary.
  const margin = Math.max(2, Math.floor(MIN_PROP_COLS / 2));
  let valleyX = -1;
  let valleyY = -1;
  for (let x = x0 + margin; x <= x1 - margin; x++) {
    if (top[x] > valleyY) {
      valleyY = top[x];
      valleyX = x;
    }
  }

  const splitDepth = 0.35 * (horizonPx - topRow);
  if (valleyX < 0 || valleyY - topRow < splitDepth) {
    out.push([x0, x1]);
    return;
  }

  splitAtValleys(x0, valleyX, top, horizonPx, out, depth + 1);
  splitAtValleys(valleyX + 1, x1, top, horizonPx, out, depth + 1);
}

/**
 * Cut the object out of the full-resolution photo, making sky transparent above
 * its own skyline so the silhouette edge is the real one, not a rectangle.
 */
function cutout(fullImg, sx0, sy0, sx1, sy1, skylineTop, scale) {
  const w = sx1 - sx0;
  const h = sy1 - sy0;
  const canvas = createCanvas(w, h);
  const ctx = canvas.getContext('2d');
  const out = ctx.createImageData(w, h);

  for (let y = 0; y < h; y++) {
    const srcY = sy0 + y;
    for (let x = 0; x < w; x++) {
      const srcX = sx0 + x;
      const si = (srcY * fullImg.width + srcX) * 4;
      const di = (y * w + x) * 4;

      // Everything above this column's own skyline is sky -> transparent.
      const analysisCol = clamp(Math.floor(srcX / scale), 0, skylineTop.length - 1);
      const colTop = skylineTop[analysisCol] * scale;
      const above = srcY < colTop;

      out.data[di] = fullImg.data[si];
      out.data[di + 1] = fullImg.data[si + 1];
      out.data[di + 2] = fullImg.data[si + 2];

      if (above) {
        out.data[di + 3] = 0;
      } else {
        // Soften the top edge by a couple of pixels so cutouts do not alias
        // into hard jagged staircases against the sky dome.
        const feather = clamp((srcY - colTop) / 3, 0, 1);
        out.data[di + 3] = Math.round(255 * feather);
      }
    }
  }

  ctx.putImageData(out, 0, 0);

  // Reject cutouts that ended up almost entirely transparent.
  let opaque = 0;
  for (let i = 3; i < out.data.length; i += 4) if (out.data[i] > 128) opaque++;
  if (opaque < w * h * 0.05) return null;

  return canvas.toDataURL('image/png');
}
