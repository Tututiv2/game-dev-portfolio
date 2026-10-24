package com.skyclone.mod.storage;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Wardrobe (research 08 11.7): 18 armour sets over 2 pages. Rows 0-3 hold the helmet, chestplate,
 * leggings and boots of slots 1-9 (free slots you place armour into), row 4 the slot buttons. Equipping
 * swaps the whole set: what you wear goes back into the slot it came from (or to your inventory if it did
 * not come from the wardrobe). Gated by SkyBlock level instead of paid ranks.
 */
final class WardrobeMenu {

    static final String[] PIECES = {"Helmet", "Chestplate", "Leggings", "Boots"};

    private final StorageModule mod;

    WardrobeMenu(StorageModule mod) { this.mod = mod; }

    static ItemCategory category(int piece) {
        return switch (piece) {
            case 0 -> ItemCategory.HELMET;
            case 1 -> ItemCategory.CHESTPLATE;
            case 2 -> ItemCategory.LEGGINGS;
            default -> ItemCategory.BOOTS;
        };
    }

    /** Does this item belong in the given armour row? Sky defs by category; vanilla armour by material name. */
    static boolean fits(ItemStack it, int piece) {
        if (Codec.empty(it)) return false;
        ItemDef d = Codec.def(it);
        if (d != null && d.category != null && d.category.isArmor()) return d.category == category(piece);
        // hats: a cosmetic HEAD item. Runes are COSMETIC player heads too (skills), and are not hats (wave 5 fix).
        if (d != null && d.category == ItemCategory.COSMETIC) return piece == 0 && !d.has("rune") && !d.has("storage_sack") && !d.has("backpack_skin");
        // a sky item that is something else (an accessory on a head, a pet...) is never armour
        if (d != null && d.category != null && d.category != ItemCategory.MATERIAL && d.category != ItemCategory.NONE) return false;
        String m = it.getType().name();
        return switch (piece) {
            case 0 -> m.endsWith("_HELMET") || m.endsWith("_HEAD") || m.endsWith("_SKULL") || m.equals("CARVED_PUMPKIN");
            case 1 -> m.endsWith("_CHESTPLATE") || m.equals("ELYTRA");
            case 2 -> m.endsWith("_LEGGINGS");
            default -> m.endsWith("_BOOTS");
        };
    }

    static ItemStack worn(PlayerInventory inv, int piece) {
        return switch (piece) {
            case 0 -> inv.getHelmet();
            case 1 -> inv.getChestplate();
            case 2 -> inv.getLeggings();
            default -> inv.getBoots();
        };
    }

    static void wear(PlayerInventory inv, int piece, ItemStack it) {
        switch (piece) {
            case 0 -> inv.setHelmet(it);
            case 1 -> inv.setChestplate(it);
            case 2 -> inv.setLeggings(it);
            default -> inv.setBoots(it);
        }
    }

    // ---- menu -------------------------------------------------------------------------------------------

    void open(Player p, int page) {
        if (!Store.ready(p)) { Msg.error(p, "Your profile is still loading, try again in a moment."); return; }
        if (!mod.store().wardrobeAllowed(p)) {
            Msg.error(p, "The Wardrobe unlocks at " + Text.strip(Store.describe(StorageDials.WD_REQUIREMENT.get())) + ".");
            return;
        }
        final int pg = StorageMath.clamp(page, 0, 1);
        Menu m = new Menu("Wardrobe (" + (pg + 1) + "/2)", 6).keepFreeSlotItems();
        draw(p, m, pg);
        m.onFreeSlotChange(c -> { if (sync(c.player(), m, pg)) redrawButtons(c.player(), m, pg); });
        m.onClose(pl -> {
            if (sync(pl, m, pg)) {
                try { Sky.profiles().saveNow(pl); } catch (RuntimeException ignored) { }
            }
        });
        mod.track(p, m, "wd", () -> sync(p, m, pg));
        m.open(p);
    }

