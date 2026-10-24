import { toImageData, createCanvas, clamp, rgbToHex } from './util.js';
import { detectHorizon } from './horizon.js';
import { buildSkyModel, assessSky, traceSkyline } from './sky.js';
import { classify } from './archetype.js';
import { makeCamera } from './camera.js';
import { extractProps } from './props.js';
import { buildGround, WORLD_SIZE, Z_MIN } from './terrain.js';
import { inferLight } from './light.js';

const ANALYSIS_MAX = 512; // analysis runs small; textures come from the full image
const TEXTURE_MAX = 2048;

/**
 * photo -> WorldSpec.
 *
 * WorldSpec is deliberately plain JSON (plus a few data URLs). The renderer
 * only consumes it, and the optional AI pass only edits it, so a world can be
 * saved, reloaded, hand-tweaked or regenerated without re-running any of this.
 */
export async function buildWorld(imageEl, opts = {}) {
  const t0 = performance.now();

  const analysis = toImageData(imageEl, ANALYSIS_MAX);
  const full = toImageData(imageEl, TEXTURE_MAX);

  const horizon = detectHorizon(analysis);
  const skyModel = buildSkyModel(analysis, horizon.yPx);
  const skyInfo = assessSky(analysis, horizon.yPx, skyModel);
  const arch = classify(analysis, horizon, skyInfo);

  const camera = makeCamera(analysis.width, analysis.height, horizon.yPx, opts.camera);
  const light = inferLight(analysis, camera, horizon.yPx, skyInfo);

  const spec = {
    version: 1,
    createdWith: 'ScreenshotAiGameMaker',
    name: opts.name || 'Untitled world',
    archetype: arch.mode,
    walkable: arch.walkable,
    diagnostics: {
      horizon: {
        y: horizon.y,
        confidence: horizon.confidence,
        separability: horizon.separability,
        step: horizon.step,
      },
      sky: { hasSky: skyInfo.hasSky, openness: skyInfo.openness, meanTex: skyInfo.meanTex },
      archetype: arch,
      analysisMs: 0,
    },
    source: {
      width: full.width,
      height: full.height,
      aspect: full.width / full.height,
      photo: encodePhoto(imageEl, full),
    },
    camera: {
      fovDeg: camera.fovDeg,
      eyeHeight: camera.eyeHeight,
      horizonY: horizon.y,
    },
    light: {
      direction: light.direction,
      sunColor: rgbToHex(light.sunColor),
      sunIntensity: light.sunIntensity,
      ambientColor: rgbToHex(light.ambientColor),
      ambientIntensity: light.ambientIntensity,
      night: light.night,
    },
    sky: null,
    ground: null,
    props: [],
    fog: null,
  };

  if (arch.mode === 'open' || arch.mode === 'interior') {
    const enclosed = arch.mode === 'interior';

    spec.sky = {
      enabled: !enclosed,
      top: rgbToHex(skyInfo.top),
      bottom: rgbToHex(skyInfo.bottom),
      // The upper band of the photo wraps onto the dome so real clouds and
      // real sunset colour end up overhead, not a synthetic gradient.
      texture: enclosed ? null : encodeSkyBand(full, horizon.y),
      enclosedColor: enclosed ? rgbToHex(skyInfo.top) : null,
    };

    spec.ground = buildGround(full, camera, horizon.yPx, {
      size: enclosed ? WORLD_SIZE * 0.45 : WORLD_SIZE,
      zMin: enclosed ? Z_MIN * 0.45 : Z_MIN,
      amplitude: enclosed ? 0.12 : 0.9,
    });

    if (!enclosed && skyModel.valid) {
      const skyline = traceSkyline(analysis, skyModel, horizon.yPx);
      spec.props = extractProps(analysis, full, skyline, horizon.yPx, camera, {
        maxProps: opts.maxProps ?? 14,
        skyModel,
      });
      spec.diagnostics.skyline = Array.from(skyline.top);
    }

    const fogColor = enclosed ? skyInfo.top : skyInfo.bottom;
    spec.fog = {
      color: rgbToHex(fogColor),
      near: enclosed ? 6 : 24,
      far: enclosed ? WORLD_SIZE * 0.42 : WORLD_SIZE * 0.78,
    };
    spec.spawn = { x: 0, z: 0, yaw: 0 };
  } else {
    // Gallery void: no ground plane exists in the photo, so do not invent one.
    // The image stands as a lit monolith in a room made from its own palette.
    spec.gallery = buildGallery(full);
    spec.fog = {
      color: rgbToHex(spec.gallery.voidColor),
      near: 8,
      far: 90,
    };
    spec.spawn = { x: 0, z: 0, yaw: 0 };
  }

  spec.diagnostics.analysisMs = Math.round(performance.now() - t0);
  return spec;
}

/** Re-encode the source photo once, for the flat pose of the dive transition. */
function encodePhoto(imageEl, full) {
  const c = createCanvas(full.width, full.height);
  c.getContext('2d').drawImage(imageEl, 0, 0, full.width, full.height);
  return c.toDataURL('image/jpeg', 0.92);
}

/**
 * The sky band, stretched into a strip suitable for wrapping around a dome.
 * Mirrored horizontally so the seam behind the player does not butt-join.
 */
function encodeSkyBand(full, horizonY) {
  const hPx = clamp(Math.round(full.height * horizonY), 8, full.height - 1);
  const bandH = hPx;
  const c = createCanvas(full.width * 2, bandH);
  const ctx = c.getContext('2d');
  const tmp = createCanvas(full.width, bandH);
  const tctx = tmp.getContext('2d');
  const src = tctx.createImageData(full.width, bandH);
  for (let y = 0; y < bandH; y++) {
    for (let x = 0; x < full.width; x++) {
      const si = (y * full.width + x) * 4;
      const di = (y * full.width + x) * 4;
      src.data[di] = full.data[si];
      src.data[di + 1] = full.data[si + 1];
      src.data[di + 2] = full.data[si + 2];
      src.data[di + 3] = 255;
    }
  }
  tctx.putImageData(src, 0, 0);

  ctx.drawImage(tmp, 0, 0);
  ctx.save();
  ctx.translate(full.width * 2, 0);
  ctx.scale(-1, 1);
  ctx.drawImage(tmp, 0, 0);
  ctx.restore();

  return c.toDataURL('image/jpeg', 0.85);
}

function buildGallery(full) {
  // Average colour, pushed dark, becomes the void the image hangs in.
  let r = 0;
  let g = 0;
  let b = 0;
  let n = 0;
  const step = Math.max(4, Math.floor(full.width / 48)) * 4;
  for (let i = 0; i < full.data.length; i += step) {
    r += full.data[i];
    g += full.data[i + 1];
    b += full.data[i + 2];
    n++;
  }
  const mean = [r / n, g / n, b / n];
  const voidColor = mean.map((v) => clamp(v * 0.18 + 6, 0, 255));

  return {
    voidColor,
    monolith: {
      // Big enough to be architecture rather than a picture frame.
      height: 9,
      distance: 11,
    },
  };
}
