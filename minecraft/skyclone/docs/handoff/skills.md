# skills — hand-off

Package `com.skyclone.mod.skills` (entry `SkillsModule`), data `resources/data/skills/`, generator
`tools/gen/skills/gen_skills.py`, unit test `plugin/src/test/java/com/skyclone/mod/skills/SkillTableTest.java`.

## What works (verified how)

| Feature | Verified by |
|---|---|
| `SkillService` provided: addXp (Wisdom applied, `SkillXpGain` fired, mutable/cancellable), level, xp, progress, needed, average, cap, setCap, openMenu | `check.sh skills` = CHECK OK; self-test `skills.service-provided` |
| All 11 API skills with the reference XP curves, max levels, coin / SkyBlock XP / stat rewards and perk values **generated** from `research/data/skills.json` (no numbers typed by hand) | unit test (7 tests, all green, run locally against the shipping JSON) + self-tests `skills.table-valid`, `skills.research-numbers` (31 research-02 numbers: Farming 111,672,425 XP to 60, 242 Health at 60, Combat +210% at 60, Foraging 100 Strength at 57, coin totals 27,259,100 / 17,259,100, SB XP 1,000 / 910 / 700, ...) |
| Level caps: Farming, Taming, Foraging start at 50 (dials), raised with `setCap`; XP keeps banking past the cap, so raising the cap pays the levels out at once | unit test `capsClampLevels`; code path shared with admin `setcap` |
| Level-up: genre box `SKILL LEVEL UP Farming IV➜V` + REWARDS (perk sentence with old➜new value, stats, unlocks, coins, SkyBlock XP), level-up sound, coins to purse (each level paid once ever), SkyBlock XP via `Features.SkyLevel.award(p, "skill:<SKILL>:<level>", xp, ...)`, one `SkillLevelUp` event per level. Jumps of more than 3 levels (dial) collapse into one combined box | rendered sample boxes printed with a local harness (all 11 skills, see "Known risks" for what was NOT seen in a client) |
| StatProvider `skills`: every level's stats (Health, Defense, Crit Chance, Strength, Intelligence, Ability Damage, Pet Luck) + perk stats (Farming/Mining/Foraging Fortune, Treasure Chance) | unit test `statsAccumulateAndPerkIsSeparate` |
| Combat perk ("Veteran"): DamageModifier `skills_warrior` priority 150, additive +4%/level to 50 then +1%/level (210% at 60); outgoing player hits on non-players only, melee + projectile (magic behind a dial) | code review; formula numbers from the table |
| Enchanting perk ("Arcanist"): +5%..300% vanilla experience orbs (`PlayerExpChangeEvent`) | code review |
| Combat XP from `SkyEvents.MobKill.combatXp` (listener at HIGH so other modules can scale it first), killer only, none on the private island (reference rule; dial) | code review |
| Social XP: every minute, 1 XP visiting someone's private island, 2 XP hosting, 10 XP per new unique guest (research 02 §2.4.8, all dials). Needs a real `Features.PrivateIsland` | code review |
| Action-bar popup `&3+4.5 Farming (1,234/5,000)` on every gain (per-player toggle) | code review |
| Menus: `/skills` "Your Skills" (research 08 §11.3 slots, Skill Average in slot 4, every skill with progress bar + next level's rewards + `&eClick to view!`, Dungeoneering shows Catacombs level, XP-popup toggle at 50, Go Back to the SkyBlock Menu at 48); per-skill road "Farming Skill": the 25-slot zig-zag snake, pages of 25 (Farming 3 pages), lime done / yellow current with progress / red locked / red "above your level cap", stack size = level, rewards in every pane, level-cap info icon | self-test `skills.menus-build` builds both for an empty and a half-way profile, every page of every skill |
| Requirement prefix `skill`: `skill:<skill>:<level>` and `skill:average:<n>` | self-test `skills.requirement-resolver` |
| Tab widget `skills` (priority 110): last trained skill with % and Skill Average | code review |
| SkyBlock XP catch-up: on join, levels reached while no real SkyLevel service was installed are awarded (keys are one-time, so this never double-pays) | code review |

## What is partial / not built

(Updated wave 5, 2026-10-06. Items that moved out of this list are under "Wave 5" below.)

- **Rune Sack is still empty** (storage's `RUNE` family has `contents: []`) - request 9, unchanged. Runes stay in
  the inventory meanwhile; nothing is lost.
- **Hunting perk "Snarewright" (Charm Chance %) is read by nobody**: there is no hunting module yet and no Charm stat.
  Hunting Fortune per level IS on the stat sheet. Request 14.
- **Runebook rarity does not rise per digit** (accessories' item; the Wisdom does - see wave 5). Request 15.
- **Island Decorator (Social road)**: the 11 Social unlock lines ("Buy Parkour Blocks from the Island Decorator"...)
  are still text; no module sells island decorations / minigames. Request 16 (island/hub).
- **Pet Trainer deviations** (wave 5, see below): the pet is not taken away while it trains (the pets API cannot
  lock a pet), and the Owl's "Training Refunds" / "Efficient Trainer" perks are not wired (the pets API cannot tell
  whether a player OWNS an unsummoned Owl). All session numbers are TUNE (the research has none except Turbo = 500 Bits).
- **Rune fusion never fails.** Research 02 says Runecrafting XP comes from "attempting a fusion (success or fail)" but
  gives no success odds; fusion here always succeeds. Runecrafting XP amounts (fusion, Runic kill, boss) and the
  Runic spawn chance are still TUNE dials (no numbers in research or the NEU repo).
- The two Hub NPCs are placed by this module next to existing Hub anchors because the Hub has no anchors for them
  (requests 10, 13). Not seen in a client: menus, the box, the popup, rune particles, the Pet Trainer, the NPC spots.
- (Moved out of this list in wave 3, done by their owners: Distiller/Beastkeeper perks - alchemy/pets; Carpentry XP -
  crafting; Taming XP - pets; skill-gated warps; reforge/power stone gates. Done in wave 5: Hunting skill (core),
  Foraging max 57 (core), purple Runic names (core, request 11), Runic Pedestal NPC (here, request 10 fallback),
  furniture lines (crafting builds furniture; the road now names its items).)

## Commands added (player + admin)

| Command | What |
|---|---|
| `/skills [skill]` | "Your Skills", or straight to one skill's road on the page of your current level |
| `/sb skill <player> <skill\|all> addxp <n>` | full pipeline (Wisdom + `SkillXpGain` event), like a real gain |
| `/sb skill <player> <skill\|all> rawxp <n>` | adds exactly n XP, no Wisdom/event |
| `/sb skill <player> <skill\|all> setxp <n>` / `setlevel <n>` / `setcap <n>` | setlevel raises the cap when needed so the level sticks; `1.5k`, `2m` accepted |
| `/sb skill <player> info` / `reset [skill\|all]` / `maxall` | reset keeps the "already paid" records, so re-levelling never pays coins twice |
| `/sb skill table <skill> [level]` | prints the table (no player needed, good over RCON) |
| `/sb skills ...` | alias of `/sb skill ...` |
| `/runes` | the Runic Pedestal (wave 3) |
| `/sb runes list` | every rune type: key, slot, required level, rarity, source |
| `/sb runes give <player> <rune> [tier] [amount]` | e.g. `/sb runes give Steve BLOOD_2 2 4` (key or item id) |
| `/sb runes runic [player]` | makes up to 3 sky mobs within 16 blocks Runic (to test drops and XP) |
| `/sb runes test-fuse <player>` | gives 2 Blood Rune I to try a fusion |
| `/pettrainer` | the Pet Trainer (wave 5); also Your Skills -> Taming -> slot 46, or the Hub NPC |
| `/sb skill <player> trainer [info\|finish\|clear]` | inspect / finish now / drop a training session (wave 5) |
| `/sb skill <player> runebook [n]` | show or set the Runebook's Runic-kill count (wave 5) |
| `/sb skillnpc [runic-pedestal\|pet-trainer] [info\|here\|auto\|respawn]` | where skills' Hub NPCs stand; `here` pins one to your position (dial), `auto` puts it back next to its anchor (wave 5) |

## Dials added

`skills.xp-multiplier` (1.0) · `skills.xp-multiplier.<skill>` (1.0 each) · `skills.default-cap.<skill>`
(Farming/Taming/Foraging 50, rest = max) · `skills.cap-gates` (true; false = everyone can reach max) ·
`skills.coin-rewards` (true) · `skills.coin-multiplier` (1.0) · `skills.sbxp-catch-up` (true) ·
`skills.level-up-sound` (true) · `skills.flash-ticks` (40) · `skills.bulk-levelup-box` (3) ·
`skills.combat-xp` (true) · `skills.combat-xp-private-island` (false) · `skills.perk.warrior` (true) ·
`skills.perk.warrior-magic` (false, TUNE) · `skills.perk-scale` (1.0) · `skills.perk.conjurer` (true) ·
`skills.tab-widget` (true) · `skills.average-fractional` (false) · `skills.social-xp` (true) ·
`skills.social.visit-per-minute` (1) · `skills.social.host-per-minute` (2) · `skills.social.unique-guest` (10).

Wave 3: `skills.hide-missing-enchant-unlocks` (true) · `skills.runes.effects` (true) · `skills.runes.level-gate` (true) ·
`skills.runes.fuse-xp-scale` (1.0, TUNE) · `skills.runes.runic-chance` (0.01, TUNE) · `skills.runes.runic-health` (4) ·
`skills.runes.runic-damage` (2) · `skills.runes.runic-xp` (20, TUNE) · `skills.runes.runic-xp-per-level` (2, TUNE) ·
`skills.runes.runic-drop-window` (5) · `skills.runes.runic-excluded-islands` (private_island,catacombs,kuudra,rift) ·
`skills.runes.boss-xp` (5, TUNE).

Wave 5: `skills.runebook` (true) · `skills.average-includes-carpentry` (true, research 02) ·
`skills.average-includes-hunting` (auto | true | false; auto = only once a module provides `Features.Hunting`) ·
`skills.trainer.enabled` (true) · `skills.trainer.cost-scale` / `xp-scale` / `time-scale` (1.0, TUNE) ·
`skills.trainer.turbo-bits` (500, research 05) · `skills.trainer.same-pet` (true) ·
`skills.npc.runic-pedestal` / `skills.npc.pet-trainer` (true) · `skills.npc.runic-pedestal-at` / `skills.npc.pet-trainer-at`
("" = auto; "x,y,z[,yaw]" in the Hub).

## Self-tests added

`skills.table-valid` · `skills.research-numbers` · `skills.service-provided` ·
`skills.requirement-resolver` · `skills.menus-build` · `skills.unlock-items-exist` (every reforge
stone / power stone / recipe a skill level names is a registered item — fails if reforges/accessories
did not register them all) · `skills.unlock-enchants-exist` (every enchant Enchanting levels name is
registered — enchanting's) · `skills.access-islands-exist` (every island a skill level grants access
to is registered). Wave 3: `skills.runes-valid` (34 rune types × 3 tiers registered BY skills, particles
resolve, fusion pays XP, a level-0 player can get a Runic drop).
Wave 5: `skills.trainer-sessions` (6 sessions at Taming 10..60, XP rising, value improving, the Taming road names
exactly these) · `skills.trainer-payments` (only Free is free, only Turbo costs Bits, a Pets service exists) ·
`skills.npcs-placed` (each NPC registered, or yielded to another module's; in auto mode a free spot exists once the Hub
chunk is loaded) · `skills.npc-names` (no other NPC shares our two names) · `skills.runebook` (digit maths; RUNEBOOK
registered; Accessories service present) · `skills.furniture-unlocks` (37 furniture pieces on the Carpentry road, all
registered as `furniture` by crafting) · `skills.average-rule` (Social/Runecrafting out, Carpentry/Hunting follow
their dials). `skills.unlock-items-exist` now also checks the furniture ids.

`skills.unlock-enchants-exist` now passes while `skills.hide-missing-enchant-unlocks` is on (the missing ones are hidden
from players); turn the dial off to see the list again. The last three check OTHER modules' registrations: a FAIL there lists the missing ids and names the owner.

## Requests (things you need from core or another module — the integrator reads this)

1. **Island builders / worlds — warp requirements** (the reference's skill gates): `barn` `skill:farming:1`,
   `mushroom_desert` `skill:farming:5`, `gold_mine` `skill:mining:1`, `deep_caverns` `skill:mining:5`,
   `dwarven_mines` + `crystal_hollows` `skill:mining:12`, `spiders_den` `skill:combat:1`, `the_end`
   `skill:combat:12` (its Bruiser Hideout zone `skill:combat:20`), `crimson_isle` `skill:combat:22`,
   `park` `skill:foraging:1`. The skill menus already advertise these.
2. **reforges / accessories**: `resources/data/skills/stone_unlocks.json` maps 96 item ids to
   `{skill, level, kind}` (reforge stones need `skill:mining:N`, power stones `skill:combat:N`). Read it with
   `Sky.plugin().getResource("data/skills/stone_unlocks.json")` and put the key in the item's `requirements`
   (or check it when applying).
3. **enchanting**: the Enchanting level that unlocks each enchant at the table is in `skills.json`
   (`ENCHANTING.levels[].unlocks`, type `enchant`): gate with `skill:enchanting:N`. Enchanting XP per research:
   `3.5 × levelsSpent^1.5`. Please register enchant ids as the lower_snake of the name (`life_steal`,
   `ultimate_jerry`...) so these lines show your names.
4. **garden / farming** (contest medal shop): call `SkillService.setCap(p, FARMING, 51..60)`; research 02
   §2.4.1 has the medal + ticket cost per step. **pets** (pet collector): `setCap(p, TAMING, +1)` per pet handed in;
   Taming XP per pet XP gained is yours; perk: pet XP × (1 + tamingLevel/100), exp share = 0.2 × tamingLevel %.
   **foraging / collections**: `setCap(p, FORAGING, ...)` at the log milestones. **alchemy**: brewed potion
   duration × (1 + alchemyLevel/100). **crafting**: Carpentry XP = 3% of the ingredients' NPC sell value.
5. **fishing**: sea-creature names in the Fishing rewards are looked up as mob ids `squid`, `sea_walker`,
   `frog_man`... (lower_snake of the reference name; `sea_`/`fishing_`/`sc_` prefixes are also tried).
   Unknown ones show as "N new Sea Creatures".
6. **menus**: the brief asks menus for a skills tab widget too; this module already draws `skills` — turn
   `skills.tab-widget` off if yours replaces it. SkyBlock Menu → Skills should call
   `Sky.service(SkillService.class).openMenu(p)` (or `/skills`).
7. **progression (SkyLevel)**: award keys from this module are `skill:<SKILL>:<level>` (e.g. `skill:FARMING:5`).
   Do not award skill levels yourself, or they pay twice.
8. **API owner**: add `HUNTING` to `SkillType` if Hunting is wanted, and correct `FORAGING.maxLevel` to 57.
9. **storage** (wave 3): fill the `RUNE` sack's `contents` with every item tagged `rune` (102 ids: `<KEY>_RUNE`,
   `<KEY>_RUNE_2`, `<KEY>_RUNE_3`; `/sb runes list`, or `data/skills/runes.json`).
10. **hub** (wave 3): a Runic Pedestal NPC/block in the Mining District (reference NPC "Dusk" — rename it) that opens
    the pedestal (player runs `/runes`'s behaviour). Until then: Your Skills → Runecrafting (slot 50) or `/runes`.
11. **core (nameplates)** (wave 3): a way to tint one mob's nameplate purple (Runic mobs). They carry the PDC key
    `skyclone:runic` (byte 1), so the nameplate renderer could read it.
12. **slayer** (FYI, wave 3): skills now registers the 12 slayer rune ids FIRST (ModuleList order), so the
    "Runecrafting arrives with a later update" trophies in `items_fallback.json` are skipped; the drops are unchanged
    and are now usable runes (tier I). Nothing to do unless you want to delete those fallback entries.

13. **hub** (wave 5): two anchors would let core place skills' NPCs like every other: `npc.runic_pedestal` (Mining
    District, beside the Blacksmith) and `npc.pet_trainer` (anywhere sensible; the reference Fann stands in the Hub).
    The moment either anchor exists in the hub layout, skills registers its NPC AT it (no code change here). If you
    would rather register the NPC yourself, give it an id or title containing `runic_pedestal` / `runic pedestal` /
    `pet_trainer` / `pet trainer` and skills stands aside; open `/runes` / `/pettrainer` on click
    (`Sky.commands().run(p, "runes")`). Until then skills places both next to `npc.blacksmith` / `npc.pet_sitter`.
14. **hunting** (wave 5, whoever builds it): grant XP with `SkillService.addXp(p, SkillType.HUNTING, xp, "hunting:<what>")`.
    The Snarewright perk = Charm Chance % (extra shard per catch) at Hunting level L is
    `data/skills/skills.json` `skills.HUNTING.levels[L-1].perk` (0.04 per level, 2.0 at 50). Providing
    `Features.Hunting` also makes Hunting count in the Skill Average (dial `skills.average-includes-hunting` = auto).
15. **accessories** (wave 5): the Runebook's Runecrafting Wisdom is now live (this module counts Runic kills while
    `Accessories.has(p, "RUNEBOOK")` and adds +1 Wisdom per digit through its stat provider - do NOT add it again).
    Its "rarity rises per digit" part is yours if you want it; the count is `/sb skill <p> runebook` (member data
    `skills.runebookKills`).
16. **island / hub** (wave 5): the Social road's Island Decorator (reference "Amelia") - a shop for island decorations
    and minigames (parkour blocks, tic tac toe, four in a row, rock paper shears, island NPCs, showcase block, egg hunt,
    social display) gated by `skill:social:1..10`, plus island ranks (Social 9) and island naming (Social 20). The hub
    has an `npc.decorator` anchor. Until someone builds it the lines stay text.
17. **pets** (wave 5): `Features.Pets.owns(p, type)` (or a "pet in storage" read) would let the Pet Trainer apply the
    Owl's "Training Refunds" (1% off per 1M coins spent on sessions, max 5%; the spend is already tracked in
    `skills.trainerSpent`) and "Efficient Trainer", which the reference grants while the Owl is merely owned.

