package com.skyclone.mod.garden;

import com.skyclone.api.Sky;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Copper Market (SkyMart, research 08 1.13; catalogue from NEU constants/skymart.json). Paid in
 * Copper or coins. Where the reference charged its paid currency the price became Copper x10 (no
 * real money in this game). Items other modules have not registered are simply not shown.
 */
final class Market {

    private Market() {}

    static List<String> categories() {
        Set<String> c = new LinkedHashSet<>();
        c.add("All");
        for (GardenTables.ShopEntry e : G.T.shop) c.add(e.category);
        return new ArrayList<>(c);
    }

    static List<GardenTables.ShopEntry> visible(String cat) {
        List<GardenTables.ShopEntry> out = new ArrayList<>();
        for (GardenTables.ShopEntry e : G.T.shop)
            if (G.exists(e.item) && ("All".equals(cat) || cat.equals(e.category))) out.add(e);
        return out;
    }

    static void open(Player p, String cat, int page) {
        GardenData d = G.data(p);
        List<GardenTables.ShopEntry> list = visible(cat);
        Sky.menus().paged("Copper Market" + ("All".equals(cat) ? "" : " - " + cat), list, e -> icon(p, d, e),
                pc -> {
                    Player q = pc.click().player();
                    if (pc.click().right() && stackable(pc.entry())) {
                        Sky.menus().inputNumber(q, "How many? (max " + MAX_BULK + ")", n -> {
                            if (n == null) { open(q, cat, pc.page()); return; }
                            long k = (long) Math.floor(n);
                            if (k < 1 || k > MAX_BULK) { Msg.error(q, "Pick between 1 and " + MAX_BULK + "."); return; }
                            buy(q, pc.entry(), cat, pc.page(), (int) k);
                        });
                    } else buy(q, pc.entry(), cat, pc.page(), 1);
                },
                m -> {
                    m.set(4, Icons.of(Material.COPPER_INGOT, "&aCopper Market", "&7Copper comes from visitors.", "",
                            "&7You have " + G.copperText(d.copper), "&7Purse: &6" + Text.commasInt(Sky.economy().purse(p)) + " Coins"));
                    List<String> cats = categories();
                    int next = (cats.indexOf(cat) + 1) % cats.size();
                    m.set(50, Icons.of(Material.HOPPER, "&aCategory: &e" + cat, "&7Next: &a" + cats.get(next), "", "&eClick to switch!"),
                            c -> open(c.player(), cats.get(next), 0));
                    m.back("The Desk", () -> Desk.build(p));
                }, page).open(p);
    }

    static ItemStack icon(Player p, GardenData d, GardenTables.ShopEntry e) {
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("&7Cost:");
        lore.add(" " + price(e));
        for (Map.Entry<String, Integer> n : e.needs.entrySet())
            lore.add(" " + G.itemName(n.getKey()) + " &8x" + n.getValue() + (G.count(p, n.getKey()) >= n.getValue() ? " &a✔" : ""));
        lore.add("");
        if (G.level(d) < e.level) lore.add("&cRequires Garden " + GardenMath.roman(e.level));
        else {
            lore.add("&eClick to buy!");
            if (stackable(e)) lore.add("&eRight-click to buy in bulk!");
        }
        return Icons.withLore(G.icon(e.item, 1), lore);
    }

    static String price(GardenTables.ShopEntry e) { return price(e, 1); }

    static String price(GardenTables.ShopEntry e, int qty) {
        double total = e.price * qty;
        return "copper".equals(e.currency) ? G.copperText((long) total) : "&6" + Text.commasInt(total) + " Coins";
    }

    static final int MAX_BULK = 640;

    /** Bulk buying only for plain stackable items with no trade-in (compost, biofuel, sprays...). */
    static boolean stackable(GardenTables.ShopEntry e) {
        var def = Sky.items().def(e.item);
        return def != null && !def.isUnique() && e.needs.isEmpty();
    }

    static void buy(Player p, GardenTables.ShopEntry e, String cat, int page) { buy(p, e, cat, page, 1); }

    static void buy(Player p, GardenTables.ShopEntry e, String cat, int page, int qty) {
        GardenData d = G.data(p);
        if (qty < 1 || qty > 1 && !stackable(e)) return;
        if (G.level(d) < e.level) { Msg.error(p, "Requires Garden " + GardenMath.roman(e.level) + "!"); return; }
        if (!G.has(p, e.needs)) { Msg.error(p, "You are missing an item this needs!"); return; }
        boolean copper = "copper".equals(e.currency);
        double total = e.price * qty;
        if (copper ? d.copper < total : Sky.economy().purse(p) < total) { Msg.error(p, "You can't afford that!"); return; }
        String what = (qty > 1 ? qty + "x " : "") + Text.strip(G.itemName(e.item));
        Sky.menus().confirm(p, "Buy " + what + "?", List.of("&7Cost: " + price(e, qty)), () -> {
            GardenData dd = G.data(p);
            if (G.level(dd) < e.level) { Msg.error(p, "Requires Garden " + GardenMath.roman(e.level) + "!"); return; }
            if (!G.has(p, e.needs)) { Msg.error(p, "You are missing an item this needs!"); return; }
            if (copper) {
                if (dd.copper < total) { Msg.error(p, "You can't afford that!"); return; }
            } else if (!Sky.economy().withdraw(p, total, "garden: copper market " + e.item + " x" + qty)) { Msg.error(p, "You can't afford that!"); return; }
            if (!e.needs.isEmpty() && !G.take(p, e.needs)) {
                if (!copper) Sky.economy().deposit(p, total, "garden: refund " + e.item);
                return;
            }
            if (copper) dd.copper -= (long) total;
            G.give(p, e.item, qty, LootSource.NPC_SHOP);
            Msg.success(p, "You bought " + (qty > 1 ? "&a" + qty + "x " : "") + G.itemName(e.item) + "&a!");
            Msg.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.2f);
            open(p, cat, page);
        }, () -> open(p, cat, page));
    }
}
