package com.skyclone.mod.bank;

import com.google.gson.JsonElement;
import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The genre's secure trade window ({@code /trade <player>}), research 05 section 1.8 + 11.2/11.3:
 * <ul>
 *   <li>16 item slots and a coin offer per side; offered items leave the inventory into escrow
 *       (memory + a {@code bank_pending} row) so they cannot be used, dropped or duplicated meanwhile.</li>
 *   <li>Both sides press Accept; ANY change un-accepts both and locks Accept for 3 seconds, so nothing
 *       can be swapped in at the last moment.</li>
 *   <li>The commit re-validates everything (both online and close, purses, daily coin caps, inventory
 *       space), takes the coins, then moves every escrow row to its new owner and writes the log in ONE
 *       database transaction. Deliveries leave the table before they reach an inventory. No partial trades.</li>
 *   <li>Cancelled by closing the window, moving apart, teleporting, taking damage, dying or quitting.
 *       Requests expire. Anti-scam display: the real item id and name, and coins in full digits.</li>
 * </ul>
 */
final class Trades implements Listener {

    private static final Map<UUID, TradeSession> SESSIONS = new ConcurrentHashMap<>();
    /** target -> (requester -> expiry millis) */
    private static final Map<UUID, Map<UUID, Long>> REQUESTS = new HashMap<>();
    private static final Map<UUID, Long> LAST_REQUEST = new HashMap<>();

    static TradeSession session(Player p) { return SESSIONS.get(p.getUniqueId()); }

    static Collection<TradeSession> sessions() { return new HashSet<>(SESSIONS.values()); }

    static int maxSlots() { return Math.max(1, Math.min(16, Bank.tradeSlots.get())); }

    // ---- requests ------------------------------------------------------------------------------------

    /** /trade <name> from {@code from}. */
    static void request(Player from, Player to) {
        if (!Bank.tradeEnabled.get()) { Msg.error(from, "Trading is disabled."); return; }
        if (!Bank.ready(from)) return;
        if (!Bank.accountReady(from) || !Bank.accountReady(to)) { Msg.error(from, "Still loading, try again in a moment."); return; }
        String why = cannotTrade(from, to, true);
        if (why != null) { Msg.error(from, why); return; }
        long now = System.currentTimeMillis();
        Map<UUID, Long> mine = REQUESTS.get(from.getUniqueId());
        Long theirs = mine == null ? null : mine.get(to.getUniqueId());
        if (theirs != null && theirs > now) {
            mine.remove(to.getUniqueId());
            start(to, from);
            return;
        }
        Long last = LAST_REQUEST.get(from.getUniqueId());
        if (last != null && now - last < Bank.tradeRequestCooldown.get() * 1000L) {
            Msg.error(from, "Please wait before sending another trade request.");
            return;
        }
        LAST_REQUEST.put(from.getUniqueId(), now);
        Map<UUID, Long> pending = REQUESTS.computeIfAbsent(to.getUniqueId(), k -> new HashMap<>());
        if (pending.containsKey(from.getUniqueId()) && pending.get(from.getUniqueId()) > now) {
            Msg.error(from, "You already sent " + to.getName() + " a trade request.");
            return;
        }
        pending.put(from.getUniqueId(), now + Math.max(5, Bank.tradeRequestSeconds.get()) * 1000L);
        Msg.send(from, "&aYou sent a trade request to &b" + to.getName() + "&a.");
        Component click = Text.c("&b" + from.getName() + " &awants to trade with you! ")
                .append(Text.c("&e&l[CLICK TO TRADE]")
                        .clickEvent(ClickEvent.runCommand("/trade " + from.getName()))
                        .hoverEvent(HoverEvent.showText(Text.c("&eOpen a trade with " + from.getName()))));
        to.sendMessage(click);
        Msg.sound(to, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.6f);
    }

    /** Why these two cannot trade right now, or null. {@code checkDistance} false for admin-forced trades. */
    static String cannotTrade(Player from, Player to, boolean checkDistance) {
        if (to == null || !to.isOnline()) return "That player is not online.";
        if (to.equals(from)) return "You cannot trade with yourself.";
        if (!Bank.ready(to)) return to.getName() + " is still loading in.";
        String req = Bank.tradeRequirement.get();
        if (req != null && !req.isBlank() && !Sky.requirements().check(from, req.trim()))
            return "Trading requires " + Text.strip(Sky.requirements().describe(req.trim())) + ".";
        if (session(from) != null) return "You are already trading.";
        if (session(to) != null) return to.getName() + " is already trading with someone.";
        if (checkDistance) {
            if (!from.getWorld().equals(to.getWorld())) return to.getName() + " is too far away to trade.";
            double max = Math.max(1, Bank.tradeMaxDistance.get());
            if (from.getLocation().distanceSquared(to.getLocation()) > max * max) return to.getName() + " is too far away to trade (max " + BankMath.commas(max) + " blocks).";
        }
        if (Sky.service(Features.Dungeons.class).inDungeon(from) || Sky.service(Features.Dungeons.class).inDungeon(to))
            return "You cannot trade inside a dungeon.";
        String pa = Sky.profiles().profileId(from), pb = Sky.profiles().profileId(to);
        boolean sameProfile = pa != null && pa.equals(pb);
        if (!sameProfile) {
            if (!"normal".equalsIgnoreCase(orNormal(Sky.profiles().mode(from)))) return "Your profile type cannot trade outside its co-op.";
            if (!"normal".equalsIgnoreCase(orNormal(Sky.profiles().mode(to)))) return to.getName() + "'s profile type cannot trade outside its co-op.";
        }
        return null;
    }

