package com.skyclone.mod.mayors;

import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.gen.Box;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Dark Auction (research/05 §5.1): every real hour at :55. Entry needs 400,000 coins in the purse.
 * After a lobby minute, lots go up one by one: 5 s to look, then a 10 s window that every bid resets.
 * Rounds 1-4 draw from the general pool, round 5 is always an enchanted book; while the Shadow Lots
 * perk (Scorpius) is on there are 7 rounds with darker lots.
 *
 * <p>Public-server rules: the top bid is ESCROWED — taken from the bidder's purse when placed and
 * refunded the moment someone outbids them (or the server stops). The winner has therefore already
 * paid; the lot is delivered with {@code extra.winning_bid} (the Midas weapons read it).
 */
final class DarkAuction {

    enum Phase { LOBBY, PREVIEW, BIDDING }

    static final class Session {
        final List<EventData.Lot> lots;
        final boolean darker;
        int round;                 // 0-based index into lots
        Phase phase = Phase.LOBBY;
        long phaseEnds;
        final Set<UUID> joined = new LinkedHashSet<>();
        UUID top;
        String topName;
        double topBid;
        final Map<UUID, Long> lastBid = new HashMap<>();
        /** Joined players already told they stepped out (said once per auction). */
        final Set<UUID> toldAway = new java.util.HashSet<>();
        Session(List<EventData.Lot> lots, boolean darker) { this.lots = lots; this.darker = darker; }
    }

    private final MayorsModule mod;
    private Session session;
    private long lastStartHour = -1;

    DarkAuction(MayorsModule mod) { this.mod = mod; }

    Session session() { return session; }

    /** Every second. */
    void tick() {
        long hour = System.currentTimeMillis() / 3_600_000L;
        if (session == null && Cal.active(Cal.DARK_AUCTION) && lastStartHour != hour) start();
        if (session == null) return;
        long now = System.currentTimeMillis();
        if (now < session.phaseEnds) return;
        switch (session.phase) {
            case LOBBY -> {
                if (session.joined.isEmpty() || session.lots.isEmpty()) { session = null; return; }
                nextLot();
            }
            case PREVIEW -> {
                session.phase = Phase.BIDDING;
                session.phaseEnds = now + mod.dials().daBidSeconds.get() * 1000L;
                tell("&eBidding is open! &7(" + mod.dials().daBidSeconds.get() + "s, every bid resets the timer)");
            }
            case BIDDING -> closeLot();
        }
    }

    /** Begin an auction now (the calendar at :55, or an admin). */
    String start() {
        if (session != null) return "an auction is already running";
        lastStartHour = System.currentTimeMillis() / 3_600_000L;
        boolean darker = mod.election().perkActive("scorpius.darker_auctions");
        List<EventData.Lot> lots = rollLots(darker);
        if (lots.isEmpty()) return "no lot in the Dark Auction table exists on this server";
        session = new Session(lots, darker);
        session.phaseEnds = System.currentTimeMillis() + mod.dials().daLobbySeconds.get() * 1000L;
        // The invitation goes only to players who could walk in right now: a profile that is ready and a purse that
        // already passes the door (canEnter, the same rule join() enforces). Telling a 0-coin player every hour to
        // "bring 400,000 coins" is an invitation they cannot act on - chat noise (playtest wave 3). Anyone else can
        // still find the auction on the calendar, at Ashgrave, or with /darkauction.
        double need = mod.dials().daEntryPurse.get();
        String how = mod.dials().daRemoteJoin.get() ? "use &a/darkauction" : "speak to &5Ashgrave &7in the Hub";
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Sky.profiles().isReady(p) || !canEnter(Sky.economy().purse(p), need)) continue;
            if (!EventAlerts.news(p, Cal.DARK_AUCTION)) continue;          // calendar alerts off

