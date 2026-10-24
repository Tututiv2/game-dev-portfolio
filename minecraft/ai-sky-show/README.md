# Can 4 AIs Beat SkyBlock?

A production rig where four AI characters play a SkyBlock-style MMO in real Minecraft clients while a fifth AI directs the camera.

Each character drives its own real game client, plays co-op in 30-minute sprints on my SkyClone server, types in chat, and thinks out loud in its own voice. Inner monologue is never shown to the other players. A Director AI picks moments and calls cinematic shots on a spectator client, OBS records it all, and every episode ends with chapters, a list of best moments, short-form candidates and a full transcript. The goal is that the AIs actually play the game, and that the footage is something you would want to watch.

## Features

- Four characters with their own personalities, colours and voices, defined as data in `config/cast.json`
- 24 gameplay macros (walk, gather, fight, talk to NPCs, use menus) executed through TestPilot, an in-process client driver, so no OS input is ever sent
- A per-turn observation digest plus a screenshot fed to each character
- A Director that scores events (a rubric plus an LLM call) and sends smooth camera shots to a spectator client
- **ShowControl**, a Paper plugin that publishes the game as an event feed (SSE), a `/state` endpoint and a `/cmd` endpoint, with live profile resets and a 30-minute sprint ruleset
- **ShowCam**, a Fabric client mod for smooth client-side camera moves and unique window titles for capture
- A dashboard and an OBS browser-source overlay on a local web server
- Edit outputs per episode: `events.jsonl`, transcript, chapters, best moments, shorts and an EDL
- Alternate formats (hidden god, traitor, free-for-all, duos) saved as data presets for later

## How it works

- **One event shape everywhere.** The plugin feed, the orchestrator bus and the episode log all use the same JSON event (`src`, `type`, `players`, `drama 0-10`, `text`, `loc`, `data`). The Director, the overlay and the edit tools read one stream, which is what made it possible to build six parts in parallel against a single written contract (`CONTRACT.md`).
- **AI orchestration.** Each character is a persistent headless `claude -p` process in stream-json mode, run from a working directory outside the repo so no project instructions leak into the character. A measured run against a mock world: 26 turns in 1.5 minutes, about 3.1 s average latency, 81% prompt-cache hit rate, 0 errors and 0 refusals, with the characters playing the opening on their own.
- **Cost accounting that is actually correct.** `total_cost_usd` in the stream is cumulative per process, so per-turn cost is computed as a delta. A refusal is handled as "start a fresh session and carry memory over" instead of crashing the episode.
- **Every tunable is a live dial**, changeable mid-episode, and the whole orchestrator is Python standard library only.
- **Server-side aggregation.** Gathering is summarised per player every 30 seconds rather than per block, and item events fire only for rare drops or first-ever pickups, so the Director is not flooded with noise.

## Tech

- Python 3.13 (standard library only) orchestrator, dashboard and OBS WebSocket v5 client
- Java 25 Paper 26.2 plugin (ShowControl), depending on SkyClone
- Java 25 Fabric 26.1.2 client mod (ShowCam)
- TestPilot client driver, IndexTTS2 or Windows SAPI for voices, OBS for recording

## Running it

- `Test.bat` runs the headless suite: 254 tests, about 45 seconds, no server or client needed.
- `Dashboard.bat demo` plays a scripted fake episode in the overlay and dashboard.
- From `orchestrator/`: `py -3 run.py episode --mock --agents real --brain fake --fast-intro --no-obs --voice none` runs the full pipeline against a mock world at zero cost.
- `Start Episode.bat` runs preflight and then a real episode.
- Build the plugin with `plugin/gradlew.bat build` and the client mod with `showcam/gradlew.bat build`.

The show server, Minecraft clients, the SkyClone jar, OBS and voice models are not included in this repo. `scripts/setup_server.ps1` builds the server folder from a local SkyClone install.

**Status:** every part is built and tested headless, and the characters have played against the mock world. A full live episode on the real server has not been run yet.
