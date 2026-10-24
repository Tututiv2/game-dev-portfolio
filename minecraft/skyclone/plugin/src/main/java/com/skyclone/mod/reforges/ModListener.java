package com.skyclone.mod.reforges;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Essence pouch intake and the hub's physical reforge anvil. */
final class ModListener implements Listener {

    private static String essenceType(String itemId) {
        if (itemId == null || !itemId.startsWith("ESSENCE_")) return null;
        ItemDef d = Sky.items().def(itemId);
        if (d == null || d.extra == null || !d.extra.has("essence")) return null;
        return d.extra.get("essence").getAsString();
    }

    /** Essence a player EARNS (dungeon chests, slayer drops, salvaging) goes straight into the pouch. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onGain(SkyEvents.ItemGain e) {
        if (!ReforgesModule.absorbEssence.get()) return;
        String type = essenceType(e.itemId);
        if (type == null || e.amount <= 0 || !Sky.profiles().isReady(e.player)) return;
        Gear.addEssence(e.player, type, e.amount);
        e.setCancelled(true);
        Sky.vitals().flash(e.player, "&d+" + Text.commasInt(e.amount) + " " + Text.strip(Gear.essenceName(type)), 40);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        Action a = e.getAction();
        if (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            String type = Gear.empty(hand) ? null : essenceType(Sky.items().idOf(hand));
            if (type != null) {
                e.setCancelled(true);
                if (!Sky.profiles().isReady(p)) return;
                int n = hand.getAmount();
                p.getInventory().setItemInMainHand(null);
                Gear.addEssence(p, type, n);
                Msg.send(p, "&aStored &d" + Text.commasInt(n) + " " + Text.strip(Gear.essenceName(type)) + "&a. You now have &d"
                        + Text.commasInt(Gear.essence(p, type)) + "&a.");
                Msg.sound(p, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.6f);
                return;
            }
        }
        if (a != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        Material t = b.getType();
        if (t != Material.ANVIL && t != Material.CHIPPED_ANVIL && t != Material.DAMAGED_ANVIL) return;
        if (!"hub".equals(Sky.worlds().islandOf(b.getWorld()))) return;
        Location anchor = Sky.worlds().anchor("hub", "npc.reforge_anvil");
        if (anchor == null || anchor.getWorld() != b.getWorld()) return;
        int r = ReforgesModule.anvilBlockRadius.get();
        if (anchor.distanceSquared(b.getLocation().add(0.5, 0.5, 0.5)) > (double) r * r) return;
        e.setCancelled(true);
        Menus.anvil(p);
    }
}
