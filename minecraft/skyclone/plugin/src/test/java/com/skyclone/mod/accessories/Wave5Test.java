package com.skyclone.mod.accessories;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.ItemStatSheet;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.stat.Stat;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wave 5: the effects that core's new hooks (Master Mode, calendar events, per-target Magic Find, Abiphone contacts,
 * DungeonXpGain's source) and the mining module's block table made buildable here, checked against the shipped data
 * and the pure helpers, with no server.
 */
class Wave5Test {

    private static final Map<String, JsonObject> BY_ID = new HashMap<>();

    private static Map<String, JsonObject> all() throws Exception {
        if (!BY_ID.isEmpty()) return BY_ID;
        for (String f : new String[]{"items.json", "equipment.json"}) {
            try (InputStream in = Wave5Test.class.getClassLoader().getResourceAsStream("data/accessories/" + f)) {
                assertNotNull(in, f);
                JsonArray arr = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
                for (JsonElement e : arr) BY_ID.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
            }
        }
        return BY_ID;
    }

    private static List<Fx> fx(String id) throws Exception {
        JsonObject o = all().get(id);
        assertNotNull(o, id + " is shipped");
        List<Fx> flat = new ArrayList<>();
        for (Fx f : Fx.parseAll(o.getAsJsonObject("extra"), id)) f.flatten(flat);
        for (Fx f : flat) assertTrue(f.known && f.when.valid, id + " " + f.type + " parses");
        return flat;
    }

    private static Fx first(String id, String type) throws Exception {
        for (Fx f : fx(id)) if (f.type.equals(type)) return f;
        throw new AssertionError(id + " has no " + type);
    }

    private static boolean hooked(String id) throws Exception {
        JsonObject ex = all().get(id).getAsJsonObject("extra");
        return ex != null && ex.has("hook");
    }

    /** A Cond.Ctx with every fact settable. */
    private static final class Ctx implements Cond.Ctx {
        boolean dungeon, master;
        String island, mining;
        Set<String> events = Set.of();
        @Override public boolean night() { return false; }
        @Override public boolean burning() { return false; }
        @Override public boolean inDungeon() { return dungeon; }
        @Override public String island() { return island; }
        @Override public String zone() { return null; }
        @Override public int season() { return 0; }
        @Override public boolean masterMode() { return master; }
        @Override public boolean event(String id) { return events.contains(id); }
        @Override public String miningBlock() { return mining; }
    }

    @Test
    void masterSkullsNeedAMasterModeRun() throws Exception {
        Fx skull = first("MASTER_SKULL_TIER_7", "stat_pct");
        assertEquals(10, skull.pct);
        Ctx c = new Ctx();
        c.dungeon = true;
        assertFalse(skull.when.test(c), "a normal floor is not Master Mode");
        c.master = true;
        assertTrue(skull.when.test(c));
        c.dungeon = false;
        assertFalse(skull.when.test(c), "master needs to be inside the dungeon too");
    }

    @Test
    void eventConditionsFollowTheCalendar() throws Exception {
        Fx zorro = first("ZORROS_CAPE", "equip_mult");
        Ctx c = new Ctx();
        assertFalse(zorro.when.test(c));
        c.events = Set.of("farming_contest");
        assertTrue(zorro.when.test(c));
        Fx myth = first("MYTHOS_CLOAK", "equip_mult");
        assertEquals("hub", myth.island);
        c.events = Set.of("mythological_ritual");
        assertTrue(myth.when.test(c));
    }

    @Test
    void zorrosCapeNeedsTheMaskedHopper() throws Exception {
        JsonArray req = all().get("ZORROS_CAPE").getAsJsonArray("requirements");
        assertNotNull(req);
        assertTrue(req.toString().contains("chocolate:rabbit:zorro"), req.toString());
    }

    @Test
    void miningPiecesApplyOnlyWhileDiggingTheirBlock() throws Exception {
        Ctx c = new Ctx();
        Fx amber = first("AMBER_NECKLACE", "stat_if");
        assertEquals(Stat.MINING_FORTUNE, amber.stat);
        assertEquals(30, amber.value);
        assertFalse(amber.when.test(c));
        c.mining = "gem_jade";
        assertFalse(amber.when.test(c));
        c.mining = "gem_amber";
        assertTrue(amber.when.test(c));

        Fx mith = first("MITHRIL_GAUNTLET", "stat_if");
        assertEquals(5, mith.value);
        c.mining = "mithril_blue";
        assertTrue(mith.when.test(c));
        c.mining = "titanium";
        assertFalse(mith.when.test(c), "mithril pieces ignore titanium");
        Fx tita = first("TITANIUM_NECKLACE", "stat_if");
        assertEquals(10, tita.value);
        assertTrue(tita.when.test(c));
        c.mining = "mithril_gray";
        assertTrue(tita.when.test(c));

        // the set bonus is still the island one, and the old hook is gone
        for (String id : new String[]{"AMBER_NECKLACE", "AMETHYST_GAUNTLET", "JADE_BELT", "SAPPHIRE_CLOAK", "MITHRIL_BELT",
                "TITANIUM_BELT", "DIVAN_PENDANT", "ORGAN_DONOR_ARTIFACT", "POWER_RELIC"}) {
            assertFalse(hooked(id), id + " no longer waits on the mining module");
        }
    }

