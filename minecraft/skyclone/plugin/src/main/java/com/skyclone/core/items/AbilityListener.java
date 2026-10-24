package com.skyclone.core.items;

import com.skyclone.api.item.AbilityDef;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Trigger detection for item abilities. Only the MAIN hand counts (the off-hand copy of every
 * interact event is ignored). One trigger per player per tick: the client sends several packets
 * for one right click (block + air), and an ability must not fire twice for it.
 *
 * <p>Block-protection cancels are deliberately ignored for clicks on blocks/air: an ability must
 * work in the protected hub. Entity right-clicks respect cancellation, because the NPC service
 * cancels the click on an NPC and talking to an NPC must not also teleport you.
 */
final class AbilityListener implements Listener {

    private final AbilityServiceImpl abilities;
    private final Map<UUID, Integer> lastTick = new HashMap<>();

    AbilityListener(AbilityServiceImpl abilities) { this.abilities = abilities; }

    private boolean once(Player p) {
        int now = Bukkit.getCurrentTick();
        Integer last = lastTick.put(p.getUniqueId(), now);
        return last == null || last != now;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a == Action.PHYSICAL) return;
        Player p = e.getPlayer();
        ItemStack held = p.getInventory().getItemInMainHand();
        if (held.getType().isAir()) return;
        if (abilities.clickConsumed(p)) return;   // a module used this click (AbilityService.consumeClick)
        // Right-clicking something you OPEN (enchanting table, crafting table, anvil, chest, door...)
        // is a use of that block, not of the held item: firing the ability as well opened the table
        // AND teleported the player (playtest wave 4). Sneaking skips the block, as in vanilla.
        if (a == Action.RIGHT_CLICK_BLOCK && !p.isSneaking() && e.getClickedBlock() != null
                && e.getClickedBlock().getType().isInteractable()) return;
        AbilityDef.Trigger t;
        if (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) {
            t = p.isSneaking() ? AbilityDef.Trigger.SNEAK_RIGHT_CLICK : AbilityDef.Trigger.RIGHT_CLICK;
        } else {
            t = AbilityDef.Trigger.LEFT_CLICK;
        }
        if (AbilityServiceImpl.find(abilitiesDef(held), t) == null) return;
        if (!once(p)) { if (t != AbilityDef.Trigger.LEFT_CLICK) e.setUseItemInHand(Event.Result.DENY); return; }
        if (abilities.trigger(p, held, t) && t != AbilityDef.Trigger.LEFT_CLICK) {
            // the ability replaces the item's vanilla use (no bow draw, no block placement)
            e.setUseItemInHand(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !(e.getRightClicked() instanceof LivingEntity)) return;
        Player p = e.getPlayer();
        ItemStack held = p.getInventory().getItemInMainHand();
        if (held.getType().isAir()) return;
        AbilityDef.Trigger t = p.isSneaking() ? AbilityDef.Trigger.SNEAK_RIGHT_CLICK : AbilityDef.Trigger.RIGHT_CLICK;
        if (AbilityServiceImpl.find(abilitiesDef(held), t) == null) return;
        if (abilities.clickConsumed(p)) return;
        if (!once(p)) return;
        abilities.trigger(p, held, t);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent e) {
        if (!e.isSneaking()) return;
        Player p = e.getPlayer();
        ItemStack held = p.getInventory().getItemInMainHand();
        if (held.getType().isAir()) return;
        if (AbilityServiceImpl.find(abilitiesDef(held), AbilityDef.Trigger.SNEAK) == null) return;
        abilities.trigger(p, held, AbilityDef.Trigger.SNEAK);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        lastTick.remove(e.getPlayer().getUniqueId());
        abilities.forget(e.getPlayer());
    }

    private com.skyclone.api.item.ItemDef abilitiesDef(ItemStack held) {
        return ItemsCore.items().def(ItemsCore.items().idOf(held));
    }
}
