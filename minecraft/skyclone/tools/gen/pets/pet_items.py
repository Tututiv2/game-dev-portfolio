"""Pet held items, candies, Kat materials: our names, our wording, effects.

Numbers are the ones the reference states (research/06 §6.4 + Appendix D, NEU item lore). Where the
reference has a legacy rarity variant with no stated number, the step is extrapolated and marked
TUNE in the description field `tune` (the hand-off lists them).

Held-item effect vocabulary (PetItems.java):
  stat     {stats:{STAT:v}}            flat stats to the owner while the pet is summoned
  pet_mult {stats:[..]|'ALL', v}       +v% to the pet's OWN base stats
  xp_boost {skill|'ALL', v}            +v% pet XP from that skill
  tier_boost                           pet counts as one rarity higher while holding it
  share    {v}                         +v% Exp Share rate while the pet sits in an Exp Share slot
  per_level {stats:{}, every}          +1 per `every` pet levels
  only     {pet}                       may only be given to that pet type
  kill_buff {stats:{}, dur}            buff for dur seconds after each kill
  kb_immune                            knockback immunity
  when     [conds]                     condition list applied to stat effects
  info                                 shown only
"""


def S(**stats):
    return {'t': 'stat', 'stats': stats}


def F(t, **kw):
    d = {'t': t}
    d.update(kw)
    return d


SKILLS = ['COMBAT', 'FARMING', 'FISHING', 'FORAGING', 'MINING']
RAR = ['COMMON', 'UNCOMMON', 'RARE', 'EPIC', 'LEGENDARY']

# id -> (our name, rarity, lore (our words), effects, tune-note or None)
ITEMS = {}

# ---- skill exp boosts: C20 U30 R40 E50 (research §6.4); L60 extrapolated --------------------------
for sk in SKILLS:
    for i, r in enumerate(RAR):
        v = [20, 30, 40, 50, 60][i]
        ITEMS['PET_ITEM_%s_SKILL_BOOST_%s' % (sk, r)] = (
            '%s Exp Boost' % sk.title(), r,
            'Your pet earns &a+%d%% &7more pet XP from &a%s&7.' % (v, sk.title()),
            [F('xp_boost', skill=sk, v=v)], 'legendary +60% extrapolated' if r == 'LEGENDARY' else None)
# All skills: C10 stated; higher rarities extrapolated (+5 per step)
for i, r in enumerate(RAR):
    v = [10, 15, 20, 25, 30][i]
    ITEMS['PET_ITEM_ALL_SKILLS_BOOST_%s' % r] = (
        'All Skills Exp Boost', r, 'Your pet earns &a+%d%% &7more pet XP from every skill.' % v,
        [F('xp_boost', skill='ALL', v=v)], None if r == 'COMMON' else 'only COMMON is stated; +5%/rarity extrapolated')
ITEMS['ALL_SKILLS_SUPER_BOOST'] = ('All Skills Exp Super-Boost', 'UNCOMMON', 'Your pet earns &a+20% &7more pet XP from every skill.',
                                   [F('xp_boost', skill='ALL', v=20)], None)

# ---- flat stat ladders (legacy rarity variants: same as the stated tier) ------------------------
for i, r in enumerate(RAR):
    ITEMS['PET_ITEM_BIG_TEETH_%s' % r] = ('Big Teeth', r, 'Grants &9+5 Crit Chance&7.', [S(CRIT_CHANCE=5)],
                                         None if r == 'COMMON' else 'legacy variant; same as COMMON')
    ITEMS['PET_ITEM_SHARPENED_CLAWS_%s' % r] = ('Sharpened Claws', r, 'Grants &9+15 Crit Damage&7.', [S(CRIT_DAMAGE=15)],
                                               None if r == 'UNCOMMON' else 'legacy variant; same as UNCOMMON')
    ITEMS['PET_ITEM_HARDENED_SCALES_%s' % r] = ('Hardened Scales', r, 'Grants &a+25 Defense&7.', [S(DEFENSE=25)],
                                               None if r == 'UNCOMMON' else 'legacy variant; same as UNCOMMON')
    ITEMS['PET_ITEM_IRON_CLAWS_%s' % r] = ('Iron Claws', r, 'Raises the pet\'s own &9Crit Damage &7and &9Crit Chance &7by &a40%&7.',
                                          [F('pet_mult', stats=['CRIT_DAMAGE', 'CRIT_CHANCE'], v=40)],
                                          None if r == 'COMMON' else 'legacy variant; same as COMMON')