    @Test
    void divanPendantInsideMineshaftsOnly() throws Exception {
        List<Fx> d = fx("DIVAN_PENDANT");
        Fx pristine = null, spread = null;
        for (Fx f : d) {
            if (f.stat == Stat.PRISTINE) pristine = f;
            if (f.stat == Stat.MINING_SPREAD) spread = f;
        }
        assertNotNull(pristine);
        assertNotNull(spread);
        assertEquals(0.25, pristine.value);
        assertEquals(2.5, spread.value);
        Ctx c = new Ctx();
        c.island = "mineshaft";
        assertTrue(pristine.when.test(c));
        assertFalse(spread.when.test(c), "spread needs a gemstone being dug");
        c.mining = "gem_onyx";
        assertTrue(spread.when.test(c));
        c.island = "crystal_hollows";
        assertFalse(pristine.when.test(c));
        assertFalse(spread.when.test(c));
    }

    @Test
    void mineTargetsResolveTheMiningModulesRealTable() {
        MineTargets mt = MineTargets.load(Wave5Test.class.getClassLoader());
        assertTrue(mt.size() > 20, "mining's blocks.json is on the classpath");
        assertEquals("gem_amber", mt.resolve("crystal_hollows", "ORANGE_STAINED_GLASS"));
        assertEquals("gem_amber", mt.resolve("mineshaft", "orange_stained_glass_pane"));
        assertEquals("titanium", mt.resolve("dwarven_mines", "POLISHED_DIORITE"));
        assertEquals("mithril_blue", mt.resolve("dwarven_mines", "LIGHT_BLUE_WOOL"));
        // the same Material is a different block on a different island
        assertEquals("hard_stone", mt.resolve("crystal_hollows", "STONE"));
        assertEquals("stone", mt.resolve("hub", "STONE"));
        // unknown island: the first block with that Material; unknown Material: nothing
        assertNotNull(mt.resolve(null, "POLISHED_DIORITE"));
        assertNull(mt.resolve("hub", "BEDROCK"));
        // every id a shipped condition names exists in the table
        for (String ids : new String[]{"gem_amber", "gem_amethyst", "gem_jade", "gem_sapphire", "mithril_gray",
                "mithril_prismarine", "mithril_blue", "titanium", "gem_onyx", "gem_peridot"}) {
            boolean found = false;
            for (String isl : new String[]{"crystal_hollows", "dwarven_mines", "mineshaft", "glacite_tunnels"}) {
                for (String m : new String[]{"ORANGE_STAINED_GLASS", "PURPLE_STAINED_GLASS", "LIME_STAINED_GLASS",
                        "LIGHT_BLUE_STAINED_GLASS", "GRAY_WOOL", "PRISMARINE", "LIGHT_BLUE_WOOL", "POLISHED_DIORITE",
                        "BLACK_STAINED_GLASS", "GREEN_STAINED_GLASS"}) {
                    found |= ids.equals(mt.resolve(isl, m));
                }
            }
            assertTrue(found, ids + " is a block id the mining table can produce");
        }
    }

    @Test
    void digWindowLapses() {
        EffectEngine.MineHit h = new EffectEngine.MineHit("titanium", 1_000);
        assertEquals("titanium", EffectEngine.activeMineBlock(h, 1_000, 3_000));
        assertEquals("titanium", EffectEngine.activeMineBlock(h, 4_000, 3_000));
        assertNull(EffectEngine.activeMineBlock(h, 4_001, 3_000));
        assertNull(EffectEngine.activeMineBlock(h, 999, 3_000), "a clock that went backwards does not count");
        assertNull(EffectEngine.activeMineBlock(null, 1_000, 3_000));
        assertNull(EffectEngine.activeMineBlock(new EffectEngine.MineHit(null, 1_000), 1_000, 3_000));
    }

    @Test
    void organDonorDingsForFrozenCorpsesOnly() throws Exception {
        for (String id : new String[]{"ORGAN_DONOR_TALISMAN", "ORGAN_DONOR_RING", "ORGAN_DONOR_ARTIFACT"}) {
            assertEquals(20, first(id, "corpse_ding").range);
        }
        // the mining module's names (Tunnels.corpseName, colour codes stripped)
        for (String n : new String[]{"Frozen Lapis Corpse", "Frozen Umber Corpse", "Frozen Tungsten Corpse", "Frozen Vanguard Corpse"}) {
            assertTrue(EffectEngine.isCorpseName(n), n);
        }
        assertFalse(EffectEngine.isCorpseName("Corpse"));
        assertFalse(EffectEngine.isCorpseName("Frozen Steve"));
        assertFalse(EffectEngine.isCorpseName(null));
    }

