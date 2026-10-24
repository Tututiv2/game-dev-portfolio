// Procedural sound. There is not one audio file in this project.
//
// Weather is the best case for synthesis there is: rain is shaped noise plus transients, wind is
// noise through a moving filter, thunder is a low burst with a long tail. Writing it in code costs
// seconds, is deterministic from a seed (so the test suite can reproduce a storm exactly), and
// scales continuously with intensity in a way a recorded loop cannot.
//
// Everything here writes samples straight into an AudioBuffer. No nodes, no scheduling — that is
// engine.js's job.
//
// WHAT MADE THE FIRST VERSION SOUND LIKE STATIC. The reported symptom was "sharp sounds, not
// rain", and the tempting diagnosis — not enough drops, so you hear them individually as crackle —
// turned out to be third on the list. Measuring the two buffers in octave bands settled it:
//
//   1. SPECTRAL, and this was the whole thing. The bed was shaped by ONE one-pole low-pass, i.e.
//      6 dB/octave, which does not band-limit anything — it just tilts it. Measured, the old rain
//      ran -9.0, -6.4, -5.9, -8.5, -7.2 dB across the 1 k, 2 k, 4 k, 8 k and 12 k octaves: flat to
//      the top of hearing, with as much energy at 12 kHz as at 1 kHz. That is the definition of
//      hiss. Real rain rolls off above a couple of kHz. Two poles instead of one took the spectral
//      centroid from 5703 Hz to 3043 Hz, and that single change is most of the fix.
//
//   2. ONSETS. Every drop started at full amplitude on its first sample — a step discontinuity,
//      which is broadband by definition. Hundreds of hard edges a second is a click track buried
//      in noise. Real impacts have a rise time; a few hundred microseconds of attack costs nothing
//      and removes the edge. Honest caveat: measured on its own this one moved the centroid by
//      11 Hz, because the drop layer gets low-passed after the fact and that was already taking
//      most of the edge energy out. It is kept because it is right at the source rather than
//      because it rescued anything.
//
//   3. DENSITY, the real but smallest effect. ~360 drops/sec is slow enough that the ear separates
//      them; more of them, each far quieter, pile into a texture instead. Note this one barely
//      moves the obvious metric — short-window level steadiness went 1.33 → 1.38 — which is exactly
//      why it should not have been diagnosed by ear-reasoning alone.
//
// And one supporting fix: PEAK NORMALISATION IS THE WRONG NORMALISATION for anything with
// transients in it, because dividing by the single loudest sample pushes the bed down underneath
// whichever drop happened to land hardest. RMS-normalise, then soft-clip the peaks.
//
// ---------------------------------------------------------------------------------------------
// DO NOT SYNTHESISE THE DROPS YOU CANNOT HEAR SEPARATELY. Chasing (3) above, the drop rates went
// to 3400/sec for rain and 1900/sec for the roof — which meant the roof loop was summing about 350
// overlapping decaying oscillators into every single output sample, 90 million inner iterations,
// each with a Math.exp, a Math.pow and a Math.sin in it. Boot went to NINE SECONDS of blocked main
// thread, and from outside the only symptom was that clicking "begin" appeared to do nothing.
//
// It was pointless as well as slow. The sum of hundreds of overlapping identical-statistics
// transients is stationary — it is noise, and there is already a noise bed right there modelling
// it far more cheaply. So the split is: the BED is the fused majority of the storm, and the drop
// layer synthesises only the few hundred a second that stand out of it. Rates came back to 900 and
// 420, the spectrum moved by 76 Hz of rain centroid, and boot went 9.0 s → 0.41 s. Together with
// stepping the envelope and the pitch drift incrementally instead of calling exp/pow per sample,
// that is a 27x speedup for a change nobody can hear.

import { mulberry32 } from '../weather/weather.js';

const TAU = Math.PI * 2;

/** One-pole low-pass, in place. `f` is 0..1; cutoff ≈ -ln(1-f) · sr / 2π. 6 dB/octave. */
function lowpass(buf, f) {
  let y = 0;
  for (let i = 0; i < buf.length; i++) { y += f * (buf[i] - y); buf[i] = y; }
}

/** One-pole high-pass, in place. */
function highpass(buf, f) {
  let y = 0;
  for (let i = 0; i < buf.length; i++) { y += f * (buf[i] - y); buf[i] -= y; }
}

