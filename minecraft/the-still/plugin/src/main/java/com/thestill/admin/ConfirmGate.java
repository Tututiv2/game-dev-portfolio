package com.thestill.admin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Click once to arm, click again to fire — and the arm remembers <em>who it was
 * armed against</em>.
 *
 * <h2>The bug this exists to make unwritable</h2>
 * Shadow Prison's version keyed its arm on the admin alone. It computed the right
 * key —
 * <pre>String armKey = admin.getUniqueId() + ":" + actionId + ":" + targetUuid;</pre>
 * — and then never read it, checking {@code confirmArmedUntil.get(admin)} and a
 * separate {@code lastArmedAction} map instead. So arming "Clear Inventory" on one
 * player, backing out, opening somebody else and clicking once <b>wiped the second
 * player's inventory with no confirmation at all</b>. The dangerous half of the
 * safety mechanism was the half that got dropped.
 *
 * <p>Here the arm is a value holding all three of target, action and deadline, and
 * {@link #press} fires only on an exact match. There is no path that checks two of
 * the three, because there is no second map to fall out of step.
 *
 * <p><b>No Bukkit in this class.</b> The clock is a parameter, so the whole thing
 * is testable — see {@code ConfirmGateTest}, which is the only place the "armed on
 * A, fired on B" case can be proven without two accounts and a stopwatch.
 */
public final class ConfirmGate {

    /** How long an arm survives. Long enough to read the tile, short enough to lapse. */
    public static final long WINDOW_MILLIS = 8_000L;

    /** What a press did. */
    public enum Result {
        /** Nothing happened yet — the action is now armed and waiting. */
        ARMED,
        /** The matching second press. The caller does the dangerous thing now. */
        FIRED
    }

    private record Arm(UUID target, String action, long expiresAt) {
    }

    private final Map<UUID, Arm> arms = new ConcurrentHashMap<>();

    /**
     * One press of a destructive button.
     *
     * <p>Fires only if this admin's live arm names the same target <em>and</em> the
     * same action. Anything else — a different player, a different button, a lapsed
     * arm — re-arms instead, which is the safe direction to be wrong in.
     */
    public Result press(UUID admin, UUID target, String action, long now) {
        Arm existing = arms.get(admin);
        if (existing != null
                && existing.expiresAt() > now
                && existing.target().equals(target)
                && existing.action().equals(action)) {
            arms.remove(admin);
            return Result.FIRED;
        }
        arms.put(admin, new Arm(target, action, now + WINDOW_MILLIS));
        return Result.ARMED;
    }

    /**
     * Milliseconds left on this exact arm, or 0 if this button is not the armed one.
     *
     * <p>Used for the tile's lore, so an armed button says so and every other button
     * on the page does not.
     */
    public long remaining(UUID admin, UUID target, String action, long now) {
        Arm existing = arms.get(admin);
        if (existing == null
                || !existing.target().equals(target)
                || !existing.action().equals(action)) {
            return 0L;
        }
        return Math.max(0L, existing.expiresAt() - now);
    }

    /** Drops whatever this admin had armed. Called when they leave the page or log out. */
    public void disarm(UUID admin) {
        arms.remove(admin);
    }

    public int armedCount() {
        return arms.size();
    }
}
