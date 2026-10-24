// Mantissa + exponent decimal. The scale ladder ends somewhere past "eat a
// galaxy", and a float dies at 1e308 -- silently, as Infinity, which on screen
// reads exactly like a finished game rather than like a broken one. So no
// number on the ladder is ever a float.
//
// Adapted from HOLLOW/app/src/sim/decimal.js. Pure: no DOM, no Date, no random.
//
// Invariant: either (m === 0 && e === 0), or 1 <= |m| < 10.

export class D {
  constructor(m = 0, e = 0) {
    this.m = m;
    this.e = e;
    this.norm();
  }

  norm() {
    if (!isFinite(this.m) || this.m === 0) { this.m = 0; this.e = 0; return this; }
    if (Math.abs(this.m) >= 10 || Math.abs(this.m) < 1) {
      // Jump by the log rather than looping: normalising 1e300 one decade at a
      // time is 300 divisions for a number we already know the size of.
      const k = Math.floor(Math.log10(Math.abs(this.m)));
      this.m /= Math.pow(10, k);
      this.e += k;
      // and floating point can leave the mantissa a hair outside the range
      while (Math.abs(this.m) >= 10) { this.m /= 10; this.e += 1; }
      while (Math.abs(this.m) < 1) { this.m *= 10; this.e -= 1; }
    }
    return this;
  }

  static of(n) {
    if (n instanceof D) return new D(n.m, n.e);
    if (Array.isArray(n)) return new D(n[0] || 0, n[1] || 0);
    const x = typeof n === 'string' ? Number(n) : n;
    if (!isFinite(x) || x === 0) return new D(0, 0);
    const e = Math.floor(Math.log10(Math.abs(x)));
    return new D(x / Math.pow(10, e), e);
  }

  /** The only way to build a number a float cannot hold. */
  static fromLog10(l) {
    if (!isFinite(l)) return new D(0, 0);
    const e = Math.floor(l);
    return new D(Math.pow(10, l - e), e);
  }

  clone() { return new D(this.m, this.e); }
  isZero() { return this.m === 0; }

  add(o) {
    o = D.of(o);
    if (this.m === 0) return o.clone();
    if (o.m === 0) return this.clone();
    const [hi, lo] = this.e >= o.e ? [this, o] : [o, this];
    const shift = lo.e - hi.e;
    if (shift < -17) return hi.clone();           // lo is below hi's precision
    return new D(hi.m + lo.m * Math.pow(10, shift), hi.e);
  }

  sub(o) { o = D.of(o); return this.add(new D(-o.m, o.e)); }
  mul(o) { o = D.of(o); return new D(this.m * o.m, this.e + o.e); }

  div(o) {
    o = D.of(o);
    if (o.m === 0) return new D(0, 0);            // dividing by nothing yields nothing
    return new D(this.m / o.m, this.e - o.e);
  }

  pow(n) {
    if (this.m === 0) return new D(0, 0);
    if (this.m < 0 && n !== Math.round(n)) return new D(0, 0);
    return D.fromLog10(this.log10() * n);
  }

  log10() { return this.m === 0 ? -Infinity : Math.log10(Math.abs(this.m)) + this.e; }

  /** Only meaningful below 1e308. Past that this is Infinity, which is the point. */
  toNumber() { return this.m * Math.pow(10, this.e); }

  cmp(o) {
    o = D.of(o);
    if (this.m === 0 && o.m === 0) return 0;
    if (this.m === 0) return o.m > 0 ? -1 : 1;
    if (o.m === 0) return this.m > 0 ? 1 : -1;
    const s = Math.sign(this.m);
    if (s !== Math.sign(o.m)) return s > 0 ? 1 : -1;
    if (this.e !== o.e) return (this.e > o.e ? 1 : -1) * s;
    if (this.m === o.m) return 0;
    return this.m > o.m ? 1 : -1;
  }

