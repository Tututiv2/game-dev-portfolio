package com.skyclone.mod.crimson;

import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.IslandRun;
import com.skyclone.gen.Walkability;
import com.skyclone.mod.crimson.gen.CrimsonIsleGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The island as the game builds it: every anchor walkable from spawn, every required anchor and area present. */
class CrimsonIsleGeneratorTest {

    @Test
    void islandIsWalkableAndComplete() {
        CrimsonIsleGenerator gen = new CrimsonIsleGenerator();
        IslandRun.Result r = IslandRun.run(gen, IslandRun.seedOf(gen.islandId()));
        assertEquals(0, r.outsideBounds, "writes outside bounds()");
        Layout l = r.layout;
        for (String a : List.of("spawn", "npc.crimson_guide", "npc.barbarian_leader", "npc.mage_leader", "npc.dojo_master",
                "npc.crimson_armorsmith", "npc.trophy_keeper", "npc.mage_envoy", "npc.barbarian_envoy", "boss.bladesoul",
                "boss.mage_outlaw", "boss.barbarian_duke", "boss.ashfang", "boss.magma_boss", "dojo.center", "warp.drakehold",
                "warp.carmine", "warp.tomb", "warp.skull"))
            assertNotNull(l.anchor(a), "anchor " + a);
        for (String a : List.of("portal.hub", "dojo.arena", "fishing.lava.crater", "fishing.lava.hut", "mobs.smoldering_tomb",
                "mobs.crimson_fields", "mobs.burning_desert", "mobs.mystic_marsh", "mobs.stronghold_grounds", "mobs.courtyard", "mobs.dukedom"))
            assertNotNull(l.area(a), "area " + a);
        long souls = l.anchors().keySet().stream().filter(k -> k.startsWith("fairy_soul.")).count();
        assertTrue(souls >= 10 && souls <= 40, "fairy souls " + souls);

        Walkability.Report rep = Walkability.analyse(r.canvas, l, new Walkability.Options());
        for (Walkability.AnchorResult a : rep.anchors)
            assertTrue(a.status() == Walkability.Status.REACHABLE || a.status() == Walkability.Status.NEAR || a.status() == Walkability.Status.SKIPPED,
                    a.name() + " is " + a.status() + " " + a.detail());
        for (Walkability.PortalResult p : rep.portals) assertTrue(p.reachable(), p.name() + " unreachable");
    }
}
