package com.skyclone.core.foundation;

import com.skyclone.core.foundation.ProfileModel.ProfileRow;
import com.skyclone.core.foundation.ProfileModel.Snapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the real profile SQL and the real single-thread database against a temp SQLite file. */
class ProfileStoreTest {

    private File dir;
    private SqliteDatabase db;
    private final Logger log = Logger.getLogger("test");

    @BeforeEach
    void open() throws Exception {
        dir = Files.createTempDirectory("skyclone-test").toFile();
        db = new SqliteDatabase(new File(dir, "t.db"), log);
        db.open();
        db.migrate("core", 1, ProfileStore.SCHEMA_V1);
    }

    @AfterEach
    void close() {
        db.close(10);
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }

    private <T> T tx(SqliteDatabase.SqlWork<T> w) throws Exception { return db.transaction(w).get(10, TimeUnit.SECONDS); }

    private boolean txb(SqliteDatabase.SqlWork<Boolean> w) throws Exception { return tx(w); }

    private int txi(SqliteDatabase.SqlWork<Integer> w) throws Exception { return tx(w); }

    @Test
    void migrateIsIdempotentAndVersioned() throws Exception {
        db.migrate("core", 1, ProfileStore.SCHEMA_V1);                                   // no-op
        db.migrate("mod", 1, "CREATE TABLE mod_x (a INTEGER)");
        db.migrate("mod", 1, "CREATE TABLE mod_x (a INTEGER)");                           // would fail if re-run
        db.migrate("mod", 2, "ALTER TABLE mod_x ADD COLUMN b INTEGER");
        int v = tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT version FROM schema_versions WHERE module = 'mod'");
                 ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : -1; }
        });
        assertEquals(2, v);
        assertThrows(IllegalStateException.class, () -> db.migrate("bad", 1, "CREATE TABLE ("));
    }

    @Test
    void transactionsRollBackAndNest() throws Exception {
        tx(c -> { c.createStatement().executeUpdate("CREATE TABLE t (v INTEGER)"); return null; });
        CompletableFuture<Object> f = db.transaction(c -> {
            c.createStatement().executeUpdate("INSERT INTO t VALUES (1)");
            throw new IllegalStateException("boom");
        });
        assertThrows(Exception.class, () -> f.get(5, TimeUnit.SECONDS));
        // nested: inner failure rolls back only the inner part
        tx(c -> {
            c.createStatement().executeUpdate("INSERT INTO t VALUES (2)");
            CompletableFuture<Object> inner = db.transaction(c2 -> {
                c2.createStatement().executeUpdate("INSERT INTO t VALUES (3)");
                throw new IllegalStateException("inner");
            });
            assertTrue(inner.isCompletedExceptionally());
            return null;
        });
        List<Integer> rows = tx(c -> {
            List<Integer> out = new ArrayList<>();
            try (ResultSet rs = c.createStatement().executeQuery("SELECT v FROM t ORDER BY v")) { while (rs.next()) out.add(rs.getInt(1)); }
            return out;
        });
        assertEquals(List.of(2), rows);
    }

    @Test
    void queueIsFifo() throws Exception {
        tx(c -> { c.createStatement().executeUpdate("CREATE TABLE q (v INTEGER)"); return null; });
        List<CompletableFuture<Object>> fs = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            int n = i;
            fs.add(db.transaction(c -> { c.createStatement().executeUpdate("INSERT INTO q VALUES (" + n + ")"); return null; }));
        }
        int last = tx(c -> {
            try (ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM q")) { return rs.next() ? rs.getInt(1) : -1; }
        });
        assertEquals(200, last, "a read queued after 200 writes must see all of them");
    }

    @Test
    void firstLoginCreatesOneProfileAndRelogFindsIt() throws Exception {
        UUID u = UUID.randomUUID();
        Snapshot a = tx(c -> ProfileStore.readOrCreate(c, u, "Steve", 0, log));
        assertTrue(a.firstJoin);
        assertTrue(ProfileModel.FRUITS.contains(a.profileName));
        assertEquals(List.of(u), a.members);
        tx(c -> { ProfileStore.markSeen(c, a.profileId, u); return null; });
        Snapshot b = tx(c -> ProfileStore.readOrCreate(c, u, "Steve", 0, log));
        assertEquals(a.profileId, b.profileId);
        assertFalse(b.firstJoin);
        assertEquals(1, txi(c -> ProfileStore.profileCount(c, u)));
    }

    @Test
    void memberDataRoundTrip() throws Exception {
        UUID u = UUID.randomUUID();
        Snapshot a = tx(c -> ProfileStore.readOrCreate(c, u, "Alex", 0, log));
        tx(c -> { ProfileStore.upsertMember(c, a.profileId, u, "skills", "{\"xp\":1}"); return null; });
        tx(c -> { ProfileStore.upsertMember(c, a.profileId, u, "skills", "{\"xp\":2}"); return null; });
        Snapshot b = tx(c -> ProfileStore.readOrCreate(c, u, "Alex", 0, log));
        assertEquals("{\"xp\":2}", b.memberJson.get("skills"));
    }

    @Test
    void extraProfilesSwitchAndUniqueNames() throws Exception {
        UUID u = UUID.randomUUID();
        Snapshot first = tx(c -> ProfileStore.readOrCreate(c, u, "Sam", 0, log));
        Set<String> names = new HashSet<>();
        names.add(first.profileName);
        String second = null;
        for (int i = 0; i < 4; i++) {
            String id = tx(c -> ProfileStore.createProfile(c, u, "Sam", false));
            if (second == null) second = id;
            names.add(tx(c -> ProfileStore.profileName(c, id)));
        }
        assertEquals(5, names.size(), "a player's profiles get distinct fruit names");
        assertEquals(first.profileId, tx(c -> ProfileStore.readOrCreate(c, u, "Sam", 0, log)).profileId, "new profiles are not active");
        String target = second;
        assertTrue(txb(c -> ProfileStore.switchTo(c, u.toString(), target)));
        assertEquals(target, tx(c -> ProfileStore.readOrCreate(c, u, "Sam", 0, log)).profileId);
        List<ProfileRow> rows = tx(c -> ProfileStore.listProfiles(c, u));
        assertEquals(5, rows.size());
        assertEquals(1, rows.stream().filter(ProfileRow::active).count());
        assertFalse(txb(c -> ProfileStore.switchTo(c, u.toString(), "not-a-profile")));
    }

    @Test
    void coopJoinCapsAndLeave() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID friend = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        Snapshot o = tx(c -> ProfileStore.readOrCreate(c, owner, "Owner", 0, log));
        Snapshot f = tx(c -> ProfileStore.readOrCreate(c, friend, "Friend", 0, log));
        assertTrue(txb(c -> ProfileStore.addMember(c, o.profileId, friend, 2, 5)));
        assertFalse(txb(c -> ProfileStore.addMember(c, o.profileId, friend, 2, 5)), "already a member");
        tx(c -> ProfileStore.readOrCreate(c, third, "Third", 0, log));
        assertFalse(txb(c -> ProfileStore.addMember(c, o.profileId, third, 2, 5)), "co-op full at 2");

        Snapshot fs = tx(c -> ProfileStore.readOrCreate(c, friend, "Friend", 0, log));
        assertEquals(o.profileId, fs.profileId, "joining makes the co-op active");
        assertTrue(fs.firstJoin, "first load of a co-op profile is a first join for that member");
        assertEquals(2, fs.members.size());

        tx(c -> { ProfileStore.leave(c, o.profileId, friend.toString()); return null; });
        Snapshot back = tx(c -> ProfileStore.readOrCreate(c, friend, "Friend", 0, log));
        assertEquals(f.profileId, back.profileId, "leaving returns to the oldest remaining profile");
        assertEquals(List.of(owner), tx(c -> ProfileStore.membersOf(c, o.profileId)));

        // profile cap
        UUID busy = UUID.randomUUID();
        tx(c -> ProfileStore.readOrCreate(c, busy, "Busy", 0, log));
        assertFalse(txb(c -> ProfileStore.addMember(c, o.profileId, busy, 5, 1)), "at the profile cap");
    }

    @Test
    void leavingYourOnlyProfileCreatesAFreshOne() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID guest = UUID.randomUUID();
        Snapshot o = tx(c -> ProfileStore.readOrCreate(c, owner, "Owner", 0, log));
        assertTrue(txb(c -> ProfileStore.addMember(c, o.profileId, guest, 5, 5)));
        tx(c -> { ProfileStore.leave(c, o.profileId, guest.toString()); return null; });
        Snapshot g = tx(c -> ProfileStore.readOrCreate(c, guest, "Guest", 0, log));
        assertNotEquals(o.profileId, g.profileId);
        assertTrue(g.firstJoin);
    }

    @Test
    void danglingMembershipIsClosedNotFatal() throws Exception {
        UUID u = UUID.randomUUID();
        tx(c -> {
            c.createStatement().executeUpdate("INSERT INTO profile_members(profile_id, uuid, joined, active) VALUES('ghost', '" + u + "', 1, 1)");
            return null;
        });
        Snapshot s = tx(c -> ProfileStore.readOrCreate(c, u, "Ghosted", 0, log));
        assertNotEquals("ghost", s.profileId);
        assertEquals(1, txi(c -> ProfileStore.profileCount(c, u)));
    }

    @Test
    void closeDrainsQueuedWork() throws Exception {
        tx(c -> { c.createStatement().executeUpdate("CREATE TABLE d (v INTEGER)"); return null; });
        for (int i = 0; i < 100; i++) {
            int n = i;
            db.transaction(c -> { c.createStatement().executeUpdate("INSERT INTO d VALUES (" + n + ")"); return null; });
        }
        db.close(10);
        SqliteDatabase again = new SqliteDatabase(new File(dir, "t.db"), log);
        again.open();
        int n = again.query(c -> {
            try (ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM d")) { return rs.next() ? rs.getInt(1) : -1; }
        }).get(5, TimeUnit.SECONDS);
        again.close(5);
        db = again; // AfterEach closes it again (no-op)
        assertEquals(100, n);
    }
}
