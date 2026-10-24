package com.aisky.showcontrol;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Locale;

/**
 * The server-side FALLBACK camera (ShowCam, the client mod, is the preferred path - CONTRACT §11).
 * Teleports the camera account every tick along a smooth path around a target player:
 * <ul>
 *   <li>orbit  - circles the target at {@code radius}, {@code height} above its feet, 15 deg/s</li>
 *   <li>follow - sits behind and above the target, chasing it with exponential smoothing</li>
 *   <li>pushin - starts {@code radius*1.8} in front of the target and eases in to {@code radius*0.5}</li>
 * </ul>
 * Always looks at the target's eyes. The camera account is put in spectator and hidden from players.
 * A shot with {@code seconds > 0} ends by itself (the camera holds its last frame).
 */
final class CameraFallback implements Runnable {

    enum Mode { NONE, ORBIT, FOLLOW, PUSHIN }

    private final ShowControlPlugin plugin;
    private volatile String cameraAccount;

    private Mode mode = Mode.NONE;
    private String target;
    private double seconds;
    private double radius = 6.0;
    private double height = 2.2;
    private long startMs;
    private double angle0;
    private Location cur;

    CameraFallback(ShowControlPlugin plugin) {
        this.plugin = plugin;
        this.cameraAccount = plugin.config().camera;
    }

    String account() { return cameraAccount; }

    /** /show camera <account>: designate + prepare (spectator, hidden). Main thread. */
    void setAccount(String name) {
        cameraAccount = name;
        plugin.config().camera = name;
        Player cam = Bukkit.getPlayerExact(name);
        if (cam != null) prepare(cam);
    }

    boolean isCamera(Player p) { return p != null && cameraAccount != null && p.getName().equalsIgnoreCase(cameraAccount); }

    void onJoin(Player joined) {
        if (isCamera(joined)) {
            prepare(joined);
        } else {
            Player cam = cameraAccount == null ? null : Bukkit.getPlayerExact(cameraAccount);
            if (cam != null) joined.hidePlayer(plugin, cam);
        }
    }

    private void prepare(Player cam) {
        cam.setGameMode(GameMode.SPECTATOR);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p != cam) p.hidePlayer(plugin, cam);
        }
    }

    /** Main thread. */
    String start(String modeName, String targetName, double secs, Double r) {
        Mode m;
        try { m = Mode.valueOf(modeName.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { return "Unknown shot " + modeName; }
        if (m == Mode.NONE) { stop(); return "Camera stopped."; }
        Player t = Bukkit.getPlayerExact(targetName);
        if (t == null) return "Target " + targetName + " is not online.";
        Player cam = cameraAccount == null ? null : Bukkit.getPlayerExact(cameraAccount);
        if (cam == null) return "Camera account " + cameraAccount + " is not online.";
        prepare(cam);
        mode = m;
        target = t.getName();
        seconds = Math.max(0, secs);
        radius = r == null ? (m == Mode.FOLLOW ? 5.0 : 6.0) : Math.max(1.5, r);
        height = m == Mode.FOLLOW ? 2.5 : 2.2;
        startMs = System.currentTimeMillis();
        // Start the orbit from wherever the camera already is, so the first frame does not jump.
        Location tl = t.getLocation();
        Location cl = cam.getLocation();
        angle0 = cl.getWorld() == tl.getWorld()
                ? Math.atan2(cl.getZ() - tl.getZ(), cl.getX() - tl.getX())
                : Math.toRadians(tl.getYaw() + 90);
        cur = cl.getWorld() == tl.getWorld() ? cl.clone() : null;
        return "Camera: " + m.name().toLowerCase(Locale.ROOT) + " on " + target
                + (seconds > 0 ? " for " + seconds + " s" : " until stopped");
    }

    void stop() {
        mode = Mode.NONE;
        target = null;
    }

    String status() {
        return mode == Mode.NONE ? "idle" : mode.name().toLowerCase(Locale.ROOT) + " " + target;
    }

    @Override
    public void run() {
        if (mode == Mode.NONE || target == null) return;
        double t = (System.currentTimeMillis() - startMs) / 1000.0;
        if (seconds > 0 && t > seconds) { stop(); return; }
        Player cam = cameraAccount == null ? null : Bukkit.getPlayerExact(cameraAccount);
        Player tgt = Bukkit.getPlayerExact(target);
        if (cam == null || tgt == null) return;
        Location base = tgt.getLocation();
        Location eye = tgt.getEyeLocation();
        Location desired;
        double alpha;
        switch (mode) {
            case ORBIT -> {
                double a = angle0 + Math.toRadians(15) * t;
                desired = base.clone().add(Math.cos(a) * radius, height, Math.sin(a) * radius);
                alpha = 0.5;
            }
            case FOLLOW -> {
                Vector back = base.getDirection().setY(0);
                if (back.lengthSquared() < 1e-6) back = new Vector(0, 0, 1);
                back.normalize().multiply(-radius);
                desired = base.clone().add(back).add(0, height, 0);
                alpha = 0.12;
            }
            case PUSHIN -> {
                double dur = seconds > 0 ? seconds : 6.0;
                double k = Math.min(1.0, t / dur);
                double ease = k * k * (3 - 2 * k);
                double dist = radius * (1.8 - 1.3 * ease);
                Vector front = base.getDirection().setY(0);
                if (front.lengthSquared() < 1e-6) front = new Vector(0, 0, 1);
                front.normalize().multiply(dist);
                desired = base.clone().add(front).add(0, 1.6 + 0.6 * (1 - ease), 0);
                alpha = 0.35;
            }
            default -> { return; }
        }
        if (cur == null || cur.getWorld() != desired.getWorld() || cur.distanceSquared(desired) > 64 * 64) {
            cur = desired.clone();
        } else {
            cur.add(desired.toVector().subtract(cur.toVector()).multiply(alpha));
        }
        Location look = cur.clone();
        Vector dir = eye.toVector().subtract(cur.toVector());
        if (dir.lengthSquared() > 1e-6) look.setDirection(dir);
        cam.teleport(look);
    }
}
