import com.aisky.showcam.CamMath;
import com.aisky.showcam.ShotPlanner;
import com.aisky.showcam.ShotSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Headless checks for ShowCam's pure camera maths: runs the SHIPPING
 * CamMath / ShotSpec / ShotPlanner classes with no Minecraft, no Fabric and no
 * gradle. Run with check\check.ps1 (javac + java, a few seconds, single process).
 *
 * Exit code 0 = all passed.
 */
public final class CamMathCheck {

    private static final List<String> FAILS = new ArrayList<>();
    private static int passed;

    public static void main(String[] args) {
        conventions();
        easing();
        smoothing();
        envelope();
        orbit();
        pushin();
        crane();
        dolly();
        statik();
        follow();
        continuity();
        spec();
        System.out.printf(Locale.ROOT, "%n%d passed, %d failed%n", passed, FAILS.size());
        for (String f : FAILS) {
            System.out.println("  FAIL " + f);
        }
        System.exit(FAILS.isEmpty() ? 0 : 1);
    }

    // ------------------------------------------------------------------

    static void conventions() {
        for (double yaw : new double[] {0, 45, 90, -90, 135, 179, -179}) {
            double back = CamMath.yawTo(CamMath.fwdX(yaw), CamMath.fwdZ(yaw));
            check("yawTo inverts fwd at " + yaw, Math.abs(CamMath.wrapDeg(back - yaw)) < 1e-9);
            double dot = CamMath.fwdX(yaw) * CamMath.rightX(yaw) + CamMath.fwdZ(yaw) * CamMath.rightZ(yaw);
            check("right is perpendicular to forward at " + yaw, Math.abs(dot) < 1e-12);
        }
        // Minecraft: yaw 0 looks south (+Z); yaw 90 looks west (-X); your right facing south is west.
        check("yaw 0 looks +Z", Math.abs(CamMath.fwdZ(0) - 1) < 1e-12);
        check("yaw 90 looks -X", Math.abs(CamMath.fwdX(90) + 1) < 1e-12);
        check("right of south is west", Math.abs(CamMath.rightX(0) + 1) < 1e-12);
        check("pitch positive looks DOWN", CamMath.pitchTo(1, -1, 0) > 44.9);
        check("pitch negative looks up", CamMath.pitchTo(0, 1, 1) < -44.9);
        check("wrapDeg 190 -> -170", Math.abs(CamMath.wrapDeg(190) + 170) < 1e-9);
        check("approachDeg takes the short way across +-180",
                Math.abs(CamMath.wrapDeg(CamMath.approachDeg(179, -179, 0.5) - 180)) < 1e-9);
    }

    static void easing() {
        check("ease(0)=0", CamMath.ease(0) == 0);
        check("ease(1)=1", Math.abs(CamMath.ease(1) - 1) < 1e-12);
        check("ease(0.5)=0.5", Math.abs(CamMath.ease(0.5) - 0.5) < 1e-12);
        double prev = -1;
        boolean mono = true;
        for (int i = 0; i <= 100; i++) {
            double v = CamMath.ease(i / 100.0);
            mono &= v >= prev;
            prev = v;
        }
        check("ease monotonic", mono);
        double h = 1e-4;
        check("ease starts at rest", CamMath.ease(h) / h < 1e-6);
        check("ease ends at rest", (1 - CamMath.ease(1 - h)) / h < 1e-6);
    }

    static void smoothing() {
        double a30 = run(30), a144 = run(144);
        check(String.format(Locale.ROOT, "exponential smoothing is frame-rate independent (30fps %.6f vs 144fps %.6f)",
                a30, a144), Math.abs(a30 - a144) < 1e-9);
    }

    private static double run(int fps) {
        double v = 0;
        for (int i = 0; i < fps; i++) {
            v += (1 - v) * CamMath.alpha(1.0 / fps, 0.3);
        }
        return v;
    }

    static void envelope() {
        check("envelope 0 at start", CamMath.envelope(0, 8, 1.5) == 0);
        check("envelope 1 in the middle", Math.abs(CamMath.envelope(4, 8, 1.5) - 1) < 1e-12);
        check("envelope 0 at end", CamMath.envelope(8, 8, 1.5) == 0);
        check("envelope 0 after end", CamMath.envelope(9, 8, 1.5) == 0);
        check("forever envelope reaches 1", Math.abs(CamMath.envelope(30, 0, 1.5) - 1) < 1e-12);
        check("short move still eases (peak <= 1)", CamMath.envelope(1, 2, 1.5) <= 1.0);
    }

