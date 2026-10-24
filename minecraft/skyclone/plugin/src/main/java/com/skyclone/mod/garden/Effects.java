package com.skyclone.mod.garden;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Rarity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What OTHER modules' items, pets and reforges do inside the Garden (wave 5). Everything is read by id
 * through {@code Sky}: {@code Features.Accessories.has}, {@code Features.Equipment.worn},
 * {@code Features.Pets.abilityValue}, the held item's {@code ItemData.reforge} and the reforges module's own
 * data file (read-only, the way the farming module reads it). Numbers are in {@link EffectMath}.
 */
final class Effects {

    private Effects() {}

    // =====================================================================================
    // accessories (family tiers, cached a second: a fast farmer breaks 20 crops a second)
    // =====================================================================================

    private record Tiers(long at, Map<String, Integer> tier) {}
    private static final Map<UUID, Tiers> TIERS = new ConcurrentHashMap<>();
    static final String[] FAMILIES = {"COPPER", "GRATITUDE", "BIOANALYSIS"};

    static boolean hasAccessory(Player p, String id) {
        try { return Sky.service(Features.Accessories.class).has(p, id); } catch (Throwable t) { return false; }
    }

    /** Highest owned member of {@code <BASE>_TALISMAN / _RING / _ARTIFACT}: 0 none, 1..3. */
    static int tier(Player p, String base) {
        long now = System.currentTimeMillis();
        Tiers t = TIERS.get(p.getUniqueId());
        if (t == null || now - t.at > 1000) {
            Map<String, Integer> m = new HashMap<>();
            for (String f : FAMILIES) {
                int v = 0;
                if (hasAccessory(p, f + "_ARTIFACT")) v = 3;
                else if (hasAccessory(p, f + "_RING")) v = 2;
                else if (hasAccessory(p, f + "_TALISMAN")) v = 1;
                m.put(f, v);
            }
            t = new Tiers(now, m);
            TIERS.put(p.getUniqueId(), t);
            if (TIERS.size() > 512) TIERS.entrySet().removeIf(e -> now - e.getValue().at > 10_000);
        }
        return t.tier.getOrDefault(base, 0);
    }

    static double copperRarePct(Player p) { return EffectMath.familyValue(EffectMath.COPPER_PCT, tier(p, "COPPER")); }
    static double gratitudePct(Player p) { return EffectMath.familyValue(EffectMath.GRATITUDE_PCT, tier(p, "GRATITUDE")); }
    static double bioanalysisPct(Player p) { return p == null ? 0 : EffectMath.familyValue(EffectMath.BIOANALYSIS_PCT, tier(p, "BIOANALYSIS")); }

    /** SkyBlock season 0 spring, 1 summer, 2 autumn, 3 winter; -1 when the calendar is not answering. */
    static int season() {
        try { return Sky.calendar().now().season(); } catch (Throwable t) { return -1; }
    }

    /** Atmospheric Filter, Autumn half: pests show up 15% more often on your Garden (accessories own the Spring/Summer stats). */
    static double filterPestMult(Player p) {
        return season() == 2 && hasAccessory(p, "ATMOSPHERIC_FILTER") ? 1 + G.filterAutumnPct.get() / 100.0 : 1.0;
    }

    /** Atmospheric Filter, Winter half: +5% Copper from visitors. */
    static double filterCopperPct(Player p) {
        return season() == 3 && hasAccessory(p, "ATMOSPHERIC_FILTER") ? G.filterWinterPct.get() : 0;
    }

    // =====================================================================================
    // pets
    // =====================================================================================

    static double pet(Player p, String key, String token) {
        if (p == null) return 0;
        try { return Sky.service(Features.Pets.class).abilityValue(p, key, token); } catch (Throwable t) { return 0; }
    }

    static double pet(Player p, String key) { return pet(p, key, "a"); }

