// Applies the active Dimension's modifiers + hooks into the running simulation.

import { MODIFIERS } from "../content/dimensions.js";

export function activeMods(state) {
  return state.dimension ? state.dimension.mods : [];
}

// Steady multipliers — called from recomputeMods.
export function applyDimensionMods(m, state) {
  for (const id of activeMods(state)) {
    const mod = MODIFIERS[id];
    if (mod && mod.apply) mod.apply(m, state);
  }
}

// Per-tick dimension effects (events, time-based). Marks mods dirty if a hook
// flips a steady flag so recomputeMods picks it up.
export function dimensionTick(state, dt) {
  const before = JSON.stringify([state._oddHour, state._pulseOn, state._qDouble]);
  for (const id of activeMods(state)) {
    const mod = MODIFIERS[id];
    if (mod && mod.onTick) mod.onTick(state, dt);
  }
  const after = JSON.stringify([state._oddHour, state._pulseOn, state._qDouble]);
  if (before !== after) state.epsDirty = true;
}

// Reaction when a node is placed.
export function dimensionOnPlace(state, node) {
  for (const id of activeMods(state)) {
    const mod = MODIFIERS[id];
    if (mod && mod.onPlace) mod.onPlace(state, node);
  }
}