    /** Lay out the whole page from stored data. Only call when the menu's free slots were just synced. */
    private void draw(Player p, Menu m, int pg) {
        Store st = mod.store();
        StorageData.Wardrobe w = st.wardrobe(p);
        Inventory inv = m.getInventory();
        for (int col = 0; col < 9; col++) {
            int slot = pg * 9 + col;
            boolean open = st.wdSlotOpen(p, slot);
            boolean equipped = w.equipped == slot;
            Map<String, String> set = w.sets.getOrDefault(String.valueOf(slot), Map.of());
            for (int piece = 0; piece < 4; piece++) {
                int idx = piece * 9 + col;
                m.freeSlots().remove(idx);
                if (equipped) {
                    ItemStack on = worn(p.getInventory(), piece);
                    if (Codec.empty(on)) m.set(idx, Icons.of(Material.LIME_STAINED_GLASS_PANE, "&aSlot " + (slot + 1) + " " + PIECES[piece],
                            "&7Nothing worn here."));
                    else m.set(idx, Icons.withLore(on, List.of("", "&a(currently worn)")));
                    continue;
                }
                if (!open) {
                    m.set(idx, Icons.of(Material.RED_STAINED_GLASS_PANE, "&cSlot " + (slot + 1) + ": Locked"));
                    continue;
                }
                String raw = set.get(String.valueOf(piece));
                ItemStack it = Codec.decode(raw);
                if (raw != null && it == null) {
                    m.set(idx, Icons.of(Material.BARRIER, "&cUnreadable item", "&7Kept untouched. Tell an admin."));
                    continue;
                }
                m.set(idx, (ItemStack) null);
                m.free(idx);
                inv.setItem(idx, it);
            }
            drawButton(p, m, pg, col);
        }
        for (int i = 45; i < 54; i++) m.set(i, Icons.filler());
        if (pg > 0) m.set(45, Icons.prevPage(pg + 1), c -> { sync(c.player(), m, pg); open(c.player(), pg - 1); });
        if (pg < 1) m.set(53, Icons.nextPage(pg + 1), c -> { sync(c.player(), m, pg); open(c.player(), pg + 1); });
        m.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        m.closeButton();
        m.set(50, Icons.of(Material.CHEST, "&aStorage", "&7Ender chest pages and backpacks.", "", "&eClick to open!"),
                c -> mod.storageMenus().openStorage(c.player()));
        if (StorageDials.EQ_WARDROBE.get()) {
            m.set(46, Icons.of(Material.LEAD, "&aEquipment Wardrobe", "&7Sets of necklace, cloak, belt", "&7and gloves.", "",
                    "&eClick to switch!"), c -> { sync(c.player(), m, pg); mod.equipWardrobe().open(c.player(), pg); });
            m.set(52, linkIcon(p), c -> { sync(c.player(), m, pg); toggleLink(c.player()); open(c.player(), pg); });
        }
    }

    /** Ours (wave 5): the "Link Equipment" toggle shown in both wardrobes. */
    ItemStack linkIcon(Player p) {
        boolean on = Store.ready(p) && mod.store().wardrobe(p).linked;
        return Icons.of(on ? Material.LEAD : Material.STRING, "&aLink Equipment: " + (on ? "&aON" : "&cOFF"),
                "&7When on, equipping armour slot &aN", "&7also equips Equipment Wardrobe", "&7slot &aN &7if it holds a set, and", "&7unequipping takes both off.",
                "", "&eClick to toggle!");
    }

    void toggleLink(Player p) {
        if (!Store.ready(p)) return;
        StorageData.Wardrobe w = mod.store().wardrobe(p);
        w.linked = !w.linked;
        Msg.send(p, "&aLink Equipment " + (w.linked ? "&aenabled" : "&cdisabled") + "&a.");
    }

