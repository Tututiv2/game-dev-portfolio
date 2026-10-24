"""Generate the extras module's data files.

    py -3 tools/gen/extras/gen_extras.py

Reads research/data (neu-repo items + constants, items.json) and the other modules' registered item ids,
writes plugin/src/main/resources/data/extras/{items,songs,contacts,city_projects,firesales}.json and
docs/names/extras.md. Prints anything it had to drop (a contact requirement item nobody registered, a skin whose
helmet nobody registered) so the report is the review list.
"""
import glob
import json
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
NEU = os.path.join(ROOT, 'research', 'data', 'neu-repo')
DATA = os.path.join(ROOT, 'plugin', 'src', 'main', 'resources', 'data')
OUT = os.path.join(DATA, 'extras')
NAMES = os.path.join(ROOT, 'docs', 'names', 'extras.md')


def strip(s):
    return re.sub('§.', '', s or '')


def neu(item_id):
    p = os.path.join(NEU, 'items', item_id + '.json')
    if not os.path.exists(p):
        return None
    with open(p, encoding='utf-8') as f:
        return json.load(f)


def skull_of(d):
    m = re.search(r'Value:\\?"([A-Za-z0-9+/=]+)\\?"', d.get('nbttag', ''))
    return m.group(1) if m else None


def registered_ids():
    ids = set()
    for f in glob.glob(os.path.join(DATA, '**', '*.json'), recursive=True):
        if os.sep + 'extras' + os.sep in f:
            continue
        try:
            with open(f, encoding='utf-8') as fh:
                d = json.load(fh)
        except Exception:
            continue
        if isinstance(d, list):
            for o in d:
                if isinstance(o, dict) and 'id' in o and 'material' in o:
                    ids.add(o['id'])
    return ids


REG = registered_ids()
renames = []   # (id, reference name, our name)
report = []

# ---------------------------------------------------------------------------------------------------------------
# Phones (research 08 section 6.4; capacities and features from each neu item's lore)
# ---------------------------------------------------------------------------------------------------------------
PHONES = [
    # id, our model name, rarity
    ('ABIPHONE_BASIC', 'Ringphone Basic', 'COMMON'),
    ('ABIPHONE_X_PLUS', 'Ringphone X Red', 'UNCOMMON'),
    ('ABIPHONE_X_PLUS_SPECIAL_EDITION', 'Ringphone X Blue', 'UNCOMMON'),
    ('ABIPHONE_XI_ULTRA', 'Ringphone XI Yellow', 'UNCOMMON'),
    ('ABIPHONE_XI_ULTRA_STYLE', 'Ringphone XI Green', 'UNCOMMON'),
    ('ABIPHONE_XII_MEGA', 'Ringphone XII Amber', 'RARE'),
    ('ABIPHONE_XII_MEGA_COLOR', 'Ringphone XII Amethyst', 'RARE'),
    ('ABIPHONE_XIII_PRO', 'Ringphone XIII Jade', 'RARE'),
    ('ABIPHONE_XIII_PRO_GIGA', 'Ringphone XIII Sapphire', 'RARE'),
    ('ABIPHONE_XIV_ENORMOUS', 'Ringphone XIV Orange', 'EPIC'),
    ('ABIPHONE_XIV_ENORMOUS_BLACK', 'Ringphone XIV Black', 'EPIC'),
    ('ABIPHONE_XIV_ENORMOUS_PURPLE', 'Ringphone XIV Purple', 'EPIC'),
    ('ABIPHONE_FLIP_DRAGON', 'Ringphone Flip: Dragon', 'LEGENDARY'),
    ('ABIPHONE_FLIP_NUCLEUS', 'Ringphone Flip: Nucleus', 'LEGENDARY'),
    ('ABIPHONE_FLIP_VOLCANO', 'Ringphone Flip: Volcano', 'LEGENDARY'),
    ('ABIPHONE_FLIP_PLUS_LUNAR', 'Ringphone Flip+: Lunar', 'LEGENDARY'),
    ('ABIPHONE_FLIP_PLUS_FLOWER', 'Ringphone Flip+: Flower', 'LEGENDARY'),
    ('ABIPHONE_FLIP_PLUS_CLOCKWORK', 'Ringphone Flip+: Clockwork', 'LEGENDARY'),
]
PHONE_MATERIAL = {'COMMON': 'PAPER', 'UNCOMMON': 'PAPER', 'RARE': 'PAPER', 'EPIC': 'PAPER', 'LEGENDARY': 'PAPER'}   # a map item would render as an empty map
FEATURE_KEYS = {
    'Do Not Disturb Mode': 'dnd', 'Ringtones': 'ringtones', 'Snake': 'snake',
    'Tic Tac Toe': 'tictactoe', 'Speed Dial': 'speed_dial', 'Contacts Directory': 'directory',
}

