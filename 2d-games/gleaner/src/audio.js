// The dial is called "noise". If you cannot hear it, the mechanic is invisible.
// Everything here is synthesised — no audio files.

export class Audio {
  constructor() {
    this.ctx = null;
    this.ready = false;
    this.muted = false;
  }

  /** Must be called from a real user gesture or the context stays suspended. */
  start() {
    if (this.ctx) return;
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return;
    const ctx = new AC();
    this.ctx = ctx;

    this.master = ctx.createGain();
    this.master.gain.value = 0.55;
    this.master.connect(ctx.destination);

    // ---- the choir: a held chord, not an engine ----
    // Root, fifth, octave and twelfth on soft waveforms through a filter that
    // opens with the lever. Drive it hard and the upper voices come in, which
    // is the mechanic ("noise is what kills you") made audible.
    this.engGain = ctx.createGain();
    this.engGain.gain.value = 0;
    this.engFilter = ctx.createBiquadFilter();
    this.engFilter.type = 'lowpass';
    this.engFilter.frequency.value = 300;
    this.engFilter.Q.value = 3;
    this.engGain.connect(this.engFilter);
    this.engFilter.connect(this.master);

    this.osc = [];
    this.ratios = [1, 1.5, 2, 3];
    const shapes = ['triangle', 'sine', 'triangle', 'sine'];
    const levels = [1, 0.55, 0.42, 0.2];
    for (let i = 0; i < this.ratios.length; i++) {
      const o = ctx.createOscillator();
      const vg = ctx.createGain();
      o.type = shapes[i];
      o.frequency.value = 55 * this.ratios[i];
      o.detune.value = i * 4 - 6;         // just enough to keep it from sounding synthetic
      vg.gain.value = levels[i];
      o.connect(vg); vg.connect(this.engGain);
      o.start();
      this.osc.push(o);
    }

    // slow breath on the whole chord, so a held note never sits perfectly still
    const lfo = ctx.createOscillator();
    const lfoGain = ctx.createGain();
    lfo.frequency.value = 0.23;
    lfoGain.gain.value = 3.5;
    lfo.connect(lfoGain);
    lfoGain.connect(this.engFilter.detune);
    lfo.start();

    // ---- a rumble bed so the machine has weight ----
    const noise = ctx.createBufferSource();
    const buf = ctx.createBuffer(1, ctx.sampleRate * 2, ctx.sampleRate);
    const d = buf.getChannelData(0);
    for (let i = 0; i < d.length; i++) d[i] = (Math.random() * 2 - 1) * 0.5;
    noise.buffer = buf;
    noise.loop = true;
    this.rumbleGain = ctx.createGain();
    this.rumbleGain.gain.value = 0;
    const lp = ctx.createBiquadFilter();
    lp.type = 'lowpass';
    lp.frequency.value = 190;
    noise.connect(this.rumbleGain);
    this.rumbleGain.connect(lp);
    lp.connect(this.master);
    noise.start();
    this.noiseBuf = buf;

    this.ready = true;
  }

  /** The chord rises and brightens with the lever. */
  setThrottle(t) {
    if (!this.ready || this.muted) return;
    const n = this.ctx.currentTime;
    const root = 46 + t * 30;
    for (let i = 0; i < this.osc.length; i++) {
      this.osc[i].frequency.setTargetAtTime(root * this.ratios[i], n, 0.18);
    }
    this.engFilter.frequency.setTargetAtTime(220 + t * 2100, n, 0.2);
    this.engGain.gain.setTargetAtTime(0.026 + t * 0.07, n, 0.18);
    this.rumbleGain.gain.setTargetAtTime(0.010 + t * 0.048, n, 0.2);
  }

  _blip(dur, freq, type, vol, sweepTo) {
    if (!this.ready || this.muted) return;
    const ctx = this.ctx, n = ctx.currentTime;
    const o = ctx.createOscillator();
    const g = ctx.createGain();
    o.type = type;
    o.frequency.setValueAtTime(freq, n);
    if (sweepTo) o.frequency.exponentialRampToValueAtTime(sweepTo, n + dur);
    g.gain.setValueAtTime(vol, n);
    g.gain.exponentialRampToValueAtTime(0.0001, n + dur);
    o.connect(g); g.connect(this.master);
    o.start(n); o.stop(n + dur + 0.02);
  }

  shot(vol, freq) {
    if (!this.ready || this.muted) return;
    const ctx = this.ctx, n = ctx.currentTime;
    const src = ctx.createBufferSource();
    src.buffer = this.noiseBuf;
    const bp = ctx.createBiquadFilter();
    bp.type = 'bandpass';
    bp.frequency.value = freq * 4;
    bp.Q.value = 1.1;
    const g = ctx.createGain();
    g.gain.setValueAtTime(vol * 0.22, n);
    g.gain.exponentialRampToValueAtTime(0.0001, n + 0.11);
    src.connect(bp); bp.connect(g); g.connect(this.master);
    src.start(n, Math.random() * 1.5); src.stop(n + 0.13);
    this._blip(0.09, freq, 'square', vol * 0.09, freq * 0.4);
  }

  thud() { this._blip(0.07, 150, 'triangle', 0.05, 70); }

  /** A swell answering back out of the field — a chord, from underneath. */
  horn() {
    this._blip(2.0, 58, 'triangle', 0.15, 44);
    this._blip(2.0, 87, 'sine', 0.11, 66);
    this._blip(1.7, 116, 'sine', 0.07, 88);
  }

  hurt() { this._blip(0.18, 220, 'square', 0.06, 90); }

  place() { this._blip(0.1, 520, 'square', 0.07, 780); }

  deny() { this._blip(0.13, 150, 'square', 0.07, 100); }

  death() {
    this._blip(2.4, 110, 'sawtooth', 0.2, 30);
    this._blip(2.4, 72, 'sine', 0.16, 24);
  }

  toggleMute() {
    this.muted = !this.muted;
    if (this.master) this.master.gain.value = this.muted ? 0 : 0.55;
    return this.muted;
  }
}
