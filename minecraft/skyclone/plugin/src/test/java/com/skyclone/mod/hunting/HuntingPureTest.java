package com.skyclone.mod.hunting;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The shipping hunting math and data, with no server: levelling, Hunting Fortune, fusion, echoes, the catalog. */
class HuntingPureTest {

    static ShardCatalog cat;

    static Path data() {
        Path p = Path.of("src/main/resources/data/hunting");
        return Files.isDirectory(p) ? p : Path.of("plugin/src/main/resources/data/hunting");
    }

    @BeforeAll static void load() throws Exception {
        try (Reader a = Files.newBufferedReader(data().resolve("shards.json"), StandardCharsets.UTF_8);
             Reader b = Files.newBufferedReader(data().resolve("habitats.json"), StandardCharsets.UTF_8)) {
            cat = ShardCatalog.load(a, b);
        }
    }

    // ---------------------------------------------------------------- levelling (reference table)

    @Test void levellingMatchesTheReferenceTable() throws Exception {
        JsonObject t = JsonParser.parseString(Files.readString(data().resolve("levelling.json"))).getAsJsonObject();
        for (int r = 0; r < HuntMath.RARITIES.length; r++) {
            var arr = t.getAsJsonArray(HuntMath.RARITIES[r]);
            assertEquals(10, arr.size());
            for (int i = 0; i < 10; i++) assertEquals(arr.get(i).getAsInt(), HuntMath.LEVELLING[r][i], HuntMath.RARITIES[r] + " step " + i);
        }
        assertEquals(96, HuntMath.maxShards("COMMON"));
        assertEquals(24, HuntMath.maxShards("LEGENDARY"));
    }

    @Test void levelFromShards() {
        assertEquals(0, HuntMath.level("COMMON", 0));
        assertEquals(1, HuntMath.level("COMMON", 1));
        assertEquals(1, HuntMath.level("COMMON", 3));
        assertEquals(2, HuntMath.level("COMMON", 4));
        assertEquals(10, HuntMath.level("COMMON", 96));
        assertEquals(10, HuntMath.level("COMMON", 5000));
        assertEquals(3, HuntMath.toNext("COMMON", 1));
        assertEquals(0, HuntMath.toNext("LEGENDARY", 24));
        assertEquals(2, HuntMath.level("EPIC", 2));
    }

    @Test void huntingFortuneEvery100IsAShard() {
        assertEquals(1, HuntMath.shardsPerCatch(0, 0));
        assertEquals(2, HuntMath.shardsPerCatch(100, 0.99));
        assertEquals(2, HuntMath.shardsPerCatch(150, 0.6));     // 50% remainder, roll above it
        assertEquals(3, HuntMath.shardsPerCatch(150, 0.4));     // roll under it
        assertEquals(1, HuntMath.shardsPerCatch(Double.NaN, 0));
    }

    @Test void captureXpIsResearch() {
        assertEquals(75, HuntMath.captureXp("COMMON"));
        assertEquals(150, HuntMath.captureXp("UNCOMMON"));
        assertEquals(300, HuntMath.captureXp("RARE"));
        assertEquals(500, HuntMath.captureXp("EPIC"));
        assertEquals(1000, HuntMath.captureXp("LEGENDARY"));
        assertEquals(2.0, HuntMath.skillCharm(50), 1e-9);   // skills data: 0.04 per level
    }

    @Test void fmtTrimsZeros() {
        assertEquals("2", HuntMath.fmt(2.0));
        assertEquals("0.5", HuntMath.fmt(0.5));
        assertEquals("1.25", HuntMath.fmt(1.25));
        assertEquals("0.05", HuntMath.fmt(0.05));
    }

    // ---------------------------------------------------------------- catalog

    @Test void catalogHasEveryReferenceShard() {
        assertEquals(322, cat.size());
        Set<String> codes = new HashSet<>();
        Set<String> attrs = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (ShardDef s : cat.all()) {
            assertTrue(s.id.startsWith("SHARD_"), s.id);
            assertTrue(codes.add(s.code), "duplicate code " + s.code);
            assertTrue(attrs.add(s.attr), "duplicate attribute " + s.attr);
            assertTrue(names.add(s.name), "two shards called " + s.name);
            assertNotNull(s.text);
            assertFalse(s.sources.isEmpty(), s.id + " has no source");
        }
        assertNotNull(cat.find("C1"));
        assertNotNull(cat.find("fishing_speed"));
        assertNotNull(cat.find("SHARD_TITANOBOA"));
        assertEquals("SHARD_GROVE", cat.find("grove").id);
    }

