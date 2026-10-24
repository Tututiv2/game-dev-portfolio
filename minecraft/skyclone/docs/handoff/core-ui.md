# core-ui — hand-off

Package `com.skyclone.core.ui`. Boot class `UiCore` (`boot(JavaPlugin)`, `afterModules()`, `shutdown()`).
Provides `MenuService`, `SidebarService`, `NpcService`; also owns public chat formatting, the tab list,
and the SkyBlock Menu nether star in hotbar slot 9.

| File | What |
|---|---|
| `UiCore` | boot/afterModules/shutdown, `/sb npc`, `/sb ui`, 9 self-tests, fallback `/sbmenu` |
| `MenuServiceImpl` | the one Menu listener; open/current/paged/confirm/input/inputNumber; refresh task |
| `InputPrompts` | virtual sign editor + chat prompt (whichever answers first), timeout |
| `SidebarServiceImpl` | per-player scoreboard, tab header/footer, tab names, level cache for chat |
| `NpcServiceImpl` | mannequin/entity NPCs, TextDisplay holograms, look-at, clicks, watchdog |
| `MenuStar` | the locked nether star (slot index 8) and every guard around it |
| `ChatFormat` | `&7[<lvl>] &7Name&f: msg` renderer |
| `NumberParse`, `PagedLayout`, `SidebarFrame`, `LevelColors` | pure logic (unit-tested) |
| `UiDials`, `UiUtil` | dials, helpers |

## What works (verified how)
- **Compiles**: `api + core/ui` compiles clean with `-Xlint:all` (one deprecation removed). The full
  `check.sh core` currently fails only inside other agents' half-written packages (items/combat) — see
  "Known risks" for the check.sh path bug and how I ran it.
- **Unit tests**: `plugin/src/test/java/com/skyclone/core/ui/UiPureTest.java`, 9 tests, all pass
  (run with the JUnit launcher directly, not gradle): number parsing, paging maths, sidebar line budget
  and drop order, tab footer, level colours, chat prefix, sign-text cleaning.
- **Not run live** (the gate owns the server). Everything Bukkit-side is written against the 26.1.2
  jar signatures (checked with javap): `Mannequin`, `ResolvableProfile.Builder`, `Score#customName`,
  `NumberFormat.blank()`, `Player#openVirtualSign` + `UncheckedSignChangeEvent`,
  `PrePlayerAttackEntityEvent`, `PlayerPickItemEvent`, `EntitiesLoadEvent`.

## Behaviour module authors MUST know

### Menus
- **Every click in the menu is cancelled** unless the slot is `free(...)`: number-key swaps, double-click
  collect (always cancelled, even on free slots — it would pull icons), drags over non-free slots,
  shift-click from the menu.
- **Slot handlers run on the NEXT tick** (Bukkit forbids opening/closing inventories inside a click
  event), only if the same menu is still open, debounced per player (`ui.menu-click-debounce-ms`, 100).
  `MenuClick.current()` / `cursor()` are snapshots (clones) taken at click time.
- Exceptions from any handler (slot, free-slot, player-inventory, onClose, drawer) are caught, logged
  with the menu title, and the player gets "Something went wrong with that menu".
- **Player-inventory clicks**: allowed normally. Shift-click INTO the menu is cancelled; if the menu
  has free slots, core moves the stack itself (merge into matching free stacks, then empty ones, lowest
  slot first) and then fires `onFreeSlotChange`.
- **`onPlayerInventoryClick(handler)`**: when set, EVERY click in the player's inventory is cancelled and
  the handler runs next tick with `click.slot()` = the index in `p.getInventory()` (0-8 hotbar, 9-35 main,
  36-39 armour, 40 offhand) and `item` = the LIVE stack in that slot. Core skips the call if the stack
  changed since the click (anti-dupe). To take it: `p.getInventory().setItem(click.slot(), null)` or
  lower its amount. The star is never passed to you.
- **`onFreeSlotChange`** fires next tick, ONCE per click/drag/shift (slot = one of the changed slots).
  Re-read all your free slots in the handler; it is the only safe pattern.
- **Close order: `onClose` runs first, then core returns EVERYTHING still in free slots to the player**
  (inventory, overflow dropped at feet and owned by them). This makes crafting grids, sell slots and
  the reforge anvil safe by default.
  **If your menu STORES items in free slots (storage pages, backpacks, accessory bag, wardrobe,
  quiver), your `onClose` MUST copy the items into your data and then CLEAR those slots
  (`menu.getInventory().setItem(s, null)`) — otherwise core gives them back too = DUPE.**
  (API change request below asks for an explicit flag.)
