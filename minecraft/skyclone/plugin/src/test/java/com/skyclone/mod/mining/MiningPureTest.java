package com.skyclone.mod.mining;

import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** The mining module's pure parts against the research numbers (research/07 section 2). */
class MiningPureTest {

    private static Reader res(String f) throws Exception {
        Path p = Path.of("src/main/resources/data/mining", f);
        if (!Files.exists(p)) p = Path.of("plugin/src/main/resources/data/mining", f);
        return new InputStreamReader(Files.newInputStream(p), StandardCharsets.UTF_8);
    }

    @Test
    void ticksToBreakMatchesTheResearchTable() {
        // research 2.3 worked examples
        assertEquals(30, MiningMath.ticksToBreak(500, 500, true));
        assertEquals(15, MiningMath.ticksToBreak(500, 1000, true));
        assertEquals(120, MiningMath.ticksToBreak(2000, 500, true));
        assertEquals(8, MiningMath.ticksToBreak(2000, 8000, true));
        assertEquals(138, MiningMath.ticksToBreak(2300, 500, true));
        assertEquals(23, MiningMath.ticksToBreak(3000, 4000, true));
        assertEquals(360, MiningMath.ticksToBreak(6000, 500, true));
        assertEquals(4, MiningMath.ticksToBreak(50, 500, false));     // floor
        assertEquals(0, MiningMath.ticksToBreak(15, 451, false));     // stone instamine at 451
        assertEquals(4, MiningMath.ticksToBreak(15, 450, false));
        assertEquals(0, MiningMath.ticksToBreak(50, 1501, false));    // hard stone instamine > 1,500
        assertNotEquals(0, MiningMath.ticksToBreak(30, 1800, true));  // ores need > 1,800
        assertEquals(0, MiningMath.ticksToBreak(30, 1801, true));
        assertEquals(Integer.MAX_VALUE, MiningMath.ticksToBreak(30, 0, true));
        assertEquals(200, MiningMath.speedForFloor(30));
    }

    @Test
    void fortunePristineSpread() {
        assertEquals(4, MiningMath.fortuneDrops(1, 250, 0.49));
        assertEquals(3, MiningMath.fortuneDrops(1, 250, 0.51));
        assertEquals(2, MiningMath.fortuneDrops(1, 100, 0.0));
        assertEquals(0, MiningMath.fortuneDrops(0, 500, 0.0));
        assertEquals(1, MiningMath.spreadBlocks(150, 0.9));
        assertEquals(2, MiningMath.spreadBlocks(150, 0.1));
        assertEquals(0, MiningMath.spreadBlocks(0, 0.0));
        // Pristine 10% over many gems: ~10%
        int n = MiningMath.pristineProcs(100_000, 10, new Random(1));
        assertTrue(n > 9_500 && n < 10_500, "pristine procs " + n);
        assertEquals(1.0 * 1.079 * 2.0, MiningMath.expectedRough(10, 100), 1e-9);
    }

    @Test
    void hotmTreeIsComplete() throws Exception {
        HotmTree t;
        try (Reader r = res("hotm.json")) { t = HotmTree.load(r); }
        assertEquals(46, t.perks.size());
        assertEquals(1_247_000, t.xpForTier(10));
        assertEquals(1, t.tier(0));
        assertEquals(2, t.tier(3_000));
        assertEquals(3, t.tier(12_000));
        assertEquals(10, t.tier(5_000_000));
        assertEquals(23, t.tokensFor(10, 10));
        HotmTree.Perk ms = t.perk("mining_speed");
        assertEquals(27, ms.costToNext(1));        // (1+2)^3
        assertEquals(1000, ms.value(50));
        assertEquals(MiningMath.perkCost(2, 3.0), ms.costToNext(1));
        HotmTree.Perk core = t.perk("core_of_the_mountain");
        assertEquals(10, core.maxLevel);
        assertEquals(50_000, core.costToNext(1));
        assertEquals("GLACITE", core.powderFor(9));
        HotmTree.Perk mm = t.perk("mining_master");
        assertEquals(59_049, mm.costToNext(1));    // (1+8)^5
        // the root is tier 1, everything reachable through neighbours
        Map<String, Integer> lv = new HashMap<>();
        assertTrue(t.reachable(ms, lv, 1));
        HotmTree.Perk fortune = t.perk("mining_fortune");
        assertFalse(t.reachable(fortune, lv, 2));
        lv.put("mining_speed", 1);
        assertTrue(t.reachable(fortune, lv, 2));
        assertFalse(t.reachable(fortune, lv, 1));
    }

