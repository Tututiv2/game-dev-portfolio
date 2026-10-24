import * as THREE from "three";

/**
 * Shared mutable game state (avoids circular imports between systems).
 */
export const G = {
  scene: null,
  camera: null,
  renderer: null,
  controls: null,
  /** @type {THREE.SpotLight | null} */
  headLamp: null,
  headLampOn: false,
  raycaster: null,
  lastTime: performance.now(),

  grids: [],
  interactables: [],
  celestialBodies: [],
  celestialObjects: [],
  bodyById: new Map(),
  activeUniverse: null,

  activePlanet: null,

  Player: {
    wrapper: new THREE.Object3D(),
    velocity: new THREE.Vector3(),
    onGround: false,
    jetpack: false,
    suitDampeners: true,
    inShip: false,
    controlledGrid: null,
    activeCockpit: null,
    height: 1.8,
    rotationX: 0,
    radius: 0.8,
  },

  CamState: { distance: 0, orbitX: 0, orbitY: 0 },

  Build: {
    activeSlotIndex: 0,
    selectedBlockId: 1,
    isActive: true,
    rotation: new THREE.Quaternion(),
    ghost: null,
    targetGrid: null,
    valid: false,
    mode: "large",
    localPos: new THREE.Vector3(),
  },

  Input: { w: 0, a: 0, s: 0, d: 0, sp: 0, c: 0, q: 0, e: 0, alt: false },

  Hotbar: [1, 2, 3, 4, 10, 6, 7, 8, 9, 11],
  isLibraryOpen: false,
  draggedItem: null,
};