function normalise(buf, peak = 0.85) {
  let m = 0;
  for (let i = 0; i < buf.length; i++) m = Math.max(m, Math.abs(buf[i]));
  if (m < 1e-9) return;
  const k = peak / m;
  for (let i = 0; i < buf.length; i++) buf[i] *= k;
}

/**
 * Normalise to RMS over the first `n` samples — the right measure for a continuous texture, where
 * what you hear is the average and not the one drop that happened to land hardest.
 */
function normaliseRms(buf, n, target) {
  let s = 0;
  for (let i = 0; i < n; i++) s += buf[i] * buf[i];
  const r = Math.sqrt(s / n);
  if (r < 1e-9) return;
  const k = target / r;
  for (let i = 0; i < buf.length; i++) buf[i] *= k;
}

/** Round the top off the few samples that stick out, without touching the body of the signal. */
function softClip(buf, n, ceiling = 0.92) {
  const knee = ceiling * 0.68;
  for (let i = 0; i < n; i++) {
    const v = buf[i];
    const a = Math.abs(v);
    if (a <= knee) continue;
    buf[i] = Math.sign(v) * (knee + (ceiling - knee) * Math.tanh((a - knee) / (ceiling - knee)));
  }
}

/**
 * Make a buffer loop without a click, by GENERATING `x` extra samples and folding them back.
 *
 * The obvious version of this — crossfade the tail into the head of a fixed-length buffer, then
 * return a shortened view — cannot work, because an AudioBuffer's length is fixed at creation and
 * the shortened view is therefore discarded by every caller. Playback still ran to the last sample
 * before wrapping to the first, and the crossfade had rewritten the head to equal the *pre-tail*,
 * so the wrap was a jump between two unrelated samples. It was a no-op wearing a reassuring name.
 *
 * Worth being exact about the damage, because the tempting story is wrong: measured against the
 * old buffers, that wrap step was 0.5x the median sample step in rain, 1.1x in roof and 2.6x in
 * wind — in every case smaller than the largest step already occurring inside the buffer, so it
 * was inaudible, and it is NOT what made the rain sound sharp. It was luck rather than design,
 * though: nothing bounded it, and a different seed could have landed it anywhere.
 *
 * Generating n+x samples and folding the overlap makes it correct rather than lucky: out[0] IS
 * work[n], so the wrap work[n-1] → work[n] is a pair the generator actually produced in sequence,
 * and continuity holds by construction for any seed. As a bonus it hides the filters' warm-up from
 * silence, since work[0] is weighted to zero.
 */
function loopFold(work, n, x) {
  for (let i = 0; i < x; i++) {
    const t = i / x;
    work[i] = work[i] * t + work[n + i] * (1 - t);
  }
}

function stereoBuffer(ctx, seconds) {
  return ctx.createBuffer(2, Math.floor(ctx.sampleRate * seconds), ctx.sampleRate);
}

/**
 * A decaying resonance — the building block of every impact in here. Water on a leaf, water on a
 * board, a latch, a footstep: all the same shape, different frequency and decay.
 */
function blip(out, at, freq, decaySec, amp, sr, rnd, noisiness = 0.25, attackSec = 0) {
  // Three time constants, not four. exp(-4) is 1.8% of the peak — inaudible under a bed, and the
  // last quarter of every blip's length was a quarter of the whole synthesis budget.
  const len = Math.min(Math.floor(decaySec * sr * 3), out.length - at);
  if (len <= 0) return;
  // Envelope and pitch drift stepped INCREMENTALLY. The direct form calls Math.exp and Math.pow
  // once per sample, and this is the hottest loop in the project by two orders of magnitude — it
  // runs tens of millions of times before the first frame is drawn — so each becomes one multiply.
  const envStep = Math.exp(-1 / (decaySec * sr));
  const drift = 1 - rnd() * 0.0004; // a touch of pitch fall, so it reads as an object, not a tone
  // A rise time. Starting at full amplitude on sample zero is a step discontinuity, and a step is
  // broadband — do it a few thousand times a second and the buffer acquires a layer of clicks that
  // no amount of filtering the RESULT can remove, because the edges are inside the signal. Default
  // zero so latches and footsteps, which genuinely do want a hard attack, are untouched.
  const aLen = Math.max(0, Math.floor(attackSec * sr));
  const tw = 1 - noisiness;
  let env = 1;
  let w = TAU * freq / sr;
  let ph = rnd() * TAU;
  for (let i = 0; i < len; i++) {
    const a = env * amp * (i < aLen ? (i + 1) / (aLen + 1) : 1);
    ph += w;
    w *= drift;
    env *= envStep;
    out[at + i] += a * (Math.sin(ph) * tw + (rnd() * 2 - 1) * noisiness);
  }
}

