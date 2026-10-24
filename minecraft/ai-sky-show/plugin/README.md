# ShowControl — the server half of "Can 4 AIs Beat Hypixel SkyBlock?"

A separate Paper **26.2** plugin (Java 25, `depend: [SkyClone]`, package `com.aisky.showcontrol`).
It only USES SkyClone's public API (`com.skyclone.api`, `Sky` is the one door) and never patches
SkyClone. It gives the orchestrator an event feed, a per-agent state document, a command endpoint,
the episode clock, a fallback camera and rulesets. Contract: `../CONTRACT.md` §3–§4.

## Build / deploy / run

| Step | Command (from `AISkyShow\`) |
|---|---|
| create the show server (copy of SkyClone's dev server, no worlds, no player data) | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\setup_server.ps1` |
| build ShowControl + copy it (+ refresh SkyClone.jar from its latest build) | `... -File scripts\deploy_plugin.ps1` |
| start detached, wait for RCON + `/health` | `... -File scripts\start_server.ps1 -Detached -WaitReady` |
| stop (RCON `stop`, kills only the `-Daisky.show=true` JVM if it hangs) | `... -File scripts\stop_server.ps1` |
| fresh profiles for a new episode | `... -File scripts\reset_episode.ps1` (`-Live` = no restart, `-Full` = rebuild the world) |
| console command | `py -3 scripts\rcon.py "show status"` |
| build gate | `py -3 scripts\gate_events.py` (needs one cast client online) |

Every heavy script refuses to run while Streamlabs / Rocket League / OBS are open (`-Force` overrides).
The gradle build compiles against `../../SkyblockClone/plugin/build/libs/SkyClone.jar` (read-only;
SkyClone is never built from here; override with `-PskyCloneJar=<path>`).

Ports: game **25640**, RCON **25641** (password `config/show.json` → `rcon_password`), HTTP **25642**.

## HTTP API (127.0.0.1:25642, JDK `com.sun.net.httpserver`)

| Route | What |
|---|---|
| `GET /events` | SSE. `id: <id>` + `data: <event json>`. Honors `Last-Event-ID` (header) or `?lastEventId=`/`?since=`; with neither, only new events. `: hb` comment every 10 s. `retry: 2000`. |
| `GET /events/since?id=N[&limit=]` | JSON array of buffered events with id > N (ring of 5000). |
| `GET /players` | `[{"name","uuid","online","island","island_name","zone","health","maxHealth","purse","cast"?,"camera"?}]` — everyone seen this run; offline rows keep their last values with `online:false`. |
| `GET /state/<name>` | the state document below. Unknown/offline → `{"name","online":false,...}`, never an error. |
| `POST /cmd {"cmd":"..."}` | console command on the main thread → `{"ok","output"}` (output captured with Paper's feedback sender; text printed after a DB future is not captured — same limit as RCON). |
| `GET /health` | `{"ok":true,"episode":{running,minutes,started_at,elapsed_s,remaining_s,frozen,ruleset?,end_reason?},"tps","players_online","last_event_id","camera","camera_shot"}` |

Event ids are a sequence seeded from the wall clock at boot, so they keep rising across restarts and
a client's old `Last-Event-ID` never hides new events. HTTP threads never touch Bukkit: events are
serialised once at emit time, state is a snapshot rebuilt on the main thread every 10 ticks.

### `/state/<name>` — only what the Sky API exposes
`name, online, t, ready, pos{x,y,z,yaw,pitch,world}, gamemode, island, island_name, zone (display),
zone_id` always; after `ProfileReady` also `health, maxHealth, mana, maxMana, defense,
stats{HEALTH,DEFENSE,STRENGTH,CRIT_CHANCE,CRIT_DAMAGE,SPEED,INTELLIGENCE,MAGIC_FIND,MINING_FORTUNE,
FARMING_FORTUNE,FORAGING_FORTUNE}, purse, bank, bits, profile, skills{SKILL:{level,xp,progress,next,cap}},
skill_average, sb_level, collections[{id,name,tier,total}], held{id,name,count,rarity},
armor[{slot,id,name,count,rarity}], inventory[{id,name,count,rarity}] (grouped by sky id),
hotbar[{slot,id,name,count,rarity}], inventory_free, held_slot, nearby_mobs[{id,name,dist,health,
maxHealth,level,boss?,passive?,x,y,z}] (24 blocks, nearest 12), nearby_npcs[{id,name,title?,dist,x,y,z}]
(48 blocks), nearby_players[{name,dist}], warps_unlocked[...], minions[{type,tier,slot}], objective, pet{type,level,rarity}`.

Omitted on purpose (no API to fill them honestly): `can_afford`, `next_unlocks`. `collections` lists
only collections that moved since the server started (the Collections service has no "list all").

## Events (CONTRACT §3 shape; `src:"server"`, `players` = ACCOUNT names, the bus maps them)

| type | data | drama | from |
|---|---|---|---|
| `join` / `quit` | — | 1 / 2 | Bukkit (camera excluded) |
| `profile_ready` | `first_join, profile` | 2 first / 1 | `SkyEvents.ProfileReady` |
| `chat` | `message` | 2 | Paper `AsyncChatEvent` (async; loc from the last snapshot) |
| `mob_kill` | `mob_id, mob_name, level, coins, combat_xp, boss` | 1 · lvl≥30 2 · sea 3 · boss 7 | `MobKill` (killer must be a cast player) |
| `item_gain` | `item_id, item_name, amount, rarity, source, first` | C1 U2 R4 E6 L8 M+9 | `ItemGain` — only rarity ≥ RARE or the player's first of that item this episode (sack-absorbed gains count) |
| `gather_summary` | `kind, what (top item), amount (kind total), window_s, breakdown{}` | 1 · ≥64 2 · ≥200 3 | `Gather`, one per player per kind per 30 s |
| `skill_level_up` | `skill, skill_name, level` | 3 · ≥5 4 · ≥10 5 · ≥15 6 | `SkillLevelUp` |
| `collection_tier_up` | `collection, collection_name, tier` | 3 · ≥V 4 | `CollectionTierUp` |
| `coins_milestone` | `purse, milestone, reason` | 1k 2 · 5k 3 · 10k 4 · 100k 6 · 1M 8 | `CoinsChange` crossing 1k/5k/10k/25k/50k/100k/250k/500k/1M/5M/10M (highest newly crossed only, once per episode) |
| `island_change` | `from, to, from_name, to_name` | 2–3 | `IslandChange` |
| `zone_change` | `from, to, island, from_name, to_name` | 1 | `ZoneChange` (leaving every zone is not reported) |
| `ability_used` | `ability, ability_name` | 2 | `AbilityUsed`, debounced 8 s per ability |
| `death` | `cause, cause_text, coins_lost` | 5 · ≥1k lost 7 | `SkyDeath` (MONITOR: the final `coinsLost`) |
| `near_death` | `health, max` | 5 | vitals poll every 0.5 s: health < 15 % of max, debounced 20 s |
| `big_hit` | `damage, from, health_before, max` | 4 · lethal 6 | `SkyEvents.Damage` ≥ 30 % of max HP, debounced 5 s |
| `fish_caught` | `what, what_name, sea_creature, treasure, quality, lava` | 1 · treasure 3/4/6 · sea creature 4 | `FishCaught` |
| `calendar_start` / `calendar_end` | `event, event_name, at_boot` | 3 (1 at boot) / 2 | `CalendarStart/End` |
| `boss_spawn` | `mob_id, name, level, owner` | 7 | any sky mob with the `boss` flag, seen one tick after `EntitySpawnEvent` |
| `minion_placed` | `type, tier, count` | 4 | profile's placed-minion count rising (polled every 5 s) |
| `first_of_kind` | `what, key` | 4–8 | see below |
| `episode_start` / `episode_end` | `minutes` / `reason, elapsed_s` | 8 | `/show start`, `/show stop`, the clock (`time`), `/show reset` |
| `mark` | `label` | 5 (0 for `showcontrol_enabled`, 3 for `reset`) | `/show mark` |

`first_of_kind` keys (once per episode, cleared by `/show start` and `/show reset`): `first_kill`,
`first_boss_kill`, `first_sea_creature_kill`, `first_sea_creature`, `first_rare_drop` (RARE+ from a
natural source), `first_death`, `first_skill_level`, `first_skill_5`, `first_skill_10`,
`first_collection_tier`, `first_minion`, `first_coins_<n>` (≥10k milestones), `first_island:<id>`
(every island except the private island; the hub is drama 6).

`/show emit <type> [drama] [text]` publishes a SYNTHETIC event (`data.synthetic:true`) for overlay work.

## Commands (`/show`, console/RCON/op; permission `aisky.show`)
`reset [acct...]` · `start <min>` · `stop` · `freeze on|off` · `mark <label>` · `camera <acct>` ·
`cam orbit|follow|pushin|stop <target> [seconds] [radius]` · `ruleset <name>|list|off` · `status` ·
`test kill|boss|hit|die <player> [arg]` · `emit <type> [drama] [text]`

- **reset**: kicks the cast, refuses their logins for 2 s, then closes every open profile membership
  (`profile_members.left_at = now, active = 0` — SkyClone's own "left profile" state, queued on
  SkyClone's single DB thread after the quit-save). SkyClone's next login creates a brand-new first
  profile (new id, empty purse, new private island instance, `profile_ready{first_join:true}`). The
  vanilla inventory (world player file) is wiped on their next join at LOWEST priority, before
  SkyClone hands out starter items (`plugins/ShowControl/pending-wipe.yml` survives a restart).
  The offline equivalent is `scripts/reset_episode.ps1` (→ `reset_profiles.py`).
- **freeze**: players cannot move (head turns allowed) or take damage; with `freeze-world-ticks`
  the world tick is frozen too (mobs stand still). Plugin schedulers keep running.
- **camera**: the account goes to spectator and is `hidePlayer`'d from every player (re-applied on joins).
  It never produces events and is not counted as a player.
- **cam** (fallback; ShowCam is preferred): per-tick teleport of the camera account, always looking at
  the target's eyes. `orbit` circles at `radius` (default 6) at 15°/s starting from where the camera
  already is; `follow` sits 5 behind/2.5 above with exponential smoothing; `pushin` eases from
  1.8×radius to 0.5×radius in front of the face over `seconds` (default 6). `seconds > 0` ends the shot.
- **test** (gate helpers; `allow-test-commands`): drive real SkyClone paths — `kill` spawns a mob near
  the player and kills it with a real `CombatService.playerAttack` from that player; `boss` spawns a
  boss-flagged mob (prefers ones with no scripted behaviour) and removes it after 5 s; `hit` deals TRUE
  damage through `CombatService.damagePlayer` (never lethal); `die` runs `VitalsService.kill`.

## Rulesets (`plugins/ShowControl/rulesets.yml`)
`/show ruleset sprint30` applies each `dials:` entry with SkyClone's own `/sb dial set` (SkyClone
validates key and value; failures are reported, never invented) and remembers the keys in
`state.yml`; switching ruleset or `/show ruleset normal|off` runs `/sb dial reset` on exactly those
keys. `show:` holds ShowControl's knobs for what SkyClone has no dial for (cast only):
`mob_coin_mult` (multiplies `MobKill.coins` before core pays) and `magic_find_bonus` (a
`showcontrol` StatProvider). The reasoning for every `sprint30` number is in the yml.

| dial | sprint30 | why |
|---|---|---|
| `skills.xp-multiplier` | 4.0 | ~2,000 base XP in 30 min → Level 8–9 instead of 5–6; one global dial so nothing doubles |
| `skills.coin-multiplier` | 2.0 | each level-up visibly pays |
| `collections.gain-multiplier` | 3.0 | tier I/II (minion + enchanted recipes) land inside the episode |
| `farming.drop-multiplier` / `mining.drop-mult` | 1.5 | enough items to craft or sell a stack |
| `minions.speed-bonus` | 100 | a minion placed mid-episode visibly fills |
| `vitals.death-coin-loss` | 0.25 | deaths sting but do not end the run |
| `hub.spawn-scale` | 1.5 | four players share hub mobs without waiting |
| `shops.price-scale` | 0.75 | NPC gear reachable in 30 min (anti-arbitrage floor kept) |
| `slayer.cost-scale` | 0.5 | a Tier I slayer attempt is affordable late on |
| `progression.objectives.coin-scale` / `hub.guide-coin-scale` | 2.0 | rewards the explore-and-meet opening |
| `pets.xp-scale` | 2.0 | cheap cosmetic progress |
| (show) `mob_coin_mult` | 3.0 | a Graveyard Zombie pays 1 coin |
| (show) `magic_find_bonus` | 50 | sub-5 % drops 1.5× as likely → a RARE DROP on camera is plausible |

## Requests for SkyClone (ShowControl works around each; nothing was patched)
1. **Minion events / service** — a `SkyEvents.MinionPlaced` (or `Features.Minions.placed(p)`). Today
   ShowControl reads the minions module's `MinionStore` from profile shared data (`"minions"` key,
   read-only, guarded) and polls it every 5 s: it couples to an internal class.
2. **Profile wipe API** — `ProfileService.closeProfile(UUID)` / `/sb profile <p> wipe`. `/show reset`
   runs SkyClone's closeMembership SQL itself on `profile_members`; it couples to the schema.
3. **Release the old private island** when a membership closes — every reset leaves the previous
   instance slot allocated (harmless, but slots grow by one per reset per player).
4. **`Features.Collections.all(Player)`** (id → count/tier) and a "next tier reward" query, so
   `/state` can list every collection and fill `next_unlocks`.
5. **Dials** for mob kill coins (`mob.coin-multiplier`) and a generic drop-chance multiplier —
   ShowControl emulates them with `MobKill.coins` and a Magic Find stat provider.
6. **`SkyEvents.BossSpawn`** — bosses are detected one tick after `EntitySpawnEvent` via `defOf`.
7. **`NpcService.entities(id)` / `near(Location, radius)`** — NPC copies placed with `spawnAt` (the
   island helper on private islands) are invisible to `entity(id)`, so they are missing from
   `nearby_npcs`.
8. **A shop price lookup** (`Features.Shops.price(npc, item)`) to fill `can_afford`.

## Files
`ShowControlPlugin` (wiring) · `ShowConfig` · `EventBus` (ring + SSE waiters + event builder) ·
`GameListener` (every event) · `GatherAggregator` · `EpisodeMemory` (firsts, milestones, seen items) ·
`StateSnapshots` (/state, /players, near_death + minion polls) · `Episode` (clock, freeze) ·
`CameraFallback` · `Rulesets` · `CommandRunner` · `ProfileReset` · `HttpApi` · `ShowCommand` · `Names`.
