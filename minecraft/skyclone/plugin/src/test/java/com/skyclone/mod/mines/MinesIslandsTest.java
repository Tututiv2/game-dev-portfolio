package com.skyclone.mod.mines;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.IslandRun;
import com.skyclone.gen.Walkability;
import com.skyclone.mod.mines.gen.DeepCavernsGenerator;
import com.skyclone.mod.mines.gen.DwarvenMinesGenerator;
import com.skyclone.mod.mines.gen.GoldMineGenerator;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the three real generators offline and checks what the rest of the game depends on. */
class MinesIslandsTest {

    private static final Map<String, IslandRun.Result> RUNS = new HashMap<>();

    private static IslandRun.Result run(IslandGenerator g) {
        return RUNS.computeIfAbsent(g.islandId(), k -> IslandRun.run(g, IslandRun.seedOf(k)));
    }

    private static List<IslandGenerator> gens() {
        return List.of(new GoldMineGenerator(), new DeepCavernsGenerator(), new DwarvenMinesGenerator());
    }

    @Test
    void everyAnchorAndPortalIsWalkableFromSpawn() {
        for (IslandGenerator g : gens()) {
            IslandRun.Result r = run(g);
            Walkability.Report w = Walkability.analyse(r.canvas, r.layout, new Walkability.Options());
            assertTrue(w.ok(), g.islandId() + " walkability: " + w.failed() + " " + w.failedPortals() + " " + w.spawnProblem);
            assertEquals(0, r.outsideBounds, g.islandId() + " wrote outside its bounds");
        }
    }

    /** Playtest 2026-10-03: the Goblin Burrows cart dropped the player into ~30 goblins and a Lv200. */
    @Test
    void everyStationAndLiftStopIsSafe() {
        Layout dm = run(new DwarvenMinesGenerator()).layout;
        for (String st : DwarvenMinesGenerator.STATIONS) {
            for (String a : List.of("station." + st, "npc.ticket." + st)) {
                Layout.Anchor an = dm.anchor(a);
                assertNotNull(an, "dwarven_mines anchor " + a);
                assertTrue(DwarvenMinesGenerator.safeAt(dm, an.x(), an.y(), an.z()), "dwarven_mines " + a + " is not in a SAFE zone");
            }
        }
        Layout dc = run(new DeepCavernsGenerator()).layout;
        for (int n = 1; n <= Places.layers(); n++) {
            Layout.Anchor an = dc.anchor("lift." + n);
            assertNotNull(an, "deep_caverns lift." + n);
            assertTrue(DwarvenMinesGenerator.safeAt(dc, an.x(), an.y(), an.z()), "deep_caverns lift." + n + " is not in a SAFE zone");
        }
    }

    @Test
    void villageRegularsAndHangingCourtAreSafe() {
        // wave 3: every talking NPC of the village, tavern, library and palace stands in a SAFE zone, and the
        // Hanging Court is its own named SAFE zone in front of the keep (between the guards).
        Layout dm = run(new DwarvenMinesGenerator()).layout;
        for (String a : List.of("npc.fetcher", "npc.riddlewright", "npc.drill_mechanic", "npc.barkeep", "npc.archivist",
                "npc.guard.1", "npc.guard.2", "npc.old_miner")) {
            Layout.Anchor an = dm.anchor(a);
            assertNotNull(an, a);
            assertTrue(DwarvenMinesGenerator.safeAt(dm, an.x(), an.y(), an.z()), a + " is not in a SAFE zone");
        }
        Layout.Zone court = dm.zones().stream().filter(z -> z.id().equals("hanging_court")).findFirst().orElse(null);
        assertNotNull(court, "hanging_court zone");
        assertTrue(court.flags().contains(Layout.Zone.SAFE));
        Layout.Anchor g = dm.anchor("npc.guard.1");
        assertTrue(court.box().contains(g.x(), g.y(), g.z()), "guard stands in the Hanging Court");
    }

    @Test
    void requiredAnchorsPortalsAndOreAreasExist() {
        for (IslandGenerator g : gens()) {
            Layout l = run(g).layout;
            for (String a : Places.requiredAnchors(g.islandId())) assertNotNull(l.anchor(a), g.islandId() + " anchor " + a);
            for (String a : Places.requiredPortals(g.islandId())) assertNotNull(l.area(a), g.islandId() + " portal " + a);
            for (String a : Places.requiredOreAreas(g.islandId())) assertNotNull(l.area(a), g.islandId() + " ore area " + a);
        }
    }

