# -*- coding: utf-8 -*-
"""
Hand-written layer of the weapons module data: OUR names, OUR descriptions, ability parameters and
the behaviour traits ("w") the Java code reads from ItemDef.extra.w.

Every description is rewritten in our own words; every number is the reference game's (or marked
TUNE in the Java dial that owns it). gen_weapons.py merges this onto research/data/items.json.

Trait keys (ItemDef.extra.w) understood by com.skyclone.mod.weapons.Trait:
  vs        {mobType: +pct}       additive damage % against a mob type (melee + arrows)
  vsMult    {mobType: x}          true multiplier against a mob type
  wisdomVs  {mobType: pct}        +% combat XP from kills of that type
  lessFrom  {mobType: pct}        take pct% less damage from that type while held
  melee     pct                   flat additive % on every melee/arrow hit (Sharpness X joke sword)
  mult      x                     every hit multiplied (Savanna Bow)
  healHit   hp                    heal per melee hit
  water     pct                   additive % while the attacker stands in water
  lavaStr   n                     +n Strength against mobs standing in lava
  miningIslands pct               additive % on mining islands
  spooky    pct                   additive % vs spooky mobs during the spooky festival
  ignite    seconds               set the victim alight: burns for weapons.ignite-percent-per-second of the hit each second
  poisonStr n                     +n Strength while the wielder is poisoned
  cleave    radius                part of every melee hit splashes to nearby mobs
  shortbow  seconds               fires on click with this base shot cooldown
  arrows    n                     arrows per shot (extra ones deal extraPct and may home)
  extraPct  fraction              damage of the extra arrows
  homing    bool                  extra arrows home on targets
  volley    n                     arrows per shot fanned out at full damage (Venom's Touch)
  multiHit  n                     each arrow hits n mobs around its target (Juju)
  endermen  bool                  arrows can hurt endermen (they dodge everyone else's)
  critDiv   n                     crit chance divided by n for this bow's arrows
  alwaysCrit bool                 every hit crits
  infusion  {item, mult, vs?}     consumes one item per shot for a multiplier (vs = only that type)
  explosive radius                arrow explodes for full damage on every mob in radius
  bounce    chance                arrow may bounce to one more target
  mark      seconds               arrow hits are stored and burst after `seconds` (+10%)
  aura      radius                arrow damages everything near its path instead of impact
  pierce    n                     arrows pass through n mobs
  speed     x                     arrow velocity multiplier
  dragonPct pct                   additive % vs dragons
  killsDamage {per, max}          +1 damage per `per` kills with this item (kills tracked on the item)
  raider    bool                  Raider Sword counters
  tactician bool                  +15 damage per Combat collection at tier VII+
  emerald   bool                  purse-scaled damage
  greed     "sword"|"staff"       Dark Auction price-scaled bonus
  shaman    hpPer                 +1 damage per hpPer max health
  pooch     perLevel             +perLevel damage per Wolf Slayer level (+ pack set bonus)
  voidSword str                   +str Strength per Ender armor piece
  daedalus  perLevel              +perLevel damage per Taming level
  vengeance bool                  Burning Vengeance: repeat the last 8 hits on a blaze-slayer foe once the shield changed
  wraith    bool                  Wraith Drain: heal 200 + 4% max HP once the shield changed
  fear      bool                  +1 Damage and +1 Strength per Fear (5 per Great Spook armor piece)
  souls     n                     Raise Souls: souls the blade can hold
  petCopy   bool                  the held weapon grants the active pet's stats again (Daedalus)
  missingVsUndead pct             +pct% vs undead per 1% of your missing health
  missingSelf pct                 +pct% vs undead per 1% missing health (Bone Reaver)
  targetMissing pct               +pct% per 1% of the TARGET's missing health
  defShred  {pct, stacks}         each hit lowers the target's defense
  hurricane bool                  Tempest kill milestones add arrows
  animalDrops {entity: {ITEM: n}} extra drops from farm animals
  cataStats {STAT: perLevel}      stats per Catacombs level (Damage uses key DAMAGE)
  witherPct pct                   additive % vs wither mobs (same as vs.wither, kept for clarity)
  mfVs      {mobType: mf}         +mf Magic Find on drops of that type (core's per-target Magic Find hook)
  mythBestiary {dmg, mf}          per Mythological Bestiary tier (all families summed): +dmg% damage and +mf Magic Find vs mythic foes
  classAbility bool               right-click inside a dungeon uses your dungeon class ability (no wither scrolls bound)
  adaptive  bool                  Adaptive Blade: per-dungeon-class bonuses inside dungeons
  whip      mobType               +1 Strength per kill of that type with it during the current dungeon run
  setStr    {setId: n}            +n Strength while wearing that full armour set
  sting     bool                  Stinger Bow: arrows on dungeon teammates buff them (100 mana)
  witherSkull bool                arrows look like wither skulls (pair with explosive)
  dungeonHeal hp                  heal on hit inside dungeons
"""

# ---------------------------------------------------------------------------------------------
# helpers

def A(aid, name, trigger="RIGHT_CLICK", mana=0, cd=0, dmg=None, scaling=None, lore=()):
    d = {"id": aid, "name": name, "trigger": trigger, "mana": mana, "cooldown": cd, "lore": list(lore)}
    if dmg is not None:
        d["damage"] = dmg
    if scaling is not None:
        d["scaling"] = scaling
    return d


def S(name=None, lore=(), abil=(), w=None, tags=(), extra=None, material=None, category=None):
    d = {"lore": list(lore), "abil": list(abil), "w": dict(w or {}), "tags": list(tags)}
    if name:
        d["name"] = name
    if extra:
        d["extra"] = extra
    if material:
        d["material"] = material
    if category:
        d["category"] = category
    return d


TYPE_WORD = {
    "undead": "&cundead", "arthropod": "&cspider-kin", "ender": "&5End-dwelling", "enderman": "&5Enderman",
    "wither": "&8withered", "skeletal": "&fskeletal", "infernal": "&6infernal", "animal": "&2beast",
    "wolf": "&fwolf", "sea_creature": "&3sea-creature", "spooky": "&6spooky", "mythological": "&2mythic",
    "dragon": "&5dragon", "egg_sac": "&fegg-sac", "slime": "&aslime", "squid": "&3squid and guardian",
}

# ---------------------------------------------------------------------------------------------
# the table

SPEC = {}

# ---- starter / vanilla swords ------------------------------------------------------------------
for vid in ("WOOD_SWORD", "STONE_SWORD", "IRON_SWORD", "GOLD_SWORD", "DIAMOND_SWORD", "BOW"):
    SPEC[vid] = S(tags=["vanilla"])
SPEC["ARROW"] = S(lore=["&7A plain flint-headed arrow.", "&7Its damage is added to the bow's when shot."], tags=["vanilla"])

SPEC["ROGUE_SWORD"] = S(abil=[A("speed_boost", "Quickstep", mana=41, cd=5,
                                lore=["&7Gain &f+100✦ Speed &7for &a30s&7."])])
SPEC["UNDEAD_SWORD"] = S(w={"vs": {"undead": 100}})
SPEC["SPIDER_SWORD"] = S(w={"vs": {"arthropod": 100}}, lore=["&8Woven with spider silk."])
SPEC["END_SWORD"] = S(w={"vs": {"ender": 100}})
SPEC["WITHER_BOW"] = S(w={"vs": {"wither": 100}})
SPEC["FANCY_SWORD"] = S(lore=["&6Passive: Jeweller's Edge", "&7Hits land &a+150% &7harder while you", "&7stand on a mining island."],
                        w={"miningIslands": 150})
