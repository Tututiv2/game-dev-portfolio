package com.skyclone.mod.bank;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bank's formulas and generated data against the research numbers (research 05 sections 1.2, 1.3, 1.8, 6.2, 6.3). */
class BankMathTest {

    private static BankCatalog catalog() {
        return BankCatalog.load(f -> {
            InputStream in = BankMathTest.class.getResourceAsStream("/data/bank/" + f);
            assertNotNull(in, "missing resource data/bank/" + f);
            return in;
        });
    }

    @Test
    void tiersMatchResearch() {
        BankCatalog c = catalog();
        assertEquals(7, c.tiers.size());
        long[] caps = {50_000_000L, 100_000_000L, 250_000_000L, 500_000_000L, 1_000_000_000L, 6_000_000_000L, 60_000_000_000L};
        long[] costs = {0, 5_000_000, 10_000_000, 25_000_000, 50_000_000, 100_000_000, 200_000_000};
        int[] blocks = {0, 1, 5, 20, 50, 100, 200};
        long[] maxInt = {250_000, 300_000, 350_000, 390_000, 500_000, 1_000_000, 1_500_000};
        for (int i = 0; i < 7; i++) {
            BankMath.Tier t = c.tiers.get(i);
            assertEquals(i, t.index);
            assertEquals(caps[i], t.capacity, t.name);
            assertEquals(costs[i], t.coins, t.name);
            assertEquals(blocks[i], t.enchantedGoldBlocks, t.name);
            assertEquals(maxInt[i], t.maxInterest, t.name);
        }
        assertEquals("Palatial", c.tiers.get(6).name);
        assertEquals(50, c.tiers.get(6).sbxp);
    }

    @Test
    void interestBands() {
        List<BankMath.Band> b = catalog().bands;
        assertEquals(200_000, BankMath.interest(10_000_000, 0, b), 1e-6);
        assertEquals(250_000, BankMath.interest(15_000_000, 0, b), 1e-6);
        assertEquals(250_000, BankMath.interest(50_000_000, 0, b), 1e-6);     // Starter stops at 15M
        assertEquals(300_000, BankMath.interest(50_000_000, 1, b), 1e-6);     // Gold adds 15-20M at 1%
        assertEquals(20_000, BankMath.interest(1_000_000, 0, b), 1e-6);
        assertEquals(0, BankMath.interest(0, 6, b), 1e-9);
        assertEquals(0, BankMath.interest(-5, 6, b), 1e-9);
        for (BankMath.Tier t : catalog().tiers) {
            assertEquals(t.maxInterest, BankMath.maxInterest(t.index, b), 0.5, t.name);
            assertEquals(t.maxInterest, BankMath.interest(1e13, t.index, b), 0.5, t.name);
        }
        // personal accounts: bands a fifth as wide pay a fifth as much at the matching balance
        assertEquals(40_000, BankMath.interestScaled(2_000_000, 0, b, 0.2), 1e-6);
        assertEquals(50_000, BankMath.interestScaled(10_000_000, 0, b, 0.2), 1e-6);
    }

    @Test
    void periodsAreSeasons() {
        assertEquals(BankMath.periodIndex(517, 1, 3), BankMath.periodIndex(517, 3, 3));
        assertEquals(BankMath.periodIndex(517, 3, 3) + 1, BankMath.periodIndex(517, 4, 3));
        assertEquals(BankMath.periodIndex(517, 12, 3) + 1, BankMath.periodIndex(518, 1, 3));
        assertEquals(4, BankMath.periodIndex(518, 1, 3) - BankMath.periodIndex(517, 1, 3));
        assertEquals(93, BankMath.daysUntilNextPeriod(1, 1, 0, 0, 3, 31), 1e-9);
        assertEquals(31, BankMath.daysUntilNextPeriod(3, 1, 0, 0, 3, 31), 1e-9);
        assertEquals(0.5, BankMath.daysUntilNextPeriod(12, 31, 12, 0, 3, 31), 1e-9);
        // monthly payouts if the dial says 1
        assertEquals(BankMath.periodIndex(1, 1, 1) + 1, BankMath.periodIndex(1, 2, 1));
    }

