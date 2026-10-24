# Aether Conductor

A node-graph incremental game: wire a flow network across a hex grid and route Aether into the Core.

Aether Conductor is an idle game where the thing you build is a graph rather than a list of buildings. You place nodes on a hexagonal playfield, draw edges between them, and the throughput of the whole network is what earns you resources. Edges have capacity, nodes have heat, and the best layouts are the ones that respect both. A prestige layer resets the run and drops you into a procedurally rolled Dimension whose modifiers change the rules for the next one.

## Features

- 19 node types: Siphons, Channels, Filters, Condensers, Splitters, Mergers, Relays, Prisms, Heatsinks, Overclockers and more, each with its own throughput, connection limit or range
- Edge capacity and bottlenecks, shown as a pulse on the overloaded wire
- **Heat**: aggressive nodes (Amplifier x1.6, Overclocker x2.4) run hot and melt if they pass their heat cap; Heatsinks cool their neighbours and melted nodes are repaired with Essence
- **Resonance**: a path from a Siphon to the Core that passes through tiers 2, 3 and 5 (the 2:3:5 chord) triggers a timed Harmonic Surge
- A 12-node research tree in three branches (Flow, Focus, Mind)
- **Collapse** prestige: Echoes are earned as `floor(log10(runEssence + 1))` plus bonuses and spent in an 8-upgrade Echo shop that persists across runs
- Procedural Dimensions after each Collapse, built from data-driven modifiers with `apply`, `onTick` and `onPlace` hooks
- Hidden secrets with codex lore, an auto-layout assistant that proposes three wirings with a live EPS preview, a minimap, offline progress, and save export/import

## How it works

- **The simulation is separate from the renderer.** `src/sim/` holds the grid, graph, heat, resonance, prestige and resource systems; `src/render/` draws them on a Canvas2D starfield. A fixed-timestep ticker drives the sim and pauses work while the tab is hidden.
- **Resonance detection is a memoised graph search.** The first version ran a DFS from every producer, which is exponential in the worst case on every tick. Memoising on `(node, tier-bitmask)` makes it O(V+E), which is valid because the flow graph is acyclic.
- **Auto-layouts are acyclic by construction.** The assistant only wires edges toward strictly decreasing hex distance from the Core, so every suggestion it offers is a valid flow graph.
- **Saves are validated field by field.** A corrupt or hand-edited import used to NaN-cascade through the whole sim, and an unknown modifier id crashed the renderer. Loading now sanitises every field and migrates older schema versions. Other fixes from the same review pass: Splitter and Merger had been functionally identical because `maxOut` was never enforced on manual wiring, every milestone toasted twice, and typing in the import box fired game hotkeys.

## Tech

- Vanilla JavaScript, ES modules (about 4,000 lines across 26 files)
- Canvas2D rendering, HTML/CSS panels for the UI, `localStorage` saves
- A small custom bundler (`build.mjs`) that concatenates the modules into one classic script

## Running it

Open `index.html` directly in a browser. It loads `game.bundle.js`, so it works from `file://` with no server.

If you edit anything in `src/` (or the bundle is missing), rebuild it with Node:

```
node build.mjs
```

The bundler strips `import`/`export` and concatenates the modules in dependency order inside one IIFE, because browsers block ES-module loading over `file://`.

**Status:** playable through Collapse and Dimensions. The Vault node's storage capacity is not enforced yet.
