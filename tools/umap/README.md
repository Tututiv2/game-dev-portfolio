# UMAP

A whole folder of projects as one 3D map, plus a HUD that lights it up while an AI agent works in it.

**Double-click `UMAP.bat`.**

---

## What you're looking at

Every file is a dot. Dots sit near each other because their *contents* are alike (the same words,
the same functions), not because they're in the same folder. A project is the centre of its own dots.

- **colour** = language (orange Java, cyan web, purple C#, yellow Python, green Lua, pink Skript)
- **size** = how much is in it
- **lines** = "these two files are about the same thing"

The pipeline: every first-party text file is tokenised (camelCase and snake_case split, symbols a file
*defines* weighted above words it only uses), TF-IDF, truncated SVD to 128 dimensions, then a real
UMAP into 3D. It uses `umap-learn` when it's installed, and falls back to `tools/umap3d.py`, a
from-scratch implementation (smooth-kNN, fuzzy-union graph, PCA init, negative-sampling SGD) so the
map still builds on a machine with only numpy.

## The controls

| | |
|---|---|
| **drag** | turn the map · **wheel** zoom · **right-drag** or shift-drag to pan |
| **click a dot** | what's in that file: its own summary line, the functions it defines, and the files nearest it in meaning. "read the file" shows the actual code. |
| **semantic ⟷ galaxy** | left = the honest UMAP, where projects blend into each other. right = one cluster per project. |
| **↻ replay** | watch the folder get built, oldest file first (`r`) |
| **links** | off → just the selected project's → all. These arcs are the only lines here a person wrote. Everything else on the map is derived from the files. |
| **search** (`/`) | file names, project names, tags, function names, and the actual contents of every file, with the match count and the line it found |

## Watching an agent work

Double-click **`Install Agent Hook.bat`** once. After that, any Claude Code session you start in the
folder shows up on the map: every file it reads or edits lights up in that agent's colour, with a
trail behind it and a live feed on the right. Click an agent's card and the camera follows it around.

When it greps for something, every file that *defines* that thing lights up at once, which is the
clearest way to see what it's actually hunting for. An agent can also ask the map what is near a file
it is holding (`GET /api/neighbors?path=...`), and that question shows up in the feed too.

To see it without an agent: **run a demo walk** in the agent panel.

To remove the hook: `Install Agent Hook.bat --uninstall`. It costs about 76 ms per tool call while
installed, and it's silent when the map isn't running.

## One copy per folder

`instance.json` beside `server.py` says which map this copy is: its `name`, its `port`, and which
folder it maps. The same code can map several folders at once (`"roots": ["A", "B"]`), and a map can
`relay` every agent event on to another one, so a combined map lights up for agents working in any
of the folders it covers. With no `instance.json` it maps the folder it sits in, on port 5580.

## "N files changed since this map was built"

That pill in the top bar means what it says: the map is a snapshot, and it tells you when it has
drifted. Click it for the numbers. There's no auto-update on purpose. The fast way to do it would
place new files by guessing from a frozen model, and the map would slowly stop matching its own
measurements. `Rebuild.bat` takes about a minute and is correct.

## When you've added a project

**`Rebuild.bat`** rescans everything and refits the map. Takes about a minute.

Its written-up description comes from `data/curated/*.json`. A new project still gets mapped without
one; it just won't have the prose.

## If something's wrong

**`Test.bat`** runs the checks. `Test.bat --live` also stands up a real server and drives it.
