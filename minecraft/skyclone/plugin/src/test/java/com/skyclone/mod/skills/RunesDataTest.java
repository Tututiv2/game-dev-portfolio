package com.skyclone.mod.skills;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.skyclone.api.item.ItemCategory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The SHIPPING rune table (data/skills/runes.json) — what the Runic Pedestal and Runic mobs read. */
class RunesDataTest {

    static List<Runes.Def> defs;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = RunesDataTest.class.getResourceAsStream("/data/skills/runes.json")) {
            assertNotNull(in, "data/skills/runes.json on the classpath");
            defs = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), new TypeToken<List<Runes.Def>>() { }.getType());
        }
    }

    @Test void thirtyFourTypesEveryFieldSet() {
        assertEquals(34, defs.size());
        Set<String> ids = new HashSet<>();
        for (Runes.Def r : defs) {
            assertNotNull(r.key); assertNotNull(r.name); assertNotNull(r.skull, r.key + " skull");
            assertNotNull(r.flavour); assertNotNull(r.particle);
            assertTrue(r.level >= 1 && r.level <= 25, r.key + " level " + r.level);
            assertTrue(List.of("WEAPON", "BOW", "BOOTS", "CHESTPLATE", "HOE").contains(r.slot), r.key + " slot");
            assertTrue(List.of("runic", "drop").contains(r.source), r.key + " source");
            assertTrue(r.id.endsWith("_RUNE"), r.id);
            for (int t = 1; t <= Runes.MAX_TIER; t++) assertTrue(ids.add(r.itemId(t)), "duplicate id " + r.itemId(t));
            assertFalse(r.name.contains("§"), r.key + " name has a formatting code");
        }
    }

    @Test void slayerDropIdsAreRunes() {
        // slayer's loot tables name these tier-I ids; they must be the pedestal's runes
        Set<String> ids = new HashSet<>();
        for (Runes.Def r : defs) ids.add(r.itemId(1));
        for (String id : List.of("ZOMBIE_SLAYER_RUNE", "SNAKE_RUNE", "BITE_RUNE", "DARKNESS_WITHIN_RUNE", "SPIRIT_RUNE",
                "COUTURE_RUNE", "ENDERSNAKE_RUNE", "DRAGON_RUNE", "ENCHANT_RUNE", "LAVATEARS_RUNE", "FIERY_BURST_RUNE", "SOULTWIST_RUNE"))
            assertTrue(ids.contains(id), id);
    }

    @Test void newPlayersCanEarnAndUseRunes() {
        int early = 0;
        for (Runes.Def r : defs) if ("runic".equals(r.source) && r.level <= 5) early++;
        assertTrue(early >= 4, "runic runes usable by Runecrafting 0-5: " + early);
    }

    @Test void slotsMatchCategories() {
        assertTrue(Runes.fits("WEAPON", ItemCategory.SWORD));
        assertTrue(Runes.fits("BOW", ItemCategory.BOW));
        assertTrue(Runes.fits("BOOTS", ItemCategory.BOOTS));
        assertFalse(Runes.fits("BOOTS", ItemCategory.HELMET));
        assertFalse(Runes.fits("WEAPON", ItemCategory.BOW));
        assertTrue(Runes.fits("HOE", ItemCategory.HOE));
        assertFalse(Runes.fits("WEAPON", null));
    }
}
