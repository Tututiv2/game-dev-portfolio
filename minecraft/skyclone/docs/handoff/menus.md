# menus — hand-off

The face of the game: the SkyBlock Menu (nether star / `/sbmenu`), Stats & Equipment and the profile
viewer, Fast Travel, Calendar and Events, Settings, the SkyBlock Guide, a Your Bags stand-in, and two
tab-list widgets. Provides `Features.SkyMenu` and (wave 5) `Features.Settings`. Package `com.skyclone.mod.menus` (24 classes), data in
`resources/data/menus/` (generated), generator `tools/gen/menus/gen_menus.py`.

## What works (verified how)

Verified by `bash tools/check.sh menus` (**CHECK OK**) and **14 JUnit tests passing**
(`plugin/src/test/java/com/skyclone/mod/menus/MenusPureTest.java`, run with javac + the JUnit platform
launcher from the gradle cache). **Not yet opened in a real client.** The integrator's
`/sb selftest menus` (14 tests) and `/sb menus dump <player> <screen>` are the first live checks.

| Feature | Detail |
|---|---|
| **SkyBlock Menu** (`/sbmenu`, star) | Research 08 §11.1 slot for slot: 13 Stats & Equipment (your head, 9 key stats), 19 Skills (skill average), 20 Collections (**collections unlocked x/90** like the category pages, then tiers x/790, maxed, boss collections), 21 Recipe Book (**% of CRAFTING recipes unlocked**, via `Sky.recipes().unlocked`), 22 SkyBlock Leveling (level + progress), 23 Quest Log (current objective), 24 Calendar (date, time, mayor, next 3 events; **right-click = Upcoming Events**), 25 Storage, 29 Your Bags, 30 Pets (active pet), 31 Crafting Table, 32 Wardrobe, 33 Personal Bank (bank + purse), 47 Fast Travel (destinations unlocked), 48 Profile Management (profile, mode, co-op size), 49 Close, 50 Settings, 51 Cookie (**buff Active / Not active** read from the bank's `cookie` requirement key, bits; **right-click = Community Shop**, research "Community-Shop shortcuts"). **Ours:** a quick-access row 37-43 (Museum, Bestiary, Active Effects, SkyBlock Guide, Your Island, Bazaar, Auction House), toggled by dial and per-player setting. **Ours:** 46 **The Rift** (`/rift`, shows your Rift Time) and 52 **Leviathan's Hollow** (`/kuudra`, shows your best hunt tier cleared via `kuudra:<tier>`), flanking the navigation row (`SkyMenuLayout.DESTINATIONS`, always shown). Every icon has a real item/skull, our own lore, the command hint (`&8/pets`) and `&eClick to ...!`. A feature whose module is missing shows `&cComing soon!` and says so on click instead of breaking. Each icon is built in its own try/catch so one broken service never blanks the menu. |
| **Opening other modules** (`Links` + `FeatureIndex`) | ONE table of **79 features in 9 categories**. Each command name was checked against what the modules actually register (2026-10-02). Order: owner's feature service when really installed (`Sky.has`) → its registered command(s) via `Sky.commands().run` (constant names, never player text) → a built-in stand-in (quests/effects/home/bags) → "coming soon". |
| **Stats & Equipment / profile viewer** (`/stats`, `/pv [player]`, sneak + right-click a player) | Research §11.2 layout: armor 1/10/19/28, equipment 2/11/20/29 (from `Features.Equipment.worn`, sorted by item category), held item 37, head 4 (SB level tag, profile, skill average, magical power, pet), **Combat / Gathering / Wisdom / Misc** at 13-16, summaries 22 Skills (all 11 levels), 23 Slayers (6 types, level + XP, active quest), 24 Dungeons (Catacombs + 5 classes), 25 Progress (SB level, HOTM + 3 powders, Garden level + copper, bestiary milestone), 31 Collections, 32 Accessory Bag (MP), 33 Equipment button, 34 Coins (self, or others who opt in), 47 Pet. Viewing someone else adds **Send Trade Request** (`/trade <name>`) and **Visit Island** (`/visit <name>`). **Live**: redrawn every second. Items shown are **display copies** (material/name/lore/skull/colour/glint only: no sky id, no uuid), so nothing in the viewer can ever be a dupe. The pet's look is cached per pet id, so the 1 s redraw does not create a new unique item every second. Privacy: target's `pv.allow_view` (admins bypass), dial `menus.pv-others`. |
| **Stat pages + breakdowns** | Each category page lists every stat of the category (every one of the 51 `Stat`s is on exactly one page, unit-tested), with our one-line description, base value and top 5 sources. Arrows 45/53 cycle categories. Clicking a stat opens its **breakdown** from `Sky.stats().breakdown`. Core's three kinds of row are kept apart: flat points (with share of sources), `"<id> (%)"` percent bonuses and `"<id> (x)"` multipliers. The header shows base, flat sum, percent, multiplier and the formula. All 27 stat-provider ids registered by modules have readable names. |
| **Fast Travel** (`/fasttravel`, `/ft`) | Research §11.10 slots per island (private island 10, hub 11, dungeon hub 12, barn 13, garden 14, desert 15, park 16, gold 19, deep 20, dwarven 21, hollows 22, spider 23, end 24, crimson 25, winter workshop 28, bayou 29, glacite 30, rift 31, kuudra 32). Unknown islands that register warps get overflow slots. Built from `Sky.worlds().warpInfos()`. Left-click warps to the main warp; right-click lists sub-warps. Locked islands name **only the requirement parts the player is missing** (`skill:foraging:5 & foraging:scroll:jungle` with Foraging 7 says just the scroll), plus the reference unlock hint. Also "still being built" and a glowing "You are here!". Private island and garden go through `/is` and `/garden`. Island portal skulls come from NEU. Slot 50 is an "All Warps" paged list. |
| **Calendar and Events** (`/calendar`, `/cal`, `/events`) | Month grid of 31 days (stack size = day): past days grey, today a glowing clock, event days dyed per event with "day x/y" and a real-time "Starts in" on every future day. **Clicking an event day opens that event's feature** (Spooky Festival → `/spooky`, Dark Auction → `/darkauction`, Harvest Fair → `/harvestfair`, ...). Arrows browse any month; "Back to Today". Header: year, season, day/night, real length of a day and a year, mayor, next 5 events. Slot 50 **Upcoming Events**: paged, running first then soonest first, with schedule text like "Every year: Autumn 29th to 31st (3 days)", a live countdown (redrawn every 5 s) and the in-game start date; real-time events too; **click opens the event**. Slot 51 Mayor & Election, slot 52 reminder toggle. The calendar maths handles month 0 (every month), month spill-over, year wrap and `everyYears`, unit-tested against research numbers (50 s SB hour, 124 h year). Core's own `/calendar` fallback steps aside automatically. |
| **Upcoming list rules** | One shared list, cached for 1 s and dropped on `CalendarStart`/`CalendarEnd`. Events core says never come (`millisUntil == -1`) and events further than 200 SkyBlock years away are left out: the mayors module parks forced-only events (Encore Spooky, the Ritual) a million years out. Two registrations of one occurrence (mayors **and** progression both register "New Year Celebration") are shown once, in the list, the day grid, the reminders and the "has started!" line. |
| **Event reminders** | Every `menus.reminder-check-seconds` (20): a chat reminder N real minutes before each event (per-player lead time 1/5/10/30), and "has started!" on `CalendarStart` (ignored for 60 s after boot, because core replays in-progress events). Per-player toggles. |
| **Settings** (`/settings [category]`) | 35 settings in 7 categories (Personal, Chat, Notifications, Combat, Gathering, Display, Island): icon plus a lime/gray dye toggle underneath; choices cycle (left/right click); reset per category and reset all (confirm). Stored in member data `settings` and mirrored to PDC. For keys **another module owns**, a legal PDC value written by that module wins over member data. The island module sets `island.visitors` on every co-op member's PDC, and before this fix the next login put the old value back. **Implemented here:** menu click sounds, quick-access row, profile viewing, sneak-click profiles, show coins on profile, **player chat filter** (removes viewers in `AsyncChatEvent`; core's renderer keeps working), event reminders + lead time + start messages, the Stats and Next Event tab widgets. The others are stored for their owners to read (list below). |
| **Your Bags** | `/bags` belongs to **storage**: it enables first, so its registration wins, and its menu knows the quiver lock, the sack of sacks and the vault. SkyBlock Menu slot 29 therefore runs `/bags`. Ours (research §11.8: 19 Accessory Bag, 20 Potion Bag, 21 Fishing Bag, 22 Quiver, 23 Sack of Sacks, 24 Sacks, 25 Equipment) is the stand-in, as `/bagsmenu`. |
| **SkyBlock Guide** (`/guide`) - ours | Every feature of the game (79) in 9 categories (Profile, Skills, Gathering, Items, Storage, Economy, Events, Travel, Help) with its description, command and whether it is installed. Click opens it. |
| **Tab widgets** | `menus.stats` (120: Speed, Strength, Crit Chance, Crit Damage, Attack Speed, research §10 "Stats:") and `menus.event` (165: running events, else the next one with its countdown, research §10 "Event:"). Each has a per-player toggle and dial `menus.tab-widgets`. Pet, effects, skills, profile/account, bank, cookie and mayor widgets are drawn by their owners (pets 300, alchemy 160, skills 110, core 0, bank 140/145, mayors 150), so this module no longer duplicates them. |
| **Stand-ins** | Quest Log (current objective from `Features.Objectives`), Active Effects (from `Features.Potions`) and Your Bags open only if progression's `/questlog`, alchemy's `/effects` or storage's `/bags` are not registered. |

