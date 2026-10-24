export const meta = {
  name: 'skyclone-wave5-all',
  description: 'Close the gap to an exact replica: core hooks, every module works its full partial list, 5 new system modules, integrate, two TestPilot playtests with fixers, final verify',
  phases: [
    { title: 'Core', detail: 'shared API hooks the wave needs' },
    { title: 'Build', detail: 'every module + 5 new modules, one agent each' },
    { title: 'Integrate', detail: 'ModuleList, compile, boot, self-tests' },
    { title: 'Playtest', detail: 'real client via TestPilot' },
    { title: 'Fix', detail: 'per-module fixers' },
    { title: 'Verify', detail: 'final build, boot, self-tests, re-checks' },
  ],
}

const ROOT = 'D:\\projects\\SkyblockClone'

const RULES = `Ground rules (all paid for):
- SkyClone: a complete SkyBlock-genre MMO modelled system-for-system on Hypixel SkyBlock, Paper 26.2 plugin, ${ROOT}. Goal of this wave: an EXACT replica of the reference game except for names. Read ${ROOT}\\CLAUDE.md, CONTRACT.md, docs/CORE_GUIDE.md first.
- Dev server: port 25630, RCON 25631 pw skydev (scripts/rcon.ps1). Port 25610 = ShadowPrison2; 25640/5650 + TestPilot 25620-25624 may belong to AISkyShow; the owner's own Minecraft (user playerone) and watcher.py may run — never stop/kill/touch anything that is not SkyClone's (read a process's command line first).
- NEVER search the whole drive (find /, recursive dir of C:/D:). Jars: plugin/build/classpath.txt; JUnit under ~/.gradle/caches/modules-2/files-2.1/org.junit*.
- Write Java with the file tool, never shell heredocs / non-raw Python strings into .java. bash tools/check.sh <module|core|all> must print CHECK OK. PYTHONIOENCODING=utf-8 when printing game text.
- Rename every reference proper noun shown to players (docs/names/<module>.md); ids stay the reference game's. No pay-to-win, no real money. Every feature: command + menu path; tunables are dials; >=3 self-tests per new system; generators pure (tools/preview.sh, READ the PNGs).
- Numbers: use research/ and research/data (NEU repo, Hypixel API dumps). Where no data exists, design something faithful in spirit and mark it // TUNE. Never leave a feature as lore-only if a hook exists or can be built in your own module.`

const PILOT = `Real client (owner may be at the PC, but TestPilot never takes input/focus so it is allowed): ONLY TestPilot (tools/testpilot/README.md, pilot.py), TESTPILOT_PORT=25629, fresh dev user. NEVER SendInput/keybd_event/mouse_event/SetForegroundWindow/SetCursorPos/desktop capture. Before launching: tasklist for RocketLeague.exe; if running wait (poll 2 min, max 60) — and if the owner says he is streaming, stop. online-mode=false only while testing (write server.properties as UTF-8 without BOM, never PowerShell Set-Content); restore online-mode=true + restart at the end, ops.json empty, kill only your client.`

