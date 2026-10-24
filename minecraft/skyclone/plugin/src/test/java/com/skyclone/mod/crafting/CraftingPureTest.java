package com.skyclone.mod.crafting;

import org.bukkit.block.structure.StructureRotation;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The crafting engine's pure parts: matching, planning, parsing, Carpentry XP, the raw tree, rotation. */
class CraftingPureTest {

    private static Pattern.Ing ing(String id, int n) { return Pattern.Ing.of(id, n); }

    private static Pattern.Ing any(int n, String... ids) {
        return new Pattern.Ing(new LinkedHashSet<>(List.of(ids)), n, ids[0]);
    }

    /** Enchanted Diamond: a plus of 5 x 32 Diamonds (research/02 §5.2: 160 -> 1). */
    private static Pattern enchantedDiamond() {
        Pattern.Ing d = ing("DIAMOND", 32);
        return Pattern.fromGrid("ed", "ENCHANTED_DIAMOND", 1, false,
                new Pattern.Ing[]{null, d, null, d, d, d, null, d, null}, 0, "collection:DIAMOND:4", false);
    }

    private static String[] ids(String... nine) {
        String[] out = new String[9];
        for (int i = 0; i < 9; i++) out[i] = nine[i] == null || nine[i].equals("-") ? null : nine[i];
        return out;
    }

    @Test
    void plusOf32Matches160Rule() {
        Pattern p = enchantedDiamond();
        int total = p.totals().values().iterator().next();
        assertEquals(160, total);
        String[] g = ids("-", "DIAMOND", "-", "DIAMOND", "DIAMOND", "DIAMOND", "-", "DIAMOND", "-");
        Matcher.Match m = Matcher.match(p, g, new int[]{0, 64, 0, 64, 64, 64, 0, 64, 0}, true);
        assertNotNull(m);
        assertEquals(2, m.maxTimes());
        assertArrayEquals(new int[]{0, 32, 0, 32, 32, 32, 0, 32, 0}, m.consume());
    }

    @Test
    void tooFewInOneSlotFails() {
        Pattern p = enchantedDiamond();
        String[] g = ids("-", "DIAMOND", "-", "DIAMOND", "DIAMOND", "DIAMOND", "-", "DIAMOND", "-");
        assertNull(Matcher.match(p, g, new int[]{0, 64, 0, 64, 31, 64, 0, 64, 0}, true));
    }

    @Test
    void extraItemFailsShaped() {
        Pattern p = enchantedDiamond();
        String[] g = ids("DIRT", "DIAMOND", "-", "DIAMOND", "DIAMOND", "DIAMOND", "-", "DIAMOND", "-");
        assertNull(Matcher.match(p, g, new int[]{1, 64, 0, 64, 64, 64, 0, 64, 0}, true));
    }

    @Test
    void shapedMatchesAtAnyOffset() {
        Pattern stick = Pattern.fromGrid("stick", "STICK", 4, false,
                new Pattern.Ing[]{any(1, "OAK_PLANKS", "BIRCH_PLANKS"), null, null, any(1, "OAK_PLANKS", "BIRCH_PLANKS"), null, null, null, null, null},
                0, null, true);
        assertEquals(1, stick.w);
        assertEquals(2, stick.h);
        // bottom-right column, mixed plank types (a tag)
        String[] g = ids("-", "-", "-", "-", "-", "OAK_PLANKS", "-", "-", "BIRCH_PLANKS");
        Matcher.Match m = Matcher.match(stick, g, new int[]{0, 0, 0, 0, 0, 7, 0, 0, 2}, false);
        assertNotNull(m);
        assertEquals(2, m.maxTimes());
        // horizontal is a different shape
        assertNull(Matcher.match(stick, ids("OAK_PLANKS", "OAK_PLANKS", "-", "-", "-", "-", "-", "-", "-"),
                new int[]{1, 1, 0, 0, 0, 0, 0, 0, 0}, true));
    }

    @Test
    void mirroredShapes() {
        // an L: X. / XX  -- mirrored is .X / XX
        Pattern.Ing x = ing("IRON_INGOT", 1);
        Pattern l = Pattern.fromGrid("l", "THING", 1, false, new Pattern.Ing[]{x, null, null, x, x, null, null, null, null}, 0, null, false);
        String[] mirrored = ids("-", "IRON_INGOT", "-", "IRON_INGOT", "IRON_INGOT", "-", "-", "-", "-");
        int[] a = {0, 1, 0, 1, 1, 0, 0, 0, 0};
        assertNotNull(Matcher.match(l, mirrored, a, true));
        assertNull(Matcher.match(l, mirrored, a, false));
    }

