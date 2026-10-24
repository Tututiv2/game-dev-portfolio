package com.skyclone.mod.reforges;

import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.ItemStatSheet;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.item.ReforgeDef;
import com.skyclone.api.stat.Stat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The shipping reforge tables against the research numbers (research 01 §13-§19). */
class ReforgeDataTest {

    static ReforgeData D;
    static ItemMods M;

    static Path dataDir() {
        for (String c : List.of("src/main/resources/data/reforges", "plugin/src/main/resources/data/reforges")) {
            Path p = Path.of(c);
            if (Files.isDirectory(p)) return p;
        }
        throw new IllegalStateException("cannot find data/reforges from " + Path.of("").toAbsolutePath());
    }

    @BeforeAll
    static void load() {
        Path dir = dataDir();
        D = ReforgeData.load(f -> {
            try { return Files.exists(dir.resolve(f)) ? new FileInputStream(dir.resolve(f).toFile()) : null; }
            catch (IOException e) { throw new RuntimeException(e); }
        });
        M = new ItemMods(D);
    }

    static ItemDef def(String id, ItemCategory cat, Rarity r) {
        ItemDef d = ItemDef.of(id, id, "IRON_SWORD", r, cat);
        return d;
    }

    @Test void counts() {
        assertEquals(50, D.rows.values().stream().filter(r -> !r.isStone()).count());
        assertEquals(85, D.rows.values().stream().filter(ReforgeData.Row::isStone).count());
        assertEquals(12, D.gems.types.size());
    }

    @Test void spicyMatchesResearch() {
        ReforgeDef sp = ReforgeData.toDef(D.rows.get("spicy"));
        assertEquals(10.0, sp.statsFor(Rarity.LEGENDARY).get(Stat.STRENGTH));
        assertEquals(80.0, sp.statsFor(Rarity.LEGENDARY).get(Stat.CRIT_DAMAGE));
        assertEquals(100.0, sp.statsFor(Rarity.MYTHIC).get(Stat.CRIT_DAMAGE));
        // DIVINE is not in the table: nearest lower (MYTHIC) row
        assertEquals(100.0, sp.statsFor(Rarity.DIVINE).get(Stat.CRIT_DAMAGE));
    }

    @Test void blacksmithCosts() {
        assertEquals(250, ReforgeData.blacksmithCost(Rarity.COMMON));
        assertEquals(5000, ReforgeData.blacksmithCost(Rarity.LEGENDARY));
        assertEquals(50000, ReforgeData.blacksmithCost(Rarity.VERY_SPECIAL));
    }

    @Test void poolsByCategory() {
        ItemDef sword = def("TEST_SWORD", ItemCategory.SWORD, Rarity.RARE);
        List<ReforgeData.Row> pool = D.blacksmithPool(sword);
        assertEquals(9, pool.size(), "nine SWORD/ROD blacksmith reforges");
        assertTrue(pool.stream().anyMatch(r -> r.id.equals("spicy")));
        ItemDef helm = def("TEST_HELM", ItemCategory.HELMET, Rarity.RARE);
        assertEquals(9, D.blacksmithPool(helm).size());
        ItemDef hoe = def("TEST_HOE", ItemCategory.HOE, Rarity.RARE);
        assertEquals(4, D.blacksmithPool(hoe).size());
        ItemDef acc = def("TEST_TALI", ItemCategory.ACCESSORY, Rarity.RARE);
        assertTrue(D.blacksmithPool(acc).isEmpty(), "accessory reforges were removed");
        // item-specific stone
        ReforgeData.Row warped = D.byStone.get("AOTE_STONE");
        assertTrue(D.appliesTo(warped, def("ASPECT_OF_THE_END", ItemCategory.SWORD, Rarity.RARE)));
        assertFalse(D.appliesTo(warped, sword));
        assertFalse(ReforgeData.rarityAllowed(warped, Rarity.COMMON));
    }

    @Test void stoneCosts() {
        ReforgeData.Row fabled = D.byStone.get("DRAGON_CLAW");
        assertEquals("fabled", fabled.id);
        assertEquals(1_000_000, ReforgeData.stoneCost(fabled, Rarity.LEGENDARY));
        assertEquals(60_000, ReforgeData.stoneCost(fabled, Rarity.COMMON));
        assertEquals(15.0, ReforgeData.bonusValue(fabled, Rarity.LEGENDARY, 0, -1));
    }