    @Test
    void blockTableMeaningDependsOnTheIsland() throws Exception {
        BlockTable b;
        try (Reader r = res("blocks.json")) { b = BlockTable.load(r); }
        assertEquals("tungsten", b.lookup("glacite_tunnels", "COBBLESTONE").id);
        assertEquals("cobblestone", b.lookup("gold_mine", "COBBLESTONE").id);
        assertEquals("hard_stone", b.lookup("crystal_hollows", "GRAY_WOOL").id);
        assertEquals("mithril_gray", b.lookup("dwarven_mines", "GRAY_WOOL").id);
        assertEquals("gem_ruby", b.lookup("crystal_hollows", "RED_STAINED_GLASS_PANE").id);
        assertNull(b.lookup("dwarven_mines", "RED_STAINED_GLASS"));   // no gems in the Dwarven Mines (windows stay windows)
        assertNull(b.lookup("hub", "BEDROCK"));
        BlockTable.Def ti = b.lookup("dwarven_mines", "POLISHED_DIORITE");
        assertEquals(2000, ti.strength);
        assertEquals(5, ti.breakingPower);
        assertTrue(ti.kind.sixtyRule());
        assertFalse(b.lookup("crystal_hollows", "STONE").kind.sixtyRule());
        assertEquals(6000, b.lookup("glacite_tunnels", "PACKED_ICE").strength);
    }

    @Test
    void puzzleAlwaysStaysOnTheBoard() {
        for (int i = 0; i < 500; i++) {
            int[] mv = MiningMenus.puzzle("seed" + i);
            int[] end = MiningMenus.solve(mv);
            assertTrue(end[0] >= 0 && end[0] <= 4 && end[1] >= 0 && end[1] <= 4);
        }
    }

    @Test
    void mineshaftExitAndFiesta() {
        assertEquals(1000, MiningMath.mineshaftExitPowder(0, 0, 0));
        assertEquals(1000 + 500 + 250 + 100, MiningMath.mineshaftExitPowder(1, 1, 10));
        // Aug-2026 rework: BP / 10,000 (Ruby BP 6 = 0.06%, Topaz BP 8 = 0.08%); the old /2,000 is 5x too generous
        assertEquals(0.0001, MiningMath.fiestaChance(1, 0), 1e-12);
        assertEquals(0.0006, MiningMath.fiestaChance(6, 0), 1e-12);
        assertEquals(0.0008, MiningMath.fiestaChance(8, 0, 10_000), 1e-12);
        assertEquals(0.0045, MiningMath.fiestaChance(9, 0, 2_000), 1e-12);
        assertEquals(0, MiningMath.fiestaChance(9, 0, 0), 1e-12);
    }