**Status (wave 5 check):** 8 done (core-wave5: HUNTING in SkillType, Foraging 57). 10: no hub anchor yet - fallback
built here (request 13). 11 done (core paints `skyclone:runic` plates purple; the per-hit re-tint listener was removed
here). 9 still open (Rune Sack contents empty).

**Status of 1-7 (wave 3 check):** 1 done (farming, combatzones, crimson, foraging, mines/hollows gate their warps);
2 done (reforges puts `skill:mining:N` on stones, accessories `skill:combat:N` on power stones); 3 enchanting gates
the table, but ~34 Enchanting-road enchant ids are still unregistered — those lines are now hidden from players
(dial above) rather than promising enchants that do not exist; 4 done (pets: Taming cap, XP and perk; alchemy:
Distiller; crafting: Carpentry XP).

## Wave 3 (2026-10-04)

**Runecrafting is a playable skill now** — it had no XP source anywhere. New: `Runes.java`, `RunicPedestal.java`,
`data/skills/runes.json`, `tools/gen/skills/gen_runes.py`, test `RunesDataTest.java`.

- **34 rune types × tiers I-III = 102 items**, generated from the NEU repo (required Runecrafting level, what it goes
  on, rarity, colour, skull texture, slayer/event vs Runic source). Tier I keeps the reference id (`BLOOD_2_RUNE`), so
  every slayer boss's existing rune drop is now a real, usable rune. Our own descriptions; 5 renames (names doc).
