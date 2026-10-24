# island — hand-off

The Private Island: one INSTANCED starter island per profile (co-op members share it), its helper NPC
**Gerald** (our Jerry), visiting, guest permissions, island settings, and `Features.PrivateIsland` for core
and every other module. Package `com.skyclone.mod.island`, generator `com.skyclone.mod.island.gen.PrivateIslandGenerator`.

## What works (verified how)

| Feature | Verified |
|---|---|
| **The island** (`private_island`, instanced, spacing 1024, generator v4, pure): a 83x71 grass island at y≈70 with a rocky underside and a separate NW islet joined by a railed bridge. West: round stone **spawn plaza** with benches/lamps and the **hub portal** (valid obsidian frame, 3x4 nether-portal opening, blackstone pillars, soul lanterns, purple banners; area `portal.hub`). North: a 2-floor **starter house** (11x8, chimney, ladder, a double chest + crafting table + furnace downstairs, bed + bookshelves upstairs) and a **well yard** with a woodpile. East: the **Minion Terrace** — five 5x5 grass work areas, each framed by a flush stone-brick ring with a polished-andesite slot (`minion.pad.1..5`). South-west: a fenced **farm** (wheat/carrots/potatoes, irrigation channel, gate). South: a **pond** with a plank dock. South-east: the **Quarry**, a mound of stone/cobble/andesite/coal/iron with a walkable ledge to the top. NW islet: a stone **lookout tower**. A big oak by the plaza (first log), a wooded rim around an open meadow left free for building, flowers, boulders, lamps every ~12 blocks on the paths. | `tools/preview.sh` (top/iso/iso_nw/walk/crop PNGs read and judged): `RESULT: OK`, **0 unreachable anchors, 0 floating components**, 0 out-of-bounds writes, 10/10 reservations, 89% of standable cells reachable (the rest are roofs/tower tops). Unit tests run the same on 5 seeds. |
| **15 fairy souls** `fairy_soul.1..15`: behind the portal pillar, the house loft, behind the house, the well yard, the far terrace corner, outside the farm fence, the dock end, the quarry summit, the lookout top, the islet's far edge, beside the bridge head, behind the big oak, the south-coast rocks, the NE cliff top, between oak and house. All on full blocks, all walkable. | preview report: every soul `ok` |
| **Anchors/areas others need**: `spawn`, `npc.island_helper`, area `build` (161-wide default square), `portal.hub`, plus `minion.pad.1..5`, `dock`, areas `minions`, `quarry`, `farm`, `pond`, `plaza`, `house`. One zone `private_island` "Private Island" covering the full 241x241 max size, flag **BUILD** (there are deliberately NO sub-zones: the smallest zone wins, so a sub-zone without BUILD would forbid building in it). | self-tests `island.generator-walkable`, `island.template-layout`; unit test `defaultSeedIsWalkableAndComplete` |
| **`Features.PrivateIsland`**: `home(p)` (custom `/setspawn` or the template spawn; null while the instance is still building, which also kicks the build off), `isOnOwnIsland(p)`, `canBuild(p, l)` = member of the profile that owns that grid slot AND inside the island's build square (161 + Island Size upgrades, up to 241) AND y 0..255 (dials). | code review; `IslandMath` unit tests (size, half width, build square, grid rounding) |
| **Join flow**: on `ProfileReady` (NORMAL, before core's HIGH `sendHome`) the profile's island is `ensureInstance`d. If it is not built yet core parks the player at the hub/limbo; when the build completes they are moved home if they are still where core parked them (dial `island.first-join-home`). First join: "YOUR ISLAND" title + welcome box after arrival. | code review against `ZoneTracker.onReady` / `WorldServiceImpl.sendHome` |
| **Gerald** (`island_helper`, a VILLAGER, `spawnAt` per instance at `npc.island_helper`): first click = 6-line intro then his menu; completes objective `talk_island_helper`. Menu: **Starter Kit** (once per member; marked + `saveNow` BEFORE giving, so a crash loses rather than duplicates; given through `Sky.loot().give(..., REWARD)`; unique items one by one), **Island Tips** (12 rotating tips), Island Management, To the Hub. Despawned 30 s after nobody of that profile and no guest is around; respawned on login, visit, `IslandBuilt`. | code review |
| **Visiting**: `/visit <player>` (online, or offline by name via the `island_players` directory when the server dial AND the owner allow offline visits), `/visit` with no name = paged list of online players' islands showing guests x/limit, size and why you can't go; "Visit by Name" sign/chat input. Checks: ban list, visitor mode (`anyone`/`coop`/`nobody`), a member online (reference rule), guest limit (5 + Guests Limit upgrade). Guests land on the island's home with a title. Members get `[Island] X is visiting your island!` / `left` (respects each member's `island.guest_notify` setting). | code review |
| **Guest rules**: guests can never build (core asks our `canBuild`). Containers: denied by core for non-builders; our HIGHEST handler re-ALLOWs them when the owner enables "Guests: Containers". Doors/trapdoors/gates/buttons/levers/pressure plates: allowed unless the owner disables "Guests: Doors & Switches". Guests can't touch armour stands (minions), frames, paintings, storage carts or non-sky animals, can't trample farmland, and can't pick up items that are not theirs unless "Guests: Pick Up Items" is on. PvP is never on (no PVP zone). | code review |
| **Enforcement tick** (1 s): tracks who is a guest where; sends guests home when they are banned, the island stops accepting visitors, or every member has been offline for 30 s (dial) without offline visits allowed. Counts unique guests and visits. | code review |
| **Island settings** (`/is settings`): visitors (cycles anyone / co-op only / nobody — written to the island AND mirrored onto every online member's `skyclone:setting.island.visitors`, so the menus module's Settings screen agrees; members' own setting changes are pulled back every 5 s, strictest wins), the three guest permissions, offline visits, PvP (locked off), visitor messages (opens `/settings island`), reset spawn, kick all. Stored per PROFILE in SQLite (`island_profiles`), so an offline owner's rules still apply. | code review |
| **Island Management** (`/is menu`, Gerald): island card (owner, profile, size + tier, members online, guests x/limit, visitor mode, unique guests), go home, set/reset spawn, settings, guests (left-click kick, right-click ban), visit an island, Island Size (opens `/cshop`), Co-op (`/coop`), banned players (click to unban), Gerald, to the Hub. | self-test `island.menus-build` |
| **Island Size / Guests Limit**: read from the Community Shop through requirement keys `cshop:island_size:N` / `cshop:guests_limit:N` (bank module), cached 30 s per island. Widths from `data/bank/upgrades.json` when shipped, else our own `data/island/upgrades.json` (generated from research 05 by `tools/gen/island/gen_upgrades.py`): 161 → 169 … 241. | unit tests `islandSizeMatchesResearch`, `tableOverridesFormula`; self-test `island.size-formula` |
| **The Quarry regrows**: rock mined inside the template's `quarry` area grows back after 6 s (dial; never into a standing entity). It is the island's stand-in for a cobblestone generator, which a void world cannot run reliably. | code review |
| Sidebar (`island`, prio 300): `Guests: 2/5` on your island when you have guests, `Visiting: <owner>` on someone else's. Tab widget (`island`, 170) with owner, size, guests, visitor mode while on any private island. | code review |

## What is partial / not built

- **Never run on the live server** (per the rules I did not start it). Everything above is compiled (`check.sh`),
  unit-tested offline (12 tests: maths + the generator on 5 seeds + the Portal Isle frames) and previewed; the Bukkit side (join flow,
  NPC copies, guest events) is code-reviewed only. The integrator should run `/sb selftest island` and walk a
  fresh account through: join → lands on island → click Gerald → kit → `/is menu` → `/visit` with a second account.
- **No island expansion of the TERRAIN**: Island Size widens the buildable square, not the grass. (Reference behaviour too.)
- **Social XP** is the skills module's (it asks `Features.PrivateIsland`). The island side of the Social road (the
  Island Decorator's goods, Island Ranks at 9, Island Naming at 20) is built since wave 5b; "/social commands" (Social 4)
  are not the island's.
- **Guest limit base (5)** and the per-tier Guests Limit slots are TUNE: research gives only "+1 … +4 (?)".
- **Starter kit**: the dial no longer lists `COBBLESTONE_GENERATOR_1` (the minions module hands every new profile its
  starter minion via `minions.starter-minion`; listing it here gave two). `island.starter-kit-items` checks the rest.
- ~~Per-member co-op build permissions~~ - **built in wave 5** (see below). The owner is resolved from the island side
  (Request 8 is still the clean fix); the Use Minions toggle needs the minions module to read it (Request 9).
- **Islands built before v4 have no Portal Isle** (built instances keep their blocks). Gerald offers "Build the Portal
  Isle" on those islands (also `/sb isle retrofit <p>`) — code-reviewed only, not run live.
- **Portal destinations**: the ten Portal Isle ones + (wave 5) the Far Court's Garden, Backwater Bayou, Lotus Atoll and
  Torrhus Canyon. Still no frame for the Rift (the reference enters it through the Wizard Portal + an infusion, so a free
  island portal would bypass its cost), Crystal Caverns / Frozen Tunnels (reached through the Dwarven Mines in the
  reference), Winter Workshop (seasonal), Critter Safari, Kuudra.

## Wave 3 (2026-10-04)

Worked the partial list by player impact. `bash tools/check.sh island` CHECK OK (184 files); **12/12 unit tests**
(11 old + `portalIsleFramesAreValid` on 3 seeds; 21 since wave 5; javac + the JUnit launcher); `tools/preview.sh` **RESULT: OK** —
0 unreachable anchors, 0 floating components, 0 out-of-bounds, every `isportal.*` front anchor reachable from spawn
(PNGs read: `previews/private_island/crop_*.png` with `crop=-70,-5,-15,55`). Not run live (no server in this phase).

- **The Portal Isle (generator v4).** Research 03 §1.1: "a frame appears on the Private Island for every island you
  have unlocked". A new islet south-west of the main island (centre -50,40, joined by a railed spruce bridge from beside
  the farm, with a path from the plaza) carries a paved courtyard with a fountain and **ten portals in two facing rows**:
  barn, desert, park, gold_mine, deep_caverns (north row) / dwarven_mines, spiders_den, the_end, crimson_isle,
  dungeon_hub (south row). Each is a VALID 2x3 obsidian frame (a lit portal never pops on a neighbour update) with
  corners, crown and a carpet runner in the destination's colours; shared stone pillars carry lanterns. v3 anchors
  did not move.
  - **Lit per island at runtime** (`IslandPortals`, every 2 s for islands someone stands on): an opening fills with
    nether-portal blocks once ANY online member has `visited:<island>` and meets the island + warp requirement;
    otherwise it stays dark. Portal blocks go only into AIR and only portal blocks are removed, so a block a player
    put in a frame is never overwritten; a frame that is not intact (pre-v4 island, or torn down) is left alone.
    The first time a portal lights with you around: "PORTAL OPENED!" + particles + sound.
  - **Walk in** = `Sky.worlds().warp(p, warp)`, judged on the WALKER's own unlocks (guests use theirs). Dark portal:
    "The portal to X opens once you have been there", a bass note and a nudge back out of the frame; a failed warp
    requirement prints the requirement. Areas are `isportal.<warp>` (not `portal.`) because core's portal handling
    would warp without the visited check.
  - **Labels** over every portal and over the plaza's hub portal ("The Barn / Walk in to travel!", "Visit it first to
    open", "Closed" when the warp/island is not registered): non-persistent text displays, re-made on each sync,
    removed when nobody is on the island and on disable — nothing piles up in the world.
  - **Menus**: Island Management slot 25 "Portal Isle" (open x/10 + one line per portal) → `/is portals`, an icon per
    portal (open / locked / closed); click = taken to the spot in front of it on your island. Gerald's slot 22 links
    there. One new intro line + 2 tips point at it.
  - **Retrofit for older islands**: without frames, Gerald's slot 22 becomes "Build the Portal Isle" (admin:
    `/sb isle retrofit <p>`). Runs the pure generator off-thread and pastes only the box x -72..-22, z 22..56: islet
    blocks only into world columns that are completely EMPTY, the bridge's own parts into air where it meets the coast
    — nothing a player built is touched. ~3000 blocks a tick.