SPEC["FLAMING_SWORD"] = S(lore=["&7Each hit sets your target alight", "&7for &a3s&7."], w={"ignite": 3})
SPEC["PRISMARINE_BLADE"] = S(lore=["&7While you are in water this blade", "&7hits &a+200% &7harder."], w={"water": 200})
SPEC["HUNTER_KNIFE"] = S(lore=["&7A light skinning knife favoured", "&7by trappers."])
SPEC["SQUIRE_SWORD"] = S(lore=["&7Standard issue for new recruits."])
SPEC["MERCENARY_AXE"] = S(lore=["&7Paid for in coin, swung for coin."])
SPEC["HARVESTING_SWORD"] = S(lore=["&7A farmhand's blade, keen enough", "&7to double as a sickle."])
SPEC["VENOMOUS_SWORD"] = S(lore=["&7Its edge still glistens with venom."])
SPEC["ARACK"] = S(name="Venomspine",
                  lore=["&6Passive: Toxic Rush", "&7While you are poisoned you gain", "&c+100❁ Strength&7."],
                  w={"poisonStr": 100})
SPEC["TACTICIAN_SWORD"] = S(lore=["&7Gains &c+15 &7Damage for every", "&7Combat collection you have pushed", "&7to tier &eVII &7or beyond &8(11 in total)&7."],
                            w={"tactician": True})
SPEC["RAIDER_AXE"] = S(lore=["&7Monster kills pay &6+20 coins &7once", "&7you reach &aCombat 10&7.",
                             "&7Gains &c+1 &7Damage per &a500 &7kills", "&8(up to +35)&7.",
                             "&7Gains &c+1❁ Strength &7per &a500 &7logs in", "&7your wood collections &8(up to +100)&7."],
                       w={"raider": True})
SPEC["EMERALD_BLADE"] = S(lore=["&7Cut from solid emerald, this blade", "&7grows sharper the heavier your",
                                "&7purse &8(counted up to 2B coins)&7.",
                                "&7Striking with it brings the &cMiser's Curse&7:", "&7nothing can soften what you lose", "&7on death."],
                          w={"emerald": True})
SPEC["GOLEM_SWORD"] = S(abil=[A("iron_punch", "Iron Punch", mana=70, cd=3, scaling=0.3,
                                lore=["&7Pound the ground and hit every foe", "&7in a ring around you for &c{damage}", "&7base magic damage."])])
SPEC["CLEAVER"] = S(lore=["&6Passive: Cleave", "&7Each hit also bites monsters within", "&a3 blocks &7for part of its damage."],
                    w={"cleave": 3})
SPEC["SWORD_OF_BAD_HEALTH"] = S(abil=[A("bad_health", "Blood Price", cd=5,
                                        lore=["&7Pay &c100❤ &7and gain &c+5❁ Strength &7for", "&7every &a5% &7of your health you still",
                                              "&7have, for &a5s &8(max +100)&7."])])
SPEC["BLADE_OF_THE_VOLCANO"] = S(lore=["&7Forged deep inside a burning", "&7mountain.", "&7Gain &c+100❁ Strength &7against foes", "&7standing in lava."],
                                 w={"lavaStr": 100})
SPEC["ENRAGER"] = S(abil=[A("enrage", "Provoke", mana=500, cd=20,
                            lore=["&7Draw the anger of every enemy within", "&a10 blocks&7. They deal &a10% &7less damage", "&7to you for &a10s&7."])])
SPEC["RAGNAROCK_AXE"] = S(name="Doomrock Axe",
                          abil=[A("ragnarock", "Doomcall", mana=500, cd=20,
                                  lore=["&7Begin channelling. Go &a3s &7without being", "&7hit and this weapon's &c❁ Strength &7is",
                                        "&7multiplied by &a1.5x &7for &a10s&7."])])
SPEC["GHOUL_BUSTER"] = S(lore=["&6Passive: Fright Night", "&7During the spooky festival, hits on", "&6spooky &7mobs deal &a+100% &7damage."],
                         w={"spooky": 100})
SPEC["GREAT_SPOOK_SWORD"] = S(lore=["&7Gains &c+1 &7Damage and &c+1❁ Strength", "&7for every point of &5Fear &7you have.", "&8Each Great Spook armor piece: +5 Fear."], w={"fear": True})
SPEC["VOID_SWORD"] = S(lore=["&7Gain &c+20❁ Strength &7for each piece", "&7of Ender armor you wear."], w={"voidSword": 20})
SPEC["END_STONE_SWORD"] = S(abil=[A("extreme_focus", "Focus Burn", mana=20,
                                    lore=["&7Only in &5The End&7: burn all your mana.", "&7Every &b100 mana &7burned grants &a1% &7less",
                                          "&7damage taken for &a5s &8(max 20%)", "&7and &a+25% &7to your next hit &8(max +500%)&7."])])
SPEC["INK_WAND"] = S(abil=[A("ink_bomb", "Ink Burst", mana=60, cd=30, scaling=0.3,
                             lore=["&7Lob a bomb of ink that bursts for", "&c{damage} &7damage and blinds whatever", "&7it splashes."])])
SPEC["EMBER_ROD"] = S(abil=[A("fire_blast", "Ember Volley", mana=150, cd=30, dmg=30, scaling=0.3,
                              lore=["&7Spit &a3 &7fireballs in quick succession,", "&7each dealing &c{damage} &7damage."])])
SPEC["FROZEN_SCYTHE"] = S(abil=[A("ice_bolt", "Frost Bolt", mana=50, scaling=0.3,
                                  lore=["&7Fire a bolt of ice for &c{damage}", "&7damage that slows its target for &a5s&7."])])
SPEC["GLACIAL_SCYTHE"] = S(abil=[A("ice_bolt_glacial", "Frost Bolt", mana=75, scaling=0.3,
                                   lore=["&7Fire a bolt of ice for &c{damage}", "&7damage that slows its target for &a5s&7.",
                                         "&7Where it lands it shatters, hitting", "&7everything within &a3 blocks &7for the same."])])
SPEC["YETI_SWORD"] = S(abil=[A("terrain_toss", "Boulder Heave", mana=250, cd=1, scaling=0.3,
                               lore=["&7Rip up a chunk of ground and hurl it", "&7forward for up to &c{damage} &7damage."])])
SPEC["MIDAS_SWORD"] = S(name="Gilded King's Sword",
                        lore=["&6Passive: Avarice", "&7Its bonus &c❁ Strength &7and &cDamage &7grow", "&7with the price paid for it at the",
                              "&7Shadow Auction &8(max +120 at 50M coins)&7."],
                        w={"greed": "sword"})
SPEC["MIDAS_STAFF"] = S(name="Gilded King's Staff",
                        lore=["&6Passive: Avarice", "&7Its bonus ability damage grows with", "&7the price paid for it at the Shadow",
                              "&7Auction &8(max +26,000 at 100M coins)&7."],
                        abil=[A("molten_wave", "Golden Tide", mana=500, cd=1, scaling=0.3, dmg=12000,
                                lore=["&7Send a wave of molten gold rolling", "&7ahead of you for up to &c{damage} &7damage."])],
                        w={"greed": "staff"})
SPEC["JERRY_STAFF"] = S(name="Jester-chine Gun",
                        abil=[A("rapid_fire_jerry", "Rapid Fire", mana=30, scaling=0.2,
                                lore=["&7Fire a bouncing jester round for &c{damage}", "&7damage. The recoil knocks you back.",
                                      "&7Each shot costs &b+30 mana &7more than", "&7the last until you stop for &a4s&7."])])
SPEC["LEAPING_SWORD"] = S(abil=[A("leap", "Pounce", mana=50, cd=1, scaling=0.3,
                                  lore=["&7Spring into the air. On landing deal", "&c{damage} &7damage to nearby foes and", "&7freeze them for &a1s&7."])])
SPEC["SILK_EDGE_SWORD"] = S(abil=[A("leap_silk", "Pounce", mana=50, cd=1, scaling=0.3,
                                    lore=["&7Spring into the air. On landing deal", "&c{damage} &7damage to nearby foes and", "&7freeze them for &a1s&7."])])