## What is partial / not built

- **Not seen in a client.** Everything is compiled and unit-tested; screens are exercised by the
  `menus.screens-build` self-test only when a player is online.
- **Ten settings are still only stored** (as of 2026-10-06): `chat.rare_drops` (core loot), `chat.xp_popup` (skills),
  `notify.bank_interest` (bank), `combat.slayer_alerts` (slayer), `garden.visitor_alerts`, `garden.pest_alerts` (garden),
  `mining.commission_alerts`, `mining.event_alerts` (mining), `abiphone.calls` (extras), and the skills half of
  `numbers.roman`. Each owner can now read them in one line through `Features.Settings` (Request 13). Everything else
  in the table below is read by someone. Menus cannot implement these itself: each is a message another module sends.
- Profile viewer offline: a SNAPSHOT (as the player last left it); stat breakdowns still need them online.
- (fixed elsewhere, 2026-10-06 check) bank's remote-bank message and collections' tier bar label - Requests 10 and 11 are done.

## Finish wave 2026-10-03

Verified by `bash tools/check.sh menus` (**CHECK OK**) and `MenusPureTest` **17/17** (javac + JUnit launcher from the gradle cache). Not re-seen in a client.

- **"Collections Unlocked 1/790" disagreed with the per-category pages.** The collections module's category icons
  draw "Collections Unlocked" as collections DISCOVERED out of the category ("4/12"); the SkyBlock Menu and `/pv`
  drew the same label over TIERS (x/790). One label, two meanings, one click apart. Now `Summaries.collectionSummary`
  (shared by SkyBlock Menu slot 20 and the profile viewer) draws the bar as collections unlocked out of 90 - the
  sum of the category bars, and what the genre's menu shows - with tiers on their own line
  ("Collection Tiers: 12/790"), then "Collections Maxed: x/90" and "Boss Collections: x/8".
