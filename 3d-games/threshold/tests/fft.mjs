// A radix-2 FFT and some band maths, so the spectral tests can assert on what the audio graph
// actually produced rather than on the parameters that were fed into it.

/** In-place iterative radix-2 FFT. Length must be a power of two. */
export function fft(re, im) {
  const n = re.length;
  for (let i = 1, j = 0; i < n; i++) {
    let bit = n >> 1;
    for (; j & bit; bit >>= 1) j ^= bit;
    j ^= bit;
    if (i < j) {
      let t = re[i]; re[i] = re[j]; re[j] = t;
      t = im[i]; im[i] = im[j]; im[j] = t;
    }
  }
  for (let len = 2; len <= n; len <<= 1) {
    const ang = -2 * Math.PI / len;
    const wr = Math.cos(ang), wi = Math.sin(ang);
    for (let i = 0; i < n; i += len) {
      let cr = 1, ci = 0;
      for (let k = 0; k < len / 2; k++) {
        const ar = re[i + k], ai = im[i + k];
        const br = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci;
        const bi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr;
        re[i + k] = ar + br; im[i + k] = ai + bi;
        re[i + k + len / 2] = ar - br; im[i + k + len / 2] = ai - bi;
        const ncr = cr * wr - ci * wi;
        ci = cr * wi + ci * wr; cr = ncr;
      }
    }
  }
}

const N = 16384;

/** Magnitude-squared spectrum of a Hann-windowed slice. */
export function spectrum(samples, offset = 0) {
  const re = new Float64Array(N), im = new Float64Array(N);
  for (let i = 0; i < N; i++) {
    const s = samples[offset + i] || 0;
    const w = 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / (N - 1));
    re[i] = s * w;
  }
  fft(re, im);
  const half = N / 2;
  const mag = new Float64Array(half);
  for (let i = 0; i < half; i++) mag[i] = re[i] * re[i] + im[i] * im[i];
  return mag;
}

/**
 * Energy in a band, averaged over several slices so a single unlucky window of noise cannot
 * swing a result.
 */
export function bandEnergy(samples, sr, lo, hi, slices = 6) {
  const usable = samples.length - N;
  if (usable <= 0) return 0;
  let total = 0;
  for (let s = 0; s < slices; s++) {
    const off = Math.floor((usable * s) / Math.max(1, slices - 1));
    const mag = spectrum(samples, off);
    const binHz = sr / N;
    const i0 = Math.max(1, Math.floor(lo / binHz));
    const i1 = Math.min(mag.length - 1, Math.ceil(hi / binHz));
    let e = 0;
    for (let i = i0; i <= i1; i++) e += mag[i];
    total += e;
  }
  return total / slices;
}

/** Spectral centroid in Hz — "how bright is this", in one number. */
export function centroid(samples, sr, lo = 40, hi = 16000, slices = 4) {
  const usable = samples.length - N;
  if (usable <= 0) return 0;
  let num = 0, den = 0;
  for (let s = 0; s < slices; s++) {
    const off = Math.floor((usable * s) / Math.max(1, slices - 1));
    const mag = spectrum(samples, off);
    const binHz = sr / N;
    const i0 = Math.max(1, Math.floor(lo / binHz));
    const i1 = Math.min(mag.length - 1, Math.ceil(hi / binHz));
    for (let i = i0; i <= i1; i++) { num += mag[i] * i * binHz; den += mag[i]; }
  }
  return den > 0 ? num / den : 0;
}

export const toDb = e => 10 * Math.log10(Math.max(e, 1e-30));

/** Total RMS, for sanity checks. */
export function rms(samples) {
  let s = 0;
  for (let i = 0; i < samples.length; i++) s += samples[i] * samples[i];
  return Math.sqrt(s / samples.length);
}
