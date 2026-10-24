// Code phonk: a claim-free drift phonk track, synthesised live on the Web Audio
// clock. Everything here is shared between the live player (engine.js) and
// renderPhonkOffline(), so the self-test measures the song the game plays.
//
// Song form, in bars relative to the DROP (bar 0 starts exactly at dropAt):
//   bar -2, -1   lead-in: cowbell riff through an opening low-pass + a riser.
//                The last beat of bar -1 is TOTAL silence (a gate, not a hope).
//   bar 0..7     main: 808 + kick + clap + hats + cowbell. Crash + sub boom on 0.
//   bar 8        break: build (snare roll, riser, filter closing), silent last beat.
//   bar 9        next drop ... so drops land at dropAt + k * 9 bars.
// Practice mode is the main groove from bar 0 with no lead-in and no breaks.
//
// Every note time is t0 + (bar*16 + step) * sixteenth -- computed, never
// accumulated -- so step 0 of bar k is exactly BeatGrid(bpm, t0).beatTime(4k).

export const PHONK_BPM = 130;
export const LEAD_BARS = 2;
export const MAIN_BARS = 8;
export const CYCLE_BARS = MAIN_BARS + 1;
export const FMAX = 18000;

export const mtof = (m) => 440 * Math.pow(2, (m - 69) / 12);

// Deterministic hash in [0,1): the live song and the offline render are the same song.
export function hash(a, b = 0) {
  let h = (Math.imul(a | 0, 374761393) + Math.imul(b | 0, 668265263) + 0x27d4eb2d) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  h ^= h >>> 16;
  return (h >>> 0) / 4294967296;
}

// ---------------------------------------------------------------- buffers/curves

const BUFS = new WeakMap();
export function noiseBuffers(ctx) {
  let n = BUFS.get(ctx);
  if (n) return n;
  const sr = ctx.sampleRate;
  let s = 0x2545f491;
  const rnd = () => {
    s ^= s << 13; s ^= s >>> 17; s ^= s << 5;
    return ((s >>> 0) / 4294967296) * 2 - 1;
  };
  const white = ctx.createBuffer(1, Math.floor(sr * 2), sr);
  const w = white.getChannelData(0);
  for (let i = 0; i < w.length; i++) w[i] = rnd();
  // Brown (leaky-integrated) noise, loop crossfaded so the wind never clicks.
  const len = Math.floor(sr * 6), fade = Math.floor(sr * 0.5);
  const tmp = new Float32Array(len + fade);
  let b = 0;
  for (let i = 0; i < tmp.length; i++) { b = (b + 0.02 * rnd()) / 1.02; tmp[i] = b * 3.5; }
  const brown = ctx.createBuffer(1, len, sr);
  const bd = brown.getChannelData(0);
  for (let i = 0; i < len; i++) bd[i] = tmp[i];
  for (let i = 0; i < fade; i++) { const k = i / fade; bd[i] = tmp[i] * k + tmp[len + i] * (1 - k); }
  n = { white, brown };
  BUFS.set(ctx, n);
  return n;
}

// Odd length so x = 0 maps exactly onto the centre sample (silence stays 0.0).
const CURVES = new Map();
export function satCurve(drive) {
  const key = 'sat' + drive;
  let c = CURVES.get(key);
  if (c) return c;
  const n = 4097, norm = Math.tanh(drive);
  c = new Float32Array(n);
  for (let i = 0; i < n; i++) { const x = (i / (n - 1)) * 2 - 1; c[i] = Math.tanh(drive * x) / norm; }
  CURVES.set(key, c);
  return c;
}
// Final safety clip: linear below 0.9, soft knee to 0.995. Driven through a
// 0.5x pre / 2x post pair, so the curve's [-1,1] domain covers inputs to +-2.
function clipCurve() {
  let c = CURVES.get('clip');
  if (c) return c;
  const n = 4097;
  c = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    const u = ((i / (n - 1)) * 2 - 1) * 2;
    const a = Math.abs(u);
    const y = a <= 0.9 ? a : 0.9 + 0.095 * Math.tanh((a - 0.9) / 0.095);
    c[i] = (Math.sign(u) * y) / 2;
  }
  CURVES.set('clip', c);
  return c;
}