- `onClose` also runs when the menu is REPLACED by another menu (Go Back, opening a sub-menu), on quit
  and at shutdown. Do not open a menu from `onClose` to "go back" — it will fight the menu the player
  just navigated to. `open()` called from inside `onClose` is deferred one tick.
- On quit, core closes the open menu at `PlayerQuitEvent` LOWEST priority, so returned items land in
  the inventory before anything saves it.
- **One Menu instance per viewer.** Do not show the same `Menu` object to two players.
- `menu.open(p)` on the menu that is already open just redraws (no close/reopen, no item return).
- `refreshEvery(ticks)`: one 1-tick scheduler pass redraws open menus whose interval elapsed (calls your
  `drawer`). Menus without a drawer are not redrawn.
- `current(p)` = the Menu in the player's top inventory, else null.
- **`paged(title, entries, icon, onClick, decorate, page)`**: `page` is **0-based** and clamped.
  Inner slots 10-16/19-25/28-34/37-43, chrome + Close (49). Title gets `" (1/3)"` only when there is
  more than one page. Arrows: 45 (only if a previous page exists) and 53 (only if a next page exists),
  placed AFTER `decorate`, so put your Back at 48 and sort/search at 50/51/52. Empty list shows a gray
  "Nothing to show here!" at 22 (before decorate, so you may overwrite it). An icon function that throws
  draws a barrier for that entry only. `PagedClick.page()` is 0-based.
- **`confirm(...)`**: 3 rows, green terracotta `&aConfirm` at 11, sign info icon at 13 (title + your
  description lines), red terracotta `&cCancel` at 15, no Close button. Each runs once; afterwards the
  menu closes unless your callback opened another menu. **Esc does NOT call onNo.** `onNo` may be null.