ITEMS.update({
 'BIGGER_TEETH': ('Bigger Teeth', 'UNCOMMON', 'Grants &9+10 Crit Chance&7.', [S(CRIT_CHANCE=10)], None),
 'SERRATED_CLAWS': ('Serrated Claws', 'RARE', 'Grants &9+25 Crit Damage&7.', [S(CRIT_DAMAGE=25)], None),
 'REINFORCED_SCALES': ('Reinforced Scales', 'RARE', 'Grants &a+40 Defense&7.', [S(DEFENSE=40)], None),
 'GOLD_CLAWS': ('Gold Claws', 'UNCOMMON', 'Raises the pet\'s own &9Crit Damage &7and &9Crit Chance &7by &a50%&7.',
                [F('pet_mult', stats=['CRIT_DAMAGE', 'CRIT_CHANCE'], v=50)], None),
 'PET_ITEM_TEXTBOOK': ('Textbook', 'LEGENDARY', 'Doubles the pet\'s own &bIntelligence&7.', [F('pet_mult', stats=['INTELLIGENCE'], v=100)], None),
 'ANTIQUE_REMEDIES': ('Antique Remedies', 'EPIC', 'Raises the pet\'s own &cStrength &7by &a80%&7.', [F('pet_mult', stats=['STRENGTH'], v=80)], None),
 'HEPHAESTUS_REMEDIES': ('Forgemaster Remedies', 'LEGENDARY', 'Doubles the pet\'s own &cStrength&7.', [F('pet_mult', stats=['STRENGTH'], v=100)], None),
 'MINOS_RELIC': ('Labyrinth Relic', 'EPIC', 'Raises every one of the pet\'s own stats by &a33.3%&7.', [F('pet_mult', stats='ALL', v=33.3)], None),
 'HEPHAESTUS_RELIC': ('Forgemaster Relic', 'LEGENDARY', 'Raises every one of the pet\'s own stats by &a50%&7.', [F('pet_mult', stats='ALL', v=50)], None),
 'PET_ITEM_LUCKY_CLOVER': ('Lucky Clover', 'EPIC', 'Grants &b+7 Magic Find &7and &a+7 &7Overbloom.', [S(MAGIC_FIND=7)], None),
 'POIGNANT_LUCKY_CLOVER': ('Poignant Lucky Clover', 'LEGENDARY', 'Grants &b+13 Magic Find &7and &a+13 &7Overbloom.', [S(MAGIC_FIND=13)], None),
 'PET_ITEM_TIER_BOOST': ('Tier Boost', 'LEGENDARY', 'Your pet counts as &aone rarity higher &7while holding this. Take it off and it drops back.',
                         [F('tier_boost')], None),
 'PET_ITEM_EXP_SHARE': ('Exp Share', 'EPIC', 'While this pet sits in an Exp Share slot, its share rate is &a+15%&7.', [F('share', v=15)], None),
 'PET_ITEM_QUICK_CLAW': ('Quick Claw', 'LEGENDARY', 'Every &a2 &7pet levels grant &6+1 Mining Speed &7and &6+1 Mining Fortune&7.',
                         [F('per_level', stats={'MINING_SPEED': 1, 'MINING_FORTUNE': 1}, every=2)], None),
 'CROCHET_TIGER_PLUSHIE': ('Crochet Tiger Plushie', 'EPIC', 'Grants &e+35 Attack Speed&7.', [S(ATTACK_SPEED=35)], None),
 'HEPHAESTUS_PLUSHIE': ('Forgemaster Plushie', 'LEGENDARY', 'Grants &e+50 Attack Speed&7.', [S(ATTACK_SPEED=50)], None),
 'DWARF_TURTLE_SHELMET': ('Dwarf Turtle Shelmet', 'EPIC', 'Grants &f+10 True Defense &7and makes you immune to knockback.',
                          [S(TRUE_DEFENSE=10), F('kb_immune')], None),
 'HEPHAESTUS_SHELMET': ('Forgemaster Shelmet', 'LEGENDARY', 'Grants &f+20 True Defense &7and makes you immune to knockback.',
                        [S(TRUE_DEFENSE=20), F('kb_immune')], None),
 'CRETAN_URN': ('Cretan Urn', 'EPIC', 'Grants &d+5 Pet Luck&7.', [S(PET_LUCK=5)], None),
 'HEPHAESTUS_URN': ('Forgemaster Urn', 'LEGENDARY', 'Grants &d+10 Pet Luck&7.', [S(PET_LUCK=10)], None),
 'WASHED_UP_SOUVENIR': ('Washed-up Souvenir', 'EPIC', 'Grants &3+5 Sea Creature Chance&7.', [S(SEA_CREATURE_CHANCE=5)], None),
 'HEPHAESTUS_SOUVENIR': ('Forgemaster Souvenir', 'LEGENDARY', 'Grants &3+10 Sea Creature Chance&7.', [S(SEA_CREATURE_CHANCE=10)], None),
 'PET_ITEM_SADDLE': ('Saddle', 'UNCOMMON', 'Grants &f+30 Speed&7.', [S(SPEED=30)], None),
 'PET_ITEM_SPOOKY_CUPCAKE': ('Spooky Cupcake', 'UNCOMMON', 'Grants &c+30 Strength &7and &f+20 Speed&7.', [S(STRENGTH=30, SPEED=20)], None),
 'REAPER_GEM': ('Reaper Gem', 'LEGENDARY', 'Each kill grants &c+8 Ferocity &7for &a5s&7.', [F('kill_buff', stats={'FEROCITY': 8}, dur=5)], None),
 'FOUR_EYED_FISH': ('Four-Eyed Fish', 'EPIC', 'Griffin only. Grants &b+13 Magic Find &7and &a+55 Defense&7; burrows pay &6+2,000 coins&7.',
                    [F('only', pet='GRIFFIN'), S(MAGIC_FIND=13, DEFENSE=55)], None),
 'PET_ITEM_FLYING_PIG': ('Flying Pig', 'RARE', 'Pig only. Grants &f+20 Speed &7and &6+40 &7Potato Fortune.',
                         [F('only', pet='PIG'), S(SPEED=20)], None),
 'PET_ITEM_BUBBLEGUM': ('Bubblegum', 'RARE', 'Your placed deployables last twice as long.', [F('info')], None),
 'UNALLOYED_SPEED': ('Unalloyed Speed', 'EPIC', 'Raises your speed cap by &f50&7.', [F('info')], None),
 'BURNT_TEXTS': ('Burnt Texts', 'LEGENDARY', 'Grants &9+5 Double Hook Chance &7but &c-50 Fishing Speed&7.',
                 [S(DOUBLE_HOOK_CHANCE=5, FISHING_SPEED=-50)], None),
 'SNAKE_EYES': ('Snake Eyes', 'LEGENDARY', 'Grants &6+1.1 Treasure Chance&7.', [S(TREASURE_CHANCE=1.1)], None),
 'EDIBLE_SEAWEED': ('Edible Seaweed', 'RARE', 'Grants &b+10 Fishing Speed&7.', [S(FISHING_SPEED=10)], None),
 'WINDING_IVY': ('Winding Ivy', 'RARE', 'Grants &2+10 Sweep&7.', [S(SWEEP=10)], None),
 'BARREL_OF_RICHES': ('Barrel of Riches', 'EPIC', 'Trophy fish are &a10% &7more likely to be Gold.', [F('info')], None),
 'LOTUS_CROWN': ('Lotus Crown', 'LEGENDARY', 'Trophy fish are &a10% &7more likely to be Diamond.', [F('info')], None),
 'PET_ITEM_TITANIUM_MINECART': ('Titanium Minecart', 'RARE', 'Grants &6+33.3 Mining Fortune &7in the Dwarven Mines.',
                                [{'t': 'stat', 'stats': {'MINING_FORTUNE': 33.3}, 'when': ['island:dwarven_mines']}], None),
 'BROWN_BANDANA': ('Brown Bandana', 'EPIC', 'Gain &2+0.2 Bonus Pest Chance &7per pest bestiary tier.', [F('info')], None),
 'RUBBER_SNORKEL': ('Rubber Snorkel', 'RARE', 'Grants Respiration, doubled on foraging islands.', [F('potion', effect='WATER_BREATHING')], None),
 'GRANDMAS_KNITTING_NEEDLE': ('Knitting Needle', 'RARE', 'Grandma Wolf only. A combo of &a50+ &7grants &b+5% Magic Find&7.',
                              [F('only', pet='GRANDMA_WOLF')], None),
 'BEJEWELED_COLLAR': ('Bejeweled Collar', 'RARE', 'Grants &6+25 Mining Speed &7and &6+10 Mining Fortune&7.',
                      [S(MINING_SPEED=25, MINING_FORTUNE=10)], None),
 'DEAD_CAT_FOOD': ('Dead Cat Food', 'RARE', 'A curious snack for a curious cat.', [F('info')], None),
})