- **Every way home honours `/setspawn`** (Request 3, done from this side): core's `home` / `private_island` warps (hub
  portal, `/warp home`, Fast Travel) land on the template spawn; a HIGH `PlayerTeleportEvent` handler moves a
  PLUGIN/COMMAND teleport that lands on EXACTLY the template spawn block of an island with a custom spawn onto it.
- **A custom spawn that was built over or mined away is not used**: `homeOf` checks a solid floor + two free blocks
  (only if the chunk is loaded) and falls back to the template spawn instead of dropping players into the void.
- **Guests who die on someone's island respawn on THAT island** (`SkyDeath.respawnAt` = host's home) instead of being
  flown to their own — unless they were banned or the island closed to visitors meanwhile.
- Self-test `island.portal-isle`; `IslandChecks.layoutProblems` now requires every `isportal.*` area (a 2x3 opening)
  and its anchor. Commands `/is portals`, `/sb isle portals <p>`, `/sb isle retrofit <p>`.

**Live check for the integrator:** fresh account → from the plaza walk south past the farm and over the bridge: ten dark
frames with "Visit it first" labels; walking into one says so and nudges you out. Hub portal → walk to The Barn → `/is`:
within 2 s "PORTAL OPENED!", The Barn's frame is lit; walk in → The Barn. `/setspawn` somewhere, then the hub's
private-island portal → you land on the custom spawn. An old (v3) profile: Gerald → "Build the Portal Isle".
- Admin `/sb isle reset` releases the instance slot and builds a fresh island; the old slot's blocks are cleared
  lazily by core. Not exercised live.

