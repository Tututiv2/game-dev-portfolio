# Rocket League Ledger

A local match tracker that reads Rocket League's replay files and turns them into a stats dashboard.

Rocket League writes a `.replay` file after every game, and the box score is sitting in its header. This tool watches the replay folder, parses each new file as it appears, appends it to a ledger, and renders a dashboard that answers the questions a scoreboard does not: how the win rate is trending, why goals are going in, and whether hardware or connection problems are actually costing games. Nothing is uploaded anywhere.

## Features

- Watches the replay folder and picks up each new game within about five seconds
- Auto-detects the replay folder (including OneDrive-relocated Documents) and which player's PC it is
- Groups games into sessions by a 45-minute gap so one night can be reviewed on its own
- Dashboard cards: win rate, goals scored and conceded per game, goal share, shot conversion, saves, points versus teammates
- Rolling 10-game win-rate chart, filterable by playlist and by last 10/20/50/100 games
- Per-game goal timeline; tag why each conceded goal went in and see the reasons ranked
- Tag games with issues (lag, disconnect, input problems) and compare win rate in clean games against affected ones

## How it works

- **A hand-written replay header parser.** The parser reads Unreal-style property lists (Int, Float, QWord, Str, Name, Bool, Struct, Byte, Array) directly with `struct`. Two traps are handled explicitly: a `StructProperty` and a `ByteProperty` both write a type or enum name that is not counted in the declared size, so skipping by size alone lands short and corrupts everything after it. Arrays are parsed inside their own slice so one odd element cannot shift the rest.
- **Header only.** Only the header is read (the first 500 KB at most), never the network stream of frame data, which keeps a full rescan fast.
- **No server, no install.** A `file://` page cannot fetch a JSON file next to it, so the watcher also writes `ledger.js`, which assigns a global. The dashboard opens straight from disk. Tags are stored in the browser's `localStorage`.
- **Player detection by frequency.** The player who appears in the most replays (the recording player counts double) is treated as the local player, with a constant to override it.

## Tech

Python 3 standard library only; a single-file HTML/JS dashboard with canvas charts.

## Running it

```
python rl_watch.py            # scan everything, then watch for new games
python rl_watch.py --once     # scan once and exit
python rl_watch.py --folder <path-to-Demos>
```

Then open `dashboard.html`. `ledger.json` and `ledger.js` are generated output.