// Master chain: bus compressor -> limiter -> soft clip -> master volume.
export function buildMaster(ctx, dest = ctx.destination) {
  const input = ctx.createGain();
  const comp = ctx.createDynamicsCompressor();
  comp.threshold.value = -14; comp.knee.value = 8; comp.ratio.value = 3;
  comp.attack.value = 0.006; comp.release.value = 0.2;
  const lim = ctx.createDynamicsCompressor();
  lim.threshold.value = -4; lim.knee.value = 0; lim.ratio.value = 20;
  lim.attack.value = 0.001; lim.release.value = 0.09;
  const pre = ctx.createGain(); pre.gain.value = 0.5;
  const clip = ctx.createWaveShaper(); clip.curve = clipCurve(); clip.oversample = 'none';
  const post = ctx.createGain(); post.gain.value = 2;
  const out = ctx.createGain();
  input.connect(comp); comp.connect(lim); lim.connect(pre); pre.connect(clip); clip.connect(post);
  post.connect(out); out.connect(dest);
  return { input, comp, lim, out };
}

// DynamicsCompressorNode has a fixed look-ahead delay (~6 ms each in Chrome).
// Everything (music AND sfx) passes the same chain, so it is pure output latency.
export async function measureChainLatency(sampleRate = 48000) {
  const len = Math.round(sampleRate * 0.1), at = 256;
  const ctx = new OfflineAudioContext(1, len, sampleRate);
  const m = buildMaster(ctx);
  const b = ctx.createBuffer(1, len, sampleRate);
  b.getChannelData(0)[at] = 0.25;
  const s = ctx.createBufferSource(); s.buffer = b; s.connect(m.input); s.start(0);
  const out = (await ctx.startRendering()).getChannelData(0);
  let peak = 0, idx = -1;
  for (let i = 0; i < out.length; i++) { const a = Math.abs(out[i]); if (a > peak) { peak = a; idx = i; } }
  return idx < 0 ? 0 : (idx - at) / sampleRate;
}

// ---------------------------------------------------------------- voices
// Each voice: (ctx, dest, t, ...) -> schedules itself at ctx time t. Nothing else.

export function vCowbell(ctx, dest, t, n, v = 1, send = null) {
  const f = mtof(n);
  const o1 = ctx.createOscillator(), o2 = ctx.createOscillator();
  o1.type = 'square'; o2.type = 'square';
  o1.frequency.value = f; o2.frequency.value = f * 1.48;
  const bp = ctx.createBiquadFilter(); bp.type = 'bandpass'; bp.frequency.value = f * 2.1; bp.Q.value = 1.1;
  const hp = ctx.createBiquadFilter(); hp.type = 'highpass'; hp.frequency.value = 350;
  const g = ctx.createGain();
  const peak = 0.15 * v;
  g.gain.setValueAtTime(0, t);
  g.gain.linearRampToValueAtTime(peak, t + 0.0015);
  g.gain.exponentialRampToValueAtTime(peak * 0.32, t + 0.035);
  g.gain.exponentialRampToValueAtTime(0.0005, t + 0.26);
  o1.connect(bp); o2.connect(bp); bp.connect(hp); hp.connect(g); g.connect(dest);
  if (send) g.connect(send);
  o1.start(t); o2.start(t); o1.stop(t + 0.28); o2.stop(t + 0.28);
}

// Distorted, boosted 808: pitch drop on the attack, optional glide into `slide`.
export function vBass(ctx, dest, t, n, dur, slide = null, v = 1, accent = true) {
  const f = mtof(n);
  const o = ctx.createOscillator();
  // An accented note (on a beat, with the kick) punches: pitch drop from 3x and a
  // peak. An off-beat note re-enters at the sustain level with a small drop, so the
  // 808 grooves in 3-3-2 without its onsets out-voting the kick for the pulse.
  o.frequency.setValueAtTime(f * (accent ? 3 : 1.5), t);
  o.frequency.exponentialRampToValueAtTime(f, t + (accent ? 0.045 : 0.03));
  if (slide != null) {
    const ts = t + Math.max(0.07, dur * 0.45);
    o.frequency.setValueAtTime(f, ts);
    o.frequency.exponentialRampToValueAtTime(mtof(slide), t + Math.max(0.1, dur * 0.9));
  }
  const sh = ctx.createWaveShaper(); sh.curve = satCurve(4.5);
  const shelf = ctx.createBiquadFilter(); shelf.type = 'lowshelf'; shelf.frequency.value = 80; shelf.gain.value = 5;
  const lp = ctx.createBiquadFilter(); lp.type = 'lowpass'; lp.frequency.value = 1900; lp.Q.value = 0.8;
  const g = ctx.createGain();
  g.gain.setValueAtTime(0, t);
  g.gain.linearRampToValueAtTime((accent ? 0.6 : 0.13) * v, t + 0.004);
  g.gain.setTargetAtTime(0.11 * v, t + 0.05, 0.25);
  // Release starts exactly where the next note attacks: a legato crossfade, so an
  // off-beat 808 note is a pitch change, not an energy gap -- the beat stays the pulse.
  g.gain.setTargetAtTime(0, t + Math.max(0.03, dur), 0.006);
  o.connect(sh); sh.connect(shelf); shelf.connect(lp); lp.connect(g); g.connect(dest);
  o.start(t); o.stop(t + dur + 0.06);
}

