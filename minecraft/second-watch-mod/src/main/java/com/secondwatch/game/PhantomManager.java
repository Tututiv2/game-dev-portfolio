package com.secondwatch.game;

import com.secondwatch.SecondWatch;
import com.secondwatch.core.PhantomPlan;
import com.secondwatch.core.PlayerState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs the split.
 *
 * <p>Every rule enforced here exists to keep the phantom BORING, because that is
 * the only thing that makes it work. It walks at a person's pace, it goes
 * somewhere plausible, and it does not react to you. A phantom that behaves
 * strangely is caught in four seconds and the illusion is spent for the rest of
 * the run; a phantom that behaves like a bored friend heading to the next ore
 * vein gets followed for two minutes, and the horror is not the moment it
 * appears - it is the ordinary conversation that keeps happening afterwards.
 */
public final class PhantomManager {

    /** Player walking pace, in blocks per tick. Not a fraction faster. */
    private static final double WALK_PER_TICK = 4.3 / 20.0;

    /** It gives up and stops having been there after this long. */
    private static final long LIFETIME_MS = 75_000L;

    /** How far ahead of the victim it first appears. */
    private static final double APPEAR_AHEAD = 14.0;

    private final Map<UUID, Active> active = new HashMap<>();

    /**
     * Puppets spawned by the test command. Kept apart from real ones because a
     * test puppet wears YOUR face, which every rule in the core forbids - it
     * exists so the body, the skin and the walk can be looked at by one person
     * without needing a second player and a five-night run.
     */
    private final List<TestPuppet> tests = new ArrayList<>();

    private record TestPuppet(PhantomPuppet puppet, long bornAtMs) {
    }

    private record Active(PhantomPuppet puppet, PhantomPlan plan, long bornAtMs) {
    }

    public void tick(MinecraftServer server, RunState run) {
        long now = System.currentTimeMillis();

        walkExisting(server, run, now);
        expireTests(now);

        List<PlayerState> states = Snapshots.of(server, run);
        // The core throws below two live players on purpose - it is the thesis
        // compiled - so the caller has to know not to ask. This is that caller.
        if (states.size() < 2) return;
        if (states.stream().filter(PlayerState::alive).count() < 2) return;

        Optional<PhantomPlan> plan =
                run.director().consider(states, run.dread(), run.runNowMs());
        if (plan.isEmpty()) return;

        spawn(server, plan.get(), now);
    }

    private void spawn(MinecraftServer server, PhantomPlan plan, long now) {
        ServerPlayer victim = Snapshots.byName(server, plan.victimId());
        ServerPlayer wearing = Snapshots.byName(server, plan.wearingId());
        if (victim == null || wearing == null) return;
        if (active.containsKey(victim.getUUID())) return;

        // It appears ahead of them, in the direction they are already walking,
        // at the distance a person naturally drifts to. Appearing behind is a
        // jumpscare, and a jumpscare would tell them immediately that it is not
        // their friend.
        Vec3 look = victim.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        if (flat.lengthSqr() < 1.0e-6) flat = new Vec3(1, 0, 0);
        flat = flat.normalize();

        double px = victim.getX() + flat.x * APPEAR_AHEAD;
        double pz = victim.getZ() + flat.z * APPEAR_AHEAD;
        float yaw = victim.getYRot();

        PhantomPuppet puppet = PhantomPuppet.spawn(
                victim, wearing.getGameProfile(), px, victim.getY(), pz, yaw);
        active.put(victim.getUUID(), new Active(puppet, plan, now));

        // The line comes from where the body is, so a player who turns toward
        // the voice is turning toward the thing wearing their friend.
        plan.utterance().ifPresent(line ->
                Voice.speak(victim, plan, line, px, victim.getY() + 1.5, pz));

        SecondWatch.LOG.info("split: {} is being shown {}{}",
                plan.victimId(), plan.wearingId(), plan.luring() ? " (luring)" : "");
    }

    private void walkExisting(MinecraftServer server, RunState run, long now) {
        List<UUID> finished = new ArrayList<>();

        for (Map.Entry<UUID, Active> entry : active.entrySet()) {
            Active a = entry.getValue();
            PhantomPuppet puppet = a.puppet();

            if (!puppet.viewerStillOnline() || now - a.bornAtMs() > LIFETIME_MS) {
                puppet.despawn();
                finished.add(entry.getKey());
                continue;
            }

            ServerPlayer victim = puppet.viewer();

            // The moment the real person is visible, the copy has to be gone.
            // Two of your friend on screen at once is not twice as frightening,
            // it is a bug, and the players will correctly read it as one.
            ServerPlayer partner = Snapshots.byName(server, a.plan().wearingId());
            if (partner != null && bothVisible(victim, partner, puppet)) {
                puppet.despawn();
                finished.add(entry.getKey());
                continue;
            }

            if (!a.plan().luring()) continue;

            double dx = a.plan().headingX() - puppet.x();
            double dz = a.plan().headingZ() - puppet.z();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 0.5) continue;

            double step = Math.min(WALK_PER_TICK, dist);
            double nx = puppet.x() + (dx / dist) * step;
            double nz = puppet.z() + (dz / dist) * step;
            float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));

            puppet.moveTo(nx, victim.getY(), nz, yaw);
        }

        for (UUID id : finished) active.remove(id);
    }

    /** True when the victim can see the real partner and the fake one together. */
    private static boolean bothVisible(ServerPlayer victim, ServerPlayer partner, PhantomPuppet puppet) {
        if (victim.level() != partner.level()) return false;
        if (victim.distanceToSqr(partner) > 48 * 48) return false;
        return victim.hasLineOfSight(partner);
    }

    /**
     * Show a player a copy of themselves, straight ahead. Test scaffolding for
     * checking that the body renders, wears the right skin and stands right.
     */
    public void spawnTest(ServerPlayer viewer) {
        spawnTest(viewer, viewer.getGameProfile());
    }

    /** Show a player a copy of somebody else, using a looked-up skin. */
    public void spawnTest(ServerPlayer viewer, com.mojang.authlib.GameProfile wearing) {
        Vec3 look = viewer.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        if (flat.lengthSqr() < 1.0e-6) flat = new Vec3(1, 0, 0);
        flat = flat.normalize();

        PhantomPuppet puppet = PhantomPuppet.spawn(
                viewer, wearing,
                viewer.getX() + flat.x * 6.0,
                viewer.getY(),
                viewer.getZ() + flat.z * 6.0,
                viewer.getYRot() + 180f);
        tests.add(new TestPuppet(puppet, System.currentTimeMillis()));
    }

    private void expireTests(long now) {
        tests.removeIf(t -> {
            if (!t.puppet().viewerStillOnline() || now - t.bornAtMs() > 60_000L) {
                t.puppet().despawn();
                return true;
            }
            return false;
        });
    }

    /** Clear everything, e.g. on shutdown. */
    public void clear() {
        for (Active a : active.values()) a.puppet().despawn();
        active.clear();
        for (TestPuppet t : tests) t.puppet().despawn();
        tests.clear();
    }

    public int activeCount() {
        return active.size();
    }
}
