package com.skyclone.mod.slayer;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Nullshade Seraph (research 04 sec 2.8 / 6.2):
 * <pre>
 * all  Hitshield: 15/30/60/100 HITS (not damage) at 100%, 66% and 33%; while it is down Ferocity strikes deal -75%;
 *      while it is up (T3+) it hits +2,000 harder
 * T2+  Dissonance every 8 s: blinks next to you, then blasts 40% of its damage within 6.5 blocks
 * T2+  Void Glyphs (T2 under 50%, T3+ under 66%) every 12 s: a beacon lands; stand next to it within 5 s or it bursts
 *      for lethal damage
 * T3+  Nukekubi Heads under 33%, one every 10 s: each deals 600 (T4 1,500) true damage per second, doubling for
 *      every other head alive. Look straight at a head for half a second to pop it
 * T4   Heartbreak Beams every 45 s: invulnerable 8 s, 12 rotating beams, 800 true damage and -12 Vitality for 90 s
 * </pre>
 */
final class EndermanFight extends BossFight {

    private int shield;
    private boolean shield66, shield33;
    private long blastAt = -1;
    private final List<Glyph> glyphs = new ArrayList<>();
    private final List<Head> heads = new ArrayList<>();
    private long beamsStart = -1;
    private double beamAngle;
    boolean allowTeleport;

    private static final class Glyph {
        Location at;
        long fuse;
        BlockDisplay display;
    }

    private static final class Head {
        ItemDisplay display;
        double angle;
        double radius;
        int gaze;
    }

    EndermanFight(SlayerModule mod, Player owner, SlayerCatalog.Type type, SlayerCatalog.Tier tier) {
        super(mod, owner, type, tier);
        barColor = BossBar.Color.PURPLE;
        shield = SlayerMath.hitShield(tierNo);
    }

    @Override
    void onBegin() {
        if (boss instanceof Enderman e) {
            try { e.setCarriedBlock(Material.BEACON.createBlockData()); } catch (RuntimeException ignored) { }
        }
        updateStatus();
    }

    private void updateStatus() {
        status = shield > 0 ? "&d&lHits: &f" + shield : "&8Shield down";
    }

    @Override
    void step() {
        Player o = owner();
        if (o == null) return;
        Location b = boss.getLocation();

        // Dissonance (T2+)
        if (tierNo >= 2 && beamsStart < 0 && every(8) && o.getWorld() == boss.getWorld()) {
            Location to = around(o.getLocation(), 2, 3);
            Fx.particle(b.clone().add(0, 1.5, 0), Particle.PORTAL, 40, 0.5, 0.5);
            allowTeleport = true;
            teleportBoss(to);
            allowTeleport = false;
            Fx.sound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.7f);
            blastAt = ticks + 2;
        }
        if (blastAt >= 0 && ticks >= blastAt) {
            blastAt = -1;
            Location c = boss.getLocation();
            Fx.ring(c, 6.5, Fx.rgb(150, 0, 200), 40);
            Fx.particle(c.clone().add(0, 1, 0), Particle.REVERSE_PORTAL, 60, 2.5, 0.1);
            for (Player p : playersNear(c, 6.5)) hurt(p, tier.dps * 0.4);
        }

        // Void Glyphs
        double glyphAt = tierNo == 2 ? 0.5 : 0.66;
        if (tierNo >= 2 && frac() <= glyphAt && every(12)) throwGlyph(o);
        tickGlyphs();

        // Nukekubi heads
        if (tierNo >= 3 && frac() <= 1.0 / 3.0 && every(10) && heads.size() < 6) spawnHead(o);
        tickHeads(o);

