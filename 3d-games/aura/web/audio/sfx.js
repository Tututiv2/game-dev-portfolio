// Every sound effect and the ambient dusk bed, synthesised. No asset files.
//
// A voice is built from three primitives -- tone(), noise(), and a shared
// post chain (drive / filters / formants) -- all of which read two numbers off
// the voice: P (frequency multiplier: opts.pitch x slow-mo) and S (time
// stretch from slow-mo). So setSlowmo() reaches every SFX without any of them
// knowing it exists.

import { noiseBuffers, satCurve } from './phonk.js';

const clamp = (x, a, b) => Math.min(b, Math.max(a, x));
const rand = (a, b) => a + Math.random() * (b - a);

// Stereo decaying-noise impulse response, deterministic. Used for the SFX
// room and for the music's long tail (engine.boom).
export function makeImpulse(ctx, seconds = 1.5, decay = 3, seed = 7) {
  const sr = ctx.sampleRate, len = Math.max(1, Math.floor(sr * seconds));
  const b = ctx.createBuffer(2, len, sr);
  let s = (0x9e3779b9 ^ seed) | 0;
  for (let ch = 0; ch < 2; ch++) {
    const d = b.getChannelData(ch);
    for (let i = 0; i < len; i++) {
      s ^= s << 13; s ^= s >>> 17; s ^= s << 5;
      const r = ((s >>> 0) / 4294967296) * 2 - 1;
      const k = i / len;
      d[i] = r * Math.pow(1 - k, decay) * (i < sr * 0.004 ? i / (sr * 0.004) : 1);
    }
  }
  return b;
}

// ---------------------------------------------------------------- primitives

function newVoice(E, opts) {
  const ctx = E.ctx;
  const rate = clamp(E.slowmo || 1, 0.15, 1);
  const jitter = 1 + (Math.random() - 0.5) * 0.05; // no two swings identical
  const P = (opts.pitch ?? 1) * Math.pow(rate, 0.55) * jitter;
  const S = Math.pow(rate, -0.8);
  const t = Math.max(ctx.currentTime, opts.at ?? 0);
  const out = ctx.createGain(); out.gain.value = opts.vol ?? 1;
  const pan = ctx.createStereoPanner(); pan.pan.value = clamp(opts.pan ?? 0, -1, 1);
  out.connect(pan); pan.connect(E.sfxIn);
  const N = noiseBuffers(ctx);
  return { ctx, t, P, S, out, pan, verb: E.verbIn, white: N.white, brown: N.brown, end: t, nyq: ctx.sampleRate / 2 - 100 };
}

function env(param, t0, peak, a, h, d) {
  param.setValueAtTime(0, t0);
  param.linearRampToValueAtTime(peak, t0 + Math.max(0.0005, a));
  if (h > 0) param.setValueAtTime(peak, t0 + a + h);
  param.exponentialRampToValueAtTime(0.0001, t0 + a + h + Math.max(0.005, d));
}

// Optional post chain: drive -> lp -> hp -> formants. Returns the last node.
function post(V, node, o) {
  const { ctx } = V;
  let last = node;
  if (o.drive) { const sh = ctx.createWaveShaper(); sh.curve = satCurve(o.drive); last.connect(sh); last = sh; }
  if (o.lp) { const f = ctx.createBiquadFilter(); f.type = 'lowpass'; f.frequency.value = Math.min(V.nyq, o.lp); last.connect(f); last = f; }
  if (o.hp) { const f = ctx.createBiquadFilter(); f.type = 'highpass'; f.frequency.value = Math.min(V.nyq, o.hp); last.connect(f); last = f; }
  if (o.formants) {
    const sum = ctx.createGain();
    for (const [f, q, g] of o.formants) {
      const bp = ctx.createBiquadFilter(); bp.type = 'bandpass';
      bp.frequency.value = Math.min(V.nyq, f * Math.sqrt(V.P)); bp.Q.value = q;
      const fg = ctx.createGain(); fg.gain.value = g;
      last.connect(bp); bp.connect(fg); fg.connect(sum);
    }
    last = sum;
  }
  return last;
}