items = []
phones = []
for pid, our, rarity in PHONES:
    d = neu(pid)
    lore = [strip(x) for x in d['lore']]
    contacts = int(next(re.search(r'Maximum Contacts: (\d+)', l).group(1) for l in lore if 'Maximum Contacts' in l))
    discs = next((int(re.search(r'Maximum Discs: (\d+)', l).group(1)) for l in lore if 'Maximum Discs' in l), 0)
    feats = [FEATURE_KEYS[l.strip()] for l in lore if l.strip() in FEATURE_KEYS]
    tier = ['COMMON', 'UNCOMMON', 'RARE', 'EPIC', 'LEGENDARY'].index(rarity)
    phones.append({'id': pid, 'contacts': contacts, 'discs': discs, 'features': feats})
    renames.append((pid, strip(d['displayname']), our))
    body = ['&7A pocket phone. Click townsfolk while', '&7holding it to save them as contacts,',
            '&7then call them from anywhere.', '', '&7Features:', '&8 • &7Maximum Contacts: &a' + str(contacts)]
    if discs:
        body.append('&8 • &7Ringtone Slots: &a' + str(discs))
    nice = {'directory': 'Contacts Directory', 'dnd': 'Do Not Disturb Mode', 'ringtones': 'Ringtones',
            'snake': 'Snake', 'tictactoe': 'Tic Tac Toe', 'speed_dial': 'Speed Dial'}
    for f in feats:
        body.append('&8 • &7' + nice[f])
    abilities = [{'id': 'extras_phone_open', 'name': 'Open Phone', 'trigger': 'RIGHT_CLICK', 'mana': 0, 'cooldown': 0,
                  'lore': ['&7Opens your contacts.']}]
    if 'speed_dial' in feats:
        abilities.append({'id': 'extras_phone_speed_dial', 'name': 'Speed Dial', 'trigger': 'LEFT_CLICK', 'mana': 0,
                          'cooldown': 0, 'lore': ['&7Calls your speed-dial contact.']})
    items.append({'id': pid, 'name': our, 'material': PHONE_MATERIAL[rarity], 'rarity': rarity, 'category': 'NONE',
                  'tags': ['abiphone'], 'maxStack': 1, 'lore': body, 'abilities': abilities, 'glow': tier >= 4,
                  'extra': {'contacts': contacts, 'discs': discs, 'tier': tier}})

d = neu('TRIO_CONTACTS_ADDON')
renames.append(('TRIO_CONTACTS_ADDON', strip(d['displayname']), 'Ringphone Contacts Trio'))
items.append({'id': 'TRIO_CONTACTS_ADDON', 'name': 'Ringphone Contacts Trio', 'material': 'COMPARATOR',
              'rarity': 'SPECIAL', 'category': 'CONSUMABLE', 'tags': ['abiphone', 'consumable'],
              'lore': ['&7Permanently adds &a+3 &7contact slots to', '&7your phone plan, on every phone you',
                       '&7own now or later.', '', '&7Up to &a25 &7of these may be used.', '',
                       '&eRight-click to consume!']})

# ---------------------------------------------------------------------------------------------------------------
# Item skins for Fire Sales: every neu helmet skin whose helmet some module registered.
# ---------------------------------------------------------------------------------------------------------------
with open(os.path.join(ROOT, 'research', 'data', 'items.json'), encoding='utf-8') as f:
    API_ITEMS = json.load(f)['items']
BY_NAME = {}
for it in API_ITEMS:
    BY_NAME.setdefault(it['name'], []).append(it['id'])

# reference proper nouns inside skin names -> the names the OTHER modules already gave them (docs/names/*.md):
# collections/dungeons Bonzo -> Grinko, Necron -> Morvane, Shadow Assassin -> Shade Stalker; armor Divan -> Varnas,
# Yog -> Lavaborn, Fermento -> Fermenta, Tarantula -> Broodweaver, Warden -> Sentinel, Final Destination -> Last
# Horizon; accessories Jerry -> Gerald. The three Wither Lords have no rename anywhere yet: ours (TUNE).
PROPER = [
    (r"\bBonzo's\b", "Grinko's"), (r'\bBonzo\b', 'Grinko'), (r"\bNecron's\b", "Morvane's"), (r'\bNecron\b', 'Morvane'),
    (r"\bGoldor's\b", "Bulwark Lord's"), (r"\bMaxor's\b", "Swift Lord's"), (r"\bStorm's\b", "Tempest Lord's"),
    (r'\bShadow Assassin\b', 'Shade Stalker'), (r'\bDivan\b', 'Varnas'), (r'\bYog\b', 'Lavaborn'),
    (r'\bFermento\b', 'Fermenta'), (r'\bTarantula\b', 'Broodweaver'), (r'\bFinal Destination\b', 'Last Horizon'),
    (r'\bWarden Helmet\b', 'Sentinel Helmet'), (r'\bJerry\b', 'Gerald'), (r'\bPrecursor\b', 'Relic'),
]
FIXED_NAMES = {'SENTINEL_WARDEN': 'Vigilant Sentinel Skin', 'TRUE_WARDEN': 'True Sentinel Skin',
               'WARDEN_SPARTAN': 'Spartan Sentinel Skin', 'THUNDER_STORM_KNIGHT': 'Storm Knight Thunder Skin'}
RARITY_GEMS = {'UNCOMMON': 250, 'RARE': 400, 'EPIC': 650, 'LEGENDARY': 900, 'MYTHIC': 1200}   # TUNE (sample: EPIC 650)

OUR_NAMES = {}
for f in glob.glob(os.path.join(DATA, '*', 'items.json')):
    if os.sep + 'extras' + os.sep in f:
        continue
    with open(f, encoding='utf-8') as fh:
        for o in json.load(fh):
            OUR_NAMES.setdefault(o['id'], o.get('name'))


def targets_of(text):
    text = re.sub(r'^any tier of ', '', text)
    parts = re.split(r',\s*(?:and\s+)?|\s+and\s+', text)
    out = []
    for p in parts:
        p = p.strip()
        if not p:
            continue
        for i in BY_NAME.get(p, []):
            if i not in out:
                out.append(i)
    return out


