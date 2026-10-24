// Rebuild audio/*.wav from the original public-domain recordings.
//
//   node tools/prepare-audio.mjs      (needs ffmpeg on PATH or at $FFMPEG, and network access)
//
// A DEV tool. The game never runs this; it just plays what lands in audio/. Everything that was
// done to the recordings is here, so the assets are reproducible rather than mystery binaries
// someone once dragged into the folder. See audio/CREDITS.md for sources and licences.

import { execFileSync } from 'node:child_process';
import { writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const OUT = resolve(ROOT, 'audio');
const CACHE = resolve(OUT, '.sources');
const SR = 44100;
const UA = 'THRESHOLD-gamedev/1.0 (hobby game prototype)';

const FFMPEG = process.env.FFMPEG
  || ['C:/Users/you/Documents/FFmpeg/bin/ffmpeg', 'ffmpeg'].find(p => {
    try { execFileSync(p, ['-version'], { stdio: 'ignore' }); return true; } catch (e) { return false; }
  });
if (!FFMPEG) { console.error('ffmpeg not found - set FFMPEG=/path/to/ffmpeg'); process.exit(2); }

// Wikimedia Commons originals. Both are public domain or CC0.
const SOURCES = {
  'rain-source.wav': 'https://upload.wikimedia.org/wikipedia/commons/8/85/Light_Rain_Distant_Thunder_July_5th_2016.wav',
  'roof-source.ogg': 'https://upload.wikimedia.org/wikipedia/commons/7/7c/Rain_on_a_veranda_and_t.ogg',
};

async function ensure(name, url) {
  const p = resolve(CACHE, name);
  if (existsSync(p)) return p;
  mkdirSync(CACHE, { recursive: true });
  process.stdout.write(`  downloading ${name} ... `);
  const r = await fetch(url, { headers: { 'User-Agent': UA } });
  if (!r.ok) throw new Error(`HTTP ${r.status} for ${url}`);
  const b = Buffer.from(await r.arrayBuffer());
  writeFileSync(p, b);
  console.log(`${(b.length / 1048576).toFixed(1)} MB`);
  return p;
}

function decode(path, ss, dur, ch) {
  const raw = execFileSync(FFMPEG, ['-v', 'error', '-ss', String(ss), '-t', String(dur), '-i', path,
    '-ac', String(ch), '-ar', String(SR), '-f', 'f32le', '-'], { maxBuffer: 1 << 29 });
  const f = new Float32Array(raw.buffer, raw.byteOffset, raw.length / 4);
  const n = Math.floor(f.length / ch);
  const out = [];
  for (let c = 0; c < ch; c++) {
    const a = new Float32Array(n);
    for (let i = 0; i < n; i++) a[i] = f[i * ch + c];
    out.push(a);
  }
  return out;
}

/** One-pole low-pass; `f` is 0..1, cutoff = -ln(1-f)*sr/2pi. Cascade for a real roll-off. */
function lowpass(buf, f) {
  let y = 0;
  for (let i = 0; i < buf.length; i++) { y += f * (buf[i] - y); buf[i] = y; }
}

/**
 * Loop by GENERATING past the end and folding the overlap back - the same trick synth.js uses.
 * out[0] is src[n], so the wrap src[n-1] -> src[n] is a pair the recording really produced in
 * sequence. Crossfading in place instead joins two unrelated moments and leaves a step there.
 */
function fold(src, n, x) {
  const out = new Float32Array(n);
  out.set(src.subarray(0, n));
  for (let i = 0; i < x; i++) { const t = i / x; out[i] = src[i] * t + src[n + i] * (1 - t); }
  return out;
}

function scaleToRms(chans, target) {
  let s = 0, n = 0;
  for (const c of chans) { for (let i = 0; i < c.length; i++) s += c[i] * c[i]; n += c.length; }
  const k = target / Math.sqrt(s / n);
  for (const c of chans) for (let i = 0; i < c.length; i++) c[i] *= k;
  return k;
}

function softClip(chans, ceiling = 0.95) {
  const knee = ceiling * 0.7;
  for (const c of chans) for (let i = 0; i < c.length; i++) {
    const a = Math.abs(c[i]);
    if (a <= knee) continue;
    c[i] = Math.sign(c[i]) * (knee + (ceiling - knee) * Math.tanh((a - knee) / (ceiling - knee)));
  }
}

function writeWav(file, chans) {
  const ch = chans.length, n = chans[0].length;
  const b = Buffer.alloc(44 + n * ch * 2);
  b.write('RIFF', 0); b.writeUInt32LE(36 + n * ch * 2, 4); b.write('WAVE', 8);
  b.write('fmt ', 12); b.writeUInt32LE(16, 16); b.writeUInt16LE(1, 20);
  b.writeUInt16LE(ch, 22); b.writeUInt32LE(SR, 24);
  b.writeUInt32LE(SR * ch * 2, 28); b.writeUInt16LE(ch * 2, 32); b.writeUInt16LE(16, 34);
  b.write('data', 36); b.writeUInt32LE(n * ch * 2, 40);
  let o = 44;
  for (let i = 0; i < n; i++) {
    for (let c = 0; c < ch; c++) {
      b.writeInt16LE(Math.round(Math.max(-1, Math.min(1, chans[c][i])) * 32767), o);
      o += 2;
    }
  }
  writeFileSync(file, b);
  return b.length;
}

function report(name, chans, bytes) {
  const n = chans[0].length;
  let peak = 0, s = 0, step = 0;
  for (let i = 0; i < n; i++) {
    const a = Math.abs(chans[0][i]);
    if (a > peak) peak = a;
    s += chans[0][i] * chans[0][i];
  }
  for (let i = 1; i < n; i++) step += Math.abs(chans[0][i] - chans[0][i - 1]);
  const wrap = Math.abs(chans[0][0] - chans[0][n - 1]) / (step / (n - 1));
  let same = 0;
  for (let i = 0; i < 3000; i++) if (chans[0][i] === chans[1][i]) same++;
  console.log(`  ${(name + '.wav').padEnd(11)} ${(n / SR).toFixed(1)}s  rms ${Math.sqrt(s / n).toFixed(3)}`
    + `  peak ${peak.toFixed(2)}  wrap/step ${wrap.toFixed(2)}`
    + `  L==R ${same}/3000  ${(bytes / 1048576).toFixed(2)} MB`);
}

async function main() {
  mkdirSync(OUT, { recursive: true });
  const rainSrc = await ensure('rain-source.wav', SOURCES['rain-source.wav']);
  const roofSrc = await ensure('roof-source.ogg', SOURCES['roof-source.ogg']);

  // ---- outdoor rain --------------------------------------------------------------------------
  // 94.5 s in is the steadiest 12 s in the recording (0.25 s-window cv 0.064) and one of the few
  // long stretches with no measurable low-frequency rumble under it. The file is called "Light Rain
  // DISTANT THUNDER" and there is thunder elsewhere in it, which on a 12 s loop would come round
  // every 12 seconds for ever.
  {
    const n = SR * 12, x = SR * 1;
    const [L, R] = decode(rainSrc, 94.5, 13.5, 2);
    const chans = [fold(L, n, x), fold(R, n, x)];
    scaleToRms(chans, 0.17);      // the synth's level, so the model's calibration carries over
    softClip(chans);
    report('rain', chans, writeWav(resolve(OUT, 'rain.wav'), chans));
  }

  // ---- rain on the roof ----------------------------------------------------------------------
  // Mono source, so the two ears get two DIFFERENT stretches of it. Duplicating one channel would
  // collapse the drumming to the centre of the head; a delay would comb-filter it. Two stretches of
  // the same rain are decorrelated with identical statistics, which is exactly what is wanted.
  //
  // Then LOW-PASSED, and that is the difference between the recording and the thing being modelled.
  // It was made under a veranda - outdoors, with the open-air rain reaching the mic too - so it
  // measured FLAT from 2 kHz to 16 kHz (-11.6, -12.1, -12.7 dB by octave). Indoors you hear the
  // drumming through the boards. That shelf of treble put enough 2-8 kHz into the room to mask the
  // sound coming through a cracked window, which is the one effect this whole project exists to
  // demonstrate; the check for it dropped to 4.0 dB against a 6 dB threshold. Two poles at ~1.1 kHz
  // turn "under a veranda" into "under a roof".
  //
  // Corner raised from ~1.1 kHz to ~2.3 kHz once the roof's LEVEL was fixed (see DEFAULT_MIX in
  // model.js). The first pass filtered this hard to stop the roof masking the window in a band
  // test, which was treating a symptom: the roof was not too bright, it was 16 dB too loud. With
  // the level right, the patter can come back - and it should, because "rain on a roof" is a
  // texture of individual impacts and the ear finds them between 1 and 3 kHz.
  {
    const n = SR * 10, x = SR * 1;
    const [A] = decode(roofSrc, 2.5, 11.5, 1);
    const [B] = decode(roofSrc, 14.0, 11.5, 1);
    // Filter BEFORE folding, never after. A one-pole starts at y=0, so filtering the folded loop
    // ramps its head up from silence and leaves the wrap discontinuous - measured, that put the
    // wrap step at 10.9x the mean sample step where folding last gives 2.0x. Filter the long
    // source, then fold, and out[0] is a settled sample the filter really produced.
    for (const c of [A, B]) { lowpass(c, 0.28); lowpass(c, 0.28); }
    const chans = [fold(A, n, x), fold(B, n, x)];
    scaleToRms(chans, 0.19);
    softClip(chans);
    report('roof', chans, writeWav(resolve(OUT, 'roof.wav'), chans));
  }

  console.log('\n  wind and thunder stay synthesised - see audio/CREDITS.md for why.');
}

main().catch(e => { console.error(e); process.exit(1); });