    /** Audit 2026-10-06 (docs/AUDIT_REAL_GAME_2026-10-06.md section 2): the formula rows M1, M5/M17, M11. */
    @Test
    void auditForgeSlotsColdAndWormCooldown() {
        // M1: 2 slots at HOTM 2, +1 per tier, 7 at HOTM 7 (and still 7 at 10); none below HOTM 2
        assertEquals(0, MiningMath.forgeSlots(1, 2, 7, false, 0));
        assertEquals(2, MiningMath.forgeSlots(2, 2, 7, false, 0));
        assertEquals(5, MiningMath.forgeSlots(5, 2, 7, false, 2));   // HOTM 5 with Core II: 5, not 6
        assertEquals(7, MiningMath.forgeSlots(7, 2, 7, false, 0));
        assertEquals(7, MiningMath.forgeSlots(10, 2, 7, false, 10));
        assertEquals(6, MiningMath.forgeSlots(5, 2, 7, true, 2));    // the old Core slot, behind its dial
        assertEquals(0, MiningMath.forgeSlots(1, 2, 7, true, 2));
        // M11: -0.5% Mining Speed per Cold, continuous
        assertEquals(1.0, MiningMath.coldSpeedMultiplier(0, 0.5), 1e-12);
        assertEquals(0.95, MiningMath.coldSpeedMultiplier(10, 0.5), 1e-12);
        assertEquals(0.75, MiningMath.coldSpeedMultiplier(50, 0.5), 1e-12);
        assertEquals(0.5, MiningMath.coldSpeedMultiplier(100, 0.5), 1e-12);
        assertEquals(0.0, MiningMath.coldSpeedMultiplier(300, 0.5), 1e-12);
        // M17: one worm per 30 s
        assertTrue(MiningMath.cooldownOver(null, 1_000, 30));
        assertFalse(MiningMath.cooldownOver(1_000L, 30_999, 30));
        assertTrue(MiningMath.cooldownOver(1_000L, 31_000, 30));
    }

