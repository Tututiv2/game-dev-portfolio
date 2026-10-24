// Every cosmic event's visuals, in one table, keyed by event id.
//
// Each module implements ./api.js and owns its own files. The renderer builds a
// module the first time its event is live, updates it every frame while it runs,
// and disposes it when the event ends -- so an event that never happens costs
// nothing but an import.

import * as blackhole from './blackhole.js';
import * as wormhole from './wormhole.js';
import * as impact from './impact.js';
import * as moon from './moon.js';
import * as rift from './rift.js';
import { FX as STAR_FX } from './star.js';

const MODULES = [blackhole, wormhole, impact, moon, rift, ...STAR_FX];

export const FX = {};
for (const m of MODULES) {
  if (m && m.id) FX[m.id] = m;
}

export function fxFor(id) { return FX[id] || null; }
