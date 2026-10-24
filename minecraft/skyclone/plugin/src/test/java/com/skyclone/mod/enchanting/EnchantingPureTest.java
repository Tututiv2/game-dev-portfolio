package com.skyclone.mod.enchanting;

import com.google.gson.Gson;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.stat.Stat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Pure logic of the enchanting module against the SHIPPING data files. */
class EnchantingPureTest {

    static EnchantRegistry reg;
    static ExperimentSpec ex;

    static Path data(String file) {
        Path p = Path.of("src/main/resources/data/enchanting", file);
        if (!Files.exists(p)) p = Path.of("plugin/src/main/resources/data/enchanting", file);
        return p;
    }

    @BeforeAll
    static void load() throws Exception {
        try (Reader r = Files.newBufferedReader(data("enchants.json"), StandardCharsets.UTF_8)) { reg = EnchantRegistry.read(r); }
        try (Reader r = Files.newBufferedReader(data("experiments.json"), StandardCharsets.UTF_8)) { ex = new Gson().fromJson(r, ExperimentSpec.class); }
    }

    static ItemDef item(ItemCategory c) { return ItemDef.of("TEST_" + c.name(), "x", "STONE", Rarity.COMMON, c); }

    @Test void everyEnchantIsWellFormed() {
        assertTrue(reg.size() >= 150, "enchant count " + reg.size());
        for (EnchantSpec s : reg.all()) {
            assertTrue(s.minLevel >= 1 && s.maxLevel >= s.minLevel, s.id + " levels");
            assertTrue(s.tableMax <= s.maxLevel, s.id + " table max above max");
            assertFalse(s.name == null || s.name.isBlank(), s.id + " name");
            assertTrue(!s.categories.isEmpty() || !s.itemIds.isEmpty() || !s.tags.isEmpty(), s.id + " applies to nothing");
            for (String c : s.categories) assertEquals(c, ItemCategory.parse(c).name(), s.id + " bad category " + c);
            for (EnchantSpec.StatRef st : s.stats) assertNotNull(Stat.parse(st.stat), s.id + " bad stat " + st.stat);
            for (int l = s.minLevel; l <= s.maxLevel; l++) {
                assertFalse(s.describe(l).contains("{"), s.id + " unfilled placeholder at " + l);
                assertFalse(s.fill(s.shortDesc, l).matches(".*\\{\\d}.*"), s.id + " short placeholder");
                assertNotNull(s.rarity(l));
            }
            for (String c : s.conflicts) assertNotNull(reg.spec(c), s.id + " conflicts with unknown " + c);
            assertEquals(s.ultimate, s.id.startsWith("ultimate_"), s.id + " ultimate flag");
        }
    }

    @Test void researchNumbers() {
        EnchantSpec sharp = reg.spec("sharpness");
        assertEquals(30, sharp.v(5), 1e-9);
        assertEquals(50, sharp.v(7), 1e-9);              // research 01 §14.3, 2026 values
        assertEquals(30, sharp.cost(5));
        assertEquals(5, sharp.tableMax);
        assertEquals(30, reg.spec("protection").v(7), 1e-9);
        assertEquals(105, reg.spec("growth").v(7), 1e-9);
        assertEquals(100, reg.spec("critical").v(7), 1e-9);
        assertEquals(65, reg.spec("giant_killer").v(1, 7), 1e-9);
        assertEquals(1.25, reg.spec("execute").v(6), 1e-9);
        assertEquals(10, reg.spec("champion").v(10), 1e-9);
        assertEquals(50_000, reg.spec("champion").thresholdFor(2));
        assertEquals(3_000_000, reg.spec("champion").thresholdFor(10));
        assertEquals(9, reg.spec("critical").skill);       // research 02 Enchanting road
        assertEquals(38, reg.spec("ultimate_one_for_all").skill);
        assertEquals(500, reg.spec("ultimate_one_for_all").v(1), 1e-9);
        assertEquals("Stormlord", reg.spec("thunderlord").name);   // renamed proper noun
        assertNull(reg.spec("telekinesis"), "telekinesis is the default here");
    }

