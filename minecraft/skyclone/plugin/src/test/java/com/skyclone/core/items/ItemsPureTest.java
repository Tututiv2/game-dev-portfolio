package com.skyclone.core.items;

import com.google.gson.JsonPrimitive;
import com.skyclone.api.item.AbilityDef;
import com.skyclone.api.item.EnchantDef;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.ItemStatSheet;
import com.skyclone.api.item.LoreContributor;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.item.ReforgeDef;
import com.skyclone.api.stat.Stat;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Pure logic of the items core: codec, sheet math, lore text, recipe cells, vitals formulas, JSON loading. */
class ItemsPureTest {

    // ---- helpers ---------------------------------------------------------------------------

    static LoreRenderer.Env env(Map<String, EnchantDef> enchants, Map<String, ReforgeDef> reforges, List<LoreContributor> lore) {
        return new LoreRenderer.Env() {
            public EnchantDef enchant(String id) { return enchants.get(id); }
            public ReforgeDef reforge(String id) { return reforges.get(id); }
            public String describeRequirement(String key) { return "&aCombat Skill 15"; }
            public List<LoreContributor> loreContributors() { return lore; }
            public String displayName(ItemDef def) { return def.name; }
            public void contributorFailed(Object c, Throwable t) { fail("contributor threw " + t); }
        };
    }

    static LoreRenderer.Rendered render(ItemDef def, ItemData d) {
        Rarity r = SheetMath.effectiveRarity(def, d);
        ItemStatSheet sh = SheetMath.compute(def, d, r, id -> null, List.of(), null);
        return LoreRenderer.render(def, d, r, sh, env(Map.of(), Map.of(), List.of()), 3);
    }

    // ---- codec -----------------------------------------------------------------------------

    @Test void emptyDataWritesNothing() {
        assertEquals("", ItemDataCodec.write(new ItemData()));
    }

    @Test void codecRoundTripsAndIsDeterministic() {
        ItemData d = new ItemData();
        d.uuid = "u-1";
        d.reforge = "spicy";
        d.enchants.put("sharpness", 5);
        d.enchants.put("critical", 6);
        d.potatoBooks = 12;
        d.recombobulated = true;
        d.stars = 7;
        d.gems.put("1", "FINE_JASPER_GEM");
        d.gems.put("0", "FLAWED_RUBY_GEM");
        d.unlockedGemSlots.add("1");
        d.unlockedGemSlots.add("0");
        d.extra.add("kills", new JsonPrimitive(42));
        d.created = 1234L;
        String a = ItemDataCodec.write(d);
        ItemData back = ItemDataCodec.read(a);
        assertEquals(a, ItemDataCodec.write(back));
        assertEquals("spicy", back.reforge);
        assertEquals(6, back.enchant("critical"));
        assertEquals(12, back.potatoBooks);
        assertTrue(back.recombobulated);
        assertEquals(42, back.extra.get("kills").getAsInt());
        // enchant order is preserved (application order)
        assertEquals(List.of("sharpness", "critical"), new ArrayList<>(back.enchants.keySet()));
    }

    @Test void codecIsLenient() {
        assertNotNull(ItemDataCodec.read("not json"));
        assertNotNull(ItemDataCodec.read("[1,2]"));
        assertEquals(0, ItemDataCodec.read("{\"stars\":\"x\"}").stars);
    }

    // ---- sheet ------------------------------------------------------------------------------

    @Test void potatoBooksOnWeaponsAndArmor() {
        ItemDef sw = ItemDef.of("S", "S", "DIAMOND_SWORD", Rarity.RARE, ItemCategory.SWORD);
        sw.damage = 35;
        ItemData d = new ItemData();
        d.potatoBooks = 15;
        ItemStatSheet sh = SheetMath.compute(sw, d, Rarity.RARE, id -> null, List.of(), null);
        assertEquals(65, sh.damage());
        assertEquals(30, sh.damageBonus());
        assertEquals(30, sh.total(Stat.STRENGTH));
        ItemDef ch = ItemDef.of("C", "C", "DIAMOND_CHESTPLATE", Rarity.RARE, ItemCategory.CHESTPLATE);
        sh = SheetMath.compute(ch, d, Rarity.RARE, id -> null, List.of(), null);
        assertEquals(60, sh.total(Stat.HEALTH));
        assertEquals(30, sh.total(Stat.DEFENSE));
        ItemDef acc = ItemDef.of("A", "A", "PLAYER_HEAD", Rarity.RARE, ItemCategory.ACCESSORY);
        sh = SheetMath.compute(acc, d, Rarity.RARE, id -> null, List.of(), null);
        assertEquals(0, sh.total(Stat.HEALTH));
    }

