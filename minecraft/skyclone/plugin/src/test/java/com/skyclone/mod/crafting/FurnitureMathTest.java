package com.skyclone.mod.crafting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.item.ItemCategory;
import org.bukkit.block.structure.StructureRotation;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wave 5: what placed furniture does (storage pages, showcase slots, stations, display orientation). */
class FurnitureMathTest {

    @Test
    void storagePagesAreClampedAndOnlyForStorage() {
        assertEquals(3, FurnitureMath.pages("storage:3"));
        assertEquals(6, FurnitureMath.pages("storage:6"));
        assertEquals(6, FurnitureMath.pages("storage:99"), "a typo in the data can never make a 99-page chest");
        assertEquals(1, FurnitureMath.pages("storage:x"));
        assertEquals(0, FurnitureMath.pages("showcase:armor"));
        assertEquals(0, FurnitureMath.pages(null));
        assertEquals("p2:44", FurnitureMath.pageKey(2, 44));
    }

    @Test
    void showcasesAcceptOnlyTheirGear() {
        assertEquals(5, FurnitureMath.showSlots("showcase:armor"));
        assertEquals(4, FurnitureMath.showSlots("showcase:weapons:4"));
        assertEquals(1, FurnitureMath.showSlots("showcase:best"));
        assertTrue(FurnitureMath.accepts("showcase:armor", 0, ItemCategory.HELMET));
        assertFalse(FurnitureMath.accepts("showcase:armor", 0, ItemCategory.BOOTS), "boots never go in the helmet slot");
        assertTrue(FurnitureMath.accepts("showcase:armor", 3, ItemCategory.BOOTS));
        assertTrue(FurnitureMath.accepts("showcase:armor", 4, ItemCategory.AXE));
        assertFalse(FurnitureMath.accepts("showcase:armor", 5, ItemCategory.SWORD), "no sixth slot");
        assertTrue(FurnitureMath.accepts("showcase:weapons:4", 2, ItemCategory.PICKAXE));
        assertFalse(FurnitureMath.accepts("showcase:weapons:4", 1, ItemCategory.MATERIAL), "a rack is not a chest");
        assertFalse(FurnitureMath.accepts("showcase:best", 0, ItemCategory.HELMET));
        assertFalse(FurnitureMath.accepts("showcase:best", 0, null));
    }

    @Test
    void stationsNameTheirWorkingBlock() {
        assertEquals("FURNACE", FurnitureMath.stationBlock("station:furnace"));
        assertEquals("ANVIL", FurnitureMath.stationBlock("station:anvil"));
        assertNull(FurnitureMath.stationBlock("storage:3"));
        assertNotNull(FurnitureMath.describe("station:brewing"));
        assertNull(FurnitureMath.describe("cosmetic"));
        assertEquals(java.util.List.of("Item Showcase", "Storage"), FurnitureMath.tags("showcase:best"));
    }

    @Test
    void displayRotationMatchesTheBlockRotation() {
        StructureRotation[] rot = {StructureRotation.NONE, StructureRotation.CLOCKWISE_90, StructureRotation.CLOCKWISE_180,
                StructureRotation.COUNTERCLOCKWISE_90};
        String[] facing = {"NORTH", "EAST", "SOUTH", "WEST"};
        for (int q = 0; q < 4; q++) {
            assertEquals(q, FurnitureMath.quarters(facing[q]));
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                int[] b = Furniture.rotate(dx, dz, rot[q]);
                double[] d = FurnitureMath.rotate(dx, dz, q);
                assertArrayEquals(new double[]{b[0], b[1]}, d, 1e-9, "q=" + q + " (" + dx + "," + dz + ")");
            }
            // the front (local +z) faces where the yaw points: yaw 0 = +z (south), 90 = -x (west)
            double[] front = FurnitureMath.rotate(0, 1, q);
            double yaw = Math.toRadians(FurnitureMath.frontYaw(facing[q]));
            assertEquals(-Math.sin(yaw), front[0], 1e-9, facing[q]);
            assertEquals(Math.cos(yaw), front[1], 1e-9, facing[q]);
        }
    }

    /** The shipped data: 12 storage pieces, 3 showcases, and every station's build holds its working block. */
    @Test
    void shippedFurnitureMatchesTheReferenceLore() throws Exception {
        Path f = null;
        for (String c : new String[]{"src/main/resources/data/crafting/furniture.json", "../../main/resources/data/crafting/furniture.json"}) {
            if (Files.exists(Path.of(c))) { f = Path.of(c); break; }
        }
        if (f == null) return;
        int storage = 0, show = 0, station = 0;
        for (JsonElement e : JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            String fn = o.get("function").getAsString();
            switch (FurnitureMath.kind(fn)) {
                case "storage" -> storage++;
                case "showcase" -> show++;
                case "station" -> {
                    station++;
                    String want = FurnitureMath.stationBlock(fn);
                    boolean has = false;
                    for (JsonElement b : o.getAsJsonArray("blocks")) {
                        if (b.getAsJsonArray().get(3).getAsString().toUpperCase(Locale.ROOT).startsWith(want)) has = true;
                    }
                    assertTrue(has, o.get("id").getAsString() + " has no " + want);
                }
                default -> { }
            }
        }
        assertEquals(12, storage);
        assertEquals(3, show);
        assertEquals(5, station);
    }
}
