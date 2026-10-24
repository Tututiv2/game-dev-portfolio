package com.skyclone.mod.enchanting;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "Enchant Item" (research 08 §3.2): drop an item (or a plain Book) in slot 19; the right side lists
 * every table enchant for it; click one to pick a level (I .. table max), paid in XP levels, gated by
 * bookshelf power and the Enchanting skill. Re-enchanting replaces the level; a conflicting enchant is
 * removed (the level button says which). Clicking the level the item already has removes it.
 */
final class TableMenu {

    private TableMenu() {}

    static final int ITEM_SLOT = 19;
    private static final int[] LIST = {12, 13, 14, 15, 16, 21, 22, 23, 24, 25, 30, 31, 32, 33, 34};

    private static final class State {
        final int power;
        EnchantSpec selected;
        int page;
        State(int power) { this.power = power; }
    }

    static Menu build(Player p, int power) {
        Menu m = new Menu("Enchant Item", 6);
        State st = new State(power);
        m.free(ITEM_SLOT);
        m.onFreeSlotChange(c -> { st.selected = null; st.page = 0; m.redraw(); });
        m.drawer(menu -> draw(menu, p, st));
        return m;
    }

    static void open(Player p, int power) { build(p, power).open(p); }

    private static void draw(Menu m, Player p, State st) {
        m.chrome();
        ItemStack it = m.get(ITEM_SLOT);
        ItemDef def = itemDef(it);
        boolean plainBook = it != null && it.getType() == Material.BOOK && (def == null || EnchantRegistry.isPlainBook(def.id));
        int ench = p == null ? 0 : EnchantingModule.enchantingLevel(p);

        m.set(28, Icons.of(Material.ENCHANTING_TABLE, "&aEnchant Item",
                "&7Place an item in the open slot", "&7to enchant it!", "", "&7A plain &fBook&7 becomes an", "&7&aEnchanted Book&7."));
        m.set(48, Icons.of(Material.BOOKSHELF, "&dBookshelf Power",
                "&7Stronger enchantments need more", "&7bookshelves around the table.", "",
                "&7Current power: &d" + st.power + "&7/&d15", "&7Each level above I needs &d" + EnchantingModule.dials().powerPerLevel.get() + "&7 more."));
        m.set(50, Icons.of(Material.BOOK, "&aEnchantments Guide",
                "&7Browse every enchantment, what", "&7it does and where it comes from.", "",
                "&8Your item is returned to you.", "", "&eClick to view!"),
                c -> GuideMenu.open(c.player(), null, 0));
        m.set(53, Icons.of(Material.PURPUR_BLOCK, "&aExperimentation Table", "&7Daily enchanting experiments.", "",
                "&8Your item is returned to you.", "", "&eClick to open!"),
                c -> Sky.commands().run(c.player(), "experiments"));
        m.set(46, Icons.of(Material.ANVIL, "&aAnvil", "&7Combine books with items.", "",
                "&8Your item is returned to you.", "", "&eClick to open!"),
                c -> AnvilMenu.open(c.player()));
        if (p != null) m.set(45, Icons.of(Material.EXPERIENCE_BOTTLE, "&aYour Experience",
                "&7Levels: &a" + p.getLevel(), "&7Enchanting skill: &a" + ench, "",
                "&7Kill mobs, mine ores, fish or smash", "&7Experience Bottles for more levels."));

        if (it == null || it.getType().isAir()) {
            m.set(23, Icons.of(Material.GRAY_DYE, "&cEnchant Item", "&7Place an item in the open slot", "&7to enchant it!"));
            return;
        }
        if (def == null && !plainBook) {
            m.set(23, Icons.of(Material.BARRIER, "&cCannot Enchant", "&7This item cannot be enchanted."));
            return;
        }
        List<EnchantSpec> list = EnchantingModule.reg().tableFor(plainBook ? null : def);
        if (list.isEmpty()) {
            m.set(23, Icons.of(Material.BARRIER, "&cCannot Enchant", "&7No enchantment on the table", "&7fits this item."));
            return;
        }
        if (it.getAmount() > 1 && !plainBook) {
            m.set(23, Icons.of(Material.BARRIER, "&cOne at a time", "&7Enchant a single item."));
            return;
        }
        Map<String, Integer> have = plainBook ? Map.of() : Gear.enchants(it);
        if (st.selected == null) drawList(m, p, st, list, have, ench);
        else drawLevels(m, p, st, it, def, plainBook, have, ench);
    }