    @Test void reforgeUsesEffectiveRarityRow() {
        ReforgeDef rf = new ReforgeDef();
        rf.id = "spicy"; rf.name = "Spicy";
        Map<Stat, Double> leg = new EnumMap<>(Stat.class); leg.put(Stat.STRENGTH, 10.0);
        Map<Stat, Double> myth = new EnumMap<>(Stat.class); myth.put(Stat.STRENGTH, 15.0);
        rf.stats.put(Rarity.LEGENDARY, leg);
        rf.stats.put(Rarity.MYTHIC, myth);
        ItemDef sw = ItemDef.of("S", "S", "DIAMOND_SWORD", Rarity.LEGENDARY, ItemCategory.SWORD);
        ItemData d = new ItemData();
        d.reforge = "spicy";
        d.recombobulated = true;
        Rarity r = SheetMath.effectiveRarity(sw, d);
        assertEquals(Rarity.MYTHIC, r);
        ItemStatSheet sh = SheetMath.compute(sw, d, r, id -> rf, List.of(), null);
        assertEquals(15, sh.part(Stat.STRENGTH, ItemStatSheet.Source.REFORGE));
    }

    @Test void throwingContributorIsSkipped() {
        ItemDef sw = ItemDef.of("S", "S", "DIAMOND_SWORD", Rarity.RARE, ItemCategory.SWORD);
        int[] errors = {0};
        ItemStatSheet sh = SheetMath.compute(sw, new ItemData(), Rarity.RARE, id -> null,
                List.of((def, data, rar, s) -> { throw new IllegalStateException("x"); },
                        (def, data, rar, s) -> s.add(Stat.DEFENSE, 5, ItemStatSheet.Source.ENCHANT)),
                (c, t) -> errors[0]++);
        assertEquals(1, errors[0]);
        assertEquals(5, sh.total(Stat.DEFENSE));
    }

    // ---- lore -------------------------------------------------------------------------------

    @Test void swordLoreMatchesGenre() {
        ItemDef sw = ItemDef.of("WOOD_SWORD", "Wooden Sword", "WOODEN_SWORD", Rarity.COMMON, ItemCategory.SWORD);
        sw.damage = 20;
        LoreRenderer.Rendered r = render(sw, new ItemData());
        assertEquals("&fWooden Sword", r.name());
        assertEquals(List.of("&7Damage: &c+20", "", "&8This item can be reforged!", "&f&lCOMMON SWORD"), r.lore());
    }

    @Test void materialHasOnlyFooter() {
        ItemDef w = ItemDef.of("WHEAT", "Wheat", "WHEAT", Rarity.COMMON, ItemCategory.MATERIAL);
        assertEquals(List.of("&f&lCOMMON"), render(w, new ItemData()).lore());
    }

    @Test void recombFooterAndStars() {
        ItemDef sw = ItemDef.of("H", "Blade", "IRON_SWORD", Rarity.LEGENDARY, ItemCategory.SWORD);
        sw.dungeon = true;
        ItemData d = new ItemData();
        d.recombobulated = true;
        d.stars = 10;
        LoreRenderer.Rendered r = render(sw, d);
        assertEquals("&dBlade &6✪✪✪✪✪&c➎", r.name());
        assertEquals("&d&l&ka&r &d&lMYTHIC DUNGEON SWORD &d&l&ka", r.lore().get(r.lore().size() - 1));
        assertEquals(" &6✪✪✪", LoreRenderer.stars(3));
        assertEquals("", LoreRenderer.stars(0));
    }

