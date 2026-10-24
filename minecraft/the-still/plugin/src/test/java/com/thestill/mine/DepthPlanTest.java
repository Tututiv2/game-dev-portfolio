package com.thestill.mine;

import com.thestill.economy.Money;
import com.thestill.economy.PriceTable;
import com.thestill.region.Cuboid;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the progression.
 *
 * <p>These are the assertions that turn a tuning mistake into a failed build
 * instead of a player who bought a rank and got poorer.
 */
class DepthPlanTest {

    private static final Map<Material, Long> PRICES = PriceTable.defaultPrices();

    private static double valueAt(int depth) {
        return DepthPlan.expectedValuePerBlock(depth, material ->
                PRICES.getOrDefault(material, 0L));
    }

    // ---- the one that matters -------------------------------------------

    @Test
    @DisplayName("every mine pays more per block than the mine above it")
    void incomeIsMonotonic() {
        List<String> regressions = new ArrayList<>();
        double previous = 0;
        int previousDepth = 0;
        for (int depth = 1; depth <= DepthPlan.DEPTH_COUNT; depth++) {
            // Plains are NOT skipped. A plains depth is still a mine that
            // respawns — only its shape and its mobs differ — so it has to earn
            // its place in the curve like every other depth.
            double value = valueAt(depth);
            if (previousDepth > 0 && value < previous) {
                regressions.add(String.format("D%d pays %s, down from %s at D%d",
                        depth, Money.format(Math.round(value)),
                        Money.format(Math.round(previous)), previousDepth));
            }
            previous = value;
            previousDepth = depth;
        }
        // A depth that pays less than the one above it means a player spent a
        // rank-up and their income went DOWN. Shadow Prison's live table has
        // exactly this shape between D14 and D18 - inheriting the ore weights
        // was right, inheriting the resulting curve was not.
        assertTrue(regressions.isEmpty(),
                "income regressions:\n  " + String.join("\n  ", regressions));
    }

    @Test
    @DisplayName("the climb from D1 to D26 is worth making")
    void incomeGrowsEnoughOverall() {
        double first = valueAt(1);
        double last = valueAt(DepthPlan.DEPTH_COUNT);
        assertTrue(last > first * 5,
                "D26 pays " + Money.format(Math.round(last)) + " against D1's "
                        + Money.format(Math.round(first)) + " — not enough reason to descend");
    }

    // ---- ore tables ------------------------------------------------------

    @Test
    @DisplayName("every depth is exactly 75 filler and 25 ore")
    void ratioIsFixed() {
        for (int depth = 1; depth <= DepthPlan.DEPTH_COUNT; depth++) {
            OreTable table = DepthPlan.tableFor(depth);
            assertEquals(100, table.totalWeight(), "D" + depth + " total weight");
            double fillerShare = table.shareOf(table.filler());
            assertEquals(0.75, fillerShare, 1e-9,
                    "D" + depth + " filler share — the 75/25 split is the constant every "
                            + "later multiplier is tuned against");
        }
    }

    @Test
    @DisplayName("every block a mine places drops something that is priced")
    void everyBlockIsSellable() {
        for (int depth = 1; depth <= DepthPlan.DEPTH_COUNT; depth++) {
            for (Material block : DepthPlan.tableFor(depth).weights().keySet()) {
                Material drop = DepthPlan.dropOf(block);
                assertTrue(PRICES.containsKey(drop),
                        "D" + depth + " places " + block + " which drops " + drop
                                + ", and nothing prices it — that block is worthless to mine");
            }
        }
    }

    @Test
    @DisplayName("filler blocks drop themselves")
    void fillersSelfDrop() {
        for (int depth = 1; depth <= DepthPlan.DEPTH_COUNT; depth++) {
            Material filler = DepthPlan.fillerFor(depth);
            // STONE yields COBBLESTONE and DEEPSLATE yields COBBLED_DEEPSLATE, so
            // either as filler means the block placed and the item priced differ.
            assertEquals(filler, DepthPlan.dropOf(filler),
                    "D" + depth + " filler " + filler + " does not drop itself");
        }
    }

    @Test
    @DisplayName("no ore vanishes and comes back")
    void oresRampInAndOutOnce() {
        // A new ore arriving before the previous one leaves is what makes going
        // deeper feel additive. An ore that disappears and returns reads as a bug.
        Map<Material, List<Integer>> appearances = new java.util.LinkedHashMap<>();
        for (int depth = 1; depth <= DepthPlan.DEPTH_COUNT; depth++) {
            for (Material block : DepthPlan.tableFor(depth).weights().keySet()) {
                if (block != DepthPlan.fillerFor(depth)) {
                    appearances.computeIfAbsent(block, m -> new ArrayList<>()).add(depth);
                }
            }
        }
        appearances.forEach((ore, depths) -> {
            int first = depths.get(0);
            int last = depths.get(depths.size() - 1);
            assertEquals(last - first + 1, depths.size(),
                    ore + " appears in a broken run: " + depths);
        });
    }

    // ---- geometry --------------------------------------------------------

