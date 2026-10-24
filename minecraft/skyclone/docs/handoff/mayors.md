# mayors — hand-off

Mayors, the yearly election, every mayor perk, and the calendar's festivals: Spooky Festival, New Year
Celebration, Traveling Zoo, Season of Gerald + the **Winter Workshop island** (`jerrys_workshop`), Dark
Auction, Mythological Ritual, Stonks Auction and Gerald Sightings. Provides `Features.Mayors`.

Data is generated: `py -3 tools/gen/mayors/gen_mayors.py` writes `resources/data/mayors/{mayors,items,recipes,
mobs,spawns,events}.json` and `docs/names/mayors.md` from research/data (neu-repo shops, gift tables, mob stats)
and research/05. Edit the script, not the JSON.

## What works (verified how)
`bash tools/check.sh mayors` = CHECK OK. The pure checks (`MayorsChecks`: roster, election math, spooky brackets,
zoo/DA prices) were run offline against the real JSON with a scratch harness — all pass — and are both live
self-tests and JUnit (`plugin/src/test/java/com/skyclone/mod/mayors/MayorsPureTest.java`, compiles against
junit; not run under gradle by me). The island was checked with `tools/preview.sh` (RESULT OK, 0 unreachable
anchors, 0 floating components; images looked at). **Nothing here has run on a live server yet.**

- **Election** (research/05 §8, `ElectionService`, `ElectionMath`): state is derived from `Sky.calendar()` every
  second. Booth opens **Late Summer 27**, closes **Late Spring 27** (279 SB days = 93 real h); the winner governs
  the next SB year. 5 candidates, regulars drawn excluding the sitting mayor and minister, each running with a
  random 1–4 of their perks with exactly one minister perk (the ballot always carries one minister-eligible perk; the Ritual, Long Game, Grand Feast and Fishing Festival are mayor-only - see 2026-10-06);
  special mayor every 8 years rotating Corvane(Scorpius) → Bonko(Derpy) → Gerald(Jerry) (496 = Gerald, 504 =
  Corvane, 512 = Bonko). 2nd place = Minister with one perk; no minister when a special wins. Long Game (Diaz LTI)
  carries the minister onto the next ballot with all perks. Tie-break and every roll are seeded
  (`mayors.seed`), so restarts/recounts agree. Votes per account, changeable while open, weight dial ×2 with
  Artifact of Control x Fame Rank votes (wave 3), stored in `mayors_votes`; townsfolk votes (`mayors.npc-votes`, 25) keep the % bars alive
  on a small server. If the server was off through a whole election, the result is rolled. Fresh server: a
  mayor is installed at once and election 0 is open from day 1. Results broadcast in a genre box; history of
  12 kept. Kickback (Scorpius bribe) pays his voters 50k–1M by SkyBlock level.
