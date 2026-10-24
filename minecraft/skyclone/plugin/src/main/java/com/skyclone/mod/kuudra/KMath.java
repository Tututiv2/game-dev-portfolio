package com.skyclone.mod.kuudra;

/** The hunt's formulas. Pure (no Bukkit) so they are unit-tested against the numbers they promise. */
public final class KMath {

    private KMath() {}

    /**
     * The Leviathan's health multiplier for a crew of {@code n} (1..4). The reference is balanced for four;
     * a smaller crew faces a smaller beast, linearly from {@code soloFraction} at one player to 1.0 at four.
     * TUNE (the research gives no party scaling).
     */
    public static double partyHpScale(int n, double soloFraction) {
        int k = Math.max(1, Math.min(4, n));
        double s = Math.max(0.05, Math.min(1, soloFraction));
        return s + (1 - s) * (k - 1) / 3.0;
    }

    /** Brood in one wave: grows by one every two waves and by one per extra player, capped. TUNE. */
    public static int waveSize(int waveNo, int players, int cap) {
        int n = 2 + Math.max(0, waveNo) / 2 + Math.max(0, players - 1);
        return Math.max(1, Math.min(cap, n));
    }

    /**
     * Most brood alive at once for a crew of {@code n}: {@code maxAlive} is the four-hunter number, a smaller crew
     * faces a smaller swarm (40% solo, +20% per extra hunter), never under four. TUNE. The 2026-10-03 playtest
     * soloed Basic into 18 deaths against the full four-hunter cap of 28.
     */
    public static int crowdCap(int maxAlive, int players) {
        int k = Math.max(1, Math.min(4, players));
        return Math.max(Math.min(4, Math.max(1, maxAlive)), (int) Math.round(Math.max(1, maxAlive) * (0.4 + 0.2 * (k - 1))));
    }

    /**
     * A new wave climbs out only when its timer is due AND the crew has thinned the last one to half the cap or
     * less. Without the second half the timer alone kept the swarm at the cap for the whole hunt, so a hunter who
     * was losing never got a breather to build, carry or load in. A wave held past {@code maxHoldMillis} comes
     * anyway (a straggler stuck on the far rim must not stop the hunt's clock).
     */
    public static boolean waveDue(long now, long nextWave, int alive, int cap, long maxHoldMillis) {
        if (now < nextWave) return false;
        return alive <= Math.max(1, cap) / 2 || now >= nextWave + Math.max(0, maxHoldMillis);
    }

    /**
     * Channel/build speed while being hit. The beast's own blows (spit, slam, rampage) interrupt; the brood's
     * hits only slow you to this fraction. Before, every brood hit reset the channel, and with a swarm on you a
     * channel could never finish.
     */
    public static final double BROOD_HIT_SLOW = 0.5;

    /**
     * How long a channel's progress survives losing its target - a brood knockback shoving you a step off the pad,
     * a moment's release of crouch. The wave4-endgame playtest lost a 3 s set-down to every shove (it restarted at
     * 0%), so with Followers on the carrier it never finished. The beast's own blows still interrupt outright.
     */
    public static final long CHANNEL_GRACE_MILLIS = 1500;

    /** Does a channel that lost its target {@code lostForMillis} ago still keep its progress? */
    public static boolean channelHolds(long lostForMillis) { return lostForMillis >= 0 && lostForMillis < CHANNEL_GRACE_MILLIS; }

    /**
     * The brood's damage multiplier for a crew of {@code n}: {@code soloFraction} for one hunter, linear to 1.0 at
     * four - the same shape as the beast's health ({@link #partyHpScale}). A solo hunter has every Follower on
     * them; the wave4-endgame playtest saw Lv100 Followers knock down a 983 HP / 741 Defense hunter mid-carry on Basic.
     * TUNE.
     */
    public static double broodDamageScale(int n, double soloFraction) { return partyHpScale(n, soloFraction); }

    /**
     * Blocks above {@code lair.spawn} the hunters drop in from when the floor gives way (with Slow Falling).
     * The lair dome leaves ~16 blocks of headroom there (generator: lry 32, spawn 22 blocks off the dome's
     * centre line) and more further in, so 10 keeps every head clear of the roof.
     */
    public static final double LAIR_DROP = 10;
    /** ...and this many blocks further in (towards the head, -z), where the dome is higher. */
    public static final double LAIR_DROP_INWARD = 4;