## Wave-3 playtest fix (2026-10-04): the bridge you fell off (generator v5)

**Finding:** walking the spruce bridge to the Portal Isle in a straight line fell into the void twice at the diagonal
step near world (-2076, 29) = template (-28, 29); the deck was ~1.4 blocks wide there.

**Root cause:** `Builders.bridge` (core gen toolkit, not ours) lays each step of the line as a strip PERPENDICULAR TO
THE NEAREST AXIS. On a diagonal, each row is shifted a block from the last, so rows overlap by only two blocks, and
where the line steps in x only, the next row's rail fence lands ON the walkway. Both island bridges are diagonal.

**Fix (island only):**
- `gen/IslandBridge.java` - a thick-LINE bridge: a cell is deck when its centre is within 1.75 of the centre line
  (so every point within 1.0 of the line is over deck on ANY heading; axis-aligned stays exactly 3 wide), the rail is
  the 8-neighbour ring of the deck (4-connected, so posts always join), height changes are half-block steps with
  bottom slabs (walkable from every side), no rail over land, deck caps never cut into a rise or pave over built
  things. Both bridges use it. Paths to the bridge heads are now laid BEFORE the bridge (the path used to re-cut the
  ground under the bridge head into a 1-block step). `VERSION` 4 -> **5**. Layout data `bridge.islet`,
  `bridge.portal_isle` (+ `.v4` = where v1-v4 laid them).
- **Islands built before v5 are repaired in place** (`IslandBridges`, Bukkit side; decision is the pure
  `IslandBridge.repairPlan`): once per island per boot when someone is on it and the chunks are loaded. Only acts if
  the OLD bridge is actually there (>= half its blocks by material at their exact places); changes only AIR and old-
  bridge blocks at old-bridge positions; lays the new bridge on a copy of the world minus the old one so it sees the
  real terrain; keeps an old deck block bedded in the ground rather than leave a hole. Dial `island.repair-bridges`
  (true). Admin `/sb isle bridges <p>` runs it now and reports per bridge. `spruce_slab` added to the retrofit's
  bridge parts.
