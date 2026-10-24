package com.skyclone.mod.mining;

import com.skyclone.api.Sky;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The crystal caverns (reference: Crystal Hollows): Heat in the deep magma layer and the Crystal
 * Nucleus, where the five zone crystals are placed for a loot bundle (research 2.11).
 */
final class Hollows {

    static final List<String> NUCLEUS_GEMS = List.of("AMETHYST", "JADE", "SAPPHIRE", "AMBER", "TOPAZ");

    private final MiningModule m;
    private final Map<UUID, Integer> heat = new ConcurrentHashMap<>();
    private int tick;

    Hollows(MiningModule m) {
        this.m = m;
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::second, 20L, 20L);
    }

    int heat(Player p) { return heat.getOrDefault(p.getUniqueId(), 0); }

    /** Admin/testing: set a player's Heat (0-100). */
    void setHeat(Player p, int n) { int v = Math.max(0, Math.min(100, n)); if (v == 0) heat.remove(p.getUniqueId()); else heat.put(p.getUniqueId(), v); }

    void cool(Player p, int n) { heat.computeIfPresent(p.getUniqueId(), (k, v) -> Math.max(0, v - n)); }

    private boolean hot(Player p) {
        if (!"crystal_hollows".equals(MiningUtil.island(p))) return false;
        if (p.getLocation().getBlockY() < m.dials.heatY.get()) return true;
        Layout.Zone z = Sky.worlds().zoneOf(p);
        return z != null && (z.id() + z.displayName()).toLowerCase().contains("magma");
    }

    private void second() {
        tick++;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!MiningUtil.ready(p)) continue;
            UUID u = p.getUniqueId();
            int h = heat.getOrDefault(u, 0);
            if (hot(p)) {
                double res = Sky.stats().get(p, Stat.HEAT_RESISTANCE);
                if (res < 1000 && tick % Math.max(1, m.dials.heatSeconds.get()) == 0
                        && MiningUtil.rnd().nextDouble() < 100.0 / (100.0 + res * 5)) h = Math.min(100, h + 1);
                if (h == 50 && heat.getOrDefault(u, 0) == 49) Msg.send(p, "&6It's getting really hot down here...");
                if (h == 90 && heat.getOrDefault(u, 0) == 89) Msg.send(p, "&c&lYou are about to burn up! Climb out of the magma layer!");
                if (h >= 100) Sky.vitals().damage(p, Sky.vitals().maxHealth(p) * m.dials.hollowsHeatDamage.get(), "heat");
            } else {
                h = Math.max(0, h - 3);
            }
            if (h <= 0) heat.remove(u); else heat.put(u, h);
        }
    }

    void forget(UUID u) { heat.remove(u); }

    // ---- Crystal Nucleus ------------------------------------------------------------------------

    /** Is the player at the Nucleus (anchor / area "nucleus", or the Hollows merchant as a fallback)? */
    boolean atNucleus(Player p) {
        if (!"crystal_hollows".equals(MiningUtil.island(p))) return false;
        int r = m.dials.nucleusRadius.get();
        Location l = p.getLocation();
        for (String a : List.of("nucleus", "npc.nucleus")) {
            Location at = Sky.worlds().anchor("crystal_hollows", a);
            if (at != null && at.getWorld() == l.getWorld() && at.distanceSquared(l) <= r * r) return true;
        }
        Box b = Sky.worlds().area("crystal_hollows", "nucleus");
        if (b != null && b.grow(r).contains(l.getX(), l.getY(), l.getZ())) return true;
        Entity merchant = Sky.npcs().entity("mining_hollows_merchant");
        return merchant != null && merchant.getWorld() == l.getWorld() && merchant.getLocation().distanceSquared(l) <= r * r;
    }

    void openNucleus(Player p) {
        MiningData d = MiningUtil.data(p);
        Menu menu = new Menu("Crystal Nucleus", 6).chrome();
        boolean here = atNucleus(p);
        menu.set(4, Icons.of(Material.BEACON, "&5Crystal Nucleus",
                "&7Place all five zone crystals here", "&7to open the Nucleus and claim its", "&7treasure. Then hunt them again.",
                "", "&7Runs completed: &a" + d.nucleusRuns, "", here ? "&aYou are at the Nucleus." : "&cGo to the heart of the crystal caverns."));
        int[] pos = {20, 21, 22, 23, 24};
        for (int i = 0; i < NUCLEUS_GEMS.size(); i++) {
            String gem = NUCLEUS_GEMS.get(i);
            String id = gem + "_CRYSTAL";
            boolean placed = d.nucleusPlaced.contains(gem);
            int have = Sky.items().count(p, id);
            if (placed) {
                menu.set(pos[i], Icons.glow(Icons.of(Material.END_CRYSTAL, MiningUtil.itemName(id), "&a&lPLACED")));
            } else {
                menu.set(pos[i], Icons.of(Material.GLASS, "&7" + Text.titleCase(gem) + " Crystal Socket",
                        "&7Found in the " + zoneOf(gem) + "&7.", "", "&7In your inventory: " + (have > 0 ? "&a" + have : "&c0"), "",
                        have > 0 && here ? "&eClick to place!" : "&cYou need the crystal here."), c -> place(c.player(), gem));
            }
        }
        menu.set(40, Icons.of(Material.CHEST, "&6Nucleus Loot",
                "&7Opening the Nucleus gives:", "&d2,500-5,000 Gemstone Powder", "&3+20,000 Mining XP", "&5+800 Heart of the Mountain XP",
                "&7one Fine gem of each crystal's colour,", "&7and 14-19 rolls of loot: precursor", "&7parts, goblin eggs, Hoardite, Varnas",
                "&7Fragments, Jadecrown, Gemstone Mixture", "&7and, very rarely, Varnas Alloy."));
        menu.set(48, Icons.back("Mining"), c -> m.menus.openMain(c.player()));
        menu.open(p);
    }

    static String zoneOf(String gem) {
        return switch (gem) {
            case "AMETHYST" -> "&5Jungle";
            case "JADE" -> "&aMithril Deposits";
            case "SAPPHIRE" -> "&bPrecursor Remnants";
            case "AMBER" -> "&6Goblin Holdout";
            case "TOPAZ" -> "&eMagma Fields";
            default -> "&ccaverns";
        };
    }

    private void place(Player p, String gem) {
        if (!atNucleus(p)) { Msg.error(p, "You must be at the Crystal Nucleus."); return; }
        MiningData d = MiningUtil.data(p);
        if (d.nucleusPlaced.contains(gem)) return;
        if (Sky.items().remove(p, gem + "_CRYSTAL", 1) < 1) { Msg.error(p, "You don't have that crystal."); return; }
        d.nucleusPlaced.add(gem);
        Msg.success(p, "You placed the " + Text.titleCase(gem) + " Crystal!");
        Msg.sound(p, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f);
        if (d.nucleusPlaced.containsAll(NUCLEUS_GEMS)) {
            d.nucleusPlaced.clear();
            d.nucleusRuns++;
            List<String> got = m.loot.rollTable(p, m.loot.f.nucleus, "RUBY", "GEMSTONE", LootSource.MINING);
            // Mole pet "Nucleic Veins": a chance (%) for one more item from the bundle
            double extra = MiningUtil.pet(p, "mole_nucleic_veins");
            if (extra > 0 && m.loot.f.nucleus != null && !m.loot.f.nucleus.table.isEmpty() && MiningUtil.rnd().nextDouble() * 100 < extra)
                got.add(m.loot.give(p, m.loot.pick(m.loot.f.nucleus.table), "RUBY", LootSource.MINING) + " &6(Nucleic Veins)");
            Msg.box(p, "&5&lCRYSTAL NUCLEUS &7opened! &8(run #" + d.nucleusRuns + ")", got);
            Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f);
            Sky.service(com.skyclone.api.feature.Features.SkyLevel.class).award(p, "mining:nucleus:first", 15, "First Crystal Nucleus run");
        }
        openNucleus(p);
    }

    List<String> crystalStatus(Player p) {
        MiningData d = MiningUtil.data(p);
        List<String> out = new ArrayList<>();
        for (String g : NUCLEUS_GEMS) {
            boolean placed = d.nucleusPlaced.contains(g);
            boolean have = Sky.items().count(p, g + "_CRYSTAL") > 0;
            out.add((placed ? "&a✔ " : have ? "&e● " : "&c✖ ") + "&7" + Text.titleCase(g) + (placed ? " &8(placed)" : have ? " &8(carried)" : ""));
        }
        return out;
    }
}
