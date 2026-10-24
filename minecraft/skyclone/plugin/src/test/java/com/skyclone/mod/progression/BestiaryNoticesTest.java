package com.skyclone.mod.progression;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wave-5 regression: a burst of Bestiary tier-ups is one box, not ten. */
class BestiaryNoticesTest {

    private static BestiaryNotices.TierUp up(String key, int from, int to) {
        return new BestiaryNotices.TierUp(key, key.substring(0, 1).toUpperCase() + key.substring(1), from, to);
    }

    @Test
    void sameFamilyMergesIntoOneSpan() {
        List<BestiaryNotices.TierUp> l = new ArrayList<>();
        BestiaryNotices.merge(l, up("zombie", 1, 2));
        BestiaryNotices.merge(l, up("zombie", 2, 4));
        assertEquals(1, l.size());
        assertEquals(1, l.get(0).from());
        assertEquals(4, l.get(0).to());
        assertEquals(3, BestiaryNotices.totalTiers(l));
    }

    @Test
    void noGainIsIgnored() {
        List<BestiaryNotices.TierUp> l = new ArrayList<>();
        BestiaryNotices.merge(l, up("bat", 2, 2));
        assertTrue(l.isEmpty());
    }

    @Test
    void tenFamiliesFitOneShortBox() {
        List<BestiaryNotices.TierUp> l = new ArrayList<>();
        for (int i = 0; i < 10; i++) BestiaryNotices.merge(l, up("fam" + i, i % 2, i % 2 + 1));
        List<String> body = BestiaryNotices.body(l, 1, 3, 4);
        // 4 family lines (3+3+3+1), REWARDS, XP, bonuses
        assertEquals(7, body.size());
        assertEquals("  &8+&b10 SkyBlock XP", body.get(5));
        assertTrue(BestiaryNotices.headline(l).contains("10 families"));
        assertTrue(BestiaryNotices.headline(l).contains("5 new"));
    }

    @Test
    void longBurstSaysHowManyMore() {
        List<BestiaryNotices.TierUp> l = new ArrayList<>();
        for (int i = 0; i < 20; i++) BestiaryNotices.merge(l, up("fam" + i, 0, 1));
        List<String> body = BestiaryNotices.body(l, 1, 3, 4);
        assertTrue(body.get(4).contains("and 8 more"), body.get(4));
    }

    @Test
    void romanAndZero() {
        assertEquals("0", BestiaryNotices.roman(0));
        assertEquals("XIV", BestiaryNotices.roman(14));
        assertTrue(BestiaryNotices.entry(up("ghoul", 0, 1)).endsWith("0➜&bI"));
    }
}