- Tests: `bridgesAreWideOnEveryHeading` (5 seeds, both bridges: a walker within 1 block of the centre line always
  has deck underfoot, head room, no fence/lantern, never more than a half-block step; verified it FAILS on the old
  `Builders.bridge` deck - rail in the lane, 1-block steps, void underfoot) and `oldBridgesAreRelaidSafely` (stage a v4
  bridge + two player blocks, run the plan: walks clean, player blocks untouched, second pass is a no-op; a v5
  island gets no plan). **14/14** unit tests (JUnit launcher). `tools/preview.sh` **RESULT: OK** (0 unreachable,
  0 floating, 89.5% reachable); `crop_iso.png`/`crop_top.png` (crop -50,-40,-10,45) read: both bridges solid wide decks.
- **Not run live.** Live check: walk the Portal Isle bridge diagonally edge to edge on an island built before today -
  within 2 s of standing on the island the log says `re-laid bridge.portal_isle ...`; or `/sb isle bridges <p>`.
- Note for core (no request): `Builders.bridge` has the same flaw for every module that lays a diagonal bridge.

## Wave 5 (2026-10-06)

`bash tools/check.sh island` **CHECK OK (189 files)**; **21/21 unit tests** (7 maths + 8 generator incl. the new
`farCourtFitsItsRetrofitBox` and the Far Court in `portalIsleFramesAreValid` / `bridgesAreWideOnEveryHeading` + 6 new
`CoopPermTest`), javac + the JUnit platform launcher; `tools/preview.sh` **RESULT: OK** (generator v6: 0 unreachable
anchors, 0 floating components, 0 out-of-bounds, 89.7% of standable cells reachable; `crop_iso.png` / `crop_top.png`
with `crop=-108,15,-25,65` read: the Far Court islet, its four coloured frames, the straight bridge from the Portal
Isle). **Not run live** (no server in this phase).

### Done