- **`input(p, prompt, cb)`**: closes any open inventory (so the menu's onClose/free-slot return happens),
  opens a virtual sign editor (line 1 empty for the answer, line 2 `^^^`, lines 3-4 the prompt) AND
  listens for the player's next chat line; whichever comes first wins. Chat shows
  `&eType your input in chat, or &ccancel&e.` Answer is plain text: colour codes stripped, control chars
  removed, trimmed, max 256 chars. `null` on "cancel", empty sign, timeout (`ui.input-timeout-seconds`,
  30) or when replaced by a newer prompt. Callback always on the main thread; not called if the player
  logs off. **Validate the text yourself** (it is player input).
- **`inputNumber`**: parses `64`, `1.5k`, `2m`, `3b`, `1t`, `1,234`; rejects negatives, NaN, `1e5`, hex,
  anything > 1e15. Re-prompts on garbage up to 5 times, then `null`. Values may be fractional
  (`1.5`) — round/floor yourself when you need an integer.

### Sidebar & tab
- Frame (exactly): title (`ui.sidebar-title`), `&7MM/dd/yy &8m<ui.shard>`, blank, ` <dayLabel>`,
  ` &7&e☀ &7<time>` (`&b☽` at night), ` &7⏣ <zone colour><zone>` (falls back to the island display name,
  then `&7None`), blank, `Purse: &6<commas>`, `Bits: &b<n>`, then **each section preceded by a blank
  line**, then blank + `&e<server.footer>`.
- **Line budget**: the frame uses 10 lines, so with 15 max there are **5 lines for ALL sections combined,
  and each section costs 1 + its lines.** One 4-line section, or a 2-line + 1-line pair, fits. Sections
  are taken in priority order (lower number first); one that does not fit is skipped and a smaller,
  less important one may still use the space. Keep sections short.
- A section that throws is skipped (logged at most once a minute). Return an empty list to hide.
- Sidebar redraw every `ui.sidebar-refresh-ticks` (20); only changed lines are re-sent. `refresh(p)` forces.
- **Core gives every player their OWN Scoreboard** (created on `ProfileReady`) and re-asserts it each
  redraw. Do not call `p.setScoreboard`. Teams on the MAIN scoreboard do not apply to players
  (see API change request).
- Tab list: header `\n&bYou are playing on &e&l<ui.server-name>\n` (dial `ui.tab-header`), footer = tab
  widgets in priority order separated by blank lines + `ui.tab-footer`, updated every 40 ticks and only
  sent when changed. Core registers widget `core.account` (priority 0: Profile, SB Level, Area, Server).
  Use priority 100+ for module widgets. Tab name: `&8[<lvl colour><lvl>&8] &7<name>`.
- SkyBlock level colours (chat + tab): 0 gray, 40 white, 80 yellow, 120 green, 160 dark green,
  200 aqua, 240 dark aqua, 280 blue, 320 pink, 360 purple, 400 gold, 440 red, 480+ dark red.

### NPCs
- Body: `Mannequin` unless `def.entity` names another spawnable EntityType (unknown -> mannequin, logged).
  Invulnerable, silent, no gravity, no AI, not collidable, non-persistent, immovable (mannequin),
  description hidden, PDC `skyclone:npc=<id>`. Skin: `def.skin` (+ optional signature) as a static
  `ResolvableProfile` with a `textures` property. `def.hand`: a sky item id or a Material name.
- Holograms (nametag, restyled 2026-10-03 to the reference look): **ONE TextDisplay** holding every line,
  PDC `skyclone:npc_holo=<id>`, billboard CENTER, shadowed, no background box (default background off +
  ARGB 0), see-through false, full-bright, line width 400 (no wrapping). Lines sit at native nametag
  spacing; the bottom line starts `ui.npc-holo-height` (0.12) above the body's head. Top to bottom:
  **title, def.lines..., NAME (coloured), `&e&lCLICK`**. Built by `NpcHolo.lines(def, clickable)`:
  - the name is never bare white: an explicit non-white colour is kept; a leading `&f` / `&r` / no colour
    is replaced by the title's first real colour (`&6&lBANKER` -> `&6Banker Rhys`), else `&a`;
  - `&e&lCLICK` is added under the name for every NPC registered with a click handler (spawnAt copies
    included); a title or line that only says CLICK is moved there instead of being shown twice.
  - Modules: no need to set `.title("&e&lCLICK")` any more; give a real title (`&b&lBANKER`) or none.
  - `talk()` still uses `def.name` exactly as registered for the `[NPC] Name:` chat line.
- Placement: `Sky.worlds().anchor(island, anchor)`, only when world `sb_<island>` is ALREADY loaded
  (core never creates/loads a world or chunk for an NPC). Missing anchor -> logged once, placed near the
  island's `spawn` anchor (offset by id hash so several do not stack). **Instanced islands
  (`IslandDef.instanced`, e.g. private_island, garden) are skipped** with a log line — the owning module
  must place per-instance NPCs itself (API change request).
- Lifecycle: spawn in `afterModules()`, on `register()` after that, on `respawnAll()` (world core calls it
  after a build), on `WorldLoadEvent` for that island, on chunk load, and a 2-second watchdog re-spawns
  any NPC whose chunk is loaded but whose body or a hologram is gone (non-persistent bodies die on chunk
  unload). Stray tagged entities are removed at boot and on `EntitiesLoadEvent`.
- Clicks: right-click (`PlayerInteractEntityEvent`, main hand only), left-click
  (`PrePlayerAttackEntityEvent`), and damage by a player or their projectile all call `onClick` (next tick,
  exceptions caught). Debounce `ui.npc-click-debounce-ms` (500) per player. While `talk()` is running
  for that player+NPC, clicks on that NPC are ignored. All damage, fire and targeting of NPCs is cancelled.
- `talk(p, id, lines, then)`: `Msg.npc` with the NPC's name (one line per 1.2 s), then `then`.
- `lookAtPlayers`: every 5 ticks the body turns to the nearest non-spectator within `ui.npc-look-range`
  (8); with nobody near, it returns to the anchor's yaw.
- `entity(id)` is null while the chunk/island is not loaded — never cache it.
- Holding the SkyBlock star does not block NPC clicks.

### Chat
- `&7[<lvl colour><lvl>&7] &7<name>&f: <message>`. The message is flattened to plain text (no player
  formatting, no click/hover injection). Level comes from a main-thread cache refreshed every 40 ticks
  (`Features.SkyLevel`, 0 when absent). Dial `ui.chat-format`.
- A pending `input()` prompt consumes that player's next chat line at LOWEST priority (cancelled, never
  broadcast).

### SkyBlock Menu star
- Nether star `&aSkyBlock Menu &7(Click)`, PDC `skyclone:sbmenu`, kept in hotbar slot 9 (index 8) for
  every player with a loaded profile. Click it (hand: any click; inventory: left/right) ->
  `Sky.service(Features.SkyMenu.class).open(p)`; if no module provides SkyMenu, a placeholder 3-row menu.
- Blocked: drop (Q / ctrl-Q / inventory drop), any click that would pick it up, number-key 9 swaps,
  offhand (F) swaps, drags, pick-block into slot 9, item frames/armour stands/entity interactions,
  death drops (removed), item-entity spawns of it, pick-ups. A repair pass every 40 ticks (and on
  ProfileReady/respawn/world change) restores it, deletes strays anywhere else in the inventory/cursor,
  and moves (never deletes) any other item found in slot 9 into the inventory or at the player's feet.
