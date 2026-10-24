package com.skyclone.mod.storage;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Data access (always re-fetched: never keep a member object across ticks), the unlock rules, and the
 * generic "a menu whose free slots ARE a stored container" helper every storage menu is built on.
 */
final class Store {

    private final StorageModule mod;

    Store(StorageModule mod) { this.mod = mod; }

    static boolean ready(Player p) {
        try { return p != null && p.isOnline() && Sky.profiles().isReady(p); }
        catch (RuntimeException e) { return false; }
    }

    // ---- data ---------------------------------------------------------------------------------

    StorageData.Ender ender(Player p) { return Sky.profiles().member(p, StorageData.ENDER, StorageData.Ender.class); }
    StorageData.Backpacks backpacks(Player p) { return Sky.profiles().member(p, StorageData.BACKPACKS, StorageData.Backpacks.class); }
    StorageData.Wardrobe wardrobe(Player p) { return Sky.profiles().member(p, StorageData.WARDROBE, StorageData.Wardrobe.class); }
    StorageData.Sacks sacks(Player p) { return Sky.profiles().member(p, StorageData.SACKS, StorageData.Sacks.class); }
    StorageData.Bags bags(Player p) { return Sky.profiles().member(p, StorageData.BAGS, StorageData.Bags.class); }

    Map<String, String> ecPage(Player p, int page) { return ender(p).pages.computeIfAbsent(String.valueOf(page), k -> new HashMap<>()); }
    Map<String, String> bpContents(Player p, int slot) { return backpacks(p).contents.computeIfAbsent(String.valueOf(slot), k -> new HashMap<>()); }
    Map<String, String> wardrobeSet(Player p, int slot) { return wardrobe(p).sets.computeIfAbsent(String.valueOf(slot), k -> new HashMap<>()); }

    ItemStack backpackItem(Player p, int slot) { return Codec.decode(backpacks(p).items.get(String.valueOf(slot))); }

    int backpackCapacity(Player p, int slot) {
        String b64 = backpacks(p).items.get(String.valueOf(slot));
        if (b64 == null) return 0;
        ItemStack it = Codec.decode(b64);
        return it == null ? 0 : mod.catalog().backpackSlots(Codec.id(it));
    }

    static List<ItemStack> decodeAll(Map<String, String> m) {
        List<ItemStack> out = new ArrayList<>();
        if (m == null) return out;
        for (String v : m.values()) {
            ItemStack s = Codec.decode(v);
            if (s != null) out.add(s);
        }
        return out;
    }

    static int used(Map<String, String> m) { return m == null ? 0 : m.size(); }

    /** Every container, decoded, keyed by its guard key. */
    Map<String, List<ItemStack>> allContainers(Player p) {
        Map<String, List<ItemStack>> out = new LinkedHashMap<>();
        if (!ready(p)) return out;
        for (Map.Entry<String, Map<String, String>> en : ender(p).pages.entrySet()) out.put("ec:" + en.getKey(), decodeAll(en.getValue()));
        StorageData.Backpacks b = backpacks(p);
        out.put("bpitems", decodeAll(b.items));
        for (Map.Entry<String, Map<String, String>> en : b.contents.entrySet()) out.put("bp:" + en.getKey(), decodeAll(en.getValue()));
        List<ItemStack> wd = new ArrayList<>();
        for (Map<String, String> set : wardrobe(p).sets.values()) wd.addAll(decodeAll(set));
        out.put("wd", wd);
        List<ItemStack> eq = new ArrayList<>();
        for (Map<String, String> set : wardrobe(p).eqSets.values()) eq.addAll(decodeAll(set));
        out.put("wdeq", eq);
        out.put("sackbag", decodeAll(sacks(p).bag));
        StorageData.Bags bags = bags(p);
        out.put("quiver", decodeAll(bags.quiver));
        out.put("vault", decodeAll(bags.vault));
        return out;
    }

