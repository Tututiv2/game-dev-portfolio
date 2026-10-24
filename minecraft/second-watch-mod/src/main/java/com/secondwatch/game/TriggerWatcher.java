package com.secondwatch.game;

import com.secondwatch.core.TriggerModel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Watches what the players are doing and charges them for it.
 *
 * <p>This is what turns the rolled {@link com.secondwatch.core.Trigger} from
 * flavour text in a dead stranger's journal into instructions. Before this
 * existed the night escalated on a timer regardless; now the journal entry
 * saying <i>do not light it, it walks to the light every single time</i> is
 * something a player can obey, and obeying it is worth about a chapter.
 *
 * <p>Sampled once a second rather than per tick. The behaviours being measured -
 * talking, standing still, being apart - all happen on human timescales, and
 * sampling twenty times a second would just be twenty times the arithmetic for
 * the same answer.
 */
public final class TriggerWatcher {

    private static final long SAMPLE_MS = 1000L;

    private long lastSampleMs = -1;
    private final Map<UUID, Vec3> lastPositions = new HashMap<>();
    private final Map<UUID, Integer> blocksBroken = new HashMap<>();

    /** Called from the block-break event; counted until the next sample. */
    public void recordBlockBroken(ServerPlayer player) {
        blocksBroken.merge(player.getUUID(), 1, Integer::sum);
    }

    public void tick(MinecraftServer server, RunState run) {
        long now = System.currentTimeMillis();
        if (lastSampleMs < 0) {
            lastSampleMs = now;
            return;
        }
        if (now - lastSampleMs < SAMPLE_MS) return;

        double seconds = (now - lastSampleMs) / 1000.0;
        lastSampleMs = now;

        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return;

        for (ServerPlayer player : players) {
            if (!player.isAlive()) continue;

            Vec3 position = player.position();
            Vec3 previous = lastPositions.put(player.getUUID(), position);
            double speed = previous == null ? 1.0 : position.distanceTo(previous) / seconds;

            double partnerDistance = Double.MAX_VALUE;
            for (ServerPlayer other : players) {
                if (other == player) continue;
                partnerDistance = Math.min(partnerDistance, player.distanceTo(other));
            }
            if (partnerDistance == Double.MAX_VALUE) {
                // Alone on the server. Do not charge them for a separation they
                // have no way to fix.
                partnerDistance = 0;
            }

            int broken = blocksBroken.getOrDefault(player.getUUID(), 0);
            blocksBroken.put(player.getUUID(), 0);

            String name = player.getName().getString();
            long lastSpoke = run.corpus().lastSpokeMs(name);
            boolean speaking = lastSpoke >= 0 && (run.runNowMs() - lastSpoke) < 2_000;

            TriggerModel.Conditions conditions = new TriggerModel.Conditions(
                    speaking,
                    player.level().getBrightness(LightLayer.BLOCK, player.blockPosition()),
                    speed,
                    partnerDistance,
                    broken);

            double amount = TriggerModel.feed(run.rules().trigger(), conditions, seconds);
            if (amount > 0) run.dread().feed(amount);
        }
    }

    public void forget(ServerPlayer player) {
        lastPositions.remove(player.getUUID());
        blocksBroken.remove(player.getUUID());
    }

    public void clear() {
        lastPositions.clear();
        blocksBroken.clear();
        lastSampleMs = -1;
    }
}
