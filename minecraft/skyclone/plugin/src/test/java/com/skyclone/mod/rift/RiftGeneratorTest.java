package com.skyclone.mod.rift;

import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.IslandRun;
import com.skyclone.gen.Walkability;
import com.skyclone.mod.rift.gen.RiftGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the real Rift generator offline (exactly what the game builds) and checks the layout the module relies on. */
class RiftGeneratorTest {

    static IslandRun.Result run;
    static Walkability.Report walk;

    @BeforeAll
    static void generate() {
        RiftGenerator gen = new RiftGenerator();
        run = IslandRun.run(gen, IslandRun.seedOf(gen.islandId()));
        Walkability.Options o = new Walkability.Options();
        o.skipFloating = true;
        walk = Walkability.analyse(run.canvas, run.layout, o);
    }

    @Test
    void everyAnchorAndAreaTheModuleNeedsExists() {
        Layout l = run.layout;
        List<String> miss = new ArrayList<>();
        for (String a : RiftCatalog.NPC_ANCHORS) if (l.anchor(a) == null) miss.add(a);
        for (String a : RiftCatalog.POINT_ANCHORS) if (l.anchor(a) == null) miss.add(a);
        for (String a : RiftCatalog.MOB_AREAS) if (l.area(a) == null) miss.add(a);
        for (String a : RiftCatalog.OTHER_AREAS) if (l.area(a) == null) miss.add(a);
        for (int i = 1; i <= RiftCatalog.EGGS; i++) if (l.anchor("egg." + i) == null) miss.add("egg." + i);
        for (int i = 1; i <= RiftCatalog.HEART_SPOTS; i++) if (l.anchor("heart." + i) == null) miss.add("heart." + i);
        for (int i = 1; i <= RiftCatalog.RACE_CHECKPOINTS; i++) if (l.anchor("race." + i) == null) miss.add("race." + i);
        assertTrue(miss.isEmpty(), "missing " + miss);
    }

    @Test
    void all52SoulsArePlacedWithHintsInTheirArea() {
        Layout l = run.layout;
        for (int n = 1; n <= RiftCatalog.totalSouls(); n++) {
            assertNotNull(l.anchor("soul." + n), "soul." + n);
            assertNotNull(l.data().get("soul." + n + ".hint"), "hint for soul." + n);
            assertEquals(RiftCatalog.soulArea(n), l.data().get("soul." + n + ".area"), "area of soul." + n);
        }
    }

    @Test
    void walkableFromSpawnWithNoBuriedAnchors() {
        assertNull(walk.spawnProblem, walk.spawnProblem);
        assertTrue(walk.failed().isEmpty(), "unreachable/buried: " + walk.failed());
        assertTrue(walk.failedPortals().isEmpty(), "portals: " + walk.failedPortals());
        assertEquals(0, run.outsideBounds, "writes outside bounds: " + run.outsideSamples);
    }

    @Test
    void everyAreaHasItsGatedZone() {
        for (RiftCatalog.Area a : RiftCatalog.AREAS) {
            boolean found = false;
            for (Layout.Zone z : run.layout.zones()) {
                if (!z.id().equals(a.id())) continue;
                found = true;
                assertEquals(a.requirementKey(), z.requirement(), a.id());
                assertTrue(z.flags().contains(Layout.Zone.NO_FALL), a.id() + " NO_FALL (research: no fall damage in the Rift)");
            }
            assertTrue(found, "zone " + a.id());
        }
    }

    /** Wave 5: every soul carries its riddle, and the riddles that need props got them. */
    @Test
    void everyRiddleHasItsSoulAndItsProps() {
        Layout l = run.layout;
        java.util.Map<String, String> soulOf = new java.util.HashMap<>();
        for (int n = 1; n <= RiftCatalog.totalSouls(); n++) {
            String r = l.data().get("soul." + n + ".riddle");
            assertNotNull(r, "riddle of soul." + n);
            RiftEnigma.Riddle rd = RiftEnigma.riddle(r);
            assertNotNull(rd, r);
            assertEquals(rd.area(), l.data().get("soul." + n + ".area"), "riddle " + r + " is in its own area");
            assertNull(soulOf.put(r, "soul." + n), "riddle placed twice: " + r);
        }
        assertEquals(RiftEnigma.RIDDLES.size(), soulOf.size());
        List<String> bad = new ArrayList<>();
        for (RiftEnigma.Riddle r : RiftEnigma.RIDDLES) {
            String s = soulOf.get(r.id());
            switch (r.kind()) {
                case POTS -> { if (l.anchor(s + ".pot.2") == null) bad.add(r.id() + " pots"); }
                case BALLOONS -> { if (l.anchor(s + ".balloon.3") == null) bad.add(r.id() + " balloons"); }
                case RUNES -> { if (l.anchor(s + ".rune.2") == null) bad.add(r.id() + " runes"); }
                case SPOOK -> { if (l.anchor(s + ".spook") == null) bad.add(r.id() + " spook"); }
                case CAKE -> { if (l.anchor(s + ".cake") == null) bad.add(r.id() + " cake"); }
                case PLATES -> { if (l.anchor(s + ".plate.2") == null || l.data().get(s + ".cage") == null) bad.add(r.id() + " plates"); }
                case BEACON -> { if (l.anchor(s + ".hopper") == null) bad.add(r.id() + " beacon"); }
                case WALL -> { if (l.data().get(s + ".cage") == null) bad.add(r.id() + " wall"); }
                case FALL -> { if (l.data().get(s + ".pillar") == null) bad.add(r.id() + " pillar"); }
                default -> { }
            }
            if (r.npc() != null && l.anchor(RiftEnigma.npcAnchor(r.npc())) == null) bad.add(r.id() + " npc");
        }
        for (int i = 1; i <= RiftCatalog.CAT_PIECES; i++) if (l.anchor("cat." + i) == null) bad.add("cat." + i);
        for (int i = 1; i <= RiftCatalog.EYES; i++) if (l.anchor("eye." + i) == null) bad.add("eye." + i);
        for (int i = 1; i <= 8; i++) if (l.anchor("riddle.button." + i) == null) bad.add("riddle.button." + i);
        assertTrue(bad.isEmpty(), "missing props: " + bad);
    }

    @Test
    void generatorIsDeterministic() {
        RiftGenerator gen = new RiftGenerator();
        IslandRun.Result again = IslandRun.run(gen, IslandRun.seedOf(gen.islandId()));
        assertEquals(run.layout.anchors().keySet(), again.layout.anchors().keySet());
        assertEquals(run.layout.anchor("soul.30"), again.layout.anchor("soul.30"));
        assertEquals(run.layout.anchor("mote.10"), again.layout.anchor("mote.10"));
    }
}