function tone(V, o) {
  const { ctx, P, S } = V;
  const at = V.t + (o.at || 0) * S;
  const a = (o.a ?? 0.002) * S, h = (o.h || 0) * S, d = (o.d ?? 0.2) * S;
  const end = at + a + h + d + 0.03;
  const osc = ctx.createOscillator(); osc.type = o.type || 'sine';
  osc.frequency.setValueAtTime(clamp(o.f * P, 1, V.nyq), at);
  if (o.f1) osc.frequency.exponentialRampToValueAtTime(clamp(o.f1 * P, 1, V.nyq), at + (o.glide ?? 0.1) * S);
  if (o.detune) osc.detune.value = o.detune;
  if (o.lfo) {
    const l = ctx.createOscillator(); l.frequency.value = o.lfo[0] / S;
    const lg = ctx.createGain(); lg.gain.value = o.lfo[1] * P;
    l.connect(lg); lg.connect(osc.frequency);
    l.start(at); l.stop(end);
  }
  const g = ctx.createGain();
  env(g.gain, at, o.g ?? 0.5, a, h, d);
  osc.connect(g);
  post(V, g, o).connect(o.to || V.out);
  osc.start(at); osc.stop(end);
  V.end = Math.max(V.end, end);
  return g;
}

function noise(V, o) {
  const { ctx, P, S } = V;
  const at = V.t + (o.at || 0) * S;
  const a = (o.a ?? 0.002) * S, h = (o.h || 0) * S, d = (o.d ?? 0.2) * S;
  const end = at + a + h + d + 0.03;
  const src = ctx.createBufferSource(); src.buffer = o.brown ? V.brown : V.white; src.loop = true;
  const fl = ctx.createBiquadFilter(); fl.type = o.type || 'bandpass'; fl.Q.value = o.q ?? 1;
  fl.frequency.setValueAtTime(clamp((o.f ?? 1000) * P, 20, V.nyq), at);
  if (o.f1) fl.frequency.exponentialRampToValueAtTime(clamp(o.f1 * P, 20, V.nyq), at + (o.glide ?? 0.1) * S);
  const g = ctx.createGain();
  env(g.gain, at, o.g ?? 0.5, a, h, d);
  src.connect(fl); fl.connect(g);
  post(V, g, o).connect(o.to || V.out);
  src.start(at, Math.random() * 1.5); src.stop(end);
  V.end = Math.max(V.end, end);
  return g;
}

// Inharmonic partials: parts = [[ratio, gain, decay], ...]
function ring(V, { f, parts, at = 0, to = null, a = 0.001, type = 'sine' }) {
  for (const [r, g, d] of parts) tone(V, { type, f: f * r, at, a, d, g, to });
}

// A node that feeds both the dry voice output and the reverb.
function bus(V, wet) {
  const b = V.ctx.createGain();
  const w = V.ctx.createGain(); w.gain.value = wet;
  b.connect(V.out); b.connect(w); w.connect(V.verb);
  return b;
}

function heartbeat(V, at = 0, g = 1) {
  const lp = V.ctx.createBiquadFilter(); lp.type = 'lowpass'; lp.frequency.value = 220; lp.connect(V.out);
  tone(V, { f: 62, f1: 42, glide: 0.1, at, a: 0.006, d: 0.14, g: g, to: lp });
  tone(V, { type: 'triangle', f: 124, f1: 84, glide: 0.1, at, a: 0.006, d: 0.08, g: 0.25 * g, to: lp });
  tone(V, { f: 58, f1: 40, glide: 0.1, at: at + 0.19, a: 0.006, d: 0.13, g: 0.75 * g, to: lp });
  tone(V, { type: 'triangle', f: 116, f1: 80, glide: 0.1, at: at + 0.19, a: 0.006, d: 0.07, g: 0.18 * g, to: lp });
}

// ---------------------------------------------------------------- the sounds

