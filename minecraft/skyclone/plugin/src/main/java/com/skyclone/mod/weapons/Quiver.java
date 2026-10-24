package com.skyclone.mod.weapons;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.stat.Stat;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Where arrows come from. The storage module owns the quiver itself and exposes it through
 * {@code Features.Sacks} (count/remove by arrow id); the inventory is checked first. A player may
 * pick a preferred arrow in {@code /arrows}; otherwise the cheapest usable arrow goes first, so
 * nobody burns Magma Arrows by accident.
 */
final class Quiver {

    private Quiver() {}

    private static List<ItemDef> arrowCache;
    private static long arrowCacheAt;

    /**
     * Every arrow type, weakest first. Cached for 30 s: the Quiver Arrow asks this twice a second for every
     * bow holder, and the item registry holds thousands of defs.
     */
    static List<ItemDef> arrowTypes() {
        long now = System.currentTimeMillis();
        List<ItemDef> c = arrowCache;
        if (c != null && now - arrowCacheAt < 30_000) return c;
        List<ItemDef> out = new ArrayList<>();
        for (ItemDef d : Sky.items().all()) if (d.category == ItemCategory.ARROW) out.add(d);
        out.sort(Comparator.comparingDouble((ItemDef d) -> d.damage).thenComparing(d -> d.id));
        arrowCache = List.copyOf(out);
        arrowCacheAt = now;
        return arrowCache;
    }

    /**
     * Infinite Quiver (research 01: 3% x level chance not to use an arrow, X = 30%), applied wherever THIS
     * module takes the arrow (shortbows, a preferred arrow, the Quiver Arrow). When vanilla takes the arrow
     * itself the enchanting module's own roll applies, so a shot is never rolled twice.
     */
    static boolean saved(org.bukkit.inventory.ItemStack bow) {
        if (bow == null) return false;
        int lvl;
        try { lvl = Sky.items().data(bow).enchant("infinite_quiver"); } catch (RuntimeException e) { return false; }
        double pct = WeaponMath.infiniteQuiverPct(lvl, WeaponDials.INFINITE_QUIVER_PER_LEVEL.get());
        return pct > 0 && Math.random() * 100 < pct;
    }

    static List<ItemDef> poisons() {
        List<ItemDef> out = new ArrayList<>();
        for (ItemDef d : Sky.items().all()) if (d.has("arrow_poison")) out.add(d);
        out.sort(Comparator.comparing(d -> d.id));
        return out;
    }

    static int available(Player p, String id) {
        int n = 0;
        try { n += Sky.items().count(p, id); } catch (RuntimeException ignored) { }
        try { n += Math.max(0, Sky.service(Features.Sacks.class).count(p, id)); } catch (RuntimeException ignored) { }
        return n;
    }

    static boolean usable(Player p, ItemDef d) {
        if (d == null) return false;
        for (String req : d.requirements) {
            try { if (!Sky.requirements().check(p, req)) return false; } catch (RuntimeException e) { return false; }
        }
        return true;
    }

    /** The arrow the next shot will use, or null when the player has none they may use. */
    static ItemDef pick(Player p) {
        WeaponData data = WeaponsModule.data(p);
        if (data != null && data.selectedArrow != null && !data.selectedArrow.isEmpty()) {
            ItemDef sel = Sky.items().def(data.selectedArrow);
            if (sel != null && usable(p, sel) && available(p, sel.id) > 0) return sel;
        }
        for (ItemDef d : arrowTypes()) {
            if (usable(p, d) && available(p, d.id) > 0) return d;
        }
        return null;
    }

    /** Take one arrow: inventory first, then the quiver/sacks. */
    static boolean consume(Player p, String id) {
        try {
            if (Sky.items().count(p, id) > 0 && Sky.items().remove(p, id, 1) == 1) return true;
        } catch (RuntimeException ignored) { }
        try { return Sky.service(Features.Sacks.class).remove(p, id, 1) == 1; } catch (RuntimeException e) { return false; }
    }

    /** The first poison the player carries (and wants to use), or null. */
    static ItemDef poison(Player p) {
        if (!WeaponDials.POISONS_ENABLED.get()) return null;
        WeaponData data = WeaponsModule.data(p);
        if (data != null && !data.usePoisons) return null;
        for (ItemDef d : poisons()) if (available(p, d.id) > 0) return d;
        return null;
    }

    static double arrowCritChance(ItemDef arrow) {
        if (arrow == null) return 0;
        Double v = arrow.stats.get(Stat.CRIT_CHANCE);
        return v == null ? 0 : v;
    }
}
