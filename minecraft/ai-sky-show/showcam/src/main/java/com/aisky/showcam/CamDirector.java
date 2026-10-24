package com.aisky.showcam;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.Locale;

/**
 * The per-frame camera driver. Runs ONLY on the client (render) thread.
 *
 * <p><b>Why the camera is overridden per frame instead of moving the player.</b>
 * The local player's rendered position is {@code lerp(partialTick, xo, x)},
 * i.e. it only changes 20 times a second and is interpolated in between; a
 * player teleported every frame stutters against that interpolation, and the
 * server answers fast movement with corrections ("moved too quickly"). So the
 * lens is set directly inside {@code Camera.update} every rendered frame from
 * the target's own interpolated position ({@code Entity.getPosition(partial)}),
 * which is as smooth as the frame rate. The player entity is then dragged
 * along behind the lens on the 20 Hz tick, in steps of at most a few blocks,
 * purely so the SERVER keeps sending the chunks and entities around the shot
 * (the client only knows about what is near its player). It is never rendered
 * (the camera stays "first person", so vanilla skips the camera entity) and
 * its jumps never show on screen because nothing visible depends on it.
 *
 * <p>Smoothing has two layers: {@link ShotPlanner} produces an eased ideal
 * path, and this class low-passes the lens position, the look-at point and the
 * FOV toward it with frame-rate independent exponentials. A new shot therefore
 * glides out of the previous one; {@code "cut":true} (or a jump of more than
 * {@value #SNAP_DISTANCE} blocks) snaps instead.
 */
public final class CamDirector {

    static final double SNAP_DISTANCE = 40.0;
    private static final double TAU_POS = 0.30;
    private static final double TAU_LOOK = 0.18;
    private static final double TAU_FOV = 0.35;
    private static final double TAU_FOCUS = 0.08;
    /** Max blocks per tick the camera player is moved (vanilla rejects > 10). */
    private static final double MAX_PLAYER_STEP = 5.0;
    private static final double TELEPORT_DISTANCE = 64.0;
    private static final long TP_COOLDOWN_MS = 3000;

    /** Written by HTTP threads, consumed by the render thread. */
    private static volatile ShotSpec pending;
    /** Published every frame for {@code GET /status}. */
    private static volatile Status status = Status.idle(false, null, 0f, 0f, 0f);

    // ---- render-thread state ----
    private static ShotPlanner planner;
    private static boolean have;
    private static double sx;
    private static double sy;
    private static double sz;
    private static double lx;
    private static double ly;
    private static double lz;
    private static double sfov = 70;
    private static boolean focusInit;
    private static double fx;
    private static double fy;
    private static double fz;
    private static boolean targetLoaded;
    private static boolean everSeen;
    private static boolean snapNext;
    private static long lastNanos;
    private static long lastTpMs;
    private static String lastTpReason = null;

    // Output for this frame (read by CameraMixin right after frame()).
    private static float outYaw;
    private static float outPitch;
    private static float outFov;

    // HUD / options we change in memory, and their original values.
    private static boolean optionsSaved;
    private static boolean savedHideGui;
    private static boolean savedBobView;

    private CamDirector() {
    }

    // ------------------------------------------------------------------ API

    public static void submit(ShotSpec spec) {
        pending = spec;
    }

    public static Status status() {
        return status;
    }

    public static boolean hasPending() {
        return pending != null;
    }

