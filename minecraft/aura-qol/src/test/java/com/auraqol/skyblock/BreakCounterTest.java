package com.auraqol.skyblock;

import static com.auraqol.skyblock.BreakCounter.Kind.AIR;
import static com.auraqol.skyblock.BreakCounter.Kind.OTHER;
import static com.auraqol.skyblock.BreakCounter.Kind.SOLID;
import static com.auraqol.skyblock.BreakCounter.Kind.UNBREAKABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Simulated event sequences, in the order the client actually sees them: the
 * crosshair poll runs at the END of a tick, block-update packets are applied
 * at the START of the next, and a spread block can arrive before or after
 * the block you were mining.
 */
class BreakCounterTest {

    private static final String MITHRIL = "minecraft:light_blue_wool";
    private static final String BEDROCK = "minecraft:bedrock";
    private static final String GEM = "minecraft:red_stained_glass";
    private static final String AIRB = "minecraft:air";
    private static final String WHEAT = "minecraft:wheat";

    /** Hold attack on (x,y,z) for {@code ticks} ticks starting at t. Returns the time after. */
    private static long mine(BreakCounter c, long t, int ticks, int x, int y, int z) {
        for (int i = 0; i < ticks; i++) {
            c.aim(t, x, y, z);
            t += 50;
        }
        return t;
    }

    @Test
    void singleServerSideBreak() {
        BreakCounter c = BreakCounter.standard();
        long t = mine(c, 0, 10, 0, 64, 0);
        assertEquals(1, c.serverChange(t, 0, 64, 0, MITHRIL, SOLID, BEDROCK, UNBREAKABLE, true));
        // the poll sees the same change at the end of the tick: no second count
        assertEquals(0, c.primary(t + 10, 0, 64, 0));
        assertEquals(0, c.spreadTotal());
    }

    @Test
    void threeBlockSpreadArrivingAfterTheMinedBlock() {
        BreakCounter c = BreakCounter.standard();
        long t = mine(c, 0, 10, 0, 64, 0);
        int n = c.serverChange(t, 0, 64, 0, GEM, SOLID, AIRB, AIR, true);
        n += c.serverChange(t, 1, 64, 0, GEM, SOLID, AIRB, AIR, true);
        n += c.serverChange(t, 0, 65, 0, GEM, SOLID, AIRB, AIR, true);
        n += c.serverChange(t + 50, 0, 64, 1, GEM, SOLID, AIRB, AIR, true);
        assertEquals(4, n);
        assertEquals(3, c.spreadTotal());
    }

    @Test
    void spreadArrivingBeforeTheMinedBlockWaitsForIt() {
        BreakCounter c = BreakCounter.standard();
        long t = mine(c, 0, 10, 0, 64, 0);
        // Same packet batch, spread first. Attack is still held.
        assertEquals(0, c.serverChange(t, 2, 64, 0, MITHRIL, SOLID, BEDROCK, UNBREAKABLE, true));
        assertEquals(0, c.serverChange(t, 0, 63, 0, MITHRIL, SOLID, BEDROCK, UNBREAKABLE, true));
        // the block under the crosshair arrives: 1 + the 2 waiting spread blocks
        assertEquals(3, c.serverChange(t, 0, 64, 0, MITHRIL, SOLID, BEDROCK, UNBREAKABLE, true));
        assertEquals(2, c.spreadTotal());
    }

    @Test
    void sameSpreadBlockCountsOnce() {
        BreakCounter c = BreakCounter.standard();
        long t = mine(c, 0, 10, 0, 64, 0);
        c.serverChange(t, 0, 64, 0, GEM, SOLID, AIRB, AIR, true);
        assertEquals(1, c.serverChange(t, 1, 64, 0, GEM, SOLID, AIRB, AIR, true));
        // a re-sent update for the same position (air -> air would be from=AIR anyway,
        // but a resync that repeats the old state then the new one must not double)
        assertEquals(0, c.serverChange(t + 20, 1, 64, 0, GEM, SOLID, AIRB, AIR, true));
    }

    @Test
    void regenerationNeverCounts() {
        BreakCounter c = BreakCounter.standard();
        long t = mine(c, 0, 10, 0, 64, 0);
        assertEquals(1, c.serverChange(t, 0, 64, 0, MITHRIL, SOLID, BEDROCK, UNBREAKABLE, true));
        // keep holding attack on the bedrock while it regenerates, and around it
        t = mine(c, t, 10, 0, 64, 0);
        assertEquals(0, c.serverChange(t, 0, 64, 0, BEDROCK, UNBREAKABLE, MITHRIL, SOLID, true));
        assertEquals(0, c.serverChange(t, 1, 64, 0, BEDROCK, UNBREAKABLE, MITHRIL, SOLID, true));
        assertEquals(0, c.serverChange(t, 2, 64, 0, AIRB, AIR, GEM, SOLID, true));
    }

    @Test
    void anotherPlayersBreakTenBlocksAwayIsIgnored() {
        BreakCounter c = BreakCounter.standard();
        long t = mine(c, 0, 10, 0, 64, 0);
        // theirs arrives first, in the same tick as yours
        assertEquals(0, c.serverChange(t, 10, 64, 0, MITHRIL, SOLID, BEDROCK, UNBREAKABLE, true));
        assertEquals(1, c.serverChange(t, 0, 64, 0, MITHRIL, SOLID, BEDROCK, UNBREAKABLE, true));
        // and again just after yours
        assertEquals(0, c.serverChange(t + 50, 0, 64, 10, MITHRIL, SOLID, BEDROCK, UNBREAKABLE, true));
        assertEquals(0, c.spreadTotal());
    }

