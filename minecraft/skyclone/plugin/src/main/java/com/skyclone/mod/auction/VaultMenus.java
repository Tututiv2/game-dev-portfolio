package com.skyclone.mod.auction;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Vault Auction screens.
 * <pre>
 *   Vault Auction   4 about · main-track lots on row 2 · Special lots on row 4 · 48 Go Back · 49 Close · 50 Claim
 *   Vault Lot       13 the item · 29 Submit Bid · 31 Standings · 33 Bid Amount · 48 Go Back · 49 Close
 * </pre>
 */
final class VaultMenus {

    private final VaultAuction vault;
    private final AuctionHouse house;
    private final AuctionMenus menus;
    /** viewer -> lot id -> custom amount typed. */
    private final Map<UUID, Map<String, Double>> custom = new HashMap<>();

    VaultMenus(VaultAuction vault, AuctionHouse house, AuctionMenus menus) {
        this.vault = vault;
        this.house = house;
        this.menus = menus;
    }

    void forget(UUID uuid) { custom.remove(uuid); }

    private static String coins(double v) { return "&6" + AuctionRules.coinsWords(v) + " coins"; }

    /** The icon for the Auction House main menu. */
    ItemStack entryIcon(Player p) {
        List<VaultAuction.Lot> cur = vault.current(p);
        int wins = vault.winnings(p).size();
        List<String> l = new ArrayList<>();
        l.add("&7Once a year, rare items are sold here");
        l.add("&7in small numbers. The highest bidders");
        l.add("&7when the year ends take them home.");
        l.add("");
        if (!house.dials.vaultEnabled.get()) l.add("&cClosed this year.");
        else if (cur.isEmpty()) l.add("&7No lots are open right now.");
        else {
            l.add("&7Lots open: &e" + cur.size());
            l.add("&7Closes in: &e" + AuctionRules.timeLeft(vault.millisLeft()));
        }
        if (wins > 0) l.add("&aWins to collect: " + wins);
        l.add("");
        l.add("&eClick to open!");
        ItemStack it = Icons.of(Material.ENDER_CHEST, "&5Vault Auction", l);
        return wins > 0 ? Icons.amount(Icons.glow(it), wins) : it;
    }

    Menu main(Player p) {
        Menu m = new Menu("Vault Auction", 6);
        m.drawer(menu -> draw(p, menu));
        m.refreshEvery(20);
        return m;
    }

    private void draw(Player p, Menu m) {
        m.chrome();
        int year = VaultAuction.currentYear();
        List<VaultAuction.Lot> cur = vault.current(p);
        List<VaultAuction.Lot> main = new ArrayList<>(), special = new ArrayList<>();
        for (VaultAuction.Lot l : cur) (VaultRules.specialTrack(l.track) ? special : main).add(l);
        int vol = house.dials.vaultVolume();

        List<String> info = new ArrayList<>();
        info.add("&7Year &e" + year + "&7's auction closes in &e" + AuctionRules.timeLeft(vault.millisLeft()) + "&7.");
        info.add("");
        info.add("&7Each lot has a few copies. The highest");
        info.add("&7bids hold them; a bid that pushes you out");
        info.add("&7returns your coins at once.");
        info.add("&7You may raise your own bid at any time.");
        info.add("");
        info.add("&7Main lots need: " + describe(vault.requirement(false)));
        info.add("&7Special lots need: " + describe(vault.requirement(true)));
        if (vault.ironman(p)) {
            info.add("");
            info.add("&7Ironman: your own lots, with half");
            info.add("&7the copies, bid on only by Ironmen.");
        }
        if (vol > 1) { info.add(""); info.add("&aThe mayor has multiplied every lot's stock x" + vol + "!"); }
        m.set(4, Icons.of(Material.BOOK, "&5Vault Auction", info));

        int[] mainSlots = VaultRules.centered(18, main.size());
        for (int i = 0; i < mainSlots.length; i++) placeLot(p, m, mainSlots[i], main.get(i));
        int[] specialSlots = VaultRules.centered(36, special.size());
        for (int i = 0; i < specialSlots.length; i++) placeLot(p, m, specialSlots[i], special.get(i));
        if (!special.isEmpty()) m.set(31, Icons.of(Material.PURPLE_STAINED_GLASS_PANE, "&dSpecial Lots", "&7A rotating pick each year."));
        if (cur.isEmpty()) {
            m.set(22, Icons.of(Material.BARRIER, "&cNo lots open", List.of(
                    house.dials.vaultEnabled.get() ? "&7The next Vault Auction opens with the new year." : "&7The Vault Auction is closed.")));
        }

        m.set(48, Icons.back("Auction House"), c -> {
            // an Ironman with the cosmetic AH switched off may still use the Vault: Go Back closes instead
            if (house.canUse(c.player(), false)) menus.main(c.player()).open(c.player());
            else c.player().closeInventory();
        });
        m.set(45, regaliaIcon(p), c -> {
            if (vault.regalia(c.player())) regalia(c.player()).open(c.player());
            else Msg.error(c.player(), "Only those carrying " + vault.regaliaName() + " &cmay enter the Regalia Room.");
        });
        int wins = vault.winnings(p).size();
        if (wins > 0) {
            m.set(50, Icons.glow(Icons.of(Material.CHEST, "&aCollect Winnings", List.of(
                    "&7You won &a" + wins + " &7lot" + (wins == 1 ? "" : "s") + ".", "", "&eClick to collect!"))), c -> {
                vault.claimAll(c.player());
                m.redraw();
            });
        }
    }