- **Perks**: `perkActive/perkValue` take the id or any alias (case-insensitive). Mayor + minister + Perk Parade
  (Gerald: every 18 SB days another regular mayor's full set, Diaz excluded) + admin forces − `mayors.disabled-perks`.
  Values can be overridden live (`mayors.perk-values`). Implemented here: Mining XP Buff +60 / Fishing XP Buff
  +50 wisdom on public islands, Deep Luck +15 SCC, Twin Lines +0.1 DHC per SCC, (Lucky Paws removed 2026-10-06: replaced by Huntress' Intuition, hunting's),
  Stat Surge +10% all combat/misc stats (StatProvider `mayors`, shows as "Mayor Perks"), MORE SKILLS +50% skill XP
  (`SkillXpGain` at NORMAL), BEEFY MOBS ×2 sky-mob health, Arcane Spark +15% magic damage and −15% mana,
  Scholar's Boon +15 alchemy/enchanting wisdom, plus every event perk below.
- **Menus**: Election booth (`/election`, SkyBlock Menu → Election via menus' FeatureIndex) with candidate heads,
  perk lore, live % bars, confirm-to-vote; Mayor office (`/mayor`) with active perks, minister and Perk Parade
  countdown; Election History; Festivals & Events board (`/festivals`) linking every event below.
- **Spooky Festival** (Autumn 29–31): 10% candy per kill × (1 + boosts: spooky/bat_person sets +5%, candy
  accessories up to +20%, Bat pet up to +20%, Sugar Rush +30%) × dial; 1 in 10 Purple; points 1/5 counted from
  natural candy gains; Trick-or-Treat chests 1%/3% (treat = candy, trick = a Wraith); 6 spooky night mobs
  (shielded: health in hits) in hub `graveyard`/`wilderness` via spawn rules `event:spooky_festival`; Rider's
  Candle summons the Headless Rider in the graveyard at night; Dread Peddler shop = the exact neu-repo Fear
  Mongerer table (21 lines, unavailable ids hidden); leaderboard; brackets Top 50/Emerald/Diamond/Gold/Iron
  (+Stone/Wood TUNE) pay coins, items (WITCH_MASK, Vampirism books, Skeleton Horse) and one-time SkyBlock XP;
  everyone who scored gets a dated Spooky Pie. Scores persist in `mayors_spooky`. Encore (Foxy) schedules an
  extra festival in month `mayors.spooky.extra-month`; Open Hands drops Party Chests.
- **New Year** (Late Winter 29–31): one free cake per SB year, stamped with the year (lore + `extra.year`);
  Cake Bag for 250,000 if accessories registers it.
- **Traveling Zoo** (Early Summer 1–3, Early Winter 1–3): 1 Legendary on the 6-step rotation + 2 random
  COMMON–EPIC, exact neu-repo Oringo prices (Blue Whale L = 10M + 8 Enchanted Cooked Cod), preview of the next
  three legendaries.
- **Dark Auction** (every real hour at :55): 400,000 purse to enter; 60 s lobby; per lot 5 s preview + 10 s
  window reset by every bid; rounds 1–4 general pool, round 5 a book; Shadow Lots (Scorpius) → 7 rounds with the
  darker table. Top bid is escrowed (refunded when outbid / on shutdown); winner's lot carries
  `extra.winning_bid` (lore line shows it). Live menu refreshes twice a second.
- **Mythological Ritual** (while `diana.mythological_ritual`): calendar event `mythological_ritual` is forced on
  exactly then. Huntress sells Griffin + Ancestral Spade; spade right-click (Echo) reveals a chain of burrows
  (4 / 6 / 8 by spade, +2 Erudite) on open ground in the Hub (not SAFE zones) and draws a trail; digging
  (right/left click within 3 blocks) gives treasure or a creature. Since wave 5 the Griffin's rarity (Common ..
  Mythic) opens the twelve creatures two at a time and picks their variant - see "Wave 5". Creatures use the
  reference wiki's levels/HP/coins/XP/drops, owned by the digger. Crown of Greed doubles burrow coins.
- **Winter Workshop** (`jerrys_workshop`, `WinterWorkshopGenerator` v2, see Wave 3): snowy island, decorated great tree
  plaza (SAFE) with the hub portal, Gerald's Workshop, Ingvar's Emporium, Marnie's stall, Festival Hall, frozen
  pond (area `pond` for fishing), Mount Gerald. Warps `jerrys_workshop` and `winter` (requirement
  `mayors:workshop`; open all year by default). **Season of Gerald** (Late Winter 24): 5 rounds of lava raiders
  walking to the workshop door; Gift Thieves only take Packed Snowball damage and steal gifts on arrival;
  each round pays Red/Green/White gifts by damage rank + North Stars; 3+ steals = no Red. Gifts open by
  right-clicking another player (both get loot — neu-repo gift tables + coins) or sneak-right-click alone (dial).
  Ingvar sells for North Stars, Marnie for coins (neu-repo tables; missing ids hidden).
- **Gerald Sightings** (`jerry.jerrypocalypse`): 0.4% per Gather/MobKill reveals a hidden Gerald (neu-repo odds
  54.55/27.27/13.64/4.55); its box gives coins, skill XP, Gerald talismans.
- **Stonks Auction** (`diaz.stock_exchange`): bid ≥ 2,000,000 per SB month (escrowed, raise only); settles at month
  end into Shares of Stonks (1 per 2M, min 1).
- **Rewards owed to offline players** queue in `mayors_claims` and arrive on their next ProfileReady.
- **NPCs**: hub `npc.election` (Ballot Clerk Tamsin), `npc.dark_auction` (Ashgrave). Winter Workshop: Saint
  Gerald, Ingvar, Marnie, and the Festival Hall stalls of the 5 seasonal vendors (Dread Peddler, Baker Haverly,
  Zebulon the Keeper, Huntress Orla, Broker Penhallow). While their event runs each vendor ALSO appears in the
  hub: at `npc.<vendor>` if the hub has that anchor, otherwise lined up inside the `election_room` area.
- Sidebar section `mayors` (priority 400, 2 lines, only during DA/siege/burrow chain/spooky); tab widget with
  mayor, minister, Perk Parade.

### Perk ids (the contract for other modules — use `Sky.service(Features.Mayors.class)`)
"applied by" = who must read it. **mayors (done)** = already applied here; do NOT re-apply it.

| perk id | aliases | value | our name (mayor) | applied by |
|---|---|---|---|---|
| `aatrox.slashed_pricing` | `aatrox.slayer_discount` | 50 | Cut-Rate Contracts (Vexmar) | slayer |
| `aatrox.slayer_xp_buff` | `aatrox.slayer_xp` | 25 | Slayer XP Buff (Vexmar) | slayer |
| `aatrox.pathfinder` | `aatrox.rare_drops` | 20 | Trailblazer (Vexmar) | slayer |
| `cole.prospection` |  | 25 | Deep Survey (Bram) | minions |
| `cole.mining_xp_buff` | `cole.mining_xp` | 60 | Mining XP Buff (Bram) | **mayors (done)** |
| `cole.mining_fiesta` | `cole.fiesta`, `mining_fiesta` | 25 | Mining Fiesta (Bram) - whole term, also as minister; calendar `mining_fiesta` driven here | mining (effects) |
| `cole.molten_forge` | `cole.forge` | 25 | Hot Anvils (Bram) | mining |
| `diana.huntress_intuition` | `diana.tracking` | 10 | Huntress' Intuition (Orla) - +10 Tracking, her minister perk | **hunting (not wired yet)** |
| `diana.mythological_ritual` | `diana.mythological`, `diana.ritual` | 1 | Mythological Ritual (Orla) | **mayors (done)** |
| `diana.pet_xp_buff` | `diana.pet_xp` | 35 | Pet XP Buff (Orla) | pets (already reads it) |
| `diana.sharing_is_caring` | `diana.exp_share` | 2 | Shared Bowls (Orla) | pets (already reads it) |
| `diaz.long_term_investment` | `diaz.lti` | 1 | Long Game (Mercer) | **mayors (done)** |
| `diaz.volume_trading` | `diaz.shens` | 2 | Bulk Lots (Mercer) | auction (Shen-style yearly auction) |
| `diaz.stock_exchange` | `diaz.stonks` | 1 | Share Floor (Mercer) | **mayors (done)** |
| `diaz.shopping_spree` | `diaz.npc_limits` | 10 | Shopping Spree (Mercer) | shops |
| `finnegan.pest_eradicator` | `finnegan.pests` | 20 | Pest Patrol (Wystan) | garden |
| `finnegan.goated` | `finnegan.contest_brackets` | 10 | Top of the Field (Wystan) | farming |
| `finnegan.blooming_business` | `finnegan.visitors` | 10 | Busy Gates (Wystan) | garden |
| `finnegan.grand_feast` | `finnegan.feast` | 1 | Year-Long Harvest (Wystan) | farming |
| `foxy.sweet_benevolence` | `foxy.candy` | 30 | Sugar Rush (Pip) | **mayors (done)** (candy, gifts); chocolate if anyone builds it |
| `foxy.extra_event` | `foxy.extra_spooky`, `foxy.extra_festival` | 1 | Encore (Pip) - extra Fishing Festival / Mining Fiesta / Spooky Festival, variant rolled per election | **mayors (done)** + fishing ORs `fishing_festival_extra` |
| `foxy.a_time_for_giving` | `foxy.party_chests` | 1 | Open Hands (Pip) | **mayors (done)** |
| `foxy.chivalrous_carnival` | `foxy.carnival` | 1 | Fairground (Pip) | **mayors (done)** (the Carnival, wave 3) |
| `marina.fishing_xp_buff` | `marina.fishing_xp` | 50 | Fishing XP Buff (Coralie) | **mayors (done)** |
| `marina.luck_of_the_sea` | `marina.scc` | 15 | Deep Luck (Coralie) | **mayors (done)** |
| `marina.fishing_festival` | `marina.festival` | 1 | Fishing Festival (Coralie) | fishing |
| `marina.double_trouble` | `marina.double_hook` | 0.1 | Twin Lines (Coralie) | **mayors (done)** |
| `paul.marauder` | `paul.chest_discount` | 20 | Plunderer (Osric) | dungeons |
| `paul.ezpz` | `paul.score` | 10 | Easy Street (Osric) | dungeons |
| `paul.benediction` | `paul.blessings` | 25 | Benediction (Osric) | dungeons |
| `barry.arcane_catalyst` | `barry.spells` | 15 | Arcane Spark (Leofric) | **mayors (done)** |
| `barry.astral_negotiator` | `barry.enchant_cost` | 15 | Star Bargain (Leofric) | enchanting |
| `barry.magic_xp_boost` | `barry.magic_xp` | 15 | Scholar's Boon (Leofric) | **mayors (done)** |
| `scorpius.bribe` | `scorpius.bribes` | 1 | Kickback (Corvane) | **mayors (done)** |
| `scorpius.darker_auctions` | `scorpius.dark_auction` | 7 | Shadow Lots (Corvane) | **mayors (done)** |
| `derpy.quad_taxes` | `derpy.taxes` | 4 | QUADRUPLE TAX!!! (Bonko) | auction + bazaar (multiply taxes by the value) |
| `derpy.turbo_minions` | `derpy.minions` | 2 | MINION OVERDRIVE!!! (Bonko) | minions (output ×value) |
| `derpy.double_mobs_hp` | `derpy.mob_hp` | 2 | BEEFY MOBS!!! (Bonko) | **mayors (done)** |
| `derpy.moar_skillz` | `derpy.skill_xp` | 50 | MORE SKILLS!!! (Bonko) | **mayors (done)** |
| `jerry.perkpocalypse` | `jerry.perk_rotation` | 18 | Perk Parade (Gerald) | **mayors (done)** |
| `jerry.statspocalypse` | `jerry.stats` | 10 | Stat Surge (Gerald) | **mayors (done)** |
| `jerry.jerrypocalypse` | `jerry.hidden` | 1 | Gerald Sightings (Gerald) | **mayors (done)** |

Values are percentages unless obvious (slots, multipliers). `perkValue` returns 0 when inactive.

### Calendar event ids (owned here)
`spooky_festival` (Autumn 29, 3 d) · `spooky_festival_extra` (forced by Encore) · `new_year_celebration`
(Late Winter 29, 3 d) · `traveling_zoo` (Early Summer 1, 3 d) · `traveling_zoo_winter` (Early Winter 1, 3 d) ·
`season_of_jerry` (Late Winter 24, 3 d) · `jerrys_workshop_open` (all Late Winter) · `dark_auction` (real-time,
hourly :55, 5 min) · `election_booth` (Late Summer 27, 279 d) · `mayor_term_start` (Late Spring 27) ·
`mythological_ritual` (forced exactly while the ritual perk is on; armor reads it) · `carnival` (forced exactly while
the Fairground perk is on) · `mining_fiesta` (forced all term while `cole.mining_fiesta` is on, or Foxy's mining Extra
Event window) · `fishing_festival_extra` (Foxy's fishing Extra Event window). NOT mine: `fishing_festival`, farming contests — their owners schedule them and gate them on the perk ids above.

### Requirement prefix
`mayors:workshop` (Winter Workshop open) · `mayors:carnival` (the Carnival is open) ·
`mayors:carnival_perk:<perkId>[:<tier>]` (the player owns at least that tier of a Carnival Perk - wave 5; fishing /
mining / garden / hunting read their perks through it) · `mayors:perk:<id>` · `mayors:mayor:<mayorId>` ·
`mayors:alert:<calendarEventId>[:remind|start]` (would this player get a calendar chat line for it - Wave 4).

### Member data `mayors` (read-only for others)
`lastCakeYear, giftsGiven, giftsOpened, burrowsDug, mythKills, geraldsFound, siegeRounds, auctionsWon,
auctionCoinsSpent` — progression can read these for SkyBlock XP tasks. Carnival (wave 5): `carnivalTokens, carnivalGames,
carnivalBest, carnivalPerks{perkId: tier}, carnivalGoalsDone, carnivalPlays{FRUIT|SHOOT|FISH}, carnivalBestScores`.

## What is partial / not built
*Rewritten Wave 5 (2026-10-06). Everything below that used to be a behaviour gap is built (see "Wave 5"); what is left
is either a number the research does not give (TUNE) or a system another module owns.*
- **Not run on a live server**: the physical Carnival booths (block changes, per-player entities, the firing stand and
  dock locks, hooking a tropical fish), the staged siege waves, the Sphinx riddle menu, the Griffin rule, the four new
  mask abilities. NPC placement, siege pathing (`Mob#getPathfinder().moveTo`) and magma-cube hopping, snowball hits and
  the Dark Auction menu refresh are still code-reviewed only.
- **TUNE numbers the research does not give**: Carnival booth pacing (zombie/fish/lamp spawn rhythm; the points,
  costs, tokens and goals ARE the reference's); siege attacker stats, wave composition and the 4-waves-a-round split
  (`mayors.siege.waves` / `wave-gap-seconds`); gift coin and skill-XP ranges; Archaic/Deific treasure scaling and the
  Braided Griffin Feather rate; what Tracking does on burrows (+1% weight per point for the Legendary-and-up creatures);
  the Zombie Mask's "undead armor" word list (dial); MythMechanics' per-stack constants (axe 8 s / 1.5x, lightning
  fractions, sting timing, harpy leap) and HP of variants the wiki does not list; Stonks ranking (linear, 1 share per
  2M); Spooky Stone/Wood brackets and coins below Iron; candy talisman %.
- **Owned elsewhere**: the yearly vault auction (auction, "Vault Auction"); Winter sea creatures, Glacial Cave drops,
  Einary's quests (fishing / others); the Carnival Perks for shark, powder, titanium, seasoning, pest, flask and Spooky
  sea creatures (they read `mayors:carnival_perk:<id>:<tier>` - see Requests); the Golden Bounty Stonks trade needs
  reforges to register `GOLDEN_BOUNTY` (3 of Liz's 4 trades exist now).
- Votes by Fame Rank need the bank module (without it every ballot is 1 vote, + badges, x2 Artifact of Control).

## Commands added (player + admin)
Player: `/carnival` (`/fruitdigging`, `/carnivaltickets`) · `/election` (`/vote`, `/electionbooth`) · `/mayor` (`/mayors`, `/perks`) · `/festivals` (`/eventboard`,
`/mayorevents`) · `/spooky` (`/spookyfestival`, `/candyshop`) · `/newyear` (`/newyearcake`, `/baker`) · `/zoo`
(`/travelingzoo`) · `/darkauction` (`/da`) · `/mythology` (`/ritual`, `/burrows`) · `/winter` (`/workshop`,
`/savethejerrys`, `/jerrysworkshop`) · `/stonks` (`/stockexchange`) · `/eventalerts [important|all|off]`
(`/calendaralerts`, `/calalerts`, `/eventnotify`; also the bell in Festivals & Events slot 41). Warps: `/warp jerrys_workshop`, `/warp winter`.
Menu path: SkyBlock Menu → Election → Festivals & Events → every event.

Admin (`/sb mayors ...`, all RCON-friendly):
`status` · `perks` (every perk, value, owner, on/off) · `set <mayor> [minister]` · `perk <id> on|off|clear` ·
`election open|reroll|resolve` · `vote <player> <mayor>` · `event <calendarId> on|off` · `da start|skip` ·
`spooky start|end|points <player> <n>` · `cake <player> [year]` · `zoo` · `myth tier|dig|spawn <player> [kind] [variant 1-6]`
(kinds: hunter lynx nymph bull harpy gaia minotaur champion sphinx inquisitor manticore minos; `tier` prints the
variant, Tracking and whether a Griffin is out) ·
`siege start|skip|stop` · `gift <player> WHITE|GREEN|RED [n]` · `gerald <player>` · `stonks` (settle now) ·
`carnival tokens|tickets|reset <player> [n]` (open the Carnival with `perk foxy.chivalrous_carnival on`) ·
`claims <player>` · `npcs` · `alerts <player> [important|all|off]` (set the scope, then list remind=/start= for every
calendar event and say which module is announcing).

Quick test script: `sb mayors status` → `sb mayors set diana cole` → `sb selftest mayors` →
`sb mayors spooky start` → `sb mayors spooky points Tutut 2500` → `sb mayors spooky end` → `sb mayors da start` →
`sb mayors siege start` (then a player on the island) → `sb mayors set jerry` → `sb mayors gerald Tutut`.

## Dials added
`mayors.seed` 517 · `special-every` 8 · `include-retired` false · `npc-votes` 25 · `vote-weight` 1 · `fame-votes` true ·
`vote-requirement` always · `mayor-all-perks` false · `disabled-perks` "" · `perk-values` "" · `bribe-min` 50k ·
`bribe-max` 1M · `bribe-per-level` 5k (TUNE) · `perk-parade-days` 18 · `private-islands` private_island,garden ·
`sidebar` true · `spooky.candy-scale` 1 · `spooky.threshold-scale` 1 · `spooky.extra-month` 5 ·
`newyear.free-cake` true · `da.min-bid-scale` 1 · `da.entry-purse` 400k · `da.remote-join` true ·
`da.bid-seconds` 10 · `da.lobby-seconds` 60 · `myth.require-griffin` true · `myth.mob-chance` 0.75 (reference) ·
`myth.hp-scale` 1 · `workshop.always-open` true · `siege.break-seconds` 120 · `siege.round-seconds` 600 ·
`siege.hp-scale` 1 · `gifts.self-open` true (TUNE: research requires giving) · `jerry.chance` 0.004 (TUNE) ·
`stonks.min-bid` 2M · wave 5: `myth.griffin-to-hurt` true · `siege.waves` 4 (TUNE) · `siege.wave-gap-seconds` 60 (TUNE) ·
`carnival.undead-armor` "ZOMBIE,SKELETON,REVENANT,REAPER,ROTTEN,SKELETOR,UNDEAD" (TUNE) · `carnival.parrot-range` 20 ·
`carnival.feast-events` "harvest_feast,finnegan.grand_feast" ·
`alerts.takeover` true · `alerts.important` (festival/election ids) · `alerts.owner-announced` ·
`alerts.perk-gates` fishing_festival=marina.fishing_festival · `alerts.da-near` 0.5. (`spooky.extra-month` and
`alerts.takeover` take effect live; the others are read at use time.)

## Self-tests added
`mayors.roster` (ids, prefixes, specials, every promised id resolves) · `mayors.election-math` (279-day/93 h window,
496/504/512/520 specials, term/booth boundaries, 30 ballots: 5 distinct candidates, exclusions, 1–4 perks, minister
on ballot, Long Game carry) · `mayors.brackets` (5000/#1 = Top 50, #60 = Emerald, 2000 = Diamond 500k, 1999 = Gold,
1500 = Iron) · `mayors.prices` (Blue Whale L = 10M + 8 E. Cooked Cod, Lion C = 10k, DA 400k/5 rounds/10 s, Midas
100k, candy 10%/1/5) · `mayors.service` (a mayor is installed, active perks have values) · `mayors.items-exist` ·
`mayors.mobs-exist` · `mayors.myth-mechanics` · `mayors.fame-votes` · `mayors.carnival` · `mayors.alerts` (who hears which calendar line;
live half: exactly one announcer, every `alerts.important` id is registered) · `mayors.shops-stocked` (Dread Peddler, DA lots/books, zoo, Ingvar have existing goods) ·
wave 5: `mayors.myth-rules` (chains 4/6/8 +2 Erudite max 10, two creatures per Griffin rarity at 1000/800/600/400/75/15,
Tracking tilts only the rare ones, first burrow treasure, Crown of Greed, the Griffin rule, every riddle) ·
`mayors.siege-rules` (waves keep every attacker and the boss last, early next wave, extra gifts, Red Fad, gift scaling,
gifts give skill XP) · `mayors.masks` (Party Flock tiers, undead matching, the Frog region holds a day and moves,
Bee curve, hooves + Trick or Treat Enthusiast, Purple Vogue) · `mayors.carnival-grounds` (booth anchors/areas/tools on
the built island) · `mayors.mobs-exist` now checks every pool creature in that rarity's variant ·
`mayors.workshop-anchors` (now also the carnival troupe, `npc.ice_carver`, `npc.frozen_trader`) · `mayors.hub-anchors` (`npc.election`, `npc.dark_auction`, `election_room`,
`graveyard`) · `mayors.calendar` (events registered; ritual state follows the perk).

## Requests (things you need from core or another module — the integrator reads this)
1. **hub**: please add `portal.jerrys_workshop` (warp id `jerrys_workshop` is registered) — today the workshop is
   reachable only by `/warp jerrys_workshop`, `/winter` and the Saint Gerald menu. Optional anchors
   `npc.fear_mongerer`, `npc.baker`, `npc.zoo`, `npc.mythology`, `npc.stonks` in the village; without them those
   vendors stand inside `election_room` during their events (so please make that room roomy, ≥ 11 blocks long).
   Areas `graveyard` and `wilderness` must exist with real y ranges (spooky spawn rules + candle).
2. **slayer / minions / mining / fishing / farming / garden / dungeons / shops / auction / bazaar / enchanting**:
   read your rows of the perk table above. In particular `derpy.quad_taxes` (auction & bazaar),
   `derpy.turbo_minions` + `cole.prospection` (minions), `cole.mining_fiesta` + `cole.molten_forge` (mining),
   `marina.fishing_festival` (fishing), `aatrox.*` (slayer), `paul.*` (dungeons). Don't re-apply wisdom/SCC/pet
   luck perks marked done.
3. **weapons**: Midas' Sword / Midas Staff "Greed" can read `ItemData.extra.winning_bid` (the coins paid at the
   Dark Auction) — same key as the reference game.
4. **pets**: your dials already default to `diana.pet_xp_buff` / `diana.sharing_is_caring` — those are the ids.
   Griffin tier is read from `activeType` ("GRIFFIN") + `activeRarity` (name or index).
5. **armor**: calendar id `mythological_ritual` exists and is active exactly while the ritual runs; creatures are
   family `mythological` and flagged `mythological`.
6. **progression**: SkyBlock XP for spooky brackets is awarded via `SkyLevel.award("spooky_bracket:<id>")`;
   the New Year *Century* cake event (`progression_new_year`) is yours and separate from our dated New Year Cake.
7. **fishing**: area `pond` on `jerrys_workshop` is a frozen pond with a 3×3 hole for winter fishing.
8. **accessories**: Candy talisman line, Bat items, Gift talismans, `NEW_YEAR_CAKE_BAG`, `ARTIFACT_OF_CONTROL`,
   the Gerald talismans are referenced by id (shops/boosts/vote weight); hidden if absent.

9. **hub**: during the Carnival (`Sky.calendar().active("carnival")`, or perk `foxy.chivalrous_carnival`) Barker
   Pip and Gamesmaster Lulu still say "the games aren't running today". Please switch their lines while it is
   active (e.g. "Talk to the Ringmaster beside me!"). Our troupe stands 2-3 blocks beside `npc.carnival_barker` /
   `npc.carnival_games`, so please keep two or three free standing blocks next to each of those anchors (2 by the barker, 3 by the games).
10. **accessories**: `CARNIVAL_MASK_BAG` (yours, `hook: events`) - its +1 Intelligence per different mask is applied
    HERE (StatProvider `mayors_masks`); do not apply it again. The masks are ours (`*_MASK`, `extra.carnivalMask`).
11. **shops**: your `npc.ice_carver` (by Gerald's Pond) and `npc.frozen_trader` (inside the new Glacial Cave in
    Mount Gerald) anchors now exist on `jerrys_workshop`. `npc.star_trader` / `npc.frost_vendor` are deliberately
    NOT built: Ingvar (North Stars) and Marnie (coins) already sell those tables, so they would be duplicates.
12. **weapons**: `HILT_OF_REVELATIONS` now drops from the Minos Hunter (0.3/0.4/0.5% by griffin tier, TUNE: neu-repo
    gives the source, not the rate); `DAEDALUS_STICK` drops from the Minotaur. `GLACIAL_FRAGMENT` is still unsourced
    (the reference mines it in the Glacial Cave - the cave exists now; mining/fishing owners decide the drop).
13. **bank**: fame votes are read through your `fame:<n>` requirement and `data/bank/fame.json` (`fame`/`votes`
    keys) - nothing touches your member data. Please keep both stable.
14. **(worked around from the mayors side in Wave 4 - see Request 15)** **menus** (wave-3 playtest, "calendar chat noise"): `MenusListener` sends "[Calendar] X starts in 4m 43s!" and
    "[Calendar] X has started!" to EVERY ready player for EVERY calendar event, and our `dark_auction` is a
    real-time event (`intervalMinutes = 60`), so every player gets two calendar lines every real hour on top of
    our own invitation. Mayors now invites only players who can enter (see the fix below), so the calendar lines
    are the remaining hourly noise and they are yours to change. Suggested root fix: skip both the reminder and
    the start line for real-time recurring events (`CalendarEvent.intervalMinutes > 0`) - they recur too often to
    announce and their owner announces to the players who can act - or make that a separate per-player setting
    defaulting off. Please keep `dark_auction` listed in the calendar menu and tab widget. Until then an agent
    can silence them per player with the settings `notify.event_reminders` / `notify.event_start`.
15. **menus** (Wave 4, to retire the takeover): mayors now sends the calendar chat itself and turns your dial
    `menus.event-reminders` off (see Wave 4). To take the job back cleanly: (a) add a Settings entry
    `notify.event_scope` (choice `important` / `all` / `off`, default `important`, reader `mayors`, Notifications
    category) - mayors already reads and writes `skyclone:setting.notify.event_scope` on the PDC, so your
    adopt-from-PDC rule keeps the two in step with no migration; (b) in `MenusListener.onEventStart` and
    `reminderTick`, skip a player when `Sky.has(Features.Mayors.class) && !Sky.requirements().check(p,
    "mayors:alert:" + eventId + ":start")` (`:remind` for the reminder) - guard on `Sky.has` because an unknown
    prefix fails closed and would silence everything without mayors; (c) for `dark_auction` reminders, mayors'
    line adds "Use /darkauction" or "you are N coins short" - keep sending ours by leaving takeover on, or copy
    that wording. Then `/sb dial set mayors.alerts.takeover false` hands the announcer back (it turns your dial on).

16. **reforges** (wave 5): Erudite now works - a spade carrying `ItemData.reforge = "erudite"` reveals 2 more burrows a
    chain (max 10); `ANCESTRAL_SPADE` lost its `no_reforge` tag and `ARCHAIC_SPADE` / `DEIFIC_SPADE` are registered
    here (your erudite `itemIds` lists all three). Please drop Erudite from your lore-only list. Also: `GOLDEN_BOUNTY`
    (a reforge stone) is Liz's fourth Stonks trade; it shows as soon as you register it.
17. **accessories** (wave 5): now applied HERE - Voter's Badges (+3/6/9/12 votes), Lucky / Eternal Hoof (+1% / +2%
    trick-or-treat chests, the better one), the five gift talismans (+5..25% coins and skill XP from gifts opened, the
    best one), the Snow set (+5% extra-gift chance a piece in the siege), Golden Fleece (+15 Tracking on burrows; its
    Magic Find half stays yours). Please move them to your "applied by their owners" list.
18. **pets** (wave 5): read here - `bat_candy_lover` (candy %, falls back to the research's level-scaled figure when you
    report 0), `reindeer_snow_power` (extra-gift chance %), Griffin Mythic "Old Earth" (+1 Tracking per burrow dug in
    the current chain; computed from activeType/activeRarity, the ability has no number). `griffin_odyssey` is the
    rarity -> creature/variant rule itself. Your dials `pets.share-perk` / `pets.xp-perk` default to the right ids
    (`diana.sharing_is_caring`, `diana.pet_xp_buff`) - confirmed. `seal_showboater` is not ours (Year of the Seal).
19. **fishing / mining / garden / farming / hunting** (wave 5): Carnival Perks you apply, read with
    `Sky.requirements().check(p, "mayors:carnival_perk:<id>:<tier>")` (highest tier that passes): fishing
    `spooky_hook` (+1% Spooky sea creature chance a tier, 5), `shark_sonar` (+1% shark chance, 5), `denture_collector`
    (+10% teeth, 3), `elasmobranchii_affinity` (+50% extra fin chance, 2); mining `refined_mind` (+2% Refined Mineral /
    Glossy Gemstone, 5), `powder_hoarder` (+2% powder, 3), `titanium_tenacity` (+3% titanium, 2); garden/farming
    `natural_talent` (+1% seasoning, 5), `feast_crashers` (+2 bonus pest chance, 3), `leftovers` (+1 Feast Flask, 2).
    Applied here already: candy, purple, trick-or-treat, siege gifts, Red Gifts, gift coins/XP, Icy Hook SCC, the
    festival Fishing Wisdom / Mining Fortune / Farming Fortune, Storied Stinger damage, Deadly Greed, Huntress's Favor
    MF, Elusive Hunter Tracking.
20. **hunting** (wave 5): we add your `attributeLevel(p, "stat:tracking")` to Tracking on burrows. If your Griffin-
    burrow-only Tracking attribute has a condition only we can answer, add a `stat:` form for it (e.g.
    `stat:tracking_burrow`) and we will read it.

## Known risks
0. **Wave 5 untested live**: the Carnival booths drive per-player block changes and hidden entities (shown only to the
   player) - a client that misses a `showEntity` would see empty lanes; the Griffin rule cancels damage from anyone
   without a Griffin out (admins testing creatures: `/sb dial set mayors.myth.griffin-to-hurt false`); siege rounds now
   last until every wave is down (or `siege.round-seconds`), so a round is minutes, not one burst.
1. **Untested live**: siege mob AI (pathfinder to `siege.target`, magma cube hopping by velocity), snowball
   damage routing (thieves cancel any hit that is not our snowball raw hit), and `spawnAt` copies of island NPCs
   onto the hub are the likeliest things to misbehave on first boot.
2. **Calendar forcing**: `spooky_festival_extra` and `mythological_ritual` are driven by `Sky.calendar().force()`
   every second, so a manual `/sb calendar force` of those two ids is overridden — use `/sb mayors perk ...` instead.
3. **Economy scale**: research-exact Dark Auction minimums (10M–100M) and Spooky thresholds (5,000 points) are
   far beyond a new server; `mayors.da.min-bid-scale` and `mayors.spooky.threshold-scale` are the knobs.

## Wave 3 (2026-10-04)
All compiled (`bash tools/check.sh mayors` = CHECK OK). The new pure checks (`mythMechanics`, `fameVotes`,
`carnival`, which also plays scripted Fruit Digging, Zombie Shootout and Catch a Fish rounds) and the old ones (`brackets`, `prices`) were run offline against the real JSON with a scratch harness -
all pass; the JUnit file compiles against junit-jupiter-api. The island was re-previewed (RESULT OK, every anchor
reachable, crop images looked at). **Nothing in this wave has run on a live server.**
- **Mythological creatures fight like the reference** (`MythMechanics`, behaviour `mayors_myth`, neu-repo bestiary
  pages). *Siamese Lynxes*: only the lynx under angry-villager particles takes damage; each landed hit passes the
  mark to its twin ("Hit the other lynx!"). *Gaia Construct*: only every 6th / 7th / 8th hit lands (stages at 66% /
  33% HP; counter in the action bar, anvil clank on a blocked hit), 1 / 2 / 3 lightning strikes every 5 s, true
  knockback. *Minotaur*: Bleed +1 per hit and +3 per thrown axe (every 8 s, blocked by line of sight), each stack
  +5% Minotaur damage; the axe halves health regen/vitality for 5 s. *Minos Champion*: +3.5% damage per second
  alive, vanishes at +600% (~171 s), one lightning strike if it cannot reach you. *Minos Inquisitor*: +6% per
  second, vanishes at +600% (100 s); each hit you land drains 10% of your combat stats (max 40%, 20 s); telegraphed
  lightning (sparks, then a strike 1 s later) on your block; digging one up is announced server-wide. Each creature
  explains its trick in chat when it appears. Player debuffs are a StatProvider (`mayors_myth`).
- **The Carnival** (`Carnival`, Fairground perk, calendar id `carnival`). Ringmaster Fenella hands out 25 Carnival
  Tickets once per real (UTC) day. Captain Barnacle runs **Fruit Digging**: a 7x6 sand patch, 15 digs, every dug
  square shows how many bombs touch it (minesweeper with fruit); Apple escalates, Watermelon splashes open its
  neighbours, Pomegranate +50% on the next fruit, Coconut refunds the dig, Cherry pairs +300, Durian/Dragonfruit are
  the jackpots, a bomb ends the round; points become Carnival Tokens. Sheriff Dusty runs **Zombie Shootout**
  (`CarnivalShootout`): a 30 s menu round, zombies pop up behind a 7x3 fence for 1.7 s -> 0.9 s (2 -> 5 at once as
  the round speeds up), Zombie +10, Golden Zombie +30 (ducks faster), townsfolk -20, +10 every 5 hits in a row;
  closing the menu cashes out. Old Gill runs **Catch a Fish** (`CarnivalFishing`): a 30 s menu round, fish swim
  left to right across three lanes and speed up through the round; Fish +10, Yellow Fish +30 (rare, fast),
  Pufferfish -15, +10 every 5 catches in a row. Token Trader Fitz sells the exact neu-repo
  DOUG_NPC table (Mask Bag 250, Zombie/Salmon 500, Armadillo/Parrot 1000, Bee/Frog 2000). The six masks are new
  items with the reference stats; Salmon (+10 Speed/+10 Fishing Speed in water) and Armadillo (+50 Block Fortune)
  work, and the Mask Bag gives +1 Intelligence per different mask carried (`CarnivalMasks`). The troupe lives in the
  Festival Hall on the Winter Workshop and, while the Carnival runs, also stands beside the hub's carnival tents
  (`npc.carnival_barker` / `npc.carnival_games`; election room as the last resort). `/carnival`; Festivals & Events
  board slot 21. Perk `foxy.chivalrous_carnival` is now owned by mayors.
- **Votes by Fame Rank** (`FameVotes`, dial `mayors.fame-votes`): a ballot is worth the bank's Fame Rank votes
  (Citizen 2 ... Senator+ 100) x `vote-weight`, x2 with the Artifact of Control; the booth shows your Fame Rank.
- **Winter Workshop v2** (`WinterWorkshopGenerator` version 2 - rebuilds on next boot): a **Glacial Cave** tunnelled
  into Mount Gerald's south face (3-wide tunnel, 11x11 ice chamber with lanterns and a counter; zone `glacial_cave`
  SAFE, area `glacial_cave`) with shops' `npc.frozen_trader` inside, `npc.ice_carver` by the pond, and the carnival
  troupe's five stalls (three on the Festival Hall's west wall, the cowboy and the fisherman on the east wall).
