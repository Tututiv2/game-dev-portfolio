package com.skyclone.mod.extras;

import com.google.gson.JsonElement;
import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.LoreContributor;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Item skins (what Fire Sales sell). A skin is applied to a fitting helmet: the helmet keeps every stat and is
 * only drawn with the skin's head texture. The skin id lives in the item's data ({@code extra.skin}, shown in its
 * lore) AND in a module PDC key that survives core's re-render; because core redraws an item from its def on
 * every update, a light pass re-applies the look to worn, held and carried items (dial
 * {@code extras.skins.pass-seconds}). Removing a skin gives the skin item back.
 */
final class Skins implements Listener {

    static NamespacedKey KEY;

    static void init() { KEY = new NamespacedKey(Sky.plugin(), "extras_skin"); }

    static boolean isSkin(String id) {
        ItemDef d = id == null ? null : Sky.items().def(id);
        return d != null && d.has("item_skin");
    }

    static List<String> targets(String skinId) {
        Catalog.Skin s = X.catalog.skins.get(skinId);
        if (s != null) return s.targets;
        ItemDef d = Sky.items().def(skinId);
        List<String> out = new ArrayList<>();
        if (d != null && d.extra != null && d.extra.has("skinTargets"))
            for (JsonElement e : d.extra.getAsJsonArray("skinTargets")) out.add(e.getAsString());
        return out;
    }

    static boolean fits(String skinId, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return false;
        String id = Sky.items().idOf(stack);
        return id != null && targets(skinId).contains(id);
    }

    static String skinOf(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return null;
        return stack.getItemMeta().getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
    }

    /** Lore line on a skinned item. Pure on (def, data). */
    static final class Lore implements LoreContributor {
        @Override public Section section() { return Section.BEFORE_FOOTER; }
        @Override public List<String> lines(ItemDef def, ItemData data) {
            if (data == null || data.extra == null || !data.extra.has("skin")) return List.of();
            String sid = data.extra.get("skin").getAsString();
            ItemDef sd = Sky.items().def(sid);
            return List.of("&8Skin: " + (sd == null ? sid : sd.rarity.code() + Text.strip(sd.name)));
        }
    }

    // ---- the look ------------------------------------------------------------------------------------------

    /** Returns the stack to put back (it may be a new stack when the material had to change), or null if unchanged. */
    static ItemStack ensureLook(ItemStack stack) {
        String sid = skinOf(stack);
        if (sid == null) return null;
        ItemDef sd = Sky.items().def(sid);
        if (sd == null || sd.skull == null || sd.skull.isBlank()) return null;
        ItemStack out = stack;
        boolean changed = false;
        if (out.getType() != Material.PLAYER_HEAD) {
            out = out.withType(Material.PLAYER_HEAD);
            changed = true;
        }
        ItemMeta meta = out.getItemMeta();
        if (!(meta instanceof SkullMeta sm)) return changed ? out : null;
        UUID want = UUID.nameUUIDFromBytes(sd.skull.getBytes());
        PlayerProfile have = sm.getPlayerProfile();
        if (have != null && want.equals(have.getId())) return changed ? out : null;
        Icons.applyTexture(sm, sd.skull);
        out.setItemMeta(sm);
        return out;
    }