    @Test
    void shapelessUsesAugmentingPaths() {
        // ingredient A accepts {X, Y}, ingredient B accepts only {X}. Grid holds X then Y: a greedy
        // first-fit would give X to A and then fail B; matching must reassign.
        Pattern p = Pattern.fromGrid("s", "R", 1, true,
                new Pattern.Ing[]{any(1, "X", "Y"), ing("X", 1), null, null, null, null, null, null, null}, 0, null, false);
        Matcher.Match m = Matcher.match(p, ids("X", "Y", "-", "-", "-", "-", "-", "-", "-"), new int[]{3, 5, 0, 0, 0, 0, 0, 0, 0}, true);
        assertNotNull(m);
        assertEquals(3, m.maxTimes());
        assertNull(Matcher.match(p, ids("Y", "Y", "-", "-", "-", "-", "-", "-", "-"), new int[]{1, 1, 0, 0, 0, 0, 0, 0, 0}, true));
        assertNull(Matcher.match(p, ids("X", "-", "-", "-", "-", "-", "-", "-", "-"), new int[]{1, 0, 0, 0, 0, 0, 0, 0, 0}, true));
    }

    @Test
    void planFromStockPrefersSpecificIngredients() {
        // needs 1 of {X,Y} and 2 of {X}; stock X=2, Y=1 -> exactly one craft, Y spent on the tag
        Pattern p = Pattern.fromGrid("s", "R", 1, true,
                new Pattern.Ing[]{any(1, "X", "Y"), ing("X", 2), null, null, null, null, null, null, null}, 0, null, false);
        Map<String, Integer> stock = new HashMap<>(Map.of("X", 2, "Y", 1));
        Map<String, Integer> plan = Matcher.plan(p, stock, 1);
        assertNotNull(plan);
        assertEquals(2, plan.get("X"));
        assertEquals(1, plan.get("Y"));
        assertEquals(1, Matcher.maxTimes(p, stock, 100));
        assertNull(Matcher.plan(p, stock, 2));
        assertEquals(0, Matcher.maxTimes(p, Map.of("X", 1), 100));
    }

    @Test
    void maxTimesIsCapped() {
        Pattern p = enchantedDiamond();
        assertEquals(10, Matcher.maxTimes(p, Map.of("DIAMOND", 1600), 100));
        assertEquals(4, Matcher.maxTimes(p, Map.of("DIAMOND", 1600), 4));
        assertEquals(0, Matcher.maxTimes(p, Map.of("DIAMOND", 159), 100));
    }

    @Test
    void cellParsing() {
        Set<String> known = Set.of("INK_SACK:3", "DIAMOND", "LOG_2:1");
        assertEquals(new CraftMath.Cell("ENCHANTED_DIAMOND", 32), CraftMath.parse("ENCHANTED_DIAMOND:32", known::contains));
        assertEquals(new CraftMath.Cell("INK_SACK:3", 1), CraftMath.parse("INK_SACK:3", known::contains));
        assertEquals(new CraftMath.Cell("INK_SACK:3", 16), CraftMath.parse("INK_SACK:3:16", known::contains));
        assertEquals(new CraftMath.Cell("DIAMOND", 1), CraftMath.parse("DIAMOND", known::contains));
        // with no registry, the last ':' is always the amount (how our own data is written)
        assertEquals(new CraftMath.Cell("LOG_2", 1), CraftMath.parse("LOG_2:1", null));
        assertEquals(new CraftMath.Cell("LOG_2:1", 1), CraftMath.parse("LOG_2:1", known::contains));
        assertNull(CraftMath.parse("  ", null));
    }

    @Test
    void carpentryIsThreePercentOfNpcValue() {
        // research/02 §2.4.8: 3% of the ingredients' summed NPC sell price
        assertEquals(38.4, CraftMath.carpentryXp(Map.of("DIAMOND", 160), id -> 8.0, 0.03), 1e-9);
        assertEquals(0.0, CraftMath.carpentryXp(Map.of("STICK", 4), id -> 0.0, 0.03), 1e-9);
        assertEquals(0.0, CraftMath.carpentryXp(Map.of("X", 4), id -> -5.0, 0.03), 1e-9);
    }

