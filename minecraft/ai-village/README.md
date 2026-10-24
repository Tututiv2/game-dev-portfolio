# AI Village

Up to ten LLM-driven bots on a private survival server, cooperating to kill the Ender Dragon while one of them is secretly the imposter.

AI Village is an experiment in leaving AI agents alone in Minecraft for in-game days to see what emerges. Each bot has a role (builder, farmer, warrior, miner, scout, strategist, coordinator, watcher, caver) and every prompt carries the same team objective: a nine-step plan from wood to stone, iron, a portal, blaze rods and pearls, eyes of ender, the stronghold and the dragon. One bot is quietly server-op'd and told to cause strange events with commands while acting innocent. Everything runs on local models through Ollama, so there is no per-hour cost, and the whole experiment is controlled from in-game chat.

It builds on the open-source [Mindcraft](https://github.com/mindcraft-bots/mindcraft) agent framework (Mineflayer). This repo contains my controller and launch scripts; the Mindcraft checkout itself is not included.

## Features

- `!sim` chat commands for any human player: status, op, restart bots, full world reset with blank memories, wipe one bot's memory, set world spawn, teleport bots, give a bot a goal, day/night, pause/resume bots, and raw server commands
- Talk to a bot with `/msg <Bot>` (it replies by `/msg`) or by starting public chat with its name
- A drop file (`control/inject.txt`) so scripts can queue commands, picked up every 2 seconds
- A watchdog that restarts the bot process if it dies, or hard-restarts it if it is alive but no bots have been online for three checks in a row
- Bot output piped to a log with automatic rotation past 20 MB
- Web UI (from Mindcraft) for per-bot chat, thoughts and start/stop

## How it works

- **The controller watches the server log and acts over RCON.** `control/village-control.js` tails `latest.log` for chat, ignores lines from bots, parses `!sim` commands and executes them through RCON. No server plugin is needed.
- **Bridging a protocol gap.** Mineflayer supports up to 1.21.11 and the server is Paper 26.1.2. I pinned the bots to 1.21.6 and put ViaVersion and ViaBackwards on the server, then patched Mindcraft's version check to warn and connect instead of throwing, and added a whisper fallback because ViaVersion does not fire Mineflayer's whisper event.
- **Surviving multi-day unattended runs.** The bot process once died overnight: an Ollama outage caused agent churn, and an unguarded socket emit in Mindcraft's chat relay crashed Node. I added null guards and the watchdog above, which is what makes long runs viable.
- **Tuning for a shared GPU.** Slow, silent bots turned out to be Ollama's serial queue, not the framework: shortened command syntax made prompts about 3x smaller, and parallel request slots took the wait per thought from 90+ seconds to about 1 second. Bots now use an 8B Minecraft-finetuned reasoning model; to afford it the active roster runs smaller, and parallelism was lowered after it crashed Ollama under load. Anti-flip-flop settings (per-bot cooldowns, hunting and elbow-room modes off, a "finish what you start" rule) stopped bots abandoning tasks mid-way.

## Tech

- Node.js controller (RCON, log tailing, watchdog)
- Mindcraft / Mineflayer agents on local Ollama models (chat model plus `embeddinggemma` embeddings)
- Paper 26.1.2 with ViaVersion and ViaBackwards
- PowerShell start/stop scripts

## Running it

1. Install Ollama and pull the chat and embedding models.
2. Clone Mindcraft next to `control/` and `scripts/`, re-apply the version-check and whisper patches, and generate the bot profiles.
3. Put a Paper 26.1.2 server in `server/` with ViaVersion, ViaBackwards, RCON enabled and `allow-flight=true` (pathfinding gets bots kicked otherwise).
4. Run `scripts/start-experiment.ps1` to start the server, the controller and the bots; `scripts/stop-experiment.ps1` stops everything with a clean world save.

The server, world, Mindcraft checkout, bot memories and models are not included in this repo. Bot-generated code runs in Mindcraft's sandboxed compartment; keep that option off on any public server.
