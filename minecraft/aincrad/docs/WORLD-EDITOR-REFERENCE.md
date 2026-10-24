# Aincrad Editor - command reference

> Generated from `EditorHelp.REGISTRY` by `/aed docs`. Do not hand-edit: the
> in-game help, the book and this file are rendered from the same list, which is
> the only way they stay in agreement.

Every command also works as `/aed <name>` if `//` is awkward to type.

## Choosing an area

| Command | What it does | Example |
|---|---|---|
| `//wand` | Get the selection rod | `//wand` |
| `//pos1` | Set corner 1 to where you are standing | `//pos1` |
| `//pos2` | Set corner 2 to where you are standing | `//pos2` |
| `//size` | Show the size and coordinates of your selection | `//size` |
| `//desel` | Forget the selection | `//desel` |
| `//expand <n> [direction]` | Grow the selection | `//expand 10 up` |
| `//contract <n> [direction]` | Shrink the selection | `//contract 5 north` |
| `//shift <n> [direction]` | Slide the selection without resizing it | `//shift 16 east` |
| `//outline` | Turn the glowing selection outline on or off | `//outline` |

**`//wand`** - Left-click a block for corner 1, right-click for corner 2. Clicking empty air reaches up to 120 blocks, so you can select things from a distance without flying to them.

**`//expand <n> [direction]`** - With no direction it grows the way you are looking. Give two directions to grow both ways at once. '//expand vert' stretches from the world floor to the ceiling, which is how you select a whole column of a building.

**`//contract <n> [direction]`** - Exactly the same rules as //expand, in reverse.

**`//shift <n> [direction]`** - Useful for working along a wall a section at a time.

**`//outline`** - The outline is drawn only for you, and only when you are within about 100 blocks.

## Filling and replacing

| Command | What it does | Example |
|---|---|---|
| `//set <block>` | Fill the whole selection | `//set stone` |
| `//replace <find> <put>` | Swap one block for another | `//replace dirt grass_block` |
| `//walls <block>` | Build the four vertical sides only | `//walls stone_bricks` |
| `//shell <block>` | Build all six faces | `//shell glass` |
| `//hollow [block]` | Empty the inside, keep a one-block skin | `//hollow` |
| `//count <block>` | Count matching blocks in the selection | `//count diamond_ore` |
| `//distr` | List everything in the selection, most common first | `//distr` |

**`//set <block>`** - Accepts mixtures: '//set 70%stone,30%cobblestone' gives a weathered look. '//set air' deletes everything in the selection.

**`//replace <find> <put>`** - Only touches blocks matching <find>, so the rest of the build is safe. '//replace !air stone' turns every solid block to stone and leaves the gaps. A bare name matches every rotation, so '//replace oak_stairs stone' catches all of them.

**`//walls <block>`** - No floor, no ceiling. This is how you raise a room's walls in one command.

**`//shell <block>`** - Like //walls but with a floor and ceiling too - a sealed box.

**`//hollow [block]`** - Turns a solid block of stone into a room. With no block given it hollows to air.

**`//distr`** - Good for checking what a build is actually made of before you start replacing things.

## Spheres, cylinders, pyramids

| Command | What it does | Example |
|---|---|---|
| `//sphere <block> <radius>` | Solid sphere centred on you | `//sphere stone 10` |
| `//hsphere <block> <radius>` | Hollow sphere - a dome or a bubble | `//hsphere glass 12` |
| `//cyl <block> <radius> [height]` | Solid cylinder rising from your feet | `//cyl stone 8 20` |
| `//hcyl <block> <radius> [height]` | Hollow cylinder - a tower or a silo | `//hcyl stone_bricks 8 30` |
| `//pyramid <block> <size>` | Solid pyramid rising from your feet | `//pyramid sandstone 12` |
| `//hpyramid <block> <size>` | Hollow pyramid | `//hpyramid sandstone 12` |

**`//sphere <block> <radius>`** - Three radii make an ellipsoid: '//sphere stone 10 4 10' is a squashed dome.

**`//cyl <block> <radius> [height]`** - A negative height builds downwards, which is how you sink a well or a pillar footing.

**`//pyramid <block> <size>`** - Size is both the height and how far the base reaches from the centre.

## Copy, paste, move, stack

| Command | What it does | Example |
|---|---|---|
| `//copy` | Copy the selection to your clipboard | `//copy` |
| `//cut` | Copy the selection, then delete the original | `//cut` |
| `//paste [air]` | Paste the clipboard at your feet | `//paste` |
| `//rotate <degrees>` | Turn the clipboard, in multiples of 90 | `//rotate 90` |
| `//flip [x|y|z]` | Mirror the clipboard | `//flip` |
| `//stack <count> [direction]` | Repeat the selection end to end | `//stack 5 east` |
| `//move <n> [direction]` | Slide the selection's contents | `//move 10 up` |

**`//copy`** - The copy remembers where you were standing, so pasting puts it back in the same relationship to you. Stand at a building's front door when you copy it and it will paste with its door at your feet.

**`//paste [air]`** - Plain //paste skips air, so it stamps a build onto the landscape without punching holes around it. Add 'air' to paste air as well, which erases - use that when you are moving a room's interior and want the old contents gone.

**`//rotate <degrees>`** - Stairs, doors, logs, signs and fences all turn with it.

**`//flip [x|y|z]`** - With no axis it mirrors the way you are facing. Block states are mirrored too.

**`//stack <count> [direction]`** - Each copy sits exactly one selection-length along, so a corridor section stacks into a corridor. Does not use the clipboard.

**`//move <n> [direction]`** - Clears the old position and takes the selection with it.

## Painting with a brush

| Command | What it does | Example |
|---|---|---|
| `//brush sphere <size> <block>` | Paint spheres where you look | `//brush sphere 5 stone` |
| `//brush cube <size> <block>` | Paint cubes where you look | `//brush cube 4 stone` |
| `//brush paint <size> <block>` | Coat the surface without filling underneath | `//brush paint 6 grass_block` |
| `//brush none` | Stop painting | `//brush none` |

**`//brush sphere <size> <block>`** - Right-click with the selection rod to paint. Left-click still selects, so the rod keeps doing both jobs.

**`//brush paint <size> <block>`** - Only replaces blocks that are exposed to air, so it re-skins terrain and roofs instead of burying them.

## Undo, cancel, and when things go wrong

| Command | What it does | Example |
|---|---|---|
| `//undo [n]` | Undo your last edit, or the last n | `//undo` |
| `//redo [n]` | Redo what you just undid | `//redo` |
| `//cancel` | Stop an operation that is still running | `//cancel` |
| `//perf [n]` | How many blocks are placed per tick | `//perf 30000` |
| `//guide` | Get this guide as a book you can carry | `//guide` |

**`//undo [n]`** - Every operation is undoable, including ones that are still running. History is kept per player and bounded by memory rather than a step count, so lots of small edits all stay undoable.

**`//redo [n]`** - Making a new edit clears the redo branch, the same as any text editor.

**`//cancel`** - Blocks already written stay written, and stay undoable.

**`//perf [n]`** - Default 12,000 per tick, which is 240,000 a second. Raise it to finish sooner at the cost of server smoothness; lower it if you are playing while a big job runs.

