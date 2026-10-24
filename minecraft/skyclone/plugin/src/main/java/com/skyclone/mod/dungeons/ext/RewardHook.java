package com.skyclone.mod.dungeons.ext;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * EXTENSION POINT (agent B): end-of-run loot. The framework calls every registered hook once per completed run, on the
 * main thread, AFTER score and XP. Agent B registers the reward-chest system here:
 * <pre>  RewardHook.set(result -> chests.spawn(result));</pre>
 * Until something is set, {@link #DEFAULT} (the module's fallback: the free chest's guaranteed essence) runs.
 * Also: {@link #onFail} hooks run for failed runs (rarely needed).
 */
@FunctionalInterface
public interface RewardHook {

    void onComplete(RunResult result);

    /** Registry. */
    final class Registry {
        private Registry() {}
        static volatile RewardHook hook;
        static RewardHook fallback;
        static final List<Consumer<String>> FAIL = new CopyOnWriteArrayList<>();
    }

    /** Replace the reward system (agent B). Null restores the fallback. */
    static void set(RewardHook h) { Registry.hook = h; }

    /** Set by the framework at enable. */
    static void setFallback(RewardHook h) { Registry.fallback = h; }

    static RewardHook current() { return Registry.hook != null ? Registry.hook : Registry.fallback; }

    static boolean custom() { return Registry.hook != null; }

    /** Called with the run id of a failed run. */
    static void onFail(Consumer<String> c) { Registry.FAIL.add(c); }

    static List<Consumer<String>> failHooks() { return Registry.FAIL; }

    RewardHook DEFAULT = r -> { };
}
