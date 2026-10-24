// AURA audio engine: buses, master chain, SFX, ambient bed, and the music
// director that puts the drop exactly where the game needs it.
//
// Graph:
//   sfx voices -> sfxIn -> world(lowpass, muffle) -> sfxOut(sfx vol) --\
//   ambient bed ----------^   reverb -> world                          +-> master: comp -> limiter -> clip -> master vol
//   music sessions -> musicBus(music vol) -----------------------------/
//   tinnitus sine ---------------------------------------------------/
//
// Timing: every musical event is scheduled on ctx.currentTime by a lookahead
// scheduler (25 ms tick, 150 ms ahead). setSlowmo never touches music.

import { BeatGrid } from '../sim/beat.js';
import { detectDrop, detectTempo, snapToGrid, analyzeTrack } from '../sim/analyze.js';
import {
  PHONK_BPM, LEAD_BARS, MAIN_BARS, CYCLE_BARS, PhonkPlayer, buildMaster, measureChainLatency,
  noiseBuffers, satCurve, vCrash,
} from './phonk.js';
import { playSfx, AmbientBed, makeImpulse, SFX_NAMES } from './sfx.js';

export { SFX_NAMES };

const clamp = (x, a, b) => Math.min(b, Math.max(a, x));
const CODE_ID = 'code';
const CODE_NAME = 'Code Phonk (claim-free)';
const LOOKAHEAD = 0.15;
const TICK_MS = 25;

function fadeParam(param, at, to, dur) {
  param.cancelScheduledValues(at);
  param.setValueAtTime(param.value, at);
  param.linearRampToValueAtTime(to, at + Math.max(0.004, dur));
}

// A user mp3 playing on the ctx clock, optionally looping a bar-aligned region.
class TrackPlayer {
  constructor(ctx, dest, buffer, { when, offset, loopStart = null, loopEnd = null }) {
    this.ctx = ctx;
    this.stopAt = Infinity;
    this.out = ctx.createGain();
    this.out.connect(dest);
    const src = ctx.createBufferSource();
    src.buffer = buffer;
    if (loopStart != null && loopEnd != null && loopEnd > loopStart) {
      src.loop = true; src.loopStart = loopStart; src.loopEnd = loopEnd;
    }
    let w = when, off = offset;
    if (off < 0) { w = when - off; off = 0; } // drop earlier than the lead-in: wait instead
    src.connect(this.out);
    src.start(w, off);
    this.src = src;
  }
  get stopped() { return this.stopAt !== Infinity; }
  schedule() {}
  stop(at, fade = 1.5) {
    fade = Math.max(0.004, fade);
    if (at + fade >= this.stopAt) return;
    this.stopAt = at + fade;
    fadeParam(this.out.gain, at, 0, fade);
    try { this.src.stop(at + fade + 0.02); } catch (e) { /* ok */ }
    const ms = Math.max(0, (at + fade + 0.3 - this.ctx.currentTime) * 1000);
    clearTimeout(this._disp);
    this._disp = setTimeout(() => { try { this.out.disconnect(); } catch (e) { /* ok */ } }, ms);
  }
  cut(at) { this.stop(at, 0.004); }
}

export class MusicDirector {
  constructor(engine) {
    this.engine = engine;
    this._tracks = [];
    this._cache = new Map();   // id -> decoded buffer + raw detection
    this._sel = null;
    this._session = null;
    this._grid = null;
    this._form = null;         // { dropAt, bar, every } for nextSectionDrop
    this._metro = false;
    this._metroK = 0;
  }

  get _ctx() {
    const c = this.engine.ctx;
    if (!c) throw new Error('[audio] AudioEngine.init() must run first (from a user gesture)');
    return c;
  }

  _codeSel(bpm = PHONK_BPM) {
    return { id: CODE_ID, name: CODE_NAME, bpm, dropAt: (LEAD_BARS * 240) / bpm, firstBeat: 0, detected: false };
  }

  async listTracks() {
    let list = [];
    try {
      const r = await fetch('/api/tracks', { cache: 'no-store' });
      if (r.ok) list = await r.json();
    } catch (e) { console.warn('[audio] /api/tracks unavailable', e); }
    this._tracks = Array.isArray(list) ? list : [];
    const out = [{ id: CODE_ID, name: CODE_NAME }];
    for (const t of this._tracks) {
      const rec = { id: t.id, name: t.name, file: t.file };
      for (const k of ['url', 'drop', 'bpm', 'firstBeat']) if (t[k] != null) rec[k] = t[k];
      out.push(rec);
    }
    return out;
  }

