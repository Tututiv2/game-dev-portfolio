package com.skyclone.mod.hollows;

/**
 * The Crystal Caverns lobby lifecycle as pure arithmetic (no Bukkit, unit-tested). Research 03 §12 / 07 §2.11:
 * a lobby opens on SkyBlock Day 0, stops taking NEW players on Day 19, closes as soon as fewer than 4 players
 * remain after that, and is force-closed on Day 35. One SkyBlock day is one calendar day (20 real minutes),
 * so Day 19 is ~6.3 h and Day 35 ~11.7 h after the lobby opened.
 */
public final class LobbyMath {

    private LobbyMath() {}

    public enum Stage {
        /** Day 0 .. close-day: anyone may enter. */
        OPEN,
        /** close-day .. shutdown-day: only players who were already in this lobby (or a party member's) may enter; closes when it thins out. */
        CLOSING,
        /** shutdown-day reached: everyone out, a new lobby is built. */
        SHUTDOWN
    }

    /** Whole SkyBlock days since the lobby opened (0 on the opening day). Never negative. */
    public static int day(long now, long openedAt, long millisPerDay) {
        if (millisPerDay <= 0 || openedAt <= 0 || now <= openedAt) return 0;
        long d = (now - openedAt) / millisPerDay;
        return d > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) d;
    }

    public static Stage stage(int day, int closeDay, int shutdownDay) {
        if (shutdownDay > 0 && day >= shutdownDay) return Stage.SHUTDOWN;
        if (closeDay > 0 && day >= closeDay) return Stage.CLOSING;
        return Stage.OPEN;
    }

    /** After the close day the lobby closes once fewer than {@code closeBelow} players remain inside (reference: 4). */
    public static boolean shouldClose(Stage stage, int playersInside, int closeBelow) {
        if (stage == Stage.SHUTDOWN) return true;
        return stage == Stage.CLOSING && playersInside < Math.max(1, closeBelow);
    }

    /**
     * May this player walk in? Newcomers are refused once the lobby is CLOSING, unless they were already in
     * this lobby or a party member is inside right now (they join that member's lobby, as in the reference).
     */
    public static boolean mayEnter(Stage stage, boolean lockNewcomers, boolean seenThisLobby, boolean partyInside) {
        if (stage == Stage.SHUTDOWN) return false;
        if (stage == Stage.OPEN || !lockNewcomers) return true;
        return seenThisLobby || partyInside;
    }

    /** Real millis until a given SkyBlock day of the lobby begins (0 when already reached). */
    public static long millisUntilDay(long now, long openedAt, long millisPerDay, int day) {
        if (openedAt <= 0 || millisPerDay <= 0) return 0;
        long at = openedAt + (long) day * millisPerDay;
        return Math.max(0, at - now);
    }

    /** "6h 20m" / "14m" / "now" for a real-time span. */
    public static String span(long millis) {
        if (millis <= 0) return "now";
        long m = (millis + 59_999) / 60_000;
        if (m < 60) return m + "m";
        return (m / 60) + "h " + (m % 60) + "m";
    }
}
