package com.skyclone.mod.storage;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-dupe for everything the storage module holds. Every unique item carries a uuid; this keeps, per
 * online player, which storage container holds which uuid, and refuses a NEW uuid into a container when
 * the same uuid is already in another container or still on the player. A refused copy is deleted (never
 * handed back: one copy already exists) and logged for admins.
 *
 * <p>Container keys: {@code ec:<page>}, {@code bp:<slot>} (contents), {@code bpitems} (the backpacks
 * themselves), {@code wd} (wardrobe), {@code wdeq} (Equipment Wardrobe), {@code sackbag}, {@code quiver}, {@code vault}.
 */
final class Guard {

    private final Map<UUID, Map<String, String>> index = new ConcurrentHashMap<>();
    private final StorageModule mod;

    Guard(StorageModule mod) { this.mod = mod; }

    void forget(UUID player) { index.remove(player); }

    /** Lazily build the player's index by decoding every container once per session. */
    private Map<String, String> index(Player p) {
        return index.computeIfAbsent(p.getUniqueId(), k -> {
            Map<String, String> m = new HashMap<>();
            for (Map.Entry<String, List<ItemStack>> en : mod.store().allContainers(p).entrySet()) {
                for (ItemStack s : en.getValue()) {
                    String u = Codec.uuid(s);
                    if (u != null) m.putIfAbsent(u, en.getKey());
                }
            }
            return m;
        });
    }

    /**
     * May this unique item enter {@code container}? False = it is a duplicate (already in another container
     * or on the player); the caller must delete this copy.
     */
    boolean allowNew(Player p, String container, ItemStack it, Map<String, String> seenThisPass) {
        if (!StorageDials.DUPE_GUARD.get()) return true;
        String u = Codec.uuid(it);
        if (u == null) return true;
        if (seenThisPass.containsKey(u)) return reject(p, container, it, u, "twice in " + container);
        seenThisPass.put(u, container);
        String other = index(p).get(u);
        if (other != null && !other.equals(container)) return reject(p, container, it, u, "already in " + other);
        String on = Codec.uuidsOnPlayer(p).get(u);
        if (on != null) return reject(p, container, it, u, "also in the player's " + on);
        return true;
    }

    private boolean reject(Player p, String container, ItemStack it, String u, String why) {
        String line = "[storage] DUPE refused for " + p.getName() + ": " + Codec.id(it) + " uuid " + u + " into " + container + " (" + why + ")";
        Sky.log().warning(line);
        for (Player op : Bukkit.getOnlinePlayers()) {
            if (op.hasPermission("skyclone.admin")) Msg.send(op, "&c" + line);
        }
        return false;
    }

    /** Replace what the index knows about one container with its current contents. */
    void reindex(Player p, String container, Collection<ItemStack> contents) {
        Map<String, String> m = index(p);
        m.values().removeIf(container::equals);
        for (ItemStack s : contents) {
            String u = Codec.uuid(s);
            if (u != null) m.putIfAbsent(u, container);
        }
    }

    /** Full scan: every uuid that appears more than once across storage + the player. uuid -> places. */
    Map<String, List<String>> scan(Player p) {
        Map<String, List<String>> seen = new LinkedHashMap<>();
        for (Map.Entry<String, List<ItemStack>> en : mod.store().allContainers(p).entrySet()) {
            for (ItemStack s : en.getValue()) {
                String u = Codec.uuid(s);
                if (u != null) seen.computeIfAbsent(u, k -> new ArrayList<>()).add(en.getKey() + ":" + Codec.id(s));
            }
        }
        for (ItemStack s : p.getInventory().getContents()) {
            String u = Codec.uuid(s);
            if (u != null) seen.computeIfAbsent(u, k -> new ArrayList<>()).add("inventory:" + Codec.id(s));
        }
        seen.values().removeIf(l -> l.size() < 2);
        return seen;
    }
}
