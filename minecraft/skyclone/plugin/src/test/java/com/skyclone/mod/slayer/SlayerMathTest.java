package com.skyclone.mod.slayer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.stat.Stat;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SlayerMath and the shipped slayer data against research 04 (no server). */
class SlayerMathTest {

    private static final double E = 1e-9;

    private static SlayerCatalog catalog() throws Exception {
        try (InputStream in = SlayerMathTest.class.getClassLoader().getResourceAsStream("data/slayer/slayers.json")) {
            assertNotNull(in, "slayers.json on the classpath");
            return SlayerCatalog.read(in);
        }
    }

    private static JsonArray array(String f) throws Exception {
        try (InputStream in = SlayerMathTest.class.getClassLoader().getResourceAsStream("data/slayer/" + f)) {
            assertNotNull(in, f + " on the classpath");
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
        }
    }

    @Test
    void levelTablesMatchResearch() throws Exception {
        SlayerCatalog c = catalog();
        SlayerCatalog.Type z = c.type("zombie"), w = c.type("wolf"), v = c.type("vampire");
        assertEquals(9, z.maxLevel());
        assertEquals(5, v.maxLevel());
        assertEquals(0, SlayerMath.level(z.xp, 4));
        assertEquals(1, SlayerMath.level(z.xp, 5));
        assertEquals(4, SlayerMath.level(z.xp, 4999));
        assertEquals(5, SlayerMath.level(z.xp, 5000));
        assertEquals(9, SlayerMath.level(z.xp, 1_000_000));
        assertEquals(2, SlayerMath.level(w.xp, 249));
        assertEquals(3, SlayerMath.level(w.xp, 250));
        assertEquals(5, SlayerMath.level(v.xp, 2400));
        assertEquals(-1, SlayerMath.nextThreshold(z.xp, 2_000_000));
        assertEquals(20_000, SlayerMath.nextThreshold(z.xp, 6000));
        assertEquals(0.5, SlayerMath.levelProgress(z.xp, 12_500), E);
    }

    @Test
    void masterTableMatchesResearch() throws Exception {
        SlayerCatalog c = catalog();
        // research 04 sec 2.3
        assertEquals(1_500_000, c.type("zombie").tier(4).health, E);
        assertEquals(10_000_000, c.type("zombie").tier(5).health, E);
        assertEquals(6000, c.type("zombie").tier(5).spawnXp, E);
        assertEquals(100_000, c.type("spider").tier(5).cost, E);
        assertEquals(200, c.type("wolf").tier(4).trueDps, E);
        assertEquals(210_000_000, c.type("enderman").tier(4).health, E);
        assertEquals(150_000, c.type("blaze").tier(4).cost, E);
        assertEquals(36_000, c.type("blaze").tier(4).spawnXp, E);
        assertEquals(1500, c.type("zombie").tier(5).slayerXp, E);
        assertEquals(150, c.type("vampire").tier(5).slayerXp, E);
        assertEquals(955, SlayerMath.immolate(c.type("blaze").immolate[3], 0.2), E);
        assertEquals(385, SlayerMath.immolate(c.type("blaze").immolate[3], 0.9), E);
    }

    @Test
    void lootChancesReproduceTheDisplayedOdds() throws Exception {
        SlayerCatalog c = catalog();
        Map<String, Double> z4 = SlayerMath.chances(c.type("zombie"), 4, 9, 0, 0, null, 1);
        assertEquals(16.22, z4.get("FOUL_FLESH") * 100, 1e-6);
        assertEquals(0.06, z4.get("SCYTHE_BLADE") * 100, 1e-6);
        double sum = 0;
        for (double v : z4.values()) sum += v;
        assertEquals(1.0, sum, 1e-9);
        Map<String, Double> s5 = SlayerMath.chances(c.type("spider"), 5, 9, 0, 0, null, 1);
        assertEquals(12.809, s5.get("TOXIC_ARROW_POISON") * 100, 1e-6);
        Map<String, Double> e4 = SlayerMath.chances(c.type("enderman"), 4, 9, 0, 0, null, 1);
        assertEquals(0.0565, e4.get("JUDGEMENT_CORE") * 100, 1e-6);
    }

