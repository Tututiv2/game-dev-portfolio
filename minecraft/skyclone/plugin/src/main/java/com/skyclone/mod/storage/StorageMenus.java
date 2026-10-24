package com.skyclone.mod.storage;

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
import java.util.List;
import java.util.Map;

/**
 * {@link Features.Storage}: the Storage overview (ender chest pages on top, backpack slots below), every
 * ender chest page, every backpack, and the backpack options (open, upgrade, dye, take out).
 */
final class StorageMenus implements Features.Storage {

    private final StorageModule mod;

    StorageMenus(StorageModule mod) { this.mod = mod; }

    @Override public void openStorage(Player p) { open(p); }

    @Override public void openWardrobe(Player p) { mod.wardrobe().open(p, 0); }

    // ---- wave 5 readers, public so other modules can bind them BY NAME until Features.Storage declares them ----

    /** The Equipment Wardrobe (sets of necklace, cloak, belt and gloves). */
    public void openEquipmentWardrobe(Player p) { mod.equipWardrobe().open(p, 0); }

    /**
     * Copies of every armour piece stored in the Wardrobe (NOT the set being worn). For the Eerie pet's
     * "Fear from spook armour in your wardrobe counts even when not worn" (armor owns Fear). Empty while loading.
     */
    public java.util.List<org.bukkit.inventory.ItemStack> wardrobeItems(Player p) {
        java.util.List<org.bukkit.inventory.ItemStack> out = new java.util.ArrayList<>();
        if (!Store.ready(p)) return out;
        for (java.util.Map<String, String> set : mod.store().wardrobe(p).sets.values()) out.addAll(Store.decodeAll(set));
        return out;
    }

    /** Copies of every equipment piece stored in the Equipment Wardrobe (not the worn set). */
    public java.util.List<org.bukkit.inventory.ItemStack> equipmentWardrobeItems(Player p) {
        java.util.List<org.bukkit.inventory.ItemStack> out = new java.util.ArrayList<>();
        if (!Store.ready(p)) return out;
        for (java.util.Map<String, String> set : mod.store().wardrobe(p).eqSets.values()) out.addAll(Store.decodeAll(set));
        return out;
    }

    // ---- overview -----------------------------------------------------------------------------------

