package com.skyclone.mod.storage;

import com.google.gson.JsonElement;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link Features.Sacks}: per-item counts, capacity summed over every sack the player carries or keeps in
 * the Sack of Sacks, arrows routed to the quiver. Everything here runs on the main thread.
 */
final class SackService implements Features.Sacks {

    static final String LININGS = "sack_linings";

    private final StorageModule mod;
    /** Capacity per family from the Sack of Sacks only (decoding it on every pickup would be wasteful). */
    private final Map<UUID, Map<String, Long>> bagCaps = new ConcurrentHashMap<>();
    /** Items absorbed since the last "[Sacks] +N items" line. */
    private final Map<UUID, Map<String, Long>> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSummary = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastFull = new ConcurrentHashMap<>();

    SackService(StorageModule mod) { this.mod = mod; }

    void forget(UUID id) {
        bagCaps.remove(id);
        pending.remove(id);
        lastSummary.remove(id);
        lastFull.remove(id);
    }

    void invalidate(Player p) { bagCaps.remove(p.getUniqueId()); }

    // ---- the "[Sacks]" summary toggle ----------------------------------------------------------
    // The Settings menu (menus module) owns a "Sacks Messages" toggle, key chat.sacks, read by us and mirrored to
    // the player's PDC. A legal PDC value wins; our own member flag is the fallback when the menus module is absent.

    private static final org.bukkit.NamespacedKey SETTING = new org.bukkit.NamespacedKey("skyclone", "setting.chat.sacks");

    boolean summaryOn(Player p) {
        try {
            String v = p.getPersistentDataContainer().get(SETTING, org.bukkit.persistence.PersistentDataType.STRING);
            if ("true".equals(v)) return true;
            if ("false".equals(v)) return false;
        } catch (RuntimeException ignored) { }
        return mod.store().sacks(p).summary;
    }

    /** Our /sacks toggle writes both, so the Settings menu shows the same answer. */
    void setSummary(Player p, boolean on) {
        mod.store().sacks(p).summary = on;
        try { p.getPersistentDataContainer().set(SETTING, org.bukkit.persistence.PersistentDataType.STRING, String.valueOf(on)); }
        catch (RuntimeException ignored) { }
    }

    // ---- capacity -------------------------------------------------------------------------------

