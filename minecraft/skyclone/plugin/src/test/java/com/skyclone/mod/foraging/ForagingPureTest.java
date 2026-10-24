package com.skyclone.mod.foraging;

import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.gen.IslandRun;
import com.skyclone.gen.Walkability;
import com.skyclone.mod.foraging.gen.MarshGenerator;
import com.skyclone.mod.foraging.gen.ParkGenerator;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the shipping foraging formulas and islands with no server. */
class ForagingPureTest {

    @Test
    void sweepOnZeroToughnessTrees() {
        // research 07 §4.3: logs = clamp(1 + Sweep, 1, 36)
        assertEquals(1, SweepMath.logsPerSwing(0, 0, false, 0.5, false));
        assertEquals(5, SweepMath.logsPerSwing(4, 0, false, 0.5, false));     // Sprucecutter
        assertEquals(26, SweepMath.logsPerSwing(25, 0, false, 0.5, false));   // Timberfeller
        assertEquals(36, SweepMath.logsPerSwing(35, 0, false, 0.5, false));
        assertEquals(36, SweepMath.logsPerSwing(900, 0, false, 0.5, false));
        assertEquals(13, SweepMath.logsPerSwing(25, 0, true, 0.5, false));    // a throw keeps half the Sweep
        assertEquals(1, SweepMath.logsPerSwing(-5, 0, false, 0.5, false));
    }

    @Test
    void sweepOnToughTrees() {
        // clone design formula, research 07 §4.3: extra = min(20, floor(Sweep / (1 + t/10)))
        assertEquals(11, SweepMath.logsPerSwing(20, 10, false, 0.5, false));    // fig: 1 per 2 Sweep
        assertEquals(4, SweepMath.logsPerSwing(18, 50, false, 0.5, false));     // mangrove: 1 per 6 Sweep
        assertEquals(21, SweepMath.logsPerSwing(320, 150, false, 0.5, false));  // helix: 320 Sweep to cap
        assertEquals(20, SweepMath.logsPerSwing(319, 150, false, 0.5, false));
        assertEquals(6, SweepMath.logsPerSwing(20, 10, false, 0.5, true));      // wrong order halves it
        assertEquals(320, SweepMath.sweepFor(21, 150), 1e-9);
    }

    @Test
    void fortune() {
        Random r = new Random(7);
        assertEquals(2, SweepMath.dropsForLog(100, r));
        assertEquals(3, SweepMath.dropsForLog(200, r));
        int total = 0;
        for (int i = 0; i < 10_000; i++) total += SweepMath.dropsForLog(50, r);
        assertEquals(15_000, total, 400);   // 1.5 per log on average
        assertEquals(36 * 2.5, SweepMath.expectedDrops(36, 150), 1e-9);
    }

    @Test
    void treeGiftShares() {
        assertEquals(0, SweepMath.giftShare(0.0999));
        assertEquals(1, SweepMath.giftShare(0.10));
        assertEquals(1.5, SweepMath.giftShare(0.20));
        assertEquals(2, SweepMath.giftShare(1.0 / 3.0));
        assertEquals(2, SweepMath.giftShare(1));
    }

    @Test
    void heartOfTheForest() {
        assertEquals(1, SweepMath.hotfTier(0));
        assertEquals(2, SweepMath.hotfTier(3_000));
        assertEquals(4, SweepMath.hotfTier(37_000));   // Torrent Canyon unlock
        assertEquals(8, SweepMath.hotfTier(547_000));
        assertEquals(15, SweepMath.tokensAt(8));
        assertEquals(8, SweepMath.perkCost(0, 3.0));             // floor((1+1)^3)
        assertEquals(132_651, SweepMath.perkCost(49, 3.0));      // the 50th Sweep level: 51^3
        assertEquals(32_768, SweepMath.keystoneCost(0));         // (1+7)^5
        assertEquals(218, SweepMath.linearCost(0, 200, 18));     // Daily Wishes
        for (HotfPerk p : HotfPerk.ALL) {
            assertTrue(p.slot() >= 0 && p.slot() < 45, p.id() + " slot");
            assertTrue(p.tier() >= 1 && p.tier() <= 8, p.id() + " tier");
            for (HotfPerk q : HotfPerk.ALL) if (p != q) assertTrue(p.slot() != q.slot(), p.id() + " and " + q.id() + " share a slot");
        }
        assertEquals("Grants &2+30∮ Sweep&7.", HotfPerk.render("Grants &2+{1}∮ Sweep&7.", 30));
        assertEquals("&a+2.5% loot", HotfPerk.render("&a+{0.5}% loot", 5));
    }

