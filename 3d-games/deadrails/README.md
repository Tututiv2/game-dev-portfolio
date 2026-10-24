# Desert Rails (Dead Rails–style) — 3D + co-op (iteration 1)

Unofficial fan experiment: one **shared run** in the browser, **3D** view (Three.js), **authoritative** game state on a small **Node** server (Socket.IO).

## Quick start (you + a friend on the same Wi‑Fi)

1. **Install (once per machine that runs the server)**  
   From this folder:
   ```bash
   npm install
   ```

2. **Start dev servers** (game logic + Vite in parallel):
   ```bash
   npm run dev
   ```
   - Open **http://localhost:5173** on the host PC.  
   - Your friend opens **http://&lt;YOUR_LAN_IP&gt;:5173** (same port). Example: `http://192.168.1.10:5173`.  
   - The Vite dev server proxies WebSocket traffic to the game server, so you usually only need **port 5173** open on the firewall for LAN play.

3. **What runs where**
   - **Port 5173** — Vite (the web UI + bundled 3D client).  
   - **Port 3001** — Game server (simulation + `public/config.json`). The browser talks to it via the proxy, not by typing 3001 in the URL.

## If your friend is not on your LAN (over the internet)

- **Easiest for testing:** use a tunnel (e.g. [ngrok](https://ngrok.com/) or [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/)) to expose **5173** and share the **https** URL it gives you. They open that link; you keep `npm run dev` running.  
- **Longer term:** deploy the built app: `npm run build` then `npm start` (serves the built `dist` folder and Socket.IO on one port, default **3001**). Point a domain or tunnel at that process. You still need a **public** URL your friend can reach; “opening a port on your router” and forwarding to your PC is the traditional alternative (and you’d forward the same port you expose, e.g. 3001 for production, or 5173 in dev with appropriate firewall rules).

## Production-style run (one port)

```bash
npm install
npm run build
npm start
```

Open **http://localhost:3001** (or the host/port you set). Set `PORT=8080` in the environment if you need another port.

## What’s “missing” in this first iteration (expected)

- No separate character models per player (crew is one shared train + HUD).  
- No persistent accounts; bonds are per-browser `localStorage`.  
- No anti-cheat; the server is authoritative for the run, but this is a hobby stack.  
- No voice chat, friends list, or Roblox-style matchmaking.  
- Art is placeholder boxes + terrain.

## Legacy 2D build

The old **canvas-only** `js/game.js` (no network) is still in the repo for reference; the main **entry** is the Vite app + server above.

## Legal

Inspired by *Dead Rails* (Roblox). This project is not affiliated. Use original names/art in any public release.
