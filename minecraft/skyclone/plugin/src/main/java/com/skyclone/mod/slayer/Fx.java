package com.skyclone.mod.slayer;

import com.skyclone.api.ui.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.time.Duration;

/** Particles, sounds and titles for telegraphs. Every call swallows errors: an effect must never break a fight. */
final class Fx {

    private Fx() {}

    static void particle(Location at, Particle p, int count, double spread, double speed) {
        if (at == null || at.getWorld() == null) return;
        try { at.getWorld().spawnParticle(p, at, count, spread, spread, spread, speed); } catch (RuntimeException ignored) { }
    }

    static void dust(Location at, Color c, float size, int count, double spread) {
        if (at == null || at.getWorld() == null) return;
        try {
            at.getWorld().spawnParticle(Particle.DUST, at, count, spread, spread, spread, 0, new Particle.DustOptions(c, size));
        } catch (RuntimeException ignored) { }
    }

    static void sound(Location at, Sound s, float vol, float pitch) {
        if (at == null || at.getWorld() == null) return;
        try { at.getWorld().playSound(at, s, vol, pitch); } catch (RuntimeException ignored) { }
    }

    static void sound(Player p, Sound s, float vol, float pitch) {
        try { p.playSound(p.getLocation(), s, vol, pitch); } catch (RuntimeException ignored) { }
    }

    /** A flat ring of dust on the ground. */
    static void ring(Location centre, double radius, Color c, int points) {
        if (centre == null) return;
        World w = centre.getWorld();
        if (w == null) return;
        Particle.DustOptions o = new Particle.DustOptions(c, 1.3f);
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            Location l = centre.clone().add(Math.cos(a) * radius, 0.15, Math.sin(a) * radius);
            try { w.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, o); } catch (RuntimeException ignored) { }
        }
    }

    /** A filled disc of particles (fire pits, glyph zones). */
    static void disc(Location centre, double radius, Particle p, int count) {
        if (centre == null || centre.getWorld() == null) return;
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            double a = r.nextDouble(Math.PI * 2), d = Math.sqrt(r.nextDouble()) * radius;
            particle(centre.clone().add(Math.cos(a) * d, 0.2, Math.sin(a) * d), p, 1, 0, 0);
        }
    }

    /** A straight line of dust between two points. */
    static void line(Location a, Location b, Color c, double step) {
        if (a == null || b == null || a.getWorld() == null || a.getWorld() != b.getWorld()) return;
        Vector d = b.toVector().subtract(a.toVector());
        double len = d.length();
        if (len < 0.01) return;
        d.normalize().multiply(step);
        Location cur = a.clone();
        Particle.DustOptions o = new Particle.DustOptions(c, 1.1f);
        for (double t = 0; t < len; t += step) {
            try { a.getWorld().spawnParticle(Particle.DUST, cur, 1, 0, 0, 0, 0, o); } catch (RuntimeException ignored) { }
            cur.add(d);
        }
    }

    static void title(Player p, String title, String sub, int fadeIn, int stay, int fadeOut) {
        try {
            p.showTitle(Title.title(Text.c(title), Text.c(sub == null ? "" : sub),
                    Title.Times.times(Duration.ofMillis(fadeIn * 50L), Duration.ofMillis(stay * 50L), Duration.ofMillis(fadeOut * 50L))));
        } catch (RuntimeException ignored) { }
    }

    /** A short red warning title (dial slayer.telegraph-titles). */
    static void warn(Player p, String title, String sub) {
        if (p == null) return;
        if (SlayerDials.TELEGRAPH_TITLES.get()) title(p, title, sub, 2, 26, 6);
        sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.6f);
    }

    static Color rgb(int r, int g, int b) { return Color.fromRGB(r, g, b); }
}
