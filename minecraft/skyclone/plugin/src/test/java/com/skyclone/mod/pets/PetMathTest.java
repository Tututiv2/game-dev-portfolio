package com.skyclone.mod.pets;

import org.junit.jupiter.api.Test;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pet numbers against research/06 §6.2-§6.6, using the SHIPPED data files
 * (plugin/src/main/resources/data/pets) so a regenerated table that breaks a reference number fails.
 */
class PetMathTest {

    static PetDefs defs() {
        Path dir = Path.of("src", "main", "resources", "data", "pets");
        if (!Files.isDirectory(dir)) dir = Path.of("plugin", "src", "main", "resources", "data", "pets");
        Path base = dir;
        return PetDefs.load(f -> {
            try {
                Path p = base.resolve(f);
                return Files.exists(p) ? (InputStream) new FileInputStream(p.toFile()) : null;
            } catch (IOException e) {
                return null;
            }
        });
    }

    @Test
    void cumulativeXpMatchesResearchTable() {
        PetDefs d = defs();
        PetDefs.PetDef bee = d.pet("BEE");
        assertNotNull(bee);
        // research 06 §6.2 table
        assertEquals(100, d.totalXpFor(bee, "COMMON", 2), 1e-6);
        assertEquals(1_340, d.totalXpFor(bee, "COMMON", 10), 1e-6);
        assertEquals(5_624_785, d.totalXpFor(bee, "COMMON", 100), 1e-6);
        assertEquals(8_644_220, d.totalXpFor(bee, "UNCOMMON", 100), 1e-6);
        assertEquals(12_626_665, d.totalXpFor(bee, "RARE", 100), 1e-6);
        assertEquals(18_608_500, d.totalXpFor(bee, "EPIC", 100), 1e-6);
        assertEquals(660, d.totalXpFor(bee, "LEGENDARY", 2), 1e-6);
        assertEquals(573_730, d.totalXpFor(bee, "LEGENDARY", 50), 1e-6);
        assertEquals(25_353_230, d.totalXpFor(bee, "LEGENDARY", 100), 1e-6);
        assertEquals(25_353_230, d.totalXpFor(bee, "MYTHIC", 100), 1e-6);
    }

    @Test
    void levelFromXpRoundTrips() {
        PetDefs d = defs();
        PetDefs.PetDef bee = d.pet("BEE");
        for (String r : bee.rarities) for (int l = 1; l <= 100; l += 7) {
            double xp = d.totalXpFor(bee, r, l);
            assertEquals(l, d.levelOf(bee, r, xp), r + " level " + l);
            if (l > 1) assertEquals(l - 1, d.levelOf(bee, r, xp - 0.5), r + " just under " + l);
        }
        assertEquals(100, d.levelOf(bee, "LEGENDARY", 1e15), "level caps at 100");
    }

    @Test
    void dragonsGoTo200() {
        PetDefs d = defs();
        PetDefs.PetDef gd = d.pet("GOLDEN_DRAGON");
        assertEquals(200, gd.maxLevel);
        assertEquals(25_353_230 + 100L * 1_886_700, d.totalXpFor(gd, "LEGENDARY", 200), 1e-6);
        assertEquals(200, d.levelOf(gd, "LEGENDARY", 1e12));
        assertEquals(1, PetMath.statLevel(gd.curve, 100));
        assertEquals(1, PetMath.statLevel(gd.curve, 101));
        assertEquals(100, PetMath.statLevel(gd.curve, 200));
        assertEquals(100, PetMath.statLevel(null, 100));
    }

    @Test
    void bingoUsesOffsetZeroAndReindeerDoublesXp() {
        PetDefs d = defs();
        assertEquals(5_624_785, d.totalXpFor(d.pet("BINGO"), "LEGENDARY", 100), 1e-6);
        assertEquals(5_624_785, d.totalXpFor(d.pet("BINGO"), "MYTHIC", 100), 1e-6);
        assertEquals(2.0, d.pet("REINDEER").xpMult, 1e-9);
    }

