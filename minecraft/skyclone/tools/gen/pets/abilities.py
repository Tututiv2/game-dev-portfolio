"""Pet abilities: OUR names and OUR wording for every reference ability, plus the machine-readable
effects the Java side (com.skyclone.mod.pets.PetEffects) implements.

Keyed by PET id -> reference ability name (the name is only a join key, never shown).
Tokens {a},{b},{c}... are the reference block's number placeholders IN ORDER OF FIRST APPEARANCE
inside that ability block, so the numbers (lvl1 -> lvl100 per rarity) come straight from
research/data/neu-repo/constants/petnums.json and are never typed here. Literal numbers in a lore
line are numbers the reference writes as fixed text (they do not scale).

Effect vocabulary (see PetEffects.java for the exact semantics):
  stat      {stats:{STAT:v}, when}                flat stats
  pct       {stats:[..] | group, v, when}         +v% of the player's total stat(s)
  per_stat  {stat, from, every, v, above, cap}    v per `every` of another stat
  frac_stat {stat, from, v, cap}                  v% of another stat
  per_skill {skills:[..], stats:{}}               per skill level
  per_hotm  {stats:{}, when}                      per Heart of the Mountain level
  per_digit {collection, stats:{}, max}           per digit of a collection count
  per_players {radius, max, stats:{}, pet?}       per other player nearby (optionally with that pet summoned)
  per_mobs  {match, radius, max, stats:{}}        per matching mob nearby
  kill_stacks {stats:{}, dur, max}                stacking buff on each kill
  cast_buff {stats:{}, dur, cap}                  buff after using an item ability
  jump_buff {stats:{}, dur}                       buff after jumping
  bow_stacks {stats:{}, max, dur}                 stack per bow hit
  aura      {radius, max, stats:{}, pct:{}}       grants stats to players near the owner (non-stacking)
  pet_base  {v, when}                             +v% to this pet's own base stats
  pet_base_all {v}                                +v% to the ACTIVE pet's base stats (Bingo)
  held_buff {items:[..], damage, stats:{}}        when one of these items is held
  category_buff {categories:[..], damage, stats:{}}
  armor_buff {match:[..], v}                      +v% of worn armour pieces whose id contains a keyword
  potato_double                                   potato-book stats on worn/held gear count twice
  pet_item_boost {v}                              stats from this pet's held item +v%
  dmg       {match, v, when}                      +v% damage dealt to matching mobs
  dmg_taken {match | hp_below | absorption, v}    -v% damage taken
  dmg_missing {v} · dmg_far {dist,v} · dmg_near {dist,v} · dmg_isolated {radius,v}
  dmg_retaliate {v} · dmg_level {tiers} · dmg_bank {v, cap} · dmg_kind {kind, v, dungeon}
  last_stand {...} · rekindle {...} · flare {...} · wither {...} · snowball {...} · ricochet {v}
  toss {...} · slow_hit {v} · stun {chance, cd} · blizzard {radius, v} · zap {v, every, radius}
  skel_arrow {v, cd, radius} · club {every, v, radius} · kb_immune · fall {v} · trample {min, v, radius}
  overheal {v, idle} · vitality_hits {hits, v}
  combat_xp {match, v} · coins_kill {v} · coins_level {v} · skill_xp {skill, v}
  gather_drop {kind, chance, items} · kill_drop {match, chance, items} · kill_double {match, chance}
  collection_bonus {v, source} · no_coin_loss · mana_cost {v, match} · free_cast {v} · cooldown {v}
  cast_weapon_dmg {match, v, dur} · potion {effect, amp, when} · flight {islands}
  treasure_buff {stats:{}, dur} · treasure_drop {chance, items}  on a treasure catch (FishCaught.treasure)
  sc_stun {dur} · sc_wound {v}                    on the sea creature you just hooked (FishCaught.seaCreature)
  gather_spawn {kind, what:[..], mob, chance, per_stat}   spawn a mob next to the gathered block
  kill_armor {v}                                  armour drops of a killed mob get an extra roll at v% of their chance
  ride {mode: sit|mount, speed}                  rideable pet (PetRide)
  pool {every, heal, regen, dur} · bulwark {match, every, defense, true_defense, max} · xp_orbs {v}
  external {by} (another module reads abilityValue(key)) · combo (Grandma Wolf) · info (shown, not simulated: the system it touches is another module's)

Conditions ("when"): island:<id>[|<id>], mining, public, night, day, event:<id>, hp_above:<pct>,
hp_below:<pct>, water, still, holding:<CATEGORY>, dungeon, zone:<keyword>, rain, riding (on your pet, PetRide), potion:<alchemy effect id>, target:<family> (the mob you last hit, 6s),
dig:<tag> (the mining block you are breaking, 3s; tags from data/pets/dig.json: block id, kind, id prefix, 'ore').

Wave 5 effects: morph {scale} · morph_speed {mult, fly} (Rat form, PetForms) · cheese {every, radius, life} ·
rat_blessing {mf, dur, radius, copy} · sail {cd} (Rock stays put; sneak to call) · tunnel {islands, energy, cost, regen,
per_pass} · tunnel_energy {v} (Armadillo digging while ridden) · last_stand_boost {v, per, hp, enchant} (Ankylosaurus) ·
mf_vs {match, v} (Magic Find on that mob family's drops only, through core's LootService.addMagicFindModifier).
"""

MINING = 'island:gold_mine|deep_caverns|dwarven_mines|crystal_hollows|glacite_tunnels'


# ---- wave 5: lists read from other modules' shipped data at GENERATION time (ids only, no runtime coupling) ----
import json as _json
import os as _os

_RES = _os.path.join(_os.path.dirname(_os.path.dirname(_os.path.dirname(_os.path.dirname(_os.path.abspath(__file__))))),
                     'plugin', 'src', 'main', 'resources', 'data')


def _load(*parts):
    try:
        with open(_os.path.join(_RES, *parts), encoding='utf-8') as f:
            return _json.load(f)
    except (OSError, ValueError):
        return None


_FISH_MOBS = _load('fishing', 'mobs.json') or []
SPOOKY_SC = sorted(m['id'] for m in _FISH_MOBS if 'spooky' in ((m.get('extra') or {}).get('types') or []))
SHARK_SC = sorted(m['id'] for m in _FISH_MOBS if 'shark' in m['id'])
HOTSPOT_SC = sorted(m['id'] for m in _FISH_MOBS if 'hotspot' in str((m.get('extra') or {}).get('catchMessage', '')).lower())
_FISHING = _load('fishing', 'fishing.json') or {}
TROPHY_TOP = sorted(s + '_' + t for s in (_FISHING.get('trophy') or {}).get('fish', {}) for t in ('GOLD', 'DIAMOND'))
_WATER = ((_FISHING.get('treasure') or {}).get('water') or {})


def treasure_table(q):
    return [{'item': e['item'], 'min': e.get('min', 1), 'max': e.get('max', 1), 'weight': e.get('weight', 1)} for e in _WATER.get(q, [])]


_SHARDS = _load('hunting', 'shards.json') or []
_HABITATS = {h['id']: h for h in (_load('hunting', 'habitats.json') or [])}
HUNT_ATTRS = sorted({s['attr'] for s in _SHARDS if s.get('attr')})


def _water_shards(islands):
    out = {}
    for s in _SHARDS:
        for src in s.get('sources', []):
            if src.get('method') != 'net':
                continue
            for h in src.get('habitats', []):
                isl = (_HABITATS.get(h) or {}).get('island')
                if isl in islands and s['id'] not in out.setdefault(isl, []):
                    out[isl].append(s['id'])
    return out


FORAGING_WATER_SHARDS = _water_shards(('galatea', 'park'))
AUTOMATON_PARTS = ['ELECTRON_TRANSMITTER', 'FTX_3070', 'ROBOTRON_REFLECTOR', 'SUPERLITE_MOTOR', 'CONTROL_SWITCH', 'SYNTHETIC_HEART']
SCAVENGED = ['DWARVEN_LAPIS_SWORD', 'DWARVEN_EMERALD_HAMMER', 'DWARVEN_DIAMOND_AXE', 'DWARVEN_GOLD_HAMMER']


def S(**stats):
    return {'t': 'stat', 'stats': stats}


def W(e, *when):
    e = dict(e)
    e['when'] = list(when)
    return e


def F(t, **kw):
    d = {'t': t}
    d.update(kw)
    return d


INFO = [F('info')]
# The reference already counts this ability inside the pet's stat lines (petnums statNums) - no extra effect.
INCLUDED = [F('included')]

