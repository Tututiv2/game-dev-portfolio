package com.skyclone.mod.farming;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Text;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What OTHER modules' items and pets do inside farming (wave 5). Every number is read from their data by id, never
 * by importing their classes:
 * <ul>
 *   <li><b>per-crop fortune</b> written as {@code extra.otherStats} on accessories (Cropling Talisman, Squash Ring,
 *       Fermenta Artifact - only the highest of a family counts) and as a pet's level-scaled extra stat (Pig: Potato
 *       Fortune; {@code data/pets/pets.json} x1/x100);</li>
 *   <li><b>Overbloom</b> (rare-crop chance): the Overpriced reforge on the held tool and Thorny on worn pieces
 *       ({@code data/reforges/reforges.json} otherStats), Freshly Baked accessories, the Orchid Mantis pet;</li>
 *   <li>worn-equipment checks for the trapper's pelt bonuses and the Masked Hopper's Cape.</li>
 * </ul>
 * Accessories answer {@code has} per call, so a player's counted list is cached for a second (a fast farmer breaks
 * 20 crops a second).
 */
final class Perks {

    /** Accessory ids whose families matter to farming, with their numbers. Built lazily once items are registered. */
    private volatile List<PerkMath.Accessory> accessories;
    /** reforge id -> rarity name -> otherStats, only rows that carry farming numbers. */
    private final Map<String, Map<String, JsonObject>> reforgeStats = new HashMap<>();
    /** pet id -> tier -> stat key -> {at level 1, at level 100}; plus each pet's stat curve. */
    private final Map<String, Map<String, Map<String, double[]>>> petStats = new HashMap<>();
    private final Map<String, String> petCurve = new HashMap<>();
    private final Map<String, String> petNames = new HashMap<>();
    /** Every pet ability key in data/pets/pets.json (self-test: the perks farming reads by key are spelt right). */
    final Set<String> petAbilityKeys = new HashSet<>();
    /** The pets' info perks farming applies (pets handoff request 16) - checked against {@link #petAbilityKeys}. */
    static final List<String> FARMING_PET_PERKS = List.of("orchid_mantis_intelligent_specimen", "pig_hamfisted",
            "pig_shining_stampede", "rose_dragon_dragon_s_gluttony");

    private record Snapshot(long at, List<PerkMath.Accessory> counted) {}
    private final Map<UUID, Snapshot> cache = new HashMap<>();

    void load(ClassLoader cl) {
        readReforges(cl);
        readPets(cl);
    }

    // ---- data ------------------------------------------------------------------------------------

    private static JsonElement res(ClassLoader cl, String path) {
        try (InputStream in = cl.getResourceAsStream(path)) {
            if (in == null) return null;
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Sky.log().warning("[farming] could not read " + path + ": " + e);
            return null;
        }
    }

    private void readReforges(ClassLoader cl) {
        JsonElement root = res(cl, "data/reforges/reforges.json");
        JsonArray rows = root == null ? null : root.isJsonArray() ? root.getAsJsonArray()
                : root.isJsonObject() && root.getAsJsonObject().has("reforges") ? root.getAsJsonObject().getAsJsonArray("reforges") : null;
        if (rows == null) return;
        for (JsonElement e : rows) {
            if (!e.isJsonObject()) continue;
            JsonObject r = e.getAsJsonObject();
            if (!r.has("id") || !r.has("otherStats") || !r.get("otherStats").isJsonObject()) continue;
            Map<String, JsonObject> byRarity = new HashMap<>();
            for (Map.Entry<String, JsonElement> t : r.getAsJsonObject("otherStats").entrySet())
                if (t.getValue().isJsonObject() && PerkMath.farmingRelevant(t.getValue().getAsJsonObject()))
                    byRarity.put(t.getKey().toUpperCase(java.util.Locale.ROOT), t.getValue().getAsJsonObject());
            if (!byRarity.isEmpty()) reforgeStats.put(r.get("id").getAsString().toLowerCase(java.util.Locale.ROOT), byRarity);
        }
    }