- **Per-member co-op permissions** (research 05 §9.2: "per-member toggles for build/break/chests/minions", plus the
  co-op scam note asking for Owner/Member roles on island chests). Five toggles per member - **Place Blocks, Break
  Blocks, Open Containers, Use Minions, Manage Island** (`CoopPerm`, pure, unit-tested) - stored per island in
  `IslandSettings.coopPerms`; a member with no entry gets the dial `island.coop-default-perms` (default: everything,
  which is the reference behaviour, so nothing changes until an owner acts).
  - **The owner** is the island's longest-standing member, recorded in `IslandSettings.owner`. Resolved on every
    `ProfileReady` (and on opening `/is coop`): a solo profile -> that player; a recorded owner still in the co-op is
    kept; otherwise the earliest-joined current member. Core exposes no owner and its loaded member list is a HashSet
    copy (unordered), so that one lookup reads core's `profile_members` table ordered by `joined`, read-only, on the DB
    thread; if it fails nobody is restricted (Request 8 is the clean fix). Owners can hand the island over
    (**Make Owner**, with a confirm) and the old owner keeps every permission until the new one changes them.
  - **Enforcement**: `canBuild` (core's protection, minions placement, farming, alchemy, crafting furniture...) is now
    "member AND inside the build square AND (Place OR Break)". On top, island listeners at NORMAL refine: placing blocks /
    buckets out / signs / frames / armour stands / entities need Place; breaking / buckets in / breaking frames need Break;
    containers are denied without Open Containers and **re-allowed** with it even for a member who lost both block
    toggles (core's HIGH container block is overridden at HIGHEST, inside the build square only); armour-stand gear counts
    as a container. Manage gates island settings, visitor mode, offline visits, `/setspawn` and reset spawn, kick /
    kick-all / ban / unban (command AND menu paths); a member without Manage is also ignored when the visitor mode is
    mirrored from members' personal Settings (so they cannot close the island from their own settings screen).
    Build mode and the dial `island.coop-permissions` = false bypass all of it.
  - **Use Minions** is published, not enforced here: the minions module opens minion menus from a LOW handler that ignores
    cancellation, so only it can honour the toggle -> requirement key `island:perm:minions` (Request 9).
  - **Requirement keys** (new prefix `island`): `island:perm:<build|break|containers|minions|manage>`, `island:owner`,
    `island:home` (on your own island), `island:<islandId>` (standing on that island).
  - **Menus**: Island Management slot 34 "Co-op Permissions" -> `/is coop`: every loaded member (owner first, with a
    tick/cross per permission); the owner clicks a member -> five toggles + Grant Everything / Revoke Everything / Make
    Owner. Members see the same screen read-only. A member who loses a permission while online is told.
- **The Far Court** (generator **v6**): the Garden, Backwater Bayou, Lotus Atoll and Torrhus Canyon get Portal Isle
  portals, on their own islet (centre -88,40) west of the Portal Isle, joined by a straight 3-wide `IslandBridge` from the
  courtyard's west end. Two facing rows of two VALID 2x3 obsidian frames (shared portal-row builder with the Portal Isle),
  corners/crown/carpet in each place's colours (moss, mud-and-mangrove, pink, terracotta), paving, lamps, a little
  planter. Areas `isportal.<warp>` + `far_court`, anchor `far_court`, data `bridge.far_court`. Bounds grew west to -108.
  Lighting, labels, the walk-in, the visited + requirement check, `/is portals` (now 14 icons) and Island Management all
  read `PrivateIslandGenerator.ALL_PORTALS`. Portal Isle anchors, fairy souls and everything else of v5 did not move.
  - **Older islands**: Gerald's slot 22 offers "Build the Far Court" on a v4/v5 island (and "Build the Portal Isle" on a
    pre-v4 one now builds both). Same rule as before: islet blocks only into completely EMPTY columns, bridge parts only
    into air. Box `RETRO_FAR` = x -108..-64 (it starts on the Portal Isle's west coast so the bridge meets it); a unit test
    proves every Far Court block lies inside it and no Portal Isle frame does. Admin: `/sb isle retrofit <p>` builds
    whichever part is missing. Dial `island.retrofit-portals` hides Gerald's offer.
- **Accessories / pets / reforges effects that belong here**: read all three hand-offs and grepped their data for
  island wording. None is the island's to apply: "minions work X% faster on your island" (Potato talisman/ring, Rabbit,
  Ocelot, Pigman, Magma Cube, Spider, Snail pets) is minions'; Phoenix flight on the private island is pets' (done
  there); Chicken "anywhere but your private island", Bluetooth rings "players share your island", coin talismans
  "public islands", the gravity talisman's island spawn are their owners' and only need `Features.PrivateIsland`
  (`isOnOwnIsland`, `home`), which already answers. No reforge mentions the private island.
- Two new Gerald tips (the Far Court, co-op ownership); `/is help` lists the new commands.

### Still left

- **Never run live** (all of the above is compiled, unit-tested and previewed; the Bukkit side is code-reviewed only).
  Live check: two accounts in one co-op -> `/is coop` as the creator shows them as Owner; revoke the other's Break ->
  they cannot mine the quarry ("The island owner has not given you the "Break Blocks" permission"); revoke Open
  Containers -> the house chest refuses; revoke both block toggles but keep Containers -> chests still open; revoke
  Manage -> `/is settings` toggles and `/is kick` refuse. On a v5 island: Gerald -> "Build the Far Court"; on a new
  island walk west over the Portal Isle -> four frames, lit once you have been to each place.
- **Use Minions** waits on the minions module (Request 9). Until then the toggle is shown and stored but minions ignore it.
- ~~**Social skill unlocks**~~ - **built in wave 5b** (below): the shops module sells the Island Decorator's goods
  (and runs the three minigames); everything the placeables do on an island is here, plus Island Ranks and Island Naming.
- **Guest limit base (5)** stays TUNE: research still gives only "+1 ... +4 (?)" per Guests Limit tier.
- ~~A member with neither Place nor Break loses vanilla workstations~~ - **fixed in wave 5b** (workstations are re-allowed
  for every member inside the build square). Such a member still earns no Social host XP, because skills asks `canBuild`
  (Request 10).
- Remaining portal destinations (Rift, Crystal Caverns, Frozen Tunnels, Winter Workshop, Critter Safari, Kuudra) - see
  the partial list for why each is left to its own route.

## Wave 5b (2026-10-06, continued): the Social road on the island

`bash tools/check.sh island` **CHECK OK (193 files)**; **29/29 unit tests** (the 21 above + 8 new in `DecorTest`), javac +
the JUnit platform launcher; no control characters in any island source. Generator untouched (still v6). **Not run live.**

### Done

- **The Island Decorator's placeables now DO something** (answers shops Request 12; research 02 §2.3: Parkour Blocks at
  Social 1, Social Display 2, Egg Hunt 3, Island NPC 5, Showcase Block 10). The shops module still registers and sells the
  items (`data/shops/items.json`) and runs the three minigames; the island tracks every placed one per island in
  `IslandSettings.decor` (pure rules in `Decor`, Bukkit side in `IslandDecorations`). They can only be placed on your OWN
  private island (any member who may place blocks), limits are dials, breaking one gives the ITEM back (never the vanilla
  drop), a block that holds a plate or an egg cannot be broken until the decoration comes off, and a record whose block has
  vanished (piston, island reset) is dropped silently - never refunded, so nothing can be duplicated.
  - **Parkour**: Start/End plates (max 2: the first is the start, a second the finish; with one plate the course is a loop,
    which needs at least one checkpoint), checkpoints run in the order they were PLACED, a Times plinth shows the best 10.
    Stepping on the start starts a timer (action-bar segment `island_parkour`: time + checkpoint x/y); checkpoints must be
    taken in order ("Wrong checkpoint!"), the finish only counts after the last one; stepping on the start again restarts.
    A run is cancelled by teleporting, leaving the island, dying, flying/gliding, quitting, or the time limit (dial).
    Best times per player per island; **changing the course (a plate added or removed) resets the times**, so a shortened
    course can never keep a record. `/is parkour [times|checkpoint|restart|cancel]` (checkpoint = back to your last one,
    timer running). Guests can race - parkour plates stay usable even when "Guests: Doors & Switches" is off.
  - **Social Display**: a plinth with the island's top 10 visitors by visit count (new per-guest counter
    `IslandSettings.guestVisits`, capped by dial) and the unique-guest total.
  - **Egg Hunt**: up to 10 eggs (the item's own lore); a guest right-clicks one to find it once ("You found an egg! 3/7",
    "EGG HUNT COMPLETE!", members told); members right-clicking see how many guests found it. Island Decorations ->
    right-click "Egg Hunt" starts a new hunt (forgets who found what).
  - **Showcase Block**: right-click it holding an item -> a display copy of the item floats inside the glass with its name
    above. **The item stays in your inventory**: the case keeps a picture (`serializeAsBytes` of a one-item copy with every
    plugin tag stripped), so a showcase can never hold, lose or duplicate a real item. Sneak + empty hand clears it; guests
    get the item's hover card in chat. // TUNE: the reference may lock the item inside; ours is deliberately a picture.
  - **Island NPC**: using the spawn egg on your island puts a resident there (consumed BEFORE it is recorded); right-click
    it (anyone who may place blocks) -> Rename, Set Greeting, Change Look (15 villager professions), Face Me, Move Here,
    Pick Up (gives the egg back). Guests who click it hear its line. Typed text is cleaned (colour codes and symbols
    stripped, length capped) and checked against dial `island.text-blocklist`; dial `island.custom-text` turns typing off.
  - Every floating text / item / NPC is a NON-persistent entity re-made every 2 s while someone is on the island and removed
    when nobody is (same rule as the Portal Isle labels), so nothing piles up in the world.
