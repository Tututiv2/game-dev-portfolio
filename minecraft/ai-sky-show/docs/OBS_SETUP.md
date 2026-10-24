# OBS setup for the show rig

The show records with **OBS Studio** (not Streamlabs — the rig never touches Streamlabs). The
orchestrator drives OBS over **obs-websocket v5** with a stdlib client (`orchestrator/show/obs.py`).

## State of this PC (read 2026-10-03, read-only — nothing was changed)

`%APPDATA%\obs-studio\plugin_config\obs-websocket\config.json`:

| setting | value | what it means |
|---|---|---|
| `server_enabled` | **false** | the websocket server is OFF — must be turned on once |
| `server_port` | 4455 | matches `config/show.json` `ports.obs` |
| `auth_required` | true | a password is required |
| `server_password` | set (16 chars) | **you do not need to copy it anywhere** — when `obs_password` in `config/show.json` is empty, the rig reads it from this file |

OBS profile `Untitled`: canvas **1920x1080**, 60 fps, recording format **mkv**, record path
`D:/Videos/Edited Videos/TODO, ROCKET LEAGUE FADED SERIES`. The rig points recordings at the
episode folder while an episode runs and **puts that path back** when it stops (also on a crash).

## One-time steps (after the stream, with OBS closed or idle)

1. Open OBS -> **Tools -> WebSocket Server Settings** -> tick **Enable WebSocket server**.
   Leave port 4455 and authentication on. Click **OK**.
2. (Optional) If you prefer an explicit password, click *Show Connect Info*, copy the password into
   `config/show.json` -> `"obs_password"`. Otherwise leave it empty (see above).
3. Double-click nothing yet — from `AISkyShow\orchestrator` run:
   `py -3 run.py obs-setup` — this connects, reads the canvas, and builds three scenes
   (safe to run any number of times; it repairs rather than duplicates):
   - **AISky Grid** — the four player windows tiled 2x2 + the overlay on top
   - **AISky Intro** — every capture full-screen, exactly one shown at a time (used for the intro
     and for the Director's CUT_TO), plus the camera client
   - **AISky Cinematic** — the camera client full-screen + the overlay
4. Recording settings worth checking once (Settings -> Output -> Recording): encoder NVENC,
   format mkv (safe if anything crashes; Resolve imports mkv directly, or File -> Remux to mp4).

## How each source is set up (so you can fix one by hand if needed)

- **Window captures** `AISky Cap <Name>` / `AISky Cap Camera`: window `AISky - <Name>:GLFW30:java.exe`
  (ShowCam sets each client's title to `AISky - <Name>`; Minecraft is a GLFW window; our launcher runs
  it under **java.exe**, not javaw.exe). Window match priority = **"Window title must match"** — all
  five clients share class + exe, so any looser match would grab the wrong client. Capture method
  **Windows 10 (WGC)** — OpenGL windows capture black with BitBlt. Client area only, no cursor.
  A minimised window captures nothing: leave the clients open on the second monitor.
- **Overlay** `AISky Overlay`: browser source `http://127.0.0.1:5650/overlay`, sized to the canvas,
  **"Control audio via OBS" ON** (`reroute_audio`) — this is what puts the voice lines into the
  recording: the overlay page plays every caption's wav, and with reroute on that page becomes an OBS
  audio source. It is ONE shared source in all three scenes with "Shutdown source when not visible"
  OFF, so a scene switch never cuts a line mid-word. Audio monitoring is set to *Monitor and Output*
  so you hear the voices too (switch to *Monitor Off* in Advanced Audio Properties if it echoes).

## Audio during an episode

At record start the rig **mutes OBS's global Desktop Audio and Mic/Aux** (Spotify, Discord, the
game clients' own sound) and restores their previous mute state when it stops. The recording then
contains only the overlay audio: voice lines + narrator. Game sound from five clients at once is
noise; if you want some, add an *Application Audio Capture* of one client to the Grid scene by hand.

## Not done by the rig (yet)

- **ISO recordings** of each client (build prompt §4.6) need the *Source Record* plugin; not installed,
  so only the program feed is recorded. The EDL/best-moments timestamps refer to that file.
- The rig never changes OBS's websocket config, profile or encoder settings.
