# AURA

A 3D anime action game where the boss fight is locked to the beat of the song, so the music is the tell.

You start weak at dusk and goblins beat you down. An ogre lifts your little sister by the neck and raises a cleaver. Bass-boosted phonk starts two bars before the drop, the lead-in is cut like an "aura edit" (eye, veins, grip, hero shot, slow-motion run), the last beat before the drop is total silence, and on the drop the frame inverts to high-contrast black and white as you slash through the ogre's arm. From then on every ogre swing lands on a beat, and every parry, dash and hit is graded against the music. (AURA is a working title.)

## Features

- A scripted awakening cinematic that syncs camera cuts, slow motion and a black-and-white impact frame to the song's drop
- A beat-locked boss with 7 moves (slam, sweep, quick backhand, a delayed swing that winds up and holds, double, stomp shockwaves, an unparryable jump) dealt from a shuffled deck that never repeats a move, plus a drop combo that must be parried all the way through
- Four stackable skill layers, each toggleable in Settings:
  - **Directional parry**: flick the mouse toward the side the blade comes from. Perfect is -75/+60 ms; within 22 ms is an AURA parry with a full black-and-white freeze
  - **Flow**: every action graded against the beat; on-beat play climbs, off-beat breaks it
  - **Aura Reset**: one air dash, refilled only by a hit or a perfect parry in the air
  - **Deflect-aim**: perfect-parry a thrown spear and it flies down your crosshair
- A Dojo with parry, deflect, air-dash and flow drills, a BPM slider, a millisecond timing strip and tap calibration
- Third- and first-person cameras (`V`), cel-shaded procedural characters, pooled effects and a composite post shader (invert, black and white, speed lines)
- A synthesised, copyright-free phonk track built in, or drop your own tracks into `music/` and the drop and tempo are detected automatically

## How it works

- **Three clocks.** Real time drives UI and hit-stop, game time is scaled by slow motion, and audio time drives the song, every beat-locked attack and every skill grade. A press is stamped on the audio clock, and a beat-locked hit is graded at `impact + outputLatency`, the moment it is actually heard. Pausing suspends the AudioContext so the beat-locked world freezes with the song.
- **The swings were drawn late, and a test measured it.** The strike animation started on the beat, but the blade connects partway through behind pose easing, so every blow was drawn 69 to 101 ms after the beat the parry is graded on, outside the 60 ms late window. `tests/swing_sync.mjs` plays the real ogre rig headlessly (the three.js scene graph, no WebGL) and finds the frame the blade connects. The contact point was fitted from two tempos (slam 4.9% + 56 ms, sweep 11.8% + 20 ms) and is now within 5.6 ms at 82, 110, 130 and 160 BPM, asserted in the suite. Swing and impact sounds are scheduled on the audio clock rather than fired by a frame.
- **Music analysis that generalises.** Drops are found first by the phonk "cut" (at least 50 ms under -60 dBFS inside continuous music, then the first strong 808), and tempo is a comb-filter scan from the drop onward. Autocorrelation had read a free-time intro as 112 BPM, and bass weighting broke the synthesised phonk's syncopated 808 to 104; the built-in track now reads 130.02 BPM in the browser self-test.
- **Smaller lessons.** A random masher reached Flow 6 when a +/-130 ms band was forgiven, so the window was tightened to +/-65 ms with one climb per beat. One parry press answers every blow landing within 35 ms of the first, so a goblin and the ogre on the same beat are one moment. A negative first-frame delta time parked an afterimage on screen forever, so every loop clamps dt to zero or more. And high-contrast black and white of a night scene is solid black, so exposure lifts with the effect and the strike camera sits low, looking up at a white sky.

## Tech

- Vanilla JavaScript ES modules, three.js r171 (vendored), Web Audio
- Not one asset file: every mesh, texture and sound is generated in code
- A pure `web/sim/` layer (beat grid, skill grading, combat tables, story director, audio analysis) tested headlessly with Node
- A small Python standard-library server

## Running it

```
py -3 server.py        # or: python server.py
```

Then open `http://127.0.0.1:5620/` (on Windows, `Play.bat` does this). Run the headless tests with `node tests/run.mjs` (`Test.bat`). Dev pages: `/audio-test.html`, `/actors-test.html`, `/world-test.html`.

**Status:** chapter 1 (the awakening and the ogre fight) and the Dojo are playable. Chapter 2 is designed but not built, and goblin lunge timing has not been measured against the beat yet.
