package com.shadowprison.money;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyTest {

    @Test
    void parsesPlainAndShorthand() {
        assertEquals(Optional.of(150_000L), Money.parse("1500"));
        assertEquals(Optional.of(150_000L), Money.parse("1,500"));
        assertEquals(Optional.of(150_000L), Money.parse("$1,500"));
        assertEquals(Optional.of(150_000L), Money.parse("1.5k"));
        assertEquals(Optional.of(200_000_000L), Money.parse("2m"));
        assertEquals(Optional.of(300_000_000_000L), Money.parse("3B"));
        assertEquals(Optional.of(1_250L), Money.parse("12.50"));
        assertEquals(Optional.of(50L), Money.parse(".5"));
    }

    /** Every input that broke SP1's double-based economy (golden rule 27) — and friends. */
    @Test
    void rejectsEverythingThatIsNotAFiniteNonNegativeAmount() {
        for (String bad : new String[]{"NaN", "nan", "Infinity", "-Infinity", "-5", "-0.01", "1e9",
                "1E999", "0x10", "1.234", "", "   ", "k", "1kk", "1..2", "１２", "12 34", "5-"}) {
            assertTrue(Money.parse(bad).isEmpty(), "should reject: '" + bad + "'");
        }
        assertTrue(Money.parse(null).isEmpty());
    }

    @Test
    void rejectsAmountsAboveTheCap() {
        assertTrue(Money.parse("2000000T").isEmpty());
        assertTrue(Money.parse("99999999999999999999999").isEmpty());
    }

    @Test
    void formats() {
        assertEquals("$1,234", Money.format(123_400));
        assertEquals("$12.50", Money.format(1_250));
        assertEquals("$0", Money.format(0));
        assertEquals("$1.25M", Money.compact(125_000_000));
        assertEquals("$999", Money.compact(99_900));
    }

    @Test
    void scaleByBasisPointsRoundsDownAndCaps() {
        assertEquals(15_000L, Money.scaleBp(10_000L, 15_000L));   // 1.5x
        assertEquals(3L, Money.scaleBp(7L, 5_000L));              // 3.5 -> 3
        assertEquals(Money.MAX_CENTS, Money.scaleBp(Money.MAX_CENTS, 20_000L));
    }
}