SPEC["PIGMAN_SWORD"] = S(abil=[A("burning_souls", "Soul Pyre", mana=400, cd=5, scaling=0.1,
                                 lore=["&7Gain &a+75❈ Defense &7for &a5s &7and loose", "&7a spiral of flame on nearby foes for", "&7up to &c{damage} &7over &a5s&7."])])
SPEC["ASPECT_OF_THE_END"] = S(name="Voidstep Blade",
                              abil=[A("instant_transmission", "Blink Step", mana=50,
                                      lore=["&7Blink &a8 blocks &7forward and gain", "&f+50✦ Speed &7for &a3s&7."])])
SPEC["ASPECT_OF_THE_VOID"] = S(name="Riftwalker Blade",
                               abil=[A("instant_transmission_void", "Blink Step", mana=45,
                                       lore=["&7Blink &a8 blocks &7forward and gain", "&f+50✦ Speed &7for &a3s&7."]),
                                     A("ether_transmission_void", "Phasewarp", trigger="SNEAK_RIGHT_CLICK", mana=180,
                                       lore=["&7Warp onto the block you are looking", "&7at, up to &a57 blocks &7away.",
                                             "&8Needs a Phasewarp Merger applied;", "&8unmerged it blinks instead."])])
SPEC["ETHERWARP_CONDUIT"] = S(name="Phasewarp Conduit",
                              lore=["&7A tuning fork for the space between", "&7two places."],
                              abil=[A("ether_transmission", "Phasewarp", mana=180,
                                      lore=["&7Warp onto the block you are looking", "&7at, up to &a57 blocks &7away."])],
                              category="NONE", extra={"maxStack": 1})
SPEC["ASPECT_OF_THE_DRAGON"] = S(name="Wyrmbreath Blade",
                                 abil=[A("dragon_rage", "Wyrm's Fury", mana=100, scaling=0.1,
                                         lore=["&7Breathe fire on every monster in", "&7front of you for &c{damage} &7damage,", "&7hurling them far back."])])
SPEC["ASPECT_OF_THE_JERRY"] = S(name="Jester's Folly",
                                abil=[A("parley", "Banter", cd=5, lore=["&7Let out your inner jester."])])
SPEC["ASPECT_OF_THE_JERRY_SIGNATURE"] = S(name="Jester's Folly, Signed Edition",
                                          lore=["&9Sharpness X", "&7Melee hits deal &a+50% &7damage."],
                                          abil=[A("parley", "Banter", cd=5, lore=["&7Let out your inner jester."])],
                                          w={"melee": 50})
SPEC["SWORD_OF_REVELATIONS"] = S(w={"vsMult": {"mythological": 1.5}, "mfVs": {"mythological": 5}})
SPEC["DAEDALUS_AXE"] = S(name="Labyrinth Blade",
                         lore=["&7Gains &c+4 &7Damage per &aTaming &7level.", "&7Copies the stats of your active pet.",
                               "&7Grants &c+1% &7Damage and &b+0.2✯ Magic Find", "&7on &2mythic &7foes per Bestiary tier",
                               "&7you have across all of them."],
                         w={"daedalus": 4, "petCopy": True, "mythBestiary": {"dmg": 1, "mf": 0.2}})
SPEC["TRIBAL_SPEAR"] = S(abil=[A("thwack", "Boomerang Throw", cd=1,
                                 lore=["&7Hurl the spear like an arrow; it", "&7strikes for your melee damage and", "&7flies back to your hand."])])
SPEC["HORSEMAN_SCYTHE"] = S(lore=["&7Carried by a headless rider on", "&7the festival's last night."])
SPEC["VOIDWALKER_KATANA"] = S(w={"vs": {"enderman": 150}, "lessFrom": {"enderman": 3}})
SPEC["SOUL_WHIP"] = S(abil=[A("flay", "Lash", cd=0.5,
                              lore=["&7Crack the whip in a wide arc, hitting", "&7everything in its path with your", "&7melee damage."])],
                      w={"vsMult": {"sea_creature": 2}})
SPEC["FLAMING_FLAY"] = S(abil=[A("flay_flaming", "Lash", cd=0.5,
                                 lore=["&7Crack the whip in a wide arc, hitting", "&7everything in its path with your",
                                       "&7melee damage. Heal &c1% ❤ &7for each", "&7sea creature hit &8(max 25%)&7."])],
                         w={"vsMult": {"sea_creature": 3}})
SPEC["ZOMBIE_SWORD"] = S(abil=[A("instant_heal", "Mend Flesh", lore=["&7Heal &c320❤ &7and players within &a7", "&7blocks for &c64❤&7.", "&8Holds 4 charges; one returns every 15s."])])
SPEC["ORNATE_ZOMBIE_SWORD"] = S(abil=[A("instant_heal_ornate", "Mend Flesh", lore=["&7Heal &c420❤ &7and players within &a7", "&7blocks for &c84❤&7.", "&8Holds 5 charges; one returns every 15s."])])
SPEC["FLORID_ZOMBIE_SWORD"] = S(abil=[A("instant_heal_florid", "Mend Flesh", lore=["&7Heal &c500❤ &7and players within &a8", "&7blocks for &c100❤&7.", "&8Holds 5 charges; one returns every 15s."])])

# ---- animal axes (SWORD category in the data) --------------------------------------------------
# drops are VANILLA Material names; the Java side maps them with Sky.items().vanillaId(Material)
_ANIMAL = {
    "CHICKEN": ("Chickens", {"FEATHER": "Feather", "CHICKEN": "Raw Chicken"}), "COW": ("Cows", {"BEEF": "Raw Beef"}),
    "MUSHROOM_COW": ("Mooshrooms", {"RED_MUSHROOM": "Red Mushroom", "LEATHER": "Leather"}), "PIG": ("Pigs", {"PORKCHOP": "Raw Porkchop"}),
    "RABBIT": ("Rabbits", {"RABBIT": "Raw Rabbit", "RABBIT_HIDE": "Rabbit Hide", "RABBIT_FOOT": "Rabbit's Foot"}),
    "SHEEP": ("Sheep", {"WHITE_WOOL": "White Wool", "MUTTON": "Raw Mutton"}),
}
_ENTITY = {"CHICKEN": "CHICKEN", "COW": "COW", "MUSHROOM_COW": "MOOSHROOM", "PIG": "PIG", "RABBIT": "RABBIT", "SHEEP": "SHEEP"}
for key, (plural, drops) in _ANIMAL.items():
    for prefix, n in (("BASIC_", 1), ("", 2)):
        d = {k: n for k in drops}
        names = ", ".join(drops.values())
        SPEC[prefix + key + "_AXE"] = S(lore=["&7%s you kill with this drop &a%d" % (plural, n), "&7extra: &f%s&7." % names],
                                        w={"animalDrops": {_ENTITY[key]: d}})

# ---- wands & staffs ------------------------------------------------------------------------------
SPEC["CELESTE_WAND"] = S(name="Skybolt Wand",
                         abil=[A("lightning_strike", "Skybolt", mana=70, dmg=40, scaling=0.3,
                                 lore=["&7Call lightning down on the nearest", "&7monster within &a10 blocks &7for &c{damage}", "&7damage."])])
SPEC["STARLIGHT_WAND"] = S(abil=[A("starfall", "Starfall", mana=100, cd=2, scaling=0.3,
                                   lore=["&7Rain stars on a &a3 block &7area for &a5s&7,", "&7dealing &c{damage} &7damage each second to", "&7anything inside."])])
SPEC["STAFF_OF_THE_VOLCANO"] = S(abil=[A("volcano_explode", "Eruption", mana=100, cd=30, scaling=0.3,
                                         lore=["&7Pay &c1,000❤ &7to erupt, dealing &c{damage}", "&7damage and setting every mob within",
                                               "&a4 blocks &7on fire."])])
