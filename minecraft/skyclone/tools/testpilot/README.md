# TestPilot

A Fabric **client** mod for Minecraft **26.1.2** that lets a test agent drive a real
Minecraft client **from inside the game**, over a localhost HTTP/JSON API, plus
`pilot.py`, a stdlib-only Python client.

**Why it exists.** The old SkyClone driver (see `docs/INTEGRATION.md`, rounds 1-3) used
Windows `SendInput` + `SetForegroundWindow` + desktop BitBlt screenshots. The owner was
gaming on the same PC and it hijacked his keyboard, mouse and window focus. TestPilot
never uses OS input, never takes focus, and never captures the desktop:

* every action is executed on the client thread through the game's own code paths
  (`startAttack`, `startUseItem`, `MultiPlayerGameMode`, `KeyMapping` states);
* screenshots are read back from the game's own framebuffer;
* the window is created hidden, placed on the **second monitor**, and shown with
  `SW_SHOWNA` semantics (GLFW `FOCUSED=0`, `FOCUS_ON_SHOW=0`). It is never activated.

None of `SendInput`, `keybd_event`, `mouse_event`, `SetForegroundWindow`,
`SetCursorPos` or screen capture appears anywhere in this folder, in the mod or the
scripts.

---

## 1. Launch a test client

The SkyClone server must be in **offline mode** for a dev account (it is
`online-mode=true` normally):

```powershell
# from SkyblockClone\
# 1. server.properties: online-mode=false, then restart
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\rcon.ps1 "stop"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-server.ps1 -NoDeploy -Detached -WaitReady

# 2. start the client with TestPilot enabled, wait until it is in the world (~20 s)
cd tools\testpilot
py -3 pilot.py launch --user PilotTest

# 3. (optional) op it for /sb admin commands
powershell -NoProfile -ExecutionPolicy Bypass -File ..\..\scripts\rcon.ps1 "op PilotTest"
```

`pilot.py launch` runs exactly
`py -3 -m launcher launch Fabric-26.1.2 --dev-user PilotTest --server localhost:25630 --detach`
from `MinecraftLauncher\`, with `TESTPILOT=1` and `TESTPILOT_PORT=25620` added to the
environment (the launcher passes its environment to the game).

**The mod is inert unless `TESTPILOT=1` is set.** The Fabric-26.1.2 instance is also
played by a human; launched any other way, TestPilot logs one line and does nothing.

A second client: `py -3 pilot.py --port 25621 launch --user PilotTest2`
(then `Pilot(port=25621)`). Both windows open at the same spot on the second monitor;
set `TESTPILOT_POS=x,y` in the environment to offset one.

Close a client (kills ONLY java processes whose command line contains
`--username <user>`):

```powershell
py -3 pilot.py kill --user PilotTest
```

Afterwards: `deop PilotTest`, set `online-mode=true` again and restart the server.

### Settings (environment variables, or `-D` system properties)

| Env | Property | Default | Meaning |
|---|---|---|---|
| `TESTPILOT` | `testpilot.enabled` | unset = **off** | `1` enables everything below |
| `TESTPILOT_PORT` | `testpilot.port` | `25620` | HTTP port (127.0.0.1 only); setting it also enables |
| `TESTPILOT_WINDOW` | `testpilot.window` | `1280x720` | window size |
| `TESTPILOT_MONITOR` | `testpilot.monitor` | `secondary` | `secondary`, `primary` or a GLFW monitor index |
| `TESTPILOT_POS` | `testpilot.pos` | - | absolute `x,y` (virtual-desktop pixels) |
| `TESTPILOT_FPS` | `testpilot.fps` | `30` | frame cap, to stay light next to the owner's game |
| `TESTPILOT_SOUND` | `testpilot.sound` | off | `1` keeps game sound (default: master volume 0) |
| `TESTPILOT_ALLOW_GRAB` | `testpilot.allowGrab` | off | `1` lets the window grab the mouse if a human clicks it |
| `TESTPILOT_NOACTIVATE` | `testpilot.noactivate` | on | `0` drops `WS_EX_NOACTIVATE` (window becomes activatable by clicks) |

All option changes are **in memory only**: while TestPilot is active `Options.save()` is
cancelled, so the instance's `options.txt` (the owner's settings) is never written.

---

## 2. Endpoints

`POST` (or `GET` with query parameters) `http://127.0.0.1:25620/<endpoint>` with a JSON
object body. Every reply is JSON with `"ok": true|false` (and `"error"` when false).
Game work runs on the client thread; long actions block their request until done, and
other requests (e.g. `/state` during `/walkto`) are served concurrently.

