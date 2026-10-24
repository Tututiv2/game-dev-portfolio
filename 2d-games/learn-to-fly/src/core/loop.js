// §4.1 — the most important 40 lines in the project.
//
// Fixed-timestep accumulator at 120 Hz with an interpolated render. Everything about how
// this game FEELS rests on the sim advancing in equal steps regardless of display rate, and
// everything about whether it survives an alt-tab rests on the two clamps.

export function createLoop({ step, render, dt, maxSteps, maxFrameDt, now }) {
  const clock = now || (() => performance.now());
  let acc = 0;
  let prev = clock();
  let raf = 0;
  let running = false;
  let timeScale = 1;                    // §15 — '[' and ']' for slow motion

  const stats = { fps: 60, steps: 0, frameMs: 0, sinceFps: 0, frames: 0 };

  function frame(t) {
    if (!running) return;
    const real = (t - prev) / 1000;
    prev = t;

    // Landmine 1. Without this clamp, alt-tabbing for thirty seconds delivers a 30 s dt, the
    // loop runs 3,600 steps in one frame and the browser hangs. With it a tabbed-out game
    // simply loses time, which is correct.
    acc += Math.min(real, maxFrameDt) * timeScale;

    let n = 0;
    const t0 = clock();
    while (acc >= dt && n < maxSteps) { step(dt); acc -= dt; n++; }
    if (n >= maxSteps) acc = 0;         // spiral-of-death guard: drop the backlog, do not queue it

    // alpha in [0,1) between the previous and current sim state. At 120 Hz physics on a
    // 60 Hz display every other state is dropped, and on a fast-moving sprite that reads
    // as judder unless the renderer interpolates.
    render(acc / dt, real);
    stats.frameMs = clock() - t0;
    stats.steps = n;

    stats.frames++;
    stats.sinceFps += real;
    if (stats.sinceFps >= 0.5) {
      stats.fps = stats.frames / stats.sinceFps;
      stats.frames = 0; stats.sinceFps = 0;
    }
    raf = requestAnimationFrame(frame);
  }

  return {
    stats,
    start() { if (running) return; running = true; prev = clock(); acc = 0; raf = requestAnimationFrame(frame); },
    stop() { running = false; cancelAnimationFrame(raf); },
    get timeScale() { return timeScale; },
    set timeScale(v) { timeScale = Math.max(0, Math.min(4, v)); },
    /** Drop accumulated time — call after a long blocking operation like a level build. */
    resync() { prev = clock(); acc = 0; },
  };
}