SPEC["FIRE_VEIL_WAND"] = S(abil=[A("fire_veil", "Flame Mantle", mana=300, cd=1, scaling=0.3,
                                   lore=["&7Wrap yourself in fire for &a5s&7. Mobs", "&7inside it take &c{damage} &7damage every", "&7second."])])
SPEC["FIRE_FURY_STAFF"] = S(abil=[A("firestorm", "Firestorm", mana=1000, cd=20, scaling=0.3,
                                    lore=["&7Launch a fire core. Where it lands a", "&7storm rages for &a10s&7, dealing up to",
                                          "&c{damage} &7damage to mobs within &a7 blocks&7."])])
SPEC["FIRE_FREEZE_STAFF"] = S(abil=[A("fire_freeze", "Cold Snap", mana=500, cd=10,
                                      lore=["&7Mark a &a5 block &7circle. After &a5s &7every", "&7mob inside is frozen solid for &a10s&7."])])
SPEC["HELLSTORM_STAFF"] = S(abil=[A("hellstorm", "Reckless Power", mana=100, cd=30,
                                    lore=["&7Gain &c+5๑ Ability Damage &7for &a30s&7,", "&7but take &cdouble &7damage meanwhile."])])
SPEC["WAND_OF_STRENGTH"] = S(abil=[A("life_blood", "Blood Pact", cd=1,
                                     lore=["&7Spend &c10% &7of your max health to give", "&7nearby allies &c+30❁ Strength &7for &a10s&7."])])
SPEC["ALCHEMISTS_STAFF"] = S(abil=[A("coin_conversion", "Transmute", scaling=0.3,
                                     lore=["&7Spend &6100,000 coins &7to cast a bolt", "&7that deals &c{damage} &7damage and knocks", "&7its target away."])],
                             tags=["soulbound"])
SPEC["HOPE_OF_THE_RESISTANCE"] = S(abil=[A("ray_of_hope", "Dawn Ray", mana=0.1, scaling=0.2,
                                           lore=["&7Channel a ray at your target that", "&7explodes for &c{damage} &7damage.",
                                                 "&7Costs &b10% &7of your max mana."])])
SPEC["GENERALS_HOPE_OF_THE_RESISTANCE"] = S(abil=[A("ray_of_hope_general", "Dawn Ray", mana=0.02, scaling=0.2,
                                                    lore=["&7Channel a ray at your target that", "&7explodes for &c{damage} &7damage.",
                                                          "&7Costs &b2% &7of your max mana."])])
SPEC["POLYMORPH_WAND"] = S(lore=["&7Its spell was never finished."])
SPEC["WAND_OF_RECALL"] = S(abil=[A("recall", "Recall", cd=60, lore=["&7Return to your island's home."])])

# ---- bows ------------------------------------------------------------------------------------------
SPEC["DECENT_BOW"] = S(lore=["&7It shoots. Decently."])
SPEC["AIMING_BOW"] = S(lore=["&7Its sights were tuned by a", "&7master marksman."])
SPEC["SAVANA_BOW"] = S(lore=["&7Every hit from this bow deals", "&adouble &7damage."], w={"mult": 2})
SPEC["PRISMARINE_BOW"] = S(lore=["&6Passive: Shard Infusion", "&7Each shot uses a &bPrismarine Shard &7from", "&7your inventory or quiver: the arrow",
                                 "&7can hurt sea creatures and deals &a+300%", "&7to squids and guardians."],
                           w={"infusion": {"item": "PRISMARINE_SHARD", "mult": 4, "vs": "squid"}})
SPEC["ENDER_BOW"] = S(abil=[A("ender_warp", "Pearl Shot", "LEFT_CLICK", mana=50, cd=5,
                              lore=["&7Fire an ender pearl. Where it lands", "&7every monster within &a8 blocks &7loses",
                                    "&a10% &7of its health &8(max 500)&7."])])
SPEC["END_STONE_BOW"] = S(abil=[A("extreme_focus_bow", "Focus Burn", "LEFT_CLICK",
                                  lore=["&7Burn all your mana; your next hit", "&7gains &a+25% &7damage per &b100 &7mana", "&7burned &8(max +500%)&7."])])
SPEC["EXPLOSIVE_BOW"] = S(lore=["&6Passive: Blast Shot", "&7Arrows explode on impact and every", "&7monster in the blast takes the bow's", "&7full damage."],
                          w={"explosive": 3.5})
SPEC["HURRICANE_BOW"] = S(lore=["&6Passive: Gale", "&7Kills with this bow feed its power:", "&a20&7: 2 arrows  &a50&7: 3 arrows",
                                "&a100&7: 4 arrows  &a250&7: 5 arrows"],
                          w={"hurricane": True})
SPEC["MAGMA_BOW"] = S(lore=["&6Passive: Cream Infusion", "&7Each shot uses &6Magma Cream &7from your", "&7inventory or quiver to deal &adouble", "&7damage."],
                      w={"infusion": {"item": "MAGMA_CREAM", "mult": 2}})
SPEC["SULPHUR_BOW"] = S(lore=["&6Passive: Powder Infusion", "&7Each shot uses &7Gunpowder &7from your", "&7inventory or quiver to deal &adouble", "&7damage."],
                        w={"infusion": {"item": "SULPHUR", "mult": 2}})
SPEC["SLIME_BOW"] = S(lore=["&6Passive: Gel Infusion", "&7Each shot uses a &aSlimeball &7from your", "&7inventory or quiver to deal &a5x &7damage",
                            "&7to slimes and magma cubes."],
                      w={"infusion": {"item": "SLIME_BALL", "mult": 5, "vs": "slime"}})
SPEC["RUNAANS_BOW"] = S(name="Trinity Bow",
                        lore=["&6Passive: Three-fold", "&7Every shot looses &a3 &7arrows. The two", "&7extra arrows deal &a40% &7damage and",
                              "&7seek out targets."],
                        w={"arrows": 3, "extraPct": 0.4, "homing": True})
SPEC["ARTISANAL_SHORTBOW"] = S(w={"shortbow": 0.5})
SPEC["DRAGON_SHORTBOW"] = S(lore=["&6Passive: Wyrm Stalker", "&7Deal &a+15% &7damage to dragons; arrows", "&7curve toward dragons within &a10 blocks&7."],
                            w={"shortbow": 0.5, "dragonPct": 15}, tags=["soulbound"])
SPEC["SOULS_REBOUND"] = S(lore=["&7Arrows brand what they hit for &a5s&7.", "&7Branded foes take nothing at first;", "&7when the brand fades they take every",
                                "&7bit of it at once, &a+10%&7."],
                          w={"mark": 5})
SPEC["SPIDER_QUEENS_STINGER"] = S(lore=["&7Arrows carry a venom cloud that hits", "&7everything near their path for the", "&7bow's damage, and fly straight",
                                        "&7through enemies."],
                                  w={"aura": 2.0, "pierce": 5})
SPEC["VENOMS_TOUCH"] = S(lore=["&7Looses a fan of &a5 &7arrows at once.", "&7Struck foes are poisoned for &a3s&7."],
                         w={"volley": 5})

