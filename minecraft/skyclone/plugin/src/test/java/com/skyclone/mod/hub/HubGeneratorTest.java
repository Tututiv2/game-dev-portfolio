package com.skyclone.mod.hub;

import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.BlockState;
import com.skyclone.gen.IslandRun;
import com.skyclone.gen.KnownBlocks;
import com.skyclone.gen.Walkability;
import com.skyclone.mod.hub.gen.HubAnchors;
import com.skyclone.mod.hub.gen.HubGenerator;
import com.skyclone.mod.hub.gen.Landing;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the real hub generator offline (exactly what the game builds) and checks the result. */
class HubGeneratorTest {

    static IslandRun.Result run;
    static Walkability.Report walk;

    @BeforeAll
    static void generate() {
        HubGenerator gen = new HubGenerator();
        run = IslandRun.run(gen, IslandRun.seedOf(gen.islandId()));
        Walkability.Options o = new Walkability.Options();
        o.skipFloating = true;
        walk = Walkability.analyse(run.canvas, run.layout, o);
    }

    /** ForestHint tells a player swinging at a decorative tree that the Forest is "west of the Portal Plaza". */
    @Test
    void theForestIsWestOfThePortalPlaza() {
        var forest = run.layout.area("forest");
        assertNotNull(forest, "the 'forest' area (foraging's choppable trees)");
        var plaza = com.skyclone.mod.hub.gen.HubPlan.PORTALS;
        assertTrue(forest.maxX() < plaza.minX(), "forest " + forest + " vs plaza " + plaza);
        assertTrue(forest.minZ() <= plaza.minZ() && forest.maxZ() >= plaza.maxZ(), "the Forest spans the plaza's whole west side");
        assertNotNull(run.layout.anchor("warp.forest"), "the compass target");
    }

    @Test
    void everyExpectedAnchorAreaAndPortalExists() {
        Layout l = run.layout;
        List<String> miss = new ArrayList<>();
        List<String> all = new ArrayList<>(HubAnchors.CONTRACT);
        all.addAll(HubAnchors.REQUESTED);
        all.addAll(HubAnchors.OWN);
        all.addAll(HubAnchors.TRAVEL);
        for (String[] d : HubAnchors.DISTRICTS) all.add("warp." + d[0]);
        for (String[] v : HubAnchors.VILLAGE) all.add(v[2]);
        for (String a : all) if (l.anchor(a) == null) miss.add(a);
        for (String a : HubAnchors.CONTRACT_AREAS) if (l.area(a) == null) miss.add("area " + a);
        for (String p : HubAnchors.PORTALS) if (l.area("portal." + p) == null) miss.add("portal." + p);
        for (String t : HubAnchors.TRAVEL) if (l.area(t) == null) miss.add("area " + t);
        assertTrue(miss.isEmpty(), "missing " + miss);
    }

    @Test
    void districtsMatchTheModuleList() {
        Set<String> zones = new TreeSet<>();
        for (Layout.Zone z : run.layout.zones()) zones.add(z.id());
        for (HubDistrict d : HubDistrict.ALL) assertTrue(zones.contains(d.id()), "no zone for district " + d.id());
        assertEquals(HubAnchors.DISTRICTS.length, HubDistrict.ALL.size());
    }

    @Test
    void walkableFromSpawn() {
        assertNull(walk.spawnProblem);
        assertTrue(walk.failed().isEmpty(), "unreachable anchors " + walk.failed());
        assertTrue(walk.failedPortals().isEmpty(), "unreachable portals " + walk.failedPortals());
    }

