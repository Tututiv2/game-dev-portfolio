package com.skyclone.mod.alchemy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the SHIPPING alchemy table (data/alchemy/effects.json) through the real brewing rules. */
class AlchemyTableTest {

    static EffectTable t;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = AlchemyTableTest.class.getResourceAsStream("/data/alchemy/effects.json")) {
            assertNotNull(in, "data/alchemy/effects.json on the classpath");
            t = EffectTable.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test void tableIsSelfConsistent() { assertNull(t.validate()); }

    @Test void researchNumbers() { assertNull(AlchemySelfTests.researchNumbers(t)); }

    @Test void brewChain() { assertNull(AlchemySelfTests.brewChain(t)); }

    @Test void basicBrews() { assertNull(AlchemySelfTests.basicBrews(t)); }

    @Test void specialPotions() { assertNull(AlchemySelfTests.specialPotions(t)); }

    @Test void standBookkeeping() { assertNull(AlchemySelfTests.standBookkeeping()); }

    @Test void everyEffectHasAnItemAndOnlyStandardIdsAreGenerated() throws Exception {
        // every effect level points at an item alchemy registers (special ones through potionItem)
        try (InputStream in = AlchemyTableTest.class.getResourceAsStream("/data/alchemy/items.json")) {
            assertNotNull(in);
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (com.google.gson.JsonElement el : com.google.gson.JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray())
                ids.add(el.getAsJsonObject().get("id").getAsString());
            for (EffectTable.Effect e : t.effects()) for (EffectTable.Level l : e.levels)
                assertTrue(ids.contains(e.itemId(l.level)), e.id + " " + l.level + " -> " + e.itemId(l.level));
            for (String id : AlchemySelfTests.SPECIAL_ITEMS) assertTrue(ids.contains(id), id);
        }
    }

    @Test void islandStandSurvivesEveryGridSlot() {
        // the same block of the same island, loaded into 50 different instance slots, is one stand
        String want = Stands.islandKey("private_island", 5, 64, 7, 1024);
        for (int sx = -5; sx <= 4; sx++) for (int sz = -2; sz <= 2; sz++)
            assertEquals(want, Stands.islandKey("private_island", sx * 1024L + 5, 64, sz * 1024L + 7, 1024));
    }

    @Test void xpIsPaidExactlyOncePerPotion() {
        // random take/put/swap sequences never pay more than was stored, and pay all of it once emptied
        java.util.Random r = new java.util.Random(7);
        for (int run = 0; run < 2000; run++) {
            AlchemyData.Stand s = new AlchemyData.Stand();
            Stands.normalise(s);
            double stored = 0, paid = 0;
            String[] cur = new String[3];
            for (int step = 0; step < 12; step++) {
                int i = r.nextInt(3);
                switch (r.nextInt(3)) {
                    case 0 -> { String sig = "p" + r.nextInt(4); double x = 1 + r.nextInt(100); Stands.store(s, i, x, sig); cur[i] = sig; stored += x; }
                    case 1 -> cur[i] = null;
                    default -> cur[i] = "q" + r.nextInt(4);
                }
                paid += Stands.collect(s, cur.clone());
                assertTrue(paid <= stored + 1e-9);
            }
            paid += Stands.collect(s, new String[3]);
            assertEquals(stored, paid, 1e-9);
        }
    }

    @Test void recipeBookMatchesTheBrewingRules() throws Exception {
        // every recipe the generator lists (python mirror of appliesTo) must really brew at the stand, and vice versa
        try (InputStream in = AlchemyTableTest.class.getResourceAsStream("/data/alchemy/recipes.json")) {
            assertNotNull(in);
            com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
            int brewRecipes = 0;
            for (com.google.gson.JsonElement el : arr) {
                com.google.gson.JsonObject r = el.getAsJsonObject();
                String base = r.getAsJsonArray("inputs").get(0).getAsString().split(":")[0];
                String ing = r.getAsJsonArray("inputs").get(1).getAsString();
                ing = ing.substring(0, ing.lastIndexOf(':'));
                Brew.Bottle b = switch (base) {
                    case "POTION" -> Brew.Bottle.water();
                    case "POTION_AWKWARD" -> Brew.Bottle.awkward();
                    default -> { brewRecipes++; yield Brew.Bottle.basicBrew(base); }
                };
                Brew.Result res = Brew.react(t, b, ing);
                assertNotNull(res, r.get("id").getAsString());
                String want = r.get("result").getAsString();
                String got = res.bottle().kind() == Brew.Kind.AWKWARD ? "POTION_AWKWARD"
                        : EffectTable.potionId(res.bottle().effect(), res.bottle().level(), false);
                assertEquals(want, got, r.get("id").getAsString());
            }
            int expected = 0;
            for (EffectTable.BasicBrew bb : t.brews()) for (EffectTable.Ingredient i : t.ingredients())
                if ("AWKWARD".equals(i.base) && Brew.react(t, Brew.Bottle.basicBrew(bb.item), i.item) != null) expected++;
            assertEquals(expected, brewRecipes, "one recipe-book entry per Basic Brew reaction");
        }
    }

    @Test void everyBasicBrewBrewsWithEveryIngredientItBoosts() {
        // A Basic Brew reacts exactly with the Awkward ingredients of the effects it boosts, and never loses itself.
        for (EffectTable.BasicBrew bb : t.brews()) {
            int made = 0;
            for (EffectTable.Ingredient i : t.ingredients()) {
                if (!"AWKWARD".equals(i.base)) continue;
                Brew.Result r = Brew.react(t, Brew.Bottle.basicBrew(bb.item), i.item);
                boolean should = bb.appliesTo(t.effect(i.effect));
                assertEquals(should, r != null, bb.item + " + " + i.item);
                if (r != null) { assertEquals(bb.item, r.bottle().brew()); made++; }
            }
            assertTrue(made > 0, bb.item + " brews nothing");
        }
    }

    @Test void everyIngredientBrewsSomething() {
        for (EffectTable.Ingredient i : t.ingredients()) {
            Brew.Bottle base = "WATER".equals(i.base) ? Brew.Bottle.water() : Brew.Bottle.awkward();
            Brew.Result r = Brew.react(t, base, i.item);
            assertNotNull(r, i.item);
            assertEquals(i.effect, r.bottle().effect(), i.item);
        }
    }

    @Test void everyBrewableLevelIsReachable() {
        // From the lowest brewable start, glowstone family + ingredients must reach every level up to brewMax.
        for (EffectTable.Effect e : t.effects()) {
            if (!e.brewable) continue;
            boolean[] seen = new boolean[e.brewMax + 1];
            for (EffectTable.Ingredient i : t.ingredientsFor(e.id)) {
                int base = Math.min(i.level, e.brewMax);
                seen[base] = true;
                for (int add = 1; add <= 3; add++) if (base + add <= e.brewMax) seen[base + add] = true;
            }
            for (String other : new String[]{"speed", "strength", "healing", "night_vision", "jump_boost", "poison"}) {
                EffectTable.Effect o = t.effect(other);
                if (o != null && e.id.equals(o.corruptsTo)) for (int L = 1; L <= e.brewMax; L++) seen[L] = true;
            }
            for (int L = 1; L <= e.brewMax; L++) {
                if (e.level(L) == null) continue;
                assertTrue(seen[L], e.id + " level " + L + " cannot be brewed");
            }
        }
    }

    @Test void godPotionAndBundles() {
        assertTrue(t.god().size() >= 20);
        assertEquals(7, t.bundles("dungeon").size());
        EffectTable.Bundle b7 = t.bundle("dungeon", 7);
        assertEquals(2400, b7.seconds);
        assertEquals(5, (int) b7.effects.get("strength"));
    }

    @Test void clockFormat() {
        assertEquals("3:00", EffectTable.clock(180));
        assertEquals("1:02:05", EffectTable.clock(3725));
        assertEquals("0:45", EffectTable.clock(45));
    }

    // ---- stand layout (playtest: a shift-clicked stack of 3 Water Bottles all went into the top slot) ----

    private static Brew.SlotKind[] k(Brew.SlotKind... ks) { return ks; }
    private static final Brew.SlotKind E = Brew.SlotKind.EMPTY, B = Brew.SlotKind.BOTTLE,
            I = Brew.SlotKind.INGREDIENT, O = Brew.SlotKind.OTHER;

    @Test void shiftClickedBottlesSpreadOnePerSlot() {
        Brew.Arrangement a = Brew.arrange(k(B, E, E, E), new int[]{3, 0, 0, 0});
        assertTrue(a.changed());
        assertEquals(-1, a.from()[0]);
        for (int t = 1; t <= 3; t++) { assertEquals(0, a.from()[t]); assertEquals(1, a.take()[t]); }
        assertEquals(0, a.back()[0]);
    }

    @Test void arrangeEdgeCases() {
        // 5 bottles: 3 placed, 2 back to the player
        Brew.Arrangement a = Brew.arrange(k(B, E, E, E), new int[]{5, 0, 0, 0});
        assertEquals(2, a.back()[0]);
        // ingredient already on top, a stack shift-clicked into the first bottle slot: spread
        a = Brew.arrange(k(I, B, E, E), new int[]{4, 3, 0, 0});
        assertEquals(0, a.from()[0]); assertEquals(4, a.take()[0]);
        assertEquals(1, a.from()[1]); assertEquals(1, a.from()[2]); assertEquals(1, a.from()[3]);
        assertEquals(0, a.back()[1]);
        // ingredient landed in a bottle slot with the top free: moves up
        a = Brew.arrange(k(E, I, B, E), new int[]{0, 2, 1, 0});
        assertEquals(1, a.from()[0]); assertEquals(2, a.take()[0]); assertEquals(-1, a.from()[1]); assertEquals(2, a.from()[2]);
        // a second ingredient / junk in a bottle slot goes back
        a = Brew.arrange(k(I, I, O, B), new int[]{1, 7, 1, 1});
        assertEquals(7, a.back()[1]); assertEquals(1, a.back()[2]); assertEquals(3, a.from()[3]);
        // a correct stand is left alone
        assertTrue(!Brew.arrange(k(I, B, B, B), new int[]{9, 1, 1, 1}).changed());
        assertTrue(!Brew.arrange(k(E, E, E, E), new int[]{0, 0, 0, 0}).changed());
        assertTrue(!Brew.arrange(k(I, E, B, E), new int[]{1, 0, 1, 0}).changed());
    }

    /** Every item ends up in exactly one place, for every combination: arrange can never dupe or delete. */
    @Test void arrangeConservesEveryItem() {
        Brew.SlotKind[] all = Brew.SlotKind.values();
        int[] amts = {1, 2, 3, 64};
        int combos = 0;
        for (int c = 0; c < Math.pow(all.length * amts.length, 4); c++) {
            Brew.SlotKind[] kind = new Brew.SlotKind[4];
            int[] amount = new int[4];
            int x = c;
            for (int s = 0; s < 4; s++) {
                int v = x % (all.length * amts.length); x /= all.length * amts.length;
                kind[s] = all[v / amts.length];
                amount[s] = kind[s] == Brew.SlotKind.EMPTY ? 0 : amts[v % amts.length];
            }
            Brew.Arrangement a = Brew.arrange(kind, amount);
            int[] used = new int[4];
            for (int t = 0; t < 4; t++) {
                if (a.from()[t] < 0) continue;
                assertTrue(a.take()[t] > 0);
                used[a.from()[t]] += a.take()[t];
                if (t > 0) { assertEquals(Brew.SlotKind.BOTTLE, kind[a.from()[t]]); assertEquals(1, a.take()[t]); }
                else assertTrue(kind[a.from()[0]] != Brew.SlotKind.BOTTLE);
            }
            for (int s = 0; s < 4; s++) assertEquals(amount[s], used[s] + a.back()[s], "slot " + s + " combo " + c);
            // idempotent: arranging the result changes nothing
            Brew.SlotKind[] k2 = new Brew.SlotKind[4];
            int[] a2 = new int[4];
            for (int t = 0; t < 4; t++) {
                k2[t] = a.from()[t] < 0 ? Brew.SlotKind.EMPTY : kind[a.from()[t]];
                a2[t] = a.from()[t] < 0 ? 0 : a.take()[t];
            }
            assertTrue(!Brew.arrange(k2, a2).changed(), "not idempotent, combo " + c);
            combos++;
        }
        assertTrue(combos > 1000);
    }
}
