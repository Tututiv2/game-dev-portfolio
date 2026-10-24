package com.skyclone.mod.garden;

import com.skyclone.api.world.gen.Box;
import com.skyclone.gen.IslandRun;
import com.skyclone.gen.MemoryCanvas;
import com.skyclone.gen.Walkability;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wave 5: the effect numbers, the chips and the barn / greenhouse skin shapes. Pure: no server. */
class GardenWave5Test {

    @Test
    void pieceGrowthMatchesTheReferenceLore() {
        // research/data/auctions_page0.json: Peony "+7, next +8 at 150" with 117 served, Blossom "+18, next at 1,500" with 1,244
        assertEquals(7, EffectMath.pieceBonus("LOTUS_BRACELET", 117));
        assertEquals(150, EffectMath.nextThreshold(117));
        assertEquals(9, EffectMath.pieceBonus("LOTUS_BELT", 480));
        assertEquals(18, EffectMath.pieceBonus("BLOSSOM_CLOAK", 1244));
        assertEquals(22.5, EffectMath.pieceBonus("BLOSSOM_NECKLACE", 2544));
        assertEquals(-1, EffectMath.nextThreshold(2544));
        assertEquals(0, EffectMath.pieceBonus("PESTHUNTERS_BELT", 5000));
        assertEquals(0, EffectMath.pieceBonus("LOTUS_BELT", 0));
    }

    @Test
    void effectFormulas() {
        // Overbloom: base roll + top-up together hit chance x (1 + ob/100)
        for (double c : new double[]{0.001, 0.03, 0.2}) for (double ob : new double[]{10, 50, 200}) {
            double top = EffectMath.topUpChance(c, ob);
            assertEquals(Math.min(1, c * (1 + ob / 100)), c + (1 - c) * top, 1e-9);
        }
        assertEquals(0, EffectMath.topUpChance(0.03, 0));
        assertEquals(175, EffectMath.buzzinBarterer(2, 140));
        assertEquals(30, EffectMath.eradicatorFortune(3, 10));
        assertEquals(40, EffectMath.eradicatorFortune(9, 10));
        // Hypercharge 10 levels (30%) on a 50 FF potion = +15
        assertEquals(15, EffectMath.hypercharge(50, 30), 1e-9);
        // Quickdraw: a wait spent harvesting throughout is exactly N% shorter
        double pct = 15;
        long wait = 900_000, left = wait, t = 0;
        while (left > 0) { left -= 20_000 + EffectMath.quickdrawExtraMillis(20_000, pct); t += 20_000; }
        assertEquals(wait * (1 - pct / 100), t, 20_000);
        assertEquals(0, EffectMath.quickdrawExtraMillis(20_000, 0));
        // Pesthunter's Bonus: kills x per pest, capped
        assertEquals(60, EffectMath.pesthunterBonus(500, 1, 60));
        assertEquals(12, EffectMath.pesthunterBonus(12, 1, 60));
        assertEquals(0, EffectMath.pesthunterBonus(-3, 1, 60));
    }

    @Test
    void chipsAreTheReferenceChips() {
        GardenTables t = GardenPureTest.tables();
        Set<String> known = Set.of("ff", "wisdom", "overbloom", "pest_chance", "greenhouse", "analyzer_copper", "visitor_harvest",
                "ff_buffs", "contest_fortune", "tool_xp");
        assertEquals(10, t.garden.chips.size());
        for (Map.Entry<String, GardenTables.Chip> e : t.garden.chips.entrySet())
            assertTrue(known.contains(e.getValue().kind), e.getKey() + " has an effect kind nothing applies: " + e.getValue().kind);
        // NEU items/*_GARDEN_CHIP.json lore, one number per level
        Map<String, Double> want = Map.of("CROPSHOT_GARDEN_CHIP", 3.0, "SOWLEDGE_GARDEN_CHIP", 1.0, "RAREFINDER_GARDEN_CHIP", 1.5,
                "VERMIN_VAPORIZER_GARDEN_CHIP", 3.0, "EVERGREEN_GARDEN_CHIP", 2.0, "SYNTHESIS_GARDEN_CHIP", 1.0,
                "QUICKDRAW_GARDEN_CHIP", 1.5, "HYPERCHARGE_GARDEN_CHIP", 3.0, "OVERDRIVE_GARDEN_CHIP", 5.0, "MECHAMIND_GARDEN_CHIP", 1.5);
        for (Map.Entry<String, Double> e : want.entrySet()) assertEquals(e.getValue(), t.garden.chips.get(e.getKey()).per, 1e-9, e.getKey());
        // Evergreen multiplies the additive yield bonuses (research 08 "Greenhouse")
        GardenTables.GreenhouseTable gt = t.greenhouse;
        GardenData.GreenhouseState g = new GardenData.GreenhouseState();
        GreenhouseSim.ensureCells(g, 1);
        g.cells.get(12).crop = "CARROT";
        g.cells.get(13).crop = "WHEAT";                       // +20% yield to the carrot, additive with +3%/unique crop
        double additive = 1 + 0.20 + 0.03 * 2;
        assertEquals(Math.round(175 * additive), GreenhouseSim.yield(gt, g.cells, 12, 0));
        assertEquals(Math.round(175 * additive * 1.4), GreenhouseSim.yield(gt, g.cells, 12, 40));
    }