- **Island Ranks** (Social 9; research names the reward and nothing else, so the ranks are designed here - TUNE): a member
  with Manage + Social 9 gives a non-member **Friend** (not counted against the guest limit; may always use doors, gates,
  trapdoors, buttons, levers) or **Trusted** (a Friend who may also open containers and pick up items). Ranks only LOOSEN
  the island's guest settings for that player; nobody outside the co-op can ever build, break or touch minions. Shown in
  the arrival title, the members' "is visiting" line, the guests menu and the visit list. Banning someone drops their rank.
  `/is rank <player> <friend|trusted|guest>`, `/is ranks` (paged; left-click cycles, right-click removes, "Rank a Player"
  by name), shift-click a guest in `/is guests`.
- **Island Naming** (Social 20): `/is name <name>` / `reset` (Manage + Social 20; reset needs only Manage). The name
  replaces "Owner's Island" in the arrival title, the sidebar's "Visiting:", the tab widget, Island Management and the
  visit list.
- **Island Decorations screen**: Island Management slot 40 -> `/is decor`: counts vs limits, the course and its top 3,
  the visitors board, egg hunt progress (reset), showcases, NPCs, a link to the Island Decorator (`/merchants decorator`),
  Island Name and Island Ranks buttons (gated, the lore says which Social level).
- **Workstations for restricted members** (a wave-5 behaviour gap): a member whose owner revoked both block toggles is no
  builder to core, which also took their crafting table, anvil, enchanting table, smithing table, grindstone,
  stonecutter, loom, cartography/fletching table and bell. Those only open a screen, so the island re-allows them at
  HIGHEST for every member inside the build square (`CoopPerm.WORKSTATIONS`). Containers still follow "Open Containers".

### Still left

