// Parameter smoothing.
//
// Every acoustic number the engine ever applies passes through here first. Two reasons:
//
//  1. Nothing may snap. A filter cutoff that jumps when you cross a doorframe reads as a bug and
//     kills the effect outright (BUILD_PROMPT §3.6).
//  2. The smoothed values live in plain JS, not inside the audio graph, so the continuity test can
//     sample them frame by frame with no browser in sight.
//
// Cutoffs are smoothed in LOG space. A linear glide from 700 Hz to 18 kHz spends most of its time
// in the top octave, where the ear notices least, and lurches through the bottom where it notices
// most. In octaves it sounds like one even sweep.

const TAU_GAIN = 0.09;
const TAU_CUT = 0.11;
const TAU_ROOF = 0.18; // a big soft layer; it should swell rather than appear
const TAU_SLOW = 0.35; // reverb — changing room size should feel like walking, not switching

const GAIN_FLOOR = 1e-4; // −80 dB

function approach(cur, target, dt, tau) {
  if (tau <= 0) return target;
  return cur + (target - cur) * (1 - Math.exp(-dt / tau));
}

function approachLog(cur, target, dt, tau) {
  const c = Math.log2(Math.max(cur, 20));
  const t = Math.log2(Math.max(target, 20));
  const step = MAX_CUT_SLEW * dt;
  return Math.pow(2, clamp(approach(c, t, dt, tau), c - step, c + step));
}


// Hard ceilings on how fast anything may move, in real time. These are what actually guarantee
// check 8, and they are frame-rate independent: at 60 fps they work out at 1.1 dB and 8% per frame.
const MAX_GAIN_SLEW = 66;  // dB per second
const MAX_CUT_SLEW = 7;    // octaves per second
const NEPER_DB = 8.685889638;

const clamp = (v, a, b) => (v < a ? a : v > b ? b : v);

/**
 * Gains smooth in LOG space AND are slew-limited, and both halves were paid for.
 *
 * Linear smoothing looks symmetric and is not: falling from 1.0 to 0.08 lands gently, but RISING
 * from 0.08 to 1.0 the first step is 0.08 → 0.24, a 9.4 dB lurch in one frame. Walking into the
 * cabin was smooth and walking back out clicked, from the same code.
 *
 * Switching to log space fixed the asymmetry and introduced the opposite fault: constant-ratio
 * smoothing moves at a constant number of dB per frame, so a 79 dB gap (an emitter waking from
 * the −80 dB floor) opens with a 13 dB step. Exponential in log space is the right SHAPE; the
 * slew limit is what makes it a promise.
 */
function approachGain(cur, target, dt, tau) {
  const c = Math.log(Math.max(cur, GAIN_FLOOR));
  const t = Math.log(Math.max(target, GAIN_FLOOR));
  const step = (MAX_GAIN_SLEW * dt) / NEPER_DB;
  const next = clamp(approach(c, t, dt, tau), c - step, c + step);
  const v = Math.exp(next);
  return v <= GAIN_FLOOR * 1.5 && target < GAIN_FLOOR ? target : v;
}

export class SmoothedAcoustics {
  constructor() {
    this.diffuseGain = 0;
    this.diffuseCutoff = 20000;
    this.roofGain = 0;
    this.windGain = 0;
    this.windCutoff = 20000;
    this.rt60 = 0.3;
    this.wet = 0.1;
    this.outsideWeight = 1;
    /** @type {Map<object, {gain,cutoff,whistleGain,whistleFreq,pos,pan,distance,name,alive}>} */
    this.apertures = new Map();
    this.primed = false;
  }

  /**
   * Jump straight to the target — used once at boot so the first frame is not a fade-in.
   * It has to assign directly rather than call update() with a huge dt: the slew limits are
   * per-second, so a big dt no longer converges, it just takes one big legal step.
   */
  prime(frame) {
    this.diffuseGain = frame.diffuse.gain;
    this.diffuseCutoff = frame.diffuse.cutoff;
    this.roofGain = frame.roof.gain;
    this.windGain = frame.wind.gain;
    this.windCutoff = frame.wind.cutoff;
    this.rt60 = frame.reverb.rt60;
    this.wet = frame.reverb.wet;
    this.outsideWeight = frame.outsideWeight;
    this.apertures.clear();
    for (const a of frame.apertures) {
      this.apertures.set(a.opening, {
        name: a.name, gain: a.gain, cutoff: a.cutoff,
        whistleGain: a.whistleGain, whistleFreq: a.whistleFreq,
        pos: a.pos.slice(), pan: a.pan, distance: a.distance, alive: true,
      });
    }
    this.primed = true;
  }

