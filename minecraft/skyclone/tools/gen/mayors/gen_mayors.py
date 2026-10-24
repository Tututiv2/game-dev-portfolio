"""Generate the mayors module's data: the mayor roster + perks, the event items, event mobs, spawn
rules and every event content table (Dark Auction lots, Traveling Zoo prices, the Fear Mongerer's
shop, gift and box loot, burrow tables, the winter shops), plus docs/names/mayors.md.

Outputs (plugin/src/main/resources/data/mayors/):
  mayors.json    roster: every mayor, every perk (id, value, our name, our description, owner module)
  items.json     ItemDef array: candy, gifts, cake, pie, spade, claws, boxes, stock, snowball, north star
  recipes.json   Recipe array (Enchanted Ancient Claw)
  mobs.json      MobDef array: spooky mobs, mythological creatures (3 griffin tiers), siege attackers,
                 hidden Geralds, the Headless Rider
  spawns.json    SpawnRule array: spooky night spawns (condition event:spooky_festival)
  events.json    every content table the Java reads (see EventData.java)

Sources: research/data/items.json (material, tier, skull, npc price), research/data/neu-repo/items
(Fear Mongerer / Oringo / Einary / Sherry / Baker shops, gift drop tables, mythological + spooky
mob stats), research/05 (election rules, perk values, Dark Auction lots, spooky brackets).

Descriptions are NOT copied: every lore line here is written for this game. Proper nouns are renamed
through NAMES below and mirrored into docs/names/mayors.md.

Run:  py -3 tools/gen/mayors/gen_mayors.py
"""
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
D = os.path.join(ROOT, "research", "data")
NEU = os.path.join(D, "neu-repo", "items")
OUT = os.path.join(ROOT, "plugin", "src", "main", "resources", "data", "mayors")
NAMES_DOC = os.path.join(ROOT, "docs", "names", "mayors.md")

os.makedirs(OUT, exist_ok=True)

ITEMS = {i["id"]: i for i in json.load(open(os.path.join(D, "items.json"), encoding="utf8"))["items"]}


def neu(item_id):
    p = os.path.join(NEU, item_id + ".json")
    if not os.path.exists(p):
        return None
    return json.load(open(p, encoding="utf8"))


def skin_of(item_id):
    it = ITEMS.get(item_id)
    if it and isinstance(it.get("skin"), dict):
        return it["skin"].get("value")
    return None


def tier_of(item_id, default="COMMON"):
    it = ITEMS.get(item_id)
    return (it or {}).get("tier", default)


def sell_of(item_id):
    return (ITEMS.get(item_id) or {}).get("npc_sell_price", 0)


# ---------------------------------------------------------------------------------------------
# Renames: (kind, id, reference name, our name). Written to docs/names/mayors.md.
# ---------------------------------------------------------------------------------------------
NAMES = []


def name(kind, ident, ref, ours):
    NAMES.append((kind, ident, ref, ours))
    return ours


# ---------------------------------------------------------------------------------------------
# Mayors and perks. Values are the research numbers (research/05 §8.4-8.5). "owner" = the module
# that applies the effect ("mayors" = implemented in this module).
# ---------------------------------------------------------------------------------------------
def perk(pid, ref, ours, value, owner, lore, aliases=(), minister=True):
    name("perk", pid, ref, ours)
    return {"id": pid, "ref": ref, "name": ours, "value": value, "owner": owner,
            "lore": lore, "aliases": list(aliases), "ministerEligible": minister}