    /** Wave 5: exactly the reference's souls per island (NEU fairy_souls.json: mining_1 12, mining_2 21, mining_3 15). */
    @Test
    void fairySoulsMatchTheReference() {
        for (IslandGenerator g : gens()) {
            long n = run(g).layout.anchors().keySet().stream().filter(k -> k.startsWith("fairy_soul.")).count();
            assertEquals(Places.researchSouls(g.islandId()), n, g.islandId() + " fairy souls");
        }
    }

    // ---- wave 5: real rides and the skipped sub-areas ----

    @Test
    void cartLinesReachEveryStationOnLaidRail() {
        IslandRun.Result r = run(new DwarvenMinesGenerator());
        Layout l = r.layout;
        assertEquals("", l.data().get("rail.missing"), "stations the track does not reach");
        int[] depot = null;
        for (String st : DwarvenMinesGenerator.STATIONS) {
            List<int[]> line = com.skyclone.mod.mines.gen.RailRoute.decode(l.data().get("rail." + st));
            assertFalse(line.isEmpty(), "no line to " + st);
            assertTrue(com.skyclone.mod.mines.gen.RailRoute.contiguous(line), st + " line is not contiguous");
            if (depot == null) depot = line.get(0);
            assertTrue(depot[0] == line.get(0)[0] && depot[2] == line.get(0)[2], st + " line does not start at the depot");
            for (int[] q : line) {
                String rail = r.canvas.get(q[0], q[1] + 1, q[2]);
                assertTrue(rail != null && rail.startsWith("rail"), st + ": no rail at " + q[0] + "," + (q[1] + 1) + "," + q[2] + " (" + rail + ")");
                for (int y = q[1] + 2; y <= q[1] + 3; y++) assertTrue(r.canvas.isAir(q[0], y, q[2]), st + ": no headroom at " + q[0] + "," + y + "," + q[2]);
                for (Map.Entry<String, com.skyclone.api.world.gen.Box> e : l.areas().entrySet())
                    if (e.getKey().startsWith("portal.")) assertFalse(e.getValue().contains(q[0], q[1] + 1, q[2]), st + " line runs through " + e.getKey());
            }
        }
        // every pair of stations joins into one rideable track that starts and ends at the two buffers
        for (String a : DwarvenMinesGenerator.STATIONS)
            for (String b : DwarvenMinesGenerator.STATIONS) {
                if (a.equals(b)) continue;
                List<int[]> la = com.skyclone.mod.mines.gen.RailRoute.decode(l.data().get("rail." + a));
                List<int[]> lb = com.skyclone.mod.mines.gen.RailRoute.decode(l.data().get("rail." + b));
                List<int[]> ride = com.skyclone.mod.mines.gen.RailRoute.join(la, lb);
                assertNotNull(ride, a + "->" + b);
                assertTrue(com.skyclone.mod.mines.gen.RailRoute.contiguous(ride), a + "->" + b + " is not contiguous");
                assertTrue(java.util.Arrays.equals(ride.get(0), la.get(la.size() - 1)) && java.util.Arrays.equals(ride.get(ride.size() - 1), lb.get(lb.size() - 1)), a + "->" + b + " ends");
            }
    }

    @Test
    void liftShaftsAreClearForTheCage() {
        IslandRun.Result dc = run(new DeepCavernsGenerator());
        double[] s = Travel.parseShaft(dc.layout.data().get("lift.shaft"));
        assertNotNull(s, "deep_caverns lift.shaft");
        int x = (int) Math.floor(s[0]), z = (int) Math.floor(s[1]);
        for (int y = (int) s[2]; y <= DeepCavernsGenerator.FLOOR[0] + 3; y++) {
            if (y == DeepCavernsGenerator.FLOOR[0]) continue;                       // the parked cage's floor
            assertTrue(dc.canvas.isAir(x, y, z), "deep_caverns shaft blocked at y" + y);
        }
        assertFalse(dc.canvas.isAir(x, (int) s[2] - 1, z), "the shaft has a floor at the bottom");
        IslandRun.Result dm = run(new DwarvenMinesGenerator());
        double[] w = Travel.parseShaft(dm.layout.data().get("lift.dwarven.shaft"));
        assertNotNull(w, "dwarven_mines lift.dwarven.shaft");
        assertTrue(w.length == 4 && w[3] - w[2] >= 40, "The Lift's shaft height");
        for (int y = (int) w[2]; y <= (int) w[3] + 2; y++)
            assertTrue(dm.canvas.isAir((int) Math.floor(w[0]), y, (int) Math.floor(w[1])), "The Lift's shaft blocked at y" + y);
    }