    private void readPets(ClassLoader cl) {
        JsonElement root = res(cl, "data/pets/pets.json");
        if (root == null || !root.isJsonArray()) return;
        for (JsonElement e : root.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            JsonObject p = e.getAsJsonObject();
            if (!p.has("id") || !p.has("tiers") || !p.get("tiers").isJsonObject()) continue;
            String id = p.get("id").getAsString();
            Map<String, Map<String, double[]>> tiers = new HashMap<>();
            for (Map.Entry<String, JsonElement> t : p.getAsJsonObject("tiers").entrySet()) {
                if (!t.getValue().isJsonObject()) continue;
                JsonObject tier = t.getValue().getAsJsonObject();
                if (tier.has("abilities") && tier.get("abilities").isJsonArray())
                    for (JsonElement ab : tier.getAsJsonArray("abilities"))
                        if (ab.isJsonObject() && ab.getAsJsonObject().has("key")) petAbilityKeys.add(ab.getAsJsonObject().get("key").getAsString());
                JsonObject x1 = tier.has("x1") && tier.get("x1").isJsonObject() ? tier.getAsJsonObject("x1") : new JsonObject();
                JsonObject x100 = tier.has("x100") && tier.get("x100").isJsonObject() ? tier.getAsJsonObject("x100") : null;
                if (x100 == null || !PerkMath.farmingRelevant(x100)) continue;
                Map<String, double[]> stats = new HashMap<>();
                for (Map.Entry<String, JsonElement> s : x100.entrySet())
                    if (s.getValue().isJsonPrimitive()) stats.put(s.getKey().toUpperCase(java.util.Locale.ROOT),
                            new double[]{PerkMath.number(x1, s.getKey()), s.getValue().getAsDouble()});
                tiers.put(t.getKey().toUpperCase(java.util.Locale.ROOT), stats);
            }
            if (tiers.isEmpty()) continue;
            petStats.put(id, tiers);
            if (p.has("curve") && p.get("curve").isJsonPrimitive()) petCurve.put(id, p.get("curve").getAsString());
            if (p.has("name") && p.get("name").isJsonPrimitive()) petNames.put(id, p.get("name").getAsString());
        }
    }

    /** Every accessory in a family that has at least one member with farming numbers (so a higher member with none still wins). */
    List<PerkMath.Accessory> accessories() {
        List<PerkMath.Accessory> a = accessories;
        if (a != null) return a;
        Map<String, List<PerkMath.Accessory>> byFamily = new LinkedHashMap<>();
        Set<String> relevant = new HashSet<>();
        for (ItemDef d : Sky.items().all()) {
            if (d == null || d.category != ItemCategory.ACCESSORY || d.extra == null) continue;
            JsonObject os = d.extra.has("otherStats") && d.extra.get("otherStats").isJsonObject() ? d.extra.getAsJsonObject("otherStats") : null;
            String fam = d.extra.has("family") && d.extra.get("family").isJsonPrimitive() ? d.extra.get("family").getAsString() : null;
            int rank = d.extra.has("familyRank") && d.extra.get("familyRank").isJsonPrimitive() ? d.extra.get("familyRank").getAsInt() : 0;
            PerkMath.Accessory acc = new PerkMath.Accessory(d.id, fam, rank, os);
            byFamily.computeIfAbsent(acc.familyKey(), k -> new ArrayList<>()).add(acc);
            if (PerkMath.farmingRelevant(os)) relevant.add(acc.familyKey());
        }
        List<PerkMath.Accessory> out = new ArrayList<>();
        for (String f : relevant) out.addAll(byFamily.get(f));
        accessories = List.copyOf(out);
        return accessories;
    }

    /** Forget the index (an admin reload, or a module registering items late). */
    void reindex() { accessories = null; cache.clear(); overbloomCache.clear(); }

    int indexedAccessories() { return accessories().size(); }
    int indexedReforges() { return reforgeStats.size(); }
    int indexedPets() { return petStats.size(); }

    // ---- per player --------------------------------------------------------------------------------