    @Test
    void woods() {
        assertEquals(Wood.OAK, Wood.ofBlock("OAK_LOG", false));
        assertEquals("LOG:2", Wood.ofBlock("BIRCH_LOG", false).itemId);
        assertNull(Wood.ofBlock("STRIPPED_SPRUCE_LOG", false), "stripped logs off the marsh are buildings");
        assertEquals(Wood.FIG, Wood.ofBlock("STRIPPED_SPRUCE_LOG", true));
        assertEquals(Wood.HELIX, Wood.ofBlock("STRIPPED_MANGROVE_LOG", true));
        assertNull(Wood.ofBlock("OAK_LOG", true), "plain oak on the marsh is decoration");
        assertEquals(150, Wood.HELIX.toughness);
        assertEquals(6, Wood.JUNGLE.xp);
        assertEquals("WOOD:5", Wood.DARK_OAK.plankItem());
        assertTrue(Wood.isLeaves("CHERRY_LEAVES") && Wood.isLeaves("AZALEA_LEAVES") && !Wood.isLeaves("OAK_LOG"));
    }

    @Test
    void cuttingOrder() {
        // research 07 §4.4: fig trunk -> branches, mangrove branches -> trunk, spiral beige -> red
        assertEquals(0, Wood.FIG.stage("STRIPPED_SPRUCE_LOG", 'y'));
        assertEquals(1, Wood.FIG.stage("STRIPPED_SPRUCE_LOG", 'x'));
        assertEquals(0, Wood.FIG.stage("STRIPPED_SPRUCE_WOOD", 'z'), "buttress roots are trunk");
        assertEquals(0, Wood.MANGROVE.stage("MANGROVE_LOG", 'z'));
        assertEquals(1, Wood.MANGROVE.stage("MANGROVE_LOG", 'y'));
        assertEquals(Wood.HELIX, Wood.ofBlock("STRIPPED_BIRCH_LOG", true));
        assertNull(Wood.ofBlock("STRIPPED_BIRCH_LOG", false), "stripped birch off the marsh is a building");
        assertEquals(0, Wood.HELIX.stage("STRIPPED_BIRCH_LOG", 'x'));
        assertEquals(1, Wood.HELIX.stage("STRIPPED_MANGROVE_LOG", 'y'));
        assertEquals(1, Wood.OAK.stages());
        assertEquals("Trunk → branches", Wood.FIG.orderLine());
        // wrong order halves the effective Sweep: fig 20 Sweep 11 logs -> 6
        assertEquals(6, SweepMath.logsPerSwing(20, 10, false, 0.5, true));
    }

    @Test
    void islandResources() {
        assertEquals(IslandResource.LUSHLILAC, IslandResource.ofBlock("LILAC"));
        assertEquals(IslandResource.SEA_LUMIES, IslandResource.ofBlock("sea_pickle"));
        assertEquals(IslandResource.RUBY_VEILSHROOM, IslandResource.ofBlock("RED_MUSHROOM"));
        assertNull(IslandResource.ofBlock("POPPY"));
        for (IslandResource r : IslandResource.values()) assertTrue(r.xp >= 15 && r.xp <= 16, "research: 15-16 XP");
        assertEquals(0.5, IslandResource.collectorChance(25), 1e-9);
        assertTrue(HotfPerk.byId("collector") != null && HotfPerk.byId("collector").max() == 50);
    }

    @Test
    void starfallContests() {
        // research 07 §4.6: Agatha brackets Common 25 ... Special 12,000; Miria Common 50 ... Special 20,000
        assertEquals(-1, Contest.bracket(24, false));
        assertEquals(0, Contest.bracket(25, false));
        assertEquals(2, Contest.bracket(1_000, false));
        assertEquals(7, Contest.bracket(12_000, false));
        assertEquals(-1, Contest.bracket(49, true));
        assertEquals(3, Contest.bracket(2_500, true));
        assertEquals(7, Contest.bracket(20_000, true));
        // per 100 logs: fig 3, mangrove 6, spiral 9
        assertEquals(3, Contest.logPoints(Wood.FIG) * 100, 1e-9);
        assertEquals(9, Contest.logPoints(Wood.HELIX) * 100, 1e-9);
        assertEquals(0, Contest.logPoints(Wood.OAK), 1e-9);
        assertEquals(9, Contest.resourcePoints(IslandResource.LUSHLILAC), 1e-9);
        // reward ranges span Common..Special
        assertEquals(2_500, Contest.scale(0, 2_500, 20_000), 1e-9);
        assertEquals(20_000, Contest.scale(7, 2_500, 20_000), 1e-9);
    }

