package com.skyclone.mod.mayors;

import com.skyclone.api.Sky;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Msg;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shared NPC-shop rendering and buying for every event vendor. */
final class ShopUi {

    private ShopUi() {}

    private static final Map<UUID, Long> lastBuy = new HashMap<>();

    static ItemStack offerIcon(Player p, EventData.Offer o, double coinScale, String lockedLine) {
        ItemStack icon = Goods.icon(o.result, o.amount);
        List<String> extra = new ArrayList<>();
        extra.add("");
        extra.addAll(Goods.costLines(o, coinScale));
        extra.add("");
        if (lockedLine != null) extra.add(lockedLine);
        else extra.add(Goods.canAfford(p, o, coinScale) ? "&eClick to trade!" : "&cYou can't afford this!");
        return Icons.withLore(icon, extra);
    }

    /** Validate, take the cost, give the result. Rate-limited to one trade per 250 ms. */
    static boolean buy(Player p, EventData.Offer o, double coinScale, String reason) {
        long now = System.currentTimeMillis();
        Long last = lastBuy.get(p.getUniqueId());
        if (last != null && now - last < 250) return false;
        lastBuy.put(p.getUniqueId(), now);
        String result = Goods.resolve(o.result);
        if (result == null || !Goods.available(o)) { Msg.error(p, "That item is not available."); return false; }
        if (!Goods.canAfford(p, o, coinScale)) { Msg.error(p, "You can't afford this!"); return false; }
        if (!Goods.pay(p, o, coinScale, "mayors: " + reason + " " + result)) { Msg.error(p, "You can't afford this!"); return false; }
        Goods.give(p, result, Math.max(1, o.amount), LootSource.NPC_SHOP);
        Msg.send(p, "&aYou bought " + Goods.displayName(result) + (o.amount > 1 ? " &8x" + o.amount : "") + "&a!");
        Msg.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.4f);
        Sky.profiles().saveNow(p);
        return true;
    }
}
