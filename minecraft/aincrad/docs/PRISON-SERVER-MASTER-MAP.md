# Shadow Prison — Complete System & Transfer Map

> Portable source-of-truth for understanding what the prison server contains and deciding
> what moves into a new Prison × SAO project.
>
> Snapshot: **2026-07-23**
>
> Scope note: this describes systems and player-facing capabilities, not every implementation
> class or every balance value. The repository currently contains **457 Java source files,
> 42 top-level packages, 150 registered commands, 18 bundled YAML files, and 39 topic-specific
> knowledge documents**. The code and the live server's YAML remain authoritative when a
> number or deployment status matters.

---

## 1. Which document answers which question?

| Document | Purpose |
|---|---|
| **This file** | Portable inventory of the game, its reusable systems, and its Prison × SAO transfer plan |
| `HANDOVER.md` | Operating the dev/live servers, admin tools, recovery, deploys, and historical state |
| `commands.md` | Detailed player/admin command reference |
| `knowledge/INDEX.md` | Router to focused engineering notes and hard-won system gotchas |
| `PROGRESSION-PLAN.md` | Prestige, Ascension, Void Coins, and endgame progression intent |
| `QUESTS-PLAN.md` | The Induction onboarding chain and quest-engine intent |
| `themes/THE-STACK.md` | Existing Cloudspire/SAO-fusion pitch and build plan |
| `.agents/memory.md` | Current cross-session status: live/dev/in-flight/verification notes |
| `plugin/src/main/resources/*.yml` | Bundled defaults and content definitions |
| `server/plugins/GamePlugin/*.yml` | Current dev-server runtime data/configuration |
| Live host YAML | Final authority for live balance and live player data |

This file is deliberately not an incident log or deploy manual. It is the file to copy into
the new project so a fresh session can understand the complete inheritance surface without
dragging the old server's runtime world and player data with it.

---

## 2. The game in one page

Shadow Prison is a persistent Paper prison MMO built around a mining progression loop:

1. Enter a mine available to the player's rank.
2. Break blocks for sellable materials, keys, currency, rare finds, skill XP, and enchant procs.
3. Sell inventory, improve gear, complete quests, trade, collect, and participate in events.
4. Rank from **A through Z**.
5. Prestige through **P1–P30**, unlocking new mines, enchants, crates, places, and rewards.
6. Ascend at P30, resetting core economic progression while retaining selected permanent power
   and identity.
7. Repeat while pursuing serialized collectibles, guild/gang goals, achievements, pets, relics,
   leaderboards, events, and social status.

The plugin is not merely a mine resetter. It contains a complete economy, long-form progression,
collection economy, social layer, event framework, quest/achievement systems, cosmetics,
moderation/operations tooling, a custom resource pack, and multiple alternate themed universes.

---

## 3. Non-negotiable product laws

These rules transfer into every descendant project.

1. **No gambling.** No wagers or player-vs-player staking. Random earned rewards are the ceiling.
2. **No pay-to-win.** Paid roles may provide identity/cosmetics/convenience, never combat,
   sell, progression, luck, or drop advantages.
3. **Never expose exact reward odds to players.** Use rarity language and atmospheric progress,
   not percentages or `1 in N`.
4. **Item safety is sacred.** Serialized or valuable items use PDC identity, never display-name
   matching. Every transform/grant follows consume-one, validate-one, grant-one.
5. **Every gameplay dial gets an in-game admin surface.** YAML is the backup, not the owner's
   everyday tuning interface.
6. **World builders are versioned or explicitly invoked.** They never repaint hand-built areas
   on every restart.
7. **Heavy world generation is block-budget sliced.** No giant same-tick builds.
8. **Persistent saves are atomic and shutdown-critical data flushes synchronously.**
9. **Ceremonies and repeating tasks survive individual frame failures and log abort paths.**
10. **Live configuration and player data are never copied from dev over live.**

---

## 4. Complete gameplay-system inventory

### 4.1 Core prison progression

- Configurable mines with bounds, material palettes, reset thresholds, reset schedules, borders,
  walls, surfaces, signs, trader placement, and generation state.
- A–Z rank ladder with costs, rank gates, mine access, `/rankup`, `/rankcost`, and admin setters.
- Automatic mine regeneration with performance budgets, proximity rules, and cube-only reset
  safety.
