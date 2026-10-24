# Game Dev Portfolio

Games, engines, mods and tools I've built, in one place. About fifty projects across
**JavaScript/TypeScript, Java, C#/Unity, Python, Lua/Luau and GLSL**. They range from 3D planet engines
and multiplayer servers to desktop audio apps and a Minecraft launcher.

Most of these were built with AI pair-programming, and I directed the design, testing and verification.
I treat tests as part of the work: a lot of these projects ship with headless simulation suites that measure the
design claims ("is this upgrade actually worth buying?", "does the planet have enough buildable land?")
instead of just eyeballing them.

> Every project folder is source only. Build outputs, downloaded dependencies, server jars and worlds,
> and any assets I don't have the rights to redistribute were left out, so some projects need their
> dependencies installed before they run. Each folder's own README/docs say how.

---

## Highlights

| Project | What it is | Stack |
|---|---|---|
| [**VESPER**](3d-games/vesper) | Space game with 12 procedural planets you fly down to, land on and walk around. Surfaces are baked into cubemaps (28 → 132 fps at 1080p), and the close-up ground is real geometry from the same height field collision uses | three.js, Vite |
| [**The Still**](minecraft/the-still) | Original Minecraft server: a custom 3,840-block-tall dimension with 26 mine depths, a crash-safe SQLite store (10 hard kills, zero lost writes) and a building layout *solver* that replaced hand-placed coordinates | Java 25, Paper |
| [**SkyClone**](minecraft/skyclone) | A SkyBlock-genre MMO modelled system for system on Hypixel SkyBlock as one Paper plugin: 40 modules, 1,143 unit tests plus 680 live self-tests, built by orchestrating ~60 agents against a frozen API contract | Java 25, Paper |
| [**MyFire Launcher**](minecraft/myfire-launcher) | A real Minecraft launcher: any version, Forge/Fabric/vanilla, its own Java runtimes, and isolated installs. Pure Python stdlib, with 41 offline checks covering the full auth chain | Python, tkinter |
| [**Shadow**](tools/shadow) | Desktop AI dev environment: an author agent does the work and three independent reviewer agents attack it before you see it. Also includes an editor with live preview, a streaming build terminal and local voice cloning | Electron, TypeScript, FastAPI |
| [**Neon Nomad 3D**](3d-games/neon-nomad-3d) | Tower defence on a real globe you drive over the horizon of, built on a tangent-warped cube-sphere whose mapping inverts to 1e-16 | three.js |
| [**GRAFT**](3d-games/graft) | Third-person planet builder where everything you can see, you can grow. Includes seven cosmic events and a megastructure ladder, with 200 checks across ten suites | three.js |
| [**Learn to Fly**](2d-games/learn-to-fly) | A no-Flash remake of the 2009 classic. The flight model is pure and headless-tested, and the economy is tuned by simulating a player day by day | Canvas2D |
| [**SubtitleStudio**](tools/subtitle-studio) | Local CapCut-style animated auto-subtitles: Whisper on the GPU, word-level karaoke timing, 10 styles × 5 reveal modes | Python, PySide6, ffmpeg |
| [**UMAP**](tools/umap) | Every file in this repo as one 3D map, placed by what the code *says* rather than where it sits: TF-IDF → LSA → UMAP, with a from-scratch UMAP fallback and a live HUD that lights up files as an AI agent reads them | Python, numpy, three.js |
| [**SplitCast**](tools/splitcast) | Music on Twitch and silence on YouTube from **one** encode. A local RTMP fork copies the video and re-mixes only the audio | Python, ffmpeg |

---

## Everything

### 3D games
| Project | What it is | Stack |
|---|---|---|
| [vesper](3d-games/vesper) | Planets from orbit to on foot (see above) | three.js, Vite |
| [graft](3d-games/graft) | Planet builder, cosmic events, idle village | three.js |
| [aura](3d-games/aura) | Anime action game where every boss swing lands on a beat of the music, graded on the audio clock | three.js, Web Audio |
| [neon-nomad-3d](3d-games/neon-nomad-3d) | Tower defence on a drivable globe | three.js |
| [threshold](3d-games/threshold) | Acoustics probe: walk in from a storm and hear *where* the sound comes in. Uses a sealed-volume flood fill and openings modelled as their own sound sources | three.js, Web Audio |
| [lumber-tycoon-clone](3d-games/lumber-tycoon-clone) | LT2-style sandbox with map and tree editors | three.js, cannon-es |
| [space-engineers-lite](3d-games/space-engineers-lite) | Block-based space builder | three.js |
| [deadrails](3d-games/deadrails) | Co-op train survival with a multiplayer server | three.js, Socket.IO |
| [screenshot-to-world](3d-games/screenshot-to-world) | Drop in a photo and walk around inside it | three.js, Electron |
| [zombies-fps](3d-games/zombies-fps) | Single-file CoD-style zombies FPS | three.js |