    @Test
    void fameAndBits() {
        BankCatalog c = catalog();
        assertEquals(24, c.fame.size());
        assertEquals(1.0, BankMath.bitsMultiplier(0, c.fame), 1e-9);
        assertEquals(1.1, BankMath.bitsMultiplier(20_000, c.fame), 1e-9);
        assertEquals(1.0, BankMath.bitsMultiplier(19_999, c.fame), 1e-9);
        assertEquals(2.4, BankMath.bitsMultiplier(600_000_000L, c.fame), 1e-9);
        assertEquals("Paragon", c.fameRank(600_000_000L).name);
        assertEquals(4800, BankMath.cookiePool(4800, 1.0));
        assertEquals(250, BankMath.bitsPayout(4800, 250, 1.0));
        assertEquals(275, BankMath.bitsPayout(4800, 250, 1.1));
        assertEquals(30, BankMath.bitsPayout(30, 250, 1.0));
        assertEquals(0, BankMath.bitsPayout(0, 250, 1.0));
        // a cookie drains in 19.2 payouts = 9.6 hours of play at 30 minutes each
        long pool = BankMath.cookiePool(4800, 1.0);
        int payouts = 0;
        while (pool > 0) { pool -= BankMath.bitsPayout(pool, 250, 1.0); payouts++; }
        assertEquals(20, payouts);
    }

    @Test
    void tradeCaps() {
        assertEquals(50e6, BankMath.tradeCap(0, 50e6, 1e9, 1e10, 50, 100), 0);
        assertEquals(50e6, BankMath.tradeCap(49, 50e6, 1e9, 1e10, 50, 100), 0);
        assertEquals(1e9, BankMath.tradeCap(50, 50e6, 1e9, 1e10, 50, 100), 0);
        assertEquals(1e10, BankMath.tradeCap(100, 50e6, 1e9, 1e10, 50, 100), 0);
    }

    @Test
    void coinRoundingAndText() {
        assertEquals(125, BankMath.creditTenths(12.5));
        assertEquals(3, BankMath.creditTenths(0.3));
        assertEquals(12, BankMath.creditTenths(1.29));
        assertEquals(13, BankMath.debitTenths(1.21));
        assertEquals(0, BankMath.creditTenths(Double.NaN));
        assertEquals(0, BankMath.creditTenths(-4));
        assertEquals(1.2, BankMath.floorTenth(1.29), 1e-9);
        assertEquals("12,500,000 coins (12.5M)", BankMath.coinWords(12_500_000));
        assertEquals("1,250,000 coins (1.2M)", BankMath.coinWords(1_250_000));
        assertEquals("999 coins", BankMath.coinWords(999));
        assertEquals("1.5k", BankMath.shortNum(1_500));
        assertEquals("60B", BankMath.shortNum(60_000_000_000.0));
        assertEquals("XVIII", BankMath.roman(18));
        assertEquals("3d 0h", BankMath.duration(3L * 86_400_000L));
        assertEquals("45s", BankMath.duration(45_000));
    }

    /** Playtest: "Withdraw half of 102.5" showed 51.2 out / 51.3 left, and half of a 407.5 purse 203.7. */
    @Test
    void sharesAreWholeTenthsAndAddUp() {
        assertEquals(1025, BankMath.tenthsOf(102.5));
        assertEquals(1025, BankMath.tenthsOf(102.49999999999999));   // float noise from the economy
        assertEquals(0, BankMath.tenthsOf(Double.NaN));
        assertEquals(0, BankMath.tenthsOf(-3));
        long half = BankMath.share(1025, 1, 2);
        assertEquals(512, half);                                    // 51.2 moves, the odd tenth stays
        assertEquals(1025, half + (1025 - half));
        assertEquals("51.2", BankMath.commasTenths(half));
        assertEquals("51.3", BankMath.commasTenths(1025 - half));
        assertEquals("203.7", BankMath.commasTenths(BankMath.share(4075, 1, 2)));
        assertEquals(205, BankMath.share(1025, 1, 5));               // 20% of 102.5 = 20.5 exactly
        assertEquals(1025, BankMath.share(1025, 1, 1));
        assertEquals(0, BankMath.share(1, 1, 2));
        assertEquals(0, BankMath.share(-10, 1, 2));
        long big = 9_000_000_000_000_000L;                          // near the core coin cap: no overflow
        assertEquals(big / 5, BankMath.share(big, 1, 5));
        for (long t = 0; t < 2000; t++) {                           // every share is a whole tenth <= the balance
            long h = BankMath.share(t, 1, 2), f = BankMath.share(t, 1, 5);
            assertEquals(t / 2, h);
            assertEquals(t / 5, f);
        }
        assertEquals("51.25", BankMath.commasExact(51.25));
        assertEquals("1,234.5", BankMath.commasTenths(12345));
    }