# ---- arrows ----------------------------------------------------------------------------------------
_ARROW_TXT = {
    "REINFORCED_IRON_ARROW": ["&7An iron-banded shaft."],
    "GOLD_TIPPED_ARROW": ["&7Tipped in gold for a cleaner bite."],
    "REDSTONE_TIPPED_ARROW": ["&7Its tip hums with redstone."],
    "EMERALD_TIPPED_ARROW": ["&7A gem-sharp emerald point."],
    "MAGMA_ARROW": ["&7Still smouldering from the forge."],
    "ARMORSHRED_ARROW": ["&6Passive: Plate Splitter", "&7Hits ignore &a5% &7of the target's", "&a❈ Defense&7."],
    "BOUNCY_ARROW": ["&6Passive: Ricochet", "&7Hits have a &a50% &7chance to bounce on", "&7to another target, up to &a4 &7times,", "&7losing &c15% &7damage each bounce."],
    "EXPLOSIVE_ARROW": ["&6Passive: Charged Tip", "&7Explodes on impact or after &a2s&7. The", "&7blast splits the arrow's damage among",
                        "&7mobs within &a2.5 blocks&7.", "&cStanding in it costs you 10% ❤."],
    "GLUE_ARROW": ["&6Passive: Gummed Up", "&7Victims cannot walk for &a1.5s&7.", "&8Each victim can be glued once per 6s."],
    "ICY_ARROW": ["&6Passive: Numbing Cold", "&7Targets are slowed &8(Slowness I) &7for &a3s&7."],
    "NANSORB_ARROW": ["&6Passive: Skewer", "&7When an arrow pierces, each further", "&7victim takes &a+7.5% &7more than the last", "&8(up to 5 victims)&7."],
}
for aid, lines in _ARROW_TXT.items():
    SPEC[aid] = S(lore=lines + ["&8Damage is added to the bow's when shot."])
SPEC["NANSORB_ARROW"]["name"] = "Skewer Arrow"
SPEC["TOXIC_ARROW_POISON"] = S(lore=["&7Used up as you shoot.", "&7Arrows deal an extra &a10% &7of their", "&7damage as poison."])
SPEC["TWILIGHT_ARROW_POISON"] = S(name="Dusk Arrow Poison",
                                  lore=["&7Used up as you shoot.", "&7Struck foes take &a1.1x &7damage from", "&7everything for &a20s&7."])

SPEC["GRAPPLING_HOOK"] = S(lore=["&7Cast it into a wall or the ground and", "&7reel in to fling yourself after it.", "&8Two second cooldown."],
                           tags=["grappling_hook", "throwable"], category="NONE", extra={"maxStack": 1})

# =====================================================================================================
# SLAYER-owned weapons: registered only if the slayer module did not register them (postEnable).
# =====================================================================================================
SPEC["REVENANT_SWORD"] = S(w={"vs": {"undead": 150}})
SPEC["REAPER_SWORD"] = S(lore=["&7Heal &c10❤ &7per hit."], w={"vs": {"undead": 200}, "healHit": 10, "wisdomVs": {"undead": 45}})
SPEC["AXE_OF_THE_SHREDDED"] = S(lore=["&7Heal &c25❤ &7per hit."], w={"vs": {"undead": 250}, "healHit": 25, "wisdomVs": {"undead": 45}},
                                abil=[A("halberd_throw", "Hurl", mana=20,
                                        lore=["&7Throw the halberd through everything in", "&7its path for &a10% &7of your melee damage.",
                                              "&7Each throw in a row costs &b2x &7the mana", "&7and hits &a2x &7as hard &8(max 16x)&7."])])
SPEC["REAPER_SCYTHE"] = S(lore=[], abil=[A("raise_souls", "Raise Souls", cd=1,
                                lore=["&7Monsters you slay with this blade", "&7leave their soul behind. Right-click", "&7a soul to absorb it, then right-click", "&7to raise your souls to fight for you.", "&8Mana depends on the monsters' power.", "&8Sneak right-click to release souls.", "&8A raised soul that dies is lost.", "&8Max Souls: &a3"])], w={"souls": 3})
SPEC["RECLUSE_FANG"] = S(lore=["&6Passive: Silk Cutter", "&7Cuts through the webs a spider boss", "&7spins."], w={"vs": {"arthropod": 150}})
SPEC["TARANTULA_FANG"] = S(lore=["&6Passive: Silk Cutter", "&7Cuts through the webs a spider boss", "&7spins."],
                           w={"vs": {"arthropod": 200}, "wisdomVs": {"arthropod": 15}})
SPEC["SCORPION_FOIL"] = S(lore=["&6Passive: Silk Cutter", "&7Cuts through the webs a spider boss", "&7spins."],
                          w={"vs": {"arthropod": 250}, "wisdomVs": {"arthropod": 30}})
SPEC["STING"] = S(lore=["&6Passive: Silk Cutter", "&7Cuts through the webs a spider boss", "&7spins.", "&6Passive: Needlepoint", "&7Every hit is a critical hit."],
                  w={"vs": {"arthropod": 300}, "wisdomVs": {"arthropod": 45}, "alwaysCrit": True})
SPEC["SHAMAN_SWORD"] = S(lore=["&7Gains &c+1 &7Damage per &c50❤ &7max health."],
                         w={"shaman": 50, "lessFrom": {"animal": 20}, "wisdomVs": {"animal": 30}})
SPEC["POOCH_SWORD"] = S(lore=["&7Gains &c+20 &7Damage per Wolf Slayer level.",
                              "&6Passive: Pack Instinct", "&7Wearing a full pack-hunter or mastiff", "&7set adds another &a+100% &7against wolves."],
                        w={"pooch": 20, "vs": {"wolf": 200}, "lessFrom": {"animal": 20}, "wisdomVs": {"animal": 45}})
SPEC["EDIBLE_MACE"] = S(abil=[A("me_smash_head", "Big Bonk", mana=100,
                                lore=["&7Your next hit deals &adouble &7damage and", "&7leaves beasts dealing &c35% &7less damage", "&7for &a30s&7. Does not stack."])])
SPEC["VOIDEDGE_KATANA"] = S(w={"vs": {"enderman": 200}, "lessFrom": {"enderman": 6}, "wisdomVs": {"enderman": 15}},
                            abil=[A("soulcry", "Soul Howl", mana=200, cd=4, lore=["&7Gain &c+200⫽ Ferocity &7against", "&5Endermen &7for &a4s&7."])])
SPEC["VORPAL_KATANA"] = S(w={"vs": {"enderman": 250}, "lessFrom": {"enderman": 9}, "wisdomVs": {"enderman": 30}},
                          abil=[A("soulcry_vorpal", "Soul Howl", mana=200, cd=4, lore=["&7Gain &c+300⫽ Ferocity &7against", "&5Endermen &7for &a4s&7."])])
SPEC["ATOMSPLIT_KATANA"] = S(name="Fissionblade Katana", w={"vs": {"enderman": 300}, "lessFrom": {"enderman": 12}, "wisdomVs": {"enderman": 45}},
                             abil=[A("soulcry_atomsplit", "Soul Howl", mana=200, cd=4, lore=["&7Gain &c+400⫽ Ferocity &7against", "&5Endermen &7for &a4s&7."])])
SPEC["SINSEEKER_SCYTHE"] = S(name="Sinhunter Scythe",
                             abil=[A("sinrecall", "Sin Recall", mana=260,
                                     lore=["&7Zip &a4 blocks &7forward; every mob you", "&7pass takes a melee hit. Recast within",
                                           "&a1s &7to go again &8(1.5x mana)&7, or you", "&7snap back to where you started."])])
SPEC["GYROKINETIC_WAND"] = S(abil=[A("gravity_storm", "Gravity Well", "LEFT_CLICK", mana=1200, cd=30,
                                     lore=["&7Open a rift where you aim that drags", "&7every nearby mob into one spot."]),
                                   A("cells_alignment", "Cell Sync", "RIGHT_CLICK", mana=220, cd=10,
                                     lore=["&7For &a6s &7incoming hits are softened:", "&7you take only a third of each hit."])])
