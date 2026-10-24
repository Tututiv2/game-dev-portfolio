package com.secondwatch.game;

import com.secondwatch.SecondWatch;
import com.secondwatch.content.SecondWatchContent;
import com.secondwatch.core.Rng;
import com.secondwatch.core.SpawnPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Puts the bestiary into the world, slowly, and never where you are looking.
 *
 * <p>Two rules do all the work here, and both are about restraint rather than
 * about spawning:
 *
 * <p><b>Never in view.</b> Nothing may appear where a player can see it appear.
 * A thing that pops into existence is a video game; a thing that is simply
 * <em>there</em> when you turn around has always been there as far as the
 * player's memory is concerned, and they will insist it was.
 *
 * <p><b>Never close.</b> They arrive at the edge of vision and walk in. The
 * approach is the whole product - a Follower that spawns at forty blocks and
 * takes two minutes to become undeniable is worth ten that spawn at ten.
 */
public final class Bestiary {

    /** How often the world is allowed to gain something. */
    private static final long SPAWN_INTERVAL_MS = 45_000L;

    private static final double MIN_DISTANCE = 28.0;
    private static final double MAX_DISTANCE = 52.0;

    /** How far up and down to look for standable ground at the chosen spot. */
    private static final int VERTICAL_SEARCH = 16;

    private long lastSpawnMs = -1;
    private final Rng rng;

    public Bestiary(long seed) {
        this.rng = new Rng(seed).fork("bestiary");
    }

    public void tick(MinecraftServer server, RunState run) {
        long now = System.currentTimeMillis();
        if (lastSpawnMs < 0) {
            lastSpawnMs = now;
            return;
        }
        if (now - lastSpawnMs < SPAWN_INTERVAL_MS) return;
        lastSpawnMs = now;

        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return;

        Map<SpawnPlan.Kind, Integer> alive = census(server);
        List<SpawnPlan.Kind> wanted = SpawnPlan.next(run.dread().tier(), alive, rng);
        if (wanted.isEmpty()) return;

        ServerPlayer near = players.get(rng.nextInt(players.size()));
        SpawnPlan.Kind kind = wanted.get(0);

        BlockPos spot = findSpot(near);
        if (spot == null) return;

        EntityType<? extends Mob> type = typeOf(kind);
        ServerLevel level = near.level();
        Mob mob = type.create(level, EntitySpawnReason.EVENT);
        if (mob == null) return;

        // snapTo, not moveTo - Entity.moveTo was renamed in 26.x.
        mob.snapTo(spot.getX() + 0.5, (double) spot.getY(), spot.getZ() + 0.5,
                (float) rng.nextInt(360), 0.0f);
        // These are the run's cast, not wildlife. They must not evaporate the
        // moment both players walk to the other end of the mine.
        mob.setPersistenceRequired();
        level.addFreshEntity(mob);

        SecondWatch.LOG.info("the world gained a {} ({} blocks from {})",
                kind, (int) Math.sqrt(near.distanceToSqr(
                        spot.getX(), spot.getY(), spot.getZ())), near.getName().getString());
    }

    private Map<SpawnPlan.Kind, Integer> census(MinecraftServer server) {
        Map<SpawnPlan.Kind, Integer> counts = new EnumMap<>(SpawnPlan.Kind.class);
        for (SpawnPlan.Kind k : SpawnPlan.Kind.values()) counts.put(k, 0);

        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                SpawnPlan.Kind kind = kindOf(entity.getType());
                if (kind != null) counts.merge(kind, 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * A standable spot at the right distance that the player cannot see.
     *
     * <p>Gives up rather than compromising. A failed attempt costs one interval;
     * a spawn in view costs the illusion for the rest of the run.
     */
    private BlockPos findSpot(ServerPlayer player) {
        ServerLevel level = player.level();

        for (int attempt = 0; attempt < 24; attempt++) {
            double angle = rng.nextDouble() * Math.PI * 2;
            double distance = MIN_DISTANCE + rng.nextDouble() * (MAX_DISTANCE - MIN_DISTANCE);

            int x = (int) Math.round(player.getX() + Math.cos(angle) * distance);
            int z = (int) Math.round(player.getZ() + Math.sin(angle) * distance);

            for (int dy = VERTICAL_SEARCH; dy >= -VERTICAL_SEARCH; dy--) {
                BlockPos ground = BlockPos.containing(x, player.getY() + dy, z);
                if (!level.isLoaded(ground)) continue;

                BlockPos feet = ground.above();
                boolean standable = !level.getBlockState(ground).isAir()
                        && level.getBlockState(feet).isAir()
                        && level.getBlockState(feet.above()).isAir();
                if (!standable) continue;

                if (canBeSeen(player, feet)) continue;
                return feet;
            }
        }
        return null;
    }

    /**
     * Would this spot be visible to the player right now?
     *
     * <p>Errs heavily toward "yes". A spawn the player half-noticed is worse
     * than no spawn at all, so anything in the forward arc is rejected whether
     * or not a wall happens to be in the way.
     */
    private static boolean canBeSeen(ServerPlayer player, BlockPos pos) {
        double dx = (pos.getX() + 0.5) - player.getX();
        double dz = (pos.getZ() + 0.5) - player.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0e-4) return true;

        var look = player.getLookAngle();
        double dot = (look.x * dx + look.z * dz) / length;

        // cos(75 degrees) - a much wider arc than the actual field of view.
        return dot > 0.26;
    }

    private static EntityType<? extends Mob> typeOf(SpawnPlan.Kind kind) {
        return switch (kind) {
            case FOLLOWER -> SecondWatchContent.FOLLOWER;
            case WATCHER -> SecondWatchContent.WATCHER;
            case MIMIC -> SecondWatchContent.MIMIC;
            case SWARM -> SecondWatchContent.SWARM;
            case CRAWLER -> SecondWatchContent.CRAWLER;
            case TALL_ONE -> SecondWatchContent.TALL_ONE;
        };
    }

    private static SpawnPlan.Kind kindOf(EntityType<?> type) {
        if (type == SecondWatchContent.FOLLOWER) return SpawnPlan.Kind.FOLLOWER;
        if (type == SecondWatchContent.WATCHER) return SpawnPlan.Kind.WATCHER;
        if (type == SecondWatchContent.MIMIC) return SpawnPlan.Kind.MIMIC;
        if (type == SecondWatchContent.SWARM) return SpawnPlan.Kind.SWARM;
        if (type == SecondWatchContent.CRAWLER) return SpawnPlan.Kind.CRAWLER;
        if (type == SecondWatchContent.TALL_ONE) return SpawnPlan.Kind.TALL_ONE;
        return null;
    }
}