    @Test
    void skippedSubAreasNowExistAndAreSafe() {
        Layout dm = run(new DwarvenMinesGenerator()).layout;
        java.util.Set<String> zones = new java.util.HashSet<>();
        for (Layout.Zone z : dm.zones()) zones.add(z.id());
        for (String z : List.of("the_lift", "forge_basin", "gates_to_the_mines", "cart_depot", "house_a")) assertTrue(zones.contains(z), "zone " + z);
        for (String a : List.of("npc.lift.dwarven", "lift.dwarven", "npc.gwendolyn", "npc.guild")) {
            Layout.Anchor an = dm.anchor(a);
            assertNotNull(an, a);
            assertTrue(DwarvenMinesGenerator.safeAt(dm, an.x(), an.y(), an.z()), a + " is not in a SAFE zone");
        }
    }

    @Test
    void railRouteEncodesJoinsAndShapes() {
        List<int[]> line = List.of(new int[]{0, 5, 0}, new int[]{1, 5, 0}, new int[]{1, 6, 1}, new int[]{1, 6, 2}, new int[]{2, 6, 2});
        String enc = com.skyclone.mod.mines.gen.RailRoute.encode(line);
        assertNotNull(enc);
        List<int[]> back = com.skyclone.mod.mines.gen.RailRoute.decode(enc);
        assertEquals(line.size(), back.size());
        for (int i = 0; i < line.size(); i++) assertTrue(java.util.Arrays.equals(line.get(i), back.get(i)), "round trip " + i);
        // a line that turns into a rise is not railable: (1,5,0) -> (1,6,1) turns south AND climbs; shape says ascending
        assertEquals("ascending_south", com.skyclone.mod.mines.gen.RailRoute.shape(line, 1));
        assertEquals("east_west", com.skyclone.mod.mines.gen.RailRoute.shape(line, 0));
        assertEquals("north_east", com.skyclone.mod.mines.gen.RailRoute.shape(line, 3));
        // join: two lines sharing the first two cells ride back to the fork and out again
        List<int[]> a = List.of(new int[]{0, 0, 0}, new int[]{1, 0, 0}, new int[]{2, 0, 0});
        List<int[]> b = List.of(new int[]{0, 0, 0}, new int[]{1, 0, 0}, new int[]{1, 0, 1});
        List<int[]> ab = com.skyclone.mod.mines.gen.RailRoute.join(a, b);
        assertEquals(3, ab.size());
        assertTrue(java.util.Arrays.equals(ab.get(0), new int[]{2, 0, 0}) && java.util.Arrays.equals(ab.get(2), new int[]{1, 0, 1}));
        assertNull(com.skyclone.mod.mines.gen.RailRoute.encode(List.of(new int[]{0, 0, 0}, new int[]{2, 0, 0})), "a gap is not encodable");
    }

    @Test
    void plannerNeverTurnsOnASlopeOrRailsAValley() {
        // a 9x3 corridor: floor 5 everywhere except a dip at x=4 (floor 4). Straight through needs down then up at
        // x=4, which vanilla rails cannot do, so the only way is around... there is none, so no line.
        com.skyclone.mod.mines.gen.RailPlanner p = new com.skyclone.mod.mines.gen.RailPlanner(0, 0, 8, 0,
                (x, z) -> x == 4 ? 4 : 5, (x, z) -> 0);
        List<List<int[]>> out = p.plan(0, 0, 1, List.of(new com.skyclone.mod.mines.gen.RailPlanner.Target("t", 8, 0, 1, 5)));
        assertNull(out.get(0), "a one-block dip cannot be railed");
        // a step up then flat is fine, and the line climbs straight
        com.skyclone.mod.mines.gen.RailPlanner q = new com.skyclone.mod.mines.gen.RailPlanner(0, 0, 8, 0,
                (x, z) -> x < 4 ? 5 : 6, (x, z) -> 0);
        List<int[]> line = q.plan(0, 0, 1, List.of(new com.skyclone.mod.mines.gen.RailPlanner.Target("t", 8, 0, 1, 6))).get(0);
        assertNotNull(line);
        assertEquals(9, line.size());
        assertTrue(com.skyclone.mod.mines.gen.RailRoute.contiguous(line));
    }

    @Test
    void wardenStripsOnlyThePass() {
        assertEquals("skill:mining:12 & hotm:4", MinesModule.withoutPass("skill:mining:12 & mines:hollows_pass & hotm:4"));
        assertEquals("hotm:4", MinesModule.withoutPass("hotm:4"));
        assertNull(MinesModule.withoutPass("mines:hollows_pass"));
        assertNull(MinesModule.withoutPass(null));
        assertEquals(Travel.DWARVEN_STOP, MinesModule.liftStop("dwarven"));
        assertEquals(3, MinesModule.liftStop("3"));
    }

