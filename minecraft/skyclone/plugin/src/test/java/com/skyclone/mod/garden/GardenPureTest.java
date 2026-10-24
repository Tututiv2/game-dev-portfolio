package com.skyclone.mod.garden;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.IslandRun;
import com.skyclone.gen.MemoryCanvas;
import com.skyclone.gen.Walkability;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure checks of the garden's data and formulas against research/08 section 1. */
class GardenPureTest {

    static GardenTables tables() {
        return GardenTables.load(name -> {
            InputStream in = GardenPureTest.class.getResourceAsStream("/data/garden/" + name);
            assertNotNull(in, "missing resource " + name);
            return new InputStreamReader(in, StandardCharsets.UTF_8);
        });
    }

    @Test
    void dataHangsTogether() {
        GardenTables t = tables();
        assertEquals(13, t.crops().size());
        assertEquals(24, t.plots().size());
        assertEquals(140, t.allVisitors().size());
        assertEquals(15, t.pests().size());
        for (GardenTables.Crop c : t.crops()) {
            assertEquals(46, c.milestones.length, c.key);
            assertNotNull(t.crop(c.key));
            assertNotNull(t.pest(c.pest), c.key + " pest " + c.pest);
        }
        // every plot sits on the grid exactly once, the barn cell is free
        Set<String> cells = new HashSet<>();
        for (GardenTables.Plot p : t.plots()) {
            assertTrue(cells.add(p.col + "," + p.row));
            assertFalse(PlotGrid.isBarn(p.col, p.row));
            assertNotNull(t.group(p.group));
            assertEquals(p.id, GardenGenerator.PLOT_IDS[p.col][p.row], "generator plot table");
            assertEquals(p.number, GardenGenerator.PLOT_NUMBER[p.col][p.row]);
        }
        // visitor levels within I..XV, rarities known
        for (GardenTables.Visitor v : t.allVisitors()) {
            assertTrue(v.level >= 1 && v.level <= 15, v.id);
            assertNotNull(t.rarity(v.rarity));
        }
        // barn skins have palettes and vice versa
        for (GardenTables.BarnSkin s : t.garden.barnSkins) assertTrue(BarnBuilder.SKINS.containsKey(s.id), s.id);
        // mutation recipes only name crops or earlier mutations
        Set<String> names = new HashSet<>();
        for (GardenTables.Crop c : t.crops()) names.add(c.key);
        for (GardenTables.Mutation m : t.greenhouse.mutations) {
            for (String n : m.needs) assertTrue(names.contains(n), m.id + " needs unknown " + n);
            assertTrue(m.needs.size() <= 4, m.id);
            names.add(m.id);
        }
    }

    @Test
    void levelsAndMilestonesMatchResearch() {
        GardenTables t = tables();
        long[] lv = t.garden.levels;
        assertEquals(60_120, GardenMath.totalXpFor(15, lv));          // research 08 1.5
        assertEquals(1, GardenMath.level(69, lv));
        assertEquals(2, GardenMath.level(70, lv));
        assertEquals(5, GardenMath.level(520, lv));
        assertEquals(10, GardenMath.level(10_120, lv));
        assertEquals(15, GardenMath.level(1e9, lv));
        assertEquals(90, GardenMath.cropGrowth(10));                   // +10 per level above I
        GardenTables.Crop wheat = t.crop("WHEAT");
        assertEquals(30, wheat.milestones[0]);                         // NEU constants
        assertEquals(0, GardenMath.milestoneTier(29, wheat.milestones));
        assertEquals(1, GardenMath.milestoneTier(30, wheat.milestones));
        assertEquals(2, GardenMath.milestoneTier(80, wheat.milestones));
        assertEquals(80, GardenMath.milestoneThreshold(2, wheat.milestones));
        int sum = 0;
        for (int c : t.garden.cropUpgrades.copper) sum += c;
        assertEquals(7_685, sum);                                       // research 08 1.7
        assertEquals(99_905, sum * 13);
    }

