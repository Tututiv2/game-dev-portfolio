# SplitCast — setup

Music on Twitch, silence on YouTube, from **one** encode.
Nothing to install: ffmpeg and SteelSeries Sonar are already on this machine.

---

## Why it works

Streamlabs Multistream forks the stream **after** the audio is baked in — that is
why there is no per-platform mute anywhere in its UI, and no setting will add
one. So the fork moves onto your PC instead:

```
Spotify ─────────────────────────────┐   (never enters Streamlabs)
                                     │
Game + Mic → Streamlabs → one clean encode
                             │
                             ↓ rtmp://127.0.0.1:1935/live
                        ┌──────────┐
                        │ SplitCast│
                        └────┬─────┘
              ┌──────────────┴──────────────┐
              ↓                             ↓
          YouTube                        Twitch
      bit-for-bit copy            same video + Spotify
```

Video is **copied** on both branches — never re-encoded — so the second
destination costs no GPU. Only Twitch's audio is rebuilt (one AAC encode,
about 1% of a core).

**Upload is still doubled.** Two full streams leave the machine, same as any
multistream. Check you have the headroom before your first real run.

---

## 1 · SteelSeries Sonar — make a music-only bus

Streamer Mode is already on if each channel shows **two columns** of controls.
Sonar calls the two mixes **Personal** and **Stream** — there is no "Monitoring".

**First, clear the red boxes in the MASTER panel:**

1. **Select Stream Mix** → `SteelSeries Sonar - Stream`
2. **Select Mic Input** → your real mic, e.g. `Microphone (Realtek(R) Audio)`
3. Leave **Personal Mix** on your headphones/speakers.

**Then read the columns.** Each channel (GAME/CHAT/MEDIA/AUX/MIC) has two:

| Column | Icon | What it is |
|---|---|---|
| Left | headphones | **Personal** — what *you* hear |
| Right | broadcast | **Stream** — the bus SplitCast reads |

**Then set the mutes:**

4. **Right column (Stream):** mute GAME, CHAT, AUX, MIC. Leave **MEDIA
   unmuted** at 100%.
5. **Left column (Personal):** unmute all five. This is your own audio.
6. Top row: **Media** tab → add Spotify. **Game** tab → add your game.

The `SteelSeries Sonar - Stream` capture device is now carrying *only Spotify*.
That is the music bus.

> Your live music volume is **Media's slider in the right (Stream) column**.
> SplitCast's own volume slider only applies on restart.

## 2 · Streamlabs — hand over a clean stream

1. **Settings → Audio**: set **Desktop Audio** to `SteelSeries Sonar - Gaming`
   (*not* Default — Default would pick up the music).
2. **Leave your mic exactly as it is.** Fewer moving parts.
3. **Settings → Stream**: turn **Multistream OFF**, choose **Custom RTMP**.
4. Server: `rtmp://127.0.0.1:1935/live`  ·  Stream key: `stream`
   (both are copy-buttons in the SplitCast window).

## 3 · SplitCast

Run **`Create Shortcut.bat`** once. It puts SplitCast on your Desktop and in the
Start menu, with its own icon and **no black console window**. Open it from
there from now on; `SplitCast.bat` still works and is the one to use if you ever
want to watch it start up in a console.

1. Double-click the **SplitCast** icon.
2. Paste your **Twitch** and **YouTube** stream keys.
3. Music plays on: **Twitch**. Source: pick the full name from the dropdown —
   `SteelSeries Sonar - Stream (SteelSeries Sonar Virtual Audio Device)`.
   The short name enumerates but ffmpeg will not open it. Press **Test** — `OK`.
4. Press **START RELAY**. It reads `WAITING FOR STREAMLABS`.
5. Hit **Go Live** in Streamlabs. SplitCast flips to **LIVE**.

Start SplitCast *before* going live. It has to be holding the port when
Streamlabs dials in.

### Streaming WITHOUT the relay — Direct mode

Section **0 Mode** switches between two ways of reaching both platforms.

* **Split** — what the rest of this file describes. Music on Twitch only, and
  you upload the stream twice.
* **Direct** — plain Streamlabs Multistream. **One upload.** No relay involved.
  The trade is that music is now inside the single encode, so it plays on
  **both** platforms or on neither.

Pick one and press **APPLY MODE**. Then three things, none of which SplitCast
can do for you, and all of which fail *silently* if skipped:

1. **Restart Streamlabs.** It reads the ingest file only at startup. Skip this
   and nothing works, with no error explaining why.
2. **Go Live → Twitch toggle.** ON in Direct, OFF in Split. Leaving it ON in
   Split publishes to Twitch twice — once from Streamlabs with no music, once
   from the relay — and the two fight over the channel.
3. **Direct only — give Streamlabs the music.** In Split the relay adds music
   afterwards, so Streamlabs never captures it. Add **+ → Audio Output Capture**
   with the device `SteelSeries Sonar - Media`, or the stream is silent where
   the music should be. SplitCast checks and says which channels it found; mute
   that source again when you go back to Split, or Twitch gets music twice.

The overlay and the chat window work in both modes.

## 4 · Now-playing overlay (optional)

1. In SplitCast, press **START OVERLAY**, then **Preview** to see it.
2. In Streamlabs: **+ → Browser Source**.
3. URL `http://127.0.0.1:5570/`, width **1920**, height **1080**.
4. Tick **Shutdown source when not visible** and **Refresh browser when scene
   becomes active**.

The card reads Spotify's window title — no Spotify API, no login, no token to
expire mid-stream.

**It shows on both platforms.** A browser source lives in the Streamlabs scene,
so it is inside the single encode. That is why the caption defaults to
*"Music on Twitch!"* — on YouTube it reads as a pointer to the stream that has
the music. Restricting it to one platform would mean re-encoding video there,
which throws away the whole reason this is cheap.

---

## If something breaks

| What you see | What it is |
|---|---|
| Stuck on `WAITING FOR STREAMLABS` | Streamlabs is still on Multistream, or the server/key don't match exactly. |
| `music OFF (device failed)` | Sonar's Streamer Mode is off, so the Stream device does not exist. **The stream stays up** — this is the deliberate fallback. |
| Bus is silent though Spotify is on Media | **MEDIA is muted in the right (Stream) column.** Each channel has two mutes; the Personal one being open is not enough. |
| Music on YouTube too | Streamlabs' Desktop Audio is on *Default* instead of `Sonar - Gaming`. |
| Discord / Aux missing from stream | Sonar exposes only **one** non-mic capture bus and this design spends it on music, so Streamlabs reads `Sonar - Gaming`. Route those apps to **Gaming** too, or add each Sonar channel as its own *Audio Output Capture* source. Never add **Media**. |
| Overlay is a black box | Browser source needs a transparent page — don't set a custom background colour on the source. |
| Overlay stuck on one song | Spotify was restarted; press **Refresh** on the browser source. |

**Failure mode to know:** if ffmpeg dies, both platforms blip while it restarts
(~2 s). Streamlabs multistream is more robust in that one respect — this is the
price of per-platform audio.

## Checks

`Test.bat` — 69 checks. The live layer stands up a real RTMP listener, pushes a
real encoded stream through it, and measures both branches: it confirms the clean
branch is level-identical to its source and that the music tone sits ~34 dB down
in it.