    @Test void applicability() {
        ItemDef sword = item(ItemCategory.SWORD), helm = item(ItemCategory.HELMET), pick = item(ItemCategory.PICKAXE), bow = item(ItemCategory.BOW);
        assertTrue(reg.spec("sharpness").appliesTo(sword));
        assertFalse(reg.spec("sharpness").appliesTo(helm));
        assertTrue(reg.spec("protection").appliesTo(helm));
        assertTrue(reg.spec("efficiency").appliesTo(pick));
        assertTrue(reg.spec("power").appliesTo(bow));
        assertTrue(reg.spec("big_brain").appliesTo(helm));
        assertFalse(reg.spec("big_brain").appliesTo(item(ItemCategory.BOOTS)));
        assertFalse(reg.tableFor(sword).isEmpty());
        assertTrue(reg.tableFor(null).stream().allMatch(EnchantSpec::onTable));
    }

    @Test void bookIds() {
        assertEquals("ENCHANTMENT_SHARPNESS_5", EnchantRegistry.bookId("sharpness", 5));
        assertEquals(new EnchantRegistry.BookRef("sharpness", 5), reg.parseBook("ENCHANTMENT_SHARPNESS_5"));
        assertEquals(new EnchantRegistry.BookRef("sharpness", 5), reg.parseBook("SHARPNESS;5"));
        assertEquals(new EnchantRegistry.BookRef("ultimate_wise", 3), reg.parseBook("ENCHANTMENT_ULTIMATE_WISE_3"));
        assertNull(reg.parseBook("ENCHANTMENT_SHARPNESS_9"));
        assertNull(reg.parseBook("ENCHANTED_DIAMOND"));
        assertNull(reg.parseBook("ENCHANTMENT_NOPE_1"));
    }

    @Test void anvil() {
        ItemDef sword = item(ItemCategory.SWORD);
        AnvilRules.Result r = AnvilRules.combineBooks(reg, "sharpness", 4, "sharpness", 4, 1);
        assertTrue(r.ok);
        assertEquals(5, r.bookLevel);
        assertTrue(AnvilRules.combineBooks(reg, "sharpness", 5, "sharpness", 5, 1).ok);    // anvil cap = table max + 1
        assertFalse(AnvilRules.combineBooks(reg, "sharpness", 6, "sharpness", 6, 1).ok);
        assertFalse(AnvilRules.combineBooks(reg, "sharpness", 3, "sharpness", 4, 1).ok);
        assertFalse(AnvilRules.combineBooks(reg, "champion", 1, "champion", 1, 1).ok);       // cannot be combined
        assertTrue(AnvilRules.combineBooks(reg, "ultimate_wise", 4, "ultimate_wise", 4, 1).ok);

        r = AnvilRules.applyBook(reg, sword, Map.of("sharpness", 4), Map.of("sharpness", 4), 60, true, 1);
        assertTrue(r.ok);
        assertEquals(5, r.enchants.get("sharpness"));
        r = AnvilRules.applyBook(reg, sword, Map.of("sharpness", 5), Map.of("sharpness", 3), 60, true, 1);
        assertFalse(r.ok);
        r = AnvilRules.applyBook(reg, sword, Map.of("smite", 5), Map.of("ultimate_one_for_all", 1), 60, true, 1);
        assertTrue(r.ok);
        assertFalse(r.enchants.containsKey("smite"));
        assertTrue(r.removed.contains("smite"));
        r = AnvilRules.applyBook(reg, sword, Map.of("ultimate_one_for_all", 1), Map.of("critical", 1), 60, true, 1);
        assertFalse(r.ok);
        r = AnvilRules.applyBook(reg, sword, new LinkedHashMap<>(Map.of("ultimate_wise", 5)), Map.of("ultimate_swarm", 2), 60, true, 1);
        assertTrue(r.ok);
        assertEquals(Set.of("ultimate_swarm"), r.enchants.keySet());
        r = AnvilRules.applyBook(reg, sword, Map.of("giant_killer", 3), Map.of("titan_killer", 3), 60, true, 1);
        assertFalse(r.ok, "Giant Killer and Titan Killer conflict");
        r = AnvilRules.applyBook(reg, sword, Map.of(), Map.of("critical", 3), 8, true, 1);
        assertFalse(r.ok, "Critical needs Enchanting 9");
        assertTrue(AnvilRules.applyBook(reg, sword, Map.of(), Map.of("critical", 3), 8, false, 1).ok);
        assertFalse(AnvilRules.potato(sword, 10, false, 10, 15).ok);
        assertFalse(AnvilRules.potato(sword, 5, true, 10, 15).ok);
        assertEquals(15, AnvilRules.potato(sword, 14, true, 10, 15).potatoBooks);
        assertFalse(AnvilRules.potato(item(ItemCategory.PICKAXE), 0, false, 10, 15).ok);
    }