- Player balance, blocks mined, ranks, prestige, ascension, titles, currencies, settings, and
  other long-lived profile fields.
- `/mine`, `/mines`, `/prison`, `/refresh`, mine navigation, menus, wayfinding, and mine titles.
- Sell flow through `/sell`, `/sellall`, filters, material pricing, custom-item pricing,
  condensed-item pricing, boosters, receipts, and shops.
- P1–P30 prestige arc with confirmation, escalating costs, skill gates, reward tables,
  milestone rewards, prestige mines, bridge gates, prestige lounge/crates, and the cinematic
  **Take the Walk** ceremony.
- Ascension at P30 with a reset/retention contract, permanent bonuses, Ascension tags,
  Ascendant Sanctum, and the `a1` mine.
- Per-player playtime, progression display, scoreboards, profiles, and leaderboards.

Primary code:

- `com.yourgame.prison`
- `com.yourgame.economy`
- `com.yourgame.prestige`
- `mines.yml`, `ranks.yml`, `prestige.yml`, `prices.yml`

### 4.2 Skills, XP, and long-term improvement

- Shadow Skills: mining, smelting, selling, and acrobatics.
- Skill XP/progression, menus, persistence, and mining hooks.
- Super Breaker and enchant-aware efficiency behavior.
- Vanilla XP withdrawal/storage tools and XP artifact/bottle support.
- Furnace enhancement progression.
- Haste Totems: earned, tradeable, duration-based mining boosts with success flow.
- Persistent Ascension bonuses and prestige milestone progression.
- Achievement definitions, triggers, progress, rewards, storage, and menus.

Primary code:

- `com.yourgame.skills`
- `com.yourgame.achievements`
- `com.yourgame.economy`
- `achievements.yml`

### 4.3 Custom enchants and equipment improvement

- A large custom-enchant engine with definitions, levels, rarity tiers, gradients, lore,
  application rules, proc feedback, and persistence on items.
- Multi-enchant pickaxes and a configurable slot ladder.
- Player enchant-level cap plus admin access up to level C/100.
- Custom anvil and enchanting-table handling.
- Enchant books, failure rules by rarity, armor eligibility, additive multi-slot behavior,
  and exact-input `/config` dials.
- Prestige enchant progression including RUSH and SHATTER.
- Area mining: Rift, Devour, Oblivion, Plunder, and related depth/radius rules.
- Mining/economy effects including autosmelt, bonus drops, key finding, rare materials,
  durability behavior, cooldown manipulation, forced proc chains, and event-horizon effects.
- Enchant surgery through Unenchanter, Itemizer, Combiner, fusion, and unfuse flows.
- Defensive/combat enchant support and special services such as Early Frost, Faint Resolve,
  Second Wind, Flash Freeze, Starforged, and Super Breaker integration.
- Enchant crate ladder: Enchant, Enchant+, and Enchant++, gated by progression.

Primary code:

- `com.yourgame.enchants`
- `enchantments.yml`
- `knowledge/enchant-rarity-tiers.md`
- `knowledge/enchants-progression.md`

### 4.4 Economy and currencies

The server contains several mechanically distinct value systems:

- **Money** — selling, ranks, prestige, shops, auction house, mall, player payments, lottery pot.
- **Shadow Shards** — collector/store currency.
- **Void Coins** — rare earned gameplay currency for the Void Exchange and progression items.
- **Merchant Marks** — earned through selling and used by the Mall ecosystem.
- **Archive Points** — serial-collection sink/reward currency in the Archivist design.
- **Crypto balances** — simulated market earnings backed by GPU hash rate.
- **XP levels/artifacts** — stored or spent through XP-related services.
- **Gang banks** — shared social-group balances.
- **Keys, cases, crates, relic materials, repair resources, and custom ores** — item currencies.

Supporting systems:

- `/pay` with immediate scoreboard refresh.
- Configurable prices for vanilla materials and custom tagged items.
- Condense/uncondense and custom crafting recipes.
- Cash/essence bundles and physicalized rewards.
- Black Market hourly rotation.
- Player shops, NPC shops, category menus, quantity picker, and config editors.
- Lottery/community draw system with rollover behavior. This is an existing ceiling for
  chance-based rewards, not a precedent for wagers.
- Sell and global event boosters.

Primary code:

- `com.yourgame.economy`
- `com.yourgame.shop`
- `com.yourgame.store`
- `com.yourgame.craft`
- `prices.yml`, `shops.yml`, `store.yml`, `recipes.yml`

### 4.5 Crypto and GPU progression

- Simulated crypto market and player crypto balances.
- GPU tier definitions, items, gradients, hash rates, find chances, and celebrations.
- Three-slot GPU rig system.
- GPU Collection/Library with one reversible physical GPU slot per tier; collected GPUs
  continue contributing hash rate.
- Salvage, earning rolls, display modes, notification preferences, and admin configuration.
- Per-player luck multipliers and global luck/event seams.
- Durability warning preferences integrated into the notification system.

Primary code:

- `com.yourgame.crypto`
- `gpus.yml`
- `knowledge/gpu-tiers.md`

### 4.6 Crates, cases, keys, and reward delivery

- Physical prison crate stations with persisted placement and rebuild/admin tools.
- Multiple key/crate tiers, prestige crates, raid crates, Superior rewards, birthday crates,
  and progression-gated enchant crates.
- Sacred Cases and collector-store case flows.
- Animated reel/reveal menus with safe pending-reward handling.
- Crate/case loot previews.
- Physical openable tokens and migration handling.
- Bulk opening where allowed, without violating one-token/one-roll/one-reward.
- Claim buckets for offline, delayed, overflow, and cataloged reward delivery.
- Config editing, item vault/admin copies, and audited grant paths.
- Global buffs from special crate rewards.

Primary code:

- `com.yourgame.collection`
- `com.yourgame.claim`
- `com.yourgame.store`
- `crates.yml`, `cases.yml`, `store.yml`
- `knowledge/crate-reveal.md`
- `knowledge/crate-case-economy.md`

### 4.7 Serialized collectibles

- Definition-driven collectible designs with item models, equipment assets, tiers, and
  finite edition caps.
- Atomic serial minting where first successful mint receives serial #1.
- PDC-backed permanent identity: design, serial, edition, and ownership metadata.
- Mint audit log.
- Ownership tracking and collection/storage menus.
- Secret/RGB promotion families and Starfade/VOID design lines.
- Roman-numeral developer editions.
- Item Vault pages that can mint real audited serials.
- Collection leaderboards and profile showcase integration.
- Combiner flows for serialized armor/gear with identity-preserving fusion and unfuse.
- Archivist serial sink design, catalog, and exclusive cosmetic rewards.

Primary code:

- `com.yourgame.collection`
- `designs.yml`
- `SERIALS.md`
- `knowledge/archivist.md`

### 4.8 Gear, relics, repairs, and inventory quality of life

- Gear Sets loadouts with safe equip/swap and preview behavior.
- Profile armor/pickaxe showcase through `/currentarmor`, `/currentpick`, and profile menus.
- Aegis, Prospector, Windfall, and other relic infrastructure, menus, finds, and admin grants.
- Repair command, repair tokens/shards, durability setting tools, reset repair, and warnings.
- Condensing, custom recipes, custom items, hats, sit, trash, ender-chest/inventory peek tools,
  and container quality-of-life behavior.
- Warrior Infusion and other controlled gear transforms.

Primary code:

- `com.yourgame.gear`
- `com.yourgame.relics`
- `com.yourgame.craft`
- `com.yourgame.dev`

### 4.9 Quests, onboarding, story, and discovery

- **The Induction**: 14-step onboarding chain teaching the server one system at a time.
- Quest definitions, ordered chains, triggers, storage, popups, bossbars, grandfathering,
  resets, admin controls, and `/quests`.
- Prestige quest definitions and hooks.
- Welcome flow, soul numbering/identity, milestones, and the Soul Ledger monument.
- **The Encounter** cinematic/scene system with voice/resource-pack assets and replay tools.
- **Find Her**: rare silent appearances, discovery tracking, voice contract, and title reward.
- Achievement engine with configurable definitions and rewards.
- Birthday crate flow.
- Contextual command/help catalog and player-visible `/commands`.

Primary code:

- `com.yourgame.quests`
- `com.yourgame.story`
- `com.yourgame.achievements`
- `quests.yml`, `achievements.yml`

### 4.10 Social systems

- Gangs with creation, roles, co-leaders, bank, levels, description, rename cooldown,
  member management, menus, chat, and profile/leaderboard drill-down.