    @Test
    void fortunePestsComposter() {
        GardenTables t = tables();
        // "260 fortune yields 2 guaranteed extra drops plus a 60% chance for a third" (research 08 1.3)
        assertEquals(4, GardenMath.fortuneDrops(1, 260, 0.59));
        assertEquals(3, GardenMath.fortuneDrops(1, 260, 0.61));
        assertEquals(1, GardenMath.fortuneDrops(1, 0, 0.0));
        // pest penalty matrix (research 08 1.12)
        int[][] pen = t.garden.pests.penalty;
        assertEquals(0, GardenMath.penalty(pen, 50, 2));
        assertEquals(5, GardenMath.penalty(pen, 50, 3));
        assertEquals(75, GardenMath.penalty(pen, 50, 7));
        assertEquals(100, GardenMath.penalty(pen, 50, 8));
        assertEquals(15, GardenMath.penalty(pen, 450, 0));
        assertEquals(30, GardenMath.penalty(pen, 900, 0));
        assertEquals(1, GardenMath.pestsPerSpawn(0, 0.0));
        assertEquals(3, GardenMath.pestsPerSpawn(150, 0.2));
        assertEquals(2, GardenMath.pestsPerSpawn(150, 0.9));
        assertEquals(130, GardenMath.pestCooldown(300, 1, 0.6, 130, 1200), 1e-9);
        assertEquals(600, GardenMath.pestCooldown(300, 2, 0, 130, 1200), 1e-9);
        // Pest Vest + 3 Pesthunter + 4 Squeaky = 135 s; the Pest Cooldown attribute at X (-5 s) reaches the 130 s floor
        assertEquals(135, GardenMath.pestCooldown(300, 1, 0.55, 0, 130, 1200), 1e-9);
        assertEquals(130, GardenMath.pestCooldown(300, 1, 0.55, 5, 130, 1200), 1e-9);
        assertEquals(130, GardenMath.pestCooldown(300, 1, 0.55, 50, 130, 1200), 1e-9, "never below the floor");
        assertEquals(295, GardenMath.pestCooldown(300, 1, 0, 5, 130, 1200), 1e-9);
        // composter: base 1 per 10 min, 4,000 OM + 2,000 fuel each (research 08 1.11)
        GardenMath.CompostRun r = GardenMath.compost(40_000, 100_000, 0, 3_600, 600, 4_000, 2_000, 1_000);
        assertEquals(6, r.made());
        assertEquals(16_000, r.organic(), 1e-9);
        GardenMath.CompostRun starve = GardenMath.compost(5_000, 100_000, 0, 36_000, 600, 4_000, 2_000, 1_000);
        assertEquals(1, starve.made());
        // maxed speed tier is +500% (6x): 63 an hour with the 25% cost reduction is the research's "about 63"
        GardenTables.CompUpgrade speed = t.composterUpgrade("speed");
        assertEquals(500, speed.tiers.get(24).value, 1e-9);
        assertEquals(100, GardenMath.composterInterval(600, 500), 1e-9);
        GardenTables.CompUpgrade fuelCap = t.composterUpgrade("fuel_cap");
        assertEquals(850_000, fuelCap.tiers.get(fuelCap.tiers.size() - 1).value, 1e-9);
        GardenTables.CompUpgrade omCap = t.composterUpgrade("organic_matter_cap");
        assertEquals(790_000, omCap.tiers.get(omCap.tiers.size() - 1).value, 1e-9);
        // plots
        GardenTables.PlotGroup b = t.group("beginner");
        assertEquals(List.of(1, 2, 4, 8), b.costs.stream().map(c -> c.amount).toList());
        assertEquals(3, GardenMath.plotCostIndex(10, 4));
    }

    @Test
    void greenhouse() {
        GardenTables t = tables();
        GardenTables.GreenhouseTable g = t.greenhouse;
        assertEquals(14_400, GardenMath.stageSeconds(14_400, 6_063, 0, 0, 0, 0), 1e-9);
        // c=12, g=210: 14400 / (1 + 0.3 + 0.525) = 7890
        assertEquals(14_400 / 1.825, GardenMath.stageSeconds(14_400, 6_063, 12, 210, 0, 0), 1e-6);
        assertEquals(6_063, GardenMath.stageSeconds(14_400, 6_063, 12, 210, 1000, 0.5), 1e-9);
        GardenData.GreenhouseState s = new GardenData.GreenhouseState();
        GreenhouseSim.ensureCells(s, 1);
        assertEquals(25, s.cells.size());
        assertEquals(List.of(7, 11, 13, 17).stream().sorted().toList(), GreenhouseSim.neighbours(12).stream().sorted().toList());
        // wheat (harvest boost) next to carrot: the carrot gets +20% yield
        s.cells.get(12).crop = "CARROT";
        s.cells.get(13).crop = "WHEAT";
        GreenhouseSim.Received r = GreenhouseSim.received(g, s.cells, 12);
        assertEquals(20, r.yield(), 1e-9);
        // potato (immunity) shields the carrot from sugar cane's harvest loss
        s.cells.get(11).crop = "SUGAR_CANE";
        assertEquals(0, GreenhouseSim.received(g, s.cells, 12).yield(), 1e-9);
        s.cells.get(7).crop = "POTATO";
        assertEquals(20, GreenhouseSim.received(g, s.cells, 12).yield(), 1e-9);
        // growth: 8 stages of 1h each with plenty of water
        long now = 1_000_000_000L;
        for (GardenData.Cell c : s.cells) if (c.crop != null) { c.water = 1000; c.nextStageAt = now + 3_600_000L; }
        s.lastSim = now;
        GreenhouseSim.advance(g, s, now + 8 * 3_600_000L, 3_600_000L, new Random(1));
        assertTrue(GreenhouseSim.ready(g, s.cells.get(12)));
        assertEquals(Math.round(175 * (1 + 0.20 + 0.03 * 4)), GreenhouseSim.yield(g, s.cells, 12, 0));
        // a mutation whose recipe is satisfied can sprout in an empty cell
        GardenTables.Mutation m = g.mutations.get(0);
        assertTrue(GreenhouseSim.satisfied(m.needs, Map.of(m.needs.get(0), 4, m.needs.get(m.needs.size() - 1), 4)));
    }

