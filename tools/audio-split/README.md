# AudioSplit

Drag a video onto a .bat and get the voice and the music back as two separate 48 kHz WAV files.

I recorded a clip with music playing underneath by accident and needed the voice without it. DaVinci Resolve can do this with Voice Isolation, but only in the paid Studio build. AudioSplit does it locally with a trained source-separation model, and is set up so that the output drops straight onto an editing timeline.

## Features

- Drag-and-drop: drop any video or audio file onto `SPLIT AUDIO.bat`
- Optional start/end range, so only the damaged stretch of a long recording is processed
- Writes `<name>_VOICE.wav` and `<name>_MUSIC.wav` next to the source
- 48 kHz stereo WAV output, the format an editor timeline wants, with no second lossy encode
- Prints the exact placement instructions for the editor when a range was used

## How it works

- **A model, not a filter.** Speech and music occupy the same frequencies, so no EQ or gate can separate them. AudioSplit runs Demucs (`htdemucs`), a network trained on songs already split into stems.
- **Two stems, not four.** `--two-stems=vocals` skips the drums/bass/other split, about half the work of the full model, since everything that is not voice ends up in one file anyway.
- **Process only what is broken.** The range option exists because separation always leaves some artefact; running it over speech that was already clean would make that speech slightly worse. `-ss` is placed before `-i` so seeking into an hour-long file is instant.
- **Full-quality input.** ffmpeg extracts the audio to 48 kHz PCM first, so compression artefacts from a low-bitrate track are not baked into both stems.
- **An isolated environment.** The tool runs from its own venv created with `--system-site-packages`, so it reuses an existing CUDA PyTorch install (about 2.5 GB not downloaded again) while keeping Demucs and its dependencies out of the shared Python environment.

## Tech

Python 3, Demucs (htdemucs), PyTorch (CUDA optional), ffmpeg, a Windows batch front end.

## Running it

```
py -3 -m venv --system-site-packages .venv
.venv\Scripts\pip install demucs
```

Put ffmpeg on `PATH`, then drag a file onto `SPLIT AUDIO.bat`. The first run downloads the model (about 80 MB). The venv and model cache are not included.
