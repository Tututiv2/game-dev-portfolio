package com.skyclone.core.foundation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoinsTest {

    @Test
    void formatsLikeTheGenre() {
        assertEquals("1,234,567.5", Coins.format(1234567.5));
        assertEquals("12", Coins.format(12));
        assertEquals("0", Coins.format(0));
        assertEquals("999", Coins.format(999));
        assertEquals("1,000", Coins.format(1000));
        assertEquals("-1,500.5", Coins.formatTenths(-15005));
        assertEquals("1,000,000,000,000", Coins.format(1e12));
    }

    @Test
    void tenthsNeverMintByRounding() {
        assertEquals(3, Coins.creditTenths(0.3));     // 0.3*10 = 2.9999999999999996
        assertEquals(3, Coins.debitTenths(0.3));
        assertEquals(0, Coins.creditTenths(0.05));    // credit rounds down
        assertEquals(1, Coins.debitTenths(0.05));     // debit rounds up
        assertEquals(12345, Coins.creditTenths(1234.5));
        assertEquals(10, Coins.creditTenths(1.0));
    }

    @Test
    void rejectsGarbage() {
        assertFalse(Coins.valid(-1));
        assertFalse(Coins.valid(Double.NaN));
        assertFalse(Coins.valid(Double.POSITIVE_INFINITY));
        assertFalse(Coins.valid(1e18));
        assertTrue(Coins.valid(0));
        assertThrows(IllegalArgumentException.class, () -> Coins.creditTenths(-5));
        assertEquals(-1, Coins.addCapped(Coins.MAX_TENTHS, 1));
        assertEquals(15, Coins.addCapped(10, 5));
    }

    @Test
    void parsesSuffixes() {
        assertEquals(2500, Coins.parse("2.5k"));
        assertEquals(3_000_000, Coins.parse("3m"));
        assertEquals(1_200_000_000, Coins.parse("1.2B"));
        assertEquals(1500, Coins.parse("1,500"));
        assertTrue(Double.isNaN(Coins.parse("abc")));
        assertTrue(Double.isNaN(Coins.parse("")));
        assertTrue(Double.isNaN(Coins.parse("NaN")));
        assertTrue(Double.isNaN(Coins.parse("Infinity")));
    }
}