    private static ItemDef itemDef(ItemStack it) {
        if (it == null || it.getType().isAir()) return null;
        String id = Sky.items().idOf(it);
        if (id == null && it.getType() == Material.BOOK) return Sky.items().def("BOOK");
        return id == null ? null : Sky.items().def(id);
    }

    private static void drawList(Menu m, Player p, State st, List<EnchantSpec> list, Map<String, Integer> have, int ench) {
        int pages = (list.size() + LIST.length - 1) / LIST.length;
        st.page = Math.max(0, Math.min(st.page, pages - 1));
        int start = st.page * LIST.length;
        boolean enforce = EnchantingModule.dials().enforceSkill.get();
        for (int i = 0; i < LIST.length && start + i < list.size(); i++) {
            EnchantSpec s = list.get(start + i);
            boolean locked = enforce && s.skill > ench;
            List<String> lore = new ArrayList<>(Text.wrap("&7" + s.describe(1), 36));
            lore.add("");
            lore.add("&7Table levels: &aI&7-&a" + EnchantSpec.roman(s.tableMax));
            Integer cur = have.get(s.id);
            if (cur != null) lore.add("&7On your item: &a" + EnchantSpec.roman(cur));
            lore.add("");
            if (locked) lore.add("&cRequires Enchanting " + s.skill + "!");
            else lore.add("&eClick to view!");
            ItemStack icon = Icons.of(locked ? Material.BOOK : Material.ENCHANTED_BOOK, (locked ? "&c" : "&a") + s.name, lore);
            m.set(LIST[i], icon, locked ? c -> Msg.error(c.player(), "Requires Enchanting " + s.skill + "!")
                    : c -> { st.selected = s; m.redraw(); });
        }
        if (st.page > 0) m.set(39, Icons.prevPage(st.page + 1), c -> { st.page--; m.redraw(); });
        if (st.page < pages - 1) m.set(41, Icons.nextPage(st.page + 1), c -> { st.page++; m.redraw(); });
    }

    private static void drawLevels(Menu m, Player p, State st, ItemStack it, ItemDef def, boolean plainBook,
                                   Map<String, Integer> have, int ench) {
        EnchantSpec s = st.selected;
        int n = s.tableMax;
        int start = 23 - n / 2;
        int cur = have.getOrDefault(s.id, 0);
        List<String> conflicts = new ArrayList<>();
        for (String h : have.keySet()) {
            EnchantSpec hs = EnchantingModule.reg().spec(h);
            if (s.conflictsWith(h) || (hs != null && hs.conflictsWith(s.id))) conflicts.add(hs == null ? h : hs.name);
        }
        int perLevel = EnchantingModule.dials().powerPerLevel.get();
        for (int l = 1; l <= n; l++) {
            int cost = s.cost(l);
            int needPower = Math.min(15, perLevel * (l - 1));
            List<String> lore = new ArrayList<>(Text.wrap("&7" + s.describe(l), 36));
            lore.add("");
            lore.add("&7Cost");
            lore.add("&3" + cost + " Exp Levels");
            if (needPower > 0) lore.add("&7Bookshelf Power: &d" + needPower);
            if (!conflicts.isEmpty() && cur == 0) {
                lore.add("");
                lore.add("&cThis will remove:");
                for (String c : conflicts) lore.add("&c- " + c);
            }
            lore.add("");
            String verdict;
            final int level = l;
            if (cur == l) verdict = "&eClick to remove!";
            else if (cur > l) verdict = "&cYour item has a higher level!";
            else if (st.power < needPower) verdict = "&cNot enough bookshelf power!";
            else if (p != null && p.getLevel() < cost && p.getGameMode() != org.bukkit.GameMode.CREATIVE) verdict = "&cNot enough experience!";
            else verdict = "&eClick to enchant!";
            lore.add(verdict);
            ItemStack icon = Icons.of(Material.ENCHANTED_BOOK, s.displayName(l), lore);
            if (l <= cur) icon = Icons.glow(icon);
            m.set(start + l - 1, icon, c -> apply(c.player(), m, s, level, st));
        }
        m.set(37, Icons.back("Enchantment list"), c -> { st.selected = null; m.redraw(); });
        m.set(4, Icons.of(Material.ENCHANTED_BOOK, s.displayName(Math.max(1, cur)).replace("&9", "&a"),
                "&7Pick a level below.", cur > 0 ? "&7On your item: &a" + EnchantSpec.roman(cur) : "&7Not on your item yet."));
    }

