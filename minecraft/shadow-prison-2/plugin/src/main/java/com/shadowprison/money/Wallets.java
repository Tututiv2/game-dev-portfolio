package com.shadowprison.money;

import com.shadowprison.profile.Profile;
import com.shadowprison.profile.ProfileAccess;
import com.shadowprison.store.Database;

import java.sql.PreparedStatement;

/**
 * The only code allowed to change a balance. Every movement is a positive {@code long} with a
 * reason, and lands in the money ledger in the same transaction as the balance.
 *
 * <p>Online players only: their profile is the live copy and the writer applies absolute values
 * in queue order, so the store always matches what the game last showed. (Offline grants go
 * through /claim, never straight into a balance nobody is looking at.)
 */
public final class Wallets {

    private final Database db;

    public Wallets(Database db) {
        this.db = db;
    }

    public long balance(Profile profile, Currency currency) {
        return profile.balance(currency);
    }

    public boolean has(Profile profile, Currency currency, long amount) {
        return amount >= 0 && profile.balance(currency) >= amount;
    }

    /** Adds {@code amount} (> 0). False if non-positive or it would pass {@link Money#MAX_CENTS}. */
    public boolean credit(Profile profile, Currency currency, long amount, String reason) {
        if (amount <= 0) {
            return false;
        }
        long now = profile.balance(currency);
        long next;
        try {
            next = Math.addExact(now, amount);
        } catch (ArithmeticException overflow) {
            return false;
        }
        if (next > Money.MAX_CENTS) {
            return false;
        }
        apply(profile, currency, next, amount, reason);
        return true;
    }

    /** Removes {@code amount} (> 0) if the player has it. False changes nothing. */
    public boolean debit(Profile profile, Currency currency, long amount, String reason) {
        if (amount <= 0 || profile.balance(currency) < amount) {
            return false;
        }
        apply(profile, currency, profile.balance(currency) - amount, -amount, reason);
        return true;
    }

    /** Moves money between two online players in one transaction. */
    public boolean transfer(Profile from, Profile to, Currency currency, long amount, String reason) {
        if (amount <= 0 || from == to || from.balance(currency) < amount) {
            return false;
        }
        long toNext;
        try {
            toNext = Math.addExact(to.balance(currency), amount);
        } catch (ArithmeticException overflow) {
            return false;
        }
        if (toNext > Money.MAX_CENTS) {
            return false;
        }
        long fromNext = from.balance(currency) - amount;
        ProfileAccess.setBalance(from, currency, fromNext);
        ProfileAccess.setBalance(to, currency, toNext);
        String f = from.uuid().toString();
        String t = to.uuid().toString();
        long at = System.currentTimeMillis();
        db.submit("transfer", c -> {
            write(c, f, currency, fromNext, -amount, reason + " -> " + to.name(), at);
            write(c, t, currency, toNext, amount, reason + " <- " + from.name(), at);
        });
        return true;
    }

    private void apply(Profile profile, Currency currency, long next, long delta, String reason) {
        ProfileAccess.setBalance(profile, currency, next);
        String uuid = profile.uuid().toString();
        long at = System.currentTimeMillis();
        db.submit("wallet", c -> write(c, uuid, currency, next, delta, reason, at));
    }

    private static void write(java.sql.Connection c, String uuid, Currency currency, long balance,
                              long delta, String reason, long at) throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO wallets(uuid, currency, amount) VALUES (?, ?, ?)
                ON CONFLICT(uuid, currency) DO UPDATE SET amount = excluded.amount
                """)) {
            ps.setString(1, uuid);
            ps.setString(2, currency.name());
            ps.setLong(3, balance);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO money_ledger(uuid, currency, delta, balance_after, reason, at) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, uuid);
            ps.setString(2, currency.name());
            ps.setLong(3, delta);
            ps.setLong(4, balance);
            ps.setString(5, reason == null ? "unspecified" : reason);
            ps.setLong(6, at);
            ps.executeUpdate();
        }
    }
}