def our_skin_name(iid, ref):
    if iid in FIXED_NAMES:
        return FIXED_NAMES[iid]
    name = ref
    for pat, rep in PROPER:
        name = re.sub(pat, rep, name)
    return name.strip()


def target_label(t):
    nm = OUR_NAMES.get(t)
    if not nm:
        nm = next((x['name'] for x in API_ITEMS if x['id'] == t), t)
        for pat, rep in PROPER:
            nm = re.sub(pat, rep, nm)
    return nm


skins = []
skin_items = []
for f in sorted(glob.glob(os.path.join(NEU, 'items', '*.json'))):
    with open(f, encoding='utf-8') as fh:
        d = json.load(fh)
    iid = d.get('internalname', '')
    if iid.startswith('PET_SKIN'):
        continue
    lore = [strip(x) for x in d.get('lore', [])]
    m = re.search(r'This skin can be applied to (.+?)\.', ' '.join(lore))
    if not m:
        continue
    tg = [t for t in targets_of(m.group(1)) if t in REG]
    tex = skull_of(d)
    if not tg or not tex:
        report.append('skin dropped (no registered helmet / no texture): %s -> %s' % (iid, m.group(1)))
        continue
    rar = 'EPIC'
    for r in ('MYTHIC', 'LEGENDARY', 'EPIC', 'RARE', 'UNCOMMON'):
        if lore and lore[-1].startswith(r):
            rar = r
            break
    ref = strip(d['displayname'])
    ours = our_skin_name(iid, ref)
    if not ours.lower().endswith('skin'):
        ours += ' Skin'
    tnames = [target_label(t) for t in tg]
    skin_items.append({'id': iid, 'ref': ref, 'name': ours, 'material': 'PLAYER_HEAD', 'skull': tex, 'rarity': rar,
                       'category': 'COSMETIC', 'maxStack': 1, 'tags': ['item_skin'], 'extra': {'skinTargets': tg},
                       'tnames': tnames})
    skins.append({'id': iid, 'rarity': rar, 'gems': RARITY_GEMS.get(rar, 650), 'stock': 950, 'targets': tg})

# Several reference skins share a bare name ("Baby Skin" exists for seven dragon helmets): name those after their
# helmet so a player can tell them apart in a menu.
from collections import Counter
dup = Counter(s['name'] for s in skin_items)
for s in skin_items:
    if dup[s['name']] > 1:
        stem = re.sub(r'\s+(Helmet|Head|Mask|Crown|Cap|Hat)$', '', s['tnames'][0])
        s['name'] = stem + ' ' + s['name']
    if s['name'] != s['ref']:
        renames.append((s['id'], s['ref'], s['name']))
    tn = s.pop('tnames')
    del s['ref']
    s['lore'] = ['&7Item skins give your gear a fresh', '&7new look! Apply one with &e/skins&7.', '',
                 ('&7Fits: &a' + ', '.join(tn)) if len(tn) <= 2 else ('&7Fits &a' + str(len(tn)) + ' &7different items.'),
                 '', '&eRight-click to apply!']
    items.append(s)
assert len({s['name'] for s in skin_items}) == len(skin_items), 'duplicate skin names'

# ---------------------------------------------------------------------------------------------------------------
# Harp songs (research 08 section 6.3; SkyBlock XP from neu constants/sblevels.json harp_songs_names)
# Melodies: public-domain tunes written out by hand; the reference's three non-PD tunes are replaced with
# original melodies (and new names) so nothing copyrighted ships.
# Token = one beat: a pitch like E4, F#5, or '-' for a rest.
# ---------------------------------------------------------------------------------------------------------------
with open(os.path.join(NEU, 'constants', 'sblevels.json'), encoding='utf-8') as f:
    SBL = json.load(f)
HARP_XP = SBL['miscellaneous_task']['harp_songs_names']