export const SFX = {
  swing(V) {
    noise(V, { f: 700, f1: 2600, glide: 0.16, q: 1.4, a: 0.03, d: 0.17, g: 0.55 });
    tone(V, { type: 'triangle', f: 1800, f1: 3200, glide: 0.15, a: 0.01, d: 0.12, g: 0.035 });
  },
  swingHeavy(V) {
    noise(V, { f: 350, f1: 1500, glide: 0.28, q: 1.1, a: 0.06, d: 0.28, g: 0.7 });
    noise(V, { type: 'lowpass', f: 500, q: 0.7, a: 0.05, d: 0.3, g: 0.3 });
    tone(V, { f: 90, f1: 60, glide: 0.25, a: 0.05, d: 0.25, g: 0.25 });
  },
  hit(V) {
    tone(V, { f: 140, f1: 50, glide: 0.08, a: 0.001, d: 0.16, g: 0.9, drive: 2 });
    noise(V, { type: 'lowpass', f: 2200, f1: 600, glide: 0.08, q: 0.7, a: 0.001, d: 0.09, g: 0.7 });
    noise(V, { type: 'highpass', f: 3000, q: 0.7, a: 0.0005, d: 0.02, g: 0.4 });
  },
  slice(V) {
    noise(V, { f: 5000, f1: 8000, glide: 0.1, q: 1, a: 0.004, d: 0.12, g: 0.6, hp: 3500 });
    tone(V, { type: 'triangle', f: 3000, f1: 5200, glide: 0.08, a: 0.001, d: 0.09, g: 0.06 });
    tone(V, { f: 160, f1: 60, glide: 0.08, a: 0.001, d: 0.1, g: 0.5 });
    noise(V, { f: 900, q: 0.8, a: 0.003, d: 0.15, g: 0.25 });
  },
  sever(V) {
    const b = bus(V, 0.2);
    noise(V, { f: 4500, f1: 9000, glide: 0.12, q: 0.9, a: 0.003, d: 0.16, g: 0.75, hp: 3000, to: b });
    tone(V, { type: 'triangle', f: 2600, f1: 5600, glide: 0.1, a: 0.001, d: 0.12, g: 0.08, to: b });
    tone(V, { f: 110, f1: 38, glide: 0.2, a: 0.001, d: 0.32, g: 1, drive: 3, to: b });
    noise(V, { type: 'lowpass', f: 1200, q: 0.7, a: 0.002, d: 0.35, g: 0.5 });
    noise(V, { f: 520, f1: 280, glide: 0.4, q: 1.5, at: 0.04, a: 0.02, d: 0.45, g: 0.45 });
    noise(V, { f: 1400, f1: 700, glide: 0.3, q: 2.5, at: 0.08, a: 0.01, d: 0.3, g: 0.2 });
  },
  clang(V) {
    const b = bus(V, 0.15);
    ring(V, { f: 620, to: b, parts: [[1, 0.5, 0.5], [2.76, 0.35, 0.35], [5.4, 0.25, 0.25], [8.93, 0.15, 0.15]] });
    noise(V, { type: 'highpass', f: 2000, q: 0.7, a: 0.0005, d: 0.03, g: 0.5, to: b });
  },
  // THE aura sound: bright inharmonic ring with a shimmer, a sub boom, a room.
  clangPerfect(V) {
    const b = bus(V, 0.55);
    const f = 988;
    ring(V, { f, to: b, parts: [[1, 0.55, 1.4], [1.506, 0.3, 1.1], [2.0, 0.35, 1.2], [2.76, 0.3, 0.9],
      [3.52, 0.18, 0.7], [5.4, 0.16, 0.5], [6.8, 0.1, 0.35], [8.93, 0.08, 0.25]] });
    ring(V, { f: f * 1.004, to: b, parts: [[1, 0.25, 1.3], [2.0, 0.18, 1.0]] }); // beating shimmer
    ring(V, { f: f * 2, at: 0.012, to: b, parts: [[1, 0.12, 0.8], [1.5, 0.08, 0.6]] });
    noise(V, { type: 'highpass', f: 3000, q: 0.7, a: 0.0005, d: 0.05, g: 0.8, to: b });
    tone(V, { f: 85, f1: 34, glide: 0.5, a: 0.002, d: 0.9, g: 1.0, drive: 2 });
    noise(V, { type: 'lowpass', f: 300, q: 0.7, a: 0.001, d: 0.2, g: 0.4 });
  },
  block(V) {
    const b = bus(V, 0.1);
    ring(V, { f: 330, to: b, parts: [[1, 0.4, 0.18], [2.76, 0.25, 0.12], [5.4, 0.12, 0.08]] });
    noise(V, { f: 900, q: 1, a: 0.001, d: 0.08, g: 0.6 });
    tone(V, { f: 120, f1: 60, glide: 0.1, a: 0.001, d: 0.12, g: 0.6 });
  },
  whoosh(V) {
    noise(V, { f: 400, f1: 1800, glide: 0.35, q: 1, a: 0.12, d: 0.3, g: 0.6 });
    noise(V, { type: 'highpass', f: 2000, q: 0.7, a: 0.1, d: 0.2, g: 0.1 });
  },
  jump(V) {
    noise(V, { f: 600, f1: 300, glide: 0.12, q: 1, a: 0.005, d: 0.12, g: 0.35 });
    tone(V, { f: 180, f1: 330, glide: 0.1, a: 0.005, d: 0.1, g: 0.15 });
  },
  land(V) {
    tone(V, { f: 110, f1: 45, glide: 0.1, a: 0.001, d: 0.18, g: 0.8 });
    noise(V, { type: 'lowpass', f: 900, q: 0.7, a: 0.001, d: 0.18, g: 0.45 });
    noise(V, { f: 2500, q: 0.8, a: 0.001, d: 0.08, g: 0.15 });
  },
  step(V) {
    noise(V, { f: rand(1500, 2400), q: 1.2, a: 0.002, d: 0.05, g: 0.35 });
    tone(V, { f: 90, f1: 60, glide: 0.05, a: 0.001, d: 0.05, g: 0.25 });
  },
  stepHeavy(V) {
    tone(V, { f: 60, f1: 28, glide: 0.3, a: 0.004, d: 0.45, g: 1, drive: 2 });
    noise(V, { type: 'lowpass', f: 280, q: 0.7, a: 0.005, d: 0.4, g: 0.8 });
    noise(V, { f: 1200, q: 1, a: 0.002, d: 0.12, g: 0.12 });
  },
  goblinGrowl(V) {
    const f = rand(85, 110);
    const fm = [[650, 3, 1], [1100, 4, 0.6]];
    tone(V, { type: 'sawtooth', f, f1: f * 0.85, glide: 0.6, a: 0.08, h: 0.1, d: 0.45, g: 0.5, lfo: [22, 12], formants: fm, drive: 2 });
    noise(V, { f: 700, q: 1.5, a: 0.08, h: 0.1, d: 0.4, g: 0.12 });
  },
  goblinAttack(V) {
    const fm = [[900, 4, 1], [1800, 5, 0.6]];
    tone(V, { type: 'sawtooth', f: 380, f1: 640, glide: 0.12, a: 0.02, h: 0.12, d: 0.2, g: 0.45, lfo: [30, 30], formants: fm, drive: 3 });
    noise(V, { f: 2500, q: 2, a: 0.02, d: 0.25, g: 0.15 });
  },
  goblinDie(V) {
    const fm = [[700, 3, 1], [1200, 4, 0.5]];
    tone(V, { type: 'sawtooth', f: 300, f1: 70, glide: 0.7, a: 0.02, h: 0.1, d: 0.6, g: 0.45, lfo: [17, 40], formants: fm, drive: 2 });
    noise(V, { f: 500, f1: 250, glide: 0.5, q: 2, a: 0.03, d: 0.5, g: 0.2 });
    tone(V, { f: 90, f1: 40, glide: 0.1, at: 0.5, a: 0.002, d: 0.2, g: 0.6 });
    noise(V, { type: 'lowpass', f: 700, q: 0.7, at: 0.5, a: 0.002, d: 0.2, g: 0.4 });
  },
  ogreRoar(V) {
    const b = bus(V, 0.2);
    const fm = [[500, 2.5, 1], [850, 3, 0.8], [1500, 4, 0.4]];
    for (const [f, dt] of [[62, 0], [62.6, 7], [93, -5]]) {
      tone(V, { type: 'sawtooth', f, f1: f * 0.88, glide: 1.6, detune: dt, a: 0.25, h: 0.7, d: 0.9, g: 0.45, lfo: [7, 3], formants: fm, drive: 4, to: b });
    }
    noise(V, { f: 700, f1: 500, glide: 1.5, q: 0.7, a: 0.3, h: 0.6, d: 0.8, g: 0.35, to: b });
    tone(V, { f: 45, f1: 38, glide: 1.6, a: 0.2, h: 0.6, d: 0.9, g: 0.5 });
  },
  ogreSwing(V) {
    noise(V, { f: 180, f1: 700, glide: 0.5, q: 1, a: 0.25, d: 0.35, g: 0.9 });
    noise(V, { type: 'lowpass', f: 300, q: 0.7, a: 0.2, d: 0.4, g: 0.5 });
    tone(V, { f: 55, f1: 40, glide: 0.5, a: 0.2, d: 0.3, g: 0.3 });
  },
  ogreImpact(V) {
    const b = bus(V, 0.25);
    tone(V, { f: 70, f1: 24, glide: 0.6, a: 0.002, d: 1.1, g: 1.1, drive: 4 });
    noise(V, { type: 'lowpass', f: 1400, f1: 200, glide: 0.5, q: 0.7, a: 0.002, d: 0.6, g: 1.0, to: b });
    noise(V, { f: 3000, q: 0.6, a: 0.001, d: 0.25, g: 0.3, to: b });
    for (let i = 0; i < 8; i++) noise(V, { f: rand(1500, 4000), q: 2, at: rand(0.05, 0.6), a: 0.001, d: 0.03, g: 0.15 });
  },
  heartbeat(V) { heartbeat(V); },
  getUpPulse(V) {
    heartbeat(V);
    noise(V, { f: 200, f1: 2400, glide: 0.55, q: 1.5, a: 0.45, d: 0.12, g: 0.55 });
    tone(V, { f: 180, f1: 520, glide: 0.5, a: 0.45, d: 0.1, g: 0.12 });
  },
  auraBurst(V) {
    const { ctx, S } = V;
    const b = bus(V, 0.35);
    const lp = ctx.createBiquadFilter(); lp.type = 'lowpass'; lp.Q.value = 2;
    lp.frequency.setValueAtTime(300, V.t);
    lp.frequency.exponentialRampToValueAtTime(Math.min(V.nyq, 7000), V.t + 0.35 * S);
    lp.connect(b);
    for (const f of [130.8, 196, 261.6, 311.1]) {
      for (const dt of [-8, 8]) tone(V, { type: 'sawtooth', f, detune: dt, a: 0.03, h: 0.25, d: 0.8, g: 0.12, to: lp });
    }
    tone(V, { f: 90, f1: 32, glide: 0.7, a: 0.002, d: 1.0, g: 1, drive: 2.5 });
    noise(V, { f: 800, f1: 6000, glide: 0.3, q: 1, a: 0.01, d: 0.5, g: 0.5, to: b });
    ring(V, { f: 1568, at: 0.05, to: b, parts: [[1, 0.12, 0.9], [2.0, 0.08, 0.7], [2.76, 0.05, 0.5]] });
  },
  uiTick(V) {
    tone(V, { f: 1800, a: 0.001, d: 0.03, g: 0.25 });
    tone(V, { type: 'square', f: 3600, a: 0.001, d: 0.01, g: 0.03 });
  },
  uiSelect(V) {
    tone(V, { type: 'triangle', f: 880, a: 0.002, d: 0.07, g: 0.35 });
    tone(V, { type: 'triangle', f: 1320, at: 0.06, a: 0.002, d: 0.12, g: 0.35 });
  },
  flowUp(V) {
    tone(V, { f: 1200, a: 0.001, d: 0.045, g: 0.3 });
    tone(V, { f: 2400, a: 0.001, d: 0.02, g: 0.08 });
  },
  flowBreak(V) {
    const lp = V.ctx.createBiquadFilter(); lp.type = 'lowpass'; lp.frequency.value = 500; lp.connect(V.out);
    tone(V, { type: 'triangle', f: 110, f1: 70, glide: 0.2, a: 0.003, d: 0.3, g: 0.6, to: lp });
    tone(V, { type: 'triangle', f: 117, f1: 66, glide: 0.2, a: 0.003, d: 0.3, g: 0.5, to: lp });
    noise(V, { type: 'lowpass', f: 400, q: 0.7, a: 0.002, d: 0.15, g: 0.4 });
  },
  // Aura Reset: a rising two-note crystal "tink", like a flip reset.
  reset(V) {
    const b = bus(V, 0.3);
    const parts = [[1, 0.28, 0.3], [2.0, 0.1, 0.2], [2.76, 0.08, 0.15], [4.1, 0.05, 0.1]];
    for (const [r, g, d] of parts) tone(V, { f: 1700 * r, f1: 1760 * r, glide: 0.02, a: 0.001, d, g, to: b });
    for (const [r, g, d] of parts) tone(V, { f: 2560 * r, f1: 2637 * r, glide: 0.025, at: 0.055, a: 0.001, d: d * 1.6, g, to: b });
    noise(V, { type: 'highpass', f: 8000, q: 0.7, at: 0.055, a: 0.001, d: 0.06, g: 0.12, to: b });
  },
  throw(V) {
    noise(V, { f: 500, f1: 1400, glide: 0.2, q: 1, a: 0.02, d: 0.18, g: 0.45 });
    tone(V, { f: 250, f1: 180, glide: 0.1, a: 0.005, d: 0.1, g: 0.15 });
    noise(V, { f: 900, f1: 600, glide: 0.15, q: 1, at: 0.12, a: 0.01, d: 0.15, g: 0.25 });
  },
  deflect(V) {
    const b = bus(V, 0.2);
    tone(V, { f: 2600, f1: 1700, glide: 0.25, a: 0.001, d: 0.3, g: 0.25, to: b });
    tone(V, { type: 'triangle', f: 5200, f1: 3400, glide: 0.2, a: 0.001, d: 0.15, g: 0.06, to: b });
    ring(V, { f: 2300, to: b, parts: [[1, 0.2, 0.25], [2.76, 0.1, 0.15]] });
    noise(V, { type: 'highpass', f: 3000, q: 0.7, a: 0.0005, d: 0.03, g: 0.5 });
  },
  bodyFall(V) {
    tone(V, { f: 90, f1: 40, glide: 0.12, a: 0.002, d: 0.25, g: 0.9, drive: 1.5 });
    noise(V, { type: 'lowpass', f: 700, q: 0.7, a: 0.002, d: 0.25, g: 0.6 });
    noise(V, { f: 2000, q: 1, a: 0.002, d: 0.15, g: 0.12 });
    tone(V, { f: 80, f1: 45, glide: 0.1, at: 0.22, a: 0.002, d: 0.12, g: 0.35 });
    noise(V, { type: 'lowpass', f: 600, q: 0.7, at: 0.22, a: 0.002, d: 0.12, g: 0.25 });
  },
  impactFrame(V) {
    const b = bus(V, 0.2);
    noise(V, { type: 'highpass', f: 200, q: 0.5, a: 0.0005, d: 0.06, g: 1.0, to: b });
    tone(V, { f: 120, f1: 28, glide: 0.35, a: 0.001, d: 0.6, g: 1.2, drive: 5 });
    noise(V, { type: 'lowpass', f: 3000, f1: 300, glide: 0.2, q: 0.7, a: 0.001, d: 0.3, g: 0.7, to: b });
    tone(V, { type: 'square', f: 180, a: 0.0005, d: 0.04, g: 0.3 });
  },
  whiteout(V) {
    const b = bus(V, 0.4);
    tone(V, { f: 900, f1: 7800, glide: 1.1, a: 0.1, d: 1.4, g: 0.12, to: b });
    noise(V, { f: 2000, f1: 9000, glide: 1.2, q: 1, a: 0.6, d: 1.0, g: 0.35, to: b });
    tone(V, { f: 4200, a: 0.6, d: 1.2, g: 0.05, to: b });
  },
};