const NEW_MODULES = [
  { id: 'hunting', task: `NEW MODULE "hunting": the Hunting skill (12th skill, max 50 — core adds SkillType.HUNTING; you implement its XP sources/rewards with the skills module's data shape: coordinate via Requests if skills owns the table, else provide the table here and make skills read it), critters (catchable creatures spawning in zones across islands, a catching tool/net), ATTRIBUTE SHARDS (the 2025 rework: shards drop from hunting/mobs/fishing/kuudra, a shard collection/menu, fusion, attributes giving stats via a StatProvider), the Safari area or equivalent, Hunting Fortune/Wisdom stats. Provide Features.Hunting (core adds the interface). Research: research/04 §4.7, research/07 (Galatea/foraging update), research/03 (critters/Safari), research/data. Publish the shard ids other modules should drop (crimson, kuudra, slayer, fishing) in your hand-off.` },
  { id: 'galatea', task: `NEW MODULE "galatea": build the missing Foraging-update islands as pure generators — LOTUS ATOLL (lotus materials LOTUS_SILVER/LOTUS_GOLD/LOTUS_DIAMOND sources, its NPCs, its fishing spots) and TORRHUS CANYON (canyon combat mobs Haggard/Parched/Torrid/Sprawl-equivalents, its fishing pool spot), warps, portals back to hub, fairy soul anchors, zones named "lotus_atoll" and "torrhus_canyon" (fishing will add sea-creature pools keyed to those zone ids). The hub ship currently says Lotus Atoll "not charted yet" — register the warps so the hub ship/fast travel can use them (hub reads registered warps). Research: research/03 (2026 islands), research/07.` },
  { id: 'chocolate', task: `NEW MODULE "chocolate": Hoppity's Chocolate Factory equivalent (research/08 §6.6): the factory menu (click for chocolate, employees/rabbits with levels, upgrades, prestige tiers, time tower, rabbit barn), chocolate as a progression currency, Hoppity's Hunt (eggs that appear at anchors/ground spots across islands on a schedule, collectible rabbits with rarities), the shop. Use Sky.calendar() for the event season.` },
  { id: 'extras', task: `NEW MODULE "extras": (1) the HARP (Melody's harp equivalent, research/08 §6.3: song list, note-hitting menu minigame, rewards incl. intelligence), (2) the ABIPHONE (§6.4: phone item tiers, contacts unlocked by meeting NPCs, calling contacts opens their services remotely, contact limits by tier, ring tones), (3) CITY PROJECTS (community contributions to unlock perks — research has no table, design it faithfully, TUNE), (4) FIRE SALES (rotating limited cosmetic sales for bits — cosmetic only, no pay-to-win), (5) CENTURY CAKES if progression has not built them (check its hand-off).` },
  { id: 'soulflow', task: `NEW MODULE "soulflow": the Soulflow system (research/06/04): soulflow as a per-player resource fed by Soulflow Pile/Battery/Supercell accessories and Soulflow engines, items owned here, Features.Soulflow (core adds the interface) so weapons (Phasewarp, Soul Howl, Judgement Beam), slayer (soulflow items, runebook) and accessories can consume/read it. Also the Etherwarp Conduit and the soulflow-based travel items if research has them.` },
]

const MODS = ['skills','collections','progression','materials','crafting','enchanting','reforges','pets','accessories','armor','weapons','alchemy','storage','menus','bank','bazaar','auction','shops','mayors','mining','farming','foraging','fishing','slayer','minions','garden','island','hub','mines','hollows','combatzones','crimson','dungeons','kuudra','rift']

const EXTRA = {
  pets: 'Make the 94 description-only abilities real (tools/gen/pets/info_only.txt). Pet skins: build them as cosmetics obtainable in-game (bits/community shop/events — no real money).',
  accessories: 'Wire every accessory effect you can inside your own module. For effects owned by another system, the OWNING module agent is told to wire them this wave — keep your hand-off list precise so they can.',
  fishing: 'Add sea-creature pools for zones "lotus_atoll" and "torrhus_canyon" (galatea module builds them) and the Moonmarsh; Puddle Jumper minigame; geysers; fishing nets; every accessory/pet/reforge effect listed for fishing in accessories/pets/reforges hand-offs.',
  mining: 'Structure quests, the gauntlet gem-spilling half, Mining Speed for drills on the private island, NPC bodies for Fetcher/Riddlewright/Drill Mechanic, every accessory/pet/reforge effect for mining.',
  hollows: 'Per-lobby feel: instanced or rotating cavern lobbies (or a scheduled rebuild with a new seed + party-scoped lobbies — pick the closest faithful option), jungle temple inner parkour, real Mines of Varnas treasure, guardians at mineshafts.',
  mines: 'Real minecart/lift rides (moving vehicles, not a menu teleport) and the skipped Dwarven sub-areas.',
  minions: 'Physical storage chests next to minions, Minion Chair/Free Will/Super Jacob System/Jacob medal fuel, fuller animations (mobs walk, cocoa/ghast minions animate).',
  rift: 'Real Enigma Soul puzzles (two-player plates, balloons, cube puzzle etc. — build as many distinct puzzles as the research names), the Rift pet / Montezuma pieces (coordinate with pets via Requests), Rift-only stats.',
  mayors: 'Carnival booths as REAL physical minigames (not menus), siege waves, mythological creature numbers.',
  kuudra: 'Cutscene-style sequences (camera-free: titles, freezes, display entities), the intro questline, attribute shard drops via Features.Hunting.',
  crimson: 'Attribute shards via Features.Hunting, faction-specific mini-boss loot.',
  slayer: 'Use Features.Soulflow for soulflow items/runebook; Riftstalker fee in Motes via the rift feature service if available; mixins at the Bartender; attribute shards via Features.Hunting.',
  weapons: 'Use Features.Soulflow for Phasewarp/Soul Howl/Judgement Beam costs; per-target Magic Find via core\'s new loot hook; dungeon class abilities on class-ability weapons.',
  progression: 'Bestiary "Magic Find vs that mob" via core\'s new loot hook; museum co-op soulbound enforcement (auction/bank trade must refuse — coordinate via Requests); per-district objective mini-chains.',
  storage: 'Equipment wardrobe (necklace/cloak/belt/gloves sets); Rune Sack contents; register your containers with core\'s new dupe-scan source hook.',
  bank: 'Museum interest bonus, Community Shop upgrades (co-op slots, armor wardrobe...), fame multiplier on bits from other sources.',
  collections: 'Separate Ironman/Stranded leaderboards if profile modes exist; otherwise document.',
  island: 'Per-member co-op build permissions; Portal Isle destinations for Garden, Backwater Bayou, Lotus Atoll, Torrhus Canyon.',
  menus: 'Implement Features.Settings (core adds it) over your stored settings so other modules can read them; profile viewer for offline players.',
  enchanting: 'Stealth, Scuba, Drain on Vitality if core adds it, multi-number enchant descriptions.',
  garden: 'Garden accessory effects (copper/visitor talismans, bioanalysis, pesthunter belts, lotus/blossom), distinct barn/greenhouse skins.',
  foraging: 'Honey hives, underwater depths if research has them, Moonmarsh sea creatures (coordinate with fishing via zone ids), foraging accessory/pet effects.',
  dungeons: 'Master Mode M1-M7 if not complete, in-dungeon star boost, dungeon accessory effects (treasure, medallion, soulweaver), fire SkyEvents.DungeonXpGain (core adds it).',
  hub: 'Townhouse NPCs with purpose, Lotus Atoll charted via the galatea warps, any hub NPC that is description-only.',
  shops: 'Real pelts, register/stock the remaining unregistered ids where you own them, Elizabeth/Community Shop placement if missing.',
  alchemy: 'Per-block brewing stands (each stand block holds its own brew), the missing special potions.',
  combatzones: 'Draconic altar + dragon essence, Ike/Michael intro NPCs, Lone Adventurer shop and kill quests.',
  auction: 'Ironman vault rules, anything in your partial list.',
  bazaar: 'The seeding trick for progression-gating materials, anything in your partial list.',
}