    /** Audit M2, M5-M10, M12-M16: the generated data carries the reference numbers. */
    @Test
    void auditLootMobsForgeAndBlocks() throws Exception {
        com.google.gson.JsonObject loot;
        try (Reader r = res("loot.json")) { loot = com.google.gson.JsonParser.parseReader(r).getAsJsonObject(); }
        MiningLoot.File f;
        try (Reader r = res("loot.json")) { f = new com.google.gson.Gson().fromJson(r, MiningLoot.File.class); }

        // M9/M14-M16: the Crystal Nucleus bundle
        MiningLoot.Table n = f.nucleus;
        assertArrayEquals(new int[]{14, 19}, n.rolls);
        assertEquals(800, n.hotmXp, 1e-9);
        assertEquals(java.util.Set.of("FINE_AMETHYST_GEM", "FINE_JADE_GEM", "FINE_SAPPHIRE_GEM", "FINE_AMBER_GEM", "FINE_TOPAZ_GEM"),
                n.always.stream().map(l -> l.item).collect(java.util.stream.Collectors.toSet()));
        double total = 0;
        Map<String, Double> w = new HashMap<>();
        for (MiningLoot.Line l : n.table) { total += l.weight; w.merge(l.item, l.weight, Double::sum); }
        assertEquals(307_212, total, 1.0);
        assertFalse(w.containsKey("PRECURSOR_APPARATUS"), "the Nucleus must not pay back the Apparatus a run consumes");
        assertEquals(0.318, MiningMath.bundleChance(w.get("DIVAN_FRAGMENT"), total, 14, 19), 0.002);
        assertEquals(0.077, MiningMath.bundleChance(w.get("JADERALD"), total, 14, 19), 0.002);
        assertEquals(0.037, MiningMath.bundleChance(w.get("GEMSTONE_MIXTURE"), total, 14, 19), 0.001);
        assertEquals(0.0009, MiningMath.bundleChance(w.get("DIVAN_ALLOY"), total, 14, 19), 0.00005);

        // M8: 4.5 rolls per treasure chest on average
        assertArrayEquals(new int[]{3, 6}, f.treasure_chest.rolls);

        // M12/M13: corpse powder + HOTM XP, NEU tables (Ice Cold book, Skeleton Key, Blue Goblin Egg, Vanguard's pendant)
        assertArrayEquals(new int[]{500, 500}, f.corpse.get("lapis").powder);
        assertEquals(100, f.corpse.get("lapis").hotmXp, 1e-9);
        assertArrayEquals(new int[]{5000, 5000}, f.corpse.get("umber").powder);
        assertArrayEquals(new int[]{5000, 5000}, f.corpse.get("tungsten").powder);
        assertArrayEquals(new int[]{25000, 25000}, f.corpse.get("vanguard").powder);
        assertArrayEquals(new int[]{3, 6}, f.corpse.get("lapis").rolls);
        assertArrayEquals(new int[]{4, 7}, f.corpse.get("umber").rolls);
        assertArrayEquals(new int[]{5, 8}, f.corpse.get("vanguard").rolls);
        for (String c : java.util.List.of("umber", "tungsten")) {
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (MiningLoot.Line l : f.corpse.get(c).table) ids.add(l.item);
            assertTrue(ids.containsAll(java.util.List.of("ENCHANTMENT_ICE_COLD_1", "SKELETON_KEY", "GOBLIN_EGG_BLUE", "SUSPICIOUS_SCRAP")), c + " " + ids);
        }
        assertTrue(f.corpse.get("vanguard").table.stream().anyMatch(l -> l.item.equals("SHATTERED_PENDANT")));
        // follow-up 2026-10-06: materials registers the cereal now, so the corpse tables carry it again
        assertTrue(f.corpse.get("umber").table.stream().anyMatch(l -> l.item.equals("DWARVEN_OS_METALLIC_MINIS")), "Metallic Minis back in the corpses");
        assertTrue(Consumables.ownScroll("forge") && !Consumables.ownScroll("crimson_skull") && !Consumables.ownScroll("jungle"),
                "mining reads only its own travel scrolls");
        for (MiningLoot.Table t : f.corpse.values()) for (MiningLoot.Line l : t.table) assertFalse(l.item.contains(";"), l.item);
        assertTrue(loot.getAsJsonObject("corpse").getAsJsonObject("lapis").get("key").isJsonNull());

        // M6/M7: worm drops and Scatha pet odds
        com.google.gson.JsonArray mobs;
        try (Reader r = res("mobs.json")) { mobs = com.google.gson.JsonParser.parseReader(r).getAsJsonArray(); }
        Map<String, Map<String, double[]>> drops = new HashMap<>();
        for (var el : mobs) {
            var o = el.getAsJsonObject();
            Map<String, double[]> d = new HashMap<>();
            for (var de : o.getAsJsonArray("drops")) {
                var x = de.getAsJsonObject();
                d.put(x.get("item").getAsString(), new double[]{x.get("min").getAsDouble(), x.get("max").getAsDouble(), x.get("chance").getAsDouble()});
            }
            drops.put(o.get("id").getAsString(), d);
        }
        var worm = drops.get("mining_worm");
        assertArrayEquals(new double[]{1, 1, 1.0}, worm.get("WORM_MEMBRANE"));
        assertEquals(1.0, worm.get("ROUGH_TOPAZ_GEM")[2], 1e-12);
        var scatha = drops.get("mining_scatha");
        assertArrayEquals(new double[]{4, 4, 1.0}, scatha.get("WORM_MEMBRANE"));
        assertEquals(1.0, scatha.get("FINE_TOPAZ_GEM")[2], 1e-12);
        assertEquals(0.0024, scatha.get("SCATHA;2")[2], 1e-12);
        assertEquals(0.0012, scatha.get("SCATHA;3")[2], 1e-12);
        assertEquals(0.0004, scatha.get("SCATHA;4")[2], 1e-12);

        // M2: the Mithril pieces need HOTM II
        com.google.gson.JsonArray forge;
        try (Reader r = res("forge.json")) { forge = com.google.gson.JsonParser.parseReader(r).getAsJsonArray(); }
        int mithril = 0;
        for (var el : forge) {
            var o = el.getAsJsonObject();
            String u = o.get("unlock").isJsonNull() ? "" : o.get("unlock").getAsString();
            assertFalse(u.matches(".*hotm:1(?![0-9]).*"), o.get("result") + " " + u);   // nothing forges below HOTM II
            if (o.get("result").getAsString().matches("MITHRIL_(BELT|CLOAK|NECKLACE|GAUNTLET)")) { mithril++; assertEquals("hotm:2", u); }
        }
        assertEquals(4, mithril);

        // M10: mineshaft pity points - mithril 2, titanium 8, every gemstone 8
        BlockTable b;
        try (Reader r = res("blocks.json")) { b = BlockTable.load(r); }
        for (BlockTable.Def d : b.all()) {
            if (d.kind == BlockTable.Kind.GEMSTONE) assertEquals(8, d.quality, d.id);
            if (d.isTitanium()) assertEquals(8, d.quality, d.id);
            if (d.isMithril()) assertEquals(2, d.quality, d.id);
        }
    }