export function vKick(ctx, dest, t, v = 1) {
  const o = ctx.createOscillator();
  o.frequency.setValueAtTime(180, t);
  o.frequency.exponentialRampToValueAtTime(55, t + 0.055);
  o.frequency.exponentialRampToValueAtTime(42, t + 0.28);
  const g = ctx.createGain();
  g.gain.setValueAtTime(0, t);
  g.gain.linearRampToValueAtTime(v, t + 0.0008);
  g.gain.exponentialRampToValueAtTime(0.001, t + 0.3);
  const sh = ctx.createWaveShaper(); sh.curve = satCurve(1.8);
  const lvl = ctx.createGain(); lvl.gain.value = 1.5;
  o.connect(g); g.connect(sh); sh.connect(lvl); lvl.connect(dest);
  o.start(t); o.stop(t + 0.32);
  const src = ctx.createBufferSource(); src.buffer = noiseBuffers(ctx).white;
  const hp = ctx.createBiquadFilter(); hp.type = 'highpass'; hp.frequency.value = 2500;
  const cg = ctx.createGain();
  cg.gain.setValueAtTime(0.3 * v, t);
  cg.gain.exponentialRampToValueAtTime(0.001, t + 0.01);
  src.connect(hp); hp.connect(cg); cg.connect(dest);
  src.start(t, 0.3); src.stop(t + 0.015);
}

export function vClap(ctx, dest, t, v = 1) {
  const src = ctx.createBufferSource(); src.buffer = noiseBuffers(ctx).white;
  // A phonk clap is a clap/snare hybrid and it has to hit as hard as the kick:
  // beats 2 and 4 are half of the parry tell.
  const bp = ctx.createBiquadFilter(); bp.type = 'bandpass'; bp.frequency.value = 1400; bp.Q.value = 0.55;
  const hp = ctx.createBiquadFilter(); hp.type = 'highpass'; hp.frequency.value = 450;
  const g = ctx.createGain();
  for (let i = 0; i < 3; i++) {
    const ti = t + i * 0.011;
    g.gain.setValueAtTime(2.0 * v, ti);
    g.gain.exponentialRampToValueAtTime(0.3 * v, ti + 0.009);
  }
  g.gain.setValueAtTime(1.6 * v, t + 0.031);
  g.gain.exponentialRampToValueAtTime(0.001, t + 0.26);
  // band-passed noise loses most of its energy; this puts the clap level with the kick
  const lvl = ctx.createGain(); lvl.gain.value = 5;
  lvl.connect(dest);
  src.connect(bp); bp.connect(hp); hp.connect(g); g.connect(lvl);
  src.start(t, 0.7); src.stop(t + 0.28);
  const o = ctx.createOscillator(); o.type = 'triangle';
  o.frequency.setValueAtTime(210, t);
  o.frequency.exponentialRampToValueAtTime(150, t + 0.07);
  const og = ctx.createGain();
  og.gain.setValueAtTime(0.3 * v, t);
  og.gain.exponentialRampToValueAtTime(0.001, t + 0.1);
  o.connect(og); og.connect(lvl);
  o.start(t); o.stop(t + 0.11);
}

export function vSnare(ctx, dest, t, v = 1) {
  const src = ctx.createBufferSource(); src.buffer = noiseBuffers(ctx).white;
  const bp = ctx.createBiquadFilter(); bp.type = 'bandpass'; bp.frequency.value = 1900; bp.Q.value = 0.8;
  const g = ctx.createGain();
  g.gain.setValueAtTime(0.5 * v, t);
  g.gain.exponentialRampToValueAtTime(0.001, t + 0.09);
  src.connect(bp); bp.connect(g); g.connect(dest);
  src.start(t, 1.1); src.stop(t + 0.1);
  const o = ctx.createOscillator(); o.type = 'triangle'; o.frequency.value = 230;
  const og = ctx.createGain();
  og.gain.setValueAtTime(0.15 * v, t);
  og.gain.exponentialRampToValueAtTime(0.001, t + 0.06);
  o.connect(og); og.connect(dest);
  o.start(t); o.stop(t + 0.07);
}

