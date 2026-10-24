package com.skyclone.mod.hollows;

import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.IslandRun;
import com.skyclone.gen.Walkability;
import com.skyclone.mod.hollows.gen.HollowsGenerator;
import com.skyclone.mod.hollows.gen.TunnelsGenerator;
import com.skyclone.mod.hollows.gen.Zones;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HollowsPureTest {

    @Test
    void zonesMatchTheMiningKeywords() {
        // the mining module reads a zone's gem from keywords in its id/name
        assertEquals(Zones.JUNGLE, Zones.at(-100, 100, -100));
        assertEquals(Zones.MITHRIL, Zones.at(100, 100, -100));
        assertEquals(Zones.PRECURSOR, Zones.at(100, 100, 100));
        assertEquals(Zones.GOBLIN, Zones.at(-100, 100, 100));
        assertEquals(Zones.MAGMA, Zones.at(0, 50, 0));
        assertEquals(Zones.NUCLEUS, Zones.at(0, 110, 0));
        assertTrue(Zones.JUNGLE.id().contains("jungle"));
        assertTrue(Zones.MITHRIL.id().contains("mithril"));
        assertTrue(Zones.PRECURSOR.id().contains("precursor"));
        assertTrue(Zones.GOBLIN.id().contains("goblin"));
        assertTrue(Zones.MAGMA.id().contains("magma"));
        assertEquals("AMETHYST", Zones.gemOfGlass(Zones.JUNGLE.glass()));
        assertEquals("JADE", Zones.gemOfGlass(Zones.MITHRIL.glass()));
        assertEquals("SAPPHIRE", Zones.gemOfGlass(Zones.PRECURSOR.glass()));
        assertEquals("AMBER", Zones.gemOfGlass(Zones.GOBLIN.glass()));
        assertEquals("TOPAZ", Zones.gemOfGlass(Zones.MAGMA.glass()));
        assertEquals("JASPER", Zones.gemOfGlass("magenta"));
    }

    @Test
    void magmawIsHitCounted() {
        // research (NEU BAL_BOSS): 200 health, 1 damage per hit, 5% flawed gem per hit -> 10 per fight
        assertEquals(200, Lore.hitsToKill(200, 1));
        assertEquals(10.0, Lore.expectedDrops(200, 1, 0.05), 1e-9);
        assertEquals(Integer.MAX_VALUE, Lore.hitsToKill(200, 0));
    }

    @Test
    void detectorGetsHotterAndHigher() {
        assertTrue(Lore.reading(1).contains("RIGHT HERE"));
        assertTrue(Lore.reading(50).contains("Cold"));
        assertTrue(Lore.pitch(1) > Lore.pitch(30));
        assertEquals(4, Lore.KEEPER_TOOLS.size());
        assertEquals(6, Lore.ROBOT_PARTS.size());
        assertEquals(5, Lore.QUESTS.size());
    }

    @Test
    void cavernsAreWalkableAndComplete() {
        var run = caverns();
        Walkability.Options o = new Walkability.Options();
        o.skipFloating = true;
        var report = Walkability.analyse(run.canvas, run.layout, o);
        assertTrue(report.ok(), report.text());
        Layout l = run.layout;
        for (String a : HollowsTests.CH_ANCHORS) assertNotNull(l.anchor(a), a);
        long souls = l.anchors().keySet().stream().filter(n -> n.startsWith("fairy_soul.")).count();
        assertTrue(souls >= 10 && souls <= 40, "souls " + souls);
        assertNotNull(l.area("portal.hub"));
        assertNotNull(l.area("portal.dwarven_mines"));
        // every "Find a Place" target and the Goblin Queen's den exist
        for (Waypoints.Place p : Waypoints.CAVERNS) assertNotNull(l.anchor(p.anchor()), "waypoint " + p.anchor());
        assertNotNull(l.area("boss.goblin_queen"));
        assertNotNull(l.area("mobs.goblin_queen_den"));
        // temple dart traps: 4 dispensers, alternating sides, each a dispenser block on the stair railing
        var traps = TempleTraps.parse(l.data().get("hollows.temple.traps"));
        assertEquals(4, traps.size(), "temple traps");
        for (int[] t : traps) assertTrue(run.canvas.get(t[0], t[1], t[2]).startsWith("dispenser"), "trap block " + run.canvas.get(t[0], t[1], t[2]));
        assertEquals(6, l.data().get("hollows.temple.stairs").split(",").length);
        assertTrue(l.zones().stream().anyMatch(z -> z.id().equals("goblin_queen_den") && z.id().contains("goblin")), "queen den zone");
    }

    // ---- wave 5 --------------------------------------------------------------------------------

    private static IslandRun.Result cavernsRun;

    /** One generation of the caverns shared by every test that needs it (27.7M blocks). */
    static synchronized IslandRun.Result caverns() {
        if (cavernsRun == null) cavernsRun = IslandRun.run(new HollowsGenerator(), IslandRun.seedOf("crystal_hollows"));
        return cavernsRun;
    }

    @Test
    void lobbyLifecycleMatchesResearch() {
        long day = 20 * 60_000L, opened = 1_000_000L;
        assertEquals(0, LobbyMath.day(opened - 5, opened, day));
        assertEquals(0, LobbyMath.day(opened + day - 1, opened, day));
        assertEquals(19, LobbyMath.day(opened + 19 * day, opened, day));
        assertEquals(LobbyMath.Stage.OPEN, LobbyMath.stage(18, 19, 35));
        assertEquals(LobbyMath.Stage.CLOSING, LobbyMath.stage(19, 19, 35));
        assertEquals(LobbyMath.Stage.SHUTDOWN, LobbyMath.stage(35, 19, 35));
        // after Day 19 the lobby closes once fewer than 4 remain; before it never does
        assertTrue(LobbyMath.shouldClose(LobbyMath.Stage.CLOSING, 3, 4));
        assertFalse(LobbyMath.shouldClose(LobbyMath.Stage.CLOSING, 4, 4));
        assertFalse(LobbyMath.shouldClose(LobbyMath.Stage.OPEN, 0, 4));
        assertTrue(LobbyMath.shouldClose(LobbyMath.Stage.SHUTDOWN, 40, 4));
        // newcomers: refused while closing unless they were here or a party member is inside
        assertTrue(LobbyMath.mayEnter(LobbyMath.Stage.OPEN, true, false, false));
        assertFalse(LobbyMath.mayEnter(LobbyMath.Stage.CLOSING, true, false, false));
        assertTrue(LobbyMath.mayEnter(LobbyMath.Stage.CLOSING, true, true, false));
        assertTrue(LobbyMath.mayEnter(LobbyMath.Stage.CLOSING, true, false, true));
        assertTrue(LobbyMath.mayEnter(LobbyMath.Stage.CLOSING, false, false, false));
        assertFalse(LobbyMath.mayEnter(LobbyMath.Stage.SHUTDOWN, false, true, true));
        // Day 19 at 20-minute days is 6h20m, Day 35 is 11h40m
        assertEquals("6h 20m", LobbyMath.span(LobbyMath.millisUntilDay(opened, opened, day, 19)));
        assertEquals("11h 40m", LobbyMath.span(LobbyMath.millisUntilDay(opened, opened, day, 35)));
        assertEquals("now", LobbyMath.span(LobbyMath.millisUntilDay(opened + 40 * day, opened, day, 35)));
    }

    private static boolean passable(String b) {
        return b == null || b.equals("air") || b.contains("pressure_plate") || b.startsWith("candle") || b.startsWith("ladder");
    }

    private static boolean standable(com.skyclone.gen.MemoryCanvas c, int x, int y, int z) {
        return !passable(c.get(x, y - 1, z)) && passable(c.get(x, y, z)) && passable(c.get(x, y + 1, z));
    }

    /** Standable cells reachable from a start, with moves up to {@code reach} blocks sideways (1 = walking only). */
    private static java.util.Set<Long> flood(com.skyclone.gen.MemoryCanvas c, int[] from, int reach) {
        java.util.Set<Long> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
        q.add(from);
        seen.add(com.skyclone.gen.util.Pack.key(from[0], from[1], from[2]));
        while (!q.isEmpty()) {
            int[] p = q.poll();
            for (int dx = -reach; dx <= reach; dx++)
                for (int dz = -reach; dz <= reach; dz++) {
                    int d2 = dx * dx + dz * dz;
                    if (d2 == 0 || (reach == 1 ? d2 != 1 : d2 > 10)) continue;   // a running jump clears about 3 blocks
                    for (int dy = -4; dy <= 1; dy++) {
                        if (dy == 1 && reach > 1 && d2 > 8) continue;           // jumping UP covers less ground
                        int x = p[0] + dx, y = p[1] + dy, z = p[2] + dz;
                        if (!standable(c, x, y, z)) continue;
                        if (seen.add(com.skyclone.gen.util.Pack.key(x, y, z))) q.add(new int[]{x, y, z});
                    }
                }
        }
        return seen;
    }

    private static int[] cell(Layout.Anchor a) { return new int[]{(int) Math.floor(a.x()), (int) Math.floor(a.y()), (int) Math.floor(a.z())}; }

    @Test
    void innerTempleIsAParkour() {
        var run = caverns();
        var c = run.canvas;
        Layout l = run.layout;
        assertEquals("1", l.data().get("hollows.temple.inner"));
        Layout.Anchor start = l.anchor("parkour.start"), guard = l.anchor("temple.guardian"), cp = l.anchor("parkour.cp.1");
        assertNotNull(start); assertNotNull(guard); assertNotNull(cp);
        assertNotNull(l.area("temple.exit")); assertNotNull(l.anchor("temple.exit_to"));
        int[] s = cell(start), g = cell(guard);
        assertTrue(standable(c, s[0], s[1], s[2]), "start pad");
        assertTrue(standable(c, g[0], g[1], g[2]), "guardian spot");
        long gk = com.skyclone.gen.util.Pack.key(g[0], g[1], g[2]);
        // walking alone never gets you across the pit...
        assertFalse(flood(c, s, 1).contains(gk), "the sanctum must not be walkable from the start pad");
        // ...but jumping does, by way of the checkpoint
        var jumps = flood(c, s, 3);
        assertTrue(jumps.contains(gk), "the parkour must be jumpable to the sanctum");
        int[] k = cell(cp);
        assertTrue(jumps.contains(com.skyclone.gen.util.Pack.key(k[0], k[1], k[2])), "checkpoint on the route");
        // and the inner temple is SEALED: its air never reaches outside a box around the pyramid
        java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        q.add(s);
        seen.add(com.skyclone.gen.util.Pack.key(s[0], s[1], s[2]));
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!q.isEmpty()) {
            int[] p = q.poll();
            assertTrue(p[0] > -140 && p[0] < -106 && p[2] > -140 && p[2] < -106 && p[1] > 68 && p[1] < 92,
                    "inner temple air leaks out at " + p[0] + "," + p[1] + "," + p[2]);
            for (int[] d : dirs) {
                int x = p[0] + d[0], y = p[1] + d[1], z = p[2] + d[2];
                if (!passable(c.get(x, y, z))) continue;
                if (seen.add(com.skyclone.gen.util.Pack.key(x, y, z))) q.add(new int[]{x, y, z});
            }
        }
        assertTrue(seen.size() > 300, "inner air " + seen.size());
    }

    @Test
    void varnasTreasureIsReallyBuried() {
        var run = caverns();
        Layout l = run.layout;
        assertNotNull(l.data().get("hollows.varnas.buried"));
        int n = 0;
        for (Layout.Anchor a : l.anchors().values()) {
            if (!a.name().startsWith("varnas.treasure.")) continue;
            n++;
            int[] p = cell(a);
            assertTrue(run.canvas.get(p[0], p[1] - 2, p[2]).startsWith("chest"), a.name() + " has " + run.canvas.get(p[0], p[1] - 2, p[2]));
            assertEquals("cracked_deepslate_tiles", run.canvas.get(p[0], p[1] - 1, p[2]), a.name() + " cover");
            assertTrue(passable(run.canvas.get(p[0], p[1], p[2])), a.name() + " open above");
            // sealed: rock on the other five sides
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
                assertFalse(passable(run.canvas.get(p[0] + d[0], p[1] - 2, p[2] + d[1])), a.name() + " chest side open");
            assertFalse(passable(run.canvas.get(p[0], p[1] - 3, p[2])), a.name() + " chest bottom open");
        }
        assertTrue(n >= 12, "treasure spots " + n);
    }

    @Test
    void compassPointsTheRightWay() {
        assertEquals("N", Waypoints.compass(0, -10));
        assertEquals("E", Waypoints.compass(10, 0));
        assertEquals("S", Waypoints.compass(0, 10));
        assertEquals("W", Waypoints.compass(-10, 0));
        assertEquals("SW", Waypoints.compass(-10, 10));
        assertEquals("NE", Waypoints.compass(10, -10));
    }

    @Test
    void tunnelsAreWalkableAndComplete() {
        var run = IslandRun.run(new TunnelsGenerator(), IslandRun.seedOf("glacite_tunnels"));
        Walkability.Options o = new Walkability.Options();
        o.skipFloating = true;
        var report = Walkability.analyse(run.canvas, run.layout, o);
        assertTrue(report.ok(), report.text());
        for (String a : HollowsTests.GT_ANCHORS) assertNotNull(run.layout.anchor(a), a);
        for (Waypoints.Place p : Waypoints.TUNNELS) assertNotNull(run.layout.anchor(p.anchor()), "waypoint " + p.anchor());
        assertNotNull(run.layout.area("base_camp"));
        long souls = run.layout.anchors().keySet().stream().filter(n -> n.startsWith("fairy_soul.")).count();
        assertTrue(souls >= 10 && souls <= 40, "souls " + souls);
    }
}