    // ---- the Regalia Room -----------------------------------------------------------------------------------

    /** Ledger slots: the four inner rows, seven columns each. */
    static final int[] LEDGER_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    ItemStack regaliaIcon(Player p) {
        boolean in = vault.regalia(p);
        List<String> l = new ArrayList<>();
        l.add("&7A quiet room beneath the Auction");
        l.add("&7House, where the Vaultkeeper keeps");
        l.add("&7the ledger of every past year.");
        l.add("");
        if (in) {
            l.add("&aYou carry " + vault.regaliaName() + "&a.");
            l.add("");
            l.add("&eClick to enter!");
        } else {
            l.add("&7Only those carrying " + vault.regaliaName());
            l.add("&7are let in.");
            l.add("");
            l.add("&cLocked");
        }
        return Icons.of(in ? Material.CHISELED_DEEPSLATE : Material.IRON_BARS, "&6Regalia Room", l);
    }

    Menu regalia(Player p) {
        Menu m = new Menu("Regalia Room", 6);
        m.drawer(menu -> drawRegalia(p, menu));
        m.refreshEvery(20);
        return m;
    }

    private void drawRegalia(Player p, Menu m) {
        m.chrome();
        m.set(48, Icons.back("Vault Auction"), c -> main(c.player()).open(c.player()));
        if (!vault.regalia(p)) {
            // the regalia left the inventory while the room was open: shown the door, not the ledger
            m.set(22, Icons.of(Material.IRON_BARS, "&cThe door is shut", List.of(
                    "&7Carry " + vault.regaliaName() + " &7to enter.")));
            return;
        }
        m.set(4, Icons.of(Material.WRITABLE_BOOK, "&6The Vaultkeeper's Ledger", List.of(
                "&7Every lot of every past Vault",
                "&7Auction: how many copies went,",
                "&7and what the winners paid.",
                "",
                "&8Newest year first.")));
        List<VaultAuction.Past> past = vault.ledger(LEDGER_SLOTS.length);
        if (past.isEmpty()) {
            m.set(22, Icons.of(Material.PAPER, "&7The ledger is empty", List.of(
                    "&7No Vault Auction has closed yet.", "&7The first entry is written when", "&7this year's auction ends.")));
        }
        for (int i = 0; i < past.size() && i < LEDGER_SLOTS.length; i++) m.set(LEDGER_SLOTS[i], ledgerIcon(past.get(i)));
        AuctionData d = house.data(p);
        m.set(50, Icons.of(Material.PLAYER_HEAD, "&aYour Vault Record", List.of(
                "&7Vault bids placed: &e" + Text.commasInt(d.vaultBids),
                "&7Vault lots won: &a" + Text.commasInt(d.vaultWon),
                "&7Wins to collect: &a" + vault.winnings(p).size())));
    }

