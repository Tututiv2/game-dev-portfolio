package com.thestill.economy;

import com.thestill.player.Profile;
import com.thestill.player.Profiles;

import java.util.logging.Logger;

/**
 * Money movement, and the one place that decides money is durable.
 *
 * <p>Every mutation here writes through {@link Profiles#saveDurable} rather than
 * waiting for the write-behind timer. Playtime and counters can afford to be
 * twenty seconds stale after a crash; a balance cannot, because the player
 * remembers what they sold.
 */
public final class Economy {

    private final Logger log;
    private final Profiles profiles;

    public Economy(Logger log, Profiles profiles) {
        this.log = log;
        this.profiles = profiles;
    }

    public long balanceOf(Profile profile) {
        return profile.balance();
    }

    /** Adds money and commits it. */
    public long deposit(Profile profile, long minorUnits, String reason) {
        if (minorUnits <= 0) {
            return profile.balance();
        }
        long updated = profile.deposit(minorUnits);
        profiles.saveDurable(profile);
        log.fine(() -> profile.name() + " +" + Money.format(minorUnits) + " (" + reason + ")");
        return updated;
    }

    /**
     * Takes money if there is enough, and commits it.
     *
     * @return true if the player could afford it; false leaves the balance alone
     */
    public boolean withdraw(Profile profile, long minorUnits, String reason) {
        if (minorUnits <= 0) {
            return true;
        }
        if (!profile.withdraw(minorUnits)) {
            return false;
        }
        profiles.saveDurable(profile);
        log.fine(() -> profile.name() + " -" + Money.format(minorUnits) + " (" + reason + ")");
        return true;
    }

    public void set(Profile profile, long minorUnits, String reason) {
        profile.setBalance(Math.max(0L, minorUnits));
        profiles.saveDurable(profile);
        log.info(profile.name() + " balance set to " + Money.format(profile.balance())
                + " (" + reason + ")");
    }

    public boolean canAfford(Profile profile, long minorUnits) {
        return profile.balance() >= minorUnits;
    }
}