    @Test
    void marshHasResourcesAndMobAreas() {
        IslandRun.Result r = IslandRun.run(new MarshGenerator(), IslandRun.seedOf(MarshGenerator.ID));
        for (String k : new String[]{"marsh.lilacs", "marsh.lumies", "marsh.veilshrooms"})
            assertTrue(Integer.parseInt(r.layout.data().get(k)) >= 20, k + " = " + r.layout.data().get(k));
        for (String a : new String[]{"mobs.loch", "mobs.ruins.west", "mobs.ruins.south", "mobs.ruins.east"})
            assertNotNull(r.layout.area(a), a);
    }

    /** wave4-endgame playtest: /warp howl landed among 4+ Lv30 Pack Wraiths at 1-3 blocks. */
    @Test
    void howlArrivalIsSafeAndTheWolvesStillHaveRoom() {
        IslandRun.Result r = IslandRun.run(new ParkGenerator(), IslandRun.seedOf(ParkGenerator.ID));
        assertNull(ParkGenerator.arrivalProblem(r.layout));
        var a = r.layout.anchor("warp.howl");
        int x = (int) Math.floor(a.x()), y = (int) Math.floor(a.y()), z = (int) Math.floor(a.z());
        assertTrue(!r.canvas.isAir(x, y - 1, z) && r.canvas.isAir(x, y, z) && r.canvas.isAir(x, y + 1, z),
                "warp.howl is not standable: " + r.canvas.get(x, y - 1, z) + " / " + r.canvas.get(x, y, z) + " / " + r.canvas.get(x, y + 1, z));
        // the pack still has a real floor to spawn on (solid floor, 2 free blocks above)
        var box = r.layout.area("howling_cave");
        int spots = 0;
        for (int bx = box.minX(); bx <= box.maxX(); bx++)
            for (int bz = box.minZ(); bz <= box.maxZ(); bz++)
                for (int by = box.minY(); by <= box.maxY(); by++)
                    if (!r.canvas.isAir(bx, by - 1, bz) && r.canvas.isAir(bx, by, bz) && r.canvas.isAir(bx, by + 1, bz)) { spots++; break; }
        assertTrue(spots >= 150, "Pack Wraith spawn spots: " + spots);
    }

    // ---- wave 5 ---------------------------------------------------------------------------

    @Test
    void divingMath() {
        // base 30 Respiration = vanilla 15 s of air; Respiration IV (+60) triples it
        assertEquals(300, DepthMath.maxAirTicks(DepthMath.BASE_RESPIRATION, 10));
        assertEquals(900, DepthMath.maxAirTicks(90, 10));
        assertEquals(20, DepthMath.maxAirTicks(0, 10));
        assertEquals(60, DepthMath.pressure(30, 2), 1e-9);
        assertEquals(0, DepthMath.effective(60, 60), 1e-9);    // a full Diver's set (4 x 15) dives 30 blocks freely
        assertEquals(20, DepthMath.effective(60, 40), 1e-9);
        assertEquals(0.04, DepthMath.damageFraction(20, 2), 1e-9);
        assertEquals(0.20, DepthMath.damageFraction(500, 2), 1e-9);
        assertEquals(0, DepthMath.damageFraction(0, 2), 1e-9);
    }

    @Test
    void honeyMath() {
        assertEquals(300_000, HiveMath.refillMillis(300, false));
        assertEquals(240_000, HiveMath.refillMillis(300, true));               // Hive Mind: 20% faster
        assertEquals(3, HiveMath.honeycomb(3, false, false));
        assertEquals(10, HiveMath.honeycomb(3, true, true));                    // +2, then doubled
        assertEquals(3_600_000, HiveMath.latherMillis(60, 0));                  // Fun-Sized: 60 minutes
        assertEquals(1_800_000, HiveMath.latherMillis(60, 50));
        assertEquals(360_000, HiveMath.latherMillis(60, 400));                  // never below 10%
        assertEquals(0, HiveMath.latherMillis(0, 0));                           // Behemoth: at once
        assertEquals(0, HiveMath.digits(0));
        assertEquals(4, HiveMath.digits(1234));
        assertEquals(8, HiveMath.digits(999_999_999));                          // "max 10M collection"
        int a = HiveMath.dailyPick(1, 2, 20_000, "daily_hive", 4);
        assertEquals(a, HiveMath.dailyPick(1, 2, 20_000, "daily_hive", 4));    // stable all day
        int[] seen = new int[4];
        for (int d = 0; d < 400; d++) seen[HiveMath.dailyPick(1, 2, 20_000 + d, "daily_hive", 4)]++;
        for (int s : seen) assertTrue(s > 50, "each face shows up: " + java.util.Arrays.toString(seen));
    }

