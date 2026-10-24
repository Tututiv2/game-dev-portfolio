package com.skyclone.mod.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.world.gen.Box;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wave-4 playtest: a 600 HP / 630 Defense Ghoul-slayer grinder died three times in six minutes in the Crypt
 * (2M, 1M, 500k coins) because the Lv60 Golden Ghoul (800 a hit) walked the main hall where the Crypt Ghouls
 * are farmed. These pin the rule that makes it opt-in: its own vault, and no fight outside it unless you start one.
 */
class GoldenVaultTest {

    @Test
    void fightsOnlyInTheVaultOrWhenHit() {
        assertTrue(GoldenVault.mayFight(true, true, false), "in the vault");
        assertTrue(GoldenVault.mayFight(true, false, true), "outside, but you hit it");
        assertFalse(GoldenVault.mayFight(true, false, false), "outside and left alone: the grind hall");
        assertTrue(GoldenVault.mayFight(false, false, false), "dial off = the old behaviour");
    }

    @Test
    void theDoorwayCountsAsTheVault() {
        Box v = Box.of(195, 60, -67, 205, 62, -57);
        assertTrue(GoldenVault.inVault(v, 200.5, 60, -62.5));
        assertTrue(GoldenVault.inVault(v, 200.5, 60, -55.5), "within the margin, in the doorway");
        assertFalse(GoldenVault.inVault(v, 200.5, 60, -53.5), "three blocks down the corridor");
        assertFalse(GoldenVault.inVault(v, 200.5, 60, -7.5), "the main hall");
        assertTrue(GoldenVault.inVault(null, 0, 0, 0), "no vault in the layout (old build): no restriction");
    }

    @Test
    void cryptTipSaysWhatADeathCostsAndWhereTheVaultIs() {
        String rich = String.join("\n", GoldenVault.cryptTipLines(4_000_000, 3));
        assertTrue(rich.contains("half your purse") && rich.contains("2,000,000"), rich);
        assertTrue(rich.contains("Banker") && rich.contains("Golden Ghoul") && rich.contains("vault"), rich);
        assertTrue(rich.contains("at most 3"), rich);
        String broke = String.join("\n", GoldenVault.cryptTipLines(10, 0));
        assertFalse(broke.contains("half your purse"), "nothing to bank: " + broke);
        assertTrue(broke.contains("Golden Ghoul"), "the vault is always worth saying");
        assertFalse(broke.contains("at most"), "cap 0 = no limit, so no number: " + broke);
        String warn = String.join("\n", GoldenVault.vaultLines(1_000_000));
        assertTrue(warn.contains("500,000") && warn.contains("800 damage"), warn);
        for (String l : GoldenVault.cryptTipLines(4_000_000, 3)) assertFalse(l.indexOf('§') >= 0, "legacy '&' codes only: " + l);
    }

    @Test
    void theGoldenGhoulSpawnsInItsVaultAndCarriesTheRule() throws Exception {
        try (InputStream in = GoldenVaultTest.class.getResourceAsStream("/data/hub/spawns.json")) {
            assertNotNull(in);
            JsonArray arr = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
            int found = 0;
            for (JsonElement el : arr) {
                JsonObject r = el.getAsJsonObject();
                if (!"hub_golden_ghoul".equals(r.get("mob").getAsString())) continue;
                found++;
                assertEquals(GoldenVault.AREA, r.get("area").getAsString(), "the Golden Ghoul spawns in the vault only");
                assertEquals(1, r.get("max").getAsInt());
            }
            assertEquals(1, found);
        }
        try (InputStream in = GoldenVaultTest.class.getResourceAsStream("/data/hub/mobs.json")) {
            assertNotNull(in);
            JsonArray arr = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
            boolean seen = false;
            for (JsonElement el : arr) {
                JsonObject m = el.getAsJsonObject();
                String id = m.get("id").getAsString();
                String b = m.has("behaviours") ? m.get("behaviours").toString() : "";
                if (id.equals("hub_golden_ghoul")) {
                    seen = true;
                    assertTrue(b.contains("\"" + GoldenVault.ID + "\""), "golden ghoul lacks " + GoldenVault.ID);
                    // the reference's numbers are kept: the fix is where it lives, not how hard it hits
                    assertEquals(800, m.get("damage").getAsInt());
                    assertEquals(45000, m.get("health").getAsInt());
                } else {
                    assertFalse(b.contains("\"" + GoldenVault.ID + "\""), id + " should not keep to the Golden Ghoul's vault");
                }
            }
            assertTrue(seen);
        }
    }
}