    @Test
    void rawTreeExpandsAndStopsOnCycles() {
        Pattern ed = enchantedDiamond();
        Pattern edb = Pattern.fromGrid("edb", "ENCHANTED_DIAMOND_BLOCK", 1, false,
                new Pattern.Ing[]{null, ing("ENCHANTED_DIAMOND", 32), null, ing("ENCHANTED_DIAMOND", 32), ing("ENCHANTED_DIAMOND", 32),
                        ing("ENCHANTED_DIAMOND", 32), null, ing("ENCHANTED_DIAMOND", 32), null}, 0, null, false);
        Pattern block = Pattern.fromGrid("blk", "DIAMOND_BLOCK", 1, true, new Pattern.Ing[]{ing("DIAMOND", 9), null, null, null, null, null, null, null, null}, 0, null, true);
        Pattern unblock = Pattern.fromGrid("unb", "DIAMOND", 9, true, new Pattern.Ing[]{ing("DIAMOND_BLOCK", 1), null, null, null, null, null, null, null, null}, 0, null, true);
        Map<String, Pattern> book = Map.of("ENCHANTED_DIAMOND", ed, "ENCHANTED_DIAMOND_BLOCK", edb, "DIAMOND_BLOCK", block, "DIAMOND", unblock);
        Map<String, Long> raw = CraftMath.raw("ENCHANTED_DIAMOND_BLOCK", 1, book::get, 3);
        // 160 enchanted diamonds -> 25,600 diamonds (research: "25,600 raw items per second-tier item"),
        // and the diamond <-> block cycle stops instead of looping
        assertEquals(1, raw.size(), raw.toString());
        assertTrue(raw.containsKey("DIAMOND") || raw.containsKey("DIAMOND_BLOCK"), raw.toString());
        Map<String, Long> two = CraftMath.raw("ENCHANTED_DIAMOND_BLOCK", 1, id -> id.equals("DIAMOND") || id.equals("DIAMOND_BLOCK") ? null : book.get(id), 6);
        assertEquals(Map.of("DIAMOND", 25_600L), two);
    }

    /** Wave 3: a recipe making 4 needed once in two branches is crafted once, not twice. */
    @Test
    void rawTreeSharesLeftovers() {
        Pattern sticks = Pattern.fromGrid("st", "STICK", 4, false, new Pattern.Ing[]{ing("PLANK", 1), null, null, ing("PLANK", 1), null, null, null, null, null}, 0, null, false);
        Pattern a = Pattern.fromGrid("a", "A", 1, true, new Pattern.Ing[]{ing("STICK", 1), ing("IRON", 1), null, null, null, null, null, null, null}, 0, null, false);
        Pattern b = Pattern.fromGrid("b", "B", 1, true, new Pattern.Ing[]{ing("STICK", 1), ing("GOLD", 1), null, null, null, null, null, null, null}, 0, null, false);
        Map<String, Pattern> book = Map.of("STICK", sticks, "A", a, "B", b);
        Map<String, Long> wants = new java.util.LinkedHashMap<>();
        wants.put("A", 1L);
        wants.put("B", 1L);
        Map<String, Long> raw = CraftMath.rawAll(wants, book::get, 6);
        assertEquals(2L, raw.get("PLANK"), raw.toString());     // one stick craft (2 planks -> 4 sticks), not two
        assertEquals(1L, raw.get("IRON"));
        assertEquals(1L, raw.get("GOLD"));
        assertEquals(Map.of("PLANK", 4L), CraftMath.raw("STICK", 5, book::get, 6));   // 5 sticks = 2 crafts
    }

