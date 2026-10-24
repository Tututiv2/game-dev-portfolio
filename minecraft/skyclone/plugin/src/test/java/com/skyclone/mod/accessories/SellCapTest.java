package com.skyclone.mod.accessories;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** No craft-and-sell coin printer: a crafted item never out-sells its cheapest recipe's inputs. */
class SellCapTest {

    private static SellCap.Way way(int amount, Object... idAmt) {
        Map<String, Double> m = new HashMap<>();
        for (int i = 0; i < idAmt.length; i += 2) m.put((String) idAmt[i], ((Number) idAmt[i + 1]).doubleValue());
        return new SellCap.Way(m, amount);
    }

    @Test
    void capsAtTheCheapestRecipeAndOnlyLowers() {
        Map<String, Double> price = Map.of("PEARL", 200.0, "NECKLACE", 52_000.0, "CHEAP", 10.0, "BELT", 5.0);
        Map<String, List<SellCap.Way>> ways = Map.of(
                "NECKLACE", List.of(way(1, "PEARL", 20), way(1, "PEARL", 40)),
                "BELT", List.of(way(1, "CHEAP", 9)));
        Map<String, Double> low = SellCap.compute(List.of("NECKLACE", "BELT"),
                id -> price.getOrDefault(id, 0.0), id -> ways.getOrDefault(id, List.of()), 1.0);
        assertEquals(4000.0, low.get("NECKLACE"), 1e-9, "20 pearls at 200 is the cheapest way");
        assertTrue(!low.containsKey("BELT"), "a price already under its inputs is never raised");
    }

    @Test
    void ourOwnInputsAreValuedAtTheirCappedPrice() {
        // Emperor Talisman 100 -> Ring (4 talismans, ref 900) -> Artifact (4 rings, ref 8,100)
        Map<String, Double> price = Map.of("TALISMAN", 100.0, "RING", 900.0, "ARTIFACT", 8100.0);
        Map<String, List<SellCap.Way>> ways = Map.of(
                "RING", List.of(way(1, "TALISMAN", 4)),
                "ARTIFACT", List.of(way(1, "RING", 4)));
        Map<String, Double> low = SellCap.compute(List.of("ARTIFACT", "RING", "TALISMAN"),
                id -> price.getOrDefault(id, 0.0), id -> ways.getOrDefault(id, List.of()), 1.0);
        assertEquals(400.0, low.get("RING"), 1e-9);
        assertEquals(1600.0, low.get("ARTIFACT"), 1e-9, "four CAPPED rings, not four at the reference 900");
    }

    @Test
    void unknownInputsCountZeroAndYieldDivides() {
        Map<String, Double> price = Map.of("TALISMAN", 1000.0, "STACK", 100.0, "GOOD", 8.0);
        Map<String, List<SellCap.Way>> ways = Map.of(
                "TALISMAN", List.of(way(1, "NOBODY_REGISTERS_THIS", 9)),
                "STACK", List.of(way(4, "GOOD", 10)));
        Map<String, Double> low = SellCap.compute(List.of("TALISMAN", "STACK"),
                id -> price.getOrDefault(id, 0.0), id -> ways.getOrDefault(id, List.of()), 1.0);
        assertEquals(0.0, low.get("TALISMAN"), 1e-9);
        assertEquals(20.0, low.get("STACK"), 1e-9, "10 x 8 coins makes 4, so each is worth 20");
    }

    @Test
    void cyclesTerminate() {
        Map<String, Double> price = Map.of("A", 50.0, "B", 50.0, "X", 1.0);
        Map<String, List<SellCap.Way>> ways = Map.of("A", List.of(way(1, "B", 1)), "B", List.of(way(1, "A", 1), way(1, "X", 2)));
        Map<String, Double> low = SellCap.compute(List.of("A", "B"),
                id -> price.getOrDefault(id, 0.0), id -> ways.getOrDefault(id, List.of()), 1.0);
        assertEquals(2.0, low.get("B"), 1e-9);
    }

