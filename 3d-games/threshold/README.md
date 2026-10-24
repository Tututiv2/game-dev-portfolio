# THRESHOLD

A first-person audio vertical slice about one idea: cozy is not quiet, cozy is filtered.

Walk out into a storm, walk into a cabin, shut the door behind you, then crack a window by degrees and hear exactly where the noise comes from. THRESHOLD is a probe for a larger space game: can the transition between outside and inside feel the way it does in real life? It also models the part most games miss. Going inside does not only subtract sound, it adds one. Outdoors, rain is a directionless hiss; indoors you gain the drumming on the roof overhead, which you cannot hear outside at all. That gain is what makes a room feel like shelter.

Headphones recommended.

## Features

- Voxel cabin with analog doors and windows (hold E or scroll to open by degrees)
- Positional rain, roof drumming, wind with gap whistle, distance-shaped thunder and foley
- Reverb sized from the room you are standing in
- Rain on glass, a sky, ambient-occluded voxel meshing
- **B** toggles between the full portal model and the baked crossfade most games ship, both rendered from the same material so the comparison is fair
- **M** solos a layer and **[ ]** trims the roof level live
- A debug HUD showing the live acoustic state

## How it works

- **One flood fill is the spine.** `volumes.js` fills empty cells: escaping to the sky means outdoors, a closed fill is a room, and the fill's boundary enumerates every opening. That one pass gives occlusion, portals, reverb size, roof coverage and rain culling. It is the same solve used for airtightness in space building games, so pressurisation can later subscribe to `{sealed, volume, apertures[]}` rather than re-deriving it.
- **An opening is its own sound source.** A door or window is not "the wall attenuates less"; it is a positional source at its own coordinates. That makes the noise come from the window, and it is physically right: a wall 1% open by area cannot give more than about 20 dB of isolation, because sound walks through the gap. The first 10% of opening buys most of the loudness, which is why a window is a dial, not a switch.
- **Measured, not felt.** 170 pure-logic checks plus 193 in-browser checks render the real Web Audio graph through `OfflineAudioContext` and FFT the samples. One renders the same moment facing opposite ways and asserts the stereo image flips; the baked backend fails it by construction. Boot from click to running is 0.42 s.
- **Correct in every component and still inert.** With every acoustic check green, the roof sat 16 dB above everything indoors, so opening the front door onto a downpour moved the total by only 1.5 dB. A sweep picked a roof level that makes stepping inside 15.5 dB quieter, opening the door 5.1 dB louder and cracking the window 5.3 dB louder, while keeping the roof the loudest thing in the room. Four checks now assert that player actions are audible, and all four fail against the old mix.
- **Assertions that only compare a thing to itself.** A and D were swapped because `right` was built as `up x forward`, which is left in a right-handed Y-up world, and a second inlined copy of the same vector made the tests agree with the bug. Thunder was inaudible while firing perfectly because distance was charged twice (an inverse-square law in a gain, then panner rolloff on top: -39 dB at 3 km). Both are now checked against something outside the model: the ear the real panner renders into, and absolute spectra.
- **Synthesis versus recordings, decided by measurement.** A single one-pole filter tilts rather than band-limits, which made the rain bed static-like; two poles moved its spectral centroid from 5703 Hz to 3032 Hz. Hundreds of overlapping drop oscillators had pushed boot to 9 seconds, and folding them into the noise bed brought it to 0.41 s. Outdoor rain and roof drumming are now CC0 recordings chosen by measurement from 42 candidates; thunder stays synthesised because a recording already carries its own distance and cannot be filtered for a new one. The synth remains the live fallback if a recording fails to load.

## Tech

- Vanilla JavaScript ES modules, three.js (vendored), Web Audio API
- No build step and nothing to install; Node.js only serves the files
- Puppeteer-core for the headless browser suite and screenshots (dev tools only)

## Running it

- `Play.bat` serves the folder on `http://localhost:5520` and opens it (ES modules do not load from `file://`).
- `Test.bat`, or `node tests/run-node.mjs` and `node tools/headless.mjs` (`--all` prints every check with its measured number).
- `?test` in the URL runs the in-browser suite; `?nolock` skips pointer lock.

The headless tools borrow puppeteer-core from a local `node_modules` that is not included in the repo.

**Status:** the slice is built and tested. The final judgement, whether the transition feels right to a listener, is still open.