    @Test
    void statsInterpolateLinearly() {
        PetDefs.Tier t = defs().pet("BEE").tiers.get("LEGENDARY");
        assertEquals(5.25, PetMath.lerp(t.s1.get("STRENGTH"), t.s100.get("STRENGTH"), 1), 1e-9);
        assertEquals(30, PetMath.lerp(t.s1.get("STRENGTH"), t.s100.get("STRENGTH"), 100), 1e-9);
        assertEquals(5.25 + 24.75 * 49 / 99.0, PetMath.lerp(5.25, 30, 50), 1e-9);
        assertEquals(10, t.num(0, 100), 1e-9);   // Hive: +10 Intelligence per player at Lv100
    }

    @Test
    void petXpFormula() {
        assertEquals(100, PetMath.petXp(100, "MINING", "MINING", 0, 0, 1), 1e-9);
        assertEquals(100, PetMath.petXp(300, "FARMING", "MINING", 0, 0, 1), 1e-9);
        assertEquals(150, PetMath.petXp(100, "FISHING", "FISHING", 0, 0, 1), 1e-9);
        assertEquals(50, PetMath.petXp(100, "FISHING", "MINING", 0, 0, 1), 1e-9);
        assertEquals(100.0 / 12.0, PetMath.petXp(100, "ALCHEMY", "MINING", 0, 0, 1), 1e-9);
        assertEquals(100, PetMath.petXp(100, "ALCHEMY", "ALCHEMY", 0, 0, 1), 1e-9);
        assertEquals(160, PetMath.petXp(100, "MINING", "MINING", 60, 0, 1), 1e-9);
        assertEquals(150, PetMath.petXp(100, "MINING", "MINING", 0, 50, 1), 1e-9);
        assertEquals(200, PetMath.petXp(100, "MINING", "MINING", 0, 0, 2), 1e-9);
        assertEquals(100, PetMath.petXp(100, "COMBAT", "ALL", 0, 0, 1), 1e-9);
        assertEquals(0, PetMath.petXp(100, "CARPENTRY", "COMBAT", 0, 0, 1), 1e-9);
        assertEquals(0, PetMath.petXp(100, "TAMING", "COMBAT", 0, 0, 1), 1e-9);
    }

    @Test
    void shareRateAndScore() {
        assertEquals(12, PetMath.shareRate(60, false, false, 0), 1e-9);
        assertEquals(27, PetMath.shareRate(60, true, false, 0), 1e-9);
        assertEquals(37, PetMath.shareRate(60, true, true, 0), 1e-9);
        assertEquals(47, PetMath.shareRate(60, true, true, 100), 1e-9);
        PetDefs d = defs();
        assertEquals(0, PetMath.scoreMagicFind(9, d.meta.scoreTable));
        assertEquals(1, PetMath.scoreMagicFind(10, d.meta.scoreTable));
        assertEquals(5, PetMath.scoreMagicFind(100, d.meta.scoreTable));
        assertEquals(13, PetMath.scoreMagicFind(600, d.meta.scoreTable));
        assertEquals(6, PetMath.scoreOf("MYTHIC"));
        assertEquals(1, PetMath.scoreOf("COMMON"));
    }

    @Test
    void everyAbilityTokenHasANumber() {
        PetDefs d = defs();
        assertEquals(88, d.pets.size());
        Pattern tok = Pattern.compile("\\{([a-z])\\}");
        for (PetDefs.PetDef p : d.pets.values()) for (String r : p.rarities) {
            PetDefs.Tier t = p.tiers.get(r);
            assertNotNull(t, p.id + " " + r);
            for (PetDefs.Ability a : t.abilities) {
                for (String line : a.lore) {
                    Matcher m = tok.matcher(line);
                    while (m.find()) {
                        Integer idx = a.tok.get(m.group(1));
                        if (idx == null || idx >= t.n100.length) fail(p.id + " " + r + " " + a.name + " uses {" + m.group(1) + "} with no number");
                    }
                }
            }
        }
    }

