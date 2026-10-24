package com.skyclone.mod.foraging;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Diving (research 01 §2.2 Respiration / Pressure Resistance; research 03: the Moonmarsh's depths under its loch,
 * the Torrent Canyon's Spring Depths "need Respiration + Pressure Resistance"). See {@link DepthMath}.
 * <ul>
 *   <li>On every foraging island your <b>breath</b> lasts {@code Respiration x 10} ticks (30 base = vanilla
 *       15 s); a full Diver's or Abyssal set never runs out.</li>
 *   <li>Inside a depths zone ({@code foraging.depth-zones}: our Dimwater Depths and the galatea module's Spring
 *       Depths) water <b>pressure</b> grows with every block below the surface; Pressure Resistance cancels it,
 *       what is left hurts every second and slows you. The action bar shows depth, pressure and resistance.</li>
 * </ul>
 */
final class Depths implements Listener {

    /** Players whose max air we changed (restored when they leave the foraging islands). */
    private final Set<UUID> changed = new HashSet<>();
    /** Last action-bar line per player (null = none). */
    private final Map<UUID, String> bar = new HashMap<>();

    void register() {
        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());
        Sky.vitals().actionBarSegment("foraging_depth", 60, p -> bar.get(p.getUniqueId()));
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 20L, 20L);
    }

    static boolean depthZone(Layout.Zone z) {
        if (z == null || z.id() == null) return false;
        for (String s : FDials.depthZones.get().split(",")) if (!s.isBlank() && z.id().equalsIgnoreCase(s.trim())) return true;
        return false;
    }

    /** Blocks of water above the player's eyes (0 when the head is dry). */
    static int depth(Player p) {
        Block eye = p.getEyeLocation().getBlock();
        if (!wet(eye)) return 0;
        World w = p.getWorld();
        int n = 0;
        for (int y = eye.getY(); y < Math.min(w.getMaxHeight(), eye.getY() + 128); y++) {
            if (!wet(w.getBlockAt(eye.getX(), y, eye.getZ()))) break;
            n++;
        }
        return n;
    }

    private static boolean wet(Block b) {
        Material m = b.getType();
        if (m == Material.WATER || m == Material.BUBBLE_COLUMN || m == Material.KELP || m == Material.KELP_PLANT
                || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS) return true;
        return b.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged();
    }

    private void tick() {
        boolean on = FDials.depths.get();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (!on || !Sky.profiles().isReady(p) || !Chopper.foragingIsland(Chopper.island(p.getWorld()))) {
                restore(p);
                continue;
            }
            double resp = Bonuses.respiration(p);
            int max = DepthMath.maxAirTicks(resp, FDials.depthAirTicksPerPoint.get());
            if (p.getMaximumAir() != max) p.setMaximumAir(max);
            changed.add(id);
            if (p.getRemainingAir() > max) p.setRemainingAir(max);
            int depth = depth(p);
            if (depth > 0 && Bonuses.breathesWater(p)) p.setRemainingAir(max);   // One with the Fish

            Layout.Zone z = Sky.worlds().zoneAt(p.getLocation());
            if (depth <= 0 || !depthZone(z)) { bar.remove(id); continue; }
            double pressure = DepthMath.pressure(depth, FDials.depthPressurePerBlock.get());
            double res = Bonuses.pressureResistance(p);
            double eff = DepthMath.effective(pressure, res);
            String color = eff <= 0 ? "&9" : eff < 20 ? "&e" : "&c";
            bar.put(id, "&3" + depth + "m " + color + "❍ " + Text.commasInt(pressure) + "&7/&9" + Text.commasInt(res) + " Pressure");
            if (eff > 0) {
                double frac = DepthMath.damageFraction(eff, FDials.depthDamagePercent.get());
                Sky.vitals().damage(p, Sky.vitals().maxHealth(p) * frac, "pressure");
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, eff >= 30 ? 1 : 0, true, false, false));
                p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 60, 0, true, false, false));
            }
        }
    }

    private void restore(Player p) {
        UUID id = p.getUniqueId();
        bar.remove(id);
        if (changed.remove(id)) {
            p.setMaximumAir(300);
            if (p.getRemainingAir() > 300) p.setRemainingAir(300);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { restore(e.getPlayer()); }

    /** For admin output: what the player has right now. */
    static List<String> report(Player p) {
        double resp = Bonuses.respiration(p), res = Bonuses.pressureResistance(p);
        int depth = depth(p);
        double pressure = DepthMath.pressure(depth, FDials.depthPressurePerBlock.get());
        return List.of("&7Respiration &3" + Text.commas(resp) + " &8(" + Text.commas(DepthMath.maxAirTicks(resp, FDials.depthAirTicksPerPoint.get()) / 20.0)
                        + " s of air" + (Bonuses.breathesWater(p) ? ", breathes water" : "") + ")",
                "&7Pressure Resistance &9" + Text.commas(res) + " &8(safe to " + (int) Math.floor(res / Math.max(0.01, FDials.depthPressurePerBlock.get())) + " blocks deep)",
                "&7Depth &3" + depth + " &7pressure &e" + Text.commas(pressure) + " &7in a depths zone: &a" + depthZone(Sky.worlds().zoneAt(p.getLocation())));
    }
}
