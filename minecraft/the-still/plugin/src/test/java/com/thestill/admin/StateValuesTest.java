package com.thestill.admin;

import com.thestill.rank.RankService;
import com.thestill.role.Static;
import com.thestill.state.StateKey;
import com.thestill.state.StateMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the generic state editor is allowed to write, and what it must refuse. */
class StateValuesTest {

    @Test
    @DisplayName("a serialised value declares its own type")
    void typesAreReadOffTheValue() {
        assertEquals(StateValues.DOUBLE, StateValues.typeOf("d:812.5"));
        assertEquals(StateValues.LONG, StateValues.typeOf("l:9"));
        assertEquals(StateValues.INT, StateValues.typeOf("i:4"));
        assertEquals(StateValues.BOOLEAN, StateValues.typeOf("b:true"));
        assertEquals(StateValues.STRING, StateValues.typeOf("s:hello"));

        assertEquals(StateValues.UNKNOWN, StateValues.typeOf("x:1"));
        assertEquals(StateValues.UNKNOWN, StateValues.typeOf("nonsense"));
        assertEquals(StateValues.UNKNOWN, StateValues.typeOf(null));
        assertEquals(StateValues.UNKNOWN, StateValues.typeOf(""));
    }

    @Test
    @DisplayName("the body survives colons in the value")
    void bodiesKeepTheirColons() {
        assertEquals("http://example.test", StateValues.bodyOf("s:http://example.test"));
        assertEquals("812.5", StateValues.bodyOf("d:812.5"));
        // Nothing to strip: an unreadable row is shown whole rather than half-eaten.
        assertEquals("garbage", StateValues.bodyOf("garbage"));
    }

    @Test
    @DisplayName("booleans take exactly true or false")
    void booleansAreStrict() {
        assertEquals(Boolean.TRUE, StateValues.parse(StateValues.BOOLEAN, "true"));
        assertEquals(Boolean.FALSE, StateValues.parse(StateValues.BOOLEAN, "FALSE"));
        // Boolean.parseBoolean would call each of these a confident `false`, which is
        // how a typo silently turns a flag off instead of being rejected.
        assertNull(StateValues.parse(StateValues.BOOLEAN, "yes"));
        assertNull(StateValues.parse(StateValues.BOOLEAN, "1"));
        assertNull(StateValues.parse(StateValues.BOOLEAN, ""));
    }

    @Test
    @DisplayName("a non-finite decimal is refused before it reaches the state bag")
    void noInfinities() {
        assertNull(StateValues.parse(StateValues.DOUBLE, "NaN"));
        assertNull(StateValues.parse(StateValues.DOUBLE, "Infinity"));
        assertEquals(812.5, StateValues.parse(StateValues.DOUBLE, "812.5"));
    }

    @Test
    @DisplayName("whole numbers refuse decimals rather than truncating them")
    void wholeNumbersAreWhole() {
        assertEquals(9L, StateValues.parse(StateValues.LONG, "9"));
        assertNull(StateValues.parse(StateValues.LONG, "9.5"));
        assertEquals(4, StateValues.parse(StateValues.INT, "4"));
        // Past Integer.MAX_VALUE — silently wrapping would set a rank index negative.
        assertNull(StateValues.parse(StateValues.INT, "3000000000"));
    }

    @Test
    @DisplayName("text is stored exactly as typed, spaces included")
    void textIsNotTrimmed() {
        assertEquals("  two spaces  ", StateValues.parse(StateValues.STRING, "  two spaces  "));
    }

    @Test
    @DisplayName("a new key's type is guessed, and true/false wins over text")
    void inference() {
        assertEquals(StateValues.BOOLEAN, StateValues.infer("true"));
        assertEquals(StateValues.BOOLEAN, StateValues.infer("False"));
        assertEquals(StateValues.LONG, StateValues.infer("42"));
        assertEquals(StateValues.DOUBLE, StateValues.infer("42.5"));
        assertEquals(StateValues.STRING, StateValues.infer("thestill"));
        assertEquals(StateValues.STRING, StateValues.infer("NaN"));
    }

    @Test
    @DisplayName("applying a value lands it in the bag and marks it dirty")
    void applyWrites() {
        StateMap state = new StateMap();
        assertNull(StateValues.apply(state, "din.total", StateValues.DOUBLE, "12.5"));
        assertEquals(12.5, state.get(StateKey.ofDouble("din.total", 0.0)));
        assertTrue(state.isDirty());
        assertEquals("d:12.5", state.serialise().get("din.total"));
    }

    @Test
    @DisplayName("a refusal explains itself instead of doing nothing")
    void applyRefusesWithAReason() {
        StateMap state = new StateMap();
        String refusal = StateValues.apply(state, "din.total", StateValues.DOUBLE, "loud");
        assertNotNull(refusal);
        assertTrue(refusal.contains("decimal"), refusal);
        assertEquals(0, state.size());

        assertNotNull(StateValues.apply(state, "  ", StateValues.STRING, "x"));
        assertNotNull(StateValues.apply(state, "k", StateValues.UNKNOWN, "x"));
    }

    /**
     * The reason the editor never re-infers the type of an existing key.
     *
     * <p>{@code StateMap.get} casts by the key's declared type and falls back to the
     * default when the stored object is a different class — no throw, no warning. So
     * writing an Integer key back as a Long does not fail loudly, it reads as rank 0
     * forever and the player is at D1 with nothing in the log.
     */
    @Test
    @DisplayName("re-typing an existing key silently resets it, which is why type is preserved")
    void changingTheTypeIsASilentReset() {
        StateMap state = new StateMap();
        state.set(RankService.RANK_INDEX, 7);
        assertEquals(7, state.get(RankService.RANK_INDEX));

        String stored = state.serialise().get(RankService.RANK_INDEX.id());
        assertEquals(StateValues.INT, StateValues.typeOf(stored));

        // What a naive editor would do: guess from what was typed. "9" infers as LONG.
        assertEquals(StateValues.LONG, StateValues.infer("9"));
        assertNull(StateValues.apply(state, RankService.RANK_INDEX.id(),
                StateValues.LONG, "9"));
        assertEquals(0, state.get(RankService.RANK_INDEX),
                "a Long in an Int key reads as the default — the reset this class exists to avoid");

        // What the editor actually does: reuse the type the value already had.
        state.set(RankService.RANK_INDEX, 7);
        assertNull(StateValues.apply(state, RankService.RANK_INDEX.id(),
                StateValues.typeOf(state.serialise().get(RankService.RANK_INDEX.id())), "9"));
        assertEquals(9, state.get(RankService.RANK_INDEX));
    }

    @Test
    @DisplayName("a key can be removed whatever its type, including one we cannot read")
    void removalIsTypeBlind() {
        StateMap state = new StateMap();
        state.set(Static.BALANCE, 1_250L);
        StateValues.remove(state, Static.BALANCE.id());
        assertEquals(0L, state.get(Static.BALANCE));
        assertEquals(0, state.size());
    }
}
