package com.skyclone.mod.weapons;

import com.google.gson.JsonElement;
import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Msg;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The teleport blades and their upgrades (research 01 item-mods table, research 06):
 * <ul>
 *   <li><b>Transmission Tuner</b> (slayer drop): +1 block on Blink Step (and Phasewarp) each, max 4,
 *       on the Voidstep Blade (AOTE) or Riftwalker Blade (AOTV). Stored as {@code ItemData.extra.tuned_transmission}.</li>
 *   <li><b>Phasewarp Merger</b> ({@code ETHERWARP_MERGER}, slayer drop): binds Ether Transmission to the
 *       Riftwalker Blade, sneak right-click: teleport onto the block you look at, up to 57 blocks, 180 mana.
 *       Stored as {@code ItemData.extra.ethermerge = 1}.</li>
 *   <li><b>Phasewarp Conduit</b> ({@code ETHERWARP_CONDUIT}): the same jump as a stand-alone item.</li>
 * </ul>
 * The reference applies tuners and the merger in an anvil: the reforges module's Reforge Anvil does it (wave 4);
 * {@code /weapons tune} and {@code /weapons merge} and the Arsenal's Teleport Upgrades button do the same.
 * Soulstream (reference Soulflow, 1 per warp) is charged by the soulflow module's ability gate (wave 5): it refuses a
 * Phasewarp the player can not pay for and takes 1 after a successful warp; nothing to do here (see {@link SoulflowLink}).
 */
final class Teleporters {

    private Teleporters() {}

    static final String TUNER = "TRANSMISSION_TUNER";
    static final String MERGER = "ETHERWARP_MERGER";
    static final Set<String> TUNABLE = Set.of("ASPECT_OF_THE_END", "ASPECT_OF_THE_VOID");
    static final Set<String> MERGEABLE = Set.of("ASPECT_OF_THE_VOID");
    static final int MAX_TUNERS = 4;

    static int tuners(ItemData d) {
        JsonElement e = d == null ? null : d.extra.get("tuned_transmission");
        try { return e == null ? 0 : Math.max(0, Math.min(MAX_TUNERS, e.getAsInt())); } catch (RuntimeException ex) { return 0; }
    }

    static boolean merged(ItemData d) {
        JsonElement e = d == null ? null : d.extra.get("ethermerge");
        try { return e != null && e.getAsInt() > 0; } catch (RuntimeException ex) { return false; }
    }

    static int tuners(ItemStack s) {
        try { return tuners(Sky.items().data(s)); } catch (RuntimeException e) { return 0; }
    }

    static boolean merged(ItemStack s) {
        try { return merged(Sky.items().data(s)); } catch (RuntimeException e) { return false; }
    }

    static double blinkRange(ItemStack s) { return WeaponMath.teleportRange(8, tuners(s)); }

    static double etherRange(ItemStack s) { return WeaponMath.teleportRange(WeaponDials.ETHER_RANGE.get(), tuners(s)); }

    // ---- Ether Transmission -------------------------------------------------------------------------

    /**
     * Teleport onto the top of the block the player looks at, up to {@code range} blocks. The block must be
     * solid with two free blocks above it (the reference rule). Returns false, with the reference's message,
     * when there is no valid target; no mana is charged then.
     */
    static boolean ether(Player p, double range) {
        Location eye = p.getEyeLocation();
        RayTraceResult r = p.getWorld().rayTraceBlocks(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true);
        Block b = r == null ? null : r.getHitBlock();
        if (b == null) { Msg.error(p, "No target block in range!"); return false; }
        Block feet = b.getRelative(0, 1, 0), head = b.getRelative(0, 2, 0);
        if (b.isPassable() || !feet.isPassable() || !head.isPassable()) {
            Msg.error(p, "There are blocks in the way!");
            return false;
        }
        Location to = b.getLocation().add(0.5, 1.0, 0.5);
        to.setYaw(p.getLocation().getYaw());
        to.setPitch(p.getLocation().getPitch());
        Location from = p.getLocation();
        Fx.burst(from.clone().add(0, 1, 0), Particle.PORTAL, 30, 0.4);
        p.setFallDistance(0);
        p.teleport(to);
        p.setFallDistance(0);
        Fx.burst(to.clone().add(0, 1, 0), Particle.REVERSE_PORTAL, 30, 0.4);
        p.getWorld().playSound(to, Sound.ENTITY_ENDER_DRAGON_HURT, 0.6f, 0.53f);   // the reference warp sound
        return true;
    }

    // ---- applying upgrades ----------------------------------------------------------------------------