  async select(id) {
    if (!id || id === CODE_ID) {
      this._sel = this._codeSel();
      return this._public(this._sel);
    }
    let tr = this._tracks.find((t) => t.id === id);
    if (!tr) { await this.listTracks(); tr = this._tracks.find((t) => t.id === id); }
    if (!tr) {
      console.warn(`[audio] unknown track "${id}", using code phonk`);
      this._sel = this._codeSel();
      return this._public(this._sel);
    }
    let a = this._cache.get(id);
    if (!a) {
      const res = await fetch(tr.url || '/music/' + encodeURIComponent(tr.file));
      if (!res.ok) throw new Error(`[audio] could not load ${tr.file}: HTTP ${res.status}`);
      const data = await res.arrayBuffer();
      const dc = this.engine.ctx || new OfflineAudioContext(2, 1, 44100);
      const buffer = await dc.decodeAudioData(data);
      const sr = buffer.sampleRate, n = buffer.length, chs = buffer.numberOfChannels;
      const mono = new Float32Array(n);
      for (let c = 0; c < chs; c++) {
        const d = buffer.getChannelData(c);
        for (let i = 0; i < n; i++) mono[i] += d[i] / chs;
      }
      // drop first, then tempo from the drop on (see analyzeTrack in ../sim/analyze.js)
      const r = analyzeTrack(mono, sr);
      const raw = r.drop != null ? r.drop : Math.min(30, buffer.duration / 3);
      a = { buffer, bpm: r.bpm, firstBeat: r.firstBeat, raw, confidence: r.dropConfidence };
      this._cache.set(id, a);
    }
    let bpm = a.bpm, firstBeat = a.firstBeat, detected = true;
    if (typeof tr.bpm === 'number' && tr.bpm > 0) bpm = tr.bpm;
    if (typeof tr.firstBeat === 'number') firstBeat = tr.firstBeat;
    let drop = snapToGrid(a.raw, bpm, firstBeat);
    if (typeof tr.drop === 'number') { drop = tr.drop; detected = false; }
    const ls = readOverride(id);
    if (ls != null) { drop = ls; detected = false; }
    this._sel = { id, name: tr.name, bpm, dropAt: drop, firstBeat, detected, buffer: a.buffer, confidence: a.confidence };
    return this._public(this._sel);
  }

  _public(s) {
    return { id: s.id, name: s.name, bpm: s.bpm, dropAt: s.dropAt, firstBeat: s.firstBeat, detected: s.detected };
  }

  setDropOverride(id, seconds) {
    if (typeof seconds !== 'number' || !isFinite(seconds)) return;
    try { localStorage.setItem('aura:drop:' + id, String(seconds)); } catch (e) { /* private mode */ }
    if (this._sel && this._sel.id === id && id !== CODE_ID) { this._sel.dropAt = seconds; this._sel.detected = false; }
  }

  _halt(fade = 0.05) {
    if (this._session) this._session.stop(this._ctx.currentTime, fade);
    this._session = null;
    this._grid = null;
    this._form = null;
  }

  _begin(session, grid, form) {
    this._session = session;
    this._grid = grid;
    this._form = form;
    const now = this._ctx.currentTime;
    this._metroK = Math.ceil(grid.beatAt(now) - 1e-9);
    session.schedule(now + LOOKAHEAD);
  }

  // Loop region for a user track: from the drop, a whole number of bars.
  _loop(sel) {
    const bar = 240 / sel.bpm, dur = sel.buffer.duration;
    const start = clamp(sel.dropAt, 0, Math.max(0, dur - 0.05));
    const n = Math.floor((dur - start - 0.01) / bar);
    return n >= 1 ? { loopStart: start, loopEnd: start + n * bar } : { loopStart: 0, loopEnd: dur };
  }

