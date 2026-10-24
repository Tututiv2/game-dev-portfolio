package com.skyclone.mod.kuudra;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wave 5: attribute shards in the paid chest, the Wheel of Embers, the intro questline, the cutscene timelines. */
class KuudraWave5Test {

    static final Predicate<String> ALL = id -> true;

    /** id -> rarity from the hunting module's generated shard catalog (the source of truth for shard ids). */
    static Map<String, String> huntingShards() throws Exception {
        Path dir = KuudraDataTest.dataDir().getParent().resolve("hunting");
        Map<String, String> out = new HashMap<>();
        if (!Files.exists(dir.resolve("shards.json"))) return out;
        try (Reader r = new InputStreamReader(new FileInputStream(dir.resolve("shards.json").toFile()), StandardCharsets.UTF_8)) {
            JsonArray a = new Gson().fromJson(r, JsonArray.class);
            for (JsonElement e : a) {
                JsonObject o = e.getAsJsonObject();
                out.put(o.get("id").getAsString(), o.get("rarity").getAsString());
            }
        }
        return out;
    }

    @Test
    void shardPoolHangsTogether() throws Exception {
        KCatalog c = KuudraDataTest.load();
        assertNull(KTests.shardPool(c));
        Map<String, String> hunting = huntingShards();
        assertFalse(hunting.isEmpty(), "data/hunting/shards.json should ship beside us");
        for (KCatalog.Loot l : c.loot.values())
            for (KCatalog.Shard s : l.shards) {
                assertTrue(hunting.containsKey(s.id), s.id + " is not a hunting shard");
                assertEquals(hunting.get(s.id), s.rarity, s.id + " rarity disagrees with hunting");
            }
    }

    @Test
    void shardsFollowTheirWeightsAndClimbWithTheTier() throws Exception {
        KCatalog c = KuudraDataTest.load();
        double lastAvg = -1;
        for (KCatalog.Tier t : c.tiers) {
            KCatalog.Loot l = c.loot.get(t.id);
            Random rng = new Random(11 + t.index);
            int n = 20_000, shards = 0;
            double rarity = 0;
            for (int i = 0; i < n; i++) {
                var paid = KLoot.roll(l, rng, ALL, 0).paid();
                assertTrue(paid.getOrDefault(KLoot.KRAKEN, 0) > 0, t.id + ": no guaranteed Kraken");
                String s = KLoot.shardIn(paid);
                assertNotEquals(KLoot.KRAKEN, s, "the guaranteed Kraken is not the chest's rolled shard");
                if (s == null) continue;
                shards++;
                rarity += KCatalog.SHARD_RARITIES.indexOf(l.shardRarityOf(s));
            }
            // a rolled shard comes from slot 1 or slot 2: P = 1 - (1 - p1)(1 - p2), from the reference weights
            double p1 = 0, p2 = 0;
            for (KCatalog.Entry e : l.slot1) if (KLoot.isShard(e.item)) p1 += e.weight;
            for (KCatalog.Entry e : l.slot2) if (KLoot.isShard(e.item)) p2 += e.weight;
            p1 /= KCatalog.Loot.weight(l.slot1, ALL);
            p2 /= KCatalog.Loot.weight(l.slot2, ALL);
            assertEquals(1 - (1 - p1) * (1 - p2), shards / (double) n, 0.015, t.id + " rolled-shard share");
            double avg = rarity / shards;
            assertTrue(avg > lastAvg, t.id + ": a hotter hunt must bring rarer shards (" + avg + " vs " + lastAvg + ")");
            lastAvg = avg;
        }
    }

    @Test
    void noHuntingModuleMeansNoShardAndStillAFullChest() throws Exception {
        KCatalog c = KuudraDataTest.load();
        Predicate<String> noShards = id -> !KLoot.isShard(id);
        Random rng = new Random(2);
        for (KCatalog.Loot l : c.loot.values())
            for (int i = 0; i < 2_000; i++) {
                var r = KLoot.roll(l, rng, noShards, 0);
                assertNull(KLoot.shardIn(r.paid()));
                assertFalse(r.paid().isEmpty());
            }
    }

