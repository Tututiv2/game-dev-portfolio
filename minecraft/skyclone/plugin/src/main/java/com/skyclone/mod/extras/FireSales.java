package com.skyclone.mod.extras;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fire Sales (research 05 / 08 section 6.8: limited-time cosmetics; research/data/firesales.json is a live API
 * sample: 5 helmet skins, 950 stock each, 650 gems, ~4 days). Gems are not sold here, so the price is in EARNED
 * Bits ({@code extras.firesale.bits-per-gem}). Only cosmetics are ever on sale (item skins, which change how a
 * helmet looks and nothing else). One sale per cycle, picked from a fixed shuffled order of every skin.
 */
final class FireSales {

    private FireSales() {}

    /** "SKIN@cycle" -> copies sold (server-wide). */
    static final Map<String, Integer> SOLD = new ConcurrentHashMap<>();
    private static final Set<UUID> BUSY = new HashSet<>();
    private static long announced = Long.MIN_VALUE;

    static double cycleHours() { return Math.max(1, XDials.fireCycleHours.get()); }

    static long cycle() { return Rotation.cycle(System.currentTimeMillis(), cycleHours()); }

    static boolean isOpen() {
        return XDials.fireEnabled.get() && Rotation.open(System.currentTimeMillis(), cycleHours(), XDials.fireOpenHours.get());
    }

    static long millisToNext() {
        long now = System.currentTimeMillis();
        return Math.max(0, Rotation.cycleStart(cycle() + 1, cycleHours()) - now);
    }

    static long millisLeft() {
        long end = Rotation.cycleStart(cycle(), cycleHours()) + Rotation.periodMs(Math.min(XDials.fireOpenHours.get(), cycleHours()));
        return Math.max(0, end - System.currentTimeMillis());
    }

    /** Skins that can be sold here: their item and at least one helmet they fit exist. */
    static List<Catalog.Skin> catalogue() {
        List<Catalog.Skin> out = new ArrayList<>();
        for (Catalog.Skin s : X.catalog.skins.values()) {
            if (!Sky.items().exists(s.id)) continue;
            boolean fits = false;
            for (String t : s.targets) if (Sky.items().exists(t)) { fits = true; break; }
            if (fits) out.add(s);
        }
        return out;
    }

    static List<Catalog.Skin> sale(long cycle) {
        List<Catalog.Skin> all = catalogue();
        List<Catalog.Skin> out = new ArrayList<>();
        for (int i : Rotation.picks(cycle, all.size(), XDials.fireCount.get())) out.add(all.get(i));
        return out;
    }

    static long price(Catalog.Skin s) { return Math.max(1, Math.round(s.gems * XDials.fireBitsPerGem.get())); }

    static int stock(Catalog.Skin s) { return Math.max(1, (int) Math.round(s.stock * XDials.fireStockScale.get())); }

    static String key(Catalog.Skin s, long cycle) { return s.id + "@" + cycle; }

    // ---- persistence ---------------------------------------------------------------------------------------

