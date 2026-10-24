package com.thestill.player;

import com.thestill.state.StateMap;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One player's persisted record.
 *
 * <p>Deliberately small. Shadow Prison's equivalent grew to 961 lines and about
 * ninety fields, because every feature added one, and it became a class nobody
 * could change safely. Here, only the handful of things that get queried or
 * sorted are fields; everything else a later phase records — Din totals, Wake
 * level, seal position, prestige — is a key in {@link #state()}, which costs no
 * schema migration and no edit to this file.
 */
public final class Profile {

    private final UUID uuid;
    private final long firstSeen;

    private volatile String name;
    private volatile long lastSeen;
    private volatile long playtimeMillis;

    /**
     * Money in integer minor units, never a double.
     *
     * <p>{@code /bal give <player> NaN} permanently poisoned an account on
     * Shadow Prison: {@code Math.max(0, NaN)} is NaN, {@code NaN < 0} is false,
     * so the guard passed and every later addition inherited it. A long cannot
     * hold NaN, so the entire failure is unstateable rather than merely guarded
     * against.
     */
    private final AtomicLong balance;

    private final StateMap state;
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    /** When this session began, for playtime accounting. Zero when offline. */
    private volatile long sessionStartedAt;

    public Profile(UUID uuid, String name, long firstSeen, long lastSeen,
                   long playtimeMillis, long balance, StateMap state) {
        this.uuid = uuid;
        this.name = name;
        this.firstSeen = firstSeen;
        this.lastSeen = lastSeen;
        this.playtimeMillis = playtimeMillis;
        this.balance = new AtomicLong(balance);
        this.state = state == null ? new StateMap() : state;
    }

    public static Profile fresh(UUID uuid, String name, long now) {
        return new Profile(uuid, name, now, now, 0L, 0L, new StateMap());
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        if (!name.equals(this.name)) {
            this.name = name;
            markDirty();
        }
    }

    public long firstSeen() {
        return firstSeen;
    }

    public long lastSeen() {
        return lastSeen;
    }

    public void setLastSeen(long lastSeen) {
        this.lastSeen = lastSeen;
        markDirty();
    }

    /** Stored playtime plus the current session, if online. */
    public long playtimeMillis() {
        long session = sessionStartedAt == 0L ? 0L : System.currentTimeMillis() - sessionStartedAt;
        return playtimeMillis + Math.max(0L, session);
    }

    void beginSession(long now) {
        this.sessionStartedAt = now;
    }

    /** Folds the current session into stored playtime and ends it. */
    void endSession(long now) {
        if (sessionStartedAt != 0L) {
            playtimeMillis += Math.max(0L, now - sessionStartedAt);
            sessionStartedAt = 0L;
            markDirty();
        }
    }

    /**
     * Overwrites the <em>stored</em> playtime. Admin edit only.
     *
     * <p>Note what this does not touch: a live session. Somebody online has a
     * session running on top of this number, so setting it to two hours while they
     * are playing means {@link #playtimeMillis()} reads two hours plus however long
     * they have been on. That is the honest behaviour — the alternative is
     * retroactively editing when they logged in — and {@code /adminset} says so on
     * the tile.
     */
    public void setPlaytimeMillis(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("negative playtime: " + millis);
        }
        this.playtimeMillis = millis;
        markDirty();
    }

    // ---- money ----------------------------------------------------------

    public long balance() {
        return balance.get();
    }

    /**
     * Adds money.
     *
     * @throws IllegalArgumentException on a negative amount — taking money is
     *         {@link #withdraw}, and letting a "deposit" subtract is how a sign
     *         error becomes an economy exploit
     */
    public long deposit(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("deposit of a negative amount: " + amount);
        }
        long updated = balance.updateAndGet(current -> {
            try {
                return Math.addExact(current, amount);
            } catch (ArithmeticException overflow) {
                return Long.MAX_VALUE; // saturate rather than wrap into a negative
            }
        });
        markDirty();
        return updated;
    }

    /**
     * Removes money if there is enough.
     *
     * @return true if the withdrawal happened; false leaves the balance untouched
     */
    public boolean withdraw(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("withdrawal of a negative amount: " + amount);
        }
        boolean[] taken = {false};
        balance.updateAndGet(current -> {
            if (current < amount) {
                taken[0] = false;
                return current;
            }
            taken[0] = true;
            return current - amount;
        });
        if (taken[0]) {
            markDirty();
        }
        return taken[0];
    }

    public void setBalance(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("negative balance: " + amount);
        }
        balance.set(amount);
        markDirty();
    }

    // ---- open-ended state ----------------------------------------------

    public StateMap state() {
        return state;
    }

    // ---- dirty tracking -------------------------------------------------

    public void markDirty() {
        dirty.set(true);
    }

    /** True if the profile's own columns changed. State dirtiness is tracked separately. */
    public boolean isDirty() {
        return dirty.get() || state.isDirty();
    }

    /** Clears the flag before the caller snapshots, so a racing write is not lost. */
    boolean takeDirty() {
        return dirty.getAndSet(false);
    }

    long storedPlaytimeMillis() {
        return playtimeMillis;
    }

    @Override
    public String toString() {
        return "Profile[" + name + " " + uuid + " bal=" + balance.get() + "]";
    }
}