MAYORS = [
    {"id": "aatrox", "ref": "Aatrox", "name": name("mayor", "aatrox", "Aatrox", "Vexmar"), "type": "Slayer",
     "color": "c", "icon": "DIAMOND_SWORD", "special": False, "retired": False,
     "perks": [
         perk("aatrox.slashed_pricing", "SLASHED Pricing", "Cut-Rate Contracts", 50, "slayer",
              ["&7Starting a &5Slayer &7quest costs &a50% &7less."], ["aatrox.slayer_discount"]),
         perk("aatrox.slayer_xp_buff", "Slayer XP Buff", "Slayer XP Buff", 25, "slayer",
              ["&7Earn &d25% &7more &5Slayer XP&7."], ["aatrox.slayer_xp"]),
         perk("aatrox.pathfinder", "Pathfinder", "Trailblazer", 20, "slayer",
              ["&7Rare drops from &5Slayer &7bosses show up", "&a20% &7more often."], ["aatrox.rare_drops"]),
     ]},
    {"id": "cole", "ref": "Cole", "name": name("mayor", "cole", "Cole", "Bram"), "type": "Mining",
     "color": "6", "icon": "IRON_PICKAXE", "special": False, "retired": False,
     "perks": [
         perk("cole.prospection", "Prospection", "Deep Survey", 25, "minions",
              ["&7Mining minions work &a25% &7faster."]),
         perk("cole.mining_xp_buff", "Mining XP Buff", "Mining XP Buff", 60, "mayors",
              ["&7Gain &3+60☯ Mining Wisdom &7on", "&7every public island."], ["cole.mining_xp"]),
         # Aug-2026 rework (SkyblockMoney/work/mining.md section 4, election.json Y518): the Fiesta lasts the WHOLE
         # term since Feb 2026 and is Cole's minister perk too. Effects are mining's (dials mining.fiesta-*);
         # the "mining_fiesta" alias is the id mining's dial mining.fiesta-perk reads.
         perk("cole.mining_fiesta", "Mining Fiesta", "Mining Fiesta", 25, "mining",
              ["&7A &6Mining Fiesta &7runs all term: gain", "&3+25☯ Mining Wisdom&7, &6+25% Mining Fortune&7,",
               "&7and collect &9Refined Minerals &7and", "&5Glossy Gemstones &7from mining!"], ["cole.fiesta", "mining_fiesta"]),
         perk("cole.molten_forge", "Molten Forge", "Hot Anvils", 25, "mining",
              ["&7Every &6Forge &7job finishes &d25% &7faster."], ["cole.forge"]),
     ]},
    {"id": "diana", "ref": "Diana", "name": name("mayor", "diana", "Diana", "Orla"), "type": "Pets",
     "color": "a", "icon": "BONE", "special": False, "retired": False,
     "perks": [
         # NEU DIANA_MAYOR_MONSTER: Huntress' Intuition replaced Lucky! (+25 Pet Luck). It is her minister perk
         # (SkyblockMoney/work/combat.md section 3); the Ritual is mayor-only. Tracking is hunting's pseudo stat.
         perk("diana.huntress_intuition", "Huntress' Intuition", "Huntress' Intuition", 10, "hunting",
              ["&7Gain &a+10❃ Tracking&7."], ["diana.tracking"]),
         perk("diana.mythological_ritual", "Mythological Ritual", "Mythological Ritual", 1, "mayors",
              ["&7The mayor sells the &fGriffin &7pet and the", "&9Ancestral Spade&7: dig up &eGriffin Burrows",
               "&7in the Hub for &2Mythological &7creatures", "&7and their treasure."], ["diana.mythological", "diana.ritual"], minister=False),
         perk("diana.pet_xp_buff", "Pet XP Buff", "Pet XP Buff", 35, "pets",
              ["&7Pets gain &d35% &7more XP."], ["diana.pet_xp"]),
         perk("diana.sharing_is_caring", "Sharing is Caring", "Shared Bowls", 2, "pets",
              ["&7Unlocks &a2 &7more &6Exp Share &7slots and raises", "&7the &6Exp Share &7rate by &a+10%&7."], ["diana.exp_share"]),
     ]},
    {"id": "diaz", "ref": "Diaz", "name": name("mayor", "diaz", "Diaz", "Mercer"), "type": "Economist",
     "color": "6", "icon": "GOLD_INGOT", "special": False, "retired": False,
     "perks": [
         perk("diaz.long_term_investment", "Long Term Investment", "Long Game", 1, "mayors",
              ["&7Whoever becomes &bMinister &7under this mayor", "&7runs again next election, with &aevery &7perk."], ["diaz.lti"], minister=False),
         perk("diaz.volume_trading", "Volume Trading", "Bulk Lots", 2, "auction",
              ["&7The yearly vault auction offers &a2x &7the", "&7stock and &a2 &7extra special lots."], ["diaz.shens"]),
         perk("diaz.stock_exchange", "Stock Exchange", "Share Floor", 1, "mayors",
              ["&7Opens the &6Stonks Auction&7: bid coins for", "&5Stocks of Stonks &7to trade for rare goods."], ["diaz.stonks"]),
         perk("diaz.shopping_spree", "Shopping Spree", "Shopping Spree", 10, "shops",
              ["&7Daily NPC buy limits are &a10x &7higher,", "&7including NPC trades."], ["diaz.npc_limits"]),
     ]},
    {"id": "finnegan", "ref": "Finnegan", "name": name("mayor", "finnegan", "Finnegan", "Wystan"), "type": "Farming",
     "color": "e", "icon": "GOLDEN_HOE", "special": False, "retired": False,
     "perks": [
         perk("finnegan.pest_eradicator", "Pest Eradicator", "Pest Patrol", 20, "garden",
              ["&7The pest hunter's &6Farming Fortune &7bonus lasts", "&a60m&7, and &2Pests &7spawn &a20% &7sooner."], ["finnegan.pests"]),
         perk("finnegan.goated", "GOATed", "Top of the Field", 10, "farming",
              ["&7Farming contest medal brackets take in", "&aup to 10% &7more players."], ["finnegan.contest_brackets"]),
         perk("finnegan.blooming_business", "Blooming Business", "Busy Gates", 10, "garden",
              ["&2Garden &7visitors come more often, at higher", "&7rarity, and pay &a+10% Copper&7."], ["finnegan.visitors"]),
         perk("finnegan.grand_feast", "Grand Feast", "Year-Long Harvest", 1, "farming",
              ["&7The harvest feast runs &ball year&7 and the", "&7feast bakery opens for kernels."], ["finnegan.feast"], minister=False),
     ]},
    {"id": "foxy", "ref": "Foxy", "name": name("mayor", "foxy", "Foxy", "Pip"), "type": "Events",
     "color": "d", "icon": "CAKE", "special": False, "retired": False,
     "perks": [
         perk("foxy.sweet_benevolence", "Sweet Benevolence", "Sugar Rush", 30, "mayors",
              ["&7Event drops (&6Candy&7, &cGifts&7) have a &a30%", "&7chance to come in pairs."], ["foxy.candy"]),
         # NEU FOXY_MAYOR_MONSTER: an extra Fishing Festival, Mining Fiesta or Spooky Festival, rolled per ballot
         # (election.json Y517/Y518: Fishing, run Late Summer 22-24). {extra_event} = that election's variant + date.
         perk("foxy.extra_event", "Extra Event", "Encore", 1, "mayors",
              ["&7Schedules an extra {extra_event}", "&7event during the year."], ["foxy.extra_spooky", "foxy.extra_festival"]),
         perk("foxy.a_time_for_giving", "A Time for Giving", "Open Hands", 1, "mayors",
              ["&7Mobs killed during the &6Spooky Festival &7may", "&7drop &dParty Chests &7full of treats."], ["foxy.party_chests"]),
         perk("foxy.chivalrous_carnival", "Chivalrous Carnival", "Fairground", 1, "mayors",
              ["&7The &eCarnival &7in the Hub stays open the", "&7entire year: free tickets daily,", "&6Fruit Digging &7and a mask shop."], ["foxy.carnival"]),
     ]},
    {"id": "marina", "ref": "Marina", "name": name("mayor", "marina", "Marina", "Coralie"), "type": "Fishing",
     "color": "b", "icon": "FISHING_ROD", "special": False, "retired": False,
     "perks": [
         perk("marina.fishing_xp_buff", "Fishing XP Buff", "Fishing XP Buff", 50, "mayors",
              ["&7Gain &3+50☯ Fishing Wisdom &7on", "&7every public island."], ["marina.fishing_xp"]),
         perk("marina.luck_of_the_sea", "Luck of the Sea 2.0", "Deep Luck", 15, "mayors",
              ["&7Gain &3+15α Sea Creature Chance&7."], ["marina.scc"]),
         perk("marina.fishing_festival", "Fishing Festival", "Fishing Festival", 1, "fishing",
              ["&7A &bFishing Festival &7opens on the first three", "&7days of every month: &9sharks &7roam the water."], ["marina.festival"], minister=False),
         perk("marina.double_trouble", "Double Trouble", "Twin Lines", 0.1, "mayors",
              ["&7Every &31α Sea Creature Chance &7also gives", "&9+0.1⚓ Double Hook Chance&7."], ["marina.double_hook"]),
     ]},
    {"id": "paul", "ref": "Paul", "name": name("mayor", "paul", "Paul", "Osric"), "type": "Dungeons",
     "color": "5", "icon": "WITHER_SKELETON_SKULL", "special": False, "retired": False,
     "perks": [
         perk("paul.marauder", "Marauder", "Plunderer", 20, "dungeons",
              ["&7Dungeon reward chests cost &a20% &7less."], ["paul.chest_discount"]),
         perk("paul.ezpz", "EZPZ", "Easy Street", 10, "dungeons",
              ["&7Every dungeon run earns &a+10 &7bonus score."], ["paul.score"]),
         perk("paul.benediction", "Benediction", "Benediction", 25, "dungeons",
              ["&7Dungeon blessings are &a25% &7stronger."], ["paul.blessings"]),
     ]},
    {"id": "barry", "ref": "Barry", "name": name("mayor", "barry", "Barry", "Leofric"), "type": "Magic",
     "color": "3", "icon": "ENCHANTING_TABLE", "special": False, "retired": True,
     "perks": [
         perk("barry.arcane_catalyst", "Arcane Catalyst", "Arcane Spark", 15, "mayors",
              ["&7Spells deal &a15% &7more damage and cost", "&a15% &7less mana."], ["barry.spells"]),
         perk("barry.astral_negotiator", "Astral Negotiator", "Star Bargain", 15, "enchanting",
              ["&7Enchanting and anvil experience costs are", "&a15% &7lower."], ["barry.enchant_cost"]),
         perk("barry.magic_xp_boost", "Magic XP Boost", "Scholar's Boon", 15, "mayors",
              ["&7Gain &3+15☯ Alchemy &7and &3+15☯ Enchanting", "&3Wisdom&7."], ["barry.magic_xp"]),
     ]},
    # ---- special mayors (every 8 SB years, rotating) ----
    {"id": "scorpius", "ref": "Scorpius", "name": name("mayor", "scorpius", "Scorpius", "Corvane"), "type": "Special",
     "color": "5", "icon": "ENDER_EYE", "special": True, "retired": False,
     "perks": [
         perk("scorpius.bribe", "Bribe", "Kickback", 1, "mayors",
              ["&7If he wins, everyone who voted for him is", "&7paid &650,000 &7to &61,000,000 coins&7."], ["scorpius.bribes"]),
         perk("scorpius.darker_auctions", "Darker Auctions", "Shadow Lots", 7, "mayors",
              ["&7He crashes the &5Dark Auction&7: up to &a7", "&7rounds and lots you will not see otherwise."], ["scorpius.dark_auction"]),
     ]},
    {"id": "derpy", "ref": "Derpy", "name": name("mayor", "derpy", "Derpy", "Bonko"), "type": "Special",
     "color": "d", "icon": "PUFFERFISH", "special": True, "retired": False,
     "perks": [
         perk("derpy.quad_taxes", "QUAD TAXES!!!", "QUADRUPLE TAX!!!", 4, "auction",
              ["&7Every market tax is &c4x&7. Ouch."], ["derpy.taxes"]),
         perk("derpy.turbo_minions", "TURBO MINIONS!!!", "MINION OVERDRIVE!!!", 2, "minions",
              ["&7Minions produce &a2x &7the output."], ["derpy.minions"]),
         perk("derpy.double_mobs_hp", "DOUBLE MOBS HP!!!", "BEEFY MOBS!!!", 2, "mayors",
              ["&7Every monster has &c2x &7health."], ["derpy.mob_hp"]),
         perk("derpy.moar_skillz", "MOAR SKILLZ!!!", "MORE SKILLS!!!", 50, "mayors",
              ["&7Gain &a50% &7more skill XP."], ["derpy.skill_xp"]),
     ]},
    {"id": "jerry", "ref": "Jerry", "name": name("mayor", "jerry", "Jerry", "Gerald"), "type": "Special",
     "color": "a", "icon": "VILLAGER_SPAWN_EGG", "special": True, "retired": False,
     "perks": [
         perk("jerry.perkpocalypse", "Perkpocalypse", "Perk Parade", 18, "mayors",
              ["&7Every &a18 &7SkyBlock days (&a6 &7hours) the", "&7perks of another mayor switch on in full."], ["jerry.perk_rotation"]),
         perk("jerry.statspocalypse", "Statspocalypse", "Stat Surge", 10, "mayors",
              ["&7Every combat and misc stat is &a+10%&7."], ["jerry.stats"]),
         perk("jerry.jerrypocalypse", "Jerrypocalypse", "Gerald Sightings", 1, "mayors",
              ["&7Hidden &aGeralds &7pop out while you chop,", "&7farm, mine and fight. They carry boxes."], ["jerry.hidden"]),
     ]},
]

roster = {"mayors": MAYORS,
          "specialRotation": ["scorpius", "derpy", "jerry"],
          "perkpocalypseExclude": ["diaz"],
          "notes": "Values are research/05 §8.4-8.5. owner = module that applies the effect."}
json.dump(roster, open(os.path.join(OUT, "mayors.json"), "w", encoding="utf8"), indent=1, ensure_ascii=False)

# ---------------------------------------------------------------------------------------------
# Items this module owns (event specials). Display names + lore are ours.
# ---------------------------------------------------------------------------------------------
LEGACY = {"SKULL_ITEM": "PLAYER_HEAD", "WOOD_SPADE": "WOODEN_SHOVEL"}


def item(iid, nm, lore, category="MATERIAL", material=None, rarity=None, tags=(), extra=None, glow=None, sell=None):
    src = ITEMS.get(iid, {})
    mat = material or LEGACY.get(src.get("material", "STONE"), src.get("material", "STONE"))
    d = {"id": iid, "name": nm, "material": mat, "rarity": rarity or src.get("tier", "COMMON"),
         "category": category, "lore": lore, "tags": list(tags)}
    sk = skin_of(iid)
    if sk and mat == "PLAYER_HEAD":
        d["skull"] = sk
    s = sell if sell is not None else src.get("npc_sell_price", 0)
    if s:
        d["npcSell"] = s
    if glow is not None:
        d["glow"] = glow
    elif src.get("glowing"):
        d["glow"] = True
    if extra:
        d["extra"] = extra
    return d


def ref_name(iid):
    return (ITEMS.get(iid) or {}).get("name", iid)


