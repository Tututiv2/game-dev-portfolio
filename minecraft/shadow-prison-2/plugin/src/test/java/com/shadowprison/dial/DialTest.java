package com.shadowprison.dial;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DialTest {

    private static Dial dial(Dial.Kind kind, long def, long min, long max) {
        return new Dial("test.dial", "Test", "Test", "desc", kind, def, min, max, 1, Material.STONE);
    }

    @Test
    void clampsToRange() {
        Dial d = dial(Dial.Kind.NUMBER, 5, 0, 10);
        assertEquals(10, d.set(99));
        assertEquals(0, d.set(-99));
    }

    @Test
    void parsesEachKind() {
        assertEquals(1L, dial(Dial.Kind.TOGGLE, 0, 0, 1).parse("on"));
        assertEquals(0L, dial(Dial.Kind.TOGGLE, 0, 0, 1).parse("off"));
        assertEquals(250_000L, dial(Dial.Kind.MONEY, 0, 0, Long.MAX_VALUE).parse("2.5k"));
        assertEquals(1_250L, dial(Dial.Kind.PERCENT, 0, 0, 100_000).parse("12.5%"));
        assertEquals(1_500L, dial(Dial.Kind.NUMBER, 0, 0, 10_000).parse("1,500"));
        assertNull(dial(Dial.Kind.MONEY, 0, 0, 10).parse("NaN"));
        assertNull(dial(Dial.Kind.NUMBER, 0, 0, 10).parse("ten"));
    }

    @Test
    void formatsEachKind() {
        assertEquals("ON", dial(Dial.Kind.TOGGLE, 1, 0, 1).format(1));
        assertEquals("$100", dial(Dial.Kind.MONEY, 0, 0, Long.MAX_VALUE).format(10_000));
        assertEquals("12.50%", dial(Dial.Kind.PERCENT, 0, 0, 100_000).format(1_250));
        assertEquals("25%", dial(Dial.Kind.PERCENT, 0, 0, 100_000).format(2_500));
    }

    @Test
    void rejectsBadDefinitions() {
        assertThrows(IllegalArgumentException.class, () -> dial(Dial.Kind.NUMBER, 50, 0, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new Dial("Bad Key,", "c", "l", "d", Dial.Kind.NUMBER, 0, 0, 1, 1, Material.STONE));
    }
}