- `HILT_OF_REVELATIONS` drops from Minos Hunters (TUNE rate), feeding weapons' Sword of Revelations recipe.
- The seasonal vendors' hub anchors exist now (hub built them), so the election-room fallback is only a last resort.

## Playtest-endgame fixes (2026-10-03)
- **Vote had no visible confirmation.** The chat line existed, but the booth was reopened on top of it right away, so it
  was hidden behind the menu (`62_voted.png` shows it half-covered). A vote that goes through now closes the booth
  (the confirm menu's own close), says "You voted for X! (n votes)" plus "You can change it ... /election", and
  flashes "Vote cast for X!" in the action bar. A refused vote (closed booth, not on the ballot, too fast, same
  candidate) still reopens the booth (`ElectionMenus`, `ElectionService.vote`).
- **A Dark Auction bid button showed a different number from the bid placed.** Root cause: once you held the top bid,
  the buttons kept showing `nextBid()` (top x 1.1), so right after bidding 10,000,000 the button read "Bid: 11,000,000".
  When you hold the top bid the buttons now read "Your bid: <your bid>" with the minimum next bid in the lore.
  Related race fixed too: the label came from the last redraw (every 10 ticks) and the click recomputed the price,
  so a bid by someone else in between charged an amount the clicker never saw. The click now carries the amount it
  showed (`bid(p, mult, shown)`); if the price moved, nothing is charged and the player gets the new price. The menu
  redraws right after a click. The bidder gets their own "You bid X coins!" line, and every offered amount (including
  the opening "+25%" / "Double It") is rounded up to a whole thousand. `bash tools/check.sh mayors` = CHECK OK. Not run live.

## Playtest wave-3 fixes (2026-10-04)
- **"DARK AUCTION! ... Bring at least 400,000 coins" reached a 0-coin player every hour.** Root cause: `DarkAuction.start()`
  broadcast to every online player, while the door (`join`) refuses anyone under the entry purse, so most of the
  server got an hourly invitation it could not act on. The invitation now goes only to ready profiles that pass the
  same door rule (`DarkAuction.canEnter(purse, need)`, now shared by `start()` and `join()` so the two cannot drift),
  and it names the way in that actually works: `/darkauction` when `mayors.da.remote-join` is on, "speak to Ashgrave
  in the Hub" when it is off (it used to say `/darkauction` either way). The calendar menu, Ashgrave and
  `/darkauction` still show the auction to everyone. `bash tools/check.sh mayors` = CHECK OK. Not run live.
- **"[Calendar] ... starts in / has started" for every event** is sent by the menus module, not here (Request 14).
  **Fixed from this side in Wave 4** (below).

## Wave 4 (2026-10-04)
**Calendar chat is filtered per player.** Playtest: AI players (and humans) got "[Calendar] X starts in 4m 43s!" and
"X has started!" for EVERY calendar event - Spider's Den rain every 40 min, a Harvest Fair and the Dark Auction every
hour - so the lines that mattered scrolled away. Those lines come from menus' `MenusListener`, which this module may
not edit, so mayors now SENDS them itself through a policy and switches menus' generic announcer off through its own
dial. Compiled (`bash tools/check.sh mayors` = CHECK OK); `MayorsPureTest` (12 tests, incl. the new
`calendarAlerts`) run offline with a scratch reflection harness - all pass, and a mutation (DA far-below -> PLAIN)
makes `calendarAlerts` fail, so the check can see the defect it guards. **Not run on a live server.**
- **Policy** (`AlertPolicy`, pure): per-player scope **important** (default) / **all** / **off**.
  *important* = festivals with rewards (Spooky, Encore Spooky, New Year + progression's cakes, Traveling Zoo, Season
  of Gerald, Mythological Ritual, Carnival, fishing festival), the election (`election_booth`, `mayor_term_start`),
  and the Dark Auction only for players who can afford it. Hourly/ambient events (Spider's Den rain/thunder, Harvest
  Fair, volcano eruption, Workshop-open) are *all*-only. *off* = no calendar chat at all, including our own festival
  announcements below. menus' own toggles (`notify.event_reminders`, `notify.event_reminder_minutes`,
  `notify.event_start`) are still honoured, read from their PDC mirror, so the calendar menu's bell and `/settings`
  keep working.