OUR_ITEMS = [
    item("GREEN_CANDY", "Green Candy", ["&7Dropped by mobs during the &6Spooky", "&6Festival&7. Trade it at the &5Dread", "&5Peddler&7. Worth &a1 &7festival point."], tags=["bazaar"]),
    item("PURPLE_CANDY", "Purple Candy", ["&7The rarer sweet of the &6Spooky Festival&7.", "&7Worth &a5 &7festival points."], tags=["bazaar"]),
    item("WHITE_GIFT", "White Gift", ["&7Hold it and right-click another player", "&7to share it: you &bboth &7get the contents!"], category="CONSUMABLE", tags=["bazaar", "usable"]),
    item("GREEN_GIFT", "Green Gift", ["&7A sturdier present from the &bWinter", "&bWorkshop&7. Right-click another player to", "&7open it together."], category="CONSUMABLE", tags=["bazaar", "usable"]),
    item("RED_GIFT", "Red Gift", ["&7The best present of the season. Right-click", "&7another player to open it together."], category="CONSUMABLE", tags=["bazaar", "usable"]),
    item("NEW_YEAR_CAKE", "New Year Cake", ["&7Handed out by the &eBaker &7once every", "&7SkyBlock year during the &dNew Year", "&dCelebration&7. Collectors keep every year."], tags=["museum"], extra={"collectible": "new_year"}),
    item("SPOOKY_PIE", "Spooky Pie", ["&7A keepsake baked for everyone who earned", "&7points in a &6Spooky Festival&7."], tags=["museum"], extra={"collectible": "spooky"}),
    item("HORSEMAN_CANDLE", name("item", "HORSEMAN_CANDLE", "Horseman's Candle", "Rider's Candle"),
         ["&7Light it in the &cGraveyard &7at night to call", "&7the &cHeadless Rider&7."], category="CONSUMABLE", tags=["bazaar", "usable"]),
    item("GRIFFIN_FEATHER", "Griffin Feather", ["&7Dug out of a &eGriffin Burrow&7. Still soft", "&7after centuries in the ground."], tags=["bazaar"]),
    item("ANCIENT_CLAW", "Ancient Claw", ["&7Torn from a &2Mythological &7creature.", "&7Used in mythic crafting."], tags=["bazaar"]),
    item("ENCHANTED_ANCIENT_CLAW", "Enchanted Ancient Claw", ["&7160 &9Ancient Claws &7pressed into one."], tags=["bazaar"]),
    item("DAEDALUS_STICK", name("item", "DAEDALUS_STICK", "Daedalus Stick", "Labyrinth Rod"),
         ["&7A very rare drop from &2Minotaurs&7.", "&7The heart of a famous blade."], tags=["bazaar"]),
    item("ANCESTRAL_SPADE", "Ancestral Spade",
         ["&7Reveals and digs &eGriffin Burrows &7in the", "&7Hub while the &2Mythological Ritual &7runs.", "",
          "&7Burrow chain length: &a4", "", "&7A burrow may hold:", "&8 ● &cMonsters", "&8 ● &fGriffin Feathers", "&8 ● &6Coins"],
         category="SHOVEL", material="WOODEN_SHOVEL", rarity="RARE",
         extra={"abilities": [{"id": "mayors_spade_echo", "name": "Echo", "trigger": "RIGHT_CLICK", "cooldown": 3,
                                "lore": ["&7Points you toward your next &eburrow&7,", "&7or reveals a fresh chain."]}]}),
    # wave 5: the two upgraded spades (neu-repo ARCHAIC_SPADE / DEIFIC_SPADE: chain 6 / 8, Echo 2 s / 1 s). Reforgeable
    # (the reforges module lists all three spades for Erudite: +2 burrows a chain).
    item("ARCHAIC_SPADE", "Archaic Spade",
         ["&7Reveals and digs &eGriffin Burrows &7in the", "&7Hub while the &2Mythological Ritual &7runs.", "",
          "&7Burrow chain length: &a6", "", "&7A burrow may hold:", "&8 ● &cMonsters", "&8 ● &fGriffin Feathers", "&8 ● &6Coins",
          "&8 ● &9Mythos Fragments"],
         category="SHOVEL", material="STONE_SHOVEL", rarity="EPIC",
         extra={"abilities": [{"id": "mayors_spade_echo", "name": "Echo", "trigger": "RIGHT_CLICK", "cooldown": 2,
                                "lore": ["&7Points you toward your next &eburrow&7,", "&7or reveals a fresh chain."]}]}),
    item("DEIFIC_SPADE", "Deific Spade",
         ["&7Reveals and digs &eGriffin Burrows &7in the", "&7Hub while the &2Mythological Ritual &7runs.", "",
          "&7Burrow chain length: &a8", "", "&7A burrow may hold:", "&8 ● &cMonsters", "&8 ● &fGriffin Feathers", "&8 ● &6Coins",
          "&8 ● &9Mythos Fragments", "&8 ● &5Braided Griffin Feathers"],
         category="SHOVEL", material="GOLDEN_SHOVEL", rarity="LEGENDARY",
         extra={"abilities": [{"id": "mayors_spade_echo", "name": "Echo", "trigger": "RIGHT_CLICK", "cooldown": 1,
                                "lore": ["&7Points you toward your next &eburrow&7,", "&7or reveals a fresh chain."]}]}),
    item("STOCK_OF_STONKS", name("item", "STOCK_OF_STONKS", "Stock of Stonks", "Share of Stonks"),
         ["&7Won at the &6Stonks Auction &7while the", "&6Economist &7governs. Traders swap them", "&7for rare goods."], tags=["bazaar"]),
    item("JERRY_BOX_GREEN", name("item", "JERRY_BOX_GREEN", "Green Jerry Box", "Green Gerald Box"),
         ["&7Dropped by a hidden &aGerald&7.", "&7Holds &6coins&7, &3skill XP &7and odd trinkets.", "", "&eRight-click to open!"], category="CONSUMABLE", tags=["bazaar", "usable"]),
    item("JERRY_BOX_BLUE", name("item", "JERRY_BOX_BLUE", "Blue Jerry Box", "Blue Gerald Box"),
         ["&7Dropped by a hidden &9Gerald&7.", "&7Holds &6coins&7, &3skill XP &7and odd trinkets.", "", "&eRight-click to open!"], category="CONSUMABLE", tags=["bazaar", "usable"]),
    item("JERRY_BOX_PURPLE", name("item", "JERRY_BOX_PURPLE", "Purple Jerry Box", "Purple Gerald Box"),
         ["&7Dropped by a hidden &5Gerald&7.", "&7Holds &6coins&7, &3skill XP &7and odd trinkets.", "", "&eRight-click to open!"], category="CONSUMABLE", tags=["bazaar", "usable"]),
    item("JERRY_BOX_GOLDEN", name("item", "JERRY_BOX_GOLDEN", "Golden Jerry Box", "Golden Gerald Box"),
         ["&7Dropped by a hidden &6Gerald&7.", "&7Holds &6coins&7, &3skill XP &7and odd trinkets.", "", "&eRight-click to open!"], category="CONSUMABLE", tags=["bazaar", "usable"]),
    item("SIEGE_SNOWBALL", "Packed Snowball", ["&7The only thing that hurts a &cGift Thief&7.", "&7Handed out at the &bWinter Workshop&7."],
         material="SNOWBALL", rarity="COMMON", tags=["throwable", "no_ah"], sell=0),
    item("NORTH_STAR", "North Star", ["&7Earned defending the &bWinter Workshop&7.", "&7Spend them at the workshop emporium."],
         material="NETHER_STAR", rarity="SPECIAL", tags=["no_ah"], glow=True, sell=0),
    item("PARTY_CHEST", "Party Chest", ["&7A treat-stuffed chest from a festival mob.", "", "&eRight-click to open!"],
         material="CHEST", rarity="RARE", category="CONSUMABLE", tags=["usable"], sell=0),
]


# Carnival masks (Doug's token shop, neu-repo *_MASK.json): helmets with the reference stats. Their
# abilities are applied by CarnivalMasks (wave 5: all six) - extra.hook = carnival_mask marks them as ours.
def mask(iid, nm, rarity, stats, ability, flavour):
    d = item(iid, nm, ["&6Ability: " + ability[0]] + ["&7" + l for l in ability[1:]] + ["", "&8" + flavour],
             category="HELMET", material="PLAYER_HEAD", rarity=rarity, extra={"hook": "carnival_mask", "carnivalMask": True})
    d["stats"] = stats
    return d


OUR_ITEMS += [
    mask("ZOMBIE_MASK", "Zombie Mask", "UNCOMMON", {"HEALTH": 1}, ["Exanimate Blessing", "Increases the stats of Undead", "armor pieces by +5%."], "Guh."),
    mask("SALMON_MASK", "Salmon Mask", "UNCOMMON", {"HEALTH": 1}, ["Slippery Slope", "Gain +10 Speed and +10 Fishing", "Speed while in water."], "Not to be confused with a fish hat."),
    mask("ARMADILLO_MASK", "Armadillo Mask", "RARE", {"HEALTH": 1}, ["Soiling! Soiling! Soiling!", "Grants +50 Block Fortune."], "What does the Armadillo say?!"),
    mask("PARROT_MASK", "Parrot Mask", "RARE", {"HEALTH": 1}, ["Party Flock", "Nearby players within 20 blocks gain", "+1 Strength per upgrade of your", "Intimidation Accessory."], "Squawk, squawk!"),
    mask("BEE_MASK", "Bee Mask", "EPIC", {"HEALTH": 7, "FORAGING_FORTUNE": 7, "SPEED": 7}, ["Buzzy Little Bee", "Double the base pet stats of your", "active Bee pet."], "Buzz, buzz!"),
    mask("FROG_MASK", "Frog Mask", "EPIC", {"HEALTH": 7, "FORAGING_FORTUNE": 7, "SPEED": 7}, ["Stick Around", "A random Park region each SkyBlock", "day grants +20 Foraging Fortune", "and +5 Foraging Wisdom."], "Ribbit, ribbit!"),
]

