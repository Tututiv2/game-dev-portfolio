package com.skyclone.mod.armor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wave 5, second pass (2026-10-06): behaviour gaps found by diffing our bonus text against the reference
 * lore - Magma Lord's necklace tier, per-target Magic Find, Peace Treaty, Bat Swarm, Nether Lord's obsidian,
 * Old Blood on Feather Falling, and the Great Spook Staff.
 */
class ArmorWave5bTest {

    // ---- formulas --------------------------------------------------------------------------

    @Test void magmaLordNecklaceIsTheFifthPiece() {
        assertEquals(5, SetMath.magmaLordPieces(4, true));
        assertEquals(4, SetMath.magmaLordPieces(4, false));
        assertEquals(2, SetMath.magmaLordPieces(1, true));   // one armour piece + necklace = Fireproof
        assertEquals(0, SetMath.magmaLordPieces(0, true));   // a necklace alone is not the set
        assertEquals(5, SetMath.magmaLordNeed(5, true));
        assertEquals(4, SetMath.magmaLordNeed(5, false));    // nobody registers the necklace: armour alone still reaches it
        assertEquals(1, SetMath.magmaLordNeed(0, true));
    }

    @Test void magicFindVsOnlyTheRightTargets() {
        // research 06: Lord's Blessing +20 vs magmatic; Crown of Greed +10 vs mythological; Familiarity per Mythos piece
        assertEquals(20, SetMath.magicFindVs(true, false, true, 0, false, 20, 2.5, 10), 1e-9);
        assertEquals(0, SetMath.magicFindVs(true, false, false, 0, false, 20, 2.5, 10), 1e-9);
        assertEquals(20, SetMath.magicFindVs(false, true, false, 4, true, 20, 2.5, 10), 1e-9);
        assertEquals(5, SetMath.magicFindVs(false, true, false, 2, false, 20, 2.5, 10), 1e-9);
        assertEquals(0, SetMath.magicFindVs(false, false, true, 4, true, 20, 2.5, 10), 1e-9);
        assertEquals(30, SetMath.magicFindVs(true, true, true, 0, true, 20, 2.5, 10), 1e-9);
    }

    @Test void peaceTreatyNeedsTwoOfOneSet() {
        assertTrue(SetMath.peaceTreaty(2, 2, 0, 0, 0));
        assertTrue(SetMath.peaceTreaty(2, 0, 0, 0, 4));
        assertFalse(SetMath.peaceTreaty(2, 1, 1, 0, 0));       // mixed sets do not add up
        assertFalse(SetMath.peaceTreaty(0, 4, 4, 4, 4));       // dial 0 = off
        assertFalse(SetMath.peaceTreaty(2));
    }

    @Test void batsHomeInAndComeBack() {
        double[] p = SetMath.batStep(new double[]{0, 0, 0}, new double[]{3, 4, 0}, 1);
        assertEquals(0.6, p[0], 1e-9);
        assertEquals(0.8, p[1], 1e-9);
        double[] land = SetMath.batStep(new double[]{2.9, 4, 0}, new double[]{3, 4, 0}, 1);
        assertArrayEquals(new double[]{3, 4, 0}, land, 0);
        assertArrayEquals(new double[]{1, 1, 1}, SetMath.batStep(new double[]{1, 1, 1}, new double[]{1, 1, 1}, 1), 0);
        assertTrue(SetMath.batReady(0, 0, 10));
        assertFalse(SetMath.batReady(1_000, 10_999, 10));
        assertTrue(SetMath.batReady(1_000, 11_000, 10));
    }

    @Test void netherLordDisc() {
        assertEquals(1, SetMath.discOffsets(0).size());
        assertEquals(5, SetMath.discOffsets(1).size());
        assertEquals(13, SetMath.discOffsets(2).size());
        for (int[] o : SetMath.discOffsets(2.5)) assertTrue(o[0] * o[0] + o[1] * o[1] <= 6.25);
        assertArrayEquals(new int[]{-5, 64, 7}, NetherLord.parse("-5,64,7"));
        assertNull(NetherLord.parse("1,2"));
        assertNull(NetherLord.parse("a,b,c"));
    }

    @Test void oldBloodFeatherFallingShare() {
        assertEquals(2.5, SetMath.oldBloodSafeFall(5, 0.5), 1e-9);
        assertEquals(0, SetMath.oldBloodSafeFall(0, 0.5), 1e-9);
        assertEquals(0, SetMath.oldBloodSafeFall(5, 0), 1e-9);
    }

    // ---- generated data -----------------------------------------------------------------------

    @Test void loreSaysWhatTheCodeDoes() throws Exception {
        Map<String, JsonObject> items = ArmorWave3Test.items();
        for (String set : List.of("BRONZE_HUNTER", "SILVER_HUNTER", "GOLD_HUNTER", "DIAMOND_HUNTER"))
            for (String slot : List.of("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"))
                assertTrue(lore(items.get(set + "_" + slot)).contains("Peace Treaty"), set + "_" + slot);
        assertTrue(lore(items.get("MAGMA_LORD_HELMET")).contains("Magma Lord Necklace"));
        assertTrue(lore(items.get("WITCH_MASK")).contains("4,000"));
        assertTrue(lore(items.get("VAMPIRE_WITCH_MASK")).contains("3,000"));
        assertFalse(lore(items.get("WITCH_MASK")).contains("decorative"), "Bat Swarm is real now");
        JsonObject spook = items.get("GREAT_SPOOK_CHESTPLATE");
        assertEquals("Fearsome", spook.get("setBonusName").getAsString(), "the first FULL bonus keeps the set-bonus slot");
        assertTrue(lore(spook).contains("Full Set Bonus: Spooky"), "the second FULL bonus is drawn in the lore");
        assertTrue(String.join(" ", strings(items.get("EMBER_HELMET"), "setBonusLore")).contains("obsidian"));
        assertTrue(String.join(" ", strings(items.get("OLD_DRAGON_HELMET"), "setBonusLore")).contains("Feather Falling"));
    }

    @Test void greatSpookStaffIsOurs() throws Exception {
        Set<String> ids = new HashSet<>();
        JsonObject staff = null;
        for (JsonElement e : ArmorPureTest.json("extras.json")) {
            JsonObject o = e.getAsJsonObject();
            ids.add(o.get("id").getAsString());
            if ("GREAT_SPOOK_STAFF".equals(o.get("id").getAsString())) staff = o;
        }
        assertNotNull(staff);
        assertEquals(10, staff.getAsJsonObject("extra").getAsJsonObject("otherStats").get("FEAR").getAsDouble(), 1e-9);
        Set<String> abil = new HashSet<>();
        for (JsonElement a : staff.getAsJsonArray("abilities")) abil.add(a.getAsJsonObject().get("id").getAsString());
        assertEquals(Set.of("armor_spooker", "armor_spook"), abil);
        // no id in extras may shadow an armour piece
        for (JsonObject it : ArmorWave3Test.items().values()) assertFalse(ids.contains(it.get("id").getAsString()));
    }

    private static String lore(JsonObject o) { return String.join(" ", strings(o, "lore")); }

    private static List<String> strings(JsonObject o, String key) {
        assertNotNull(o);
        if (!o.has(key)) return List.of();
        List<String> out = new java.util.ArrayList<>();
        for (JsonElement e : o.getAsJsonArray(key)) out.add(e.getAsString());
        return out;
    }
}