SONGS = [
    ('SONG_HYMN_JOY', 'Hymn to the Joy', 'Ode to Joy', 'EASY', 1,
     'E4 E4 F4 G4 G4 F4 E4 D4 C4 C4 D4 E4 E4 - D4 D4 - - E4 E4 F4 G4 G4 F4 E4 D4 C4 C4 D4 E4 D4 - C4 C4'),
    ('SONG_FRERE_JACQUES', 'Frère Jacques', 'Frère Jacques', 'EASY', 1,
     'C4 D4 E4 C4 C4 D4 E4 C4 E4 F4 G4 - E4 F4 G4 - G4 A4 G4 F4 E4 C4 G4 A4 G4 F4 E4 C4 C4 G3 C4 - C4 G3 C4'),
    ('SONG_AMAZING_GRACE', 'Amazing Grace', 'Amazing Grace', 'EASY', 1,
     'G3 C4 - E4 C4 E4 - D4 C4 - A3 G3 - G3 C4 - E4 C4 E4 - D4 G4 - - E4 G4 - E4 C4 E4 - D4 C4 - A3 G3 - G3 C4 - E4 C4 E4 - D4 C4'),
    ('SONG_BRAHMS', "Brahms' Lullaby", 'Cradle Song', 'MEDIUM', 2,
     'E4 E4 G4 - E4 E4 G4 - E4 G4 C5 B4 A4 A4 G4 - D4 E4 F4 D4 D4 E4 F4 - D4 F4 B4 A4 G4 B4 C5 - C4 C4 C5 - A4 F4 G4 - E4 C4 F4 G4 A4 G4 - C4 C4 C5 - A4 F4 G4 - E4 C4 F4 E4 D4 C4'),
    ('SONG_HAPPY_BIRTHDAY', 'Happy Birthday to You', 'Happy Birthday', 'MEDIUM', 2,
     'G4 G4 A4 G4 C5 B4 - G4 G4 A4 G4 D5 C5 - G4 G4 G5 E5 C5 B4 A4 - F5 F5 E5 C5 D5 C5 - G4 G4 A4 G4 C5 B4 - G4 G4 A4 G4 D5 C5'),
    ('SONG_GREENSLEEVES', 'Greensleeves', 'Greensleeves', 'MEDIUM', 2,
     'A4 C5 D5 E5 F5 E5 D5 B4 G4 A4 B4 C5 A4 A4 G#4 A4 B4 G#4 E4 - A4 C5 D5 E5 F5 E5 D5 B4 G4 A4 B4 C5 B4 A4 G#4 F#4 G#4 A4 A4'),
    # reference: a quiz-show theme (not public domain) -> an original tune
    ('SONG_JEOPARDY', 'Geothermy', 'Ponder Waltz', 'MEDIUM', 3,
     'C5 G4 E4 G4 C5 - B4 A4 G4 - F4 G4 A4 G4 E4 - D4 E4 F4 G4 A4 B4 C5 - G4 E4 C4 - D4 F4 E4 D4 C4 - E5 D5 C5 B4 A4 G4 F4 E4 D4 C4'),
    ('SONG_MINUET', 'Minuet', 'Minuet in G', 'HARD', 3,
     'D5 G4 A4 B4 C5 D5 - G4 - G4 - E5 C5 D5 E5 F#5 G5 - G4 - G4 - C5 D5 C5 B4 A4 B4 C5 B4 A4 G4 F#4 G4 A4 B4 G4 B4 A4 - D5 G4 A4 B4 C5 D5 - G4 - G4 - E5 C5 D5 E5 F#5 G5 - G4 - G4 - C5 D5 C5 B4 A4 B4 C5 B4 A4 G4 A4 B4 A4 G4 F#4 G4'),
    ('SONG_JOY_WORLD', 'Joy to the World', 'Joy to the World', 'HARD', 3,
     'C5 B4 A4 - G4 F4 E4 D4 C4 - G4 A4 - A4 B4 - B4 C5 - - C5 C5 B4 A4 G4 G4 F4 E4 C5 C5 B4 A4 G4 G4 F4 E4 E4 E4 E4 E4 E4 F4 G4 - F4 E4 D4 D4 D4 D4 E4 F4 - E4 D4 C4 C5 - A4 G4 F4 E4 F4 E4 D4 C4'),
    # reference: a film song (not public domain) -> an original tune
    ('SONG_PURE_IMAGINATION', 'Godly Imagination', 'Daydream Air', 'HARD', 4,
     'E4 G4 C5 B4 A4 G4 E4 - F4 A4 D5 C5 B4 A4 F4 - G4 B4 E5 D5 C5 B4 G4 - A4 C5 F5 E5 D5 C5 A4 G4 E4 G4 C5 B4 A4 G4 E4 D4 C4 D4 E4 G4 F4 E4 D4 C4 - C5 E5 G5 E5 C5 G4 E4 C4'),
    # reference: a 1945 chanson (not public domain) -> an original tune
    ('SONG_VIE_EN_ROSE', 'La Vie en Rose', 'Rosewater Evening', 'EXPERT', 4,
     'C5 B4 A4 G4 E4 G4 A4 - B4 A4 G4 F4 D4 F4 G4 - A4 G4 F4 E4 C4 E4 F4 G4 A4 B4 C5 D5 C5 - E5 D5 C5 B4 G4 B4 C5 D5 E5 D5 C5 B4 A4 G4 F4 E4 D4 E4 F4 G4 A4 G4 E4 C4'),
    # reference: a power-metal track (not public domain) -> an original, very fast tune
    ('SONG_FIRE_AND_FLAMES', 'Through the Campfire', 'Wildfire Run', 'EXPERT', 0,
     'E5 B4 G4 E4 G4 B4 E5 F#5 G5 F#5 E5 D5 B4 G4 A4 B4 C5 B4 A4 G4 F#4 G4 A4 B4 E5 B4 G4 E4 F#4 G4 A4 B4 C5 D5 E5 F#5 G5 E5 C5 A4 F#4 D4 E4 G4 B4 D5 E5 - E5 G5 F#5 E5 D5 C5 B4 A4 G4 F#4 E4 D4 E4 - E4 E5'),
    ('SONG_PACHELBEL', 'Pachelbel', 'Canon in D', 'EXPERT', 0,
     'F#5 E5 D5 C#5 B4 A4 B4 C#5 D5 C#5 B4 A4 G4 F#4 G4 E4 D4 F#4 A4 G4 F#4 D4 F#4 E4 D4 B3 D4 A4 G4 B4 A4 G4 F#4 D4 E4 C#5 D5 F#5 A5 A4 B4 G4 A4 F#4 D4 D5 D5 C#5 D5 C#5 D5 D4 C#4 A4 E4 F#4 D4 D5 C#5 B4 C#5 F#5 A5 B5 G5 F#5 E5 G5 F#5 E5 D5 C#5 B4 A4 G4 F#4 E4 G4 F#4 E4 D4'),
]
BEAT_TICKS = {'EASY': 8, 'MEDIUM': 6, 'HARD': 5, 'EXPERT': 4}   # TUNE: reference speeds are not documented
songs = []
for sid, ref, ours, diff, intel, notes in SONGS:
    toks = notes.split()
    for t in toks:
        if t != '-' and not re.fullmatch(r'[A-G]#?[3-5]', t):
            sys.exit('bad note %r in %s' % (t, sid))
    ticks = 3 if sid == 'SONG_FIRE_AND_FLAMES' else BEAT_TICKS[diff]
    songs.append({'id': sid, 'name': ours, 'difficulty': diff, 'intelligence': intel, 'sbxp': HARP_XP[sid],
                  'beatTicks': ticks, 'notes': notes})
    if ours != ref:
        renames.append((sid, ref, ours))