- **Dark Auction**: purse >= entry (400k) -> "starts in 5m! Use /darkauction to enter." ; purse >= `alerts.da-near`
  (50%) of entry -> the reminder with "Entry needs 400,000 coins - you are N short" ; further below -> **nothing** on
  *important*, and on *all* only the bare "starts in" line with **no coin requirement**. The door rule
  (`canEnter`) now lives in `AlertPolicy` and `DarkAuction.canEnter` delegates to it, so door, invitation and
  reminder cannot drift. The :55 invitation also respects *off*.
- **No double news**: a generic "has started!" line is skipped for events whose owner already announces the start
  (`alerts.owner-announced`: every mayors festival, the DA invitation, the election, progression's cakes, the
  volcano). Two registrations of one festival (mayors + progression "New Year Celebration") remind once.
- **Perk gates** (`alerts.perk-gates`): `fishing_festival` is registered on day 1 of EVERY month by fishing and only
  runs under `marina.fishing_festival`, so it is announced only while that perk is on (even on *all*).
- **Our own announcements filtered** (`EventAlerts.news`): Spooky begin, New Year, Zoo, Season of Gerald siege,
  election opening, election results, Carnival, Mythological Ritual (the last two were `Msg.broadcast`, now per
  player), DA invitation.
- **Exactly one announcer** (`EventAlerts.takeOver`, postEnable): with `mayors.alerts.takeover` on (default) mayors
  sets `menus.event-reminders` false once; turning takeover off turns menus' dial back on; turning menus' dial back on
  by hand switches takeover off (read as "menus should do it"), so the two never both send and never fight. Note the
  dial write persists in `dials.yml` like any `/sb dial set`.
