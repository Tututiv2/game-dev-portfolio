package com.skyclone.mod.armor;

import com.skyclone.api.Sky;
import com.skyclone.api.mob.MobDef;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Set;

/** Island and mob-kind questions the bonuses ask. */
final class ArmorUtil {

    private ArmorUtil() {}

    /** Islands the reference calls "Mining Islands" (Pressurized/Depth Coating, Lapis magnetism). */
    static final Set<String> MINING_ISLANDS = Set.of("gold_mine", "deep_caverns", "dwarven_mines", "crystal_hollows",
            "glacite_tunnels", "mineshaft");

    /**
     * Index into {@code PlayerInventory.getArmorContents()} (boots 0, leggings 1, chestplate 2,
     * helmet 3) for an armour category, or -1 when the category is not worn. Pure, unit-tested.
     */
    static int armorIndex(com.skyclone.api.item.ItemCategory c) {
        if (c == null) return -1;
        return switch (c) {
            case BOOTS -> 0;
            case LEGGINGS -> 1;
            case CHESTPLATE -> 2;
            case HELMET -> 3;
            default -> -1;
        };
    }

    static String island(Player p) {
        try { return Sky.worlds().islandOf(p.getWorld()); } catch (RuntimeException e) { return null; }
    }

    static boolean on(Player p, String island) { return island.equals(island(p)); }

    static boolean onMiningIsland(Player p) {
        String i = island(p);
        return i != null && MINING_ISLANDS.contains(i);
    }

    static MobDef def(LivingEntity e) {
        if (e == null || e instanceof Player) return null;
        try { return Sky.mobs().defOf(e); } catch (RuntimeException ex) { return null; }
    }

    static boolean seaCreature(MobDef d) { return d != null && d.has("sea_creature"); }

    static boolean magmatic(MobDef d) {
        if (d == null) return false;
        if (d.has("magmatic")) return true;
        if (d.extra != null && d.extra.has("magmatic") && d.extra.get("magmatic").getAsBoolean()) return true;
        return d.has("sea_creature") && matches(d, ArmorDials.MAGMATIC_WORDS.get());
    }

    static boolean mythic(MobDef d) {
        if (d == null) return false;
        return d.has("mythological") || matches(d, ArmorDials.MYTHIC_WORDS.get());
    }

    static boolean ghost(MobDef d) { return d != null && matches(d, ArmorDials.GHOST_WORDS.get()); }

    static boolean monster(MobDef d) { return d != null && !d.has("passive"); }

    static boolean matches(MobDef d, String commaWords) {
        if (commaWords == null || commaWords.isBlank()) return false;
        String id = (d.id == null ? "" : d.id).toLowerCase(Locale.ROOT);
        String fam = d.familyId() == null ? "" : d.familyId().toLowerCase(Locale.ROOT);
        for (String w : commaWords.split(",")) {
            String t = w.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty() && (id.contains(t) || fam.contains(t))) return true;
        }
        return false;
    }

    static boolean familyContains(MobDef d, String word) {
        if (d == null) return false;
        String id = d.id == null ? "" : d.id.toLowerCase(Locale.ROOT);
        String fam = d.familyId() == null ? "" : d.familyId().toLowerCase(Locale.ROOT);
        return id.contains(word) || fam.contains(word);
    }

    /** Is a calendar event running (null-safe, false on any error). */
    static boolean eventActive(String id) {
        if (id == null || id.isBlank()) return false;
        try { return Sky.calendar().active(id); } catch (RuntimeException e) { return false; }
    }

    static long longExtra(com.google.gson.JsonObject o, String key) {
        if (o == null || !o.has(key)) return 0;
        try { return o.get(key).getAsLong(); } catch (RuntimeException e) { return 0; }
    }
}
