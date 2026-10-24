package com.skyclone.mod.mayors;

import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.LoreContributor;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.time.SkyDate;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The New Year Celebration (research/05 §7.8): Late Winter 29-31. The Baker gives every player ONE
 * free New Year Cake per SkyBlock year, stamped with that year — so missed years become collectibles.
 * He also sells the Cake Bag (250,000 coins). The year lives in ItemData.extra.year and a lore line.
 */
final class NewYear {

    private final MayorsModule mod;
    private boolean announced;

    NewYear(MayorsModule mod) { this.mod = mod; }

    boolean active() { return Cal.active(Cal.NEW_YEAR); }

    void tick() {
        boolean on = active();
        if (on && !announced) {
            announced = true;
            int year = Sky.calendar().now().year();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!EventAlerts.news(p, Cal.NEW_YEAR)) continue;
                Msg.send(p, "&d&lNEW YEAR! &eHappy new SkyBlock Year &d" + (year + 1) + "&e! The &eBaker &eis handing out");
                Msg.send(p, "&eone free &cNew Year Cake &eto everyone. Visit him in the Hub or use &a/newyear&e.");
                Msg.sound(p, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1f);
            }
        } else if (!on) {
            announced = false;
        }
    }

    /** Lore lines for the dated keepsakes this module hands out. */
    static void registerLore() {
        Sky.items().addLoreContributor("mayors_keepsakes", new LoreContributor() {
            @Override public Section section() { return Section.BEFORE_FOOTER; }
            @Override public List<String> lines(ItemDef def, ItemData data) {
                if (def == null || data == null || data.extra == null) return List.of();
                JsonObject x = data.extra;
                if ("NEW_YEAR_CAKE".equals(def.id) && x.has("year")) {
                    return List.of("&7Baked for SkyBlock Year &d" + x.get("year").getAsInt() + "&7.");
                }
                if ("SPOOKY_PIE".equals(def.id) && x.has("year")) {
                    List<String> out = new ArrayList<>();
                    out.add("&7Spooky Festival, Year &6" + x.get("year").getAsInt());
                    if (x.has("player")) out.add("&7Player: &6" + x.get("player").getAsString());
                    if (x.has("rank")) out.add("&7Position: &a#" + Text.commasInt(x.get("rank").getAsInt()));
                    if (x.has("points")) out.add("&7Points: &6" + Text.commasInt(x.get("points").getAsLong()));
                    return out;
                }
                if (x.has("winning_bid")) {
                    return List.of("&7Won at the &5Dark Auction &7for &6" + Text.commasInt(x.get("winning_bid").getAsDouble()) + " coins&7.");
                }
                return List.of();
            }
        });
    }

    /** /newyear : the Baker. */
    void open(Player p) {
        Menu m = new Menu("Baker", 4).chrome();
        SkyDate d = Sky.calendar().now();
        PlayerData pd = mod.data(p);
        boolean on = active();
        List<String> lore = new ArrayList<>();
        lore.add("&7One free cake per SkyBlock year, during");
        lore.add("&7the &dNew Year Celebration &7(&eLate Winter 29th-31st&7).");
        lore.add("");
        if (!mod.dials().freeCake.get()) {
            lore.add("&cFree cakes are switched off on this server.");
        } else if (!on) {
            lore.add("&7Next celebration: " + Cal.when(Cal.NEW_YEAR));
        } else if (pd != null && pd.lastCakeYear >= d.year()) {
            lore.add("&cYou already have this year's cake!");
        } else {
            lore.add("&eClick to claim your Year " + d.year() + " cake!");
        }
        m.set(13, Icons.of(Material.CAKE, "&cNew Year Cake", lore), c -> claim(c.player()));
        int slot = 15;
        for (EventData.Offer o : mod.data().newYear.shop) {
            if (!Goods.available(o)) continue;
            m.set(slot++, ShopUi.offerIcon(p, o, 1.0, null), c -> { ShopUi.buy(c.player(), o, 1.0, "baker"); open(c.player()); });
        }
        m.set(30, Icons.back("Festivals & Events"), c -> EventBoard.open(c.player()));
        m.open(p);
    }

    void claim(Player p) {
        if (!mod.dials().freeCake.get()) { Msg.error(p, "Free cakes are switched off."); return; }
        if (!active()) { Msg.error(p, "Come back during the New Year Celebration!"); return; }
        PlayerData pd = mod.data(p);
        if (pd == null) return;
        int year = Sky.calendar().now().year();
        if (pd.lastCakeYear >= year) { Msg.error(p, "You already claimed this year's cake!"); return; }
        pd.lastCakeYear = year;
        giveCake(p, year);
        Sky.profiles().saveNow(p);
        Msg.success(p, "The Baker hands you a &cNew Year Cake &afor Year &d" + year + "&a!");
        Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.4f);
    }

    static void giveCake(Player p, int year) {
        ItemStack cake = Sky.items().create("NEW_YEAR_CAKE", 1);
        Sky.items().update(cake, d -> d.extra.addProperty("year", year));
        Sky.loot().give(p, cake, LootSource.EVENT);
    }
}