- **Setting storage**: PDC `skyclone:setting.notify.event_scope` (`important`/`all`/`off`), written the way island
  writes `island.visitors`, so a menus Settings entry (Request 15) adopts it with no migration. Per player, not per
  profile (it is a chat preference).
- **Player UI**: `/eventalerts [important|all|off]` (no arg = status: scope, reminder lead, start toggle), and the
  bell in Festivals & Events slot 41 cycles important -> all -> off. The first calendar line a player gets each
  server run is followed by "Calendar alerts: Important only. Change with /eventalerts."
- **Cross-module**: requirement `mayors:alert:<eventId>[:remind|start]` answers "would this player get the line",
  for menus to consult if it takes the announcer back (Request 15).
- **Admin**: `/sb mayors alerts <player> [scope]` prints remind=/start= decisions for every registered event.
- **Live test plan** (owner/integrator): boot, `sb selftest mayors` (`mayors.alerts` must pass: takeover on and
  menus' dial off) -> `sb dial get menus.event-reminders` = false -> with a 0-coin player `sb mayors alerts <p>`:
  `dark_auction remind=NONE`, `spiders_den_rain remind=NONE`, `spooky_festival remind=PLAIN start=NONE` -> wait for
  :50 real time: no DA line for the poor player; give 250k coins -> next hour the "short" line; 400k -> the
  "Use /darkauction" line.
- Files: `AlertPolicy.java`, `EventAlerts.java` (new); `MayorsModule`, `MayorDials`, `MayorsCommands`, `EventBoard`,
  `MayorsChecks`, `MayorsSelfTests`, `DarkAuction`, `SpookyFestival`, `NewYear`, `TravelingZoo`, `WinterWorkshop`,
  `ElectionService`, `Carnival`, `Mythology`; test `MayorsPureTest.calendarAlerts`.


## Fix pass (2026-10-04, wave4-endgame playtest)
The Festivals & Events bell (slot 41) printed the current scope's meaning as one long line and then the Important-only text again; it now lists the three choices with ▶ on the current one. Live-verified the Wave 4 alert policy: Important only + 80M purse -> "[Calendar] Dark Auction starts in 4m 58s! Use /darkauction to enter."; `/eventalerts` status and the bell cycle work. Open: DA round lines keep reaching a player who left the room; the Spooky sidebar's two candy counts are unlabelled.

## Wave4-endgame playtest fixes (2026-10-04)
Compiled (`bash tools/check.sh mayors` = CHECK OK); the new pure check `MayorsChecks.chatAndSidebar` (self-test
`mayors.presence`, JUnit `auctionNewsFollowsPresenceAndCandyIsLabelled`) and `alerts` were run offline with a scratch
harness against the compiled module - both pass. **Not run on a live server.**
- **Dark Auction round news reached a player who had left** (open issue 9: left after round 1, warped across 15
  islands, still got "Round 3/5", "No bids ... withdrawn", "The Dark Auction is over"). Root cause: `tell()` sent to
  everyone in `session.joined`, and joining was forever - nothing ever modelled leaving. Round news now goes only to
  joined players who are IN THE ROOM: the Dark Auction menu is open (`Sky.menus().current`, title `DarkAuction.TITLE`)
  or they stand in the hub area `dark_auction` (+2 blocks, same as its zone). Pure rule `DarkAuction.inRoom`.
  Membership is kept, so `/darkauction` (or Ashgrave) takes them straight back in with no second purse check.
  Closing the menu outside the hall says ONCE per auction "You stepped out of the Dark Auction - round news stops
  here. Back in: /darkauction" (+ "Your top bid still stands." if it does), checked a tick later so menu-to-menu and
  standing in the hall are not leaving. Own-coin lines stay personal: the outbid refund (unchanged) and a won lot
  ("You won X for N coins!") reach the player wherever they are. The DA sidebar section also goes quiet for a
  stepped-out player unless they hold the top bid, so it no longer pushes an island's own section off the sidebar.
- **Spooky sidebar counts were colour-only** (open issue 10: "Candy: 5 1 (10 pts)"). Now three labelled lines:
  "Spooky Festival" / "Candy: 5 Green, 1 Purple" / "Points: 10" (`SpookyFestival.sidebarLines`, pure). The
  section is 3 lines during the festival (was 2).
- Files: `DarkAuction`, `SpookyFestival`, `MayorsChecks`, `MayorsSelfTests`, test `MayorsPureTest`.
- Live test: `sb mayors da start`, join with `/darkauction`, close the menu on the Hub outside the hall -> one
  "stepped out" line, then nothing per round; `/darkauction` again -> round lines resume. `sb mayors spooky start`,
  kill a mob with candy -> sidebar reads "Candy: N Green, M Purple" / "Points: P".

## Wave 5 (2026-10-06)
Two agents this wave (the first stopped mid-way after building the Carnival booths and the new creature data; the
second finished the rest and wrote this). `bash tools/check.sh mayors` = CHECK OK. `MayorsPureTest` (16 tests incl. the
new `mythologicalRitualNumbers`, `siegeWavesAndGifts`, `maskAbilitiesAndSpookyPerks`, and the extended `carnivalRules`)
compiled against the real classpath + junit-jupiter-api and run with a scratch reflection harness against the real JSON:
16/16 pass. Data regenerated (`gen_mayors.py`); diff = `ANCESTRAL_SPADE` (reforgeable, Echo 3 s), new `ARCHAIC_SPADE` /
`DEIFIC_SPADE`, gift skill XP, Deific treasure; nothing else moved. **Nothing in this wave has run on a live server.**

**Done**
- **Carnival booths are physical** (`CarnivalGames` + pure `FruitDig` / `ShootRound` / `FishRound`, island
  `CarnivalGroundsGenerator` = `carnival`, `/warp carnival`, preview RESULT OK, every booth anchor reachable):
  *Fruit Digging* - walk onto the 7x7 sand patch with the lent Carnival Shovel and left-click squares; what you dig is
  drawn for you alone (block changes + floating labels), right-click cycles the Dowsing Mode (Mines / Anchor /
  Treasure), 15 digs, 5 minutes, 34 fruit + 10 bombs + 5 rum with every reference fruit rule. *Zombie Shootout* - you
  are held on the firing stand, zombies (leather/iron/gold/diamond, babies double) walk two raised lanes and lamps light
  on the back wall; right-click the Dart Tube (a ray, drawn), lit lamp +100 and x1.2 for 6 s, 100 s. *Catch a Fish* -
  held on the dock, cast the Carnival Rod next to a glowing tropical fish to hook it, hold right-click until its bar
  fills (Red 50 / Blue 100 / Yellow 200, +5 per catch in a row to +50, 10% doubled), 100 s. Entities are per-player
  (hidden from everyone else), so a crowd can play at once; tools are lent and taken back; sneak twice to stop; a game
  is paid out (points so far) on quit, leaving, the Carnival closing or a server stop. Scorekeeper Odette's 15 goals
  (50/100 tokens + 5 tickets), the 24 Carnival Perks (neu-repo tiers/costs) and the souvenir shop.
- **Carnival Perks applied**: Candy Scavenger, Purple Vogue, Trick or Treat Enthusiast (`SpookyFestival`); Present
  Connoisseur, Red Fad, Professional Gifter (`WinterWorkshop` siege + gifts); Deadly Greed, Huntress's Favor (MF hook),
  Storied Stinger (damage modifier 450), Elusive Hunter (Tracking) (`Mythology`/`CarnivalPerks`); Icy Hook, Fishing
  Prodigy, Fortunate Festivity, Fortunate Feasting (stat provider). The rest via `mayors:carnival_perk:` (Request 19).
- **All six masks work** (`CarnivalMasks`): Zombie (+5% stats of undead chest/legs/boots), Salmon, Armadillo, Parrot
  (Party Flock: others within 20 blocks +1 Strength per Intimidation upgrade, best wearer), Bee (the summoned Bee pet's
  base stats again, from pets' own data/curve), Frog (+20 Foraging Fortune / +5 Foraging Wisdom in one Park region a
  SkyBlock day, seeded by the date).
- **Mythological Ritual on the reference's new model** (`Mythology` rewritten over pure `MythRules`): three spades
  (Ancestral 4 / Archaic 6 / Deific 8 burrows, Echo 3/2/1 s, +2 with the Erudite reforge, max 10; the two new spades are
  registered here and crafting's existing neu recipes light up); the first burrow of a chain is treasure, then 75%
  creatures; the Griffin's rarity opens two creatures a step (Common: Hunter + Twin Lynxes ... Mythic: Manticore + the
  Labyrinth King, weights 1000/800/600/400/75/15) and picks the variant (Common .. Empyrean); treasure per spade
  (Griffin Feather 2/3, else coins x2 Crown of Greed x Deadly Greed; Mythos Fragments, Braided Griffin Feathers on the
  Deific, Myth the Fish 1/10,000); Tracking (Golden Fleece +15, Elusive Hunter, Mythic Griffin +1 per burrow in the
  chain, hunting's `stat:tracking`) tilts the pool to the rare creatures; Inquisitor / Manticore / King dug up is
  announced server-wide; each creature explains its trick on arrival. **This also fixed a live bug**: events.json had
  moved to rarity-keyed pools and spade-keyed treasure while `Mythology` still asked for tier "1".."3" pools and tier
  treasure - every treasure burrow paid nothing and Minotaur and up could never appear.
- **The Sphinx asks its riddle** (`SphinxRiddles`, 14 questions about this game): a menu opens a second after it rises
  (right-click it to reopen); right = a roll of your spade's treasure, wrong = enraged (+50% health, heavier bleed).
- **Only the Hunter can be hurt without a Griffin out** (reference rule; dial `mayors.myth.griffin-to-hurt`).
- **Siege rounds come in waves** (pure `SiegeRules`): each round's attackers march in over 4 waves (pressure rising,
  the Lava Behemoth with the last), the next wave when the field is down to a quarter or after 60 s; the round ends
  when every wave is down. Extra gifts from Present Connoisseur + the Snow set (+5% a piece) + a Reindeer's Gift Sense;
  Red Fad upgrades; gifts now give skill XP (a random skill, TUNE ranges) scaled with the best gift talisman and
  Professional Gifter. Sidebar shows the wave.
- **Spooky**: Lucky / Eternal Hoof and the Bat pet's own Sweet Tooth number.
- **Settings**: calendar alert scope / toggles / reminder lead are read through `Features.Settings` (menus adopts our
  PDC writes, so `/eventalerts` and the Settings menu agree).
- Files (this agent): `MythRules`, `SiegeRules` (new); `Mythology` (rewritten), `CarnivalMasks` (rewritten),
  `WinterWorkshop`, `SpookyFestival`, `MythMechanics`, `EventAlerts`, `MayorDials`, `MayorsModule`, `MayorsCommands`,
  `MayorsChecks`, `MayorsSelfTests`, test `MayorsPureTest`, `tools/gen/mayors/gen_mayors.py`, data `items.json`,
  `events.json`. (First agent: `Carnival*`, `FruitDig`, `ShootRound`, `FishRound`, `SphinxRiddles`, `EventData`,
  `PlayerData`, `EventNpcs`, `ElectionService` badges, the generator's creature tables.)

**Live test plan**: `sb mayors perk foxy.chivalrous_carnival on` -> `/carnival`, claim tickets, `/warp carnival`, play
each booth (Barnacle / Dusty / Gill) -> tokens and goals; `sb mayors carnival tokens <p> 5000` -> buy Candy Scavenger
and the masks. `sb mayors set diana` -> `/sb pets give <p> GRIFFIN MYTHIC 100`, summon, buy a spade, Echo in the Hub,
`sb mayors myth spawn <p> sphinx 6` (riddle menu), `... minos 6` (shield, rage). `sb mayors set jerry` (or the season)
-> `sb mayors siege start`, stand on the Winter Workshop: waves 1..4, the Behemoth last, gifts after each round.

## Real-game audit lane 4 (2026-10-06, `docs/AUDIT_REAL_GAME_2026-10-06.md` §7)
- **M4 / C13 Mining Fiesta**: lasts the WHOLE term and is minister-eligible (`ministerEligible: true`; election.json Y518
  flags it). New forced-only calendar event `mining_fiesta` (`Cal.FIESTA`), driven every second by `ExtraEvents` while
  `cole.mining_fiesta` is active (mayor with it on his ballot, or minister with it as his perk) - dial
  `mayors.fiesta.whole-term` (true). Alias `mining_fiesta` added so mining's `mining.fiesta-perk` dial resolves too
  (before this it resolved to nothing and no calendar event had that id: **the Fiesta could never run**).
- **M3 lore only**: "+25 Mining Wisdom, +25% Mining Fortune, Refined Minerals and Glossy Gemstones" (value 25). The
  numbers are mining's dials (`mining.fiesta-*`).
- **F7 Foxy Extra Event variants**: `ElectionMath.extraEventVariant(seed, election, dial)` rolls fishing / mining /
  spooky per election (NEU FOXY_MAYOR_MONSTER lists the three; the reference rolls it per ballot). Dial
  `mayors.extra-event.variant` (auto | fishing | mining | spooky). Fishing and mining run on
  `mayors.extra-event.month/day/days` = Late Summer 22-24 (reference Y518); spooky keeps `mayors.spooky.extra-month`
  (29th, 3 d). Fishing variant = new forced-only event `fishing_festival_extra`, which `Fishing.festival()` now ORs in
  (3-line edit in the fishing module, no perk gate there: mayors only turns it on under the perk). Mining variant
  forces `mining_fiesta` for the window. Perk lore shows the rolled variant and its date (`{extra_event}` placeholder,
  `ElectionMenus.perkLines`). Both events added to the default `mayors.alerts.important`.
- **C12 Diana**: Lucky! (+25 Pet Luck) is gone from the reference (NEU DIANA_MAYOR_MONSTER) and is replaced by
  `diana.huntress_intuition` (+10 Tracking, minister-eligible, owner **hunting** - not applied yet: hunting has a
  `tracking` pseudo stat from shards but no reader). The Ritual stays mayor-only, and `ElectionMath` now guarantees every
  regular ballot carries a minister-eligible perk (`ensureMinisterPerk`) and never falls back to an ineligible one, so a
  Diana who rolled only the Ritual can no longer bring it as minister.
- **Values checked** against fishing/mining/combat.md + election.json: Marina 50 wisdom / 15 SCC / 0.1 DH per SCC, Paul
  Marauder 20% / Benediction 25%, Cole Prospection 25% - all already matched. Minister flags seen on real ballots are
  asserted eligible.
- Tests: `MayorsChecks.referencePerks` + `extraEvents` (JUnit `perksMatchTheRealGame`, `foxyExtraEventVariantsAndWindow`;
  self-tests `mayors.reference-perks`, `mayors.extra-events`; `mayors.calendar` now checks the Fiesta follows its
  perk). 18/18 JUnit green via javac + platform launcher.
