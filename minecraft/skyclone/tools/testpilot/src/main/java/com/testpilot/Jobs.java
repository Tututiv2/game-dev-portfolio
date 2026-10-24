package com.testpilot;

import com.google.gson.JsonObject;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Actions that span ticks (move, walkto, attackhold, delayed attack/use, wait).
 *
 * <p>Everything in here runs on the client thread. A job is ticked once per
 * client tick (END_CLIENT_TICK) until it returns a result. Held keys are
 * re-asserted at the START of every tick, before the player's input is read,
 * so they survive anything that resets KeyMapping state - except an open
 * screen: vanilla releases every key when a GUI opens and a pilot that kept
 * walking with a chest open would be doing something no player can.
 */
public final class Jobs {

    /** One tick of a job. Return the reply to finish, null to keep going. */
    public interface Job {
        JsonObject tick(Minecraft mc, int tick);

        /** Called when the job is cancelled or fails; release what it holds. */
        default void cleanup(Minecraft mc) {
            releaseKeys();
        }

        /** Movement/attack-hold jobs are exclusive: a new one cancels the old one. */
        default boolean exclusive() {
            return false;
        }
    }

    private static final class Running {
        final Job job;
        final CompletableFuture<JsonObject> future;
        int tick;

        Running(Job job, CompletableFuture<JsonObject> future) {
            this.job = job;
            this.future = future;
        }
    }

    private static final List<Running> RUNNING = new ArrayList<>();
    private static final Map<KeyMapping, Boolean> HELD = new LinkedHashMap<>();
    private static long clientTicks;

    private Jobs() {
    }

    public static long clientTicks() {
        return clientTicks;
    }

    /** Queue a job; the future completes on the client thread. */
    public static CompletableFuture<JsonObject> submit(Job job) {
        CompletableFuture<JsonObject> f = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            if (job.exclusive()) {
                cancelExclusive("superseded by a new movement/attack job");
            }
            RUNNING.add(new Running(job, f));
        });
        return f;
    }

    public static void cancelExclusive(String why) {
        Minecraft mc = Minecraft.getInstance();
        Iterator<Running> it = RUNNING.iterator();
        while (it.hasNext()) {
            Running r = it.next();
            if (r.job.exclusive()) {
                it.remove();
                r.job.cleanup(mc);
                JsonObject o = PilotServer.error("cancelled: " + why);
                o.addProperty("cancelled", true);
                r.future.complete(o);
            }
        }
        releaseKeys();
    }

    static void startTick(Minecraft mc) {
        if (mc.screen == null) {
            for (Map.Entry<KeyMapping, Boolean> e : HELD.entrySet()) {
                setKey(e.getKey(), e.getValue());
            }
        }
    }

    static void endTick(Minecraft mc) {
        clientTicks++;
        if (RUNNING.isEmpty()) {
            return;
        }
        List<Running> snapshot = new ArrayList<>(RUNNING);
        for (Running r : snapshot) {
            if (!RUNNING.contains(r)) {
                continue;
            }
            JsonObject result;
            try {
                if (mc.player == null || mc.level == null) {
                    throw new PilotException(409, "not in a world");
                }
                result = r.job.tick(mc, r.tick++);
            } catch (Throwable t) {
                RUNNING.remove(r);
                r.job.cleanup(mc);
                r.future.completeExceptionally(t);
                continue;
            }
            if (result != null) {
                RUNNING.remove(r);
                r.future.complete(result);
            }
        }
    }

    // ------------------------------------------------------------------ keys

    /** Hold (or release) a key from inside the client and keep it that way. */
    public static void hold(KeyMapping key, boolean down) {
        if (down) {
            HELD.put(key, true);
        } else {
            HELD.remove(key);
        }
        setKey(key, down);
    }

    public static void releaseKeys() {
        for (KeyMapping k : new ArrayList<>(HELD.keySet())) {
            setKey(k, false);
        }
        HELD.clear();
    }

    /**
     * Set a KeyMapping's state, correct for toggle mappings too (toggle sprint
     * and toggle crouch flip on every setDown(true) and ignore setDown(false)).
     */
    public static void setKey(KeyMapping k, boolean down) {
        if (k.isDown() == down) {
            return;
        }
        k.setDown(down);
        if (k.isDown() != down) {
            k.setDown(true);
        }
    }
}
