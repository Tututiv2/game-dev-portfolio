package com.skyclone.mod.farming;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.gen.IslandRun;
import com.skyclone.gen.Walkability;
import com.skyclone.mod.farming.gen.BarnGenerator;
import com.skyclone.mod.farming.gen.DesertGenerator;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The farming module's pure logic and shipped data, against the research numbers. */
class FarmingPureTest {

    private static Reader res(String file) {
        InputStream in = FarmingPureTest.class.getResourceAsStream("/data/farming/" + file);
        assertNotNull(in, "missing resource " + file);
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    @Test
    void fortuneMatchesResearch() {
        // research 08 1.3: 260 fortune = 2 guaranteed extra drops + 60% chance of a third
        assertEquals(4, FortuneMath.roll(1, 260, 0.59));
        assertEquals(3, FortuneMath.roll(1, 260, 0.61));
        assertEquals(3.6, FortuneMath.expected(1, 260), 1e-9);
        assertEquals(10, FortuneMath.roll(5, 100, 0.99));
        assertEquals(5, FortuneMath.roll(5, -50, 0.0));
        // research 08 1.4: Logarithmic Counter 1,234,567 -> +112
        assertEquals(112, FortuneMath.counterFortune(1_234_567, 16));
        assertEquals(0, FortuneMath.counterFortune(0, 16));
        assertEquals(16, FortuneMath.counterFortune(9, 16));
        assertEquals(32, FortuneMath.counterFortune(10, 16));
        assertEquals(56, FortuneMath.analysisFortune(1_000_000, 8));
    }

    @Test
    void toolLevelsAndMilestones() throws Exception {
        Tables t = Tables.read(res("tables.json"), res("shops.json"));
        assertEquals(49, t.toolLevelXp.length, "50 tool levels");
        assertEquals(1, FortuneMath.toolLevel(0, t.toolLevelXp));
        assertEquals(1, FortuneMath.toolLevel(999, t.toolLevelXp));
        assertEquals(2, FortuneMath.toolLevel(1000, t.toolLevelXp));
        long total = 0;
        for (int v : t.toolLevelXp) total += v;
        assertEquals(50, FortuneMath.toolLevel(total, t.toolLevelXp));
        assertEquals(50, FortuneMath.toolLevel(total * 10, t.toolLevelXp));
        assertEquals(15, t.anitaFortune.size());
        assertEquals(10, t.farmingCap.size());
        // research 02 2.4.1: cap 60 costs 3 gold + 500 tickets
        assertEquals(3, t.farmingCap.get(9)[0]);
        assertEquals(500, t.farmingCap.get(9)[1]);
        int[] wheat = t.milestones(Crop.WHEAT);
        assertEquals(46, wheat.length);
        assertEquals(0, FortuneMath.milestone(29, wheat));
        assertEquals(1, FortuneMath.milestone(30, wheat));
        assertEquals(2, FortuneMath.milestone(80, wheat));
        for (Crop c : Crop.values()) assertTrue(t.milestones(c).length > 0, "milestones for " + c);
    }

    @Test
    void contestBrackets() {
        assertEquals(ContestMath.Bracket.DIAMOND, ContestMath.bracket(1, 50, false));
        assertEquals(ContestMath.Bracket.PLATINUM, ContestMath.bracket(5, 100, false));
        assertEquals(ContestMath.Bracket.GOLD, ContestMath.bracket(10, 100, false));
        assertEquals(ContestMath.Bracket.SILVER, ContestMath.bracket(11, 100, false));
        assertEquals(ContestMath.Bracket.BRONZE, ContestMath.bracket(60, 100, false));
        assertEquals(ContestMath.Bracket.NONE, ContestMath.bracket(61, 100, false));
        assertEquals(ContestMath.Bracket.GOLD, ContestMath.bracket(20, 100, true));
        // medals: platinum = gold + bronze, diamond = gold + silver
        assertEquals(1, ContestMath.Bracket.PLATINUM.gold());
        assertEquals(1, ContestMath.Bracket.PLATINUM.bronze());
        assertEquals(1, ContestMath.Bracket.DIAMOND.silver());
        assertEquals(0, ContestMath.Bracket.GOLD.bronze());
        // the schedule
        long period = 60 * 60_000L, offset = 15 * 60_000L, dur = 20 * 60_000L;
        assertTrue(ContestMath.active(offset + 1, period, offset, dur));
        assertTrue(!ContestMath.active(offset + dur, period, offset, dur));
        assertEquals(period - dur, ContestMath.untilNext(offset + dur, period, offset, dur));
        // three distinct crops, deterministic
        List<Crop> a = ContestMath.crops(7, Crop.contestCrops(false)), b = ContestMath.crops(7, Crop.contestCrops(false));
        assertEquals(a, b);
        assertEquals(3, new HashSet<>(a).size());
        // ghosts are sorted, deterministic and at least 100
        double[] g = ContestMath.ghostFinals(3, Crop.WHEAT, 40, 1500, 1.0);
        assertEquals(40, g.length);
        assertTrue(g[0] >= 100);
        for (int i = 1; i < g.length; i++) assertTrue(g[i] >= g[i - 1]);
        // a score just above the gold threshold places gold or better
        double need = ContestMath.scoreFor(ContestMath.Bracket.GOLD, g, false);
        int pos = ContestMath.position(need, g);
        assertTrue(ContestMath.bracket(pos, g.length + 1, false).goldOrBetter());
    }

    @Test
    void cropTable() {
        assertEquals(13, Crop.values().length, "13 garden crops (research 08 1.2)");
        assertEquals(Crop.WHEAT, Crop.ofBlock("minecraft:wheat[age=7]"));
        assertEquals(Crop.MUSHROOM, Crop.ofBlock("brown_mushroom"));
        assertEquals(Crop.COCOA, Crop.parse("INK_SACK:3"));
        assertEquals(Crop.SUGAR_CANE, Crop.parse("cane"));
        assertEquals(2, Crop.SUGAR_CANE.xp, 1e-9, "research 02 2.4.1");
        assertEquals(6, Crop.MUSHROOM.xp, 1e-9);
        assertEquals(4.5, Crop.PUMPKIN.xp, 1e-9);
        assertEquals(10, Crop.contestCrops(false).size());
    }

    @Test
    void toolData() {
        JsonArray items = new Gson().fromJson(res("items.json"), JsonArray.class);
        Set<String> ids = new HashSet<>();
        int crop = 0;
        for (JsonElement e : items) {
            JsonObject o = e.getAsJsonObject();
            ids.add(o.get("id").getAsString());
            if (!o.has("extra") || !o.getAsJsonObject("extra").has("farmTool")) continue;
            ToolSpec s = ToolSpec.parse(o.getAsJsonObject("extra").getAsJsonObject("farmTool"));
            if (s.mk == 0) continue;
            crop++;
            assertTrue(!s.crops.isEmpty(), o.get("id") + " crops");
            if (s.mk < 3) assertNotNull(s.next, o.get("id") + " upgrade");
            assertEquals(s.kind.equals("hoe") && s.mk >= 2, s.counter);
        }
        assertEquals(36, crop, "12 tool lines x Mk I-III");
        for (JsonElement e : items) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("extra") && o.getAsJsonObject("extra").has("farmTool")) {
                JsonObject ft = o.getAsJsonObject("extra").getAsJsonObject("farmTool");
                if (ft.has("next")) assertTrue(ids.contains(ft.get("next").getAsString()), "next of " + o.get("id"));
            }
        }
        JsonArray recipes = new Gson().fromJson(res("recipes.json"), JsonArray.class);
        assertEquals(36, recipes.size());
        for (JsonElement e : recipes) assertTrue(ids.contains(e.getAsJsonObject().get("result").getAsString()));
    }

    @Test
    void personalBestsAndGardenHandshake() throws Exception {
        Tables t = Tables.read(res("tables.json"), res("shops.json"));
        // every crop has a skyhanni personal-best step; 1,000,000 Wheat at step 100 is the 100 cap
        for (Crop c : Crop.values()) assertTrue(t.pbStep(c) > 0, "pb step for " + c);
        assertEquals(100.0, FortuneMath.personalBestFortune(1_000_000, t.pbStep(Crop.WHEAT), 100), 1e-9);
        assertEquals(100.0, FortuneMath.personalBestFortune(50_000_000, t.pbStep(Crop.WHEAT), 100), 1e-9);
        assertEquals(1.23, FortuneMath.personalBestFortune(12_345, 100, 100), 1e-9);
        assertEquals(0.0, FortuneMath.personalBestFortune(0, 100, 100), 1e-9);
        assertEquals(0.0, FortuneMath.personalBestFortune(5000, 0, 100), 1e-9);
        // the Garden fires Gather with its collection item ids: every one must map back to our crop
        String[] gardenIds = {"WHEAT", "CARROT_ITEM", "POTATO_ITEM", "PUMPKIN", "SUGAR_CANE", "MELON", "CACTUS", "INK_SACK:3",
                "RED_MUSHROOM", "BROWN_MUSHROOM", "NETHER_STALK", "DOUBLE_PLANT", "MOONFLOWER", "WILD_ROSE"};
        for (String id : gardenIds) assertNotNull(Crop.parse(id), "garden Gather id " + id);
        assertEquals(Crop.MUSHROOM, Crop.parse("BROWN_MUSHROOM"));
        // tools carry the Garden's crop_fortune hand-shake, keyed by the Garden's crop keys, = their tier fortune
        JsonArray items = new Gson().fromJson(res("items.json"), JsonArray.class);
        int checked = 0;
        for (JsonElement e : items) {
            JsonObject ex = e.getAsJsonObject().getAsJsonObject("extra");
            if (ex == null || !ex.has("farmTool")) continue;
            ToolSpec s = ToolSpec.parse(ex.getAsJsonObject("farmTool"));
            if (s.crops.isEmpty()) continue;
            assertTrue(ex.has("crop_fortune"), e.getAsJsonObject().get("id") + " crop_fortune");
            JsonObject cf = ex.getAsJsonObject("crop_fortune");
            for (Crop c : s.crops) assertEquals(s.cropFortune, cf.get(c.milestoneKey).getAsInt(), "crop_fortune " + c.milestoneKey);
            checked++;
        }
        assertEquals(36, checked);
        assertEquals("COCOA_BEANS", Crop.COCOA.milestoneKey, "the Garden's key for cocoa");
    }

    @Test
    void contestLateRankingIsTheLiveRanking() {
        // A player ranked after the fact (offline at the end) is ranked against the SAME ghost field the live
        // contest used: ghostFinals is deterministic per (index, crop).
        double[] live = ContestMath.ghostFinals(123, Crop.CARROT, 40, 1500, 1.0);
        double[] late = ContestMath.ghostFinals(123, Crop.CARROT, 40, 1500, 1.0);
        assertEquals(java.util.Arrays.toString(live), java.util.Arrays.toString(late));
        long score = (long) live[live.length - 1] + 1;
        assertEquals(ContestMath.Bracket.DIAMOND, ContestMath.bracket(ContestMath.position(score, late), late.length + 1, false));
    }

    @Test
    void contestEarlyEndRanksLikeTheLiveSidebar() {
        // playtest-loops: live sidebar said BRONZE #23/41 with 14 of 20 minutes left; forcing the fair off then ranked
        // the same score against the ghosts' FULL 20-minute finals (#37, no medal). Live and end must use one clock.
        double[] finals = ContestMath.ghostFinals(77, Crop.WHEAT, 40, 1500, 1.0);
        long start = 1_000_000, end = start + 20 * 60_000L, now = start + 6 * 60_000L;
        double t = ContestMath.progress(now, start, end);
        assertEquals(0.3, t, 1e-9);
        double[] live = ContestMath.ghostsAt(finals, t);
        long score = (long) live[(int) (live.length * 0.55)] + 1;       // mid-field at 30% of the fair
        int livePos = ContestMath.position(score, live);
        ContestMath.Bracket liveB = ContestMath.bracket(livePos, live.length + 1, false);
        assertTrue(liveB != ContestMath.Bracket.NONE, "fixture should be in a medal bracket live: #" + livePos);
        // the early end ranks against ghosts at the same progress -> the same place and medal
        double[] atEnd = ContestMath.ghostsAt(finals, ContestMath.progress(now, start, end));
        assertEquals(livePos, ContestMath.position(score, atEnd));
        // against the finals (the old behaviour) the same score falls out of the medals
        assertTrue(ContestMath.position(score, finals) > livePos);
        // a natural end is exactly the finals; progress clamps on both sides
        assertEquals(1.0, ContestMath.progress(end + 5_000, start, end));
        assertEquals(0.0, ContestMath.progress(start - 5_000, start, end));
        assertEquals(java.util.Arrays.toString(finals), java.util.Arrays.toString(ContestMath.ghostsAt(finals, 1.0)));
    }

    @Test
    void ghostFarmersProgressSmoothly() {
        // playtest: NPC farmers looked like they jumped to their full scores at the end, so the live standing misled.
        // Each ghost now follows its own pace: never backwards, no jump, final score exactly at the end, and the
        // field reorders as the fair runs (an even pace kept the same order all fair).
        int n = 40;
        double[] finals = ContestMath.ghostFinals(5, Crop.CARROT, n, 1500, 1.0);
        ContestMath.GhostPace[] paces = ContestMath.ghostPaces(5, Crop.CARROT, n);
        assertEquals(java.util.Arrays.toString(ContestMath.ghostPaces(5, Crop.CARROT, n)), java.util.Arrays.toString(paces));
        int steps = 1200;                                   // one step per second of a 20-minute fair
        double[] prev = ContestMath.ghostsAt(finals, paces, 0);
        for (double v : prev) assertEquals(0.0, v);
        boolean reordered = false;
        for (int s = 1; s <= steps; s++) {
            double t = (double) s / steps;
            double[] now = ContestMath.ghostsAt(finals, paces, t);
            for (int i = 0; i < n; i++) {
                assertTrue(now[i] >= prev[i], "ghost " + i + " went backwards at t=" + t);
                // the fastest a ghost ever farms is 1.65x its average (warm-up up to 6% squeezes it a little more)
                assertTrue(now[i] - prev[i] <= Math.ceil(finals[i] * 1.8 / steps) + 1,
                        "ghost " + i + " jumped " + (now[i] - prev[i]) + " in one second at t=" + t);
            }
            for (int i = 1; i < n && !reordered; i++) if (now[i] < now[i - 1]) reordered = true;
            prev = now;
        }
        assertEquals(java.util.Arrays.toString(finals), java.util.Arrays.toString(prev), "natural end = the finals");
        assertTrue(reordered, "the field never changed order: every ghost is on the same even pace");
        // half-way through, a ghost has between a quarter and three quarters of its final (no back-loading)
        double[] half = ContestMath.ghostsAt(finals, paces, 0.5);
        for (int i = 0; i < n; i++) {
            double share = half[i] / finals[i];
            assertTrue(share > 0.25 && share < 0.75, "ghost " + i + " at half time has " + share);
        }
    }

    @Test
    void islandsAreWalkable() {
        for (IslandGenerator g : List.<IslandGenerator>of(new BarnGenerator(), new DesertGenerator())) {
            IslandRun.Result r = IslandRun.run(g, IslandRun.seedOf(g.islandId()));
            assertEquals(0, r.outsideBounds, g.islandId() + " writes outside its bounds");
            Walkability.Report w = Walkability.analyse(r.canvas, r.layout, new Walkability.Options());
            assertTrue(w.ok(), g.islandId() + ": " + w.failed() + " " + w.failedPortals() + " " + w.spawnProblem);
            int souls = 0;
            for (String a : r.layout.anchors().keySet()) if (a.startsWith("fairy_soul.")) souls++;
            assertTrue(souls >= 10, g.islandId() + " fairy souls " + souls);
            assertNotNull(r.layout.anchor("spawn"));
            assertNotNull(r.layout.area("portal.hub"));
        }
    }
}
