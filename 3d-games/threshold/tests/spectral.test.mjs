// SPECTRAL TESTS — BUILD_PROMPT §8 checks 1, 2, 3 and 6b.
//
// These render the REAL audio graph through an OfflineAudioContext and analyse the samples that
// come out. Nothing here inspects a parameter: every assertion is about sound that was actually
// produced. That is the difference between "the filter is set to 700 Hz" and "the treble is gone".
//
// Check 3 is the one that matters. A baked crossfade and a portal model both get quieter indoors;
// only one of them can put the noise AT the window. The test for that is to render the same moment
// twice, facing opposite ways, and ask whether the stereo image flipped — which a crossfade of two
// fixed stems physically cannot do, and which is asserted below for both backends.

import { group, check, gt, lt } from './harness.mjs';
import { Grid } from '../src/world/grid.js';
import { VolumeGraph } from '../src/world/volumes.js';
import { buildCabin, PROBES } from '../src/world/cabin.js';
import { computeAcoustics, makeListener } from '../src/audio/model.js';
import { SmoothedAcoustics } from '../src/audio/smooth.js';
import { PortalBackend } from '../src/audio/backendPortal.js';
import { BakedBackend, renderStems } from '../src/audio/backendBaked.js';
import { loadBuffers } from '../src/audio/samples.js';
import { bandEnergy, centroid, toDb, rms } from './fft.mjs';

const SR = 44100;
const SECONDS = 1.6;
const SKIP = Math.floor(SR * 0.45); // let the parameter ramps settle before analysing

function scene() {
  const grid = new Grid();
  const { openings } = buildCabin(grid);
  const graph = new VolumeGraph(grid);
  return { grid, graph, openings };
}

/**
 * Render one moment: a listener, a world state, a backend. Returns the stereo samples.
 */
// Decode ONCE and share. These are the buffers the game itself plays — the suite must not quietly
// test the synth while the game plays recordings, or every number below stops describing the thing
// the owner is listening to. AudioBuffers are transferable between contexts, and every context here
// runs at the same rate, so one decode serves the whole suite.
let SHARED = null;
export async function sharedBuffers() {
  if (!SHARED) {
    const OAC = globalThis.OfflineAudioContext;
    SHARED = await loadBuffers(new OAC(2, 128, SR));
  }
  return SHARED;
}

async function render({ s, pos, yaw = 0, weather = { rain: 1, wind: 0.7 }, backend = 'portal', thunderStrike = null }) {
  const OAC = globalThis.OfflineAudioContext;
  const ctx = new OAC(2, Math.ceil(SR * SECONDS), SR);

  const buffers = await sharedBuffers();

  let be;
  if (backend === 'baked') {
    const stems = await renderStems(SR, buffers);
    be = new BakedBackend(ctx, ctx.destination, buffers, stems);
  } else {
    be = new PortalBackend(ctx, ctx.destination, buffers);
  }
  be.start();

  const pose = makeListener(pos, yaw, 0);
  const frame = computeAcoustics({ grid: s.grid, graph: s.graph, listener: pose, weather });
  const smooth = new SmoothedAcoustics();
  smooth.prime(frame);
  be.apply(smooth, pose, weather);

  const l = ctx.listener;
  if (l.positionX) {
    l.positionX.value = pose.pos[0]; l.positionY.value = pose.pos[1]; l.positionZ.value = pose.pos[2];
    l.forwardX.value = pose.forward[0]; l.forwardY.value = pose.forward[1]; l.forwardZ.value = pose.forward[2];
    l.upX.value = 0; l.upY.value = 1; l.upZ.value = 0;
  } else {
    l.setPosition(pose.pos[0], pose.pos[1], pose.pos[2]);
    l.setOrientation(pose.forward[0], pose.forward[1], pose.forward[2], 0, 1, 0);
  }

  if (thunderStrike) be.thunder(thunderStrike, pose);

  const buf = await ctx.startRendering();
  return {
    L: buf.getChannelData(0).subarray(SKIP),
    R: buf.getChannelData(1).subarray(SKIP),
    mono: (() => {
      const a = buf.getChannelData(0), b = buf.getChannelData(1);
      const m = new Float32Array(a.length - SKIP);
      for (let i = 0; i < m.length; i++) m[i] = (a[i + SKIP] + b[i + SKIP]) * 0.5;
      return m;
    })(),
  };
}

