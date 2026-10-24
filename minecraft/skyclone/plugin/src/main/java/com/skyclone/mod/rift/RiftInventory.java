package com.skyclone.mod.rift;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.skyclone.api.Sky;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * The Rift's separate inventory (research 08 sec 5.1).
 *
 * <p><b>Why the player's own PersistentDataContainer:</b> it is saved in the SAME player .dat file as
 * the inventory, in the same write. Whatever moment the server crashes, the file either holds the
 * pre-swap inventory with the pre-swap stash, or the post-swap pair - never one of each, so no swap
 * can duplicate or lose an item. Storing the stash in the profile database would be a separate write
 * on a separate clock, which is exactly how inventory dupes are made.
 *
 * <p>State: {@code rift_in} = 1 while the Rift inventory is the live one; {@code rift_main} = the main
 * inventory parked while inside; {@code rift_inv} = the Rift inventory parked while outside.
 * Slot 8 (the SkyBlock Menu star, core-owned) is never moved. Items in the transfer list cross the
 * boundary with you, both ways.
 */
final class RiftInventory {

    static final NamespacedKey IN = NamespacedKey.fromString("skyclone:rift_in");
    static final NamespacedKey MAIN = NamespacedKey.fromString("skyclone:rift_main");
    static final NamespacedKey RIFT = NamespacedKey.fromString("skyclone:rift_inv");
    private static final int STAR_SLOT = 8;

    private RiftInventory() {}

    static boolean inside(Player p) {
        return p.getPersistentDataContainer().has(IN, PersistentDataType.BYTE);
    }

    /** Swap the main inventory out and the Rift inventory in. Returns the ids that came along. */
    static List<String> enter(Player p, Set<String> transferable) {
        List<String> carried = new ArrayList<>();
        if (inside(p)) return carried;
        p.closeInventory();
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        PlayerInventory inv = p.getInventory();
        ItemStack[] cur = inv.getContents();
        ItemStack[] park = new ItemStack[cur.length];
        ItemStack[] next = decode(pdc.get(RIFT, PersistentDataType.STRING), cur.length);
        List<ItemStack> moving = new ArrayList<>();
        for (int i = 0; i < cur.length; i++) {
            ItemStack it = cur[i];
            if (empty(it)) continue;
            if (i == STAR_SLOT && isStar(it)) continue;
            if (crosses(it, transferable)) moving.add(it);
            else park[i] = it;
        }
        placeInto(next, moving, park, carried);
        if (!empty(cur.length > STAR_SLOT ? cur[STAR_SLOT] : null) && isStar(cur[STAR_SLOT])) next[STAR_SLOT] = cur[STAR_SLOT];
        // one in-memory transaction: stash + flag + contents, saved together with the .dat
        pdc.set(MAIN, PersistentDataType.STRING, encode(park));
        pdc.remove(RIFT);
        pdc.set(IN, PersistentDataType.BYTE, (byte) 1);
        inv.setContents(next);
        p.updateInventory();
        return carried;
    }

    /** Swap back. Returns the ids exported from the Rift. Safe to call when not inside (no-op). */
    static List<String> exit(Player p, Set<String> exportable) {
        List<String> carried = new ArrayList<>();
        if (!inside(p)) return carried;
        p.closeInventory();
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        PlayerInventory inv = p.getInventory();
        ItemStack[] cur = inv.getContents();
        ItemStack[] park = new ItemStack[cur.length];
        ItemStack[] next = decode(pdc.get(MAIN, PersistentDataType.STRING), cur.length);
        List<ItemStack> moving = new ArrayList<>();
        for (int i = 0; i < cur.length; i++) {
            ItemStack it = cur[i];
            if (empty(it)) continue;
            if (i == STAR_SLOT && isStar(it)) continue;
            if (crosses(it, exportable)) moving.add(it);
            else park[i] = it;
        }
        placeInto(next, moving, park, carried);
        if (!empty(cur.length > STAR_SLOT ? cur[STAR_SLOT] : null) && isStar(cur[STAR_SLOT])) next[STAR_SLOT] = cur[STAR_SLOT];
        pdc.set(RIFT, PersistentDataType.STRING, encode(park));
        pdc.remove(MAIN);
        pdc.remove(IN);
        inv.setContents(next);
        p.updateInventory();
        return carried;
    }

    /** Items that fit go into empty slots of {@code target}; the rest stay parked on their side. */
    private static void placeInto(ItemStack[] target, List<ItemStack> moving, ItemStack[] park, List<String> carried) {
        for (ItemStack it : moving) {
            int slot = -1;
            for (int i = 0; i < 36 && i < target.length; i++) if (i != STAR_SLOT && empty(target[i])) { slot = i; break; }
            if (slot < 0) {
                for (int i = 0; i < park.length; i++) if (empty(park[i])) { park[i] = it; break; }
                continue;
            }
            target[slot] = it;
            String id = Sky.items().idOf(it);
            carried.add(id == null ? it.getType().name() : id);
        }
    }

    private static boolean crosses(ItemStack it, Set<String> list) {
        String id = Sky.items().idOf(it);
        return id != null && list.contains(id);
    }

    static boolean empty(ItemStack it) { return it == null || it.getType().isAir() || it.getAmount() <= 0; }

    private static boolean isStar(ItemStack it) {
        if (it == null || it.getType() != Material.NETHER_STAR || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(NamespacedKey.fromString("skyclone:sbmenu"), PersistentDataType.BYTE);
    }

    // ---- codec ------------------------------------------------------------------------------------

    static String encode(ItemStack[] items) {
        JsonArray a = new JsonArray();
        for (ItemStack it : items) a.add(empty(it) ? "" : Base64.getEncoder().encodeToString(it.serializeAsBytes()));
        return a.toString();
    }

    static ItemStack[] decode(String json, int size) {
        ItemStack[] out = new ItemStack[size];
        if (json == null || json.isEmpty()) return out;
        try {
            JsonArray a = JsonParser.parseString(json).getAsJsonArray();
            for (int i = 0; i < a.size() && i < size; i++) {
                JsonElement e = a.get(i);
                String s = e.isJsonNull() ? "" : e.getAsString();
                if (s.isEmpty()) continue;
                try { out[i] = ItemStack.deserializeBytes(Base64.getDecoder().decode(s)); }
                catch (RuntimeException ex) { Sky.log().warning("[rift] could not decode a stashed item in slot " + i + ": " + ex); }
            }
        } catch (RuntimeException ex) {
            Sky.log().warning("[rift] stashed inventory is unreadable: " + ex);
        }
        return out;
    }

    /** How many items are parked on the other side (for menus and admin info). */
    static int parkedCount(Player p) {
        String key = inside(p) ? p.getPersistentDataContainer().get(MAIN, PersistentDataType.STRING)
                : p.getPersistentDataContainer().get(RIFT, PersistentDataType.STRING);
        int n = 0;
        for (ItemStack it : decode(key, 41)) if (!empty(it)) n++;
        return n;
    }
}