    @Test
    void nearbyChangeLongAfterYourBreakIsIgnored() {
        BreakCounter c = BreakCounter.standard();
        long t = mine(c, 0, 10, 0, 64, 0);
        c.serverChange(t, 0, 64, 0, MITHRIL, SOLID, BEDROCK, UNBREAKABLE, true);
        assertEquals(0, c.serverChange(t + 2000, 1, 64, 0, MITHRIL, SOLID, BEDROCK, UNBREAKABLE, false));
    }

    @Test
    void nothingCountsWhileNotMining() {
        BreakCounter c = BreakCounter.standard();
        // never aimed, attack not held: someone breaks a block right next to you
        assertEquals(0, c.serverChange(10_000, 1, 64, 0, GEM, SOLID, AIRB, AIR, false));
        assertEquals(0, c.serverChange(10_000, 0, 64, 0, GEM, SOLID, AIRB, AIR, false));
    }

    @Test
    void stateChangeOfTheSameBlockIsNotABreak() {
        BreakCounter c = BreakCounter.standard();
        long t = mine(c, 0, 10, 0, 64, 0);
        assertEquals(0, c.serverChange(t, 0, 64, 0, "minecraft:redstone_ore", SOLID,
                "minecraft:redstone_ore", SOLID, true));
    }

    @Test
    void gardenInstantBreaksCountExactlyOnceEach() {
        BreakCounter c = BreakCounter.standard();
        long t = 0;
        for (int i = 0; i < 20; i++) {
            c.aim(t, i, 70, 0);
            assertEquals(1, c.clientBreak(t, i, 70, 0, OTHER));
            // the server confirms the break the client already predicted: the
            // client world is air already, so the hook sees AIR -> AIR
            assertEquals(0, c.serverChange(t + 30, i, 70, 0, AIRB, AIR, AIRB, AIR, true));
            // Replenish replants it: air -> wheat
            assertEquals(0, c.serverChange(t + 40, i, 70, 0, AIRB, AIR, WHEAT, OTHER, true));
            // the poll fallback runs too and must not double it
            assertEquals(0, c.primary(t + 45, i, 70, 0));
            t += 50;
        }
        // the same replanted crop broken again quickly is a real second break
        assertEquals(1, c.clientBreak(t, 0, 70, 0, OTHER));
        assertEquals(0, c.spreadTotal());
    }

    @Test
    void gardenCactusColumnFallingIsNotSpread() {
        BreakCounter c = BreakCounter.standard();
        c.aim(0, 0, 70, 0);
        assertEquals(1, c.clientBreak(0, 0, 70, 0, OTHER));
        // the server pops the two cactus blocks above
        assertEquals(0, c.serverChange(20, 0, 71, 0, "minecraft:cactus", OTHER, AIRB, AIR, true));
        assertEquals(0, c.serverChange(20, 0, 72, 0, "minecraft:cactus", OTHER, AIRB, AIR, true));
    }

    @Test
    void clientSideMiningBreakStillAnchorsSpread() {
        // If a mining island ever lets the client break a block itself, spread
        // around it must still count.
        BreakCounter c = BreakCounter.standard();
        c.aim(0, 0, 64, 0);
        assertEquals(1, c.clientBreak(0, 0, 64, 0, SOLID));
        assertEquals(1, c.serverChange(30, 1, 64, 0, GEM, SOLID, AIRB, AIR, true));
        assertEquals(1, c.spreadTotal());
    }

    @Test
    void liquidsAndPlantsNearbyAreNotSpread() {
        BreakCounter c = BreakCounter.standard();
        long t = mine(c, 0, 10, 0, 64, 0);
        c.serverChange(t, 0, 64, 0, GEM, SOLID, AIRB, AIR, true);
        assertEquals(0, c.serverChange(t, 1, 64, 0, "minecraft:water", OTHER, AIRB, AIR, true));
        assertEquals(0, c.serverChange(t, 0, 65, 0, "minecraft:short_grass", OTHER, AIRB, AIR, true));
    }

    @Test
    void pollFallbackCountsWhenNoHookIsInstalled() {
        BreakCounter c = BreakCounter.standard();
        long t = mine(c, 0, 10, 0, 64, 0);
        assertEquals(1, c.primary(t, 0, 64, 0));
        assertEquals(0, c.primary(t + 50, 0, 64, 0));
    }

    @Test
    void steadyMiningWithSpreadAddsUp() {
        // 10 blocks in a row, each with one spread block beside it, 4 ticks apart.
        BreakCounter c = BreakCounter.standard();
        long t = 0;
        int total = 0;
        for (int i = 0; i < 10; i++) {
            t = mine(c, t, 4, i, 64, 0);
            total += c.serverChange(t, i, 65, 0, GEM, SOLID, AIRB, AIR, true);
            total += c.serverChange(t, i, 64, 0, GEM, SOLID, AIRB, AIR, true);
        }
        assertEquals(20, total);
        assertEquals(10, c.spreadTotal());
    }

    @Test
    void packIsUniqueForNegativeAndLargeCoordinates() {
        long a = BreakCounter.pack(-1, 64, -1);
        long b = BreakCounter.pack(1, 64, 1);
        long d = BreakCounter.pack(-1, -64, -1);
        assertEquals(3, java.util.Set.of(a, b, d).size());
    }
}