    @Test void statPartsAreColoured() {
        ItemStatSheet sh = new ItemStatSheet();
        sh.add(Stat.STRENGTH, 30, ItemStatSheet.Source.BASE);
        sh.add(Stat.STRENGTH, 20, ItemStatSheet.Source.POTATO);
        sh.add(Stat.STRENGTH, 15, ItemStatSheet.Source.REFORGE);
        sh.add(Stat.STRENGTH, 10, ItemStatSheet.Source.GEMSTONE);
        assertEquals("&7Strength: &c+75 &e(+20) &9(+15) &d[+10]", LoreRenderer.statLine(Stat.STRENGTH, sh));
        sh.add(Stat.CRIT_CHANCE, 10, ItemStatSheet.Source.BASE);
        assertEquals("&7Crit Chance: &c+10%", LoreRenderer.statLine(Stat.CRIT_CHANCE, sh));
        sh.add(Stat.INTELLIGENCE, -5, ItemStatSheet.Source.REFORGE);
        assertEquals("&7Intelligence: &a-5 &9(-5)", LoreRenderer.statLine(Stat.INTELLIGENCE, sh));
        sh.add(Stat.MINING_FORTUNE, 2.5, ItemStatSheet.Source.BASE);
        assertEquals("&7Mining Fortune: &a+2.5", LoreRenderer.statLine(Stat.MINING_FORTUNE, sh));
    }

    @Test void abilityBlockAndRequirements() {
        ItemDef it = ItemDef.of("AOTE", "Blade of Ends", "DIAMOND_SWORD", Rarity.RARE, ItemCategory.SWORD);
        it.damage = 100;
        it.ability(new AbilityDef("instant_transmission", "Short Hop", AbilityDef.Trigger.RIGHT_CLICK, 50, 0,
                List.of("&7Teleport &a8 blocks &7ahead.")));
        it.requirements.add("skill:combat:15");
        it.tags.add("soulbound");
        List<String> l = render(it, new ItemData()).lore();
        int i = l.indexOf("&6Ability: Short Hop &e&lRIGHT CLICK");
        assertTrue(i > 0, l.toString());
        assertEquals("&7Teleport &a8 blocks &7ahead.", l.get(i + 1));
        assertEquals("&8Mana Cost: &350", l.get(i + 2));
        assertTrue(l.contains("&4❣ &cRequires &aCombat Skill 15&c."));
        assertTrue(l.contains("&8&l* &8Co-op Soulbound &8&l*"));
        // no double blanks, no trailing blank
        for (int k = 1; k < l.size(); k++) assertFalse(l.get(k).isEmpty() && l.get(k - 1).isEmpty(), l.toString());
        assertFalse(l.get(l.size() - 1).isEmpty());
    }

    @Test void fractionalManaCost() {
        ItemDef it = ItemDef.of("W", "Wand", "STICK", Rarity.RARE, ItemCategory.WAND);
        it.ability(new AbilityDef("x", "Zap", AbilityDef.Trigger.RIGHT_CLICK, 0.1, 2, List.of()));
        List<String> l = render(it, new ItemData()).lore();
        assertTrue(l.contains("&8Mana Cost: &310% &8of max"), l.toString());
        assertTrue(l.contains("&8Cooldown: &a2s"), l.toString());
        assertEquals(50, VitalsMath.manaCost(0.1, 500), 1e-9);
        assertEquals(75, VitalsMath.manaCost(75, 500), 1e-9);
    }

