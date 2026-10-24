package com.skyclone.mod.menus;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.time.CalendarEvent;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Player-side behaviour of the module: settings set-up on ProfileReady, sneak-click profile viewing,
 * the player-chat filter, calendar reminders / start messages, and the tab-list widgets.
 */
final class MenusListener implements Listener {

    private final Dials.Dial<Boolean> sneakClick;
    private final Dials.Dial<Boolean> reminders;
    private final Dials.Dial<Boolean> tabWidgets;
    private final long enabledAt = System.currentTimeMillis();
    private final Map<UUID, Long> lastSneakView = new HashMap<>();
    /** eventId -> the start time (epoch ms, rounded to the minute) we already reminded about. */
    private final Map<String, Long> reminded = new HashMap<>();

    /** {@code menus.event-reminders}, for {@link #liveReader} (static: the settings screens are static). */
    private static volatile Dials.Dial<Boolean> announcerDial;

    private final Dials.Dial<Boolean> snapshots;

    MenusListener(Dials.Dial<Boolean> sneakClick, Dials.Dial<Boolean> reminders, Dials.Dial<Boolean> tabWidgets,
                  Dials.Dial<Boolean> snapshots) {
        this.sneakClick = sneakClick;
        this.reminders = reminders;
        this.tabWidgets = tabWidgets;
        this.snapshots = snapshots;
        announcerDial = reminders;
    }

    // ---- profile lifecycle ------------------------------------------------------------------------------

