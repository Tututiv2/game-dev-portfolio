# DECISIONS — owner picks, 2026-10-03 (recorded verbatim-ish, before he went live)

| # | Question | Owner's answer |
|---|---|---|
| 1 | Show name | **"Can 4 AIs Beat Hypixel SkyBlock?"** (folder `AISkyShow/`) |
| 2 | Cast | **Shonen squad** vibe — loud hothead fighter, calm genius planner, greedy gremlin trader, sweet quiet one who's secretly the scariest. Character sheets drafted in `config/cast.json`; **names are drafts, he renames freely** |
| 3 | God | First picked A (hidden god among them), then **changed his mind: "for now, lets do 4 ais, no god actually no one betraying"**. All god/traitor designs are SAVED as future presets (below), not built into v1 |
| 4 | Format | Team of 4, co-op. He liked all formats "if you could save those" → presets below |
| 5 | Episode | **~30-minute sprints.** Starts SLOW: pan over all four as they join and are on their islands ("huh? where am I"), then they all go to the hub and meet each other. **"they ACTUALLY play the game is the goal"** |
| 6 | Recorder | **OBS** (WebSocket v5). He streams with Streamlabs — the show never touches Streamlabs |
| 7 | Tone | **Swearing + roasting OK** |
| 8 | Billing | **His Claude subscription** → agents run through headless `claude -p` (stream-json, persistent). The anthropic-SDK API backend exists behind the same switch but is not the default |

## Saved presets (not built in v1 — the owner may want them later)
- **god-hidden** — one of the four secretly holds miracles (storm, bless/curse a drop, raise a boss,
  anonymous whisper), Faith budget enforced server-side, own secret goal, reveal is the moment.
- **god-outside** — a fifth AI the players pray to in chat; Faith fed by prayers/offerings.
- **god-rotating** — the god role rotates secretly per episode; audience told, players not.
- **traitor** — team of 4, one secretly betraying them / secretly OP.
- **ffa** — free-for-all race. **duos** — 2v2.

`config/presets.json` holds these as data so a future build only has to implement the miracle
tools (ShowControl `/show miracle ...`, server-checked) and add a prompt block.

## Constraints in force while building (owner is LIVE streaming)
- Nothing GPU-heavy, no Minecraft clients, no servers, no TTS renders while Streamlabs / Rocket League run.
- Never touch `SkyblockClone/` source — another session is working in it. Read-only. Its built jar is copied.
- Never use OS input. TestPilot only.