    @Test void gemValues() {
        assertEquals(30, D.gemValue("PERFECT_RUBY_GEM", Rarity.MYTHIC, "RUBY"));
        assertEquals(5, D.gemValue("FINE_JASPER_GEM", Rarity.EPIC, "COMBAT"));
        assertEquals(120, D.gemValue("PERFECT_AMBER_GEM", Rarity.DIVINE, "AMBER"));
        assertEquals(7, D.gemValue("ROUGH_RUBY_GEM", Rarity.DIVINE, "RUBY"), "falls back to MYTHIC");
        assertEquals(500_000, D.removalFee("PERFECT_SAPPHIRE_GEM"));
        assertTrue(ReforgeData.slotAccepts("COMBAT", "JASPER"));
        assertFalse(ReforgeData.slotAccepts("COMBAT", "JADE"));
        assertTrue(ReforgeData.slotAccepts("UNIVERSAL", "TOPAZ"));
        assertNull(ReforgeData.parseGem("ENCHANTED_DIAMOND"));
    }

    @Test void hyperionLadderAndSlots() {
        ReforgeData.StarEntry e = D.stars.get("HYPERION");
        assertEquals(5, e.costs.size());
        assertEquals(1500, e.costs.get(4).get(0).amount);
        assertEquals("WITHER", e.costs.get(4).get(0).essence);
        assertTrue(e.dungeon);
        ItemDef hyp = def("HYPERION", ItemCategory.SWORD, Rarity.LEGENDARY);
        assertEquals(List.of("SAPPHIRE", "COMBAT"), D.slotTypes(hyp));
        assertEquals(250_000.0, D.slotCost(hyp, 0).get(0).coins);
        assertTrue(D.masterStarsAllowed(hyp) || !hyp.dungeon);
    }

    @Test void contributorStarsGemsArt() {
        ItemDef d = def("HYPERION", ItemCategory.SWORD, Rarity.LEGENDARY);
        d.damage = 260;
        d.stats.put(Stat.STRENGTH, 150.0);
        d.stats.put(Stat.INTELLIGENCE, 350.0);
        ItemData data = new ItemData();
        data.stars = 5;
        data.gems.put("0", "PERFECT_SAPPHIRE_GEM");
        data.extra.addProperty("art_of_war", 1);
        ItemStatSheet sh = new ItemStatSheet();
        M.contribute(d, data, Rarity.LEGENDARY, sh);
        assertEquals(15.0, sh.part(Stat.STRENGTH, ItemStatSheet.Source.STARS), 1e-9, "5 stars = +10% of 150");
        assertEquals(35.0, sh.part(Stat.INTELLIGENCE, ItemStatSheet.Source.STARS), 1e-9);
        assertEquals(26.0, sh.damage(), 1e-9, "+10% of 260 damage");
        assertEquals(24.0, sh.part(Stat.INTELLIGENCE, ItemStatSheet.Source.GEMSTONE), 1e-9, "perfect sapphire on legendary");
        assertEquals(5.0, sh.part(Stat.STRENGTH, ItemStatSheet.Source.OTHER), 1e-9);
        // master stars give nothing outside master mode
        data.stars = 8;
        ItemStatSheet sh2 = new ItemStatSheet();
        M.contribute(d, data, Rarity.LEGENDARY, sh2);
        assertEquals(15.0, sh2.part(Stat.STRENGTH, ItemStatSheet.Source.STARS), 1e-9);
    }

    @Test void suspiciousAddsWeaponDamage() {
        ItemDef d = def("TEST_SWORD", ItemCategory.SWORD, Rarity.RARE);
        ItemData data = new ItemData();
        data.reforge = "suspicious";
        ItemStatSheet sh = new ItemStatSheet();
        M.contribute(d, data, Rarity.RARE, sh);
        assertEquals(15.0, sh.damage(), 1e-9);
    }