    static int linings(ItemStack sack) {
        try {
            JsonElement e = Sky.items().data(sack).extra.get(LININGS);
            return e == null ? 0 : e.getAsInt();
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    long sackCapacity(ItemStack sack) {
        Catalog.Sack s = mod.catalog().sacks.get(Codec.id(sack));
        if (s == null) return 0;
        return StorageMath.sackCapacity(s.capacity(), linings(sack), StorageDials.LINING_MAX.get(), StorageDials.LINING_BONUS.get());
    }

    private Map<String, Long> bagCaps(Player p) {
        return bagCaps.computeIfAbsent(p.getUniqueId(), k -> {
            Map<String, Long> m = new HashMap<>();
            for (ItemStack s : Store.decodeAll(mod.store().sacks(p).bag)) add(m, s);
            return m;
        });
    }

    private void add(Map<String, Long> caps, ItemStack s) {
        Catalog.Sack info = mod.catalog().sacks.get(Codec.id(s));
        if (info == null) return;
        caps.merge(info.family(), sackCapacity(s), Long::sum);
    }

    /** Capacity per family: Sack of Sacks + (dial) sacks in the inventory. */
    Map<String, Long> familyCaps(Player p) {
        Map<String, Long> out = new HashMap<>(bagCaps(p));
        if (StorageDials.SACK_INVENTORY.get()) {
            for (ItemStack s : p.getInventory().getStorageContents()) {
                if (Codec.empty(s)) continue;
                String id = Sky.items().idOf(s);
                if (id != null && mod.catalog().isSack(id)) add(out, s);
            }
        }
        return out;
    }

    /** Number of sacks of each family the player has active. */
    Map<String, Integer> familyCounts(Player p) {
        Map<String, Integer> out = new HashMap<>();
        List<ItemStack> all = new ArrayList<>(Store.decodeAll(mod.store().sacks(p).bag));
        if (StorageDials.SACK_INVENTORY.get()) for (ItemStack s : p.getInventory().getStorageContents()) if (!Codec.empty(s)) all.add(s);
        for (ItemStack s : all) {
            Catalog.Sack info = mod.catalog().sacks.get(Codec.id(s));
            if (info != null) out.merge(info.family(), 1, Integer::sum);
        }
        return out;
    }

    long stored(Player p, String id) {
        Long v = mod.store().sacks(p).stored.get(id);
        return v == null ? 0 : v;
    }

    /** Capacity left for one item right now (in items of that id). */
    long room(Player p, String id) {
        return fitsNow(p, id, Long.MAX_VALUE / 8, familyCaps(p));
    }

    /** Capacity for this item, in its own units (gems: per gem type, in rough units). Sack Size applied. */
    long capacity(Player p, String id, Map<String, Long> caps) {
        long cap = 0;
        for (Catalog.Family f : mod.catalog().holdersOf(id)) cap += caps.getOrDefault(f.id(), 0L);
        return StorageMath.withPercent(cap, sackSizePct(p));
    }

    /** A family's capacity as the menus show it: summed sacks, then Sack Size. */
    long shownCapacity(Player p, long rawFamilyCap) { return StorageMath.withPercent(rawFamilyCap, sackSizePct(p)); }

    /**
     * Wave 5: the Hunting attribute <b>Sack Size</b> (NEU {@code ATTRIBUTE_SHARD_SACK_SIZE}, "Your sacks can hold +1%
     * more items" per level). Hunting answers {@code value:sack_size} = level x per-level x echo, i.e. the percentage.
     */
    double sackSizePct(Player p) {
        if (p == null || !StorageDials.SACK_SIZE_ATTRIBUTE.get()) return 0;
        try {
            double v = Sky.service(Features.Hunting.class).attributeLevel(p, "value:sack_size");
            return Double.isFinite(v) ? Math.max(0, v) : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private long fitsNow(Player p, String id, long amount, Map<String, Long> caps) {
        List<Catalog.Family> fams = mod.catalog().holdersOf(id);
        if (fams.isEmpty()) return 0;
        long cap = capacity(p, id, caps);
        for (Catalog.Family f : fams) {
            Catalog.Weight w = f.weights().get(id);
            if (w == null) continue;
            long used = 0;
            for (Map.Entry<String, Catalog.Weight> en : f.weights().entrySet()) {
                if (en.getValue().group().equals(w.group())) used += stored(p, en.getKey()) * en.getValue().weight();
            }
            return StorageMath.fits(cap, used, amount, w.weight());
        }
        return StorageMath.fits(cap, stored(p, id), amount, 1);
    }

    /** Stored "used" figure for display: the item count, or for a gem the whole gem type in rough units. */
    long usedFor(Player p, String id) {
        for (Catalog.Family f : mod.catalog().holdersOf(id)) {
            Catalog.Weight w = f.weights().get(id);
            if (w == null) continue;
            long used = 0;
            for (Map.Entry<String, Catalog.Weight> en : f.weights().entrySet()) {
                if (en.getValue().group().equals(w.group())) used += stored(p, en.getKey()) * en.getValue().weight();
            }
            return used;
        }
        return stored(p, id);
    }

    // ---- Features.Sacks ---------------------------------------------------------------------------

    @Override
    public int count(Player p, String itemId) {
        if (!Store.ready(p) || itemId == null) return 0;
        long n = stored(p, itemId);
        if (mod.catalog().isArrow(itemId)) n += mod.bagMenus().quiverCount(p, itemId);
        return StorageMath.clampInt(n);
    }

    @Override
    public int remove(Player p, String itemId, int amount) {
        if (!Store.ready(p) || itemId == null || amount <= 0) return 0;
        Map<String, Long> st = mod.store().sacks(p).stored;
        long have = st.getOrDefault(itemId, 0L);
        long take = Math.min(have, amount);
        if (take > 0) {
            if (have - take <= 0) st.remove(itemId); else st.put(itemId, have - take);
        }
        int got = (int) take;
        if (got < amount && mod.catalog().isArrow(itemId)) got += mod.bagMenus().quiverRemove(p, itemId, amount - got);
        return got;
    }

    @Override
    public int add(Player p, String itemId, int amount) {
        if (!Store.ready(p) || itemId == null || amount <= 0) return 0;
        int done = 0;
        if (mod.catalog().isArrow(itemId)) {
            done += mod.bagMenus().quiverAdd(p, itemId, amount);
            if (done >= amount) return done;
        }
        long fit = fitsNow(p, itemId, amount - done, familyCaps(p));
        if (fit > 0) {
            mod.store().sacks(p).stored.merge(itemId, fit, Long::sum);
            done += (int) fit;
        }
        return done;
    }

    @Override
    public void openMenu(Player p) { mod.sackMenus().openSacks(p, 0); }

    // ---- absorbing -------------------------------------------------------------------------------

    /**
     * Try to put {@code amount} of {@code id} into sacks (and arrows into the quiver). Returns how many were
     * taken. Only plain stackable items: a unique item never loses its instance data to a sack.
     */
    int absorb(Player p, ItemStack proto, String id, int amount) {
        if (!Store.ready(p) || id == null || amount <= 0 || !mod.store().sacks(p).autoPickup) return 0;
        if (proto != null && !Codec.sackable(proto)) return 0;
        int taken = 0;
        if (mod.catalog().isArrow(id) && StorageDials.QUIVER_ABSORB.get()) {
            taken += mod.bagMenus().quiverAdd(p, id, amount);
        }
        if (taken < amount && mod.catalog().sackable(id)) {
            long fit = fitsNow(p, id, amount - taken, familyCaps(p));
            if (fit > 0) {
                mod.store().sacks(p).stored.merge(id, fit, Long::sum);
                taken += (int) fit;
                pending.computeIfAbsent(p.getUniqueId(), k -> new LinkedHashMap<>()).merge(id, fit, Long::sum);
            }
            if (taken < amount && capacity(p, id, familyCaps(p)) > 0) full(p, id);
        }
        return taken;
    }

    private void full(Player p, String id) {
        long now = System.currentTimeMillis();
        Long last = lastFull.get(p.getUniqueId());
        if (last != null && now - last < Math.max(1, StorageDials.SACK_FULL_SECONDS.get()) * 1000L) return;
        lastFull.put(p.getUniqueId(), now);
        ItemDef d = Sky.items().def(id);
        Msg.send(p, "&cYour sacks are full of " + (d == null ? id : d.rarity.code() + d.name) + "&c!");
    }

    /** Called every second: send each player's "[Sacks] +N items" line when its interval has passed. */
    void flushSummaries() {
        int sec = StorageDials.SACK_SUMMARY_SECONDS.get();
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Map<String, Long>> en : new ArrayList<>(pending.entrySet())) {
            Player p = org.bukkit.Bukkit.getPlayer(en.getKey());
            if (p == null || !p.isOnline()) { pending.remove(en.getKey()); continue; }
            Long last = lastSummary.get(en.getKey());
            if (sec > 0 && last != null && now - last < sec * 1000L) continue;
            Map<String, Long> got = pending.remove(en.getKey());
            lastSummary.put(en.getKey(), now);
            if (got == null || got.isEmpty() || sec <= 0 || !Store.ready(p) || !summaryOn(p)) continue;
            long total = 0;
            for (long v : got.values()) total += v;
            List<Component> hover = new ArrayList<>();
            hover.add(Text.c("&aAdded items:"));
            int shown = 0;
            for (Map.Entry<String, Long> g : got.entrySet()) {
                if (shown++ >= 12) { hover.add(Text.c("&8...and " + (got.size() - 12) + " more")); break; }
                ItemDef d = Sky.items().def(g.getKey());
                hover.add(Text.c(" &a+" + Text.commasInt(g.getValue()) + " " + (d == null ? "&f" + g.getKey() : d.rarity.code() + d.name)
                        + " &8(" + Text.commasInt(stored(p, g.getKey())) + ")"));
            }
            hover.add(Text.c("&eClick /sacks to open your sacks."));
            Component msg = Text.c("&6[Sacks] &a+" + Text.commasInt(total) + " &eitem" + (total == 1 ? "" : "s") + "&e. &8(Last "
                    + Math.max(1, sec) + "s.)").hoverEvent(HoverEvent.showText(Component.join(
                    net.kyori.adventure.text.JoinConfiguration.newlines(), hover)));
            p.sendMessage(msg);
        }
    }

    // ---- moving items in and out ------------------------------------------------------------------

    /** Take up to {@code stacks} stacks of one item out into the inventory. Returns how many items moved. */
    int takeOut(Player p, String id, int stacks) {
        if (!Sky.items().exists(id)) return 0;
        int max = Math.max(1, Sky.items().create(id, 1).getMaxStackSize());
        return takeAmount(p, id, (long) Math.max(1, stacks) * max);
    }

    /** Take up to {@code amount} of one item out into the inventory (capped by room). Returns how many items moved. */
    int takeAmount(Player p, String id, long amount) {
        long have = stored(p, id);
        if (have <= 0 || amount <= 0 || !Sky.items().exists(id)) return 0;
        ItemStack proto = Sky.items().create(id, 1);
        int max = Math.max(1, proto.getMaxStackSize());
        long want = Math.min(have, amount);
        int room = Codec.room(p, proto);
        int n = (int) Math.min(want, room);
        if (n <= 0) { Msg.error(p, "Your inventory is full!"); return 0; }
        int moved = 0;
        for (int part : StorageMath.stacks(n, max)) {
            ItemStack s = Sky.items().create(id, part);
            Map<Integer, ItemStack> rest = p.getInventory().addItem(s);
            int r = 0;
            for (ItemStack x : rest.values()) r += x.getAmount();
            moved += part - r;
            if (r > 0) break;
        }
        Map<String, Long> st = mod.store().sacks(p).stored;
        long after = have - moved;
        if (after <= 0) st.remove(id); else st.put(id, after);
        return moved;
    }

    /** Move every item the sacks accept from the inventory into them. {@code onlyFamily} null = all families. Returns items moved. */
    long insertInventory(Player p, String onlyFamily) {
        long moved = 0;
        ItemStack[] inv = p.getInventory().getStorageContents();
        Map<String, Long> caps = familyCaps(p);
        for (int i = 0; i < inv.length; i++) {
            ItemStack s = inv[i];
            if (Codec.empty(s) || !Codec.sackable(s)) continue;
            String id = Codec.id(s);
            List<Catalog.Family> fams = mod.catalog().holdersOf(id);
            if (fams.isEmpty()) continue;
            if (onlyFamily != null && fams.stream().noneMatch(f -> f.id().equals(onlyFamily))) continue;
            long fit = fitsNow(p, id, s.getAmount(), caps);
            if (fit <= 0) continue;
            mod.store().sacks(p).stored.merge(id, fit, Long::sum);
            moved += fit;
            if (fit >= s.getAmount()) p.getInventory().setItem(i, null);
            else { s.setAmount((int) (s.getAmount() - fit)); p.getInventory().setItem(i, s); }
        }
        return moved;
    }
}