_DAGGERS = {
    "FIREDUST_DAGGER": ("Cinderdust Dagger", {"infernal": 1.2, "wither": 1.1}, 15, "ashen"),
    "MAWDUST_DAGGER": ("Dusklight Dagger", {"infernal": 1.5, "skeletal": 1.2}, 15, "spirit"),
    "BURSTFIRE_DAGGER": ("Emberbane Dagger", {"infernal": 1.5, "wither": 1.2}, 30, "ashen"),
    "BURSTMAW_DAGGER": ("Gloomdredge Dagger", {"infernal": 2.5, "skeletal": 1.5}, 30, "spirit"),
    "HEARTFIRE_DAGGER": ("Pyreruin Dagger", {"infernal": 2.0, "wither": 1.5}, 45, "ashen"),
    "HEARTMAW_DAGGER": ("Soulripper Dagger", {"infernal": 3.5, "skeletal": 2.0}, 45, "spirit"),
}
for did, (dname, mult, wis, mode) in _DAGGERS.items():
    pair = "Ashen and Auric" if mode == "ashen" else "Spirit and Crystal"
    if mode == "ashen":
        follow = ["&6Ability: Burning Vengeance", "&7When a demon's shield changes colour", "&7within &a6s &7of your hit, your last",
                  "&a8 &7hits on it land again."]
        wflag = {"vengeance": True}
    else:
        follow = ["&6Ability: Wraith Drain", "&7When a demon's shield changes colour", "&7within &a6s &7of your hit, heal",
                  "&c200❤ &7+ &c4% &7of your max Health."]
        wflag = {"wraith": True}
    SPEC[did] = S(name=dname, w=dict({"vsMult": mult, "wisdomVs": {"infernal": wis}}, **wflag), lore=follow,
                  abil=[A("attunement", "Attune", lore=["&7Switch between %s modes" % pair, "&7to cut through a demon's shield."])],
                  extra={"attunement": mode})
for wid, (nm, hps, sec) in {"WAND_OF_HEALING": ("Small Heal", 60, 5), "WAND_OF_MENDING": ("Medium Heal", 80, 5),
                            "WAND_OF_RESTORATION": ("Big Heal", 100, 6), "WAND_OF_ATONEMENT": ("Huge Heal", 120, 6)}.items():
    SPEC[wid] = S(abil=[A("wand_heal_" + wid.split("_")[-1].lower(), nm, cd=1,
                          lore=["&7Heal &c%d❤ &7every second for &a%ds&7." % (hps, sec), "&8Wand heals do not stack."])],
                  extra={"healPerSecond": hps, "healSeconds": sec})
SPEC["SCORPION_BOW"] = S(lore=["&6Passive: Egg Breaker", "&7Arrows deal &a2x &7damage to a spider", "&7boss's egg sacs."],
                         w={"shortbow": 0.5, "vsMult": {"egg_sac": 2}})
SPEC["MOSQUITO_BOW"] = S(name="Bloodsucker Shortbow",
                         lore=["&6Passive: Egg Breaker", "&7Arrows deal &a3x &7damage to a spider", "&7boss's egg sacs."],
                         w={"shortbow": 0.5, "vsMult": {"egg_sac": 3}},
                         abil=[A("nasty_bite", "Leech Shot", "LEFT_CLICK", cd=0.5,
                                 lore=["&7Fire a charged arrow that heals you", "&7for &c189❤ &7when it hits."])])
SPEC["JUJU_SHORTBOW"] = S(name="Hexbound Shortbow",
                          lore=["&7Each arrow strikes &a3 &7mobs where it", "&7lands, and can hurt &5Endermen&7."],
                          w={"shortbow": 0.5, "multiHit": 3, "endermen": True})
SPEC["TERMINATOR"] = S(name="Oblivion Shortbow",
                       lore=["&7Looses &a3 &7arrows at once and can hurt", "&5Endermen&7.", "&cYour ☣ Crit Chance is divided by 4!"],
                       w={"shortbow": 0.5, "arrows": 3, "extraPct": 1.0, "endermen": True, "critDiv": 4},
                       abil=[A("salvation", "Judgement Beam", "LEFT_CLICK",
                               lore=["&7After &a3 &7arrow hits, left click to fire", "&7a beam through up to &a5 &7enemies.", "&7The beam always crits."])])

# =====================================================================================================
# DUNGEON drops: registered only if the dungeons module did not register them (postEnable).
# =====================================================================================================
SPEC["BAT_WAND"] = S(abil=[A("guided_bat", "Spirit Bat", mana=200, scaling=0.2,
                             lore=["&7Release a spirit bat that follows your", "&7gaze and bursts for &c{damage} &7damage."])])
SPEC["ICE_SPRAY_WAND"] = S(abil=[A("ice_spray", "Frost Breath", mana=50, cd=5, scaling=0.1,
                                   lore=["&7Spray a cone of ice for &c{damage} &7damage.", "&7Struck mobs freeze for &a5s &7and take",
                                         "&a10% &7more damage while frozen."])])
SPEC["BONZO_STAFF"] = S(name="Balloonist's Staff",
                        abil=[A("showtime", "Pop Show", mana=90, scaling=0.2,
                                lore=["&7Launch a balloon that bursts on impact", "&7for up to &c{damage} &7damage."])])
SPEC["GIANTS_SWORD"] = S(abil=[A("giants_slam", "Titan Slam", mana=100, cd=30, scaling=0.05,
                                 lore=["&7Drive the blade into the ground,", "&7dealing &c{damage} &7damage to everything", "&7near you."])])
SPEC["GIANTS_EYE_SWORD"] = S(abil=[A("eye_beam", "Eye Ray", mana=40, cd=5, scaling=0.05, dmg=4000,
                                     lore=["&7Fire a searing ray ahead of you for", "&c{damage} &7damage."])])
SPEC["CRYPT_DREADLORD_SWORD"] = S(abil=[A("dreadlord", "Grave Skull", mana=40, scaling=0.3, dmg=504,
                                          lore=["&7Fire a skull that deals &c{damage} &7damage."])])
SPEC["CRYPT_WITHERLORD_SWORD"] = S(abil=[A("witherlord", "Grave Skulls", mana=40, cd=3, scaling=0.3, dmg=304.5,
                                           lore=["&7Fire &a3 &7skulls, each dealing &c{damage}", "&7damage."])])
SPEC["SHADOW_FURY"] = S(abil=[A("shadow_fury", "Shadow Dance", cd=15,
                                lore=["&7Flash between up to &a5 &7enemies within", "&a12 blocks&7, rooting each and striking", "&7it as you pass."])])
SPEC["SILENT_DEATH"] = S(abil=[A("shadowstep", "Backstab Step", cd=60,
                                 lore=["&7Appear behind the enemy you look at", "&8(20 blocks max) &7and gain &c+25❁ Strength", "&7for &a10s&7. Kills reset the cooldown."])])
SPEC["LIVID_DAGGER"] = S(name="Mimic Dagger", lore=["&7Critical hits from behind deal", "&a+100% &7damage."],
                         abil=[A("livid_throw", "Knife Toss", mana=150, cd=5, lore=["&7Throw the dagger at whatever you aim", "&7at, striking it with your melee damage."])])
for cid, r in (("SUPER_CLEAVER", 3), ("HYPER_CLEAVER", 4), ("GIANT_CLEAVER", 5)):
    SPEC[cid] = S(lore=["&6Passive: Cleave", "&7Each hit also bites monsters within", "&a%d blocks &7for part of its damage." % r], w={"cleave": r})
SPEC["BONE_REAVER"] = S(lore=["&7Gains &a+1% &7damage to undead per &a1%", "&7of your missing health."], w={"missingSelf": 1},
                        abil=[A("reaving_strike", "Reaping Arc", cd=1, lore=["&7Swing in a huge arc for &a125% &7of your", "&7melee damage to everything it hits."])])
SPEC["FELTHORN_REAPER"] = S(lore=["&7Gains &a+1% &7damage per &a1% &7of the", "&7target's missing health."], w={"targetMissing": 1},
                            abil=[A("reaving_strike_fel", "Reaping Arc", cd=1, lore=["&7Swing in a huge arc for &a135% &7of your", "&7melee damage to everything it hits."])])