    /** Wave 3: "did you mean" lists recipes using every grid item, closest first, one per result. */
    @Test
    void nearMissesRankByMissingKinds() {
        Pattern ed = enchantedDiamond();                                   // DIAMOND only
        Pattern sword = Pattern.fromGrid("sw", "DIAMOND_SWORD", 1, false,
                new Pattern.Ing[]{ing("DIAMOND", 1), null, null, ing("DIAMOND", 1), null, null, ing("STICK", 1), null, null}, 0, null, false);
        Pattern pick = Pattern.fromGrid("pk", "DIAMOND_PICKAXE", 1, false,
                new Pattern.Ing[]{ing("DIAMOND", 1), ing("DIAMOND", 1), ing("DIAMOND", 1), null, ing("STICK", 1), null, null, ing("STICK", 1), null}, 0, null, false);
        Pattern boat = Pattern.fromGrid("bt", "OAK_BOAT", 1, false,
                new Pattern.Ing[]{ing("PLANK", 1), null, ing("PLANK", 1), ing("PLANK", 1), ing("PLANK", 1), ing("PLANK", 1), null, null, null}, 0, null, false);
        List<CraftMath.NearMiss> one = CraftMath.nearMisses(Set.of("DIAMOND"), List.of(sword, pick, boat, ed), 5);
        assertEquals("ENCHANTED_DIAMOND", one.get(0).pattern().result);   // nothing missing: wrong amounts
        assertEquals(0, one.get(0).missingKinds());
        assertEquals(3, one.size());                                       // the boat never uses diamonds
        assertEquals(1, one.get(1).missingKinds());                        // sword/pickaxe still need sticks
        assertEquals("DIAMOND_SWORD", one.get(1).pattern().result);        // fewer cells first
        List<CraftMath.NearMiss> both = CraftMath.nearMisses(Set.of("DIAMOND", "STICK"), List.of(sword, pick, boat, ed), 5);
        assertEquals(2, both.size());
        assertEquals(0, both.get(0).missingKinds());
        assertTrue(CraftMath.nearMisses(Set.of(), List.of(sword), 5).isEmpty());
    }

    @Test
    void dropChanceReadsLikeTheReference() {
        assertEquals("always", Sources.chanceText(1.0));
        assertEquals("25%", Sources.chanceText(0.25));
        assertEquals("2.5%", Sources.chanceText(0.025));
        assertEquals("0.05%", Sources.chanceText(0.0005));
        assertEquals("<0.01%", Sources.chanceText(0.00001));
    }

    @Test
    void furnitureRotationTurnsClockwise() {
        // north (-z) turned clockwise is east (+x)
        assertArrayEquals(new int[]{1, 0}, Furniture.rotate(0, -1, StructureRotation.CLOCKWISE_90));
        assertArrayEquals(new int[]{0, 1}, Furniture.rotate(0, -1, StructureRotation.CLOCKWISE_180));
        assertArrayEquals(new int[]{-1, 0}, Furniture.rotate(0, -1, StructureRotation.COUNTERCLOCKWISE_90));
        assertArrayEquals(new int[]{2, -3}, Furniture.rotate(2, -3, StructureRotation.NONE));
        // four quarter turns are the identity
        int[] v = {2, -1};
        for (int i = 0; i < 4; i++) v = Furniture.rotate(v[0], v[1], StructureRotation.CLOCKWISE_90);
        assertArrayEquals(new int[]{2, -1}, v);
    }

    /** Block <-> ingot pairs and self-consuming recipes give no Carpentry XP; a one-way chain still does. */
    @Test
    void reversibleRecipesAreFound() {
        Pattern.Ing d = ing("DIAMOND", 1);
        Pattern block = Pattern.fromGrid("block", "DIAMOND_BLOCK", 1, false, new Pattern.Ing[]{d, d, d, d, d, d, d, d, d}, 0, null, false);
        Pattern unblock = Pattern.fromGrid("unblock", "DIAMOND", 9, true, new Pattern.Ing[]{ing("DIAMOND_BLOCK", 1), null, null, null, null, null, null, null, null}, 0, null, false);
        Pattern dye = Pattern.fromGrid("dye", "WOOL", 8, true, new Pattern.Ing[]{any(1, "WOOL", "RED_WOOL"), ing("RED_DYE", 1), null, null, null, null, null, null, null}, 0, null, false);
        Pattern ench = enchantedDiamond();
        java.util.Set<String> r = CraftMath.reversible(List.of(block, unblock, dye, ench));
        assertEquals(java.util.Set.of("block", "unblock", "dye"), r);
    }

    /** Wave 3: a longer loop A -> B -> C -> A pays no XP either; a chain hanging off the loop still does. */
    @Test
    void longerCyclesAreFound() {
        Pattern ab = Pattern.fromGrid("ab", "B", 1, true, new Pattern.Ing[]{ing("A", 4), null, null, null, null, null, null, null, null}, 0, null, false);
        Pattern bc = Pattern.fromGrid("bc", "C", 2, true, new Pattern.Ing[]{ing("B", 1), null, null, null, null, null, null, null, null}, 0, null, false);
        Pattern ca = Pattern.fromGrid("ca", "A", 2, true, new Pattern.Ing[]{ing("C", 1), null, null, null, null, null, null, null, null}, 0, null, false);
        Pattern out = Pattern.fromGrid("cd", "D", 1, true, new Pattern.Ing[]{ing("C", 3), ing("E", 1), null, null, null, null, null, null, null}, 0, null, false);
        assertEquals(java.util.Set.of("ab", "bc", "ca"), CraftMath.reversible(List.of(ab, bc, ca, out)));
        java.util.Map<String, Integer> comp = CraftMath.components(java.util.Map.of("A", java.util.Set.of("B"), "B", java.util.Set.of("C"),
                "C", java.util.Set.of("A", "D")));
        assertEquals(comp.get("A"), comp.get("C"));
        assertNotEquals(comp.get("A"), comp.get("D"));
    }