    @Test
    void toughSweepAndContestTables() {
        // lowered toughness stays on the tough formula (never the 36-log Park cap)
        assertEquals(SweepMath.logsPerSwing(20, 10, false, 0.5, false), SweepMath.logsPerSwingTough(20, 10, false, 0.5, false));
        assertEquals(21, SweepMath.logsPerSwingTough(500, 0, false, 0.5, false));
        assertEquals(12, SweepMath.logsPerSwingTough(20, 8, false, 0.5, false));    // fig 10 -> 8: 20 / 1.8 = 11.1 -> 12 logs
        assertEquals(10, Contest.fishPoints(true, false, null, false), 1e-9);
        assertEquals(25, Contest.fishPoints(false, true, "good", false), 1e-9);
        assertEquals(750, Contest.fishPoints(false, true, "outstanding", true), 1e-9);
        assertEquals(0, Contest.fishPoints(true, false, null, true), 1e-9);
        assertEquals(3, Contest.shardPoints(0, false), 1e-9);
        assertEquals(45, Contest.shardPoints(9, false), 1e-9);
        assertEquals(200, Contest.shardPoints(4, true), 1e-9);
        assertEquals(10, Contest.coupons(0));
        assertEquals(45, Contest.coupons(7));
        assertEquals(15, Contest.bits(0));
        assertEquals(120, Contest.bits(7));
        assertEquals(0, Contest.prizeChance(2), 1e-9);
        assertEquals(0.20, Contest.prizeChance(7), 1e-9);
        // every HotF perk sits on its own slot
        java.util.Set<Integer> slots = new java.util.HashSet<>();
        for (HotfPerk p : HotfPerk.ALL) assertTrue(slots.add(p.slot()), "two perks on slot " + p.slot());
        assertEquals(1, HotfPerk.byId("daily_hive").max());
    }

    @Test
    void marshV3HasDepthsHivesAndContestHost() {
        IslandRun.Result r = IslandRun.run(new MarshGenerator(), IslandRun.seedOf(MarshGenerator.ID));
        assertNotNull(r.layout.anchor("npc.contest_host"));
        var hives = r.layout.area("honey_hives");
        assertNotNull(hives);
        assertTrue(Integer.parseInt(r.layout.data().get("marsh.hives")) >= 4, "hives: " + r.layout.data().get("marsh.hives"));
        var deep = r.layout.area("depths_water");
        assertNotNull(deep, "the Dimwater Depths were not carved");
        int floor = Integer.parseInt(r.layout.data().get("marsh.depths_floor"));
        assertTrue(75 - floor >= 20, "depths only " + (75 - floor) + " blocks deep");
        // water at the bottom of the shaft, and rock (never air) under the cavern bed
        int cx = (deep.minX() + deep.maxX()) / 2, cz = (deep.minZ() + deep.maxZ()) / 2;
        int waters = 0;
        for (int y = floor; y < 75; y++) if (r.canvas.get(cx, y, cz) != null && r.canvas.get(cx, y, cz).startsWith("water")) waters++;
        assertTrue(waters >= 20, "the shaft holds only " + waters + " water");
        for (int x = deep.minX(); x <= deep.maxX(); x += 3)
            for (int z = deep.minZ(); z <= deep.maxZ(); z += 3) {
                String bed = r.canvas.get(x, floor - 1, z);
                String under = r.canvas.get(x, floor - 2, z);
                if (bed != null && bed.startsWith("water")) assertTrue(under != null && !r.canvas.isAir(x, floor - 2, z), "hole under the depths at " + x + "," + z);
            }
        assertTrue(Integer.parseInt(r.layout.data().get("marsh.depths_lumies")) >= 5, "Sea Lumies in the depths");
    }

