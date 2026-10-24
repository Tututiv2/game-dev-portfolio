package com.skyclone.mod.shops;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.mod.shops.ShopData.Shop;
import com.skyclone.mod.shops.ShopData.Townsfolk;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Puts merchant NPCs into the world.
 *
 * <ul>
 *   <li><b>contract</b> merchants (anchor guaranteed by CONTRACT §4) are registered in postEnable unless
 *       another module already stands an NPC on that anchor - then that module runs the spot and we yield
 *       (enchanting's Librarian, alchemy's Alchemist, a builder's own forger...).</li>
 *   <li><b>secondary</b> merchants are registered only once their island's layout HAS the anchor, so nothing
 *       ever piles up at an island spawn. Until then they are "travelling" and trade from /merchants.
 *       Re-checked when an island finishes building and once a minute.</li>
 * </ul>
 */
final class Stationing {

    private Stationing() {}

    /** Id of an NPC another module put on (island, anchor), or null. */
    static String anchorOwner(String island, String anchor) {
        for (NpcDef d : Sky.npcs().all()) {
            if (d.id == null || d.id.startsWith("shops_") || d.id.startsWith("townsfolk_")) continue;
            if (island.equals(d.island) && anchor.equals(d.anchor)) return d.id;
        }
        return null;
    }

    private static boolean anchorInLayout(String island, String anchor) {
        Layout l = Shops.layout(island);
        return l != null && l.anchor(anchor) != null;
    }

    /** Evaluate every merchant that is not settled yet. Safe to call any number of times. */
    static int stationAll() {
        int added = 0;
        boolean npcs = ShopDials.NPCS.get();
        for (Shop s : Shops.all()) {
            Shops.Status st = Shops.STATUS.get(s.id);
            if (st == Shops.Status.STATIONED || st == Shops.Status.YIELDED || st == Shops.Status.DISABLED) continue;
            if (!npcs) { Shops.STATUS.put(s.id, Shops.Status.DISABLED); continue; }
            String owner = anchorOwner(s.island, s.anchor);
            if (owner != null) {
                Shops.STATUS.put(s.id, Shops.Status.YIELDED);
                Shops.YIELDED_TO.put(s.id, owner);
                continue;
            }
            if (s.contract() || anchorInLayout(s.island, s.anchor)) {
                register(s);
                Shops.STATUS.put(s.id, Shops.Status.STATIONED);
                added++;
            } else {
                Shops.STATUS.put(s.id, Shops.Status.PENDING);
            }
        }
        if (ShopDials.TOWNSFOLK.get()) {
            for (Townsfolk t : Shops.TOWNSFOLK) {
                if (Shops.TOWN_STATUS.get(t.id) == Shops.Status.STATIONED) continue;
                if (anchorOwner(t.island, t.anchor) != null || !anchorInLayout(t.island, t.anchor)) {
                    Shops.TOWN_STATUS.put(t.id, Shops.Status.PENDING);
                    continue;
                }
                registerTownsfolk(t);
                Shops.TOWN_STATUS.put(t.id, Shops.Status.STATIONED);
                added++;
            }
        }
        return added;
    }

    private static void register(Shop s) {
        NpcDef d = NpcDef.of(Shops.npcId(s), s.name, s.island, s.anchor).title("&e&lCLICK");
        if (s.skin != null && !s.skin.isBlank()) d.skin(s.skin, null);
        Sky.npcs().register(d, p -> onMerchantClick(p, s));
    }

    private static void registerTownsfolk(Townsfolk t) {
        NpcDef d = NpcDef.of(t.id, t.name, t.island, t.anchor);
        if (t.skin != null && !t.skin.isBlank()) d.skin(t.skin, null);
        Sky.npcs().register(d, p -> Sky.npcs().talk(p, t.id, t.lines, null));
    }

    /** Right/left click on a merchant: greet the first time, then open the shop (trading in person). */
    static void onMerchantClick(Player p, Shop s) {
        if (p == null || !p.isOnline()) return;
        try { Sky.service(Features.Objectives.class).complete(p, "talk_merchant"); } catch (Throwable ignored) { }
        ShopMenus.Session session = new ShopMenus.Session(s, true, null, false);
        if (ShopDials.GREET_FIRST.get() && Sky.profiles().isReady(p)) {
            Pricing.Ledger led = Trade.ledger(p);
            if (led.met.add(s.id) && s.greeting != null && !s.greeting.isEmpty()) {
                Sky.npcs().talk(p, Shops.npcId(s), List.copyOf(s.greeting), () -> ShopMenus.openShop(p, session));
                return;
            }
        }
        ShopMenus.openShop(p, session);
    }
}
