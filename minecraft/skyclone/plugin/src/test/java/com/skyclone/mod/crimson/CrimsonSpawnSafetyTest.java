package com.skyclone.mod.crimson;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.IslandRun;
import com.skyclone.mod.crimson.gen.CrimsonIsleGenerator;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wave-4 playtest: the Mystic Marsh spawn box swallowed Cinder Shore's lava pool and a Lv80 Mushroom Bull walked up
 * to a fisher. Fishing spots and the Belly must be mob-free, and no spawn box may sit within aggro range of the pool.
 */
class CrimsonSpawnSafetyTest {

    /** core's mob.aggro-range default. */
    private static final double AGGRO = 16;

    @Test
    void fishingShoreAndBellyAreMobFree() {
        CrimsonIsleGenerator gen = new CrimsonIsleGenerator();
        Layout l = IslandRun.run(gen, IslandRun.seedOf(gen.islandId())).layout;

        Box pool = l.area("fishing.lava.hut");
        assertNotNull(pool, "fishing.lava.hut");
        Layout.Zone shore = zone(l, "crimson_lava_hut");
        assertTrue(shore.flags().contains(Layout.Zone.NO_MOBS), "Cinder Shore must be NO_MOBS");
        assertTrue(contains(shore.box(), pool), "Cinder Shore zone must cover the whole pool");
        // the smallest zone wins, so nothing smaller may cover the pool without the flag
        for (Layout.Zone z : l.zones())
            if (z.box().intersects(pool) && z.box().volume() < shore.box().volume())
                assertTrue(z.flags().contains(Layout.Zone.NO_MOBS) || z.flags().contains(Layout.Zone.SAFE), z.id() + " overrides Cinder Shore");

        Layout.Zone belly = zone(l, "crimson_belly");
        assertTrue(belly.flags().contains(Layout.Zone.NO_MOBS), "Belly of the Beast must be NO_MOBS");

        for (Map.Entry<String, Box> e : l.areas().entrySet()) {
            if (!e.getKey().startsWith("mobs.")) continue;
            double d = xzGap(e.getValue(), pool);
            assertTrue(d > AGGRO, e.getKey() + " is " + Math.round(d) + " blocks from the Cinder Shore pool (aggro range " + AGGRO + ")");
        }
        double marsh = xzGap(l.area("mobs.mystic_marsh"), pool);
        assertTrue(marsh >= 24, "mobs.mystic_marsh only " + marsh + " blocks from the pool");
    }

    private static Layout.Zone zone(Layout l, String id) {
        for (Layout.Zone z : l.zones()) if (z.id().equals(id)) return z;
        throw new AssertionError("zone " + id);
    }

    private static boolean contains(Box outer, Box in) {
        return outer.minX() <= in.minX() && outer.maxX() >= in.maxX() && outer.minZ() <= in.minZ() && outer.maxZ() >= in.maxZ()
                && outer.minY() <= in.minY() && outer.maxY() >= in.maxY();
    }

    /** Horizontal distance between two boxes (0 when they overlap in x/z). */
    private static double xzGap(Box a, Box b) {
        double dx = Math.max(0, Math.max(a.minX() - b.maxX(), b.minX() - a.maxX()));
        double dz = Math.max(0, Math.max(a.minZ() - b.maxZ(), b.minZ() - a.maxZ()));
        return Math.sqrt(dx * dx + dz * dz);
    }
}
