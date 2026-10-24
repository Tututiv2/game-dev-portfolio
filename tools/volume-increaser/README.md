# Volume Increaser

A Windows audio booster that goes past 100% by capturing a playback device and replaying it with gain, plus a Chromium extension for boosting a single browser tab.

Windows does not let the master fader go above 100%. Volume Increaser does three things in one window: it sets the master and per-app faders to their maximum, and it provides a real "overdrive" path that takes the signal from one device, multiplies it, and plays it out of another. The bundled browser extension covers the common case of a quiet video in a tab.

## Features

- Master volume slider for the default output, and a one-click "set every app on this device to 100%"
- Overdrive: capture from one device, play to another with 100-300% gain, adjustable live
- Any playback device can be the source via WASAPI loopback (listed as `[loopback]`), so no virtual cable is required
- Still supports VB-Audio Virtual Cable as a capture source for a classic cable setup
- Boost a single app by sending only that app to a spare output in the Windows volume mixer, then looping that device back to your speakers
- Chromium extension (Manifest V3): in-page Web Audio gain above 100% on every `<audio>` and `<video>`, including iframes

## How it works

- **Loopback removes the cable requirement.** Any output you are not listening to, such as an unused HDMI endpoint or an existing virtual device, works as the "cable". It is captured with `WasapiLoopbackCapture`, buffered, scaled, and played to the real speakers through shared-mode `WasapiOut`.
- **A feedback guard in the engine, not the UI.** Capturing and playing on the same device builds a loop that gets louder until it clips. `Start()` refuses that pairing itself, so no future caller can wire it up.
- **Cleanup on partial failure.** A review pass fixed three resource bugs: an `MMDevice` COM leak when repopulating the device lists, a crash on device switch when an endpoint exposes no volume control, and WASAPI clients leaked when `Start()` threw part-way through initialisation (it now calls `Stop()` and rethrows). The gain path clamps rather than wraps.

## Tech

C# / .NET 8 WPF, NAudio 2.2.1 (WASAPI capture, loopback and render); JavaScript Web Audio for the browser extension.

## Running it

```
dotnet build VolumeIncreaser.csproj -c Release
```

For the extension, open `chrome://extensions`, enable Developer mode, and choose "Load unpacked" on the `chrome-extension/` folder. Build outputs (`bin/`, `obj/`) are not included.

Loudness above 100% can clip and distort; start low.
