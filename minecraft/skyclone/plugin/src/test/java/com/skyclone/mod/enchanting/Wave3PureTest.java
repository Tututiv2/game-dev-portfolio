package com.skyclone.mod.enchanting;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Wave 3 (2026-10-04): the pure parts of Piercing, Duplex, Lapidary, The One and use-levelling. */
class Wave3PureTest {

    static EnchantRegistry reg;

    static Path res(String rel) {
        Path p = Path.of("src/main/resources/data", rel);
        if (!Files.exists(p)) p = Path.of("plugin/src/main/resources/data", rel);
        return p;
    }

    @BeforeAll
    static void load() throws Exception {
        try (Reader r = Files.newBufferedReader(res("enchanting/enchants.json"), StandardCharsets.UTF_8)) { reg = EnchantRegistry.read(r); }
    }

    @Test void piercingFirstTargetFullThenQuarter() {
        EnchantSpec s = reg.spec("piercing");
        assertNotNull(s);
        assertEquals(25, s.v(1), 1e-9);
        assertEquals(1.0, EnchantMath.piercingShare(1, s.v(1)), 1e-9);
        assertEquals(0.25, EnchantMath.piercingShare(2, s.v(1)), 1e-9);
        assertEquals(0.25, EnchantMath.piercingShare(5, s.v(1)), 1e-9);
        assertEquals(0.0, EnchantMath.piercingShare(2, -10), 1e-9);
    }

    @Test void dragonSeekerRadiiAndDuplexMultiplier() {
        EnchantSpec aim = reg.spec("aiming");
        assertNotNull(aim);
        assertEquals(2, aim.v(1), 1e-9);
        assertEquals(10, aim.v(5), 1e-9);
        EnchantSpec dup = reg.spec("ultimate_reiterate");
        assertNotNull(dup);
        assertEquals(1.5, dup.v(1, 5), 1e-9);
        assertEquals(1.5, EnchantMath.duplexMark(1.2, 1.5), 1e-9);
        assertEquals(1.5, EnchantMath.duplexMark(1.5, 1.1), 1e-9, "a weaker mark never overwrites a stronger one");
        assertEquals(1.0, EnchantMath.duplexMark(0, 0), 1e-9);
    }

    @Test void lapidaryGemstoneBlocks() {
        assertTrue(EnchantMath.isGemstoneBlock("RED_STAINED_GLASS"));
        assertTrue(EnchantMath.isGemstoneBlock("purple_stained_glass_pane"));
        assertFalse(EnchantMath.isGemstoneBlock("GLASS"));
        assertFalse(EnchantMath.isGemstoneBlock("STONE"));
        assertFalse(EnchantMath.isGemstoneBlock(null));
        EnchantSpec s = reg.spec("lapidary");
        assertEquals(100, s.v(1, 5), 1e-9, "Lapidary V: +100 Mining Speed on gemstones");
    }

    @Test void theOneReadsEveryCollectionsTopTier() throws Exception {
        Path col = res("collections/collections.json");
        if (!Files.exists(col)) return;   // collections module absent in a stripped checkout
        Map<String, Integer> tops;
        try (Reader r = Files.newBufferedReader(col, StandardCharsets.UTF_8)) { tops = EnchantMath.collectionTops(JsonParser.parseReader(r)); }
        assertTrue(tops.size() >= 50, "collections read: " + tops.size());
        assertTrue(tops.getOrDefault("WHEAT", 0) >= 9, "wheat top tier " + tops.get("WHEAT"));
        for (Map.Entry<String, Integer> e : tops.entrySet()) assertTrue(e.getValue() >= 1, e.getKey());
        EnchantSpec one = reg.spec("ultimate_the_one");
        assertEquals(1.0, one.v(0, 5), 1e-9);
        assertEquals(0.2, one.v(1, 5), 1e-9);
        assertTrue(EnchantMath.collectionTops(null).isEmpty());
        assertTrue(EnchantMath.collectionTops(JsonParser.parseString("{\"collections\":5}")).isEmpty());
    }

    @Test void hecatombLevelsFromDungeonsCounter() {
        EnchantSpec h = reg.spec("hecatomb");
        assertNotNull(h);
        assertTrue(h.levelsByUse(), "hecatomb levels by use");
        assertEquals(1, h.levelForCounter(0));
        // every threshold reached raises the level by exactly one, and the top is maxLevel
        for (int l = 2; l <= h.maxLevel; l++) {
            long t = h.thresholdFor(l);
            assertEquals(l - 1, h.levelForCounter(t - 1), "just below level " + l);
            assertEquals(l, h.levelForCounter(t), "at level " + l);
        }
        assertEquals(h.maxLevel, h.levelForCounter(Long.MAX_VALUE / 2));
    }

    @Test void frostWalkerAndRainbowHaveData() {
        assertEquals(2, reg.spec("frost_walker").maxLevel);
        assertTrue(reg.spec("frost_walker").conflictsWith("depth_strider"));
        assertTrue(reg.spec("rainbow").describe(1).contains("random colour"));
    }
}
