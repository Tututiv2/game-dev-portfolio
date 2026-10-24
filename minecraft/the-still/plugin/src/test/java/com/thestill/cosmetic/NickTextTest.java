package com.thestill.cosmetic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Six guards, and the order they run in.
 *
 * <p>Shadow Prison's nickname policy was five lines — strip {@code &} and {@code §},
 * trim, truncate, reject blank — with a slur check bolted on at one of the two call
 * sites. Everything below is something that plugin allowed: {@code /nick Notch},
 * {@code /nick [Owner]}, a name identical to the player standing next to you, and a name
 * changed forty times a minute so that nobody could report the one that said something.
 *
 * <p>The reason this is a unit test rather than something checked by hand on a live
 * server is the reason {@link NickText} has no Bukkit in it.
 */
class NickTextTest {

    private static final long MINUTE = 60_000L;

    // ---- the six guards ---------------------------------------------------

    @Test
    @DisplayName("1 · the charset is an allow-list, not a blocklist")
    void charset() {
        assertEquals(NickText.Reason.ILLEGAL_CHARACTERS, reasonFor("Ghost»Rig"));
        assertEquals(NickText.Reason.ILLEGAL_CHARACTERS, reasonFor("na‮me"));
        assertEquals(NickText.Reason.ILLEGAL_CHARACTERS, reasonFor("drop;table"));
        // Letters, digits, underscore and the space, and nothing else.
        assertTrue(check("Deck Hand_7").ok());
    }

    @Test
    @DisplayName("2 · length refuses rather than silently shortening")
    void length() {
        assertEquals(NickText.Reason.TOO_SHORT, reasonFor("Al"));
        assertEquals(NickText.Reason.EMPTY, reasonFor("   "));
        assertEquals(NickText.Reason.TOO_LONG, reasonFor("A".repeat(17)));

        // The whole argument for refusing: truncation invents a name the player did not
        // ask for, and the name it invents can be worse than the one they typed.
        NickText.Result cut = check("TheRealAdminGuy1");
        assertTrue(cut.ok());
        assertEquals("TheRealAdminGuy1", cut.text(), "sixteen characters is legal, so it "
                + "must arrive intact");
        assertEquals(NickText.Reason.TOO_LONG, reasonFor("TheRealAdminGuy12"),
                "seventeen must be refused, NOT quietly trimmed back to 'TheRealAdminGuy'");
    }

    @Test
    @DisplayName("3 · the cooldown holds, and reports how long is left")
    void cooldown() {
        long now = 10 * MINUTE;
        NickText.Result blocked = NickText.check("Driftwood", rules(), now, now - MINUTE);
        assertEquals(NickText.Reason.COOLDOWN, blocked.reason());
        assertEquals(4 * MINUTE, blocked.waitMillis(),
                "the player is owed a number, not 'try again later'");

        assertTrue(NickText.check("Driftwood", rules(), now, now - 6 * MINUTE).ok());
        // Never set before. A first name must not be blocked by a zero timestamp.
        assertTrue(NickText.check("Driftwood", rules(), now, 0L).ok());
    }

    @Test
    @DisplayName("4 · the slur filter runs on a name the same way it runs on chat")
    void slur() {
        assertEquals(NickText.Reason.SLUR, reasonFor("n1gg3r"));
        assertEquals(NickText.Reason.SLUR, reasonFor("xN I G G Ax"));
    }

    @Test
    @DisplayName("5 · impersonation: staff words, bracket shapes, and real accounts")
    void impersonation() {
        assertEquals(NickText.Reason.IMPERSONATION, reasonFor("Owner"));
        assertEquals(NickText.Reason.IMPERSONATION, reasonFor("console"));
        assertEquals(NickText.Reason.IMPERSONATION, reasonFor("Moderator"));

        // A real account that belongs to somebody else.
        NickText.Rules borrowed = new NickText.Rules(16, 0L,
                candidate -> candidate.equalsIgnoreCase("Tutut"), never());
        assertEquals(NickText.Reason.IMPERSONATION,
                NickText.check("Tutut", borrowed, 0L, 0L).reason());
        assertTrue(NickText.check("Driftwood", borrowed, 0L, 0L).ok());

        // The bracket shape is tested at the guard rather than through check(), because
        // the charset already refuses '[' — and that redundancy is deliberate. The
        // charset is the rule most likely to be widened by a later edit; the guard that
        // matters must not depend on it having stayed narrow.
        assertTrue(NickText.impersonates("[Boss]"));
        assertTrue(NickText.impersonates("Admin"));
        assertFalse(NickText.impersonates("Deckhand"));
    }

