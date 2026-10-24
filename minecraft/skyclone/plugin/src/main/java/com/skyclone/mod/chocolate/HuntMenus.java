package com.skyclone.mod.chocolate;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.npc.NpcDef;
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
import java.util.Random;

/** Hopsworth (the Hunt's host) and his menu: today's eggs, the Egg Seeker, the fish for his number, his daily offer. */
final class HuntMenus {

    private HuntMenus() {}

    /** TUNE: coins for Hopsworth's daily rabbit offer, by rarity. */
    static final Map<String, Double> OFFER_PRICE = Map.of(
            "COMMON", 100_000.0, "UNCOMMON", 250_000.0, "RARE", 1_000_000.0, "EPIC", 5_000_000.0, "LEGENDARY", 25_000_000.0);

    static boolean npcRegistered;

    /** Register Hopsworth only where his anchor exists, so a missing anchor never fails {@code ui.npc-anchors}. */
    static void registerNpc() {
        String island = Choc.npcIsland.get(), anchor = Choc.npcAnchor.get();
        boolean has;
        try {
            var l = Sky.worlds().layout(island);
            has = l != null && l.anchor(anchor) != null;
        } catch (Throwable t) { has = false; }
        if (!has) {
            Sky.log().info("[chocolate] anchor " + island + "/" + anchor + " not in the layout yet: Hopsworth is not placed (/hoppity still works)");
            return;
        }
        NpcDef def = NpcDef.of(Choc.NPC, "&d" + Choc.NPC_NAME, island, anchor).title("&e&lCLICK");
        def.lines.add("&7Chocolate Factory");
        if (Choc.catalog.factory.npcSkin != null) def.skin(Choc.catalog.factory.npcSkin, null);
        Sky.npcs().register(def, p -> Sky.npcs().talk(p, Choc.NPC,
                List.of(Hunt.active() ? "&fThe eggs are out! Go on, they won't find themselves." : "&fSpring is egg season. Until then, mind the factory!"),
                () -> openHunt(p, true)));
        npcRegistered = true;
    }

    static boolean contact(Player p, ChocData d) {
        if (d.fishGiven) return true;
        try { return Sky.service(Features.Abiphone.class).hasContact(p, Choc.NPC); } catch (Throwable t) { return false; }
    }

    static void openHunt(Player p, boolean atNpc) {
        if (!Choc.ready(p)) return;
        ChocData d = Choc.data(p);
        boolean on = Hunt.active();
        Menu m = new Menu(Choc.HUNT_NAME, 5).chrome();
        List<String> status = new ArrayList<>();
        status.add("&7Through all of Spring, a Chocolate");
        status.add("&7Rabbit Egg hides on every island for");
        status.add("&7each meal of the day. Find one and the");
        status.add("&7rabbit inside joins your factory.");
        status.add("");
        if (on) status.add("&aRunning! &7Ends in &a" + Text.duration(Sky.calendar().millisRemaining(Choc.HUNT) / 1000));
        else status.add("&7Starts in &e" + Text.duration(Sky.calendar().millisUntil(Choc.HUNT) / 1000));
        status.add("&7Eggs found: &a" + d.eggsFound + " &8| &7Rabbits: &a" + d.unique() + "&7/&a" + Choc.catalog.rabbits.size());
        m.set(4, Icons.of(Material.EGG, (on ? "&a" : "&7") + Choc.HUNT_NAME, status));

        long abs = Hunt.nowAbs();
        List<ChocCatalog.Meal> meals = Hunt.meals();
        int[] slots = {10, 11, 12, 14, 15, 16};
        for (int i = 0; i < meals.size() && i < slots.length; i++) {
            ItemStack icon = on ? Hunt.mealIcon(d, meals.get(i), abs)
                    : Icons.of(Material.GRAY_DYE, "&7" + meals.get(i).name, "&7Only during " + Choc.HUNT_NAME + ".");
            m.set(slots[i], icon);
        }

        m.set(29, Icons.of(Material.COMPASS, "&aEgg Seeker", "&7Points at the nearest egg you have", "&7not found on your island.", "",
                d.seekerClaimed ? "&8You already took one. More in the shop." : "&eClick to take one, free!"), c -> {
            ChocData dd = Choc.data(c.player());
            if (dd.seekerClaimed) { Msg.send(c.player(), "&cYou already have one! Buy another in the Chocolate Shop."); return; }
            if (!Sky.items().exists("EGGLOCATOR")) return;
            dd.seekerClaimed = true;
            Sky.loot().give(c.player(), "EGGLOCATOR", 1, LootSource.REWARD);
            openHunt(c.player(), atNpc);
        });
        m.set(30, Icons.of(Material.BOOK, "&aHopsworth's Collection", "&7Found: &a" + d.unique() + "&7/&a" + Choc.catalog.rabbits.size(), "", "&eClick to view!"),
                c -> FactoryMenus.openCollection(c.player(), "ALL", 0));
        m.set(31, Icons.of(Material.DROPPER, "&6Chocolate Factory", "", "&eClick to open!"), c -> FactoryMenus.openFactory(c.player()));
        m.set(32, Icons.of(Material.IRON_SWORD, "&aRabbit Bounty Hunter", "&7Eggs waiting: &6" + d.hitmanEggs, "", "&eClick to view!"),
                c -> FactoryMenus.openBounty(c.player()));

        boolean phone = contact(p, d);
        if (!phone) {
            boolean holds = Sky.items().count(p, "STEAMED_CHOCOLATE_FISH") > 0;
            m.set(33, Icons.of(Material.COD, "&dHopsworth's Number", "&7Bring him a &5Steamed Chocolate Fish", "&7and he adds you to his Ringphone,",
                    "&7so his daily offer reaches you anywhere.", "", holds ? "&eClick to give him the fish!" : "&cYou have no fish for him."), c -> giveFish(c.player(), atNpc));
        }
        if (atNpc || phone) m.set(34, offerIcon(p, d, on), c -> buyOffer(c.player(), atNpc));
        m.set(m.closeSlot() - 1, Icons.back("Chocolate Factory"), c -> FactoryMenus.openFactory(c.player()));
        m.open(p);
    }

