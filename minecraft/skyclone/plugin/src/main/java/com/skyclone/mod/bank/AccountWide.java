package com.skyclone.mod.bank;

import com.google.gson.Gson;
import com.skyclone.api.Sky;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Account-wide state (research 05 section 6: the Boon Buff, bits, fame, account upgrades and the free cookie
 * follow the ACCOUNT, not the profile). The live copy stays in the member key {@code bank} so every other class
 * keeps reading {@code Bank.member(p)}; this class carries it between profiles through the table
 * {@code bank_account_wide}:
 * <ul>
 *   <li>Profile ready: read the newest snapshot (memory first, then the DB) and copy it in if it is newer than
 *       the copy this profile holds. Nothing else of the module runs for that player until it is applied.</li>
 *   <li>Every 20 s, on profile unload (quit, profile switch) and at shutdown: snapshot -> table, only if it changed.
 *       A snapshot is never taken before the load finished, so stale data can never be stamped newer.</li>
 * </ul>
 * Copies replace, they never add, so switching profiles cannot mint bits. A profile whose copy was never synced
 * (data from before Wave 3) is MERGED once instead (max of buff end, fame, upgrade tiers; the free cookie stays
 * claimed if either side claimed it) so nobody loses a buff they paid for on another profile.
 */
final class AccountWide {

    private AccountWide() {}

    /** The fields that follow the account. Gson-friendly; fields are only ever added. */
    static final class Snapshot {
        long cookieUntil;
        boolean cookieExpiredNotified = true;
        long bitsAvailable;
        long lastBitsPayout;
        long fame;
        long bitsSpent;
        boolean freeCookieClaimed;
        int cookiesEaten;
        String tradeDay = "";
        double tradeSentToday;
        long tradesCompleted;
        Map<String, Integer> upgrades = new HashMap<>();
        String building = "";
        int buildingTier;
        long buildingDone;
    }

    private static final Gson GSON = new Gson();
    /** Newest snapshot per player seen this server session: savedAt + json (profile switches skip the DB). */
    private static final Map<UUID, BankStore.AccountRow> CACHE = new ConcurrentHashMap<>();
    /** Player -> profile id whose copy is loaded and may be saved. */
    private static final Map<UUID, String> LOADED = new ConcurrentHashMap<>();
    /** Players whose table row could not be read: they play on the profile's own copy and never save over the table. */
    private static final java.util.Set<UUID> NO_SAVE = ConcurrentHashMap.newKeySet();
    /** Last json written per player (skip unchanged writes). */
    private static final Map<UUID, String> LAST = new ConcurrentHashMap<>();

    static Snapshot of(BankData.Member m) {
        Snapshot s = new Snapshot();
        s.cookieUntil = m.cookieUntil;
        s.cookieExpiredNotified = m.cookieExpiredNotified;
        s.bitsAvailable = m.bitsAvailable;
        s.lastBitsPayout = m.lastBitsPayout;
        s.fame = m.fame;
        s.bitsSpent = m.bitsSpent;
        s.freeCookieClaimed = m.freeCookieClaimed;
        s.cookiesEaten = m.cookiesEaten;
        s.tradeDay = m.tradeDay == null ? "" : m.tradeDay;
        s.tradeSentToday = m.tradeSentToday;
        s.tradesCompleted = m.tradesCompleted;
        s.upgrades = new HashMap<>(m.upgrades == null ? Map.of() : m.upgrades);
        s.building = m.building == null ? "" : m.building;
        s.buildingTier = m.buildingTier;
        s.buildingDone = m.buildingDone;
        return s;
    }

    /** Overwrite the member's copy with the snapshot. */
    static void apply(Snapshot s, BankData.Member m) {
        m.cookieUntil = s.cookieUntil;
        m.cookieExpiredNotified = s.cookieExpiredNotified;
        m.bitsAvailable = s.bitsAvailable;
        m.lastBitsPayout = s.lastBitsPayout;
        m.fame = s.fame;
        m.bitsSpent = s.bitsSpent;
        m.freeCookieClaimed = s.freeCookieClaimed;
        m.cookiesEaten = s.cookiesEaten;
        m.tradeDay = s.tradeDay == null ? "" : s.tradeDay;
        m.tradeSentToday = s.tradeSentToday;
        m.tradesCompleted = s.tradesCompleted;
        m.upgrades = new HashMap<>(s.upgrades == null ? Map.of() : s.upgrades);
        m.building = s.building == null ? "" : s.building;
        m.buildingTier = s.buildingTier;
        m.buildingDone = s.buildingDone;
    }

    /**
     * One-time merge of a never-synced (legacy) profile copy into the account snapshot. Bits still waiting are
     * the snapshot's (no adding, so nothing is minted); everything paid for keeps the better of the two.
     */
    static void merge(Snapshot s, BankData.Member m) {
        Snapshot mine = of(m);
        if (mine.cookieUntil > s.cookieUntil) { s.cookieUntil = mine.cookieUntil; s.cookieExpiredNotified = mine.cookieExpiredNotified; }
        s.fame = Math.max(s.fame, mine.fame);
        s.bitsSpent = Math.max(s.bitsSpent, mine.bitsSpent);
        s.freeCookieClaimed = s.freeCookieClaimed || mine.freeCookieClaimed;
        s.cookiesEaten = Math.max(s.cookiesEaten, mine.cookiesEaten);
        s.tradesCompleted = Math.max(s.tradesCompleted, mine.tradesCompleted);
        if (Objects.equals(s.tradeDay, mine.tradeDay)) s.tradeSentToday = Math.max(s.tradeSentToday, mine.tradeSentToday);
        if (s.upgrades == null) s.upgrades = new HashMap<>();
        for (Map.Entry<String, Integer> e : mine.upgrades.entrySet()) s.upgrades.merge(e.getKey(), e.getValue(), Math::max);
        if ((s.building == null || s.building.isEmpty()) && !mine.building.isEmpty()
                && s.upgrades.getOrDefault(mine.building, 0) < mine.buildingTier) {
            s.building = mine.building;
            s.buildingTier = mine.buildingTier;
            s.buildingDone = mine.buildingDone;
        }
        apply(s, m);
    }