    /** Hunt steps (of 0.25 s) the beast takes to drag itself ashore when the DPS phase begins: 3 s. */
    public static final int CRAWL_STEPS = 12;

    /** Smoothstep (slow start, heave, settle) of a crawl {@code done} steps of {@code total} in; clamped to 0..1. */
    public static double crawlProgress(int done, int total) {
        if (total <= 0) return 1;
        double c = Math.max(0, Math.min(1, done / (double) total));
        return c * c * (3 - 2 * c);
    }

    /** Blocks under {@code kuudra.rise} the beast starts from; it surfaces over the 5 s countdown. */
    public static final double RISE_DEPTH = 10;

    /** Ease-out (fast start, slow finish) on 0..1: the beast heaves up, then settles. */
    public static double easeOut(double t) {
        double c = Math.max(0, Math.min(1, t));
        return 1 - (1 - c) * (1 - c) * (1 - c);
    }

    /**
     * Centre z of the beached beast so its FRONT (+z) face reaches {@code faceZ}, the line on the shore it
     * crawls onto, given half its hitbox depth. Never closer than 1.5 blocks to that line, so an unexpectedly
     * small hitbox still sits in the lava rather than inside the shore.
     */
    public static double beachedCentreZ(double faceZ, double halfDepth) {
        return Math.min(faceZ - Math.max(0, halfDepth), faceZ - 1.5);
    }

    /**
     * Feet y of the beached beast: its anchor height, raised only when a short hitbox would not stand at least
     * three blocks over the shore (a beast hidden under the lava cannot be hit with a sword).
     */
    public static double beachedFeetY(double anchorY, double shoreY, double height) {
        return Math.max(anchorY, Math.min(shoreY - 1, shoreY + 3 - Math.max(0, height)));
    }

    /**
     * Seconds a channel (pick up, set down, load) takes after perks and accessories, each a percentage
     * off. Never below 20% of the base.
     */
    public static double channelSeconds(double base, double percentOff) {
        return base * Math.max(0.2, 1 - Math.max(0, percentOff) / 100.0);
    }

    /** Ballista build percent one builder adds per second, with % bonuses (perk + accessory). */
    public static double buildRate(double basePerSecond, double percentBonus) {
        return basePerSecond * (1 + Math.max(0, percentBonus) / 100.0);
    }

    /**
     * Health a volley removes: {@code percent} of the beast's max, plus Overcharge, never taking it below
     * {@code floorHp} (the ballista cannot finish the beast: the DPS phase does).
     */
    public static double volley(double hp, double maxHp, double percent, double overchargePct, double floorHp) {
        double dmg = maxHp * percent / 100.0 * (1 + Math.max(0, overchargePct) / 100.0);
        double after = Math.max(floorHp, hp - dmg);
        return Math.max(0, hp - after);
    }

    /** Volleys a full-health beast needs to reach the DPS phase (for the guide lore). */
    public static int volleysToDps(double volleyPercent, double dpsAtPercent) {
        if (volleyPercent <= 0) return Integer.MAX_VALUE;
        return (int) Math.ceil((100 - dpsAtPercent) / volleyPercent - 1e-9);
    }

    /** Strikes an organ in the Gullet needs: base minus Gut Pick levels, at least one. */
    public static int orbHits(int base, int gutPickLevel) { return Math.max(1, base - Math.max(0, gutPickLevel)); }

    /** Tokens for a brood kill: the def's tokens (already tier-scaled by the generator) times a dial. */
    public static int killTokens(int defTokens, double scale) { return (int) Math.max(0, Math.round(defTokens * scale)); }

    /** "4:07" */
    public static String clock(long millis) {
        long s = Math.max(0, millis / 1000);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    /** Uniform integer in [lo, hi] from a unit roll. */
    public static int between(int lo, int hi, double unit) {
        if (hi <= lo) return lo;
        return lo + (int) Math.floor(Math.max(0, Math.min(0.999999, unit)) * (hi - lo + 1));
    }
}