    /**
     * The Green Bandana (lane 12): +4 Farming Fortune per Garden level while the summoned pet holds one. The pets
     * module owns held items and {@code Features.Pets} has no "held item" call, so this asks
     * {@code abilityValue(p, "held:GREEN_BANDANA", "a")} - 1 when the active pet holds it (hand-off Request 11 for
     * pets). Until pets answers that key it is 0, never a wrong number.
     */
    static double greenBandanaFortune(Player p, int gardenLevel) {
        if (pet(p, "held:GREEN_BANDANA") <= 0) return 0;
        double per = 4;
        try {
            var def = Sky.items().def("GREEN_BANDANA");
            if (def != null && def.extra != null && def.extra.has("fortunePerGardenLevel")) per = def.extra.get("fortunePerGardenLevel").getAsDouble();
        } catch (Throwable ignored) { }
        return per * Math.max(0, gardenLevel);
    }

    // =====================================================================================
    // equipment
    // =====================================================================================

    static List<ItemStack> worn(Player p) {
        try {
            List<ItemStack> w = Sky.service(Features.Equipment.class).worn(p);
            return w == null ? List.of() : w;
        } catch (Throwable t) { return List.of(); }
    }

    static String id(ItemStack s) {
        if (s == null || s.getType().isAir()) return null;
        try { return Sky.items().idOf(s); } catch (Throwable t) { return null; }
    }

    static String reforgeOf(ItemStack s) {
        if (id(s) == null) return null;
        try { ItemData d = Sky.items().data(s); return d == null || d.reforge == null ? null : d.reforge.toLowerCase(Locale.ROOT); }
        catch (Throwable t) { return null; }
    }

    static String uuidOf(ItemStack s) {
        if (id(s) == null) return null;
        try { ItemData d = Sky.items().data(s); return d == null ? null : d.uuid; } catch (Throwable t) { return null; }
    }

    /** Pesthunter's Belt/Cloak/Gloves/Necklace worn (the "Eradicator" tiered bonus, 0/4). */
    static int pesthunterPieces(Player p) {
        int n = 0;
        for (ItemStack s : worn(p)) { String i = id(s); if (i != null && i.startsWith("PESTHUNTERS_")) n++; }
        return n;
    }

    // =====================================================================================
    // reforges (data/reforges/reforges.json, read-only)
    // =====================================================================================

    /** reforge id -> rarity -> {"values": [...], "other": {...}}. */
    private static volatile Map<String, Map<Rarity, JsonObject>> REFORGES;

    private static Map<String, Map<Rarity, JsonObject>> reforges() {
        Map<String, Map<Rarity, JsonObject>> r = REFORGES;
        if (r != null) return r;
        Map<String, Map<Rarity, JsonObject>> out = new HashMap<>();
        try (InputStream in = Sky.plugin().getResource("data/reforges/reforges.json")) {
            if (in != null) {
                JsonElement root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                JsonArray rows = root.isJsonArray() ? root.getAsJsonArray()
                        : root.isJsonObject() && root.getAsJsonObject().has("reforges") ? root.getAsJsonObject().getAsJsonArray("reforges") : new JsonArray();
                for (JsonElement e : rows) {
                    if (!e.isJsonObject()) continue;
                    JsonObject row = e.getAsJsonObject();
                    if (!row.has("id")) continue;
                    String rid = row.get("id").getAsString().toLowerCase(Locale.ROOT);
                    Map<Rarity, JsonObject> byR = new HashMap<>();
                    for (Rarity rar : Rarity.values()) {
                        JsonObject cell = new JsonObject();
                        JsonObject bonus = sub(row, "bonus", rar.name());
                        if (bonus != null && bonus.has("values")) cell.add("values", bonus.get("values"));
                        JsonObject other = sub(row, "otherStats", rar.name());
                        if (other != null) cell.add("other", other);
                        if (cell.size() > 0) byR.put(rar, cell);
                    }
                    if (!byR.isEmpty()) out.put(rid, byR);
                }
            }
        } catch (Throwable t) {
            Sky.log().warning("[garden] could not read data/reforges/reforges.json: " + t);
        }
        REFORGES = out;
        return out;
    }

    private static JsonObject sub(JsonObject row, String key, String rarity) {
        if (!row.has(key) || !row.get(key).isJsonObject()) return null;
        JsonObject o = row.getAsJsonObject(key);
        return o.has(rarity) && o.get(rarity).isJsonObject() ? o.getAsJsonObject(rarity) : null;
    }

