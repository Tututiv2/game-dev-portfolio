import { SCENES, renderScene } from '../tests/scenes.js';
import { toImageData, clamp } from './analyze/util.js';
import { detectHorizon } from './analyze/horizon.js';
import { buildSkyModel, assessSky, traceSkyline } from './analyze/sky.js';
import { classify } from './analyze/archetype.js';
import { makeCamera } from './analyze/camera.js';
import { buildWorld } from './analyze/world.js';

/**
 * Renders every analysis pass as a picture so failures are visible rather than
 * inferred. Truth horizons come from the synthetic scenes, so the horizon pass
 * gets a real pass/fail rather than a vibe check.
 */

const out = document.getElementById('out');

for (const scene of SCENES) {
  const canvas = renderScene(scene);
  const el = document.createElement('div');
  el.className = 'scene';
  el.innerHTML = `<h2>${scene.label}</h2><div class="row"></div>`;
  const row = el.querySelector('.row');
  out.appendChild(el);

  const analysis = toImageData(canvas, 512);
  const horizon = detectHorizon(analysis);
  const skyModel = buildSkyModel(analysis, horizon.yPx);
  const skyInfo = assessSky(analysis, horizon.yPx, skyModel);
  const arch = classify(analysis, horizon, skyInfo);
  const camera = makeCamera(analysis.width, analysis.height, horizon.yPx);
  const skyline = skyModel.valid ? traceSkyline(analysis, skyModel, horizon.yPx) : null;

  row.appendChild(panel('source + overlay', overlayCanvas(canvas, analysis, horizon, skyline, scene)));
  row.appendChild(panel('sky mask', maskCanvas(analysis, skyModel, horizon)));

  // Full pipeline, so ground/props/heights get exercised too.
  const img = new Image();
  img.src = canvas.toDataURL('image/png');
  await img.decode();
  const spec = await buildWorld(img, { name: scene.label });

  if (spec.ground) {
    row.appendChild(panel('ground orthophoto', dataUrlCanvas(spec.ground.texture, 260)));
    row.appendChild(panel('heightfield', heightCanvas(spec.ground)));
  }
  if (spec.props?.length) {
    row.appendChild(panel(`props (${spec.props.length})`, propsStrip(spec.props)));
  }

  row.appendChild(metaPanel(scene, horizon, skyInfo, arch, spec));
}

document.body.dataset.ready = '1';

// --- panels ----------------------------------------------------------------

function panel(label, node) {
  const d = document.createElement('div');
  d.className = 'panel';
  const s = document.createElement('span');
  s.textContent = label;
  d.append(s, node);
  return d;
}

function overlayCanvas(src, analysis, horizon, skyline, scene) {
  const w = 340;
  const h = Math.round((src.height / src.width) * w);
  const c = document.createElement('canvas');
  c.width = w;
  c.height = h;
  const ctx = c.getContext('2d');
  ctx.drawImage(src, 0, 0, w, h);

  // Detected horizon
  ctx.strokeStyle = '#ff4d4d';
  ctx.lineWidth = 2;
  ctx.beginPath();
  ctx.moveTo(0, horizon.y * h);
  ctx.lineTo(w, horizon.y * h);
  ctx.stroke();

  // Ground truth
  if (scene.truthHorizon != null) {
    ctx.strokeStyle = '#5cff9a';
    ctx.setLineDash([6, 5]);
    ctx.beginPath();
    ctx.moveTo(0, scene.truthHorizon * h);
    ctx.lineTo(w, scene.truthHorizon * h);
    ctx.stroke();
    ctx.setLineDash([]);
  }

  // Skyline trace: top in cyan, ground-contact points in magenta
  if (skyline) {
    const { top, base } = skyline;
    ctx.strokeStyle = '#4dd8ff';
    ctx.lineWidth = 1.4;
    ctx.beginPath();
    for (let x = 0; x < top.length; x++) {
      const sx = (x / top.length) * w;
      const sy = (top[x] / analysis.height) * h;
      if (x === 0) ctx.moveTo(sx, sy);
      else ctx.lineTo(sx, sy);
    }
    ctx.stroke();

    ctx.fillStyle = '#ff5cf0';
    for (let x = 0; x < base.length; x += 3) {
      if (base[x] < 0) continue;
      ctx.fillRect((x / base.length) * w, (base[x] / analysis.height) * h, 1.5, 1.5);
    }
  }
  return c;
}

function maskCanvas(analysis, model, horizon) {
  const c = document.createElement('canvas');
  const w = 200;
  const h = Math.round((analysis.height / analysis.width) * w);
  c.width = w;
  c.height = h;
  const ctx = c.getContext('2d');
  const img = ctx.createImageData(w, h);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const ax = Math.floor((x / w) * analysis.width);
      const ay = Math.floor((y / h) * analysis.height);
      const si = (ay * analysis.width + ax) * 4;
      const rgb = [analysis.data[si], analysis.data[si + 1], analysis.data[si + 2]];
      const isSky = model.valid && ay < horizon.yPx && model.isSkyAt(rgb, ay);
      const di = (y * w + x) * 4;
      img.data[di] = isSky ? 60 : rgb[0] * 0.55;
      img.data[di + 1] = isSky ? 190 : rgb[1] * 0.55;
      img.data[di + 2] = isSky ? 255 : rgb[2] * 0.55;
      img.data[di + 3] = 255;
    }
  }
  ctx.putImageData(img, 0, 0);
  return c;
}

