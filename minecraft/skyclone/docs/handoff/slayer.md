# slayer — hand-off

Package `com.skyclone.mod.slayer`, entry `SlayerModule`. All six slayers (research 04 sec 2), every tier, every
ability, quests, levels + rewards, RNG meter, minibosses, loot, slayer drops + armour, the usable slayer items, the
Slayer master NPC and the Slayer Hotline. Provides `Features.Slayers` and the requirement prefixes
`slayer:<type>:<lvl>` (aliases `eman`, `revenant`, `tarantula`, `sven`, `inferno`, `riftstalker` accepted) and
`slayerkill:<type>:<tier>` (has defeated that tier or higher).

Data is generated, never typed: `py -3 tools/gen/slayer/gen_slayer.py` reads `research/data/items.json`, the NEU
`*_BOSS.json` / `*_MINIBOSS.json` pages (per-tier loot chances, quantities, HP), NEU item pages (recipes,
`slayer_req`), `neu-repo/constants/leveling.json` (XP tables) and `rngscore.json` (RNG meter XP), plus research 04's
master table / gates / level rewards (transcribed in the script with section numbers). It writes
`plugin/src/main/resources/data/slayer/{slayers,items,items_fallback,mobs,recipes,recipes_late}.json` and
`docs/names/slayer.md`.

## What works (verified how)

Verified by `bash tools/check.sh slayer` (**CHECK OK**, 186 files) and **8 JUnit tests**
(`plugin/src/test/java/com/skyclone/mod/slayer/SlayerMathTest.java`, run locally with javac + the JUnit 5.11 launcher
from the gradle cache: **8/8 pass**) that run the SHIPPED json through the pure `SlayerMath`: level tables (Zombie
1,000,000 XP = 9, Wolf 249 = 2, Vampire 2,400 = 5), the master table (HP, cost, spawn XP, slayer XP, Immolate rows),
the loot model reproducing the displayed odds exactly (Ghoul IV Foul Flesh **16.22%**, Broodweaver V Toxic Arrow
Poison **12.809%**, Seraph IV Verdict Core **0.0565%**), level filtering + renormalisation, Magic Find / Trailblazer
only on drops under 5%, minibosses never out-hitting their boss tier, item/mob/recipe well-formedness and "no
reference proper noun in any item name".
**Nothing has run on the live server** (agents may not start it) — every gameplay claim below is "written and
compiled", the self-tests are what to run first.

