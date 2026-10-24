package com.skyclone.mod.shops;

import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.ui.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * NPC merchants: every shop of the reference's NPC data (neu-repo, 76 merchants over 15 islands), stationed at
 * island anchors, with the genre's shop menu (click to buy one, shift for a stack, right-click for amounts),
 * selling by clicking your own items or through a sell box, a 10-item buyback, daily limits, accessory
 * discounts, and a Merchant Directory ({@code /merchants}) that shows where everybody is.
 *
 * <p>Owns no items. Coins via the economy (reasons {@code npc-buy:<shop>:<ID> xN}, {@code npc-sell:<ID> xN},
 * {@code npc-buyback:<ID> xN}); purchases enter the inventory through {@code Sky.loot().give(.., NPC_SHOP)}, so
 * they never count toward collections.
 */
public final class ShopsModule implements GameModule, Listener {

    @Override public String id() { return "shops"; }

    @Override
    public void enable() {
        ShopDials.register();
        Shops.load();
        Sky.log().info("[shops] loaded " + Shops.BY_ID.size() + " merchants and " + Shops.TOWNSFOLK.size() + " townsfolk");

        Sky.commands().register(SkyCommand.of("merchants", "Open the Merchant Directory: every NPC shop, where it is and what it sells")
                .alias("merchantdirectory", "npcshops").category("Economy").playerOnly()
                .run((s, a) -> {
                    Player p = (Player) s;
                    if (a.length > 0) {
                        ShopData.Shop shop = Shops.get(a[0]);
                        if (shop != null && Shops.status(shop) != Shops.Status.YIELDED) {
                            String block = ShopMenus.remoteBlock(p, shop);
                            ShopMenus.openShop(p, new ShopMenus.Session(shop, block == null, block, true));
                            return;
                        }
                    }
                    ShopMenus.directory(p, 0).open(p);
                })
                .tab((s, a) -> a.length <= 1 ? new ArrayList<>(Shops.BY_ID.keySet()) : List.of()));

        Sky.commands().register(SkyCommand.of("buyback", "See the items you recently sold to merchants and buy them back")
                .category("Economy").playerOnly()
                .run((s, a) -> {
                    Player p = (Player) s;
                    if (!ShopDials.BUYBACK_ANYWHERE.get()) {
                        Msg.error(p, "Talk to any merchant to use buyback.");
                        return;
                    }
                    ShopMenus.buybackMenu(p, null, null).open(p);
                }));

        // a unique item sold to a merchant lives only in buyback until it is bought back or expires; core's dupe scan
        // then catches the same uuid turning up in an inventory too (wave 5 hook)
        if (ShopDials.BUYBACK_DUPE_SCAN.get()) {
            try { Sky.items().addDupeScanSource("shops_buyback", Trade::buybackStacks); } catch (Throwable t) { Sky.log().warning("[shops] dupe-scan source: " + t); }
        }
        Decorations.register();
        AdminCommands.register();
        SelfTests.register();
        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());
    }

    @Override
    public void postEnable() {
        // Every module has enabled now, so any NPC another module put on one of our anchors is known.
        int n = Stationing.stationAll();
        Sky.log().info("[shops] stationed " + n + " NPC(s); the rest wait for their island layout (see /sb shops npcs)");
        // after stationing: a yielded merchant's spot belongs to another module, which registers its own lines
        if (ShopDials.RECIPES.get()) {
            try { ShopRecipes.registerAll(); } catch (Throwable t) { Sky.log().warning("[shops] recipes: " + t); }
            // accessories re-applies its NPC sell caps a tick after postEnable; the price floor follows sell prices
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                try { ShopRecipes.refreshPrices(); } catch (Throwable t) { Sky.log().warning("[shops] recipe re-price: " + t); }
            }, 40L);
        }
        // Secondary merchants appear as soon as their island has an anchor for them.
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> {
            try { Stationing.stationAll(); } catch (Throwable t) { Sky.log().warning("[shops] stationing: " + t); }
        }, 20L * 30, 20L * 60);
    }

    @EventHandler
    public void onIslandBuilt(SkyEvents.IslandBuilt e) {
        if (e.instanceKey != null) return;
        Bukkit.getScheduler().runTask(Sky.plugin(), Stationing::stationAll);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        // Buyback is cleared on logout, as in the genre; the spam-guard entry goes too.
        Trade.forget(e.getPlayer());
    }
}