    @Test void referenceNamesAreRenamed() {
        // invented proper nouns of the reference must not reach players (docs/names/hunting.md)
        String[] banned = {"Hideonleaf", "Phanpyre", "Bonzo", "Scarf", "Kada Knight", "Bezal", "Nessie", "Lord Jawbus", "Wiki Tiki",
                "Barbarian Duke X", "Moonglade", "Torrhus", "Galatea", "Starlyn", "Kuudra", "Hoppity", "Grandma Wolf", "Divan"};
        for (ShardDef s : cat.all()) {
            for (String b : banned) {
                assertFalse(s.name.contains(b), s.id + " name " + s.name);
                assertFalse(s.text.contains(b), s.id + " text " + s.text);
                assertFalse(s.attrName.contains(b), s.id + " attribute " + s.attrName);
            }
        }
    }

    @Test void habitatsHoldRealCritters() {
        assertFalse(cat.habitats().isEmpty());
        for (ShardCatalog.Habitat h : cat.habitats()) {
            assertFalse(h.critters.isEmpty(), h.id + " is empty");
            for (String c : h.critters) assertNotNull(cat.byCritter(c), h.id + " lists " + c);
        }
        assertEquals(4, cat.habitats().stream().filter(h -> h.safari).count());
    }

    @Test void effectsParse() {
        Map<String, Integer> kinds = new HashMap<>();
        for (ShardDef s : cat.all()) {
            kinds.merge(s.effect.kind, 1, Integer::sum);
            if (!"hook".equals(s.effect.kind) && !"echo".equals(s.effect.kind)) assertTrue(s.effect.per > 0, s.id + " has no per-level value");
        }
        assertTrue(kinds.getOrDefault("stat", 0) >= 60, "stat effects: " + kinds);
        assertTrue(kinds.getOrDefault("damage_vs", 0) >= 15);
        assertEquals(14, kinds.getOrDefault("echo", 0));
        ShardDef grove = cat.get("SHARD_GROVE");
        assertEquals("HEALTH", grove.effect.stat);
        assertEquals(2.0, grove.effect.per, 1e-9);
        assertTrue(grove.effectText(10, 1).contains("+20"), grove.effectText(10, 1));
    }

    // ---------------------------------------------------------------- fusion

    @Test void fusionOnlyShardsCanBeMade() {
        Map<String, Set<String>> made = new HashMap<>();
        List<ShardDef> all = new ArrayList<>(cat.all());
        for (ShardDef a : all) for (ShardDef b : all) for (Fusion.Output o : Fusion.outputs(cat, a, b))
            made.computeIfAbsent(o.shardId(), k -> new HashSet<>()).add(o.kind().startsWith("family:") ? "family" : o.kind());
        for (ShardDef s : all) {
            boolean onlyFusion = s.sources.stream().allMatch(x -> "fusion".equals(x.type));
            if (onlyFusion) assertTrue(made.containsKey(s.id), s.id + " is fusion-only and no fusion makes it");
            // the upper Elementals and top Reptiles come from their FAMILY, not only from the id line
            if (s.fusionOnly && !"SHARD_CHAMELEON".equals(s.id))
                assertTrue(made.getOrDefault(s.id, Set.of()).contains("family"), s.id + " (" + s.families + ") has no family fusion");
        }
    }

    @Test void chameleonGivesTheNextThreeOnTheLine() {
        ShardDef cham = cat.get(Fusion.CHAMELEON);
        ShardDef grove = cat.get("SHARD_GROVE");   // C1
        List<Fusion.Output> outs = Fusion.outputs(cat, cham, grove);
        assertEquals(3, outs.size());
        assertEquals("SHARD_MIST", outs.get(0).shardId());   // C2
        assertEquals("SHARD_FLASH", outs.get(1).shardId());  // C3
        assertTrue(Fusion.outputs(cat, cham, cham).isEmpty());
        assertEquals(1, Fusion.cost(cham));
        assertEquals(5, Fusion.cost(grove));
    }

    @Test void sameFamilyFusesUpARarity() {
        ShardDef grove = cat.get("SHARD_GROVE"), mist = cat.get("SHARD_MIST");   // Elemental commons
        List<Fusion.Output> outs = Fusion.outputs(cat, grove, mist);
        assertTrue(outs.stream().anyMatch(o -> o.kind().equals("family:Elemental") && "UNCOMMON".equals(cat.get(o.shardId()).rarity)), outs.toString());
        // the line rule: same rarity gives two
        assertTrue(outs.stream().anyMatch(o -> o.kind().equals("line") && o.amount() == 2));
    }

    // ---------------------------------------------------------------- attributes + echo