- **To use slot 9 for something else** (dungeon class item, minigame): set the player PDC key
  `skyclone:no_sbmenu` (any byte). The next repair removes the star; delete the key to bring it back.
  Use `MenuStar.isStar(item)` semantics = NETHER_STAR with that PDC tag.
- **Items core**: do not convert/re-tag an item carrying `skyclone:sbmenu`.
- `/sbmenu` fallback is registered in `afterModules()` only when nobody provides `Features.SkyMenu`.

## Commands added
Player: `/sbmenu` (fallback only, see above).
Admin:
- `/sb npc list` — every NPC: live or not, island/anchor, fallback flag.
- `/sb npc tp <id>` — teleport in front of the NPC (or its resolved home).
- `/sb npc respawn` — despawn + respawn all.
- `/sb npc here <id>` — anchor location vs live entity location and distance.
- `/sb ui demo|freeslots|confirm|input|number|sidebar|refresh|star [player]` — 60-entry paged demo
  (confirm on click, search/number prompts at 48/50, free-slot demo at 51), a 7-free-slot sell-style menu
  that returns items on close, prompt tests, a dump of the composed sidebar + section/widget ids, refresh
  all sidebars, repair the star.

## Dials added
| key | default | |
|---|---|---|
| `ui.sidebar-title` | `&e&lSKYBLOCK` | sidebar title |
| `ui.shard` | `1A` | server id after `m` on the date line |
| `server.footer` | `www.skyclone.example` | sidebar footer |
| `ui.sidebar-max-lines` | 15 | |
| `ui.sidebar-refresh-ticks` | 20 | |
| `ui.sidebar` | true | show the sidebar |
| `ui.server-name` | `SKYCLONE` | `{server}` in the tab header |
| `ui.tab-header` | `&bYou are playing on &e&l{server}` | |
| `ui.tab-footer` | `&7Type &e/sbmenu &7to open the SkyBlock Menu` | last footer line |
| `ui.tab-refresh-ticks` | 40 | |
| `ui.tab-max-lines` | 40 | widget lines in the footer |
| `ui.tab-names` | true | `[lvl] name` in tab |
| `ui.menu-click-debounce-ms` | 100 | |
| `ui.input-timeout-seconds` | 30 | |
| `ui.input-sign` | true | open a sign editor for prompts (chat always works) |
| `ui.npc-click-debounce-ms` | 500 | |
| `ui.npc-look-range` | 8.0 | |
| `ui.npc-holo-height` | 0.12 | gap between the NPC's head and the bottom nametag line (replaced `ui.npc-holo-spacing`) |
| `ui.chat-format` | true | |
| `ui.sbmenu-star` | true | |

