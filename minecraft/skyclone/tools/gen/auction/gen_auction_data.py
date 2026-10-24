"""Generate plugin/src/main/resources/data/auction/*.json from research/data.

    py -3 tools/gen/auction/gen_auction_data.py

Outputs
  prices.json      reference unit prices per item id, from the live AH sample (auctions_page0.json).
                   Pets are keyed "TYPE;n" (n = rarity index, the pets module's ids). Player UUIDs,
                   names and lore from the sample are NOT copied - only id, price and counts.
  bazaar_ref.json  bazaar instant-buy prices (quick_status.buyPrice) for the craft-cost estimator,
                   used only when the live Bazaar service has no price for an ingredient.
  agents.json      our own NPC seller personas and Auction House dialogue (original text).
"""
import json
import os
import statistics
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, '..', '..', '..'))
DATA = os.path.join(ROOT, 'research', 'data')
OUT = os.path.join(ROOT, 'plugin', 'src', 'main', 'resources', 'data', 'auction')
sys.path.insert(0, HERE)
from nbt import decode_item_bytes  # noqa: E402

RARITY = ['COMMON', 'UNCOMMON', 'RARE', 'EPIC', 'LEGENDARY', 'MYTHIC']

# Sample ids that are not one item type (their real identity is inside the NBT); skipped.
SKIP = {'ENCHANTED_BOOK', 'POTION', 'RUNE', 'UNIQUE_RUNE', 'NEW_YEAR_CAKE', 'NEW_YEAR_CAKE_BAG',
        'DUNGEON_ITEM', 'ATTRIBUTE_SHARD', None}


def load(name):
    with open(os.path.join(DATA, name), encoding='utf-8') as f:
        return json.load(f)


def lower_quartile(xs):
    xs = sorted(xs)
    if len(xs) < 4:
        return xs[0] if len(xs) < 3 else xs[1]
    return statistics.quantiles(xs, n=4)[0]


def prices():
    sample = load('auctions_page0.json')['auctions']
    per = {}       # id -> list of unit prices
    fresh = {}     # pet id -> prices of level-1 (exp 0) pets
    tiers = {}
    for a in sample:
        try:
            item = decode_item_bytes(a['item_bytes'])['i'][0]
        except Exception:
            continue
        ea = item.get('tag', {}).get('ExtraAttributes', {})
        iid = ea.get('id')
        count = max(1, int(item.get('Count', 1)))
        if a['bin']:
            total = a['starting_bid']
        elif a.get('highest_bid_amount', 0) > 0:
            total = a['highest_bid_amount']
        else:
            continue   # an auction nobody bid on says nothing about value
        if iid == 'PET':
            try:
                info = json.loads(ea.get('petInfo', '{}'))
            except ValueError:
                continue
            if info.get('tier') not in RARITY or not info.get('type'):
                continue
            iid = '%s;%d' % (info['type'], RARITY.index(info['tier']))
            if float(info.get('exp', 0)) <= 0 and not info.get('heldItem'):
                fresh.setdefault(iid, []).append(total)
        if iid in SKIP:
            continue
        per.setdefault(iid, []).append(total / count)
        tiers[iid] = a.get('tier')
    out = []
    for iid in sorted(per):
        xs = per[iid]
        if ';' in iid:
            # A pet's sample price includes its level and held item. A fresh level-1 pet is what an
            # NPC seller lists, so price it from exp-0 samples, else from the cheapest sample.
            ref = min(fresh[iid]) if iid in fresh else min(xs)
        else:
            ref = lower_quartile(xs)
        out.append({'id': iid, 'ref': round(ref, 1), 'min': round(min(xs), 1), 'n': len(xs),
                    'tier': tiers.get(iid) or 'COMMON'})
    return out


def bazaar_ref():
    products = load('bazaar.json')['products']
    out = {}
    for pid, p in sorted(products.items()):
        qs = p.get('quick_status', {})
        buy = qs.get('buyPrice') or 0
        if buy > 0:
            out[pid] = round(buy, 1)
    return out


# Our own personas for the Auction Agents' consignment stock. Original names.
AGENTS = {
    'sellers': [
        'Isembard Mortlake', 'Liesl Brackwell', 'Ottavio Quenby', 'Saoirse Ashgrove', 'Teodor Pargeter', 'Wilhelmina Holloway',
        'Ebba Treloar', 'Caspian Fairweather', 'Rosalind Merriweather', 'Augustin Gaunt', 'Philippa Cresswell', 'Leopold Tolliver',
        'Imogen Whitlock', 'Henrietta Pennyfeather', 'Delphine Ostrander', 'Bartholomew Coyle'
    ],
    'master': {
        'name': '&6Auctioneer Dorian',
        'title': '&e&lAUCTION HOUSE',
        'lines': [
            '&fWelcome to the Auction House!',
            '&fList anything rare here. Buyers bid, or pay your Buy It Now price outright.',
            '&fThe Agents beside me keep the shelves stocked when the floor is quiet.'
        ]
    },
    'agent_1': {
        'name': '&eAgent Lysa',
        'title': '&e&lBROWSE',
        'lines': ['&fLooking for something? I can show you every listing on the floor.']
    },
    'agent_2': {
        'name': '&eAgent Pemberton',
        'title': '&e&lYOUR AUCTIONS',
        'lines': ['&fCome to sell, or to collect? Your listings are right here.']
    }
}


def main():
    os.makedirs(OUT, exist_ok=True)
    p = prices()
    with open(os.path.join(OUT, 'prices.json'), 'w', encoding='utf-8') as f:
        json.dump(p, f, indent=0, ensure_ascii=True)
    b = bazaar_ref()
    with open(os.path.join(OUT, 'bazaar_ref.json'), 'w', encoding='utf-8') as f:
        json.dump(b, f, indent=0, ensure_ascii=True, sort_keys=True)
    with open(os.path.join(OUT, 'agents.json'), 'w', encoding='utf-8') as f:
        json.dump(AGENTS, f, indent=2, ensure_ascii=True)
    pets = sum(1 for x in p if ';' in x['id'])
    print('prices.json: %d ids (%d pets); bazaar_ref.json: %d products' % (len(p), pets, len(b)))


if __name__ == '__main__':
    main()