assert sum(s['sbxp'] for s in songs) == SBL['miscellaneous_task']['harp_songs'], 'harp SB XP total'

# ---------------------------------------------------------------------------------------------------------------
# Contacts (neu constants/abiphone.json: 86 reference contacts and their requirements). Each one is mapped onto
# the NPC on THIS server that does that job; the player only ever sees our NPC's own name, read live from the
# registry. A contact whose npc is not registered stays hidden (see /sb extras contacts).
# req: items {ID: n} (consumed unless keep), coins (paid), key (a requirement key), essence {type: n},
#      contact (another contact first), set (a full armour set worn).
# ---------------------------------------------------------------------------------------------------------------
with open(os.path.join(NEU, 'constants', 'abiphone.json'), encoding='utf-8') as f:
    ABI = json.load(f)

C = []
REG |= {i['id'] for i in items}   # our own phones and the Trio count too


def contact(cid, ref, npcs, cmd=None, args=None, req=None, lines=None, keep=False, tip=None):
    if ref not in ABI:
        sys.exit('unknown reference contact ' + ref)
    r = dict(req or {})
    items_needed = r.get('items', {})
    for i in list(items_needed):
        if i not in REG:
            report.append('contact %s: requirement item %s not registered -> dropped (TUNE)' % (cid, i))
            del items_needed[i]
    if keep:
        r['keep'] = True
    C.append({'id': cid, 'ref': ref, 'npcs': npcs if isinstance(npcs, list) else [npcs], 'command': cmd,
              'args': args or [], 'req': r, 'lines': lines or [], 'tip': tip or ''})


M = 'merchants'
contact('alda', 'Alda', 'shops_phone_vendor', M, ['phone_vendor'],
        lines=['I only stock the Basic here. Every other model is with {npc:shops_phone_dealer} on the Crimson Isle.'], tip='Sells the Ringphone Basic.')
contact('elizabeth', 'Elizabeth', 'bank_community_shop', 'extras_community', [],
        req={'items': {'TRIO_CONTACTS_ADDON': 1}}, keep=True,
        lines=['Community business! Projects, fire sales, the bits shop. What can I do for you?'],
        tip='Community Shop, City Projects and Fire Sales.')
contact('kat', 'Kat', 'pets_sitter', 'petsitter', req={'items': {'KAT_FLOWER': 1}},
        lines=["Your pets are in good paws. Want to check on them?"], tip='Pet upgrades.')
contact('trevor', 'Trevor', 'farming_trapper', 'trapper', req={'coins': 1_000_000},
        lines=['Got a fresh trail for you, if you are quick.'], tip='Trapper hunts.')
contact('jacob', 'Jacob', ['farming_host', 'farming_host_barn'], 'harvestfair', req={'items': {'JACOBS_TICKET': 10}},
        lines=['Fields are looking good this season! Here is the contest schedule.'], tip='Farming contests.')
contact('anita', 'Anita', 'farming_harvesta', 'medals', req={'contact': 'jacob'},
        lines=['Medals to trade? Bring them over the phone, I trust you.'], tip='Medal shop.')
contact('maddox', 'Maddox the Slayer', 'slayer_master', 'slayer', req={'items': {'AATROX_BATPHONE': 1}}, keep=True,
        lines=['Talk fast, I am mid-hunt. Which bounty do you want?'], tip='Slayer quests.')
contact('bartender', 'Bartender', 'shops_bartender', M, ['bartender'], req={'key': 'slayer:spider:9'},
        lines=['Delivery? Sure, for you. What are you drinking?'], tip='Tavern shop.')
contact('blacksmith', 'Blacksmith', 'reforges_blacksmith', 'reforge', req={'coins': 32_000_000},
        lines=['Bring the steel to my anvil in your mind, I will do the rest.'], tip='Reforging.')
contact('builder', 'Builder', 'shops_builder', M, ['builder'], req={'items': {'BUILDERS_WAND': 1}}, keep=True,
        lines=['Building something? I have blocks by the cartload.'], tip='Building blocks.')
contact('lumber_merchant', 'Lumber Merchant', 'shops_lumber_merchant', M, ['lumber_merchant'],
        req={'items': {'JUNGLE_AXE': 1}}, keep=True, lines=['Logs, saplings, axes. Say the word.'], tip='Lumber shop.')
contact('alchemist', 'Alchemist', 'alchemy_alchemist', 'brew', req={'items': {'MUTANT_NETHER_STALK': 64}},
        lines=['The cauldron is warm. What shall we brew?'], tip='Brewing.')
contact('wool_weaver', 'Wool Weaver', 'shops_wool_weaver', M, ['wool_weaver'], req={'items': {'ENCHANTED_WOOL': 512}},
        lines=['Every colour on the loom, ready to ship.'], tip='Wool and dyes.')
contact('mad_redstone_engineer', 'Mad Redstone Engineer', 'shops_tinkerer', M, ['tinkerer'],
        req={'items': {'ENCHANTED_REDSTONE_BLOCK': 1}}, lines=['Gears! Pistons! Wonderful. What do you need?'],
        tip='Redstone parts.')