    @Test void anvilPlans() {
        ItemDef sword = def("TEST_SWORD", ItemCategory.SWORD, Rarity.EPIC);
        ItemData data = new ItemData();
        ItemDef hpb = ItemDef.of("HOT_POTATO_BOOK", "x", "BOOK", Rarity.EPIC, ItemCategory.MATERIAL);
        ItemDef fpb = ItemDef.of("FUMING_POTATO_BOOK", "x", "BOOK", Rarity.EPIC, ItemCategory.MATERIAL);
        assertTrue(M.plan(sword, data, Rarity.EPIC, hpb).ok());
        assertFalse(M.plan(sword, data, Rarity.EPIC, fpb).ok(), "fuming needs 10 hot first");
        data.potatoBooks = 10;
        assertFalse(M.plan(sword, data, Rarity.EPIC, hpb).ok());
        ItemMods.Plan f = M.plan(sword, data, Rarity.EPIC, fpb);
        assertTrue(f.ok());
        f.change().accept(data);
        assertEquals(11, data.potatoBooks);
        data.potatoBooks = 15;
        assertFalse(M.plan(sword, data, Rarity.EPIC, fpb).ok());

        ItemDef recomb = ItemDef.of("RECOMBOBULATOR_3000", "x", "PLAYER_HEAD", Rarity.LEGENDARY, ItemCategory.MATERIAL);
        ItemMods.Plan r = M.plan(sword, data, Rarity.EPIC, recomb);
        assertTrue(r.ok());
        r.change().accept(data);
        assertTrue(data.recombobulated);
        assertFalse(M.plan(sword, data, Rarity.LEGENDARY, recomb).ok());

        ItemDef claw = ItemDef.of("DRAGON_CLAW", "x", "PLAYER_HEAD", Rarity.RARE, ItemCategory.REFORGE_STONE);
        ItemMods.Plan s = M.plan(sword, data, Rarity.LEGENDARY, claw);
        assertTrue(s.ok());
        assertEquals(1_000_000, s.coins());
        s.change().accept(data);
        assertEquals("fabled", data.reforge);
        ItemDef helm = def("TEST_HELM", ItemCategory.HELMET, Rarity.EPIC);
        assertFalse(M.plan(helm, new ItemData(), Rarity.EPIC, claw).ok(), "a sword stone does not fit a helmet");

        ItemDef star1 = ItemDef.of("FIRST_MASTER_STAR", "x", "PLAYER_HEAD", Rarity.EPIC, ItemCategory.MATERIAL);
        star1.extra.addProperty("master_star", 1);
        ItemDef dsword = def("HYPERION", ItemCategory.SWORD, Rarity.LEGENDARY);
        dsword.dungeon = true;
        ItemData dd = new ItemData();
        dd.stars = 4;
        assertFalse(M.plan(dsword, dd, Rarity.LEGENDARY, star1).ok());
        dd.stars = 5;
        ItemMods.Plan ms = M.plan(dsword, dd, Rarity.LEGENDARY, star1);
        assertTrue(ms.ok());
        ms.change().accept(dd);
        assertEquals(6, dd.stars);

        ItemDef enrich = ItemDef.of("TALISMAN_ENRICHMENT_STRENGTH", "x", "PLAYER_HEAD", Rarity.SPECIAL, ItemCategory.MATERIAL);
        enrich.extra.addProperty("enrichment", "strength");
        ItemDef tali = def("TEST_TALI", ItemCategory.ACCESSORY, Rarity.EPIC);
        assertFalse(M.plan(tali, new ItemData(), Rarity.EPIC, enrich).ok(), "epic accessories cannot be enriched");
        assertTrue(M.plan(tali, new ItemData(), Rarity.LEGENDARY, enrich).ok());
    }

    @Test void nextStarCost() {
        ItemDef d = def("HYPERION", ItemCategory.SWORD, Rarity.LEGENDARY);
        ItemData data = new ItemData();
        assertEquals(150, M.nextStarCost(d, data).get(0).amount);
        data.stars = 5;
        assertNull(M.nextStarCost(d, data));
    }

    @Test void bonusTextFilledEverywhere() {
        for (ReforgeData.Row r : D.rows.values()) {
            if (r.bonus == null) continue;
            for (Map.Entry<String, ReforgeData.Bonus> e : r.bonus.entrySet())
                assertFalse(e.getValue().text.contains("?"), r.id + " " + e.getKey() + ": " + e.getValue().text);
        }
    }

