#!/usr/bin/env python3
"""Generate plugin/src/main/resources/data/island/upgrades.json from the research.

Source: research/05-economy-events-mayors.md, section "Profile upgrades":
  | Island Size | I-X | 161x161 -> 241x241 | ...
  | Guests Limit | I-V | +1 ... +4 (?) guest slots | ...

The island module reads this as its fallback table; the bank module's Community Shop table
(data/bank/upgrades.json) wins when present, and the self-test island.size-formula checks both agree
with the research endpoints (161 at tier 0, 241 at tier X).

Run from the repo root:  python tools/gen/island/gen_upgrades.py
"""
import json
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
SRC = os.path.join(ROOT, "research", "05-economy-events-mayors.md")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "island", "upgrades.json")

ROMAN = ["I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"]


def main():
    text = open(SRC, encoding="utf-8").read()
    size = None
    guests = None
    for line in text.splitlines():
        if line.startswith("| Island Size |"):
            nums = re.findall(r"(\d+)\s*[x×]\s*\d+", line)
            tiers = re.search(r"\|\s*I\s*[-–]\s*([IVX]+)\s*\|", line)
            if len(nums) >= 2 and tiers:
                base, top = int(nums[0]), int(nums[1])
                n = ROMAN.index(tiers.group(1)) + 1
                step = (top - base) / n
                size = {"base": base, "top": top, "tiers": [round(base + step * (i + 1)) for i in range(n)],
                        "source": line.strip()}
        if line.startswith("| Guests Limit |"):
            tiers = re.search(r"\|\s*I\s*[-–]\s*([IVX]+)\s*\|", line)
            if tiers:
                n = ROMAN.index(tiers.group(1)) + 1
                # research: "+1 ... +4 (?)" over five tiers is unclear; one extra slot per tier (TUNE)
                guests = {"tiers": [i + 1 for i in range(n)], "note": "TUNE: research lists '+1 ... +4 (?)'; one slot per tier assumed",
                          "source": line.strip()}
    if size is None or guests is None:
        print("could not find the Island Size / Guests Limit rows in", SRC)
        return 1
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        json.dump({"island_size": size, "guests_limit": guests}, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print("wrote", OUT, "island_size", size["tiers"], "guests", guests["tiers"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
