// SYNTH TESTS — does the source material sound like weather?
//
// These exist because of a bug that every other test in this project was structurally incapable of
// catching. The acoustics suite asserts what the ROOM does to a sound: the storm loses 20 dB and an
// octave of treble through a wall, the window puts the noise in the right ear, the roof takes over
// the low-mids indoors. All of that is relative. Every one of those checks passes just as happily
// when the thing being filtered is a burst of static, because they only ever compare the rain to
// itself. The first listener said "it doesn't sound like rain, it sounds like sharp sounds", and
// they were right, and 129 checks were green.
//
// So: absolute assertions on the buffers themselves, before any room touches them. They are the
// weakest tests in the project — a spectrum is a poor proxy for a texture — but they pin the one
// property that was actually wrong, and they run in node with no Web Audio at all, because synth.js
// only ever asks a context for `sampleRate` and `createBuffer`.

import { group, gt, lt, between } from './harness.mjs';
import { makeRainBuffer, makeRoofBuffer, makeWindBuffer, makeThunderBuffer } from '../src/audio/synth.js';
import { bandEnergy, centroid, toDb, rms } from './fft.mjs';

const SR = 44100;

/** The whole Web Audio surface synth.js uses. */
const fakeCtx = {
  sampleRate: SR,
  createBuffer(channels, length) {
    const data = Array.from({ length: channels }, () => new Float32Array(length));
    return {
      numberOfChannels: channels, length, sampleRate: SR,
      getChannelData: i => data[i],
    };
  },
};

/** dB of one band relative to the whole. */
const share = (d, lo, hi) => toDb(bandEnergy(d, SR, lo, hi)) - toDb(bandEnergy(d, SR, 40, 20000));