    @Test void enchantBlockDetailedAndCompact() {
        EnchantDef sharp = new EnchantDef();
        sharp.id = "sharpness"; sharp.name = "Sharpness"; sharp.values = new double[]{5, 10, 15, 20, 30};
        sharp.description = "&7Increases melee damage dealt by &a%s%&7.";
        EnchantDef ult = new EnchantDef();
        ult.id = "chimera"; ult.name = "Chimera"; ult.ultimate = true;
        ItemDef sw = ItemDef.of("S", "S", "DIAMOND_SWORD", Rarity.RARE, ItemCategory.SWORD);
        ItemData d = new ItemData();
        d.enchants.put("sharpness", 5);
        d.enchants.put("chimera", 1);
        Rarity r = Rarity.RARE;
        ItemStatSheet sh = SheetMath.compute(sw, d, r, id -> null, List.of(), null);
        List<String> l = LoreRenderer.render(sw, d, r, sh, env(Map.of("sharpness", sharp, "chimera", ult), Map.of(), List.of()), 3).lore();
        assertEquals("&d&lChimera I", l.get(0));
        assertEquals("&9Sharpness V", l.get(1));
        assertEquals("&7Increases melee damage dealt by &a30%&7.", l.get(2));
        d.enchants.put("critical", 6);
        d.enchants.put("cubism", 5);
        l = LoreRenderer.render(sw, d, r, sh, env(Map.of("sharpness", sharp, "chimera", ult), Map.of(), List.of()), 3).lore();
        assertEquals("&d&lChimera I&9, &9Sharpness V&9, &9Critical VI", l.get(0));
        assertEquals("&9Cubism V", l.get(1));
    }

    @Test void gemRow() {
        ItemDef sw = ItemDef.of("S", "S", "DIAMOND_SWORD", Rarity.RARE, ItemCategory.SWORD);
        sw.gemSlots.add("JASPER");
        sw.gemSlots.add("COMBAT");
        ItemData d = new ItemData();
        d.gems.put("0", "FINE_JASPER_GEM");
        assertEquals(" &9[&d❁&9] &8[⚔&8]", LoreRenderer.gemRow(sw, d));
        d.unlockedGemSlots.add("1");
        assertEquals(" &9[&d❁&9] &7[&8⚔&7]", LoreRenderer.gemRow(sw, d));
    }

    @Test void contributorsLandInTheirSections() {
        ItemDef it = ItemDef.of("P", "Pick", "DIAMOND_PICKAXE", Rarity.RARE, ItemCategory.PICKAXE);
        it.stat(Stat.MINING_SPEED, 220).stat(Stat.BREAKING_POWER, 4);
        it.lore("&7A good pick.");
        LoreContributor after = new LoreContributor() {
            public Section section() { return Section.AFTER_STATS; }
            public List<String> lines(ItemDef d, ItemData x) { return List.of("&7Fuel: &a100"); }
        };
        LoreContributor foot = new LoreContributor() {
            public Section section() { return Section.BEFORE_FOOTER; }
            public List<String> lines(ItemDef d, ItemData x) { return List.of("&7Blocks: &a5"); }
        };
        Rarity r = Rarity.RARE;
        ItemStatSheet sh = SheetMath.compute(it, new ItemData(), r, id -> null, List.of(), null);
        List<String> l = LoreRenderer.render(it, new ItemData(), r, sh, env(Map.of(), Map.of(), List.of(after, foot)), 3).lore();
        assertEquals(List.of("&8Breaking Power 4", "&7Mining Speed: &a+220", "&7Fuel: &a100", "", "&7A good pick.", "",
                "&7Blocks: &a5", "", "&8This item can be reforged!", "&9&lRARE PICKAXE"), l);
    }

    // ---- recipes ----------------------------------------------------------------------------

    @Test void recipeCells() {
        java.util.Set<String> known = java.util.Set.of("INK_SACK:3", "INK_SACK", "ENCHANTED_DIAMOND");
        assertEquals(new RecipeCells.Cell("ENCHANTED_DIAMOND", 32), RecipeCells.parse("ENCHANTED_DIAMOND:32", known::contains));
        assertEquals(new RecipeCells.Cell("INK_SACK:3", 1), RecipeCells.parse("INK_SACK:3", known::contains));
        assertEquals(new RecipeCells.Cell("INK_SACK:3", 16), RecipeCells.parse("INK_SACK:3:16", known::contains));
        assertEquals(new RecipeCells.Cell("COINS", 1000), RecipeCells.parse("COINS:1000", known::contains));
        assertEquals(new RecipeCells.Cell("DIAMOND", 1), RecipeCells.parse(" DIAMOND ", known::contains));
        assertNull(RecipeCells.parse("", known::contains));
        Recipe r = new Recipe();
        r.result = "X";
        r.grid = new String[]{"A:2", null, "A:3", "", "B", null, null, null, null};
        r.inputs = List.of("COINS:5");
        assertEquals(Map.of("A", 5, "B", 1, "COINS", 5), RecipeCells.ingredients(r, s -> false));
    }