    /** After an armour swap: follow with the equipment set of the same slot when linked (quietly; armour already moved). */
    private void followLink(Player p, int slot, boolean equipping) {
        try {
            StorageData.Wardrobe w = mod.store().wardrobe(p);
            if (!w.linked || !StorageDials.EQ_WARDROBE.get() || !mod.equipBridge().live(p)) return;
            if (equipping) {
                if (w.eqEquipped != slot && !w.eqSets.getOrDefault(String.valueOf(slot), Map.of()).isEmpty()) {
                    String err = mod.equipWardrobe().equip(p, slot);
                    if (err != null) Msg.send(p, "&7(Equipment slot " + (slot + 1) + ": " + err + ")");
                }
            } else if (w.eqEquipped == slot) {
                String err = mod.equipWardrobe().unequip(p, slot);
                if (err != null) Msg.send(p, "&7(Equipment slot " + (slot + 1) + ": " + err + ")");
            }
        } catch (RuntimeException e) {
            Sky.log().warning("[storage] linked equipment swap for " + p.getName() + ": " + e);
        }
    }

    /** The slot button under one column (Locked / Equipped / Ready / Empty), from the stored data. */
    private void drawButton(Player p, Menu m, int pg, int col) {
        Store st = mod.store();
        StorageData.Wardrobe w = st.wardrobe(p);
        final int slot = pg * 9 + col;
        int btn = 36 + col;
        boolean open = st.wdSlotOpen(p, slot);
        Map<String, String> set = w.sets.getOrDefault(String.valueOf(slot), Map.of());
        if (!open) {
            m.set(btn, Icons.of(Material.RED_DYE, "&cSlot " + (slot + 1) + ": Locked",
                    "&7Requires &aSkyBlock Level " + st.wdSlotLevelNeeded(p, slot) + "&7,", "&7or &aArmor Wardrobe Slots &7from the", "&6Community Shop&7.", "&8Your level: " + Store.sbLevel(p)));
        } else if (w.equipped == slot) {
            m.set(btn, Icons.glow(Icons.of(Material.LIME_DYE, "&aSlot " + (slot + 1) + ": Equipped",
                    "&7You are wearing this set.", "", "&eClick to unequip and store it!")), c -> unequip(c.player(), m, pg, slot));
        } else if (!set.isEmpty()) {
            List<String> lore = new ArrayList<>();
            lore.add("&7" + set.size() + "/4 pieces stored:");
            for (int piece = 0; piece < 4; piece++) {
                ItemStack it = Codec.decode(set.get(String.valueOf(piece)));
                lore.add(it == null ? "&8 - no " + PIECES[piece].toLowerCase() : "&7 - " + Codec.pretty(it));
            }
            lore.add("");
            lore.add("&eClick to equip!");
            m.set(btn, Icons.of(Material.PINK_DYE, "&eSlot " + (slot + 1) + ": Ready", lore.toArray(new String[0])),
                    c -> equip(c.player(), m, pg, slot));
        } else {
            m.set(btn, Icons.of(Material.GRAY_DYE, "&7Slot " + (slot + 1) + ": Empty",
                    "&7Place armour in the rows above", "&7to save a set here."));
        }
    }

    /**
     * Redraw the button row after the player changed the armour rows (playtest 2026-10-03: "Slot 1: Empty"
     * stayed until the menu was reopened). Only while this menu is still the player's open top inventory.
     */
    private void redrawButtons(Player p, Menu m, int pg) {
        Menu cur;
        try { cur = Sky.menus().current(p); } catch (RuntimeException e) { cur = null; }
        if (cur != m || !Store.ready(p)) return;
        for (int col = 0; col < 9; col++) drawButton(p, m, pg, col);
    }

