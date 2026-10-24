/* global window */
/**
 * Optional runtime mapping: unit type string -> embedded glTF (data URL or path).
 * Populate via dev tools or future import UI. Used by mission WebGL overlay.
 */
window.MODEL_REGISTRY = window.MODEL_REGISTRY || {
  schemaVersion: 1,
  byUnitType: {}
};
