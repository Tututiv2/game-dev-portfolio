package com.skyclone.mod.fishing;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure checks of the fishing formulas and data files against research 07 §3. */
class FishingPureTest {

    private static Reader res(String file) {
        InputStream in = FishingPureTest.class.getResourceAsStream("/data/fishing/" + file);
        assertNotNull(in, "missing resource " + file);
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    private static FishingCatalog cat() { return FishingCatalog.read(res("fishing.json")); }

    @Test
    void waitFormulaMatchesResearch() {
        // 150 FS at a cap-300 spot: a 5-10 s wait becomes 2.5-5 s
        assertEquals(100, FishMath.waitTicks(200, 150, 300, 0));
        assertEquals(200, FishMath.waitTicks(400, 150, 300, 0));
        assertEquals(1, FishMath.waitTicks(400, 300, 300, 1));
        assertEquals(1, FishMath.waitTicks(400, 999, 300, 1));
        assertEquals(400, FishMath.maxBaseTicks(0));
        assertEquals(280, FishMath.maxBaseTicks(30));
        assertEquals(200, FishMath.maxBaseTicks(90));
        int lo = FishMath.baseTicks(() -> 0.0, 0), hi = FishMath.baseTicks(() -> 0.999999, 0);
        assertEquals(200, lo);
        assertEquals(400, hi);
        FishingCatalog c = cat();
        assertEquals(200, c.fsCap("backwater_bayou", 300));
        assertEquals(350, c.fsCap("crimson_isle", 300));
        assertEquals(300, c.fsCap("hub", 300));
        assertEquals(250, c.fsCap("lotus_atoll", 300));   // research 01
        assertEquals(2, (int) c.hotspots.get("lotus_atoll"));
        assertEquals(2, (int) c.hotspots.get("torrhus_canyon"));
    }

    @Test
    void seaCreatureChance() {
        assertEquals(0.20, FishMath.seaCreatureProbability(20, 1), 1e-9);
        assertEquals(0.05, FishMath.seaCreatureProbability(20, 4), 1e-9);
        assertEquals(1.0, FishMath.seaCreatureProbability(234, 1), 1e-9);
        assertEquals(0.80, FishMath.replaceChance(0.15, 0.20 + 0.10 + 0.5, 0.80), 1e-9);
    }

    @Test
    void waterHydraIsZeroPointFourTwoPercentOfTheClassicTen() {
        FishingCatalog c = cat();
        FishingCatalog.Pool water = c.pool("water");
        Map<String, Double> w = new HashMap<>();
        for (FishingCatalog.PoolCreature pc : water.creatures) w.put(pc.mob, pc.weight);
        String[] classic = {"sc_squid", "sc_sea_walker", "sc_sea_witch", "sc_sea_archer", "sc_rider_of_the_deep", "sc_catfish",
                "sc_sea_leech", "sc_guardian_defender", "sc_deep_sea_protector", "sc_water_hydra"};
        double sum = 0;
        for (String id : classic) sum += w.get(id);
        assertEquals(4296, sum, 1e-9);
        assertEquals(0.0042, w.get("sc_water_hydra") / sum, 0.0001);
        // the standard post-2024 ladder in the Bayou: 50 / 28.75 / 15 / 5 / 1 / 0.25 %
        FishingCatalog.Pool bayou = c.pool("bayou");
        double t = 0;
        for (FishingCatalog.PoolCreature pc : bayou.creatures) t += pc.weight;
        assertEquals(0.0025, bayou.creatures.get(5).weight / t, 1e-9);
    }

    @Test
    void poolsResolveByPlace() {
        PoolResolver r = new PoolResolver(cat());
        PoolResolver.Spot s = new PoolResolver.Spot();
        s.island = "hub"; s.fishingLevel = 50;
        assertEquals("water", r.basePool(s).id);
        s.island = "backwater_bayou";
        assertEquals("bayou", r.basePool(s).id);
        // wave 5: the Bayou's marsh/lagoon zones fish the Bayou pool; the Galatea pools are on their own islands
        s.zoneText = " moonlit_marsh moonlit marsh lotus_lagoon";
        assertEquals("bayou", r.basePool(s).id);
        s.island = "galatea"; s.zoneText = " dimwater_loch dimwater loch";
        assertEquals("marsh", r.basePool(s).id);
        s.island = "lotus_atoll"; s.zoneText = " lotus_atoll_lagoon";
        assertEquals("lagoon", r.basePool(s).id);
        s.island = "torrhus_canyon"; s.zoneText = " torrhus_canyon_springs";
        assertEquals("torrhus", r.basePool(s).id);
        assertEquals(Set.of("sc_haggard", "sc_brineling", "sc_sprawl", "sc_torrid", "sc_silkbreeze", "sc_giant_isopod"),
                ids(r.options(s)));
        s.fishingLevel = 14;
        assertTrue(r.options(s).isEmpty(), "Torrent Springs creatures need Fishing 15");
        s.fishingLevel = 50; s.island = "backwater_bayou";
        s.liquid = "lava";
        assertEquals("marsh_lava", r.basePool(s).id);
        s.island = "galatea";
        assertEquals("marsh_lava", r.basePool(s).id);
        s.island = "crimson_isle"; s.zoneText = "";
        assertEquals("crimson_lava", r.basePool(s).id);
        s.island = "hub"; s.liquid = "lava";
        assertNull(r.basePool(s));
        // level gating: a fresh fisher only meets the first two water creatures
        s = new PoolResolver.Spot();
        s.island = "hub"; s.fishingLevel = 1;
        List<PoolResolver.Option> opts = r.options(s);
        assertEquals(Set.of("sc_squid", "sc_sea_walker"), ids(opts));
        // night squid needs night AND dark bait in open water; the park only needs night
        s.fishingLevel = 10; s.night = true;
        assertFalse(ids(r.options(s)).contains("sc_night_squid"));
        s.bait = "DARK_BAIT";
        assertTrue(ids(r.options(s)).contains("sc_night_squid"));
        s.bait = null; s.island = "park";
        assertTrue(ids(r.options(s)).contains("sc_night_squid"));
        // worm bait in the Hollows: worms only
        s = new PoolResolver.Spot();
        s.island = "crystal_hollows"; s.fishingLevel = 50; s.zoneText = "goblin holdout"; s.wormsOnly = true;
        for (PoolResolver.Option o : r.options(s)) assertTrue(o.mob().contains("worm"), o.mob());
    }

    @Test
    void replacementPoolsNeedTheirTrigger() {
        PoolResolver r = new PoolResolver(cat());
        PoolResolver.Spot s = new PoolResolver.Spot();
        s.island = "hub"; s.fishingLevel = 30;
        assertTrue(r.replacements(s).isEmpty());
        s.festival = true;
        assertEquals("shark", r.replacements(s).get(0).pool().id);
        s.festival = false; s.hotspot = true;
        assertEquals("water_hotspot", r.replacements(s).get(0).pool().id);
        s.liquid = "lava"; s.island = "crimson_isle";
        assertEquals("lava_hotspot", r.replacements(s).get(0).pool().id);
        s.liquid = "water"; s.island = "hub"; s.hotspot = false; s.bait = "CARROT_BAIT";
        assertEquals("carrot", r.replacements(s).get(0).pool().id);
    }

    @Test
    void weightModifiers() {
        PoolResolver.Option rare = new PoolResolver.Option("sc_water_hydra", 18, 19, "water");
        PoolResolver.Option common = new PoolResolver.Option("sc_squid", 1200, 1, "water");
        PoolResolver.Mods m = new PoolResolver.Mods();
        m.rareWeight = 1.25;
        m.commonWeight = 1.25;
        m.rarity = id -> id.equals("sc_squid") ? "COMMON" : "LEGENDARY";
        assertEquals(22.5, PoolResolver.weight(rare, m), 1e-9);
        assertEquals(1500, PoolResolver.weight(common, m), 1e-9);
        m.mob.put("sc_water_hydra", 5.0);
        assertEquals(112.5, PoolResolver.weight(rare, m), 1e-9);
    }

    @Test
    void trophyTiersAndPity() {
        FishingCatalog.Tiers t = cat().trophy.tiers;
        assertEquals("GOLD", FishMath.trophyTier(() -> 0.99, 99, 10, 0, t.chances, t.pity));
        assertEquals("DIAMOND", FishMath.trophyTier(() -> 0.99, 10, 599, 0, t.chances, t.pity));
        assertEquals("BRONZE", FishMath.trophyTier(() -> 0.99, 0, 0, 0, t.chances, t.pity));
        assertEquals("DIAMOND", FishMath.trophyTier(() -> 0.001, 0, 0, 0, t.chances, t.pity));
        assertEquals("GOLD", FishMath.trophyTier(() -> 0.01, 0, 0, 0, t.chances, t.pity));
        assertEquals("SILVER", FishMath.trophyTier(() -> 0.2, 0, 0, 0, t.chances, t.pity));
        assertEquals(0.30, FishMath.trophyChance(25, 20), 1e-9);
        assertEquals(0.012, FishMath.manaRayChance(1200, 0), 1e-9);
        assertEquals(0, FishMath.manaRayChance(1199, 50), 1e-9);
        assertEquals(0, FishMath.goldenFishChance(7.9, 8, 12), 1e-9);
        assertEquals(0.5, FishMath.goldenFishChance(10, 8, 12), 1e-9);
        assertEquals(1, FishMath.goldenFishChance(13, 8, 12), 1e-9);
    }

    @Test
    void trophyData() {
        FishingCatalog c = cat();
        assertEquals(18, c.trophy.fish.size());
        for (Map.Entry<String, FishingCatalog.TrophyFish> e : c.trophy.fish.entrySet())
            for (String tier : FishingCatalog.TIERS) assertTrue(FishMath.fillet(e.getValue().fillet, tier) > 0, e.getKey() + " " + tier);
        assertEquals(1300, FishMath.fillet(c.trophy.fish.get("GOLDEN_FISH").fillet, "DIAMOND"));
        assertEquals(4, c.trophy.titles.size());
    }

    @Test
    void treasureQualityShiftsWithBlessing() {
        Map<String, Double> w = cat().treasure.weights;
        int[] plain = new int[3], blessed = new int[3];
        for (int i = 0; i < 1000; i++) {
            double r = (i + 0.5) / 1000.0;
            plain[idx(FishMath.treasureQuality(w, 0, () -> r))]++;
            blessed[idx(FishMath.treasureQuality(w, 0.5, () -> r))]++;
        }
        assertEquals(850, plain[0], 1);
        assertTrue(blessed[0] < plain[0]);
        assertTrue(blessed[2] > plain[2]);
    }

    @Test
    void dataFilesHangTogether() {
        FishingCatalog c = cat();
        JsonArray mobs = new Gson().fromJson(res("mobs.json"), JsonArray.class);
        Set<String> mobIds = new HashSet<>();
        for (JsonElement e : mobs) {
            JsonObject o = e.getAsJsonObject();
            mobIds.add(o.get("id").getAsString());
            assertTrue(o.get("health").getAsDouble() > 0);
            boolean sea = false;
            for (JsonElement f : o.getAsJsonArray("flags")) sea |= f.getAsString().equals("sea_creature");
            assertTrue(sea, o.get("id").getAsString());
        }
        for (FishingCatalog.Pool p : c.pools) for (FishingCatalog.PoolCreature pc : p.creatures) assertTrue(mobIds.contains(pc.mob), pc.mob);
        JsonArray items = new Gson().fromJson(res("items.json"), JsonArray.class);
        Set<String> itemIds = new HashSet<>();
        for (JsonElement e : items) itemIds.add(e.getAsJsonObject().get("id").getAsString());
        for (String id : c.parts.keySet()) assertTrue(itemIds.contains(id), id);
        for (String id : c.baits.keySet()) assertTrue(itemIds.contains(id), id);
        for (String id : c.rods.keySet()) assertTrue(itemIds.contains(id), id);
        for (String stem : c.trophy.fish.keySet()) for (String t : FishingCatalog.TIERS) assertTrue(itemIds.contains(stem + "_" + t));
        Set<String> slots = Set.of("hook", "line", "sinker");
        for (FishingCatalog.Part p : c.parts.values()) assertTrue(slots.contains(p.slot));
        assertEquals(20, c.pools.size());
        // the plain Water Orb is the core of every themed orb recipe; without it those recipes never register
        for (String orb : List.of("WATER_ORB", "SHARK_WATER_ORB", "HOTSPOT_WATER_ORB", "BAYOU_WATER_ORB", "LAVA_WATER_ORB",
                "SPOOKY_WATER_ORB", "WINTER_WATER_ORB", "LOTUS_WATER_ORB")) assertTrue(itemIds.contains(orb), orb);
        // no drop may name the Puddle Jumper trophy frogs: the galatea module rolls those on the kill itself
        for (JsonElement e : mobs)
            for (JsonElement d : e.getAsJsonObject().getAsJsonArray("drops"))
                assertFalse(d.getAsJsonObject().get("item").getAsString().startsWith("PUDDLE_JUMPER_"), e.toString());
        // every recipe this module ships produces an item this module registers
        JsonArray recipes = new Gson().fromJson(res("recipes.json"), JsonArray.class);
        for (JsonElement e : recipes) assertTrue(itemIds.contains(e.getAsJsonObject().get("result").getAsString()), e.toString());
    }

    @Test
    void shopsAreSaneAndTreasureTablesFilled() {
        FishingCatalog c = cat();
        for (List<FishingCatalog.ShopEntry> shop : List.of(c.baitShop, c.partShop, c.keeperShop))
            for (FishingCatalog.ShopEntry e : shop) {
                assertTrue(e.coins > 0, e.item);
                assertTrue(e.title >= 0 && e.title <= 4, e.item);
            }
        for (String q : List.of("good", "great", "outstanding")) {
            assertFalse(c.treasure.water.getOrDefault(q, List.of()).isEmpty(), q);
            for (FishingCatalog.TreasureEntry t : c.treasure.water.get(q)) {
                assertTrue(t.min >= 1 && t.max >= t.min, t.item);
                assertTrue(t.weight > 0, t.item);
            }
        }
        // a lava bait must never be usable with a water rod: the Obfuscated chain baits are lava-only
        for (Map.Entry<String, FishingCatalog.Bait> e : c.baits.entrySet())
            if (e.getValue().trophyBait != null) assertTrue(e.getValue().lavaOnly, e.getKey());
    }

    // ---- wave 5 ---------------------------------------------------------------------------------

    @Test
    void puddleJumperRideIsAContinuousLoopBackToTheWater() {
        double[] a = FishMath.rideOffset(0, 7, 9), mid = FishMath.rideOffset(0.5, 7, 9), z = FishMath.rideOffset(1, 7, 9);
        assertEquals(0, a[0] * a[0] + a[1] * a[1] + a[2] * a[2], 1e-12);
        assertEquals(9, mid[1], 1e-9);
        assertEquals(0, z[1], 1e-9);
        assertTrue(Math.hypot(z[0], z[2]) > 5, "it lands in a new puddle, not where it took off");
        for (int i = 1; i <= 120; i++) {
            double[] p0 = FishMath.rideOffset((i - 1) / 120.0, 7, 9), p1 = FishMath.rideOffset(i / 120.0, 7, 9);
            assertTrue(Math.hypot(Math.hypot(p1[0] - p0[0], p1[1] - p0[1]), p1[2] - p0[2]) < 1.0, "tick " + i);
            assertTrue(p1[1] >= -1e-9, "never below the water");
        }
        // clamped outside 0..1
        assertEquals(FishMath.rideOffset(1, 7, 9)[0], FishMath.rideOffset(3, 7, 9)[0], 1e-12);
    }

    @Test
    void geyserSitesAreDeterministicSpacedAndCapped() {
        List<int[]> cand = new java.util.ArrayList<>();
        for (int x = -30; x <= 30; x += 2) for (int z = -30; z <= 30; z += 2) cand.add(new int[]{x, z});
        List<int[]> a = FishMath.pickSites(cand, 5, 10);
        List<int[]> rev = new java.util.ArrayList<>(cand);
        java.util.Collections.reverse(rev);
        List<int[]> b = FishMath.pickSites(rev, 5, 10);
        assertEquals(5, a.size());
        for (int i = 0; i < 5; i++) { assertEquals(a.get(i)[0], b.get(i)[0]); assertEquals(a.get(i)[1], b.get(i)[1]); }
        for (int i = 0; i < 5; i++) for (int j = i + 1; j < 5; j++)
            assertTrue(Math.hypot(a.get(i)[0] - a.get(j)[0], a.get(i)[1] - a.get(j)[1]) >= 10);
        assertTrue(FishMath.pickSites(List.of(new int[]{0, 0}, new int[]{1, 1}), 5, 10).size() == 1, "spacing beats count");
        assertTrue(FishMath.pickSites(List.of(), 5, 10).isEmpty());
    }

    @Test
    void thunderSlugAndTreasureHooks() {
        assertEquals(12_000, FishMath.thunderCharge(12_000, 1, false));
        assertEquals(12_120, FishMath.thunderCharge(12_000, 1, true));      // Pulse Ring: +1%
        assertEquals(0, FishMath.thunderCharge(500, 0, true));
        assertEquals(20_000, FishMath.slugPatienceMs(20_000, 0));
        assertEquals(10_000, FishMath.slugPatienceMs(20_000, 50));
        assertEquals(2_000, FishMath.slugPatienceMs(20_000, 100));            // capped at 90%
        Map<String, Double> w = cat().treasure.weights;
        double g = FishMath.treasureShare(w, 0, 0, 0, "great"), gb = FishMath.treasureShare(w, 0, 0.5, 0, "great");
        double o = FishMath.treasureShare(w, 0, 0, 0, "outstanding"), ob = FishMath.treasureShare(w, 0, 0, 1.0, "outstanding");
        assertTrue(gb > g && ob > o);
        assertEquals(1.0, FishMath.treasureShare(w, 0.5, 0.25, 0.75, "good") + FishMath.treasureShare(w, 0.5, 0.25, 0.75, "great")
                + FishMath.treasureShare(w, 0.5, 0.25, 0.75, "outstanding"), 1e-12);
        // the roll agrees with the share: r just under the outstanding share is outstanding, just over is not
        double so = FishMath.treasureShare(w, 0, 0, 1.0, "outstanding");
        assertEquals("outstanding", FishMath.treasureQuality(w, 0, 0, 1.0, () -> so - 1e-9));
        assertFalse("outstanding".equals(FishMath.treasureQuality(w, 0, 0, 1.0, () -> so + 1e-9)));
        // the old two-argument roll is unchanged
        assertEquals(FishMath.treasureQuality(w, 0.5, () -> 0.3), FishMath.treasureQuality(w, 0.5, 0, 0, () -> 0.3));
    }

    @Test
    void wave5DataTorrhusPuddleJumperBottles() {
        JsonArray mobs = new Gson().fromJson(res("mobs.json"), JsonArray.class);
        Map<String, JsonObject> byId = new HashMap<>();
        for (JsonElement e : mobs) byId.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
        String[] torr = {"sc_haggard", "sc_brineling", "sc_sprawl", "sc_torrid", "sc_silkbreeze", "sc_giant_isopod"};
        int[] lv = {44, 58, 71, 85, 105, 186};
        for (int i = 0; i < torr.length; i++) {
            JsonObject o = byId.get(torr[i]);
            assertNotNull(o, torr[i]);
            assertEquals(lv[i], o.get("level").getAsInt(), torr[i]);
        }
        // reference names never reach players: Brineling / Silkbreeze carry the hunting module's renames
        assertEquals("Saltling", byId.get("sc_brineling").get("name").getAsString());
        assertEquals("Silkdrift", byId.get("sc_silkbreeze").get("name").getAsString());
        JsonObject pj = byId.get("sc_puddle_jumper");
        assertTrue(pj.getAsJsonObject("extra").get("elusive").getAsBoolean());
        assertEquals(0, pj.get("damage").getAsInt());
        boolean beh = false;
        for (JsonElement b : pj.getAsJsonArray("behaviours")) beh |= b.getAsString().equals("fishing_puddle_jumper");
        assertTrue(beh);
        JsonArray items = new Gson().fromJson(res("items.json"), JsonArray.class);
        Map<String, JsonObject> it = new HashMap<>();
        for (JsonElement e : items) it.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
        assertEquals(1, it.get("THUNDER_IN_A_BOTTLE_EMPTY").get("maxStack").getAsInt(), "the charge lives on the item");
        for (String id : List.of("THUNDER_IN_A_BOTTLE", "ISOPOD_HUSK", "WATER_HYACINTH", "DISTANT_ECHO")) assertNotNull(it.get(id), id);
        // every island the Galatea pools name gets its treasure table and hotspots where research gives a count
        FishingCatalog c = cat();
        for (String isl : List.of("lotus_atoll", "torrhus_canyon", "galatea")) assertNotNull(c.treasure.islands.get(isl), isl);
    }

    @Test
    void chumBucketsFollowTheReferenceLore() {
        // "Sea Creatures slain nearby will drop +1 Chum. Up to +4 total from buckets!"
        assertEquals(0, FishMath.chumDrop(0, 1, 4, 0, true));
        assertEquals(1, FishMath.chumDrop(1, 1, 4, 0, true));
        assertEquals(4, FishMath.chumDrop(9, 1, 4, 0, true));
        // Glowy Chum Bait "+2 Chums dropped": rides on a bucket ("obtained ... with a nearby Chum Bucket")
        assertEquals(0, FishMath.chumDrop(0, 1, 4, 2, true));
        assertEquals(3, FishMath.chumDrop(1, 1, 4, 2, true));
        assertEquals(2, FishMath.chumDrop(0, 1, 4, 2, false));
        // Chumcap: "+4 Fishing Speed. Up to +16 from buckets!"
        assertEquals(0, FishMath.chumcapSpeed(0, 4, 16), 1e-9);
        assertEquals(12, FishMath.chumcapSpeed(3, 4, 16), 1e-9);
        assertEquals(16, FishMath.chumcapSpeed(7, 4, 16), 1e-9);
        // Chum: "+20 Coins and +10 Fishing XP"; Luminous Bracelet: the same again in someone ELSE's bucket
        assertArrayEquals(new double[]{20, 10}, FishMath.chumDeposit(1, 20, 10, true, false, 20, 10), 1e-9);
        assertArrayEquals(new double[]{40, 20}, FishMath.chumDeposit(1, 20, 10, true, true, 20, 10), 1e-9);
        assertArrayEquals(new double[]{100, 50}, FishMath.chumDeposit(5, 20, 10, false, true, 20, 10), 1e-9);
        // a bucket lost to a restart/crash comes back exactly once; one standing without a record pays nothing
        assertEquals(List.of("EMPTY_CHUM_BUCKET"), FishMath.missingBuckets(List.of("EMPTY_CHUM_BUCKET"), List.of()));
        assertEquals(List.of(), FishMath.missingBuckets(List.of("EMPTY_CHUM_BUCKET"), List.of("EMPTY_CHUM_BUCKET")));
        assertEquals(List.of(), FishMath.missingBuckets(List.of(), List.of("EMPTY_CHUMCAP_BUCKET")));
        assertEquals(List.of("A", "B"), FishMath.missingBuckets(List.of("A", "A", "B"), List.of("A")));
        // the Mooshroom Diver answers a PLACED Chumcap of your own, not one in your pocket
        FishingCatalog c = cat();
        PoolResolver r = new PoolResolver(c);
        PoolResolver.Spot s = new PoolResolver.Spot();
        s.island = "hub";
        s.fishingLevel = 60;
        s.carried = new HashSet<>(Set.of("EMPTY_CHUMCAP_BUCKET"));
        assertTrue(r.replacements(s).stream().noneMatch(x -> x.pool().id.equals("chumcap")));
        s.chumcap = true;
        assertTrue(r.replacements(s).stream().anyMatch(x -> x.pool().id.equals("chumcap")
                && x.options().stream().anyMatch(o -> o.mob().equals("sc_agarimoo"))));
        // the item is a placeable head with the reference's Fishing 15 requirement
        JsonArray items = new Gson().fromJson(res("items.json"), JsonArray.class);
        JsonObject cap = null;
        for (JsonElement e : items) if (e.getAsJsonObject().get("id").getAsString().equals("EMPTY_CHUMCAP_BUCKET")) cap = e.getAsJsonObject();
        assertNotNull(cap);
        assertEquals("PLAYER_HEAD", cap.get("material").getAsString());
        assertTrue(cap.has("skull"));
        assertEquals("skill:fishing:15", cap.getAsJsonArray("requirements").get(0).getAsString());
    }

    @Test
    void spinosaurusRaisesOnlyGoldAndDiamond() {
        Map<String, Double> ch = Map.of("DIAMOND", 0.002, "GOLD", 0.02, "SILVER", 0.25);
        Map<String, Integer> pity = Map.of("DIAMOND", 600, "GOLD", 100);
        // r = 0.025: past gold (0.022) normally, inside it at +50% (0.033)
        assertEquals("SILVER", FishMath.trophyTier(() -> 0.025, 0, 0, 0, 0, ch, pity));
        assertEquals("GOLD", FishMath.trophyTier(() -> 0.025, 0, 0, 0, 0.5, ch, pity));
        // silver's own share is untouched: the band is shifted, not widened (0.033 + 0.25)
        assertEquals("SILVER", FishMath.trophyTier(() -> 0.28, 0, 0, 0, 0.5, ch, pity));
        assertEquals("BRONZE", FishMath.trophyTier(() -> 0.2831, 0, 0, 0, 0.5, ch, pity));
        // the old signature is the zero-bonus case
        assertEquals(FishMath.trophyTier(() -> 0.021, 5, 5, 0.2, ch, pity), FishMath.trophyTier(() -> 0.021, 5, 5, 0.2, 0, ch, pity));
    }

    private static Set<String> ids(List<PoolResolver.Option> opts) {
        Set<String> s = new HashSet<>();
        for (PoolResolver.Option o : opts) s.add(o.mob());
        return s;
    }

    private static int idx(String q) { return q.equals("good") ? 0 : q.equals("great") ? 1 : 2; }
}
