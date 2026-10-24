package com.auraqol.skyblock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MoneyLogTest {

    @Test
    void aLineSaysCountedOrIgnoredTheValueAndTheRule() {
        String l = MoneyLog.line(0, "sacks", "Wheat +1,234 @6", 7404, true, "sack-gain");
        assertTrue(l.contains("COUNTED"));
        assertTrue(l.contains("+7,404"));
        assertTrue(l.endsWith("| sack-gain"));
        String u = MoneyLog.line(0, "drop", "Mystery x1", Double.NaN, false, "unpriced: not in price data");
        assertTrue(u.contains("ignored"));
        assertTrue(u.contains("unpriced"));
        assertFalse(MoneyLog.line(0, "chat", "two\nlines", 0, false, "r").contains("\n"));
    }

    @Test
    void theFileIsCappedAndRotated(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("auraqol-money-log.txt");
        MoneyLog log = new MoneyLog(f, 4096);
        String line = "x".repeat(100);
        for (int i = 0; i < 60; i++) {
            log.add(line);
            log.flush();
        }
        assertTrue(Files.exists(f));
        assertTrue(Files.exists(dir.resolve("auraqol-money-log.old.txt")));
        assertTrue(Files.size(f) <= 4096 + 200, "current file stays near the cap");
        assertTrue(Files.size(dir.resolve("auraqol-money-log.old.txt")) <= 4096 + 200);
        log.add("last");
        log.flush();
        assertTrue(Files.readString(f, StandardCharsets.UTF_8).contains("last"));
        assertEquals(dir.resolve("auraqol-money-log.old.txt"), log.oldFile());
    }
}
