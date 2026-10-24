package com.skyclone.core.foundation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * One {@code /sb selftest} run, sliced so it never holds the main thread for long.
 *
 * <p>Why this exists: 357 self-tests run back to back held the main thread for over ten seconds
 * with a player online, which is long enough for Paper's watchdog to dump every thread. A self-test
 * is an opaque {@code Supplier}, so it cannot be interrupted; what CAN be done is to stop between
 * tests. {@link #step(long)} runs tests until the time budget is spent (always at least one, so a run
 * always makes progress) and returns; the caller runs the next slice on the next tick.
 *
 * <p>No Bukkit in here on purpose: the slicing and the report are unit-tested with a fake clock
 * ({@code SelfTestRunTest}). The caller is responsible for running every step on the main thread.
 */
public final class SelfTestRun {

    /** Status line prefixes. RCON clients look for {@link #RUNNING} to know they must poll. */
    public static final String RUNNING = "SELFTEST RUNNING";
    public static final String SUMMARY = "SELFTEST:";

    /** A single test longer than this is named in the log: it cannot be split, only its owner can. */
    public static final long SLOW_TEST_MS = 1000;

    /** One finished test. {@code error} is null for a pass. */
    public record Result(String id, String error, long nanos) {
        public boolean passed() { return error == null; }
        public String line() { return passed() ? "PASS " + id : "FAIL " + id + ": " + error; }
        public long millis() { return nanos / 1_000_000L; }
    }

    /** Called for a test that threw (so the caller can log the stack); may be null. */
    public interface ThrowHandler { void threw(String id, Throwable t); }

    private final List<Map.Entry<String, Supplier<String>>> tests;
    private final String filter;
    private final LongSupplier clock;
    private final ThrowHandler onThrow;
    private final List<Result> results = new ArrayList<>();
    private final long startedNanos;
    private long busyNanos;
    private int slices;
    private int next;

    /**
     * @param all    every registered test, in registration order (copied)
     * @param filter case-insensitive substring of the id, or null/empty for all
     * @param clock  monotonic nanoseconds ({@code System::nanoTime} in production)
     */
    public SelfTestRun(Map<String, Supplier<String>> all, String filter, LongSupplier clock, ThrowHandler onThrow) {
        this.filter = filter == null || filter.isBlank() ? null : filter;
        this.clock = clock;
        this.onThrow = onThrow;
        String f = this.filter == null ? null : this.filter.toLowerCase(java.util.Locale.ROOT);
        List<Map.Entry<String, Supplier<String>>> picked = new ArrayList<>();
        for (Map.Entry<String, Supplier<String>> e : new LinkedHashMap<>(all).entrySet()) {
            if (f != null && !e.getKey().toLowerCase(java.util.Locale.ROOT).contains(f)) continue;
            picked.add(Map.entry(e.getKey(), e.getValue()));
        }
        this.tests = picked;
        this.startedNanos = clock.getAsLong();
    }

    /**
     * Run tests until {@code budgetNanos} has been spent or none are left. At least one test runs per
     * call, so a single test slower than the budget still completes (it just gets a slice to itself).
     *
     * @return the results produced by this slice, in order
     */
    public List<Result> step(long budgetNanos) {
        List<Result> out = new ArrayList<>();
        if (done()) return out;
        slices++;
        long sliceStart = clock.getAsLong();
        do {
            Map.Entry<String, Supplier<String>> e = tests.get(next++);
            long t0 = clock.getAsLong();
            String error;
            try {
                error = e.getValue().get();
            } catch (Throwable t) {
                error = "threw " + t;
                if (onThrow != null) onThrow.threw(e.getKey(), t);
            }
            Result r = new Result(e.getKey(), error, Math.max(0, clock.getAsLong() - t0));
            results.add(r);
            out.add(r);
        } while (!done() && clock.getAsLong() - sliceStart < budgetNanos);
        busyNanos += Math.max(0, clock.getAsLong() - sliceStart);
        return out;
    }

    public boolean done() { return next >= tests.size(); }

    public int total() { return tests.size(); }

    public int completed() { return results.size(); }

    public String filter() { return filter; }

    public int slices() { return slices; }

    public List<Result> results() { return List.copyOf(results); }

    public int passed() { int n = 0; for (Result r : results) if (r.passed()) n++; return n; }

    public int failed() { return results.size() - passed(); }

    /** The tests over {@link #SLOW_TEST_MS}, slowest first. */
    public List<Result> slowTests() {
        List<Result> slow = new ArrayList<>();
        for (Result r : results) if (r.millis() >= SLOW_TEST_MS) slow.add(r);
        slow.sort(Comparator.comparingLong(Result::nanos).reversed());
        return slow;
    }

    /** "SELFTEST: 357 passed, 0 failed" — the exact line integrators and scripts already grep for. */
    public String summary() {
        return SUMMARY + " " + passed() + " passed, " + failed() + " failed";
    }

    /** One line saying where a run stands, starting with {@link #RUNNING} while unfinished. */
    public String progressLine() {
        if (done()) return summary();
        return RUNNING + " " + completed() + "/" + total()
                + (filter != null ? " (filter '" + filter + "')" : "")
                + " - spread across ticks so the server keeps running; '/sb selftest report' prints the results when done";
    }

    /** Timing footer: how long the run took on the wall clock vs on the main thread, and the slowest tests. */
    public String timingLine() {
        long wallMs = Math.max(0, clock.getAsLong() - startedNanos) / 1_000_000L;
        StringBuilder sb = new StringBuilder("SELFTEST timing: ").append(busyNanos / 1_000_000L).append(" ms of main thread over ")
                .append(slices).append(slices == 1 ? " tick" : " ticks").append(", ").append(wallMs).append(" ms wall");
        List<Result> top = new ArrayList<>(results);
        top.sort(Comparator.comparingLong(Result::nanos).reversed());
        if (!top.isEmpty()) {
            sb.append("; slowest:");
            for (int i = 0; i < Math.min(3, top.size()); i++) {
                sb.append(i == 0 ? " " : ", ").append(top.get(i).id()).append(' ').append(top.get(i).millis()).append(" ms");
            }
        }
        return sb.toString();
    }

    /** Every result line, then the summary, then the timing line. What {@code /sb selftest report} prints. */
    public List<String> report() {
        List<String> lines = new ArrayList<>();
        for (Result r : results) lines.add(r.line());
        lines.add(done() ? summary() : progressLine());
        if (done()) lines.add(timingLine());
        return lines;
    }
}
