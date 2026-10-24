package com.skyclone.mod.crafting;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The player's materials as crafting sees them: the 36 storage slots (never armour, off-hand or
 * the SkyBlock Menu star) plus, when enabled, their sacks. Counting and taking go through the same
 * canonical ids the recipes use.
 */
final class Stock {

    private Stock() {}

    private static final NamespacedKey STAR = NamespacedKey.fromString("skyclone:sbmenu");

    static boolean isStar(ItemStack s) {
        if (s == null || !s.hasItemMeta()) return false;
        ItemMeta m = s.getItemMeta();
        return m != null && STAR != null && m.getPersistentDataContainer().has(STAR);
    }

    /** Canonical id -> count over the main inventory. */
    static Map<String, Integer> inventory(Player p, RecipeIndex idx) {
        Map<String, Integer> m = new HashMap<>();
        if (p == null) return m;
        for (ItemStack s : p.getInventory().getStorageContents()) {
            if (s == null || s.getType().isAir() || isStar(s)) continue;
            String id = idx.stackId(s);
            if (id != null) m.merge(id, s.getAmount(), Integer::sum);
        }
        return m;
    }

    /** Inventory counts plus sack counts for the given ids (sacks only queried for ids a recipe needs). */
    static Map<String, Integer> withSacks(Player p, Map<String, Integer> inv, Set<String> ids, boolean useSacks,
                                          Map<String, Integer> sackMemo) {
        Map<String, Integer> m = new HashMap<>(inv);
        if (!useSacks || p == null || !Sky.has(Features.Sacks.class)) return m;
        Features.Sacks sacks = Sky.service(Features.Sacks.class);
        for (String id : ids) {
            int n = sackMemo == null ? safeCount(sacks, p, id) : sackMemo.computeIfAbsent(id, k -> safeCount(sacks, p, k));
            if (n > 0) m.merge(id, n, Integer::sum);
        }
        return m;
    }

    private static int safeCount(Features.Sacks sacks, Player p, String id) {
        try { return Math.max(0, sacks.count(p, id)); } catch (RuntimeException e) { return 0; }
    }

    /** What a take removed: the inventory stacks (copies, with their amounts) and sack amounts per id. */
    static final class Taken {
        final List<ItemStack> fromInventory = new ArrayList<>();
        final Map<String, Integer> fromSacks = new HashMap<>();
    }

    /**
     * Remove exactly {@code take} (canonical id -> amount): inventory first, then sacks. All-or-nothing:
     * when anything comes up short, everything already taken is put back and null is returned.
     */
    static Taken take(Player p, RecipeIndex idx, Map<String, Integer> take, boolean useSacks) {
        return take(p, idx, take, useSacks, 0);
    }

    /**
     * As {@link #take(Player, RecipeIndex, Map, boolean)}; for UNIQUE ingredients {@code prefer} picks which copies
     * go first: 1 = the most upgraded (an upgrade recipe carries them over), -1 = the plainest, 0 = inventory order.
     */
    static Taken take(Player p, RecipeIndex idx, Map<String, Integer> take, boolean useSacks, int prefer) {
        PlayerInventory inv = p.getInventory();
        // Verify first (same tick, nothing else runs in between).
        Map<String, Integer> have = inventory(p, idx);
        Features.Sacks sacks = Sky.service(Features.Sacks.class);
        boolean sacksOn = useSacks && Sky.has(Features.Sacks.class);
        for (Map.Entry<String, Integer> e : take.entrySet()) {
            int fromInv = have.getOrDefault(e.getKey(), 0);
            int rest = e.getValue() - fromInv;
            if (rest > 0 && (!sacksOn || safeCount(sacks, p, e.getKey()) < rest)) return null;
        }
        Taken t = new Taken();
        ItemStack[] c = inv.getStorageContents();
        for (Map.Entry<String, Integer> e : take.entrySet()) {
            int left = e.getValue();
            for (int i : slotOrder(c, idx, e.getKey(), prefer)) {
                if (left <= 0) break;
                ItemStack s = c[i];
                if (s == null || s.getType().isAir() || isStar(s)) continue;
                if (!e.getKey().equals(idx.stackId(s))) continue;
                int n = Math.min(left, s.getAmount());
                ItemStack copy = s.clone();
                copy.setAmount(n);
                t.fromInventory.add(copy);
                left -= n;
                if (n >= s.getAmount()) { inv.setItem(i, null); c[i] = null; }
                else { s.setAmount(s.getAmount() - n); inv.setItem(i, s); }
            }
            if (left > 0) {
                int got = 0;
                try { got = sacksOn ? sacks.remove(p, e.getKey(), left) : 0; } catch (RuntimeException ex) { got = 0; }
                if (got > 0) t.fromSacks.merge(e.getKey(), got, Integer::sum);
                if (got < left) {
                    refund(p, t);
                    return null;
                }
            }
        }
        return t;
    }

    /** Storage slot indices in the order {@link #take} should drain them for one id. */
    private static List<Integer> slotOrder(ItemStack[] c, RecipeIndex idx, String id, int prefer) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < c.length; i++) order.add(i);
        if (prefer == 0) return order;
        com.skyclone.api.item.ItemDef d = Sky.items().def(id);
        if (d == null || !d.isUnique()) return order;
        Map<Integer, Integer> worth = new HashMap<>();
        for (int i = 0; i < c.length; i++) {
            ItemStack s = c[i];
            if (s == null || s.getType().isAir() || !id.equals(idx.stackId(s))) continue;
            try { worth.put(i, CraftMath.upgradeWorth(Sky.items().data(s))); } catch (RuntimeException ex) { worth.put(i, 0); }
        }
        order.sort((a, b) -> Integer.compare(worth.getOrDefault(b, 0) * prefer, worth.getOrDefault(a, 0) * prefer));
        return order;
    }

    /** Put everything a failed take removed back where it came from. */
    static void refund(Player p, Taken t) {
        for (ItemStack s : t.fromInventory) {
            Map<Integer, ItemStack> over = p.getInventory().addItem(s);
            for (ItemStack o : over.values()) Sky.loot().dropFor(p, p.getLocation(), o);
        }
        Features.Sacks sacks = Sky.service(Features.Sacks.class);
        t.fromSacks.forEach((id, n) -> {
            int back = 0;
            try { back = sacks.add(p, id, n); } catch (RuntimeException ignored) {}
            if (back < n) {
                try { Sky.loot().dropFor(p, p.getLocation(), Sky.items().create(id, n - back)); } catch (RuntimeException ignored) {}
            }
        });
    }

    /** How many of {@code id} still fit in the storage slots (stack merging included). */
    static int capacity(Player p, String id) {
        int max;
        try { max = Sky.items().def(id) == null ? 64 : Sky.items().def(id).effectiveMaxStack(); } catch (RuntimeException e) { max = 64; }
        int cap = 0;
        for (ItemStack s : p.getInventory().getStorageContents()) {
            if (s == null || s.getType().isAir()) { cap += max; continue; }
            if (max > 1 && id.equals(Sky.items().idOf(s))) cap += Math.max(0, Math.min(max, s.getMaxStackSize()) - s.getAmount());
        }
        return cap;
    }
}
