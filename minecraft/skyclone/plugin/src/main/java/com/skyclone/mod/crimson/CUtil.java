package com.skyclone.mod.crimson;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/** Small shared helpers: item names, counting/taking items across inventory + sacks, days. */
final class CUtil {

    private CUtil() {}

    static String name(String itemId) {
        ItemDef d = Sky.items().def(itemId);
        if (d == null) return "&f" + Text.titleCase(itemId);
        return d.rarity.code() + d.name;
    }

    static ItemStack icon(String itemId, Material fallback) {
        try {
            if (Sky.items().exists(itemId)) return Sky.items().create(itemId, 1);
        } catch (RuntimeException ignored) { }
        return new ItemStack(fallback);
    }

    /**
     * The essence pouch type of an item id ("ESSENCE_CRIMSON" -> "CRIMSON"), or null. Essence picked up goes
     * straight into the reforges module's pouch (Features.Essence), so a cost in Crimson Essence that only
     * looked at the inventory could never be paid from what the player actually owns.
     */
    static String essenceType(String itemId) {
        return itemId != null && itemId.startsWith("ESSENCE_") && itemId.length() > 8 ? itemId.substring(8) : null;
    }

    static long pouch(Player p, String itemId) {
        String type = essenceType(itemId);
        if (type == null) return 0;
        try { return Math.max(0, Sky.service(Features.Essence.class).essence(p, type)); } catch (RuntimeException e) { return 0; }
    }

    /** Inventory + sacks (+ the essence pouch for ESSENCE_* ids). */
    static int have(Player p, String itemId) {
        long n = Sky.items().count(p, itemId);
        try { n += Math.max(0, Sky.service(Features.Sacks.class).count(p, itemId)); } catch (RuntimeException ignored) { }
        n += pouch(p, itemId);
        return (int) Math.min(Integer.MAX_VALUE, n);
    }

    /** Take from the essence pouch (ESSENCE_* ids) first, then the inventory, then sacks. Returns how many were taken. */
    static int take(Player p, String itemId, int amount) {
        int got = 0;
        String type = essenceType(itemId);
        if (type != null && amount > 0) {
            long inPouch = pouch(p, itemId);
            int fromPouch = (int) Math.min(amount, inPouch);
            try {
                if (fromPouch > 0 && Sky.service(Features.Essence.class).takeEssence(p, type, fromPouch)) got += fromPouch;
            } catch (RuntimeException ignored) { }
        }
        if (got < amount) got += Sky.items().remove(p, itemId, amount - got);
        if (got < amount) {
            try { got += Math.max(0, Sky.service(Features.Sacks.class).remove(p, itemId, amount - got)); } catch (RuntimeException ignored) { }
        }
        return got;
    }

    /** Give back what {@link #take} took: essence to the pouch when there is one, everything else as items. */
    static void refund(Player p, String itemId, int amount) {
        if (amount <= 0) return;
        String type = essenceType(itemId);
        if (type != null) {
            try {
                Features.Essence pouch = Sky.service(Features.Essence.class);
                long before = pouch.essence(p, type);
                pouch.addEssence(p, type, amount);
                if (pouch.essence(p, type) >= before + amount) return;
            } catch (RuntimeException ignored) { }
        }
        Sky.loot().give(p, itemId, amount, com.skyclone.api.loot.LootSource.REWARD);
    }

    /** Lines describing a cost list ("&8- &a64x &fBlaze Rod"), green when owned, red otherwise. */
    static List<String> costLines(Player p, double coins, List<String> cells) {
        List<String> out = new ArrayList<>();
        if (coins > 0) out.add((p == null || Sky.economy().purse(p) >= coins ? "&6" : "&c") + Text.commasInt(coins) + " Coins");
        for (String c : cells) {
            Object[] cell = CrimsonRules.cell(c);
            String id = (String) cell[0];
            int n = (int) cell[1];
            boolean ok = p != null && have(p, id) >= n;
            out.add(name(id) + " &8x" + (ok ? "&a" : "&c") + Text.commasInt(n));
        }
        return out;
    }

    static String today(int resetHourUtc) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC).minusHours(Math.max(0, Math.min(23, resetHourUtc)));
        LocalDate d = now.toLocalDate();
        return d.toString();
    }

    /** Seconds until the next daily reset. */
    static long secondsToReset(int resetHourUtc) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime next = now.withHour(Math.max(0, Math.min(23, resetHourUtc))).withMinute(0).withSecond(0).withNano(0);
        if (!next.isAfter(now)) next = next.plusDays(1);
        return java.time.Duration.between(now, next).getSeconds();
    }

    static String bar(double frac) { return Text.dashBar(frac, 20); }
}