function dataUrlCanvas(url, size) {
  const c = document.createElement('canvas');
  c.width = size;
  c.height = size;
  const ctx = c.getContext('2d');
  const im = new Image();
  im.onload = () => ctx.drawImage(im, 0, 0, size, size);
  im.src = url;
  return c;
}

function heightCanvas(ground) {
  const n = ground.gridRes;
  const c = document.createElement('canvas');
  c.width = 260;
  c.height = 260;
  const ctx = c.getContext('2d');
  const img = ctx.createImageData(260, 260);

  let mn = Infinity;
  let mx = -Infinity;
  for (const v of ground.heights) {
    if (v < mn) mn = v;
    if (v > mx) mx = v;
  }
  const range = Math.max(1e-6, mx - mn);

  for (let y = 0; y < 260; y++) {
    for (let x = 0; x < 260; x++) {
      const gx = Math.floor((x / 260) * n);
      const gy = Math.floor((y / 260) * n);
      const v = (ground.heights[gy * n + gx] - mn) / range;
      const di = (y * 260 + x) * 4;
      img.data[di] = clamp(v * 255, 0, 255);
      img.data[di + 1] = clamp(v * 210 + 20, 0, 255);
      img.data[di + 2] = clamp(120 - v * 90, 0, 255);
      img.data[di + 3] = 255;
    }
  }
  ctx.putImageData(img, 0, 0);
  ctx.fillStyle = '#fff';
  ctx.font = '11px monospace';
  ctx.fillText(`${mn.toFixed(2)} .. ${mx.toFixed(2)} m`, 6, 251);
  return c;
}

function propsStrip(props) {
  const c = document.createElement('canvas');
  c.width = 300;
  c.height = 140;
  const ctx = c.getContext('2d');
  ctx.fillStyle = '#141a24';
  ctx.fillRect(0, 0, 300, 140);
  const slot = 300 / Math.min(props.length, 8);
  props.slice(0, 8).forEach((p, i) => {
    const im = new Image();
    im.onload = () => {
      const s = Math.min(slot / im.width, 110 / im.height);
      ctx.drawImage(im, i * slot + 2, 118 - im.height * s, im.width * s, im.height * s);
      ctx.fillStyle = '#8fa0b8';
      ctx.font = '9px monospace';
      ctx.fillText(`${p.world.z.toFixed(0)}m`, i * slot + 4, 132);
    };
    im.src = p.texture;
  });
  return c;
}

function metaPanel(scene, horizon, skyInfo, arch, spec) {
  const d = document.createElement('div');
  d.className = 'panel meta';
  const err = scene.truthHorizon != null ? Math.abs(horizon.y - scene.truthHorizon) : null;
  const cls = err == null ? '' : err < 0.03 ? 'ok' : err < 0.07 ? 'warn' : 'bad';

  d.innerHTML = `
    <span>diagnostics</span>
    <dl>
      <dt>archetype</dt><dd>${spec.archetype}</dd>
      <dt>horizon</dt><dd>${horizon.y.toFixed(3)}${
        scene.truthHorizon != null
          ? ` <span class="${cls}">(truth ${scene.truthHorizon.toFixed(3)}, err ${err.toFixed(3)})</span>`
          : ''
      }</dd>
      <dt>refined</dt><dd>${horizon.refined ? `yes, from ${horizon.coarseY.toFixed(3)}` : 'no'}</dd>
      <dt>confidence</dt><dd>${horizon.confidence.toFixed(3)} (sep ${horizon.separability.toFixed(2)}, step ${horizon.step.toFixed(2)})</dd>
      <dt>has sky</dt><dd class="${skyInfo.hasSky ? 'ok' : 'warn'}">${skyInfo.hasSky} (score ${skyInfo.score.toFixed(2)}, chroma ${skyInfo.chroma.toFixed(2)}, edges ${skyInfo.edges.toFixed(2)})</dd>
      <dt>sky cues</dt><dd>${Object.entries(skyInfo.cues).map(([k, v]) => `${k.replace('Cue', '')} ${v.toFixed(2)}`).join(', ')}</dd>
      <dt>subject dom</dt><dd>${arch.metrics.subjectDominance.toFixed(3)}</dd>
      <dt>props</dt><dd>${spec.props?.length ?? 0}</dd>
      <dt>light</dt><dd>${spec.light.sunColor} @ ${spec.light.sunIntensity.toFixed(2)}${spec.light.night ? ' (night)' : ''}</dd>
      <dt>sun dir</dt><dd>${fmtDir(spec.light.direction)}</dd>
      <dt>analysis</dt><dd>${spec.diagnostics.analysisMs} ms</dd>
    </dl>
    <div class="reasons">${arch.reasons.join('<br>')}</div>
  `;
  return d;
}

function fmtDir(v) {
  return `${v.x.toFixed(2)}, ${v.y.toFixed(2)}, ${v.z.toFixed(2)}`;
}
