package com.skyclone.mod.rift;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.Sky;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Motes prices (data/rift/economy.json, generated): what the Mote Monger pays for each item (research
 * items.json {@code motes_sell_price}), which items may cross into and out of the Rift (research
 * {@code rift_transferrable}), and the Rift shops (TUNE prices, see the generator).
 */
final class RiftEconomy {

    record Offer(String item, long motes, String requirement) {}
    record Shop(String id, String name, List<Offer> offers) {}

    private final RiftModule m;
    final Map<String, Integer> sell = new LinkedHashMap<>();
    final Set<String> transferable = new HashSet<>();
    final Map<String, Shop> shops = new LinkedHashMap<>();
    String loadError;

    RiftEconomy(RiftModule m) { this.m = m; }

    void load() {
        sell.clear(); transferable.clear(); shops.clear();
        try (InputStream in = Sky.plugin().getResource("data/rift/economy.json")) {
            if (in == null) { loadError = "data/rift/economy.json missing"; return; }
            JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("sell").entrySet()) sell.put(e.getKey(), e.getValue().getAsInt());
            for (JsonElement e : o.getAsJsonArray("transferable")) transferable.add(e.getAsString());
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("shops").entrySet()) {
                JsonObject s = e.getValue().getAsJsonObject();
                List<Offer> offers = new ArrayList<>();
                for (JsonElement it : s.getAsJsonArray("items")) {
                    JsonObject io = it.getAsJsonObject();
                    String req = io.has("requirement") && !io.get("requirement").isJsonNull() ? io.get("requirement").getAsString() : null;
                    offers.add(new Offer(io.get("item").getAsString(), io.get("motes").getAsLong(), req));
                }
                shops.put(e.getKey(), new Shop(e.getKey(), s.get("name").getAsString(), offers));
            }
        } catch (Exception e) {
            loadError = e.toString();
            Sky.log().warning("[rift] economy.json: " + e);
        }
    }

    /** Offers whose item is registered (a missing owner module just hides an offer). */
    List<Offer> available(String shopId) {
        Shop s = shops.get(shopId);
        List<Offer> out = new ArrayList<>();
        if (s == null) return out;
        for (Offer o : s.offers()) if (Sky.items().exists(o.item())) out.add(o);
        return out;
    }

    int sellValue(String id) { Integer v = sell.get(id); return v == null ? 0 : v; }
}
