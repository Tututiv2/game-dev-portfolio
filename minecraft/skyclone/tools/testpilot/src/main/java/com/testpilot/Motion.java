package com.testpilot;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * A replica of the client player's own movement physics ({@code LivingEntity.travelInAir},
 * {@code Entity.collide} with step-up, ladders), run ahead of time to answer one question before
 * the walker presses a key: <b>if I hold these keys, can I still stop without falling?</b>
 *
 * <p>Why it exists (wave-4 final verification, PLAYTEST.md #16): the old edge guard asked "is
 * there floor under the box a little way ahead" and only while the player was ON the ground. On a
 * player's own Private Island a sprint walk west along the islet's jagged north rim - a one-wide
 * staircase ledge stepping down 74 -> 73 -> 72 with the void beyond - fell off 3 times out of 3
 * with {@code edgeStops: 0}: every step DOWN puts the player in the air for five ticks, a sprint
 * carries 0.28 blocks a tick through the air, so the body lands ~1.4 blocks past the lip of the
 * step it walked off - past the end of a one-block ledge. No look-ahead at the feet sees that,
 * because the danger is not under the next step, it is where the momentum lands. So the guard now
 * plays the next ticks forward with the real numbers (ground friction 0.546, air drag 0.91, sprint
 * 1.3x, the 0.2 sprint-jump kick, gravity 0.08 with 0.98 drag, the ladder clamp) against the real
 * block shapes, and a key set is allowed only if braking right after it still ends with the player
 * standing on something.
 *
 * <p>Pure: no client classes, so {@code MotionTest} runs it with no game.
 */
final class Motion {

    static final double HALF_W = Nav.HALF_W;
    static final double HEIGHT = Nav.HEIGHT;
    /** Half-width of the body's "core": standing still with it over a deep hole is not allowed. */
    static final double CORE = 0.15;
    /** Ticks the chosen keys are assumed held before braking starts (decision + 1 tick key latency + margin). */
    static final int COMMIT = 3;
    /** Longest look ahead: a 3.5-block fall takes 13 ticks, a ground stop from a sprint 4. */
    static final int HORIZON = 80;

    private static final double EPS = 1e-7;

    private Motion() {
    }

    // ------------------------------------------------------------------ state

    /** The player's movement state: position (feet centre), velocity per tick, flags. */
    static final class Body {
        double x, y, z, vx, vy, vz;
        boolean onGround, sprinting, horizontalCollision;
        int jumpDelay;
        /** MOVEMENT_SPEED without the sprint modifier (0.1 for a plain player; SkyBlock Speed raises it). */
        double speed = 0.1;
        double jumpPower = 0.42, gravity = 0.08, stepHeight = 0.6;
        /**
         * The real hitbox: half its width and its height. Not always 0.6 x 1.8 - the pets module's Rat
         * "Shapeshift" sets SCALE 0.3, making the player 0.18 wide and 0.54 tall (sneaking is 1.5 tall).
         */
        double halfW = HALF_W, height = HEIGHT;
        /** The {@link #edgeMargin} to keep; NaN (always, outside tests) = by {@link #speed}. */
        double margin = Double.NaN;
        /** Feet height the last time something held the player up (ground, ladder, water). */
        double supportY = Double.NaN;
        /**
         * Water is not somewhere to step: walking into it is a risk ("water") like a fall. Set for a
         * {@code /walkto dry:true} (kill_nearest from a dock - wave 5, the bot followed a sea creature
         * off the Bayou dock and drowned under the deck).
         */
        boolean dry;

        Body copy() {
            Body b = new Body();
            b.x = x;
            b.y = y;
            b.z = z;
            b.vx = vx;
            b.vy = vy;
            b.vz = vz;
            b.onGround = onGround;
            b.sprinting = sprinting;
            b.horizontalCollision = horizontalCollision;
            b.jumpDelay = jumpDelay;
            b.speed = speed;
            b.jumpPower = jumpPower;
            b.gravity = gravity;
            b.stepHeight = stepHeight;
            b.halfW = halfW;
            b.height = height;
            b.margin = margin;
            b.supportY = supportY;
            b.dry = dry;
            return b;
        }

        AABB box() {
            return new AABB(x - halfW, y, z - halfW, x + halfW, y + height, z + halfW);
        }

        double hspeed() {
            return Math.sqrt(vx * vx + vz * vz);
        }

        @Override
        public String toString() {
            return String.format("(%.3f, %.3f, %.3f) v(%.3f, %.3f, %.3f)%s", x, y, z, vx, vy, vz, onGround ? " ground" : "");
        }
    }

    /** The movement keys for one tick, and where the player faces (yaw: 0 = +z/south, 90 = -x/west). */
    static final class Keys {
        boolean forward, back, left, right, jump, sneak, sprint;
        /** Let go of a sprint already running (vanilla keeps sprinting while W is held). */
        boolean stopSprint;
        float yaw;

        Keys copy() {
            Keys k = new Keys();
            k.forward = forward;
            k.back = back;
            k.left = left;
            k.right = right;
            k.jump = jump;
            k.sneak = sneak;
            k.sprint = sprint;
            k.stopSprint = stopSprint;
            k.yaw = yaw;
            return k;
        }

        boolean moving() {
            return forward || back || left || right || jump;
        }

        @Override
        public String toString() {
            StringBuilder s = new StringBuilder();
            if (forward) s.append('W');
            if (back) s.append('S');
            if (left) s.append('A');
            if (right) s.append('D');
            if (jump) s.append(" jump");
            if (sneak) s.append(" sneak");
            if (sprint) s.append(" sprint");
            if (stopSprint) s.append(" stopSprint");
            return (s.length() == 0 ? "none" : s.toString().trim()) + String.format(" yaw %.0f", yaw);
        }
    }

    /** Minecraft's yaw for facing along (dx, dz). */
    static float yawOf(double dx, double dz) {
        return Mth.wrapDegrees((float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f);
    }

    // ------------------------------------------------------------------ one tick

    /** Advance {@code b} by one client tick with keys {@code k} held, as LocalPlayer.aiStep does. */
    static void tick(BlockGetter level, Body b, Keys k) {
        // LivingEntity.aiStep: tiny velocities are zeroed
        if (Math.abs(b.vx) < 0.003) b.vx = 0;
        if (Math.abs(b.vy) < 0.003) b.vy = 0;
        if (Math.abs(b.vz) < 0.003) b.vz = 0;

        // LocalPlayer.aiStep: sprinting starts with sprint+W and ends with W released or a wall
        boolean fwd = k.forward && !k.back;
        if (b.sprinting && (!fwd || b.horizontalCollision || k.stopSprint || k.sneak)) {
            b.sprinting = false;
        } else if (!b.sprinting && fwd && k.sprint && !k.sneak && !k.stopSprint) {
            b.sprinting = true;
        }

        // input vector (LocalPlayer.modifyInput): x = strafe (left +), y = forward
        double ix = (k.left ? 1 : 0) - (k.right ? 1 : 0);
        double iz = (k.forward ? 1 : 0) - (k.back ? 1 : 0);
        if (ix != 0 || iz != 0) {
            double s = 0.98 * (k.sneak ? 0.3 : 1.0);
            ix *= s;
            iz *= s;
            double len = Math.sqrt(ix * ix + iz * iz);
            double ux = ix / len, uz = iz / len;
            double toSquare = 1.0 / Math.max(Math.abs(ux), Math.abs(uz));
            double scale = Math.min(len * toSquare, 1.0);
            ix = ux * scale;
            iz = uz * scale;
        }

        // LivingEntity.aiStep: jumping
        if (b.jumpDelay > 0) {
            b.jumpDelay--;
        }
        if (k.jump) {
            if (b.onGround && b.jumpDelay == 0) {
                b.vy = Math.max(b.jumpPower, b.vy);
                if (b.sprinting) {
                    double r = k.yaw * Mth.DEG_TO_RAD;
                    b.vx += -Mth.sin(r) * 0.2;
                    b.vz += Mth.cos(r) * 0.2;
                }
                b.jumpDelay = 10;
            }
        } else {
            b.jumpDelay = 0;
        }

        // LivingEntity.travelInAir
        float f = b.onGround ? friction(level, b) : 1.0f;
        double g = f * 0.91f;
        double spd = b.onGround
                ? b.speed * (b.sprinting ? 1.3 : 1.0) * (0.21600002f / (f * f * f))
                : (b.sprinting ? 0.025999999f : 0.02f);
        double l2 = ix * ix + iz * iz;
        if (l2 >= 1e-7) {
            if (l2 > 1) {
                double l = Math.sqrt(l2);
                ix /= l;
                iz /= l;
            }
            ix *= spd;
            iz *= spd;
            double r = k.yaw * Mth.DEG_TO_RAD;
            double sin = Mth.sin(r), cos = Mth.cos(r);
            b.vx += ix * cos - iz * sin;
            b.vz += iz * cos + ix * sin;
        }
        // handleOnClimbable: a ladder caps the speed and the fall; sneaking holds you on it
        if (onClimbable(level, b)) {
            b.vx = Mth.clamp(b.vx, -0.15, 0.15);
            b.vz = Mth.clamp(b.vz, -0.15, 0.15);
            b.vy = Math.max(b.vy, -0.15);
            if (b.vy < 0 && k.sneak) {
                b.vy = 0;
            }
        }
        move(level, b);
        if ((b.horizontalCollision || k.jump) && onClimbable(level, b)) {
            b.vy = 0.2;                                     // climbing
        }
        b.vy = (b.vy - b.gravity) * 0.98f;
        b.vx *= g;
        b.vz *= g;
    }

    private static float friction(BlockGetter level, Body b) {
        BlockState s = level.getBlockState(BlockPos.containing(b.x, b.y - 0.500001, b.z));
        return s.isAir() ? 0.6f : s.getBlock().getFriction();
    }

    static boolean onClimbable(BlockGetter level, Body b) {
        return Nav.climbable(level.getBlockState(BlockPos.containing(b.x, b.y, b.z)));
    }

    // ------------------------------------------------------------------ collisions

    /** Entity.move: collide (with the 0.6 auto-step), set onGround / horizontalCollision, zero what hit. */
    static void move(BlockGetter level, Body b) {
        AABB box = b.box();
        double mx = b.vx, my = b.vy, mz = b.vz;
        double[] r = collide(level, box, mx, my, mz);
        boolean xc = r[0] != mx, zc = r[2] != mz, yc = r[1] != my;
        boolean below = yc && my < 0;
        if (b.stepHeight > 0 && (below || b.onGround) && (xc || zc)) {
            AABB base = below ? box.move(0, r[1], 0) : box;
            double done = r[0] * r[0] + r[2] * r[2];
            for (double h : stepHeights(level, base, mx, mz, b.stepHeight, r[1])) {
                double[] s = collide(level, base, mx, h, mz);
                if (s[0] * s[0] + s[2] * s[2] > done) {
                    r = new double[] {s[0], s[1] + (base.minY - box.minY), s[2]};
                    break;
                }
            }
            xc = r[0] != mx;
            zc = r[2] != mz;
            yc = r[1] != my;
            below = yc && my < 0;
        }
        b.x += r[0];
        b.y += r[1];
        b.z += r[2];
        b.horizontalCollision = xc || zc;
        b.onGround = below;
        if (xc) b.vx = 0;
        if (zc) b.vz = 0;
        if (yc) b.vy = 0;
    }

    /** Y first, then the larger horizontal axis first (Direction.axisStepOrder). */
    static double[] collide(BlockGetter level, AABB box, double mx, double my, double mz) {
        double dy = clip(level, box, 1, my);
        box = box.move(0, dy, 0);
        double dx, dz;
        if (Math.abs(mx) < Math.abs(mz)) {
            dz = clip(level, box, 2, mz);
            box = box.move(0, 0, dz);
            dx = clip(level, box, 0, mx);
        } else {
            dx = clip(level, box, 0, mx);
            box = box.move(dx, 0, 0);
            dz = clip(level, box, 2, mz);
        }
        return new double[] {dx, dy, dz};
    }

    /** How far {@code box} can move along {@code axis} (0 x, 1 y, 2 z) up to {@code d} before it hits a block. */
    static double clip(BlockGetter level, AABB box, int axis, double d) {
        if (Math.abs(d) < EPS) {
            return 0;
        }
        AABB sweep = box.expandTowards(axis == 0 ? d : 0, axis == 1 ? d : 0, axis == 2 ? d : 0);
        int x0 = Mth.floor(sweep.minX - EPS), x1 = Mth.floor(sweep.maxX + EPS);
        int y0 = Mth.floor(sweep.minY - EPS) - 1, y1 = Mth.floor(sweep.maxY + EPS);   // -1: fences reach up 1.5
        int z0 = Mth.floor(sweep.minZ - EPS), z1 = Mth.floor(sweep.maxZ + EPS);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    BlockState s = level.getBlockState(m.set(x, y, z));
                    for (AABB a : Nav.boxes(level, s, m)) {
                        double ax0 = a.minX + x, ax1 = a.maxX + x, ay0 = a.minY + y, ay1 = a.maxY + y, az0 = a.minZ + z, az1 = a.maxZ + z;
                        switch (axis) {
                            case 0 -> {
                                if (ay0 < box.maxY - EPS && ay1 > box.minY + EPS && az0 < box.maxZ - EPS && az1 > box.minZ + EPS) {
                                    if (d > 0 && ax0 >= box.maxX - EPS) d = Math.min(d, ax0 - box.maxX);
                                    else if (d < 0 && ax1 <= box.minX + EPS) d = Math.max(d, ax1 - box.minX);
                                }
                            }
                            case 1 -> {
                                if (ax0 < box.maxX - EPS && ax1 > box.minX + EPS && az0 < box.maxZ - EPS && az1 > box.minZ + EPS) {
                                    if (d > 0 && ay0 >= box.maxY - EPS) d = Math.min(d, ay0 - box.maxY);
                                    else if (d < 0 && ay1 <= box.minY + EPS) d = Math.max(d, ay1 - box.minY);
                                }
                            }
                            default -> {
                                if (ax0 < box.maxX - EPS && ax1 > box.minX + EPS && ay0 < box.maxY - EPS && ay1 > box.minY + EPS) {
                                    if (d > 0 && az0 >= box.maxZ - EPS) d = Math.min(d, az0 - box.maxZ);
                                    else if (d < 0 && az1 <= box.minZ + EPS) d = Math.max(d, az1 - box.minZ);
                                }
                            }
                        }
                    }
                }
            }
        }
        return Math.abs(d) < EPS ? 0 : d;
    }

    /** Entity.collectCandidateStepUpHeights: block-shape y boundaries 0..stepHeight above the box's feet. */
    private static List<Double> stepHeights(BlockGetter level, AABB base, double mx, double mz, double step, double skip) {
        AABB region = base.expandTowards(mx, step, mz);
        TreeSet<Double> hs = new TreeSet<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = Mth.floor(region.minX - EPS); x <= Mth.floor(region.maxX + EPS); x++) {
            for (int z = Mth.floor(region.minZ - EPS); z <= Mth.floor(region.maxZ + EPS); z++) {
                for (int y = Mth.floor(region.minY - EPS) - 1; y <= Mth.floor(region.maxY + EPS); y++) {
                    BlockState s = level.getBlockState(m.set(x, y, z));
                    for (AABB a : Nav.boxes(level, s, m)) {
                        for (double c : new double[] {a.minY + y, a.maxY + y}) {
                            double h = (float) (c - base.minY);
                            if (h >= 0 && h <= step + EPS && h != (float) skip) {
                                hs.add(h);
                            }
                        }
                    }
                }
            }
        }
        return new ArrayList<>(hs);
    }

    // ------------------------------------------------------------------ support

    /**
     * Something within {@code maxDrop} under the square footprint {@code x+-half, z+-half} at feet
     * {@code feetY} that stops a fall: a block's collision top, water, or a ladder/vine (which
     * catches a falling player). False over the void or an unloaded chunk.
     */
    static boolean supportUnder(BlockGetter level, double x, double z, double half, double feetY, double maxDrop) {
        if (level instanceof LevelReader r && !r.hasChunkAt(BlockPos.containing(x, feetY, z))) {
            return false;
        }
        double fx0 = x - half, fx1 = x + half, fz0 = z - half, fz1 = z + half;
        double lowest = feetY - maxDrop - 1e-3;
        int top = Mth.floor(feetY + 0.5), bottom = Mth.floor(lowest);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int bx = Mth.floor(fx0); bx <= Mth.floor(fx1 - EPS); bx++) {
            for (int bz = Mth.floor(fz0); bz <= Mth.floor(fz1 - EPS); bz++) {
                for (int by = top; by >= bottom; by--) {
                    BlockState s = level.getBlockState(m.set(bx, by, bz));
                    if (Nav.climbable(s) || Nav.isWater(s)) {
                        return true;
                    }
                    for (AABB a : Nav.boxes(level, s, m)) {
                        if (a.minX + bx < fx1 && a.maxX + bx > fx0 && a.minZ + bz < fz1 && a.maxZ + bz > fz0
                                && by + a.maxY >= lowest && by + a.maxY <= feetY + 0.5 + EPS) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** A deep drop within {@code r} of a normal-sized body (any of 16 points around it): no sprinting here. */
    static boolean nearVoid(BlockGetter level, double x, double z, double feetY, double r) {
        return nearVoid(level, x, z, feetY, r, HALF_W);
    }

    /** {@link #nearVoid} for a body {@code halfW} wide each side (a shrunken player sees more drops). */
    static boolean nearVoid(BlockGetter level, double x, double z, double feetY, double r, double halfW) {
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            for (double d : new double[] {r * 0.5, r}) {
                if (!supportUnder(level, x + Math.cos(a) * d, z + Math.sin(a) * d, halfW, feetY, Nav.SAFE_DROP)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A deep drop within 2.5 blocks along (dx, dz): the reason a walk toward there stopped is an edge. */
    static boolean edgeToward(BlockGetter level, double x, double z, double feetY, double dx, double dz) {
        return edgeToward(level, x, z, feetY, dx, dz, HALF_W);
    }

    /** {@link #edgeToward} for a body {@code halfW} wide each side. */
    static boolean edgeToward(BlockGetter level, double x, double z, double feetY, double dx, double dz, double halfW) {
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 1e-9) {
            return false;
        }
        for (double s : new double[] {0.8, 1.3, 1.8, 2.5}) {
            if (!supportUnder(level, x + dx / d * s, z + dz / d * s, halfW, feetY, Nav.SAFE_DROP)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the safety margin

    /**
     * How far short of falling the body must come to rest, in blocks. 0.15 at a plain player's
     * walk speed (0.1) - the margin the guard was tuned and verified with: a 0.6-wide box falls
     * once its centre is 0.3 past the edge, so its 0.15 {@link #CORE} must stay on the ground. Any
     * tick the prediction is off by (key latency, a server correction) costs a tick of motion, and
     * motion scales with MOVEMENT_SPEED, so a faster body keeps a proportionally wider margin:
     * Rat "Turbo Tail" doubles speed (wave-5 playtest: off the Leviathan dock), and SkyBlock Speed
     * goes up to 4x. Capped at 0.35 so a one-wide bridge stays walkable at any speed.
     */
    static double edgeMargin(double speed) {
        return Mth.clamp(0.15 * speed / 0.1, 0.15, 0.35);
    }

    /**
     * The body stands with its margin: its centre is no more than {@code halfW - margin} past
     * the ground's edge. For a plain player that is "the 0.15 core is over ground" - unchanged.
     * For a body narrower than twice its margin (a 0.18-wide rat: it falls once its centre is only
     * 0.09 past the edge) the centre must instead be {@code margin - halfW} INSIDE the edge, which
     * is every corner of a square that far round the centre over ground.
     */
    static boolean stands(BlockGetter level, Body b) {
        double c = b.halfW - (Double.isNaN(b.margin) ? edgeMargin(b.speed) : b.margin);
        if (c >= 0.01) {
            return supportUnder(level, b.x, b.z, c, b.y, Nav.SAFE_DROP);
        }
        double d = Math.max(0, -c);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                if (!supportUnder(level, b.x + sx * d, b.z + sz * d, 0.005, b.y, Nav.SAFE_DROP)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean touches(BlockGetter level, AABB box, boolean lava) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX - EPS); x++) {
            for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ - EPS); z++) {
                for (int y = Mth.floor(box.minY); y <= Mth.floor(box.maxY - EPS); y++) {
                    FluidState f = level.getFluidState(m.set(x, y, z));
                    if (f.isEmpty()) {
                        continue;
                    }
                    boolean isLava = f.getType() == Fluids.LAVA || f.getType() == Fluids.FLOWING_LAVA;
                    if (isLava == lava && y + f.getHeight(level, m) > box.minY) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Onto a ladder hole's grab point (a window ~0.2 wide, {@link Nav#grabPoint}): a speed limit that
     * shrinks with the distance ({@code ax, az} to go, {@code ad} its length; velocity
     * {@code vx, vz}), on both axes of the facing - W/S along it, A/D across it - so the body neither
     * stops on the rim nor coasts over onto the far side. Uses {@code k.yaw}; sets the movement keys.
     */
    static void creep(Keys k, double vx, double vz, double ax, double az, double ad) {
        double r = k.yaw * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(r), fz = Mth.cos(r);           // facing
        double rx = -fz, rz = fx;                             // right-hand side (D)
        double ef = ax * fx + az * fz, er = ax * rx + az * rz;
        double vf = vx * fx + vz * fz, vr = vx * rx + vz * rz;
        double wf = Mth.clamp(ef * 0.45, -0.13, 0.13), wr = Mth.clamp(er * 0.45, -0.13, 0.13);
        k.forward = Math.abs(ef) > 0.02 && vf < wf - 0.01 && wf > 0;
        k.back = Math.abs(ef) > 0.02 && vf > wf + 0.01 && (wf < 0 || vf > wf + 0.05);
        k.right = Math.abs(er) > 0.02 && vr < wr - 0.01 && wr > 0;
        k.left = Math.abs(er) > 0.02 && vr > wr + 0.01 && (wr < 0 || vr > wr + 0.05);
        if (ad < 0.02) {
            k.forward = k.back = k.left = k.right = false;
        }
    }

    // ------------------------------------------------------------------ the guard

    /** What braking looks like: face the motion, hold S while still going, hold sneak on a ladder. */
    static Keys brake(BlockGetter level, Body b, float yaw) {
        Keys k = new Keys();
        double hv = b.hspeed();
        k.yaw = hv > 0.01 ? yawOf(b.vx, b.vz) : yaw;
        k.back = hv > 0.02;
        k.sneak = onClimbable(level, b);
        k.stopSprint = true;
        return k;
    }

    /**
     * Null when holding {@code keys} for {@code commit} ticks and then braking ends with the player
     * held up by something; otherwise why not ("fall", "edge", "lava", "unloaded"). "edge" is the
     * safety margin: once the body's core has been over solid ground, it must never come to stand
     * with its core over a deep drop - the footprint still touching the rim is how a jagged edge
     * holds a player up right until it does not.
     */
    static String risk(BlockGetter level, Body start, Keys keys, int commit) {
        Body b = start.copy();
        if (Double.isNaN(b.supportY)) {
            b.supportY = b.y;
        }
        boolean armed = !b.onGround || stands(level, b);
        for (int t = 0; t < HORIZON; t++) {
            Keys k = t < commit ? keys : brake(level, b, keys.yaw);
            tick(level, b, k);
            if (level instanceof LevelReader r && !r.hasChunkAt(BlockPos.containing(b.x, b.y, b.z))) {
                return "unloaded";
            }
            AABB box = b.box();
            if (touches(level, box, true)) {
                return "lava";
            }
            if (touches(level, box, false)) {
                // water breaks any fall; swimming is the walker's job - unless the walk must stay dry
                return b.dry ? "water" : null;
            }
            boolean climbing = onClimbable(level, b);
            if (b.onGround || climbing) {
                b.supportY = b.y;
            }
            if (b.y < b.supportY - Nav.SAFE_DROP) {
                return "fall";
            }
            if (b.onGround) {
                if (stands(level, b)) {
                    armed = true;
                } else if (armed) {
                    return "edge";
                }
            }
            if (t >= commit - 1 && b.hspeed() < 0.005 && (b.onGround || (climbing && k.sneak))) {
                return null;                                // at rest, held up
            }
        }
        // still moving at the horizon (a long slide down a ladder): something must be under it
        return supportUnder(level, b.x, b.z, b.halfW, b.y, b.y - (b.supportY - Nav.SAFE_DROP)) ? null : "fall";
    }

    /** The keys actually pressed this tick, and how far they had to be toned down. */
    static final class Choice {
        final Keys keys;
        /** 0 as wanted, 1 no sprint, 2 no sprint no jump, 3 coast, 4 brake, 5 brake and nothing was safe. */
        final int level;
        /** Why the wanted keys were refused (null at level 0). */
        final String risk;

        Choice(Keys keys, int level, String risk) {
            this.keys = keys;
            this.level = level;
            this.risk = risk;
        }

        boolean holding() {
            return level >= 3;
        }
    }

    /**
     * The most of {@code want} that is safe: as wanted, then without sprint, then without the jump,
     * then letting go (coast), then braking. If even braking is predicted to fall, brake anyway and
     * hold sneak - vanilla's own sneak never lets a grounded player walk off a block edge.
     */
    static Choice choose(BlockGetter level, Body body, Keys want) {
        List<Keys> tries = new ArrayList<>(4);
        tries.add(want);
        if (want.sprint || body.sprinting) {
            Keys k = want.copy();
            k.sprint = false;
            k.stopSprint = true;
            tries.add(k);
        }
        if (want.jump) {
            Keys k = want.copy();
            k.sprint = false;
            k.stopSprint = true;
            k.jump = false;
            tries.add(k);
        }
        Keys coast = new Keys();
        coast.yaw = want.yaw;
        coast.stopSprint = true;
        coast.sneak = want.sneak;
        String first = null;
        for (int i = 0; i < tries.size(); i++) {
            String r = risk(level, body, tries.get(i), COMMIT);
            if (r == null) {
                int lvl = i == 0 ? 0 : (tries.get(i).jump || !want.jump ? 1 : 2);
                return new Choice(tries.get(i), lvl, first);
            }
            if (first == null) {
                first = r;
            }
        }
        if (want.moving() && risk(level, body, coast, COMMIT) == null) {
            return new Choice(coast, 3, first);
        }
        Keys brake = brake(level, body, want.yaw);
        if (risk(level, body, brake, 1) == null) {
            return new Choice(brake, 4, first);
        }
        brake.sneak = true;
        return new Choice(brake, 5, first);
    }

    // ------------------------------------------------------------------ flight

    /**
     * Keeps the walker from double-tapping jump while the player may fly. In vanilla two jump
     * PRESSES within 7 ticks toggle flight when {@code mayfly} is set ({@code LocalPlayer.aiStep},
     * {@code jumpTriggerTime}), and the pets module's Rat "Turbo Tail" grants exactly that. The
     * walker presses jump for steps (6-tick cooldown), ladders, swimming and the stuck "wiggle",
     * and the AI show's stuck recovery sends {@code /move} jump bursts - so a rat could start
     * flying without anyone asking, the guard (which models walking, not flight) stands down while
     * flying, and the next double tap stops the flight wherever it is, over the void included.
     * A held key is not a press, so climbing a ladder or swimming with jump held is untouched; only
     * a NEW press within {@link #DOUBLE_TAP} ticks of the last one is held back a few ticks.
     */
    static final class JumpGate {
        /** Vanilla's window is 7 ticks; 2 more for the tick the key state reaches the client. */
        static final int DOUBLE_TAP = 9;
        private long lastPress = Long.MIN_VALUE / 2;

        /**
         * May jump be held down on tick {@code tick}? {@code held}: the key is down already (not a
         * new press). Ticks are any counter that goes up by one a tick; one that goes backwards (a
         * new player entity after a respawn or world change) starts the gate afresh.
         */
        boolean allow(long tick, boolean held, boolean mayFly) {
            if (tick < lastPress) {
                lastPress = Long.MIN_VALUE / 2;
            }
            return held || !mayFly || tick - lastPress > DOUBLE_TAP;
        }

        /** Record what was applied on {@code tick}: {@code down} the key now, {@code held} the key before. */
        void applied(long tick, boolean held, boolean down) {
            if (down && !held) {
                lastPress = tick;
            }
        }

        /** {@link #allow} then {@link #applied}: the jump key to hold this tick. */
        boolean press(long tick, boolean held, boolean mayFly, boolean want) {
            boolean down = want && allow(tick, held, mayFly);
            applied(tick, held, down);
            return down;
        }
    }

    /** Debug: the simulated track of {@code keys} then braking, one body per tick. */
    static List<Body> track(BlockGetter level, Body start, Keys keys, int commit, int ticks) {
        List<Body> out = new ArrayList<>();
        Body b = start.copy();
        for (int t = 0; t < ticks; t++) {
            tick(level, b, t < commit ? keys : brake(level, b, keys.yaw));
            out.add(b.copy());
        }
        return Collections.unmodifiableList(out);
    }
}