    void open(Player p) {
        if (!Store.ready(p)) { Msg.error(p, "Your profile is still loading, try again in a moment."); return; }
        Store st = mod.store();
        Menu m = new Menu("Storage", 6).chrome();
        int pages = st.ecPages();
        m.set(4, Icons.of(Material.ENDER_CHEST, "&aEnder Chest",
                "&7Store items in your ender chest", "&7pages. Up to &a" + pages + " &7pages of room.", "",
                "&7Pages unlocked: &a" + st.ecPagesOpen(p) + "&7/&a" + pages));
        for (int i = 0; i < 9; i++) {
            final int page = i;
            if (i >= pages) { m.set(9 + i, Icons.filler()); continue; }
            if (st.ecPageOpen(p, i)) {
                int used = Store.used(st.ender(p).pages.get(String.valueOf(i)));
                int size = st.ecPageSize(i);
                ItemStack icon = Icons.of(Material.PURPLE_STAINED_GLASS_PANE, "&aEnder Chest Page " + (i + 1),
                        "&7Slots used: &a" + used + "&7/&a" + size, "", "&eLeft-click to open!");
                if (used > 0) icon = Icons.amount(icon, Math.max(1, Math.min(64, used)));
                m.set(9 + i, icon, c -> openEnder(c.player(), page));
            } else {
                m.set(9 + i, Icons.of(Material.GRAY_STAINED_GLASS_PANE, "&cLocked Page",
                        "&7Ender Chest Page " + (i + 1), "", "&7Requires &aSkyBlock Level " + st.ecPageLevelNeeded(p, i) + "&7,",
                        "&7or more &aEnder Chest Pages &7from", "&7the &6Community Shop&7.", "&8Your level: " + Store.sbLevel(p)));
            }
        }
        m.set(22, Icons.of(Material.CHEST, "&aBackpacks",
                "&7Place backpacks in the slots below", "&7to carry even more items.", "",
                "&7Slots unlock as you find &dFairy Souls&7."));
        int slots = st.bpSlots();
        for (int i = 0; i < 18; i++) {
            final int slot = i;
            if (i >= slots) { m.set(27 + i, Icons.filler()); continue; }
            ItemStack bp = st.backpackItem(p, i);
            if (bp != null) {
                int cap = mod.catalog().backpackSlots(Codec.id(bp));
                int used = Store.used(st.backpacks(p).contents.get(String.valueOf(i)));
                List<String> extra = new ArrayList<>(List.of("", "&7Backpack Slot &a" + (i + 1),
                        "&7Slots used: &a" + used + "&7/&a" + cap, "", "&eLeft-click to open!", "&eRight-click for options!"));
                m.set(27 + i, Icons.withLore(skinned(bp), extra), c -> {
                    if (c.right()) openOptions(c.player(), slot);
                    else openBackpack(c.player(), slot);
                });
            } else if (st.bpSlotOpen(p, i)) {
                m.set(27 + i, Icons.of(Material.BROWN_STAINED_GLASS_PANE, "&eEmpty Backpack Slot " + (i + 1),
                        "&7Click with a backpack on your", "&7cursor to place it here, or", "&7click to pick one from your", "&7inventory.", "",
                        "&eClick to place a backpack!"), c -> clickEmpty(c.player(), slot));
            } else {
                m.set(27 + i, Icons.of(Material.GRAY_STAINED_GLASS_PANE, "&cLocked Backpack Slot " + (i + 1),
                        "", "&7Requires &d" + st.bpSouls(i) + " Fairy Souls&7.", "&8Exchange souls with the fairy in the Hub."));
            }
        }
        m.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        m.set(45, Icons.of(Material.LEATHER_CHESTPLATE, "&aWardrobe", "&7Store whole armour sets and", "&7swap between them instantly.", "",
                "&eClick to open!"), c -> mod.wardrobe().open(c.player(), 0));
        m.set(46, Icons.of(Material.BUNDLE, "&aSacks", "&7Everything your sacks hold.", "", "&eClick to open!"),
                c -> mod.sackMenus().openSacks(c.player(), 0));
        m.set(47, Icons.of(Material.CHEST, "&aYour Bags", "&7Quiver, Sack of Sacks, Personal", "&7Vault and the other bags.", "", "&eClick to open!"),
                c -> mod.bagMenus().openBags(c.player()));
        if (StorageDials.SHOP.get()) {
            m.set(50, Icons.of(Material.DIAMOND, "&bStorage Counter", "&7Jumbo upgrades, sack linings and", "&7special sacks, sold for &bBits&7.", "",
                    "&eClick to browse!"), c -> mod.bagMenus().openShop(c.player()));
        }
        // No player-inventory handler on purpose: the player can pick a backpack up onto the cursor and click an
        // empty slot with it (a handler would cancel every click in their own inventory).
        m.open(p);
    }

    // ---- ender chest pages ------------------------------------------------------------------------------

    void openEnder(Player p, int page) {
        Store st = mod.store();
        if (!Store.ready(p)) { Msg.error(p, "Your profile is still loading, try again in a moment."); return; }
        if (page < 0 || page >= st.ecPages()) { Msg.error(p, "There is no ender chest page " + (page + 1) + "."); return; }
        if (!st.ecPageOpen(p, page) && Store.used(st.ender(p).pages.get(String.valueOf(page))) == 0) {
            Msg.error(p, "That page unlocks at SkyBlock Level " + st.ecPageLevelNeeded(p, page) + " (or with Community Shop Ender Chest Pages).");
            return;
        }
        int cap = st.ecPageOpen(p, page) ? st.ecPageSize(page) : 0;
        int pages = st.ecPages();
        Store.Spec spec = new Store.Spec("Ender Chest (" + (page + 1) + "/" + pages + ")", "ec:" + page, cap,
                pl -> mod.store().ecPage(pl, page), null, "", m -> {
                    int base = m.size() - 9;
                    if (page > 0) m.set(base, Icons.prevPage(page + 1), c -> openEnder(c.player(), page - 1));
                    if (page < pages - 1) m.set(base + 8, Icons.nextPage(page + 1), c -> openEnder(c.player(), page + 1));
                    m.set(m.size() - 6, Icons.back("Storage"), c -> open(c.player()));
                });
        mod.store().container(p, spec).open(p);
        Msg.sound(p, Sound.BLOCK_ENDER_CHEST_OPEN, 1f);
    }

    // ---- backpacks ---------------------------------------------------------------------------------------