| System | What it does |
|---|---|
| **Quests** (`Quests.java`) | Pick slayer + tier at **Brannoc the Slayer** (hub `npc.slayer_master`) or `/slayer` → confirm → fee charged (Vexmar's *Cut-Rate Contracts* halves it via `Features.Mayors`). Kill that slayer's mobs: each kill adds its **Combat XP** to the bar (sidebar section prio 200 + action-bar meter). Mobs count by `MobDef.slayer`; untagged mobs count by entity type (dial). Bar full → 2 s summoning (closing ring, lightning, title) → boss spawns owned by you, next to you. **Timer 3:00** (Bloodfiend 4:00). Fail on timeout, death, logging out, or staying >48 blocks away for 10 s ("SLAYER QUEST FAILED!"). Server restarts / chunk unloads never cost a paid quest: the boss becomes **READY** and is re-summoned with `/slayer summon` or the menu. |
| **Kill rewards** | Slayer XP credited on the kill (no walk back to the NPC — the one deliberate change), Slayer XP Buff perk (+25%), level-up box with stat rewards / notes / every recipe unlocked at that level (scanned live from the recipe registry) + SkyBlock XP (`Features.SkyLevel.award("slayer:<type>:<lvl>")`), loot, RNG meter, bits 2/5/8/12/15 with an active Boon Cookie, after-kill heal 40-200 HP/s for 6-10 s by level, best-time tracking, auto-restart (Auto-Slayer). |
| **Levels** | Exact XP tables (Zombie/Spider 5…1,000,000, Wolf/Enderman/Blaze 10…1,000,000, Vampire 20…2,400), our own level names, stat rewards per research (Zombie +34 Health +30 Regen, Spider +16 Crit Damage +5 Alchemy Wisdom, Wolf Speed/Health/CD, Enderman Health/Int, Blaze Health/Str/True Def, Vampire Rift Time) via StatProvider `slayer_levels`. Wolf 8: lose 5% fewer coins on death. |
| **Unlock chain** | Spider after Ghoul II, Wolf after Broodweaver II, Enderman after Fenris IV, Blaze after Seraph III, Vampire after Fenris III. Tier gates Combat 5/10/15/25 (+level 7 for T5) for Zombie/Spider/Wolf; Enderman/Blaze/Vampire need the previous tier killed (Vampire T4/T5 + Combat 15/25). Locked tiers say exactly why. |
| **Loot** (`SlayerLoot`) | Token always (Ghoul Flesh, Broodweaver Web, Wolf Tooth, Null Sphere, Derelict Ashe, Coven Seal) + one weighted pick per kill over the drops your level allows (weights = NEU per-kill %, token fills the rest), Magic Find + Trailblazer on drops < 5%, dyes rolled independently, "RARE DROP!" banners by odds, server broadcast under 0.1%. Lootshare: co-op helpers with ≥10% of the boss's HP dealt get a fifth of the token and a 20% weighted roll. |
| **RNG meter** | Per slayer: pick any eligible rare drop (menu or `/slayer rng`), every kill adds its slayer XP; full meter = guaranteed drop on the next kill of a tier that can drop it; natural drop resets it; meter XP needed straight from `rngscore.json` (Sentinel Heart 3,674,250, Verdict Core 885,562…); while filling the selected drop's weight is x(1 + fill) (dial). |
| **Ghoul Horror I-V** | Life Drain, Pestilence (AoE + -25% Defense via StatProvider), Enrage cycle (MAD warning → 12 s enraged x2 dmg, faster). **Penitent Horror (V)**: 0.1%/s regen, immune to ability damage, TNT on marked rings (1.5 s warning; a hit heals it 500k; twice as often under 33%), **Thermonuclear** every 30 s (rooted, closing 7-block ring, 5-4-3-2-1 titles, lightning). |
| **Broodweaver Patriarch I-V** | Backstab (+20…80% from behind) + leaps behind you, Paralytic Venom (-Vitality/-Speed %, T5 strips Absorption), **Web Cocoon** at 66/33% (invulnerable, 2/3/4 floating egg sacs taking 3 or 9 HITS, 15 s or they hatch into broodlings, heals while cocooned), **Bat Barrage** (each live bat drains 0.1/0.2% max HP/s), **Bound in Death** at T5 → **Twinbound Brood** phase (20M HP, 4,400 dmg). |
| **Fenris Packlord I-IV** | Agile, true damage 10/50/200 per second within 4 blocks, **Call the Pups** at 50% (invulnerable + rooted, 5 pups one per second at 10% of its HP each, regenerates while they live). |
| **Nullshade Seraph I-IV** | **Hitshield** 15/30/60/100 HITS at 100/66/33% (hit counter on the label + boss bar, +2,000 dmg while up at T3+, Ferocity -75% while down), Dissonance blink + 6.5-block blast, **Void Glyphs** (beacon lands, stand next to it within 5 s or lethal burst), **Nukekubi heads** under 33% (look at them for half a second to pop; damage doubles per head alive), **Heartbreak Beams** at T4 (12 rotating beams, 800 true + -12 Vitality 90 s). Enderman random teleports are suppressed. |
| **Cinder Demonlord I-IV** | Immolate aura by HP third (45/65/115 … 385/575/955 true/s), **Demon Split** at 50% (T3+ 66/33%) into Quorrix (beam 1,500 true) + Tyrphon (fire trail 1,350/s), **Attuned Shield** T2+: ASHEN → SPIRIT → AURIC → CRYSTAL, 8 matching hits per colour, wrong attunement = 1% damage; demons cycle their own pairs. Reads the dagger's `ItemData.extra.attunement` (weapons module contract). **Fire Pillars** (8 hits in 7 s or everyone near dies), **Fire Pit Dance** under 33% (telegraphed 3x3 pits, 950 / 2,865 true/s). |
| **Duskborne Bloodfiend I-V** | Coagulation, Spectral Form, **Mania** (invulnerable 26 s, moving safe ring), Twin Claws (telegraphed frontal cone), Stone Wrath (Blood Gargoyles sap 10% max HP each), **Spring Trap** (alternate LEFT/RIGHT clicks 6 times in 15 s or die), **Command** (JUMP / SNEAK / LOOK UP / LOOK DOWN in 2-2.5 s), **Blood Ichor** at T5 (bait Twin Claws into it), **Execution** below 20% only with a Steak Stake (players under Vampire 2 may finish it by hand; dial). Rift numbers are scaled: 1 Rift heart = 4% max HP, 1 Rift HP = 1,000 HP (dials). |
| **Shared fight polish** | Boss bar with HP, timer and phase/shield status (colour follows the attunement), floating label ("Spawned by", timer, shield/IMMUNE), action-bar flashes for every mechanic, warning titles + sounds before every big hit, particle rings/lines/cones for every telegraph, bosses follow you if you run, immune hits say why. HP thresholds can never be skipped by one huge hit (`clampAt`). Co-op members may help (`setAllies`). |
| **Minibosses** | 17 (Ghoul Sycophant…Searsoul Demon) with NEU HP/levels/token drops; 1.2% per quest-mob kill (dial) at their tier+, owned by you, fill the quest bar with their Combat XP; per-player kill counts. |
| **Items** (109 own + 19 fallback) | All slayer drops (renamed), 27 armour pieces in 7 sets, 2 heart helmets and the Sentinel Helmet, Slayer Hotline, 5 mixins, 4 power orbs, 3 flares, 2 steak stakes, 2 tubas, Gloomlock Grimoire, Holy Ice, 2 book bundles; fallback (only if nobody else made them): 12 runes, 6 dyes, the Seraph pet skin. 53 recipes (NEU grids, slayer-level unlocks; 1 waits for `PLASMA_NUCLEUS`). |
| **Armour** (`SlayerGear`) | Bulwark kill counters on Ghoul/Broodweaver/Last Horizon pieces (+20 Defense vs that mob per 50/50/100 kills, max +120, shown in lore), Grave Trolling (+100 Def vs undead), Reaper's Rage (sneak: +100 Spd/Str/+100% dmg 6 s, 25 s cd) + x2 vs undead, Reaper Mask heal on undead kill, Eightfold/Elder Strike (every 4th/3rd hit), +CD per 10 Strength, Absolute Unit (Mastiff: +1 CD per 2 Defense, +1% HP per 25 CD max 40%, -20% from animals, heal 150 when hit, Defense capped at 300 on incoming hits), Pack Spirit, Vivid Darkness (sneaking), Sentinel Helmet Brute Force (Speed x0.5, +20% base weapon damage per 25 Speed). |
| **Usables** (`SlayerItems`) | Hotline (rings, joke lines on cooldown, no reception in dungeons/Rift, opens the menu), mixins (36 h stacking, Boon Cookie gated), orbs/flares as real deployables (floating display, radius, max players, heals, stats, "only the best one applies"), tubas (Howl to you + 4 nearest), grimoire (Life Tap / Extreme Measures), Holy Ice (80% DR 1.5 s), Razor Stake Bloodcrave (next hit x2), book bundles. |
| **Menus** | Slayer (overview head, 6 slayers, live quest icon, RNG meter, Shop, Statistics + collection log, Help, Auto-Slayer) → per slayer (5/4 tier icons with HP, damage, every ability up to that tier, reward, spawn XP, price incl. sale, kills/best time, lock reason; Boss Drops per tier with YOUR odds and RNG select; Level Rewards road with stats/notes/recipes; RNG Meter; Minibosses; Auto-Slayer toggle). Confirm dialogs for start/cancel/expensive buys. Back buttons everywhere, SkyBlock Menu link. |

## What is partial / not built
(Rewritten in wave 5 - see the "Wave 5 (2026-10-06)" section at the end for what changed and how it was verified.)
- **Riftstalker fees are in Motes only once the rift module provides `Features.Rift`** (`Sky.has`). As of this wave
  the rift module does not provide it yet (grep finds no `Sky.provide(Features.Rift...)`), so the Bloodfiend still
  charges coins x `slayer.vampire-cost-per-mote` until it does; the switch is automatic (dial
  `slayer.vampire-fee-currency` auto / motes / coins).
- **Slayer weapons are NOT registered here**: the weapons module ships all 31 (falchions, scythes, katanas, daggers,
  Shaman/Pooch, shortbows, wands) with abilities as fallbacks; I left them to it rather than duplicate their ability
  code. Accessories, minions and sacks stay with their owners. Their recipes' slayer ingredients are all mine.
- **Mastiff's "Heal 150 when hit, consuming 5 Vitality"**: the heal works, the Vitality cost does not - Vitality is a
  pool local to the enchanting module with no service (core-wave5 "not done").
- **Archfiend dice** are fixed-value tokens (no gambling, CONTRACT 1.2) - deliberate, permanent.
- Not in the reference data, so TUNE-marked: Life Drain amount (dial `slayer.zombie-drain-seconds`), Inferno melee
  damage, miniboss melee damage and spawn chance, Twin Claws/Command/Spring numbers in % of max HP, pup damage, regen
  while cocooned/pups; wave 5 adds the cocoon hatch time / hatched health (dials), Wolf Paragon 0.1% and the Paragon
  minimum level 5 outside the Ghoul table.
- Steak Stake recipe/requirement adapted (Coven Seals + iron, sold in the Slayer Shop).
- Not this module's any more (owners have them): runes and runecrafting (skills registers all 34 rune types, our
  fallback runes yield), Soulstream items / engines / batteries and Gloomlock's 15 Soulstream (soulflow, accessories,
  minions), Phasewarp Conduit (weapons), travel scrolls (combatzones), the Runebook (`extra.hook = skills`).

