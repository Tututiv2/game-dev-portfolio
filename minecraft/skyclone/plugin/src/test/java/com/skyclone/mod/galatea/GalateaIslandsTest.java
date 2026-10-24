package com.skyclone.mod.galatea;

import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.IslandRun;
import com.skyclone.gen.Walkability;
import com.skyclone.mod.galatea.gen.LotusAtollGenerator;
import com.skyclone.mod.galatea.gen.TorrhusCanyonGenerator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Both islands are generated with the shipping seed and flood-checked, the same as tools/preview.sh does. */
class GalateaIslandsTest {

    static Walkability.Report walk(IslandGenerator g, IslandRun.Result r) {
        return Walkability.analyse(r.canvas, r.layout, new Walkability.Options());
    }

    static int souls(Layout l) {
        int n = 0;
        for (String a : l.anchors().keySet()) if (a.startsWith("fairy_soul.")) n++;
        return n;
    }

    static boolean zone(Layout l, String id) {
        for (Layout.Zone z : l.zones()) if (z.id().equals(id)) return true;
        return false;
    }

    @Test void lotusAtollIsWalkableAndComplete() {
        IslandGenerator g = new LotusAtollGenerator();
        IslandRun.Result r = IslandRun.run(g, IslandRun.seedOf(g.islandId()));
        Walkability.Report w = walk(g, r);
        assertTrue(w.ok(), w.text());
        assertEquals(6, souls(r.layout), "research: 6 fairy souls on the Lotus Atoll");
        assertTrue(zone(r.layout, "lotus_atoll"));
        for (String z : List.of("lotus_atoll_landing", "lotus_atoll_lagoon", "lotus_atoll_highlands", "lotus_atoll_cave"))
            assertTrue(zone(r.layout, z) && z.startsWith("lotus_atoll"), z);
        assertNotNull(r.layout.area("portal.hub"));
        assertNotNull(r.layout.area("cave_pool"));
        assertEquals(2, r.layout.anchors().keySet().stream().filter(a -> a.startsWith("fishing.hotspot.")).count(), "research: 2 hotspots");
        assertEquals(0, r.outsideBounds, "every block inside bounds()");
    }

    @Test void torrentCanyonIsWalkableAndComplete() {
        IslandGenerator g = new TorrhusCanyonGenerator();
        IslandRun.Result r = IslandRun.run(g, IslandRun.seedOf(g.islandId()));
        Walkability.Report w = walk(g, r);
        assertTrue(w.ok(), w.text());
        assertEquals(12, souls(r.layout), "research: 12 fairy souls in Torrhus Canyon");
        for (String z : List.of("torrhus_canyon", "torrhus_canyon_springs", "torrhus_canyon_heights", "torrhus_canyon_ants", "torrhus_canyon_haven"))
            assertTrue(zone(r.layout, z), z);
        for (String a : List.of("mobs.parched.west", "mobs.parched.east", "mobs.grizzly", "mobs.ants", "portal.hub", "portal.moonmarsh"))
            assertNotNull(r.layout.area(a), a);
        assertNotNull(r.layout.anchor("warp.springs"));
        assertNotNull(r.layout.anchor("warp.safari"));
        assertTrue(r.layout.anchors().keySet().stream().anyMatch(a -> a.startsWith("tree.helix.")), "spiral tree anchors for foraging");
        assertEquals(0, r.outsideBounds, "every block inside bounds()");
    }

    /** Every NPC in npcs.json stands on an anchor its island actually has. */
    @Test void everyNpcHasItsAnchor() throws Exception {
        Path f = Path.of("src/main/resources/data/galatea/npcs.json");
        if (!Files.exists(f)) f = Path.of("plugin/src/main/resources/data/galatea/npcs.json");
        String json = Files.readString(f);
        IslandGenerator atoll = new LotusAtollGenerator(), canyon = new TorrhusCanyonGenerator();
        Layout la = IslandRun.run(atoll, IslandRun.seedOf(atoll.islandId())).layout;
        Layout lc = IslandRun.run(canyon, IslandRun.seedOf(canyon.islandId())).layout;
        Matcher m = Pattern.compile("\"island\":\\s*\"([a-z_]+)\",\\s*\"anchor\":\\s*\"([a-z_.]+)\"").matcher(json);
        int n = 0;
        while (m.find()) {
            Layout l = m.group(1).equals("lotus_atoll") ? la : lc;
            assertNotNull(l.anchor(m.group(2)), m.group(1) + " " + m.group(2));
            n++;
        }
        assertEquals(22, n, "npcs.json rows");
    }

