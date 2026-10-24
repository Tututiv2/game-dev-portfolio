package com.skyclone.mod.combatzones;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.mod.combatzones.gen.DenGenerator;
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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Professor Dunmore's relic hunt: {@link DenGenerator#RELICS} old skulls hidden around the Spider's
 * Den (layout data {@code relics} = block positions). Right-click (or left-click) one to find it:
 * coins and a little SkyBlock XP each, once per player per relic; Professor Dunmore pays a bonus
 * when you have them all. Progress is in {@link CzData#relics}.
 */
final class Relics implements Listener {

    private final CombatzonesModule m;

    Relics(CombatzonesModule m) { this.m = m; }

    /** Every relic block position on the built island ("x,y,z"), in layout order. */
    static Set<String> all() {
        Layout l = Sky.worlds().layout(DenGenerator.ID);
        Set<String> out = new LinkedHashSet<>();
        String s = l == null ? null : l.data().get("relics");
        if (s == null || s.isBlank()) return out;
        for (String p : s.split(";")) if (!p.isBlank()) out.add(p.trim());
        return out;
    }

    int found(Player p) {
        Set<String> all = all();
        int n = 0;
        for (String k : m.data(p).relics) if (all.contains(k)) n++;
        return n;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.LEFT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (!b.getType().name().contains("SKULL")) return;
        if (!b.getWorld().equals(Sky.worlds().world(DenGenerator.ID))) return;
        String key = b.getX() + "," + b.getY() + "," + b.getZ();
        Set<String> all = all();
        if (!all.contains(key)) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        CzData d = m.data(p);
        if (!d.relics.add(key)) {
            Msg.send(p, "&7You already found this relic. &8(" + found(p) + "/" + all.size() + ")");
            return;
        }
        int n = found(p);
        int coins = Math.max(0, m.dials.relicCoins.get());
        if (coins > 0) Sky.economy().deposit(p, coins, "combatzones: relic");
        Sky.service(Features.SkyLevel.class).award(p, "relic:" + key, Math.max(0, m.dials.relicSbXp.get()), "Find a Spider's Den relic");
        Msg.send(p, "&5&lRELIC FOUND! &7You found an old relic. &8(&d" + n + "&8/&d" + all.size() + "&8)"
                + (coins > 0 ? " &6+" + Text.commasInt(coins) + " coins" : ""));
        Msg.sound(p, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f);
        Location at = b.getLocation().add(0.5, 0.4, 0.5);
        at.getWorld().spawnParticle(Particle.ENCHANT, at, 30, 0.3, 0.3, 0.3, 0.5);
        if (n >= all.size()) Msg.send(p, "&dThat is every relic! &7Bring the news to &fProfessor Dunmore &7at the Archaeologist's Camp.");
    }

    /** Professor Dunmore's lines: progress, and the all-relics bonus once. */
    List<String> dunmoreLines(Player p) {
        Set<String> all = all();
        int n = found(p), total = all.size();
        CzData d = m.data(p);
        if (total > 0 && n >= total && !d.relicsRewarded) {
            d.relicsRewarded = true;
            int coins = Math.max(0, m.dials.relicAllCoins.get());
            if (coins > 0) Sky.economy().deposit(p, coins, "combatzones: all relics");
            Sky.service(Features.SkyLevel.class).award(p, "relic:all", Math.max(0, m.dials.relicSbXp.get()) * 10, "Find every Spider's Den relic");
            Msg.box(p, "&5&lRELIC HUNTER", List.of("&7You found all &d" + total + " &7relics of the Spider's Den.",
                    coins > 0 ? "&7Professor Dunmore paid you &6" + Text.commasInt(coins) + " coins&7." : "&7Professor Dunmore is delighted."));
            return List.of("&fAll " + total + "? Every one? Remarkable. The sanctuary's builders left these, I am sure of it.",
                    "&fTake this for your trouble. Science thanks you.");
        }
        if (n >= total && total > 0)
            return List.of("&fYou found every relic on this mountain. I am still writing the paper.");
        return List.of(
                "&fThis mountain is hollow, you know. Something built that sanctuary long before the spiders came.",
                "&fThey left &dold skulls &fall over the mountain. Find them: right-click one when you see it. &7(" + n + "/" + total + ")",
                "&fThe altar has four sockets. Four callings, four voices. Or one crystal, if you are reckless.");
    }

    // ============================================================================ the Archaeologist's Compass (wave 5)

    static final String COMPASS = "ARCHAEOLOGIST_COMPASS";
    private final java.util.Map<java.util.UUID, Long> compassCooldown = new java.util.HashMap<>();

    /**
     * The accessories module's Archaeologist's Compass ("Right-click it to point at the nearest hidden relic", its
     * {@code extra.hook}) - the relic hunt is ours, so the pointing is too: a trail of particles toward the nearest
     * relic this player has not found yet, and how far it is.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onCompass(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (e.getItem() == null || !COMPASS.equals(Sky.items().idOf(e.getItem()))) return;
        e.setCancelled(true);
        Sky.abilities().consumeClick(p);
        if (!Sky.profiles().isReady(p)) return;
        long now = System.currentTimeMillis();
        Long last = compassCooldown.get(p.getUniqueId());
        if (last != null && now - last < 2000) return;
        compassCooldown.put(p.getUniqueId(), now);
        if (!DenGenerator.ID.equals(Sky.worlds().islandOf(p.getWorld()))) {
            Msg.send(p, "&7The needle spins lazily. &8(It only finds relics in the Spider's Den.)");
            return;
        }
        Set<String> found = m.data(p).relics;
        Location best = null;
        double bd = Double.MAX_VALUE;
        for (String k : all()) {
            if (found.contains(k)) continue;
            String[] a = k.split(",");
            Location l = new Location(p.getWorld(), Integer.parseInt(a[0]) + 0.5, Integer.parseInt(a[1]) + 0.5, Integer.parseInt(a[2]) + 0.5);
            double d = l.distanceSquared(p.getLocation());
            if (d < bd) { bd = d; best = l; }
        }
        if (best == null) { Msg.send(p, "&aYou have found every relic on this mountain!"); return; }
        org.bukkit.util.Vector dir = best.toVector().subtract(p.getEyeLocation().toVector());
        double dist = dir.length();
        dir.normalize();
        Location at = p.getEyeLocation();
        for (int i = 1; i <= Math.min(12, (int) dist); i++)
            p.spawnParticle(Particle.HAPPY_VILLAGER, at.clone().add(dir.clone().multiply(i)), 2, 0.05, 0.05, 0.05, 0);
        Msg.send(p, "&eThe needle points to a relic &a" + (int) Math.round(dist) + " blocks &eaway.");
        Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.6f);
    }
}
