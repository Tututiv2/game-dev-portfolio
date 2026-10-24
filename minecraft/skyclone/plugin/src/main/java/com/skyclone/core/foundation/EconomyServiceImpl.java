package com.skyclone.core.foundation;

import com.skyclone.api.config.Dials;
import com.skyclone.api.economy.EconomyService;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.core.foundation.ProfileModel.LoadedProfile;
import com.skyclone.core.foundation.ProfileModel.OfflineWrite;
import com.skyclone.core.foundation.ProfileModel.Session;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Coins and bits, written through to SQLite on EVERY mutation together with an audit row in
 * {@code economy_ledger} (same transaction). Balances live in the profile session (purse, bits)
 * and the loaded profile (bank, shared by a co-op), loaded with the profile at login.
 *
 * <p>Amounts are long TENTHS internally (see {@link Coins}): credits round down, debits round up,
 * negative / NaN / infinite amounts are refused and logged with a stack trace (a module bug, never
 * a player's fault). Bits are whole numbers.
 *
 * <p>Thread-safety: mutations hold this object's lock; CoinsChange always fires on the main thread.
 * Writes are absolute values captured under the lock and queued in order on the single DB thread,
 * so the database can never run ahead of or behind the in-memory balance.
 */
public final class EconomyServiceImpl implements EconomyService {

    /** One audit row (amounts in tenths for coins, whole for bits). */
    public record LedgerRow(long time, String profileId, UUID uuid, String currency, long delta, long after, String reason) {}

    private final ProfileServiceImpl profiles;
    private final SqliteDatabase db;
    private final Logger log;
    private final Dials.Dial<Double> defaultBankCap;
    private final Dials.Dial<Integer> ledgerMemory;
    private final Deque<LedgerRow> recentAll = new ArrayDeque<>();
    private final Map<UUID, Deque<LedgerRow>> recentByPlayer = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public EconomyServiceImpl(ProfileServiceImpl profiles, SqliteDatabase db, Dials dials, Logger log) {
        this.profiles = profiles;
        this.db = db;
        this.log = log;
        this.defaultBankCap = dials.number("economy.bank-default-capacity", 50_000_000,
                "Bank capacity of a new profile in coins (genre Starter Account: 50M) until the bank module upgrades it");
        this.ledgerMemory = dials.integer("economy.ledger-memory", 200,
                "Recent ledger rows kept in memory per player for /sb ledger (all rows are always in the database)");
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private Session session(Player p) { return profiles.session(p); }

    private boolean badAmount(double amount, String op, String reason) {
        if (Coins.valid(amount)) return false;
        String k = op + ":" + reason;
        if (warned.size() < 1000 && warned.add(k)) {
            log.log(Level.WARNING, "[economy] refused " + op + " of " + amount + " (reason '" + reason
                    + "'): amounts must be finite, >= 0 and <= 1e15", new Throwable("called from"));
        }
        return true;
    }

    private Session require(Player p, String op) {
        Session s = session(p);
        if (s == null && p != null && warned.add("unloaded:" + op)) {
            log.warning("[economy] " + op + " for " + p.getName() + " before their profile loaded - refused");
        }
        return s;
    }

    private void fireCoins(Player p, long deltaTenths, long purseAfter, String reason) {
        Runnable r = () -> {
            if (!p.isOnline()) return;
            try {
                Bukkit.getPluginManager().callEvent(new SkyEvents.CoinsChange(p, Coins.toCoins(deltaTenths), Coins.toCoins(purseAfter), reason));
            } catch (Throwable t) {
                log.log(Level.WARNING, "[economy] CoinsChange listener threw", t);
            }
        };
        if (Bukkit.isPrimaryThread()) r.run(); else Bukkit.getScheduler().runTask(profiles.plugin, r);
    }

    /** SkyEvents.CurrencyChange for every row (purse, bank, bits), on the main thread. */
    private void fireCurrency(UUID who, List<LedgerRow> rows) {
        Runnable r = () -> {
            Player p = Bukkit.getPlayer(who);
            if (p == null || !p.isOnline()) return;
            for (LedgerRow row : rows) {
                boolean bits = "bits".equals(row.currency());
                double delta = bits ? row.delta() : Coins.toCoins(row.delta());
                double after = bits ? row.after() : Coins.toCoins(row.after());
                try {
                    Bukkit.getPluginManager().callEvent(new SkyEvents.CurrencyChange(p, row.currency(), delta, after, row.reason()));
                } catch (Throwable t) {
                    log.log(Level.WARNING, "[economy] CurrencyChange listener threw", t);
                }
            }
        };
        if (Bukkit.isPrimaryThread()) r.run(); else Bukkit.getScheduler().runTask(profiles.plugin, r);
    }

    private void remember(LedgerRow row) {
        int cap = Math.max(10, ledgerMemory.get());
        synchronized (recentAll) {
            recentAll.addLast(row);
            while (recentAll.size() > 2000) recentAll.removeFirst();
        }
        if (row.uuid() != null) {
            Deque<LedgerRow> d = recentByPlayer.computeIfAbsent(row.uuid(), k -> new ArrayDeque<>());
            synchronized (d) {
                d.addLast(row);
                while (d.size() > cap) d.removeFirst();
            }
        }
    }

    static void insertLedger(Connection c, LedgerRow r) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO economy_ledger(time, profile_id, uuid, currency, delta, balance_after, reason) VALUES(?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, r.time());
            ps.setString(2, r.profileId());
            ps.setString(3, r.uuid() == null ? null : r.uuid().toString());
            ps.setString(4, r.currency());
            ps.setLong(5, r.delta());
            ps.setLong(6, r.after());
            ps.setString(7, r.reason() == null ? "" : r.reason());
            ps.executeUpdate();
        }
    }

    /** Queue: member balances (absolute) + profile bank (absolute, optional) + ledger rows. */
    private CompletableFuture<Void> persist(Session s, boolean member, boolean bank, List<LedgerRow> rows) {
        String pid = s.profile.id;
        String us = s.uuid.toString();
        long purse = s.purse, bits = s.bits;
        long bankV = s.profile.bank, cap = s.profile.bankCap;
        for (LedgerRow r : rows) remember(r);
        fireCurrency(s.uuid, rows);
        return db.transaction(c -> {
            if (member) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE profile_members SET purse = ?, bits = ? WHERE profile_id = ? AND uuid = ?")) {
                    ps.setLong(1, purse);
                    ps.setLong(2, bits);
                    ps.setString(3, pid);
                    ps.setString(4, us);
                    if (ps.executeUpdate() != 1) throw new SQLException("no membership row for " + us + " on " + pid);
                }
            }
            if (bank) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE profiles SET bank = ?, bank_cap = ? WHERE id = ?")) {
                    ps.setLong(1, bankV);
                    ps.setLong(2, cap);
                    ps.setString(3, pid);
                    if (ps.executeUpdate() != 1) throw new SQLException("no profile row " + pid);
                }
            }
            for (LedgerRow r : rows) insertLedger(c, r);
            return (Void) null;
        }).whenComplete((r, ex) -> {
            if (ex != null) log.log(Level.SEVERE, "[economy] WRITE FAILED for " + s.name + " (" + rows + ") - in-memory balance is ahead of the database", ex);
        });
    }

    private LedgerRow row(Session s, String currency, long delta, long after, String reason) {
        return new LedgerRow(System.currentTimeMillis(), s.profile.id, s.uuid, currency, delta, after, reason);
    }

    // ---- purse ---------------------------------------------------------------------------------------

    @Override
    public double purse(Player p) {
        Session s = session(p);
        return s == null ? 0 : Coins.toCoins(s.purse);
    }

    public long purseTenths(Player p) { Session s = session(p); return s == null ? 0 : s.purse; }

    @Override
    public boolean withdraw(Player p, double amount, String reason) {
        if (badAmount(amount, "withdraw", reason)) return false;
        Session s = require(p, "withdraw");
        if (s == null) return false;
        long t = Coins.debitTenths(amount);
        if (t == 0) return true;
        long after;
        synchronized (this) {
            if (s.evicted || s.purse < t) return false;
            s.purse -= t;
            after = s.purse;
            persist(s, true, false, List.of(row(s, "purse", -t, after, reason)));
        }
        fireCoins(p, -t, after, reason);
        return true;
    }

    @Override
    public void deposit(Player p, double amount, String reason) {
        if (badAmount(amount, "deposit", reason)) return;
        Session s = require(p, "deposit");
        if (s == null) return;
        depositTenths(s, p, Coins.creditTenths(amount), reason);
    }

    CompletableFuture<Void> depositTenths(Session s, Player p, long t, String reason) {
        if (t <= 0) return CompletableFuture.completedFuture(null);
        long after;
        CompletableFuture<Void> f;
        synchronized (this) {
            if (s.evicted) return CompletableFuture.failedFuture(new IllegalStateException("session closed"));
            long n = Coins.addCapped(s.purse, t);
            if (n < 0) {
                log.warning("[economy] deposit of " + Coins.formatTenths(t) + " to " + s.name + " would pass the coin cap; refused");
                return CompletableFuture.failedFuture(new IllegalStateException("coin cap"));
            }
            s.purse = n;
            after = n;
            f = persist(s, true, false, List.of(row(s, "purse", t, after, reason)));
        }
        fireCoins(p, t, after, reason);
        return f;
    }

    /** Admin: set the purse to an exact value. */
    public void setPurse(Player p, double amount, String reason) {
        if (badAmount(amount, "setPurse", reason)) return;
        Session s = require(p, "setPurse");
        if (s == null) return;
        long target = Coins.creditTenths(amount);
        long delta, after;
        synchronized (this) {
            delta = target - s.purse;
            if (delta == 0) return;
            s.purse = target;
            after = target;
            persist(s, true, false, List.of(row(s, "purse", delta, after, reason)));
        }
        fireCoins(p, delta, after, reason);
    }

    @Override
    public CompletableFuture<Void> depositOffline(UUID player, double amount, String reason) {
        return depositOfflineTo(null, player, amount, reason);
    }

    @Override
    public CompletableFuture<Void> depositOffline(String profileId, UUID player, double amount, String reason) {
        return depositOfflineTo(profileId, player, amount, reason);
    }

    /**
     * {@code targetProfile} null = the player's active profile. A target profile the player is no
     * longer a member of (left the co-op) falls back to the active profile, so coins are never lost.
     */
    private CompletableFuture<Void> depositOfflineTo(String targetProfile, UUID player, double amount, String reason) {
        if (badAmount(amount, "depositOffline", reason)) return CompletableFuture.failedFuture(new IllegalArgumentException("bad amount " + amount));
        long t = Coins.creditTenths(amount);
        if (t == 0) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> result = new CompletableFuture<>();
        Runnable decide = () -> {
            Session s = profiles.session(player);
            Player p = Bukkit.getPlayer(player);
            if (s != null && p != null && !s.evicted && (targetProfile == null || targetProfile.equals(s.profile.id))) {
                depositTenths(s, p, t, reason).whenComplete((r, ex) -> { if (ex != null) result.completeExceptionally(ex); else result.complete(null); });
                return;
            }
            profiles.beginOffline(player);
            db.transaction(c -> {
                try {
                    String pid = targetProfile != null && ProfileStore.isMember(c, targetProfile, player.toString())
                            ? targetProfile : ProfileStore.activeProfile(c, player);
                    if (pid == null) throw new IllegalStateException("player " + player + " has no profile to pay into");
                    long purse;
                    try (PreparedStatement ps = c.prepareStatement("SELECT purse FROM profile_members WHERE profile_id = ? AND uuid = ?")) {
                        ps.setString(1, pid);
                        ps.setString(2, player.toString());
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) throw new SQLException("no membership row");
                            purse = rs.getLong(1);
                        }
                    }
                    long n = Coins.addCapped(purse, t);
                    if (n < 0) throw new IllegalStateException("coin cap");
                    try (PreparedStatement ps = c.prepareStatement("UPDATE profile_members SET purse = ? WHERE profile_id = ? AND uuid = ?")) {
                        ps.setLong(1, n);
                        ps.setString(2, pid);
                        ps.setString(3, player.toString());
                        ps.executeUpdate();
                    }
                    LedgerRow r = new LedgerRow(System.currentTimeMillis(), pid, player, "purse", t, n, reason + " (offline)");
                    insertLedger(c, r);
                    remember(r);
                    profiles.recordOffline(player, new OfflineWrite(profiles.nextSeq(), pid, null, null, t));
                    return (Void) null;
                } finally {
                    profiles.endOffline(player);
                }
            }).whenComplete((r, ex) -> { if (ex != null) result.completeExceptionally(ex); else result.complete(null); });
        };
        if (Bukkit.isPrimaryThread()) decide.run(); else Bukkit.getScheduler().runTask(profiles.plugin, decide);
        return result;
    }

    // ---- bank ----------------------------------------------------------------------------------------

    @Override
    public double bank(Player p) {
        Session s = session(p);
        return s == null ? 0 : Coins.toCoins(s.profile.bank);
    }

    long capTenths(LoadedProfile lp) {
        if (lp.bankCap > 0) return lp.bankCap;
        double d = defaultBankCap.get();
        return Coins.valid(d) ? Coins.creditTenths(d) : 500_000_000L;
    }

    @Override
    public double bankCapacity(Player p) {
        Session s = session(p);
        return s == null ? 0 : Coins.toCoins(capTenths(s.profile));
    }

    @Override
    public void setBankCapacity(Player p, double capacity) {
        if (badAmount(capacity, "setBankCapacity", "capacity")) return;
        Session s = require(p, "setBankCapacity");
        if (s == null) return;
        synchronized (this) {
            s.profile.bankCap = Math.max(1, Coins.creditTenths(capacity));
            persist(s, false, true, List.of());
        }
    }

    @Override
    public boolean bankDeposit(Player p, double amount) {
        if (badAmount(amount, "bankDeposit", "bank")) return false;
        Session s = require(p, "bankDeposit");
        if (s == null) return false;
        long t = Coins.debitTenths(amount);
        if (t <= 0) return false;
        long purseAfter;
        synchronized (this) {
            LoadedProfile lp = s.profile;
            if (s.evicted || s.purse < t) return false;
            if (lp.bank + t > capTenths(lp)) return false;
            s.purse -= t;
            lp.bank += t;
            purseAfter = s.purse;
            persist(s, true, true, List.of(row(s, "purse", -t, s.purse, "bank deposit"), row(s, "bank", t, lp.bank, "bank deposit")));
        }
        fireCoins(p, -t, purseAfter, "bank deposit");
        return true;
    }

    @Override
    public boolean bankWithdraw(Player p, double amount) {
        if (badAmount(amount, "bankWithdraw", "bank")) return false;
        Session s = require(p, "bankWithdraw");
        if (s == null) return false;
        long t = Coins.debitTenths(amount);
        if (t <= 0) return false;
        long purseAfter;
        synchronized (this) {
            LoadedProfile lp = s.profile;
            if (s.evicted || lp.bank < t) return false;
            long n = Coins.addCapped(s.purse, t);
            if (n < 0) return false;
            lp.bank -= t;
            s.purse = n;
            purseAfter = n;
            persist(s, true, true, List.of(row(s, "bank", -t, lp.bank, "bank withdraw"), row(s, "purse", t, n, "bank withdraw")));
        }
        fireCoins(p, t, purseAfter, "bank withdraw");
        return true;
    }

    @Override
    public void bankCredit(Player p, double amount, String reason) {
        if (badAmount(amount, "bankCredit", reason)) return;
        Session s = require(p, "bankCredit");
        if (s == null) return;
        long t = Coins.creditTenths(amount);
        if (t <= 0) return;
        synchronized (this) {
            LoadedProfile lp = s.profile;
            long n = Coins.addCapped(lp.bank, t);
            if (n < 0) return;
            lp.bank = n;
            persist(s, false, true, List.of(row(s, "bank", t, n, reason)));
        }
    }

    /** Admin: set the bank to an exact value (ignores capacity). */
    public void setBank(Player p, double amount, String reason) {
        if (badAmount(amount, "setBank", reason)) return;
        Session s = require(p, "setBank");
        if (s == null) return;
        synchronized (this) {
            LoadedProfile lp = s.profile;
            long target = Coins.creditTenths(amount);
            long delta = target - lp.bank;
            if (delta == 0) return;
            lp.bank = target;
            persist(s, false, true, List.of(row(s, "bank", delta, target, reason)));
        }
    }

    // ---- bits ----------------------------------------------------------------------------------------

    @Override
    public long bits(Player p) { Session s = session(p); return s == null ? 0 : s.bits; }

    @Override
    public void addBits(Player p, long amount, String reason) {
        if (amount < 0) { badAmount(-1, "addBits", reason); return; }
        if (amount == 0) return;
        Session s = require(p, "addBits");
        if (s == null) return;
        synchronized (this) {
            if (s.evicted) return;
            long n = s.bits > Long.MAX_VALUE / 2 - amount ? s.bits : s.bits + amount;
            long d = n - s.bits;
            s.bits = n;
            persist(s, true, false, List.of(row(s, "bits", d, n, reason)));
        }
    }

    @Override
    public boolean spendBits(Player p, long amount, String reason) {
        if (amount < 0) { badAmount(-1, "spendBits", reason); return false; }
        if (amount == 0) return true;
        Session s = require(p, "spendBits");
        if (s == null) return false;
        synchronized (this) {
            if (s.evicted || s.bits < amount) return false;
            s.bits -= amount;
            persist(s, true, false, List.of(row(s, "bits", -amount, s.bits, reason)));
        }
        return true;
    }

    /** Admin: set bits exactly. */
    public void setBits(Player p, long amount, String reason) {
        if (amount < 0) return;
        Session s = require(p, "setBits");
        if (s == null) return;
        synchronized (this) {
            long d = amount - s.bits;
            if (d == 0) return;
            s.bits = amount;
            persist(s, true, false, List.of(row(s, "bits", d, amount, reason)));
        }
    }

    @Override
    public String format(double coins) { return Coins.format(coins); }

    // ---- ledger --------------------------------------------------------------------------------------

    /** Newest last. In-memory rows since boot (cap: economy.ledger-memory per player). */
    public List<LedgerRow> recent(UUID u, int n) {
        Deque<LedgerRow> d = recentByPlayer.get(u);
        if (d == null) return List.of();
        List<LedgerRow> all;
        synchronized (d) { all = new ArrayList<>(d); }
        return all.size() <= n ? all : all.subList(all.size() - n, all.size());
    }

    /** Newest last, from the database. Completes on the DB thread. */
    public CompletableFuture<List<LedgerRow>> ledgerFromDb(UUID u, int n) {
        return db.query(c -> {
            List<LedgerRow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT time, profile_id, currency, delta, balance_after, reason FROM economy_ledger WHERE uuid = ? ORDER BY id DESC LIMIT ?")) {
                ps.setString(1, u.toString());
                ps.setInt(2, Math.max(1, Math.min(500, n)));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new LedgerRow(rs.getLong(1), rs.getString(2), u, rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getString(6)));
                    }
                }
            }
            java.util.Collections.reverse(out);
            return out;
        });
    }

    public static String formatRow(LedgerRow r) {
        String amt = r.currency().equals("bits") ? (r.delta() >= 0 ? "+" : "") + r.delta() : (r.delta() >= 0 ? "+" : "") + Coins.formatTenths(r.delta());
        String after = r.currency().equals("bits") ? Long.toString(r.after()) : Coins.formatTenths(r.after());
        String when = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
                .format(java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(r.time()), java.time.ZoneId.systemDefault()));
        return when + " " + r.currency() + " " + amt + " -> " + after + " (" + r.reason() + ")";
    }
}