| Endpoint | Body | Returns / does |
|---|---|---|
| `/ping` | - | `inWorld`, `player`, `focused` (game's flag), `osFocused` (GLFW), `screen`, `screenType`, `chatCount`, `tick` |
| `/state` | `{radius?:32, chat?:100}` | full snapshot, below |
| `/chat` | `{text}` | sends chat, or a command when `text` starts with `/` |
| `/chatsince` | `{since}` | chat lines numbered `> since` (`n` is a running count) |
| `/look` | `{yaw,pitch}` \| `{x,y,z}` \| `{entity:id}` \| `{nearest:"name"}` | sets rotation; entities are aimed at the **centre of the bounding box** (the body). Returns the new crosshair |
| `/attack` | `{entity?}` \| `{nearest?}` \| `{}` | aims at the body, waits 1 tick (so the server has the rotation), re-aims, runs the client's own `startAttack()`. `{}` = click whatever is under the crosshair. Returns `hit`, `onTarget`, `cooldownTicks`, `targetAlive` |
| `/attackhold` | `{seconds, x?,y?,z?}` | holds attack via the real `keyAttack` KeyMapping; vanilla `continueAttack()` does the breaking (real progress, real swings). With a block it aims at a **visible** point (centre, else exposed faces, each tested with the client's own pick) and stops as soon as the block changes. `broken`, `before`, `after` |
| `/use` | `{target?:"auto"\|"air"\|"block"\|"entity", entity?, nearest?, x?,y?,z?, hand?:"main"\|"off"}` | `auto` = the client's own `startUseItem()` (a real right click); the others call `useItem` / `useItemOn` / `interact` directly |
| `/usehold` | `{seconds?:1.2, x?,y?,z?, lift?:0}` \| `{probe:true}` | holds right click via the real `keyUse` KeyMapping (startUseItem, then vanilla releases it when the key goes up): a real bow draw and loose. With a point it re-aims at it every tick (`lift` = blocks of elevation for arrow drop). `probe` answers ok and presses nothing (feature test: jars before 2026-10-04 answer 404). Returns `wasUsing` |
| `/hotbar` | `{slot: 0-8}` | slot 8 is the key labelled 9 (SkyBlock Menu) |
| `/drop` | `{all?:false}` | Q / Ctrl+Q |
| `/swapoffhand` | - | F |
| `/click` | `{slot, button?:0, action?:"PICKUP"}` | `handleContainerInput` on the open menu. `action`: PICKUP, QUICK_MOVE, SWAP, THROW, PICKUP_ALL, CLONE, QUICK_CRAFT. Slots are numbered the way the open menu numbers them (`/state` `screen.slots[].slot`) |
| `/close` | - | closes the open screen (container screens send the close packet) |
| `/openinv` | - | E |
| `/move` | `{forward, strafe, jump, sneak, sprint, seconds, edgeGuard?:true, flyToggle?:false}` | holds W/S (`forward` >0 / <0), D/A (`strafe` >0 = right), Space, Shift, Ctrl via KeyMapping states; stops early on a world change or teleport (`stoppedBy`). Goes through the same **edge guard** as `/walkto` (keys toned down - no sprint, no jump, let go, brake - whenever they would carry the player over a drop deeper than 3.5 blocks; reply `edge`, `guardTicks`, `guardRisk`), because the AI show's stuck-recovery answers a walk that stopped at an edge with "move forward, jump, sprint" toward the same target. `edgeGuard:false` only to walk off something on purpose. While the player may fly (Rat "Turbo Tail") a jump pressed again within vanilla's 7-tick double-tap window would toggle flight, so it is held back (`jumpsHeldBack`) unless `flyToggle:true` |
| `/walkto` | `{x,y,z, timeout?:20, tolerance?:0.6, sprint?, path?:true, maxNodes?:8000, edgeGuard?:true, dry?:false}` | walks like a player: A* over standable cells (`Nav.java`, 8 directions, never cuts a corner) so it goes **round** walls; jumps whatever the player's own **hitbox** says is a jumpable rise (a 15/16 `dirt_path`, a slab on a block, a full block - never a wall, door frame or 1.5-high fence); stops and right-clicks closed wooden doors / fence gates on the path, **both leaves of a double door**, **bare-handed** (an empty hotbar slot 1-8, else the held item parked in an empty inventory slot by a SWAP click and put back after, else a hotbar item with no ability lore; if every slot holds an ability item the door is routed round, `doorsSkipped`) because a SkyBlock ability fires on any right click; **never falls off an edge** in any steering mode: before every tick the keys it is about to press are played forward through a replica of the player's own movement physics (`Motion.java`: ground friction, air drag, sprint x1.3, the sprint-jump kick, gravity, auto-step, the ladder clamp, against the real block shapes) and toned down - no sprint, then no jump, then let go, then brake - unless braking right after them still ends with the player held up by something within 3.5 blocks, and never at rest within a margin of a deeper drop (0.15 at walk speed, so a plain player's core +-0.15 stays over ground; wider for a faster body, 0.30 at Rat "Turbo Tail"'s double speed); the simulated body is the player's REAL hitbox (SCALE and pose: a Rat "Shapeshift" player is 0.18 x 0.54), and the walker never double-taps jump while the player may fly; it does not sprint within 1.5 blocks of such a drop (`edgeGuard:false` to disable). A step DOWN is where the old look-ahead failed: the 5 airborne ticks of a 1-block step carry a sprint ~1.4 blocks past the lip, off the end of a one-wide ledge. A target past an island edge ends `edge:true`, `stoppedBy` "blocked: edge - ..." at the edge (with `noRoute:true` when no walkable way gets closer); a planned step that cannot be taken without falling is never planned through again for that walk (and after four, "blocked: edge"). Partial plans (goal out of reach or out of budget) do not drop down a ledge they cannot climb back up unless that buys 8+ blocks of progress, and must get at least a block closer than where the player stands (no ping-pong between two equal dips); re-plans when stuck 1.5 s or when a partial path runs out; a door that will not stay open after 3 clicks is routed round; **climbs ladders and vines** up and down: up = hold jump up the column; down = it steers for the one spot over the hole where the whole 0.6-wide body clears the ladder's panel (`Nav.grabPoint`; a ladder takes 3/16 of a one-block hole, so the cell centre is 0.0125 from standing on the ladder's top edge), creeps onto it with a speed limit on both axes until the body drops onto the ladder, lets go to slide, and does not press W toward the exit until its head has cleared the floor above (W into that floor is a climb). Plans from the player's real position, so standing pressed against a wall, fence or iron bars is never a dead start, and a player placed INSIDE a carpet or snow layer (a dungeon start room: feet at 71.0, carpet top 71.0625) plans from the top of it. **Breath:** the planner never routes through water where the head would be under with no air above (under a dock deck, a roof, ice), and a swimmer is planned to climb out onto a bank up to a block above the surface; with the eyes under water and the air bar under 60% the walk surfaces before anything else (holds jump; with no air straight up it swims to the nearest open water, else back to where the head was last above water) and goes on once the bar is full. `dry:true` never steps INTO water at all - not planned, and the edge guard refuses it like a fall - ending `water:true`, `stoppedBy` "blocked: water - ..." when the target is in it; already in water, a dry walk still swims out. Stops on arrival (horizontal distance, and within 3 blocks of the target's `y` when one is given: the storey below is not "here"), on timeout, if a screen opens, **when the world changes or the player is teleported** (portal, /warp, lift: `stoppedBy` says which, `worldChanged:true` for a new world - walk again from there), or after three one-second retries find nothing walkable closer to the target (`noRoute:true`, standing at the closest point). Reply adds `pathed`, `waypoints`, `planComplete`, `replans`, `doorsOpened`, `doorsSkipped`, `edgeStops` (times it stopped at a drop), `edge`, `guardTicks` (ticks the keys were toned down), `guardRisk` (why: `fall`, `edge`, `lava`, `unloaded`), `heldItemParkedIn` (only if a screen opened mid-door-click), `nodesExpanded`, `worldChanged`, `noRoute`, `dry`, `water`, `surfaced` (times the breath guard took over), `underWaterTicks`, `air` / `maxAir` (300 = 15 s), `lowestAir`. `path:false` = the old straight-line steering (sidestep A/D round frames) |
| `/clearance` | `{directions?:16, max?:16}` | walkable distance without jumping from where the player stands, per yaw (0 = south, 90 = west); `bestYaw`, `bestClear`. Point a ridden pet, a sprint or a camera at open ground |
| `/stop` | - | cancels move/walkto/attackhold, releases every held key |
| `/findblocks` | `{block:"coal_ore", radius?:8 (max 24), limit?:20, exposed?}` | nearest loaded blocks whose id contains `block` |
| `/screenshot` | `{path?}` | renders the current frame from the framebuffer to PNG (works unfocused, covered or on another monitor). Default path: `<instance>\screenshots\testpilot-<ms>.png` |
| `/wait` | `{ticks}` | returns after that many client ticks |

### `/state` contents

* `player`: name, uuid, x/y/z, block x/y/z, yaw, pitch, dimension, health, maxHealth,
  absorption, food, saturation, xpLevel, onGround, selectedSlot, attackStrength, gameMode
* `hotbar` (9 entries), `inventory` (non-empty slots 0-35), `equipment` (head/chest/legs/feet/offhand)
* every item: `id`, `count`, `name`, `nameLegacy`, `lore[]`, `loreLegacy[]` (with `§` codes,
  RGB as `§x§r§r§g§g§b§b`), `customData` (SNBT; SkyClone's item id is in
  `PublicBukkitValues."skyclone:id"`)
* `screen`: `null`, or `type`, `title`, `titleLegacy`, `containerId`, `menuType`,
  `containerSlots`, `slots[]` (every slot: item + `slot` + `container` (false = player
  inventory part) + `containerSlot`), `carried`
* `sidebar`: `title`, `lines[]` (in display order), `linesLegacy[]`, `scores[]`
* `tab`: `header`, `footer` (text + legacy), `players[]`
* `actionBar` (`text`, `legacy`, `ageMs`), `title`, `subtitle`
* `chat[]` (last N: `n`, `text`, `legacy`, `time`) and `chatCount`
* `entities[]` within `radius`: `id`, `uuid`, `type`, `name`, `customName(+Legacy)`,
  `nameplate` (own custom name, else the floating text displays / armour stands above it,
  top to bottom, joined with ` | `), x/y/z, `centerY`, `height`, `distance`, `alive`,
  `health`, `maxHealth`, `isPlayer`; text displays also have `text`
* `crosshair`: `type` (BLOCK/ENTITY/MISS) + `block {x,y,z,id,face}` or `entity {...}`
* `focused`, `osFocused`, `inWorld`, `tick`

Examples:

```powershell
py -3 pilot.py state
py -3 pilot.py chat "/warp hub"
py -3 pilot.py look nearest:Zombie
py -3 pilot.py attack nearest:Zombie
py -3 pilot.py hotbar 8
py -3 pilot.py use
py -3 pilot.py waitscreen "SkyBlock Menu"
py -3 pilot.py clickname "Your Skills"
py -3 pilot.py click 19 0 PICKUP
py -3 pilot.py close
py -3 pilot.py move --forward 1 --seconds 1
py -3 pilot.py walkto 5 75 -3
py -3 pilot.py findblocks coal_ore --exposed
py -3 pilot.py mine 74 76 -141
py -3 pilot.py killmob "Graveyard Zombie"
py -3 pilot.py screenshot out.png
py -3 pilot.py call findblocks "{\"block\": \"stone\", \"radius\": 4}"
```

Raw HTTP (no Python):

```powershell
Invoke-RestMethod -Method Post http://127.0.0.1:25620/chat -Body '{"text":"/warp hub"}'
```

---

## 3. Python helpers (`from pilot import Pilot`)

```python
from pilot import Pilot
p = Pilot(port=25620)
p.wait_until_ready()                  # in world, no loading screen
st = p.state()                        # dict, see above
p.chat("/warp hub")
n = p.chat_count(); p.chat("/sb info"); p.chat_since(n)   # just the new lines
p.sidebar()                           # list of sidebar lines
p.hotbar(8); p.use()                  # SkyBlock Menu
p.wait_for_screen("SkyBlock Menu")    # title substring, case-insensitive
p.click_item_named("Fast Travel")     # first container slot whose name contains it
p.find_slot("Hub")                    # slot dict (name, lore, slot...)
p.close(); p.wait_for_no_screen()
p.move(forward=1, seconds=1); p.walk_to(x, y, z)   # plans round walls, opens doors
p.clearance()["bestYaw"]              # most open direction from here
p.hold("bow")                         # in hand; swaps it in from the inventory if /give put it there
p.nearest("Zombie")                   # nearest LIVING match; skips label entities
p.kill_nearest("Zombie", timeout=30)  # approach + look + attack until dead; never steps into water (stay_dry=False to swim after it)
p.find_blocks("coal_ore", exposed=True)
p.mine_block_at(x, y, z)              # visible point + hold attack until it changes
p.mine_nearest("coal_ore")
p.screenshot("out.png")               # returns the absolute path
p.wait(20)                            # ticks
```

`wave3_pass.py` is the wave-3 client pass (2026-10-04): 14 checks, one per wave-3 feature that had only
ever passed compile/JUnit/boot/self-test (Rift arch, Target Practice, Portal Isle, Kuudra Hunt Again,
Moonmarsh, Belly of the Beast pearls, Goblin Queen, Quiver Arrow, pet riding, bank account-wide sync,
Rift Gallery / Split or Steal, Runic Pedestal, Carnival, `/gfs`). Admin steps go over RCON (25631), player
steps through TestPilot; it ops the user for the run and deops at the end, and never starts/stops a server
or client. `py -3 wave3_pass.py --list`, then `py -3 wave3_pass.py --user PilotTest [--only a,b]`. Writes
`docs/playtest/wave3/report.md` (+ json, screenshots). Verdicts PASS / FAIL / LOOK (a person must read the
screenshot) / SKIP / ERROR. Target Practice and Quiver Arrow need `/usehold`: rebuild and reinstall the jar first.

`smoke_test.py` runs the whole flow (state, SkyBlock Menu + click + close, `/warp hub`,
walk, spawn + kill a zombie) and saves screenshots: `py -3 smoke_test.py --out verify/smoke`.

`focuswatch.py` is the read-only foreground monitor used for the verification below:
`py -3 focuswatch.py --seconds 90 --out focus.jsonl` (exit 0 = the test client never
became the foreground window).

---

## 4. The no-focus guarantee, and how it was verified

What the mod does (`WindowMixin`):

1. Before `glfwCreateWindow`: `GLFW_VISIBLE=0`, `GLFW_FOCUSED=0`, `GLFW_FOCUS_ON_SHOW=0`.
2. Forces windowed 1280x720 (GLFW always focuses a fullscreen window).
3. Moves the window onto the first non-primary monitor before vanilla positions it.
4. Shows the still-hidden window; with both focus hints off GLFW uses `SW_SHOWNA`,
   which does not activate. `FOCUS_ON_SHOW` is also pinned as a window attribute.
5. Before showing, the window gets `WS_EX_NOACTIVATE | WS_EX_APPWINDOW` (`NoActivate.java`,
   via LWJGL's `User32.SetWindowLongPtr` on our own HWND, then `SetWindowPos` with
   `SWP_NOACTIVATE|SWP_NOMOVE|SWP_NOSIZE|SWP_NOZORDER|SWP_FRAMECHANGED`). GLFW's hints only
   stop *GLFW* from focusing the window; Windows itself still activates a window when the
   user **minimises or closes the foreground window** (it activates the next window in
   Z-order) or **clicks into it**. NOACTIVATE stops both. APPWINDOW keeps it on the
   taskbar so a human can still find and close it. Turn off with `TESTPILOT_NOACTIVATE=0`.
6. Vanilla initialises `Window.focused = true` and only updates it from the focus
   callback, which never fires for a never-focused window; the mod sets it from the real
   GLFW attribute so the game knows it is in the background.

Plus: `pauseOnLostFocus=false` (the game keeps running), `rawMouseInput=false`, the mouse
is never grabbed, and held attack works without a grabbed mouse (`MinecraftKeybindsMixin`
lets vanilla's `continueAttack` run off the real `keyAttack` state).

Verified 2026-10-02 against the SkyClone dev server, with the owner using the PC
(Brave, Chrome, YouTube Studio, Streamlabs) the whole time:

* `focuswatch.py` ran for the whole 23-minute session (6 launches: 5 before the
  NOACTIVATE change, 4x PilotTest and 1x PilotTest2, plus the final build), polling
  `GetForegroundWindow` every 50 ms. **No launch, join, or driven action ever made a test client the foreground
  window.** It recorded **one** foreground event: at 12:30:04 the PilotTest window was in
  front for 5.5 s, between two periods of the owner's Brave window. Nothing was being sent
  to the client then (the last command had finished 20 s earlier). The cause is almost
  certainly the owner clicking the window on his second monitor, or Windows handing
  activation to the next window in Z-order when he minimised or closed his foreground
  window. GLFW hints cannot prevent either; that is what step 5 (NOACTIVATE) was added for.
  Log: `verify/focus_session.jsonl`.
* Final build (with NOACTIVATE), dedicated 120 s watch from just before launch through
  join, the full smoke test and a mining run: **1,595 samples, 0 violations**
  (`verify/focus_launch_final.jsonl`). The watcher also reads the window's extended
  style from outside: `exstyle 0x8040110`, `noactivate: true`.
* The client reports `focused=false, osFocused=false` after joining.
* The instance's `options.txt` kept its 11:40 timestamp and the owner's values
  (`pauseOnLostFocus:true`, `maxFps:120`, his volume) through every test session.

---

## 5. Build / install

```powershell
cd tools\testpilot
.\gradlew.bat build
copy build\libs\testpilot-1.0.0+mc26.1.2.jar "..\..\..\MinecraftLauncher\data\instances\Fabric-26.1.2\mods\"
```

Matches the instance exactly: Minecraft 26.1.2, Fabric Loader 0.19.5, Fabric API
0.155.3+26.1.2 (already in the instance), Java 25, Loom 1.17 with **no mappings line**
(26.x ships deobfuscated). Install only into the instance's `mods\`, never into
`%APPDATA%\.minecraft`.

---

## 6. Known limitations

* **Not verified: the inactive path.** That a launch without `TESTPILOT=1` leaves the mod
  completely inert is checked by code inspection only (every mixin and the entrypoint
  test `TestPilot.ACTIVE`). Actually launching it that way would open a normal,
  focus-taking Minecraft window on the owner's desktop, so it was not done.
* **The window is visible on the second monitor.** It never takes focus, but it is a
  normal window: if the owner screen-shares or streams that monitor, it shows. It also
  appears in the taskbar/Alt-Tab. Move it with `TESTPILOT_POS` / `TESTPILOT_MONITOR`.
* **GPU/CPU cost.** A test client renders at 30 fps (sound muted) next to whatever the
  owner is doing. Lower `TESTPILOT_FPS` further if that matters.
* With NOACTIVATE a human **click into** the test window does not activate it, but the
  click is still delivered to the game (e.g. it would click a slot in an open chest GUI).
  The keyboard stays with whatever the owner was using. Not verified by actually clicking,
  since that would mean driving the owner's mouse.
* The NOACTIVATE protection against "Windows activates the next window when you minimise
  yours" is Windows' documented behaviour for that style; it was not reproduced, because
  reproducing it means minimising the owner's window.
* `/walkto` plans only through loaded chunks and within `maxNodes` (8000 = ~100 ms on the client
  thread, measured in `NavTest`); a farther goal is walked in legs. It climbs ladders and vines but not scaffolding, does not plan
  boats or swimming routes (water is allowed but expensive), and iron doors / trapdoors are walls.
* The edge guard is a replica of the client's movement code, not the code itself: soul sand / honey slowdown, cobwebs,
  slime bounces, knockback and swimming are not modelled (in water the guard is off - water breaks any fall). It errs on
  the safe side where it can (it does not model vanilla's sneak edge-backoff, which it uses as the last resort), and
  `MotionTest` pins it to vanilla's numbers (walk 0.2158 / sprint 0.2806 blocks a tick, jump apex 1.2522, ladder
  2.35 m/s). It costs ~20 us a tick on the client thread right at an edge, where every candidate is played out and refused (`MotionTest`). `MotionTest` also replays the
  walker's steering on it headless over a box cut out of the real Private Island (`private_island_nw`) and the Tavern.
* A knockback (a mob, an explosion) can still throw the player off an edge; the guard only governs the keys.
  `NavWorldTest` replays the planner on boxes cut out of the real generated islands
  (`bash tools/navdump.sh <Generator> <name> x1 y1 z1 x2 y2 z2` writes one into
  `src/test/resources/nav/`) - the places a playtest walker got stuck in.
  `NavTest` (run by `bash tools/check.sh tools`) covers the geometry against real block shapes; the
  steering itself is verified only in-game.
* `/attack` reports what the crosshair hit; server-side rejections (cooldown, protection)
  are not visible to the client except through the effects (`/state`).
* Two clients on one instance share the instance folder (logs, screenshots dir).
* **SkyClone notes for agents** (server behaviour, not the mod):
  * New players are in **survival mode** (confirmed 2026-10-03; it used to be adventure).
  * Never set `TESTPILOT_POS` without knowing the monitor layout: absolute coordinates such as
    `200,200` land on the owner's **primary** monitor. Leave it unset for a second client.
  * `/walkto` walks door cells through their centre (an open panel leaves a 0.81-block gap for a
    0.6-wide player, which is why the old straight-line steering snagged on door frames).
  * `/walkto` stops the moment a screen opens - an NPC reply that opens its menu a tick late
    looks like "stuck".
  * Mining islands use the DigEngine: break speed is zeroed, the server breaks the block,
    you need a tool with enough breaking power (`/sb give PilotTest WOODEN_PICKAXE`), and
    a mined ore turns into a placeholder (bedrock), not air.
  * Hub warps are locked until discovered: `/sb hub discover PilotTest <district|all>`.
  * `/sb mob spawn <id> 1` spawns the mob on top of the player; step back before aiming.