    @Test
    void boostersAndForestsFavor() {
        BoosterMath.Kind sweep = BoosterMath.Kind.SWEEP, fight = BoosterMath.Kind.FIGHTING;
        assertNull(BoosterMath.check(sweep, 1, null, 0));                  // a Common one on a bare item
        assertNotNull(BoosterMath.check(sweep, 2, null, 0));               // an Uncommon one only upgrades
        assertNull(BoosterMath.check(sweep, 2, sweep, 1));
        assertNotNull(BoosterMath.check(sweep, 2, fight, 1));              // not someone else's kind
        assertNotNull(BoosterMath.check(sweep, 2, sweep, 2));              // already upgraded
        assertNotNull(BoosterMath.check(fight, 1, sweep, 1));              // one booster per item
        assertEquals(2, sweep.value(2), 1e-9);
        assertEquals(0, sweep.value(0), 1e-9);
        assertEquals(BoosterMath.Kind.FORAGING_FORTUNE, BoosterMath.Kind.parse("Foraging Fortune"));
        // Karma's data stops at V (1..5%); Forest's Favor makes VI = 6%
        assertEquals(5, BoosterMath.extrapolate(new double[]{1, 2, 3, 4, 5}, 5), 1e-9);
        assertEquals(6, BoosterMath.extrapolate(new double[]{1, 2, 3, 4, 5}, 6), 1e-9);
        assertEquals(0, BoosterMath.extrapolate(new double[0], 6), 1e-9);
    }

    @Test
    void treeGiftCreaturesAndBonusDrops() {
        // research 07 4.4: fig 11.8 + 7.1 + 1.2 = 20.1%, spiral 15 + 15 + 5 + 5 + 1 = 41%, one creature at most
        assertEquals("critter_phanpyre", GiftTables.pick(Wood.FIG, 1, 0.10).mob());
        assertEquals("critter_phanflare", GiftTables.pick(Wood.FIG, 1, 0.15).mob());
        assertEquals("critter_dreadwing", GiftTables.pick(Wood.FIG, 1, 0.195).mob());
        assertNull(GiftTables.pick(Wood.FIG, 1, 0.202));
        assertNull(GiftTables.pick(Wood.HELIX, 1, 0.411));
        assertEquals("critter_grizzly_bear", GiftTables.pick(Wood.HELIX, 1, 0.405).mob());
        assertNotNull(GiftTables.pick(Wood.FIG, 1.5, 0.25));               // Tree Lurker widens the table
        assertNull(GiftTables.pick(Wood.OAK, 10, 0.0));                    // Park woods give no gifts
        Random r = new Random(11);
        int hits = 0;
        for (int i = 0; i < 100_000; i++) if (GiftTables.pick(Wood.MANGROVE, 1, r.nextDouble()) != null) hits++;
        assertEquals(20_100, hits, 600);
        boolean fish = false;
        for (GiftTables.Extra e : GiftTables.extras(Wood.FIG)) if (e.ids().contains("TREE_THE_FISH")) { fish = true; assertEquals(0.0005, e.chance(), 1e-12); }
        assertTrue(fish, "Tree the Fish is a fig gift (1 in 2,000)");
        for (GiftTables.Extra e : GiftTables.extras(Wood.HELIX)) assertTrue(!e.ids().contains("TREE_THE_FISH"));
    }

