// §8.6. WebAudio, unlocked on the first gesture, and every sound is SYNTHESISED — there is
// not one audio file in this project. That is the same argument §8.1 makes for the art: this
// game's palette is simple enough that generating it is both cheaper and more controllable
// than sourcing it, and it keeps `assets/` genuinely optional.
//
// Landmine 10: a browser will not start an AudioContext without a user gesture, and a game
// whose audio silently never plays is a bug nobody reports because it looks like a choice.
// unlock() runs on the launcher click and `suspended` is surfaced in the HUD.

const NOISE_SECONDS = 2;

export function createAudio() {
  let ctx = null, master = null, noiseBuf = null;
  let muted = false, volume = 0.7;
  const loops = new Map();

  function ensure() {
    if (ctx) return ctx;
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return null;
    ctx = new AC();
    master = ctx.createGain();
    master.gain.value = muted ? 0 : volume;
    master.connect(ctx.destination);

    // One buffer of white noise, reused by wind, whoosh, splash, rumble and explosion.
    noiseBuf = ctx.createBuffer(1, ctx.sampleRate * NOISE_SECONDS, ctx.sampleRate);
    const d = noiseBuf.getChannelData(0);
    let last = 0;
    for (let i = 0; i < d.length; i++) {
      const w = Math.random() * 2 - 1;
      last = (last + 0.02 * w) / 1.02;          // a little brown, so it is not hissy
      d[i] = last * 3.5;
    }
    return ctx;
  }

  const now = () => (ctx ? ctx.currentTime : 0);

  function env(node, t0, attack, hold, release, peak) {
    const g = ctx.createGain();
    g.gain.setValueAtTime(0.0001, t0);
    g.gain.exponentialRampToValueAtTime(Math.max(0.0001, peak), t0 + attack);
    g.gain.setValueAtTime(Math.max(0.0001, peak), t0 + attack + hold);
    g.gain.exponentialRampToValueAtTime(0.0001, t0 + attack + hold + release);
    node.connect(g); g.connect(master);
    return g;
  }

  function tone(freq, t0, dur, { type = 'sine', peak = 0.25, slideTo = null, attack = 0.005 } = {}) {
    if (!ensure()) return;
    const o = ctx.createOscillator();
    o.type = type;
    o.frequency.setValueAtTime(freq, t0);
    if (slideTo !== null) o.frequency.exponentialRampToValueAtTime(Math.max(1, slideTo), t0 + dur);
    env(o, t0, attack, dur * 0.3, dur * 0.7, peak);
    o.start(t0); o.stop(t0 + dur + 0.05);
  }

  function noise(t0, dur, { peak = 0.3, lp = 2000, hp = 0, q = 1, sweepTo = null } = {}) {
    if (!ensure()) return;
    const s = ctx.createBufferSource();
    s.buffer = noiseBuf; s.loop = true;
    s.playbackRate.value = 0.8 + Math.random() * 0.4;
    let node = s;
    if (hp > 0) { const f = ctx.createBiquadFilter(); f.type = 'highpass'; f.frequency.value = hp; node.connect(f); node = f; }
    const f2 = ctx.createBiquadFilter();
    f2.type = 'lowpass'; f2.Q.value = q;
    f2.frequency.setValueAtTime(lp, t0);
    if (sweepTo !== null) f2.frequency.exponentialRampToValueAtTime(Math.max(40, sweepTo), t0 + dur);
    node.connect(f2);
    env(f2, t0, 0.01, dur * 0.2, dur * 0.8, peak);
    s.start(t0); s.stop(t0 + dur + 0.05);
  }

  // ── continuous loops: wind, rocket, creak ────────────────────────────────────────────
  // The wind is doing an enormous amount of work for the sense of velocity — its volume and
  // cutoff track speed, which is most of why a fast run FEELS fast (§8.6).

  function startLoop(name, build) {
    if (loops.has(name) || !ensure()) return loops.get(name);
    const l = build();
    loops.set(name, l);
    return l;
  }

  function stopLoop(name) {
    const l = loops.get(name);
    if (!l) return;
    try { l.gain.gain.setTargetAtTime(0.0001, now(), 0.05); l.src.stop(now() + 0.4); } catch { /* already gone */ }
    loops.delete(name);
  }

  const api = {
    get ready() { return !!ctx; },
    get suspended() { return !ctx || ctx.state === 'suspended'; },

    unlock() {
      if (!ensure()) return;
      if (ctx.state === 'suspended') ctx.resume();
    },
    setVolume(v) { volume = v; if (master) master.gain.value = muted ? 0 : v; },
    setMuted(m) { muted = m; if (master) master.gain.value = m ? 0 : volume; },
    get volume() { return volume; },
    get muted() { return muted; },

    // ── one-shots ──
    click() { tone(660, now(), 0.06, { type: 'square', peak: 0.08 }); },
    back() { tone(330, now(), 0.08, { type: 'square', peak: 0.07 }); },
    purchase() {
      const t = now();
      tone(523, t, 0.12, { type: 'triangle', peak: 0.16 });
      tone(784, t + 0.07, 0.18, { type: 'triangle', peak: 0.16 });
    },
    tick(i = 0) { tone(880 + (i % 8) * 40, now(), 0.035, { type: 'square', peak: 0.05 }); },
    launch() {
      const t = now();
      noise(t, 0.9, { peak: 0.45, lp: 900, sweepTo: 5000 });
      tone(90, t, 0.5, { type: 'sawtooth', peak: 0.2, slideTo: 260 });
    },
    rampRumble() { noise(now(), 0.6, { peak: 0.22, lp: 320, q: 3 }); },
    /** Pitch rises per consecutive bounce — this is what sells a chain (§4.8). */
    skip(n = 1) {
      const t = now();
      tone(420 * Math.pow(1.09, Math.min(n, 14)), t, 0.13, { type: 'sine', peak: 0.2, slideTo: 900 * Math.pow(1.05, Math.min(n, 14)) });
      noise(t, 0.16, { peak: 0.24, lp: 5000, hp: 900 });
    },
    splash() { noise(now(), 0.55, { peak: 0.4, lp: 3200, sweepTo: 400 }); },
    surface() { noise(now(), 0.3, { peak: 0.22, lp: 1800, sweepTo: 4000 }); },
    thud(force = 1) {
      const t = now();
      tone(70, t, 0.3, { type: 'sine', peak: 0.3 * force, slideTo: 30 });
      noise(t, 0.25, { peak: 0.3 * force, lp: 700 });
    },
    snap() {
      const t = now();
      noise(t, 0.5, { peak: 0.5, lp: 7000, sweepTo: 300 });
      tone(220, t, 0.25, { type: 'sawtooth', peak: 0.25, slideTo: 60 });
    },
    crack() { noise(now(), 0.3, { peak: 0.35, lp: 6000, hp: 1200, sweepTo: 800 }); },
    explosion() {
      const t = now();
      noise(t, 1.6, { peak: 0.6, lp: 1800, sweepTo: 120 });
      tone(58, t, 0.9, { type: 'sine', peak: 0.4, slideTo: 24 });
    },
    medal() {
      const t = now();
      [523, 659, 784, 1047].forEach((f, i) => tone(f, t + i * 0.09, 0.35, { type: 'triangle', peak: 0.18 }));
    },
    win() {
      const t = now();
      [392, 523, 659, 784, 1047, 1319].forEach((f, i) => tone(f, t + i * 0.11, 0.5, { type: 'triangle', peak: 0.2 }));
    },

    // ── loops ──
    /** @param {number} speed01 0..1 — how fast, as a fraction of the current speed ceiling */
    wind(speed01) {
      if (speed01 <= 0.02) { stopLoop('wind'); return; }
      const l = startLoop('wind', () => {
        const src = ctx.createBufferSource();
        src.buffer = noiseBuf; src.loop = true;
        const filt = ctx.createBiquadFilter(); filt.type = 'bandpass'; filt.Q.value = 0.7;
        const gain = ctx.createGain(); gain.gain.value = 0.0001;
        src.connect(filt); filt.connect(gain); gain.connect(master);
        src.start();
        return { src, filt, gain };
      });
      if (!l) return;
      const t = now();
      l.gain.gain.setTargetAtTime(0.02 + 0.30 * speed01 * speed01, t, 0.08);
      l.filt.frequency.setTargetAtTime(220 + 2600 * speed01, t, 0.08);
    },
    rocket(on) {
      if (!on) { stopLoop('rocket'); return; }
      const l = startLoop('rocket', () => {
        const src = ctx.createBufferSource();
        src.buffer = noiseBuf; src.loop = true;
        const filt = ctx.createBiquadFilter(); filt.type = 'lowpass'; filt.frequency.value = 1400; filt.Q.value = 4;
        const gain = ctx.createGain(); gain.gain.value = 0.0001;
        src.connect(filt); filt.connect(gain); gain.connect(master);
        src.start();
        return { src, filt, gain };
      });
      if (l) l.gain.gain.setTargetAtTime(0.30, now(), 0.03);
    },
    /** Fades in as gliderHp drops — the wing tells you before it lets go (§4.6). */
    creak(stress01) {
      if (stress01 <= 0.01) { stopLoop('creak'); return; }
      const l = startLoop('creak', () => {
        const src = ctx.createOscillator();
        src.type = 'sawtooth'; src.frequency.value = 62;
        const filt = ctx.createBiquadFilter(); filt.type = 'bandpass'; filt.Q.value = 9; filt.frequency.value = 340;
        const gain = ctx.createGain(); gain.gain.value = 0.0001;
        src.connect(filt); filt.connect(gain); gain.connect(master);
        src.start();
        return { src, filt, gain };
      });
      if (!l) return;
      const t = now();
      l.gain.gain.setTargetAtTime(0.05 + 0.18 * stress01, t, 0.1);
      l.filt.frequency.setTargetAtTime(300 + 500 * stress01, t, 0.1);
    },
    stopAllLoops() { for (const k of [...loops.keys()]) stopLoop(k); },
  };
  return api;
}
