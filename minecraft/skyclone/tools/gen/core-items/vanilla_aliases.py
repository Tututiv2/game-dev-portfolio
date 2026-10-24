"""Generate plugin/src/main/resources/data/core-items/vanilla_aliases.json

Maps a modern Bukkit Material name to the reference game's ids that mean "that vanilla item"
(e.g. COCOA_BEANS -> ["INK_SACK:3", "INK_SACK-3"]). Core's ItemService.vanillaId(Material) walks
the list and returns the first id some MODULE registered; if none, the material name itself.

Source: research/data/neu-repo/items (items flagged vanilla, plus every collection key).
Run:  py -3 tools/gen/core-items/vanilla_aliases.py
"""
import json
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
NEU = os.path.join(ROOT, "research", "data", "neu-repo", "items")
COLL = os.path.join(ROOT, "research", "data", "collections.json")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "core-items", "vanilla_aliases.json")

MODEL = re.compile(r'ItemModel:"minecraft:([a-z0-9_]+)"')


def colon(neu_id):
    m = re.match(r"^(.*)-(\d+)$", neu_id)
    return m.group(1) + ":" + m.group(2) if m else neu_id


def main():
    coll = json.load(open(COLL, encoding="utf8"))["collections"]
    coll_keys = {k for cat in coll.values() for k in cat["items"]}
    by_mat = {}
    for f in sorted(os.listdir(NEU)):
        if not f.endswith(".json"):
            continue
        try:
            d = json.load(open(os.path.join(NEU, f), encoding="utf8"))
        except Exception:
            continue
        iid = d.get("internalname", "")
        cid = colon(iid)
        nbt = d.get("nbttag", "")
        if "ench:" in nbt or "SkullOwner" in nbt:
            continue
        if not (d.get("vanilla") or cid in coll_keys):
            continue
        m = MODEL.search(nbt)
        mat = (m.group(1) if m else d.get("itemid", ":").split(":")[1]).upper()
        if not mat:
            continue
        lst = by_mat.setdefault(mat, [])
        for cand in (cid, iid):
            if cand not in lst:
                lst.append(cand)
    out = {}
    for mat, ids in sorted(by_mat.items()):
        if mat in ids:
            continue  # the material name itself is the reference id; nothing to alias
        # collection keys first (they are what collections/sacks/bazaar count), then the rest
        order = list(ids)
        out[mat] = sorted(order, key=lambda i: (0 if i in coll_keys else 1, order.index(i)))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf8") as fh:
        json.dump(out, fh, indent=1, sort_keys=True)
    print("wrote", len(out), "aliases to", OUT)


if __name__ == "__main__":
    main()