    @Test void snapshotAppliesLevelsAndEchoes() {
        Map<String, Integer> lv = new HashMap<>();
        lv.put("nature_elemental", 10);      // Grove: +2 Health / level
        lv.put("echo_of_elemental", 5);      // Starchild: +2% per level to the other Elementals
        AttrSnapshot s = new AttrSnapshot(lv, cat, Set.of());
        assertEquals(20 * 1.10, s.value("nature_elemental"), 1e-9);
        assertTrue(s.stats.stream().anyMatch(g -> g.stat().equals("HEALTH") && Math.abs(g.value() - 22) < 1e-9));
        // disabled attributes do nothing
        AttrSnapshot off = new AttrSnapshot(lv, cat, Set.of("nature_elemental"));
        assertEquals(0, off.value("nature_elemental"));
        // echo of echoes boosts the other echo
        lv.put("echo_of_echoes", 10);        // Tiamat: +5% / level to echoes
        AttrSnapshot e = new AttrSnapshot(lv, cat, Set.of());
        assertEquals(20 * (1 + 10 * 1.5 / 100.0), e.value("nature_elemental"), 1e-9);
    }

    @Test void kindsLandInTheirBuckets() {
        Map<String, Integer> lv = new HashMap<>();
        for (ShardDef s : cat.all()) lv.put(s.attr, 1);
        AttrSnapshot s = new AttrSnapshot(lv, cat, Set.of());
        assertTrue(s.damageVs.containsKey("undead"));
        assertTrue(s.defenseVs.containsKey("undead"));
        assertTrue(s.mfVs.containsKey("undead"));
        assertTrue(s.melee > 0 && s.ranged > 0 && s.fullHp > 0 && s.lowHp > 0 && s.payback > 0);
        assertEquals(4, s.hfRarity.size() - (s.hfRarity.containsKey("LEGENDARY") ? 1 : 0));
        assertTrue(s.pseudo("charm_chance", c -> true) > 0);
        assertEquals(322, s.levels.size());
        for (double v : s.values.values()) assertTrue(Double.isFinite(v));
    }

    // ---------------------------------------------------------------- playtest wave 5 fixes

    /** "/sb hunting spawn W5New SHARD_INKLOTL" said "Unknown critter": the Inklotl's id is SHARD_SEPIALOT. */
    @Test void findAcceptsWhatPlayersSee() {
        ShardDef ink = cat.get("SHARD_SEPIALOT");
        assertNotNull(ink);
        assertEquals("Inklotl", ink.name);
        for (String q : List.of("SHARD_INKLOTL", "Inklotl", "inklotl", "INKLOTL", " Inklotl ", "&9Inklotl",
                "Bucket Greed", "bucket greed", "critter_sepialot", "SHARD_SEPIALOT", "sepialot", "bucket_greed", "U56"))
            assertSame(ink, cat.find(q), q);
        assertNull(cat.find("SHARD_NOT_A_THING"));
        assertNull(cat.find(""));
        assertNull(cat.find(null));
    }

    @Test void everyShardIsFoundByItsDisplayNameAndIdsWinOverNames() {
        Set<String> keys = new HashSet<>();
        for (ShardDef s : cat.all()) {
            assertTrue(keys.add(ShardCatalog.key(s.name)), "two shards share the name " + s.name);
            assertSame(s, cat.find(s.name), s.name);
            assertSame(s, cat.find("SHARD_" + s.name.toUpperCase().replace(' ', '_')), s.name);
            assertSame(s, cat.find(s.id), s.id);          // a name can never shadow an id
            assertSame(s, cat.find(s.attr), s.attr);
        }
        assertEquals("kingminos", ShardCatalog.key("&6King Minos"));
        assertEquals("kingminos", ShardCatalog.key("king_minos"));
    }

    /** Water critters dove 8-12 blocks and drifted 20-60 blocks out while a net reaches ~7 (playtest wave 5). */
    @Test void waterCrittersAreKeptWithinANetThrow() {
        // spawn band sits inside one throw of the shortest net (7 blocks), even one block under the surface
        assertTrue(HuntMath.WATER_SPAWN_MIN >= 2 && HuntMath.WATER_SPAWN_MAX < HuntMath.WATER_SPAWN_MIN + 5);
        assertTrue(Math.hypot(HuntMath.WATER_SPAWN_MAX, 2.6) < 7, "spawn band outside a 7-block net");
        // depth: shallow is left alone, deeper is pushed up harder, a deep dive is put back
        assertEquals(0, HuntMath.surfacePush(0, 2));
        assertEquals(0, HuntMath.surfacePush(2, 2));
        assertTrue(HuntMath.surfacePush(3, 2) > 0);
        assertTrue(HuntMath.surfacePush(5, 2) > HuntMath.surfacePush(3, 2));
        assertTrue(HuntMath.surfacePush(40, 2) <= 0.5);
        assertFalse(HuntMath.snapBack(5, 2, 0, 8));
        assertTrue(HuntMath.snapBack(10, 2, 0, 8));      // the 8-12 block dive the playtest saw
        // distance: within the leash it swims freely; past it, steered home; far past it, put back
        assertEquals(0, HuntMath.leashPull(8, 8));
        assertTrue(HuntMath.leashPull(12, 8) > 0);
        assertTrue(HuntMath.leashPull(100, 8) <= 0.4);
        assertFalse(HuntMath.snapBack(0, 2, 15, 8));
        assertTrue(HuntMath.snapBack(0, 2, 20, 8));      // the 20-60 block drift the playtest saw
    }