    @Test
    void cellsParseIdsThatContainColons() {
        Map<String, Double> m = new HashMap<>();
        SellCap.addCell(m, "RAW_FISH:2:32");
        SellCap.addCell(m, "KUUDRA_TIER_KEY");
        SellCap.addCell(m, "GAZING_PEARL:5");
        SellCap.addCell(m, "GAZING_PEARL:5");
        SellCap.addCell(m, "");
        assertEquals(Map.of("RAW_FISH:2", 32.0, "KUUDRA_TIER_KEY", 1.0, "GAZING_PEARL", 10.0), m);
    }

    // ---- the shipped data ------------------------------------------------------------------------

    private static JsonElement parse(InputStream in) throws IOException {
        return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    /** Every OTHER module's item prices as shipped (priced entries win over unpriced fallback copies). */
    private static Map<String, Double> outsidePrices(Path data) throws IOException {
        Map<String, Double> out = new HashMap<>();
        try (DirectoryStream<Path> mods = Files.newDirectoryStream(data)) {
            for (Path mod : mods) {
                if (!Files.isDirectory(mod) || mod.getFileName().toString().equals("accessories")) continue;
                try (DirectoryStream<Path> files = Files.newDirectoryStream(mod, "*.json")) {
                    for (Path f : files) {
                        JsonElement j;
                        try (InputStream in = Files.newInputStream(f)) { j = parse(in); } catch (RuntimeException e) { continue; }
                        if (!j.isJsonArray()) continue;
                        for (JsonElement e : j.getAsJsonArray()) {
                            if (!e.isJsonObject()) continue;
                            JsonObject o = e.getAsJsonObject();
                            if (!o.has("id") || !o.has("name") || !o.has("rarity")) continue;
                            double v = o.has("npcSell") ? o.get("npcSell").getAsDouble() : 0;
                            double have = out.getOrDefault(o.get("id").getAsString(), 0.0);
                            out.put(o.get("id").getAsString(), have <= 0 ? v : (v <= 0 ? have : Math.min(v, have)));
                        }
                    }
                }
            }
        }
        return out;
    }

    @Test
    void shippedDataHasNoCraftAndSellLoop() throws Exception {
        URL url = SellCapTest.class.getClassLoader().getResource("data/accessories/recipes.json");
        assertNotNull(url);
        Path data = Paths.get(url.toURI()).getParent().getParent();
        Map<String, Double> price = outsidePrices(data);
        List<String> ours = new ArrayList<>();
        for (String f : List.of("items.json", "equipment.json")) {
            try (InputStream in = Files.newInputStream(data.resolve("accessories").resolve(f))) {
                for (JsonElement e : parse(in).getAsJsonArray()) {
                    JsonObject o = e.getAsJsonObject();
                    String id = o.get("id").getAsString();
                    ours.add(id);
                    price.put(id, o.has("npcSell") ? o.get("npcSell").getAsDouble() : 0);
                }
            }
        }
        JsonArray recipes;
        try (InputStream in = Files.newInputStream(data.resolve("accessories").resolve("recipes.json"))) {
            recipes = parse(in).getAsJsonArray();
        }
        List<String> bad = new ArrayList<>();
        for (JsonElement e : recipes) {
            JsonObject r = e.getAsJsonObject();
            Map<String, Double> in = new HashMap<>();
            for (String k : List.of("grid", "inputs")) {
                if (r.has(k)) for (JsonElement c : r.getAsJsonArray(k)) SellCap.addCell(in, c.getAsString());
            }
            double cost = 0;
            for (Map.Entry<String, Double> x : in.entrySet()) cost += price.getOrDefault(x.getKey(), 0.0) * x.getValue();
            int amount = r.has("amount") ? Math.max(1, r.get("amount").getAsInt()) : 1;
            String result = r.get("result").getAsString();
            double out = price.getOrDefault(result, 0.0) * amount;
            if (out > cost + 0.5) bad.add(result + " (in " + Math.round(cost) + ", sells " + Math.round(out) + ")");
        }
        assertTrue(bad.isEmpty(), bad.size() + " recipes sell for more than their inputs; rerun the generator: " + bad);
        assertTrue(ours.size() > 500);
        // the reported printers, pinned
        assertEquals(4000.0, price.get("DELIRIUM_NECKLACE"), 1e-9);
        assertEquals(0.0, price.get("RESPIRATION_TALISMAN"), 1e-9);
    }
}
