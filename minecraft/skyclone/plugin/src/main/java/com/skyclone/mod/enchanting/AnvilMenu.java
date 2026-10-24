package com.skyclone.mod.enchanting;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Anvil" (research 08 §3.3): left slot 29 = the item to upgrade, right slot 33 = the sacrifice
 * (an enchanted book, a second identical book, a Hot or Fuming Potato Book). Slot 13 previews the
 * result, slot 22 combines. Indicator panes go green when the pair is valid. Rules: {@link AnvilRules}.
 */
final class AnvilMenu {

    private AnvilMenu() {}

    static final int LEFT = 29;
    static final int RIGHT = 33;
    private static final int[] LEFT_LIGHTS = {11, 12, 20};
    private static final int[] RIGHT_LIGHTS = {14, 15, 24};

    /** A computed combination: what it does and how to perform it. */
    record Plan(AnvilRules.Result result, Kind kind, String error) {
        boolean ok() { return result != null && result.ok; }
    }

    enum Kind { NONE, ITEM_BOOK, BOOK_BOOK, POTATO, ENDCAP }

    static Menu build(Player p) {
        Menu m = new Menu("Anvil", 6);
        m.free(LEFT, RIGHT);
        m.onFreeSlotChange(c -> m.redraw());
        m.drawer(menu -> draw(menu, p));
        return m;
    }

    static void open(Player p) { build(p).open(p); }

    private static void draw(Menu m, Player p) {
        m.chrome();
        ItemStack left = m.get(LEFT), right = m.get(RIGHT);
        Plan plan = plan(p, left, right);
        Material light = plan.ok() ? Material.LIME_STAINED_GLASS_PANE : (empty(left) && empty(right) ? Material.WHITE_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE);
        for (int s : LEFT_LIGHTS) m.set(s, Icons.glass(light, "&aItem to Upgrade", "&7The item you want to upgrade", "&7goes in the slot below."));
        for (int s : RIGHT_LIGHTS) m.set(s, Icons.glass(light, "&aItem to Sacrifice", "&7The book or upgrade you want", "&7to use goes in the slot below."));

        if (plan.ok()) {
            ItemStack preview = preview(plan, left);
            m.set(13, preview == null ? Icons.of(Material.BARRIER, "&c?") : preview);
            int cost = cost(plan);
            List<String> lore = new ArrayList<>();
            lore.add("&7Combine the items in the slots");
            lore.add("&7to the left and right below.");
            if (!plan.result().removed.isEmpty()) {
                lore.add("");
                lore.add("&cThis removes: &7" + String.join(", ", names(plan.result().removed)));
            }
            lore.add("");
            lore.add("&7Cost");
            lore.add(cost > 0 ? "&3" + cost + " Exp Levels" : "&aFree");
            lore.add("");
            lore.add(p != null && cost > p.getLevel() && p.getGameMode() != org.bukkit.GameMode.CREATIVE ? "&cNot enough experience!" : "&eClick to combine!");
            m.set(22, Icons.of(Material.ANVIL, "&aCombine Items", lore), c -> combine(c.player(), m));
        } else {
            m.set(13, Icons.of(Material.BARRIER, "&cAnvil", plan.error() == null || plan.error().isEmpty()
                    ? new String[]{"&7Place a target item in the left slot", "&7and a sacrifice item in the right", "&7slot to combine them!"}
                    : new String[]{"&c" + plan.error()}));
            m.set(22, Icons.of(Material.ANVIL, "&cCombine Items", "&7Combine the items in the slots", "&7to the left and right below."));
        }
    }

    private static boolean empty(ItemStack s) { return s == null || s.getType().isAir(); }

