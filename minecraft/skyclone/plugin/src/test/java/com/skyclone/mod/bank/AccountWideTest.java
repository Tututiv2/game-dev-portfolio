package com.skyclone.mod.bank;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The account-wide copy: snapshots replace (never add), legacy copies merge once without minting bits. */
class AccountWideTest {

    private static BankData.Member member(long cookieUntil, long bitsAvailable, long fame, boolean free, int magicFind) {
        BankData.Member m = new BankData.Member();
        m.cookieUntil = cookieUntil;
        m.bitsAvailable = bitsAvailable;
        m.fame = fame;
        m.freeCookieClaimed = free;
        if (magicFind > 0) m.upgrades.put("magic_find", magicFind);
        m.lastPocketUse = 777;          // per profile: never carried
        m.firstDeposit = true;
        return m;
    }

    @Test
    void snapshotRoundTripsThroughJsonAndReplaces() {
        BankData.Member a = member(5_000, 4_300, 1_200, true, 3);
        a.tradeDay = "2026-10-04";
        a.tradeSentToday = 12_345.5;
        a.building = "accessory_bag";
        a.buildingTier = 2;
        a.buildingDone = 99;
        Gson g = new Gson();
        AccountWide.Snapshot s = g.fromJson(g.toJson(AccountWide.of(a)), AccountWide.Snapshot.class);

        BankData.Member b = member(0, 9_999, 0, false, 5);
        b.lastPocketUse = 1;
        b.firstDeposit = false;
        AccountWide.apply(s, b);
        assertEquals(5_000, b.cookieUntil);
        assertEquals(4_300, b.bitsAvailable, "a newer snapshot REPLACES the pool, it never adds to it");
        assertEquals(1_200, b.fame);
        assertTrue(b.freeCookieClaimed);
        assertEquals(3, b.upgrades.get("magic_find"));
        assertEquals("2026-10-04", b.tradeDay);
        assertEquals(12_345.5, b.tradeSentToday, 1e-9);
        assertEquals("accessory_bag", b.building);
        assertEquals(2, b.buildingTier);
        assertEquals(1, b.lastPocketUse, "per-profile fields are not carried");
        assertEquals(false, b.firstDeposit);
        // the applied copy is independent of the snapshot
        s.upgrades.put("magic_find", 1);
        assertEquals(3, b.upgrades.get("magic_find"));
    }

    @Test
    void legacyCopyMergesWithoutMintingBits() {
        AccountWide.Snapshot s = AccountWide.of(member(1_000, 300, 500, false, 1));
        BankData.Member legacy = member(9_000, 4_800, 200, true, 4);
        legacy.upgrades.put("sack_of_sacks", 2);
        AccountWide.merge(s, legacy);
        assertEquals(9_000, legacy.cookieUntil, "the longer buff survives");
        assertEquals(300, legacy.bitsAvailable, "bits waiting come from the account snapshot only");
        assertEquals(500, legacy.fame);
        assertTrue(legacy.freeCookieClaimed, "a free cookie claimed on either side stays claimed");
        assertEquals(4, legacy.upgrades.get("magic_find"));
        assertEquals(2, legacy.upgrades.get("sack_of_sacks"));
        assertEquals(4, s.upgrades.get("magic_find"), "the snapshot carries the merge forward");
    }

    @Test
    void mergeKeepsABuildOnlyWhenItIsStillAhead() {
        AccountWide.Snapshot s = AccountWide.of(member(0, 0, 0, false, 3));
        BankData.Member legacy = member(0, 0, 0, false, 0);
        legacy.building = "magic_find";
        legacy.buildingTier = 2;           // the account already owns III: this build is stale
        legacy.buildingDone = 5;
        AccountWide.merge(s, legacy);
        assertEquals("", legacy.building);

        AccountWide.Snapshot s2 = AccountWide.of(member(0, 0, 0, false, 1));
        BankData.Member l2 = member(0, 0, 0, false, 0);
        l2.building = "magic_find";
        l2.buildingTier = 2;
        l2.buildingDone = 5;
        AccountWide.merge(s2, l2);
        assertEquals("magic_find", l2.building);
        assertEquals(2, l2.buildingTier);
    }
}
