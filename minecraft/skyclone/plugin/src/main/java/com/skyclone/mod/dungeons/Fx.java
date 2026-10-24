package com.skyclone.mod.dungeons;

import com.skyclone.api.ui.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.time.Duration;

/** Particles, sounds and titles. Every call swallows errors: an effect must never break a run. */
public final class Fx {

    private Fx() {}

    public static void particle(Location at, Particle p, int count, double spread, double speed) {
        if (at == null || at.getWorld() == null) return;
        try { at.getWorld().spawnParticle(p, at, count, spread, spread, spread, speed); } catch (RuntimeException ignored) { }
    }

    /** Block-crack particles of a material (BLOCK / FALLING_DUST style particles). */
    public static void particle(Location at, Particle p, int count, double spread, double speed, org.bukkit.Material block) {
        if (at == null || at.getWorld() == null) return;
        try { at.getWorld().spawnParticle(p, at, count, spread, spread, spread, speed, block.createBlockData()); } catch (RuntimeException ignored) { }
    }

    public static void dust(Location at, Color c, float size, int count, double spread) {
        if (at == null || at.getWorld() == null) return;
        try { at.getWorld().spawnParticle(Particle.DUST, at, count, spread, spread, spread, 0, new Particle.DustOptions(c, size)); }
        catch (RuntimeException ignored) { }
    }

    public static void sound(Location at, Sound s, float vol, float pitch) {
        if (at == null || at.getWorld() == null) return;
        try { at.getWorld().playSound(at, s, vol, pitch); } catch (RuntimeException ignored) { }
    }

    public static void sound(Player p, Sound s, float vol, float pitch) {
        try { p.playSound(p.getLocation(), s, vol, pitch); } catch (RuntimeException ignored) { }
    }

    public static void line(Location a, Location b, Color c, double step) {
        if (a == null || b == null || a.getWorld() == null || a.getWorld() != b.getWorld()) return;
        Vector d = b.toVector().subtract(a.toVector());
        double len = d.length();
        if (len < 0.01) return;
        d.normalize().multiply(step);
        Location cur = a.clone();
        Particle.DustOptions o = new Particle.DustOptions(c, 1.0f);
        for (double t = 0; t < len; t += step) {
            try { a.getWorld().spawnParticle(Particle.DUST, cur, 1, 0, 0, 0, 0, o); } catch (RuntimeException ignored) { }
            cur.add(d);
        }
    }

    public static void ring(Location centre, double radius, Color c, int points) {
        if (centre == null || centre.getWorld() == null) return;
        Particle.DustOptions o = new Particle.DustOptions(c, 1.2f);
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            try { centre.getWorld().spawnParticle(Particle.DUST, centre.clone().add(Math.cos(a) * radius, 0.15, Math.sin(a) * radius), 1, 0, 0, 0, 0, o); }
            catch (RuntimeException ignored) { }
        }
    }

    public static void title(Player p, String title, String sub, int fadeIn, int stay, int fadeOut) {
        try {
            p.showTitle(Title.title(Text.c(title), Text.c(sub == null ? "" : sub),
                    Title.Times.times(Duration.ofMillis(fadeIn * 50L), Duration.ofMillis(stay * 50L), Duration.ofMillis(fadeOut * 50L))));
        } catch (RuntimeException ignored) { }
    }
}