export function vHat(ctx, dest, t, v = 1, open = false, tone = 1, offset = 0) {
  const src = ctx.createBufferSource(); src.buffer = noiseBuffers(ctx).white;
  const hp = ctx.createBiquadFilter(); hp.type = 'highpass'; hp.frequency.value = Math.min(15000, 7200 * tone);
  const pk = ctx.createBiquadFilter(); pk.type = 'peaking'; pk.frequency.value = Math.min(16000, 10500 * tone); pk.gain.value = 4;
  const g = ctx.createGain();
  const dur = open ? 0.22 : 0.035;
  g.gain.setValueAtTime(0.2 * v, t);
  g.gain.exponentialRampToValueAtTime(0.001, t + dur);
  const p = ctx.createStereoPanner(); p.pan.value = 0.15;
  src.connect(hp); hp.connect(pk); pk.connect(g); g.connect(p); p.connect(dest);
  src.start(t, offset); src.stop(t + dur + 0.01);
}

export function vCrash(ctx, dest, t, v = 1, len = 2.2) {
  const white = noiseBuffers(ctx).white;
  for (const [pan, off] of [[-0.55, 0.1], [0.55, 0.93]]) {
    const src = ctx.createBufferSource(); src.buffer = white; src.loop = true;
    const hp = ctx.createBiquadFilter(); hp.type = 'highpass'; hp.frequency.value = 4000;
    const g = ctx.createGain();
    g.gain.setValueAtTime(0.3 * v, t);
    g.gain.exponentialRampToValueAtTime(0.001, t + len);
    const p = ctx.createStereoPanner(); p.pan.value = pan;
    src.connect(hp); hp.connect(g); g.connect(p); p.connect(dest);
    src.start(t, off); src.stop(t + len + 0.05);
  }
  const mhp = ctx.createBiquadFilter(); mhp.type = 'highpass'; mhp.frequency.value = 7000;
  const mg = ctx.createGain();
  mg.gain.setValueAtTime(0.05 * v, t);
  mg.gain.exponentialRampToValueAtTime(0.001, t + len * 0.7);
  mhp.connect(mg); mg.connect(dest);
  for (const r of [205.3, 304.4, 369.6, 522.7, 540, 800]) {
    const o = ctx.createOscillator(); o.type = 'square'; o.frequency.value = r * 1.9;
    o.connect(mhp); o.start(t); o.stop(t + len * 0.7 + 0.05);
  }
}

export function vSubBoom(ctx, dest, t, v = 1, len = 2.2) {
  const o = ctx.createOscillator();
  o.frequency.setValueAtTime(62, t); // already sub on the first sample: the drop is instant
  o.frequency.exponentialRampToValueAtTime(30, t + len * 0.7);
  const g = ctx.createGain();
  g.gain.setValueAtTime(0, t);
  g.gain.linearRampToValueAtTime(0.9 * v, t + 0.004);
  g.gain.exponentialRampToValueAtTime(0.001, t + len);
  const sh = ctx.createWaveShaper(); sh.curve = satCurve(2);
  const lp = ctx.createBiquadFilter(); lp.type = 'lowpass'; lp.frequency.value = 250;
  o.connect(g); g.connect(sh); sh.connect(lp); lp.connect(dest);
  o.start(t); o.stop(t + len + 0.05);
}

// Dark detuned-saw pad (the lead-in's held breath). Ends with a 12 ms fade at t + dur.
export function vPad(ctx, dest, t, dur, notes = [60, 63, 67], v = 1) {
  const g = ctx.createGain();
  g.gain.setValueAtTime(0.0001, t);
  g.gain.exponentialRampToValueAtTime(0.05 * v, t + Math.min(0.4, dur * 0.3));
  g.gain.setValueAtTime(0.05 * v, t + dur - 0.012);
  g.gain.linearRampToValueAtTime(0, t + dur);
  const hp = ctx.createBiquadFilter(); hp.type = 'highpass'; hp.frequency.value = 220;
  g.connect(hp); hp.connect(dest);
  for (const n of notes) {
    for (const dt of [-9, 9]) {
      const o = ctx.createOscillator(); o.type = 'sawtooth';
      o.frequency.value = mtof(n); o.detune.value = dt;
      o.connect(g); o.start(t); o.stop(t + dur + 0.01);
    }
  }
}