- **Quest Log said "Talk Island Helper"**: `Features.Objectives.current` returns the id. The text and how-to hint
  now come from progression's own `data/progression/objectives.json` (read as data through the plugin's
  resources - menus still never imports progression; a missing file falls back to the title-cased id). SkyBlock
  Menu slot 23 shows "Talk to your Island Helper" plus the hint in grey; the `/questlog` stand-in shows both.
  Unit test `objectiveTextsComeFromProgressionData`. (Request 7 answered from our side.)
- **Running events say when they END** (core added `CalendarService.millisRemaining`, Request 4): Upcoming Events
  icon "Ends in: 1h 20m", the calendar header / SkyBlock Menu calendar lines "ACTIVE (ends in ...)", the Event tab
  widget "Ends in ..." and `/sb menus calendar`. Stored on `Summaries.Upcoming.remaining` inside the shared 1 s cache.
- **Upcoming Events pages 2+ now tick.** Core's arrows rebuilt the next page through its own `paged()` without our
  5 s drawer; the arrows (45/53) are re-pointed at `CalendarMenu.upcoming(p, page +- 1)`, so every page counts down.
- **"Coming soon" marking uses core's `CommandService.exists`** first (Request 2), then the Bukkit command map, so
  features are pre-marked correctly however Paper exposes Brigadier commands.
- `/warp` with no arguments now opens Fast Travel (core did Request 1), so it is off the partial list.
- Already done in the playtest-endgame pass and re-checked: Fast Travel "All Warps" groups aliases (one entry per
  place, locked only if every alias is), and the Personal Bank lore says "Talk to any Banker".

## Commands added (player + admin)