    @EventHandler
    public void onReady(SkyEvents.ProfileReady e) {
        SettingsStore.prepare(e.player);
        // the offline profile viewer's snapshot: after modules have equipped pets, accessories and stats
        Player p = e.player;
        if (snapshots.get()) Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (p.isOnline()) PvSnapshot.take(p); }, 100L);
    }

    /**
     * The snapshot as the player leaves. LOWEST: before any module's own quit handler drops the caches the stat
     * sheet is built from (core saves member data later, at MONITOR, so this lands in the same save).
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuitSnapshot(PlayerQuitEvent e) {
        if (snapshots.get()) PvSnapshot.take(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        SettingsStore.forget(e.getPlayer());
        lastSneakView.remove(e.getPlayer().getUniqueId());
    }

    // ---- sneak + right-click a player --------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!(e.getRightClicked() instanceof Player target)) return;
        Player p = e.getPlayer();
        if (!p.isSneaking() || !sneakClick.get()) return;
        if (Bukkit.getPlayer(target.getUniqueId()) != target) return;          // a fake/NPC player entity
        if (!Sky.profiles().isReady(p) || !SettingsStore.on(p, SettingDefs.PV_SNEAK)) return;
        long now = System.currentTimeMillis();
        Long last = lastSneakView.get(p.getUniqueId());
        if (last != null && now - last < 1000) return;
        lastSneakView.put(p.getUniqueId(), now);
        Bukkit.getScheduler().runTask(Sky.plugin(), () -> { if (p.isOnline()) ProfileViewer.open(p, target); });
    }

    // ---- Damage Numbers (combat.damage_indicators) ------------------------------------------------------

    /** Core's damage indicators carry this PDC byte (core/combat CombatKeys.INDICATOR, plugin namespace "indicator"). */
    private static org.bukkit.NamespacedKey indicatorKey;

    /**
     * Core spawns every floating damage number as a TextDisplay tagged {@code <plugin>:indicator}. Hide it from
     * each player who turned Damage Numbers off, as it enters the world - before the tracker sends it - so
     * nobody sees it flash. One set lookup per indicator when nobody has it off.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityAdd(com.destroystokyo.paper.event.entity.EntityAddToWorldEvent e) {
        if (SettingsStore.INDICATORS_HIDDEN.isEmpty()) return;
        if (!(e.getEntity() instanceof org.bukkit.entity.TextDisplay td)) return;
        if (indicatorKey == null) indicatorKey = new org.bukkit.NamespacedKey(Sky.plugin(), "indicator");
        if (!td.getPersistentDataContainer().has(indicatorKey)) return;
        hideIndicator(td);
        // A private indicator (only for the attacker) is shown to its player by core right AFTER the spawn, with
        // the same plugin handle, which would undo the hide above; hide it once more on the next tick.
        Bukkit.getScheduler().runTask(Sky.plugin(), () -> { if (td.isValid()) hideIndicator(td); });
    }

    private static void hideIndicator(org.bukkit.entity.Entity td) {
        for (UUID id : SettingsStore.INDICATORS_HIDDEN) {
            Player v = Bukkit.getPlayer(id);
            if (v != null) v.hideEntity(Sky.plugin(), td);
        }
    }

    // ---- Co-op Invites (chat.coop_invites) ----------------------------------------------------------------

    /**
     * {@code /coop invite <player>} (or {@code add}) to someone who turned Co-op Invites off is refused before
     * core's command runs, with the reason. Only typed commands pass through here; that is how invites are sent.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(org.bukkit.event.player.PlayerCommandPreprocessEvent e) {
        String trade = tradeTarget(e.getMessage());
        if (trade != null) { onTradeCommand(e, trade); return; }
        String target = coopInviteTarget(e.getMessage());
        if (target == null) return;
        Player t = Bukkit.getPlayerExact(target);
        if (t == null || t.equals(e.getPlayer()) || !Sky.profiles().isReady(t)) return;
        if (SettingsStore.on(t, SettingDefs.COOP_INVITES)) return;
        e.setCancelled(true);
        Msg.error(e.getPlayer(), t.getName() + " is not accepting co-op invites.");
    }

    // ---- Trade Requests (chat.trade_requests) -------------------------------------------------------------

    /**
     * "from|to" -&gt; until when (epoch ms) a trade request {@code from} sent {@code to} may still be accepted, as far as
     * this module saw it go out. Used so a player who turned requests OFF can still have their OWN request accepted:
     * the bank's {@code /trade <name>} is both "ask" and "accept", and accepting is never refused.
     */
    private static final Map<String, Long> tradeAsked = new java.util.concurrent.ConcurrentHashMap<>();

    /** The bank's request lifetime (its dial), plus slack; 60 s when the bank is not installed. */
    private static long tradeWindowMs() {
        try {
            return (Sky.dials().integer("bank.trade.request-seconds", 60, "Seconds before a trade request expires (research: ~60)").get() + 5) * 1000L;
        } catch (Throwable t) { return 65_000L; }
    }

    private void onTradeCommand(org.bukkit.event.player.PlayerCommandPreprocessEvent e, String name) {
        Player p = e.getPlayer();
        Player t = Bukkit.getPlayerExact(name);
        if (t == null || t.equals(p) || !Sky.profiles().isReady(t) || !Sky.commands().exists("trade")) return;
        if (!tradeAllowed(p, t)) {
            e.setCancelled(true);
            Msg.error(p, t.getName() + " is not accepting trade requests.");
        }
    }

    /**
     * The bank's right-click request (not sneaking, empty hand: bank.trade.click-player). LOW so it is cancelled
     * before the bank's NORMAL, ignoreCancelled handler sees it. Sneak-clicks are profile views and pass.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTradeClick(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !(e.getRightClicked() instanceof Player t)) return;
        Player p = e.getPlayer();
        if (p.isSneaking() || Bukkit.getPlayer(t.getUniqueId()) != t || !Sky.profiles().isReady(t)) return;
        var hand = p.getInventory().getItemInMainHand();
        if (hand != null && !hand.getType().isAir()) return;
        if (!Sky.commands().exists("trade")) return;
        boolean clickTrades;
        try { clickTrades = Sky.dials().flag("bank.trade.click-player", true, "Right-clicking another player sends them a trade request").get(); }
        catch (Throwable x) { clickTrades = true; }
        if (!clickTrades) return;
        if (!tradeAllowed(p, t)) {
            e.setCancelled(true);
            Msg.error(p, t.getName() + " is not accepting trade requests.");
        }
    }

    /** May {@code from} send {@code to} a trade request (or accept theirs)? Records an allowed request. */
    static boolean tradeAllowed(Player from, Player to) {
        long now = System.currentTimeMillis();
        if (tradeAsked.size() > 2000) tradeAsked.values().removeIf(until -> until < now);
        Long theirs = tradeAsked.get(to.getUniqueId() + "|" + from.getUniqueId());
        boolean accepting = theirs != null && theirs > now;
        if (!accepting && !SettingsStore.on(to, SettingDefs.TRADE_REQUESTS)) return false;
        if (!accepting) tradeAsked.put(from.getUniqueId() + "|" + to.getUniqueId(), now + tradeWindowMs());
        return true;
    }

    /** Pure: the player name in "/trade Name" (any case, extra spaces, "/plugin:trade"), else null. */
    static String tradeTarget(String message) {
        if (message == null) return null;
        String[] w = message.trim().split("\\s+");
        if (w.length < 2) return null;
        String cmd = w[0].toLowerCase(java.util.Locale.ROOT);
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        int colon = cmd.indexOf(':');
        if (colon >= 0) cmd = cmd.substring(colon + 1);
        if (!cmd.equals("trade")) return null;
        return w[1].matches("[A-Za-z0-9_]{1,16}") ? w[1] : null;
    }

    /** Pure: the player name in "/coop invite Name" or "/coop add Name" (any case, extra spaces), else null. */
    static String coopInviteTarget(String message) {
        if (message == null) return null;
        String[] w = message.trim().split("\\s+");
        if (w.length < 3) return null;
        String cmd = w[0].toLowerCase(java.util.Locale.ROOT);
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        int colon = cmd.indexOf(':');
        if (colon >= 0) cmd = cmd.substring(colon + 1);                       // "/skyclone:coop"
        if (!cmd.equals("coop")) return null;
        String sub = w[1].toLowerCase(java.util.Locale.ROOT);
        if (!sub.equals("invite") && !sub.equals("add")) return null;
        return w[2].matches("[A-Za-z0-9_]{1,16}") ? w[2] : null;
    }

    // ---- player chat filter ----------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (SettingsStore.CHAT_HIDDEN.isEmpty()) return;
        UUID sender = e.getPlayer().getUniqueId();
        try {
            e.viewers().removeIf(a -> a instanceof Player v && !v.getUniqueId().equals(sender)
                    && SettingsStore.CHAT_HIDDEN.contains(v.getUniqueId()));
        } catch (UnsupportedOperationException ignored) {
            // an immutable viewer set (another plugin replaced it): nothing we can do
        }
    }

    // ---- calendar ---------------------------------------------------------------------------------------

    /** Event name (lower case) -> when we last announced it, so two registrations of one festival say it once. */
    private final Map<String, Long> announced = new HashMap<>();

    @EventHandler
    public void onEventEnd(SkyEvents.CalendarEnd e) { Summaries.invalidateUpcoming(); }

    @EventHandler
    public void onEventStart(SkyEvents.CalendarStart e) {
        Summaries.invalidateUpcoming();
        if (!reminders.get()) return;
        if (System.currentTimeMillis() - enabledAt < 60_000) return;           // boot replays in-progress events
        CalendarEvent ev = find(e.eventId);
        if (ev == null) return;
        String name = Summaries.eventName(ev);
        long now = System.currentTimeMillis();
        Long last = announced.put(name.toLowerCase(java.util.Locale.ROOT), now);
        if (last != null && now - last < 120_000) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Sky.profiles().isReady(p) || !SettingsStore.on(p, SettingDefs.NOTIFY_START)) continue;
            if (!scopeAllows(p, ev.id, true)) continue;
            Msg.send(p, "&e[Calendar] &6" + name + " &ahas started!");
            Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1.4f);
        }
    }

    /** Runs every {@code windowMs}: remind players whose lead time matches an event's countdown. */
    void reminderTick(long windowMs) {
        if (!reminders.get()) return;
        List<Summaries.Upcoming> up = Summaries.upcoming();
        if (up.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Summaries.Upcoming u : up) {
            if (u.active() || u.millis() <= 0) continue;
            long startAt = (now + u.millis()) / 60_000 * 60_000;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!Sky.profiles().isReady(p) || !SettingsStore.on(p, SettingDefs.NOTIFY_REMIND)) continue;
                if (!scopeAllows(p, u.event().id, false)) continue;
                long lead = leadMillis(p);
                if (u.millis() > lead || u.millis() <= lead - windowMs) continue;   // fire once, inside one check window
                String key = u.event().id + "|" + p.getUniqueId() + "|" + lead;
                Long done = reminded.get(key);
                if (done != null && Math.abs(done - startAt) < 120_000) continue;
                reminded.put(key, startAt);
                remind(p, u);
            }
        }
        if (reminded.size() > 5000) reminded.clear();
    }

    /**
     * The player's Calendar Alerts scope ({@link SettingDefs#NOTIFY_SCOPE}) for a line this module would send
     * (mayors Request 15 b). With mayors installed it decides, through its requirement
     * {@code mayors:alert:<eventId>:start|remind} - the same policy its own announcer uses (important events only,
     * the Dark Auction only for a purse within reach, perk-gated festivals). Guarded on {@code Sky.has}: an unknown
     * requirement prefix fails closed and would silence everything. Without mayors only "off" means anything.
     */
    static boolean scopeAllows(Player p, String eventId, boolean start) {
        if (eventId == null) return true;
        if (Sky.has(Features.Mayors.class)) {
            try { return Sky.requirements().check(p, "mayors:alert:" + eventId + (start ? ":start" : ":remind")); }
            catch (Throwable t) { /* fall through to the plain scope */ }
        }
        return !"off".equals(SettingsStore.get(p, SettingDefs.NOTIFY_SCOPE));
    }

    /** Who acts on a setting right now ({@link SettingDefs#liveReader}), for /sb menus setting and the Settings lore. */
    static String liveReader(SettingDefs.Setting s) {
        Dials.Dial<Boolean> d = announcerDial;
        boolean menusOn = d == null || d.get();
        boolean mayors = Sky.has(Features.Mayors.class);
        boolean mayorsOn = false;
        if (mayors && !menusOn) {
            try {
                mayorsOn = Sky.dials().flag("mayors.alerts.takeover", true,
                        "Mayors sends the per-player-filtered calendar chat ([Calendar] starts in / has started) and turns menus.event-reminders off; false hands it back to menus").get();
            } catch (Throwable t) { mayorsOn = true; }
        }
        return SettingDefs.liveReader(s, menusOn, mayors, mayorsOn);
    }

    static long leadMillis(Player p) {
        try { return Long.parseLong(SettingsStore.get(p, SettingDefs.NOTIFY_REMIND_MIN)) * 60_000L; }
        catch (NumberFormatException e) { return 5 * 60_000L; }
    }

    static void remind(Player p, Summaries.Upcoming u) {
        Msg.send(p, "&e[Calendar] &6" + Summaries.eventName(u.event()) + " &estarts in &a" + Text.duration(Math.max(1, u.millis() / 1000)) + "&e!");
        Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_BELL, 1.2f);
    }

    static CalendarEvent find(String id) {
        try {
            for (CalendarEvent ev : Sky.calendar().events()) if (ev.id != null && ev.id.equals(id)) return ev;
        } catch (Throwable ignored) { /* calendar down */ }
        return null;
    }

    // ---- tab widgets ------------------------------------------------------------------------------------

    /**
     * Stats (research 08 s10 "Stats:") and Event ("Event:") are this module's. The pet and active-effect
     * widgets are NOT registered: the pets module (priority 300, with XP progress) and alchemy (160, with
     * time left) already draw better ones, and a second copy only pushed other widgets off the list.
     * {@link #petWidget}/{@link #effectsWidget} stay for {@code /sb menus tab} and as a stand-in.
     */
    void registerWidgets() {
        Sky.sidebar().tabWidget("menus.stats", 120, this::statsWidget);
        Sky.sidebar().tabWidget("menus.event", 165, this::eventWidget);
    }

    private boolean show(Player p, String key) {
        return tabWidgets.get() && Sky.profiles().isReady(p) && SettingsStore.on(p, key);
    }

    List<String> statsWidget(Player p) {
        if (!show(p, SettingDefs.TAB_STATS)) return List.of();
        List<String> out = new ArrayList<>();
        out.add("&e&lStats:");
        for (Stat s : new Stat[]{Stat.SPEED, Stat.STRENGTH, Stat.CRIT_CHANCE, Stat.CRIT_DAMAGE, Stat.ATTACK_SPEED}) {
            out.add(" " + s.display + ": &" + s.color + s.symbol + StatCategories.format(s, Sky.stats().get(p, s)));
        }
        return out;
    }

    List<String> petWidget(Player p) {
        if (!show(p, SettingDefs.TAB_PET) || !Sky.has(Features.Pets.class)) return List.of();
        if (!Sky.profiles().isReady(p)) return List.of();
        String label = MenuKit.petLabel(p);
        return List.of("&e&lPet:", " " + (label == null ? "&cNone" : label));
    }

    List<String> effectsWidget(Player p) {
        if (!show(p, SettingDefs.TAB_EFFECTS) || !Sky.has(Features.Potions.class)) return List.of();
        Map<String, Integer> fx = SkyBlockMenu.activeEffects(p);
        List<String> out = new ArrayList<>();
        out.add("&e&lActive Effects: &7(" + fx.size() + ")");
        if (fx.isEmpty()) { out.add(" &7None"); return out; }
        int n = 0;
        for (Map.Entry<String, Integer> e : fx.entrySet()) {
            if (n++ >= 3) { out.add(" &7...and " + (fx.size() - 3) + " more"); break; }
            out.add(" &a" + Text.titleCase(e.getKey()) + " " + Text.roman(Math.max(1, e.getValue())));
        }
        return out;
    }

    List<String> eventWidget(Player p) {
        if (!show(p, SettingDefs.TAB_EVENT)) return List.of();
        List<Summaries.Upcoming> up = Summaries.upcoming();
        if (up.isEmpty()) return List.of();
        // research 08 s10: "Event:" then " Spooky Festival: 2d 3h" - running events first, then the next one
        List<String> out = new ArrayList<>();
        out.add("&e&lEvent:");
        int n = 0;
        for (Summaries.Upcoming u : up) {
            if (n >= 2 || (!u.active() && n >= 1)) break;
            out.add(" " + Summaries.eventName(u.event()) + ": " + (u.active() ? (u.remaining() > 0 ? "&aEnds in " + Text.duration(u.remaining() / 1000) : "&aACTIVE") : "&e" + Text.duration(u.millis() / 1000)));
            n++;
        }
        return out;
    }
}