    private static List<String> names(List<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            EnchantSpec s = EnchantingModule.reg().spec(id);
            out.add(s == null ? id : s.name);
        }
        return out;
    }

    static int cost(Plan plan) {
        return (int) Math.round(plan.result().cost * EnchantingModule.dials().anvilCostMultiplier.get());
    }

    /** Enchant map a book stack carries (canonical/alias id or the generic data-carrying book). */
    static Map<String, Integer> bookEnchants(ItemStack s) {
        String id = Sky.items().idOf(s);
        if (id == null) return Map.of();
        EnchantRegistry.BookRef b = EnchantingModule.reg().parseBook(id);
        if (b != null) return Map.of(b.enchant(), b.level());
        if (EnchantRegistry.GENERIC_BOOK.equals(id)) return Gear.enchants(s);
        return Map.of();
    }

    static Plan plan(Player p, ItemStack left, ItemStack right) {
        if (empty(left) || empty(right)) return new Plan(null, Kind.NONE, "");
        String lid = Sky.items().idOf(left), rid = Sky.items().idOf(right);
        if (lid == null || rid == null) return new Plan(null, Kind.NONE, "These items cannot be combined.");
        ItemDef ldef = Sky.items().def(lid);
        EnchantRegistry reg = EnchantingModule.reg();
        EDials d = EnchantingModule.dials();
        int ench = p == null ? 0 : EnchantingModule.enchantingLevel(p);

        if ("HOT_POTATO_BOOK".equals(rid) || "FUMING_POTATO_BOOK".equals(rid)) {
            if (left.getAmount() != 1) return new Plan(null, Kind.NONE, "Upgrade one item at a time.");
            ItemData ld = Sky.items().data(left);
            AnvilRules.Result r = AnvilRules.potato(ldef, ld.potatoBooks, "FUMING_POTATO_BOOK".equals(rid), 10, 15);
            return new Plan(r, Kind.POTATO, r.ok ? null : r.reason);
        }
        AnvilRules.Endcap up = EnchantingModule.endcaps().get(rid);
        if (up != null) {
            // Endcap Upgrade (End Stone Idol, Golden Bounty, ...): one enchant one level past its top, once per item
            if (left.getAmount() != 1) return new Plan(null, Kind.NONE, "Upgrade one item at a time.");
            ItemData ld = Sky.items().data(left);
            boolean applied = ld.extra != null && ld.extra.has(AnvilRules.endcapFlag(up.enchant));
            AnvilRules.Result r = AnvilRules.endcap(reg, ldef, Gear.enchants(left), up, applied);
            return new Plan(r, Kind.ENDCAP, r.ok ? null : r.reason);
        }
        Map<String, Integer> book = bookEnchants(right);
        if (book.isEmpty()) return new Plan(null, Kind.NONE, "The right item is not something an anvil can use.");

        Map<String, Integer> leftBook = bookEnchants(left);
        if (!leftBook.isEmpty() && reg.parseBook(lid) != null) {
            // book + book
            if (leftBook.size() != 1 || book.size() != 1) return new Plan(null, Kind.NONE, "Those books cannot be combined.");
            Map.Entry<String, Integer> a = leftBook.entrySet().iterator().next(), b = book.entrySet().iterator().next();
            AnvilRules.Result r = AnvilRules.combineBooks(reg, a.getKey(), a.getValue(), b.getKey(), b.getValue(), d.anvilOverTable.get());
            return new Plan(r, Kind.BOOK_BOOK, r.ok ? null : r.reason);
        }
        if (left.getAmount() != 1) return new Plan(null, Kind.NONE, "Upgrade one item at a time.");
        Map<String, Integer> current = EnchantRegistry.GENERIC_BOOK.equals(lid) ? leftBook : Gear.enchants(left);
        AnvilRules.Result r = AnvilRules.applyBook(reg, ldef, current, book, ench, d.enforceSkill.get(), d.anvilOverTable.get());
        return new Plan(r, Kind.ITEM_BOOK, r.ok ? null : r.reason);
    }

    private static ItemStack preview(Plan plan, ItemStack left) {
        AnvilRules.Result r = plan.result();
        switch (plan.kind()) {
            case BOOK_BOOK -> { return Sky.items().create(EnchantRegistry.bookId(r.bookEnchant, r.bookLevel), 1); }
            case ITEM_BOOK, ENDCAP -> {
                ItemStack copy = left.clone();   // display only: lives in a locked slot, never given out
                LinkedHashMap<String, Integer> ench = r.enchants;
                Sky.items().update(copy, d -> { d.enchants.clear(); d.enchants.putAll(ench); });
                return copy;
            }
            case POTATO -> {
                ItemStack copy = left.clone();
                int n = r.potatoBooks;
                Sky.items().update(copy, d -> d.potatoBooks = n);
                return copy;
            }
            default -> { return null; }
        }
    }

    private static void combine(Player p, Menu m) {
        ItemStack left = m.get(LEFT), right = m.get(RIGHT);
        Plan plan = plan(p, left, right);
        if (!plan.ok()) { Msg.error(p, plan.error() == null ? "Those items cannot be combined." : plan.error()); m.redraw(); return; }
        int cost = cost(plan);
        if (!EnchantingModule.takeLevels(p, cost)) { Msg.error(p, "You need " + cost + " experience levels!"); return; }
        AnvilRules.Result r = plan.result();
        switch (plan.kind()) {
            case BOOK_BOOK -> {
                take(m, LEFT, left);
                take(m, RIGHT, right);
                ItemStack out = Sky.items().create(EnchantRegistry.bookId(r.bookEnchant, r.bookLevel), 1);
                if (empty(m.get(LEFT))) m.getInventory().setItem(LEFT, out);
                else Sky.loot().give(p, out, LootSource.CRAFT);
            }
            case ITEM_BOOK -> {
                LinkedHashMap<String, Integer> ench = r.enchants;
                Sky.items().update(left, d -> { d.enchants.clear(); d.enchants.putAll(ench); });
                m.getInventory().setItem(LEFT, left);
                take(m, RIGHT, right);
            }
            case POTATO -> {
                int n = r.potatoBooks;
                Sky.items().update(left, d -> d.potatoBooks = n);
                m.getInventory().setItem(LEFT, left);
                take(m, RIGHT, right);
            }
            case ENDCAP -> {
                LinkedHashMap<String, Integer> ench = r.enchants;
                AnvilRules.Endcap up = EnchantingModule.endcaps().get(Sky.items().idOf(right));
                String flag = AnvilRules.endcapFlag(up.enchant);
                Sky.items().update(left, d -> { d.enchants.clear(); d.enchants.putAll(ench); d.extra.addProperty(flag, true); });
                m.getInventory().setItem(LEFT, left);
                take(m, RIGHT, right);
            }
            default -> { return; }
        }
        EnchantingModule.addEnchantingXp(p, cost * EnchantingModule.dials().anvilXpPerLevel.get(), "anvil");
        EnchantingModule.data(p).anvilCombines++;
        Msg.sound(p, Sound.BLOCK_ANVIL_USE, 1.0f);
        Sky.stats().invalidate(p);
        m.redraw();
    }

    /** Use up one item from a free slot. */
    private static void take(Menu m, int slot, ItemStack s) {
        if (s.getAmount() > 1) { s.setAmount(s.getAmount() - 1); m.getInventory().setItem(slot, s); }
        else m.getInventory().setItem(slot, null);
    }
}
