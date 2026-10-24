package com.skyclone.mod.kuudra;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Performs the beats of a {@link KScript} scene for one hunt: titles, Ferryman Brask's lines, sounds, particles and
 * display entities (tentacles breaking the lake, the sinking carcass). Camera-free by necessity: the crew is frozen
 * in place facing the lake instead. Everything spawned here is tagged with the hunt and removed by {@link #clear}.
 */
final class Scenes {

    /** Transient (never saved) modifiers: a crash mid-scene cannot leave anyone frozen after a relog. */
    static NamespacedKey FREEZE;

    private final Run r;
    private final List<List<BlockDisplay>> tentacles = new ArrayList<>();
    private final List<BlockDisplay> carcass = new ArrayList<>();
    private Location carcassAt;

    Scenes(Run r) { this.r = r; }

    // =====================================================================================
    // freezing
    // =====================================================================================

    static void freeze(Player p) {
        if (FREEZE == null) return;
        for (Attribute a : new Attribute[]{Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH}) {
            AttributeInstance in = p.getAttribute(a);
            if (in == null || in.getModifier(FREEZE) != null) continue;
            in.addTransientModifier(new AttributeModifier(FREEZE, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        }
    }

    static void unfreeze(Player p) {
        if (FREEZE == null || p == null) return;
        for (Attribute a : new Attribute[]{Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH}) {
            AttributeInstance in = p.getAttribute(a);
            if (in != null && in.getModifier(FREEZE) != null) in.removeModifier(FREEZE);
        }
    }

    /** Turn the player (where they stand) to look at {@code at}. */
    static void face(Player p, Location at) {
        if (at == null || at.getWorld() != p.getWorld()) return;
        Location l = p.getLocation();
        Vector d = at.toVector().subtract(l.toVector());
        if (d.lengthSquared() < 1e-4) return;
        l.setDirection(d);
        l.setPitch(Math.max(-30f, Math.min(30f, l.getPitch())));
        p.teleport(l);
    }

    // =====================================================================================
    // beats
    // =====================================================================================

    void beat(String scene, String beat) {
        try {
            if (KScript.OPENING.equals(scene)) opening(beat);
            else if (KScript.VICTORY.equals(scene)) victory(beat);
        } catch (RuntimeException e) {
            Sky.log().warning("[kuudra] scene " + scene + "/" + beat + " threw: " + e);
        }
    }

    /** The scene is over (played out or skipped): leave the world exactly as the fight expects it. */
    void end(String scene, boolean skipped) {
        try {
            if (KScript.OPENING.equals(scene)) {
                clearTentacles();
                r.surfaceNow();
                for (Player p : r.players()) mark(p, KScript.OPENING);
                if (skipped) r.title("", "&7The Leviathan has surfaced.", 900);
            } else if (KScript.VICTORY.equals(scene)) {
                clearCarcass();
                r.afterVictoryScene();
            }
        } catch (RuntimeException e) {
            Sky.log().warning("[kuudra] scene end " + scene + " threw: " + e);
        }
    }

    private void mark(Player p, String scene) {
        if (!Sky.profiles().isReady(p)) return;
        KData d = r.mod.data(p);
        if (!d.scenesSeen.contains(scene)) d.scenesSeen.add(scene);
    }

    void clear() { clearTentacles(); clearCarcass(); }

    // ---- the opening ----

    private Location lake() {
        Location rise = r.arena.anchor("kuudra.rise");
        if (rise == null) return null;
        Location s = rise.clone();
        s.setY(com.skyclone.mod.kuudra.gen.KuudraArenaGenerator.FLOOR + 1);
        return s;
    }

    private void opening(String beat) {
        Location lake = lake();
        switch (beat) {
            case "frame" -> {
                for (Player p : r.players()) {
                    face(p, lake == null ? null : lake.clone().add(0, 6, 0));
                    p.playSound(p.getLocation(), Sound.AMBIENT_BASALT_DELTAS_MOOD, 1f, 0.6f);
                }
                r.title("&c&lLEVIATHAN'S HOLLOW", r.tier.colored() + " &7hunt", 2600);
            }
            case "brask1" -> r.narrate("scene-1", "Quiet now... feel that? The lake's breathing. It knows a boat came in.");
            case "churn" -> {
                if (lake == null) return;
                World w = lake.getWorld();
                w.spawnParticle(Particle.LAVA, lake, 60, 9, 0.4, 9, 0);
                w.spawnParticle(Particle.LARGE_SMOKE, lake.clone().add(0, 1, 0), 40, 10, 1, 10, 0.02);
                for (Player p : r.players()) {
                    p.playSound(lake, Sound.BLOCK_LAVA_POP, 2f, 0.5f);
                    p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 0.5f);
                }
            }
            case "tentacles" -> {
                if (lake == null) return;
                spawnTentacles(lake);
                for (Player p : r.players()) p.playSound(lake, Sound.ENTITY_GENERIC_SPLASH, 3f, 0.4f);
            }
            case "brask2" -> r.narrate("scene-2", "Arms in the lava! It's waking - stay off the edge!");
            case "sway" -> swayTentacles(3.0);
            case "rise" -> {
                r.startRise();
                sinkTentacles();
                for (Player p : r.players()) p.playSound(lake == null ? p.getLocation() : lake, Sound.ENTITY_RAVAGER_ROAR, 2f, 0.4f);
            }
            case "brask3" -> r.narrate("scene-3", "Here it comes... the Magma Leviathan!");
            case "roar" -> {
                clearTentacles();
                Location at = r.boss != null && r.boss.isValid() ? r.boss.getLocation().add(0, 6, 0) : lake;
                if (at == null) return;
                at.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, at, 3, 3, 2, 3, 0);
                at.getWorld().spawnParticle(Particle.FLAME, at, 120, 6, 4, 6, 0.08);
                for (Player p : r.players()) {
                    p.playSound(at, Sound.ENTITY_ENDER_DRAGON_GROWL, 2f, 0.5f);
                    p.playSound(at, Sound.ENTITY_RAVAGER_ROAR, 2f, 0.6f);
                }
            }
            case "name" -> r.title("&4&lTHE MAGMA LEVIATHAN", r.tier.colored() + " &8| &c" + Text.shortNum(r.expectedBossMax()) + " &7health", 2200);
            default -> { }
        }
    }

    /** Four arms, each a stack of shrinking blocks, coming up out of the lava around the rise point. */
    private void spawnTentacles(Location lake) {
        clearTentacles();
        String[] flesh = {"nether_wart_block", "crimson_hyphae[axis=y]", "nether_wart_block", "magma_block", "crimson_hyphae[axis=y]", "nether_wart_block", "shroomlight"};
        for (int i = 0; i < 4; i++) {
            double a = Math.PI / 4 + i * Math.PI / 2 + r.rng.nextDouble() * 0.4;
            double rad = 13 + r.rng.nextDouble() * 5;
            Location base = lake.clone().add(Math.cos(a) * rad, -12, Math.sin(a) * rad);
            List<BlockDisplay> arm = new ArrayList<>();
            double y = 0;
            for (int k = 0; k < flesh.length; k++) {
                float s = (float) (1.9 - k * 0.2);
                BlockDisplay d = r.blockDisplay(base.clone().add(0, y, 0), flesh[k], s);
                d.setTeleportDuration(30);
                arm.add(d);
                y += s * 0.95;
            }
            tentacles.add(arm);
        }
        // one tick later, lift them all by 12 blocks; the client glides them up over 1.5 s
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            for (List<BlockDisplay> arm : tentacles)
                for (BlockDisplay d : arm) if (d.isValid()) d.teleport(d.getLocation().add(0, 12, 0));
        }, 2L);
    }

    /** The arms lean, the higher a segment the further (a whip, not a pole). */
    private void swayTentacles(double reach) {
        for (List<BlockDisplay> arm : tentacles) {
            double a = r.rng.nextDouble() * Math.PI * 2;
            for (int k = 0; k < arm.size(); k++) {
                BlockDisplay d = arm.get(k);
                if (!d.isValid()) continue;
                d.setTeleportDuration(20);
                double f = reach * k / Math.max(1, arm.size() - 1);
                d.teleport(d.getLocation().add(Math.cos(a) * f, 0, Math.sin(a) * f));
            }
        }
    }

    private void sinkTentacles() {
        for (List<BlockDisplay> arm : tentacles)
            for (BlockDisplay d : arm) {
                if (!d.isValid()) continue;
                d.setTeleportDuration(25);
                d.teleport(d.getLocation().add(0, -14, 0));
                d.getWorld().spawnParticle(Particle.LAVA, d.getLocation(), 3, 0.5, 0.2, 0.5, 0);
            }
    }

    private void clearTentacles() {
        for (List<BlockDisplay> arm : tentacles) for (Entity e : arm) Run.remove(e);
        tentacles.clear();
    }

    // ---- the victory ----

    private void victory(String beat) {
        switch (beat) {
            case "carcass" -> {
                Location at = r.bossLastLocation();
                // slain in the Lair (Infernal): the crew is back up on the platform, so it goes under in the lake they can see
                if (at != null && at.getY() < 45) at = r.arena.anchor("kuudra.rise");
                if (at == null) return;
                carcassAt = at.clone();
                float body = (float) Math.max(4, r.bossWidth());
                clearCarcass();
                carcass.add(r.blockDisplay(at.clone(), "magma_block", body));
                for (int i = 0; i < 4; i++) {
                    double a = i * Math.PI / 2 + Math.PI / 4;
                    carcass.add(r.blockDisplay(at.clone().add(Math.cos(a) * body * 0.45, body * 0.7, Math.sin(a) * body * 0.45), "nether_wart_block", body * 0.3f));
                }
                for (Player p : r.players()) face(p, at.clone().add(0, body / 2, 0));
                at.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, at.clone().add(0, body / 2, 0), 2, 2, 2, 2, 0);
            }
            case "sink" -> {
                for (BlockDisplay d : carcass) {
                    if (!d.isValid()) continue;
                    d.setTeleportDuration(60);
                    d.teleport(d.getLocation().add(0, -Math.max(16, r.bossWidth() * 1.5), 0));
                }
                for (Player p : r.players()) p.playSound(p.getLocation(), Sound.ENTITY_WITHER_DEATH, 0.8f, 0.5f);
            }
            case "burst" -> {
                if (carcassAt == null) return;
                Location s = carcassAt.clone();
                s.setY(com.skyclone.mod.kuudra.gen.KuudraArenaGenerator.FLOOR + 1);
                World w = s.getWorld();
                w.spawnParticle(Particle.LAVA, s, 40, 5, 0.5, 5, 0);
                w.spawnParticle(Particle.EXPLOSION, s.clone().add(0, 1, 0), 6, 4, 1, 4, 0);
                w.spawnParticle(Particle.LARGE_SMOKE, s.clone().add(0, 2, 0), 30, 5, 2, 5, 0.03);
                for (Player p : r.players()) p.playSound(s, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.6f);
            }
            default -> { }
        }
    }

    private void clearCarcass() {
        for (Entity e : carcass) Run.remove(e);
        carcass.clear();
    }
}