    /** Free slots -> stored sets, validating each row. Returns true when anything changed. */
    boolean sync(Player p, Menu m, int pg) {
        if (!Store.ready(p)) return false;
        StorageData.Wardrobe w = mod.store().wardrobe(p);
        Inventory inv = m.getInventory();
        boolean changed = false;
        Map<String, String> seen = new HashMap<>();
        // pass 0: armour that landed in the wrong row moves to its own row. A shift-click from the inventory fills
        // the FIRST empty free slot (the helmet row), so without this only helmets could ever be shift-clicked in.
        for (int idx : new ArrayList<>(m.freeSlots())) {
            int piece = idx / 9, col = idx % 9;
            if (piece > 3) continue;
            ItemStack it = inv.getItem(idx);
            if (Codec.empty(it) || it.getAmount() != 1 || fits(it, piece)) continue;
            int row = -1;
            for (int r = 0; r < 4 && row < 0; r++) if (fits(it, r)) row = r;
            if (row < 0) continue;
            int target = emptyIn(m, inv, row, col);
            if (target < 0) continue;   // no room in that row: pass 2 hands it back with a message
            inv.setItem(target, it);
            inv.setItem(idx, null);
        }
        // pass 1: unchanged items are the known-good uuids
        for (int idx : new ArrayList<>(m.freeSlots())) {
            int piece = idx / 9, col = idx % 9;
            if (piece > 3) continue;
            ItemStack it = inv.getItem(idx);
            String old = w.sets.getOrDefault(String.valueOf(pg * 9 + col), Map.of()).get(String.valueOf(piece));
            if (!Codec.empty(it) && old != null && old.equals(Codec.encode(it))) {
                String u = Codec.uuid(it);
                if (u != null) seen.put(u, "wd");
            }
        }
        for (int idx : new ArrayList<>(m.freeSlots())) {
            int piece = idx / 9, col = idx % 9;
            if (piece > 3) continue;
            int slot = pg * 9 + col;
            if (w.equipped == slot) continue;
            ItemStack it = inv.getItem(idx);
            if (Codec.empty(it)) it = null;
            if (it != null && (!fits(it, piece) || it.getAmount() > 1)) {
                inv.setItem(idx, null);
                Codec.giveBack(p, it);
                boolean armour = false;
                for (int r = 0; r < 4; r++) armour |= fits(it, r);
                Msg.error(p, !armour ? "Only armour goes in the Wardrobe."
                        : it.getAmount() > 1 ? "One piece per slot." : "No empty slot in that row on this page.");
                it = null;
            }
            Map<String, String> set = w.sets.computeIfAbsent(String.valueOf(slot), k -> new HashMap<>());
            String enc = it == null ? null : Codec.encode(it);
            String old = set.get(String.valueOf(piece));
            if (enc == null ? old == null : enc.equals(old)) continue;
            if (it != null && !mod.guard().allowNew(p, "wd", it, seen)) {
                inv.setItem(idx, null);
                enc = null;
            }
            if (enc == null) set.remove(String.valueOf(piece)); else set.put(String.valueOf(piece), enc);
            if (set.isEmpty()) w.sets.remove(String.valueOf(slot));
            changed = true;
        }
        if (changed) reindex(p);
        return changed;
    }

    /** An empty free slot in armour row {@code row}: the same column first, then the leftmost one. -1 when the row is full. */
    private static int emptyIn(Menu m, Inventory inv, int row, int col) {
        int same = row * 9 + col;
        if (m.freeSlots().contains(same) && Codec.empty(inv.getItem(same))) return same;
        for (int c = 0; c < 9; c++) {
            int idx = row * 9 + c;
            if (m.freeSlots().contains(idx) && Codec.empty(inv.getItem(idx))) return idx;
        }
        return -1;
    }

    private void reindex(Player p) {
        List<ItemStack> all = new ArrayList<>();
        for (Map<String, String> set : mod.store().wardrobe(p).sets.values()) all.addAll(Store.decodeAll(set));
        mod.guard().reindex(p, "wd", all);
    }

    // ---- swapping ---------------------------------------------------------------------------------------

    void equip(Player p, Menu m, int pg, int slot) {
        sync(p, m, pg);
        String err = equip(p, slot);
        if (err != null) { Msg.error(p, err); return; }
        draw(p, m, pg);
    }

    void unequip(Player p, Menu m, int pg, int slot) {
        sync(p, m, pg);
        String err = unequip(p, slot);
        if (err != null) { Msg.error(p, err); return; }
        draw(p, m, pg);
    }

