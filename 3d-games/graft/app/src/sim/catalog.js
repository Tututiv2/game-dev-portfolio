// THE ONE RULE: everything you can see, you can make.
//
// This is NOT "the world starts empty". The world starts full -- forests,
// crystal fields, coastline, a sea. The rule is that none of it is off-limits:
// every kind of thing standing on this planet is an entry in this file, so
// anything you can see growing, you can grow. There is scenery; there is
// nothing that is ONLY scenery.
//
// The suite asserts it: any object instance whose `kind` is not creatable here
// is a bug, not set dressing. So the horizon is a place AND a list.

export const KIND = {
  BLOOM: 'bloom',
  CRYSTAL: 'crystal',
  TREE: 'tree',
  KELP: 'kelp',
  HAB: 'hab',
  MAST: 'mast',
  KILN: 'kiln',
  BEACON: 'beacon',
  TOWER: 'tower',
};

// stages: what it looks like as it grows, in order. `at` is seconds from the
// previous stage. The last stage is what it finally becomes.
// yield: what you get for harvesting it at final stage (and whether it survives)
// needs: what it costs to plant
// unlock: what must already exist before you may plant it
export const CATALOG = [
  {
    id: KIND.BLOOM,
    name: 'Bloom',
    verb: 'plant',
    blurb: 'A tuft that lights itself. It is the first thing on this planet that was not weather.',
    needs: {},
    stages: [
      { name: 'sprout', at: 0, h: 0.35 },
      { name: 'bloom', at: 42, h: 0.9 },
      { name: 'thicket', at: 150, h: 1.5 },
    ],
    harvest: { biomass: 4 },
    regrow: 1,               // drops back to this stage instead of vanishing
    maxSlope: 0.65,
    settles: 0,
  },
  {
    id: KIND.CRYSTAL,
    name: 'Spire',
    verb: 'seed',
    blurb: 'Mineral, and it does grow — slowly, and only where the ground is bare.',
    needs: { biomass: 6 },
    stages: [
      { name: 'seed', at: 0, h: 0.5 },
      { name: 'shard', at: 90, h: 1.8 },
      { name: 'spire', at: 300, h: 4.2 },
    ],
    harvest: { shard: 5 },
    regrow: 1,
    maxSlope: 0.8,
    settles: 0,
  },
  {
    id: KIND.TREE,
    name: 'Tree',
    verb: 'plant',
    blurb: 'It takes twenty minutes to become a tree and it will still be there tomorrow.',
    needs: { biomass: 5 },
    stages: [
      { name: 'sapling', at: 0, h: 1.1 },
      { name: 'young', at: 150, h: 3.6 },
      { name: 'tree', at: 480, h: 7.2 },
    ],
    harvest: { biomass: 14 },
    regrow: 1,
    maxSlope: 0.6,
    maxHeight: 4.6,          // trees do not grow on bare alpine rock
    settles: 0,
  },
  {
    id: KIND.KELP,
    name: 'Kelp',
    verb: 'seed',
    blurb: 'The only thing that will take root under the water, and it glows down there.',
    needs: { biomass: 8 },
    stages: [
      { name: 'holdfast', at: 0, h: 0.6 },
      { name: 'fronds', at: 120, h: 2.6 },
      { name: 'forest', at: 380, h: 5.0 },
    ],
    harvest: { biomass: 20 },
    regrow: 1,
    maxSlope: 0.9,
    underwater: true,        // the ONLY kind that may be planted below the sea
    settles: 0,
  },
  {
    id: KIND.HAB,
    name: 'Hab',
    verb: 'raise',
    blurb: 'A dome with a door. Put three near each other and somebody names the place.',
    needs: { biomass: 20, shard: 10 },
    stages: [
      { name: 'frame', at: 0, h: 1.2 },
      { name: 'sealed', at: 70, h: 2.4 },
      { name: 'lit', at: 210, h: 2.6 },
    ],
    harvest: null,           // you do not harvest somebody's house
    maxSlope: 0.35,
    settles: 1,              // counts toward a settlement
  },
  {
    id: KIND.MAST,
    name: 'Mast',
    verb: 'raise',
    blurb: 'It listens. Things grow faster inside its reach, because somebody is paying attention.',
    needs: { shard: 18 },
    stages: [
      { name: 'pylon', at: 0, h: 3.0 },
      { name: 'rigged', at: 80, h: 7.5 },
      { name: 'live', at: 230, h: 8.0 },
    ],
    harvest: null,
    maxSlope: 0.5,
    settles: 0,
    aura: { radius: 22, growth: 1.6 },
  },
  {
    id: KIND.KILN,
    name: 'Kiln',
    verb: 'raise',
    blurb: 'Turns what grew into what lasts. It works while you are somewhere else.',
    needs: { biomass: 40, shard: 20 },
    stages: [
      { name: 'pit', at: 0, h: 1.0 },
      { name: 'stack', at: 110, h: 3.0 },
      { name: 'firing', at: 300, h: 3.4 },
    ],
    harvest: null,
    maxSlope: 0.3,
    settles: 0,
    produces: { alloy: 1, every: 26 },
  },
  {
    id: KIND.BEACON,
    name: 'Beacon',
    verb: 'raise',
    blurb: 'Neon. It does nothing except be visible from the far side of the world, which turns out to be the point.',
    needs: { alloy: 12, shard: 30 },
    unlock: 'village',
    stages: [
      { name: 'mount', at: 0, h: 2.0 },
      { name: 'strung', at: 90, h: 6.0 },
      { name: 'burning', at: 240, h: 6.5 },
    ],
    harvest: null,
    maxSlope: 0.4,
    settles: 0,
    neon: true,
  },
  {
    id: KIND.TOWER,
    name: 'Tower',
    verb: 'raise',
    blurb: 'Twelve floors of people who came because you left a light on.',
    needs: { alloy: 40, shard: 60, biomass: 60 },
    unlock: 'town',
    stages: [
      { name: 'core', at: 0, h: 4.0 },
      { name: 'floors', at: 220, h: 14.0 },
      { name: 'occupied', at: 640, h: 16.0 },
    ],
    harvest: null,
    maxSlope: 0.25,
    settles: 4,
    neon: true,
  },
];

