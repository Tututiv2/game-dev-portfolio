package com.skyclone.mod.kuudra;

import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.IslandRun;
import com.skyclone.gen.MemoryCanvas;
import com.skyclone.gen.Walkability;
import com.skyclone.mod.kuudra.gen.BallistaShape;
import com.skyclone.mod.kuudra.gen.KuudraArenaGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The arena as the game builds it: complete, walkable, and its underground rooms standable. */
class KuudraArenaGeneratorTest {

    @Test
    void arenaIsCompleteAndWalkable() {
        KuudraArenaGenerator gen = new KuudraArenaGenerator();
        IslandRun.Result r = IslandRun.run(gen, IslandRun.seedOf(gen.islandId()));
        assertEquals(0, r.outsideBounds, "writes outside bounds()");
        assertNull(KTests.missing(r.layout));
        Walkability.Report rep = Walkability.analyse(r.canvas, r.layout, new Walkability.Options());
        assertNull(rep.spawnProblem);
        for (Walkability.AnchorResult a : rep.anchors)
            assertTrue(a.status() == Walkability.Status.REACHABLE || a.status() == Walkability.Status.NEAR || a.status() == Walkability.Status.SKIPPED,
                    a.name() + " is " + a.status() + " " + a.detail());
    }

    @Test
    void lairAndGulletHaveFloorsAndHeadroom() {
        KuudraArenaGenerator gen = new KuudraArenaGenerator();
        IslandRun.Result r = IslandRun.run(gen, IslandRun.seedOf(gen.islandId()));
        for (String a : new String[]{"lair.spawn", "stomach.spawn"}) {
            Layout.Anchor an = r.layout.anchor(a);
            int x = (int) Math.floor(an.x()), y = (int) Math.floor(an.y()), z = (int) Math.floor(an.z());
            assertTrue(!r.canvas.isAir(x, y - 1, z), a + " has no floor");
            assertTrue(r.canvas.isAir(x, y, z) && r.canvas.isAir(x, y + 1, z), a + " has no headroom");
        }
        // every organ is a real block, reachable by standing beside its pedestal
        for (String part : r.layout.data().get("kuudra.orbs").split(";")) {
            String[] p = part.split(",");
            assertEquals("ochre_froglight", r.canvas.get(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])));
        }
        // the beast's surfacing spots are in the lava lake
        for (int i = 0; i < 6; i++) {
            Layout.Anchor k = r.layout.anchor("kuudra.pos." + i);
            assertEquals("lava", r.canvas.get((int) Math.floor(k.x()), KuudraArenaGenerator.FLOOR, (int) Math.floor(k.z())), "kuudra.pos." + i);
        }
    }

    @Test
    void lairDropHasHeadroom() {
        // the collapse drops hunters KMath.LAIR_DROP blocks above lair.spawn (scattered +-2): open air all the way
        KuudraArenaGenerator gen = new KuudraArenaGenerator();
        IslandRun.Result r = IslandRun.run(gen, IslandRun.seedOf(gen.islandId()));
        Layout.Anchor an = r.layout.anchor("lair.spawn");
        int x0 = (int) Math.floor(an.x()), y0 = (int) Math.floor(an.y()), z0 = (int) Math.floor(an.z() - KMath.LAIR_DROP_INWARD);
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int y = y0; y <= y0 + (int) KMath.LAIR_DROP + 2; y++)
                    assertTrue(r.canvas.isAir(x0 + dx, y, z0 + dz), "lair drop blocked at " + (x0 + dx) + "," + y + "," + (z0 + dz));
    }

    @Test
    void beachIsDryAndWalkableFromTheRailGap() {
        KuudraArenaGenerator gen = new KuudraArenaGenerator();
        IslandRun.Result r = IslandRun.run(gen, IslandRun.seedOf(gen.islandId()));
        Layout.Anchor beach = r.layout.anchor("kuudra.beach");
        assertEquals(KuudraArenaGenerator.BEACH_Z, beach.z(), 1e-9);
        int f = KuudraArenaGenerator.FLOOR;
        // the beast's face lies over dry shore, with lava right behind it
        assertTrue(!"lava".equals(r.canvas.get(0, f, KuudraArenaGenerator.BEACH_Z - 1)), "the beach line is over lava");
        assertEquals("lava", r.canvas.get(0, f, KuudraArenaGenerator.BEACH_Z - 2));
        // and a hunter walks there from the platform through the gap in the rail: floor + two air at every step
        for (int z = -9; z >= KuudraArenaGenerator.BEACH_Z - 1; z--) {
            int top = r.canvas.topY(0, z, f - 1, f + 4);
            assertTrue(top != Integer.MIN_VALUE, "no floor at z " + z);
            assertTrue(r.canvas.isAir(0, top + 1, z) && r.canvas.isAir(0, top + 2, z), "no headroom at z " + z);
        }
    }

    @Test
    void ballistaBuildsBottomUp() {
        assertEquals(0, BallistaShape.partsAt(0));
        assertEquals(BallistaShape.built().size(), BallistaShape.partsAt(100));
        int prevY = 0;
        for (BallistaShape.Part p : BallistaShape.built()) {
            assertTrue(p.dy() >= prevY - 1, "part " + p + " placed before the layer under it");
            prevY = Math.max(prevY, p.dy());
        }
        MemoryCanvas c = new MemoryCanvas();
        for (BallistaShape.Part p : BallistaShape.built()) c.set(p.dx(), p.dy(), p.dz(), p.block());
        assertTrue(c.get(0, 4, -4).startsWith("end_rod"));
    }
}