    @Test
    void wheelClimbsToLegendaryAndStops() throws Exception {
        KCatalog.Loot l = KuudraDataTest.load().loot.get("basic");
        String s = "SHARD_BEZAL";
        assertEquals("COMMON", l.shardRarityOf(s));
        Random rng = new Random(4);
        int steps = 0;
        while (true) {
            String up = KLoot.upgradeShard(l, s, rng, ALL);
            if (up == null) break;
            assertTrue(KCatalog.SHARD_RARITIES.indexOf(l.shardRarityOf(up)) > KCatalog.SHARD_RARITIES.indexOf(l.shardRarityOf(s)));
            s = up;
            steps++;
        }
        assertEquals(4, steps, "Common -> Uncommon -> Rare -> Epic -> Legendary");
        assertEquals("LEGENDARY", l.shardRarityOf(s));
        assertNull(KLoot.upgradeShard(l, "NOT_A_SHARD", rng, ALL));
    }

    @Test
    void leviathansBoxAddsTeeth() throws Exception {
        assertEquals(0, KLoot.extraTeeth(0, 0.0));
        assertEquals(1, KLoot.extraTeeth(25, 0.10));
        assertEquals(0, KLoot.extraTeeth(25, 0.30));
        assertEquals(2, KLoot.extraTeeth(200, 0.99));
        KCatalog.Loot l = KuudraDataTest.load().loot.get("hot");
        // a sure extra tooth (100%) puts teeth in every paid chest
        Random rng = new Random(8);
        for (int i = 0; i < 500; i++) assertTrue(KLoot.roll(l, rng, ALL, 0, 100).paid().getOrDefault(KLoot.TEETH, 0) >= 1);
    }

    @Test
    void introQuestline() {
        assertNull(KTests.intro());
        // a whole playthrough: meet, pledge, cull 15, report, hunt
        int s = KIntro.MEET;
        s = KIntro.advance(s, false, 0, 15, 0);
        assertEquals(KIntro.MEET, s);
        s = KIntro.talk(s);
        s = KIntro.advance(s, false, 0, 15, 0);
        assertEquals(KIntro.PLEDGE, s);
        s = KIntro.advance(s, true, 0, 15, 0);
        assertEquals(KIntro.CULL, s);
        for (int k = 1; k <= 15; k++) s = KIntro.advance(s, true, k, 15, 0);
        assertEquals(KIntro.REPORT, s);
        assertFalse(KIntro.unlocked(s));
        s = KIntro.talk(s);
        assertTrue(KIntro.unlocked(s));
        s = KIntro.advance(s, true, 15, 15, 1);
        assertEquals(KIntro.DONE, s);
        assertNull(KIntro.step(s));
        // the reference names never reach players
        for (int i = 0; i <= KIntro.DONE; i++)
            for (String line : KIntro.lines(i, i % 2 == 0)) assertFalse(line.toLowerCase().contains("kuudra") || line.contains("Elle"), line);
        for (KIntro.Step st : KIntro.STEPS) assertFalse(st.objective().toLowerCase().contains("kuudra"), st.objective());
    }

    @Test
    void cutsceneTimelines() {
        assertNull(KTests.scripts());
        KScript.Scene o = KScript.opening();
        // stepping it the way Run does (5 ticks a step) hits every beat exactly once
        int seen = 0;
        for (int t = 0; t < o.length(); t += 5) seen += o.between(t, t + 5).size();
        assertEquals(o.beats().size(), seen);
        assertEquals(List.of("frame"), o.between(0, 5).stream().map(KScript.Beat::id).toList());
        assertNotNull(KScript.byId(KScript.VICTORY));
        assertNull(KScript.byId("nope"));
        assertTrue(KScript.victory().length() <= 100, "the chests should not wait more than 5 s");
        // the crawl is monotonic
        double last = -1;
        for (int i = 0; i <= KMath.CRAWL_STEPS; i++) {
            double p = KMath.crawlProgress(i, KMath.CRAWL_STEPS);
            assertTrue(p >= last);
            last = p;
        }
    }
}
