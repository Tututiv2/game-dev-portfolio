#!/usr/bin/env python3
"""Generate plugin/src/main/resources/data/skills/runes.json from the NEU item repo.

    python tools/gen/skills/gen_runes.py

Runes are the Runecrafting skill's items: cosmetic particle effects applied to a weapon, bow,
boots, chestplate or hoe at the Runic Pedestal, and fused two-into-one (I + I -> II, II + II -> III).

Parsed from research/data/neu-repo/items/<KEY>_RUNE;<tier>.json (nothing numeric typed here):
  required Runecrafting level ("Requires level N"), what it applies to, rarity, display colour,
  the skull texture, and whether it is a slayer/event drop ("Obtained ...").
Typed here: OUR descriptions (the reference lore is not copied), the particle each rune draws,
and the renames of invented proper nouns (docs/names/skills.md).

Only runes with all three tiers are generated (the single-tier ones are event/unique cosmetics),
and throwing-axe runes are skipped (no throwing axe applies runes in this game).
"""
import glob
import json
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
NEU = os.path.join(ROOT, "research", "data", "neu-repo", "items")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "skills", "runes.json")

SLOT = {"Weapons": "WEAPON", "Bows": "BOW", "Boots": "BOOTS", "Chestplates": "CHESTPLATE", "Hoes": "HOE"}

# Invented proper nouns get our own names; generic ones stay.
RENAME = {
    "SOULTWIST": "Soulwring",
    "ENDERSNAKE": "Voidcoil",
    "LAVATEARS": "Magmaweep",
    "COUTURE": "Tailored",
    "DARKNESS_WITHIN": "Inner Dark",
}

# key -> (particle spec, our own one-line flavour). Particle spec: a Bukkit Particle name, or
# DUST:rrggbb, or RAINBOW (cycling dust). Unknown names fall back to CRIT at runtime.
LOOK = {
    "BITE": ("CRIT", "Your arrows snap at the air like fangs."),
    "BLOOD_2": ("DUST:aa0000", "Your kills burst in a spray of red."),
    "CLOUDS": ("CLOUD", "Every step kicks up a puff of cloud."),
    "COUTURE": ("WAX_ON", "Strut about in a shimmer of fashion."),
    "DARKNESS_WITHIN": ("SQUID_INK", "Inky shadow pools around your feet."),
    "DRAGON": ("PORTAL", "Your arrows drag a ribbon of the Void."),
    "ENCHANT": ("ENCHANT", "Runic glyphs swirl around your chest."),
    "ENDERSNAKE": ("REVERSE_PORTAL", "A coil of void light trails behind you."),
    "FIERY_BURST": ("FLAME", "Crops you harvest go up in sparks."),
    "FIRE_SPIRAL": ("FLAME", "Your arrows spin a spiral of fire."),
    "GEM": ("HAPPY_VILLAGER", "Your arrows leave a glittering green wake."),
    "GOLDEN": ("DUST:ffcc00", "Your arrows trail flakes of gold."),
    "HEARTS": ("HEART", "Your foes go out in a shower of hearts."),
    "HOT": ("FLAME", "Little flames lick at your heels."),
    "ICE": ("SNOWFLAKE", "Your arrows hiss with frost."),
    "LAVATEARS": ("DRIPPING_LAVA", "Your arrows weep molten drops."),
    "LAVA": ("LAVA", "Your arrows spit magma as they fly."),
    "LIGHTNING": ("ELECTRIC_SPARK", "Sometimes a bolt of lightning marks a kill."),
    "MAGIC": ("WITCH", "Your arrows glow with a magical shine."),
    "MUSIC": ("NOTE", "Every kill plays a little tune."),
    "RAINBOW": ("RAINBOW", "Walk on a trail of every colour."),
    "REDSTONE": ("DUST:ff0000", "Leave a redstone circuit wherever you walk."),
    "SLIMY": ("ITEM_SLIME", "A squelchy trail follows your arrows."),
    "SMOKEY": ("LARGE_SMOKE", "Your arrows leave a charred trail."),
    "SNAKE": ("DUST:33aa33", "A green serpent slithers after your steps."),
    "SNOW": ("SNOWFLAKE", "Your kills freeze in a flurry of snow."),
    "SOULTWIST": ("SOUL", "Souls twist out of the things you slay."),
    "SPARKLING": ("FIREWORK", "Your shoes sparkle as you walk."),
    "SPIRIT": ("SOUL_FIRE_FLAME", "Pale spirit fire flares on every kill."),
    "TIDAL": ("SPLASH", "The tide follows you wherever you go."),
    "WAKE": ("FALLING_WATER", "Your arrows ride a calm wave."),
    "WHITE_SPIRAL": ("END_ROD", "Your arrows twirl in white spirals."),
    "ZAP": ("ELECTRIC_SPARK", "Crackle across the land with every step."),
    "ZOMBIE_SLAYER": ("DUST:4c7a2a", "Your kills rot away in a sickly cloud."),
}

COLOR = re.compile(r"^§([0-9a-fk-or])")
FMT = re.compile(r"§.")


def load(base, tier):
    p = os.path.join(NEU, f"{base};{tier}.json")
    if not os.path.exists(p):
        return None
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def main():
    bases = sorted({os.path.basename(f).split(";")[0] for f in glob.glob(os.path.join(NEU, "*_RUNE;*.json"))})
    out = []
    for base in bases:
        tiers = [load(base, t) for t in (1, 2, 3)]
        if any(t is None for t in tiers):
            continue
        one = tiers[0]
        lore = one["lore"]
        slot = SLOT.get(FMT.sub("", lore[1]).strip())
        if slot is None:
            continue
        key = base[: -len("_RUNE")]
        m = re.search(r"Requires level (\d+)", FMT.sub("", lore[0]))
        level = int(m.group(1)) if m else 1
        footer = FMT.sub("", lore[-1]).strip().split(" ")[0]
        disp = FMT.sub("", one["displayname"]).replace("◆", "").strip()
        name = re.sub(r"\s+I$", "", disp)
        if key in RENAME:
            name = RENAME[key] + " Rune"
        cm = COLOR.match(one["displayname"])
        color = cm.group(1) if cm else "f"
        body = " ".join(FMT.sub("", x) for x in lore[3:6])
        source = "drop" if "Obtained" in body else "runic"
        tex = re.search(r'Value:\\?"([A-Za-z0-9+/=]+)\\?"', one.get("nbttag", ""))
        part, flavour = LOOK.get(key, ("CRIT", "A cosmetic effect."))
        out.append({
            "key": key,
            "id": base,
            "name": name,
            "color": color,
            "rarity": footer,
            "slot": slot,
            "level": level,
            "source": source,
            "particle": part,
            "flavour": flavour,
            "skull": tex.group(1) if tex else None,
        })
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        json.dump(out, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print(f"wrote {len(out)} runes -> {OUT}")
    missing = [r["key"] for r in out if r["key"] not in LOOK]
    if missing:
        print("no LOOK entry (CRIT fallback):", missing)


if __name__ == "__main__":
    main()
