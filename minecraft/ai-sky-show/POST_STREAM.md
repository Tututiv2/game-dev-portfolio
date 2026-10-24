# POST_STREAM — the gates nobody has run yet (in order)

Everything below needs the PC free: Rocket League and Streamlabs closed. Every heavy script refuses while
`Streamlabs / RocketLeague / obs64` run (`-Force` overrides). The owner does only the 🧑 steps;
a Claude session does the rest.

| # | Step | Command (from `AISkyShow\`) | Pass when |
|---|---|---|---|
| 1 | Build + install ShowCam | `powershell -NoProfile -ExecutionPolicy Bypass -File showcam\install.ps1` | jar in the Fabric-26.1.2 `mods\`; no mixin errors in `logs\latest.log` on first launch |
| 2 | Create the show server | `... -File scripts\setup_server.ps1` | `server\` exists, port 25640, offline, whitelisted, no worlds copied |
| 3 | Build + deploy ShowControl | `... -File scripts\deploy_plugin.ps1` (`-SkipBuild` uses the hand-built jar) | jar in `server\plugins\` |
| 4 | Boot | `... -File scripts\start_server.ps1 -Detached -WaitReady` | first boot builds every island (minutes) |
| 5 | Ruleset | `py -3 scripts\rcon.py "show ruleset sprint30"` | 13 `set` lines (if all FAILED: `cmd-sender: console` in `server\plugins\ShowControl\config.yml`) |
| 6 | Event gate | launch Kaito: `cd orchestrator; py -3 -c "from show import clients; from show.config import SHOW; clients.launch(clients.spec_for(SHOW.characters[0]))"`, then `py -3 scripts\gate_events.py --player Kaito --chat --pilot-port 25650` | exit 0 (≥ 10 event kinds on the feed) |
| 7 | Macro gate | `py -3 tests\live_macros.py --slot 0` (fresh profile: `show reset` first) | read `episodes\_live_gate\<time>\` — screenshots + result per step. Most likely to fail: `talk_to`, long hub walks, `fish`, `place_minion`, multi-word `craft` |
| 8 | Camera | camera client online → `show camera ShowCam` → every ShowCam shot on a moving player | smooth, no HUD, no snap on stop, no "moved too quickly" spam |
| 9 | 🧑 OBS | open OBS → Tools → WebSocket Server Settings → **Enable** (keep port 4455 + auth) | — |
| 10 | Scenes | `cd orchestrator; py -3 run.py obs-setup` | 3 AISky scenes; Grid tiles show the right windows; overlay source has "Control audio via OBS" |
| 11 | 🧑 Voices (optional) | drop a clean 3–10 s clip per character at `voices\<id>\ref.wav` (see `voices\README.md`); until then Windows voices are used | `py -3 -m show.tts Kaito "test line" determined --engine indextts` sounds right |
| 12 | 5-minute smoke | `py -3 run.py preflight` until READY, then `py -3 run.py episode --minutes 5` | right windows captured, voices audible in the .mkv, outputs in the episode folder |
| 13 | 🧑 Episode 1 | double-click `Start Episode.bat` | he watches it |

Report numbers at every gate: cost/hour, turns/minute, cache-hit, stuck count, GPU/CPU, dropped frames.
