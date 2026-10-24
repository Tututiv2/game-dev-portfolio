package com.skyclone.mod.accessories;

import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Wave 5: the two accessories that are also storage, Carnival Mask Bag and New Year Cake Bag
 * ({@code keep_bag} effects). Right-click the bag in your hand (or {@code /maskbag}, {@code /cakebag}) to open it;
 * every DIFFERENT mask / cake year inside grants its stat while the bag counts (Accessory Bag or inventory).
 * Contents are stored in {@link AccData#keepBags}, saved on every change like the Accessory Bag.
 */
final class KeepBags {

    static final String MASK = "mask", CAKE = "cake";
    static final String MASK_BAG = "CARNIVAL_MASK_BAG", CAKE_BAG = "NEW_YEAR_CAKE_BAG";

    private final Bags bags;

    KeepBags(Bags bags) { this.bags = bags; }

    static String bagId(String kind) { return MASK.equals(kind) ? MASK_BAG : CAKE_BAG; }

    static String title(String kind) { return MASK.equals(kind) ? "Carnival Mask Bag" : "New Year Cake Bag"; }

    // ---- what fits, and what counts once ---------------------------------------------------------

    /** Does this item belong in that bag? Masks carry {@code extra.carnivalMask} (mayors); cakes are NEW_YEAR_CAKE. */
    static boolean accepts(String kind, ItemStack it) {
        String id = Sky.items().idOf(it);
        if (id == null) return false;
        if (CAKE.equals(kind)) return "NEW_YEAR_CAKE".equals(id);
        ItemDef def = Sky.items().def(id);
        return def != null && def.extra != null && def.extra.has("carnivalMask") && def.extra.get("carnivalMask").getAsBoolean();
    }

    /** What makes a stored item "different": the mask id, or the cake's year (a cake without one counts as its id). */
    static String key(String kind, ItemStack it) {
        String id = Sky.items().idOf(it);
        if (CAKE.equals(kind)) {
            try {
                JsonObject x = Sky.items().data(it).extra;
                if (x != null && x.has("year")) return "year:" + x.get("year").getAsInt();
            } catch (RuntimeException ignored) { }
        }
        return id;
    }

    /** Distinct keys, leaving out any in {@code exclude}. Pure (unit-tested). */
    static int distinct(Collection<String> keys, Set<String> exclude) {
        Set<String> s = new HashSet<>();
        for (String k : keys) if (k != null && (exclude == null || !exclude.contains(k))) s.add(k);
        return s.size();
    }

    /** The distinct keys inside one bag, decoded once per change and cached in the runtime state. */
    Set<String> keys(Player p, String kind) {
        Bags.State st = bags.state(p);
        Set<String> cached = st.keepKeys.get(kind);
        if (cached != null) return cached;
        Set<String> out = new TreeSet<>();
        for (String b64 : stored(p, kind).values()) {
            ItemStack it = Bags.decode(b64);
            if (it != null) out.add(key(kind, it));
        }
        st.keepKeys.put(kind, out);
        return out;
    }

    /**
     * How many different items in this bag earn the bonus. Masks the mayors module already counts from the
     * inventory and helmet (its CarnivalMasks provider, while this bag is counted) are left out here, so a mask is
     * never paid twice; when mayors stops counting the inventory this is the whole bonus.
     */
    int bonusCount(Player p, String kind) {
        Set<String> keys = keys(p, kind);
        if (keys.isEmpty()) return 0;
        Set<String> exclude = null;
        if (MASK.equals(kind)) {
            exclude = new HashSet<>();
            for (ItemStack it : p.getInventory().getStorageContents()) {
                if (it != null && accepts(MASK, it)) exclude.add(Sky.items().idOf(it));
            }
            ItemStack head = p.getInventory().getHelmet();
            if (head != null && accepts(MASK, head)) exclude.add(Sky.items().idOf(head));
        }
        return distinct(keys, exclude);
    }

    static Map<String, String> stored(Player p, String kind) {
        return Bags.data(p).keepBags.computeIfAbsent(kind, k -> new HashMap<>());
    }

    // ---- the menu --------------------------------------------------------------------------------

    void open(Player p, String kind) {
        if (!Bags.ready(p)) { Msg.error(p, "Your profile is still loading, try again in a moment."); return; }
        int slots = Math.max(9, Math.min(45, AccDials.KEEP_BAG_SLOTS.get()));
        Menu m = new Menu(title(kind), 6).keepFreeSlotItems();
        Map<String, String> data = stored(p, kind);
        for (int i = 0; i < 45; i++) {
            if (i < slots) {
                m.free(i);
                ItemStack it = Bags.decode(data.get(String.valueOf(i)));
                m.getInventory().setItem(i, it);
            } else {
                m.set(i, Icons.of(Material.GRAY_STAINED_GLASS_PANE, "&7"));
            }
        }
        for (int i = 45; i < 54; i++) m.set(i, Icons.filler());
        drawInfo(m, p, kind);
        m.closeButton();
        m.onFreeSlotChange(c -> {
            Player pl = c.player();
            if (sync(pl, m, kind) && BagMenus.isOpen(pl, m)) drawInfo(m, pl, kind);
        });
        m.onClose(pl -> {
            sync(pl, m, kind);
            try { Sky.profiles().saveNow(pl); } catch (RuntimeException ignored) { }
        });
        m.open(p);
    }

    private void drawInfo(Menu m, Player p, String kind) {
        Set<String> keys = keys(p, kind);
        int bonus = bonusCount(p, kind);
        List<String> lore = new ArrayList<>();
        if (MASK.equals(kind)) {
            lore.add("&7Each different carnival mask inside");
            lore.add("&7grants &b+1 ✎ Intelligence&7.");
            lore.add("");
            lore.add("&7Different masks: &a" + keys.size());
            lore.add("&7Current bonus: &b+" + bonus + " ✎ Intelligence");
            if (bonus < keys.size()) lore.add("&8(masks you also carry count once)");
        } else {
            lore.add("&7Each New Year cake from a different");
            lore.add("&7year grants &c+1 ❤ Health&7.");
            lore.add("");
            lore.add("&7Different years: &a" + keys.size());
            lore.add("&7Current bonus: &c+" + bonus + " ❤ Health");
        }
        lore.add("");
        lore.add("&7The bonus counts while the bag is");
        lore.add("&7in your Accessory Bag.");
        m.set(49, Icons.of(MASK.equals(kind) ? Material.CARVED_PUMPKIN : Material.CAKE, "&a" + title(kind), lore));
    }

    /** Persist the open menu's free slots; anything that does not belong goes back. True when the contents changed. */
    boolean sync(Player p, Menu m, String kind) {
        if (!Bags.ready(p)) return false;
        Map<String, String> data = stored(p, kind);
        boolean changed = false;
        for (int i : new ArrayList<>(m.freeSlots())) {
            ItemStack it = m.getInventory().getItem(i);
            if (it != null && it.getType().isAir()) it = null;
            if (it != null && !accepts(kind, it)) {
                m.getInventory().setItem(i, null);
                BagMenus.giveBack(p, it);
                Msg.error(p, MASK.equals(kind) ? "Only carnival masks fit in this bag." : "Only New Year cakes fit in this bag.");
                it = null;
            } else if (it != null && it.getAmount() > 1) {
                ItemStack rest = it.clone();
                rest.setAmount(it.getAmount() - 1);
                it.setAmount(1);
                m.getInventory().setItem(i, it);
                BagMenus.giveBack(p, rest);
            }
            String slot = String.valueOf(i);
            String enc = Bags.encode(it);
            String cur = data.get(slot);
            if (enc == null ? cur != null : !enc.equals(cur)) {
                if (enc == null) data.remove(slot); else data.put(slot, enc);
                changed = true;
            }
        }
        if (changed) {
            bags.state(p).keepKeys.remove(kind);
            Sky.stats().invalidate(p);
        }
        return changed;
    }
}
