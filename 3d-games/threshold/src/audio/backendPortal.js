// BACKEND B — the real thing.
//
// Walls low-pass. Openings become their own positional sound sources sitting at their own
// coordinates. Room size drives the reverb. Rain drums on the roof above your head. Every number
// comes from the solver, so any room ever built will sound correct with no authoring at all.
//
// The graph, built once and then only re-parameterised:
//
//   rain ──┬─ diffuse:  lowpass → gain ─┬──────────────→ master
//          │                            └→ reverb send
//          ├─ portal_i: lowpass → gain → panner ─┬─────→ master        (one per opening)
//          │                                     └→ reverb send
//          └─ roof:     lowpass → gain → panner(above) ─┬────→ master   (indoors only)
//                                                       └→ reverb send
//   wind ──┬─ bed:      lowpass → gain ────────────────→ master
//          └─ whistle_i: bandpass(high Q) → gain → panner → master
//   thunder (one-shots) → lowpass → gain → panner ─┬────→ master
//                                                  └→ reverb send

import { AcousticBackend } from './backend.js';
import { Reverb } from './reverb.js';

const loopSource = (ctx, buffer, rate = 1) => {
  const src = ctx.createBufferSource();
  src.buffer = buffer;
  src.loop = true;
  src.playbackRate.value = rate;
  return src;
};

export class PortalBackend extends AcousticBackend {
  constructor(ctx, dest, buffers) {
    super(ctx, dest, buffers);
    this.portals = new Map(); // Opening -> nodes
    this.solo = null;
    this.thunderVoices = new Set();
    this.nodeCount = 0;
  }

  start() {
    if (this.started) return;
    const { ctx, buffers } = this;
    this.started = true;

    this.master = ctx.createGain();
    this.master.gain.value = 1;
    this.master.connect(this.dest);

    this.reverb = new Reverb(ctx);
    this.reverbWet = ctx.createGain();
    this.reverbWet.gain.value = 0.15;
    this.reverb.connect(this.reverbWet);
    this.reverbWet.connect(this.master);

    // ---- shared, always-running sources -------------------------------------------------
    // ONE rain source feeding every path. Not an optimisation: it is what makes the sound
    // coming through the wall and the sound coming through the window the same rain, which is
    // what stops an open window sounding like a second, unrelated rainstorm outside it.
    this.rain = loopSource(ctx, buffers.rain);
    this.rainTap = ctx.createGain();
    this.rain.connect(this.rainTap);
    this.rain.start();

    this.wind = loopSource(ctx, buffers.wind);
    this.windTap = ctx.createGain();
    this.wind.connect(this.windTap);
    this.wind.start();

    this.roofSrc = loopSource(ctx, buffers.roof);
    this.roofTap = ctx.createGain();
    this.roofSrc.connect(this.roofTap);
    this.roofSrc.start();

    // ---- diffuse bed: what bleeds through the walls --------------------------------------
    this.diffuseFilter = ctx.createBiquadFilter();
    this.diffuseFilter.type = 'lowpass';
    this.diffuseFilter.frequency.value = 20000;
    this.diffuseFilter.Q.value = 0.7;
    this.diffuseGain = ctx.createGain();
    this.diffuseGain.gain.value = 0;
    this.rainTap.connect(this.diffuseFilter);
    this.diffuseFilter.connect(this.diffuseGain);
    this.diffuseGain.connect(this.master);
    this.diffuseSend = ctx.createGain();
    this.diffuseSend.gain.value = 1;
    this.diffuseGain.connect(this.diffuseSend);
    this.diffuseSend.connect(this.reverb.input);

    // ---- wind bed -------------------------------------------------------------------------
    this.windFilter = ctx.createBiquadFilter();
    this.windFilter.type = 'lowpass';
    this.windFilter.frequency.value = 20000;
    this.windGain = ctx.createGain();
    this.windGain.gain.value = 0;
    this.windTap.connect(this.windFilter);
    this.windFilter.connect(this.windGain);
    this.windGain.connect(this.master);

    // ---- the roof layer, spatialised ABOVE the listener ------------------------------------
    this.roofFilter = ctx.createBiquadFilter();
    this.roofFilter.type = 'lowpass';
    this.roofFilter.frequency.value = 2600;
    this.roofGain = ctx.createGain();
    this.roofGain.gain.value = 0;
    this.roofPanner = ctx.createPanner();
    this.roofPanner.panningModel = 'HRTF';
    this.roofPanner.distanceModel = 'inverse';
    this.roofPanner.refDistance = 1.6;
    this.roofTap.connect(this.roofFilter);
    this.roofFilter.connect(this.roofGain);
    this.roofGain.connect(this.roofPanner);
    this.roofPanner.connect(this.master);
    this.roofSend = ctx.createGain();
    this.roofSend.gain.value = 0.8;
    this.roofGain.connect(this.roofSend);
    this.roofSend.connect(this.reverb.input);

    this.nodeCount = 24;
  }