    private static void apply(Player p, Menu m, EnchantSpec s, int level, State st) {
        ItemStack it = m.get(ITEM_SLOT);
        ItemDef def = itemDef(it);
        if (it == null || it.getType().isAir() || def == null) { m.redraw(); return; }
        boolean plainBook = it.getType() == Material.BOOK && EnchantRegistry.isPlainBook(def.id);
        if (!plainBook && (!s.appliesTo(def) || it.getAmount() != 1)) { Msg.error(p, "That enchantment does not fit this item."); m.redraw(); return; }
        if (level < 1 || level > s.tableMax) return;
        EDials d = EnchantingModule.dials();
        if (d.enforceSkill.get() && s.skill > EnchantingModule.enchantingLevel(p)) { Msg.error(p, "Requires Enchanting " + s.skill + "!"); return; }
        int needPower = Math.min(15, d.powerPerLevel.get() * (level - 1));
        if (st.power < needPower) { Msg.error(p, "You need " + needPower + " bookshelf power for that level!"); return; }
        int cur = plainBook ? 0 : Gear.level(it, s.id);
        if (!plainBook && cur == level) {
            Sky.items().update(it, data -> data.enchants.remove(s.id));
            m.getInventory().setItem(ITEM_SLOT, it);
            Msg.success(p, "Removed " + s.name + " " + EnchantSpec.roman(level) + " from your item.");
            st.selected = null;
            m.redraw();
            return;
        }
        if (cur > level) { Msg.error(p, "Your item already has a higher level!"); return; }
        int cost = s.cost(level);
        if (!EnchantingModule.takeLevels(p, cost)) { Msg.error(p, "You need " + cost + " experience levels!"); return; }

        if (plainBook) {
            ItemStack book = Sky.items().create(EnchantRegistry.bookId(s.id, level), 1);
            if (it.getAmount() == 1) m.getInventory().setItem(ITEM_SLOT, book);
            else {
                it.setAmount(it.getAmount() - 1);
                m.getInventory().setItem(ITEM_SLOT, it);
                Sky.loot().give(p, book, LootSource.CRAFT);
            }
        } else {
            List<String> removed = new ArrayList<>();
            Sky.items().update(it, data -> {
                for (String h : new ArrayList<>(data.enchants.keySet())) {
                    EnchantSpec hs = EnchantingModule.reg().spec(h);
                    if (s.conflictsWith(h) || (hs != null && hs.conflictsWith(s.id))) { data.enchants.remove(h); removed.add(hs == null ? h : hs.name); }
                }
                data.enchants.put(s.id, level);
            });
            m.getInventory().setItem(ITEM_SLOT, it);
            if (!removed.isEmpty()) Msg.send(p, "&7Removed &c" + String.join("&7, &c", removed) + "&7.");
        }
        EnchantingModule.addEnchantingXp(p, cost * d.tableXpPerLevel.get(), "enchanting_table");
        PlayerData pd = EnchantingModule.data(p);
        pd.tableEnchants++;
        Msg.sound(p, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f);
        Msg.send(p, "&aYou enchanted your item with " + s.displayName(level) + "&a!");
        Sky.stats().invalidate(p);
        st.selected = null;
        m.redraw();
    }
}