    /** Nearest-lower-rarity cell of a reforge row (the ReforgeDef rule), or null. */
    private static JsonObject cell(String reforgeId, Rarity r) {
        Map<Rarity, JsonObject> byR = reforges().get(reforgeId);
        if (byR == null || r == null) return null;
        for (int i = r.ordinal(); i >= 0; i--) { JsonObject c = byR.get(Rarity.values()[i]); if (c != null) return c; }
        return null;
    }

    /** {@code bonus.<R>.values[idx]} of the stack's reforge when it is {@code reforgeId}, else {@code fallback}-less 0. */
    static double reforgeValue(ItemStack s, String reforgeId, int idx, double fallback) {
        if (!reforgeId.equals(reforgeOf(s))) return 0;
        Rarity r;
        try { r = Sky.items().rarity(s); } catch (Throwable t) { r = Rarity.COMMON; }
        JsonObject c = cell(reforgeId, r);
        if (c == null || !c.has("values") || !c.get("values").isJsonArray()) return fallback;
        JsonArray a = c.getAsJsonArray("values");
        return a.size() > idx ? a.get(idx).getAsDouble() : fallback;
    }

    /** {@code bonus.<R>.values[idx]} of a reforge row at a rarity, or NaN when the data has none (self-tests). */
    static double rowValue(String reforgeId, Rarity r, int idx) {
        JsonObject c = cell(reforgeId, r);
        if (c == null || !c.has("values") || !c.get("values").isJsonArray() || c.getAsJsonArray("values").size() <= idx) return Double.NaN;
        return c.getAsJsonArray("values").get(idx).getAsDouble();
    }

    /** A number in a reforge row's {@code otherStats} at a rarity (self-tests). */
    static double rowOther(String reforgeId, Rarity r, String key) {
        JsonObject c = cell(reforgeId, r);
        return c == null || !c.has("other") ? 0 : number(c.getAsJsonObject("other"), key);
    }

    /** A number in the stack's reforge {@code otherStats} (case-blind key), 0 when absent. */
    static double reforgeOther(ItemStack s, String key) {
        String rf = reforgeOf(s);
        if (rf == null) return 0;
        Rarity r;
        try { r = Sky.items().rarity(s); } catch (Throwable t) { r = Rarity.COMMON; }
        JsonObject c = cell(rf, r);
        if (c == null || !c.has("other")) return 0;
        return number(c.getAsJsonObject("other"), key);
    }

    static double number(JsonObject o, String key) {
        if (o == null) return 0;
        for (Map.Entry<String, JsonElement> e : o.entrySet())
            if (e.getKey().equalsIgnoreCase(key) && e.getValue().isJsonPrimitive()) {
                try { return e.getValue().getAsDouble(); } catch (Exception ignored) { return 0; }
            }
        return 0;
    }

    // =====================================================================================
    // accessory otherStats: per-crop fortune and Overbloom (only the highest of a family counts)
    // =====================================================================================

    private record Acc(String id, String family, int rank, JsonObject other) {}
    private static volatile List<Acc> ACCS;

    private static List<Acc> accessories() {
        List<Acc> a = ACCS;
        if (a != null) return a;
        Map<String, List<Acc>> byFam = new LinkedHashMap<>();
        java.util.Set<String> relevant = new java.util.HashSet<>();
        for (ItemDef d : Sky.items().all()) {
            if (d == null || d.category != ItemCategory.ACCESSORY || d.extra == null) continue;
            JsonObject os = d.extra.has("otherStats") && d.extra.get("otherStats").isJsonObject() ? d.extra.getAsJsonObject("otherStats") : null;
            String fam = d.extra.has("family") ? d.extra.get("family").getAsString() : d.id;
            int rank = d.extra.has("familyRank") ? d.extra.get("familyRank").getAsInt() : 0;
            Acc acc = new Acc(d.id, fam, rank, os);
            byFam.computeIfAbsent(fam, k -> new ArrayList<>()).add(acc);
            if (os != null) for (String k : os.keySet()) {
                String u = k.toUpperCase(Locale.ROOT);
                if (u.endsWith("_FORTUNE") || u.equals("OVERBLOOM")) relevant.add(fam);
            }
        }
        List<Acc> out = new ArrayList<>();
        for (String f : relevant) out.addAll(byFam.get(f));
        ACCS = List.copyOf(out);
        return ACCS;
    }