    static void orbit() {
        for (int fps : new int[] {30, 60, 144}) {
            ShotSpec s = spec(ShotSpec.Type.ORBIT, 8, Double.NaN, Double.NaN, 1.0);
            ShotPlanner p = new ShotPlanner(s);
            double dt = 1.0 / fps;
            p.step(0, 0, 70, 0, Double.NaN, 6, 0); // camera starts due east (+X)
            double start = Math.atan2(p.camZ, p.camX);
            double total = 0, prevA = start;
            boolean radiusOk = true;
            for (int i = 0; i < fps * 10; i++) {
                p.step(dt, 0, 70, 0, Double.NaN, 0, 0);
                double r = Math.hypot(p.camX, p.camZ);
                radiusOk &= Math.abs(r - 6) < 1e-9;
                double a = Math.atan2(p.camZ, p.camX);
                double d = a - prevA;
                if (d > Math.PI) d -= 2 * Math.PI;
                if (d < -Math.PI) d += 2 * Math.PI;
                total += d;
                prevA = a;
            }
            double deg = Math.toDegrees(total);
            // Integral of a smootherstep ramp is half its width: 18 deg/s * (8 - 1.5) = 117 deg.
            check(String.format(Locale.ROOT, "orbit @%dfps sweeps 117 deg in 8 s then stops (got %.2f)", fps, deg),
                    Math.abs(deg - 117) < 1.0);
            check("orbit @" + fps + "fps keeps its radius", radiusOk);
            check("orbit starts from the camera's current bearing", Math.abs(start) < 1e-9);
            check("orbit is done after its seconds", p.done());
        }
    }

    static void pushin() {
        ShotSpec s = spec(ShotSpec.Type.PUSHIN, 4, 4, 0.5, 1);
        ShotPlanner p = new ShotPlanner(s);
        p.step(0, 0, 70, 0, 0.0, 0, 0);
        double prev = Double.MAX_VALUE;
        boolean closing = true, inFront = true;
        for (int i = 0; i < 300; i++) {
            p.step(1 / 60.0, 0, 70, 0, 0.0, 0, 0);
            double d = Math.hypot(p.camX, p.camZ);
            closing &= d <= prev + 1e-9;
            prev = d;
            inFront &= p.camZ > 0; // target faces +Z (yaw 0): the lens must be in front of the face
        }
        check("push-in closes distance monotonically", closing);
        check("push-in stays in front of the face", inFront);
        check(String.format(Locale.ROOT, "push-in ends at 1.2 blocks (got %.3f)", prev), Math.abs(prev - 1.2) < 1e-6);
        check(String.format(Locale.ROOT, "push-in narrows FOV to 55%% (got %.2f)", p.fov), Math.abs(p.fov - 33) < 1e-6);
    }

    static void crane() {
        ShotSpec s = spec(ShotSpec.Type.CRANE, 6, 5, 6, 1);
        ShotPlanner p = new ShotPlanner(s);
        p.step(0, 0, 70, 0, Double.NaN, 5, 0);
        double y0 = p.camY, d0 = Math.hypot(p.camX, p.camZ);
        for (int i = 0; i < 6 * 60; i++) {
            p.step(1 / 60.0, 0, 70, 0, Double.NaN, 5, 0);
        }
        check("crane rises (" + y0 + " -> " + p.camY + ")", y0 < 70 && Math.abs(p.camY - 76) < 1e-6);
        check("crane pulls back", Math.hypot(p.camX, p.camZ) > d0 * 3);
    }

    static void dolly() {
        ShotSpec s = spec(ShotSpec.Type.DOLLY, 6, 5, 1, 1);
        ShotPlanner p = new ShotPlanner(s);
        p.step(0, 0, 70, 0, Double.NaN, 5, 0);
        double startD = Math.hypot(p.camX, p.camZ);
        double minD = Double.MAX_VALUE;
        double minAt = -1;
        for (int i = 1; i <= 360; i++) {
            p.step(1 / 60.0, 0, 70, 0, Double.NaN, 5, 0);
            double d = Math.hypot(p.camX, p.camZ);
            if (d < minD) {
                minD = d;
                minAt = i / 60.0;
            }
        }
        check(String.format(Locale.ROOT, "dolly passes closest at mid-shot (t=%.2f, d=%.3f)", minAt, minD),
                Math.abs(minAt - 3.0) < 0.05 && Math.abs(minD - 5) < 1e-3);
        check("dolly starts off to the side", startD > 6);
    }

    static void statik() {
        ShotSpec s = spec(ShotSpec.Type.STATIC, 0, 6, 2, 1);
        ShotPlanner p = new ShotPlanner(s);
        p.step(0, 0, 70, 0, Double.NaN, 6, 0);
        double x = p.camX, y = p.camY, z = p.camZ;
        for (int i = 0; i < 120; i++) {
            p.step(1 / 60.0, i * 0.05, 70, 0, Double.NaN, x, z); // target walks away
        }
        check("static camera does not move", p.camX == x && p.camY == y && p.camZ == z);
        check("static camera keeps looking at the moving target", Math.abs(p.lookX - 119 * 0.05) < 1e-9);
    }