    @Test
    void reforgeFindBonusIsFoldedInOnce() {
        double base = 0.0004;
        // no bonus: unchanged
        assertEquals(base * 2, MiningMath.findChance(base, 2, 0, 0), 1e-15);
        // Ambered Legendary (+30%) on top of Cave Luck x2.5 and Sky Mall x10
        double mult = 2.5 * 10, pct = 30;
        double whole = base * mult * 1.3;
        assertEquals(whole, MiningMath.findChance(base, mult, pct, 0), 1e-15);
        // while reforges still rolls base x pct/100 itself, the two rolls together pay the whole bonus exactly once
        double theirs = MiningMath.reforgesExtraRoll(base, pct);
        assertEquals(base * 0.3, theirs, 1e-15);
        assertEquals(whole, MiningMath.findChance(base, mult, pct, theirs) + theirs, 1e-15);
        // and mining's own roll never drops below what it paid before the reforge existed
        assertTrue(MiningMath.findChance(base, 1, pct, theirs) >= base - 1e-15);
        // Blazing: 4 pieces x 0.05%/Heat x 100 Heat = +20% worms
        assertEquals(0.003 * 1.2, MiningMath.findChance(0.003, 1, 4 * 0.05 * 100, 0), 1e-15);
        // clamped
        assertEquals(1.0, MiningMath.findChance(0.5, 10, 0, 0), 0);
        assertEquals(0.0, MiningMath.findChance(0.001, 1, 0, 0.5), 0);
    }

    @Test
    void excavatorGivesFossilEssence() throws Exception {
        try (Reader r = res("loot.json")) {
            MiningLoot.File f = new com.google.gson.Gson().fromJson(r, MiningLoot.File.class);
            MiningLoot.Line ess = f.excavator.table.stream().filter(l -> "ESSENCE_FOSSIL".equals(l.item)).findFirst().orElse(null);
            assertNotNull(ess, "excavator table has no ESSENCE_FOSSIL line");
            assertEquals(10, ess.min);   // research 2.14: 10-40 each
            assertEquals(40, ess.max);
            assertTrue(ess.weight > 0);
        }
    }

    @Test
    void commissionSidebarLineIsOneLineInSlotOrder() {
        MiningData.Commission done = new MiningData.Commission("a", 10);
        done.progress = 12;                                   // over-filled still reads DONE
        MiningData.Commission half = new MiningData.Commission("b", 350);
        half.progress = 159;                                  // 45.4% floors to 45
        MiningData.Commission almost = new MiningData.Commission("c", 3);
        almost.progress = 2;                                  // 66.6% floors, never rounds up to look done
        assertEquals("&9Commissions: &aDONE&7, &e45%&7, &e66%", MiningMath.commissionLine(java.util.List.of(done, half, almost)));
        assertEquals("", MiningMath.commissionLine(java.util.List.of()));
        assertEquals("", MiningMath.commissionLine(null));
        assertEquals(0, MiningMath.commissionPercent(-5, 10));
        assertEquals(99, MiningMath.commissionPercent(99, 100));
        assertEquals(100, MiningMath.commissionPercent(0, 0));
        // 4 slots (Milestone III + Core III) must still be one sidebar-width line
        MiningData.Commission q = new MiningData.Commission("d", 100);
        q.progress = 99;
        String four = MiningMath.commissionLine(java.util.List.of(half, half, half, q));
        assertTrue(four.replaceAll("&.", "").length() <= 32, four);
    }