    private static String orNormal(String mode) { return mode == null || mode.isBlank() ? "normal" : mode; }

    // ---- session lifecycle ---------------------------------------------------------------------------

    static TradeSession start(Player a, Player b) {
        TradeSession s = new TradeSession(a, b);
        SESSIONS.put(a.getUniqueId(), s);
        SESSIONS.put(b.getUniqueId(), s);
        Map<UUID, Long> ra = REQUESTS.get(a.getUniqueId());
        if (ra != null) ra.remove(b.getUniqueId());
        Map<UUID, Long> rb = REQUESTS.get(b.getUniqueId());
        if (rb != null) rb.remove(a.getUniqueId());
        openWindow(s, s.a);
        openWindow(s, s.b);
        try {
            Bank.store.tradesBetween(a.getUniqueId(), b.getUniqueId()).whenComplete((n, err) -> Bank.sync(() -> {
                s.pairTrades = err != null || n == null ? 0 : n;
                if (s.live()) redrawBoth(s);
            }));
        } catch (RuntimeException e) {
            s.pairTrades = 0;
        }
        Sky.log().info("[bank] trade " + s.id + " opened: " + a.getName() + " <-> " + b.getName());
        return s;
    }

    static void openWindow(TradeSession s, TradeSession.Side side) {
        Player p = Bukkit.getPlayer(side.uuid);
        if (p == null || s.closed) return;
        if (side.menu == null) side.menu = buildWindow(s, side);
        side.menu.open(p);
    }

    private static Menu buildWindow(TradeSession s, TradeSession.Side side) {
        TradeSession.Side other = s.other(side);
        Menu m = new Menu("You             " + other.name, 6);
        m.drawer(menu -> render(menu, s, side));
        m.refreshEvery(10);
        m.onPlayerInventoryClick((mc, live) -> offer(s, side, mc.player(), mc.slot(), live));
        m.onClose(pl -> onWindowClosed(s, side));
        return m;
    }

    private static void onWindowClosed(TradeSession s, TradeSession.Side side) {
        if (side.expectClose) { side.expectClose = false; return; }
        if (s.closed || s.committing) return;
        cancel(s, side.name + " closed the trade.", null);
    }

    static void redrawBoth(TradeSession s) {
        for (TradeSession.Side side : new TradeSession.Side[]{s.a, s.b}) if (side.menu != null) side.menu.redraw();
    }

    /** Something changed: nobody has accepted THIS version of the trade, and Accept is locked a moment. */
    static void changed(TradeSession s) {
        s.a.accepted = false;
        s.b.accepted = false;
        s.a.confirmUntil = 0;
        s.b.confirmUntil = 0;
        s.lastChange = System.currentTimeMillis();
        redrawBoth(s);
    }

    // ---- offering ----------------------------------------------------------------------------------------

    private static void offer(TradeSession s, TradeSession.Side side, Player p, int invSlot, ItemStack live) {
        if (!s.live() || live == null || live.getType().isAir()) return;
        if (side.offers.size() >= maxSlots()) { Msg.error(p, "Your side of the trade is full."); return; }
        Player partner = Bukkit.getPlayer(s.other(side).uuid);
        String why = untradeable(p, partner, live);
        if (why != null) { Msg.error(p, why); return; }
        ItemStack inSlot = p.getInventory().getItem(invSlot);
        if (inSlot == null || !inSlot.equals(live)) return;   // it moved since the click
        ItemStack taken = inSlot.clone();
        p.getInventory().setItem(invSlot, null);
        byte[] bytes;
        try {
            bytes = taken.serializeAsBytes();
        } catch (Throwable t) {
            p.getInventory().setItem(invSlot, taken);
            Msg.error(p, "That item cannot be traded.");
            return;
        }
        try { p.saveData(); } catch (Throwable ignored) { }   // the inventory on disk no longer holds it
        String key = UUID.randomUUID().toString();
        side.offers.add(new TradeSession.Offer(key, taken));
        Bank.store.escrow(key, side.uuid, s.id, bytes);
        Msg.sound(p, Sound.UI_BUTTON_CLICK, 1.4f);
        changed(s);
    }

    /** Why a stack cannot be traded, or null. */
    static String untradeable(Player owner, Player partner, ItemStack st) {
        String id = Sky.items().idOf(st);
        if (id == null) return null;
        ItemDef def = Sky.items().def(id);
        if (def != null) {
            if (def.has("soulbound") || def.has("no_trade")) return "That item is soulbound and cannot be traded.";
            if (def.category == ItemCategory.SACK) return "Sacks cannot be traded.";
        }
        ItemData data = Sky.items().data(st);
        if (data != null && data.extra != null) {
            if (data.extra.has("soulbound")) return "That item is soulbound and cannot be traded.";
            for (String k : new String[]{"coop_soulbound", "museum_soulbound"}) {
                if (!data.extra.has(k)) continue;
                String pid = null;
                try { JsonElement e = data.extra.get(k); pid = e.isJsonPrimitive() ? e.getAsString() : null; } catch (RuntimeException ignored) { }
                String partnerProfile = partner == null ? null : Sky.profiles().profileId(partner);
                if (pid == null || partnerProfile == null || !pid.equals(partnerProfile))
                    return "That item is co-op soulbound: it can only be traded inside its co-op.";
            }
        }
        return null;
    }