- Bounties with same-gang abuse protection.
- The Outpost permanent KOTH and gang-holder reward.
- Gang Tournament bracket/event support.
- Secure player-to-player trading with item/money confirmation and anti-swap validation.
- Auction house listing, browsing, buying, canceling, storage, and Auctioneer NPC.
- Mall with player stalls, claim/list/buy flows, offline-safe sales, idle takeover, and
  Merchant Marks.
- Player housing/cells, rent/ownership data, menus, generation, and Prestige Cell timed loaders.
  Housing remains one of the less-finished areas compared with the rest of the plugin.
- Private messages and replies.
- Vote party and player participation.

Primary code:

- `com.yourgame.gangs`
- `com.yourgame.tourney`
- `com.yourgame.trade`
- `com.yourgame.auction`
- `com.yourgame.mall`
- `com.yourgame.housing`

### 4.11 Pets and companions

- Earned-only tradeable companion eggs.
- Bind-on-use and reversible return-to-egg behavior.
- Lerp/follow movement and chunk-safe entity handling.
- Multiple pet rarities and a growing pet roster.
- Modest boosts wired into specific sell/key/coin/GPU paths.
- Pet menus, grants, persistence, and player inspection tools.
- Explicit product law: pets are not sold as pay-to-win power.

Primary code:

- `com.yourgame.pets`
- `knowledge/pets.md`

### 4.12 World events, raids, and PvP

- **Starfall world event**: rare mining trigger, private head start, crash-site access,
  drop boxes, hold-the-ring/KOTH phase, cooldowns, and rewards.
- **Starfall custom enchant**: separate, intentionally same-named system that boosts personal
  GPU find chance. It is not connected to the world event.
- **Void Surge**: server-wide timed enchant-proc and GPU-luck amplification with bossbar,
  cadence, admin controls, and configuration.
- Milestone/global boost framework.
- **Celestial Rift** raid with Void Titan, shared participation/loot credit, warnings,
  arena rules, and Raid Crate rewards.
- Boss raid engine and raid command infrastructure.
- Gang tournaments and hosted event front door.
- The Edge and Frozen Reach global rivalry boosters.
- Combat teleport lock and combat-log death behavior.
- Vote party, daily/community events, birthday rewards, and hosted ceremonies.

Primary code:

- `com.yourgame.prison.StarfallService`
- `com.yourgame.buffs`
- `com.yourgame.raid`
- `com.yourgame.adminhost`
- `com.yourgame.tourney`
- `knowledge/events-and-social.md`
- `knowledge/events-hosting.md`

### 4.13 Identity, roles, chat, and cosmetics

- Role categories for staff, VIP, mythic, and other server identities.
- Role priorities, gradients, bold styling, permissions, chat/tab/name display, and menus.
- Titles, hidden tags, title toggles, rank/prestige/ascension display, and gang identity.
- Nickname gradient/shade picker.
- Chat-color picker, bold toggle, and shader-driven animated RGB chat.
- Above-head nameplate/nametag infrastructure.
- Fifteen wing shapes with multiple color schemes and visibility/performance handling.
- Armor and pickaxe particle cosmetics.
- Admin cosmetic editor for crowns, trails, waist effects, auras, footsteps, and orbitals.
- Custom animated armor textures and equipment assets.
- Gradient Bold Renamer and VOID kit cosmetics.
- Sit, hats, rank particles, floating text, and profile/showcase identity.

Primary code:

- `com.yourgame.roles`
- `com.yourgame.cosmetics`
- `com.yourgame.perks`
- `com.yourgame.ui`
- `roles.yml`
- `knowledge/particle-cosmetics.md`
- `knowledge/admincosmetics.md`
- `knowledge/animated-chat.md`
- `knowledge/roles-titles-tab.md`

### 4.14 Menus and player interfaces

- Main 54-slot menu hub.
- Mines, lists, quests, skills, achievements, crates/cases, collection, claim, crypto,
  notifications, shops, store, auction, trade, gangs, pets, relics, profiles, leaderboards,
  titles, nick/chat, cosmetics, gear sets, and recipe interfaces.
- Per-viewer scoreboards.
- Bossbars, titles, actionbars, announcements, floating text, holograms, signs, and NPC entry
  points.