// Noise sweep + a rising sine. Ends with a 12 ms fade exactly at t + dur.
export function vRiser(ctx, dest, t, dur) {
  const src = ctx.createBufferSource(); src.buffer = noiseBuffers(ctx).white; src.loop = true;
  const bp = ctx.createBiquadFilter(); bp.type = 'bandpass'; bp.Q.value = 2;
  bp.frequency.setValueAtTime(350, t);
  bp.frequency.exponentialRampToValueAtTime(7500, t + dur);
  const g = ctx.createGain();
  g.gain.setValueAtTime(0.0001, t);
  g.gain.exponentialRampToValueAtTime(0.22, t + dur - 0.012);
  g.gain.linearRampToValueAtTime(0, t + dur);
  src.connect(bp); bp.connect(g); g.connect(dest);
  src.start(t, 0.4); src.stop(t + dur + 0.01);
  const o = ctx.createOscillator();
  o.frequency.setValueAtTime(150, t);
  o.frequency.exponentialRampToValueAtTime(1100, t + dur);
  const og = ctx.createGain();
  og.gain.setValueAtTime(0.0001, t);
  og.gain.exponentialRampToValueAtTime(0.05, t + dur - 0.012);
  og.gain.linearRampToValueAtTime(0, t + dur);
  o.connect(og); og.connect(dest);
  o.start(t); o.stop(t + dur + 0.01);
}

// ---------------------------------------------------------------- the song

const RIFFS = {
  A1: [72, 0, 72, 75, 0, 79, 77, 0, 75, 0, 74, 72, 0, 67, 70, 0],
  A2: [72, 0, 72, 75, 0, 79, 80, 0, 79, 0, 77, 75, 0, 74, 0, 72],
  B1: [84, 0, 82, 0, 79, 0, 80, 79, 0, 77, 0, 75, 77, 0, 74, 0],
  B2: [75, 0, 75, 77, 0, 79, 0, 82, 80, 0, 79, 0, 77, 75, 74, 71],
  C1: [79, 0, 79, 0, 80, 0, 79, 77, 75, 0, 75, 0, 74, 0, 72, 0],
  C2: [72, 0, 75, 0, 77, 0, 79, 0, 82, 0, 80, 79, 77, 0, 75, 74],
};
// 808 roots per main bar (C C Ab Bb C C Ab G / C Ab F G C Ab Bb G).
const PROGS = [
  [36, 36, 32, 34, 36, 36, 32, 31],
  [36, 32, 29, 31, 36, 32, 34, 31],
];
// [step, length in steps, octave offset]
const BASS_PATS = [
  [[0, 6], [6, 4], [10, 6]],
  [[0, 3], [3, 4], [7, 3], [10, 3], [13, 3]],
  [[0, 8], [8, 3], [11, 5]],
  [[0, 10], [10, 3, 12], [13, 3]],
];
const VARIANTS = [
  { riffs: ['A1', 'A2', 'A1', 'A2', 'B1', 'B2', 'A1', 'A2'],
    hats: ['straight', 'straight', 'straight', 'skip', 'straight', 'straight', 'straight', 'skip'],
    bass: [0, 0, 0, 0, 1, 1, 0, 2], prog: 0, filtered: -1, slides: [7], oct: false },
  { riffs: ['B1', 'B2', 'B1', 'B2', 'C1', 'C2', 'B1', 'B2'],
    hats: ['skip', 'skip', 'skip', 'straight', 'skip', 'skip', 'open', 'straight'],
    bass: [1, 1, 2, 1, 1, 1, 2, 3], prog: 1, filtered: 4, slides: [3, 7], oct: false },
  { riffs: ['A1', 'A2', 'C1', 'C2', 'A1', 'A2', 'B1', 'B2'],
    hats: ['trip', 'straight', 'trip', 'straight', 'trip', 'straight', 'trip', 'skip'],
    bass: [2, 0, 2, 0, 3, 0, 2, 1], prog: 0, filtered: -1, slides: [1, 3, 5, 7], oct: true },
  { riffs: ['C1', 'C2', 'B1', 'B2', 'C1', 'C2', 'A1', 'A2'],
    hats: ['open', 'open', 'straight', 'straight', 'open', 'open', 'trip', 'straight'],
    bass: [3, 1, 3, 1, 0, 1, 3, 2], prog: 1, filtered: 5, slides: [3, 7], oct: false },
];