    private static void retract(TradeSession s, TradeSession.Side side, Player p, int index) {
        if (!s.live() || index < 0 || index >= side.offers.size()) return;
        TradeSession.Offer o = side.offers.remove(index);
        giveBack(side.uuid, List.of(o));
        Msg.sound(p, Sound.UI_BUTTON_CLICK, 0.8f);
        changed(s);
    }

    /** Release escrow rows, THEN hand the stacks back (or re-file them as deliveries if the owner left). */
    private static void giveBack(UUID owner, List<TradeSession.Offer> offers) {
        if (offers.isEmpty()) return;
        List<String> keys = new ArrayList<>();
        for (TradeSession.Offer o : offers) keys.add(o.key);
        Bank.store.release(keys).whenComplete((gone, err) -> Bank.sync(() -> {
            if (err != null) {
                // the rows are still filed (escrow): they become deliveries at the next boot. Never give twice.
                Sky.log().warning("[bank] could not release trade escrow for " + owner + ": " + err.getMessage());
                return;
            }
            Player p = Bukkit.getPlayer(owner);
            List<BankStore.Pending> refile = new ArrayList<>();
            for (TradeSession.Offer o : offers) {
                // released, or never filed at all (its insert failed): either way this stack exists nowhere else
                if (p != null && Bank.ready(p)) Sky.loot().give(p, o.stack, LootSource.TRADE);
                else refile.add(new BankStore.Pending(o.key, owner, null, "item", o.stack.serializeAsBytes(), 0, owner.toString()));
            }
            if (!refile.isEmpty()) Bank.store.unclaim(refile);
            if (p != null) try { p.saveData(); } catch (Throwable ignored) { }
        }));
    }

    // ---- coins -------------------------------------------------------------------------------------------------

    static double capLeft(Player p) {
        int level = Sky.service(Features.SkyLevel.class).level(p);
        double cap = BankMath.tradeCap(level, Bank.tradeCapLow.get(), Bank.tradeCapMid.get(), Bank.tradeCapHigh.get(),
                Bank.tradeCapMidLevel.get(), Bank.tradeCapHighLevel.get());
        BankData.Member m = Bank.member(p);
        double sent = Bank.today().equals(m.tradeDay) ? m.tradeSentToday : 0;
        return Math.max(0, cap - sent);
    }

    private static void setCoins(TradeSession s, TradeSession.Side side, Player p, double amount) {
        if (!s.live()) return;
        double a = BankMath.floorTenth(Math.max(0, amount));
        if (a > Sky.economy().purse(p)) { Msg.error(p, "You don't have that many coins in your purse."); return; }
        double left = capLeft(p);
        if (a > left) { Msg.error(p, "That is over your daily trade limit. You can still send " + BankMath.coinWords(left) + " today."); return; }
        side.coins = a;
        changed(s);
    }

    private static Menu coinMenu(TradeSession s, TradeSession.Side side, Player p) {
        Menu m = new Menu("Coin Transaction", 4).chrome();
        m.onClose(pl -> {
            if (side.expectClose) { side.expectClose = false; return; }
            // Esc out of the coin menu goes back to the trade window (closing THAT cancels the trade)
            if (!s.closed) Bukkit.getScheduler().runTask(Sky.plugin(), () -> { if (!s.closed && pl.isOnline()) openWindow(s, side); });
        });
        double purse = Sky.economy().purse(p);
        double[] presets = {1_000, 10_000, 100_000, 1_000_000, 10_000_000};
        int[] slots = {10, 11, 12, 13, 14};
        for (int i = 0; i < presets.length; i++) {
            double v = presets[i];
            m.set(slots[i], Icons.of(Material.GOLD_NUGGET, "&6" + BankMath.shortNum(v) + " coins",
                    "&7Offer &6" + BankMath.coinWords(v) + "&7.", "", purse >= v ? "&eClick to offer!" : "&cNot enough coins in your purse."),
                    c -> pick(s, side, c.player(), v));
        }
        m.set(15, Icons.of(Material.GOLD_INGOT, "&6Half your purse", "&7Offer &6" + BankMath.coinWords(BankMath.floorTenth(purse / 2)) + "&7.", "", "&eClick to offer!"),
                c -> pick(s, side, c.player(), Sky.economy().purse(c.player()) / 2));
        m.set(16, Icons.of(Material.GOLD_BLOCK, "&6Your whole purse", "&7Offer &6" + BankMath.coinWords(BankMath.floorTenth(purse)) + "&7.", "", "&eClick to offer!"),
                c -> pick(s, side, c.player(), Sky.economy().purse(c.player())));
        m.set(21, Icons.of(Material.OAK_SIGN, "&aCustom amount", "&7Type any amount: &e64k&7, &e2.5m&7...", "", "&eClick to type an amount!"), c -> {
            Player pl = c.player();
            side.expectClose = true;
            pl.closeInventory();
            Sky.menus().inputNumber(pl, "Coins to offer", n -> {
                if (s.closed) return;
                Player live = Bukkit.getPlayer(side.uuid);
                if (live == null) return;
                if (n != null && n >= 0 && !Double.isInfinite(n)) setCoins(s, side, live, n);
                openWindow(s, side);
            });
        });
        m.set(23, Icons.of(Material.BARRIER, "&cRemove coins", "&7Take your coin offer back.", "", "&eClick to remove!"),
                c -> pick(s, side, c.player(), 0));
        m.set(4, Icons.of(Material.PAPER, "&eDaily trade limit", "&7You can still send &6" + BankMath.coinWords(capLeft(p)) + " &7today.",
                "&7The limit grows with your SkyBlock level."));
        m.set(31, Icons.back("Trade"), c -> { side.expectClose = true; openWindow(s, side); });
        return m;
    }

