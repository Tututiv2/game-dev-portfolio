package com.skyclone.mod.rift;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The Rift's pure rules, against the research numbers they come from. */
class RiftMathTest {

    static final long H4 = 4 * 3_600_000L;

    @Test
    void infusionsChargeEveryFourHoursAndCapAtThree() {
        // research 03 sec 19: 1 free every 4 h, 3 stored
        assertEquals(1, RiftMath.accrue(0, 1000, 1000 + H4, H4, 3).count());
        assertEquals(2, RiftMath.accrue(0, 1000, 1000 + 2 * H4 + 7, H4, 3).count());
        assertEquals(3, RiftMath.accrue(1, 1000, 1000 + 50 * H4, H4, 3).count());
        // partial progress is kept: 1.5 periods -> 1 charge, clock advanced by exactly one period
        RiftMath.Infusions i = RiftMath.accrue(0, 1000, 1000 + H4 + H4 / 2, H4, 3);
        assertEquals(1, i.count());
        assertEquals(1000 + H4, i.clock());
        // a full store does not bank time
        RiftMath.Infusions full = RiftMath.accrue(3, 1000, 1000 + 9 * H4, H4, 3);
        assertEquals(3, full.count());
        assertEquals(1000 + 9 * H4, full.clock());
        assertEquals(0, RiftMath.untilNext(3, 0, 5, H4, 3));
        assertEquals(H4 - 10, RiftMath.untilNext(0, 100, 110, H4, 3));
    }

    @Test
    void riftTimeSumsItsSources() {
        // research: base 480 s
        assertEquals(480, RiftMath.maxTime(480, 0, 60, 0, 4, 5, 0));
        assertEquals(480 + 8 * 60 + 13 * 5 + 100, RiftMath.maxTime(480, 8, 60, 52, 4, 5, 100));
        assertEquals("7m 32s", RiftMath.clock(452));
        assertEquals("9s", RiftMath.clock(8.2));
        assertEquals("0s", RiftMath.clock(-3));
    }

    @Test
    void gatesFollowResearchTable() {
        // research 03 sec 19.1
        RiftCatalog.Area lagoon = RiftCatalog.area("black_lagoon");
        assertFalse(RiftMath.passesGate(lagoon, Set.of(), 239));
        assertTrue(RiftMath.passesGate(lagoon, Set.of(), 240));
        RiftCatalog.Area plaza = RiftCatalog.area("village_plaza");
        assertFalse(RiftMath.passesGate(plaza, Set.of("chicken"), 600));
        assertTrue(RiftMath.passesGate(plaza, Set.of("chicken", "mirror"), 480));
        RiftCatalog.Area colo = RiftCatalog.area("colosseum");
        assertEquals(900, colo.gateSeconds());
        assertEquals(0.5, colo.drain());
        assertEquals(0.0, RiftCatalog.area("mirrorverse").drain());
        assertTrue(RiftMath.passesGate(RiftCatalog.area("wyld_woods"), Set.of(), 0));
        assertNull(RiftCatalog.area("wyld_woods").requirementKey());
        assertEquals("rift:gate:chateau", RiftCatalog.area("chateau").requirementKey());
    }

    @Test
    void heartsAreAShareOfMaxHealth() {
        assertEquals(10, RiftMath.heartDamage(1, 100, 10));
        assertEquals(250, RiftMath.heartDamage(2.5, 1000, 10));
        assertEquals(0, RiftMath.heartDamage(1, 100, 0));
        assertEquals(10, RiftMath.heartsLeft(100, 100, 10));
        assertEquals(5, RiftMath.heartsLeft(50, 100, 10));
        assertEquals(0.5, RiftMath.heartsLeft(1, 100, 10));
    }

    @Test
    void mazePathIsConnectedAndCrossesTheRoom() {
        for (long seed = 0; seed < 500; seed++) {
            long[] rows = RiftMath.mazePath(11, 17, seed);
            assertEquals(17, rows.length);
            for (int r = 0; r < rows.length; r++) {
                assertNotEquals(0, rows[r], "row " + r + " seed " + seed);
                assertEquals(0, rows[r] >>> 11, "path left the room, seed " + seed);
                // each row's safe tiles are one contiguous run
                long v = rows[r] >>> Long.numberOfTrailingZeros(rows[r]);
                assertEquals(0, v & (v + 1), "row " + r + " is not contiguous, seed " + seed);
            }
            for (int r = 0; r + 1 < rows.length; r++) assertNotEquals(0, rows[r] & rows[r + 1], "rows do not touch, seed " + seed);
        }
    }