function riffEvents(ev, name, oct = 0, below = 16, vel = 1) {
  RIFFS[name].forEach((n, i) => {
    if (n && i < below) ev.push({ s: i, k: 'cb', n: n + oct, v: (i % 4 === 0 ? 1 : 0.82) * vel });
  });
}

function hatEvents(ev, style, roll, bar) {
  const end = roll ? 12 : 16;
  const add = (s, v, open = false) => { if (s < end) ev.push({ s, k: 'hat', v, open }); };
  if (style === 'straight') {
    for (let s = 0; s < 16; s++) add(s, s % 4 === 0 ? 0.9 : s % 2 === 0 ? 0.62 : 0.42);
  } else if (style === 'skip') {
    for (const s of [0, 2, 3, 4, 6, 8, 10, 11, 12, 14]) add(s, s % 4 === 0 ? 0.9 : 0.6);
    add(7, 0.5, true);
  } else if (style === 'trip') {
    for (let b = 0; b < 4; b++) {
      if (b % 2) for (let i = 0; i < 6; i++) add(4 * b + (i * 4) / 6, i === 0 ? 0.85 : 0.5);
      else { add(4 * b, 0.9); add(4 * b + 2, 0.6); }
    }
  } else { // 'open'
    for (let s = 0; s < 16; s++) {
      if (s % 4 === 0) add(s, 0.8);
      else if (s % 4 === 2) add(s, 0.55, true);
      else add(s, 0.3);
    }
  }
  if (roll) {
    const trip = hash(bar, 11) < 0.5;
    const n = trip ? 6 : 8, step = 4 / n;
    for (let i = 0; i < n; i++) {
      const k = i / (n - 1);
      ev.push({ s: 12 + i * step, k: 'hat', v: 0.35 + 0.6 * k, tone: 0.9 + 0.4 * k });
    }
  }
}

function leadInBar(bar) {
  const ev = [];
  if (bar === -2) {
    ev.push({ s: 0, k: 'lp', from: 260, to: 9000, d: 28 });
    ev.push({ s: 0, k: 'riser', d: 28 });
    ev.push({ s: 0, k: 'pad', d: 28 });
    riffEvents(ev, 'A1', 0, 16, 0.85);
  } else {
    riffEvents(ev, 'A2', 0, 11, 0.85); // last note leaves 230 ms to decay before the silence
    ev.push({ s: 12, k: 'gate', d: 4 });
  }
  return ev;
}

function breakBar(cyc) {
  const ev = [];
  riffEvents(ev, cyc % 2 ? 'C1' : 'B1', 0, 11);
  ev.push({ s: 0, k: 'lp', from: FMAX, to: 1600, d: 12 });
  ev.push({ s: 0, k: 'riser', d: 12 });
  // no kick, no 808: the low end leaves for the whole build, so the drop is the only rise
  for (const [s, v] of [[0, 0.3], [2, 0.34], [4, 0.38], [6, 0.42]]) ev.push({ s, k: 'snare', v });
  for (let i = 0; i < 8; i++) ev.push({ s: 8 + i * 0.5, k: 'snare', v: 0.5 + i * 0.06 });
  ev.push({ s: 12, k: 'gate', d: 4 });
  return ev;
}