/**
 * OUTDOOR RAIN. A dense broadband wash, made of far more drops than you can count, which is the
 * only way it stops reading as static with ticks in it. This is the bed that walls filter and
 * apertures re-radiate.
 *
 * The spectrum is deliberately narrow at the bottom — rain genuinely has very little below about
 * 400 Hz, and the low end of a storm is the WIND. Keeping the two apart is also what leaves room
 * for the roof layer to own the low-mids indoors, which is the whole effect (BUILD_PROMPT §0).
 */
export function makeRainBuffer(ctx, seed = 11) {
  const rnd = mulberry32(seed);
  const sr = ctx.sampleRate;
  const buf = stereoBuffer(ctx, 5.0);
  const n = buf.length;
  const x = Math.floor(sr * 0.30);
  const N = n + x;

  for (let c = 0; c < 2; c++) {
    // The wash. Two poles off the top rather than one, so it hisses rather than sizzles — a single
    // 6 dB/octave pole leaves 12 kHz only a few dB below 6 kHz, and that shelf of top end is what
    // the ear reads as harshness.
    const bed = new Float32Array(N);
    for (let i = 0; i < N; i++) bed[i] = rnd() * 2 - 1;
    // 12 dB/octave at BOTH ends. The bottom matters as much as the top and for a different reason:
    // every decibel of low-mid the rain bed keeps is a decibel the roof layer has to fight to be
    // heard over, and the roof owning the low-mids indoors IS the effect this project exists for.
    // Rain really does have very little below ~400 Hz — the bottom of a storm is the wind.
    highpass(bed, 0.048);           // ~350 Hz, twice = 12 dB/octave
    highpass(bed, 0.048);
    lowpass(bed, 0.40);             // ~3.9 kHz, twice = 12 dB/octave
    lowpass(bed, 0.40);

    // The drops that you can actually RESOLVE, in their own layer so they can be filtered as a
    // group. Not every drop in the storm: a downpour is thousands a second, and summing thousands
    // of overlapping decaying oscillators per sample is both ruinously slow and pointless, because
    // their sum is stationary — it IS the noise bed above. So the bed models the fused majority and
    // this layer models only the few hundred a second near enough to stand out of it. The cubed
    // amplitude puts most of those near silence and a few forward, which is how rain distributes.
    const drop = new Float32Array(N);
    const count = Math.round(900 * (N / sr));
    for (let k = 0; k < count; k++) {
      const at = Math.floor(rnd() * (N - 1200));
      const f = 800 + rnd() * rnd() * 7200;
      const a = 0.006 + rnd() * rnd() * rnd() * 0.15;
      blip(drop, at, f, 0.0012 + rnd() * 0.005, a, sr, rnd, 0.82, 0.00025);
    }
    lowpass(drop, 0.60);            // takes the razor edge off without dulling them

    const out = new Float32Array(N);
    for (let i = 0; i < N; i++) {
      // Rain arrives in slow waves; a perfectly steady level is the other thing that reads as noise
      // rather than weather. Offset per channel so the two sides breathe independently.
      const t = i / sr;
      const wash = 0.86 + 0.14 * Math.sin(t * 0.29 + c * 2.1);
      out[i] = (bed[i] * 0.62 + drop[i]) * wash;
    }

    loopFold(out, n, x);
    normaliseRms(out, n, 0.17);
    softClip(out, n, 0.9);
    buf.getChannelData(c).set(out.subarray(0, n));
  }
  return buf;
}

/**
 * RAIN ON THE ROOF — the whole point of the project (BUILD_PROMPT §0).
 *
 * Not a filtered version of the bed. It is a different sound: lower, woodier, made of discrete
 * impacts with a body to them, because you are hearing drops strike a surface a metre or two above
 * your head rather than a field of water fifty metres wide. This is the sound you CANNOT hear
 * outdoors, and gaining it is what makes an interior feel like shelter instead of a quiet room.
 *
 * Same density lesson as the rain bed, one octave down: individually audible taps sound like
 * someone flicking the ceiling, and a roof in a downpour is a ROAR with taps resolvable in it.
 */