    @Test
    void tierForCapacity() {
        BankCatalog c = catalog();
        assertEquals(0, BankMath.tierForCapacity(50_000_000, c.tiers));
        assertEquals(1, BankMath.tierForCapacity(100_000_000, c.tiers));
        assertEquals(6, BankMath.tierForCapacity(60_000_000_000.0, c.tiers));
    }

    @Test
    void shopAndUpgradesData() {
        BankCatalog c = catalog();
        assertTrue(c.bitsShop.size() >= 40, "bits shop entries: " + c.bitsShop.size());
        Set<String> ids = new HashSet<>();
        for (BankCatalog.BitsOffer o : c.bitsShop) {
            assertTrue(o.bits > 0, o.item);
            assertFalse(o.item.contains(";"), "book ids must be translated: " + o.item);
            assertTrue(ids.add(o.item), "duplicate offer " + o.item);
        }
        assertTrue(ids.contains("GOD_POTION_2"));
        assertEquals(15, c.upgrades.size());
        BankCatalog.Upgrade island = c.upgrades.get("island_size");
        assertEquals(10, island.maxTier());
        assertEquals(241, island.tier(10).value, 1e-9);       // research: 161 -> 241
        BankCatalog.Upgrade allowance = c.upgrades.get("coins_allowance");
        assertEquals(10_000, allowance.tier(1).value, 1e-9);  // research: 10k -> 50k a day
        assertEquals(50_000, allowance.tier(5).value, 1e-9);
        assertEquals("profile", allowance.scope);
        assertEquals(5, c.upgrades.get("magic_find").tier(5).value, 1e-9);
        assertEquals(40, c.upgrades.get("garden_farming_fortune").tier(10).value, 1e-9);
        for (BankCatalog.Upgrade u : c.upgrades.values()) {
            for (int i = 0; i < u.tiers.size(); i++) {
                assertEquals(i + 1, u.tiers.get(i).tier, u.id);
                assertTrue(u.tiers.get(i).hours >= 0, u.id);
                if (i > 0) assertTrue(u.tiers.get(i).value >= u.tiers.get(i - 1).value, u.id + " values must not shrink");
            }
        }
        assertNotNull(c.npcSkins.get("banker"));
        assertNotNull(c.npcSkins.get("community_shop"));
    }

    @Test
    void riskyTradesAskTwice() {
        // giving something for nothing: always confirm, even with an old partner
        assertNotNull(BankMath.tradeRisk(true, false, 0, 5, 1_000_000, "Bob"));
        // a gift TO you is not risky for you
        assertEquals(null, BankMath.tradeRisk(false, true, 0, 0, 1_000_000, "Bob"));
        // first trade paying 1M+ asks; under the line or an old partner does not
        String r = BankMath.tradeRisk(true, true, 1_000_000, 0, 1_000_000, "Bob");
        assertNotNull(r);
        assertTrue(r.contains("1,000,000 coins (1M)"), r);
        assertEquals(null, BankMath.tradeRisk(true, true, 999_999.9, 0, 1_000_000, "Bob"));
        assertEquals(null, BankMath.tradeRisk(true, true, 5_000_000, 1, 1_000_000, "Bob"));
        // log not read yet (-1) is not treated as a first trade; threshold 0 disables
        assertEquals(null, BankMath.tradeRisk(true, true, 5_000_000, -1, 1_000_000, "Bob"));
        assertEquals(null, BankMath.tradeRisk(true, true, 5_000_000, 0, 0, "Bob"));
    }

    @Test
    void compassPointsTheMinecraftWay() {
        assertEquals("north", BankMath.compass(0, -10));
        assertEquals("south", BankMath.compass(0, 10));
        assertEquals("east", BankMath.compass(10, 0));
        assertEquals("west", BankMath.compass(-10, 0));
        assertEquals("north-west", BankMath.compass(-10, -10));
        assertEquals("south-east", BankMath.compass(7, 8));
        assertEquals("here", BankMath.compass(0, 0));
    }
}