    @Test
    @DisplayName("depths never overlap, whichever order they are created in")
    void depthsAreDisjoint() {
        List<Cuboid> boxes = new ArrayList<>();
        for (int depth = 1; depth <= DepthPlan.DEPTH_COUNT; depth++) {
            boxes.add(DepthPlan.boundsFor("world", depth));
        }
        for (int a = 0; a < boxes.size(); a++) {
            for (int b = a + 1; b < boxes.size(); b++) {
                assertFalse(boxes.get(a).intersects(boxes.get(b)),
                        "D" + (a + 1) + " overlaps D" + (b + 1));
            }
        }
    }

    @Test
    @DisplayName("a depth's coordinates do not depend on any other depth existing")
    void coordinatesArePinned() {
        // The Shadow Prison bug: positions came from iteration order over a map,
        // so deleting one mine physically moved every mine after it.
        Cuboid seven = DepthPlan.boundsFor("world", 7);
        assertEquals(DepthPlan.boundsFor("world", 7), seven, "same input, same box");
        assertEquals(DepthPlan.topYFor(7), seven.maxY(),
                "D7 sits at its own index, not at a position in a list");
    }

    @Test
    @DisplayName("the world descends: every depth is strictly below the one before it")
    void depthsDescend() {
        assertTrue(DepthPlan.FOG_Y < DepthPlan.RIG_Y, "the fog must be below the rigs");
        assertTrue(DepthPlan.topYFor(1) < DepthPlan.FOG_Y,
                "D1 must be under the fog — everything of value is beneath it");
        for (int depth = 2; depth <= DepthPlan.DEPTH_COUNT; depth++) {
            Cuboid above = DepthPlan.boundsFor("world", depth - 1);
            Cuboid here = DepthPlan.boundsFor("world", depth);
            assertTrue(here.maxY() < above.minY(),
                    "D" + depth + " is not below D" + (depth - 1) + " — the descent is the world");
        }
    }

    @Test
    @DisplayName("the whole descent fits inside the dimension")
    void descentFitsTheWorld() {
        // Engine limit for a custom dimension type: y in [-2032, 2031].
        assertTrue(DepthPlan.RIG_Y <= 2031,
                "the rigs are above the maximum build height");
        Cuboid deepest = DepthPlan.boundsFor("world", DepthPlan.DEPTH_COUNT);
        assertTrue(deepest.minY() > -2032,
                "D" + DepthPlan.DEPTH_COUNT + " is below bedrock at y=" + deepest.minY());
    }

    @Test
    @DisplayName("a refill never puts anyone back inside the ore it is replacing")
    void entryPointIsOutsideTheOre() {
        // This is where a mine refilling under a player's feet puts them. Inside
        // the ore body means being buried by the very fill that was trying to save
        // you, which is the one outcome this point exists to prevent.
        for (int depth = 1; depth <= DepthPlan.DEPTH_COUNT; depth++) {
            Cuboid ore = DepthPlan.oreBodyFor("world", depth);
            int[] landing = DepthPlan.caveLanding(depth);
            assertFalse(ore.contains(landing[0], landing[1], landing[2]),
                    "D" + depth + " lands players inside its own ore");
            // A body has to fit above the feet, too.
            assertFalse(ore.contains(landing[0], landing[1] + 1, landing[2]),
                    "D" + depth + " lands players with their head in the ore");
        }
    }

    @Test
    @DisplayName("D1 sits under the rig's drop hole")
    void landingIsUnderTheRig() {
        // The opening move of the game is stepping into the hole in the deck at
        // (0,0). The spiral used to include D1 and put it 64 blocks east of that,
        // so the drop went down the side of the Landing into 3,500 blocks of void.
        Cuboid landing = DepthPlan.boundsFor("world", 1);
        assertTrue(landing.contains(0, landing.maxY(), 0),
                "the rig's drop hole does not open onto D1");
        assertTrue(DepthPlan.isPlains(1), "the thing you fall onto has to be ground");
        Cuboid pit = DepthPlan.pitBoundsFor("world", 1);
        assertTrue(pit.contains(0, pit.maxY(), 0),
                "the drop should land in the Landing's quarry — the first ore you see");
    }

    @Test
    @DisplayName("a plains quarry nests inside its plain without touching the walls")
    void pitNestsInsideThePlain() {
        for (int depth = 1; depth <= DepthPlan.DEPTH_COUNT; depth++) {
            if (!DepthPlan.isPlains(depth)) {
                continue;
            }
            Cuboid plain = DepthPlan.boundsFor("world", depth);
            Cuboid pit = DepthPlan.pitBoundsFor("world", depth);
            // RegionIndex refuses a child that leaves its parent, so this failing
            // means /still setup depths throws rather than builds.
            assertTrue(plain.containsFully(pit),
                    "D" + depth + "'s quarry does not fit inside the plain");
            // And it must not fill it: the ring of ground left over is the only
            // reason a plains reads as a place rather than as a wider mine.
            assertTrue(pit.minX() > plain.minX() && pit.maxX() < plain.maxX(),
                    "D" + depth + "'s quarry reaches the edge of the plain");
            assertTrue(pit.maxY() < plain.maxY(),
                    "D" + depth + "'s quarry has no headroom above it");
            assertEquals(plain.minY(), pit.minY(),
                    "D" + depth + "'s quarry should bottom out on the plain's floor");
            assertEquals(DepthPlan.groundYFor(depth), pit.maxY(),
                    "D" + depth + "'s quarry should be LEVEL with the plain, not sunk into "
                            + "it — a step down made it read as a hole punched into somewhere "
                            + "else instead of as what is simply under this part of the ground");
        }
    }