    @Test void math() {
        assertEquals(10, EnchantMath.execute(0.2, 50, 100), 1e-9);
        assertEquals(0, EnchantMath.execute(0.2, 100, 100), 1e-9);
        assertEquals(5, EnchantMath.prosecute(0.1, 50, 100), 1e-9);
        assertEquals(65, EnchantMath.giantKiller(1.2, 65, 10_000, 1_000), 1e-9);
        assertEquals(12, EnchantMath.giantKiller(0.1, 5, 1_000, 1_000) + 12, 1e-9);
        assertEquals(42, EnchantMath.titanKiller(14, 80, 300), 1e-9);
        assertEquals(80, EnchantMath.titanKiller(14, 80, 1000), 1e-9);
        assertEquals(1.0, EnchantMath.extraDefenseMultiplier(100, 0), 1e-9);
        assertEquals(200.0 / 300.0, EnchantMath.extraDefenseMultiplier(100, 100), 1e-9);
        assertEquals(2.0 / 1.5, EnchantMath.defenseShredMultiplier(100, 50), 1e-9);
        assertEquals(1.5, EnchantMath.strengthBonusMultiplier(100, 100), 1e-9);
        assertEquals(1395, EnchantMath.pointsForLevel(30));
        assertEquals(352, EnchantMath.pointsForLevel(16));
        assertEquals(8, EnchantMath.snipe(4, 25), 1e-9);
        assertTrue(EnchantMath.mobTypes("ZOMBIE", Set.of(), null, "hub_graveyard_zombie").contains("undead"));
        assertTrue(EnchantMath.mobTypes("SPIDER", Set.of(), null, "x").contains("arthropod"));
        assertTrue(EnchantMath.mobTypes("ENDERMAN", Set.of(), null, "end_zealot").contains("ender"));
        assertTrue(EnchantMath.mobTypes("SQUID", Set.of("sea_creature"), null, "x").contains("aquatic"));
        assertTrue(EnchantMath.mobTypes("ZOMBIE", Set.of(), List.of("Woodland"), "x").contains("woodland"));
        assertFalse(EnchantMath.mobTypes("COW", Set.of(), null, "x").contains("undead"));
    }

    @Test void levelByUse() {
        EnchantSpec c = reg.spec("compact");
        assertEquals(1, c.levelForCounter(0));
        assertEquals(2, c.levelForCounter(100));
        assertEquals(3, c.levelForCounter(999));
        assertEquals(10, c.levelForCounter(5_000_000));
        assertEquals(0.25, c.v(1, 1), 1e-9);
    }

    @Test void experimentsData() {
        assertEquals(6, ex.tiers.size());
        for (ExperimentSpec.Tier t : ex.tiers) {
            assertEquals(0, (t.tiles() - t.specials) % 2, t.id);
            assertFalse(t.rewards.isEmpty(), t.id);
            for (ExperimentSpec.Reward r : t.rewards) {
                if (r.item.startsWith("ENCHANTMENT_")) assertNotNull(reg.parseBook(r.item), r.item);
            }
        }
        assertEquals(15, ex.tier("metaphysical").chronomatronRounds);
        assertEquals(50, ex.tier("metaphysical").skill);   // wiki Superpairs: Enchanting L (audit G12)
    }

    @Test void superpairsPerfectPlayWins() {
        Random rnd = new Random(7);
        for (ExperimentSpec.Tier t : ex.tiers) {
            SuperpairsBoard b = SuperpairsBoard.deal(t, 1000, rnd);
            assertEquals(t.tiles(), b.tiles.size());
            for (int i = 0; i < b.tiles.size(); i++) {
                for (int j = i + 1; j < b.tiles.size(); j++) {
                    SuperpairsBoard.Tile x = b.tiles.get(i), y = b.tiles.get(j);
                    if (!x.matched && !y.matched && x.kind == SuperpairsBoard.Kind.REWARD && x.key().equals(y.key())) {
                        assertEquals(SuperpairsBoard.Outcome.FIRST, b.click(i, rnd));
                        assertEquals(SuperpairsBoard.Outcome.MATCH, b.click(j, rnd));
                        break;
                    }
                }
            }
            assertTrue(b.allFound(), t.id);
            assertEquals(t.pairs(), b.won.size());
        }
    }

    @Test void superpairsClicksRunOut() {
        ExperimentSpec.Tier t = ex.tier("beginner");
        SuperpairsBoard b = SuperpairsBoard.deal(t, 2, new Random(1));
        b.click(0, new Random(1));
        b.click(1, new Random(1));
        assertTrue(b.finished());
        assertEquals(SuperpairsBoard.Outcome.IGNORED, b.click(2, new Random(1)));
    }

