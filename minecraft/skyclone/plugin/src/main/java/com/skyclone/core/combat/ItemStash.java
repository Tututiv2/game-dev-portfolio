package com.skyclone.core.combat;

import com.skyclone.api.Sky;
import com.skyclone.api.command.CommandService;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The ITEM STASH: where an earned item goes when the inventory is full, instead of the floor.
 *
 * <p>Playtest "endgame": a Dark Auction prize handed over with a full inventory lay on the ground as an
 * item entity. On a public server a 10M prize on the floor is a theft or loss risk (despawn, void,
 * lava, a /clear, a crash before pickup). The genre answer is a stash the player empties with
 * {@code /pickupstash} (and browses with {@code /viewstash}). It is profile member data, so it is saved
 * with the profile, survives logout and is per profile like the inventory-side state.
 *
 * <p>Only when the stash itself is full ({@code loot.stash-max-entries} distinct items) does overflow
 * fall back to the old owner-only floor drop.
 */
public final class ItemStash implements Listener {

    /** Profile member key. */
    public static final String KEY = "core_item_stash";

    private static final Map<UUID, Long> lastNotice = new HashMap<>();
    private static final Map<UUID, Long> lastPickup = new HashMap<>();
    /** Last CHAT notice of an overflow; cleared by a pickup, so the next overflow is announced again. */
    private static final Map<UUID, Long> lastChat = new HashMap<>();
    /** Named items waiting for their one combined chat line (see {@link #queueNamed}). */
    private static final Map<UUID, StashBatch> pendingNamed = new HashMap<>();
    /** When the last named-item line was said, so a burst spread over ticks is still one or two lines. */
    private static final Map<UUID, Long> lastNamedLine = new HashMap<>();
    /** Minimum gap between two named-item lines; anything named in between joins the next one. */
    static final long NAMED_WINDOW_MS = 2000;

    ItemStash() {}

    // ---- put ------------------------------------------------------------------------------------

    /**
     * Put {@code stack} in {@code p}'s stash. Works for an offline player too (written to their active
     * profile). {@code important} = save right away and name the item in chat (rewards, unique items).
     *
     * @return how many of the stack were NOT stashed (stash disabled or full) - the caller drops those
     */
    public static int put(Player p, ItemStack stack, boolean important) {
        if (p == null || stack == null || stack.getType().isAir() || stack.getAmount() <= 0) return 0;
        int amount = stack.getAmount();
        if (!enabled()) return amount;
        String key;
        try {
            key = encode(stack);
        } catch (Throwable t) {
            Sky.log().log(Level.WARNING, "[stash] could not encode " + stack.getType() + " for " + p.getName(), t);
            return amount;
        }
        String id = idOf(stack);
        boolean mergeable = stack.getMaxStackSize() > 1;
        boolean unique = isUnique(id);
        boolean save = important || unique;
        int rank = rankOf(stack);
        long now = System.currentTimeMillis();

        if (p.isOnline() && Sky.profiles().isLoaded(p)) {
            StashData d = Sky.profiles().member(p, KEY, StashData.class);
            if (!d.add(key, id, amount, mergeable, maxEntries(), now, save, rank)) {
                notifyFull(p);
                return amount;
            }
            if (save) Sky.profiles().saveNow(p);
            notifyStashed(p, stack, id, save);
            return 0;
        }
        // Offline / not loaded yet: straight into their active profile (no cap - nothing else can take it).
        UUID u = p.getUniqueId();
        String who = p.getName();
        Sky.profiles().modifyOffline(u, KEY, StashData.class, d -> d.add(key, id, amount, mergeable, 0, now, save, rank))
                .exceptionally(ex -> {
                    // Recoverable by hand: the bytes are the item.
                    Sky.log().log(Level.SEVERE, "[stash] could not stash " + amount + "x " + id + " for " + who + " (" + u
                            + "). Item bytes (Base64, amount 1): " + key, ex);
                    return null;
                });
        return 0;
    }

    static String encode(ItemStack stack) {
        ItemStack one = stack.clone();
        one.setAmount(1);
        return Base64.getEncoder().encodeToString(one.serializeAsBytes());
    }