# The three Carnival game tools (neu-repo CARNIVAL_SHOVEL / CARNIVAL_DART_TUBE / CARNIVAL_FISHING_ROD). Handed out
# when a round starts and taken back when it ends; never tradeable. Category NONE on purpose: the fishing module
# owns every FISHING_ROD-category bobber, and the carnival pond is not a fishing spot.
OUR_ITEMS += [
    item("CARNIVAL_SHOVEL", "Carnival Shovel",
         ["&7Digs up &6fruit &7in the &6Fruit Digging", "&7booth.", "",
          "&eDowsing Mode: &cMines", "&7Tells you how many &cbombs &7touch", "&7the square you dig.", "",
          "&eRight-click to change the Dowsing Mode!"],
         category="NONE", material="IRON_SHOVEL", rarity="UNCOMMON", tags=["no_ah", "no_trade", "soulbound", "no_reforge", "carnival_tool"]),
    item("CARNIVAL_DART_TUBE", "Carnival Dart Tube",
         ["&6Ability: Phwomp  &e&lRIGHT CLICK", "&7Fires a dart. Only the &cZombies &7of", "&7the &cZombie Shootout &7mind it."],
         category="NONE", material="STICK", rarity="UNCOMMON", tags=["no_ah", "no_trade", "soulbound", "no_reforge", "carnival_tool"]),
    item("CARNIVAL_FISHING_ROD", "Carnival Rod",
         ["&7Hooks the &3fish &7of the &3Catch a Fish", "&7pond. Hold &eRight-click &7to reel one in."],
         category="NONE", material="FISHING_ROD", rarity="UNCOMMON", tags=["no_ah", "no_trade", "soulbound", "no_reforge", "carnival_tool"]),
]
for it in OUR_ITEMS:
    for ab in (it.get("extra") or {}).get("abilities", []):
        it.setdefault("abilities", []).append(ab)
    if "extra" in it and "abilities" in it["extra"]:
        del it["extra"]["abilities"]
        if not it["extra"]:
            del it["extra"]
json.dump(OUR_ITEMS, open(os.path.join(OUT, "items.json"), "w", encoding="utf8"), indent=1, ensure_ascii=False)

recipes = [{"id": "ENCHANTED_ANCIENT_CLAW#mayors", "type": "CRAFTING", "result": "ENCHANTED_ANCIENT_CLAW", "amount": 1,
            "grid": ["", "ANCIENT_CLAW:32", "", "ANCIENT_CLAW:32", "ANCIENT_CLAW:32", "ANCIENT_CLAW:32", "", "ANCIENT_CLAW:32", ""],
            "unlock": None, "station": "Crafting"}]
# ARCHAIC_SPADE / DEIFIC_SPADE recipes already live in crafting's skyblock_recipes.json (sb:ARCHAIC_SPADE#0 ...);
# they turn on now that the items exist, so they are not repeated here.
json.dump(recipes, open(os.path.join(OUT, "recipes.json"), "w", encoding="utf8"), indent=1, ensure_ascii=False)

# ---------------------------------------------------------------------------------------------
# Mobs. "hits": health counted in hits (shielded mobs) — every hit deals exactly 1.
# ---------------------------------------------------------------------------------------------
def drop(iid, mn=1, mx=1, chance=1.0, label=None):
    d = {"item": iid, "min": mn, "max": mx, "chance": chance}
    if label:
        d["label"] = label
    return d


MOBS = []


def mob(mid, nm, level, entity, health, damage, xp, coins, drops, flags=(), behaviours=(), extra=None, family=None, equipment=None, speed=1.0, defense=0):
    m = {"id": mid, "name": nm, "level": level, "entity": entity, "health": health, "damage": damage,
         "combatXp": xp, "coins": coins, "drops": drops, "flags": list(flags), "behaviours": list(behaviours),
         "speed": speed, "defense": defense}
    if extra:
        m["extra"] = extra
    if family:
        m["family"] = family
    if equipment:
        m["equipment"] = equipment
    MOBS.append(m)


# Spooky Festival night mobs (neu-repo *_MONSTER.json: level, hits, xp 20, candy drops). Damage TUNE.
SPOOKY = [
    ("spooky_scary_jerry", name("mob", "spooky_scary_jerry", "Scary Jerry", "Scary Gerald"), 30, "ZOMBIE_VILLAGER", 12, 0.05, {"HEAD": "CARVED_PUMPKIN"}),
    ("spooky_trick_or_treater", "Trick or Treater", 30, "ZOMBIE", 10, 0.05, {"HEAD": "JACK_O_LANTERN"}),
    ("spooky_wraith", "Wraith", 50, "STRAY", 15, 0.05, {"HAND": "IRON_HOE"}),
    ("spooky_phantom_spirit", "Phantom Spirit", 35, "SKELETON", 10, 0.05, {"HEAD": "CARVED_PUMPKIN"}),
    ("spooky_batty_witch", "Batty Witch", 60, "WITCH", 17, 0.35, {}),
    ("spooky_crazy_witch", "Crazy Witch", 60, "WITCH", 17, 0.05, {}),
]
for mid, nm, lvl, ent, hits, purple, eq in SPOOKY:
    drops = [drop("GREEN_CANDY", 1, 1, 1.0), drop("PURPLE_CANDY", 1, 1, purple)]
    if mid in ("spooky_wraith", "spooky_phantom_spirit"):
        drops.append(drop("ECTOPLASM", 1, 1, 0.10))
    mob(mid, nm, lvl, ent, hits, 60 + lvl * 4, 20, 1, drops, flags=["aggressive", "spooky"],
        behaviours=["mayors_hits"], extra={"hits": True, "event": "spooky_festival"}, family="spooky", equipment=eq)
mob("spooky_headless_rider", name("mob", "spooky_headless_rider", "Headless Horseman", "Headless Rider"), 100, "SKELETON",
    3_000_000, 1200, 500, 5000,
    [drop("GREEN_CANDY", 40, 80, 1.0), drop("PURPLE_CANDY", 10, 25, 1.0), drop("WITCH_MASK", 1, 1, 0.02, "VERY_RARE"),
     drop("SPOOKY_HELMET", 1, 1, 0.05), drop("ENCHANTED_PUMPKIN", 4, 12, 0.5)],
    flags=["boss", "aggressive", "spooky", "no_knockback", "fire_immune"], family="spooky",
    equipment={"HEAD": "JACK_O_LANTERN", "HAND": "DIAMOND_HOE", "CHEST": "LEATHER_CHESTPLATE"}, extra={"tune": "HP/damage are TUNE (research: (?))"})

# Mythological creatures (wave 5, the 2025 ritual): twelve creatures, each with one variant per Griffin rarity it
# appears at. Id = myth_<kind>_<n>, n = Griffin rarity index + 1 (1 Common .. 6 Mythic), so the variant you meet
# is the one matching your Griffin. Level / health / damage / coins / combat XP / drops are the reference wiki's
# tables (hypixelskyblock.minecraft.wiki, Mythological Creatures + each creature page); where a page gives no
# coins/xp for a variant the neighbouring variant's figure is used and marked TUNE in extra.tune.
VARIANT = ["", "Blessed ", "Stalwart ", "Venerable ", "Exalted ", "Empyrean "]
GOLD = "ENCHANTED_GOLD"


def claws(n, gold=True, extra=()):
    out = [drop("ANCIENT_CLAW", n, n)]
    if gold:
        out.append(drop(GOLD, 0, n, 0.5))
    out.append(drop("MYTHOLOGICAL_DYE", 1, 1, 0.000004, "CRAZY_RARE"))
    return out + list(extra)


