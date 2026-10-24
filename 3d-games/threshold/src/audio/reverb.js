// Room reverb — a Schroeder bank of damped feedback combs.
//
// Chosen over convolution for one reason: RT60 has to be CONTINUOUS. It comes from the solver, so
// it changes as you walk from a cabin into a cupboard, and swapping convolution impulses means
// either crossfading two convolvers or hearing the room switch. Here the decay is a gain on a
// feedback loop, which is an AudioParam, which ramps.
//
// Each comb has a low-pass in its loop, so the tail darkens as it decays — which is what real
// rooms do, and what makes a short tail read as "wooden" rather than "metallic".

// Mutually prime-ish delays, in seconds. Chosen so their echoes never line up into a flutter.
const COMBS = [0.0297, 0.0371, 0.0411, 0.0437, 0.0533, 0.0619];

export class Reverb {
  constructor(ctx) {
    this.ctx = ctx;
    this.input = ctx.createGain();
    this.output = ctx.createGain();
    this.output.gain.value = 1;

    // A little pre-delay: the room answers you, it does not talk over you.
    this.pre = ctx.createDelay(0.2);
    this.pre.delayTime.value = 0.011;
    this.input.connect(this.pre);

    this.combs = COMBS.map((d, i) => {
      const delay = ctx.createDelay(0.2);
      delay.delayTime.value = d;
      const fb = ctx.createGain();
      fb.gain.value = 0.5;
      const damp = ctx.createBiquadFilter();
      damp.type = 'lowpass';
      damp.frequency.value = 4200;
      const out = ctx.createGain();
      out.gain.value = 1 / COMBS.length;

      this.pre.connect(delay);
      delay.connect(damp);
      damp.connect(fb);
      fb.connect(delay);        // the loop
      delay.connect(out);
      // Spread the combs across the stereo field so the tail has width.
      const pan = ctx.createStereoPanner();
      pan.pan.value = (i % 2 ? 1 : -1) * (0.25 + (i / COMBS.length) * 0.5);
      out.connect(pan);
      pan.connect(this.output);

      return { delay, fb, damp, time: d };
    });

    this.rt60 = 0.4;
    this.setRT60(0.4, 0);
  }

  /**
   * Feedback gain for a target decay: after rt60 seconds the loop must be 60 dB down, and it goes
   * round every `time` seconds, so g = 10^(-3·time/rt60).
   */
  setRT60(rt60, ramp = 0.25) {
    const t = Math.max(0.05, Math.min(rt60, 5));
    if (Math.abs(t - this.rt60) < 1e-4) return;
    this.rt60 = t;
    const now = this.ctx.currentTime;
    for (const c of this.combs) {
      const g = Math.min(0.93, Math.pow(10, (-3 * c.time) / t));
      c.fb.gain.setTargetAtTime(g, now, Math.max(0.01, ramp));
      // Short rooms are small and soft, so they should be darker as well as shorter.
      const damp = 2200 + Math.min(t, 2) * 3400;
      c.damp.frequency.setTargetAtTime(damp, now, Math.max(0.01, ramp));
    }
  }

  connect(dest) { this.output.connect(dest); return this; }
  disconnect() { this.output.disconnect(); }
}