    @Test
    void marshV4HasTheWyrmsHollowAVentAndHotspots() {
        assertEquals(4, new MarshGenerator().version());
        IslandRun.Result r = IslandRun.run(new MarshGenerator(), IslandRun.seedOf(MarshGenerator.ID));
        assertNull(r.layout.data().get("marsh.lair_failed"), "lair: " + r.layout.data().get("marsh.lair_failed"));
        var npc = r.layout.anchor("npc.jade_dragon");
        assertNotNull(npc, "no jade dragon");
        int nx = (int) Math.floor(npc.x()), ny = (int) Math.floor(npc.y()), nz = (int) Math.floor(npc.z());
        assertTrue(!r.canvas.isAir(nx, ny - 1, nz) && r.canvas.isAir(nx, ny, nz) && r.canvas.isAir(nx, ny + 1, nz), "the dragon cannot stand");
        // swim from the depths cavern to the dragon: through water and air only, never up into the loch
        var deep = r.layout.area("depths_water");
        int floor = Integer.parseInt(r.layout.data().get("marsh.depths_floor"));
        String[] lair = r.layout.data().get("marsh.lair").split(",");
        int lairF = Integer.parseInt(lair[1]);
        int minX = Math.min(deep.minX(), Integer.parseInt(lair[0])) - 4, maxX = Math.max(deep.maxX(), Integer.parseInt(lair[3])) + 4;
        int minZ = Math.min(deep.minZ(), Integer.parseInt(lair[2])) - 4, maxZ = Math.max(deep.maxZ(), Integer.parseInt(lair[4])) + 4;
        int maxY = lairF + 7;
        assertTrue(maxY < 75 - 6, "the lair reaches the loch bed");
        int cx = (deep.minX() + deep.maxX()) / 2, cz = (deep.minZ() + deep.maxZ()) / 2;
        int sy = floor + 2;
        while (sy < maxY && !String.valueOf(r.canvas.get(cx, sy, cz)).startsWith("water")) sy++;
        java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        q.add(new int[]{cx, sy, cz});
        boolean reached = false;
        int[][] nb = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!q.isEmpty() && !reached) {
            int[] c = q.poll();
            for (int[] d : nb) {
                int x = c[0] + d[0], y = c[1] + d[1], z = c[2] + d[2];
                if (x < minX || x > maxX || z < minZ || z > maxZ || y < floor - 2 || y > maxY) continue;
                if (!seen.add(((long) x << 40) ^ ((long) y << 20) ^ (z & 0xFFFFF))) continue;
                String b = r.canvas.get(x, y, z);
                if (!(r.canvas.isAir(x, y, z) || b != null && b.startsWith("water"))) continue;
                if (x == nx && y == ny && z == nz) { reached = true; break; }
                q.add(new int[]{x, y, z});
            }
        }
        assertTrue(reached, "no swim route from the depths to the dragon");
        // the lava vent: lava with air above, nothing but lava or solid rock beside it at its own level (nothing flows)
        assertNotNull(r.layout.area("lava_vent"), "no lava vent");
        var v = r.layout.area("lava_vent");
        int lava = 0;
        for (int x = v.minX() - 4; x <= v.maxX() + 4; x++)
            for (int z = v.minZ() - 4; z <= v.maxZ() + 4; z++)
                for (int y = v.minY() - 2; y <= v.maxY(); y++) {
                    String b = r.canvas.get(x, y, z);
                    if (b == null || !b.startsWith("lava")) continue;
                    lava++;
                    for (int[] d : nb) {
                        if (d[1] == 1) continue;
                        String o = r.canvas.get(x + d[0], y + d[1], z + d[2]);
                        assertTrue(o != null && !r.canvas.isAir(x + d[0], y + d[1], z + d[2]) && !o.startsWith("water"), "lava can flow at " + x + "," + y + "," + z);
                    }
                }
        assertTrue(lava >= 20, "vent holds " + lava + " lava");
        // three hotspot anchors floating on open loch water
        int hot = 0;
        for (var a : r.layout.anchors().values()) {
            if (!a.name().startsWith("fishing.hotspot.")) continue;
            hot++;
            assertTrue(String.valueOf(r.canvas.get((int) Math.floor(a.x()), (int) Math.floor(a.y()) - 1, (int) Math.floor(a.z()))).startsWith("water"), a.name() + " is not on water");
        }
        assertTrue(hot >= 3, "hotspots: " + hot);
    }

    @Test
    void parkIsWalkable() { walkable(new ParkGenerator(), 20); }

    @Test
    void marshIsWalkable() { walkable(new MarshGenerator(), 12); }

    private static void walkable(IslandGenerator g, int souls) {
        IslandRun.Result r = IslandRun.run(g, IslandRun.seedOf(g.islandId()));
        assertNotNull(r.layout.anchor("spawn"));
        assertNotNull(r.layout.area("portal.hub"));
        long n = r.layout.anchors().keySet().stream().filter(k -> k.startsWith("fairy_soul.")).count();
        assertTrue(n >= souls && n <= 40, g.islandId() + " fairy souls: " + n);
        Walkability.Report w = Walkability.analyse(r.canvas, r.layout, new Walkability.Options());
        assertTrue(w.ok(), g.islandId() + ": " + w.failed() + " " + w.failedPortals());
        assertEquals(0, r.outsideBounds, g.islandId() + " wrote outside its bounds: " + r.outsideSamples);
    }
}