    @Test
    void everySkinIsItsOwnBuildingAndKeepsTheAnchors() {
        // every shape a skin names is a real shape, and the barn's shapes are all used
        Set<String> barnShapes = new HashSet<>();
        for (BarnBuilder.Skin s : BarnBuilder.SKINS.values()) {
            assertTrue(BarnBuilder.SHAPES.contains(s.shape()), s.id() + " " + s.shape());
            barnShapes.add(s.shape());
        }
        assertEquals(new HashSet<>(BarnBuilder.SHAPES), barnShapes, "a barn shape no skin uses");
        Set<String> ghShapes = new HashSet<>();
        for (GreenhouseBuilder.Skin s : GreenhouseBuilder.SKINS.values()) {
            assertTrue(GreenhouseBuilder.SHAPES.contains(s.shape()), s.id() + " " + s.shape());
            assertTrue(ghShapes.add(s.shape()), "two greenhouse skins share the shape " + s.shape());
        }
        // the Desk, its stand and the loft are the same under every roof; everything stays in the barn box
        String deskBlock = null;
        Set<String> silhouettes = new HashSet<>();
        for (String id : BarnBuilder.SKINS.keySet()) {
            MemoryCanvas mc = new MemoryCanvas();
            BarnBuilder.build(mc, id, 0);
            Box b = mc.bounds(), x = BarnBuilder.BOX;
            assertTrue(b.minX() >= x.minX() && b.maxX() <= x.maxX() && b.minY() >= x.minY() && b.maxY() <= x.maxY()
                    && b.minZ() >= x.minZ() && b.maxZ() <= x.maxZ(), id + " " + b);
            String desk = mc.get(BarnBuilder.DESK_X, BarnBuilder.FLOOR_Y + 1, BarnBuilder.DESK_Z);
            assertTrue(desk != null && desk.startsWith("lectern"), id + " desk " + desk);
            if (deskBlock == null) deskBlock = desk; else assertEquals(deskBlock, desk);
            // (the stand is on the green carpet, so the head block is what must be free)
            assertTrue(mc.isAir(BarnBuilder.DESK_STAND[0], BarnBuilder.DESK_STAND[1] + 2, BarnBuilder.DESK_STAND[2]), id + " desk stand blocked");
            assertTrue(mc.isAir(BarnBuilder.LOFT_SPOT[0], BarnBuilder.LOFT_SPOT[1] + 1, BarnBuilder.LOFT_SPOT[2]), id + " loft blocked");
            assertTrue(!mc.isAir(BarnBuilder.LOFT_SPOT[0], BarnBuilder.LOFT_SPOT[1], BarnBuilder.LOFT_SPOT[2]), id + " loft floor gone");
            // silhouette: the highest block of every column above the wall line
            StringBuilder sil = new StringBuilder(BarnBuilder.skin(id).shape()).append('|');
            for (int xx = x.minX(); xx <= x.maxX(); xx += 3) for (int zz = x.minZ(); zz <= x.maxZ(); zz += 3) {
                int top = 0;
                for (int y = x.maxY(); y > BarnBuilder.WALL_TOP; y--) if (!mc.isAir(xx, y, zz)) { top = y; break; }
                sil.append(top).append(',');
            }
            silhouettes.add(sil.toString());
        }
        assertTrue(silhouettes.size() >= BarnBuilder.SHAPES.size(), "barn skins do not differ in shape: " + silhouettes.size());
        for (String id : GreenhouseBuilder.SKINS.keySet()) {
            MemoryCanvas mc = new MemoryCanvas();
            int[] bot = GreenhouseBuilder.build(mc, id);
            Box b = mc.bounds(), x = GreenhouseBuilder.PLACE_BOX;
            assertTrue(b.minX() >= x.minX() && b.maxX() <= x.maxX() && b.minY() >= x.minY() && b.maxY() <= x.maxY()
                    && b.minZ() >= x.minZ() && b.maxZ() <= x.maxZ(), "greenhouse " + id + " " + b + " outside " + x);
            assertTrue(mc.isAir(bot[0], bot[1] + 1, bot[2]) && mc.isAir(bot[0], bot[1] + 2, bot[2]), id + " botanist spot blocked");
        }
    }

    @Test
    void theSkinShowcaseIsWalkable() {
        // every barn skin's Desk and loft and every greenhouse's botanist corner reachable on foot
        IslandRun.Result r = IslandRun.run(new SkinShowcase(), 1L);
        Walkability.Report rep = Walkability.analyse(r.canvas, r.layout, new Walkability.Options());
        assertTrue(rep.ok(), "walkability: " + rep.failed() + " " + rep.failedPortals() + " " + rep.spawnProblem);
        assertEquals(0, r.outsideBounds);
        int anchors = r.layout.anchors().size();
        assertEquals(1 + 2 * BarnBuilder.SKINS.size() + GreenhouseBuilder.SKINS.size(), anchors);
        assertTrue(List.copyOf(r.layout.anchors().keySet()).contains("barn.castle.loft"));
    }
}