    @Test void sequenceGames() {
        SequenceGame g = new SequenceGame(SequenceGame.Mode.CHRONOMATRON, 3, 4, new Random(3));
        for (int round = 1; round <= 3; round++) {
            assertEquals(round, g.sequence.size());
            g.startInput();
            for (int k : List.copyOf(g.sequence)) assertTrue(g.input(k));
        }
        assertEquals(SequenceGame.Phase.WON, g.phase);
        assertEquals(3, g.roundsCleared);

        SequenceGame u = new SequenceGame(SequenceGame.Mode.ULTRASEQUENCER, 5, 28, new Random(4));
        assertEquals(3, u.sequence.size());
        u.startInput();
        int wrong = (u.sequence.get(0) + 1) % 28;
        while (u.sequence.contains(wrong) && u.sequence.indexOf(wrong) == 0) wrong = (wrong + 1) % 28;
        assertFalse(u.input(wrong));
        assertEquals(SequenceGame.Phase.LOST, u.phase);
        assertEquals(3, SequenceGame.bonusClicks(15, 15));
        assertEquals(1, SequenceGame.bonusClicks(5, 15));
        assertEquals(0, SequenceGame.bonusClicks(4, 15));
    }

    @Test void aliasesResolveAndNeverShadow() {
        assertFalse(reg.aliasMap().isEmpty());
        for (Map.Entry<String, String> a : reg.aliasMap().entrySet()) {
            assertNotNull(reg.spec(a.getKey()), a.getKey());
            assertEquals(a.getValue(), reg.spec(a.getKey()).id, a.getKey());
            assertTrue(reg.isAlias(a.getKey()));
            assertNull(reg.parseBook(EnchantRegistry.bookId(a.getKey(), 1)), "no book items under alias " + a.getKey());
        }
        for (EnchantSpec s : reg.all()) assertFalse(reg.isAlias(s.id), s.id + " is both an id and an alias");
        assertEquals("arcane", reg.canonical("woodsplitter"));
        assertEquals("ultimate_bank", reg.canonical("BANK"));
        assertEquals("ultimate_reiterate", reg.canonical("duplex"));
        assertEquals("aiming", reg.canonical("dragon_tracer"));
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("bank", 3); m.put("ultimate_bank", 5); m.put("sharpness", 4);
        PlayerEnchants.canonicalise(reg, m);
        assertEquals(Map.of("ultimate_bank", 5, "sharpness", 4), m);
    }

    /** The skills table names its Enchanting unlocks by enchant NAME; every one must resolve here. */
    @Test void everySkillUnlockEnchantResolves() throws Exception {
        Path p = Path.of("src/main/resources/data/skills/skills.json");
        if (!Files.exists(p)) p = Path.of("plugin/src/main/resources/data/skills/skills.json");
        if (!Files.exists(p)) return;   // skills module absent: nothing to resolve
        com.google.gson.JsonObject root;
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            root = com.google.gson.JsonParser.parseReader(r).getAsJsonObject();
        }
        com.google.gson.JsonElement skills = root.get("skills");
        List<String> missing = new java.util.ArrayList<>();
        int checked = 0;
        Iterable<com.google.gson.JsonElement> all = skills.isJsonArray() ? skills.getAsJsonArray()
                : skills.getAsJsonObject().entrySet().stream().map(Map.Entry::getValue).toList();
        for (com.google.gson.JsonElement sk : all) {
            if (!sk.isJsonObject() || !sk.getAsJsonObject().has("levels")) continue;
            for (com.google.gson.JsonElement lv : sk.getAsJsonObject().getAsJsonArray("levels")) {
                if (!lv.getAsJsonObject().has("unlocks")) continue;
                for (com.google.gson.JsonElement u : lv.getAsJsonObject().getAsJsonArray("unlocks")) {
                    com.google.gson.JsonObject o = u.getAsJsonObject();
                    if (!o.has("type") || !"enchant".equals(o.get("type").getAsString()) || !o.has("id") || o.get("id").isJsonNull()) continue;
                    checked++;
                    String id = o.get("id").getAsString();
                    if (reg.spec(id) == null) missing.add(id);
                }
            }
        }
        assertTrue(checked > 0, "no enchant unlocks found in skills.json");
        assertTrue(missing.isEmpty(), missing.size() + " skill unlock enchant ids unknown: " + missing);
    }
}