- Config/admin menus with exact-input anvil dialogs.
- Player data inspection menus and safe live inventory viewing.
- Desktop web configuration dashboard backed by a restricted HTTP API.

Primary code:

- `com.yourgame.ui`
- `com.yourgame.webadmin`
- `webadmin.html`

### 4.15 Places and world-building

- Void Mothership hub, courtyard, avenues, bazaar, bridge, reactor, enchanting/blacksmith area,
  mine gate, NPCs, signs, ambient effects, and wayfinding.
- Skydeck with casino-themed NPC area, observatory, plaza, lounge, and void garden.
- The Mall and player stalls.
- Crate sanctum/stations.
- Cell corridor and Prestige Cell spaces.
- A–Z mines, wood mine, P1–P30 prestige mine chain, and `a1` Ascension mine.
- The Fabled, Infernal, Summit, and asteroid visual mine families.
- Prestige bridges and Take the Walk.
- Ascendant Sanctum and Observation Edge.
- Starfall crash site and Outpost.
- The Edge and Frozen Reach.
- Hall of Fame statues, leaderboard holograms, Soul Ledger, and story/cinematic locations.
- Self-healing/versioned builders, entity generation guards, chunk-load rebuilding, and
  protected-zone janitors.

Primary code:

- `com.yourgame.prison`
- `com.yourgame.prestige`
- `com.yourgame.halloffame`
- `com.yourgame.mall`
- `knowledge/hub-layout.md`
- `knowledge/mine-reset-perf.md`

### 4.16 Alternate themed universes

The same core plugin can run as isolated development universes selected by `theme:`. These are
separate server clones and do not alter the main live world's theme unless deliberately deployed:

- Neon District
- The Clockwork
- The Hive
- The Archive
- LEGO
- The Abyss
- The Deep
- Tidehollow
- Cloudspire/Spire variants 1–3

Each universe may supply:

- Theme-gated spawn builder.
- World dresser/reskin.
- Alternate system text and palettes.
- A small set of signature twist mechanics.
- Dedicated server folder, port, web-admin port, and launcher.
- Universe lock preventing the wrong builder from running on another server.

Primary code:

- `com.yourgame.themes`
- theme-specific dressers in `com.yourgame.prison`
- `themes/THEMES.md`
- `knowledge/themed-servers.md`
- `knowledge/new-universe-playbook.md`

### 4.17 Administration, moderation, and operations

- `/config` in-game tuning across progression, mines, enchants, crates, cases, roles,
  shops, economy, events, quests, pets, mall, item vault, alerts, and launch operations.
- Config audit logging, presets, snapshots, and a restricted desktop dashboard.
- `/adminset` offline-capable player editor.
- Rank, balance, key, item, enchant, relic, pet, role, serial, and progression grant tools.
- Live inventory/ender-chest/gear inspection.
- Player activity/audit log and owner-only private observation tools. Private moderation
  capabilities must never appear in public changelogs.
- Mod mode, vanish, ban, timeout, kick, maintenance, pause/resume, and host-event tooling.
- Launch reset/wipe with confirmation.
- Automated backups, daily restarts, welcome flow, maintenance mode, voting, chat guard,
  slur filter, Discord update helper, and config dashboard.
- Dev deploy, safe live jar-only deploy, snapshots, verification, and rollback tooling.

Primary code:

- `com.yourgame.admin`
- `com.yourgame.ops`
- `com.yourgame.webadmin`
- `com.yourgame.dev`
- `knowledge/config-admin-tools.md`
- `knowledge/launch-ops.md`
- `knowledge/config-dashboard.md`
- `knowledge/deploy-workflow.md`

### 4.18 Persistence and safety infrastructure

- Atomic asynchronous YAML writer with synchronous shutdown flushes.
- Dedicated stores for players, skills, achievements, gangs, bounties, quests, auctions,
  mall, claims, serials, housing, vaults, notifications, showcases, and other subsystems.
- PDC keys for custom item identity and persistent player/entity flags.
- Mint audit, prestige audit, activity log, config audit, and admin inspection surfaces.
- Theme locks and generation stamps.
- Chunk-lifecycle-safe decorative entities.
- Server-owned menus that cancel unsafe click/drag paths.
- Resource-pack build/sync/serve pipeline.
- Single composition root in `GamePlugin.java` with explicit service construction and wiring.

Primary code:

- `com.yourgame.data.AsyncYamlWriter`
- each system's `*Storage`, `*Config`, and `*Writer` classes
- `GamePlugin.java`

---

## 5. What Cloudspire already contains

Cloudspire is not merely a document. A development implementation exists in the current
GamePlugin and is inert unless the configured theme is `spire1`, `spire2`, or `spire3`.

### Built in source

- Three isolated development servers and launchers:
  - `server-spire1` — port 25574
  - `server-spire2` — port 25575
  - `server-spire3` — port 25576
- `StackService` with:
  - `floors.yml` registry.
  - floor states: locked/open/mapped/cleared.
  - persisted origin, positions, assigned mines, mapping totals, targets, and clear history.
  - mapping progress from block breaks on the current frontier floor.
  - mapping bossbar for players physically on that floor.
  - live clear ceremony and bells.
  - live sliced construction of the next floor.
  - relocation/regeneration of existing mines onto their assigned floors.
  - safe clean-slate command and deterministic wipe bounds.
  - asynchronous dirty saves plus synchronous shutdown flush.
- Three genuinely different layout engines:
  - `spire1`: **The Stack** — huge solid stacked landscape discs, Town of First Light,
    terrain/biomes, mine quarries, and a physical labyrinth route upward.
  - `spire2`: **The Great Spire** — one enormous literal tower with internal stacked chambers,
    windows, balconies, and a central stair/lift structure.
  - `spire3`: **The Archipelago** — wandering organic cloud islands, long rope bridges, and
    a distant column of light.
- Cloudspire atmosphere:
  - first-join `«LINK START»` ceremony.
  - rejoin shimmer.
  - sky-whale ambience and whale-song boon.
  - periodic `«EXP BOOST»` proc surge.
  - changing deck weather/ambience.
  - mine-reset `RECOMPILE` presentation.
- Theme-specific dresser and spawn/admin command.
- Universe locks preventing Cloudspire commands from affecting other universes.

### Explicitly not finished yet

- Real Guardian assault scheduling.
- Floor-specific Guardian encounter generation and boss retheming.
- Wipe/failure/retry history driven by real battles.
- Killing-blow Last Attack tracking.
- One-of-one `«LA»` serial mint and safe delivery.
- Party/raid UX designed specifically for floor assaults.
- Real action-combat/Sword Skill system.
- Floor-specific quest populations, NPC life, dungeons, and monster ecology.
- Full player-level/stat/weapon progression appropriate to an SAO-style MMO.
- A complete Deck One content pass.

The current `beginGuardianAssault` method intentionally logs that assaults are not built.
`/spire forceclear` is the temporary operator-driven substitute.

---

## 6. The strongest Prison × SAO fusion

The fusion should not be “a prison server with an SAO portal.” It should be one coherent game:

> **The tower is the prison. Mining is how the trapped players build power. Floor bosses are
> how the entire server earns its way upward.**

The two halves contribute different strengths:

| Shadow Prison contributes | SAO-style tower contributes |
|---|---|
| Proven gathering/economy loop | A world-scale reason to progress |
| Mines and resets | Resource zones on every floor |
| Ranks/prestige/ascension | Player level, floor access, and long-term mastery |
| Enchants and rare finds | Weapon passives, skills, and discoveries |
| Gangs | Guilds |
| Bounties/combat tag | Criminal/orange-player justice system |
| Crates and serialized collectibles | Boss chests, legendary drops, and Last Attack uniques |
| Quests/achievements | Floor stories and exploration |
| Pets | Familiars |
| Auction/trade/mall | Player-driven Col economy |
| Raids/events | Floor Guardian assaults |
| Scoreboards/profiles/roles | MMO identity and social status |
| `/config` and admin tooling | A tunable live-service game |
| Resource pack | Original weapons, armor, UI, mobs, sound, and world identity |

### The combined moment-to-moment loop

1. A player enters the highest unlocked floor they can survive.
2. They explore fields, gather/mine resources, hunt mobs, complete quests, and improve gear.
3. Every meaningful floor activity contributes to public **Floor Mapping**:
   mining, discoveries, quests, minibosses, and labyrinth objectives.
4. At 100% mapping, the Guardian door and assault window unlock.
5. Parties prepare food, gear, potions, relics, and roles.
6. The server fights the Floor Guardian with telegraphed mechanics.
7. Defeat records the failed assault and starts a fair recovery/cooldown cycle.
8. Victory mints the one-of-one Last Attack reward, engraves the clear, and constructs/unlocks
   the next floor.