# kind: (reference name, our name, entity, equipment, first rarity index, [(lvl, hp, dmg, coins, xp, drops), ...])
MYTH2 = {
    "hunter": ("Minos Hunter", "Labyrinth Hunter", "ZOMBIE", {"HAND": "WOODEN_SWORD", "HEAD": "GOLDEN_HELMET", "CHEST": "LEATHER_CHESTPLATE"}, 0,
               [(15, 4000, 40, 50, 50, claws(1, extra=[drop("HILT_OF_REVELATIONS", 1, 1, 0.0025)])),
                (25, 15000, 125, 150, 100, claws(2, extra=[drop("HILT_OF_REVELATIONS", 1, 1, 0.005)])),
                (60, 100000, 500, 225, 150, claws(3, extra=[drop("HILT_OF_REVELATIONS", 1, 1, 0.008)])),
                (80, 350000, 1250, 300, 200, claws(4, extra=[drop("HILT_OF_REVELATIONS", 1, 1, 0.012)])),
                (125, 1000000, 2750, 400, 250, claws(6, extra=[drop("HILT_OF_REVELATIONS", 1, 1, 0.016)])),
                (200, 1750000, 4000, 500, 300, claws(8, extra=[drop("HILT_OF_REVELATIONS", 1, 1, 0.02)]))]),
    "lynx": ("Siamese Lynxes", "Twin Lynx", "OCELOT", {}, 0,
             [(15, 2500, 30, 50, 50, claws(1, extra=[drop("CROCHET_TIGER_PLUSHIE", 1, 1, 0.0005)])),
              (25, 12500, 75, 150, 100, claws(2, extra=[drop("CROCHET_TIGER_PLUSHIE", 1, 1, 0.001)])),
              (55, 75000, 350, 225, 150, claws(3, extra=[drop("CROCHET_TIGER_PLUSHIE", 1, 1, 0.0015)])),
              (85, 250000, 1000, 300, 200, claws(4, extra=[drop("CROCHET_TIGER_PLUSHIE", 1, 1, 0.002)])),
              (155, 750000, 2250, 400, 250, claws(6, extra=[drop("CROCHET_TIGER_PLUSHIE", 1, 1, 0.003)])),
              (200, 1250000, 3000, 500, 300, claws(8, extra=[drop("CROCHET_TIGER_PLUSHIE", 1, 1, 0.004)]))]),
    "nymph": ("Stranded Nymph", "Stranded Naiad", "DROWNED", {"HAND": "TRIDENT"}, 1,
              [(40, 20000, 100, 200, 150, claws(4, extra=[drop("WASHED_UP_SOUVENIR", 1, 1, 0.002)])),
               (70, 125000, 250, 275, 200, claws(5, extra=[drop("WASHED_UP_SOUVENIR", 1, 1, 0.0025)])),
               (95, 400000, 750, 350, 250, claws(6, extra=[drop("WASHED_UP_SOUVENIR", 1, 1, 0.003)])),
               (150, 1250000, 1750, 450, 300, claws(8, extra=[drop("WASHED_UP_SOUVENIR", 1, 1, 0.004)])),
               (250, 2500000, 2500, 550, 350, claws(10, extra=[drop("WASHED_UP_SOUVENIR", 1, 1, 0.005)]))]),
    "bull": ("Cretan Bull", "Labyrinth Bull", "COW", {}, 1,
             [(40, 25000, 100, 200, 150, claws(4, extra=[drop("CRETAN_URN", 1, 1, 0.002)])),
              (70, 150000, 400, 275, 200, claws(5, extra=[drop("CRETAN_URN", 1, 1, 0.0025)])),
              (95, 500000, 800, 350, 250, claws(6, extra=[drop("CRETAN_URN", 1, 1, 0.003)])),
              (150, 1500000, 2000, 450, 300, claws(8, extra=[drop("CRETAN_URN", 1, 1, 0.004)])),
              (250, 3000000, 2750, 550, 350, claws(10, extra=[drop("CRETAN_URN", 1, 1, 0.005)]))]),
    "harpy": ("Harpy", "Harpy", "VEX", {"HAND": "BOW"}, 2,
              [(85, 200000, 750, 300, 200, claws(7, extra=[drop("ANTIQUE_REMEDIES", 1, 1, 0.0035)])),
               (110, 750000, 1500, 400, 300, claws(8, extra=[drop("ANTIQUE_REMEDIES", 1, 1, 0.0045)])),
               (175, 2000000, 3750, 500, 400, claws(10, extra=[drop("ANTIQUE_REMEDIES", 1, 1, 0.005)])),
               (325, 3750000, 6500, 600, 500, claws(12, extra=[drop("ANTIQUE_REMEDIES", 1, 1, 0.006)]))]),
    "gaia": ("Gaia Construct", "Earthen Construct", "IRON_GOLEM", {}, 2,
             [(85, 175000, 600, 300, 200, claws(7, extra=[drop("DWARF_TURTLE_SHELMET", 1, 1, 0.0035)])),
              (140, 650000, 2000, 400, 300, claws(8, extra=[drop("DWARF_TURTLE_SHELMET", 1, 1, 0.0045)])),
              (260, 1500000, 3500, 500, 400, claws(10, extra=[drop("DWARF_TURTLE_SHELMET", 1, 1, 0.005)])),
              (325, 3500000, 5000, 600, 500, claws(12, extra=[drop("DWARF_TURTLE_SHELMET", 1, 1, 0.006)]))]),
    "minotaur": ("Minotaur", "Minotaur", "ZOMBIFIED_PIGLIN", {"HAND": "IRON_AXE"}, 3,
                 [(45, 1500000, 350, 500, 375, claws(10, extra=[drop("DAEDALUS_STICK", 1, 1, 0.0004)])),
                  (120, 8000000, 500, 1000, 475, claws(12, extra=[drop("DAEDALUS_STICK", 1, 1, 0.0006)])),
                  (210, 15500000, 1000, 1500, 575, claws(14, extra=[drop("DAEDALUS_STICK", 1, 1, 0.0008)]))]),
    "champion": ("Minos Champion", "Labyrinth Champion", "WITHER_SKELETON", {"HAND": "GOLDEN_SWORD", "HEAD": "GOLDEN_HELMET"}, 3,
                 [(175, 2000000, 1000, 500, 375, claws(10, extra=[drop("MINOS_RELIC", 1, 1, 0.0002)])),
                  (310, 12500000, 1750, 1000, 475, claws(12, extra=[drop("MINOS_RELIC", 1, 1, 0.0003)])),
                  (550, 25000000, 2500, 1500, 575, claws(14, extra=[drop("MINOS_RELIC", 1, 1, 0.0004)]))]),
    "sphinx": ("Sphinx", "Sphinx", "RAVAGER", {}, 4,
               [(750, 40000000, 4000, 2500, 1500, claws(16, extra=[drop("ENCHANTED_ANCIENT_CLAW", 0, 2, 0.5), drop("BRAIN_FOOD", 1, 1, 0.0025)])),
                (1250, 65000000, 7000, 2500, 2500, claws(32, extra=[drop("ENCHANTED_ANCIENT_CLAW", 0, 3, 0.5), drop("BRAIN_FOOD", 1, 1, 0.005)]))]),
    "inquisitor": ("Minos Inquisitor", "Labyrinth Inquisitor", "PIGLIN_BRUTE", {"HAND": "GOLDEN_AXE"}, 4,
                   [(750, 50000000, 750, 2500, 1500, claws(16, extra=[drop("ENCHANTED_ANCIENT_CLAW", 0, 2, 0.5),
                                                                       drop("ULTIMATE_CHIMERA;1", 1, 1, 0.01, "CRAZY_RARE")])),
                    (1250, 80000000, 1000, 2500, 2500, claws(32, extra=[drop("ENCHANTED_ANCIENT_CLAW", 0, 4, 0.5),
                                                                        drop("ULTIMATE_CHIMERA;1", 1, 1, 0.0125, "CRAZY_RARE")]))]),
    "manticore": ("Manticore", "Manticore", "RAVAGER", {}, 5,
                  [(1750, 125000000, 8000, 5000, 3500, claws(48, extra=[drop("ENCHANTED_ANCIENT_CLAW", 0, 4, 0.5),
                                                                        drop("FATEFUL_STINGER", 1, 1, 0.005), drop("MANTI_CORE", 1, 1, 0.002)]))]),
    "minos": ("King Minos", "the Labyrinth King", "PIGLIN_BRUTE", {"HAND": "GOLDEN_AXE", "HEAD": "GOLDEN_HELMET"}, 5,
              [(1750, 100000000, 2500, 5000, 3500, claws(48, extra=[drop("ENCHANTED_ANCIENT_CLAW", 0, 4, 0.5),
                                                                    drop("CROWN_OF_GREED", 1, 1, 0.02), drop("SHIMMERING_WOOL", 1, 1, 0.002)]))]),
}
# Burrow spawn weights per creature (wiki: Common 1000, Uncommon 800, Rare 600, Epic 400, Legendary 75, Mythic 15);
# each Griffin rarity unlocks the next two creatures.
MYTH_WEIGHT = {0: 1000, 1: 800, 2: 600, 3: 400, 4: 75, 5: 15}
for key, (ref, ours, ent, eq, first, variants) in MYTH2.items():
    nm = name("mob", "myth_" + key, ref, ours)
    for i, (lvl, hp, dmg, coins, xp, drops) in enumerate(variants):
        rar = first + i
        flags = ["aggressive", "mythological"]
        if key in ("champion", "inquisitor", "minotaur", "sphinx", "manticore", "minos", "bull", "gaia"):
            flags.append("no_knockback")
        prefix = VARIANT[rar]
        if key == "minos":
            prefix = ""
        mob(f"myth_{key}_{rar + 1}", (prefix + nm) if not nm.startswith("the ") else nm[0].upper() + nm[1:], lvl, ent, hp, dmg, xp, coins,
            drops, flags=flags, family="mythological", equipment=eq,
            extra={"griffinRarity": rar, "kind": key, "bowImmune": first >= 3 or key == "gaia", "weight": MYTH_WEIGHT[first]},
            behaviours=["mayors_myth"])
for m in MOBS:
    if m["id"].startswith("myth_"):
        m["magicResistance"] = 0.5          # wiki: every Mythological mob resists 50% of magic damage

# Workshop siege attackers (Season of Gerald). All TUNE: research gives no stats.
mob("siege_lava_imp", "Lava Imp", 20, "HUSK", 2000, 150, 15, 10, [drop("MAGMA_CREAM", 1, 2, 0.5)],
    flags=["aggressive", "fire_immune", "siege"], behaviours=["mayors_siege"], family="siege", extra={"tune": True})
mob("siege_molten_brute", "Molten Brute", 40, "ZOMBIFIED_PIGLIN", 12000, 400, 40, 25, [drop("MAGMA_CREAM", 2, 4, 0.8)],
    flags=["aggressive", "fire_immune", "siege"], behaviours=["mayors_siege"], family="siege", equipment={"HAND": "GOLDEN_AXE"}, extra={"tune": True})
mob("siege_gift_thief", "Gift Thief", 10, "MAGMA_CUBE", 6, 80, 10, 5, [],
    flags=["aggressive", "fire_immune", "siege"], behaviours=["mayors_siege", "mayors_hits", "mayors_snow_only"], family="siege",
    extra={"hits": True, "thief": True, "tune": True})