    /** The accessories that count for this player (family rule applied), cached for a second. */
    List<PerkMath.Accessory> counted(Player p) {
        long now = System.currentTimeMillis();
        Snapshot s = cache.get(p.getUniqueId());
        if (s != null && now - s.at < 1000) return s.counted;
        List<PerkMath.Accessory> owned = new ArrayList<>();
        if (Sky.has(Features.Accessories.class)) {
            Features.Accessories acc = Sky.service(Features.Accessories.class);
            for (PerkMath.Accessory a : accessories()) {
                try { if (acc.has(p, a.id())) owned.add(a); } catch (RuntimeException ignored) { }
            }
        }
        List<PerkMath.Accessory> counted = PerkMath.counted(owned);
        cache.put(p.getUniqueId(), new Snapshot(now, counted));
        if (cache.size() > 512) cache.entrySet().removeIf(e -> now - e.getValue().at > 5000);
        return counted;
    }

    /** The active pet's level-scaled extra stat (0 without a pet, or when it has none). */
    double petStat(Player p, String key) {
        if (!Sky.has(Features.Pets.class)) return 0;
        Features.Pets pets = Sky.service(Features.Pets.class);
        String type = pets.activeType(p);
        if (type == null) return 0;
        Map<String, Map<String, double[]>> tiers = petStats.get(type);
        if (tiers == null) return 0;
        String rar = pets.activeRarity(p);
        Map<String, double[]> st = rar == null ? null : tiers.get(rar.toUpperCase(java.util.Locale.ROOT));
        double[] v = st == null ? null : st.get(key.toUpperCase(java.util.Locale.ROOT));
        if (v == null) return 0;
        return PerkMath.petLerp(v[0], v[1], PerkMath.petStatLevel(petCurve.get(type), pets.activeLevel(p)));
    }

    /** Per-crop fortune from accessories and the pet, one line per source (for the drop roll AND the /fortune page). */
    List<Harvest.Part> cropParts(Player p, Crop c) {
        List<Harvest.Part> out = new ArrayList<>();
        for (PerkMath.Accessory a : counted(p)) {
            double v = PerkMath.cropFortune(a.otherStats(), c);
            if (v != 0) out.add(new Harvest.Part(name(a.id()), v));
        }
        double pet = 0;
        for (String k : PerkMath.statKeys(c)) { pet = petStat(p, k); if (pet != 0) break; }
        if (pet != 0) out.add(new Harvest.Part(petName(p) + " pet", pet));
        FarmingModule m = FarmingModule.M;
        if (m != null && Sky.profiles().isReady(p)) {
            // Pig pet "Shining Stampede": +a Potato Fortune per Shiny Pig bestiary tier (info perk the pets module leaves to us)
            if (c == Crop.POTATO) {
                double per = petAbility(p, "pig_shining_stampede", "a");
                int tier = per > 0 ? ShinyMath.tier(shinyKills(p), m.tables.shinyPigTiers) : 0;
                double v = ShinyMath.stampedeFortune(per, tier);
                if (v != 0) out.add(new Harvest.Part("Pig pet Shine Rush (Shiny Pig tier " + tier + ")", v));
            }
            // Refined Dark Cacao Truffle (materials request 11): +30 for 60 minutes, +1 permanent each up to 5
            if (c == Crop.COCOA) {
                FarmData d = m.data(p);
                boolean buff = d.truffleUntil > System.currentTimeMillis();
                int cap = m.dials.trufflePermanentMax.get();
                int perm = Math.max(0, Math.min(d.trufflesEaten, Math.max(0, cap)));
                if (perm > 0) out.add(new Harvest.Part("Dark Cacao Truffles eaten (" + perm + "/" + cap + ")", perm));
                if (buff) out.add(new Harvest.Part("Dark Cacao Truffle (" + Text.duration((d.truffleUntil - System.currentTimeMillis()) / 1000) + ")",
                        Math.max(0, m.dials.truffleFortune.get())));
            }
        }
        return out;
    }

