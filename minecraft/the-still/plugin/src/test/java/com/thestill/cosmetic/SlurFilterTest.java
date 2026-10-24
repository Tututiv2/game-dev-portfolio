package com.thestill.cosmetic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The evasions, and the words that must not be caught.
 *
 * <p>This filter was ported verbatim rather than rewritten, and this is the test that
 * makes "verbatim" mean something: it walks the three evasion families the normaliser
 * exists for, and — just as importantly — the false positive that every naive version of
 * this ships with.
 *
 * <p><b>Niger and Nigerian have to pass.</b> A country and a nationality are not a slur,
 * and a filter that eats them is a filter that gets switched off by whoever is tired of
 * apologising for it. That is why the stem requires the double {@code g}, and it is the
 * single most load-bearing character in the pattern.
 */
class SlurFilterTest {

    @Test
    @DisplayName("the plain word, both endings, and plurals")
    void theWordItself() {
        assertTrue(SlurFilter.containsBlockedWord("nigger"));
        assertTrue(SlurFilter.containsBlockedWord("NIGGER"));
        assertTrue(SlurFilter.containsBlockedWord("nigga"));
        assertTrue(SlurFilter.containsBlockedWord("niggers"));
        assertTrue(SlurFilter.containsBlockedWord("niggaz"));
        assertTrue(SlurFilter.containsBlockedWord("say hello nigger ok"));
    }

    @Test
    @DisplayName("leetspeak is folded before matching")
    void leet() {
        assertTrue(SlurFilter.containsBlockedWord("n1gg3r"));
        assertTrue(SlurFilter.containsBlockedWord("n!gg3r"));
        assertTrue(SlurFilter.containsBlockedWord("n|gg3r"));
        assertTrue(SlurFilter.containsBlockedWord("N1GG4"));
        // '9' folds to 'g', which is the one people reach for after the letters fail
        assertTrue(SlurFilter.containsBlockedWord("ni99er"));
        // 'l' folds to 'i', which is what makes the lowercase-L homoglyph useless
        assertTrue(SlurFilter.containsBlockedWord("nlgger"));
    }

    @Test
    @DisplayName("spacing and punctuation are dropped, so neither hides it")
    void spacing() {
        assertTrue(SlurFilter.containsBlockedWord("n i g g e r"));
        assertTrue(SlurFilter.containsBlockedWord("n.i.g.g.e.r"));
        assertTrue(SlurFilter.containsBlockedWord("n-i-g-g-e-r"));
        assertTrue(SlurFilter.containsBlockedWord("n_i_g_g_e_r"));
        assertTrue(SlurFilter.containsBlockedWord("n/i/g/g/3/r"));
    }

    @Test
    @DisplayName("stretching a letter cannot dodge the natural double-g")
    void repetition() {
        // Runs of three or more collapse to two, so stretching the doubled letter lands
        // back on the stem rather than sliding past it.
        assertTrue(SlurFilter.containsBlockedWord("niggggger"));
        assertTrue(SlurFilter.containsBlockedWord("nnnniggerrrr"));
        assertTrue(SlurFilter.containsBlockedWord("niggerrrrrr"));
    }

    @Test
    @DisplayName("what the collapse does NOT catch, written down rather than assumed")
    void theKnownGaps() {
        // Collapsing 3+ down to TWO is what protects the double g — and it is also what
        // lets a stretched SINGLE letter through, because "ii" is not "i". This is a real
        // gap in the ported filter and it is recorded here rather than discovered later
        // by somebody who thought the test suite proved otherwise.
        assertFalse(SlurFilter.containsBlockedWord("niiigger"));

        // And the other direction: dropping punctuation means any word that happens to
        // CONTAIN the stem trips. "snigger" is the one real word that does. Nobody has
        // ever needed it in a nickname, so the cost is accepted — but it is a cost, and
        // widening the stem to fix it would reopen Niger.
        assertTrue(SlurFilter.containsBlockedWord("snigger"));
    }

    @Test
    @DisplayName("Niger and Nigerian do NOT trip it")
    void theFalsePositiveThatMatters() {
        assertFalse(SlurFilter.containsBlockedWord("Niger"));
        assertFalse(SlurFilter.containsBlockedWord("Nigerian"));
        assertFalse(SlurFilter.containsBlockedWord("Nigeria"));
        assertFalse(SlurFilter.containsBlockedWord("the Niger delta"));
        // One 'g'. The whole design of the stem is that this is the discriminator.
        assertFalse(SlurFilter.containsBlockedWord("niger"));
    }

    @Test
    @DisplayName("ordinary words and empty input are left alone")
    void ordinaryText() {
        assertFalse(SlurFilter.containsBlockedWord(null));
        assertFalse(SlurFilter.containsBlockedWord(""));
        assertFalse(SlurFilter.containsBlockedWord("noise calls, heat keeps"));
        assertFalse(SlurFilter.containsBlockedWord("bigger"));
        assertFalse(SlurFilter.containsBlockedWord("digger"));
    }
}
