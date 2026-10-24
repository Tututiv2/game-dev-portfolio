const DEFAULT_SIM_HZ = 30;

export type StepFn = (fixedDt: number) => void;

/**
 * Fixed timestep accumulator. Call {@link addFrame} each frame with real `deltaSeconds`.
 */
export class FixedStepper {
  private acc = 0;
  private readonly simHz: number;
  private readonly maxStepsPerFrame: number;
  public readonly fixedDt: number;

  constructor(
    private readonly onStep: StepFn,
    options: { simHz?: number; maxStepsPerFrame?: number } = {}
  ) {
    this.simHz = options.simHz ?? DEFAULT_SIM_HZ;
    this.maxStepsPerFrame = options.maxStepsPerFrame ?? 10;
    this.fixedDt = 1 / this.simHz;
  }

  /** Unscaled real time. */
  addFrame(deltaSeconds: number, timeScale: number): void {
    this.acc += deltaSeconds * timeScale;
    let steps = 0;
    while (this.acc >= this.fixedDt && steps < this.maxStepsPerFrame) {
      this.onStep(this.fixedDt);
      this.acc -= this.fixedDt;
      steps++;
    }
    // If the step cap was hit, drop the remaining backlog: otherwise a long stall
    // (tab hidden, GC pause) leaves `acc` growing without bound and the sim
    // max-steps every subsequent frame trying to catch up (spiral of death).
    if (this.acc > this.fixedDt) this.acc = this.fixedDt;
  }

  reset(): void {
    this.acc = 0;
  }
}
