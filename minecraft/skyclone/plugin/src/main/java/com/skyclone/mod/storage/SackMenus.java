package com.skyclone.mod.storage;

import com.google.gson.JsonPrimitive;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** /sacks (every sack family), one family's contents with take-out, the Sack of Sacks and linings. */
final class SackMenus {

    private static final int[] GRID = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    private final StorageModule mod;

    SackMenus(StorageModule mod) { this.mod = mod; }

    // ---- all sacks ------------------------------------------------------------------------------------

    void openSacks(Player p, int page) {
        if (!Store.ready(p)) { Msg.error(p, "Your profile is still loading, try again in a moment."); return; }
        SackService ss = mod.sacks();
        Map<String, Long> caps = ss.familyCaps(p);
        Map<String, Integer> counts = ss.familyCounts(p);
        List<Catalog.Family> fams = new ArrayList<>(mod.catalog().families);
        // owned families first, then anything holding items, then the rest; each in catalogue order
        fams.sort(Comparator.comparingInt((Catalog.Family f) -> caps.getOrDefault(f.id(), 0L) > 0 ? 0 : (storedIn(p, f) > 0 ? 1 : 2))
                .thenComparingInt(Catalog.Family::order));
        int pages = Math.max(1, (fams.size() + GRID.length - 1) / GRID.length);
        final int pg = StorageMath.clamp(page, 0, pages - 1);
        Menu m = new Menu("Sacks" + (pages > 1 ? " (" + (pg + 1) + "/" + pages + ")" : ""), 6).chrome();
        StorageData.Sacks data = mod.store().sacks(p);
        long totalItems = 0;
        for (long v : data.stored.values()) totalItems += v;
        List<String> head = new ArrayList<>(List.of(
                "&7Sacks hold huge amounts of one kind", "&7of item. Anything you pick up that a", "&7sack accepts goes straight in.", "",
                "&7Sack kinds owned: &a" + counts.size() + "&7/&a" + mod.catalog().families.size(),
                "&7Items stored: &a" + Text.commasInt(totalItems)));
        double size = ss.sackSizePct(p);
        if (size > 0) head.add("&7Sack Size: &a+" + Text.commas(size) + "% &7capacity &8(Hunting)");
        m.set(4, Icons.of(Material.BUNDLE, "&aSacks", head.toArray(new String[0])));
        for (int i = 0; i < GRID.length; i++) {
            int idx = pg * GRID.length + i;
            if (idx >= fams.size()) break;
            Catalog.Family f = fams.get(idx);
            m.set(GRID[i], familyIcon(p, f, ss.shownCapacity(p, caps.getOrDefault(f.id(), 0L)), counts.getOrDefault(f.id(), 0)),
                    c -> openFamily(c.player(), f.id(), 0));
        }
        if (pg > 0) m.set(45, Icons.prevPage(pg + 1), c -> openSacks(c.player(), pg - 1));
        if (pg < pages - 1) m.set(53, Icons.nextPage(pg + 1), c -> openSacks(c.player(), pg + 1));
        m.set(47, Icons.of(Material.CHEST, "&aInsert Inventory", "&7Put every item your sacks accept", "&7from your inventory into them.", "",
                "&eClick to insert!"), c -> { insert(c.player(), null); openSacks(c.player(), pg); });
        m.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        m.set(50, Icons.of(Material.BUNDLE, "&aSack of Sacks", "&7Keep sacks here so they work", "&7without taking inventory space.", "",
                "&7Slots: &a" + mod.store().sackBagSlots(p), "", "&eClick to open!"), c -> openBag(c.player()));
        m.set(51, Icons.of(data.autoPickup ? Material.HOPPER : Material.BARRIER, "&aAuto-Pickup: " + (data.autoPickup ? "&aON" : "&cOFF"),
                "&7When on, items your sacks accept", "&7go into them instead of your", "&7inventory.", "", "&eClick to toggle!"),
                c -> { StorageData.Sacks d = mod.store().sacks(c.player()); d.autoPickup = !d.autoPickup; openSacks(c.player(), pg); });
        boolean sum = ss.summaryOn(p);
        m.set(52, Icons.of(sum ? Material.PAPER : Material.MAP, "&aPickup Summary: " + (sum ? "&aON" : "&cOFF"),
                "&7Shows a '[Sacks] +N items' line", "&7every " + StorageDials.SACK_SUMMARY_SECONDS.get() + " seconds while sacks fill.",
                "&8Same as Settings > Sacks Messages.", "",
                "&eClick to toggle!"), c -> { mod.sacks().setSummary(c.player(), !mod.sacks().summaryOn(c.player())); openSacks(c.player(), pg); });
        m.open(p);
    }

