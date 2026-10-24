package com.skyclone.mod.collections;

import com.skyclone.api.Sky;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Global collection leaderboards (research 02 section 3: "after 25,000 of an item, the menu shows the
 * player's global rank for that collection").
 *
 * <p>One row per (profile, collection) once the profile's combined total reaches the dial
 * {@code collections.leaderboard-min}. Gains only mark a row dirty in memory; a timer writes the dirty rows
 * in one transaction and then re-reads the whole board into an immutable snapshot, so menus rank from
 * memory and never wait on SQLite. Ranks are per profile (a co-op is one entry, as its total is one
 * number), and a profile never outranks itself through a stale row: the live total is used for "you".
 *
 * <p><b>Profile modes</b> (wave 5). Classic, Ironman, Stranded and Bingo profiles are ranked on SEPARATE
 * boards (dial {@code collections.leaderboard-split-modes}): an Ironman gathers with only what it made itself,
 * while a Classic profile buys its tools, pets and boosters on the Bazaar and Auction House, so one board would
 * compare two different games. The
 * mode is stored on the row when it is written and refreshed from core's {@code profiles} table on every
 * re-read (a profile converted Ironman -> Classic moves boards without a new gain). Rows of profiles that no
 * longer have any member (deleted / everyone left) are pruned on a slow cadence
 * ({@code collections.leaderboard-prune}).
 */
final class CollectionsLeaderboard {

    /** One leaderboard row: a profile's combined total and who is on it (first contributors). */
    record Row(String profileId, long total, String names) {}

    /** A row as read from SQL, before it is split into per-mode boards. */
    record Raw(String coll, String mode, Row row) {}

    /** "#rank of size". */
    record Position(int rank, int size) {
        /** Top percentage, rounded up to a whole percent (never 0): the genre's "Top 5%". */
        int topPercent() { return size <= 0 ? 100 : (int) Math.max(1, Math.ceil(100.0 * rank / size)); }
    }

    /** The boards a player can name ({@code /collection top <item> ironman}). "normal" is Classic. */
    static final List<String> BOARDS = List.of("normal", "ironman", "stranded", "bingo");

    private record Pending(String profileId, String coll, long total, String names, String mode) {}

    /** Prune deleted profiles every this many flushes (30 s x 20 = every ~10 minutes by default). */
    private static final int PRUNE_EVERY = 20;

    private final CollectionsModule mod;
    private final Map<String, Pending> dirty = new ConcurrentHashMap<>();
    /** key(board, coll) -> rows, highest first. */
    private volatile Map<String, List<Row>> board = Map.of();
    /** "profileId|coll" of every row in {@link #board}: O(1) for the per-gain check. */
    private volatile java.util.Set<String> keys = java.util.Set.of();
    private volatile long loadedAt;
    private volatile boolean dbOk;
    /** False once the join against core's profiles table failed (then the stored mode is used). */
    private volatile boolean joinOk = true;
    private volatile boolean pruneOk = true;
    private volatile int lastPruned = -1;
    private int flushes;
    private BukkitTask task;

    CollectionsLeaderboard(CollectionsModule mod) { this.mod = mod; }

    // ---- lifecycle ---------------------------------------------------------------------------

    void enable() {
        try {
            Sky.db().migrate("collections", 1,
                    "CREATE TABLE IF NOT EXISTS collections_lb (profile_id TEXT NOT NULL, coll TEXT NOT NULL, "
                            + "total INTEGER NOT NULL, names TEXT, updated INTEGER NOT NULL, PRIMARY KEY (profile_id, coll))",
                    "CREATE INDEX IF NOT EXISTS collections_lb_coll ON collections_lb (coll, total)");
            // v2 (wave 5): the profile's game mode, so Ironman/Stranded/Bingo rank on their own boards.
            Sky.db().migrate("collections", 2,
                    "ALTER TABLE collections_lb ADD COLUMN mode TEXT NOT NULL DEFAULT 'normal'");
            dbOk = true;
        } catch (RuntimeException e) {
            Sky.log().log(Level.WARNING, "[collections] leaderboard table unavailable; leaderboards disabled", e);
            dbOk = false;
            return;
        }
        if (mod.leaderboardPrune()) prune().whenComplete((v, err) -> reload());
        else reload();
        int secs = Math.max(5, mod.leaderboardRefreshSeconds());
        task = Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::flush, 20L * secs, 20L * secs);
    }

    void disable() {
        if (task != null) task.cancel();
        if (!dbOk || dirty.isEmpty()) return;
        try { write(drain()).get(5, TimeUnit.SECONDS); }
        catch (Exception e) { Sky.log().log(Level.WARNING, "[collections] leaderboard flush on disable failed", e); }
    }

    boolean available() { return dbOk; }

    long loadedAt() { return loadedAt; }

    int pending() { return dirty.size(); }

    boolean joinOk() { return joinOk; }

    /** Rows removed by the last prune; -1 = never ran / unavailable. */
    int lastPruned() { return lastPruned; }

    // ---- boards (pure) -----------------------------------------------------------------------

    /**
     * The board a profile mode ranks on. Classic ("normal", "classic", blank, null) is "normal"; Ironman,
     * Stranded and Bingo are their own; an unknown mode gets its own board too (never silently mixed into
     * Classic). With {@code split} off everyone shares "normal".
     */
    static String boardOf(String mode, boolean split) {
        if (!split || mode == null) return "normal";
        String m = mode.trim().toLowerCase(Locale.ROOT);
        if (m.isEmpty() || m.equals("normal") || m.equals("classic")) return "normal";
        if (m.length() > 24 || !m.matches("[a-z0-9_]+")) return "normal";
        return m;
    }

    /** Player-facing board name: "Classic", "Ironman", "Stranded", "Bingo". */
    static String label(String board) {
        if (board == null || board.equals("normal")) return "Classic";
        return Character.toUpperCase(board.charAt(0)) + board.substring(1);
    }

    /** Parse a board a player typed ("ironman", "classic", "im"); null when it is not one. */
    static String parseBoard(String s) {
        if (s == null) return null;
        String m = s.trim().toLowerCase(Locale.ROOT);
        return switch (m) {
            case "normal", "classic" -> "normal";
            case "ironman", "im", "iron" -> "ironman";
            case "stranded" -> "stranded";
            case "bingo" -> "bingo";
            default -> null;
        };
    }

    static String key(String board, String coll) { return board + "|" + coll; }

    /** Pure: split raw rows into boards (key(board, coll) -> rows, highest total first, immutable). */
    static Map<String, List<Row>> group(List<Raw> raws, boolean split) {
        Map<String, List<Row>> out = new HashMap<>();
        for (Raw r : raws) out.computeIfAbsent(key(boardOf(r.mode(), split), r.coll()), k -> new ArrayList<>()).add(r.row());
        Map<String, List<Row>> frozen = new HashMap<>();
        out.forEach((k, v) -> {
            v.sort((a, b) -> Long.compare(b.total(), a.total()));
            frozen.put(k, Collections.unmodifiableList(v));
        });
        return Collections.unmodifiableMap(frozen);
    }

    // ---- writes ------------------------------------------------------------------------------

    /**
     * Record a profile's new combined total. Only totals at or above the minimum make a row; a total that
     * fell below it (admin remove/reset) deletes the row if one is on the board.
     */
    void note(Player p, CollectionCatalog.Coll c, long total) {
        if (!dbOk || p == null || c == null || c.isBoss() || !mod.leaderboardOn()) return;
        String pid = Sky.profiles().profileId(p);
        if (pid == null) return;
        boolean qualifies = total >= mod.leaderboardMin();
        if (!qualifies && !onBoard(c.id, pid) && !dirty.containsKey(pid + "|" + c.id)) return;
        String names = qualifies ? names(p, c) : "";
        dirty.put(pid + "|" + c.id, new Pending(pid, c.id, qualifies ? total : -1, names, modeOf(p)));
    }

    /** Every qualifying collection of this profile (on ProfileReady: totals from before the board existed). */
    void noteAll(Player p) {
        if (!dbOk || !mod.leaderboardOn() || !Sky.profiles().isReady(p)) return;
        for (CollectionCatalog.Coll c : mod.catalog().all()) {
            long n = mod.service().total(p, c);
            if (n >= mod.leaderboardMin()) note(p, c, n);
        }
    }

    /** Write dirty rows, then re-read the board. Runs on the timer and from the admin command. */
    void flush() {
        if (!dbOk) return;
        boolean prune = mod.leaderboardPrune() && ++flushes % PRUNE_EVERY == 0;
        List<Pending> work = drain();
        java.util.concurrent.CompletableFuture<Void> w = work.isEmpty()
                ? java.util.concurrent.CompletableFuture.completedFuture(null) : write(work);
        w.whenComplete((v, err) -> {
            if (err != null) {
                Sky.log().log(Level.WARNING, "[collections] leaderboard write failed", err);
                for (Pending pd : work) dirty.putIfAbsent(pd.profileId() + "|" + pd.coll(), pd);
            }
            if (prune) prune().whenComplete((v2, err2) -> reload());
            else reload();
        });
    }

    /** Admin: prune now, then re-read. */
    void pruneNow() {
        if (!dbOk) return;
        prune().whenComplete((v, err) -> reload());
    }

    private List<Pending> drain() {
        List<Pending> out = new ArrayList<>();
        for (String k : new ArrayList<>(dirty.keySet())) {
            Pending pd = dirty.remove(k);
            if (pd != null) out.add(pd);
        }
        return out;
    }

    private java.util.concurrent.CompletableFuture<Void> write(List<Pending> work) {
        long now = System.currentTimeMillis();
        return Sky.db().transaction(conn -> {
            try (PreparedStatement up = conn.prepareStatement(
                    "INSERT INTO collections_lb (profile_id, coll, total, names, updated, mode) VALUES (?,?,?,?,?,?) "
                            + "ON CONFLICT(profile_id, coll) DO UPDATE SET total=excluded.total, names=excluded.names, "
                            + "updated=excluded.updated, mode=excluded.mode");
                 PreparedStatement del = conn.prepareStatement("DELETE FROM collections_lb WHERE profile_id=? AND coll=?")) {
                for (Pending pd : work) {
                    if (pd.total() < 0) {
                        del.setString(1, pd.profileId()); del.setString(2, pd.coll()); del.addBatch();
                    } else {
                        up.setString(1, pd.profileId()); up.setString(2, pd.coll()); up.setLong(3, pd.total());
                        up.setString(4, pd.names()); up.setLong(5, now); up.setString(6, pd.mode()); up.addBatch();
                    }
                }
                up.executeBatch();
                del.executeBatch();
            }
            return null;
        });
    }

    /**
     * Remove rows of profiles that no longer have a single current member (core closes every membership
     * when a profile is deleted; a salvaged profile is re-noted on its next ProfileReady). Guarded: it only
     * runs when core's membership table exists and is non-empty, so an empty or foreign schema deletes nothing.
     */
    private java.util.concurrent.CompletableFuture<Void> prune() {
        if (!pruneOk) return java.util.concurrent.CompletableFuture.completedFuture(null);
        return Sky.db().<Void>transaction(conn -> {
            if (!hasMembers(conn)) { lastPruned = 0; return null; }
            try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM collections_lb WHERE NOT EXISTS (SELECT 1 FROM profile_members m "
                            + "WHERE m.profile_id = collections_lb.profile_id AND m.left_at = 0)")) {
                int n = del.executeUpdate();
                lastPruned = n;
                if (n > 0) Sky.log().info("[collections] leaderboard: pruned " + n + " rows of deleted profiles");
            }
            return null;
        }).exceptionally(err -> {
            pruneOk = false;
            Sky.log().log(Level.WARNING, "[collections] leaderboard prune unavailable (core schema?); rows of deleted profiles stay", err);
            return null;
        });
    }

    /** Rows the prune WOULD remove (read-only; self-test and admin). -1 when core's membership table is unreadable. */
    java.util.concurrent.CompletableFuture<Integer> pruneCandidates() {
        if (!dbOk) return java.util.concurrent.CompletableFuture.completedFuture(-1);
        return Sky.db().<Integer>query(conn -> {
            if (!hasMembers(conn)) return 0;
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT COUNT(*) FROM collections_lb l WHERE NOT EXISTS (SELECT 1 FROM profile_members m "
                            + "WHERE m.profile_id = l.profile_id AND m.left_at = 0)");
                 ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }).exceptionally(err -> -1);
    }

    private static boolean hasMembers(Connection conn) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement("SELECT 1 FROM profile_members WHERE left_at = 0 LIMIT 1");
             ResultSet rs = st.executeQuery()) {
            return rs.next();
        }
    }

    /** Re-read the whole board into a fresh immutable snapshot (off-thread; the swap is one write). */
    void reload() {
        if (!dbOk) return;
        boolean split = mod.leaderboardSplitModes();
        Sky.db().query(conn -> group(readRaw(conn), split)).whenComplete((m, err) -> {
            if (err != null) { Sky.log().log(Level.WARNING, "[collections] leaderboard read failed", err); return; }
            java.util.Set<String> k = new java.util.HashSet<>();
            m.forEach((bk, rows) -> {
                String coll = bk.substring(bk.indexOf('|') + 1);
                for (Row r : rows) k.add(r.profileId() + "|" + coll);
            });
            keys = k;
            board = m;
            loadedAt = System.currentTimeMillis();
        });
    }

    /** Rows with the profile's CURRENT mode from core's profiles table; the stored mode if that join fails. */
    private List<Raw> readRaw(Connection conn) throws SQLException {
        if (joinOk) {
            try {
                return readRaw(conn, "SELECT l.coll, l.profile_id, l.total, l.names, COALESCE(p.mode, l.mode) "
                        + "FROM collections_lb l LEFT JOIN profiles p ON p.id = l.profile_id");
            } catch (SQLException e) {
                joinOk = false;
                Sky.log().warning("[collections] leaderboard: cannot read profile modes from core ("
                        + e.getMessage() + "); using the mode stored with each row");
            }
        }
        return readRaw(conn, "SELECT coll, profile_id, total, names, mode FROM collections_lb");
    }

    private static List<Raw> readRaw(Connection conn, String sql) throws SQLException {
        List<Raw> out = new ArrayList<>();
        try (PreparedStatement st = conn.prepareStatement(sql); ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                out.add(new Raw(rs.getString(1), rs.getString(5), new Row(rs.getString(2), rs.getLong(3), rs.getString(4))));
            }
        }
        return out;
    }

    // ---- reads (memory only) -----------------------------------------------------------------

    private boolean onBoard(String coll, String pid) {
        return keys.contains(pid + "|" + coll);
    }

    private static String modeOf(Player p) {
        try {
            String m = Sky.profiles().mode(p);
            return m == null || m.isBlank() ? "normal" : m.toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return "normal";
        }
    }

    /** The board this player's profile ranks on. */
    String boardFor(Player p) { return boardOf(modeOf(p), mod.leaderboardSplitModes()); }

    /** This player's profile position on its own board, or null when below the minimum / leaderboards are off. */
    Position position(Player p, CollectionCatalog.Coll c) {
        if (!dbOk || !mod.leaderboardOn() || c == null || c.isBoss()) return null;
        long n = mod.service().total(p, c);
        if (n < mod.leaderboardMin()) return null;
        return rank(rows(c, boardFor(p)), Sky.profiles().profileId(p), n);
    }

    private List<Row> rows(CollectionCatalog.Coll c, String boardId) {
        return board.getOrDefault(key(boardId, c.id), List.of());
    }

    /** Top {@code n} rows of a collection on one board (the snapshot, highest first). */
    List<Row> top(CollectionCatalog.Coll c, String boardId, int n) {
        List<Row> rows = rows(c, boardId);
        return rows.subList(0, Math.min(n, rows.size()));
    }

    /** Number of profiles on a collection's board. */
    int size(CollectionCatalog.Coll c, String boardId) { return rows(c, boardId).size(); }

    /** Profiles per board for one collection (admin info). */
    Map<String, Integer> sizes(CollectionCatalog.Coll c) {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (var e : board.entrySet()) {
            String k = e.getKey();
            int bar = k.indexOf('|');
            if (bar > 0 && k.substring(bar + 1).equals(c.id)) out.put(k.substring(0, bar), e.getValue().size());
        }
        return out;
    }

    /**
     * Pure ranking: 1 + the number of OTHER profiles strictly above {@code total}; the board size counts
     * this profile once whether or not its row was written yet. Ties share a rank.
     */
    static Position rank(List<Row> rows, String profileId, long total) {
        int above = 0;
        boolean self = false;
        for (Row r : rows) {
            if (profileId != null && profileId.equals(r.profileId())) { self = true; continue; }
            if (r.total() > total) above++;
        }
        return new Position(above + 1, rows.size() + (self ? 0 : 1));
    }

    /** "Alice" or "Alice, Bob +1": the profile's largest contributors to this collection. */
    private String names(Player p, CollectionCatalog.Coll c) {
        List<Map.Entry<String, Long>> con = mod.service().contributions(p, c);
        if (con.isEmpty()) return p.getName();
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (var e : con) {
            if (shown == 2) { sb.append(" +").append(con.size() - 2); break; }
            if (shown > 0) sb.append(", ");
            sb.append(e.getKey());
            shown++;
        }
        return sb.toString();
    }
}