## Commands added (player + admin)
- `/slayer` (alias `/slayers`) — Slayer menu. Subs: `summon` (re-summon a READY boss), `cancel` (confirm), `rng [type]`,
  `shop`, `stats`, `help`, `<type>` (zombie, spider, wolf, enderman, blaze, vampire).
- Menu paths: SkyBlock Menu → Slayer (menus module calls `Features.Slayers.openMenu`), Brannoc at hub, Slayer Hotline.
- `/sb slayer help | info [player] | spawn <p> <type> <tier> | start <p> <type> <tier> [paid] | progress <p> <combatXp> |
  xp <p> <type> <n> | level <p> <type> <lvl> | kills <p> <type> <tier> <n> | rng <p> <type> [item|clear|fill|<xp>] |
  kill <p> | fail <p> | cancel <p> | mini <p> <type> [mobId] | loot <type> <tier> <level> [rolls] [mf] | reset <p> [type] |
  menu <p> [type] | cocoon <p> <type> <tier|mini> | mixin <p> <MIXIN_ID> [hours]` (the last two wave 5) — all take a player name, so everything is testable over RCON. `loot` Monte-Carlos the table and prints
  expected vs observed.

## Dials added
`slayer.boss-time-seconds` 180 · `slayer.vampire-time-seconds` 240 · `slayer.boss-hp-scale` 1 · `slayer.boss-damage-scale` 1 ·
`slayer.vampire-hp-scale` 1000 · `slayer.vampire-heart-percent` 4 · `slayer.vampire-cost-per-mote` 1 · `slayer.vampire-any-mob` true ·
`slayer.stake-required-level` 2 · `slayer.spawn-xp-scale` 1 · `slayer.xp-scale` 1 · `slayer.cost-scale` 1 ·
`slayer.miniboss-chance` 0.012 · `slayer.rng-boost` 1 · `slayer.auto-slayer-level` 8 · `slayer.relog-fails-quest` true ·
`slayer.death-fails-quest` true · `slayer.leash-range` 48 · `slayer.leash-seconds` 10 · `slayer.follow-range` 20 ·
`slayer.coop-can-help` true · `slayer.lootshare-damage-percent` 10 · `slayer.bits-need-cookie` true · `slayer.hotline-cooldown` 20 ·
`slayer.sidebar-lines` 2 · `slayer.entity-type-fallback` true · `slayer.spawn-delay-ticks` 40 · `slayer.mixin-hours` 36 ·
`slayer.mixin-needs-cookie` true · `slayer.shop.mixin-price` 150000 · `slayer.shop.stake-price` 2500 ·
`slayer.mechanic-damage-scale` 1 · `slayer.zombie-drain-seconds` 0.5 · `slayer.actionbar` true · `slayer.telegraph-titles` true · `slayer.gear.bulwark-defense` 20.
Wave 5: `slayer.vampire-fee-currency` auto · `slayer.primordial-chance` 10 · `slayer.cocoon-hatch-seconds` 3 ·
`slayer.cocoon-health-percent` 100 · `slayer.cocoon-lifetime-seconds` 120 · `slayer.wolf-fur-magic-find` 7 ·
`slayer.gear.vivid-needs-soulstream` true · `slayer.gear.combat-seconds` 8 · `slayer.gear.vivid-ender-ferocity` 200 ·
`slayer.coldfused-break-true-defense` 30 · `slayer.coldfused-break-damage` 1.15 · `slayer.coldfused-break-seconds` 60
(these three replaced `slayer.coldfused-pillar-hits`, see wave 5 second pass) · `slayer.shard-drops` true · `slayer.mixins-at-bartender` true ·
`slayer.hunting-attributes` true; changed default: `slayer.vampire-heart-percent` 4 -> **10** (rift request 1).

## Self-tests added
`slayer.catalog` (6 types, tier counts, XP caps, master-table spot checks) · `slayer.mobs-exist` (every boss tier,
phase, minion, miniboss + their drops) · `slayer.drops-exist` (tokens and every rare drop someone must register) ·
`slayer.loot-math` (16.22% / 12.809% / 0.0565% / 1.011% reproduced; level gate) · `slayer.level-math` ·
`slayer.rng-scores` · `slayer.miniboss-damage` (no miniboss out-hits its boss tier) · `slayer.recipes` (results exist, unlock keys describe) · `slayer.requirements` (`slayer:` +
alias + every tier gate) · `slayer.icons` (every loot icon builds) · `slayer.utility-items` (Tactical Insertion + both Voodoo Dolls registered with abilities) · `slayer.npc-anchor` (hub has `npc.slayer_master`).
Wave 5: `slayer.hunting-shards` (every shard row named, weighted, research odds 1.2165% / 0.71%, a pool without
shards still sums to 1, Foul Flesh still 16.22%) · `slayer.wave5-hooks` (`slayer:mixin:` describes, behaviour mixins
grant no flat stat, cocoon chance / never twice, discount stacking, Coldfused pillar hits) · `slayer.fee-currency`
(Motes iff `Features.Rift` is provided, fee text, no coins-per-mote multiplier on Motes, only the Bloodfiend) ·
`slayer.pet-reforge-hooks` (pillar strike counts 1 here, Coldfused break-bonus rule, Webbed Cells 75% -> 52.5%, the pets
key `tarantula_webbed_cells` still exists) · `slayer.mob-families` (every slayer mob carries `extra.mobTypes`; Egg Sac is
an arthropod, Blood Gargoyle undead).

## Review pass (2026-10-02, second agent)
Read the whole module against CONTRACT, CORE_GUIDE, the API, research 04 sec 2 and 06's slayer-armour table. Fixed:
- **Stack-overflow on first stat compute** (`SlayerGear`): the Broodweaver/Primordial/Mastiff bonuses call
  `Sky.stats().get` inside a stat provider; for a player with no computed stats yet core computes on demand, which
  re-entered the provider without end. Re-entrancy guard added.
- **Mastiff was wrong vs research 06**: it halved Crit Damage; now +1 CD per 2 Defense, +1% HP per 25 CD (max 40%),
  Defense capped at 300 on incoming non-true hits. Lore regenerated to match.
- **Sentinel Helmet** only had its downside: Brute Force (+20% base weapon damage per 25 Speed) now applies.
- **Free escape from a losing fight**: warping away unloaded the boss's chunk, the fight was treated as "vanished" and
  a fresh full-health boss with a new timer could be summoned. A boss lost while its owner is away (other world or
  beyond the leash range) now fails the quest; only a boss lost under the owner's feet becomes READY.
- **Nullshade hitshield**: a hit that would cross 66%/33% while the shield was UP got through (the threshold check ran
  before the shield). The shield now eats the hit first.
- **Ghoul Life Drain** healed 5x what it drained; research says "the same amount".
- **Bloodfiend Spring Trap** spawned at a NaN location when the player looked straight up/down (zero horizontal
  vector normalised); Twin Claws had the same edge. Twin Claws no longer un-roots the boss during a Mania.