    /**
     * Called from {@code Camera.update} (CameraMixin) after vanilla has aligned
     * the camera with the player and computed its FOV, before the frustum and
     * projection are built. Returns true when the camera must be overridden
     * with {@link #x()}.. {@link #fov()} this frame.
     */
    public static boolean frame(Vec3 vanillaPos, float vanillaYaw, float vanillaPitch, float vanillaFov, float partial) {
        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 0.0 : Math.min(0.1, Math.max(0.0, (now - lastNanos) / 1.0e9));
        lastNanos = now;
        Minecraft mc = Minecraft.getInstance();

        ShotSpec p = pending;
        if (p != null) {
            pending = null;
            adopt(mc, p, vanillaPos, vanillaYaw, vanillaPitch, vanillaFov);
        }
        if (planner == null) {
            status = Status.idle(mc.level != null, vanillaPos, vanillaYaw, vanillaPitch, vanillaFov);
            return false;
        }

        // 1. Where is the subject this frame?
        double tx;
        double ty;
        double tz;
        double targetYaw = Double.NaN;
        ShotSpec spec = planner.spec;
        AbstractClientPlayer target = spec.player == null ? null : findPlayer(mc, spec.player);
        if (target != null) {
            Vec3 pos = target.getPosition(partial);
            double eye = target.getEyeHeight();
            tx = pos.x;
            ty = pos.y + (spec.type == ShotSpec.Type.PUSHIN ? eye : eye * 0.88);
            tz = pos.z;
            targetYaw = spec.type == ShotSpec.Type.PUSHIN
                    ? target.getViewYRot(partial)
                    : Mth.rotLerp(partial, target.yBodyRotO, target.yBodyRot);
            targetLoaded = true;
            everSeen = true;
        } else if (spec.player == null && spec.hasPoint()) {
            tx = spec.x;
            ty = spec.y;
            tz = spec.z;
            targetLoaded = true;
        } else {
            targetLoaded = false;
            if (!everSeen) {
                // Never seen on this client: hold the lens perfectly still (and do not start the
                // shot's clock) until the server has sent the subject - the tick asks for a /tp.
                status = new Status(true, mc.level != null, spec.type.id(), spec.targetText(), spec.seconds,
                        0, 0, false, sx, sy, sz, outYaw, outPitch, outFov, false, Double.NaN, Double.NaN,
                        Double.NaN, lastTpReason == null ? "waiting for target to load" : lastTpReason);
                return true;
            }
            // Seen before, gone now (left render range, died, logged out): hold on the last known spot.
            tx = fx;
            ty = fy;
            tz = fz;
        }

        // 2. Smooth the focus a touch (absorbs server position corrections); snap on big jumps.
        double jump = Math.sqrt(sq(tx - fx) + sq(ty - fy) + sq(tz - fz));
        if (!focusInit || jump > 24.0) {
            if (focusInit && jump > 24.0) {
                snapNext = true; // the subject warped: cut with it rather than fly across the map
            }
            fx = tx;
            fy = ty;
            fz = tz;
            focusInit = true;
        } else {
            double a = CamMath.alpha(dt, TAU_FOCUS);
            fx += (tx - fx) * a;
            fy += (ty - fy) * a;
            fz += (tz - fz) * a;
        }

        // 3. Ideal camera for this frame.
        planner.step(dt, fx, fy, fz, targetYaw, sx, sz);
        double ix = planner.camX;
        double iy = planner.camY;
        double iz = planner.camZ;
        if (spec.clip && mc.level != null) {
            // Ray from the subject out to the lens: a wall in between pulls the lens in front of it.
            Vec3 from = new Vec3(fx, fy, fz);
            Vec3 to = new Vec3(ix, iy, iz);
            HitResult hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE,
                    CollisionContext.empty()));
            if (hit.getType() != HitResult.Type.MISS) {
                Vec3 h = hit.getLocation();
                Vec3 dir = to.subtract(from);
                double len = dir.length();
                if (len > 1.0e-4) {
                    double keep = Math.max(0.3, from.distanceTo(h) - 0.3) / len;
                    ix = from.x + dir.x * keep;
                    iy = from.y + dir.y * keep;
                    iz = from.z + dir.z * keep;
                }
            }
        }

        // 4. Low-pass toward the ideal (or snap).
        double dist = Math.sqrt(sq(ix - sx) + sq(iy - sy) + sq(iz - sz));
        if (snapNext || !have || dist > SNAP_DISTANCE) {
            sx = ix;
            sy = iy;
            sz = iz;
            lx = planner.lookX;
            ly = planner.lookY;
            lz = planner.lookZ;
            sfov = planner.fov;
            snapNext = false;
            have = true;
        } else {
            double ap = CamMath.alpha(dt, TAU_POS);
            double al = CamMath.alpha(dt, TAU_LOOK);
            double af = CamMath.alpha(dt, TAU_FOV);
            sx += (ix - sx) * ap;
            sy += (iy - sy) * ap;
            sz += (iz - sz) * ap;
            lx += (planner.lookX - lx) * al;
            ly += (planner.lookY - ly) * al;
            lz += (planner.lookZ - lz) * al;
            sfov += (planner.fov - sfov) * af;
        }

        double dx = lx - sx;
        double dy = ly - sy;
        double dz = lz - sz;
        if (dx * dx + dy * dy + dz * dz > 1.0e-6) {
            outYaw = (float) CamMath.yawTo(dx, dz);
            outPitch = (float) CamMath.clamp(CamMath.pitchTo(dx, dy, dz), -90.0, 90.0);
        }
        outFov = (float) sfov;

        status = new Status(true, mc.level != null, spec.type.id(), spec.targetText(), spec.seconds,
                planner.elapsed(), planner.progress(), planner.done(), sx, sy, sz, outYaw, outPitch, outFov,
                targetLoaded, targetLoaded ? fx : Double.NaN, targetLoaded ? fy : Double.NaN,
                targetLoaded ? fz : Double.NaN, lastTpReason);
        return true;
    }

    public static double x() {
        return sx;
    }

    public static double y() {
        return sy;
    }

    public static double z() {
        return sz;
    }

    public static float yaw() {
        return outYaw;
    }

    public static float pitch() {
        return outPitch;
    }

    public static float fov() {
        return outFov;
    }

    // ------------------------------------------------------------------ tick (20 Hz)

    /**
     * END_CLIENT_TICK: keep the (invisible) camera player near the lens so the
     * server streams the chunks and entities the shot needs.
     */
    public static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (planner == null || player == null || mc.getConnection() == null) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        ShotSpec spec = planner.spec;

        // Target never seen on this client: ask the server to put us next to it.
        if (!targetLoaded && spec.player != null && Flags.autoTeleport() && nowMs - lastTpMs > TP_COOLDOWN_MS
                && isSafeName(spec.player)) {
            lastTpMs = nowMs;
            lastTpReason = "tp to unloaded target " + spec.player;
            mc.getConnection().sendCommand("tp @s " + spec.player);
            return;
        }
        if (!have || !player.isSpectator()) {
            // Never drag a survival/creative body through blocks; the server would rubber-band it.
            return;
        }
        double ex = sx;
        double ey = sy - player.getEyeHeight();
        double ez = sz;
        double dx = ex - player.getX();
        double dy = ey - player.getY();
        double dz = ez - player.getZ();
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d > TELEPORT_DISTANCE) {
            if (Flags.autoTeleport() && nowMs - lastTpMs > TP_COOLDOWN_MS) {
                lastTpMs = nowMs;
                lastTpReason = String.format(Locale.ROOT, "tp to lens %.1f %.1f %.1f", ex, ey, ez);
                mc.getConnection().sendCommand(String.format(Locale.ROOT, "tp @s %.2f %.2f %.2f", ex, ey, ez));
            }
            return;
        }
        if (d > 0.05) {
            double k = Math.min(1.0, MAX_PLAYER_STEP / d);
            player.setPos(player.getX() + dx * k, player.getY() + dy * k, player.getZ() + dz * k);
        }
        player.setDeltaMovement(Vec3.ZERO);
        player.setYRot(outYaw);
        player.setXRot(outPitch);
    }

    // ------------------------------------------------------------------ internals

    private static void adopt(Minecraft mc, ShotSpec p, Vec3 vPos, float vYaw, float vPitch, float vFov) {
        if (p.type == ShotSpec.Type.STOP) {
            release(mc);
            return;
        }
        if (planner == null || !have) {
            // Coming from the vanilla camera: start the glide exactly where the lens is now.
            sx = vPos.x;
            sy = vPos.y;
            sz = vPos.z;
            double dist = 6.0;
            double cp = Math.cos(Math.toRadians(vPitch));
            lx = sx + CamMath.fwdX(vYaw) * cp * dist;
            ly = sy - Math.sin(Math.toRadians(vPitch)) * dist;
            lz = sz + CamMath.fwdZ(vYaw) * cp * dist;
            sfov = vFov;
            outYaw = vYaw;
            outPitch = vPitch;
            outFov = vFov;
            have = true;
        }
        boolean newSubject = planner == null || !sameTarget(planner.spec, p);
        if (newSubject) {
            focusInit = false;
            everSeen = false;
        }
        planner = new ShotPlanner(p);
        snapNext = p.cut;
        lastTpReason = null;
        applyShotOptions(mc);
        ShowCam.LOG.info("[showcam] shot {} target={} seconds={} radius={} height={} fov={} speed={} cut={}",
                p.type.id(), p.targetText(), p.seconds, p.radius, p.height, p.fov, p.speed, p.cut);
    }

    private static void release(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (planner != null && have && player != null && player.isSpectator()) {
            // Hand back to vanilla without a visible jump: put the player's eye where the lens is.
            double ey = sy - player.getEyeHeight();
            player.snapTo(sx, ey, sz, outYaw, outPitch);
            player.setOldPosAndRot();
        }
        planner = null;
        have = false;
        focusInit = false;
        everSeen = false;
        restoreOptions(mc);
        ShowCam.LOG.info("[showcam] stop - vanilla camera");
    }

    /** In memory only: OptionsMixin cancels every save while ShowCam is active. */
    private static void applyShotOptions(Minecraft mc) {
        if (!optionsSaved) {
            savedHideGui = mc.options.hideGui;
            savedBobView = mc.options.bobView().get();
            optionsSaved = true;
        }
        if (!Flags.keepHud()) {
            mc.options.hideGui = true;
        }
        mc.options.bobView().set(false);
        if (!mc.options.getCameraType().isFirstPerson()) {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
        }
    }

    private static void restoreOptions(Minecraft mc) {
        if (optionsSaved) {
            mc.options.hideGui = savedHideGui;
            mc.options.bobView().set(savedBobView);
            optionsSaved = false;
        }
    }

    private static AbstractClientPlayer findPlayer(Minecraft mc, String name) {
        if (mc.level == null) {
            return null;
        }
        for (AbstractClientPlayer pl : mc.level.players()) {
            if (pl.isRemoved() || pl == mc.player) {
                continue;
            }
            if (pl.getScoreboardName().equalsIgnoreCase(name)) {
                return pl;
            }
        }
        return null;
    }

    private static boolean sameTarget(ShotSpec a, ShotSpec b) {
        if (a.player != null || b.player != null) {
            return a.player != null && b.player != null && a.player.equalsIgnoreCase(b.player);
        }
        return a.hasPoint() && b.hasPoint() && a.x == b.x && a.y == b.y && a.z == b.z;
    }

    /** Minecraft account names: never let a request smuggle extra command text into /tp. */
    static boolean isSafeName(String s) {
        return s != null && s.matches("[A-Za-z0-9_]{1,16}");
    }

    private static double sq(double v) {
        return v * v;
    }

    /** Immutable snapshot for GET /status (built on the render thread, read on HTTP threads). */
    public record Status(boolean active, boolean inWorld, String shot, String target, double seconds,
                         double elapsed, double progress, boolean done, double x, double y, double z,
                         float yaw, float pitch, float fov, boolean targetLoaded, double tx, double ty,
                         double tz, String note) {

        static Status idle(boolean inWorld, Vec3 pos, float yaw, float pitch, float fov) {
            return new Status(false, inWorld, null, null, 0, 0, 0, false,
                    pos == null ? Double.NaN : pos.x, pos == null ? Double.NaN : pos.y, pos == null ? Double.NaN : pos.z,
                    yaw, pitch, fov, false, Double.NaN, Double.NaN, Double.NaN, null);
        }
    }
}
