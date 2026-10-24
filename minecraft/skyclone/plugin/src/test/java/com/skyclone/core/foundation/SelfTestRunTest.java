package com.skyclone.core.foundation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The slicing that keeps {@code /sb selftest} from tripping Paper's watchdog. A fake clock advances
 * only when a "test" runs, so every number here is exact.
 */
class SelfTestRunTest {

    private static final long MS = 1_000_000L;

    /** A clock that each fake test advances by its own cost. */
    private static final class FakeClock { long now; }

    private static Map<String, Supplier<String>> tests(FakeClock c, int n, long costMs) {
        Map<String, Supplier<String>> m = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            final int k = i;
            m.put("mod.t" + i, () -> { c.now += costMs * MS; return k == 3 ? "broken" : null; });
        }
        return m;
    }

    @Test
    void neverHoldsTheThreadMuchPastTheBudget() {
        FakeClock c = new FakeClock();
        // 357 tests at 30 ms each = 10.7 s, the run that tripped the watchdog in one tick.
        SelfTestRun run = new SelfTestRun(tests(c, 357, 30), null, () -> c.now, null);
        int slices = 0;
        long worst = 0;
        while (!run.done()) {
            long t0 = c.now;
            run.step(25 * MS);
            worst = Math.max(worst, c.now - t0);
            slices++;
        }
        assertEquals(357, run.completed());
        assertEquals(357, slices, "a test longer than the budget gets a slice to itself");
        assertEquals(30 * MS, worst, "a slice never starts another test once the budget is spent");
        assertEquals("SELFTEST: 356 passed, 1 failed", run.summary());
    }

    @Test
    void fastTestsShareASlice() {
        FakeClock c = new FakeClock();
        SelfTestRun run = new SelfTestRun(tests(c, 100, 1), null, () -> c.now, null);
        List<SelfTestRun.Result> first = run.step(25 * MS);
        assertEquals(25, first.size());
        assertFalse(run.done());
        assertTrue(run.progressLine().startsWith(SelfTestRun.RUNNING + " 25/100"));
        while (!run.done()) run.step(25 * MS);
        assertEquals(4, run.slices());
    }

    @Test
    void aSmallFilteredRunFinishesInOneSliceLikeBefore() {
        FakeClock c = new FakeClock();
        Map<String, Supplier<String>> all = tests(c, 50, 1);
        all.put("other.ok", () -> null);
        SelfTestRun run = new SelfTestRun(all, "OTHER", () -> c.now, null);
        run.step(25 * MS);
        assertTrue(run.done());
        assertEquals(1, run.slices());
        assertEquals(List.of("PASS other.ok", "SELFTEST: 1 passed, 0 failed"), run.report().subList(0, 2));
    }

    @Test
    void aThrowingTestFailsAndTheRunContinues() {
        FakeClock c = new FakeClock();
        Map<String, Supplier<String>> m = new LinkedHashMap<>();
        m.put("a.boom", () -> { throw new IllegalStateException("x"); });
        m.put("a.fine", () -> null);
        List<String> thrown = new ArrayList<>();
        SelfTestRun run = new SelfTestRun(m, null, () -> c.now, (id, t) -> thrown.add(id));
        run.step(25 * MS);
        assertTrue(run.done());
        assertEquals(List.of("a.boom"), thrown);
        assertEquals("FAIL a.boom: threw java.lang.IllegalStateException: x", run.results().get(0).line());
        assertEquals("SELFTEST: 1 passed, 1 failed", run.summary());
    }

    @Test
    void reportShowsProgressWhileRunningAndNamesSlowTests() {
        FakeClock c = new FakeClock();
        Map<String, Supplier<String>> m = new LinkedHashMap<>();
        m.put("a.quick", () -> { c.now += MS; return null; });
        m.put("a.slow", () -> { c.now += 1500 * MS; return null; });
        m.put("a.last", () -> null);
        SelfTestRun run = new SelfTestRun(m, null, () -> c.now, null);
        run.step(1 * MS);
        List<String> mid = run.report();
        assertTrue(mid.get(mid.size() - 1).startsWith(SelfTestRun.RUNNING), "a script polls until this line is gone");
        while (!run.done()) run.step(1 * MS);
        assertEquals(List.of("a.slow"), run.slowTests().stream().map(SelfTestRun.Result::id).toList());
        List<String> end = run.report();
        assertEquals("SELFTEST: 3 passed, 0 failed", end.get(end.size() - 2));
        assertTrue(end.get(end.size() - 1).contains("slowest: a.slow 1500 ms"), end.get(end.size() - 1));
    }

    @Test
    void anEmptyFilterIsADoneRunNotAHang() {
        SelfTestRun run = new SelfTestRun(Map.of("a.b", () -> null), "nomatch", () -> 0L, null);
        assertTrue(run.done());
        assertTrue(run.step(MS).isEmpty());
        assertEquals("SELFTEST: 0 passed, 0 failed", run.summary());
    }
}