contact('plumber_joe', 'Plumber Joe', 'shops_plumber', M, ['plumber'], lines=['Pipes are fine. Need anything else?'],
        tip='Plumbing supplies.')
contact('taylor', 'Taylor', 'shops_tailor', M, ['tailor'], lines=['Something sharp to wear? I have a fitting free.'],
        tip='Tailoring.')
contact('tia_the_fairy', 'Tia the Fairy', 'progression_fairy', 'fairysouls', req={'set': 'FAIRY'},
        lines=['Still collecting souls? Show me what you found.'], tip='Fairy Soul exchange.')
contact('fear_mongerer', 'Fear Mongerer', 'mayors_fear_mongerer', 'spooky',
        req={'items': {'PURPLE_CANDY': 64, 'ECTOPLASM': 6}}, lines=['Ooooh. You called. Candy for sale, if you dare.'],
        tip='Spooky festival shop.')
contact('frozen_alex', 'Frozen Alex', 'shops_frozen_trader', M, ['frozen_trader'],
        req={'items': {'ENCHANTED_LAVA_BUCKET': 1}}, lines=['Brrr. Warm hands, warm heart. Shopping?'],
        tip='Winter goods.')
contact('einary', 'Einary', 'mayors_einary', 'winter', lines=['The North Star Emporium is open for orders.'],
        tip='Winter emporium.')
contact('fred', 'Fred', 'mining_forge', 'forge', req={'items': {'FOUL_FLESH': 64}},
        lines=['Forge is hot. What are we making?'], tip='The Forge.')
contact('geo', 'Geo', 'shops_gemcutter', 'gemstones',
        req={'items': {'FLAWLESS_RUBY_GEM': 1, 'FLAWLESS_AMBER_GEM': 1, 'FLAWLESS_TOPAZ_GEM': 1, 'FLAWLESS_JADE_GEM': 1,
                       'FLAWLESS_SAPPHIRE_GEM': 1, 'FLAWLESS_AMETHYST_GEM': 1}},
        lines=['A gem a day keeps the dullness away. Let me see your stones.'], tip='Gemstones.')
contact('mort', 'Mort', 'dungeon_gatekeeper', 'dungeons', req={'items': {'SAVING_GRACE': 1, 'SPIRIT_BONE': 1}},
        lines=['The gates are always open, for those who come back.'], tip='Dungeon runs.')
contact('ophelia', 'Ophelia', 'shops_dungeon_quartermaster', M, ['dungeon_quartermaster'],
        req={'items': {'CATACOMBS_EXPERT_RING': 1}}, keep=True, lines=['Supplies for the Catacombs, delivered.'],
        tip='Dungeon supplies.')
contact('malik', 'Malik', 'dungeon_essence_keeper', 'essence', req={'essence': {'undead': 5000}},
        lines=['Essence speaks louder than coin down here.'], tip='Essence shop.')
contact('maxwell', 'Maxwell', 'thaumaturgist', 'powers', req={'items': {'ULTIMATE_CARROT_CANDY': 2}},
        lines=['Your accessories hum with power. Shall we tune them?'], tip='Accessory powers.')
contact('spider_tamer', 'Spider Tamer', 'shops_spider_tamer', M, ['spider_tamer'],
        lines=['My little ones say hello. Need anything?'], tip='Spider goods.')
contact('junker_joel', 'Junker Joel', 'shops_scrapper', M, ['scrapper'], req={'items': {'BAYOU_WATER_ORB': 1}},
        lines=['Scrap? Junk? Treasure? All the same to me.'], tip='Bayou scrap.')
contact('roddy', 'Roddy', 'fishing_roddy', 'rodparts', req={'items': {'TORN_CLOTH': 1}},
        lines=['Line, sinker, hook. What is broken this time?'], tip='Rod parts.')
contact('odger', 'Odger', ['fishing_trophy_keeper', 'shops_trophy_keeper'], 'trophyfish',
        lines=['Caught anything worth mounting?'], tip='Trophy fish.')
contact('queen_nyx', 'Queen Nyx', 'mage_leader', 'faction', req={'key': 'faction:mage:12000'},
        lines=['The Mages remember their friends. Speak.'], tip='Faction business.')
contact('clerk_seraphine', 'Clerk Seraphine', 'mayors_election', 'election',
        lines=['Ballots are counted twice. Want to see the candidates?'], tip='Elections.')
contact('sirius', 'Sirius', 'mayors_dark_auction', 'darkauction',
        lines=['Shh. The next auction is soon. Be there.'], tip='Dark Auction.')
contact('oringo', 'Oringo', 'mayors_zoo', 'zoo', req={'items': {'SILENT_PEARL': 64}},
        lines=['The animals miss you! The zoo is on the road.'], tip='Traveling zoo.')
contact('moby', 'Moby', 'shops_chum_vendor', 'baits', req={'items': {'FULL_CHUM_BUCKET': 64}},
        lines=['Chum is fresh, bait is fresher.'], tip='Baits.')
contact('philip', 'Philip', 'garden_pestwarden', 'pests', req={'items': {'SQUEAKY_TOY': 1}},
        lines=['Pests again? I will be right over. Figuratively.'], tip='Pest control.')
contact('tyzzo', 'Tyzzo', 'cz_end_guide', 'dragon', req={'items': {'DRAGON_SCALE': 1}},
        lines=['The eyes are watching the altar. Want the news?'], tip='Dragon fights.')
