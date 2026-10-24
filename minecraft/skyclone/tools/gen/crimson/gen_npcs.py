"""Generate data/crimson/npcs.json: the crimson module's own NPCs (names, titles, skins, greeting lines).

    python tools/gen/crimson/gen_npcs.py

Skins come from the NEU repo's NPC pages (texture values only). Names and every line of dialogue are our
own (see docs/names/crimson.md). NPCs that the SHOPS module runs on this island (peddlers, brewers,
generator smiths, envoys, the trophy keeper, the devotee, the frantic engineer, the lampwright, the
phone dealer) are deliberately NOT here - the island only provides their anchors.
"""
import json
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
NEU = os.path.join(ROOT, "research", "data", "neu-repo", "items")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "crimson")

NPCS = [
    # id, anchor, name, title, skin source, role, lines
    ("crimson_guide", "npc.crimson_guide", "&fWatchman Orrin", "&e&lCRIMSON ISLE", "ROLLIM_NPC", "guide", [
        "Welcome to the Crimson Isle, traveller. Mind the heat.",
        "Two powers squabble over this rock: the Mages of Carmine Spire to the east...",
        "...and the Barbarians of Drakehold to the west. Pledge to one and they will give you work.",
        "Type &a/crimson &fany time to see your standing, your quests and the mini-boss timers.",
        "Once you've pledged, come and see me each day for a week - I'll vouch for you with your faction."]),
    ("barbarian_leader", "npc.barbarian_leader", "&6Warchief Grathe", "&c&lBARBARIANS", "CHIEF_SCORN_NPC", "leader_barbarian", [
        "Hah! Another soft-skinned wanderer.",
        "Drakehold takes anyone who can swing an axe and doesn't flinch at a little fire.",
        "Bring down the mages' champions and the beasts of this isle, and you'll earn our respect."]),
    ("mage_leader", "npc.mage_leader", "&5Archmage Seraphine", "&d&lMAGES", "QUEEN_NYX_NPC", "leader_mage", [
        "Ah. You have the look of someone with potential.",
        "Carmine Spire rewards discipline, study... and results.",
        "Help us keep the barbarian rabble in check and the order will remember your name."]),
    ("barbarian_shop", "npc.barbarian_shop", "&fQuartermaster Hrolf", "&c&lFACTION SHOP", "DUKE_NPC", "shop_barbarian", [
        "Only sworn Drakeholders trade here. Show me your standing."]),
    ("mage_shop", "npc.mage_shop", "&fArcanist Vela", "&d&lFACTION SHOP", "HILDA_NPC", "shop_mage", [
        "The order's armoury opens only to its members. Let me see your standing."]),
    ("dojo_master", "npc.dojo_master", "&fSensei Kiyoshi", "&e&lDOJO", "MASTER_TAO_NPC", "dojo", [
        "Strength is common. Control is rare.",
        "Seven trials wait in my dojo. Earn points in each and you will earn a belt.",
        "Step into the arena when you are ready."]),
    ("crimson_armorsmith", "npc.crimson_armorsmith", "&fForgemaster Ignatius", "&6&lARMOR PRESTIGE", "VULCAN_NPC", "armorsmith", [
        "Crimson-forged plate grows stronger with every hunt it survives.",
        "Bring me Crimson Essence and Leviathan teeth and I will raise its prestige."]),
    ("leviathan_dock", "npc.leviathan_dock", "&fDockhand Nell", "&c&lLEVIATHAN'S HOLLOW", "ELLE_NPC", "dock", [
        "This is where crews set out to hunt the Magma Leviathan.",
        "The lava's too restless right now - the hunts aren't running yet.",
        "Grab a key from a faction envoy in the meantime. You'll want one when the hunts begin."]),
    ("drakehold_banker", "npc.drakehold_banker", "&fCoffer-keeper Mordaunt", "&6&lBANK", "BRUUH_NPC", "bank", [
        "Drakehold keeps your coins safer than any mage would."]),
    ("carmine_banker", "npc.carmine_banker", "&fTreasurer Lucienne", "&6&lBANK", "BETH_NPC", "bank", [
        "Your savings, ledgered and warded."]),
    ("drakehold_bazaar", "npc.drakehold_bazaar", "&fTrader Kolm", "&6&lBAZAAR", "BUBU_NPC", "bazaar", [
        "Buy cheap, sell dear - even out here."]),
    ("carmine_bazaar", "npc.carmine_bazaar", "&fBroker Amaranth", "&6&lBAZAAR", "POMTAIR_NPC", "bazaar", [
        "The market reaches even the Spire."]),
    ("drakehold_lore", "npc.drakehold_lore.1", "&fOld Bjarka", None, "SIRIH_NPC", "lore", [
        "They say the skull in the square came from a dragon my grandfather's grandfather killed.",
        "Truth is, he found it. Still makes a good story.",
        "Watch the Dukedom to the south-west. The Duke never stays dead for long."]),
    ("carmine_lore", "npc.carmine_lore.1", "&fApprentice Lio", None, "ALCHEMAGE_NPC", "lore", [
        "I'm supposed to be studying ember runes. Don't tell the Archmage.",
        "The Rogue Pyromancer used to be one of us. Now he haunts the Courtyard south of here.",
        "If you see him, a few of the order's mages pay well for proof he's been put down."]),
]


def skin(name):
    p = os.path.join(NEU, name + ".json")
    if not os.path.exists(p):
        return None
    with open(p, encoding="utf-8") as f:
        d = json.load(f)
    m = re.search(r'Value:\\?"([A-Za-z0-9+/=]+)\\?"', d.get("nbttag", ""))
    return m.group(1) if m else None


def main():
    out = []
    for (nid, anchor, name, title, src, role, lines) in NPCS:
        out.append({"id": nid, "anchor": anchor, "name": name, "title": title, "skin": skin(src), "role": role,
                    "lines": ["&f" + l if not l.startswith("&") else l for l in lines]})
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "npcs.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, indent=1, ensure_ascii=False)
    print(f"npcs: {len(out)} ({sum(1 for o in out if o['skin'])} with skins)")


if __name__ == "__main__":
    main()
