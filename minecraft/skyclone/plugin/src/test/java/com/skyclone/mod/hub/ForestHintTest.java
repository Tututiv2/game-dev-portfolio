package com.skyclone.mod.hub;

import com.skyclone.api.world.gen.Box;
import com.skyclone.mod.hub.gen.HubPlan;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wave-3 playtest: the Portal Plaza's trees look like the Forest's but are protected, and "Chop 20 logs in
 * the Hub's Forest" gives no direction. Swinging at a decorative tree now names the Forest's distance and
 * direction; these pin the words against the real plan.
 */
class ForestHintTest {

    @Test
    void fromThePortalPlazaTheForestIsWest() {
        Box forest = Box.of(-268, 0, -122, -176, 0, 110);      // HubWorld's forest box (the "forest" area's XZ)
        Box plaza = HubPlan.PORTALS;
        double cx = (plaza.minX() + plaza.maxX()) / 2.0, cz = (plaza.minZ() + plaza.maxZ()) / 2.0;
        String h = ForestHint.hint(forest, cx, cz);
        assertTrue(h.contains("to the &ewest"), h);
        int dist = (int) Math.round(cx - (forest.maxX() + 1));
        assertTrue(h.contains("&e" + dist + " blocks"), dist + " expected in: " + h);
        assertTrue(h.contains("west of the Portal Plaza"), h);
        // from the far side of the island the direction follows the player, not the plaza
        assertTrue(ForestHint.hint(forest, -220, 200).contains("to the &enorth"), ForestHint.hint(forest, -220, 200));
    }

    @Test
    void nearestPointAndLogs() {
        Box b = Box.of(0, 0, 0, 9, 0, 9);
        assertArrayEquals(new double[]{5, 5}, ForestHint.nearestXZ(b, 5, 5), 1e-9);
        assertArrayEquals(new double[]{10, 0}, ForestHint.nearestXZ(b, 30, -4), 1e-9);
        assertTrue(ForestHint.isLog(Material.OAK_LOG));
        assertTrue(ForestHint.isLog(Material.BIRCH_WOOD));
        assertFalse(ForestHint.isLog(Material.OAK_LEAVES));
        assertFalse(ForestHint.isLog(Material.OAK_PLANKS));
    }
}
