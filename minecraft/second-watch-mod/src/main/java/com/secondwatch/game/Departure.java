package com.secondwatch.game;

import com.secondwatch.SecondWatch;
import com.secondwatch.core.TheSeat;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.GameType;

/**
 * Runs the twenty seconds that end a run.
 *
 * <p>Almost all of this is theatre, and the theatre is the mechanic. Both
 * players get the countdown, so the one who is staying watches the number go
 * down knowing exactly what it means. There is no confirmation dialog and no way
 * to hurry it. Whatever they say to each other during those twenty seconds is
 * the ending of the recording, and the mod's only job is to get out of the way
 * and let it happen.
 *
 * <p>The one hard rule enforced here: an interrupted departure does not spend
 * the seat. Somebody who dies or logs out mid-countdown has not used it, because
 * a run made unwinnable by bad luck is a run the players stop taking seriously.
 */
public final class Departure {

    private int lastAnnouncedSecond = -1;

    public void tick(MinecraftServer server, RunState run) {
        TheSeat seat = run.seat();
        if (!seat.inProgress()) {
            lastAnnouncedSecond = -1;
            return;
        }

        String name = seat.occupant().orElse(null);
        if (name == null) return;

        ServerPlayer occupant = Snapshots.byName(server, name);
        if (occupant == null || !occupant.isAlive()) {
            // Died or disconnected in it. The seat stays usable.
            seat.interrupt();
            server.getPlayerList().broadcastSystemMessage(
                    Component.literal("§8The seat is empty again."), false);
            return;
        }

        long now = System.currentTimeMillis();
        int remaining = (int) Math.ceil(
                (TheSeat.DEPARTURE_MS * (1.0 - seat.progress(now))) / 1000.0);

        if (remaining != lastAnnouncedSecond && remaining > 0) {
            lastAnnouncedSecond = remaining;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.sendOverlayMessage(Component.literal("§f" + remaining));
            }
            // A tone per second, so it is audible from wherever the other player
            // has decided to be standing.
            occupant.level().playSound(null, occupant.blockPosition(),
                    SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS,
                    1.0f, 0.5f + (20 - remaining) * 0.05f);
        }

        seat.tick(now).ifPresent(who -> finish(server, run, who));
    }

    private void finish(MinecraftServer server, RunState run, String who) {
        ServerPlayer leaver = Snapshots.byName(server, who);
        if (leaver != null) {
            // Spectator rather than a teleport: they are out of the run, they
            // can still watch, and they cannot help. That is the point of the
            // ending and it should be uncomfortable.
            leaver.setGameMode(GameType.SPECTATOR);
            leaver.sendSystemMessage(Component.literal("§7You got out."));
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getName().getString().equals(who)) continue;
            player.sendSystemMessage(Component.literal("§8" + who + " got out."));
            player.sendSystemMessage(Component.literal("§8You did not."));
        }

        run.markEnded();
        run.save(server);
        SecondWatch.LOG.info("{} took the seat. the run is over.", who);
    }
}
