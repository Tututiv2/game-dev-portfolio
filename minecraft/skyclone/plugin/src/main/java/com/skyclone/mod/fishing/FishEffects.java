package com.skyclone.mod.fishing;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import org.bukkit.entity.Player;

/**
 * Effects other modules' items, pets and attributes carry whose MECHANIC lives in fishing (wave 5).
 * Each owner ships the number in its lore and leaves the behaviour to us:
 *
 * <ul>
 *   <li>accessories: Bait Ring / Spiked Atrocity (bait), Tera Shell Necklace (no trophy fish), the five
 *       Shark Tooth Necklaces (shark teeth during the festival), Pulse Ring (thunder bottles);</li>
 *   <li>pets ({@code Features.Pets.abilityValue}): Slug "Patience" (Slugfish wait), Flying Fish
 *       "Rapid Rot" (Flash procs more often);</li>
 *   <li>hunting attributes ({@code Features.Hunting.attributeLevel(p, "value:<key>")}, the number to
 *       apply): Night Squid "fisherman" (bait), Cod "cheapstake" (treasure coins), Mantis Shrimp
 *       "great_treasure", Blue Crab "outstanding_fisher", Litterbug "buckle_fisher" / Maggot "boot_fisher"
 *       (junk quality), Salmon "lost_and_found" (Wet Book), Magma Slug "infection" (corruption), Goldfin
 *       "gold_bait" (Golden Fish sooner).</li>
 * </ul>
 * Every reader is wrapped: a module that is absent or throws counts as 0.
 */
final class FishEffects {

    private FishEffects() {}

    // ---- accessories ----------------------------------------------------------------------------

    static boolean has(Player p, String accessoryId) {
        try { return Sky.service(Features.Accessories.class).has(p, accessoryId); } catch (RuntimeException e) { return false; }
    }

    /** Shark Tooth Necklaces, best one owned: +5/10/15/20/25% shark teeth during the festival (accessories' lore). */
    static double sharkToothBonus(Player p) {
        if (has(p, "RAZOR_SHARP_SHARK_TOOTH_NECKLACE")) return 0.25;
        if (has(p, "SHARP_SHARK_TOOTH_NECKLACE")) return 0.20;
        if (has(p, "HONED_SHARK_TOOTH_NECKLACE")) return 0.15;
        if (has(p, "DULL_SHARK_TOOTH_NECKLACE")) return 0.10;
        if (has(p, "RAGGEDY_SHARK_TOOTH_NECKLACE")) return 0.05;
        return 0;
    }

    /** "You can no longer catch Trophies while wearing this Necklace." */
    static boolean teraShell(Player p) { return has(p, "TERA_SHELL_NECKLACE"); }

    static boolean pulseRing(Player p) { return has(p, "PULSE_RING"); }

    // ---- pets -----------------------------------------------------------------------------------

    static double pet(Player p, String key) {
        try { return Sky.service(Features.Pets.class).abilityValue(p, key, "a"); } catch (RuntimeException e) { return 0; }
    }

    static String petType(Player p) {
        try { return Sky.service(Features.Pets.class).activeType(p); } catch (RuntimeException e) { return null; }
    }

    /** Slug "Patience": slugfish take a% less time on the Crimson Isle. Falls back to 50% if pets reports no number. */
    static double slugPercent(Player p) {
        double v = pet(p, "slug_slow_and_steady");
        if (v <= 0 && "SLUG".equalsIgnoreCase(petType(p))) v = 50;   // the pre-wave-5 rule (10 s of 20)
        return v;
    }

    /**
     * Pet perks that are ODDS inside fishing's own rolls (Bat "Sonar" spooky, Hermit Crab "Hotspot Hazard" hotspot,
     * Megalodon "Feeding Frenzy" sharks, Spinosaurus "Trophy Chase" gold/diamond trophies), as a fraction.
     * Pets approximates these today with its own "echo" effect; applying both would pay twice. So fishing applies
     * one only once pets' data marks it {@code {"t":"external","by":"fishing"}} (pets' own handshake marker,
     * read from {@code data/pets/pets.json}); until then it is 0 here and pets' version runs.
     */
    static double petOdds(Player p, String key) {
        if (p == null || !FishDials.PET_ODDS.get() || !externalToFishing().contains(key)) return 0;
        return Math.max(0, pet(p, key)) / 100.0;
    }