        // Heartbreak Beams (T4)
        if (tierNo >= 4 && beamsStart < 0 && elapsedSeconds() >= 30 && every(45)) {
            beamsStart = ticks;
            immune = "Heartbreak Beams";
            setImmobile(true);
            Fx.warn(o, "&5&lHEARTBREAK BEAMS", "&7Stay in the gaps between the beams!");
            Fx.sound(b, Sound.BLOCK_BEACON_ACTIVATE, 1f, 0.6f);
        }
        if (beamsStart >= 0) tickBeams();
    }

    private void throwGlyph(Player o) {
        Location at = around(o.getLocation(), 4, 8);
        Glyph g = new Glyph();
        g.at = at;
        g.fuse = ticks + 20;      // 5 s
        try {
            g.display = at.getWorld().spawn(at.clone().add(-0.5, 0, -0.5), BlockDisplay.class, d -> {
                d.setPersistent(false);
                d.setBlock(Material.BEACON.createBlockData());
                d.setGlowing(true);
            });
            props.add(g.display);
        } catch (RuntimeException ignored) { }
        glyphs.add(g);
        Fx.sound(at, Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.5f);
        Fx.warn(o, "&d&lVOID GLYPH", "&7Stand next to the beacon!");
    }

    private void tickGlyphs() {
        Iterator<Glyph> it = glyphs.iterator();
        while (it.hasNext()) {
            Glyph g = it.next();
            for (int y = 0; y < 6; y++) Fx.dust(g.at.clone().add(0, 1 + y, 0), Fx.rgb(200, 60, 255), 1.2f, 1, 0.05);
            Fx.ring(g.at, 1.5, Fx.rgb(200, 60, 255), 16);
            boolean defused = false;
            for (Player p : playersNear(g.at, 1.8)) {
                defused = true;
                Msg.send(p, "&aYou defused a Void Glyph!");
            }
            if (defused) {
                removeGlyph(g);
                Fx.sound(g.at, Sound.BLOCK_GLASS_BREAK, 1f, 1.2f);
                it.remove();
                continue;
            }
            if (ticks >= g.fuse) {
                removeGlyph(g);
                it.remove();
                Fx.particle(g.at.clone().add(0, 1, 0), Particle.EXPLOSION_EMITTER, 2, 1, 0);
                Fx.sound(g.at, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.6f);
                for (Player p : playersNear(g.at, 40)) hurtPercent(p, 100, "void_glyph");    // "stand next to them or die"
            }
        }
    }

    private void removeGlyph(Glyph g) {
        if (g.display != null) {
            try { g.display.remove(); } catch (RuntimeException ignored) { }
            props.remove(g.display);
        }
    }

    private void spawnHead(Player o) {
        Head h = new Head();
        h.angle = Math.random() * Math.PI * 2;
        h.radius = 3 + Math.random() * 2;
        Location at = headPos(o, h);
        try {
            h.display = at.getWorld().spawn(at, ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.setItemStack(new ItemStack(Material.WITHER_SKELETON_SKULL));
                d.setBillboard(Display.Billboard.CENTER);
                d.setGlowing(true);
            });
            props.add(h.display);
            heads.add(h);
            Fx.sound(at, Sound.ENTITY_GHAST_SCREAM, 0.8f, 1.6f);
            Msg.send(o, "&5A Nukekubi head fixates on you! &7Look at it to pop it.");
        } catch (RuntimeException ignored) { }
    }

    private Location headPos(Player o, Head h) {
        return o.getLocation().add(Math.cos(h.angle) * h.radius, 1.6 + Math.sin(h.angle * 2) * 0.4, Math.sin(h.angle) * h.radius);
    }

    private void tickHeads(Player o) {
        if (heads.isEmpty()) return;
        Location eye = o.getEyeLocation();
        Vector look = eye.getDirection().normalize();
        Iterator<Head> it = heads.iterator();
        while (it.hasNext()) {
            Head h = it.next();
            if (h.display == null || !h.display.isValid()) { it.remove(); continue; }
            h.angle += 0.06;
            Location at = headPos(o, h);
            try { h.display.teleport(at); } catch (RuntimeException ignored) { }
            Vector to = at.toVector().add(new Vector(0, 0.25, 0)).subtract(eye.toVector());
            double dist = to.length();
            if (dist > 0.01 && dist < 25 && look.angle(to.normalize()) < 0.14) {
                h.gaze++;
                Fx.particle(at.clone().add(0, 0.25, 0), Particle.ENCHANTED_HIT, 3, 0.1, 0);
                if (h.gaze >= 2) {
                    Fx.particle(at, Particle.LARGE_SMOKE, 15, 0.2, 0.02);
                    Fx.sound(at, Sound.ENTITY_ITEM_BREAK, 1f, 0.6f);
                    try { h.display.remove(); } catch (RuntimeException ignored) { }
                    props.remove(h.display);
                    it.remove();
                }
            } else if (h.gaze > 0) {
                h.gaze--;
            }
        }
        if (every(1) && !heads.isEmpty()) {
            int n = heads.size();
            double base = tierNo >= 4 ? 1500 : 600;
            double dmg = n * base * Math.pow(2, n - 1);
            hurtTrue(o, dmg);
            Sky.vitals().flash(o, "&5☠ " + n + " head" + (n == 1 ? "" : "s") + " &7-" + Text.shortNum(dmg * mech()) + "❤", 18);
        }
    }

    private void tickBeams() {
        long t = ticks - beamsStart;
        Location c = boss.getLocation().add(0, 1, 0);
        beamAngle += 0.12;
        double width = Math.toRadians(7);
        for (int i = 0; i < 12; i++) {
            double a = beamAngle + Math.PI * 2 * i / 12;
            Location end = c.clone().add(Math.cos(a) * 10, 0, Math.sin(a) * 10);
            Fx.line(c, end, Fx.rgb(255, 60, 160), 0.7);
        }
        for (Player p : playersNear(c, 10.5)) {
            Vector d = p.getLocation().toVector().subtract(c.toVector()).setY(0);
            if (d.lengthSquared() < 0.25) continue;
            double pa = Math.atan2(d.getZ(), d.getX());
            for (int i = 0; i < 12; i++) {
                double a = beamAngle + Math.PI * 2 * i / 12;
                double diff = Math.abs(Math.atan2(Math.sin(pa - a), Math.cos(pa - a)));
                if (diff < width) {
                    if (t % 2 == 0) {
                        hurtTrue(p, 800);
                        mod.debuffs.vitality(p, 12, 90_000);
                        Fx.sound(p, Sound.ENTITY_GUARDIAN_ATTACK, 0.7f, 1.6f);
                    }
                    break;
                }
            }
        }
        if (t >= 32) {     // 8 s
            beamsStart = -1;
            immune = null;
            setImmobile(false);
        }
    }

    @Override
    void onHit(DamageContext ctx) {
        // a raised shield eats the hit first (it counts HITS); only an unshielded hit can reach a threshold
        if (shield <= 0) {
            // thresholds bring the shield back (research: at 100/66/33%)
            if (!shield66 && clampAt(ctx, 0.66)) { shield66 = true; refill(); return; }
            if (!shield33 && clampAt(ctx, 0.33)) { shield33 = true; refill(); return; }
        }
        if (shield > 0) {
            shield--;
            ctx.cancelled = true;
            Player p = ctx.playerAttacker();
            Fx.sound(boss.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.8f, 1.4f);
            if (shield == 0) {
                Fx.sound(boss.getLocation(), Sound.BLOCK_GLASS_BREAK, 1.2f, 0.7f);
                Fx.particle(boss.getLocation().add(0, 1.5, 0), Particle.REVERSE_PORTAL, 50, 0.7, 0.2);
                if (p != null) Msg.send(p, "&d&lThe Hitshield shattered!");
            } else if (p != null) {
                Sky.vitals().flash(p, "&dHitshield: &f" + shield + " hits", 12);
            }
            updateStatus();
            return;
        }
        if (ctx.kind == DamageContext.Kind.FEROCITY) ctx.finalDamage *= 0.25;
    }

    private void refill() {
        shield = SlayerMath.hitShield(tierNo);
        updateStatus();
        Fx.sound(boss.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.4f);
        Player o = owner();
        if (o != null) Msg.send(o, "&dThe Seraph raised its Hitshield again! &7(" + shield + " hits)");
    }

    @Override
    void onStrike(Player target, DamageContext ctx) {
        if (tierNo >= 3 && shield > 0) ctx.flatBonus += 2000;      // research: +2,000 DPS while shielded (T3-T4)
    }

    @Override
    void onEnd() {
        for (Glyph g : glyphs) removeGlyph(g);
        glyphs.clear();
        heads.clear();
    }
}
