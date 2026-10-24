package com.auraqol.skyblock;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * No control characters in any source file. A regex written through a shell
 * heredoc or a non-raw string can turn {@code \b} into a real backspace: it
 * compiles, reads fine in an editor, and never matches. This portfolio has
 * paid for that class of bug four times; the parsers here are all regexes.
 */
class SourceHygieneTest {

    @Test
    void noControlCharactersInSources() throws IOException {
        List<String> bad = new ArrayList<>();
        for (String root : new String[]{"src/main", "src/test", "src/mc26_1", "src/mc26_2"}) {
            Path r = Path.of(root);
            if (!Files.isDirectory(r)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(r)) {
                for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".json"))::iterator) {
                    String s = Files.readString(f, StandardCharsets.UTF_8);
                    for (int i = 0; i < s.length(); i++) {
                        char c = s.charAt(i);
                        if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') {
                            bad.add(f + " offset " + i + " char 0x" + Integer.toHexString(c));
                            break;
                        }
                    }
                }
            }
        }
        assertTrue(bad.isEmpty(), "control characters: " + bad);
    }
}