// All events for one bar, sorted by step. mode: 'song' | 'practice'.
export function planBar(bar, mode = 'song') {
  if (mode === 'song' && bar < 0) return leadInBar(bar).sort((a, b) => a.s - b.s);
  const len = mode === 'song' ? CYCLE_BARS : MAIN_BARS;
  const cyc = Math.floor(bar / len), pos = bar - cyc * len;
  if (pos === MAIN_BARS) return breakBar(cyc).sort((a, b) => a.s - b.s);
  const V = VARIANTS[cyc % VARIANTS.length];
  const ev = [];
  if (pos === 0) {
    ev.push({ s: 0, k: 'lpset', to: FMAX });
    ev.push({ s: 0, k: 'crash', v: 1 });
    if (mode === 'song') ev.push({ s: 0, k: 'boom', v: 1 });
  } else if (pos === 4) {
    ev.push({ s: 0, k: 'crash', v: 0.45 });
  }
  if (pos === V.filtered) {
    ev.push({ s: 0, k: 'lp', from: FMAX, to: 420, d: 1 });
    ev.push({ s: 12, k: 'lp', from: 420, to: FMAX, d: 4 });
  }
  // cowbell lead (an octave up for two bars in variant 2)
  riffEvents(ev, V.riffs[pos], V.oct && (pos === 4 || pos === 5) ? 12 : 0);
  // 808
  const prog = PROGS[V.prog];
  const root = prog[pos], next = prog[(pos + 1) % MAIN_BARS];
  const pat = BASS_PATS[V.bass[pos]];
  pat.forEach(([s, d, o = 0], i) => {
    const e = { s, k: 'bass', n: root + o, d };
    if (i === pat.length - 1 && V.slides.includes(pos)) e.slide = next + (hash(bar, 3) < 0.5 ? 12 : 0);
    ev.push(e);
  });
  // drums: kick on 1 and 3, clap on 2 and 4 -- every beat is audible, which is the parry tell
  ev.push({ s: 0, k: 'kick', v: 1 }, { s: 8, k: 'kick', v: 1 });
  ev.push({ s: 4, k: 'clap', v: 1 }, { s: 12, k: 'clap', v: 1 });
  if (pos === 7 || (pos === 3 && hash(bar, 5) < 0.5)) ev.push({ s: 15, k: 'clap', v: 0.45 });
  const roll = pos === 3 || pos === 7 || hash(bar, 7) < 0.2;
  hatEvents(ev, V.hats[pos], roll, bar);
  return ev.sort((a, b) => a.s - b.s);
}

// ---------------------------------------------------------------- the player

export class PhonkPlayer {
  // song:     t0 = dropAt, playback starts at bar -2 (t0 - 2 bars).
  // practice: t0 = start, bar 0 at t0, main groove only.
  constructor(ctx, dest, { bpm = PHONK_BPM, mode = 'song', t0 = 0, lateSkip = true, mute = null } = {}) {
    this.ctx = ctx;
    this.bpm = bpm;
    this.mode = mode;
    this.t0 = t0;
    this.beat = 60 / bpm;
    this.six = this.beat / 4;
    this.lateSkip = lateSkip;
    this.mute = mute; // optional Set of event kinds to leave out (stem renders for diagnostics)
    this.bar = mode === 'song' ? -LEAD_BARS : 0;
    this.events = null;
    this.ei = 0;
    this.stopAt = Infinity;
    this._nz = 1;
    // voices -> in -> filter -> post -> gate -> out -> dest
    this.in = ctx.createGain(); this.in.gain.value = 0.7;
    this.filter = ctx.createBiquadFilter(); this.filter.type = 'lowpass';
    this.filter.frequency.value = FMAX; this.filter.Q.value = 0.9;
    this.post = ctx.createGain(); this.post.gain.value = 0.6;
    this.gate = ctx.createGain();
    this.out = ctx.createGain();
    // 808 + cowbell ride a sidechain bus that pumps on every kick
    this.duck = ctx.createGain();
    this.duck.connect(this.in);
    this.in.connect(this.filter); this.filter.connect(this.post);
    this.post.connect(this.gate); this.gate.connect(this.out); this.out.connect(dest);
    // dotted-eighth echo on the cowbell (tails live behind the gate like everything else)
    this.echoSend = ctx.createGain();
    const dl = ctx.createDelay(2); dl.delayTime.value = this.beat * 0.75;
    const fb = ctx.createGain(); fb.gain.value = 0.3;
    const tone = ctx.createBiquadFilter(); tone.type = 'lowpass'; tone.frequency.value = 2800;
    const wet = ctx.createGain(); wet.gain.value = 0.14;
    const pan = ctx.createStereoPanner(); pan.pan.value = 0.35;
    this.echoSend.connect(dl); dl.connect(tone); tone.connect(fb); fb.connect(dl);
    tone.connect(wet); wet.connect(pan); pan.connect(this.duck);
    this._internal = [this.duck, this.in,this.filter, this.post, this.gate, this.echoSend, dl, fb, tone, wet, pan];
  }

  time(bar, s) { return this.t0 + (bar * 16 + s) * this.six; }
  get stopped() { return this.stopAt !== Infinity; }