    // ---- wave 5 ---------------------------------------------------------------------------------------

    @Test
    void islandBreakFactorMakesVanillaTakeTheFormulaTicks() {
        double hand = 1.0 / 1.5 / 100.0;          // stone with no correct tool: what a drill is to vanilla
        double pick = 6.0 / 1.5 / 30.0;           // stone with a vanilla iron pickaxe
        for (double base : new double[]{hand, pick}) {
            for (int ticks : new int[]{4, 5, 9, 30, 120, 999}) {
                double k = MiningMath.breakSpeedScale(ticks, base);
                assertEquals(ticks, MiningMath.vanillaTicks(base * k), "ticks " + ticks + " base " + base);
            }
            assertEquals(0, MiningMath.vanillaTicks(base * MiningMath.breakSpeedScale(0, base)), "instamine");
        }
        assertEquals(1.0, MiningMath.breakSpeedScale(Integer.MAX_VALUE, hand));   // unbreakable: vanilla untouched
        assertEquals(1.0, MiningMath.breakSpeedScale(30, 0));                     // unusable base
        assertTrue(MiningMath.breakSpeedScale(4, 1e-9) <= 1000);                  // attribute cap respected
    }

    @Test
    void gemIdsParseAndTheGauntletSpillsRoughGems() {
        assertEquals("JADE", MiningMath.gemType("FINE_JADE_GEM"));
        assertEquals("AQUAMARINE", MiningMath.gemType("PERFECT_AQUAMARINE_GEM"));
        assertEquals(4, MiningMath.gemQuality("PERFECT_RUBY_GEM"));
        assertEquals(-1, MiningMath.gemQuality("RUBY_CRYSTAL"));
        assertNull(MiningMath.gemType("JADE_CRYSTAL"));
        assertEquals(1, MiningMath.gauntletSpill("ROUGH_RUBY_GEM", 1));
        assertEquals(5, MiningMath.gauntletSpill("PERFECT_RUBY_GEM", 1));
        assertEquals(10, MiningMath.gauntletSpill("PERFECT_RUBY_GEM", 2));
        assertEquals(0, MiningMath.gauntletSpill("NOT_A_GEM", 1));
    }

    @Test
    void structuresMapToTheirCrystals() {
        assertEquals("AMETHYST", MiningMath.structureGem("structure.jungle_temple"));
        assertEquals("JADE", MiningMath.structureGem("structure.mines_of_varnas"));
        assertEquals("SAPPHIRE", MiningMath.structureGem("structure.lost_relic_city"));
        assertEquals("AMBER", MiningMath.structureGem("structure.goblin_burrow"));
        assertEquals("AMBER", MiningMath.structureGem("structure.goblin_queen_den"));
        assertEquals("TOPAZ", MiningMath.structureGem("structure.emberdeep"));
        assertNull(MiningMath.structureGem("structure.gang_hideout"));
        assertNull(MiningMath.structureGem("structure.fairy_grotto_2"));
        assertNull(MiningMath.structureGem("landmark.dragons_lair"));
    }

    @Test
    void chestZoneLootIsLikelierAtHome() throws Exception {
        assertEquals(3.0, MiningMath.zoneWeight(1, "SAPPHIRE", "SAPPHIRE", 3, 0.25));
        assertEquals(0.25, MiningMath.zoneWeight(1, "SAPPHIRE", "RUBY", 3, 0.25));
        assertEquals(40, MiningMath.zoneWeight(40, null, "RUBY", 3, 0.25));
        // the shipped table: every robot part belongs to the relic ruins
        com.google.gson.JsonObject o;
        try (Reader r = res("loot.json")) { o = com.google.gson.JsonParser.parseReader(r).getAsJsonObject(); }
        int robots = 0;
        for (var el : o.getAsJsonObject("treasure_chest").getAsJsonArray("table")) {
            var line = el.getAsJsonObject();
            String item = line.get("item").getAsString();
            if (java.util.Set.of("CONTROL_SWITCH", "ELECTRON_TRANSMITTER", "FTX_3070", "ROBOTRON_REFLECTOR", "SUPERLITE_MOTOR", "SYNTHETIC_HEART").contains(item)) {
                assertEquals("SAPPHIRE", line.get("zoneGem").getAsString(), item);
                robots++;
            }
        }
        assertEquals(6, robots);
    }