    /** Supercraft prefers the most-upgraded copy of a unique ingredient for an upgrade, the plainest otherwise. */
    @Test
    void upgradeWorthCountsEveryUpgrade() {
        com.skyclone.api.item.ItemData plain = new com.skyclone.api.item.ItemData();
        com.skyclone.api.item.ItemData rich = new com.skyclone.api.item.ItemData();
        rich.enchants.put("sharpness", 5);
        rich.reforge = "sharp";
        rich.stars = 3;
        rich.potatoBooks = 10;
        assertEquals(0, CraftMath.upgradeWorth(plain));
        assertTrue(CraftMath.upgradeWorth(rich) > CraftMath.upgradeWorth(plain));
        assertEquals(0, CraftMath.upgradeWorth(null));
    }

    /** research/05: trades share the NPC 640/day limit, x10 under the Shopping Spree perk. */
    @Test
    void tradeLimitWithSpree() {
        assertEquals(640, CraftMath.tradeLimit(640, false, 10));
        assertEquals(6400, CraftMath.tradeLimit(640, true, 10));
        assertEquals(640, CraftMath.tradeLimit(640, true, 0));
        assertEquals(0, CraftMath.tradeLimit(-5, false, 10));
    }

    /**
     * Wave-3 playtest: 88 Oak Logs + a Wooden Axe, and the Oak Minion I never showed in Craftable Now — it has no
     * sell price, so a value-only sort put it behind every plank, slab and fence the same logs make (last page).
     */
    @Test
    void craftableNowPutsUnsellableMinionFirst() {
        Pattern.Ing log = ing("LOG", 10);
        Pattern minion = Pattern.fromGrid("minions:OAK_GENERATOR_1", "OAK_GENERATOR_1", 1, false,
                new Pattern.Ing[]{log, log, log, log, ing("WOODEN_AXE", 1), log, log, log, log}, 0, "collection:LOG:1", false);
        Pattern planks = Pattern.fromGrid("vanilla:oak_planks", "OAK_PLANKS", 4, true,
                new Pattern.Ing[]{ing("LOG", 1), null, null, null, null, null, null, null, null}, 0, null, true);
        Pattern wood = Pattern.fromGrid("vanilla:oak_wood", "OAK_WOOD", 3, false,
                new Pattern.Ing[]{ing("LOG", 1), ing("LOG", 1), null, ing("LOG", 1), ing("LOG", 1), null, null, null, null}, 0, null, true);
        Pattern cheapSb = Pattern.fromGrid("sb:cheap", "CHEAP_THING", 1, true,
                new Pattern.Ing[]{ing("LOG", 1), null, null, null, null, null, null, null, null}, 0, null, false);
        Map<String, Double> price = Map.of("LOG", 2.0, "OAK_PLANKS", 0.5, "OAK_WOOD", 2.0, "CHEAP_THING", 1.0);
        java.util.function.ToDoubleFunction<String> v = id -> price.getOrDefault(id, 0.0);

        // An unsellable result is worth what goes into it: 80 logs at 2 coins.
        assertEquals(160.0, CraftMath.craftWorth(minion, v), 1e-9);
        // The better of output (3 x 2 = 6) and input (4 logs x 2 = 8) value.
        assertEquals(8.0, CraftMath.craftWorth(wood, v), 1e-9);

        List<Pattern> list = new java.util.ArrayList<>(List.of(planks, wood, cheapSb, minion));
        CraftMath.sortCraftable(list, x -> x, v);
        assertEquals(List.of("OAK_GENERATOR_1", "CHEAP_THING", "OAK_WOOD", "OAK_PLANKS"),
                list.stream().map(x -> x.result).toList(), "SkyBlock first by worth, then vanilla by worth");
    }

    @Test
    void gridRoundTrip() {
        Pattern p = enchantedDiamond();
        Pattern.Ing[] g = p.grid9();
        assertNull(g[0]);
        assertNotNull(g[1]);
        assertEquals(5, p.cellCount());
    }
}
