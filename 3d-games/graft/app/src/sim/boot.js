// Assembles the parts that must not import each other.
//
// Every cosmic event ships the building that causes it. Those entries land in
// the catalog HERE, at startup, because an event module imports catalog.js and
// catalog.js importing the events back would be a cycle.
//
// Import this once, early. world.js does.

import { register } from './catalog.js';
import {
  BLACKHOLE_CAT, WORMHOLE_CAT, IMPACT_CAT, MOON_CAT, RIFT_CAT, STAR_CAT,
} from './events/registry.js';

export const REGISTERED = register([
  ...BLACKHOLE_CAT, ...WORMHOLE_CAT, ...IMPACT_CAT,
  ...MOON_CAT, ...RIFT_CAT, ...STAR_CAT,
]);