    @Test
    @DisplayName("a cave mouth sits outside the ore, so a refill can never seal it")
    void caveMouthSurvivesARefill() {
        for (int depth = 1; depth < DepthPlan.DEPTH_COUNT; depth++) {
            Cuboid ore = DepthPlan.oreBodyFor("world", depth);
            int[] mouth = DepthPlan.caveMouth(depth);
            assertFalse(ore.contains(mouth[0], mouth[1], mouth[2]),
                    "D" + depth + "'s cave mouth is inside the ore — the first refill "
                            + "would brick the way down and nothing would report it");
            // Outside, but only just: it has to be reachable by breaking the wall.
            assertTrue(ore.expanded(1, 1).contains(mouth[0], mouth[1], mouth[2]),
                    "D" + depth + "'s cave mouth is not against the ore body's wall");
            assertTrue(mouth[1] > ore.minY() && mouth[1] < ore.maxY(),
                    "D" + depth + "'s cave mouth is not in the wall at all");
        }
    }

    @Test
    @DisplayName("every cave descends, and none of them ends inside the next depth's ore")
    void cavesDescendIntoOpenSpace() {
        for (int from = 1; from < DepthPlan.DEPTH_COUNT; from++) {
            int to = from + 1;
            int[] mouth = DepthPlan.caveMouth(from);
            int[] landing = DepthPlan.caveLanding(to);
            assertTrue(mouth[1] > landing[1],
                    "the cave from D" + from + " to D" + to + " does not go down");
            Cuboid toOre = DepthPlan.oreBodyFor("world", to);
            assertFalse(toOre.contains(landing[0], landing[1], landing[2]),
                    "the cave into D" + to + " arrives inside the ore");
        }
    }

    @Test
    @DisplayName("Plains are D1 the Landing, then every fifth depth")
    void plainsAreEveryFifth() {
        assertTrue(DepthPlan.isPlains(1), "D1 is the Landing — where you find your first pick");
        assertTrue(DepthPlan.isPlains(5), "D5 is the first session's payoff");
        assertTrue(DepthPlan.isPlains(10), "D10 is the Crossing");
        assertFalse(DepthPlan.isPlains(2), "D2 is the first mine");
        assertFalse(DepthPlan.isPlains(4));
        assertFalse(DepthPlan.isPlains(6));
        long Plains = java.util.stream.IntStream.rangeClosed(1, DepthPlan.DEPTH_COUNT)
                .filter(DepthPlan::isPlains).count();
        assertEquals(6, Plains, "expected 6 Plains across 26 depths");
        assertEquals("the Landing", DepthPlan.nameFor(1));
        assertEquals("the Crossing", DepthPlan.nameFor(10));
    }

    @Test
    @DisplayName("the first mine is reachable without having mined anything")
    void firstMineIsFree() {
        // D1 is the Landing and has no ore, so the money for the rank that opens
        // the first real mine has to come from its chests. If D2 needed a rank a
        // new player cannot afford, the game would be unstartable.
        int firstMine = 2;
        assertFalse(DepthPlan.isPlains(firstMine));
        assertTrue(DepthPlan.requiredRankFor(firstMine) <= 1,
                "the first mine must be at most one rung away from a standing start");
    }

    @Test
    @DisplayName("required rank rises one rung per depth")
    void rankGatingIsOnePerDepth() {
        assertEquals(0, DepthPlan.requiredRankFor(1), "D1 must be open to a brand-new player");
        for (int depth = 2; depth <= DepthPlan.DEPTH_COUNT; depth++) {
            assertEquals(DepthPlan.requiredRankFor(depth - 1) + 1,
                    DepthPlan.requiredRankFor(depth));
        }
    }

    // ---- the money-printer guard ----------------------------------------

    @Test
    @DisplayName("the default price list contains no craft exploits")
    void defaultsHaveNoMoneyPrinters() {
        // The bug this catches shipped live on Shadow Prison: compact_emerald was
        // priced 8100 against a correct 9 x 540, so nine emeralds bought a block
        // worth 3240 more than the emeralds.
        List<String> printers = PriceTable.findCraftPrinters(PRICES);
        assertTrue(printers.isEmpty(), "money printers:\n  " + String.join("\n  ", printers));
    }

    @Test
    @DisplayName("the guard actually detects a printer")
    void guardCatchesAPrinter() {
        Map<Material, Long> broken = new java.util.EnumMap<>(PRICES);
        broken.put(Material.COAL_BLOCK, PRICES.get(Material.COAL) * 12);
        List<String> printers = PriceTable.findCraftPrinters(broken);
        assertEquals(1, printers.size(), printers.toString());
        assertTrue(printers.get(0).contains("COAL_BLOCK"));
    }
}
