"""Generate the crafting module's VANILLA recipe tables from the real server jar.

    python tools/gen/crafting/gen_vanilla.py

Reads data/minecraft/recipe/*.json and data/minecraft/tags/item/*.json out of
server/versions/26.1.2/paper-26.1.2.jar (the exact game version we run), and writes:

  plugin/src/main/resources/data/crafting/vanilla_recipes.json
      [{"id","result","count","shaped","rows"|"ingredients","key","cat"}]
      cells are a Material name ("STICK") or "#group" (any member of the group matches).
  plugin/src/main/resources/data/crafting/vanilla_groups.json
      {"planks": ["OAK_PLANKS", ...], ...}  - item tags flattened, plus synthetic
      "any-of" groups for inline ingredient lists (["minecraft:coal","minecraft:charcoal"]).

Only crafting_shaped and crafting_shapeless are taken. Special / transmute / dye / smithing /
cooking recipes are skipped (they carry component data our item engine does not model).
At runtime every Material is translated through Sky.items().vanillaId(), so whatever sky id the
materials module chose for "oak log" is the one the recipe asks for.
"""
import json
import os
import zipfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
JAR = os.path.join(ROOT, "server", "versions", "26.1.2", "paper-26.1.2.jar")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "crafting")

# Results we do not want in the book: their item carries data the 3x3 engine would not reproduce,
# or they are meaningless in a void sky world.
SKIP_RESULTS = {
    "FIREWORK_ROCKET", "FIREWORK_STAR", "WRITTEN_BOOK", "WRITABLE_BOOK", "SUSPICIOUS_STEW",
    "KNOWLEDGE_BOOK", "DEBUG_STICK", "FILLED_MAP", "TIPPED_ARROW", "BUNDLE",
}
# Whole recipe ids to skip (redundant conversion recipes that would only clutter the book).
SKIP_IDS_SUFFIX = ("_from_honey_block",)
# Re-dye recipes (any coloured bed + dye -> red bed) only clutter the book.
SKIP_IDS_PREFIX = ("dye_",)


def mat(ns_id):
    """'minecraft:oak_planks' -> 'OAK_PLANKS'"""
    return ns_id.split(":", 1)[1].upper() if ":" in ns_id else ns_id.upper()


def main():
    z = zipfile.ZipFile(JAR)
    names = z.namelist()

    def read(n):
        return json.loads(z.read(n).decode("utf-8-sig"))

    # ---- tags --------------------------------------------------------------------------
    raw_tags = {}
    for n in names:
        if n.startswith("data/minecraft/tags/item/") and n.endswith(".json"):
            raw_tags[n[len("data/minecraft/tags/item/"):-5]] = read(n)["values"]

    resolved = {}

    def resolve(tag, depth=0):
        if tag in resolved:
            return resolved[tag]
        if depth > 20 or tag not in raw_tags:
            return []
        out = []
        for v in raw_tags[tag]:
            if isinstance(v, dict):
                v = v.get("id", "")
            if v.startswith("#"):
                for m in resolve(v[1:].split(":", 1)[-1], depth + 1):
                    if m not in out:
                        out.append(m)
            else:
                m = mat(v)
                if m not in out:
                    out.append(m)
        resolved[tag] = out
        return out

    groups = {}

    def cell(ing):
        """An ingredient -> a Material name or '#group'."""
        if isinstance(ing, dict):  # old {"item": ...} / {"tag": ...} form, just in case
            if "tag" in ing:
                ing = "#" + ing["tag"]
            else:
                ing = ing.get("item", "")
        if isinstance(ing, list):
            mats = []
            for e in ing:
                c = cell(e)
                ms = groups[c[1:]] if c.startswith("#") else [c]
                for m in ms:
                    if m not in mats:
                        mats.append(m)
            if len(mats) == 1:
                return mats[0]
            g = "any:" + "+".join(sorted(m.lower() for m in mats))
            if len(g) > 48:
                g = "any:" + mats[0].lower() + "+" + str(len(mats) - 1) + "_more"
                if g in groups and groups[g] != mats:
                    raise ValueError("group name clash " + g)
            groups[g] = mats
            return "#" + g
        if ing.startswith("#"):
            t = ing[1:].split(":", 1)[-1]
            ms = resolve(t)
            if not ms:
                raise ValueError("empty tag " + t)
            if len(ms) == 1:
                return ms[0]
            groups[t] = ms
            return "#" + t
        return mat(ing)

    recipes = []
    skipped = 0
    for n in sorted(names):
        if not (n.startswith("data/minecraft/recipe/") and n.endswith(".json")):
            continue
        rid = n[len("data/minecraft/recipe/"):-5]
        d = read(n)
        t = d.get("type")
        if t not in ("minecraft:crafting_shaped", "minecraft:crafting_shapeless"):
            continue
        res = d["result"]
        result = mat(res["id"])
        if result in SKIP_RESULTS or "components" in res or rid.endswith(SKIP_IDS_SUFFIX) or rid.startswith(SKIP_IDS_PREFIX):
            skipped += 1
            continue
        out = {"id": rid, "result": result, "count": int(res.get("count", 1)),
               "shaped": t.endswith("shaped"), "cat": d.get("category", "misc")}
        try:
            if out["shaped"]:
                out["rows"] = d["pattern"]
                out["key"] = {k: cell(v) for k, v in d["key"].items()}
            else:
                out["ingredients"] = [cell(i) for i in d["ingredients"]]
        except ValueError as e:
            print("skip", rid, e)
            skipped += 1
            continue
        recipes.append(out)

    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "vanilla_recipes.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(recipes, f, indent=0, separators=(",", ":"))
    with open(os.path.join(OUT, "vanilla_groups.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(dict(sorted(groups.items())), f, indent=1)
    print("vanilla recipes:", len(recipes), "skipped:", skipped, "groups:", len(groups))


if __name__ == "__main__":
    main()