  /** Lazily build the node chain for one opening; reused for its whole life. */
  _portal(opening) {
    let p = this.portals.get(opening);
    if (p) return p;
    const { ctx } = this;

    const filter = ctx.createBiquadFilter();
    filter.type = 'lowpass';
    filter.frequency.value = 800;
    filter.Q.value = 0.7;

    const gain = ctx.createGain();
    gain.gain.value = 0;

    const panner = ctx.createPanner();
    panner.panningModel = 'HRTF';
    panner.distanceModel = 'inverse';
    panner.refDistance = 1.0;
    panner.rolloffFactor = 1.0;
    panner.maxDistance = 60;

    this.rainTap.connect(filter);
    filter.connect(gain);
    gain.connect(panner);
    panner.connect(this.master);

    const send = ctx.createGain();
    send.gain.value = 1;
    gain.connect(send);
    send.connect(this.reverb.input);

    // The whistle: wind through a gap, sharing the aperture's position.
    const wFilter = ctx.createBiquadFilter();
    wFilter.type = 'bandpass';
    wFilter.frequency.value = 1200;
    wFilter.Q.value = 11;
    const wGain = ctx.createGain();
    wGain.gain.value = 0;
    this.windTap.connect(wFilter);
    wFilter.connect(wGain);
    wGain.connect(panner);

    p = { filter, gain, panner, send, wFilter, wGain };
    this.portals.set(opening, p);
    this.nodeCount += 7;
    return p;
  }

  _dropPortal(opening) {
    const p = this.portals.get(opening);
    if (!p) return;
    for (const n of [p.filter, p.gain, p.panner, p.send, p.wFilter, p.wGain]) {
      try { n.disconnect(); } catch (e) { /* already gone */ }
    }
    this.portals.delete(opening);
    this.nodeCount -= 7;
  }

  apply(s, listener) {
    if (!this.started) return;
    const now = this.ctx.currentTime;
    // Everything arriving here is already smoothed and slew-limited by SmoothedAcoustics; the tiny
    // time constant below only takes the stair-step out of per-frame parameter writes.
    const T = 0.012;
    const set = (param, v) => param.setTargetAtTime(v, now, T);

    // Solo: a debugging ear, not a feature. Isolating one layer is the fastest way to answer
    // "which part of this sounds wrong", and the alternative — reasoning about a four-layer mix
    // from a spectrum plot — is exactly the approach that kept producing confident wrong answers.
    const m = k => (!this.solo || this.solo === k ? 1 : 0);

    set(this.diffuseGain.gain, s.diffuseGain * m('diffuse'));
    set(this.diffuseFilter.frequency, Math.min(s.diffuseCutoff, 20000));
    set(this.windGain.gain, s.windGain * 0.5 * m('wind'));
    set(this.windFilter.frequency, Math.min(s.windCutoff, 20000));

    set(this.roofGain.gain, s.roofGain * m('roof'));
    // Sit the roof source just above and slightly in front, so it has a direction rather than
    // being everywhere. Head-tracked, so looking up at the boards is a real thing to do.
    const [lx, ly, lz] = listener.pos;
    this._place(this.roofPanner, lx, ly + 2.0, lz + 0.001);

    set(this.reverbWet.gain, s.wet);
    this.reverb.setRT60(s.rt60, 0.3);

    const live = new Set();
    for (const [opening, a] of s.apertures) {
      live.add(opening);
      const p = this._portal(opening);
      set(p.gain.gain, a.gain * m('portals'));
      set(p.filter.frequency, Math.min(a.cutoff, 20000));
      set(p.wGain.gain, a.whistleGain * 0.32 * m('portals'));
      set(p.wFilter.frequency, a.whistleFreq);
      this._place(p.panner, a.pos[0], a.pos[1], a.pos[2]);
    }
    // Anything the model has stopped reporting has already faded to silence in the smoother.
    for (const opening of [...this.portals.keys()]) {
      if (!live.has(opening)) this._dropPortal(opening);
    }
  }