    // ---------------------------------------------------------------- real-game audit 2026-10-06 (C14-C16, C20)

    @Test void shellwiseAndJoydiveAreNettedAtTheMurkwaterLoch() {
        for (String id : List.of("SHARD_SHELLWISE", "SHARD_JOYDIVE")) {
            ShardDef s = cat.get(id);
            assertFalse(s.fusionOnly, id);
            ShardDef.Source c = s.sources.stream().filter(x -> "critter".equals(x.type)).findFirst().orElseThrow();
            assertEquals("net", c.method, id);
            assertTrue(c.habitats.contains("loch"), id + " " + c.habitats);   // ours: Dimwater Loch = ref. Murkwater Loch
        }
        ShardCatalog.Habitat loch = cat.habitats().stream().filter(h -> h.id.equals("loch")).findFirst().orElseThrow();
        assertTrue(loch.critters.contains("critter_shellwise") && loch.critters.contains("critter_joydive"), loch.critters.toString());
        assertEquals("EPIC", cat.get("SHARD_SHELLWISE").rarity);
    }

    @Test void krakenIsAKuudraDropNotACritter() {
        ShardDef k = cat.get("SHARD_KRAKEN");
        assertNull(k.critterMob());
        assertTrue(k.hasSource("kuudra"));
        assertFalse(k.hasSource("critter"));
        for (ShardCatalog.Habitat h : cat.habitats()) assertFalse(h.critters.contains("critter_kraken"), h.id);
    }

    @Test void paragonListsEveryT4AndT5SlayerBossWithoutCharmDrops() {
        ShardDef p = cat.get("SHARD_PARAGON");
        assertFalse(p.hasSource("mob"), "a mob source would add Charm Chance / Black Hole drops the real game has not");
        ShardDef.Source s = p.sources.stream().filter(x -> "slayer".equals(x.type)).findFirst().orElseThrow();
        assertEquals(Set.of("slayer_zombie_4", "slayer_zombie_5", "slayer_spider_4", "slayer_spider_5", "slayer_wolf_4",
                "slayer_enderman_4", "slayer_blaze_4"), new HashSet<>(s.mobs));
        assertFalse(cat.mobIds().contains("slayer_zombie_4") && cat.byMob("slayer_zombie_4").contains(p));
    }

    /** The attribute a shard grants is NEU's mapping (constants/attribute_shards.json), not the shard's own name. */
    @Test void attributeNamesFollowTheirShards() {
        Map<String, String> want = Map.of("SHARD_LUNAR_MOTH", "Lunar Power", "SHARD_FIREFLY", "Solar Power",
                "SHARD_CRICKET", "Pest Fortune", "SHARD_TERMITE", "Infiltration", "SHARD_SHELLWISE", "Shell",
                "SHARD_KRAKEN", "Vitality", "SHARD_PARAGON", "Slayer Discount", "SHARD_JOYDIVE", "Deep Diving");
        for (Map.Entry<String, String> e : want.entrySet()) assertEquals(e.getValue(), cat.get(e.getKey()).attrName, e.getKey());
        assertEquals("lunar_power", cat.get("SHARD_LUNAR_MOTH").attr);
        assertEquals("solar_power", cat.get("SHARD_FIREFLY").attr);
        assertEquals("pest_fortune", cat.get("SHARD_CRICKET").attr);
        assertEquals("infiltration", cat.get("SHARD_TERMITE").attr);
        assertEquals("Earthworm", cat.get("SHARD_TERMITE").name);   // the bazaar id is TERMITE, the creature an Earthworm
    }

    /** Follow-up 2026-10-06 (garden Request 14): both garden attributes carry a number the garden can read. */
    @Test void gardenPestAttributesHaveNumbers() {
        ShardDef cd = cat.byAttr("pest_cooldown");
        assertEquals(0.5, cd.effect.per, 1e-9, "Pest Cooldown = 0.5 s a level (garden reads value:pest_cooldown)");
        ShardDef bpc = cat.byAttr("bonus_pest_chance");
        assertEquals("stat", bpc.effect.kind);
        assertEquals("PEST_CHANCE", bpc.effect.stat, "Bonus Pest Chance reaches the garden through Stat.PEST_CHANCE");
    }
}