- **Tyrphon's fire trail** only checked players within 1 block of the owner; it now checks everyone near the trail.
- **Cinder Demonlord** no longer hovers out of sword reach (pulled to the ground when it drifts up) and every fight
  entity's vanilla projectiles (blaze fireballs etc.) are cancelled, so only scripted damage applies.
- **Miniboss melee** was health/250 (Void-Mad Maniac hit for 300,000 next to an 11,000 DPS boss); now 3/4 of the DPS of
  the boss tier they appear in, capped by health/250 (TUNE, in the generator). Unit test + self-test guard it.
- **RNG meter XP** is banked while nothing is selected (the menus already said "Banked meter XP"; it never banked).
- Co-op helpers who hit the boss now see its boss bar (hidden again at the end).
- A quest lost to logging out now says so on the next login (`SlayerData.loginNotice`, new field).
- Level-up box read "Zombie Slayer  -> I" for the first level; now "0 -> I".
Data regenerated with `py -3 tools/gen/slayer/gen_slayer.py` (items.json lore, mobs.json miniboss damage).

## Playtest fix (2026-10-03): Ghoul Life Drain
Playtest-loops found Life Drain was 1.5x the tier DPS as **true** damage every 4 s (180 at T3 = ~45 true dps on top of
melee and Pestilence), so a 600 HP / 700 Defense player died in ~13 s whatever their Defense, while the tooltip said
only "Damage: 120 per second". Fixed at the root:
- `SlayerMath.lifeDrain(dps, seconds)` / `lifeDrainDps` / `pestilence(dps)` (pure, unit-tested). One drain = `slayer.zombie-drain-seconds`
  (default **1**) seconds of the tier's stated DPS every 4 s, dealt as **normal damage (Defense applies)** — on average
  +25% on top of the stated DPS instead of +37.5% that ignored Defense. T3: 120 per drain (was 180 true).
- The boss heals **exactly the damage dealt** (`BossFight.hurt`/`hurtTrue` now return what `damagePlayer` dealt), and
  the action-bar flash shows that number. Research 2.5 says "heals the boss by the same amount".
- The Ghoul tier tooltip now states the ability bill under the melee line: "Life Drain: 120 every 4s", "Pestilence: 36
  per second nearby" (T2+), "Defense reduces all of these." (both include `slayer.mechanic-damage-scale`). Ability text
  regenerated: "Every 4s it rips health out of you and heals itself by exactly what it took. Defense applies."