  _place(panner, x, y, z) {
    if (panner.positionX) {
      const t = this.ctx.currentTime;
      panner.positionX.setValueAtTime(x, t);
      panner.positionY.setValueAtTime(y, t);
      panner.positionZ.setValueAtTime(z, t);
    } else {
      panner.setPosition(x, y, z); // older Safari
    }
  }

  /**
   * A thunder voice: one shared buffer, shaped by distance. Disposes itself when it ends.
   *
   * `frame` is the last acoustic frame, or null — indoors, thunder should come through the wall
   * like everything else.
   */
  thunder(strike, listener, frame = null) {
    if (!this.started) return;
    const { ctx } = this;
    const src = ctx.createBufferSource();
    src.buffer = this.buffers.thunder;
    // Distant thunder does not just get quieter and duller, it gets LONGER — the sound arrives via
    // many paths of slightly different length and smears into a roll.
    src.playbackRate.value = Math.max(0.35, 1 / (1 + strike.shape.spread * 0.55));

    // Indoors, thunder is muffled but it is NOT quiet, and the difference is the mass law rather
    // than a preference. Practically all of a thunderclap's energy sits below the wall's cutoff,
    // so the wall has very little of it to remove — hence the room's low-pass in full but only a
    // third of the room's broadband attenuation. This is why a storm indoors still makes you look
    // up: what a wall takes from thunder is the crack, not the boom.
    const t = frame ? Math.max(1e-4, Math.min(1, frame.transmission)) : 1;
    const cutoff = Math.min(strike.shape.cutoff, frame ? frame.diffuse.cutoff : 20000);
    const wall = Math.pow(t, 0.35);   // a third of the room's attenuation, in dB

    const filter = ctx.createBiquadFilter();
    filter.type = 'lowpass';
    filter.frequency.value = Math.max(120, cutoff);
    filter.Q.value = 0.6;

    const gain = ctx.createGain();
    gain.gain.value = strike.shape.gain * wall * 0.9;

    // DIRECTION ONLY. strikeShape.gain already carries the whole distance law, so the panner must
    // not apply one as well — rolloffFactor 0 makes an inverse-distance model return exactly 1 at
    // every distance, leaving the panner doing the one job it is here for. Placing the source at
    // its true kilometre-scale position and letting the panner attenuate it was the other half of
    // the inaudible-thunder bug, and it is a tempting mistake because the placement looks more
    // "correct" the more wrong it sounds.
    const panner = ctx.createPanner();
    panner.panningModel = 'HRTF';
    panner.distanceModel = 'inverse';
    panner.refDistance = 1;
    panner.rolloffFactor = 0;
    // Near strikes are overhead; distant ones sit down toward the horizon where you can point at
    // them. The radius is fixed, so this is an angle and nothing more.
    const R = 14;
    const up = 3 + 13 * Math.max(0, 1 - strike.distance / 2500);
    this._place(panner,
      listener.pos[0] + Math.sin(strike.azimuth) * R,
      listener.pos[1] + up,
      listener.pos[2] + Math.cos(strike.azimuth) * R);

    src.connect(filter); filter.connect(gain); gain.connect(panner); panner.connect(this.master);
    const send = ctx.createGain();
    send.gain.value = 1.4; // thunder should ring the room
    gain.connect(send); send.connect(this.reverb.input);

    const voice = { src, filter, gain, panner, send };
    this.thunderVoices.add(voice);
    this.nodeCount += 5;
    src.onended = () => {
      for (const n of [src, filter, gain, panner, send]) { try { n.disconnect(); } catch (e) { /* gone */ } }
      this.thunderVoices.delete(voice);
      this.nodeCount -= 5;
    };
    src.start();
  }

  stop() {
    if (!this.started) return;
    this.started = false;
    for (const opening of [...this.portals.keys()]) this._dropPortal(opening);
    for (const v of this.thunderVoices) {
      try { v.src.stop(); } catch (e) { /* already stopped */ }
    }
    this.thunderVoices.clear();
    for (const s of [this.rain, this.wind, this.roofSrc]) {
      try { s.stop(); } catch (e) { /* already stopped */ }
      s.disconnect();
    }
    for (const n of [this.rainTap, this.windTap, this.roofTap, this.diffuseFilter, this.diffuseGain,
      this.diffuseSend, this.windFilter, this.windGain, this.roofFilter, this.roofGain,
      this.roofPanner, this.roofSend, this.reverbWet, this.master]) {
      try { n.disconnect(); } catch (e) { /* gone */ }
    }
    this.reverb.disconnect();
    this.nodeCount = 0;
  }

  describe() {
    return { name: 'portal', nodes: this.nodeCount, portals: this.portals.size };
  }
}
