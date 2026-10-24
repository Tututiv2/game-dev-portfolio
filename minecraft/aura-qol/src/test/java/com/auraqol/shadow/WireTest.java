package com.auraqol.shadow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Primitive edge cases and every "drop the frame, never crash" rule. */
class WireTest {

    private static byte[] hex(String h) {
        return HexFormat.of().parseHex(h);
    }

    @Test
    void s64RoundTripsTheExtremes() throws Wire.DecodeException {
        long[] values = {0, 1, -1, 63, -64, 64, 127, 128, Integer.MAX_VALUE, Integer.MIN_VALUE,
                9007199254740993L, Long.MAX_VALUE, Long.MIN_VALUE};
        for (long v : values) {
            byte[] b = new Wire.Writer().s64(v).toByteArray();
            assertEquals(v, new Wire.Reader(b).s64(), "s64 " + v);
        }
        assertArrayEquals(hex("01"), new Wire.Writer().s64(-1).toByteArray());
    }

    @Test
    void u32RoundTripsTheWholeUnsignedRange() throws Wire.DecodeException {
        long[] values = {0, 127, 128, 16383, 16384, Integer.MAX_VALUE, Wire.MAX_U32};
        for (long v : values) {
            byte[] b = new Wire.Writer().u32(v).toByteArray();
            assertEquals(v, new Wire.Reader(b).u32(), "u32 " + v);
        }
        assertThrows(IllegalArgumentException.class, () -> new Wire.Writer().u32(-1));
        assertThrows(IllegalArgumentException.class, () -> new Wire.Writer().u32(Wire.MAX_U32 + 1));
    }

    @Test
    void overLongVarIntsAreErrors() {
        assertThrows(Wire.DecodeException.class, () -> new Wire.Reader(hex("ffffffff1f")).u32());
        assertThrows(Wire.DecodeException.class, () -> new Wire.Reader(hex("ffffffffff01")).u32());
        assertThrows(Wire.DecodeException.class, () -> new Wire.Reader(hex("ffffffffffffffffff7f")).s64());
        assertThrows(Wire.DecodeException.class, () -> new Wire.Reader(hex("ffffffffffffffffffff01")).s64());
    }

    @Test
    void badBoolIsAnError() {
        // WELCOME with supported = 0x02
        assertThrows(Wire.DecodeException.class, () -> Codec.decodeS2C(hex("010101020000000000")));
    }

    @Test
    void truncatedFramesAreErrorsNotExceptions() {
        byte[] full = Codec.encode(new Msg.Income("selling", "Selling", 1250000, 1791850000500L));
        for (int cut = 0; cut < full.length; cut++) {
            byte[] part = java.util.Arrays.copyOf(full, cut);
            assertThrows(Wire.DecodeException.class, () -> Codec.decodeS2C(part), "cut at " + cut);
        }
    }

    @Test
    void overLimitStringAndListAreErrors() {
        // AREA whose string claims 40000 bytes
        Wire.Writer w = new Wire.Writer().u32(Msg.AREA).u32(1).u32(40000);
        assertThrows(Wire.DecodeException.class, () -> Codec.decodeS2C(w.toByteArray()));
        // BOOSTS claiming 2000 entries
        Wire.Writer w2 = new Wire.Writer().u32(Msg.BOOSTS).u32(1).u32(2000);
        assertThrows(Wire.DecodeException.class, () -> Codec.decodeS2C(w2.toByteArray()));
        // a list count bigger than the bytes left
        Wire.Writer w3 = new Wire.Writer().u32(Msg.BOOSTS).u32(1).u32(5);
        assertThrows(Wire.DecodeException.class, () -> Codec.decodeS2C(w3.toByteArray()));
    }

    @Test
    void higherVersionAndTrailingBytesAreAccepted() throws Wire.DecodeException {
        byte[] v1 = Codec.encode(new Msg.Area("Mine C"));
        byte[] v9 = java.util.Arrays.copyOf(v1, v1.length + 3);
        v9[1] = 9; // version byte
        v9[v1.length] = 1;
        v9[v1.length + 1] = 2;
        v9[v1.length + 2] = 3;
        assertEquals(new Msg.Area("Mine C"), Codec.decodeS2C(v9));
    }

    @Test
    void aBrokenInnerSnapshotFrameCostsOnlyItself() throws Wire.DecodeException {
        byte[] good = Codec.encode(new Msg.Area("Mine C"));
        byte[] bad = hex("04010773656c"); // truncated INCOME
        byte[] unknown = hex("6301");
        byte[] nested = Codec.encode(new Msg.Snapshot(List.of()));
        Wire.Writer w = new Wire.Writer().u32(Msg.SNAPSHOT).u32(1).count(4).bytes(bad).bytes(good).bytes(unknown)
                .bytes(nested);
        Msg.Snapshot s = (Msg.Snapshot) Codec.decodeS2C(w.toByteArray());
        assertEquals(List.of(new Msg.Area("Mine C")), s.frames());
    }

    @Test
    void emptyAndNullFramesAreErrors() {
        assertThrows(Wire.DecodeException.class, () -> Codec.decodeS2C(new byte[0]));
        assertThrows(Wire.DecodeException.class, () -> Codec.decodeS2C(null));
        assertThrows(Wire.DecodeException.class, () -> Codec.decodeC2S(new byte[0]));
    }

    @Test
    void randomGarbageNeverThrowsAnythingButDecodeException() {
        java.util.Random rnd = new java.util.Random(42);
        List<Throwable> wrong = new ArrayList<>();
        for (int i = 0; i < 20000; i++) {
            byte[] b = new byte[rnd.nextInt(40)];
            rnd.nextBytes(b);
            if (b.length > 0) {
                b[0] = (byte) (1 + rnd.nextInt(12)); // mostly known types
            }
            try {
                Codec.decodeS2C(b);
            } catch (Wire.DecodeException expected) {
                // fine
            } catch (Throwable t) {
                wrong.add(t);
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    void helloAndRequestRoundTrip() throws Wire.DecodeException {
        Msg.Hello h = new Msg.Hello(1, "1.0.0+mc26.1.2", List.of("breaks", "lookup"));
        assertEquals(h, Codec.decodeC2S(Codec.encode(h)));
        Msg.Request r = new Msg.Request(4_000_000_000L, Msg.Request.PROFILE, "9e93b8ee-50df-5fe3-9601-11b243607a93");
        assertEquals(r, Codec.decodeC2S(Codec.encode(r)));
    }
}