export const SFX_NAMES = Object.keys(SFX);

// E = { ctx, sfxIn, verbIn, slowmo }. opts = { vol, pan, pitch, at }.
export function playSfx(E, name, opts = {}) {
  const fn = SFX[name];
  if (!fn) { console.warn(`[audio] unknown sfx "${name}"`); return; }
  const V = newVoice(E, opts || {});
  fn(V);
  if (typeof setTimeout === 'function' && !(V.ctx instanceof OfflineAudioContext)) {
    setTimeout(() => { try { V.out.disconnect(); V.pan.disconnect(); } catch (e) { /* ok */ } },
      (V.end - V.ctx.currentTime + 0.5) * 1000);
  }
  return V.end;
}

// ---------------------------------------------------------------- ambient bed

// Quiet dusk: wind (filtered brown noise with slow LFOs), a howl, leaf rustle
// with gusts, and crickets chirping at random intervals.
export class AmbientBed {
  constructor(ctx, dest) {
    this.ctx = ctx;
    this.target = 0;
    this.level = ctx.createGain(); this.level.gain.value = 0;
    this.level.connect(dest);
    const N = noiseBuffers(ctx);
    const loop = (buf, off) => { const s = ctx.createBufferSource(); s.buffer = buf; s.loop = true; s.start(0, off); return s; };
    const lfo = (rate, depth, param) => {
      const o = ctx.createOscillator(); o.frequency.value = rate;
      const g = ctx.createGain(); g.gain.value = depth;
      o.connect(g); g.connect(param); o.start();
    };
    // wind
    const wbp = ctx.createBiquadFilter(); wbp.type = 'bandpass'; wbp.frequency.value = 450; wbp.Q.value = 0.7;
    const wg = ctx.createGain(); wg.gain.value = 0.55;
    loop(N.brown, 0).connect(wbp); wbp.connect(wg); wg.connect(this.level);
    lfo(0.063, 220, wbp.frequency);
    lfo(0.11, 0.22, wg.gain);
    // howl
    const hbp = ctx.createBiquadFilter(); hbp.type = 'bandpass'; hbp.frequency.value = 900; hbp.Q.value = 9;
    const hg = ctx.createGain(); hg.gain.value = 0.05;
    loop(N.brown, 2.7).connect(hbp); hbp.connect(hg); hg.connect(this.level);
    lfo(0.047, 300, hbp.frequency);
    lfo(0.09, 0.04, hg.gain);
    // leaves
    const lhp = ctx.createBiquadFilter(); lhp.type = 'highpass'; lhp.frequency.value = 2800;
    const lbp = ctx.createBiquadFilter(); lbp.type = 'bandpass'; lbp.frequency.value = 5500; lbp.Q.value = 0.6;
    const lg = ctx.createGain(); lg.gain.value = 0.018;
    this.gust = ctx.createGain(); this.gust.gain.value = 0;
    loop(N.white, 0.5).connect(lhp); lhp.connect(lbp); lbp.connect(lg); lbp.connect(this.gust);
    lg.connect(this.level); this.gust.connect(this.level);
    lfo(0.21, 0.012, lg.gain);
    this.crickets = [
      { f: 4400, pan: -0.5, next: 0, n: 0 },
      { f: 4850, pan: 0.45, next: 0, n: 3 },
      { f: 4100, pan: 0.1, next: 0, n: 7 },
    ];
    this.nextGust = 0;
  }

