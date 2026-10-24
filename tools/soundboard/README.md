# SoundBoard

A global hotkey soundboard for Windows: press a key while a game has focus and a sound plays, without the game losing the key.

I wanted single keys to make noise mid-match without alt-tabbing, plus one button to silence everything the moment I start typing. Any key or mouse button 3/4/5 can be bound to any sound in a browsable library, and each binding decides whether the keystroke still reaches the game.

## Features

- Bind any key (with exact modifier matching) or mouse 3/4/5 to a sound
- Per-binding "pass to game" checkbox, on by default: the sound plays and the game still gets the key
- Retrigger modes: Restart, Overlap, Toggle
- One big Stop button and a global enable/disable hotkey
- A second output device, so the board can be routed into a virtual cable while you still hear it
- Short sounds decoded once to RAM; files over 30 seconds stream from disk
- WASAPI shared mode with a WaveOut fallback for endpoints that refuse WASAPI
- Bindings live in `config.json` next to the app, so deleting the build never loses them

## How it works

- **A low-level hook instead of `RegisterHotKey`.** A registered hotkey always eats the key, so binding `L` would mean the game never sees `L` again. `WH_KEYBOARD_LL` and `WH_MOUSE_LL` hand over the keystroke and let each binding choose. Left and right mouse are deliberately unbindable.
- **The hook callback does no work.** Windows silently unhooks any app whose hook takes longer than about 300 ms, with no error. The callback only decides which binding matched and whether to swallow the key, then hands playback to a worker. Auto-repeat is tracked so a held key is still swallowed consistently but does not re-fire the sound.
- **Two locks that must never wait on each other.** NAudio's mixer raises `MixerInputEnded` from the audio thread while holding its own lock. If that handler took the engine lock while `Play()` held it and waited on the mixer, all audio would deadlock, so cleanup is posted to a worker and the audio thread takes no lock.
- **A bug found in use: three copies running.** The window was minimised to a hidden tray icon, a second copy was opened and disabled, and sounds kept playing, because every instance installs its own system-wide hook. A named `Mutex` now enforces a single instance, and a second launch signals the first to restore itself, so launching again is the recovery action. Verified by launching four times in a row (one process survives).

## Tech

C# / .NET 8 WPF, NAudio 2.2.1, Win32 low-level hooks via P/Invoke.

## Running it

- `SoundBoard.bat` builds on first run (`dotnet build -c Release`) and launches `bin\SoundBoard.exe`.
- `Build.bat` rebuilds with full compiler output.
- `Self Test.bat` runs a headless harness that compiles the shipping engine, config and hook sources directly: modifier matching, config round trip, hook installation, device enumeration, decoding every sound in `Sounds\`, all three retrigger modes, streaming, and finally a loopback capture that records the PC playing its own sound (measured peak about -7 dBFS). It makes noise on purpose.

The sound library is not included; put your own files in `Sounds\`. Build outputs (`bin/`, `obj/`) are not included.
