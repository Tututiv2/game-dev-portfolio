# SplitCast

Music on Twitch and silence on YouTube from a single encode, by forking the stream on the local machine.

Streamlabs Multistream uploads one encoded stream to Streamlabs' cloud, which fans it out, so by the time it forks the audio is already baked in. No setting can give two destinations different audio. SplitCast moves the fork onto the PC: Streamlabs streams to `127.0.0.1:1935`, and one ffmpeg process sends YouTube an untouched copy while Twitch gets the same video plus a re-mixed audio track with music added.

```
Streamlabs --(one encode)--> 127.0.0.1:1935 --+-> -c copy                     -> YouTube
                                              +-> video copied, audio remixed -> Twitch
```

## Features

- Per-platform audio from one encode; video is never re-encoded, so the second destination costs no GPU (only one AAC encode, about 1% of a core)
- `ffmpeg -listen 1` is the RTMP server: no nginx-rtmp or SRS
- A fan-in RTMP proxy that accepts both Dual Output connections (horizontal and vertical) and reads each stream key in flight
- Two modes: Split (relay) and Direct (Streamlabs' own cloud), switched by patching one Streamlabs config file
- Live music volume control through ffmpeg's `azmq` filter, using a hand-written ZeroMQ client
- A now-playing overlay (browser source) read from the music player's window title, with album art from the iTunes Search API
- A merged Twitch and YouTube chat window, with OAuth-scoped moderation (implicit grant for Twitch, PKCE for YouTube)

## How it works

- **A music bus with no new software.** The machine's virtual audio mixer exposes its streaming mix as a capture device, so that mix is repurposed as a music-only bus. Measured on real hardware: -35.7 dB with music playing, -91.0 dB (digital silence) with music paused and other audio at full volume, 55 dB of separation.
- **`amix` defaults that would have broken the mix.** `normalize` defaults to true and divides every input by the input count, so adding music would have made game and mic 6 dB quieter. `dropout_transition` defaults to 2 s, turning every capture gap into a two-second swell. Both are set explicitly, and the live check asserts the music branch lands at +3.0 dB against the clean branch, the expected figure for an uncorrelated sum.
- **Stream keys are discovered, not configured.** YouTube mints a new ingest key at every Go Live, so a pasted key works once. RTMP sends the key as a plain AMF0 string in the `publish` command, so the proxy reads it as bytes pass through without altering them. I measured an 18 ms causal chain showing that relaunching on the live connection killed the vertical stream for the session, so the key is now read from Streamlabs' service files 6.5 s before the socket opens, and only from a file whose server is SplitCast's own listen address.
- **A music problem must never become a stream problem.** If the music device cannot be opened, the supervisor relaunches as pure passthrough and says so. Relaunches it requested itself are flagged so ffmpeg's exit-time "I/O error" is not mistaken for a device failure.
- **Tests that measure.** The live layer stands up a real RTMP listener, pushes a real encoded stream through it, and measures both branches. The passthrough check compares the clean branch against the source file within 0.5 dB rather than against a guessed absolute level. A hygiene check scans every source file for control characters and imports every module, after a NUL byte in a JavaScript string passed every syntax check.

## Tech

Python 3 standard library + tkinter, ffmpeg 7.1.1, Win32 via ctypes, vanilla JS overlay pages. Requires Streamlabs Desktop and a virtual audio mixer that exposes a stream mix as a capture device (set up with SteelSeries Sonar; see `SETUP.md`).

## Running it

- `SplitCast.bat` (or `Create Shortcut.bat` for a console-free desktop shortcut via `pythonw`)
- `Devices.bat` lists the capture devices ffmpeg can open
- `Test.bat` runs three suites (74, 176 and 38 checks), including the RTMP loopback and a build of the real window, withdrawn

`config.json` (stream keys and API credentials) is not included; defaults are used when it is missing and settings are saved from the window.

**Status:** verified end to end against a local RTMP loopback with measured audio. It has not been run against real Twitch or YouTube ingests.