    void openBackpack(Player p, int slot) {
        Store st = mod.store();
        if (!Store.ready(p)) { Msg.error(p, "Your profile is still loading, try again in a moment."); return; }
        ItemStack bp = st.backpackItem(p, slot);
        if (bp == null) { Msg.error(p, "There is no backpack in slot " + (slot + 1) + "."); return; }
        int cap = mod.catalog().backpackSlots(Codec.id(bp));
        ItemDef d = Codec.def(bp);
        String name = d == null ? "Backpack" : Text.strip(d.name);
        Store.Spec spec = new Store.Spec(name + " (Slot #" + (slot + 1) + ")", "bp:" + slot, cap,
                pl -> mod.store().bpContents(pl, slot), it -> !mod.catalog().isBackpack(Codec.id(it)),
                "Backpacks cannot go inside backpacks.", m -> {
                    int base = m.size() - 9;
                    int prev = neighbour(p, slot, -1);
                    int next = neighbour(p, slot, +1);
                    if (prev >= 0) m.set(base, Icons.of(Material.ARROW, "&aPrevious Backpack", "&eSlot " + (prev + 1)), c -> openBackpack(c.player(), prev));
                    if (next >= 0) m.set(base + 8, Icons.of(Material.ARROW, "&aNext Backpack", "&eSlot " + (next + 1)), c -> openBackpack(c.player(), next));
                    m.set(m.size() - 6, Icons.back("Storage"), c -> open(c.player()));
                    m.set(m.size() - 4, Icons.of(Material.ANVIL, "&aBackpack Options", "&7Upgrade, dye or take it out.", "", "&eClick to open!"),
                            c -> openOptions(c.player(), slot));
                });
        mod.store().container(p, spec).open(p);
        Msg.sound(p, Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f);
    }

    private int neighbour(Player p, int slot, int dir) {
        StorageData.Backpacks b = mod.store().backpacks(p);
        for (int i = slot + dir; i >= 0 && i < mod.store().bpSlots(); i += dir) if (b.items.containsKey(String.valueOf(i))) return i;
        return -1;
    }

    /** The first backpack slot that holds a backpack, or -1. */
    int firstBackpack(Player p) { return neighbour(p, -1, +1); }

    private void clickEmpty(Player p, int slot) {
        ItemStack cursor = p.getItemOnCursor();
        if (!Codec.empty(cursor) && mod.catalog().isBackpack(Codec.id(cursor))) {
            if (!placeCheck(p, slot)) return;
            ItemStack bp = cursor.clone();
            p.setItemOnCursor(null);
            store(p, slot, bp);
            Msg.success(p, "Placed your " + Text.strip(Codec.pretty(bp)) + " in Backpack Slot " + (slot + 1) + ".");
            open(p);
            return;
        }
        openPicker(p, slot);
    }

    private boolean placeCheck(Player p, int slot) {
        Store st = mod.store();
        if (!Store.ready(p) || !st.bpSlotOpen(p, slot)) { Msg.error(p, "That backpack slot is locked."); return false; }
        if (st.backpacks(p).items.containsKey(String.valueOf(slot))) { Msg.error(p, "That backpack slot is taken."); return false; }
        return true;
    }

    /** Move a backpack from the player's inventory (found again by uuid, never trusting the snapshot) into a slot. */
    private void placeFromInventory(Player p, int slot, ItemStack snapshot) {
        if (!placeCheck(p, slot)) return;
        String u = Codec.uuid(snapshot);
        ItemStack[] inv = p.getInventory().getContents();
        for (int i = 0; i < inv.length; i++) {
            ItemStack s = inv[i];
            if (Codec.empty(s) || !mod.catalog().isBackpack(Codec.id(s))) continue;
            if (u != null ? !u.equals(Codec.uuid(s)) : !s.isSimilar(snapshot)) continue;
            ItemStack bp = s.clone();
            bp.setAmount(1);
            if (s.getAmount() > 1) { s.setAmount(s.getAmount() - 1); p.getInventory().setItem(i, s); }
            else p.getInventory().setItem(i, null);
            store(p, slot, bp);
            Msg.success(p, "Placed your " + Text.strip(Codec.pretty(bp)) + " in Backpack Slot " + (slot + 1) + ".");
            open(p);
            return;
        }
        Msg.error(p, "That backpack is no longer in your inventory.");
    }