const shutAll = s => { for (const o of s.grid.openingList) { o.openness = 0; o.target = 0; } };

export async function run() {
  const s = scene();

  group('spectral — the buffers under test are the ones the game plays');
  const bufs = await sharedBuffers();
  // Without this the whole suite silently downgrades to testing the synth the moment a .wav 404s,
  // and every measurement below would describe something the owner never hears.
  check('the recorded weather loaded (not the synth fallback)', bufs.source === 'recorded');
  if (bufs.source !== 'recorded') console.warn('fallback reason:', bufs.reason);
  check('rain is a real stereo buffer', bufs.rain.numberOfChannels === 2 && bufs.rain.duration > 2);
  check('roof is a real stereo buffer', bufs.roof.numberOfChannels === 2 && bufs.roof.duration > 2);

  // ------------------------------------------------- checks 1 & 2: outside vs inside
  group('spectral — the storm loses its treble through a wall (checks 1 & 2)');

  shutAll(s);
  const out = await render({ s, pos: PROBES.outside });
  const inn = await render({ s, pos: PROBES.mainRoom });

  const outHi = bandEnergy(out.mono, SR, 4000, 16000);
  const innHi = bandEnergy(inn.mono, SR, 4000, 16000);
  const outLo = bandEnergy(out.mono, SR, 60, 400);
  const innLo = bandEnergy(inn.mono, SR, 60, 400);

  gt('outdoor rain really does have substantial energy above 4 kHz',
    toDb(outHi) - toDb(bandEnergy(out.mono, SR, 60, 16000)), -14,
    `${toDb(outHi).toFixed(1)} dB of ${toDb(bandEnergy(out.mono, SR, 60, 16000)).toFixed(1)} dB total`);

  gt('indoors, above 4 kHz is more than 20 dB down', toDb(outHi) - toDb(innHi), 20,
    `outdoors ${toDb(outHi).toFixed(1)} dB, indoors ${toDb(innHi).toFixed(1)} dB`);

  // The bass survives far better than the treble. That IS what "muffled" means, and it is the
  // mass law doing its job rather than a volume knob being turned down.
  const hiDrop = toDb(outHi) - toDb(innHi);
  const loDrop = toDb(outLo) - toDb(innLo);
  gt('the treble is cut much harder than the bass — this is a filter, not a fader',
    hiDrop - loDrop, 12,
    `treble down ${hiDrop.toFixed(1)} dB, bass down ${loDrop.toFixed(1)} dB`);

  lt('and the whole thing is darker', centroid(inn.mono, SR), centroid(out.mono, SR) / 3,
    `${centroid(out.mono, SR).toFixed(0)} Hz outdoors, ${centroid(inn.mono, SR).toFixed(0)} Hz indoors`);

  // --------------------------------------------------------- check 3: cracking a window
  group('spectral — cracking the window (check 3)');

  shutAll(s);
  const shut = await render({ s, pos: PROBES.mainRoom });
  s.openings.windowWest.openness = 0.1;
  const crack = await render({ s, pos: PROBES.mainRoom });

  const shutBand = bandEnergy(shut.mono, SR, 2000, 8000);
  const crackBand = bandEnergy(crack.mono, SR, 2000, 8000);
  gt('a 10% crack lifts the 2–8 kHz band by more than 6 dB',
    toDb(crackBand) - toDb(shutBand), 6,
    `${toDb(shutBand).toFixed(1)} → ${toDb(crackBand).toFixed(1)} dB`);

  // --------------------------------- check 3, the part a crossfade cannot fake
  group('spectral — the noise is AT the window, and this is the proof');

  // Same instant, same window, listener turned 180 degrees. If the sound has a location, the
  // stereo image must reverse. If it is two stems on a fader, it cannot.
  const imbalance = r =>
    toDb(bandEnergy(r.L, SR, 2000, 8000)) - toDb(bandEnergy(r.R, SR, 2000, 8000));

  s.openings.windowWest.openness = 0.45;
  const north = await render({ s, pos: PROBES.mainRoom, yaw: 0 });
  const south = await render({ s, pos: PROBES.mainRoom, yaw: Math.PI });
  const iN = imbalance(north), iS = imbalance(south);

  gt('facing one way the window is clearly on one side', Math.abs(iN), 2.5,
    `${iN.toFixed(2)} dB of left/right difference`);
  lt('turn around and it swaps to the other ear', iN * iS, 0,
    `north ${iN.toFixed(2)} dB, south ${iS.toFixed(2)} dB — the sign must flip`);
  gt('...and the swing is a real one', Math.abs(iN - iS), 5,
    `${Math.abs(iN - iS).toFixed(2)} dB of swing between facings`);

  // ...and the side the model CLAIMS must be the side the panner actually renders into.
  //
  // This is the check that was missing, and its absence cost a shipped bug. The model's own pan
  // was computed from a right vector built the wrong way round; every test that used it compared
  // it against itself, so the sign was free to be wrong and stay wrong. A sign convention can only
  // be verified against something outside the thing that defines it — here, the ear the audio
  // lands in. It also transitively pins the movement axes, since player.js now takes them from the
  // same makeListener, which is how A and D got swapped on screen while all of this passed.
  const modelPan = yaw => computeAcoustics({
    grid: s.grid, graph: s.graph,
    listener: makeListener(PROBES.mainRoom, yaw, 0),
    weather: { rain: 1, wind: 0.7 },
  }).apertures.find(a => a.name === 'west window').pan;

  // imbalance is L-minus-R, so a source on the RIGHT (pan > 0) must give a NEGATIVE imbalance.
  lt('the side the model reports is the side you actually hear it in, facing north',
    modelPan(0) * iN, 0,
    `model pan ${modelPan(0).toFixed(2)}, rendered L-R ${iN.toFixed(2)} dB`);
  lt('...and facing south', modelPan(Math.PI) * iS, 0,
    `model pan ${modelPan(Math.PI).toFixed(2)}, rendered L-R ${iS.toFixed(2)} dB`);

  const bakedN = await render({ s, pos: PROBES.mainRoom, yaw: 0, backend: 'baked' });
  const bakedS = await render({ s, pos: PROBES.mainRoom, yaw: Math.PI, backend: 'baked' });
  const bN = imbalance(bakedN), bS = imbalance(bakedS);
  lt('the baked backend cannot do this — turning your head changes nothing',
    Math.abs(bN - bS), 1.0,
    `baked swing ${Math.abs(bN - bS).toFixed(2)} dB vs portal ${Math.abs(iN - iS).toFixed(2)} dB`);

  // ------------------------------------------------------------ the roof, in the rendered audio
  group('spectral — the sound you only get indoors (check 4)');

  shutAll(s);
  const inside = await render({ s, pos: PROBES.mainRoom });
  const outside = await render({ s, pos: PROBES.outside });

  // The drumming lives in the low-mids, and indoors it should be most of what is left.
  //
  // This used to be asserted as a >6 dB SHIFT in that band's share between outdoors and indoors,
  // which worked against synthetic rain and became unreachable against a recording. The share is a
  // fraction of the total, so it cannot exceed 0 dB; the largest shift the metric can ever report
  // is therefore however far below 0 the OUTDOOR share sits. Real rain has genuine energy between
  // 120 and 900 Hz where the synth bed had almost none, which put outdoors at -5.8 dB and capped
  // the whole metric at 5.8. It read as a regression and was a ceiling.
  //
  // So: assert the thing actually claimed, in a form nothing bounds. Indoors that band must BE the
  // mix, and it must be a real rise on outdoors.
  const share = r => toDb(bandEnergy(r.mono, SR, 120, 900)) - toDb(bandEnergy(r.mono, SR, 60, 16000));
  gt('indoors, the low-mid drumming IS the mix', share(inside), -1.5,
    `${share(inside).toFixed(1)} dB of the total is 120-900 Hz`);
  gt('...and that is a real change from outdoors', share(inside) - share(outside), 4,
    `${share(outside).toFixed(1)} dB outdoors, ${share(inside).toFixed(1)} dB indoors`);

  // The unbounded version of the same claim: how far the mix TILTS toward the low-mids. Outdoors
  // the treble is right there; indoors the wall has taken it and the roof has replaced it.
  const tilt = r => toDb(bandEnergy(r.mono, SR, 120, 900)) - toDb(bandEnergy(r.mono, SR, 2000, 16000));
  gt('going inside tilts the whole balance toward the drumming', tilt(inside) - tilt(outside), 15,
    `${tilt(outside).toFixed(1)} dB outdoors, ${tilt(inside).toFixed(1)} dB indoors`);

  // -------------------------------------------------------- check 6b: thunder and distance
  group('spectral — near thunder cracks, far thunder rumbles (check 6b)');

  const { Strike } = await import('../src/weather/thunder.js');
  const near = await render({
    s, pos: PROBES.outside, weather: { rain: 0, wind: 0 },
    thunderStrike: new Strike(200, 0, () => 0.5),
  });
  const far = await render({
    s, pos: PROBES.outside, weather: { rain: 0, wind: 0 },
    thunderStrike: new Strike(3000, 0, () => 0.5),
  });

  gt('the strike is audible at all', rms(near.mono), 0.002,
    `near rms ${rms(near.mono).toExponential(2)}, far rms ${rms(far.mono).toExponential(2)}`);

  const cNear = centroid(near.mono, SR, 40, 16000);
  const cFar = centroid(far.mono, SR, 40, 16000);
  gt('the same strike is much brighter close up', cNear, cFar * 2.2,
    `${cNear.toFixed(0)} Hz at 200 m, ${cFar.toFixed(0)} Hz at 3 km`);
  gt('and much louder',
    toDb(bandEnergy(near.mono, SR, 40, 16000)) - toDb(bandEnergy(far.mono, SR, 40, 16000)), 8);

  // ------------------------------------------------------------------ node hygiene
  group('spectral — no leaks');

  // Web Audio nodes are not collected while connected. A storm left running with emitters leaking
  // would strangle the tab, so the backend must actually take its graph down.
  const OAC = globalThis.OfflineAudioContext;
  const ctx = new OAC(2, SR, SR);
  const be = new PortalBackend(ctx, ctx.destination, await sharedBuffers());
  be.start();
  const pose = makeListener(PROBES.mainRoom, 0, 0);
  s.openings.windowWest.openness = 0.5;
  s.openings.door.openness = 1;
  const sm = new SmoothedAcoustics();
  sm.prime(computeAcoustics({ grid: s.grid, graph: s.graph, listener: pose, weather: { rain: 1, wind: 1 } }));
  // Baseline AFTER one apply: the first frame legitimately builds a chain per opening, and
  // counting that as growth would be measuring construction, not leakage.
  be.apply(sm, pose, { rain: 1, wind: 1 });
  const before = be.describe().nodes;
  for (let i = 0; i < 400; i++) be.apply(sm, pose, { rain: 1, wind: 1 });
  const after = be.describe().nodes;
  check('four hundred frames do not grow the graph by a single node', before === after,
    `${before} → ${after}`);
  gt('...and there were portals to begin with', be.describe().portals, 2);
  be.stop();
  check('stopping takes everything down', be.describe().nodes === 0);
}
