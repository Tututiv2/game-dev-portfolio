"""Scratch explorer for the bazaar generator: prints products the recipe-family pass cannot place.
Not used by the build; kept so the classification can be re-audited.  py -3 tools/gen/bazaar/explore.py
"""
import json, os, re, sys
sys.path.insert(0, os.path.dirname(__file__))
import gen_bazaar as g

if __name__ == "__main__":
    ctx = g.load()
    fam = g.families(ctx)
    left = []
    for pid in sorted(ctx["products"]):
        if pid in fam: continue
        if g.special_group(pid, ctx) is not None: continue
        left.append(pid)
    print(len(left), "unplaced")
    for pid in left:
        it = ctx["items"].get(pid, {})
        print(pid, "|", it.get("name"), "|", it.get("category"), "|", ctx["neu_lore_tail"].get(pid, ""))