mob("siege_lava_behemoth", "Lava Behemoth", 100, "RAVAGER", 400000, 900, 300, 1000, [drop("ENCHANTED_MAGMA_CREAM", 1, 3, 0.5)],
    flags=["boss", "aggressive", "fire_immune", "siege", "no_knockback"], behaviours=["mayors_siege"], family="siege", extra={"tune": True})

# Hidden Geralds (Gerald Sightings perk). Hits from neu-repo (3/4/5/6), passive, drop their box.
for col, hits, box, chance in (("green", 3, "JERRY_BOX_GREEN", 54.55), ("blue", 4, "JERRY_BOX_BLUE", 27.27),
                               ("purple", 5, "JERRY_BOX_PURPLE", 13.64), ("golden", 6, "JERRY_BOX_GOLDEN", 4.55)):
    nm = name("mob", f"hidden_jerry_{col}", f"{col.title()} Jerry", f"{col.title()} Gerald")
    mob(f"hidden_jerry_{col}", nm, 1, "VILLAGER", hits, 1, 0, 0, [drop(box, 1, 1, 1.0)], flags=["passive", "no_knockback"],
        behaviours=["mayors_hits"], family="hidden_jerry", extra={"hits": True, "weight": chance})

json.dump(MOBS, open(os.path.join(OUT, "mobs.json"), "w", encoding="utf8"), indent=1, ensure_ascii=False)

SPAWNS = []
for area, island in (("graveyard", "hub"), ("wilderness", "hub")):
    for mid, mx in (("spooky_trick_or_treater", 4), ("spooky_scary_jerry", 3), ("spooky_wraith", 2),
                    ("spooky_phantom_spirit", 2), ("spooky_batty_witch", 1), ("spooky_crazy_witch", 1)):
        SPAWNS.append({"id": f"mayors_{mid}_{area}", "island": island, "area": area, "mob": mid, "max": mx,
                       "respawnSeconds": 12, "activationRange": 64, "condition": "event:spooky_festival"})
json.dump(SPAWNS, open(os.path.join(OUT, "spawns.json"), "w", encoding="utf8"), indent=1, ensure_ascii=False)

# ---------------------------------------------------------------------------------------------
# Event content tables
# ---------------------------------------------------------------------------------------------
def npc_shop(npc_file):
    d = neu(npc_file) or {}
    out = []
    for r in d.get("recipes", []) or []:
        if r.get("type") != "npc_shop":
            continue
        res = str(r.get("result"))
        m = re.match(r"^(.*):(\d+(?:\.\d+)?)$", res)
        amount = 1
        if m:
            amount = int(float(m.group(2)))
            res = m.group(1)
        cost = []
        coins = 0
        for c in r.get("cost", []):
            c = str(c)
            mm = re.match(r"^(.*):(\d+(?:\.\d+)?)$", c)
            cid, n = (mm.group(1), int(float(mm.group(2)))) if mm else (c, 1)
            if cid == "SKYBLOCK_COIN":
                coins += n
            elif cid == "SKYBLOCK_NORTH_STAR":
                cost.append(f"NORTH_STAR:{n}")
            else:
                cost.append(f"{cid}:{n}")
        out.append({"result": res, "amount": amount, "coins": coins, "cost": cost})
    return out


def gift_table(mob_file):
    d = neu(mob_file.upper()) or {}
    for r in d.get("recipes", []) or []:
        if r.get("type") == "drops":
            out = []
            for x in r.get("drops", []):
                ch = float(str(x.get("chance", "0")).rstrip("%")) / 100.0
                alts = [x["id"]] + [a["id"] for a in x.get("alternatives", [])]
                out.append({"items": alts, "chance": round(ch, 7)})
            return out
    return []


def goal(gid, game, kind, n, tokens, text):
    return {"id": gid, "game": game, "kind": kind, "target": n, "tokens": tokens, "tickets": 5, "text": text}


# Chantelle's goals (reference wiki, Carnival): five per game, 50 or 100 tokens + 5 tickets each, once per Carnival.
CARNIVAL_GOALS = [
    goal("fruit_play", "FRUIT", "plays", 3, 50, "Play &a3 games &7of &6Fruit Digging&7."),
    goal("fruit_score", "FRUIT", "score", 3000, 50, "Reach &a3,000 score &7in a single game of &6Fruit Digging&7."),
    goal("fruit_apples", "FRUIT", "apples", 3, 100, "Dig up &a3 Apples &7in a single game of &6Fruit Digging&7."),
    goal("fruit_unique", "FRUIT", "unique", 5, 100, "Dig up &a5 unique fruits &7in a single game of &6Fruit Digging&7."),
    goal("fruit_dragonfruit", "FRUIT", "dragonfruit", 1, 100, "Dig up a &dDragonfruit&7."),
    goal("fish_play", "FISH", "plays", 3, 50, "Play &a3 games &7of &3Catch a Fish&7."),
    goal("fish_score", "FISH", "score", 3000, 50, "Reach &a3,000 score &7in a single game of &3Catch a Fish&7."),
    goal("fish_caught", "FISH", "caught", 30, 100, "Catch &a30 Fish &7in a single game of &3Catch a Fish&7."),
    goal("fish_yellow", "FISH", "yellow", 5, 100, "Catch &a5 Yellow Fish &7in a single game of &3Catch a Fish&7."),
    goal("fish_streak", "FISH", "streak", 5, 100, "Reach a &a5 Catch Streak &7in a single game of &3Catch a Fish&7."),
    goal("shoot_play", "SHOOT", "plays", 3, 50, "Play &a3 games &7of &cZombie Shootout&7."),
    goal("shoot_score", "SHOOT", "score", 3000, 50, "Reach &a3,000 score &7in a single game of &cZombie Shootout&7."),
    goal("shoot_zombies", "SHOOT", "kills", 60, 100, "Shoot &a60 Zombies &7in a single game of &cZombie Shootout&7."),
    goal("shoot_diamond", "SHOOT", "diamond", 5, 100, "Shoot &a5 Diamond Zombies &7in a single game of &cZombie Shootout&7."),
    goal("shoot_lamps", "SHOOT", "lamps", 5, 100, "Shoot &a5 Redstone Lamps &7in a single game of &cZombie Shootout&7."),
]


def cperk(pid, event, ref, ours, costs, per, unit, effect):
    name("carnival_perk", pid, ref, ours)
    return {"id": pid, "event": event, "name": ours, "costs": costs, "perTier": per, "unit": unit, "effect": effect}


# Doug's Carnival Perks: ids, tiers and costs from neu-repo constants/carnivalshops.json; the effect per tier from the
# reference wiki's Doug page. Bought once, kept for good; each works during its own festival.
CARNIVAL_PERKS = [
    cperk("candy_scavenger", "spooky_festival", "Candy Scavenger", "Candy Scavenger", [50, 100, 150, 200, 250], 2, "%", "more Candy"),
    cperk("spooky_hook", "spooky_festival", "Spooky Savant", "Spooky Savant", [50, 100, 150, 200, 250], 1, "%", "Spooky Sea Creature chance"),
    cperk("purple_vogue", "spooky_festival", "Purple Vogue", "Purple Vogue", [100, 200, 400], 1, "%", "chance Green Candy is Purple"),
    cperk("trick_or_treat_enthusiast", "spooky_festival", "Trick or Treat Enthusiast", "Treat Seeker", [100, 1000], 0.5, "%", "Trick-or-Treat chest chance"),
    cperk("icy_hook", "season_of_jerry", "Icy Hook", "Icy Hook", [50, 100, 150, 200, 250], 0.5, "", "Sea Creature Chance on the Winter Workshop"),
    cperk("present_connoissuer", "season_of_jerry", "Present Connoisseur", "Present Connoisseur", [50, 100, 150, 200, 250], 2, "%", "more Gifts from the siege"),
    cperk("professional_gifter", "season_of_jerry", "Professional Gifter", "Professional Gifter", [100, 1000], 5, "%", "Coins and Skill XP from Gifts"),
    cperk("red_fad", "season_of_jerry", "Red Fad", "Red Fad", [100, 200, 400], 1, "%", "Red Gift chance"),
    cperk("fishing_prodigy", "fishing_festival", "Fishing Prodigy", "Fishing Prodigy", [50, 100, 150, 200, 250], 2, "", "Fishing Wisdom during the festival"),
    cperk("shark_sonar", "fishing_festival", "Shark Sonar", "Shark Sonar", [50, 100, 150, 200, 250], 1, "%", "Shark catch chance"),
    cperk("denture_collector", "fishing_festival", "Denture Collector", "Denture Collector", [100, 200, 400], 10, "%", "more Shark Teeth"),
    cperk("elasmobranchii_affinity", "fishing_festival", "Elasmobranchii Affinity", "Shark Affinity", [100, 1000], 50, "%", "extra Shark Fin chance"),
    cperk("refined_mind", "mining_fiesta", "Refined Mind", "Refined Mind", [50, 100, 150, 200, 250], 2, "%", "Refined Mineral and Glossy Gemstone finds"),
    cperk("fortunate_festivity", "mining_fiesta", "Fortunate Festivity", "Fortunate Festivity", [50, 100, 150, 200, 250], 5, "", "Mining Fortune during the fiesta"),
    cperk("powder_hoarder", "mining_fiesta", "Powder Hoarder", "Powder Hoarder", [100, 200, 400], 2, "%", "more Powder"),
    cperk("titanium_tenacity", "mining_fiesta", "Titanium Tenacity", "Titanium Tenacity", [100, 1000], 3, "%", "Titanium find chance"),
    cperk("storied_stinger", "mythological_ritual", "Storied Stinger", "Storied Stinger", [50, 100, 150, 200, 250], 5, "%", "damage to Mythological creatures"),
    cperk("deadly_greed", "mythological_ritual", "Deadly Greed", "Deadly Greed", [50, 100, 150, 200, 250], 2, "%", "Coins from Griffin Burrows"),
    cperk("dianas_favor", "mythological_ritual", "Diana's Favor", "Huntress's Favor", [100, 200, 400], 1, "", "Magic Find against Mythological creatures"),
    cperk("elusive_hunter", "mythological_ritual", "Elusive Hunter", "Elusive Hunter", [100, 1000], 2.5, "", "Tracking"),
    cperk("natural_talent", "harvest_feast", "Natural Talent", "Natural Talent", [50, 100, 150, 200, 250], 1, "%", "Seasoning find chance"),
    cperk("fortunate_feasting", "harvest_feast", "Fortunate Feasting", "Fortunate Feasting", [50, 100, 150, 200, 250], 5, "", "Farming Fortune during the feast"),
    cperk("feast_crashers", "harvest_feast", "Feast Crashers", "Feast Crashers", [100, 200, 400], 2, "", "Bonus Pest Chance"),
    cperk("leftovers", "harvest_feast", "Leftovers", "Leftovers", [100, 1000], 1, "", "Feast Flasks"),
]