    @Test
    void enoughFairySouls() {
        long souls = run.layout.anchors().keySet().stream().filter(n -> n.startsWith("fairy_soul.")).count();
        assertTrue(souls >= HubAnchors.MIN_SOULS && souls <= 90, "fairy souls: " + souls);
        // wave 3: topped up to the reference's 80, none inside a block, none within 12 blocks of another
        assertEquals(HubAnchors.REFERENCE_SOULS, souls, "fairy souls");
        List<Layout.Anchor> all = new ArrayList<>();
        for (var e : run.layout.anchors().entrySet()) if (e.getKey().startsWith("fairy_soul.")) all.add(e.getValue());
        for (Layout.Anchor a : all) {
            int x = (int) Math.floor(a.x()), y = (int) Math.floor(a.y()), z = (int) Math.floor(a.z());
            for (int h = 0; h <= 1; h++) {
                com.skyclone.gen.BlockInfo bi = com.skyclone.gen.BlockInfo.of(run.canvas.get(x, y + h, z));
                assertTrue(bi.top16 <= 1 || !bi.cube, a.name() + " is inside " + bi.name);
            }
            for (Layout.Anchor b : all)
                if (a != b) assertTrue(Math.hypot(a.x() - b.x(), a.z() - b.z()) + Math.abs(a.y() - b.y()) >= 3, a.name() + " sits on " + b.name());
        }
    }

    @Test
    void nothingOverlapsAndEverythingInBounds() {
        String res = run.layout.data().get("reservations");
        assertFalse(res.contains("REFUSED"), res);
        assertEquals(0, run.outsideBounds, "writes outside bounds: " + run.outsideSamples);
    }

    @Test
    void everyBlockNameIsValid() {
        if (!KnownBlocks.available()) return;
        Set<String> bad = new TreeSet<>();
        run.canvas.forEachId((x, y, z, id) -> {
            String n = BlockState.name(run.canvas.palette(id));
            if (!KnownBlocks.known(n)) bad.add(n);
        });
        assertTrue(bad.isEmpty(), "unknown blocks " + bad);
    }

    /**
     * 2026-10-03 playtest: walking over from the square to the Bank, Auction House, Bazaar and
     * Builder's House met a wall of windows at all four. Each now has a door on the side nearest
     * the square: the anchor's step must be in front of a real door, and nearer the square than the
     * building's middle.
     */
    @Test
    void bigBuildingsHaveADoorFacingTheSquare() {
        for (String id : HubAnchors.SQUARE_DOORS) {
            Layout.Anchor a = run.layout.anchor("door." + id);
            assertNotNull(a, "no door." + id);
            int x = (int) Math.floor(a.x()), y = (int) Math.floor(a.y()), z = (int) Math.floor(a.z());
            double yaw = Math.toRadians(a.yaw());
            int fx = (int) Math.round(-Math.sin(yaw)), fz = (int) Math.round(Math.cos(yaw));
            String door = BlockState.name(run.canvas.get(x + fx, y, z + fz));
            assertTrue(door.endsWith("_door"), id + ": the step looks at " + door + ", not a door");
            assertTrue(run.canvas.get(x + fx, y, z + fz).contains("half=lower"), id + ": not the bottom of a door");
            // two cells inside the door are free to walk into
            for (int d = 2; d <= 3; d++)
                for (int h = 0; h <= 1; h++) {
                    com.skyclone.gen.BlockInfo bi = com.skyclone.gen.BlockInfo.of(run.canvas.get(x + fx * d, y + h, z + fz * d));
                    assertTrue(bi.top16 <= 1, id + ": blocked " + d + " inside the door by " + bi.name);
                }
            Layout.Zone zone = null;
            for (Layout.Zone zz : run.layout.zones()) if (zz.id().equals(id)) zone = zz;
            assertNotNull(zone, "no zone " + id);
            double cx = (zone.box().minX() + zone.box().maxX()) / 2.0, cz = (zone.box().minZ() + zone.box().maxZ()) / 2.0;
            assertTrue(Math.hypot(a.x(), a.z()) < Math.hypot(cx, cz), id + ": the door is on the far side from the square");
        }
    }

