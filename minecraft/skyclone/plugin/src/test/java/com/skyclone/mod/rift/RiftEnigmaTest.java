package com.skyclone.mod.rift;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Wave 5: the Riddle Soul catalogue and the pure puzzle maths. */
class RiftEnigmaTest {

    @Test
    void fiftyTwoRiddlesSplitLikeTheSouls() {
        assertTrue(RiftEnigma.consistent(), "riddles per area must match RiftCatalog.SOULS_PER_AREA");
        Set<String> ids = new HashSet<>();
        for (RiftEnigma.Riddle r : RiftEnigma.RIDDLES) {
            assertTrue(ids.add(r.id()), "duplicate " + r.id());
            assertTrue(r.id().startsWith("rift_"), "reference task id " + r.id());
            if (r.kind() == RiftEnigma.Kind.SHOP) assertTrue(r.cost() > 0, r.id());
            if (r.npc() != null) assertTrue(RiftEnigma.NPCS.containsKey(r.npc()), r.id());
            assertFalse(r.task().isBlank(), r.id());
        }
        // at least 20 distinct kinds of puzzle (the research names ~24)
        Set<RiftEnigma.Kind> kinds = new HashSet<>();
        for (RiftEnigma.Riddle r : RiftEnigma.RIDDLES) kinds.add(r.kind());
        assertTrue(kinds.size() >= 20, "kinds " + kinds.size());
        // every kind is exactly one of pickup / reveal / grant
        for (RiftEnigma.Kind k : RiftEnigma.Kind.values())
            assertEquals(1, (k.pickup() ? 1 : 0) + (k.reveals() ? 1 : 0) + (k.grants() ? 1 : 0), k.name());
    }

    @Test
    void runesFaceTheSoul() {
        assertEquals(1, RiftEnigma.facingTowards(0, 0, 5, 1));     // east
        assertEquals(3, RiftEnigma.facingTowards(0, 0, -5, 1));    // west
        assertEquals(2, RiftEnigma.facingTowards(0, 0, 1, 5));     // south (+z)
        assertEquals(0, RiftEnigma.facingTowards(0, 0, 1, -5));    // north
    }

    @Test
    void aFullCircleSumsToTwoPi() {
        double sum = 0, last = 0;
        for (int i = 1; i <= 36; i++) {
            double a = Math.atan2(Math.sin(i * Math.PI / 18), Math.cos(i * Math.PI / 18));
            sum += RiftEnigma.angleStep(last, a);
            last = a;
        }
        assertEquals(2 * Math.PI, sum, 1e-9);
        // walking back and forth across the seam does not count as a lap
        assertEquals(0.2, RiftEnigma.angleStep(Math.PI - 0.1, -Math.PI + 0.1), 1e-9);
    }

    @Test
    void ubaldosGames() {
        assertEquals(5, RiftEnigma.averageTarget(List.of(4, 6, 5, 7, 3, 5), 5));
        assertEquals(6, RiftEnigma.averageTarget(List.of(9, 9, 1), 5));
        for (int prize = 0; prize < 3; prize++)
            for (int pick = 0; pick < 3; pick++) {
                int open = RiftEnigma.boxToOpen(prize, pick, 3);
                assertNotEquals(prize, open);
                assertNotEquals(pick, open);
            }
    }

    @Test
    void pharaohAndTheCloakMatchTheirLore() {
        // NEU FRACTURED_MONTEZUMA_SOUL;2 (1/9, RARE): Rift Time +25s; ;3 (6/9, EPIC): +100s, Mana Regen +12%
        assertEquals(25, RiftEnigma.catRiftTime(1));
        assertEquals(100, RiftEnigma.catRiftTime(6));
        assertEquals(0, RiftEnigma.catManaRegen(1));
        assertEquals(12, RiftEnigma.catManaRegen(6));
        assertEquals(2, RiftEnigma.catRarity(1));
        assertEquals(3, RiftEnigma.catRarity(6));
        assertEquals(0, RiftEnigma.catRiftTime(0));
        // Enigma Cloak: claimed every 4 souls, holds at most 42
        assertEquals(0, RiftEnigma.cloakClaimable(3));
        assertEquals(8, RiftEnigma.cloakClaimable(11));
        assertEquals(42, RiftEnigma.cloakClaimable(52));
        assertEquals(9, RiftEnigma.CAT_PIECES.size());
        assertEquals(7, RiftEnigma.EYES.size());
    }
}