# ---- held by a pet, but the ITEM is registered (and its effect applied) by another module ----------
# Only a held_items.json row is written: no item, no recipe, no lore here. The owning module reads
# Features.Pets.abilityValue(p, "held:<ID>", "a") (1 while the summoned pet holds it) and applies the
# effect itself, so the fx stays 'info' - a stat fx here would count the bonus twice.
HELD_ELSEWHERE = {
 'GREEN_BANDANA': ([F('info')], 'garden'),   # +4 Farming Fortune per Garden level, applied by garden Effects
}

# ---- Kat-only upgrade items (no held effect) ----------------------------------------------------
KAT_ONLY = {
 'SMALL_SLOTH_CLAW': ('Small Sloth Claw', 'UNCOMMON'), 'MEDIUM_SLOTH_CLAW': ('Medium Sloth Claw', 'RARE'),
 'LARGE_SLOTH_CLAW': ('Large Sloth Claw', 'EPIC'), 'GIANT_SLOTH_CLAW': ('Giant Sloth Claw', 'LEGENDARY'),
}

# ---- consumables ---------------------------------------------------------------------------------
CANDY = {
 'SIMPLE_CARROT_CANDY': ('Simple Carrot Candy', 'UNCOMMON', 20000),
 'GREAT_CARROT_CANDY': ('Great Carrot Candy', 'RARE', 200000),
 'SUPERB_CARROT_CANDY': ('Superb Carrot Candy', 'EPIC', 750000),
 'ULTIMATE_CARROT_CANDY': ('Ultimate Carrot Candy', 'LEGENDARY', 1500000),
 'PET_CAKE': ('Pet Cake', 'COMMON', 10000),
}

# Renames of reference proper nouns used by pets (shown to players).
RENAMES = {
 'KAT_FLOWER': ('Kat Flower', "Sitter's Daisy"),
 'KAT_BOUQUET': ('Kat Bouquet', "Sitter's Bouquet"),
 'ZOG_ANVIL': ('Zog Anvil', "Tamer's Anvil"),
 'HEPHAESTUS_ANVIL': ('Hephaestus Anvil', 'Forgemaster Anvil'),
}