    /**
     * 2026-10-03 playtest: a new player drowned from 105 to 28 HP in the Fairy Pond. Every water
     * column's bed must be at most one block below each neighbour's floor (a dry neighbour's floor is
     * its ground), so you can walk out from anywhere; and the fairy must stand on dry ground on
     * the town (north-east) side.
     */
    @Test
    void fairyPondCanBeWadedOutOf() {
        int px = com.skyclone.mod.hub.gen.HubPlan.FAIRY_POND_X, pz = com.skyclone.mod.hub.gen.HubPlan.FAIRY_POND_Z, r = 14;
        int level = Integer.MIN_VALUE;
        for (int y = 200; y > 30 && level == Integer.MIN_VALUE; y--)
            if (BlockState.name(run.canvas.get(px, y, pz)).equals("water")) level = y;
        assertTrue(level != Integer.MIN_VALUE, "no water in the middle of the Fairy Pond");
        int n = 2 * r + 1, wetCount = 0;
        int[][] floor = new int[n][n];
        boolean[][] wet = new boolean[n][n];
        for (int i = 0; i < n; i++)
            for (int k = 0; k < n; k++) {
                int x = px + i - r, z = pz + k - r;
                wet[i][k] = BlockState.name(run.canvas.get(x, level, z)).equals("water");
                if (wet[i][k]) {
                    wetCount++;
                    int y = level;
                    while (BlockState.name(run.canvas.get(x, y, z)).equals("water")) y--;
                    floor[i][k] = y;
                } else floor[i][k] = com.skyclone.gen.Builders.ground(run.canvas, x, z, level + 4, level - 20);
            }
        assertTrue(wetCount > 60, "the pond is nearly gone: " + wetCount + " water columns");
        List<String> bad = new ArrayList<>();
        for (int i = 1; i < n - 1; i++)
            for (int k = 1; k < n - 1; k++) {
                if (!wet[i][k]) continue;
                int up = Math.max(Math.max(floor[i - 1][k], floor[i + 1][k]), Math.max(floor[i][k - 1], floor[i][k + 1]));
                if (up - floor[i][k] > 1) bad.add((px + i - r) + "," + (pz + k - r) + " bed " + floor[i][k] + " next to " + up);
            }
        assertTrue(bad.isEmpty(), "pond steps too high to walk out: " + bad);
        Layout.Anchor f = run.layout.anchor("npc.fairy");
        assertNotNull(f);
        assertTrue(f.x() > px && f.z() < pz, "the fairy is not on the town side of the pond: " + f);
        assertFalse(BlockState.name(run.canvas.get((int) Math.floor(f.x()), (int) Math.floor(f.y()) - 1, (int) Math.floor(f.z()))).equals("water"));
    }

    /**
     * Playtest "loops" 2026-10-03: /warp crypt landed in the ghoul pack and killed a 600 HP / 700 Defense
     * player within a second, twice. The landing must be a SAFE zone (no spawns, no targeting) that is the
     * smallest zone at warp.crypt, while leaving the rest of the Crypt to spawn in; and walking down from the
     * mausoleum must pass through it too (the stair's foot is in it).
     */
    @Test
    void cryptArrivalIsSafe() {
        assertNull(GraveyardPack.arrivalProblem(run.layout, "warp.crypt", "crypt"));
        Layout.Anchor w = run.layout.anchor("warp.crypt");
        Layout.Zone landing = null;
        for (Layout.Zone z : run.layout.zones()) if (z.id().equals("crypt_landing")) landing = z;
        assertNotNull(landing);
        com.skyclone.api.world.gen.Box crypt = run.layout.area("crypt");
        // the hall beyond the landing still has room for the 16 ghouls: at least 40 blocks of it
        assertTrue(crypt.maxX() - landing.box().maxX() >= 40, "the landing eats the Crypt: " + landing.box());
        // the landing reaches back up the stair under the mausoleum (its top step is just under the floor)
        assertTrue(landing.box().minX() <= w.x() - 10, "the stair down is not in the landing: " + landing.box());
        // and the Graveyard's own arrival is unchanged (its quiet gate is the pack's, not a zone)
        assertNotNull(run.layout.anchor("warp.graveyard"));
    }