  startAwakening() {
    const ctx = this._ctx;
    const sel = this._sel || (this._sel = this._codeSel());
    this._halt(0.05);
    const musicStart = ctx.currentTime + 0.12;
    const bar = 240 / sel.bpm;
    const dropAt = musicStart + LEAD_BARS * bar;
    const grid = new BeatGrid(sel.bpm, dropAt);
    if (sel.id === CODE_ID) {
      const p = new PhonkPlayer(ctx, this.engine.musicBus, { bpm: sel.bpm, mode: 'song', t0: dropAt });
      this._begin(p, grid, { dropAt, bar, every: CYCLE_BARS });
    } else {
      const tp = new TrackPlayer(ctx, this.engine.musicBus, sel.buffer,
        { when: musicStart, offset: sel.dropAt - LEAD_BARS * bar, ...this._loop(sel) });
      this._begin(tp, grid, { dropAt, bar, every: 8 });
    }
    return { musicStart, dropAt, beat: 60 / sel.bpm, grid };
  }

  previewDrop() {
    const ctx = this._ctx;
    const sel = this._sel || (this._sel = this._codeSel());
    this._halt(0.05);
    const now = ctx.currentTime + 0.08;
    const bar = 240 / sel.bpm;
    let dropAt, s;
    if (sel.id === CODE_ID) {
      dropAt = now + LEAD_BARS * bar;
      s = new PhonkPlayer(ctx, this.engine.musicBus, { bpm: sel.bpm, mode: 'song', t0: dropAt });
    } else {
      const pre = Math.min(4, sel.dropAt);
      dropAt = now + pre;
      s = new TrackPlayer(ctx, this.engine.musicBus, sel.buffer, { when: now, offset: sel.dropAt - pre });
    }
    this._begin(s, new BeatGrid(sel.bpm, dropAt), { dropAt, bar, every: 8 });
    s.stop(dropAt + 2, 0.3);
    return { dropAt };
  }

  startPractice(bpm = PHONK_BPM) {
    const ctx = this._ctx;
    const sel = this._sel || (this._sel = this._codeSel());
    this._halt(0.05);
    const t0 = ctx.currentTime + 0.1;
    if (sel.id === CODE_ID) {
      const b = clamp(+bpm || PHONK_BPM, 60, 200);
      const grid = new BeatGrid(b, t0);
      const p = new PhonkPlayer(ctx, this.engine.musicBus, { bpm: b, mode: 'practice', t0 });
      this._begin(p, grid, { dropAt: t0, bar: 240 / b, every: MAIN_BARS });
      return grid;
    }
    const grid = new BeatGrid(sel.bpm, t0);
    const tp = new TrackPlayer(ctx, this.engine.musicBus, sel.buffer, { when: t0, offset: sel.dropAt, ...this._loop(sel) });
    this._begin(tp, grid, { dropAt: t0, bar: 240 / sel.bpm, every: 8 });
    return grid;
  }

  get grid() { return this._grid; }

  get isPlaying() {
    const c = this.engine.ctx;
    return !!(c && this._session && c.currentTime < this._session.stopAt);
  }

  nextSectionDrop(t) {
    const f = this._form;
    if (!f) return null;
    if (t < f.dropAt) return f.dropAt;
    const period = f.every * f.bar;
    return f.dropAt + (Math.floor((t - f.dropAt) / period + 1e-9) + 1) * period;
  }

  cut() {
    if (!this._session) return;
    this._session.cut(this._ctx.currentTime);
    this._session = null;
    this._grid = null;
    this._form = null;
  }

  stop(fade = 1.5) {
    if (!this._session) return;
    const s = this._session;
    s.stop(this._ctx.currentTime, fade);
    // grid stays valid while the fade plays; _tick clears it after
  }