    /**
     * Playtest wave 5: the Torrent Springs spot was a one-block bank over a 4-deep ring; a sea
     * creature's knockback put the player on the bottom and a TestPilot client (which never swims up)
     * drowned three times. At every bank fishing spot, no water within {@link #KNOCKBACK} blocks is
     * over a standing player's head, and every such water column walks back to the spot in steps of
     * at most one block. (The Atoll's pier spot is a railed deck and is not a bank.)
     */
    @Test void bankFishingSpotsCannotDrownYou() {
        IslandGenerator canyon = new TorrhusCanyonGenerator(), atoll = new LotusAtollGenerator();
        StringBuilder bad = new StringBuilder();
        IslandRun.Result rc = IslandRun.run(canyon, IslandRun.seedOf(canyon.islandId()));
        for (String a : List.of("fishing.spot.springs", "fishing.spot.haven")) bad.append(shelfProblems(rc.canvas, rc.layout.anchor(a)));
        IslandRun.Result ra = IslandRun.run(atoll, IslandRun.seedOf(atoll.islandId()));
        for (String a : List.of("fishing.spot.islet", "fishing.spot.cove")) bad.append(shelfProblems(ra.canvas, ra.layout.anchor(a)));
        assertTrue(bad.isEmpty(), bad.toString());
    }

    static final int KNOCKBACK = 5;

    /** The highest block a player stands on in this column, at or below {@code fromY}; water, plants and lily pads are not floors. */
    static int floor(com.skyclone.api.world.gen.VoxelCanvas c, int x, int fromY, int z) {
        for (int y = fromY; y > fromY - 60; y--) {
            com.skyclone.gen.BlockInfo b = com.skyclone.gen.BlockInfo.of(c.get(x, y, z));
            if (!b.liquid() && b.top16 >= 8 && !b.name.equals("lily_pad")) return y;
        }
        return Integer.MIN_VALUE;
    }

    static int water(com.skyclone.api.world.gen.VoxelCanvas c, int x, int floor, int z) {
        int n = 0;
        while (com.skyclone.gen.BlockInfo.of(c.get(x, floor + 1 + n, z)).water) n++;
        return n;
    }

    static String shelfProblems(com.skyclone.api.world.gen.VoxelCanvas c, Layout.Anchor a) {
        if (a == null) return "missing anchor; ";
        int ax = (int) Math.floor(a.x()), az = (int) Math.floor(a.z()), top = (int) Math.floor(a.y()) + 2;
        int r = KNOCKBACK + 3, side = 2 * r + 1;
        int[] fl = new int[side * side];
        for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) fl[(dz + r) * side + dx + r] = floor(c, ax + dx, top, az + dz);
        // walk out from the spot in one-block steps
        boolean[] seen = new boolean[side * side];
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        seen[r * side + r] = true;
        q.add(r * side + r);
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty()) {
            int i = q.poll(), x = i % side, z = i / side;
            for (int[] d : dirs) {
                int nx = x + d[0], nz = z + d[1];
                if (nx < 0 || nz < 0 || nx >= side || nz >= side) continue;
                int j = nz * side + nx;
                if (seen[j] || Math.abs(fl[j] - fl[i]) > 1) continue;
                seen[j] = true;
                q.add(j);
            }
        }
        StringBuilder out = new StringBuilder();
        for (int dz = -KNOCKBACK; dz <= KNOCKBACK; dz++) for (int dx = -KNOCKBACK; dx <= KNOCKBACK; dx++) {
            if (Math.hypot(dx, dz) > KNOCKBACK) continue;
            int i = (dz + r) * side + dx + r, x = ax + dx, z = az + dz;
            int w = water(c, x, fl[i], z);
            if (w == 0) continue;
            if (w > 1) out.append(a.name()).append(" ").append(x).append(",").append(z).append(" water ").append(w).append(" deep; ");
            else if (!seen[i]) out.append(a.name()).append(" ").append(x).append(",").append(z).append(" no one-block way back; ");
        }
        return out.toString();
    }
}