    @Test
    void katTableIsSane() {
        PetDefs d = defs();
        assertTrue(d.kat.size() >= 200, "sitter steps: " + d.kat.size());
        PetDefs.KatStep ed = d.katStep("ENDER_DRAGON", "EPIC");
        assertNotNull(ed);
        assertEquals("LEGENDARY", ed.to);
        assertEquals(400_000_000L, ed.coins);   // research 06 §6.5
        PetDefs.KatStep whale = d.katStep("BLUE_WHALE", "COMMON");
        assertNotNull(whale);
        assertEquals(15_000L, whale.coins);
        assertEquals(86_400L, whale.seconds);
    }

    /** Every effect type the data uses must be one the Java side names somewhere (no silent dead effect). */
    @Test
    void everyEffectTypeHasAnInterpreter() throws IOException {
        PetDefs d = defs();
        Path src = Path.of("src", "main", "java", "com", "skyclone", "mod", "pets");
        if (!Files.isDirectory(src)) src = Path.of("plugin", "src", "main", "java", "com", "skyclone", "mod", "pets");
        StringBuilder all = new StringBuilder();
        try (var files = Files.list(src)) {
            for (Path f : (Iterable<Path>) files::iterator) if (f.toString().endsWith(".java")) all.append(Files.readString(f));
        }
        String code = all.toString();
        java.util.Set<String> types = new java.util.TreeSet<>();
        for (PetDefs.PetDef p : d.pets.values()) for (PetDefs.Tier t : p.tiers.values())
            for (PetDefs.Ability a : t.abilities) for (com.google.gson.JsonObject fx : a.fx) types.add(fx.get("t").getAsString());
        for (PetDefs.HeldItem h : d.held.values()) for (com.google.gson.JsonObject fx : h.fx) types.add(fx.get("t").getAsString());
        java.util.Set<String> marker = java.util.Set.of("info", "included", "external");
        for (String t : types) {
            if (marker.contains(t)) continue;
            assertTrue(code.contains("\"" + t + "\""), "effect type '" + t + "' is in the data but no pets class names it");
        }
    }

    /** Wave 3: fishing hooks, the wisp pool, the Bejeweled Collar and gated Forge copies. */
    @Test
    void wave3Data() throws IOException {
        PetDefs d = defs();
        assertTrue(hasFx(d, "DOLPHIN", "LEGENDARY", "sc_stun"), "Dolphin Splash Stun");
        assertTrue(hasFx(d, "SPINOSAURUS", "LEGENDARY", "sc_wound"), "Spinosaurus Sharp Teeth");
        assertTrue(hasFx(d, "HERMIT_CRAB", "LEGENDARY", "treasure_buff"), "Hermit Crab Shell Shelter");
        assertTrue(hasFx(d, "SEAL", "LEGENDARY", "treasure_drop"), "Seal Peak Form");
        com.google.gson.JsonObject pool = fx(d, "SUBZERO_WISP", "LEGENDARY", "pool");
        assertNotNull(pool, "Subzero Wisp Cool Spring");
        assertEquals(25, pool.get("heal").getAsDouble(), 1e-9);   // reference lore: 25% now
        assertEquals(10, pool.get("regen").getAsDouble(), 1e-9);  // and 10%/s for 8s
        assertEquals(15, fx(d, "FROST_WISP", "RARE", "pool").get("heal").getAsDouble(), 1e-9);
        for (String w : new String[]{"DROPLET_WISP", "FROST_WISP", "GLACIAL_WISP", "SUBZERO_WISP"}) {
            PetDefs.PetDef p = d.pets.get(w);
            com.google.gson.JsonObject b = fx(d, w, p.rarities.get(p.rarities.size() - 1), "bulwark");
            assertNotNull(b, w + " Ember Ward");
            assertEquals("blaze", b.get("match").getAsString());
            assertEquals(30, b.get("defense").getAsDouble(), 1e-9);   // reference lore: next upgrade +30 & +3 at 100 kills
            assertEquals(100, b.get("every").getAsInt());
        }
        PetData.OwnedPet op = new PetData.OwnedPet();
        assertEquals(0, op.counter("bulwark"));
        for (int i = 0; i < 3; i++) op.bump("bulwark");
        assertEquals(3, op.counter("bulwark"));
        PetDefs.HeldItem collar = d.held.get("BEJEWELED_COLLAR");
        assertNotNull(collar, "Bejeweled Collar is a pet item");
        com.google.gson.JsonObject st = collar.fx.get(0).getAsJsonObject("stats");
        assertEquals(25, st.get("MINING_SPEED").getAsDouble(), 1e-9);
        assertEquals(10, st.get("MINING_FORTUNE").getAsDouble(), 1e-9);
        Path rec = Path.of("src", "main", "resources", "data", "pets", "recipes.json");
        if (!Files.exists(rec)) rec = Path.of("plugin", "src", "main", "resources", "data", "pets", "recipes.json");
        for (var e : com.google.gson.JsonParser.parseString(Files.readString(rec)).getAsJsonArray()) {
            var o = e.getAsJsonObject();
            if ("FORGE".equals(o.get("type").getAsString()))
                assertTrue(o.has("unlock") && o.get("unlock").getAsString().startsWith("hotm:"), o.get("result") + " forge copy has no HOTM gate");
            if ("CHICKEN;0".equals(o.get("result").getAsString()))   // the crafting module's gate, copied
                assertEquals("collection:RAW_CHICKEN:3", o.get("unlock").getAsString());
        }
    }

