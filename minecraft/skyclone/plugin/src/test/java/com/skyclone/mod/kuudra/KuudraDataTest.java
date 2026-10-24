package com.skyclone.mod.kuudra;

import org.junit.jupiter.api.Test;

import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shipping data files and formulas, against the research numbers. */
class KuudraDataTest {

    static Path dataDir() {
        for (String c : new String[]{"src/main/resources/data/kuudra", "plugin/src/main/resources/data/kuudra"}) {
            Path p = Path.of(c);
            if (Files.isDirectory(p)) return p;
        }
        throw new IllegalStateException("data/kuudra not found from " + Path.of("").toAbsolutePath());
    }

    static Reader r(String f) throws Exception {
        return new InputStreamReader(new FileInputStream(dataDir().resolve(f).toFile()), StandardCharsets.UTF_8);
    }

    static KCatalog load() throws Exception {
        try (Reader t = r("tiers.json"); Reader p = r("perks.json"); Reader l = r("loot.json"); Reader n = r("npcs.json")) {
            return KCatalog.load(t, p, l, n);
        }
    }

    @Test
    void tiersMatchResearch() throws Exception {
        assertEquals(null, KTests.research(load()));
    }

    @Test
    void formulas() {
        assertEquals(null, KTests.formulas());
        assertEquals(0.5, KMath.partyHpScale(1, 0.5), 1e-9);
        assertEquals(2.0 / 3.0, KMath.partyHpScale(2, 0.5), 1e-9);
        assertEquals(1.0, KMath.partyHpScale(9, 0.5), 1e-9);
        assertEquals(2, KMath.waveSize(0, 1, 8));
        assertEquals(8, KMath.waveSize(40, 4, 8));
        assertEquals(0.75, KMath.channelSeconds(1.5, 50), 1e-9);
        assertEquals(0.3, KMath.channelSeconds(1.5, 95), 1e-9);   // floor at 20%
        // Overcharge II (+20%) on a 15% volley = 18% of max
        assertEquals(180, KMath.volley(1000, 1000, 15, 20, 0), 1e-9);
    }

    @Test
    void pacingFormulas() {
        // the swarm scales with the crew: 28 is the four-hunter cap
        assertEquals(11, KMath.crowdCap(28, 1));
        assertEquals(17, KMath.crowdCap(28, 2));
        assertEquals(28, KMath.crowdCap(28, 4));
        assertEquals(28, KMath.crowdCap(28, 9));
        assertEquals(4, KMath.crowdCap(5, 1));          // never under four
        // a due wave waits while more than half the cap still stands, but not for ever
        assertFalse(KMath.waveDue(999, 1000, 0, 11, 30_000));
        assertTrue(KMath.waveDue(1000, 1000, 5, 11, 30_000));
        assertFalse(KMath.waveDue(1000, 1000, 6, 11, 30_000));
        assertTrue(KMath.waveDue(31_000, 1000, 11, 11, 30_000));
        assertTrue(KMath.BROOD_HIT_SLOW > 0 && KMath.BROOD_HIT_SLOW < 1);
        // a shove off the pad keeps the set-down's progress for a moment, not for ever
        assertTrue(KMath.channelHolds(0));
        assertTrue(KMath.channelHolds(KMath.CHANNEL_GRACE_MILLIS - 1));
        assertFalse(KMath.channelHolds(KMath.CHANNEL_GRACE_MILLIS));
        assertFalse(KMath.channelHolds(-5));
        assertTrue(KMath.CHANNEL_GRACE_MILLIS >= 1000, "a knockback lasts most of a second");
        // the brood hits a solo hunter softer, a full crew at full strength
        assertEquals(0.6, KMath.broodDamageScale(1, 0.6), 1e-9);
        assertEquals(1.0, KMath.broodDamageScale(4, 0.6), 1e-9);
        assertTrue(KMath.broodDamageScale(2, 0.6) > 0.6 && KMath.broodDamageScale(3, 0.6) < 1.0);
        // the playtest hunter (741 Defense) against a Basic Follower (400): about 28 a hit solo, was 48
        double perHit = 400 * KMath.broodDamageScale(1, 0.6) * (1 - 741.0 / (741 + 100));
        assertTrue(perHit < 30, "solo Follower hit " + perHit);
    }