    private long storedIn(Player p, Catalog.Family f) {
        long n = 0;
        for (String id : f.contents()) n += mod.sacks().stored(p, id);
        return n;
    }

    private ItemStack familyIcon(Player p, Catalog.Family f, long cap, int owned) {
        String iconId = f.icon();
        ItemStack base = iconId != null && Sky.items().exists(iconId) ? Sky.items().create(iconId) : new ItemStack(Material.BUNDLE);
        long stored = storedIn(p, f);
        int kinds = 0;
        for (String id : f.contents()) if (mod.sacks().stored(p, id) > 0) kinds++;
        List<String> lore = new ArrayList<>();
        lore.add("&7Holds what you gather from " + f.field() + "&7.");
        lore.add("");
        if (owned > 0) {
            lore.add("&7Sacks owned: &a" + owned);
            lore.add("&7Capacity: &e" + Text.commasInt(cap) + " &7" + (f.weighted() ? "per gem type" : "per item"));
        } else {
            lore.add("&cYou own no sack of this kind.");
            lore.add("&8Craft one, or buy it, to start filling it.");
        }
        lore.add("&7Stored: &a" + Text.commasInt(stored) + " &7items in &a" + kinds + "&7/&a" + f.contents().size() + " &7kinds");
        lore.add("");
        lore.add("&eClick to open!");
        String name = (owned > 0 ? "&a" : "&7") + f.name() + " Sack";
        return base.getType() == Material.PLAYER_HEAD ? withHead(base, name, lore) : Icons.of(base.getType(), name, lore);
    }