    private void store(Player p, int slot, ItemStack bp) {
        StorageData.Backpacks b = mod.store().backpacks(p);
        b.items.put(String.valueOf(slot), Codec.encode(bp));
        mod.guard().reindex(p, "bpitems", Store.decodeAll(b.items));
        Msg.sound(p, Sound.ITEM_ARMOR_EQUIP_LEATHER, 1.2f);
        try { Sky.profiles().saveNow(p); } catch (RuntimeException ignored) { }
    }

    private void openPicker(Player p, int slot) {
        List<ItemStack> mine = new ArrayList<>();
        for (ItemStack s : p.getInventory().getStorageContents()) if (!Codec.empty(s) && mod.catalog().isBackpack(Codec.id(s))) mine.add(s.clone());
        if (mine.isEmpty()) {
            Msg.error(p, "You have no backpack in your inventory! Craft a Small Backpack from Leather.");
            return;
        }
        if (mine.size() == 1 && mine.get(0).getAmount() == 1) {   // only one candidate: no picker to click through
            placeFromInventory(p, slot, mine.get(0));
            return;
        }
        Sky.menus().paged("Choose a Backpack", mine, s -> Icons.withLore(s, List.of("", "&eClick to place in slot " + (slot + 1) + "!")),
                pc -> placeFromInventory(pc.click().player(), slot, pc.entry()),
                page -> page.set(page.size() - 6, Icons.back("Storage"), c -> open(c.player())), 0).open(p);
    }

    // ---- options --------------------------------------------------------------------------------------

    void openOptions(Player p, int slot) {
        Store st = mod.store();
        ItemStack bp = st.backpackItem(p, slot);
        if (bp == null) { open(p); return; }
        String id = Codec.id(bp);
        int cap = mod.catalog().backpackSlots(id);
        int used = Store.used(st.backpacks(p).contents.get(String.valueOf(slot)));
        Menu m = new Menu("Backpack Slot #" + (slot + 1), 4).chrome();
        m.set(4, Icons.withLore(skinned(bp), List.of("", "&7Slots used: &a" + used + "&7/&a" + cap)));
        m.set(10, Icons.of(Material.CHEST, "&aOpen", "&7Open this backpack.", "", "&eClick to open!"), c -> openBackpack(c.player(), slot));
        m.set(12, Icons.of(Material.ANVIL, "&aUpgrade",
                "&7Swap in a bigger backpack from", "&7your inventory. Everything inside", "&7moves over; the old one comes", "&7back to you empty.", "",
                "&eClick to choose!"), c -> openUpgrade(c.player(), slot));
        m.set(14, Icons.of(Material.MAGENTA_DYE, "&dDye",
                "&7Recolour this backpack with a dye", "&7from your inventory (&a" + StorageDials.BP_DYE_COST.get() + "&7 dye).", "",
                "&eClick to choose a colour!"), c -> openDye(c.player(), slot));
        m.set(16, Icons.of(Material.HOPPER, "&cTake Out",
                "&7Return this backpack to your", "&7inventory. It must be &cempty&7.", "",
                used == 0 ? "&eClick to take out!" : "&cEmpty it first! (" + used + " items)"), c -> takeOut(c.player(), slot));
        String skin = skinOf(bp);
        if (skin != null) {
            ItemDef sd = Sky.items().def(skin);
            m.set(22, Icons.of(Material.SHEARS, "&cRemove Skin",
                    "&7Skin: " + (sd == null ? skin : sd.rarity.code() + Text.strip(sd.name)), "",
                    "&7The skin goes back to your", "&7inventory; the backpack keeps", "&7everything inside.", "", "&eClick to remove!"),
                    c -> removeSkin(c.player(), slot));
        } else {
            int have = skinsIn(p).size();
            m.set(22, Icons.of(Material.PAINTING, "&dApply Skin",
                    "&7Give this backpack a new look with", "&7a &5Backpack Skin &7from your inventory.", "",
                    "&7Skins in your inventory: &a" + have, "", have > 0 ? "&eClick to choose!" : "&8You have no backpack skin."),
                    c -> openSkins(c.player(), slot));
        }
        m.set(m.size() - 6, Icons.back("Storage"), c -> open(c.player()));
        m.open(p);
    }

    // ---- backpack skins (wave 5) ------------------------------------------------------------------------
    // The 29 reference backpack skins (data/storage/backpack_skins.json, tag backpack_skin). The skin id is stored
    // in the backpack's ItemData.extra.skin (the same key the extras module uses for helmet skins, so its lore
    // line "Skin: ..." shows), and extras' look key is set too, so its pass keeps the head texture on a carried one.