    private static void pick(TradeSession s, TradeSession.Side side, Player p, double amount) {
        if (s.closed) return;
        setCoins(s, side, p, amount);
        side.expectClose = true;
        openWindow(s, side);
    }

    // ---- accept & commit --------------------------------------------------------------------------------------

    static long lockLeftMillis(TradeSession s) {
        long lock = (long) (Math.max(0, Bank.tradeAcceptCooldown.get()) * 1000);
        return Math.max(0, s.lastChange + lock - System.currentTimeMillis());
    }

    /** Why this side's accept needs a second click, or null (research 1.8: nothing back / first-time partner). */
    static String risk(TradeSession s, TradeSession.Side side) {
        TradeSession.Side other = s.other(side);
        boolean giving = !side.offers.isEmpty() || side.coins > 0;
        boolean getting = !other.offers.isEmpty() || other.coins > 0;
        return BankMath.tradeRisk(giving, getting, side.coins, s.pairTrades, Bank.tradeConfirmFirstCoins.get(), other.name);
    }

    private static void clickAccept(TradeSession s, TradeSession.Side side, Player p) {
        if (!s.live()) return;
        if (side.accepted) { side.accepted = false; redrawBoth(s); return; }
        if (lockLeftMillis(s) > 0) { Msg.error(p, "The trade just changed: check it again before accepting."); return; }
        if (side.offers.isEmpty() && side.coins <= 0 && s.other(side).offers.isEmpty() && s.other(side).coins <= 0) {
            Msg.error(p, "There is nothing in this trade yet.");
            return;
        }
        String risk = risk(s, side);
        long now = System.currentTimeMillis();
        if (risk != null && side.confirmUntil < now) {
            side.confirmUntil = now + (long) (Math.max(1, Bank.tradeConfirmSeconds.get()) * 1000);
            Msg.error(p, risk + " Click Accept again to confirm.");
            Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f);
            redrawBoth(s);
            return;
        }
        side.confirmUntil = 0;
        side.accepted = true;
        Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1.6f);
        if (s.a.accepted && s.b.accepted) commit(s);
        else redrawBoth(s);
    }

    /** Both accepted: validate everything again, take coins, move escrow in one transaction, deliver. */
    static void commit(TradeSession s) {
        Player pa = Bukkit.getPlayer(s.a.uuid), pb = Bukkit.getPlayer(s.b.uuid);
        String why = commitProblem(s, pa, pb);
        if (why != null) {
            s.a.accepted = false;
            s.b.accepted = false;
            if (pa != null) Msg.error(pa, why);
            if (pb != null) Msg.error(pb, why);
            redrawBoth(s);
            return;
        }
        s.committing = true;
        redrawBoth(s);
        double ca = s.a.coins, cb = s.b.coins;
        if (ca > 0 && !Sky.economy().withdraw(pa, ca, "trade: to " + s.b.name + " (" + s.id.substring(0, 8) + ")")) { abortCommit(s, "Coins could not be taken."); return; }
        if (cb > 0 && !Sky.economy().withdraw(pb, cb, "trade: to " + s.a.name + " (" + s.id.substring(0, 8) + ")")) {
            if (ca > 0) Sky.economy().deposit(pa, ca, "trade: refund (" + s.id.substring(0, 8) + ")");
            abortCommit(s, "Coins could not be taken.");
            return;
        }
        List<String> aKeys = new ArrayList<>(), bKeys = new ArrayList<>();
        for (TradeSession.Offer o : s.a.offers) aKeys.add(o.key);
        for (TradeSession.Offer o : s.b.offers) bKeys.add(o.key);
        String aItems = summary(s.a.offers), bItems = summary(s.b.offers);
        Bank.store.commitTrade(s.id, s.a.uuid, s.a.name, s.b.uuid, s.b.name, aKeys, bKeys,
                BankMath.creditTenths(ca), BankMath.creditTenths(cb), aItems, bItems).thenAccept(ok -> Bank.sync(() -> {
            if (ok == null || !ok) {
                Player la = Bukkit.getPlayer(s.a.uuid), lb = Bukkit.getPlayer(s.b.uuid);
                if (ca > 0) refund(s.a.uuid, la, ca, s);
                if (cb > 0) refund(s.b.uuid, lb, cb, s);
                s.committing = false;
                cancel(s, "the trade could not be completed. Nothing changed hands.", null);
                return;
            }
            finish(s, ca, cb);
        }));
    }

    private static void refund(UUID id, Player p, double coins, TradeSession s) {
        if (p != null && Bank.ready(p)) Sky.economy().deposit(p, coins, "trade: refund (" + s.id.substring(0, 8) + ")");
        else Sky.economy().depositOffline(id, coins, "trade: refund (" + s.id.substring(0, 8) + ")");
    }

    private static void abortCommit(TradeSession s, String why) {
        s.committing = false;
        s.a.accepted = false;
        s.b.accepted = false;
        for (TradeSession.Side side : new TradeSession.Side[]{s.a, s.b}) {
            Player p = Bukkit.getPlayer(side.uuid);
            if (p != null) Msg.error(p, why);
        }
        redrawBoth(s);
    }

    private static String commitProblem(TradeSession s, Player pa, Player pb) {
        if (s.closed) return "The trade is closed.";
        if (pa == null || pb == null || !Bank.ready(pa) || !Bank.ready(pb)) return "Both players must be online.";
        if (!pa.getWorld().equals(pb.getWorld())) return "You are too far apart to trade.";
        double brk = Math.max(1, Bank.tradeBreakDistance.get());
        if (pa.getLocation().distanceSquared(pb.getLocation()) > brk * brk) return "You are too far apart to trade.";
        if (Sky.economy().purse(pa) < s.a.coins) return s.a.name + " no longer has the coins they offered.";
        if (Sky.economy().purse(pb) < s.b.coins) return s.b.name + " no longer has the coins they offered.";
        if (s.a.coins > capLeft(pa)) return s.a.name + " would go over their daily trade limit.";
        if (s.b.coins > capLeft(pb)) return s.b.name + " would go over their daily trade limit.";
        if (!fits(pa, s.b.offers)) return s.a.name + " does not have enough inventory space.";
        if (!fits(pb, s.a.offers)) return s.b.name + " does not have enough inventory space.";
        String ma = cannotTradeLate(pa, pb);
        return ma;
    }

    /** Profile-type rules once more at commit (a profile could have been switched by a kick/rejoin). */
    private static String cannotTradeLate(Player a, Player b) {
        String pa = Sky.profiles().profileId(a), pb = Sky.profiles().profileId(b);
        if (pa != null && pa.equals(pb)) return null;
        if (!"normal".equalsIgnoreCase(orNormal(Sky.profiles().mode(a))) || !"normal".equalsIgnoreCase(orNormal(Sky.profiles().mode(b))))
            return "One of these profiles cannot trade outside its co-op.";
        return null;
    }

    /** Would these stacks fit into the player's 36 storage slots (merging into partial stacks first)? */
    static boolean fits(Player p, List<TradeSession.Offer> incoming) {
        ItemStack[] inv = p.getInventory().getStorageContents();
        ItemStack[] sim = new ItemStack[inv.length];
        for (int i = 0; i < inv.length; i++) sim[i] = inv[i] == null ? null : inv[i].clone();
        for (TradeSession.Offer o : incoming) {
            int left = o.stack.getAmount();
            int max = o.stack.getMaxStackSize();
            for (int i = 0; i < sim.length && left > 0; i++) {
                ItemStack cur = sim[i];
                if (cur == null || cur.getType().isAir() || !cur.isSimilar(o.stack)) continue;
                int room = Math.max(0, max - cur.getAmount());
                int put = Math.min(room, left);
                cur.setAmount(cur.getAmount() + put);
                left -= put;
            }
            for (int i = 0; i < sim.length && left > 0; i++) {
                if (sim[i] != null && !sim[i].getType().isAir()) continue;
                ItemStack c = o.stack.clone();
                int put = Math.min(max, left);
                c.setAmount(put);
                sim[i] = c;
                left -= put;
            }
            if (left > 0) return false;
        }
        return true;
    }

    private static String summary(List<TradeSession.Offer> offers) {
        StringBuilder sb = new StringBuilder();
        for (TradeSession.Offer o : offers) {
            if (sb.length() > 0) sb.append(", ");
            String id = Sky.items().idOf(o.stack);
            sb.append(id == null ? o.stack.getType().name() : id).append(" x").append(o.stack.getAmount());
            ItemData d = id == null ? null : Sky.items().data(o.stack);
            if (d != null && d.uuid != null) sb.append(" [").append(d.uuid).append("]");
        }
        return sb.toString();
    }

    private static void finish(TradeSession s, double ca, double cb) {
        s.closed = true;
        s.committing = false;
        SESSIONS.remove(s.a.uuid, s);
        SESSIONS.remove(s.b.uuid, s);
        String today = Bank.today();
        for (TradeSession.Side side : new TradeSession.Side[]{s.a, s.b}) {
            TradeSession.Side other = s.other(side);
            Player p = Bukkit.getPlayer(side.uuid);
            if (p != null && Bank.ready(p)) {
                BankData.Member m = Bank.member(p);
                if (!today.equals(m.tradeDay)) { m.tradeDay = today; m.tradeSentToday = 0; }
                m.tradeSentToday += side.coins;
                m.tradesCompleted++;
                if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu) p.closeInventory();
                List<String> lines = new ArrayList<>();
                lines.add("&7You gave: " + describe(side));
                lines.add("&7You got: " + describe(other));
                Msg.box(p, "&a&lTRADE COMPLETED &7with &b" + other.name, lines);
                Msg.sound(p, Sound.ENTITY_VILLAGER_YES, 1f);
                deliver(p, true);
            }
        }
        Sky.log().info("[bank] trade " + s.id + " completed: " + s.a.name + " gave " + summary(s.a.offers) + " + " + ca
                + " coins; " + s.b.name + " gave " + summary(s.b.offers) + " + " + cb + " coins");
    }

    private static String describe(TradeSession.Side side) {
        List<String> parts = new ArrayList<>();
        int items = 0;
        for (TradeSession.Offer o : side.offers) items += o.stack.getAmount();
        if (!side.offers.isEmpty()) parts.add("&f" + items + " item" + (items == 1 ? "" : "s"));
        if (side.coins > 0) parts.add("&6" + BankMath.coinWords(side.coins));
        return parts.isEmpty() ? "&8nothing" : String.join(" &7+ ", parts);
    }

    // ---- cancel ---------------------------------------------------------------------------------------------

    /** Cancel a session; {@code leaving} is a player who is quitting (their items wait for next login). */
    static void cancel(TradeSession s, String reason, UUID leaving) {
        if (s.closed) return;
        if (s.committing) return;   // a commit in flight finishes or rolls back by itself
        s.closed = true;
        SESSIONS.remove(s.a.uuid, s);
        SESSIONS.remove(s.b.uuid, s);
        for (TradeSession.Side side : new TradeSession.Side[]{s.a, s.b}) {
            List<TradeSession.Offer> offers = new ArrayList<>(side.offers);
            side.offers.clear();
            Player p = Bukkit.getPlayer(side.uuid);
            boolean gone = p == null || side.uuid.equals(leaving);
            if (!offers.isEmpty()) {
                if (gone) {
                    List<String> keys = new ArrayList<>();
                    for (TradeSession.Offer o : offers) keys.add(o.key);
                    Bank.store.toDelivery(keys);
                } else {
                    giveBack(side.uuid, offers);
                }
            }
            if (p != null && !gone) {
                if (Sky.menus().current(p) != null && (Sky.menus().current(p) == side.menu || Sky.menus().current(p).title().equals("Coin Transaction"))) {
                    p.closeInventory();
                }
                Msg.error(p, "Trade cancelled: " + reason);
            }
        }
        Sky.log().info("[bank] trade " + s.id + " cancelled: " + Text.strip(reason));
    }

    // ---- deliveries ------------------------------------------------------------------------------------------

    /** Hand over everything waiting for this player (trade goods, coins, items from cancelled trades). */
    static void deliver(Player p, boolean quiet) {
        UUID id = p.getUniqueId();
        Bank.store.claim(id).thenAccept(rows -> Bank.sync(() -> {
            if (rows == null || rows.isEmpty()) return;
            Player live = Bukkit.getPlayer(id);
            if (live == null || !Bank.ready(live)) { Bank.store.unclaim(rows); return; }
            int returned = 0, received = 0;
            double coins = 0;
            List<BankStore.Pending> failed = new ArrayList<>();
            for (BankStore.Pending row : rows) {
                boolean own = row.from() == null || row.from().equals(id.toString());
                if ("coins".equals(row.kind())) {
                    double c = BankMath.coins(row.coins());
                    String from = row.from() == null ? "?" : Bank.name(UUID.fromString(row.from()));
                    Sky.economy().deposit(live, c, "trade: from " + from + (row.tradeId() == null ? "" : " (" + row.tradeId().substring(0, 8) + ")"));
                    coins += c;
                } else {
                    try {
                        ItemStack st = ItemStack.deserializeBytes(row.item());
                        Sky.loot().give(live, st, LootSource.TRADE);
                        if (own) returned += st.getAmount(); else received += st.getAmount();
                    } catch (Throwable t) {
                        Sky.log().warning("[bank] could not restore a pending item for " + live.getName() + ": " + t.getMessage());
                        failed.add(row);
                    }
                }
            }
            if (!failed.isEmpty()) Bank.store.unclaim(failed);
            try { live.saveData(); } catch (Throwable ignored) { }
            Sky.profiles().saveNow(live);
            if (quiet) return;
            if (returned > 0) Msg.send(live, "&e[Trade] &7Returned &f" + returned + " item" + (returned == 1 ? "" : "s") + " &7from a trade that did not finish.");
            if (received > 0 || coins > 0) Msg.send(live, "&e[Trade] &7Received &f" + received + " item" + (received == 1 ? "" : "s")
                    + (coins > 0 ? " &7and &6" + BankMath.coinWords(coins) : "") + " &7from a trade completed while you were away.");
        }));
    }

    // ---- rendering ---------------------------------------------------------------------------------------------

    static int mySlot(int i) { return (i / 4) * 9 + (i % 4); }

    static int theirSlot(int i) { return (i / 4) * 9 + 5 + (i % 4); }

    private static void render(Menu m, TradeSession s, TradeSession.Side side) {
        TradeSession.Side other = s.other(side);
        Player me = Bukkit.getPlayer(side.uuid);
        for (int r = 0; r < 6; r++) for (int c = 0; c < 9; c++) {
            int slot = r * 9 + c;
            if (r < 4 && c != 4) continue;
            m.set(slot, Icons.filler());
        }
        for (int r = 0; r < 5; r++) m.set(r * 9 + 4, Icons.of(Material.GRAY_STAINED_GLASS_PANE, "&7<- You &8| &7" + other.name + " ->"));
        int max = maxSlots();
        for (int i = 0; i < 16; i++) {
            int ms = mySlot(i), ts = theirSlot(i);
            if (i < side.offers.size()) {
                TradeSession.Offer o = side.offers.get(i);
                final int idx = i;
                m.set(ms, Icons.withLore(o.stack, List.of("", "&eClick to take it back!")), c -> retract(s, side, c.player(), idx));
            } else m.set(ms, i < max ? null : Icons.of(Material.RED_STAINED_GLASS_PANE, "&cNo slot"));
            if (i < other.offers.size()) {
                m.set(ts, theirIcon(other.offers.get(i).stack, other.name));
            } else m.set(ts, i < max ? null : Icons.of(Material.RED_STAINED_GLASS_PANE, "&cNo slot"));
        }
        // my coins
        double purse = me == null ? 0 : Sky.economy().purse(me);
        m.set(36, Icons.of(coinMaterial(side.coins), "&6Coin transaction",
                "&7Offering: " + (side.coins > 0 ? "&6" + BankMath.coinWords(side.coins) : "&8no coins"),
                "&7Your purse: &6" + BankMath.commas(purse), "", "&eClick to change!"), c -> {
            if (!s.live()) return;
            side.expectClose = true;
            coinMenu(s, side, c.player()).open(c.player());
        });
        m.set(39, acceptIcon(s, side), c -> clickAccept(s, side, c.player()));
        // their status & coins
        m.set(41, other.accepted
                ? Icons.of(Material.LIME_DYE, "&a" + other.name + " accepted", "&7They are happy with this trade.")
                : Icons.of(Material.GRAY_DYE, "&7" + other.name + " has not accepted", "&7Waiting for them..."));
        m.set(44, Icons.of(coinMaterial(other.coins), "&6" + other.name + "'s coins",
                "&7Offering: " + (other.coins > 0 ? "&6" + BankMath.coinWords(other.coins) : "&8no coins")));
        m.set(49, Icons.of(Material.BARRIER, "&cCancel Trade", "&7Everything goes back to its owner."), c -> {
            if (!s.live()) return;
            cancel(s, side.name + " cancelled the trade.", null);
        });
        Player them = Bukkit.getPlayer(other.uuid);
        int lvl = them == null ? 0 : Sky.service(Features.SkyLevel.class).level(them);
        List<String> info = new ArrayList<>();
        info.add("&7SkyBlock Level: &b" + lvl);
        info.add("&7Trades completed: &a" + (them != null && Bank.ready(them) ? Bank.member(them).tradesCompleted : 0));
        info.add("");
        info.add("&7Click items in &eyour inventory &7to");
        info.add("&7offer them. Click your offers to");
        info.add("&7take them back.");
        info.add("");
        info.add("&7Any change un-accepts both sides.");
        m.set(53, them == null ? Icons.of(Material.PLAYER_HEAD, "&b" + other.name, info)
                : Icons.playerHead(them.getUniqueId(), "&b" + other.name, info));
    }

    private static Material coinMaterial(double coins) {
        if (coins >= 10_000_000) return Material.GOLD_BLOCK;
        if (coins >= 100_000) return Material.GOLD_INGOT;
        return Material.GOLD_NUGGET;
    }

    private static ItemStack theirIcon(ItemStack stack, String owner) {
        List<String> extra = new ArrayList<>();
        extra.add("");
        String id = Sky.items().idOf(stack);
        if (id != null) {
            ItemDef def = Sky.items().def(id);
            if (def != null) extra.add("&8Real item: " + Sky.items().rarity(stack).code() + Text.strip(def.name));
            extra.add("&8ID: " + id);
            ItemData d = Sky.items().data(stack);
            if (d != null && d.uuid != null) extra.add("&8UUID: " + d.uuid.substring(0, Math.min(8, d.uuid.length())));
        } else {
            extra.add("&8Vanilla item: " + stack.getType().name());
        }
        extra.add("&8Offered by " + owner);
        return Icons.withLore(stack, extra);
    }

    private static ItemStack acceptIcon(TradeSession s, TradeSession.Side side) {
        TradeSession.Side other = s.other(side);
        List<String> lore = new ArrayList<>();
        lore.add("&7You give: " + describe(side));
        lore.add("&7You get: " + describe(other));
        boolean giving = !side.offers.isEmpty() || side.coins > 0;
        boolean getting = !other.offers.isEmpty() || other.coins > 0;
        if (giving && !getting) { lore.add(""); lore.add("&c&lWARNING! &cYou are getting nothing back!"); }
        if (other.coins >= 1_000_000) { lore.add(""); lore.add("&7They pay: &6" + BankMath.coinWords(other.coins)); }
        lore.add("");
        if (s.committing) return Icons.of(Material.YELLOW_TERRACOTTA, "&eProcessing trade...", lore);
        if (side.accepted) {
            lore.add("&7Waiting for &b" + other.name + "&7...");
            lore.add("&eClick to take back your accept.");
            return Icons.glow(Icons.of(Material.LIME_TERRACOTTA, "&aYou accepted!", lore));
        }
        long lock = lockLeftMillis(s);
        if (lock > 0) {
            lore.add("&eThe trade changed! Check it again.");
            lore.add("&7Accept unlocks in &e" + Math.max(1, (lock + 999) / 1000) + "s");
            return Icons.of(Material.ORANGE_TERRACOTTA, "&6Wait...", lore);
        }
        String risk = risk(s, side);
        if (risk != null) {
            if (s.pairTrades == 0) lore.add("&eFirst trade with &b" + other.name + "&e!");
            if (side.confirmUntil >= System.currentTimeMillis()) {
                lore.add("&c" + risk);
                lore.add("&cClick again to confirm!");
                return Icons.glow(Icons.of(Material.RED_TERRACOTTA, "&c&lCONFIRM TRADE", lore));
            }
            lore.add("&7You will be asked to click twice.");
        }
        lore.add("&eClick to accept the trade!");
        return Icons.of(Material.GREEN_TERRACOTTA, "&aAccept trade", lore);
    }

    // ---- periodic checks & events ------------------------------------------------------------------------------

    static void tick() {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, Map<UUID, Long>>> it = REQUESTS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Map<UUID, Long>> e = it.next();
            for (Iterator<Map.Entry<UUID, Long>> jt = e.getValue().entrySet().iterator(); jt.hasNext(); ) {
                Map.Entry<UUID, Long> r = jt.next();
                if (r.getValue() > now) continue;
                jt.remove();
                Player from = Bukkit.getPlayer(r.getKey());
                if (from != null) Msg.send(from, "&cYour trade request to " + Bank.name(e.getKey()) + " expired.");
            }
            if (e.getValue().isEmpty()) it.remove();
        }
        double brk = Math.max(1, Bank.tradeBreakDistance.get());
        for (TradeSession s : sessions()) {
            if (s.closed || s.committing) continue;
            Player a = Bukkit.getPlayer(s.a.uuid), b = Bukkit.getPlayer(s.b.uuid);
            if (a == null || b == null) { cancel(s, "a player left.", a == null ? s.a.uuid : s.b.uuid); continue; }
            if (!a.getWorld().equals(b.getWorld()) || a.getLocation().distanceSquared(b.getLocation()) > brk * brk) {
                cancel(s, "you moved too far apart.", null);
            }
        }
    }

    // ---- admin test drivers (same code paths as the clicks) -----------------------------------------------

    static void adminCoins(TradeSession s, Player p, double amount) {
        TradeSession.Side side = s.side(p.getUniqueId());
        if (side != null) setCoins(s, side, p, amount);
    }

    static void adminOffer(TradeSession s, Player p, int invSlot, ItemStack live) {
        TradeSession.Side side = s.side(p.getUniqueId());
        if (side != null) offer(s, side, p, invSlot, live);
    }

    static void adminAccept(TradeSession s, Player p) {
        TradeSession.Side side = s.side(p.getUniqueId());
        if (side != null && !side.accepted) clickAccept(s, side, p);
    }

    /**
     * Server stopping: no async callback will run any more, so nothing is handed back now. Every escrow row
     * becomes a delivery to its own owner (the next boot does the same for rows a crash left behind) and is
     * returned at their next login. A commit in flight is either in the DB queue (it lands) or not (rolled back).
     */
    static void shutdown() {
        for (TradeSession s : sessions()) {
            s.closed = true;
            for (TradeSession.Side side : new TradeSession.Side[]{s.a, s.b}) {
                List<String> keys = new ArrayList<>();
                for (TradeSession.Offer o : side.offers) keys.add(o.key);
                side.offers.clear();
                if (!keys.isEmpty() && !s.committing) Bank.store.toDelivery(keys);
                Player p = Bukkit.getPlayer(side.uuid);
                if (p != null) {
                    try { p.closeInventory(); } catch (Throwable ignored) { }
                    Msg.send(p, "&cTrade cancelled: the server is restarting. Your items come back when you next log in.");
                }
            }
        }
        SESSIONS.clear();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        TradeSession s = session(e.getPlayer());
        if (s != null) cancel(s, e.getPlayer().getName() + " left.", e.getPlayer().getUniqueId());
        REQUESTS.remove(e.getPlayer().getUniqueId());
        LAST_REQUEST.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(SkyEvents.Damage e) {
        Player v = e.ctx.playerVictim();
        if (v == null || e.ctx.finalDamage <= 0) return;
        TradeSession s = session(v);
        if (s != null) cancel(s, v.getName() + " took damage.", null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(SkyEvents.SkyDeath e) {
        TradeSession s = session(e.player);
        if (s != null) cancel(s, e.player.getName() + " died.", null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        TradeSession s = session(e.getPlayer());
        if (s == null || e.getTo() == null) return;
        if (!e.getFrom().getWorld().equals(e.getTo().getWorld()) || e.getFrom().distanceSquared(e.getTo()) > 9) {
            cancel(s, e.getPlayer().getName() + " teleported away.", null);
        }
    }

    /** Right-clicking another player (not sneaking: that opens their profile) sends a trade request. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onClickPlayer(PlayerInteractEntityEvent e) {
        if (!Bank.tradeClickPlayer.get() || e.getHand() != EquipmentSlot.HAND) return;
        if (!(e.getRightClicked() instanceof Player target) || !target.isOnline() || Bukkit.getPlayer(target.getUniqueId()) != target) return;
        Player p = e.getPlayer();
        if (p.isSneaking() || !Bank.ready(p) || !Bank.ready(target)) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand != null && !hand.getType().isAir()) return;   // only with an empty hand: never steal a weapon swing
        request(p, target);
    }
}