    private record Counted(long at, List<Acc> list) {}
    private static final Map<UUID, Counted> COUNTED = new ConcurrentHashMap<>();

    private static List<Acc> counted(Player p) {
        long now = System.currentTimeMillis();
        Counted c = COUNTED.get(p.getUniqueId());
        if (c != null && now - c.at < 1000) return c.list;
        Map<String, Acc> best = new HashMap<>();
        for (Acc a : accessories()) {
            if (!hasAccessory(p, a.id)) continue;
            Acc b = best.get(a.family);
            if (b == null || a.rank > b.rank) best.put(a.family, a);
        }
        List<Acc> list = new ArrayList<>(best.values());
        COUNTED.put(p.getUniqueId(), new Counted(now, list));
        if (COUNTED.size() > 512) COUNTED.entrySet().removeIf(e -> now - e.getValue().at > 10_000);
        return list;
    }

    /** The otherStats keys that mean "fortune for this garden crop". */
    static List<String> cropKeys(String cropKey) {
        return switch (cropKey) {
            case "MELON" -> List.of("MELON_SLICE_FORTUNE", "MELON_FORTUNE");
            case "COCOA_BEANS" -> List.of("COCOA_BEANS_FORTUNE", "COCOA_FORTUNE");
            case "NETHER_WART" -> List.of("NETHER_WART_FORTUNE", "NETHER_STALK_FORTUNE");
            case "MUSHROOM" -> List.of("MUSHROOM_FORTUNE", "RED_MUSHROOM_FORTUNE");
            default -> List.of(cropKey + "_FORTUNE");
        };
    }

    /** Per-crop fortune carried by counted accessories (Cropling / Squash / Fermenta lines...). */
    static double accessoryCropFortune(Player p, String cropKey) {
        double t = 0;
        for (Acc a : counted(p)) {
            if (a.other == null) continue;
            for (String k : cropKeys(cropKey)) { double v = number(a.other, k); if (v != 0) { t += v; break; } }
        }
        return t;
    }

    /**
     * Overbloom (rare-drop chance): reforge otherStats on the held item, armour and equipment (Overpriced, Thorny...),
     * counted accessories' otherStats (Freshly Baked...), the Rose Dragon's Feast, and the Hedgehog's Pest Terror when
     * the drop is a pest's.
     */
    static double overbloom(Player p, boolean vsPests) {
        double t = reforgeOther(p.getInventory().getItemInMainHand(), "OVERBLOOM");
        for (ItemStack s : p.getInventory().getArmorContents()) t += reforgeOther(s, "OVERBLOOM");
        for (ItemStack s : worn(p)) t += reforgeOther(s, "OVERBLOOM");
        for (Acc a : counted(p)) t += number(a.other, "OVERBLOOM");
        t += pet(p, "rose_dragon_dragon_s_gluttony");
        if (vsPests) t += pet(p, "hedgehog_fearsome_farmer", "b");
        try { if (G.ready(p)) t += G.chipValue(G.data(p), "overbloom"); } catch (Throwable ignored) { }   // Rarefinder chip
        return t;
    }

    // =====================================================================================
    // temporary Farming Fortune (the Hypercharge chip): active potion effects' Farming Fortune
    // =====================================================================================

    /** effect id -> level -> Farming Fortune, read from alchemy's data file (read-only). */
    private static volatile Map<String, Map<Integer, Double>> POTION_FF;

