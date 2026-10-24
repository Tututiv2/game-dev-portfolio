package com.skyclone.mod.menus;

import com.skyclone.api.Sky;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The profile viewer for OFFLINE players ({@code /pv <name>} when they are not on).
 *
 * <p>Armor, equipment and the stat sheet are live data that only exist while a player is online (the stat
 * sheet is rebuilt from every module's providers), so they cannot be recomputed for someone who is away.
 * Instead this module keeps a <b>snapshot</b> of what {@code /pv} showed for them - the reference game's
 * profile viewers show the last saved state the same way - in member data {@value #KEY}: every icon of the
 * main page, every stat value, and the stat icons that have sources. It is taken when the profile is ready,
 * every {@code menus.pv-snapshot-minutes}, and as they quit (LOWEST priority, before any module drops its
 * per-player caches). Reading it offline is {@code Sky.profiles().readOffline}, i.e. the player's ACTIVE
 * profile, off the main thread.
 *
 * <p>Every stored icon is a display copy ({@link MenuKit#displayCopy}: look only, no item id, no uuid) and is
 * copied again on the way out, so nothing in the offline viewer can ever be a real item.
 */
final class PvSnapshot {

    private PvSnapshot() {}

    static final String KEY = "menus_pv";

    /** Slots of the main page that are viewer-specific and never stored (coins is stored on its own). */
    private static final java.util.Set<Integer> SKIP = java.util.Set.of(ProfileViewer.COINS, 48, 49, 50, 51);

    /** Gson-friendly member data. */
    public static final class Data {
        public long at;
        public String name;
        public String profile;
        public boolean allowView = true;
        public boolean showCoins;
        /** Main-page slot -> base64 item bytes. */
        public Map<String, String> slots = new LinkedHashMap<>();
        /** Stat enum name -> value at snapshot time (every stat). */
        public Map<String, Double> stats = new LinkedHashMap<>();
        /** Stat enum name -> base64 icon, only for stats that had at least one source. */
        public Map<String, String> statIcons = new LinkedHashMap<>();
        public String coins;
    }

    // ---- taking -----------------------------------------------------------------------------------------

    /** Build a snapshot of an online, ready player (main thread). */
    static Data capture(Player t) {
        Data d = new Data();
        d.at = System.currentTimeMillis();
        d.name = t.getName();
        d.profile = Sky.profiles().profileName(t);
        d.allowView = SettingsStore.on(t, SettingDefs.PV_ALLOW);
        d.showCoins = SettingsStore.on(t, SettingDefs.PV_COINS);
        Menu scratch = new Menu("snapshot", 6);
        ProfileViewer.draw(scratch, t, t, false);
        for (int i = 0; i < scratch.size(); i++) {
            if (SKIP.contains(i)) continue;
            String b = encode(scratch.get(i));
            if (b != null) d.slots.put(String.valueOf(i), b);
        }
        for (Stat s : Stat.values()) {
            double v;
            try { v = Sky.stats().get(t, s); } catch (Throwable e) { v = s.base; }
            d.stats.put(s.name(), v);
            if (!ProfileViewer.breakdownOf(t, s).isEmpty()) {
                String b = encode(ProfileViewer.statIcon(t, s, false));
                if (b != null) d.statIcons.put(s.name(), b);
            }
        }
        d.coins = encode(ProfileViewer.coinsIcon(t));
        return d;
    }

    /** Take (or refresh) the stored snapshot of an online player. Never throws. */
    static void take(Player t) {
        if (t == null || !Sky.profiles().isReady(t)) return;
        try {
            Data fresh = capture(t);
            Data live = Sky.profiles().member(t, KEY, Data.class);
            live.at = fresh.at;
            live.name = fresh.name;
            live.profile = fresh.profile;
            live.allowView = fresh.allowView;
            live.showCoins = fresh.showCoins;
            live.slots = fresh.slots;
            live.stats = fresh.stats;
            live.statIcons = fresh.statIcons;
            live.coins = fresh.coins;
        } catch (Throwable e) {
            Sky.log().warning("[menus] profile snapshot of " + t.getName() + " failed: " + e);
        }
    }

    /** A privacy setting changed: update the two flags at once, without a full re-take. */
    static void privacyChanged(Player p, String key) {
        if (!key.equals(SettingDefs.PV_ALLOW) && !key.equals(SettingDefs.PV_COINS)) return;
        try {
            Data live = Sky.profiles().member(p, KEY, Data.class);
            live.allowView = SettingsStore.on(p, SettingDefs.PV_ALLOW);
            live.showCoins = SettingsStore.on(p, SettingDefs.PV_COINS);
        } catch (Throwable ignored) { /* not ready */ }
    }

    // ---- reading ----------------------------------------------------------------------------------------

    /**
     * {@code /pv <name>} for someone not online. Looks the name up in the server's own cache (never Mojang),
     * reads the snapshot off the main thread and opens it on the main thread.
     */
    static void openOffline(Player viewer, String name) {
        OfflinePlayer op;
        try { op = Bukkit.getOfflinePlayerIfCached(name); } catch (Throwable t) { op = null; }
        if (op == null || (!op.hasPlayedBefore() && !op.isOnline())) {
            Msg.error(viewer, "No player named " + name + " has played here.");
            return;
        }
        UUID id = op.getUniqueId();
        String shown = op.getName() == null ? name : op.getName();
        Msg.send(viewer, "&7Loading " + shown + "'s profile...");
        Sky.profiles().readOffline(id, KEY, Data.class).whenComplete((d, err) ->
                Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
                    if (!viewer.isOnline()) return;
                    Player now = Bukkit.getPlayer(id);
                    if (now != null && Sky.profiles().isReady(now)) { ProfileViewer.open(viewer, now); return; }   // came online
                    if (err != null) {
                        Sky.log().warning("[menus] offline profile read for " + shown + " failed: " + err);
                        Msg.error(viewer, "Couldn't load " + shown + "'s profile.");
                        return;
                    }
                    String why = refusal(viewer, d, shown);
                    if (why != null) { Msg.error(viewer, why); return; }
                    build(viewer, d).open(viewer);
                }));
    }

    /** Null when the viewer may see this snapshot, else the reason why not. */
    static String refusal(Player viewer, Data d, String name) {
        return refusal(viewer.hasPermission("skyclone.admin"), d, name);
    }

    /** Pure form of {@link #refusal(Player, Data, String)} (unit-tested). */
    static String refusal(boolean admin, Data d, String name) {
        if (d == null || d.at <= 0 || d.slots == null || d.slots.isEmpty())
            return name + " has no profile to show yet (it is saved the next time they play).";
        if (!d.allowView && !admin) return name + " has profile viewing turned off.";
        return null;
    }

    static Menu build(Player viewer, Data d) {
        String name = d.name == null ? "?" : d.name;
        Menu m = new Menu(ProfileViewer.clip(name + "'s Profile"), 6).chrome();
        String ago = Text.duration(Math.max(1, (System.currentTimeMillis() - d.at) / 1000));
        for (Map.Entry<String, String> e : d.slots.entrySet()) {
            int slot;
            try { slot = Integer.parseInt(e.getKey()); } catch (NumberFormatException ex) { continue; }
            if (slot < 0 || slot >= m.size()) continue;
            ItemStack it = decode(e.getValue());
            if (it == null) continue;
            if (slot == ProfileViewer.HEAD) it = Icons.withLore(it, List.of("", "&8Offline - last seen " + ago + " ago.",
                    "&8This is their profile as they left it."));
            StatCategories.Category cat = categoryAt(slot);
            if (cat != null) {
                m.set(slot, it, c -> { MenuKit.click(c.player()); statPage(viewer, d, cat).open(viewer); });
            } else m.set(slot, it);
        }
        if (d.coins != null && (d.showCoins || viewer.hasPermission("skyclone.admin"))) {
            ItemStack coins = decode(d.coins);
            if (coins != null) m.set(ProfileViewer.COINS, coins);
        }
        m.set(50, Icons.of(Material.CLOCK, "&eOffline Profile", List.of(
                "&7" + name + " is not online, so this is", "&7the profile they had when they", "&7were last seen, " + ago + " ago.",
                "", "&7Profile: &a" + (d.profile == null ? "?" : d.profile),
                "", "&8Stat breakdowns need them online.")));
        return m;
    }

    /** Category whose icon sits in {@code slot} on the main page, or null. */
    static StatCategories.Category categoryAt(int slot) {
        StatCategories.Category[] cats = StatCategories.Category.values();
        for (int i = 0; i < cats.length && i < ProfileViewer.CATS.length; i++) if (ProfileViewer.CATS[i] == slot) return cats[i];
        return null;
    }

    static Menu statPage(Player viewer, Data d, StatCategories.Category c) {
        String name = d.name == null ? "?" : d.name;
        Menu m = new Menu(ProfileViewer.clip(name + " - " + c.title), 6).chrome();
        int catSlot = -1;
        StatCategories.Category[] cats = StatCategories.Category.values();
        for (int i = 0; i < cats.length && i < ProfileViewer.CATS.length; i++) if (cats[i] == c) catSlot = ProfileViewer.CATS[i];
        ItemStack header = catSlot < 0 ? null : decode(d.slots.get(String.valueOf(catSlot)));
        if (header != null) m.set(4, header);
        List<Stat> stats = StatCategories.of(c);
        for (int i = 0; i < stats.size() && i < ProfileViewer.GRID.length; i++) {
            Stat s = stats.get(i);
            ItemStack icon = decode(d.statIcons.get(s.name()));
            if (icon == null) icon = plainStatIcon(s, d.stats.getOrDefault(s.name(), s.base));
            m.set(ProfileViewer.GRID[i], icon, click -> Msg.send(click.player(), "&7Stat breakdowns need " + name + " to be online."));
        }
        StatCategories.Category prev = cats[Math.floorMod(c.ordinal() - 1, cats.length)];
        StatCategories.Category next = cats[Math.floorMod(c.ordinal() + 1, cats.length)];
        m.set(45, Icons.of(Material.ARROW, "&a" + prev.title, "&7Previous category"), click -> { MenuKit.click(viewer); statPage(viewer, d, prev).open(viewer); });
        m.set(53, Icons.of(Material.ARROW, "&a" + next.title, "&7Next category"), click -> { MenuKit.click(viewer); statPage(viewer, d, next).open(viewer); });
        m.back(name + "'s Profile", () -> build(viewer, d));
        return m;
    }

    /** A stat with no sources: its value and base, the same look as the live icon. */
    static ItemStack plainStatIcon(Stat s, double value) {
        StatCategories.Info info = StatCategories.info(s);
        List<String> lore = new ArrayList<>(MenuKit.wrap(info.description()));
        lore.add("");
        lore.add("&7Base: &f" + StatCategories.format(s, s.base));
        return Icons.of(MenuKit.mat(info.icon(), Material.PAPER), StatCategories.valueLine(s, value), lore);
    }

    // ---- item bytes -------------------------------------------------------------------------------------

    /** Display copy -> base64, null for nothing / filler / failure. */
    static String encode(ItemStack it) {
        if (it == null || it.getType().isAir()) return null;
        if (it.getType() == Material.BLACK_STAINED_GLASS_PANE || it.getType() == Material.GRAY_STAINED_GLASS_PANE) {
            ItemMeta meta = it.getItemMeta();
            if (meta == null || !meta.hasDisplayName() || Text.plain(meta.displayName()).isBlank()) return null;
        }
        try {
            ItemStack look = MenuKit.displayCopy(it, null);
            return look == null ? null : Base64.getEncoder().encodeToString(look.serializeAsBytes());
        } catch (Throwable t) {
            return null;
        }
    }

    /** base64 -> a fresh display copy (look only), null when unreadable. */
    static ItemStack decode(String b64) {
        if (b64 == null || b64.isEmpty()) return null;
        try {
            return MenuKit.displayCopy(ItemStack.deserializeBytes(Base64.getDecoder().decode(b64)), null);
        } catch (Throwable t) {
            return null;
        }
    }
}
