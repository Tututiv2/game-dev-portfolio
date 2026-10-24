package com.skyclone.mod.soulflow;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Msg;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Raw Soulstream / Soulstream in the hand: right-click internalizes one (sneak: the whole stack), left-click in the
 * air opens the Soulstream menu. Reference: "Hold and right-click to consume, gaining +160⸎ Soulflow!".
 */
final class SoulflowItems implements Listener {

    private final SoulflowService svc;
    private final SoulflowMenu menu;

    SoulflowItems(SoulflowService svc, SoulflowMenu menu) { this.svc = svc; this.menu = menu; }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() == Action.PHYSICAL) return;
        Player p = e.getPlayer();
        ItemStack held = p.getInventory().getItemInMainHand();
        String id = Sky.items().idOf(held);
        if (svc.config().unitsOf(id) <= 0) return;
        Action a = e.getAction();
        if (a == Action.LEFT_CLICK_AIR) {
            menu.open(p);
            return;
        }
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        e.setUseItemInHand(Event.Result.DENY);
        e.setUseInteractedBlock(Event.Result.DENY);
        internalizeHeld(p, id, p.isSneaking());
    }

    /** Internalize from the hand stack's id: one, or every one in the inventory with {@code all}. */
    long internalizeHeld(Player p, String id, boolean all) {
        String why = svc.internalizeBlock(p);
        if (why != null) { Msg.error(p, why); return 0; }
        long units = svc.internalizeInventory(p, id, all ? Integer.MAX_VALUE : 1);
        if (units <= 0) {
            Msg.error(p, "Your Soulstream is full!");
            return 0;
        }
        Msg.send(p, "&7Internalized &3+" + SoulflowMath.units(units) + " Soulstream&7! &8(" + SoulflowMath.units(svc.pool(p)) + " held)");
        Msg.sound(p, Sound.PARTICLE_SOUL_ESCAPE, 1.4f);
        return units;
    }
}
