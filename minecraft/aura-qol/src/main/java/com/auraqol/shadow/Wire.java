package com.auraqol.shadow;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The primitive types of the Shadow Live wire protocol (PROTOCOL.md, "Primitive
 * types"). Pure: no Minecraft, no Netty, so the codec is unit-tested against the
 * shared golden fixtures without starting the game.
 *
 * <ul>
 *   <li>{@code u32}: unsigned LEB128 VarInt, 1-5 bytes. Returned as a long so the
 *       whole unsigned range is exact.</li>
 *   <li>{@code s64}: ZigZag LEB128 VarLong, 1-10 bytes.</li>
 *   <li>{@code bool}: 0x00 / 0x01, anything else is an error.</li>
 *   <li>{@code str}: u32 byte length + UTF-8, max 32767 bytes.</li>
 *   <li>{@code list}: u32 count, max 1024.</li>
 *   <li>{@code bytes}: u32 length + raw bytes.</li>
 * </ul>
 *
 * <p>Every malformed input - truncated, an over-long VarInt, a bad bool, an
 * over-limit length - is a {@link DecodeException}, never an index exception or
 * anything else that could escape into the game. The protocol rule is "drop the
 * frame, never crash", and a checked exception is how the caller is forced to
 * drop it.
 */
public final class Wire {

    public static final int MAX_STRING_BYTES = 32767;
    public static final int MAX_LIST = 1024;
    public static final long MAX_U32 = 0xFFFF_FFFFL;

    private Wire() {
    }

    /** A frame that cannot be decoded. The frame is dropped; nothing else happens. */
    public static final class DecodeException extends Exception {
        public DecodeException(String message) {
            super(message);
        }
    }

    /** Reads primitives from one frame. Not thread-safe; one reader per frame. */
    public static final class Reader {
        private final byte[] buf;
        private int pos;
        private final int end;

        public Reader(byte[] buf) {
            this(buf, 0, buf.length);
        }

        public Reader(byte[] buf, int offset, int length) {
            this.buf = buf;
            this.pos = offset;
            this.end = offset + length;
        }

        public int remaining() {
            return end - pos;
        }

        private int next() throws DecodeException {
            if (pos >= end) {
                throw new DecodeException("truncated at byte " + pos);
            }
            return buf[pos++] & 0xFF;
        }

        public long u32() throws DecodeException {
            long out = 0;
            for (int i = 0; i < 5; i++) {
                int b = next();
                if (i == 4 && (b & 0xF0) != 0) {
                    throw new DecodeException("u32 overflows 32 bits");
                }
                out |= (long) (b & 0x7F) << (7 * i);
                if ((b & 0x80) == 0) {
                    return out;
                }
            }
            throw new DecodeException("u32 longer than 5 bytes");
        }

        /** A u32 that must also fit a Java int (lengths, counts, ids). */
        public int u32Int() throws DecodeException {
            long v = u32();
            if (v > Integer.MAX_VALUE) {
                throw new DecodeException("value too large: " + v);
            }
            return (int) v;
        }

        public long s64() throws DecodeException {
            long z = 0;
            for (int i = 0; i < 10; i++) {
                int b = next();
                if (i == 9 && (b & 0xFE) != 0) {
                    throw new DecodeException("s64 overflows 64 bits");
                }
                z |= (long) (b & 0x7F) << (7 * i);
                if ((b & 0x80) == 0) {
                    return (z >>> 1) ^ -(z & 1);
                }
            }
            throw new DecodeException("s64 longer than 10 bytes");
        }

        public boolean bool() throws DecodeException {
            int b = next();
            if (b == 0) {
                return false;
            }
            if (b == 1) {
                return true;
            }
            throw new DecodeException("bad bool 0x" + Integer.toHexString(b));
        }

        public String str() throws DecodeException {
            int len = u32Int();
            if (len > MAX_STRING_BYTES) {
                throw new DecodeException("string of " + len + " bytes is over the limit");
            }
            if (len > remaining()) {
                throw new DecodeException("string runs past the end of the frame");
            }
            String s = new String(buf, pos, len, StandardCharsets.UTF_8);
            pos += len;
            return s;
        }

        /** A list count, already checked against the limit. */
        public int count() throws DecodeException {
            int n = u32Int();
            if (n > MAX_LIST) {
                throw new DecodeException("list of " + n + " items is over the limit");
            }
            // Every item is at least one byte, so a count beyond what is left is a lie.
            if (n > remaining()) {
                throw new DecodeException("list count runs past the end of the frame");
            }
            return n;
        }

        public byte[] bytes() throws DecodeException {
            int len = u32Int();
            if (len > remaining()) {
                throw new DecodeException("bytes run past the end of the frame");
            }
            byte[] out = new byte[len];
            System.arraycopy(buf, pos, out, 0, len);
            pos += len;
            return out;
        }
    }

    /** Writes primitives into a growing buffer. */
    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(64);

        public Writer u32(long v) {
            if (v < 0 || v > MAX_U32) {
                throw new IllegalArgumentException("u32 out of range: " + v);
            }
            long n = v;
            while (true) {
                int b = (int) (n & 0x7F);
                n >>>= 7;
                if (n != 0) {
                    out.write(b | 0x80);
                } else {
                    out.write(b);
                    return this;
                }
            }
        }

        public Writer s64(long v) {
            long z = (v << 1) ^ (v >> 63);
            while (true) {
                int b = (int) (z & 0x7F);
                z >>>= 7;
                if (z != 0) {
                    out.write(b | 0x80);
                } else {
                    out.write(b);
                    return this;
                }
            }
        }

        public Writer bool(boolean v) {
            out.write(v ? 1 : 0);
            return this;
        }

        public Writer str(String s) {
            byte[] raw = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
            if (raw.length > MAX_STRING_BYTES) {
                throw new IllegalArgumentException("string over " + MAX_STRING_BYTES + " bytes");
            }
            u32(raw.length);
            out.write(raw, 0, raw.length);
            return this;
        }

        public Writer count(int n) {
            if (n < 0 || n > MAX_LIST) {
                throw new IllegalArgumentException("list of " + n + " items");
            }
            return u32(n);
        }

        public Writer bytes(byte[] b) {
            u32(b.length);
            out.write(b, 0, b.length);
            return this;
        }

        public byte[] toByteArray() {
            return out.toByteArray();
        }
    }
}