/**
 * Cosmic events bring their own buildings -- the thing that causes them
 * deliberately. They register here at boot rather than being written into the
 * list above, because an event module already imports this file and having
 * this file import them back would be a cycle. See ./boot.js.
 */
export function register(entries) {
  for (const e of entries || []) {
    if (!e || !e.id) continue;
    if (CATALOG.some(c => c.id === e.id)) continue;
    CATALOG.push(e);
    KIND[e.id.toUpperCase()] = e.id;
  }
  return CATALOG.length;
}

export function spec(kind) { return CATALOG.find(c => c.id === kind); }
export function isCreatable(kind) { return !!spec(kind); }

/** Total seconds from planting to final stage. */
export function growTime(kind) {
  const s = spec(kind);
  return s.stages.reduce((a, st) => a + st.at, 0);
}

/** What a settlement of this many habs is called, or null. */
export function tierOf(count, C) {
  if (count >= C.grow.city) return 'city';
  if (count >= C.grow.town) return 'town';
  if (count >= C.grow.village) return 'village';
  return null;
}

/** What a settlement is allowed to raise for itself, as it grows. */
export const SELF_BUILD = {
  village: [KIND.HAB, KIND.TREE, KIND.HAB, KIND.MAST],
  town: [KIND.HAB, KIND.HAB, KIND.KILN, KIND.BEACON, KIND.TREE],
  city: [KIND.TOWER, KIND.HAB, KIND.BEACON, KIND.HAB, KIND.TOWER],
};
