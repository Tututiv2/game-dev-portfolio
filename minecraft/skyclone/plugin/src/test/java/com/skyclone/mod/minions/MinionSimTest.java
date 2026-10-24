package com.skyclone.mod.minions;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.skyclone.mod.minions.MinionCatalog.TierDef;
import com.skyclone.mod.minions.MinionCatalog.TypeDef;
import com.skyclone.mod.minions.MinionStore.Placed;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the SHIPPED minion data through the pure production model. No server needed. */
class MinionSimTest {

    static Path dir;
    static MinionCatalog cat;
    static final long T0 = 1_700_000_000_000L;
    static final long HOUR = 3_600_000L;

    /** Every item stacks to 64 except ender pearls (16); prices: 1 coin unless listed. */
    static final class Env implements MinionSim.Env {
        double speed;
        double mult = 1;
        Map<String, Double> prices = new HashMap<>();
        @Override public double speedBonus(Placed m, TypeDef t) { return speed; }
        @Override public double outputMult(Placed m, TypeDef t) { return mult; }
        @Override public boolean solarActive() { return true; }
        @Override public int maxStack(String id) { return "ENDER_PEARL".equals(id) ? 16 : 64; }
        @Override public double npcPrice(String id) { return prices.getOrDefault(id, 1.0); }
        @Override public boolean known(String id) { return true; }
    }