    static final String SKIN = "skin";
    private static final org.bukkit.NamespacedKey EXTRAS_LOOK = new org.bukkit.NamespacedKey("skyclone", "extras_skin");

    static boolean isBackpackSkin(String id) {
        ItemDef d = id == null ? null : Sky.items().def(id);
        return d != null && d.has("backpack_skin") && d.skull != null && !d.skull.isBlank();
    }

    /** The skin id on a backpack, or null. */
    static String skinOf(ItemStack bp) {
        try {
            com.google.gson.JsonElement e = Sky.items().data(bp).extra.get(SKIN);
            String s = e == null || e.isJsonNull() ? null : e.getAsString();
            return isBackpackSkin(s) ? s : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** A copy of the backpack wearing its skin's head texture (display only). */
    static ItemStack skinned(ItemStack bp) {
        String s = skinOf(bp);
        if (s == null) return bp;
        ItemDef sd = Sky.items().def(s);
        ItemStack out = bp.getType() == Material.PLAYER_HEAD ? bp.clone() : bp.withType(Material.PLAYER_HEAD);
        if (out.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta sm) {
            Icons.applyTexture(sm, sd.skull);
            out.setItemMeta(sm);
        }
        return out;
    }

    private static List<ItemStack> skinsIn(Player p) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : p.getInventory().getStorageContents()) if (!Codec.empty(s) && isBackpackSkin(Codec.id(s))) out.add(s.clone());
        return out;
    }

    private void openSkins(Player p, int slot) {
        List<ItemStack> mine = skinsIn(p);
        if (mine.isEmpty()) { Msg.error(p, "You have no backpack skin in your inventory."); return; }
        Sky.menus().paged("Apply a Backpack Skin", mine, s -> Icons.withLore(s, List.of("", "&eClick to apply to slot " + (slot + 1) + "!")),
                pc -> applySkin(pc.click().player(), slot, pc.entry()),
                page -> page.set(page.size() - 6, Icons.back("Backpack Slot #" + (slot + 1)), c -> openOptions(c.player(), slot)), 0).open(p);
    }

    /** Consume the skin (found again in the inventory at click time, by uuid) and put its id on the stored backpack. */
    private void applySkin(Player p, int slot, ItemStack snapshot) {
        StorageData.Backpacks b = mod.store().backpacks(p);
        String key = String.valueOf(slot);
        ItemStack bp = Codec.decode(b.items.get(key));
        if (bp == null) { open(p); return; }
        if (skinOf(bp) != null) { Msg.error(p, "Remove the current skin first."); return; }
        String skinId = Codec.id(snapshot);
        if (!isBackpackSkin(skinId)) return;
        String u = Codec.uuid(snapshot);
        ItemStack[] inv = p.getInventory().getStorageContents();
        int at = -1;
        for (int i = 0; i < inv.length && at < 0; i++) {
            ItemStack s = inv[i];
            if (Codec.empty(s) || !skinId.equals(Codec.id(s))) continue;
            if (u == null || u.equals(Codec.uuid(s))) at = i;
        }
        if (at < 0) { Msg.error(p, "That skin is no longer in your inventory."); return; }
        ItemStack s = inv[at];
        if (s.getAmount() > 1) { s.setAmount(s.getAmount() - 1); p.getInventory().setItem(at, s); }
        else p.getInventory().setItem(at, null);
        Sky.items().update(bp, d -> d.extra.addProperty(SKIN, skinId));
        markLook(bp, skinId);
        b.items.put(key, Codec.encode(bp));
        mod.guard().reindex(p, "bpitems", Store.decodeAll(b.items));
        try { Sky.profiles().saveNow(p); } catch (RuntimeException ignored) { }
        ItemDef sd = Sky.items().def(skinId);
        Msg.success(p, "Applied " + (sd == null ? skinId : Text.strip(sd.name)) + " to Backpack Slot " + (slot + 1) + "!");
        Msg.sound(p, Sound.ENTITY_VILLAGER_WORK_CARTOGRAPHER, 1.2f);
        openOptions(p, slot);
    }

