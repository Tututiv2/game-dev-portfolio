package com.secondwatch.game;

import com.secondwatch.core.PlayerState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns live players into the flat {@link PlayerState} the core reasons about.
 *
 * <p>The interesting judgement here is {@code seesPartner}, because the whole
 * illusion depends on it: showing somebody a second copy of a friend they are
 * looking straight at is not a scare, it is a bug report. So it has to be
 * generous - err toward "they can see them" and lose a phantom, rather than err
 * the other way and burn the mod's credibility in one moment.
 */
public final class Snapshots {

    /** Past this, you cannot tell a person from a shape, so it does not count. */
    private static final double SIGHT_RANGE = 48.0;

    /**
     * Roughly a 100-degree cone. Wider than Minecraft's default FOV on purpose,
     * because peripheral vision counts and because being generous here is the
     * cheap side of the trade.
     */
    private static final double COS_HALF_FOV = Math.cos(Math.toRadians(50));

    private Snapshots() {
    }

    public static List<PlayerState> of(MinecraftServer server, RunState run) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        List<PlayerState> out = new ArrayList<>(players.size());

        for (ServerPlayer p : players) {
            ServerPlayer partner = partnerOf(players, p);
            out.add(new PlayerState(
                    p.getName().getString(),
                    p.getX(),
                    p.getZ(),
                    p.level().getBrightness(LightLayer.BLOCK, p.blockPosition()),
                    run.corpus().lastSpokeMs(p.getName().getString()),
                    partner != null && canSee(p, partner),
                    p.isAlive()));
        }
        return out;
    }

    public static ServerPlayer byName(MinecraftServer server, String name) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.getName().getString().equals(name)) return p;
        }
        return null;
    }

    private static ServerPlayer partnerOf(List<ServerPlayer> players, ServerPlayer p) {
        for (ServerPlayer q : players) {
            if (q != p) return q;
        }
        return null;
    }

    /**
     * Can {@code viewer} currently see {@code other}?
     *
     * <p>Three gates, cheapest first: close enough, in front of them, and not
     * behind a wall. All three have to pass, and any doubt resolves as yes.
     */
    private static boolean canSee(ServerPlayer viewer, ServerPlayer other) {
        if (viewer.level() != other.level()) return false;

        double distSq = viewer.distanceToSqr(other);
        if (distSq > SIGHT_RANGE * SIGHT_RANGE) return false;

        Vec3 toOther = other.position().subtract(viewer.position());
        if (toOther.lengthSqr() < 1.0e-4) return true;

        Vec3 look = viewer.getLookAngle();
        if (look.dot(toOther.normalize()) < COS_HALF_FOV) return false;

        return viewer.hasLineOfSight(other);
    }
}