  // The body hitting the ground: one huge distorted 808 + crash + long tail.
  boom() {
    const e = this.engine, ctx = this._ctx;
    const t = ctx.currentTime + 0.01;
    const bus = ctx.createGain(); bus.gain.value = 0.9;
    const wet = ctx.createGain(); wet.gain.value = 0.45;
    bus.connect(e.musicBus); bus.connect(wet); wet.connect(e._musicVerbIn);
    const o = ctx.createOscillator();
    o.frequency.setValueAtTime(160, t);
    o.frequency.exponentialRampToValueAtTime(48, t + 0.08);
    o.frequency.exponentialRampToValueAtTime(33, t + 2.6);
    const pre = ctx.createGain(); pre.gain.value = 1;
    const sh = ctx.createWaveShaper(); sh.curve = satCurve(5);
    const lp = ctx.createBiquadFilter(); lp.type = 'lowpass'; lp.frequency.value = 650; lp.Q.value = 1.2;
    const g = ctx.createGain();
    g.gain.setValueAtTime(0, t);
    g.gain.linearRampToValueAtTime(1, t + 0.003);
    g.gain.setTargetAtTime(0.55, t + 0.08, 0.5);
    g.gain.setTargetAtTime(0, t + 1.4, 0.7);
    o.connect(pre); pre.connect(sh); sh.connect(lp); lp.connect(g); g.connect(bus);
    o.start(t); o.stop(t + 5);
    const src = ctx.createBufferSource(); src.buffer = noiseBuffers(ctx).white;
    const nlp = ctx.createBiquadFilter(); nlp.type = 'lowpass'; nlp.frequency.setValueAtTime(3000, t);
    nlp.frequency.exponentialRampToValueAtTime(200, t + 0.5);
    const ng = ctx.createGain();
    ng.gain.setValueAtTime(0.7, t);
    ng.gain.exponentialRampToValueAtTime(0.001, t + 0.6);
    src.connect(nlp); nlp.connect(ng); ng.connect(bus);
    src.start(t, 0.2); src.stop(t + 0.65);
    vCrash(ctx, bus, t, 1.2, 3.5);
    setTimeout(() => { try { bus.disconnect(); wet.disconnect(); } catch (err) { /* ok */ } }, 9000);
  }

  setMetronome(on) {
    this._metro = !!on;
    const c = this.engine.ctx;
    if (this._metro && this._grid && c) this._metroK = Math.ceil(this._grid.beatAt(c.currentTime + 0.02) - 1e-9);
  }

  _click(t, accent) {
    const ctx = this._ctx;
    const o = ctx.createOscillator(); o.frequency.value = accent ? 2400 : 1600;
    const g = ctx.createGain();
    g.gain.setValueAtTime(0, t);
    g.gain.linearRampToValueAtTime(accent ? 0.35 : 0.2, t + 0.001);
    g.gain.exponentialRampToValueAtTime(0.001, t + 0.035);
    o.connect(g); g.connect(this.engine.sfxOut);
    o.start(t); o.stop(t + 0.045);
  }

  _tick(now, until) {
    const s = this._session;
    if (!s) return;
    if (now > s.stopAt + 0.3) { // a stop()/preview fade has finished
      this._session = null; this._grid = null; this._form = null;
      return;
    }
    s.schedule(until);
    if (this._metro && this._grid) {
      for (;;) {
        const tb = this._grid.beatTime(this._metroK);
        if (tb >= until || tb >= s.stopAt) break;
        if (tb >= now - 0.004) this._click(tb, ((this._metroK % 4) + 4) % 4 === 0);
        this._metroK++;
      }
    }
  }
}

function readOverride(id) {
  try {
    const v = localStorage.getItem('aura:drop:' + id);
    if (v == null || v === '') return null;
    const n = parseFloat(v);
    return isFinite(n) ? n : null;
  } catch (e) { return null; }
}

export class AudioEngine {
  constructor() {
    this._ctx = null;
    this.slowmo = 1;
    this.chainLatency = 0;
    this._vol = { master: 0.9, music: 0.85, sfx: 0.9 };
    this.music = new MusicDirector(this);
  }

