package com.skyclone.mod.mayors;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Item ids from other modules, shop costs, and safe giving. Every id this module did not register
 * is resolved at USE time and may be missing (its owner absent or not built yet), so everything
 * here degrades: an unknown reward is skipped, an unknown shop line is hidden.
 */
final class Goods {

    private Goods() {}

    /**
     * The registered id for a reference-style id, or null. Tries the id itself, then the genre's
     * "NAME;level" forms: an enchanted book ({@code SHARPNESS;6} → {@code ENCHANTMENT_SHARPNESS_6}),
     * then the bare name (potions, runes).
     */
    static String resolve(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            if (Sky.items().exists(raw)) return raw;
            int semi = raw.indexOf(';');
            if (semi > 0) {
                String base = raw.substring(0, semi), lvl = raw.substring(semi + 1);
                String book = "ENCHANTMENT_" + base.toUpperCase(Locale.ROOT) + "_" + lvl;
                if (Sky.items().exists(book)) return book;
                if (Sky.items().exists(base)) return base;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** First id of a list that resolves. */
    static String first(List<String> ids) {
        for (String s : ids) { String r = resolve(s); if (r != null) return r; }
        return null;
    }

    record Cell(String id, int amount) {}

    /** "ID:amount" with the core rule: a cell that IS a registered id means x1. */
    static Cell cell(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String id = raw;
        int amount = 1;
        if (resolve(raw) == null) {
            int c = raw.lastIndexOf(':');
            if (c > 0) {
                try { amount = Integer.parseInt(raw.substring(c + 1)); id = raw.substring(0, c); } catch (NumberFormatException ignored) { }
            }
        }
        String r = resolve(id);
        return r == null ? null : new Cell(r, Math.max(1, amount));
    }

    /** True when every id in the offer (result and costs) exists. */
    static boolean available(EventData.Offer o) {
        if (resolve(o.result) == null) return false;
        for (String c : o.cost) if (cell(c) == null) return false;
        return true;
    }

    static String displayName(String id) {
        ItemDef d = id == null ? null : Sky.items().def(id);
        if (d == null) return "&f" + (id == null ? "?" : Text.titleCase(id));
        return d.rarity.code() + d.name;
    }

    /** A real rendered stack of an id for a menu icon (falls back to a barrier). */
    static ItemStack icon(String id, int amount) {
        try {
            String r = resolve(id);
            if (r != null) {
                ItemStack s = Sky.items().create(r, Math.max(1, Math.min(64, amount)));
                if (amount > 64) s.setAmount(64);
                return s;
            }
        } catch (Throwable ignored) {
        }
        return Icons.of(Material.BARRIER, "&c" + id, "&7Not available yet.");
    }

    static List<String> costLines(EventData.Offer o, double coinScale) {
        List<String> out = new ArrayList<>();
        out.add("&7Cost");
        if (o.coins > 0) out.add("&6" + Text.commasInt(o.coins * coinScale) + " Coins");
        for (String c : o.cost) {
            Cell cell = cell(c);
            if (cell != null) out.add(displayName(cell.id()) + " &8x" + cell.amount());
        }
        return out;
    }

    static boolean canAfford(Player p, EventData.Offer o, double coinScale) {
        if (o.coins > 0 && Sky.economy().purse(p) < o.coins * coinScale) return false;
        for (String c : o.cost) {
            Cell cell = cell(c);
            if (cell == null || Sky.items().count(p, cell.id()) < cell.amount()) return false;
        }
        return true;
    }

    /**
     * Take the cost of an offer: coins first (atomic in core), then items. If an item vanished between
     * the check and the take, everything already taken is given back. Returns false with nothing taken.
     */
    static boolean pay(Player p, EventData.Offer o, double coinScale, String reason) {
        if (!canAfford(p, o, coinScale)) return false;
        double coins = o.coins * coinScale;
        if (coins > 0 && !Sky.economy().withdraw(p, coins, reason)) return false;
        List<Cell> taken = new ArrayList<>();
        for (String c : o.cost) {
            Cell cell = cell(c);
            int got = Sky.items().remove(p, cell.id(), cell.amount());
            if (got < cell.amount()) {
                if (got > 0) Sky.loot().give(p, cell.id(), got, LootSource.OTHER);
                for (Cell t : taken) Sky.loot().give(p, t.id(), t.amount(), LootSource.OTHER);
                if (coins > 0) Sky.economy().deposit(p, coins, "refund: " + reason);
                return false;
            }
            taken.add(cell);
        }
        return true;
    }

    /** Give an id if it exists; returns the id given, or null. */
    static String give(Player p, String raw, int amount, LootSource source) {
        String id = resolve(raw);
        if (id == null || amount <= 0) return null;
        ItemDef d = Sky.items().def(id);
        if (d != null && d.isUnique()) {
            for (int i = 0; i < amount; i++) Sky.loot().give(p, id, 1, source);
        } else {
            Sky.loot().give(p, id, amount, source);
        }
        return id;
    }

    static int between(int[] range) {
        if (range == null || range.length == 0) return 0;
        int lo = range[0], hi = range.length > 1 ? range[1] : range[0];
        if (hi <= lo) return lo;
        return lo + ThreadLocalRandom.current().nextInt(hi - lo + 1);
    }

    /** Roll a drop table; returns the ids that dropped (first existing alternative of each line). */
    static List<String> roll(List<EventData.Drop> drops, double luckMultiplier) {
        List<String> out = new ArrayList<>();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (EventData.Drop d : drops) {
            if (r.nextDouble() < d.chance * luckMultiplier) {
                // alternatives are equally likely variants of one line (e.g. which XP potion)
                List<String> alts = new ArrayList<>(d.items);
                java.util.Collections.shuffle(alts);
                String id = first(alts);
                if (id != null) out.add(id);
            }
        }
        return out;
    }
}