    /** Mob families against the REAL mob ids other modules ship (key = "id family slayer name entity"). */
    @Test
    void mobFamiliesHitTheRightMobs() {
        assertTrue(PetEffects.matchesKey("pest", "garden_pest_mite mite null mite endermite"));
        assertTrue(!PetEffects.matchesKey("pest", "end_nest_endermite endermite null nest endermite endermite"), "a nest endermite is not a pest");
        assertTrue(!PetEffects.matchesKey("pest", "ch_butterfly butterfly null butterfly allay"), "a butterfly is not a pest");
        assertTrue(PetEffects.matchesKey("spooky", "spooky_batty_witch spooky null batty witch witch"));
        assertTrue(!PetEffects.matchesKey("spooky", "crimson_cinderbat cinderbat null cinderbat bat"), "a cinderbat is not spooky");
        assertTrue(!PetEffects.matchesKey("spooky", "sc_sea_witch sea_witch null sea witch witch"), "a sea witch is not spooky");
        assertTrue(PetEffects.matchesKey("mythological", "myth_inquisitor_3 inquisitor null minos inquisitor zombie"));
        assertTrue(PetEffects.matchesKey("winter", "sc_reindrake reindrake null reindrake ravager"));
        assertTrue(PetEffects.matchesKey("blaze", "crimson_ashfang ashfang null ashfang blaze"));
        assertTrue(PetEffects.matchesKey("bal", "ch_magmaw magmaw null magmaw magma_cube"), "Bal is Magmaw here");
        assertTrue(PetEffects.matchesKey("ender", "end_zealot_55 zealot null zealot enderman"));
    }