contact('carrolyn', 'Carrolyn', 'carmine_lore', 'crimson', req={'items': {'MAGMA_CORE': 1}},
        lines=['The Isle is restless today.'], tip='Crimson Isle news.')
contact('pablo', 'Pablo', 'shops_peddler_dragontail', M, ['peddler_dragontail'],
        lines=['Fresh from the dunes, good prices.'], tip='Desert goods.')
contact('rollim', 'Rollim', 'shops_peddler_scarleton', M, ['peddler_scarleton'],
        lines=['Mushrooms, the good kind. Want some?'], tip='Mushroom goods.')
contact('suus', 'Suus', 'shops_lampwright', M, ['lampwright'], lines=['Lamps lit, wicks trimmed.'], tip='Lamps.')
contact('sirih', 'Sirih', 'shops_brewer_scarleton', M, ['brewer_scarleton'], lines=['A brew for the road?'],
        tip='Crimson brews.')
contact('igrupan', 'Igrupan', 'shops_brewer_dragontail', M, ['brewer_dragontail'], lines=['Strong stuff, this one.'],
        tip='Barbarian brews.')
contact('kaus', 'Kaus', 'dojo_master', 'dojo', lines=['Discipline is a phone call away. Train.'], tip='The Dojo.')
contact('aranya', 'Aranya', 'crimson_armorsmith', 'crimson', lines=['Armour fitted? Come by the forge.'],
        tip='Crimson armour.')
contact('jax', 'Jax', 'hub_range_master', 'arrows', req={'coins': 10_000_000},
        lines=['Arrows by the quiverful. How many?'], tip='Archery.')
contact('st_jerry', 'St. Jerry', 'mayors_jerry', 'winter', lines=['Season of Gerald greetings to you!'],
        tip='Winter festival.')
contact('rusty', 'Rusty', 'mines_delver', 'mines', lines=['Lost something down the shafts? I keep a list.'],
        tip='Deep mines.')
contact('walter', 'Walter', 'mining_commissions', 'commissions', lines=['Commissions are posted. Take your pick.'],
        tip='Commissions.')
contact('gemma', 'Gemma', 'mining_hollows_merchant', 'hollowsmerchant',
        lines=['Need supplies for the hollows?'], tip='Hollows supplies.')

contacts_out = C

# ---------------------------------------------------------------------------------------------------------------
# City Projects - the research has no table (08 section 6.8 lists the tab only; 05: 200 fame per contribution).
# Designed in spirit: long-lived community builds, every component a personal one-time contribution with a
# permanent personal perk, and a community goal that switches on a server-wide perk while the project runs.
# Every number is TUNE.
# ---------------------------------------------------------------------------------------------------------------
P = []


def comp(cid, name, coins, items_, stat=None, value=0.0, sbxp=10):
    for i in items_:
        if i not in REG:
            sys.exit('city component %s: unknown item %s' % (cid, i))
    return {'id': cid, 'name': name, 'coins': coins, 'items': items_, 'stat': stat, 'value': value, 'sbxp': sbxp}


P.append({'id': 'harbor_restoration', 'name': 'Harbor Restoration', 'icon': 'FISHING_ROD',
          'desc': ['The old pier is rotting into the pond.', 'Rebuild it board by board.'],
          'goal': 40, 'perk': {'type': 'skill_xp', 'skill': 'FISHING', 'value': 0.05, 'text': '+5% Fishing XP'},
          'components': [
              comp('pier_planks', 'Pier Planks', 250_000, {'ENCHANTED_OAK_LOG': 64}, 'FISHING_SPEED', 2),
              comp('net_racks', 'Net Racks', 500_000, {'ENCHANTED_STRING': 64}, 'FISHING_SPEED', 2),
              comp('lighthouse_lamp', 'Lighthouse Lamp', 1_000_000, {'ENCHANTED_GLOWSTONE_DUST': 32}, 'SEA_CREATURE_CHANCE', 0.5),
              comp('bait_cellar', 'Bait Cellar', 2_000_000, {'ENCHANTED_RAW_FISH': 64}, 'SEA_CREATURE_CHANCE', 0.5)]})
P.append({'id': 'library_wing', 'name': 'Grand Library Wing', 'icon': 'BOOKSHELF',
          'desc': ['The library has run out of shelves.', 'Fund a new wing for the scribes.'],
          'goal': 40, 'perk': {'type': 'skill_xp', 'skill': 'ENCHANTING', 'value': 0.05, 'text': '+5% Enchanting XP'},
          'components': [
              comp('oak_shelves', 'Oak Shelves', 250_000, {'ENCHANTED_PAPER': 64}, 'INTELLIGENCE', 2),
              comp('reading_lamps', 'Reading Lamps', 500_000, {'ENCHANTED_GLOWSTONE_DUST': 32}, 'INTELLIGENCE', 2),
              comp('ink_vats', 'Ink Vats', 1_000_000, {'ENCHANTED_INK_SACK': 64}, 'INTELLIGENCE', 3),
              comp('lapis_dome', 'Lapis Dome', 2_000_000, {'ENCHANTED_LAPIS_LAZULI': 128}, 'ENCHANTING_WISDOM', 1)]})
