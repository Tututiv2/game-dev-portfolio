"""Generate plugin/src/main/resources/data/pets/*.json from research/data.

  py -3 tools/gen/pets/gen_pets.py

Sources (never typed by hand):
  neu-repo/constants/pets.json      XP curve, rarity offsets, custom levelling (dragons, Bingo, Reindeer), pet types
  neu-repo/constants/petnums.json   per pet, per rarity: lvl1 / lvl100 stats and ability numbers
  neu-repo/items/<PET>;<n>.json     ability blocks (to know which ability each rarity has and which number it uses),
                                    skull textures, Kat upgrade recipes (katgrade), crafting/forge recipes
  neu-repo/items/<ID>.json          pet item / candy / Kat material textures and recipes
  research/data/items.json          item names, rarities, fallback skins
Hand-written (ours): abilities.py (names, wording, effect specs), pet_items.py (held-item effects).
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
DATA = os.path.join(ROOT, 'research', 'data')
NEU = os.path.join(DATA, 'neu-repo')
OUT = os.path.join(ROOT, 'plugin', 'src', 'main', 'resources', 'data', 'pets')
sys.path.insert(0, HERE)
from abilities import A  # noqa: E402
import pet_items  # noqa: E402

R = ['COMMON', 'UNCOMMON', 'RARE', 'EPIC', 'LEGENDARY', 'MYTHIC']

# Stat keys in petnums -> our api.stat.Stat names (None = shown only, no such stat here)
STAT_MAP = {
    'BONUS_ATTACK_SPEED': 'ATTACK_SPEED', 'TROPHY_CHANCE': 'TROPHY_FISH_CHANCE', 'BONUS_PEST_CHANCE': 'PEST_CHANCE',
    'DAMAGE': None, 'OVERBLOOM': None, 'POTATO_FORTUNE': None, 'RESPIRATION': None,
}
KNOWN_STATS = set()

# Pet display names that are proper nouns of the reference game -> ours.
# Bits per skin by rarity (TUNE: the reference sells skins for gems, i.e. real money; here they are earned).
# Invented proper nouns inside reference skin names (everything else in them is a plain descriptive word).
SKIN_WORDS = {'Armaron': 'Bastion', 'Chromari': 'Prismara', 'Reindrake': 'Frostdrake', 'Mr Claws': 'Sir Pinch',
              'Squeakheart': 'Squeakling'}
SKIN_BITS = {'COMMON': 600, 'UNCOMMON': 900, 'RARE': 1500, 'EPIC': 2500, 'LEGENDARY': 4000, 'MYTHIC': 6000}

PET_NAMES = {
    'SCATHA': ('Scatha', 'Tunnel Wyrm'),
    'BAL': ('Bal', 'Magmaw'),
    'KUUDRA': ('Kuudra', 'Magma Leviathan'),
    'JERRY': ('Jerry', 'Barry'),
    'PRECURSOR_DRONE': ('Precursor Drone', 'Relic Drone'),
    'TYRANNOSAURUS': ('T-Rex', 'T-Rex'),
}

# George's list (Taming 50 -> 60): one each, research 02 line 724.
COLLECTOR = [['RIFT_FERRET', 'EPIC'], ['SLUG', 'EPIC'], ['SPIRIT', 'EPIC'], ['GIRAFFE', 'EPIC'], ['JELLYFISH', 'EPIC'],
             ['BAL', 'EPIC'], ['BABY_YETI', 'EPIC'], ['BLACK_CAT', 'LEGENDARY'], ['FROST_WISP', 'RARE'], ['ENDERMAN', 'MYTHIC']]

# Pets that work from the menu without being summoned (lore says so).
PASSIVE = {'BINGO', 'GRANDMA_WOLF', 'KUUDRA', 'SPIRIT', 'OWL'}

# Kat-only upgrade materials the pets module OWNS (other kat inputs belong to materials/drops modules).
KAT_OWNED = ['SMALL_HONEY_DIPPER', 'MEDIUM_HONEY_DIPPER', 'LARGE_HONEY_DIPPER', 'GIANT_HONEY_DIPPER', 'ROYAL_JELLY',
             'SMALL_PIG_EAR', 'MEDIUM_PIG_EAR', 'LARGE_PIG_EAR', 'GIANT_PIG_EAR',
             'SMALL_FROG_TREAT', 'MEDIUM_FROG_TREAT', 'LARGE_FROG_TREAT', 'GIANT_FROG_TREAT',
             'SMALL_SEAL_PLUSHIE', 'MEDIUM_SEAL_PLUSHIE', 'LARGE_SEAL_PLUSHIE', 'GIANT_SEAL_PLUSHIE',
             'SMALL_WITCH_CAULDRON', 'MEDIUM_WITCH_CAULDRON', 'LARGE_WITCH_CAULDRON', 'GIANT_WITCH_CAULDRON',
             'SMALL_SLOTH_CLAW', 'MEDIUM_SLOTH_CLAW', 'LARGE_SLOTH_CLAW', 'GIANT_SLOTH_CLAW',
             'GRIFFIN_UPGRADE_STONE_UNCOMMON', 'GRIFFIN_UPGRADE_STONE_RARE', 'GRIFFIN_UPGRADE_STONE_EPIC',
             'GRIFFIN_UPGRADE_STONE_LEGENDARY', 'GRIFFIN_UPGRADE_STONE_MYTHIC',
             'TRUNK_POLISH', 'ENDERMAN_CORTEX_REWRITER', 'FAKE_NEUROSCIENCE_DEGREE', 'BLACK_WOOLEN_YARN',
             'BUCKET_OF_FROGSPAWN', 'BUSTED_BELT_BUCKLE', 'FROZEN_SCUTE', 'GUARDIAN_LUCKY_BLOCK', 'MAGIC_TOP_HAT',
             'MIXED_MITE_GEL', 'PET_ITEM_CHOCOLATE_SYRINGE', 'PET_ITEM_PURE_MITHRIL_GEM', 'PET_ITEM_TOY_JERRY',
             'PET_ITEM_VAMPIRE_FANG', 'POCKET_SIZED_IGLOO', 'RADIOACTIVE_VIAL', 'RAT_JETPACK', 'SCUTTLER_SHELL']
ITEM_RENAMES = {'PET_ITEM_TOY_JERRY': ('Jerry 3D Glasses', 'Barry 3D Glasses')}

# Kat supply-shop price per rarity for Kat-only materials (TUNE: the reference sources them from events/shops
# this server does not have; without a price nobody could ever upgrade a Bee, Pig, Frog... past Common).
SUPPLY_PRICE = {'UNCOMMON': 10000, 'RARE': 50000, 'EPIC': 250000, 'LEGENDARY': 1000000, 'MYTHIC': 5000000}

LEGACY_MAT = {'SKULL_ITEM': 'PLAYER_HEAD', 'CARROT_ITEM': 'CARROT', 'RED_ROSE': 'POPPY', 'DOUBLE_PLANT': 'ROSE_BUSH',
              'INK_SACK': 'INK_SAC', 'WATCH': 'CLOCK', 'GOLD_BARDING': 'GOLDEN_HORSE_ARMOR', 'WOOD_SWORD': 'WOODEN_SWORD'}

MODEL = re.compile(r'ItemModel:"minecraft:([a-z0-9_]+)"')
TEXTURE = re.compile(r'Value:\\?"([A-Za-z0-9+/=]+)\\?"')


def load(p):
    with open(p, encoding='utf8') as f:
        return json.load(f)


def neu_item(iid):
    p = os.path.join(NEU, 'items', iid + '.json')
    return load(p) if os.path.exists(p) else None


def strip(s):
    return re.sub('§.', '', s)


def colon(i):
    # NEU writes damage values with '-': "LOG-3" is LOG:3, and "LOG-3:32" is 32 of LOG:3.
    m = re.match(r'^(.*?)-(\d+)(:\d+)?$', i)
    return m.group(1) + ':' + m.group(2) + (m.group(3) or '') if m else i


def skull_of(j):
    if not j:
        return None
    m = TEXTURE.search(j.get('nbttag', ''))
    return m.group(1) if m else None


def material_of(j, fallback_item=None):
    if j:
        m = MODEL.search(j.get('nbttag', ''))
        if m:
            return m.group(1).upper()
        if 'SkullOwner' in j.get('nbttag', ''):
            return 'PLAYER_HEAD'
    if fallback_item:
        mat = fallback_item.get('material', 'STONE')
        return LEGACY_MAT.get(mat, mat)
    return 'PAPER'


def blocks(lore):
    """Ability blocks of a pet tooltip: a gold title line, then body lines until a blank line."""
    out, cur = [], None
    for l in lore[1:]:
        p = strip(l)
        if cur is None and l.startswith('§6') and not l.startswith('§6§l') and p.strip() and not p.startswith(' '):
            cur = [p.strip()]
            continue
        if cur is not None:
            if not p.strip():
                out.append(cur)
                cur = None
            else:
                cur.append(p)
    if cur:
        out.append(cur)
    return out


def tokens(block):
    """Placeholder indexes in order of first appearance -> token letters a, b, c..."""
    seen = []
    for line in block[1:]:
        for m in re.finditer(r'\{(\d+)\}', line):
            i = int(m.group(1))
            if i not in seen:
                seen.append(i)
    return {chr(ord('a') + k): v for k, v in enumerate(seen)}


def wrap(text, width=38):
    out, line, vis, carry = [], '', 0, '&7'
    for word in text.split(' '):
        wv = len(re.sub(r'&[0-9a-fk-or]', '', word))
        if vis and vis + 1 + wv > width:
            out.append(line)
            line, vis = carry, 0
        if vis:
            line += ' '
            vis += 1
        elif not line:
            line = '&7'
        line += word
        vis += wv
        for m in re.finditer(r'&[0-9a-fk-or]', word):
            carry = '&7' if m.group(0) == '&r' else m.group(0)
    if line:
        out.append(line)
    return out


def ability_keyname(pet, name):
    return re.sub(r'[^a-z0-9]+', '_', (pet + '_' + name).lower()).strip('_')


def main():
    os.makedirs(OUT, exist_ok=True)
    consts = load(os.path.join(NEU, 'constants', 'pets.json'))
    nums = load(os.path.join(NEU, 'constants', 'petnums.json'))
    raw = load(os.path.join(DATA, 'items.json'))
    ref_items = {i['id']: i for i in (raw['items'] if isinstance(raw, dict) else raw)}
    stat_names = set(re.findall(r'^\s+([A-Z_]+)\("', open(os.path.join(ROOT, 'plugin', 'src', 'main', 'java', 'com', 'skyclone', 'api', 'stat', 'Stat.java'), encoding='utf8').read(), re.M))

    problems, info_only, renames = [], [], []
    ref_by_name = {}
    pets, items, kat, recipes = [], [], [], []

    for pet in sorted(nums):
        tiers = [r for r in R if r in nums[pet]]
        skill = consts['pet_types'].get(pet, 'COMBAT')
        if skill == '?':
            skill = 'COMBAT'
        ref_name = None
        skull = None
        tdefs = {}
        for r in tiers:
            j = neu_item('%s;%d' % (pet, R.index(r)))
            n = nums[pet][r]
            if j:
                ref_name = ref_name or strip(j['displayname']).replace('[Lvl {LVL}] ', '')
                skull = skull_of(j) or skull
            s1, s100, extra1, extra100 = {}, {}, {}, {}
            for lvl, dst, ex in (('1', s1, extra1), ('100', s100, extra100)):
                for k, v in n[lvl]['statNums'].items():
                    key = STAT_MAP.get(k, k)
                    if key is None or key not in stat_names:
                        ex[k] = v
                    else:
                        dst[key] = v
            abil = []
            seen_titles = {}
            for b in (blocks(j['lore']) if j else []):
                title = b[0]
                if title.startswith('Edition') or 'Edition #' in title:
                    continue
                seen_titles[title] = seen_titles.get(title, 0) + 1
                table = A.get(pet, {})
                # most specific first: "Title#2" (repeated titles), "Title@RARE" (rarity-specific wording)
                spec = table.get('%s#%d' % (title, seen_titles[title])) or table.get('%s@%s' % (title, r)) or table.get(title)
                if seen_titles[title] > 1 or ('%s#1' % title) in table:
                    title = '%s#%d' % (title, seen_titles[title])
                if spec is None:
                    problems.append('no ability text for %s / %s (%s)' % (pet, title, r))
                    continue
                our_name, text, fx = spec
                tok = tokens(b)
                if pet == 'GRANDMA_WOLF':
                    tok = {chr(ord('a') + i): i for i in range(len(n['100']['otherNums']))}
                used = set(re.findall(r'\{([a-z])\}', text))
                for e in fx:
                    for v in e.values():
                        if isinstance(v, str) and re.fullmatch(r'[a-z](-1)?', v):
                            used.add(v[0])
                        if isinstance(v, dict):
                            for vv in v.values():
                                if isinstance(vv, str) and re.fullmatch(r'[a-z]', vv):
                                    used.add(vv)
                missing = [t for t in used if t not in tok]
                if missing:
                    problems.append('%s / %s (%s): tokens %s not in reference block %s' % (pet, title, r, missing, b))
                if fx and fx[0].get('t') == 'info' and r == tiers[-1]:
                    info_only.append('%s: %s (%s)' % (pet, our_name, title))
                abil.append({'key': ability_keyname(pet, title), 'name': our_name, 'lore': wrap(text),
                             'tok': tok, 'fx': fx})
            tdefs[r] = {'s1': s1, 's100': s100, 'x1': extra1, 'x100': extra100,
                        'n1': n['1']['otherNums'], 'n100': n['100']['otherNums'], 'abilities': abil}
        cpl = consts['custom_pet_leveling'].get(pet, {})
        curve = nums[pet].get(tiers[-1], {}).get('stats_levelling_curve') or next(
            (nums[pet][r].get('stats_levelling_curve') for r in tiers if nums[pet][r].get('stats_levelling_curve')), None)
        name = ref_name or pet.replace('_', ' ').title()
        ref_by_name[name] = pet
        if pet in PET_NAMES:
            renames.append((pet, PET_NAMES[pet][0], PET_NAMES[pet][1]))
            name = PET_NAMES[pet][1]
        pets.append({
            'id': pet, 'name': name, 'skill': skill, 'rarities': tiers,
            'maxLevel': cpl.get('max_level', 100), 'extraLevels': cpl.get('pet_levels', []),
            'offsets': cpl.get('rarity_offset'), 'xpMult': cpl.get('xp_multiplier', 1),
            'curve': curve, 'skull': skull, 'passive': pet in PASSIVE, 'tiers': tdefs,
        })
        # one ItemDef per (pet, rarity)
        for r in tiers:
            iid = '%s;%d' % (pet, R.index(r))
            items.append({'id': iid, 'name': name, 'material': 'PLAYER_HEAD', 'skull': skull, 'rarity': r,
                          'category': 'PET', 'lore': ['&eRight-click to add this pet to your', '&epets menu!'],
                          'tags': ['no_reforge', 'pet'], 'extra': {'petType': pet, 'petTier': r}})
            j = neu_item(iid)
            for rec in (j or {}).get('recipes', []):
                t = rec.get('type')
                if t == 'katgrade':
                    kat.append({'pet': pet, 'from': R[int(rec['input'].split(';')[1])], 'to': R[int(rec['output'].split(';')[1])],
                                'coins': int(rec.get('coins', 0)), 'seconds': int(rec.get('time', 0)),
                                'items': [colon(x) for x in rec.get('items', [])]})
                elif t == 'crafting':
                    grid = [colon(rec.get(k, '')) if rec.get(k) else '' for k in ('A1', 'A2', 'A3', 'B1', 'B2', 'B3', 'C1', 'C2', 'C3')]
                    recipes.append({'id': 'pets:' + iid, 'type': 'CRAFTING', 'result': iid, 'amount': 1, 'grid': grid})
                elif t == 'forge':
                    ins = [colon(x.split(':')[0]) + ':' + str(int(float(x.split(':')[1]))) for x in rec['inputs']]
                    ins = ['COINS:' + x.split(':')[1] if x.startswith('SKYBLOCK_COIN:') else x for x in ins]
                    recipes.append({'id': 'pets:' + iid + ':forge', 'type': 'FORGE', 'result': iid, 'amount': 1,
                                    'inputs': ins, 'durationSeconds': int(rec.get('duration', 0)), 'station': 'Forge'})
                elif t == 'trade':
                    c = rec['cost']
                    recipes.append({'id': 'pets:' + iid + ':trade', 'type': 'NPC_SHOP', 'result': iid, 'amount': 1,
                                    'inputs': [colon(c)], 'station': 'Pet Sitter'})

    # ---- held items, candies, kat materials ------------------------------------------------
    held = []
    for iid, (name, rar, text, fx, tune) in sorted(pet_items.ITEMS.items()):
        ref = ref_items.get(iid)
        j = neu_item(iid)
        if ref is None:
            problems.append('pet item %s not in items.json' % iid)
        base_neu = j or neu_item(re.sub(r'_(COMMON|UNCOMMON|RARE|EPIC|LEGENDARY)$', '_COMMON', iid)) or neu_item(re.sub(r'_(COMMON|UNCOMMON|RARE|EPIC|LEGENDARY)$', '_UNCOMMON', iid))
        sk = skull_of(base_neu) or ((ref or {}).get('skin') or {}).get('value')
        mat = 'PLAYER_HEAD' if sk else material_of(base_neu, ref)
        if ref and ref.get('name') != name:
            renames.append((iid, ref.get('name'), name))
        lore = wrap(text) + ['', '&7A pet holds one item; swap it any time.', '&eRight-click with a summoned pet to give', '&eit this item!']
        items.append({'id': iid, 'name': name, 'material': mat, 'skull': sk, 'rarity': rar, 'category': 'PET_ITEM',
                      'lore': lore, 'tags': ['pet_item']})
        held.append({'id': iid, 'fx': fx, 'tune': tune})
        if j and j.get('recipe'):
            rr = j['recipe']
            grid = [colon(rr.get(k, '')) if rr.get(k) else '' for k in ('A1', 'A2', 'A3', 'B1', 'B2', 'B3', 'C1', 'C2', 'C3')]
            recipes.append({'id': 'pets:' + iid, 'type': 'CRAFTING', 'result': iid, 'amount': 1, 'grid': grid})
        for rec in (j or {}).get('recipes', []):
            if rec.get('type') == 'crafting':
                grid = [colon(rec.get(k, '')) if rec.get(k) else '' for k in ('A1', 'A2', 'A3', 'B1', 'B2', 'B3', 'C1', 'C2', 'C3')]
                recipes.append({'id': 'pets:' + iid, 'type': 'CRAFTING', 'result': iid, 'amount': int(rec.get('count', 1)), 'grid': grid})

    for iid, (fx, owner) in sorted(pet_items.HELD_ELSEWHERE.items()):
        if iid in pet_items.ITEMS:
            problems.append('pet item %s is in both ITEMS and HELD_ELSEWHERE' % iid)
            continue
        held.append({'id': iid, 'fx': fx, 'tune': None, 'owner': owner})
    held.sort(key=lambda h: h['id'])

    candies = []
    for iid, (name, rar, xp) in pet_items.CANDY.items():
        ref = ref_items.get(iid)
        j = neu_item(iid)
        mat = material_of(j, ref)
        cap = iid != 'PET_CAKE'
        lore = wrap('Feeds your summoned pet &a+%s &7pet XP.' % format(xp, ',')) + \
            (['&8A pet can only eat 10 candies in its life.'] if cap else []) + ['', '&eRight-click to feed your summoned pet!']
        items.append({'id': iid, 'name': name, 'material': mat, 'rarity': rar, 'category': 'CONSUMABLE', 'lore': lore,
                      'tags': ['pet_candy']})
        candies.append({'id': iid, 'xp': xp, 'counts': cap})
        if j and j.get('recipe'):
            rr = j['recipe']
            grid = [colon(rr.get(k, '')) if rr.get(k) else '' for k in ('A1', 'A2', 'A3', 'B1', 'B2', 'B3', 'C1', 'C2', 'C3')]
            recipes.append({'id': 'pets:' + iid, 'type': 'CRAFTING', 'result': iid, 'amount': 1, 'grid': grid})

    supplies = []
    for iid in KAT_OWNED + list(pet_items.KAT_ONLY):
        if any(i['id'] == iid for i in items):
            continue
        ref = ref_items.get(iid)
        if not ref:
            problems.append('kat material %s not in items.json' % iid)
            continue
        j = neu_item(iid)
        sk = skull_of(j) or (ref.get('skin') or {}).get('value')
        mat = 'PLAYER_HEAD' if sk else material_of(j, ref)
        name = ref['name']
        if iid in ITEM_RENAMES:
            renames.append((iid, ITEM_RENAMES[iid][0], ITEM_RENAMES[iid][1]))
            name = ITEM_RENAMES[iid][1]
        rar = ref.get('tier') or 'COMMON'
        target = [k for k in kat if iid in [x.split(':')[0] for x in k['items']]]
        desc = ('Used by the Pet Sitter to raise a %s from %s to %s.' % (
            next(p['name'] for p in pets if p['id'] == target[0]['pet']), target[0]['from'].title(), target[0]['to'].title())) if target else 'Used by the Pet Sitter for pet upgrades.'
        items.append({'id': iid, 'name': name, 'material': mat, 'skull': sk, 'rarity': rar, 'category': 'MATERIAL',
                      'lore': wrap(desc), 'tags': ['kat_material']})
        supplies.append({'id': iid, 'coins': SUPPLY_PRICE.get(rar, 10000)})
        for rec in (j or {}).get('recipes', []):
            if rec.get('type') == 'crafting':
                grid = [colon(rec.get(k, '')) if rec.get(k) else '' for k in ('A1', 'A2', 'A3', 'B1', 'B2', 'B3', 'C1', 'C2', 'C3')]
                recipes.append({'id': 'pets:' + iid, 'type': 'CRAFTING', 'result': iid, 'amount': int(rec.get('count', 1)), 'grid': grid})

    # Flower/bouquet + anvils (renamed)
    for iid, (refname, ours) in pet_items.RENAMES.items():
        ref = ref_items.get(iid) or {}
        j = neu_item(iid)
        sk = skull_of(j) or (ref.get('skin') or {}).get('value')
        mat = 'PLAYER_HEAD' if sk else material_of(j, ref)
        renames.append((iid, refname, ours))
        if iid == 'KAT_FLOWER':
            lore = wrap('Give this to the Pet Sitter to skip &a1 day &7of a pet upgrade.')
        elif iid == 'KAT_BOUQUET':
            lore = wrap('Give this to the Pet Sitter to skip &a5 days &7of a pet upgrade.')
        else:
            lore = wrap('Used in crafting to upgrade pet items.')
        items.append({'id': iid, 'name': ours, 'material': mat, 'skull': sk, 'rarity': ref.get('tier') or 'RARE',
                      'category': 'MATERIAL', 'lore': lore, 'tags': ['kat_material'] if iid.startswith('KAT') else []})
        if j and j.get('recipe'):
            rr = j['recipe']
            grid = [colon(rr.get(k, '')) if rr.get(k) else '' for k in ('A1', 'A2', 'A3', 'B1', 'B2', 'B3', 'C1', 'C2', 'C3')]
            recipes.append({'id': 'pets:' + iid, 'type': 'CRAFTING', 'result': iid, 'amount': 1, 'grid': grid})
        for rec in (j or {}).get('recipes', []):
            if rec.get('type') == 'crafting':
                grid = [colon(rec.get(k, '')) if rec.get(k) else '' for k in ('A1', 'A2', 'A3', 'B1', 'B2', 'B3', 'C1', 'C2', 'C3')]
                recipes.append({'id': 'pets:' + iid, 'type': 'CRAFTING', 'result': iid, 'amount': 1, 'grid': grid})

    # Zog's pet-item shop (neu-repo ZOG_NPC npc_shop), sold by our Pet Sitter.
    zog = load(os.path.join(NEU, 'items', 'ZOG_NPC.json'))
    shop = []
    for rec in zog.get('recipes', []):
        if rec.get('type') == 'npc_shop':
            coins = sum(int(float(c.split(':')[1])) for c in rec['cost'] if c.startswith('SKYBLOCK_COIN'))
            others = [colon(c) for c in rec['cost'] if not c.startswith('SKYBLOCK_COIN')]
            shop.append({'id': rec['result'].split(':')[0], 'coins': coins, 'items': others})

    # Forge copies carry the mining module's gate (HOTM tier), so no view of our copy offers a fossil pet to a
    # HOTM 1 player (mining Request, wave 3). Mining's forge.json is the source of truth for the gate.
    mining_forge = os.path.join(ROOT, 'plugin', 'src', 'main', 'resources', 'data', 'mining', 'forge.json')
    if os.path.exists(mining_forge):
        gates = {r['result']: r.get('unlock') for r in load(mining_forge) if r.get('type') == 'FORGE'}
        for rec in recipes:
            if rec['type'] == 'FORGE' and gates.get(rec['result']):
                rec['unlock'] = gates[rec['result']]

    # Same for crafting: the crafting module's copy of every pet / pet-item / candy recipe carries the
    # collection or slayer unlock (Chicken pet = Raw Chicken III...). Ours had none, so the same grid
    # crafted a pet with no collection at all. Copy the gate wherever the grids are identical (wave 3).
    sb_recipes = os.path.join(ROOT, 'plugin', 'src', 'main', 'resources', 'data', 'crafting', 'skyblock_recipes.json')
    if os.path.exists(sb_recipes):
        gated = {}
        for r in load(sb_recipes):
            if r.get('type') == 'CRAFTING' and r.get('unlock'):
                gated[(r['result'], tuple(r.get('grid') or []))] = r['unlock']
        for rec in recipes:
            if rec['type'] == 'CRAFTING':
                u = gated.get((rec['result'], tuple(rec.get('grid') or [])))
                if u:
                    rec['unlock'] = u

    # Fallback materials the recipes / kat table use: registered in postEnable ONLY if no module registered them.
    used = set()
    for rec in recipes:
        for c in (rec.get('grid') or []) + rec.get('inputs', []):
            if c and not c.startswith('COINS'):
                used.add(re.sub(r':\d+$', '', c))
    for k in kat:
        for x in k['items']:
            used.add(re.sub(r':\d+$', '', x))
    mine = {i['id'] for i in items}
    fallback = []
    for iid in sorted(used - mine):
        ref = ref_items.get(iid)
        if not ref:
            continue
        j = neu_item(iid.replace(':', '-')) or neu_item(iid)
        sk = skull_of(j) or (ref.get('skin') or {}).get('value')
        mat = 'PLAYER_HEAD' if sk else material_of(j, ref)
        fallback.append({'id': iid, 'name': ref['name'], 'material': mat, 'skull': sk, 'rarity': ref.get('tier') or 'COMMON',
                         'category': 'MATERIAL', 'lore': [], 'tags': ['pets_fallback']})

    # ---- pet skins (wave 5): cosmetics bought with Bits from the Pet Sitter, never with real money ----
    skins = []
    our_name = {p['id']: p['name'] for p in pets}
    for fn in sorted(os.listdir(os.path.join(NEU, 'items'))):
        if not fn.startswith('PET_SKIN_') or not fn.endswith('.json'):
            continue
        j = load(os.path.join(NEU, 'items', fn))
        sid = j.get('internalname') or fn[:-5]
        lore = [strip(x) for x in j.get('lore', [])]
        joined = ' '.join(lore)
        m = re.search(r'applied to (.+?) pets', joined)
        tex = skull_of(j)
        if not m or not tex:
            problems.append('skin %s: no pet / texture' % sid)
            continue
        names = [x.strip() for x in re.split(r',|\band\b', m.group(1)) if x.strip()]
        pets_of = [ref_by_name.get(x) for x in names]
        if not pets_of or None in pets_of:
            problems.append('skin %s: unknown pet %s' % (sid, m.group(1)))
            continue
        pet = pets_of[0]
        rar = 'EPIC'
        for r in reversed(R):
            if lore and lore[-1].startswith(r):
                rar = r
                break
        ref_disp = strip(j.get('displayname', sid))
        disp = ref_disp
        if pet in PET_NAMES:
            disp = disp.replace(PET_NAMES[pet][0], PET_NAMES[pet][1])
        for ref_word, ours in SKIN_WORDS.items():   # the few invented proper nouns inside skin names
            disp = disp.replace(ref_word, ours)
        if disp != ref_disp:
            renames.append((sid, ref_disp, disp))
        skins.append({'id': sid, 'name': disp, 'pets': pets_of, 'rarity': rar, 'skull': tex,
                      'bits': SKIN_BITS.get(rar, 3000)})
        items.append({'id': sid, 'name': disp, 'material': 'PLAYER_HEAD', 'skull': tex, 'rarity': rar,
                      'category': 'COSMETIC', 'tags': ['pet_skin', 'no_reforge'],
                      'lore': ['&8Consumed on use', ''] + wrap('Pet skins change the look of your pet. Only one skin can be active at a time.')
                              + [''] + wrap('This skin can only be applied to &a%s &7pets.' % ', '.join(our_name.get(x, x) for x in pets_of))
                              + ['', '&eRight-click with your pet summoned to', '&eapply this skin!']})

    xp = {'levels': consts['pet_levels'], 'offsets': consts['pet_rarity_offset']}
    meta = {
        'scoreTable': [[10, 1], [25, 2], [50, 3], [75, 4], [100, 5], [130, 6], [175, 7], [225, 8], [275, 9], [325, 10],
                       [375, 11], [450, 12], [500, 13]],
        'collector': COLLECTOR,
        'katTaming': {'EPIC': 10, 'LEGENDARY': 20, 'MYTHIC': 25},
        'candies': candies, 'shop': shop, 'supplies': supplies,
        'skipItems': {'KAT_FLOWER': 86400, 'KAT_BOUQUET': 432000},
    }

    def dump(name, obj):
        with open(os.path.join(OUT, name), 'w', encoding='utf8', newline='\n') as f:
            json.dump(obj, f, ensure_ascii=False, indent=1 if name != 'pets.json' else None, separators=(',', ':') if name == 'pets.json' else None)

    dump('pets.json', pets)
    dump('items.json', items)
    dump('fallback_items.json', fallback)
    dump('held_items.json', held)
    dump('kat.json', kat)
    dump('recipes.json', recipes)
    dump('xp.json', xp)
    dump('meta.json', meta)
    dump('skins.json', skins)
    # wave 5: which mining block a material is, per island (first match wins, as mining's BlockTable does), so the
    # 'dig:<tag>' condition (Mithril Golem 'when mining Mithril', Goblin 'when mining Ores') reads the block being dug
    dig = {}
    try:
        with open(os.path.join(OUT, '..', 'mining', 'blocks.json'), encoding='utf8') as f:
            mine_blocks = json.load(f).get('blocks', [])
    except (OSError, ValueError):
        mine_blocks = []
    for b in mine_blocks:
        kind = (b.get('kind') or '').lower()
        tags = sorted({b['id'], kind, b['id'].split('_')[0]} | ({'ore'} if kind in ('ore', 'pure_ore') else set()))
        for isl in b.get('islands', []):
            for m in b.get('materials', []):
                dig.setdefault(isl, {}).setdefault(m.upper(), tags)
    dump('dig.json', dig)
    with open(os.path.join(HERE, 'renames.json'), 'w', encoding='utf8') as f:
        json.dump(sorted(set(renames)), f, ensure_ascii=False, indent=1)
    with open(os.path.join(HERE, 'info_only.txt'), 'w', encoding='utf8') as f:
        f.write('\n'.join(info_only))
    print('pets %d, items %d (+%d fallback), kat %d, recipes %d, held %d, shop %d, supplies %d' % (
        len(pets), len(items), len(fallback), len(kat), len(recipes), len(held), len(shop), len(supplies)))
    print('info-only abilities: %d, skins %d' % (len(info_only), len(skins)))
    for p in problems:
        print('PROBLEM', p)


if __name__ == '__main__':
    main()
