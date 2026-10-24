package com.secondwatch;

import com.secondwatch.content.SecondWatchContent;
import com.secondwatch.game.Bestiary;
import com.secondwatch.game.Departure;
import com.secondwatch.game.NightKeeper;
import com.secondwatch.game.PhantomManager;
import com.secondwatch.game.RunState;
import com.secondwatch.game.Runs;
import com.secondwatch.game.SecondWatchCommands;
import com.secondwatch.game.TriggerWatcher;
import com.secondwatch.game.Voice;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side entrypoint.
 *
 * <p>Architecture reminder, because it is the decision the whole mod rests on:
 * there is exactly one real world and the server owns it. Everything the mod
 * fabricates for a player - phantoms, replayed voice, wrong blocks - is sent to
 * that player's client as a render-only instruction and never becomes world
 * state. If a fabrication can deal damage, move a block or touch an inventory,
 * it is in the wrong layer and it is a bug.
 */
public class SecondWatch implements ModInitializer {

    public static final String MOD_ID = "secondwatch";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    /** How often the chapter clock and the sky are reconciled. */
    private static final int TICK_INTERVAL = 20;

    private int tickCounter;

    /** Runs the split. Ticked every tick, because a phantom that stutters is caught. */
    public static final PhantomManager PHANTOMS = new PhantomManager();

    /** Charges the players for whatever this run's trigger is. */
    public static final TriggerWatcher TRIGGERS = new TriggerWatcher();

    /** Puts the cast into the world, slowly, and never in view. */
    private static Bestiary bestiary;

    /** Runs the twenty seconds that end a run. */
    public static final Departure DEPARTURE = new Departure();

    @Override
    public void onInitialize() {
        LOG.info("Second Watch loading. Nobody came back.");
        SecondWatchContent.register();
        if (!Voice.present()) {
            LOG.warn("Simple Voice Chat is not installed. The scenario will run, but the "
                    + "phantom will be mute, which removes most of what makes it work.");
        }

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            RunState run = Runs.loadFor(server);
            LOG.info("run seed {} - night {}/{} - tell {} - trigger {}",
                    run.seed(), run.night(), run.rules().nights(),
                    run.rules().tell(), run.rules().trigger());
            // A world reloaded mid-run must come back to the sky it left, before
            // anybody has a chance to see a sunrise that should not exist.
            NightKeeper.enforce(server, run);
            bestiary = new Bestiary(run.seed());
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            PHANTOMS.clear();
            TRIGGERS.clear();
            bestiary = null;
            if (Runs.active()) Runs.get().save(server);
            Runs.clear();
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!Runs.active()) return;

            // Every tick: the phantom has to move like a person, and a person
            // does not move at 1 Hz.
            if (Runs.get().started()) {
                Voice.tick();
                PHANTOMS.tick(server, Runs.get());
                TRIGGERS.tick(server, Runs.get());
                DEPARTURE.tick(server, Runs.get());
                if (bestiary != null) bestiary.tick(server, Runs.get());
            }

            if (++tickCounter < TICK_INTERVAL) return;
            tickCounter = 0;

            RunState run = Runs.get();
            if (!run.started()) return;

            if (run.tickChapter(System.currentTimeMillis())) {
                LOG.info("night {} - {}", run.night(), run.dread().tier());
                run.save(server);
            }
            NightKeeper.enforce(server, run);
        });

        // Digging is one of the six things a run can be watching for.
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, entity) -> {
            if (Runs.active() && Runs.get().started()
                    && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                TRIGGERS.recordBlockBroken(serverPlayer);
            }
        });

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> SecondWatchCommands.register(dispatcher));
    }
}