    @Test
    void lowLevelsRemoveRareDropsAndRenormalise() throws Exception {
        SlayerCatalog.Type z = catalog().type("zombie");
        Map<String, Double> lvl0 = SlayerMath.chances(z, 5, 0, 0, 0, null, 1);
        Map<String, Double> lvl9 = SlayerMath.chances(z, 5, 9, 0, 0, null, 1);
        assertFalse(lvl0.containsKey("WARDEN_HEART"));
        assertTrue(lvl9.containsKey("WARDEN_HEART"));
        // research 2.11: levelling up dilutes common drops
        assertTrue(lvl0.get("FOUL_FLESH") > lvl9.get("FOUL_FLESH"));
    }

    @Test
    void magicFindAndMeterOnlyBoostRareDrops() throws Exception {
        SlayerCatalog.Type z = catalog().type("zombie");
        SlayerCatalog.LootEntry foul = z.lootEntry("FOUL_FLESH"), scythe = z.lootEntry("SCYTHE_BLADE");
        assertEquals(foul.chanceAt(4), SlayerMath.weight(foul, 4, 9, 300, 0, 1), E);              // 16% ignores MF
        assertEquals(scythe.chanceAt(4) * 4, SlayerMath.weight(scythe, 4, 9, 300, 0, 1), E);      // +300% MF
        assertEquals(scythe.chanceAt(4) * 1.2, SlayerMath.weight(scythe, 4, 9, 0, 20, 1), E);     // Trailblazer +20%
        assertEquals(2.0, SlayerMath.meterMultiplier(0.5, 2.0), E);
        assertEquals(0.5, SlayerMath.meterProgress(1_837_125, 3_674_250), E);
        assertEquals("", SlayerMath.pick(SlayerMath.chances(z, 1, 9, 0, 0, null, 1), 0.5));       // T1 only has its token
    }

    @Test
    void questMathMatchesResearch() {
        assertEquals(50_000, SlayerMath.cost(100_000, 50), E);
        assertEquals(1875, SlayerMath.slayerXp(1500, 25), E);
        assertEquals(6, SlayerMath.healSeconds(0), E);
        assertEquals(10, SlayerMath.healSeconds(9), E);
        assertEquals(100, SlayerMath.hitShield(4));
        assertEquals(80, SlayerMath.backstab(5), E);
        assertEquals(9, SlayerMath.eggSacs(5)[1]);
        assertEquals("2:05", SlayerMath.clock(125));
    }

