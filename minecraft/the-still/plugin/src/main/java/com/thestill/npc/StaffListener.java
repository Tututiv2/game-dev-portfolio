package com.thestill.npc;

import com.thestill.economy.SellService;
import com.thestill.player.Profile;
import com.thestill.player.Profiles;
import com.thestill.ui.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

import java.util.Map;

/**
 * Right-clicking a clerk.
 *
 * <p>Three guards before anything happens, and each one is a bug somewhere else:
 *
 * <ul>
 *   <li><b>The hand check.</b> {@code PlayerInteractEntityEvent} fires <em>once per
 *       hand</em>. Shadow Prison's shop listener has no such guard and its utility-NPC
 *       listener does; the difference is whether the menu opens once or twice.
 *   <li><b>Cancel the event.</b> A villager without this opens the vanilla trade GUI
 *       over the top of ours.
 *   <li><b>PDC, not name.</b> The counter id is a persistent value on the entity, so a
 *       renamed or duplicated clerk cannot be mistaken for a different one.
 * </ul>
 */
public final class StaffListener implements Listener {

    private final Plugin plugin;
    private final RigStaff staff;
    private final Profiles profiles;
    private final SellService selling;

    /**
     * What each counter says until it has something to sell.
     *
     * <p>A clerk who says "not yet, and here is why" is the same free storytelling as a
     * sealed shutter, and it costs a string. It is also the build plan's rule made
     * visible: <b>do not build ahead of the gates.</b> These doors are open, the people
     * are behind them, and what they trade in does not exist yet.
     */
    private static final Map<String, String> LINES = Map.ofEntries(
            Map.entry("tools", "Real picks, when the company sends them down. Yours will do for now."),
            Map.entry("vault", "Nothing to bank into yet. When there is, what you carry stops being safe."),
            Map.entry("smeltery", "Furnace is cold. Bring me ore when I can light it."),
            Map.entry("auger", "Bite against muffle. Fast chews loud — you will learn that part yourself."),
            Map.entry("mess", "Kitchen's shut. Everyone's still down there."),
            Map.entry("claims", "Plots are marked out. Nobody owns one yet, including you."),
            Map.entry("hiring", "No Listeners on the books. You would not want one yet."),
            Map.entry("outfitter", "Wool wrap, felt pick. Quiet costs you heat — that is the whole trade."),
            Map.entry("instruments", "Goggles read what you cannot feel. Not fitted yet."),
            Map.entry("charts", "The heat map is blank. Nothing has been warm long enough to draw."),
            Map.entry("enchanter", "Hush or vent. One line, two ends, and you cannot run both."),
            Map.entry("suit", "Seal's a number from nought to a hundred. Set it wrong and something notices."),
            Map.entry("winch", "Pull the lever and it screams. That is not a fault. That is the fare."),
            Map.entry("muster", "If it screams, you come here. Not down. Here."));

    public StaffListener(Plugin plugin, RigStaff staff, Profiles profiles, SellService selling) {
        this.plugin = plugin;
        this.staff = staff;
        this.profiles = profiles;
        this.selling = selling;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        // Once per hand, and we only want the one.
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        String counter = staff.counterOf(event.getRightClicked());
        if (counter == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();

        if (counter.equals("assay")) {
            Profile profile = profiles.get(player.getUniqueId());
            if (profile == null) {
                return;
            }
            new AssayMenu(plugin, player, profile, profiles, selling).open();
            return;
        }
        String line = LINES.get(counter);
        if (line != null) {
            player.sendMessage(Component.text("  » ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(line, NamedTextColor.GRAY, TextDecoration.ITALIC)));
        } else {
            Msg.info(player, "They look at you and go back to what they were doing.");
        }
    }

    /** A clerk cannot be killed, and cannot be turned into something else. */
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (staff.counterOf(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }

    /**
     * A villager can become a witch or a zombie villager.
     *
     * <p>There is no weather and there are no hostile spawns in this dimension yet —
     * and both of those change in Phase 2, which is exactly why this is written now
     * rather than after the first report of the Assay Office being struck by lightning.
     */
    @EventHandler(ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        if (staff.counterOf(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }
}