    @Test
    void beachedBeastIsInSwordReach() {
        // the shipping boss: magma cube size 12 (0.52 per size) at scale 2.4 -> ~15 blocks wide and tall
        double width = 0.52 * 12 * 2.4;
        double centre = KMath.beachedCentreZ(-13, width / 2);
        assertEquals(-13, centre + width / 2, 1e-9);                   // front face on the beach line
        assertTrue(centre < -14, "the beast's centre stays in the lava");
        // a hunter on the platform lip (block z -11, centre -10.5) reaches the face with a 3-block sword
        assertTrue(-10.5 - (centre + width / 2) <= 3.0);
        assertEquals(60, KMath.beachedFeetY(60, 64, width), 1e-9);    // big beast: anchor height
        // a tiny hitbox is kept off the shore and lifted so it shows over the lava
        assertEquals(-14.5, KMath.beachedCentreZ(-13, 0.25), 1e-9);
        assertEquals(63, KMath.beachedFeetY(60, 64, 0.5), 1e-9);
    }

    @Test
    void lootRollsAreSaneForEveryTier() throws Exception {
        KCatalog c = load();
        Random rng = new Random(3);
        int[] sure = {1, 1, 2, 2, 3};
        int[] paidEss = {100, 250, 500, 1000, 2000};
        // the research's Tentacle share of slot 2 (SkyblockMoney/work/kuudra.md sec 2): 17.2 / 15.8 / 11.0 / 9.1 / 8.5 %
        double[] tentacle = {0.172, 0.158, 0.110, 0.091, 0.085};
        for (KCatalog.Tier t : c.tiers) {
            int i = t.index;
            KCatalog.Loot l = c.loot.get(t.id);
            assertNotNull(l, "loot for " + t.id);
            int n = 20_000, tent = 0, armor = 0;
            for (int k = 0; k < n; k++) {
                KLoot.Rolled roll = KLoot.roll(l, rng, id -> true, 0);
                assertEquals(t.freeEssence[0], roll.free().getOrDefault(KLoot.ESSENCE, 0), t.id + " free essence");
                Map<String, Integer> paid = roll.paid();
                assertEquals(sure[i], paid.getOrDefault(KLoot.KRAKEN, 0), t.id + " guaranteed Kraken");
                assertTrue(paid.getOrDefault(KLoot.TEETH, 0) >= sure[i], t.id + " guaranteed teeth");
                assertTrue(paid.getOrDefault(KLoot.ESSENCE, 0) >= paidEss[i], t.id + " guaranteed essence");
                if (paid.containsKey("KUUDRA_TENTACLE")) tent++;
                for (String id : paid.keySet()) {
                    assertFalse(id.matches("(HOT|BURNING|FIERY|INFERNAL)_(CRIMSON|AURORA|TERROR|FERVOR|HOLLOW)_.*"), t.id + " dropped prestige armour " + id);
                    if (id.matches("(CRIMSON|AURORA|TERROR|FERVOR|HOLLOW)_(HELMET|CHESTPLATE|LEGGINGS|BOOTS)")) armor++;
                    if (id.matches("ENCHANTMENT_.*_MANA_\\d+|ENCHANTMENT_MANA_VAMPIRE_\\d+")) assertTrue(id.endsWith("_" + (i + 1)), t.id + " book level " + id);
                }
            }
            assertEquals(tentacle[i], tent / (double) n, 0.01, t.id + " Tentacle share");
            // 20 x 2.875 = 57.5 of the slot-1 weight is armour
            assertEquals(57.5 / KCatalog.Loot.weight(l.slot1, id -> true), armor / (double) n, 0.015, t.id + " armour share");
            // the Leviathan pet drops at the tier's own rarity (Basic Common .. Infernal Legendary)
            boolean pet = false;
            for (KCatalog.Rare rr : l.rares) if (rr.item != null && rr.item.startsWith("KUUDRA;")) {
                assertEquals("KUUDRA;" + t.index, rr.item, t.id + " pet rarity");
                assertTrue(rr.chance > 0 && rr.chance < 0.05, t.id + " pet chance " + rr.chance);
                pet = true;
            }
            assertTrue(pet, t.id + " has no pet drop");
        }
    }