export function makeRoofBuffer(ctx, seed = 23) {
  const rnd = mulberry32(seed);
  const sr = ctx.sampleRate;
  const buf = stereoBuffer(ctx, 5.4);
  const n = buf.length;
  const x = Math.floor(sr * 0.30);
  const N = n + x;

  for (let c = 0; c < 2; c++) {
    // A soft bed underneath, so the taps sit in something rather than in silence.
    const bed = new Float32Array(N);
    for (let i = 0; i < N; i++) bed[i] = rnd() * 2 - 1;
    lowpass(bed, 0.10);
    lowpass(bed, 0.10);
    highpass(bed, 0.013);

    // Same split as the rain bed: the roar is the bed, and these are only the impacts that stand
    // out of it. At 1900/sec this loop was writing 350 overlapping voices into every single sample
    // and took SEVEN SECONDS at boot on its own.
    const taps = new Float32Array(N);
    const count = Math.round(420 * (N / sr));
    for (let k = 0; k < count; k++) {
      const at = Math.floor(rnd() * (N - 4000));
      const f = 130 + rnd() * rnd() * 520;
      const a = 0.010 + rnd() * rnd() * rnd() * 0.32;
      blip(taps, at, f, 0.008 + rnd() * 0.030, a, sr, rnd, 0.55, 0.0006);
    }
    // A few heavier ones — drips off the eaves, a gust throwing a handful at the boards. These are
    // the ones you are supposed to notice, and they only read as events because the rest do not.
    //
    // Their count is FIXED while the taps around them are a rate, so cutting the tap rate promoted
    // them from accents to the main event: the buffer went bottom-heavy (-2.0 dB below 120 Hz
    // against -4.4 in the 120-900 band where the model expects the drumming to live) and the check
    // that the low-mids dominate indoors failed. Anything expressed as an absolute count next to
    // something expressed as a rate has to be rebalanced whenever the rate moves.
    for (let k = 0; k < 22; k++) {
      const at = Math.floor(rnd() * (N - 12000));
      blip(taps, at, 95 + rnd() * 130, 0.045 + rnd() * 0.08, 0.20 + rnd() * 0.18, sr, rnd, 0.35, 0.0012);
    }
    lowpass(taps, 0.34);

    const out = new Float32Array(N);
    for (let i = 0; i < N; i++) {
      const t = i / sr;
      const wash = 0.85 + 0.15 * Math.sin(t * 0.23 + c * 1.7);
      out[i] = (bed[i] * 0.40 + taps[i]) * wash;
    }

    loopFold(out, n, x);
    normaliseRms(out, n, 0.19);
    softClip(out, n, 0.92);
    buf.getChannelData(c).set(out.subarray(0, n));
  }
  return buf;
}

/** WIND. Pink-ish noise; the live band-pass and gust envelope do the shaping. */
export function makeWindBuffer(ctx, seed = 37) {
  const rnd = mulberry32(seed);
  const sr = ctx.sampleRate;
  const buf = stereoBuffer(ctx, 6.3);
  const n = buf.length;
  const x = Math.floor(sr * 0.45);
  const N = n + x;

  for (let c = 0; c < 2; c++) {
    const out = new Float32Array(N);
    // Paul Kellet's pink filter — cheap and good enough to sound like moving air.
    let b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
    for (let i = 0; i < N; i++) {
      const w = rnd() * 2 - 1;
      b0 = 0.99886 * b0 + w * 0.0555179;
      b1 = 0.99332 * b1 + w * 0.0750759;
      b2 = 0.96900 * b2 + w * 0.1538520;
      b3 = 0.86650 * b3 + w * 0.3104856;
      b4 = 0.55000 * b4 + w * 0.5329522;
      b5 = -0.7616 * b5 - w * 0.0168980;
      out[i] = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362) * 0.11;
      b6 = w * 0.115926;
    }
    // Slow swells baked in, so even a steady wind breathes.
    for (let i = 0; i < N; i++) {
      const t = i / sr;
      out[i] *= 0.72 + 0.28 * (Math.sin(t * 0.37) * 0.6 + Math.sin(t * 0.91 + 2.1) * 0.4);
    }
    loopFold(out, n, x);
    normaliseRms(out, n, 0.20);
    softClip(out, n, 0.9);
    buf.getChannelData(c).set(out.subarray(0, n));
  }
  return buf;
}