    /** Apply one Transmission Tuner from the inventory to the held blade. */
    static boolean applyTuner(Player p) {
        ItemStack held = p.getInventory().getItemInMainHand();
        String id = Traits.idOf(held);
        if (id == null || !TUNABLE.contains(id)) {
            Msg.error(p, "Hold a Voidstep Blade or Riftwalker Blade to tune it.");
            return false;
        }
        int have = tuners(held);
        if (have >= MAX_TUNERS) { Msg.error(p, "This blade already holds " + MAX_TUNERS + " Transmission Tuners."); return false; }
        if (Sky.items().count(p, TUNER) <= 0) { Msg.error(p, "You need a Transmission Tuner in your inventory."); return false; }
        if (Sky.items().remove(p, TUNER, 1) != 1) return false;
        // the held stack may have shifted if the tuner sat in the same slot: re-read it
        ItemStack blade = p.getInventory().getItemInMainHand();
        if (!id.equals(Traits.idOf(blade))) { Sky.loot().give(p, Sky.items().create(TUNER), com.skyclone.api.loot.LootSource.OTHER); return false; }
        Sky.items().update(blade, d -> d.extra.addProperty("tuned_transmission", have + 1));
        p.getInventory().setItemInMainHand(blade);
        Msg.success(p, "Tuned! Blink Step now carries you &a" + (int) blinkRange(blade) + " blocks&a (" + (have + 1) + "/" + MAX_TUNERS + ").");
        Fx.sound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.7f, 1.4f);
        return true;
    }

    /** Apply the Phasewarp Merger from the inventory to the held Riftwalker Blade. */
    static boolean applyMerger(Player p) {
        ItemStack held = p.getInventory().getItemInMainHand();
        String id = Traits.idOf(held);
        if (id == null || !MERGEABLE.contains(id)) { Msg.error(p, "Hold a Riftwalker Blade to merge it."); return false; }
        if (merged(held)) { Msg.error(p, "This blade is already merged."); return false; }
        if (Sky.items().count(p, MERGER) <= 0) {
            ItemDef m = Sky.items().def(MERGER);
            Msg.error(p, "You need a " + (m == null ? "Phasewarp Merger" : com.skyclone.api.ui.Text.strip(m.name)) + " in your inventory.");
            return false;
        }
        if (Sky.items().remove(p, MERGER, 1) != 1) return false;
        ItemStack blade = p.getInventory().getItemInMainHand();
        if (!id.equals(Traits.idOf(blade))) { Sky.loot().give(p, Sky.items().create(MERGER), com.skyclone.api.loot.LootSource.OTHER); return false; }
        Sky.items().update(blade, d -> d.extra.addProperty("ethermerge", 1));
        p.getInventory().setItemInMainHand(blade);
        Msg.success(p, "Merged! &7Sneak and right-click to warp onto the block you look at.");
        Fx.sound(p.getLocation(), Sound.BLOCK_END_PORTAL_FRAME_FILL, 1f, 0.8f);
        return true;
    }

    /** Lore lines for a tuned / merged blade. */
    static List<String> lore(ItemDef def, ItemData data) {
        List<String> out = new ArrayList<>();
        if (def == null || !TUNABLE.contains(def.id)) return out;
        int t = tuners(data);
        if (t > 0) out.add("&7Transmission Tuners: &a" + t + "&7/" + MAX_TUNERS + " &8(+" + t + " blocks)");
        if (MERGEABLE.contains(def.id)) out.add(merged(data) ? "&5Phasewarp merged" : "&8Not merged: apply a Phasewarp Merger.");
        return out;
    }

    /** Arsenal panel lines for the held item. */
    static List<String> panel(ItemStack held) {
        List<String> out = new ArrayList<>();
        String id = Traits.idOf(held);
        if (id == null || !TUNABLE.contains(id)) {
            out.add("&7Hold a &9Voidstep Blade &7or &5Riftwalker");
            out.add("&5Blade &7to upgrade its teleport.");
            return out;
        }
        out.add("&7Blink Step range: &a" + (int) blinkRange(held) + " blocks");
        out.add("&7Transmission Tuners: &a" + tuners(held) + "&7/" + MAX_TUNERS);
        if (MERGEABLE.contains(id)) {
            out.add(merged(held) ? "&7Phasewarp: &a" + (int) etherRange(held) + " blocks &8(sneak + right-click)" : "&7Phasewarp: &cnot merged");
        }
        if (MERGEABLE.contains(id) && merged(held) && com.skyclone.api.Sky.has(com.skyclone.api.feature.Features.Soulflow.class))
            out.add("&7Phasewarp cost: &31⸎ Soulstream &8(per warp)");
        out.add("");
        out.add("&eLeft-click &7to apply a Transmission Tuner");
        if (MERGEABLE.contains(id) && !merged(held)) out.add("&eRight-click &7to apply a Phasewarp Merger");
        return out;
    }
}