    private ItemStack ledgerIcon(VaultAuction.Past x) {
        List<String> l = new ArrayList<>();
        l.add("&8&m                        ");
        l.add("&7Year &e" + x.year() + " &8- " + trackName(x.track()));
        l.add("&7Copies: &e" + x.stock());
        VaultRules.Result r = x.result();
        if (r.sold() == 0) {
            l.add("&7No bids: &cnone were sold&7.");
        } else {
            l.add("&7Sold: &a" + r.sold() + (r.unsold() > 0 ? " &8(" + r.unsold() + " unsold)" : ""));
            l.add("&7Lowest winning bid: " + coins(r.lowest()));
            l.add("&7Highest winning bid: " + coins(r.highest()));
            if (r.sold() > 2) l.add("&7Average: " + coins(r.mean()));
        }
        ItemStack base;
        try { base = ItemCodec.display(Sky.items().create(x.itemId(), 1)); }
        catch (Throwable t) { base = Icons.of(Material.BARRIER, "&c" + x.itemId()); }
        return Icons.withLore(base, l);
    }

    private static String describe(String req) {
        if (req == null || req.isBlank()) return "&anothing";
        try { return Sky.requirements().describe(req); } catch (Throwable t) { return "&7" + req; }
    }

    private void placeLot(Player p, Menu m, int slot, VaultAuction.Lot l) {
        m.set(slot, lotIcon(p, l, "&eClick to bid!"), c -> lot(c.player(), l.id).open(c.player()));
    }

    static String trackName(String track) {
        if (VaultAuction.SPECIAL.equals(track)) return "&dSpecial";
        if (VaultAuction.IRONMAN.equals(track)) return "&7Ironman";
        if (VaultAuction.IRONMAN_SPECIAL.equals(track)) return "&7Ironman &dSpecial";
        return "&5Vault";
    }

    List<String> lotLines(Player p, VaultAuction.Lot l) {
        List<String> out = new ArrayList<>();
        out.add("&8&m                        ");
        out.add("&7Lot: " + trackName(l.track) + " &8#" + l.id);
        out.add("&7Copies: &e" + l.stock);
        out.add("&7Minimum bid: " + coins(l.minBid));
        List<VaultAuction.VBid> ranked = VaultAuction.ranked(l);
        out.add("&7Bids holding a copy: &e" + ranked.size() + "&7/&e" + l.stock);
        if (ranked.size() >= l.stock) out.add("&7Lowest winning bid: " + coins(ranked.get(ranked.size() - 1).amount));
        VaultAuction.VBid own = VaultAuction.mine(l, p, VaultAuction.ACTIVE);
        out.add("");
        if (own != null) {
            out.add("&aYour bid: " + coins(own.amount));
            out.add("&aYou hold place #" + VaultRules.place(own.key(), VaultAuction.entries(l)) + "&a.");
        } else if (VaultAuction.mine(l, p, VaultAuction.OUTBID) != null) {
            out.add("&cYou were pushed out. Bid again!");
        }
        if (!l.closed) out.add("&7Closes in: &e" + AuctionRules.timeLeft(vault.millisLeft()));
        if (!vault.unlocked(p, l)) {
            out.add("");
            out.add("&cRequires " + describe(vault.requirement(l)) + "&c!");
        }
        return out;
    }

    private ItemStack lotIcon(Player p, VaultAuction.Lot l, String hint) {
        List<String> extra = lotLines(p, l);
        extra.add("");
        extra.add(vault.unlocked(p, l) ? hint : "&cLocked");
        return Icons.withLore(vault.display(l), extra);
    }

    Menu lot(Player p, String lotId) {
        Menu m = new Menu("Vault Lot", 6);
        m.drawer(menu -> drawLot(p, menu, lotId));
        m.refreshEvery(20);
        return m;
    }

