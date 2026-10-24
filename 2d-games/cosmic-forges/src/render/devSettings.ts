/**
 * Live-tunable values for the dev menu (no persistence; visual only).
 */
export type DevSettings = {
  /** Skydome star layer */
  sky: {
    /** Higher = more cells / denser field */
    starSpacing: number;
    /** Base visibility for main star layer (0.97–0.999) */
    starThreshold: number;
    /** Scales twinkle in shader */
    twinkle: number;
    /** 0 = blue/cool, 1 = warm */
    starColorWarmth: number;
    /** Milky band multiplier */
    milky: number;
    /** World-space camera length: below = “near” full stars */
    lodOrbitNear: number;
    /** Between near and this = “mid” layer */
    lodOrbitMid: number;
    /** Above this = mostly point stars */
    lodOrbitFar: number;
  };
  /** System map: three bands by camera distance to each body (full ≤ lodFullMax < mid ≤ lodMidMax < dot) */
  solar: {
    /** d ≤ this: full mesh */
    lodFullMax: number;
    /** d ≤ this (and > full): low-poly; greater: point only */
    lodMidMax: number;
    /** Base radius of the far “dot” mesh (world units) */
    dotSize: number;
  };
};

export const defaultDevSettings = (): DevSettings => ({
  sky: {
    starSpacing: 320,
    starThreshold: 0.985,
    twinkle: 1,
    starColorWarmth: 0.45,
    milky: 1,
    lodOrbitNear: 22,
    lodOrbitMid: 45,
    lodOrbitFar: 90,
  },
  solar: {
    lodFullMax: 10,
    lodMidMax: 38,
    dotSize: 0.09,
  },
});

export const devSettings: DevSettings = defaultDevSettings();

export function resetDevSettings(): void {
  const d = defaultDevSettings();
  Object.assign(devSettings.sky, d.sky);
  Object.assign(devSettings.solar, d.solar);
}