    @Test
    @DisplayName("6 · two players cannot wear the same name")
    void uniqueness() {
        NickText.Rules taken = new NickText.Rules(16, 0L, never(),
                candidate -> candidate.equalsIgnoreCase("Driftwood"));
        assertEquals(NickText.Reason.TAKEN,
                NickText.check("driftwood", taken, 0L, 0L).reason(),
                "case must not be a way round it — 'driftwood' and 'Driftwood' read the "
                        + "same in a chat line");
        assertTrue(NickText.check("Mudder", taken, 0L, 0L).ok());
    }

    // ---- the ordering rule ------------------------------------------------

    @Test
    @DisplayName("strip, trim, truncate — and only THEN check")
    void everyGuardReadsTheSanitisedText() {
        // The rule, stated as a property: whatever comes back in text() is what would be
        // stored, and it is what every guard was applied to. Checking the raw input and
        // storing the sanitised one means the thing on screen was never examined.
        NickText.Result padded = check("  &c&lDeckhand  ");
        assertTrue(padded.ok());
        assertEquals("Deckhand", padded.text(),
                "colour codes and padding must be gone BEFORE anything judges the name");

        // A code is TWO characters and both go. Deleting only the sigil is what the source
        // did, and it did not refuse "&c&lDeckhand" or clean it — it stored "clDeckhand".
        assertEquals("Deckhand", NickText.sanitise("&c&lDeckhand"));

        // And the guards see that form, not the raw one: this is a slur only after the
        // formatting is gone and the letters close up.
        assertEquals(NickText.Reason.SLUR, reasonFor("§4n§4i§4g§4g§4e§4r"));

        // Same trick, aimed at the impersonation guard rather than the word filter:
        // "Own§4er" is not a reserved word until it is sanitised, and then it is.
        assertEquals(NickText.Reason.IMPERSONATION, reasonFor("Own§4er"));

        // Control characters go with the formatting ones — a newline in a nickname is a
        // nickname that forges a second chat line.
        assertEquals("Deckhand", NickText.sanitise("Deck\nhand").replace(" ", ""));
        assertFalse(NickText.sanitise("Deck\nhand").contains("\n"));
    }

    @Test
    @DisplayName("the hard ceiling and the policy limit are different things")
    void twoLimits() {
        // sanitise() truncates at the structural ceiling so nothing downstream is ever
        // handed an unbounded string; the policy limit then REFUSES anything still over.
        // Collapsing these into one number is how the length guard becomes unreachable.
        assertEquals(NickText.HARD_LIMIT, NickText.sanitise("x".repeat(500)).length());
        assertEquals(NickText.Reason.TOO_LONG, reasonFor("x".repeat(500)));
    }

    @Test
    @DisplayName("the cooldown is reported before the slur, so the filter is not a probe")
    void guardOrder() {
        // Somebody on cooldown gets told about the cooldown. Answering "that word is not
        // welcome here" first would turn the name box into a free oracle for working out
        // exactly what the filter catches.
        long now = 10 * MINUTE;
        assertEquals(NickText.Reason.COOLDOWN,
                NickText.check("n1gg3r", rules(), now, now - MINUTE).reason());
    }

    // ---- plumbing ---------------------------------------------------------

    /** Sixteen characters, five minutes, and nobody else in the world. */
    private static NickText.Rules rules() {
        return new NickText.Rules(16, 5 * MINUTE, never(), never());
    }

    private static Predicate<String> never() {
        return candidate -> false;
    }

    private static NickText.Result check(String raw) {
        return NickText.check(raw, new NickText.Rules(16, 0L, never(), never()), 0L, 0L);
    }

    private static NickText.Reason reasonFor(String raw) {
        return check(raw).reason();
    }

    /** Kept so the reserved list cannot quietly lose an entry. */
    @Test
    @DisplayName("every reserved word is actually refused")
    void reservedWordsAreLive() {
        for (String word : Set.of("admin", "owner", "staff", "console", "server", "notch")) {
            assertTrue(NickText.impersonates(word), "'" + word + "' is not being refused");
            assertTrue(NickText.impersonates(word.toUpperCase()),
                    "'" + word + "' must be refused whatever the case");
        }
    }
}