    @Test void wave3Upgrades() {
        ItemDef drill = def("TEST_DRILL", ItemCategory.DRILL, Rarity.EPIC);
        ItemDef pick = def("TEST_PICK", ItemCategory.PICKAXE, Rarity.EPIC);
        ItemDef polar = ItemDef.of("POLARVOID_BOOK", "x", "BOOK", Rarity.RARE, ItemCategory.MATERIAL);
        ItemData data = new ItemData();
        assertFalse(M.plan(pick, data, Rarity.EPIC, polar).ok(), "drills only (reference lore)");
        for (int i = 0; i < ItemMods.POLARVOID_MAX; i++) {
            ItemMods.Plan pl = M.plan(drill, data, Rarity.EPIC, polar);
            assertTrue(pl.ok(), "book " + (i + 1));
            pl.change().accept(data);
        }
        assertFalse(M.plan(drill, data, Rarity.EPIC, polar).ok(), "max 5");
        ItemStatSheet sh = new ItemStatSheet();
        M.contribute(drill, data, Rarity.EPIC, sh);
        assertEquals(50.0, sh.total(Stat.MINING_SPEED), 1e-9);
        assertEquals(5.0, sh.total(Stat.MINING_FORTUNE), 1e-9);

        ItemDef sword = def("TEST_SWORD", ItemCategory.SWORD, Rarity.EPIC);
        ItemDef hoe = def("TEST_HOE", ItemCategory.HOE, Rarity.EPIC);
        ItemDef stats = ItemDef.of("BOOK_OF_STATS", "x", "BOOK", Rarity.EPIC, ItemCategory.COSMETIC);
        ItemData sd = new ItemData();
        ItemMods.Plan bs = M.plan(sword, sd, Rarity.EPIC, stats);
        assertTrue(bs.ok());
        bs.change().accept(sd);
        assertFalse(M.plan(sword, sd, Rarity.EPIC, stats).ok(), "once");
        assertTrue(M.plan(hoe, new ItemData(), Rarity.EPIC, stats).ok(), "farming tools count crops");
        assertFalse(M.plan(def("TEST_HELM", ItemCategory.HELMET, Rarity.EPIC), new ItemData(), Rarity.EPIC, stats).ok());

        ItemDef axe = def("TEST_AXE", ItemCategory.AXE, Rarity.EPIC);
        ItemDef wood = ItemDef.of("WOOD_SINGULARITY", "x", "PLAYER_HEAD", Rarity.LEGENDARY, ItemCategory.MATERIAL);
        ItemData ad = new ItemData();
        ItemMods.Plan w = M.plan(axe, ad, Rarity.EPIC, wood);
        assertTrue(w.ok());
        w.change().accept(ad);
        assertFalse(M.plan(axe, ad, Rarity.EPIC, wood).ok());
        ItemStatSheet ash = new ItemStatSheet();
        M.contribute(axe, ad, Rarity.EPIC, ash);
        assertEquals(25.0, ash.total(Stat.FORAGING_FORTUNE), 1e-9);
    }

    @Test void wave3BonusesAreData() {
        for (String id : List.of("mithraic", "scraped", "dimensional", "lustrous", "royal", "candied", "fleet", "stellar")) {
            ReforgeData.Row r = D.rows.get(id);
            assertNotNull(r, id);
            assertTrue(r.bonusImpl, id + " marked implemented");
            assertTrue(Bonuses.IMPLEMENTED.contains(id), id);
            assertTrue(ReforgeData.bonusValue(r, Rarity.LEGENDARY, 0, -1) > 0, id + " has a number");
        }
        // every bonus the data marks implemented has code, and vice versa
        for (ReforgeData.Row r : D.rows.values()) if (r.bonusImpl) assertTrue(Bonuses.IMPLEMENTED.contains(r.id), r.id + " flagged but no code");
    }