    @Test void recipeJsonLenient() {
        String json = "[{\"result\":\"A\",\"grid\":[\"B:2\"]},{\"result\":\"\"},{\"result\":\"C\",\"type\":\"forge\",\"inputs\":[\"D:1\"],\"durationSeconds\":60},5]";
        List<Recipe> ok = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        DefJson.readRecipes(new StringReader(json), new DefJson.EntrySink<>() {
            public void ok(Recipe v) { ok.add(v); }
            public void bad(String id, String reason) { bad.add(id + ":" + reason); }
        });
        assertEquals(2, ok.size());
        assertEquals(2, bad.size());
        assertEquals(9, ok.get(0).grid.length);
        assertEquals(Recipe.Type.FORGE, ok.get(1).type);
    }

    // ---- item json --------------------------------------------------------------------------

    @Test void itemJsonLenient() {
        String json = "[{\"id\":\"DIAMOND_SWORD\",\"material\":\"DIAMOND_SWORD\",\"rarity\":\"uncommon\",\"category\":\"sword\","
                + "\"stats\":{\"DAMAGE\":35,\"strength\":10,\"NOT_A_STAT\":3,\"walk_speed\":5}},"
                + "{\"name\":\"no id\"},"
                + "{\"id\":\"HOE_X\",\"category\":\"FARMING_TOOL\",\"rarity\":\"very special\",\"abilities\":[{\"id\":\"a\",\"trigger\":\"sneak right click\"}]}]";
        List<ItemDef> ok = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        DefJson.readItems(new StringReader(json), new DefJson.EntrySink<>() {
            public void ok(ItemDef v) { ok.add(v); }
            public void bad(String id, String reason) { bad.add(reason); }
        });
        assertEquals(2, ok.size());
        assertEquals(1, bad.size());
        ItemDef sw = ok.get(0);
        assertEquals(35, sw.damage);
        assertEquals(10, sw.stats.get(Stat.STRENGTH));
        assertEquals(5, sw.stats.get(Stat.SPEED));
        assertFalse(sw.stats.containsKey(null));
        assertEquals(Rarity.UNCOMMON, sw.rarity);
        assertEquals(ItemCategory.SWORD, sw.category);
        assertTrue(sw.isUnique());
        ItemDef hoe = ok.get(1);
        assertEquals(ItemCategory.HOE, hoe.category);
        assertEquals(Rarity.VERY_SPECIAL, hoe.rarity);
        assertEquals("Hoe X", hoe.name);
        assertEquals(AbilityDef.Trigger.SNEAK_RIGHT_CLICK, hoe.abilities.get(0).trigger);
    }

    // ---- vitals -----------------------------------------------------------------------------

