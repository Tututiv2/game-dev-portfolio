package com.skyclone.mod.enchanting;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads enchants off what a player wears and holds. Armour + equipment are cached per player and
 * refreshed every 10 ticks by the "enchanting" StatProvider (which core already calls that often),
 * so per-hit handlers never re-parse four item JSON blobs.
 */
final class Gear {

    private Gear() {}

    /** Per player: one enchant map per worn armour piece and equipment item. */
    private static final Map<UUID, List<Map<String, Integer>>> WORN = new HashMap<>();
    private static final Map<UUID, List<String>> WORN_IDS = new HashMap<>();

    static boolean isSky(ItemStack s) {
        return s != null && !s.getType().isAir() && Sky.items().idOf(s) != null;
    }

    static Map<String, Integer> enchants(ItemStack s) {
        if (!isSky(s)) return Map.of();
        ItemData d = Sky.items().data(s);
        return d.enchants == null ? Map.of() : d.enchants;
    }

    static int level(ItemStack s, String enchant) {
        Integer l = enchants(s).get(enchant);
        return l == null ? 0 : l;
    }

    static ItemDef def(ItemStack s) {
        String id = isSky(s) ? Sky.items().idOf(s) : null;
        return id == null ? null : Sky.items().def(id);
    }

    static ItemStack held(Player p) { return p.getInventory().getItemInMainHand(); }

    /** Rebuild the worn cache (called from the stat provider every 10 ticks). */
    static void refresh(Player p) {
        List<Map<String, Integer>> maps = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (ItemStack a : p.getInventory().getArmorContents()) {
            if (!isSky(a)) continue;
            ItemDef d = def(a);
            if (d == null || d.category == null || !d.category.isArmor()) continue;
            maps.add(new HashMap<>(enchants(a)));
            ids.add(d.id);
        }
        try {
            List<ItemStack> eq = Sky.service(Features.Equipment.class).worn(p);
            if (eq != null) for (ItemStack a : eq) {
                if (!isSky(a)) continue;
                maps.add(new HashMap<>(enchants(a)));
                ids.add(Sky.items().idOf(a));
            }
        } catch (RuntimeException ignored) { /* equipment module absent or broken: armour still counts */ }
        WORN.put(p.getUniqueId(), maps);
        WORN_IDS.put(p.getUniqueId(), ids);
    }

    static void forget(UUID u) { WORN.remove(u); WORN_IDS.remove(u); }

    static List<Map<String, Integer>> worn(Player p) {
        List<Map<String, Integer>> l = WORN.get(p.getUniqueId());
        if (l == null) { refresh(p); l = WORN.get(p.getUniqueId()); }
        return l == null ? List.of() : l;
    }

    /** Sum of one value series of an enchant over every worn piece (armour enchants stack per piece). */
    static double wornSum(Player p, String enchant, int series) {
        EnchantSpec s = EnchantingModule.reg().spec(enchant);
        if (s == null) return 0;
        double t = 0;
        for (Map<String, Integer> m : worn(p)) {
            Integer l = m.get(enchant);
            if (l != null && l > 0) t += s.v(series, l);
        }
        return t;
    }

    static double wornSum(Player p, String enchant) { return wornSum(p, enchant, 0); }

    /** Highest level of an enchant on any worn piece. */
    static int wornMax(Player p, String enchant) {
        int best = 0;
        for (Map<String, Integer> m : worn(p)) {
            Integer l = m.get(enchant);
            if (l != null && l > best) best = l;
        }
        return best;
    }

    static boolean wears(Player p, String enchant) { return wornMax(p, enchant) > 0; }
}