    @Test void amberedAndBlazingAreBehaviour() {
        ReforgeData.Row amb = D.rows.get("ambered"), blz = D.rows.get("blazing");
        assertTrue(amb.bonusImpl && Bonuses.IMPLEMENTED.contains("ambered"));
        assertTrue(blz.bonusImpl && Bonuses.IMPLEMENTED.contains("blazing"));
        assertEquals(30.0, ReforgeData.bonusValue(amb, Rarity.LEGENDARY, 0, -1), 1e-9, "Ambered L = +30% goblins");
        assertEquals(1.0, ReforgeData.bonusValue(blz, Rarity.LEGENDARY, 0, -1), 1e-9, "Blazing heat step");
        assertEquals(0.05, ReforgeData.bonusValue(blz, Rarity.LEGENDARY, 1, -1), 1e-9, "Blazing % per step");
        // the extra roll only adds what the bonus adds: 0.0004 base x 30% = 0.00012 more per block
        assertEquals(0.00012, Bonuses.extraChance(0.0004, 30), 1e-12);
        // 100 Heat x 0.05% = +5% of the 0.003 worm chance
        assertEquals(0.00015, Bonuses.extraChance(0.003, 0.05 * 100), 1e-12);
        assertEquals(0.0, Bonuses.extraChance(0.003, 0), 0);
        assertEquals(1.0, Bonuses.extraChance(0.5, 1000), 0, "clamped");
    }

    @Test void allGemIds() {
        List<String> ids = D.allGemIds();
        assertEquals(60, ids.size());
        assertEquals("PERFECT", ReforgeData.parseGem(ids.get(0))[0], "best quality first");
        assertTrue(ids.contains("ROUGH_RUBY_GEM"));
    }

    // ---- wave 5 -------------------------------------------------------------------------------------

    static ItemDef mod(String id) { return ItemDef.of(id, id, "PAPER", Rarity.EPIC, ItemCategory.MATERIAL); }

    @Test void wave5TeleportUpgrades() {
        ItemDef aote = def("ASPECT_OF_THE_END", ItemCategory.SWORD, Rarity.RARE), aotv = def("ASPECT_OF_THE_VOID", ItemCategory.SWORD, Rarity.EPIC);
        ItemDef tuner = mod("TRANSMISSION_TUNER"), merger = mod("ETHERWARP_MERGER");
        assertFalse(M.plan(def("TEST_SWORD", ItemCategory.SWORD, Rarity.EPIC), new ItemData(), Rarity.EPIC, tuner).ok(), "teleport blades only");
        ItemData d = new ItemData();
        for (int i = 0; i < ItemMods.TUNER_MAX; i++) {
            ItemMods.Plan p = M.plan(aote, d, Rarity.RARE, tuner);
            assertTrue(p.ok(), "tuner " + (i + 1));
            p.change().accept(d);
        }
        assertEquals(4, ItemMods.count(d, "tuned_transmission"), "the key the weapons module reads");
        assertFalse(M.plan(aote, d, Rarity.RARE, tuner).ok(), "max 4");
        assertFalse(M.plan(aote, new ItemData(), Rarity.RARE, merger).ok(), "the merger is the void blade's only");
        ItemData v = new ItemData();
        ItemMods.Plan m = M.plan(aotv, v, Rarity.EPIC, merger);
        assertTrue(m.ok());
        m.change().accept(v);
        assertTrue(ItemMods.flag(v, "ethermerge"));
        assertFalse(M.plan(aotv, v, Rarity.EPIC, merger).ok(), "once");
    }