    /** Shiny Pigs this player has in the bestiary (progression's count; our own delivered count without it). */
    static long shinyKills(Player p) {
        if (Sky.has(Features.Bestiary.class)) {
            try { return Sky.service(Features.Bestiary.class).kills(p, Shiny.FAMILY); } catch (RuntimeException ignored) { }
        }
        FarmingModule m = FarmingModule.M;
        return m == null ? 0 : m.data(p).shinyPigsCaught;
    }

    /** An active-pet perk's number (0 without the pet / without the pets module). */
    static double petAbility(Player p, String key, String token) {
        if (!Sky.has(Features.Pets.class)) return 0;
        try { return Sky.service(Features.Pets.class).abilityValue(p, key, token); } catch (RuntimeException e) { return 0; }
    }

    /** Orchid Mantis "Bright Mind": farming tools earn this many % more experience. */
    static double toolXpPercent(Player p) {
        return Math.max(0, petAbility(p, "orchid_mantis_intelligent_specimen", "a"));
    }

    double cropBonus(Player p, Crop c) {
        double t = 0;
        for (Harvest.Part pt : cropParts(p, c)) t += pt.value();
        return t;
    }

    /**
     * The part of {@link #cropBonus} the Garden does NOT add itself. Since wave 5 the garden counts accessories'
     * per-crop {@code otherStats} on its own ({@code Effects.accessoryCropFortune}), so carrying them on the tool too
     * would pay them twice there; the pet's crop stat, Shine Rush and the truffle it does not read.
     */
    double gardenCropBonus(Player p, Crop c, boolean withAccessories) {
        if (withAccessories) return cropBonus(p, c);
        double acc = 0;
        for (PerkMath.Accessory a : counted(p)) acc += PerkMath.cropFortune(a.otherStats(), c);
        return cropBonus(p, c) - acc;
    }

    private final Map<UUID, long[]> chipCache = new HashMap<>();

    /**
     * The Garden's Cogmind chip level (Mechamind, garden request 8a), read through the garden's requirement key
     * {@code garden:chip:MECHAMIND_GARDEN_CHIP:<n>} by binary search, cached 5 s. 0 without the garden module.
     */
    int cogmindLevel(Player p) {
        long now = System.currentTimeMillis();
        long[] c = chipCache.get(p.getUniqueId());
        if (c != null && now - c[0] < 5000) return (int) c[1];
        int lvl = 0;
        if (Sky.requirements().known("garden")) {
            int lo = 0, hi = 64;                                         // chips cap at 20 (dial garden.chip-max-level)
            while (lo < hi) {
                int mid = (lo + hi + 1) / 2;
                boolean ok;
                try { ok = Sky.requirements().check(p, "garden:chip:MECHAMIND_GARDEN_CHIP:" + mid); } catch (RuntimeException e) { ok = false; }
                if (ok) lo = mid; else hi = mid - 1;
            }
            lvl = lo;
        }
        chipCache.put(p.getUniqueId(), new long[]{now, lvl});
        if (chipCache.size() > 512) chipCache.entrySet().removeIf(e -> now - e.getValue()[0] > 10_000);
        return lvl;
    }

    /** Multiplier on farming-tool XP: Orchid Mantis Bright Mind x the Garden's Cogmind chip (+1.5% a level). */
    double toolXpMultiplier(Player p) {
        FarmingModule m = FarmingModule.M;
        double chip = m == null ? 0 : Math.max(0, m.dials.cogmindPercent.get()) * cogmindLevel(p);
        return (1 + toolXpPercent(p) / 100.0) * (1 + chip / 100.0);
    }