    private void removeSkin(Player p, int slot) {
        StorageData.Backpacks b = mod.store().backpacks(p);
        String key = String.valueOf(slot);
        ItemStack bp = Codec.decode(b.items.get(key));
        if (bp == null) { open(p); return; }
        String skinId = skinOf(bp);
        if (skinId == null) { openOptions(p, slot); return; }
        if (Codec.freeSlots(p) <= 0) { Msg.error(p, "Your inventory is full!"); return; }
        Sky.items().update(bp, d -> d.extra.remove(SKIN));
        markLook(bp, null);
        b.items.put(key, Codec.encode(bp));
        mod.guard().reindex(p, "bpitems", Store.decodeAll(b.items));
        Codec.giveBack(p, Sky.items().create(skinId));
        try { Sky.profiles().saveNow(p); } catch (RuntimeException ignored) { }
        Msg.success(p, "Removed the skin from Backpack Slot " + (slot + 1) + ".");
        Msg.sound(p, Sound.ENTITY_SHEEP_SHEAR, 1f);
        openOptions(p, slot);
    }

    /** extras' look key: its pass re-applies the head texture to a skinned item the player carries. Null removes it. */
    static void markLook(ItemStack bp, String skinId) {
        try {
            org.bukkit.inventory.meta.ItemMeta meta = bp.getItemMeta();
            if (meta == null) return;
            if (skinId == null) meta.getPersistentDataContainer().remove(EXTRAS_LOOK);
            else meta.getPersistentDataContainer().set(EXTRAS_LOOK, org.bukkit.persistence.PersistentDataType.STRING, skinId);
            bp.setItemMeta(meta);
        } catch (RuntimeException ignored) { }
    }

    private void takeOut(Player p, int slot) {
        Store st = mod.store();
        StorageData.Backpacks b = st.backpacks(p);
        String key = String.valueOf(slot);
        if (!b.contents.getOrDefault(key, Map.of()).isEmpty()) { Msg.error(p, "Empty the backpack before taking it out."); return; }
        if (Codec.freeSlots(p) <= 0) { Msg.error(p, "Your inventory is full!"); return; }
        ItemStack bp = Codec.decode(b.items.get(key));
        if (bp == null) { Msg.error(p, "That backpack could not be read; it stays in place. Tell an admin."); return; }
        b.items.remove(key);
        b.contents.remove(key);
        mod.guard().reindex(p, "bpitems", Store.decodeAll(b.items));
        Codec.giveBack(p, bp);
        try { Sky.profiles().saveNow(p); } catch (RuntimeException ignored) { }
        Msg.success(p, "Took your backpack out of slot " + (slot + 1) + ".");
        open(p);
    }

    private void openUpgrade(Player p, int slot) {
        ItemStack cur = mod.store().backpackItem(p, slot);
        if (cur == null) { open(p); return; }
        int have = mod.catalog().backpackSlots(Codec.id(cur));
        List<ItemStack> bigger = new ArrayList<>();
        for (ItemStack s : p.getInventory().getStorageContents()) {
            if (Codec.empty(s)) continue;
            int n = mod.catalog().backpackSlots(Codec.id(s));
            if (n > have) bigger.add(s.clone());
        }
        if (bigger.isEmpty()) { Msg.error(p, "You have no bigger backpack in your inventory."); return; }
        Sky.menus().paged("Upgrade Backpack", bigger, s -> Icons.withLore(s, List.of("", "&7" + have + " &8-> &a" + mod.catalog().backpackSlots(Codec.id(s)) + " slots",
                        "", "&eClick to swap it in!")),
                pc -> swapIn(pc.click().player(), slot, pc.entry()),
                page -> page.set(page.size() - 6, Icons.back("Backpack Slot #" + (slot + 1)), c -> openOptions(c.player(), slot)), 0).open(p);
    }

    private void swapIn(Player p, int slot, ItemStack snapshot) {
        Store st = mod.store();
        StorageData.Backpacks b = st.backpacks(p);
        String key = String.valueOf(slot);
        ItemStack old = Codec.decode(b.items.get(key));
        if (old == null) { open(p); return; }
        int oldCap = mod.catalog().backpackSlots(Codec.id(old));
        String u = Codec.uuid(snapshot);
        ItemStack[] inv = p.getInventory().getContents();
        for (int i = 0; i < inv.length; i++) {
            ItemStack s = inv[i];
            if (Codec.empty(s) || mod.catalog().backpackSlots(Codec.id(s)) <= oldCap) continue;
            if (u != null ? !u.equals(Codec.uuid(s)) : !s.isSimilar(snapshot)) continue;
            ItemStack nw = s.clone();
            nw.setAmount(1);
            if (s.getAmount() > 1) { s.setAmount(s.getAmount() - 1); p.getInventory().setItem(i, s); }
            else p.getInventory().setItem(i, null);
            b.items.put(key, Codec.encode(nw));   // contents stay with the slot
            mod.guard().reindex(p, "bpitems", Store.decodeAll(b.items));
            Codec.giveBack(p, old);
            try { Sky.profiles().saveNow(p); } catch (RuntimeException ignored) { }
            Msg.success(p, "Upgraded Backpack Slot " + (slot + 1) + " to " + mod.catalog().backpackSlots(Codec.id(nw)) + " slots!");
            Msg.sound(p, Sound.BLOCK_ANVIL_USE, 1.4f);
            openOptions(p, slot);
            return;
        }
        Msg.error(p, "That backpack is no longer in your inventory.");
    }

