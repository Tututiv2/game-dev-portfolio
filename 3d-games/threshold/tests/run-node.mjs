// Headless test runner: everything that does not need Web Audio or a GPU.
//
//   node tests/run-node.mjs
//
// The spectral tests (BUILD_PROMPT §8 checks 1–7) genuinely need OfflineAudioContext, so they live
// in the browser at ?test and are driven by tools/headless.mjs.

import { report, reset } from './harness.mjs';
import { run as runSynth } from './synth.test.mjs';
import { run as runSolver } from './solver.test.mjs';
import { run as runAcoustics } from './acoustics.test.mjs';
import { run as runContinuity } from './continuity.test.mjs';

reset();
runSynth();
runSolver();
runAcoustics();
runContinuity();

const r = report();
console.log(r.text);
process.exit(r.fail > 0 ? 1 : 0);