export function run() {
  const rain = makeRainBuffer(fakeCtx);
  const roof = makeRoofBuffer(fakeCtx);
  const wind = makeWindBuffer(fakeCtx);
  const thunder = makeThunderBuffer(fakeCtx);
  const L = b => b.getChannelData(0);

  // ------------------------------------------------------------------ rain
  group('synth — rain is rain and not hiss');

  // THE regression. The first version shaped the bed with a single one-pole low-pass, which is
  // 6 dB/octave and does not band-limit anything, it only tilts. Measured across the 1 k, 2 k, 4 k,
  // 8 k and 12 k octaves it ran -9.0, -6.4, -5.9, -8.5, -7.2 dB: as much energy at 12 kHz as at
  // 1 kHz, i.e. flat to the top of hearing, i.e. static. Real rain rolls off above a couple of kHz.
  const c = centroid(L(rain), SR);
  between('the rain bed is bright but not white (centroid 1.5–4 kHz)', c, 1500, 4000,
    `${c.toFixed(0)} Hz — the version that sounded like static measured 5703 Hz`);

  const mid = share(L(rain), 1000, 4000);
  const top = share(L(rain), 8000, 20000);
  gt('there is a real roll-off above 8 kHz, not a shelf', mid - top, 6,
    `1–4 kHz at ${mid.toFixed(1)} dB, 8–20 kHz at ${top.toFixed(1)} dB (the old bed: -6.7 vs -5.4)`);

  // ...but not so far that the wall has nothing left to take. The whole inside/outside effect is
  // treble disappearing, and treble that was never there cannot disappear.
  gt('...while keeping enough treble for a wall to be able to remove it',
    share(L(rain), 4000, 16000), -16);

  // Rain has very little bottom end; the bottom of a storm is the WIND. This is also what leaves
  // the low-mids free for the roof to own indoors, which is the entire point of the project.
  lt('rain has almost nothing below 250 Hz', share(L(rain), 40, 250), -18);

  // ------------------------------------------------------------------ roof
  group('synth — the roof is a different sound, not a filtered copy');

  const rc = centroid(L(roof), SR);
  lt('the roof layer is far darker than the bed', rc, c / 2.5,
    `roof ${rc.toFixed(0)} Hz vs rain ${c.toFixed(0)} Hz`);
  gt('the drumming lives in the low-mids where the model expects it',
    share(L(roof), 120, 900), -6, `${share(L(roof), 120, 900).toFixed(1)} dB of the total`);

  // ------------------------------------------------------------- loop hygiene
  group('synth — the loops actually loop');

  // Every bed is played on `loop: true` forever, so the wrap from the last sample to the first is a
  // real edge in the output. loopFold() generates past the end and folds the overlap back, which
  // makes that pair a sequence the generator really produced. Measured against the median step so
  // the check means the same thing for a hissy buffer and a dull one.
  for (const [name, buf] of [['rain', rain], ['roof', roof], ['wind', wind]]) {
    for (let ch = 0; ch < 2; ch++) {
      const d = buf.getChannelData(ch);
      const n = d.length;
      let sum = 0;
      for (let i = 1; i < n; i++) sum += Math.abs(d[i] - d[i - 1]);
      const meanStep = sum / (n - 1);
      const wrap = Math.abs(d[0] - d[n - 1]);
      lt(`${name} ch${ch} wraps without a discontinuity`, wrap, meanStep * 6,
        `wrap ${wrap.toFixed(4)} vs mean step ${meanStep.toFixed(4)}`);
    }
  }

  // ---------------------------------------------------------------- levels
  group('synth — nothing is clipped and nothing is silent');

  for (const [name, buf] of [['rain', rain], ['roof', roof], ['wind', wind], ['thunder', thunder]]) {
    const d = L(buf);
    let peak = 0;
    for (let i = 0; i < d.length; i++) peak = Math.max(peak, Math.abs(d[i]));
    lt(`${name} does not clip`, peak, 1.0, `peak ${peak.toFixed(3)}`);
    gt(`${name} is not silent`, rms(d), 0.01, `rms ${rms(d).toFixed(4)}`);
    // The two channels must not be the same samples, or the whole thing collapses to the middle of
    // the head and every stereo claim in the project is a lie.
    if (name !== 'thunder') {
      let same = 0;
      const a = buf.getChannelData(0), b = buf.getChannelData(1);
      for (let i = 0; i < 2000; i++) if (a[i] === b[i]) same++;
      lt(`${name} is genuinely stereo`, same, 100, `${same}/2000 identical samples`);
    }
  }

  // ------------------------------------------------------------- boot cost
  group('synth — the whole storm builds fast enough to click a button');

  // Every buffer in the game is synthesised on the MAIN THREAD between the click and the first
  // frame, so this is not a performance nicety — it is the difference between a title screen and a
  // hung tab. It has already been ruinous once: chasing drop density took the roof layer to ~350
  // overlapping voices per sample and boot to nine seconds, which presents to a player as "clicking
  // begin doesn't do anything". Deliberately loose, because it is guarding against a 27x
  // regression, not policing milliseconds on someone else's machine.
  const t0 = performance.now();
  makeRainBuffer(fakeCtx);
  makeRoofBuffer(fakeCtx);
  makeWindBuffer(fakeCtx);
  makeThunderBuffer(fakeCtx);
  const cost = performance.now() - t0;
  lt('all four buffers build fast enough to not look broken', cost, 2500, `${cost.toFixed(0)} ms`);

  // --------------------------------------------------------------- thunder
  group('synth — thunder is a low event with a tail');

  lt('thunder is mostly low frequency', centroid(L(thunder), SR), 1600,
    `${centroid(L(thunder), SR).toFixed(0)} Hz`);
  // The crack is at the front and the roll is behind it. If the buffer were flat it would read as
  // a noise burst rather than as a strike.
  const d = L(thunder);
  const head = rms(d.subarray(0, Math.floor(SR * 0.15)));
  const tail = rms(d.subarray(Math.floor(SR * 1.5)));
  gt('it starts with a crack', head, tail * 2, `head ${head.toFixed(3)}, tail ${tail.toFixed(3)}`);
  gt('...and still has a tail two seconds in', tail, 0.004, `${tail.toFixed(4)}`);
}
