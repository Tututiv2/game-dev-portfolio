package com.skyclone.mod.mining;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.util.Requirements;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Location;
import org.bukkit.Particle;
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
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Map;

/**
 * Right-click items of the mines: Wishing Compass, Ascension Rope, Blast Bomb, Portable Campfire and the
 * travel scrolls (which unlock warps gated by the "mining:scroll:<id>" requirement).
 */
final class Consumables implements Listener {

    /** warp id, island, anchor, display. */
    static final String[][] SCROLL_WARPS = {
            {"forge", "mining_forge", "dwarven_mines", "station.forge", "The Forge"},   // menus request 9: npc.forge put you inside the Forgemaster
            {"crystals", "mining_crystals", "crystal_hollows", "spawn", "Crystal Caverns"},
            {"base_camp", "mining_base_camp", "glacite_tunnels", "spawn", "Base Camp"},
            {"nucleus", "mining_nucleus", "crystal_hollows", "nucleus", "Crystal Nucleus"},
            {"mines_scroll", "mining_dwarven", "dwarven_mines", "spawn", "Dwarven Mines"},
    };

    private final MiningModule m;

    Consumables(MiningModule m) {
        this.m = m;
        for (String[] w : SCROLL_WARPS) Sky.worlds().registerWarp(w[1], w[2], w[3], w[4], "mining:scroll:" + w[0]);
        Sky.requirements().register("mining", new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                if (!MiningUtil.ready(p) || parts.length < 3) return false;
                return "scroll".equals(parts[1]) && MiningUtil.data(p).scrolls.contains(parts[2]);
            }
            @Override public String describe(String[] parts) {
                return parts.length >= 3 && "scroll".equals(parts[1]) ? "&athe " + Text.titleCase(parts[2]) + " Travel Scroll" : "&a" + String.join(":", parts);
            }
        });
        Sky.abilities().register("mining_royal_pigeon", use -> { m.comms.open(use.player(), null); return true; });
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        ItemStack it = e.getItem();
        if (it == null || it.getType().isAir() || !MiningUtil.ready(p)) return;
        String id = Sky.items().idOf(it);
        if (id == null) return;
        ItemDef def = Sky.items().def(id);
        boolean used;
        switch (id) {
            case "WISHING_COMPASS" -> used = compass(p);
            case "ASCENSION_ROPE" -> used = rope(p);
            case "BOB_OMB" -> used = bomb(p);
            case "PORTABLE_CAMPFIRE" -> used = m.tunnels.campfire(p);
            default -> {
                // only OUR scrolls: foraging (jungle/howl) and crimson (crimson_skull) items also carry extra.warp,
                // and this LOW listener used to read them into mining's scroll set (follow-up 2026-10-06)
                if (def == null || def.extra == null || !def.extra.has("warp") || !ownScroll(def.extra.get("warp").getAsString())) return;
                used = scroll(p, def.extra.get("warp").getAsString());
            }
        }
        e.setCancelled(true);
        if (used) it.setAmount(it.getAmount() - 1);
    }

    /** Whether {@code warp} is one of the mining travel scrolls ({@link #SCROLL_WARPS}). */
    static boolean ownScroll(String warp) {
        for (String[] w : SCROLL_WARPS) if (w[0].equals(warp)) return true;
        return false;
    }

    private boolean scroll(Player p, String warp) {
        MiningData d = MiningUtil.data(p);
        if (!d.scrolls.add(warp)) { Msg.error(p, "You already unlocked this destination."); return false; }
        Msg.success(p, "Unlocked a new fast travel destination! Use /warp to go there.");
        Msg.sound(p, Sound.ITEM_BOOK_PAGE_TURN, 1f);
        return true;
    }

    /**
     * Wishing Compass (structure quests): points at the nearest crystal-caverns structure whose crystal the player
     * still needs - not placed in the Nucleus and not carried (Jungle Temple -> Amethyst, Mines of Varnas -> Jade,
     * Lost Relic City -> Sapphire, the Goblin King / Queen -> Amber, Emberdeep -> Topaz; {@link MiningMath#structureGem}).
     * With every crystal in hand it points at the Crystal Nucleus; with nothing left to find there either, at the
     * nearest other landmark (grottos, lairs...). A target within 30 blocks counts as "you are here" and is skipped.
     */
    private boolean compass(Player p) {
        if (!"crystal_hollows".equals(MiningUtil.island(p))) { Msg.error(p, "The compass only works in the crystal caverns."); return false; }
        Layout lay = Sky.worlds().layout("crystal_hollows");
        if (lay == null) return false;
        java.util.Set<String> needed = neededCrystals(p);
        Location here = p.getLocation();
        String[] pick = null;   // {anchor, reason}
        if (!needed.isEmpty()) pick = nearest(lay, here, n -> n.startsWith("structure.") && needed.contains(MiningMath.structureGem(n)));
        if (pick == null && needed.isEmpty()) {
            // every crystal is carried or placed: the Nucleus is where they go
            for (String n : List.of("landmark.crystal_nucleus", "nucleus")) {
                Location l = Sky.worlds().anchor("crystal_hollows", n);
                if (l != null && l.getWorld() == here.getWorld() && l.distanceSquared(here) >= 30 * 30) { pick = new String[]{n}; break; }
            }
        }
        if (pick == null) pick = nearest(lay, here, n -> (n.startsWith("structure.") || n.startsWith("landmark.") || n.startsWith("crystal."))
                && MiningMath.structureGem(n) == null);
        if (pick == null) { Msg.error(p, "The needle spins and spins... nothing calls to it here."); return false; }
        Location best = Sky.worlds().anchor("crystal_hollows", pick[0]);
        if (best == null) return false;
        Vector dir = best.toVector().subtract(here.toVector()).normalize();
        for (int i = 1; i <= 12; i++) p.spawnParticle(Particle.END_ROD, p.getEyeLocation().add(dir.clone().multiply(i * 0.8)), 1, 0, 0, 0, 0);
        String gem = MiningMath.structureGem(pick[0]);
        String place = Text.titleCase(pick[0].substring(pick[0].indexOf('.') + 1).replace('_', ' '));
        Msg.send(p, "&aThe compass points to &e" + place + " &7(" + best.getBlockX() + ", " + best.getBlockY() + ", " + best.getBlockZ() + ")"
                + (gem != null ? " &8- the " + Text.titleCase(gem) + " Crystal is there" : ""));
        return true;
    }

    /** Crystals the player neither placed in the Nucleus this run nor carries. */
    static java.util.Set<String> neededCrystals(Player p) {
        MiningData d = MiningUtil.data(p);
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String g : Hollows.NUCLEUS_GEMS)
            if (!d.nucleusPlaced.contains(g) && Sky.items().count(p, g + "_CRYSTAL") <= 0) out.add(g);
        return out;
    }

    private static String[] nearest(Layout lay, Location here, java.util.function.Predicate<String> ok) {
        String bestName = null;
        double bestD = Double.MAX_VALUE;
        for (Map.Entry<String, Layout.Anchor> en : lay.anchors().entrySet()) {
            String n = en.getKey();
            if (!ok.test(n)) continue;
            Location l = Sky.worlds().anchor("crystal_hollows", n);
            if (l == null || l.getWorld() != here.getWorld()) continue;
            double d2 = l.distanceSquared(here);
            if (d2 < 30 * 30 || d2 >= bestD) continue;
            bestD = d2;
            bestName = n;
        }
        return bestName == null ? null : new String[]{bestName};
    }

    private boolean rope(Player p) {
        String i = MiningUtil.island(p);
        if ("crystal_hollows".equals(i)) {
            Location s = Sky.worlds().anchor("crystal_hollows", "spawn");
            if (s == null) return false;
            p.teleport(s);
        } else if ("glacite_tunnels".equals(i) || "mineshaft".equals(i)) {
            if (m.tunnels.inShaft(p)) m.tunnels.leave(p, false); else Tunnels.backToCamp(p);
        } else { Msg.error(p, "Use it in the crystal caverns or the frozen tunnels."); return false; }
        Msg.success(p, "You climbed back to the entrance.");
        return true;
    }

    private boolean bomb(Player p) {
        String i = MiningUtil.island(p);
        if (!"crystal_hollows".equals(i) && !"mineshaft".equals(i)) { Msg.error(p, "Blast Bombs only work in the crystal caverns and mineshafts."); return false; }
        Block c = p.getTargetBlockExact(6);
        if (c == null) c = p.getLocation().getBlock();
        p.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, c.getLocation(), 1);
        p.getWorld().playSound(c.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.8f);
        int n = 0;
        for (int dx = -3; dx <= 3; dx++) for (int dy = -3; dy <= 3; dy++) for (int dz = -3; dz <= 3; dz++) {
            if (dx * dx + dy * dy + dz * dz > 10) continue;
            Block b = c.getRelative(dx, dy, dz);
            BlockTable.Def def = m.engine.mineable(p, b, false);
            if (def == null) continue;
            if (MiningUtil.rnd().nextBoolean()) m.breaker.breakBlock(p, b, def, true);   // research: keeps 50% of what it breaks
            else {
                int secs = m.dials.regenSeconds(i);
                if (secs > 0) m.regen.schedule(b, org.bukkit.Material.AIR.createBlockData(), null, secs);
                else b.setType(org.bukkit.Material.AIR, false);
            }
            if (++n >= 60) return true;
        }
        return true;
    }

    static List<String[]> scrollWarps() { return List.of(SCROLL_WARPS); }
}