function buildPrompt(id, isNew, task) {
  return `${RULES}

YOU OWN module "${id}" (plugin/src/main/java/com/skyclone/mod/${id}/**, resources/data/${id}/**, tools/gen/${id}/**, docs/handoff|names/${id}.md, tests under src/test/java/com/skyclone/mod/${id}/). ~40 other agents edit other modules in parallel right now — never touch their files, api/, core/, ModuleList, plugin.yml. Need something elsewhere? Requests in your hand-off + a fallback.
${isNew ? 'This is a NEW module: entry class com.skyclone.mod.' + id + '.' + id.charAt(0).toUpperCase() + id.slice(1) + 'Module implements GameModule (see an existing module for the pattern). The integrator adds it to core.ModuleList.\n' : ''}
Core just added (read the API + docs/handoff/core-wave5.md): SkillType.HUNTING, Features.Settings / Soulflow / Hunting / Abiphone, a per-target Magic Find loot hook, SkyEvents.DungeonXpGain, a dupe-scan source hook. Use them where relevant.

TASK: ${task}
${isNew ? '' : `Also: work your ENTIRE "What is partial / not built" list in docs/handoff/${id}.md (behaviour gaps first; TUNE numbers only if research now supports them), and read docs/handoff/accessories.md, pets.md and reforges.md for effects that belong to YOUR system and wire them here (check Sky.service(Features.Accessories.class).has(p, id), Features.Pets active type/level, the held item's reforge in Sky.items().data(stack).reforge).`}
Do NOT start the server or a client. Spend as long as the work needs (up to ~2 hours), stop at a clean compiling state. Update the hand-off ("Wave 5 (2026-10-06)": done / still left). Return a short plain-text summary.`
}

const ISSUES = {
  type: 'object',
  properties: {
    compileOk: { type: 'boolean' }, bootOk: { type: 'boolean' },
    selftestPassed: { type: 'integer' }, selftestFailed: { type: 'integer' },
    failures: { type: 'array', items: { type: 'object', properties: { module: { type: 'string' }, problem: { type: 'string' } }, required: ['module', 'problem'] } },
    summary: { type: 'string' },
  },
  required: ['compileOk', 'bootOk', 'failures', 'summary'],
}
const PLAY = {
  type: 'object',
  properties: {
    fixedDuringPlay: { type: 'array', items: { type: 'string' } },
    issues: { type: 'array', items: { type: 'object', properties: { module: { type: 'string' }, severity: { type: 'string' }, problem: { type: 'string' } }, required: ['module', 'severity', 'problem'] } },
    verdict: { type: 'string' },
  },
  required: ['issues', 'verdict'],
}

async function fixers(list, tag) {
  const by = {}
  for (const f of list || []) { const k = (f.module || 'core').toLowerCase().split(/[\s/(,]/)[0] || 'core'; (by[k] = by[k] || []).push(f.problem) }
  const ids = Object.keys(by)
  if (!ids.length) return
  phase('Fix')
  await parallel(ids.map(id => () => agent(`You are a FIXER. ${RULES}\nFound during ${tag}, in "${id}":\n${by[id].map(p => '- ' + p).join('\n')}\nRead docs/PLAYTEST.md / docs/INTEGRATION.md and ${id === 'core' ? 'the core packages' : 'module ' + id + ' + docs/handoff/' + id + '.md'}. Fix each at its root, staying inside ${id === 'core' ? 'core/gen/api (additive)' : 'module ' + id + "'s files"} (other fixers run in parallel). CHECK OK required. Do NOT start the server or a client. Note changes in the hand-off. Return a short summary.`, { label: 'fix:' + id + ':' + tag, phase: 'Fix', effort: 'high' })))
}

phase('Core')
await agent(`${RULES}

YOU ARE the CORE agent for wave 5. Before ~40 module agents start, add these ADDITIVE API + core changes (never rename/remove API members):
1. SkillType.HUNTING (display "Hunting", max 50, wisdom stat HUNTING_WISDOM) and new Stats HUNTING_WISDOM and HUNTING_FORTUNE; fix SkillType.FORAGING maxLevel to the data's 57 — check every core/module use of the enum still compiles (bash tools/check.sh all).
2. Features interfaces (in api.feature.Features): Settings { boolean flag(Player p, String key, boolean def); String text(Player p, String key, String def); } (provided by menus), Soulflow { long soulflow(Player p); boolean consume(Player p, long amount, String reason); void add(Player p, long amount, String reason); } (soulflow), Hunting { int shards(Player p, String shardId); void addShards(Player p, String shardId, int n, String reason); double attributeLevel(Player p, String attribute); } (hunting), Abiphone { void addContact(Player p, String contactId); boolean hasContact(Player p, String contactId); } (extras). Document each in docs/CORE_GUIDE.md.
3. A per-target Magic Find hook in LootService: addMagicFindModifier(String id, BiFunction<Player, MobDef, Double>) summed into the magic find used when rolling a mob's drops (bestiary tiers and weapons need it).
4. SkyEvents.DungeonXpGain (player, amount mutable, class, floor) — dungeons will fire it.
5. ItemService.addDupeScanSource(String id, Function<Player, List<ItemStack>>) so storage containers join the dupe scan.
6. Any other small additive hook that the "Requests" sections of docs/handoff/*.md ask core for and that is clearly right — list what you did.
bash tools/check.sh all must stay CHECK OK; run gradle build (cd plugin && ./gradlew.bat build) for unit tests. Write docs/handoff/core-wave5.md describing every addition with a usage snippet. Do NOT start the server. Return a short summary.`, { label: 'core', phase: 'Core', effort: 'high' })

phase('Build')
const tasks = NEW_MODULES.map(m => ({ id: m.id, isNew: true, task: m.task }))
  .concat(MODS.map(id => ({ id, isNew: false, task: EXTRA[id] || 'Your partial list (see below).' })))
await parallel(tasks.map(t => () => agent(buildPrompt(t.id, t.isNew, t.task), { label: (t.isNew ? 'new:' : 'mod:') + t.id, phase: 'Build', effort: 'high' })))

phase('Integrate')
const integ = await agent(`${RULES}

YOU ARE the INTEGRATOR of wave 5. Core added hooks (docs/handoff/core-wave5.md); 35 modules worked their partial lists; 5 NEW modules exist (hunting, galatea, chocolate, extras, soulflow). You may edit any file (api: additive only).
1. Add the new modules to com.skyclone.core.ModuleList in a sensible place (soulflow and hunting with the systems, galatea with the islands, chocolate/extras after progression).
2. bash tools/check.sh all; cd plugin && ./gradlew.bat build — fix everything.
3. Satisfy reasonable cross-module Requests from the wave-5 hand-off sections.
4. Deploy + boot (stop server via RCON if running; scripts\\start-server.ps1 -Detached -WaitReady). All modules must enable; new islands must build (wait); /sb selftest (poll 'sb selftest report') — fix or report every FAIL. No client this step. Leave the server running in online mode.
5. Append "Wave 5 integration" to docs/INTEGRATION.md. Update ${ROOT}\\CLAUDE.md's module count/stats line if it changed.
Return the structured report.`, { label: 'integrate', phase: 'Integrate', schema: ISSUES, effort: 'high' })
if (integ && (integ.failures || []).length) await fixers(integ.failures, 'wave5-integrate')

const PASSES = [
  { key: 'newcontent', focus: 'EVERYTHING NEW IN WAVE 5 (read the wave-5 hand-off sections): the Hunting skill + critters + attribute shards + fusion; Lotus Atoll and Torrhus Canyon (warp, walk, mobs, fishing pools); the Chocolate Factory + an egg hunt; Harp, Abiphone calls, City Projects, Fire Sales; Soulflow items feeding Phasewarp/Soul Howl; real minecart rides; Crystal Caverns lobby behaviour; physical minion chests; Rift soul puzzles; Carnival booths; Kuudra sequences; equipment wardrobe; per-target magic find; 10+ accessory/pet effects that were lore-only before (pick ones a player would notice). Use admin to reach mid/late content.' },
  { key: 'regression', focus: 'REGRESSION on what already worked: a fresh player\'s first 30 minutes (objectives, crafting, minion, hub NPCs, Bazaar at SkyBlock Level 5), then with admin help one round each of slayer T2, a mining commission, farming in the Barn, a garden visitor, fishing at the Bayou, dungeon F1 solo, the auction house and a /trade if a second client is feasible. Anything that worked in docs/PLAYTEST.md wave 4 and is now broken is a BLOCKER.' },
]
for (const pt of PASSES) {
  phase('Playtest')
  const res = await agent(`${RULES}
${PILOT}

YOU ARE a PLAYTESTER (wave 5, pass "${pt.key}"). Four AI characters will play this game on camera, so anything confusing, silent or broken matters. Read docs/PLAYTEST.md (earlier passes) and the wave-5 hand-off sections. First: bash tools/check.sh all; (cd plugin && ./gradlew.bat build); stop the server (RCON stop), online-mode=false, start it (scripts\\start-server.ps1 -Detached -WaitReady).
PLAY: ${pt.focus}
Screenshot constantly (pilot.py screenshot), LOOK at them. Small obvious fixes: make them, rebuild, redeploy, recheck. Everything else: record precisely (module, severity, did/expected/actual). Append "Wave 5 ${pt.key} pass" to docs/PLAYTEST.md (screenshots under docs/playtest/wave5-${pt.key}/). Finish: online-mode=true, server restarted and running, de-op, kill your client. Return the structured result.`, { label: 'playtest:' + pt.key, phase: 'Playtest', schema: PLAY, effort: 'high' })
  if (res && (res.issues || []).length) await fixers(res.issues.map(i => ({ module: i.module, problem: '[' + i.severity + '] ' + i.problem })), 'wave5-' + pt.key)
}

phase('Verify')
const verify = await agent(`${RULES}
${PILOT}

YOU ARE the FINAL VERIFIER of wave 5. Read docs/INTEGRATION.md + docs/PLAYTEST.md wave-5 sections.
1. bash tools/check.sh all; cd plugin && ./gradlew.bat build — fix anything broken.
2. Stop server, deploy + start; all modules enable; /sb selftest — fix or list FAILs.
3. With TestPilot, re-verify every blocker/major a fixer claims fixed in wave 5. Never wait/poll silently more than 10 minutes on anything; report blockers and move on.
4. PYTHONIOENCODING=utf-8 py -3 tools/gen_overview.py (add the 5 new modules to its MODULES list if missing, with one-line descriptions).
5. Finish: online-mode=true, server restarted and LEFT RUNNING, ops.json empty, your client killed. Append "Wave 5 final verification" to docs/PLAYTEST.md.
Return a short report: build, boot, selftests, verified-fixed, still-broken, and the updated "what is still not an exact replica" list in 10 lines.`, { label: 'verify', phase: 'Verify', effort: 'high' })
return { verify }