/**
 * THUNDER, dry and full-bandwidth. Distance shaping is applied at play time by the backend — one
 * sound, filtered and stretched, so a rumble and a crack are demonstrably the same event far away
 * and near. See BUILD_PROMPT §3.5.
 */
export function makeThunderBuffer(ctx, seed = 51) {
  const rnd = mulberry32(seed);
  const sr = ctx.sampleRate;
  const buf = stereoBuffer(ctx, 3.4);
  for (let c = 0; c < 2; c++) {
    const d = buf.getChannelData(c);
    const n = d.length;
    for (let i = 0; i < n; i++) d[i] = rnd() * 2 - 1;
    // Two poles, same lesson as the rain bed. One pole at 0.35 left the source centroid at 4000 Hz
    // — so "dry and full-bandwidth" was really "white noise with a tilt", and since a strike at
    // 200 m is only low-passed at 10 kHz, the closest and most dramatic thunder in the game was the
    // one that sounded least like thunder. This lands it at 1379 Hz.
    //
    // Two and not three, which is where it was first set: at three poles the source is so dark that
    // it sits below the air-absorption cutoff of a DISTANT strike, and near and far stop sounding
    // different — the check that near thunder is much brighter than far thunder failed outright.
    // The source has to keep enough top for distance to have something to take away, which is the
    // same constraint the rain bed is under against the wall, one octave down.
    lowpass(d, 0.26);
    lowpass(d, 0.26);
    // The strike: a fast crack, then a long irregular roll made of overlapping swells.
    for (let i = 0; i < n; i++) {
      const t = i / sr;
      const crack = Math.exp(-t * 42) * 1.0;
      const roll = Math.exp(-t * 1.05) * (0.55 + 0.45 * Math.sin(t * 7.3 + Math.sin(t * 2.1) * 3));
      d[i] *= crack + roll * 0.85;
    }
    // A sub-bass thump underneath — the part that survives distance.
    for (let i = 0; i < n; i++) {
      const t = i / sr;
      d[i] += Math.sin(TAU * (34 - t * 6) * t) * Math.exp(-t * 2.4) * 0.5;
    }
    normalise(d, 0.95);
  }
  return buf;
}

/** Footsteps and latches — short, material-coloured transients. */
export function makeFoleyBuffer(ctx, spec, seed) {
  const rnd = mulberry32(seed);
  const sr = ctx.sampleRate;
  const buf = ctx.createBuffer(1, Math.floor(sr * 0.4), sr);
  const d = buf.getChannelData(0);
  blip(d, 0, spec.freq, spec.decay, spec.gain, sr, rnd, 0.72);
  blip(d, 0, spec.freq * 1.94, spec.decay * 0.45, spec.gain * 0.4, sr, rnd, 0.8);
  normalise(d, 0.9);
  return buf;
}

/**
 * Reverb impulse: exponentially decaying noise, with the top end decaying faster than the bottom
 * because air and soft furnishings both eat treble first.
 */
export function makeImpulse(ctx, rt60, seed = 71) {
  const rnd = mulberry32(seed);
  const sr = ctx.sampleRate;
  const len = Math.max(64, Math.floor(sr * Math.min(rt60 * 1.4, 4)));
  const buf = ctx.createBuffer(2, len, sr);
  for (let c = 0; c < 2; c++) {
    const d = buf.getChannelData(c);
    const k = 6.9078 / (rt60 * sr); // ln(1000) — the −60 dB point
    for (let i = 0; i < len; i++) d[i] = (rnd() * 2 - 1) * Math.exp(-i * k);
    const bright = new Float32Array(d);
    lowpass(d, 0.25);
    for (let i = 0; i < len; i++) {
      const t = i / len;
      d[i] = d[i] * (0.45 + 0.55 * t) + bright[i] * (0.55 - 0.5 * t) * 0.5;
    }
    // Silence the first millisecond: the direct sound is not the reverb's job.
    const pre = Math.floor(sr * 0.006);
    for (let i = 0; i < pre; i++) d[i] *= i / pre;
    normalise(d, 0.5);
  }
  return buf;
}
