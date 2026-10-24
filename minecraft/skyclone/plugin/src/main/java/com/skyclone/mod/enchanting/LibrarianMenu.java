package com.skyclone.mod.enchanting;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * The hub Librarian's stock (research NPC data: Experience Bottles, Books, level-I books), used only
 * when no other module registered an NPC at npc.librarian. Also links to the table, anvil and guide.
 */
final class LibrarianMenu {

    private LibrarianMenu() {}

    static final class Row {
        public String item;
        public double coins;
    }

    static List<Row> load() {
        try (Reader r = EnchantingModule.openResource("librarian.json")) {
            List<Row> l = new Gson().fromJson(r, new TypeToken<List<Row>>() {}.getType());
            return l == null ? List.of() : l;
        } catch (Exception e) {
            Sky.log().warning("[enchanting] librarian.json unreadable: " + e.getMessage());
            return List.of();
        }
    }

    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};

    static void open(Player p) { build().open(p); }

    static Menu build() {
        Menu m = new Menu("Librarian", 6).chrome();
        int i = 0;
        for (Row r : EnchantingModule.librarianRows()) {
            if (i >= SLOTS.length) break;
            if (!Sky.items().exists(r.item)) continue;
            ItemStack shown = Sky.items().create(r.item, 1);
            List<String> extra = new ArrayList<>();
            extra.add("");
            extra.add("&7Cost");
            extra.add("&6" + Sky.economy().format(r.coins) + " Coins");
            extra.add("");
            extra.add("&eClick to trade!");
            extra.add("&eShift-click for 5!");
            final Row row = r;
            m.set(SLOTS[i++], Icons.withLore(shown, extra), c -> buy(c.player(), row, c.shift() ? 5 : 1));
        }
        m.set(37, Icons.of(Material.ENCHANTING_TABLE, "&aEnchant Item", "&7Open the enchanting table.", "", "&eClick to open!"),
                c -> TableMenu.open(c.player(), BlockListener.nearbyPower(c.player(), EnchantingModule.dials().commandPower.get())));
        m.set(39, Icons.of(Material.ANVIL, "&aAnvil", "&7Combine books with items.", "", "&eClick to open!"), c -> AnvilMenu.open(c.player()));
        m.set(41, Icons.of(Material.BOOK, "&aEnchantments Guide", "&7Every enchantment in one place.", "", "&eClick to view!"),
                c -> GuideMenu.open(c.player(), null, 0));
        m.set(43, Icons.of(Material.PURPUR_BLOCK, "&aExperimentation Table", "&7Daily enchanting experiments:",
                "&7Twin Tiles, Echo Keys and", "&7Number Vault.", "", "&eClick to open!"),
                c -> Sky.commands().run(c.player(), "experiments"));
        return m;
    }

    private static void buy(Player p, Row r, int n) {
        double total = r.coins * n;
        ItemDef def = Sky.items().def(r.item);
        if (def == null) return;
        if (!Sky.economy().withdraw(p, total, "npc-shop: librarian " + r.item + " x" + n)) {
            Msg.error(p, "You don't have enough coins!");
            return;
        }
        Sky.loot().give(p, r.item, n, LootSource.NPC_SHOP);
        Msg.send(p, "&aYou bought " + def.rarity.code() + def.name + (n > 1 ? " &8x" + n : "") + "&a for &6" + Sky.economy().format(total) + " coins&a!");
        Msg.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.4f);
    }
}