events = {
    "darkAuction": {
        "_source": "research/05 §5.1",
        "entryPurse": 400000, "rounds": 5, "darkerRounds": 7,
        "previewSeconds": 5, "bidSeconds": 10, "lobbySeconds": 60,
        "lots": [
            {"item": "ENDER_ARTIFACT", "minBid": 20000000, "weight": 10},
            {"item": "WITHER_ARTIFACT", "minBid": 10000000, "weight": 10},
            {"item": "NETHER_ARTIFACT", "minBid": 10000000, "weight": 10},
            {"item": "MAGIC_8_BALL", "minBid": 20000000, "weight": 10},
            {"item": "ECCENTRIC_PAINTING_BUNDLE", "minBid": 20000000, "weight": 10},
            {"item": "HOCUS_POCUS_CIPHER", "minBid": 10000000, "weight": 10},
            {"item": "MIDAS_SWORD", "minBid": 100000, "weight": 10},
            {"item": "TURTLE;3", "minBid": 500000, "weight": 5},
            {"item": "PARROT;3", "minBid": 1000000, "weight": 5},
            {"item": "JELLYFISH;3", "minBid": 10000000, "weight": 3},
            {"item": "TURTLE;4", "minBid": 1000000, "weight": 3},
            {"item": "PARROT;4", "minBid": 1000000, "weight": 3},
            {"item": "JELLYFISH;4", "minBid": 10000000, "weight": 2},
            {"item": "FLOWER_GENERATOR_1", "minBid": 2500000, "weight": 4},
        ],
        "bookRound": [
            {"item": "ENCHANTMENT_SHARPNESS_6", "minBid": 500000, "weight": 10},
            {"item": "ENCHANTMENT_GIANT_KILLER_6", "minBid": 500000, "weight": 10},
            {"item": "ENCHANTMENT_PROTECTION_6", "minBid": 500000, "weight": 10},
            {"item": "ENCHANTMENT_GROWTH_6", "minBid": 500000, "weight": 10},
            {"item": "ENCHANTMENT_POWER_6", "minBid": 500000, "weight": 10},
            {"item": "ENCHANTMENT_VICIOUS_5", "minBid": 10000000, "weight": 3},
        ],
        "darkerLots": [
            {"item": "ENCHANTMENT_PROTECTION_7", "minBid": 10000000, "weight": 10},
            {"item": "ENCHANTMENT_SHARPNESS_7", "minBid": 10000000, "weight": 10},
            {"item": "ENCHANTMENT_POWER_7", "minBid": 10000000, "weight": 10},
            {"item": "ENCHANTMENT_GROWTH_7", "minBid": 10000000, "weight": 10},
            {"item": "ENCHANTMENT_GIANT_KILLER_7", "minBid": 10000000, "weight": 10},
            {"item": "ENCHANTMENT_COUNTER_STRIKE_5", "minBid": 10000000, "weight": 10},
            {"item": "MIDAS_STAFF", "minBid": 100000, "weight": 6},
        ],
        "darkerFinal": [
            {"item": "PLASMA_NUCLEUS", "minBid": 20000000, "weight": 10},
            {"item": "HEGEMONY_ARTIFACT", "minBid": 100000000, "weight": 4},
        ],
    },
    "zoo": {
        "_source": "neu-repo ORINGO_NPC shop; research/05 §7.9 (rotation)",
        "legendaryRotation": ["GIRAFFE", "ELEPHANT", "TIGER", "BLUE_WHALE", "MONKEY", "LION"],
        "offers": npc_shop("ORINGO_NPC"),
    },
    "spooky": {
        "_source": "research/05 §7.6; neu-repo FEAR_MONGERER_NPC shop",
        "baseCandyChance": 0.10, "purpleShare": 0.10, "greenPoints": 1, "purplePoints": 5,
        "trickOrTreatChance": 0.01, "trickOrTreatSpookyChance": 0.03,
        "shop": npc_shop("FEAR_MONGERER_NPC"),
        "brackets": [
            {"id": "top50", "name": "Top 50", "rank": 50, "minPoints": 5000, "coins": 1000000, "items": ["SKELETON_HORSE;4", "WITCH_MASK", "ENCHANTMENT_VAMPIRISM_6"], "sbxp": 75},
            {"id": "emerald", "name": "Emerald", "minPoints": 5000, "coins": 750000, "items": ["WITCH_MASK", "ENCHANTMENT_VAMPIRISM_6"], "sbxp": 75},
            {"id": "diamond", "name": "Diamond", "minPoints": 2000, "coins": 500000, "items": ["WITCH_MASK", "ENCHANTMENT_VAMPIRISM_5"], "sbxp": 50},
            {"id": "gold", "name": "Gold", "minPoints": 1750, "coins": 250000, "items": ["ENCHANTMENT_VAMPIRISM_4"], "sbxp": 40},
            {"id": "iron", "name": "Iron", "minPoints": 1500, "coins": 100000, "items": [], "sbxp": 30},
            {"id": "stone", "name": "Stone", "minPoints": 500, "coins": 50000, "items": [], "sbxp": 20, "tune": True},
            {"id": "wood", "name": "Wood", "minPoints": 100, "coins": 20000, "items": [], "sbxp": 10, "tune": True},
        ],
        "candyBoosts": {"armorSets": {"spooky": 0.05, "bat_person": 0.05},
                         "accessories": {"CANDY_RELIC": 0.20, "CANDY_ARTIFACT": 0.15, "CANDY_RING": 0.10, "CANDY_TALISMAN": 0.05},
                         "petBat": 0.20, "_note": "Candy talisman/ring % are TUNE; relic/artifact/bat are research"},
        "partyChest": {"chance": 0.005, "green": [3, 8], "purple": [0, 2], "coins": [1000, 5000]},
    },
    "gifts": {
        "_source": "neu-repo WHITE/GREEN/RED_GIFT drop tables; coin and skill XP ranges TUNE (the gift talismans' lore says gifts give both)",
        "WHITE_GIFT": {"coins": [1000, 5000], "skillXp": [500, 2000], "northStars": [0, 1], "drops": gift_table("white_gift")},
        "GREEN_GIFT": {"coins": [5000, 20000], "skillXp": [2000, 8000], "northStars": [1, 2], "drops": gift_table("green_gift")},
        "RED_GIFT": {"coins": [20000, 80000], "skillXp": [8000, 30000], "northStars": [2, 4], "drops": gift_table("red_gift")},
    },
    "jerryBoxes": {
        "_source": "TUNE: research names coins, skill XP and jerry items only",
        "JERRY_BOX_GREEN": {"coins": [5000, 25000], "skillXp": [500, 2000], "drops": [{"items": ["JERRY_TALISMAN_GREEN"], "chance": 0.02}]},
        "JERRY_BOX_BLUE": {"coins": [25000, 75000], "skillXp": [2000, 6000], "drops": [{"items": ["JERRY_TALISMAN_BLUE"], "chance": 0.02}]},
        "JERRY_BOX_PURPLE": {"coins": [75000, 300000], "skillXp": [6000, 20000], "drops": [{"items": ["JERRY_TALISMAN_PURPLE"], "chance": 0.02}, {"items": ["JERRY;3"], "chance": 0.01}]},
        "JERRY_BOX_GOLDEN": {"coins": [300000, 1000000], "skillXp": [20000, 60000], "drops": [{"items": ["JERRY_TALISMAN_GOLDEN"], "chance": 0.02}, {"items": ["JERRY;4"], "chance": 0.02}]},
    },
    "myth": {
        "_source": "wave 5: reference wiki (Mythological Ritual): 75% creature / 25% treasure per burrow, chain 4/6/8 by spade "
                   "(+2 with the Erudite reforge, max 10), creature weights by Griffin rarity, Ancestral treasure = Griffin "
                   "Feather 2/3 else 2,500-25,000 coins, Myth the Fish 1/10,000. Archaic/Deific treasure scaling is TUNE.",
        "chainLength": 4, "mobChance": 0.75, "radiusMin": 18, "radiusMax": 55,
        "chainBySpade": {"ANCESTRAL_SPADE": 4, "ARCHAIC_SPADE": 6, "DEIFIC_SPADE": 8},
        "eruditeBonus": 2, "chainMax": 10,
        "pools": {str(r + 1): {k: MYTH_WEIGHT[v[4]] for k, v in MYTH2.items() if v[4] <= r} for r in range(6)},
        "treasure": {
            "ANCESTRAL_SPADE": {"coins": [2500, 25000], "drops": [{"items": ["GRIFFIN_FEATHER"], "chance": 0.6667},
                                                              {"items": ["MYTH_THE_FISH"], "chance": 0.0001}]},
            "ARCHAIC_SPADE": {"coins": [5000, 50000], "drops": [{"items": ["GRIFFIN_FEATHER"], "chance": 0.6667},
                                                            {"items": ["MYTHOS_FRAGMENT"], "chance": 0.01},
                                                            {"items": ["MYTH_THE_FISH"], "chance": 0.0001}]},
            "DEIFIC_SPADE": {"coins": [10000, 100000], "drops": [{"items": ["GRIFFIN_FEATHER"], "chance": 0.6667},
                                                             {"items": ["MYTHOS_FRAGMENT"], "chance": 0.02},
                                                             {"items": ["BRAIDED_GRIFFIN_FEATHER"], "chance": 0.002},   # TUNE: lore lists it, no rate
                                                             {"items": ["MYTH_THE_FISH"], "chance": 0.0001}]},
        },
        "shop": [{"result": "GRIFFIN;0", "amount": 1, "coins": 10000, "cost": [], "tune": True},
                 {"result": "ANCESTRAL_SPADE", "amount": 1, "coins": 10000, "cost": [], "tune": True}],
    },
    "newYear": {"_source": "research/05 §7.8; neu-repo BAKER_NPC", "shop": npc_shop("BAKER_NPC")},
    "winter": {
        "_source": "neu-repo EINARY_NPC (North Stars) + SHERRY_NPC (coins); siege TUNE (research/05 §7.7: 5 rounds)",
        "einary": npc_shop("EINARY_NPC"),
        "sherry": npc_shop("SHERRY_NPC"),
        "siege": {"rounds": 5, "breakSeconds": 120, "roundSeconds": 600,
                  "waves": [{"siege_lava_imp": 8, "siege_gift_thief": 3},
                            {"siege_lava_imp": 10, "siege_molten_brute": 2, "siege_gift_thief": 4},
                            {"siege_lava_imp": 12, "siege_molten_brute": 4, "siege_gift_thief": 5},
                            {"siege_lava_imp": 12, "siege_molten_brute": 6, "siege_gift_thief": 6},
                            {"siege_molten_brute": 6, "siege_gift_thief": 6, "siege_lava_behemoth": 1}],
                  "hpPerRound": 0.5, "northStarsPerRound": 1, "redTop": 1, "greenShare": 0.25, "stolenForNoRed": 3},
    },
    "stonks": {"_source": "research/05 §5.3; stock formula TUNE", "minBid": 2000000, "coinsPerStock": 2000000,
               "shop": npc_shop("LIZ_NPC")},
    "carnival": {
        "_source": "wave 5: reference wiki (Fruit Digging / Zombie Shootout / Catch a Fish / Carnival / Doug / Carnival Token): "
                   "7x7 sand, 15 digs, 5 min; 34 fruit + 10 bombs + 5 rum; 100 s shootout and fishing; 5 tickets a game; "
                   "1 token per 100 points; goals 50/100 tokens + 5 tickets. Spawn pacing of zombies/fish/lamps is TUNE.",
        "ticketsPerDay": 25, "gameCost": 5, "digs": 15, "tokensPerPoint": 0.01,
        "digSeconds": 300, "shootSeconds": 100, "fishSeconds": 100,
        "grid": {"DRAGONFRUIT": 1, "MANGO": 10, "COCONUT": 3, "APPLE": 8, "CHERRY": 2, "POMEGRANATE": 4,
                 "DURIAN": 2, "WATERMELON": 4, "BOMB": 10, "RUM": 5},
        "points": {"DRAGONFRUIT": 1200, "DURIAN": 800, "MANGO": 300, "APPLE": 0, "WATERMELON": 100,
                   "POMEGRANATE": 200, "COCONUT": 200, "CHERRY": 200, "BOMB": 0, "RUM": 0},
        "goals": CARNIVAL_GOALS,
        "perks": CARNIVAL_PERKS,
        "shop": [{"result": "CARNIVAL_MASK_BAG", "tokens": 250}, {"result": "ZOMBIE_MASK", "tokens": 500},
                 {"result": "SALMON_MASK", "tokens": 500}, {"result": "ARMADILLO_MASK", "tokens": 1000},
                 {"result": "PARROT_MASK", "tokens": 1000}, {"result": "BEE_MASK", "tokens": 2000},
                 {"result": "FROG_MASK", "tokens": 2000}],
    },
}
# Map-typed tables must hold only entries (Gson reads them as Map<String, Bag>): move notes out.
events["_notes"] = {"gifts": events["gifts"].pop("_source"), "jerryBoxes": events["jerryBoxes"].pop("_source"),
                    "carnival": events["carnival"].pop("_source"), "myth": events["myth"].pop("_source")}
