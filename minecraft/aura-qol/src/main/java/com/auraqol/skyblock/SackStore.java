package com.auraqol.skyblock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What is in your sacks, as far as the client can know. Pure; the clock is
 * wall time in ms, passed in.
 *
 * <p>The client cannot read sacks. A count becomes KNOWN when you open a sack
 * menu (each item's lore says "Stored: X/Y" - {@link #sync}), and is then kept
 * current from the [Sacks] chat summaries ({@link #change}). An item never
 * seen in a sack menu stays unknown, however many summaries mention it - a
 * delta on top of nothing is not a count. Gains are recorded for the rate
 * either way.
 *
 * <h2>The one overlap</h2>
 * A [Sacks] summary covers the 30 s BEFORE it arrives. If you opened the menu
 * part-way through that window, the menu's count already includes the part
 * before you opened it, and adding the whole summary would count that part
 * twice. Nothing can be harvested while a menu is open, so the first summary
 * after a sync is applied in proportion to how much of its 30 s came after
 * the sync (the menu closing). An estimate - re-open the menu to be exact.
 */
public final class SackStore {

    /** A [Sacks] summary covers this long. */
    public static final long SUMMARY_MS = 30_000;
    /** The estimate's rate is the net change over this much sack history, ending at the last summary. */
    public static final long ESTIMATE_WINDOW_MS = 120_000;
    /** No summary for this long means the gains stopped: show the real count again, not a guess. */
    public static final long ESTIMATE_MAX_MS = 40_000;

    private final Map<String, Long> counts = new HashMap<>();
    private final Map<String, Long> syncedAt = new HashMap<>();
    private final Set<String> fresh = new HashSet<>();
    private final Map<String, ArrayDeque<long[]>> gains = new HashMap<>();
    /** Every change, signed (gains AND removals), for the estimate's net rate. */
    private final Map<String, ArrayDeque<long[]>> changes = new HashMap<>();
    private long lastSummaryAt = -1;
    private long trackStart = -1;
    private boolean dirty;

    /** A count read from a sack menu. */
    public void sync(String id, long count, long now) {
        if (id == null || count < 0) {
            return;
        }
        Long old = counts.put(id, count);
        syncedAt.put(id, now);
        fresh.add(id);
        if (old == null || old != count) {
            dirty = true;
        }
    }

    /** One line of a [Sacks] summary ("+1,280 Wheat" / "-64 Wheat"). */
    public void change(String id, long delta, long now) {
        if (id == null || delta == 0) {
            return;
        }
        if (trackStart < 0) {
            trackStart = now - SUMMARY_MS;
        }
        lastSummaryAt = Math.max(lastSummaryAt, now);
        ArrayDeque<long[]> cq = changes.computeIfAbsent(id, k -> new ArrayDeque<>());
        cq.addLast(new long[]{now, delta});
        while (cq.size() > 4096) {
            cq.removeFirst();
        }
        if (delta > 0) {
            ArrayDeque<long[]> q = gains.computeIfAbsent(id, k -> new ArrayDeque<>());
            q.addLast(new long[]{now, delta});
            while (q.size() > 4096) {
                q.removeFirst();
            }
        }
        Long have = counts.get(id);
        if (have == null) {
            return;
        }
        long applied = delta;
        if (fresh.remove(id)) {
            long since = now - syncedAt.getOrDefault(id, now);
            if (since >= 0 && since < SUMMARY_MS) {
                applied = Math.round(delta * (since / (double) SUMMARY_MS));
            }
        }
        counts.put(id, Math.max(0, have + applied));
        dirty = true;
    }

    /** The count, or null if never synced. */
    public Long count(String id) {
        return id == null ? null : counts.get(id);
    }

    /** When the item was last read from a sack menu, or -1. */
    public long syncedAt(String id) {
        Long t = id == null ? null : syncedAt.get(id);
        return t == null ? -1 : t;
    }

    /** Gains of one item in the last {@code windowMs}. */
    public long gained(String id, long now, long windowMs) {
        ArrayDeque<long[]> q = gains.get(id);
        if (q == null) {
            return 0;
        }
        long sum = 0;
        for (long[] g : q) {
            if (g[0] > now - windowMs) {
                sum += g[1];
            }
        }
        return sum;
    }

    /** When the last [Sacks] summary line arrived, or -1. */
    public long lastSummaryAt() {
        return lastSummaryAt;
    }

    /** Ids with a known count (a live view, not a copy). */
    public Set<String> knownIds() {
        return java.util.Collections.unmodifiableSet(counts.keySet());
    }

    /**
     * Net change of one item per millisecond: everything the summaries in the
     * {@link #ESTIMATE_WINDOW_MS} before the LAST summary added and removed,
     * over that span. Anchored at the last summary, so it does not wobble
     * between summaries. Never negative (a draining sack is not extrapolated).
     */
    public double perMs(String id) {
        ArrayDeque<long[]> q = changes.get(id);
        if (q == null || lastSummaryAt < 0 || trackStart < 0) {
            return 0;
        }
        long span = Math.min(ESTIMATE_WINDOW_MS, lastSummaryAt - trackStart);
        if (span <= 0) {
            return 0;
        }
        long sum = 0;
        for (long[] c : q) {
            if (c[0] > lastSummaryAt - span && c[0] <= lastSummaryAt) {
                sum += c[1];
            }
        }
        return sum <= 0 ? 0 : sum / (double) span;
    }

    /**
     * The count as it probably is NOW: the last known count plus the item's
     * recent rate times the time since the last summary (Hypixel sends one
     * every 20-30 s, so the real number is always up to half a minute old).
     * {@code activeUntil} is when you last collected (the last broken block);
     * the estimate stops growing there. Past {@link #ESTIMATE_MAX_MS} with no
     * summary the gains have stopped and the plain count is returned. When a
     * summary lands the estimate snaps to the real number by construction.
     *
     * @return the estimate (equal to the count when there is nothing to add), or null if the count is unknown
     */
    public Long estimate(String id, long now, long activeUntil) {
        Long c = count(id);
        if (c == null || lastSummaryAt < 0 || now - lastSummaryAt > ESTIMATE_MAX_MS) {
            return c;
        }
        long from = Math.max(lastSummaryAt, syncedAt(id));
        long dt = Math.min(Math.min(now, activeUntil) - from, ESTIMATE_MAX_MS);
        if (dt <= 0) {
            return c;
        }
        return c + (long) Math.floor(perMs(id) * dt);
    }

    /** One row of a family view; {@code count} null = unknown. */
    public record Row(String id, long mult, Long count) {
    }

    /**
     * A family's rows, its known total in raw items (null if no member is
     * known), whether some members are unknown, its raw-equivalent gain rate
     * per hour over {@code windowMs} (0 until a minute of sack data exists),
     * and when its oldest known member was synced (-1 if none).
     */
    public record View(SackFamilies.Family family, List<Row> rows, Long rawTotal, boolean partial,
                       double perHour, long oldestSync) {
    }

    public View view(SackFamilies.Family f, long now, long windowMs) {
        List<Row> rows = new ArrayList<>();
        long total = 0;
        boolean any = false;
        boolean partial = false;
        long oldest = -1;
        double gainedRaw = 0;
        for (SackFamilies.Member m : f.members()) {
            Long c = counts.get(m.id());
            rows.add(new Row(m.id(), m.mult(), c));
            if (c != null) {
                any = true;
                total += c * m.mult();
                long s = syncedAt.getOrDefault(m.id(), -1L);
                if (s >= 0 && (oldest < 0 || s < oldest)) {
                    oldest = s;
                }
            } else {
                partial = true;
            }
            gainedRaw += (double) gained(m.id(), now, windowMs) * m.mult();
        }
        double perHour = 0;
        if (trackStart >= 0) {
            long span = Math.min(windowMs, now - trackStart);
            if (span >= 60_000) {
                perHour = gainedRaw * 3_600_000.0 / span;
            }
        }
        return new View(f, rows, any ? total : null, partial, perHour, oldest);
    }

    /** Anything changed since the last {@link #clearDirty}? (For saving.) */
    public boolean dirty() {
        return dirty;
    }

    public void clearDirty() {
        dirty = false;
    }

    public void clear() {
        counts.clear();
        syncedAt.clear();
        fresh.clear();
        gains.clear();
        changes.clear();
        lastSummaryAt = -1;
        trackStart = -1;
        dirty = true;
    }

    public int knownCount() {
        return counts.size();
    }

    // ------------------------------------------------------------ persistence

    /** "ID count syncedAt" per line. Gains are not saved: a rate is about this session. */
    public String toText() {
        StringBuilder sb = new StringBuilder("# AuraQoL sack counts: id count synced-at-epoch-ms. Open /sacks to refresh.\n");
        List<String> ids = new ArrayList<>(counts.keySet());
        ids.sort(null);
        for (String id : ids) {
            sb.append(id).append(' ').append(counts.get(id)).append(' ')
                    .append(syncedAt.getOrDefault(id, 0L)).append('\n');
        }
        return sb.toString();
    }

    /** Inverse of {@link #toText}; unreadable lines are skipped. */
    public static SackStore fromText(String text) {
        SackStore s = new SackStore();
        if (text == null) {
            return s;
        }
        for (String line : text.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            String[] p = t.split("\\s+");
            if (p.length < 2) {
                continue;
            }
            try {
                long c = Long.parseLong(p[1]);
                long at = p.length > 2 ? Long.parseLong(p[2]) : 0;
                if (c >= 0) {
                    s.counts.put(p[0], c);
                    s.syncedAt.put(p[0], at);
                }
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return s;
    }
}