    @Test
    void chiselGemsAndOreExperience() {
        assertEquals(0.30, MiningMath.chiselAquamarine("ROUGH_AQUAMARINE_GEM"), 1e-9);
        assertEquals(1.00, MiningMath.chiselAquamarine("PERFECT_AQUAMARINE_GEM"), 1e-9);
        assertEquals(0, MiningMath.chiselAquamarine(null), 1e-9);
        assertEquals(1, MiningMath.chiselCitrine("ROUGH_CITRINE_GEM"));
        assertEquals(5, MiningMath.chiselCitrine("PERFECT_CITRINE_GEM"));
        assertEquals(1, MiningMath.chiselOnyx("ROUGH_ONYX_GEM"));
        assertEquals(3, MiningMath.chiselOnyx("PERFECT_ONYX_GEM"));
        assertEquals(25, MiningMath.chiselPeridot("PERFECT_PERIDOT_GEM"), 1e-9);
        assertArrayEquals(new int[]{0, 2}, MiningMath.oreExp("COAL_ORE"));
        assertArrayEquals(new int[]{3, 7}, MiningMath.oreExp("DEEPSLATE_EMERALD_ORE"));
        assertArrayEquals(new int[]{2, 5}, MiningMath.oreExp("NETHER_QUARTZ_ORE"));
        assertArrayEquals(new int[]{0, 0}, MiningMath.oreExp("IRON_ORE"));
        assertArrayEquals(new int[]{0, 0}, MiningMath.oreExp("COAL_BLOCK"));
    }

    @Test
    void abilityNumbersComeFromTheData() throws Exception {
        HotmTree t;
        try (Reader r = res("hotm.json")) { t = HotmTree.load(r); }
        int abilities = 0;
        for (HotmTree.Perk p : t.perks) {
            if (!p.ability) continue;
            abilities++;
            for (String w : new String[]{"value", "duration", "cooldown"})
                for (int lvl = 1; lvl <= 3; lvl++) assertFalse(Double.isNaN(p.ability(w, lvl)), p.id + " " + w + " " + lvl);
            assertTrue(p.ability("cooldown", 1) >= p.ability("cooldown", 3), p.id + " cooldown grows with level");
        }
        assertEquals(6, abilities);
        // research 2.7.4 / NEU hotmlayout
        assertEquals(200, t.perk("mining_speed_boost").ability("value", 1));
        assertEquals(20, t.perk("mining_speed_boost").ability("duration", 3));
        assertEquals(60, t.perk("pickobulus").ability("cooldown", 1));
        assertEquals(40, t.perk("pickobulus").ability("cooldown", 3));
        assertEquals(110, t.perk("anomalous_desire").ability("cooldown", 2));
        assertEquals(10, t.perk("maniac_miner").ability("value", 2));
        assertEquals(140, t.perk("gemstone_infusion").ability("cooldown", 1));
        assertEquals(25, t.perk("sheer_force").ability("duration", 2));
        assertEquals(20, t.perk("sheer_force").ability("duration", 0));   // clamped to level 1
        assertTrue(Double.isNaN(t.perk("mining_speed").ability("value", 1)));
    }

