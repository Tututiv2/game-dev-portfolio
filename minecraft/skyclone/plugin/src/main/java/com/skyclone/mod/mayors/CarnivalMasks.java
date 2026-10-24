package com.skyclone.mod.mayors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.time.SkyDate;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * What the Carnival's masks do beyond their stats (neu-repo *_MASK.json), as one StatProvider (wave 5: all six live):
 * <ul>
 *   <li>Zombie Mask (Exanimate Blessing): the stats of Undead armor pieces worn with it +5% (chest, legs, boots - the
 *       mask is the helmet; "undead" = ids containing a word of dial {@code mayors.carnival.undead-armor}, TUNE).</li>
 *   <li>Salmon Mask: +10 Speed and +10 Fishing Speed while in water.</li>
 *   <li>Armadillo Mask: +50 Block Fortune.</li>
 *   <li>Parrot Mask (Party Flock): every OTHER player within 20 blocks gains +1 Strength per upgrade of the wearer's
 *       Intimidation accessory (Talisman 1, Ring 2, Artifact 3, Relic 4); the best nearby wearer counts.</li>
 *   <li>Bee Mask (Buzzy Little Bee): your summoned Bee pet's base stats again (pets' own data and level curve).</li>
 *   <li>Frog Mask (Stick Around): one Park region a SkyBlock day (seeded by the date) grants +20 Foraging Fortune and
 *       +5 Foraging Wisdom while you stand in it.</li>
 *   <li>Carnival Mask Bag (an accessory owned by accessories): +1 Intelligence per different mask you carry (inventory
 *       or worn); accessories counts the masks stored INSIDE the bag and skips the ones we count.</li>
 * </ul>
 */
final class CarnivalMasks {

    static final String[] INTIMIDATION = {"INTIMIDATION_TALISMAN", "INTIMIDATION_RING", "INTIMIDATION_ARTIFACT", "INTIMIDATION_RELIC"};
    static final double ZOMBIE_BONUS = 0.05;
    static final double FROG_FORTUNE = 20, FROG_WISDOM = 5;

    private final Set<UUID> wet = new HashSet<>();
    /** Party Flock Strength each player currently gets from a nearby Parrot Mask. */
    private final Map<UUID, Integer> flock = new HashMap<>();
    private Map<String, double[][]> beeStats;   // rarity -> {s1 values, s100 values} in Stat order of beeKeys
    private List<Stat> beeKeys = List.of();