    @Test
    void wave3TargetAndSpawnPerks() {
        PetDefs d = defs();
        // wave 5: Crowned moved from "target:" (stat while the last-hit mob is mythological) to mf_vs, core's per-kill
        // Magic Find hook, so the bonus lands on exactly that mob's drops (PetWave5Test.magicFindAgainstAFamily)
        com.google.gson.JsonObject crowned = fx(d, "GRIFFIN", "LEGENDARY", "mf_vs");
        assertNotNull(crowned, "Griffin Crowned is Magic Find against a family");
        assertEquals("mythological", crowned.get("match").getAsString());
        com.google.gson.JsonObject mite = fx(d, "ENDERMITE", "LEGENDARY", "gather_spawn");
        assertNotNull(mite, "Endermite Mite Lure");
        assertEquals("end_nest_endermite", mite.get("mob").getAsString());
        assertEquals("PET_LUCK", mite.get("per_stat").getAsString());
        assertNotNull(crowned);
        com.google.gson.JsonObject rock = fx(d, "ROCK", "LEGENDARY", "ride");
        assertNotNull(rock, "Rock can be sat on");
        assertEquals("sit", rock.get("mode").getAsString());
        assertEquals("mount", fx(d, "ARMADILLO", "LEGENDARY", "ride").get("mode").getAsString());
        assertTrue(fx(d, "ROCK", "LEGENDARY", "dmg_mult").get("when").toString().contains("riding"), "Steady Ground needs you seated");
    }

    private static boolean hasFx(PetDefs d, String pet, String r, String type) { return fx(d, pet, r, type) != null; }

    private static com.google.gson.JsonObject fx(PetDefs d, String pet, String r, String type) {
        PetDefs.PetDef p = d.pets.get(pet);
        if (p == null || p.tiers.get(r) == null) return null;
        for (PetDefs.Ability a : p.tiers.get(r).abilities)
            for (com.google.gson.JsonObject f : a.fx) if (type.equals(f.get("t").getAsString())) return f;
        return null;
    }

    @Test
    void petScoreAwardIsOneLinePerRise() {
        // wave4-loops: two pets learned = 4 score points printed "+3 SkyBlock XP" four times
        assertEquals(12, PetMath.scoreAwardXp(0, 4, 3), "4 points at once = one 12 XP award");
        assertEquals(6, PetMath.scoreAwardXp(4, 6, 3));
        assertEquals(0, PetMath.scoreAwardXp(6, 6, 3), "no rise, no award");
        assertEquals(0, PetMath.scoreAwardXp(6, 3, 3), "a falling score never pays or takes");
    }

    @Test
    void mountSpeedFollowsSpeedAndLevelUnderACap() {
        double bps = PetMath.BLOCKS_PER_ATTR;
        // wave4-loops: Lv1 Armadillo at ~350 Speed ran 21.7 b/s. Lv1 now walks at your own walking pace...
        double lv1 = PetMath.mountAttr(350, 1, 1.6, 400, 15) * bps;
        assertTrue(lv1 <= 15.0 + 1e-9 && lv1 < 21.7 / 1.4, "Lv1 at 350 Speed: " + lv1);
        assertEquals(0.1, PetMath.mountAttr(100, 1, 1.6, 400, 15), 1e-9, "Lv1, 100 Speed = vanilla walk");
        // ...and Lv100 is the data's 1.6x (the 6.9 b/s seen live in wave 3 at 100 Speed)
        assertEquals(0.16, PetMath.mountAttr(100, 100, 1.6, 400, 15), 1e-9);
        assertEquals(0.13, PetMath.mountAttr(100, 50, 1.6, 400, 15), 0.002, "linear in level");
        assertEquals(0.16, PetMath.mountAttr(100, 200, 1.6, 400, 15), 1e-9, "levels past 100 count as 100");
        // the cap in blocks/s holds at any Speed; Speed past the stat cap buys nothing; low Speed never slows below walk
        assertEquals(15.0, PetMath.mountAttr(5000, 100, 1.6, 400, 15) * bps, 1e-9);
        assertEquals(PetMath.mountAttr(400, 30, 1.6, 400, 99), PetMath.mountAttr(900, 30, 1.6, 400, 99), 1e-12);
        assertEquals(0.1, PetMath.mountAttr(20, 1, 1.6, 400, 15), 1e-9);
    }

    @Test
    void numbersFormat() {
        assertEquals("10", PetMath.fmt(10));
        assertEquals("0.02", PetMath.fmt(0.02));
        assertEquals("11.1", PetMath.fmt(11.1));
        assertEquals("1,500,000", PetMath.fmt(1_500_000));
    }
}