    @Test
    void spawnRulesPointAtRealAreasAndMobs() throws Exception {
        JsonArray mobs = read("mobs.json"), spawns = read("spawns.json");
        List<String> ids = new ArrayList<>();
        for (JsonElement e : mobs) ids.add(e.getAsJsonObject().get("id").getAsString());
        Map<String, Layout> layouts = new HashMap<>();
        for (IslandGenerator g : gens()) layouts.put(g.islandId(), run(g).layout);
        for (JsonElement e : spawns) {
            JsonObject s = e.getAsJsonObject();
            Layout l = layouts.get(s.get("island").getAsString());
            assertNotNull(l, "island of " + s);
            assertNotNull(l.area(s.get("area").getAsString()), "area of " + s);
            assertTrue(ids.contains(s.get("mob").getAsString()), "mob of " + s);
        }
    }

    @Test
    void mobNumbersMatchTheResearch() throws Exception {
        Map<String, JsonObject> m = new HashMap<>();
        for (JsonElement e : read("mobs.json")) m.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
        // research 03 §9.2: Lapis Zombie Lv7 200 HP 50 dmg 12 XP; Emerald Slime 5/10/15 -> 80/150/250 HP
        assertEquals(200, m.get("deep_caverns_lapis_zombie").get("health").getAsDouble());
        assertEquals(50, m.get("deep_caverns_lapis_zombie").get("damage").getAsDouble());
        assertEquals(250, m.get("deep_caverns_emerald_slime_15").get("health").getAsDouble());
        assertEquals(80, m.get("deep_caverns_emerald_slime_5").get("health").getAsDouble());
        // research 03 §10.2: Ghost Lv250, 1,000,000 HP, 1,000 damage, 100 XP
        assertEquals(250, m.get("dwarven_ghost").get("level").getAsInt());
        assertEquals(1_000_000, m.get("dwarven_ghost").get("health").getAsDouble());
        assertEquals(100, m.get("dwarven_ghost").get("combatXp").getAsDouble());
        // NEU ICE_WALKER_MONSTER: 888 HP, 40 combat XP
        assertEquals(888, m.get("dwarven_ice_walker").get("health").getAsDouble());
        assertEquals(40, m.get("dwarven_ice_walker").get("combatXp").getAsDouble());
    }

    @Test
    void onlyLv25GoblinsLiveInTheDwarvenMines() throws Exception {
        // PLAYTEST round 2: a Lv200 goblin at the Goblin Burrows killed arrivals. In the reference the Lv70-200
        // goblins are Crystal Hollows (Goblin Holdout) mobs; the Dwarven bestiary ids are all _25.
        int goblins = 0;
        for (JsonElement e : read("mobs.json")) {
            JsonObject o = e.getAsJsonObject();
            if (!"goblin".equals(o.get("family").getAsString())) continue;
            goblins++;
            assertTrue(o.get("level").getAsInt() <= 25, o.get("id") + " level " + o.get("level"));
            assertTrue(o.has("skull") && o.get("skull").getAsString().length() > 100, o.get("id") + " wears the goblin head");
            assertFalse(o.getAsJsonArray("flags").contains(new com.google.gson.JsonPrimitive("baby")), o.get("id") + " is not a baby zombie");
        }
        assertEquals(5, goblins);
        for (JsonElement e : read("spawns.json")) {
            String mob = e.getAsJsonObject().get("mob").getAsString();
            assertFalse(mob.contains("cutthroat") || mob.contains("pitfighter") || mob.contains("creepertamer"), mob);
        }
    }

    @Test
    void zonesMapToLayers() {
        assertEquals(1, Places.layerOfZone("gunpowder_mines"));
        assertEquals(6, Places.layerOfZone("obsidian_sanctuary"));
        assertEquals(3, Places.layerOfZone("lift_3"));
        assertEquals(1, Places.layerOfZone("arrival"));
        assertEquals(0, Places.layerOfZone("dwarven_village"));
        assertEquals(0, Places.layerOfZone(null));
        assertEquals("Lapis Quarry", Places.layerName(2));
    }

    @Test
    void faresNeverGoNegative() {
        assertEquals(0, Places.fare(0, 0, 400));
        assertEquals(60, Places.fare(10, 0.5, 100));
        assertEquals(0, Places.fare(-5, -1, 10));
    }

    @Test
    void dataTracksVisitedLayers() {
        MinesData d = new MinesData();
        assertTrue(d.visit(4));
        assertTrue(d.visit(2));
        assertTrue(!d.visit(4));
        assertTrue(!d.visit(0));
        assertEquals(List.of(2, 4), d.layers);
    }

    private static JsonArray read(String file) throws Exception {
        try (var in = MinesIslandsTest.class.getClassLoader().getResourceAsStream("data/mines/" + file)) {
            assertNotNull(in, file);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
        }
    }
}
