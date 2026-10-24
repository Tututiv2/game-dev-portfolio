package com.skyclone.mod.soulflow;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.stat.Stat;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shipping data files hang together and carry the research numbers. */
class SoulflowDataFilesTest {

    private static SoulflowConfig cfg() {
        InputStream in = SoulflowDataFilesTest.class.getClassLoader().getResourceAsStream("data/soulflow/soulflow.json");
        assertNotNull(in, "data/soulflow/soulflow.json missing");
        return SoulflowConfig.read(in);
    }

    private static JsonArray items() throws Exception {
        try (InputStream in = SoulflowDataFilesTest.class.getClassLoader().getResourceAsStream("data/soulflow/items.json")) {
            assertNotNull(in, "data/soulflow/items.json missing");
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
        }
    }

    @Test
    void costsCarryTheReferenceNumbers() {
        SoulflowConfig c = cfg();
        assertEquals(1, c.costFor("weapons_ether_transmission", null, null).cost);       // Etherwarp Conduit
        assertEquals(1, c.costFor("weapons_ether_transmission_void", null, null).cost);  // AOTV + Etherwarp Merger
        assertEquals("ethermerge", c.costFor("weapons_ether_transmission_void", null, null).whenExtra);
        assertEquals(1, c.costFor("weapons_soulcry", null, null).cost);                  // Voidedge
        assertEquals(1, c.costFor("weapons_soulcry_vorpal", null, null).cost);           // Vorpal
        assertEquals(2, c.costFor("weapons_soulcry_atomsplit", null, null).cost);        // Atomsplit
        assertEquals(1, c.costFor("weapons_salvation", null, null).cost);                // Terminator
        assertTrue(c.costFor("weapons_salvation", null, null).soft());
        assertEquals(10, c.costFor("weapons_gravity_storm", null, null).cost);           // Gyrokinetic LMB
        assertEquals(2, c.costFor("weapons_cells_alignment", null, null).cost);          // Gyrokinetic RMB
        assertEquals(15, c.costFor("slayer_grimoire", null, null).cost);                 // Gloomlock Life Tap
        assertEquals(10, c.costFor("soulflow_soulward", null, null).cost);               // Soul Esoward
        assertEquals(1, c.unitsOf("RAW_SOULFLOW"));
        assertEquals(160, c.unitsOf("SOULFLOW"));
        assertEquals(0, c.unitsOf("NULL_SPHERE"));
    }

    @Test
    void itemPlusTriggerFallbackCatchesAReRegisteredAbility() {
        SoulflowConfig c = cfg();
        // a module that registers the Vorpal Katana with its own ability id is still charged
        SoulflowConfig.Cost k = c.costFor("slayer_soulcry_v2", "VORPAL_KATANA", "RIGHT_CLICK");
        assertNotNull(k);
        assertSame(c.costFor("weapons_soulcry_vorpal", null, null), k);
        // ... but not on another trigger, and not on an unrelated item
        assertNull(c.costFor("x", "VORPAL_KATANA", "LEFT_CLICK"));
        assertNull(c.costFor("weapons_instant_transmission_void", "ASPECT_OF_THE_VOID", "RIGHT_CLICK"));
        // the Gyrokinetic Wand's two triggers cost differently
        assertEquals(10, c.costFor("y", "GYROKINETIC_WAND", "LEFT_CLICK").cost);
        assertEquals(2, c.costFor("y", "GYROKINETIC_WAND", "RIGHT_CLICK").cost);
    }

    @Test
    void tiersAndDrainsAreConsistent() {
        SoulflowConfig c = cfg();
        assertEquals(3, c.tiers.size());
        assertEquals("SOULFLOW_PILE", c.tiers.get(0).item);
        assertEquals("SOULFLOW_SUPERCELL", c.tiers.get(2).item);
        assertEquals(5, c.tier("SOULFLOW_PILE").units);
        assertEquals(200, c.tier("SOULFLOW_PILE").overflow, 1e-9);
        assertEquals(400, c.tier("SOULFLOW_BATTERY").overflow, 1e-9);
        assertEquals(600, c.tier("SOULFLOW_SUPERCELL").overflow, 1e-9);
        assertEquals(30, c.overflowBoosts.get(0).percent, 1e-9);
        Set<String> ids = new HashSet<>();
        for (SoulflowConfig.Drain d : c.drains) {
            assertTrue(ids.add(d.id), "duplicate drain " + d.id);
            assertTrue(d.cost > 0 && d.every > 0, d.id);
            assertTrue(d.item != null || d.set != null, d.id + " has neither an item nor a set");
            for (String s : d.stats.keySet()) Stat.valueOf(s);   // throws on an unknown stat
        }
        assertEquals(20, c.drain("chalice").stats.get("FEROCITY"), 1e-9);
        assertEquals(100, c.drain("espresso").stats.get("INTELLIGENCE"), 1e-9);
        assertFalse(c.drain("chalice").defaultOn);                 // reference ships "Enabled: Off"
        assertEquals(2, c.drain("final_destination").cost);
        assertTrue(c.drain("final_destination").sneaking);
        for (SoulflowConfig.Cost k : c.costs) {
            assertTrue(k.cost > 0, k.ability);
            assertFalse(k.items.isEmpty(), k.ability);
            assertNotNull(k.trigger, k.ability);
        }
    }

    @Test
    void ownItemsAreRenamedAndOwned() throws Exception {
        Set<String> want = new HashSet<>(Set.of("RAW_SOULFLOW", "SOULFLOW", "SOUL_ESOWARD"));
        for (JsonElement el : items()) {
            JsonObject o = el.getAsJsonObject();
            String id = o.get("id").getAsString();
            assertTrue(want.remove(id), "unexpected item " + id);
            assertEquals("soulflow", o.getAsJsonObject("extra").get("src").getAsString(), id);
            StringBuilder shown = new StringBuilder(o.get("name").getAsString());
            if (o.has("lore")) for (JsonElement l : o.getAsJsonArray("lore")) shown.append('\n').append(l.getAsString());
            if (o.has("abilities")) for (JsonElement a : o.getAsJsonArray("abilities")) {
                shown.append('\n').append(a.getAsJsonObject().get("name").getAsString());
                for (JsonElement l : a.getAsJsonObject().getAsJsonArray("lore")) shown.append('\n').append(l.getAsString());
            }
            String low = shown.toString().toLowerCase(Locale.ROOT);
            for (String banned : SoulflowMath.REFERENCE_NAMES) assertFalse(low.contains(banned), id + " shows '" + banned + "'");
        }
        assertTrue(want.isEmpty(), "missing " + want);
    }
}