P.append({'id': 'guildhall', 'name': "Miners' Guildhall", 'icon': 'IRON_PICKAXE',
          'desc': ['Miners need a hall to swap stories', 'and sharpen picks between shifts.'],
          'goal': 40, 'perk': {'type': 'skill_xp', 'skill': 'MINING', 'value': 0.05, 'text': '+5% Mining XP'},
          'components': [
              comp('stone_footings', 'Stone Footings', 250_000, {'ENCHANTED_COBBLESTONE': 128}, 'MINING_SPEED', 5),
              comp('iron_beams', 'Iron Beams', 500_000, {'ENCHANTED_IRON': 64}, 'MINING_SPEED', 5),
              comp('coal_furnaces', 'Coal Furnaces', 1_000_000, {'ENCHANTED_COAL': 64}, 'MINING_FORTUNE', 2),
              comp('mithril_bell', 'Mithril Bell', 2_000_000, {'ENCHANTED_MITHRIL': 32}, 'MINING_FORTUNE', 3)]})
P.append({'id': 'granary', 'name': "Farmers' Granary", 'icon': 'HAY_BLOCK',
          'desc': ['Harvests rot without a dry store.', 'Raise a granary for the whole valley.'],
          'goal': 40, 'perk': {'type': 'skill_xp', 'skill': 'FARMING', 'value': 0.05, 'text': '+5% Farming XP'},
          'components': [
              comp('grain_bins', 'Grain Bins', 250_000, {'ENCHANTED_WHEAT': 128}, 'FARMING_FORTUNE', 2),
              comp('root_cellar', 'Root Cellar', 500_000, {'ENCHANTED_POTATO': 64, 'ENCHANTED_CARROT': 64}, 'FARMING_FORTUNE', 2),
              comp('sugar_mill', 'Sugar Mill', 1_000_000, {'ENCHANTED_SUGAR': 64}, 'FARMING_FORTUNE', 3),
              comp('weathervane', 'Weathervane', 2_000_000, {'ENCHANTED_GOLD': 64}, 'FARMING_WISDOM', 1)]})
P.append({'id': 'watchtower', 'name': 'Graveyard Watchtower', 'icon': 'STONE_BRICKS',
          'desc': ['Something keeps climbing out of the', 'graveyard at night. Build a lookout.'],
          'goal': 40, 'perk': {'type': 'mob_coins', 'value': 0.05, 'text': '+5% coins from mobs'},
          'components': [
              comp('palisade', 'Palisade', 250_000, {'ENCHANTED_ROTTEN_FLESH': 64}, 'STRENGTH', 2),
              comp('bone_braziers', 'Bone Braziers', 500_000, {'ENCHANTED_BONE': 64}, 'STRENGTH', 2),
              comp('signal_horn', 'Signal Horn', 1_000_000, {'ENCHANTED_GUNPOWDER': 64}, 'DEFENSE', 5),
              comp('watch_crystal', 'Watch Crystal', 2_000_000, {'ENCHANTED_ENDER_PEARL': 32}, 'CRIT_DAMAGE', 2)]})
P.append({'id': 'festival_grounds', 'name': 'Festival Grounds', 'icon': 'FIREWORK_ROCKET',
          'desc': ['A proper field for fairs, fireworks', 'and the odd parade.'],
          'goal': 40, 'perk': {'type': 'magic_find', 'value': 2, 'text': '+2 Magic Find for everyone'},
          'components': [
              comp('bunting', 'Bunting', 250_000, {'ENCHANTED_PAPER': 32, 'ENCHANTED_STRING': 32}, 'SPEED', 2),
              comp('stage', 'Stage', 500_000, {'ENCHANTED_SPRUCE_LOG': 64}, 'HEALTH', 5),
              comp('lanterns', 'Lanterns', 1_000_000, {'ENCHANTED_GLOWSTONE_DUST': 64}, 'MAGIC_FIND', 1),
              comp('fountain', 'Fountain', 2_000_000, {'ENCHANTED_PRISMARINE_SHARD': 64}, 'PET_LUCK', 1)]})

# ---------------------------------------------------------------------------------------------------------------
os.makedirs(OUT, exist_ok=True)


def dump(name, obj):
    with open(os.path.join(OUT, name), 'w', encoding='utf-8', newline='\n') as f:
        json.dump(obj, f, indent=1, ensure_ascii=False)
        f.write('\n')


dump('items.json', items)
dump('phones.json', phones)
dump('songs.json', songs)
dump('contacts.json', contacts_out)
dump('city_projects.json', P)
dump('firesales.json', skins)

with open(NAMES, 'w', encoding='utf-8', newline='\n') as f:
    f.write('# extras - renames\n\nGenerated by `tools/gen/extras/gen_extras.py` (do not edit by hand). Contact display\n'
            'names are NOT renamed here: a contact always shows the live name of the NPC on this server that does\n'
            'that job (e.g. the reference "Elizabeth" is our Community Shop keeper), so one name stays one person.\n\n'
            '| id | reference name | our name |\n|---|---|---|\n')
    f.write('| (device) | Abiphone | Ringphone |\n')
    f.write('| (harp) | Melody\'s Harp | the harp (played at the foraging harpist, Lyra) |\n')
    for i, ref, ours in renames:
        f.write('| `%s` | %s | %s |\n' % (i, ref, ours))
    f.write('\n## Contacts (reference contact -> our NPC id)\n\n| contact id | reference | our npc |\n|---|---|---|\n')
    for c in contacts_out:
        f.write('| `%s` | %s | `%s` |\n' % (c['id'], c['ref'], '`, `'.join(c['npcs'])))

print('items %d (phones %d, skins %d), songs %d, contacts %d, projects %d' % (
    len(items), len(phones), len(skins), len(songs), len(contacts_out), len(P)))
for r in report:
    print('  ' + r)
