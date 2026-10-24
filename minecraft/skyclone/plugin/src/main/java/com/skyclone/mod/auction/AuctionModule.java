package com.skyclone.mod.auction;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Auction House (module id {@code auction}). Provides {@link Features.Auctions}.
 *
 * <p>Player: {@code /ah [player]}, {@code /ahs <search>}, {@code /ahprice}, {@code /ahview <id>} (chat links),
 * the Auction Master and two Agents at the hub, and the SkyBlock Menu (via Features.Auctions.open).
 * Admin: {@code /sb auction ...} (see {@link AuctionAdmin}).
 */
public final class AuctionModule implements GameModule, Listener {

    private AuctionDials dials;
    private AuctionStore store;
    private PriceGuide guide;
    private AuctionHouse house;
    private AuctionMenus menus;
    private NpcMarket npc;
    private VaultAuction vault;
    private VaultMenus vaultMenus;
    private int restockClock, buyerClock;
    private boolean firstStock = true;

    @Override public String id() { return "auction"; }

    @Override
    public void enable() {
        dials = new AuctionDials();
        guide = new PriceGuide();
        guide.load();
        store = new AuctionStore();
        store.migrate();
        store.load(guide, Math.max(1, dials.historyDays.get()));
        house = new AuctionHouse(store, guide, dials);
        menus = new AuctionMenus(house);
        house.menus = menus;
        npc = new NpcMarket(house);
        vault = new VaultAuction(house);
        vault.migrate();
        vault.load();
        vaultMenus = new VaultMenus(vault, house, menus);
        menus.vaultMenus = vaultMenus;
        Sky.provide(Features.Auctions.class, house);

        registerNpcs();
        registerCommands();
        // escrowed uniques join core's dupe scan (wave 5): a crash-rollback copy is logged + reported to admins
        try { Sky.items().addDupeScanSource("auction", house::escrowedStacks); } catch (Throwable ignored) { }
        // "regalia" (or "regalia:<ITEM>"): carrying the Vault's Regalia - for the hub's secret room zone
        Sky.requirements().register("regalia", new com.skyclone.api.util.Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                return parts.length > 1 && !parts[1].isBlank() ? VaultAuction.carries(p, parts[1]) : vault.regalia(p);
            }
            @Override public String describe(String[] parts) {
                return (parts.length > 1 && !parts[1].isBlank() ? VaultAuction.nameOf(parts[1]) : vault.regaliaName()) + " &7(carried)";
            }
        });
        new AuctionAdmin(house, menus, npc, vault, vaultMenus).register();
        AuctionSelfTests.register(house, npc, vault);
        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());
        // tab list (not the crowded sidebar): only when there is something to say
        Sky.sidebar().tabWidget("auction", 260, this::tabLines);

        Sky.log().info("[auction] loaded " + store.all().size() + " open auctions, " + store.pendingCount()
                + " create-slot items, " + guide.refs().size() + " reference prices");
    }

    @Override
    public void postEnable() {
        // the clock: end auctions every second; Agents restock and buy on their own dial-driven periods
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 40L, 20L);
        // the Vault keeper stands below the Auction House only where the hub builder made a spot for them
        // (registering an NPC whose anchor is missing parks it at spawn and fails ui.npc-anchors)
        try {
            var layout = Sky.worlds().layout("hub");
            if (layout != null && layout.anchor("npc.auction_vault") != null) {
                NpcDef def = NpcDef.of("auction_vault", "&5Vaultkeeper Ossery", "hub", "npc.auction_vault").title("&5&lVAULT AUCTION");
                def.hand = "ENDER_EYE";
                Sky.npcs().register(def, p -> {
                    if (vault.canUse(p, true)) vaultMenus.main(p).open(p);
                });
            }
            // the Regalia Room's keeper: only where the hub built the room (request: anchor npc.regalia_room,
            // zone regalia_room with requirement "regalia" - core then keeps everyone else out)
            if (layout != null && layout.anchor("npc.regalia_room") != null) {
                NpcDef def = NpcDef.of("regalia_room", "&6Ledgerwarden Verity", "hub", "npc.regalia_room").title("&6&lREGALIA ROOM");
                def.hand = "WRITABLE_BOOK";
                Sky.npcs().register(def, p -> {
                    if (!vault.regalia(p)) {
                        Msg.error(p, "Only those carrying " + vault.regaliaName() + " &cmay read the ledger.");
                        return;
                    }
                    vaultMenus.regalia(p).open(p);
                });
            }
        } catch (Throwable t) {
            Sky.log().log(java.util.logging.Level.WARNING, "[auction] vault keeper not placed", t);
        }
    }

    @Override
    public void disable() {
        // every change is already queued on the database thread; nothing is held only in memory
        // except the browser state and custom bid drafts, which are conveniences.
    }

    private void tick() {
        try {
            house.sweep();
        } catch (Throwable t) {
            Sky.log().log(java.util.logging.Level.WARNING, "[auction] sweep failed", t);
        }
        try {
            vault.tick();
        } catch (Throwable t) {
            Sky.log().log(java.util.logging.Level.WARNING, "[auction] vault tick failed", t);
        }
        try {
            if (++restockClock >= Math.max(10, dials.npcRestockSeconds.get()) || (firstStock && restockClock >= 10)) {
                restockClock = 0;
                if (firstStock) {
                    firstStock = false;
                    npc.restock(Math.max(0, dials.npcListings.get()), true);
                } else {
                    npc.restock(10, false);
                }
            }
            if (++buyerClock >= Math.max(10, dials.npcBuyerSeconds.get())) {
                buyerClock = 0;
                npc.buyerPass(false);
            }
        } catch (Throwable t) {
            Sky.log().log(java.util.logging.Level.WARNING, "[auction] agent pass failed", t);
        }
    }

    // ---- NPCs ----------------------------------------------------------------------------------

    private void registerNpcs() {
        JsonElement e = PriceGuide.read("agents.json");
        JsonObject a = e instanceof JsonObject o ? o : new JsonObject();
        registerNpc(a, "master", "auction_master", "npc.auction_master", "&6Auction Master", "&e&lAUCTION HOUSE",
                p -> menus.main(p).open(p));
        registerNpc(a, "agent_1", "auction_agent_1", "npc.auction_agent_1", "&eAuction Agent", "&e&lBROWSE",
                p -> menus.browse(p).open(p));
        registerNpc(a, "agent_2", "auction_agent_2", "npc.auction_agent_2", "&eAuction Agent", "&e&lYOUR AUCTIONS",
                p -> (house.mine(p).isEmpty() ? menus.create(p) : menus.manage(p)).open(p));
    }

    private void registerNpc(JsonObject agents, String key, String npcId, String anchor, String defName, String defTitle,
                             java.util.function.Consumer<Player> then) {
        JsonObject o = agents.get(key) instanceof JsonObject j ? j : new JsonObject();
        String name = o.has("name") ? o.get("name").getAsString() : defName;
        String title = o.has("title") ? o.get("title").getAsString() : defTitle;
        List<String> lines = new ArrayList<>();
        if (o.has("lines")) for (JsonElement l : o.getAsJsonArray("lines")) lines.add(l.getAsString());
        NpcDef def = NpcDef.of(npcId, name, "hub", anchor).title(title);
        def.hand = "GOLD_INGOT";
        Sky.npcs().register(def, p -> {
            if (!house.canUse(p, true)) return;
            AuctionData d = house.data(p);
            if (!lines.isEmpty() && !d.greeted.contains(npcId)) {
                d.greeted.add(npcId);                                         // the pitch plays once per NPC
                Sky.npcs().talk(p, npcId, lines, () -> then.accept(p));
            } else {
                then.accept(p);
            }
        });
    }

    // ---- commands ---------------------------------------------------------------------------------

    private void registerCommands() {
        Sky.commands().register(SkyCommand.of("ah", "Open the Auction House (or /ah <player> for their auctions)")
                .alias("auction", "auctionhouse", "auctions").category("Economy").usage("[player]").playerOnly()
                .run((s, a) -> {
                    Player p = (Player) s;
                    if (!house.canUse(p, true)) return;
                    if (!house.reachable(p, true)) return;
                    if (a.length >= 1 && !a[0].isBlank()) {
                        String name = a[0].replaceAll("[^A-Za-z0-9_]", "");
                        if (name.isEmpty()) { menus.main(p).open(p); return; }
                        menus.openSeller(p, name);
                        return;
                    }
                    menus.main(p).open(p);
                })
                .tab((s, a) -> {
                    List<String> out = new ArrayList<>();
                    if (a.length == 1) for (Player o : Bukkit.getOnlinePlayers())
                        if (o.getName().toLowerCase(Locale.ROOT).startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(o.getName());
                    return out;
                }));

        Sky.commands().register(SkyCommand.of("ahs", "Search the Auction House")
                .alias("ahsearch").category("Economy").usage("<search>").playerOnly()
                .run((s, a) -> {
                    Player p = (Player) s;
                    if (!house.canUse(p, true)) return;
                    if (!house.reachable(p, true)) return;
                    String q = String.join(" ", a).trim();
                    if (q.isEmpty()) { menus.browse(p).open(p); return; }
                    menus.openSearch(p, Text.strip(q));
                }));

        Sky.commands().register(SkyCommand.of("ahview", "Open one auction (used by Auction House chat links)")
                .category("Economy").usage("<auction id>").playerOnly()
                .run((s, a) -> {
                    Player p = (Player) s;
                    if (!house.canUse(p, true) || !house.reachable(p, true)) return;
                    if (a.length < 1) { Msg.error(p, "Usage: /ahview <auction id>"); return; }
                    String id = a[0].replaceAll("[^0-9a-fA-F]", "");
                    Listing l = store.get(id);
                    if (l == null) { Msg.error(p, "That auction is over and has been collected."); return; }
                    menus.view(p, l.id, () -> menus.main(p)).open(p);
                }));

        Sky.commands().register(SkyCommand.of("vaultauction", "Open the yearly Vault Auction (rare items in limited stock)")
                .alias("vah").category("Economy").playerOnly()
                .run((s, a) -> {
                    Player p = (Player) s;
                    if (!vault.canUse(p, true) || !house.reachable(p, true)) return;
                    vaultMenus.main(p).open(p);
                }));

        Sky.commands().register(SkyCommand.of("regaliaroom", "Enter the Regalia Room beneath the Auction House (carry the Regalia)")
                .alias("regalia").category("Economy").playerOnly()
                .run((s, a) -> {
                    Player p = (Player) s;
                    if (!vault.canUse(p, true) || !house.reachable(p, true)) return;
                    if (!vault.regalia(p)) {
                        Msg.error(p, "Only those carrying " + vault.regaliaName() + " &cmay enter the Regalia Room.");
                        return;
                    }
                    vaultMenus.regalia(p).open(p);
                }));

        Sky.commands().register(SkyCommand.of("ahprice", "What is the item in your hand worth on the Auction House?")
                .alias("pricecheck").category("Economy").playerOnly()
                .run((s, a) -> {
                    Player p = (Player) s;
                    ItemStack hand = p.getInventory().getItemInMainHand();
                    String id = Sky.items().idOf(hand);
                    if (id == null) { Msg.error(p, "Hold an item to price-check it."); return; }
                    PriceGuide.Guide g = guide.guide(id, house.lowestBin(id));
                    List<String> out = new ArrayList<>();
                    out.add("&6[Auction] &ePrice check: " + ItemCodec.name(hand, id) + (hand.getAmount() > 1 ? " &8x" + hand.getAmount() : ""));
                    out.add(" &7Lowest BIN: " + (g.lowestBin() > 0 ? "&6" + AuctionRules.coinsWords(g.lowestBin()) : "&8none listed"));
                    out.add(" &7Recent sales: " + (g.serverSales() > 0 ? "&6" + AuctionRules.coinsWords(g.serverMedian()) + " &8(" + g.serverSales() + ")" : "&8none yet"));
                    out.add(" &7Market reference: " + (g.reference() > 0 ? "&6" + AuctionRules.coinsWords(g.reference()) : "&8unknown"));
                    out.add(" &7Crafting cost: " + (g.crafted() > 0 ? "&6" + AuctionRules.coinsWords(g.crafted()) : "&8not craftable"));
                    if (g.suggested() > 0) out.add(" &7Suggested: &6" + AuctionRules.coinsWords(g.suggested()) + " &8each (" + g.source() + ")");
                    String why = AuctionHouse.auctionable(hand);
                    if (why != null) out.add(" &c" + why);
                    Msg.send(p, out);
                }));
    }

    private List<String> tabLines(Player p) {
        if (!Sky.profiles().isReady(p)) return List.of();
        int[] s = house.summary(Sky.profiles().profileId(p));
        int selling = s[0], bidding = s[1], collect = s[2];
        if (selling + bidding + collect == 0) return List.of();
        List<String> out = new ArrayList<>();
        out.add("&6&lAuctions:");
        if (selling > 0) out.add(" Selling: &e" + selling);
        if (bidding > 0) out.add(" Bidding: &e" + bidding);
        if (collect > 0) out.add(" To collect: &a" + collect);
        return out;
    }

    // ---- events ----------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onReady(SkyEvents.ProfileReady e) {
        Player p = e.player;
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            if (!p.isOnline()) return;
            house.loginNotice(p);
            vault.loginNotice(p);
        }, 60L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        menus.forget(e.getPlayer().getUniqueId());
        vaultMenus.forget(e.getPlayer().getUniqueId());
        house.forget(e.getPlayer().getUniqueId());
    }
}