    /**
     * Core's dupe-scan source (wave 5, {@code ItemService.addDupeScanSource("storage", ...)}): every stack stored in
     * every container. A storage menu the player has open is flushed first, so an item just taken out of a backpack
     * into the inventory is not reported as a copy of its own stale stored record. Main thread only.
     */
    List<ItemStack> allStacks(Player p) {
        List<ItemStack> out = new ArrayList<>();
        if (!ready(p)) return out;
        mod.flushOpen(p);
        for (List<ItemStack> l : allContainers(p).values()) out.addAll(l);
        return out;
    }

    /** Every stored item as its raw base64, per container (same keys as {@link #allContainers}). */
    Map<String, List<String>> rawContainers(Player p) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (!ready(p)) return out;
        for (Map.Entry<String, Map<String, String>> en : ender(p).pages.entrySet()) out.put("ec:" + en.getKey(), new ArrayList<>(en.getValue().values()));
        StorageData.Backpacks b = backpacks(p);
        out.put("bpitems", new ArrayList<>(b.items.values()));
        for (Map.Entry<String, Map<String, String>> en : b.contents.entrySet()) out.put("bp:" + en.getKey(), new ArrayList<>(en.getValue().values()));
        List<String> wd = new ArrayList<>();
        for (Map<String, String> set : wardrobe(p).sets.values()) wd.addAll(set.values());
        out.put("wd", wd);
        List<String> eq = new ArrayList<>();
        for (Map<String, String> set : wardrobe(p).eqSets.values()) eq.addAll(set.values());
        out.put("wdeq", eq);
        out.put("sackbag", new ArrayList<>(sacks(p).bag.values()));
        StorageData.Bags bags = bags(p);
        out.put("quiver", new ArrayList<>(bags.quiver.values()));
        out.put("vault", new ArrayList<>(bags.vault.values()));
        return out;
    }

    /** Decoded UNIQUE stacks by their stored bytes; {@link #NOT_UNIQUE} for anything else. Bounded LRU. */
    private static final Object NOT_UNIQUE = new Object();
    private final Map<String, Object> uniqueCache = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Object> e) { return size() > 20_000; }
            });

    /**
     * What core's dupe scan needs (wave 5): only the stored items that carry a uuid. Every 30 s for every online
     * player, so decoding ~1,200 stored stacks each time would be wasteful; stored bytes never change in place, so
     * each distinct base64 string is decoded once and remembered (stackables as "not unique"). An open storage menu is
     * flushed first, so an item just taken out is not reported as a copy of its own stale record. Main thread only.
     */
    List<ItemStack> uniqueStacks(Player p) {
        List<ItemStack> out = new ArrayList<>();
        if (!ready(p)) return out;
        mod.flushOpen(p);
        for (List<String> l : rawContainers(p).values()) {
            for (String raw : l) {
                if (raw == null) continue;
                Object s = uniqueCache.get(raw);
                if (s == null) {
                    ItemStack d = Codec.decode(raw);
                    s = d != null && Codec.uuid(d) != null ? d : NOT_UNIQUE;
                    uniqueCache.put(raw, s);
                }
                if (s instanceof ItemStack it) out.add(it);
            }
        }
        return out;
    }

    int uniqueCacheSize() { return uniqueCache.size(); }

    // ---- unlocks ------------------------------------------------------------------------------

    static int sbLevel(Player p) {
        try { return Sky.service(Features.SkyLevel.class).level(p); } catch (RuntimeException e) { return 0; }
    }

    static int collectionTier(Player p, String coll) {
        try { return Sky.service(Features.Collections.class).tier(p, coll); } catch (RuntimeException e) { return 0; }
    }

    static boolean check(Player p, String key) {
        if (key == null || key.isBlank() || key.equals("always")) return true;
        try { return Sky.requirements().check(p, key); } catch (RuntimeException e) { return false; }
    }

    static String describe(String key) {
        try { return Sky.requirements().describe(key); } catch (RuntimeException e) { return key; }
    }

    int ecPages() { return StorageMath.clamp(StorageDials.EC_PAGES.get(), 1, 9); }

    int ecPageSize(int page) { return StorageMath.enderPageSize(page, ecPages(), StorageDials.EC_LAST_PAGE.get()); }

    int ecPageLevel(int page) {
        int[] t = StorageMath.thresholds(StorageDials.EC_PAGE_LEVELS.get());
        return page < t.length ? t[page] : Integer.MAX_VALUE;
    }

    boolean ecPageOpen(Player p, int page) {
        if (page < 0 || page >= ecPages()) return false;
        if (page == 0 || sbLevel(p) >= ecPageLevel(page)) return true;
        int shop = shopTier(p, StorageDials.EC_SHOP_UPGRADE.get(), 8);
        return shop > 0 && page < StorageMath.openCount(StorageMath.thresholds(StorageDials.EC_PAGE_LEVELS.get()), sbLevel(p), shop, ecPages());
    }

    /** The level shown on a locked page: what opens it given the Community Shop tier already owned. */
    int ecPageLevelNeeded(Player p, int page) {
        return StorageMath.levelFor(StorageMath.thresholds(StorageDials.EC_PAGE_LEVELS.get()), page,
                shopTier(p, StorageDials.EC_SHOP_UPGRADE.get(), 8));
    }

    // ---- Community Shop account upgrades (wave 5) ---------------------------------------------------

    /**
     * The tier of a Community Shop upgrade the player owns (bank module, requirement {@code cshop:<id>:<tier>}),
     * 0 when the id is blank, the bank module is absent or nothing is owned.
     */
    static int shopTier(Player p, String upgradeId, int maxTier) {
        if (upgradeId == null || upgradeId.isBlank()) return 0;
        try {
            if (!Sky.requirements().known("cshop")) return 0;
            for (int n = maxTier; n >= 1; n--) if (Sky.requirements().check(p, "cshop:" + upgradeId.trim() + ":" + n)) return n;
        } catch (RuntimeException ignored) { }
        return 0;
    }

    int ecPagesOpen(Player p) {
        int n = 0;
        for (int i = 0; i < ecPages(); i++) if (ecPageOpen(p, i)) n++;
        return n;
    }

    int bpSlots() { return StorageMath.clamp(StorageDials.BP_SLOTS.get(), 1, 18); }

    int bpSouls(int slot) {
        int[] t = StorageMath.thresholds(StorageDials.BP_SOULS.get());
        return slot < t.length ? t[slot] : Integer.MAX_VALUE;
    }

    boolean bpSlotOpen(Player p, int slot) {
        if (slot < 0 || slot >= bpSlots()) return false;
        if (slot < StorageDials.BP_FREE.get()) return true;
        int souls = bpSouls(slot);
        if (souls <= 0) return true;
        if (souls == Integer.MAX_VALUE) return false;
        // A slot that already holds a backpack stays usable even if the rule later tightens.
        if (backpacks(p).items.containsKey(String.valueOf(slot))) return true;
        return check(p, "fairysouls:" + souls);
    }

    int wdSlots() { return 18; }

    int wdSlotLevel(int slot) {
        int[] t = StorageMath.thresholds(StorageDials.WD_SLOT_LEVELS.get());
        return slot < t.length ? t[slot] : Integer.MAX_VALUE;
    }

    boolean wdSlotOpen(Player p, int slot) {
        if (slot < 0 || slot >= wdSlots()) return false;
        if (!wardrobe(p).sets.getOrDefault(String.valueOf(slot), Map.of()).isEmpty()) return true;   // never strand stored armour
        if (sbLevel(p) >= wdSlotLevel(slot)) return true;
        int shop = shopTier(p, StorageDials.WD_SHOP_UPGRADE.get(), 9);
        return shop > 0 && slot < StorageMath.openCount(StorageMath.thresholds(StorageDials.WD_SLOT_LEVELS.get()), sbLevel(p), shop, wdSlots());
    }

    /** The level shown on a locked armour slot, Community Shop tier taken into account. */
    int wdSlotLevelNeeded(Player p, int slot) {
        return StorageMath.levelFor(StorageMath.thresholds(StorageDials.WD_SLOT_LEVELS.get()), slot,
                shopTier(p, StorageDials.WD_SHOP_UPGRADE.get(), 9));
    }

    // ---- equipment wardrobe (wave 5) ----------------------------------------------------------------

    Map<String, String> eqSet(Player p, int slot) { return wardrobe(p).eqSets.computeIfAbsent(String.valueOf(slot), k -> new HashMap<>()); }

    boolean eqSlotOpen(Player p, int slot) {
        if (slot < 0 || slot >= wdSlots()) return false;
        if (!wardrobe(p).eqSets.getOrDefault(String.valueOf(slot), Map.of()).isEmpty()) return true;   // never strand stored pieces
        int[] t = StorageMath.thresholds(StorageDials.EQ_SLOT_LEVELS.get());
        if (slot < t.length && sbLevel(p) >= t[slot]) return true;
        int shop = shopTier(p, StorageDials.EQ_SHOP_UPGRADE.get(), 9);
        return shop > 0 && slot < StorageMath.openCount(t, sbLevel(p), shop, wdSlots());
    }

    int eqSlotLevelNeeded(Player p, int slot) {
        return StorageMath.levelFor(StorageMath.thresholds(StorageDials.EQ_SLOT_LEVELS.get()), slot,
                shopTier(p, StorageDials.EQ_SHOP_UPGRADE.get(), 9));
    }

    boolean wardrobeAllowed(Player p) { return check(p, StorageDials.WD_REQUIREMENT.get()); }

    int sackBagSlots(Player p) {
        int[] lv = StorageMath.thresholds(StorageDials.SACKBAG_LEVELS.get());
        int account = StorageMath.openCount(lv, sbLevel(p), shopTier(p, StorageDials.SACKBAG_SHOP_UPGRADE.get(), 8), lv.length);
        int tier = collectionTier(p, StorageDials.SACKBAG_COLLECTION.get());
        return StorageMath.sackBagSlots(account, tier, StorageDials.SACKBAG_UNLOCK.get());
    }

    int quiverSlots(Player p) {
        return StorageMath.quiverSlots(collectionTier(p, StorageDials.QUIVER_COLLECTION.get()), StorageDials.QUIVER_BASE.get());
    }

    int vaultSlots(Player p) {
        double cap;
        try { cap = Sky.economy().bankCapacity(p); } catch (RuntimeException e) { cap = 0; }
        return StorageMath.clamp(StorageMath.tieredValue(StorageDials.VAULT_TIERS.get(), cap), 0, 45);
    }

    // ---- containers -----------------------------------------------------------------------------

    /** A stored container shown as the free slots of a menu. */
    record Spec(String title, String guardKey, int capacity, Function<Player, Map<String, String>> store,
                Predicate<ItemStack> accepts, String reject, Consumer<Menu> nav) {}

    /** Build (not open) the menu for a container: free slots 0..capacity-1, glass after, nav row last. */
    Menu container(Player p, Spec spec) {
        int cap = StorageMath.clamp(spec.capacity(), 0, 45);
        Map<String, String> data = spec.store().apply(p);
        // Items stored beyond the current capacity (a page that got smaller) stay visible and can be taken out.
        int highest = cap;
        for (String k : data.keySet()) {
            try { highest = Math.max(highest, Integer.parseInt(k) + 1); } catch (NumberFormatException ignored) { }
        }
        highest = Math.min(highest, 45);
        int rows = StorageMath.rowsFor(highest);
        Menu m = new Menu(spec.title(), rows).keepFreeSlotItems();
        int navStart = (rows - 1) * 9;
        int shown = Math.min(highest, navStart);
        for (int i = 0; i < navStart; i++) {
            if (i >= shown) { m.set(i, Icons.filler()); continue; }
            String raw = data.get(String.valueOf(i));
            ItemStack it = Codec.decode(raw);
            if (raw != null && it == null) {
                m.set(i, Icons.of(Material.BARRIER, "&cUnreadable item", "&7The item stored here could not be loaded.",
                        "&7It is kept untouched. Tell an admin (slot " + i + ")."));
                continue;
            }
            if (i >= cap && it == null) {
                m.set(i, Icons.of(Material.RED_STAINED_GLASS_PANE, "&cLocked Slot", "&7This slot is not unlocked."));
                continue;
            }
            m.free(i);
            m.getInventory().setItem(i, it);
        }
        for (int i = navStart; i < rows * 9; i++) m.set(i, Icons.filler());
        m.closeButton();
        if (spec.nav() != null) spec.nav().accept(m);
        m.onFreeSlotChange(c -> sync(c.player(), m, spec, shown));
        m.onClose(pl -> {
            if (sync(pl, m, spec, shown)) {
                try { Sky.profiles().saveNow(pl); } catch (RuntimeException ignored) { }
            }
        });
        mod.track(p, m, spec.guardKey(), () -> sync(p, m, spec, shown));
        return m;
    }

    /** Copy a container menu's free slots into its stored map. Returns true when anything changed. */
    boolean sync(Player p, Menu m, Spec spec, int shown) {
        if (!ready(p)) return false;
        Map<String, String> data = spec.store().apply(p);
        Inventory inv = m.getInventory();
        Map<Integer, ItemStack> current = new LinkedHashMap<>();
        Map<Integer, String> encoded = new HashMap<>();
        // pass 1: validate every free slot and find which ones differ from what is stored
        for (int i = 0; i < shown; i++) {
            if (!m.freeSlots().contains(i)) continue;   // an unreadable slot: its raw data stays as it is
            ItemStack it = inv.getItem(i);
            if (Codec.empty(it)) it = null;
            String key = String.valueOf(i);
            if (it != null && i >= spec.capacity()) {
                // a slot past the capacity (the page shrank) may only be emptied
                String old = data.get(key);
                if (old == null || !old.equals(Codec.encode(it))) {
                    inv.setItem(i, null);
                    Codec.giveBack(p, it);
                    Msg.error(p, "That slot is locked.");
                    it = null;
                }
            }
            if (it != null && spec.accepts() != null && !spec.accepts().test(it)) {
                inv.setItem(i, null);
                Codec.giveBack(p, it);
                Msg.error(p, spec.reject());
                it = null;
            }
            current.put(i, it);
            encoded.put(i, it == null ? null : Codec.encode(it));
        }
        // pass 2: uuids of the unchanged items are known-good; new ones are checked against them and everything else
        Map<String, String> seen = new HashMap<>();
        for (Map.Entry<Integer, ItemStack> en : current.entrySet()) {
            String enc = encoded.get(en.getKey());
            if (en.getValue() != null && enc != null && enc.equals(data.get(String.valueOf(en.getKey())))) {
                String u = Codec.uuid(en.getValue());
                if (u != null) seen.put(u, spec.guardKey());
            }
        }
        boolean changed = false;
        List<ItemStack> now = new ArrayList<>();
        for (Map.Entry<Integer, ItemStack> en : current.entrySet()) {
            int i = en.getKey();
            ItemStack it = en.getValue();
            String key = String.valueOf(i);
            String enc = encoded.get(i);
            String old = data.get(key);
            if (enc == null ? old == null : enc.equals(old)) {
                if (it != null) now.add(it);
                continue;
            }
            if (it != null && !mod.guard().allowNew(p, spec.guardKey(), it, seen)) {
                inv.setItem(i, null);   // duplicate: delete this copy, the original lives elsewhere
                enc = null;
                it = null;
            }
            if (enc == null) data.remove(key);
            else { data.put(key, enc); now.add(it); }
            changed = true;
        }
        if (changed) {
            mod.guard().reindex(p, spec.guardKey(), now);
            if ("sackbag".equals(spec.guardKey())) mod.sacks().invalidate(p);
        }
        return changed;
    }
}
