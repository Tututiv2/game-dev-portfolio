package com.auraqol.skyblock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FmtTest {

    @Test
    void exactCoinsGroupAndDropDecimalsAboveAHundred() {
        assertEquals("442,000,000", Fmt.coins(442_000_000));
        assertEquals("1,368", Fmt.coins(1368.4));
        assertEquals("3.6", Fmt.coins(3.6));
        assertEquals("3", Fmt.coins(3));
        assertEquals("0", Fmt.coins(0));
        assertEquals("?", Fmt.coins(Double.NaN));
    }

    @Test
    void compactKeepsThreeSignificantFigures() {
        assertEquals("950", Fmt.compact(950));
        assertEquals("1.00k", Fmt.compact(1000));
        assertEquals("12.3k", Fmt.compact(12_345));
        assertEquals("20.6k", Fmt.compact(20_600));
        assertEquals("123k", Fmt.compact(123_456));
        assertEquals("1.24M", Fmt.compact(1_240_000));
        assertEquals("3.10B", Fmt.compact(3_100_000_000.0));
        assertEquals("-3.10M", Fmt.compact(-3_100_000));
    }

    @Test
    void compactRollsOverToTheNextSuffixInsteadOfPrinting1000k() {
        assertEquals("1.00M", Fmt.compact(999_999));
        assertEquals("1.00M", Fmt.compact(999_600));
        assertEquals("999k", Fmt.compact(999_400));
    }

    @Test
    void signedCompactMarksGains() {
        assertEquals("+1.24M", Fmt.signedCompact(1_240_000));
        assertEquals("-300", Fmt.signedCompact(-300));
        assertEquals("0", Fmt.signedCompact(0));
    }

    @Test
    void durationDropsSecondsOnceHoursAppear() {
        assertEquals("0s", Fmt.duration(0));
        assertEquals("42s", Fmt.duration(42_000));
        assertEquals("5m 07s", Fmt.duration(307_000));
        assertEquals("1h 02m", Fmt.duration(3_720_000));
        assertEquals("0s", Fmt.duration(-5));
    }

    @Test
    void ageGetsCoarser() {
        assertEquals("30s", Fmt.age(30_000));
        assertEquals("5m", Fmt.age(300_000));
        assertEquals("3h", Fmt.age(3 * 3_600_000L));
        assertEquals("3d", Fmt.age(72 * 3_600_000L));
    }

    @Test
    void stripRemovesFormattingCodes() {
        assertEquals("Purse: 1,234", Fmt.strip("§7Purse: §6§l1,234§r"));
        assertEquals("plain", Fmt.strip("plain"));
        assertEquals("end", Fmt.strip("end§"));
    }

    @Test
    void plainNameDropsIconsGlyphsAndExtraSpace() {
        assertEquals("Rough Ruby Gemstone", Fmt.plainName("§c\uE010 Rough Ruby Gemstone"));
        assertEquals("Fine Jade Gemstone", Fmt.plainName("❂ Fine Jade Gemstone"));
        assertEquals("Enchanted Hay Bale", Fmt.plainName(" §9Enchanted\u00A0Hay  Bale "));
        assertEquals("Overclocker 3000", Fmt.plainName("Overclocker 3000"));
        assertEquals("", Fmt.plainName(null));
    }
}
