package com.thestill.cosmetic;

import java.util.regex.Pattern;

/**
 * ONE word family is blocked, absolutely — the N-slur (hard-R and -a endings, plurals,
 * common leetspeak/spacing evasion). Everything else is left to human moderators.
 *
 * <p>Ported verbatim from Shadow Prison's {@code ops.SlurFilter}, and verbatim is the
 * point: it is the one piece of that codebase that had already survived contact with
 * people trying to beat it, and a re-derived version would be a version that has not.
 *
 * <p>Pure static logic with no Bukkit in it, so {@code /nick}, chat, and any future
 * free-text input share the exact same check — and so a unit test can walk the evasions
 * without a server.
 *
 * <p>Detection: normalize (lowercase, strip everything but letters, fold common leet
 * substitutions, collapse runs of 3+ repeated letters down to 2 so stretching like
 * "niggger" can't dodge the natural double-g), then substring-match the stem. Requiring
 * the double "gg" deliberately avoids false-triggering on "Niger"/"Nigerian" (single g),
 * which are legitimate real-world words and places — and that false positive is not
 * hypothetical, it is the one every naive version of this ships with.
 */
public final class SlurFilter {

    private static final Pattern SLUR_STEM = Pattern.compile("nigg[ae][rz]?s?");
    private static final Pattern RUN_OF_THREE_PLUS = Pattern.compile("(.)\\1{2,}");

    private SlurFilter() {
    }

    public static boolean containsBlockedWord(String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return false;
        }
        StringBuilder normalized = new StringBuilder(rawText.length());
        for (int i = 0; i < rawText.length(); i++) {
            char c = Character.toLowerCase(rawText.charAt(i));
            switch (c) {
                case '1', 'l', '|', '!', 'í', 'ì' -> normalized.append('i');
                case '3', '€' -> normalized.append('e');
                case '4', '@' -> normalized.append('a');
                case '0' -> normalized.append('o');
                case '$', '5' -> normalized.append('s');
                case '9' -> normalized.append('g');
                default -> {
                    if (c >= 'a' && c <= 'z') {
                        normalized.append(c);
                    }
                    // everything else (spaces, punctuation, other symbols) is dropped —
                    // defeats "n . i . g . g . e . r" / "n i g g e r" spacing tricks
                }
            }
        }
        String collapsed = RUN_OF_THREE_PLUS.matcher(normalized).replaceAll("$1$1");
        return SLUR_STEM.matcher(collapsed).find();
    }
}
