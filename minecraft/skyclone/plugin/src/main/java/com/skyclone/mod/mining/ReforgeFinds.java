package com.skyclone.mod.mining;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.Rarity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The two reforge bonuses that change mining's OWN rare-find rolls (research 01 reforge table):
 * <ul>
 *   <li><b>Ambered</b> (pickaxe / drill, held): treasure goblins appear +10..50% more often by rarity;</li>
 *   <li><b>Blazing</b> (equipment, every worn piece adds its own): each 1 Heat makes worms +0.05% more likely.</li>
 * </ul>
 * The numbers are the reforges module's data ({@code data/reforges/reforges.json}, the {@code bonus.<RARITY>.values}
 * of each row), read here BY ID - no import of that module - so retuning the reforge retunes the roll. The reforge
 * itself is read off the item's instance data ({@code Sky.items().data(stack).reforge}) at its effective rarity
 * ({@code Sky.items().rarity}, recombobulation included).
 */
final class ReforgeFinds {

    private ReforgeFinds() {}

    static final String AMBERED = "ambered";
    static final String BLAZING = "blazing";

    /** reforge id -> rarity -> bonus values; loaded once. Empty when the reforges data is absent. */
    private static volatile Map<String, Map<Rarity, List<Double>>> table;

    static Map<String, Map<Rarity, List<Double>>> table() {
        Map<String, Map<Rarity, List<Double>>> t = table;
        if (t != null) return t;
        synchronized (ReforgeFinds.class) {
            if (table != null) return table;
            Map<String, Map<Rarity, List<Double>>> out = new HashMap<>();
            try (InputStream in = Sky.plugin().getResource("data/reforges/reforges.json")) {
                if (in != null) {
                    JsonElement root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    JsonArray rows = root.isJsonArray() ? root.getAsJsonArray()
                            : root.isJsonObject() && root.getAsJsonObject().has("reforges") ? root.getAsJsonObject().getAsJsonArray("reforges") : null;
                    if (rows != null) for (JsonElement el : rows) {
                        if (!el.isJsonObject()) continue;
                        JsonObject o = el.getAsJsonObject();
                        if (!o.has("id") || !o.has("bonus") || !o.get("bonus").isJsonObject()) continue;
                        String id = o.get("id").getAsString();
                        if (!AMBERED.equals(id) && !BLAZING.equals(id)) continue;
                        Map<Rarity, List<Double>> byRarity = new EnumMap<>(Rarity.class);
                        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("bonus").entrySet()) {
                            if (!e.getValue().isJsonObject() || !e.getValue().getAsJsonObject().has("values")) continue;
                            List<Double> vals = new ArrayList<>();
                            for (JsonElement v : e.getValue().getAsJsonObject().getAsJsonArray("values")) vals.add(v.getAsDouble());
                            byRarity.put(Rarity.parse(e.getKey()), vals);
                        }
                        out.put(id, byRarity);
                    }
                }
            } catch (Exception ex) {
                Sky.log().warning("[mining] could not read data/reforges/reforges.json for Ambered/Blazing: " + ex);
            }
            table = out;
            return out;
        }
    }

    /** Bonus value {@code idx} of a reforge at a rarity (nearest LOWER rarity defined, as the reforges module reads it). */
    static double value(String reforge, Rarity rarity, int idx, double def) {
        Map<Rarity, List<Double>> byRarity = table().get(reforge);
        if (byRarity == null || byRarity.isEmpty()) return def;
        Rarity[] all = Rarity.values();
        for (int i = (rarity == null ? 0 : rarity.ordinal()); i >= 0; i--) {
            List<Double> v = byRarity.get(all[i]);
            if (v != null) return idx < v.size() ? v.get(idx) : def;
        }
        return def;
    }

    private static String reforgeOf(ItemStack s) {
        if (s == null || s.getType().isAir() || Sky.items().idOf(s) == null) return null;
        ItemData d = Sky.items().data(s);
        return d == null ? null : d.reforge;
    }

    /** Ambered on the held tool: % more treasure goblins (0 when the held item is not Ambered). */
    static double amberedPct(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!AMBERED.equals(reforgeOf(hand))) return 0;
        return Math.max(0, value(AMBERED, Sky.items().rarity(hand), 0, 10));
    }

    /**
     * Blazing on worn pieces (armor slots and the equipment slots): % more worms per 1 Heat, summed over
     * every piece. Values are {@code [heat step, % per step]}, so a piece adds {@code pct / step} per Heat.
     */
    static double blazingPctPerHeat(Player p) {
        List<ItemStack> worn = new ArrayList<>();
        for (ItemStack a : p.getInventory().getArmorContents()) worn.add(a);
        try {
            List<ItemStack> eq = Sky.service(Features.Equipment.class).worn(p);
            if (eq != null) worn.addAll(eq);
        } catch (Throwable ignored) { }
        double sum = 0;
        for (ItemStack s : worn) {
            if (!BLAZING.equals(reforgeOf(s))) continue;
            Rarity r = Sky.items().rarity(s);
            sum += Math.max(0, value(BLAZING, r, 1, 0.05)) / Math.max(1, value(BLAZING, r, 0, 1));
        }
        return sum;
    }
}
