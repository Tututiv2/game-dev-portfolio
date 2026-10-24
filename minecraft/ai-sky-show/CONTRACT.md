# CONTRACT — the seams between the parts of the show rig

Every builder reads this whole file first. If you need to change a seam, change it HERE and say so
in your report. The goal of the whole rig: **four AI characters ACTUALLY play SkyClone (a Hypixel
SkyBlock clone) on camera, talk in chat, think out loud in their own voices, and the owner gets a
watchable 30-minute episode + an edit list.** Footage must be watchable, not a tech demo.

Read for background: `../SkyblockClone/docs/AI_SHOW_BUILD_PROMPT.md` (the original brief — but
`DECISIONS.md` here overrides it: NO god, NO traitor in v1, 30-minute sprints, slow intro).

## 0. Ground rules for every builder (non-negotiable)
1. Write ONLY inside `D:\projects\AISkyShow\`. Everything else is read-only —
   especially `SkyblockClone/` (another AI session is actively editing it) and
   `SkyblockClone/tools/testpilot/` (import `pilot.py` from there via `sys.path`, never edit it).
2. **The owner is live-streaming right now.** Do NOT launch Minecraft clients, Paper servers, OBS,
   TTS/IndexTTS, Ollama, gradle daemons or anything GPU/CPU heavy. Writing code + fast unit tests
   (pure Python, `javac` of a few files) is fine. Before ANY heavier step run
   `tasklist | findstr /I "Streamlabs RocketLeague obs64"` — if anything matches, skip the step and
   list it under "needs a post-stream run" in your report.
3. Never use OS input (SendInput, keybd_event, mouse_event, SetForegroundWindow, SetCursorPos,
   desktop capture). Clients are driven only through TestPilot HTTP.
4. No filesystem-wide searches. Use known paths.
5. Python: 3.13 via `py -3`, **stdlib only** in the orchestrator unless this file says otherwise
   (the API backend may import `anthropic` lazily, guarded). No pip installs during the stream.
6. Every tunable is a live **dial** (§6), not a constant.
7. Write tests under `tests/` that run headless with no server/client (`py -3 -m unittest discover -s tests`).

## 1. Ports (all 127.0.0.1)
| Port | What |
|---|---|
| 25640 | show Paper server (game) — `online-mode=false`, `server-ip=127.0.0.1`, whitelist on |
| 25641 | show server RCON (password in `config/show.json`) |
| 25642 | ShowControl HTTP (event feed + state + cmd) — inside the Paper plugin |
| 25650–25653 | TestPilot HTTP, player clients 0–3 |
| 25654 | TestPilot HTTP, camera client |
| 25660 | ShowCam HTTP (smooth-camera mod, camera client only) |
| 5650 | Orchestrator: dashboard + overlay + SSE + audio files |
| 4455 | OBS WebSocket v5 (OBS default) |

## 2. Layout
```
AISkyShow/
  config/show.json      ports, paths, monitor tiles, model, backend, dial defaults
  config/cast.json      the four characters (name, account, colour, voice, personality...)
  config/presets.json   saved future formats (god/traitor/ffa/duos) — data only
  plugin/               ShowControl — Paper 26.2 plugin, depend: [SkyClone]        (builder A)
  scripts/              setup_server.ps1, reset_episode.ps1, start_server.ps1, rcon.py (A)
  server/               the show server (created by setup_server.ps1; gitignored-ish)
  showcam/              ShowCam — Fabric 26.1.2 client mod: smooth camera + window title (E)
  orchestrator/show/    the Python package `show`                                  (B, C, D)
  orchestrator/prompts/ system prompts + SkyBlock primer                           (B)
  overlay/              overlay.html (OBS browser source), dashboard.html          (D)
  voices/<char>/        reference clips (ref.wav, emotion clips)                   (D)
  episodes/<YYYY-MM-DD_epN>/  events.jsonl, transcript.md, chapters.txt, best_moments.md,
                        shorts.md, highlights.edl, cost.json, audio/               (C)
  Start Episode.bat · Setup.bat · Test.bat · Dashboard.bat