9. Cleared floors remain alive as towns, markets, gathering regions, quests, and catch-up zones.

This preserves the prison server's satisfying “break blocks and numbers go up” rhythm while
giving every block a communal narrative purpose.

---

## 7. Transfer decisions by system

### Transfer nearly unchanged

- Atomic persistence and shutdown flush.
- Player-profile/storage patterns.
- Config loaders/writers and `/config` exact-input patterns.
- Mine definitions, generation, resets, performance budgeting, and relocation.
- Economy transaction safety and custom-item pricing.
- Claim/overflow reward delivery.
- Serial minting, ownership, auditing, and item-safety rules.
- Auction, secure trade, shop, and mall transaction engines.
- Quest, achievement, notification, scoreboard, profile, leaderboard, role, title, and
  cosmetic infrastructure.
- Resource-pack pipeline.
- Admin inspection, audit, backup, maintenance, and deploy tooling.

### Transfer mechanically, but rename/retheme

| Prison system | Fusion identity |
|---|---|
| Money | **Col** |
| Ranks A–Z | Player/adventurer progression tiers, kept as internal IDs if migration helps |
| Prestige | Mastery tiers or floor-era progression |
| Ascension | Reincarnation/New Game+ only if it fits the original lore |
| Mines | Floor quarries, crystal caverns, forests, ruins, and resource dungeons |
| Gangs | Guilds |
| Gang bank | Guild treasury |
| Bounties | Criminal/orange-player contracts |
| Outpost | Contested floor landmark |
| Pets | Familiars |
| Void Coins | Floor Tokens, Cardinal Shards, or another original earned currency |
| Shadow Shards | Cosmetic Memory Shards |
| Haste Totems | Gathering charms |
| Relics | Unique/ancient equipment |
| Enchant books | Skill records, enhancement scrolls, or weapon traits |
| Crate keys | Earned boss/dungeon keys |
| Crate station | Floor reward vault |
| Starfall | Rare floor incursion/event |
| Vote Party | Server-wide EXP/gathering event |
| Hall of Fame | Monument of the Clearers |
| Soul Ledger | Linked-player registry |

### Adapt heavily

- Custom enchants should remain as item traits, but they are not a replacement for actual
  active Sword Skills.
- Crypto/GPUs need a deliberate fiction. They could become a hidden system-layer **Memory Core**
  economy, or stay disabled; ordinary GPUs in a fantasy town would feel pasted on.
- The Void Mothership hub should become the lowest-floor city or a system lobby, not coexist
  unchanged beside the tower.
- Prestige resets need reconsideration: repeated rebirth can undercut the fantasy that everyone
  is climbing one shared death-game tower.
- The lottery should not lead the new economy. Community earned-reward draws may remain, but no
  wagers and no real-money-adjacent chance purchases.
- Existing combat enchants and raids need a real telegraph/dodge/parry framework around them.

### Do not blindly copy

- Live player data, live runtime YAML, world files, server IDs, secrets, or audit logs.
- Shadow Prison branding when the new project is intended to be a distinct game.
- Exact SAO names, characters, story, music, UI art, monsters, or logos for a public release.
- All 150 commands. Port only the player surface the new game needs; preserve admin capability
  behind a cleaner namespace.
- Any paid product that grants power.
- Any menu transformation without revalidating the physical input item on confirmation.

---

## 8. New systems required for the fusion

These are the major capabilities Shadow Prison does not already provide.

### 8.1 Action combat

- Weapon families: one-handed sword, rapier, greatsword, spear, dagger, axe, and later shields.
- Active Sword Skills with wind-up, directional motion, hit windows, recovery, cooldown, and
  cancel rules.
- Light/heavy chains, combo routes, dodge, block, parry, stagger, posture, and threat.
- Server-authoritative hit validation.
- Clear PvE telegraphs that work within Minecraft latency and movement.
- Hotbar/controller-friendly input design that does not require dozens of commands.

### 8.2 Mob and boss encounters

- Floor mob families with readable attacks, leashes, spawn ecology, loot, and level scaling.
- Elite/miniboss system.
- Guardian phase engine: arena bounds, telegraphs, mechanics, adds, enrages, wipes, and recovery.
- Participation and contribution tracking that rewards support without letting AFK players leech.
- Party roles without forcing a strict holy trinity.