    /**
     * Wave-4 playtest: the Golden Ghoul (Lv60, 800 a hit) walked the main hall 14 blocks past the landing and
     * killed a Ghoul-slayer grinder three times in six minutes. Its vault must be a carved room off the main
     * hall, inside the Crypt's spawn area, far from the landing, and the smallest zone there (so it warns).
     */
    @Test
    void goldenGhoulVaultIsOffTheGrindHall() {
        assertNull(GoldenVault.vaultProblem(run.layout));
        com.skyclone.api.world.gen.Box v = run.layout.area(GoldenVault.AREA);
        Layout.Anchor w = run.layout.anchor("warp.crypt");
        // the main hall runs east from the landing at the warp's z; the vault (with its doorway margin) is well off it
        assertTrue(v.grow(GoldenVault.MARGIN).maxZ() < w.z() - 30, "vault " + v + " vs the main hall at z " + w.z());
        // the vault floor is carved: most of its cells are open with a floor under them
        int open = 0, cells = 0;
        for (int x = v.minX(); x <= v.maxX(); x++)
            for (int z = v.minZ(); z <= v.maxZ(); z++) {
                cells++;
                if (run.canvas.isAir(x, v.minY(), z) && run.canvas.isAir(x, v.minY() + 1, z) && !run.canvas.isAir(x, v.minY() - 1, z)) open++;
            }
        assertTrue(open >= cells * 0.8, "only " + open + "/" + cells + " vault cells are standable");
    }

