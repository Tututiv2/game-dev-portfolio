// §9. Versioned, migration-aware, and guarded.
//
// The one thing here that is genuinely hard to retrofit is the PERSISTENCE BOUNDARY (§7.5):
//
//     `account` survives "new game". `ltf1` and `ltf2` do not.
//
// Medals are account-level and stay unlocked across all played games; challenges are
// per-playthrough. Discovering that at phase 11 means every existing player's lifetime
// counters start at zero (landmine 12), so it is built in from the start.
//
// The storage backend is injectable purely so `node tests/run-tests.mjs` can round-trip a
// save without a browser. In the game it is localStorage.

export const SAVE_VERSION = 3;
const KEY = 'learn-to-fly:save';

export function freshSave() {
  return {
    version: SAVE_VERSION,
    ltf1: {
      day: 1, cash: 0,
      upgrades: { rampHeight: 0, acceleration: 0, airResistance: 0, glider: 0, rockets: 0, rocketFuel: 0 },
      bestDistFt: 0, bestAltFt: 0, completed: false,
    },
    ltf2: {
      mode: 'story', day: 1, cash: 0,
      gear: { sled: 0, glider: 0, thruster: 0, payload: 0 },
      attrs: { lift: 0, power: 0, mass: 0, tough: 0 },
      obstacleHp: {}, challenges: {}, secrets: {},
      arcadeScore: 0, completed: false,
    },
    account: {
      bp: 0,
      medals: {},                                   // id -> ISO timestamp
      lifetime: {
        distFt: 0, airTimeS: 0, bounces: 0, destroyed: 0, destroyedKinds: {},
        diveFt: 0, reverseFt: 0, cashEarned: 0, bpEarned: 0, days: 0,
        maxAltFt: 0, maxSpeedMph: 0, arcadePoints: 0, pacifistDays: 0,
      },
      seenLtf1Ending: false,
    },
    settings: { volume: 0.7, muted: false, reducedMotion: false, keybinds: null },
  };
}

// ── migrations ─────────────────────────────────────────────────────────────────────────
// One entry per historical version, each taking vN to vN+1. A player who loses a 150-day
// run to a schema change does not come back (landmine 11), so this chain only ever grows.

const MIGRATIONS = {
  1: (s) => {
    // v1 had no account scope at all — medals lived inside ltf2 and died with a new game.
    const fresh = freshSave();
    fresh.ltf1 = { ...fresh.ltf1, ...(s.ltf1 || {}) };
    if (s.ltf2 && s.ltf2.medals) fresh.account.medals = s.ltf2.medals;
    fresh.version = 2;
    return fresh;
  },
  2: (s) => {
    // v2 had no lifetime counters, so cumulative medals (Traveler, Duration, Destruction,
    // Diving, Reverse) could never complete for an existing player. Seed what we can.
    const out = { ...s, version: 3 };
    out.account = out.account || {};
    out.account.lifetime = { ...freshSave().account.lifetime, ...(out.account.lifetime || {}) };
    if (out.ltf1 && out.ltf1.bestDistFt) out.account.lifetime.distFt ||= out.ltf1.bestDistFt;
    return out;
  },
};

export function migrate(save) {
  let s = save;
  let guard = 0;
  while (s.version < SAVE_VERSION && guard++ < 32) {
    const m = MIGRATIONS[s.version];
    if (!m) { s.version = SAVE_VERSION; break; }
    s = m(s);
  }
  return fill(s);
}

/** Deep-merge missing keys from a fresh save. Cheap insurance against a partial write. */
function fill(s) {
  const base = freshSave();
  const merge = (a, b) => {
    for (const k of Object.keys(a)) {
      if (b[k] === undefined || b[k] === null) b[k] = a[k];
      else if (typeof a[k] === 'object' && !Array.isArray(a[k]) && typeof b[k] === 'object') merge(a[k], b[k]);
    }
    return b;
  };
  const out = merge(base, s);
  out.version = SAVE_VERSION;
  return out;
}

// ── storage ────────────────────────────────────────────────────────────────────────────

function memoryBackend() {
  const m = new Map();
  return { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, v), removeItem: (k) => m.delete(k) };
}

export function createSave(backend) {
  const store = backend || (typeof localStorage !== 'undefined' ? localStorage : memoryBackend());
  let warning = null;

  function load() {
    let raw = null;
    try { raw = store.getItem(KEY); } catch { /* private mode, quota, whatever */ }
    if (!raw) return freshSave();
    try {
      const parsed = JSON.parse(raw);
      if (!parsed || typeof parsed !== 'object' || typeof parsed.version !== 'number') {
        throw new Error('shape');
      }
      return migrate(parsed);
    } catch (e) {
      // A white screen is the worst possible outcome here. Fall back visibly instead.
      warning = `Your save could not be read (${e.message}) and has been reset.`;
      return freshSave();
    }
  }

  function save(data) {
    try { store.setItem(KEY, JSON.stringify(data)); return true; }
    catch (e) { warning = `Could not write your save (${e.message}).`; return false; }
  }

  return {
    load, save,
    reset() { try { store.removeItem(KEY); } catch { /* ignore */ } return freshSave(); },
    /** "New game" — §7.5's boundary. account survives, the playthroughs do not. */
    newGame(data, which) {
      const fresh = freshSave();
      const out = { ...data };
      if (which === 'ltf1' || which === 'both') out.ltf1 = fresh.ltf1;
      if (which === 'ltf2' || which === 'both') out.ltf2 = fresh.ltf2;
      return out;
    },
    takeWarning() { const w = warning; warning = null; return w; },
  };
}