  /**
   * Every scalar the engine will actually apply, flat and named.
   *
   * This is what continuity is asserted on, rather than a derived figure like an energy-weighted
   * mean cutoff — that statistic can swing faster than any of its inputs when two sources of very
   * different brightness trade places, which looks alarming in a log and is inaudible, because the
   * audio graph has two separate filters whose gains are crossfading smoothly.
   */
  params() {
    const p = new Map();
    p.set('diffuse', { gain: this.diffuseGain, cutoff: this.diffuseCutoff });
    p.set('wind', { gain: this.windGain, cutoff: this.windCutoff });
    p.set('roof', { gain: this.roofGain, cutoff: 1800 });
    for (const s of this.apertures.values()) {
      p.set(`portal:${s.name}`, { gain: s.gain, cutoff: s.cutoff });
      p.set(`whistle:${s.name}`, { gain: s.whistleGain, cutoff: s.whistleFreq });
    }
    return p;
  }

  /**
   * Overall loudness, with NO distance rolloff.
   *
   * Distance is deliberately excluded: sprinting past a source a metre away genuinely does change
   * its level fast, and that is physics doing its job, not the system lurching.
   */
  level() {
    let e = this.diffuseGain * this.diffuseGain + this.roofGain * this.roofGain * 0.5;
    for (const s of this.apertures.values()) e += s.gain * s.gain;
    return Math.sqrt(e);
  }

  update(frame, dt) {
    dt = Math.max(0, Math.min(dt, 0.25)); // a long stall must not teleport parameters
    this.diffuseGain = approachGain(this.diffuseGain, frame.diffuse.gain, dt, TAU_GAIN);
    this.diffuseCutoff = approachLog(this.diffuseCutoff, frame.diffuse.cutoff, dt, TAU_CUT);
    // The roof gets the same dB treatment as everything else. It is the one layer that genuinely
    // sits at exactly zero outdoors, and a LINEAR slew from zero produces unbounded dB steps no
    // matter how small you make it — 0.005 → 0.017 is 11 dB. In dB it swells in over about a
    // second, which is both provably smooth and closer to what walking under a roof feels like.
    this.roofGain = approachGain(this.roofGain, frame.roof.gain, dt, TAU_ROOF);
    this.windGain = approachGain(this.windGain, frame.wind.gain, dt, TAU_GAIN);
    this.windCutoff = approachLog(this.windCutoff, frame.wind.cutoff, dt, TAU_CUT);
    this.rt60 = approach(this.rt60, frame.reverb.rt60, dt, TAU_SLOW);
    this.wet = approach(this.wet, frame.reverb.wet, dt, TAU_SLOW);
    this.outsideWeight = approach(this.outsideWeight, frame.outsideWeight, dt, TAU_GAIN);

    for (const s of this.apertures.values()) s.alive = false;

    for (const a of frame.apertures) {
      let s = this.apertures.get(a.opening);
      if (!s) {
        // New emitter — start silent so it fades up rather than pops in.
        s = {
          name: a.name, gain: 0, cutoff: a.cutoff, whistleGain: 0, whistleFreq: a.whistleFreq,
          pos: a.pos.slice(), pan: a.pan, distance: a.distance, alive: true,
        };
        this.apertures.set(a.opening, s);
      }
      s.alive = true;
      s.name = a.name;
      s.pos = a.pos;
      s.pan = a.pan;
      s.distance = a.distance;
      s.gain = approachGain(s.gain, a.gain, dt, TAU_GAIN);
      s.cutoff = approachLog(s.cutoff, a.cutoff, dt, TAU_CUT);
      s.whistleGain = approachGain(s.whistleGain, a.whistleGain, dt, TAU_GAIN);
      s.whistleFreq = approachLog(s.whistleFreq, a.whistleFreq, dt, TAU_CUT);
    }

    // Emitters that dropped out of the frame fade away rather than vanishing.
    for (const [k, s] of this.apertures) {
      if (s.alive) continue;
      s.gain = approachGain(s.gain, 0, dt, TAU_GAIN);
      s.whistleGain = approachGain(s.whistleGain, 0, dt, TAU_GAIN);
      if (s.gain <= GAIN_FLOOR * 1.5 && s.whistleGain <= GAIN_FLOOR * 1.5) this.apertures.delete(k);
    }
  }

  /** Same shape as model.summarise(), but of the SMOOTHED state — what you actually hear. */
  summarise() {
    let e = this.diffuseGain * this.diffuseGain;
    let cutE = e * this.diffuseCutoff;
    for (const s of this.apertures.values()) {
      const g = s.gain / Math.max(1, s.distance);
      e += g * g;
      cutE += g * g * s.cutoff;
    }
    const stormE = e, stormCutE = cutE;
    const roofE = this.roofGain * this.roofGain * 0.5;
    e += roofE;
    cutE += roofE * 1800;
    return {
      gain: Math.sqrt(e),
      cutoff: e > 1e-12 ? cutE / e : 20000,
      storm: Math.sqrt(stormE),
      stormCutoff: stormE > 1e-12 ? stormCutE / stormE : 20000,
      rt60: this.rt60,
      wet: this.wet,
      roof: this.roofGain,
      outsideWeight: this.outsideWeight,
    };
  }
}