SPEC["SPIRIT_SWORD"] = S(lore=["&7Gains &a+2% &7damage to undead per &a1%", "&7of your missing health."], w={"missingVsUndead": 2})
SPEC["ITEM_SPIRIT_BOW"] = S(lore=["&7Gains &a+2% &7damage to undead per &a1%", "&7of your missing health."], w={"missingVsUndead": 2, "shortbow": 0.75})
SPEC["BONE_BOOMERANG"] = S(abil=[A("bonemerang", "Bone Toss", cd=1,
                                   lore=["&7Throw the bone a short way, hitting for", "&7arrow damage and piercing up to &a10&7.",
                                         "&7It hits &a2x &7as hard on the way back."])])
SPEC["DEATH_BOW"] = S(lore=["&7Arrows have a &a50% &7chance to bounce", "&7on to another target."], w={"vs": {"undead": 100}, "bounce": 0.5})
SPEC["SUPER_UNDEAD_BOW"] = S(lore=["&7Arrows have a &a25% &7chance to bounce", "&7on to another target."], w={"vs": {"undead": 100}, "bounce": 0.25})
SPEC["UNDEAD_BOW"] = S(w={"vs": {"undead": 100}})
SPEC["LAST_BREATH"] = S(lore=["&7Each hit strips &a10% &7of the target's", "&7max &a❈ Defense&7, stacking &a5 &7times."], w={"defShred": {"pct": 10, "stacks": 5}})
SPEC["MACHINE_GUN_BOW"] = S(w={"shortbow": 0.5},
                            abil=[A("rapid_fire", "Bullet Storm", cd=100,
                                    lore=["&7Fire &a5 &7arrows a second for &a8s&7. These", "&7arrows deal &a70% &7damage."])])
SPEC["SNIPER_BOW"] = S(lore=["&7Arrows fly much farther and faster."], w={"speed": 1.8})
SPEC["WITHER_CLOAK"] = S(abil=[A("creeper_veil", "Ward Veil", cd=10,
                                 lore=["&7For &a10s &7a layered ward soaks up to", "&c1,000 &7damage per layer. You cannot", "&7attack while it holds."])])
SPEC["FLOWER_OF_TRUTH"] = S(abil=[A("heat_seeking_rose", "Thorn Seeker", cd=1, dmg=0,
                                    lore=["&7Pay &c100❤ &7to fire a rose that hops", "&7between up to &a3 &7foes with your melee", "&7damage, hitting harder each hop."])])
SPEC["BOUQUET_OF_LIES"] = S(abil=[A("petal_barrage", "Petal Storm", cd=1,
                                    lore=["&7Pay &c250❤ &7to fire &a3 &7roses that hop", "&7between up to &a5 &7foes, &a+2% &7damage",
                                          "&7per &a10% &7of your missing health."])])
SPEC["NECROMANCER_SWORD"] = S(lore=[], abil=[A("raise_souls", "Raise Souls", cd=1,
                                lore=["&7Monsters you slay with this blade", "&7leave their soul behind. Right-click", "&7a soul to absorb it, then right-click", "&7to raise your souls to fight for you.", "&8Mana depends on the monsters' power.", "&8Sneak right-click to release souls.", "&8A raised soul that dies is lost.", "&8Max Souls: &a2"])], w={"souls": 2})
SPEC["ZOMBIE_SOLDIER_CUTLASS"] = S(lore=["&7Inside a dungeon each hit heals", "&7you &c10❤&7."], w={"dungeonHeal": 10})
CLASS_ABILITY_LINE = "&eRight-click to use your class ability!"
SPEC["FEL_SWORD"] = S(lore=["&7Every &a100 &7kills adds &c+1 &7Damage", "&8(up to +100)&7.", "",
                            "&7Dungeon Healer bonuses:", "&8- &7Healing Circle heals &a10% &7more.",
                            "&8- &7Wish recharges &a10s &7sooner.", "", CLASS_ABILITY_LINE],
                      w={"killsDamage": {"per": 100, "max": 100}, "classAbility": True},
                      extra={"dungeonClassBonus": {"HEALER": {"abilityPower": 10, "ultimateCooldownCut": 10}}})
SPEC["ZOMBIE_COMMANDER_WHIP"] = S(abil=[A("commander_whip", "Rallying Lash", trigger="PASSIVE",
                                          lore=["&7Every zombie slain with it during a", "&7dungeon run grants &c+1❁ Strength",
                                                "&7until the run ends."])],
                                  w={"whip": "zombie"})
SPEC["ZOMBIE_KNIGHT_SWORD"] = S(lore=["&7Gains &c+30❁ Strength &7with the", "&7Zombie Knight armor."], w={"setStr": {"zombie_knight": 30}})
SPEC["STONE_BLADE"] = S(lore=["&7Shifts its shape to suit its", "&7wielder's dungeon class:",
                              "&cBerserk&7: &c+10 &7Damage, &c+75❁ Strength&7,", "&f+10✦ Speed&7.",
                              "&dHealer&7: &a+10☄ Mending&7; allies within", "&a10 &7blocks share &a33% &7of your life-steal healing.",
                              "&bMage&7: &b+100✎ Intelligence&7, &c+10 &7Damage,", "&7melee hits restore &a25% &7more mana.",
                              "&7Tank: &a+100❈ Defense&7, &4+5♨ Vitality&7,", "&f+5❂ True Defense&7.",
                              "&6Archer&7: melee hits make foes take &a10%", "&7more damage from your arrows for &a5s&7.",
                              "", CLASS_ABILITY_LINE],
                        w={"adaptive": True, "classAbility": True})
SPEC["CONJURING_SWORD"] = S(lore=["&7Guided Sheep recharges &a5s &7sooner, and", "&7no stray sheep appear while you hold it."],
                            extra={"dungeonClassBonus": {"MAGE": {"abilityCooldownCut": 5}}})
SPEC["EARTH_SHARD"] = S(lore=["&7Adapts to its wielder inside dungeons,", "&7letting you use your class ability.",
                              "&7Seismic Wave recharges &a2s &7sooner.", "", CLASS_ABILITY_LINE],
                        w={"classAbility": True}, extra={"dungeonClassBonus": {"TANK": {"abilityCooldownCut": 2}}})
SPEC["DARK_CLAYMORE"] = S(lore=["&7Less a sword than a slab of stone", "&7with an edge."])
SPEC["CRYPT_BOW"] = S(lore=["&7Your arrows become exploding", "&8wither skulls&7."], w={"witherSkull": True, "explosive": 2})
SPEC["STINGER_BOW"] = S(abil=[A("sting", "Needle Gift", trigger="PASSIVE", mana=100,
                                lore=["&7In dungeons, arrows that hit a teammate", "&7grant them &f+20✦ Speed &7and &c+30❁ Strength",
                                      "&7for &a30s&7, but deal &c1 &7damage."])],
                        w={"sting": True})
SPEC["SKELETON_LORD_BOW"] = S(lore=["&7Taken from a fallen bone lord."])
SPEC["BOSS_SPIRIT_BOW"] = S(lore=["&7Brittle, yet the only bow that can", "&7wound the thorned spirit."])
for hid in ("HYPERION", "VALKYRIE", "ASTRAEA", "SCYLLA", "NECRON_BLADE"):
    SPEC[hid] = S(w={"vs": {"wither": 50}, "classAbility": True})
