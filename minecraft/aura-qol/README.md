# Aura QoL

A client-only Fabric mod with a draggable HUD, quality-of-life tools and a SkyBlock toolkit: live item prices, a money-per-hour tracker, sacks, commissions, slayer and ability panels.

The sibling of Aura Client (menus, cosmetics, accounts), kept as a separate mod on purpose. One source tree builds for Minecraft 26.2 and 26.1.2. Every feature is wrapped so one failing feature never takes the mod down, and every feature can be switched on or off instantly from a Lunar-style `/mods` library.

## Features

- **HUD**: info panel, CPS counter, keystrokes, zoom on C, your own nametag in third person
- **HUD editor** (`]`): drag, scroll to scale, right-click to hide, plus 5 save slots for whole layouts
- **Chat hotkeys**: a key or mouse button (with exact modifier matching) sends a chat line or a command
- **Price tooltips** on SkyBlock items: Bazaar buy/sell, lowest BIN, pet brackets, NPC sell, stack value
- **Recipe to Bazaar**: right-click an ingredient in a recipe menu to search it on the Bazaar or Auction House
- **Money tracker** (`/money`): coins per hour over the last N active minutes or the whole session, broken down by source, with an optional decision log
- **Blocks per second**, including blocks broken by the server (Mining Spread)
- **Sacks, Commissions, Slayer and Pickaxe Ability panels**, read from the sidebar, tab list, chat and item lore
- **To-do list** with importable checklists
- **Server data channel client**: a binary protocol that lets my prison server push live mining, earnings, rank-up and contract stats into seven HUD panels

## How it works

- **Pure logic, tested without Minecraft.** Everything that decides anything (chat parsing, pricing, income rules, break counting, the binary codec) lives in packages with no Minecraft imports. The suite has grown to over 230 unit tests, plus live checks that hit the real price APIs.
- **A money tracker rebuilt from a real session log.** My first version went negative while farming: harvested crops pass through the inventory for about half a second on their way into sacks, so every inventory change was counted and then the sack summary counted the same crops again. I replayed a real 534-line session log as a test (`MoneyLogReplayTest`); the rebuilt engine reaches the same total (394,728 coins) on every counted line and never goes below zero at any of the 533 steps. Drops are counted from chat and netted against their later arrival, so nothing is counted twice.
- **Server-side breaks.** On mining islands the server breaks the block itself, so the client break event never fires. A mixin on the one method that applies both block-update and section-update packets sees those breaks (and Mining Spread), and a de-duplicating counter stops two sources from counting one block twice.
- **A frozen binary protocol with golden fixtures.** The server data channel uses VarInt/ZigZag framing. 26 golden fixtures decode and re-encode byte-for-byte, the test fails if the server repo's copy differs by a single byte, and a fuzz test throws 20,000 random frames at the decoder, which may only ever raise its own checked `DecodeException`.
- **Pricing that matches what players already see.** Lowest BIN comes from the same backend the Skyblocker mod uses, with a fallback source. A live round-trip check found two items sharing a display name where one is a dead Bazaar key with no orders, which had silently valued every hay bale at zero.
- A source-hygiene test fails the build on any control character in a source file, after a regex escape was turned into a raw control byte by a shell heredoc.

## Tech

- Java 25, Fabric Loader + Fabric API, Mixin, Minecraft 26.2 and 26.1.2
- JUnit 5, Gson for API payloads
- Hypixel public API (Bazaar, items), hysky.de and Coflnet for lowest BIN

## Running it

- `Build.bat` builds `auraqol-<version>+mc26.2.jar` and `+mc26.1.2.jar` into `build/libs/` and runs the unit tests.
- `Test.bat` runs the unit tests and then the live price-source checks (needs internet).
- `gradlew runClient` starts a dev client; `-Pselftest` logs what the SkyBlock features compute every 2 seconds, `-Pdemo` draws sample data in empty panels.

Downloaded dependencies and the dev run folder are not included. This is an independent client mod and is not affiliated with Hypixel.