json.dump(events, open(os.path.join(OUT, "events.json"), "w", encoding="utf8"), indent=1, ensure_ascii=False)

# ---------------------------------------------------------------------------------------------
# NPCs + places renamed (used in Java; recorded here so the names doc is complete).
# ---------------------------------------------------------------------------------------------
for ident, ref, ours in [
    ("npc mayors_election", "Election Official (booth)", "Ballot Clerk Tamsin"),
    ("npc mayors_dark_auction", "Sirius", "Ashgrave"),
    ("npc mayors_fear_mongerer", "Fear Mongerer", "Dread Peddler"),
    ("npc mayors_baker", "Baker", "Baker Haverly"),
    ("npc mayors_zoo", "Oringo", "Zebulon the Keeper"),
    ("npc mayors_mythology", "Diana (as shopkeeper)", "Huntress Orla"),
    ("npc mayors_stonks", "Richard / Liz", "Broker Penhallow"),
    ("npc mayors_jerry", "St. Jerry / Jerry", "Saint Gerald"),
    ("npc mayors_einary", "Einary", "Ingvar"),
    ("npc mayors_sherry", "Sherry", "Marnie"),
    ("place jerrys_workshop", "Jerry's Workshop / Winter Island", "the Winter Workshop"),
    ("place jerry_pond", "Jerry Pond", "Gerald's Pond"),
    ("place mount_jerry", "Mount Jerry", "Mount Gerald"),
    ("place einary_emporium", "Einary's Emporium", "Ingvar's Emporium"),
    ("event season_of_jerry", "Season of Jerry", "Season of Gerald"),
    ("event darker_auction", "Darker Auction (Scorpius)", "Shadow Auction"),
    ("mechanic griffin_burrow", "Griffin Burrow", "Griffin Burrow (kept: generic myth)"),
    ("npc mayors_carnival_leader", "Carnival Leader", "Ringmaster Fenella"),
    ("npc mayors_carnival_pirate", "Carnival Pirateman (Fruit Digging)", "Captain Barnacle"),
    ("npc mayors_carnival_tokens", "Doug (Carnival token shop)", "Token Trader Fitz"),
    ("npc mayors_carnival_cowboy", "Carnival Cowboy (Zombie Shootout)", "Sheriff Dusty"),
    ("mechanic zombie_shootout", "Zombie Shootout", "Zombie Shootout (kept: generic)"),
    ("npc mayors_carnival_fisher", "Carnival Fisherman (Catch a Fish)", "Old Gill"),
    ("mechanic catch_a_fish", "Catch a Fish", "Catch a Fish (kept: generic)"),
    ("place glacial_cave", "Glacial Cave", "Glacial Cave (kept: generic)"),
    ("event carnival", "The Carnival (Foxy)", "The Carnival (kept: generic)"),
    ("mechanic fruit_digging", "Fruit Digging", "Fruit Digging (kept: generic)"),
    ("npc mayors_carnival_goals", "Chantelle (Carnival goals)", "Scorekeeper Odette"),
    ("place carnival_grounds", "The Carnival (Hub carnival grounds)", "the Carnival Grounds (its own island, warp carnival)"),
    ("item MYTH_VARIANTS", "Blessed / Stalwart / Venerable / Exalted / Empyrean (creature variants)", "kept: generic adjectives"),
]:
    kind, i = ident.split(" ", 1)
    name(kind, i, ref, ours)

with open(NAMES_DOC, "w", encoding="utf8") as f:
    f.write("# mayors — renames\n\n")
    f.write("Generated by `tools/gen/mayors/gen_mayors.py` (edit the script, not this file). Internal ids keep the\n"
            "reference id; only display names change. Generic words (Candy, Gift, Spooky Festival, Dark Auction,\n"
            "Traveling Zoo, New Year Cake, Griffin, Minotaur, Mining Fiesta, Fishing Festival) are kept.\n\n")
    f.write("| kind | id | reference name | our name |\n|---|---|---|---|\n")
    for kind, ident, ref, ours in NAMES:
        f.write(f"| {kind} | {ident} | {ref} | {ours} |\n")

print("mayors:", len(MAYORS), "perks:", sum(len(m["perks"]) for m in MAYORS), "items:", len(OUR_ITEMS),
      "mobs:", len(MOBS), "spawns:", len(SPAWNS), "renames:", len(NAMES))
print("zoo offers:", len(events["zoo"]["offers"]), "spooky shop:", len(events["spooky"]["shop"]),
      "einary:", len(events["winter"]["einary"]), "sherry:", len(events["winter"]["sherry"]),
      "gift drops:", {k: len(v["drops"]) for k, v in events["gifts"].items() if not k.startswith("_")})
