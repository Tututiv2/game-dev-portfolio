package com.thestill.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateMapTest {

    private static final StateKey<Double> HEAT = StateKey.ofDouble("heat", 0.0);
    private static final StateKey<Boolean> SEALED = StateKey.ofBoolean("sealed", false);
    private static final StateKey<String> NOTE = StateKey.ofString("note", "");
    private static final StateKey<Long> BREAKS = StateKey.ofLong("breaks", 0L);

    @Test
    @DisplayName("a missing key returns its default, never null")
    void defaultsAreAlwaysAvailable() {
        StateMap state = new StateMap();
        assertEquals(0.0, state.get(HEAT));
        assertFalse(state.get(SEALED));
        assertEquals("", state.get(NOTE));
        assertFalse(state.has(HEAT));
    }

    @Test
    @DisplayName("a key whose type changed between versions falls back to its default")
    void typeMismatchFallsBack() {
        StateMap state = new StateMap();
        state.set(NOTE, "not a number");
        // Same id, different type - what happens when a key is redeclared in a
        // later build. Returning the default beats a ClassCastException at a
        // call site with no way to recover.
        StateKey<Double> sameIdDifferentType = StateKey.ofDouble("note", 7.5);
        assertEquals(7.5, state.get(sameIdDifferentType));
    }

    @Test
    @DisplayName("non-finite values are refused at the model")
    void refusesNonFinite() {
        StateMap state = new StateMap();
        assertThrows(IllegalArgumentException.class, () -> state.set(HEAT, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> state.set(HEAT, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> state.add(HEAT, Double.NaN, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> state.set(HEAT, null));
        assertFalse(state.has(HEAT), "nothing was stored");
    }

    @Test
    @DisplayName("add clamps into range and cannot be driven out of it")
    void addClamps() {
        StateMap state = new StateMap();
        assertEquals(120.0, state.add(HEAT, 120.0, 0.0, 2000.0));
        assertEquals(2000.0, state.add(HEAT, 999_999.0, 0.0, 2000.0), "clamped at the ceiling");
        assertEquals(0.0, state.add(HEAT, -999_999.0, 0.0, 2000.0), "clamped at the floor");
        // Room heat bleeds off on a timer and deposits on break; neither may ever
        // push it negative or infinite whatever the caller passes.
        assertEquals(0.0, state.add(HEAT, -1.0, 0.0, 2000.0));
    }

    @Test
    @DisplayName("add is atomic under concurrent deposits")
    void addIsAtomic() throws Exception {
        StateMap state = new StateMap();
        int threads = 8;
        int perThread = 500;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                try {
                    go.await();
                    for (int n = 0; n < perThread; n++) {
                        state.add(HEAT, 1.0, 0.0, Double.MAX_VALUE);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            worker.setDaemon(true);
            worker.start();
        }
        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "workers finished");
        assertEquals((double) threads * perThread, state.get(HEAT),
                "no deposit was lost to a read-modify-write race");
    }

    // ---- serialisation --------------------------------------------------

    @Test
    @DisplayName("every supported type round-trips through the self-describing form")
    void roundTrip() {
        StateMap original = new StateMap();
        original.set(HEAT, 812.5);
        original.set(SEALED, true);
        original.set(NOTE, "cooked last tuesday");
        original.set(BREAKS, 4_000_000_000L);

        Map<String, String> wire = original.serialise();
        assertEquals("d:812.5", wire.get("heat"));
        assertEquals("b:true", wire.get("sealed"));
        assertEquals("s:cooked last tuesday", wire.get("note"));
        assertEquals("l:4000000000", wire.get("breaks"));

        StateMap restored = new StateMap();
        restored.load(wire, (key, reason) ->
                org.junit.jupiter.api.Assertions.fail("unexpected bad value: " + key));
        assertEquals(812.5, restored.get(HEAT));
        assertTrue(restored.get(SEALED));
        assertEquals("cooked last tuesday", restored.get(NOTE));
        assertEquals(4_000_000_000L, restored.get(BREAKS));
    }

    @Test
    @DisplayName("a string containing a colon survives the round trip")
    void colonsInStrings() {
        StateMap state = new StateMap();
        state.set(NOTE, "d:not-really-a-double");
        StateMap restored = new StateMap();
        restored.load(state.serialise(), (key, reason) -> { });
        assertEquals("d:not-really-a-double", restored.get(NOTE));
    }

    @Test
    @DisplayName("one malformed row is skipped, the rest of the load survives")
    void badRowsAreSkippedNotFatal() {
        // Shadow Prison lost live player data because a single unparseable line
        // threw out of the load loop, the partial cache stayed in memory, and
        // shutdown wrote it back over the good file.
        Map<String, String> wire = new LinkedHashMap<>();
        wire.put("heat", "d:not-a-number");
        wire.put("sealed", "b:maybe");
        wire.put("mystery", "q:???");
        wire.put("truncated", "d");
        wire.put("note", "s:this one is fine");
        wire.put("breaks", "l:42");

        AtomicInteger rejected = new AtomicInteger();
        StateMap state = new StateMap();
        state.load(wire, (key, reason) -> {
            rejected.incrementAndGet();
            assertNotNull(reason);
        });

        assertEquals(4, rejected.get(), "four bad rows reported");
        assertEquals("this one is fine", state.get(NOTE), "good rows still loaded");
        assertEquals(42L, state.get(BREAKS));
        assertEquals(0.0, state.get(HEAT), "the bad one fell back to its default");
    }

    @Test
    @DisplayName("a corrupt boolean does not load as a confident false")
    void corruptBooleanIsRejected() {
        // Boolean.parseBoolean maps every non-"true" string to false, so garbage
        // would load as a real, wrong value rather than as an error.
        assertNull(StateMap.decode("b:tru"));
        assertNull(StateMap.decode("b:1"));
        assertEquals(Boolean.FALSE, StateMap.decode("b:false"));
        assertEquals(Boolean.TRUE, StateMap.decode("b:true"));
    }

    @Test
    @DisplayName("a stored infinity is rejected on the way back in")
    void nonFiniteIsRejectedOnLoad() {
        assertNull(StateMap.decode("d:Infinity"));
        assertNull(StateMap.decode("d:NaN"));
        assertEquals(1.5, StateMap.decode("d:1.5"));
    }

    // ---- dirty tracking -------------------------------------------------

    @Test
    @DisplayName("a clean map produces no snapshot")
    void cleanMapSkipsTheWrite() {
        StateMap state = new StateMap();
        assertNull(state.takeDirtySnapshot(), "nothing to write");
        state.set(HEAT, 1.0);
        assertNotNull(state.takeDirtySnapshot());
        assertNull(state.takeDirtySnapshot(), "flag cleared by the previous take");
    }

    @Test
    @DisplayName("loading does not mark the map dirty")
    void loadingIsNotAChange() {
        StateMap state = new StateMap();
        state.load(Map.of("heat", "d:5.0"), (key, reason) -> { });
        assertFalse(state.isDirty(), "data just read from disk does not need writing back");
    }

    @Test
    @DisplayName("a write during a flush is not lost")
    void writeDuringFlushSurvives() {
        // takeDirtySnapshot clears the flag BEFORE reading. A mutation racing the
        // flush therefore re-dirties the map and is caught next time. Clearing
        // after would drop it, and it would look like a random unreproducible
        // loss of one player's progress.
        StateMap state = new StateMap();
        state.set(HEAT, 1.0);
        Map<String, String> first = state.takeDirtySnapshot();
        assertNotNull(first);

        state.set(HEAT, 2.0);
        Map<String, String> second = state.takeDirtySnapshot();
        assertNotNull(second, "the racing write is still pending");
        assertEquals("d:2.0", second.get("heat"));
    }
}
