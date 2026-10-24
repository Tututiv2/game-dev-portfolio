// EVERY number lives here. Nothing reads a literal.

export const C = {
  seed: 11,
  tick: 1 / 30,

  planet: {
    radius: 118,         // small on purpose: a city goes over the horizon
    relief: 13.5,        // real hills, real basins
    detail: 58,          // IcosahedronGeometry detail is EDGE segments, not levels:
                         // faces = 20*(detail+1)^2, so 7 meant a 1,280-triangle
                         // planet. 58 is ~68k triangles and one draw call.
    sea: 0.7,            // OCEAN: ground below this is under water (~46% of the world)
    shore: 1.0,          // this far above the sea is sand
    alpine: 4.6,         // above this is bare rock
    snow: 6.2,
  },

  player: {
    height: 1.7,
    walk: 7.5,
    run: 13.5,
    turn: 2.4,
    reach: 4.0,          // how close you must be to touch something
    harvestTime: 0.75,
  },

  day: 300,              // seconds for one full turn of the sun

  // Growth is the idle layer: you plant, you leave, it is bigger when you return.
  grow: {
    offlineCap: 12 * 3600,
    settleRadius: 18,
    village: 3,
    town: 8,
    city: 16,
    // A settlement raises its own next building on this clock. It SLOWS as the
    // place gets bigger: at a flat rate a village reaches city in half an hour
    // and the whole ladder is over before you have walked anywhere.
    selfBuild: { village: 115, town: 260, city: 520 },
    selfBuildSpread: 13,
  },

  // Cosmic events: things that happen TO the planet on their own clock.
  events: {
    first: 240,          // nothing enormous happens in the first four minutes
    gap: 420,            // roughly this long between events
    gapPerActive: 260,   // and longer again while one is still running
    repeat: true,        // may an event happen more than once
    siteArc: 0.85,       // radians from the player an event may be sited.
                         // The planet has a far side; a black hole that
                         // arrives behind you is one you never see.
  },

  // The world is FULL when you arrive. None of it is off-limits: every kind of
  // thing standing on this planet is something you can plant or raise yourself.
  world: {
    scatter: 2600,       // candidate points considered at generation
    forestBias: 0.62,    // how much lowland favours trees
  },
};