  schedule(until) {
    for (;;) {
      if (!this.events) { this.events = planBar(this.bar, this.mode); this.ei = 0; }
      const ev = this.events[this.ei];
      if (!ev) {
        this.bar++;
        this.events = null;
        if (this.time(this.bar, 0) >= until || this.time(this.bar, 0) >= this.stopAt) return;
        continue;
      }
      let t = this.time(this.bar, ev.s);
      if (t >= until || t >= this.stopAt) return;
      this.ei++;
      if (this.mute && this.mute.has(ev.k)) continue;
      const auto = ev.k === 'lp' || ev.k === 'lpset' || ev.k === 'gate';
      if (this.lateSkip && !auto && t < this.ctx.currentTime - 0.004) continue; // late = off-beat; drop it
      const t1 = Math.max(0, this.time(this.bar, ev.s + (ev.d || 0)));
      if (t < 0) t = 0; // float dust at the very start of an offline render
      try { this.play(ev, t, t1); } catch (e) { console.warn('[phonk]', ev.k, e); }
    }
  }

  play(ev, t, t1) {
    const c = this.ctx, d = this.in;
    switch (ev.k) {
      case 'cb': vCowbell(c, this.duck, t, ev.n, ev.v, this.echoSend); break;
      case 'bass': vBass(c, this.duck, t, ev.n, t1 - t, ev.slide ?? null, 1, ev.s % 4 === 0); break;
      case 'kick': vKick(c, d, t, ev.v ?? 1); this._pump(t, 0.35); break;
      case 'clap': vClap(c, d, t, ev.v ?? 1); break;
      case 'snare': vSnare(c, d, t, ev.v ?? 1); break;
      case 'hat': vHat(c, d, t, ev.v ?? 1, !!ev.open, ev.tone ?? 1, hash(this._nz++, 5) * 1.5); break;
      case 'crash': vCrash(c, d, t, ev.v ?? 1); break;
      case 'boom': vSubBoom(c, d, t, ev.v ?? 1); break;
      case 'riser': vRiser(c, this.post, t, t1 - t); break;
      case 'pad': vPad(c, d, t, t1 - t); break;
      case 'lp': {
        const f = this.filter.frequency;
        f.setValueAtTime(ev.from, t);
        f.exponentialRampToValueAtTime(ev.to, t1);
        break;
      }
      case 'lpset': this.filter.frequency.setValueAtTime(ev.to, t); break;
      case 'gate': {
        const g = this.gate.gain;
        g.setValueAtTime(1, Math.max(0, t - 0.006));
        g.linearRampToValueAtTime(0, t);
        g.setValueAtTime(1, t1); // reopens exactly on the drop
        break;
      }
    }
  }

  _pump(t, depth) {
    const g = this.duck.gain;
    g.setTargetAtTime(depth, t, 0.002);
    g.setTargetAtTime(1, t + 0.012, 0.075);
  }

  // Fade out from `at`. Keeps scheduling until the fade has finished.
  stop(at, fade = 1.5) {
    fade = Math.max(0.004, fade);
    if (at + fade >= this.stopAt) return;
    this.stopAt = at + fade;
    const g = this.out.gain;
    g.cancelScheduledValues(at);
    g.setValueAtTime(g.value, at);
    g.linearRampToValueAtTime(0, at + fade);
    this._disposeAt(at + fade + 0.3);
  }
  cut(at) { this.stop(at, 0.004); }

  _disposeAt(t) {
    if (typeof setTimeout !== 'function' || this.ctx instanceof OfflineAudioContext) return;
    const ms = Math.max(0, (t - this.ctx.currentTime) * 1000);
    clearTimeout(this._disp);
    this._disp = setTimeout(() => {
      try { this.out.disconnect(); } catch (e) { /* already gone */ }
      for (const n of this._internal) { try { n.disconnect(); } catch (e) { /* ok */ } }
    }, ms);
  }
}

// Render the code phonk offline with the same voices, master chain and form:
// lead-in from t = 0, so the drop is at exactly 2 bars.
export async function renderPhonkOffline(seconds = 20, bpm = PHONK_BPM, sampleRate = 44100, mute = null) {
  const ctx = new OfflineAudioContext(2, Math.ceil(seconds * sampleRate), sampleRate);
  const master = buildMaster(ctx);
  const dropAt = (LEAD_BARS * 4 * 60) / bpm;
  const p = new PhonkPlayer(ctx, master.input, { bpm, mode: 'song', t0: dropAt, lateSkip: false, mute });
  p.schedule(seconds);
  return ctx.startRendering();
}