  gte(o) { return this.cmp(o) >= 0; }
  gt(o) { return this.cmp(o) > 0; }
  lt(o) { return this.cmp(o) < 0; }
  lte(o) { return this.cmp(o) <= 0; }
  eq(o) { return this.cmp(o) === 0; }
  max(o) { return this.gte(o) ? this.clone() : D.of(o); }
  min(o) { return this.lte(o) ? this.clone() : D.of(o); }

  // Plain to a million, scientific after. Big numbers stay readable and small
  // ones stay familiar -- 4,200 means more to a player than 4.20e3.
  toString() {
    if (this.m === 0) return '0';
    if (this.e < 0) return this.toNumber().toFixed(Math.min(2, -this.e + 1));
    if (this.e < 6) {
      const n = this.toNumber();
      return n >= 1000 ? Math.floor(n).toLocaleString('en-US') : n.toFixed(n < 10 ? 1 : 0);
    }
    return this.m.toFixed(2) + 'e' + this.e;
  }

  // Saves are JSON. A class instance is not, so it goes out as a pair.
  toJSON() { return [this.m, this.e]; }
}

export const d = (n) => D.of(n);
export const ZERO = () => new D(0, 0);

/**
 * A huge number means nothing on its own. 4.7e24 kg is a fact; "about one
 * Earth" is a sentence. Every readout on the ladder carries one of these.
 *
 * `kg` is the real mass and `l` is derived from it, never typed. A hand-typed
 * log is a rounding error waiting to happen: 24.776 is a hair ABOVE
 * log10(5.97e24), so Earth's own mass read as "about 81 moons".
 */
export const ANCHORS = [
  { kg: 70, one: 'one person', many: 'people' },
  { kg: 1.5e3, one: 'one car', many: 'cars' },
  { kg: 1.5e5, one: 'a blue whale', many: 'blue whales' },
  { kg: 4.5e7, one: 'a battleship', many: 'battleships' },
  { kg: 5.9e9, one: 'the Great Pyramid', many: 'pyramids' },
  { kg: 1.0e12, one: 'a small hill', many: 'hills' },
  { kg: 8.1e14, one: 'Everest', many: 'Everests' },
  { kg: 4.0e17, one: 'a comet', many: 'comets' },
  { kg: 9.4e20, one: 'Ceres', many: 'Ceres-sized rocks' },
  { kg: 7.35e22, one: 'the Moon', many: 'moons' },
  { kg: 5.97e24, one: 'one Earth', many: 'Earths' },
  { kg: 1.9e27, one: 'Jupiter', many: 'Jupiters' },
  { kg: 1.99e30, one: 'the Sun', many: 'suns' },
  { kg: 2.0e35, one: 'a star cluster', many: 'star clusters' },
  { kg: 1.5e42, one: 'a galaxy', many: 'galaxies' },
  { kg: 1.5e45, one: 'a galaxy cluster', many: 'galaxy clusters' },
  { kg: 1.5e53, one: 'the observable universe', many: 'observable universes' },
];
for (const a of ANCHORS) a.l = Math.log10(a.kg);

function countText(r) {
  if (r < 10) return r.toFixed(1);
  if (r < 1e6) return Math.round(r).toLocaleString('en-US');
  return D.of(r).toString();
}

/** "about one Earth", "about 12 Earths", "about a galaxy". Kilograms in. */
export function compare(n) {
  const v = D.of(n);
  const l = v.log10();
  if (!isFinite(l)) return 'nothing at all';
  if (l < ANCHORS[0].l) return 'less than one person';
  let best = ANCHORS[0];
  // the epsilon is not fussiness: log10 of a mantissa plus its exponent and
  // log10 of the whole number differ in the last bit, which is enough to make
  // Earth's exact mass fall one anchor short of Earth
  for (const a of ANCHORS) if (l >= a.l - 1e-9) best = a;
  const ratio = Math.pow(10, Math.min(300, l - best.l));
  if (ratio < 1.35) return 'about ' + best.one;
  return 'about ' + countText(ratio) + ' ' + best.many;
}

/** The whole line: "4.71e24 kg — about one Earth". */
export function readout(n, unit = 'kg') {
  const v = D.of(n);
  return v.toString() + ' ' + unit + ' — ' + compare(v);
}