  set(level, ramp = 1) {
    const now = this.ctx.currentTime;
    this.target = clamp(level, 0, 1);
    this.level.gain.cancelScheduledValues(now);
    this.level.gain.setTargetAtTime(this.target, now, Math.max(0.01, ramp / 3));
  }

  tick(now, until) {
    if (this.target < 0.02) return;
    for (const c of this.crickets) {
      if (c.next < now) c.next = now + rand(0.05, 0.6);
      while (c.next < until) {
        this.chirp(c, c.next);
        c.n++;
        c.next += c.n % 12 === 0 ? rand(2.5, 5.5) : rand(0.45, 1.35);
      }
    }
    if (this.nextGust < now) this.nextGust = now + rand(1, 4);
    if (this.nextGust < until) {
      const t = this.nextGust, g = this.gust.gain;
      g.setTargetAtTime(rand(0.04, 0.09), t, 0.25);
      g.setTargetAtTime(0, t + rand(0.6, 1.4), 0.5);
      this.nextGust = t + rand(3, 9);
    }
  }

  chirp(c, t) {
    const ctx = this.ctx;
    const o = ctx.createOscillator(); o.frequency.value = c.f * rand(0.985, 1.015);
    const g = ctx.createGain(); g.gain.value = 0;
    const p = ctx.createStereoPanner(); p.pan.value = c.pan;
    const pulses = 3 + Math.floor(Math.random() * 3);
    const amp = rand(0.025, 0.045);
    for (let i = 0; i < pulses; i++) {
      const ti = t + i * 0.032;
      g.gain.setValueAtTime(0, ti);
      g.gain.linearRampToValueAtTime(amp, ti + 0.004);
      g.gain.linearRampToValueAtTime(0, ti + 0.016);
    }
    o.connect(g); g.connect(p); p.connect(this.level);
    o.start(t); o.stop(t + pulses * 0.032 + 0.02);
  }
}
