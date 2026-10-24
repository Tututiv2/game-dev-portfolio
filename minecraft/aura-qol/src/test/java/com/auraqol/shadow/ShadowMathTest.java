package com.auraqol.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** RankEta, ServerClock, RequestLimiter, ProfileCard, ActiveLedger, ShadowFmt. */
class ShadowMathTest {

    private static final long S = 1000;

    // ------------------------------------------------------------ RankEta

    @Test
    void rankEtaEdgeCases() {
        // $1,000 left at $6,000/h = 10 min
        RankEta.Result r = RankEta.of(400_000, 500_000, 600_000);
        assertEquals(RankEta.Kind.ETA, r.kind());
        assertEquals(10 * 60 * S, r.etaMs());
        // rate 0, negative, NaN: hidden, never infinity
        assertEquals(RankEta.Kind.HIDDEN, RankEta.of(0, 500_000, 0).kind());
        assertEquals(RankEta.Kind.HIDDEN, RankEta.of(0, 500_000, -100_000).kind());
        assertEquals(RankEta.Kind.HIDDEN, RankEta.of(0, 500_000, Double.NaN).kind());
        assertEquals(RankEta.Kind.HIDDEN, RankEta.of(0, 500_000, 50).kind());
        // already affordable, whatever the rate
        assertEquals(RankEta.Kind.AFFORDABLE, RankEta.of(500_000, 500_000, 0).kind());
        assertEquals(RankEta.Kind.AFFORDABLE, RankEta.of(900_000, 500_000, -1).kind());
        // max rank, unknown balance
        assertEquals(RankEta.Kind.HIDDEN, RankEta.of(900_000, -1, 1e9).kind());
        assertEquals(RankEta.Kind.HIDDEN, RankEta.of(Long.MIN_VALUE, 500_000, 1e9).kind());
        // weeks away: hidden rather than a meaningless countdown
        assertEquals(RankEta.Kind.HIDDEN, RankEta.of(0, Long.MAX_VALUE / 2, 1e6).kind());
        // huge cents stay exact enough
        assertEquals(RankEta.Kind.ETA, RankEta.of(0, 98_765_432_100L, 98_765_432_100.0).kind());
        assertEquals(3_600_000, RankEta.of(0, 98_765_432_100L, 98_765_432_100.0).etaMs());
    }

    // ------------------------------------------------------------ ServerClock

    @Test
    void serverClockCorrectsForADifferentPcClock() {
        ServerClock c = new ServerClock();
        // the PC is 2 minutes slow
        c.sync(10_000_000, 10_000_000 - 120_000);
        assertTrue(c.synced());
        assertEquals(10_000_000 + 5000, c.serverNow(10_000_000 - 120_000 + 5000));
        assertEquals(55_000, c.remaining(10_060_000, 10_000_000 - 120_000 + 5000));
        assertEquals(0, c.remaining(9_000_000, 10_000_000));
        assertEquals(Long.MAX_VALUE, c.remaining(0, 123));
        c.reset();
        assertFalse(c.synced());
        assertEquals(0, c.offsetMs());
    }

    // ------------------------------------------------------------ RequestLimiter

    @Test
    void limiterEnforcesGapBurstAndRetryAfter() {
        RequestLimiter l = new RequestLimiter(6);
        assertEquals(0, l.tryAcquire(0));
        // too soon after the last one
        assertTrue(l.tryAcquire(500) > 0);
        assertEquals(0, l.tryAcquire(1000));
        assertEquals(0, l.tryAcquire(2000));
        // burst of 3 used, refill is 0.1/s
        long w = l.tryAcquire(3000);
        assertTrue(w > 0, "burst exhausted");
        assertEquals(0, l.tryAcquire(3000 + w));
        // the server says wait 10 s
        l.rateLimited(10_000, 10_000);
        assertTrue(l.tryAcquire(15_000) >= 5000);
        assertEquals(0, l.tryAcquire(20_000));
    }