    static void follow() {
        ShotSpec s = spec(ShotSpec.Type.FOLLOW, 0, 3.5, 0.8, 1);
        ShotPlanner p = new ShotPlanner(s);
        p.step(0, 0, 70, 0, 0.0, 0, -3);
        for (int i = 0; i < 120; i++) {
            p.step(1 / 60.0, 0, 70, 0, 0.0, 0, -3);
        }
        check("follow sits behind a target facing +Z", p.camZ < -3.0);
        check("follow sits over the RIGHT shoulder (west when facing south)", p.camX < -0.5);
        check("follow looks ahead of the target", p.lookZ > 7.9);
        // Facing flicks across the +-180 seam must not swing the camera the long way round.
        ShotPlanner q = new ShotPlanner(s);
        q.step(0, 0, 70, 0, 179.0, 0, 0);
        q.step(1 / 60.0, 0, 70, 0, -179.0, 0, 0);
        double angle = Math.toDegrees(Math.atan2(-q.camZ, -q.camX));
        check("follow handles the yaw seam", Math.hypot(q.camX, q.camZ) > 3.0 && !Double.isNaN(angle));
    }

    /** No shot may make the IDEAL camera jump: per-frame displacement at 60 fps stays tiny. */
    static void continuity() {
        for (ShotSpec.Type t : new ShotSpec.Type[] {ShotSpec.Type.ORBIT, ShotSpec.Type.PUSHIN, ShotSpec.Type.FOLLOW,
                ShotSpec.Type.CRANE, ShotSpec.Type.DOLLY, ShotSpec.Type.STATIC}) {
            ShotSpec s = ShotSpec.resolve(t, "Kaito", Double.NaN, Double.NaN, Double.NaN, 6, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, false, true);
            ShotPlanner p = new ShotPlanner(s);
            double tx = 0, tz = 0, yaw = 0;
            p.step(0, tx, 70, tz, yaw, 5, 5);
            double px = p.camX, py = p.camY, pz = p.camZ, worst = 0;
            for (int i = 0; i < 8 * 60; i++) {
                tx += 4.3 / 60.0; // the subject sprints along +X while turning
                yaw += 30.0 / 60.0;
                p.step(1 / 60.0, tx, 70, tz, yaw, p.camX, p.camZ);
                double d = Math.sqrt(sq(p.camX - px) + sq(p.camY - py) + sq(p.camZ - pz));
                worst = Math.max(worst, d);
                px = p.camX;
                py = p.camY;
                pz = p.camZ;
            }
            check(String.format(Locale.ROOT, "%s ideal path is continuous (worst %.3f blocks/frame)", t.id(), worst),
                    worst < 0.5);
        }
    }

    static void spec() {
        check("parse push-in", ShotSpec.Type.parse("push-in") == ShotSpec.Type.PUSHIN);
        check("parse ORBIT", ShotSpec.Type.parse("ORBIT") == ShotSpec.Type.ORBIT);
        check("parse junk -> null", ShotSpec.Type.parse("zoom") == null);
        ShotSpec d = ShotSpec.resolve(ShotSpec.Type.PUSHIN, "A", Double.NaN, Double.NaN, Double.NaN, 0, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, false, true);
        check("move shots get a duration even when seconds=0", d.seconds == 4);
        ShotSpec c = ShotSpec.resolve(ShotSpec.Type.ORBIT, "A", Double.NaN, Double.NaN, Double.NaN, 1e9, 9999, 9999,
                500, 99, false, true);
        check("absurd values are clamped", c.seconds == 600 && c.radius == 64 && c.height == 64 && c.fov == 120
                && c.speed == 10);
        ShotSpec o = ShotSpec.resolve(ShotSpec.Type.ORBIT, null, 1, 2, 3, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, false, true);
        check("orbit defaults: forever, r6 h2 fov60", o.seconds == 0 && o.radius == 6 && o.height == 2 && o.fov == 60);
        check("point target recognised", o.hasPoint() && o.targetText().equals("1.0,2.0,3.0"));
    }

    // ------------------------------------------------------------------

    private static ShotSpec spec(ShotSpec.Type t, double seconds, double radius, double height, double speed) {
        return ShotSpec.resolve(t, null, 0, 70, 0, seconds, radius, height, Double.NaN, speed, false, true);
    }

    private static double sq(double v) {
        return v * v;
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ok   " + name);
        } else {
            FAILS.add(name);
            System.out.println("  FAIL " + name);
        }
    }
}
