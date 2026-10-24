# ZombieSurge

A wave-based zombie shooter in .NET 8, built twice: a top-down 2D version in WPF and a first-person 3D version on Veldrid, plus a 3D level editor.

ZombieSurge started as a 2D round-survival prototype and grew into a small engine-less 3D game. Rather than use a game engine, the 3D version renders with Veldrid directly, and levels are plain JSON made of axis-aligned boxes and spawn points, authored in a WPF editor with a live 3D viewport. Shared tuning and level data live in a platform-independent core library.

## Features

- **Classic2D** (WPF): top-down rounds, mag-and-reserve ammo, reloads, knife melee, wave breaks, points
- **Game** (Veldrid): first-person version of the same loop with hitscan shooting, melee and rounds
- **Editor** (WPF 3D): place, select, duplicate and delete boxes and zombie spawns, edit coordinates in a grid, set the player start, load and save the game's `default.json`
- Preset layouts: default arena, small playtest arena, district map
- Shared `ZombieSurge.Core`: `GameBalance` tuning, `LevelData`, JSON level IO with a fallback to the built-in arena

## How it works

- **Backend selection.** The 3D game tries Vulkan first (native SPIR-V), then OpenGL, then Direct3D 11 last because SPIR-V-to-HLSL cross-compilation can mis-compile. Startup failures are written to a log and shown in a native message box rather than disappearing.
- **The 3D renderer was fully broken, and it was two bugs.** Per-object uniforms were written with `GraphicsDevice.UpdateBuffer`, which executes immediately, so every draw in the frame saw only the last value. They are now recorded into the command list so each update runs in order with its draw. Separately, the world matrix was composed translate-then-scale, which is wrong under System.Numerics' row-vector convention; it is now scale-then-translate.
- **Frame timing.** `DateTime.UtcNow` only ticks about every 15.6 ms on Windows, which made the frame delta jitter badly; the loop now uses `Stopwatch`.
- **Cheap HUD.** The HUD is text rendered with ImageSharp and uploaded as a texture. It is only re-rendered when a key built from the visible values changes, instead of every frame. The 2D version caches frozen WPF brushes and pens for the same reason.
- **Simple, explicit collision.** Hitscan is a ray-versus-sphere test against each zombie within a 120-unit range, and the world is axis-aligned boxes, which keeps levels trivially serialisable.

## Tech

C# / .NET 8, WPF (2D game and editor), Veldrid 4.9 with Veldrid.SPIRV and SDL2 windowing, SixLabors ImageSharp and Fonts, System.Text.Json.

## Running it

```
dotnet build ZombieSurge.slnx
dotnet run --project ZombieSurge.Game        # 3D
dotnet run --project Classic2D               # 2D
dotnet run --project ZombieSurge.Editor      # level editor
```

Requires Windows and a GPU with Vulkan, OpenGL or Direct3D 11. Build outputs (`bin/`, `obj/`) and NuGet packages are not included; `dotnet build` restores them.
