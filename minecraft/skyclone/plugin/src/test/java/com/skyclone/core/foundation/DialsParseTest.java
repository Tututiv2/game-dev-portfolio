package com.skyclone.core.foundation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DialsParseTest {

    @Test
    void parsesEachKind() {
        assertEquals(2500, DialsImpl.parse(DialsImpl.Kind.INTEGER, "2.5k"));
        assertEquals(-3, DialsImpl.parse(DialsImpl.Kind.INTEGER, "-3"));
        assertNull(DialsImpl.parse(DialsImpl.Kind.INTEGER, "1.5"));
        assertNull(DialsImpl.parse(DialsImpl.Kind.INTEGER, "5b"));       // > int range
        assertEquals(0.25, DialsImpl.parse(DialsImpl.Kind.NUMBER, "0.25"));
        assertEquals(1000.0, DialsImpl.parse(DialsImpl.Kind.NUMBER, "1e3"));
        assertEquals(Boolean.TRUE, DialsImpl.parse(DialsImpl.Kind.FLAG, "ON"));
        assertEquals(Boolean.FALSE, DialsImpl.parse(DialsImpl.Kind.FLAG, "no"));
        assertNull(DialsImpl.parse(DialsImpl.Kind.FLAG, "maybe"));
        assertEquals("Sky Server", DialsImpl.parse(DialsImpl.Kind.TEXT, "Sky Server"));
    }

    @Test
    void formatsWithoutScientificNotation() {
        assertEquals("1700000000000", DialsImpl.format(1.7e12));
        assertEquals("0.25", DialsImpl.format(0.25));
        assertEquals("20", DialsImpl.format(20.0));
        assertEquals("true", DialsImpl.format(true));
    }
}
