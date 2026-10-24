package com.skyclone.mod.mining;

import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Mining lanterns: right-click to set down an orb for 5 minutes that buffs miners within 30 blocks.
 * Only the strongest orb in range applies (research: "Only one deployable buff applies").
 */
final class Deployables {

    static final String ABILITY = "mining_deploy";

    static final class Orb {
        UUID owner;
        String itemId;
        Location at;
        long until;
        double speed, fortune, heat, cold, spread;
        int players, radius;
        ItemDisplay display;
    }

    private final MiningModule m;
    private final List<Orb> orbs = new ArrayList<>();

    Deployables(MiningModule m) {
        this.m = m;
        Sky.abilities().register(ABILITY, use -> deploy(use.player(), use.item()));
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 20L, 20L);
    }

    private boolean deploy(Player p, ItemStack item) {
        String id = Sky.items().idOf(item);
        ItemDef def = id == null ? null : Sky.items().def(id);
        if (def == null || def.extra == null || !def.extra.has("deployable")) return false;
        if (!MiningUtil.onMiningIsland(p)) { Msg.error(p, "Lanterns only work on mining islands."); return false; }
        JsonObject o = def.extra.getAsJsonObject("deployable");
        orbs.removeIf(x -> {
            if (x.owner.equals(p.getUniqueId())) { remove(x); return true; }
            return false;
        });
        Orb orb = new Orb();
        orb.owner = p.getUniqueId();
        orb.itemId = id;
        orb.at = p.getLocation().add(0, 2.2, 0);
        orb.until = System.currentTimeMillis() + o.get("seconds").getAsLong() * 1000L;
        orb.speed = o.get("speed").getAsDouble();
        orb.fortune = o.get("fortune").getAsDouble();
        orb.heat = o.get("heat").getAsDouble();
        orb.cold = o.get("cold").getAsDouble();
        orb.spread = o.get("spread").getAsDouble();
        orb.players = o.get("players").getAsInt();
        orb.radius = o.get("radius").getAsInt();
        try {
            orb.display = p.getWorld().spawn(orb.at, ItemDisplay.class, d -> {
                d.setItemStack(new ItemStack(Material.valueOf(def.material)));
                d.setPersistent(false);
                d.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            });
        } catch (Throwable ignored) { }
        orbs.add(orb);
        Msg.success(p, "Deployed your " + com.skyclone.api.ui.Text.strip(MiningUtil.itemName(id)) + "!");
        Msg.sound(p, Sound.BLOCK_BEACON_ACTIVATE, 1.6f);
        return true;
    }

    private void remove(Orb o) {
        if (o.display != null && o.display.isValid()) o.display.remove();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Iterator<Orb> it = orbs.iterator(); it.hasNext(); ) {
            Orb o = it.next();
            if (o.until < now || o.at.getWorld() == null) { remove(o); it.remove(); continue; }
            o.at.getWorld().spawnParticle(Particle.END_ROD, o.at, 3, 0.3, 0.3, 0.3, 0.01);
        }
    }

    private Orb best(Player p) {
        Orb best = null;
        long now = System.currentTimeMillis();
        for (Orb o : orbs) {
            if (o.until < now || o.at.getWorld() != p.getWorld()) continue;
            boolean inShaft = "mineshaft".equals(MiningUtil.island(p)) && !"MITHRIL_LANTERN".equals(o.itemId) && !"TITANIUM_LANTERN".equals(o.itemId);
            if (!inShaft && o.at.distanceSquared(p.getLocation()) > (double) o.radius * o.radius) continue;
            if (best == null || o.speed > best.speed) best = o;
        }
        return best;
    }

    void contribute(Player p, StatMap s) {
        if (orbs.isEmpty()) return;
        Orb o = best(p);
        if (o == null) return;
        s.add(Stat.MINING_SPEED, o.speed);
        s.add(Stat.MINING_FORTUNE, o.fortune);
        s.add(Stat.HEAT_RESISTANCE, o.heat);
        s.add(Stat.COLD_RESISTANCE, o.cold);
    }

    double gemSpread(Player p) {
        if (orbs.isEmpty()) return 0;
        Orb o = best(p);
        return o == null ? 0 : o.spread;
    }

    void clear() { for (Orb o : orbs) remove(o); orbs.clear(); }
}