    @Test
    void islandIsWalkableAndPresetsStayInTheirPlot() {
        IslandRun.Result r = IslandRun.run(new GardenGenerator(), IslandRun.seedOf("garden"));
        Walkability.Report rep = Walkability.analyse(r.canvas, r.layout, new Walkability.Options());
        assertTrue(rep.ok(), "walkability: " + rep.failed() + " " + rep.failedPortals() + " " + rep.spawnProblem);
        Layout l = r.layout;
        assertNotNull(l.anchor("spawn"));
        assertNotNull(l.anchor("desk"));
        assertNotNull(l.area("portal.hub"));
        int souls = 0;
        for (String a : l.anchors().keySet()) if (a.startsWith("fairy_soul.")) souls++;
        assertTrue(souls >= 10 && souls <= 40, "fairy souls " + souls);
        assertEquals(0, r.outsideBounds);
        // a preset writes only inside the plot's inner box
        Box in = PlotGrid.inner(2, 3);
        for (String preset : PlotPresets.ALL.keySet()) {
            MemoryCanvas mc = new MemoryCanvas();
            PlotPresets.write(mc, in, preset);
            Box b = mc.bounds();
            assertTrue(b.minX() >= in.minX() && b.maxX() <= in.maxX() && b.minZ() >= in.minZ() && b.maxZ() <= in.maxZ(), preset + " " + b);
        }
        // every barn skin builds inside the barn box
        for (String skin : BarnBuilder.SKINS.keySet()) {
            MemoryCanvas mc = new MemoryCanvas();
            BarnBuilder.build(mc, skin, 0);
            Box b = mc.bounds();
            Box x = BarnBuilder.BOX;
            assertTrue(b.minX() >= x.minX() && b.maxX() <= x.maxX() && b.minY() >= x.minY() && b.maxY() <= x.maxY()
                    && b.minZ() >= x.minZ() && b.maxZ() <= x.maxZ(), skin + " " + b);
        }
        assertNull(PlotGrid.isBarn(0, 0) ? "x" : null);
        assertEquals(PlotGrid.MIN, -240);
        assertEquals(-1, PlotGrid.colOf(240));
        assertEquals(2, PlotGrid.colOf(0));
    }

    @Test
    void pestKindsFollowTheCount() {
        GardenData.PlotState s = new GardenData.PlotState();
        Pests.addPest(s, "fly");
        Pests.addPest(s, "beetle");
        Pests.addPest(s, "fly");
        assertEquals(3, s.pests);
        assertEquals(List.of("fly", "beetle", "fly"), s.pestTypes);
        Pests.removePest(s, "beetle");
        assertEquals(2, s.pests);
        assertEquals(List.of("fly", "fly"), s.pestTypes);
        // an old save: a count with no kinds recorded never goes negative or keeps stale kinds
        GardenData.PlotState old = new GardenData.PlotState();
        old.pests = 2;
        Pests.removePest(old, "rat");
        Pests.removePest(old, "rat");
        Pests.removePest(old, "rat");
        assertEquals(0, old.pests);
        assertTrue(old.pestTypes.isEmpty());
        Pests.clearPests(s);
        assertEquals(0, s.pests);
        assertTrue(s.pestTypes.isEmpty());
    }
}
