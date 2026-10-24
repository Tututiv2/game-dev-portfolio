package com.thestill.role;

import com.thestill.player.Profile;
import com.thestill.state.StateKey;

/**
 * Static — the premium currency, and the only thing on this server bought with money.
 *
 * <p>Named from the spec's own vocabulary rather than invented: STATIC EXCHANGE is one
 * of the rig's sealed counters, described as opening "after the climb". So the thing it
 * exchanges already had a name and a locked door waiting for it.
 *
 * <p><b>Static buys nothing that touches the game.</b> Ranks, tags, cases of cosmetics,
 * keys to cosmetics. It cannot buy ore, money, a rung of the ladder, a quieter suit or
 * a colder room — see {@link Perks}, which refuses those at construction. That is the
 * whole reason a premium currency is safe here at all.
 *
 * <p>Stored as a {@link StateKey} on the profile like everything else: no column, no
 * migration, and an admin can already read and set it from the state editor.
 */
public final class Static {

    /** Balance, in whole Static. Not minor units — you never buy half of one. */
    public static final StateKey<Long> BALANCE = StateKey.ofLong("static.balance", 0L);

    /** Lifetime total, so a refund or a support question has something to check against. */
    public static final StateKey<Long> LIFETIME = StateKey.ofLong("static.lifetime", 0L);

    private Static() {
    }

    public static long balanceOf(Profile profile) {
        return profile.state().get(BALANCE);
    }

    public static void give(Profile profile, long amount) {
        if (amount <= 0) {
            return;
        }
        profile.state().set(BALANCE, balanceOf(profile) + amount);
        profile.state().set(LIFETIME, profile.state().get(LIFETIME) + amount);
        profile.markDirty();
    }

    /**
     * Spends, or refuses.
     *
     * <p>Returns false rather than throwing or clamping. Every caller has to handle
     * "you cannot afford this" anyway, and a silent clamp is how somebody ends up with
     * a negative balance nobody can explain.
     */
    public static boolean spend(Profile profile, long amount) {
        if (amount <= 0) {
            return false;
        }
        long balance = balanceOf(profile);
        if (balance < amount) {
            return false;
        }
        profile.state().set(BALANCE, balance - amount);
        profile.markDirty();
        return true;
    }

    /** {@code 1,250 Static} — the form used everywhere it is shown. */
    public static String format(long amount) {
        return String.format("%,d", amount) + " Static";
    }
}
