package com.skyclone.mod.extras;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The community desk: Community Shop (bank module), City Projects and Fire Sales (here). Opened by calling the
 * Community Shop keeper on the phone and by {@code /community}; the bank's Community Shop menu can link
 * {@code /cityprojects} and {@code /firesales} directly (hand-off request).
 */
final class Community {

    private Community() {}

    static void open(Player p) {
        Menu m = new Menu("Community", 4).chrome();
        m.set(11, Icons.of(Material.EMERALD, "&aCommunity Shop", "&7Bits shop, Boon Buff, fame ranks", "&7and account upgrades.", "", "&eClick to open!"),
                c -> { if (!Sky.commands().run(c.player(), "cshop")) c.player().closeInventory(); });
        long left = City.cycleMillisLeft();
        m.set(13, Icons.of(Material.BRICKS, "&aCity Projects", "&7Build the city together. Every part you", "&7fund gives you a permanent perk; a",
                "&7finished project helps everyone.", "", "&7These projects end in &e" + Text.duration(left / 1000), "", "&eClick to view!"),
                c -> City.open(c.player()));
        m.set(15, Icons.of(Material.BLAZE_POWDER, "&6Fire Sales", "&7Rare cosmetic skins for a limited", "&7time, in limited numbers, for Bits.", "",
                FireSales.isOpen() ? "&aA Fire Sale is on now!" : "&7Next sale in &e" + Text.duration(FireSales.millisToNext() / 1000), "", "&eClick to view!"),
                c -> FireSales.open(c.player()));
        m.open(p);
    }
}