    private void openDye(Player p, int slot) {
        ItemStack cur = mod.store().backpackItem(p, slot);
        if (cur == null) { open(p); return; }
        String size = mod.catalog().backpackSize(Codec.id(cur));
        Map<String, String> colours = size == null ? Map.of() : mod.catalog().backpackColours.getOrDefault(size, Map.of());
        if (colours.isEmpty()) { Msg.error(p, "This backpack cannot be dyed."); return; }
        Menu m = new Menu("Dye Backpack", 4).chrome();
        int idx = 0;
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 26, 27};
        for (Map.Entry<String, String> en : colours.entrySet()) {
            if (idx >= slots.length) break;
            String colour = en.getKey();
            String target = en.getValue();
            Material dye = Material.matchMaterial(mod.catalog().dyes.getOrDefault(colour, "WHITE_DYE"));
            if (dye == null) dye = Material.WHITE_DYE;
            final Material dyeMat = dye;
            m.set(slots[idx++], Icons.of(dye, "&f" + Text.titleCase(colour), "&7Costs &a" + StorageDials.BP_DYE_COST.get() + "x " + Text.titleCase(dye.name()) + "&7.",
                    "", "&eClick to dye!"), c -> dye(c.player(), slot, target, dyeMat));
        }
        String plain = mod.catalog().backpackBase.get(size);
        if (plain != null) {
            m.set(slots[Math.min(idx, slots.length - 1)], Icons.of(Material.WATER_BUCKET, "&fWash Out", "&7Back to the plain colour. Free.", "", "&eClick to wash!"),
                    c -> dye(c.player(), slot, plain, null));
        }
        m.set(m.size() - 6, Icons.back("Backpack Slot #" + (slot + 1)), c -> openOptions(c.player(), slot));
        m.open(p);
    }

    private void dye(Player p, int slot, String targetId, Material dye) {
        StorageData.Backpacks b = mod.store().backpacks(p);
        String key = String.valueOf(slot);
        ItemStack old = Codec.decode(b.items.get(key));
        if (old == null || !Sky.items().exists(targetId)) { open(p); return; }
        if (targetId.equals(Codec.id(old))) { Msg.error(p, "It is already that colour."); return; }
        if (dye != null) {
            int cost = Math.max(0, StorageDials.BP_DYE_COST.get());
            String dyeId = Sky.items().vanillaId(dye);
            if (Sky.items().count(p, dyeId) < cost) { Msg.error(p, "You need " + cost + "x " + Text.titleCase(dye.name()) + "!"); return; }
            if (Sky.items().remove(p, dyeId, cost) < cost) { Msg.error(p, "You need " + cost + "x " + Text.titleCase(dye.name()) + "!"); return; }
        }
        // A fresh item of the new colour; the old one is consumed. Contents belong to the slot and stay.
        ItemStack nw = Sky.items().create(targetId);
        String skin = skinOf(old);   // a skin survives a recolour (it is on the item, not the colour)
        if (skin != null) {
            Sky.items().update(nw, d -> d.extra.addProperty(SKIN, skin));
            markLook(nw, skin);
        }
        b.items.put(key, Codec.encode(nw));
        mod.guard().reindex(p, "bpitems", Store.decodeAll(b.items));
        try { Sky.profiles().saveNow(p); } catch (RuntimeException ignored) { }
        Msg.success(p, dye == null ? "Your backpack is washed back to its plain colour."
                : "Your backpack is now " + Text.titleCase(dye.name().replace("_DYE", "")).toLowerCase() + ".");
        Msg.sound(p, Sound.ITEM_DYE_USE, 1f);
        openOptions(p, slot);
    }
}