| Command | Aliases | Opens |
|---|---|---|
| `/sbmenu` | `/skyblockmenu`, `/menu` | SkyBlock Menu |
| `/stats` | `/equipmentstats`, `/mystats` | your Stats & Equipment |
| `/pv [player]` | `/profileviewer`, `/viewprofile` | a player's profile (tab-completes online names); an offline player opens their stored snapshot (wave 5) |
| `/fasttravel` | `/ft`, `/travelmenu`, `/islands` | Fast Travel |
| `/calendar [events]` | `/cal`, `/events` | Calendar and Events / Upcoming Events |
| `/settings [category]` | `/options`, `/sbsettings` | Settings (island's own menu deep-links `/settings island`) |
| `/bagsmenu` | | Your Bags stand-in (`/bags` is storage's) |
| `/guide` | `/sbguide`, `/features` | SkyBlock Guide |

Admin `/sb menus ...`. Every sub takes a player NAME and prints synchronously, so all of it works over RCON:

- `open <player> <screen> [arg]`: open any screen for a player.
- `dump <player> <screen> [arg]`: build a screen and print every non-filler slot as `slot: name | first lore line [click]`.
  Screens: `sbmenu stats pv statpage breakdown fasttravel warps warplist calendar events settings settingcat bags guide guidecat quests effects`.
  Args: `pv <target>`, `statpage combat|gathering|wisdom|misc`, `breakdown <stat>`, `warps <island>`, `calendar <monthOffset>`, `settingcat <cat>`, `guidecat profile|skills|gathering|items|storage|economy|events|travel|help`.
- `links`: every feature, AVAILABLE / MISSING / UNKNOWN, with which service and which commands exist.
- `layout`: the SkyBlock Menu slot map.
- `setting <player> [key [value]]`: list, read or set a player's settings (validated).
- `calendar`: date, real day/hour/year lengths, mayor, every listable event with countdown and schedule, this month's event days.
- `remind <player>`: send the next event's reminder now.
- `tab <player>`: print the tab widgets for that player (also the pet/effects stand-ins).
- `snapshot <player> [viewer]` (wave 5): re-take an online player's offline-profile snapshot and print it (age, icons, stats, privacy, undecodable icons); an offline player's is read from the DB asynchronously (the answer prints a moment later). With a viewer, opens the OFFLINE viewer for them.

Quick live test: `/sb selftest menus`, `/sb menus links`, `/sb menus dump Tutut sbmenu`, `/sb menus dump Tutut pv Tutut`,
`/sb menus dump Tutut breakdown strength`, `/sb menus dump Tutut fasttravel`, `/sb menus dump Tutut events`, `/sb menus calendar`.

## Dials added

| key | default | |
|---|---|---|
| `menus.quick-row` | true | quick-access row (37-43) in the SkyBlock Menu |
| `menus.sneak-click-profile` | true | sneak + right-click a player opens their profile |
| `menus.pv-others` | true | allow `/pv` on other players at all |
| `menus.event-reminders` | true | reminders + "has started" lines |
| `menus.reminder-check-seconds` | 20 | reminder check period (5-120); re-schedules live |
| `menus.tab-widgets` | true | draw this module's tab widgets |
| `menus.pv-offline` | true | (wave 5) keep a profile snapshot so `/pv <name>` works while they are offline |
| `menus.pv-snapshot-minutes` | 5 | (wave 5) how often online players' snapshots refresh (1-60); always taken on join (+5 s) and on quit |

## Self-tests added (18)

- (wave 5) `menus.settings-service`: `Features.Settings` is provided; null player / unknown key give the caller's default; for every ready player every key answers what the store says, `flag()` agrees, and the off-thread cache is current.
- (wave 5) `menus.sidebar-toggles`: the 4 sidebar toggles are Display toggles implemented here, default shown, groups disjoint, never `dungeons`/`kuudra`; for every ready player core's `hidden()` matches the setting.
- (wave 5) `menus.pv-snapshot`: refusal rules (empty, private, admin bypass); for every ready player a fresh capture has the head and every stat, every icon decodes and carries NO item id, and the offline main page and a stat page build.
- (wave 5) `menus.request-gates`: `/trade` and `/coop invite` parsing for the two request settings.

- `menus.sbmenu-layout`: the 17 research §11.1 slots exactly; the quick row and the Rift/Leviathan slots never collide; every slot is a real feature.
- `menus.sbmenu-links` (**new**): every research slot opens something real on this server (an installed service or a registered command).
- `menus.event-links` (**new**): every registered calendar event's click opens a feature that exists.
- `menus.upcoming-sane` (**new**): the Upcoming list has no negative countdown, no duplicate and nothing past the horizon.
- `menus.breakdown-keys` (**new**): flat, `(%)` and `(x)` breakdown rows are told apart.
- `menus.stat-categories`: all 51 stats on exactly one page with a real item icon; number formatting.
- `menus.calendar-math`: 50 s SkyBlock hour and 124 h year at 20-minute days (research 08 §8); a 3-day festival covers exactly its days; year wrap; monthly spill-over.
- `menus.fast-travel`: contract island ids, unique non-border slots, textures exist, every island that registered a warp gets a slot.
- `menus.settings`: unique keys, legal defaults, real icons, every implemented key defined.
- `menus.collections-data`: 90 collections / 790 tiers from research; when the collections module is installed it recognises every id (cross-checked offline: all 90 are in `data/collections/collections.json`).
- `menus.textures`: 36 skull textures decode; every feature texture exists.
- `menus.icons-valid`: every Material name in every table is a real item.
- `menus.core-links`: `/sbmenu /pv /fasttravel /calendar /settings /bags /guide` and core's `/profiles /hub /warp /coop /commands` are registered; SkyMenu is provided.
- `menus.screens-build`: for every ready online player, all 17 screens build, every SkyBlock Menu research and destination slot is filled, and every stat breakdown and every island icon builds.

## Settings contract (other modules READ these)

**Simplest read path (wave 5): `Sky.service(Features.Settings.class).flag(p, key, def)` / `.text(p, key, def)`** - any key
below, any thread (off the main thread it answers from a per-player cache), `def` when not ready or unknown.

Member data key **`settings`** is a class with exactly ONE field: `public Map<String, String> values = new HashMap<>();`.
Use exactly that field name: `member()` converts between classes through JSON, and a class without it would drop the data.
Toggles are `"true"`/`"false"`. Every key is filled with its default on `ProfileReady`, so `values.get(key)` is never null after that.
**Simpler read path** (no shared profile key at all): the player's PDC, `new NamespacedKey("skyclone", "setting." + key)`, type STRING.
**Writing** a key you own straight to the PDC is supported (island does it); this module adopts a legal value it finds there.

| key | default | read by |
|---|---|---|
| `numbers.roman` | true | collections (**reads it**, via `Features.Settings`); skills not yet |
| `chat.sacks` | true | storage (**reads it**: the 30 s sack summary) |
| `chat.rare_drops` | true | core loot (RARE DROP! lines) |
| `chat.xp_popup` | amount | skills (`amount` / `percent` / `off` action-bar XP popup) |
| `chat.trade_requests` | true | **menus** (wave 5): `/trade <p>` typed or clicked, the right-click request and the profile viewer button are refused when the target turned it off, unless the target asked first (then it is an accept) |
| `chat.coop_invites` | true | **menus**: a typed `/coop invite|add <p>` to someone who turned it off is refused |
| `notify.auction_outbid` | true | auction (**reads it**) |
| `notify.bazaar_filled` | true | bazaar (**reads it**) |
| `notify.bank_interest` | true | bank |
| `notify.minion_full` | true | minions (**reads it**) |
| `combat.damage_indicators` | true | **menus**: hides core's indicator TextDisplays from players who turned it off |
| `combat.slayer_alerts` | true | slayer |
| `garden.visitor_alerts`, `garden.pest_alerts` | true | garden |
| `mining.commission_alerts`, `mining.event_alerts` | true | mining |
| `fishing.sea_creature_alerts`, `fishing.trophy_alerts` | true | fishing (**reads them**) |
| `pets.visible`, `pets.others_visible` | true | pets (**reads them**) |
| `tab.pet` | true | pets (**reads it**: its own tab widget) |
| `tab.effects` | true | alchemy (**reads it**: its own tab widget) |
| `island.visitors` | anyone | island (`anyone` / `coop` / `nobody`; **reads and writes it**) |
| `island.guest_notify` | true | island (**reads it**) |
| `notify.event_scope` | important | mayors (`important` / `all` / `off`; **reads and writes it**: `/eventalerts`, the Festivals & Events bell). Menus' own announcer asks mayors' `mayors:alert:<id>:start\|remind`; without mayors it honours `off` only |
| `sidebar.objective`, `sidebar.slayer`, `sidebar.events`, `sidebar.area` | true | **menus** (wave 5): hide groups of module sidebar sections through `SidebarService.setHidden` (`SidebarToggles.GROUPS`); dungeon and hunt run state never hide |
| `sidebar.hoppity` | true | chocolate (**reads it**) |
| `abiphone.calls` | true | extras (not yet) |
| implemented here: `menus.click_sounds`, `menus.sbmenu_quick_row`, `pv.allow_view`, `pv.sneak_click`, `pv.show_coins`, `chat.players`, `tab.stats`, `tab.event` | | menus |
| `notify.event_reminders`, `notify.event_reminder_minutes`, `notify.event_start` | true / 5 / true | **whoever announces**: menus while `menus.event-reminders` is on, mayors while `mayors.alerts.takeover` holds the job (default). `/sb menus setting` and the Settings lore print the live reader |

## Requests (things you need from core or another module - the integrator reads this)

1. **(done by core: `WorldCommands` runs `fasttravel` first)** **core (world)**: make `/warp` with no arguments open the Fast Travel menu when it exists:
   in `WorldCommands`, `if (a.length == 0 && Sky.commands().run(p, "fasttravel")) return;` before `listWarps(p)` (keep the chat list as fallback).
2. **(done by core, used since 2026-10-03)** **core (commands)**: `CommandService.exists(String name)`, so menus can mark features "Coming soon" without depending on Paper listing Brigadier commands in the Bukkit command map.
3. **core (sidebar)**: per-player section hiding, e.g. `SidebarService.hidden(Player, String sectionId)` consulted by core, so Settings can offer "hide the slayer/objective section".
4. **(first half done by core, used since 2026-10-03)** **core (calendar)**: put `millisRemaining(String eventId)` on `CalendarService` (CalendarServiceImpl has it), so the calendar can say "ends in 2h". **Also a performance note:** `CalendarMath.dateUntil` loops `year .. year + everyYears + 1`, so the mayors module's forced-only events (`everyYears = 1_000_000`) cost a million iterations per `millisUntil` call. This module now calls it at most once a second, but any other caller pays it every time; capping the loop (or skipping events whose `yearOffset` is far ahead) would fix it at the source.
5. **every module in the settings table above**: read your key(s) (the PDC read path is the least fuss). **pets / alchemy**: honour `tab.pet` / `tab.effects` in your tab widgets.
6. **bank**: an API or requirement for the cookie buff's **time left** (the `cookie` requirement key already gives active / not active, which slot 51 now shows).
7. **(worked around 2026-10-03: menus reads progression's objectives.json)** **progression**: `Features.Objectives.current` returns the objective id; a display-text method (or returning the text) would let the SkyBlock Menu say "Talk to the Banker" instead of "Talk Banker".
8. **island** (note, not a bug here): the player PDC is per PLAYER, not per profile, so `island.visitors` written on the PDC follows a player to their other profiles. Keep the island store as the source of truth per profile, as it already is.

## Known risks

1. **Never opened in a live client.** Lore, slots and click routing are verified by compile, unit tests and data self-tests; the first real look is the integrator's `/sb menus dump` and a client session.
2. **Command-availability probe** (see partial): if Paper does not expose Brigadier commands through `Bukkit.getCommandMap()`, missing features are not pre-marked "Coming soon" (they still say so when clicked), and `menus.sbmenu-links` / `menus.event-links` report UNKNOWN as a pass.
3. **The `settings` member key is shared by design**: a reader whose class lacks the `values` field would lose the data on the JSON conversion core performs between classes. The PDC mirror avoids this entirely.

## Review pass (2026-10-02) - what changed and why

Bugs fixed:
- **Stat breakdown added multipliers as points.** Core's `breakdown()` returns `"<id>"`, `"<id> (%)"` and `"<id> (x)"` rows. The viewer summed all three as flat sources, so a x1.5 set bonus read "+1.5 Strength" and distorted every share. Rows are now parsed (`StatCategories.parseKey`) and shown as points, percents and multipliers.
- **"Never" became "in 0s", first in the list.** Core returns `millisUntil = -1` for an event that never comes, and `Math.max(0, -1)` sorted it to the top. Forced-only mayor events showed countdowns of about 50 billion days. Both are now filtered (`CalendarMath.listable`, a 200-year horizon).
- **The same festival appeared twice** (New Year from mayors and progression) in the list, the grid, reminders and start lines. All four are de-duplicated now.
- **Per-player cost of the event list:** the tab widget asked core for every event's countdown per player per refresh, and core walks up to a million years for forced-only events. The list is now one shared 1 s cache.
- **`/bags` collision:** storage registers `/bags` first, so our `/bags` was dropped with a warning, and the SkyBlock Menu opened a different, poorer bags menu than the command did. Slot 29 now runs storage's `/bags`; ours is the `/bagsmenu` stand-in.
- **Duplicate tab widgets:** our pet and effects widgets duplicated the pets (with XP progress) and alchemy (with time left) widgets and pushed others off the list. They are no longer registered.
- **Island visitor setting reverted on login:** island writes `setting.island.visitors` to every co-op member's PDC, and our `prepare()` overwrote it from stale member data. For keys another module owns, a legal PDC value now wins.
- **Profile viewer minted a unique pet item every second** (`items().create` on each 1 s redraw). Now cached per pet id.
- **Locked warps listed every requirement part**, including ones already met. Now only the missing parts are named.

Breadth added:
- The feature index grew **from 48 to 76** entries (minions, trades, mining, forge, commissions, farming, harvest fair, foraging, HOTF, sea creatures, trophy fish, arsenal, experiments, alchemy, accessory powers and guide, vault, community shop, merchants, festivals, dark auction, spooky, zoo, new year, winter, mythology, hub map, portals). Each name was checked against the real registrations. There are now 9 guide categories.
- Calendar event days and Upcoming entries are **clickable** and open their event (`FeatureIndex.forEvent`, unit-tested on every event id the modules register). The Upcoming page counts down live.
- SkyBlock Menu cookie slot: buff state from the `cookie` requirement key, plus right-click for the Community Shop. Calendar slot: right-click for Upcoming Events.
- The event tab widget shows a running event first ("ACTIVE"), else the next one.
- 4 new self-tests and 4 new unit tests (13 total).

## Fix pass (integrate-w2-1, 2026-10-02)

- **rift Request 3 / kuudra Request 3 done: SkyBlock Menu icons for `/rift` and `/kuudra`.** The research layout
  is fixed (17 slots, unit-tested), so they live in a new `SkyMenuLayout.DESTINATIONS`: slot **46 The Rift** and
  slot **52 Leviathan's Hollow**, the two bottom-row slots beside Fast Travel (47) and the cookie (51) that
  research leaves as glass. They open through `Links` like every other icon (registered command, else
  "Coming soon!"). Lore: the Rift shows the player's Rift Time stat when the sheet has one; the hunt shows the
  best tier cleared (Basic..Infernal) from kuudra's `kuudra:<tier>` requirement key, left out when kuudra is not
  installed. `/sb menus layout` prints them with their AVAILABLE/MISSING state.
- **FeatureIndex** grew `kuudra` (Skills & Collections, beside Slayer/Dungeons; `/kuudra`, `/leviathan`), `rift`
  and `crimson` (Travel; `/rift`, `/crimson` `/faction`) - so the SkyBlock Guide lists them too (79 features).
- **Name alignment (kuudra Request 4, menus' half):** the Fast Travel fallback name for island `kuudra` was
  "Magmaw's Hollow"; it is now **"Leviathan's Hollow"**, the name the kuudra module registers on its own
  `IslandDef` (so a live server already showed that - only the no-kuudra fallback disagreed). "Magmaw" is the
  Crystal Hollows boss (reference Bal) and the BAL pet, so the old name pointed at a different fight. The
  accessories items ("Magmaw's Kidney/Lung/Heart", "Magmaw Follower Artifact") are accessories' half and still
  say Magmaw - that remains the owner's naming call (INTEGRATION.md).
- Tests: unit test `riftAndLeviathanHaveSkyMenuIcons` (slots 46/52, no collision with research/quick row/Close,
  commands, names, textures); self-tests `menus.sbmenu-layout` and `menus.screens-build` cover the new slots.

## Fix pass (playtest-endgame, 2026-10-03)

Three minor findings from the real-client endgame playtest (`docs/PLAYTEST.md`, menus rows). Verified by
`bash tools/check.sh menus` (CHECK OK) and `MenusPureTest` **16/16** (two new tests). Not re-seen in a client.

- **All Warps listed aliases separately, some locked while their alias was open** (Dwarven Mines x3, The Forge x2,
  Crystal Caverns x2, Base Camp x2). Root cause: modules register one place under several warp ids (mines:
  `dwarven_mines`/`mines`, `forge`; mining's travel scrolls: `mining_dwarven`, `mining_forge`, `mining_crystals`,
  `mining_base_camp`; also `gold`/`gold_mine`, `deep`/`deep_caverns`, `home`/`private_island`, `winter`), each with its
  own requirement, and every menu iterated raw warps. Now `TravelLayout.aliasGroups` folds them into ONE destination
  (same island AND same anchor OR same display name, merged transitively) and `TravelLayout.pick` chooses which warp a
  click uses: an OPEN one first, then the island's own warp, then one not on an `npc.*` anchor, then registration
  order. A destination is locked only when every alias is ("Requires Mining XII or the Forge Travel Scroll"); its
  other ids are listed as `Also: /warp mines, mining_dwarven`. Applied everywhere: All Warps, an island's sub-warp
  list, the island icon (its main warp, its "Warps: n" count, left-click) and the "Destinations unlocked" count
  (SkyBlock Menu slot 47 and the Fast Travel header) - aliases count once. `mining_base_camp` (glacite_tunnels spawn)
  folds into Frozen Tunnels; mines' `camp` ("Dwarven Base Camp", a station in the Dwarven Mines) stays its own place.
- **`mining_forge` lands exactly on the Forge NPC** (its anchor is `npc.forge`, mining's data): the menu now prefers
  mines' `forge` (anchor `station.forge`) whenever both are open, so Fast Travel no longer drops you inside the NPC.
  `/warp mining_forge` typed by hand still does - see Request 9.
- **The Moonmarsh had no description**: it is not a CONTRACT island (id `galatea`), so it had no `TravelLayout`
  entry. `TravelLayout.entry` now also answers from an `EXTRA` table (description, icon, colour, unlock hint; slot -1,
  so it keeps its overflow slot and `entries()` / the slot self-test are unchanged). The island's spawn warp also
  carries the island description in All Warps.
- **"Collections Unlocked 1/790, Discovered 1" after eight COLLECTION UNLOCKED messages.** Two causes. "Discovered"
  counted collections at **tier >= 1**, while the collections module prints COLLECTION UNLOCKED on the first item
  (`old == 0 && now > 0`); Coal/Ender Pearl/Gunpowder/Bone were below tier I. Discovered is now `count > 0`
  (`Features.Collections.count`). And boss collections were not counted anywhere (they are outside the 790 resource
  tiers, as in the reference): a new generated `data/menus/boss_collections.json` (8, from the collections module's
  own data - research has none) feeds a **"Boss Collections: x/8"** line. SkyBlock Menu slot 20 and the profile viewer
  share `Summaries.collectionLines`. The "Collections Unlocked x/790" bar stays tier-based on purpose.
  Self-test `menus.collections-data` also fails if the boss table is missing.
- **Personal Bank in the Hub said "You can only bank at a Banker in the Hub!"** That text is the bank module's
  (`Accounts.remoteDenial`), so menus cannot change it; the icon's lore now states the real rule before the click:
  "Talk to any Banker to bank. The Boon Buff or a Pocket Bank lets you bank from anywhere." (or "Your Boon Buff lets
  you bank from anywhere." when it is active). See Request 10.

Requests added:

9. **mining**: `Consumables.SCROLL_WARPS` registers `mining_forge` on anchor `npc.forge`, the spot the Forgemaster
   stands on, so `/warp mining_forge` lands inside him. Point it at mines' `station.forge` (the anchor `forge` uses),
   or the NPC anchor plus an offset.
10. **bank** (still open 2026-10-03): `Accounts.remoteDenial` says "You can only bank at a Banker **in the Hub**!" - shown while the player is
   in the Hub, it reads as wrong. The rule is "at a Banker": drop "in the Hub" (or name the Bank in the Village).
11. **collections**: the main `/collections` icon (slot 4) labels its tier bar "Collections Unlocked: x% ... tiers/790"
   while each category icon uses "Collections Unlocked" for a discovered-collections bar ("4/12") with a TIER
   percentage above it. Suggest: category and main bars both "discovered/size" with a matching percent, and tiers as
   their own "Collection Tiers: x/790" line - what the SkyBlock Menu and `/pv` now show.

## Fix pass (wave4-endgame, 2026-10-04)

Two minor findings from the wave 4 endgame playtest (`docs/PLAYTEST.md` Open issues #11, #12). Verified by
`bash tools/check.sh menus` (CHECK OK) and `MenusPureTest` **19/19** (two new tests). Not re-seen in a client.

- **#11 The alert scope was not in `/settings`** (mayors Request 15 a and b, done). New setting **`notify.event_scope`**
  "Calendar Alerts" in Notifications (choice `important` / `all` / `off`, default `important`, labels Important only /
  Everything / Off - mayors' own). Its reader is `mayors`, so the existing adopt-from-PDC rule keeps it in step with
  `/eventalerts` and the bell both ways with no migration (mayors writes `skyclone:setting.notify.event_scope`; ours
  mirrors there). The Calendar's slot 52 bell also shows it and **right-click cycles it** (left-click still toggles
  reminders). Menus' own announcer (only runs when `mayors.alerts.takeover` is off) now skips a player unless
  `mayors:alert:<eventId>:start|remind` says yes, guarded on `Sky.has(Features.Mayors)` (an unknown prefix fails
  closed); without mayors only `off` silences it. Part (c), the Dark Auction's "Use /darkauction" / "N coins short"
  wording, is NOT copied: leave takeover on (the default) and mayors sends it.
- **#11 `/sb menus setting` said `notify.event_reminders ... read by menus`** while mayors sends the lines. It now
  prints the LIVE reader (`MenusListener.liveReader` over the pure `SettingDefs.liveReader`): `mayors` while takeover
  holds the job, `menus` while `menus.event-reminders` is on, `nobody (...)` when neither runs. The single-key form
  prints it too, and a setting's "Used by:" lore in `/settings` uses the same function.
- **#12 Away from the Hub the Bazaar icon said "Requires the Boon Buff!" while the Auction House opened.** Root cause
  is NOT in menus: both icons go through their owner's own gate (`/bz` -> `Bz.access`; `Features.Auctions.open` ->
  `AuctionHouse.reachable`), and for a normal player both want the Boon Buff (`cookie`) away from the building. The
  tester was **op**: `AuctionHouse.reachable` lets `skyclone.admin` through anywhere ("so testing works from
  anywhere"), the bazaar has no such bypass. AI players are not op, so they see one rule - see Request 12. Menus'
  part: the two icons now **state the rule before the click, in one sentence for both** (`SkyMenuLayout.reachText`):
  "Use it at the Bazaar/Auction House in the Hub, or from anywhere with the Boon Buff." / "Your Boon Buff lets you
  use it from anywhere.", read from the owners' dials (`bazaar.remote-requirement`, `auction.remote-requirement`,
  `auction.anywhere`; bazaar blank = anywhere, auction blank = only at the house). The Bazaar icon also shows
  **"Requires SkyBlock Level 5."** while `bazaar.access-requirement` (the owner's `sblevel:5`) is not met
  (`SkyMenuLayout.accessKey` reads it the way bazaar's `Gate.key` does). Lore only; the click still uses the owner's gate.
- Self-test `menus.settings` also checks the scope entry (Notifications, mayors' values, adopted not owned).

Requests added:

12. **auction** (or bazaar): `AuctionHouse.reachable` exempts `skyclone.admin` from the remote rule; `Bz.access` does not.
    An op therefore sees the Auction House open anywhere and the Bazaar refuse (playtest wave4-endgame #12). Pick one
    rule for both - suggest a shared dial (e.g. `auction.admin-anywhere`, default false) or dropping the bypass, since
    `/sb warpto hub` already gets an admin there.

## Wave 5 (2026-10-06)

Verified by `bash tools/check.sh menus` (**CHECK OK**, 199 files) and `MenusPureTest` **23/23** (javac + the JUnit
platform launcher from the gradle cache; four new tests). **Not seen in a client; the server was not started.**

### Done
- **`Features.Settings` provided** (`SettingsService`): `flag`/`text` over the stored settings for any key, from any
  thread. Main thread = the authoritative store (member data, with an owner's legal PDC write adopted first); other
  threads = a per-player ConcurrentHashMap cache kept current by prepare/set/get and dropped on quit; a key this module
  does not define = the PDC mirror `skyclone:setting.<key>` (so a module that keeps its own key there reads through the
  same door). Collections and chocolate already read through it.
- **Offline profile viewer** (`PvSnapshot`): `/pv <name>` for someone not online opens a snapshot of the profile as
  they last left it - every main-page icon, every stat value, and the stat icons that had sources - stored in member
  data `menus_pv` as display-copy item bytes (no item id, no uuid; decoded into fresh display copies again on the way
  out, so nothing in it can be a real item). Taken 5 s after ProfileReady, every `menus.pv-snapshot-minutes`, and on
  quit at LOWEST (before modules drop the caches the stat sheet comes from; core's MONITOR save then includes it).
  Read with `Sky.profiles().readOffline` (the ACTIVE profile), off the main thread. Privacy follows the player:
  `pv.allow_view` off refuses (admins bypass), `pv.show_coins` decides the coins icon, and a privacy change updates the
  stored flags at once. Name lookup is the server's own cache (`getOfflinePlayerIfCached`), never Mojang. Stat pages
  open from the snapshot; breakdowns say they need the player online. A clock icon at 50 says how old it is.
- **Sidebar toggles** (menus Request 3, answered by core's `SidebarService.setHidden`): Settings -> Display gains
  "Sidebar: Objective / Slayer Quest / Events / Area Info", each hiding a group of module sections
  (`SidebarToggles.GROUPS`: objective + district guide; slayer; mayors' event countdowns + farming contest; island,
  garden, mining, hollows, crimson, combatzones, fishing, foraging, galatea, hunting, rift, trapper). Re-applied on
  every ProfileReady (core keeps it per session) and on every change, with an immediate sidebar refresh. The dungeon
  run and the Leviathan hunt are in no group on purpose.
- **Trade Requests now does something** (`chat.trade_requests`, was "bank, not read"): a `/trade <p>` (typed, or the
  chat [CLICK TO TRADE] that runs it), the bank's empty-hand right-click request and the profile viewer's "Send Trade
  Request" are refused with "<p> is not accepting trade requests." - unless <p> sent YOU a request within the bank's
  window (`bank.trade.request-seconds` + 5 s), because `/trade <p>` is also how you accept. The viewer's button turns
  grey and says so before the click. Co-op Invites and Damage Numbers (earlier in this wave) work the same way.
- **Cookie slot shows the time left** (menus Request 6, answered by `Features.Bank.cookieMillisLeft`): "Duration: 3d 4h"
  under "Boon Buff: Active"; the active/inactive state also comes from `Features.Bank.cookieActive` when bank provides it.
- **Owners' openMenu methods** (core-wave5 section 2) used by `Links`: Bank, Equipment (accessories request 6), Mining,
  Garden (Desk), Bestiary, Museum, SkyBlock Leveling, Potion Bag - the provider's own door first, command as fallback.
- **Quest Log text from `Features.Objectives.currentText`** (menus Request 7, now on the API and implemented by
  progression); the objectives.json reader stays as the fallback and for the hint line.
- **Reforges request 2**: the quick-access row gained **Reforging** (slot 36) and **Enchanting** (slot 44), the two
  gear-upgrade stations, at both ends of the row (ours, toggled with the row).
- **SkyBlock Guide: 79 -> 94 features**: Party, Ringphone (extras' name for the Abiphone), Party Finder, Hunting,
  Attributes, Vitality, Fishing Hotspots, Soulflow, Runic Pedestal, Item Skins, Furniture, Community Desk, Chocolate
  Factory, Lotus Atoll & Torrent Canyon, Ship Navigator - each against the command its module really registers.
- **Fast Travel descriptions** for the new islands that register warps: Lotus Atoll, Torrent Canyon (galatea) and
  Critter Safari (hunting, "Hunting Skill XV").
- Hunting (core's new cosmetic skill) shows in the profile viewer's skills list automatically, tagged (Cosmetic).
- `/sb menus snapshot <player> [viewer]`; 4 new self-tests; 4 new unit tests; names in `docs/names/menus.md`.

### Effects from the accessories / pets / reforges hand-offs that belong here
- accessories 6 (Accessory Bag via `openBag`, Equipment via its own door): done (Equipment now via `Features.Equipment.openMenu`).
- pets 6 / 11 (Pets via `openMenu`; profile viewer shows active type/rarity/level; `pets.*` and `tab.pet` marked read by pets): done.
- reforges 2 (a Reforging button in the SkyBlock Menu): done (quick row slot 36). No accessory carries `extra.hook = menus`
  (checked in `data/accessories/*.json`), and no pet ability key or reforge effect is a menus concern.

### Still left
- **Not seen in a client** - first look: `/sb selftest menus`, `/pv <offline name>`, `/settings display`, `/sb menus snapshot <p>`.
- Ten settings still need their owner to read them (Request 13), incl. `numbers.roman` for skills and `chat.rare_drops` for core loot.
- Requests 9 (mining_forge on the Forgemaster) and 12 (op bypass on the AH only) are still open with their owners.

### Requests added
13. **core loot, skills, bank, slayer, garden, mining, extras**: read your Settings key with
    `Sky.service(Features.Settings.class).flag(p, "<key>", true)` (`text` for `chat.xp_popup`): `chat.rare_drops` (core:
    the RARE DROP! line in the loot roll), `chat.xp_popup` + `numbers.roman` (skills), `notify.bank_interest` (bank),
    `combat.slayer_alerts` (slayer), `garden.visitor_alerts` / `garden.pest_alerts` (garden), `mining.commission_alerts` /
    `mining.event_alerts` (mining), `abiphone.calls` (extras: off = do not disturb). Until then those toggles change nothing.

### Known risks (wave 5)
1. **Snapshot size**: `menus_pv` holds ~30-80 serialized display items per profile (tens of KB) and rides every
   profile save. Turn `menus.pv-offline` off if saves get heavy; existing snapshots are then simply not read.
2. **The trade gate is observational**: an accept is recognised only if this module SAW the other player's request go
   out (command, chat click, right-click, viewer button). It only ever refuses requests TO a player who turned them off,
   so the worst case is that such a player's own request, sent by some future path that bypasses all four, cannot be
   accepted.
3. **Sidebar group ids are strings** copied from the modules' `section(...)` calls on 2026-10-06; a renamed section
   simply stops being hidden (core ignores unknown ids). The self-test cannot list core's sections to catch that.