    /** Equip a stored set (works without a menu: admin and tests). Returns an error or null. */
    String equip(Player p, int slot) {
        Store st = mod.store();
        if (!Store.ready(p)) return "Your profile is still loading.";
        if (!st.wdSlotOpen(p, slot)) return "That wardrobe slot is locked.";
        StorageData.Wardrobe w = st.wardrobe(p);
        if (w.equipped == slot) return "You are already wearing that set.";
        Map<String, String> set = w.sets.getOrDefault(String.valueOf(slot), Map.of());
        if (set.isEmpty()) return "That wardrobe slot is empty.";
        ItemStack[] incoming = new ItemStack[4];
        for (int i = 0; i < 4; i++) {
            String raw = set.get(String.valueOf(i));
            incoming[i] = Codec.decode(raw);
            if (raw != null && incoming[i] == null) return "A piece in that slot could not be read. Tell an admin.";
        }
        PlayerInventory inv = p.getInventory();
        ItemStack[] current = new ItemStack[4];
        int wearing = 0;
        for (int i = 0; i < 4; i++) {
            current[i] = worn(inv, i);
            if (Codec.empty(current[i])) current[i] = null; else wearing++;
        }
        int from = w.equipped;
        if (from >= 0 && !w.sets.getOrDefault(String.valueOf(from), Map.of()).isEmpty()) from = -1;   // never overwrite stored armour
        if (from < 0 && wearing > Codec.freeSlots(p)) return "Not enough inventory space to take off your armour!";
        // 1) what is worn now goes back to its slot (or the inventory)
        if (from >= 0) {
            Map<String, String> back = w.sets.computeIfAbsent(String.valueOf(from), k -> new HashMap<>());
            for (int i = 0; i < 4; i++) if (current[i] != null) back.put(String.valueOf(i), Codec.encode(current[i]));
        }
        for (int i = 0; i < 4; i++) wear(inv, i, null);
        if (from < 0) for (ItemStack c : current) if (c != null) Codec.giveBack(p, c);
        // 2) the stored set goes on
        w.sets.remove(String.valueOf(slot));
        for (int i = 0; i < 4; i++) wear(inv, i, incoming[i]);
        w.equipped = slot;
        reindex(p);
        try { Sky.stats().invalidate(p); } catch (RuntimeException ignored) { }
        try { Sky.profiles().saveNow(p); } catch (RuntimeException ignored) { }
        Msg.sound(p, Sound.ITEM_ARMOR_EQUIP_DIAMOND, 1f);
        Msg.send(p, "&aEquipped Wardrobe Slot " + (slot + 1) + "!");
        followLink(p, slot, true);
        return null;
    }

    /** Take off the equipped set and store it back in its slot. */
    String unequip(Player p, int slot) {
        Store st = mod.store();
        if (!Store.ready(p)) return "Your profile is still loading.";
        StorageData.Wardrobe w = st.wardrobe(p);
        if (w.equipped != slot) return "That set is not equipped.";
        Map<String, String> set = w.sets.computeIfAbsent(String.valueOf(slot), k -> new HashMap<>());
        if (!set.isEmpty()) { w.equipped = -1; return "That slot already holds armour; nothing was moved."; }
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 4; i++) {
            ItemStack on = worn(inv, i);
            if (Codec.empty(on)) continue;
            if (!fits(on, i)) { Codec.giveBack(p, on); wear(inv, i, null); continue; }
            set.put(String.valueOf(i), Codec.encode(on));
            wear(inv, i, null);
        }
        if (set.isEmpty()) w.sets.remove(String.valueOf(slot));
        w.equipped = -1;
        reindex(p);
        try { Sky.stats().invalidate(p); } catch (RuntimeException ignored) { }
        try { Sky.profiles().saveNow(p); } catch (RuntimeException ignored) { }
        Msg.sound(p, Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.8f);
        Msg.send(p, "&eStored your armour in Wardrobe Slot " + (slot + 1) + ".");
        followLink(p, slot, false);
        return null;
    }
}
