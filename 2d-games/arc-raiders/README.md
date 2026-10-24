# Arc Raiders Prototypes

Two games in one folder: a server-authoritative multiplayer extraction shooter, and a standalone star-forging idle game.

This project started as a fan prototype inspired by the extraction-shooter genre. It contains a networked first-person shooter, with a Node WebSocket server running the simulation at 20 Hz and a Three.js client that predicts its own movement, and **The Iron Star**, a single-file idle/prestige game about feeding a star until it collapses.

## Features

**Multiplayer extraction prototype** (`client/` + `server/`)
- Rooms with a server-run objective state machine: warmup, hold, then extraction with a progress meter
- Bot enemies with a forward vision cone (about 104 degrees, 35 m range), strafing, flanking, retreating, burst fire, magazines and reloads; bot count ramps up once extraction starts
- Movement with jumping and sliding, two switchable weapons, and weapon upgrades paid for with collected materials
- Client-side prediction with server reconciliation for the local player, and interpolation for remote players

**The Iron Star** (`arc_raiders.html`)
- Inject hydrogen and dark matter to grow a star while iron builds up toward a collapse
- Research with timers, an alien signal event with choices, a star map of purchasable nodes, constellations and an in-game manual
- A collapse/reboot prestige loop

## How it works

- **The server owns the game.** Clients send inputs; the server simulates movement, shots, bots and the objective, then broadcasts snapshots. Movement constants are shared so the client's prediction matches the server.
- **Hardening the server.** A review pass found that any abrupt client disconnect crashed the whole process (sockets had no `error` handlers), that a double `joinRoom` left ghost players being simulated forever, and that `Infinity`/`NaN` in a client's input poisoned yaw and shot math and was then broadcast to every client. Input is now validated, the per-message action list is bounded, and one-shot actions such as `upgrade_weapon` are consumed once, which closed a path that could spend materials twice across ticks.
- **Fixing rubber-banding.** Prediction had been running per frame with per-tick math, so at 60 fps and above the local player constantly snapped back to the server position, and a slide lasted 150 ms locally against 450 ms on the server. Prediction is now exact against the server's delta time, and recoil and camera smoothing are frame-rate independent.
- **Client cleanup.** Disconnected players' meshes are removed, bot meshes share geometry and materials instead of leaking GPU objects on every spawn, and input state is cleared on alt-tab so keys and fire no longer latch on.

## Tech

- Server: Node.js with `ws` (about 1,050 lines)
- Client: Three.js + Vite
- Idle game: single-file HTML, CSS and vanilla JavaScript

## Running it

`node_modules` is not included, so install dependencies first.

```
cd server
npm install
npm start          # WebSocket server on ws://localhost:8080

cd client
npm install
npm run dev        # Vite dev server on http://localhost:5173
```

Open the client in two browser windows to see two players in the same room. The server address and room can be overridden with `?ws=ws://host:port&room=name`.

The Iron Star needs no setup: open `arc_raiders.html` in a browser.

**Status:** prototype. There is no respawn outside warmup and no reconnect logic.