    @BeforeAll
    static void load() {
        for (String p : new String[]{"src/main/resources/data/minions", "plugin/src/main/resources/data/minions"}) {
            if (Files.isDirectory(Path.of(p))) { dir = Path.of(p); break; }
        }
        assertNotNull(dir, "cannot find data/minions from " + Path.of("").toAbsolutePath());
        cat = MinionCatalog.load(f -> {
            try { return Files.newBufferedReader(dir.resolve(f), StandardCharsets.UTF_8); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        });
    }

    static Placed minion(String type, int tier) {
        Placed m = new Placed();
        m.id = "t";
        m.type = type;
        m.tier = tier;
        m.world = "w";
        m.lastUpdate = T0;
        return m;
    }

    @Test
    void catalogueCoversEveryType() {
        assertEquals(61, cat.types.size(), "research: 61 minion types");
        int tiers = 0;
        for (TypeDef t : cat.types()) {
            assertFalse(t.drops.isEmpty(), t.id + " drops");
            assertNotNull(t.unlock, t.id + " unlock");
            for (int i = 0; i < t.tiers.size(); i++) {
                TierDef td = t.tiers.get(i);
                assertEquals(i + 1, td.tier, t.id + " tiers contiguous");
                assertTrue(td.tba > 0 && td.storage > 0, t.id + " tba/storage");
                if (i > 0) assertTrue(td.tba <= t.tiers.get(i - 1).tba, t.id + " never slower at a higher tier");
            }
            tiers += t.tiers.size();
        }
        assertEquals(719, tiers, "reference: 719 minion items");
    }

    @Test
    void researchNumbers() {
        // research 02 section 4.1 / 4.10
        assertEquals(14, cat.type("COBBLESTONE").tier(1).tba);
        assertEquals(6, cat.type("COBBLESTONE").tier(12).tba);
        assertEquals(1013, cat.type("INFERNO").tier(1).tba);
        assertEquals(22.5, cat.type("QUARTZ").tier(1).tba);
        assertEquals(960, cat.type("WHEAT").tier(12).storage);
        assertEquals(15, cat.type("WHEAT").tier(12).slots());
        // section 4.3: slot table
        assertEquals(5, cat.placeableFor(0));
        assertEquals(6, cat.placeableFor(5));
        assertEquals(9, cat.placeableFor(50));
        assertEquals(26, cat.placeableFor(650));
        assertEquals(26, cat.placeableFor(10_000));
        assertEquals(15, cat.nextSlotAt(5));
        // section 4.3: SkyBlock XP per new unique tier
        assertEquals(1, cat.sbxpForTier(6));
        assertEquals(6, cat.sbxpForTier(10));
        assertEquals(24, cat.sbxpForTier(12));
        // section 4.4 fuels
        assertEquals(25, cat.fuel("ENCHANTED_LAVA_BUCKET").speed);
        assertTrue(cat.fuel("ENCHANTED_LAVA_BUCKET").infinite());
        assertEquals(3, cat.fuel("CATALYST").mult);
        assertEquals(3 * 3600, cat.fuel("CATALYST").seconds);
        assertEquals(90, cat.fuel("FOUL_FLESH").speed);
        // section 4.5/4.6
        assertEquals(0.5, cat.upgrade("BUDGET_HOPPER").sell);
        assertEquals(27, cat.upgrade("XXLARGE_ENCHANTED_CHEST").slots);
        assertEquals(20, cat.upgrade("FLYCATCHER_UPGRADE").speed);
    }

    @Test
    void cobblestoneOneHourFillsTierOneStorage() {
        Placed m = minion("COBBLESTONE", 1);
        MinionSim.Result r = MinionSim.advance(cat, m, T0 + HOUR, new Env(), false);
        assertEquals(128, r.harvests, "3600 / (2 x 14) = 128.57");
        assertEquals(64, m.count("COBBLESTONE"), "tier I holds 64");
        assertTrue(r.full);
        assertEquals(64, r.lost);
        // a full minion makes nothing more and keeps no progress
        MinionSim.Result again = MinionSim.advance(cat, m, T0 + 2 * HOUR, new Env(), false);
        assertEquals(0, again.harvests);
    }

    @Test
    void speedBoostsDivideTheTba() {
        Placed m = minion("COBBLESTONE", 11);   // big storage
        Env env = new Env();
        env.speed = 25;
        MinionSim.Result r = MinionSim.advance(cat, m, T0 + HOUR, env, false);
        double tba = cat.type("COBBLESTONE").tier(11).tba;
        long expected = (long) Math.floor(3600 / (2 * tba / 1.25));
        assertEquals(expected, r.harvests);
    }

    @Test
    void timedFuelOnlyPowersItsDuration() {
        Placed m = minion("COBBLESTONE", 11);
        m.fuel = "COAL";
        m.fuelCount = 1;
        m.fuelLeftMs = 30 * 60_000L;
        MinionSim.Result r = MinionSim.advance(cat, m, T0 + HOUR, new Env(), false);
        double tba = cat.type("COBBLESTONE").tier(11).tba;
        double expect = 1800 * 1.05 / (2 * tba) + 1800 / (2 * tba);
        assertEquals((long) Math.floor(expect), r.harvests);
        assertNull(m.fuel, "the coal burned out");
    }

    @Test
    void stackedFuelBurnsInRealTime() {
        Placed m = minion("COBBLESTONE", 11);
        m.fuel = "ENCHANTED_BREAD";   // 12 h each
        m.fuelCount = 3;
        m.fuelLeftMs = 12 * HOUR;
        MinionSim.advance(cat, m, T0 + 13 * HOUR, new Env(), false);
        assertEquals("ENCHANTED_BREAD", m.fuel);
        assertEquals(2, m.fuelCount);
        assertEquals(11 * HOUR, m.fuelLeftMs, 1);
    }

    @Test
    void multiplierFuelMultipliesDrops() {
        Placed a = minion("COBBLESTONE", 11), b = minion("COBBLESTONE", 11);
        b.fuel = "CATALYST";
        b.fuelCount = 1;
        b.fuelLeftMs = 3 * HOUR;
        MinionSim.Result ra = MinionSim.advance(cat, a, T0 + HOUR, new Env(), false);
        MinionSim.Result rb = MinionSim.advance(cat, b, T0 + HOUR, new Env(), false);
        assertEquals(ra.harvests, rb.harvests, "a multiplier does not change speed");
        assertEquals(ra.total() * 3, rb.total());
    }

    @Test
    void fractionalDropsAverageExactly() {
        Placed m = minion("RABBIT", 12);
        double tba = cat.type("RABBIT").tier(12).tba;
        long secs = (long) Math.ceil(100 * 2 * tba);       // >= 100 harvests
        MinionSim.Result r = MinionSim.advance(cat, m, T0 + secs * 1000, new Env(), false);
        long h = r.harvests;
        assertEquals(h, m.count("RABBIT"));
        assertEquals((long) Math.floor(h * 0.35 + 1e-9), m.count("RABBIT_FOOT"));
    }

    @Test
    void ultraCompactorPacksCobblestone() {
        Placed m = minion("COBBLESTONE", 11);
        m.upgrade1 = "SUPER_COMPACTOR_3000";
        MinionSim.Result r = new MinionSim.Result();
        MinionSim.deliver(cat, m, Map.of("COBBLESTONE", 1600L + 7), MinionSim.capacity(cat, m), new Env(), r);
        assertEquals(10, m.count("ENCHANTED_COBBLESTONE"));
        assertEquals(7, m.count("COBBLESTONE"));
    }

    @Test
    void compactorMakesBlocksAndUltraTakesTheBlockStep() {
        Placed m = minion("SNOW", 11);
        m.upgrade1 = "COMPACTOR";
        MinionSim.Result r = new MinionSim.Result();
        MinionSim.deliver(cat, m, Map.of("SNOW_BALL", 18L), MinionSim.capacity(cat, m), new Env(), r);
        assertEquals(4, m.count("SNOW_BLOCK"));
        assertEquals(2, m.count("SNOW_BALL"));

        Placed u = minion("SNOW", 11);
        u.upgrade1 = "SUPER_COMPACTOR_3000";
        MinionSim.deliver(cat, u, Map.of("SNOW_BALL", 640L), MinionSim.capacity(cat, u), new Env(), new MinionSim.Result());
        assertEquals(1, u.count("ENCHANTED_SNOW_BLOCK"), "snowball -> snow block -> enchanted snow block");
    }

    @Test
    void smelterThenCompactor() {
        Placed m = minion("IRON", 11);
        m.upgrade1 = "DWARVEN_COMPACTOR";
        Env env = new Env();
        long secs = (long) Math.ceil(170 * 2 * cat.type("IRON").tier(11).tba);
        MinionSim.advance(cat, m, T0 + secs * 1000, env, false);
        assertEquals(0, m.count("IRON_ORE"), "smelted");
        assertEquals(1, m.count("ENCHANTED_IRON"), "160 ingots -> 1 Enchanted Iron");
    }

    @Test
    void hopperSellsOverflow() {
        Placed m = minion("COBBLESTONE", 1);
        m.shipping = "BUDGET_HOPPER";
        Env env = new Env();
        env.prices.put("COBBLESTONE", 2.0);
        MinionSim.Result r = MinionSim.advance(cat, m, T0 + HOUR, env, false);
        assertEquals(64, m.count("COBBLESTONE"));
        assertEquals((128 - 64) * 2.0 * 0.5, r.coins, 1e-9);
        assertEquals(r.coins, m.heldCoins, 1e-9);
        assertEquals(0, r.lost);
    }

    @Test
    void storageChestAddsSlotsAndSmallStacksRespected() {
        Placed m = minion("ENDERMAN", 1);
        m.chest = "SMALL_ENCHANTED_CHEST";
        assertEquals(1 + 3, MinionSim.capacity(cat, m));
        int added = MinionSim.addToStorage(m, "ENDER_PEARL", 100, MinionSim.capacity(cat, m), new Env());
        assertEquals(64, added, "4 slots of 16 pearls");
        assertEquals(4, m.storage.size());
    }

    @Test
    void pausedMinionBurnsFuelButMakesNothing() {
        Placed m = minion("COBBLESTONE", 5);
        m.fuel = "COAL";
        m.fuelCount = 1;
        m.fuelLeftMs = 30 * 60_000L;
        MinionSim.Result r = MinionSim.advance(cat, m, T0 + HOUR, new Env(), true);
        assertEquals(0, r.total());
        assertNull(m.fuel);
    }

    @Test
    void longOfflineCatchUpIsBoundedAndCheap() {
        Placed m = minion("SNOW", 12);
        m.upgrade1 = "SUPER_COMPACTOR_3000";
        m.upgrade2 = "DIAMOND_SPREADING";
        m.chest = "XXLARGE_ENCHANTED_CHEST";
        long start = System.nanoTime();
        MinionSim.Result r = MinionSim.advance(cat, m, T0 + 30L * 24 * HOUR, new Env(), false);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(r.harvests > 100_000, "a month of snow: " + r.harvests);
        assertTrue(m.storage.size() <= MinionSim.capacity(cat, m));
        assertTrue(m.count("ENCHANTED_SNOW_BLOCK") > 0);
        assertTrue(ms < 2000, "catch-up took " + ms + " ms");
    }

    @Test
    void infernoFuelReplacesOutputWithSpecialty() {
        Placed m = minion("INFERNO", 11);
        m.fuel = "INFERNO_HYPERGOLIC_BLAZE_ROD";
        m.fuelCount = 1;
        m.fuelLeftMs = 24 * HOUR;
        MinionSim.Result r = MinionSim.advance(cat, m, T0 + 24 * HOUR, new Env(), false);
        long h = r.harvests;
        assertTrue(h > 0);
        // 20x output, 4/5 of it Blaze Rods (avg 1), 1/5 Crude Gourmet Meat
        assertEquals((long) Math.floor(h * 20 * 0.8 + 1e-9), r.produced.getOrDefault("BLAZE_ROD", 0L), 1);
        assertEquals((long) Math.floor(h * 20 * 0.2 + 1e-9), r.produced.getOrDefault("CRUDE_GABAGOOL", 0L), 1);
    }

    @Test
    void upgradesRestrictedToTheirMinions() {
        assertTrue(cat.upgrade("FLINT_SHOVEL").appliesTo(cat.type("GRAVEL")));
        assertFalse(cat.upgrade("FLINT_SHOVEL").appliesTo(cat.type("SAND")));
        assertTrue(cat.upgrade("CORRUPT_SOIL").appliesTo(cat.type("ZOMBIE")));
        assertFalse(cat.upgrade("CORRUPT_SOIL").appliesTo(cat.type("WHEAT")));
        Placed g = minion("GRAVEL", 5);
        g.upgrade1 = "FLINT_SHOVEL";
        List<MinionCatalog.Drop> d = MinionSim.effectiveDrops(cat, g, cat.type("GRAVEL"));
        assertEquals(1, d.size());
        assertEquals("FLINT", d.get(0).item);
    }

    @Test
    void timedOutputAccruesWithTimeNotHarvests() {
        // Lesser Soulflow Engine: one Raw Soulflow per 30 min. Driven by 5 s live ticks (most of which complete
        // no harvest) an hour must still make exactly 2 - the old code only counted ticks that harvested.
        Env env = new Env();
        Placed m = minion("COBBLESTONE", 1);
        m.upgrade1 = "LESSER_SOULFLOW_ENGINE";
        m.shipping = "BUDGET_HOPPER";
        long made = 0;
        for (int i = 1; i <= 720; i++) made += MinionSim.advance(cat, m, T0 + i * 5_000L, env, false).produced.getOrDefault("RAW_SOULFLOW", 0L);
        assertEquals(2, made);
    }

    /** Raw-item worth of an item through the compaction chains (Enchanted Cobblestone = 160). */
    static long worth(String id) {
        for (MinionCatalog.Rule r : cat.enchantedRules.values()) if (r.to.equals(id)) return r.amount * worth(r.from) / Math.max(1, r.count);
        for (MinionCatalog.Rule r : cat.blockRules.values()) if (r.to.equals(id)) return r.amount * worth(r.from) / Math.max(1, r.count);
        return 1;
    }

    static long worth(Placed m) {
        long v = 0;
        for (MinionStore.Slot s : m.storage) v += s.amount * worth(s.id);
        return v;
    }

    @Test
    void compactionNeverLosesItems() {
        // Whatever storage looks like, compacting may only ever trade items for their exact equivalent:
        // what is stored after + what overflowed == what was stored before + what was delivered.
        java.util.Random rnd = new java.util.Random(7);
        String[] fill = {"SNOW_BALL", "SNOW_BLOCK", "ENCHANTED_SNOW_BLOCK", "COBBLESTONE", "ENCHANTED_COBBLESTONE"};
        for (int run = 0; run < 2000; run++) {
            Env env = new Env();
            Placed m = minion("SNOW", 5);
            m.upgrade1 = rnd.nextBoolean() ? "SUPER_COMPACTOR_3000" : "COMPACTOR";
            if (rnd.nextBoolean()) m.upgrade2 = "COMPACTOR";
            int cap = 1 + rnd.nextInt(4);
            int pre = rnd.nextInt(cap + 1);
            for (int i = 0; i < pre; i++) m.storage.add(new MinionStore.Slot(fill[rnd.nextInt(fill.length)], 1 + rnd.nextInt(64)));
            long before = worth(m);
            long delivered = rnd.nextInt(700);
            MinionSim.Result r = new MinionSim.Result();
            MinionSim.deliver(cat, m, Map.of("SNOW_BALL", delivered), cap, env, r);
            assertEquals(before + delivered, worth(m) + r.lost, "run " + run + " lost items while compacting: " + m.upgrade1 + "/" + m.upgrade2);
            assertTrue(m.storage.size() <= cap, "run " + run + " overfilled storage");
        }
    }

    @Test
    void recipesAreWellFormed() throws IOException {
        List<Map<String, Object>> recipes;
        try (Reader r = Files.newBufferedReader(dir.resolve("recipes.json"), StandardCharsets.UTF_8)) {
            recipes = new Gson().fromJson(r, new TypeToken<List<Map<String, Object>>>() {}.getType());
        }
        Set<String> results = new HashSet<>();
        for (Map<String, Object> r : recipes) {
            @SuppressWarnings("unchecked") List<String> grid = (List<String>) r.get("grid");
            assertEquals(9, grid.size(), "grid of " + r.get("result"));
            for (String c : grid) assertFalse(c != null && c.endsWith(":0"), "zero-amount cell: " + c);
            results.add((String) r.get("result"));
        }
        int craftable = 0;
        for (TypeDef t : cat.types()) for (TierDef td : t.tiers) if (td.tier <= 11) {
            assertTrue(results.contains(t.itemId(td.tier)), "recipe for " + t.itemId(td.tier));
            craftable++;
        }
        assertTrue(craftable > 650);
    }

    @Test
    void compactedItemsResolveToTheirRawCollection() {
        Set<String> raw = Set.of("COBBLESTONE", "POTATO_ITEM", "SNOW_BALL", "IRON_INGOT");
        MinionCatalog.RawEquivalent e = cat.rawEquivalent("ENCHANTED_COBBLESTONE", raw::contains);
        assertNotNull(e);
        assertEquals("COBBLESTONE", e.item());
        assertEquals(160, e.amount());
        MinionCatalog.RawEquivalent snow = cat.rawEquivalent("ENCHANTED_SNOW_BLOCK", raw::contains);
        assertNotNull(snow);
        assertEquals("SNOW_BALL", snow.item());
        assertEquals(640, snow.amount());
        MinionCatalog.RawEquivalent baked = cat.rawEquivalent("ENCHANTED_BAKED_POTATO", raw::contains);
        assertNotNull(baked);
        assertEquals("POTATO_ITEM", baked.item());
        assertEquals(25600, baked.amount());
        // a raw item, and something no rule produces, are not compaction products
        assertNull(cat.rawEquivalent("COBBLESTONE", raw::contains));
        assertNull(cat.rawEquivalent("DIAMOND_SWORD", raw::contains));
        // a chain that never reaches an accepted item credits nothing
        assertNull(cat.rawEquivalent("ENCHANTED_COBBLESTONE", id -> false));
    }

    // ---- wave 5 -------------------------------------------------------------------------------------

    @Test
    void seatedMinionDoesNotWorkOrBurnFuel() {
        Placed m = minion("COBBLESTONE", 5);
        m.seated = true;
        m.fuel = "COAL";
        m.fuelCount = 2;
        m.fuelLeftMs = 1_800_000L;
        MinionSim.Result r = MinionSim.advance(cat, m, T0 + 10 * HOUR, new Env(), false);
        assertEquals(0, r.harvests);
        assertEquals(0, m.stored());
        assertEquals(1_800_000L, m.fuelLeftMs, "a seated minion burns no fuel");
        assertEquals(2, m.fuelCount);
        assertEquals(T0 + 10 * HOUR, m.lastUpdate, "its clock still moves: standing up does not catch up the break");
        // standing again, it works from there
        m.seated = false;
        MinionSim.Result r2 = MinionSim.advance(cat, m, T0 + 11 * HOUR, new Env(), false);
        assertTrue(r2.harvests > 0);
    }

    @Test
    void seatedMinionsDoNotCountTowardTheCap() {
        MinionStore s = new MinionStore();
        for (int i = 0; i < 4; i++) {
            Placed m = minion("WHEAT", 1);
            m.id = "m" + i;
            m.seated = i < 2;
            s.placed.put(m.id, m);
        }
        assertEquals(2, s.working());
    }

    @Test
    void superJacobSystemPrintsATicketEveryThreeHours() {
        Placed m = minion("WHEAT", 1);
        m.upgrade1 = "SUPER_JACOB_SYSTEM";
        m.shipping = "BUDGET_HOPPER";
        long tickets = 0;
        // 5 s live ticks for 9 hours: timed outputs accrue with time, not harvests
        for (long t = T0 + 5000; t <= T0 + 9 * HOUR; t += 5000) {
            tickets += MinionSim.advance(cat, m, t, new Env(), false).produced.getOrDefault("JACOBS_TICKET", 0L);
        }
        assertEquals(3, tickets);
        assertFalse(cat.upgrade("SUPER_JACOB_SYSTEM").appliesTo(cat.type("COBBLESTONE")), "farming minions only");
        assertTrue(cat.upgrade("SUPER_JACOB_SYSTEM").onePerProfile);
    }

    @Test
    void participationMedalMakesTicketsOnlyWhileBurningInAFarmingMinion() {
        Placed m = minion("CARROT", 3);
        m.fuel = "JACOBS_PARTICIPATION_MEDAL";
        m.fuelCount = 1;
        m.fuelLeftMs = 72 * HOUR;
        m.shipping = "ENCHANTED_HOPPER";
        MinionSim.Result r = MinionSim.advance(cat, m, T0 + 100 * HOUR, new Env(), false);
        assertEquals(18, (long) r.produced.getOrDefault("JACOBS_TICKET", 0L), "72 h of burn at one per 4 h");
        assertNull(m.fuel, "the medal burned out after 3 days");
        Placed cob = minion("COBBLESTONE", 3);
        cob.fuel = "JACOBS_PARTICIPATION_MEDAL";
        cob.fuelCount = 1;
        cob.fuelLeftMs = 72 * HOUR;
        cob.shipping = "ENCHANTED_HOPPER";
        assertNull(MinionSim.activeFuel(cat, cob, cat.type("COBBLESTONE")), "the medal does not power a mining minion");
        MinionSim.Result r2 = MinionSim.advance(cat, cob, T0 + 10 * HOUR, new Env(), false);
        assertFalse(r2.produced.containsKey("JACOBS_TICKET"));
    }

    @Test
    void freeWillOddsFollowTheTier() {
        assertEquals(50, MinionSim.freeWillChance(1, 50, 4), 1e-9);
        assertEquals(70, MinionSim.freeWillChance(6, 50, 4), 1e-9);
        assertEquals(94, MinionSim.freeWillChance(12, 50, 4), 1e-9);
        assertEquals(100, MinionSim.freeWillChance(12, 80, 4), 1e-9);
        assertEquals(0, MinionSim.freeWillChance(1, -5, 0), 1e-9);
        MinionCatalog.UpgradeDef fw = cat.upgrade("FREE_WILL");
        assertNotNull(fw);
        assertEquals("freewill", fw.slot);
        assertEquals(10, fw.speed, 1e-9);
    }

    @Test
    void petPerksMatchThePetsModuleKeys() {
        assertEquals(List.of("pigman_bacon_farmer", "rabbit_efficient_farming"), MinionSim.petAbilityKeys(cat.type("PIG")));
        assertEquals(List.of("rabbit_efficient_farming"), MinionSim.petAbilityKeys(cat.type("WHEAT")));
        assertEquals(List.of("ocelot_tree_hugger"), MinionSim.petAbilityKeys(cat.type("FLOWER")));
        assertEquals(List.of("magma_cube_slimy_minions"), MinionSim.petAbilityKeys(cat.type("SLIME")));
        assertEquals(List.of("spider_spider_whisperer"), MinionSim.petAbilityKeys(cat.type("TARANTULA")));
        assertEquals(List.of("snail_red_sand_enjoyer"), MinionSim.petAbilityKeys(cat.type("RED_SAND")));
        assertTrue(MinionSim.petAbilityKeys(cat.type("DIAMOND")).isEmpty());
        assertEquals("GHAST", cat.type("GHAST").visual, "the Ghast minion shows a (scaled) ghast");
    }

    @Test
    void winterCrystalPresentOncePerSkyBlockYear() {
        assertTrue(MinionManager.presentDue(true, 0, false, 1), "a fresh island's first year gets one");
        assertTrue(MinionManager.presentDue(true, 6, false, 7));
        assertFalse(MinionManager.presentDue(true, 7, false, 7), "never twice in one year");
        assertFalse(MinionManager.presentDue(true, 6, true, 9), "never a second while one waits unclaimed");
        assertFalse(MinionManager.presentDue(false, 6, false, 7), "no crystal, no present");
        MinionCatalog.UpgradeDef wc = cat.upgrade(MinionManager.WINTER_CRYSTAL);
        assertNotNull(wc);
        assertEquals("crystal", wc.slot);
        assertEquals(5, wc.speed, 1e-9);
    }
}