## Self-tests added
`ui.number-parse`, `ui.sidebar-frame`, `ui.paged-layout` (builds real paged menus: slots, arrows,
titles, clamp), `ui.confirm-builds`, `ui.star-item`, `ui.level-colors`, `ui.sign-wrap`,
`ui.npc-anchors` (every NPC anchor exists in its island's layout; islands without a layout are skipped),
`ui.npc-alive` (every NPC whose chunk is loaded has a body), `ui.npc-nametag-style` (name colouring + CLICK
placement rules, pure).

## API change requests
1. **`Menu.keepFreeSlotItems()`** (or `storage()`): a flag telling core NOT to return free-slot items on
   close, for menus that persist their free slots (storage, backpacks, accessory bag, wardrobe). Today
   those menus must clear the slots in `onClose` or they dupe. Core would honour the flag in
   `MenuServiceImpl.returnFreeItems`.
2. **Close reason for `onClose`**: `Menu.onClose(BiConsumer<Player, Reason>)` or a
   `MenuService.replacing(p)` query, so a menu can tell "player pressed Esc" from "navigated to another
   menu" (and safely implement close-goes-back).
3. **NPCs on instanced islands**: `NpcService.spawnAt(String npcId, Location at)` / `despawn(npcId, key)`
   so the island/garden modules can put per-instance NPCs (island helper, garden visitors) through the
   same click/hologram machinery.
4. **Scoreboard teams**: since each player has their own Scoreboard, main-scoreboard teams (glow colours,
   nametag visibility, collision) do not apply. Either `SidebarService.team(String name, Consumer<Team>)`
   that core mirrors onto every player board, or `SidebarService.scoreboard(Player)`.
5. `Features.SkyMenu` javadoc says "Provided by skyblockmenu" but `ModuleList` calls the module `menus`.
6. `NpcService.talking(Player, String npcId)` (read-only) would let modules skip their own guards.

## Known risks
1. **`tools/check.sh` cannot pass anywhere in this project as written**: the project path contains a
   space ("Ai Game") and javac's `@file` splits unquoted lines on whitespace, so every agent gets
   `error: invalid flag: D:\projects\Ai`. Fix (one line, integrator's call): quote each line of the
   list, e.g. `cygpath -w -f "$LIST" | awk '{print "\"" $0 "\""}' > "$LIST.win"`. Workaround without
   editing: run it through a space-free junction —
   `New-Item -ItemType Junction -Path $env:TEMP\skyclone\proj -Target "D:\projects\SkyblockClone"`
   then `bash tools/check.sh core` from inside the junction.
2. **Virtual sign editor is untested live.** The fake sign is sent 3 blocks below the player (client must
   be within interaction range). If clients do not open it, players can still type in chat (both are
   always listened to); set `ui.input-sign false` to skip the sign entirely.
3. **Per-player scoreboards** (see API request 4) — any module relying on main-scoreboard teams will
   silently not work for players. Also: modules that despawn "untracked" living entities (a mob core
   cleanup pass) must skip entities tagged `skyclone:npc` / `skyclone:npc_holo`, or NPCs (villager bodies
   especially) will be killed and re-spawned every 2 s by the watchdog.
4. The storage-menu dupe rule above is a convention, not enforced (API request 1).

## Finish wave 2026-10-03 — NPC nametags
Owner: "npcs all have white nametags, normally looks a LOT better in hypixel". Done in core only
(`NpcHolo` new, `NpcServiceImpl.spawnHolograms`, `UiDials`, one self-test in `UiCore`):
coloured name (title colour or `&a`, never bare white), `&e&lCLICK` under the name of every clickable NPC,
one tight full-bright TextDisplay right above the head, no background, shadow, not see-through. Applies to
`spawnAt` copies on instanced islands too (same spawn path). `bash tools/check.sh core` -> CHECK OK.
**Not seen in a client yet** (no server/client run during the stream prep): after the next deploy, check
`/sb npc respawn` then look at the hub banker and the bazaar villager. If the text sits too high/low, tune
`ui.npc-holo-height` live and `/sb npc respawn`. Visitor NPCs with a white (common) rarity name now render
green; an explicit non-white colour (or a title) from the garden module overrides that.

## Wave-3 playtest fix 2026-10-04 — sidebar footer placeholder
`www.skyclone.example` is gone. Dial `server.footer` now defaults to `{server}`, substituted with
`ui.server-name` (default `SKYCLONE`) by `SidebarServiceImpl.footer()`, so renaming the server renames the
footer too and no fake web address is ever shown. Still yellow (`&e` prefix); set it to empty to show a blank
line. When the public server has a real address: `/sb dial set server.footer play.<domain>`. New self-test
`ui.sidebar-footer` fails on any `.example`/unsubstituted placeholder. No dials.yml override existed on the dev
server. `bash tools/check.sh core` -> CHECK OK. Not seen in a client. (The `api/ui/SidebarService` javadoc
example still shows the old address; it is a comment in the frozen api, left alone.)

## Wave-5 regression fix (2026-10-06): sidebar sections that do not fit go to the tab list
Playtest note (wave-5 regression, open #8): at the Farm during a Harvest Fair the fair (250) takes the 4 free
sidebar lines and the Objective (900, the filler) vanished. The priority rule is the reference's and stays; what
changed is that a dropped section is no longer lost: `SidebarFrame.compose(..., dropped)` reports every non-empty
section that did not fit, `SidebarServiceImpl.draw` keeps them on the board, and `updateTab` puts them at the TOP
of the tab footer (`SidebarFrame.tabFooter(widgets, overflow, tail, max)`), ahead of the module widgets, inside
the same `ui.tab-max-lines` budget. Sections hidden by `setHidden` (the Settings toggles) are excluded before
compose, so they never reappear in the tab. Dial `ui.sidebar-overflow-tab` (true); off (or `ui.sidebar` off) =
the old tab footer. No api change. Self-tests `ui.sidebar-overflow-reported`, `ui.sidebar-overflow-nothing-when-fits`,
`ui.sidebar-overflow-tab-first`; unit tests `UiPureTest.sidebarReportsDroppedSections`, `tabFooterPutsOverflowFirst`.
`check.sh core` / `all` CHECK OK. Not seen in a client.