    /** Re-applies looks to every carried item with a skin. Cheap: a PDC lookup per stack. */
    static void pass(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int slot = 0; slot <= 40; slot++) {
            ItemStack it = inv.getItem(slot);
            if (it == null || it.getType().isAir() || !it.hasItemMeta()) continue;
            if (!it.getItemMeta().getPersistentDataContainer().has(KEY, PersistentDataType.STRING)) continue;
            ItemStack fixed = ensureLook(it);
            if (fixed != null) inv.setItem(slot, fixed);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p) Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (p.isOnline()) pass(p); }, 2L);
    }

    // ---- applying ------------------------------------------------------------------------------------------

    static void apply(Player p, int slot, String skinId) {
        if (!X.ready(p)) return;
        PlayerInventory inv = p.getInventory();
        ItemStack target = inv.getItem(slot);
        if (!fits(skinId, target)) { Msg.error(p, "That skin does not fit that item."); return; }
        if (Sky.items().count(p, skinId) < 1) { Msg.error(p, "You do not have that skin."); return; }
        if (Sky.items().remove(p, skinId, 1) < 1) { Msg.error(p, "You do not have that skin."); return; }
        target = inv.getItem(slot);   // removing the skin may not touch this slot, but re-read anyway
        if (target == null || !fits(skinId, target)) {
            Sky.loot().give(p, skinId, 1, LootSource.REWARD);
            Msg.error(p, "That item moved. Your skin was returned.");
            return;
        }
        String old = skinOf(target);
        Sky.items().update(target, d -> d.extra.addProperty("skin", skinId));
        ItemMeta meta = target.getItemMeta();
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, skinId);
        target.setItemMeta(meta);
        ItemStack fixed = ensureLook(target);
        inv.setItem(slot, fixed != null ? fixed : target);
        if (old != null && !old.equals(skinId)) Sky.loot().give(p, old, 1, LootSource.REWARD);
        Msg.send(p, "&aApplied " + X.itemName(skinId) + " &ato your " + X.itemName(Sky.items().idOf(target)) + "&a!");
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.6f);
    }

    static void remove(Player p, int slot) {
        if (!X.ready(p)) return;
        PlayerInventory inv = p.getInventory();
        ItemStack target = inv.getItem(slot);
        String old = skinOf(target);
        if (old == null) { Msg.error(p, "That item has no skin."); return; }
        String id = Sky.items().idOf(target);
        ItemDef def = id == null ? null : Sky.items().def(id);
        ItemMeta meta = target.getItemMeta();
        meta.getPersistentDataContainer().remove(KEY);
        target.setItemMeta(meta);
        Material base = def == null ? null : Material.matchMaterial(def.material);
        if (base != null && base != target.getType()) target = target.withType(base);
        ItemStack t = target;
        Sky.items().update(t, d -> d.extra.remove("skin"));
        Sky.items().refresh(t);
        inv.setItem(slot, t);
        Sky.loot().give(p, old, 1, LootSource.REWARD);
        Msg.send(p, "&eRemoved " + X.itemName(old) + "&e. It is back in your inventory.");
    }

    // ---- menus ---------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack hand = e.getItem();
        String id = hand == null ? null : Sky.items().idOf(hand);
        if (!isSkin(id)) return;
        e.setCancelled(true);
        if (X.ready(e.getPlayer())) chooseTarget(e.getPlayer(), id);
    }

    /** Every carried item that has, or could take, a skin. */
    static void open(Player p) {
        if (!X.ready(p)) { Msg.error(p, "Your profile is still loading."); return; }
        PlayerInventory inv = p.getInventory();
        List<Integer> slots = new ArrayList<>();
        for (int s = 0; s <= 40; s++) {
            ItemStack it = inv.getItem(s);
            if (it == null || it.getType().isAir()) continue;
            String id = Sky.items().idOf(it);
            if (id == null) continue;
            if (skinOf(it) != null || anySkinFits(id)) slots.add(s);
        }
        Sky.menus().paged("Your Skins", slots, s -> {
            ItemStack it = inv.getItem(s).clone();
            List<String> l = new ArrayList<>();
            String cur = skinOf(it);
            l.add("&7Skin: " + (cur == null ? "&8none" : X.itemName(cur)));
            l.add("");
            int held = 0;
            for (String sid : X.catalog.skins.keySet()) if (targets(sid).contains(Sky.items().idOf(it)) && Sky.items().count(p, sid) > 0) held++;
            l.add("&7Fitting skins you carry: &a" + held);
            l.add("");
            l.add("&eClick to choose a skin!");
            if (cur != null) l.add("&cRight-click to remove the skin.");
            return Icons.withLore(it, l);
        }, pc -> {
            Player pl = pc.click().player();
            int s = pc.entry();
            if (pc.click().right()) { remove(pl, s); open(pl); return; }
            chooseSkin(pl, s);
        }, menu -> {}, 0).open(p);   // playtest wave 5: the menu was built and never opened, so /skins did nothing
    }

    private static boolean anySkinFits(String itemId) {
        for (Catalog.Skin s : X.catalog.skins.values()) if (s.targets.contains(itemId)) return true;
        return false;
    }

    static void chooseSkin(Player p, int slot) {
        ItemStack target = p.getInventory().getItem(slot);
        if (target == null) return;
        String tid = Sky.items().idOf(target);
        List<String> mine = new ArrayList<>();
        for (String sid : X.catalog.skins.keySet()) if (targets(sid).contains(tid) && Sky.items().count(p, sid) > 0) mine.add(sid);
        if (mine.isEmpty()) { Msg.error(p, "You have no skins for that item. Fire Sales sell them (/firesales)."); return; }
        Sky.menus().paged("Choose a Skin", mine, sid -> {
            ItemDef d = Sky.items().def(sid);
            return Icons.skull(d == null ? null : d.skull, X.itemName(sid), List.of("", "&eClick to apply!"));
        }, pc -> apply(pc.click().player(), slot, pc.entry()), menu -> {}, 0).open(p);
    }

    static void chooseTarget(Player p, String skinId) {
        PlayerInventory inv = p.getInventory();
        List<Integer> slots = new ArrayList<>();
        for (int s = 0; s <= 40; s++) if (fits(skinId, inv.getItem(s))) slots.add(s);
        if (slots.isEmpty()) {
            Msg.error(p, "You are not carrying anything this skin fits.");
            return;
        }
        Sky.menus().paged("Apply " + Text.strip(X.itemName(skinId)), slots, s -> Icons.withLore(inv.getItem(s).clone(), List.of("", "&eClick to apply!")),
                pc -> apply(pc.click().player(), pc.entry(), skinId), menu -> {}, 0).open(p);
    }
}