    private void drawLot(Player p, Menu m, String lotId) {
        m.chrome();
        m.set(48, Icons.back("Vault Auction"), c -> main(c.player()).open(c.player()));
        VaultAuction.Lot l = vault.get(lotId);
        if (l == null || l.closed) {
            m.set(13, Icons.of(Material.BARRIER, "&cThis lot has closed", "&7Winners collect from the Vault Auction."));
            return;
        }
        m.set(13, lotIcon(p, l, "&8Lot #" + l.id));

        // standings
        List<String> st = new ArrayList<>();
        List<VaultAuction.VBid> ranked = VaultAuction.ranked(l);
        if (ranked.isEmpty()) st.add("&7No bids yet. Be the first!");
        for (int i = 0; i < ranked.size() && i < 15; i++) {
            VaultAuction.VBid b = ranked.get(i);
            st.add("&e#" + (i + 1) + " &b" + b.name + " &7- " + coins(b.amount));
        }
        m.set(31, Icons.of(Material.FILLED_MAP, "&fStandings", st));

        if (!vault.unlocked(p, l)) {
            m.set(29, Icons.of(Material.RED_TERRACOTTA, "&cLocked", List.of("&7Requires " + describe(vault.requirement(l)) + "&7.")));
            return;
        }
        if (!vault.forViewer(p, l)) {
            m.set(29, Icons.of(Material.RED_TERRACOTTA, vault.ironman(p) ? "&cClassic Lot" : "&cIronman Lot", List.of(
                    vault.ironman(p) ? "&7Ironman profiles bid on the" : "&7Only Ironman profiles may",
                    vault.ironman(p) ? "&7Ironman lots only." : "&7bid on this lot.")));
            return;
        }
        double min = vault.minFor(p, l);
        double bid = Math.max(min, custom.getOrDefault(p.getUniqueId(), Map.of()).getOrDefault(l.id, 0.0));
        VaultAuction.VBid own = VaultAuction.mine(l, p, VaultAuction.ACTIVE);
        double pay = own == null ? bid : bid - own.amount;
        double purse = Sky.economy().purse(p);
        List<String> bl = new ArrayList<>();
        bl.add(own == null ? "&7New bid: " + coins(bid) : "&7Raise your bid to: " + coins(bid));
        if (own != null) bl.add("&7You pay the difference: " + coins(pay));
        bl.add("");
        bl.add("&7If a higher bid pushes you out of the");
        bl.add("&7last copy, the coins come straight back.");
        bl.add("");
        bl.add(purse < pay ? "&cYou cannot afford this bid!" : "&eClick to bid!");
        m.set(29, Icons.of(Material.GOLD_NUGGET, own == null ? "&6Submit Bid" : "&6Raise Bid", bl), c -> {
            Player pl = c.player();
            List<String> desc = new ArrayList<>();
            desc.add("&7Lot: " + vault.itemName(l));
            desc.add("&7Your bid: " + coins(bid));
            desc.add("&7Coins taken now: " + coins(pay));
            desc.add("&7Purse after: &6" + AuctionRules.fmt(Sky.economy().purse(pl) - pay));
            Sky.menus().confirm(pl, "Confirm Vault Bid", desc, () -> {
                if (vault.bid(pl, l, bid)) {
                    Map<String, Double> cb = custom.get(pl.getUniqueId());
                    if (cb != null) cb.remove(l.id);
                }
                lot(pl, l.id).open(pl);
            }, () -> lot(pl, l.id).open(pl));
        });
        m.set(33, Icons.of(Material.GOLD_INGOT, "&6Bid Amount: &e" + AuctionRules.shortNum(bid), List.of(
                "&7You need to bid at least " + coins(min) + "&7.",
                "",
                "&7Bid more to keep your copy safe.",
                "",
                "&eClick to set a custom amount!")), c -> Sky.menus().inputNumber(c.player(), "Vault bid (min " + AuctionRules.shortNum(min) + ")", n -> {
            if (n != null && n > 0) {
                custom.computeIfAbsent(c.player().getUniqueId(), k -> new HashMap<>()).put(l.id, Math.ceil(n));
                if (n < min) Msg.error(c.player(), "Your bid must be at least " + AuctionRules.fmt(min) + " coins!");
            }
            lot(c.player(), l.id).open(c.player());
        }));
        m.set(4, Icons.of(Material.CLOCK, "&aTime Left", List.of("&7Closes in &e" + AuctionRules.timeLeft(vault.millisLeft()) + "&7,",
                "&7when the SkyBlock year ends.", "", "&7Year " + l.year + " &8(" + Text.commasInt(l.stock) + " copies)")));
    }
}
