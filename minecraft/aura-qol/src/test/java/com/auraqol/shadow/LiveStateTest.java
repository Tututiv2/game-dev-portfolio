package com.auraqol.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class LiveStateTest {

    private static final long S = 1000;
    private static final long M = 60 * S;
    private static final List<String> ALL = List.of("breaks", "income", "balance", "progress", "boosts", "skill",
            "contract", "area", "lookup");

    private static LiveState welcomed() {
        LiveState st = new LiveState(60 * S);
        st.welcome(new Msg.Welcome(1, true, 1_000_000, ALL, 250, 30), 1_000_000);
        return st;
    }

    private static Msg.Breaks breaks(long n, long cents) {
        return new Msg.Breaks(n, cents, List.of(new Msg.BlockCount("STONE", n)));
    }

    private static Msg.Income income(String id, String name, long cents) {
        return new Msg.Income(id, name, cents, 0);
    }

    @Test
    void nothingAppliesBeforeAnAcceptedWelcome() {
        LiveState st = new LiveState(60 * S);
        st.apply(breaks(10, 100), 0);
        st.apply(new Msg.Area("Mine C"), 0);
        assertFalse(st.seenBreaks());
        assertNull(st.area());
        st.welcome(new Msg.Welcome(2, false, 0, List.of(), 0, 0), 0);
        assertFalse(st.active());
        st.apply(new Msg.Area("Mine C"), 0);
        assertNull(st.area());
        assertFalse(st.has("breaks"));
    }

    @Test
    void featuresGateThePanels() {
        LiveState st = new LiveState(60 * S);
        st.welcome(new Msg.Welcome(1, true, 5, List.of("breaks", "income"), 250, 30), 5);
        assertTrue(st.has("breaks"));
        assertFalse(st.has("boosts"));
    }

    @Test
    void blocksAndValuePerHourOverActiveTime() {
        LiveState st = welcomed();
        // 4 flushes a second for 60 s: 10 blocks and $1.00 each flush
        for (long t = 0; t <= 60 * S; t += 250) {
            st.apply(breaks(10, 100), t);
        }
        // 241 flushes over 60 s of active time
        assertEquals(2410, st.sessionBlocks());
        assertEquals(24100, st.sessionValueCents());
        double bph = st.blocksPerHour(60 * S, false, 10 * M);
        assertEquals(2410 * 60.0, bph, 1e-6);
        assertEquals(24100 * 60.0, st.valueCentsPerHour(60 * S, false, 10 * M), 1e-6);
        // recent window agrees with the session while the session is shorter than it
        assertEquals(bph, st.blocksPerHour(60 * S, true, 10 * M), 2410 * 60.0 * 0.02);
    }

    @Test
    void anAfkGapDoesNotDragTheRateDown() {
        LiveState st = welcomed();
        for (long t = 0; t <= 60 * S; t += 1000) {
            st.apply(breaks(5, 50), t);
        }
        double before = st.blocksPerHour(60 * S, true, 10 * M);
        // 20 minutes away: nothing at all happens
        long back = 60 * S + 20 * M;
        assertEquals(before, st.blocksPerHour(back, true, 10 * M), 1e-6);
        assertEquals(before, st.blocksPerHour(back, false, 10 * M), 1e-6);
        // and coming back carries on from the same window
        st.apply(breaks(5, 50), back);
        assertEquals(60 * S, st.miningClock().activeMs(back), 1);
    }

    @Test
    void noRateUntilFifteenSecondsOfActiveTime() {
        LiveState st = welcomed();
        st.apply(breaks(100, 1000), 0);
        st.apply(breaks(100, 1000), 10 * S);
        assertEquals(0, st.blocksPerHour(10 * S, true, M));
        assertFalse(st.miningHasRate(10 * S));
        st.apply(breaks(100, 1000), 20 * S);
        assertTrue(st.blocksPerHour(20 * S, true, M) > 0);
    }

    /** Money per hour = value mined into the inventory + other payouts; selling is never added twice. */
    @Test
    void moneyPerHourCountsMinedValueAndNeverCountsSellingTwice() {
        LiveState st = welcomed();
        st.activity(0);
        st.apply(breaks(1000, 9_999_999), 1 * S);
        st.apply(income("selling", "Selling", 1_250_000), 5 * S);   // turns mined ore into cash
        st.apply(income("enchant_greed", "Greed Enchant", 300), 6 * S);
        st.apply(income("enchant_greed", "Greed Enchant", 300), 7 * S);
        st.apply(income("admin_grant", "Admin", -500), 8 * S);
        st.activity(30 * S);
        long now = 30 * S;
        for (boolean recent : new boolean[]{true, false}) {
            double head = st.incomeCentsPerHour(now, recent, 10 * M);
            double sum = 0;
            for (LiveState.Earning e : st.incomeRows(now, recent, 10 * M)) {
                sum += e.centsPerHour();
            }
            assertEquals(head, sum, 1e-6, "rows add up (recent=" + recent + ")");
            // 9,999,999 mined + 600 greed - 500 admin = 10,000,099 cents in 30 s of active time
            assertEquals(10_000_099 * 120.0, head, 1e-6);
        }
        List<LiveState.Earning> rows = st.incomeRows(now, true, 10 * M);
        assertEquals("Mined", rows.get(0).label());
        assertEquals(3, rows.size());
        assertTrue(rows.stream().noneMatch(r -> r.label().equals("Selling")));
        assertEquals(10_000_099, st.sessionIncomeCents());
        assertEquals(1_250_000, st.sessionSoldCents());
    }

    @Test
    void thePanelFollowsTheLastActivityAndCombatValueIsMoney() {
        LiveState st = welcomed();
        assertEquals("Mining", st.activity());
        st.activity(0);
        st.apply(breaks(10, 1000), S);
        assertEquals("Mining", st.activity());
        st.apply(new Msg.Combat(3, 4500, List.of(new Msg.MobCount("ZOMBIE", 3))), 2 * S);
        assertEquals("Combat", st.activity());
        assertEquals(3, st.sessionKills());
        assertEquals(4500, st.sessionCombatValueCents());
        st.apply(breaks(0, 0), 3 * S);                       // an empty flush does not switch back
        assertEquals("Combat", st.activity());
        st.apply(breaks(5, 500), 4 * S);
        assertEquals("Mining", st.activity());
        st.activity(30 * S);
        List<LiveState.Earning> rows = st.incomeRows(30 * S, false, 10 * M);
        assertTrue(rows.stream().anyMatch(r -> r.label().equals("Combat")), rows.toString());
        assertEquals(1000 + 4500 + 500, st.sessionIncomeCents());
    }

    @Test
    void sellingAloneIsNotMoneyMade() {
        LiveState st = welcomed();
        st.activity(0);
        st.apply(income("selling", "Selling", 5_000_000), 5 * S);
        st.activity(30 * S);
        assertEquals(0, st.incomeCentsPerHour(30 * S, false, 10 * M));
        assertFalse(st.seenIncome());
    }

    @Test
    void moreSourcesThanRowsFoldIntoOtherAndStillAddUp() {
        LiveState st = welcomed();
        st.activity(0);
        for (int i = 0; i < 10; i++) {
            st.apply(income("src" + i, "Source " + i, (i + 1) * 1000L), (i + 1) * S);
        }
        st.activity(20 * S);
        List<LiveState.Earning> rows = st.incomeRows(20 * S, true, 10 * M);
        assertEquals(LiveState.MAX_ROWS, rows.size());
        assertEquals("Other", rows.get(rows.size() - 1).label());
        assertEquals("Source 9", rows.get(0).label());
        double sum = rows.stream().mapToDouble(LiveState.Earning::centsPerHour).sum();
        assertEquals(st.incomeCentsPerHour(20 * S, true, 10 * M), sum, 1e-6);
    }

    @Test
    void aSnapshotIsStateNotEvents() {
        LiveState st = welcomed();
        Msg.Snapshot snap = new Msg.Snapshot(List.of(breaks(128, 5000), income("selling", "Selling", 1_250_000),
                new Msg.Balance(98765432100L, List.of()), new Msg.Progress("C", "D", 5_000_000, 3, 1234567),
                new Msg.Area("Mine C"), new Msg.Skill("mining", "Mining", 17, 3400, 12000)));
        st.apply(snap, 0);
        st.apply(snap, 5 * S); // world change: sent again
        assertEquals(0, st.sessionBlocks());
        assertEquals(0, st.sessionIncomeCents());
        assertEquals(98765432100L, st.balance().balanceCents());
        assertEquals("D", st.progress().nextRankDisplay());
        assertEquals("Mine C", st.area());
        assertEquals(17, st.training(5 * S, true, M).level());
        assertEquals(0, st.training(5 * S, true, M).xpPerHour());
    }

    @Test
    void skillXpPerHourAndTimeToNextLevelIncludingALevelUp() {
        LiveState st = welcomed();
        st.apply(new Msg.Skill("mining", "Mining", 17, 3400, 12000), 0);
        st.apply(new Msg.Skill("mining", "Mining", 17, 3900, 12000), 10 * S);
        st.apply(new Msg.Skill("mining", "Mining", 17, 11900, 12000), 20 * S);
        // level-up: 100 left of 17 + 400 into 18 = 500 gained
        st.apply(new Msg.Skill("mining", "Mining", 18, 400, 13000), 30 * S);
        LiveState.SkillView v = st.training(30 * S, false, 10 * M);
        assertEquals(18, v.level());
        // gained 500 + 8000 + 500 = 9000 over 20 s of active time (clock starts at the first gain)
        assertEquals(9000 * 180.0, v.xpPerHour(), 1e-6);
        assertEquals(Math.round((13000 - 400) / (9000 * 180.0) * 3_600_000.0), v.etaMs());
    }

    @Test
    void theTrainedSkillIsTheOneGainingXpNotTheLastSent() {
        LiveState st = welcomed();
        st.apply(new Msg.Skill("mining", "Mining", 5, 100, 1000), 0);
        st.apply(new Msg.Skill("mining", "Mining", 5, 200, 1000), S);
        st.apply(new Msg.Skill("fishing", "Fishing", 50, 0, 0), 2 * S); // a re-send, no gain
        assertEquals("Mining", st.training(3 * S, true, M).displayName());
        // long after the last gain, the last one sent wins
        assertEquals("Fishing", st.training(3 * S + LiveState.TRAINING_MS + S, true, M).displayName());
        assertTrue(st.training(3 * S + LiveState.TRAINING_MS + S, true, M).maxed());
    }

    @Test
    void maxedSkillHasNoEta() {
        LiveState st = welcomed();
        st.apply(new Msg.Skill("fishing", "Fishing", 50, 0, 0), 0);
        assertEquals(-1, st.training(0, true, M).etaMs());
    }

    @Test
    void boostsCountDownFromServerTimeAndExpireLocally() {
        LiveState st = new LiveState(60 * S);
        // the server's clock is 5 s ahead of this PC
        st.welcome(new Msg.Welcome(1, true, 1_000_005_000L, ALL, 250, 30), 1_000_000_000L);
        st.apply(new Msg.Boosts(List.of(new Msg.Boost("sell_2x", "Sell Boost", "x2.0", 1_000_065_000L),
                new Msg.Boost("global", "Global Token Rush", "+50%", 0))), 0);
        List<LiveState.BoostView> v = st.boosts(1_000_000_000L);
        assertEquals(2, v.size());
        assertEquals(60_000, v.get(0).remainingMs());
        assertTrue(v.get(1).permanent());
        // 61 s later (local) the sell boost is gone without any packet
        assertEquals(1, st.boosts(1_000_061_000L).size());
        // an empty BOOSTS replaces, never merges
        st.apply(new Msg.Boosts(List.of()), 0);
        assertTrue(st.boosts(1_000_000_000L).isEmpty());
        assertTrue(st.seenBoosts());
    }

    @Test
    void contractEtaFromTheBarsPace() {
        LiveState st = welcomed();
        st.apply(new Msg.Contract(Msg.Contract.HUNTING, "Cave Lurker", 3, 100), 0);
        st.apply(new Msg.Contract(Msg.Contract.HUNTING, "Cave Lurker", 3, 200), 10 * S);
        st.apply(new Msg.Contract(Msg.Contract.HUNTING, "Cave Lurker", 3, 300), 20 * S);
        // 200 permille in 20 s -> 700 left = 70 s
        assertEquals(70 * S, st.contractEtaMs(20 * S), 10);
        st.apply(new Msg.Contract(Msg.Contract.BOSS_UP, "Cave Lurker", 3, 1000), 30 * S);
        assertEquals(-1, st.contractEtaMs(30 * S));
        st.apply(new Msg.Contract(Msg.Contract.NONE, "", 0, 0), 40 * S);
        assertEquals(Msg.Contract.NONE, st.contract().state());
    }

    @Test
    void rankEtaUsesMoneyPerHourIncludingMinedValue() {
        LiveState st = welcomed();
        st.apply(new Msg.Progress("C", "D", 5_000_000, 3, 0), 0);
        st.apply(new Msg.Balance(1_000_000, List.of()), 0);
        st.activity(0);
        // selling alone makes no money (it was counted when mined): no ETA
        st.apply(income("selling", "Selling", 1_000_000), 20 * S);
        assertEquals(RankEta.Kind.HIDDEN, st.rankEta(20 * S, true, 10 * M).kind());
        // $10,000 mined over 20 s of active time = $1.8M/h; $40,000 left = 80 s
        st.apply(breaks(1000, 1_000_000), 20 * S);
        RankEta.Result r = st.rankEta(20 * S, false, 10 * M);
        assertEquals(RankEta.Kind.ETA, r.kind());
        assertEquals(80 * S, r.etaMs(), 100);
        st.apply(new Msg.Balance(5_000_000, List.of()), 21 * S);
        assertEquals(RankEta.Kind.AFFORDABLE, st.rankEta(21 * S, true, 10 * M).kind());
        st.apply(new Msg.Progress("Z", "", -1, 300, 0), 22 * S);
        assertEquals(RankEta.Kind.HIDDEN, st.rankEta(22 * S, true, 10 * M).kind());
    }

    @Test
    void resetKeepsWhatTheServerSaidButClearsTheSession() {
        LiveState st = welcomed();
        st.apply(new Msg.Area("Mine C"), 0);
        for (long t = 0; t <= 30 * S; t += S) {
            st.apply(breaks(1, 1), t);
        }
        st.resetSession();
        assertEquals(0, st.sessionBlocks());
        assertEquals(0, st.blocksPerHour(30 * S, true, M));
        assertEquals("Mine C", st.area());
    }

    @Test
    void passiveIncomeWhileAfkKeepsTheEarningsClockHonest() {
        LiveState st = welcomed();
        st.activity(0);
        // AFK for an hour; a rig pays $100 every minute
        for (long t = M; t <= 60 * M; t += M) {
            st.apply(income("crypto_mining", "Crypto Mining", 10_000), t);
        }
        // $6,000 over ~an hour, not crammed into a frozen instant
        double perHour = st.incomeCentsPerHour(60 * M, false, 10 * M);
        assertEquals(600_000, perHour, 600_000 * 0.03);
        // and the mining clock did not run: no blocks, no time
        assertTrue(st.miningClock().activeMs(60 * M) <= 60 * S);
    }
}
