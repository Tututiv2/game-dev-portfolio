# Recorded weather — sources and licences

The project shipped its first version with **no audio files at all**; rain, roof, wind and thunder
were synthesised. The owner listened to two rounds of synthesis and asked for real recordings, so
outdoor rain and the roof drumming are now recordings. **Wind, thunder and all foley are still
synthesised** — wind was never the complaint and a recorded wind bed is mostly microphone rumble,
and thunder is a harder no, for a reason worth reading (below).

`src/audio/synth.js` is still live and still tested: if these files are missing or fail to decode,
`samples.js` falls back to it and the game runs anyway. `tests/synth.test.mjs` covers that path.

Both sources are **public domain or CC0** — no attribution is legally required and nothing here
is share-alike, so this stays usable if the space game ever ships. Credit is given anyway.

| file | source | author | licence |
|---|---|---|---|
| `rain.wav` | [Light Rain Distant Thunder July 5th 2016](https://commons.wikimedia.org/wiki/File:Light_Rain_Distant_Thunder_July_5th_2016.wav) via Wikimedia Commons, originally [kvgarlic on Freesound](https://freesound.org/people/kvgarlic/) | kvgarlic | **CC0** |
| `roof.wav` | [Rain on a veranda and thunder](https://commons.wikimedia.org/wiki/File:Rain_on_a_veranda_and_t.ogg) | ezwa | **Public domain** |

## What was done to them

Everything below is reproducible from the sources; nothing was hand-edited in a DAW.

- **Chosen by measurement, not by ear.** Candidates were scored on level steadiness (0.25 s window
  coefficient of variation), low-frequency contamination (traffic and thunder rumble under the
  rain), spectral flatness (tonal content = birds, voices, music) and source quality. `rain.wav`
  comes from the steadiest 12 s of its recording, at 94.5 s in, where cv is 0.064 and there is no
  measurable rumble. One rejected candidate was an 8 kHz mono upload — no treble at all above
  4 kHz, which would have made the whole inside/outside effect impossible.
- **Looped by folding, not by crossfading in place.** Each loop is cut `length + 1 s`, and the
  extra second is folded back over the head. The wrap is therefore a pair of samples the recording
  really produced in sequence, so `loop: true` has no seam. Measured wrap step: 2.15x the mean
  sample step for rain, 1.99x for roof — i.e. ordinary.
- **`roof.wav` is a mono recording widened into stereo by taking two DIFFERENT stretches** of it for
  left and right (offsets 2.5 s and 14 s). Duplicating one channel would collapse the drumming to
  the middle of the head; a delay would comb-filter it. Two stretches of the same rain are
  decorrelated and statistically identical, which is exactly what is wanted.
- **The roof bed was low-passed, and that is not a taste decision.** The recording was made under a
  veranda — outdoors, with the open-air rain reaching the microphone too — so it measured *flat*
  from 2 kHz to 16 kHz (−11.6, −12.1, −12.7 dB by octave). Indoors you hear the drumming through
  the boards. That shelf of treble put enough 2–8 kHz into the room to mask the sound coming
  through a cracked window, which is the one effect this project exists to demonstrate: the check
  for it fell to 4.0 dB against a 6 dB threshold. Two poles at ~1.1 kHz turn "under a veranda" into
  "under a roof", and it went to 11.7 dB — better than the synth ever managed.
- **Filtering happens BEFORE the loop is folded, never after.** A one-pole filter starts at y=0, so
  running it over an already-folded loop ramps the head up from silence and un-does the seam.
  Measured: the wrap step went to 10.9x the mean sample step, against 2.0x in the right order.

## Why thunder is NOT a recording

A public-domain strike was prepared and rejected, and the reason generalises.

The source had steady rain under it, so the rain was removed by spectral subtraction — its average
magnitude spectrum measured from a strike-free stretch and subtracted from every STFT frame of the
sharpest-onset strike in the file. That worked: 19 dB between the strike head and the residual tail,
up from 11 dB.

It failed on physics instead. **A recording carries the air absorption of its own distance already
baked in.** The cleaned strike measured a 404 Hz spectral centroid with 4 kHz already 39 dB down —
i.e. it *is* a distant strike. BUILD_PROMPT §3.5 exists to make one sound serve as both a crack
overhead and a rumble a mile off, by filtering for distance at play time; applying that to a
recording charges it for a distance it already paid at the microphone. Near-versus-far collapsed
from 2092/291 Hz to 232/144 Hz, and no amount of tuning fixes it, because you cannot put back treble
a microphone never captured. This is the same double-counting that made thunder inaudible in an
earlier round (an inverse-square law in a gain, plus a panner rolloff on top) wearing a new hat.

The synth strike stays, because it is the only version that can be both things.
- **Levels match the synth exactly** (rain RMS 0.17, roof RMS 0.19), so every calibrated decibel in
  the acoustic model carried over unchanged rather than needing a re-tune.
- **Stored as 16-bit WAV rather than Ogg or MP3**, deliberately. Both encoders pad the start and end
  of a stream, which puts a gap in a looping `AudioBufferSourceNode`. Seamless beds are the whole
  premise here, so ~5 MB is the cheap side of that trade.

Regenerate with `tools/prepare-audio.mjs` (needs ffmpeg and network access).
