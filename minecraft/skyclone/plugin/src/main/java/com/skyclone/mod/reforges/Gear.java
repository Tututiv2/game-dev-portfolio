package com.skyclone.mod.reforges;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.ui.Text;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bukkit-side helpers shared by the menus, the bonus listeners and the admin commands. */
final class Gear {

    private Gear() {}

    static boolean empty(ItemStack s) { return s == null || s.getType().isAir() || s.getAmount() <= 0; }

    static ItemDef def(ItemStack s) {
        if (empty(s)) return null;
        String id = Sky.items().idOf(s);
        return id == null ? null : Sky.items().def(id);
    }

    static String name(ItemStack s) {
        if (empty(s)) return "&7nothing";
        var meta = s.getItemMeta();
        if (meta != null && meta.displayName() != null) return Text.toLegacy(meta.displayName());
        ItemDef d = def(s);
        return d != null ? d.rarity.code() + d.name : Text.titleCase(s.getType().name());
    }

    /** Coins as "&65,000 Coins". */
    static String coins(double c) { return "&6" + Text.commasInt(c) + " Coins"; }

    /** Display name of an item id, rarity-coloured. */
    static String itemName(String id) {
        ItemDef d = id == null ? null : Sky.items().def(id);
        if (d == null) return "&f" + (id == null ? "?" : Text.titleCase(id));
        return d.rarity.code() + d.name;
    }

    static String essenceName(String type) {
        return "&d" + Text.titleCase(type.toLowerCase(Locale.ROOT)) + " Essence";
    }

    // ---- essence pouch ----------------------------------------------------------------------------

    static long essence(Player p, String type) {
        if (!Sky.profiles().isReady(p)) return 0;
        return ReforgesModule.data(p).essence.getOrDefault(type.toUpperCase(Locale.ROOT), 0L);
    }

    static void addEssence(Player p, String type, long amount) {
        if (amount <= 0 || !Sky.profiles().isReady(p)) return;
        ReforgesModule.data(p).essence.merge(type.toUpperCase(Locale.ROOT), amount, Long::sum);
    }

    static boolean takeEssence(Player p, String type, long amount) {
        if (amount <= 0) return true;
        if (!Sky.profiles().isReady(p)) return false;
        Map<String, Long> m = ReforgesModule.data(p).essence;
        String k = type.toUpperCase(Locale.ROOT);
        long have = m.getOrDefault(k, 0L);
        if (have < amount) return false;
        m.put(k, have - amount);
        return true;
    }

    // ---- items: inventory + sacks --------------------------------------------------------------------

    /** How many of an item the player has in the inventory plus their sacks (as the reference counts upgrade costs). */
    static int count(Player p, String id) {
        if (id == null) return 0;
        long n = Sky.items().count(p, id);
        try { n += Math.max(0, Sky.service(Features.Sacks.class).count(p, id)); } catch (Throwable ignored) { }
        return (int) Math.min(Integer.MAX_VALUE, n);
    }

    /** How many sit in the sacks only. */
    static int sackCount(Player p, String id) {
        try { return Math.max(0, Sky.service(Features.Sacks.class).count(p, id)); } catch (Throwable t) { return 0; }
    }

    /**
     * Take {@code n} of an item, inventory first and the rest from the sacks. All or nothing: checks the
     * total first, and if the sack part comes up short (a sack changed under us) the inventory part is
     * given back.
     */
    static boolean take(Player p, String id, int n) {
        if (n <= 0) return true;
        if (count(p, id) < n) return false;
        int fromInv = Math.min(n, Sky.items().count(p, id));
        int took = fromInv > 0 ? Sky.items().remove(p, id, fromInv) : 0;
        int rest = n - took;
        if (rest <= 0) return true;
        int fromSack = 0;
        try { fromSack = Sky.service(Features.Sacks.class).remove(p, id, rest); } catch (Throwable ignored) { }
        if (fromSack >= rest) return true;
        // roll back: put back whatever was taken
        if (took > 0) Sky.loot().give(p, id, took, com.skyclone.api.loot.LootSource.OTHER);
        if (fromSack > 0) {
            int back = 0;
            try { back = Sky.service(Features.Sacks.class).add(p, id, fromSack); } catch (Throwable ignored) { }
            if (back < fromSack) Sky.loot().give(p, id, fromSack - back, com.skyclone.api.loot.LootSource.OTHER);
        }
        return false;
    }