    /** Overbloom, one line per source. */
    List<Harvest.Part> overbloomParts(Player p) {
        List<Harvest.Part> out = new ArrayList<>();
        ItemStack hand = p.getInventory().getItemInMainHand();
        double held = reforgeOverbloom(hand);
        if (held != 0) out.add(new Harvest.Part("Held tool reforge", held));
        double worn = 0;
        for (ItemStack s : p.getInventory().getArmorContents()) worn += reforgeOverbloom(s);
        for (ItemStack s : equipment(p)) worn += reforgeOverbloom(s);
        if (worn != 0) out.add(new Harvest.Part("Worn reforges", worn));
        for (PerkMath.Accessory a : counted(p)) {
            double v = PerkMath.number(a.otherStats(), "OVERBLOOM");
            if (v != 0) out.add(new Harvest.Part(name(a.id()), v));
        }
        double pet = petStat(p, "OVERBLOOM");
        if (pet != 0) out.add(new Harvest.Part(petName(p) + " pet", pet));
        // Rose Dragon "Feast": +a Overbloom (an info perk; farming is where Overbloom is spent outside the Garden)
        double feast = petAbility(p, "rose_dragon_dragon_s_gluttony", "a");
        if (feast > 0) out.add(new Harvest.Part(petName(p) + " pet (Feast)", feast));
        return out;
    }

    private final Map<UUID, double[]> overbloomCache = new HashMap<>();

    /** Total Overbloom, cached for a second (read on every crop broken during a Harvest Fair; it decodes 9 stacks). */
    double overbloom(Player p) {
        long now = System.currentTimeMillis();
        double[] c = overbloomCache.get(p.getUniqueId());
        if (c != null && now - (long) c[0] < 1000) return c[1];
        double t = 0;
        for (Harvest.Part pt : overbloomParts(p)) t += pt.value();
        overbloomCache.put(p.getUniqueId(), new double[]{now, t});
        if (overbloomCache.size() > 512) overbloomCache.entrySet().removeIf(e -> now - (long) e.getValue()[0] > 5000);
        return t;
    }

    private double reforgeOverbloom(ItemStack s) {
        if (s == null || s.getType().isAir() || reforgeStats.isEmpty()) return 0;
        String id = Sky.items().idOf(s);
        if (id == null) return 0;
        ItemData d = Sky.items().data(s);
        if (d.reforge == null) return 0;
        Map<String, JsonObject> byRarity = reforgeStats.get(d.reforge.toLowerCase(java.util.Locale.ROOT));
        if (byRarity == null) return 0;
        com.skyclone.api.item.Rarity r = Sky.items().rarity(s);
        // missing rarity = nearest lower one defined (the ReforgeDef rule)
        for (int i = r.ordinal(); i >= 0; i--) {
            JsonObject o = byRarity.get(com.skyclone.api.item.Rarity.values()[i].name());
            if (o != null) return PerkMath.number(o, "OVERBLOOM");
        }
        return 0;
    }

    /** Worn equipment (necklace / cloak / belt / gloves), never null. */
    static List<ItemStack> equipment(Player p) {
        if (!Sky.has(Features.Equipment.class)) return List.of();
        try {
            List<ItemStack> w = Sky.service(Features.Equipment.class).worn(p);
            return w == null ? List.of() : w;
        } catch (RuntimeException e) { return List.of(); }
    }

    /** Is a piece with this id worn (armour or equipment slot)? */
    static boolean wears(Player p, String id) {
        for (ItemStack s : p.getInventory().getArmorContents()) if (s != null && id.equals(Sky.items().idOf(s))) return true;
        for (ItemStack s : equipment(p)) if (s != null && id.equals(Sky.items().idOf(s))) return true;
        return false;
    }

    /** Does the accessories module count this accessory for the player (bag or inventory)? */
    static boolean has(Player p, String id) {
        if (!Sky.has(Features.Accessories.class)) return false;
        try { return Sky.service(Features.Accessories.class).has(p, id); } catch (RuntimeException e) { return false; }
    }

    private static String name(String id) {
        ItemDef d = Sky.items().def(id);
        return d == null ? id : Text.strip(d.name);
    }

    private String petName(Player p) {
        String t = Sky.service(Features.Pets.class).activeType(p);
        if (t == null) return "Pet";
        String n = petNames.get(t);
        return n != null ? Text.strip(n) : Text.titleCase(t);
    }
}