    void register() {
        Sky.stats().addProvider("mayors_masks", this::contribute);
        // In/out of water flips the Salmon Mask, the Parrot's flock follows people around, the Frog's region changes
        // with the day: re-read stats when any of them changes (once a second is plenty).
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::refresh, 40L, 20L);
    }

    private void refresh() {
        Map<UUID, Integer> now = new HashMap<>();
        List<Player> parrots = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if ("PARROT_MASK".equals(helmet(p))) parrots.add(p);
        int range = Math.max(1, MayorsModule.get() == null ? 20 : MayorsModule.get().dials().carnivalParrotRange.get());
        for (Player wearer : parrots) {
            int tier;
            try {
                Features.Accessories a = Sky.service(Features.Accessories.class);
                tier = intimidationTier(id -> a.has(wearer, id));
            } catch (Throwable t) { tier = 0; }
            if (tier <= 0) continue;
            for (Player other : wearer.getWorld().getPlayers()) {
                if (other == wearer || other.getLocation().distanceSquared(wearer.getLocation()) > (double) range * range) continue;
                now.merge(other.getUniqueId(), tier, Math::max);
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            boolean changed = !java.util.Objects.equals(now.get(u), flock.get(u));
            boolean salmon = "SALMON_MASK".equals(helmet(p));
            boolean inWater = salmon && p.isInWater();
            if (inWater != wet.contains(u)) { changed = true; if (inWater) wet.add(u); else wet.remove(u); }
            String head = helmet(p);
            if (changed || "FROG_MASK".equals(head)) Sky.stats().invalidate(p);
        }
        flock.clear();
        flock.putAll(now);
        wet.removeIf(u -> Bukkit.getPlayer(u) == null);
    }

    private static String helmet(Player p) {
        try { return Sky.items().idOf(p.getInventory().getHelmet()); } catch (Throwable t) { return null; }
    }

    static boolean isMask(String id) {
        if (id == null) return false;
        ItemDef d = Sky.items().def(id);
        return d != null && d.extra != null && d.extra.has("carnivalMask") && d.extra.get("carnivalMask").getAsBoolean();
    }

    // =============================================================================================== pure pieces

    /** Upgrade of the best Intimidation accessory: Talisman 1 .. Relic 4, 0 without one. */
    static int intimidationTier(Predicate<String> has) {
        for (int i = INTIMIDATION.length - 1; i >= 0; i--) if (has.test(INTIMIDATION[i])) return i + 1;
        return 0;
    }

    /** Is this armor id an Undead piece (contains one of the comma-separated keywords)? */
    static boolean undead(String id, String keywords) {
        if (id == null || keywords == null) return false;
        for (String k : keywords.split(",")) {
            String w = k.trim().toUpperCase(Locale.ROOT);
            if (!w.isEmpty() && id.toUpperCase(Locale.ROOT).contains(w)) return true;
        }
        return false;
    }

    /** The Frog Mask's region for a SkyBlock date: one of {@code regions}, the same all day, changing day to day. */
    static String frogRegion(List<String> regions, int year, int month, int day) {
        if (regions == null || regions.isEmpty()) return null;
        long abs = (long) year * 372 + (long) (month - 1) * 31 + (day - 1);
        long h = abs * 0x9E3779B97F4A7C15L;
        h ^= (h >>> 29);
        return regions.get((int) Math.floorMod(h, (long) regions.size()));
    }

    /** Pet stat curve (pets' PetMath.lerp): the level-1 value up to the level-100 value. */
    static double lerp(double at1, double at100, int level) {
        int l = Math.max(1, Math.min(100, level));
        return at1 + (at100 - at1) * (l - 1) / 99.0;
    }

    // =============================================================================================== provider

    private void contribute(Player p, StatMap into) {
        String head = helmet(p);
        if ("SALMON_MASK".equals(head) && p.isInWater()) {
            into.add(Stat.SPEED, 10);
            into.add(Stat.FISHING_SPEED, 10);
        }
        if ("ARMADILLO_MASK".equals(head)) into.add(Stat.BLOCK_FORTUNE, 50);
        if ("ZOMBIE_MASK".equals(head)) zombie(p, into);
        if ("BEE_MASK".equals(head)) bee(p, into);
        if ("FROG_MASK".equals(head) && frogHere(p)) {
            into.add(Stat.FORAGING_FORTUNE, FROG_FORTUNE);
            into.add(Stat.FORAGING_WISDOM, FROG_WISDOM);
        }
        Integer fl = flock.get(p.getUniqueId());
        if (fl != null && fl > 0) into.add(Stat.STRENGTH, fl);
        boolean bag;
        try { bag = Sky.service(Features.Accessories.class).has(p, "CARNIVAL_MASK_BAG"); } catch (Throwable t) { bag = false; }
        if (!bag) return;
        Set<String> masks = new HashSet<>();
        if (isMask(head)) masks.add(head);
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (it == null || it.getType().isAir()) continue;
            String id = Sky.items().idOf(it);
            if (id != null && id.endsWith("_MASK") && isMask(id)) masks.add(id);
        }
        if (!masks.isEmpty()) into.add(Stat.INTELLIGENCE, masks.size());
    }

    private void zombie(Player p, StatMap into) {
        String words = MayorsModule.get() == null ? "ZOMBIE,SKELETON,REVENANT,REAPER,ROTTEN" : MayorsModule.get().dials().carnivalUndeadArmor.get();
        ItemStack[] pieces = {p.getInventory().getChestplate(), p.getInventory().getLeggings(), p.getInventory().getBoots()};
        for (ItemStack it : pieces) {
            String id = it == null ? null : Sky.items().idOf(it);
            if (!undead(id, words)) continue;
            ItemDef d = Sky.items().def(id);
            if (d == null || d.stats == null) continue;
            for (Map.Entry<Stat, Double> e : d.stats.entrySet()) into.add(e.getKey(), e.getValue() * ZOMBIE_BONUS);
        }
    }

    private void bee(Player p, StatMap into) {
        try {
            Features.Pets pets = Sky.service(Features.Pets.class);
            if (!"BEE".equals(pets.activeType(p))) return;
            Map<String, double[][]> table = beeTable();
            String rarity = pets.activeRarity(p);
            double[][] t = table.get(rarity == null ? "" : rarity.toUpperCase(Locale.ROOT));
            if (t == null) return;
            int lvl = pets.activeLevel(p);
            for (int i = 0; i < beeKeys.size(); i++) into.add(beeKeys.get(i), lerp(t[0][i], t[1][i], lvl));
        } catch (Throwable ignored) { }
    }

    /** The Bee pet's level-1 / level-100 base stats per rarity, read once from pets' data (data/pets/pets.json). */
    private Map<String, double[][]> beeTable() {
        if (beeStats != null) return beeStats;
        Map<String, double[][]> out = new HashMap<>();
        List<Stat> keys = new ArrayList<>();
        try (InputStream in = Sky.plugin().getResource("data/pets/pets.json")) {
            if (in != null) {
                JsonArray arr = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
                for (JsonElement el : arr) {
                    JsonObject o = el.getAsJsonObject();
                    if (!"BEE".equals(o.get("id").getAsString())) continue;
                    JsonObject tiers = o.getAsJsonObject("tiers");
                    for (String r : tiers.keySet()) for (String k : tiers.getAsJsonObject(r).getAsJsonObject("s100").keySet()) {
                        Stat s = Stat.valueOf(k);
                        if (!keys.contains(s)) keys.add(s);
                    }
                    for (String r : tiers.keySet()) {
                        JsonObject t = tiers.getAsJsonObject(r);
                        double[][] v = new double[2][keys.size()];
                        for (int i = 0; i < keys.size(); i++) {
                            String k = keys.get(i).name();
                            v[0][i] = t.getAsJsonObject("s1").has(k) ? t.getAsJsonObject("s1").get(k).getAsDouble() : 0;
                            v[1][i] = t.getAsJsonObject("s100").has(k) ? t.getAsJsonObject("s100").get(k).getAsDouble() : 0;
                        }
                        out.put(r.toUpperCase(Locale.ROOT), v);
                    }
                }
            }
        } catch (Throwable t) {
            Sky.log().warning("[mayors] Bee Mask could not read the Bee pet's stats: " + t);
        }
        beeKeys = keys;
        beeStats = out;
        return out;
    }

    private static boolean frogHere(Player p) {
        if (!"park".equals(Sky.worlds().islandOf(p.getWorld()))) return false;
        Layout.Zone here = Sky.worlds().zoneOf(p);
        if (here == null) return false;
        return here.id().equals(frogRegionToday());
    }

    /** Today's Frog Mask region on the Park (null when the Park has no regions). */
    static String frogRegionToday() {
        Layout l = Sky.worlds().layout("park");
        if (l == null) return null;
        List<String> ids = new ArrayList<>();
        for (Layout.Zone z : l.zones()) if (!"park".equals(z.id())) ids.add(z.id());
        SkyDate d = Sky.calendar().now();
        return frogRegion(ids, d.year(), d.month(), d.day());
    }
}