    private static java.util.Set<String> external;

    /** Ability keys pets hands to fishing (fx {@code external} by {@code fishing}). Lazy, read once. */
    static synchronized java.util.Set<String> externalToFishing() {
        if (external != null) return external;
        java.util.Set<String> out = new java.util.HashSet<>();
        try (java.io.InputStream in = Sky.plugin().getResource("data/pets/pets.json")) {
            if (in != null) {
                var arr = new com.google.gson.Gson().fromJson(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8),
                        com.google.gson.JsonArray.class);
                for (var pe : arr) {
                    var pet = pe.getAsJsonObject();
                    if (!pet.has("tiers") || !pet.get("tiers").isJsonObject()) continue;
                    for (var t : pet.getAsJsonObject("tiers").entrySet()) {
                        if (!t.getValue().isJsonObject() || !t.getValue().getAsJsonObject().has("abilities")) continue;
                        for (var ab : t.getValue().getAsJsonObject().getAsJsonArray("abilities")) {
                            var a = ab.getAsJsonObject();
                            if (!a.has("key") || !a.has("fx")) continue;
                            for (var fx : a.getAsJsonArray("fx")) {
                                var f = fx.getAsJsonObject();
                                if (f.has("t") && "external".equals(f.get("t").getAsString())
                                        && f.has("by") && "fishing".equals(f.get("by").getAsString()))
                                    out.add(a.get("key").getAsString());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Sky.log().warning("[fishing] could not read pets' ability markers: " + e);
        }
        external = out;
        return out;
    }

    /** Flying Fish "Rapid Rot": the Flash enchantment triggers a% more often (multiplier). */
    static double flashMult(Player p) { return 1 + Math.max(0, pet(p, "flying_fish_rapid_decay")) / 100.0; }

    // ---- hunting attributes ---------------------------------------------------------------------

    /** The number an attribute hook applies (level x per-level x echoes), 0 when none / hunting absent. */
    static double attr(Player p, String key) {
        if (!FishDials.HUNTING_HOOKS.get()) return 0;
        try { return Math.max(0, Sky.service(Features.Hunting.class).attributeLevel(p, "value:" + key)); }
        catch (RuntimeException e) { return 0; }
    }

    /** Chance not to use bait from attributes ("fisherman", Night Squid shard), a fraction. */
    static double baitKeep(Player p) { return attr(p, "fisherman") / 100.0; }

    /** Extra coins from coin treasure, a fraction ("cheapstake", Cod shard). */
    static double treasureCoins(Player p) { return attr(p, "cheapstake") / 100.0; }

    /** {greatOnly, outstandingOnly} for a treasure (or junk) roll. */
    static double[] treasureQuality(Player p, boolean junk) {
        if (junk) return new double[]{attr(p, "buckle_fisher") / 100.0, attr(p, "boot_fisher") / 100.0};
        return new double[]{attr(p, "great_treasure") / 100.0, attr(p, "outstanding_fisher") / 100.0};
    }

    /** "+v% chance to find a Wet Book when fishing a Treasure" (Salmon shard), a fraction. */
    static double wetBook(Player p) { return attr(p, "lost_and_found") / 100.0; }

    /** "Increases the chance of catching corrupted Sea Creatures by +v%" (Magma Slug shard), added to Corruption. */
    static double infection(Player p) { return attr(p, "infection") / 100.0; }

    /** "The cooldown of Golden Fish is reduced by v s" (Goldfin shard), in minutes. */
    static double goldenSooner(Player p) { return attr(p, "gold_bait") / 60.0; }
}
