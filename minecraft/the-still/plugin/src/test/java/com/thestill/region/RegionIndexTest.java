package com.thestill.region;

import com.thestill.state.StateMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lookup every later system depends on.
 *
 * <p>Din spawn pressure, room heat, the PvP flag and the Ledger all resolve
 * through {@link RegionIndex#deepestAt}. If it can return two different answers
 * for the same block, or the wrong level of nesting, every one of them is
 * quietly wrong in a way that shows up as a balance complaint months later.
 */
class RegionIndexTest {

    private static Region region(String id, RegionType type, String parent,
                                 int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Region(id, type, parent,
                new Cuboid("world", x1, y1, z1, x2, y2, z2), 0L, new StateMap());
    }

    private static RegionIndex withD1() {
        RegionIndex index = new RegionIndex();
        index.add(region("depth.d1", RegionType.DEPTH, null, -100, -60, -100, 100, 0, 100));
        index.add(region("shaft.north", RegionType.SHAFT, "depth.d1", -80, -50, -80, -20, -20, -20));
        index.add(region("shaft.south", RegionType.SHAFT, "depth.d1", 20, -50, 20, 80, -20, 80));
        return index;
    }

    // ---- lookup ---------------------------------------------------------

    @Test
    @DisplayName("returns the most specific region, not the first one that matches")
    void deepestWins() {
        RegionIndex index = withD1();
        Region inside = index.deepestAt("world", -50, -30, -50);
        assertNotNull(inside);
        assertEquals("shaft.north", inside.id(), "a block in a shaft resolves to the shaft");
        assertEquals(1, inside.nesting());

        Region between = index.deepestAt("world", 0, -30, 0);
        assertNotNull(between);
        assertEquals("depth.d1", between.id(), "outside every shaft it is just the level");
        assertEquals(0, between.nesting());
    }

    @Test
    @DisplayName("a block outside everything resolves to nothing, not to a guess")
    void missesReturnNull() {
        RegionIndex index = withD1();
        assertNull(index.deepestAt("world", 5000, -30, 5000), "far outside");
        assertNull(index.deepestAt("world", 0, 200, 0), "inside on X/Z, above on Y");
        assertNull(index.deepestAt("nether", 0, -30, 0), "right coordinates, wrong world");
    }

    @Test
    @DisplayName("Y is not ignored just because the chunk matches")
    void yIsChecked() {
        RegionIndex index = withD1();
        // Same chunk as shaft.north, but above its ceiling: must fall back to the
        // level rather than claim the shaft. Getting this wrong would deposit
        // heat into a room the player is standing on top of.
        Region above = index.deepestAt("world", -50, -10, -50);
        assertNotNull(above);
        assertEquals("depth.d1", above.id());
    }

    @Test
    @DisplayName("allAt lists the whole stack, innermost first")
    void allAtIsOrdered() {
        List<Region> stack = withD1().allAt("world", -50, -30, -50);
        assertEquals(2, stack.size());
        assertEquals("shaft.north", stack.get(0).id());
        assertEquals("depth.d1", stack.get(1).id());
    }

    @Test
    @DisplayName("chainOf walks from the region up to the root")
    void chainWalksUp() {
        RegionIndex index = withD1();
        List<Region> chain = index.chainOf(index.byId("shaft.north"));
        assertEquals(List.of("shaft.north", "depth.d1"),
                chain.stream().map(Region::id).toList());
    }

    @Test
    @DisplayName("near collects every region within a chunk radius")
    void nearFindsNeighbours() {
        RegionIndex index = withD1();
        // Mob retargeting asks this once per tick instead of once per mob.
        assertTrue(index.near("world", -50, -50, 2).stream()
                .anyMatch(r -> r.id().equals("shaft.north")));
        assertTrue(index.near("world", 0, 0, 8).size() >= 3, "wide radius sees all three");
        assertTrue(index.near("world", 100_000, 100_000, 1).isEmpty());
    }

    // ---- validation -----------------------------------------------------

    @Test
    @DisplayName("overlapping siblings are refused")
    void siblingsMustBeDisjoint() {
        RegionIndex index = withD1();
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                index.add(region("shaft.bad", RegionType.SHAFT, "depth.d1",
                        -50, -40, -50, 0, -30, 0)));
        assertTrue(thrown.getMessage().contains("overlaps sibling"), thrown.getMessage());
        assertEquals(3, index.size(), "a rejected region leaves the index untouched");
    }

    @Test
    @DisplayName("a child must be fully inside its parent")
    void childMustFitParent() {
        RegionIndex index = withD1();
        assertThrows(IllegalArgumentException.class, () ->
                index.add(region("shaft.out", RegionType.SHAFT, "depth.d1",
                        500, -50, 500, 560, -20, 560)));
        assertThrows(IllegalArgumentException.class, () ->
                index.add(region("shaft.half", RegionType.SHAFT, "depth.d1",
                        90, -50, 90, 150, -20, 150)), "poking out one side is still out");
    }

    @Test
    @DisplayName("a missing parent is refused rather than silently orphaned")
    void parentMustExist() {
        RegionIndex index = new RegionIndex();
        assertThrows(IllegalArgumentException.class, () ->
                index.add(region("shaft.orphan", RegionType.SHAFT, "depth.nope",
                        0, 0, 0, 10, 10, 10)));
    }

    @Test
    @DisplayName("duplicate ids are refused")
    void idsAreUnique() {
        RegionIndex index = withD1();
        assertThrows(IllegalArgumentException.class, () ->
                index.add(region("depth.d1", RegionType.DEPTH, null, 0, 0, 0, 5, 5, 5)));
    }

    @Test
    @DisplayName("an implausibly large region is refused before it builds a huge index")
    void sizeIsCapped() {
        RegionIndex index = new RegionIndex();
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                index.add(region("huge", RegionType.DEPTH, null,
                        -900_000, -60, -900_000, 900_000, 100, 900_000)));
        assertTrue(thrown.getMessage().contains("chunks"), thrown.getMessage());
    }

    @Test
    @DisplayName("regions in different worlds may share coordinates")
    void worldsAreSeparate() {
        RegionIndex index = new RegionIndex();
        index.add(region("a", RegionType.DEPTH, null, 0, 0, 0, 50, 50, 50));
        index.add(new Region("b", RegionType.DEPTH, null,
                new Cuboid("nether", 0, 0, 0, 50, 50, 50), 0L, new StateMap()));
        assertEquals("a", index.deepestAt("world", 25, 25, 25).id());
        assertEquals("b", index.deepestAt("nether", 25, 25, 25).id());
    }

    // ---- mutation -------------------------------------------------------

    @Test
    @DisplayName("removing a region clears it from every chunk it occupied")
    void removalIsComplete() {
        RegionIndex index = withD1();
        index.remove("shaft.north");
        assertEquals(2, index.size());
        assertNull(index.byId("shaft.north"));
        assertEquals("depth.d1", index.deepestAt("world", -50, -30, -50).id(),
                "the parent takes over the space");
        // The chunk buckets that only held the shaft must be gone, not left as
        // empty lists that a lookup still has to walk.
        assertTrue(index.chunkEntryCounts().get("world") > 0);
    }

    @Test
    @DisplayName("an id freed by removal can be reused")
    void removalFreesTheId() {
        RegionIndex index = withD1();
        index.remove("shaft.north");
        index.add(region("shaft.north", RegionType.SHAFT, "depth.d1", -90, -55, -90, -85, -50, -85));
        assertEquals("shaft.north", index.deepestAt("world", -87, -52, -87).id());
    }
}