```

## 3. Event shape (ONE shape everywhere: ShowControl feed, orchestrator bus, events.jsonl)
```json
{"id": 123, "t": 1759500000123, "src": "server|agent|director|tts|obs|system",
 "type": "mob_kill", "players": ["Kaito"], "drama": 3,
 "text": "Kaito killed a Graveyard Zombie", "loc": {"world":"hub","x":1.5,"y":70,"z":-3},
 "data": {...}}
```
- `t` = epoch ms. `id` = per-source sequence. `drama` = 0–10 hint (0 noise, 3 notable, 6 big, 9 huge).
- `players` = SHOW character names when known (ShowControl knows account names; the orchestrator
  maps account→character via cast.json; ShowControl may emit account names — the bus maps them).

### Server event types (ShowControl emits)
`join, quit, profile_ready{first_join}, chat{message}, mob_kill{mob_id,mob_name,coins,boss?},
item_gain{item_id,amount,rarity,source} (only rarity>=RARE or first-ever of that item),
gather_summary{kind,what,amount} (aggregated per player per 30 s, never per block),
skill_level_up{skill,level}, collection_tier_up{collection,tier}, coins_milestone{purse},
island_change{from,to}, zone_change{from,to,island}, first_of_kind{what} ("first to reach the hub",
"first minion", "first skill 5", "first death"...), death{cause,coins_lost}, near_death{health,max}
(health < 15%, debounced 20 s), big_hit{damage,from}, ability_used{ability}, fish_caught{what,
sea_creature,treasure,quality}, calendar_start/end{event}, boss_spawn{mob_id,name},
episode_start{minutes}, episode_end{reason}, mark{label}, minion_placed{type,tier,count}`
(builder A, 2026-10-03: `minion_placed` added - SkyClone has no minion event, ShowControl polls the
profile's placed minions every 5 s. Every event also carries extra human fields in `data`, e.g.
`mob_name`, `item_name`, `to_name`; `gather_summary.amount` is the window's total for that kind and
`data.breakdown` has per-item counts. Full list: `plugin/README.md`.)

### Orchestrator event types
`say{char,text}` (typed in-game chat), `inner_voice{char,text,emotion}`, `agent_turn{char,goal,
actions,latency_s}`, `agent_action{char,tool,args,result,ok}`, `agent_stuck{char,seconds}`,
`director{decision,target,shot,seconds,line,score,reason}`, `cinematic_start/end{target,shot}`,
`tts_start/end{char,clip,duration_ms}`, `narrate{text}`, `scene{mode}`, `cost{usd,turns,...}`,
`replay_mark{start,end,label}`
- **added by C (2026-10-03):** `meeting{a,b,pairs,dist,island,all?}` (src `director`, drama 7, or 8
  with `all:true` the first time all four stand together) — first time characters come within 10
  blocks on the same island, from ShowControl `/state/<name>` positions; pairs meeting in the same
  check are ONE event. `scene{mode,target?,lower_third?}` is also emitted by the episode for the
  intro (one per character) and the overlay bridge maps it. `replay_mark.start/end` are epoch SECONDS.

## 4. ShowControl HTTP (port 25642, builder A)
- `GET /events` — SSE: `id: <id>\ndata: <event json>\n\n`. Honors `Last-Event-ID`. Heartbeat comment every 10 s.
- `GET /events/since?id=N` — JSON array of events with id > N (ring buffer of the last 5000).
- `GET /players` — `[{"name","uuid","online","island","zone","health","maxHealth","purse"}]`
- `GET /state/<name>` — the agent's structured state, everything a human reads off menus:
  ```json
  {"name","online","island","zone","pos":{x,y,z},"health","maxHealth","defense","mana","maxMana",
   "purse","bank","skills":{"FARMING":{"level":3,"xp":1234.5,"next":2000}},
   "collections":[{"id","tier","total"}],"held":{"id","name","rarity"},
   "armor":[...],"inventory":[{"id","name","count","rarity"}],"inventory_free":12,
   "nearby_mobs":[{"id","name","dist","health","maxHealth","level"}],
   "nearby_npcs":[{"name","dist"}],"warps_unlocked":["hub","..."],
   "minions":[{"type","tier","slot"}],"objective":"...","can_afford":[...],"next_unlocks":[...]}
  ```
  Fill what the `Sky` API exposes; omit what it doesn't (never invent). Must not throw for an offline player.
- `POST /cmd {"cmd":"..."}` → runs a console command on the main thread → `{"ok":true,"output":"..."}`.
- `GET /health` → `{"ok":true,"episode":{...},"tps":19.9}`.
- Commands (console/op): `/show reset` (wipe the four profiles), `/show start <minutes>`,
  `/show stop`, `/show freeze on|off`, `/show mark <label>`, `/show camera <account>` (spectator,
  hidden from players via hidePlayer), `/show cam orbit|follow|pushin|stop <target> [seconds]`
  (server-side FALLBACK camera — teleports the camera account smoothly every tick; ShowCam is the
  preferred path), `/show ruleset <name>` (applies SkyClone dials from `plugins/ShowControl/rulesets.yml`).

## 5. Orchestrator overlay/dashboard server (port 5650, builder D owns the pages, C/B emit)
- `GET /` dashboard.html · `GET /overlay` overlay.html · `GET /audio/<file>` wav clips · `GET /static/*`
- `GET /stream` — SSE of **overlay messages** (below). `GET /api/state` — full snapshot (chars,
  dials, cost, clock, scene). `GET/POST /api/dials` — read / set dials live. `POST /api/control
  {"action":"start|stop|pause|resume|cinematic|grid|mark|kill_agent|restart_agent", ...}`.
- Overlay messages (`{"kind":..., ...}`):
  `caption{char,text,emotion,color,audio_url?,duration_ms}` (typewriter, in char colour, plays audio
  if given — the overlay page IS the audio source OBS records), `chat{char,text,color}` (bubble +
  typing animation), `status{char,level,purse,island,goal,health,maxHealth}`,
  `clock{elapsed_s,total_s}`, `ticker{text,drama}`, `scene{mode:"intro|grid|cinematic", target?,
  lower_third?}`, `title_card{title,subtitle,seconds}`, `letterbox{on}`, `cost{usd,turns,per_hour}`,
  `narrate{text,audio_url?}`.
- **Additions by D (2026-10-03), all optional fields:** `scene.focus` (grid mode: glow one quadrant —
  director `CUT_TO`), `scene{mode:"intro",target,lower_third}` = per-character intro card,
  `clock.running` + `clock.episode`, `status.archetype/slot/color`, `ticker.char/color/type`,
  `narrate.duration_ms`. Extra routes: `GET /bus` (SSE of raw bus events, dashboard feed),
  `GET /api/recent`, `POST /api/push` (push one overlay message — debug), `GET /api/health`.
  Extra control actions handled by the bridge: `tts_warmup` (load IndexTTS2 — record time only) and
  `test_voice{char,text?,emotion?}`. Built-in: `pause|resume` also set the `paused` dial; `mark` with
  no handler emits a `mark` bus event. Overlay URL params: `?demo=1` (fake game backdrop),
  `?mute=1` (no audio — the dashboard preview), `?nostream=1` (drive by hand via `AISKY.handle(msg)`).
- **Python API (D):** `server.start() -> ShowServer` (singleton; `.push(msg)`, `.set_audio_dir(p)`,
  `.url`), `server.push(msg)`, `server.on_control(fn)` (`fn(action, body) -> dict | None`),
  `server.STATE` (dashboard model). `overlay_bridge.Bridge(server=, bus=, tts=, dials=, poll_server=)`
  `.start()/.stop()/.set_episode_dir(ep)/.cinematic(target, lower_third, seconds)/.end_cinematic()/
  .narrate(text, voice=True)`. `tts.TTS(audio_dir=, dials=, engines=)` `.speak(char,text,emotion) ->
  Clip|None`, `.say(Line, on_start, on_skip, on_end)`, `.busy()` (a voice line is playing — the
  director must not cut), `.status()`, `.start_indextts(force=False)` (refuses while RocketLeague /
  Streamlabs run). `inner_voice.data.voice = false` / `narrate.data.voice = false` = caption only.
  The bridge enforces `voice_lines_per_min` (extra lines become caption-only).

## 6. Dials (live, `show/dials.py`, persisted to `config/dials.json` on change)
| key | default | meaning |
|---|---|---|
| `backend` | `"claude_code"` | `claude_code` (subscription, `claude -p`) or `api` |
| `model` | `"claude-opus-5-5"` | player agents |
| `director_model` | `"claude-opus-5-5"` | director |
| `effort` | `"medium"` | routine turns; `big_effort` `"high"` for flagged moments |
| `turn_min_gap_s` | 6 | min seconds between one agent's turns |
| `screenshot_every` | 3 | attach a framebuffer screenshot every N turns |
| `max_actions_per_turn` | 3 | |
| `voice_lines_per_min` | 3 | per character |
| `voice_global_gap_s` | 1.5 | silence between any two voice lines |
| `cinematic_min_gap_s` | 120 | hysteresis |
| `cinematic_threshold` | 6 | drama score to trigger |
| `episode_minutes` | 30 | |
| `max_turns_per_hour` | 900 | total across agents (subscription rate-limit guard) |
| `budget_usd_stop` | 40 | hard stop for the `api` backend |
| `paused` | false | |

Director/episode dials (registered in `dials.py` DEFAULTS 2026-10-03, settable live):
`director_llm` true · `director_prethreshold` 4 (LLM only consulted at/above) ·
`director_cut_threshold` 5 · `director_cut_seconds` 8 · `director_cut_min_gap_s` 25 ·
`director_stale_s` 12 (a held decision older than this is dropped) · `director_return_grace_s` 6 ·
`director_meeting_poll_s` 5 · `director_look_every` 3 (screenshot every N director intervals) ·
`narrate_min_gap_s` 90 · `intro_pause_others` true · `intro_join_max_s` 50 (how long one wake-up may
hold the intro while the first turn + its voice line finish).

## 7. Brain (builder B) — `show/brain.py`
```python
class Brain:                     # one per agent (and one for the director)
    def __init__(self, name, system_prompt, model, effort, cwd): ...
    def turn(self, blocks: list[dict]) -> Reply   # blocks = Anthropic content blocks (text/image base64)
    def reset(self, summary: str) -> None          # restart the session with a rolling summary
    def close(self) -> None
@dataclass
class Reply: text: str; usage: dict; cost_usd: float | None; latency_s: float; error: str | None
```
- `ClaudeCodeBrain`: ONE persistent process per agent:
  `claude -p --input-format stream-json --output-format stream-json --verbose --model <m>
   --system-prompt-file <f> --tools "" --strict-mcp-config --no-session-persistence --effort <e>`
  started with **cwd OUTSIDE the `Ai Game` tree** (e.g. `%LOCALAPPDATA%\AISkyShow\agents\<name>`)
  so no CLAUDE.md is loaded into the characters. Launched with `CREATE_NO_WINDOW`. Read the result
  message for usage/cost. Verify the exact stream-json message shapes with ONE tiny real call.
- `ApiBrain`: anthropic SDK, lazy import, prompt caching (frozen system first, cache_control on the
  last history block), `output_config.effort`, NO forced tool_choice (400 on this model), handles
  `stop_reason == "refusal"`. Not the default.

## 8. Agent turn I/O (builder B)
Each turn the agent receives: elapsed/remaining time, its ShowControl state, its TestPilot state
digest (chat since last turn — OTHER players' chat only, never their inner voice; open screen + slot
names; nearby entities; sidebar; action bar), the results of last turn's actions, and every
`screenshot_every` turns a framebuffer PNG (downscaled ≤ 768 px wide).
It replies with ONE JSON object (no prose outside it):
```json
{"inner_voice": "≤ 2 short sentences, first person, in character", "emotion": "hype|calm|angry|scared|smug|confused|sad|determined|happy",
 "say": "chat line or null", "goal": "current goal, ≤ 8 words",
 "actions": [{"tool": "warp", "args": {"dest": "hub"}}]}
```
`inner_voice` may be `null` on a "nothing to narrate" turn. Tools are MACROS (§9) — the model
decides WHAT, code does HOW. Parse defensively (strip fences, first `{...}`); one repair retry.

## 9. Macro tools (builder B) — `show/macros.py`, each returns `{"ok", "result": short text}`
`look_around()`, `walk_to(x,y,z | anchor)`, `warp(dest)`, `open_menu(name)` (SkyBlock menu = hotbar 8),
`click_item(name)`, `close_menu()`, `talk_to(npc_name)`, `fight(target, seconds|count)`,
`mine(block, count)`, `chop(log, count)`, `farm(crop, count)`, `fish(seconds)`,
`sell_to_npc(item?, all?)`, `craft(item_name, n)`, `buy_from_npc(npc, item, n)`, `bazaar(...)`,
`equip(item_name)`, `use_ability()`, `say(text)` (chat — also allowed as top-level "say"),
`wait(seconds)`, `command(text)` (whitelisted player commands only: /warp, /hub, /is, /sbmenu ...).
Each has a timeout + stuck detection + a recovery ladder (re-path → warp home/hub → ask in chat).

## 10. Voice (builder D) — `show/tts.py`
`TTS.speak(char, text, emotion) -> Clip(path, url, duration_ms) | None`. Engines in order:
`indextts` (Shadow's Voice Forge worker, see `../Shadow/docs/voice-forge.md` — GPU, ONLY at record
time), `sapi` (Windows System.Speech via PowerShell → wav, zero GPU, the always-works fallback),
`none` (captions only). A global queue plays one line at a time with `voice_global_gap_s` silence;
lines older than 8 s when their turn comes are dropped to caption-only (stale thoughts are worse than none).

## 11. Camera (builder E = ShowCam mod, builder C = camera.py + director)
ShowCam (port 25660, active only with `SHOWCAM=1`, inert otherwise like TestPilot):
`POST /shot {"type":"orbit|pushin|follow|crane|dolly|static|stop","target":{"player":"name"}|{"x","y","z"},
 "seconds":8,"radius":6,"height":2,"fov":60,"speed":1.0}` — interpolated client-side every frame.
`GET /status`. Also: when `SHOWCAM_TITLE` is set it sets the window title to that string (so OBS
can window-capture each client distinctly — all clients load the same instance, so ShowCam is in all
five, each with its own title, only the camera client gets `SHOWCAM=1`).
Fallback when ShowCam is absent: ShowControl `/show cam ...` (server-side smoothed teleports).

## 12. OBS (builder C) — `show/obs.py`, obs-websocket v5 over a stdlib WebSocket client
Scenes (created if missing): `AISky Intro`, `AISky Grid` (4 window captures tiled + overlay browser
source), `AISky Cinematic` (camera window full + overlay). Recording: start/stop, filename into
`episodes/<ep>/`. Password from `config/show.json`.

## 13. Episode flow (builder C — `show/episode.py`)
1. preflight (GPU/process check, ports free, server up, OBS reachable) → 2. `/show reset` + ruleset →
3. OBS record start, `AISky Intro` scene, title card → 4. clients join ONE AT A TIME onto their
private islands; for each, the camera/overlay focuses that player while their FIRST turn runs with
the "you just woke up here, no memory, where am I?" prompt → 5. grid scene; agents run; the prompt
nudges discovering the hub and meeting the others → 6. Director runs → 7. at `episode_minutes`:
`/show stop`, wrap-up turn (each says a last line), record stop → 8. edit outputs + cost report.