    @Test
    void limiterUsesADefaultWhenTheServerSaysZero() {
        RequestLimiter l = new RequestLimiter(0);
        assertEquals(0, l.tryAcquire(0));
        assertEquals(0, l.tryAcquire(1000));
        assertEquals(0, l.tryAcquire(2000));
        assertTrue(l.tryAcquire(3000) > 0);
    }

    @Test
    void limiterOfOnePerMinuteHasNoBurst() {
        RequestLimiter l = new RequestLimiter(1);
        assertEquals(0, l.tryAcquire(0));
        long w = l.tryAcquire(2000);
        assertTrue(w >= 55_000, "wait " + w);
    }

    // ------------------------------------------------------------ ProfileCard

    @Test
    void profileRowsAreGroupedInOrderWithUnknownsUnderOther() {
        Msg.Response r = new Msg.Response(7, 0, 0, "playerone", "9e93b8ee-50df-5fe3-9601-11b243607a93", true, List.of(
                new Msg.Row("balance", "Balance", "$987.6M"),
                new Msg.Row("rank", "Rank", "C (Prestige 3)"),
                new Msg.Row("pets", "Best pet", "Mythic Wolf"),
                new Msg.Row("skills", "Mining", "17"),
                new Msg.Row("skills", "Fishing", "50"),
                new Msg.Row("RANK", "Prestige", "3")));
        ProfileCard.Card c = ProfileCard.of(r);
        assertEquals("playerone", c.name());
        assertTrue(c.online());
        List<String> titles = c.sections().stream().map(ProfileCard.Section::title).toList();
        assertEquals(List.of("Rank", "Skills", "Balance", "Other"), titles);
        assertEquals(2, c.sections().get(0).lines().size());
        assertEquals("Mining", c.sections().get(1).lines().get(0).label());
        assertEquals("Mythic Wolf", c.sections().get(3).lines().get(0).value());
    }

    @Test
    void emptyProfileHasNoSections() {
        ProfileCard.Card c = ProfileCard.of(new Msg.Response(1, 0, 0, "x", "", false, List.of()));
        assertTrue(c.sections().isEmpty());
    }

    // ------------------------------------------------------------ ActiveLedger

    @Test
    void ledgerMergesASecondAndWindowsByActiveTime() {
        ActiveLedger l = new ActiveLedger(31 * 60_000L, 64);
        for (int i = 0; i < 100; i++) {
            l.add(10_000 + i, "selling", 1);
        }
        assertEquals(1, l.size(), "same key, same second -> one entry");
        l.add(10_500, "greed", 5);
        assertEquals(2, l.size());
        l.add(70_000, "selling", 10);
        assertEquals(10, l.sum(70_000, 30_000), 1e-9);
        assertEquals(115, l.sum(70_000, 120_000), 1e-9);
        assertEquals(115, l.sessionTotal(), 1e-9);
        assertEquals(110.0, l.sessionByKey().get("selling"), 1e-9);
        // pruned past keepMs, session totals stay
        l.prune(70_000 + 31 * 60_000L);
        assertEquals(1, l.size());
        assertEquals(115, l.sessionTotal(), 1e-9);
    }

    // ------------------------------------------------------------ ShadowFmt

    @Test
    void formatting() {
        assertEquals("$12.5k", ShadowFmt.money(1_250_000));
        assertEquals("-$5", ShadowFmt.money(-500));
        assertEquals("$0.01", ShadowFmt.money(1));
        assertEquals("$988M", ShadowFmt.money(98765432100L));
        assertEquals("+$12.5k", ShadowFmt.signedMoney(1_250_000));
        assertEquals("III", ShadowFmt.roman(3));
        assertEquals("XIV", ShadowFmt.roman(14));
        assertEquals("0", ShadowFmt.roman(0));
        assertEquals("42.5%", ShadowFmt.permille(425));
        assertEquals("100%", ShadowFmt.permille(1000));
        assertEquals("0%", ShadowFmt.permille(-3));
    }
}