### 2D & browser games
| Project | What it is | Stack |
|---|---|---|
| [gleaner](2d-games/gleaner) | Mobile tower defence where the base drives, and the throttle sets both your income *and* your difficulty | Canvas, ES modules |
| [learn-to-fly](2d-games/learn-to-fly) | Learn to Fly 1 & 2 remake | Canvas2D |
| [stronghold-crusader](2d-games/stronghold-crusader) | Isometric RTS with sprite and 3D editors | Canvas |
| [cosmic-forges](2d-games/cosmic-forges) | Factory / space 4X sim | TypeScript, Vite, vitest |
| [aether-conductor](2d-games/aether-conductor) | Node-graph incremental | JS, esbuild |
| [arc-raiders](2d-games/arc-raiders) | Idle game plus a multiplayer extraction shooter | JS, ws, three.js |
| [neon-warlord-td](2d-games/neon-warlord-td) | Neon tower defence | Canvas |
| [chrono-cycles](2d-games/chrono-cycles) | Spinning-rings idle game | HTML/JS |
| [void-keeper](2d-games/void-keeper) | Lighthouse survival idle | HTML/JS |
| [singularity](2d-games/singularity) | Singularity + Zombies incrementals | HTML/JS |
| [protocol-zero](2d-games/protocol-zero) | Five stacked prestige layers | HTML/JS |

### Minecraft (servers, mods, tools)
| Project | What it is | Stack |
|---|---|---|
| [shadow-prison-2](minecraft/shadow-prison-2) | Season 2 rewrite designed against 35 bug classes from season 1: money in `long` cents, crash-gated storage | Java 25, Paper, SQLite |
| [the-still](minecraft/the-still) | Zombie/prison hybrid server (see above) | Java 25, Paper |
| [skyclone](minecraft/skyclone) | SkyBlock-genre MMO (see above) | Java 25, Paper |
| [smhmines](minecraft/smhmines) | Void skyblock where raw ore is the currency, with a spawn mountain whose road is verified climbable | Java 25, Paper |
| [aincrad](minecraft/aincrad) | Floating castle in a custom 2048-high dimension, with a hand-written 43-command world editor | Java, Paper |
| [second-watch-mod](minecraft/second-watch-mod) | Co-op horror mod: a phantom wearing your partner's signed skin | Java 25, Fabric |
| [myfire-launcher](minecraft/myfire-launcher) | Minecraft launcher (see above) | Python |
| [aura-client](minecraft/aura-client) | Client mod: capes, custom main menu, account switcher | Java 25, Fabric |
| [aura-qol](minecraft/aura-qol) | QoL HUD mod with SkyBlock money tracking, price tooltips and 179 JUnit tests | Java 25, Fabric |
| [ai-village](minecraft/ai-village) | Ten local-LLM bots cooperating to kill the Ender Dragon, one of them a secret imposter | Node.js, Mineflayer, Ollama |
| [ai-sky-show](minecraft/ai-sky-show) | Production rig for a show where four AI characters each play a real Minecraft client | Python, Paper, Fabric, OBS |
| [shadertoy-post](minecraft/shadertoy-post) | Shadertoy shaders as Iris post-processing, verified by a WebGL2 compile + render harness | GLSL |

### Unity / .NET
| Project | What it is | Stack |
|---|---|---|
| [aftermarket-apocalypse](unity-dotnet/aftermarket-apocalypse) | Round-based zombies shooter with a serial-numbered skin economy. 213 EditMode + 11 PlayMode tests. Code only; store assets are not redistributable | Unity 6, C# |
| [zombiesurge](unity-dotnet/zombiesurge) | 2D (WPF) and 3D (Veldrid) wave shooter | .NET 8, C# |

### Roblox
| Project | What it is | Stack |
|---|---|---|
| [zombie-roller](roblox/zombie-roller) | Zombies FPS | Luau, Rojo |

### Tools & desktop apps
| Project | What it is | Stack |
|---|---|---|
| [shadow](tools/shadow) | AI dev environment with reviewer agents (see above) | Electron, TS, FastAPI |
| [umap](tools/umap) | 3D semantic map of this repo, with a live agent HUD (see above) | Python, three.js |
| [subtitle-studio](tools/subtitle-studio) | Animated auto-subtitles | Python, PySide6, Whisper |
| [splitcast](tools/splitcast) | Per-platform stream audio from one encode | Python, ffmpeg |
| [soundboard](tools/soundboard) | Global-hotkey soundboard that still passes keys through to the game | .NET 8, WPF, NAudio |
| [audio-capture](tools/audio-capture) | Mic / system / mixed recording straight to TTS-ready WAV | .NET 8, WPF, NAudio |
| [appboost](tools/appboost) | Per-app volume via WASAPI process loopback, no virtual cable | .NET 8, COM |
| [volume-increaser](tools/volume-increaser) | Audio booster above 100% | .NET 8, WPF |
| [audio-split](tools/audio-split) | Drop a clip in, get voice and music as two WAVs | Python |
| [rocket-league-ledger](tools/rocket-league-ledger) | Parses Rocket League replays into a stats dashboard | Python |
| [session-saver](tools/session-saver) | Park and restore every open Claude Code session in one click | PowerShell, WinForms |
| [factorio-mod](tools/factorio-mod) | Factorio mod: biters adapt to whatever you keep killing them with | Lua |

---

## Contact
Open an issue on this repo, or reach me through my GitHub profile.