    @Test
    void catalogCountsMatchResearch() {
        assertEquals(52, RiftCatalog.totalSouls());                         // research 08 sec 5.5
        assertEquals(8, RiftCatalog.CHARMS.size());
        assertEquals(10, RiftCatalog.AREAS.size());
        assertEquals("wyld_woods", RiftCatalog.soulArea(1));
        assertEquals("mountaintop", RiftCatalog.soulArea(52));
        for (RiftCatalog.Charm c : RiftCatalog.CHARMS) assertNotNull(RiftCatalog.area(c.area()), c.id());
        for (RiftCatalog.Area a : RiftCatalog.AREAS) for (String c : a.gateCharms()) assertNotNull(RiftCatalog.charm(c), a.id() + " -> " + c);
        assertEquals(List.of(), RiftCatalog.AREAS.stream().filter(a -> a.name().contains("Wyld") || a.name().contains("Stillgore")).toList());
    }

    @Test
    void splitOrStealPaysLikeTheGameShow() {
        assertArrayEquals(new long[]{1000, 1000}, RiftMath.splitSteal(false, false, 2000));
        assertArrayEquals(new long[]{2000, 0}, RiftMath.splitSteal(true, false, 2000));
        assertArrayEquals(new long[]{0, 2000}, RiftMath.splitSteal(false, true, 2000));
        assertArrayEquals(new long[]{0, 0}, RiftMath.splitSteal(true, true, 2000));
        assertArrayEquals(new long[]{0, 0}, RiftMath.splitSteal(false, false, -5));
        // nothing is ever created: the two payouts never exceed the pot
        for (boolean a : new boolean[]{true, false}) for (boolean b : new boolean[]{true, false}) {
            long[] r = RiftMath.splitSteal(a, b, 2001);
            assertTrue(r[0] + r[1] <= 2001);
        }
        long h2 = 2 * 3_600_000L;
        assertEquals(0, RiftMath.splitStealCooldown(0, 5000, h2));
        assertEquals(h2 - 10, RiftMath.splitStealCooldown(100, 110, h2));
        assertEquals(0, RiftMath.splitStealCooldown(100, 100 + h2, h2));
    }

    @Test
    void cruxTalismansCountOnlyTheMonstersTheirTierTracks() {
        assertEquals(0, RiftMath.cruxMilestones(9));
        assertEquals(1, RiftMath.cruxMilestones(10));          // research: "Shy: 0/10 kills"
        assertEquals(RiftMath.CRUX_STEPS.length, RiftMath.cruxMilestones(1_000_000));
        java.util.Map<String, Integer> kills = new java.util.HashMap<>();
        kills.put("rift_shy", 60);       // 2 milestones
        kills.put("rift_shadow", 10);    // 1
        kills.put("rift_puff", 5000);    // 6, but only the top tier tracks puffs
        assertEquals(0, RiftMath.cruxMilestones(kills, 0));
        assertEquals(2, RiftMath.cruxMilestones(kills, 1));
        assertEquals(3, RiftMath.cruxMilestones(kills, 2));
        assertEquals(3, RiftMath.cruxMilestones(kills, 6));
        assertEquals(9, RiftMath.cruxMilestones(kills, 7));
        assertEquals(RiftMath.CRUX_MOBS.size(), RiftMath.CRUX_ITEMS.size());
        assertEquals(0, RiftMath.cruxBonus(0, 0.0));
        assertEquals(1, RiftMath.cruxBonus(40, 0.39));
        assertEquals(0, RiftMath.cruxBonus(40, 0.41));
        assertEquals(1, RiftMath.cruxBonus(100, 0.99));
        assertEquals(2, RiftMath.cruxBonus(150, 0.2));
    }

    @Test
    void galleryScalesNecklaceByChargesSecured() {
        assertEquals(0, RiftMath.galleryScale(0, 8));
        assertEquals(0.5, RiftMath.galleryScale(4, 8));
        assertEquals(1, RiftMath.galleryScale(8, 8));
        assertEquals(1, RiftMath.galleryScale(12, 8));
        assertEquals(RiftCatalog.CHARMS.size(), 8);
        assertTrue(RiftCatalog.NPC_ANCHORS.contains("npc.curator"));
        assertTrue(RiftCatalog.NPC_ANCHORS.contains("npc.split_steal"));
    }
}