    @Test void vitalsFormulas() {
        assertEquals(2.5, VitalsMath.healthRegen(100, 100, 1), 1e-9);
        assertEquals(11.5, VitalsMath.healthRegen(1000, 100, 1), 1e-9);
        assertEquals(0, VitalsMath.healthRegen(1000, 0, 1), 1e-9);
        assertEquals(4, VitalsMath.manaRegenPerSecond(200, 0, 2), 1e-9);
        assertEquals(8, VitalsMath.manaRegenPerSecond(200, 100, 2), 1e-9);
        assertEquals(20, VitalsMath.heartScale(100, 40));
        assertEquals(22, VitalsMath.heartScale(125, 40));
        assertEquals(40, VitalsMath.heartScale(99999, 40));
        assertEquals(30, VitalsMath.heartScale(99999, 30));
        assertEquals(10, VitalsMath.gauge(50, 100, 20), 1e-9);
        assertEquals(0.5, VitalsMath.gauge(0, 100, 20), 1e-9);
        assertEquals(617, VitalsMath.deathLoss(1234.5, 0.5), 1e-9);
        // playtest "endgame": 12,495,341 coins -> "lost 6,247,670.5" before; whole coins now
        assertEquals(6_247_670, VitalsMath.deathLoss(12_495_341, 0.5), 1e-9);
        assertEquals(0, VitalsMath.deathLoss(0.9, 0.5), 1e-9);
        assertEquals(0, VitalsMath.deathLoss(Double.NaN, 0.5), 1e-9);
        assertEquals(0, VitalsMath.wholeCoins(Double.NaN), 1e-9);
        assertEquals(41, VitalsMath.wholeCoins(41.99), 1e-9);
        assertEquals("&c1,234/1,500❤     &a612❈ Defense     &b850/950✎ Mana",
                VitalsMath.actionBar(1234, 1500, 0, 612, 850, 950, null));
        assertEquals("&61,334/1,500❤     &b-50 Mana (&6Zap&b)     &b850/950✎ Mana",
                VitalsMath.actionBar(1234, 1500, 100, 612, 850, 950, "&b-50 Mana (&6Zap&b)"));
    }

    @Test void abilityLookupFallsBackFromSneak() {
        ItemDef it = ItemDef.of("I", "I", "STICK", Rarity.RARE, ItemCategory.WAND);
        AbilityDef rc = new AbilityDef("rc", "RC", AbilityDef.Trigger.RIGHT_CLICK, 0, 0, List.of());
        it.ability(rc);
        assertSame(rc, AbilityServiceImpl.find(it, AbilityDef.Trigger.SNEAK_RIGHT_CLICK));
        assertNull(AbilityServiceImpl.find(it, AbilityDef.Trigger.LEFT_CLICK));
        AbilityDef src = new AbilityDef("src", "SRC", AbilityDef.Trigger.SNEAK_RIGHT_CLICK, 0, 0, List.of());
        it.ability(src);
        assertSame(src, AbilityServiceImpl.find(it, AbilityDef.Trigger.SNEAK_RIGHT_CLICK));
    }

    /** rift Request 5: a provider can replace a stat's final value for the players it applies to. */
    @Test void statOverrideReplacesFinalValue() {
        com.skyclone.api.stat.StatMap main = new com.skyclone.api.stat.StatMap()
                .add(Stat.INTELLIGENCE, 500).addPercent(Stat.INTELLIGENCE, 20).multiply(Stat.INTELLIGENCE, 2);
        assertEquals(1200, main.total(Stat.INTELLIGENCE), 1e-9);
        assertNull(main.override(Stat.INTELLIGENCE));
        com.skyclone.api.stat.StatMap rift = new com.skyclone.api.stat.StatMap().set(Stat.INTELLIGENCE, 30).set(Stat.SPEED, 140);
        assertFalse(rift.isEmpty());
        com.skyclone.api.stat.StatMap total = new com.skyclone.api.stat.StatMap().addAll(main).addAll(rift);
        assertEquals(30, total.total(Stat.INTELLIGENCE), 1e-9);
        assertEquals(140, total.total(Stat.SPEED), 1e-9);
        assertEquals(40, total.bonus(Stat.SPEED), 1e-9);            // override minus base 100
        assertEquals(100, total.total(Stat.HEALTH), 1e-9);          // untouched stats compute normally
        total.addAll(new com.skyclone.api.stat.StatMap().set(Stat.INTELLIGENCE, 7));
        assertEquals(7, total.total(Stat.INTELLIGENCE), 1e-9);      // a later provider wins
        total.set(Stat.SPEED, Double.NaN);                           // garbage is ignored
        assertEquals(140, total.total(Stat.SPEED), 1e-9);
        total.unset(Stat.INTELLIGENCE);
        assertEquals(1200, total.total(Stat.INTELLIGENCE), 1e-9);   // back to the computed value
    }
}
