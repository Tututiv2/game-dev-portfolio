package com.skyclone.mod.storage;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Equipment Wardrobe (wave 5; research 05 Community Shop "Equipment Wardrobe Slots"): the Wardrobe's twin for
 * the four equipment slots. 18 sets over 2 pages; rows 0-3 hold the necklace, cloak, belt and gloves of slots 1-9,
 * row 4 the slot buttons. Equipping swaps all four worn pieces through {@link EquipBridge} (the accessories module
 * owns the worn slots): what you wear goes back to the slot it came from, or to your inventory.
 *
 * <p>Without a live bridge the menu still stores sets and "Take Set" moves one into your inventory to put on by
 * hand (right-click a piece, or /equipment) — it never pretends to have equipped something.
 */
final class EquipWardrobeMenu {

    private final StorageModule mod;

    EquipWardrobeMenu(StorageModule mod) { this.mod = mod; }

    static boolean fits(ItemStack it, int piece) { return !Codec.empty(it) && EquipBridge.piece(it) == piece; }

    // ---- menu -------------------------------------------------------------------------------------------

    void open(Player p, int page) {
        if (!Store.ready(p)) { Msg.error(p, "Your profile is still loading, try again in a moment."); return; }
        if (!StorageDials.EQ_WARDROBE.get()) { Msg.error(p, "The Equipment Wardrobe is turned off."); return; }
        if (!mod.store().wardrobeAllowed(p)) {
            Msg.error(p, "The Wardrobe unlocks at " + Text.strip(Store.describe(StorageDials.WD_REQUIREMENT.get())) + ".");
            return;
        }
        final int pg = StorageMath.clamp(page, 0, 1);
        // An "Equipped" mark only means something while the bridge can take the set off again. If the bridge went
        // away (accessories changed), the pieces are simply worn: clear the mark so the slot is usable, nothing moves.
        StorageData.Wardrobe w0 = mod.store().wardrobe(p);
        if (w0.eqEquipped >= 0 && !mod.equipBridge().live(p)) w0.eqEquipped = -1;
        Menu m = new Menu("Equipment Wardrobe (" + (pg + 1) + "/2)", 6).keepFreeSlotItems();
        draw(p, m, pg);
        m.onFreeSlotChange(c -> { if (sync(c.player(), m, pg)) redrawButtons(c.player(), m, pg); });
        m.onClose(pl -> {
            if (sync(pl, m, pg)) {
                try { Sky.profiles().saveNow(pl); } catch (RuntimeException ignored) { }
            }
        });
        mod.track(p, m, "wdeq", () -> sync(p, m, pg));
        m.open(p);
    }