    static void giveFish(Player p, boolean atNpc) {
        ChocData d = Choc.data(p);
        if (contact(p, d)) return;
        if (Sky.items().remove(p, "STEAMED_CHOCOLATE_FISH", 1) < 1) { Msg.send(p, "&cYou have no Steamed Chocolate Fish!"); return; }
        d.fishGiven = true;
        try { Sky.service(Features.Abiphone.class).addContact(p, Choc.NPC); } catch (Throwable ignored) { }
        Msg.send(p, "&e[NPC] &d" + Choc.NPC_NAME + "&f: Mmm, the cocoa crust! You may call me any time, friend.");
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1.2f);
        openHunt(p, atNpc);
    }

    /** The daily offer: one rabbit you have not found, for coins, once per in-game day of the Hunt. */
    static ChocCatalog.Rabbit offer(Player p, ChocData d) {
        long day = Math.floorDiv(Hunt.nowAbs(), ChocMath.MIN_PER_DAY);
        if (d.offerDay != day) {
            d.offerDay = day;
            d.offerBought = false;
            d.offerRabbit = null;
            Random rng = new Random(p.getUniqueId().getLeastSignificantBits() ^ day * 31L);
            List<ChocCatalog.Rabbit> pool = new ArrayList<>();
            for (ChocCatalog.Rabbit r : Choc.catalog.rabbits) {
                if (!r.fromEggs() || r.resident() || d.has(r.id) || !OFFER_PRICE.containsKey(r.rarity)) continue;
                pool.add(r);
            }
            if (!pool.isEmpty()) d.offerRabbit = pool.get(rng.nextInt(pool.size())).id;
        }
        return Choc.catalog.rabbit(d.offerRabbit);
    }

    static ItemStack offerIcon(Player p, ChocData d, boolean on) {
        if (!on) return Icons.of(Material.GRAY_DYE, "&7Hopsworth's Offer", "&7Every day of the Hunt he sells", "&7one rabbit you have not found.", "", "&cOnly during the Hunt.");
        ChocCatalog.Rabbit r = offer(p, d);
        if (r == null) return Icons.of(Material.GRAY_DYE, "&7Hopsworth's Offer", "&aYou have every rabbit he could sell!");
        double price = OFFER_PRICE.getOrDefault(r.rarity, 1_000_000.0);
        List<String> lore = new ArrayList<>();
        lore.add("&7Today he will part with:");
        lore.add(Choc.rabbitName(r) + " &7(" + Choc.rarityColor(r.rarity) + r.rarity + "&7)");
        lore.add("");
        lore.add("&7Price: &6" + Choc.num(price) + " coins");
        lore.add("");
        lore.add(d.offerBought || d.has(r.id) ? "&aBought! Come back tomorrow." : "&eClick to buy!");
        return Icons.skull(r.texture, "&dHopsworth's Offer", lore);
    }

    static void buyOffer(Player p, boolean atNpc) {
        if (!Hunt.active()) { Msg.send(p, "&cHopsworth only sells rabbits during the Hunt."); return; }
        ChocData d = Choc.data(p);
        ChocCatalog.Rabbit r = offer(p, d);
        if (r == null || d.offerBought || d.has(r.id)) { Msg.send(p, "&cNothing to buy today."); return; }
        if (d.unique() >= Factory.barnCapacity(d)) { Msg.send(p, "&cYour Rabbit Barn is full! Upgrade it first."); return; }
        double price = OFFER_PRICE.getOrDefault(r.rarity, 1_000_000.0);
        if (!Sky.economy().withdraw(p, price, "chocolate: hopsworth offer " + r.id)) { Msg.send(p, "&cYou don't have enough coins!"); return; }
        d.offerBought = true;
        Factory.grant(p, r, "hopsworth", false, false);
        openHunt(p, atNpc);
    }
}