    // ---- lifecycle ---------------------------------------------------------------------------------------------

    /** Profile ready: bring the account-wide copy in, then run {@code then} (the rest of the module's setup). */
    static void load(Player p, Runnable then) {
        UUID id = p.getUniqueId();
        String pid = Sky.profiles().profileId(p);
        BankStore.AccountRow cached = CACHE.get(id);
        if (cached != null) {
            applyRow(p, pid, cached);
            then.run();
            return;
        }
        Bank.store.loadAccount(id).whenComplete((row, err) -> Bank.sync(() -> {
            if (!Bank.ready(p) || !Objects.equals(pid, Sky.profiles().profileId(p))) return;   // left or switched: that ready handles it
            if (err != null) {
                // DB trouble: run on this profile's own copy and never save over the table this session
                Sky.log().warning("[bank] account-wide state of " + p.getName() + " could not be read: " + err);
                NO_SAVE.add(id);
                LOADED.put(id, pid);
                then.run();
                return;
            }
            BankStore.AccountRow r = CACHE.get(id);      // a save may have landed meanwhile (another ready)
            if (r == null || (row != null && row.savedAt() > r.savedAt())) r = row;
            applyRow(p, pid, r);
            then.run();
        }));
    }

    private static void applyRow(Player p, String pid, BankStore.AccountRow row) {
        BankData.Member m = Bank.member(p);
        UUID id = p.getUniqueId();
        if (row != null) {
            Snapshot s = null;
            try { s = GSON.fromJson(row.json(), Snapshot.class); } catch (RuntimeException e) {
                Sky.log().warning("[bank] unreadable account-wide row of " + p.getName() + ": " + e);
            }
            if (s != null) {
                if (m.accountSavedAt == 0) {
                    merge(s, m);
                    m.accountSavedAt = row.savedAt();
                    LOADED.put(id, pid);
                    save(p);                              // the merge may have added something: publish it
                    return;
                }
                CACHE.put(id, row);
                if (row.savedAt() < m.accountSavedAt) {
                    // this profile's copy is NEWER than the table (the row write was lost in a crash): publish it
                    LOADED.put(id, pid);
                    LAST.remove(id);
                    save(p);
                    return;
                }
                if (row.savedAt() > m.accountSavedAt) {
                    apply(s, m);
                    m.accountSavedAt = row.savedAt();
                }
                LAST.put(id, GSON.toJson(of(m)));
            }
        }
        LOADED.put(id, pid);
        if (row == null) save(p);                         // first sight of this account: this profile seeds it
    }

    /** Snapshot this player's copy into the table if it changed (main thread). */
    static void save(Player p) {
        UUID id = p.getUniqueId();
        String pid = LOADED.get(id);
        // no Bank.ready()/isOnline() here: at ProfileUnload on quit the session still exists but the player may already
        // count as offline, and that last snapshot is the one a profile switch depends on
        if (pid == null || NO_SAVE.contains(id) || !pid.equals(Sky.profiles().profileId(p))) return;
        BankData.Member m = Bank.member(p);
        String json = GSON.toJson(of(m));
        if (json.equals(LAST.get(id))) return;
        long savedAt = Math.max(System.currentTimeMillis(), m.accountSavedAt + 1);
        BankStore.AccountRow cached = CACHE.get(id);
        if (cached != null) savedAt = Math.max(savedAt, cached.savedAt() + 1);
        m.accountSavedAt = savedAt;
        BankStore.AccountRow row = new BankStore.AccountRow(savedAt, json);
        CACHE.put(id, row);
        LAST.put(id, json);
        try {
            Bank.store.saveAccount(id, savedAt, json).exceptionally(t -> {
                Sky.log().warning("[bank] account-wide save failed for " + id + ": " + t);
                LAST.remove(id);                          // try again next round
                return null;
            });
        } catch (RuntimeException e) {
            LAST.remove(id);
        }
    }

    /** Profile unload (quit / switch / shutdown): last snapshot, then forget the binding. */
    static void unload(Player p) {
        try { save(p); } finally {
            LOADED.remove(p.getUniqueId());
            LAST.remove(p.getUniqueId());
            NO_SAVE.remove(p.getUniqueId());
        }
    }

    /** The account-wide copy of this player's CURRENT profile is in place (writes to it will not be overwritten). */
    static boolean loaded(Player p) {
        String pid = LOADED.get(p.getUniqueId());
        return pid != null && pid.equals(Sky.profiles().profileId(p));
    }

    static void saveAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { save(p); } catch (Throwable t) { Sky.log().warning("[bank] account-wide save failed for " + p.getName() + ": " + t); }
        }
    }

    /** Admin/info line. */
    static String describe(Player p) {
        UUID id = p.getUniqueId();
        BankStore.AccountRow r = CACHE.get(id);
        return "account-wide: " + (LOADED.containsKey(id) ? "loaded" : "NOT loaded")
                + ", copy stamp " + Bank.member(p).accountSavedAt + ", newest " + (r == null ? "none" : r.savedAt());
    }
}