    private void draw(Player p, Menu m, int pg) {
        Store st = mod.store();
        StorageData.Wardrobe w = st.wardrobe(p);
        Inventory inv = m.getInventory();
        for (int col = 0; col < 9; col++) {
            int slot = pg * 9 + col;
            boolean open = st.eqSlotOpen(p, slot);
            boolean equipped = w.eqEquipped == slot;
            Map<String, String> set = w.eqSets.getOrDefault(String.valueOf(slot), Map.of());
            for (int piece = 0; piece < 4; piece++) {
                int idx = piece * 9 + col;
                m.freeSlots().remove(idx);
                if (equipped) {
                    ItemStack on = mod.equipBridge().equipped(p, piece);
                    if (Codec.empty(on)) m.set(idx, Icons.of(Material.LIME_STAINED_GLASS_PANE, "&aSlot " + (slot + 1) + " " + EquipBridge.NAMES[piece],
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
        m.set(46, Icons.of(Material.LEATHER_CHESTPLATE, "&aArmor Wardrobe", "&7Your armour sets.", "", "&eClick to switch!"),
                c -> { sync(c.player(), m, pg); mod.wardrobe().open(c.player(), pg); });
        m.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        m.closeButton();
        m.set(50, Icons.of(Material.CHEST, "&aStorage", "&7Ender chest pages and backpacks.", "", "&eClick to open!"),
                c -> mod.storageMenus().openStorage(c.player()));
        m.set(51, Icons.of(Material.BLAZE_POWDER, "&aYour Equipment", "&7Necklace, cloak, belt and gloves", "&7you are wearing now.", "",
                "&eClick to open!"), c -> { sync(c.player(), m, pg); Sky.service(Features.Equipment.class).openMenu(c.player()); });
        m.set(52, mod.wardrobe().linkIcon(p), c -> { sync(c.player(), m, pg); mod.wardrobe().toggleLink(c.player()); open(c.player(), pg); });
    }

    private void drawButton(Player p, Menu m, int pg, int col) {
        Store st = mod.store();
        StorageData.Wardrobe w = st.wardrobe(p);
        final int slot = pg * 9 + col;
        int btn = 36 + col;
        boolean open = st.eqSlotOpen(p, slot);
        boolean live = mod.equipBridge().live(p);
        Map<String, String> set = w.eqSets.getOrDefault(String.valueOf(slot), Map.of());
        if (!open) {
            int lvl = st.eqSlotLevelNeeded(p, slot);
            m.set(btn, Icons.of(Material.RED_DYE, "&cSlot " + (slot + 1) + ": Locked",
                    lvl == Integer.MAX_VALUE ? "&7Unlocks with &aEquipment Wardrobe Slots" : "&7Requires &aSkyBlock Level " + lvl + "&7,",
                    lvl == Integer.MAX_VALUE ? "&7from the &6Community Shop&7." : "&7or &aEquipment Wardrobe Slots &7from the",
                    lvl == Integer.MAX_VALUE ? "" : "&6Community Shop&7.", "&8Your level: " + Store.sbLevel(p)));
        } else if (w.eqEquipped == slot) {
            m.set(btn, Icons.glow(Icons.of(Material.LIME_DYE, "&aSlot " + (slot + 1) + ": Equipped",
                    "&7You are wearing this set.", "", live ? "&eClick to unequip and store it!" : "&8Unequip it from /equipment.")),
                    c -> unequip(c.player(), m, pg, slot));
        } else if (!set.isEmpty()) {
            List<String> lore = new ArrayList<>();
            lore.add("&7" + set.size() + "/4 pieces stored:");
            for (int piece = 0; piece < 4; piece++) {
                ItemStack it = Codec.decode(set.get(String.valueOf(piece)));
                lore.add(it == null ? "&8 - no " + EquipBridge.NAMES[piece].toLowerCase() : "&7 - " + Codec.pretty(it));
            }
            lore.add("");
            if (live) lore.add("&eClick to equip!");
            else {
                lore.add("&7Equipment can't be swapped from here");
                lore.add("&7yet: this moves the set into your");
                lore.add("&7inventory to put on by hand.");
                lore.add("");
                lore.add("&eClick to take the set!");
            }
            m.set(btn, Icons.of(Material.PINK_DYE, "&eSlot " + (slot + 1) + ": Ready", lore.toArray(new String[0])),
                    c -> equip(c.player(), m, pg, slot));
        } else {
            m.set(btn, Icons.of(Material.GRAY_DYE, "&7Slot " + (slot + 1) + ": Empty",
                    "&7Place a necklace, cloak, belt or", "&7gloves in the rows above to save", "&7a set here."));
        }
    }

    private void redrawButtons(Player p, Menu m, int pg) {
        Menu cur;
        try { cur = Sky.menus().current(p); } catch (RuntimeException e) { cur = null; }
        if (cur != m || !Store.ready(p)) return;
        for (int col = 0; col < 9; col++) drawButton(p, m, pg, col);
    }

    /** Free slots -> stored sets, validating each row. True when anything changed. */
    boolean sync(Player p, Menu m, int pg) {
        if (!Store.ready(p)) return false;
        StorageData.Wardrobe w = mod.store().wardrobe(p);
        Inventory inv = m.getInventory();
        boolean changed = false;
        Map<String, String> seen = new HashMap<>();
        // pass 0: a piece shift-clicked into the first empty row moves to its own row (same column first)
        for (int idx : new ArrayList<>(m.freeSlots())) {
            int piece = idx / 9, col = idx % 9;
            if (piece > 3) continue;
            ItemStack it = inv.getItem(idx);
            if (Codec.empty(it) || it.getAmount() != 1 || fits(it, piece)) continue;
            int row = EquipBridge.piece(it);
            if (row < 0) continue;
            int target = emptyIn(m, inv, row, col);
            if (target < 0) continue;
            inv.setItem(target, it);
            inv.setItem(idx, null);
        }
        // pass 1: unchanged items are the known-good uuids
        for (int idx : new ArrayList<>(m.freeSlots())) {
            int piece = idx / 9, col = idx % 9;
            if (piece > 3) continue;
            ItemStack it = inv.getItem(idx);
            String old = w.eqSets.getOrDefault(String.valueOf(pg * 9 + col), Map.of()).get(String.valueOf(piece));
            if (!Codec.empty(it) && old != null && old.equals(Codec.encode(it))) {
                String u = Codec.uuid(it);
                if (u != null) seen.put(u, "wdeq");
            }
        }
        for (int idx : new ArrayList<>(m.freeSlots())) {
            int piece = idx / 9, col = idx % 9;
            if (piece > 3) continue;
            int slot = pg * 9 + col;
            if (w.eqEquipped == slot) continue;
            ItemStack it = inv.getItem(idx);
            if (Codec.empty(it)) it = null;
            if (it != null && (!fits(it, piece) || it.getAmount() > 1)) {
                inv.setItem(idx, null);
                Codec.giveBack(p, it);
                Msg.error(p, EquipBridge.piece(it) < 0 ? "Only necklaces, cloaks, belts and gloves go in the Equipment Wardrobe."
                        : it.getAmount() > 1 ? "One piece per slot." : "No empty slot in that row on this page.");
                it = null;
            }
            Map<String, String> set = w.eqSets.computeIfAbsent(String.valueOf(slot), k -> new HashMap<>());
            String enc = it == null ? null : Codec.encode(it);
            String old = set.get(String.valueOf(piece));
            if (enc == null ? old == null : enc.equals(old)) { if (set.isEmpty()) w.eqSets.remove(String.valueOf(slot)); continue; }
            if (it != null && !mod.guard().allowNew(p, "wdeq", it, seen)) {
                inv.setItem(idx, null);
                enc = null;
            }
            if (enc == null) set.remove(String.valueOf(piece)); else set.put(String.valueOf(piece), enc);
            if (set.isEmpty()) w.eqSets.remove(String.valueOf(slot));
            changed = true;
        }
        if (changed) reindex(p);
        return changed;
    }

    private static int emptyIn(Menu m, Inventory inv, int row, int col) {
        int same = row * 9 + col;
        if (m.freeSlots().contains(same) && Codec.empty(inv.getItem(same))) return same;
        for (int c = 0; c < 9; c++) {
            int idx = row * 9 + c;
            if (m.freeSlots().contains(idx) && Codec.empty(inv.getItem(idx))) return idx;
        }
        return -1;
    }

    void reindex(Player p) {
        List<ItemStack> all = new ArrayList<>();
        for (Map<String, String> set : mod.store().wardrobe(p).eqSets.values()) all.addAll(Store.decodeAll(set));
        mod.guard().reindex(p, "wdeq", all);
    }

    // ---- swapping ---------------------------------------------------------------------------------------

    private void equip(Player p, Menu m, int pg, int slot) {
        sync(p, m, pg);
        String err = mod.equipBridge().live(p) ? equip(p, slot) : take(p, slot);
        if (err != null) { Msg.error(p, err); return; }
        draw(p, m, pg);
    }

    private void unequip(Player p, Menu m, int pg, int slot) {
        sync(p, m, pg);
        String err = unequip(p, slot);
        if (err != null) { Msg.error(p, err); return; }
        draw(p, m, pg);
    }

    /** Decode a stored set; null entries for empty rows. Returns null when a stored piece is unreadable. */
    private static ItemStack[] decodeSet(Map<String, String> set) {
        ItemStack[] out = new ItemStack[4];
        for (int i = 0; i < 4; i++) {
            String raw = set.get(String.valueOf(i));
            out[i] = Codec.decode(raw);
            if (raw != null && out[i] == null) return null;
        }
        return out;
    }

    private String checkSlot(Player p, int slot) {
        if (!Store.ready(p)) return "Your profile is still loading.";
        if (!StorageDials.EQ_WARDROBE.get()) return "The Equipment Wardrobe is turned off.";
        if (!mod.store().eqSlotOpen(p, slot)) return "That equipment wardrobe slot is locked.";
        return null;
    }

    /**
     * Equip a stored set through the bridge (works without a menu: admin, tests and the armour link). Each slot is
     * swapped on its own and only after the owner confirmed the write, so a refusal half way never duplicates or
     * loses a piece: what moved is consistent, the rest stays stored. Returns an error or null.
     */
    String equip(Player p, int slot) {
        String bad = checkSlot(p, slot);
        if (bad != null) return bad;
        EquipBridge br = mod.equipBridge();
        if (!br.live(p)) return "Equipment can't be swapped from the wardrobe yet. Use Take Set instead.";
        StorageData.Wardrobe w = mod.store().wardrobe(p);
        if (w.eqEquipped == slot) return "You are already wearing that set.";
        Map<String, String> set = w.eqSets.getOrDefault(String.valueOf(slot), Map.of());
        if (set.isEmpty()) return "That equipment wardrobe slot is empty.";
        ItemStack[] incoming = decodeSet(set);
        if (incoming == null) return "A piece in that slot could not be read. Tell an admin.";
        ItemStack[] current = new ItemStack[4];
        int wearing = 0;
        for (int i = 0; i < 4; i++) { current[i] = br.equipped(p, i); if (current[i] != null) wearing++; }
        int from = w.eqEquipped;
        if (from >= 0 && !w.eqSets.getOrDefault(String.valueOf(from), Map.of()).isEmpty()) from = -1;   // never overwrite stored pieces
        if (from < 0 && wearing > Codec.freeSlots(p)) return "Not enough inventory space to take off your equipment!";
        Map<String, String> back = from >= 0 ? w.eqSets.computeIfAbsent(String.valueOf(from), k -> new HashMap<>()) : null;
        int moved = 0;
        boolean failed = false;
        for (int i = 0; i < 4; i++) {
            if (incoming[i] == null && current[i] == null) continue;
            if (!br.set(p, i, incoming[i])) { failed = true; break; }
            moved++;
            set.remove(String.valueOf(i));
            if (current[i] != null) {
                if (back != null) back.put(String.valueOf(i), Codec.encode(current[i]));
                else Codec.giveBack(p, current[i]);
            }
        }
        if (set.isEmpty()) w.eqSets.remove(String.valueOf(slot));
        if (back != null && back.isEmpty()) w.eqSets.remove(String.valueOf(from));
        if (!failed) w.eqEquipped = slot;
        else if (moved > 0) w.eqEquipped = -1;
        reindex(p);
        try { Sky.stats().invalidate(p); } catch (RuntimeException ignored) { }
        try { Sky.profiles().saveNow(p); } catch (RuntimeException ignored) { }
        if (failed) return moved == 0 ? "Your equipment could not be changed right now." : "Only part of the set could be equipped; the rest is still stored.";
        Msg.sound(p, Sound.ITEM_ARMOR_EQUIP_LEATHER, 1.2f);
        Msg.send(p, "&aEquipped Equipment Wardrobe Slot " + (slot + 1) + "!");
        return null;
    }

    /** Take off the equipped set and store it back in its slot. */
    String unequip(Player p, int slot) {
        if (!Store.ready(p)) return "Your profile is still loading.";
        EquipBridge br = mod.equipBridge();
        StorageData.Wardrobe w = mod.store().wardrobe(p);
        if (w.eqEquipped != slot) return "That set is not equipped.";
        if (!br.live(p)) return "Equipment can't be swapped from the wardrobe yet. Take it off in /equipment.";
        Map<String, String> set = w.eqSets.computeIfAbsent(String.valueOf(slot), k -> new HashMap<>());
        if (!set.isEmpty()) { w.eqEquipped = -1; return "That slot already holds equipment; nothing was moved."; }
        for (int i = 0; i < 4; i++) {
            ItemStack on = br.equipped(p, i);
            if (on == null) continue;
            if (!br.set(p, i, null)) continue;   // the owner kept it on: nothing to store
            set.put(String.valueOf(i), Codec.encode(on));
        }
        if (set.isEmpty()) w.eqSets.remove(String.valueOf(slot));
        w.eqEquipped = -1;
        reindex(p);
        try { Sky.stats().invalidate(p); } catch (RuntimeException ignored) { }
        try { Sky.profiles().saveNow(p); } catch (RuntimeException ignored) { }
        Msg.sound(p, Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.8f);
        Msg.send(p, "&eStored your equipment in Equipment Wardrobe Slot " + (slot + 1) + ".");
        return null;
    }

    /**
     * Fallback while the bridge is not live: move a stored set into the inventory (all or nothing) so the player
     * can put it on by hand. The slot is emptied; nothing is marked as worn.
     */
    String take(Player p, int slot) {
        String bad = checkSlot(p, slot);
        if (bad != null) return bad;
        StorageData.Wardrobe w = mod.store().wardrobe(p);
        Map<String, String> set = w.eqSets.getOrDefault(String.valueOf(slot), Map.of());
        if (set.isEmpty()) return "That equipment wardrobe slot is empty.";
        ItemStack[] pieces = decodeSet(set);
        if (pieces == null) return "A piece in that slot could not be read. Tell an admin.";
        int n = 0;
        for (ItemStack s : pieces) if (s != null) n++;
        if (n > Codec.freeSlots(p)) return "Not enough inventory space for that set!";
        w.eqSets.remove(String.valueOf(slot));
        for (ItemStack s : pieces) if (s != null) Codec.giveBack(p, s);
        reindex(p);
        try { Sky.profiles().saveNow(p); } catch (RuntimeException ignored) { }
        Msg.send(p, "&aMoved Equipment Wardrobe Slot " + (slot + 1) + " into your inventory. &7Right-click each piece to wear it.");
        Msg.sound(p, Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f);
        return null;
    }
}