    static Map<String, Map<Integer, Double>> potionFortune() {
        Map<String, Map<Integer, Double>> r = POTION_FF;
        if (r != null) return r;
        Map<String, Map<Integer, Double>> out = new HashMap<>();
        try (InputStream in = Sky.plugin().getResource("data/alchemy/effects.json")) {
            if (in != null) {
                JsonElement root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                JsonArray rows = root.isJsonArray() ? root.getAsJsonArray()
                        : root.isJsonObject() && root.getAsJsonObject().has("effects") ? root.getAsJsonObject().getAsJsonArray("effects") : new JsonArray();
                for (JsonElement e : rows) {
                    if (!e.isJsonObject() || !e.getAsJsonObject().has("id") || !e.getAsJsonObject().has("levels")) continue;
                    JsonObject row = e.getAsJsonObject();
                    Map<Integer, Double> byLevel = new HashMap<>();
                    for (JsonElement l : row.getAsJsonArray("levels")) {
                        if (!l.isJsonObject()) continue;
                        JsonObject lv = l.getAsJsonObject();
                        JsonObject st = lv.has("stats") && lv.get("stats").isJsonObject() ? lv.getAsJsonObject("stats") : null;
                        double ff = number(st, "FARMING_FORTUNE");
                        if (ff != 0 && lv.has("level")) byLevel.put(lv.get("level").getAsInt(), ff);
                    }
                    if (!byLevel.isEmpty()) out.put(row.get("id").getAsString(), byLevel);
                }
            }
        } catch (Throwable t) {
            Sky.log().warning("[garden] could not read data/alchemy/effects.json: " + t);
        }
        POTION_FF = out;
        return out;
    }

    /** The Pestwarden's Bonus Farming Fortune when it is running (0 otherwise). */
    static double pesthunterFortune(GardenMember m) {
        return m != null && m.pestBonusUntil > System.currentTimeMillis() ? Math.max(0, m.pestBonusFf) : 0;
    }

    /**
     * Temporary Farming Fortune the player has right now (the buffs the Hypercharge chip makes stronger): the
     * Pestwarden's Bonus and active potion effects' Farming Fortune.
     */
    static double temporaryFortune(Player p) {
        double fromPests = 0;
        try { if (G.ready(p)) fromPests = pesthunterFortune(G.member(p)); } catch (Throwable ignored) { }
        return fromPests + potionTemporaryFortune(p);
    }

    static double potionTemporaryFortune(Player p) {
        Map<String, Integer> active;
        try { active = Sky.service(Features.Potions.class).active(p); } catch (Throwable t) { return 0; }
        if (active == null || active.isEmpty()) return 0;
        double t = 0;
        Map<String, Map<Integer, Double>> table = potionFortune();
        for (Map.Entry<String, Integer> e : active.entrySet()) {
            Map<Integer, Double> byLevel = table.get(e.getKey());
            if (byLevel == null) continue;
            Double v = byLevel.get(e.getValue());
            if (v == null) {   // a level the table lacks: the nearest lower one it has
                int best = Integer.MIN_VALUE;
                for (int l : byLevel.keySet()) if (l <= (e.getValue() == null ? 0 : e.getValue()) && l > best) best = l;
                v = best == Integer.MIN_VALUE ? 0.0 : byLevel.get(best);
            }
            t += v;
        }
        return t;
    }

    // =====================================================================================
    // the Harvest Fair (Overdrive chip): is this crop one of the running fair's crops?
    // =====================================================================================

    static final String FAIR_EVENT = "farming_contest";

    /**
     * True when a Harvest Fair is running and {@code cropKey} is one of its crops. Farming owns the fair; when it
     * registers the requirement prefix {@code harvest_fair} ({@code harvest_fair:<CROP>}) we ask it, otherwise
     * (until then) every crop counts while a fair runs.
     */
    static boolean fairCrop(Player p, String cropKey) {
        try {
            if (!Sky.calendar().active(FAIR_EVENT)) return false;
            if (Sky.requirements().known("harvest_fair")) return Sky.requirements().check(p, "harvest_fair:" + cropKey);
            return true;
        } catch (Throwable t) { return false; }
    }

    /** Forget cached indexes (admin reload, late item registration). */
    static void reindex() { ACCS = null; REFORGES = null; POTION_FF = null; TIERS.clear(); COUNTED.clear(); }

    // =====================================================================================
    // Peony (LOTUS_*) / Blossom pieces: visitors served while worn, per piece uuid
    // =====================================================================================