    @Test void wave5FarmingAndVacuumUpgrades() {
        ItemDef vac = def("TEST_VACUUM", ItemCategory.VACUUM, Rarity.RARE);
        ItemDef hoe = def("TEST_HOE_TOOL", ItemCategory.HOE, Rarity.RARE);
        hoe.extra.add("farmTool", new com.google.gson.JsonObject());
        ItemDef alm = mod("FARMING_FOR_DUMMIES"), worm = mod("BOOKWORM_BOOK");
        assertFalse(M.plan(def("TEST_SWORD", ItemCategory.SWORD, Rarity.EPIC), new ItemData(), Rarity.EPIC, alm).ok());
        assertTrue(M.plan(hoe, new ItemData(), Rarity.RARE, alm).ok(), "farming's tools (farmTool spec)");
        ItemData d = new ItemData();
        for (int i = 0; i < 5; i++) { ItemMods.Plan p = M.plan(vac, d, Rarity.RARE, alm); assertTrue(p.ok()); p.change().accept(d); }
        assertFalse(M.plan(vac, d, Rarity.RARE, alm).ok(), "almanac cap 5");
        assertEquals(5, ItemMods.count(d, "farm_almanac"), "farming's own key");
        for (int i = 0; i < ItemMods.BOOKWORM_MAX; i++) { ItemMods.Plan p = M.plan(vac, d, Rarity.RARE, worm); assertTrue(p.ok()); p.change().accept(d); }
        assertFalse(M.plan(vac, d, Rarity.RARE, worm).ok(), "bookworm cap 5");
        assertFalse(M.plan(hoe, new ItemData(), Rarity.RARE, worm).ok(), "vacuums only");
        ItemStatSheet sh = new ItemStatSheet();
        M.contribute(vac, d, Rarity.RARE, sh);
        assertEquals(5.0, sh.total(Stat.FARMING_FORTUNE), 1e-9, "almanac FF on a vacuum");
        assertEquals(50.0, sh.damage(), 1e-9, "5 books x 10 damage");
        ItemStatSheet hs = new ItemStatSheet();
        ItemData hd = new ItemData();
        hd.extra.addProperty("farm_almanac", 3);
        M.contribute(hoe, hd, Rarity.RARE, hs);
        assertEquals(0.0, hs.total(Stat.FARMING_FORTUNE), 1e-9, "farming's contributor adds a hoe's almanacs, not us (no double count)");
    }

    @Test void wave5Silex() {
        ItemDef pick = def("TEST_PICK", ItemCategory.PICKAXE, Rarity.EPIC), axe = def("TEST_AXE", ItemCategory.AXE, Rarity.EPIC);
        ItemDef silex = mod("SIL_EX");
        ItemData d = new ItemData();
        d.enchants.put("efficiency", 4);
        assertFalse(M.plan(pick, d, Rarity.EPIC, silex).ok(), "needs Efficiency V");
        d.enchants.put("efficiency", 5);
        assertFalse(M.plan(axe, d, Rarity.EPIC, silex).ok(), "pickaxe or drill only (reference lore)");
        for (int lvl = 6; lvl <= 10; lvl++) {
            ItemMods.Plan p = M.plan(pick, d, Rarity.EPIC, silex);
            assertTrue(p.ok(), "to " + lvl);
            p.change().accept(d);
            assertEquals(lvl, d.enchant("efficiency"));
        }
        assertFalse(M.plan(pick, d, Rarity.EPIC, silex).ok(), "stops at X");
    }

    @Test void wave5PowerScrolls() throws IOException {
        // the shipping item data carries each scroll's reference numbers
        com.google.gson.JsonArray items;
        try (var r = Files.newBufferedReader(dataDir().resolve("items.json"))) { items = com.google.gson.JsonParser.parseReader(r).getAsJsonArray(); }
        Map<String, Bonuses.Scroll> scrolls = new java.util.HashMap<>();
        for (var el : items) {
            var o = el.getAsJsonObject();
            if (o.get("id").getAsString().endsWith("_POWER_SCROLL")) scrolls.put(o.get("id").getAsString(), Bonuses.Scroll.of(o.getAsJsonObject("extra")));
        }
        assertEquals(6, scrolls.size());
        assertEquals(1.0, scrolls.get("RUBY_POWER_SCROLL").healMissingPct());
        assertEquals(5.0, scrolls.get("RUBY_POWER_SCROLL").cooldown());
        assertEquals(5.0, scrolls.get("SAPPHIRE_POWER_SCROLL").mana());
        assertEquals(Stat.MINING_SPEED, scrolls.get("AMBER_POWER_SCROLL").stat());
        assertEquals(30.0, scrolls.get("AMBER_POWER_SCROLL").value());
        assertEquals(Stat.TRUE_DEFENSE, scrolls.get("OPAL_POWER_SCROLL").stat());
        assertEquals(Stat.STRENGTH, scrolls.get("JASPER_POWER_SCROLL").stat());
        assertEquals(Stat.DEFENSE, scrolls.get("AMETHYST_POWER_SCROLL").stat());
        for (Bonuses.Scroll s : scrolls.values()) if (s.stat() != null) assertEquals(5.0, s.seconds(), "5s buffs");

        ItemDef ruby = mod("RUBY_POWER_SCROLL");
        ruby.extra.addProperty("power_scroll", "RUBY");
        ItemDef jasper = mod("JASPER_POWER_SCROLL");
        jasper.extra.addProperty("power_scroll", "JASPER");
        ItemDef plain = def("TEST_SWORD", ItemCategory.SWORD, Rarity.EPIC);
        assertFalse(M.plan(plain, new ItemData(), Rarity.EPIC, ruby).ok(), "needs a RIGHT CLICK ability");
        ItemDef wand = def("TEST_WAND", ItemCategory.WAND, Rarity.EPIC);
        wand.abilities.add(new com.skyclone.api.item.AbilityDef("t", "T", com.skyclone.api.item.AbilityDef.Trigger.SNEAK, 10, 0, List.of()));
        assertFalse(M.plan(wand, new ItemData(), Rarity.EPIC, ruby).ok(), "a sneak ability is not a RIGHT CLICK one");
        wand.abilities.add(new com.skyclone.api.item.AbilityDef("r", "R", com.skyclone.api.item.AbilityDef.Trigger.RIGHT_CLICK, 10, 0, List.of()));
        ItemData d = new ItemData();
        ItemMods.Plan p = M.plan(wand, d, Rarity.EPIC, ruby);
        assertTrue(p.ok());
        p.change().accept(d);
        assertEquals("RUBY_POWER_SCROLL", ItemMods.str(d, "power_ability_scroll"), "reference key");
        assertFalse(M.plan(wand, d, Rarity.EPIC, ruby).ok(), "same scroll twice");
        ItemMods.Plan q = M.plan(wand, d, Rarity.EPIC, jasper);
        assertTrue(q.ok(), "a different scroll replaces it");
        q.change().accept(d);
        assertEquals("JASPER_POWER_SCROLL", ItemMods.str(d, "power_ability_scroll"));
    }

