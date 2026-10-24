// Number formatting + small math helpers.
// Scientific notation above 1e6 per design spec; tidy fixed/short forms below.

const SUFFIX = ["", "K", "M", "B", "T", "Qa", "Qi", "Sx", "Sp", "Oc", "No", "Dc"];

export function formatNumber(n) {
  if (n === undefined || n === null || Number.isNaN(n)) return "0";
  if (!Number.isFinite(n)) return "Infinity";
  const neg = n < 0;
  n = Math.abs(n);

  let out;
  if (n < 1000) {
    out = n < 10 && n % 1 !== 0 ? n.toFixed(1) : Math.floor(n).toString();
  } else if (n < 1e6) {
    // short suffix form for readability between 1K and 1M
    const tier = Math.floor(Math.log10(n) / 3);
    const scaled = n / Math.pow(1000, tier);
    out = scaled.toFixed(scaled < 100 ? 2 : 0).replace(/\.00$/, "") + SUFFIX[tier];
  } else {
    // scientific above 1e6
    out = n.toExponential(2).replace("e+", "e");
  }
  return neg ? "-" + out : out;
}

// Per-second rate formatting.
export function formatRate(n) {
  return formatNumber(n) + "/s";
}

export function clamp(v, lo, hi) {
  return v < lo ? lo : v > hi ? hi : v;
}

export function lerp(a, b, t) {
  return a + (b - a) * t;
}

// Smooth approach for animated counters (frame-rate aware).
export function approach(current, target, dt, speed = 10) {
  const t = 1 - Math.exp(-speed * dt);
  return current + (target - current) * t;
}