    // ---- costs ------------------------------------------------------------------------------------

    /** Lore lines describing a cost list ("&dWither Essence &8x150"), each green/red by affordability. */
    static List<String> describe(Player p, List<ReforgeData.Cost> costs, double coinMult) {
        List<String> out = new ArrayList<>();
        for (ReforgeData.Cost c : costs) {
            if (c.coins != null) {
                double v = c.coins * coinMult;
                out.add((Sky.economy().purse(p) >= v ? "&a✔ " : "&c✖ ") + coins(v));
            } else if (c.essence != null) {
                out.add((essence(p, c.essence) >= c.amount ? "&a✔ " : "&c✖ ") + essenceName(c.essence) + " &8x" + Text.commasInt(c.amount));
            } else if (c.item != null) {
                boolean known = Sky.items().exists(c.item);
                boolean has = known && count(p, c.item) >= c.amount;
                out.add((has ? "&a✔ " : "&c✖ ") + itemName(c.item) + " &8x" + Text.commasInt(c.amount) + (known ? "" : " &c(unobtainable)"));
            }
        }
        return out;
    }

    /** Null when the player can pay all of it, else a short reason. Does not take anything. */
    static String canPay(Player p, List<ReforgeData.Cost> costs, double coinMult) {
        double coins = 0;
        for (ReforgeData.Cost c : costs) {
            if (c.coins != null) coins += c.coins * coinMult;
            else if (c.essence != null && essence(p, c.essence) < c.amount)
                return "You need " + Text.commasInt(c.amount) + " " + Text.strip(essenceName(c.essence)) + ".";
            else if (c.item != null) {
                if (!Sky.items().exists(c.item)) return "This needs an item that does not exist yet (" + c.item + ").";
                if (count(p, c.item) < c.amount)
                    return "You need " + c.amount + "x " + Text.strip(itemName(c.item)) + " (inventory or sacks).";
            }
        }
        if (coins > 0 && Sky.economy().purse(p) < coins) return "You need " + Text.commasInt(coins) + " coins in your purse.";
        return null;
    }

    /** Takes the whole cost, coins first (the only step that can fail after canPay). False = nothing taken. */
    static boolean pay(Player p, List<ReforgeData.Cost> costs, double coinMult, String reason) {
        if (canPay(p, costs, coinMult) != null) return false;
        double coins = 0;
        for (ReforgeData.Cost c : costs) if (c.coins != null) coins += c.coins * coinMult;
        if (coins > 0 && !Sky.economy().withdraw(p, coins, reason)) return false;
        for (ReforgeData.Cost c : costs) {
            if (c.essence != null) takeEssence(p, c.essence, c.amount);
            else if (c.item != null) take(p, c.item, c.amount);
        }
        return true;
    }

    // ---- what a player is wearing -------------------------------------------------------------------

    /** One reforged piece of gear the player has on. */
    record Worn(ReforgeData.Row row, Rarity rarity, ItemStack stack, boolean held) {}

    /** Reforged gear in effect: the held weapon/tool, the four armor pieces and the equipment slots. */
    static List<Worn> worn(Player p) {
        List<Worn> out = new ArrayList<>();
        ItemStack hand = p.getInventory().getItemInMainHand();
        ItemDef hd = def(hand);
        if (hd != null && hd.category != null && (hd.category.isWeapon() || hd.category.isTool())) add(out, hand, true);
        for (ItemStack a : p.getInventory().getArmorContents()) add(out, a, false);
        try {
            List<ItemStack> eq = Sky.service(Features.Equipment.class).worn(p);
            if (eq != null) for (ItemStack e : eq) add(out, e, false);
        } catch (Throwable ignored) { }
        return out;
    }

    private static void add(List<Worn> out, ItemStack s, boolean held) {
        if (empty(s) || def(s) == null) return;
        ItemData d = Sky.items().data(s);
        if (d.reforge == null) return;
        ReforgeData.Row row = ReforgesModule.DATA.rows.get(d.reforge);
        if (row == null) return;
        out.add(new Worn(row, Sky.items().rarity(s), s, held));
    }
}