- **Runic Pedestal** (`/runes`; Your Skills → Runecrafting → slot 50): click an item and a rune in your inventory to
  **apply** (weapons / bows / boots / chestplates / hoes, by item category; one rune per item, a new one replaces the
  old, shown as `◆ Blood Rune II` under the stats), or two identical runes to **fuse** I+I→II, II+II→III for
  Runecrafting XP (Common 10 · Uncommon 20 · Rare 40 · Epic 80 · Legendary 160, ×2 into III). Both gated by
  Runecrafting ≥ the rune's level. Items never leave the inventory while "on" the pedestal: the slot is remembered and
  re-checked at the click, so closing, moving or dropping can never dupe and nothing has to be returned on close.
- **Rune effects** (cosmetic, visible to everyone): weapon runes burst on each kill (Lightning sometimes strikes a
  lightning effect, Music plays a note), bow runes trail the arrow (Spiral runes spiral), boots runes trail your steps,
  chestplate runes orbit you, hoe runes burst on every harvested crop (`SkyEvents.Gather` FARMING).
- **Runic mobs**: 1% (dial) of ordinary sky mobs spawn Runic — 4× health, 2× damage to players, purple particles —
  and always drop a rune (rarity-weighted among Runic-source runes needing at most your level + 5) plus 20 + 2×level
  Runecrafting XP. Purple name (recoloured after each core repaint) and purple sparkles, re-tracked after chunk
  loads and restarts. Never bosses, slayer mobs, sea creatures, owned summons, passive mobs, or excluded islands.