    /** Re-label a skull item (keeps its texture). */
    private static ItemStack withHead(ItemStack head, String name, List<String> lore) {
        ItemStack it = head.clone();
        org.bukkit.inventory.meta.ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.c(name));
            meta.lore(Text.lines(lore));
            Icons.hide(meta);
            it.setItemMeta(meta);
        }
        return it;
    }

    // ---- one family -------------------------------------------------------------------------------------

    void openFamily(Player p, String familyId, int page) {
        Catalog.Family f = mod.catalog().familyById.get(familyId);
        if (f == null || !Store.ready(p)) return;
        if ("RUNE".equals(familyId) && StorageDials.RUNE_SACK_GROUPED.get() && !mod.runeTiers.isEmpty()) { openRunes(p, page); return; }
        SackService ss = mod.sacks();
        Map<String, Long> caps = ss.familyCaps(p);
        List<String> ids = new ArrayList<>(f.contents());
        int pages = Math.max(1, (ids.size() + GRID.length - 1) / GRID.length);
        final int pg = StorageMath.clamp(page, 0, pages - 1);
        Menu m = new Menu(f.name() + " Sack" + (pages > 1 ? " (" + (pg + 1) + "/" + pages + ")" : ""), 6).chrome();
        long cap = ss.shownCapacity(p, caps.getOrDefault(f.id(), 0L));
        m.set(4, Icons.of(Material.BUNDLE, "&a" + f.name() + " Sack", "&7Holds what you gather from " + f.field() + "&7.", "",
                "&7Capacity: &e" + Text.commasInt(cap) + " &7" + (f.weighted() ? "per gem type (in rough gems)" : "per item"),
                "&8Every sack of this kind you carry or", "&8keep in the Sack of Sacks adds up."));
        for (int i = 0; i < GRID.length; i++) {
            int idx = pg * GRID.length + i;
            if (idx >= ids.size()) break;
            String id = ids.get(idx);
            m.set(GRID[i], contentIcon(p, f, id, caps), c -> {
                take(c.player(), id, c.right());
                openFamily(c.player(), familyId, pg);
            });
        }
        if (pg > 0) m.set(45, Icons.prevPage(pg + 1), c -> openFamily(c.player(), familyId, pg - 1));
        if (pg < pages - 1) m.set(53, Icons.nextPage(pg + 1), c -> openFamily(c.player(), familyId, pg + 1));
        m.set(47, Icons.of(Material.CHEST, "&aInsert Inventory", "&7Put every item this sack accepts", "&7from your inventory into it.", "",
                "&eClick to insert!"), c -> { insert(c.player(), familyId); openFamily(c.player(), familyId, pg); });
        m.set(48, Icons.back("Sacks"), c -> openSacks(c.player(), 0));
        m.open(p);
    }

    private ItemStack contentIcon(Player p, Catalog.Family f, String id, Map<String, Long> caps) {
        long stored = mod.sacks().stored(p, id);
        long cap = mod.sacks().capacity(p, id, caps);
        ItemStack base;
        if (Sky.items().exists(id)) {
            try { base = Sky.items().create(id, 1); } catch (RuntimeException e) { base = new ItemStack(Material.PAPER); }
        } else {
            base = Icons.of(Material.PAPER, "&f" + Text.titleCase(id), "&8(not in this game yet)");
        }
        List<String> extra = new ArrayList<>();
        extra.add("");
        Catalog.Weight w = f.weights().get(id);
        if (w != null) {
            extra.add("&7Stored: &a" + Text.commasInt(stored));
            extra.add("&7" + Text.titleCase(w.group()) + " in sacks: &a" + Text.commasInt(mod.sacks().usedFor(p, id)) + "&7/&e" + Text.commasInt(cap) + " &8(rough)");
        } else {
            extra.add("&7Stored: &a" + Text.commasInt(stored) + "&7/&e" + Text.commasInt(cap));
        }
        if (cap > 0) extra.add(Text.dashBar(cap == 0 ? 0 : (double) mod.sacks().usedFor(p, id) / cap, 20));
        extra.add("");
        if (stored > 0) {
            extra.add("&eLeft-click to take one stack!");
            extra.add("&eRight-click to fill your inventory!");
        } else {
            extra.add("&8Empty.");
        }
        ItemStack icon = Icons.withLore(base, extra);
        if (stored > 0) icon = Icons.amount(icon, (int) Math.min(64, Math.max(1, stored)));
        return icon;
    }

    // ---- the Rune Sack, grouped (wave 5) -----------------------------------------------------------------
    // Reference lore: "Holds most commonly dropped Runes and their tiers! ... Capacity: 64 of each rune tier".
    // One icon per rune (its three tiers listed), clicking opens that rune's tiers to take out.

    /** Rune key -> [tier I id, tier II id, tier III id] for the runes the Rune family holds, family order. */
    java.util.LinkedHashMap<String, String[]> runeGroups() {
        Map<String, String> tags = new java.util.LinkedHashMap<>(mod.runeTiers);
        Catalog.Family f = mod.catalog().familyById.get("RUNE");
        // a rune some module registered without rune_key/rune_tier still gets an icon of its own (never hidden)
        if (f != null) for (String id : f.contents()) tags.putIfAbsent(id, id + ":1");
        return StorageMath.runeGroups(tags, 3);
    }

    void openRunes(Player p, int page) {
        Catalog.Family f = mod.catalog().familyById.get("RUNE");
        if (f == null || !Store.ready(p)) return;
        SackService ss = mod.sacks();
        Map<String, Long> caps = ss.familyCaps(p);
        List<Map.Entry<String, String[]>> groups = new ArrayList<>(runeGroups().entrySet());
        int pages = Math.max(1, (groups.size() + GRID.length - 1) / GRID.length);
        final int pg = StorageMath.clamp(page, 0, pages - 1);
        Menu m = new Menu(f.name() + " Sack" + (pages > 1 ? " (" + (pg + 1) + "/" + pages + ")" : ""), 6).chrome();
        long cap = ss.shownCapacity(p, caps.getOrDefault(f.id(), 0L));
        m.set(4, Icons.of(Material.BUNDLE, "&a" + f.name() + " Sack", "&7Holds runes and their tiers.", "",
                "&7Capacity: &e" + Text.commasInt(cap) + " &7of each rune tier",
                "&8Every sack of this kind you carry or", "&8keep in the Sack of Sacks adds up."));
        for (int i = 0; i < GRID.length; i++) {
            int idx = pg * GRID.length + i;
            if (idx >= groups.size()) break;
            String key = groups.get(idx).getKey();
            String[] tiers = groups.get(idx).getValue();
            m.set(GRID[i], runeIcon(p, tiers, caps), c -> openRune(c.player(), key, pg));
        }
        if (pg > 0) m.set(45, Icons.prevPage(pg + 1), c -> openRunes(c.player(), pg - 1));
        if (pg < pages - 1) m.set(53, Icons.nextPage(pg + 1), c -> openRunes(c.player(), pg + 1));
        m.set(47, Icons.of(Material.CHEST, "&aInsert Inventory", "&7Put every rune this sack accepts", "&7from your inventory into it.", "",
                "&eClick to insert!"), c -> { insert(c.player(), "RUNE"); openRunes(c.player(), pg); });
        m.set(48, Icons.back("Sacks"), c -> openSacks(c.player(), 0));
        m.open(p);
    }

    private ItemStack runeIcon(Player p, String[] tiers, Map<String, Long> caps) {
        String first = null;
        for (String id : tiers) if (id != null) { first = id; break; }
        ItemStack base = first != null && Sky.items().exists(first) ? Sky.items().create(first, 1) : new ItemStack(Material.PAPER);
        ItemDef d = first == null ? null : Sky.items().def(first);
        String name = d == null ? "&5Rune" : (d.rarity == null ? "&f" : d.rarity.code()) + baseRuneName(d.name);
        List<String> lore = new ArrayList<>();
        lore.add("&7Stored in your sacks:");
        long total = 0;
        for (int t = 0; t < tiers.length; t++) {
            if (tiers[t] == null) continue;
            long n = mod.sacks().stored(p, tiers[t]);
            total += n;
            long cap = mod.sacks().capacity(p, tiers[t], caps);
            lore.add("&7 Tier " + Text.roman(t + 1) + ": " + (n > 0 ? "&a" : "&8") + Text.commasInt(n) + "&7/&e" + Text.commasInt(cap));
        }
        lore.add("");
        lore.add(total > 0 ? "&eClick to view tiers!" : "&8Empty.");
        ItemStack icon = base.getType() == Material.PLAYER_HEAD ? withHead(base, name, lore) : Icons.of(base.getType(), name, lore);
        if (total > 0) icon = Icons.amount(icon, (int) Math.min(64, total));
        return icon;
    }

    /** "◆ Blood Rune I" -> "◆ Blood Rune" (the tier numeral is per item; the group shows all three). */
    static String baseRuneName(String name) {
        if (name == null) return "Rune";
        String n = name.trim();
        for (String suffix : new String[]{" III", " II", " I"}) if (n.endsWith(suffix)) return n.substring(0, n.length() - suffix.length());
        return n;
    }

    void openRune(Player p, String key, int backPage) {
        Catalog.Family f = mod.catalog().familyById.get("RUNE");
        String[] tiers = runeGroups().get(key);
        if (f == null || tiers == null || !Store.ready(p)) { openRunes(p, backPage); return; }
        Map<String, Long> caps = mod.sacks().familyCaps(p);
        String title = "Rune Sack";
        for (String id : tiers) {
            ItemDef d = id == null ? null : Sky.items().def(id);
            if (d != null) { title = Text.strip(baseRuneName(d.name)).replace("◆", "").trim(); break; }
        }
        Menu m = new Menu(title, 4).chrome();
        int[] at = {11, 13, 15};
        for (int t = 0; t < tiers.length && t < at.length; t++) {
            String id = tiers[t];
            if (id == null) { m.set(at[t], Icons.of(Material.GRAY_STAINED_GLASS_PANE, "&7Tier " + Text.roman(t + 1), "&8Not in this game.")); continue; }
            m.set(at[t], contentIcon(p, f, id, caps), c -> {
                take(c.player(), id, c.right());
                openRune(c.player(), key, backPage);
            });
        }
        m.set(30, Icons.back("Rune Sack"), c -> openRunes(c.player(), backPage));
        m.set(32, Icons.of(Material.CHEST, "&aInsert Inventory", "&7Put every rune this sack accepts", "&7from your inventory into it.", "",
                "&eClick to insert!"), c -> { insert(c.player(), "RUNE"); openRune(c.player(), key, backPage); });
        m.open(p);
    }

    /** Left-click one stack, right-click fill the inventory (dial-capped); tells the player what moved. */
    private void take(Player p, String id, boolean all) {
        int stacks = all ? Math.max(1, StorageDials.TAKE_MAX_STACKS.get()) : 1;
        int moved = mod.sacks().takeOut(p, id, stacks);
        if (moved > 0) {
            ItemDef d = Sky.items().def(id);
            Msg.send(p, "&aMoved &e" + Text.commasInt(moved) + "x " + (d == null ? id : d.rarity.code() + d.name) + " &afrom your sacks.");
            Msg.sound(p, Sound.ENTITY_ITEM_PICKUP, 1.2f);
        }
    }

    private void insert(Player p, String family) {
        long n = mod.sacks().insertInventory(p, family);
        if (n > 0) {
            Msg.success(p, "Put " + Text.commasInt(n) + " items into your sacks.");
        } else {
            Msg.error(p, "Nothing in your inventory fits in your sacks.");
        }
    }

    // ---- sack of sacks -----------------------------------------------------------------------------------

    void openBag(Player p) {
        if (!Store.ready(p)) { Msg.error(p, "Your profile is still loading, try again in a moment."); return; }
        int cap = mod.store().sackBagSlots(p);
        Store.Spec spec = new Store.Spec("Sack of Sacks", "sackbag", cap, pl -> mod.store().sacks(pl).bag,
                it -> mod.catalog().isSack(Codec.id(it)), "Only sacks fit in the Sack of Sacks.", m -> {
                    m.set(m.size() - 6, Icons.back("Sacks"), c -> openSacks(c.player(), 0));
                    m.set(m.size() - 4, Icons.of(Material.STRING, "&aStitch a Lining",
                            "&7Sew an &cInner Sack Lining &7into one", "&7of your sacks: &c+200% &7of its original",
                            "&7capacity, up to &a" + StorageDials.LINING_MAX.get() + " &7times per sack.", "",
                            "&7Linings in your inventory: &a" + Sky.items().count(p, "POCKET_SACK_IN_A_SACK"), "",
                            "&eClick to choose a sack!"), c -> openLining(c.player()));
                    m.set(m.size() - 9, Icons.of(Material.BOOK, "&aSlots: &e" + cap,
                            "&7Account slots by SkyBlock level:", "&7  levels " + StorageDials.SACKBAG_LEVELS.get(),
                            "&7  plus &6Community Shop &7Sack of Sacks: &a" + Store.shopTier(p, StorageDials.SACKBAG_SHOP_UPGRADE.get(), 8),
                            "&7Collection: " + Text.strip(Store.describe("collection:" + StorageDials.SACKBAG_COLLECTION.get() + ":4")),
                            "&7  gives " + StorageDials.SACKBAG_UNLOCK.get() + ", then +4 at V-VIII and +5 at IX."));
                });
        mod.store().container(p, spec).open(p);
    }

    private void openLining(Player p) {
        if (Sky.items().count(p, "POCKET_SACK_IN_A_SACK") <= 0) {
            Msg.error(p, "You need an Inner Sack Lining in your inventory.");
            return;
        }
        List<String> keys = new ArrayList<>();
        List<ItemStack> shown = new ArrayList<>();
        for (Map.Entry<String, String> en : mod.store().sacks(p).bag.entrySet()) {
            ItemStack s = Codec.decode(en.getValue());
            if (s == null || !mod.catalog().isSack(Codec.id(s))) continue;
            keys.add(en.getKey());
            shown.add(s);
        }
        if (keys.isEmpty()) { Msg.error(p, "Put a sack in your Sack of Sacks first."); return; }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) order.add(i);
        Sky.menus().paged("Stitch a Lining", order, i -> {
                    ItemStack s = shown.get(i);
                    int n = SackService.linings(s);
                    long now = mod.sacks().sackCapacity(s);
                    Catalog.Sack info = mod.catalog().sacks.get(Codec.id(s));
                    long next = info == null ? now : StorageMath.sackCapacity(info.capacity(), n + 1, StorageDials.LINING_MAX.get(), StorageDials.LINING_BONUS.get());
                    return Icons.withLore(s, List.of("", "&7Linings: &a" + n + "&7/&a" + StorageDials.LINING_MAX.get(),
                            "&7Capacity: &e" + Text.commasInt(now) + (next > now ? " &8-> &a" + Text.commasInt(next) : ""), "",
                            n >= StorageDials.LINING_MAX.get() ? "&cFully lined!" : "&eClick to stitch!"));
                },
                pc -> stitch(pc.click().player(), keys.get(pc.entry())),
                page -> page.set(page.size() - 6, Icons.back("Sack of Sacks"), c -> openBag(c.player())), 0).open(p);
    }

    private void stitch(Player p, String bagSlot) {
        StorageData.Sacks d = mod.store().sacks(p);
        ItemStack s = Codec.decode(d.bag.get(bagSlot));
        if (s == null || !mod.catalog().isSack(Codec.id(s))) { openBag(p); return; }
        int n = SackService.linings(s);
        if (n >= StorageDials.LINING_MAX.get()) { Msg.error(p, "That sack is already fully lined."); return; }
        if (Sky.items().remove(p, "POCKET_SACK_IN_A_SACK", 1) < 1) { Msg.error(p, "You need an Inner Sack Lining in your inventory."); return; }
        Sky.items().update(s, data -> data.extra.add(SackService.LININGS, new JsonPrimitive(n + 1)));
        d.bag.put(bagSlot, Codec.encode(s));
        mod.sacks().invalidate(p);
        try { Sky.profiles().saveNow(p); } catch (RuntimeException ignored) { }
        Msg.success(p, "Stitched a lining into your " + Text.strip(Codec.pretty(s)) + "! Capacity is now " + Text.commasInt(mod.sacks().sackCapacity(s)) + ".");
        Msg.sound(p, Sound.ENTITY_SHEEP_SHEAR, 1.2f);
        openLining(p);
    }
}