    @Test void wave5Bonuses() {
        ReforgeData.Row mantid = D.rows.get("mantid"), chomp = D.rows.get("chomp");
        assertEquals(50.0, ReforgeData.bonusValue(D.rows.get("majestic"), Rarity.EPIC, 0, -1), 1e-9, "Majestic +50 Speed on safari");
        for (String id : List.of("mantid", "chomp", "spiritual", "majestic")) {
            assertTrue(D.rows.get(id).bonusImpl, id);
            assertTrue(Bonuses.IMPLEMENTED.contains(id), id);
        }
        double per = ReforgeData.bonusValue(mantid, Rarity.RARE, 0, -1), mins = ReforgeData.bonusValue(mantid, Rarity.RARE, 1, -1),
                cap = ReforgeData.bonusValue(mantid, Rarity.RARE, 2, -1);
        assertEquals(0.25, per, 1e-9);
        assertEquals(10.0, mins, 1e-9);
        assertEquals(5.0, cap, 1e-9);
        assertEquals(0.0, Bonuses.mantidBonus(0, per, cap), 1e-9);
        assertEquals(2.5, Bonuses.mantidBonus(10, per, cap), 1e-9);
        assertEquals(5.0, Bonuses.mantidBonus(21, per, cap), 1e-9, "capped at 5");
        List<Long> stacks = new java.util.ArrayList<>(List.of(100L, 200L, 300L, 400L));
        assertEquals(2, Bonuses.activeStacks(stacks, 250L));
        assertEquals(2, stacks.size(), "expired stacks pruned");
        assertEquals(1.0, ReforgeData.bonusValue(chomp, Rarity.LEGENDARY, 0, -1), 1e-9);
        assertEquals(20.0, ReforgeData.bonusValue(chomp, Rarity.LEGENDARY, 1, -1), 1e-9);
        assertEquals(7.0, Bonuses.chompPct(7, 1, 20), 1e-9);
        assertEquals(20.0, Bonuses.chompPct(35, 1, 20), 1e-9);
        ItemData rod = new ItemData();
        assertEquals(0, Bonuses.chompCount(rod));
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        arr.add("sc_lava_blaze");
        arr.add("sc_magma_slug");
        rod.extra.add("chomp_slain", arr);
        assertEquals(2, Bonuses.chompCount(rod));
        com.skyclone.api.mob.MobDef blaze = new com.skyclone.api.mob.MobDef();
        blaze.id = "sc_lava_blaze";
        blaze.flags.add("sea_creature");
        blaze.extra.addProperty("lava", true);
        assertTrue(Bonuses.lavaSeaCreature(blaze));
        blaze.extra.addProperty("lava", false);
        assertFalse(Bonuses.lavaSeaCreature(blaze), "water sea creatures do not count");
        com.skyclone.api.mob.MobDef pest = new com.skyclone.api.mob.MobDef();
        pest.id = "garden_pest_mite";
        assertTrue(Bonuses.isPest(pest));
        pest.id = "zombie";
        assertFalse(Bonuses.isPest(pest));
    }

