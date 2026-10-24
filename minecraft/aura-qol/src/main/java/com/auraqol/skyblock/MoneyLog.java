package com.auraqol.skyblock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * {@code /money log}: every money decision, counted or ignored, with its rule
 * and value, appended to a text file the owner can send when a number looks
 * wrong. Capped: when the file passes {@code capBytes} it is moved to
 * {@code <name>.old.txt} (replacing the previous one) and a new file starts, so
 * the two together never exceed twice the cap. Lines are buffered and written
 * by {@link #flush} (the game calls it every couple of seconds), so a burst of
 * events is one small write, not a write per line. No Minecraft imports.
 */
public final class MoneyLog {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private final Path file;
    private final long capBytes;
    private final StringBuilder buffer = new StringBuilder();

    public MoneyLog(Path file, long capBytes) {
        this.file = file;
        this.capBytes = Math.max(4096, capBytes);
    }

    public Path file() {
        return file;
    }

    /**
     * One event as a line:
     * {@code 2026-10-08 14:03:22.123  COUNTED  +12,345  sacks  Wheat +1,234 @10  | sack-gain}.
     */
    public static String line(long epochMs, String channel, String what, double value, boolean counted, String rule) {
        String v = Double.isNaN(value) ? "unpriced" : (value > 0 ? "+" : "") + Fmt.coins(value);
        return TIME.format(Instant.ofEpochMilli(epochMs)) + "  " + (counted ? "COUNTED" : "ignored") + "  "
                + pad(v, 12) + "  " + pad(channel == null ? "" : channel, 6) + "  "
                + oneLine(what) + "  | " + oneLine(rule);
    }

    public synchronized void add(String line) {
        buffer.append(line).append(System.lineSeparator());
        if (buffer.length() > 256 * 1024) {
            flush(); // never let a runaway buffer grow without bound
        }
    }

    /** Write what is buffered; rotate first if the file is over the cap. Never throws. */
    public synchronized void flush() {
        if (buffer.length() == 0) {
            return;
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (Files.exists(file) && Files.size(file) >= capBytes) {
                Files.move(file, oldFile(), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(file, buffer, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) {
            // A log that cannot be written is not worth a crash; drop the batch.
        }
        buffer.setLength(0);
    }

    public Path oldFile() {
        String n = file.getFileName().toString();
        String base = n.endsWith(".txt") ? n.substring(0, n.length() - 4) : n;
        return file.resolveSibling(base + ".old.txt");
    }

    private static String pad(String s, int w) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < w) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c == '\n' || c == '\r' || c == '\t' ? ' ' : c);
        }
        return sb.toString();
    }
}