- New JUnit `ghoulLifeDrainIsABoundedShareOfStatedDamage` replays the playtest: at T3, 600 HP / 700 Def (shredded to
  525), Life Drain alone now takes ~125 s (was ~13 s) and is <20% of incoming damage; melee + aura + drain ~20 s with
  no healing (the reference's own 120 dps melee is the bulk). `SlayerMathTest` 9/9, `check.sh slayer` CHECK OK.
- Not verified live (no server/client this pass).

## Finish wave 2026-10-03
Worked the partial list, highest player impact first. `bash tools/check.sh slayer` **CHECK OK** (194 files);
`SlayerMathTest` **9/9** (javac + JUnit 5.11 launcher, data regenerated). Not run live (no server/client this pass).
- **Tactical Insertion** (`TACTICAL_INSERTION`, Blaze 5, ability `slayer_tactical` "Gorilla Tactics", 200 mana, 20 s):
  marks your spot, counts down 3 s, teleports you back (same world only), halves your current Health, and burns every
  monster within 3 blocks for 10% of ALL damage you dealt in those 3 s, spread over 6 s (counted from core's
  `SkyEvents.Damage` `finalDamage` at MONITOR). Numbers verbatim from the NEU page.
- **Voodoo Doll** (`VOODOO_DOLL`, Zombie 5, `slayer_voodoo`: 1,500/s for 10 s, 200 mana, 5 s) and **Jinxed Voodoo Doll**
  (`VOODOO_DOLL_WILTED`, `slayer_voodoo_jinxed`: 2,222/s for 12 s, 180 mana, 3 s): ray-traces the monster you look at
  (20 blocks), draws 8 converging arrow streaks, slows it (and anything within 1.5 blocks) and ticks the damage as
  MAGIC raw damage credited to you. Re-casting refreshes the timer instead of stacking. Other players' owned bosses
  are never targeted. The crafting book already listed all three recipes with no item behind them; their recipes are
  now generated here too (Jinxed's Rift berberis is substituted with Enchanted Iron, like the other Rift ingredients).
- **Chilled Spirit Water** (alchemy's `POTION_WISP_ICE;1`) is back in the Cinder Demonlord loot at the NEU odds
  (2.809 / 2.688 / 2.527%, x1/x2/x5 at T2-T4); the token weight absorbs it.
- Handoff corrected: the vampire-quest and Rift-gear gaps were already closed by the rift module; what is left there
  is only Motes as the fee currency.
- Still open: Wolf Fur Mixin is a flat +7 Magic Find (core reads cached Magic Find at roll time, so "one-hit kills
  only" needs a core hook), Grave Trolling wand healing and Mastiff's Vitality-per-heal (no heal hook in
  `VitalsService`), Soulflow items, attribute shards.

## Requests (things you need from core or another module — the integrator reads this)
1. **hub**: anchor `npc.slayer_master` inside a tavern, on a full block. Tag hub mobs: graveyard/crypt zombies
   `"slayer": "zombie"`, ruins wolves `"slayer": "wolf"` (entity fallback covers untagged ones meanwhile).
2. **combatzones / crimson**: tag Spider's Den spiders `spider`, End endermen `enderman`, Crimson blazes / nether mobs
   `blaze` so the quests count exactly the reference's mobs.
3. **rift (wave 2)**: tag vampire-quest mobs `"slayer": "vampire"`; expose Motes and the Rift health scale, then set
   `slayer.vampire-any-mob false` and retune `slayer.vampire-hp-scale` / `vampire-heart-percent`.
4. **mining**: register `PLASMA_NUCLEUS` (Plasmaflux Power Orb recipe waits for it; `/sb slayer info` lists it).
5. **enchanting**: book ids used in loot: `ENCHANTMENT_SMITE_6`, `_BANE_OF_ARTHROPODS_6`, `_CRITICAL_6`,
   `_MANA_STEAL_1`, `_SMARTY_PANTS_1`, `_FIRE_ASPECT_3`, `_ULTIMATE_REITERATE_1`, plus `_QUANTUM_3` and
   `_ULTIMATE_THE_ONE_4` for the bundles. Missing ids are simply skipped at roll time.
6. **weapons**: keep the dagger contract `ItemData.extra.attunement` / `ItemDef.extra.attunement` = ashen | auric |
   spirit | crystal (lower case). Daggers are detected by id suffix `_DAGGER`.
7. **alchemy**: mixins are standalone timed buffs here (member key `slayer.mixins` = id → expiry ms); if you build God
   Potions, read that map instead of re-implementing them.
8. **shops (Bartender)**: if you want the Bartender to sell mixins, run `Sky.commands().run(p, "slayer", "shop")`.
9. **core**: a per-player way to hide a sidebar section (players with many quests hit the 5-line limit).
10. **bank**: `Features.Bank.cookieActive` would replace my `Sky.requirements().check(p, "cookie")` calls.

## Known risks
1. **Never run live.** Every mechanic is code-reviewed and compiled only. Most likely rough edges: boss entity AI
   quirks (Blaze ground pull vs its flying AI, Enderman dodging arrows before core sees the hit, Enderman with a TextDisplay passenger, Wolf aggression), TextDisplay label
   offset over the core nameplate, and how strong the TUNE numbers feel. `/sb slayer spawn <p> <type> <tier>` +
   `/sb slayer kill <p>` exercise every boss and the loot path in seconds.
2. **Boss damage and mechanic damage are research numbers on a young server**: T4/T5 bosses (Immolate, Nukekubi
   doubling, Thermonuclear 75%, Void Glyph 100%, Fire Pillar = death) are meant to wipe under-geared players.
   `slayer.boss-damage-scale` / `slayer.mechanic-damage-scale` / `slayer.boss-hp-scale` tune all of it live.
3. **Ownership overlap**: I register slayer drops in `enable` (materials/pets fallback copies yield, as designed) but
   NOT slayer weapons (weapons' fallbacks stay live). If the integrator wants weapons to defer to slayer for those 31
   ids too, their behaviour must move with them.

## Playtest fix (2026-10-04, wave3): the boss no longer rises on top of a hurt player
Wave3 playtest: a new player's Ghoul Horror I bar filled at 59% Health in the middle of the Graveyard pack, the boss
spawned at once beside them, they died, and death fails the quest and keeps the 2,000-coin fee (faithful, kept). The
root cause was that the bar filling was the only trigger; the player had no say in WHEN the fight starts. Fixed:
- **Bar full -> the boss GATHERS** (state stays `SPAWNING`, in-memory `Quests.gathering`): a "<BOSS> STIRS / Step out
  of the fight and heal up" title + one chat line with the exact conditions. It rises (the usual 2 s summoning) once
  the player is at or above `slayer.spawn-ready-health` (75%) of max Health AND has not been hit by anything for
  `slayer.spawn-calm-seconds` (3), or after `slayer.spawn-max-wait-seconds` (60) whatever the state, so a quest never
  stalls. A player at full health who was not just hit sees no pause at all. `slayer.spawn-max-wait-seconds 0` restores
  the reference (instant spawn).
- It rises where the player stands now; the spot where the bar filled is used only if they are still within 6 blocks of
  it (so stepping away from the pack takes the fight away from the pack). It never rises in a SAFE / NO_MOBS zone or
  off-island (it waits; the action bar says "leave the safe area") - before, a player who died while SPAWNING could get
  the boss at their respawn point.
- While gathering: action bar "Boss stirs | Health 59%/75% | get out of the fight | rises in 42s", sidebar "The boss
  stirs... heal up", menu quest icon explains it and **click / `/slayer summon` calls it now** (same safety checks as a
  READY re-summon). The quest-start box gains one line telling new players this up front.
- "Hit" = any `SkyEvents.Damage` with a player victim and a non-null attacker other than themselves (MONITOR,
  ignoreCancelled; `Quests.lastHurt`, cleared on quit). Dying while gathering does not fail the quest (no fight yet).
- Pure rule `SlayerMath.readyToSummon(...)`; new JUnit `bossWaitsUntilTheSummonerIsReady` replays the playtest moment.
  `/sb slayer spawn` (admin) and READY re-summons stay immediate; `/sb slayer progress` filling the bar gathers like a
  real kill, so it can be tested over RCON.
- Verified: `bash tools/check.sh slayer` **CHECK OK** (194 files), `SlayerMathTest` **10/10** (javac + JUnit 5.11
  launcher). Not run live (no server/client this pass) - first live check: `/sb slayer start <p> zombie 1`, damage
  yourself, `/sb slayer progress <p> 999`, watch the action bar, step away, heal.
- New dials: `slayer.spawn-max-wait-seconds` 60 · `slayer.spawn-ready-health` 75 · `slayer.spawn-calm-seconds` 3.

## Playtest fix (2026-10-04, wave-4 loops)
`BossFight.attacking` tags boss mechanic damage (`hurt`, Kind.MOB) with `ability` + `slayer_mechanic`; alchemy's potion Dodge skips `ability` hits, so a God Potion (64% dodge) no longer dodges Life Drain / Pestilence (verified live: melee dodged, Life Drain every ~4 s). `SlayerCatalog.mobPlural()` fixes "Wolfs" in the quest box and menus. Playtest note: T3 Ghoul with Superior Dragon + an enchanted Sharp Shadow Fury (598 HP) dies at ~70% boss HP; the gear ladder before T3 gives too little HP (PLAYTEST wave-4 loops, open issue 4).

## Playtest fix (2026-10-04, wave-4 loops fixer): T3 Ghoul balance + named books
**Open issue 4 (major): T3 Ghoul unwinnable with Superior Dragon + an enchanted Shadow Fury.** Root cause in this
module: Life Drain and Pestilence have no reference numbers (TUNE) and were stacked ON TOP of the reference's stated
DPS, so a T3 Ghoul dealt 120 melee + 30 drain + 36 Pestilence = **186 raw/s against a tooltip "Damage: 120 per
second"** (x1.55; Enrage then doubled the melee). Research 04 puts Revenant T1-T3 in the early-dragon-armour stage.
Fixed:
- **The stated DPS is now the whole bill.** `SlayerMath.ghoulMeleeShare(tier, drainSeconds)` = 1 - drain share -
  Pestilence share (T2+), floor 25%; `ZombieFight.onStrike` scales the melee by it (Enrage x2 still on the melee part).
  Life Drain default **0.5 s** of DPS every 4 s (dial `slayer.zombie-drain-seconds` 1 -> 0.5, still heals the boss by
  what it took), Pestilence **15%** of DPS per second (was 30%), -25% Defense unchanged. T3 at default dials: melee 87/s
  + drain 60 every 4 s + Pestilence 18/s = 120/s. Applies to every Ghoul tier (T5 included: 1,740 melee + 300 drain/4 s
  + 360 Pestilence = 2,400).
- Tier tooltip: "Damage: 120 per second" then "of which: Melee 87 per second (x2 enraged) / Life Drain 60 every 4s /
  Pestilence 18 per second nearby / Defense reduces all of these."
- **Every tier tooltip and the Start confirm now say what the boss does to YOU** (`SlayerMenus.standingLines`):
  "Against you: ~21❤/s (630❈ Defense, 472 under Pestilence)" and "Your 598❤ last ~28s with no healing" (green >= 90 s,
  yellow >= 30 s, red below, + "Kill it first, out-heal it or bring more Health."). Ghoul = exact (melee + drain +
  Pestilence + shred); other slayers = melee + true damage (+ Immolate's first band for Blaze) after Defense / True
  Defense, flagged "Its abilities hit on top of this."; nothing for the Bloodfiend (Rift hearts). A floor, not a forecast.
- JUnit (`SlayerMathTest` **13/13**): `ghoulStatedDamageIsTheWholeBill` (melee + drain + Pestilence = stated DPS at every
  tier and drain dial, melee floor), `t3GhoulIsWinnableWithTheWaveFourKit` steps a T3 fight the way `ZombieFight` runs
  it (Enrage cycle, drain heals the boss): the playtest kit (598 HP, 630 Def, ~18 HP/s Life Steal III, 7.5k dps) now
  **wins in ~53 s with its lowest Health ~49%**; the OLD numbers kill it (reproduces the playtest); the same kit with no
  healing still loses; an under-geared 300 HP / 200 Def / 3k dps player still dies with the boss >90%.
  `ghoulLifeDrainIsABoundedShareOfStatedDamage` updated to the 0.5 s default.
- Not touched: the gear ladder itself (other modules) and the Graveyard mobs that join the fight.

**Open issue 12 (minor): enchanted books named "Enchanted Book".** `SlayerMenus.itemName(def)` (pure part
`SlayerMath.withEnchant`) reads the book def's `extra.enchant` / `extra.level` (how enchanting registers every level)
and the enchant's own name: "&9Enchanted Book (Smite VI)". Used by every slayer drop icon (`display`), the RNG meter
list / "Selected:" line / "RNG meter set to" message, the drop chat lines + RARE DROP! banner + broadcast
(`SlayerLoot.give`), recipe results and the statistics drop log. JUnit `booksSayWhichEnchant`.
**The Harvest Fair box ("+1 Enchanted Book" = Turbo-Wheat I) is the farming module's** - not changed here (request 11).

Verified: `bash tools/check.sh slayer` **CHECK OK** (194 files); `SlayerMathTest` 13/13 (javac + JUnit 5.11.4 launcher).
Not run live (no server/client this pass). First live check: open the Ghoul menu in Superior Dragon (tooltip lines),
`/sb slayer spawn <p> zombie 3` and watch melee hits land at ~87 raw (about 15 after 472 Defense), `LIFE DRAIN!` ~10.
`docs/COMMANDS.md` still lists the old drain default (1); it is regenerated outside this module.

Requests added:
11. **farming** (Harvest Fair reward box) and anyone else printing item names: an enchanted book's def name is just
    "Enchanted Book"; append the enchant from `def.extra.enchant`/`level` like `SlayerMenus.itemName` and shops'
    `Shops.variant` do. Better at the root: **core** could offer `Sky.items().displayName(id)` doing exactly that, so
    three modules stop carrying the same helper.

## Wave 5 (2026-10-06)
Verified: `bash tools/check.sh slayer` **CHECK OK** (197 files); `SlayerMathTest` **19/19** (javac + JUnit 5.11.4
launcher; 6 new tests); data regenerated with `py -3 tools/gen/slayer/gen_slayer.py` (only `slayers.json`, `items.json`
lore, `recipes*.json` and `docs/names/slayer.md` changed). **Not run live** (no server/client this wave): 3 new
self-tests are for the integrator.

### Done
- **Hunting shards in the boss loot** (`Features.Hunting.addShards(p, id, n, "slayer_boss")`, straight into the
  Hunting Box + Hunting XP, never an item). Generated: Revenant (Ghoul T3/T4/T5 = research w100/150/200 next to Foul
  Flesh's w2000 -> 0.804 / 1.2165 / 1.616%), Paragon (NEU's legacy `ATTRIBUTE_SHARD_SLAYER_DISCOUNT` 0.25% on Ghoul T4-5,
  Seraph T4, Demonlord T3-4; research 0.25 / 0.50% on Broodweaver T4-5; Fenris T4 0.1% TUNE; min level 5) - it is
  RNG-meter selectable (rngscore 250,000), Primordial (Broodweaver T5 0.71%), Burningsoul (Demonlord T2-T4 1.54 / 2.90 /
  5.27%, "Inferno Demonlord Shard" row, min level 3). Names/rarity are hunting's own (read from `data/hunting/shards.json`
  at generation time). The token absorbs their share, so every other displayed odd is unchanged. Without the hunting
  module (or with `slayer.shard-drops` off) shards leave the pool and the rest renormalises
  (`SlayerMath.chances(..., available)`), the drop/RNG menus hide them. Menu icon: a glowing prismarine shard,
  "Hunting shard: goes to your Hunting Box".
- **Hunting attributes**: Slayer Discount (Paragon, `value:slayer_discount`) applies after the mayor's Cut-Rate
  Contracts (`SlayerMath.cost(base, mayor, attribute)`: 50% + 5% = 47.5% off); Slayer Progress (Wolverine,
  `value:slayer_progress`) starts a quest that % full (never full, the start box says so); Cocoon Chance (Primordial,
  `value:cocoon_chance`) adds to The Primordial. Dial `slayer.hunting-attributes`.
- **The Primordial** (accessories' `THE_PRIMORDIAL` belt, `extra.hook = slayer`; worn via `Features.Equipment.worn` or
  carried per `Features.Accessories.has`): a slain slayer boss or your own miniboss is cocooned with 10% + Cocoon
  Chance (`Cocoon.java`): a cobweb display, 3 s of hatching, then the same mob hatches owned by you (health dial, 120 s
  lifetime). A hatched miniboss drops its own loot through core; a hatched BOSS is a plain owned mob (no abilities, no
  timer, no quest) whose death re-rolls the boss loot (`SlayerLoot.rollCocoon`: token + one weighted pick + dyes; no
  slayer XP, no RNG meter, no bits). A hatched monster never cocoons again. Statistics count "Cocooned monsters slain again".
- **Riftstalker fees in Motes** through `Features.Rift.spendMotes` as soon as the rift module provides it (all-or-nothing,
  "You need X Motes"); menus/confirm say "X Motes"; statistics track Motes spent; the coins-per-mote dial only applies
  while the fee is coins. Auto-Slayer pays the same way.
- **Soulflow**: requirement key `slayer:mixin:<MIXIN_ID>` (true while that mixin is active; soulflow request 1), so
  End Portal Fumes now does what it says - **+30% Overflow from Soulstream conversions** (the soulflow module reads the
  key); the +30 Intelligence stand-in is gone. **Vivid Darkness** (Last Horizon) now needs sneaking + in combat (hit
  dealt or taken within `slayer.gear.combat-seconds`) + Soulstream in the pool (`Features.Soulflow.soulflow > 0`; free
  when no soulflow module is loaded), and gained its **+200 Ferocity against endermen** as real extra ferocity strikes
  (`SlayerMath.bonusFerocityStrikes`: the strikes the +200 adds over the player's own Ferocity, cap 500). Set lore names
  the 2⸎/5s cost. Gloomlock's 15⸎ is charged by soulflow's AbilityGate (nothing to do here).
- **Wolf Fur Mixin = +7 Magic Find on one-hit kills** via core's per-target Magic Find hook
  (`addMagicFindModifier("slayer_wolf_fur")`): a hit on a full-health sky mob that kills it is remembered for that tick
  and the modifier pays +7 only for that kill. The flat +7 is gone.
- **Mixins at the Bartender**: the shops module's Bartender already lists all five (now that we register them). The
  Slayer Shop shows them as "Sold by the Bartender in the hub tavern" while `shops_bartender` is registered and sells them
  itself only when no Bartender stands (dial `slayer.mixins-at-bartender`).
- **Coldfused** (reforges request 22): ~~a Coldfused weapon with a Wisp pet out counts 2 hits per strike~~ - superseded in
  the second pass below (it doubled on top of pets' x2).
- **Weapons request 11**: weaponless dagger follow-ups (Burning Vengeance TRUE, Soul Howl FEROCITY) take the attunement
  of the dagger in hand, so they land at full damage instead of 1%; only swings (and core's ferocity repeats, which carry
  the weapon) count toward the shield's 8.
- **Grave Trolling "+50% wand healing"**: after a successful `*wand_heal*` cast (AbilityUsed) with 3+ Ghoul pieces we
  heal half the wand's `extra.healPerSecond` again for its `healSeconds`.
- **Magic Find** for boss loot is now core's per-target value (`Sky.loot().magicFind(p, bossDef)`), so bestiary and
  "vs" modifiers apply to slayer drops too.
- **Settings** (menus request 13): `combat.slayer_alerts` off hides the spawn/stir titles and sounds (chat stays).
- **Bank**: Boon Cookie checks use `Features.Bank.cookieActive` (bits, mixins); bits reason stays `slayer: ...` so the
  bank's fame multiplier applies.
- **Pets**: Quick Study (`bingo_fast_learner`, "more Skill XP and Slayer XP") adds its % to boss Slayer XP.
- **Rift request 1**: `slayer.vampire-heart-percent` default 4 -> 10 (rift: max HP / 10 base Rift hearts).
- `PLASMAFLUX_POWER_ORB` recipe is no longer late (mining registers `PLASMA_NUCLEUS`; request 4 closed).
- Admin: `/sb slayer cocoon <p> <type> <tier|mini>` (forces a cocoon by the player), `/sb slayer mixin <p> <ID> [hours]`
  (and prints the `slayer:mixin:` answer); `info <p>` shows fee currency, Primordial/attributes, mixins, Vivid Darkness.

### Done - second pass (same day, continuation)
Verified: `bash tools/check.sh slayer` **CHECK OK** (197 files); `SlayerMathTest` **22/22** (javac + JUnit 5.11.4 launcher;
3 new tests: `firePillarIsNotDoubledTwice`, `webbedCellsWeakensVenomVitality`, `everySlayerMobNamesItsFamilies`);
`gen_slayer.py` re-run: only `mobs.json` changed (each mob gained `extra.mobTypes`, everything else byte-identical when
that key is stripped), `docs/names/slayer.md` unchanged; no control characters in any slayer source. **Not run live.**
- **Fire Pillar double-doubling fixed.** The pets module already doubles a strike on `slayer_fire_pillar` for every Wisp
  (Drophammer / Icehammer, its `pillar` source) and folds Coldfused into that same x2 (`max(mult, coldfused ? 2 : 1)`,
  which is what makes a Glacial Wisp - whose own perk does not double - double with Coldfused). We ALSO set 2 per
  Coldfused strike in `onDamaged`, which runs before pets' Damage handler, so Coldfused + Wisp counted **4** hits (a pillar
  in 2 strikes). We now set `SlayerMath.PILLAR_HITS_PER_STRIKE` (1) and leave "twice as fast" to pets. Dial
  `slayer.coldfused-pillar-hits` removed.
- **Coldfused's other half** (research 01: "breaking one grants +30 True Defense and +1.15x damage for 60s", was lore-only;
  reforges hand-off line 94 left it to us): the strike that topples a pillar is remembered (`BlazeFight.pillarBreaker`);
  if it carried a Coldfused weapon with a Wisp out, `SlayerGear.coldfusedBreak` gives +30 ❂ (stat provider `slayer_gear`)
  and x1.15 on every non-TRUE outgoing hit (modifier `slayer_gear_out`) for 60 s, with a "COLDFUSED!" flash. Three dials.
- **Tarantula pet "Webbed Cells"** (pets request 10, `tarantula_webbed_cells`; research 06: "Vitality reduction is 30% less
  effective against you"): Paralytic Venom's Vitality cut is `SlayerMath.venomVitality(pct, webbed)` = pct x (1 - a/100),
  a read live from `Features.Pets.abilityValue`. The Speed cut and T5's Absorption strip are unchanged.
- **Monster families on every slayer mob** (accessories request 3, weapons request 8): the generator copies `extra.types`
  into `extra.mobTypes` minus the non-family tags (`egg_sac`, `enderman`, `wolf`). Before, accessories inferred families
  from the entity type, so the Egg Sac (a SLIME) and Brood Bat had none and the Blood Gargoyle (a VEX) was not undead -
  "vs arthropod/undead" accessories missed them.
- `/sb slayer info <p>` also prints: Wisp out, Coldfused bonus on/off, Webbed Cells % and the resulting T5 venom.
- Checked and already fine (no change): power-stone drops (Furball is in the Fenris T3/T4 NEU tables, the only one a
  slayer boss page lists); Raw Soulstream has no slayer-boss source in NEU/research (soulflow's engines are the source);
  the Runebook and the Soulstream items/engines are skills' / soulflow's / minions'; the Abiphone "Maddox" contact is
  extras' (it runs `/slayer`); Aatrox's Pathfinder, Slashed Pricing and Slayer XP Buff are read here.

### Still left
- Bloodfiend Motes wait on the rift module providing `Features.Rift` (request 12) - re-checked this pass: still no
  `Sky.provide(Features.Rift...)` anywhere.
- Mastiff's 5-Vitality cost (enchanting's Vitality pool is module-local and spent only by ability ids; Mastiff's heal is
  a passive on being hit, not an ability - request 16).
- Mixins brewed INTO God Potions at the stand (alchemy's route; alchemy request 12 - the `slayer:mixin:` key answers
  "is it active"; for the write side alchemy can dispatch `/sb slayer mixin <player> <MIXIN_ID> <hours>` from the console
  until `Features.Slayers` grows a `mixins` / `applyMixin` pair, which is an api change for core).
- Life Tap adding Overflow instead of mana (soulflow request 1, optional) needs a `Features.Soulflow.addOverflow`.
- First live checks: `/sb slayer cocoon <p> zombie 3` then kill it (loot-only re-roll in chat); equip The Primordial and
  `/sb slayer spawn <p> spider 1` + `kill` a few times; `/sb slayer mixin <p> END_PORTAL_FUMES_MIXIN` then
  `/sb soulflow <p> combat off`; `/sb slayer loot zombie 5 9` lists `SHARD_REVENANT ~1.6%`.
  Second pass: `/sb slayer spawn <p> blaze 2`, hit it under 50%, break the pillar with a Coldfused dagger and a Wisp out
  (4 strikes, "COLDFUSED!" flash, `/sb slayer info <p>` shows the bonus on); with a Tarantula pet out, `/sb slayer spawn
  <p> spider 5` and watch Vitality on the stats page drop less than 75%.

### Requests (wave 5)
12. **rift**: `Sky.provide(Features.Rift.class, ...)` (motes / spendMotes / addMotes over `RiftData.motes`) - the
    Bloodfiend fee switches to Motes by itself.
13. **soulflow / integrator**: Vivid Darkness is now gated on Soulstream in the pool, so `soulflow.charge-owner-gated-drains`
    can be turned on (the 2⸎/5s drain).
14. **shops**: give the Bartender's five `*_MIXIN` lines the requirement `slayer:<type>:8` (zombie / spider / wolf /
    enderman / blaze): today anyone can buy one, and only drinking it checks the level.
15. **hunting**: the boss loot shows your shard names verbatim; "Revenant" is the reference boss's proper noun (our boss is
    the Ghoul Horror) - if you rename `SHARD_REVENANT`, re-run `gen_slayer.py` (it copies your names).
16. **enchanting**: a `Features`-level Vitality spend (or a requirement-style hook) would let Mastiff consume its 5 Vitality.
17. **pets (FYI, second pass)**: you own the Fire Pillar x2 for Wisps and Coldfused (`PetEffects` `pillar`); slayer now
    always sets 1 per strike in `onDamaged`, so keep doing it - if you ever drop the Coldfused part of your `max(...)`,
    tell us and we put it back here. Coldfused's +30 True Defense / x1.15 / 60 s after a break is ours.
18. **accessories (FYI)**: every slayer mob now carries `extra.mobTypes`, so `MobTypes` takes our list verbatim (Egg Sac
    `arthropod`, Brood Bat `animal`, Blood Gargoyle `undead`, Quorrix `infernal/skeletal/wither`); your accessories.md
    still lists "slayer: The Primordial" under hooks no owner applies - it has been live here since the first wave-5 pass.
19. **core (optional)**: `Features.Slayers.mixins(Player)` (id -> expiry ms) + `applyMixin(Player, id, millis)` for
    alchemy's God Potion route (alchemy request 12); we implement them the moment they exist.

### Known risks (wave 5)
- A hatched boss is the boss mob def with no BossFight: it melee-hits at its def damage and has no mechanics. If that
  feels too cheap or too strong, `slayer.cocoon-health-percent` / `slayer.primordial-chance` tune it live.
- Wolf Fur's one-hit check reads the mob's health in the Damage event (before core applies the hit); a module that
  heals or shields a mob after that event could make a non-lethal hit count (harmless: +7 MF on one roll).
- Vivid Darkness' "in combat" is tracked here (8 s) and soulflow tracks its own (`soulflow.combat-seconds`, 8 s): keep
  the two dials equal or the bonus and the drain can disagree for a few seconds.


## Wave-5 regression fix (2026-10-06): the slayer master's intro plays once
Playtest note (open #8): Brannoc repeated his three intro lines on every click until the first quest was bought
(players read the tier pages first, so they heard it 3-4 times). New `SlayerData.introHeard` (field ADDED, old
saves read false); `SlayerModule.introOnce(d)` returns the intro only while `questsStarted == 0 && !introHeard` and
marks it heard when it starts (walking off mid-intro still counts). Every later click opens the board. Self-test
`slayer.intro-once`. `check.sh slayer` / `all` CHECK OK. Not seen in a client.

## Real-game audit lane 5 (2026-10-06): rows C1-C8, C18
Source rows: `docs/AUDIT_REAL_GAME_2026-10-06.md` section 3; values from `SkyblockMoney/work/combat.md` and research 04
2.5-2.9. All data changes are in `tools/gen/slayer/gen_slayer.py` (`MINLVL`, `SHARD_DROPS`, new `OWN_POOL`,
`LOOT_FIX`, `LOOTSHARE_ONLY`) and regenerated; nothing in `data/slayer/` was hand-edited.
- **C1** ERE Artifact Upgrader (Ender Relic Refiner) drops from Enderman **6** (was 7).
- **C2/C19** Paragon shard: 0.25% on every T4, 0.5% on every T5, slayer level 5, **own pool** (`independent: true`,
  rolled like the dyes, on top of the one weighted pick). Wolf T4 0.1 -> 0.25, Ghoul T5 0.25 -> 0.5, Inferno T3 removed.
  Independent rolls now also get the Aatrox rare perk and the RNG-meter multiplier (`SlayerMath.independentChance`
  6-arg), skip shards while hunting is absent, and a natural drop of the selected item resets the meter.
- **C3** Inferno distillates: only Gabagool reaches the summoner (T2 5.19% x4, T3 4.87% x10-14, T4 4.43% x24-32,
  Blaze 3). Blaze Rod / Glowstone / Magma Cream / Nether Wart distillates are `lootshareOnly` (new LootEntry field):
  they are only in a helper's lootshare roll (`SlayerMath.chances(..., lootshare=true)`), with the shared-slot
  quantities 4 / 10-14 / 24-32; the drops menu labels them "Lootshare only". Blaze token weights rose accordingly.
- **C4** dial `slayer.rng-boost` default 1 -> **2** (selected drop ramps 1x -> 3x). A saved override would beat it;
  self-test `slayer.audit-2026-10-06` reports one.
- **C5** Seraph T4 Summoning Eye 0.6734 -> 0.565%, Twilight Arrow Poison 15.15 -> 12.7% (x60-64). Cause: NEU lists a
  drop's chance at its own unlock level, the wiki at max level, which is what our weights mean.
- **C6** Broodweaver webs T3 28-48 -> 16-32, T4 52-64 -> 32-48 (research 04 2.6; ~40 a T4 kill).
- **C7** Ghoul T5 Foul Flesh 16.16 -> 13.6082% (w2000 = Viscera's weight); Viscera quantity 1 -> 1-2 (research 04).
  The Revenant shard at T5 follows Foul Flesh's weight: 1.6162 -> 1.3608%.
- **C8** the reference renamed Shard of the Shredded to **Shredded Sinew** - which was our name - so ours is now
  **Shredded Tendon** (`docs/names/slayer.md`). `materials/items_combat.json` still carries "Shredded Sinew" as its
  fallback name for the same id (slayer registers first, so ours shows; the materials lane should rename it).
- **C18 not changed**: no Zombie-7 spawn discount exists in research 04's level-reward table, the NEU repo or our
  data; the 4% (x0.96) in combat.md matches a **Paragon attribute at level 8** (0.5%/level, already implemented in
  `SlayerMath.cost`), so it is most likely the player's attribute, not a level reward.
Tests: `SlayerMathTest.realGameAudit20261006` (+ updated shard / banned-name tests): **23/23 pass** (javac + JUnit
5.11 launcher). Self-test `slayer.audit-2026-10-06` added; `slayer.hunting-shards` now expects the Paragon in its own
pool. `bash tools/check.sh slayer` CHECK OK. Not run on the server.