    /** The rare rows are as rare as the reference's weights say (audit K3, K11, K23: exact odds, not samples). */
    @Test
    void rareRowsMatchTheReferenceOdds() throws Exception {
        KCatalog c = load();
        Predicate<String> all = id -> true;
        // Fatal Tempo: ~1 in 4,600 chests at Basic, 1 in ~494 at Infernal (was 3.75% everywhere)
        assertEquals(1.0 / 4662, KLoot.chance(c.loot.get("basic").slot2, "ENCHANTMENT_ULTIMATE_FATAL_TEMPO_1", all), 1.0 / 4662 * 0.02);
        assertEquals(1.0 / 494, KLoot.chance(c.loot.get("infernal").slot2, "ENCHANTMENT_ULTIMATE_FATAL_TEMPO_1", all), 1.0 / 494 * 0.02);
        // Wheel of Fate ~2.4% at Basic
        assertEquals(0.0236, KLoot.chance(c.loot.get("basic").slot2, KMenus.WHEEL, all), 0.001);
        // Burning Kuudra Core 1 in ~194 at Infernal, none below Burning
        assertEquals(1.0 / 194, KLoot.chance(c.loot.get("infernal").slot1, "BURNING_KUUDRA_CORE", all), 1.0 / 194 * 0.02);
        assertEquals(0.0, KLoot.chance(c.loot.get("hot").slot1, "BURNING_KUUDRA_CORE", all));
        // Ananke Feather ~1 in 370 at Infernal and only there; Wings of Destiny lifts it
        KCatalog.Loot t5 = c.loot.get("infernal");
        assertEquals(1.0 / 370, KLoot.chance(t5.slot1, KLoot.ANANKE_FEATHER, all), 1.0 / 370 * 0.02);
        for (String tier : List.of("basic", "hot", "burning", "fiery")) assertEquals(0.0, KLoot.chance(c.loot.get(tier).slot1, KLoot.ANANKE_FEATHER, all));
        KCatalog.Entry feather = null;
        for (KCatalog.Entry e : t5.slot1) if (KLoot.ANANKE_FEATHER.equals(e.item)) feather = e;
        assertNotNull(feather);
        assertEquals(2 * feather.weight, KLoot.weightOf(feather, new KLoot.Mods(0, 0, 1, 100)), 1e-9);
        assertEquals(feather.weight, KLoot.weightOf(feather, KLoot.Mods.NONE), 1e-9);
        // nothing below 0.1% shows as a percentage in the preview
        assertEquals("1 in 4,662", KLoot.odds(1.0 / 4662));
        assertEquals("17.16%", KLoot.odds(0.17160));
    }

    /** The paid-essence dial scales the guaranteed essence; the essence bonus lifts every essence in both chests. */
    @Test
    void essenceDialsAndBonus() throws Exception {
        KCatalog.Loot l = load().loot.get("burning");
        Predicate<String> only = id -> id.equals(KLoot.ESSENCE);    // only essence exists: guaranteed + any essence row
        KLoot.Rolled half = KLoot.roll(l, new Random(1), only, new KLoot.Mods(0, 0, 0.5, 0));
        KLoot.Rolled full = KLoot.roll(l, new Random(1), only, KLoot.Mods.NONE);
        int rowsHalf = half.paid().get(KLoot.ESSENCE) - 250, rowsFull = full.paid().get(KLoot.ESSENCE) - 500;
        assertEquals(rowsFull, rowsHalf, "same seed, same essence rows; only the guaranteed 500 halves");
        KLoot.Rolled bonus = KLoot.roll(l, new Random(1), only, new KLoot.Mods(10, 0, 1, 0));
        assertEquals(55, bonus.free().get(KLoot.ESSENCE));                 // 50 free + 10%
        assertTrue(bonus.paid().get(KLoot.ESSENCE) >= 550);
    }

    @Test
    void missingItemsNeverAppear() throws Exception {
        KCatalog c = load();
        Random rng = new Random(9);
        for (KCatalog.Loot l : c.loot.values())
            for (int i = 0; i < 500; i++) {
                KLoot.Rolled roll = KLoot.roll(l, rng, id -> id.equals(KLoot.ESSENCE), 0);
                for (String id : roll.paid().keySet()) assertEquals(KLoot.ESSENCE, id);
            }
    }

    /** Every chest row is a registered id somewhere in the shipping data (or marked optional). */
    @Test
    void everyRowNamesAShippedItem() throws Exception {
        KCatalog c = load();
        Path data = dataDir().getParent();
        java.util.Set<String> ids = new java.util.HashSet<>();
        try (var files = Files.walk(data)) {
            for (Path f : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".json"))::iterator) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"([A-Z0-9_;]+)\"").matcher(Files.readString(f));
                while (m.find()) ids.add(m.group(1));
            }
        }
        for (KCatalog.Loot l : c.loot.values())
            for (KCatalog.Entry e : l.allEntries()) {
                if (e.optional || e.item.startsWith("ENCHANTMENT_")) continue;     // books are made by the enchanting module
                assertTrue(ids.contains(e.item), l.tier + ": " + e.item + " is registered by nobody");
            }
    }

    @Test
    void perksAreAffordableAndBounded() throws Exception {
        KCatalog c = load();
        assertTrue(c.perks.size() >= 10);
        for (KCatalog.Perk p : c.perks) {
            assertNotNull(p.name);
            if (p.consumable()) assertTrue(p.costOf(5) > 0);
            else {
                assertEquals(-1, p.costOf(p.max), p.id);
                assertTrue(p.cost.length >= p.max, p.id + " costs per level");
            }
        }
    }
}