- Boss and slayer kills give a little Runecrafting XP (research: "and bosses").
- Enchant unlock lines for enchants nobody registered are hidden (see Requests status).
- Verified: `check.sh skills` CHECK OK; unit tests 11/11 (4 new: the shipping rune table, slayer drop ids are runes,
  new players can earn usable runes, slot/category mapping); all 25 particle names checked against the Paper 26.2
  jar. NOT verified live — no server or client was started in this phase. First live check: `/sb selftest` →
  `skills.runes-valid`, then `/sb runes test-fuse <you>`, `/runes`, and `/sb runes runic <you>` near a mob.

## Known risks

0. (wave 3) Runic mobs are picked by an `EntitySpawnEvent` listener 2 ticks after spawn; a module that re-sets a mob's
   max health later than that undoes the 4×. The purple name is a colour swap on core's rendered nameplate (the code
   right before the mob's name); core repaints on every health change and the module puts the purple back a tick
   later, so a hit may flash red for one tick. A clean fix is Request 11.

1. **The cross-module unlock self-tests will FAIL until reforges/accessories/enchanting/islands register
   everything the skill table names.** That is the point of them, but read the message before blaming skills.
2. Not driven by a real client: menu layout, box wrapping (38 chars) and the action-bar popup were checked as
   text only. The road puts level 25 in slot 53 (bottom-right, per research 08's suggested snake); next page is 50.
3. The Combat perk is a +% additive damage modifier at priority 150 on melee/projectile; whether the reference
   also boosts ability damage is not in the research (dial `skills.perk.warrior-magic`, off).

## Playtest fix (2026-10-04, wave-4 loops)
`Runes.onKill`: boss Runecrafting XP now only for mobs flagged `boss`. It also fired for `MobDef.slayer != null`, which is the slayer QUEST tag on every Graveyard Zombie / Crypt Ghoul / spider / wolf, so ordinary kills paid Runecrafting XP and hid the Combat XP flash. Verified live (a Graveyard kill flashes Combat XP again).

## Wave 5 (2026-10-06)

Verified: `bash tools/check.sh skills` = CHECK OK (190 files); **unit tests 18/18** (11 old + 7 new in
`TrainerTiersTest`), compiled with javac against `plugin/build/classpath.txt` + the junit-jupiter 5.11.4 jars and run
through the JUnit platform launcher with `src/main/resources` on the classpath. `gen_skills.py` re-run: only CARPENTRY
changed (furniture lines), every other skill byte-identical as JSON. **Not run on the server and not seen in a client**
(lane rule) - first live check: `/sb selftest skills`, then `/sb skillnpc`, `/pettrainer`, `/skills carpentry`.

**Done**
- **Runic Pedestal NPC "Runescribe Gloaming"** (request 10 fallback, `SkillNpcs`): decided in `postEnable` - stands
  aside if another module registered one; uses the hub anchor `npc.runic_pedestal` if the hub layout has it; else it
  is registered on a placeholder island (so core neither spawns it nor fails `ui.npc-anchors`) and placed with
  `spawnAt` on the nearest free full block 2-6 blocks from `npc.blacksmith` (the reference Mining District spot),
  only once that chunk is loaded, re-placed when the Hub world reloads. `/sb skillnpc runic-pedestal here` pins it to
  your feet (dial), `auto` undoes. First click plays three lines of dialogue, then opens the pedestal.
- **Pet Trainer "Trainer Brindle"** (the six Taming road unlocks were text): `/pettrainer`, Your Skills -> Taming ->
  slot 46, or the Hub NPC (placed beside `npc.pet_sitter` the same way). Sessions Free / Light / Moderate / Expert /
  Ultra / Turbo! at Taming 10..60 give 10k / 100k / 500k / 2M / 6M / 6M pet XP after 1h / 2h / 4h / 8h / 16h / 2h for
  0 / 50k / 200k / 750k / 2M / 2M coins (+500 Bits for Turbo, research 05). All TUNE + dial-scaled. Pay up front
  (withdraw, then spendBits; a Bits failure refunds the coins), wait real time (offline counts), claim with the same
  pet type+rarity summoned -> `Features.Pets.addPetXp` (pets' caps and level-up messages apply). The session is
  cleared and saved BEFORE the XP is handed over, so a double click cannot pay twice. Cancel = no refund. A finished
  session pings the player once while online.
- **Runebook** (accessories item, lore-only until now; Runic mobs are this module's): Runic kills made while
  `Accessories.has(p,"RUNEBOOK")` are counted (`SkillData.runebookKills`); the `skills` stat provider adds +1
  Runecrafting Wisdom per digit of the count, a chat line announces each new digit, the Runic Pedestal's skill icon
  shows the count.
- **Carpentry furniture lines are real**: `gen_skills.py` now reads `data/crafting/furniture.json` and emits
  `{type: furniture, id}` for all 37 pieces; the road shows crafting's registered name + rarity, and the Carpentry
  road has a Furniture button (slot 46) that runs crafting's `/furniture`.
- **Skill Average follows research 02** (every skill except Social, Runecrafting, Dungeoneering): Carpentry now
  counts (dial, true) although the API enum marks it cosmetic; Hunting counts once a module provides `Features.Hunting`
  (dial auto|true|false). The "(Not counted in your Skill Average)" note and the menu average follow the same rule.
- **Runic nameplates**: core now paints them purple from the PDC tag (request 11), so the per-hit re-tint listener is
  gone (no more one-tick red flash) and the tag is written BEFORE the health change so the first repaint is purple.
  The colour-swap remains only as a fallback for plates core does not recolour.

**Still left** - see "What is partial / not built" (Rune Sack contents, Hunting perk reader, Runebook rarity, Island
Decorator, Owl trainer perks, fusion failure odds, TUNE numbers, nothing seen in a client).

**Risk**: if the hub agent adds `npc.runic_pedestal` / `npc.pet_trainer` anchors AND registers its own NPC with an
unrelated id/title, both would appear; the hints above are how to avoid it. The auto spot is "nearest free full block
with two air above, no entity on it" - it can land on a path or in a doorway; move it with `/sb skillnpc ... here`.