- **Never run live.** Live check: Social 1+ account buys two Start/End plates and two checkpoints from the Island Decorator,
  places start -> cp -> cp -> finish on the island, runs it (action bar timer, "Wrong checkpoint!" when skipping, "PARKOUR
  COMPLETE! ... FIRST TIME!"), puts a Parkour Times plinth down (best time floats above it). A guest account visits: finds
  two eggs, right-clicks the showcase (hover card), clicks the NPC (its greeting). `/is rank <guest> trusted` with Social 9
  (`/sb skills` admin to set it) -> the guest opens a chest although "Guests: Containers" is off. `/is name Test Isle` with
  Social 20 -> the guest's sidebar says "Visiting: Test Isle".
- Island Ranks are a design (TUNE): the research gives only "Unlock Island Ranks". If the reference's ranks turn out to
  mean something else, `IslandRank` is the one place to change.
- The showcase is a picture, not a vault (TUNE, deliberate for a public server).
- One parkour course per island (the reference's limit is unknown); checkpoint count, plinth counts and NPC count are TUNE dials.
- Still open from wave 5: Use Minions (Request 9), guest-limit base (TUNE), Social host XP for members without block
  toggles (Request 10), the remaining portal destinations.

## Commands added (player + admin)

Player:
- `/is` (aliases `/island`, `/home`) — go to your island. Subs: `menu`, `settings`, `setspawn`, `portals`, `coop` (wave 5),
  `perm <member> <build|break|containers|minions|manage|all> <on|off>` (owner, wave 5), `transfer <member>` (owner, wave 5),
  wave 5b: `decor` (Island Decorations), `parkour [times|checkpoint|restart|cancel]`, `name <name|reset>` (Social 20),
  `rank <player> <friend|trusted|guest>` (Social 9), `ranks`,
  `guests`, `kick <p>`, `kickall`, `ban <p>`, `unban <p>`, `bans`, `visit <p>`, `help`.
- `/visit [player]` — visit an island (no name = browse menu).
- `/setspawn` — set your island spawn where you stand.

Admin (`/sb isle ...` — **not `/sb island`, which is core's world tool**):
`info <p>` (key, origin, built, size/tier, guest limit, every setting, spawn, bans, guests, helper, kit) ·
`home <p>` · `tp <p>` (you visit, ignoring rules) · `visit <visitor> <host>` (prints the permission verdict, then forces) ·
`kit <p> give|reset|status` · `helper <p>` (respawn Gerald) · `settings <p> visitors|containers|doors|pickup|offline <value>` ·
`guests` (every island with guests) · `kickall <p>` · `spawn <p>` · `portals <p>` · `retrofit <p>` (whichever of the Portal Isle /
Far Court is missing) · `bridges <p>` · `reset <p> confirm` · wave 5: `owner <p> [member]` (show / set the owner) ·
`perm <p> <member> <perm|all> <on|off>`. `info <p>` now prints the owner and each member's permissions. Wave 5b:
`decor <p> [list|wipe]` (every placed decoration + course state; wipe forgets them without refunds), `name <p> <name|reset>`,
`rank <host> <player> <friend|trusted|guest>` (no Social gate, no cap). `reset` now also wipes the island's decorations.

## Dials added

`island.base-size` 161 · `island.size-per-tier` 8 · `island.build-min-y` 0 · `island.build-max-y` 255 ·
`island.guest-limit` 5 (TUNE) · `island.quarry-regen-seconds` 6 (TUNE, 0 = off) · `island.host-leave-grace-seconds` 30 ·
`island.visit-cooldown-seconds` 3 · `island.unique-guest-cap` 500 · `island.visit-offline` false ·
`island.first-join-home` true · `island.helper-enabled` true · `island.read-community-shop` true ·
`island.enforce-guests` true · `island.guest-arrival-title` true · `island.repair-bridges` true ·
`island.starter-kit` "ROOKIE_PICKAXE:1,ROOKIE_AXE:1,ROOKIE_HOE:1,BREAD:8" ·
wave 5: `island.coop-permissions` true · `island.coop-default-perms` "build,break,containers,minions,manage" ·
`island.retrofit-portals` true ·
wave 5b: `island.decor-enabled` true · `island.decor.max-parkour-start` 2 · `island.decor.max-checkpoints` 25 (TUNE) ·
`island.decor.max-times` 3 · `island.decor.max-displays` 3 · `island.decor.max-eggs` 10 · `island.decor.max-showcases` 20 ·
`island.decor.max-npcs` 5 · `island.parkour-max-seconds` 900 · `island.board-lines` 10 · `island.board-cap` 200 ·
`island.rank-cap` 50 · `island.rank-social-level` 9 · `island.name-social-level` 20 · `island.name-max-length` 24 ·
`island.npc-text-max-length` 60 · `island.text-blocklist` "" · `island.custom-text` true.
(Reads core's `world.join-location`.)

## Self-tests added

`island.generator-walkable` (runs the real generator offline: walkability OK, no floating, nothing out of bounds, layout checks) ·
`island.template-layout` (the live template layout has every required anchor/area, 10-40 souls, one BUILD zone covering 241x241,
`build` = the 161 square containing spawn; warps `hub`, `private_island`, `home` exist) ·
`island.size-formula` (161 at tier 0, 241 at tier X, the shipped table increasing and ending at 241) ·
`island.starter-kit-items` (every kit id registered) ·
`island.portal-isle` (wave 3: ten template openings, every destination warp registered to a known island) ·
`island.service-and-npc` (`Features.PrivateIsland` is ours, Gerald registered, island instanced, spacing > largest island) ·
`island.menus-build` (management/settings/Gerald/portals/co-op menus + sidebar + tab build for an online player) ·
wave 5: `island.coop-permissions` (owner always allowed, toggles honoured, the defaults dial names only real permissions,
the `island` requirement prefix is registered) · `island.far-court` (four openings in the template, each warp registered
to a known island, area `far_court`, every opening inside the retrofit box) · wave 5b: `island.decor-rules` (a start ->
2 checkpoints -> finish course: skipping is refused, the finish needs every checkpoint, the time is exact; an egg is found
once; a third Start/End plate is refused) · `island.decor-items` (all seven decorator items registered, the block ones
placeable and of the material the island tracks) · `island.ranks-and-names` (rank cap, Trusted/Friend/Guest rules, typed
text cleaned, the `skill` requirement prefix exists so the Social gates can pass). `island.menus-build` also builds the
Island Decorations and Island Ranks screens.

Unit tests: `plugin/src/test/java/com/skyclone/mod/island/IslandMathTest.java` (7), `PrivateIslandGeneratorTest.java` (8), `CoopPermTest.java` (6, wave 5), `DecorTest.java` (8, wave 5b) — 29/29 pass
(run standalone with the JUnit launcher, since gradle is shared).

## Requests (things you need from core or another module — the integrator reads this)

1. **core (commands)**: the brief asked for admin tools under `/sb island ...`, but core's world module owns `/sb island`.
   Mine are `/sb isle ...`. If you want them under `/sb island`, core could forward unknown `island` subs to a hook.
2. **core (npc)**: shops' hand-off noted `NpcServiceImpl.checkAlive` looks `spawnAt` copies up by their BASE id; Gerald's base
   never spawns (instanced island), so a watchdog/health check may report `island_helper` dead or log about it. Please key
   copies by `npcId#key`.
3. ~~**core (world)**~~ (done from the island side in wave 3 by redirecting the landing teleport; a real hook would be cleaner): optionally let a module override an instanced warp's landing point (e.g. a `Features.PrivateIsland.home`
   lookup when the warp is `home`/`private_island`), so the hub portal and `/warp home` honour `/setspawn`.
4. **minions**: register `COBBLESTONE_GENERATOR_1` (Gerald's starter kit). Use `Features.PrivateIsland.canBuild(p, l)` to decide
   where a minion may be placed; anchors `minion.pad.1..5` mark five ready-made 5x5 slots (area `minions`). Guests are already
   blocked from touching armour stands on islands they don't belong to.
5. **menus**: the "Your Island" quick button runs `/is` (good). A right-click → `Sky.commands().run(p, "is", "menu")` would give
   the SkyBlock Menu a path to Island Management; the `island` Settings category could link to `/is settings` for the guest
   permissions that live here (containers, doors, pickup, offline visits).
6. **hub builder**: `portal.private_island` must exist on the hub (warp id `private_island` is registered here).
7. **bank**: `cshop:island_size:N` and `cshop:guests_limit:N` are read; nothing else needed.
8. **core (profiles) / co-op**: expose a profile owner (or members ordered by join time). Wave 5 built the permissions
   anyway by reading `profile_members ... ORDER BY joined` directly (read-only, `IslandService.ensureOwner`); an API
   (e.g. `ProfileService.owner(profileId)`, or keeping `LoadedProfile.members` in join order) would let that go.
9. **minions (wave 5)**: honour the co-op toggle "Use Minions": before opening a minion/crystal menu, placing one, or
   picking one up, check `Sky.requirements().check(p, "island:perm:minions")` (true for owners, solo players, admins in
   build mode and whenever the island dial is off) and say "The island owner has not given you the \"Use Minions\"
   permission." when it fails.

10. **skills (wave 5b)**: Social host XP asks `Features.PrivateIsland.canBuild`, so a co-op member whose owner revoked both
   block toggles earns none while hosting. Ask `isOnOwnIsland(p)` instead (or `Sky.requirements().check(p, "island:home")`).
11. **shops (answering your Request 12)**: done here - keep registering the Island Decorator's goods; the island reads the
   item ids on place/break/use and needs nothing else. Please keep `PARKOUR_CONTROLLER` / `PARKOUR_POINT` as light / heavy
   weighted plates, `PARKOUR_TIMES` iron, `SOCIAL_DISPLAY` gold, `EGG_HUNT` a player head and `SHOWCASE_BLOCK` glass - the
   island uses the block material to notice a decoration that is gone (`island.decor-items` checks it).

## Known risks

000. **Wave 5b decorations** are new and unplayed. Parkour runs on `PlayerMoveEvent` block changes (the plate is the block at
    the player's feet); NPCs are non-persistent villagers with AI off, invulnerable, tagged `skyclone_island_decor`, every
    click on them cancelled at LOWEST - another module that handles villager clicks with `ignoreCancelled = false` would
    still see it. Showcases deserialize stored item bytes with `ItemStack.deserializeBytes`; an item from a much older
    data version may fail to load and then simply shows nothing.
00. **Wave 5 co-op permissions change what `canBuild` means**: a member whose owner revoked BOTH Place and Break is no
    longer a builder anywhere on the island, for every module that asks (minion placement, farming, furniture, alchemy's
    stand, skills' Social host XP). With the defaults (everything on) nothing changes. The owner lookup reads core's
    `profile_members` table directly; if core renames it, the read fails safe (owner left unset = nobody restricted) and
    logs once per profile.
0. **The Portal Isle is new and unplayed.** Lighting writes nether-portal blocks (physics off) into a valid obsidian frame;
   walking in is our own `PlayerMoveEvent` (MONITOR); vanilla nether travel is already cancelled in game worlds by core.

1. **Join timing on a cold slot.** A brand-new player's island is built on login; until it is placed they wait at the hub/limbo
   and are moved home when it finishes. If core's build queue is long (many simultaneous first joins), they may start walking
   around the hub first — then they are NOT pulled back (by design) and must type `/is`.
2. **Location → island mapping is a cache** (grid origin → profile key), filled from logins, visits and online players. A
   player standing on an island whose owner never logged in this session (only possible through admin teleports) is treated as
   being on "nobody's" island: they cannot build there and are not tracked as a guest.
3. **Guest-protection overlap with other modules**: the minions/garden modules may add their own armour-stand/entity click
   handlers; mine cancel guest interactions at LOW priority, so a module that listens with `ignoreCancelled = false` will still
   see the click and must check `canBuild`/ownership itself.

## Playtest triage 2026-10-03 (playtest-newplayer, filed under "island")

Both findings filed against island were traced and **neither originates in this module**; no island code changed.

- **"+35 SkyBlock XP (Heart of the Mountain Tier 1)" on first join** — owner: **mining**.
  `MiningModule.onReady(ProfileReady)` calls `Hotm.payTierRewards(p)`; HOTM tier 1 sits at 0 XP
  (`HotmTree.tier(0) == 1`), so `rewardedTier` 0 → 1 pays `skyblockXp[0]` (35) to every new profile
  the moment it loads. Fix belongs in `mining/Hotm.java`: only pay tier 1 once HOTM is actually unlocked
  (first Dwarven Mines visit / first HOTM XP), or seed `rewardedTier = 1` silently for profiles that have
  never earned HOTM XP. The playtest's "island /" prefix is only because it happens while you land on the island.
- **Sidebar footer `www.skyclone.example`** — owner: **core ui**. Default of dial `server.footer`
  (`core/ui/UiDials.java:42`, fallback in `SidebarServiceImpl.java:315`). Changeable live with the dial;
  the default text is core's to choose. Island contributes only its own sidebar section (prio 300).

`bash tools/check.sh island` → CHECK OK (177 files).
