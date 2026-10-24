package com.skyclone.mod.enchanting;

/**
 * Experimentation Table charges as pure functions (unit tested). Wiki Experiments (checked 2026-10-06, audit G12):
 * the table holds up to three charges, every Superpairs game costs one, and one charge comes back every 24 hours
 * while the bank is not full. Once they are spent, up to three extra games a day can be bought with XP levels and
 * bits ({@link ExperimentSpec#extraRuns}); a bought game is simply one more charge.
 */
final class ExperimentCharges {

    private ExperimentCharges() {}

    /** Bring {@code d} up to {@code now}: first-time fill, charges earned since {@code chargeAt}, a lowered maximum. */
    static void refresh(PlayerData d, long now, int max, long periodMs) {
        max = Math.max(1, max);
        periodMs = Math.max(1, periodMs);
        if (d.charges < 0) { d.charges = max; d.chargeAt = 0; }
        if (d.charges >= max) { d.charges = Math.min(d.charges, max); d.chargeAt = 0; return; }
        if (d.chargeAt <= 0) d.chargeAt = now + periodMs;   // a missing clock never strands a player
        while (d.charges < max && now >= d.chargeAt) {
            d.charges++;
            d.chargeAt += periodMs;
        }
        if (d.charges >= max) d.chargeAt = 0;
    }

    /** Use one charge; false (nothing changed) when there is none. Starts the refill clock if the bank was full. */
    static boolean spend(PlayerData d, long now, int max, long periodMs) {
        refresh(d, now, max, periodMs);
        if (d.charges <= 0) return false;
        boolean wasFull = d.charges >= Math.max(1, max);
        d.charges--;
        if (wasFull || d.chargeAt <= 0) d.chargeAt = now + Math.max(1, periodMs);
        return true;
    }

    /** The extra game the player would buy next, or null when charges remain, the day's limit is reached, or none exist. */
    static ExperimentSpec.ExtraRun nextExtra(PlayerData d, ExperimentSpec spec, int perDay) {
        if (d.charges > 0 || d.extraRunsToday >= Math.max(0, perDay)) return null;
        return spec.extraRun(d.extraRunsToday);
    }

    /** Record a bought extra game: one more charge, and the day's counter. */
    static void bought(PlayerData d) {
        d.charges = Math.max(0, d.charges) + 1;
        d.extraRunsToday++;
    }

    /** Milliseconds until the next charge, 0 when the bank is full. */
    static long untilNext(PlayerData d, long now) { return d.chargeAt <= 0 ? 0 : Math.max(0, d.chargeAt - now); }
}