    /** uuid -> served, filled from every loaded member's data, so lore (which has no player) can read it. */
    static final Map<String, Long> SERVED = new ConcurrentHashMap<>();

    static long served(ItemData data) {
        if (data == null) return 0;
        long v = data.extra != null && data.extra.has("visitors_served") ? data.extra.get("visitors_served").getAsLong() : 0;
        if (data.uuid != null) v = Math.max(v, SERVED.getOrDefault(data.uuid, 0L));
        return v;
    }

    static long served(GardenMember m, ItemStack s) {
        String u = uuidOf(s);
        long v = 0;
        try { v = served(Sky.items().data(s)); } catch (Throwable ignored) { }
        if (u != null && m != null) v = Math.max(v, m.pieceServed.getOrDefault(u, 0L));
        return v;
    }

    /** Load a member's counts into the lore cache. */
    static void remember(GardenMember m) {
        if (m == null) return;
        for (Map.Entry<String, Long> e : m.pieceServed.entrySet()) SERVED.merge(e.getKey(), e.getValue(), Math::max);
    }

    /** One visitor offer completed: every worn growing piece counts it. Returns the pieces that reached a new step. */
    static List<String> countServed(Player p) {
        List<String> grew = new ArrayList<>();
        GardenMember m = G.member(p);
        for (ItemStack s : worn(p)) {
            String id = id(s);
            if (!EffectMath.isGrowingPiece(id)) continue;
            String u = uuidOf(s);
            if (u == null) continue;
            long before = served(m, s);
            long now = before + 1;
            m.pieceServed.put(u, now);
            SERVED.merge(u, now, Math::max);
            // the live equipment stack too (lore at once); the count is kept in member data either way
            try { Sky.items().update(s, d -> d.extra.addProperty("visitors_served", now)); } catch (Throwable ignored) { }
            if (EffectMath.pieceSteps(now) > EffectMath.pieceSteps(before)) grew.add(id);
        }
        // copies of the player's pieces in the inventory carry their count, so a traded piece keeps it
        try {
            for (ItemStack s : p.getInventory().getContents()) {
                if (!EffectMath.isGrowingPiece(id(s))) continue;
                String u = uuidOf(s);
                Long v = u == null ? null : m.pieceServed.get(u);
                if (v == null) continue;
                long have = served(Sky.items().data(s));
                if (have < v) Sky.items().update(s, d -> d.extra.addProperty("visitors_served", v));
            }
        } catch (Throwable ignored) { }
        if (!grew.isEmpty()) Sky.stats().invalidate(p);
        return grew;
    }

    /** Farming Fortune from worn Peony / Blossom pieces (only when the wearer meets their requirements). */
    static double pieceFortune(Player p, GardenMember m) {
        double t = 0;
        for (ItemStack s : worn(p)) {
            String id = id(s);
            if (!EffectMath.isGrowingPiece(id)) continue;
            boolean ok;
            try { ok = Sky.items().meetsRequirements(p, s, false); } catch (Throwable e) { ok = true; }
            if (ok) t += EffectMath.pieceBonus(id, served(m, s));
        }
        return t;
    }

    /** Lore lines for a growing piece (no player: reads the item's own count and the cache). */
    static List<String> pieceLore(ItemDef def, ItemData data) {
        if (def == null || !EffectMath.isGrowingPiece(def.id)) return List.of();
        long served = served(data);
        double step = EffectMath.pieceStep(def.id);
        List<String> out = new ArrayList<>();
        out.add("&7Piece Bonus: &6+" + fmt(EffectMath.pieceBonus(def.id, served)) + "☘ Farming Fortune");
        int next = EffectMath.nextThreshold(served);
        if (next < 0) out.add("&a&lMAXED OUT!");
        else out.add("&7Next Upgrade: &6+" + fmt((EffectMath.pieceSteps(served) + 1) * step) + " &8(&a"
                + com.skyclone.api.ui.Text.commasInt(served) + "&7/&c" + com.skyclone.api.ui.Text.commasInt(next) + "&8)");
        out.add("&7Garden Visitors Served: &a" + com.skyclone.api.ui.Text.commasInt(served));
        return out;
    }

    static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }
}