### 8.3 Party and raid layer

- Party invitations, leadership, ready checks, location, shared objectives, and party UI.
- Assault roster, capacity rules, reconnect grace, spectator handling, and wipe detection.
- Fair loot policy and deterministic Last Attack attribution.
- Guild-to-party and guild-to-raid integrations.

### 8.4 Floor content framework

- Content definition per floor: biome, town, quarry/resources, mobs, quests, NPCs, labyrinth,
  miniboss, Guardian, rewards, music/ambience, and clear ceremony.
- Mapping contribution sources beyond block breaking.
- Unlock/dependency graph and replay/catch-up policy.
- Floor fast travel for cleared areas.
- Content validation so a floor cannot open missing its required components.

### 8.5 Character and gear model

- Combat level and stat model.
- Weapon proficiency/mastery.
- Equipment stats, requirements, upgrade paths, durability, and repair.
- Active skill loadout plus passive traits.
- Death/downed/recovery rules with meaningful stakes but no real-world harm or cruel account loss.
- Reconciliation with existing rank, prestige, skill, enchant, and relic data.

### 8.6 Original visual identity

- Original name and lore for the tower/world.
- Original interface language and system voice.
- Original weapons, armor, mobs, bosses, icons, sounds, and music.
- Resource-pack models/animations compatible with performance targets.
- A reusable floor art kit so new content does not require rebuilding every asset from scratch.

---

## 9. Recommended first playable release

Do not begin with 100 shallow floors. Begin with one complete floor and prove the game.

### Vertical slice: Floor One

- Town of First Light or an original renamed starting city.
- One large explorable landscape.
- Three resource zones using relocated prison mines.
- Three mob families and one elite.
- One short quest chain that teaches gathering, combat, gear, party, and trade.
- Three weapon families with two polished active skills each.
- Basic party system.
- One labyrinth.
- One Floor Guardian with three readable phases.
- Floor Mapping from mining, quests, exploration, elite kill, and labyrinth completion.
- Assault failure/retry and full clear ceremony.
- One audited Last Attack serial reward.
- Construction/unlock preview of Floor Two.
- Col economy, NPC shops, auction/trade, profile, scoreboard, cosmetics, and admin tuning.

### Success test

The slice is successful if players enjoy spending time on Floor One even before Floor Two
exists. If Floor One is only a waiting room for the boss, the foundation is wrong.

---

## 10. Suggested new-project layout

When the new folder exists, start with this shape:

```text
<new-project>/
  AGENTS.md
  README.md
  docs/
    PRISON-SERVER-MASTER-MAP.md
    FUSION-VISION.md
    FLOOR-CONTENT-SCHEMA.md
    COMBAT-DESIGN.md
    MIGRATION-LEDGER.md
  plugin/
    build.gradle.kts
    src/main/java/<new-namespace>/
    src/main/resources/
  resource-pack/
  server-dev/
  scripts/
```

Recommended migration method:

1. Create a fresh Paper plugin and new Java namespace.
2. Copy this document and the relevant knowledge notes first.
3. Define the new profile/currency/progression vocabulary before porting player data classes.
4. Port infrastructure in small modules, preserving tests and safety invariants.
5. Port content definitions only after the new IDs and schema are settled.
6. Keep Cloudspire as a reference/prototype until the new project reproduces a feature.
7. Never delete or dismantle Shadow Prison during the migration.
8. Track every old class/config as **ported**, **adapted**, **replaced**, **parked**, or
   **rejected** in `MIGRATION-LEDGER.md`.

---

## 11. Practical recommendation

Use **Cloudspire as the prototype** and the new folder as the clean product.

- Shadow Prison remains safe and playable.
- Cloudspire proves world geometry, mapping, mine relocation, and clear-to-build spectacle.
- The new project receives selected, understood systems instead of a 457-file copy with old
  names and hidden coupling.
- Once the Floor One vertical slice works in the new project, decide whether to migrate existing
  player identities/items or launch it as a sibling server.

The central design is already strong enough to carry a game:

> **You are imprisoned inside a world-sized tower. You mine and fight to grow stronger.
> Every player contributes to discovering the floor. The whole server faces its Guardian.
> Victory physically creates the road upward.**

