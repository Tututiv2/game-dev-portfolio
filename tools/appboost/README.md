# AppBoost

A per-app volume dial for Windows that goes past 100%, built on WASAPI process loopback with no virtual audio cable.

Windows lets you turn a single app down but never up. AppBoost lists every app currently making sound and gives each one a dial up to about 400%. It captures that one process's audio, amplifies it, and plays it back, while the rest of the system is untouched. There is no routing to set up and nothing to install besides the app.

## Features

- Lists every app with an active audio session, across all active render endpoints
- One dial per app, live while audio plays; rows update in place so a refresh never resets a dial mid-drag
- Per-process capture through the WASAPI process loopback API, with the COM interop written by hand
- Gain plus soft saturation, tuned for music rather than a level-reactive limiter
- A separate `probe` console harness and a `tone` generator used to measure every claim

## How it works

- **The capture tap is after the app's own volume and mute.** I measured this before designing around it: the probe toggles the app's session volume 100%/50% twenty times inside one continuous capture and pools each phase, giving a peak ratio of 0.509. Muting captures digital silence. So the app cannot be silenced and replayed. Instead its session volume is set to 3% and the captured copy is boosted by `dial / 0.03`, which puts the direct path 30 dB under the boosted copy. In a float32 pipeline that attenuation costs nothing.
- **The obvious makeup gain was wrong.** Against a known 440 Hz tone, the capture returned a constant 0.21187 of the generated amplitude at every level and at both 48 and 96 kHz. Ignoring it turned a 4x boost into 0.86x, quieter than no boost. Rather than hardcode the constant, the engine calibrates at engage time from the session peak meter (which reads the level before the volume scalar) against the captured peak, and re-trims about every 100 ms over the same window. Verified end to end: a 3.83x boost at the virtual device and 3.79x at the speakers.
- **A wrong GUID, found with a control experiment.** `GetService` kept returning `E_NOINTERFACE`. Running the same interface declaration and raw vtable call against an ordinary device client resolved neighbouring interfaces fine, which isolated the problem to one IID: only its first block had been right.
- **The measured ceiling.** On my hardware the engine delivers a clean 3.94x, but the speaker endpoint only shows 1.89x (5.5 dB), because baseline audio already sat 5.7 dB under full scale. The boost uses essentially all the headroom that exists; past 0 dBFS extra gain is clipping. I also measured and rejected a post-gain peak limiter (about 10% louder) and a compressor (no gain at all on music).

## Tech

C# / .NET 8 WPF, NAudio 2.2.1, hand-written COM interop for `ActivateAudioInterfaceAsync` process loopback.

## Running it

```
dotnet build app/AppBoost.csproj -c Release
dotnet run --project probe -- <AppName> --boost 3 --ab   # measurement harness
```

Requires Windows 10 2004+ (process loopback). Build outputs (`bin/`, `obj/`) are not included.

**Status:** works, and is at the physical headroom limit of the hardware it was tested on. If the app is force-killed it can leave the target app at 3% volume; `probe <app> --check` reports and repairs that.
