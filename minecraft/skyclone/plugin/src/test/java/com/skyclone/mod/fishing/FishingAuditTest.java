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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real-game audit of 2026-10-06 (docs/AUDIT_REAL_GAME_2026-10-06.md section 4, rows F1-F12), checked
 * against the SHIPPING data files (research: SkyblockMoney/work/fishing.md, mining_fishing_gear.md).
 */
class FishingAuditTest {

    private static Reader res(String file) {
        InputStream in = FishingAuditTest.class.getResourceAsStream("/data/fishing/" + file);
        assertNotNull(in, "missing resource " + file);
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    private static FishingCatalog cat() { return FishingCatalog.read(res("fishing.json")); }

    private static Map<String, JsonObject> mobs() {
        Map<String, JsonObject> out = new HashMap<>();
        for (JsonElement e : new Gson().fromJson(res("mobs.json"), JsonArray.class))
            out.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
        return out;
    }

    private static Map<String, JsonObject> items() {
        Map<String, JsonObject> out = new HashMap<>();
        for (JsonElement e : new Gson().fromJson(res("items.json"), JsonArray.class))
            out.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
        return out;
    }

    private static double drop(JsonObject mob, String item) {
        for (JsonElement d : mob.getAsJsonArray("drops"))
            if (d.getAsJsonObject().get("item").getAsString().equals(item)) return d.getAsJsonObject().get("chance").getAsDouble();
        return -1;
    }

    private static int fxp(JsonObject mob) { return mob.getAsJsonObject("extra").get("fishingXp").getAsInt(); }

    /** F2: junk has its own split, Good 89 / Great 10 / Outstanding 1, one item per tier, Moby-Duck 1/6 of Outstanding. */
    @Test
    void junkTiersAreTheJunkSinkersOwn() {
        FishingCatalog.Treasure t = cat().treasure;
        Map<String, Double> w = t.weightsFor(true);
        assertEquals(0.89, FishMath.treasureShare(w, 0, 0, 0, "good"), 1e-9);
        assertEquals(0.10, FishMath.treasureShare(w, 0, 0, 0, "great"), 1e-9);
        assertEquals(0.01, FishMath.treasureShare(w, 0, 0, 0, "outstanding"), 1e-9);
        assertEquals(t.weights, t.weightsFor(false), "plain treasure keeps its own split");
        assertEquals(List.of("RUSTY_COIN"), t.junk.get("good").stream().map(e -> e.item).toList());
        assertEquals(List.of("BUSTED_BELT_BUCKLE"), t.junk.get("great").stream().map(e -> e.item).toList());
        Map<String, Double> out = new HashMap<>();
        for (FishingCatalog.TreasureEntry e : t.junk.get("outstanding")) { out.put(e.item, e.weight); assertEquals(1, e.max); }
        assertEquals(Set.of("OLD_LEATHER_BOOT", "MOBY_DUCK"), out.keySet());
        assertEquals(1.0 / 6, out.get("MOBY_DUCK") / (out.get("MOBY_DUCK") + out.get("OLD_LEATHER_BOOT")), 1e-9);
        for (String q : List.of("good", "great", "outstanding"))
            for (FishingCatalog.TreasureEntry e : t.junk.get(q)) { assertEquals(1, e.min); assertEquals(1, e.max); }
        // the research's hourly figures: 20.5 junk/h -> 18.3 Rusty Coins, 2.05 Buckles, 0.17 Boots, 0.034 Moby-Ducks
        assertEquals(18.3, 20.5 * 0.89, 0.1);
        assertEquals(0.034, 20.5 * 0.01 / 6, 0.001);
    }

    /** F10: the Moby-Duck is a registered item (and so is what it evolves into). */
    @Test
    void mobyDuckExists() {
        Map<String, JsonObject> it = items();
        assertTrue(it.containsKey("MOBY_DUCK"));
        assertTrue(it.containsKey("MOBY_DUCK_COLLECTOR_EDITION"));
        assertEquals("RARE", it.get("MOBY_DUCK").get("rarity").getAsString());
        assertEquals(1, it.get("MOBY_DUCK").get("maxStack").getAsInt(), "the bonus lives on the item: it must not stack");
    }

    /** F3-F6: the current wiki's drop chances beat NEU's. */
    @Test
    void dropChancesFollowTheWiki() {
        Map<String, JsonObject> m = mobs();
        assertEquals(0.05, drop(m.get("sc_trash_gobbler"), "CAN_OF_WORMS"), 1e-12);
        assertEquals(0.02, drop(m.get("sc_dumpster_diver"), "BRONZE_BOWL"), 1e-12);
        assertEquals(0.10, drop(m.get("sc_fried_chicken"), "FRIED_FEATHER"), 1e-12);
        assertEquals(0.01, drop(m.get("sc_fireproof_witch"), "SINGED_POWDER"), 1e-12);
        // matches the research already had
        assertEquals(0.25, drop(m.get("sc_flaming_worm"), "WORM_MEMBRANE"), 1e-12);
        assertEquals(1.0, drop(m.get("sc_alligator"), "ALLIGATOR_SKIN"), 1e-12);
        assertEquals(0.015, drop(m.get("sc_thunder"), "ENCHANTMENT_ULTIMATE_FLASH_1"), 1e-12);
        assertEquals(0.005, drop(m.get("sc_lord_jawbus"), "RADIOACTIVE_VIAL"), 1e-12);
    }

    /** F9: Lord Jawbus needs Fishing 45 AND the Adept Trophy Fisher title (every trophy fish at Silver). */
    @Test
    void jawbusNeedsAdeptTrophyFisher() {
        JsonObject j = mobs().get("sc_lord_jawbus");
        assertEquals("trophy:title:2", j.getAsJsonObject("extra").get("requires").getAsString());
        FishingCatalog c = cat();
        FishingCatalog.Title adept = c.trophy.titles.stream().filter(t -> t.level == 2).findFirst().orElseThrow();
        assertEquals("Adept Trophy Fisher", adept.name);
        assertEquals("SILVER", adept.tier);
        PoolResolver r = new PoolResolver(c);
        PoolResolver.Spot s = new PoolResolver.Spot();
        s.island = "crimson_isle"; s.liquid = "lava"; s.fishingLevel = 45;
        s.requirement = mob -> !mob.equals("sc_lord_jawbus");      // no title yet
        assertFalse(ids(r.options(s)).contains("sc_lord_jawbus"));
        s.requirement = mob -> true;                                 // Adept
        assertTrue(ids(r.options(s)).contains("sc_lord_jawbus"));
        s.fishingLevel = 44;
        assertFalse(ids(r.options(s)).contains("sc_lord_jawbus"));
    }

    /** F12: the Flaming Worm only bites in the Precursor lava ABOVE Y64; the Magma Fields keep theirs. */
    @Test
    void flamingWormNeedsLavaAboveY64() {
        PoolResolver r = new PoolResolver(cat());
        PoolResolver.Spot s = new PoolResolver.Spot();
        s.island = "crystal_hollows"; s.liquid = "lava"; s.fishingLevel = 30; s.zoneText = " precursor_remnants relic remnants";
        s.y = 90;
        assertEquals(Set.of("sc_flaming_worm"), ids(r.options(s)));
        s.y = 64;
        assertTrue(ids(r.options(s)).isEmpty(), "Y64 itself is not above 64");
        s.y = 40;
        assertTrue(ids(r.options(s)).isEmpty());
        s.zoneText = " magma_fields magma fields";
        assertEquals(Set.of("sc_lava_blaze", "sc_lava_pigman"), ids(r.options(s)));
        // fishing level gate stays: Fishing 19
        s.zoneText = " precursor_remnants"; s.y = 100; s.fishingLevel = 18;
        assertTrue(ids(r.options(s)).isEmpty());
        // height unknown (menus): the condition does not hide it
        s.fishingLevel = 19; s.y = Double.NaN;
        assertEquals(Set.of("sc_flaming_worm"), ids(r.options(s)));
        assertTrue(PoolResolver.cond("above:64", s));
        s.y = 65;
        assertTrue(PoolResolver.cond("above:64&below:70", s));
        s.y = 70;
        assertFalse(PoolResolver.cond("above:64&below:70", s));
    }

    /** Alligator ~1 in 100 sea creatures at Fishing 20 (Titanoboa locked), the research's measured share. */
    @Test
    void alligatorIsOneInAHundredAtFishingTwenty() {
        PoolResolver r = new PoolResolver(cat());
        PoolResolver.Spot s = new PoolResolver.Spot();
        s.island = "backwater_bayou"; s.fishingLevel = 20;
        Map<String, Double> odds = PoolResolver.odds(r.options(s), null);
        assertEquals(0.01, odds.get("sc_alligator"), 0.0003);
        assertEquals(0.501, odds.get("sc_trash_gobbler"), 0.001);
        assertEquals(0.288, odds.get("sc_dumpster_diver"), 0.001);
        assertFalse(odds.containsKey("sc_titanoboa"));
        s.fishingLevel = 19;
        assertFalse(PoolResolver.odds(r.options(s), null).containsKey("sc_alligator"));
    }

    /** Baits whose numbers the audit re-checked (Fish/Whale/Treasure/Hotspot/Worm) and the Treasure Hook / Junk Sinker. */
    @Test
    void baitsAndPartsMatchTheReference() {
        FishingCatalog c = cat();
        assertEquals(45, c.baits.get("FISH_BAIT").fs, 1e-9);
        assertEquals(30, c.baits.get("WHALE_BAIT").fs, 1e-9);
        assertEquals(1.25, c.baits.get("WHALE_BAIT").rareAlways, 1e-9);
        assertEquals(2, c.baits.get("TREASURE_BAIT").treasure, 1e-9);
        assertEquals(10, c.baits.get("TREASURE_BAIT").fs, 1e-9);
        assertEquals(0.20, c.baits.get("HOTSPOT_BAIT").hotspotChance, 1e-9);
        assertEquals(60, c.baits.get("WORM_BAIT").fs, 1e-9);
        assertTrue(c.baits.get("WORM_BAIT").wormsOnly);
        assertTrue(c.parts.get("TREASURE_HOOK").noCreatures);
        assertTrue(c.parts.get("JUNK_SINKER").junk);
        // more Sea Creature Chance means fewer item catches, so fewer junk rolls at the Bayou
        double tc = 0.24;
        double junkAt40 = (1 - FishMath.seaCreatureProbability(40, 1)) * tc;
        double junkAt60 = (1 - FishMath.seaCreatureProbability(60, 1)) * tc;
        assertTrue(junkAt60 < junkAt40);
    }

    /** F1: Fishing XP is the live game's (0.27.2, 2026-10-06), not the stale SkyHanni table. */
    @Test
    void seaCreatureXpIsTheLiveGames() {
        Map<String, JsonObject> m = mobs();
        assertEquals(25, fxp(m.get("sc_sea_walker")));          // 68 (SkyHanni) -> 150 (2025-03) -> 25 (0.27.2)
        assertEquals(25, fxp(m.get("sc_squid")));
        assertEquals(1150, fxp(m.get("sc_flaming_worm")));      // was 240
        assertEquals(2640, fxp(m.get("sc_alligator")));
        assertEquals(6480, fxp(m.get("sc_thunder")));
        assertEquals(14925, fxp(m.get("sc_lord_jawbus")));      // was 40,000
        assertEquals(127, fxp(m.get("sc_trash_gobbler")));
        assertEquals(115, m.get("sc_flaming_worm").get("coins").getAsInt());
        assertEquals(92, m.get("sc_flaming_worm").get("level").getAsInt());
        assertEquals("LEGENDARY", m.get("sc_thunder").getAsJsonObject("extra").get("rarity").getAsString());
        // every creature the patch touched obeys its formula: XP = floor(level x rate), at least 25
        Map<String, Double> rate = Map.of("COMMON", 7.5, "UNCOMMON", 10.0, "RARE", 12.5, "EPIC", 15.0, "LEGENDARY", 20.0, "MYTHIC", 25.0);
        int checked = 0;
        for (JsonObject mob : m.values()) {
            String id = mob.get("id").getAsString();
            if (id.contains("mithril_grubber") || id.equals("sc_sea_emperor")) continue;   // pages the patch did not update
            JsonObject ex = mob.getAsJsonObject("extra");
            int lvl = mob.get("level").getAsInt();
            int want = Math.max(25, (int) Math.floor(lvl * rate.get(ex.get("rarity").getAsString())));
            assertEquals(want, fxp(mob), id);
            checked++;
        }
        assertTrue(checked >= 80, "checked " + checked);
        // Sea Archer: Fishing IX (NEU lore + wiki), not VIII
        FishingCatalog c = cat();
        for (FishingCatalog.PoolCreature pc : c.pool("water").creatures)
            if (pc.mob.equals("sc_sea_archer")) assertEquals(9, pc.level);
    }

    /** F10: the Moby-Duck ages 0.1 Wisdom per online hour to 30 (300 h); a read buff lasts, Bookworm stays. */
    @Test
    void mobyDuckAgesAndBuffs() {
        double v = 0;
        int steps = 0;
        while (v < 30) { v = FishMath.mobyAge(v, 0.1 * 6 / 60.0, 30); steps++; }
        assertEquals(30, v, 1e-12);
        assertEquals(300.0, steps * 6 / 60.0, 1e-9, "300 hours online");
        assertEquals(30, FishMath.mobyAge(29.99, 5, 30), 1e-12);
        assertEquals(12.5, FishMath.mobyWisdom(12.5, 1000, 999, false, 1), 1e-12);
        assertEquals(0, FishMath.mobyWisdom(12.5, 1000, 1000, false, 1), 1e-12);
        assertEquals(31, FishMath.mobyWisdom(30, 1000, 0, true, 1), 1e-12);
        assertEquals(1, FishMath.mobyWisdom(30, 1000, 5000, true, 1), 1e-12);
        assertEquals("12.3", FishMath.oneDecimal(12.34));
        assertEquals("30", FishMath.oneDecimal(30));
    }

    /** F11: the Bayou guide's seven chapters, Fishing XP per task 100 .. 10,000, the Pearl at the end. */
    @Test
    void jonesyChaptersEndInThePearl() {
        List<FishingCatalog.Chapter> ch = cat().jonesy;
        assertEquals(7, ch.size());
        double[] xp = {100, 250, 500, 1000, 2500, 5000, 10000};
        int[] sb = {5, 10, 15, 20, 25, 30, 35};
        Set<String> kinds = Set.of("talk", "collection", "fished", "caught", "hotspot", "own", "part", "pet");
        Map<String, JsonObject> mobs = mobs();
        for (int i = 0; i < 7; i++) {
            assertEquals(xp[i], ch.get(i).xp, 1e-9);
            assertEquals(sb[i], ch.get(i).sbXp);
            assertEquals(i == 6, ch.get(i).pearl);
            assertFalse(ch.get(i).tasks.isEmpty());
            for (FishingCatalog.Task t : ch.get(i).tasks) {
                assertTrue(kinds.contains(t.kind), t.kind);
                if (t.kind.equals("caught")) assertTrue(mobs.containsKey(t.arg), t.arg);
            }
        }
        assertEquals("TREASURE_BAIT", ch.get(0).rewards.get(0).item);
        assertEquals(4, ch.get(0).rewards.get(0).min);
        assertTrue(ch.get(6).rewards.isEmpty());
        assertTrue(FishMath.rarityAtLeast("EPIC", "RARE"));
        assertFalse(FishMath.rarityAtLeast("UNCOMMON", "RARE"));
        assertFalse(FishMath.rarityAtLeast(null, "COMMON"));
        assertEquals(7, FishMath.currentChapter(9, 7));
    }

    private static Set<String> ids(List<PoolResolver.Option> opts) {
        Set<String> s = new HashSet<>();
        for (PoolResolver.Option o : opts) s.add(o.mob());
        return s;
    }
}
