package com.auraqol.skyblock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * A Garden session replayed line by line through the same glue the game uses
 * (MoneyRouter -> MoneyChat -> IncomeEngine) against a small price snapshot.
 * The chat lines are the SkyHanni-pattern wordings used in MoneyChatTest.
 */
class MoneyRouterTest {

    private static final long S = 1000;

    private static PriceData prices() {
        Map<String, List<String>> names = new HashMap<>();
        Map<String, Double> npc = new HashMap<>();
        for (String[] n : new String[][]{
                {"WHEAT", "Wheat"}, {"ENCHANTED_COCOA", "Enchanted Cocoa Beans"}, {"OVERCLOCKER_3000", "Overclocker 3000"},
                {"DUNG", "Dung"}, {"VINYL_PRETTY_FLY", "Pretty Fly Vinyl"}}) {
            names.put(n[1].toLowerCase(java.util.Locale.ROOT), List.of(n[0]));
        }
        npc.put("OVERCLOCKER_3000", 250_000.0);
        npc.put("VINYL_PRETTY_FLY", 50_000.0);
        return PriceData.EMPTY
                .withItems(new PriceData.Items(npc, names), 1)
                .withBazaar(Map.of(
                        "WHEAT", new PriceData.Bazaar(7, 6),
                        "ENCHANTED_COCOA", new PriceData.Bazaar(750, 700),
                        "DUNG", new PriceData.Bazaar(100, 90)), 1)
                .withLbin(Map.of("VINYL_PRETTY_FLY", 48_000.0, "LVL_1_EPIC_SLUG", 1_000_000.0), 1, "test");
    }

    @Test
    void aGardenSessionCountsTheFarmingAndTheDropsAndNotTheClaim() {
        PriceData d = prices();
        IncomeEngine e = new IncomeEngine(new RateTracker(60 * S), 3 * S, 90 * S);
        e.rate().activity(0);
        e.setCollecting(java.util.Set.of("WHEAT"));
        e.inventory(0, Map.of());

        MoneyRouter.sackHover(e, d, 30 * S, "Added items:\n  +3,000 Wheat (Agronomy Sack)\n\nThis message can be disabled in the settings.");

        e.menu(40 * S, true, false);                                   // Bazaar, Manage Orders
        MoneyRouter.line(e, d, 41 * S, "§6[Bazaar] §7Claimed §a640x §fEnchanted Cocoa Beans §7worth §6448,000 coins §7bought for §6700.0 §7each!", null, true);
        e.menu(42 * S, false, false);
        MoneyRouter.sackHover(e, d, 60 * S, "Added items:\n  +640 Enchanted Cocoa Beans (Agronomy Sack)\n  +2,000 Wheat (Agronomy Sack)");

        MoneyRouter.line(e, d, 70 * S, "§6§lRARE DROP! §r§9Overclocker 3000 §b(+120% Magic Find)", null, true);
        e.inventory(71 * S, Map.of("OVERCLOCKER_3000", new IncomeEngine.Holding(1, 250_000.0)));

        MoneyRouter.line(e, d, 80 * S, "§eYou received §a7x §fDung §efor killing a §2Fly§e!", null, true);
        MoneyRouter.line(e, d, 85 * S, "§6§lRARE DROP! §r§9Pretty Fly Vinyl §6(+1,234\uE02B)", null, true);
        String pet = "§6§lPET DROP! §r§5Slug §b(+300% Magic Find)";
        MoneyRouter.line(e, d, 90 * S, pet, pet, true);
        MoneyRouter.line(e, d, 95 * S, "§6§lRARE DROP! §r§9Weird Unknown Thing", null, true);
        MoneyRouter.sackHover(e, d, 100 * S, "Added items:\n  +7 Dung (Agronomy Sack)\n  +1,500 Wheat (Agronomy Sack)");
        MoneyRouter.sackHover(e, d, 100 * S, "Removed items:\n  -64 Wheat (Agronomy Sack)");   // never income

        double[] by = e.sessionBySource();
        assertEquals((3_000 + 2_000 + 1_500) * 6.0, by[IncomeSource.SACKS.ordinal()], 1e-6);
        assertEquals(250_000 + 1_000_000, by[IncomeSource.RARE.ordinal()], 1e-6);   // Overclocker + the pet
        assertEquals(0, by[IncomeSource.OTHER.ordinal()], 1e-6);
        assertEquals(Map.of("Weird Unknown Thing", 1), e.unpriced());
        double pest = 7 * 90.0 + 48_000;
        assertEquals(pest, by[IncomeSource.PEST.ordinal()], 1e-6);              // dung (netted in sacks) + vinyl
        assertEquals(39_000 + 1_250_000 + pest, e.rate().total(), 1e-6);
    }
}