    static ItemStack decode(String b64) {
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(b64));
    }

    // ---- pickup ---------------------------------------------------------------------------------

    /** Move as much of the stash as fits into the inventory. {@code only} = one entry, or null for all. */
    static void pickup(Player p, StashData.Entry only) {
        if (!Sky.profiles().isLoaded(p)) { Msg.send(p, "&cYour profile is still loading."); return; }
        if (inRift(p)) {
            // The Rift swaps in its own inventory: anything picked up here would stay parked in the Rift.
            Msg.send(p, "&cYou can't pick up your item stash in the Rift.");
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastPickup.get(p.getUniqueId());
        if (last != null && now - last < 500) return;   // spam guard (each pickup saves player data)
        lastPickup.put(p.getUniqueId(), now);
        lastChat.remove(p.getUniqueId());   // the next overflow is a new episode: say it in chat again

        StashData d = Sky.profiles().member(p, KEY, StashData.class);
        d.prune();
        if (d.items.isEmpty()) { Msg.send(p, "&7Your item stash is empty."); return; }
        long moved = 0;
        // Rewards, rarer items and small piles first (StashData#pickupOrder): oldest-first refilled a full
        // inventory with the 4,000 wheat a farming session overflowed and left the Harvest Fair Tickets behind it.
        for (StashData.Entry e : d.pickupOrder()) {
            if (only != null && e != only) continue;
            ItemStack proto;
            try {
                proto = decode(e.item);
            } catch (Throwable t) {
                Sky.log().log(Level.WARNING, "[stash] unreadable stash entry (" + e.id + " x" + e.amount + ") for "
                        + p.getName() + " - kept untouched", t);
                continue;
            }
            int max = Math.max(1, proto.getMaxStackSize());
            while (e.amount > 0) {
                int n = Math.min(max, e.amount);
                ItemStack part = proto.clone();
                part.setAmount(n);
                int notFit = 0;
                for (ItemStack rest : p.getInventory().addItem(part).values()) notFit += rest.getAmount();
                int fit = n - notFit;
                e.amount -= fit;
                moved += fit;
                if (notFit > 0) break;
            }
            // No stop on a full inventory: a later entry may still top up a stack the player already holds.
        }
        d.prune();
        if (moved > 0) {
            // Inventory first, then the stash: a crash between the two duplicates rather than loses.
            try { p.saveData(); } catch (Throwable t) { Sky.log().log(Level.WARNING, "[stash] saveData failed", t); }
            Sky.profiles().saveNow(p);
            Msg.send(p, "&aYou picked up &e" + Text.commasInt(moved) + " &aitem" + (moved == 1 ? "" : "s") + " from your stash.");
        }
        long left = d.total();
        if (left > 0) {
            if (moved == 0) Msg.send(p, "&cYour inventory is full!");
            Msg.send(p, "&eYou still have &6" + Text.commasInt(left) + " &eitem" + (left == 1 ? "" : "s")
                    + " in your stash. Make room and use &6/pickupstash &eagain.");
        }
    }

    // ---- view -----------------------------------------------------------------------------------

    static void view(Player p, int page) {
        if (!Sky.profiles().isLoaded(p)) { Msg.send(p, "&cYour profile is still loading."); return; }
        StashData d = Sky.profiles().member(p, KEY, StashData.class);
        d.prune();
        List<StashData.Entry> list = d.pickupOrder();   // the menu reads in the order /pickupstash empties it
        long total = d.total();
        Menu m = Sky.menus().paged("Item Stash", list, ItemStash::icon, click -> {
            Player who = click.click().player();
            StashData.Entry e = click.entry();
            StashData cur = Sky.profiles().member(who, KEY, StashData.class);
            if (cur.items.contains(e)) pickup(who, e);
            view(who, click.page());
        }, menu -> {
            menu.set(4, Icons.of(Material.CHEST, "&aItem Stash",
                    "&7Things you earned while your", "&7inventory was full wait here.", "",
                    "&7Stashed: &e" + Text.commasInt(total) + " &7item" + (total == 1 ? "" : "s"),
                    "&7Kinds: &e" + list.size() + " &8/ " + maxEntries(), "",
                    "&7Click an item to pick it up, or", "&7use &e/pickupstash&7."));
            if (!list.isEmpty()) {
                menu.set(50, Icons.of(Material.HOPPER, "&aPick Up Everything",
                        "&7Moves as much as fits into", "&7your inventory.", "", "&eClick to pick up!"),
                        c -> { pickup(c.player(), null); view(c.player(), 0); });
            }
        }, Math.max(0, page));
        m.open(p);
    }

    private static ItemStack icon(StashData.Entry e) {
        ItemStack it;
        try {
            it = decode(e.item);
        } catch (Throwable t) {
            return Icons.of(Material.BARRIER, "&cUnreadable item", "&7" + e.id + " x" + e.amount, "&7Ask an admin.");
        }
        it.setAmount(Math.max(1, Math.min(e.amount, it.getMaxStackSize())));
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Component.empty());
            lore.add(Text.c("&7Stashed: &e" + Text.commasInt(e.amount)));
            lore.add(Text.c("&eClick to pick up!"));
            meta.lore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    // ---- messages -------------------------------------------------------------------------------

    private static Component pickupButton() {
        return Text.c("&6&l[PICK UP]")
                .clickEvent(ClickEvent.runCommand("/pickupstash"))
                .hoverEvent(HoverEvent.showText(Text.c("&eClick to pick up your item stash!")));
    }

    /**
     * Bulk overflow (farming, mining, mob drops) is said in CHAT once per "episode" - the first overflow
     * since the stash was last picked up - and again only every {@code loot.stash-reminder-seconds}, with
     * the running total; in between it is an action-bar flash at most every
     * {@code loot.full-message-cooldown-seconds}. Wave4-loops: a plain 20 s chat throttle still printed
     * "Your inventory is full!" 17 times in 6 minutes of farming. Named items (rewards, trades, uniques)
     * are always named in chat: they are rare, and the player must learn where the prize went - but a burst
     * of them is one line ({@link #queueNamed}).
     */
    private static void notifyStashed(Player p, ItemStack stack, String id, boolean named) {
        long now = System.currentTimeMillis();
        UUID u = p.getUniqueId();
        if (named) {
            lastNotice.put(u, now);
            lastChat.put(u, now);
            queueNamed(p, nameOf(stack, id), stack.getAmount(), now);
            return;
        }
        Long chat = lastChat.get(u);
        if (chat == null || now - chat >= Math.max(0, reminderSeconds()) * 1000L) {
            lastChat.put(u, now);
            lastNotice.put(u, now);
            long total = stashTotal(p);
            String count = total > 0 ? " &7(" + Text.commasInt(total) + " item" + (total == 1 ? "" : "s") + " waiting)" : "";
            p.sendMessage(Text.c("&cYour inventory is full! &eItems are going to your item stash" + count + "&e. ")
                    .append(pickupButton()));
            return;
        }
        Long last = lastNotice.get(u);
        if (last != null && now - last < Math.max(0, CombatDials.FULL_MSG_COOLDOWN.get()) * 1000L) return;
        lastNotice.put(u, now);
        try {
            Sky.vitals().flash(p, "&cInventory full &7- &e" + Text.commasInt(stashTotal(p)) + " &7in &e/pickupstash", 40);
        } catch (RuntimeException ignored) {
            // no action bar: the chat reminder still comes on its own clock
        }
    }

    /**
     * Named items given in a burst (a reward kit, a DA lot, 17 unique axes) are said in ONE line: they gather in
     * a per-player {@link StashBatch} that is flushed a tick later, and a later burst waits until
     * {@link #NAMED_WINDOW_MS} after the previous named line. Wave4-endgame: 17 gives printed 17 lines.
     */
    private static void queueNamed(Player p, String name, int amount, long now) {
        UUID u = p.getUniqueId();
        StashBatch b = pendingNamed.get(u);
        if (b != null) { b.add(name, amount); return; }   // a flush is already scheduled: join it
        b = new StashBatch();
        b.add(name, amount);
        pendingNamed.put(u, b);
        long ticks = Math.max(1, (StashBatch.delayMs(lastNamedLine.get(u), now, NAMED_WINDOW_MS) + 49) / 50);
        try {
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> flushNamed(u), ticks);
        } catch (RuntimeException e) {
            flushNamed(u);   // no scheduler (plugin disabling): say it now rather than never
        }
    }

    private static void flushNamed(UUID u) {
        StashBatch b = pendingNamed.remove(u);
        if (b == null || b.isEmpty()) return;
        Player p = Bukkit.getPlayer(u);
        if (p == null || !p.isOnline()) return;   // the items are saved; the login reminder will tell them
        long now = System.currentTimeMillis();
        lastNamedLine.put(u, now);
        lastChat.put(u, now);
        Component line = Text.c(b.line());
        List<String> hover = b.hover();
        if (!hover.isEmpty()) line = line.hoverEvent(HoverEvent.showText(Text.c(String.join("\n", hover))));
        p.sendMessage(line.append(pickupButton()));
    }

    private static long stashTotal(Player p) {
        try {
            return Sky.profiles().isLoaded(p) ? Sky.profiles().member(p, KEY, StashData.class).total() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    static int reminderSeconds() {
        return CombatDials.STASH_REMINDER_SECONDS == null ? 300 : CombatDials.STASH_REMINDER_SECONDS.get();
    }

    private static void notifyFull(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastNotice.get(p.getUniqueId());
        if (last != null && now - last < Math.max(0, CombatDials.FULL_MSG_COOLDOWN.get()) * 1000L) return;
        lastNotice.put(p.getUniqueId(), now);
        p.sendMessage(Text.c("&cYour inventory AND item stash are full! Items are dropping at your feet. ").append(pickupButton()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onReady(SkyEvents.ProfileReady e) {
        Player p = e.player;
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            if (!p.isOnline() || !Sky.profiles().isLoaded(p)) return;
            long n = Sky.profiles().member(p, KEY, StashData.class).total();
            if (n <= 0) return;
            p.sendMessage(Text.c("&eYou have &6" + Text.commasInt(n) + " &eitem" + (n == 1 ? "" : "s")
                    + " in your item stash. ").append(pickupButton()));
        }, 60L);
    }

    static void forget(UUID u) {
        lastNotice.remove(u); lastPickup.remove(u); lastChat.remove(u);
        pendingNamed.remove(u); lastNamedLine.remove(u);
    }

    // ---- commands -------------------------------------------------------------------------------

    static void register() {
        CommandService cs = Sky.commands();
        cs.register(SkyCommand.of("pickupstash", "Pick up the items waiting in your item stash")
                .alias("pickstash").playerOnly().category("Items")
                .run((s, a) -> pickup((Player) s, null)));
        cs.register(SkyCommand.of("viewstash", "Browse your item stash (items earned while your inventory was full)")
                .alias("stash").playerOnly().category("Items")
                .run((s, a) -> view((Player) s, 0)));
        cs.admin("stash", "<player> [clear]", "Show (or clear) a player's item stash",
                ItemStash::adminCommand,
                (s, a) -> {
                    if (a.length <= 1) {
                        List<String> names = new ArrayList<>();
                        for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
                        return names;
                    }
                    return a.length == 2 ? List.of("clear") : List.of();
                });
        cs.selfTest("loot.stash-roundtrip", ItemStash::selfTestRoundTrip);
        cs.selfTest("loot.stash-merge-and-cap", ItemStash::selfTestMerge);
    }

    private static void adminCommand(CommandSender s, String[] a) {
        if (a.length < 1) { s.sendMessage(Text.c("&cUsage: /sb stash <player> [clear]")); return; }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null || !Sky.profiles().isLoaded(t)) { s.sendMessage(Text.c("&cThat player is not online.")); return; }
        StashData d = Sky.profiles().member(t, KEY, StashData.class);
        d.prune();
        if (a.length > 1 && a[1].equalsIgnoreCase("clear")) {
            long n = d.total();
            d.items.clear();
            Sky.profiles().saveNow(t);
            s.sendMessage(Text.c("&aCleared " + Text.commasInt(n) + " stashed items of " + t.getName() + "."));
            Sky.log().info("[admin] " + s.getName() + " cleared the item stash of " + t.getName() + " (" + n + " items)");
            return;
        }
        s.sendMessage(Text.c("&6Item stash of " + t.getName() + ": &e" + d.liveEntries() + " &6kinds, &e"
                + Text.commasInt(d.total()) + " &6items"));
        int shown = 0;
        for (StashData.Entry e : d.items) {
            if (shown++ >= 20) { s.sendMessage(Text.c("&7...")); break; }
            s.sendMessage(Text.c("&7- &f" + e.id + " &8x&e" + Text.commasInt(e.amount)));
        }
    }

    private static String selfTestRoundTrip() {
        ItemStack in = new ItemStack(Material.DIAMOND, 1);
        ItemStack back = decode(encode(in));
        if (!back.isSimilar(in)) return "a diamond did not survive encode/decode";
        if (!encode(new ItemStack(Material.DIAMOND, 7)).equals(encode(in))) return "the stash key depends on the amount";
        // a sky item with its data (id, rarity, lore) must come back as the same sky item
        for (String id : List.of("ENCHANTED_DIAMOND", "ASPECT_OF_THE_END")) {
            if (!Sky.items().exists(id)) continue;
            ItemStack sky = Sky.items().create(id, 1);
            ItemStack again = decode(encode(sky));
            if (!id.equals(Sky.items().idOf(again))) return id + " lost its sky id through encode/decode";
            if (!again.isSimilar(sky)) return id + " changed through encode/decode";
        }
        return null;
    }

    private static String selfTestMerge() {
        StashData d = new StashData();
        d.add("A", "A", 64, true, 2, 0);
        d.add("A", "A", 64, true, 2, 0);
        if (d.liveEntries() != 1 || d.total() != 128) return "identical items did not merge";
        d.add("U1", "U", 1, false, 2, 0);
        if (d.add("U2", "U", 1, false, 2, 0)) return "a full stash took a new kind of item";
        if (!d.add("A", "A", 1, true, 2, 0)) return "a full stash refused an item it already holds";
        StashData o = new StashData();
        o.add("WHEAT", "WHEAT", 4000, true, 0, 1);
        o.add("ESS", "FOSSIL_ESSENCE", 3, true, 0, 2);
        o.add("TICKET", "JACOBS_TICKET", 5, true, 0, 3, true, 0);
        List<StashData.Entry> order = o.pickupOrder();
        if (!"TICKET".equals(order.get(0).item) || !"ESS".equals(order.get(1).item))
            return "pickup order put " + order.get(0).id + " first (rewards and small piles must come before bulk)";
        return null;
    }

    // ---- helpers --------------------------------------------------------------------------------

    static boolean enabled() {
        return CombatDials.STASH_ENABLED == null || CombatDials.STASH_ENABLED.get();
    }

    static int maxEntries() {
        return CombatDials.STASH_MAX_ENTRIES == null ? 720 : Math.max(1, CombatDials.STASH_MAX_ENTRIES.get());
    }

    private static boolean inRift(Player p) {
        try { return "rift".equals(Sky.worlds().islandOf(p.getWorld())); } catch (RuntimeException e) { return false; }
    }

    private static String idOf(ItemStack s) {
        try {
            String id = Sky.items().idOf(s);
            if (id == null) id = Sky.items().vanillaId(s.getType());
            return id != null ? id : s.getType().name();
        } catch (RuntimeException e) {
            return s.getType().name();
        }
    }

    /** Rarity ordinal (higher = rarer) for the pickup order; 0 when the item has none. */
    private static int rankOf(ItemStack s) {
        try {
            Rarity r = Sky.items().rarity(s);
            return r == null ? 0 : r.ordinal();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static boolean isUnique(String id) {
        try {
            ItemDef def = Sky.items().def(id);
            return def != null && def.isUnique();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String nameOf(ItemStack stack, String id) {
        try {
            ItemDef def = Sky.items().def(id);
            Rarity r = Sky.items().rarity(stack);
            String n = def != null && def.name != null ? def.name : Text.titleCase(id);
            return (r != null ? r.code() : "&f") + n;
        } catch (RuntimeException e) {
            return "&f" + Text.titleCase(id);
        }
    }
}