  async init() {
    if (this._ctx) {
      if (this._ctx.state !== 'running') { try { await this._ctx.resume(); } catch (e) { /* ok */ } }
      return;
    }
    const AC = globalThis.AudioContext || globalThis.webkitAudioContext;
    const ctx = new AC({ latencyHint: 'interactive' });
    this._ctx = ctx;
    const resumed = ctx.state !== 'running' ? ctx.resume().catch(() => {}) : null; // inside the gesture
    this.master = buildMaster(ctx);
    this.master.out.gain.value = this._vol.master;
    this._musicBus = ctx.createGain(); this._musicBus.gain.value = this._vol.music;
    this._musicBus.connect(this.master.input);
    this._sfxOut = ctx.createGain(); this._sfxOut.gain.value = this._vol.sfx;
    this._sfxOut.connect(this.master.input);
    this.world = ctx.createBiquadFilter(); this.world.type = 'lowpass';
    this.world.frequency.value = 20000; this.world.Q.value = 0.5;
    this.world.connect(this._sfxOut);
    this.sfxIn = ctx.createGain(); this.sfxIn.connect(this.world);
    // SFX room
    this.verbIn = ctx.createGain();
    const verb = ctx.createConvolver(); verb.buffer = makeImpulse(ctx, 1.4, 3.2, 11);
    const verbOut = ctx.createGain(); verbOut.gain.value = 0.7;
    this.verbIn.connect(verb); verb.connect(verbOut); verbOut.connect(this.world);
    // music long tail (boom)
    this._musicVerbIn = ctx.createGain();
    const mverb = ctx.createConvolver(); mverb.buffer = makeImpulse(ctx, 3.8, 2.4, 23);
    this._musicVerbIn.connect(mverb); mverb.connect(this._musicBus);
    // dusk bed
    this.ambient = new AmbientBed(ctx, this.world);
    // tinnitus ring for the knocked-down state (not muffled -- it is inside your head)
    this._tinnG = ctx.createGain(); this._tinnG.gain.value = 0;
    const tinn = ctx.createOscillator(); tinn.frequency.value = 4100;
    const tinn2 = ctx.createOscillator(); tinn2.frequency.value = 4107;
    const t2g = ctx.createGain(); t2g.gain.value = 0.4;
    tinn.connect(this._tinnG); tinn2.connect(t2g); t2g.connect(this._tinnG);
    this._tinnG.connect(this.master.input);
    tinn.start(); tinn2.start();
    this._timer = setInterval(() => this._tick(), TICK_MS);
    if (resumed) await resumed;
    try { this.chainLatency = await measureChainLatency(ctx.sampleRate); } catch (e) { this.chainLatency = 0; }
  }

  get ctx() { return this._ctx; }
  get now() { return this._ctx ? this._ctx.currentTime : 0; }
  // Device latency plus the master chain's fixed compressor look-ahead
  // (measured at init). Music and SFX both pass the chain, so it is all output latency.
  get outputLatency() {
    const c = this._ctx;
    if (!c) return 0;
    return (c.outputLatency || c.baseLatency || 0) + this.chainLatency;
  }
  get musicBus() { return this._musicBus || null; }  // GainNode (music volume) feeding the master chain
  get sfxOut() { return this._sfxOut || null; }      // GainNode (sfx volume) feeding the master chain, after the muffle

  setVolumes({ master, music, sfx } = {}) {
    const c = this._ctx;
    if (master != null) this._vol.master = clamp(master, 0, 1);
    if (music != null) this._vol.music = clamp(music, 0, 1);
    if (sfx != null) this._vol.sfx = clamp(sfx, 0, 1);
    if (!c) return;
    const now = c.currentTime;
    this.master.out.gain.setTargetAtTime(this._vol.master, now, 0.02);
    this._musicBus.gain.setTargetAtTime(this._vol.music, now, 0.02);
    this._sfxOut.gain.setTargetAtTime(this._vol.sfx, now, 0.02);
  }

  setAmbient(level, ramp = 1) {
    if (this.ambient) this.ambient.set(level, ramp);
  }

  setMuffle(amount, ramp = 0.3) {
    const c = this._ctx;
    if (!c) return;
    const a = clamp(amount, 0, 1), now = c.currentTime, tc = Math.max(0.005, ramp / 3);
    const f = 20000 * Math.pow(400 / 20000, a);
    this.world.frequency.cancelScheduledValues(now);
    this.world.frequency.setTargetAtTime(f, now, tc);
    this._tinnG.gain.cancelScheduledValues(now);
    this._tinnG.gain.setTargetAtTime(Math.max(0, (a - 0.3) / 0.7) * 0.02, now, tc);
  }

  setSlowmo(rate) { this.slowmo = clamp(+rate || 1, 0.15, 1); }

  sfx(name, opts = {}) {
    if (!this._ctx) return;
    try {
      playSfx({ ctx: this._ctx, sfxIn: this.sfxIn, verbIn: this.verbIn, slowmo: this.slowmo }, name, opts);
    } catch (e) { console.warn('[audio] sfx failed', name, e); }
  }

  _tick() {
    const c = this._ctx;
    if (!c) return;
    const now = c.currentTime;
    // background tabs throttle timers to ~1 Hz: look further ahead so the song never gaps
    const ahead = typeof document !== 'undefined' && document.hidden ? 1.5 : LOOKAHEAD;
    try { this.music._tick(now, now + ahead); } catch (e) { console.warn('[audio] music tick', e); }
    try { this.ambient.tick(now, now + ahead); } catch (e) { console.warn('[audio] ambient tick', e); }
  }
}
