package com.skyclone.mod.bazaar;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Inventory helpers: what a player holds, what fits, and handing items over through the loot service. */
final class Inv {

    private Inv() {}

    static int maxStack(String id) {
        ItemDef d = Sky.items().def(id);
        return d == null ? 64 : Math.max(1, d.effectiveMaxStack());
    }

    static int inInventory(Player p, String id) { return Sky.items().count(p, id); }

    static int inSacks(Player p, String id) {
        if (!Sky.has(Features.Sacks.class)) return 0;
        try { return Math.max(0, Sky.service(Features.Sacks.class).count(p, id)); } catch (RuntimeException e) { return 0; }
    }

    static boolean hasSacks() { return Sky.has(Features.Sacks.class); }

    /** How many of this item fit in the main inventory (36 storage slots), counting partial stacks. */
    static int capacity(Player p, String id) {
        int max = maxStack(id);
        PlayerInventory inv = p.getInventory();
        ItemStack[] st = inv.getStorageContents();
        long room = 0;
        for (ItemStack s : st) {
            if (s == null || s.getType().isAir()) { room += max; continue; }
            if (id.equals(Sky.items().idOf(s)) && Sky.items().data(s).uuid == null && s.getAmount() < max) room += max - s.getAmount();
        }
        return (int) Math.min(Integer.MAX_VALUE, room);
    }

    /**
     * Give items that came out of the market, in stack-sized chunks through {@code Sky.loot()} (a sack may absorb
     * them; collections never count BAZAAR). Callers size {@code n} to what fits, so nothing hits the floor.
     */
    static void give(Player p, String id, int n) {
        int max = maxStack(id);
        while (n > 0) {
            int c = Math.min(max, n);
            Sky.loot().give(p, id, c, LootSource.BAZAAR);
            n -= c;
        }
    }

    /** Store directly into sacks if the sacks module accepts it; returns how many it took. */
    static int toSacks(Player p, String id, int n) {
        if (n <= 0 || !Sky.has(Features.Sacks.class)) return 0;
        try { return Math.max(0, Math.min(n, Sky.service(Features.Sacks.class).add(p, id, n))); } catch (RuntimeException e) { return 0; }
    }

    /** Remove up to n: inventory first, then sacks when allowed. Returns how many were taken. */
    static int take(Player p, String id, int n, boolean sacks) {
        int got = Sky.items().remove(p, id, n);
        if (got < n && sacks && Sky.has(Features.Sacks.class)) {
            try { got += Math.max(0, Sky.service(Features.Sacks.class).remove(p, id, n - got)); } catch (RuntimeException ignored) { }
        }
        return got;
    }

    /**
     * Make the removal durable NOW: items left the inventory and are about to become an order row. Without this a
     * crash before the next player-data save would give them back while the order also exists (a dupe).
     */
    static void persist(Player p, boolean sacksTouched) {
        try { p.saveData(); } catch (RuntimeException e) { Sky.log().warning("[bazaar] saveData failed for " + p.getName() + ": " + e); }
        if (sacksTouched) Sky.profiles().saveNow(p);
    }
}
