package com.skyclone.mod.foraging;

import java.util.List;

/**
 * What a Tree Gift can hold beyond its guaranteed contents, pure (no Bukkit).
 *
 * <p><b>Creatures</b> (research 07 §4.4 "rare gift rolls", NEU monster cards): Fig/Mangrove release a Phanpyre
 * 11.8%, Phanflare 7.1% or Dreadwing 1.2%; Spiral a Firefox 15%, Groundhog 15%, Drybark 5%, Puck 5% or Grizzly Bear
 * 1%. Mangrove's odds are not given separately, so it uses Fig's (TUNE). They are the hunting module's critter
 * defs, so a lasso catches them for their shards; at most one per gift (one roll against the summed table).
 *
 * <p><b>Bonus drops</b> (SkyHanni's TreeGiftBonusDrops list; odds are ours, TUNE except Tree the Fish's 1 in 2,000):
 * enchanted books (First Impression, Missile, Karma), Sweep / Foraging Wisdom boosters, Chop and Reptiloid shards,
 * the fading axe runes, Tree the Fish, the Epic Foraging Exp Boost pet item, and Forest's Favor on spirals.
 * Each row lists id candidates; the first one some module registered is given.
 */
final class GiftTables {

    private GiftTables() {}

    record Creature(String mob, String fallback, double chance) {}

    record Extra(List<String> ids, double chance) {}

    static final List<Creature> FIG = List.of(
            new Creature("critter_phanpyre", null, 0.118), new Creature("critter_phanflare", null, 0.071),
            new Creature("critter_dreadwing", null, 0.012));
    static final List<Creature> HELIX = List.of(
            new Creature("critter_firefox", null, 0.15), new Creature("critter_groundhog", null, 0.15),
            new Creature("critter_drybark", null, 0.05), new Creature("critter_puck", null, 0.05),
            new Creature("critter_grizzly_bear", "galatea_grizzly_bear", 0.01));

    static List<Creature> creatures(Wood w) {
        return switch (w) {
            case FIG, MANGROVE -> FIG;
            case HELIX -> HELIX;
            default -> List.of();
        };
    }

    /**
     * One roll ({@code roll} in [0,1)) against the wood's creature table with every chance times {@code factor}
     * (Tree Lurker, the dial); the creature it lands on, or null for none.
     */
    static Creature pick(Wood w, double factor, double roll) {
        double acc = 0;
        for (Creature c : creatures(w)) {
            acc += c.chance() * Math.max(0, factor);
            if (roll < Math.min(1, acc)) return c;
        }
        return null;
    }

    static final List<Extra> COMMON = List.of(
            new Extra(List.of("SWEEP_BOOSTER"), 0.02),
            new Extra(List.of("FORAGING_WISDOM_BOOSTER"), 0.02),
            new Extra(List.of("ENCHANTMENT_KARMA_1", "KARMA;1"), 0.01),
            new Extra(List.of("ENCHANTMENT_ULTIMATE_MISSILE_1", "ULTIMATE_MISSILE;1"), 0.004),
            new Extra(List.of("ENCHANTMENT_ULTIMATE_FIRST_IMPRESSION_1", "ULTIMATE_FIRST_IMPRESSION;1"), 0.004),
            new Extra(List.of("SHARD_HUMMINGBIRD"), 0.01),          // Chop attribute
            new Extra(List.of("SHARD_CHAMELEON"), 0.002),           // Reptiloid attribute
            new Extra(List.of("PET_ITEM_FORAGING_SKILL_BOOST_EPIC"), 0.003));

    static List<Extra> extras(Wood w) {
        List<Extra> own = switch (w) {
            case FIG -> List.of(new Extra(List.of("TREE_THE_FISH"), 0.0005), new Extra(List.of("AXE_FADING_WHITE_RUNE;1"), 0.005));
            case MANGROVE -> List.of(new Extra(List.of("AXE_FADING_GREEN_RUNE;1"), 0.005));
            case HELIX -> List.of(new Extra(List.of(Boosters.FAVOR), 0.002), new Extra(List.of("AXE_FADING_BLUE_RUNE;1"), 0.005));
            default -> List.of();
        };
        java.util.ArrayList<Extra> all = new java.util.ArrayList<>(own);
        all.addAll(COMMON);
        return all;
    }
}
