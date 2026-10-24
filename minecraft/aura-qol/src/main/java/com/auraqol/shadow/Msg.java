package com.auraqol.shadow;

import java.util.List;

/**
 * Every Shadow Live message, as plain data (PROTOCOL.md, message tables). Field
 * order and names follow the protocol exactly so a record reads like its row.
 *
 * <p>Every unsigned wire number ({@code u32}) is a {@code long}, so the whole
 * range is exact; money is integer CENTS and times are server epoch ms, both
 * {@code s64}. No field is a floating point number - that is the point of the
 * protocol (NaN cannot be sent).
 */
public sealed interface Msg {

    // ------------------------------------------------------------ type ids

    int WELCOME = 1;
    int SNAPSHOT = 2;
    int BREAKS = 3;
    int INCOME = 4;
    int BALANCE = 5;
    int PROGRESS = 6;
    int BOOSTS = 7;
    int SKILL = 8;
    int CONTRACT = 9;
    int AREA = 10;
    int RESPONSE = 11;
    /** Added 2026-10-13: the fighting twin of BREAKS. */
    int COMBAT = 12;

    int HELLO = 1;
    int REQUEST = 2;

    /** The message type id on the wire. */
    int type();

    // ------------------------------------------------------------ server -> client

    record Welcome(long protocol, boolean supported, long serverTimeMs, List<String> features,
                   long flushMs, long lookupsPerMinute) implements Msg {
        public int type() {
            return WELCOME;
        }
    }

    /** Each element is a complete frame of types 3-10, already decoded; undecodable ones are dropped. */
    record Snapshot(List<Msg> frames) implements Msg {
        public int type() {
            return SNAPSHOT;
        }
    }

    record BlockCount(String blockKey, long count) {
    }

    record Breaks(long count, long valueCents, List<BlockCount> perBlock) implements Msg {
        public int type() {
            return BREAKS;
        }
    }

    record MobCount(String mobKey, long count) {
    }

    /** Kills since the last COMBAT and the inventory value gained while fighting. */
    record Combat(long kills, long valueCents, List<MobCount> perMob) implements Msg {
        public int type() {
            return COMBAT;
        }
    }

    record Income(String sourceId, String displayName, long amountCents, long serverTimeMs) implements Msg {
        public int type() {
            return INCOME;
        }
    }

    record Extra(String key, String display) {
    }

    record Balance(long balanceCents, List<Extra> extras) implements Msg {
        public int type() {
            return BALANCE;
        }
    }

    /** {@code nextRankDisplay = ""} and {@code nextRankCostCents = -1} mean max rank. */
    record Progress(String rankDisplay, String nextRankDisplay, long nextRankCostCents, long prestige,
                    long blocksMinedTotal) implements Msg {
        public int type() {
            return PROGRESS;
        }

        public boolean maxRank() {
            return nextRankCostCents < 0 || nextRankDisplay == null || nextRankDisplay.isEmpty();
        }
    }

    /** {@code expiresAtMs = 0} = no expiry. */
    record Boost(String id, String name, String multiplierText, long expiresAtMs) {
    }

    /** The FULL current set (replace, never merge). */
    record Boosts(List<Boost> boosts) implements Msg {
        public int type() {
            return BOOSTS;
        }
    }

    /** {@code xpForLevel = 0} = maxed. */
    record Skill(String skillId, String displayName, long level, long xpInLevel, long xpForLevel) implements Msg {
        public int type() {
            return SKILL;
        }
    }

    record Contract(long state, String target, long tier, long progressPermille) implements Msg {
        public static final int NONE = 0;
        public static final int HUNTING = 1;
        public static final int BOSS_UP = 2;
        public static final int SLAIN = 3;
        public static final int FAILED = 4;

        public int type() {
            return CONTRACT;
        }
    }

    record Area(String name) implements Msg {
        public int type() {
            return AREA;
        }
    }

    record Row(String group, String label, String value) {
    }

    /**
     * {@code name, uuid, online, rows} exist only when {@code status == OK};
     * otherwise they are "", "", false and an empty list.
     */
    record Response(long reqId, long status, long retryAfterMs, String name, String uuid, boolean online,
                    List<Row> rows) implements Msg {
        public static final int OK = 0;
        public static final int NOT_FOUND = 1;
        public static final int RATE_LIMITED = 2;
        public static final int ERROR = 3;
        public static final int UNSUPPORTED = 4;

        public static Response status(long reqId, long status, long retryAfterMs) {
            return new Response(reqId, status, retryAfterMs, "", "", false, List.of());
        }

        public int type() {
            return RESPONSE;
        }
    }

    // ------------------------------------------------------------ client -> server

    record Hello(long protocol, String modVersion, List<String> wants) implements Msg {
        public int type() {
            return HELLO;
        }
    }

    record Request(long reqId, long kind, String query) implements Msg {
        public static final int PROFILE = 1;

        public int type() {
            return REQUEST;
        }
    }

    // ------------------------------------------------------------ other

    /** A frame whose type this client does not know. Ignored whole (forward compatibility). */
    record Unknown(int type, long version) implements Msg {
    }
}
