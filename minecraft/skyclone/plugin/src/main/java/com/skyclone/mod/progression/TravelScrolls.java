package com.skyclone.mod.progression;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SkyBlock XP for Fast Travel (research 02 section 6: "Fast Travel unlocked: 15 per travel scroll, max 300").
 * Nothing paid it before 2026-10-04, so the Leveling task sat at 0/300 for ever (wave-3 playtest read it as
 * broken). Every module that owns a scroll (hub, combatzones, foraging, mining) consumes it synchronously on
 * the right-click when it unlocks something, and refuses without consuming when it was already unlocked; so
 * "a TRAVEL_SCROLL item left the main hand during the click" is exactly "a scroll was read". Watching that
 * from here needs no change in those modules: LOWEST records the held stack, MONITOR (cancelled events too:
 * the owners cancel the click) compares. One award per scroll id, ever ({@code scroll:<ITEM_ID>}).
 */
final class TravelScrolls implements Listener {

    private record Held(String id, int slot, int amount) {}

    private final Map<UUID, Held> before = new HashMap<>();

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClickFirst(PlayerInteractEvent e) {
        before.remove(e.getPlayer().getUniqueId());
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!Prog.ready(p) || Prog.lvlScrollXp.get() <= 0) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        String id = scrollId(hand);
        if (id == null || Prog.level(p).awards.containsKey("scroll:" + id)) return;
        before.put(p.getUniqueId(), new Held(id, p.getInventory().getHeldItemSlot(), hand.getAmount()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClickLast(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        Held h = before.remove(p.getUniqueId());
        if (h == null || !Prog.ready(p)) return;
        ItemStack now = p.getInventory().getItem(h.slot());
        int left = now != null && h.id().equals(Sky.items().idOf(now)) ? now.getAmount() : 0;
        if (left >= h.amount()) return;   // refused ("already unlocked") or not a read
        ItemDef def = Sky.items().def(h.id());
        String name = def == null || def.name == null ? null : Text.strip(def.name);
        Prog.levels.awardScroll(p, h.id(), name);
    }

    void forget(Player p) { before.remove(p.getUniqueId()); }

    private static String scrollId(ItemStack it) {
        if (it == null || it.getType().isAir()) return null;
        String id = Sky.items().idOf(it);
        if (id == null) return null;
        ItemDef def = Sky.items().def(id);
        return def != null && def.category == ItemCategory.TRAVEL_SCROLL ? id : null;
    }
}