SPEC["HYPERION"]["name"] = "Highlord"
SPEC["HYPERION"]["w"]["cataStats"] = {"DAMAGE": 1, "INTELLIGENCE": 2}
SPEC["HYPERION"]["lore"] = ["&7Grants &c+1 &7Damage and &b+2✎ Intelligence", "&7per Catacombs level."]
SPEC["VALKYRIE"]["name"] = "Shieldmaiden"
SPEC["VALKYRIE"]["w"]["cataStats"] = {"DAMAGE": 1, "STRENGTH": 1}
SPEC["VALKYRIE"]["lore"] = ["&7Grants &c+1 &7Damage and &c+1❁ Strength", "&7per Catacombs level."]
SPEC["ASTRAEA"]["name"] = "Starward"
SPEC["ASTRAEA"]["w"]["cataStats"] = {"DAMAGE": 1, "DEFENSE": 2}
SPEC["ASTRAEA"]["lore"] = ["&7Grants &c+1 &7Damage and &a+2❈ Defense", "&7per Catacombs level."]
SPEC["SCYLLA"]["name"] = "Riptide"
SPEC["SCYLLA"]["w"]["cataStats"] = {"DAMAGE": 1, "CRIT_DAMAGE": 1}
SPEC["SCYLLA"]["lore"] = ["&7Grants &c+1 &7Damage and &9+1☠ Crit Damage", "&7per Catacombs level."]
SPEC["NECRON_BLADE"]["name"] = "Warden's Blade (Unrefined)"
for hid in ("HYPERION", "VALKYRIE", "ASTRAEA", "SCYLLA", "NECRON_BLADE"):
    SPEC[hid]["lore"] = list(SPEC[hid]["lore"]) + ([""] if SPEC[hid]["lore"] else []) + [CLASS_ABILITY_LINE]

# Generated vs-text is prepended for every item whose "w" has vs/vsMult/lessFrom/wisdomVs.

# ---- renames that are not in a spec's name (ability/lore nouns) ------------------------------------
EXTRA_RENAMES = [
    ("(lore)", "Soulflow", "Soulstream (the soulflow module's name; shown in the Arsenal and 'not enough' messages)"),
    ("(ability)", 'Instant Transmission', 'Blink Step'),
    ("(ability)", 'Ether Transmission', 'Phasewarp'),
    ("(item mod)", 'Etherwarp Merger', 'Phasewarp Merger (slayer item; applied by /weapons merge)'),
    ("(ability)", 'Dragon Rage', "Wyrm's Fury"),
    ("(ability)", 'Parley', 'Banter'),
    ("(ability)", 'Ragnarock', 'Doomcall'),
    ("(ability)", 'Greed', 'Avarice'),
    ("(ability)", 'Soulcry', 'Soul Howl'),
    ("(ability)", 'Salvation', 'Judgement Beam'),
    ("(ability)", 'Commander Whip', 'Rallying Lash'),
    ("(ability)", 'Sting', 'Needle Gift'),
    ("(ability)", 'Nasty Bite', 'Leech Shot'),
    ("(ability)", 'Showtime', 'Pop Show'),
    ("(ability)", "Giant's Slam", 'Titan Slam'),
    ("(ability)", 'Guided Bat', 'Spirit Bat'),
    ("(ability)", 'Speed Boost', 'Quickstep'),
    ("(ability)", 'Bad Health', 'Blood Price'),
    ("(ability)", 'Enrage', 'Provoke'),
    ("(ability)", 'Extreme Focus', 'Focus Burn'),
    ("(ability)", 'Ink Bomb', 'Ink Burst'),
    ("(ability)", 'Fire Blast', 'Ember Volley'),
    ("(ability)", 'Ice Bolt', 'Frost Bolt'),
    ("(ability)", 'Terrain Toss', 'Boulder Heave'),
    ("(ability)", 'Molten Wave', 'Golden Tide'),
    ("(ability)", 'Rapid-fire (Jerry-chine)', 'Rapid Fire'),
    ("(ability)", 'Leap', 'Pounce'),
    ("(ability)", 'Burning Souls', 'Soul Pyre'),
    ("(ability)", 'Thwack', 'Boomerang Throw'),
    ("(ability)", 'Flay', 'Lash'),
    ("(ability)", 'Instant Heal', 'Mend Flesh'),
    ("(ability)", 'Lightning Strike', 'Skybolt'),
    ("(ability)", 'Explode', 'Eruption'),
    ("(ability)", 'Fire Veil', 'Flame Mantle'),
    ("(ability)", 'Fire Freeze', 'Cold Snap'),
    ("(ability)", 'Hellstorm', 'Reckless Power'),
    ("(ability)", 'Life Blood', 'Blood Pact'),
    ("(ability)", 'Coin Conversion', 'Transmute'),
    ("(ability)", 'Ray of Hope', 'Dawn Ray'),
    ("(ability)", 'Ender Warp', 'Pearl Shot'),
    ("(ability)", 'ME SMASH HEAD', 'Big Bonk'),
    ("(ability)", 'Sinrecall Transmission', 'Sin Recall'),
    ("(ability)", 'Gravity Storm', 'Gravity Well'),
    ("(ability)", 'Cells Alignment', 'Cell Sync'),
    ("(ability)", 'Attunement', 'Attune'),
    ("(ability)", 'Ice Spray', 'Frost Breath'),
    ("(ability)", 'Eye Beam', 'Eye Ray'),
    ("(ability)", 'Dreadlord', 'Grave Skull'),
    ("(ability)", 'Witherlord', 'Grave Skulls'),
    ("(ability)", 'Shadow Fury (ability)', 'Shadow Dance'),
    ("(ability)", 'Shadowstep', 'Backstab Step'),
    ("(ability)", 'Throw (Livid Dagger)', 'Knife Toss'),
    ("(ability)", 'Throw (Halberd)', 'Hurl'),
    ("(ability)", 'Reaving Strike', 'Reaping Arc'),
    ("(ability)", 'Swing (Bonemerang)', 'Bone Toss'),
    ("(ability)", 'Creeper Veil', 'Ward Veil'),
    ("(ability)", 'Heat-Seeking Rose', 'Thorn Seeker'),
    ("(ability)", 'Petal Barrage', 'Petal Storm'),
    ("(ability)", 'Rapid Fire (Machine Gun)', 'Bullet Storm'),
    ("(ability)", 'Small/Medium/Big/Huge Heal', '(kept: generic)'),
    ("(ability)", 'Raise Souls', '(not shipped)'),
    ("(passive)", 'Tempest', 'Gale'),
    ("(passive)", 'Triple Shot', 'Three-fold'),
    ("(passive)", 'Arrow Infusion', 'Shard / Cream / Powder / Gel Infusion'),
    ("(passive)", 'Webcutter', 'Silk Cutter'),
    ("(passive)", 'Stinger', 'Needlepoint'),
    ("(passive)", 'Pack Mentality', 'Pack Instinct'),
    ("(passive)", 'Venom Mold', 'Toxic Rush'),
    ("(passive)", 'Bejeweled Blade', "Jeweller's Edge"),
    ("(passive)", 'Spooktacular', 'Fright Night'),
    ("(passive)", 'Explosive Shot', 'Blast Shot'),
    ("(passive)", 'Dragon Stalker', 'Wyrm Stalker'),
    ("(passive)", 'Eggsecute', 'Egg Breaker'),
    ("(passive)", 'Defense Reduction (arrow)', 'Plate Splitter'),
    ("(passive)", 'Bouncy!', 'Ricochet'),
    ("(passive)", 'Explosive (arrow)', 'Charged Tip'),
    ("(passive)", 'Stuck in goo', 'Gummed Up'),
    ("(passive)", 'Muscle Freeze', 'Numbing Cold'),
    ("(passive)", 'Skewer', 'Skewer (kept: generic)'),
    ("(lore)", 'Dark Auction', 'Shadow Auction'),
    ("(lore)", 'Curse of Greed', "Miser's Curse"),
    ("(lore)", 'Tarantula Broodfather', 'a spider boss'),
    ("(lore)", 'Hellion Shield', "a demon's shield"),
    ("(lore)", 'Thorn', 'the thorned spirit'),
    ("(lore)", 'Dante', '(dropped from the lore)'),
    ("(lore)", 'Jerry', 'jester'),
    ("(lore)", 'Armor of the Pack / Mastiff Armor', 'pack-hunter or mastiff set'),
    ("(lore)", 'Blazing Volcano', 'a burning mountain'),
    ("(lore)", 'Scarf (Adaptive Blade lore)', '(dropped)'),
    ("(lore)", 'Target Practice (arrow requirement)', '(dropped: Combat level gate kept)'),
]
