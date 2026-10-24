package com.skyclone.core.items;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Anti-dupe. Every unique item carries a uuid (ItemData.uuid). Two stacks with the same uuid mean
 * one of them was copied. Within ONE player (inventory + ender chest) the older copy is kept
 * (smaller {@code created}; ties keep the first one found) and the newer is deleted, with both
 * stacks' full data written to {@code items_dupe_log}. Copies across DIFFERENT players are always
 * logged and only deleted when {@code items.dupe-delete-cross-player} is on, because which of two
 * byte-identical copies is "the original" cannot be known.
 */
final class DupeScanner {

    private final ItemServiceImpl items;

    DupeScanner(ItemServiceImpl items) { this.items = items; }

    /** {@code inv} is null for a module-supplied source (wave 5): those copies are logged, never deleted. */
    private record Where(Player player, Inventory inv, String invName, int slot, ItemStack stack, ItemData data) {
        String describe() { return player.getName() + " " + invName + "#" + slot; }
    }

    /** Scan these players together (cross-player copies are only seen within one scan). Returns dupes found. */
    int scan(Collection<? extends Player> players, String trigger) {
        Map<String, Where> seen = new HashMap<>();
        int found = 0;
        for (Player p : players) {
            if (p == null || !p.isOnline()) continue;
            found += scanInv(p, p.getInventory(), "inv", seen, trigger);
            found += scanInv(p, p.getEnderChest(), "ender", seen, trigger);
            for (Map.Entry<String, java.util.function.Function<Player, List<ItemStack>>> src : items.dupeSources().entrySet()) {
                List<ItemStack> stacks;
                try {
                    stacks = src.getValue().apply(p);
                } catch (Throwable t) {
                    items.warnThrottled("dupesrc:" + src.getKey(), "dupe-scan source '" + src.getKey() + "' threw " + t);
                    continue;
                }
                if (stacks == null || stacks.isEmpty()) continue;
                found += scanStacks(p, null, "src:" + src.getKey(), stacks.toArray(new ItemStack[0]), seen, trigger);
            }
        }
        return found;
    }

    private int scanInv(Player p, Inventory inv, String name, Map<String, Where> seen, String trigger) {
        return scanStacks(p, inv, name, inv.getContents(), seen, trigger);
    }

    private int scanStacks(Player p, Inventory inv, String name, ItemStack[] c, Map<String, Where> seen, String trigger) {
        int found = 0;
        for (int i = 0; i < c.length; i++) {
            ItemStack s = c[i];
            String id = items.idOf(s);
            if (id == null) continue;
            ItemData d = items.data(s);
            if (d.uuid == null || d.uuid.isEmpty()) continue;
            Where here = new Where(p, inv, name, i, s, d);
            Where prev = seen.get(d.uuid);
            if (prev == null) { seen.put(d.uuid, here); continue; }
            found++;
            boolean samePlayer = prev.player().getUniqueId().equals(p.getUniqueId());
            // keep the older copy
            Where keep = prev, kill = here;
            if (here.data().created > 0 && prev.data().created > 0 && here.data().created < prev.data().created) {
                keep = here; kill = prev;
            }
            boolean delete = samePlayer ? ItemsDials.flag(ItemsDials.DUPE_DELETE, true)
                    : ItemsDials.flag(ItemsDials.DUPE_DELETE_CROSS_PLAYER, false);
            if (kill.inv() == null) delete = false;   // inside a module's container: log only (wave 5)
            String action = delete ? "deleted" : "flagged";
            if (delete) {
                kill.inv().setItem(kill.slot(), null);
                seen.put(d.uuid, keep);
            }
            report(id, d.uuid, keep, kill, action, trigger);
        }
        return found;
    }

    private void report(String itemId, String uuid, Where keep, Where kill, String action, String trigger) {
        String msg = "DUPE " + action.toUpperCase() + ": " + itemId + " uuid=" + uuid + " kept " + keep.describe()
                + ", " + action + " " + kill.describe() + " (scan: " + trigger + ")";
        items.plugin().getLogger().severe("[items] " + msg);
        for (Player op : Bukkit.getOnlinePlayers()) {
            if (op.hasPermission("skyclone.admin")) op.sendMessage(Text.c("&c&l[DUPE] &7" + msg));
        }
        String keptData = ItemDataCodec.write(keep.data()), killedData = ItemDataCodec.write(kill.data());
        String keptP = keep.player().getUniqueId().toString(), killP = kill.player().getUniqueId().toString();
        String keptW = keep.invName() + "#" + keep.slot(), killW = kill.invName() + "#" + kill.slot();
        long now = System.currentTimeMillis();
        try {
            Sky.db().transaction(c -> {
                try (var ps = c.prepareStatement("INSERT INTO items_dupe_log(at, item_id, item_uuid, kept_player, kept_where, "
                        + "other_player, other_where, kept_data, other_data, action) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                    ps.setLong(1, now); ps.setString(2, itemId); ps.setString(3, uuid);
                    ps.setString(4, keptP); ps.setString(5, keptW); ps.setString(6, killP); ps.setString(7, killW);
                    ps.setString(8, keptData); ps.setString(9, killedData); ps.setString(10, action);
                    ps.executeUpdate();
                }
                return null;
            }).exceptionally(t -> { items.plugin().getLogger().log(Level.WARNING, "[items] could not write dupe log", t); return null; });
        } catch (RuntimeException e) {
            items.plugin().getLogger().log(Level.WARNING, "[items] could not write dupe log", e);
        }
    }

    /** Last {@code n} log rows as display lines (runs on the DB thread). */
    static java.util.concurrent.CompletableFuture<List<String>> recent(int n) {
        return Sky.db().query(c -> {
            List<String> out = new ArrayList<>();
            try (var ps = c.prepareStatement("SELECT at, item_id, item_uuid, kept_player, kept_where, other_player, other_where, action "
                    + "FROM items_dupe_log ORDER BY id DESC LIMIT ?")) {
                ps.setInt(1, n);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String when = new java.text.SimpleDateFormat("MM-dd HH:mm").format(new java.util.Date(rs.getLong(1)));
                        out.add("&7" + when + " &c" + rs.getString(8) + " &f" + rs.getString(2) + " &8" + shortUuid(rs.getString(3))
                                + " &7kept " + name(rs.getString(4)) + " " + rs.getString(5)
                                + " &7other " + name(rs.getString(6)) + " " + rs.getString(7));
                    }
                }
            }
            return out;
        });
    }

    private static String shortUuid(String u) { return u == null || u.length() < 8 ? String.valueOf(u) : u.substring(0, 8); }

    private static String name(String uuid) {
        try {
            String n = Bukkit.getOfflinePlayer(java.util.UUID.fromString(uuid)).getName();
            return n == null ? shortUuid(uuid) : n;
        } catch (RuntimeException e) {
            return shortUuid(uuid);
        }
    }
}