            Msg.send(p, (darker ? "&5&lSHADOW AUCTION! " : "&5&lDARK AUCTION! ") + "&7The doors are open for &e" + mod.dials().daLobbySeconds.get()
                    + "s&7 - " + how + "&7 to enter.");
            Msg.sound(p, Sound.BLOCK_BELL_USE, 0.6f);
        }
        return null;
    }

    /** The door rule, shared by the invitation and {@link #join}: enough coins in the purse. */
    static boolean canEnter(double purse, double need) { return AlertPolicy.canEnter(purse, need); }

    private List<EventData.Lot> rollLots(boolean darker) {
        EventData.DarkAuction da = mod.data().darkAuction;
        int rounds = darker ? Math.max(da.rounds, (int) Math.max(da.darkerRounds, mod.election().perkValue("scorpius.darker_auctions"))) : da.rounds;
        List<EventData.Lot> pool = available(da.lots);
        List<EventData.Lot> books = available(da.bookRound);
        List<EventData.Lot> dark = available(da.darkerLots);
        List<EventData.Lot> fin = available(da.darkerFinal);
        List<EventData.Lot> out = new ArrayList<>();
        int general = Math.max(0, Math.min(4, rounds - 1));
        for (int i = 0; i < general; i++) {
            EventData.Lot l = pick(pool);
            if (l != null) { out.add(l); pool.remove(l); }
        }
        EventData.Lot book = pick(books);
        if (book != null) out.add(book);
        for (int r = out.size(); darker && r < rounds; r++) {
            List<EventData.Lot> src = r == rounds - 1 && !fin.isEmpty() ? fin : dark;
            EventData.Lot l = pick(src);
            if (l == null) break;
            out.add(l);
            src.remove(l);
        }
        return out;
    }

    private static List<EventData.Lot> available(List<EventData.Lot> in) {
        List<EventData.Lot> out = new ArrayList<>();
        for (EventData.Lot l : in) if (Goods.resolve(l.item) != null) out.add(l);
        return out;
    }

    private static EventData.Lot pick(List<EventData.Lot> pool) {
        int total = 0;
        for (EventData.Lot l : pool) total += Math.max(1, l.weight);
        if (total == 0) return null;
        int r = ThreadLocalRandom.current().nextInt(total);
        for (EventData.Lot l : pool) { r -= Math.max(1, l.weight); if (r < 0) return l; }
        return pool.isEmpty() ? null : pool.get(0);
    }

    double minBid(EventData.Lot l) { return Math.max(1, Math.round(l.minBid * Math.max(0.0001, mod.dials().daMinBidScale.get()))); }

    /**
     * The next bid a button offers: the minimum (x multiplier), then +10% (or x multiplier) of the top bid,
     * rounded up to a whole thousand. The button LABEL and the CLICK both read this, and the click carries
     * the amount it showed so a bid that moved in between is refused instead of charged (see {@link #bid}).
     */
    double nextBid(double multiplier) {
        if (session == null || session.round >= session.lots.size()) return 0;
        EventData.Lot l = session.lots.get(session.round);
        double raw = session.top == null ? minBid(l) * Math.max(1, multiplier) : session.topBid * Math.max(1.1, multiplier);
        return raw >= 1000 ? Math.ceil(raw / 1000.0) * 1000.0 : Math.ceil(raw);
    }

    private void nextLot() {
        session.phase = Phase.PREVIEW;
        session.top = null;
        session.topName = null;
        session.topBid = 0;
        session.phaseEnds = System.currentTimeMillis() + mod.data().darkAuction.previewSeconds * 1000L;
        EventData.Lot l = session.lots.get(session.round);
        tell("&5Round " + (session.round + 1) + "/" + session.lots.size() + ": " + Goods.displayName(Goods.resolve(l.item))
                + " &7- starting bid &6" + Text.commasInt(minBid(l)));
    }

    private void closeLot() {
        EventData.Lot l = session.lots.get(session.round);
        String id = Goods.resolve(l.item);
        if (session.top != null && id != null) {
            JsonObject extra = new JsonObject();
            extra.addProperty("winning_bid", session.topBid);
            Claims.give(session.top, id, 1, extra, "dark auction");
            Player w = Bukkit.getPlayer(session.top);
            if (w != null && Sky.profiles().isReady(w)) {
                PlayerData pd = mod.data(w);
                if (pd != null) { pd.auctionsWon++; pd.auctionCoinsSpent += session.topBid; }
                Sky.profiles().saveNow(w);
            }
            tell("&a" + session.topName + " &7won " + Goods.displayName(id) + " &7for &6" + Text.commasInt(session.topBid) + " coins&7!");
            // The winner hears about their own prize wherever they are: it is their coins and their item, not round news.
            if (w != null && !present(w)) Msg.send(w, "&8[&5Auction&8] &aYou won " + Goods.displayName(id) + " &7for &6" + Text.commasInt(session.topBid) + " coins&7!");
            Sky.log().info("[mayors] dark auction: " + session.topName + " won " + id + " for " + session.topBid);
        } else {
            tell("&7No bids. " + Goods.displayName(id) + " &7is withdrawn.");
        }
        session.top = null;
        session.round++;
        if (session.round >= session.lots.size()) {
            tell("&5The Dark Auction is over. &7Until next hour...");
            session = null;
            return;
        }
        nextLot();
    }

    /**
     * Round news ("Round 3/5", "Bidding is open", "No bids ... withdrawn", "The Dark Auction is over") goes only to
     * joined players who are still IN THE ROOM. Playtest endgame: a player who left after round 1 and warped across
     * 15 islands kept getting every later round. Joining is kept (so /darkauction takes them straight back in, no
     * second purse check) - only the chat follows presence. Lines about a player's own coins (outbid refund, a won
     * lot) are sent to that player directly and are not round news.
     */
    private void tell(String msg) {
        if (session == null) return;
        for (UUID u : session.joined) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && present(p)) { Msg.send(p, "&8[&5Auction&8] " + msg); Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_HAT, 1f); }
        }
    }

    static final String TITLE = "Dark Auction";

    /** In the room = the Dark Auction menu is open, or standing in the Hub's Dark Auction hall. */
    boolean present(Player p) {
        Menu m = null;
        try { m = Sky.menus().current(p); } catch (Throwable ignored) { }
        return inRoom(m != null && TITLE.equals(m.title()), inHall(p));
    }

    /** The presence rule, pure: the menu (remote join) or the hall (walked in). Neither = stepped out. */
    static boolean inRoom(boolean viewingMenu, boolean inHall) { return viewingMenu || inHall; }

    private static boolean inHall(Player p) {
        try {
            if (!"hub".equals(Sky.worlds().islandOf(p.getWorld()))) return false;
            Box hall = Sky.worlds().area("hub", "dark_auction");
            if (hall == null) return false;
            Location l = p.getLocation();
            return hall.grow(2).contains(l.getX(), l.getY(), l.getZ());
        } catch (Throwable t) {
            return false;
        }
    }

    /** The auction menu was closed: if that took the player out of the room, say once that round news stops. */
    private void steppedOut(Player p) {
        Session s = session;
        if (s == null || !s.joined.contains(p.getUniqueId())) return;
        // next tick: closing to open another menu, or walking in the hall, is not leaving
        Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            if (session != s || !p.isOnline() || present(p) || !s.toldAway.add(p.getUniqueId())) return;
            Msg.send(p, "&8[&5Auction&8] &7You stepped out of the Dark Auction - round news stops here."
                    + (p.getUniqueId().equals(s.top) ? " &7Your top bid still stands." : "")
                    + " &7Back in: &a/darkauction");
        });
    }

    // ---- players --------------------------------------------------------------------------------

    /** The auctioneer was clicked / /darkauction typed. */
    void join(Player p, boolean fromNpc) {
        if (session == null) {
            Msg.npc(p, "&5Ashgrave", List.of("&7The doors open at &e:55 &7every hour. Come back with deep pockets."), null);
            open(p);
            return;
        }
        if (!fromNpc && !mod.dials().daRemoteJoin.get() && !session.joined.contains(p.getUniqueId())) {
            Msg.error(p, "Speak to the auctioneer in the Hub to enter.");
            return;
        }
        if (!session.joined.contains(p.getUniqueId())) {
            double need = mod.dials().daEntryPurse.get();
            if (!canEnter(Sky.economy().purse(p), need)) { Msg.error(p, "You need at least " + Text.commasInt(need) + " coins in your purse to enter!"); return; }
            session.joined.add(p.getUniqueId());
            Msg.send(p, "&5You slip into the Dark Auction...");
        }
        open(p);
    }

    boolean bid(Player p, double multiplier) { return bid(p, multiplier, -1); }

    /**
     * Place a bid. {@code shown} is the amount on the button the player clicked (or -1 for "whatever the next
     * bid is"); if the price moved since the menu was drawn - someone else bid - nothing is charged and the
     * player is told the new price, because charging a number the player never saw is the bug this guards.
     */
    boolean bid(Player p, double multiplier, double shown) {
        if (session == null || session.phase != Phase.BIDDING) { Msg.error(p, "Bidding is not open."); return false; }
        if (!session.joined.contains(p.getUniqueId())) { Msg.error(p, "You are not in the auction."); return false; }
        long now = System.currentTimeMillis();
        Long last = session.lastBid.get(p.getUniqueId());
        if (last != null && now - last < 400) return false;
        session.lastBid.put(p.getUniqueId(), now);
        if (p.getUniqueId().equals(session.top)) { Msg.error(p, "You already hold the top bid."); return false; }
        double amount = nextBid(multiplier);
        if (amount <= 0 || amount > 1e14) return false;
        if (shown > 0 && Math.abs(shown - amount) >= 0.5) {
            Msg.error(p, "The bid moved! The next bid is now " + Text.commasInt(amount) + " coins - click again to bid that.");
            return false;
        }
        String id = Goods.resolve(session.lots.get(session.round).item);
        if (!Sky.economy().withdraw(p, amount, "dark auction: escrow bid on " + id)) { Msg.error(p, "You can't afford that bid!"); return false; }
        refundTop("outbid");
        session.top = p.getUniqueId();
        session.topName = p.getName();
        session.topBid = amount;
        session.phaseEnds = now + mod.dials().daBidSeconds.get() * 1000L;
        tell("&e" + p.getName() + " &7bid &6" + Text.commasInt(amount) + " coins&7!");
        Msg.success(p, "You bid " + Text.commasInt(amount) + " coins! &7(held until you are outbid)");
        Sky.profiles().saveNow(p);
        return true;
    }

    private void refundTop(String why) {
        if (session == null || session.top == null) return;
        UUID u = session.top;
        double amount = session.topBid;
        String id = Goods.resolve(session.lots.get(session.round).item);
        Player p = Bukkit.getPlayer(u);
        if (p != null && Sky.profiles().isReady(p)) {
            Sky.economy().deposit(p, amount, "dark auction: refund (" + why + ") on " + id);
            if ("outbid".equals(why)) Msg.send(p, "&cYou were outbid! &7Your &6" + Text.commasInt(amount) + " coins &7were returned.");
        } else {
            Sky.economy().depositOffline(u, amount, "dark auction: refund (" + why + ") on " + id);
        }
        session.top = null;
    }

    /** Server stopping: give back the coins held for the current lot. */
    void shutdown() {
        if (session != null && session.phase == Phase.BIDDING) refundTop("server stopping");
        session = null;
    }

    String adminSkip() {
        if (session == null) return "no auction running";
        session.phaseEnds = 0;
        if (session.phase == Phase.LOBBY && session.joined.isEmpty()) return "lobby has nobody in it: the auction will close";
        return null;
    }

    void open(Player p) {
        Menu m = new Menu(TITLE, 6).chrome();
        m.refreshEvery(10).drawer(menu -> draw(menu, p)).onClose(this::steppedOut);
        m.open(p);
    }

    private void draw(Menu m, Player p) {
        for (int s : new int[]{4, 13, 22, 29, 31, 33, 40, 47}) m.set(s, Icons.filler());
        Session s = session;
        if (s == null) {
            long ms = 0;
            try { ms = Sky.calendar().millisUntil(Cal.DARK_AUCTION); } catch (Throwable ignored) { }
            m.set(13, Icons.of(Material.ENDER_EYE, "&5Dark Auction",
                    "&7Rare lots, sold to the highest bidder.",
                    "&7Every hour at &e:55&7.",
                    "",
                    "&7Next auction: &e" + Text.duration(Math.max(0, ms) / 1000),
                    "&7Entry: &6" + Text.commasInt(mod.dials().daEntryPurse.get()) + " coins &7in your purse."));
            m.set(48, Icons.back("Festivals & Events"), c -> EventBoard.open(c.player()));
            return;
        }
        long left = Math.max(0, s.phaseEnds - System.currentTimeMillis()) / 1000;
        if (s.phase == Phase.LOBBY) {
            m.set(13, Icons.of(Material.CLOCK, "&5The auction is about to begin", "&7First lot in &e" + left + "s&7.", "",
                    "&7Bidders in the room: &a" + s.joined.size(), s.darker ? "&5Shadow lots tonight!" : ""));
            return;
        }
        EventData.Lot lot = s.lots.get(s.round);
        String id = Goods.resolve(lot.item);
        ItemStack icon = Goods.icon(id, 1);
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("&7Round: &e" + (s.round + 1) + "/" + s.lots.size());
        lore.add("&7Starting bid: &6" + Text.commasInt(minBid(lot)));
        lore.add(s.top == null ? "&7Top bid: &8none" : "&7Top bid: &6" + Text.commasInt(s.topBid) + " &7by &e" + s.topName);
        lore.add(s.phase == Phase.PREVIEW ? "&7Bidding opens in &e" + left + "s" : "&7Time left: &c" + left + "s");
        m.set(13, Icons.withLore(icon, lore));
        boolean mine = p.getUniqueId().equals(s.top);
        double[] mults = {1.0, 1.25, 2.0};
        String[] names = {"Bid", "Bid +25%", "Double It"};
        int[] slots = {29, 31, 33};
        for (int i = 0; i < 3; i++) {
            double amount = nextBid(mults[i]);
            double mult = mults[i];
            if (mine) {
                // Holding the top bid: the button must show the bid you PLACED, not the next one somebody else
                // would have to beat (the playtest read "Bid: 11,000,000" right after placing 10,000,000).
                m.set(slots[i], Icons.of(Material.LIME_TERRACOTTA, "&aYour bid: " + Text.commasInt(s.topBid) + " coins",
                        "&aYou hold the top bid.", "&7The next bid must be at least &6" + Text.commasInt(nextBid(1.0)) + " coins&7."));
                continue;
            }
            boolean can = s.phase == Phase.BIDDING && Sky.economy().purse(p) >= amount;
            Material mat = can ? Material.GOLD_BLOCK : Material.RED_TERRACOTTA;
            m.set(slots[i], Icons.of(mat, (can ? "&6" : "&c") + names[i] + ": " + Text.commasInt(amount) + " coins",
                    s.phase != Phase.BIDDING ? "&7Wait for bidding to open." : can ? "&eClick to bid!" : "&cYou can't afford this bid."),
                    c -> { bid(c.player(), mult, amount); c.menu().redraw(); });
        }
        m.set(40, Icons.of(Material.PLAYER_HEAD, "&7Bidders", "&7In the room: &a" + s.joined.size(), "&7Your purse: &6" + Text.commasInt(Sky.economy().purse(p))));
    }

    List<String> sidebar(Player p) {
        Session s = session;
        if (s == null || !s.joined.contains(p.getUniqueId())) return List.of();
        // Stepped out (not in the menu or the hall) and nothing riding on it: the section goes quiet with the chat,
        // so a festival or siege section on the island they warped to is not pushed off by an auction they left.
        if (!p.getUniqueId().equals(s.top) && !present(p)) return List.of();
        if (s.phase == Phase.LOBBY) return List.of("&5Dark Auction", " &7Starting soon");
        return List.of("&5Dark Auction", " &7Round &e" + (s.round + 1) + "/" + s.lots.size()
                + (p.getUniqueId().equals(s.top) ? " &a(top bid)" : ""));
    }
}