A = {
 'AMMONITE': {
  'Heart of the Sea': ('Deep Pulse', 'Your pet gains &3+{a} Sea Creature Chance &7for every Heart of the Mountain level you have.',
                       [F('per_hotm', stats={'SEA_CREATURE_CHANCE': 'a'})]),
  'Expert Cave Fisher': ('Cave Angler', 'Inside the Crystal Caverns, gain &9+{a} Double Hook Chance &7per Heart of the Mountain level.',
                         [F('per_hotm', stats={'DOUBLE_HOOK_CHANCE': 'a'}, when=['island:crystal_hollows'])]),
  'Gift of the Ammonite': ('Spiral Blessing', 'Every Mining and Fishing level you have adds &b+{a} Fishing Speed&7, &f+{b} Speed &7and &a+{c} Defense&7.',
                           [F('per_skill', skills=['MINING', 'FISHING'], stats={'FISHING_SPEED': 'a', 'SPEED': 'b', 'DEFENSE': 'c'})]),
 },
 'ANKYLOSAURUS': {
  'Armored Tank': ('Plated Might', 'Turn &a{a}% &7of your &aDefense &7into &cStrength&7. &8(Max +500)',
                   [F('frac_stat', stat='STRENGTH', **{'from': 'DEFENSE'}, v='a', cap=500)]),
  'Unyielding': ('Unbreakable Will', 'Below &c40% &7health, the &aLast Stand &7enchant on your armour gives &a{a}% &7more Defense.',
                 [F('last_stand_boost', v='a', per=2.5, hp=40, enchant='ultimate_last_stand')]),
  'Clubbed Tail': ('Tail Club', 'Every &a5th &7hit splashes &c{a}% &7of the damage onto enemies within &a5 &7blocks; enemies struck hit &a10% &7softer for &a10s&7.',
                   [F('club', every=5, v='a', radius=5)]),
 },
 'ARMADILLO': {
  'Ridable': ('Saddle Up', 'Right-click your summoned pet (or &e/pet ride&7) to ride it; it runs faster the more &fSpeed &7you have and the higher its level.',
              [F('ride', mode='mount', speed=1.6)]),
  'Tunneller': ('Burrower', 'While you ride it in the Crystal Caverns, the pet digs through whatever is in its way with your held tool. Digging uses &6Burrowing Energy&7.',
                [F('tunnel', islands=['crystal_hollows'], energy=100, cost=1, regen=2, per_pass=3)]),
  'Rolling Miner': ('Rolling Start', 'Every &a{a} &7seconds, the next Gemstone you mine drops double.', [F('gem_double', every='a')]),
  'Long Claws': ('Long Claws', 'Gain &e+{a} Mining Spread &7while mining in the Crystal Caverns.',
                 [W(S(MINING_SPREAD='a'), 'island:crystal_hollows')]),
  'Well-Worked': ('Efficient Gait', 'Burrowing uses &a{a}% &7less energy.', [F('tunnel_energy', v='a')]),
 },
 'BABY_YETI': {
  'Yeti Fury': ('Frost Temper', 'The <item:YETI_SWORD> &7gains &c+{a} Damage &7and &b+{a} Intelligence&7, and its cooldown is cut by &a{b}%&7.',
                [F('held_buff', items=['YETI_SWORD'], damage='a', stats={'INTELLIGENCE': 'a'})]),
  'Cold Breeze': ('Snowbound', 'On the winter island your combat and fishing stats are &a{a}% &7higher.',
                  [W(F('pct', group='combat', v='a'), 'island:jerrys_workshop'), W(F('pct', group='fishing', v='a'), 'island:jerrys_workshop')]),
  'Frosty Familiarity': ('Old Friends', 'Gain &b+{a} Magic Find &7against winter sea creatures.', [F('mf_vs', match='winter', v='a')]),
  'Family Gathering': ('Pack Instinct', 'Gain &a10 &7Tracking on the winter island.', INFO),
 },
 'BAL': {
  'Furnace': ('Forge Heart', 'Gain &5+{a} Pristine &7while in the Magma Fields.',
              [W(S(PRISTINE='a'), 'island:crystal_hollows', 'zone:magma')]),
  'Dispersion': ('Heat Sink', 'In the Crystal Caverns each kill lowers your &cHeat &7by &a{a}&7.', [F('external', by='mining')]),
  'Cannibal': ('Kinslayer', 'Deal &c{a}% &7more damage to molten giants of its own kind.',
               [F('dmg', match='bal', v='a')]),
 },
 'BAT': {
  'Candy Lover': ('Sweet Tooth', 'Mobs drop festival candy &a{a}% &7more often.', [F('external', by='mayors')]),
  'Nightmare': ('Night Wings', 'At night gain &b+{a} Intelligence&7, &f+{b} Speed &7and Night Vision.',
                [W(S(INTELLIGENCE='a', SPEED='b'), 'night'), F('potion', effect='NIGHT_VISION', amp=0, when=['night'])]),
  'Wings of Steel': ('Steel Wings', 'Deal &c+{a}% &7damage to spooky enemies during the Spooky Festival.',
                     [F('dmg', match='spooky', v='a', when=['event:spooky_festival'])]),
  'Sonar': ('Echo Sense', 'Spooky sea creatures bite &a{a}% &7more often during the Spooky Festival.', [F('echo_mob', ids=SPOOKY_SC, chance='a', when=['event:spooky_festival'])]),
 },
 'BEE': {
  'Hive': ('Swarm', 'For every player within &a25 &7blocks gain &b+{a} Intelligence&7, &c+{b} Strength &7and &a+{c} Defense&7. &8(Up to 15 players)',
           [F('per_players', radius=25, max=15, stats={'INTELLIGENCE': 'a', 'STRENGTH': 'b', 'DEFENSE': 'c'})]),
  'Busy Buzz Buzz': ('Worker Bee', 'Your pet gains &6+{a} Farming&7, &6Foraging &7and &6Mining Fortune&7.',
                     INCLUDED),
  'Honey Harvester': ('Honey Hunter', 'Each crop you farm has a &a{a}% &7chance to turn up a &aHoney Jar&7.',
                      [F('gather_drop', kind='FARMING', chance='a', items=['HONEY_JAR'])]),
  'Powered by Pollen': ('Pollinator', 'Gain &6+{a} &7Sunflower, Moonflower and Wild Rose Fortune in the Garden.', [F('crop_fortune', crops=['SUNFLOWER', 'DOUBLE_PLANT', 'MOONFLOWER', 'WILD_ROSE'], v='a', when=['island:garden'])]),
 },
 'BINGO': {
  'Lucky Looting': ('Lucky Haul', 'Gain &a{a}% &7more collection items from every source.',
                    [F('collection_bonus', v='a', source='any')]),
  'Climber': ('Climber', 'Earn &a{a}% &7more Heart of the Mountain and Heart of the Forest XP.', INFO),
  'Fast Learner': ('Quick Study', 'Gain &a{a}% &7more Skill XP and Slayer XP.',
                   [F('skill_xp', skill='ALL', v='a')]),
  'Chimera': ('Chimera', 'Your active pet\'s base stats are &a{a}% &7higher.',
              [F('pet_base_all', v='a')]),
  'Scavenger': ('Scrounger', 'Gain &6{a} &7extra coins per monster level on every kill.',
                [F('coins_level', v='a')]),
  'Consumer': ('Long Brew', 'Potion effects you receive last &a{a}% &7longer.', INFO),
  'Power Of Completion': ('Card Complete', 'Gain &c+2 Strength&7, &9+1 Crit Chance &7and &c+5 Health &7for every personal Bingo goal finished this event.', INFO),
 },
 'BLACK_CAT': {
  'Hunter': ('Prowler', 'Gain &f+{a} Speed &7and raise your speed cap by the same amount.',
             INCLUDED),
  'Omen': ('Ill Omen', 'Gain &d+{a} Pet Luck&7.', INCLUDED),
  'Supernatural': ('Nine Lives', 'Gain &b+{a} Magic Find&7.', INCLUDED),
  'Looting': ('Cat Burglar', 'Gain &a{a}% &7more collection items from monsters.',
              [F('collection_bonus', v='a', source='mob')]),
 },
 'BLAZE': {
  'Nether Embodiment': ('Hellborn', 'Your combat and miscellaneous stats are &a{a}% &7higher on the Crimson Isle.',
                        [W(F('pct', group='combat', v='a'), 'island:crimson_isle'), W(F('pct', group='misc', v='a'), 'island:crimson_isle')]),
  'Bling Armor': ('Flame Polish', 'Blaze Armor stats are raised by &a{a}%&7.',
                  [F('armor_buff', match=['BLAZE_'], v='a')]),
  'Fusion-Style Potato': ('Double Bake', 'Hot potato books on your gear count twice.',
                          [F('potato_double')]),
 },
 'BLUE_WHALE': {
  'Ingest': ('Blubber', 'While you have any absorption, gain &a+{a}% &7damage reduction.',
             [F('dmg_taken', absorption=True, v='a')]),
  'Bulk': ('Bulk', 'Gain &a{a} Defense &7for every &c20 Max Health&7.',
           [F('per_stat', stat='DEFENSE', **{'from': 'HEALTH'}, every=20, v='a')]),
  'Archimedes': ('Buoyancy', 'Gain &c+{a}% Max Health&7.', [F('pct', stats=['HEALTH'], v='a')]),
 },
 'CHICKEN': {
  'Free Range': ('Free Roam', 'Gain &6+{a} Farming Fortune &7anywhere but your private island.',
                 [W(S(FARMING_FORTUNE='a'), 'public')]),
  'Eggstra Loot': ('Nest Egg', 'Chickens always drop an Egg; animals have a &a{a}% &7chance to drop their loot again.',
                   [F('kill_drop', match='chicken', chance=100, items=['EGG']), F('kill_double', match='passive', chance='a')]),
  'Light Feet': ('Featherfall', 'Fall damage is reduced by &a{a}%&7.', [F('fall', v='a')]),
 },
 'CROW': {
  'Quick Hands': ('Quick Talons', 'Weapon ability cooldowns are &a{a}% &7shorter.', [F('cooldown', v='a')]),
  'Camouflage': ('Shadowcloak', 'After using an ability gain &a+{a} Defense &7for &a20 &7seconds. &8(Max 500)',
                 [F('cast_buff', stats={'DEFENSE': 'a'}, dur=20, cap=500)]),
  'Insightful': ('Keen Eye', 'Abilities have a &a{a}% &7chance to cost no mana.', [F('free_cast', v='a')]),
 },
 'DOLPHIN': {
  'Pod Tactics': ('Pod Swim', 'Gain &b+{a} Fishing Speed &7for each player within &a30 &7blocks. &8(Up to 5)',
                  [F('per_players', radius=30, max=5, stats={'FISHING_SPEED': 'a'})]),
  'Echolocation': ('Sonar Click', 'Gain &3+{a} Sea Creature Chance&7.', [S(SEA_CREATURE_CHANCE='a')]),
  'Splash Surprise': ('Splash Stun', 'Sea creatures are stunned for &a5s &7when you hook them.', [F('sc_stun', dur=5)]),
 },
 'DROPLET_WISP': {
  'Drophammer': ('Dousing Strike', 'Break fire pillars twice as fast, healing &c15% &7of your max Health over &a3s&7.', [F('pillar', mult=2, heal=15)]),
  'Bulwark': ('Ember Ward', 'Killing blazes hardens you against blazes and demons: every &a100 &7kills adds &a+30 Defense &7and &f+3 True Defense &7against them. &8(Up to 10 times)',
              [F('bulwark', match='blaze', every=100, defense=30, true_defense=3, max=10)]),
  'Blaze Slayer': ('Blaze Hunter', 'Earn &a{a}x &7Combat XP from blazes.', [F('combat_xp', match='blaze', v='a')]),
 },
 'EERIE': {
  'Fearnesy': ('Shared Dread', 'Fear from spook armour in your wardrobe counts even when not worn.', INFO),
  'Fearama': ('Dread Edge', 'Deal &c{a}% &7more damage to spooky mobs for every point of Fear.', [F('external', by='weapons')]),   # wave 5: weapons owns Fear and applies this (Passives "eerie_fearama"); ours doubled it
  'Fearcreasing': ('Fear Harvest', 'Gain &a+{a} &7Fear for every 10 primal fears slain. &8(Up to 150)', [F('external', by='weapons')]),   # wave 5: weapons counts primal fears and adds this to Fear (Passives "eerie_fearcreasing")
 },
 'ELEPHANT': {
  'Stomp': ('Heavy Tread', 'Gain &a{a} Defense &7for every &f100 Speed&7.',
            [F('per_stat', stat='DEFENSE', **{'from': 'SPEED'}, every=100, v='a')]),
  'Walking Fortress': ('Living Fortress', 'Gain &c{a} Health &7for every &a10 Defense&7.',
                       [F('per_stat', stat='HEALTH', **{'from': 'DEFENSE'}, every=10, v='a')]),
  'Trunk Efficiency': ('Trunk Harvest', 'Gain &6+{a} Farming Fortune&7.', [S(FARMING_FORTUNE='a')]),
  'Abundant Harvest': ('Bountiful', 'Earn &a+{a}% &7more Sowdust while farming.', INFO),
 },
 'ENDERMAN': {
  'Enderian': ('Void Kin', 'Take &a{a}% &7less damage from Ender mobs.', [F('dmg_taken', match='ender', v='a')]),
  'Teleport Savvy': ('Blink Strike', 'Teleport abilities grant &c+{a} &7weapon damage for &a5s&7.',
                     [F('cast_weapon_dmg', match='transmission', v='a', dur=5)]),
  'Zealot Madness': ('Zealous', 'Special zealots appear &a{a}% &7more often for you.', [F('kill_bonus', match='end_zealot_5', dial='combatzones.special-zealot-chance', base=0.00238095, v='a', item='SUMMONING_EYE', msg='&dYour pet sniffed out a Special Zealot: &5Summoning Eye&d!')]),
  'Enderman Slayer': ('Void Hunter', 'Earn &a{a}x &7Combat XP from Endermen.', [F('combat_xp', match='enderman', v='a')]),
 },
 'ENDERMITE': {
  'More Stonks': ('Stone Snacker', 'End stone has a &a+{a}% &7chance to drop an extra block.',
                  [F('gather_drop', kind='MINING', chance='a', items=['ENDER_STONE'], what='END_STONE')]),
  'Daily Commuter': ('Commuter', 'Teleport abilities cost &a{a}% &7less mana.', [F('mana_cost', v='a', match='transmission')]),
  'Mite Bait': ('Mite Lure', 'Mining End Stone has &a{a}% &7chance per &dPet Luck &7to dig up a bonus nest mite.',
                [F('gather_spawn', kind='MINING', what=['END_STONE', 'ENDER_STONE'], mob='end_nest_endermite', chance='a', per_stat='PET_LUCK')]),
  'Sacrificer': ('Altar Favour', 'Bonus rolls at the dragon altar are &a{a}% &7more likely.', INFO),
 },
 'ENDER_DRAGON': {
  'End Strike': ('Void Bane', 'Deal &c{a}% &7more damage to Ender mobs.', [F('dmg', match='ender', v='a')]),
  'One With The Dragon': ('Dragonbond', 'The <item:ASPECT_OF_THE_DRAGON> &7gains &c+{a} Damage &7and &c+{b} Strength&7.',
                          [F('held_buff', items=['ASPECT_OF_THE_DRAGON'], damage='a', stats={'STRENGTH': 'b'})]),
  'Superior': ('Apex', 'All combat stats and &bMagic Find &7are &a{a}% &7higher.',
               [F('pct', group='combat', v='a'), F('pct', stats=['MAGIC_FIND'], v='a')]),
 },
 'FLYING_FISH': {
  'Quick Reel': ('Swift Line', 'Gain &b+{a} Fishing Speed&7.', [S(FISHING_SPEED='a')]),
  'Water Bender': ('Tide Strength', 'Gain &c+{a} Strength &7and &a+{a} Defense &7near water.',
                   [W(S(STRENGTH='a', DEFENSE='a'), 'water')]),
  'Lava Bender': ('Tide and Magma', 'Gain &c+{a} Strength &7and &a+{a} Defense &7near water or lava.',
                  [W(S(STRENGTH='a', DEFENSE='a'), 'water')]),
  'Deep Sea Diver': ('Deep Diver', 'Diver Armor stats are raised by &a{a}%&7.', [F('armor_buff', match=['DIVER_'], v='a')]),
  'Magmatic Diver': ('Magma Diver', 'Deep-sea diving, magma and abyssal armour stats are raised by &a{a}%&7.',
                     [F('armor_buff', match=['DIVER_', 'MAGMA_LORD_', 'ABYSSAL_'], v='a')]),
  'Rapid Decay': ('Rapid Rot', 'The Flash enchantment triggers &a{a}% &7more often.', INFO),
 },
 'FROG': {
  'Hop': ('Leapfrog', 'Each jump grants &6+{a} Foraging Fortune &7for &a20 &7seconds.',
          [F('jump_buff', stats={'FORAGING_FORTUNE': 'a'}, dur=20)]),
  'Happy Tree Friends@RARE': ('Chorus', 'For every other summoned Frog on your island gain &6+{a} Foraging Fortune&7. &8(Up to 10)',
                              [F('per_players', radius=10000, max=10, pet='FROG', stats={'FORAGING_FORTUNE': 'a'})]),
  'Happy Tree Friends@EPIC': ('Chorus', 'For every other summoned Frog on your island gain &6+{a} Foraging Fortune&7. &8(Up to 10)',
                              [F('per_players', radius=10000, max=10, pet='FROG', stats={'FORAGING_FORTUNE': 'a'})]),
  'Happy Tree Friends@LEGENDARY': ('Chorus', 'For every other summoned Frog on your island gain &6+{a} Foraging Fortune&7. &8(Up to 10)',
                                   [F('per_players', radius=10000, max=10, pet='FROG', stats={'FORAGING_FORTUNE': 'a'})]),
  'Happy Tree Friends': ('Chorus', 'For every other summoned Frog on your island gain &6+{a} Foraging Fortune &7and &b+{b} Fishing Speed&7. &8(Up to 10)',
                         [F('per_players', radius=10000, max=10, pet='FROG', stats={'FORAGING_FORTUNE': 'a', 'FISHING_SPEED': 'b'})]),
  'Hunting Enjoyer': ('Pond Skimmer', 'Water shards are &a{a}% &7more likely on foraging islands.', [F('fish_shard', dial='hunting.fish-shard-chance', base=0.01, v='a', shards=FORAGING_WATER_SHARDS)]),
  'Home Sweet Home': ('Lily Throne', 'Gold and Diamond trophy frogs are &a{a}% &7more likely.', [F('echo_item', items=['FROGGLES_GOLD', 'FROGGLES_DIAMOND'], chance='a')]),
 },
 'FROST_WISP': {
  'Icehammer': ('Frost Strike', 'Break fire pillars twice as fast, healing &c25% &7of your max Health over &a3s&7.', [F('pillar', mult=2, heal=25)]),
  'Bulwark': ('Ember Ward', 'Killing blazes hardens you against blazes and demons: every &a100 &7kills adds &a+30 Defense &7and &f+3 True Defense &7against them. &8(Up to 10 times)',
              [F('bulwark', match='blaze', every=100, defense=30, true_defense=3, max=10)]),
  'Blaze Slayer': ('Blaze Hunter', 'Earn &a{a}x &7Combat XP from blazes.', [F('combat_xp', match='blaze', v='a')]),
  'Extinguish': ('Cool Spring', 'In combat on the Crimson Isle a healing pool appears every &a8s&7. Stepping in heals &c15% &7of your max Health, then &c4%&7/s for &a8s&7.',
                 [W(F('pool', every=8, heal=15, regen=4, dur=8), 'island:crimson_isle')]),
 },
 'GHOUL': {
  'Undead Slayer': ('Grave Hunter', 'Earn &a{a}x &7Combat XP from zombies.', [F('combat_xp', match='zombie', v='a')]),
  'Army of the Dead': ('Soul Hoard', 'Store &a2 &7more souls and catch souls &a{a}% &7more often.', [F('external', by='weapons')]),
  'Reaper Soul': ('Reaper\'s Due', 'Summoned mobs cost &a{a}% &7less, deal &c{b}% &7more damage and have &c{c}% &7more health.', [F('external', by='weapons')]),
 },
 'GIRAFFE': {
  'Good Heart': ('Big Heart', 'Gain &c+{a} Health Regen&7.', [S(HEALTH_REGEN='a')]),
  'Higher Ground': ('High Vantage', 'Gain &a{a}% &9Crit Damage &7and &cStrength &7for every 0.1 Swing Range above 3. &8(Up to 6)',
                    [F('per_stat_pct', stats=['CRIT_DAMAGE', 'STRENGTH'], **{'from': 'SWING_RANGE'}, every=0.1, above=3, top=6, v='a')]),
  'Long Neck': ('Long Reach', 'Melee hits on targets over &a3 &7blocks away deal &c{a}% &7more damage.',
                [F('dmg_far', dist=3, v='a')]),
 },
 'GLACIAL_WISP': {
  'Icehammer': ('Frost Strike', 'Break fire pillars, healing &c40% &7of your max Health over &a3s&7.', [F('pillar', mult=1, heal=40)]),
  'Bulwark': ('Ember Ward', 'Killing blazes hardens you against blazes and demons: every &a100 &7kills adds &a+30 Defense &7and &f+3 True Defense &7against them. &8(Up to 10 times)',
              [F('bulwark', match='blaze', every=100, defense=30, true_defense=3, max=10)]),
  'Blaze Slayer': ('Blaze Hunter', 'Earn &a{a}x &7Combat XP from blazes.', [F('combat_xp', match='blaze', v='a')]),
  'Extinguish': ('Cool Spring', 'In combat on the Crimson Isle a healing pool appears every &a8s&7. Stepping in heals &c20% &7of your max Health, then &c7%&7/s for &a8s&7.',
                 [W(F('pool', every=8, heal=20, regen=7, dur=8), 'island:crimson_isle')]),
 },
 'GLACITE_GOLEM': {
  'Powder-powered': ('Powder Engine', 'Gain &a{a}% &7more Glacite Powder.', [F('external', by='mining')]),
  'Iceborn': ('Iceborn', 'Gain &6+{a} Mining Fortune &7in the glacite tunnels.',
              [W(S(MINING_FORTUNE='a'), 'island:glacite_tunnels')]),
  'Frozen Perfection': ('Frozen Perfection', 'Gain &5+{a} Pristine &7per frozen corpse looted in the current mineshaft.', [F('external', by='mining')]),
 },
 'GOBLIN': {
  'Grunt Work': ('Grunt Labour', 'Gain &6+{a} Mining Speed &7while mining ores.', [W(S(MINING_SPEED='a'), 'dig:ore')]),
  'Fetid Thief': ('Sticky Fingers', 'Gain &e+{a} Mining Spread &7in the Crystal Caverns.', [W(S(MINING_SPREAD='a'), 'island:crystal_hollows')]),
  'Free-range Eggs': ('Egg Hunter', 'Rare goblin eggs turn up &a{a}% &7more often.', [F('echo_item', items=['GOBLIN_EGG_GREEN', 'GOBLIN_EGG_RED', 'GOBLIN_EGG_YELLOW', 'GOBLIN_EGG_BLUE'], chance='a')]),
 },
 'GOLDEN_DRAGON': {
  'Gold\'s Power': ('Midas Touch', 'Gold-greed weapon abilities are &a{a}% &7stronger.', [F('held_greed', items=['MIDAS_SWORD', 'MIDAS_STAFF'], v='a')]),
  'Shining Scales': ('Gilded Scales', 'Your pet gains &c+11.1 Strength &7and &b+2.2 Magic Find &7for every digit of your Gold collection. &8(Max 100M)',
                     [F('per_digit', collection='GOLD_INGOT', stats={'STRENGTH': '11.1', 'MAGIC_FIND': '2.2'}, max=9)]),
  'Dragon\'s Greed': ('Hoarder', 'Gain &c+{a}% Strength &7per &b5 Magic Find&7. &8(Max +{b}%)',
                      [F('per_stat_pct', stats=['STRENGTH'], **{'from': 'MAGIC_FIND'}, every=5, v='a', cap='b')]),
  'Legendary Treasure': ('Dragon Hoard', 'Deal &c{a}% &7more damage for every million coins in your bank. &8(Max {b}%)',
                         [F('dmg_bank', v='a', cap='b')]),
  'Symbiosis': ('Kinship', 'At level 200, gain &6+5 coins &7per kill for every other maxed combat pet you own.',
                [F('symbiosis', skill='COMBAT', coins=5)]),
 },
 'GOLEM': {
  'Last Stand': ('Final Bulwark', 'Below &c20% &7Health take &a20% &7less damage, gain a shield worth &a40% &7of your max Health and deal &c40% &7more damage. &8(12s, 60s cooldown)',
                 [F('last_stand', hp=20, reduce=20, shield=40, dmg=40, dur=12, cd=60)]),
  'Ricochet': ('Iron Rebound', '&a{a}% &7of attacks against you bounce back at the attacker.', [F('ricochet', v='a')]),
  'Toss': ('Hurl', 'Every &a5 &7hits, fling the enemy upward for &c5x &7damage. &8(5s cooldown)', [F('toss', every=5, mult=5, cd=5)]),
 },
 'GRANDMA_WOLF': {
  'Kill Combo': ('Kill Streak', 'Chain kills for buffs: &a5 &7(+3% &bMagic Find&7), &a10 &7(+coins), &a15 &7(+3% &bMagic Find&7), &a20 &7(+15 &3Combat Wisdom&7), &a25 &7(+3% &bMagic Find&7), &a30 &7(+coins). Combo windows shrink from &a{a}s &7to &a{f}s&7.',
                 [F('combo')]),
 },
 'GRIFFIN': {
  'Odyssey': ('Legend Seeker', 'Mythological creatures can rise from burrows; their strength follows this pet\'s rarity.', [F('external', by='mayors')]),
  'Sacred Strength': ('Hallowed Might', 'Gain &c+{a}% Strength &7while above &a85% &7Health.',
                      [W(F('pct', stats=['STRENGTH'], v='a'), 'hp_above:85')]),
  'King of Kings': ('Crowned', 'Gain &b+{a} Magic Find &7against mythological creatures.', [F('mf_vs', match='mythological', v='a')]),
  'Ancient Earth': ('Old Earth', 'Gain &a+1 &7Tracking per burrow dug in your current chain.', INFO),
 },
 'GUARDIAN': {
  'Laserbeam': ('Prism Beam', 'Every &a3s&7, zap a nearby enemy for &b{a}x &7your Intelligence.',
                [F('zap', v='a', every=3, radius=8)]),
  'Enchanting Wisdom Boost': ('Lorekeeper', 'Gain &3+{a} Enchanting Wisdom&7.', [S(ENCHANTING_WISDOM='a')]),
  'Mana Pool': ('Mana Tide', 'Regenerate &b{a}% &7more mana, doubled near water.',
                [S(MANA_REGEN='a'), W(S(MANA_REGEN='a'), 'water')]),
  'Lucky Seven': ('Seventh Seal', '&a+{a}% &7chance to find ultra rare books while experimenting.', [F('external', by='enchanting')]),
 },
 'HEDGEHOG': {
  'Spiky Quills': ('Quills', 'Deal &c{a}% &7more damage to pests.', [F('dmg', match='pest', v='a')]),
  'Fearsome Farmer': ('Pest Terror', 'Gain &6+{a} Farming Fortune &7and &a+{b} &7Overbloom against pests.', [W(S(FARMING_FORTUNE='a'), 'target:pest')]),
  'Hunter\'s Insight': ('Field Notes', 'Gain &6+0.7 Farming Fortune &7per pest bestiary tier.', [F('external', by='progression')]),
 },
 'HERMIT_CRAB': {
  'Comfort Zone': ('Shell Shelter', 'Catching treasure grants &b+{a} Fishing Speed &7for &a30s&7.',
                   [F('treasure_buff', stats={'FISHING_SPEED': 'a'}, dur=30)]),
  'Seafloor Scalper': ('Seabed Scour', 'Treasure is &a{a}% &7more likely to be great or outstanding.', [F('treasure_up', v='a', great=treasure_table('great'), outstanding=treasure_table('outstanding'))]),
  'Crab Rave': ('Crab Dance', 'Gain &6+{a} Treasure Chance &7for each player within &a30 &7blocks with a Hermit Crab summoned. &8(Up to 5)',
                [F('per_players', radius=30, max=5, pet='HERMIT_CRAB', stats={'TREASURE_CHANCE': 'a'})]),
  'Hotspot Hazard': ('Hot Water', 'Hotspot sea creatures bite &a{a}% &7more often.', [F('echo_mob', ids=HOTSPOT_SC, chance='a')]),
 },
 'HORSE': {
  'High Stride': ('Long Stride', 'Permanent Jump Boost IV.', [F('potion', effect='JUMP_BOOST', amp=3)]),
  'Stampede': ('Gallop', 'Each kill grants &f+{a} Speed &7and &c+{b} Strength &7for &a5s&7. &8(Up to 20 stacks)',
               [F('kill_stacks', stats={'SPEED': 'a', 'STRENGTH': 'b'}, dur=5, max=20)]),
  'Trample': ('Trample', 'Falls of &a20+ &7blocks deal no damage to you and hit mobs within &a3 &7blocks for &c{a}% &7of your weapon damage per block fallen.',
              [F('trample', min=20, v='a', radius=3)]),
 },
 'HOUND': {
  'Scavenger': ('Scrounger', 'Gain &6+{a} coins &7per monster kill.', [F('coins_kill', v='a')]),
  'Finder': ('Bloodhound', 'Monsters drop their armour &a{a}% &7more often.', [F('kill_armor', v='a')]),
  'Pack Slayer': ('Pack Breaker', 'Earn &a{a}x &7Combat XP from wolves.', [F('combat_xp', match='wolf', v='a')]),
 },
 'JADE_DRAGON': {
  'Forest Power': ('Grove Might', 'Axes you hold gain &c+{a} Strength &7and &f+{b} Speed&7.',
                   [F('category_buff', categories=['AXE'], stats={'STRENGTH': 'a', 'SPEED': 'b'})]),
  'Jade Scale': ('Jade Scales', 'Gain &6+15 Foraging Fortune &7and &2+4 Sweep &7per digit of your Mangrove collection. &8(Max 10M)',
                 [F('per_digit', collection='MANGROVE_LOG', stats={'FORAGING_FORTUNE': '15', 'SWEEP': '4'}, max=8)]),
  'Dragon\'s Pride': ('Proud Bark', 'Gain &6+1 Foraging Fortune &7per &25 Sweep&7.',
                      [F('per_stat', stat='FORAGING_FORTUNE', **{'from': 'SWEEP'}, every=5, v='1')]),
  'Apex Predator': ('Canopy King', 'Total Sweep rises &a0.1% &7per maxed attribute.', [F('attr_max', stat='SWEEP', v=0.1, attrs=HUNT_ATTRS)]),
  'Symbiosis': ('Kinship', 'At level 200, gain &6+4 Foraging Fortune &7for every other maxed foraging pet you own.',
                [F('symbiosis', skill='FORAGING', stats={'FORAGING_FORTUNE': '4'})]),
 },
 'JELLYFISH': {
  'Radiant Scyphozoa': ('Glowing Bell', 'In dungeons, power orbs cost &a{a}% &7less mana.', [F('mana_cost', match='power_orb', v='a', when=['dungeon'])]),
  'Stored Energy': ('Stored Charge', 'In dungeons, every 2,000 health healed on allies trims &a{a}s &7off your Wish cooldown. &8(Max 30s)', INFO),
  'Powerful Potions': ('Strong Brew', 'In dungeons, dungeon potions are &a{a}% &7stronger.', INFO),
 },
 'JERRY': {
  'Jerry#1': ('Barry', 'Barry gives you a &a50% &7chance to deal your normal damage.', INCLUDED),
  'Jerry#2': ('Barry', 'Barry gives you a &a100% &7chance to get normal mob drops.', INCLUDED),
  'Jerry#3': ('Barry', 'Barry actually adds &c{a} &7damage to the <item:ASPECT_OF_THE_JERRY>&7.',
              [F('held_buff', items=['ASPECT_OF_THE_JERRY'], damage='a', stats={})]),
  'Jerry#4': ('Barry', 'Barry has a tiny chance to find candy when you kill mobs.', [F('kill_drop', match='*', chance=0.05, items=['JERRY_CANDY'])]),
 },
 'KUUDRA': {
  'Crimson': ('Crimson Tithe', 'Gain &a{a}% &7more crimson essence.', [F('external', by='kuudra')]),
  'Wither Bait': ('Wither Lure', 'Vanquishers appear &a{a}% &7more often.', [F('kill_bonus', not_match='vanquisher', dial='crimson.vanquisher-chance', base=0.002, v='a', spawn='crimson_vanquisher', msg='&5&lA VANQUISHER IS SPAWNING NEARBY!', when=['island:crimson_isle'])]),
  'Kuudra Fortune': ('Lava Fortune', 'Gain &6+{a} Mining Fortune &7on the Crimson Isle.', [W(S(MINING_FORTUNE='a'), 'island:crimson_isle')]),
  'Trophy Bait': ('Trophy Lure', 'Gain &6+{a} Trophy Fish Chance &7on the Crimson Isle.', [W(S(TROPHY_FISH_CHANCE='a'), 'island:crimson_isle')]),
  'Kuudra Specialist': ('Leviathan Bane', 'Deal &c{a}% &7more damage to the great leviathan and its brood.', [F('dmg', match='kuudra', v='a')]),
 },
 'LION': {
  'Primal Force': ('Primal Force', 'Melee weapons you hold gain &c+{a} Damage &7and &c+{b} Strength&7.',
                   [F('category_buff', categories=['SWORD', 'LONGSWORD', 'DUNGEON_WEAPON', 'FISHING_WEAPON', 'GAUNTLET'], damage='a', stats={'STRENGTH': 'b'})]),
  'First Pounce': ('Opening Pounce', 'First Strike, Triple-Strike and Combo are &a{a}% &7stronger.', [F('external', by='enchanting')]),
  'King of the Jungle': ('Pride Leader', 'Deal &c+{a}% &7damage to mobs that have hit you.', [F('dmg_retaliate', v='a')]),
 },
 'MAGMA_CUBE': {
  'Slimy Minions': ('Slime Foreman', 'Slime and magma cube minions work &a{a}% &7faster on your island.', [F('external', by='minions')]),
  'Salt Blade': ('Salted Edge', 'Deal &c{a}% &7more damage to slimes.', [F('dmg', match='slime', v='a')]),
  'Hot Ember': ('Ember Glow', 'Ember armour stats are raised by &a{a}%&7.', [F('armor_buff', match=['EMBER_'], v='a')]),
 },
 'MAMMOTH': {
  'Wooly Coat': ('Woolly Coat', '&a{a}% &7chance for mob hits not to chill you in the glacite mineshafts.', INFO),
  'Tusk Luck': ('Tusk Luck', 'Gain &b+{a} Magic Find &7per &6100 Mining Fortune&7, doubled in the glacite tunnels.',
                [F('per_stat', stat='MAGIC_FIND', **{'from': 'MINING_FORTUNE'}, every=100, v='a'),
                 W(F('per_stat', stat='MAGIC_FIND', **{'from': 'MINING_FORTUNE'}, every=100, v='a'), 'island:glacite_tunnels')]),
  'Corpse Crusher': ('Corpse Crusher', 'Gain &6+{a} Mining Fortune &7per frozen corpse looted in the current mineshaft.', INFO),
 },
 'MEGALODON': {
  'Blood Scent': ('Blood in the Water', 'Deal up to &c+{a}% &7damage the more health your target is missing.', [F('dmg_missing', v='a')]),
  'Enhanced Scales': ('Festival Scales', 'Base stats double during the Fishing Festival.', [F('pet_base', v='100', when=['event:fishing_festival'])]),
  'Feeding Frenzy': ('Frenzy', 'Sharks bite &a{a}% &7more often during the Fishing Festival.', [F('echo_mob', ids=SHARK_SC, chance='a', when=['event:fishing_festival'])]),
 },
 'MITHRIL_GOLEM': {
  'Mithril Affinity': ('Mithril Kin', 'Gain &6+{a} Mining Speed &7while mining Mithril.', [W(S(MINING_SPEED='a'), 'dig:mithril')]),
  'Subterranean Battler': ('Tunnel Brawler', 'All combat stats are &a{a}% &7higher on mining islands.', [W(F('pct', group='combat', v='a'), MINING)]),
  'The Smell Of Powder': ('Powder Nose', 'Gain &a{a}% &7more Mithril Powder.', [F('external', by='mining')]),
  'Refined Senses': ('Keen Senses', '&bMagic Find &7is &a{a}% &7higher on mining islands.', [W(F('pct', stats=['MAGIC_FIND'], v='a'), MINING)]),
 },
 'MOLE': {
  'Archaeologist': ('Digger', 'Scavenged items appear &a{a}% &7more often in the Crystal Caverns ruins.', [F('echo_item', items=SCAVENGED, chance='a', when=['island:crystal_hollows'])]),
  'Magnetic Nose': ('Magnet Snout', 'Automatons drop their parts &a{a}% &7more often.', [F('echo_item', items=AUTOMATON_PARTS, chance='a')]),
  'Nucleic Veins': ('Core Seeker', 'The crystal nucleus has a &a{a}% &7chance to give an extra item.', INFO),
 },
 'MONKEY': {
  'Treeborn': ('Canopy Born', 'Gain &6+{a} Foraging Fortune&7.', [S(FORAGING_FORTUNE='a')]),
  'Vine Swing': ('Vine Swing', 'Gain &f+{a} Speed &7in the Park.', [W(S(SPEED='a'), 'island:park')]),
  'Evolved Axes': ('Axe Knack', 'Gain &2+{a} Sweep &7in the Park.', [W(S(SWEEP='a'), 'island:park')]),
 },
 'MOOSHROOM_COW': {
  'Mushroom Eater': ('Fungal Snack', 'Farming a crop has a &a{a}% &7chance to drop a mushroom.',
                     [F('gather_drop', kind='FARMING', chance='a', items=['RED_MUSHROOM', 'BROWN_MUSHROOM'])]),
  'Farming Strength': ('Strong Back', 'Gain &6+0.7 Farming Fortune &7per &c{a} Strength&7.',
                       [F('per_stat', stat='FARMING_FORTUNE', **{'from': 'STRENGTH'}, every='a', v='0.7')]),
  'Bovine Blessing': ('Bovine Gift', 'Farming crops has a &a{a}% &7chance to find &aTasty Cheese &7or &aDung&7.',
                      [F('gather_drop', kind='FARMING', chance='a', items=['CHEESE_FUEL', 'DUNG'])]),
 },
 'MOSQUITO': {
  'Smooth Jazz': ('Smooth Jazz', 'Pest vinyls are &a{a}% &7more effective.', INFO),
  'Buzzin\' Barterer': ('Buzzing Trader', 'Gain &6+{a} &7Sugar Cane Fortune for every unique garden visitor served. &8(Max 175)', INFO),
  'Bloodsucker\'s Betrayal': ('Turncoat', 'Pest traps catch their next pest &a{a}% &7faster.', INFO),
 },
 'OCELOT': {
  'Foraging Wisdom Boost': ('Woodwise', 'Gain &3+{a} Foraging Wisdom&7.', [S(FORAGING_WISDOM='a')]),
  'Tree Hugger': ('Tree Hugger', 'Foraging minions work &a{a}% &7faster on your island.', [F('external', by='minions')]),
  'Tree Essence': ('Sap Spark', 'Chopping a log has a &a{a}% &7chance to drop experience.',
                   [F('gather_orb', kind='FORAGING', chance='a')]),
 },
 'ORCHID_MANTIS': {
  'Intelligent Specimen': ('Bright Mind', 'Farming tools earn &a{a}% &7more experience.', INFO),
  'Swift Sickles': ('Swift Sickles', 'Every &f3 Speed &7above 100 becomes &6+{a} Farming Fortune&7.',
                    [F('per_stat', stat='FARMING_FORTUNE', **{'from': 'SPEED'}, every=3, above=100, v='a')]),
  'Orchid Nectar': ('Orchid Nectar', 'Farming crops has a &a{a}% &7chance to find &aJelly &7or &aPlant Matter&7.',
                    [F('gather_drop', kind='FARMING', chance='a', items=['JELLY', 'PLANT_MATTER'])]),
 },
 'OWL': {
  'Training Refunds': ('Thrifty Trainer', 'Coins spent on pet training sessions slowly earn you up to &a5% &7off future ones.', INFO),
  'Efficient Trainer': ('Study Buddy', 'Training sessions you add this pet to are more efficient.', INFO),
  'Fast Learner': ('Night Study', 'Always grants &3+{a} Taming Wisdom&7.', [S(TAMING_WISDOM='a')]),
 },
 'PARROT': {
  'Flamboyant': ('Show-off', 'Adds &a{a} &7levels to intimidation accessories.', INFO),
  'Repeat': ('Encore', 'Potions last &a{a}% &7longer.', [F('external', by='alchemy')]),
  'Bird Discourse': ('Rallying Squawk', 'Players within &a20 &7blocks gain &c+{a} Strength&7. &8(Does not stack)',
                     [F('aura', radius=20, stats={'STRENGTH': 'a'})]),
  'Parrot Feather Infusion': ('Feathered Brew', 'While summoned or in your pets menu, god potions and mixins last &a{a}% &7longer.', INFO),
 },
 'PENGUIN': {
  'Thick Blubber': ('Thick Blubber', 'Each sea creature you catch lowers your Cold by &a{a}&7.', INFO),
  'Chilly Reception': ('Huddle', 'Gain &b+{a} Cold Resistance &7per player within &a30 &7blocks. &8(Up to 10)',
                       [F('per_players', radius=30, max=10, stats={'COLD_RESISTANCE': 'a'})]),
  'Subzero Hero': ('Subzero Swimmer', 'Gain &b+{a} Fishing Speed &7in the glacite tunnels.', [W(S(FISHING_SPEED='a'), 'island:glacite_tunnels')]),
 },
 'PHOENIX': {
  'Rekindle': ('Rebirth', 'Instead of dying, become immune and gain &c+{a} Strength &7for &a{b} &7seconds. &8(60s cooldown)',
               [F('rekindle', stats={'STRENGTH': 'a'}, dur='b', cd=60)]),
  'Fourth Flare': ('Fourth Ember', 'Every 4th melee hit ignites the target for &c{a}x &7your &9Crit Damage &7each second for &a{b} &7seconds.',
                   [F('flare', every=4, v='a', dur='b')]),
  'Magic Bird': ('Firewings', 'You can always fly on your private island and in the Garden.', [F('flight', islands=['private_island', 'garden'])]),
  'Eternal Coins': ('Undying Purse', 'You no longer lose coins when you die.', [F('no_coin_loss')]),
 },
 'PIG': {
  'Hamfisted': ('Ham Fist', 'Coins from shiny pigs are &a{a}% &7higher.', INFO),
  'Shining Stampede': ('Shine Rush', 'Gain &6+{a} &7Potato Fortune per shiny pig bestiary tier.', INFO),
  'Pig Parade': ('Pig Parade', 'Base stats are &a{a}% &7higher during the Year of the Pig.', [F('pet_base', v='a', when=['event:year_of_the_pig'])]),
 },
 'PIGMAN': {
  'Bacon Farmer': ('Swineherd', 'Pig minions work &a{a}% &7faster on your island.', [F('external', by='minions')]),
  'Pork Master': ('Pork Champion', 'The <item:PIGMAN_SWORD> &7gains &c+{a} Damage &7and &c+{b} Strength&7.',
                  [F('held_buff', items=['PIGMAN_SWORD'], damage='a', stats={'STRENGTH': 'b'})]),
  'Giant Slayer': ('Giant Killer', 'Deal &c+50% &7damage to monsters level 50+ and &c+75% &7to level 100+.',
                   [F('dmg_level', tiers=[[50, 50], [100, 75]])]),
 },
 'PRECURSOR_DRONE': {
  'Contraband': ('Contraband', 'Catching a sea creature has a &a10% &7chance to also hook treasure.', [F('sc_treasure', chance=10, table=treasure_table('good'))]),
  'Grungle': ('Single Throw', 'Only your foraging axe can be thrown, but it loses its throwing penalty.', INFO),
  'Mining Off Camera': ('Off the Books', 'Each collection point while mining has a &a0.005% &7chance to drop a random enchanted mining material.',
                        [F('gather_drop', kind='MINING', chance='0.005', items=['ENCHANTED_COBBLESTONE', 'ENCHANTED_COAL', 'ENCHANTED_IRON', 'ENCHANTED_GOLD', 'ENCHANTED_REDSTONE', 'ENCHANTED_LAPIS_LAZULI', 'ENCHANTED_DIAMOND', 'ENCHANTED_EMERALD', 'ENCHANTED_MITHRIL'])]),
 },
 'RABBIT': {
  'Happy Feet': ('Spring Step', 'Jump potions also grant &f+{a} Speed&7.', [W(S(SPEED='a'), 'potion:jump_boost')]),
  'Farming Wisdom Boost': ('Field Wisdom', 'Gain &3+{a} Farming Wisdom&7.', [S(FARMING_WISDOM='a')]),
  'Efficient Farming': ('Burrow Crew', 'Farming minions work &a{a}% &7faster on your island.', [F('external', by='minions')]),
  'Chocolate Injections': ('Sugar Rush', 'Chocolate production rises by &a+{a}x &7and duplicate rabbits give &a+{b}% &7chocolate.', INFO),
 },
 'RAT': {
  'Morph': ('Shapeshift', 'Right-click your summoned pet (or &e/pet morph&7) to take its form. Do it again to change back.', [F('morph', scale=0.3)]),
  'CHEESE!': ('Cheese Nose', 'While summoned, a piece of cheese turns up nearby every so often. Follow your nose (the particles) and walk over it. Delicious.', [F('cheese', every=90, radius=12, life=60)]),
  'Rat\'s Blessing': ('Rodent Blessing', 'Finding cheese blesses a random nearby player with &b+{a} Magic Find &7for &a{b} &7seconds; a drop they get meanwhile has a &a20% &7chance to come to you too.', [F('rat_blessing', mf='a', dur='b', copy=20, radius=30)]),
  'Extreme Speed': ('Turbo Tail', 'In rat form you move twice as fast and can fly.', [F('morph_speed', mult=2, fly=True)]),
 },
 'REINDEER': {
  'Winter Sprint': ('Sleigh Run', 'This pet earns double pet XP.', INCLUDED),
  'Infused': ('Winter Infusion', 'Gain &b+{a} Fishing Speed &7and &6+5 Treasure Chance &7on the winter island.',
              [W(S(FISHING_SPEED='a', TREASURE_CHANCE='5'), 'island:jerrys_workshop')]),
  'Snow Power': ('Gift Sense', 'Gifts are &a{a}% &7more likely during the gift attack.', [F('echo_item', items=['WHITE_GIFT', 'GREEN_GIFT', 'RED_GIFT'], chance='a', when=['event:season_of_jerry'])]),
  'Icy Wind': ('Icy Gust', '&a{a}% &7chance for double ice essence.', [F('echo_item', items=['ESSENCE_ICE'], chance='a')]),
 },
 'RIFT_FERRET': {
  'Orbs are Fun': ('Orb Chaser', 'Experience orbs give &a10% &7more experience.', [F('xp_orbs', v=10)]),
 },
 'ROCK': {
  'Ridable': ('Boulder Seat', 'Right-click your summoned pet (or &e/pet ride&7) to sit on it. Sneak to get up.', [F('ride', mode='sit')]),
  'Sailing Stone': ('Rolling Stone', 'Your rock stays where it lands. Sneak to call it to you. &8(15s cooldown)', [F('sail', cd=15)]),
  'Fortify': ('Fortify', 'While sitting on your rock, gain &a+{a}% Defense&7.', [W(F('pct', stats=['DEFENSE'], v='a'), 'riding')]),
  'Steady Ground': ('Steady Ground', 'While sitting on your rock, deal &c{a}x &7damage.', [F('dmg_mult', v='a', when=['riding'])]),
 },
 'ROSE_DRAGON': {
  'Garden Power': ('Bloom Power', 'Gain &6+{a} Farming Fortune &7per Farming level.', [F('per_skill', skills=['FARMING'], stats={'FARMING_FORTUNE': 'a'})]),
  'Rosy Scales': ('Rosy Scales', 'Gain &6+{a} Farming Fortune &7and &f+{b} Speed &7per crop milestone.', INFO),
  'Dragon\'s Gluttony': ('Feast', 'Gain &a+{a} &7Overbloom.', INFO),
  'Spiritual Perfection': ('Garden Grace', 'Gain &a{a}% &7more copper from visitors and mutation analysis.', INFO),
  'Symbiosis': ('Kinship', 'At level 200, gain &6+3 Farming Fortune &7for every other maxed farming pet you own.',
                [F('symbiosis', skill='FARMING', stats={'FARMING_FORTUNE': '3'})]),
 },
 'SCATHA': {
  'Burrowing': ('Tunnel Luck', 'Treasure chests turn up &a{a}% &7more often while mining.', [F('external', by='mining')]),
  'Drill Infusion': ('Drill Infusion', 'Drills you hold gain &6+{a} Gemstone Fortune&7.', [F('category_buff', categories=['DRILL'], stats={'GEMSTONE_FORTUNE': 'a'})]),
  'Bejeweled Eyes': ('Gem Gaze', 'Gain &a{a}% &7more Gemstone Powder.', [F('external', by='mining')]),
 },
 'SEAL': {
  'Showboater': ('Showboat', 'Beach balls are &a{a}% &7more likely during the Year of the Seal.', [F('echo_item', items=['BOUNCY_BEACH_BALL', 'GIANT_BOUNCY_BEACH_BALL', 'BEACH_BALL'], chance='a', when=['event:year_of_the_seal'])]),
  'Peak Performance': ('Peak Form', 'Catching treasure has a &a{a}% &7chance to give you some treasure bait.',
                       [F('treasure_drop', chance='a', items=['TREASURE_BAIT'])]),
  'Amphibious': ('Amphibious', 'Base stats are &a{a}% &7higher during the Year of the Seal.', [F('pet_base', v='a', when=['event:year_of_the_seal'])]),
 },
 'SHEEP': {
  'Mana Saver': ('Woolly Focus', 'Abilities cost &a{a}% &7less mana.', [F('mana_cost', v='a')]),
  'Overheal': ('Overheal', 'After &a10s &7without taking damage, gain a shield of &a{a}% &7of your max Health.', [F('overheal', v='a', idle=10)]),
  'Dungeon Wizard': ('Dungeon Scholar', 'Your total mana is &b{a}% &7higher in dungeons.', [W(F('pct', stats=['INTELLIGENCE'], v='a'), 'dungeon')]),
 },
 'SILVERFISH': {
  'Magnetic': ('Magnetic', 'Earn &a+{a}% &7more Mining XP.', [F('skill_xp', skill='MINING', v='a')]),
  'Experienced Burrower': ('Old Burrower', 'Gain &3+{a} Mining Wisdom&7.', [S(MINING_WISDOM='a')]),
  'Dexterity': ('Nimble', 'Gain &6+{a} Mining Speed &7and permanent Haste {b}.',
                [S(MINING_SPEED='a'), F('potion', effect='HASTE', amp='b-1')]),
 },
 'SKELETON': {
  'Bone Arrows': ('Bone Arrows', 'Arrows deal &c{a}% &7more damage, doubled in dungeons.', [F('dmg_kind', kind='PROJECTILE', v='a', dungeon=2)]),
  'Combo': ('Volley', 'Each bow hit stacks &c+3 Strength &7for &a8s&7. &8(Max {a} stacks)', [F('bow_stacks', stats={'STRENGTH': '3'}, max='a', dur=8)]),
  'Skeletal Defense': ('Bone Sentry', 'Your skeleton fires at mobs that get close for &c30x &7your &9Crit Damage&7. &8(5s cooldown)',
                       [F('skel_arrow', v=30, cd=5, radius=5)]),
 },
 'SKELETON_HORSE': {
  'High Stride': ('Long Stride', 'Permanent Jump Boost IV.', [F('potion', effect='JUMP_BOOST', amp=3)]),
  'Stampede': ('Bone Gallop', 'Each kill grants &e+{a} Attack Speed &7and &c+{b} Strength &7for &a5s&7. &8(Up to 20 stacks)',
               [F('kill_stacks', stats={'ATTACK_SPEED': 'a', 'STRENGTH': 'b'}, dur=5, max=20)]),
  'Trample': ('Trample', 'Falls of &a20+ &7blocks deal no damage to you and hit mobs within &a3 &7blocks for &c{a}% &7of your weapon damage per block fallen.',
              [F('trample', min=20, v='a', radius=3)]),
 },
 'SLOTH': {
  'Slow Start': ('Slow Start', 'Every &a60s&7, your next chop gains &2+{a} Sweep &7and &6+{b} Foraging Fortune&7.', [F('chop_ready', every=60, stats={'SWEEP': 'a', 'FORAGING_FORTUNE': 'b'})]),
  'Stronk Arm': ('Mighty Throw', 'Thrown axes gain &2+{a} Sweep &7and &6+{b} Foraging Fortune&7.', [F('ability_buff', match='throw_axe', dur=3, stats={'SWEEP': 'a', 'FORAGING_FORTUNE': 'b'})]),
  'Starlyn\'s Favorite': ('Grove Favourite', 'Chopping trees gives &a{a}% &7more points in forest contests.', INFO),
 },
 'SLUG': {
  'Slow and Steady': ('Patience', 'On the Crimson Isle, slugfish take &a{a}% &7less time to catch.', [F('external', by='fishing')]),
  'Pest Friends': ('Pest Pal', 'Gain &2+{a} Bonus Pest Chance&7.', [S(PEST_CHANCE='a')]),
  'Repugnant Aroma': ('Foul Aroma', 'Farming in a sprayed garden plot gives &6+{a} Farming Fortune&7.', INFO),
 },
 'SNAIL': {
  'Red Sand Enjoyer': ('Red Sand Lover', 'Red sand minions work &a{a}% &7faster on your island.', [F('external', by='minions')]),
  'Slow and Steady': ('Slow and Sure', 'Every &f{a} Speed &7above 100 becomes &6+1 Block Fortune&7.',
                      [F('per_stat', stat='BLOCK_FORTUNE', **{'from': 'SPEED'}, every='a', above=100, v='1')]),
  'Slimy Reach': ('Slime Trail', 'Gain &e+{a} Mining Spread &7on mining islands.', [W(S(MINING_SPREAD='a'), MINING)]),
 },
 'SNOWMAN': {
  'Blizzard': ('Whiteout', 'Enemies within &a{a} &7blocks are slowed by &a25% &7and deal &a{b}% &7less damage.',
               [F('blizzard', radius='a', v='b')]),
  'Frostbite': ('Frostbite', 'Your chilling aura means you take &a{a}% &7less damage.', [F('dmg_taken', match='*', v='a')]),
  'Snow Cannon': ('Snow Cannon', 'When you attack, a snowball hits for &c{a}% &7of your last melee hit. &8(Max 200,000, 1s cooldown)',
                  [F('snowball', v='a', cap=200000, cd=1)]),
  'Ouch!': ('Packed Ice', 'Snowballs have a &a50% &7chance to deal double damage.', [F('snowball_crit', chance=50)]),
 },
 'SPIDER': {
  'One With The Spider': ('Arachnid Bond', 'Each worn or held spider-themed item grants &c+{a} Strength&7.',
                          [F('armor_flat', match=['SPIDER', 'TARANTULA', 'ARACHN'], stats={'STRENGTH': 'a'})]),
  'Web-weaver': ('Web Weaver', 'Monsters you hit are slowed by &a{a}%&7.', [F('slow_hit', v='a')]),
  'Spider Whisperer': ('Web Foreman', 'Spider minions work &a{a}% &7faster on your island.', [F('external', by='minions')]),
  'Web Battlefield': ('Web Field', 'Kills leave a web: players within &a20 &7blocks gain &c+0.06 Strength &7and &b+0.01 Magic Find &7for &a40s&7. &8(Up to 10)',
                      [F('kill_stacks', stats={'STRENGTH': '0.06', 'MAGIC_FIND': '0.01'}, dur=40, max=10)]),
 },
 'SPINOSAURUS': {
  'Sharp Attitude': ('Sharp Teeth', 'Sea creatures arrive missing &a{a}% &7of their health.', [F('sc_wound', v='a')]),
  'Pursuit': ('Trophy Chase', 'Gold and Diamond trophy fish are &a{a}% &7more likely.', [F('echo_item', items=TROPHY_TOP, chance='a')]),
  'Primordial Fisher': ('Storm Fisher', 'Base stats are &a{a}% &7higher in the rain.', [F('pet_base', v='a', when=['rain'])]),
 },
 'SPIRIT': {
  'Spirit Assistance': ('Ghost Ally', 'Appears and helps you while you are a ghost in dungeons.', INFO),
  'Spirit Cooldowns': ('Restless', 'Ghost ability cooldowns in dungeons are &a{a}% &7shorter.', INFO),
  'Half Life': ('Half Life', 'If you are the first to die in a dungeon, that death costs only &a1 &7score.', [F('external', by='dungeons')]),
 },
 'SQUID': {
  'More Ink': ('Ink Spill', 'Squids have a &a{a}% &7chance to drop their loot twice.', [F('kill_double', match='squid', chance='a')]),
  'Ink Specialty': ('Ink Master', 'The <item:INK_WAND> &7gains &c+{a} Damage &7and &c+{b} Strength&7.',
                    [F('held_buff', items=['INK_WAND'], damage='a', stats={'STRENGTH': 'b'})]),
  'Fishing Wisdom Boost': ('Sea Wisdom', 'Gain &3+{a} Fishing Wisdom&7.', [S(FISHING_WISDOM='a')]),
 },
 'SUBZERO_WISP': {
  'Icehammer': ('Frost Strike', 'Break fire pillars twice as fast, healing &c50% &7of your max Health over &a3s&7.', [F('pillar', mult=2, heal=50)]),
  'Bulwark': ('Ember Ward', 'Killing blazes hardens you against blazes and demons: every &a100 &7kills adds &a+30 Defense &7and &f+3 True Defense &7against them. &8(Up to 10 times)',
              [F('bulwark', match='blaze', every=100, defense=30, true_defense=3, max=10)]),
  'Blaze Slayer': ('Blaze Hunter', 'Earn &a{a}x &7Combat XP from blazes.', [F('combat_xp', match='blaze', v='a')]),
  'Extinguish': ('Cool Spring', 'In combat on the Crimson Isle a healing pool appears every &a8s&7. Stepping in heals &c25% &7of your max Health, then &c10%&7/s for &a8s&7.',
                 [W(F('pool', every=8, heal=25, regen=10, dur=8), 'island:crimson_isle')]),
  'Ephemeral Stability': ('Still Mind', 'Regenerate mana &b40% &7faster.', [S(MANA_REGEN='40')]),
 },
 'TARANTULA': {
  'Webbed Cells': ('Venom Tolerance', 'The giant spider boss\'s vitality drain is &a{a}% &7weaker against you.', INFO),
  'Eight Legs': ('Eight Legs', 'Spider-themed boots cost &a{a}% &7less mana.', [F('mana_cost', v='a', match='boots')]),
  'Arachnid Slayer': ('Arachnid Hunter', 'Earn &a{a}x &7Combat XP from spiders.', [F('combat_xp', match='spider', v='a')]),
  'Web Battlefield': ('Web Field', 'Kills leave a web: players within &a20 &7blocks gain &c+0.06 Strength &7and &b+0.01 Magic Find &7for &a40s&7. &8(Up to 10)',
                      [F('kill_stacks', stats={'STRENGTH': '0.06', 'MAGIC_FIND': '0.01'}, dur=40, max=10)]),
 },
 'TIGER': {
  'Merciless Swipe': ('Merciless', 'Gain &c+{a}% Ferocity&7.', [F('pct', stats=['FEROCITY'], v='a')]),
  'Hemorrhage': ('Bleed', 'Melee hits cut the target\'s healing by &a{a}% &7for &a10s&7.', [F('hemorrhage', v='a', dur=10)]),
  'Apex Predator': ('Lone Hunter', 'Deal &c+{a}% &7damage to targets with no other mobs within &a15 &7blocks.', [F('dmg_isolated', radius=15, v='a')]),
 },
 'TURTLE': {
  'Turtle Tactics': ('Shell Tactics', 'Gain &a+{a}% Defense&7, plus &a10% &7more while standing still.',
                     [F('pct', stats=['DEFENSE'], v='a'), W(F('pct', stats=['DEFENSE'], v='10'), 'still')]),
  'Genius Amniote': ('Shell Circle', 'Up to &a4 &7players within &a50 &7blocks gain &a+{a}% Defense&7.', [F('aura', radius=50, max=4, pct={'DEFENSE': 'a'})]),
  'Unflippable': ('Unflippable', 'You are immune to knockback.', [F('kb_immune')]),
  'Turtle Shell': ('Hard Shell', 'Below &c40% &7Health take &a{a}% &7less damage. After &a10 &7hits taken gain &4+{b} Vitality&7.',
                   [F('dmg_taken', hp_below=40, v='a'), F('vitality_hits', hits=10, v='b')]),
 },
 'TYRANNOSAURUS': {
  'Close Combat': ('Point Blank', 'Deal &c{a}% &7more damage to enemies within &a1.5 &7blocks.', [F('dmg_near', dist=1.5, v='a')]),
  'Ferocious Roar': ('Roar', 'Attacks have a &a{a}% &7chance to stun. &8(10s cooldown)', [F('stun', chance='a', cd=10)]),
  'Tyrant': ('Tyrant', 'Combat stats from this pet\'s held item are &a{a}% &7higher.', [F('pet_item_boost', v='a')]),
 },
 'WITCH': {
  'Toil and Trouble': ('Bubble and Brew', 'Ingredients drop &a{a}% &7more often during the Year of the Witch.', [F('kill_double', match='*', chance='a', when=['event:year_of_the_witch'])]),
  'Alchemism': ('Quick Brew', 'Potions brew &a{a}% &7faster.', INFO),
  'Witching Hour': ('Witching Hour', 'Base stats are &a{a}% &7higher during the Year of the Witch.', [F('pet_base', v='a', when=['event:year_of_the_witch'])]),
 },
 'WITHER_SKELETON': {
  'Stronger Bones': ('Hard Bones', 'Take &a{a}% &7less damage from skeletal mobs.', [F('dmg_taken', match='skeleton', v='a')]),
  'Wither Blood': ('Wither Blood', 'Deal &c{a}% &7more damage to wither mobs.', [F('dmg', match='wither', v='a')]),
  'Death\'s Touch': ('Withering Touch', 'Hits apply a wither for &c{a}% &7of the hit over &a3 &7seconds. &8(Does not stack)', [F('wither', v='a', dur=3)]),
 },
 'WOLF': {
  'Alpha Dog': ('Alpha', 'Take &a{a}% &7less damage from wolves.', [F('dmg_taken', match='wolf', v='a')]),
  'Pack Leader': ('Pack Leader', 'Gain &9+{a} Crit Damage &7for every wolf nearby. &8(Max 10)',
                  [F('per_mobs', match='wolf', radius=15, max=10, stats={'CRIT_DAMAGE': 'a'})]),
  'Combat Wisdom Boost': ('Battle Wisdom', 'Gain &3+{a} Combat Wisdom&7.', [S(COMBAT_WISDOM='a')]),
 },
 'ZOMBIE': {
  'Bite Shield': ('Bite Guard', 'Take &a{a}% &7less damage from zombies.', [F('dmg_taken', match='zombie', v='a')]),
  'Rotten Blade': ('Rotting Edge', 'Deal &c{a}% &7more damage to undead mobs.', [F('dmg', match='undead', v='a')]),
  'Living Dead': ('Living Dead', 'Undead armour stats are raised by &a{a}%&7.', [F('armor_buff', match=['ZOMBIE_', 'REVENANT_', 'REAPER_', 'SKELETON_', 'UNDEAD'], v='a')]),
 },
}