    @Test
    void excavatorPoolHasTheBookTheCurioAndTheDye() throws Exception {
        com.google.gson.JsonObject o;
        try (Reader r = res("loot.json")) { o = com.google.gson.JsonParser.parseReader(r).getAsJsonObject(); }
        var ex = o.getAsJsonObject("excavator");
        assertEquals(1.0 / 500_000, ex.get("dyeChance").getAsDouble(), 1e-12);
        assertEquals("DYE_FOSSIL", ex.get("dye").getAsString());
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (var el : ex.getAsJsonArray("table")) ids.add(el.getAsJsonObject().get("item").getAsString());
        assertTrue(ids.contains("ENCHANTMENT_PALEONTOLOGIST_1"));
        assertTrue(ids.contains("FOSSIL_THE_FISH"));
        assertTrue(ids.contains("ESSENCE_FOSSIL"));
        // both new items are registered by mining itself
        String items;
        try (Reader r = res("items.json")) { items = new String(new java.io.BufferedReader(r).lines().reduce("", (a, b) -> a + b)); }
        assertTrue(items.contains("\"FOSSIL_THE_FISH\""));
        assertTrue(items.contains("\"DYE_FOSSIL\""));
    }

    /**
     * Wave-5 regression: a Goblin Raid killed a 689-HP / 644-Defense player in 9 s (a Lv90 700-damage Warlord spawned
     * on every player). The roster is now the reference's Goblin Raiders family: mostly Lv5 weaklings.
     */
    @Test
    void goblinRaidIsMostlyWeaklingsAndNeverAWarlord() throws Exception {
        com.google.gson.JsonArray mobs;
        try (Reader r = res("mobs.json")) { mobs = com.google.gson.JsonParser.parseReader(r).getAsJsonArray(); }
        Map<String, com.google.gson.JsonObject> byId = new HashMap<>();
        for (var el : mobs) byId.put(el.getAsJsonObject().get("id").getAsString(), el.getAsJsonObject());
        assertFalse(byId.containsKey("mining_raid_warlord"));
        assertFalse(byId.containsKey("mining_raid_goblin"));
        for (String id : MiningMath.RAID_IDS) {
            var o = byId.get(id);
            assertNotNull(o, id);
            assertEquals("goblin_raiders", o.get("family").getAsString(), id);
            assertTrue(o.get("level").getAsInt() <= 150, id);
            if (!MiningMath.raidHeavy(id)) assertTrue(o.get("damage").getAsDouble() <= 250, id + " damage");
        }
        assertEquals(5, byId.get("mining_raid_weakling").get("level").getAsInt());
        assertEquals(5, byId.get("mining_raid_weakling_bow").get("level").getAsInt());
        // a Superior Dragon wearer (644 Defense -> 86.6% reduction) takes under 10 a hit from a weakling
        double dmg = byId.get("mining_raid_weakling").get("damage").getAsDouble() * (1 - 644.0 / (644 + 100));
        assertTrue(dmg < 10, "weakling hit " + dmg);

        // the pick: weaklings are the bulk, heavies only when allowed, the full range reachable
        Random rnd = new Random(42);
        int weak = 0, heavyNo = 0, n = 20_000;
        for (int i = 0; i < n; i++) {
            String id = MiningMath.raidPick(rnd.nextDouble(), true);
            if (id.startsWith("mining_raid_weakling")) weak++;
            if (MiningMath.raidHeavy(MiningMath.raidPick(rnd.nextDouble(), false))) heavyNo++;
        }
        assertEquals(0, heavyNo);
        assertEquals(0.70, weak / (double) n, 0.02);
        assertEquals("mining_raid_weakling", MiningMath.raidPick(0, true));
        assertEquals("mining_raid_golem", MiningMath.raidPick(1, true));
        assertEquals("mining_raid_battler", MiningMath.raidPick(1, false));
        // waves never pass the cap
        assertEquals(6, MiningMath.raidWave(0, 20, 6));
        assertEquals(2, MiningMath.raidWave(18, 20, 6));
        assertEquals(0, MiningMath.raidWave(30, 20, 6));
        assertEquals(0, MiningMath.raidWave(0, 20, -3));
        assertFalse(MiningMath.RAID_ZONES.contains("dwarven_village"));
    }
}