    static void load() {
        Sky.db().query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT sale, sold FROM extras_firesale");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) SOLD.put(rs.getString(1), rs.getInt(2));
            }
            return null;
        });
    }

    /** Called once a minute: announce a sale that just opened. */
    static void tick() {
        if (!isOpen()) return;
        long c = cycle();
        if (announced == Long.MIN_VALUE) { announced = c; return; }   // never announce on boot
        if (announced == c) return;
        announced = c;
        Msg.broadcast("&6&l✹ FIRE SALE! &eLimited cosmetic skins are on sale for Bits for &a"
                + Text.duration(millisLeft() / 1000) + "&e! &7(/firesales)");
    }

    // ---- buying --------------------------------------------------------------------------------------------

    static void buy(Player p, Catalog.Skin s) {
        if (!X.ready(p)) return;
        if (!isOpen()) { Msg.error(p, "There is no Fire Sale right now."); return; }
        long c = cycle();
        boolean onSale = false;
        for (Catalog.Skin x : sale(c)) if (x.id.equals(s.id)) { onSale = true; break; }
        if (!onSale) { Msg.error(p, "That skin is not on sale."); return; }
        String key = key(s, c);
        ExtrasData d = X.data(p);
        if (d.fireBought.getOrDefault(key, 0) >= XDials.firePerPlayer.get()) { Msg.error(p, "You already bought the most you can of that skin."); return; }
        long price = price(s);
        if (Sky.economy().bits(p) < price) { Msg.error(p, "You need " + Text.commas(price) + " Bits."); return; }
        int stock = stock(s);
        if (SOLD.getOrDefault(key, 0) >= stock) { Msg.error(p, "Sold out!"); return; }
        UUID id = p.getUniqueId();
        if (!BUSY.add(id)) return;
        // reserve one copy in the DB first (stock is server-wide), then take the Bits on the main thread
        Sky.db().transaction(conn -> {
            try (PreparedStatement ins = conn.prepareStatement("INSERT OR IGNORE INTO extras_firesale (sale, sold) VALUES (?, 0)")) {
                ins.setString(1, key);
                ins.executeUpdate();
            }
            try (PreparedStatement up = conn.prepareStatement("UPDATE extras_firesale SET sold = sold + 1 WHERE sale = ? AND sold < ?")) {
                up.setString(1, key);
                up.setInt(2, stock);
                return up.executeUpdate() == 1;
            }
        }).whenComplete((ok, err) -> Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            BUSY.remove(id);
            if (err != null || ok == null || !ok) {
                if (err != null) Sky.plugin().getLogger().warning("extras: fire sale reserve failed: " + err);
                if (p.isOnline()) Msg.error(p, err != null ? "Something went wrong, nothing was charged." : "Sold out!");
                return;
            }
            SOLD.merge(key, 1, Integer::sum);
            if (!p.isOnline() || !X.ready(p) || !Sky.economy().spendBits(p, price, "extras: fire sale " + key)) {
                release(key);
                if (p.isOnline()) Msg.error(p, "You need " + Text.commas(price) + " Bits.");
                return;
            }
            ExtrasData dd = X.data(p);
            dd.fireBought.merge(key, 1, Integer::sum);
            Sky.loot().give(p, s.id, 1, LootSource.REWARD);
            Msg.send(p, "&6&lFIRE SALE! &7You bought " + X.itemName(s.id) + " &7for &b" + Text.commas(price) + " Bits&7.");
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            Sky.profiles().saveNow(p);
        }));
    }

    private static void release(String key) {
        SOLD.merge(key, -1, Integer::sum);
        Sky.db().transaction(conn -> {
            try (PreparedStatement up = conn.prepareStatement("UPDATE extras_firesale SET sold = MAX(0, sold - 1) WHERE sale = ?")) {
                up.setString(1, key);
                up.executeUpdate();
            }
            return null;
        });
    }

    // ---- menu ----------------------------------------------------------------------------------------------

    static void open(Player p) {
        if (!X.ready(p)) { Msg.error(p, "Your profile is still loading."); return; }
        Menu m = new Menu("Fire Sales", 6).chrome();
        boolean open = isOpen();
        long c = open ? cycle() : cycle() + 1;
        List<Catalog.Skin> list = sale(c);
        m.set(4, Icons.of(Material.BLAZE_POWDER, "&6Fire Sales",
                "&7Limited cosmetic skins, a few at a", "&7time, for Bits. Purely cosmetic:", "&7a skin changes how your helmet looks.",
                "", open ? "&aOn now! &7Ends in &e" + Text.duration(millisLeft() / 1000)
                        : "&7Next sale in &e" + Text.duration(millisToNext() / 1000),
                "&7Your Bits: &b" + Text.commas(Sky.economy().bits(p))));
        int[] slots = {20, 21, 22, 23, 24, 29, 30, 31, 32, 33};
        ExtrasData d = X.data(p);
        for (int i = 0; i < list.size() && i < slots.length; i++) {
            Catalog.Skin s = list.get(i);
            String key = key(s, c);
            ItemDef def = Sky.items().def(s.id);
            List<String> l = new ArrayList<>();
            if (def != null) for (String line : def.lore) { if (line.startsWith("&eRight-click")) continue; l.add(line); }
            l.add("");
            l.add("&7Price: &b" + Text.commas(price(s)) + " Bits");
            int left = Math.max(0, stock(s) - SOLD.getOrDefault(key, 0));
            l.add("&7Stock: &a" + Text.commas(left) + "&7/&a" + Text.commas(stock(s)));
            l.add("&7You bought: &a" + d.fireBought.getOrDefault(key, 0) + "&7/&a" + XDials.firePerPlayer.get());
            l.add("");
            if (!open) l.add("&7Coming in the next sale.");
            else if (left <= 0) l.add("&cSold out!");
            else l.add("&eClick to buy!");
            ItemStack icon = def != null && def.skull != null ? Icons.skull(def.skull, X.itemName(s.id), l) : Icons.of(Material.PLAYER_HEAD, X.itemName(s.id), l);
            m.set(slots[i], icon, cl -> {
                if (!isOpen()) return;
                Sky.menus().confirm(cl.player(), "Buy for " + Text.commas(price(s)) + " Bits?",
                        List.of("&7" + Text.strip(X.itemName(s.id)), "&7Price: &b" + Text.commas(price(s)) + " Bits"),
                        () -> { buy(cl.player(), s); Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (cl.player().isOnline()) open(cl.player()); }, 10L); },
                        () -> open(cl.player()));
            });
        }
        m.set(48, Icons.back("Community"), c2 -> Community.open(c2.player()));
        m.set(50, Icons.of(Material.ARMOR_STAND, "&aYour Skins", "&7Apply or remove skins.", "", "&eClick to open!"), c2 -> Skins.open(c2.player()));
        m.open(p);
    }
}
