/**
 * Placeable structure “blueprints” for future plot-building (wood cost → build progress).
 * Gameplay wiring comes later; exposed on {@link globalThis.LT_BLUEPRINT_CATALOG} from game.js.
 */
export const BLUEPRINT_CATALOG = [
  { id: 'floor_large', label: 'Large floor', unit: 'm', w: 6, h: 0.08, d: 6, baseWood: 80, category: 'floor' },
  { id: 'floor_tile', label: 'Floor tile', unit: 'm', w: 1, h: 0.06, d: 1, baseWood: 8, category: 'floor' },
  { id: 'pixel', label: 'Tiny block', unit: 'm', w: 0.2, h: 0.2, d: 0.2, baseWood: 1, category: 'detail' },
  { id: 'wall_short', label: 'Short wall', unit: 'm', w: 3, h: 2, d: 0.25, baseWood: 30, category: 'wall' },
  { id: 'wall_tall', label: 'Tall wall', unit: 'm', w: 3, h: 4, d: 0.35, baseWood: 60, category: 'wall' },
  { id: 'door_std', label: 'Door frame', unit: 'm', w: 1.2, h: 2.4, d: 0.35, baseWood: 40, category: 'openings' },
  { id: 'wedge_2x2', label: '2×2 wedge', unit: 'm', w: 2, h: 0.25, d: 2, baseWood: 18, category: 'roof' },
];