    @Test
    void powerTalismanGemsAtHalfPower() throws Exception {
        for (String id : new String[]{"POWER_TALISMAN", "POWER_RING", "POWER_ARTIFACT", "POWER_RELIC"}) {
            assertEquals(0.5, all().get(id).getAsJsonObject("extra").get("gemPowerMult").getAsDouble(), id);
        }
        ItemDef def = ItemDef.of("POWER_RING", "Power Ring", "PLAYER_HEAD", Rarity.UNCOMMON, ItemCategory.ACCESSORY);
        def.extra.addProperty("gemPowerMult", 0.5);
        ItemStatSheet sh = new ItemStatSheet();
        sh.add(Stat.STRENGTH, 4, ItemStatSheet.Source.BASE);
        sh.add(Stat.STRENGTH, 10, ItemStatSheet.Source.GEMSTONE);
        sh.add(Stat.CRIT_DAMAGE, 6, ItemStatSheet.Source.GEMSTONE);
        ItemHooks.scaleGems(def, sh);
        assertEquals(9, sh.total(Stat.STRENGTH), 1e-9);
        assertEquals(5, sh.part(Stat.STRENGTH, ItemStatSheet.Source.GEMSTONE), 1e-9);
        assertEquals(3, sh.total(Stat.CRIT_DAMAGE), 1e-9);
        // anything without the key is untouched
        ItemDef plain = ItemDef.of("X", "X", "STONE", Rarity.COMMON, ItemCategory.ACCESSORY);
        ItemStatSheet sh2 = new ItemStatSheet();
        sh2.add(Stat.STRENGTH, 10, ItemStatSheet.Source.GEMSTONE);
        ItemHooks.scaleGems(plain, sh2);
        assertEquals(10, sh2.total(Stat.STRENGTH), 1e-9);
    }

    @Test
    void hocusPocusCarriesItsRiftStats() throws Exception {
        JsonObject rs = all().get("HOCUS_POCUS_CIPHER").getAsJsonObject("extra").getAsJsonObject("riftStats");
        assertNotNull(rs, "the rift module reads extra.riftStats of bag accessories");
        assertEquals(65, rs.get("RIFT_TIME").getAsDouble());
        assertEquals(5, rs.get("RIFT_INTELLIGENCE").getAsDouble());
        assertEquals(5, rs.get("RIFT_MANA_REGEN").getAsDouble());
    }

    @Test
    void perTargetMagicFindAndHuntingFortune() throws Exception {
        Fx fleece = first("GOLDEN_FLEECE", "mf_vs");
        assertEquals("mythological", fleece.mob);
        assertEquals(5, fleece.value);
        JsonObject core = all().get("INFERNAL_KUUDRA_CORE");
        if (core != null) {
            assertTrue(core.getAsJsonObject("stats").has("HUNTING_FORTUNE"), "Hunter Fortune is core's Hunting Fortune now");
        }
        for (JsonObject o : all().values()) {
            JsonObject ex = o.getAsJsonObject("extra");
            if (ex != null && ex.has("otherStats")) {
                assertFalse(ex.getAsJsonObject("otherStats").has("HUNTER_FORTUNE"), o.get("id") + " still files Hunter Fortune as lore");
            }
        }
    }

    @Test
    void wave5Arithmetic() {
        assertEquals(4, AccMath.abicaseMp(9, 2));
        assertEquals(0, AccMath.abicaseMp(1, 2));
        assertEquals(0, AccMath.abicaseMp(10, 0));
        assertEquals(37, AccMath.countByThreshold(n -> n <= 37, 256));
        assertEquals(0, AccMath.countByThreshold(n -> false, 256));
        assertEquals(256, AccMath.countByThreshold(n -> true, 256));
        assertEquals(0, AccMath.runicSteps(0, 4));
        assertEquals(1, AccMath.runicSteps(9, 4));
        assertEquals(4, AccMath.runicSteps(123_456, 4));
        assertEquals(101, AccMath.fireTakenMult(10_000, 0, 0), 1e-9);
        assertEquals(1.8, AccMath.fireTakenMult(0, 8, 10), 1e-9);
        assertEquals(4, AccMath.bats(true, 2, 1));
        assertEquals(0, AccMath.bats(false, 0, -3));
        assertEquals(2, KeepBags.distinct(List.of("A", "A", "B", "C"), Set.of("C")));
    }

    @Test
    void adminDungeonXpIsNeverBoosted() throws Exception {
        DungeonXpHook.Probe admin = new DungeonXpHook.Probe("catacombs", 1000);
        admin.source = "admin";
        DungeonXpHook.scale(admin, (p, cls) -> 10);
        assertEquals(1000, admin.amount, 1e-9);
        DungeonXpHook.Probe run = new DungeonXpHook.Probe("catacombs", 1000);
        DungeonXpHook.scale(run, (p, cls) -> 10);
        assertEquals(1100, run.amount, 1e-9);
    }
}