    /**
     * Playtest "loops" 2026-10-03: walking from the Graveyard to the Library the player fell into the sewer
     * stairwell, whose rail stopped short of the hole. Around the stairwell, no standable street cell may sit
     * next to an open column that drops more than two blocks (the stair's own south end drops one per step).
     */
    @Test
    void sewerStairwellCannotBeFallenInto() {
        Layout.Anchor plumber = run.layout.anchor("npc.plumber");
        assertNotNull(plumber);
        int feet = (int) Math.floor(plumber.y());
        List<String> bad = new ArrayList<>();
        for (int x = 80; x <= 96; x++)
            for (int z = -24; z <= -6; z++) {
                if (!standable(x, feet, z)) continue;
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int nx = x + d[0], nz = z + d[1];
                    if (!passable(nx, feet, nz) || !passable(nx, feet + 1, nz) || !passable(nx, feet - 1, nz)) continue;
                    int y = feet - 1;
                    while (y > feet - 12 && passable(nx, y, nz)) y--;
                    if (feet - 1 - y > 2) bad.add(x + "," + z + " -> " + nx + "," + nz + " drops " + (feet - 1 - y));
                }
            }
        assertTrue(bad.isEmpty(), "open edges round the sewer stairwell: " + bad);
    }

    /** Playtest "loops": the Tavern's west door opens on a clear row straight across to the Slayer Master. */
    @Test
    void tavernWestDoorLeadsStraightToTheSlayerMaster() {
        Layout.Anchor door = run.layout.anchor("door.tavern"), sm = run.layout.anchor("npc.slayer_master");
        assertNotNull(door);
        assertNotNull(sm);
        int y = (int) Math.floor(door.y()), z = (int) Math.floor(door.z());
        assertTrue(door.x() < sm.x(), "door.tavern is not on the west side");
        for (int x = (int) Math.floor(door.x()) + 1; x < (int) Math.floor(sm.x()); x++)
            for (int h = 0; h <= 1; h++) {
                com.skyclone.gen.BlockInfo bi = com.skyclone.gen.BlockInfo.of(run.canvas.get(x, y + h, z));
                assertTrue(bi.top16 <= 1 || BlockState.name(run.canvas.get(x, y + h, z)).endsWith("_door"),
                        "blocked at " + x + "," + (y + h) + "," + z + " by " + bi.name);
            }
        assertTrue(Math.abs(sm.z() - door.z()) <= 2, "the Slayer Master is not on the door's row");
    }

    /**
     * Integration round 2: the Bank counter's lantern sat between the player and the Banker at chest
     * height, so a click aimed at his body hit the lantern. Nothing may stand at an NPC's chest height
     * in the two cells in front of it (a counter at feet height is fine: you click over it).
     */
    @Test
    void npcsCanBeClickedFromTheFront() {
        List<String> bad = new ArrayList<>();
        for (var e : run.layout.anchors().entrySet()) {
            if (!e.getKey().startsWith("npc.")) continue;
            Layout.Anchor a = e.getValue();
            int x = (int) Math.floor(a.x()), y = (int) Math.floor(a.y()), z = (int) Math.floor(a.z());
            double yaw = Math.toRadians(a.yaw());
            int fx = (int) Math.round(-Math.sin(yaw)), fz = (int) Math.round(Math.cos(yaw));
            for (int d = 1; d <= 2; d++) {
                String b = run.canvas.get(x + fx * d, y + 1, z + fz * d);
                if (!clickThrough(b)) { bad.add(e.getKey() + " " + d + " ahead: " + BlockState.name(b)); break; }
            }
        }
        assertTrue(bad.isEmpty(), bad.size() + " NPCs have something in front of their chest: " + bad);
    }

    /**
     * Wave 3: the Wizard Tower's arch is the way into the Rift. Its area must be solid portal sheet a
     * player walks into from the step in front, every sheet cell must be held on all four sides (a
     * vanilla portal pops otherwise), and the rift module's Warden spot (17 west, 8 south of
     * warp.wizard_tower, its dial default) and the new npc.rift_warden must be open ground.
     */
    @Test
    void riftArchIsAWalkInGate() {
        com.skyclone.api.world.gen.Box b = run.layout.area("gate.rift");
        assertNotNull(b, "no gate.rift area");
        for (int x = b.minX(); x <= b.maxX(); x++)
            for (int y = b.minY(); y <= b.maxY(); y++)
                for (int z = b.minZ(); z <= b.maxZ(); z++)
                    assertEquals("nether_portal", BlockState.name(run.canvas.get(x, y, z)), "gate.rift cell " + x + "," + y + "," + z);
        assertTrue(b.maxZ() - b.minZ() >= 2 && b.maxY() - b.minY() >= 1, "the sheet is too small to walk into: " + b);
        int x0 = b.minX();
        for (int y = b.minY() - 1; y <= b.maxY() + 4; y++)
            for (int z = b.minZ() - 2; z <= b.maxZ() + 2; z++) {
                if (!BlockState.name(run.canvas.get(x0, y, z)).equals("nether_portal")) continue;
                for (int[] d : new int[][]{{0, 1}, {0, -1}, {1, 0}, {-1, 0}})
                    assertFalse(clickThrough(run.canvas.get(x0, y + d[0], z + d[1])) && !BlockState.name(run.canvas.get(x0, y + d[0], z + d[1])).equals("nether_portal"),
                            "portal cell " + x0 + "," + y + "," + z + " is open on one side");
            }
        Layout.Anchor step = run.layout.anchor("gate.rift");
        assertNotNull(step);
        int sx = (int) Math.floor(step.x()), sy = (int) Math.floor(step.y()), sz = (int) Math.floor(step.z());
        assertTrue(standable(sx, sy, sz), "the step in front of the arch is not standable");
        assertTrue(sx - b.maxX() <= 2 && sz >= b.minZ() && sz <= b.maxZ(), "the step is not in front of the sheet");
        for (int x = b.maxX() + 1; x < sx; x++) assertTrue(passable(x, sy, sz) && passable(x, sy + 1, sz), "blocked between the step and the arch at x " + x);
        Layout.Anchor wt = run.layout.anchor("warp.wizard_tower");
        int wx = (int) Math.floor(wt.x()) - 17, wy = (int) Math.floor(wt.y()), wz = (int) Math.floor(wt.z()) + 8;
        assertTrue(passable(wx, wy, wz) && passable(wx, wy + 1, wz), "the rift Warden's fallback spot is not open: " + wx + "," + wy + "," + wz);
        Layout.Anchor rw = run.layout.anchor("npc.rift_warden");
        assertNotNull(rw);
        assertTrue(standable((int) Math.floor(rw.x()), (int) Math.floor(rw.y()), (int) Math.floor(rw.z())), "npc.rift_warden is not standable");
    }

    /**
     * Wave 3: the upper floors of two-storey buildings were bare boards. The Tavern's has guest beds,
     * the Old Keep's a study; and furniture never blocks the step off any ladder in the hub.
     */
    @Test
    void upperFloorsAreFurnishedAndLaddersStillLand() {
        Layout.Anchor bar = run.layout.anchor("npc.bartender");
        int upper = (int) Math.floor(bar.y()) + 4;   // tavern: floors of 5, so the first floor's feet are 5 up
        int beds = 0;
        for (int x = 57; x <= 79; x++)
            for (int z = 15; z <= 33; z++)
                for (int y = upper; y <= upper + 2; y++) if (BlockState.name(run.canvas.get(x, y, z)).endsWith("_bed")) beds++;
        assertTrue(beds >= 8, "the Tavern's guest floor has " + beds / 2 + " beds");
        Layout.Anchor cast = run.layout.anchor("npc.castellan");
        int cx = (int) Math.floor(cast.x()), cy = (int) Math.floor(cast.y()), cz = (int) Math.floor(cast.z()), shelves = 0;
        for (int x = cx - 12; x <= cx + 12; x++)
            for (int z = cz - 16; z <= cz + 16; z++)
                for (int y = cy + 5; y <= cy + 8; y++) if (BlockState.name(run.canvas.get(x, y, z)).endsWith("bookshelf")) shelves++;
        assertTrue(shelves >= 10, "the Old Keep's study has " + shelves + " shelves");
        List<String> bad = new ArrayList<>();
        run.canvas.forEachId((x, y, z, id) -> {
            if (!BlockState.name(run.canvas.palette(id)).equals("ladder")) return;
            if (BlockState.name(run.canvas.get(x, y + 1, z)).equals("ladder")) return;
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
                if (standable(x + d[0], y + 1, z + d[1]) || standable(x + d[0], y + 2, z + d[1])) return;
            bad.add(x + "," + y + "," + z);
        });
        assertTrue(bad.isEmpty(), "ladders with nowhere to step off at the top: " + bad);
    }

    /**
     * Wave 3 Target Practice: the four target anchors are target blocks, the shooting ground lies behind
     * the line at least 20 blocks from every target with a clear flight path at eye height, and the range
     * master stands inside the range.
     */
    @Test
    void archeryRangeIsReadyForTargetPractice() {
        com.skyclone.api.world.gen.Box line = run.layout.area("archery.line"), range = run.layout.area("archery");
        assertNotNull(line);
        assertNotNull(range);
        for (String t : HubAnchors.ARCHERY_TARGETS) {
            Layout.Anchor a = run.layout.anchor(t);
            assertNotNull(a, t);
            int x = (int) Math.floor(a.x()), y = (int) Math.floor(a.y()), z = (int) Math.floor(a.z());
            assertEquals("target", BlockState.name(run.canvas.get(x, y, z)), t);
            assertTrue(line.minX() - x >= 20, t + " is too close to the shooting line");
            assertTrue(range.contains(x, y, z), t + " is outside the range");
            // nothing solid between the line and the target at the target's height
            for (int xx = x + 1; xx < line.minX(); xx++)
                assertTrue(clickThrough(run.canvas.get(xx, y, z)) || BlockState.name(run.canvas.get(xx, y, z)).contains("grass"),
                        t + " is blocked at x " + xx + " by " + BlockState.name(run.canvas.get(xx, y, z)));
        }
        Layout.Anchor m = run.layout.anchor("npc.range_master");
        assertNotNull(m);
        assertTrue(range.contains(m.x(), m.y(), m.z()) && line.contains(m.x(), m.y(), m.z()), "the range master is not at the line: " + m);
    }

    /**
     * Wave-4 endgame playtest: {@code /warp wizard} landed beside the Wizard Tower with its wall filling the left of
     * the screen (every open-ground warp faced north whatever stood there), and {@code /warp da} faced along the
     * hall's blank side wall. Each now stands back from its landmark and faces it: the ray straight ahead meets the
     * tower 9+ blocks out, and the Dark Auction's door straight ahead.
     */
    @Test
    void wizardAndDarkAuctionWarpsFaceTheirLandmark() {
        com.skyclone.api.world.gen.Box wiz = com.skyclone.mod.hub.gen.HubPlan.WIZARD;
        double tx = wiz.centerX() + 0.5, tz = wiz.centerZ() + 0.5;
        Layout.Anchor w = run.layout.anchor("warp.wizard_tower");
        assertNull(Landing.viewProblem(run.canvas, w, tx, tz, Landing.WIZARD_REACH, Landing.WIZARD_GAP));
        double d = Landing.clearAlong(run.canvas, w.x(), w.y(), w.z(), w.yaw(), 40);
        double r = Math.toRadians(w.yaw()), hx = w.x() - Math.sin(r) * d, hz = w.z() + Math.cos(r) * d;
        assertTrue(d >= Landing.WIZARD_GAP, "the first thing ahead is only " + d + " blocks away");
        assertTrue(Math.hypot(hx - tx, hz - tz) <= Landing.WIZARD_REACH + 1.5, "straight ahead is not the tower but " + hx + "," + hz);
        // the Rift arch (a walk-in gate that spends an infusion) and its Warden are not where you land
        for (String n : new String[]{"gate.rift", "npc.rift_warden"}) {
            Layout.Anchor g = run.layout.anchor(n);
            assertTrue(Math.hypot(g.x() - w.x(), g.z() - w.z()) >= 8, n + " is beside the wizard landing");
        }

        Layout.Anchor da = run.layout.anchor("warp.dark_auction");
        double rd = Math.toRadians(da.yaw()), dx = -Math.sin(rd), dz = Math.cos(rd);
        int feet = (int) Math.floor(da.y());
        String door = null;
        for (double s = 0.5; s <= 12 && door == null; s += 0.25) {
            int bx = (int) Math.floor(da.x() + dx * s), bz = (int) Math.floor(da.z() + dz * s);
            for (int y = feet; y <= feet + 1; y++) {
                String n = BlockState.name(run.canvas.get(bx, y, bz));
                if (n.endsWith("_door")) door = n + " " + s;
            }
            if (door == null && Landing.clearAlong(run.canvas, da.x(), da.y(), da.z(), da.yaw(), s) < s) break;
        }
        assertNotNull(door, "the Dark Auction landing does not face the hall's door");
        // neither landing is on top of an NPC
        for (Layout.Anchor a : run.layout.anchors().values()) {
            if (!a.name().startsWith("npc.")) continue;
            for (Layout.Anchor l : new Layout.Anchor[]{w, da})
                assertTrue(Math.hypot(a.x() - l.x(), a.z() - l.z()) >= 2 || Math.abs(a.y() - l.y()) > 3, a.name() + " stands on " + l.name());
        }
    }

    /**
     * Wave-4 endgame playtest: during the Spooky Festival {@code /warp da} landed one block from a Lv50 Wraith (260 a
     * hit): the landing was in the Wilderness spawn area, outside the Dark Auction's hall-sized SAFE zone. No district
     * warp may land in ANOTHER district's spawn area (hub and festival rules) unless a SAFE zone holds the landing.
     */
    @Test
    void noWarpLandsInAnotherDistrictsSpawnAreaUnprotected() throws Exception {
        java.util.Set<String> areas = new TreeSet<>();
        for (String module : new String[]{"hub", "mayors"})
            try (java.io.InputStream in = HubGeneratorTest.class.getResourceAsStream("/data/" + module + "/spawns.json")) {
                assertNotNull(in, "data/" + module + "/spawns.json on the test classpath");
                for (com.google.gson.JsonElement el : com.google.gson.JsonParser.parseReader(
                        new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray()) {
                    com.google.gson.JsonObject o = el.getAsJsonObject();
                    if ("hub".equals(o.get("island").getAsString()) && o.has("area")) areas.add(o.get("area").getAsString());
                }
            }
        assertTrue(areas.contains("wilderness") && areas.contains("graveyard"), "festival areas read: " + areas);
        assertEquals(List.of(), Landing.unsafeLandings(run.layout, areas));
        // the case that was hit: the DA landing IS inside the Wilderness spawn area, and is now held by the DA zone
        Layout.Anchor da = run.layout.anchor("warp.dark_auction");
        assertTrue(run.layout.area("wilderness").containsXZ(da.x(), da.z()));
        Layout.Zone z = Landing.smallestZone(run.layout, da.x(), da.y() + 0.1, da.z());
        assertEquals("dark_auction", z.id());
        // and the bigger SAFE zone still leaves the Wilderness to spawn in, and still holds the hall
        assertTrue(z.box().volume() * 20 < run.layout.area("wilderness").volume(), "the Dark Auction zone eats the Wilderness");
        com.skyclone.api.world.gen.Box hall = run.layout.area("dark_auction");
        assertTrue(z.box().contains(hall.minX(), hall.minY(), hall.minZ()) && z.box().contains(hall.maxX(), hall.maxY(), hall.maxZ()));
    }

    /** A block a click passes through (no outline shape in front of a body). */
    static boolean clickThrough(String state) {
        String n = BlockState.name(state);
        return n.equals("air") || n.equals("cave_air") || n.equals("light") || n.equals("water") || n.equals("structure_void");
    }

    private static boolean passable(int x, int y, int z) {
        com.skyclone.gen.BlockInfo bi = com.skyclone.gen.BlockInfo.of(run.canvas.get(x, y, z));
        String n = bi.name;
        return bi.top16 == 0 && !n.endsWith("_wall") && !n.contains("fence") && !n.equals("water") && !n.equals("lava");
    }

    private static boolean standable(int x, int feet, int z) {
        return !passable(x, feet - 1, z) && passable(x, feet, z) && passable(x, feet + 1, z);
    }

    @Test
    void deterministic() {
        IslandRun.Result again = IslandRun.run(new HubGenerator(), IslandRun.seedOf("hub"));
        assertEquals(run.canvas.blockCount(), again.canvas.blockCount());
        assertEquals(run.layout.anchors().keySet(), again.layout.anchors().keySet());
    }

    /**
     * Wave 5: every townhouse resident (the hub's six errand-givers, skills' Pet Trainer, chocolate's rabbit keeper)
     * has a house of its own in the village, inside it (roofed), standing on floor with room to stand, and a
     * resident's door is reachable (the walk check covers the anchor). v10 placed only 3 of 8: the civic buildings
     * of waves 2-4 had taken the big lots, so cottages now fill the gaps.
     */
    @Test
    void everyResidentHasATownhouse() {
        assertTrue(run.layout.data().getOrDefault("hub.residents", "").startsWith(HubAnchors.RESIDENTS.size() + "/"),
                "residents placed: " + run.layout.data().get("hub.residents"));
        List<int[]> spots = new ArrayList<>();
        for (String name : HubAnchors.RESIDENTS) {
            Layout.Anchor a = run.layout.anchor(name);
            assertNotNull(a, name);
            int x = (int) Math.floor(a.x()), y = (int) Math.floor(a.y()), z = (int) Math.floor(a.z());
            assertTrue(com.skyclone.mod.hub.gen.HubPlan.VILLAGE.containsXZ(x, z), name + " is outside the village");
            // an anchor is the standing position: the floor block is one below it
            assertFalse(run.canvas.isAir(x, y - 1, z), name + " stands on air");
            assertTrue(run.canvas.isAir(x, y, z) && run.canvas.isAir(x, y + 1, z), name + " has no head room");
            boolean roofed = false;
            for (int h = 3; h <= 14 && !roofed; h++) if (!run.canvas.isAir(x, y + h, z)) roofed = true;
            assertTrue(roofed, name + " is not indoors");
            for (int[] o : spots) assertTrue(Math.hypot(o[0] - x, o[1] - z) > 4, name + " shares a house");
            spots.add(new int[]{x, z});
        }
        for (String name : HubAnchors.WAVE5) assertNotNull(run.layout.anchor(name), name);
    }

    /**
     * Mayors request 9: during the Carnival the Ringmaster and the Token Trader stand beside the Barker and the
     * Gamesmaster (mayors' EventNpcs.beside tries these ten offsets in order). At least one must be standable, or
     * they fall back to the Election Hall, far from the tents.
     */
    @Test
    void carnivalTroupeHasRoomBesideTheBarkers() {
        int[][] offs = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {3, 1}, {-3, 1}, {3, -1}, {-3, -1}, {2, 2}, {-2, -2}};
        for (String name : List.of("npc.carnival_barker", "npc.carnival_games")) {
            Layout.Anchor a = run.layout.anchor(name);
            assertNotNull(a, name);
            int x = (int) Math.floor(a.x()), y = (int) Math.floor(a.y()), z = (int) Math.floor(a.z());
            int free = 0;
            for (int[] o : offs) {
                String feet = run.canvas.get(x + o[0], y, z + o[1]), head = run.canvas.get(x + o[0], y + 1, z + o[1]);
                if (clickThrough(feet) && clickThrough(head) && !run.canvas.isAir(x + o[0], y - 1, z + o[1])) free++;
            }
            assertTrue(free >= 1, name + ": no room beside it for the carnival troupe");
        }
    }
}
