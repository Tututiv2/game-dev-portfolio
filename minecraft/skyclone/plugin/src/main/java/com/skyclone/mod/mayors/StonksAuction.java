package com.skyclone.mod.mayors;

import com.skyclone.api.Sky;
import com.skyclone.api.time.SkyDate;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Stonks Auction (research/05 §5.3), open while the Economist's Share Floor perk is on. Players
 * bid coins (minimum 2,000,000, raise only) during an SkyBlock month; when the month turns, every
 * bidder receives Shares of Stonks — one per 2,000,000 bid, at least one (TUNE: the reference ranking
 * table is not public). Bids are escrowed when placed: the coins are a sink, the stocks the prize.
 */
final class StonksAuction {

    private final MayorsModule mod;
    private final Map<UUID, Double> bids = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private String round;

    StonksAuction(MayorsModule mod) { this.mod = mod; }

    boolean active() { return mod.election().perkActive("diaz.stock_exchange"); }

    static String roundKey(SkyDate d) { return "Y" + d.year() + "M" + d.month(); }

    void load() {
        round = MayorsDb.readKey("stonks_round");
        if (round != null) loadBids(round);
    }

    private void loadBids(String key) {
        bids.clear();
        names.clear();
        try {
            Map<UUID, Object[]> rows = Sky.db().query(c -> {
                Map<UUID, Object[]> out = new HashMap<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name, bid FROM mayors_stonks WHERE round = ?")) {
                    ps.setString(1, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            try { out.put(UUID.fromString(rs.getString(1)), new Object[]{rs.getString(2), rs.getDouble(3)}); } catch (IllegalArgumentException ignored) { }
                        }
                    }
                }
                return out;
            }).join();
            rows.forEach((u, r) -> { names.put(u, (String) r[0]); bids.put(u, (Double) r[1]); });
        } catch (Exception e) {
            Sky.log().warning("[mayors] could not load stonks bids: " + e);
        }
    }

    /** Every second: settle a finished month (or a month whose perk ended). */
    void tick() {
        String now = roundKey(Sky.calendar().now());
        if (round != null && (!round.equals(now) || !active()) && !bids.isEmpty()) settle();
        if (active() && !now.equals(round)) {
            round = now;
            bids.clear();
            names.clear();
            MayorsDb.writeKey("stonks_round", round);
        }
    }

    int stocksFor(double bid) {
        double per = Math.max(1, mod.data().stonks.coinsPerStock);
        return (int) Math.max(1, Math.floor(bid / per));
    }

    String settle() {
        if (bids.isEmpty()) return "no bids";
        int people = 0;
        for (Map.Entry<UUID, Double> e : bids.entrySet()) {
            int n = stocksFor(e.getValue());
            Claims.give(e.getKey(), "STOCK_OF_STONKS", n, null, "stonks auction " + round);
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) Msg.send(p, "&6&lSTONKS! &7Your bid of &6" + Text.commasInt(e.getValue()) + " &7bought &5" + n + " Share" + (n == 1 ? "" : "s") + " of Stonks&7!");
            people++;
        }
        bids.clear();
        names.clear();
        Sky.log().info("[mayors] stonks round " + round + " settled for " + people + " bidder(s)");
        return null;
    }

    void bid(Player p, double amount) {
        if (!active()) { Msg.error(p, "The Stonks Auction is closed."); return; }
        if (!Double.isFinite(amount) || amount <= 0 || amount > 1e13) { Msg.error(p, "That is not a valid bid."); return; }
        double min = mod.dials().stonksMinBid.get();
        double prev = bids.getOrDefault(p.getUniqueId(), 0.0);
        amount = Math.floor(amount);
        if (amount < min) { Msg.error(p, "The minimum bid is " + Text.commasInt(min) + " coins."); return; }
        if (amount <= prev) { Msg.error(p, "You can only raise your bid (current: " + Text.commasInt(prev) + ")."); return; }
        double delta = amount - prev;
        if (!Sky.economy().withdraw(p, delta, "stonks auction: bid " + round)) { Msg.error(p, "You can't afford that bid!"); return; }
        bids.put(p.getUniqueId(), amount);
        names.put(p.getUniqueId(), p.getName());
        String key = round, uuid = p.getUniqueId().toString(), name = p.getName(), prof = Sky.profiles().profileId(p);
        double fin = amount;
        Sky.db().transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO mayors_stonks(round, uuid, name, bid, profile) VALUES (?,?,?,?,?) "
                    + "ON CONFLICT(round, uuid) DO UPDATE SET bid = excluded.bid, name = excluded.name")) {
                ps.setString(1, key); ps.setString(2, uuid); ps.setString(3, name); ps.setDouble(4, fin); ps.setString(5, prof);
                ps.executeUpdate();
            }
            return null;
        });
        Sky.profiles().saveNow(p);
        Msg.success(p, "Your Stonks bid is now " + Text.commasInt(amount) + " coins (" + stocksFor(amount) + " share" + (stocksFor(amount) == 1 ? "" : "s") + ").");
        Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1.6f);
    }

    /** /stonks */
    void open(Player p) {
        Menu m = new Menu("Stonks Auction", 4).chrome();
        boolean on = active();
        double mine = bids.getOrDefault(p.getUniqueId(), 0.0);
        List<String> lore = new ArrayList<>();
        lore.add("&7While the &6Economist &7holds the &eShare Floor");
        lore.add("&7perk, bid coins each SkyBlock month. When the");
        lore.add("&7month ends every bidder gets &5Shares of Stonks&7:");
        lore.add("&7one per &6" + Text.shortNum(mod.data().stonks.coinsPerStock) + " &7bid, at least one.");
        lore.add("");
        lore.add(on ? "&aThe auction is open." : "&cClosed: the perk is not active.");
        lore.add("&7Bidders this month: &e" + bids.size());
        lore.add("&7Your bid: &6" + (mine > 0 ? Text.commasInt(mine) : "none"));
        m.set(4, Icons.of(Material.PAPER, "&6Stonks Auction", lore));
        m.set(13, Icons.of(Material.GOLD_BLOCK, on ? "&aPlace or raise a bid" : "&cClosed",
                "&7Minimum: &6" + Text.commasInt(mod.dials().stonksMinBid.get()) + " coins", "&7Bids are taken from your purse", "&7right away and cannot be withdrawn.", "", on ? "&eClick to bid!" : ""),
                c -> {
                    if (!active()) { Msg.error(c.player(), "The Stonks Auction is closed."); return; }
                    Sky.menus().inputNumber(c.player(), "Your total bid", n -> { if (n != null) bid(c.player(), n); });
                });
        int slot = 19;
        for (EventData.Offer o : mod.data().stonks.shop) {
            if (!Goods.available(o) || slot > 25) continue;
            m.set(slot++, ShopUi.offerIcon(p, o, 1.0, null), c -> { ShopUi.buy(c.player(), o, 1.0, "stonks trade"); open(c.player()); });
        }
        m.set(30, Icons.back("Festivals & Events"), c -> EventBoard.open(c.player()));
        m.open(p);
    }
}
