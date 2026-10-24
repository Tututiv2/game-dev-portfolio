// Every cosmic event, in one list.
//
// Each entry is a self-contained module under ./ implementing ./api.js, and each
// owns its own files. Adding an event is one import and one line here.
//
// The star module ships TWO definitions (a flare and a dimming), which is why
// this list is flattened rather than written by hand.

import BLACKHOLE from './blackhole.js';
import WORMHOLE from './wormhole.js';
import IMPACT from './impact.js';
import MOON from './moon.js';
import RIFT from './rift.js';
import { STAR_EVENTS } from './star.js';

export const EVENTS = [
  BLACKHOLE,
  WORMHOLE,
  IMPACT,
  MOON,
  RIFT,
  ...STAR_EVENTS,
];

export function byId(id) { return EVENTS.find(d => d.id === id); }

// The catalog entries each event needs for its own trigger building. Merged into
// the main catalog by catalog.js, so no event ever edits it.
export { CATALOG_ADDITIONS as BLACKHOLE_CAT } from './blackhole.js';
export { CATALOG_ADDITIONS as WORMHOLE_CAT } from './wormhole.js';
export { CATALOG_ADDITIONS as IMPACT_CAT } from './impact.js';
export { CATALOG_ADDITIONS as MOON_CAT } from './moon.js';
export { CATALOG_ADDITIONS as RIFT_CAT } from './rift.js';
export { CATALOG_ADDITIONS as STAR_CAT } from './star.js';
