package com.skyclone.mod.storage;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** ItemStack <-> base64, plus the small item helpers every storage menu needs. */
final class Codec {

    private Codec() {}

    static String encode(ItemStack s) {
        if (empty(s)) return null;
        return Base64.getEncoder().encodeToString(s.serializeAsBytes());
    }

    /** Null for blank input or bytes that no longer decode (the caller keeps the raw string untouched). */
    static ItemStack decode(String b64) {
        if (b64 == null || b64.isEmpty()) return null;
        try { return ItemStack.deserializeBytes(Base64.getDecoder().decode(b64)); }
        catch (RuntimeException e) { return null; }
    }

    static boolean empty(ItemStack s) { return s == null || s.getType().isAir() || s.getAmount() <= 0; }

    static String id(ItemStack s) {
        if (empty(s)) return null;
        try {
            String id = Sky.items().idOf(s);
            return id != null ? id : Sky.items().vanillaId(s.getType());
        } catch (RuntimeException e) {
            return s.getType().name();
        }
    }

    static ItemDef def(ItemStack s) {
        String id = id(s);
        return id == null ? null : Sky.items().def(id);
    }

    /** The uuid of a unique sky item, or null. */
    static String uuid(ItemStack s) {
        if (empty(s)) return null;
        try {
            String id = Sky.items().idOf(s);
            if (id == null) return null;
            ItemDef d = Sky.items().def(id);
            if (d == null || !d.isUnique()) return null;
            return Sky.items().data(s).uuid;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Is this a plain stackable sky item (no instance data that a sack would erase)? */
    static boolean sackable(ItemStack s) {
        if (empty(s)) return false;
        String id = id(s);
        if (id == null) return false;
        ItemDef d = Sky.items().def(id);
        return d == null || !d.isUnique();
    }

    /** Put an item back into the player's inventory; what does not fit drops at their feet. Never through loot (no sack, no deletor). */
    static void giveBack(Player p, ItemStack it) {
        if (empty(it)) return;
        Map<Integer, ItemStack> left = p.getInventory().addItem(it);
        for (ItemStack rest : left.values()) p.getWorld().dropItem(p.getLocation(), rest, e -> e.setOwner(p.getUniqueId()));
    }

    /** Empty storage slots in the main inventory (36). */
    static int freeSlots(Player p) {
        int n = 0;
        for (ItemStack s : p.getInventory().getStorageContents()) if (empty(s)) n++;
        return n;
    }

    /** How many of this item (as a fresh stack {@code proto}) the main inventory can still take. */
    static int room(Player p, ItemStack proto) {
        if (empty(proto)) return 0;
        int max = Math.max(1, proto.getMaxStackSize());
        int room = 0;
        for (ItemStack s : p.getInventory().getStorageContents()) {
            if (empty(s)) room += max;
            else if (s.isSimilar(proto)) room += Math.max(0, max - s.getAmount());
        }
        return room;
    }

    /** Every uuid currently on the player (inventory, armour, offhand, cursor). */
    static Map<String, String> uuidsOnPlayer(Player p) {
        Map<String, String> out = new HashMap<>();
        PlayerInventory inv = p.getInventory();
        for (ItemStack s : inv.getContents()) put(out, s, "inventory");
        put(out, p.getItemOnCursor(), "cursor");
        return out;
    }

    private static void put(Map<String, String> out, ItemStack s, String where) {
        String u = uuid(s);
        if (u != null) out.put(u, where);
    }

    static String pretty(ItemStack s) {
        if (empty(s)) return "nothing";
        ItemDef d = def(s);
        String n = d != null && d.name != null ? d.name : s.getType().name();
        return (d != null && d.rarity != null ? d.rarity.code() : "&f") + n + (s.getAmount() > 1 ? " &8x" + s.getAmount() : "");
    }
}
