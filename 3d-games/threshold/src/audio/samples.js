// Where the weather comes from.
//
// The project began with no audio files at all and synthesised everything, which is genuinely the
// right call for weather in most respects — deterministic from a seed, continuously scalable with
// intensity, nothing to ship. It survived two rounds of measured improvement and a human still said
// it sounded wrong, so outdoor rain, roof drumming and thunder are now recordings. Wind is not: it
// was never the complaint, and recorded wind is mostly microphone rumble.
//
// The synth is NOT dead code. It is the fallback here, it is what `tests/synth.test.mjs` covers,
// and it is what the game plays if these files are missing — which matters, because a missing asset
// should degrade the sound and not break the boot.
//
// One property survives the switch intact and it is the load-bearing one: there is still exactly
// ONE rain buffer, feeding the diffuse bed and every portal at once. That is what makes the sound
// through the wall and the sound at the open window the same rain, rather than two unrelated
// rainstorms playing either side of a pane.

import {
  makeRainBuffer, makeRoofBuffer, makeWindBuffer, makeThunderBuffer,
} from './synth.js';

// Resolved against this module rather than against the page, so the test harness at /?test and the
// game at / both find them.
const url = name => new URL(`../../audio/${name}.wav`, import.meta.url).href;

// Thunder is NOT in here, and that is a decision rather than an omission. A recorded strike was
// prepared and rejected: it measured a 404 Hz spectral centroid with 4 kHz already 39 dB down,
// because a real recording has the air absorption of ITS OWN distance baked in. Feeding that to the
// distance model means filtering it a second time for a distance it has already paid - the same
// double-counting that made thunder inaudible before - and it collapsed the near-versus-far
// contrast that BUILD_PROMPT 3.5 is built on, from 2092/291 Hz down to 232/144 Hz. You cannot put
// back treble a microphone never captured. The synth strike stays because it is the only version
// that can be BOTH a crack and a rumble, which is the claim the feature makes.
const RECORDED = ['rain', 'roof'];

async function fetchBuffer(ctx, name) {
  const res = await fetch(url(name));
  if (!res.ok) throw new Error(`audio/${name}.wav → HTTP ${res.status}`);
  const bytes = await res.arrayBuffer();
  // decodeAudioData is callback-style on old Safari; the promise form is fine everywhere we run.
  return await ctx.decodeAudioData(bytes);
}

function synthesised(ctx) {
  return { rain: makeRainBuffer(ctx), roof: makeRoofBuffer(ctx) };
}

/**
 * @param {BaseAudioContext} ctx  a live AudioContext or an OfflineAudioContext
 * @param {{prefer?: 'recorded'|'synth'}} [opts]
 * @returns {Promise<{rain, roof, wind, thunder, source: 'recorded'|'synth', reason?: string}>}
 * `source` describes the RAIN and ROOF beds only; wind and thunder are always synthesised.
 */
export async function loadBuffers(ctx, opts = {}) {
  // Wind and thunder stay procedural either way.
  const wind = makeWindBuffer(ctx);
  const thunder = makeThunderBuffer(ctx);

  if (opts.prefer === 'synth' || typeof fetch !== 'function') {
    return { ...synthesised(ctx), wind, thunder, source: 'synth' };
  }

  try {
    const loaded = await Promise.all(RECORDED.map(n => fetchBuffer(ctx, n)));
    const out = { wind, thunder, source: 'recorded' };
    RECORDED.forEach((n, i) => { out[n] = loaded[i]; });
    return out;
  } catch (e) {
    const reason = String((e && e.message) || e);
    console.warn('[THRESHOLD] recorded weather unavailable, using the synth instead:', reason);
    return { ...synthesised(ctx), wind, thunder, source: 'synth', reason };
  }
}
