// A tiny pub/sub so medals can listen without physics knowing they exist (§7.6).
//
// The rule this enforces: NOTHING IN sim/ MAY IMPORT medals.js. The sim pushes plain data
// onto `p.events`, game/run.js drains that array once a frame and republishes here, and the
// achievement system subscribes. Wire it the other way and the physics gains a dependency on
// the achievement system, `node tests/run-tests.mjs` stops being able to import it, and the
// feel becomes unverifiable.

export function createBus() {
  const map = new Map();

  function on(type, fn) {
    if (!map.has(type)) map.set(type, new Set());
    map.get(type).add(fn);
    return () => off(type, fn);
  }
  function off(type, fn) { const s = map.get(type); if (s) s.delete(fn); }

  function emit(type, payload) {
    const s = map.get(type);
    if (s) for (const fn of [...s]) fn(payload, type);
    const all = map.get('*');
    if (all) for (const fn of [...all]) fn(payload, type);
  }

  return {
    on, off, emit,
    once(type, fn) { const un = on(type, (p, t) => { un(); fn(p, t); }); return un; },
    clear() { map.clear(); },
    /** Republish a drained sim event array. */
    drain(events) { for (const e of events) emit(e.type, e); events.length = 0; },
  };
}

export const bus = createBus();