    @Test
    void minibossesNeverOutHitTheirBoss() throws Exception {
        // review 2026-10-02: health/250 alone made the Void-Mad Maniac hit for 300,000 next to an 11,000 DPS boss
        SlayerCatalog c = catalog();
        Map<String, Double> dmg = new java.util.HashMap<>();
        for (JsonElement e : array("mobs.json")) {
            JsonObject o = e.getAsJsonObject();
            dmg.put(o.get("id").getAsString(), o.get("damage").getAsDouble());
        }
        List<String> problems = new ArrayList<>();
        for (SlayerCatalog.Type t : c.types) {
            for (SlayerCatalog.Mini m : t.minibosses) {
                double boss = t.tier(m.minTier).dps;
                if (dmg.get(m.mob) > Math.max(50, boss)) problems.add(m.mob + " hits " + dmg.get(m.mob) + " > boss " + boss);
            }
        }
        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    void itemsAndMobsAreWellFormed() throws Exception {
        Set<String> ids = new HashSet<>();
        List<String> problems = new ArrayList<>();
        for (JsonElement e : array("items.json")) {
            JsonObject o = e.getAsJsonObject();
            String id = o.get("id").getAsString();
            if (!ids.add(id)) problems.add("duplicate " + id);
            ItemCategory.parse(o.get("category").getAsString());
            if (o.has("stats")) for (String k : o.getAsJsonObject("stats").keySet()) if (Stat.parse(k) == null) problems.add(id + " stat " + k);
            String name = o.get("name").getAsString();
            for (String banned : List.of("Revenant", "Tarantula", "Sven", "Voidgloom", "Maddox", "Final Destination", "Warden Heart", "Judgement", "Shredded Sinew"))
                if (name.contains(banned)) problems.add(id + " shows a reference name: " + name);
        }
        Set<String> mobs = new HashSet<>();
        for (JsonElement e : array("mobs.json")) mobs.add(e.getAsJsonObject().get("id").getAsString());
        SlayerCatalog c = catalog();
        for (SlayerCatalog.Type t : c.types) {
            for (SlayerCatalog.Tier tier : t.tiers) if (!mobs.contains(tier.mob)) problems.add("no mob " + tier.mob);
            for (SlayerCatalog.Mini m : t.minibosses) if (!mobs.contains(m.mob)) problems.add("no miniboss " + m.mob);
            for (SlayerCatalog.LootEntry l : t.loot) {
                if (l.minAt(1) > l.maxAt(1)) problems.add(t.id + " " + l.item + " min > max");
            }
        }
        for (JsonElement e : array("recipes.json")) {
            JsonObject r = e.getAsJsonObject();
            if (!ids.contains(r.get("result").getAsString())) problems.add("recipe for unknown " + r.get("result"));
        }
        assertTrue(problems.isEmpty(), problems.toString());
    }

    /**
     * Playtest 2026-10-02: Ghoul Life Drain was 1.5x tier DPS as TRUE damage every 4 s, so a T3 Ghoul killed a 600 HP /
     * 700 Defense player in ~13 s whatever their Defense. The drain is now a bounded share of the stated DPS and
     * Defense applies (core DamageMath: def / (def + 100)).
     */
    @Test
    void ghoulLifeDrainIsABoundedShareOfStatedDamage() throws Exception {
        SlayerCatalog.Type z = catalog().type("zombie");
        assertEquals(120, SlayerMath.lifeDrain(120, 1.0), E);
        for (SlayerCatalog.Tier t : z.tiers) {
            // at the default dial (0.5 s) the drain is 12.5% of the tooltip's "Damage: X per second"
            assertTrue(SlayerMath.lifeDrainDps(t.dps, 0.5) <= 0.125 * t.dps + E, "T" + t.tier);
        }
        assertEquals(0, SlayerMath.lifeDrain(120, -3), E);

        // The playtest scenario: T3, 600 HP, 700 Defense (Pestilence shreds 25% -> 525), standing in melee + aura.
        SlayerCatalog.Tier t3 = z.tiers.stream().filter(t -> t.tier == 3).findFirst().orElseThrow();
        double def = 700 * 0.75;
        double dps = SlayerMath.afterDefense(SlayerMath.ghoulRawPerSecond(3, t3.dps, 0.5, 1, 1), def);
        double drainOnly = SlayerMath.afterDefense(SlayerMath.lifeDrainDps(t3.dps, 0.5), def);
        assertTrue(600 / drainOnly > 60, "Life Drain alone took 600 HP in " + 600 / drainOnly + " s (was ~13 s)");
        assertTrue(600 / dps > 18, "600 HP should outlast melee + aura + drain for >18 s with no healing, got " + 600 / dps);
        assertTrue(drainOnly / dps < 0.2, "Life Drain must not be the main damage source, share " + drainOnly / dps);
    }

    /**
     * Wave-4 loops: Life Drain and Pestilence (TUNE, no reference numbers) were stacked ON TOP of the stated melee,
     * so a T3 Ghoul dealt 186 raw/s against a tooltip "Damage: 120 per second". Now the three add up to the stated
     * figure at default dials, whatever the tier, and a silly drain dial can never zero the melee.
     */
    @Test
    void ghoulStatedDamageIsTheWholeBill() throws Exception {
        for (SlayerCatalog.Tier t : catalog().type("zombie").tiers) {
            for (double drain : new double[]{0, 0.25, 0.5, 1.0}) {
                assertEquals(t.dps, SlayerMath.ghoulRawPerSecond(t.tier, t.dps, drain, 1, 1), 1e-6,
                        "T" + t.tier + " drain " + drain);
            }
            assertTrue(SlayerMath.ghoulMeleeShare(t.tier, 0.5) >= 0.7, "melee stays the bulk at T" + t.tier);
        }
        assertEquals(SlayerMath.MIN_MELEE_SHARE, SlayerMath.ghoulMeleeShare(3, 50), E);
        assertEquals(1.0, SlayerMath.ghoulMeleeShare(1, 0), E);
        // the dials still scale their own part
        assertEquals(120 * SlayerMath.ghoulMeleeShare(3, 0.5) * 2 + (15 + 18) * 0.5,
                SlayerMath.ghoulRawPerSecond(3, 120, 0.5, 2, 0.5), 1e-6);
        assertEquals(42.0, SlayerMath.afterDefense(120, 185.714285714), 1e-6);
        assertEquals(Double.POSITIVE_INFINITY, SlayerMath.secondsToLast(500, 0), E);
        assertEquals(25.0, SlayerMath.secondsToLast(500, 20), E);
    }

    /**
     * A T3 Ghoul fight stepped every 0.25 s the way {@link ZombieFight} runs it: melee once a second (x2 while
     * enraged, 45-57 s into each 57 s cycle), Life Drain every 4 s healing the boss by what it took, Pestilence every
     * second with -25% Defense, the player healing at a flat rate and dealing flat damage. Returns the player's
     * lowest Health fraction if they win, or minus the boss's remaining fraction if they die or time out.
     */
    private static double t3Fight(double health, double defense, double healPerSecond, double playerDps,
                                  double meleeShare, double drainSeconds, double pestShare) {
        double dps = 120, bossMax = 400_000, boss = bossMax, hp = health, low = health;
        double def = defense * 0.75;
        for (int step = 0; step < 180 * 4; step++) {
            double t = step / 4.0;
            if (step % 4 == 0) {
                boolean enraged = (t % 57) >= 45;
                hp -= SlayerMath.afterDefense(dps * meleeShare * (enraged ? 2 : 1), def);
                hp -= SlayerMath.afterDefense(dps * pestShare, def);
            }
            if (step > 0 && step % 16 == 0) {
                double dealt = SlayerMath.afterDefense(SlayerMath.lifeDrain(dps, drainSeconds), def);
                hp -= dealt;
                boss = Math.min(bossMax, boss + dealt);
            }
            hp = Math.min(health, hp + healPerSecond / 4);
            boss -= playerDps / 4;
            low = Math.min(low, hp);
            if (hp <= 0) return -boss / bossMax;
            if (boss <= 0) return low / health;
        }
        return -boss / bossMax;
    }

    /**
     * Wave-4 loops replay: Superior Dragon (598 HP, 630 Defense), an enchanted Sharp Shadow Fury (~7.5k dps) and Life
     * Steal III (~18 HP/s at two hits a second). Research puts T1-T3 in the early-dragon-armour stage; that kit died
     * twice in 35-47 s. It must now win with Health to spare, while the same kit with no healing still loses (T3 stays
     * "Challenging") and an under-geared Combat-10 player still dies fast.
     */
    @Test
    void t3GhoulIsWinnableWithTheWaveFourKit() {
        double share = SlayerMath.ghoulMeleeShare(3, 0.5);
        double kit = t3Fight(598, 630, 18, 7500, share, 0.5, SlayerMath.PESTILENCE_SHARE);
        assertTrue(kit > 0.3, "Superior Dragon + Life Steal III should win with >30% Health left, got " + kit);
        // the old stacking (melee 100% + drain 1 s + Pestilence 30%) reproduces the playtest deaths
        double old = t3Fight(598, 630, 18, 7500, 1.0, 1.0, 0.3);
        assertTrue(old < 0, "old numbers should kill the playtest kit, got " + old);
        // still a fight: no healing, same kit -> dies
        assertTrue(t3Fight(598, 630, 3.7, 7500, share, 0.5, SlayerMath.PESTILENCE_SHARE) < 0, "no healing should lose");
        // under-geared for T3 (Combat 10, 300 HP, 200 Defense, 3k dps) -> dies with the boss nearly full
        assertTrue(t3Fight(300, 200, 4, 3000, share, 0.5, SlayerMath.PESTILENCE_SHARE) < -0.9, "under-geared should lose badly");
    }

    /** Wave-4 loops: the RNG meter listed Smite VI as a bare "Enchanted Book". */
    @Test
    void booksSayWhichEnchant() {
        assertEquals("Enchanted Book (Smite VI)", SlayerMath.withEnchant("Enchanted Book", "Smite", 6));
        assertEquals("Enchanted Book (Mana Steal I)", SlayerMath.withEnchant("Enchanted Book", " Mana Steal ", 1));
        assertEquals("Ghoul Flesh", SlayerMath.withEnchant("Ghoul Flesh", null, 0));
        assertEquals("Enchanted Book (Smite)", SlayerMath.withEnchant("Enchanted Book", "Smite", 0));
    }

    /** Playtest wave3: the bar filled at 59% Health inside the pack and the boss rose on top of the player. */
    @Test
    void bossWaitsUntilTheSummonerIsReady() {
        double ready = 75, calm = 3, max = 60;
        // the playtest moment: 59% Health, hit a moment ago, bar just filled -> wait
        assertFalse(SlayerMath.readyToSummon(0.59, 0.2, 0, ready, calm, max));
        // healed but still being hit in the pack -> wait
        assertFalse(SlayerMath.readyToSummon(1.0, 1.0, 10, ready, calm, max));
        // calm but still hurt -> wait
        assertFalse(SlayerMath.readyToSummon(0.70, 10, 10, ready, calm, max));
        // healed and out of the fight -> rise
        assertTrue(SlayerMath.readyToSummon(0.75, 3, 5, ready, calm, max));
        // never hit since the bar filled, full health -> rise at once (no forced pause)
        assertTrue(SlayerMath.readyToSummon(1.0, Double.MAX_VALUE, 0, ready, calm, max));
        // the wait is bounded: the quest never stalls
        assertTrue(SlayerMath.readyToSummon(0.10, 0, 60, ready, calm, max));
        // dial at 0 = the reference (rises at once, wherever you are)
        assertTrue(SlayerMath.readyToSummon(0.10, 0, 0, ready, calm, 0));
        // unknown max health never blocks
        assertTrue(SlayerMath.readyToSummon(Double.NaN, 5, 0, ready, calm, max));
    }

    // ---- wave 5 (2026-10-06) --------------------------------------------------------------------

    /** Hunting shards in the shipped tables: research odds, the box's names, and the token absorbs them. */
    @Test
    void huntingShardsMatchResearchAndLeaveOtherOddsAlone() throws Exception {
        SlayerCatalog c = catalog();
        SlayerCatalog.Type z = c.type("zombie"), s = c.type("spider"), b = c.type("blaze");
        SlayerCatalog.LootEntry rev = z.lootEntry("SHARD_REVENANT");
        assertNotNull(rev, "Revenant shard in the Ghoul table");
        assertTrue(rev.shard);
        assertEquals("Revenant Shard", rev.name);
        // research 2.5 weights w100/w150/w200 next to Foul Flesh w2000
        assertEquals(0, rev.chanceAt(2), E);
        assertEquals(100.0 / 2000 * z.lootEntry("FOUL_FLESH").chanceAt(3), rev.chanceAt(3), 1e-3);
        assertEquals(1.2165, rev.chanceAt(4), 1e-3);
        assertEquals(0.71, s.lootEntry("SHARD_PRIMORDIAL").chanceAt(5), E);
        assertEquals(0, s.lootEntry("SHARD_PRIMORDIAL").chanceAt(4), E);
        assertEquals(0.25, z.lootEntry("SHARD_PARAGON").chanceAt(4), E);       // NEU's legacy Slayer Discount shard
        assertEquals(0.50, s.lootEntry("SHARD_PARAGON").chanceAt(5), E);
        assertEquals(5.27, b.lootEntry("SHARD_BURNINGSOUL").chanceAt(4), E);
        assertEquals(3, b.lootEntry("SHARD_BURNINGSOUL").minLevel);
        // the displayed research odds of everything else are unchanged (the token gave up the shard's share)
        assertEquals(16.22, SlayerMath.chances(z, 4, 9, 0, 0, null, 1).get("FOUL_FLESH") * 100, 0.01);
        assertEquals(12.809, SlayerMath.chances(s, 5, 9, 0, 0, null, 1).get("TOXIC_ARROW_POISON") * 100, 0.01);
        // every shard is a non-token drop with a name and rarity, weighted except the Paragon (its own pool, audit C2);
        // the Shredded Tendon is an item, not a shard
        for (SlayerCatalog.Type t : c.types) {
            for (SlayerCatalog.LootEntry e : t.loot) {
                if (!e.shard) continue;
                assertFalse(e.token, e.item);
                assertEquals(e.item.equals("SHARD_PARAGON"), e.independent, e.item);
                assertNotNull(e.name, e.item);
                assertNotNull(e.rarity, e.item);
            }
        }
        assertFalse(z.lootEntry("SHARD_OF_THE_SHREDDED").shard);
    }

    /** Without the hunting module a shard must not eat a roll: it leaves the pool, the rest renormalises. */
    @Test
    void unavailableShardsLeaveThePool() throws Exception {
        SlayerCatalog.Type z = catalog().type("zombie");
        Map<String, Double> with = SlayerMath.chances(z, 5, 9, 0, 0, null, 1);
        Map<String, Double> without = SlayerMath.chances(z, 5, 9, 0, 0, null, 1, e -> !e.shard);
        assertTrue(with.containsKey("SHARD_REVENANT"));
        assertFalse(without.containsKey("SHARD_REVENANT"));
        assertFalse(without.containsKey("SHARD_PARAGON"));
        double sum = 0;
        for (double v : without.values()) sum += v;
        assertEquals(1.0, sum, E);
        assertTrue(without.get("FOUL_FLESH") > with.get("FOUL_FLESH"), "the freed share goes back to the pool");
    }

    /** The Primordial belt: 10% + the Cocoon Chance attribute, capped at 100%, never on a hatched monster. */
    @Test
    void primordialCocoonChance() {
        assertEquals(0.10, SlayerMath.cocoonChance(true, 10, 0, false), E);
        assertEquals(0.15, SlayerMath.cocoonChance(true, 10, 5, false), E);       // Primordial shard level 10 = +5%
        assertEquals(0, SlayerMath.cocoonChance(false, 10, 5, false), E);
        assertEquals(0, SlayerMath.cocoonChance(true, 10, 5, true), E);
        assertEquals(1.0, SlayerMath.cocoonChance(true, 90, 50, false), E);
        assertEquals(0, SlayerMath.cocoonChance(true, -5, -5, false), E);
    }

    /** Paragon's Slayer Discount stacks after the mayor; Wolverine's Slayer Progress never fills the bar. */
    @Test
    void huntingAttributesOnQuests() {
        assertEquals(100_000, SlayerMath.cost(100_000, 0, 0), E);
        assertEquals(95_000, SlayerMath.cost(100_000, 0, 5), E);
        assertEquals(47_500, SlayerMath.cost(100_000, 50, 5), E);
        assertEquals(0, SlayerMath.cost(100_000, 100, 5), E);
        assertEquals(SlayerMath.cost(7_500, 50), SlayerMath.cost(7_500, 50, 0), E);   // same as before with no attribute
        assertEquals(0, SlayerMath.startingProgress(2_400, 0), E);
        assertEquals(240, SlayerMath.startingProgress(2_400, 10), E);
        assertEquals(2_399, SlayerMath.startingProgress(2_400, 100), E);
        assertEquals(0, SlayerMath.startingProgress(1, 50), E);
    }

    /** Vivid Darkness +200 Ferocity vs endermen: the strikes it adds on top of the player's own, under the cap. */
    @Test
    void bonusFerocityStrikes() {
        assertEquals(2, SlayerMath.bonusFerocityStrikes(0, 200, 500, 0.99));
        assertEquals(2, SlayerMath.bonusFerocityStrikes(150, 200, 500, 0.99));
        assertEquals(1, SlayerMath.bonusFerocityStrikes(350, 200, 500, 0.6));      // 550 capped at 500: +150 -> 1 + 50%
        assertEquals(2, SlayerMath.bonusFerocityStrikes(350, 200, 500, 0.49));
        assertEquals(0, SlayerMath.bonusFerocityStrikes(600, 200, 500, 0.0));      // already capped
        assertEquals(0, SlayerMath.bonusFerocityStrikes(0, 0, 500, 0.0));
    }

    /**
     * Fire Pillars: this module counts one hit per strike and the pets module doubles it for a Wisp (Coldfused folded
     * in). The old Coldfused x2 here stacked on top of pets' x2 and broke a pillar in 2 strikes instead of 4.
     * Coldfused's break bonus (+30 True Defense, x1.15 damage, 60 s) needs the weapon AND the Wisp.
     */
    @Test
    void firePillarIsNotDoubledTwice() {
        assertEquals(1, SlayerMath.PILLAR_HITS_PER_STRIKE);
        int petsWispMultiplier = 2;                          // pets: Drophammer / Icehammer, max(mult, coldfused ? 2 : 1)
        int strikesToBreak = (int) Math.ceil(8.0 / (SlayerMath.PILLAR_HITS_PER_STRIKE * petsWispMultiplier));
        assertEquals(4, strikesToBreak);                     // "twice as fast" = 4 strikes, not 2
        assertTrue(SlayerMath.coldfusedBreakBonus(true, true));
        assertFalse(SlayerMath.coldfusedBreakBonus(true, false));
        assertFalse(SlayerMath.coldfusedBreakBonus(false, true));
    }

    /**
     * Accessories request 3: every slayer mob names its monster families in extra.mobTypes (accessories then skips
     * entity inference), so an Egg Sac (a SLIME) is an arthropod and a Blood Gargoyle (a VEX) undead.
     */
    @Test
    void everySlayerMobNamesItsFamilies() throws Exception {
        Map<String, Set<String>> fam = new java.util.HashMap<>();
        for (JsonElement e : array("mobs.json")) {
            JsonObject m = e.getAsJsonObject();
            JsonObject extra = m.getAsJsonObject("extra");
            assertNotNull(extra, m.get("id").getAsString());
            assertTrue(extra.has("mobTypes"), m.get("id") + " has no mobTypes");
            Set<String> s = new HashSet<>();
            for (JsonElement t : extra.getAsJsonArray("mobTypes")) s.add(t.getAsString());
            assertFalse(s.isEmpty(), m.get("id") + " mobTypes empty");
            assertFalse(s.contains("egg_sac") || s.contains("enderman"), m.get("id") + " carries a non-family tag");
            fam.put(m.get("id").getAsString(), s);
        }
        assertTrue(fam.get("slayer_egg_sac").contains("arthropod"));
        assertTrue(fam.get("slayer_clotgoyle").contains("undead"));
        assertTrue(fam.get("slayer_zombie_5").contains("undead"));
        assertTrue(fam.get("slayer_vampire_1").contains("undead"));
        assertTrue(fam.get("slayer_enderman_4").contains("ender"));
        assertTrue(fam.get("slayer_blaze_4").contains("infernal"));
        assertTrue(fam.get("slayer_wolf_4").contains("animal"));
        assertTrue(fam.get("slayer_mini_mutant_tarantula").contains("arthropod"));
    }

    /** Tarantula pet, Webbed Cells: Paralytic Venom's Vitality reduction is a% less effective. */
    @Test
    void webbedCellsWeakensVenomVitality() {
        assertEquals(52.5, SlayerMath.venomVitality(75, 30), E);    // T5 venom, legendary pet at its 30%
        assertEquals(75, SlayerMath.venomVitality(75, 0), E);
        assertEquals(0, SlayerMath.venomVitality(75, 150), E);      // clamped
        assertEquals(15, SlayerMath.venomVitality(15, -5), E);
        assertEquals(0, SlayerMath.venomVitality(SlayerMath.venom(1)[0], 30), E);
    }

    /** Mixin text: Wolf Fur is one-hit Magic Find, End Portal Fumes is Overflow, Last Horizon names its cost. */
    @Test
    void wave5ItemTextIsTheBehaviour() throws Exception {
        String all = array("items.json").toString();
        assertTrue(all.contains("one hit"), "Wolf Fur Mixin says one-hit kills");
        assertTrue(all.contains("Overflow Mana"), "End Portal Fumes says Overflow");
        assertFalse(all.contains("Gain &a+30 &b✎ Intelligence&7 for"), "the old End Portal Fumes stand-in is gone");
        assertTrue(all.contains("Soulstream &7per &a5s"), "Vivid Darkness names its Soulstream cost");
    }

    /**
     * Real-game audit 2026-10-06 (docs/AUDIT_REAL_GAME_2026-10-06.md rows C1-C8; SkyblockMoney/work/combat.md and
     * research 04 2.5-2.9), run against the SHIPPED json.
     */
    @Test
    void realGameAudit20261006() throws Exception {
        SlayerCatalog c = catalog();
        SlayerCatalog.Type z = c.type("zombie"), s = c.type("spider"), e = c.type("enderman"), b = c.type("blaze");
        // C1: ERE Artifact Upgrader from Enderman 6 (rolls at 6, not at 5)
        assertEquals(6, e.lootEntry("EXCEEDINGLY_RARE_ENDER_ARTIFACT_UPGRADER").minLevel);
        assertTrue(SlayerMath.chances(e, 4, 6, 0, 0, null, 1).containsKey("EXCEEDINGLY_RARE_ENDER_ARTIFACT_UPGRADER"));
        assertFalse(SlayerMath.chances(e, 4, 5, 0, 0, null, 1).containsKey("EXCEEDINGLY_RARE_ENDER_ARTIFACT_UPGRADER"));
        // C2: Paragon 0.25% every T4, 0.5% every T5, slayer 5, own pool (not in the weighted pick), none at Inferno T3
        for (SlayerCatalog.Type t : c.types) {
            if (t.id.equals("vampire")) continue;
            SlayerCatalog.LootEntry p = t.lootEntry("SHARD_PARAGON");
            assertTrue(p.independent, t.id);
            assertEquals(5, p.minLevel, t.id);
            for (int i = 1; i <= t.maxTier(); i++) assertEquals(i == 4 ? 0.25 : i == 5 ? 0.5 : 0, p.chanceAt(i), E, t.id + " T" + i);
            assertFalse(SlayerMath.chances(t, t.maxTier(), 9, 0, 0, null, 1).containsKey("SHARD_PARAGON"), t.id);
        }
        SlayerCatalog.LootEntry para = z.lootEntry("SHARD_PARAGON");
        assertEquals(0.005, SlayerMath.independentChance(para, 5, 9, 0), E);
        assertEquals(0, SlayerMath.independentChance(para, 5, 4, 0), E);                       // below Zombie 5
        assertEquals(0.01, SlayerMath.independentChance(para, 5, 9, 100, 0, 1), E);              // +100% MF (under 5%)
        assertEquals(0.015, SlayerMath.independentChance(para, 5, 9, 0, 0, 3), E);               // full meter, x3
        // the own pool leaves the weighted odds alone: Ghoul T4 Foul Flesh still 16.22%
        assertEquals(16.22, SlayerMath.chances(z, 4, 9, 0, 0, null, 1).get("FOUL_FLESH") * 100, 1e-6);
        // C3: only Gabagool (4.87% x10-14 at T3, Blaze 3) reaches the summoner; the other four are lootshare only
        Map<String, Double> own = SlayerMath.chances(b, 3, 9, 0, 0, null, 1);
        Map<String, Double> share = SlayerMath.chances(b, 3, 9, 0, 0, null, 1, x -> true, true);
        assertEquals(4.87, own.get("CRUDE_GABAGOOL_DISTILLATE") * 100, 1e-6);
        assertEquals(10, b.lootEntry("CRUDE_GABAGOOL_DISTILLATE").minAt(3));
        assertEquals(14, b.lootEntry("CRUDE_GABAGOOL_DISTILLATE").maxAt(3));
        assertEquals(3, b.lootEntry("CRUDE_GABAGOOL_DISTILLATE").minLevel);
        for (String d : List.of("BLAZE_ROD_DISTILLATE", "GLOWSTONE_DUST_DISTILLATE", "MAGMA_CREAM_DISTILLATE", "NETHER_STALK_DISTILLATE")) {
            assertTrue(b.lootEntry(d).lootshareOnly, d);
            assertFalse(own.containsKey(d), d + " must not drop to the summoner");
            assertTrue(share.containsKey(d), d + " must be in the lootshare roll");
        }
        double sum = 0;
        for (double v : own.values()) sum += v;
        assertEquals(1.0, sum, E);
        // Blaze 4 unlocks Archfiend Dice 1.075% + Scorched Power Crystal 3.226% at T3 (unchanged)
        assertEquals(1.075, own.get("ARCHFIEND_DICE") * 100, 1e-6);
        assertEquals(3.226, own.get("SCORCHED_POWER_CRYSTAL") * 100, 1e-6);
        assertFalse(SlayerMath.chances(b, 3, 3, 0, 0, null, 1).containsKey("ARCHFIEND_DICE"));
        // C4: the meter ramps the selected drop 1x -> 3x at the shipped dial default (2)
        assertEquals(3.0, SlayerMath.meterMultiplier(1.0, 2.0), E);
        // C5: Seraph T4 Summoning Eye 0.565%, Twilight Arrow Poison 12.7% x60-64 (62 on average)
        Map<String, Double> e4 = SlayerMath.chances(e, 4, 9, 0, 0, null, 1);
        assertEquals(0.565, e4.get("SUMMONING_EYE") * 100, 1e-6);
        assertEquals(12.7, e4.get("TWILIGHT_ARROW_POISON") * 100, 1e-6);
        assertEquals(0.0565, e4.get("JUDGEMENT_CORE") * 100, 1e-6);
        // C6: Broodweaver T4 webs 32-48 (40 a kill)
        assertEquals(32, s.lootEntry("TARANTULA_WEB").minAt(4));
        assertEquals(48, s.lootEntry("TARANTULA_WEB").maxAt(4));
        // C7: Ghoul T5 Foul Flesh 13.6% x3-4, the same weight as Viscera (x1-2)
        Map<String, Double> z5 = SlayerMath.chances(z, 5, 9, 0, 0, null, 1);
        assertEquals(13.6082, z5.get("FOUL_FLESH") * 100, 1e-6);
        assertEquals(z5.get("REVENANT_VISCERA"), z5.get("FOUL_FLESH"), E);
        assertEquals(2, z.lootEntry("REVENANT_VISCERA").maxAt(5));
        // C8: the reference renamed it Shredded Sinew, so ours is not called that
        for (JsonElement x : array("items.json")) {
            JsonObject o = x.getAsJsonObject();
            if (o.get("id").getAsString().equals("SHARD_OF_THE_SHREDDED")) assertEquals("Shredded Tendon", o.get("name").getAsString());
        }
    }
}