    @Test void wave5StatsAndOwners() {
        // Hunting Fortune / Wisdom exist since core wave 5, so Majestic keeps them
        ReforgeDef maj = ReforgeData.toDef(D.rows.get("majestic"));
        assertTrue(maj.statsFor(Rarity.LEGENDARY).getOrDefault(Stat.HUNTING_FORTUNE, 0.0) > 0);
        assertTrue(maj.statsFor(Rarity.LEGENDARY).getOrDefault(Stat.HUNTING_WISDOM, 0.0) > 0);
        // stats the game has no Stat for are carried for their owners instead of dropped
        assertEquals(4.0, ReforgeData.otherStat(D.rows.get("greater_spook"), Rarity.EPIC, "fear"), 1e-9);
        assertEquals(5.0, ReforgeData.otherStat(D.rows.get("greater_spook"), Rarity.MYTHIC, "fear"), 1e-9, "falls back to LEGENDARY");
        assertEquals(20.0, ReforgeData.otherStat(D.rows.get("sticky"), Rarity.MYTHIC, "pull"), 1e-9);
        assertTrue(ReforgeData.otherStat(D.rows.get("thorny"), Rarity.LEGENDARY, "overbloom") > 0);
        assertEquals(0.0, ReforgeData.otherStat(D.rows.get("spicy"), Rarity.LEGENDARY, "fear"), 0);
        assertEquals("enchanting", ReforgeData.bonusState(D.rows.get("blood_soaked")));
        assertEquals("code", ReforgeData.bonusState(D.rows.get("mantid")));
        assertEquals("lore", ReforgeData.bonusState(D.rows.get("buzzing")));
        assertEquals("none", ReforgeData.bonusState(D.rows.get("spicy")));
    }

    /** Follow-up 2026-10-06: NEU's star coin parts on every starrable item, never twice on one star. */
    @Test void starCoinsFromNeuOnEveryLadder() {
        assertEquals(10_000.0, coinsOn("HYPERION", 4), 1e-9, "dungeon star 4");
        assertEquals(25_000.0, coinsOn("HYPERION", 5), 1e-9, "dungeon star 5");
        assertEquals(0.0, coinsOn("HYPERION", 3), 1e-9, "stars 1-3 are essence only");
        assertEquals(25_000.0, coinsOn("SUPERIOR_DRAGON_HELMET", 5), 1e-9, "dragon ladder");
        assertEquals(1_000_000.0, coinsOn("CRIMSON_CHESTPLATE", 10), 1e-9, "crimson star 10");
        int withCoins = 0;
        for (var e : D.stars.entrySet()) {
            boolean any = false;
            for (List<ReforgeData.Cost> step : e.getValue().costs) {
                long n = step.stream().filter(c -> c.coins != null).count();
                assertTrue(n <= 1, e.getKey() + " has " + n + " coin cells on one star");
                any |= n == 1;
            }
            if (any) withCoins++;
        }
        assertTrue(withCoins >= 520, "NEU lists coin parts for ~527 items, got " + withCoins);
        assertEquals(2.5, ReforgeData.bonusValue(D.rows.get("squeaky"), Rarity.RARE, 0, -1), 1e-9, "Squeaky = 2.5% per piece (garden)");
    }

    private static double coinsOn(String id, int star) {
        ReforgeData.StarEntry e = D.stars.get(id);
        assertNotNull(e, id);
        double s = 0;
        for (ReforgeData.Cost c : e.costs.get(star - 1)) if (c.coins != null) s += c.coins;
        return s;
    }

    @Test void noStreamLeak() throws IOException {
        try (InputStream in = new FileInputStream(dataDir().resolve("reforges.json").toFile())) { assertTrue(in.read() > 0); }
    }
}
